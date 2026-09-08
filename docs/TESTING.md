# Test plan

A full pass over everything PaperLab does, in the order it is worth doing it. The point is
release readiness: what works, what is only assumed to work, and where the gaps are.

Each row carries an **evidence level**, and that distinction matters more than the checkmark:

| Level | Means |
|---|---|
| **code** | verified against engine or mod source; no run needed |
| **unit** | covered by an automated test in `./gradlew test` |
| **differential** | our bytes are decoded by the client mod's real code |
| **bench** | seen working on a live server from the console |
| **client** | needs a real player with the mod installed — cannot be automated here |
| **measurement** | a number that must be reproduced, not just observed |

---

## 0. Preparation

```bash
cd paperlab-core && ./gradlew applyPatches && ./gradlew build createPaperclipJar
cd ../paperlab && ./gradlew test jar
```

The core build must run `build`, not only `createPaperclipJar`: `build` includes
`scanJarForBadCalls` and Paper's own 9143 tests. Skipping it is how two real defects — a
bypassed `PlayerGameModeChangeEvent` and a missing `minecraft.command.player` permission —
went unnoticed for weeks.

A control instance on **stock Paper** with the same plugin is part of the setup, not an
optional extra. Half the claims below are of the form "with our core X, without it Y", and
they cannot be checked from one server.

---

## 1. Core: observer

The headline feature, and the one with a number attached.

| # | Check | Level | How |
|---|---|---|---|
| 1.1 | A player at `sim=5` holds 121 ticking chunks | measurement | `/labchunks`, count ENTITY_TICKING |
| 1.2 | An observer adds **zero** ticking chunks | measurement | live player + ghost bot, world total must stay 121 |
| 1.3 | Convergence takes about 30 s | measurement | `/labchunks` every 5 s after `/ghost` |
| 1.4 | Switching off takes seconds | measurement | same, in reverse |
| 1.5 | Observer takes no mobcap | measurement | `/log mobcaps <observer>` next to a live player's |
| 1.6 | Mobs do not target the observer | client | stand in front of a zombie in ghost mode |
| 1.7 | EAR does not wake mobs | bench | mobs in a frozen chunk stay frozen when the observer enters |
| 1.8 | Blocks still place and break | client | this is what separates it from spectator |
| 1.9 | Invisible to other players and in the tab list | client | needs a second player |
| 1.10 | Mode cleared on quit | bench | `/ghost`, quit, rejoin, `/carpet ghost` shows off |
| 1.11 | On stock Paper the warning is red and honest | bench | control instance |

**1.2 is the acceptance criterion for the whole project.** If the world total exceeds 121
with an observer present, everything downstream is unreliable.

---

## 2. Core: spawn trace

| # | Check | Level | How |
|---|---|---|---|
| 2.1 | Columns add up in the right units | code | `cap`/`passes` per pass, the rest per position |
| 2.2 | `plugin` is zero on clean Paper | bench | any nonzero value means something is throttling spawns |
| 2.3 | `spawned` matches reality | measurement | compare with a counter on a real farm |
| 2.4 | Collection is off with no subscribers | code | `SpawnTrace.enabled` is one volatile read |
| 2.5 | Turning it on costs no measurable TPS | measurement | `/log tps` with the trace on and off |
| 2.6 | Reduced version works on stock Paper | bench | control instance, only spawned/cancelled |
| 2.7 | The trace collects under a real spawn cycle | measurement | overworld: `cap 20658 · passes 668 · position 1618 · spawned 5` |

2.7 is worth knowing before anyone repeats it: the bench's flat nether has nowhere for a mob to
spawn, so the spawn loop never reaches a chunk there and the trace reports **no data** — correctly,
but it looks identical to a broken instrument. Check the trace in the overworld.

2.5 matters: a lab that measures time must not pay for its own instruments. If the trace
costs TPS, every measurement taken with it on is suspect.

---

## 3. Core: bots

