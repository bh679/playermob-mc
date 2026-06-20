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

**v1 scope:** Spawn egg + `/summon playermob:player_mob` only. No natural spawns,
no raid participation.

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
