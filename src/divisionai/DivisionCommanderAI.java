package divisionai;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import divisionai.DivisionModel.BattlePhase;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.OtcOrderType;
import divisionai.DivisionModel.OtcOrder;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionIntentType;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionModel.StrikePlan;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.OtcPlay;
import divisionai.PermanentDivisionManager.LineStation;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

/**
 * Mid-level Division Commander AI:
 *  - Evaluates each division's capabilities, local overmatch, cohesion, flux pressure,
 *    OTC phase, and the division's Battle Group tasking.
 *  - Selects concrete single-division and cross-division tactics:
 *      * Harassing torpedo attack ("Destroyer Div 1" -> "Enemy Destroyer Div 4")
 *      * Time-on-target coordinated strike (OTC strike plans: stage, then commit together)
 *      * Strike-then-withdraw to reserve
 *      * Monotype division screening another division ("Destroyer Div 1" screening "Battle Div 2")
 *      * Relief of an endangered friendly division
 *      * Flux/hull rotation to reserve
 *      * Bounded pursuit & cohesion keeping
 *  - Honors Battle Group taskings according to the OTC's authority (Hybrid, Central Planning,
 *    Mission-Type Tactics, Target Coordination Only).
 *  - Commits to a chosen intent for the commander's commitment time unless an urgent trigger fires,
 *    while maneuver anchors keep tracking their targets every tick.
 *  - Logs intent transitions into the combat HUD log via DivisionCombatLog.
 *  - Executes intents cooperatively via ShipwideAIFlags without overriding player flagship
 *    or explicit player single-ship orders.
 */
public class DivisionCommanderAI {

    private static final float STRIKE_WINDOW_DURATION = 14.0f;
    private static final float FLAG_TTL = 0.85f;

    public static void evaluateAndExecuteDivisions(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            float now) {

        for (DivisionState div : myGroup.divisions) {
            if (!div.isCombatEffective()) continue;

            // Re-evaluate on the commander's decision cadence, but hold a committed intent unless something urgent happens
            boolean cadenceDue = (now - div.lastIntentChangeTime) >= div.commander.decisionCadenceSeconds;
            boolean committed = now < div.intentCommitUntil;
            if (cadenceDue && (!committed || hasUrgentTrigger(engine, div, myGroup, now))) {
                DivisionIntentType prevIntent = div.currentIntent;
                DivisionState prevEnemy = div.targetEnemyDivision;
                DivisionState prevFriendly = div.targetFriendlyDivision;
                BattleObjectiveAPI prevObjective = div.targetObjective;

                selectDivisionIntent(engine, div, myGroup, enemyGroup, now);
                div.lastIntentChangeTime = now;
                div.appliedOtcOrderVersion = div.otcOrder.version;

                boolean changed = div.currentIntent != prevIntent
                        || div.targetEnemyDivision != prevEnemy
                        || div.targetFriendlyDivision != prevFriendly
                        || div.targetObjective != prevObjective;
                if (changed) {
                    div.intentCommitUntil = isCommittableIntent(div.currentIntent)
                            ? now + Math.max(3f, div.commander.intentCommitSeconds)
                            : now;
                }
            }

            // Keep maneuver anchors tracking moving targets between intent evaluations
            refreshStationPoint(engine, div, myGroup, now);

            // Apply per-tick (0.5s cadence) ShipwideAIFlags nudges to division members
            applyDivisionFlagsToShips(engine, div, myGroup, now);
        }
    }

    /** Opening Mission during the opening window (30s-60s), Main Mission for the rest of the battle. */
    public static DivisionMissionType resolveActiveMission(DivisionState div, float now) {
        boolean inOpeningWindow = isInOpeningWindow(div, now);
        DivisionMissionType activeMission = inOpeningWindow ? div.openingMission : div.mainMission;
        if (activeMission == null) {
            if (div.archetype.isCarrierArchetype()) {
                activeMission = inOpeningWindow
                        ? DivisionMissionType.ARMED_RECONNAISSANCE
                        : DivisionMissionType.DECKLOAD_STRIKE;
            } else {
                activeMission = inOpeningWindow
                        ? DivisionMissionType.SCOUTING
                        : DivisionMissionType.CLOSE_ACTION;
            }
        }
        return activeMission;
    }

    private static boolean isInOpeningWindow(DivisionState div, float now) {
        return now <= Math.max(30f, Math.min(60f, div.openingPhaseDurationSeconds));
    }

    /** Intents that hold for the commander's commitment time; the rest end on their own triggers. */
    private static boolean isCommittableIntent(DivisionIntentType intent) {
        switch (intent) {
            case CLOSE_UP:
            case HOLD_IN_RESERVE:
            case HAUL_OUT_TO_VENT:
            case BREAK_OFF_AND_RETIRE:
            case MARSHAL_FOR_STRIKE:
            case GIVE_GENERAL_CHASE:
                return false;
            default:
                return true;
        }
    }

    /** Events that break intent commitment and force an immediate re-evaluation. */
    private static boolean hasUrgentTrigger(CombatEngineAPI engine, DivisionState div, BattleGroupState myGroup, float now) {
        if (div.otcOrder.version != div.appliedOtcOrderVersion) return true;
        if (isStrikeRunOver(div, now)) return true;
        if (isUnderHighFluxPressure(div, myGroup)
                && div.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT
                && div.currentIntent != DivisionIntentType.BREAK_OFF_AND_RETIRE) {
            return true;
        }
        if (myGroup.currentPhase == BattlePhase.FIGHTING_RETIREMENT
                && div.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT) {
            return true;
        }
        if (div.targetEnemyDivision != null
                && (!div.targetEnemyDivision.isCombatEffective()
                || !OtcAI.isDivisionVisibleToSide(engine, div.side, div.targetEnemyDivision))) {
            return true;
        }
        if (div.targetFriendlyDivision != null && !div.targetFriendlyDivision.isCombatEffective()) return true;
        if (div.currentIntent == DivisionIntentType.TAKE_AND_HOLD
                && div.targetObjective != null && div.targetObjective.getOwner() == div.side) {
            return true;
        }
        if (div.currentIntent == DivisionIntentType.REINFORCE_DIVISION
                && div.targetFriendlyDivision != null && div.targetFriendlyDivision.localForceRatio >= 1.0f) {
            return true;
        }
        return false;
    }

    /** A torpedo run finished its window, ran dry, or built flux; or a flank maneuver has run out of steam. */
    private static boolean isStrikeRunOver(DivisionState div, float now) {
        boolean inMissileStrike = (div.currentIntent == DivisionIntentType.HARASSING_MISSILE_ATTACK
                || div.currentIntent == DivisionIntentType.TOT_MISSILE_STRIKE);
        boolean inFlankManeuver = (div.currentIntent == DivisionIntentType.WORK_FLANK);

        boolean missileRunComplete = inMissileStrike && ((now - div.strikeCommittedTimestamp) >= STRIKE_WINDOW_DURATION
                || div.avgFluxLevel >= div.commander.rotationFluxThreshold * 0.90f
                || (div.hasMissileStrikeCapability && div.usableStrikeAmmoCount <= 0));
        boolean flankBreakOffNeeded = inFlankManeuver
                && (div.avgFluxLevel >= div.commander.rotationFluxThreshold * 0.88f
                || ((now - div.strikeCommittedTimestamp) >= (STRIKE_WINDOW_DURATION * 1.5f) && div.avgFluxLevel >= 0.45f));
        return missileRunComplete || flankBreakOffNeeded;
    }

    /** Requires actual flux buildup (or critical damage under heavy local overmatch) so fresh divisions never retreat. */
    private static boolean isUnderHighFluxPressure(DivisionState div, BattleGroupState myGroup) {
        boolean highFluxPressure = div.avgFluxLevel >= div.commander.rotationFluxThreshold
                || (div.maxMemberFluxLevel >= 0.78f && div.localEnemyStrength > 12f && div.localForceRatio < 0.65f)
                || (div.avgFluxLevel >= 0.45f && div.localEnemyStrength > 14f && div.localForceRatio < 0.50f && div.commander.aggressionBias < 1.35f);
        boolean canRotateOut = (div != myGroup.mainEffortDivision || div.avgFluxLevel >= 0.72f || myGroup.divisions.size() >= 3);
        return highFluxPressure && canRotateOut;
    }

    private static boolean isEndangered(DivisionState div) {
        return div.localEnemyStrength >= 12f && div.localForceRatio < 0.85f;
    }

    /**
     * The enemy division this division should fight: the OTC's allocated target when one is active
     * (binding under Hybrid / Central Planning / Target Coordination; preferred under Mission-Type Tactics,
     * where a much closer enemy on top of the division takes precedence), otherwise its own pick.
     */
    private static DivisionState resolveOtcTarget(
            CombatEngineAPI engine,
            DivisionState div,
            OtcOrder task,
            boolean taskActive,
            OtcAuthority auth,
            DivisionState ownPick) {
        if (!taskActive || task.targetEnemy == null) return ownPick;
        DivisionState otcTarget = task.targetEnemy;
        if (!otcTarget.isCombatEffective() || !OtcAI.isDivisionVisibleToSide(engine, div.side, otcTarget)) {
            return ownPick;
        }
        if (auth.isBinding() || ownPick == null || ownPick == otcTarget) return otcTarget;
        float dOwn = distance(div.centroid, OtcAI.getVisibleCentroidForSide(engine, div.side, ownPick));
        float dOtc = distance(div.centroid, OtcAI.getVisibleCentroidForSide(engine, div.side, otcTarget));
        return (dOwn < 1400f && dOtc > 2600f) ? ownPick : otcTarget;
    }

