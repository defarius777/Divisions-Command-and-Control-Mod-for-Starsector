# Changelog — Divisions: Command and Control (`division_ai`)

All notable changes to **Divisions: Command and Control** are documented in this file.

---

## [v1.0.3] — 2026-11-03

### Fixed
- **Critical Player Targeting (`R` Key) & Manual Helm Hijack Fix:**
  - Fixed a bug where assigning the player's own piloted flagship to a Division caused `DivisionCommanderAI` to overwrite the player's `R`-key target lock (`ship.setShipTarget`) every `0.20s` during manual flight.
  - **Root cause:** Vanilla Starsector's `CombatEngine.isUIAutopilotOn()` returns `!combatUI.isAutopilotOn()` (returning `true` when Autopilot is OFF and `false` when Autopilot is ON). Relying on `!engine.isUIAutopilotOn()` caused `DivisionCommanderAI.executeDivisionIntent` to treat manually piloted player flagships as if Autopilot were enabled.
  - **Fix:** Added `OtcAI.isPlayerManuallyPiloting(engine, ship)` which checks `ship == engine.getPlayerShip() && ship.getShipAI() == null` (matching vanilla `CombatEngine`'s internal manual-control check), and guarded `DivisionCommanderAI.directShipAndFightersOntoTarget` so `ship.setShipTarget(...)` is **never** called on `engine.getPlayerShip()` (even when Autopilot is enabled, preserving manual `R`-key target selection for player-fired missile racks and HUD target inspection).
- **Arma Armatura (`WingCom`) & Player-Piloted Strikecraft Compatibility:**
  - Updated `VoidstrikeCoordinator.dressShipStrikeCraftInFormation` to automatically skip player-piloted strikecraft (`fighter == engine.getPlayerShip()` or `fighter.getShipAI() == null`) and officer-commanded WingCom strikecraft (`fighter.getCaptain() != null && !fighter.getCaptain().isDefault()`), preventing Stage 1 Voidstrike marshalling from tethering WingCom wings.

---

## [v1.0.2] — 2026-11-03

### Performance (Java Flight Recorder Profiling)
- **Command Window (`Tab` Overview) Water-Droplet Polygon Pipeline (2.5x faster avg / 7.7x lower peak spikes):**
  - Precomputed intra-droplet hourglass bridge parameters, meniscus cones, and connected 3-ship triangles with padded AABB bounds (`PrecomputedSdfPrimitives`) + per-row active primitive filtering in `DivisionTacticalMapOverlay`.
  - Replaced boxed `HashMap<Long, ...>` / `HashSet<Long>` edge graphs in `traceMetaballContoursMarchingSquares` with flat primitive arrays.
  - Added 60+ FPS live ship-centroid polygon vertex translation (`translateCachedPolygonsByLiveShipMotion`) across a `0.15s` unpaused Marching Squares rebuild cadence (`~0.38 ms/frame` amortized at 60 FPS).
  - Added AABB fast-reject and ping-pong list reuse to `clipConvexPolygon`, and halved per-frame `mapGetIconHandle` calls in `renderShipSpritesOnTopLayer`.
- **TSFC (`Time`, `Space`, `Firepower`, `Convergence`) Evaluator (8.5x–9.4x faster):**
  - Hoisted sector-invariant Fog-of-War visibility, visible centroid, ship FP strength, friendly peer, and enemy fighter wing lookups out of the 24-sector inner loops in `computeDirectionalTimeEnvelope` and `computeDirectionalSpaceEnvelope`.
  - Made `DivisionState.isCombatEffective()` and `isAbleCombatEffective()` allocation-free and hoisted `engine.getFogOfWar` out of ship loops in `OtcAI`.

### Fixed
- **Mid-Save Mod Removal Compatibility (`CannotResolveClassException` Fix):**
  - `FactionFleetDoctrines` now stores cached NPC fleet division lists (`$divisionai_npc_divisions_v1`) in `MemoryAPI` as plain JDK `ArrayList<HashMap<String, Object>>` maps instead of `PermanentDivisionSpec` instances.
  - `DivisionNomenclature` now stores `divisionai_player_naming_v1` and `divisionai_player_faction_naming_v1` in `SectorAPI.getPersistentData()` as plain `LinkedHashMap<String, Object>` maps instead of `PlayerNamingConfig` instances.
  - Added XStream `readResolve()` migration hooks on `PermanentDivisionSpec` and `PlayerNamingConfig` plus `beforeGameSave()` / `onGameLoad()` sanitizers so older saves (`v0.6.x`–`v1.0.1`) automatically convert legacy serialized instances into plain JDK maps on load.

### Documentation
- Rewrote `README.md` with a **Part 1 — Human-Friendly Player Guide (Step-by-Step)** and a **Part 2 — AI Agent & Developer Reference**.

---

## [v1.0.1] — 2026-11-03

### Architecture & Refactoring
- Extracted `JsonReflectBridge.java`, `DivisionUiWidgets.java`, `OtcSelectionModalDialog.java`, `FleetCommandPanel.java`, and `DivisionConfigureSubscreen.java`.
- Externalized faction attributes (`mod/data/config/division_faction_library.json`) and faction fleet doctrines (`mod/data/config/division_faction_doctrines.json`).
- Decomposed monolithic methods in `DivisionCommanderAI.java`, `BattlefieldFirepowerEvaluator.java`, and `OtcAI.java`.

---

## [v1.0.0] — 2026-11-02

### Added
- Initial `v1.0.0` release of **Divisions: Command and Control**:
  - Two-tier Combat C2 AI (`OtcAI`, `OtcBattlePlanner`, `DivisionCommanderAI`, `VoidstrikeCoordinator`).
  - Campaign **Fleet Organization** (`DivisionEditorDialog`, `DivisionConfigureSubscreen`) & **Fleet Doctrine** (`FleetDoctrineDialog`).
  - Pre-Battle **Deployment Screen** division rows & **Fleet Command Panel** (`DivisionDeploymentDialogOrganizer`).
  - Tactical Map (`Tab`) **Warroom** fluid water-droplet division boundaries & **Time / Space / Firepower / Convergence** overlays (`DivisionTacticalMapOverlay`, `TsfcWarroomRenderer`, `BattlefieldFirepowerEvaluator`).
  - Dynamic 5-state Battle Music Controller (`DynamicBattleMusicController`).
