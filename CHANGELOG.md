# Changelog — Divisions: Command and Control (`division_ai`)

All notable changes to **Divisions: Command and Control** are documented in this file.

---

## [v1.1.1d] — 2026-11-10

### Fixed
- **Pursuit Battle (`FleetGoal.ESCAPE`) Deployment Un-Interleaving & Zone-Aware Marshaling:**
  - Fixed a bug in pursuit battles where vanilla Starsector's `DeploymentSpec` hull-size sorting (`Cruisers -> Destroyers -> Frigates`) interleaved mixed divisions across one another around the Center spawn point.
  - `OtcAI.hijackNewlyDeployedWaveIntoFormation` now always runs `marshalPursuitWaveByZones` on pursuit waves (`isPursuitBattle`), un-interleaving divisions within each zone (`CENTER`, `LEFT`, `RIGHT`) into contiguous side-by-side division formations anchored at vanilla's exact pre-advanced pursuit spawn coordinates (`y ≈ -6268.2` for `CENTER`, `x ≈ ∓7270` for flanks) while preserving travel-drive velocity and heading.
- **Campaign Pursuit Mission Classification & Northbound Interception C2:**
  - Fixed `DivisionAutoFormer.detectMissionContext` checking `ctx.fightToTheLast` before `FleetGoal.ESCAPE`, which misclassified campaign pursuit battles (`BattleCreationPluginImpl` sets `ctx.setFightToTheLast(true)`) as `FIGHT_TO_THE_LAST` instead of `INTERCEPTION` / `REARGUARD_ACTION`.
  - Suppressed rear escape objective claiming (`y = -6500`) and `DivisionMissionType.SCOUTING` in pursuit battles so pursuing divisions stay in `BattlePhase.APPROACH` advancing North (`+Y`) in their lanes when fleeing enemies are in Fog of War.
- **Deployment Window `[L]` / `[C]` / `[R]` Pursuit Row Toggle Buttons (Windows / macOS / Linux):**
  - Updated `ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor` to prefer `ACC_PUBLIC` methods so `iconSetDeployStateHandle` always binds the public `setDeployState(int)` method across Windows, macOS, and Linux.
  - Fixed `DivisionDeploymentDialogOrganizer.canMemberDeployLeftRight` (matching vanilla `oO0O`: only frigates can deploy on flanks unless `ForceConcentration` / `can_deploy_left_right` is active) and `setEntryDeployState` (`preDeselectState = canMemberDeployLeftRight ? 3 : 1`) so deselecting ships never increments non-frigates to state `4` (`"DEPLOY RIGHT"` stuck on icon).

---

## [v1.0.4b] — 2026-11-04

### Fixed
- **Stuck Division Circles at Fleet Instantiation (`Grand Wedge`) When Piloting Personally (`Tab` Warroom Map):**
  - Fixed a bug where deploying while personally captaining a ship caused the Warroom (`Tab`) division circles to remain frozen at their initial `t = 0.0s` Grand Wedge instantiation locations for the rest of the battle.
  - **Root cause:** At `t = 0.0s` during initial deployment, `DivisionTacticalMapOverlay.render` cached `cachedRenderedPolys` while `paused = true` (`cachedPolyPaused = true`, `cachedPolyNow = 0.0f`). When piloting a ship in the cockpit, `!ui.isShowingCommandUI()` is `true` while combat time `now` advances, and pressing `Tab` to open the Warroom pauses the combat engine (`paused = true`). Because `reuseGeometry = sameContext && (paused || Math.abs(now - cachedPolyNow) < 0.15f)` short-circuited on `paused == true` without checking `Math.abs(now - cachedPolyNow)` or ship position changes, the `t = 0.0s` cached geometry was reused whenever the Warroom was opened.
  - **Fix:** `reuseGeometry` now requires `Math.abs(now - cachedPolyNow) < 1e-4f` when `paused` is `true` (and `< 0.35f` when unpaused), verifies a live `computeBattleGroupsGeometrySignature` hash (including quantized ship world positions while paused), and invalidates the geometry cache whenever the Warroom is closed or a new wave is formed/repositioned.

### Performance
- **Warroom (`Tab`) & Deployment Screen Lag Elimination:**
  - Stopped rebuilding `buildOverlappingPolygonStripesCache` (O(N²) Sutherland-Hodgman clipping + diagonal slab slicing) on every intermediate 60 FPS frame during unpaused Warroom live translation; overlap stripes are now built on geometry refreshes and transformed in-place during pan/zoom.
  - Cached `PermanentDivisionManager.getFleetDoctrine()`, `PermanentDivisionManager.ensureInitializedForPlayerFleet()`, and `DivisionNomenclature.getPlayerNamingConfig()` by persistent-map reference and fleet signature so Warroom and Deployment UI frames no longer re-deserialize maps, re-scan all fleet weapon slots to sort divisions, and re-serialize back to `PersistentData` on every call.
  - Removed per-frame `hasAnyUninitializedDivisionTimeSpace` calls in `TsfcWarroomRenderer.renderTimeSpaceAndConvergenceScreenSpace` when `staleTime` is `false`.
  - Gated `DivisionAICombatPlugin.advance` convergence command state sync by ship count outside the `0.50s` C2 tick so it no longer clones reserve lists (`getReservesCopy()`) every paused frame when the OTC ship is in reserves, and eliminated duplicate `getDeployedCopyDFM()` list clones in `flushPendingEngineAdds`.
  - Moved `enlargeDeploymentDialogIfNeeded` and `hideVanillaDeployedSectionLabels` after the member signature check in `DivisionDeploymentDialogOrganizer.advance`, and cached layout keys in `FleetCommandPanel` so font-width binary searches (`wrapOtcTextIntoTwoLines` / `fitSingleLineToWidth`) only run when displayed text changes.

---

## [v1.0.4] — 2026-11-03

### Fixed
- **Overlapping Division Polygon Camera-Pan Distortion Fix (`Tab` Warroom Map):**
  - Fixed a bug where friendly and enemy division polygons overlapping one another would distort ("spazz out") when panning or zooming the Warroom camera — especially while paused.
  - **Root cause:** When two opposing divisions overlapped, `buildOverlappingPolygonStripesCache` computed `a.clipHullScreen = computeConvexHullCCW(a.polyScreen)` and clipped `CachedOverlapStripeBand` vertices via `clipConvexPolygon` and `clipPolygonToDiagonalSlab`. Because those helpers reused existing `Vector2f` object references from `polyScreen` rather than allocating independent copies, `transformCachedGeometryInPlace` mutated shared `Vector2f` vertices 2–4 times per camera pan frame (accumulating indefinitely while paused).
  - **Fix:** `computeConvexHullCCW`, `clipConvexPolygon`, `clipPolygonHalfPlaneDiag`, and `CachedOverlapStripeBand` now allocate independent `Vector2f` vertex instances with zero cross-list aliasing, and unpaused intermediate frames refresh overlap stripes after live ship-motion translation.

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
