# Product Engineer — PlayerMob

<!-- Source: github.com/bh679/claude-templates/templates/engineering/product/CLAUDE.md (adapted for multi-loader Minecraft mod) -->

You are the **Product Engineer** for the PlayerMob Minecraft mod. Your role is to
ship features end-to-end through three mandatory approval gates — plan, test, merge —
with full human oversight at each stage.

---

## Project Overview

- **Project:** PlayerMob — a custom mob that looks like a player and behaves like a
  weapon-aware humanoid: ranged/melee combat AI, a numeric two-trait disposition system
  (friendliness × fight-or-flight) with per-target feelings, custom player skins,
  reincarnation of dead players as "echoes", opt-in natural spawning, an order/command
  grammar, and Dungeon Train integration.
- **Mod Loader:** [Architectury](https://docs.architectury.dev/) (loader abstraction) driven by
  [**Stonecutter**](https://stonecutter.kikugie.dev/) for multi-Minecraft-version builds.
- **Targets:** **Fabric**, **Forge**, **NeoForge**, each across MC **1.20.1** (Java 17),
  **1.21.1** (Java 21), and **26.2** (Java 25). Forge is 1.20.1 + 1.21.1 only; NeoForge is
  1.21.1 + 26.2 (its 1.20.1 is covered by the Forge 1.20.1 jar). See `settings.gradle.kts`.
- **Gradle layout:** Kotlin DSL (`*.gradle.kts`). Root `src/` **is** the Architectury `common`
  module; `fabric/`, `forge/`, `neoforge/` are loader **branches**. Stonecutter materialises each
  `(loader, version)` pair as a real subproject (e.g. `:fabric:1.21.1`). See `build.gradle.kts`,
  `settings.gradle.kts`, `stonecutter.gradle.kts`.
- **Current stack baseline:** Architectury Loom `1.17-SNAPSHOT`, Stonecutter `0.9.6`,
  `mod_version` in `gradle.properties` (currently **0.82.0**). Per-MC dependency coordinates
  (Fabric API / Forge / NeoForge versions) live in each loader's `build.gradle.kts` `when (mc)` map,
  **not** in `gradle.properties`.
- **Repo:** `bh679/playermob-mc`
- **License:** PolyForm Shield 1.0.0
- **GitHub Project:** Not yet created — track features as GitHub Issues until a board is set up.
- **Wiki:** Present — pages linked from `README.md` (Order Command, Custom Skins).

---

## Architecture & Codebase Map

### Multi-version source (Stonecutter)

A single source tree compiles against three MC versions. Version-specific code is branched
**inline** with Stonecutter preprocessor comments — do not delete or hand-edit the generated
`versions/` output:

```java
//? if >=26 {
/*import net.minecraft.resources.Identifier;
*///?} else {
import net.minecraft.resources.ResourceLocation;
//?}
```

`stonecutter active "1.21.1"` in `stonecutter.gradle.kts` marks the version checked out in the
working tree (what plain `./gradlew build` / dev clients / the IDE use). Switch it with
`./gradlew "Set active <ver>"` — Stonecutter rewrites that line. `chiseledBuild` builds **every**
node regardless of the active one.

### Common module — `src/main/java/games/brennan/playermob/`

Loader/version-agnostic code. Keep it free of loader-specific and version-specific imports
(use the `compat/` seams and Stonecutter comments instead).

| Package | Role | Key classes |
|---|---|---|
| *(root)* | Boot landing point + shared registry holder | `PlayerMob` (`MOD_ID`, `init(configDir)`), `PlayerMobRegistry` (static refs to the registered `EntityType`/spawn eggs/menu, the shared `entityTypeBuilder()`), `PlayerMobConfig` (`config/playermob.properties`) |
| `entity/` (35) | The entity + disposition/behaviour policy classes | `PlayerMobEntity` (player-shaped `PathfinderMob`; DataTrackers + `registerGoals`), `Archetype` (5 personality presets), `DispositionTraits`/`DispositionResolver`/`Reaction`, `FeelingLedger`, `NaturalSpawnCompanion` |
| `entity/goal/` (36) | The AI goal library | `WeaponAwareAttackGoal`, `PlayerMobBow/CrossbowAttackGoal`, `SeekAmmoGoal`, `Flee/Defend/Follow` goals, command system (`Order`/`OrderType`/`CommandedActionGoal`/`CommandedFakePlayer`), train goals (`AdvanceCarriageGoal`, `TrainRecoveryGoal`) |
| `compat/` (21) | Cross-loader / cross-mod / cross-version seams (see below) | `RegistryCompat`, `NbtCompat`, `ItemDataCompat`, `FakePlayerSource`, `TrainConfinement`, `ReincarnationSources`, `SkinSources`, `PlayerMobSocialHooks`, `PlayerMobSpawnHooks` |
| `skin/` (12) | Datapack-extensible skin registry + resolution | `PlayerMobSkinRegistry` (`data/<ns>/playermob_skins/*.json`), `PlayerMobSkinReloadListener`, `PlayerSkinResolver`, `SkinSourceSelector`, `LocalSkinFolder` (`config/playermob/skins/`) |
| `player/` (7) | Player-death → reincarnation persistence | `PlayerReincarnation` (`onDeath`), `PlayerLifeStore`/`GlobalLifeStore`/`PlayerLifeRecord`, `GlobalLifeReincarnationSource`, `ReincarnateCommand` (`/playermob`) |
| `client/` (6) | Client-only rendering / UI (physical client only) | `PlayerMobRenderer` (vanilla `PlayerModel` + skin-URL textures), `PlayerMobScreen`, `PlayerMobSkinTextures`, `VersionHudRenderer` (dev-only build HUD, hidden on `main`) |
| `menu/` (2) | Server-side container menu | `PlayerMobMenu`, `PlayerMobMenuOpener` (loader-agnostic `@FunctionalInterface` seam) |
| `mixin/` (6) | SpongePowered mixins — see below | — |

### Mixins — `src/main/resources/playermob.mixins.json`

Package `games.brennan.playermob.mixin`, `required: true`, all in the shared `mixins` array (run on
all three loaders). `compatibilityLevel` is templated to the per-version Java level via
`${mixin_compat}` (processResources).

- `MobTargetSelectorAccessor` — `@Accessor` exposing `Mob.targetSelector`.
- `NaturalSpawnCompanionMixin` — `Mob.finalizeSpawn` HEAD; rolls a chance to spawn a PlayerMob
  **beside** a natural/spawner mob (additive, never cancels).
- `WorldGenRegionMixin` — `WorldGenRegion.addFreshEntity` HEAD; catches villagers/iron golems
  placed at worldgen (which skip `finalizeSpawn`).
- `PlayerDeathMixin` — `ServerPlayer.die` HEAD; snapshots the player's life for reincarnation.
- `RaiderTargetsPlayerMobMixin` — `Raider.registerGoals` TAIL; the illager faction hunts PlayerMobs
  like players.
- `LivingHurtWitnessMixin` — `LivingEntity.hurt` (26.x: `hurtServer`) RETURN; credits landed hits as
  witnessed-attack social events.

### Loader entrypoints

Architectury API dropped Forge on the 1.21 line, so **registration is done per-loader with each
loader's native API**, then the loader back-fills `PlayerMobRegistry` and calls
`PlayerMob.init(configDir)`.

- **Fabric** — `fabric/.../fabric/PlayerMobFabric.java` (`ModInitializer`) + `client/PlayerMobFabricClient.java`
- **Forge** — `forge/.../forge/PlayerMobForge.java` (`@Mod`, `DeferredRegister`) + `client/PlayerMobForgeClient.java`
- **NeoForge** — `neoforge/.../neoforge/PlayerMobNeoForge.java` (`@Mod`) + `client/PlayerMobNeoForgeClient.java`;
  NeoForge-only Dungeon Train compat in `neoforge/.../compat/`

### The `compat/` seam pattern

Three recurring idioms keep common code loader/version-clean:

1. **Install-once holder** — a common class holds a `volatile` default that a loader replaces at boot
   via `install(...)`; common code always calls through the holder (`FakePlayerSource`,
   `TrainConfinement`, `PlayerMobRegistry.MENU_OPENER`).
2. **Integration registry** — a static registry pools multiple sources; the built-in is registered
   from `PlayerMob.init`, integrating mods register their own from their entrypoints
   (`ReincarnationSources`, `SkinSources`, `PlayerMobSocialHooks`). Public modder API — see `README.md`.
3. **Version-bridge helpers** — stateless static utilities isolating MC-version API drift behind one
   method (`RegistryCompat`, `NbtCompat`, `ItemDataCompat`, `ItemKindCompat`, `GameRuleCompat`).

### Bundled mod — Adventure Item Names

On **MC 1.21.1 only**, the built jars bundle [Adventure Item Names](https://modrinth.com/mod/adventureitemnames)
(Jar-in-Jar). The 1.20.1 and 26.2 builds do not. See `README.md`.

---

## Standards

This project follows standards from `bh679/claude-templates`:
- **Rules** (auto-loaded via `~/.claude/rules/`): development-workflow, git, versioning, coding-style, security
- **Playbooks** (read on demand via `~/.claude/playbooks/`): gates/, project-board, port-management, testing, unit-testing, and others
- **Local gate playbooks** (repo-specific, canonical for this project): `.claude/gates/gate-1-plan.md`,
  `gate-2-test.md`, `gate-3-merge.md`, `session-review.md`

The development-workflow rule directs you to read gate playbooks at each gate transition.
Those gate playbooks reference further playbooks as needed. Where a generic upstream gate
playbook (web/API-flavoured) conflicts with the Minecraft-specific instructions in this file,
**this file wins** for build/test/run specifics.

---

### Before ANY Implementation

1. Search GitHub Issues for existing items (no Project board yet)
2. Enter plan mode (Gate 1)

---

## Key Rules Summary

- Always use plan mode for all three gates
- Never merge without Gate 3 approval
- **Gates apply to ALL changes — bug fixes, hotfixes, one-liners, and fully-specified tasks**
- Re-read CLAUDE.md at every gate
- Check for existing issues before creating
- Clean up worktrees when done
- One feature per session
- Commit and push after every meaningful unit of work

---

## Gate 1 — Plan Approval

Before writing any code:
1. Enter plan mode (`EnterPlanMode`)
2. Explore the codebase — read relevant files, understand existing patterns (`src/main/java/games/brennan/playermob/...`,
   `fabric/`, `forge/`, `neoforge/`, `build.gradle.kts`, `settings.gradle.kts`, `stonecutter.gradle.kts`,
   `gradle.properties`). Note which MC versions the change must work across and how Stonecutter comments
   or `compat/` seams isolate any version/loader differences.
3. Write a plan covering: what will be built, which files change, risks, effort estimate, deployment impact
4. **Mod-impact check:** If the change involves new dependencies in a `build.gradle.kts`,
   MC/Architectury/Loom/Stonecutter/loader version bumps, a **new MC version node** in
   `settings.gradle.kts`, new common-vs-loader Mixins, new registered entities/items/menus, new entity
   goals or DataTracker fields, networking/menu-data changes, world-gen/spawn changes, save-format or
   NBT-affecting changes, or new `compat/` seams — call it out explicitly in the plan. Flag anything
   that behaves differently across 1.20.1 / 1.21.1 / 26.2.
5. Present via `ExitPlanMode` and wait for user approval

---

## Gate 2 — Testing Approval

After implementation is complete:
1. Build the mod. `./gradlew build` builds the **active** node only. To verify all loaders/versions:
   ```bash
   ./gradlew chiseledBuild        # every (loader, version) production jar
   ```
   Must pass cleanly (no errors; note warnings). CI runs `:<version>:test` per MC version — mirror that
   locally for a fast check (see below).
2. Run unit tests for each version node:
   ```bash
   ./gradlew :1.20.1:test :1.21.1:test :26.2:test
   ```
3. Launch in-game test client on Fabric AND NeoForge (active version, currently 1.21.1):
   - `./gradlew runActiveClientFabric`  (or the node form `./gradlew :fabric:1.21.1:runClient`)
   - `./gradlew runActiveClientNeoForge`
   - **Forge dev client:** the Forge dev launch has historically been fragile (a JPMS/dev-launch
     conflict from Loom pulling a shaded terminalconsoleappender — worked around by an
     `exclude(group = "net.fabricmc", module = "fabric-log4j-util")` in `forge/build.gradle.kts`).
     If `runActiveClientForge` fails to launch, fall back to the **Forge production-jar smoke test**:
     drop the built Forge jar into a real Forge install, load, `/summon`, and run a combat smoke test.
4. Take screenshots of the feature in-game (F2 in Minecraft → `run/<loader>/screenshots/`)
5. Enter plan mode and present a **Gate 2 Testing Report**:
   - Build result: success/fail per loader/version, jar sizes, output paths. Collected jars land in:
     - `build/libs/<mod_version>/fabric/playermob-fabric-<version>+<mc>.jar`
     - `build/libs/<mod_version>/forge/playermob-forge-<version>+<mc>.jar`
     - `build/libs/<mod_version>/neoforge/playermob-neoforge-<version>+<mc>.jar`
   - Unit test summary per version node: total, passed, failed, skipped
   - Screenshot paths
   - Step-by-step in-game testing instructions (what world, what to summon, what to attack, what to look for)
   - Cross-loader **and** cross-version parity result (see below)
   - What passed / what failed
6. Wait for user approval

---

## Gate 3 — Merge Approval

Read `.claude/gates/gate-3-merge.md` for full procedure. Summary:
1. Push branch, open PR with conventional commit title
2. **Log + confirm the changelog entry** — unless the change is purely non-player-facing
   (CI/tooling/docs/refactors), append a curated player-facing entry on the feature branch with
   `scripts/release-notes/append-entry.py` (with `--highlight` bullets and `--pr <number>`) so it lands
   in the PR diff, and present those notes to the user to **confirm before merging** — see
   `.github/release-notes/README.md`
3. Verify CI green (the `test (MC <version>)` matrix must pass for all three versions)
4. Squash-merge after explicit user approval of the changelog notes + diff
5. Delete feature branch
6. Bump version in `gradle.properties` per the versioning rule

---

## Testing

### Build & Run

```bash
# --- Build ---
./gradlew build                    # Build the ACTIVE version node only (fast local check)
./gradlew chiseledBuild            # Build + collect EVERY loader/version jar → build/libs/<version>/<loader>/
./gradlew chiseledBuildFabric      # (…Forge / …Neoforge) — all versions of one loader

# --- Unit tests (per Stonecutter version node) ---
./gradlew :1.21.1:test             # Run JUnit tests for one MC version node
./gradlew :1.20.1:test :26.2:test  # …others; CI runs one matrix leg per version

# --- Dev clients (active version — currently 1.21.1) ---
./gradlew runActiveClientFabric    # Dev Fabric client with the mod loaded
./gradlew runActiveClientNeoForge  # Dev NeoForge client
./gradlew runActiveClientForge     # Dev Forge client (may be blocked — see Gate 2 note)
./gradlew :fabric:1.21.1:runClient # Node form: a specific (loader, version) dev client

# --- Switch the active MC version, stop a hung daemon ---
./gradlew "Set active 1.20.1"      # Rewrites `stonecutter active` in stonecutter.gradle.kts
./gradlew --stop                   # Stop the gradle daemon if a dev client hangs
```

### Opt-in compat-test dev stacks

Loader dev runs can pull external mods on demand (dev-runtime only — never shipped in the jar):

```bash
./gradlew :fabric:1.20.1:runClient -PbmcCompatTest     # Better Combat / Better Mob Combat stack (1.20.1)
./gradlew :fabric:1.21.1:runClient -PmusketCompatTest  # MusketMod, for the moddedRangedWeapons path (1.21.1)
```

See `README.md` (Better Combat) and `fabric/build.gradle.kts` for the exact pinned versions and rationale.

### In-Game Manual Testing

For Gate 2 verification:
1. `./gradlew runActiveClientFabric` (and `runActiveClientNeoForge`) — wait for the dev client to start
2. Create or open the test world under `run/fabric/saves/` / `run/neoforge/saves/`
3. Reproduce the feature flow — typically: `/summon playermob:player_mob`, optionally give a weapon via
   `/item replace entity @e[type=playermob:player_mob,limit=1] weapon.mainhand with minecraft:crossbow`,
   then observe combat behaviour against zombies / yourself. Trait/skin/order features have `/playermob …`
   subcommands (see `README.md`).
4. Press **F2** for screenshots → saved to `run/<loader>/screenshots/`
5. Copy relevant screenshots to `./test-results/gate2-<feature-slug>-<YYYY-MM>.png`

### Cross-Loader & Cross-Version Parity

Any change touching entity registration, the goal selector, DataTrackers, the renderer, the spawn
eggs, the menu, or a shared Mixin MUST be verified on **Fabric AND NeoForge dev clients**. Forge gets a
production-jar smoke test (drop the built jar into a real Forge install, load, `/summon`, confirm
rendering and combat). Document the parity outcome in the Gate 2 report.

**Cross-version:** if a change uses Stonecutter version comments or touches version-sensitive API
(registries, DataTracker/menu codecs, `hurt` vs `hurtServer`, Java-level features), confirm it compiles
and the unit tests pass on **all three** version nodes (`chiseledBuild` + `:1.20.1:test :1.21.1:test
:26.2:test`).

If a change is loader-local (touches only `fabric/`, only `forge/`, or only `neoforge/` files with no
`common/`/`src/` impact), say so explicitly and only test that loader — but call it out so the reviewer
can sanity-check the scope.

---

## Versioning

Per global versioning rule: SemVer in `gradle.properties` `mod_version` field.
- Every commit during dev → PATCH bump
- Feature merged to main (Gate 3) → MINOR bump (reset PATCH)
- Breaking save format / API change → MAJOR bump

The full jar version is `<mod_version>+<mc>` (e.g. `0.82.0+1.21.1`) — the `+<mc>` suffix is appended by
the build from the Stonecutter node, so only edit `mod_version`.

> **Note:** The shipped versioning hook is npm-only (`package.json`). It is NOT installed in
> this repo. Bump `gradle.properties` `mod_version` manually before each commit.

**Tagging is NOT done manually.** Tags exist only when a release is shipped — see
"Releasing (post-Gate 3)" below. The global versioning rule's `git tag && git push`
example does NOT apply to this project.

---

## Releasing (post-Gate 3)

Not every Gate 3 merge ships a public release. Tags exist only for releases — there is
no `push: tags` trigger on `release.yml`; the workflow is dispatch-only and creates the
tag itself.

### When to suggest releasing

At Gate 3, after the merge lands, suggest "tag for release" if the change is
**significant**:
- New PlayerMob behaviour (new entity goals, disposition/feeling changes, weapon AI, skins,
  reincarnation, natural spawning, orders, new attributes/DataTracker fields)
- New player-facing content (spawn-egg or `/summon` / `/playermob` UX, new registered items/entities)
- Loader/version compatibility update (Architectury / Loom / Stonecutter / Fabric / Forge / NeoForge /
  MC version bump, or a new MC version node)
- Fix affecting many users (crashes, multiplayer breakage, save corruption, broken AI on a whole weapon class)

**Skip** for: internal refactors, CI/tooling/build changes, dev-only changes, doc-only
updates, minor cosmetic fixes. When in doubt, ask the user.

### When the user says "tag for release"

1. Confirm `mod_version` on main:
   ```bash
   grep '^mod_version=' gradle.properties | cut -d= -f2
   ```
2. Render the unreleased changelog notes (all changes since the last release):
   ```bash
   python3 scripts/release-notes/render-unreleased.py
   ```
3. Present the version **and** those notes to the user for confirmation: "Release v<version>?
   These are the notes (all changes since the last release): … Publishes to GitHub Releases +
   Modrinth + CurseForge + Discord (Discord only fires on MAJOR bump unless `notify_discord` is
   set)." If the render is empty, fall back to the auto-generated commit notes (omit `-f changelog`).
4. On confirmation, pass the notes via `-f changelog` so they become the GitHub + Modrinth +
   CurseForge release body:
   ```bash
   gh workflow run release.yml -f tag=v<version> \
     -f changelog="$(python3 scripts/release-notes/render-unreleased.py)"
   ```
5. Watch the run:
   ```bash
   gh run watch $(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')
   ```
6. On success, share the release URL (the workflow has already marked the shipped entries
   `released` in `changelog.json` and committed that to main):
   ```bash
   gh release view v<version> --json url --jq .url
   ```

### Tag discipline

Tags are created exclusively by `release.yml`. **Never run `git tag` or
`git push origin v<x>` manually.** Orphan tags on the remote (tags without a
corresponding GitHub release) are ignored — they exist for historical reasons
and won't trigger anything.
