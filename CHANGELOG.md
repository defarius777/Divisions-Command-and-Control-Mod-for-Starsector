# Changelog — Divisions: Command and Control (`division_ai`)

All notable changes to **Divisions: Command and Control** are documented in this file.

---

## [v1.1.2b] — 2026-11-13 (Starsector `0.98.5a-RC5`)

### Changed
- **Removed Unused `LazyLib` (`lw_lazylib`) Dependency (Zero Required Dependencies):**
  - Removed the leftover `lw_lazylib` dependency block from `mod/mod_info.json`, build/test classpaths, `README.md`, and the TriOS TriLink badge URL so **Divisions: Command and Control** runs 100% standalone out of the box on vanilla `0.98.5a-RC5` without requiring `LazyLib` (`LunaLib` remains optional for in-game Mod Settings).

---

## [v1.1.2a] — 2026-11-13 (Starsector `0.98.5a-RC5`)

### Compatibility & Added
- **Full Starsector `0.98.5a-RC5` Cross-Platform Compatibility (Windows, macOS & Linux):**
  - Updated `mod_info.json` and `division_ai.version` to target `0.98.5a-RC5` while preserving full runtime bytecode compatibility with `0.98a-RC8`.
  - Verified all 7 zero-reflection `ObfuscatedBytecodeBridge` + `java.lang.invoke.MethodHandles` bridges (`DivisionDeploymentDialogOrganizer`, `DivisionCommanderAI`, `DivisionTacticalMapOverlay`, `CampaignFleetTooltipOrganizer`, `DivisionFleetTabInjector`, `DynamicBattleMusicController`, and `DivisionAISettings`) inside `com.fs.starfarer.loading.scripts.ScriptClassLoader` across the Windows, macOS, and Linux `0.98.5a-RC5` builds.
- **New `0.98.5a-RC5` Vanilla Faction Support (`diktat_achilles` — Sindrian Reformers):**
  - Added native Fleet Doctrine (`COMMAND_BY_DIRECTION`, `GRAND_WEDGE_SPEARHEAD`, `HAMMER_AND_ANVIL`, `DOUBLING: true`), military-first division nomenclature (`Reformer Battle Line`, `Reformer Cruiser Squadron`, `Reformer Destroyer Flotilla`, `Reformer Frigate Picket`, `Reformer Carrier Wing`), and `Reformer Command` OTC title support for the Reformed Sindrian Diktat (`diktat_achilles`).

### Fixed
- **`StarfarerSettings` Fleet & Battle Size Override Resolution (`DivisionAISettings` & `ObfuscatedBytecodeBridge`):**
  - Fixed `DivisionAISettings` fleet/battle size overrides silently failing on `0.98.5a-RC5` after `StarfarerSettings.new(int)` (`setBattleSize`) and `StarfarerSettings."new.new"` (`maxShipsInFleet` cached float field) were re-obfuscated to `\u00d200000(int)` and `\u00d3\u00d20000`. `DivisionAISettings` now binds public `SettingsAPI.setBattleSize(int)` when present and resolves the `StarfarerSettings` method/field names directly from `StarfarerSettings$1` bytecode across all platforms and versions.
- **"Focus on Carriers" Checkbox Action Binding & `0.98.5a-RC5` Deprecation Cleanups:**
  - Fixed `FleetDoctrineDialog` and `DivisionConfigureSubscreen` accidentally invoking the deprecated 7-argument `TooltipMakerAPI.addCheckbox(..., String font, ...)` overload instead of the 8-argument `(..., Object data, String font, ...)` overload on the **Focus on Carriers** checkbox (which passed the toggle action ID as the font path instead of `ButtonAPI.getCustomData()`), and updated `DivisionShowcaseMissions` objective creation to the non-deprecated 3-argument `MissionDefinitionAPI.addObjective(x, y, type)` API.

---

## [v1.1.2] — 2026-11-13 (Starsector `0.98a-RC8`)

### Added
- **Decoupled Division Hull Missions vs. Strike Craft Missions (`StrikeCraftMissionType` & `CARRIER_STANDOFF`):**
  - Separated warship hull missions (`Scouting`, `Close Action`, `Standoff Engagement`, `Carrier Standoff`, `Envelopment`, `ToT Alpha Strike`, `Screen`, `Reserve`, `Martyrdom`) from flight-deck strike craft missions (`Match Division`, `Armed Recon`, `Space Superiority`, `Combat Space Patrol`, `Cyclic Voidstrikes`, `Deckload Voidstrike`, `Alpha Voidstrike`, `Hold on Deck`) inside **Strike Craft Options** (`FleetDoctrineDialog`, `DivisionConfigureSubscreen`, `DivisionEditorDialog`).
  - Surface divisions with Converted Hangars can now brawl or flank up front while their fighter bays independently fly `Combat Space Patrol` or merge into fleet-wide `Alpha Voidstrikes`, and battlecarriers (`Legion`, `Odyssey`) can push `Close Action` while keeping a dedicated CAP umbrella overhead. Existing carrier saves migrate automatically.