    private static void selectDivisionIntent(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            float now) {

        DivisionState prevEnemyTarget = div.targetEnemyDivision;
        BattleObjectiveAPI prevObjective = div.targetObjective;
        DivisionIntentType prevIntent = div.currentIntent;

        div.targetEnemyDivision = null;
        div.targetFriendlyDivision = null;
        div.targetObjective = null;
        String targetLabel = "";
        String reason = "";

        OtcAuthority auth = myGroup.authority;
        OtcOrder task = div.otcOrder;
        boolean taskActive = task.isActive(now);

        DivisionState ownNearestEnemyDiv = findBestEnemyDivisionFor(engine, div, enemyGroup, false, prevEnemyTarget);
        DivisionState nearestEnemyDiv = resolveOtcTarget(engine, div, task, taskActive, auth, ownNearestEnemyDiv);
        DivisionState missileTargetEnemyDiv = (nearestEnemyDiv != ownNearestEnemyDiv && auth.isBinding())
                ? nearestEnemyDiv
                : findBestEnemyDivisionFor(engine, div, enemyGroup, true, prevEnemyTarget);
        DivisionState endangeredFriendlyDiv = findEndangeredFriendlyDivision(div, myGroup);
        DivisionState heavyFriendlyToScreen = findHeavyFriendlyDivisionToScreen(div, myGroup);
        BattleObjectiveAPI commandAssignedObj = findTaskManagerObjectiveForDivision(engine, div, myGroup, prevObjective);
        BattleObjectiveAPI uncapturedObj = (commandAssignedObj != null)
                ? commandAssignedObj
                : findNearestContestObjective(engine, div, myGroup, prevObjective);

        float distToVisibleEnemy = (nearestEnemyDiv != null)
                ? distance(div.centroid, OtcAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv))
                : Float.MAX_VALUE;
        float distToMissileTarget = (missileTargetEnemyDiv != null)
                ? distance(div.centroid, OtcAI.getVisibleCentroidForSide(engine, div.side, missileTargetEnemyDiv))
                : Float.MAX_VALUE;

        boolean inMissileStrike = (prevIntent == DivisionIntentType.HARASSING_MISSILE_ATTACK
                || prevIntent == DivisionIntentType.TOT_MISSILE_STRIKE);
        boolean inFlankManeuver = (prevIntent == DivisionIntentType.WORK_FLANK);