| # | Check | Level | How |
|---|---|---|---|
| 3.1 | Inherits position, look, dimension, mode, flight | bench | `/player Bot spawn` with no arguments |
| 3.2 | `doTick()` runs in the connection phase | code | the hook in `MinecraftServer.tickChildren` |
| 3.3 | Skin loads, including cape and second layer | client | needs a real client to see it |
| 3.4 | `fakePlayerNameSuffix` splits UUID from skin | bench | spawn a bot with a live player's name plus a suffix |
| 3.5 | Actions with `once/continuous/interval N` | bench | `/player Bot use continuous` on a lever |
| 3.6 | `interval 1` is faster than `interval 2` | measurement | the classic hold bug; count uses per minute |
| 3.7 | Movement holds until changed | bench | `/player Bot move forward`, watch it walk |
| 3.8 | Boats, minecarts, horses | client | `/player Bot mount` |
| 3.9 | Auto-respawn after death | bench | `/player Bot respawn on`, then kill it |
| 3.10 | Inventory and XP survive spawn/kill | bench | give items, kill, respawn |
| 3.11 | Bots are removed on plugin disable | bench | `/reload` or stop; no ghosts in the tab list |
| 3.12 | A bot holds the same chunks as a player | measurement | 121 each, world total exactly 242 |
| 3.13 | A bot is in the per-player mobcap census | measurement | `/paper playermobcaps`, identical to a player's |
| 3.14 | A bot takes damage and dies like a player | bench | killed by summoned zombies |

**3.12 to 3.14 settle the largest open question about bots.** Matching call order is a necessary
condition, not a proof of equivalence, so it was measured instead.

A bot was placed 800 blocks from a live player so their chunk sets could not overlap:

```
oCreker    @ chunk 0,0    within 12: ENTITY_TICKING 121
EqBot_bot  @ chunk 50,50  within 12: ENTITY_TICKING 121
world total:                         ENTITY_TICKING 242
```

121 each and a world total that is exactly their sum — no overlap, no shortfall. The mobcap was
checked with Paper's own `/paper playermobcaps` rather than ours, so the instrument is
independent of the code under test: the bot appears with its own per-player caps, identical in
shape to the player's. Incidentally, zombies summoned next to the bot killed it, which is one
more way it behaves as a player does.

A bot is therefore a reference player for chunk load and mobcap purposes, not merely a load
instrument.

---

## 4. Rules

| # | Check | Level | How |
|---|---|---|---|
| 4.1 | Ten rules listed, categories work | bench | `/carpet list`, `/carpet list experimental` |
| 4.2 | A set does not survive a restart | bench | set, restart, `/carpet list` |
| 4.3 | `setDefault` does survive | bench | same with setDefault |
| 4.4 | `removeDefault` clears it | bench | and `/carpet list defaults` is empty |
| 4.5 | `fillUpdates false` — no neighbour updates | bench | `/fill` a torch next to a wall, it stays |
| 4.6 | `hardcodeTNTangle` — identical scatter | measurement | two TNT runs from the same cannon |
| 4.7 | `microTiming` — collection actually starts | client | wool markers plus `/log microtiming` |
| 4.8 | `perWorldTick` — freeze in the nether does not stop the overworld | bench | `/execute in the_nether run tick freeze` |
| 4.9 | `tickCommandCarpetfied false` hides the nodes | bench | `/tick toggle` becomes unavailable |
| 4.10 | Core rules are refused on stock Paper | bench | control instance, `(needs our core)` |
| 4.11 | Rules are reverted on plugin disable | code | `LabRules.resetAll` in `onDisable` |

4.11 is worth checking live as well: a disabled plugin leaving behind a modified server is
exactly the silent failure the whole rule design exists to prevent.

---

## 5. Loggers and the HUD

| # | Check | Level | How |
|---|---|---|---|
| 5.1 | `/log` list matches Carpet's layout | bench | separator, header, buttons, `[X]` |
| 5.2 | TPS matches Carpet's formula | code | `1000 / max(target mspt, actual mspt)` |
| 5.3 | TPS is zero while frozen, uncapped while sprinting | bench | `/tick freeze`, `/tick sprint` |
| 5.4 | Several mobcap subscriptions at once | bench | `/log mobcaps` for two players |
| 5.5 | `full` shows backoff and the limiting player | bench | two players in one chunk |
| 5.6 | Counters in the tab list | bench | `/log counter white` |
| 5.7 | `item`, `microtiming`, `movement` | client | need real events |
| 5.8 | Clearing the last subscription clears the footer | bench | `/log clear` |
| 5.9 | TAB integration: the footer goes to TAB | client | verified and fixed by Florentem on his own server |
| 5.10 | Concurrent subscribe/read without a CME | unit | `LabLoggerConcurrencyTest` |

