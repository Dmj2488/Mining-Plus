# Heavy Mining (NeoForge 1.21.1)

A client-side mod that turns the flat vanilla arm-wave into a weighted pickaxe swing:

1. **Wind-up**: slow raise up and back
2. **Strike**: accelerating overhead arc into the block
3. **Impact**: camera jolt and a burst of crack particles
4. **Recoil**: small bounce back to rest

The swing speed adapts to each block. The mod fits a whole number of swings into the
block's break time, so the last hit always lands exactly when the block breaks
(about 0.5 s per hit, like the video).

## Build it

1. Install **JDK 21** and **IntelliJ IDEA** (Community is free).
2. Download the NeoForge 1.21.1 MDK template:
   https://github.com/NeoForged/MDK-1.21.1-ModDevGradle (Code > Download ZIP)
3. Unzip it, then in the template:
   - Delete the example code in `src/main/java/`.
   - Copy this mod's `src/` folder over it.
   - In `gradle.properties`, set `mod_id=heavymining` and `mod_group_id=com.example.heavymining`.
   - Delete `src/main/templates/META-INF/neoforge.mods.toml` (this mod includes its own).
4. Open the folder in IntelliJ and let Gradle sync.
5. Test: run the `runClient` Gradle task.
6. Build the jar: run `build`. The jar goes in `build/libs/`. Put it in your `mods` folder.

## Tuning (top of MiningAnimator.java)

| Setting | What it does |
|---|---|
| `TARGET_SWING_TICKS` | Time per hit (20 ticks = 1 second) |
| `WINDUP_END` | Larger values give a slower lift and a snappier strike |
| `RAISED` / `IMPACT` | The key poses (position plus rotation) |
| `SHAKE_PITCH` / `SHAKE_ROLL` | Camera jolt strength |
| `IMPACT_PARTICLES` | Particles per hit |

Camera shake also respects the game's **Screen Effect Scale** accessibility setting.

## Notes
- The animation only plays while holding a pickaxe (`#minecraft:pickaxes` tag) and mining a block.
- The warm lighting, depth of field and motion blur in the video come from a shader pack
  (for example Iris plus Complementary). Those effects are separate from this mod.
