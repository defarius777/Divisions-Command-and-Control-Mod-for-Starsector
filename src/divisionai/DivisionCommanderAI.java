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
import divisionai.DivisionModel.BattlegroupState;
import divisionai.DivisionModel.BgTaskType;
import divisionai.DivisionModel.BgTasking;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionIntentType;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionModel.StrikePlan;
import divisionai.PermanentDivisionManager.BattlegroupAuthority;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.OrchestrationPlay;
import divisionai.PermanentDivisionManager.PreferredSpace;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

/**
 * Mid-level Division Commander AI:
 *  - Evaluates each division's capabilities, local overmatch, cohesion, flux pressure,
 *    Battlegroup Commander phase, and the division's Battlegroup tasking.
 *  - Selects concrete single-division and cross-division tactics:
 *      * Harassing torpedo attack ("Destroyer Div 1" -> "Enemy Destroyer Div 4")
 *      * Time-on-target coordinated strike (BG strike plans: stage, then commit together)
 *      * Strike-then-withdraw to reserve
 *      * Monotype division screening another division ("Destroyer Div 1" screening "Battle Div 2")
 *      * Relief of an endangered friendly division
 *      * Flux/hull rotation to reserve
 *      * Bounded pursuit & cohesion keeping
 *  - Honors Battlegroup taskings according to the BG's authority (Hybrid, Central Planning,
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
            BattlegroupState myBg,
            BattlegroupState enemyBg,
            float now) {

        for (DivisionState div : myBg.divisions) {
            if (!div.isCombatEffective()) continue;

            // Re-evaluate on the commander's decision cadence, but hold a committed intent unless something urgent happens
            boolean cadenceDue = (now - div.lastIntentChangeTime) >= div.commander.decisionCadenceSeconds;
            boolean committed = now < div.intentCommitUntil;
            if (cadenceDue && (!committed || hasUrgentTrigger(engine, div, myBg, now))) {
                DivisionIntentType prevIntent = div.currentIntent;
                DivisionState prevEnemy = div.targetEnemyDivision;
                DivisionState prevFriendly = div.targetFriendlyDivision;
                BattleObjectiveAPI prevObjective = div.targetObjective;

                selectDivisionIntent(engine, div, myBg, enemyBg, now);
                div.lastIntentChangeTime = now;
                div.appliedBgTaskVersion = div.bgTask.version;

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
            refreshManeuverAnchor(engine, div, myBg, now);

            // Apply per-tick (0.5s cadence) ShipwideAIFlags nudges to division members
            applyDivisionFlagsToShips(engine, div, myBg, now);
        }
    }

    /** Opening Mission during the opening window (30s-60s), Main Mission for the rest of the battle. */
    public static DivisionMissionType resolveActiveMission(DivisionState div, float now) {
        boolean inOpeningWindow = isInOpeningWindow(div, now);
        DivisionMissionType activeMission = inOpeningWindow ? div.openingMission : div.mainMission;
        if (activeMission == null) {
            if (div.archetype.isCarrierArchetype()) {
                activeMission = inOpeningWindow
                        ? DivisionMissionType.CARRIER_RECON_STRIKE_CRAFT
                        : DivisionMissionType.CARRIER_DIVISION_COORDINATED_STRIKE;
            } else {
                activeMission = inOpeningWindow
                        ? DivisionMissionType.RECONNAISSANCE
                        : DivisionMissionType.DOMINATE_SPACE_INTO_BRAWL;
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
            case HOLD_AND_COHERE:
            case HOLD_IN_RESERVE:
            case ROTATE_TO_RESERVE:
            case STRIKE_AND_WITHDRAW:
            case STAGE_FOR_STRIKE:
            case PURSUE_BROKEN_ENEMY:
                return false;
            default:
                return true;
        }
    }

    /** Events that break intent commitment and force an immediate re-evaluation. */
    private static boolean hasUrgentTrigger(CombatEngineAPI engine, DivisionState div, BattlegroupState myBg, float now) {
        if (div.bgTask.version != div.appliedBgTaskVersion) return true;
        if (isStrikeRunOver(div, now)) return true;
        if (isUnderHighFluxPressure(div, myBg)
                && div.currentIntent != DivisionIntentType.ROTATE_TO_RESERVE
                && div.currentIntent != DivisionIntentType.STRIKE_AND_WITHDRAW) {
            return true;
        }
        if (myBg.currentPhase == BattlePhase.ORDERED_WITHDRAWAL
                && div.currentIntent != DivisionIntentType.ROTATE_TO_RESERVE) {
            return true;
        }
        if (div.targetEnemyDivision != null
                && (!div.targetEnemyDivision.isCombatEffective()
                || !BattlegroupCommanderAI.isDivisionVisibleToSide(engine, div.side, div.targetEnemyDivision))) {
            return true;
        }
        if (div.targetFriendlyDivision != null && !div.targetFriendlyDivision.isCombatEffective()) return true;
        if (div.currentIntent == DivisionIntentType.SEIZE_OBJECTIVE
                && div.targetObjective != null && div.targetObjective.getOwner() == div.side) {
            return true;
        }
        if (div.currentIntent == DivisionIntentType.RELIEVE_FRIENDLY_DIVISION
                && div.targetFriendlyDivision != null && div.targetFriendlyDivision.localOvermatchRatio >= 1.0f) {
            return true;
        }
        return false;
    }

    /** A torpedo run finished its window, ran dry, or built flux; or a flank maneuver has run out of steam. */
    private static boolean isStrikeRunOver(DivisionState div, float now) {
        boolean inTorpedoStrike = (div.currentIntent == DivisionIntentType.HARASS_TORPEDO_ATTACK
                || div.currentIntent == DivisionIntentType.COORDINATED_TORPEDO_STRIKE);
        boolean inFlankManeuver = (div.currentIntent == DivisionIntentType.FLANK_ENEMY_DIVISION);

        boolean torpedoRunComplete = inTorpedoStrike && ((now - div.strikeCommittedTimestamp) >= STRIKE_WINDOW_DURATION
                || div.avgFluxLevel >= div.commander.rotationFluxThreshold * 0.90f
                || (div.hasTorpedoStrikeCapability && div.usableStrikeAmmoCount <= 0));
        boolean flankWithdrawNeeded = inFlankManeuver
                && (div.avgFluxLevel >= div.commander.rotationFluxThreshold * 0.88f
                || ((now - div.strikeCommittedTimestamp) >= (STRIKE_WINDOW_DURATION * 1.5f) && div.avgFluxLevel >= 0.45f));
        return torpedoRunComplete || flankWithdrawNeeded;
    }

    /** Requires actual flux buildup (or critical damage under heavy local overmatch) so fresh divisions never retreat. */
    private static boolean isUnderHighFluxPressure(DivisionState div, BattlegroupState myBg) {
        boolean highFluxPressure = div.avgFluxLevel >= div.commander.rotationFluxThreshold
                || (div.maxMemberFluxLevel >= 0.78f && div.localEnemyStrength > 12f && div.localOvermatchRatio < 0.65f)
                || (div.avgFluxLevel >= 0.45f && div.localEnemyStrength > 14f && div.localOvermatchRatio < 0.50f && div.commander.aggressionBias < 1.35f);
        boolean canRotateOut = (div != myBg.mainEffortDivision || div.avgFluxLevel >= 0.72f || myBg.divisions.size() >= 3);
        return highFluxPressure && canRotateOut;
    }

    private static boolean isEndangered(DivisionState div) {
        return div.localEnemyStrength >= 12f && div.localOvermatchRatio < 0.85f;
    }

    /**
     * The enemy division this division should fight: the BG's allocated target when one is active
     * (binding under Hybrid / Central Planning / Target Coordination; preferred under Mission-Type Tactics,
     * where a much closer enemy on top of the division takes precedence), otherwise its own pick.
     */
    private static DivisionState resolveBgTarget(
            CombatEngineAPI engine,
            DivisionState div,
            BgTasking task,
            boolean taskActive,
            BattlegroupAuthority auth,
            DivisionState ownPick) {
        if (!taskActive || task.targetEnemy == null) return ownPick;
        DivisionState bgTarget = task.targetEnemy;
        if (!bgTarget.isCombatEffective() || !BattlegroupCommanderAI.isDivisionVisibleToSide(engine, div.side, bgTarget)) {
            return ownPick;
        }
        if (auth.isBinding() || ownPick == null || ownPick == bgTarget) return bgTarget;
        float dOwn = distance(div.centroid, BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, ownPick));
        float dBg = distance(div.centroid, BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, bgTarget));
        return (dOwn < 1400f && dBg > 2600f) ? ownPick : bgTarget;
    }

    private static void selectDivisionIntent(
            CombatEngineAPI engine,
            DivisionState div,
            BattlegroupState myBg,
            BattlegroupState enemyBg,
            float now) {

        DivisionState prevEnemyTarget = div.targetEnemyDivision;
        BattleObjectiveAPI prevObjective = div.targetObjective;
        DivisionIntentType prevIntent = div.currentIntent;

        div.targetEnemyDivision = null;
        div.targetFriendlyDivision = null;
        div.targetObjective = null;
        String targetLabel = "";
        String reason = "";

        BattlegroupAuthority auth = myBg.authority;
        BgTasking task = div.bgTask;
        boolean taskActive = task.isActive(now);

        DivisionState ownNearestEnemyDiv = findBestEnemyDivisionFor(engine, div, enemyBg, false, prevEnemyTarget);
        DivisionState nearestEnemyDiv = resolveBgTarget(engine, div, task, taskActive, auth, ownNearestEnemyDiv);
        DivisionState torpedoTargetEnemyDiv = (nearestEnemyDiv != ownNearestEnemyDiv && auth.isBinding())
                ? nearestEnemyDiv
                : findBestEnemyDivisionFor(engine, div, enemyBg, true, prevEnemyTarget);
        DivisionState endangeredFriendlyDiv = findEndangeredFriendlyDivision(div, myBg);
        DivisionState heavyFriendlyToScreen = findHeavyFriendlyDivisionToScreen(div, myBg);
        BattleObjectiveAPI commandAssignedObj = findTaskManagerObjectiveForDivision(engine, div, myBg, prevObjective);
        BattleObjectiveAPI uncapturedObj = (commandAssignedObj != null)
                ? commandAssignedObj
                : findNearestContestObjective(engine, div, myBg, prevObjective);

        float distToVisibleEnemy = (nearestEnemyDiv != null)
                ? distance(div.centroid, BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv))
                : Float.MAX_VALUE;
        float distToTorpedoEnemy = (torpedoTargetEnemyDiv != null)
                ? distance(div.centroid, BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, torpedoTargetEnemyDiv))
                : Float.MAX_VALUE;

        boolean inTorpedoStrike = (prevIntent == DivisionIntentType.HARASS_TORPEDO_ATTACK
                || prevIntent == DivisionIntentType.COORDINATED_TORPEDO_STRIKE);
        boolean inFlankManeuver = (prevIntent == DivisionIntentType.FLANK_ENEMY_DIVISION);

        // 1. Torpedo strike window finished / torpedoes expended / flux built up -> Strike-then-withdraw to reserve
        if (isStrikeRunOver(div, now)) {
            div.currentIntent = DivisionIntentType.STRIKE_AND_WITHDRAW;
            div.targetEnemyDivision = nearestEnemyDiv;
            div.maneuverAnchor.set(myBg.reserveAnchor);
            div.lastStrikeWithdrawTimestamp = now;
            targetLabel = nearestEnemyDiv != null ? nearestEnemyDiv.displayName : "contact zone";
            reason = String.format("avg flux %.0f%%", div.avgFluxLevel * 100f);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 2. High flux / damaged division -> Rotate to reserve behind a friendly division.
        //    Once rotating, stay out until flux has genuinely bled off instead of bouncing at the threshold.
        boolean stillRotating = prevIntent == DivisionIntentType.ROTATE_TO_RESERVE
                && div.avgFluxLevel >= div.commander.rotationFluxThreshold * 0.55f;
        if (isUnderHighFluxPressure(div, myBg) || stillRotating || myBg.currentPhase == BattlePhase.ORDERED_WITHDRAWAL) {

            DivisionState anchorFriend = (myBg.mainEffortDivision != null && myBg.mainEffortDivision != div)
                    ? myBg.mainEffortDivision
                    : heavyFriendlyToScreen;

            div.currentIntent = DivisionIntentType.ROTATE_TO_RESERVE;
            div.targetFriendlyDivision = anchorFriend;
            if (anchorFriend != null) {
                float retreatDirY = (div.side == 0) ? -1f : 1f;
                div.maneuverAnchor.set(anchorFriend.centroid.x, anchorFriend.centroid.y + retreatDirY * 1100f);
                targetLabel = anchorFriend.displayName;
            } else {
                div.maneuverAnchor.set(myBg.reserveAnchor);
                targetLabel = "Battlegroup Reserve";
            }
            reason = String.format("flux %.0f%%, local ratio %.2f", div.avgFluxLevel * 100f, div.localOvermatchRatio);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        DivisionMissionType activeMission = resolveActiveMission(div, now);
        String missionPhaseTag = isInOpeningWindow(div, now) ? "Opening: " : "Main: ";
        boolean isHeavyLineOrCarrier = (div.archetype == DivisionArchetype.BATTLE_DIVISION
                || div.archetype.isCarrierArchetype());

        // 3. Explicit Command Window objective order (player orders outrank the Battlegroup Commander)
        if (commandAssignedObj != null && !isHeavyLineOrCarrier && div.localEnemyStrength < 18f) {
            div.currentIntent = DivisionIntentType.SEIZE_OBJECTIVE;
            div.targetObjective = commandAssignedObj;
            div.maneuverAnchor.set(commandAssignedObj.getLocation());
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, commandAssignedObj.getDisplayName(), "executing command order", now);
            return;
        }

        // 4. Battlegroup Commander tasking
        if (taskActive && applyBgTasking(engine, div, myBg, task, auth, activeMission, prevIntent, now)) {
            return;
        }

        // 5. Mission-driven objective seizure (the Main Effort stays on its fixing task instead)
        boolean anchoredByBg = taskActive && task.type == BgTaskType.FIX_PRIMARY && auth.allowsPositioning();
        if (!anchoredByBg
                && uncapturedObj != null
                && !isHeavyLineOrCarrier
                && activeMission != DivisionMissionType.BATTLEGROUP_RESERVE
                && div.localEnemyStrength < 14f
                && (activeMission == DivisionMissionType.RECONNAISSANCE
                || nearestEnemyDiv == null
                || (activeMission == DivisionMissionType.DOMINATE_SPACE_INTO_BRAWL && distToVisibleEnemy > 2400f)
                || (div.isFastFlanker && distance(div.centroid, uncapturedObj.getLocation()) + 400f < distToVisibleEnemy))) {
            div.currentIntent = DivisionIntentType.SEIZE_OBJECTIVE;
            div.targetObjective = uncapturedObj;
            div.maneuverAnchor.set(uncapturedObj.getLocation());
            targetLabel = uncapturedObj.getDisplayName();
            reason = missionPhaseTag + activeMission.shortName;
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 6. Battlegroup Reserve mission or BG Reserve designation, when the BG is not orchestrating the reserve itself
        //    (with the Reserve & Exploit play the BG holds and commits reserves through HOLD_RESERVE / RELIEVE taskings).
        boolean bgOrchestratesReserve = myBg.isPlayEnabled(OrchestrationPlay.RESERVE_AND_EXPLOIT) && auth.allowsRetasking();
        if (!bgOrchestratesReserve) {
            boolean calledUpByBg = (endangeredFriendlyDiv != null)
                    || (myBg.currentPhase == BattlePhase.STABILIZE_AND_RELIEVE && nearestEnemyDiv != null)
                    || (myBg.currentPhase == BattlePhase.EXPLOIT_BREAKTHROUGH && nearestEnemyDiv != null)
                    || (myBg.currentPhase == BattlePhase.COORDINATED_STRIKE && nearestEnemyDiv != null && div.hasTorpedoStrikeCapability);
            if ((activeMission == DivisionMissionType.BATTLEGROUP_RESERVE || div.isDesignatedReserve) && !calledUpByBg) {
                div.currentIntent = DivisionIntentType.HOLD_IN_RESERVE;
                div.maneuverAnchor.set(myBg.reserveAnchor);
                reason = (activeMission == DivisionMissionType.BATTLEGROUP_RESERVE)
                        ? (missionPhaseTag + "Battlegroup Reserve (awaiting BG call-up)")
                        : "awaiting BG commitment";
                div.lastIntentChangeTime = now;
                DivisionCombatLog.logDivisionIntent(engine, div, "", reason, now);
                return;
            }
        }

        // 7. Local mutual support: relieve an endangered neighbour on the division's own initiative.
        //    Only when the BG is not assigning relief centrally (or under Mission-Type Tactics for close neighbours),
        //    never by carriers, and never by a division that is itself in trouble.
        boolean localReliefAllowed = !bgOrchestratesReserve || !auth.isBinding();
        if (localReliefAllowed
                && endangeredFriendlyDiv != null
                && !div.archetype.isCarrierArchetype()
                && div.avgFluxLevel < 0.58f
                && !isEndangered(div)
                && (!bgOrchestratesReserve || distance(div.centroid, endangeredFriendlyDiv.centroid) < 2500f)
                && (activeMission == DivisionMissionType.BATTLEGROUP_RESERVE
                || div.isDesignatedReserve
                || div.isFastFlanker
                || div.localEnemyStrength < 15f)) {

            div.currentIntent = DivisionIntentType.RELIEVE_FRIENDLY_DIVISION;
            div.targetFriendlyDivision = endangeredFriendlyDiv;
            div.maneuverAnchor.set(endangeredFriendlyDiv.centroid);
            targetLabel = endangeredFriendlyDiv.displayName;
            reason = (activeMission == DivisionMissionType.BATTLEGROUP_RESERVE || div.isDesignatedReserve)
                    ? String.format("BG Reserve called up to relieve (ratio %.2f)", endangeredFriendlyDiv.localOvermatchRatio)
                    : String.format("under heavy pressure, ratio %.2f", endangeredFriendlyDiv.localOvermatchRatio);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 8. Cohesion check: if division ships have scattered too far apart, pause and reform before the mission
        if (div.cohesionRatio > 1.48f && div.getAliveMembers().size() >= 2) {
            div.currentIntent = DivisionIntentType.HOLD_AND_COHERE;
            div.maneuverAnchor.set(div.centroid);
            reason = String.format("spread %.0fu > %.0fu leash", div.spreadRadius, div.desiredCohesionRadius);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, "", reason, now);
            return;
        }

        // 9. Execute configured Opening Mission / Main Mission priority (including Carrier Missions a..f)!
        boolean strikeCooldownElapsed = (now - div.lastStrikeWithdrawTimestamp) >= 12.0f;
        boolean soloStrikeAllowed = isSoloStrikeAllowed(myBg, distToTorpedoEnemy);

        if (activeMission.isCarrierMission) {
            DivisionState carrierTargetDiv = nearestEnemyDiv;
            if (activeMission == DivisionMissionType.CARRIER_MULTI_DIVISION_STRIKE
                    && myBg.primaryThreatEnemyDivision != null
                    && BattlegroupCommanderAI.isDivisionVisibleToSide(engine, div.side, myBg.primaryThreatEnemyDivision)) {
                carrierTargetDiv = myBg.primaryThreatEnemyDivision;
            } else if (activeMission == DivisionMissionType.CARRIER_PROTECT_FLEET
                    && endangeredFriendlyDiv != null) {
                DivisionState threatToFriend = findBestEnemyDivisionFor(engine, endangeredFriendlyDiv, enemyBg, false, div.targetEnemyDivision);
                if (threatToFriend != null) {
                    carrierTargetDiv = threatToFriend;
                }
            }

            switch (activeMission) {
                case CARRIER_RECON_STRIKE_CRAFT:
                    div.currentIntent = DivisionIntentType.CARRIER_STRIKE_CRAFT_RECON;
                    div.targetEnemyDivision = carrierTargetDiv;
                    if (carrierTargetDiv != null) {
                        computeStandoffAnchor(engine, div, carrierTargetDiv, div.maneuverAnchor);
                        targetLabel = carrierTargetDiv.displayName;
                    } else if (uncapturedObj != null) {
                        div.targetObjective = uncapturedObj;
                        div.maneuverAnchor.set(div.centroid.x * 0.7f, div.centroid.y + (div.side == 0 ? 700f : -700f));
                        targetLabel = uncapturedObj.getDisplayName();
                    } else {
                        div.maneuverAnchor.set(myBg.reserveAnchor);
                    }
                    reason = missionPhaseTag + activeMission.shortName;
                    div.lastIntentChangeTime = now;
                    DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                    return;

                case CARRIER_VOID_SUPERIORITY:
                    div.currentIntent = DivisionIntentType.CARRIER_VOID_SUPERIORITY_CAP;
                    div.targetEnemyDivision = carrierTargetDiv;
                    if (carrierTargetDiv != null) {
                        computeStandoffAnchor(engine, div, carrierTargetDiv, div.maneuverAnchor);
                        targetLabel = carrierTargetDiv.displayName;
                    } else {
                        div.maneuverAnchor.set(myBg.reserveAnchor);
                    }
                    reason = missionPhaseTag + activeMission.shortName;
                    div.lastIntentChangeTime = now;
                    DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                    return;

                case CARRIER_PROTECT_FLEET:
                    div.currentIntent = DivisionIntentType.CARRIER_PROTECT_FLEET_CAP;
                    DivisionState protectedFriendly = (endangeredFriendlyDiv != null)
                            ? endangeredFriendlyDiv
                            : ((heavyFriendlyToScreen != null) ? heavyFriendlyToScreen : myBg.mainEffortDivision);
                    div.targetFriendlyDivision = protectedFriendly;
                    div.targetEnemyDivision = carrierTargetDiv;
                    if (protectedFriendly != null && protectedFriendly != div) {
                        computeProtectAnchor(div, protectedFriendly, div.maneuverAnchor);
                        targetLabel = protectedFriendly.displayName;
                    } else if (carrierTargetDiv != null) {
                        computeStandoffAnchor(engine, div, carrierTargetDiv, div.maneuverAnchor);
                        targetLabel = carrierTargetDiv.displayName;
                    } else {
                        div.maneuverAnchor.set(myBg.reserveAnchor);
                    }
                    reason = missionPhaseTag + activeMission.shortName;
                    div.lastIntentChangeTime = now;
                    DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                    return;

                case CARRIER_SUCCESSIVE_STRIKES:
                    div.currentIntent = DivisionIntentType.CARRIER_STRIKE_AT_WILL;
                    div.targetEnemyDivision = carrierTargetDiv;
                    if (carrierTargetDiv != null) {
                        computeStandoffAnchor(engine, div, carrierTargetDiv, div.maneuverAnchor);
                        targetLabel = carrierTargetDiv.displayName;
                    } else {
                        div.maneuverAnchor.set(myBg.reserveAnchor);
                    }
                    reason = missionPhaseTag + activeMission.shortName;
                    div.lastIntentChangeTime = now;
                    DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                    return;

                case CARRIER_DIVISION_COORDINATED_STRIKE:
                    div.currentIntent = DivisionIntentType.CARRIER_DIVISION_STRIKE;
                    div.targetEnemyDivision = carrierTargetDiv;
                    if (carrierTargetDiv != null) {
                        computeStandoffAnchor(engine, div, carrierTargetDiv, div.maneuverAnchor);
                        targetLabel = carrierTargetDiv.displayName;
                    } else {
                        div.maneuverAnchor.set(myBg.reserveAnchor);
                    }
                    reason = missionPhaseTag + activeMission.shortName;
                    div.lastIntentChangeTime = now;
                    DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                    return;

                case CARRIER_MULTI_DIVISION_STRIKE:
                    div.currentIntent = DivisionIntentType.CARRIER_MULTI_DIV_STRIKE;
                    div.targetEnemyDivision = carrierTargetDiv;
                    if (carrierTargetDiv != null) {
                        computeStandoffAnchor(engine, div, carrierTargetDiv, div.maneuverAnchor);
                        targetLabel = carrierTargetDiv.displayName;
                    } else {
                        div.maneuverAnchor.set(myBg.reserveAnchor);
                    }
                    String syncPartner = (myBg.mainEffortDivision != null && myBg.mainEffortDivision != div)
                            ? (" synced w/ \"" + myBg.mainEffortDivision.displayName + "\"")
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
            div.currentIntent = DivisionIntentType.SCREEN_FRIENDLY_DIVISION;
            div.targetFriendlyDivision = heavyFriendlyToScreen;
            div.targetEnemyDivision = nearestEnemyDiv;
            computeScreenAnchor(engine, div, heavyFriendlyToScreen, nearestEnemyDiv, div.maneuverAnchor, div.side);
            targetLabel = heavyFriendlyToScreen.displayName;
            reason = missionPhaseTag + activeMission.shortName;
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        if (activeMission == DivisionMissionType.OUTFLANK_AND_DESTROY && nearestEnemyDiv != null) {
            if (strikeCooldownElapsed
                    && soloStrikeAllowed
                    && div.hasTorpedoStrikeCapability
                    && div.usableStrikeAmmoCount > 0
                    && div.avgFluxLevel < 0.55f
                    && torpedoTargetEnemyDiv != null
                    && distToTorpedoEnemy <= 4600f) {
                div.currentIntent = DivisionIntentType.HARASS_TORPEDO_ATTACK;
                Vector2f visTargetPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, torpedoTargetEnemyDiv);
                div.targetEnemyDivision = torpedoTargetEnemyDiv;
                div.maneuverAnchor.set(visTargetPos);
                if (!inTorpedoStrike) {
                    div.strikeCommittedTimestamp = now;
                }
                targetLabel = torpedoTargetEnemyDiv.displayName;
                reason = String.format("%s%s (%d salvos)", missionPhaseTag, activeMission.shortName, div.usableStrikeAmmoCount);
                div.lastIntentChangeTime = now;
                DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                return;
            }
            if (strikeCooldownElapsed && div.avgFluxLevel < 0.58f) {
                div.currentIntent = DivisionIntentType.FLANK_ENEMY_DIVISION;
                div.targetEnemyDivision = nearestEnemyDiv;
                computeFlankAnchor(engine, div, nearestEnemyDiv, div.maneuverAnchor, myBg);
                if (!inFlankManeuver) {
                    div.strikeCommittedTimestamp = now;
                }
                targetLabel = nearestEnemyDiv.displayName;
                reason = missionPhaseTag + activeMission.shortName + flankSideTag(div, nearestEnemyDiv, myBg);
                div.lastIntentChangeTime = now;
                DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                return;
            }
        }

        if ((activeMission == DivisionMissionType.STANDOFF_AT_RANGE
                || activeMission == DivisionMissionType.RECONNAISSANCE)
                && nearestEnemyDiv != null) {
            div.currentIntent = DivisionIntentType.ADVANCE_ENGAGE_LINE;
            div.targetEnemyDivision = nearestEnemyDiv;
            computeStandoffAnchor(engine, div, nearestEnemyDiv, div.maneuverAnchor);
            targetLabel = nearestEnemyDiv.displayName;
            reason = String.format("%s%s (standoff %.0fu)", missionPhaseTag, activeMission.shortName, div.lineStandoffDistance);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        if (activeMission == DivisionMissionType.DOMINATE_SPACE_INTO_BRAWL && nearestEnemyDiv != null) {
            Vector2f visEnemyPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv);
            div.targetEnemyDivision = nearestEnemyDiv;
            div.maneuverAnchor.set(visEnemyPos);
            targetLabel = nearestEnemyDiv.displayName;
            if (div.localOvermatchRatio >= 0.88f && distToVisibleEnemy < 3400f) {
                div.currentIntent = DivisionIntentType.PRESS_OVERMATCH;
            } else {
                div.currentIntent = DivisionIntentType.ADVANCE_ENGAGE_LINE;
            }
            reason = missionPhaseTag + activeMission.shortName;
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 10. Fallback Monotype Screening when Capital/Carrier needs screen
        if (!div.archetype.isComposite
                && div.isScreenCapable
                && !div.hasTorpedoStrikeCapability
                && heavyFriendlyToScreen != null) {

            float distToHeavy = distance(div.centroid, heavyFriendlyToScreen.centroid);
            boolean isCapitalOrCarrier = heavyFriendlyToScreen.archetype.isCarrierArchetype()
                    || heavyFriendlyToScreen.archetype == DivisionArchetype.BATTLE_DIVISION;
            boolean cruiserNeedsScreen = !div.isFastFlanker
                    || nearestEnemyDiv == null
                    || (heavyFriendlyToScreen.localEnemyStrength > 14f && heavyFriendlyToScreen.localOvermatchRatio < 0.90f);
            boolean heavyNeedsScreen = isCapitalOrCarrier
                    || (cruiserNeedsScreen && (heavyFriendlyToScreen.localEnemyStrength > 10f || distToHeavy < 2400f));

            if (heavyNeedsScreen) {
                div.currentIntent = DivisionIntentType.SCREEN_FRIENDLY_DIVISION;
                div.targetFriendlyDivision = heavyFriendlyToScreen;
                div.targetEnemyDivision = nearestEnemyDiv;
                computeScreenAnchor(engine, div, heavyFriendlyToScreen, nearestEnemyDiv, div.maneuverAnchor, div.side);
                targetLabel = heavyFriendlyToScreen.displayName;
                reason = "forward PD & anti-flank screen";
                div.lastIntentChangeTime = now;
                DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                return;
            }
        }

        // 11. Torpedo / Strike Division: Harassing torpedo attack or Time-on-Target strike (ONLY against visible enemies within strike range <= 4600u)
        if (strikeCooldownElapsed
                && soloStrikeAllowed
                && div.hasTorpedoStrikeCapability
                && div.usableStrikeAmmoCount > 0
                && div.avgFluxLevel < 0.55f
                && torpedoTargetEnemyDiv != null
                && distToTorpedoEnemy <= 4600f) {

            boolean timeOnTargetCue = (myBg.currentPhase == BattlePhase.COORDINATED_STRIKE)
                    && (myBg.mainEffortDivision != null && myBg.mainEffortDivision != div)
                    && (torpedoTargetEnemyDiv == myBg.primaryThreatEnemyDivision);

            if (timeOnTargetCue) {
                div.currentIntent = DivisionIntentType.COORDINATED_TORPEDO_STRIKE;
                reason = "synced with \"" + myBg.mainEffortDivision.displayName + "\"";
            } else {
                div.currentIntent = DivisionIntentType.HARASS_TORPEDO_ATTACK;
                reason = String.format("%d strike salvos ready", div.usableStrikeAmmoCount);
            }

            Vector2f visTargetPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, torpedoTargetEnemyDiv);
            div.targetEnemyDivision = torpedoTargetEnemyDiv;
            div.maneuverAnchor.set(visTargetPos);
            if (!inTorpedoStrike) {
                div.strikeCommittedTimestamp = now;
            }
            targetLabel = torpedoTargetEnemyDiv.displayName;
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 12. Fast flankers swing wide to flank visible enemy division when within operational contact range (<= 4500u)
        if (strikeCooldownElapsed
                && div.isFastFlanker
                && nearestEnemyDiv != null
                && distToVisibleEnemy <= 4500f
                && myBg.mainEffortDivision != null
                && myBg.mainEffortDivision != div
                && div.avgFluxLevel < 0.52f) {

            div.currentIntent = DivisionIntentType.FLANK_ENEMY_DIVISION;
            div.targetEnemyDivision = nearestEnemyDiv;
            computeFlankAnchor(engine, div, nearestEnemyDiv, div.maneuverAnchor, myBg);
            if (!inFlankManeuver) {
                div.strikeCommittedTimestamp = now;
            }
            targetLabel = nearestEnemyDiv.displayName;
            reason = "enveloping flank vector" + flankSideTag(div, nearestEnemyDiv, myBg);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 13. Press local overmatch vs standard line advance (strictly respecting Fog of War)
        if (nearestEnemyDiv != null) {
            Vector2f visEnemyPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv);
            div.targetEnemyDivision = nearestEnemyDiv;
            div.maneuverAnchor.set(visEnemyPos);
            targetLabel = nearestEnemyDiv.displayName;

            if (div.localOvermatchRatio >= Math.max(1.22f, div.commander.overmatchCommitRatio) && distToVisibleEnemy < 3200f) {
                div.currentIntent = DivisionIntentType.PRESS_OVERMATCH;
                reason = String.format("local advantage %.2fx", div.localOvermatchRatio);
            } else {
                div.currentIntent = DivisionIntentType.ADVANCE_ENGAGE_LINE;
                reason = "maintaining firing line";
            }
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
        } else {
            // No enemy visible in Fog of War yet: advance on reconnaissance / objective posture without map-hacking
            if (uncapturedObj != null && !isHeavyLineOrCarrier) {
                div.currentIntent = DivisionIntentType.SEIZE_OBJECTIVE;
                div.targetObjective = uncapturedObj;
                div.maneuverAnchor.set(uncapturedObj.getLocation());
                targetLabel = uncapturedObj.getDisplayName();
                reason = "advancing in fog of war";
                div.lastIntentChangeTime = now;
                DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            } else {
                float scoutDirY = (div.side == 0) ? 1400f : -1400f;
                div.currentIntent = DivisionIntentType.ADVANCE_ENGAGE_LINE;
                div.maneuverAnchor.set(div.centroid.x * 0.6f, div.centroid.y + scoutDirY);
                div.lastIntentChangeTime = now;
            }
        }
    }

    /**
     * Expresses an active Battlegroup tasking as this division's intent. Returns false when the tasking only
     * shapes the division's own mission (targets, flank sides) or when the division declines a request
     * under Mission-Type Tactics; the mission ladder then runs with the BG target applied.
     */
    private static boolean applyBgTasking(
            CombatEngineAPI engine,
            DivisionState div,
            BattlegroupState myBg,
            BgTasking task,
            BattlegroupAuthority auth,
            DivisionMissionType activeMission,
            DivisionIntentType prevIntent,
            float now) {

        DivisionState target = task.targetEnemy;
        boolean targetValid = target != null && target.isCombatEffective()
                && BattlegroupCommanderAI.isDivisionVisibleToSide(engine, div.side, target);
        boolean binding = auth.isBinding();
        String reason = task.reason;

        switch (task.type) {
            case STRIKE_STAGE: {
                if (!targetValid || (!binding && !acceptsStrikeRequest(div))) return false;
                div.currentIntent = DivisionIntentType.STAGE_FOR_STRIKE;
                div.targetEnemyDivision = target;
                if (task.hasAnchor) {
                    div.maneuverAnchor.set(task.anchor);
                } else {
                    computeStandoffAnchor(engine, div, target, div.maneuverAnchor);
                }
                break;
            }
            case STRIKE_COMMIT: {
                if (!targetValid || (!binding && !acceptsStrikeRequest(div))) return false;
                if (prevIntent == DivisionIntentType.STRIKE_AND_WITHDRAW) return false; // already spent this strike
                boolean wasStriking = prevIntent == DivisionIntentType.COORDINATED_TORPEDO_STRIKE
                        || prevIntent == DivisionIntentType.CARRIER_MULTI_DIV_STRIKE;
                div.targetEnemyDivision = target;
                if (div.archetype.isCarrierArchetype()) {
                    div.currentIntent = DivisionIntentType.CARRIER_MULTI_DIV_STRIKE;
                    computeStandoffAnchor(engine, div, target, div.maneuverAnchor);
                } else {
                    div.currentIntent = DivisionIntentType.COORDINATED_TORPEDO_STRIKE;
                    div.maneuverAnchor.set(BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, target));
                }
                if (!wasStriking) {
                    StrikePlan plan = myBg.strikePlan;
                    div.strikeCommittedTimestamp = (plan != null && plan.committed) ? plan.commitTime : now;
                }
                break;
            }
            case RELIEVE: {
                DivisionState friend = task.targetFriendly;
                if (friend == null || !friend.isCombatEffective() || friend == div) return false;
                if (!binding && !(div.avgFluxLevel < 0.58f && (div.localEnemyStrength < 15f || div.localOvermatchRatio >= 1.0f))) {
                    return false;
                }
                div.currentIntent = DivisionIntentType.RELIEVE_FRIENDLY_DIVISION;
                div.targetFriendlyDivision = friend;
                div.maneuverAnchor.set(friend.centroid);
                DivisionCombatLog.logDivisionIntent(engine, div, friend.displayName, reason, now);
                return true;
            }
            case HOLD_RESERVE: {
                div.currentIntent = DivisionIntentType.HOLD_IN_RESERVE;
                div.maneuverAnchor.set(myBg.reserveAnchor);
                DivisionCombatLog.logDivisionIntent(engine, div, "", reason, now);
                return true;
            }
            case PURSUE: {
                if (!targetValid || (!binding && div.avgFluxLevel >= 0.50f)) return false;
                div.currentIntent = DivisionIntentType.PURSUE_BROKEN_ENEMY;
                div.targetEnemyDivision = target;
                div.maneuverAnchor.set(BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, target));
                break;
            }
            case FIX_PRIMARY: {
                if (!targetValid) return false;
                // Strike time: the Main Effort presses the struck division (timing is within every authority but D)
                if (BattlegroupOrchestrator.isStrikeCommittedOn(myBg, target) && auth != BattlegroupAuthority.TARGET_COORDINATION) {
                    div.currentIntent = DivisionIntentType.PRESS_OVERMATCH;
                    div.targetEnemyDivision = target;
                    div.maneuverAnchor.set(BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, target));
                    reason = "BG: strike time, press \"" + target.displayName + "\"";
                    break;
                }
                // Only Central Planning overrides the Main Effort's own mission style with a fixing posture
                if (auth != BattlegroupAuthority.CENTRAL_PLANNING) return false;
                div.currentIntent = DivisionIntentType.FIX_ENEMY_DIVISION;
                div.targetEnemyDivision = target;
                if (task.hasAnchor) {
                    div.maneuverAnchor.set(task.anchor);
                } else {
                    computeStandoffAnchor(engine, div, target, div.maneuverAnchor);
                }
                break;
            }
            case FLANK: {
                if (auth != BattlegroupAuthority.CENTRAL_PLANNING) return false;
                if (!targetValid || div.avgFluxLevel >= 0.58f || (now - div.lastStrikeWithdrawTimestamp) < 12f) return false;
                boolean wasFlanking = prevIntent == DivisionIntentType.FLANK_ENEMY_DIVISION;
                div.currentIntent = DivisionIntentType.FLANK_ENEMY_DIVISION;
                div.targetEnemyDivision = target;
                computeFlankAnchor(engine, div, target, div.maneuverAnchor, myBg);
                if (!wasFlanking) {
                    div.strikeCommittedTimestamp = now;
                }
                break;
            }
            case SUPPORT_ATTACK: {
                if (auth != BattlegroupAuthority.CENTRAL_PLANNING) return false;
                if (!targetValid || div.archetype.isCarrierArchetype() || activeMission == DivisionMissionType.SCREEN) return false;
                Vector2f visEnemyPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, target);
                float dist = distance(div.centroid, visEnemyPos);
                div.targetEnemyDivision = target;
                if (activeMission == DivisionMissionType.STANDOFF_AT_RANGE) {
                    div.currentIntent = DivisionIntentType.ADVANCE_ENGAGE_LINE;
                    computeStandoffAnchor(engine, div, target, div.maneuverAnchor);
                } else if (div.localOvermatchRatio >= Math.max(1.0f, div.commander.overmatchCommitRatio) && dist < 3200f) {
                    div.currentIntent = DivisionIntentType.PRESS_OVERMATCH;
                    div.maneuverAnchor.set(visEnemyPos);
                } else {
                    div.currentIntent = DivisionIntentType.ADVANCE_ENGAGE_LINE;
                    div.maneuverAnchor.set(visEnemyPos);
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
        return div.avgFluxLevel < 0.60f && (div.localEnemyStrength < 20f || div.localOvermatchRatio >= 0.90f);
    }

    /**
     * With the Timed Strikes play (outside Mission-Type Tactics) strike divisions hold their missiles for the BG's
     * synchronized strikes instead of freelancing, except against targets that come within close range.
     */
    private static boolean isSoloStrikeAllowed(BattlegroupState myBg, float distToTorpedoEnemy) {
        if (!myBg.isPlayEnabled(OrchestrationPlay.TIMED_STRIKES)) return true;
        if (myBg.authority == BattlegroupAuthority.MISSION_TACTICS) return true;
        if (myBg.strikePlan != null) return false;
        if (myBg.mainEffortDivision == null) return true;
        return distToTorpedoEnemy < 2200f;
    }

    private static String flankSideTag(DivisionState div, DivisionState enemyDiv, BattlegroupState myBg) {
        float side = assignedFlankSide(div, enemyDiv, myBg);
        if (side == 0f) return "";
        return side > 0f ? " [BG: right flank]" : " [BG: left flank]";
    }

    /** The BG-assigned flank side for this target, or 0 if the BG has not assigned one. */
    private static float assignedFlankSide(DivisionState div, DivisionState enemyDiv, BattlegroupState myBg) {
        if (myBg == null || !myBg.authority.allowsPositioning()) return 0f;
        BgTasking task = div.bgTask;
        if (task.type == BgTaskType.FLANK && task.targetEnemy == enemyDiv && task.flankSide != 0f) {
            return task.flankSide;
        }
        return 0f;
    }

    /** Recomputes the current intent's maneuver anchor so it tracks moving targets every tick. */
    private static void refreshManeuverAnchor(CombatEngineAPI engine, DivisionState div, BattlegroupState myBg, float now) {
        DivisionState enemy = div.targetEnemyDivision;
        DivisionState friend = div.targetFriendlyDivision;
        boolean enemyTrackable = enemy != null && enemy.isCombatEffective()
                && BattlegroupCommanderAI.isDivisionVisibleToSide(engine, div.side, enemy);

        switch (div.currentIntent) {
            case SCREEN_FRIENDLY_DIVISION:
                if (friend != null) {
                    computeScreenAnchor(engine, div, friend, enemy, div.maneuverAnchor, div.side);
                }
                break;
            case FLANK_ENEMY_DIVISION:
                if (enemyTrackable) {
                    computeFlankAnchor(engine, div, enemy, div.maneuverAnchor, myBg);
                }
                break;
            case RELIEVE_FRIENDLY_DIVISION:
                if (friend != null && friend.isCombatEffective()) {
                    div.maneuverAnchor.set(friend.centroid);
                }
                break;
            case PRESS_OVERMATCH:
            case HARASS_TORPEDO_ATTACK:
            case COORDINATED_TORPEDO_STRIKE:
            case PURSUE_BROKEN_ENEMY:
                if (enemyTrackable) {
                    div.maneuverAnchor.set(BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, enemy));
                }
                break;
            case ADVANCE_ENGAGE_LINE:
                if (enemyTrackable) {
                    DivisionMissionType mission = resolveActiveMission(div, now);
                    if (mission == DivisionMissionType.STANDOFF_AT_RANGE || mission == DivisionMissionType.RECONNAISSANCE) {
                        computeStandoffAnchor(engine, div, enemy, div.maneuverAnchor);
                    } else {
                        div.maneuverAnchor.set(BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, enemy));
                    }
                }
                break;
            case FIX_ENEMY_DIVISION:
                if (div.bgTask.isActive(now) && div.bgTask.hasAnchor) {
                    div.maneuverAnchor.set(div.bgTask.anchor);
                } else if (enemyTrackable) {
                    computeStandoffAnchor(engine, div, enemy, div.maneuverAnchor);
                }
                break;
            case STAGE_FOR_STRIKE:
                if (div.bgTask.isActive(now) && div.bgTask.hasAnchor) {
                    div.maneuverAnchor.set(div.bgTask.anchor);
                }
                break;
            case HOLD_IN_RESERVE:
            case STRIKE_AND_WITHDRAW:
                div.maneuverAnchor.set(myBg.reserveAnchor);
                break;
            case ROTATE_TO_RESERVE:
                if (friend != null && friend.isCombatEffective()) {
                    float retreatDirY = (div.side == 0) ? -1f : 1f;
                    div.maneuverAnchor.set(friend.centroid.x, friend.centroid.y + retreatDirY * 1100f);
                } else {
                    div.maneuverAnchor.set(myBg.reserveAnchor);
                }
                break;
            case CARRIER_PROTECT_FLEET_CAP:
                if (friend != null && friend != div && friend.isCombatEffective()) {
                    computeProtectAnchor(div, friend, div.maneuverAnchor);
                } else if (enemyTrackable) {
                    computeStandoffAnchor(engine, div, enemy, div.maneuverAnchor);
                }
                break;
            case CARRIER_STRIKE_CRAFT_RECON:
            case CARRIER_VOID_SUPERIORITY_CAP:
            case CARRIER_STRIKE_AT_WILL:
            case CARRIER_DIVISION_STRIKE:
            case CARRIER_MULTI_DIV_STRIKE:
                if (enemyTrackable) {
                    computeStandoffAnchor(engine, div, enemy, div.maneuverAnchor);
                }
                break;
            default:
                break;
        }
    }

    private static void computeProtectAnchor(DivisionState carrierDiv, DivisionState protectedDiv, Vector2f outAnchor) {
        float backSign = (carrierDiv.side == 0) ? -1f : 1f;
        outAnchor.set(
                protectedDiv.centroid.x,
                protectedDiv.centroid.y + backSign * Math.max(600f, carrierDiv.lineStandoffDistance * 0.45f));
    }

    /**
     * Cooperatively nudges ships in the division via ShipwideAIFlags.
     * Never overrides the player's manually piloted flagship or explicit player orders.
     */
    private static void applyDivisionFlagsToShips(
            CombatEngineAPI engine,
            DivisionState div,
            BattlegroupState myBg,
            float now) {

        ShipAPI playerShip = engine.getPlayerShip();
        boolean playerManualControl = (playerShip != null && !engine.isUIAutopilotOn());

        CombatFleetManagerAPI fm = engine.getFleetManager(div.side);

        List<ShipAPI> alive = div.getAliveMembers();
        if (alive.isEmpty()) return;

        ShipAPI focusEnemyShip = pickFocusEnemyShip(engine, div.side, div.targetEnemyDivision, div.centroid);

        // Timed strike: participants and the Main Effort all concentrate on the plan's single focus ship
        StrikePlan plan = myBg.strikePlan;
        if (plan != null && plan.committed && plan.focusShip != null
                && plan.focusShip.isAlive() && !plan.focusShip.isHulk()
                && (plan.participants.contains(div) || div == myBg.mainEffortDivision)
                && BattlegroupCommanderAI.isShipVisibleToSide(engine, div.side, plan.focusShip)) {
            focusEnemyShip = plan.focusShip;
        }

        for (int shipIdx = 0; shipIdx < alive.size(); shipIdx++) {
            ShipAPI ship = alive.get(shipIdx);

            // Release strike craft we held back for staging once the division is no longer staging
            if (div.currentIntent != DivisionIntentType.STAGE_FOR_STRIKE && div.fighterPullbackShipIds.remove(ship.getId())) {
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

            Vector2f slotPos = computeShipFormationSlot(engine, ship, shipIdx, alive, div, myBg);
            boolean crowdedByPeer = isShipCrowdedByDivisionPeer(ship, alive, div.idealIntraSpacing * 0.72f);

            float distToDivCenter = distance(ship.getLocation(), div.centroid);
            boolean shipStraggling = alive.size() >= 2 && distToDivCenter > (div.desiredCohesionRadius * 1.35f);

            // Bounded pursuit & cohesion leash: prevent lone ships from chasing bait 3000u away
            if (shipStraggling && div.currentIntent != DivisionIntentType.HARASS_TORPEDO_ATTACK
                    && div.currentIntent != DivisionIntentType.COORDINATED_TORPEDO_STRIKE) {
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                continue;
            }

            switch (div.currentIntent) {
                case FIX_ENEMY_DIVISION:
                    // Hold the fixing position and fight from it; no chasing, but no backing off either
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    if (focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    }
                    if (distance(ship.getLocation(), slotPos) > Math.max(450f, div.idealIntraSpacing * 0.8f) || crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case STAGE_FOR_STRIKE:
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

                case PURSUE_BROKEN_ENEMY: {
                    // Pursue on a leash measured from the battlegroup's centre, not from the division
                    float dx = ship.getLocation().x - myBg.fleetCentroid.x;
                    float dy = ship.getLocation().y - myBg.fleetCentroid.y;
                    float fromFleet = (float) Math.sqrt(dx * dx + dy * dy);
                    if (fromFleet > BattlegroupOrchestrator.PURSUIT_LEASH) {
                        float k = (BattlegroupOrchestrator.PURSUIT_LEASH * 0.8f) / Math.max(1f, fromFleet);
                        flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL,
                                new Vector2f(myBg.fleetCentroid.x + dx * k, myBg.fleetCentroid.y + dy * k));
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

                case HARASS_TORPEDO_ATTACK:
                case COORDINATED_TORPEDO_STRIKE:
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

                case PRESS_OVERMATCH:
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

                case STRIKE_AND_WITHDRAW:
                case ROTATE_TO_RESERVE:
                    flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL * 1.4f);
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL * 1.4f);
                    flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL * 1.4f, slotPos);
                    break;

                case SCREEN_FRIENDLY_DIVISION:
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    // Use spaced picket formation slots ahead/flank of the screened division instead of
                    // ESCORT_OTHER_SHIP on the flagship (which caused frigates to ball up on the battle line)
                    if (distance(ship.getLocation(), slotPos) > Math.max(260f, div.idealIntraSpacing * 0.55f) || crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    } else if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 1650f) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    }
                    break;

                case RELIEVE_FRIENDLY_DIVISION:
                    if (div.targetFriendlyDivision != null && div.targetFriendlyDivision.shipNeedingHelp != null
                            && div.targetFriendlyDivision.shipNeedingHelp.isAlive()
                            && shipIdx == 0) {
                        flags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, div.targetFriendlyDivision.shipNeedingHelp);
                    } else {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case FLANK_ENEMY_DIVISION:
                case SEIZE_OBJECTIVE:
                case HOLD_IN_RESERVE:
                    if (distance(ship.getLocation(), slotPos) > Math.max(320f, div.idealIntraSpacing * 0.65f) || crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    } else if (focusEnemyShip != null && div.currentIntent == DivisionIntentType.FLANK_ENEMY_DIVISION) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    }
                    break;

                case HOLD_AND_COHERE:
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    if (distToDivCenter > div.desiredCohesionRadius * 0.85f || crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case CARRIER_STRIKE_CRAFT_RECON:
                case CARRIER_VOID_SUPERIORITY_CAP:
                case CARRIER_PROTECT_FLEET_CAP:
                case CARRIER_STRIKE_AT_WILL:
                case CARRIER_DIVISION_STRIKE:
                case CARRIER_MULTI_DIV_STRIKE:
                    // In a Mixed Carrier Division, non-carrier escorts protect the carrier flagship closely
                    if (div.archetype == DivisionArchetype.CARRIER_AND_ESCORT
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
                        if (distToEnemy < div.lineStandoffDistance * 0.85f) {
                            flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        } else if (distance(ship.getLocation(), slotPos) > Math.max(320f, div.idealIntraSpacing * 0.65f) || crowdedByPeer) {
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        }
                        if (ship.getNumFighterBays() > 0) {
                            if (div.currentIntent == DivisionIntentType.CARRIER_PROTECT_FLEET_CAP
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

                case ADVANCE_ENGAGE_LINE:
                default:
                    DivisionMissionType activeMission = resolveActiveMission(div, now);
                    boolean standoffPosture = (activeMission == DivisionMissionType.STANDOFF_AT_RANGE
                            || activeMission == DivisionMissionType.RECONNAISSANCE);

                    // Composite Carrier & Escort inner discipline: non-carrier escorts screen the carrier flagship
                    if (div.archetype == DivisionArchetype.CARRIER_AND_ESCORT
                            && div.flagship != null
                            && ship != div.flagship
                            && ship.getNumFighterBays() == 0) {
                        flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                        flags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, div.flagship);
                    } else if (standoffPosture && focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        float distToEnemy = distance(ship.getLocation(), focusEnemyShip.getLocation());
                        if (distToEnemy < div.lineStandoffDistance * 0.85f) {
                            flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
                            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        } else if (distToEnemy > div.lineStandoffDistance * 1.18f || crowdedByPeer) {
                            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        }
                    } else if (crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 2600f) {
                            directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        }
                    } else if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 2600f) {
                        if (activeMission == DivisionMissionType.DOMINATE_SPACE_INTO_BRAWL) {
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
            if (div.currentIntent == DivisionIntentType.SEIZE_OBJECTIVE && div.targetObjective == info.getTarget()) {
                return false;
            }
            return true;
        }
        return true;
    }

    private static DivisionState findBestEnemyDivisionFor(
            CombatEngineAPI engine,
            DivisionState myDiv,
            BattlegroupState enemyBg,
            boolean preferTorpedoPrey,
            DivisionState previousTarget) {

        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;

        for (DivisionState eDiv : enemyBg.divisions) {
            if (!eDiv.isCombatEffective()) continue;
            // Strict Fog of War check: cannot target enemy divisions hidden in the Fog of War!
            if (!BattlegroupCommanderAI.isDivisionVisibleToSide(engine, myDiv.side, eDiv)) {
                continue;
            }

            Vector2f visCentroid = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, myDiv.side, eDiv);
            float visFp = BattlegroupCommanderAI.getVisibleFpForSide(engine, myDiv.side, eDiv);
            float dist = distance(myDiv.centroid, visCentroid);
            float score = -dist * 0.35f;

            // Sticky target bonus prevents divisions from flip-flopping between two enemy divisions every few seconds
            if (eDiv == previousTarget) {
                score += 280f;
            }

            // Deprioritize civilian/auxiliary groups when combat divisions exist
            if (eDiv.archetype == DivisionArchetype.AUXILIARY_CONVOY) {
                score -= 450f;
            }

            if (preferTorpedoPrey) {
                // Missile destroyers/cruisers love enemy Destroyers, Cruisers, Carriers, or high-flux targets
                if (eDiv.archetype == DivisionArchetype.DESTROYER_DIVISION
                        || eDiv.archetype == DivisionArchetype.TORPEDO_DESTROYER_DIVISION
                        || eDiv.archetype == DivisionArchetype.CRUISER_DIVISION
                        || eDiv.archetype.isCarrierArchetype()
                        || eDiv.archetype == DivisionArchetype.BATTLE_DIVISION) {
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

    private static DivisionState findEndangeredFriendlyDivision(DivisionState myDiv, BattlegroupState myBg) {
        DivisionState best = null;
        float bestUrgency = 0f;
        for (DivisionState other : myBg.divisions) {
            if (other == myDiv || !other.isCombatEffective()) continue;
            if (other.localEnemyStrength < 12f) continue;
            // Do not relieve a division that already has local overmatch advantage (>= 0.85)
            if (other.localOvermatchRatio >= 0.85f) continue;
            // Never relieve the division that is on its way to relieve us (mutual-relief ping-pong)
            if (other.currentIntent == DivisionIntentType.RELIEVE_FRIENDLY_DIVISION && other.targetFriendlyDivision == myDiv) continue;

            // Prevent relief dogpiling: if another friendly division is already relieving 'other', skip it
            boolean alreadyRelieved = false;
            for (DivisionState peer : myBg.divisions) {
                if (peer != myDiv && peer != other && peer.isCombatEffective()
                        && peer.currentIntent == DivisionIntentType.RELIEVE_FRIENDLY_DIVISION
                        && peer.targetFriendlyDivision == other) {
                    alreadyRelieved = true;
                    break;
                }
            }
            if (alreadyRelieved) continue;

            float urgency = (1.0f - other.localOvermatchRatio) * 1000f;
            if (other.hasShipNeedingHelp) {
                urgency += 300f;
            }
            if (other.archetype.isCarrierArchetype()
                    || other.archetype == DivisionArchetype.BATTLE_DIVISION) {
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

    private static DivisionState findHeavyFriendlyDivisionToScreen(DivisionState myDiv, BattlegroupState myBg) {
        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;
        for (DivisionState other : myBg.divisions) {
            if (other == myDiv || !other.isCombatEffective()) continue;
            boolean isHeavy = other.archetype == DivisionArchetype.BATTLE_DIVISION
                    || other.archetype.isCarrierArchetype()
                    || other.archetype == DivisionArchetype.CRUISER_DIVISION;
            if (!isHeavy) continue;

            // A Mixed Carrier division already has organic escorts; only assign an extra
            // screening division if it is under local enemy pressure or lost its escorts.
            if (other.archetype == DivisionArchetype.CARRIER_AND_ESCORT
                    && other.getAliveMembers().size() >= 2
                    && other.localEnemyStrength < 15f) {
                continue;
            }

            // Cap screening to at most 1 screening division per heavy division to prevent fleet-wide screen dogpiling
            boolean alreadyScreenedByPeer = false;
            for (DivisionState peer : myBg.divisions) {
                if (peer != myDiv && peer != other && peer.isCombatEffective()
                        && peer.currentIntent == DivisionIntentType.SCREEN_FRIENDLY_DIVISION
                        && peer.targetFriendlyDivision == other) {
                    alreadyScreenedByPeer = true;
                    break;
                }
            }
            if (alreadyScreenedByPeer) continue;

            // Keep existing screen target sticky to avoid ping-ponging between two heavy divisions
            float stickyBonus = (myDiv.currentIntent == DivisionIntentType.SCREEN_FRIENDLY_DIVISION
                    && myDiv.targetFriendlyDivision == other) ? 350f : 0f;

            float dist = distance(myDiv.centroid, other.centroid);
            float score = other.totalFpStrength * 10f - dist * 0.3f + stickyBonus;
            if (other.archetype == DivisionArchetype.BATTLE_DIVISION) {
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
            if (!BattlegroupCommanderAI.isShipVisibleToSide(engine, observerSide, e)) continue;
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
            BattlegroupState myBg,
            BattleObjectiveAPI previousObjective) {
        if (engine == null) return null;
        if (div.archetype.isCarrierArchetype()
                || div.archetype == DivisionArchetype.BATTLE_DIVISION) {
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
                if (!isObjectiveClaimedByOtherDivision(obj, div, myBg)) {
                    return obj;
                }
            }
        }

        // 2. Check active objective assignments in CombatTaskManager for fast maneuver / destroyer divisions
        if (div.archetype != DivisionArchetype.FRIGATE_DIVISION
                && div.archetype != DivisionArchetype.PHASE_DIVISION
                && div.archetype != DivisionArchetype.DESTROYER_DIVISION
                && div.archetype != DivisionArchetype.TORPEDO_DESTROYER_DIVISION) {
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
                if (isObjectiveClaimedByOtherDivision(obj, div, myBg)) continue;
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
            BattlegroupState myBg,
            BattleObjectiveAPI previousObjective) {
        List<BattleObjectiveAPI> objs = engine.getObjectives();
        if (objs == null || objs.isEmpty()) return null;

        // First pass: prefer uncaptured objectives NOT already claimed by another friendly division
        BattleObjectiveAPI bestUnclaimed = null;
        float bestScore = -Float.MAX_VALUE;
        for (BattleObjectiveAPI obj : objs) {
            if (obj == null || obj.getOwner() == div.side) continue;
            if (isObjectiveClaimedByOtherDivision(obj, div, myBg)) continue;
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
            BattlegroupState myBg) {
        if (obj == null || myBg == null) return false;
        for (DivisionState peer : myBg.divisions) {
            if (peer != myDiv && peer.isCombatEffective()
                    && peer.currentIntent == DivisionIntentType.SEIZE_OBJECTIVE
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
     * Computes a ship's individual formation slot around its division's maneuver anchor using the division's
     * configured idealIntraSpacing (in units) and applies peer-separation + main-battle-line standoff repulsion
     * so frigates and line ships maintain their doctrinal intra-division spacing instead of balling up.
     */
    private static Vector2f computeShipFormationSlot(
            CombatEngineAPI engine,
            ShipAPI ship,
            int shipIdx,
            List<ShipAPI> alive,
            DivisionState div,
            BattlegroupState myBg) {

        float spacing = Math.max(150f, div.idealIntraSpacing);
        Vector2f baseCenter = (div.currentIntent == DivisionIntentType.HOLD_AND_COHERE)
                ? div.centroid
                : div.maneuverAnchor;

        float fwdX = 0f;
        float fwdY = (div.side == 0) ? 1f : -1f;
        if (div.targetEnemyDivision != null) {
            Vector2f visEnemy = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, div.targetEnemyDivision);
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
        PreferredSpace prefSpace = (div.preferredSpace != null) ? div.preferredSpace : PreferredSpace.MIDDLE;
        if (div.currentIntent != DivisionIntentType.HOLD_IN_RESERVE
                && div.currentIntent != DivisionIntentType.ROTATE_TO_RESERVE
                && div.currentIntent != DivisionIntentType.STRIKE_AND_WITHDRAW) {
            if (prefSpace == PreferredSpace.FORWARD) {
                depthOffset += 220f;
            } else if (prefSpace == PreferredSpace.BACK) {
                depthOffset -= 320f;
            }
        }

        float slotX = baseCenter.x + latX * latOffset + fwdX * depthOffset;
        float slotY = baseCenter.y + latY * latOffset + fwdY * depthOffset;

        // Peer repulsion: if another ship in the same division is closer than idealIntraSpacing * 0.90f, push apart
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

        // Inter-division spacing repulsion: keep friendly divisions separated by their configured idealInterSpacing
        if (myBg != null) {
            float desiredInter = Math.max(400f, div.idealInterSpacing);
            for (DivisionState peerDiv : myBg.divisions) {
                if (peerDiv == null || peerDiv == div || !peerDiv.isCombatEffective()) continue;
                float minInter = Math.max(desiredInter, peerDiv.idealInterSpacing) * 0.85f;
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
                && myBg != null
                && myBg.mainEffortDivision != null
                && myBg.mainEffortDivision != div
                && myBg.mainEffortDivision.isCombatEffective()) {
            Vector2f mainPos = myBg.mainEffortDivision.centroid;
            float mdx = ship.getLocation().x - mainPos.x;
            float mdy = ship.getLocation().y - mainPos.y;
            float mDist = (float) Math.sqrt(mdx * mdx + mdy * mdy);
            float minClearance = Math.max(550f, Math.max(div.idealInterSpacing * 0.80f, div.lineStandoffDistance * 0.75f));
            if (mDist > 1f && mDist < minClearance) {
                float push = (minClearance - mDist) * 0.85f;
                slotX += (mdx / mDist) * push;
                slotY += (mdy / mDist) * push;
            }
        }

        return new Vector2f(slotX, slotY);
    }

    private static void computeStandoffAnchor(
            CombatEngineAPI engine,
            DivisionState div,
            DivisionState enemyDiv,
            Vector2f outAnchor) {
        if (enemyDiv == null) {
            outAnchor.set(div.centroid);
            return;
        }
        Vector2f visEnemyPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, enemyDiv);
        float dx = div.centroid.x - visEnemyPos.x;
        float dy = div.centroid.y - visEnemyPos.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float standoff = Math.max(400f, div.lineStandoffDistance);
        PreferredSpace prefSpace = (div.preferredSpace != null) ? div.preferredSpace : PreferredSpace.MIDDLE;
        if (prefSpace == PreferredSpace.FORWARD) {
            standoff = Math.max(350f, standoff * 0.85f);
        } else if (prefSpace == PreferredSpace.BACK) {
            standoff = standoff * 1.18f;
        }
        if (len > 1f) {
            outAnchor.set(
                    visEnemyPos.x + (dx / len) * standoff,
                    visEnemyPos.y + (dy / len) * standoff);
        } else {
            float backY = (div.side == 0) ? -standoff : standoff;
            outAnchor.set(visEnemyPos.x, visEnemyPos.y + backY);
        }
    }

    private static void computeScreenAnchor(
            CombatEngineAPI engine,
            DivisionState screenDiv,
            DivisionState protectedDiv,
            DivisionState enemyDiv,
            Vector2f outAnchor,
            int side) {
        if (protectedDiv == null) return;
        float standoff = (screenDiv != null) ? Math.max(550f, screenDiv.lineStandoffDistance) : 750f;
        float flankSign = (screenDiv != null && screenDiv.divisionNumber % 2 == 0) ? -1f : 1f;
        float lateralOffset = (screenDiv != null) ? Math.max(320f, screenDiv.idealIntraSpacing * 0.85f) * flankSign : 0f;

        if (enemyDiv != null) {
            Vector2f visEnemyPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, side, enemyDiv);
            float dx = visEnemyPos.x - protectedDiv.centroid.x;
            float dy = visEnemyPos.y - protectedDiv.centroid.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1f) {
                float fwdX = dx / len;
                float fwdY = dy / len;
                float latX = -fwdY;
                float latY = fwdX;
                outAnchor.set(
                        protectedDiv.centroid.x + fwdX * (standoff * 0.80f) + latX * lateralOffset,
                        protectedDiv.centroid.y + fwdY * (standoff * 0.80f) + latY * lateralOffset);
                return;
            }
        }
        float forwardY = (side == 0) ? standoff * 0.80f : -standoff * 0.80f;
        outAnchor.set(protectedDiv.centroid.x + lateralOffset, protectedDiv.centroid.y + forwardY);
    }

    private static void computeFlankAnchor(
            CombatEngineAPI engine,
            DivisionState flankerDiv,
            DivisionState enemyDiv,
            Vector2f outAnchor,
            BattlegroupState myBg) {
        Vector2f visEnemyPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, flankerDiv.side, enemyDiv);
        float flankRadius = Math.max(1050f, flankerDiv.lineStandoffDistance + flankerDiv.idealIntraSpacing * 0.65f);

        // Pincer: a BG-assigned flank side is measured on the battlegroup's attack axis (fleet centre -> target),
        // so two flankers on opposite sides really do envelop the target from left and right.
        float assignedSide = assignedFlankSide(flankerDiv, enemyDiv, myBg);
        if (assignedSide != 0f) {
            float ax = visEnemyPos.x - myBg.fleetCentroid.x;
            float ay = visEnemyPos.y - myBg.fleetCentroid.y;
            float aLen = (float) Math.sqrt(ax * ax + ay * ay);
            if (aLen > 1f) {
                float fwdX = ax / aLen;
                float fwdY = ay / aLen;
                float perpX = -fwdY;
                float perpY = fwdX;
                // Slightly past the target's line so the flank gets at its side rather than its face
                outAnchor.set(
                        visEnemyPos.x + perpX * flankRadius * assignedSide + fwdX * flankRadius * 0.2f,
                        visEnemyPos.y + perpY * flankRadius * assignedSide + fwdY * flankRadius * 0.2f);
                return;
            }
        }

        float dx = visEnemyPos.x - flankerDiv.centroid.x;
        float dy = visEnemyPos.y - flankerDiv.centroid.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= 1f) {
            outAnchor.set(visEnemyPos);
            return;
        }
        // Perpendicular offset to swing wide around the enemy division's flank, scaled by doctrinal standoff & spacing
        float perpX = -dy / len;
        float perpY = dx / len;
        float sideSign = (flankerDiv.divisionNumber % 2 == 1) ? 1f : -1f;
        outAnchor.set(
                visEnemyPos.x + perpX * flankRadius * sideSign,
                visEnemyPos.y + perpY * flankRadius * sideSign);
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
