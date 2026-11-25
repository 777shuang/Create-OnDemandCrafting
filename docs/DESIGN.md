# On-Demand Crafting: Design Direction

> Status: Accepted design direction; implementation has not started.
>
> This document records the current architectural decisions for Create-OnDemandCrafting.
> It is a design baseline, not a claim that every listed behavior is already implemented.

## 1. Goals

- Allow on-demand production to satisfy requests from Stock Keeper, Redstone Requester, and other request sources where integration is technically possible.
- Keep request-source-specific behavior out of the crafting planner and executor.
- Reuse Create's existing Factory Panels and logistics/package transport rather than implementing a second logistics system.
- Correctly account for multiple logistics networks, existing promises, planned production, and concurrent requests.
- Make the planning core testable without launching Minecraft.

## 2. Core architecture

Separate the system into the following responsibilities:

1. **Demand adapters / intake** — translate source-specific requests and policies into a common request model.
2. **Demand coordinator** — deduplicate and coordinate request preparation, acceptance, reservation, and execution.
3. **Panel registry** — index eligible Factory Panels and maintain their lifecycle/network membership.
4. **Demand planner** — calculate a side-effect-free production plan from a request and a consistent snapshot.
5. **Demand executor** — validate and commit a prepared plan, then dispatch work through Create.
6. **Demand tracker** — track outstanding work, completion, failure, expiration, and retry/reconciliation.

Suggested package layout (names may evolve during implementation):

```text
content/
  demand/
    DemandRequest
    DemandItem
    DemandOrigin
    FulfillmentPolicy
    DemandPlan
    DemandPlanner
    DemandCoordinator
    DemandExecutor
    DemandTracker
  logistics/
    PanelRegistry
  integration/create/
    CreateDemandIntegration
    StockKeeperAdapter
    RedstoneRequesterAdapter
    FactoryPanelIntegration
```

Adapters are explicit integrations. This architecture does not imply automatic compatibility with arbitrary third-party mods; each external source needs a supported API or a dedicated adapter.

## 3. Common request model

Normalize requests before planning. The common model should contain at least:

- destination / target logistics network;
- requested item keys and quantities;
- origin (for example Stock Keeper, Redstone Requester, ComputerCraft, or another integration);
- Create request type where applicable;
- fulfillment policy;
- target address and other execution metadata where required;
- a stable request identity or deduplication key.

Keep `DemandOrigin` separate from Create's `RequestType`. The former describes who initiated the demand; the latter describes Create's logistics semantics.

### Fulfillment policy

Respect the semantics and settings of the originating request source:

- `ALL_OR_NOTHING`: accept only if the full requested quantity can be fulfilled.
- `ALLOW_PARTIAL`: accept the fulfillable portion and leave the remainder unaccepted / explicitly outstanding according to the source's semantics.

Fulfillability includes both existing stock and feasible on-demand production, not only the current physical inventory. For example, if 10 are requested, 3 are in stock, and 5 can be produced, an all-or-nothing request is not accepted; a partial request may accept 8.

Do not silently override a source's partial-request setting.

## 4. Request-source integrations

### Stock Keeper

Keep the current UI affordances that allow configured on-demand items to be selected even when their current stock is zero. Route the resulting request through the common intake and planning path.

### Redstone Requester

Integrate at the request-source boundary so that the normal stock-only early rejection does not prevent a feasible on-demand request from reaching the common coordinator.

Preserve the requester's partial-request semantics:
- when partial requests are disabled, require the full request to be fulfillable by stock plus planned production;
- when enabled, accept only the fulfillable portion.

Do not require users to enable Create's partial-request option merely to use on-demand crafting. The adapter should bridge the semantics without changing the user's configured policy.

### Other sources

Design the adapter contract so other integrations can be added without changing the planner. ComputerCraft and other mods are examples, not guaranteed built-in integrations. Support requires a reliable way to observe/intercept the request and preserve its acceptance and execution semantics.

### Factory Panels

A Factory Panel's ordinary material replenishment is not automatically a new external demand. Its material requirements are dependencies of a parent production plan and must be resolved by the planner. Avoid registering the same material demand a second time through a generic logistics hook.

If Factory Panel-originated requests are later treated as independent top-level demands, add an explicit integration with clear recursion and deduplication rules.

## 5. Create integration boundaries

Use the highest-level reliable request boundary that preserves acceptance semantics. In particular:

- `LogisticsManager.broadcastPackageRequest` is a useful common boundary for request paths that actually call it (including the standard Stock Keeper and Redstone Requester flows).
- It is not a universal hook for every logistics operation. Factory Panel replenishment can call lower-level packager lookup / dispatch methods directly and bypass it.
- Do not put planning side effects in generic lookup methods such as `findPackagersForRequest`; those methods may be used for probing or preflight checks.
- Do not start production merely because a request was observed before Create has accepted it.
- Separate read-only preparation from commit. Handle rejection, busy packagers, changed inventory, and duplicate delivery without leaving orphaned reservations or production orders.

