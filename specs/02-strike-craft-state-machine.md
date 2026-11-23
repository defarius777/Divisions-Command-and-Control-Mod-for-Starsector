# Spec 02 — Carrier & Strike-Craft Posture State Machine

## 1. Posture & Range Tether Matrix
**Invariant (`SC-INV-01`)**: Vanilla Starsector's `FighterWing.getRange()` (`M.class`) and `EscortTargetManeuverV3` tether fighters to their parent carrier whenever `engageTarget == null` or distance exceeds `wing.getRange()`. Therefore, every untethered mission MUST simultaneously maintain:
1. `carrier.isPullBackFighters() == false` (`Engage`),
2. `VOIDSTRIKE_MAX_RANGE_STAT_ID >= max(16000, mapWidth + mapHeight + 4000)`, and
3. 60 Hz Wing-Leader `MOVEMENT_DEST` + opposite-turn `blockCommandForOneFrame` (while leaving non-leader wingmen on native `maneuvers.N` leader flocking: `rehearsalWingmanSteerViolations == 0`).

| Active Strike-Craft Doctrine | Enemy Visible? | Outbound Wings Downrange? | `pullBackFighters` | Bombers Committed? | Fighter / Recon Wings | `VOIDSTRIKE_MAX_RANGE_STAT_ID` |
|---|---|---|---|---|---|---|
| `0u` / `DTA` Tethered LPC | Any | N/A | **`true`** (Zero-Flux Boost) | Hold on carrier | Hold on carrier | Unmodified |
| `SUPPORT` (`>0u` range) | Threatened ally in range | N/A | `false` (`engage = ally`) | N/A | Escort ally; else **`true`** | Unmodified |
| `COMBAT_SPACE_PATROL` | Self-CAP, no bogey | No | **`true`** (Zero-Flux Boost) | Hold / Reserve | Scramble on bogey; umbrella over allied div | Unmodified (CAP radius) |
| `CYCLIC_STRIKES` | No (FoW) | **No** (`t=0` cold) | **`true`** (Zero-Flux Boost) | Hold on carrier | Hold on carrier | Unmodified |
| `CYCLIC_STRIKES` / `FIGHTER_SWEEP` | No (FoW) | **Yes** (`Opening -> Main` handoff) | **`false`** (Continuity!) | Keep if already downrange | Continue deep FoW sweep untethered | **Full Map Span** |
| `ARMED_RECONNAISSANCE` | No (FoW) | Any | **`false`** | Hold deckload on carrier | Untethered deep FoW sweep (`fowSearchReachedEntry` latch) | **Full Map Span** |
| `SEARCH_AND_DESTROY` / `DECKLOAD_STRIKE` / `ALPHA_STRIKE` | No (FoW) | Any | **`false`** | **Commit full package** | Screen ~260u ahead of outbound bombers | **Full Map Span** |
| Offensive Strike (`DECKLOAD`, `ALPHA`, `CYCLIC`, `S&D`) | **Yes** (Spotted) | Any | **`false`** | Commit Voidstrike pass | Escort / Split-Axis Voidstrike pass | **Full Map Span** |

## 2. Fog-of-War Honesty (`SC-INV-02`)
- `findLocalEnemyAcquiredByAirGroup` MUST check `OtcAI.isShipVisibleToSide(engine, div.side, enemyShip)` whenever `engine != null` so air groups acquire hidden hulls strictly upon entering true sensor/fighter line-of-sight, never via `5.0x` `fighterRangeScale` inflation.

## 3. Multi-Carrier Wave Cohesion (`SC-INV-03`)
- Squadrons from allied/sibling carrier divisions committed against the same target division (or within `3200u * rScale`) MUST merge into the active `Voidstrike` wave rather than spawning orphan 2–3 squadron sub-waves.

## 4. Dynamic Main-Body-Anchored Carrier `VAN` / `CENTER` / `REAR` Stationing (`SC-INV-04`)
- Pure carrier divisions (`CARRIER_STANDOFF` / `isCarrierArchetype()` excluding brawling `BATTLECARRIER`s on `CLOSE_ACTION`) MUST anchor their hull stations (`VAN`, `CENTER`, `REAR`) dynamically to the **Main Body Frame** (`OtcAI.computeMainBodyCarrierStation` — built from active non-carrier, non-service, non-reserve surface combat divisions so carriers never drag their own anchor rearward):
  - **`VAN`**: Forward echelon riding abreast / just behind the leading shoulder of the Main Body (`+0.25x mainBodyHalfDepth`), bounded outside enemy surface-gun safety floor (`minSafeStandoff`).
  - **`CENTER`**: Core pocket tucked inside / immediately behind the Main Body centroid (`-0.35x divisionInterval`) under friendly line PD cover.
  - **`REAR`**: Screened close-rear echelon (`rearEdge - 0.85x divisionInterval`) that translates and maneuvers cohesively with the advancing Main Body (never marooned at static map-rear `reserveStation`).
- Unless `div.hasExplicitCustomStandoff` (`customStandoffManual == true`) is set, carrier hull distance to its enemy target division MUST be clamped within the carrier division's **effective striking envelope** (`<= max(minSafeStandoff, 0.88x liveFighterStrikeRange)`), ensuring carriers never hold back out of striking range of the enemy.
