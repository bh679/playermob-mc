# `/playermob order` — Commanding a PlayerMob

Order a specific PlayerMob to perform a one-off action against a target. The command is
**op-gated** (permission level 2) and runs server-side.

```
/playermob order <name> <action> …
```

- **`<name>`** — selects the PlayerMob by its **custom name** (set with an anvil + name tag).
  If no loaded PlayerMob matches the name, the **nearest** PlayerMob to the command source is
  used instead. Tab-completion suggests the names of loaded PlayerMobs.
- **`<action>`** — one of the actions below.

Orders are **transient**: a new order replaces any previous one, nothing is saved to disk, and
the mob returns to its normal AI once the action finishes.

---

## Targets, positions & items

| Token | Accepts |
|---|---|
| **`<target>`** | An **online player's name**, a **named PlayerMob**, or a **mob type** (resolves to the nearest entity of that type within 128 blocks of the ordered mob). Plain names only — not `@e` selectors. |
| **`<pos>`** | World coordinates `x y z`. Relative `~ ~ ~` is supported. |
| **`<block>`** | A block id (e.g. `minecraft:stone`) — placed conjured from air. |
| **`<item>` / `<weapon>`** | An item id (e.g. `minecraft:diamond_sword`). |

> **Spacing note:** Brigadier splits arguments on spaces, so numeric limits are written with a
> space and a unit — `10 s`, `2 hearts`, `50 blocks` — not `10s`.
>
> **Mob types:** give the type's **id path** without a namespace — `sheep`, `cow`, `iron_golem`.
> The command parser can't accept a colon in this slot, so `minecraft:sheep` won't parse; the
> `minecraft` namespace is assumed. A type resolves to the nearest matching entity, so it's most
> useful with a name (player / PlayerMob) reserved for a specific individual.

---

## Actions at a glance

