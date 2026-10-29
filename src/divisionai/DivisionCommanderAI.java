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
import divisionai.PermanentDivisionManager.DivisionSetpieceAttack;
import divisionai.PermanentDivisionManager.FleetWideFormation;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.OtcPlay;
import divisionai.PermanentDivisionManager.LineStation;
import divisionai.PermanentDivisionManager.SquadronFormation;
import divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode;
import divisionai.PermanentDivisionManager.VoidstrikeTargetObservance;
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

    static final float STRIKE_WINDOW_DURATION = 14.0f;
    static final float FLAG_TTL = 0.85f;

    static boolean resolvedBasicAiHandles = false;
    static Class<?> basicShipAiClass = null;
    static Class<?> shipAiWrapperClass = null;
    static Class<?> obfShipClass = null;
    static MethodHandle unwrapAiHandle = null;
    static MethodHandle setTargetOverrideHandle = null;

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

            // Scope Item 1: Advance Setpiece Division Attack staging (Stage 1 IP dress -> Stage 2 formation pass)
            updateAndApplySetpieceDivisionAttack(engine, div, myGroup, now);

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
        } else if (div.activeSetpieceAttack != null
                && div.activeSetpieceAttack != DivisionSetpieceAttack.NONE
                && div.setpieceStage > 0
                && aliveCount >= 2) {
            if (div.activeSetpieceAttack == DivisionSetpieceAttack.ECHELON_STRIKE) {
                float fSide = (div.otcOrder != null && div.otcOrder.flankSide != 0f)
                        ? div.otcOrder.flankSide
                        : div.routeSkirtSide;
                desired = (fSide >= 0f) ? DivisionFormation.ECHELON_LEFT : DivisionFormation.ECHELON_RIGHT;
            } else if (div.activeSetpieceAttack.signatureFormation != null) {
                desired = div.activeSetpieceAttack.signatureFormation;
            }
        } else if (div.otcOrderedFormation != null
                && myGroup != null
                && (myGroup.authority == OtcAuthority.POSITIVE_CONTROL
                || (myGroup.authority == OtcAuthority.COMMAND_BY_DIRECTION && div.commander.stationKeepingStrictness >= 0.88f))) {
            desired = div.otcOrderedFormation;
        } else if (myGroup == null || myGroup.authority != OtcAuthority.POSITIVE_CONTROL || base != DivisionFormation.NONE) {
            // Division Commander's tactical discretion (preserve PROTECTIVE_CIRCLE when doctrinally selected unless surrounded/breaking up)
            if (base == DivisionFormation.PROTECTIVE_CIRCLE && aliveCount >= 2) {
                desired = DivisionFormation.PROTECTIVE_CIRCLE;
            } else if (div.crossfireCordonIndex >= 0.52f && aliveCount >= 3) {
                desired = DivisionFormation.CIRCLE;
            } else if (div.currentIntent == DivisionIntentType.TAKE_SCREENING_STATION && aliveCount >= 2) {
                desired = DivisionFormation.FORWARD_SEMICIRCLE;
            } else if (div.currentIntent == DivisionIntentType.WORK_FLANK && aliveCount >= 2) {
                if (base == DivisionFormation.LINE_ABREAST
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
        // Once a scouting/reconnaissance division has made contact with the enemy main body within 3200u,
        // reconnaissance is complete — transition immediately to its combat mainMission (e.g. ENVELOPMENT, SCREEN, STRIKE).
        if (inOpeningWindow
                && div.mainMission != null
                && div.mainMission != DivisionMissionType.CLOSE_ACTION
                && (activeMission == DivisionMissionType.SCOUTING
                || activeMission == DivisionMissionType.ARMED_RECONNAISSANCE)
                && (div.localEnemyStrength >= 4f
                || div.engagementLevel >= 0.08f
                || (div.targetEnemyDivision != null
                && div.targetEnemyDivision.isCombatEffective()
                && distance(div.centroid, div.targetEnemyDivision.centroid) <= 3200f))) {
            activeMission = div.mainMission;
        }
        return activeMission;
    }

    static boolean isInOpeningWindow(DivisionState div, float now) {
        return now <= Math.max(30f, Math.min(60f, div.openingPhaseDurationSeconds));
    }

    /** Intents that hold for the commander's commitment time; the rest end on their own triggers. */
    static boolean isCommittableIntent(DivisionIntentType intent) {
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
    static boolean hasUrgentTrigger(CombatEngineAPI engine, DivisionState div, BattleGroupState myGroup, float now) {
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
    static boolean isStrikeRunOver(DivisionState div, float now) {
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
    static boolean isUnderHighFluxPressure(DivisionState div, BattleGroupState myGroup) {
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

        // Protect fragile destroyer divisions (e.g. Hammerhead / Sunder packs) from fighting to the death
        // when their Effective HP (Shield + Armor + Hull HP) is depleted against heavy enemy resistance!
        boolean isFragileDestroyerDiv = (div.archetype == DivisionArchetype.DESTROYER_DIVISION
                || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION);
        boolean ehpDepletedUnderFire = div.localEnemyStrength >= 10f
                && ((div.effectiveHpRatio < 0.56f && (div.avgFluxLevel >= 0.34f || div.localForceRatio < 0.85f))
                || (isFragileDestroyerDiv && div.effectiveHpRatio < 0.64f && div.localForceRatio < 0.78f && div.avgFluxLevel >= 0.38f));

        boolean isHeavyLineDiv = div.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                || div.archetype == DivisionArchetype.MISSILE_BATTLESHIP_DIVISION
                || div.archetype == DivisionArchetype.CRUISER_DIVISION
                || div.archetype == DivisionArchetype.MISSILE_CRUISER_DIVISION
                || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION;
        boolean highFluxPressure = div.avgFluxLevel >= div.commander.rotationFluxThreshold
                || loneFrigateOvermatched
                || (!isHeavyLineDiv && ehpDepletedUnderFire)
                || (!isHeavyLineDiv && div.maxMemberFluxLevel >= 0.78f && div.localEnemyStrength > 10f && (div.localForceRatio < 0.72f || inCrossfireCordon))
                || (!isHeavyLineDiv && div.avgFluxLevel >= 0.45f && div.localEnemyStrength > 14f && div.localForceRatio < 0.50f && div.commander.aggressionBias < 1.35f)
                || (!isHeavyLineDiv && inCrossfireCordon && (div.maxMemberFluxLevel >= 0.64f || (div.avgFluxLevel >= 0.38f && div.localForceRatio < 0.68f)));
        boolean canRotateOut = (div != myGroup.mainEffortDivision
                || div.avgFluxLevel >= 0.72f
                || div.effectiveHpRatio < 0.45f);
        return highFluxPressure && canRotateOut;
    }

    static boolean isEndangered(DivisionState div) {
        return div.localEnemyStrength >= 12f && div.localForceRatio < 0.85f;
    }

    /**
     * The enemy division this division should fight: the OTC's allocated target when one is active
     * (binding under Hybrid / Central Planning / Target Coordination; preferred under Mission-Type Tactics,
     * where a much closer enemy on top of the division takes precedence), otherwise its own pick.
     */
    static DivisionState resolveOtcTarget(
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

    static boolean commitIntent(
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

    static void selectDivisionIntent(
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

        // Step 3: Explicit Command Window objective order (player orders outrank the OTC; hysteresis prevents 18-FP boundary oscillation)
        float maxDirectObjEnemyStrength = (prevIntent == DivisionIntentType.TAKE_AND_HOLD
                && div.targetObjective == commandAssignedObj) ? 24f : 15f;
        if (commandAssignedObj != null && !isHeavyLineOrCarrier && div.localEnemyStrength < maxDirectObjEnemyStrength) {
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
        // Item C: Carrier divisions operate at 2000-3800u standoff; never pull carriers into CLOSE_UP when a visible
        // enemy division exists within strike range (< 4200u), because CLOSE_UP interrupts carrier Voidstrikes!
        boolean carrierHasStrikeTarget = (div.archetype != null && div.archetype.isCarrierArchetype())
                && nearestEnemyDiv != null && distToVisibleEnemy <= 4200f;
        boolean enterCloseUp = div.stationKeepingRatio > 1.48f && !carrierHasStrikeTarget;
        boolean stayClosingUp = (prevIntent == DivisionIntentType.CLOSE_UP) && div.stationKeepingRatio > 1.08f && !carrierHasStrikeTarget;
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

    static boolean trySelectWithdrawalOrVentIntent(
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
                    && candidateFriend.isAbleCombatEffective()
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

    static String assignCarrierStandoffOrReserveStation(
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

    static boolean trySelectCarrierMissionIntent(
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

        DivisionState prevCarrierTarget = div.targetEnemyDivision;
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
        // Item C: Keep the carrier division's target sticky while strike wings are marshaling or airborne on a run,
        // unless the current target is no longer combat-effective/visible or a new threat is > 1250u closer!
        if (prevCarrierTarget != null
                && prevCarrierTarget.isAbleCombatEffective()
                && OtcAI.isDivisionVisibleToSide(engine, div.side, prevCarrierTarget)) {
            Vector2f prevPos = OtcAI.getVisibleCentroidForSide(engine, div.side, prevCarrierTarget);
            float prevDist = distance(div.centroid, prevPos);
            boolean airborneWaveActive = div.voidstrikeStage == 1 || div.voidstrikeStage == 2;
            if (prevDist <= 4400f) {
                if (carrierTargetDiv == null || airborneWaveActive) {
                    carrierTargetDiv = prevCarrierTarget;
                } else {
                    Vector2f newPos = OtcAI.getVisibleCentroidForSide(engine, div.side, carrierTargetDiv);
                    if (prevDist <= distance(div.centroid, newPos) + 1250f) {
                        carrierTargetDiv = prevCarrierTarget;
                    }
                }
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

    static boolean trySelectCombatMissionIntent(
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
            boolean isLightOrDestroyer = div.archetype == DivisionArchetype.DESTROYER_DIVISION
                    || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION
                    || div.archetype == DivisionArchetype.FRIGATE_DIVISION
                    || div.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION;
            // T-S-F-C interplay: if a fast Destroyer/Frigate division faces a slower enemy Strongpoint that outmatches
            // its Effective HP (Space disadvantage, Time advantage), use Time superiority to outflank (WORK_FLANK)
            // into the enemy's lateral/stern blind spot rather than trading bow-to-bow!
            boolean enemyOutmatchesEhp = div.localForceRatio < 0.85f
                    || (nearestEnemyDiv.totalEffectiveHp > div.totalEffectiveHp * 1.38f);
            boolean hasTimeAdvantage = div.weightedTimeSpeed > Math.max(40f, nearestEnemyDiv.weightedTimeSpeed * 1.08f);
            if (isLightOrDestroyer && enemyOutmatchesEhp && hasTimeAdvantage && div.avgFluxLevel < 0.58f) {
                computeFlankStation(engine, div, nearestEnemyDiv, div.stationPoint, myGroup);
                if (!inFlankManeuver) {
                    div.strikeCommittedTimestamp = now;
                }
                return commitIntent(engine, div, DivisionIntentType.WORK_FLANK,
                        nearestEnemyDiv.displayName, missionPhaseTag + "Time-flank on heavy target", now);
            }
            float pressRatioReq = isLightOrDestroyer ? 1.45f : 1.28f;
            boolean ehpReadyToPress = div.effectiveHpRatio >= (isLightOrDestroyer ? 0.75f : 0.65f);
            boolean realLocalOvermatch = div.localEnemyStrength >= 12f
                    && div.localForceRatio >= pressRatioReq
                    && (myGroup.forceRatio >= 1.25f || myGroup.currentPhase == BattlePhase.GENERAL_CHASE);
            DivisionIntentType intent = (realLocalOvermatch && ehpReadyToPress && distToVisibleEnemy < 2200f)
                    ? DivisionIntentType.PRESS_HOME
                    : DivisionIntentType.CLOSE_AND_ENGAGE;
            computeStandoffStation(engine, div, nearestEnemyDiv, div.stationPoint);
            return commitIntent(engine, div, intent,
                    nearestEnemyDiv.displayName, missionPhaseTag + activeMission.shortName, now);
        }

        return false;
    }

    static void selectFallbackTacticalIntent(
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
            boolean targetHasHeavyHybridFlak = hasEnemyHybridFlakScreen(missileTargetEnemyDiv);
            boolean isLightOrDestroyerDiv = (div.archetype == DivisionArchetype.FRIGATE_DIVISION
                    || div.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION
                    || div.archetype == DivisionArchetype.DESTROYER_DIVISION
                    || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION);
            // Opportunistic ToT Alpha Strike: only charge in with all guns when the target capital is genuinely
            // high-flux / damaged & isolated AND (for frigates/destroyers) not protected by active Large/Medium Flak!
            boolean strandedCapitalTarget = (missileTargetEnemyDiv.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                    || missileTargetEnemyDiv.archetype.isCarrierArchetype())
                    && (missileTargetEnemyDiv.avgFluxLevel >= 0.68f
                    || missileTargetEnemyDiv.effectiveHpRatio <= 0.45f)
                    && missileTargetEnemyDiv.localFriendlyStrength <= 45f
                    && (!isLightOrDestroyerDiv || !targetHasHeavyHybridFlak || missileTargetEnemyDiv.avgFluxLevel >= 0.80f)
                    && (isLightOrDestroyerDiv
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
            div.targetEnemyDivision = nearestEnemyDiv;
            computeStandoffStation(engine, div, nearestEnemyDiv, div.stationPoint);

            DivisionIntentType intent;
            String reason;
            if (div.localEnemyStrength >= 12f
                    && div.localForceRatio >= Math.max(1.38f, div.commander.commitForceRatio * 1.25f)
                    && (myGroup.forceRatio >= 1.25f || myGroup.currentPhase == BattlePhase.GENERAL_CHASE)
                    && div.effectiveHpRatio >= 0.68f
                    && distToVisibleEnemy < 2200f) {
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
    static boolean applyOtcOrder(
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
                boolean isLightOrDestroyer = div.archetype == DivisionArchetype.FRIGATE_DIVISION
                        || div.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION
                        || div.archetype == DivisionArchetype.DESTROYER_DIVISION
                        || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION;
                boolean flakProtectedHeavyTarget = isLightOrDestroyer
                        && hasEnemyHybridFlakScreen(target)
                        && target.avgFluxLevel < 0.78f;
                if (div.archetype.isCarrierArchetype()) {
                    div.currentIntent = DivisionIntentType.LAUNCH_ALPHA_STRIKE;
                    computeStandoffStation(engine, div, target, div.stationPoint);
                } else if (flakProtectedHeavyTarget && activeMission != DivisionMissionType.TOT_ALPHA_STRIKE) {
                    if (div.hasMissileStrikeCapability && div.usableStrikeAmmoCount > 0) {
                        div.currentIntent = DivisionIntentType.TOT_MISSILE_STRIKE;
                        div.stationPoint.set(OtcAI.getVisibleCentroidForSide(engine, div.side, target));
                    } else {
                        div.currentIntent = DivisionIntentType.WORK_FLANK;
                        computeFlankStation(engine, div, target, div.stationPoint, myGroup);
                    }
                } else if (activeMission == DivisionMissionType.TOT_ALPHA_STRIKE
                        || !div.hasMissileStrikeCapability
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
                computeStandoffStation(engine, div, target, div.stationPoint);
                if (activeMission != DivisionMissionType.STANDOFF_ENGAGEMENT
                        && div.localEnemyStrength >= 12f
                        && div.localForceRatio >= Math.max(1.35f, div.commander.commitForceRatio * 1.2f)
                        && (myGroup.forceRatio >= 1.25f || myGroup.currentPhase == BattlePhase.GENERAL_CHASE)
                        && div.effectiveHpRatio >= 0.68f
                        && dist < 2200f) {
                    div.currentIntent = DivisionIntentType.PRESS_HOME;
                } else {
                    div.currentIntent = DivisionIntentType.CLOSE_AND_ENGAGE;
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
    static boolean acceptsStrikeRequest(DivisionState div) {
        return div.avgFluxLevel < 0.60f && (div.localEnemyStrength < 20f || div.localForceRatio >= 0.90f);
    }

    /**
     * With the Timed Strikes play (outside Mission-Type Tactics) strike divisions hold their missiles for the OTC's
     * synchronized strikes instead of freelancing, except against targets that come within close range.
     */
    static boolean isSoloStrikeAllowed(BattleGroupState myGroup, float distToMissileTarget) {
        if (!myGroup.isPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES)) return true;
        if (myGroup.authority == OtcAuthority.COMMAND_BY_NEGATION) return true;
        if (myGroup.strikePlan != null) return false;
        if (myGroup.mainEffortDivision == null) return true;
        return distToMissileTarget < 2200f;
    }

    static String flankSideTag(DivisionState div, DivisionState enemyDiv, BattleGroupState myGroup) {
        float side = assignedFlankSide(div, enemyDiv, myGroup);
        if (side == 0f) return "";
        return side > 0f ? " [OTC: port flank]" : " [OTC: starboard flank]";
    }

    /** The OTC-assigned flank side for this target, or 0 if the OTC has not assigned one. */
    static float assignedFlankSide(DivisionState div, DivisionState enemyDiv, BattleGroupState myGroup) {
        if (myGroup == null || !myGroup.authority.allowsPositioning()) return 0f;
        OtcOrder task = div.otcOrder;
        if (task.type == OtcOrderType.FLANK && task.targetEnemy == enemyDiv && task.flankSide != 0f) {
            return task.flankSide;
        }
        return 0f;
    }

    /** Recomputes the current intent's maneuver station and out-of-contact wide-arc route waypoint every tick. */
    static void refreshStationPoint(
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
                    computeStandoffStation(engine, div, enemy, div.stationPoint);
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

        // Opening Advance Cohesion Pacing: while the Main Effort (Battleship/Cruiser core) is still marching into
        // initial contact (now < 35s and Main Effort not yet engaged), prevent faster escort/missile/cruiser divisions
        // from sprinting >320u ahead of the Main Effort along the battle axis and getting defeated in detail.
        if (now < 35f
                && myGroup != null
                && myGroup.mainEffortDivision != null
                && myGroup.mainEffortDivision != div
                && myGroup.mainEffortDivision.isCombatEffective()
                && myGroup.mainEffortDivision.localEnemyStrength < 10f
                && (div.currentIntent == DivisionIntentType.CLOSE_AND_ENGAGE
                || div.currentIntent == DivisionIntentType.FORM_LINE_AND_FIX
                || div.currentIntent == DivisionIntentType.HARASSING_MISSILE_ATTACK)) {
            float mainY = myGroup.mainEffortDivision.centroid.y;
            if (div.side == 0) {
                div.stationPoint.y = Math.min(div.stationPoint.y, mainY + 320f);
            } else {
                div.stationPoint.y = Math.max(div.stationPoint.y, mainY - 320f);
            }
        }

        updateDivisionOutOfContactRoute(engine, div, myGroup, enemyGroup);
    }

    /**
     * Scope Item 1: Evaluates and executes Setpiece Division Attacks in Formation:
     *  - Stage 1 (Dressing at IP): Division forms up at an Initial Point in its setpiece formation.
     *  - Stage 2 (Executing Pass): The entire formation carriage translates along the strike vector in unison.
     */
    static void updateAndApplySetpieceDivisionAttack(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            float now) {
        if (div == null) return;
        DivisionState enemy = div.targetEnemyDivision;
        boolean enemyValid = enemy != null && enemy.isCombatEffective()
                && OtcAI.isDivisionVisibleToSide(engine, div.side, enemy);
        boolean nonCombatOrRetiring = div.archetype == null
                || div.archetype.isCarrierArchetype()
                || div.archetype == DivisionArchetype.SERVICE_GROUP
                || div.getAliveMembers().size() < 2
                || !div.detachedStragglerIds.isEmpty()
                || div.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                || div.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE
                || div.currentIntent == DivisionIntentType.HOLD_IN_RESERVE
                || div.currentIntent == DivisionIntentType.CLOSE_UP
                || div.avgFluxLevel >= 0.64f
                || div.hasRouteWaypoint;

        if (!enemyValid || nonCombatOrRetiring) {
            div.activeSetpieceAttack = DivisionSetpieceAttack.NONE;
            div.setpieceStage = 0;
            return;
        }

        Vector2f visEnemyPos = OtcAI.getVisibleCentroidForSide(engine, div.side, enemy);
        float distToEnemy = distance(div.centroid, visEnemyPos);
        if (distToEnemy > 2600f) {
            div.activeSetpieceAttack = DivisionSetpieceAttack.NONE;
            div.setpieceStage = 0;
            return;
        }

        // 1. Resolve desired setpiece attack (OTC multi-division setpiece order > division doctrine > auto T-S-F-C)
        DivisionSetpieceAttack ordered = (div.otcOrder != null && div.otcOrder.isActive(now))
                ? div.otcOrder.orderedSetpieceAttack
                : null;
        DivisionSetpieceAttack desired = DivisionSetpieceAttack.NONE;
        if (ordered != null && ordered != DivisionSetpieceAttack.NONE && ordered != DivisionSetpieceAttack.AUTO_TACTICAL) {
            desired = ordered;
        } else {
            DivisionSetpieceAttack doc = div.doctrinalSetpieceAttack != null
                    ? div.doctrinalSetpieceAttack
                    : DivisionSetpieceAttack.AUTO_TACTICAL;
            if (doc == DivisionSetpieceAttack.NONE) {
                desired = DivisionSetpieceAttack.NONE;
            } else if (doc != DivisionSetpieceAttack.AUTO_TACTICAL) {
                desired = doc;
            } else {
                // Auto T-S-F-C setpiece selection: trigger setpiece passes on decisive shock/alpha strikes
                // without overriding normal WORK_FLANK or standoff stationing unless explicitly configured
                boolean isLightOrDd = div.archetype == DivisionArchetype.DESTROYER_DIVISION
                        || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION
                        || div.archetype == DivisionArchetype.FRIGATE_DIVISION
                        || div.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION;
                boolean outmatchedEhp = div.localForceRatio < 0.95f
                        || div.effectiveHpRatio < 0.70f
                        || (enemy.totalEffectiveHp > div.totalEffectiveHp * 1.35f && div.totalEffectiveHp > 100f);
                if (!isLightOrDd || !outmatchedEhp) {
                    if (div.currentIntent == DivisionIntentType.PRESS_HOME
                            || div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE) {
                        desired = DivisionSetpieceAttack.LANCE_CHARGE;
                    } else if (div.currentIntent == DivisionIntentType.TOT_MISSILE_STRIKE) {
                        desired = DivisionSetpieceAttack.WALL_SALVO_PASS;
                    }
                }
            }
        }

        if (desired == DivisionSetpieceAttack.NONE) {
            div.activeSetpieceAttack = DivisionSetpieceAttack.NONE;
            div.setpieceStage = 0;
            return;
        }

        boolean otcImmediateCommit = (div.otcOrder != null && div.otcOrder.isActive(now)
                && div.otcOrder.type == OtcOrderType.EXECUTE_STRIKE);
        if (div.setpieceStage == 0) {
            if (!otcImmediateCommit && (now - div.lastSetpieceEndTime) < 10.0f) {
                return;
            }
            div.activeSetpieceAttack = desired;
            div.setpieceStage = otcImmediateCommit ? 2 : 1;
            div.setpieceStageStartTime = now;
        } else if (div.activeSetpieceAttack != desired && ordered != null) {
            div.activeSetpieceAttack = desired;
        }

        // Compute approach & lateral basis vectors from enemy toward division
        float dx = div.centroid.x - visEnemyPos.x;
        float dy = div.centroid.y - visEnemyPos.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) {
            dx = 0f;
            dy = (div.side == 0) ? -1f : 1f;
            len = 1f;
        }
        float dirX = dx / len;
        float dirY = dy / len;
        float perpX = -dirY;
        float perpY = dirX;
        float baseStandoff = Math.max(520f, div.standoffRange);

        float stageElapsed = Math.max(0f, now - div.setpieceStageStartTime);
        if (div.setpieceStage == 1) {
            // Stage 1: Dress formation at Initial Point (IP)
            float ipDist = baseStandoff * 1.05f;
            float latShift = (div.activeSetpieceAttack == DivisionSetpieceAttack.BROADSIDE_ROLL) ? -280f : 0f;
            div.setpieceIpPoint.set(
                    visEnemyPos.x + dirX * ipDist + perpX * latShift,
                    visEnemyPos.y + dirY * ipDist + perpY * latShift);
            div.stationPoint.set(div.setpieceIpPoint);

            boolean dressed = div.stationKeepingRatio <= 0.72f
                    && distance(div.centroid, div.setpieceIpPoint) <= 420f;
            if (otcImmediateCommit || dressed || stageElapsed >= 3.8f || distToEnemy < baseStandoff * 0.85f) {
                div.setpieceStage = 2;
                div.setpieceStageStartTime = now;
                stageElapsed = 0f;
            } else {
                return;
            }
        }

        // Stage 2: Execute Setpiece Pass in locked formation carriage
        if (stageElapsed >= 9.5f) {
            div.lastSetpieceEndTime = now;
            div.setpieceStage = 0;
            div.activeSetpieceAttack = DivisionSetpieceAttack.NONE;
            return;
        }

        float passProgress = Math.min(1f, stageElapsed / 9.5f);
        switch (div.activeSetpieceAttack) {
            case LANCE_CHARGE: {
                float targetDist = Math.max(380f, baseStandoff * (0.85f - 0.30f * passProgress));
                div.setpiecePassTarget.set(
                        visEnemyPos.x + dirX * targetDist,
                        visEnemyPos.y + dirY * targetDist);
                break;
            }
            case BROADSIDE_ROLL: {
                // Wheel across the enemy bow ("Crossing the T") from -260u to +380u laterally at 0.88x standoff
                float rollDist = baseStandoff * 0.88f;
                float latRoll = -240f + 620f * passProgress;
                div.setpiecePassTarget.set(
                        visEnemyPos.x + dirX * rollDist + perpX * latRoll,
                        visEnemyPos.y + dirY * rollDist + perpY * latRoll);
                break;
            }
            case WALL_SALVO_PASS: {
                // Step in to 0.86x standoff for synchronized salvo (first 5.5s), then recoil to 1.04x standoff
                float salvoDist = (stageElapsed <= 5.5f) ? (baseStandoff * 0.86f) : (baseStandoff * 1.04f);
                div.setpiecePassTarget.set(
                        visEnemyPos.x + dirX * salvoDist,
                        visEnemyPos.y + dirY * salvoDist);
                break;
            }
            case HORNS_OF_THE_BULL: {
                float hornDist = Math.max(460f, baseStandoff * 0.82f);
                div.setpiecePassTarget.set(
                        visEnemyPos.x + dirX * hornDist,
                        visEnemyPos.y + dirY * hornDist);
                break;
            }
            case ECHELON_STRIKE:
            default: {
                float side = (div.otcOrder != null && div.otcOrder.flankSide != 0f) ? div.otcOrder.flankSide : 1f;
                float echDist = baseStandoff * 0.86f;
                float latSlide = side * (180f + 260f * passProgress);
                div.setpiecePassTarget.set(
                        visEnemyPos.x + dirX * echDist + perpX * latSlide,
                        visEnemyPos.y + dirY * echDist + perpY * latSlide);
                break;
            }
        }
        div.stationPoint.set(div.setpiecePassTarget);
    }

    static void computePatrolStation(DivisionState carrierDiv, DivisionState protectedDiv, Vector2f outStation) {
        float backSign = (carrierDiv.side == 0) ? -1f : 1f;
        outStation.set(
                protectedDiv.centroid.x,
                protectedDiv.centroid.y + backSign * Math.max(600f, carrierDiv.standoffRange * 0.45f));
    }

    /**
     * Cooperatively nudges ships in the division via ShipwideAIFlags.
     * Never overrides the player's manually piloted flagship or explicit player orders.
     */
    static void applyDivisionFlagsToShips(
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

        ShipAPI focusEnemyShip = pickFocusEnemyShip(engine, div.side, div.targetEnemyDivision, div.centroid, div.useConvergence || (myGroup != null && myGroup.useConvergence));

        // Timed strike: participants and the Main Effort all concentrate on the plan's single focus ship
        StrikePlan plan = myGroup.strikePlan;
        if (plan != null && plan.committed && plan.focusShip != null
                && plan.focusShip.isAlive() && !plan.focusShip.isHulk()
                && (plan.participants.contains(div) || div == myGroup.mainEffortDivision)
                && OtcAI.isShipVisibleToSide(engine, div.side, plan.focusShip)) {
            focusEnemyShip = plan.focusShip;
        }

        updateDivisionVoidstrikeStaging(div, myGroup, focusEnemyShip, now);

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
            boolean isDestroyerHull = ship.isDestroyer();
            float shipCurrEhp = BattlefieldFirepowerEvaluator.computeShipEffectiveHp(ship);
            float shipMaxEhp = BattlefieldFirepowerEvaluator.computeShipMaxEffectiveHp(ship);
            float shipEhpRatio = shipCurrEhp / Math.max(1f, shipMaxEhp);
            float hardFluxFrac = (ship.getFluxTracker() != null && ship.getFluxTracker().getMaxFlux() > 1f)
                    ? (ship.getFluxTracker().getHardFlux() / ship.getFluxTracker().getMaxFlux())
                    : 0f;

            boolean loneOrOutnumberedFrigateInDanger = isFrigateHull
                    && (alive.size() == 1 || (div.localForceRatio < 0.55f && ship.getFluxLevel() >= 0.58f))
                    && div.localEnemyStrength >= 8f
                    && (ship.getFluxLevel() >= 0.44f
                    || hardFluxFrac >= 0.35f
                    || (ship.getHullLevel() < 0.72f && ship.getFluxLevel() >= 0.25f));
            // Prevent fragile Destroyers (e.g. Hammerheads / Sunders) from suicidally pressing into heavy armor/shields
            // when their Effective HP (Shield + Armor + Hull HP) or hard-flux headroom is depleted!
            boolean destroyerInEhpDanger = isDestroyerHull
                    && div.localEnemyStrength >= 10f
                    && ((shipEhpRatio < 0.52f && ship.getFluxLevel() >= 0.40f)
                    || (div.localForceRatio < 0.80f && shipEhpRatio < 0.58f && (ship.getFluxLevel() >= 0.48f || hardFluxFrac >= 0.38f))
                    || (div.localForceRatio < 0.72f && ship.getFluxLevel() >= 0.62f));
            float crisisFluxThreshold = 0.82f;
            boolean shipInPersonalCrisis = !martyrdom
                    && div.localEnemyStrength >= 8f
                    && div.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT
                    && div.currentIntent != DivisionIntentType.BREAK_OFF_AND_RETIRE
                    && (loneOrOutnumberedFrigateInDanger
                    || destroyerInEhpDanger
                    || (ship.getFluxTracker() != null && ship.getFluxTracker().isOverloadedOrVenting())
                    || ship.getFluxLevel() >= crisisFluxThreshold
                    || (ship.getHullLevel() < 0.42f && ship.getFluxLevel() >= 0.45f));
            if (shipInPersonalCrisis) {
                flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
                flags.unsetFlag(AIFlags.HARASS_MOVE_IN);
                flags.unsetFlag(AIFlags.PURSUING);
                flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL * 1.4f);
                flags.setFlag(AIFlags.RUN_QUICKLY, FLAG_TTL * 1.4f);
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL * 1.4f);
                if ((loneOrOutnumberedFrigateInDanger || destroyerInEhpDanger)
                        && myGroup != null && myGroup.reserveStation != null) {
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
            dressShipStrikeCraftInFormation(ship, shipIdx, focusEnemyShip, div, myGroup, now);
        }
    }

    public static void updateDivisionVoidstrikeStaging(
            DivisionState div,
            BattleGroupState myGroup,
            ShipAPI focusEnemyShip,
            float now) {
        VoidstrikeCoordinator.updateDivisionVoidstrikeStaging(div, myGroup, focusEnemyShip, now);
    }

    public static int[] computeCompromiseStrikeWidthAndDepth(int totalUnits, int reqWidth, int reqDepth) {
        return VoidstrikeCoordinator.computeCompromiseStrikeWidthAndDepth(totalUnits, reqWidth, reqDepth);
    }

    public static Vector2f computeSquadronCraftSlotOffset(
            SquadronFormation formation,
            int craftIdx,
            int craftCount,
            int squadronRankIdx,
            int squadronDepth,
            float craftSpacing,
            float fwdX,
            float fwdY,
            float rightX,
            float rightY) {
        return VoidstrikeCoordinator.computeSquadronCraftSlotOffset(
                formation, craftIdx, craftCount, squadronRankIdx, squadronDepth,
                craftSpacing, fwdX, fwdY, rightX, rightY);
    }

    public static Vector2f computeSquadronCraftSlotOffset(
            SquadronFormation formation,
            int craftIdx,
            int craftCount,
            int squadronRankIdx,
            int squadronDepth,
            int squadronColIdx,
            int squadronWidth,
            float craftSpacing,
            float fwdX,
            float fwdY,
            float rightX,
            float rightY) {
        return VoidstrikeCoordinator.computeSquadronCraftSlotOffset(
                formation, craftIdx, craftCount, squadronRankIdx, squadronDepth,
                squadronColIdx, squadronWidth, craftSpacing, fwdX, fwdY, rightX, rightY);
    }

    static void dressShipStrikeCraftInFormation(
            ShipAPI carrier,
            int carrierIdxInDiv,
            ShipAPI focusEnemyShip,
            DivisionState div,
            BattleGroupState myGroup,
            float now) {
        VoidstrikeCoordinator.dressShipStrikeCraftInFormation(
                carrier, carrierIdxInDiv, focusEnemyShip, div, myGroup, now);
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

    static void applyIntentFlagsToShip(
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
        final float rawCombatSlotLeash;
        switch (stickiness) {
            case STRICT:
                individualEngageHorizon = Math.max(1350f, div.standoffRange * 1.25f);
                rawCombatSlotLeash = dressTol * 1.35f;
                break;
            case LOOSE:
                individualEngageHorizon = Math.max(2050f, div.standoffRange * 1.75f);
                rawCombatSlotLeash = dressTol * 2.65f;
                break;
            case FREE:
                individualEngageHorizon = Math.max(2600f, div.standoffRange * 2.25f);
                rawCombatSlotLeash = Float.MAX_VALUE;
                break;
            case BALANCED:
            default:
                individualEngageHorizon = Math.max(1650f, div.standoffRange * 1.45f);
                rawCombatSlotLeash = dressTol * 1.95f;
                break;
        }
        final float combatSlotLeash = (div.activeSetpieceAttack != null
                && div.activeSetpieceAttack != DivisionSetpieceAttack.NONE
                && div.setpieceStage > 0
                && structuredFormation)
                ? Math.min(rawCombatSlotLeash, Math.max(150f, dressTol * 1.15f))
                : rawCombatSlotLeash;
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
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
                        boolean enemyCollapsed = (focusEnemyShip.getFluxTracker() != null && focusEnemyShip.getFluxTracker().isOverloadedOrVenting())
                                || focusEnemyShip.getFluxLevel() >= 0.78f
                                || (focusEnemyShip.getHullLevel() < 0.45f && focusEnemyShip.getFluxLevel() >= 0.50f);
                        if (div.useConvergence && ship.getFluxLevel() < 0.58f && enemyCollapsed) {
                            flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL);
                            flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
                        }
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
                    if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) <= 1850f) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
                    }
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
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
                    } else {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;
                }

                case HARASSING_MISSILE_ATTACK:
                case TOT_MISSILE_STRIKE:
                    flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL * 1.5f);
                    if (focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
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
                    boolean flakDanger = isEnemyShipFlakLethalToLightHull(ship, focusEnemyShip);
                    if (flakDanger) {
                        flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                        flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL * 1.4f);
                        if (focusEnemyShip != null) {
                            directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
                            float distToEnemy = distance(ship.getLocation(), focusEnemyShip.getLocation());
                            float safeFlakFloor = ship.isFrigate() ? 820f : 760f;
                            if (distToEnemy < safeFlakFloor) {
                                flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
                            } else if (distToEnemy > 1500f) {
                                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                            }
                        }
                        break;
                    }
                    flags.unsetFlag(AIFlags.BACK_OFF);
                    flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL * 1.5f);
                    flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL * 1.6f);
                    flags.setFlag(AIFlags.PURSUING, FLAG_TTL * 1.5f);
                    if (focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
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
                            directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
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
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
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
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
                    }
                    if (distance(ship.getLocation(), slotPos) > (structuredFormation ? dressTol : Math.max(260f, div.stationSpacing * 0.55f))
                            || (!structuredFormation && crowdedByPeer && (focusEnemyShip == null || distance(ship.getLocation(), focusEnemyShip.getLocation()) > 1400f))) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case REINFORCE_DIVISION:
                    if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 1750f) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
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
                    if (focusEnemyShip != null && distToEnemy < 2200f) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
                    }
                    if (div.hasRouteWaypoint
                            || distToSlot > (structuredFormation ? dressTol * 1.15f : Math.max(320f, div.stationSpacing * 0.65f))
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
                            } else if (div.voidstrikeCoordination != VoidstrikeCoordinationMode.COORDINATED
                                    || div.voidstrikeStage == 2) {
                                directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
                            }
                        } else if (distToEnemy < 2200f) {
                            directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
                        }
                    } else {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case CLOSE_AND_ENGAGE:
                default:
                    DivisionMissionType activeMission = resolveActiveMission(div, now);
                    boolean isLightOrDestroyerDiv = div.archetype == DivisionArchetype.DESTROYER_DIVISION
                            || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION
                            || div.archetype == DivisionArchetype.FRIGATE_DIVISION
                            || div.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION;
                    boolean standoffPosture = (activeMission == DivisionMissionType.STANDOFF_ENGAGEMENT
                            || activeMission == DivisionMissionType.SCOUTING
                            || (isLightOrDestroyerDiv && div.localForceRatio < 1.15f)
                            || div.localForceRatio < 0.92f
                            || div.effectiveHpRatio < 0.68f);

                    // Composite Carrier & Escort inner discipline: non-carrier escorts screen the carrier flagship
                    if (div.archetype == DivisionArchetype.CARRIER_GROUP
                            && div.flagship != null
                            && ship != div.flagship
                            && ship.getNumFighterBays() == 0) {
                        flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                        flags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, div.flagship);
                    } else if (standoffPosture && focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
                        float distToEnemy = distance(ship.getLocation(), focusEnemyShip.getLocation());
                        float distToSlot = distance(ship.getLocation(), slotPos);
                        int divAliveCount = div.getAliveMembers().size();
                        boolean isLightOrDdDiv = div.archetype == DivisionArchetype.DESTROYER_DIVISION
                                || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION
                                || div.archetype == DivisionArchetype.FRIGATE_DIVISION
                                || div.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION;
                        boolean outmatchedInEhp = isLightOrDdDiv
                                && (div.localForceRatio < 0.95f
                                || div.effectiveHpRatio < 0.72f
                                || (div.targetEnemyDivision != null
                                && div.targetEnemyDivision.totalEffectiveHp > div.totalEffectiveHp * 1.35f
                                && div.totalEffectiveHp > 100f));
                        float enemyRangeRef = (div.targetEnemyDivision != null)
                                ? div.targetEnemyDivision.avgEngageRange
                                : div.standoffRange;
                        float effectiveStandoff = outmatchedInEhp
                                ? Math.max(div.standoffRange * 1.15f, Math.max(960f, Math.min(1250f, enemyRangeRef * 1.06f)))
                                : div.standoffRange;
                        boolean isLoneOrOutnumberedFrigOrDd = (ship.isFrigate() || ship.isDestroyer())
                                && (divAliveCount == 1 || outmatchedInEhp);
                        if (isLoneOrOutnumberedFrigOrDd) {
                            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                        }
                        boolean safeToNudgeIn = !isLoneOrOutnumberedFrigOrDd && ship.getFluxLevel() < 0.35f;
                        float backOffThreshold = outmatchedInEhp ? (effectiveStandoff * 0.92f) : (effectiveStandoff * 0.82f);
                        if (distToEnemy < backOffThreshold) {
                            // Pressed inside standoff cushion: give ground toward standoff slot
                            flags.unsetFlag(AIFlags.HARASS_MOVE_IN);
                            flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
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
                        } else if (distToEnemy > effectiveStandoff * 1.08f) {
                            // Individual Combat Movement Phase (closing to standoff gun/missile range):
                            // Never set CAMP_LOCATION onto a behind/side slot while trying to close on focusEnemyShip!
                            // Only nudge in if not an outnumbered/lone frigate or outmatched destroyer.
                            if (safeToNudgeIn) {
                                flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL);
                            }
                            if (structuredFormation && distToSlot > combatSlotLeash) {
                                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                            }
                        } else {
                            // Inside optimal standoff band [backOffThreshold .. 1.08x effectiveStandoff]:
                            // Enforce standoff slot leash (including for lone frigates and outmatched destroyers!)
                            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                            if ((structuredFormation || isLoneOrOutnumberedFrigOrDd)
                                    && distToSlot > (outmatchedInEhp ? Math.min(combatSlotLeash, 260f) : combatSlotLeash)) {
                                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                            }
                        }
                    } else if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 2600f) {
                        // Prevent lone or outnumbered frigates and fragile/fluxed destroyers (e.g. Hammerheads)
                        // from diving into enemy gunlines or Large/Medium Flak batteries!
                        float shipEhpRatio = BattlefieldFirepowerEvaluator.computeShipEffectiveHp(ship)
                                / Math.max(1f, BattlefieldFirepowerEvaluator.computeShipMaxEffectiveHp(ship));
                        boolean loneOrOutnumberedFrigate = ship.isFrigate()
                                && (div.getAliveMembers().size() == 1 || div.localForceRatio < 0.72f);
                        boolean pressuredDestroyer = ship.isDestroyer()
                                && (div.localForceRatio < 0.85f || shipEhpRatio < 0.65f || ship.getFluxLevel() >= 0.45f);
                        boolean flakDangerToLightHull = isEnemyShipFlakLethalToLightHull(ship, focusEnemyShip);
                        if (loneOrOutnumberedFrigate || pressuredDestroyer || flakDangerToLightHull) {
                            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                        } else if (activeMission == DivisionMissionType.CLOSE_ACTION) {
                            flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
                            boolean enemyCollapsed = focusEnemyShip != null
                                    && ((focusEnemyShip.getFluxTracker() != null && focusEnemyShip.getFluxTracker().isOverloadedOrVenting())
                                    || focusEnemyShip.getFluxLevel() >= 0.78f
                                    || (focusEnemyShip.getHullLevel() < 0.45f && focusEnemyShip.getFluxLevel() >= 0.50f));
                            if (div.useConvergence && ship.getFluxLevel() < 0.58f && enemyCollapsed) {
                                flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL);
                            }
                        }
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
                        float distToEnemy = distance(ship.getLocation(), focusEnemyShip.getLocation());
                        float distToSlot = distance(ship.getLocation(), slotPos);
                        boolean isWingman = (div.flagship != null && ship != div.flagship) || (div.flagship == null && shipIdx > 0);
                        float safeFlakFloor = ship.isFrigate() ? 820f : 760f;
                        if ((flakDangerToLightHull && distToEnemy < safeFlakFloor)
                                || (loneOrOutnumberedFrigate && distToEnemy < div.standoffRange * 0.80f && ship.getFluxLevel() >= 0.30f)
                                || (pressuredDestroyer && distToEnemy < div.standoffRange * 0.82f && (ship.getFluxLevel() >= 0.42f || shipEhpRatio < 0.58f))) {
                            flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
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

    static boolean hasEnemyHybridFlakScreen(DivisionState enemyDiv) {
        if (enemyDiv == null) return false;
        for (ShipAPI e : enemyDiv.getAliveMembers()) {
            if (e == null || (!e.isCapital() && !e.isCruiser())) continue;
            if (BattlefieldFirepowerEvaluator.getShipHybridFirepower(e) >= 110f) {
                return true;
            }
        }
        return false;
    }

    static boolean isEnemyShipFlakLethalToLightHull(ShipAPI ship, ShipAPI enemyShip) {
        if (ship == null || enemyShip == null) return false;
        if (!ship.isFrigate() && !ship.isDestroyer()) return false;
        if (!enemyShip.isCapital() && !enemyShip.isCruiser()) return false;
        if (enemyShip.getFluxTracker() != null && enemyShip.getFluxTracker().isOverloadedOrVenting()) return false;
        if (enemyShip.getFluxLevel() >= 0.80f) return false;
        return BattlefieldFirepowerEvaluator.getShipHybridFirepower(enemyShip) >= 110f;
    }

    /**
     * Respects explicit Command Window orders (such as CAPTURE / ASSAULT / DEFEND on a control point,
     * RALLY, ESCORT, RETREAT, etc.) so Division AI never pulls ships away from player-ordered control points.
     * In vanilla Starsector, ships without an explicit task have assignment == null or SEARCH_AND_DESTROY.
     */
    static boolean hasExplicitCommandOrder(ShipAPI ship, DivisionState div, CombatTaskManagerAPI tm) {
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

    static DivisionState findBestEnemyDivisionFor(
            CombatEngineAPI engine,
            DivisionState myDiv,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            boolean preferMissilePrey,
            DivisionState previousTarget) {

        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;
        boolean anyAbleVisibleEnemy = false;
        for (DivisionState eDiv : enemyGroup.divisions) {
            if (eDiv.isAbleCombatEffective() && OtcAI.isDivisionVisibleToSide(engine, myDiv.side, eDiv)) {
                anyAbleVisibleEnemy = true;
                break;
            }
        }

        for (DivisionState eDiv : enemyGroup.divisions) {
            if (!eDiv.isCombatEffective()) continue;
            // Strict Fog of War check: cannot target enemy divisions hidden in the Fog of War!
            if (!OtcAI.isDivisionVisibleToSide(engine, myDiv.side, eDiv)) {
                continue;
            }

            Vector2f visCentroid = OtcAI.getVisibleCentroidForSide(engine, myDiv.side, eDiv);
            float visFp = OtcAI.getVisibleFpForSide(engine, myDiv.side, eDiv);
            float dist = distance(myDiv.centroid, visCentroid);
            float score = -dist * (dist > 1500f ? 0.68f : 0.42f);

            // Item F: Heavily deprioritize retreating ("unable") enemy divisions as long as able enemy divisions remain
            if (anyAbleVisibleEnemy
                    && myGroup.currentPhase != BattlePhase.GENERAL_CHASE
                    && (eDiv.isUnableDivision || eDiv.retreatingFraction >= 0.5f)) {
                score -= 1350f;
            }

            // Item D: Bonus for enemy divisions pinched against a map edge or corner (restricted maneuver room)
            score += BattlefieldFirepowerEvaluator.computeEdgeProximityPinch(engine, visCentroid, eDiv.freeTimeRadius) * 210f;

            // Sticky target bonus prevents divisions from flip-flopping between two enemy divisions every few seconds
            if (eDiv == previousTarget && dist <= 1850f) {
                score += 280f;
            }

            // Deprioritize civilian/auxiliary groups when combat divisions exist
            if (eDiv.archetype == DivisionArchetype.SERVICE_GROUP) {
                score -= 450f;
            }

            boolean isLightDiv = myDiv.archetype == DivisionArchetype.DESTROYER_DIVISION
                    || myDiv.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION
                    || myDiv.archetype == DivisionArchetype.FRIGATE_DIVISION
                    || myDiv.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION
                    || myDiv.archetype == DivisionArchetype.PHASE_DIVISION;

            if (myDiv.useConvergence) {
                // Convergence Division Commander (Alpha Core flagship or non-human fleet):
                // Uses non-linear Phase-Space Convergence (Time + Space + Firepower rolled into one)
                score += BattlefieldFirepowerEvaluator.computeConvergenceTargetScore(engine, myDiv, eDiv, myGroup) * 0.55f;
                if (preferMissilePrey) {
                    score += eDiv.avgFluxLevel * 450f;
                } else if (isLightDiv && myDiv.totalEffectiveHp > 100f && eDiv.totalEffectiveHp > 100f) {
                    if (eDiv.totalEffectiveHp <= myDiv.totalEffectiveHp * 1.30f) {
                        score += 220f;
                    } else if (eDiv.totalEffectiveHp > myDiv.totalEffectiveHp * 1.75f) {
                        score -= 240f;
                    }
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
                    if (isLightDiv && myDiv.totalEffectiveHp > 100f && eDiv.totalEffectiveHp > 100f) {
                        // Light/Destroyer divisions prefer targets they can match or overmatch in Effective HP,
                        // avoiding head-on magnetism toward unfixed heavy cruiser/capital strongpoints!
                        if (eDiv.totalEffectiveHp <= myDiv.totalEffectiveHp * 1.30f) {
                            score += 220f;
                        } else if (eDiv.totalEffectiveHp > myDiv.totalEffectiveHp * 1.75f) {
                            score -= 240f;
                        }
                    } else {
                        score += visFp * 6f;
                    }
                }
            }

            if (score > bestScore) {
                bestScore = score;
                best = eDiv;
            }
        }
        return best;
    }

    static void directShipAndFightersOntoTarget(ShipAPI ship, ShipwideAIFlags flags, ShipAPI focusEnemyShip) {
        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, null);
    }

    static void directShipAndFightersOntoTarget(
            ShipAPI ship,
            ShipwideAIFlags flags,
            ShipAPI focusEnemyShip,
            DivisionState div) {
        if (focusEnemyShip == null || !focusEnemyShip.isAlive() || focusEnemyShip.isHulk()) return;
        flags.setFlag(AIFlags.MANEUVER_TARGET, FLAG_TTL, focusEnemyShip);
        if (ship.getNumFighterBays() > 0) {
            boolean holdingForCoord = div != null
                    && div.voidstrikeCoordination == VoidstrikeCoordinationMode.COORDINATED
                    && (div.voidstrikeStage == 0 || div.voidstrikeStage == 1);
            if (!holdingForCoord) {
                VoidstrikeTargetObservance obs = (div != null && div.voidstrikeObservance != null)
                        ? div.voidstrikeObservance
                        : VoidstrikeTargetObservance.MEDIUM;
                if (obs == VoidstrikeTargetObservance.HIGH) {
                    ship.setForceCarrierTarget(focusEnemyShip);
                    ship.setForceCarrierTargetTime(2.2f);
                    flags.setFlag(AIFlags.CARRIER_FIGHTER_TARGET, 2.2f, focusEnemyShip);
                } else if (obs == VoidstrikeTargetObservance.MEDIUM) {
                    ship.setForceCarrierTarget(focusEnemyShip);
                    ship.setForceCarrierTargetTime(1.2f);
                    flags.setFlag(AIFlags.CARRIER_FIGHTER_TARGET, 1.2f, focusEnemyShip);
                } else {
                    // FREE observance: light carrier target suggestion only
                    flags.setFlag(AIFlags.CARRIER_FIGHTER_TARGET, 0.75f, focusEnemyShip);
                }
            }
        }
        ShipAPI currTarget = ship.getShipTarget();
        float distFocus = distance(ship.getLocation(), focusEnemyShip.getLocation());
        boolean convLock = div != null && div.useConvergence && distFocus <= 1850f;
        boolean preferFocus = currTarget == null
                || !currTarget.isAlive()
                || currTarget.isHulk()
                || convLock
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
    static void applyBasicShipAiTargetOverride(ShipAPI ship, ShipAPI focusEnemyShip) {
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

    static DivisionState findEndangeredFriendlyDivision(DivisionState myDiv, BattleGroupState myGroup) {
        DivisionState best = null;
        float bestUrgency = 0f;
        for (DivisionState other : myGroup.divisions) {
            if (other == myDiv || !other.isAbleCombatEffective()) continue;
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

    static DivisionState findHeavyFriendlyDivisionToScreen(DivisionState myDiv, BattleGroupState myGroup) {
        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;
        for (DivisionState other : myGroup.divisions) {
            if (other == myDiv || !other.isAbleCombatEffective()) continue;
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

    static ShipAPI pickFocusEnemyShip(
            CombatEngineAPI engine,
            int observerSide,
            DivisionState enemyDiv,
            Vector2f fromPoint) {
        return pickFocusEnemyShip(engine, observerSide, enemyDiv, fromPoint, false);
    }

    static ShipAPI pickFocusEnemyShip(
            CombatEngineAPI engine,
            int observerSide,
            DivisionState enemyDiv,
            Vector2f fromPoint,
            boolean useConvergence) {
        if (enemyDiv == null) return null;
        List<ShipAPI> enemies = enemyDiv.getAliveMembers();
        ShipAPI best = null;
        float bestScore = -Float.MAX_VALUE;
        Vector2f refPt = (useConvergence && enemyDiv.centroid != null) ? enemyDiv.centroid : fromPoint;
        for (ShipAPI e : enemies) {
            if (!OtcAI.isShipVisibleToSide(engine, observerSide, e)) continue;
            float d = distance(refPt, e.getLocation());
            float score;
            if (useConvergence) {
                // Convergence Fleet-Wide Kill-Target Synchronization:
                // All friendly divisions engaging enemyDiv lock onto the exact same highest-vulnerability hull
                boolean ovl = e.getFluxTracker() != null && e.getFluxTracker().isOverloadedOrVenting();
                float hardFrac = (e.getFluxTracker() != null && e.getFluxTracker().getMaxFlux() > 1f)
                        ? (e.getFluxTracker().getHardFlux() / e.getFluxTracker().getMaxFlux())
                        : 0f;
                score = -d * 0.25f
                        + (ovl ? 850f : 0f)
                        + e.getFluxLevel() * 820f
                        + hardFrac * 450f
                        + (1f - e.getHullLevel()) * 680f;
            } else {
                // Standard Human Division Commander: prefers closer targets and high-flux/exposed targets from division centroid
                score = -d * 0.45f + e.getFluxLevel() * 550f + (1f - e.getHullLevel()) * 400f;
            }
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
    static BattleObjectiveAPI findTaskManagerObjectiveForDivision(
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

    static BattleObjectiveAPI findNearestContestObjective(
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

    static boolean isObjectiveClaimedByOtherDivision(
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

    static boolean isShipCrowdedByDivisionPeer(ShipAPI ship, List<ShipAPI> alive, float minSpacing) {
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
    static Vector2f computeShipFormationSlot(
            CombatEngineAPI engine,
            ShipAPI ship,
            int shipIdx,
            List<ShipAPI> alive,
            DivisionState div,
            BattleGroupState myGroup) {
        return DivisionFormationNavigator.computeShipFormationSlot(
                engine, ship, shipIdx, alive, div, myGroup);
    }

    public static Vector2f computeFormationSlotPositionForAnchor(
            DivisionFormation formation,
            int shipIdx,
            List<ShipAPI> alive,
            ShipAPI ship,
            float anchorX,
            float anchorY,
            float headingX,
            float headingY,
            float spacing) {
        return DivisionFormationNavigator.computeFormationSlotPositionForAnchor(
                formation, shipIdx, alive, ship, anchorX, anchorY, headingX, headingY, spacing);
    }

    public static float[] computeLocalFormationOffset(
            DivisionFormation formation,
            int k,
            int n,
            float spacing) {
        return DivisionFormationNavigator.computeLocalFormationOffset(formation, k, n, spacing);
    }

    public static int computeProtectiveCircleCoreCount(int n) {
        return DivisionFormationNavigator.computeProtectiveCircleCoreCount(n);
    }

    public static float computeProtectiveCircleCorePriority(ShipAPI s, DivisionState div) {
        return DivisionFormationNavigator.computeProtectiveCircleCorePriority(s, div);
    }

    static void computeStandoffStation(
            CombatEngineAPI engine,
            DivisionState div,
            DivisionState enemyDiv,
            Vector2f outStation) {
        DivisionFormationNavigator.computeStandoffStation(engine, div, enemyDiv, outStation);
    }

    static void computeReinforceStation(
            CombatEngineAPI engine,
            DivisionState myDiv,
            DivisionState endangeredDiv,
            Vector2f outStation,
            BattleGroupState myGroup) {
        DivisionFormationNavigator.computeReinforceStation(engine, myDiv, endangeredDiv, outStation, myGroup);
    }

    static void computeScreeningStation(
            CombatEngineAPI engine,
            DivisionState myDiv,
            DivisionState protectedDiv,
            DivisionState enemyDiv,
            Vector2f outStation,
            int side) {
        DivisionFormationNavigator.computeScreeningStation(engine, myDiv, protectedDiv, enemyDiv, outStation, side);
    }

    static void computeFlankStation(
            CombatEngineAPI engine,
            DivisionState flankerDiv,
            DivisionState enemyDiv,
            Vector2f outStation,
            BattleGroupState myGroup) {
        DivisionFormationNavigator.computeFlankStation(engine, flankerDiv, enemyDiv, outStation, myGroup);
    }

    static void updateDivisionOutOfContactRoute(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup) {
        DivisionFormationNavigator.updateDivisionOutOfContactRoute(engine, div, myGroup, enemyGroup);
    }

    static Vector2f computeOutOfContactRoutePoint(
            CombatEngineAPI engine,
            int observerSide,
            Vector2f startPos,
            Vector2f goalPos,
            DivisionState movingDiv,
            DivisionState targetEnemyDiv,
            float existingSideSign,
            BattleGroupState enemyGroup,
            float[] outSideSign) {
        return DivisionFormationNavigator.computeOutOfContactRoutePoint(
                engine, observerSide, startPos, goalPos, movingDiv, targetEnemyDiv, existingSideSign, enemyGroup, outSideSign);
    }

    static boolean isShipNearVisibleEnemy(
            CombatEngineAPI engine,
            int observerSide,
            Vector2f pos,
            BattleGroupState enemyGroup,
            float radius) {
        return DivisionFormationNavigator.isShipNearVisibleEnemy(engine, observerSide, pos, enemyGroup, radius);
    }

    static void clampToMapBounds(CombatEngineAPI engine, Vector2f pt, float margin) {
        DivisionFormationNavigator.clampToMapBounds(engine, pt, margin);
    }

    static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
