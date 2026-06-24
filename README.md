# PlayerMob

A custom Minecraft mob that looks like a player and acts like a pillager. Built with
Architectury for **Fabric**, **Forge**, and **NeoForge** on Minecraft 1.21.1.

## What it does

PlayerMob registers a new entity (`playermob:player_mob`) rendered with the vanilla
player model. Its AI is weapon-aware: if it spawns holding a ranged weapon (bow,
crossbow) it fires from a distance; if it spawns with a melee weapon (sword, axe) or
empty-handed it closes in. It defaults to attacking hostile mobs and revenging any
attacker.

A `Stance` enum is wired in from v1 — future versions will let each mob pick a
stance at spawn (`HOSTILE_TO_PLAYERS`, `NEUTRAL`, etc.) without re-architecting.

**Spawning:** Spawn egg + `/summon playermob:player_mob`, the Dungeon-Train event path, and
**opt-in natural spawning**. Natural spawning ships **off** — enable it in
`config/playermob.properties` with `naturalSpawnEnabled=true`. When on, each vanilla mob has a
`naturalSpawnScale.<id>` chance (0.0–1.0) that a PlayerMob spawns **beside** it on a natural spawn
(additive — the mob is **not** replaced; villages spawn PlayerMobs among their villagers too). Each
mob's line defaults to its group:

| Group | Default | Examples |
|---|---|---|
| Hostile | 0.0 | zombie, skeleton, creeper, … |
| Nether | 0.05 | blaze, piglin, ghast, … |
| Animals | 0.15 | cow, pig, sheep, … |
| Friendly | 0.15 | wolf, fox, bee, … |
| Water | 0.0 | cod, squid, dolphin, … |
| Villager | 0.25 | villager, iron_golem |

Tune it live (op, session-only): `/playermob naturalspawn on|off`,
`/playermob naturalspawn <mob> on|off|<chance>`, and
`/playermob naturalspawn group <group> on|off|<chance>`. No raid participation.

## Build

```bash
./gradlew build                  # Build all three loader jars
./gradlew fabric:runClient       # Dev client (Fabric)
./gradlew neoforge:runClient     # Dev client (NeoForge)
./gradlew forge:runClient        # Dev client (Forge — see Compatibility note)
```

Built jars land in:

- `fabric/build/libs/playermob-fabric-<version>.jar`
- `forge/build/libs/playermob-forge-<version>.jar`
- `neoforge/build/libs/playermob-neoforge-<version>.jar`

## Bundled mod — Adventure Item Names

