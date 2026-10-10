# Changelog (MC 26.2 line)

> **This is the 26.2 version line of "More Balls" — the current mainline.**
> Ported from the 26.3 line (1.38.6); both share the same source logic.
> The 26.3 line is frozen (Curios is incompatible with NeoForge there, so the
> trinket feature could not be tested).
> History of the 26.3 line lives in `[26.3更多球]_More_Balls\CHANGELOG.md`.

---

## 0.3.4.16

### Fixed: combined balls had 4× base stats (weight, launch speed, ...)

**Root cause.** `comboProfile` keeps **two sets of per-share data**:

```java
List<BallProfile> parts = ...;   // full-ball profile of each source  <- NOT scaled
List<Fragment>   frags = ...;    // already scaled(fraction)
double fraction = 1.0 / indexes.size();

for (BallProfile p : parts) {
    damage += p.damage();        // <- full value, missing the ×fraction
    weight += p.weight();
    ...
}
```

The previous version correctly changed `damage / n` into `Math.floor(damage)`
(per the "sum then floor" rule), but `parts` holds **unscaled full-ball values** —
so a four-part combo came out **four times** too strong. Since **weight drives the
launch speed recalc**, velocity was wrong as well.

**Fix.** All five numeric fields now use `p.xxx() * fraction`, matching `frags`.

**Verification** (pre-fix traversal / post-fix traversal / simulation):

| Scenario | Math | Result |
|---|---|---|
| Four quarters (damage 10/10/1/1) | `(10+10+1+1) × 0.25 = 5.5` → floor | **5** |
| Same ball cut into 4, recombined | `(10×4) × 0.25 = 10` | **10** (restored) |
| Two halves (10/10) | `(10+10) × 0.5 = 10` | **10** (restored) |
| Weight (4/4/2/2, quarters) | `(4+4+2+2) × 0.25 = 3` | **3** (average) |

---

## 0.3.4.15

### Radial menu (hold R): top slot is now "Cancel"

Every page's **topmost slot** is now a **Cancel** button (it sits at 12 o'clock,
`angleFor(0) = π/2`). Selecting it does two things:

1. **Returns the offhand ammo into the pouch** — falls back to the inventory,
   and drops it on the ground only as a last resort. Never vanishes.
2. **Clears the selected ammo kind** — `BallAmmoAutoLoader` reads that state every
   tick, so clearing it **stops auto-loading**; no extra flag field needed.

Paging adjusted accordingly: each page now holds **7 ammo slots** instead of 8.

### Pouch transit area capacity doubled

`BallPouchTier.transitSlots()` changed from `slotsPerRow` to **`slotsPerRow × 2`**:

| Tier | Ammo | Transit (was → now) |
|---|---|---|
| Leather | 9 | 9 → **18** |
| Iron | 12 | 12 → **24** |
| Gold | 15 | 15 → **30** |
| Emerald | 18 | 18 → **36** |
| Diamond | 21 | 21 → **42** |
| Obsidian | 24 | 24 → **48** |
| Netherite | 27 | 27 → **54** |

Storage ceiling raised to match: `BallPouchContents.FIXED_SEGMENT_SIZE` **27 → 54**.
Old saves are padded automatically by `resize()` — **nothing is lost**.
A max-tier pouch now has **27 + 54 = 81** usable slots.

---

## 0.3.4.14

### Six-category rules for cutting / recombining balls (fully applied)

The author finalized the rules into **six categories**. This release applies them
across **all** 25 properties, not just the examples given.

| Category | Properties | Cutting | Combining |
|---|---|---|---|
| **Numeric · basic** | base damage, weight, launch speed, bounce, charge n, toughness(n), sense n | split evenly | sum → floor |
| **Threshold · basic** | melt n | **unchanged** | **take the lowest** |
| **Numeric · trait** | wisdom n, penetration n, **demolisher n** | split evenly | **must activate**; then sum → floor |
| **Threshold · special** | thunder n, molten n | **unchanged** | **must activate**; then **take the highest** |
| **Other · special** | morph, magnetic, kindness, conduction, shock, pulse, illuminate, lens | passed through | must activate |
| **Unique · special** | gold-shiny | **not inherited** | **does not combine** |

