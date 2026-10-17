<div align="center">

# oyvey-ported
Kosher client base ported to modern Minecraft by [@cattyngmd](https://github.com/cattyngmd)

<img src="images/ui.png" width="90%" />

# Why
![](https://i.imgur.com/VYjIphG.png)

# What
Add stuff to it like you would add stuff to your 1.12.2 OyVey paste

</div>

## PearlCatch (Combat)
Throws a wind charge so it meets an ender pearl that is already in flight. The aim is solved exactly from vanilla
physics (`InterceptSolver`), including the shooter's own momentum (which is what handles diagonal/sprinting throws),
network latency, and wind-charge bursts that will push the pearl. The pearl path is cut off at its predicted block
impact, and a throw that the client refuses (e.g. item cooldown) is retried instead of being counted as done.

| Setting | Meaning |
| --- | --- |
| Range / Window | how far to look for pearls / how many ticks ahead to search for an intercept |
| HitRadius | allowed along-track miss in blocks beyond the charge's per-tick sweep |
| Pick | `CLOSEST` (pearl nearest you), `EARLIEST`, or `BEST_FIT` |
| OwnPearls | also target your own pearls |
| AutoLatency / ExtraTicks | lead = ping/50 ms + ExtraTicks. **Tune ExtraTicks first if shots land early/late** |
| WindBursts / BurstBoost / BurstRadius | model in-flight wind charges that will deflect the pearl; BurstBoost is the knockback scale |
| LineOfSight | skip intercepts whose flight path is blocked by blocks |
| Cooldown | ticks between throws (vanilla wind charge cooldown is 10) |
| Momentum | `DISPLACEMENT` (default): the shooter's per-tick movement as the server sees it (position minus last tick's position). `VELOCITY`: the client's friction-scaled velocity, kept only to compare in game. **If shots miss while moving, try flipping this first** |
| Silent | restore your rotation right after the throw, in the same tick |
| Debug | log each solution through the client logger |

Verify the solver without the game (no dependencies):
```
java -m jdk.compiler/com.sun.tools.javac.Main -d out \
  src/main/java/me/alpha432/oyvey/util/prediction/InterceptSolver.java \
  src/test/java/me/alpha432/oyvey/util/prediction/InterceptSolverCheck.java
java -cp out me.alpha432.oyvey.util.prediction.InterceptSolverCheck
```
