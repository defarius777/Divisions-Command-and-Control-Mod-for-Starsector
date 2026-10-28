package divisionai;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAIPlugin;
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
import divisionai.PermanentDivisionManager.DivisionFormation;
import divisionai.PermanentDivisionManager.DivisionMovementStickiness;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.OtcPlay;
import divisionai.PermanentDivisionManager.LineStation;
import org.lwjgl.util.vector.Vector2f;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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

    private static boolean resolvedBasicAiHandles = false;
    private static Class<?> basicShipAiClass = null;
    private static Class<?> shipAiWrapperClass = null;
    private static Class<?> obfShipClass = null;
    private static MethodHandle unwrapAiHandle = null;
    private static MethodHandle setTargetOverrideHandle = null;

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
            refreshStationPoint(engine, div, myGroup, enemyGroup, now);

            // Evaluate active formation (inherited from doctrine -> auto-assigner -> battle, modified or broken up
            // at the discretion of the Division Commander or an OTC with enough authority)
            evaluateAndUpdateDivisionFormation(engine, div, myGroup, now);

            // Apply per-tick (0.5s cadence) ShipwideAIFlags nudges to division members
            applyDivisionFlagsToShips(engine, div, myGroup, enemyGroup, now);
        }
    }

    /**
     * Resolves and updates a division's active combat formation:
     *  - Starts from {@code div.doctrinalFormation} (inherited from Fleet Doctrine / Division Auto-Assigner / Division Editor).
     *  - Can be changed or broken up ({@link DivisionFormation#NONE}) at the discretion of:
     *      (a) an OTC with sufficient authority ({@link OtcAuthority#POSITIVE_CONTROL} or {@link OtcAuthority#COMMAND_BY_DIRECTION}), or
     *      (b) the Division Commander responding to tactical reality (breaking formation during General Chase,
     *          Haul Out to Vent, Fighting Retirement, Martyrdom, or severe high-flux close-quarters disruption;
     *          shifting to Circle when surrounded in a crossfire cordon; shifting to Line Ahead when threading
     *          out-of-contact corridors; shifting to Forward Semicircle when screening; shifting to Echelon or
     *          Reverse Semicircle when flanking; or shifting to Forward Wedge on a shock charge).
     */
    public static void evaluateAndUpdateDivisionFormation(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            float now) {
        if (div == null) return;
        DivisionFormation base = div.doctrinalFormation != null
                ? div.doctrinalFormation
                : DivisionFormation.LINE_ABREAST;
        int aliveCount = div.getAliveMembers().size();

        DivisionFormation desired = base;
        div.otcOrderedFormation = (div.otcOrder != null && div.otcOrder.isActive(now))
                ? div.otcOrder.orderedFormation
                : null;

        boolean martyrdom = isMartyrdomActive(div, myGroup, now);
        boolean urgentBreakup = martyrdom
                || div.currentIntent == DivisionIntentType.GIVE_GENERAL_CHASE
                || div.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                || div.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE
                || (div.avgFluxLevel >= 0.72f && div.localEnemyStrength >= 12f && div.commander.aggressionBias < 1.30f)
                || (div.maxMemberFluxLevel >= 0.86f && div.localEnemyStrength >= 14f && div.spreadRadius > div.stationKeepingRadius * 1.35f);

        if (urgentBreakup) {
            desired = DivisionFormation.NONE;
        } else if (div.otcOrderedFormation != null
                && myGroup != null
                && (myGroup.authority == OtcAuthority.POSITIVE_CONTROL
                || (myGroup.authority == OtcAuthority.COMMAND_BY_DIRECTION && div.commander.stationKeepingStrictness >= 0.88f))) {
            desired = div.otcOrderedFormation;
        } else if (myGroup == null || myGroup.authority != OtcAuthority.POSITIVE_CONTROL || base != DivisionFormation.NONE) {
            // Division Commander's tactical discretion
            if (div.crossfireCordonIndex >= 0.52f && aliveCount >= 3) {
                desired = DivisionFormation.CIRCLE;
            } else if (div.currentIntent == DivisionIntentType.TAKE_SCREENING_STATION && aliveCount >= 2) {
                desired = DivisionFormation.FORWARD_SEMICIRCLE;
            } else if (div.currentIntent == DivisionIntentType.WORK_FLANK && aliveCount >= 2) {
                if (div.useConvergence && aliveCount >= 3) {
                    desired = DivisionFormation.REVERSE_SEMICIRCLE;
                } else if (base == DivisionFormation.LINE_ABREAST
                        || base == DivisionFormation.FORWARD_SEMICIRCLE
                        || base == DivisionFormation.FORWARD_WEDGE) {
                    float fSide = (div.otcOrder != null && div.otcOrder.flankSide != 0f)
                            ? div.otcOrder.flankSide
                            : div.routeSkirtSide;
                    desired = (fSide >= 0f)
                            ? DivisionFormation.ECHELON_LEFT
                            : DivisionFormation.ECHELON_RIGHT;
                }
            } else if ((div.currentIntent == DivisionIntentType.LAUNCH_ALPHA_STRIKE
                    || div.currentIntent == DivisionIntentType.LAUNCH_DECKLOAD_STRIKE)
                    && aliveCount >= 2) {
                desired = DivisionFormation.REVERSE_SEMICIRCLE;
            } else if (div.currentIntent == DivisionIntentType.FLY_COMBAT_SPACE_PATROL && aliveCount >= 2) {
                desired = DivisionFormation.REVERSE_WEDGE;
            } else if (div.currentIntent == DivisionIntentType.FLY_FIGHTER_SWEEP && aliveCount >= 2) {
                desired = (div.divisionNumber % 2 == 0)
                        ? DivisionFormation.ECHELON_RIGHT
                        : DivisionFormation.ECHELON_LEFT;
            } else if (div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE && aliveCount >= 2) {
                desired = DivisionFormation.FORWARD_WEDGE;
            } else if (div.currentIntent == DivisionIntentType.PRESS_HOME && aliveCount >= 3) {
                if (base == DivisionFormation.LINE_ABREAST || base == DivisionFormation.LINE_AHEAD) {
                    desired = div.hasMissileStrikeCapability
                            ? DivisionFormation.REVERSE_SEMICIRCLE
                            : DivisionFormation.FORWARD_WEDGE;
                }
            }
        }

        if (div.activeFormation == null) {
            div.activeFormation = desired;
            div.lastFormationChangeTime = now;
            return;
        }

        if (desired != div.activeFormation) {
            boolean immediate = urgentBreakup
                    || div.activeFormation == DivisionFormation.NONE
                    || (now - div.lastFormationChangeTime) >= 4.0f;
            if (immediate) {
                div.activeFormation = desired;
                div.lastFormationChangeTime = now;
            }
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

    /**
     * Returns true when Martyrdom tactics are active for this division:
     *  - The division's active mission is MARTYRDOM, OR
     *  - The Battle Group has OtcPlay.MARTYRDOM enabled AND the division either contains VBIED/IED hulls,
     *    has MARTYRDOM as its opening/main mission, or is on a close-action charge.
     */
    public static boolean isMartyrdomActive(DivisionState div, BattleGroupState myGroup, float now) {
        if (div == null || div.archetype.isCarrierArchetype() || div.archetype == DivisionArchetype.SERVICE_GROUP) {
            return false;
        }
        DivisionMissionType activeMission = resolveActiveMission(div, now);
        if (activeMission == DivisionMissionType.MARTYRDOM) {
            return true;
        }
        if (myGroup != null && myGroup.isPlayEnabled(OtcPlay.MARTYRDOM)) {
            if (div.openingMission == DivisionMissionType.MARTYRDOM || div.mainMission == DivisionMissionType.MARTYRDOM) {
                return true;
            }
            for (ShipAPI s : div.getAliveMembers()) {
                if (isVbiedOrFanaticHull(s)) {
                    return true;
                }
            }
            if (activeMission == DivisionMissionType.CLOSE_ACTION) {
                return true;
            }
        }
        return false;
    }

    public static boolean isVbiedOrFanaticHull(ShipAPI ship) {
        if (ship == null) return false;
        if (ship.getHullSpec() != null) {
            String hId = ship.getHullSpec().getHullId() != null ? ship.getHullSpec().getHullId().toLowerCase() : "";
            String hName = ship.getHullSpec().getHullName() != null ? ship.getHullSpec().getHullName().toLowerCase() : "";
            if (hId.contains("ied") || hId.contains("vbied") || hId.contains("martyr") || hId.contains("kamikaze")
                    || hId.contains("suicide") || hName.contains("ied") || hName.contains("martyr")) {
                return true;
            }
        }
        if (ship.getSystem() != null && ship.getSystem().getId() != null) {
            String sysId = ship.getSystem().getId().toLowerCase();
            if (sysId.contains("ied") || sysId.contains("detonat") || sysId.contains("martyr") || sysId.contains("selfdestruct")) {
                return true;
            }
        }
        return false;
    }

    /** Events that break intent commitment and force an immediate re-evaluation. */
    private static boolean hasUrgentTrigger(CombatEngineAPI engine, DivisionState div, BattleGroupState myGroup, float now) {
        if (div.otcOrder.version != div.appliedOtcOrderVersion) return true;
        boolean martyrdom = isMartyrdomActive(div, myGroup, now);
        if (!martyrdom && isStrikeRunOver(div, now)) return true;
        if (!martyrdom && isUnderHighFluxPressure(div, myGroup)
                && div.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT
                && div.currentIntent != DivisionIntentType.BREAK_OFF_AND_RETIRE) {
            return true;
        }
        if (!martyrdom && myGroup.currentPhase == BattlePhase.FIGHTING_RETIREMENT
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
        if (div.currentIntent == DivisionIntentType.CLOSE_UP && div.localEnemyStrength >= 8f) {
            return true;
        }
        return false;
    }

    /** A missile run, ToT Alpha Strike, or flank maneuver finished its window (or expended torpedoes / built flux) -> break off! */
    private static boolean isStrikeRunOver(DivisionState div, float now) {
        boolean inMissileStrike = (div.currentIntent == DivisionIntentType.HARASSING_MISSILE_ATTACK
                || div.currentIntent == DivisionIntentType.TOT_MISSILE_STRIKE);
        boolean inTotAlphaStrike = (div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE);
        boolean inFlankManeuver = (div.currentIntent == DivisionIntentType.WORK_FLANK);

        boolean windowElapsedAndPressured = ((now - div.strikeCommittedTimestamp) >= STRIKE_WINDOW_DURATION)
                && (div.avgFluxLevel >= 0.25f || (div.avgFluxLevel >= 0.12f && div.localEnemyStrength >= 14f && div.localForceRatio < 0.85f));
        boolean missileRunComplete = inMissileStrike && (windowElapsedAndPressured
                || div.avgFluxLevel >= div.commander.rotationFluxThreshold * 0.90f
                || (div.hasMissileStrikeCapability && div.usableStrikeAmmoCount <= 0 && div.avgFluxLevel >= 0.25f));

        // ToT Alpha Strike ("get out of dodge"): rapidly dump all guns/torpedoes over a crisp 9.5s attack pass,
        // or break off immediately once strike torpedoes are expended or flux reaches 45% avg / 65% peak!
        float alphaElapsed = now - div.strikeCommittedTimestamp;
        boolean totAlphaRunComplete = inTotAlphaStrike && (alphaElapsed >= 9.5f
                || div.avgFluxLevel >= 0.45f
                || div.maxMemberFluxLevel >= 0.65f
                || (div.hasMissileStrikeCapability && div.usableStrikeAmmoCount <= 0 && alphaElapsed >= 4.0f)
                || (div.localForceRatio < 0.45f && div.localEnemyStrength >= 16f && div.avgFluxLevel >= 0.30f));

        boolean flankBreakOffNeeded = inFlankManeuver
                && (div.avgFluxLevel >= div.commander.rotationFluxThreshold * 0.88f
                || ((now - div.strikeCommittedTimestamp) >= (STRIKE_WINDOW_DURATION * 1.5f) && div.avgFluxLevel >= 0.45f));
        return missileRunComplete || totAlphaRunComplete || flankBreakOffNeeded;
    }

    /** Requires actual flux buildup (or critical damage under heavy local overmatch / L-U crossfire cordon) so fresh divisions never retreat. */
    private static boolean isUnderHighFluxPressure(DivisionState div, BattleGroupState myGroup) {
        boolean inCrossfireCordon = div.crossfireCordonIndex >= 0.36f && div.localEnemyStrength >= 10f;
        boolean isLoneFrigateDiv = (div.getAliveMembers().size() == 1)
                && (div.totalFpStrength <= 10f
                || div.archetype == DivisionArchetype.FRIGATE_DIVISION
                || div.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION);
        // Protect lone frigates from getting turned into swiss cheese when outnumbered,
        // even under a Reckless commander (where vanilla AI's helpless-target overmatch would otherwise make them dive)!
        boolean loneFrigateOvermatched = isLoneFrigateDiv
                && div.localEnemyStrength >= 10f
                && div.localForceRatio < 0.65f
                && (div.avgFluxLevel >= 0.35f || div.maxMemberFluxLevel >= 0.45f || div.avgHullLevel < 0.75f);

        boolean highFluxPressure = div.avgFluxLevel >= div.commander.rotationFluxThreshold
                || loneFrigateOvermatched
                || (div.maxMemberFluxLevel >= 0.78f && div.localEnemyStrength > 10f && (div.localForceRatio < 0.72f || inCrossfireCordon))
                || (div.maxMemberFluxLevel >= 0.82f && div.localEnemyStrength >= 10f && div.avgFluxLevel >= 0.42f)
                || (div.avgFluxLevel >= 0.45f && div.localEnemyStrength > 14f && div.localForceRatio < 0.50f && div.commander.aggressionBias < 1.35f)
                || (inCrossfireCordon && (div.maxMemberFluxLevel >= 0.64f || (div.avgFluxLevel >= 0.38f && div.localForceRatio < 0.68f)));
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

    // =========================================================================
    // Section 2: Division Intent Priority Ladder & Tactical Sub-Evaluators
    // =========================================================================

    private static boolean commitIntent(
            CombatEngineAPI engine,
            DivisionState div,
            DivisionIntentType intent,
            String targetLabel,
            String reason,
            float now) {
        div.currentIntent = intent;
        div.lastIntentChangeTime = now;
        DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
        return true;
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

        OtcAuthority auth = myGroup.authority;
        OtcOrder task = div.otcOrder;
        boolean taskActive = task.isActive(now);

        DivisionState ownNearestEnemyDiv = findBestEnemyDivisionFor(engine, div, myGroup, enemyGroup, false, prevEnemyTarget);
        DivisionState nearestEnemyDiv = resolveOtcTarget(engine, div, task, taskActive, auth, ownNearestEnemyDiv);
        DivisionState missileTargetEnemyDiv = (nearestEnemyDiv != ownNearestEnemyDiv && auth.isBinding())
                ? nearestEnemyDiv
                : findBestEnemyDivisionFor(engine, div, myGroup, enemyGroup, true, prevEnemyTarget);
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

        boolean martyrdom = isMartyrdomActive(div, myGroup, now);

        // Steps 1 & 2: Post-strike retirement or high-flux line rotation (HAUL_OUT_TO_VENT)
        if (!martyrdom && trySelectWithdrawalOrVentIntent(
                engine, div, myGroup, prevIntent, nearestEnemyDiv, heavyFriendlyToScreen, now)) {
            return;
        }

        DivisionMissionType activeMission = resolveActiveMission(div, now);
        String missionPhaseTag = isInOpeningWindow(div, now) ? "Opening: " : "Main: ";
        boolean isHeavyLineOrCarrier = (div.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                || div.archetype.isCarrierArchetype());

        // Step 3: Explicit Command Window objective order (player orders outrank the OTC)
        if (commandAssignedObj != null && !isHeavyLineOrCarrier && div.localEnemyStrength < 18f) {
            div.targetObjective = commandAssignedObj;
            div.stationPoint.set(commandAssignedObj.getLocation());
            commitIntent(engine, div, DivisionIntentType.TAKE_AND_HOLD,
                    commandAssignedObj.getDisplayName(), "under direct orders", now);
            return;
        }

        // Step 4: Active OTC orchestration order
        if (taskActive && applyOtcOrder(engine, div, myGroup, task, auth, activeMission, prevIntent, now)) {
            return;
        }

        // Step 5: Mission-driven objective seizure (Main Effort stays on its fixing task instead)
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
            div.targetObjective = uncapturedObj;
            div.stationPoint.set(uncapturedObj.getLocation());
            commitIntent(engine, div, DivisionIntentType.TAKE_AND_HOLD,
                    uncapturedObj.getDisplayName(), missionPhaseTag + activeMission.shortName, now);
            return;
        }

        // Step 6: Battle Group Reserve hold when the OTC is not orchestrating the reserve directly
        boolean otcHoldsReserve = myGroup.isPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE) && auth.allowsRetasking();
        if (!otcHoldsReserve) {
            boolean calledUpByOtc = (endangeredFriendlyDiv != null)
                    || (myGroup.currentPhase == BattlePhase.REINFORCE_THE_LINE && nearestEnemyDiv != null)
                    || (myGroup.currentPhase == BattlePhase.GENERAL_CHASE && nearestEnemyDiv != null)
                    || (myGroup.currentPhase == BattlePhase.COORDINATED_STRIKE && nearestEnemyDiv != null && div.hasMissileStrikeCapability);
            if ((activeMission == DivisionMissionType.RESERVE || div.isDesignatedReserve) && !calledUpByOtc) {
                div.stationPoint.set(myGroup.reserveStation);
                String reason = (activeMission == DivisionMissionType.RESERVE)
                        ? (missionPhaseTag + "Reserve, awaiting OTC orders")
                        : "awaiting OTC orders";
                commitIntent(engine, div, DivisionIntentType.HOLD_IN_RESERVE, "", reason, now);
                return;
            }
        }

        // Step 7: Local mutual support (relieve an endangered friendly division on own initiative)
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
            div.targetFriendlyDivision = endangeredFriendlyDiv;
            computeReinforceStation(engine, div, endangeredFriendlyDiv, div.stationPoint, myGroup);
            String reason = (activeMission == DivisionMissionType.RESERVE || div.isDesignatedReserve)
                    ? String.format("reserve called forward to reinforce (local ratio %.2f)", endangeredFriendlyDiv.localForceRatio)
                    : String.format("hard pressed, local ratio %.2f", endangeredFriendlyDiv.localForceRatio);
            commitIntent(engine, div, DivisionIntentType.REINFORCE_DIVISION, endangeredFriendlyDiv.displayName, reason, now);
            return;
        }

        // Step 8: Cohesion check (reform core cluster ONLY when out of enemy contact)
        boolean enterCloseUp = div.stationKeepingRatio > 1.48f;
        boolean stayClosingUp = (prevIntent == DivisionIntentType.CLOSE_UP) && div.stationKeepingRatio > 1.08f;
        boolean outOfEnemyContact = div.localEnemyStrength < 8f && distToVisibleEnemy >= 2100f;
        if ((enterCloseUp || stayClosingUp) && div.coreMemberCount >= 2 && outOfEnemyContact) {
            if (div.flagship != null && div.flagship.isAlive() && !div.flagship.isHulk()
                    && !div.detachedStragglerIds.contains(div.flagship.getId())) {
                div.stationPoint.set(div.flagship.getLocation());
            } else {
                div.stationPoint.set(div.centroid);
            }
            String reason = String.format("stations spread to %.0fu, limit %.0fu", div.spreadRadius, div.stationKeepingRadius);
            commitIntent(engine, div, DivisionIntentType.CLOSE_UP, "", reason, now);
            return;
        }

        // Step 9: Configured Opening / Main Mission execution (Carrier Missions a..f and Combat Missions)
        boolean strikeCooldownElapsed = (now - div.lastBreakOffTimestamp) >= 12.0f;
        boolean soloStrikeAllowed = isSoloStrikeAllowed(myGroup, distToMissileTarget);

        if (activeMission.isCarrierMission && trySelectCarrierMissionIntent(
                engine, div, myGroup, enemyGroup, activeMission, missionPhaseTag,
                nearestEnemyDiv, endangeredFriendlyDiv, heavyFriendlyToScreen, uncapturedObj, now)) {
            return;
        }

        if (trySelectCombatMissionIntent(
                engine, div, myGroup, activeMission, missionPhaseTag, prevIntent,
                nearestEnemyDiv, missileTargetEnemyDiv, heavyFriendlyToScreen,
                distToVisibleEnemy, distToMissileTarget, strikeCooldownElapsed, soloStrikeAllowed, martyrdom, now)) {
            return;
        }

        // Steps 10-13: Fallback screening, opportunistic torpedo/flank strikes, and line advance
        selectFallbackTacticalIntent(
                engine, div, myGroup, prevIntent, isHeavyLineOrCarrier,
                nearestEnemyDiv, missileTargetEnemyDiv, heavyFriendlyToScreen, uncapturedObj,
                distToVisibleEnemy, distToMissileTarget, strikeCooldownElapsed, soloStrikeAllowed, now);
    }

    private static boolean trySelectWithdrawalOrVentIntent(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            DivisionIntentType prevIntent,
            DivisionState nearestEnemyDiv,
            DivisionState heavyFriendlyToScreen,
            float now) {

        // 1. Torpedo strike window finished / torpedoes expended / flux built up -> Strike-then-withdraw to reserve
        if (isStrikeRunOver(div, now)) {
            div.targetEnemyDivision = nearestEnemyDiv;
            div.stationPoint.set(myGroup.reserveStation);
            div.lastBreakOffTimestamp = now;
            String targetLabel = nearestEnemyDiv != null ? nearestEnemyDiv.displayName : "contact zone";
            String reason = String.format("avg flux %.0f%%", div.avgFluxLevel * 100f);
            return commitIntent(engine, div, DivisionIntentType.BREAK_OFF_AND_RETIRE, targetLabel, reason, now);
        }

        // 2. High flux / damaged division -> Rotate to reserve behind a friendly division
        // Hold HAUL_OUT_TO_VENT until BOTH average flux and the most fluxed ship (e.g. a second Hammerhead)
        // have vented down to safe levels, preventing premature re-entry into L/U crossfire pockets at 50-75% flux!
        boolean stillRotating = prevIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                && (div.avgFluxLevel >= div.commander.rotationFluxThreshold * 0.55f
                || (div.maxMemberFluxLevel >= 0.45f && (div.localEnemyStrength >= 10f || div.crossfireCordonIndex >= 0.30f))
                || (div.crossfireCordonIndex >= 0.38f && div.avgFluxLevel >= 0.28f));
        boolean fightingRetirementVent = (myGroup.currentPhase == BattlePhase.FIGHTING_RETIREMENT && div.avgFluxLevel >= 0.32f);
        if (isUnderHighFluxPressure(div, myGroup) || stillRotating || fightingRetirementVent) {
            DivisionState candidateFriend = (myGroup.mainEffortDivision != null && myGroup.mainEffortDivision != div)
                    ? myGroup.mainEffortDivision
                    : heavyFriendlyToScreen;
            DivisionState ventBehindFriend = null;
            if (candidateFriend != null
                    && candidateFriend.isCombatEffective()
                    && candidateFriend.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT
                    && candidateFriend.currentIntent != DivisionIntentType.BREAK_OFF_AND_RETIRE
                    && (div.archetype.isCarrierArchetype() || !candidateFriend.archetype.isCarrierArchetype())) {
                // Verify that the point behind candidateFriend is actually safer / further from the enemy than our
                // current position (so a frigate division never tries to "vent behind" a reckless battleship that
                // has charged deep into the middle of the enemy fleet!).
                float retreatDirY = (div.side == 0) ? -1f : 1f;
                Vector2f behindPos = new Vector2f(candidateFriend.centroid.x, candidateFriend.centroid.y + retreatDirY * 1100f);
                boolean anchorSafe = true;
                if (nearestEnemyDiv != null && nearestEnemyDiv.isCombatEffective()) {
                    Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv);
                    if (distance(behindPos, ePos) < Math.min(1150f, distance(div.centroid, ePos) + 150f)) {
                        anchorSafe = false;
                    }
                }
                if (anchorSafe) {
                    ventBehindFriend = candidateFriend;
                }
            }

            div.targetFriendlyDivision = ventBehindFriend;
            String targetLabel;
            if (ventBehindFriend != null) {
                float retreatDirY = (div.side == 0) ? -1f : 1f;
                div.stationPoint.set(ventBehindFriend.centroid.x, ventBehindFriend.centroid.y + retreatDirY * 1100f);
                targetLabel = ventBehindFriend.displayName;
            } else {
                div.stationPoint.set(myGroup.reserveStation);
                targetLabel = "the reserve station";
            }
            String reason = String.format("flux %.0f%%, local ratio %.2f", div.avgFluxLevel * 100f, div.localForceRatio);
            return commitIntent(engine, div, DivisionIntentType.HAUL_OUT_TO_VENT, targetLabel, reason, now);
        }
        return false;
    }

    private static String assignCarrierStandoffOrReserveStation(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            DivisionState carrierTargetDiv) {
        div.targetEnemyDivision = carrierTargetDiv;
        if (carrierTargetDiv != null) {
            computeStandoffStation(engine, div, carrierTargetDiv, div.stationPoint);
            return carrierTargetDiv.displayName;
        }
        div.stationPoint.set(myGroup.reserveStation);
        return "the contact zone";
    }

    private static boolean trySelectCarrierMissionIntent(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            DivisionMissionType activeMission,
            String missionPhaseTag,
            DivisionState nearestEnemyDiv,
            DivisionState endangeredFriendlyDiv,
            DivisionState heavyFriendlyToScreen,
            BattleObjectiveAPI uncapturedObj,
            float now) {

        DivisionState carrierTargetDiv = nearestEnemyDiv;
        if (activeMission == DivisionMissionType.ALPHA_STRIKE
                && myGroup.primaryThreatEnemyDivision != null
                && OtcAI.isDivisionVisibleToSide(engine, div.side, myGroup.primaryThreatEnemyDivision)) {
            carrierTargetDiv = myGroup.primaryThreatEnemyDivision;
        } else if (activeMission == DivisionMissionType.COMBAT_SPACE_PATROL
                && endangeredFriendlyDiv != null) {
            DivisionState threatToFriend = findBestEnemyDivisionFor(engine, endangeredFriendlyDiv, myGroup, enemyGroup, false, div.targetEnemyDivision);
            if (threatToFriend != null) {
                carrierTargetDiv = threatToFriend;
            }
        }

        String baseReason = missionPhaseTag + activeMission.shortName;
        switch (activeMission) {
            case ARMED_RECONNAISSANCE: {
                div.targetEnemyDivision = carrierTargetDiv;
                String targetLabel;
                if (carrierTargetDiv != null) {
                    computeStandoffStation(engine, div, carrierTargetDiv, div.stationPoint);
                    targetLabel = carrierTargetDiv.displayName;
                } else if (uncapturedObj != null) {
                    div.targetObjective = uncapturedObj;
                    div.stationPoint.set(div.centroid.x * 0.7f, div.centroid.y + (div.side == 0 ? 700f : -700f));
                    targetLabel = uncapturedObj.getDisplayName();
                } else {
                    div.stationPoint.set(myGroup.reserveStation);
                    targetLabel = "the contact zone";
                }
                return commitIntent(engine, div, DivisionIntentType.FLY_ARMED_RECONNAISSANCE, targetLabel, baseReason, now);
            }
            case SPACE_SUPERIORITY: {
                String targetLabel = assignCarrierStandoffOrReserveStation(engine, div, myGroup, carrierTargetDiv);
                return commitIntent(engine, div, DivisionIntentType.FLY_FIGHTER_SWEEP, targetLabel, baseReason, now);
            }
            case COMBAT_SPACE_PATROL: {
                DivisionState protectedFriendly = (endangeredFriendlyDiv != null)
                        ? endangeredFriendlyDiv
                        : ((heavyFriendlyToScreen != null) ? heavyFriendlyToScreen : myGroup.mainEffortDivision);
                div.targetFriendlyDivision = protectedFriendly;
                div.targetEnemyDivision = carrierTargetDiv;
                String targetLabel;
                if (protectedFriendly != null && protectedFriendly != div) {
                    computePatrolStation(div, protectedFriendly, div.stationPoint);
                    targetLabel = protectedFriendly.displayName;
                } else if (carrierTargetDiv != null) {
                    computeStandoffStation(engine, div, carrierTargetDiv, div.stationPoint);
                    targetLabel = carrierTargetDiv.displayName;
                } else {
                    div.stationPoint.set(myGroup.reserveStation);
                    targetLabel = "the battle line";
                }
                return commitIntent(engine, div, DivisionIntentType.FLY_COMBAT_SPACE_PATROL, targetLabel, baseReason, now);
            }
            case CYCLIC_STRIKES: {
                String targetLabel = assignCarrierStandoffOrReserveStation(engine, div, myGroup, carrierTargetDiv);
                return commitIntent(engine, div, DivisionIntentType.FLY_CYCLIC_STRIKES, targetLabel, baseReason, now);
            }
            case DECKLOAD_STRIKE: {
                String targetLabel = assignCarrierStandoffOrReserveStation(engine, div, myGroup, carrierTargetDiv);
                return commitIntent(engine, div, DivisionIntentType.LAUNCH_DECKLOAD_STRIKE, targetLabel, baseReason, now);
            }
            case ALPHA_STRIKE: {
                String targetLabel = assignCarrierStandoffOrReserveStation(engine, div, myGroup, carrierTargetDiv);
                String syncPartner = (myGroup.mainEffortDivision != null && myGroup.mainEffortDivision != div)
                        ? (" with \"" + myGroup.mainEffortDivision.displayName + "\"")
                        : "";
                return commitIntent(engine, div, DivisionIntentType.LAUNCH_ALPHA_STRIKE, targetLabel, baseReason + syncPartner, now);
            }
            default:
                return false;
        }
    }

    private static boolean trySelectCombatMissionIntent(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            DivisionMissionType activeMission,
            String missionPhaseTag,
            DivisionIntentType prevIntent,
            DivisionState nearestEnemyDiv,
            DivisionState missileTargetEnemyDiv,
            DivisionState heavyFriendlyToScreen,
            float distToVisibleEnemy,
            float distToMissileTarget,
            boolean strikeCooldownElapsed,
            boolean soloStrikeAllowed,
            boolean martyrdom,
            float now) {

        boolean inMissileStrike = (prevIntent == DivisionIntentType.HARASSING_MISSILE_ATTACK
                || prevIntent == DivisionIntentType.TOT_MISSILE_STRIKE
                || prevIntent == DivisionIntentType.TOT_ALPHA_STRIKE);
        boolean inFlankManeuver = (prevIntent == DivisionIntentType.WORK_FLANK);

        if (activeMission == DivisionMissionType.TOT_ALPHA_STRIKE && nearestEnemyDiv != null) {
            DivisionState alphaTarget = (missileTargetEnemyDiv != null && distToMissileTarget <= 4400f)
                    ? missileTargetEnemyDiv
                    : nearestEnemyDiv;
            if (strikeCooldownElapsed && div.avgFluxLevel < 0.48f) {
                Vector2f visTargetPos = OtcAI.getVisibleCentroidForSide(engine, div.side, alphaTarget);
                div.targetEnemyDivision = alphaTarget;
                div.stationPoint.set(visTargetPos);
                if (prevIntent != DivisionIntentType.TOT_ALPHA_STRIKE) {
                    div.strikeCommittedTimestamp = now;
                }
                String reason = missionPhaseTag + activeMission.shortName + " (all guns & torpedoes)";
                return commitIntent(engine, div, DivisionIntentType.TOT_ALPHA_STRIKE,
                        alphaTarget.displayName, reason, now);
            } else {
                // While Strike Cooldown is recharging between passes, hold standoff outside enemy gun range
                div.targetEnemyDivision = alphaTarget;
                computeStandoffStation(engine, div, alphaTarget, div.stationPoint);
                return commitIntent(engine, div, DivisionIntentType.CLOSE_AND_ENGAGE,
                        alphaTarget.displayName, missionPhaseTag + "re-forming after ToT Alpha Strike", now);
            }
        }

        if (activeMission == DivisionMissionType.SCREEN && heavyFriendlyToScreen != null) {
            div.targetFriendlyDivision = heavyFriendlyToScreen;
            div.targetEnemyDivision = nearestEnemyDiv;
            computeScreeningStation(engine, div, heavyFriendlyToScreen, nearestEnemyDiv, div.stationPoint, div.side);
            return commitIntent(engine, div, DivisionIntentType.TAKE_SCREENING_STATION,
                    heavyFriendlyToScreen.displayName, missionPhaseTag + activeMission.shortName, now);
        }

        if (activeMission == DivisionMissionType.ENVELOPMENT && nearestEnemyDiv != null) {
            if (strikeCooldownElapsed
                    && soloStrikeAllowed
                    && div.hasMissileStrikeCapability
                    && div.usableStrikeAmmoCount > 0
                    && div.avgFluxLevel < 0.55f
                    && missileTargetEnemyDiv != null
                    && distToMissileTarget <= 4600f) {
                Vector2f visTargetPos = OtcAI.getVisibleCentroidForSide(engine, div.side, missileTargetEnemyDiv);
                div.targetEnemyDivision = missileTargetEnemyDiv;
                div.stationPoint.set(visTargetPos);
                if (!inMissileStrike) {
                    div.strikeCommittedTimestamp = now;
                }
                String reason = String.format("%s%s (%d salvos)", missionPhaseTag, activeMission.shortName, div.usableStrikeAmmoCount);
                return commitIntent(engine, div, DivisionIntentType.HARASSING_MISSILE_ATTACK,
                        missileTargetEnemyDiv.displayName, reason, now);
            }
            if (strikeCooldownElapsed && div.avgFluxLevel < 0.58f) {
                div.targetEnemyDivision = nearestEnemyDiv;
                computeFlankStation(engine, div, nearestEnemyDiv, div.stationPoint, myGroup);
                if (!inFlankManeuver) {
                    div.strikeCommittedTimestamp = now;
                }
                String reason = missionPhaseTag + activeMission.shortName + flankSideTag(div, nearestEnemyDiv, myGroup);
                return commitIntent(engine, div, DivisionIntentType.WORK_FLANK,
                        nearestEnemyDiv.displayName, reason, now);
            }
        }

        if ((activeMission == DivisionMissionType.STANDOFF_ENGAGEMENT
                || activeMission == DivisionMissionType.SCOUTING)
                && nearestEnemyDiv != null) {
            div.targetEnemyDivision = nearestEnemyDiv;
            computeStandoffStation(engine, div, nearestEnemyDiv, div.stationPoint);
            String reason = String.format("%s%s (standoff range %.0fu)", missionPhaseTag, activeMission.shortName, div.standoffRange);
            return commitIntent(engine, div, DivisionIntentType.CLOSE_AND_ENGAGE,
                    nearestEnemyDiv.displayName, reason, now);
        }

        if (martyrdom && nearestEnemyDiv != null) {
            Vector2f visEnemyPos = OtcAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv);
            div.targetEnemyDivision = nearestEnemyDiv;
            div.stationPoint.set(visEnemyPos);
            return commitIntent(engine, div, DivisionIntentType.PRESS_HOME,
                    nearestEnemyDiv.displayName, missionPhaseTag + "Martyrdom", now);
        }

        if (activeMission == DivisionMissionType.CLOSE_ACTION && nearestEnemyDiv != null) {
            Vector2f visEnemyPos = OtcAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv);
            div.targetEnemyDivision = nearestEnemyDiv;
            div.stationPoint.set(visEnemyPos);
            DivisionIntentType intent = (div.localForceRatio >= 0.88f && distToVisibleEnemy < 3400f)
                    ? DivisionIntentType.PRESS_HOME
                    : DivisionIntentType.CLOSE_AND_ENGAGE;
            return commitIntent(engine, div, intent,
                    nearestEnemyDiv.displayName, missionPhaseTag + activeMission.shortName, now);
        }

        return false;
    }

    private static void selectFallbackTacticalIntent(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            DivisionIntentType prevIntent,
            boolean isHeavyLineOrCarrier,
            DivisionState nearestEnemyDiv,
            DivisionState missileTargetEnemyDiv,
            DivisionState heavyFriendlyToScreen,
            BattleObjectiveAPI uncapturedObj,
            float distToVisibleEnemy,
            float distToMissileTarget,
            boolean strikeCooldownElapsed,
            boolean soloStrikeAllowed,
            float now) {

        boolean inMissileStrike = (prevIntent == DivisionIntentType.HARASSING_MISSILE_ATTACK
                || prevIntent == DivisionIntentType.TOT_MISSILE_STRIKE
                || prevIntent == DivisionIntentType.TOT_ALPHA_STRIKE);
        boolean inFlankManeuver = (prevIntent == DivisionIntentType.WORK_FLANK);

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
                div.targetFriendlyDivision = heavyFriendlyToScreen;
                div.targetEnemyDivision = nearestEnemyDiv;
                computeScreeningStation(engine, div, heavyFriendlyToScreen, nearestEnemyDiv, div.stationPoint, div.side);
                commitIntent(engine, div, DivisionIntentType.TAKE_SCREENING_STATION,
                        heavyFriendlyToScreen.displayName, "forward PD and anti-flank screen", now);
                return;
            }
        }

        // 11. Torpedo / Strike Division: Harassing torpedo attack or Time-on-Target strike (<= 4600u)
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
            // Opportunistic ToT Alpha Strike: Torpedo Frigates or Destroyers/Cruisers exploiting a stranded/vulnerable
            // capital ship or high-flux heavy target!
            boolean strandedCapitalTarget = (missileTargetEnemyDiv.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                    || missileTargetEnemyDiv.archetype.isCarrierArchetype()
                    || missileTargetEnemyDiv.isWeakPoint)
                    && (missileTargetEnemyDiv.getAliveMembers().size() <= 2
                    || missileTargetEnemyDiv.avgFluxLevel >= 0.42f
                    || missileTargetEnemyDiv.isWeakPoint)
                    && (div.archetype == DivisionArchetype.FRIGATE_DIVISION
                    || div.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION
                    || div.archetype == DivisionArchetype.DESTROYER_DIVISION
                    || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION
                    || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION)
                    && div.getAliveMembers().size() >= 2
                    && div.avgFluxLevel < 0.38f;

            DivisionIntentType strikeIntent = strandedCapitalTarget
                    ? DivisionIntentType.TOT_ALPHA_STRIKE
                    : (timeOnTargetCue
                    ? DivisionIntentType.TOT_MISSILE_STRIKE
                    : DivisionIntentType.HARASSING_MISSILE_ATTACK);
            String reason = strandedCapitalTarget
                    ? ("ToT Alpha Strike on vulnerable " + missileTargetEnemyDiv.displayName)
                    : (timeOnTargetCue
                    ? ("time-on-target with \"" + myGroup.mainEffortDivision.displayName + "\"")
                    : String.format("%d missile salvos ready", div.usableStrikeAmmoCount));

            Vector2f visTargetPos = OtcAI.getVisibleCentroidForSide(engine, div.side, missileTargetEnemyDiv);
            div.targetEnemyDivision = missileTargetEnemyDiv;
            div.stationPoint.set(visTargetPos);
            if (!inMissileStrike) {
                div.strikeCommittedTimestamp = now;
            }
            commitIntent(engine, div, strikeIntent, missileTargetEnemyDiv.displayName, reason, now);
            return;
        }

        // 12. Fast flankers swing wide to flank visible enemy division within operational contact range (<= 4500u)
        if (strikeCooldownElapsed
                && div.isFastFlanker
                && nearestEnemyDiv != null
                && distToVisibleEnemy <= 4500f
                && myGroup.mainEffortDivision != null
                && myGroup.mainEffortDivision != div
                && div.avgFluxLevel < 0.52f) {
            div.targetEnemyDivision = nearestEnemyDiv;
            computeFlankStation(engine, div, nearestEnemyDiv, div.stationPoint, myGroup);
            if (!inFlankManeuver) {
                div.strikeCommittedTimestamp = now;
            }
            String reason = "envelopment" + flankSideTag(div, nearestEnemyDiv, myGroup);
            commitIntent(engine, div, DivisionIntentType.WORK_FLANK, nearestEnemyDiv.displayName, reason, now);
            return;
        }

        // 13. Press local overmatch vs standard line advance (strictly respecting Fog of War)
        if (nearestEnemyDiv != null) {
            Vector2f visEnemyPos = OtcAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv);
            div.targetEnemyDivision = nearestEnemyDiv;
            div.stationPoint.set(visEnemyPos);

            DivisionIntentType intent;
            String reason;
            if (div.localForceRatio >= Math.max(1.22f, div.commander.commitForceRatio) && distToVisibleEnemy < 3200f) {
                intent = DivisionIntentType.PRESS_HOME;
                reason = String.format("local advantage %.2fx", div.localForceRatio);
            } else {
                intent = DivisionIntentType.CLOSE_AND_ENGAGE;
                reason = "keeping the line";
            }
            commitIntent(engine, div, intent, nearestEnemyDiv.displayName, reason, now);
        } else if (uncapturedObj != null && !isHeavyLineOrCarrier) {
            div.targetObjective = uncapturedObj;
            div.stationPoint.set(uncapturedObj.getLocation());
            commitIntent(engine, div, DivisionIntentType.TAKE_AND_HOLD,
                    uncapturedObj.getDisplayName(), "advancing without contact", now);
        } else {
            float scoutDirY = (div.side == 0) ? 1400f : -1400f;
            div.currentIntent = DivisionIntentType.CLOSE_AND_ENGAGE;
            div.stationPoint.set(div.centroid.x * 0.6f, div.centroid.y + scoutDirY);
            div.lastIntentChangeTime = now;
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
                        || prevIntent == DivisionIntentType.TOT_ALPHA_STRIKE
                        || prevIntent == DivisionIntentType.LAUNCH_ALPHA_STRIKE;
                div.targetEnemyDivision = target;
                if (div.archetype.isCarrierArchetype()) {
                    div.currentIntent = DivisionIntentType.LAUNCH_ALPHA_STRIKE;
                    computeStandoffStation(engine, div, target, div.stationPoint);
                } else if (activeMission == DivisionMissionType.TOT_ALPHA_STRIKE
                        || !div.hasMissileStrikeCapability
                        || div.archetype == DivisionArchetype.FRIGATE_DIVISION
                        || div.archetype == DivisionArchetype.DESTROYER_DIVISION
                        || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION) {
                    div.currentIntent = DivisionIntentType.TOT_ALPHA_STRIKE;
                    div.stationPoint.set(OtcAI.getVisibleCentroidForSide(engine, div.side, target));
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
                div.targetFriendlyDivision = friend;
                computeReinforceStation(engine, div, friend, div.stationPoint, myGroup);
                return commitIntent(engine, div, DivisionIntentType.REINFORCE_DIVISION, friend.displayName, reason, now);
            }
            case HOLD_RESERVE: {
                div.stationPoint.set(myGroup.reserveStation);
                return commitIntent(engine, div, DivisionIntentType.HOLD_IN_RESERVE, "", reason, now);
            }
            case GENERAL_CHASE: {
                if (!targetValid
                        || div.avgFluxLevel >= 0.58f
                        || div.maxMemberFluxLevel >= 0.78f
                        || (!binding && div.avgFluxLevel >= 0.50f)) {
                    return false;
                }
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

        div.lastIntentChangeTime = now;
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

    /** Recomputes the current intent's maneuver station and out-of-contact wide-arc route waypoint every tick. */
    private static void refreshStationPoint(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            float now) {
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
                    computeReinforceStation(engine, div, friend, div.stationPoint, myGroup);
                }
                break;
            case PRESS_HOME:
            case HARASSING_MISSILE_ATTACK:
            case TOT_MISSILE_STRIKE:
            case TOT_ALPHA_STRIKE:
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
            case CLOSE_UP:
                if (div.flagship != null && div.flagship.isAlive() && !div.flagship.isHulk()
                        && !div.detachedStragglerIds.contains(div.flagship.getId())) {
                    div.stationPoint.set(div.flagship.getLocation());
                } else {
                    div.stationPoint.set(div.centroid);
                }
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

        updateDivisionOutOfContactRoute(engine, div, myGroup, enemyGroup);
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
            BattleGroupState enemyGroup,
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

            // Individual Ship Crisis Self-Preservation & Lone Frigate Anti-Suicide Valve:
            //  1. If an individual ship is at >= 82% flux, overloaded/venting, or critically hull-damaged under fire,
            //     let it back off and vent.
            //  2. If a Frigate is alone (alive.size() == 1) or outnumbered (localForceRatio < 0.65f) under enemy fire,
            //     prevent vanilla BasicShipAI's "helpless enemy overmatch" logic from making the lone frigate dive to
            //     its death and get turned into swiss cheese — force BACK_OFF + DO_NOT_PURSUE + retreat vector!
            boolean martyrdom = isMartyrdomActive(div, myGroup, now)
                    || (myGroup.isPlayEnabled(OtcPlay.MARTYRDOM) && isVbiedOrFanaticHull(ship));
            boolean isDetachedStraggler = div.detachedStragglerIds.contains(ship.getId());
            if (isDetachedStraggler) {
                // Detached straggler: route around any intervening enemy battle line out of contact to rejoin the Core Cluster,
                // without freezing the Core Cluster in CLOSE_UP!
                Vector2f rejoinGoal = (div.flagship != null && div.flagship.isAlive() && !div.flagship.isHulk()
                        && !div.detachedStragglerIds.contains(div.flagship.getId()))
                        ? div.flagship.getLocation()
                        : div.centroid;
                Float prevSideObj = div.stragglerSkirtSideByShipId.get(ship.getId());
                float prevSkirtSide = prevSideObj != null ? prevSideObj : div.routeSkirtSide;
                float[] chosenSide = new float[]{prevSkirtSide};
                Vector2f rejoinWaypoint = computeOutOfContactRoutePoint(
                        engine,
                        div.side,
                        ship.getLocation(),
                        rejoinGoal,
                        null,
                        null,
                        prevSkirtSide,
                        enemyGroup,
                        chosenSide);
                div.stragglerSkirtSideByShipId.put(ship.getId(), chosenSide[0]);

                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, rejoinWaypoint);
                // If an enemy division is blocking the chord and pressing close to the straggler, keep distance while skirting
                if (ship.getFluxLevel() >= 0.42f || isShipNearVisibleEnemy(engine, div.side, ship.getLocation(), enemyGroup, 1450f)) {
                    flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
                }
                continue;
            } else {
                div.stragglerSkirtSideByShipId.remove(ship.getId());
            }

            boolean isFrigateHull = ship.isFrigate();
            boolean loneOrOutnumberedFrigateInDanger = isFrigateHull
                    && (alive.size() == 1 || (div.localForceRatio < 0.55f && ship.getFluxLevel() >= 0.58f))
                    && div.localEnemyStrength >= 8f
                    && (ship.getFluxLevel() >= 0.44f
                    || (ship.getFluxTracker() != null && ship.getFluxTracker().getHardFlux() / Math.max(1f, ship.getFluxTracker().getMaxFlux()) >= 0.35f)
                    || (ship.getHullLevel() < 0.72f && ship.getFluxLevel() >= 0.25f));
            boolean shipInPersonalCrisis = !martyrdom
                    && div.localEnemyStrength >= 8f
                    && div.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT
                    && div.currentIntent != DivisionIntentType.BREAK_OFF_AND_RETIRE
                    && (loneOrOutnumberedFrigateInDanger
                    || (ship.getFluxTracker() != null && ship.getFluxTracker().isOverloadedOrVenting())
                    || ship.getFluxLevel() >= 0.82f
                    || (ship.getHullLevel() < 0.42f && ship.getFluxLevel() >= 0.45f));
            if (shipInPersonalCrisis) {
                flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
                flags.unsetFlag(AIFlags.HARASS_MOVE_IN);
                flags.unsetFlag(AIFlags.PURSUING);
                flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL * 1.4f);
                flags.setFlag(AIFlags.RUN_QUICKLY, FLAG_TTL * 1.4f);
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL * 1.4f);
                if (loneOrOutnumberedFrigateInDanger && myGroup != null && myGroup.reserveStation != null) {
                    flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, myGroup.reserveStation);
                }
                continue;
            }

            Vector2f slotPos = computeShipFormationSlot(engine, ship, shipIdx, alive, div, myGroup);
            boolean crowdedByPeer = isShipCrowdedByDivisionPeer(ship, alive, div.stationSpacing * 0.72f);

            float distToDivCenter = distance(ship.getLocation(), div.centroid);
            boolean shipStraggling = div.coreMemberCount >= 2 && distToDivCenter > (div.stationKeepingRadius * 1.38f);

            // Bounded pursuit & cohesion leash: prevent lone ships from chasing bait 3000u away
            if (shipStraggling && div.currentIntent != DivisionIntentType.HARASSING_MISSILE_ATTACK
                    && div.currentIntent != DivisionIntentType.TOT_MISSILE_STRIKE) {
                float[] chosenSide = new float[]{div.routeSkirtSide};
                Vector2f safeSlotPos = computeOutOfContactRoutePoint(
                        engine,
                        div.side,
                        ship.getLocation(),
                        slotPos,
                        null,
                        null,
                        div.routeSkirtSide,
                        enemyGroup,
                        chosenSide);
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, safeSlotPos);
                continue;
            }

            applyIntentFlagsToShip(engine, ship, shipIdx, flags, slotPos, crowdedByPeer, focusEnemyShip, div, myGroup, enemyGroup, now);
            dressShipStrikeCraftInFormation(ship, focusEnemyShip, div);
        }
    }

    /**
     * Integrates a ship's Strike Craft (FighterWingAPI squadrons) into the division's formation and tactical intent:
     *  - During CLOSE_UP, HOLD_IN_RESERVE, MARSHAL_FOR_STRIKE, or out-of-contact HAUL_OUT_TO_VENT, pulls strike craft
     *    into a tight protective formation screen over the division.
     *  - During offensive/patrol intents, releases strike craft onto the division's focus target while keeping
     *    straggling wingmate fighters dressed on their squadron wing leader during approach.
     */
    private static void dressShipStrikeCraftInFormation(
            ShipAPI carrier,
            ShipAPI focusEnemyShip,
            DivisionState div) {
        if (carrier == null || div == null) return;
        List<com.fs.starfarer.api.combat.FighterWingAPI> wings = carrier.getAllWings();
        if (wings == null || wings.isEmpty()) return;

        boolean holdFightersInFormation = div.currentIntent == DivisionIntentType.CLOSE_UP
                || div.currentIntent == DivisionIntentType.HOLD_IN_RESERVE
                || div.currentIntent == DivisionIntentType.MARSHAL_FOR_STRIKE
                || (div.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT && div.localEnemyStrength < 10f);

        if (holdFightersInFormation) {
            if (!carrier.isPullBackFighters()) {
                carrier.setPullBackFighters(true);
                div.fighterPullbackShipIds.add(carrier.getId());
            }
        } else if (div.fighterPullbackShipIds.remove(carrier.getId())) {
            carrier.setPullBackFighters(false);
        }

        ShipAPI escortAnchor = (div.flagship != null && div.flagship.isAlive() && !div.flagship.isHulk())
                ? div.flagship
                : carrier;

        for (com.fs.starfarer.api.combat.FighterWingAPI wing : wings) {
            if (wing == null) continue;
            List<ShipAPI> wingMembers = wing.getWingMembers();
            if (wingMembers == null || wingMembers.isEmpty()) continue;
            ShipAPI wingLeader = wing.getLeader();
            if (wingLeader == null || !wingLeader.isAlive()) {
                wingLeader = wingMembers.get(0);
            }
            for (ShipAPI fighter : wingMembers) {
                if (fighter == null || !fighter.isAlive() || fighter.isHulk()) continue;
                ShipwideAIFlags fFlags = fighter.getAIFlags();
                if (fFlags == null) continue;

                if (holdFightersInFormation) {
                    fFlags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    fFlags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, escortAnchor);
                } else if (focusEnemyShip != null && focusEnemyShip.isAlive()) {
                    fFlags.setFlag(AIFlags.MANEUVER_TARGET, FLAG_TTL, focusEnemyShip);
                    // Keep wingmen fighters dressed in squadron formation with their wingLeader on the approach leg
                    if (fighter != wingLeader && wingLeader != null && wingLeader.isAlive()) {
                        float dLeader = distance(fighter.getLocation(), wingLeader.getLocation());
                        float dEnemy = distance(fighter.getLocation(), focusEnemyShip.getLocation());
                        if (dLeader > 420f && dEnemy > 900f) {
                            fFlags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, wingLeader);
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns true ONLY when a division advancing on a battlefield objective (TAKE_AND_HOLD) should advance
     * cautiously step-by-step in tight formation rather than racing at full meeting-engagement speed:
     *   1. The enemy already has Space control over the battlefield: attacking an enemy Station, OR
     *      the friendly fleet is being pursued in a Pursuit / Fighting Retreat battle.
     *   2. Enemy forces / Enemy Space already contest or threaten the objective (or are within 2300u of the division).
     *   3. The division commander has a Cautious / Timid temperament (aggressionBias < 0.85f).
     * In a standard Starsector pitched battle (Meeting Engagement) where the objective is uncontested at battle start,
     * returns false so divisions advance briskly at full speed to seize the objective!
     */
    public static boolean shouldAdvanceCautiouslyOnObjective(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup) {
        if (div == null) return false;
        // 1. Cautious or Timid division commander naturally advances cautiously
        if (div.commander != null && div.commander.aggressionBias < 0.85f) {
            return true;
        }
        // 2. Pursuit battle (enemy is pursuing us) or Station assault (enemy station already dominates space)
        if (myGroup != null && myGroup.missionContext == DivisionModel.MissionContextType.REARGUARD_ACTION) {
            return true;
        }
        if (engine != null) {
            try {
                if (div.side == 0 && engine.getContext() != null
                        && engine.getContext().getPlayerGoal() == com.fs.starfarer.api.fleet.FleetGoal.ESCAPE) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            List<ShipAPI> allShips = engine.getShips();
            if (allShips != null) {
                for (ShipAPI s : allShips) {
                    if (s == null || !s.isAlive() || s.isHulk()) continue;
                    if (s.getOwner() != div.side && (s.isStation() || s.isStationModule() || s.getParentStation() != null)) {
                        return true;
                    }
                }
            }
        }
        // 3. Visible enemy division or Enemy Space already contests the objective or presses near this division
        if (div.localEnemyStrength >= 8f || div.hostileSpacePressure > 6f) {
            return true;
        }
        Vector2f objLoc = (div.targetObjective != null && div.targetObjective.getLocation() != null)
                ? div.targetObjective.getLocation()
                : div.stationPoint;
        if (enemyGroup != null && objLoc != null) {
            for (DivisionState eDiv : enemyGroup.divisions) {
                if (eDiv == null || !eDiv.isCombatEffective()) continue;
                if (!OtcAI.isDivisionVisibleToSide(engine, div.side, eDiv)) continue;
                Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, eDiv);
                float enemyReach = Math.max(2100f, eDiv.spacePotentialRadius);
                if (distance(objLoc, ePos) <= enemyReach || distance(div.centroid, ePos) <= 2300f) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void applyIntentFlagsToShip(
            CombatEngineAPI engine,
            ShipAPI ship,
            int shipIdx,
            ShipwideAIFlags flags,
            Vector2f slotPos,
            boolean crowdedByPeer,
            ShipAPI focusEnemyShip,
            DivisionState div,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            float now) {

        DivisionFormation activeForm = div.activeFormation != null
                ? div.activeFormation
                : DivisionFormation.LINE_ABREAST;
        boolean structuredFormation = (activeForm != DivisionFormation.NONE) && (div.getAliveMembers().size() >= 2);
        DivisionMovementStickiness stickiness = div.activeMovementStickiness != null
                ? div.activeMovementStickiness
                : DivisionMovementStickiness.fromStationKeepingMult(div.stationKeepingMult);
        div.activeMovementStickiness = stickiness;

        // Doctrinal Division Movement vs. Individual Combat Movement thresholds:
        //  - dressTol: formation slot tolerance during Division Movement (marching / out-of-contact approach)
        //  - individualEngageHorizon: distance to focusEnemyShip inside which Individual Combat Movement takes over
        //  - combatSlotLeash: how far a ship can maneuver individually in gun range before division stickiness pulls it back
        float dressTol = structuredFormation
                ? Math.max(150f, div.stationSpacing * 0.34f * Math.max(0.75f, stickiness.stationKeepingMult))
                : Math.max(480f, div.stationSpacing * 0.90f);
        final float individualEngageHorizon;
        final float combatSlotLeash;
        switch (stickiness) {
            case STRICT:
                individualEngageHorizon = Math.max(1350f, div.standoffRange * 1.25f);
                combatSlotLeash = dressTol * 1.35f;
                break;
            case LOOSE:
                individualEngageHorizon = Math.max(2050f, div.standoffRange * 1.75f);
                combatSlotLeash = dressTol * 2.65f;
                break;
            case FREE:
                individualEngageHorizon = Math.max(2600f, div.standoffRange * 2.25f);
                combatSlotLeash = Float.MAX_VALUE;
                break;
            case BALANCED:
            default:
                individualEngageHorizon = Math.max(1650f, div.standoffRange * 1.45f);
                combatSlotLeash = dressTol * 1.95f;
                break;
        }
        float shipDistToEnemy = (focusEnemyShip != null)
                ? distance(ship.getLocation(), focusEnemyShip.getLocation())
                : Float.MAX_VALUE;
        div.inIndividualCombatMovementMode = (focusEnemyShip != null && shipDistToEnemy <= individualEngageHorizon);

        switch (div.currentIntent) {
                case FORM_LINE_AND_FIX: {
                    // Hold the fixing position and fight from it; when practicing a structured formation,
                    // ships dress their formation slots while outside gun range, and use combatSlotLeash in gun range.
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    float distToEnemy = shipDistToEnemy;
                    float distToSlot = distance(ship.getLocation(), slotPos);
                    boolean inActiveGunRange = distToEnemy <= Math.max(1550f, div.standoffRange * 1.30f);
                    if (focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    }
                    if (structuredFormation) {
                        float activeTol = inActiveGunRange
                                ? Math.min(combatSlotLeash, Math.max(dressTol * 1.45f, 380f))
                                : dressTol;
                        if (distToSlot > activeTol) {
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        }
                    } else if ((!inActiveGunRange && (distToSlot > Math.max(450f, div.stationSpacing * 0.8f) || crowdedByPeer))
                            || distToSlot > Math.max(920f, div.stationSpacing * 1.65f)) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;
                }

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

                case TOT_ALPHA_STRIKE: {
                    // ToT Alpha Strike: rapidly close distance, focus fire with ALL available guns and torpedoes on a
                    // single target (such as a stranded/vulnerable capital ship), and then get out of dodge via
                    // isStrikeRunOver -> BREAK_OFF_AND_RETIRE!
                    flags.unsetFlag(AIFlags.BACK_OFF);
                    flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL * 1.5f);
                    flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL * 1.6f);
                    flags.setFlag(AIFlags.PURSUING, FLAG_TTL * 1.5f);
                    if (focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        float distToEnemy = distance(ship.getLocation(), focusEnemyShip.getLocation());
                        // Close rapidly into all-guns & torpedo effective range (~500u-750u)
                        float allGunsCloseRange = Math.max(480f, Math.min(760f, div.avgEngageRange * 0.72f));
                        if (distToEnemy > allGunsCloseRange) {
                            flags.setFlag(AIFlags.MOVEMENT_DEST, FLAG_TTL, focusEnemyShip.getLocation());
                        }
                        if (distToEnemy > 1650f && distance(ship.getLocation(), slotPos) > dressTol * 1.25f) {
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        }
                    } else if (div.targetEnemyDivision != null) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;
                }

                case PRESS_HOME:
                    flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
                    if (isMartyrdomActive(div, myGroup, now) || (myGroup.isPlayEnabled(OtcPlay.MARTYRDOM) && isVbiedOrFanaticHull(ship))) {
                        flags.setFlag(AIFlags.DO_NOT_VENT, FLAG_TTL * 2f);
                        flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL * 2f);
                        flags.setFlag(AIFlags.PURSUING, FLAG_TTL * 2f);
                        flags.unsetFlag(AIFlags.BACK_OFF);
                        flags.unsetFlag(AIFlags.RUN_QUICKLY);
                        if (focusEnemyShip != null) {
                            directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                            flags.setFlag(AIFlags.MOVEMENT_DEST, FLAG_TTL, focusEnemyShip.getLocation());
                            if (distance(ship.getLocation(), focusEnemyShip.getLocation()) > 420f) {
                                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, focusEnemyShip.getLocation());
                            }
                        } else if (div.targetEnemyDivision != null) {
                            flags.setFlag(AIFlags.MOVEMENT_DEST, FLAG_TTL, div.stationPoint);
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, div.stationPoint);
                        }
                        break;
                    }
                    if (focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        float distToEnemy = distance(ship.getLocation(), focusEnemyShip.getLocation());
                        boolean isWingman = (div.flagship != null && ship != div.flagship) || (div.flagship == null && shipIdx > 0);
                        if (distToEnemy > 2100f
                                || (structuredFormation && isWingman && distToEnemy > 950f
                                && ship.getFluxLevel() < 0.65f
                                && distance(ship.getLocation(), slotPos) > dressTol * 1.45f)) {
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
                    if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 1650f) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    }
                    if (distance(ship.getLocation(), slotPos) > (structuredFormation ? dressTol : Math.max(260f, div.stationSpacing * 0.55f))
                            || (!structuredFormation && crowdedByPeer && (focusEnemyShip == null || distance(ship.getLocation(), focusEnemyShip.getLocation()) > 1400f))) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case REINFORCE_DIVISION:
                    if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 1750f) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    }
                    if (div.targetFriendlyDivision != null && div.targetFriendlyDivision.shipNeedingHelp != null
                            && div.targetFriendlyDivision.shipNeedingHelp.isAlive()
                            && shipIdx == 0) {
                        flags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, div.targetFriendlyDivision.shipNeedingHelp);
                    } else {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case WORK_FLANK: {
                    float distToEnemy = (focusEnemyShip != null)
                            ? distance(ship.getLocation(), focusEnemyShip.getLocation())
                            : Float.MAX_VALUE;
                    float distToSlot = distance(ship.getLocation(), slotPos);
                    if (focusEnemyShip != null && !div.hasRouteWaypoint && distToEnemy < 1850f) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    }
                    if (div.hasRouteWaypoint
                            || distToSlot > (structuredFormation ? dressTol * 1.10f : Math.max(320f, div.stationSpacing * 0.65f))
                            || (!structuredFormation && crowdedByPeer && distToEnemy > 1450f)) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;
                }

                case TAKE_AND_HOLD: {
                    boolean cautiousAdvance = shouldAdvanceCautiouslyOnObjective(engine, div, myGroup, enemyGroup);
                    float distToGoal = distance(ship.getLocation(), div.stationPoint);
                    if (!cautiousAdvance && distToGoal > 850f) {
                        // Meeting Engagement brisk objective rush: do not brake in open uncontested space!
                        flags.unsetFlag(AIFlags.BACK_OFF);
                        flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
                        if (distance(ship.getLocation(), slotPos) > (structuredFormation ? dressTol * 1.45f : Math.max(320f, div.stationSpacing * 0.65f)) || crowdedByPeer) {
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        }
                    } else {
                        if (distance(ship.getLocation(), slotPos) > (structuredFormation ? dressTol : Math.max(320f, div.stationSpacing * 0.65f)) || crowdedByPeer) {
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        }
                    }
                    break;
                }
                case HOLD_IN_RESERVE:
                    if (distance(ship.getLocation(), slotPos) > (structuredFormation ? dressTol : Math.max(320f, div.stationSpacing * 0.65f)) || crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case CLOSE_UP:
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    if (distance(ship.getLocation(), slotPos) > Math.max(200f, div.stationSpacing * 0.42f) || crowdedByPeer) {
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
                        float distToSlot = distance(ship.getLocation(), slotPos);
                        int divAliveCount = div.getAliveMembers().size();
                        boolean isLoneOrOutnumberedFrig = ship.isFrigate()
                                && (divAliveCount == 1 || div.localForceRatio < 0.60f);
                        if (isLoneOrOutnumberedFrig) {
                            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                        }
                        boolean safeToNudgeIn = !isLoneOrOutnumberedFrig || ship.getFluxLevel() < 0.35f;
                        if (distToEnemy < div.standoffRange * 0.82f) {
                            // Pressed inside standoff cushion: give ground toward standoff slot
                            flags.unsetFlag(AIFlags.HARASS_MOVE_IN);
                            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                            flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        } else if (distToEnemy > individualEngageHorizon) {
                            // Division Movement Phase (outside combat horizon): march in formation toward target group
                            if (distToSlot > dressTol) {
                                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                            } else if (safeToNudgeIn) {
                                flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL);
                            }
                        } else if (distToEnemy > div.standoffRange * 1.08f) {
                            // Individual Combat Movement Phase (closing to standoff gun/missile range):
                            // Never set CAMP_LOCATION onto a behind/side slot while trying to close on focusEnemyShip!
                            // Only nudge in if not an outnumbered/lone frigate.
                            if (safeToNudgeIn) {
                                flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL);
                            }
                            if (structuredFormation && distToSlot > combatSlotLeash) {
                                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                            }
                        } else {
                            // Inside optimal standoff band [0.82x .. 1.08x standoffRange]:
                            // Enforce standoff slot leash (including for lone frigates where divAliveCount == 1!)
                            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                            if ((structuredFormation || (ship.isFrigate() && divAliveCount == 1))
                                    && distToSlot > combatSlotLeash) {
                                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                            }
                        }
                    } else if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 2600f) {
                        // Prevent lone or outnumbered frigates from diving into enemy gunlines due to vanilla AI's
                        // helpless-target overmatch heuristic!
                        boolean loneOrOutnumberedFrigate = ship.isFrigate()
                                && (div.getAliveMembers().size() == 1 || div.localForceRatio < 0.72f);
                        if (loneOrOutnumberedFrigate) {
                            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                        } else if (activeMission == DivisionMissionType.CLOSE_ACTION) {
                            flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
                        }
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        float distToEnemy = distance(ship.getLocation(), focusEnemyShip.getLocation());
                        float distToSlot = distance(ship.getLocation(), slotPos);
                        boolean isWingman = (div.flagship != null && ship != div.flagship) || (div.flagship == null && shipIdx > 0);
                        if (loneOrOutnumberedFrigate && distToEnemy < div.standoffRange * 0.80f && ship.getFluxLevel() >= 0.30f) {
                            flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        } else if (distToEnemy > individualEngageHorizon) {
                            // Division Movement Phase: march in formation until entering individualEngageHorizon
                            if (distToSlot > dressTol) {
                                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                            }
                        } else if (structuredFormation && activeMission != DivisionMissionType.CLOSE_ACTION) {
                            if (distToEnemy <= div.standoffRange * 1.15f && distToSlot > combatSlotLeash) {
                                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                            }
                        } else if (structuredFormation && isWingman && distToEnemy > 950f
                                && ship.getFluxLevel() < 0.65f
                                && distToSlot > Math.max(combatSlotLeash, dressTol * 1.65f)) {
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        } else if (crowdedByPeer && distToEnemy > individualEngageHorizon) {
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        }
                    } else if (div.targetEnemyDivision != null && (distance(ship.getLocation(), slotPos) > (structuredFormation ? dressTol : 950f) || crowdedByPeer)) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;
            }
    }

    /**
     * Respects explicit Command Window orders (such as CAPTURE / ASSAULT / DEFEND on a control point,
     * RALLY, ESCORT, RETREAT, etc.) so Division AI never pulls ships away from player-ordered control points.
     * In vanilla Starsector, ships without an explicit task have assignment == null or SEARCH_AND_DESTROY.
     */
    private static boolean hasExplicitCommandOrder(ShipAPI ship, DivisionState div, CombatTaskManagerAPI tm) {
        if (ship != null && (ship.isRetreating() || ship.isDirectRetreat())) return true;
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
            BattleGroupState myGroup,
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

            if (myDiv.useConvergence) {
                // Convergence Division Commander (Alpha Core flagship or non-human fleet):
                // Uses non-linear Phase-Space Convergence (Time + Space + Firepower rolled into one)
                score += BattlefieldFirepowerEvaluator.computeConvergenceTargetScore(engine, myDiv, eDiv, myGroup) * 0.65f;
                if (preferMissilePrey) {
                    score += eDiv.avgFluxLevel * 450f;
                }
            } else {
                // Human Division Commander: weights Time reachability, Space Weak Point/Strongpoint seams, and Firepower
                if (dist <= myDiv.effectiveTimeRadius + myDiv.avgEngageRange) {
                    score += 130f * myDiv.timeFreedomFactor;
                }
                if (eDiv.isWeakPoint) {
                    score += 165f;
                } else if (eDiv.isStrongpoint) {
                    score -= 80f;
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
        float distFocus = distance(ship.getLocation(), focusEnemyShip.getLocation());
        boolean preferFocus = currTarget == null
                || !currTarget.isAlive()
                || currTarget.isHulk()
                || distFocus + 250f < distance(ship.getLocation(), currTarget.getLocation());
        if (preferFocus) {
            ship.setShipTarget(focusEnemyShip);
        }
        if (distFocus <= 2200f && (preferFocus || distFocus <= distance(ship.getLocation(), currTarget.getLocation()) + 450f)) {
            applyBasicShipAiTargetOverride(ship, focusEnemyShip);
        }
    }

    /**
     * Sets BasicShipAI.setTargetOverride(Ship) via MethodHandles so BasicShipAI.pickManeuver()
     * (lines 790, 867) directs StrafeTargetManeuverV2 onto the division's focus target instead of
     * immediately overwriting ship.getShipTarget() with an uncoordinated local pick.
     */
    private static void applyBasicShipAiTargetOverride(ShipAPI ship, ShipAPI focusEnemyShip) {
        if (ship == null || focusEnemyShip == null) return;
        ShipAIPlugin ai = ship.getShipAI();
        if (ai == null) return;

        if (!resolvedBasicAiHandles) {
            resolvedBasicAiHandles = true;
            try {
                ClassLoader cl = ai.getClass().getClassLoader();
                basicShipAiClass = Class.forName("com.fs.starfarer.combat.ai.BasicShipAI", false, cl);
                shipAiWrapperClass = Class.forName("com.fs.starfarer.combat.entities.Ship$ShipAIWrapper", false, cl);
                obfShipClass = Class.forName("com.fs.starfarer.combat.entities.Ship", false, cl);
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                unwrapAiHandle = lookup.findVirtual(
                        shipAiWrapperClass,
                        "getAI",
                        MethodType.methodType(ShipAIPlugin.class));
                setTargetOverrideHandle = lookup.findVirtual(
                        basicShipAiClass,
                        "setTargetOverride",
                        MethodType.methodType(void.class, obfShipClass));
            } catch (Throwable ignored) {
                setTargetOverrideHandle = null;
            }
        }

        if (setTargetOverrideHandle == null || obfShipClass == null || !obfShipClass.isInstance(focusEnemyShip)) {
            return;
        }
        try {
            Object targetAi = ai;
            if (shipAiWrapperClass != null && shipAiWrapperClass.isInstance(targetAi) && unwrapAiHandle != null) {
                targetAi = unwrapAiHandle.invoke(targetAi);
            }
            if (basicShipAiClass != null && basicShipAiClass.isInstance(targetAi)) {
                setTargetOverrideHandle.invoke(targetAi, focusEnemyShip);
            }
        } catch (Throwable ignored) {
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
     * configured stationSpacing (in units).
     *  - Inter-division spacing (`divisionInterval`) and main-line clearance shift the division's `baseCenter`
     *    as a single cohesive unit so friendly division repulsion never splits a division in two.
     *  - During `CLOSE_UP`, slots contract to 60% of `stationSpacing` around the division flagship (or centroid)
     *    without inter-division repulsion so scattered ships rally tightly back into formation.
     */
    private static Vector2f computeShipFormationSlot(
            CombatEngineAPI engine,
            ShipAPI ship,
            int shipIdx,
            List<ShipAPI> alive,
            DivisionState div,
            BattleGroupState myGroup) {

        boolean closingUp = (div.currentIntent == DivisionIntentType.CLOSE_UP);
        float baseSpacing = Math.max(150f, div.stationSpacing);
        float effectiveSlotSpacing = closingUp ? Math.max(140f, baseSpacing * 0.60f) : baseSpacing;

        Vector2f rawCenter;
        if (closingUp) {
            rawCenter = (div.flagship != null && div.flagship.isAlive() && !div.flagship.isHulk()
                    && !div.detachedStragglerIds.contains(div.flagship.getId()))
                    ? div.flagship.getLocation()
                    : div.centroid;
        } else if (div.hasRouteWaypoint) {
            rawCenter = div.routeWaypoint;
        } else {
            rawCenter = div.stationPoint;
        }

        float centerX = rawCenter.x;
        float centerY = rawCenter.y;

        // Moving Formation Carriage: when a division in a structured formation is marching toward a distant
        // objective or station (e.g. right after deploying as a solid block), project the formation carriage
        // a bounded lead distance ahead of the division's core centroid along the march vector so ships
        // immediately fan out laterally into their Wedge / Semicircle / Line Abreast / Echelon while advancing!
        DivisionFormation formation = div.activeFormation != null
                ? div.activeFormation
                : (div.doctrinalFormation != null ? div.doctrinalFormation : DivisionFormation.LINE_ABREAST);
        float marchDirX = 0f;
        float marchDirY = 0f;
        float marchDist = 0f;
        if (div.centroid != null && (Math.abs(div.centroid.x) > 1f || Math.abs(div.centroid.y) > 1f)) {
            float gdx = centerX - div.centroid.x;
            float gdy = centerY - div.centroid.y;
            marchDist = (float) Math.sqrt(gdx * gdx + gdy * gdy);
            if (marchDist > 1f) {
                marchDirX = gdx / marchDist;
                marchDirY = gdy / marchDist;
            }
        }
        if (!closingUp
                && !div.hasRouteWaypoint
                && formation != DivisionFormation.NONE
                && alive.size() >= 2
                && (div.currentIntent == DivisionIntentType.TAKE_AND_HOLD
                || div.currentIntent == DivisionIntentType.CLOSE_AND_ENGAGE
                || div.currentIntent == DivisionIntentType.FORM_LINE_AND_FIX)
                && marchDist > 1f) {
            // Only use the short 680u cautious step on TAKE_AND_HOLD when advancing into enemy-controlled space
            // (Station assault, Pursuit battle, or Enemy Space on the objective) or under a Cautious commander.
            // During CLOSE_AND_ENGAGE, FORM_LINE_AND_FIX, and standard Meeting Engagement TAKE_AND_HOLD, project the
            // carriage >= 1950u ahead so rear Wedge/Column ranks never fall at/behind div.centroid and ships never
            // stall inside vanilla BasicShipAI's < 450u CAMP_LOCATION braking zone!
            boolean cautiousObjectiveStep = (div.currentIntent == DivisionIntentType.TAKE_AND_HOLD)
                    && shouldAdvanceCautiouslyOnObjective(engine, div, myGroup, null);
            float maxCarriageLead = cautiousObjectiveStep
                    ? Math.max(850f, effectiveSlotSpacing * 2.10f)
                    : Math.max(1950f, effectiveSlotSpacing * 4.0f);
            if (marchDist > maxCarriageLead) {
                centerX = div.centroid.x + marchDirX * maxCarriageLead;
                centerY = div.centroid.y + marchDirY * maxCarriageLead;
            }
        }

        // Whole-division inter-division spacing & main-line clearance (applied to the division center as a unit,
        // never pushing individual ships of the same division in opposite directions!
        // CRITICAL: When the division is advancing toward a target/objective (marchDist > 180f), project out any
        // backward component along marchDir so friendly divisions ONLY separate laterally and NEVER push a division's
        // carriage backward onto its own centroid, which previously froze Missile Frigate Div 2 in the middle of battle!)
        if (!closingUp && myGroup != null) {
            float desiredInter = Math.max(400f, div.divisionInterval);
            for (DivisionState peerDiv : myGroup.divisions) {
                if (peerDiv == null || peerDiv == div || !peerDiv.isCombatEffective()) continue;
                // Do not repel from the friendly division we are actively screening, reinforcing, or venting behind
                if (peerDiv == div.targetFriendlyDivision) continue;
                float minInter = Math.max(desiredInter, peerDiv.divisionInterval) * 0.85f;
                float idx = div.centroid.x - peerDiv.centroid.x;
                float idy = div.centroid.y - peerDiv.centroid.y;
                float iDist = (float) Math.sqrt(idx * idx + idy * idy);
                if (iDist > 1f && iDist < minInter) {
                    float push = Math.min(420f, (minInter - iDist) * 0.55f);
                    float px = (idx / iDist) * push;
                    float py = (idy / iDist) * push;
                    if (marchDist > 180f) {
                        float dot = px * marchDirX + py * marchDirY;
                        if (dot < 0f) {
                            px -= dot * marchDirX;
                            py -= dot * marchDirY;
                        }
                    }
                    centerX += px;
                    centerY += py;
                }
            }

            // Anti-balling with main battle line: shift Frigate & Phase division center clear of heavy main-line centroid
            if ((div.archetype == DivisionArchetype.FRIGATE_DIVISION
                    || div.archetype == DivisionArchetype.PHASE_DIVISION)
                    && myGroup.mainEffortDivision != null
                    && myGroup.mainEffortDivision != div
                    && myGroup.mainEffortDivision != div.targetFriendlyDivision
                    && myGroup.mainEffortDivision.isCombatEffective()) {
                Vector2f mainPos = myGroup.mainEffortDivision.centroid;
                float mdx = div.centroid.x - mainPos.x;
                float mdy = div.centroid.y - mainPos.y;
                float mDist = (float) Math.sqrt(mdx * mdx + mdy * mdy);
                float minClearance = Math.max(550f, Math.max(div.divisionInterval * 0.80f, div.standoffRange * 0.75f));
                if (mDist > 1f && mDist < minClearance) {
                    float push = Math.min(500f, (minClearance - mDist) * 0.70f);
                    float px = (mdx / mDist) * push;
                    float py = (mdy / mDist) * push;
                    if (marchDist > 180f) {
                        float dot = px * marchDirX + py * marchDirY;
                        if (dot < 0f) {
                            px -= dot * marchDirX;
                            py -= dot * marchDirY;
                        }
                    }
                    centerX += px;
                    centerY += py;
                }
            }
        }

        float desiredFwdX = 0f;
        float desiredFwdY = (div.side == 0) ? 1f : -1f;
        if (div.targetEnemyDivision != null) {
            Vector2f visEnemy = OtcAI.getVisibleCentroidForSide(engine, div.side, div.targetEnemyDivision);
            float dx = visEnemy.x - div.centroid.x;
            float dy = visEnemy.y - div.centroid.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1f) {
                desiredFwdX = dx / len;
                desiredFwdY = dy / len;
            }
        } else if (div.centroid != null && rawCenter != null) {
            // When advancing on an objective or station before enemy contact, orient formation heading along the march vector
            float dx = rawCenter.x - div.centroid.x;
            float dy = rawCenter.y - div.centroid.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 220f) {
                desiredFwdX = dx / len;
                desiredFwdY = dy / len;
            }
        }

        // Smoothly wheel the division's formation heading so the line wheels as a cohesive naval unit
        if (!div.formationHeadingInitialized) {
            div.formationHeadingX = desiredFwdX;
            div.formationHeadingY = desiredFwdY;
            div.formationHeadingInitialized = true;
        } else if (shipIdx == 0) {
            float blend = 0.28f;
            float bx = div.formationHeadingX * (1f - blend) + desiredFwdX * blend;
            float by = div.formationHeadingY * (1f - blend) + desiredFwdY * blend;
            float bLen = (float) Math.sqrt(bx * bx + by * by);
            if (bLen > 0.001f) {
                div.formationHeadingX = bx / bLen;
                div.formationHeadingY = by / bLen;
            }
        }

        float fwdX = div.formationHeadingX;
        float fwdY = div.formationHeadingY;
        // Lateral axis perpendicular to forward threat vector
        float latX = -fwdY;
        float latY = fwdX;

        // Station in Line depth bias (Van / Center / Rear) relative to the main battle line
        float stationDepthBias = 0f;
        LineStation lineStation = (div.lineStation != null) ? div.lineStation : LineStation.CENTER;
        if (!closingUp
                && div.currentIntent != DivisionIntentType.HOLD_IN_RESERVE
                && div.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT
                && div.currentIntent != DivisionIntentType.BREAK_OFF_AND_RETIRE) {
            if (lineStation == LineStation.VAN) {
                stationDepthBias = 220f;
            } else if (lineStation == LineStation.REAR) {
                stationDepthBias = -320f;
            }
        }

        float anchorX = centerX + fwdX * stationDepthBias;
        float anchorY = centerY + fwdY * stationDepthBias;

        int n = Math.max(1, alive.size());
        Vector2f[] worldSlots = new Vector2f[n];
        for (int k = 0; k < n; k++) {
            float[] local = computeLocalFormationOffset(formation, k, n, effectiveSlotSpacing);
            float lx = local[0];
            float ly = local[1];
            worldSlots[k] = new Vector2f(
                    anchorX + latX * lx + fwdX * ly,
                    anchorY + latY * lx + fwdY * ly);
        }

        // Permutation-free slot assignment:
        //  - Slot 0 is the Flagship Command Slot (occupied by div.flagship if alive in `alive`, else alive.get(0)).
        //  - Remaining wingmen are matched to slots 1..n-1 greedily by minimum distance so wingmen never criss-cross.
        int leadIdx = 0;
        if (div.flagship != null) {
            for (int i = 0; i < n; i++) {
                if (alive.get(i) == div.flagship) {
                    leadIdx = i;
                    break;
                }
            }
        }

        int assignedSlotIdx = 0;
        if (shipIdx != leadIdx && n > 1) {
            boolean[] slotTaken = new boolean[n];
            slotTaken[0] = true; // Slot 0 reserved for flagship
            for (int i = 0; i < n; i++) {
                if (i == leadIdx) continue;
                ShipAPI s = alive.get(i);
                int bestSlot = 1;
                float bestDistSq = Float.MAX_VALUE;
                for (int k = 1; k < n; k++) {
                    if (slotTaken[k]) continue;
                    float dx = s.getLocation().x - worldSlots[k].x;
                    float dy = s.getLocation().y - worldSlots[k].y;
                    float dSq = dx * dx + dy * dy;
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        bestSlot = k;
                    }
                }
                slotTaken[bestSlot] = true;
                if (i == shipIdx) {
                    assignedSlotIdx = bestSlot;
                    break;
                }
            }
        }

        float slotX = worldSlots[assignedSlotIdx].x;
        float slotY = worldSlots[assignedSlotIdx].y;

        // Intra-division peer anti-collision: when practicing a structured formation, only nudge if two ships are
        // actually encroaching within 0.48 * effectiveSlotSpacing so we never warp the formation's geometry.
        float repelThreshold = (formation == DivisionFormation.NONE)
                ? (effectiveSlotSpacing * 0.85f)
                : (effectiveSlotSpacing * 0.48f);
        for (ShipAPI peer : alive) {
            if (peer == null || peer == ship || !peer.isAlive()) continue;
            float pdx = ship.getLocation().x - peer.getLocation().x;
            float pdy = ship.getLocation().y - peer.getLocation().y;
            float pDist = (float) Math.sqrt(pdx * pdx + pdy * pdy);
            if (pDist > 1f && pDist < repelThreshold) {
                float push = (repelThreshold - pDist) * 0.55f;
                slotX += (pdx / pDist) * push;
                slotY += (pdy / pDist) * push;
            }
        }

        return new Vector2f(slotX, slotY);
    }

    /**
     * Computes the local (lateralOffset, forwardDepthOffset) in combat units for slot {@code k} of {@code n} ships
     * in the given {@link DivisionFormation}.
     * Slot {@code k = 0} is always the Flagship Command Slot.
     * {@code +lateral} = starboard (right of heading), {@code -lateral} = port (left of heading);
     * {@code +depth} = ahead (toward enemy), {@code -depth} = astern.
     */
    public static float[] computeLocalFormationOffset(
            DivisionFormation formation,
            int k,
            int n,
            float spacing) {
        if (n <= 1) {
            return new float[]{0f, 0f};
        }
        DivisionFormation f = (formation != null) ? formation : DivisionFormation.LINE_ABREAST;
        int rank = (k + 1) / 2;
        float sideSign = (k == 0) ? 0f : ((k % 2 == 1) ? -1f : 1f);

        switch (f) {
            case LINE_ABREAST: {
                // Pure lateral battle line perpendicular to the enemy; flagship in the center
                return new float[]{sideSign * rank * spacing, 0f};
            }
            case LINE_AHEAD: {
                // Single-file column: flagship at the van (k=0, depth=0), wingmen astern in its wake
                return new float[]{0f, -k * (spacing * 0.82f)};
            }
            case FORWARD_WEDGE: {
                // V-wedge pointing forward: flagship at the forward apex (k=0, depth=0), wingmen stepped back on both shoulders
                float lat = sideSign * rank * (spacing * 0.84f);
                float depth = -rank * (spacing * 0.68f);
                return new float[]{lat, depth};
            }
            case REVERSE_WEDGE: {
                // Inverted V-pocket opening toward the enemy: flagship at the rear vertex (k=0), wingmen forward on shoulders
                int maxRank = Math.max(1, n / 2);
                float vertexRear = -maxRank * (spacing * 0.68f);
                float lat = sideSign * rank * (spacing * 0.84f);
                float depth = vertexRear + rank * (spacing * 0.68f);
                return new float[]{lat, depth};
            }
            case ECHELON_LEFT: {
                // Stepped diagonal line back and to port from flagship at front-starboard (k=0, depth=0)
                float step = spacing * 0.68f;
                return new float[]{k * step, -k * (step * 0.65f)};
            }
            case ECHELON_RIGHT: {
                // Stepped diagonal line back and to starboard from flagship at front-port (k=0, depth=0)
                float step = spacing * 0.68f;
                return new float[]{-k * step, -k * (step * 0.65f)};
            }
            case CIRCLE: {
                // All-around defensive ring: flagship at the forward crown (k=0, depth=0), wingmen around the ring behind it
                float radius = Math.max(spacing * 0.65f, (float) ((n * spacing * 0.72f) / (2.0 * Math.PI)));
                double angleFromFwd = (2.0 * Math.PI * k) / n;
                float lat = (float) (Math.sin(angleFromFwd) * radius);
                float depth = (float) ((Math.cos(angleFromFwd) - 1.0) * radius);
                return new float[]{lat, depth};
            }
            case FORWARD_SEMICIRCLE: {
                // Convex dome bowing toward the enemy: flagship at the forward crown (k=0, depth=0), wingmen curving back along +-70 deg arc
                int maxRank = Math.max(1, (n + 1) / 2);
                float radius = Math.max(spacing * 0.85f, maxRank * spacing * 0.82f);
                double maxAngle = Math.toRadians(70.0);
                double theta = (k == 0) ? 0.0 : (sideSign * (rank / (double) maxRank) * maxAngle);
                float lat = (float) (Math.sin(theta) * radius);
                float depth = (float) ((Math.cos(theta) - 1.0) * radius);
                return new float[]{lat, depth};
            }
            case REVERSE_SEMICIRCLE: {
                // Concave crescent opening toward the enemy: flagship at the rear-center base (k=0), wingmen curving forward on both horns
                int maxRank = Math.max(1, (n + 1) / 2);
                float radius = Math.max(spacing * 0.85f, maxRank * spacing * 0.82f);
                double maxAngle = Math.toRadians(70.0);
                double theta = (k == 0) ? 0.0 : (sideSign * (rank / (double) maxRank) * maxAngle);
                float lat = (float) (Math.sin(theta) * radius);
                float depth = (float) (-Math.cos(theta) * radius * 0.55f);
                return new float[]{lat, depth};
            }
            case NONE:
            default: {
                // Loose organic scatter for free maneuver
                float lat = sideSign * rank * (spacing * 0.95f);
                float depth = ((k % 3) - 1) * (spacing * 0.28f);
                return new float[]{lat, depth};
            }
        }
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
        // Slide standoff station out of L/U crossfire overlaps and toward enemy blind spots / disunified arcs
        if (div.useConvergence) {
            BattlefieldFirepowerEvaluator.optimizeConvergenceStation(
                    engine, div, enemyDiv, null, null, visEnemyPos, outStation, 42f, outStation);
        } else {
            BattlefieldFirepowerEvaluator.optimizeStationAlongArc(
                    engine, div, enemyDiv, visEnemyPos, outStation, 36f, 5, outStation);
        }
    }

    /**
     * Places a reinforcing division on the friendly / low-cordon shoulder of the beleaguered division
     * rather than diving straight onto its centroid inside an L- or U-shaped enemy crossfire pocket.
     */
    private static void computeReinforceStation(
            CombatEngineAPI engine,
            DivisionState reinforcer,
            DivisionState friend,
            Vector2f outStation,
            BattleGroupState myGroup) {
        if (friend == null) return;
        if (reinforcer == null) {
            outStation.set(friend.centroid);
            return;
        }
        // Default support offset: 480u toward the reinforcer / friendly reserve side of friend.centroid
        float dx = reinforcer.centroid.x - friend.centroid.x;
        float dy = reinforcer.centroid.y - friend.centroid.y;
        if (myGroup != null) {
            dx = dx * 0.65f + (myGroup.reserveStation.x - friend.centroid.x) * 0.35f;
            dy = dy * 0.65f + (myGroup.reserveStation.y - friend.centroid.y) * 0.35f;
        }
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float supportDist = Math.max(420f, Math.min(680f, reinforcer.stationSpacing * 1.15f));
        if (len > 1f) {
            outStation.set(
                    friend.centroid.x + (dx / len) * supportDist,
                    friend.centroid.y + (dy / len) * supportDist);
        } else {
            float backY = (reinforcer.side == 0) ? -supportDist : supportDist;
            outStation.set(friend.centroid.x, friend.centroid.y + backY);
        }
        BattlefieldFirepowerEvaluator.optimizeStationAlongArc(
                engine, reinforcer, friend.targetEnemyDivision, friend.centroid, outStation, 50f, 5, outStation);
        clampToMapBounds(engine, outStation, 650f);
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
        float flankRadius = Math.max(
                1250f,
                flankerDiv.standoffRange + enemyDiv.spreadRadius * 0.55f + flankerDiv.stationSpacing * 0.65f);

        // Pincer: an OTC-assigned flank side is measured on the battlegroup's attack axis (fleet centre -> target),
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
                // Check if the flanker is still approaching from the front hemisphere of the target division;
                // if so, swing wider laterally so it sweeps around the enemy's outer wing out of contact.
                float relX = flankerDiv.centroid.x - visEnemyPos.x;
                float relY = flankerDiv.centroid.y - visEnemyPos.y;
                float relLen = (float) Math.sqrt(relX * relX + relY * relY);
                float forwardDot = (relLen > 1f) ? ((relX * fwdX + relY * fwdY) / relLen) : -1f;
                float lateralMult = (forwardDot < -0.15f) ? 1.28f : 1.0f;
                float depthMult = (forwardDot < -0.15f) ? 0.15f : 0.32f;
                outStation.set(
                        visEnemyPos.x + perpX * flankRadius * lateralMult * assignedSide + fwdX * flankRadius * depthMult,
                        visEnemyPos.y + perpY * flankRadius * lateralMult * assignedSide + fwdY * flankRadius * depthMult);
                if (flankerDiv.useConvergence) {
                    BattlefieldFirepowerEvaluator.optimizeConvergenceStation(
                            engine, flankerDiv, enemyDiv, myGroup, null, visEnemyPos, outStation, 52f, outStation);
                } else {
                    BattlefieldFirepowerEvaluator.optimizeStationAlongArc(
                            engine, flankerDiv, enemyDiv, visEnemyPos, outStation, 48f, 7, outStation);
                }
                clampToMapBounds(engine, outStation, 650f);
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
        if (flankerDiv.useConvergence) {
            BattlefieldFirepowerEvaluator.optimizeConvergenceStation(
                    engine, flankerDiv, enemyDiv, myGroup, null, visEnemyPos, outStation, 60f, outStation);
        } else {
            BattlefieldFirepowerEvaluator.optimizeStationAlongArc(
                    engine, flankerDiv, enemyDiv, visEnemyPos, outStation, 55f, 7, outStation);
        }
        clampToMapBounds(engine, outStation, 650f);
    }

    /**
     * Updates a division's out-of-contact wide-arc route waypoint (div.routeWaypoint).
     * When a division is executing a flank, staging for a strike, seizing an objective, reinforcing,
     * screening, or retiring to vent, and an enemy division / battle line lies across the straight chord
     * from div.centroid to div.stationPoint, projects an intermediate tangent waypoint around the outer
     * perimeter of the enemy threat zone so the division maneuvers out of contact before assuming station.
     */
    private static void updateDivisionOutOfContactRoute(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup) {

        div.routeWaypoint.set(div.stationPoint);
        div.hasRouteWaypoint = false;
        if (enemyGroup == null || enemyGroup.divisions.isEmpty()) return;

        DivisionIntentType intent = div.currentIntent;
        if (intent == DivisionIntentType.CLOSE_UP) {
            return;
        }

        // Direct close-in attack intents do not detour around their own attack target once within 2400u,
        // though they still skirt around other intervening enemy divisions on long transits.
        boolean directAttackIntent = (intent == DivisionIntentType.PRESS_HOME
                || intent == DivisionIntentType.TOT_MISSILE_STRIKE
                || intent == DivisionIntentType.TOT_ALPHA_STRIKE
                || intent == DivisionIntentType.HARASSING_MISSILE_ATTACK
                || intent == DivisionIntentType.GIVE_GENERAL_CHASE
                || (intent == DivisionIntentType.CLOSE_AND_ENGAGE
                && resolveActiveMission(div, engine != null ? engine.getTotalElapsedTime(false) : 0f) == DivisionMissionType.CLOSE_ACTION));

        DivisionState ignoreDirectTarget = directAttackIntent ? div.targetEnemyDivision : null;
        DivisionState flankTarget = (intent == DivisionIntentType.WORK_FLANK) ? div.targetEnemyDivision : null;

        float preferredSide = assignedFlankSide(div, div.targetEnemyDivision, myGroup);
        if (preferredSide == 0f) {
            preferredSide = div.routeSkirtSide;
        }

        float[] chosenSide = new float[]{preferredSide};
        Vector2f wp = computeOutOfContactRoutePoint(
                engine,
                div.side,
                div.centroid,
                div.stationPoint,
                ignoreDirectTarget,
                flankTarget,
                preferredSide,
                enemyGroup,
                chosenSide);
        div.routeSkirtSide = chosenSide[0];
        div.routeWaypoint.set(wp);
        div.hasRouteWaypoint = distance(div.routeWaypoint, div.stationPoint) > 220f;
    }

    /**
     * Computes an out-of-contact wide-arc waypoint from startPos (A) to goalPos (B) that skirts around
     * any intervening visible enemy division threat zones.
     *
     * If the straight chord A -> B slices through an enemy threat circle (or enemy battle line cluster),
     * returns a tangent/arc waypoint W at ~52 degrees ahead along the outer perimeter of the obstacle
     * on the open flank side. Once the chord A -> B is clear of enemy threat zones, returns goalPos (B).
     */
    private static Vector2f computeOutOfContactRoutePoint(
            CombatEngineAPI engine,
            int side,
            Vector2f startPos,
            Vector2f goalPos,
            DivisionState ignoreDirectAttackTarget,
            DivisionState flankTargetDiv,
            float preferredSideHint,
            BattleGroupState enemyGroup,
            float[] outChosenSide) {

        if (startPos == null || goalPos == null) {
            return goalPos != null ? new Vector2f(goalPos) : new Vector2f();
        }
        if (enemyGroup == null || enemyGroup.divisions.isEmpty()) {
            return new Vector2f(goalPos);
        }

        float chordX = goalPos.x - startPos.x;
        float chordY = goalPos.y - startPos.y;
        float chordLen = (float) Math.sqrt(chordX * chordX + chordY * chordY);
        if (chordLen < 360f) {
            return new Vector2f(goalPos);
        }

        float dirX = chordX / chordLen;
        float dirY = chordY / chordLen;

        // Find the primary visible enemy division whose threat zone blocks the straight segment A -> B
        DivisionState blockingDiv = null;
        Vector2f blockingCenter = null;
        float blockingRadius = 0f;
        float earliestBlockProj = Float.MAX_VALUE;

        for (DivisionState eDiv : enemyGroup.divisions) {
            if (eDiv == null || !eDiv.isCombatEffective()) continue;
            if (!OtcAI.isDivisionVisibleToSide(engine, side, eDiv)) continue;
            if (eDiv == ignoreDirectAttackTarget && chordLen < 2600f) continue;

            Vector2f eCenter = OtcAI.getVisibleCentroidForSide(engine, side, eDiv);
            float distGoalToEnemy = distance(goalPos, eCenter);
            float distStartToEnemy = distance(startPos, eCenter);

            // Threat avoidance radius: enemy engagement range + spread + out-of-contact buffer
            float threatRadius = Math.max(1150f, eDiv.avgEngageRange * 1.10f)
                    + Math.min(700f, eDiv.spreadRadius * 0.70f)
                    + 320f;
            if (eDiv == flankTargetDiv) {
                // For the enemy division being flanked, keep the avoidance disc just inside the flank station radius
                // so the flanker sweeps around its front/beam out of contact rather than cutting across its nose.
                threatRadius = Math.min(threatRadius, Math.max(850f, distGoalToEnemy * 0.88f));
            } else if (distGoalToEnemy < threatRadius + 140f) {
                // If goalPos itself sits close to this enemy division, shrink avoidance radius so goalPos remains reachable
                threatRadius = Math.max(700f, distGoalToEnemy - 140f);
            }

            // Project eCenter onto segment A -> B
            float relX = eCenter.x - startPos.x;
            float relY = eCenter.y - startPos.y;
            float proj = relX * dirX + relY * dirY;
            if (proj <= 60f || proj >= chordLen - 40f) {
                continue;
            }

            float closestX = startPos.x + dirX * proj;
            float closestY = startPos.y + dirY * proj;
            float perpDist = (float) Math.sqrt(
                    (eCenter.x - closestX) * (eCenter.x - closestX)
                            + (eCenter.y - closestY) * (eCenter.y - closestY));

            if (perpDist < threatRadius * 0.96f && proj < earliestBlockProj) {
                // Also skip if startPos is already closer to goalPos than to eCenter and moving away
                if (distStartToEnemy > distGoalToEnemy && proj > chordLen * 0.75f) {
                    continue;
                }
                earliestBlockProj = proj;
                blockingDiv = eDiv;
                blockingCenter = eCenter;
                blockingRadius = threatRadius;
            }
        }

        if (blockingDiv == null || blockingCenter == null) {
            return new Vector2f(goalPos);
        }

        // Expand obstacle center/radius slightly if adjacent enemy divisions form a contiguous battle line with blockingDiv
        float clusterX = blockingCenter.x * blockingDiv.totalFpStrength;
        float clusterY = blockingCenter.y * blockingDiv.totalFpStrength;
        float clusterWeight = Math.max(1f, blockingDiv.totalFpStrength);
        for (DivisionState otherE : enemyGroup.divisions) {
            if (otherE == null || otherE == blockingDiv || !otherE.isCombatEffective()) continue;
            if (!OtcAI.isDivisionVisibleToSide(engine, side, otherE)) continue;
            if (otherE == ignoreDirectAttackTarget) continue;
            Vector2f oPos = OtcAI.getVisibleCentroidForSide(engine, side, otherE);
            float dBetween = distance(blockingCenter, oPos);
            if (dBetween < 1850f) {
                // Project onto A -> B as well
                float oProj = (oPos.x - startPos.x) * dirX + (oPos.y - startPos.y) * dirY;
                if (oProj > 0f && oProj < chordLen) {
                    float w = otherE.totalFpStrength * 0.55f;
                    clusterX += oPos.x * w;
                    clusterY += oPos.y * w;
                    clusterWeight += w;
                }
            }
        }
        Vector2f obsCenter = new Vector2f(clusterX / clusterWeight, clusterY / clusterWeight);
        float extraClusterSpan = distance(obsCenter, blockingCenter);
        float skirtRadius = Math.min(2850f, blockingRadius + extraClusterSpan * 0.65f + 240f);

        // Two candidate normals perpendicular to A -> B: +1 (Port / Left of chord) and -1 (Starboard / Right of chord)
        float normX = -dirY;
        float normY = dirX;

        float bestSide = (preferredSideHint != 0f) ? Math.signum(preferredSideHint) : 1f;
        float bestSideScore = -Float.MAX_VALUE;

        for (int sIdx = 0; sIdx < 2; sIdx++) {
            float candidateSide = (sIdx == 0) ? 1f : -1f;
            float candX = obsCenter.x + normX * candidateSide * skirtRadius;
            float candY = obsCenter.y + normY * candidateSide * skirtRadius;
            Vector2f candApex = new Vector2f(candX, candY);

            float score = 0f;
            // 1. Prefer swinging around the OUTSIDE of the enemy fleet centroid rather than between enemy divisions
            if (enemyGroup.totalFriendlyStrength > 0f) {
                score += distance(candApex, enemyGroup.fleetCentroid) * 0.42f;
            }
            // 2. Penalize candidate apex if other visible enemy divisions sit near that side
            for (DivisionState otherE : enemyGroup.divisions) {
                if (otherE == null || otherE == blockingDiv || !otherE.isCombatEffective()) continue;
                if (!OtcAI.isDivisionVisibleToSide(engine, side, otherE)) continue;
                Vector2f oPos = OtcAI.getVisibleCentroidForSide(engine, side, otherE);
                float dToCand = distance(candApex, oPos);
                if (dToCand < 1900f) {
                    score -= (1900f - dToCand) * 0.65f;
                }
            }
            // 2b. Penalize candidate apex by actual overlapping weapon-arc firepower & L/U crossfire cordon
            BattlefieldFirepowerEvaluator.PointFirepowerEval fpEval =
                    BattlefieldFirepowerEvaluator.evaluatePoint(engine, side, candApex, 180f);
            score -= fpEval.effectiveDanger * 0.42f + fpEval.cordonIndex * 380f;
            if (blockingDiv != null) {
                score += BattlefieldFirepowerEvaluator.evaluateBlindSpotAdvantage(engine, side, blockingDiv, candApex) * 0.85f;
            }

            // 3. Shorter tangent geometry from startPos around obsCenter
            float crossStart = (startPos.x - obsCenter.x) * normX * candidateSide
                    + (startPos.y - obsCenter.y) * normY * candidateSide;
            score += crossStart * 0.35f;

            // 4. Hysteresis / OTC flank-side stickiness so units don't flip-flop mid-flank
            if (preferredSideHint != 0f && Math.signum(preferredSideHint) == candidateSide) {
                score += 620f;
            }

            // 5. Penalize proximity to combat map borders
            if (engine != null) {
                float halfW = engine.getMapWidth() * 0.5f - 900f;
                float halfH = engine.getMapHeight() * 0.5f - 900f;
                if (Math.abs(candX) > halfW) {
                    score -= (Math.abs(candX) - halfW) * 1.2f;
                }
                if (Math.abs(candY) > halfH) {
                    score -= (Math.abs(candY) - halfH) * 1.2f;
                }
            }

            if (score > bestSideScore) {
                bestSideScore = score;
                bestSide = candidateSide;
            }
        }

        if (outChosenSide != null && outChosenSide.length > 0) {
            outChosenSide[0] = bestSide;
        }

        // Place waypoint W along the outer perimeter of obsCenter, stepped ~52 degrees ahead along the chosen side arc
        float rx = startPos.x - obsCenter.x;
        float ry = startPos.y - obsCenter.y;
        float rLen = (float) Math.sqrt(rx * rx + ry * ry);
        float ux;
        float uy;
        if (rLen > 1f) {
            ux = rx / rLen;
            uy = ry / rLen;
        } else {
            ux = normX * bestSide;
            uy = normY * bestSide;
        }

        // Tangent vector perpendicular to u that advances toward goalPos around the chosen side
        float t1x = -uy;
        float t1y = ux;
        float t2x = uy;
        float t2y = -ux;
        // Pick the tangent whose projection along (dir + 0.5 * chosenNormal) is positive
        float guideX = dirX + normX * bestSide * 0.65f;
        float guideY = dirY + normY * bestSide * 0.65f;
        float tx;
        float ty;
        if (t1x * guideX + t1y * guideY >= t2x * guideX + t2y * guideY) {
            tx = t1x;
            ty = t1y;
        } else {
            tx = t2x;
            ty = t2y;
        }

        // 52-degree angular step around the perimeter (cos 52° ≈ 0.616, sin 52° ≈ 0.788)
        float stepDirX = 0.616f * ux + 0.788f * tx;
        float stepDirY = 0.616f * uy + 0.788f * ty;
        float stepLen = (float) Math.sqrt(stepDirX * stepDirX + stepDirY * stepDirY);
        if (stepLen > 1e-4f) {
            stepDirX /= stepLen;
            stepDirY /= stepLen;
        }

        Vector2f waypoint = new Vector2f(
                obsCenter.x + stepDirX * skirtRadius,
                obsCenter.y + stepDirY * skirtRadius);
        clampToMapBounds(engine, waypoint, 650f);
        return waypoint;
    }

    private static boolean isShipNearVisibleEnemy(
            CombatEngineAPI engine,
            int side,
            Vector2f pos,
            BattleGroupState enemyGroup,
            float radius) {
        if (pos == null || enemyGroup == null) return false;
        float rSq = radius * radius;
        for (DivisionState eDiv : enemyGroup.divisions) {
            if (eDiv == null || !eDiv.isCombatEffective()) continue;
            for (ShipAPI e : eDiv.getAliveMembers()) {
                if (!OtcAI.isShipVisibleToSide(engine, side, e)) continue;
                float dx = pos.x - e.getLocation().x;
                float dy = pos.y - e.getLocation().y;
                if ((dx * dx + dy * dy) <= rSq) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void clampToMapBounds(CombatEngineAPI engine, Vector2f pt, float margin) {
        if (engine == null || pt == null) return;
        float maxW = Math.max(2000f, engine.getMapWidth() * 0.5f - margin);
        float maxH = Math.max(2000f, engine.getMapHeight() * 0.5f - margin);
        if (pt.x > maxW) pt.x = maxW;
        if (pt.x < -maxW) pt.x = -maxW;
        if (pt.y > maxH) pt.y = maxH;
        if (pt.y < -maxH) pt.y = -maxH;
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