On **Minecraft 1.21.1** (Fabric, Forge, and NeoForge), PlayerMob ships with
[Adventure Item Names](https://modrinth.com/mod/adventureitemnames) bundled inside its jar
(Jar-in-Jar). AIN procedurally names naturally-spawned swords, tools, shields, and armor, and
PlayerMob coordinates with its naming so reincarnated "echo" mobs keep their own names. You get
the combined experience automatically — no separate download.

**Turning it off.** AIN is a normal, self-contained nested mod; it isn't forced on:

- Set AIN's naming probability to **0** in its in-game config screen (or its config file) to stop
  the naming while keeping the mod loaded, **or**
- Remove the bundled AIN jar from PlayerMob (most launchers let you disable individual
  Jar-in-Jar mods; otherwise delete `adventureitemnames-*.jar` from PlayerMob's nested
  `META-INF/jars/` — Fabric — or `META-INF/jarjar/` — Forge/NeoForge).

The bundle is pinned via `ain_version` in `gradle.properties`. The 1.20.1 and 26.2 PlayerMob
builds do **not** bundle AIN.

## Custom skins & skin packs

About 40% of PlayerMobs wear a real player's skin, drawn from a datapack-extensible pool. Mob-pack
authors can grow that pool **without hunting down texture URLs** — just name a player and PlayerMob
resolves their skin automatically:

```json
// data/<your_pack>/playermob_skins/notch.json
{ "displayName": "Notch", "playerName": "Notch" }
```

You can also pin a specific skin in-game: `/playermob summon <player> [<pos>] [<friendliness>] [<fightFlight>]`
spawns a PlayerMob wearing that player's skin (traits optional, default random). Mods can inject
skins programmatically via the `SkinSources` seam.

See the wiki for the full format, the resolution/offline caveats, and the modder API:
**[Custom Skins](https://github.com/bh679/playermob-mc/wiki/Custom-Skins)**.

## External integration — reincarnation sources

When a player dies, PlayerMob snapshots their life (skin, gear, traits, feelings, where they
died) into a cross-world death log. A PlayerMob that spawns later — e.g. on a Dungeon Train —
may come back as an **echo** of one of those past lives. Another mod (e.g. Discord Presence) can
plug into both ends of this through the seam in `games.brennan.playermob.compat`:

- **Supply** past lives into the pool — so an echo can embody a life *your* mod knows about
  (e.g. a death synced from another server). Implement `ReincarnationSource` and register it:

  ```java
  // From your loader entry point, guarded by ModList.isLoaded("playermob"):
  ReincarnationSources.register(new MyReincarnationSource());

  final class MyReincarnationSource implements ReincarnationSource {
      @Override public List<ReincarnationRecord> candidates(MinecraftServer server, ReincarnationQuery q) {
          // Return YOUR eligible past lives for the request, oldest first. Filter by q.mode():
          //   CARRIAGE -> lives near q.carriage()   PLAYER -> lives of q.player()   ANY -> all
          // Don't de-dup or weight — the registry handles "already met" and the recency/proximity pick.
          return myLives(q);
      }
  }
  ```

- **Read** PlayerMob's own death log — to display or relay it:

  ```java
  List<ReincarnationRecord> recent = ReincarnationSources.recentDeaths(server, 20);
  ```

`ReincarnationRecord` is the symmetric data contract for both directions. Its `snapshot` is the
opaque PlayerMob entity NBT (you shuttle it verbatim — PlayerMob applies it when it spawns the
echo); `name`, `playerId`, `carriage`, and the captured `skinUrl` are pulled out for display.
PlayerMob's own death log is always a built-in source, so with no integrating mod the pool is
just the local log — exactly the prior behaviour.

**Local vs remote pools.** A spawning PlayerMob rolls two *separate* chances — one for a **local**
life (PlayerMob's own death log; the player themselves may return) and an equal, independent one
for a **remote** life (lives your mod supplies). The split means a remote pool never erodes the
local self-reincarnation chance. Your source is **remote by default**; only the built-in log is
local. Remote echoes **never embody the live player themselves** (PlayerMob excludes
`query.owner()` from remote picks), so a player only meets *other* people as remote echoes.

Selection within a pool stays random but is **heavily skewed toward recent** lives, with a mild
preference for lives that ended nearer the requesting carriage (see `ReincarnationWeighting`).
Each live player won't be shown the same past life twice in a session (`ReincarnationQuery.owner()`
gates that).

**Answer synchronously — pre-fetch, don't fetch on demand.** Every seam call runs on the **server
thread** during entity spawn, so `candidates(...)` must return from local state with no blocking
I/O. For a network-backed mod this means *pre-fetching* remote lives into a local cache ahead of
spawns — never fetching inside the call. Because Dungeon-Train spawns only ever query the band
**around the player's current carriage** (`q.carriage()`), the recommended strategy is a small
**sliding window**: keep a bounded sample of remote lives for the player's current depth band,
refreshed as they advance, rather than the whole world's log.

PlayerMob only ever uses the **newest ~5 lives per band per source** (it trims each source's
candidates to `ReincarnationSources.MAX_CANDIDATES_PER_BAND` — a ±band rarely yields more than a
handful of echoes before the player moves on, and the recency weighting makes the rest negligible).
So a source need only cache/fetch **~5 lives per band** — and it can pull several bands at once and
re-fetch only when the player crosses the pre-fetched range, keeping fetch frequency low. Cache
size is then O(bands-ahead × 5), independent of how large the global log grows.

## License

PolyForm Shield 1.0.0 — see [LICENSE](LICENSE).
