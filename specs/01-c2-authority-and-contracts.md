# Spec 01 — C2 Authority, Hysteresis & Inter-Division Contracts

## 1. OTC $\leftrightarrow$ Subordinate Eligibility Contracts
**Invariant (`C2-INV-01`)**: `OtcBattlePlanner` MUST NEVER issue an `OtcOrderType` on tick $t$ that `DivisionCommanderAI` immediately vetoes into a fallback intent (`keeping the line`) on the same tick $t$.

| Order | Authority | Entry Gate | Exit / Retention Gate (Hysteresis) | Shared Contract Method |
|---|---|---|---|---|
| `FLANK` | `POSITIVE_CONTROL`, `COMMAND_BY_DIRECTION` | `avgFlux < 0.53`, `corridorDanger < 0.38`, `(now - lastBreakOff) >= 12s` | `avgFlux < 0.63`, `corridorDanger < 0.53`, `(now - lastBreakOff) >= 12s` | `DivisionCommanderAI.canAcceptOtcFlankOrder` |
| `HOLD_RESERVE` | `POSITIVE_CONTROL`, `COMMAND_BY_DIRECTION` | `!myGroup.reserveCommitted`, `inRangeFirepower == 0`, `localEnemyStrength < 6` | Released permanently once `myGroup.reserveCommitted == true` | `OtcAI` + `OtcBattlePlanner` |
| `GENERAL_CHASE` | `POSITIVE_CONTROL`, `COMMAND_BY_DIRECTION` | Force-ratio & contact gate met | Held for `>= 16s` (`phaseEnteredTime`) while `forceRatio >= 1.12` and `!crisis` | `OtcAI.evaluatePhase` |

## 2. Fleet-Level Setpiece Marshaling (`C2-INV-02`)
- A 1-division surface gun group already within gun range (`inRangeFirepowerScore > 0` or `dist <= max(1050 * rScale, avgEngageRange * 1.06)`) MUST NOT create a fake Fleet Setpiece marshal hold.
- Multi-division Setpiece strikes (`MARSHAL_FOR_STRIKE`) remain in Stage 1 until `allParticipantsInCombatRange` (or marshal offsets reached), committing early only on `now >= strikeTime` or genuine heavy-fire emergency (`incomingInRangeFirepower > 45 && avgFlux >= 0.42`).

## 3. Fleet Line-Passing Anti-Meatshield Gate (`C2-INV-03`)
When a non-skirmisher warship division (`DESTROYER`, `CRUISER`, `CAPITAL`) enters `HAUL_OUT_TO_VENT`:
- `cand == myGroup.mainEffortDivision` is excluded so the Main Effort anchor does not steal `bestCover` and then ignore the `REINFORCE` order.
- Light skirmishers (`FRIGATE`, `STRIKE_FRIGATE`, `PHASE`, `MISSILE_PHASE`) and missile standoffs (`MISSILE_FRIGATE`, `MISSILE_DESTROYER`, `MISSILE_CRUISER`, `MISSILE_BATTLESHIP`) MUST NEVER be ordered in front of a venting warship division.
- Covering a heavy battleline division (`CRUISER` / `CAPITAL`) additionally requires either `cand.archetype.isHeavyLineArchetype()` or `cand.totalFpStrength >= venting.totalFpStrength * 0.65`.

## 4. `HAUL_OUT_TO_VENT` Entry & Release Discipline (`C2-INV-04`)
- Phase divisions (`PHASE_DIVISION`, `PHASE_CRUISER_DIVISION`, `MISSILE_PHASE_DIVISION`) MUST NOT trigger `loneFrigateOvermatched` below `42%` flux.
- A division already in `HAUL_OUT_TO_VENT` (`stillRotating`) MUST release back into combat once `avgFluxLevel < 0.42` when locally overmatching the enemy (`localForceRatio >= 1.35`), rather than remaining stuck in `HAUL_OUT_TO_VENT` at `35–41%` flux with `1.5x–3.9x` local superiority.

## 5. Unique Combat Division Display Names (`C2-INV-05`)
- Every combat division within a `BattleGroupState` (across permanent player specs, multi-fleet NPC specs in `FactionFleetDoctrines`, and leftover pools in `DivisionAutoFormer`) MUST have a unique case-insensitive `displayName`.

## 6. Explicit Player OTC Selection Precedence (`C2-INV-06`)
- Once the player explicitly selects an OTC commander/ship in the pre-battle Deployment Dialog (`setPreferredPlayerOtcMemberId` -> `explicitPlayerOtcChoiceMade == true`, `otcManuallyAssigned == true`):
  1. `setAutoPreDeployPlayerOtcMemberId` MUST NEVER overwrite `preferredPlayerOtcMemberId` or `playerGroup.preferredOtcShipId` while `isExplicitPlayerOtcChoiceMade(playerGroup)` is true.
  2. `resolvePlayerFlagshipMember` and `resolvePreBattlePlayerOtcMember` MUST resolve the explicit OTC ID across the full union of live deployed ships, deployment-dialog grids, reserves, and campaign fleet members (surviving Starsector's in-place removal of deployed ships from `reservesGrid` on clicking Deploy), and MUST NEVER fall through to largest-ship / Alpha-Core auto-scoring.
  3. While an explicitly selected OTC ship is queued in `getDeployedCopy()` or sitting in `getReservesCopy()` (and has not been destroyed/disabled in battle), `resolveActiveFleetFlagship` MUST NOT substitute a subordinate Alpha Core vessel or trigger `lockPlayerAlphaCoreOtcInBattle`.

## 7. Symmetrical Main-Body Battle Line Fixing (`C2-INV-07`)
- Auto-populated `PermanentDivisionSpec.customStandoffRange` values (from `recalculateDivisionStandoffRangeFromGuns` / `AUTO`) MUST carry `customStandoffManual == false` so `div.hasExplicitCustomStandoff` is true ONLY when the player manually adjusted the division's Line Standoff (`DIV_STANDOFF_RANGE:<val>`). Player and NPC `CLOSE_ACTION` Battle Divisions therefore resolve symmetrical brawl standoffs (`0.72x minMainBatteryRange`) rather than hanging in the rear at a single member's longest-range beam (`5,275u+`).
- `OtcBattlePlanner.computeMainEffortFixingStation` MUST measure its approach axis from the **Main Body Centroid** (`OtcAI.computeMainBodyCentroid`, excluding rear carriers and `SERVICE_GROUP`s) rather than raw `fleetCentroid`, and under `OperationalAnchor.BATTLE_LINE` / `GRAND_WEDGE_SPEARHEAD` MUST NOT station a `CLOSE_ACTION` Battle Division behind committed friendly surface line divisions.
