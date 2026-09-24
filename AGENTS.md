# AGENTS.md

## Project Structure

This is a **two Gradle project** setup for a Minecraft NeoForge mod:

- **Root (`Create-OnDemandCrafting/`)** — builds the `create_odc` mod (mod id: `create_odc`). Uses Gradle 9.7.1 (`gradlew` in root).
- **`Create/` subdirectory** — builds the Create mod itself (mod id: `create`). Uses Gradle 8.14.3 (`gradlew` in `Create/`).
- `Create/settings.gradle` uses `includeBuild(".")` so the Create mod depends on `create_odc`. Build from `Create/` when working on the Create mod.

**Entrypoints**: `CreateOnDemandCrafting` (root, `@Mod("create_odc")`), `Create` (subproject, `@Mod("create")`).

## Build & Development Commands

- **Build `create_odc` mod**: `./gradlew build` (from repo root)
- **Build Create mod**: `./gradlew build` (from `Create/` directory)
- **Run game tests**: `./gradlew runGameTestServer` (from `Create/` or root)
- **Generate data resources**: use the `data` run config or `./gradlew data` (generates to `src/generated/resources/`)
- **CI build**: `./gradlew build` then `./gradlew runGameTestServer` on Ubuntu with JDK 21

## Key Configuration

- **Java 21** required for both projects
- **NeoForge** version: root uses `21.1.250`, Create uses `21.1.219`
- **Minecraft**: `1.21.1`, Parchment mappings `2024.11.17`
- **Root `gradle.properties`**: `mod_id=create_odc`, `create_version=6.0.10-281`
- **Mixins**: `create_odc` patches Create classes via `src/main/resources/create_odc.mixins.json` (mixins: `FactoryPanelBehaviourMixin`, `FactoryPanelBlockEntityMixin`, `LogisticsManagerMixin`, `FactoryPanelScreenMixin`)
- **Data generation output**: `src/generated/resources/` (included in source set, `.cache` and `.bbmodel` excluded)

## Run Configurations

Available NeoForge run configs: `client`, `server`, `gameTestServer`, `data`. All use log level DEBUG with `REGISTRIES` markers.

## Git & CI

- `.gitignore` excludes `run/`, `build/`, `.gradle/`, `.idea/`, `.vscode/`, `repo/`
- CI (`.github/workflows/build.yml`): `./gradlew build` + `./gradlew runGameTestServer` on every push/PR
- The `Create/` CI also runs `./gradlew publishMods` on release (requires `MODRINTH_TOKEN`, `CURSEFORGE_TOKEN`, `GITHUB_TOKEN`)

## Important Gotchas

- **Two `gradlew` wrappers** with different Gradle versions (root: 9.7.1, Create/: 8.14.3). Use the correct one for each project.
- `Create/settings.gradle` only includes sub-projects if `Ponder/` directory exists — the `Create` project is a fork that includes Ponder as a sub-project.
- The `run/` directory contains runtime files, configs, and crash reports — it's gitignored and regenerated on each run.
- `build.gradle` in root uses `neoForge.ideSyncTask generateModMetadata` to auto-generate mod metadata on IDE reload.
