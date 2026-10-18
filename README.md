<div align="center">

# oyvey-ported
Kosher client base ported to modern Minecraft by [@cattyngmd](https://github.com/cattyngmd)

<img src="images/ui.png" width="90%" />

# Why
![](https://i.imgur.com/VYjIphG.png)

# What
Add stuff to it like you would add stuff to your 1.12.2 OyVey paste

</div>

## Download / Install
A prebuilt mod jar is included in this repository:
[`dist/oyvey-pearlcatch-1.21.11-v1.0.0.jar`](dist/oyvey-pearlcatch-1.21.11-v1.0.0.jar)
(SHA-256 `a5b98e2f0c15925af9bf511603146a1afc7862de84d4860d41b0f850476ae0a6`).

1. Install Fabric Loader 0.18.4 or newer for Minecraft 1.21.11.
2. Add [Fabric API](https://modrinth.com/mod/fabric-api) 0.141.1+1.21.11 (or newer for 1.21.11) to `mods/`.
3. Put the jar above in `mods/`.

Requires Java 21. This is a client-side mod.

## Build
Requires JDK 21:
```
./gradlew build
```
The jar is written to `build/libs/`. `./gradlew check` runs the standalone solver and attempt-lifecycle checks
described below.

## PearlCatch (Combat)
Throws a wind charge so it meets one of **your own** ender pearls while it is in flight; pearls whose owner is not
positively you (enemies, teammates, unresolved owner) are never tracked. The aim is solved exactly from vanilla
physics (`InterceptSolver`), including the shooter's own momentum (which is what handles diagonal/sprinting throws),
network latency, and wind-charge bursts that will push the pearl. The pearl path is cut off at its predicted block
impact.

Each pearl gets one initial attempt plus at most one retry. An attempt is classified from evidence, not from the use
having been sent: our charge spawned (a wind charge owned by you appeared), it burst, the burst was within reach of the
pearl, and the pearl's velocity then left free flight. Confirmed failure schedules the one retry; missing evidence is
`OUTCOME_UNKNOWN` and never triggers another throw by itself. A client-refused use (swap failed, item cooldown) does not
consume an attempt.

| Setting | Meaning |
| --- | --- |
| Range / Window | how far (distance from the pearl to your bounding box, one definition everywhere) to consider pearls / how many ticks ahead to search for an intercept |
| Tick Delay | 1 to 10 (default 2): client ticks between scheduling an attempt and executing it, for the initial attempt and the retry. Read live, so changing it applies at once |
| HitRadius | allowed along-track miss in blocks beyond the charge's per-tick sweep |
| Pick | `CLOSEST` (pearl nearest you), `EARLIEST`, or `BEST_FIT` |
| AutoLatency / ExtraTicks | lead = ping/50 ms + ExtraTicks. **Tune ExtraTicks first if shots land early/late** |
| WindBursts / BurstBoost / BurstRadius | model in-flight wind charges that will deflect the pearl; BurstBoost is the knockback scale |
| LineOfSight | skip intercepts whose flight path is blocked by blocks |
| Cooldown | ticks between throws (vanilla wind charge cooldown is 10). Gates execution only and runs alongside Tick Delay, not after it |
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
and the attempt lifecycle (retry limit, outcomes, Tick Delay, config fallback):
```
java -m jdk.compiler/com.sun.tools.javac.Main -d out \
  src/main/java/me/alpha432/oyvey/util/prediction/CatchTrack.java \
  src/test/java/me/alpha432/oyvey/util/prediction/CatchTrackCheck.java
java -cp out me.alpha432.oyvey.util.prediction.CatchTrackCheck
```