5.9 was a real debt for a while: the code was written and reasoned about while TAB had never
been installed alongside it. Florentem ran it on a server with TAB and fixed what broke.

---

## 6. Counters

| # | Check | Level | How |
|---|---|---|---|
| 6.1 | A hopper into wool is picked up | bench | place one, `/counter` |
| 6.2 | `scan` finds existing hoppers | bench | `/counter scan 32` |
| 6.3 | Items are counted with metadata | unit | `LabCountersTest` |
| 6.4 | Rate per hour, monotonic clock | unit | same |
| 6.5 | Zero interval gives `null`, not infinity | unit | same |
| 6.6 | A real farm | client | a hopper into wool reported 9000/h, exactly a hopper's throughput |
| 6.7 | Output matches Carpet's grammar | bench | `/counter white` |
| 6.8 | `hopperCountersUnlimitedSpeed` lifts the ceiling | measurement | 9000.0/h with the rule off; a 1728-item chest counted out in about a tick with it on; confirmed on a real farm with a live client |
| 6.9 | The rule costs nothing when off | code | one volatile read before the wool check |
| 6.10 | Only wool-facing hoppers are affected | code | `LabHopperCounters.unlimited` |
| 6.11 | The hopper is actually emptied, once | measurement | one stack in, 64 counted, hopper reads 0 |
| 6.12 | Nothing is counted twice | measurement | 1728 in the chest, 1727 counted across three runs |
| 6.13 | `scan` works from the console | bench | `execute positioned 8 -60 9 run counter scan 4` |

**6.6 read 9000 items per hour, and that number was the instrument, not the farm.** One item
per eight ticks is 2.5 per second is 9000 per hour — a hopper's throughput exactly. Any farm
above that would have read 9000/h as well, which is why the reading looked so convincing. With
`hopperCountersUnlimitedSpeed` on, the same rig counted a full 1728-item chest out in about a
tick: the ceiling is now the farm's.

### The counter was multiplying its own contents

Found while testing the rule above, and it had nothing to do with it. `LabCounters.tick()` read
the hopper through `block.getState()` — a **snapshot**. Clearing a slot on the snapshot and
calling `update()` did not reach the world: the hopper kept its items, and the same items were
counted again on the next tick, and the next.

A hopper holding one stack and fed by nothing reported **1 735 111 items an hour** and never
emptied. Nothing threw, nothing was logged. The fix is `block.getState(false)` — the live block
entity — and then no `update()` at all.

It survived this long because of how it presents. Point a hopper at wool under a slow feed and
the first reading is 9000/h, which is exactly right for a vanilla hopper; the error only becomes
visible once items accumulate faster than they are consumed. **A wrong number that matches the
number you expected is the hardest kind to notice**, and this project has now produced two of
them from the same rig.

The plugin's own javadoc still holds: accounting can be a tick off, because a plugin cannot
intercept `ejectItems`. That is immaterial for comparing rates and would matter for sub-tick
work.

---

## 7. Client mods

Everything here needs a real client. This is the largest block that cannot be automated.

| # | Check | Level | How |
|---|---|---|---|
| 7.1 | ChunkDebug: map opens, F6 | client | the handshake is server-initiated |
| 7.2 | ChunkDebug: standing still produces no traffic | client | deltas |
| 7.3 | ChunkDebug: unloaded chunks disappear | client | `chunk_unload`; the map used to only grow |
| 7.4 | MiniHUD: TPS and mobcaps | client, differential | `ServuxHudDifferentialTest` |
| 7.5 | MiniHUD: structure boxes | client, differential | `ServuxEntitiesDifferentialTest` |
| 7.6 | MiniHUD: NBT under the crosshair | client | needs `paperlab.servux.entities` |
| 7.7 | MiniHUD: debug renderers via `paperlab.debugdata` | client | without OP |
| 7.8 | Litematica: paste with no chat spam | client | `entityDataSync` must be on |
| 7.9 | Litematica: powered dust stays powered | client | this is what `UPDATE_SKIP_ON_PLACE` is for |
| 7.10 | Litematica: chests and hoppers | client | contents survive the paste |
| 7.11 | Litematica: rotation and mirroring | client | verified on a real schematic |
| 7.12 | Tweakeroo: inventory preview | **untested** | the channel is written, never exercised |
| 7.13 | Every channel is silent without permission | bench | revoke and check the mod sees no server side |