**"Activate"** = two-part: any share activates. Four-part: **≥2 shares** required.

### Notable changes

- **Demolisher n turned from a bit flag into a numeric field** — it is a
  *numeric trait*, so it must be split and summed, not simply toggled.
  Added `BallProfile.breaker` / `Fragment.breaker`; `FLAG_BREAKER` retired.
- **Threshold rules split in two**: melt takes the **lowest**, molten takes the
  **highest**; both are **not scaled** when cut (scaling would make the original
  threshold unrecoverable).
- **Thunder n went back to "must activate"** (≥2 shares in a four-part combo).

### On inaccuracy

It is the throw spread angle (vanilla snowball = `1.0`). **No ball in the mod ever
calls `withInaccuracy`, and there is no tooltip key for it** — effectively a dead
field. It is still averaged in `comboProfile` but does not affect behaviour.

---

## 0.3.4.13

### Demolisher now uses a vanilla raycast

The previous version scanned **horizontally** (to stop it digging straight down),
which meant a ball flying slightly downward could not reach blocks **below the
diagonal**. Both attempts were wrong for the same reason: computing the direction
by hand.

Now it casts a **vanilla ray** (`ClipContext` + `CollisionContext.empty()`) along
the **real motion direction** — diagonal-down naturally hits the block below,
air is skipped automatically, and level flight is a horizontal ray.

### Plus the six-category rules landing (first pass)

Also fixed the `lang` file (a stray comma had made the **entire translation file
be skipped**) and removed two leftover Chinese strings from `en_us`.

---

## 0.3.4.12

- **Penetration tooltip** — `desc.penetration` was referenced but never defined,
  so holding Shift showed a raw translation key. Added.
- **Demolisher no longer bounces after breaking a block** — it used to fall through
  to `bounceOff()`, get deflected, and then be dragged down by gravity. Now a
  successful break lets it continue along its original direction. Speed decay
  restored to **0.85**.

---

## 0.3.4.11

- **Demolisher direction** — switched to a horizontal scan (it used to dig
  straight down because gravity pulled the velocity vector downward).
- **Penetration was purely cosmetic** — `setPierceLevel` was only ever called
  with the crossbow's Piercing enchantment level, so `profile.penetration()`
  was **never read**. Now the two are compared and the higher wins.
- Fixed duplicated / wrong-language tooltip keys.

---

## 0.3.4.10

- **Shock effect name** — added the missing `effect.more_balls.shock` lang key;
  it used to display as a raw translation key.
- **Demolisher** now scans along the path for the **first real block** instead of
  a fixed one-block lookahead.

---

## 0.3.4.9

- **Combined balls rendered no pieces** — `items/combo_ball.json` is four stacked
  `select` layers with only `when: 0..7`; indices 8/9 fell through to the fallback
  (wooden ball pieces). Added the cases and 8 `combo_piece` models.

---

## 0.3.4.8

- Added the missing quadrant textures (`charge_piece_8/9_*`) and short-name lang keys.
- `Fragment` gained `penetration` / `flags`, and `comboProfile` writes them back.
- **Demolisher**: breaks the next block along the path; mining level equals diamond tools.

---

## 0.3.4.7

- **Fragment names showed "copper"** — `SHORT_NAME` / `SHORT_ID` are index-aligned
  with `sources()` but had not been extended, and `BallFragmentItem` clamps the
  index, silently falling back to the last entry (copper). Added the missing
  entries plus **two startup self-checks**.

---

## 0.3.4.6

- **Illumination crashed the game** — `ClipContext` takes a `CollisionContext`,
  but `(Entity) null` was passed; `CollisionContext.of(null)` threw an NPE.
- **Shock had no effect on non-player mobs** — mob movement is AI-driven, so
  setting velocity is overwritten next tick. Now uses `MoveControl`.
