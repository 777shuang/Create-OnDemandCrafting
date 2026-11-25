# On-Demand Crafting: Implementation Plan

> Target branch: dev
>
> Status: Planning baseline; implementation has not started against the accepted architecture.
>
> This plan deliberately divides work into independently reviewable phases. Complete and verify one phase before starting the next; do not ask an implementation agent to build the entire system in one change.

## 1. Implementation principles

- Implement the architecture in docs/DESIGN.md, not by extending the current OnDemandCraftingManager in place.
- Keep each phase small enough to compile, test, and review independently.
- Every phase must end with a focused test run and a commit. If a phase changes Mixin behavior, include a client or dedicated-server smoke test.
- Prefer ordinary Java interfaces and immutable value objects over a large framework or generic event bus.
- Keep source-specific behavior in adapters. Keep Minecraft / Create access behind integration and snapshot-provider boundaries where practical.
- Do not change unrelated UI behavior while implementing the planning core.
- Do not proceed to the next phase while the current phase has failing acceptance tests.

## 2. Phase 0 — Freeze and verify the Create compatibility baseline

**Purpose:** make sure implementation decisions and Mixin targets match the dependency that is actually compiled and run.

Tasks:
1. Resolve the exact Create artifact selected by the root Gradle build (Create 6.0.10-281 from gradle.properties at the time this plan was written).
2. Compare the Create/ submodule commit with the source corresponding to that resolved artifact. The root build depends on the Maven artifact; the submodule is a source reference and does not supply the root compile dependency.
3. Record the exact target signatures and call paths for:
   - Stock Keeper request dispatch through StockTickerBlockEntity / StockTickerInteractionHandler;
   - RedstoneRequesterBlockEntity.triggerRequest and its allowPartialRequests precheck;
   - FactoryPanelBehaviour.tickRequests, including multi-network ingredient dispatch and output promise creation;
   - LogisticsManager.broadcastPackageRequest and its busy-packager rejection behavior;
   - panel initialize, enable, disable, setNetwork, unload, and removal lifecycle.
4. Run the existing root build and record any baseline failures before changing behavior.
5. Add or document the test infrastructure needed for pure unit tests and at least one mod integration test.

Acceptance criteria:
- The exact runtime Create artifact and matching source revision are recorded.
- Every planned Mixin target is verified against that source revision, not inferred from a different Create branch.
- The unmodified project builds, or pre-existing failures are documented.
- There is a repeatable test command for domain tests and a working route for Mixin / game integration tests.

## 3. Phase 1 — Introduce the demand domain model

**Purpose:** define the API between adapters, planner, coordinator, and executor without implementing production behavior.

Implement:
- DemandId and DemandOrigin;
- immutable item identity preserving item components;
- DemandLine and DemandRequest;
- FulfillmentPolicy;
- panel identity / producer description;
- DemandPlan and per-item fulfillment result;
- structured PlanFailure / PlanResult types;
- serializable request metadata separated from ephemeral Create dispatch context.

Rules:
- Do not use live FactoryPanelBehaviour, BlockEntity, Level, or inventory references as domain identity.
- Keep DemandOrigin separate from Create RequestType.
- Preserve request line order for deterministic partial allocation.
- An adapter-generated ID is unique per invocation when the source supplies no stable ID. Retries reuse the same ID; identical new invocations get new IDs.
- Make quantities validated positive integers and use safe arithmetic helpers.

Tests:
- value equality and hashing for item identity, including differing data components;
- request aggregation while preserving deterministic order;
- ID / retry semantics;
- ALL_OR_NOTHING and ALLOW_PARTIAL result representation;
- overflow and invalid-quantity handling.

Acceptance criteria:
- The domain model compiles without adding behavior to Factory Panel Mixin code.
- Domain tests run without launching Minecraft.
- The public model is sufficient to represent both Stock Keeper and Redstone Requester requests.

## 4. Phase 2 — Build the Panel Registry and immutable snapshot provider

**Purpose:** separate producer discovery and inventory observation from planning.

Implement:
1. PanelRegistry indexed by output network and output item.
2. Explicit lifecycle registration and removal for initialization, enable / disable, destroy, unload, network changes, and eligibility changes.
3. Deterministic candidate enumeration; never choose a producer by collection iteration order.
4. DemandSnapshot and a Create-backed snapshot provider.
5. NetworkItemKey-based inventory, promise, reservation, and producer lookup.

Snapshot requirements:
- Capture actual stock for each relevant network.
- Capture eligible Create promises without mutating the PromiseQueue from planner code.
- Include the current reservation-ledger version and already reserved quantities.
- Keep output network separate from each ingredient source panel's network.
- Respect the request address and ignored-inventory context when determining stock usable by a top-level request.
- For Factory Panel dependencies, use the consumer's recipe address and each source panel's network.