7.10 and 7.11 were the risky ones — a mistake in the transforms does not fail loudly, it
silently shifts or rotates the build. Both have now been checked on a real schematic.

---

## 8. Capture & Playback

The newest subsystem and the least exercised.

| # | Check | Level | How |
|---|---|---|---|
| 8.1 | Wire format matches the mod | differential | `CPlayWireTest`, `CPlayDifferentialFuzzTest` |
| 8.2 | Handshake, asset history, player cache | client | the mod's Info tab lists both extensions |
| 8.3 | The channel is closed without `paperlab.cplay` | bench | this was a real hole; check it stays closed |
| 8.4 | Asset import writes a file safely | code | server-side UUID names, atomic write |
| 8.5 | Recording a region | client | `/capture start`, verified on redstone |
| 8.6 | Playing signals back | client | lever, repeaters and lamp reproduce the run |
| 8.7 | A recording opens in the mod's editor | client, differential | `CPlaySequenceDifferentialTest` |
| 8.8 | What the editor shows is what plays | code | playback reads the sequence, not the frames |
| 8.9 | **Editor changes are not persisted** | **gap** | see below |
| 8.10 | **Signal override on the hot path** | **untested** | `SignalGetter.getSignal`, the early-out |
| 8.11 | The block-event loop under playback | code + bench | terminates by construction, see below; three concurrent playbacks at 1.0 ms/tick |
| 8.12 | Collaborative session between two clients | **untested** | needs two players with the mod |
| 8.13 | **Server Settings tab is empty** | **gap** | we advertise Core but never send its packet 6 |

**8.9.** A session accepts edits and relays them to the other participants, but nothing applies
them to the stored asset: `SessionState` keeps the deltas in memory and the file is untouched.
Edits therefore live only as long as the session and are gone after a restart. Applying them
means understanding the mod's delta format, which has not been done.

**8.13.** The mod's Server Settings tab stays empty because the tab is filled by Core packet 6,
`GSServerSettingMapPacket`, and we implement only packet 10 of that extension. Those are
G4mespeed's own settings and nothing in Capture & Playback depends on them.

**8.11 was the top-ranked risk and is now settled by argument rather than by luck.**

`onRunBlockEventsLoop` replaces vanilla's loop condition, so a loop that never ends takes the
server with it — a hang, not a crash, which is the worse failure. The inner
`while (blockEventCount == 0)` sets `blockEventCount` from a snapshot of the queue size that it
does not re-read, so termination rests entirely on `handleReadySignalEvents` consuming at least
one event per iteration.

It does, and here is why. Before calling it the loop sets `microtick` to the head event's own
microtick, and `isEventReady` has exactly three outcomes:

* the head's phase is later than the current one — impossible, `BLOCK_EVENTS` is the last of the
  two phases and is the phase the loop runs in;
* both are `BLOCK_EVENTS` — ready iff `microtick >= event.getMicrotick()`, and `microtick` was
  just set equal to it;
* the head is `IMMEDIATE` — ready unconditionally.

So the head is always consumed, `frameIndex` only increases, and `signalFrame` is a fixed list
for the tick. Either the queue is non-empty and the loop exits at once, or the list drains and
the call returns false. **It cannot spin.**

Confirmed on the bench besides: three playbacks running at once, 1.0 ms per tick, no stall.

One real consequence remains, and it is a fidelity issue rather than a safety one. Because the
size is a snapshot, block events queued *by* a signal event during the same call are not seen —
the call returns false and vanilla's loop exits with them still queued, so they run on the next
tick. A one-tick deferral in a corner case, worth knowing before trusting a replay to the tick.

**8.10 matters for a different reason.** `getSignal` is called on every redstone update in
the world. The early-out is a static map emptiness check, which should be free — but "should
be" is not a measurement. Compare MSPT on a redstone-heavy contraption with playback idle and
with the plugin absent entirely.

### What a recording holds, and what it does not

A capture records **inputs**: a lever thrown, a plate stepped on, an observer firing. Dust,
repeaters, comparators and torches are not recorded — the circuit recomputes those, exactly as
it did while recording.

That is a deliberate limit, not an omission. The playback override is omnidirectional, so a
replayed repeater would power the dust beside it, which vanilla never does; and a replayed
component fights the one the circuit is computing at the same moment, with no principled answer
as to which wins. The cost is that a recording cannot capture a subsystem's output on its own —
only what drove it. Recording components faithfully would mean storing a direction on every
edge and making the override directional.

