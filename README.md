# Divisions: Command and Control (`division_ai` v1.1.1c)

[![Install with TriOS](https://trilink.wispborne.com/badges/install-badge-forthebadge.svg)](https://trilink.wispborne.com/open.html?mod=%7B%22url%22%3A%22https%3A%2F%2Fraw.githubusercontent.com%2Fdefarius777%2FDivisions-Command-and-Control-Mod-for-Starsector%2Fmain%2Fmod%2Fdivision_ai.version%22%2C%22id%22%3A%22division_ai%22%7D&dep=%7B%22url%22%3A%22https%3A%2F%2Fraw.githubusercontent.com%2FLazyWizard%2Flazylib%2Fmaster%2Fmod%2Flazylib.version%22%2C%22id%22%3A%22lw_lazylib%22%7D)

A two-tier naval Command & Control (C2) combat overhaul, campaign Fleet Organization & Fleet Doctrine system, Tactical Map Warroom (`Tab`) visualizer, and dynamic battle music controller for **Starsector `0.98a-RC8`**.

---

## Table of Contents
1. [Part 1 — Human-Friendly Player Guide (Step-by-Step)](#part-1--human-friendly-player-guide-step-by-step)
   - [What Does This Mod Do in Plain English?](#what-does-this-mod-do-in-plain-english)
   - [Step 1: How to Install (or Update)](#step-1-how-to-install-or-update)
   - [Step 2: Organize Your Ships into Divisions (Campaign `F` Screen)](#step-2-organize-your-ships-into-divisions-campaign-f-screen)
   - [Step 3: Pick Your Fleet Doctrine (Optional)](#step-3-pick-your-fleet-doctrine-optional)
   - [Step 4: Deploy Your Divisions Before Battle](#step-4-deploy-your-divisions-before-battle)
   - [Step 5: Watch & Command in Battle (`Tab` Map)](#step-5-watch--command-in-battle-tab-map)
   - [Step 6: Mod Settings, Compatibility & Save Safety](#step-6-mod-settings-compatibility--save-safety)
2. [Part 2 — AI Agent & Developer Reference](#part-2--ai-agent--developer-reference)
   - [1. Build, Test & JFR Profiling Commands](#1-build-test--jfr-profiling-commands)
   - [2. Hard Engine & Bytecode Invariants (Must Never Break)](#2-hard-engine--bytecode-invariants-must-never-break)
   - [3. Complete 31-Class Architecture & Data File Map](#3-complete-31-class-architecture--data-file-map)
   - [4. Combat C2 Execution Lifecycle & Tick Cadences](#4-combat-c2-execution-lifecycle--tick-cadences)

---

# Part 1 — Human-Friendly Player Guide (Step-by-Step)

### What Does This Mod Do in Plain English?
In vanilla Starsector, every ship fights for itself inside a small `1500–2200` range bubble. Ships don't know what the rest of the fleet is doing, lines fall apart, frigates get baited off alone, and battles turn into scattered dogfights.

**Divisions: Command and Control** fixes that without replacing vanilla ship AI:
- You group your ships into **Divisions** (up to 10 ships per division — like a Cruiser Line, a Frigate Wolfpack, or a Carrier Group).
- An overall **Fleet Commander (OTC)** coordinates all your divisions as a single battle group, while each **Division Leader** keeps their division together in formation, rotates high-flux ships to the rear to vent, flanks around the outside of enemy lines, and coordinates missile/bomber strikes.
- **Enemy and Allied AI fleets** also automatically organize into lore-accurate divisions and doctrines based on their faction!
- **Note:** This mod **never** forces your own player ships into divisions behind your back. Any ship you leave in "Unassigned Ships" behaves 100% like vanilla Starsector.

---

### Step 1: How to Install (or Update)
- **One-Click Install via TriOS (1.6.0+):** Click the **Install with TriOS** badge above — it automatically installs or updates **Divisions: Command and Control** and its **LazyLib** dependency.
- **Manual Install:**
  1. Make sure you have **LazyLib** installed and enabled. (**LunaLib** is optional if you want an in-game settings menu).
  2. Download `DivisionAI.zip` from the [Latest Release](https://github.com/defarius777/Divisions-Command-and-Control-Mod-for-Starsector/releases/latest) page.
  3. Extract the `DivisionAI` folder into your `Starsector/mods/` directory so that `Starsector/mods/DivisionAI/mod_info.json` exists.
  4. Check the box next to **Divisions: Command and Control** in the Starsector launcher and launch the game.

---

### Step 2: Organize Your Ships into Divisions (Campaign `F` Screen)
Once you load a campaign save (or start a new game):

1. **Open your Fleet screen:** Press **`F`** in the campaign map.
2. **Look at the bottom-left sidebar:** You will see two new buttons right above your ship list:
   - **`Fleet Doctrine`** (gold star icon)
   - **`Fleet Organization`** (gold wing icon)
3. **Click `Fleet Organization`:** This opens the **Division Editor**.
4. **The Easiest Way (1-Click Auto-Assign):**
   - Click the **`Auto-Assign`** button in the top bar, then click **`Confirm`**.
   - The mod will automatically sort your combat ships into sensible divisions (Battleships, Cruisers, Destroyers, Frigates, Carriers, Phase ships) and pick the highest-ranking officer or largest ship in each division as its **Division Leader** (marked with a star badge).
5. **Or Build Divisions Manually (Drag / Click):**
   - Click **`+ New Division`** to create a blank division card.
   - Click any ship in the **Unassigned Ships** pool on the right, then click a Division card on the left to move that ship into the division (or click a ship inside a division to move it back to Unassigned).
   - Want to customize a single division's name, role, formation, or standoff distance? Click the **`Configure`** button on that division's card!
6. **Click `Close` (or press `Esc`):** Your divisions are saved permanently with your campaign save. When you hover over your fleet on the campaign map, your fleet tooltip will now neatly display your ships grouped by division!

---

### Step 3: Pick Your Fleet Doctrine (Optional)
If you want to change how your overall fleet fights together:

1. Press **`F`** in the campaign map and click **`Fleet Doctrine`** on the left sidebar (or click the **`Fleet Doctrine`** tab at the top of the Division Editor).
2. Here you can customize:
   - **Command Style (Left Naval Pennant):**
     - *Command by Direction (Default):* Balanced — your Fleet Commander coordinates the battle plan, but Division Leaders have freedom to react to local threats.
     - *Command by Plan:* Strict — divisions stick tightly to the Fleet Commander's grand formation and assigned targets.
     - *Command by Negation:* Loose — Division Leaders act independently unless the Fleet Commander steps in during an emergency.
   - **Preferred Tactics (Right Naval Pennant):**
     - Choose which multi-division plays your Fleet Commander is allowed to call (e.g., *Line & Fire Distribution*, *Doubling / Flanking*, *Time-on-Target Strikes*, *Reserve & General Chase*).
   - **Fleet-Wide Formation & Setpiece:**
     - Pick how all your divisions line up relative to each other when advancing into battle (*Auto*, *Single Line Abreast*, *Double Line*, *Center & Wings*, *Refused Flank*, etc.).
   - **Auto-Assigner & Naming Presets:**
     - Change how `Auto-Assign` groups your fleet (e.g., *Homogeneous* same-class divisions vs. *Mixed Escort* divisions) and pick cultural naming themes (*Standard Naval*, *Hegemony*, *Tri-Tachyon*, *Persean League*, etc.).

---

### Step 4: Deploy Your Divisions Before Battle
When a battle starts and the **Deployment Screen** opens:

1. **Organized by Division Rows:** Your ships are no longer jumbled together — each of your Permanent Divisions has its own labeled row, showing its Division Leader (`*LDR`) and formation icon.
2. **1-Click Whole-Division Selection:** Click the **`Select (X DP)`** button on the left header of any division row to select or deselect that entire division at once!
3. **Pre-Battle Fleet Command Panel (Top Area):**
   - **Left (`OTC` Box):** Shows who is currently commanding your battle group (*Auto* picks your flagship or highest-ranking officer, or click it to assign a specific officer or Alpha Core ship).
   - **Center (`Command Style` Pennant):** Click the pennant to cycle your Command Style right before battle.
   - **Right (`Fleet Formation` Wheel):** Click to choose the grand formation your divisions will form up into as soon as they enter the map.
4. **Click `Deploy`:** If *Deploy in Formation* is enabled in your doctrine, your divisions will immediately spawn formed up in their doctrinal formation and advance together!

---

### Step 5: Watch & Command in Battle (`Tab` Map)
During combat, press **`Tab`** to open the Command Window (Tactical Map):

1. **Fluid Water-Droplet Division Blobs:**
   - Each division is enclosed in a smooth, translucent tactical boundary (green for player, gold for allies, red for enemies) with its callsign and current order (`FORM & FIX`, `ENGAGE`, `FLANK`, `VENTING`, `FLEET CAP`, etc.).
   - If a ship gets separated from its division, the blob stretches like liquid and splits into a sub-droplet until the straggler routes safely around the enemy line and rejoins!
2. **How to Give Manual Orders Without Fighting the Mod:**
   - **You are always the boss.** If you give a ship a manual order in the `Tab` map (like *Eliminate*, *Escort*, *Defend*, *Capture*, or *Retreat*), the mod **automatically yields control** of that ship so it obeys your exact order.
   - Only general *Engage* / *Search and Destroy* orders (or ships with no manual order) remain under Division AI orchestration.
3. **Interactive Right-Side Warroom Map Buttons:**
   - On the right edge of the `Tab` screen (above *Full Assault* / *Full Retreat*), you'll see square Warroom icon buttons that toggle tactical intelligence layers:
     - **Time Overlay (Clock Icon):** Shows how far each division can maneuver in the next 12 seconds (pinched inward in directions where enemy contact or the map edge blocks movement).
     - **Space Overlay (Hex/Zone Icon):** Shows 3-tier Zones of Control around each division, highlighting **Strongpoints** and **Weak Points / Seams** in the battle line.
     - **Firepower Overlay (Crosshair Icon):** Shows 24-sector directional weapon range and damage roses around ships, plus **L-Cordon / U-Cordon Crossfire** warnings when a division is caught in a multi-angle crossfire.
     - **Convergence Overlay (Triangle-in-Square Icon — Appears when an Alpha Core is present):** Shows Alpha Core phase-locked multi-division focal points.

---

### Step 6: Mod Settings, Compatibility & Save Safety
- **Tweaking On-Screen Combat Chatter (`LunaLib`):**
  - If you have **LunaLib** installed, open **Mod Settings -> Divisions: Command and Control** to adjust combat chatter verbosity (`Minimal` by default, or `Standard`, `Verbose`, `OTC Only`, `Off`), toggle overlays, or adjust division polygon opacity.
- **Compatibility Notes:**
  - **All Faction Mods, Ship Packs, Weapon Packs & Nexerelin:** 100% compatible (139 vanilla and modded factions have built-in cultural naming & fleet doctrines; unknown factions are dynamically profiled).
  - **Ship AI Mods (e.g., *AI Tweaks*):** Compatible! This mod steers divisions using vanilla `ShipwideAIFlags` and never overwrites custom `ShipAIPlugin` implementations.
  - **Arma Armatura (*WingCom*):** Compatible! `VoidstrikeCoordinator` automatically skips player-piloted strikecraft and officer-assigned WingCom wings so Stage 1 Voidstrike marshalling never tethers them.
  - **RTS Assist:** Leave ships you wish to manually micro with RTS Assist in your **Unassigned Ships** pool (or assign them an explicit vanilla task-manager order) so Division AI yields control.
  - **Other Deployment Screen Overhauls (*Pre-Battle Deployment Orders*, *CustomDeployment*):** Do not enable alongside this mod's deployment organizer, as both modify the pre-battle deployment screen.
- **Changelog:** See [CHANGELOG.md](CHANGELOG.md) for full release history.
- **Can I Add or Remove This Mod Mid-Save?**
  - **Yes.** You can add it to an existing campaign at any time. Campaign division data is stored in persistent sector data using plain Java lists and maps (`ArrayList`, `HashMap`, `String`) with **zero custom class serialization** in `campaign.xml`, so removing the mod will not brick your save.

---
---

# Part 2 — AI Agent & Developer Reference

This section is structured specifically for **AI coding agents** (Hermes, Claude Code, Codex, OpenCode, Cursor) and human contributors modifying, profiling, or extending the codebase.

### 1. Build, Test & JFR Profiling Commands

```bash
# 1. Compile src/divisionai/*.java and package mod/jars/DivisionAI.jar
bash build.sh

# 2. Run the full 23-test headless C2 simulation + 41-check UI/UX & geometry suite + bytecode verifier (~2.3s)
bash test.sh
```

- **JDK Requirement:** Java 17 (`toolchain/jdk17/bin` or Starsector's bundled `jre/bin`).
- **Artifacts Generated by `bash test.sh`:**
  - `build/sim_replay.html` — Interactive HTML5 Canvas tactical replay viewer across all simulated combat scenarios.
  - `build/ui_preview.html` — Layout, button-width, and copy succinctness audit report across all campaign/deployment UI screens.
  - `build/droplet_qa_sheet.png` — Visual QA sheet verifying zero self-intersections, zero uncontained ships, and zero inward gashes across 173 water-droplet polygons.
- **Profiling with Java Flight Recorder (`jfr`):**
  - Starsector's bundled JDK 17 includes `jfr.exe`. Run any simulation or live game with:
    `-XX:StartFlightRecording=filename=profile.jfr,settings=profile`
    and inspect hot methods and allocation sites via:
    `jfr print --events jdk.ExecutionSample,jdk.ObjectAllocationSample profile.jfr`

---

### 2. Hard Engine & Bytecode Invariants (Must Never Break)

1. **ZERO `java.lang.reflect` Imports or Constant-Pool Entries in `src/divisionai/`:**
   - Starsector's script classloader blocks `java.lang.reflect.*`.
   - **Rule:** Every reflective call into obfuscated `starfarer_obf.jar` UI/engine classes **must** use `java.lang.invoke.MethodHandles.lookup()` (`findVirtual`, `findStatic`, `findGetter`, `findSetter`, `findConstructor`).
   - **Enforcement:** Scenario 8 of `DivisionAITestSuite` scans the bytecode constant pool of every `.class` in `DivisionAI.jar` and fails the build if any `java/lang/reflect` UTF-8 entry exists.
2. **ZERO Compile-Time `org.json.*` Parameter or Return Types in Combat-Loaded Classes:**
   - Starsector's `CombatEngine.init` runs a security scanner (`com.fs.starfarer.loading.M.o00000`) that calls `Class.getDeclaredMethods()` on combat classes under a restricted classloader where `org.json.JSONObject` / `JSONArray` are not visible, throwing `NoClassDefFoundError: org/json/JSONObject`.
   - **Rule:** Never import `org.json.*` in `src/divisionai/`. Route all JSON access through `JsonReflectBridge.java`, which types all parameters and returns as `java.lang.Object` and invokes `org.json` methods via cached `MethodHandles`.
3. **ZERO Wildcard Static Imports (`import static ...*;`):**
   - Keep all imports explicit across `src/divisionai/*.java`.
4. **ZERO `%` Characters in `mod/data/config/LunaSettings.csv`:**
   - LunaLib passes `fieldDescription` into `TooltipMakerAPI.addPara`, which invokes `String.format()`. Any raw `%` throws `MissingFormatArgumentException` when opening Mod Settings. Spell out `"percent"`.
5. **Respect `UITestHarness.java` Source-Level Checks:**
   - `test/divisionai/sim/UITestHarness.java` performs `Files.readAllBytes` source-string audits on:
     - `FleetDoctrineDialog.java`
     - `DivisionEditorDialog.java` + `DivisionConfigureSubscreen.java`
     - `DivisionDeploymentDialogOrganizer.java` + `FleetCommandPanel.java`
     - `BattlefieldFirepowerEvaluator.java`
     - `DivisionTacticalMapOverlay.java`
     - `DivisionOtcCommandManager.java`
   - Always run `bash test.sh` after refactoring any UI or Warroom class to ensure all `23/23` tests and `41/41` UI checks pass.
6. **Never Commit Local Paths or `.lnk` / `.rar` Files:**
   - Never include local machine paths, drive letters, or local shortcut/archive files in tracked source or documentation files.

---

### 3. Complete 31-Class Architecture & Data File Map

#### Layer A — Core Model, Capability Profiling, Bridges & Data-Driven Configs
| File | Responsibility |
| :--- | :--- |
| `src/divisionai/DivisionModel.java` | Core enums (`DivisionArchetype` x16, `MissionContextType`, `BattlePhase`, `DivisionIntentType`, `OtcOrderType`) and runtime state (`CommanderProfile`, `DivisionState`, `BattleGroupState`). |
| `src/divisionai/PermanentDivisionManager.java` | Campaign persistence (`divisionai_permanent_divisions_v2`, `divisionai_fleet_doctrine_v1`), 16 doctrine enums (`OtcAuthority`, `FleetWideFormation`, `DivisionFormation`, etc.), ship archetype classifier, and campaign-to-combat bridge. |
| `src/divisionai/ShipCapabilityProfile.java` | Inspects `ShipAPI` / `FleetMemberAPI` hull class, DP cost (`resolveShipFpStrength`), weapon arcs/ranges, `AIHints.STRIKE` missiles, PD weight, and fighter wings. |
| `src/divisionai/JsonReflectBridge.java` | `MethodHandles`-based bridge for `Global.getSettings().loadJSON` / `getMergedJSONForMod` and `org.json.JSONObject` / `JSONArray` using `Object`-only signatures. |
| `src/divisionai/FactionLibrary.java` | Loads `mod/data/config/division_faction_library.json` (139 vanilla + modded faction IDs, aliases, and cultural nomenclature mappings). |
| `src/divisionai/FactionFleetDoctrines.java` | Loads `mod/data/config/division_faction_doctrines.json` (lore-accurate faction `FleetDoctrineConfig` presets) and manages persistent NPC faction division tables (`$divisionai_factionDivisionTables_v1`). |
| `src/divisionai/DivisionNomenclature.java` | Cultural division & OTC callsign generator across 8 player naming presets + NPC Remnant/Threat/Abyssal presets, shared prefix-bucket numbering, and SplitMix64 variation. |
| `src/divisionai/DivisionAISettings.java` | Reads `mod/data/config/LunaSettings.csv` and fallback `settings.json` flags. |

#### Layer B — Combat C2 Engine, TSFC Evaluator & Battle Music
| File | Responsibility |
| :--- | :--- |
| `src/divisionai/DivisionAICombatPlugin.java` | `BaseEveryFrameCombatPlugin` entry point; runs the `0.20s` division/telemetry tick, `1.20s` OTC strategy tick, wave-spawn formation interception, and live battle-screen TSFC overlay. |
| `src/divisionai/OtcAI.java` | Officer in Tactical Command (OTC): updates division/core-cluster telemetry (`updateSingleDivisionTelemetry`, `computeCoreClusterAndStragglers`), fleet-wide formation geometry (`updateFleetWideFormationGeometry`), wave spawn placement (`hijackNewlyDeployedWaveIntoFormation`), and Fog-of-War visibility queries. |
| `src/divisionai/OtcBattlePlanner.java` | Translates OTC `BattlePhase`, `FleetWideFormation`, `MultiDivisionSetpiece`, and enabled `OtcPlay`s into per-division `OtcOrder`s gated by `OtcAuthority`. |
| `src/divisionai/DivisionCommanderAI.java` | Division Commander tactical AI: 13-step `selectDivisionIntent` priority ladder, formation slot geometry (`computeShipFormationSlot`), out-of-contact wide-arc routing (`computeOutOfContactRoutePoint`), and modular per-intent `ShipwideAIFlags` execution (`applyIntentFlagsToShip` + 10 intent helpers). |
| `src/divisionai/BattlefieldFirepowerEvaluator.java` | Evaluates 24-sector Polar Firepower Roses (`updateSingleShipRose`), L/U-cordon crossfire indices, Directional Time Envelopes (`computeDirectionalTimeEnvelope`), 3-tier Directional Space Zones of Control (`computeDirectionalSpaceEnvelope`), and Alpha Core Convergence scores. |
| `src/divisionai/VoidstrikeCoordinator.java` | 3-stage carrier Voidstrike staging (Orbit Carrier -> Marshal Point -> Attack Run), squadron formations, multi-division Voidstrike clustering, and live `"Voidstrike #"` tracking. |
| `src/divisionai/ShipSystemCoordinator.java` | Generic 7-archetype `ShipSystemSpecAPI` classifier (`LINEAR_RAM_DRIVE`, `LINEAR_THRUST_DRIVE`, `OMNI_AGILITY_JETS`, `BLINK_DISPLACER`, `OFFENSIVE_AMP`, `DEFENSIVE_BULWARK`, `TACTICAL_DEPLOYABLE`), opening scouting linear-burst discipline, Stage 1 (free vs. cautious) marshaling, Stage 2 synchronized division/OTC ignition, and standoff/post-impact toggle cutoffs. |
| `src/divisionai/DivisionOtcCommandManager.java` | OTC commander/flagship resolution, flagship-loss command disruption (`120s` OTC / `30s` subordinate blackout), Alpha Core assumption-of-command comms modal, and mid-battle OTC reassignment. |
| `src/divisionai/DivisionAutoFormer.java` | Auto-forms NPC/Allied combat divisions at battle start, syncs Player Permanent Divisions (`syncPlayerPermanentDivisionsOnly`), and absorbs reinforcement waves. |
| `src/divisionai/DivisionCombatLog.java` | Logs C2 events to `starsector.log` and filters HUD chatter by `ChatterVerbosity`. |
| `src/divisionai/DynamicBattleMusicController.java` | State-machine battle music controller (`CALM`, `ACTIVE`, `INTENSE`, `VICTORY`, `DEFEAT`) with custom OGG streaming (`OggPcmLoader.java`, `StreamingOggPlayer.java`, `NativeOpenALBridge.java`). |

#### Layer C — Campaign Dialogs, Pre-Battle Deployment UI & Warroom (`Tab`) Overlay
| File | Responsibility |
| :--- | :--- |
| `src/divisionai/DivisionTacticalMapOverlay.java` | Attaches to the `Tab` Command Window warroom (`OoOO`); computes sticky water-droplet sub-clusters, precomputed-primitive Marching Squares SDF contours (`PrecomputedSdfPrimitives` + flat-array edge graph), ear-clipping triangulation, overlap striping, and 60+ FPS live ship-motion vertex translation. |
| `src/divisionai/TsfcWarroomRenderer.java` | Renders the screen-space **Time**, **Space**, **Firepower**, and **Convergence** overlays, right-side square Warroom toggle buttons, and Urgent Alpha Core Comms modal. |
| `src/divisionai/DivisionDeploymentDialogOrganizer.java` | Enlarges the pre-battle Deployment Dialog (`14` cols x `7` rows), organizes reserve ships into Permanent Division rows with `Select (X DP)` buttons, and coordinates pre-battle/mid-battle OTC selection. |
| `src/divisionai/FleetCommandPanel.java` | Renders and handles the `940x204` **Fleet Command** top panel (`createFleetCommandPanel`) and status meter bar inside the Deployment Dialog. |
| `src/divisionai/OtcSelectionModalDialog.java` | Shared modal window for selecting the Fleet Commander (OTC) pre-battle or during flagship-loss emergencies. |
| `src/divisionai/DivisionEditorDialog.java` | Campaign **Fleet Organization** (`1320x800`) Screen 1: division cards, `ShipIconTile` drag/click assignment, Unassigned Ships pool, and Auto-Assign confirmation. |
| `src/divisionai/DivisionConfigureSubscreen.java` | Campaign **Configure Division** (`1320x800`) Screen 2: 2x8 archetype selector, 10 formation emblems, and per-division spacing/standoff/mission overrides. |
| `src/divisionai/FleetDoctrineDialog.java` | Campaign **Fleet Doctrine** (`1320x800`) dialog: Command Style & Preferred Tactics pennants, Fleet-Wide Formation & Setpiece selectors, Division Modifiers, and Auto-Assigner rules. |
| `src/divisionai/DivisionUiWidgets.java` | Shared UI widget builders (`OptionButtonTag`, `SectionHeaderBar`, `FramedSectionBox`, `SolidBgPanel`, `FormationWheelWidget`) and common math helpers (`clamp`, `distance`). |
| `src/divisionai/DivisionFleetTabInjector.java` | Injects the `Fleet Doctrine` and `Fleet Organization` buttons into the campaign Fleet tab (`CoreUITabId.FLEET`). |
| `src/divisionai/CampaignFleetTooltipOrganizer.java` | Organizes the campaign fleet hover tooltip (`StandardTooltipV2`) into division rows. |
| `src/divisionai/DivisionAIModPlugin.java` | `BaseModPlugin` campaign lifecycle entry point. |

---

### 4. Combat C2 Execution Lifecycle & Tick Cadences

1. **Wave Spawn Interception (`DivisionAICombatPlugin.interceptNewlyDeployedWaves`):**
   - Runs via a `BELOW_PLANETS` layered rendering hook before the first frame a newly deployed wave is drawn, placing ships directly into their Fleet-Wide and Intra-Division formation slots under travel drive (`OtcAI.hijackNewlyDeployedWaveIntoFormation`).
2. **Division & Telemetry Tick (`DIVISION_EVAL_INTERVAL = 0.20s`):**
   - Partitions eligible combat hulls into `playerGroup`, `alliedAiGroup`, and `enemyGroup`.
   - Syncs player permanent divisions (`syncPlayerPermanentDivisionsOnly`) and NPC auto-divisions (`syncDivisionsForSide`).
   - Updates division centroids, core clusters vs. cut-off stragglers, firepower roses (`0.50s` internal cadence), and executes `DivisionCommanderAI.evaluateAndExecuteDivisions` + `VoidstrikeCoordinator.updateVoidstrikes`.
3. **OTC Strategy Tick (`OTC_EVAL_INTERVAL = 1.20s`):**
   - Updates cross-group Time/Space/Convergence envelopes (`BattlefieldFirepowerEvaluator.updateCrossGroupTimeSpaceConvergence`), evaluates Fog-of-War force ratios, transitions `BattlePhase`, and issues `OtcOrder`s via `OtcBattlePlanner`.
4. **Warroom (`Tab`) Render Loop (`DivisionTacticalMapOverlay.render`):**
   - Rebuilds Marching Squares water-droplet division polygons at `0.15s` intervals while unpaused (zero rebuilds while paused) and translates cached polygon vertices in-place by live ship centroid motion (`translateCachedPolygonsByLiveShipMotion`) and camera pan/zoom (`transformCachedGeometryInPlace`) on every intermediate 60+ FPS frame.

---

## License

Released under the [MIT License](LICENSE).