Tests:
- same item in different networks remains distinct;
- one producer is found only in its output network;
- duplicate producers are both returned in deterministic order;
- panel network changes remove the old index entry;
- inactive, disabled, restocker, removed, or unloaded panels are excluded;
- snapshot reads are immutable and do not expire / remove Create promises as a side effect.

Acceptance criteria:
- Existing recursive planning code is not used by the registry or snapshot provider.
- Panel changes cannot leave an eligible panel indexed under its previous network.
- Snapshot tests cover at least two networks and an existing promise.

## 5. Phase 3 — Implement the pure Demand Planner

**Purpose:** turn one request and one snapshot into a complete plan or a structured failure.

Implement the planner as a side-effect-free service. It must not mutate Factory Panels, inventory summaries, Create promises, registries, or reservations.

Planning algorithm:
1. Normalize and aggregate repeated request lines while preserving source order.
2. Allocate eligible actual stock and existing promised stock.
3. Resolve each remaining quantity against a producer in the requested output network.
4. Compute required batch count with checked arithmetic.
5. Resolve every ingredient using the connected source panel's output network, not the parent panel's network.
6. Reuse planned batch surplus across the current plan.
7. Propagate child failures to parent nodes.
8. Return per-item fulfillment quantities and a stable dependency order.

Failure cases:
- no producer;
- ambiguous producer;
- unavailable / invalid panel or network;
- circular dependency;
- maximum depth exceeded;
- insufficient quantity;
- invalid or overflowing recipe quantity.

Tests:
- simple one-panel production;
- multiple recipe levels;
- recipe with ingredients from two different networks;
- existing stock satisfies all / part / none of a request;
- existing promises satisfy part of a request;
- batch surplus reused by another branch;
- repeated ingredient lines aggregated;
- cycle, missing producer, and ambiguous producer;
- child failure prevents parent plan creation;
- arithmetic overflow is reported, not wrapped.

Acceptance criteria:
- Planner output is deterministic for the same request and snapshot.
- Planner has no externally visible side effects.
- No parent production is returned as executable when a required child is unresolved.
- Unit tests cover the minimum planner cases without launching the game.

## 6. Phase 4 — Add coordinator, reservations, and persistent tracking

**Purpose:** make multiple accepted requests safe across ticks and server restarts.

Implement a server-thread DemandCoordinator with operations equivalent to:
- prepare(request): capture snapshot and produce a tentative plan;
- reserve(preparedPlan): tentatively allocate stock, promises, and planned outputs;
- commit(reservation): mark accepted work and enqueue its production jobs;
- rollback(reservation): release uncommitted allocations;
- reconcile / complete / fail / expire: update accepted obligations.

Implement:
- a shared reservation ledger keyed by NetworkItemKey;
- request-ID idempotency for retries;
- a DemandTracker with per-request plan nodes and lifecycle states;
- server-level SavedData persistence for accepted obligations and reservations;
- deterministic recovery after world load.

Important rules:
- A failed source dispatch must roll back tentative reservations and must not enqueue production.
- A failed child job must leave its parent demand outstanding or failed with an explicit reason; it must not silently disappear.
- Tracker records are authoritative. Any integer pending-order value on a panel is only a derived execution aid and is rebuilt from tracker state.
- Do not assume Create RequestPromise objects contain this mod's request ID. Track obligations by network and item key and reconcile quantities as output arrives or promises expire.
- All state mutation runs on the server thread. Keep planner code pure.

Tests:
- two demands cannot reserve the same stock;
- two demands cannot reserve the same planned surplus twice;
- retrying the same ID does not duplicate jobs;
- two distinct identical requests remain distinct;
- rollback restores every tentative allocation;
- accepted work survives serialize / deserialize;
- load reconciliation does not replay a job already promised or completed;
- expired work releases reservations and remains retryable or explicitly failed.

Acceptance criteria:
- Reservations are shared across requests and ticks.
- The tracker is the single authoritative record of accepted demand.
- A save / reload test demonstrates no lost or duplicated demand.

## 7. Phase 5 — Integrate Stock Keeper as the first vertical slice

**Purpose:** deliver one complete user-visible flow before adding the second source.

Verified Create path:
- StockTickerInteractionHandler sends the request with RequestType.PLAYER.
- StockTickerBlockEntity.broadcastPackageRequest delegates to the StockCheckingBlockEntity / LogisticsManager dispatch path.

Implement StockKeeperAdapter:
1. Build a DemandRequest using the Stock Keeper network, order, address, and invocation identity.
2. Prepare and tentatively reserve before package dispatch.
3. Allow Create to dispatch its physically available stock using its existing path.
4. Commit on-demand production only if the Create dispatch is accepted.
5. Roll back if dispatch is rejected, for example because a packager is too busy.
6. Preserve existing Stock Keeper UI and its partial-dispatch behavior.