- **Advanced Strike Craft Gunnery, Targeting Controls & 4x4 Academy Wargame Showcase Missions:**
  - Added **Voidstrike Targeting Bias** (`Free Selection`, `Cruisers & Capitals`, `Capitals First`), **Focus on Carriers** toggle, **Attack Geometry** (`Direct`, `L-Crossfire`, `Pincer`), **Split Mode** (`Unified`, `Split Axes`, `Split Targets`), **Gunnery Mode** (`Precision`, `Area`), and **AoA Correction** (`Low`, `Strong`), plus predictive friendly-fire line-of-fire gating and friendly-hull clear-lane sidestepping.
  - Completed a full carrier & Voidstrike doctrinal sweep across all 36 built-in/modded library factions and 4 carrier archetypes (`sd=2, sw=4` defaults), and added 16 playable **Academy Wargame Showcase Missions** (`D1-1` through `D4-4`: Hegemony, Persean League, Tri-Tachyon, and Remnant Ordo).
- **Military-First Fleet Nomenclature Sweep & Legacy Save Migration:**
  - Audited and refined all 8 player-selectable Fleet Nomenclature presets, 30+ vanilla and modded faction libraries (war, merchant, smuggler, and salvage roles), and showcase wargame rosters so hull classes and tactical echelons always come first (`Armada Battle Line`, `Warden Cruiser Squadron`, `Vigil Frigate Picket`, `Heavy Flight Group`, `Cruiser Mora`, `Guard Battle Line`, `Frigate Wolfpack`) and cultural/institutional symbols come second, with automatic save-load migration for unmodified pre-v1.1.2 default names.

### Performance & UI
- **Instant Fleet Doctrine Window & 6-Group Hull-Type Accordion:**
  - Eliminated Fleet Doctrine opening lag by opening on `Command Scheme`, organizing all 16 division types into a clean 6-group hull-type accordion (`Frigates`, `Destroyers`, `Cruisers`, `Capitals`, `Carriers`, `Special & Auxiliary`), culling off-viewport rows, and wrapping UI labels in-place without button overlap.

### Fixed
- **Pure-Command Newtonian Strike Craft Flight Physics (Zero Ship-Center Magnet Bugs):**
  - Removed all direct `fighter.setFacing(...)` and `fighter.getVelocity().set(...)` kinematic overrides from `VoidstrikeCoordinator`, driving strike craft exclusively through vanilla `ShipCommand` thrusters (`TURN_LEFT` / `TURN_RIGHT`, `ACCELERATE`, `DECELERATE`, `STRAFE_*`), inbound-only turn-in gates, latched flank hysteresis, and clean carrier landing-bay handoffs so fighters never snap-turn, teleport velocity, or magnet onto ship centers.
- **Campaign Battle C2 Setpieces, Convoy General Chase, Anvil Stability & Telemetry Hygiene:**
  - Civilian `Service Group` / crippled-only encounters bypass marshaling Setpieces and immediately enter `General Chase` so warships run down fleeing freighters instead of staging backward while convoys escape sensor range.
  - Prevented 1-division light frigate packs (`< 20 FP`) from spamming fleet-wide Setpieces (`35s` Setpiece cooldown), kept the Main Effort anvil steady on `FORM_LINE` during Hammer & Anvil / Double Envelopment / Kill-Box execution until the target visibly breaks, suppressed duplicate 24s repeat intent radio/log lines when `(intent, target)` has not changed, reclaimed uncommitted transient Voidstrike sortie numbers so numbering never skips, and flushed `BATTLE SUMMARY:` logs on early Campaign "Claim Victory" / "Pursue" / "Clean Disengage" exits.
- **Voidstrike Split-Wing (`Pincer Envelopment` / `L-Crossfire` / `Split Axes` / `Split Targets`) Multi-Angle Lethality & Synchronization:**
  - **One-Way Latched Flanking Gates (`flankingGateClearedTargetByFighter`):** Fixed a memoryless gate-oscillation bug in `VoidstrikeCoordinator.advanceEveryFrameStrikeCraftGunnery` where strike craft reaching their flanking turning gate (`<= 185u`) would step inward toward the enemy, fall outside the gate radius while still turning, and flip back into `onFlankingDogleg = true` (blocking `ShipCommand.FIRE` and turning 180° back toward the gate). Once a fighter or its squadron leader clears its flanking gate or crosses the gate plane inward, gate clearance is now latched for that target until ordnance expenditure or return.
  - **Multi-Angle Time-on-Target (ToT) Jaw Pacing:** Added `maxArmedSubWingEtaSec` synchronization across all armed sub-wings in a multi-angle strike (`PINCER_ENVELOPMENT`, `ANVIL_CROSSFIRE`, `SPLIT_AXES`). Shorter/faster attack legs (such as the `0°` Anvil leg in L-Crossfire or the nearer Pincer jaw) pace their approach speed while outside enemy PD range (`> 820u` and not under fire) so all attack axes roll in and release ordnance simultaneously to split and overwhelm enemy PD.
  - **Compact Per-Jaw Formation Indexing & AA-Aware Flank Selection:** Indexed `subColIdx` and `subRankIdx` per sub-wing/jaw (`gateDist = 620 + subRankIdx * 65u`) rather than across all 18+ squadrons in a division, and updated `resolveExposedFlankSign` to penalize flanks guarded by adjacent non-target enemy AA ships within `720u`.
  - **Active Voidstrike Cohesion Across Wide Pincer Jaws:** Updated `reconcileVoidstrikesForSide` Step 1 so squadrons already belonging to the same active `VoidstrikeRecord` remain unified in a single Voidstrike entity even when Port and Starboard Pincer jaws separate by `> 1,200u`.
