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

/zone highlight zone|box <n...>|off          draw the borders with particles

/zone rate <name> <rate>                     0.1 to 10000
/zone freeze <name>                          toggles; use unfreeze to be explicit
/zone unfreeze <name>
/zone step <name> <ticks>                    advance a frozen zone by exactly n ticks
/zone sprint <name> <ticks>                  run n ticks as fast as the server can
/zone dump <name> <ticks>                    write an event dump for the zone
/zone dump status | stop

/zone member add|remove <player>
```

The wand, `highlight` and `cancelselect` need a real player. Everything else works from the
console.

With a zone in focus, the vanilla commands redirect to it: `/tick rate`, `/tick freeze`,
`/tick toggle`, `/tick step`, `/tick warp` and `/tick query` all act on the focused zone
instead of the server. `paperlab.tick.global` is what allows driving the server-wide tick while
zones exist; `paperlab.tick.zone` allows zone commands, `paperlab.tick.zone.admin` allows
driving a zone you are not a member of.

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

## Parity and limits

Freezing a zone is not the same as ignoring it. Separate work went into the cases where
skipping a tick would silently corrupt a measurement rather than simply pause it:

* scheduled ticks inside a frozen zone are **postponed**, not dropped, so a repeater mid-cycle
  resumes rather than losing its schedule;
* TNT, explosions, lightning, spawners, ice and snow, and random ticks are all held;
* players are never frozen, and neither is a vehicle carrying one — you can stand in a frozen
  zone and walk out of it;
* an entity in an accelerated zone is kept out of the world's entity tick and ticked in the
  zone's loop instead, so it is neither ticked twice nor skipped.

Known limits:

* **Two zones with different rates in the same world are untested.** The code allows it.
* Crossing the border mid-flight — an item or a minecart entering an accelerated zone — changes
  which loop ticks it on that frame. Sound in principle, unverified.
* A zone accelerated out of a freeze can shift the phase of a piston sequence by a tick if a
  block has not finished forming before the piston opens.