---

## 9. Permissions and holes

The permission audit found one real hole (an open Capture & Playback channel). These checks
exist so the next one is found here rather than on a live server.

| # | Check | Level | How |
|---|---|---|---|
| 9.1 | Every node appears in LuckPerms | bench | registered with Bukkit |
| 9.2 | `/carpet perms` matches the real state | bench | grant one node, re-check |
| 9.3 | Each channel closed without its node | bench | six channels, one at a time |
| 9.4 | No literal permission strings unregistered anywhere | code | grep for `hasPermission("` |
| 9.5 | `paperlab.*` grants everything | bench | flat tree |
| 9.6 | A viewing node does not grant a changing one | bench | `paperlab.counter` alone must not allow `reset` |
| 9.7 | Schematic pasting needs creative **and** the node | bench | both conditions |
| 9.8 | Entity NBT is refused beyond 128 blocks | bench | this limit is ours, Servux has none |
| 9.9 | Another player's NBT needs a separate node | bench | `paperlab.servux.entities.players` |

9.4 is worth automating: a literal that matches no registered node is exactly the shape of
the `paperlab.cplay.admin` defect, where an admin override silently did not exist.

---

## 10. Load and stability

| # | Check | Level | Result |
|---|---|---|---|
| 10.1 | Overnight run with bots and an active farm | **untested** | catches leaks and slow degradation |
| 10.2 | Cost of a running playback | measurement | +0.27 ms per tick, see below |
| 10.3 | Cost of the spawn trace while collecting | measurement | about +0.5 ms under an active spawn cycle |
| 10.4 | Chunk map while flying fast | **untested** | hundreds of changed chunks per second |
| 10.5 | Several players with different mod sets | **untested** | channels interfering |
| 10.6 | `/reload` with everything enabled | **untested** | rules reverted, bots removed, no duplicate registration |
| 10.7 | Server restart with saved defaults | bench | rules applied, nothing else |
| 10.8 | Memory over a long run | **untested** | asset store, chunk map delta state, subscriptions |

### How the numbers were taken

`/tick query` reports the mean and percentiles over 100 ticks. Each figure below is the mean of
20 such samples, taken two seconds apart, alternating between the two states so that any drift
in the bench cancels out.

The load is a field of 1681 redstone dust (41×41) driven by a pair of observers facing each
other, with a bot holding the chunks. The playback under test ran **in the nether** while the
load sat **in the overworld**, so anything it cost the overworld was the hook and not the work.

Bench idle, no load, no instruments: 0.47 ms.

| State | Mean | P95 |
|---|---|---|
| load, no playback | 2.46 ms | 6.16 ms |
| load, playback running | 2.73 ms | 6.46 ms |
| no load, no playback | 1.51 ms | 2.22 ms |
| no load, playback running | 1.65 ms | 2.27 ms |

**A running playback costs about 0.14 ms per tick on its own** — that is the last pair, where
there is no redstone for the hook to touch. Under the redstone load the total cost is 0.27 ms,
so roughly 0.13 ms of it is `isSignalOverridden` being consulted on every signal query in a
world that has no playback at all.

That share was measurably larger before `stateOf` gained its single-entry cache: 0.37 ms total
against 0.27 ms after. Both figures sit close to the noise of this bench, so treat the
improvement as indicative rather than exact — the direction was consistent across every
alternation, the magnitude was not.

**What this means.** The instruments are cheap but not free. Nothing here justifies leaving a
playback running while taking a measurement, and the documentation's claim that an idle
subscription costs "one volatile read" holds: with no playback anywhere, the difference from a
plugin-free server is below what this bench can resolve.

### A caution about measuring this

The first version of this experiment concluded that the hook cost 0.37 ms. It did not isolate
the hook at all: a running playback ticks, reads frames and updates neighbours, and `/tick
query` is server-wide, so most of that figure was the playback doing its job. The number only
became meaningful once the same comparison was repeated with the redstone load removed. A
measurement that cannot be wrong is not a measurement.

---

## 11. Tick zones