        // 1. Torpedo strike window finished / torpedoes expended / flux built up -> Strike-then-withdraw to reserve
        if (isStrikeRunOver(div, now)) {
            div.currentIntent = DivisionIntentType.BREAK_OFF_AND_RETIRE;
            div.targetEnemyDivision = nearestEnemyDiv;
            div.stationPoint.set(myGroup.reserveStation);
            div.lastBreakOffTimestamp = now;
            targetLabel = nearestEnemyDiv != null ? nearestEnemyDiv.displayName : "contact zone";
            reason = String.format("avg flux %.0f%%", div.avgFluxLevel * 100f);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 2. High flux / damaged division -> Rotate to reserve behind a friendly division.
        //    Once rotating, stay out until flux has genuinely bled off instead of bouncing at the threshold.
        boolean stillRotating = prevIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                && div.avgFluxLevel >= div.commander.rotationFluxThreshold * 0.55f;
        if (isUnderHighFluxPressure(div, myGroup) || stillRotating || myGroup.currentPhase == BattlePhase.FIGHTING_RETIREMENT) {

            DivisionState ventBehindFriend = (myGroup.mainEffortDivision != null && myGroup.mainEffortDivision != div)
                    ? myGroup.mainEffortDivision
                    : heavyFriendlyToScreen;

            div.currentIntent = DivisionIntentType.HAUL_OUT_TO_VENT;
            div.targetFriendlyDivision = ventBehindFriend;
            if (ventBehindFriend != null) {
                float retreatDirY = (div.side == 0) ? -1f : 1f;
                div.stationPoint.set(ventBehindFriend.centroid.x, ventBehindFriend.centroid.y + retreatDirY * 1100f);
                targetLabel = ventBehindFriend.displayName;
            } else {
                div.stationPoint.set(myGroup.reserveStation);
                targetLabel = "the reserve station";
            }
            reason = String.format("flux %.0f%%, local ratio %.2f", div.avgFluxLevel * 100f, div.localForceRatio);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        DivisionMissionType activeMission = resolveActiveMission(div, now);
        String missionPhaseTag = isInOpeningWindow(div, now) ? "Opening: " : "Main: ";
        boolean isHeavyLineOrCarrier = (div.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                || div.archetype.isCarrierArchetype());

        // 3. Explicit Command Window objective order (player orders outrank the OTC)
        if (commandAssignedObj != null && !isHeavyLineOrCarrier && div.localEnemyStrength < 18f) {
            div.currentIntent = DivisionIntentType.TAKE_AND_HOLD;
            div.targetObjective = commandAssignedObj;
            div.stationPoint.set(commandAssignedObj.getLocation());
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, commandAssignedObj.getDisplayName(), "under direct orders", now);
            return;
        }

        // 4. OTC tasking
        if (taskActive && applyOtcOrder(engine, div, myGroup, task, auth, activeMission, prevIntent, now)) {
            return;
        }

        // 5. Mission-driven objective seizure (the Main Effort stays on its fixing task instead)
        boolean stationedByOtc = taskActive && task.type == OtcOrderType.FORM_LINE && auth.allowsPositioning();
        if (!stationedByOtc
                && uncapturedObj != null
                && !isHeavyLineOrCarrier
                && activeMission != DivisionMissionType.RESERVE
                && div.localEnemyStrength < 14f
                && (activeMission == DivisionMissionType.SCOUTING
                || nearestEnemyDiv == null
                || (activeMission == DivisionMissionType.CLOSE_ACTION && distToVisibleEnemy > 2400f)
                || (div.isFastFlanker && distance(div.centroid, uncapturedObj.getLocation()) + 400f < distToVisibleEnemy))) {
            div.currentIntent = DivisionIntentType.TAKE_AND_HOLD;
            div.targetObjective = uncapturedObj;
            div.stationPoint.set(uncapturedObj.getLocation());
            targetLabel = uncapturedObj.getDisplayName();
            reason = missionPhaseTag + activeMission.shortName;
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 6. Battle Group Reserve mission or OTC Reserve designation, when the OTC is not orchestrating the reserve itself
        //    (with the Reserve & Exploit play the OTC holds and commits reserves through HOLD_RESERVE / RELIEVE taskings).
        boolean otcHoldsReserve = myGroup.isPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE) && auth.allowsRetasking();
        if (!otcHoldsReserve) {
            boolean calledUpByOtc = (endangeredFriendlyDiv != null)
                    || (myGroup.currentPhase == BattlePhase.REINFORCE_THE_LINE && nearestEnemyDiv != null)
                    || (myGroup.currentPhase == BattlePhase.GENERAL_CHASE && nearestEnemyDiv != null)
                    || (myGroup.currentPhase == BattlePhase.COORDINATED_STRIKE && nearestEnemyDiv != null && div.hasMissileStrikeCapability);
            if ((activeMission == DivisionMissionType.RESERVE || div.isDesignatedReserve) && !calledUpByOtc) {
                div.currentIntent = DivisionIntentType.HOLD_IN_RESERVE;
                div.stationPoint.set(myGroup.reserveStation);
                reason = (activeMission == DivisionMissionType.RESERVE)
                        ? (missionPhaseTag + "Reserve, awaiting OTC orders")
                        : "awaiting OTC orders";
                div.lastIntentChangeTime = now;
                DivisionCombatLog.logDivisionIntent(engine, div, "", reason, now);
                return;
            }
        }

        // 7. Local mutual support: relieve an endangered neighbour on the division's own initiative.
        //    Only when the OTC is not assigning relief centrally (or under Mission-Type Tactics for close neighbours),
        //    never by carriers, and never by a division that is itself in trouble.
        boolean closeReinforcementAllowed = !otcHoldsReserve || !auth.isBinding();
        if (closeReinforcementAllowed
                && endangeredFriendlyDiv != null
                && !div.archetype.isCarrierArchetype()
                && div.avgFluxLevel < 0.58f
                && !isEndangered(div)
                && (!otcHoldsReserve || distance(div.centroid, endangeredFriendlyDiv.centroid) < 2500f)
                && (activeMission == DivisionMissionType.RESERVE
                || div.isDesignatedReserve
                || div.isFastFlanker
                || div.localEnemyStrength < 15f)) {

            div.currentIntent = DivisionIntentType.REINFORCE_DIVISION;
            div.targetFriendlyDivision = endangeredFriendlyDiv;
            div.stationPoint.set(endangeredFriendlyDiv.centroid);
            targetLabel = endangeredFriendlyDiv.displayName;
            reason = (activeMission == DivisionMissionType.RESERVE || div.isDesignatedReserve)
                    ? String.format("reserve called forward to reinforce (local ratio %.2f)", endangeredFriendlyDiv.localForceRatio)
                    : String.format("hard pressed, local ratio %.2f", endangeredFriendlyDiv.localForceRatio);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 8. Cohesion check: if division ships have scattered too far apart, pause and reform before the mission
        if (div.stationKeepingRatio > 1.48f && div.getAliveMembers().size() >= 2) {
            div.currentIntent = DivisionIntentType.CLOSE_UP;
            div.stationPoint.set(div.centroid);
            reason = String.format("stations spread to %.0fu, limit %.0fu", div.spreadRadius, div.stationKeepingRadius);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, "", reason, now);
            return;
        }

        // 9. Execute configured Opening Mission / Main Mission priority (including Carrier Missions a..f)!
        boolean strikeCooldownElapsed = (now - div.lastBreakOffTimestamp) >= 12.0f;
        boolean soloStrikeAllowed = isSoloStrikeAllowed(myGroup, distToMissileTarget);

        if (activeMission.isCarrierMission) {
            DivisionState carrierTargetDiv = nearestEnemyDiv;
            if (activeMission == DivisionMissionType.ALPHA_STRIKE
                    && myGroup.primaryThreatEnemyDivision != null
                    && OtcAI.isDivisionVisibleToSide(engine, div.side, myGroup.primaryThreatEnemyDivision)) {
                carrierTargetDiv = myGroup.primaryThreatEnemyDivision;
            } else if (activeMission == DivisionMissionType.COMBAT_SPACE_PATROL
                    && endangeredFriendlyDiv != null) {
                DivisionState threatToFriend = findBestEnemyDivisionFor(engine, endangeredFriendlyDiv, enemyGroup, false, div.targetEnemyDivision);
                if (threatToFriend != null) {
                    carrierTargetDiv = threatToFriend;
                }
            }

            switch (activeMission) {
                case ARMED_RECONNAISSANCE:
                    div.currentIntent = DivisionIntentType.FLY_ARMED_RECONNAISSANCE;
                    div.targetEnemyDivision = carrierTargetDiv;
                    if (carrierTargetDiv != null) {
                        computeStandoffStation(engine, div, carrierTargetDiv, div.stationPoint);
                        targetLabel = carrierTargetDiv.displayName;
                    } else if (uncapturedObj != null) {
                        div.targetObjective = uncapturedObj;
                        div.stationPoint.set(div.centroid.x * 0.7f, div.centroid.y + (div.side == 0 ? 700f : -700f));
                        targetLabel = uncapturedObj.getDisplayName();
                    } else {
                        div.stationPoint.set(myGroup.reserveStation);
                    }
                    reason = missionPhaseTag + activeMission.shortName;
                    div.lastIntentChangeTime = now;
                    DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                    return;

                case SPACE_SUPERIORITY:
                    div.currentIntent = DivisionIntentType.FLY_FIGHTER_SWEEP;
                    div.targetEnemyDivision = carrierTargetDiv;
                    if (carrierTargetDiv != null) {
                        computeStandoffStation(engine, div, carrierTargetDiv, div.stationPoint);
                        targetLabel = carrierTargetDiv.displayName;
                    } else {
                        div.stationPoint.set(myGroup.reserveStation);
                    }
                    reason = missionPhaseTag + activeMission.shortName;
                    div.lastIntentChangeTime = now;
                    DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                    return;

                case COMBAT_SPACE_PATROL:
                    div.currentIntent = DivisionIntentType.FLY_COMBAT_SPACE_PATROL;
                    DivisionState protectedFriendly = (endangeredFriendlyDiv != null)
                            ? endangeredFriendlyDiv
                            : ((heavyFriendlyToScreen != null) ? heavyFriendlyToScreen : myGroup.mainEffortDivision);
                    div.targetFriendlyDivision = protectedFriendly;
                    div.targetEnemyDivision = carrierTargetDiv;
                    if (protectedFriendly != null && protectedFriendly != div) {
                        computePatrolStation(div, protectedFriendly, div.stationPoint);
                        targetLabel = protectedFriendly.displayName;
                    } else if (carrierTargetDiv != null) {
                        computeStandoffStation(engine, div, carrierTargetDiv, div.stationPoint);
                        targetLabel = carrierTargetDiv.displayName;
                    } else {
                        div.stationPoint.set(myGroup.reserveStation);
                    }
                    reason = missionPhaseTag + activeMission.shortName;
                    div.lastIntentChangeTime = now;
                    DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                    return;

                case CYCLIC_STRIKES:
                    div.currentIntent = DivisionIntentType.FLY_CYCLIC_STRIKES;
                    div.targetEnemyDivision = carrierTargetDiv;
                    if (carrierTargetDiv != null) {
                        computeStandoffStation(engine, div, carrierTargetDiv, div.stationPoint);
                        targetLabel = carrierTargetDiv.displayName;
                    } else {
                        div.stationPoint.set(myGroup.reserveStation);
                    }
                    reason = missionPhaseTag + activeMission.shortName;
                    div.lastIntentChangeTime = now;
                    DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                    return;

                case DECKLOAD_STRIKE:
                    div.currentIntent = DivisionIntentType.LAUNCH_DECKLOAD_STRIKE;
                    div.targetEnemyDivision = carrierTargetDiv;
                    if (carrierTargetDiv != null) {
                        computeStandoffStation(engine, div, carrierTargetDiv, div.stationPoint);
                        targetLabel = carrierTargetDiv.displayName;
                    } else {
                        div.stationPoint.set(myGroup.reserveStation);
                    }
                    reason = missionPhaseTag + activeMission.shortName;
                    div.lastIntentChangeTime = now;
                    DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                    return;

                case ALPHA_STRIKE:
                    div.currentIntent = DivisionIntentType.LAUNCH_ALPHA_STRIKE;
                    div.targetEnemyDivision = carrierTargetDiv;
                    if (carrierTargetDiv != null) {
                        computeStandoffStation(engine, div, carrierTargetDiv, div.stationPoint);
                        targetLabel = carrierTargetDiv.displayName;
                    } else {
                        div.stationPoint.set(myGroup.reserveStation);
                    }
                    String syncPartner = (myGroup.mainEffortDivision != null && myGroup.mainEffortDivision != div)
                            ? (" with \"" + myGroup.mainEffortDivision.displayName + "\"")
                            : "";
                    reason = missionPhaseTag + activeMission.shortName + syncPartner;
                    div.lastIntentChangeTime = now;
                    DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                    return;

                default:
                    break;
            }
        }

        if (activeMission == DivisionMissionType.SCREEN && heavyFriendlyToScreen != null) {
            div.currentIntent = DivisionIntentType.TAKE_SCREENING_STATION;
            div.targetFriendlyDivision = heavyFriendlyToScreen;
            div.targetEnemyDivision = nearestEnemyDiv;
            computeScreeningStation(engine, div, heavyFriendlyToScreen, nearestEnemyDiv, div.stationPoint, div.side);
            targetLabel = heavyFriendlyToScreen.displayName;
            reason = missionPhaseTag + activeMission.shortName;
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        if (activeMission == DivisionMissionType.ENVELOPMENT && nearestEnemyDiv != null) {
            if (strikeCooldownElapsed
                    && soloStrikeAllowed
                    && div.hasMissileStrikeCapability
                    && div.usableStrikeAmmoCount > 0
                    && div.avgFluxLevel < 0.55f
                    && missileTargetEnemyDiv != null
                    && distToMissileTarget <= 4600f) {
                div.currentIntent = DivisionIntentType.HARASSING_MISSILE_ATTACK;
                Vector2f visTargetPos = OtcAI.getVisibleCentroidForSide(engine, div.side, missileTargetEnemyDiv);
                div.targetEnemyDivision = missileTargetEnemyDiv;
                div.stationPoint.set(visTargetPos);
                if (!inMissileStrike) {
                    div.strikeCommittedTimestamp = now;
                }
                targetLabel = missileTargetEnemyDiv.displayName;
                reason = String.format("%s%s (%d salvos)", missionPhaseTag, activeMission.shortName, div.usableStrikeAmmoCount);
                div.lastIntentChangeTime = now;
                DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                return;
            }
            if (strikeCooldownElapsed && div.avgFluxLevel < 0.58f) {
                div.currentIntent = DivisionIntentType.WORK_FLANK;
                div.targetEnemyDivision = nearestEnemyDiv;
                computeFlankStation(engine, div, nearestEnemyDiv, div.stationPoint, myGroup);
                if (!inFlankManeuver) {
                    div.strikeCommittedTimestamp = now;
                }
                targetLabel = nearestEnemyDiv.displayName;
                reason = missionPhaseTag + activeMission.shortName + flankSideTag(div, nearestEnemyDiv, myGroup);
                div.lastIntentChangeTime = now;
                DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                return;
            }
        }

        if ((activeMission == DivisionMissionType.STANDOFF_ENGAGEMENT
                || activeMission == DivisionMissionType.SCOUTING)
                && nearestEnemyDiv != null) {
            div.currentIntent = DivisionIntentType.CLOSE_AND_ENGAGE;
            div.targetEnemyDivision = nearestEnemyDiv;
            computeStandoffStation(engine, div, nearestEnemyDiv, div.stationPoint);
            targetLabel = nearestEnemyDiv.displayName;
            reason = String.format("%s%s (standoff range %.0fu)", missionPhaseTag, activeMission.shortName, div.standoffRange);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        if (activeMission == DivisionMissionType.CLOSE_ACTION && nearestEnemyDiv != null) {
            Vector2f visEnemyPos = OtcAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv);
            div.targetEnemyDivision = nearestEnemyDiv;
            div.stationPoint.set(visEnemyPos);
            targetLabel = nearestEnemyDiv.displayName;
            if (div.localForceRatio >= 0.88f && distToVisibleEnemy < 3400f) {
                div.currentIntent = DivisionIntentType.PRESS_HOME;
            } else {
                div.currentIntent = DivisionIntentType.CLOSE_AND_ENGAGE;
            }
            reason = missionPhaseTag + activeMission.shortName;
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 10. Fallback Monotype Screening when Capital/Carrier needs screen
        if (!div.archetype.isComposite
                && div.isScreenCapable
                && !div.hasMissileStrikeCapability
                && heavyFriendlyToScreen != null) {

            float distToHeavy = distance(div.centroid, heavyFriendlyToScreen.centroid);
            boolean isCapitalOrCarrier = heavyFriendlyToScreen.archetype.isCarrierArchetype()
                    || heavyFriendlyToScreen.archetype == DivisionArchetype.BATTLESHIP_DIVISION;
            boolean cruiserNeedsScreen = !div.isFastFlanker
                    || nearestEnemyDiv == null
                    || (heavyFriendlyToScreen.localEnemyStrength > 14f && heavyFriendlyToScreen.localForceRatio < 0.90f);
            boolean heavyNeedsScreen = isCapitalOrCarrier
                    || (cruiserNeedsScreen && (heavyFriendlyToScreen.localEnemyStrength > 10f || distToHeavy < 2400f));

            if (heavyNeedsScreen) {
                div.currentIntent = DivisionIntentType.TAKE_SCREENING_STATION;
                div.targetFriendlyDivision = heavyFriendlyToScreen;
                div.targetEnemyDivision = nearestEnemyDiv;
                computeScreeningStation(engine, div, heavyFriendlyToScreen, nearestEnemyDiv, div.stationPoint, div.side);
                targetLabel = heavyFriendlyToScreen.displayName;
                reason = "forward PD and anti-flank screen";
                div.lastIntentChangeTime = now;
                DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                return;
            }
        }

        // 11. Torpedo / Strike Division: Harassing torpedo attack or Time-on-Target strike (ONLY against visible enemies within strike range <= 4600u)
        if (strikeCooldownElapsed
                && soloStrikeAllowed
                && div.hasMissileStrikeCapability
                && div.usableStrikeAmmoCount > 0
                && div.avgFluxLevel < 0.55f
                && missileTargetEnemyDiv != null
                && distToMissileTarget <= 4600f) {

            boolean timeOnTargetCue = (myGroup.currentPhase == BattlePhase.COORDINATED_STRIKE)
                    && (myGroup.mainEffortDivision != null && myGroup.mainEffortDivision != div)
                    && (missileTargetEnemyDiv == myGroup.primaryThreatEnemyDivision);

            if (timeOnTargetCue) {
                div.currentIntent = DivisionIntentType.TOT_MISSILE_STRIKE;
                reason = "time-on-target with \"" + myGroup.mainEffortDivision.displayName + "\"";
            } else {
                div.currentIntent = DivisionIntentType.HARASSING_MISSILE_ATTACK;
                reason = String.format("%d missile salvos ready", div.usableStrikeAmmoCount);
            }

            Vector2f visTargetPos = OtcAI.getVisibleCentroidForSide(engine, div.side, missileTargetEnemyDiv);
            div.targetEnemyDivision = missileTargetEnemyDiv;
            div.stationPoint.set(visTargetPos);
            if (!inMissileStrike) {
                div.strikeCommittedTimestamp = now;
            }
            targetLabel = missileTargetEnemyDiv.displayName;
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 12. Fast flankers swing wide to flank visible enemy division when within operational contact range (<= 4500u)
        if (strikeCooldownElapsed
                && div.isFastFlanker
                && nearestEnemyDiv != null
                && distToVisibleEnemy <= 4500f
                && myGroup.mainEffortDivision != null
                && myGroup.mainEffortDivision != div
                && div.avgFluxLevel < 0.52f) {

            div.currentIntent = DivisionIntentType.WORK_FLANK;
            div.targetEnemyDivision = nearestEnemyDiv;
            computeFlankStation(engine, div, nearestEnemyDiv, div.stationPoint, myGroup);
            if (!inFlankManeuver) {
                div.strikeCommittedTimestamp = now;
            }
            targetLabel = nearestEnemyDiv.displayName;
            reason = "envelopment" + flankSideTag(div, nearestEnemyDiv, myGroup);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 13. Press local overmatch vs standard line advance (strictly respecting Fog of War)
        if (nearestEnemyDiv != null) {
            Vector2f visEnemyPos = OtcAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv);
            div.targetEnemyDivision = nearestEnemyDiv;
            div.stationPoint.set(visEnemyPos);
            targetLabel = nearestEnemyDiv.displayName;

            if (div.localForceRatio >= Math.max(1.22f, div.commander.commitForceRatio) && distToVisibleEnemy < 3200f) {
                div.currentIntent = DivisionIntentType.PRESS_HOME;
                reason = String.format("local advantage %.2fx", div.localForceRatio);
            } else {
                div.currentIntent = DivisionIntentType.CLOSE_AND_ENGAGE;
                reason = "keeping the line";
            }
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
        } else {
            // No enemy visible in Fog of War yet: advance on reconnaissance / objective posture without map-hacking
            if (uncapturedObj != null && !isHeavyLineOrCarrier) {
                div.currentIntent = DivisionIntentType.TAKE_AND_HOLD;
                div.targetObjective = uncapturedObj;
                div.stationPoint.set(uncapturedObj.getLocation());
                targetLabel = uncapturedObj.getDisplayName();
                reason = "advancing without contact";
                div.lastIntentChangeTime = now;
                DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            } else {
                float scoutDirY = (div.side == 0) ? 1400f : -1400f;
                div.currentIntent = DivisionIntentType.CLOSE_AND_ENGAGE;
                div.stationPoint.set(div.centroid.x * 0.6f, div.centroid.y + scoutDirY);
                div.lastIntentChangeTime = now;
            }
        }
    }

    /**
     * Expresses an active Battle Group tasking as this division's intent. Returns false when the tasking only
     * shapes the division's own mission (targets, flank sides) or when the division declines a request
     * under Mission-Type Tactics; the mission ladder then runs with the OTC target applied.
     */
    private static boolean applyOtcOrder(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            OtcOrder task,
            OtcAuthority auth,
            DivisionMissionType activeMission,
            DivisionIntentType prevIntent,
            float now) {

        DivisionState target = task.targetEnemy;
        boolean targetValid = target != null && target.isCombatEffective()
                && OtcAI.isDivisionVisibleToSide(engine, div.side, target);
        boolean binding = auth.isBinding();
        String reason = task.reason;

        switch (task.type) {
            case MARSHAL: {
                if (!targetValid || (!binding && !acceptsStrikeRequest(div))) return false;
                div.currentIntent = DivisionIntentType.MARSHAL_FOR_STRIKE;
                div.targetEnemyDivision = target;
                if (task.hasStation) {
                    div.stationPoint.set(task.station);
                } else {
                    computeStandoffStation(engine, div, target, div.stationPoint);
                }
                break;
            }
            case EXECUTE_STRIKE: {
                if (!targetValid || (!binding && !acceptsStrikeRequest(div))) return false;
                if (prevIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE) return false; // already spent this strike
                boolean wasStriking = prevIntent == DivisionIntentType.TOT_MISSILE_STRIKE
                        || prevIntent == DivisionIntentType.LAUNCH_ALPHA_STRIKE;
                div.targetEnemyDivision = target;
                if (div.archetype.isCarrierArchetype()) {
                    div.currentIntent = DivisionIntentType.LAUNCH_ALPHA_STRIKE;
                    computeStandoffStation(engine, div, target, div.stationPoint);
                } else {
                    div.currentIntent = DivisionIntentType.TOT_MISSILE_STRIKE;
                    div.stationPoint.set(OtcAI.getVisibleCentroidForSide(engine, div.side, target));
                }
                if (!wasStriking) {
                    StrikePlan plan = myGroup.strikePlan;
                    div.strikeCommittedTimestamp = (plan != null && plan.committed) ? plan.commitTime : now;
                }
                break;
            }
            case REINFORCE: {
                DivisionState friend = task.targetFriendly;
                if (friend == null || !friend.isCombatEffective() || friend == div) return false;
                if (!binding && !(div.avgFluxLevel < 0.58f && (div.localEnemyStrength < 15f || div.localForceRatio >= 1.0f))) {
                    return false;
                }
                div.currentIntent = DivisionIntentType.REINFORCE_DIVISION;
                div.targetFriendlyDivision = friend;
                div.stationPoint.set(friend.centroid);
                DivisionCombatLog.logDivisionIntent(engine, div, friend.displayName, reason, now);
                return true;
            }
            case HOLD_RESERVE: {
                div.currentIntent = DivisionIntentType.HOLD_IN_RESERVE;
                div.stationPoint.set(myGroup.reserveStation);
                DivisionCombatLog.logDivisionIntent(engine, div, "", reason, now);
                return true;
            }
            case GENERAL_CHASE: {
                if (!targetValid || (!binding && div.avgFluxLevel >= 0.50f)) return false;
                div.currentIntent = DivisionIntentType.GIVE_GENERAL_CHASE;
                div.targetEnemyDivision = target;
                div.stationPoint.set(OtcAI.getVisibleCentroidForSide(engine, div.side, target));
                break;
            }
            case FORM_LINE: {
                if (!targetValid) return false;
                // Strike time: the Main Effort presses the struck division (timing is within every authority but D)
                if (OtcBattlePlanner.isStrikeExecutingOn(myGroup, target) && auth != OtcAuthority.FIRE_DISTRIBUTION_ONLY) {
                    div.currentIntent = DivisionIntentType.PRESS_HOME;
                    div.targetEnemyDivision = target;
                    div.stationPoint.set(OtcAI.getVisibleCentroidForSide(engine, div.side, target));
                    reason = "OTC: execute, press home on \"" + target.displayName + "\"";
                    break;
                }
                // Only Central Planning overrides the Main Effort's own mission style with a fixing posture
                if (auth != OtcAuthority.POSITIVE_CONTROL) return false;
                div.currentIntent = DivisionIntentType.FORM_LINE_AND_FIX;
                div.targetEnemyDivision = target;
                if (task.hasStation) {
                    div.stationPoint.set(task.station);
                } else {
                    computeStandoffStation(engine, div, target, div.stationPoint);
                }
                break;
            }
            case FLANK: {
                if (auth != OtcAuthority.POSITIVE_CONTROL) return false;
                if (!targetValid || div.avgFluxLevel >= 0.58f || (now - div.lastBreakOffTimestamp) < 12f) return false;
                boolean wasFlanking = prevIntent == DivisionIntentType.WORK_FLANK;
                div.currentIntent = DivisionIntentType.WORK_FLANK;
                div.targetEnemyDivision = target;
                computeFlankStation(engine, div, target, div.stationPoint, myGroup);
                if (!wasFlanking) {
                    div.strikeCommittedTimestamp = now;
                }
                break;
            }
            case ENGAGE_ASSIGNED: {
                if (auth != OtcAuthority.POSITIVE_CONTROL) return false;
                if (!targetValid || div.archetype.isCarrierArchetype() || activeMission == DivisionMissionType.SCREEN) return false;
                Vector2f visEnemyPos = OtcAI.getVisibleCentroidForSide(engine, div.side, target);
                float dist = distance(div.centroid, visEnemyPos);
                div.targetEnemyDivision = target;
                if (activeMission == DivisionMissionType.STANDOFF_ENGAGEMENT) {
                    div.currentIntent = DivisionIntentType.CLOSE_AND_ENGAGE;
                    computeStandoffStation(engine, div, target, div.stationPoint);
                } else if (div.localForceRatio >= Math.max(1.0f, div.commander.commitForceRatio) && dist < 3200f) {
                    div.currentIntent = DivisionIntentType.PRESS_HOME;
                    div.stationPoint.set(visEnemyPos);
                } else {
                    div.currentIntent = DivisionIntentType.CLOSE_AND_ENGAGE;
                    div.stationPoint.set(visEnemyPos);
                }
                break;
            }
            default:
                return false;
        }

        DivisionCombatLog.logDivisionIntent(engine, div, target != null ? target.displayName : "", reason, now);
        return true;
    }

    /** Mission-Type Tactics: a division joins a timed strike only if it is in shape to. */
    private static boolean acceptsStrikeRequest(DivisionState div) {
        return div.avgFluxLevel < 0.60f && (div.localEnemyStrength < 20f || div.localForceRatio >= 0.90f);
    }

    /**
     * With the Timed Strikes play (outside Mission-Type Tactics) strike divisions hold their missiles for the OTC's
     * synchronized strikes instead of freelancing, except against targets that come within close range.
     */
    private static boolean isSoloStrikeAllowed(BattleGroupState myGroup, float distToMissileTarget) {
        if (!myGroup.isPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES)) return true;
        if (myGroup.authority == OtcAuthority.COMMAND_BY_NEGATION) return true;
        if (myGroup.strikePlan != null) return false;
        if (myGroup.mainEffortDivision == null) return true;
        return distToMissileTarget < 2200f;
    }

    private static String flankSideTag(DivisionState div, DivisionState enemyDiv, BattleGroupState myGroup) {
        float side = assignedFlankSide(div, enemyDiv, myGroup);
        if (side == 0f) return "";
        return side > 0f ? " [OTC: port flank]" : " [OTC: starboard flank]";
    }

    /** The OTC-assigned flank side for this target, or 0 if the OTC has not assigned one. */
    private static float assignedFlankSide(DivisionState div, DivisionState enemyDiv, BattleGroupState myGroup) {
        if (myGroup == null || !myGroup.authority.allowsPositioning()) return 0f;
        OtcOrder task = div.otcOrder;
        if (task.type == OtcOrderType.FLANK && task.targetEnemy == enemyDiv && task.flankSide != 0f) {
            return task.flankSide;
        }
        return 0f;
    }

    /** Recomputes the current intent's maneuver station so it tracks moving targets every tick. */
    private static void refreshStationPoint(CombatEngineAPI engine, DivisionState div, BattleGroupState myGroup, float now) {
        DivisionState enemy = div.targetEnemyDivision;
        DivisionState friend = div.targetFriendlyDivision;
        boolean enemyTrackable = enemy != null && enemy.isCombatEffective()
                && OtcAI.isDivisionVisibleToSide(engine, div.side, enemy);

        switch (div.currentIntent) {
            case TAKE_SCREENING_STATION:
                if (friend != null) {
                    computeScreeningStation(engine, div, friend, enemy, div.stationPoint, div.side);
                }
                break;
            case WORK_FLANK:
                if (enemyTrackable) {
                    computeFlankStation(engine, div, enemy, div.stationPoint, myGroup);
                }
                break;
            case REINFORCE_DIVISION:
                if (friend != null && friend.isCombatEffective()) {
                    div.stationPoint.set(friend.centroid);
                }
                break;
            case PRESS_HOME:
            case HARASSING_MISSILE_ATTACK:
            case TOT_MISSILE_STRIKE:
            case GIVE_GENERAL_CHASE:
                if (enemyTrackable) {
                    div.stationPoint.set(OtcAI.getVisibleCentroidForSide(engine, div.side, enemy));
                }
                break;
            case CLOSE_AND_ENGAGE:
                if (enemyTrackable) {
                    DivisionMissionType mission = resolveActiveMission(div, now);
                    if (mission == DivisionMissionType.STANDOFF_ENGAGEMENT || mission == DivisionMissionType.SCOUTING) {
                        computeStandoffStation(engine, div, enemy, div.stationPoint);
                    } else {
                        div.stationPoint.set(OtcAI.getVisibleCentroidForSide(engine, div.side, enemy));
                    }
                }
                break;
            case FORM_LINE_AND_FIX:
                if (div.otcOrder.isActive(now) && div.otcOrder.hasStation) {
                    div.stationPoint.set(div.otcOrder.station);
                } else if (enemyTrackable) {
                    computeStandoffStation(engine, div, enemy, div.stationPoint);
                }
                break;
            case MARSHAL_FOR_STRIKE:
                if (div.otcOrder.isActive(now) && div.otcOrder.hasStation) {
                    div.stationPoint.set(div.otcOrder.station);
                }
                break;
            case HOLD_IN_RESERVE:
            case BREAK_OFF_AND_RETIRE:
                div.stationPoint.set(myGroup.reserveStation);
                break;
            case HAUL_OUT_TO_VENT:
                if (friend != null && friend.isCombatEffective()) {
                    float retreatDirY = (div.side == 0) ? -1f : 1f;
                    div.stationPoint.set(friend.centroid.x, friend.centroid.y + retreatDirY * 1100f);
                } else {
                    div.stationPoint.set(myGroup.reserveStation);
                }
                break;
            case FLY_COMBAT_SPACE_PATROL:
                if (friend != null && friend != div && friend.isCombatEffective()) {
                    computePatrolStation(div, friend, div.stationPoint);
                } else if (enemyTrackable) {
                    computeStandoffStation(engine, div, enemy, div.stationPoint);
                }
                break;
            case FLY_ARMED_RECONNAISSANCE:
            case FLY_FIGHTER_SWEEP:
            case FLY_CYCLIC_STRIKES:
            case LAUNCH_DECKLOAD_STRIKE:
            case LAUNCH_ALPHA_STRIKE:
                if (enemyTrackable) {
                    computeStandoffStation(engine, div, enemy, div.stationPoint);
                }
                break;
            default:
                break;
        }
    }

    private static void computePatrolStation(DivisionState carrierDiv, DivisionState protectedDiv, Vector2f outStation) {
        float backSign = (carrierDiv.side == 0) ? -1f : 1f;
        outStation.set(
                protectedDiv.centroid.x,
                protectedDiv.centroid.y + backSign * Math.max(600f, carrierDiv.standoffRange * 0.45f));
    }

    /**
     * Cooperatively nudges ships in the division via ShipwideAIFlags.
     * Never overrides the player's manually piloted flagship or explicit player orders.
     */
    private static void applyDivisionFlagsToShips(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            float now) {

        ShipAPI playerShip = engine.getPlayerShip();
        boolean playerManualControl = (playerShip != null && !engine.isUIAutopilotOn());

        CombatFleetManagerAPI fm = engine.getFleetManager(div.side);

        List<ShipAPI> alive = div.getAliveMembers();
        if (alive.isEmpty()) return;

        ShipAPI focusEnemyShip = pickFocusEnemyShip(engine, div.side, div.targetEnemyDivision, div.centroid);

        // Timed strike: participants and the Main Effort all concentrate on the plan's single focus ship
        StrikePlan plan = myGroup.strikePlan;
        if (plan != null && plan.committed && plan.focusShip != null
                && plan.focusShip.isAlive() && !plan.focusShip.isHulk()
                && (plan.participants.contains(div) || div == myGroup.mainEffortDivision)
                && OtcAI.isShipVisibleToSide(engine, div.side, plan.focusShip)) {
            focusEnemyShip = plan.focusShip;
        }

        for (int shipIdx = 0; shipIdx < alive.size(); shipIdx++) {
            ShipAPI ship = alive.get(shipIdx);

            // Release strike craft we held back for staging once the division is no longer staging
            if (div.currentIntent != DivisionIntentType.MARSHAL_FOR_STRIKE && div.fighterPullbackShipIds.remove(ship.getId())) {
                ship.setPullBackFighters(false);
            }

            if (playerManualControl && ship == playerShip) {
                continue;
            }
            CombatTaskManagerAPI tm = (fm != null) ? fm.getTaskManager(ship.isAlly()) : null;
            if (hasExplicitCommandOrder(ship, div, tm)) {
                continue;
            }

            ShipwideAIFlags flags = ship.getAIFlags();
            if (flags == null) continue;

            Vector2f slotPos = computeShipFormationSlot(engine, ship, shipIdx, alive, div, myGroup);
            boolean crowdedByPeer = isShipCrowdedByDivisionPeer(ship, alive, div.stationSpacing * 0.72f);

            float distToDivCenter = distance(ship.getLocation(), div.centroid);
            boolean shipStraggling = alive.size() >= 2 && distToDivCenter > (div.stationKeepingRadius * 1.35f);

            // Bounded pursuit & cohesion leash: prevent lone ships from chasing bait 3000u away
            if (shipStraggling && div.currentIntent != DivisionIntentType.HARASSING_MISSILE_ATTACK
                    && div.currentIntent != DivisionIntentType.TOT_MISSILE_STRIKE) {
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                continue;
            }

            switch (div.currentIntent) {
                case FORM_LINE_AND_FIX:
                    // Hold the fixing position and fight from it; no chasing, but no backing off either
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    if (focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    }
                    if (distance(ship.getLocation(), slotPos) > Math.max(450f, div.stationSpacing * 0.8f) || crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case MARSHAL_FOR_STRIKE:
                    // Move to the staging point and wait for strike time; carriers hold their strike craft back
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    if (distance(ship.getLocation(), slotPos) > 300f || crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    if (ship.getNumFighterBays() > 0 && !ship.isPullBackFighters()) {
                        ship.setPullBackFighters(true);
                        div.fighterPullbackShipIds.add(ship.getId());
                    }
                    break;

                case GIVE_GENERAL_CHASE: {
                    // Pursue on a leash measured from the battlegroup's centre, not from the division
                    float dx = ship.getLocation().x - myGroup.fleetCentroid.x;
                    float dy = ship.getLocation().y - myGroup.fleetCentroid.y;
                    float fromFleet = (float) Math.sqrt(dx * dx + dy * dy);
                    if (fromFleet > OtcBattlePlanner.CHASE_LEASH) {
                        float k = (OtcBattlePlanner.CHASE_LEASH * 0.8f) / Math.max(1f, fromFleet);
                        flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL,
                                new Vector2f(myGroup.fleetCentroid.x + dx * k, myGroup.fleetCentroid.y + dy * k));
                        break;
                    }
                    flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
                    if (focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    } else {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;
                }

                case HARASSING_MISSILE_ATTACK:
                case TOT_MISSILE_STRIKE:
                    flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL * 1.5f);
                    if (focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        if (distance(ship.getLocation(), focusEnemyShip.getLocation()) > 2100f) {
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        }
                    } else if (div.targetEnemyDivision != null) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case PRESS_HOME:
                    flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
                    if (focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        if (distance(ship.getLocation(), focusEnemyShip.getLocation()) > 2100f || crowdedByPeer) {
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        }
                    } else if (div.targetEnemyDivision != null) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case BREAK_OFF_AND_RETIRE:
                case HAUL_OUT_TO_VENT:
                    flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL * 1.4f);
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL * 1.4f);
                    flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL * 1.4f, slotPos);
                    break;

                case TAKE_SCREENING_STATION:
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    // Use spaced picket formation slots ahead/flank of the screened division instead of
                    // ESCORT_OTHER_SHIP on the flagship (which caused frigates to ball up on the battle line)
                    if (distance(ship.getLocation(), slotPos) > Math.max(260f, div.stationSpacing * 0.55f) || crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    } else if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 1650f) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    }
                    break;

                case REINFORCE_DIVISION:
                    if (div.targetFriendlyDivision != null && div.targetFriendlyDivision.shipNeedingHelp != null
                            && div.targetFriendlyDivision.shipNeedingHelp.isAlive()
                            && shipIdx == 0) {
                        flags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, div.targetFriendlyDivision.shipNeedingHelp);
                    } else {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case WORK_FLANK:
                case TAKE_AND_HOLD:
                case HOLD_IN_RESERVE:
                    if (distance(ship.getLocation(), slotPos) > Math.max(320f, div.stationSpacing * 0.65f) || crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    } else if (focusEnemyShip != null && div.currentIntent == DivisionIntentType.WORK_FLANK) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    }
                    break;

