# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## 1.3.0 — 2026-10-31

### Changed
- **Built for Paper 26.3.** The plugin's own code needed no change: every server internal it
  reaches for - `ServerPlayer.mobCounts`, `ChunkMap.getMobCountNear`, Moonrise's chunk holders,
  `net.minecraft.resources.Identifier` - survived the version bump. What moved is the build:
  `paper-api` and the fork jar, `api-version` in `paper-plugin.yml`, and the Spottedleaf
  libraries, which 26.3 publishes under the `ca.spottedleaf.leafpile` BOM. The old
  `ca.spottedleaf` coordinates shadowed the server's own `ioutil` and took eighteen servux tests
  down with `NoSuchMethodError: AbstractBufferOutputStream.writeBuffer()`.
- The jar carries a new version number for one practical reason: a 26.3 build and a 26.2 build
  must not share a filename on the bench.

### Known limits
- The differential tests still check the wire format against **26.2** client mods, because no
  26.3 builds of malilib, MiniHUD or g4mespeed exist yet. A green run says the encoder did not
  regress, not that it speaks 26.3.

## 1.2.5 — 2026-10-30

### Fixed
- **Updating malilib and MiniHUD on the client made the server unjoinable.** From malilib 0.29.6
  and MiniHUD 0.40.7 the servux data packets stopped being plain network NBT: they now carry an
  `int` length and a GZIP'd NBT stream, read through malilib's `DataByteBufUtils.fromByteBuf`.
  Metadata still uses the old framing. Sending the old one got the mod's reader to throw, leave
  the rest of the payload in the buffer, and vanilla to kill the connection on
  `found 42 bytes extra`. Both the HUD data packets (weather, spawn data, logger tick) and the
  entity and block-entity responses are on the new framing now; they share one writer.
- The differential tests run against the mod's own decoders again. They are the reason this is a
  fix and not a guess: the entity and block-entity half of the break was found by them, not by
  anyone noticing it in game. Ported to the 0.29.6 API - `getStringOr` and friends became
  `getString`, `contains` became `containsLenient`, `getCompound` returns malilib's `CompoundData`
  rather than a vanilla `CompoundTag`, and `setTransactionId` is gone from the protocol.

## 1.2.4 — 2026-09-12

### Fixed
- **A counter was invisible from another world.** There were sixteen counters *per world*, so one
  filled in the nether could not be read from the overworld, and `/counter` listed every world's
  counters while dividing all of them by whichever world the sender stood in. Two worlds' game
  times are two different clocks, so those rates were arithmetic on unrelated numbers - and with
  `perWorldTick` on, or a `/tick warp` that moved one world and not another, the answer was
  nonsense rather than merely wrong. Now sixteen counters for the server, all measured against the
  overworld's game time, exactly as Carpet does it.
- **A skeleton farm filled the chat with bows.** The item key included the stack's metadata, so
  every bow arrived with its own durability and got its own row - and the rows all rendered as a
  plain "Bow", because the display never showed the metadata that had split them. A hundred
  identical lines pushed the totals off the top of the screen. Keyed by the item now, as in
  Carpet, which is the only thing a throughput counter can mean.

### Added
- `/counter <colour> realtime`, Carpet's: the same counter measured against the wall clock rather
  than game time, which is what separates a slow farm from a slow server.

### Kept, deliberately
- `/log counter <colour>` still takes one subscription per colour and stacks them in the tab list.
  Carpet has no such thing and a farm has more than one output.

## 1.2.3 — 2026-09-09