| # | Check | Level | Result |
|---|---|---|---|
| 11.1 | A frozen zone holds entities | measurement | TNT fuse 80 → 80 over four seconds |
| 11.2 | A frozen zone holds blocks and fluids | measurement | water did not spread |
| 11.3 | Unfreezing resumes rather than resets | measurement | 80 → 60 → 20, exactly 20/s |
| 11.4 | The rest of the world is untouched | measurement | 100 blocks away, water flowed in the same tick |
| 11.5 | `rate 200` accelerates the zone | measurement | fuse 3800 → 3190 over 3 s ≈ **203 zone ticks/s** |
| 11.6 | The world pays little for it | measurement | server 1.7 ms/tick, target rate still 20.0 |
| 11.7 | `step n` advances blocks by exactly n | measurement | water spread on the step and not before |
| 11.8 | `step n` advances **entities** by exactly n | measurement | fuse dropped by exactly 30 on `step 30`, by 1 on `step 1` |
| 11.9 | A rate below 20 slows the zone | measurement | 10 ticks/s at `rate 10` |
| 11.16 | A zone at 20 is indistinguishable from open world | measurement | 20.1 vs 20.2 ticks/s |
| 11.17 | The server's rate does not drag the zone | measurement | server at 40: zone holds 20.2, open world runs 40.4 |
| 11.18 | A tick scheduled across the border survives | measurement | water flowed out of a 200 r/s zone and on for its full range |
| 11.19 | Random ticks run inside a zone, at its rate | measurement | 66 vs 65 farmland left at equal rates; 92 of 100 dried at rate 200 |
| 11.20 | A piston and the slime it pushes stay in step | measurement | identical layout at each of five steps, frozen and stepped together |
| 11.10 | The dump records order within a tick | bench | 40 ticks, 124 events, phases and sequence numbers |
| 11.11 | Zone commands work from the console | bench | create with an explicit world, console focus |
| 11.12 | Two zones, different rates, one world | **untested** | the code allows it; nothing checks it |
| 11.13 | An entity crossing the border mid-flight | **untested** | which loop ticks it that frame |
| 11.21 | Precipitation effects in a chunk split by a border | **untested** | the ice and snow gate is per position, the cauldron and lightning-rod paths in `tickPrecipitation` are not |
| 11.14 | Cost of the zone hooks | measurement | **below this bench's resolution**, see below |
| 11.15 | The player-facing half | **untested** | wand, highlight, focus redirect of `/tick`, zone permissions |

### What the hooks cost, and why there is no number

Two attempts, both inconclusive, and the second was worse than the first. Recorded because the
temptation is to publish the difference of the means and call it a result.

The design: three states, alternated so that bench drift cancels — **A** no zones at all, so
`hasActiveZones` is false and every hook is two volatile reads; **B** a zone exists but far from
the load, so every hook does the chunk-index lookup and misses; **C** a zone covers the load at
rate 20, so the lookup hits.

First run, 2646 hoppers and 400 armour stands, 5.6 ms per tick, five rounds of six samples:

| A | B | C |
|---|---|---|
| 3.54 ms | 3.98 ms | 3.38 ms |

The spread **within** state A was 3.08 to 4.50 ms — larger than any difference between states —
and C, where a zone covers the load, came out cheaper than A, where no zone exists at all. That
cannot be true, so the run measured nothing but the bench.

Second run, load raised to 3000 armour stands and 2646 hoppers (17.3 ms per tick), six rounds of
eight samples, medians rather than means:

| A | B | C |
|---|---|---|
| 20.1 ms | 21.4 ms | 21.1 ms |

Worse. Within-state spread 17.5 to 22.6 ms for A alone, and a steady upward drift across the run.
A heavier load made the bench noisier rather than the signal clearer.

**What the data does support:** with roughly 8600 hook calls per tick — 3000 entities at two
lookups each plus 2646 block entities — no difference above about 2 ms per tick was resolvable.
That is an upper bound of roughly 0.25 µs per call and no lower bound at all. The claim that an
idle hook is two volatile reads is still only a claim about the code.

Settling it needs a different instrument: a sampling profiler attributing tick time to
`LabTickZones`, or a quieter machine. `/tick query` is server-wide and this bench drifts by ±20%
over a minute; it cannot see a sub-percent effect, and no number of samples will change that.

### Two implementations of one feature

The defects above were symptoms; this was the cause, and it is worth writing down because the
shape of it is common.