                case CLOSE_UP:
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    if (distToDivCenter > div.stationKeepingRadius * 0.85f || crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case FLY_ARMED_RECONNAISSANCE:
                case FLY_FIGHTER_SWEEP:
                case FLY_COMBAT_SPACE_PATROL:
                case FLY_CYCLIC_STRIKES:
                case LAUNCH_DECKLOAD_STRIKE:
                case LAUNCH_ALPHA_STRIKE:
                    // In a Mixed Carrier Division, non-carrier escorts protect the carrier flagship closely
                    if (div.archetype == DivisionArchetype.CARRIER_GROUP
                            && div.flagship != null
                            && ship != div.flagship
                            && ship.getNumFighterBays() == 0) {
                        flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                        flags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, div.flagship);
                        break;
                    }
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    if (focusEnemyShip != null) {
                        float distToEnemy = distance(ship.getLocation(), focusEnemyShip.getLocation());
                        if (distToEnemy < div.standoffRange * 0.85f) {
                            flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        } else if (distance(ship.getLocation(), slotPos) > Math.max(320f, div.stationSpacing * 0.65f) || crowdedByPeer) {
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        }
                        if (ship.getNumFighterBays() > 0) {
                            if (div.currentIntent == DivisionIntentType.FLY_COMBAT_SPACE_PATROL
                                    && distToEnemy > 2400f
                                    && (div.targetFriendlyDivision == null || div.targetFriendlyDivision.localEnemyStrength < 8f)) {
                                // Keep fighters on defensive CAP unless enemy closes on protected fleet
                                flags.setFlag(AIFlags.CARRIER_FIGHTER_TARGET, FLAG_TTL, focusEnemyShip);
                            } else {
                                directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                            }
                        } else if (distToEnemy < 2200f) {
                            directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        }
                    } else {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case CLOSE_AND_ENGAGE:
                default:
                    DivisionMissionType activeMission = resolveActiveMission(div, now);
                    boolean standoffPosture = (activeMission == DivisionMissionType.STANDOFF_ENGAGEMENT
                            || activeMission == DivisionMissionType.SCOUTING);

                    // Composite Carrier & Escort inner discipline: non-carrier escorts screen the carrier flagship
                    if (div.archetype == DivisionArchetype.CARRIER_GROUP
                            && div.flagship != null
                            && ship != div.flagship
                            && ship.getNumFighterBays() == 0) {
                        flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                        flags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, div.flagship);
                    } else if (standoffPosture && focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        float distToEnemy = distance(ship.getLocation(), focusEnemyShip.getLocation());
                        if (distToEnemy < div.standoffRange * 0.85f) {
                            flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
                            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        } else if (distToEnemy > div.standoffRange * 1.18f || crowdedByPeer) {
                            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        }
                    } else if (crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 2600f) {
                            directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        }
                    } else if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 2600f) {
                        if (activeMission == DivisionMissionType.CLOSE_ACTION) {
                            flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
                        }
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    } else if (div.targetEnemyDivision != null && distance(ship.getLocation(), slotPos) > 950f) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;
            }
        }
    }

    /**
     * Respects explicit Command Window orders (such as CAPTURE / ASSAULT / DEFEND on a control point,
     * RALLY, ESCORT, RETREAT, etc.) so Division AI never pulls ships away from player-ordered control points.
     * In vanilla Starsector, ships without an explicit task have assignment == null or SEARCH_AND_DESTROY.
     */
    private static boolean hasExplicitCommandOrder(ShipAPI ship, DivisionState div, CombatTaskManagerAPI tm) {
        if (tm == null) return false;
        AssignmentInfo info = tm.getAssignmentFor(ship);
        if (info == null || info.getType() == null) return false;
        CombatAssignmentType t = info.getType();
        if (t == CombatAssignmentType.SEARCH_AND_DESTROY) {
            return false;
        }
        // If the ship is assigned to capture/control/assault an objective and its division is already seizing that
        // exact objective, they are aligned; otherwise, let the ship execute its explicit Command Window assignment!
        if (info.getTarget() instanceof BattleObjectiveAPI) {
            if (div.currentIntent == DivisionIntentType.TAKE_AND_HOLD && div.targetObjective == info.getTarget()) {
                return false;
            }
            return true;
        }
        return true;
    }

    private static DivisionState findBestEnemyDivisionFor(
            CombatEngineAPI engine,
            DivisionState myDiv,
            BattleGroupState enemyGroup,
            boolean preferMissilePrey,
            DivisionState previousTarget) {

        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;

        for (DivisionState eDiv : enemyGroup.divisions) {
            if (!eDiv.isCombatEffective()) continue;
            // Strict Fog of War check: cannot target enemy divisions hidden in the Fog of War!
            if (!OtcAI.isDivisionVisibleToSide(engine, myDiv.side, eDiv)) {
                continue;
            }

            Vector2f visCentroid = OtcAI.getVisibleCentroidForSide(engine, myDiv.side, eDiv);
            float visFp = OtcAI.getVisibleFpForSide(engine, myDiv.side, eDiv);
            float dist = distance(myDiv.centroid, visCentroid);
            float score = -dist * 0.35f;

            // Sticky target bonus prevents divisions from flip-flopping between two enemy divisions every few seconds
            if (eDiv == previousTarget) {
                score += 280f;
            }

            // Deprioritize civilian/auxiliary groups when combat divisions exist
            if (eDiv.archetype == DivisionArchetype.SERVICE_GROUP) {
                score -= 450f;
            }

            if (preferMissilePrey) {
                // Missile destroyers/cruisers love enemy Destroyers, Cruisers, Carriers, or high-flux targets
                if (eDiv.archetype == DivisionArchetype.DESTROYER_DIVISION
                        || eDiv.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION
                        || eDiv.archetype == DivisionArchetype.CRUISER_DIVISION
                        || eDiv.archetype.isCarrierArchetype()
                        || eDiv.archetype == DivisionArchetype.BATTLESHIP_DIVISION) {
                    score += 420f;
                }
                score += eDiv.avgFluxLevel * 600f;
            } else {
                score += visFp * 6f;
            }

            if (score > bestScore) {
                bestScore = score;
                best = eDiv;
            }
        }
        return best;
    }

    private static void directShipAndFightersOntoTarget(ShipAPI ship, ShipwideAIFlags flags, ShipAPI focusEnemyShip) {
        if (focusEnemyShip == null || !focusEnemyShip.isAlive() || focusEnemyShip.isHulk()) return;
        flags.setFlag(AIFlags.MANEUVER_TARGET, FLAG_TTL, focusEnemyShip);
        if (ship.getNumFighterBays() > 0) {
            ship.setForceCarrierTarget(focusEnemyShip);
            ship.setForceCarrierTargetTime(1.5f);
            flags.setFlag(AIFlags.CARRIER_FIGHTER_TARGET, 1.5f, focusEnemyShip);
        }
        ShipAPI currTarget = ship.getShipTarget();
        if (currTarget == null || !currTarget.isAlive() || currTarget.isHulk()
                || distance(ship.getLocation(), focusEnemyShip.getLocation()) + 250f < distance(ship.getLocation(), currTarget.getLocation())) {
            ship.setShipTarget(focusEnemyShip);
        }
    }

    private static DivisionState findEndangeredFriendlyDivision(DivisionState myDiv, BattleGroupState myGroup) {
        DivisionState best = null;
        float bestUrgency = 0f;
        for (DivisionState other : myGroup.divisions) {
            if (other == myDiv || !other.isCombatEffective()) continue;
            if (other.localEnemyStrength < 12f) continue;
            // Do not relieve a division that already has local overmatch advantage (>= 0.85)
            if (other.localForceRatio >= 0.85f) continue;
            // Never relieve the division that is on its way to relieve us (mutual-relief ping-pong)
            if (other.currentIntent == DivisionIntentType.REINFORCE_DIVISION && other.targetFriendlyDivision == myDiv) continue;

            // Prevent relief dogpiling: if another friendly division is already relieving 'other', skip it
            boolean alreadyReinforced = false;
            for (DivisionState peer : myGroup.divisions) {
                if (peer != myDiv && peer != other && peer.isCombatEffective()
                        && peer.currentIntent == DivisionIntentType.REINFORCE_DIVISION
                        && peer.targetFriendlyDivision == other) {
                    alreadyReinforced = true;
                    break;
                }
            }
            if (alreadyReinforced) continue;

            float urgency = (1.0f - other.localForceRatio) * 1000f;
            if (other.hasShipNeedingHelp) {
                urgency += 300f;
            }
            if (other.archetype.isCarrierArchetype()
                    || other.archetype == DivisionArchetype.BATTLESHIP_DIVISION) {
                urgency += 200f;
            }

            float dist = distance(myDiv.centroid, other.centroid);
            if (urgency > 350f && dist < 4200f) {
                float net = urgency - dist * 0.12f;
                if (net > bestUrgency) {
                    bestUrgency = net;
                    best = other;
                }
            }
        }
        return best;
    }

    private static DivisionState findHeavyFriendlyDivisionToScreen(DivisionState myDiv, BattleGroupState myGroup) {
        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;
        for (DivisionState other : myGroup.divisions) {
            if (other == myDiv || !other.isCombatEffective()) continue;
            boolean isHeavy = other.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                    || other.archetype.isCarrierArchetype()
                    || other.archetype == DivisionArchetype.CRUISER_DIVISION;
            if (!isHeavy) continue;

            // A Mixed Carrier division already has organic escorts; only assign an extra
            // screening division if it is under local enemy pressure or lost its escorts.
            if (other.archetype == DivisionArchetype.CARRIER_GROUP
                    && other.getAliveMembers().size() >= 2
                    && other.localEnemyStrength < 15f) {
                continue;
            }

            // Cap screening to at most 1 screening division per heavy division to prevent fleet-wide screen dogpiling
            boolean alreadyScreenedByPeer = false;
            for (DivisionState peer : myGroup.divisions) {
                if (peer != myDiv && peer != other && peer.isCombatEffective()
                        && peer.currentIntent == DivisionIntentType.TAKE_SCREENING_STATION
                        && peer.targetFriendlyDivision == other) {
                    alreadyScreenedByPeer = true;
                    break;
                }
            }
            if (alreadyScreenedByPeer) continue;

            // Keep existing screen target sticky to avoid ping-ponging between two heavy divisions
            float stickyBonus = (myDiv.currentIntent == DivisionIntentType.TAKE_SCREENING_STATION
                    && myDiv.targetFriendlyDivision == other) ? 350f : 0f;

            float dist = distance(myDiv.centroid, other.centroid);
            float score = other.totalFpStrength * 10f - dist * 0.3f + stickyBonus;
            if (other.archetype == DivisionArchetype.BATTLESHIP_DIVISION) {
                score += 650f;
            } else if (other.archetype.isCarrierArchetype()) {
                score += 450f;
            }
            if (score > bestScore) {
                bestScore = score;
                best = other;
            }
        }
        return best;
    }

    private static ShipAPI pickFocusEnemyShip(
            CombatEngineAPI engine,
            int observerSide,
            DivisionState enemyDiv,
            Vector2f fromPoint) {
        if (enemyDiv == null) return null;
        List<ShipAPI> enemies = enemyDiv.getAliveMembers();
        ShipAPI best = null;
        float bestScore = -Float.MAX_VALUE;
        for (ShipAPI e : enemies) {
            if (!OtcAI.isShipVisibleToSide(engine, observerSide, e)) continue;
            float d = distance(fromPoint, e.getLocation());
            // Prefer closer targets and high-flux/exposed targets inside the target division
            float score = -d * 0.45f + e.getFluxLevel() * 550f + (1f - e.getHullLevel()) * 400f;
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    /**
     * Checks if any ship in this division has been directly assigned to an objective in CombatTaskManager,
     * or if there is an unclaimed player/task-manager objective assignment in tm.getAllAssignments().
     */
    private static BattleObjectiveAPI findTaskManagerObjectiveForDivision(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            BattleObjectiveAPI previousObjective) {
        if (engine == null) return null;
        if (div.archetype.isCarrierArchetype()
                || div.archetype == DivisionArchetype.BATTLESHIP_DIVISION) {
            return null;
        }
        CombatFleetManagerAPI fm = engine.getFleetManager(div.side);
        boolean divIsAlly = (div.flagship != null && div.flagship.isAlly());
        CombatTaskManagerAPI tm = (fm != null) ? fm.getTaskManager(divIsAlly) : null;
        if (tm == null) return null;

        // 1. Check if any ship in this division is directly assigned to a BattleObjectiveAPI
        for (ShipAPI s : div.getAliveMembers()) {
            AssignmentInfo info = tm.getAssignmentFor(s);
            if (info != null && info.getTarget() instanceof BattleObjectiveAPI) {
                BattleObjectiveAPI obj = (BattleObjectiveAPI) info.getTarget();
                if (!isObjectiveClaimedByOtherDivision(obj, div, myGroup)) {
                    return obj;
                }
            }
        }

        // 2. Check active objective assignments in CombatTaskManager for fast maneuver / destroyer divisions
        if (div.archetype != DivisionArchetype.FRIGATE_DIVISION
                && div.archetype != DivisionArchetype.PHASE_DIVISION
                && div.archetype != DivisionArchetype.DESTROYER_DIVISION
                && div.archetype != DivisionArchetype.MISSILE_DESTROYER_DIVISION) {
            return null;
        }
        List<AssignmentInfo> all = tm.getAllAssignments();
        if (all != null) {
            BattleObjectiveAPI best = null;
            float bestScore = -Float.MAX_VALUE;
            for (AssignmentInfo info : all) {
                if (info == null || !(info.getTarget() instanceof BattleObjectiveAPI)) continue;
                CombatAssignmentType t = info.getType();
                if (t != CombatAssignmentType.CAPTURE
                        && t != CombatAssignmentType.ASSAULT
                        && t != CombatAssignmentType.CONTROL
                        && t != CombatAssignmentType.DEFEND
                        && t != CombatAssignmentType.RECON) {
                    continue;
                }
                BattleObjectiveAPI obj = (BattleObjectiveAPI) info.getTarget();
                if (isObjectiveClaimedByOtherDivision(obj, div, myGroup)) continue;
                float d = distance(div.centroid, obj.getLocation());
                float sticky = (obj == previousObjective) ? 450f : 0f;
                float score = -d + sticky;
                if (score > bestScore) {
                    bestScore = score;
                    best = obj;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static BattleObjectiveAPI findNearestContestObjective(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            BattleObjectiveAPI previousObjective) {
        List<BattleObjectiveAPI> objs = engine.getObjectives();
        if (objs == null || objs.isEmpty()) return null;

        // First pass: prefer uncaptured objectives NOT already claimed by another friendly division
        BattleObjectiveAPI bestUnclaimed = null;
        float bestScore = -Float.MAX_VALUE;
        for (BattleObjectiveAPI obj : objs) {
            if (obj == null || obj.getOwner() == div.side) continue;
            if (isObjectiveClaimedByOtherDivision(obj, div, myGroup)) continue;
            float d = distance(div.centroid, obj.getLocation());
            float sticky = (obj == previousObjective) ? 450f : 0f;
            float score = -d + sticky;
            if (score > bestScore) {
                bestScore = score;
                bestUnclaimed = obj;
            }
        }
        return bestUnclaimed;
    }

    private static boolean isObjectiveClaimedByOtherDivision(
            BattleObjectiveAPI obj,
            DivisionState myDiv,
            BattleGroupState myGroup) {
        if (obj == null || myGroup == null) return false;
        for (DivisionState peer : myGroup.divisions) {
            if (peer != myDiv && peer.isCombatEffective()
                    && peer.currentIntent == DivisionIntentType.TAKE_AND_HOLD
                    && peer.targetObjective == obj) {
                return true;
            }
        }
        return false;
    }

    private static boolean isShipCrowdedByDivisionPeer(ShipAPI ship, List<ShipAPI> alive, float minSpacing) {
        if (ship == null || alive == null || alive.size() <= 1) return false;
        for (ShipAPI peer : alive) {
            if (peer == null || peer == ship || !peer.isAlive()) continue;
            if (distance(ship.getLocation(), peer.getLocation()) < minSpacing) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes a ship's individual formation slot around its division's maneuver station using the division's
     * configured stationSpacing (in units) and applies peer-separation + main-battle-line standoff repulsion
     * so frigates and line ships maintain their doctrinal intra-division spacing instead of balling up.
     */
    private static Vector2f computeShipFormationSlot(
            CombatEngineAPI engine,
            ShipAPI ship,
            int shipIdx,
            List<ShipAPI> alive,
            DivisionState div,
            BattleGroupState myGroup) {

        float spacing = Math.max(150f, div.stationSpacing);
        Vector2f baseCenter = (div.currentIntent == DivisionIntentType.CLOSE_UP)
                ? div.centroid
                : div.stationPoint;

        float fwdX = 0f;
        float fwdY = (div.side == 0) ? 1f : -1f;
        if (div.targetEnemyDivision != null) {
            Vector2f visEnemy = OtcAI.getVisibleCentroidForSide(engine, div.side, div.targetEnemyDivision);
            float dx = visEnemy.x - div.centroid.x;
            float dy = visEnemy.y - div.centroid.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1f) {
                fwdX = dx / len;
                fwdY = dy / len;
            }
        }
        // Lateral axis perpendicular to forward threat vector (line-abreast / echelon spacing)
        float latX = -fwdY;
        float latY = fwdX;

        int rank = (shipIdx + 1) / 2;
        float sideSign = (shipIdx == 0) ? 0f : ((shipIdx % 2 == 1) ? -1f : 1f);
        float latOffset = sideSign * rank * spacing;
        float depthOffset = -rank * (spacing * 0.16f);

        // Preferred Space depth bias (Forward / Middle / Back) relative to the main battle line
        LineStation lineStation = (div.lineStation != null) ? div.lineStation : LineStation.CENTER;
        if (div.currentIntent != DivisionIntentType.HOLD_IN_RESERVE
                && div.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT
                && div.currentIntent != DivisionIntentType.BREAK_OFF_AND_RETIRE) {
            if (lineStation == LineStation.VAN) {
                depthOffset += 220f;
            } else if (lineStation == LineStation.REAR) {
                depthOffset -= 320f;
            }
        }

        float slotX = baseCenter.x + latX * latOffset + fwdX * depthOffset;
        float slotY = baseCenter.y + latY * latOffset + fwdY * depthOffset;

        // Peer repulsion: if another ship in the same division is closer than stationSpacing * 0.90f, push apart
        for (ShipAPI peer : alive) {
            if (peer == null || peer == ship || !peer.isAlive()) continue;
            float pdx = ship.getLocation().x - peer.getLocation().x;
            float pdy = ship.getLocation().y - peer.getLocation().y;
            float pDist = (float) Math.sqrt(pdx * pdx + pdy * pdy);
            if (pDist > 1f && pDist < spacing * 0.90f) {
                float push = (spacing - pDist) * 0.75f;
                slotX += (pdx / pDist) * push;
                slotY += (pdy / pDist) * push;
            }
        }

        // Inter-division spacing repulsion: keep friendly divisions separated by their configured divisionInterval
        if (myGroup != null) {
            float desiredInter = Math.max(400f, div.divisionInterval);
            for (DivisionState peerDiv : myGroup.divisions) {
                if (peerDiv == null || peerDiv == div || !peerDiv.isCombatEffective()) continue;
                float minInter = Math.max(desiredInter, peerDiv.divisionInterval) * 0.85f;
                float idx = div.centroid.x - peerDiv.centroid.x;
                float idy = div.centroid.y - peerDiv.centroid.y;
                float iDist = (float) Math.sqrt(idx * idx + idy * idy);
                if (iDist > 1f && iDist < minInter) {
                    float push = (minInter - iDist) * 0.65f;
                    slotX += (idx / iDist) * push;
                    slotY += (idy / iDist) * push;
                }
            }
        }

        // Anti-balling with main battle line: keep Frigate & Phase divisions spaced clear of heavy main-line centroid
        if ((div.archetype == DivisionArchetype.FRIGATE_DIVISION
                || div.archetype == DivisionArchetype.PHASE_DIVISION)
                && myGroup != null
                && myGroup.mainEffortDivision != null
                && myGroup.mainEffortDivision != div
                && myGroup.mainEffortDivision.isCombatEffective()) {
            Vector2f mainPos = myGroup.mainEffortDivision.centroid;
            float mdx = ship.getLocation().x - mainPos.x;
            float mdy = ship.getLocation().y - mainPos.y;
            float mDist = (float) Math.sqrt(mdx * mdx + mdy * mdy);
            float minClearance = Math.max(550f, Math.max(div.divisionInterval * 0.80f, div.standoffRange * 0.75f));
            if (mDist > 1f && mDist < minClearance) {
                float push = (minClearance - mDist) * 0.85f;
                slotX += (mdx / mDist) * push;
                slotY += (mdy / mDist) * push;
            }
        }

        return new Vector2f(slotX, slotY);
    }

    private static void computeStandoffStation(
            CombatEngineAPI engine,
            DivisionState div,
            DivisionState enemyDiv,
            Vector2f outStation) {
        if (enemyDiv == null) {
            outStation.set(div.centroid);
            return;
        }
        Vector2f visEnemyPos = OtcAI.getVisibleCentroidForSide(engine, div.side, enemyDiv);
        float dx = div.centroid.x - visEnemyPos.x;
        float dy = div.centroid.y - visEnemyPos.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float standoff = Math.max(400f, div.standoffRange);
        LineStation lineStation = (div.lineStation != null) ? div.lineStation : LineStation.CENTER;
        if (lineStation == LineStation.VAN) {
            standoff = Math.max(350f, standoff * 0.85f);
        } else if (lineStation == LineStation.REAR) {
            standoff = standoff * 1.18f;
        }
        if (len > 1f) {
            outStation.set(
                    visEnemyPos.x + (dx / len) * standoff,
                    visEnemyPos.y + (dy / len) * standoff);
        } else {
            float backY = (div.side == 0) ? -standoff : standoff;
            outStation.set(visEnemyPos.x, visEnemyPos.y + backY);
        }
    }

    private static void computeScreeningStation(
            CombatEngineAPI engine,
            DivisionState screenDiv,
            DivisionState protectedDiv,
            DivisionState enemyDiv,
            Vector2f outStation,
            int side) {
        if (protectedDiv == null) return;
        float standoff = (screenDiv != null) ? Math.max(550f, screenDiv.standoffRange) : 750f;
        float flankSign = (screenDiv != null && screenDiv.divisionNumber % 2 == 0) ? -1f : 1f;
        float lateralOffset = (screenDiv != null) ? Math.max(320f, screenDiv.stationSpacing * 0.85f) * flankSign : 0f;

        if (enemyDiv != null) {
            Vector2f visEnemyPos = OtcAI.getVisibleCentroidForSide(engine, side, enemyDiv);
            float dx = visEnemyPos.x - protectedDiv.centroid.x;
            float dy = visEnemyPos.y - protectedDiv.centroid.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1f) {
                float fwdX = dx / len;
                float fwdY = dy / len;
                float latX = -fwdY;
                float latY = fwdX;
                outStation.set(
                        protectedDiv.centroid.x + fwdX * (standoff * 0.80f) + latX * lateralOffset,
                        protectedDiv.centroid.y + fwdY * (standoff * 0.80f) + latY * lateralOffset);
                return;
            }
        }
        float forwardY = (side == 0) ? standoff * 0.80f : -standoff * 0.80f;
        outStation.set(protectedDiv.centroid.x + lateralOffset, protectedDiv.centroid.y + forwardY);
    }

    private static void computeFlankStation(
            CombatEngineAPI engine,
            DivisionState flankerDiv,
            DivisionState enemyDiv,
            Vector2f outStation,
            BattleGroupState myGroup) {
        Vector2f visEnemyPos = OtcAI.getVisibleCentroidForSide(engine, flankerDiv.side, enemyDiv);
        float flankRadius = Math.max(1050f, flankerDiv.standoffRange + flankerDiv.stationSpacing * 0.65f);

        // Pincer: a OTC-assigned flank side is measured on the battlegroup's attack axis (fleet centre -> target),
        // so two flankers on opposite sides really do envelop the target from left and right.
        float assignedSide = assignedFlankSide(flankerDiv, enemyDiv, myGroup);
        if (assignedSide != 0f) {
            float ax = visEnemyPos.x - myGroup.fleetCentroid.x;
            float ay = visEnemyPos.y - myGroup.fleetCentroid.y;
            float aLen = (float) Math.sqrt(ax * ax + ay * ay);
            if (aLen > 1f) {
                float fwdX = ax / aLen;
                float fwdY = ay / aLen;
                float perpX = -fwdY;
                float perpY = fwdX;
                // Slightly past the target's line so the flank gets at its side rather than its face
                outStation.set(
                        visEnemyPos.x + perpX * flankRadius * assignedSide + fwdX * flankRadius * 0.2f,
                        visEnemyPos.y + perpY * flankRadius * assignedSide + fwdY * flankRadius * 0.2f);
                return;
            }
        }

        float dx = visEnemyPos.x - flankerDiv.centroid.x;
        float dy = visEnemyPos.y - flankerDiv.centroid.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= 1f) {
            outStation.set(visEnemyPos);
            return;
        }
        // Perpendicular offset to swing wide around the enemy division's flank, scaled by doctrinal standoff & spacing
        float perpX = -dy / len;
        float perpY = dx / len;
        float sideSign = (flankerDiv.divisionNumber % 2 == 1) ? 1f : -1f;
        outStation.set(
                visEnemyPos.x + perpX * flankRadius * sideSign,
                visEnemyPos.y + perpY * flankRadius * sideSign);
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