Do not implement this by adding planning side effects to LogisticsManager.broadcastPackageRequest HEAD. Keep the adapter at the Stock Keeper source boundary.

Tests:
- zero stock with one eligible producer;
- mixed physical stock and production;
- multiple recipe levels;
- partial production possible;
- no producer;
- ambiguous producer;
- Create dispatch rejected because a packager is busy;
- repeated independent user requests;
- no duplicate trigger through the lower-level Create dispatch method.

Acceptance criteria:
- Stock Keeper can request an item whose physical count is zero and receive on-demand production.
- Existing physical stock is not counted twice.
- Rejected dispatch leaves no reservation or queued production.
- Existing Stock Keeper UI remains functional.

## 8. Phase 6 — Integrate Redstone Requester

**Purpose:** support the request source whose native acceptance check occurs before the shared dispatch boundary.

Implement RedstoneRequesterAdapter at or before triggerRequest's inventory-only precheck.

For allowPartialRequests = false:
- evaluate the complete demand using actual stock, eligible promises, and feasible planned production;
- if the complete request is not feasible, reject without dispatching any stock or production;
- if feasible, allow Create's normal stock dispatch and commit the production remainder only when accepted.

For allowPartialRequests = true:
- compute the feasible partial quantity using deterministic source order;
- dispatch existing physical stock through Create;
- commit only the feasible on-demand remainder;
- report the combined result through Redstone Requester's status and effect.

Avoid running the same demand through Stock Keeper / generic dispatch integration a second time. The adapter must own or explicitly scope the request transaction around the native trigger flow.

Tests:
- all-or-nothing request rejected when stock is insufficient and no producer exists;
- all-or-nothing request accepted when stock plus production covers the full quantity;
- partial request accepted for only the feasible quantity;
- multiple request lines with shared ingredients;
- packager busy rejection rolls back the plan;
- lastRequestSucceeded and requester effect reflect combined fulfillment;
- no request is started twice.

Acceptance criteria:
- The stock-only early return no longer blocks a fully producible all-or-nothing request.
- allowPartialRequests remains authoritative.
- The feature does not require changing Create's global partial-request configuration.

## 9. Phase 7 — Harden lifecycle, synchronization, and recovery

Implement and verify:
- SetOnDemandPayload slot decoding that rejects invalid values safely;
- server-side block entity, active panel, player-distance, and logistics permission checks;
- authoritative client synchronization after server acceptance;
- registry behavior for network changes, panel disable / removal, chunk unload / reload, filter and connection changes;
- stale snapshot validation at commit;
- retry, expiration, and recovery paths;
- dedicated-server startup and world save / reload integration tests.

Acceptance criteria:
- Invalid client packets cannot mutate a panel or crash payload decoding.
- A panel cannot remain indexed after its network or lifecycle changes.
- A world reload restores only valid outstanding work.
- Dedicated server startup and the relevant integration tests pass.

## 10. Phase 8 — Additional adapters and deferred features

Only after the first two adapters are stable:
- add ComputerCraft or other third-party adapters when their APIs and acceptance semantics are verified;
- consider configurable producer priority;
- consider splitting one output demand across multiple producer panels.

These are not prerequisites for the initial release and must not complicate the first planner implementation.

## 11. Verification commands and workflow

Run focused checks after each phase:

- Java formatting: ./gradlew spotlessApply
- Root build: ./gradlew build
- Domain unit tests: ./gradlew test
- Game integration tests: ./gradlew runGameTestServer after registering at least one test in this mod's namespace

The current root project does not register its own GameTests. Do not use runGameTestServer as evidence of integration coverage until the mod has registered tests; configure the test setup in Phase 0 or Phase 7.

Suggested commit boundaries:

1. verify Create compatibility and test setup;
2. add domain model;
3. add registry and snapshot provider;
4. add planner and unit tests;
5. add coordinator, reservations, and persistence;
6. integrate Stock Keeper;
7. integrate Redstone Requester;
8. lifecycle / security / recovery hardening.

Keep each change focused. After every phase, review the diff against docs/DESIGN.md, run the relevant tests, and only then start the next phase.

## 12. Completion criteria for the initial release

The initial implementation is ready only when:

- Stock Keeper and Redstone Requester both use the common request model and coordinator;
- Redstone partial and all-or-nothing behavior is preserved;
- recipes can use ingredients from different logistics networks;
- actual stock, Create promises, and outstanding on-demand reservations are not double-counted;
- ambiguous producers, missing ingredients, and cycles fail explicitly;
- a dispatch rejection leaves no orphaned production;
- panel lifecycle and client packet validation are covered;
- accepted demand survives restart without being lost or duplicated;
- domain tests, integration tests, formatting, and the root build pass.
