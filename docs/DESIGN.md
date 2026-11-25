# On-Demand Crafting: Design Direction

> Status: Accepted target architecture.
>
> The repository contains an earlier implementation prototype. That code is not yet an implementation of this target architecture and must be treated as legacy code during migration.
>
> The target is intentionally independent of any one request source. Stock Keeper and Redstone Requester are the first required adapters.

## 1. Goals

- Allow on-demand production to satisfy requests from Stock Keeper, Redstone Requester, and other request sources where integration is technically possible.
- Keep request-source-specific behavior out of the crafting planner and executor.
- Reuse Create's Factory Panels, logistics networks, package transport, and promise tracking rather than implementing a second logistics system.
- Correctly account for multiple logistics networks, existing promises, planned production, batch surplus, and competing requests.
- Make the planning core deterministic and testable without launching a Minecraft client or server.
- Preserve request semantics: a source's partial-request setting must not be silently changed.

## 2. Architectural boundaries

The system is divided into the following responsibilities:

1. **Demand adapters / intake** — capture requests at source-specific boundaries and translate source policy and metadata into the common model.
2. **Demand coordinator** — own request identity, deduplication, preparation, reservations, acceptance, and state transitions.
3. **Panel registry** — index eligible Factory Panels by output network and output item, and maintain their lifecycle.
4. **Snapshot provider** — collect immutable, network-aware views of actual stock, Create promises, and existing on-demand reservations.
5. **Demand planner** — calculate a side-effect-free production plan from a request and one consistent snapshot.
6. **Demand executor** — validate and commit a prepared plan, then schedule work through the existing Factory Panel behavior.
7. **Demand tracker** — retain accepted obligations and reconcile production, delivery, failure, expiration, reload, and retry.

Suggested package layout (names may evolve during implementation):

    content/
      demand/
        model/
        planning/
        runtime/
        persistence/
      logistics/
        PanelRegistry
      integration/
        create/
          CreateDemandIntegration
          StockKeeperAdapter
          RedstoneRequesterAdapter
          FactoryPanelIntegration

Adapters are explicit integrations. This architecture does not imply automatic compatibility with arbitrary third-party mods; each external source needs a supported API or a dedicated adapter.

## 3. Common request model

Normalize requests before planning. The serializable request model should contain at least:

- a unique demand ID;
- origin (for example Stock Keeper, Redstone Requester, ComputerCraft, or another integration);
- source identity and position where available;
- destination / target logistics network;
- requested item keys and quantities;
- fulfillment policy;
- target address and other serializable execution metadata where required.

Keep the following concepts separate:

- **DemandOrigin** describes who initiated the demand.
- **Create RequestType** describes Create's logistics dispatch semantics.
- **FulfillmentPolicy** describes whether the source accepts only full fulfillment or permits partial fulfillment.

The adapter's short-lived dispatch context may also contain Create-specific values such as an ignored inventory. Do not serialize live block-entity or inventory references into persistent demand records.

### Request identity and deduplication

Prefer an ID supplied by the source. When a source provides no stable ID, assign a fresh ID to each accepted invocation and use it to prevent duplicate processing of that invocation. Do not deduplicate solely by hashing origin, item list, and quantity: two intentionally repeated identical requests are two distinct demands.

Retries of an already accepted demand must reuse its original ID. Repeated requests created by separate user actions must not be collapsed.

### Fulfillment policy

- **ALL_OR_NOTHING** — accept only if the complete requested quantity can be fulfilled by dispatchable actual stock, eligible Create promises, and feasible on-demand production.
- **ALLOW_PARTIAL** — accept only the quantity that can actually be fulfilled; preserve the source's deterministic item ordering when allocating a partial request.

A failed all-or-nothing preparation must not dispatch the stock portion of the request. For partial requests, the plan must report the accepted quantity per item rather than merely returning a generic success flag.

## 4. Request-source integrations

### Stock Keeper

The Stock Keeper interaction path sends requests through StockTickerBlockEntity with Create RequestType.PLAYER.

Preserve Create's current behavior of dispatching whatever physical stock is available. For the initial adapter, treat the request as partial-capable: on-demand production should cover the feasible remainder, and unavailable quantities must remain explicitly unfulfilled rather than being silently reported as produced.

The adapter must prepare before invoking Create's package dispatch and must commit its production plan only if the source request is accepted by the Create dispatch path. If Create rejects dispatch because a packager is busy, discard the tentative plan and release its reservations.