| Action | Syntax | Summary |
|---|---|---|
| [walk](#walk) | `walk ( <pos> \| <target> )` | Path to a position or entity and stop. |
| [punch](#punch) | `punch <target>` | Walk into reach and land one melee hit. |
| [punchat](#punchat) | `punchat <target>` | Walk up but stop out of reach and throw punch motions (taunt). |
| [attack](#attack) | `attack <target> [limit] [with <weapon> [spawn]]` | Strike once, or fight with a stop limit / chosen weapon. |
| [gift](#gift) | `gift <target> [<item>]` | Walk up and toss an item (auto-picked, or the one named). |
| [greet](#greet) | `greet <target>` | Walk up and perform the crouch-bow greeting. |
| [steal](#steal) | `steal <target> [<n> s \| <n> blocks]` | Take the target's held item, then flee. |
| [use](#use) | `use ( <pos> \| <target> ) <item> [spawn]` | Right-click the item at a position / on an entity. |
| [place](#place) | `place ( <pos> \| <target> ) <block>` | Place a block at a position, or chase a live target and place at their feet. |

While an order runs, the Creative-only objective readout under the mob's name shows what it's
doing (e.g. *"Following order — placing block"*).

---

## walk

```
/playermob order <name> walk <pos>
/playermob order <name> walk <target>
```

Paths to a fixed position, or to a player / PlayerMob (following them until it arrives).

```
/playermob order Bob walk ~ ~ ~5
/playermob order Bob walk Steve
```

## punch

```
/playermob order <name> punch <target>
```

Walks into melee reach, swings once, and lands a single hit using whatever weapon it holds.

## punchat

```
/playermob order <name> punchat <target>
```

Walks up but **stops just outside melee range**, looks at the target, and throws a few punch
motions without ever landing a hit — a taunt.

## attack

```
/playermob order <name> attack <target> [limit] [with <weapon> [spawn]]
```

With **no limit**, performs a single strike with the equipped weapon. A limit turns it into a
sustained fight (the mob's normal combat AI does the fighting until the limit is met):

| Limit | Effect |
|---|---|
| *(none)* | One strike. |
| `kill` / `forever` | Fight until the target dies. |
| `<n> s` / `<n> seconds` | Fight for `n` seconds. |
| `<n> hearts` / `<n> heart` | Fight until the target is down to `n` hearts (`n × 2` HP). |
| `return` | Fight until the mob is hit back (takes retaliation damage), then stand down. |

The optional **`with <weapon>`** picks a weapon:

- `with <weapon>` — uses the weapon **only if the mob already carries it** (else it fails and
  tells you to add `spawn`).
- `with <weapon> spawn` — conjures the weapon into the mob's hand first.

```
/playermob order Bob attack Steve
/playermob order Bob attack Steve kill
/playermob order Bob attack Steve 10 s
/playermob order Bob attack Steve 2 hearts
/playermob order Bob attack Steve return
/playermob order Bob attack Steve with minecraft:diamond_sword spawn
/playermob order Bob attack Steve 10 s with minecraft:diamond_sword spawn
```

## gift

```
/playermob order <name> gift <target> [<item>]
```

Walks up and tosses a gift. With **no item**, it auto-picks the best thing from its pack
(spare gear, surplus food, or a flower). With an **item**, it tosses that (conjured) item.

```
/playermob order Bob gift Steve
/playermob order Bob gift Steve minecraft:diamond
```

## greet

```
/playermob order <name> greet <target>
```

Walks up and performs the friendly crouch-bow greeting.

## steal

```
/playermob order <name> steal <target> [<n> s | <n> blocks]
```

Walks up, takes whatever is in the target's **main hand** into its own pack, then flees:

| Form | Flee duration |
|---|---|
| *(none)* | A short default (~5 seconds). |
| `<n> s` / `<n> seconds` | `n` seconds. |
| `<n> b` / `<n> blocks` | Until it is `n` blocks from where it grabbed the item. |

```
/playermob order Bob steal Steve
/playermob order Bob steal Steve 10 s
/playermob order Bob steal Steve 50 blocks
```

## use

```
/playermob order <name> use <pos> <item> [spawn]
/playermob order <name> use <target> <item> [spawn]
```

Walks up and performs a real **right-click** of the item — at a position (placement, tilling,
bonemeal, buckets, throwables) or on a target entity (shearing, milking, breeding, leads).

- Without `spawn`, the mob must **already carry** the item (else it fails and tells you to add
  `spawn`).
- With `spawn`, the item is conjured into the mob's hand first.

```
/playermob order Bob use ~ ~ ~ minecraft:water_bucket spawn
/playermob order Bob use ~ ~ ~ minecraft:wheat_seeds spawn
/playermob order Bob use Sheep minecraft:shears spawn
/playermob order Bob use Cow minecraft:bucket spawn
```

> **How it works / caveats.** `use` drives a fake player through the full server interaction
> pipeline (`useItemOn` / `useItem` / `interactOn`), so the loaders' interaction events fire and
> **most modded items work**, not just vanilla. The mob's live held stack is used and written
> back, so stateful items (buckets, energy, durability, milk) reflect their result on the mob.
> NeoForge uses its real `FakePlayer` for capability-aware mods. Still best-effort: items needing
> a client/screen or a specific player identity won't work, and positional placement clicks the
> face of `<pos>` nearest the mob, so a block may land one over depending on the item.

## place

```
/playermob order <name> place <pos> <block>
/playermob order <name> place <target> <block>
```

Places a block (conjured from air):

- **`<pos>`** — walks near the position and places the block there, then swings.
- **`<target>`** — **chases the live target** and places the block at their current feet block
  (or one block up if that space is occupied) — "as close as possible".

```
/playermob order Bob place ~ ~ ~ minecraft:stone
/playermob order Bob place Steve minecraft:cobweb
```

---

## Notes

- **Single feature, layered on the AI.** An order is a high-priority goal that overrides the
  mob's autonomous behaviour while it runs, then releases control. It never drowns the mob
  (it yields swimming to the float behaviour).
- **`attack` vs the rest.** `attack` (with a limit) keeps the mob's normal combat AI driving
  the fight; the other actions are self-contained "walk up and do X" sequences.
- **Unnamed mobs.** Because `/playermob summon` doesn't name the mob, an unnamed PlayerMob is
  reachable only via the nearest-match fallback. Name one with an anvil + name tag to target it
  reliably.

## See also

Other `/playermob` subcommands (`reincarnate`, `life`, `summon`, `debug spawnlog`,
`unlimitedammo`, `naturalspawn`) are documented in the in-code Javadoc of
`ReincarnateCommand` and in the project [README](../README.md).
