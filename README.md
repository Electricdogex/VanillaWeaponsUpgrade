# Vanilla Weapons Upgrade

A server-side **Paper Minecraft Java Edition 26.3** plugin featuring craftable nuclear-style explosives, fallout, radioactive snow, and access-controlled Command Beacons with cooperative guard mobs.

**Current source version: 1.1.0 — release candidate.** Built against Paper API `26.3.build.244-stable`, using Java 25. Players need a compatible vanilla client; no client mod or resource pack is required.

This README describes the implemented build, including the explosive side of the plugin. **Homeworld/Overclock is disabled and absent from the menu.** Its experimental source is retained for future work.

## Contents

- [Build and installation](#build-and-installation)
- [Items and crafting](#items-and-crafting)
- [Nukes and terrain destruction](#nukes-and-terrain-destruction)
- [Fallout and nuclear winter](#fallout-and-nuclear-winter)
- [Command Beacons](#command-beacons)
- [Guards and shared threats](#guards-and-shared-threats)
- [SuperWithers and riding](#superwithers-and-riding)
- [Husked Guards and captured inventory](#husked-guards-and-captured-inventory)
- [Commands and permissions](#commands-and-permissions)
- [Configuration and saved data](#configuration-and-saved-data)
- [Implementation map](#implementation-map)
- [Limitations and testing](#limitations-and-testing)

## Build and installation

### Requirements

| Component | Requirement |
| --- | --- |
| Server | Paper 26.3; development used build 244 |
| Build/runtime Java | JDK/Java 25; Maven compiler target is release 25 |
| Build tool | Apache Maven 3.9+ |
| Client | Minecraft Java Edition compatible with the server |
| Optional dependencies | None required |

Other server versions, Folia, and alternative forks are not validated. This is a Maven source repository; build artifacts are excluded from Git.

### Windows PowerShell 5.1

Clone the repository and open PowerShell in its directory. Set `JAVA_HOME` to **your installed JDK 25 directory**, then run:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn.cmd --version
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

The JDK directory above is an example: change it to your installation. **Check the Java version printed by Maven**, not only `java -version`. The error `release version 25 not supported` means Maven, a compiler override, or a configured toolchain is using an older JDK. The build script reports failures instead of claiming a JAR was created.

### Linux/macOS or manual Maven build

```bash
export JAVA_HOME=/path/to/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"
bash build.sh
# Or: mvn --batch-mode --no-transfer-progress clean package
```

Successful builds produce:

```text
target/VanillaWeaponsUpgrade-1.1.0.jar
```

### Install or update

1. Stop Paper cleanly with `stop` in its server console.
2. Back up the worlds and the plugin's existing data directory.
3. Put the new JAR in `plugins/`; remove older copies of this plugin's JAR.
4. Start Paper with Java 25. Confirm VanillaWeaponsUpgrade enables without errors.
5. Review `plugins/VanillaWeaponsUpgrade/config.yml` and test in a disposable world copy.

Use `/vwu reload` for configuration changes. Use a **full restart for JAR updates**; do not use Bukkit `/reload` to replace the plugin. Possessed SuperWithers are removed on shutdown and must be built again after a restart.

### Local-only testing with a 7 GiB heap

In the test server's `server.properties`, use `server-ip=127.0.0.1`, `server-port=25565`, `online-mode=true`, and `enable-rcon=false`. After reading and accepting the [Minecraft EULA](https://www.minecraft.net/eula), launch from that server's directory:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -Xms7G -Xmx7G -jar paper.jar --nogui
```

Connect to `127.0.0.1:25565`. No port forwarding is needed. Heap allocation does not include all JVM/native memory or the Minecraft client's memory. A matching heap alone does not reproduce a production server's CPU, player load, other plugins, or world complexity.

## Items and crafting

Custom items use persistent metadata, a display name, and an enchantment glint. They have **no descriptive item lore**. Renaming a normal item does not give it the custom identity. Custom ingredients are prevented from being consumed in unrelated recipes; ordinary beacon crafting remains available.

Each recipe below produces one item. Spaces are empty crafting slots.

### Nether Core

Surround an ordinary Nether Star with eight Gold Ingots:

```text
G G G    G = Gold Ingot
G S G    S = ordinary Nether Star
G G G
```

The Nether Core is an explosive crafting ingredient, not a substitute for the star in an ordinary beacon recipe.

### Super TNT

Place a Nether Core in the centre and ordinary TNT in the four corners:

```text
T . T    T = ordinary TNT
. C .    C = Nether Core
T . T    . = empty
```

### Formidibomb

Surround Super TNT with eight Netherite Ingots:

```text
N N N    N = Netherite Ingot
N S N    S = Super TNT
N N N
```

### Corrupted Nether Star

Combine **one Command Block and one ordinary Nether Star in a crafting table**, shapeless. No world placement, adjacent beacon, or fusion machine is involved.

Command Blocks are not obtainable through ordinary vanilla survival progression. Server staff must deliberately provide them or define another acquisition system; this plugin does not add a Command Block recipe.

### Command Beacon

Use the normal beacon recipe, replacing its star with the Corrupted Nether Star:

```text
G G G    G = Glass
G C G    C = Corrupted Nether Star
O O O    O = Obsidian
```

## Nukes and terrain destruction

### Default explosive settings

| Weapon | Crater radius | Maximum depth below blast | Fuse | Visual radius | Base damage setting |
| --- | ---: | ---: | ---: | ---: | ---: |
| Super TNT | 32 blocks | 15 blocks | 80 ticks / about 4 seconds | 40 blocks | 22 |
| Formidibomb | 100 blocks | 25 blocks | 200 ticks / about 10 seconds | 150 blocks | 40 |

Ticks assume a server maintaining 20 TPS. Damage is distance-dependent and subject to the implementation's entity limits and protection checks; the table is not a guaranteed damage result for every target.

### Placing and detonating

Place the custom TNT item and prime it using normal TNT mechanisms such as ignition/fire or redstone. Tracked custom explosives also support chain priming. Breaking an unprimed custom explosive returns the custom item outside Creative mode. **Dispenser use is blocked** for these explosives.

The plugin records placed custom TNT and marks its primed TNT entities. It cancels their ordinary explosion and queues custom terrain work instead. Unmarked vanilla TNT does not become a nuke.

### What the explosion does

- Excavates a capped crater using an outward radial progression, rather than a single enormous vanilla explosion.
- Uses seeded variation to avoid a perfectly repetitive crater surface.
- Shows a bright blast, shockwave, mushroom-cloud/debris effects, sounds, and display/particle cover while terrain changes arrive.
- Gives the Formidibomb an additional intense flash, controlled by `visuals.formidibomb-flash`.
- Sends out a heat wave that attempts to ignite eligible wooden surfaces. Default reach is twice the crater radius, capped internally at 256 blocks; ignition is bounded by the configured fire budget.
- Applies exposed sand-family vitrification after excavation: sand/sandstone variants become glass; red-sand/red-sandstone variants become orange stained glass.
- Performs support cleanup for foliage and snow affected by destruction. Cosmetic debris and water-recoil effects are separate from Minecraft's actual fluid simulation.

These are fictional gameplay effects. The plugin does not simulate real nuclear physics.

### Terrain work and performance

The crater engine processes queued work on the server thread with an asynchronous chunk-request stage. It uses both a block-change cap and a processing-time budget. Defaults are **16,384 block changes per tick** and **10 ms of edit work**, with pause above **45 MSPT** and resume below **40 MSPT**.

Only already-generated chunks are requested for excavation. One explosion chunk is held at a time, and a partially unloaded area may therefore behave differently from a fully loaded arena. Default queue limits are six jobs overall, two Super TNT jobs, and one Formidibomb job in the respective processing groups.

Destruction normally suppresses block drops and block physics to reduce load. Cleanup and fluid updates reduce common artifacts, but do not make this equivalent to vanilla physics. Processing budgets are best-effort: lighting, networking, entity updates, shaders, and other plugins can still cause lag.

**Cancelling a job stops remaining work; it does not restore blocks.** Backups are the recovery mechanism.

## Fallout and nuclear winter

### Immediate aftermath

| Effect | Super TNT default | Formidibomb default |
| --- | --- | --- |
| Charged creepers requested | 3–5 | 10–15 |
| Animal mutation probability | 35% | 80% |
| Mutations | Pigs → zombified piglins; cows → mooshrooms | Same mutations |
| Nuclear winter | No | Three in-game days by default |

Creepers spawn only where the relevant chunks and surfaces are usable. A default cap of **20 plugin creepers per world** and a **90-second cleanup** limit the aftermath. Mutation work is capped at 60 animals per blast. Named animals are protected by default, and leashed animals are excluded.

Green fallout particles linger around blast sites. With fallout radiation enabled, affected Survival/Adventure players can receive **Nausea and Hunger**. Creative/Spectator players are excluded from that radiation penalty. The visual fallout duration defaults to 120 seconds.

### Radioactive snow

Formidibomb winter uses snowflake particles, green-tinted dust, and small snow display entities around eligible outdoor players, rather than relying on ordinary biome rain. Visual snowfall extends through the affected world; **actual snow-layer deposition is local to the crater**, sampled in loaded chunks.

The deposition radius defaults to 200 blocks when `fallout.snow-radius` is not explicitly configured. Placement checks require a valid supporting surface and exclude protected blocks. Winter is Overworld-only by default and persists across an ordinary restart. Its timer advances while the server runs.

`/vwu weather stop [world]` ends this nuclear-winter system. Command Beacon Permastorm/Permasun is a separate system and is controlled through its beacon menu.

## Command Beacons

### Ownership and access codes

The player placing a Command Beacon becomes its owner. On first opening, the owner must enter and confirm a **four-digit PIN** before using it. Afterwards, the owner opens the settings directly; other players must authenticate with the PIN.

Successful authentication grants a temporary 60-second access session. Five failed attempts lock that player/beacon combination for 30 seconds. A salted PIN hash is stored on the beacon; the PIN is not stored as plain text. This is a gameplay access mechanism, not protection against server operators or world-file editing.

The beacon and its pyramid power blocks share the access rules. **Effects and special weather must be disabled before dismantling them**, even for the owner. Sneak-right-click a supporting power block to access its parent beacon's menu. Overlapping protected pyramids can require access to more than one beacon.

An operator can recover a beacon with `/vwu beaconreset` while looking at it within eight blocks. This disables it, transfers ownership to that operator, and requires a new PIN.

### Power and Generosity

| Mode | Receives positive effects | Receives negative effects |
| --- | --- | --- |
| Power | Owner and whitelist | Other eligible players |
| Generosity | Owner and everyone except blacklist | Blacklisted players |

The owner is always positive during normal play. The temporary outsider preview deliberately overrides friendly status for testing. Whitelist and blacklist entries are separate UUID-based lists.

### Effects, range, and appearance

Select **up to three different positive effects and three different negative effects simultaneously**. Left-click an effect slot to cycle its choices; right-click to turn that slot OFF.

Positive choices: Speed, Haste, Resistance, Jump Boost, Strength, Regeneration, Fire Resistance, Water Breathing, Night Vision, Slow Falling, and **Armor Healing**. Armor Healing repairs one durability point on one worn item about every ten seconds; overlapping beacons share that player's repair cooldown. Friendly players receive a coloured aura.

Negative choices: Slowness, Mining Fatigue, Weakness, Hunger, and Nausea. Positive potion strength scales with pyramid tier: I / II / II / III; negative effects are level I. A valid pyramid and clear beam path are still required.

The **compass range selector** cycles through **32, 64, 96, 128, 192, and 256 blocks**. The selected radius is horizontal, with the same vertical reach above and below the beacon. Existing beacons default to 32 × pyramid tier until a radius is selected. Effects refresh every two seconds, so short potion effects can linger briefly after leaving coverage.

The visual design includes a command-block display inside the native glass cage, black orbital rings, a coloured power core, and five beam clusters with extra narrow rails. **Each cluster has its own choice of all 16 stained-glass colours, including black.** The four outer emitters are hidden. Display entities are decorative; the inner command block cannot execute commands.

Enabled, PIN-configured beacons keep their core/pyramid footprint loaded using at most four server chunk tickets each, even with Permastorm OFF. A maximum of 32 active zones / 128 tickets bounds this system. The active index is restored on restart. This does not force-load the entire effect radius or override a client's render distance.

Friendly buffs may reach through the configured spawn-protection zone. Hostile effects and offensive guard actions remain blocked there. Worlds listed in `protection.disabled-worlds` exclude all beacon effects.

### Weather and menu toggles

- **Permastorm, Power mode:** requests continuous thunderstorm until disabled or dismantled.
- **Permasun, Generosity mode:** requests continuous clear weather; it does not freeze the clock at daytime.
- If several weather beacons share a world, Power-mode storm requests win over clear-weather requests. The plugin records prior weather when taking control and restores it when its final request ends.
- **Possess Withers:** Power-only conversion of normally built Withers into SuperWithers.
- **Possess killed players:** Power-only creation of inventory-bearing husks after eligible guard kills; OFF by default.
- **Help friendly players:** Power-only combat assistance; OFF by default.
- **Friendly wither balls:** ON by default; turning it OFF permits destructive/incendiary skulls.
- **Outsider test:** 30-second owner preview of hostile affiliation. Survival defenses can actually kill the tester; this does not automatically enable possession or other toggles.

These options are per-beacon. Weather requests affect the whole world, not just the selected radius.

## Guards and shared threats

Command Beacon defenses can use lightning, Wither Skeletons, SuperWithers, and possessed player husks. Friendly/hostile affiliation follows the beacon's mode and lists.

Ordinary proximity aggression starts when an eligible enemy enters within **five blocks of the pyramid's power blocks**, not merely anywhere in the full potion radius. Unauthorized dismantling attempts or attacks on a possessed guard also report the attacker to that beacon's shared threat list.

Ground guards pursue remembered enemies they can see, while SuperWithers can respond as heavier backup after a short delay. Targets are normally forgotten when that player dies. Deactivation, mode changes, and a server restart also reset the in-memory threat session. Friendly status takes precedence over remembered hostility.

With **Help friendly players** ON, uncancelled damage to a friendly player reports the attacker, including projectile shooters. A friendly player damaging a mob can also request assistance. Player attackers become remembered threats; mob opponents are shared for 30 seconds and refreshed by qualifying combat. Starting a PvP fight alone does not frame the opponent. Guard mobs and tameable animals are excluded from mob-assistance targets.

Ground guards patrol near the beacon when idle and do not naturally despawn just from distance. Skeletons use a short low-health recharge with an end-crystal-style link. Core chunk tickets keep the beacon running, but distant guard simulation still depends on loaded entity chunks.

## SuperWithers and riding

In Power mode, enable **Possess Withers**, then construct a Wither normally from soul sand/soul soil and three skulls within beacon coverage. Spawn eggs and arbitrary summoned Withers are not the conversion trigger.

Limits are two SuperWithers per beacon and eight globally. **At capacity, additional built Withers remain ordinary vanilla Withers** and retain their normal dangers.

SuperWithers have **300 usable health** and a working numerical health bar. Their internal maximum is doubled to retain the shielded appearance from full usable health. Their name colour follows the beacon's primary colour. Native shield-related projectile resistance still applies.

When injured and off cooldown, a SuperWither starts a crystal-linked recharge, healing up to 30 health over three seconds. The cooldown is 20 seconds from recharge start. It repeats eligible recharges until full, ending the beam early at full health.

They shoot Wither skulls, can pull hostile players with a tractor effect, and apply close-range damage. Safe skulls have no terrain explosion/fire; the destructive toggle enables explosion yield and ignition, subject to game rules and the plugin's protections.

Idle SuperWithers patrol low enough for mounting and approach friendly players only near the beacon's foundation zone. Right-click to mount, one rider at a time:

| Control | Action |
| --- | --- |
| W / S + look direction | Forward / backward; look up/down to climb/descend |
| A / D | Strafe |
| Space | Fire skulls on cooldown |
| F / swap-hands binding | Toggle a 10-second tractor aura; press again to cancel |
| Shift | Dismount |

The mounted tractor can pull eligible mobs and hostile players within 14 blocks and line of sight. Riders, friendly players, possessed guards, and armour stands are excluded; friendly online owners' pets are protected by the damage filter. The selected beacon radius now bounds movement instead of the earlier fixed 80-block tether. Dismounting does not grant fall protection.

SuperWithers evaporate when their beacon loses the required Power/possession state, and are removed on plugin shutdown. They are not persistent pets across a server restart.

## Husked Guards and captured inventory

Enable **Possess killed players** in Power mode. When an eligible possessed guard kills a player, the human still dies and respawns normally; a separate **zombie husk** inherits the carried inventory.

- It equips the player's held item, offhand, chest armour, leggings, and boots.
- Its helmet is a player head matching the victim. The original helmet remains in its stored inventory.
- Backpack items and additional server-provided inventory slots are preserved. Paper 26.3 exposes 43 slots; the implementation does not require exactly 41.
- It can draw replacement totems from its stored items into an empty offhand. Combat can consume or damage gear.
- It has 40 health, sunlight immunity, persistent inventory data, patrol/target logic, and no natural distance despawn.
- Killing it drops the remaining captured inventory once, excluding the decorative player head.

**For these opted-in guard deaths, capture overrides keepInventory and the retained-item list.** Inventory transfers to the husk instead of also being retained or dropped. Totems that successfully prevent the player's death still work normally; capture happens only after death. XP, Ender Chest contents, cursor items, and crafting-grid contents are not captured by the normal inventory transfer.

Turning possession OFF, deactivating the beacon, or leaving its required Power state **releases husks into ordinary zombie AI**, rather than deleting them. They keep stored gear and sunlight immunity; their later death still returns the remaining inventory. Released husks do not automatically rejoin when the beacon returns. Drowning conversion is suppressed to preserve their inventory ownership.

Persistent ground entities reconcile their home beacon when loaded. If its home chunk is unavailable, the custom controller pauses instead of force-loading the entire world. Inventory interactions with other death/keep-inventory plugins still need server-specific testing.

## Commands and permissions

**Every `/vwu` command, including help and recipes, requires both operator status and `vwu.admin`.** The permission defaults to OP. Granting the permission to a non-operator does not bypass the explicit OP check. Normal crafting and using custom items do not require command access.

| Command | Function |
| --- | --- |
| `/vwu help` | Command summary |
| `/vwu recipes` | Recipe layouts |
| `/vwu give <player> <item> [amount]` | Give an online player 1–64 custom items |
| `/vwu status` | Queued jobs, phase, processing time, MSPT, and progress |
| `/vwu cancel <job-id>` | Cancel remaining crater work; no terrain rollback |
| `/vwu reload` | Reload config; queued job dimensions remain unchanged |
| `/vwu weather stop [world]` | End nuclear winter in the selected/current world |
| `/vwu benchmark` | Geometry estimates without detonating anything |
| `/vwu cleanup` | In-game support cleanup scan within 120 blocks in loaded chunks |
| `/vwu beaconreset` | In-game OP recovery of a beacon within eight blocks |

Accepted item IDs: `nether_core`, `super_tnt`, `formidibomb`, `corrupted_star`, `command_beacon`.

Example: `/vwu give YourPlayerName command_beacon 1`.

## Configuration and saved data

The complete defaults are in [`src/main/resources/config.yml`](src/main/resources/config.yml). Paper copies this into the plugin data directory on first start. Current categories include:

| Section | Controls |
| --- | --- |
| `explosions.super-tnt` / `formidibomb` | Radius, depth, visual radius, fuse, base damage |
| `explosions.*` | Queue limits, block/time budgets, MSPT pause/resume, entity cap, drops, physics |
| `protection.*` | Spawn radius, disabled worlds, bedrock and protected materials |
| `fallout.*` | Enable flags, radiation, creep/mutation limits, glass conversion, winter |
| `visuals.*` | Explosion visuals, fallout duration, heat-wave reach/fire budget, flash |

Most beacon choices are saved **on the beacon's persistent data**, not in global YAML. There is no global config switch for every menu option.

| Saved location | Purpose |
| --- | --- |
| `jobs.yml` | Queued crater progress/checkpoints |
| `placed.yml` | Placed custom explosive tracking |
| `winter.yml` | Nuclear-winter duration and centres |
| `command-beacons.yml` | Active core anchors and weather-source index |
| Beacon block-entity data | Ownership, PIN hash/salt, lists, effects, radius, colours, toggles |
| Husk entity data | Home association, captured inventory, released status |

Back up the **world and plugin data together**. Temporary displays and SuperWithers are deliberately nonpersistent. Threat memories and access sessions are in memory. Abrupt crashes can lose changes made since the last checkpoint.

## Disabled Homeworld / Overclock feature

`CommandRifts.java` retains the experimental ceiling-height starfield and Citadel-style energy-spine implementation. `HOMEWORLD_AVAILABLE` in `CommandBeacons.java` is currently **false**.

The option is hidden, its click action is disabled, saved ON flags are ignored, and the experimental 350-block range is unavailable. Old tagged rift displays are cleaned up on startup/chunk loading. This README does not describe Overclock as an available gameplay feature.

## Implementation map

All Java classes are under `src/main/java/com/vanillaweapons/vwu/`.

| Class | Responsibility |
| --- | --- |
| `VanillaWeaponsPlugin` | Startup, scheduled tasks, managers, protection entry points |
| `CustomItems` | Persistent item identity, recipes, ingredient validation, lore migration |
| `WeaponListener` | Placed TNT tracking, priming, event interception |
| `ExplosionEngine` | Crater queue, radial excavation, vitrification, checkpoints, budgets |
| `BlastEffects` | Explosion spectacle, heat wave, radiation patches |
| `TerrainVeil` | Temporary debris/water display effects covering terrain updates |
| `FoliageCleanup` | Support checks for vegetation and snow |
| `FalloutManager` / `RadioactiveSnow` | Mutations, charged creepers, winter state, snow visuals/deposition |
| `CommandBeacons` | PIN/menu logic, effects, modes, weather, chunks, shared threats |
| `BeaconVisuals` | Core, rings, five beam clusters, aura-related visuals |
| `SuperWithers` | Controlled flight, skulls, riding, tractor, healing |
| `GroundGuards` | Persistent ground patrols, husk capture, inventory custody/release |
| `CommandRifts` | Parked Homeworld experiment |
| `VwuCommand` | OP command execution and completion |

Terrain and entity mutations run on the server thread. Chunk acquisition uses Paper's asynchronous API and hands state changes back to the main thread. The built-in Paper API is `provided`, so it is not bundled into the plugin JAR.

## Limitations and testing

- **Release candidate:** development has included scripted live-Paper checks, but not a comprehensive automated unit/integration suite in this repository. `mvn package` compiling successfully does not certify multiplayer gameplay, client visuals, restart recovery, or every inventory-plugin interaction.
- **No WorldGuard/GriefPrevention/claim integration.** Custom direct terrain/damage operations do not generally reproduce every third-party protection hook. Keep destructive items out of claim-dependent worlds until you have an appropriate integration.
- Large craters and many simultaneous display-heavy beacons can load both server and clients. Test the real player count, render setup, hardware, and other plugins.
- Physics suppression, chunk availability, fluid settling, and unusual terrain can produce artifacts. `/vwu cleanup` is a support cleanup tool, not a world rollback.
- Guards, destructive skulls, lightning, fire, possession, and keepInventory overrides are intentional gameplay changes when the relevant custom systems are used. This is not a promise of complete noninterference with every other plugin.
- New default config keys may need to be merged into an existing config manually. Keep an old config backup when updating.

Before a production update, test on a copied world: custom/ordinary recipes, redstone/fire/chain priming, both crater types, fallout and snow, non-OP command denial, PIN/pyramid protection, both beacon modes, range boundaries, guard affiliation, real player death capture, totems, released-husk loot, chunk unload/restart, and several simultaneous beacons.

## Project status and license

This repository publishes the current development source. No license has been selected yet. It is not an official Mojang, Microsoft, or PaperMC product.
