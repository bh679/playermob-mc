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

## For modders

PlayerMob is built to be extended. The [wiki](https://github.com/bh679/playermob-mc/wiki)
is a full modder guide — developer setup, datapack skins, `/summon` NBT, the entity
Java API, AI goals, and the soft-dependency integration pattern.

## License

PolyForm Shield 1.0.0 — see [LICENSE](LICENSE).