### Changed
- **`maxTickRate` now bounds a sprint as well.** It was a ceiling on the rate you could *ask* for
  and a sprint ignored it, which missed the point: a sprint is the one thing that takes a whole
  core, because the tick loop sets its interval to zero and spins. The ceiling now puts a floor
  under a sprinted tick, so `/tick warp`, `/tick sprint` and `/zone sprint` cannot outrun what the
  server is allowed to do by any other route.

  Measured here, an empty world, 400 ticks warped at each setting:

  | ceiling | actual |
  |---|---|
  | 20 | 20.0/s |
  | 40 | 40.0/s |
  | 100 | 96/s |
  | 200 | 129/s |
  | 1000 | 129/s |
  | 10000 (default) | 3976/s |

  The ceiling is never exceeded, and below about a hundred a second it is hit exactly. Above that
  the operating system decides: a park costs around 7.7 ms on this machine however little it is
  asked for, so an interval shorter than that cannot be paced and the warp settles near 130
  instead. Shorter still and no park happens at all - which is why the default leaves a warp
  running flat out, as before.

## 1.2.2 — 2026-09-09

### Added
- **`maxTickRate` and `maxSprintTicks`** - ceilings on what `/tick rate`, `/zone rate`,
  `/tick warp`, `/tick sprint` and `/zone sprint` will accept. Vanilla's rate ceiling is ten
  thousand and its sprint has no ceiling at all, which on a shared server is one person's typo
  away from burying it for everyone. Checked in the command rather than in the Brigadier
  argument, because an argument's range is fixed when the tree is built and a rule is not.
  Neither changes what a value below the ceiling does, so a measurement taken under a lowered
  ceiling is the same measurement.

## 1.2.1 — 2026-09-09

### Added
- **`/zone resync`** (also `/tick zone resync`) - pulls a zone's pending scheduled ticks back so
  the soonest is due now. A repair tool for zones saved by a build that did not persist the clock
  offset: their ticks are stamped on a clock that no longer exists and never come due. Everything
  moves by the same amount, so a machine picks up with its own timing intact. Loaded chunks only.

### Changed
- **`/tick` no longer needs operator level.** A player holding `paperlab.tick.zone` can reach it,
  because with a zone focused that is the command that drives the zone - shutting them out of it
  left them with `/zone rate`, the same thing under a worse name. Reaching the command is not
  permission to move the world's clock: `paperlab.tick.global` still decides that, per subcommand,
  and it now applies whether or not any zone exists. `/tick query` reads and changes nothing, so it
  is no longer gated at all.

### Fixed
- **A zone's machines stood still after every restart, observer stuck lit.** A scheduled tick is
  saved as a delay from the world's game time and read back as `worldTime + delay`, so it comes
  back at the same absolute time - on the zone's clock, which is the clock it was stamped
  against. The zone's clock, though, restarted level with the world's, so every pending tick in
  it landed as far in the future as the two clocks had drifted apart. The offset between them is
  now saved in `zones.json` and restored with the zone. It is rewritten on every world save, so
  a crash recovers with the two still paired.
- **Handing a zone's ticks back to the world's clock did nothing for accelerated zones.** The
  handover used vanilla's `LevelChunkTicks.postponeTicks`, which returns early on a delta that is
  not positive - and a zone that has been running fast is *ahead* of the world, so its delta is
  negative. Deleting such a zone left every pending tick in it stranded in the world's far
  future. Replaced with a signed shift that also covers ticks in chunks that are loaded but not
  yet ticking.
- Chunk ticks are packed and unpacked against the world's clock explicitly. `getGameTime()`
  returns the zone's clock while a zone sub-tick is running, and a chunk saved in that window
  would have had its delays written against one clock and read against another.

## 1.2.0 — 2026-09-07

### Added
- **Tick zones** (`/zone`), merged from Florentem's fork and finished here. A cuboid region
  with its own tick rate, freeze, step and sprint while the rest of the world keeps running at
  20 TPS — vanilla `/tick rate 200` accelerates the player along with the farm, a zone does not.
  Measured at 203 zone ticks per second with the server still at 1.7 ms per tick. Selection
  wand, particle highlighting, owners and members, persistence in `zones.json`.
- **Event dumper** (`/zone dump`, `/tick dump`): every scheduled tick, block event, block
  change, entity tick and piston movement in a region, in the order it happened, with tick and
  phase.
