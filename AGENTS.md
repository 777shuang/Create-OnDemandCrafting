# AGENTS.md

## Project Overview

**Create: On Demand Crafting** (`create_odc`) — a NeoForge mod that adds on-demand auto-crafting to Create's logistics network: items can be crafted only when requested through the network, instead of always keeping a stock.

## Project Structure

Two Gradle projects:

- **Root (`Create-OnDemandCrafting/`)** — builds the `create_odc` mod (mod id: `create_odc`, group `com.github.shuang777.createondemandcrafting`). Uses Gradle 9.7.1 (`gradlew` in root). Depends on Create via **Maven** (`com.simibubi.create:create-1.21.1:6.0.10-281`), not via the local submodule.
- **`Create/`** — a **git submodule** of https://github.com/Creators-of-Create/Create (`.gitmodules`), checked out detached at tag `mc1.21.1-6.0.10`. Builds the Create mod itself (mod id: `create`). Uses Gradle 8.14.3 (`gradlew` in `Create/`). Reference source for mixins; not part of the root build.

**Entrypoints**: `CreateOnDemandCrafting` (root, `@Mod("create_odc")`), `CreateOnDemandCraftingClient` (root, `@Mod` with `dist = Dist.CLIENT`).

### Source layout (root)

Package base: `com.github.shuang777.createondemandcrafting`

- `content/logistics/OnDemandCraftingManager` — core registry/logic for on-demand panels
- `foundation/mixinInterfaces/` — `IOnDemandPanel`, `IOnDemandBlockEntity` (duck interfaces)
- `mixin/` — Create class mixins (see below)
- `network/` — `ModPackets`, `SetOnDemandPayload` (play-to-server payload `create_odc:set_on_demand`)
- `Config.java` — empty COMMON config spec (registered in mod constructor)
- `src/main/templates/META-INF/neoforge.mods.toml` — processed by `generateModMetadata`

## Build & Development Commands

- **Build `create_odc` mod**: `./gradlew build` (from repo root)
- **Build Create mod**: `./gradlew build` (from `Create/` directory)
- **Run game tests**: `./gradlew runGameTestServer` (from `Create/`; Create registers gametests). The root project has the run config but currently registers **no** gametests — the game test server will crash with none.
- **Generate data resources**: `./gradlew data` or the `data` run config (outputs to `src/generated/resources/`; directory created on first run)
- **Init submodule**: `git submodule update --init` (root CI does *not* check out submodules)

## Key Configuration

- **Java 21** required for both projects
- **NeoForge**: root `21.1.250`, Create `21.1.219`
- **Minecraft**: `1.21.1`; Parchment mappings `2024.11.17` (root: `parchment_minecraft_version=1.21.1`; Create: `same`)
- **Root `gradle.properties`**: `mod_id=create_odc`, `mod_version=0.1.0`, `create_version=6.0.10-281` (plus `ponder_version`, `flywheel_version`, `registrate_version`)
- **ModDevGradle** plugin `2.0.146` (root)
- **Root dependencies**: Create/Ponder/Registrate from Maven (`maven.createmod.net`, `maven.ithundxr.dev`); `localRuntime` dev mods: modernfix, ferritecore, fastboot
- **Mixins** (`src/main/resources/create_odc.mixins.json`, package `...mixin`, refMap `create_odc.refmap.json`, Java 21):
  - common: `FactoryPanelBehaviourMixin`, `FactoryPanelBlockEntityMixin`, `InventorySummaryMixin`, `LogisticsManagerMixin`
  - client: `FactoryPanelScreenMixin`, `StockKeeperRequestScreenMixin`
- **Data generation output**: `src/generated/resources/` (in source set; `.cache` and `.bbmodel` excluded)
- **Languages**: `en_us.json`, `ja_jp.json` under `assets/create_odc/lang/`

## Run Configurations

Root NeoForge run configs: `client`, `server`, `gameTestServer`, `data`. All use log level DEBUG with `REGISTRIES` markers; game test namespaces gated by `neoforge.enabledGameTestNamespaces=create_odc`.

## Git & CI

- `.gitignore` excludes `run/`, `build/`, `.gradle/`, `.idea/`, `.vscode/`, `.run/`, `bin/`, `repo/`, `**/src/generated/**/.cache/`
- **Root CI** (`.github/workflows/build.yml`): `./gradlew build` only, on push/PR, Ubuntu + JDK 21 (temurin); no submodule checkout
- **Create CI** (`Create/.github/workflows/build.yml`): `./gradlew build` + `./gradlew runGameTestServer` (skipped on release); `./gradlew publishMods` on release via `workflow_dispatch` input `is-release` (needs `MODRINTH_TOKEN`, `CURSEFORGE_TOKEN`, `GITHUB_TOKEN`). Also has label-actions and Crowdin workflows.

## Important Gotchas

- **Two `gradlew` wrappers** with different Gradle versions (root: 9.7.1, Create/: 8.14.3). Use the correct one for each project.
- `Create/` is a **submodule on a detached HEAD** — branch edits inside it require creating a branch first; bumping it means committing a new submodule SHA at root.
- `Create/settings.gradle` only runs `includeBuild(".")` / `includeBuild("Ponder")` if a `Ponder/` directory exists — it currently does **not**, so no composite build is active.
- The `run/` directory contains runtime files, configs, and crash reports — gitignored, regenerated per run.
- Root `build.gradle` uses `neoForge.ideSyncTask generateModMetadata` to auto-generate mod metadata on IDE reload.
- Working tree may contain uncommitted cleanup of NeoForge template boilerplate (commented-out example code in `Config`/main classes).
