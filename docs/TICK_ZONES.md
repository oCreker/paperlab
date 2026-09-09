# Tick zones

A cuboid region that ticks at its own rate, independent of the world around it.

## Why

Vanilla `/tick` is server-wide. Accelerate a farm with `/tick rate 200` and the player
accelerates too: you fall ten times faster, your own actions are ten times shorter, and every
other measurement on the server moves with it. Freeze it and the whole server freezes.

A zone separates the two. The world keeps running at 20 TPS while the region inside the zone
runs at whatever rate the experiment needs — measured on the bench at 203 zone ticks per second
with the server still at 1.7 ms per tick. Nothing outside the zone can tell.

## How it ticks

At the start of `ServerLevel.tick` each zone decides how many ticks it owes this frame
(`onWorldTickStart`), and the vanilla pipeline is gated accordingly: block ticks, fluid ticks,
block events, block entities and entities inside the zone are skipped when the zone is not due.
At the end of the world tick, an accelerated zone runs its own sub-tick loop:

```
runZoneTicks        scheduled block and fluid ticks inside the zone
runZoneBlockEvents  pistons, note blocks
runZoneEntities     entities inside, with the primed-TNT counter reset each sub-tick
runZoneBlockEntities  comparators, hoppers, furnaces
```

A rate below 20 is paced by a fractional accumulator, so `rate 10` ticks on every second frame
rather than half-ticking on each. Each zone keeps its own `zoneGameTime`, so scheduled ticks
inside it line up with its own clock and not the world's.

Lookups go through a chunk index (`ChunkPos.pack -> zones`), and every hook starts with a check
that zones are enabled and at least one exists — two volatile reads on a server with no zones.

## Commands

`/zone`, `/tick zone` and `/carpet zone` are the same tree.

Zones are addressed either by **name** or by **focus**. Focus is per player; the console has a
focus of its own, so the same commands work from a start-up script or RCON.

```
/zone list                                 every zone, its boxes, rate and freeze state
/zone create <name>                         in your own world, owned by you, and focused
/zone create <name> <world>                  from the console: no owner, not focused
/zone remove [name]                          the focused one if no name is given
/zone focus <name>                           subsequent commands act on this zone
/zone unfocus

/zone box add                                selection wand (snowball): left-click, right-click
/zone box add <x1> <y1> <z1> <x2> <y2> <z2>  by coordinates, into the focused zone
/zone box list
/zone box remove <n|all>
/zone box cancelselect                       drop the wand without adding a box

/zone highlight                              draw the chunk borders the zone owns
/zone highlight at <y>                       at a given height instead of your own
/zone highlight off

/zone rate <name> <rate>                     0.1 to 10000
/zone freeze <name>                          toggles; use unfreeze to be explicit
/zone unfreeze <name>
/zone step <name> <ticks>                    advance a frozen zone by exactly n ticks
/zone sprint <name> <ticks>                  run n ticks as fast as the server can
/zone dump <name> <ticks>                    write an event dump for the zone
/zone dump status | stop

/zone member add|remove <player>

/zone resync [name]                          repair: pull stranded ticks back onto the clock
```

The wand, `highlight` and `cancelselect` need a real player. Everything else works from the
console.

With a zone in focus, the vanilla commands redirect to it: `/tick rate`, `/tick freeze`,
`/tick toggle`, `/tick step`, `/tick warp` and `/tick query` all act on the focused zone
instead of the server.

Permissions:

| node | what it opens |
|---|---|
| `paperlab.tick.zone` | `/zone`, and `/tick` itself while a zone is focused |
| `paperlab.tick.global` | the world's own clock: `/tick` with no zone focused |
| `paperlab.tick.zone.admin` | driving a zone you are not a member of |
| `paperlab.tick` | the Carpet-style additions: `/tick toggle`, `warp`, `dump` |