A zone had **two implementations selected by its rate**. `isAccelerated()` meant "faster than
twenty". Above that the zone ran its own loop on its own clock, `zoneGameTime`. At or below
twenty it had no loop at all: it was gated on and off inside the world's pipeline, and its
scheduled ticks stayed stamped on the world's clock. Speeding a zone up and slowing it down were
not two settings of one mechanism, they were two mechanisms — so of course they behaved
differently, and of course crossing between them lost work.

On top of that the two clocks shared one queue. A tick's trigger time came from `getGameTime()`,
which returns the zone's clock for the duration of a sub-tick regardless of *where* the tick is
being scheduled. A block outside an accelerated zone, told from inside to fire in two ticks, was
stamped two ticks into the zone's future — hundreds of world ticks away. The world drain never
reached it. The update was not late; it was gone. That is what "механизм ломался, просто терялось
обновление" was.

The rate was also defined against the world's frame rather than against time: the accumulator
added `rate / 20` per frame, so `/tick rate 200` on the server multiplied every zone by ten.

The rewrite replaces all of it with one rule — **a position is ticked by exactly one clock** — and
one path for every rate. Freezing becomes "the clock does not advance", so the machinery that
shifted every trigger time in a frozen zone by one per frame is gone. Random ticks, which were
gated out of the world pass and run nowhere, got the chunk phase they needed.

### The step and rate defect

Worth recording because of how it presented. `/zone step 30` printed "Stepping 30 ticks" and
the block pipeline advanced correctly — water spread, exactly on the step. A TNT fuse in the
same zone did not move at all. At `rate 10` the fuse ran at a full 20 per second while the
blocks around it ran at ten.

The cause was one call that was never made. `LabTickZones.shouldTickEntity` accounts for all
four zone states; `grep` over `paper-server/patches/` found exactly one occurrence of the name —
its own declaration. Entity ticking was gated only by `isEntityFrozen`, which read
`zone.isFrozen()` and knew nothing about a step or a rate.

Two changes: `isEntityFrozen` now reads `zone.isFrozen() && !zone.shouldTickNow()`, symmetric
with `isBlockFrozen`, which had that second half all along; and the entity gate in
`ServerLevel.tick` now consults `shouldTickEntity`, with accelerated entities excluded from the
frozen flag so the client is not told they have stopped.

The lesson is the same one this file keeps recording: a step that silently does half the work
is worse than a step that errors. The block half worked, which is what made it convincing.

---

## What is known to be untested

Collected here so it is not lost in the tables above.

1. The player-facing half of tick zones (11.15) — wand, highlight, the focus redirect of
   `/tick`, and the zone permissions.
2. `hopperCountersUnlimitedSpeed` against a real farm (6.8) — the rule that makes a counter
   measure the farm instead of the hopper, checked only by reading.
3. Editor changes are not persisted (8.9).
4. An overnight run, memory, `/reload`, and the chunk map under fast flight (10.1, 10.4-10.6, 10.8).
5. Two zones at different rates in one world (11.12), and an entity crossing a zone border
   in flight (11.13).
6. Cost of the zone hooks with no zones (11.14).
7. Collaborative sessions between two clients (8.12).
8. Tweakeroo channel (7.12) — written, never exercised, and not currently needed.
9. The mod's Server Settings tab (8.13).

Closed since this plan was written: hopper counters against a real farm (a hopper into wool
reported 9000/h, which is exactly a hopper's throughput); schematic pasting with chests and with
rotation; the TAB integration; and Capture & Playback end to end.

## Known risks, ranked

1. **The refill loop in `hopperCountersUnlimitedSpeed`** — a loop inside a tick, the same shape
   as 8.11 was. It ends when the hopper is full, so about 320 rounds; the guard at 4096 exists
   because that reasoning assumes a container above that does not refill itself. A hopper that
   hits the guard logs once and undercounts that tick rather than hanging, so the worst case is
   a wrong number and not a dead server.
2. **Cost of the signal hook (8.10)** — a hot path in every redstone update, unmeasured.
3. **Cost of the zone hooks (11.14)** — the gate is claimed to be two volatile reads and has
   no number next to it. Every zone hook sits on a per-entity or per-block-tick path.
4. **Instrument overhead** — now measured rather than assumed: 0.27 ms per tick for a running
   playback under a heavy redstone load. Small, but not zero, and it argues against leaving a
   playback running while measuring something else.