### Redstone Requester

RedstoneRequesterBlockEntity.triggerRequest performs an inventory-only precheck before it calls broadcastPackageRequest. When allowPartialRequests is false, it returns early if physical stock is insufficient. In that case the shared LogisticsManager boundary is never reached.

Therefore, a LogisticsManager-only hook cannot implement Redstone Requester correctly. Its adapter must run before this stock-only check and evaluate the configured policy against actual stock, eligible promises, and feasible on-demand production.

- When partial requests are disabled, accept only if the entire request is fulfillable.
- When partial requests are enabled, accept the feasible portion and report the accepted amount.
- Do not require users to enable Create's partial-request option merely to use on-demand crafting.
- Preserve the requester's success effect and last-request status so they represent the result of the combined stock-plus-production request, not only the pre-existing physical stock.

The adapter must avoid invoking the shared coordinator a second time when it proceeds to Create's normal package dispatch.

### Factory Panels

A Factory Panel's ordinary material replenishment is not automatically a new external demand. Its material requirements are dependencies of a parent production plan and must be resolved by the planner. Do not register the same material demand a second time through a generic logistics hook.

Create's FactoryPanelBehaviour.tickRequests groups input requirements by each source panel's network, checks the relevant network stock, obtains package requests with findPackagersForRequest, checks packager busy state across the requests, dispatches them, and records a promise for the output on the output panel's network. This existing behavior is the execution path to preserve.

If Factory Panel-originated requests are later treated as independent top-level demands, add an explicit integration with clear recursion, origin, and deduplication rules.

### Other sources

Design the adapter contract so other integrations can be added without changing the planner. ComputerCraft and other mods are examples, not guaranteed built-in integrations. Support requires a reliable way to observe/intercept the request and preserve its acceptance, partial-fulfillment, and execution semantics.

## 5. Create integration boundaries

Use the highest-level reliable request boundary that preserves source acceptance semantics.

- Stock Keeper and Redstone Requester require separate source adapters because their acceptance paths differ.
- LogisticsManager.broadcastPackageRequest is a Create package-dispatch boundary, not a universal demand-ingress boundary.
- Do not put planning or reservation side effects in a generic HEAD injection on broadcastPackageRequest. It is called for different RequestType values, and treating every call as an external demand can misclassify internal RESTOCK traffic.
- Explicitly exclude RequestType.RESTOCK from top-level demand intake unless a future, separately designed restocker integration requires it.
- Factory Panel dependency dispatch can call findPackagersForRequest and performPackageRequests directly, bypassing broadcastPackageRequest entirely.
- Do not put planning side effects in generic lookup methods such as findPackagersForRequest; they may be used for probes or preflight checks.
- Do not start production merely because a request was observed before Create has accepted it.
- Separate read-only preparation from commit. Handle rejection, busy packagers, changed inventory, and duplicate delivery without leaving orphaned reservations or production orders.

Mixin choice and exact injection points must be verified against the resolved Create dependency artifact before coding. The Create source submodule is a reference checkout; the root Gradle build resolves Create from Maven, so the submodule commit must not be assumed to be byte-for-byte equivalent to the compiled dependency.

## 6. Demand planning and inventory model

The planner must be side-effect-free: it reads a consistent snapshot and returns a plan or a structured failure. It must not mutate Factory Panels, consume inventory, or reserve anything while recursively exploring.

### Network-aware item identity

Inventory and producer identity must include the logistics network:

    NetworkItemKey = (network UUID, ItemKey)

Do not merge identical items from different networks. An output demand is resolved against the target network. Each ingredient is resolved against the network of the source Factory Panel connected to the consumer. Those networks may differ.

Create already supports a consumer panel whose input panels belong to different networks. The planner and snapshot provider must preserve that behavior instead of assuming one network for an entire recipe tree.

### Stock categories

Account for these quantities separately:

- **Actual Stock** — physical stock currently available for the relevant network and request context.
- **Promised Stock** — outputs already promised by Create or by accepted in-flight work.
- **Reserved Stock** — actual or promised quantities tentatively or definitively allocated to other accepted demands.
- **Planned Stock** — outputs allocated by the plan currently being calculated, including reusable batch surplus.

A stock quantity is usable only when it is eligible for the relevant dispatch. Preserve Create's address and ignored-inventory constraints where applicable. For Factory Panel ingredients, use the consumer panel's recipe address and the source panel's network.