Mixin choice and exact injection points must be verified against the pinned Create version during implementation. Prefer narrow source-specific adapters plus a shared coordinator over a broad hook with hidden side effects.

## 6. Demand planning and inventory model

The planner must be side-effect-free: it reads a consistent snapshot and returns a plan or a structured failure. It must not mutate Factory Panels or reserve inventory while recursively exploring.

Inventory and producer identity must include the logistics network:

`NetworkItemKey = (network UUID, ItemKey)`

Do not merge identical items from different networks. Each ingredient is resolved using the network of the panel that supplies that ingredient, which may differ from the output network of its consumer.

Account separately for:

- **Actual Stock** — physical stock currently available in a network;
- **Promised Stock** — outputs already promised by Create / existing in-flight work;
- **Planned Stock** — outputs allocated by outstanding on-demand plans, including useful batch surplus.

Use checked or saturating quantity arithmetic and guard against recursion cycles and excessive planning depth. Aggregate repeated ingredient demands before planning where possible.

A plan must report structured outcomes, including at least:
- success;
- insufficient / partially fulfillable demand;
- missing producer;
- unavailable network or producer;
- circular dependency;
- ambiguous producer;
- stale snapshot / failed commit validation.

Failures in a child dependency must propagate to the parent. Never commit a parent plan whose required dependencies were not resolved.

## 7. Producer selection

Index eligible Factory Panels by output network and output item.

If more than one eligible panel can produce the same item in the same network:
- do not select an arbitrary first element or depend on hash/set iteration order;
- if an explicit priority is configured, use that priority;
- until priority configuration exists, report an ambiguous-producer failure.

Distributing production across multiple panels is intentionally out of scope for the initial implementation, but the registry and plan model should not make it impossible later.

## 8. Reservations, execution, and tracking

Planning and committing are separate phases.

1. Prepare a plan against a consistent snapshot.
2. Validate that relevant networks, panels, inventory, and promises have not changed incompatibly.
3. Atomically (or with explicit rollback) reserve the plan's inputs and planned outputs.
4. Dispatch the required work through existing Create mechanisms.
5. Track promised outputs and state transitions.
6. Reconcile completion, failure, timeout, unload, and retry.

Outstanding plans must share reservation information so two requests cannot independently consume the same stock or schedule the same intermediate surplus twice.

Track at least the distinction between planned, reserved, dispatched, promised, completed, failed, and expired work. A failure or timeout must release or restore the appropriate reservations and must not silently lose the original demand.

Minecraft server-thread execution may provide serialization for some operations, but it does not remove the need for snapshot validation, deduplication, and rollback around Create acceptance/dispatch boundaries.

## 9. Panel registry lifecycle

The registry must follow Factory Panel lifecycle and current network membership, including:

- panel added / removed;
- network UUID changed;
- block entity invalidated or replaced;
- chunk load / unload;
- panel enabled / disabled or otherwise made ineligible.

Do not rely solely on a one-time "registered" boolean. Ensure stale entries are removed and a panel is indexed under its current network.

## 10. Security and synchronization

All configuration changes initiated by clients must be validated server-side. Validate the target block entity, player distance, interaction permission for the relevant logistics network, slot/index bounds, and current panel state before mutation. Synchronize the authoritative result back to clients.

## 11. Accepted product decisions

These decisions are accepted for the initial design:

1. **Request-source coverage:** design for broad extensibility; implement explicit adapters for sources that can be integrated reliably. Stock Keeper and Redstone Requester are the first required sources.
2. **Partial fulfillment:** respect each request source's own policy and settings. Do not globally force partial acceptance.
3. **Multiple producers:** if no explicit priority exists, fail as ambiguous. Do not choose arbitrarily and do not distribute across panels in the initial version.

## 12. Suggested implementation sequence

1. Define the common request, origin, policy, plan, and failure models.
2. Implement the adapter contract and coordinator, initially wiring Stock Keeper and Redstone Requester.
3. Implement a network-aware Panel Registry and lifecycle handling.
4. Implement the pure Demand Planner and comprehensive unit tests.
5. Add shared reservations and commit validation.
6. Implement the executor and Create dispatch integration.
7. Add tracking, reconciliation, failure recovery, and retries.
8. Add further request-source adapters as concrete integrations become available.

## 13. Minimum test scenarios

- Stock Keeper request with zero physical stock but a valid producer.
- Redstone Requester with partial requests disabled and enabled.
- Same request arriving twice.
- Two concurrent demands competing for the same stock.
- Two demands reusing batch surplus from an outstanding plan.
- Ingredient supplied by a different logistics network.
- Missing producer and unavailable network.
- Cyclic recipe dependency.
- Multiple producers with no configured priority.
- Panel network change, unload, reload, disable, and removal.
- Create rejects or cannot dispatch a request after preparation.
- Failure / timeout releases reservations and preserves outstanding demand.
- Invalid client packet (wrong distance, permission, position, or slot).

---

This document is the current accepted design baseline. Implementation details, Create injection points, and third-party integrations must be verified against the exact Create dependency version before coding.