`/tick` does not need operator level. Holding `paperlab.tick.zone` is enough to reach it, because
with a zone focused that is the command that drives the zone; without `paperlab.tick.global` the
same command refuses to touch the world. `/tick query` is open to anyone who can reach the
command - it reads and changes nothing.

## The dump

`/zone dump <name> <ticks>` writes `dumps/dump_<zone>_<timestamp>.log`: every scheduled tick,
block event, block change, entity tick and piston movement inside the zone, numbered in the
order it happened, with the tick and the phase it happened in.

```
=== TICK T+5 | Dim: world ===
  #0001 [SCHEDULED_FLUID_TICKS | FLUID_TICK] minecraft:water at 112, -60, 112
  #0002 [SCHEDULED_FLUID_TICKS | BLOCK_CHANGE] Block{minecraft:air} -> Block{minecraft:water}[level=1] at 112, -60, 111
```

`/tick dump` does the same for an area or for the whole world.

## What is measured

From the bench, with the observable being a primed TNT's fuse and the spread of water:

| | |
|---|---|
| frozen zone | fuse held at 80 across four seconds; water did not spread |
| unfrozen | fuse resumed from where it stopped, 20 ticks per second |
| isolation | 100 blocks from a frozen zone, water flowed in the same tick |
| `rate 200` | 3800 → 3190 fuse over three seconds ≈ **203 zone ticks per second** |
| cost of that | server at 1.7 ms per tick, target rate still 20.0 |
| `step 10` | blocks, fluids and entities advanced by exactly ten ticks |

## One clock per place

The rule the whole thing rests on: **a position is ticked by exactly one clock.** Open world is
ticked by the world, during the world's tick. A position inside a zone is ticked by that zone,
during that zone's sub-tick, at every rate including twenty. While a zone is ticking, positions
outside it do not tick — otherwise a chunk straddling a border would be ticked once by the world
and again by every sub-tick of the zone.

A scheduled tick is stamped on the clock of the place it is scheduled **for**, not on whichever
clock happens to be running. That is what makes the border work in both directions: a repeater
outside an accelerated zone, told from inside to fire in two ticks, gets those two ticks on the
world's clock. Delay is relative, so translating between clocks is only ever a matter of adding
it to the right one.

Freezing stops the zone's clock and does nothing else. Nothing is rewritten, nothing is shifted,
and relative order between pending ticks cannot be lost.

The rate is a rate in game time, not a multiple of the server's frame, so `/tick rate` on the
server does not drag zones along with it. A `/tick warp` does not change the length of a tick,
only how fast frames are produced, so a zone does follow game time through a warp — which is
what a region inside a world should do.

The zone's clock survives a restart. Its distance from the world's clock is saved in `zones.json`
and put back when the zone loads, because a scheduled tick goes to disk as a delay from the
*world's* game time and comes back at the same absolute time - which is a time on the zone's
clock. Start the zone level with the world instead and every pending tick in it lands as far in
the future as the two clocks had drifted apart, which reads in game as a machine that stopped and
an observer stuck lit.


## Parity

| | zone | open world |
|---|---|---|
| rate 20 | 20.1 ticks/s | 20.2 ticks/s |
| server at rate 40 | 20.2 ticks/s | 40.4 ticks/s |
| server at 40, zone at 10 | 10 ticks/s | 40 ticks/s |
| server at 40, zone at 200 | 201.2 ticks/s | 40 ticks/s |
| `freeze` then `step 30` | exactly 30 ticks | — |
| random ticks, equal rates | 66 farmland left of 100 | 65 left of 100 |
| random ticks at rate 200 | 92 of 100 dried in 30 s | — |

A sticky piston pushing a slime block and a stone, built identically inside a zone and in open
world, both frozen and stepped together: the layout matched at every one of five steps, including
the two ticks the moving blocks exist as `moving_piston`.

Players and vehicles carrying players are never frozen and never belong to a zone — you can stand
in a frozen zone and walk out of it.

Not part of a zone, because it belongs to the world rather than to a region: weather, the day
cycle, raids, mob spawning.
