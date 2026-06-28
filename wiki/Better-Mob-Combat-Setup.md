# Better Mob Combat — Setup Guide

Give a PlayerMob a sword or axe and it fights with [Better Combat](https://modrinth.com/mod/better-combat)'s
animated wind‑up and combo swings, exactly like any other humanoid mob. This page is the full setup
guide; the [README](https://github.com/bh679/playermob-mc/blob/main/README.md#mod-compatibility--better-combat--better-mob-combat)
has the short version.

> **PlayerMob needs no configuration for this.** A PlayerMob is a humanoid drawn with the vanilla
> player model, so it picks up Better Combat's melee animations automatically once the Better Combat
> mod stack is installed and internally consistent. Everything below is about getting *that stack*
> right — there is no PlayerMob setting to toggle.

---

## What you get (and what you don't)

- **Melee only.** Better Combat overhauls melee. A PlayerMob holding a **sword or axe** shows the
  animated swings. A PlayerMob holding a **crossbow, bow, or empty hand** looks unchanged — that's
  expected, not a bug. Give it a sword to see the effect.
- **No ranged change.** PlayerMob's own crossbow/bow AI is unaffected by Better Combat.

---

## Will it work on my Minecraft version?

The Better Mob Combat stack only exists on some versions. Match this to the PlayerMob build you run:

| MC version | Better Mob Combat availability | PlayerMob loaders |
|---|---|---|
| **1.20.1** | ✅ Full stack — Fabric, Forge, NeoForge | Fabric, Forge, NeoForge |
| **1.21.1** | ⚠️ Only the NeoForge‑only [Better Mob Combat Neo](https://modrinth.com/mod/better-mob-combat-neo) fork | Fabric, Forge, NeoForge |
| **26.2** | ❌ No Better Mob Combat build of any kind | Fabric, NeoForge |

**1.20.1 is the version where everything is native and well‑tested.** If you are on 1.21.1, you must
use the *Neo* fork on NeoForge; on 26.2 there is currently no option.

---

## The required mod stack

"Better Mob Combat" silently depends on several other mods. If any is missing, the mobs won't
animate — or the game won't start. Install **all** of these (1.20.1):

| Mod | Role |
|---|---|
| [Better Combat](https://modrinth.com/mod/better-combat) | Core combat/animation overhaul |
| [Better Mob Combat](https://modrinth.com/mod/better-mob-combat) | Lets mobs use Better Combat |
| [Player Animator](https://modrinth.com/mod/playeranimator) | Animation runtime |
| [Mob Player Animator](https://modrinth.com/mod/mob-player-animator) | Applies Player Animator to mobs |
| [Cloth Config](https://modrinth.com/mod/cloth-config) | Config lib (pulled in by Better Combat) |

Plus, of course, **PlayerMob** itself and the loader's API (Fabric API / Forge / NeoForge).

---

## ⚠️ The #1 cause of "it doesn't work": a Better Combat version mismatch

Better Mob Combat **1.3.0** is built against Better Combat **1.8.6**. The newer Better Combat
**1.9.0** *removed* the method `CompatibilityFlags.firstPersonRender()` that Better Mob Combat calls.
So if you pair **BMC 1.3.0 with Better Combat 1.9.0**, the game **hard‑crashes** (`NoSuchMethodError`)
the first time *any* mob plays an attack swing — even a vanilla zombie. If your world dies the moment
combat starts, this mismatch is almost always why.

You have **two ways** to resolve it. Pick one.

### Path A — Keep the latest Better Combat, add the Fix mod (recommended if you're already on 1.9.0)

Install the [**Better Mob Combat Fix [BMCFIX]**](https://www.curseforge.com/minecraft/mc-mods/better-mob-combat-fix)
mod. It re‑adds the removed `firstPersonRender()` hook so Better Mob Combat **1.3.0** runs on the
newer Better Combat **1.9.0** without crashing.

- **Loader / version:** Fabric, MC **1.20.1** (`bmcfix-fabric-1.0.2.jar` at time of writing — always
  grab the file that matches your loader and your Better Combat version from the CurseForge page).
- **Install:** drop the jar into `mods/` alongside the full stack above. No config.
- **Use this when:** you want to stay on the latest Better Combat, or other mods in your pack require
  Better Combat 1.9.0.

> This is the path a PlayerMob player on the latest Better Combat reported working. It is
> **community‑maintained** (not authored by the PlayerMob or Better Combat teams), and it is
> **Fabric‑only** — on Forge/NeoForge use Path B.

### Path B — Pin Better Combat to 1.8.6 (no extra mod)

Instead of adding the Fix mod, **downgrade Better Combat to 1.8.6 or earlier** — the newest Better
Combat that Better Mob Combat 1.3.0 is binary‑compatible with. No patch mod needed.

- **Loader / version:** Fabric, Forge, **and** NeoForge on MC 1.20.1.
- **Use this when:** you're on Forge/NeoForge, or you'd rather not add a community patch mod, and
  nothing else in your pack forces Better Combat 1.9.0.

> This is the combination the PlayerMob project regression‑tests in its dev client (see below), so
> it's the most thoroughly verified pairing.

**At a glance:**

| | Path A — Fix mod | Path B — Pin 1.8.6 |
|---|---|---|
| Better Combat version | Latest (1.9.0) | 1.8.6 or earlier |
| Extra mod needed | Better Mob Combat Fix | none |
| Loaders | Fabric only | Fabric / Forge / NeoForge |
| Verified by | Community / player report | PlayerMob dev harness |

---

## Step‑by‑step (1.20.1, modpack)

1. Install **PlayerMob** and your loader API as usual.
2. Add the full stack: **Better Combat**, **Better Mob Combat**, **Player Animator**,
   **Mob Player Animator**, **Cloth Config**.
3. Make Better Combat and Better Mob Combat consistent — **either**
   - keep Better Combat 1.9.0 and add **Better Mob Combat Fix** (Path A, Fabric), **or**
   - swap Better Combat down to **1.8.6** (Path B, any loader).
4. Launch. In a creative test world:
   ```
   /summon playermob:player_mob
   /item replace entity @e[type=playermob:player_mob,limit=1] weapon.mainhand with minecraft:diamond_sword
   ```
5. Provoke the PlayerMob (hit it). It should attack with Better Combat's animated swing rather than
   the flat vanilla arm swing.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| **Crash the instant combat starts** (`NoSuchMethodError`, `firstPersonRender`) | BMC 1.3.0 on Better Combat 1.9.0 | Apply Path A (Fix mod) **or** Path B (pin 1.8.6) |
| **Mobs don't animate, no crash** | Missing **Mob Player Animator** or **Player Animator** | Install the full stack above |
| **Game won't start at all** | A stack mod is missing or wrong‑loader | Verify every mod in the table matches your loader + MC version |
| **PlayerMob with a bow/crossbow looks normal** | Working as intended — Better Combat is melee‑only | Give it a sword or axe |
| **Nothing animates on 1.21.1** | Better Mob Combat isn't on 1.21.1 except the Neo fork | Use [Better Mob Combat Neo](https://modrinth.com/mod/better-mob-combat-neo) on NeoForge |
| **Some modded mob doesn't animate** | Better Mob Combat blacklists GeckoLib‑animated mobs (incompatible with Player Animator) | Expected for those mobs; PlayerMob is unaffected (it uses the vanilla player model) |

---

## For developers — reproduce / regression‑test

The PlayerMob repo can load the whole compatible stack on demand in the 1.20.1 Fabric dev client.
It's **dev‑runtime only** (`modRuntimeOnly`) and never ships in PlayerMob's jar:

```bash
./gradlew :fabric:1.20.1:runClient -PbmcCompatTest
```

This pulls Better Combat **1.8.6** + Player Animator + Mob Player Animator + Better Mob Combat 1.3.0
(+ Cloth Config) — i.e. **Path B**. See the `-PbmcCompatTest` block in
[`fabric/build.gradle.kts`](https://github.com/bh679/playermob-mc/blob/main/fabric/build.gradle.kts).
Then summon a PlayerMob, give it a sword, and provoke it to verify the animated melee.

---

## Why PlayerMob needs no special handling

Better Mob Combat animates *humanoid* mobs through Player Animator and auto‑blacklists GeckoLib mobs.
A PlayerMob renders with the vanilla `PlayerModel` (via `HumanoidMobRenderer`) — not GeckoLib — so it
is exactly the kind of mob Better Mob Combat already supports. There is no PlayerMob ↔ Better Mob
Combat bridge code: PlayerMob is a passive beneficiary. That's why this is purely a question of
getting the Better Combat mod stack itself consistent.