- **`hopperCountersUnlimitedSpeed`** — a hopper pointing into a wool counter keeps pulling
  within the tick and ends it with no cooldown. Without it every counter is capped at 9000 items
  an hour, which is a hopper's throughput and not the farm's. Measured: 9000.0/h with the rule
  off, a full 1728-item chest counted out in about a tick with it on. Modelled on
  Carpet-TIS-Addition's rule of the same name.
- Rules `blockUpdates`, `tntRandomRange` and `hardcodeItemDrops`; ten rules in total.
- Wildcard permission groups (`paperlab.log.*`, `servux.*`, `cplay.*`, `rule.*`) with child
  mappings, so LuckPerms shows the tree.
- `/zone create <name> <world>` and a focus of the console's own, so zones can be built from a
  start-up script or RCON. `/counter scan` likewise scans around the command's position rather
  than a player's.

### Fixed
- **A counter multiplied its own contents.** `LabCounters.tick()` read the hopper through
  `block.getState()` — a snapshot — so clearing a slot never reached the world and the same
  items were counted again every tick. A hopper holding one stack, fed by nothing, reported
  1 735 111 items an hour and stayed full. Now the live block entity, `getState(false)`.
- **`/zone step` did not move entities**, and a zone below 20 r/s slowed blocks while entities
  kept full speed. `LabTickZones.shouldTickEntity` accounts for every zone state and was called
  from nowhere; `isEntityFrozen` now reads `isFrozen() && !shouldTickNow()`, symmetric with
  `isBlockFrozen`, and the entity gate in `ServerLevel` consults `shouldTickEntity`.
- The `CPlayManager.onBlockStateChanged` hook, lost in the fork being merged, is back in patch
  `0037`: without it a capture records pistons only.

### Documentation
- `TICK_ZONES.md` in English and Russian, written as documentation rather than a status report.
- The core's README is English now, with the Russian kept as `README.ru.md`.
- The test plan carries section 11 (tick zones) and records two things it could not establish:
  the cost of the zone hooks is below this bench's resolution, and both attempts are written
  down with their numbers.
- The block-event loop under playback — the project's top-ranked risk — is settled by argument:
  every iteration consumes one entry from a finite list, so it cannot spin.

## 1.1.0 — 2026-09-06

### Added
- Port of Carpet's markup (`paperlab.text.Msg`): command, rule and logger output now
  matches the mod exactly — same headers, buttons and colours.
- Rule categories, `/carpet list <category>` and `/carpet list defaults`.
- `item`, `microtiming` and `movement` loggers in the spirit of Carpet TIS Addition.
- `microTiming` and `perWorldTick` rules.
- `servux:tweaks` channel — inventory preview for Tweakeroo.
- Server side of the Capture & Playback protocol: `/capture`, `/playback`, `/cplay`.
- Differential tests: our packets are decoded by the client mods' real code.
- Documentation in English and Russian: commands, rules, permissions, client mods,
  architecture.

### Fixed
- **TPS was computed wrongly.** It used `Bukkit.getTPS()[0]` — a one-minute rolling
  average, sluggish and different from what Carpet shows. Now the instantaneous
  `1000 / max(target mspt, actual mspt)`, accounting for `/tick` freeze and sprint.
- **The Capture & Playback channel was open to everyone.** A player with the mod could
  create and import compositions — that is, write files to the server — without ever
  being granted `paperlab.cplay`. The permission is now checked at the channel entry.
- `paperlab.cplay.admin` did not exist as a permission: it was never registered, so
  there was effectively no admin override. Replaced with `paperlab.cplay.manage`.
- `hardcodeTNTangle` options did not match the printed value, so a duplicate button was
  appended to the list.
- `/log` from the console answers `For players only` instead of vanishing from the
  command tree.

### Removed
- Third-party mod jars and copies of g4mespeed sources. g4mespeed is GPL-2.0, which is
  incompatible with our GPL-3.0. The differential tests remain, but the files are now
  placed in `libs/` by hand, and the directory is in `.gitignore`.

### Licensing
- `THIRD-PARTY.md`: full audit. Expression was taken from Carpet (MIT) — the markup and
  command text; the attribution notice has been added. The other protocols are
  reimplemented and contain none of their code.