Build promise counts in the snapshot provider. Do not call mutating expiry-and-remove methods from the pure planner. In particular, Create's RequestPromiseQueue.getTotalPromisedAndRemoveExpired changes queue state and must not be used as a supposedly read-only planner query.

The snapshot provider should capture the relevant network summaries, promise counts, registered producers, and reservation-ledger version before planning. Revalidate the relevant versions and panel identities before commit.

### Planning rules

- Aggregate repeated item demands before resolving them, while preserving a deterministic order for partial allocation.
- Subtract stock and existing eligible promises before planning new production.
- Calculate batch counts with checked arithmetic. Guard multiplication, addition, and ceiling division against integer overflow.
- Reuse batch surplus within the same plan and reserve accepted surplus for later plans.
- Guard against recursion cycles and excessive planning depth.
- Propagate every child dependency failure to its parent. Never commit a parent whose required dependencies were not resolved.
- Do not treat an unresolved ingredient as though the parent can still be produced.

A plan must report structured outcomes, including at least:

- success;
- insufficient or partially fulfillable demand, including per-item fulfillable quantity;
- missing producer;
- unavailable network or producer;
- circular dependency;
- ambiguous producer;
- stale snapshot / failed commit validation;
- Create dispatch rejection;
- invalid or expired demand.

## 7. Producer selection

Index eligible Factory Panels by output network and output item.

A candidate must be active, enabled for on-demand production, not a restocker, have a valid output filter and recipe configuration, and have valid ingredient connections.

If more than one eligible panel can produce the same item in the same network:

- do not select an arbitrary first element or depend on hash/set iteration order;
- if an explicit priority is configured, use that priority;
- until priority configuration exists, report an ambiguous-producer failure.

Distributing production across multiple panels is intentionally out of scope for the initial implementation, but the registry and plan model should not make it impossible later.

## 8. Reservations, execution, and tracking

Planning and committing are separate phases:

1. Capture a consistent snapshot and prepare a plan without side effects.
2. Tentatively reserve the stock, promises, and planned outputs needed by the plan.
3. Let the source adapter perform the applicable Create dispatch / acceptance step.
4. If Create rejects the request, release the tentative reservation and do not enqueue production.
5. If accepted, commit the reservation and enqueue the required Factory Panel work.
6. Track promises and observed output delivery; reconcile completion, failure, timeout, unload, reload, and retry.

All reservation and demand-state mutations must execute on the Minecraft server thread. Server-thread serialization prevents interleaving during a synchronous transaction, but shared reservations are still required across requests that remain outstanding over multiple ticks.

The executor should reuse each selected Factory Panel's normal tickRequests behavior to dispatch recipe ingredients through Create. Do not introduce a second package transport path for Factory Panel ingredients. The parent panel may wait and retry until child-panel output has physically arrived in its input network.

Track at least the distinction between prepared, reserved, accepted, queued, dispatched, promised, completed, failed, and expired work. Keep the original demand and a structured failure reason when work cannot proceed. A failed or timed-out job must release or restore the appropriate reservation and must not silently lose the original demand.

The current Create RequestPromiseQueue tracks item quantities and age; it does not carry this mod's demand ID. DemandTracker must not assume that a Create promise can be mapped directly to one originating request. Maintain the mod's own outstanding obligations by network and item key, with deterministic reconciliation as promised quantities enter the network or expire.

Persist accepted demand obligations and their reservation state using server-level SavedData. Treat tracker records as the source of truth. Any per-panel pending count used to wake Factory Panels is a derived execution aid and must be rebuilt or reconciled from tracker state after load; do not let a separately persisted aggregate counter become a second authority.

On load, reconcile tracker records with loaded panels, Create promises, and actual network stock. Release stale reservations, retain retryable outstanding demand, and avoid replaying work already promised or completed.

## 9. Panel registry lifecycle

The registry must follow Factory Panel lifecycle and current network membership, including:

- panel initialized / added;
- panel enabled / disabled;
- network UUID changed;
- block entity invalidated, destroyed, or replaced;
- chunk load / unload;
- panel filter, recipe, or connections changed so that eligibility changes.

Do not rely solely on a one-time registered boolean. FactoryPanelBehaviour.setNetwork changes the network field, so the registry must explicitly remove the old key and add the new key. Ensure stale entries are removed and a panel is indexed under its current network.

