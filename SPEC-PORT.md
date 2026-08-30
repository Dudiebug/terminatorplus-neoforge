# TerminatorPlus NeoForge 1.21.1 Port — Executable Spec

Source: `eebc3b7480e4f30c9a16dd65401c3f6f0a05e9b9` from `Dudiebug/terminatorplus`.
Target: Java 21, Minecraft 1.21.1, NeoForge 21.1.249.
Spec approval: not obtained (autonomous implementation run).

## Setup authorized by the implementation request

- Preserve the source history and EPL-2.0 notices in a new public repository.
- Replace Paperweight/Bukkit with NeoForge ModDevGradle.
- Add only NeoForge's runtime/build dependency and SnakeYAML for one-time
  Paper YAML import; Gson/Netty/Minecraft libraries remain platform-provided.
- Keep the API and plugin modules; publish API separately and embed it in the
  distributable mod.

## Acceptance scenarios

1. `./gradlew build` succeeds with Java 21 and produces a NeoForge mod jar plus
   a compile-time API jar.
2. The mod loads on a dedicated 1.21.1 NeoForge server with no client mod and
   no custom registry entries or required custom payloads.
3. `/bot create`, `/bot multi`, `/bot remove`, `/bot reset`, `/bot count`,
   `/bot info`, `/bot inventory`, `/bot loadout`, `/bot preset`, `/bot armor`,
   `/bot settings`, `/bot move`, `/bot debug`, and all aliases preserve the
   Paper command tree, suggestions, output, and permission behavior.
4. `/ai` preserves random spawn, movement spawn, reinforcement training,
   brain status/load/save/reset, evaluate, inspect, and stop behavior.
5. Environment commands preserve material inspection, solid-block and custom
   mob lists, and mob-list mode behavior; 26.2 `IRON_CHAIN` maps to 1.21.1
   `CHAIN` and `COPPER_CHAIN` is ignored.
6. A created bot is a packet-visible `ServerPlayer` with configurable skin,
   player-list membership, equipment, health, damage, death, respawn, target,
   and removal behavior; vanilla clients render it correctly.
7. Combat and movement preserve melee, shield, mace, trident/spear, projectile,
   wind-charge, crystal, anchor, cobweb, consumable, elytra, and movement V2/
   legacy-agent paths under 1.21.1 mechanics.
8. Inventory GUI, management GUI, loadouts, presets, armor tiers, cooldowns,
   and direct NMS inventory writes preserve their Paper behavior.
9. NeoForge block, bucket, interaction, damage, death, join, quit, inventory,
   and chat events can cancel or observe bot actions; mutations stay on the
   server thread and never bypass protection hooks.
10. The five public bot events expose native NeoForge payloads and retain their
    cancellation semantics.
11. On first launch, a Paper `plugins/TerminatorPlus` (or staged import folder)
    imports config, presets, compatible brain JSON, and reports; existing
    native data is never overwritten and a backup is created.
12. Reload, shutdown, reset, removal, and death leave no bots, scheduled tasks,
    GUI listeners, temporary files, or partially written persistence artifacts.

## Negative constraints

- Do not modify BuddyBot or the Paper source repository.
- Do not require a client-side NeoForge installation.
- Do not retain Bukkit types in the runtime/API contract.
- Do not silently substitute a different item for the trident-based spear
  loadout or claim tick-perfect parity across Minecraft versions.
- Do not weaken existing behavior tests to make the port compile.

## Verification map

- Unit tests: command grammar, native type adapters, material mapping, combat
  timing, movement-only authority, inventory/loadouts, scheduler, serialization,
  brain persistence, evaluation exports, and importer idempotence.
- GameTests: bot lifecycle, fake connection/render packets, damage/death/
  respawn, inventory menus, actions/events, targeting, and delayed tasks.
- Runtime: dedicated server startup, vanilla-client smoke test, command matrix,
  and representative duel scenarios.
- Gauntlet: full test suite, compile/static checks, changed-line coverage where
  available, manual mutation of importer/scheduler guards, dependency/license
  audit, and repeat test execution for stability.
