# Spec 04 — Dynamic Battle Music Telemetry & Layer State Machine

## 1. Telemetry Source Contract (`MUS-INV-01`)
- `DynamicBattleMusicController.advance(...)` MUST read live fleet engagement (`smoothedEngagementLevel`) from **`playerGroup`** (combined with **`alliedAiGroup`** weighted by FP mass when allied fleets are deployed).
- It MUST NEVER read `smoothedEngagementLevel` from `side0OpposingView` (`isPassiveOpposingView == true`) when `playerGroup` has divisions, because passive opposing views return early in `OtcAI.updateTelemetry` without computing `updateBattleGroupEngagementTelemetry` (`smoothedEngagementLevel == 0.00f`).
- When the player has `0` combat divisions deployed (e.g., pure unassigned ship skirmish or spectator state), fallback engagement is `max(unassignedPlayerEngagement, enemyGroup.smoothedEngagementLevel)`.

## 2. Violent Contact Measurement (`MUS-INV-02`)
- `computeDivisionViolentContactIntensity` treats any combat-effective division actively discharging non-PD weapons or strike wings (`activeFiringRatio > 0.10`) under an active combat/screening/kiting intent (`CLOSE_AND_ENGAGE`, `PRESS_HOME`, `FORM_LINE_AND_FIX`, `WORK_FLANK`, `TRADE_SPACE_FOR_TIME`, `FEIGNED_RETREAT`, `TAKE_SCREENING_STATION`, `REINFORCE_DIVISION`, `TAKE_AND_HOLD`, missile/carrier strikes, `GIVE_GENERAL_CHASE`) as in contact (`enemyPresence >= 0.60 + 0.25 * activeFiringRatio`), even at `3,000–6,000u` Better Combat gun/carrier ranges.

## 3. Layer Lifecycle (`MUS-INV-03`)
- **Layer 0 (`ambience_base` / `battle_ambience_01.ogg`)**: `alwaysOn = true`, `fadeOnPause = false`, triggers immediately at battle session initialization (`threshold = 0.00`).
- **Layer 1..N (`base` faction battle track, e.g. Hegemony `faction_hegemony_encounter_02_hostile.ogg`)**: `alwaysOn = false`, `fadeOnPause = true`, triggers as soon as `currentBattleEngagementLevel >= activationThreshold` (`0.26` default), modulates volume continuously between `[minVolume, maxVolume]`, pauses smoothly in-place while combat is paused, and resets cleanly only on battle end or prolonged disengagement (`< 0.25 * activationThreshold` and `volume <= 0.006`).
- **Silent Encounters (`threat`, `dweller` / `abyssal`)**: `silent = true` suppresses Layer 1..N while preserving Layer 0 ambience.