- **Cutting tags** — `balls/solid` is what the stonecutter actually reads.

---

## 0.3.4.5

Seven fixes: tooltip entries, crossbow charge-texture sampling, illumination
position, shock on mobs, Demolisher 10, shock icon, redstone ball brightness.

---

## 0.3.4.4

Redid three crossbow charge textures **to the existing spec** — the base image
(`charge_base_crossbow.png`) is used verbatim and the ball is drawn only inside
the `(2..7, 2..7)` 6×6 region. Verified: **zero pixels changed outside that region**.

---

## 0.3.4.3

- **Redstone / diamond balls showed the copper texture** — their models were
  copy-pasted from `copper_ball.json` and the `layer0` reference was never updated.
  The texture files themselves were fine; nothing pointed at them.
- Full-tree audit of all 11 balls: `items → models → textures`.

---

## 0.3.4.2

- Fixed three "cube instead of sphere" range checks (pulse / sense / wisdom).
- Redid three crossbow charge textures and the redstone snowball texture.

---

## 0.3.4.0

**Major release — 1 new potion effect, 3 new items, 1 heat mechanic change.**

### New effect: Shock (`shock`)

| Level | I | II | III | IV | V |
|---|---|---|---|---|---|
| Interval (ticks) | 20 | 17 | 13 | 8 | 2 |

Periodically **forces a random turn** — both the view direction and the movement
direction (horizontal ±60°, pitch ±30°).

**"Ignores potion immunity" uses the official mechanism.** `LivingEntity.addEffect`
goes through `CommonHooks.canMobEffectBeApplied`, which only posts
`MobEffectEvent.Applicable`. Returning `Result.APPLY` there makes the effect
unstoppable — no vanilla code touched.

### New item: Redstone Ball

Damage 2 / tough 5 / weight 5 / bounce 0 / charge 6 / rarity 5 / stone sound /
cross-shaped redstone block recipe → 4 / 50% drops 5–7 redstone dust.

**【Pulse】** on hit, centred on the impact point, radius 7:

| Target | Condition | Result |
|---|---|---|
| Qualifies | any metal gear **or** armour > 20 | **Shock 3s** |
| Upgraded | ≥4 metal pieces **or** armour ≥ 40 | **Shock II 5s** |

Plus three expanding spherical particle waves on a strict timeline.

> Note: "initial opacity 40%" could not be implemented literally — vanilla
> dust particles take **RGB24 only** and discard alpha. Brightness and density
> ramps approximate the fade.

### New item: Redstone Snowball

Damage 0 / weight 3 / charge 6 / snowball x4 + redstone block recipe → 4 /
30% drops 1–2 redstone dust / red trail while flying.

**【Illumination】**: a square pyramid opens downward — the base half-diagonal is
`height × tan(30°)`. Creatures inside glow for 1s per tick spent there.
**No line-of-sight check, so invisible and phased creatures are lit too.**

### New item: Diamond Ball

Damage 15 / weight 7 / tough **250** / bounce 0 / charge 3 / built-in
**【Penetration 3】** / cross-shaped diamond block recipe → 4.

**【Demolisher】**: smashes blocks in its path — each one costs 1 durability and
slows it to 85%; below the settle threshold it stops. Hardness < 0 blocks
(bedrock etc.) are immune.

**【Lens】**: on a clear day, heats the 5×5×2 blocks and creatures directly below
it every 5 ticks, **even while at rest**. Ores follow the existing heat mechanic;
wood, dirt etc. start emitting flame particles at 50 and genuinely catch fire at 100.

Layered drops: 60% → 4–6 diamonds / extra 20% → 1–3 diamonds / 80% coal only if
no diamond dropped at all.

### Mechanic change: heat only decays after 1 second of no increase

Previously heat began decaying the moment no heat source was applied, so it could
never accumulate. Now there is a **20-tick grace window**, shared by both the
creature and block heat systems.

---
