# AGENTS.md

## Project Overview

**Create: On Demand Crafting** (`create_odc`) — a NeoForge mod that adds on-demand auto-crafting to Create's logistics
network: items are crafted only when requested through the network, instead of always keeping a stock.

Base package: `com.github.shuang777.createondemandcrafting` (matches `mod_group_id` in `gradle.properties`).

## Project Structure

Two Gradle projects:

- **Root (`Create-OnDemandCrafting/`)** — builds the `create_odc` mod. Depends on Create via **Maven**
  (see `repositories` / `dependencies` in `build.gradle`), not via the local submodule.
- **`Create/`** — git submodule of Create, checked out detached (see `git -C Create describe --tags`). Reference
  source for mixins only; not part of the root build.

**Entrypoints**: `CreateOnDemandCrafting` (root, `@Mod("create_odc")`), `CreateOnDemandCraftingClient` (root, `@Mod`
with `dist = Dist.CLIENT`).

### Source layout (root)

- `content/logistics/OnDemandCraftingManager` — core registry/logic: tracks on-demand `FactoryPanelBehaviour`s per
  network UUID (`WeakReference` set, self-cleaning of removed entries); resolves craftable requests against
  `InventorySummary` / `LogisticsManager` / `PackageOrderWithCrafts`.
- `foundation/mixinInterfaces/` — duck interfaces: `IOnDemandPanel` (flag + order counting), `IOnDemandBlockEntity`
  (per-`PanelSlot` flag).
- `mixin/` — Create class mixins (see below).
- `network/` — `ModPackets` (registers play-to-server payloads), `SetOnDemandPayload` (payload id
  `create_odc:set_on_demand`; carries `BlockPos` + `PanelSlot` + boolean, applies server-side via
  `IOnDemandPanel` + `notifyUpdate`).
- `Config.java` — empty COMMON config spec (registered in mod constructor).
- `src/main/templates/META-INF/neoforge.mods.toml` — template processed by the `generateModMetadata` task in
  `build.gradle` (placeholders expanded from `gradle.properties`).

## Build & Development Commands

- **Build `create_odc` mod**: `./gradlew build` (from repo root)
- **Run game tests**: `./gradlew runGameTestServer` (from `Create/`; Create registers gametests). The root project has
  the run config but currently registers **no** gametests — the game test server exits/crashes with none.
- **Format**: Spotless is configured in `build.gradle` (`spotless { java { ... } }`); run the Spotless task before
  committing (e.g. `./gradlew spotlessApply`)
- **Init submodule**: `git submodule update --init` (root CI does *not* check out submodules)

## Versions & Configuration Lookup

Do not hardcode dependency versions in this file. Resolve them from:

- `gradle.properties` — mod coordinates (`mod_id`, `mod_name`, `mod_license`, `mod_version`, `mod_group_id`) and
  version catalog values (Minecraft, NeoForge, Parchment, Create, Ponder, Flywheel, Registrate, version ranges).
- `build.gradle` — Java toolchain, ModDevGradle/plugin setup, `repositories` (Create/Ponder/Flywheel/Registrate,
  CurseMaven), `dependencies` (including `localRuntime` dev-only mods), run configs, `generateModMetadata` wiring.
- `gradle/wrapper/gradle-wrapper.properties` (root and `Create/`) — the Gradle wrapper used by each project.
- `src/main/templates/META-INF/neoforge.mods.toml` — runtime dependency declarations (`create`, `neoforge`,
  `minecraft`) and how ranges map to `gradle.properties` placeholders.

## Mixins

Config: `src/main/resources/create_odc.mixins.json` (package `...mixin`, `JAVA_21` compatibility, `defaultRequire: 1`).

- common: `FactoryPanelBehaviourMixin` (persists on-demand flag/orders to NBT, registers/unregisters with
  `OnDemandCraftingManager`, suppresses restock ticks while on-demand), `FactoryPanelBlockEntityMixin`,
  `InventorySummaryMixin`, `LogisticsManagerMixin`
- client: `FactoryPanelScreenMixin` (toggle button + `SetOnDemandPayload` send), `StockKeeperRequestScreenMixin`

Mixin targets live in the `Create/` submodule — read the target source there before editing an injector.

## Run Configurations

Defined in `build.gradle` (`neoForge { runs { ... } }`): `client`, `server` (`--nogui`), `gameTestServer`, `data`.
Shared settings: console log level DEBUG, `forge.logging.markers=REGISTRIES`, gametest namespace gated by
`neoforge.enabledGameTestNamespaces=<mod_id>`. The `data` run passes `--mod <mod_id> --all --output
src/generated/resources/ --existing src/main/resources/`.

`src/main/resources` also holds `assets/create_odc/lang/` (`en_us.json`, `ja_jp.json`). Datagen cache (`**/.cache`)
and BlockBench files (`**/*.bbmodel`) are excluded from the final jar (see `sourceSets.main.resources`).

## Important Gotchas

- **Two `gradlew` wrappers** (root and `Create/`) can differ. Always use the wrapper of the project you intend to
  build, and check its `gradle/wrapper/gradle-wrapper.properties` when versions matter.
- Root `build.gradle` hooks `generateModMetadata` into `neoForge.ideSyncTask`, so mod metadata regenerates on IDE
  sync; template placeholders must stay in sync with `gradle.properties` keys.