- **Strike Craft Survivability, Standoff Release & Modded Gunship/Corvette Compatibility:**
  - **PD-Gated Evasive Jinking:** Restricted sinusoidal anti-AA jinking in `computeAntiAaEvasionWaypoint` to actual enemy PD threat envelopes (`dToTarget <= 880u` or within `760u` of an adjacent enemy AA ship, or when actively taking shield/hull damage) so strike craft fly straight at 100% speed during long-range approach instead of bleeding speed outside PD range.
  - **Full-Range Standoff Ordnance Release & No Point-Blank Dive-Through:** Removed the `+240u` behind-target dive projection on `MOVEMENT_DEST`, raised `applyTerminalStrikeCraftGunnery`'s damping envelope to `880u` (closing the `680..880u` gate turn-in dead zone), and triggered on-boresight ordnance release immediately upon entering `maxWepRange + targetRad * 0.90f` (up to `950u` for long-range torpedoes).
  - **Standoff Gunships / Corvettes (`isStandoffGunshipOrCorvette`) & Inner Dive Floor (`minInnerDiveFloor`):** Excluded standoff gunship/corvette wings (wings tagged `"attack_at_an_angle"` or with `"Gunship"`, `"Corvette"`, `"Artillery"`, or `"Platform"` role descriptions, such as UAF Auroran gunships/corvettes) from the 60 Hz dive-bomber nose/velocity lock, and added a hard inner dive floor (`targetRad + 45u`) that releases all 60 Hz velocity/facing overrides and asserts `BACK_OFF` so no strike craft or corvette ever flies onto the center of an enemy ship.
  - **Coordinated Stage 0/1 Range Gate & Forward Slot Projection:** Scoped `Stage 0` carrier orbit and `Stage 1` marshalling to squadrons within `950u` of their carrier or marshal point (preventing mid-flight recall loops when a second carrier division joins a strike), and replaced backward wingman slot-chasing with forward slot offsets on the attack target.
- **Independent Contact Classification & OTC / Carrier Target Prioritization:**
  - Fixed `DivisionAICombatPlugin.populateSpatialContactClusters` hardcoding all unassigned opposing ship clusters (`"Independent Contact X"`) to `CRUISER_DIVISION` and shifting contact numbers when ships entered/left Fog of War; contact clusters now receive accurate hull-based archetypes (`FRIGATE_DIVISION`, `DESTROYER_DIVISION`, `CRUISER_DIVISION`, `BATTLESHIP_DIVISION`) and reserve pooled slots by living flagship ID.
  - Excluded Carrier divisions (`isCarrierDivision`) from surface-line `nonMainCommittedFp` overkill in `OtcAI.pickPrimaryEnemyTargetDivision` and penalized lone 1-ship `"Independent Contact"` stragglers when organized enemy divisions are visible so neither the Fleet Commander (OTC) nor Carrier Divisions abandon enemy Cruiser/Capital divisions to chase lone stragglers.
  - Restored `DIRECT_ASSAULT` + `UNIFIED` as the default Voidstrike geometry/split doctrine for newly created divisions and standard faction doctrines (`PermanentDivisionManager`, `DivisionShowcaseMissions`).
- **Frigate Division Movement, Objective Capture Wingman Braking & Mutual `WORK_FLANK` Anti-Orbiting:**
  - Fixed `DivisionCommanderAI.selectDivisionFormation` locking 100% of `FRIGATE_DIVISION`s into `PROTECTIVE_CIRCLE` whenever any enemy carrier existed on the map; Frigate Divisions only adopt `PROTECTIVE_CIRCLE` when actively screening (`SCREEN_DIVISION`) or under an active incoming Voidstrike within `1,650u`.
  - Fixed wingman braking on `TAKE_AND_HOLD` objective capture orders so wingmen decelerate and hold their formation slots once within `slotTol` (`85u`) inside the capture ring.
  - Added mutual `WORK_FLANK` detection so opposing divisions both attempting to flank each other at `500..650u` standoff collapse their standoff distance (`0.42x`) and engage decisively instead of mutually orbiting.

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