Weak references may be used as a safety net, but they are not a substitute for explicit lifecycle updates and reconciliation.

## 10. Security and synchronization

All configuration changes initiated by clients must be validated server-side. Validate:

- payload enum / slot values before indexing arrays;
- that the target chunk is loaded and the block entity is the expected Factory Panel block entity;
- that the selected panel exists, is active, and is eligible for the setting;
- player distance and interaction permission for the relevant logistics network;
- current panel state before mutation.

Synchronize the authoritative result back to clients. Client-side UI changes should not be treated as accepted until the server confirms the change.

## 11. Accepted product decisions

These decisions are accepted for the initial design:

1. **Request-source coverage:** design for broad extensibility; implement explicit adapters for sources that can be integrated reliably. Stock Keeper and Redstone Requester are the first required sources.
2. **Partial fulfillment:** respect each source's own policy. Stock Keeper follows its current partial package-dispatch behavior; Redstone Requester follows allowPartialRequests.
3. **Multiple producers:** if no explicit priority exists, fail as ambiguous. Do not choose arbitrarily and do not distribute across panels in the initial version.
4. **Execution:** reuse Create Factory Panel ingredient dispatch and package transport.
5. **Persistence:** accepted demand obligations and reservations survive server restart and are reconciled with Create's persisted promise state.

## 12. Legacy implementation migration notes

The following existing code is a prototype and must be reviewed rather than carried forward unchanged:

- OnDemandCraftingManager currently combines panel lookup, recursive planning, stock allocation, and order mutation. Split these responsibilities into the registry, snapshot provider, planner, coordinator, executor, and tracker.
- LogisticsManagerMixin currently triggers planning at broadcastPackageRequest HEAD without checking RequestType or waiting for acceptance. Replace this with source-specific adapters; do not keep the generic side-effecting hook.
- The current recursive resolver uses one network and one InventorySummary for the full recipe tree, chooses the first matching panel, and can still append a parent plan after a child failed. Replace it with network-aware planning and structured failure propagation.
- The current on-demand order integer on FactoryPanelBehaviour is not sufficient as the authoritative record for multiple independent demands, reservations, retries, and restart recovery. Replace it with tracker-backed jobs or rebuildable derived counts.
- SetOnDemandPayload currently needs stronger server-side permission, distance, state, and enum-bound validation.
- InventorySummaryMixin and StockKeeperRequestScreenMixin are UI / stock-display integrations; keep them separate from the planning core.

## 13. Initial scope and non-goals

Initial required behavior:

- Stock Keeper requests can trigger on-demand production when physical stock is insufficient.
- Redstone Requester requests can trigger on-demand production while respecting allowPartialRequests.
- Recipe dependencies may draw ingredients from different logistics networks.
- Existing Create package transport, Factory Panel promises, and request-source semantics remain authoritative.
- Requests competing for stock or planned surplus cannot double-allocate it.

Initial non-goals:

- automatic adapters for arbitrary third-party mods;
- distributing a single output demand across multiple producer panels;
- configurable producer priorities in the first release;
- replacing Create's logistics transport or Factory Panel execution system.

## 14. Minimum test scenarios

- Stock Keeper request with zero physical stock and one valid producer.
- Stock Keeper request mixing existing stock and on-demand production.
- Stock Keeper request where only part of the missing quantity is producible.
- Redstone Requester with partial requests disabled: stock-only failure but stock-plus-production success.
- Redstone Requester with partial requests enabled and only partial production possible.
- Create rejects dispatch because a packager is busy; no production order or reservation is left behind.
- Same accepted demand retried with the same ID; no duplicate production.
- Two intentionally separate identical requests; both remain valid demands.
- Two demands competing for the same stock.
- Two demands reusing batch surplus from an outstanding plan without double allocation.
- Ingredient supplied by a different logistics network.
- Promised output from Create and from an existing on-demand job.
- Missing producer and unavailable network.
- Cyclic recipe dependency.
- Multiple producers with no configured priority.
- Panel network change, unload, reload, disable, removal, filter change, and reconnection.
- Stale snapshot / changed panel between prepare and commit.
- Failure / timeout releases reservations and preserves retryable demand.
- Server restart restores and reconciles outstanding demand.
- Invalid client packet (invalid slot, wrong distance, denied permission, wrong position, or inactive panel).

---

The implementation sequence and per-phase acceptance criteria are maintained in docs/IMPLEMENTATION_PLAN.md.