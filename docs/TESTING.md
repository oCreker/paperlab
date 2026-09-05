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
| 3.12 | A bot counts towards the mobcap like a player | measurement | `/log mobcaps <bot>` |

**3.12 is unproven and is the largest open question about bots.** Matching call order is a
necessary condition, not a proof of equivalence. Until a bot and a live player produce the
same mobcap and the same chunk tickets side by side, a bot is a load instrument rather than
a reference player. `LabBot`'s javadoc says as much.

---

## 4. Rules

| # | Check | Level | How |
|---|---|---|---|
| 4.1 | Six rules listed, categories work | bench | `/carpet list`, `/carpet list experimental` |
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

6.6 was the oldest debt in the project and is now closed: a hopper into wool reported 9000
items per hour, which is exactly what a hopper moves — one item per 8 ticks. The plugin's own
javadoc still holds, though: accounting can be a tick off, because a plugin cannot intercept
`ejectItems`. That is immaterial for comparing rates and would matter for sub-tick work.

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
| 8.11 | **The block-event loop under playback** | **risk** | see below |
| 8.12 | Collaborative session between two clients | **untested** | needs two players with the mod |
| 8.13 | **Server Settings tab is empty** | **gap** | we advertise Core but never send its packet 6 |

**8.9.** A session accepts edits and relays them to the other participants, but nothing applies
them to the stored asset: `SessionState` keeps the deltas in memory and the file is untouched.
Edits therefore live only as long as the session and are gone after a restart. Applying them
means understanding the mod's delta format, which has not been done.

**8.13.** The mod's Server Settings tab stays empty because the tab is filled by Core packet 6,
`GSServerSettingMapPacket`, and we implement only packet 10 of that extension. Those are
G4mespeed's own settings and nothing in Capture & Playback depends on them.

**8.11 deserves attention before release.** `onRunBlockEventsLoop` receives the queue size as
a snapshot parameter rather than re-reading it. Progress is guaranteed only if
`handleReadySignalEvents` always consumes an event at the current microtick. It looks
defensible by reading, and playbacks on the bench have not hung, but an infinite loop inside a
tick would take the server with it — worth exercising deliberately with several signals in one
tick and with a gap of several microticks.

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

Nothing here has been done. Everything below is new work.

| # | Check | Why it matters |
|---|---|---|
| 10.1 | Overnight run with bots and an active farm | catches leaks and slow degradation |
| 10.2 | MSPT with every subscription on vs none | the instruments must not cost measurable time |
| 10.3 | Chunk map while flying fast | hundreds of changed chunks per second |
| 10.4 | Several players with different mod sets | channels interfering |
| 10.5 | `/reload` with everything enabled | rules reverted, bots removed, no duplicate registration |
| 10.6 | Server restart with saved defaults | rules applied, nothing else |
| 10.7 | Memory over a long run | asset store, chunk map delta state, subscriptions |

10.2 is the one that decides whether this is a measuring instrument or a toy. Every claim in
the documentation about "one volatile read" and "no traffic while standing still" is a design
statement; none of them has a number next to it yet.

---

## What is known to be untested

Collected here so it is not lost in the tables above.

1. Bot / live player equivalence (3.12).
2. Editor changes are not persisted (8.9).
3. Instrument overhead — all of section 10, and 8.10 in particular.
4. Collaborative sessions between two clients (8.12).
5. Tweakeroo channel (7.12) — written, never exercised, and not currently needed.
6. The mod's Server Settings tab (8.13).

Closed since this plan was written: hopper counters against a real farm (a hopper into wool
reported 9000/h, which is exactly a hopper's throughput); schematic pasting with chests and with
rotation; the TAB integration; and Capture & Playback end to end.

## Known risks, ranked

1. **The block-event loop under playback (8.11)** — an infinite loop inside a tick hangs the
   server. Highest severity even at low probability.
2. **Cost of the signal hook (8.10)** — a hot path in every redstone update, unmeasured.
3. **Bots as a reference (3.12)** — if they are not equivalent to players, every measurement
   taken with them carries an unknown bias.
4. **Instrument overhead (10.2)** — unmeasured, and it undermines everything if real.
