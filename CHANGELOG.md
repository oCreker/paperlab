# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
