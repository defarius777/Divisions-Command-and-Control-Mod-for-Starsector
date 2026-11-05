package divisionai;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import divisionai.DivisionModel.BattlePhase;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.OtcOrderType;
import divisionai.DivisionModel.OtcOrder;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionIntentType;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionModel.ShipRankState;
import divisionai.DivisionModel.StrikePlan;
import divisionai.DivisionModel.TacticalZoneType;
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
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

public class DivisionCommanderAI {

    private static final Logger LOG = Logger.getLogger(DivisionCommanderAI.class);
    static final float STRIKE_WINDOW_DURATION = 14.0f;
    static final float FLAG_TTL = 0.85f;

    static boolean resolvedBasicAiHandles = false;
    static Class<?> basicShipAiClass = null;
    static Class<?> shipAiWrapperClass = null;
    static Class<?> obfShipClass = null;
    static MethodHandle unwrapAiHandle = null;
    static MethodHandle setTargetOverrideHandle = null;

    static boolean resolvedTaskManagerHandles = false;
    static Class<?> obfFleetManagerClass = null;
    static Class<?> obfTaskManagerClass = null;
    static Class<?> obfDeployedMemberClass = null;
    static MethodHandle getDeployedFleetMemberHandle = null;
    static MethodHandle hasDirectOrdersHandle = null;
    static MethodHandle clearAssignedOrdersHandle = null;

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
            boolean fullAssaultUrgent = OtcAI.isFullAssaultActive(engine, myGroup)
                    && (div.archetype == null || !div.archetype.isCarrierArchetype())
                    && !div.underDirectObjectiveOrder
                    && div.avgFluxLevel < 0.86f
                    && div.currentIntent != DivisionIntentType.PRESS_HOME
                    && div.currentIntent != DivisionIntentType.GIVE_GENERAL_CHASE;
            if (fullAssaultUrgent || (cadenceDue && (!committed || hasUrgentTrigger(engine, div, myGroup, now)))) {
                DivisionIntentType prevIntent = div.currentIntent;
                DivisionState prevEnemy = div.targetEnemyDivision;
                DivisionState prevFriendly = div.targetFriendlyDivision;
                BattleObjectiveAPI prevObjective = div.targetObjective;

                boolean midRamLanceCharge = div.rammingMode != PermanentDivisionManager.DivisionRammingMode.OFF
                        && div.ramCapableMemberCount >= 1
                        && div.avgFluxLevel < 0.72f
                        && div.targetEnemyDivision != null
                        && div.targetEnemyDivision.isCombatEffective()
                        && ((div.synchronizedSkillManeuverActive && div.synchronizedSkillStage > 0)
                        || (div.activeSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE && div.setpieceStage > 0)
                        || div.hasActiveLinearSkillBurn);
                if (midRamLanceCharge) {
                    if (div.currentIntent != DivisionIntentType.PRESS_HOME
                            && div.currentIntent != DivisionIntentType.CLOSE_AND_ENGAGE
                            && div.currentIntent != DivisionIntentType.WORK_FLANK
                            && div.currentIntent != DivisionIntentType.TOT_ALPHA_STRIKE) {
                        div.currentIntent = DivisionIntentType.PRESS_HOME;
                    }
                } else {
                    selectDivisionIntent(engine, div, myGroup, enemyGroup, now);
                }
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
        // Refresh coarse fleet-wide general signals on any friendly independent ships in the battle group
        OtcAI.applyFleetIndependentCommands(engine, myGroup, enemyGroup, now);
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
        boolean fullAssaultBreakup = OtcAI.isFullAssaultActive(engine, myGroup)
                && (div.archetype == null || !div.archetype.isCarrierArchetype());
        boolean urgentBreakup = martyrdom
                || fullAssaultBreakup
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
            } else if (myGroup != null
                    && myGroup.activeFleetWideFormation != null
                    && !OtcAI.isFullAssaultActive(engine, myGroup)
                    && aliveCount >= 2
                    && (base == DivisionFormation.LINE_ABREAST || base == DivisionFormation.FORWARD_WEDGE)) {
                DivisionFormation harmonized = resolveFleetHarmonizedDivisionFormation(
                        myGroup.activeFleetWideFormation, div, myGroup, base);
                if (harmonized != null) {
                    desired = harmonized;
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
                    || !div.initialDeploymentMarshalled
                    || div.lastFormationChangeTime <= 0f
                    || div.activeFormation == DivisionFormation.NONE
                    || (now - div.lastFormationChangeTime) >= 4.0f;
            if (immediate) {
                div.activeFormation = desired;
                div.lastFormationChangeTime = now;
            }
        }
    }

    /**
     * Automatically harmonizes a division's intra-division formation with its assigned wing/rank/role inside the
     * active Fleet-Wide Formation when the division is using a default formation (Line Abreast / Forward Wedge).
     */
    public static DivisionFormation resolveFleetHarmonizedDivisionFormation(
            FleetWideFormation fleetFm,
            DivisionState div,
            BattleGroupState myGroup,
            DivisionFormation base) {
        if (fleetFm == null || div == null) return base;
        boolean isCarrierOrSupport = div.archetype != null
                && (div.archetype.isCarrierArchetype() || div.archetype == DivisionArchetype.SERVICE_GROUP);
        if (isCarrierOrSupport) {
            return (fleetFm == FleetWideFormation.FLEET_PROTECTIVE_BASTION)
                    ? DivisionFormation.PROTECTIVE_CIRCLE
                    : DivisionFormation.REVERSE_SEMICIRCLE;
        }
        boolean isMainOrCenter = (myGroup != null && div == myGroup.mainEffortDivision)
                || div.fleetFormationWingSide == 0;
        switch (fleetFm) {
            case NONE:
                return base;
            case GRAND_WEDGE_SPEARHEAD:
            case REFUSED_FLANK_OBLIQUE:
                if (isMainOrCenter) return DivisionFormation.FORWARD_WEDGE;
                return (div.fleetFormationWingSide < 0)
                        ? DivisionFormation.ECHELON_LEFT
                        : DivisionFormation.ECHELON_RIGHT;
            case REFUSED_FLANK_LEFT:
                if (isMainOrCenter) return DivisionFormation.FORWARD_WEDGE;
                return (div.fleetFormationWingSide > 0)
                        ? DivisionFormation.ECHELON_RIGHT
                        : DivisionFormation.ECHELON_LEFT;
            case REVERSE_WEDGE:
                if (isMainOrCenter) return DivisionFormation.REVERSE_WEDGE;
                return (div.fleetFormationWingSide < 0)
                        ? DivisionFormation.ECHELON_RIGHT
                        : DivisionFormation.ECHELON_LEFT;
            case CRESCENT_HORNS:
                if (isMainOrCenter) return DivisionFormation.REVERSE_SEMICIRCLE;
                return (div.fleetFormationWingSide < 0)
                        ? DivisionFormation.ECHELON_RIGHT
                        : DivisionFormation.ECHELON_LEFT;
            case REVERSE_CRESCENT:
                if (isMainOrCenter) return DivisionFormation.FORWARD_SEMICIRCLE;
                return (div.fleetFormationWingSide < 0)
                        ? DivisionFormation.ECHELON_LEFT
                        : DivisionFormation.ECHELON_RIGHT;
            case CHECKERBOARD_QUINCUNX:
                return (div.fleetFormationRank == 0)
                        ? DivisionFormation.FORWARD_WEDGE
                        : DivisionFormation.LINE_ABREAST;
            case FLEET_PROTECTIVE_BASTION:
                if (isMainOrCenter) return DivisionFormation.FORWARD_SEMICIRCLE;
                return (div.fleetFormationWingSide < 0)
                        ? DivisionFormation.ECHELON_LEFT
                        : DivisionFormation.ECHELON_RIGHT;
            case GRAND_COLUMN_IN_DEPTH:
                return (div.fleetFormationRank == 0)
                        ? DivisionFormation.FORWARD_WEDGE
                        : DivisionFormation.LINE_AHEAD;
            case GRAND_LINE_ABREAST:
            case DOCTRINAL_AUTO:
            default:
                if (div.isFastFlanker && div.fleetFormationWingSide != 0) {
                    return (div.fleetFormationWingSide < 0)
                            ? DivisionFormation.ECHELON_LEFT
                            : DivisionFormation.ECHELON_RIGHT;
                }
                return base;
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
            case TRADE_SPACE_FOR_TIME:
            case FEIGNED_RETREAT:
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
        boolean fullAssault = OtcAI.isFullAssaultActive(engine, myGroup);
        if (fullAssault
                && (div.archetype == null || !div.archetype.isCarrierArchetype())
                && !div.underDirectObjectiveOrder
                && div.currentIntent != DivisionIntentType.PRESS_HOME
                && div.currentIntent != DivisionIntentType.GIVE_GENERAL_CHASE) {
            return true;
        }
        boolean martyrdom = isMartyrdomActive(div, myGroup, now);
        if (!martyrdom && !fullAssault && isStrikeRunOver(div, now)) return true;
        if (!martyrdom && (!fullAssault || div.avgFluxLevel >= 0.86f) && isUnderHighFluxPressure(div, myGroup)
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

        boolean tradingFavorablyInRange = div.inRangeFirepowerScore > 30f
                && div.weaponReadinessRatio >= 0.35f
                && div.firepowerExchangeRatio >= 0.72f
                && div.avgFluxLevel < 0.62f;
        boolean windowElapsedAndPressured = ((now - div.strikeCommittedTimestamp) >= STRIKE_WINDOW_DURATION)
                && !tradingFavorablyInRange
                && (div.avgFluxLevel >= 0.55f || (div.avgFluxLevel >= 0.42f && div.localEnemyStrength >= 14f && div.localForceRatio < 0.75f));
        boolean missileRunComplete = inMissileStrike && (windowElapsedAndPressured
                || div.avgFluxLevel >= Math.max(0.62f, div.commander.rotationFluxThreshold * 0.92f)
                || (div.hasMissileStrikeCapability && div.usableStrikeAmmoCount <= 0 && div.avgFluxLevel >= 0.52f));

        // ToT Alpha Strike ("get out of dodge"): dump guns/torpedoes over an attack pass,
        // but do not abandon a winning gun exchange at 30-45% flux while weapons are still ready!
        float alphaElapsed = now - div.strikeCommittedTimestamp;
        boolean totAlphaRunComplete = inTotAlphaStrike && ((alphaElapsed >= 11.5f && !tradingFavorablyInRange && div.avgFluxLevel >= 0.50f)
                || div.avgFluxLevel >= 0.64f
                || (div.maxMemberFluxLevel >= 0.82f && (!div.inDoubleLineRelief || div.avgFluxLevel >= 0.58f))
                || (div.hasMissileStrikeCapability && div.usableStrikeAmmoCount <= 0 && alphaElapsed >= 6.0f && div.avgFluxLevel >= 0.48f)
                || (div.localForceRatio < 0.45f && div.localEnemyStrength >= 16f && div.avgFluxLevel >= 0.48f));

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

        // Protect fragile or ram-capable destroyer divisions (e.g. Hammerhead / Sunder / Rambunctious packs) from
        // fighting to the death when their Effective HP or post-ram shields are depleted against heavy resistance!
        boolean isFragileDestroyerDiv = (div.archetype == DivisionArchetype.DESTROYER_DIVISION
                || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION);
        List<ShipAPI> alive = div.getAliveMembers();
        int endangeredMemberCount = 0;
        int postRamExposedCount = 0;
        for (int i = 0; i < alive.size(); i++) {
            ShipAPI m = alive.get(i);
            if (m == null || !m.isAlive() || m.isHulk()) continue;
            boolean overloaded = m.getFluxTracker() != null && m.getFluxTracker().isOverloadedOrVenting();
            boolean highFluxOrLowHull = m.getFluxLevel() >= 0.66f
                    || (m.getHullLevel() < 0.58f && m.getFluxLevel() >= 0.40f);
            boolean ramRecoveringUnderFire = false;
            ShipSystemCoordinator.ShipSkillProfile sp = ShipSystemCoordinator.getSkillProfile(m);
            if (sp.isDesignedToRam && m.getSystem() != null && !m.getSystem().isActive() && !m.getSystem().isChargeup()) {
                if (m.getSystem().getCooldownRemaining() > 0.4f
                        && div.localEnemyStrength >= 10f
                        && (m.getFluxLevel() >= 0.58f
                        || (m.getHullLevel() < 0.64f && m.getFluxLevel() >= 0.36f)
                        || (div.localForceRatio < 0.68f && m.getFluxLevel() >= 0.45f))) {
                    ramRecoveringUnderFire = true;
                    postRamExposedCount++;
                }
            }
            if (overloaded || highFluxOrLowHull || ramRecoveringUnderFire) {
                endangeredMemberCount++;
            }
        }
        boolean multiShipExtractionNeeded = div.localEnemyStrength >= 10f
                && ((alive.size() >= 2 && endangeredMemberCount >= 2 && endangeredMemberCount * 2 >= alive.size()
                && (div.localForceRatio < 0.85f || inCrossfireCordon || div.avgFluxLevel >= 0.56f))
                || (postRamExposedCount >= 2 && (div.localForceRatio < 0.78f || inCrossfireCordon || div.avgFluxLevel >= 0.52f)));

        boolean ehpDepletedUnderFire = div.localEnemyStrength >= 10f
                && div.avgHullLevel < 0.88f
                && ((div.effectiveHpRatio < 0.48f && (div.avgFluxLevel >= 0.52f || div.localForceRatio < 0.72f))
                || (isFragileDestroyerDiv && div.effectiveHpRatio < 0.54f && div.localForceRatio < 0.68f && div.avgFluxLevel >= 0.55f)
                || ((isFragileDestroyerDiv || div.ramCapableMemberCount >= 1)
                && div.effectiveHpRatio < 0.64f && div.localForceRatio < 0.82f
                && (div.avgFluxLevel >= 0.42f || div.maxMemberFluxLevel >= 0.58f)));

        boolean isHeavyLineDiv = div.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                || div.archetype == DivisionArchetype.MISSILE_BATTLESHIP_DIVISION
                || div.archetype == DivisionArchetype.CRUISER_DIVISION
                || div.archetype == DivisionArchetype.MISSILE_CRUISER_DIVISION
                || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION;
        float effFluxThreshold = Math.max(0.64f, div.commander.rotationFluxThreshold);
        boolean highFluxPressure = div.avgFluxLevel >= effFluxThreshold
                || loneFrigateOvermatched
                || multiShipExtractionNeeded
                || (!isHeavyLineDiv && ehpDepletedUnderFire)
                || (!isHeavyLineDiv && div.maxMemberFluxLevel >= 0.76f && div.localEnemyStrength > 10f && (div.localForceRatio < 0.75f || inCrossfireCordon))
                || (!isHeavyLineDiv && div.avgFluxLevel >= 0.52f && div.localEnemyStrength > 12f && div.localForceRatio < 0.62f && div.commander.aggressionBias < 1.35f)
                || (!isHeavyLineDiv && inCrossfireCordon && (div.maxMemberFluxLevel >= 0.60f || (div.avgFluxLevel >= 0.36f && div.localForceRatio < 0.72f)));
        // Intra-Division Double-Line Rank Relief: if the division is NOT trapped in a 3-sided crossfire cordon
        // and still maintains its minimum healthy Front-Rank Engagement Zone (div.inDoubleLineRelief == true),
        // individual high-flux ships step back into the Rear-Rank Safe Zone while healthy ships keep firing in the
        // Front Rank — unless multiple ships or post-ram destroyers need full division extraction!
        if (highFluxPressure
                && div.inDoubleLineRelief
                && !inCrossfireCordon
                && !loneFrigateOvermatched
                && !multiShipExtractionNeeded
                && div.avgFluxLevel < 0.74f) {
            highFluxPressure = false;
        }
        // Item C: If our division is actively delivering strong in-range firepower and trading favorably
        // (and is NOT trapped in an L/U crossfire pocket or multi-ship extraction crisis), do not sacrifice gun uptime!
        if (highFluxPressure
                && !inCrossfireCordon
                && !loneFrigateOvermatched
                && !multiShipExtractionNeeded
                && div.inRangeFirepowerScore > 40f
                && div.firepowerExchangeRatio >= 0.85f
                && div.avgFluxLevel < div.commander.rotationFluxThreshold
                && div.maxMemberFluxLevel < 0.86f) {
            highFluxPressure = false;
        }
        boolean canRotateOut = (div != myGroup.mainEffortDivision
                || (!isHeavyLineDiv && multiShipExtractionNeeded)
                || div.avgFluxLevel >= 0.75f
                || div.effectiveHpRatio < 0.42f);
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
        div.underDirectObjectiveOrder = (intent == DivisionIntentType.TAKE_AND_HOLD && "under direct orders".equals(reason));
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
        DivisionState missileTargetEnemyDiv = (taskActive && task.targetEnemy != null && nearestEnemyDiv == task.targetEnemy && auth.isBinding())
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
        DivisionMissionType activeMission = resolveActiveMission(div, now);
        String missionPhaseTag = isInOpeningWindow(div, now) ? "Opening: " : "Main: ";
        boolean isHeavyLineOrCarrier = (div.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                || div.archetype.isCarrierArchetype());

        // Step 1: Explicit Command Window objective order (Player holds absolute C2 authority at all times!
        // Never reject a player-ordered Control Point capture because of localEnemyStrength or OTC tasking;
        // only yield if the division is critically fluxed out >= 82%).
        if (commandAssignedObj != null && !div.archetype.isCarrierArchetype() && div.avgFluxLevel < 0.82f) {
            div.targetObjective = commandAssignedObj;
            div.stationPoint.set(commandAssignedObj.getLocation());
            commitIntent(engine, div, DivisionIntentType.TAKE_AND_HOLD,
                    commandAssignedObj.getDisplayName(), "under direct orders", now);
            return;
        }

        // Step 1b: Player Full Assault Authority (unconditional aggressive attack across all non-carrier combat
        // divisions unless critically overloaded >= 86% flux; suppresses standoff kiting, screening, reserve holds,
        // trade-space-for-time, feigned retreat, and post-strike retirement).
        boolean fullAssault = OtcAI.isFullAssaultActive(engine, myGroup);
        if (fullAssault && !div.archetype.isCarrierArchetype() && div.archetype != DivisionArchetype.SERVICE_GROUP) {
            if (div.avgFluxLevel < 0.86f) {
                if (nearestEnemyDiv != null) {
                    Vector2f visEnemyPos = OtcAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv);
                    div.targetEnemyDivision = nearestEnemyDiv;
                    div.stationPoint.set(visEnemyPos);
                    commitIntent(engine, div, DivisionIntentType.PRESS_HOME,
                            nearestEnemyDiv.displayName, "Full Assault", now);
                    return;
                }
                if (uncapturedObj != null) {
                    div.targetObjective = uncapturedObj;
                    div.stationPoint.set(uncapturedObj.getLocation());
                    commitIntent(engine, div, DivisionIntentType.TAKE_AND_HOLD,
                            uncapturedObj.getDisplayName(), "Full Assault", now);
                    return;
                }
                float scoutDirY = (div.side == 0) ? 1600f : -1600f;
                div.stationPoint.set(div.centroid.x * 0.85f, div.centroid.y + scoutDirY);
                commitIntent(engine, div, DivisionIntentType.CLOSE_AND_ENGAGE,
                        "enemy forces", "Full Assault", now);
                return;
            }
        }

        // Steps 2 & 3: Post-strike retirement or high-flux line rotation / division extraction (HAUL_OUT_TO_VENT)
        if (!martyrdom && trySelectWithdrawalOrVentIntent(
                engine, div, myGroup, prevIntent, nearestEnemyDiv, heavyFriendlyToScreen, now)) {
            return;
        }

        // Step 4: Active OTC orchestration order
        if (taskActive && applyOtcOrder(engine, div, myGroup, task, auth, activeMission, prevIntent, now)) {
            return;
        }

        // Step 5: Mission-driven objective seizure (Main Effort stays on its fixing task instead;
        // under Consent + Orchestrated Advance, non-scouting battleline divisions stay in their Grand Formation lanes)
        boolean stationedByOtc = taskActive && task.type == OtcOrderType.FORM_LINE && auth.allowsPositioning();
        boolean holdGrandFormationAdvance = OtcAI.isOrchestratedAdvanceActive(engine, myGroup, enemyGroup)
                && activeMission != DivisionMissionType.SCOUTING
                && (!div.isFastFlanker || div.fleetFormationRank <= 1);
        if (!stationedByOtc
                && !holdGrandFormationAdvance
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
            DivisionFormationNavigator.computeReinforceStation(engine, div, endangeredFriendlyDiv, div.stationPoint, myGroup);
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
        boolean linearSkillBurnInFlight = div.hasActiveLinearSkillBurn || div.synchronizedSkillManeuverActive;
        boolean enterCloseUp = div.stationKeepingRatio > 1.48f && !carrierHasStrikeTarget && !linearSkillBurnInFlight;
        boolean stayClosingUp = (prevIntent == DivisionIntentType.CLOSE_UP) && div.stationKeepingRatio > 1.08f
                && !carrierHasStrikeTarget && !linearSkillBurnInFlight;
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
                engine, div, myGroup, enemyGroup, activeMission, missionPhaseTag, prevIntent,
                nearestEnemyDiv, missileTargetEnemyDiv, heavyFriendlyToScreen,
                distToVisibleEnemy, distToMissileTarget, strikeCooldownElapsed, soloStrikeAllowed, martyrdom, now)) {
            return;
        }

        // Steps 10-13: Fallback screening, opportunistic torpedo/flank strikes, and line advance
        selectFallbackTacticalIntent(
                engine, div, myGroup, enemyGroup, prevIntent, isHeavyLineOrCarrier,
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

        // 1. Torpedo strike window finished / torpedoes expended / flux built up:
        // Item E: If flux is not critical (< 0.72) and nearestEnemyDiv is visible, Trade Space for Time
        // (offset centroid backward/laterally while keeping guns firing) instead of turning tail to reserveStation!
        if (isStrikeRunOver(div, now)) {
            boolean isLightOrDd = div.archetype == DivisionArchetype.FRIGATE_DIVISION
                    || div.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION
                    || div.archetype == DivisionArchetype.DESTROYER_DIVISION
                    || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION;
            boolean needsPostStrikeSpace = (isLightOrDd && (div.avgFluxLevel >= 0.42f || div.localForceRatio < 0.85f))
                    || (!div.inDoubleLineRelief && div.avgFluxLevel >= 0.52f)
                    || div.avgFluxLevel >= 0.62f
                    || (div.crossfireCordonIndex >= 0.36f && div.avgFluxLevel >= 0.44f);
            if (!needsPostStrikeSpace && div.avgFluxLevel < 0.72f) {
                div.lastBreakOffTimestamp = now;
                return false;
            }
            if (nearestEnemyDiv != null && nearestEnemyDiv.isCombatEffective() && div.avgFluxLevel < 0.72f) {
                div.targetEnemyDivision = nearestEnemyDiv;
                BattlefieldFirepowerEvaluator.computeTradeSpaceForTimeOffset(
                        engine, div, nearestEnemyDiv, myGroup, div.spaceOffsetVector);
                div.stationPoint.set(
                        div.centroid.x + div.spaceOffsetVector.x,
                        div.centroid.y + div.spaceOffsetVector.y);
                div.tradeSpaceStartTime = now;
                div.lastBreakOffTimestamp = now;
                String reason = String.format("offset (%.0f, %.0f), flux %.0f%%",
                        div.spaceOffsetVector.x, div.spaceOffsetVector.y, div.avgFluxLevel * 100f);
                return commitIntent(engine, div, DivisionIntentType.TRADE_SPACE_FOR_TIME,
                        nearestEnemyDiv.displayName, reason, now);
            }
            div.targetEnemyDivision = nearestEnemyDiv;
            div.stationPoint.set(myGroup.reserveStation);
            div.lastBreakOffTimestamp = now;
            String targetLabel = nearestEnemyDiv != null ? nearestEnemyDiv.displayName : "contact zone";
            String reason = String.format("avg flux %.0f%%", div.avgFluxLevel * 100f);
            return commitIntent(engine, div, DivisionIntentType.BREAK_OFF_AND_RETIRE, targetLabel, reason, now);
        }

        // 2. High flux / damaged division -> Rotate to reserve behind a friendly division
        // Hold HAUL_OUT_TO_VENT until BOTH average flux and the most fluxed ship (e.g. a second Hammerhead)
        // have vented down to safe levels when in an L/U crossfire pocket, preventing premature re-entry!
        boolean frontRankReadyToReengage = div.inDoubleLineRelief
                && div.crossfireCordonIndex < 0.30f
                && div.avgFluxLevel < 0.48f;
        boolean stillRotating = prevIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                && !frontRankReadyToReengage
                && (div.avgFluxLevel >= div.commander.rotationFluxThreshold * 0.52f
                || (div.maxMemberFluxLevel >= 0.45f && div.crossfireCordonIndex >= 0.30f)
                || (div.crossfireCordonIndex >= 0.38f && div.avgFluxLevel >= 0.28f));
        boolean fightingRetirementVent = (myGroup.currentPhase == BattlePhase.FIGHTING_RETIREMENT && div.avgFluxLevel >= 0.32f);
        if (isUnderHighFluxPressure(div, myGroup) || stillRotating || fightingRetirementVent) {
            DivisionState candidateFriend = (myGroup.mainEffortDivision != null && myGroup.mainEffortDivision != div)
                    ? myGroup.mainEffortDivision
                    : heavyFriendlyToScreen;
            DivisionState ventBehindFriend = null;
            // Inter-Division Mutual Cover: pick the nearest healthy, consenting friendly division to slide behind.
            // Preference: the OTC's designated line-pass coverer, then the nearest heavy/line division.
            DivisionState otcCoverer = myGroup.linePassCovererByDivisionId.get(div.id);
            if (otcCoverer != null
                    && otcCoverer.isAbleCombatEffective()
                    && otcCoverer != div
                    && otcCoverer.avgFluxLevel < 0.62f
                    && (div.archetype.isCarrierArchetype() || !otcCoverer.archetype.isCarrierArchetype())) {
                candidateFriend = otcCoverer;
            }
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

            // Item C & E: If there is NO covering friendly division (ventBehindFriend == null) and enemies are actively
            // shooting us in gun range, heavy line divisions (Cruisers/Battleships) with healthy hulls Trade Space for Time
            // instead of turning tail; whereas Frigate/Destroyer/Ramming divisions execute a high-speed extraction!
            boolean isLightOrRamDiv = div.archetype == DivisionArchetype.FRIGATE_DIVISION
                    || div.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION
                    || div.archetype == DivisionArchetype.PHASE_DIVISION
                    || div.archetype == DivisionArchetype.DESTROYER_DIVISION
                    || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION
                    || div.ramCapableMemberCount >= 1;
            if (ventBehindFriend == null
                    && !isLightOrRamDiv
                    && !fightingRetirementVent
                    && div.crossfireCordonIndex < 0.35f
                    && div.incomingInRangeFirepower > 35f
                    && div.avgHullLevel >= 0.72f
                    && div.avgFluxLevel < div.commander.rotationFluxThreshold
                    && div.maxMemberFluxLevel < 0.86f) {
                if (nearestEnemyDiv != null && nearestEnemyDiv.isCombatEffective()) {
                    div.targetEnemyDivision = nearestEnemyDiv;
                    BattlefieldFirepowerEvaluator.computeTradeSpaceForTimeOffset(
                            engine, div, nearestEnemyDiv, myGroup, div.spaceOffsetVector);
                    div.stationPoint.set(
                            div.centroid.x + div.spaceOffsetVector.x,
                            div.centroid.y + div.spaceOffsetVector.y);
                    if (prevIntent != DivisionIntentType.TRADE_SPACE_FOR_TIME) {
                        div.tradeSpaceStartTime = now;
                    }
                    String reason = String.format("offset (%.0f, %.0f), flux %.0f%%",
                            div.spaceOffsetVector.x, div.spaceOffsetVector.y, div.avgFluxLevel * 100f);
                    return commitIntent(engine, div, DivisionIntentType.TRADE_SPACE_FOR_TIME,
                            nearestEnemyDiv.displayName, reason, now);
                }
                return false;
            }

            if (nearestEnemyDiv != null && nearestEnemyDiv.isCombatEffective()) {
                div.targetEnemyDivision = nearestEnemyDiv;
            }
            div.targetFriendlyDivision = ventBehindFriend;
            div.coveringFriendDivision = ventBehindFriend;
            div.underCoverByFriend = (ventBehindFriend != null);
            div.linePassActive = (ventBehindFriend != null);
            if (ventBehindFriend != null) {
                ventBehindFriend.isCoveringFriend = true;
                div.linePassUntil = now + 10f;
            }
            String targetLabel;
            if (ventBehindFriend != null) {
                float retreatDirY = (div.side == 0) ? -1f : 1f;
                div.stationPoint.set(ventBehindFriend.centroid.x, ventBehindFriend.centroid.y + retreatDirY * 720f);
                targetLabel = ventBehindFriend.displayName;
            } else if (nearestEnemyDiv != null && nearestEnemyDiv.isCombatEffective()) {
                // Compute a clear high-speed Escape Vector 1,350u away from the enemy threat (blended toward the fleet's
                // safe corridor / rear) so faster divisions (e.g. Rambunctious DDs) sprint directly out of enemy gun range!
                Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv);
                float edx = div.centroid.x - ePos.x;
                float edy = div.centroid.y - ePos.y;
                float elen = (float) Math.sqrt(edx * edx + edy * edy);
                float escDirX = (elen > 1f) ? (edx / elen) : 0f;
                float escDirY = (elen > 1f) ? (edy / elen) : (div.side == 0 ? -1f : 1f);
                float rearBiasY = (div.side == 0) ? -0.35f : 0.35f;
                escDirY += rearBiasY;
                float nLen = (float) Math.sqrt(escDirX * escDirX + escDirY * escDirY);
                if (nLen > 0.01f) {
                    escDirX /= nLen;
                    escDirY /= nLen;
                }
                div.stationPoint.set(
                        div.centroid.x + escDirX * 1350f,
                        div.centroid.y + escDirY * 1350f);
                targetLabel = "safe extraction corridor";
            } else {
                div.stationPoint.set(myGroup.fleetSafeCorridorCenter.x != 0f || myGroup.fleetSafeCorridorCenter.y != 0f
                        ? myGroup.fleetSafeCorridorCenter
                        : myGroup.reserveStation);
                targetLabel = "the reserve station";
            }
            String reason = String.format("flux %.0f%%, local ratio %.2f", div.avgFluxLevel * 100f, div.localForceRatio);
            return commitIntent(engine, div, DivisionIntentType.HAUL_OUT_TO_VENT, targetLabel, reason, now);
        }

        // 3. Item E (Division-Level "Trade Space for Time" / "Tactical Retreat"):
        // When a non-carrier division is absorbing heavy enemy firepower at an unfavorable exchange ratio
        // (flux 44%..70%, incomingInRangeFirepower > inRangeFirepowerScore * 1.25), offset its space centroid
        // by (dx, dy) to step outside the enemy's short-range alpha envelope while keeping all guns firing!
        if (!div.archetype.isCarrierArchetype()
                && div.archetype != DivisionArchetype.SERVICE_GROUP
                && nearestEnemyDiv != null
                && nearestEnemyDiv.isCombatEffective()) {
            boolean continuingTradeSpace = prevIntent == DivisionIntentType.TRADE_SPACE_FOR_TIME
                    && (now - div.tradeSpaceStartTime) < 5.5f
                    && div.avgFluxLevel >= 0.30f
                    && div.avgFluxLevel < div.commander.rotationFluxThreshold;
            // Division-level battle-sequence chaining: after 4.5s of trading space, if the enemy has overextended
            // (closing on us), our flux has dropped below 0.52, no friendly division is left behind, and a friendly
            // hammer division gives >= 1.28x combined trap superiority, escalate to a feigned retreat lure -> alpha strike!
            DivisionState seqHammer = (prevIntent == DivisionIntentType.TRADE_SPACE_FOR_TIME)
                    ? OtcBattlePlanner.findValidFeignedRetreatHammer(engine, myGroup, div, nearestEnemyDiv)
                    : null;
            boolean escalateToFeignedRetreat = prevIntent == DivisionIntentType.TRADE_SPACE_FOR_TIME
                    && (now - div.tradeSpaceStartTime) >= 4.5f
                    && (div.getAliveMembers().size() >= 3 || div.totalFpStrength >= 28f)
                    && div.avgFluxLevel >= 0.28f && div.avgFluxLevel < 0.52f
                    && seqHammer != null
                    && nearestEnemyDiv.closingSpeedToEnemy > 12f
                    && (now - div.lastFeignedRetreatEndTime) >= 20f;
            if (escalateToFeignedRetreat) {
                div.feignedRetreatStage = 1;
                div.feignedRetreatStartTime = now;
                div.fleetBattleSequenceStage = 2;
                div.targetEnemyDivision = nearestEnemyDiv;
                BattlefieldFirepowerEvaluator.computeFeignedRetreatBaitOffset(
                        engine, div, nearestEnemyDiv, seqHammer, myGroup, div.spaceOffsetVector);
                div.stationPoint.set(
                        div.centroid.x + div.spaceOffsetVector.x,
                        div.centroid.y + div.spaceOffsetVector.y);
                return commitIntent(engine, div, DivisionIntentType.FEIGNED_RETREAT,
                        nearestEnemyDiv.displayName, "battle-sequence: trade space -> feigned retreat", now);
            }
            boolean rankReliefHoldingFront = div.inDoubleLineRelief
                    && div.avgFluxLevel < 0.62f
                    && div.crossfireCordonIndex < 0.36f;
            boolean friendlyExposedAhead = OtcBattlePlanner.hasFriendlyDivisionLeftBehindOrExposed(
                    engine, myGroup, div, nearestEnemyDiv);
            boolean startTradeSpace = !rankReliefHoldingFront
                    && !friendlyExposedAhead
                    && (now - div.lastTradeSpaceEndTime) >= 7.0f
                    && div.avgFluxLevel >= 0.52f
                    && div.avgFluxLevel < div.commander.rotationFluxThreshold
                    && div.incomingInRangeFirepower > 45f
                    && div.firepowerExchangeRatio < 0.72f
                    && div.localForceRatio < 0.78f;
            if ((continuingTradeSpace && !friendlyExposedAhead) || startTradeSpace) {
                div.targetEnemyDivision = nearestEnemyDiv;
                BattlefieldFirepowerEvaluator.computeTradeSpaceForTimeOffset(
                        engine, div, nearestEnemyDiv, myGroup, div.spaceOffsetVector);
                div.stationPoint.set(
                        div.centroid.x + div.spaceOffsetVector.x,
                        div.centroid.y + div.spaceOffsetVector.y);
                if (prevIntent != DivisionIntentType.TRADE_SPACE_FOR_TIME) {
                    div.tradeSpaceStartTime = now;
                }
                String reason = String.format("offset (%.0f, %.0f), flux %.0f%%",
                        div.spaceOffsetVector.x, div.spaceOffsetVector.y, div.avgFluxLevel * 100f);
                return commitIntent(engine, div, DivisionIntentType.TRADE_SPACE_FOR_TIME,
                        nearestEnemyDiv.displayName, reason, now);
            } else if (prevIntent == DivisionIntentType.TRADE_SPACE_FOR_TIME) {
                div.lastTradeSpaceEndTime = now;
            }
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
            DivisionFormationNavigator.computeStandoffStation(engine, div, carrierTargetDiv, div.stationPoint);
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
                    DivisionFormationNavigator.computeStandoffStation(engine, div, carrierTargetDiv, div.stationPoint);
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
                    DivisionFormationNavigator.computeStandoffStation(engine, div, carrierTargetDiv, div.stationPoint);
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
            BattleGroupState enemyGroup,
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
            boolean targetCollapsing = alphaTarget.avgFluxLevel >= 0.62f || alphaTarget.avgHullLevel < 0.52f
                    || (myGroup != null && myGroup.fleetFeignedRetreatStage == 2);
            if (div.archetype.isMissileArchetype()
                    && div.hasMissileStrikeCapability
                    && div.usableStrikeAmmoCount > 0
                    && strikeCooldownElapsed
                    && div.avgFluxLevel < 0.52f
                    && (!targetCollapsing || alphaTarget.avgFluxLevel < 0.42f)) {
                Vector2f visTargetPos = OtcAI.getVisibleCentroidForSide(engine, div.side, alphaTarget);
                div.targetEnemyDivision = alphaTarget;
                div.stationPoint.set(visTargetPos);
                if (prevIntent != DivisionIntentType.TOT_MISSILE_STRIKE) {
                    div.strikeCommittedTimestamp = now;
                }
                String reason = missionPhaseTag + "ToT Missile Strike (standoff salvo)";
                return commitIntent(engine, div, DivisionIntentType.TOT_MISSILE_STRIKE,
                        alphaTarget.displayName, reason, now);
            }
            if (strikeCooldownElapsed && div.avgFluxLevel < 0.48f
                    && (targetCollapsing || (now >= 18f && div.localForceRatio >= 1.15f && div.localEnemyStrength >= 12f))) {
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
                DivisionFormationNavigator.computeStandoffStation(engine, div, alphaTarget, div.stationPoint);
                return commitIntent(engine, div, DivisionIntentType.CLOSE_AND_ENGAGE,
                        alphaTarget.displayName, missionPhaseTag + "re-forming after ToT Alpha Strike", now);
            }
        }

        if (activeMission == DivisionMissionType.SCREEN && heavyFriendlyToScreen != null) {
            div.targetFriendlyDivision = heavyFriendlyToScreen;
            div.targetEnemyDivision = nearestEnemyDiv;
            DivisionFormationNavigator.computeScreeningStation(engine, div, heavyFriendlyToScreen, nearestEnemyDiv, div.stationPoint, div.side);
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
                DivisionFormationNavigator.computeFlankStation(engine, div, nearestEnemyDiv, div.stationPoint, myGroup);
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
            DivisionFormationNavigator.computeStandoffStation(engine, div, nearestEnemyDiv, div.stationPoint);
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
            boolean explicitLanceRamDivision = div.shipSkillCoordination == PermanentDivisionManager.ShipSkillCoordinationMode.SYNCHRONIZED
                    && div.ramCapableMemberCount >= 1
                    && div.ramCapableMemberCount * 2 >= Math.max(1, div.getAliveMembers().size())
                    && (div.doctrinalSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE
                    || div.activeSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE)
                    && (div.readySkillMemberRatio >= 0.50f || div.hasActiveLinearSkillBurn);
            if (explicitLanceRamDivision && div.avgFluxLevel < 0.62f && distToVisibleEnemy <= 2450f) {
                div.stationPoint.set(visEnemyPos);
                return commitIntent(engine, div, DivisionIntentType.PRESS_HOME,
                        nearestEnemyDiv.displayName, missionPhaseTag + "Synchronized Ram Charge", now);
            }
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
                DivisionFormationNavigator.computeFlankStation(engine, div, nearestEnemyDiv, div.stationPoint, myGroup);
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
            DivisionFormationNavigator.computeStandoffStation(engine, div, nearestEnemyDiv, div.stationPoint);
            return commitIntent(engine, div, intent,
                    nearestEnemyDiv.displayName, missionPhaseTag + activeMission.shortName, now);
        }

        return false;
    }

    static void selectFallbackTacticalIntent(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
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
                DivisionFormationNavigator.computeScreeningStation(engine, div, heavyFriendlyToScreen, nearestEnemyDiv, div.stationPoint, div.side);
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
        // Only swing wide on WORK_FLANK when not already delivering strong in-range firepower on the battleline shoulder
        // and when the enemy is not broken into >= 4 dispersed clusters (where wide solo flanks lose gun uptime!).
        if (strikeCooldownElapsed
                && div.isFastFlanker
                && nearestEnemyDiv != null
                && distToVisibleEnemy <= 4500f
                && (div.inRangeFirepowerScore <= 25f || distToVisibleEnemy > div.avgEngageRange * 1.15f)
                && (enemyGroup == null || enemyGroup.divisions.size() <= 3)
                && myGroup.mainEffortDivision != null
                && myGroup.mainEffortDivision != div
                && div.avgFluxLevel < 0.52f) {
            div.targetEnemyDivision = nearestEnemyDiv;
            DivisionFormationNavigator.computeFlankStation(engine, div, nearestEnemyDiv, div.stationPoint, myGroup);
            if (!inFlankManeuver) {
                div.strikeCommittedTimestamp = now;
            }
            String reason = "envelopment" + flankSideTag(div, nearestEnemyDiv, myGroup);
            commitIntent(engine, div, DivisionIntentType.WORK_FLANK, nearestEnemyDiv.displayName, reason, now);
            return;
        }

        // 12b. Item F (Autonomous Division-Level "Feigned Retreat" for Large Divisions):
        // Large divisions (aliveMembers >= 4 or totalFp >= 32) can autonomously execute a 2-stage Feigned Retreat
        // ONLY when: (a) bait alone does not outmatch the enemy (<= 1.05x) so the enemy is pulled inward,
        // (b) no friendly division is left behind/exposed ahead of us, and (c) at least one nearby friendly
        // hammer division provides >= 1.28x combined trap superiority.
        if (!div.archetype.isCarrierArchetype()
                && div.archetype != DivisionArchetype.SERVICE_GROUP
                && nearestEnemyDiv != null
                && nearestEnemyDiv.isAbleCombatEffective()
                && !BattlefieldFirepowerEvaluator.isStationBastionDivision(nearestEnemyDiv)
                && (div.getAliveMembers().size() >= 4 || div.totalFpStrength >= 32f)) {
            DivisionState validHammer = OtcBattlePlanner.findValidFeignedRetreatHammer(
                    engine, myGroup, div, nearestEnemyDiv);
            boolean friendlyExposed = OtcBattlePlanner.hasFriendlyDivisionLeftBehindOrExposed(
                    engine, myGroup, div, nearestEnemyDiv);
            if (prevIntent == DivisionIntentType.FEIGNED_RETREAT) {
                float feignElapsed = now - div.feignedRetreatStartTime;
                if (!friendlyExposed && div.avgFluxLevel < 0.60f && feignElapsed <= 9.5f) {
                    if (distToVisibleEnemy <= Math.max(960f, div.avgEngageRange * 0.94f)
                            || (feignElapsed >= 3.5f && nearestEnemyDiv.closingSpeedToEnemy > 25f)
                            || feignElapsed >= 5.0f) {
                        // Stage 2: Spring the alpha-strike ambush!
                        div.feignedRetreatStage = 2;
                        div.lastFeignedRetreatEndTime = now;
                        div.targetEnemyDivision = nearestEnemyDiv;
                        div.stationPoint.set(OtcAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv));
                        div.strikeCommittedTimestamp = now;
                        commitIntent(engine, div, DivisionIntentType.TOT_ALPHA_STRIKE,
                                nearestEnemyDiv.displayName, "Feigned Retreat trap sprung", now);
                        return;
                    } else if (validHammer != null) {
                        // Continue Stage 1 bait lure
                        div.targetEnemyDivision = nearestEnemyDiv;
                        BattlefieldFirepowerEvaluator.computeFeignedRetreatBaitOffset(
                                engine, div, nearestEnemyDiv, validHammer, myGroup, div.spaceOffsetVector);
                        div.stationPoint.set(
                                div.centroid.x + div.spaceOffsetVector.x,
                                div.centroid.y + div.spaceOffsetVector.y);
                        commitIntent(engine, div, DivisionIntentType.FEIGNED_RETREAT,
                                nearestEnemyDiv.displayName, "baiting into alpha strike", now);
                        return;
                    }
                }
                div.feignedRetreatStage = 0;
                div.lastFeignedRetreatEndTime = now;
            } else if (validHammer != null
                    && !friendlyExposed
                    && (now - div.lastFeignedRetreatEndTime) >= 26.0f
                    && distToVisibleEnemy >= 920f
                    && distToVisibleEnemy <= 1600f
                    && div.avgFluxLevel < 0.34f
                    && div.weaponReadinessRatio >= 0.72f
                    && nearestEnemyDiv.enemyFeignedRetreatSuspicion < 0.45f
                    && nearestEnemyDiv.avgFluxLevel < 0.52f
                    && nearestEnemyDiv.closingSpeedToEnemy > 12f) {
                div.feignedRetreatStage = 1;
                div.feignedRetreatStartTime = now;
                div.targetEnemyDivision = nearestEnemyDiv;
                BattlefieldFirepowerEvaluator.computeFeignedRetreatBaitOffset(
                        engine, div, nearestEnemyDiv, validHammer, myGroup, div.spaceOffsetVector);
                div.stationPoint.set(
                        div.centroid.x + div.spaceOffsetVector.x,
                        div.centroid.y + div.spaceOffsetVector.y);
                commitIntent(engine, div, DivisionIntentType.FEIGNED_RETREAT,
                        nearestEnemyDiv.displayName, "baiting into alpha strike", now);
                return;
            }
        }

        // 13. Press local overmatch vs standard line advance (strictly respecting Fog of War)
        if (nearestEnemyDiv != null) {
            div.targetEnemyDivision = nearestEnemyDiv;
            DivisionFormationNavigator.computeStandoffStation(engine, div, nearestEnemyDiv, div.stationPoint);

            DivisionIntentType intent;
            String reason;
            if (nearestEnemyDiv.enemyFeignedRetreatSuspicion < 0.60f
                    && div.localEnemyStrength >= 12f
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
            if (myGroup != null && myGroup.mainEffortDivision != null
                    && div != myGroup.mainEffortDivision
                    && myGroup.mainEffortDivision.isCombatEffective()) {
                div.stationPoint.set(
                        myGroup.mainEffortDivision.centroid.x + div.fleetFormationOffset.x,
                        myGroup.mainEffortDivision.centroid.y + scoutDirY + div.fleetFormationOffset.y * 0.65f);
            } else {
                div.stationPoint.set(div.centroid.x * 0.85f, div.centroid.y + scoutDirY);
            }
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
                // Item C: Never stall a non-carrier division in MARSHAL_FOR_STRIKE when it is already exchanging
                // gun fire in range — sitting at a staging point with DO_NOT_PURSUE wastes firepower-in-range uptime!
                if (!div.archetype.isCarrierArchetype()
                        && (div.inRangeFirepowerScore > 0f
                        || div.incomingInRangeFirepower > 25f)) {
                    return false;
                }
                div.currentIntent = DivisionIntentType.MARSHAL_FOR_STRIKE;
                div.targetEnemyDivision = target;
                if (task.hasStation) {
                    div.stationPoint.set(task.station);
                } else {
                    DivisionFormationNavigator.computeStandoffStation(engine, div, target, div.stationPoint);
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
                    DivisionFormationNavigator.computeStandoffStation(engine, div, target, div.stationPoint);
                } else if (flakProtectedHeavyTarget && activeMission != DivisionMissionType.TOT_ALPHA_STRIKE) {
                    if (div.hasMissileStrikeCapability && div.usableStrikeAmmoCount > 0) {
                        div.currentIntent = DivisionIntentType.TOT_MISSILE_STRIKE;
                        div.stationPoint.set(OtcAI.getVisibleCentroidForSide(engine, div.side, target));
                    } else {
                        div.currentIntent = DivisionIntentType.WORK_FLANK;
                        DivisionFormationNavigator.computeFlankStation(engine, div, target, div.stationPoint, myGroup);
                    }
                } else if ((activeMission == DivisionMissionType.TOT_ALPHA_STRIKE
                        && !(div.archetype.isMissileArchetype() && div.hasMissileStrikeCapability && div.usableStrikeAmmoCount > 0))
                        || !div.hasMissileStrikeCapability
                        || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION) {
                    boolean targetCollapsingExec = target.avgFluxLevel >= 0.58f || target.avgHullLevel < 0.58f;
                    if (targetCollapsingExec || (now >= 18f && div.localEnemyStrength >= 12f && div.localForceRatio >= 1.15f)) {
                        div.currentIntent = DivisionIntentType.TOT_ALPHA_STRIKE;
                        div.stationPoint.set(OtcAI.getVisibleCentroidForSide(engine, div.side, target));
                    } else {
                        div.currentIntent = DivisionIntentType.CLOSE_AND_ENGAGE;
                        DivisionFormationNavigator.computeStandoffStation(engine, div, target, div.stationPoint);
                    }
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
                DivisionFormationNavigator.computeReinforceStation(engine, div, friend, div.stationPoint, myGroup);
                return commitIntent(engine, div, DivisionIntentType.REINFORCE_DIVISION, friend.displayName, reason, now);
            }
            case HOLD_RESERVE: {
                // Item C: Veto HOLD_RESERVE if this division already has enemies in range or the battle line is engaged
                // and needs gun uptime on the front!
                if (div.inRangeFirepowerScore > 0f
                        || div.incomingInRangeFirepower > 20f
                        || div.localEnemyStrength >= 6f
                        || (myGroup != null && myGroup.engagedDivisionCount >= 1 && myGroup.fleetFirepowerUptime < 0.58f)) {
                    return false;
                }
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
                // Martyrdom divisions always charge point-blank on the OTC's assigned target regardless of fixing orders!
                if (activeMission == DivisionMissionType.MARTYRDOM) {
                    div.currentIntent = DivisionIntentType.PRESS_HOME;
                    div.targetEnemyDivision = target;
                    div.stationPoint.set(OtcAI.getVisibleCentroidForSide(engine, div.side, target));
                    reason = "OTC: martyrdom charge on \"" + target.displayName + "\"";
                    break;
                }
                // Strike time: the Main Effort presses the struck division (timing is within every authority but D)
                if (OtcBattlePlanner.isStrikeExecutingOn(myGroup, target) && auth != OtcAuthority.FIRE_DISTRIBUTION_ONLY) {
                    div.currentIntent = DivisionIntentType.PRESS_HOME;
                    div.targetEnemyDivision = target;
                    div.stationPoint.set(OtcAI.getVisibleCentroidForSide(engine, div.side, target));
                    reason = "OTC: execute, press home on \"" + target.displayName + "\"";
                    break;
                }
                // Only Central Planning overrides the Main Effort's own mission style with a fixing posture
                if (auth != OtcAuthority.POSITIVE_CONTROL) {
                    // Human (Command by Direction) Main Effort: keep fighting its OTC-assigned primary enemy
                    // instead of drifting onto transient contact clusters that happen to pass nearby.
                    div.targetEnemyDivision = target;
                    DivisionFormationNavigator.computeStandoffStation(engine, div, target, div.stationPoint);
                    div.currentIntent = DivisionIntentType.FORM_LINE_AND_FIX;
                    if (task.hasStation) {
                        div.stationPoint.set(task.station);
                    }
                    div.lastIntentChangeTime = now;
                    DivisionCombatLog.logDivisionIntent(engine, div, target.displayName, "OTC: hold the line on the primary threat", now);
                    return true;
                }
                div.currentIntent = DivisionIntentType.FORM_LINE_AND_FIX;
                div.targetEnemyDivision = target;
                if (task.hasStation) {
                    div.stationPoint.set(task.station);
                } else {
                    DivisionFormationNavigator.computeStandoffStation(engine, div, target, div.stationPoint);
                }
                break;
            }
            case FLANK: {
                if (auth != OtcAuthority.POSITIVE_CONTROL) return false;
                if (!targetValid || div.avgFluxLevel >= 0.58f || (now - div.lastBreakOffTimestamp) < 12f) return false;
                boolean wasFlanking = prevIntent == DivisionIntentType.WORK_FLANK;
                div.currentIntent = DivisionIntentType.WORK_FLANK;
                div.targetEnemyDivision = target;
                DivisionFormationNavigator.computeFlankStation(engine, div, target, div.stationPoint, myGroup);
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
                DivisionFormationNavigator.computeStandoffStation(engine, div, target, div.stationPoint);
                if (activeMission != DivisionMissionType.STANDOFF_ENGAGEMENT
                        && target.enemyFeignedRetreatSuspicion < 0.60f
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
            case TACTICAL_RETREAT: {
                if (!targetValid || div.archetype.isCarrierArchetype()) return false;
                div.currentIntent = DivisionIntentType.TRADE_SPACE_FOR_TIME;
                div.targetEnemyDivision = target;
                if (task.hasStation) {
                    div.stationPoint.set(task.station);
                } else {
                    BattlefieldFirepowerEvaluator.computeTradeSpaceForTimeOffset(
                            engine, div, target, myGroup, div.spaceOffsetVector);
                    div.stationPoint.set(
                            div.centroid.x + div.spaceOffsetVector.x,
                            div.centroid.y + div.spaceOffsetVector.y);
                }
                if (prevIntent != DivisionIntentType.TRADE_SPACE_FOR_TIME) {
                    div.tradeSpaceStartTime = now;
                }
                break;
            }
            case FEIGN_RETREAT_TRAP: {
                if (!targetValid || div.archetype.isCarrierArchetype()) return false;
                div.currentIntent = DivisionIntentType.FEIGNED_RETREAT;
                div.targetEnemyDivision = target;
                div.feignedRetreatStage = 1;
                if (prevIntent != DivisionIntentType.FEIGNED_RETREAT) {
                    div.feignedRetreatStartTime = now;
                }
                if (task.hasStation) {
                    div.stationPoint.set(task.station);
                } else {
                    BattlefieldFirepowerEvaluator.computeFeignedRetreatBaitOffset(
                            engine, div, target, null, myGroup, div.spaceOffsetVector);
                    div.stationPoint.set(
                            div.centroid.x + div.spaceOffsetVector.x,
                            div.centroid.y + div.spaceOffsetVector.y);
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
        boolean fullAssault = OtcAI.isFullAssaultActive(engine, myGroup);
        if (fullAssault && !div.archetype.isCarrierArchetype()
                && !div.underDirectObjectiveOrder
                && div.avgFluxLevel < 0.86f
                && div.currentIntent != DivisionIntentType.PRESS_HOME
                && div.currentIntent != DivisionIntentType.GIVE_GENERAL_CHASE) {
            div.currentIntent = (enemyTrackable) ? DivisionIntentType.PRESS_HOME : DivisionIntentType.CLOSE_AND_ENGAGE;
        }

        switch (div.currentIntent) {
            case TAKE_SCREENING_STATION:
                if (friend != null) {
                    DivisionFormationNavigator.computeScreeningStation(engine, div, friend, enemy, div.stationPoint, div.side);
                }
                break;
            case WORK_FLANK:
                if (enemyTrackable) {
                    DivisionFormationNavigator.computeFlankStation(engine, div, enemy, div.stationPoint, myGroup);
                }
                break;
            case REINFORCE_DIVISION:
                if (friend != null && friend.isCombatEffective()) {
                    DivisionFormationNavigator.computeReinforceStation(engine, div, friend, div.stationPoint, myGroup);
                }
                break;
            case PRESS_HOME:
            case HARASSING_MISSILE_ATTACK:
            case TOT_MISSILE_STRIKE:
            case TOT_ALPHA_STRIKE:
                if (enemyTrackable) {
                    Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, enemy);
                    if (fullAssault && div.currentIntent == DivisionIntentType.PRESS_HOME) {
                        div.stationPoint.set(ePos);
                        break;
                    }
                    // Close to an optimal weapon engagement band rather than diving into the middle of the enemy cluster
                    // (which exposed ships to 5x enemy guns at once); the fleet-wide lateral station is re-applied below.
                    float pdx = div.centroid.x - ePos.x;
                    float pdy = div.centroid.y - ePos.y;
                    float plen = (float) Math.sqrt(pdx * pdx + pdy * pdy);
                    float baseRange = (div.avgEngageRange > 250f ? div.avgEngageRange : div.standoffRange);
                    float rangeMult = (div.currentIntent == DivisionIntentType.HARASSING_MISSILE_ATTACK
                            || div.currentIntent == DivisionIntentType.TOT_MISSILE_STRIKE) ? 0.88f : 0.80f;
                    float band = Math.max(660f, baseRange * rangeMult);
                    if (plen > 1f) {
                        div.stationPoint.set(ePos.x + pdx / plen * band, ePos.y + pdy / plen * band);
                    } else {
                        div.stationPoint.set(ePos.x, ePos.y + (div.side == 0 ? -band : band));
                    }
                }
                break;
            case TRADE_SPACE_FOR_TIME:
                if (div.otcOrder.isActive(now) && div.otcOrder.hasStation
                        && div.otcOrder.type == DivisionModel.OtcOrderType.TACTICAL_RETREAT) {
                    div.stationPoint.set(div.otcOrder.station);
                } else if (enemyTrackable) {
                    BattlefieldFirepowerEvaluator.computeTradeSpaceForTimeOffset(
                            engine, div, enemy, myGroup, div.spaceOffsetVector);
                    div.stationPoint.set(
                            div.centroid.x + div.spaceOffsetVector.x,
                            div.centroid.y + div.spaceOffsetVector.y);
                }
                break;
            case FEIGNED_RETREAT:
                if (div.otcOrder.isActive(now) && div.otcOrder.hasStation
                        && div.otcOrder.type == DivisionModel.OtcOrderType.FEIGN_RETREAT_TRAP) {
                    div.stationPoint.set(div.otcOrder.station);
                } else if (enemyTrackable) {
                    BattlefieldFirepowerEvaluator.computeFeignedRetreatBaitOffset(
                            engine, div, enemy, null, myGroup, div.spaceOffsetVector);
                    div.stationPoint.set(
                            div.centroid.x + div.spaceOffsetVector.x,
                            div.centroid.y + div.spaceOffsetVector.y);
                }
                break;
            case GIVE_GENERAL_CHASE:
                if (enemyTrackable) {
                    div.stationPoint.set(OtcAI.getVisibleCentroidForSide(engine, div.side, enemy));
                }
                break;
            case CLOSE_AND_ENGAGE:
                if (div.otcOrder.isActive(now)
                        && div.otcOrder.hasStation
                        && div.otcOrder.type == DivisionModel.OtcOrderType.ENGAGE_ASSIGNED) {
                    div.stationPoint.set(div.otcOrder.station);
                } else if (enemyTrackable) {
                    DivisionFormationNavigator.computeStandoffStation(engine, div, enemy, div.stationPoint);
                } else if (div.targetEnemyDivision == null) {
                    float scoutDirY = (div.side == 0) ? 1400f : -1400f;
                    if (myGroup != null && myGroup.mainEffortDivision != null
                            && div != myGroup.mainEffortDivision
                            && myGroup.mainEffortDivision.isCombatEffective()) {
                        div.stationPoint.set(
                                myGroup.mainEffortDivision.centroid.x + div.fleetFormationOffset.x,
                                myGroup.mainEffortDivision.centroid.y + scoutDirY + div.fleetFormationOffset.y * 0.65f);
                    } else {
                        div.stationPoint.set(div.centroid.x * 0.85f, div.centroid.y + scoutDirY);
                    }
                }
                break;
            case FORM_LINE_AND_FIX:
                if (div.otcOrder.isActive(now) && div.otcOrder.hasStation) {
                    div.stationPoint.set(div.otcOrder.station);
                } else if (enemyTrackable) {
                    DivisionFormationNavigator.computeStandoffStation(engine, div, enemy, div.stationPoint);
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
                    div.stationPoint.set(friend.centroid.x, friend.centroid.y + retreatDirY * 720f);
                } else if (enemyTrackable) {
                    Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, enemy);
                    float edx = div.centroid.x - ePos.x;
                    float edy = div.centroid.y - ePos.y;
                    float elen = (float) Math.sqrt(edx * edx + edy * edy);
                    float escDirX = (elen > 1f) ? (edx / elen) : 0f;
                    float escDirY = (elen > 1f) ? (edy / elen) : (div.side == 0 ? -1f : 1f);
                    float rearBiasY = (div.side == 0) ? -0.35f : 0.35f;
                    escDirY += rearBiasY;
                    float nLen = (float) Math.sqrt(escDirX * escDirX + escDirY * escDirY);
                    if (nLen > 0.01f) {
                        escDirX /= nLen;
                        escDirY /= nLen;
                    }
                    div.stationPoint.set(
                            div.centroid.x + escDirX * 1350f,
                            div.centroid.y + escDirY * 1350f);
                } else if (myGroup.fleetSafeCorridorCenter.x != 0f || myGroup.fleetSafeCorridorCenter.y != 0f) {
                    div.stationPoint.set(myGroup.fleetSafeCorridorCenter);
                } else {
                    div.stationPoint.set(myGroup.reserveStation);
                }
                break;
            case FLY_COMBAT_SPACE_PATROL:
                if (friend != null && friend != div && friend.isCombatEffective()) {
                    computePatrolStation(div, friend, div.stationPoint);
                } else if (enemyTrackable) {
                    DivisionFormationNavigator.computeStandoffStation(engine, div, enemy, div.stationPoint);
                }
                break;
            case FLY_ARMED_RECONNAISSANCE:
            case FLY_FIGHTER_SWEEP:
            case FLY_CYCLIC_STRIKES:
            case LAUNCH_DECKLOAD_STRIKE:
            case LAUNCH_ALPHA_STRIKE:
                if (enemyTrackable) {
                    DivisionFormationNavigator.computeStandoffStation(engine, div, enemy, div.stationPoint);
                }
                break;
            default:
                break;
        }

        if (!fullAssault) {
            if (myGroup != null && OtcAI.isOrchestratedAdvanceActive(engine, myGroup, enemyGroup)) {
                DivisionState main = myGroup.mainEffortDivision;
                if (div == main && !enemyTrackable) {
                    float fwdDirY = (div.side == 0) ? 1f : -1f;
                    float maxAbsY = (engine != null && engine.getMapHeight() > 0f)
                            ? (engine.getMapHeight() * 0.36f)
                            : 5400f;
                    float targetY = Math.max(-maxAbsY, Math.min(maxAbsY, div.centroid.y + fwdDirY * 950f));
                    div.stationPoint.set(myGroup.fleetCentroid.x + div.fleetFormationOffset.x, targetY);
                }
            }
            applyFleetLateralStationKeeping(engine, div, myGroup, now);
        }
        // Inter-Division Mutual Cover: if this division is covering an adjacent retiring friendly division,
        // shift its division stationPoint laterally/forward to interpose between the threat and the retiring friend
        // while preserving full intra-division formation slots!
        if (div.isCoveringFriend
                && myGroup != null
                && (div.currentIntent == DivisionIntentType.CLOSE_AND_ENGAGE
                || div.currentIntent == DivisionIntentType.FORM_LINE_AND_FIX)) {
            for (DivisionState fd : myGroup.divisions) {
                if (fd == div || !fd.underCoverByFriend || fd.coveringFriendDivision != div) continue;
                if (distance(div.centroid, fd.centroid) <= 1150f && enemyTrackable) {
                    Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, enemy);
                    float fdx = fd.centroid.x - ePos.x;
                    float fdy = fd.centroid.y - ePos.y;
                    float flen = (float) Math.sqrt(fdx * fdx + fdy * fdy);
                    if (flen > 1f) {
                        float hold = Math.max(680f, div.standoffRange * 0.92f);
                        float screenX = ePos.x + (fdx / flen) * hold;
                        float screenY = ePos.y + (fdy / flen) * hold;
                        div.stationPoint.set(
                                div.stationPoint.x * 0.70f + screenX * 0.30f,
                                div.stationPoint.y * 0.70f + screenY * 0.30f);
                    }
                    break;
                }
            }
        }

        // Opening Advance & Battleline Cohesion Pacing: while the Main Effort (Battleship/Cruiser core) is still
        // marching into initial gun contact (up to t=85s in large battles), pace vanguard/flank/strike divisions so
        // Destroyers, Frigates, and faster Cruisers never sprint 1,000u ahead of the Battleships into 20 enemy guns
        // alone, and release wing depth pacing as soon as the main battle line closes within 1,650u of the enemy!
        if (!fullAssault
                && now < 75f
                && myGroup != null
                && myGroup.mainEffortDivision != null
                && myGroup.mainEffortDivision != div
                && myGroup.mainEffortDivision.isCombatEffective()
                && myGroup.mainEffortDivision.inRangeFirepowerScore <= 0f
                && (div.currentIntent == DivisionIntentType.CLOSE_AND_ENGAGE
                || div.currentIntent == DivisionIntentType.FORM_LINE_AND_FIX
                || div.currentIntent == DivisionIntentType.PRESS_HOME
                || div.currentIntent == DivisionIntentType.HARASSING_MISSILE_ATTACK
                || div.currentIntent == DivisionIntentType.MARSHAL_FOR_STRIKE
                || div.currentIntent == DivisionIntentType.TOT_MISSILE_STRIKE
                || div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE
                || div.currentIntent == DivisionIntentType.TAKE_SCREENING_STATION)) {
            float mainY = myGroup.mainEffortDivision.centroid.y;
            boolean enemyStillFarFromMain = true;
            if (enemyTrackable) {
                Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, enemy);
                float mainEngageThreshold = Math.max(1650f, myGroup.mainEffortDivision.standoffRange * 1.25f);
                if (distance(myGroup.mainEffortDivision.centroid, ePos) <= mainEngageThreshold) {
                    enemyStillFarFromMain = false;
                }
            }
            if (enemyStillFarFromMain) {
                boolean deepWedgeActive = now < 24f
                        && (myGroup.activeFleetWideFormation == PermanentDivisionManager.FleetWideFormation.GRAND_WEDGE_SPEARHEAD
                        || myGroup.activeFleetWideFormation == PermanentDivisionManager.FleetWideFormation.REVERSE_WEDGE
                        || myGroup.activeFleetWideFormation == PermanentDivisionManager.FleetWideFormation.REFUSED_FLANK_OBLIQUE
                        || myGroup.activeFleetWideFormation == PermanentDivisionManager.FleetWideFormation.REFUSED_FLANK_LEFT
                        || myGroup.activeFleetWideFormation == PermanentDivisionManager.FleetWideFormation.REVERSE_CRESCENT)
                        && myGroup.divisions.size() >= 3
                        && !div.isFastFlanker
                        && div.hasFleetFormationOffset
                        && myGroup.mainEffortDivision.hasFleetFormationOffset;
                float relY = deepWedgeActive
                        ? (div.fleetFormationOffset.y - myGroup.mainEffortDivision.fleetFormationOffset.y) * 0.45f
                        : 0f;
                float leadAllowance = deepWedgeActive
                        ? (relY + (div.side == 0 ? 180f : -180f))
                        : (div.side == 0 ? 280f : -280f);
                if (div.side == 0) {
                    div.stationPoint.y = Math.min(div.stationPoint.y, mainY + leadAllowance);
                } else {
                    div.stationPoint.y = Math.max(div.stationPoint.y, mainY + leadAllowance);
                }
            }
        }
        // Keep carrier divisions behind the friendly combat line so they never drift forward into enemy gun range
        if (div.archetype.isCarrierArchetype()
                && myGroup != null
                && myGroup.mainEffortDivision != null
                && myGroup.mainEffortDivision != div
                && myGroup.mainEffortDivision.isCombatEffective()) {
            float mainY = myGroup.mainEffortDivision.centroid.y;
            if (div.side == 0) {
                div.stationPoint.y = Math.min(div.stationPoint.y, mainY - 450f);
            } else {
                div.stationPoint.y = Math.max(div.stationPoint.y, mainY + 450f);
            }
        }

        DivisionFormationNavigator.updateDivisionOutOfContactRoute(engine, div, myGroup, enemyGroup);
    }

    /**
     * Fleet-wide advance station keeping: while advancing, engaging, or holding, line divisions keep BOTH their
     * lateral slot and their forward/depth rank relative to the Main Effort. The strength of this formation pull is
     * modulated by OTC authority, contact state, division movement stickiness, and the division commander's own
     * station-keeping strictness. Scouts, envelopment flankers, carriers, service divisions, strike/chase/retreat
     * taskings, line-passing coverers, and direct player objective orders remain autonomous.
     */
    public static void applyFleetLateralStationKeeping(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            float now) {
        if (myGroup == null || div == null || !div.hasFleetFormationOffset) return;
        if (myGroup.activeFleetWideFormation == PermanentDivisionManager.FleetWideFormation.NONE
                || myGroup.fleetWideFormation == PermanentDivisionManager.FleetWideFormation.NONE) return;
        DivisionState main = myGroup.mainEffortDivision;
        if (main == null || main == div || !main.isCombatEffective() || !main.hasFleetFormationOffset) return;
        if (div.archetype == null || div.archetype.isCarrierArchetype()
                || div.archetype == DivisionArchetype.SERVICE_GROUP) return;
        if (div.isCoveringFriend || div.underDirectObjectiveOrder) return;
        boolean orchAdvance = OtcAI.isOrchestratedAdvanceActive(engine, myGroup, null);
        if (!orchAdvance) {
            if (div.isFastFlanker) return;
            if (myGroup.authority == OtcAuthority.FIRE_DISTRIBUTION_ONLY) return;
            switch (div.currentIntent) {
                case CLOSE_AND_ENGAGE:
                case FORM_LINE_AND_FIX:
                case PRESS_HOME:
                case TAKE_AND_HOLD:
                case TOT_ALPHA_STRIKE:
                case TOT_MISSILE_STRIKE:
                case HARASSING_MISSILE_ATTACK:
                    break;
                default:
                    return;
            }
            DivisionMissionType mission = resolveActiveMission(div, now);
            if (mission == DivisionMissionType.ENVELOPMENT || mission == DivisionMissionType.SCOUTING) return;
        }

        // Fleet facing: from fleet centroid toward the primary threat (fallback: straight ahead for this side)
        float fwdX = 0f;
        float fwdY = (div.side == 0) ? 1f : -1f;
        DivisionState threat = myGroup.primaryThreatEnemyDivision;
        if (threat != null && threat.isCombatEffective()) {
            Vector2f tPos = OtcAI.getVisibleCentroidForSide(engine, div.side, threat);
            float tdx = tPos.x - myGroup.fleetCentroid.x;
            float tdy = tPos.y - myGroup.fleetCentroid.y;
            float tl = (float) Math.sqrt(tdx * tdx + tdy * tdy);
            if (tl > 200f) {
                fwdX = tdx / tl;
                fwdY = tdy / tl;
            }
        }
        float latX = fwdY;
        float latY = -fwdX;
        float relX = div.fleetFormationOffset.x - main.fleetFormationOffset.x;
        float relY = div.fleetFormationOffset.y - main.fleetFormationOffset.y;
        float desiredLat = relX * latX + relY * latY;
        float desiredFwd = relX * fwdX + relY * fwdY;
        float mainLat = main.centroid.x * latX + main.centroid.y * latY;
        float mainFwd = main.centroid.x * fwdX + main.centroid.y * fwdY;
        float curLat = div.stationPoint.x * latX + div.stationPoint.y * latY;
        float curFwd = div.stationPoint.x * fwdX + div.stationPoint.y * fwdY;
        float latErr = (mainLat + desiredLat) - curLat;
        float fwdErr = (mainFwd + desiredFwd) - curFwd;

        boolean inCombatContact = div.inRangeFirepowerScore > 0f
                || div.incomingInRangeFirepower > 0f
                || div.localEnemyStrength >= 6f;
        float approachWeight;
        float contactWeight;
        switch (myGroup.authority) {
            case POSITIVE_CONTROL:
                approachWeight = 0.86f;
                contactWeight = 0.54f;
                break;
            case COMMAND_BY_DIRECTION:
                approachWeight = 0.70f;
                contactWeight = 0.32f;
                break;
            case COMMAND_BY_NEGATION:
                approachWeight = 0.32f;
                contactWeight = 0.0f;
                break;
            case FIRE_DISTRIBUTION_ONLY:
            default:
                if (!orchAdvance) return;
                approachWeight = 0.75f;
                contactWeight = 0.0f;
                break;
        }
        float divStrictness = (div.commander != null) ? div.commander.stationKeepingStrictness : 1.0f;
        float stationMult = Math.max(0.65f, div.stationKeepingMult);
        float divisionAuthority = Math.max(0.45f, Math.min(1.30f,
                (0.82f + divStrictness * 0.34f) / stationMult));
        float rawWeight = (inCombatContact ? contactWeight : approachWeight) * divisionAuthority;
        if (orchAdvance && !inCombatContact) {
            rawWeight = Math.max(rawWeight, 0.86f);
        }
        float weight = Math.max(0f, Math.min(0.92f, rawWeight));
        if (weight <= 0.01f) return;

        float maxLatCorrection = inCombatContact
                ? (myGroup.authority == OtcAuthority.POSITIVE_CONTROL ? 430f : 260f)
                : Math.max(900f, Math.abs(desiredLat) + 600f);
        float maxFwdCorrection = inCombatContact
                ? (myGroup.authority == OtcAuthority.POSITIVE_CONTROL ? 520f : 300f)
                : Math.max(950f, Math.abs(desiredFwd) + 650f);
        float latCorrection = Math.max(-maxLatCorrection, Math.min(maxLatCorrection, latErr * weight));
        float fwdCorrection = Math.max(-maxFwdCorrection, Math.min(maxFwdCorrection, fwdErr * weight));
        div.stationPoint.x += latX * latCorrection + fwdX * fwdCorrection;
        div.stationPoint.y += latY * latCorrection + fwdY * fwdCorrection;
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
                || OtcAI.isFullAssaultActive(engine, myGroup)
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
                boolean enemyIsStation = BattlefieldFirepowerEvaluator.isStationBastionDivision(enemy);
                boolean isRamDivision = !enemyIsStation
                        && div.rammingMode != PermanentDivisionManager.DivisionRammingMode.OFF
                        && div.shipSkillCoordination == PermanentDivisionManager.ShipSkillCoordinationMode.SYNCHRONIZED
                        && div.ramCapableMemberCount >= 1
                        && div.ramCapableMemberCount * 2 >= Math.max(1, div.getAliveMembers().size());
                boolean lanceRamConfigured = isRamDivision
                        && (div.rammingMode == PermanentDivisionManager.DivisionRammingMode.SYNCHRONIZED_LANCE
                        || div.doctrinalSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE);
                if (isRamDivision && (div.readySkillMemberRatio >= 0.50f || div.hasActiveLinearSkillBurn)
                        && (div.currentIntent == DivisionIntentType.PRESS_HOME
                        || div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE
                        || (lanceRamConfigured
                                && (div.currentIntent == DivisionIntentType.CLOSE_AND_ENGAGE
                                || div.currentIntent == DivisionIntentType.WORK_FLANK
                                || div.currentIntent == DivisionIntentType.GIVE_GENERAL_CHASE)))) {
                    desired = DivisionSetpieceAttack.LANCE_CHARGE;
                } else if (!isLightOrDd || !outmatchedEhp) {
                    if (div.currentIntent == DivisionIntentType.PRESS_HOME
                            || div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE) {
                        desired = enemyIsStation
                                ? DivisionSetpieceAttack.WALL_SALVO_PASS
                                : DivisionSetpieceAttack.LANCE_CHARGE;
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
        boolean enemyIsStationForPass = BattlefieldFirepowerEvaluator.isStationBastionDivision(enemy);
        if (enemyIsStationForPass && !div.archetype.isCarrierArchetype() && div.avgEngageRange > 250f) {
            baseStandoff = Math.min(baseStandoff, Math.max(540f, div.avgEngageRange * 0.86f));
        }

        float stageElapsed = Math.max(0f, now - div.setpieceStageStartTime);
        boolean isRamDivisionForPass = !enemyIsStationForPass
                && div.rammingMode != PermanentDivisionManager.DivisionRammingMode.OFF
                && div.shipSkillCoordination == PermanentDivisionManager.ShipSkillCoordinationMode.SYNCHRONIZED
                && div.ramCapableMemberCount >= 1
                && div.ramCapableMemberCount * 2 >= Math.max(1, div.getAliveMembers().size());
        if (div.setpieceStage == 1) {
            // Stage 1: Dress formation at Initial Point (IP)
            float ipDist = isRamDivisionForPass ? Math.min(1180f, Math.max(820f, baseStandoff * 0.85f)) : (baseStandoff * 1.05f);
            float latShift = (div.activeSetpieceAttack == DivisionSetpieceAttack.BROADSIDE_ROLL) ? -280f : 0f;
            div.setpieceIpPoint.set(
                    visEnemyPos.x + dirX * ipDist + perpX * latShift,
                    visEnemyPos.y + dirY * ipDist + perpY * latShift);
            div.stationPoint.set(div.setpieceIpPoint);

            boolean dressed = div.stationKeepingRatio <= (isRamDivisionForPass ? 0.90f : 0.72f)
                    && distance(div.centroid, div.setpieceIpPoint) <= (isRamDivisionForPass ? 380f : 460f);
            float maxDressTime = isRamDivisionForPass ? 1.8f : 3.8f;
            float commitDist = isRamDivisionForPass
                    ? 720f
                    : (enemyIsStationForPass ? Math.max(1350f, baseStandoff * 1.45f) : (baseStandoff * 0.85f));
            if (otcImmediateCommit || (dressed && stageElapsed >= (isRamDivisionForPass ? 0.45f : 0f))
                    || stageElapsed >= maxDressTime || distToEnemy < commitDist) {
                div.setpieceStage = 2;
                div.setpieceStageStartTime = now;
                stageElapsed = 0f;
            } else {
                return;
            }
        }

        // Stage 2: Execute Setpiece Pass in locked formation carriage
        float maxPassDuration = isRamDivisionForPass ? 6.5f : 9.5f;
        if (stageElapsed >= maxPassDuration) {
            div.lastSetpieceEndTime = now;
            div.setpieceStage = 0;
            div.activeSetpieceAttack = DivisionSetpieceAttack.NONE;
            return;
        }

        float passProgress = Math.min(1f, stageElapsed / maxPassDuration);
        switch (div.activeSetpieceAttack) {
            case LANCE_CHARGE: {
                if (isRamDivisionForPass) {
                    // Ramming division Lance Charge: drive the formation station right through the target cluster
                    // so wingmen charge abreast through their assigned targets without turning sideways!
                    float targetDist = 180f - 360f * passProgress;
                    div.setpiecePassTarget.set(
                            visEnemyPos.x + dirX * targetDist,
                            visEnemyPos.y + dirY * targetDist);
                } else {
                    float targetDist = Math.max(440f, baseStandoff * (0.88f - 0.24f * passProgress));
                    div.setpiecePassTarget.set(
                            visEnemyPos.x + dirX * targetDist,
                            visEnemyPos.y + dirY * targetDist);
                }
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

        VoidstrikeCoordinator.updateDivisionVoidstrikeStaging(div, myGroup, focusEnemyShip, now);
        ShipSystemCoordinator.updateDivisionSkillManeuverState(engine, div, myGroup, enemyGroup, focusEnemyShip, now);

        boolean outOfCombatMarch = DivisionFormationNavigator.isDivisionInOutOfCombatMarch(engine, div);
        float minSustainableSpeed = outOfCombatMarch
                ? DivisionFormationNavigator.computeDivisionSustainableSpeed(div, alive)
                : 1000f;
        float maxMemberSpeed = outOfCombatMarch
                ? DivisionFormationNavigator.computeDivisionMaxMemberSpeed(div, alive)
                : 0f;
        boolean paceMixedDivisionSpeed = outOfCombatMarch
                && alive.size() >= 2
                && (maxMemberSpeed - minSustainableSpeed) > 16f
                && maxMemberSpeed > minSustainableSpeed * 1.18f;

        // Pre-scan for any division member requiring immediate extraction so healthy division mates can step onto
        // the interposing line-of-fire chord to cover and screen its withdrawal!
        ShipAPI extractingTeamMember = null;
        for (int i = 0; i < alive.size(); i++) {
            ShipAPI cand = alive.get(i);
            if (cand == null || !cand.isAlive() || cand.isHulk()) continue;
            if (div.detachedStragglerIds != null && div.detachedStragglerIds.contains(cand.getId())) continue;
            boolean ovl = cand.getFluxTracker() != null && cand.getFluxTracker().isOverloadedOrVenting();
            ShipSystemCoordinator.ShipSkillProfile csp = ShipSystemCoordinator.getSkillProfile(cand);
            boolean ramRecovering = csp.isDesignedToRam
                    && cand.getSystem() != null
                    && !cand.getSystem().isActive()
                    && !cand.getSystem().isChargeup()
                    && cand.getSystem().getCooldownRemaining() > 0.4f
                    && div.localEnemyStrength >= 10f
                    && (cand.getFluxLevel() >= 0.62f
                    || (cand.getHullLevel() < 0.62f && cand.getFluxLevel() >= 0.38f)
                    || (div.localForceRatio < 0.68f && cand.getFluxLevel() >= 0.48f));
            if (ovl || ramRecovering || cand.getFluxLevel() >= 0.72f || (cand.getHullLevel() < 0.48f && cand.getFluxLevel() >= 0.38f)) {
                extractingTeamMember = cand;
                break;
            }
        }

        for (int shipIdx = 0; shipIdx < alive.size(); shipIdx++) {
            ShipAPI ship = alive.get(shipIdx);

            // Release strike craft we held back for staging once the division is no longer staging
            if (div.currentIntent != DivisionIntentType.MARSHAL_FOR_STRIKE && div.fighterPullbackShipIds.remove(ship.getId())) {
                if (!OtcAI.isPlayerManuallyPiloting(engine, ship)) {
                    ship.setPullBackFighters(false);
                }
            }

            if (OtcAI.isPlayerManuallyPiloting(engine, ship)) {
                continue;
            }
            CombatTaskManagerAPI tm = (fm != null) ? fm.getTaskManager(ship.isAlly()) : null;
            if (hasExplicitCommandOrder(ship, div, myGroup, tm)) {
                continue;
            }

            ShipwideAIFlags flags = ship.getAIFlags();
            if (flags == null) continue;

            // Individual Ship Crisis Self-Preservation & Intra-Division Covered Extraction:
            //  1. If an individual ship is at >= 74% flux, overloaded/venting, critically hull-damaged under fire, or
            //     a ramming destroyer with its ram drive on cooldown at close range, extract it at full forward speed
            //     along a clear escape vector (unsetting MANEUVER_TARGET so it turns and burns away instead of reversing
            //     at 35% speed while facing enemy guns!).
            //  2. Healthy division mates step onto the interposing cover chord to screen the extracting ship!
            boolean martyrdom = isMartyrdomActive(div, myGroup, now)
                    || (myGroup.isPlayEnabled(OtcPlay.MARTYRDOM) && isVbiedOrFanaticHull(ship));
            ShipSystemCoordinator.ShipSkillProfile shipSkillProf = ShipSystemCoordinator.getSkillProfile(ship);
            boolean shipInRamCharge = shipSkillProf.isDesignedToRam
                    && div.rammingMode != PermanentDivisionManager.DivisionRammingMode.OFF
                    && div.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT
                    && div.currentIntent != DivisionIntentType.BREAK_OFF_AND_RETIRE
                    && ship.getFluxLevel() < 0.74f
                    && ship.getHullLevel() > 0.38f
                    && ((ship.getSystem() != null && (ship.getSystem().isActive() || ship.getSystem().isChargeup()))
                    || (div.synchronizedSkillManeuverActive && div.synchronizedSkillStage > 0)
                    || (div.activeSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE && div.setpieceStage > 0));

            boolean isDetachedStraggler = !shipInRamCharge && div.detachedStragglerIds.contains(ship.getId());
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
                Vector2f rejoinWaypoint = DivisionFormationNavigator.computeOutOfContactRoutePoint(
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
                if (ship.getFluxLevel() >= 0.42f || DivisionFormationNavigator.isShipNearVisibleEnemy(engine, div.side, ship.getLocation(), enemyGroup, 1450f)) {
                    flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
                }
                continue;
            } else {
                div.stragglerSkirtSideByShipId.remove(ship.getId());
            }

                // Active Helmsmanship Collision Avoidance (fleet-wide): prevent this ship's velocity vector from slamming
                // into any friendly ship in its own division or another division!
                applyActiveHelmsmanshipCollisionAvoidance(engine, ship, flags, alive, div, myGroup);

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
            // Prevent fragile Destroyers (e.g. Hammerheads / Sunders / Rambunctious) from suicidally pressing into heavy
            // armor/shields when their Effective HP, hard-flux headroom, or post-ram cooldown leaves them exposed!
            boolean postRamCooldownDanger = !shipInRamCharge
                    && shipSkillProf.isDesignedToRam
                    && ship.getSystem() != null
                    && !ship.getSystem().isActive()
                    && !ship.getSystem().isChargeup()
                    && ship.getSystem().getCooldownRemaining() > 0.4f
                    && div.localEnemyStrength >= 10f
                    && focusEnemyShip != null
                    && distance(ship.getLocation(), focusEnemyShip.getLocation()) <= 620f
                    && (ship.getFluxLevel() >= 0.62f
                    || (ship.getHullLevel() < 0.62f && ship.getFluxLevel() >= 0.38f)
                    || (div.localForceRatio < 0.68f && ship.getFluxLevel() >= 0.48f));
            boolean destroyerInEhpDanger = !shipInRamCharge
                    && isDestroyerHull
                    && div.localEnemyStrength >= 10f
                    && (postRamCooldownDanger
                    || (ship.getHullLevel() < 0.68f && ship.getFluxLevel() >= 0.50f)
                    || (shipEhpRatio < 0.52f && ship.getFluxLevel() >= 0.40f)
                    || (div.localForceRatio < 0.80f && shipEhpRatio < 0.58f && (ship.getFluxLevel() >= 0.48f || hardFluxFrac >= 0.38f))
                    || (div.localForceRatio < 0.72f && ship.getFluxLevel() >= 0.62f));
            float crisisFluxThreshold = (div.incomingInRangeFirepower > 35f || div.localEnemyStrength >= 14f) ? 0.74f : 0.80f;
            boolean shipInPersonalCrisis = !martyrdom
                    && div.localEnemyStrength >= 8f
                    && div.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT
                    && div.currentIntent != DivisionIntentType.BREAK_OFF_AND_RETIRE
                    && (loneOrOutnumberedFrigateInDanger
                    || destroyerInEhpDanger
                    || postRamCooldownDanger
                    || (ship.getFluxTracker() != null && ship.getFluxTracker().isOverloadedOrVenting())
                    || ship.getFluxLevel() >= crisisFluxThreshold
                    || (ship.getHullLevel() < 0.42f && ship.getFluxLevel() >= 0.45f));
            if (shipInPersonalCrisis) {
                Vector2f extractGoal = computePersonalExtractionPoint(ship, div, myGroup, focusEnemyShip);
                flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
                flags.unsetFlag(AIFlags.HARASS_MOVE_IN);
                flags.unsetFlag(AIFlags.PURSUING);
                flags.unsetFlag(AIFlags.MANEUVER_TARGET);
                flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL * 1.4f);
                flags.setFlag(AIFlags.RUN_QUICKLY, FLAG_TTL * 1.4f);
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL * 1.4f);
                if (extractGoal != null) {
                    flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, extractGoal);
                    flags.setFlag(AIFlags.MOVEMENT_DEST, FLAG_TTL, extractGoal);
                }
                continue;
            }

            Vector2f slotPos = DivisionFormationNavigator.computeShipFormationSlot(engine, ship, shipIdx, alive, div, myGroup);
            // Intra-Division Covering Screen: if a teammate in this division is actively extracting (`extractingTeamMember`),
            // healthy peers with low flux step onto the interposing line-of-fire between the enemy and the extracting ship!
            if (extractingTeamMember != null
                    && extractingTeamMember != ship
                    && focusEnemyShip != null
                    && ship.getFluxLevel() < 0.46f
                    && ship.getHullLevel() >= 0.68f
                    && distance(ship.getLocation(), extractingTeamMember.getLocation()) <= 680f) {
                Vector2f exLoc = extractingTeamMember.getLocation();
                Vector2f enLoc = focusEnemyShip.getLocation();
                float cdx = enLoc.x - exLoc.x;
                float cdy = enLoc.y - exLoc.y;
                float clen = (float) Math.sqrt(cdx * cdx + cdy * cdy);
                if (clen > 180f) {
                    float coverDist = Math.min(260f, clen * 0.35f);
                    float coverX = exLoc.x + (cdx / clen) * coverDist;
                    float coverY = exLoc.y + (cdy / clen) * coverDist;
                    slotPos = new Vector2f(slotPos.x * 0.40f + coverX * 0.60f, slotPos.y * 0.40f + coverY * 0.60f);
                }
            }
            boolean crowdedByPeer = isShipCrowdedByDivisionPeer(ship, alive, div.stationSpacing * 0.72f);

            // Out-of-Combat Mixed-Division Speed Governor:
            // When marching out of combat in a mixed-speed division (e.g. Battle Division 1), govern faster ships that
            // have reached or passed the slowest core member's rank so the entire division advances coherently at the
            // greatest sustainable speed that all its ships can maintain! Once combat opens up, all bets are off.
            if (paceMixedDivisionSpeed && ship.getMaxSpeed() > minSustainableSpeed + 4f) {
                applyOutOfCombatSpeedGovernor(ship, flags, slotPos, alive, div, minSustainableSpeed);
            }

            float distToDivCenter = distance(ship.getLocation(), div.centroid);
            boolean shipStraggling = !shipInRamCharge
                    && div.coreMemberCount >= 2
                    && distToDivCenter > (div.stationKeepingRadius * 1.38f);

            // Bounded pursuit & cohesion leash: prevent lone ships from chasing bait 3000u away
            if (shipStraggling && div.currentIntent != DivisionIntentType.HARASSING_MISSILE_ATTACK
                    && div.currentIntent != DivisionIntentType.TOT_MISSILE_STRIKE) {
                float[] chosenSide = new float[]{div.routeSkirtSide};
                Vector2f safeSlotPos = DivisionFormationNavigator.computeOutOfContactRoutePoint(
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
            VoidstrikeCoordinator.dressShipStrikeCraftInFormation(ship, shipIdx, focusEnemyShip, div, myGroup, now);
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
        if (enemyGroup != null) {
            for (DivisionState eDiv : OtcAI.getEffectiveEnemyDivisions(engine, enemyGroup)) {
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
        float nearestEnemyDist = computeNearestVisibleEnemyShipDist(
                engine, div.side, ship.getLocation(), div.targetEnemyDivision, enemyGroup, focusEnemyShip);
        float effectiveContactDist = Math.min(shipDistToEnemy, nearestEnemyDist);
        div.inIndividualCombatMovementMode = (focusEnemyShip != null && effectiveContactDist <= individualEngageHorizon);

        if (applyZoneVentingAndRankReliefFlags(engine, ship, flags, slotPos, focusEnemyShip, div, myGroup, effectiveContactDist, now)) {
            return;
        }

        // If this ship is a ram-capable vessel that is actively executing or marshaling a ram charge (when rammingMode != OFF),
        // do not let generic standoff/backoff/640u-gun-band rules pull it off its ram trajectory!
        ShipSystemCoordinator.ShipSkillProfile ramCheckProf = ShipSystemCoordinator.getSkillProfile(ship);
        boolean shipActivelyRammingOrCharging = ramCheckProf.isDesignedToRam
                && div.rammingMode != PermanentDivisionManager.DivisionRammingMode.OFF
                && ((ship.getSystem() != null && (ship.getSystem().isActive() || ship.getSystem().isChargeup()))
                || (div.synchronizedSkillManeuverActive && div.synchronizedSkillStage > 0)
                || (div.activeSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE && div.setpieceStage > 0));
        if (shipActivelyRammingOrCharging
                && div.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT
                && div.currentIntent != DivisionIntentType.BREAK_OFF_AND_RETIRE
                && ship.getFluxLevel() < 0.78f
                && ship.getHullLevel() > 0.35f) {
            flags.unsetFlag(AIFlags.BACK_OFF);
            flags.unsetFlag(AIFlags.DO_NOT_PURSUE);
            flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
            flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL);
            flags.setFlag(AIFlags.PURSUING, FLAG_TTL);
            return;
        }

        switch (div.currentIntent) {
            case FORM_LINE_AND_FIX:
                applyFormLineAndFixFlags(
                        ship, flags, slotPos, crowdedByPeer, focusEnemyShip, div,
                        structuredFormation, dressTol, combatSlotLeash, effectiveContactDist);
                break;

            case MARSHAL_FOR_STRIKE:
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) <= 1850f) {
                    directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
                }
                if ((focusEnemyShip == null || distance(ship.getLocation(), focusEnemyShip.getLocation()) > Math.max(1100f, div.avgEngageRange * 1.12f))
                        && (distance(ship.getLocation(), slotPos) > 300f || crowdedByPeer)) {
                    flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                }
                if (ship.getNumFighterBays() > 0 && !ship.isPullBackFighters()) {
                    ship.setPullBackFighters(true);
                    div.fighterPullbackShipIds.add(ship.getId());
                }
                break;

            case GIVE_GENERAL_CHASE:
                applyGiveGeneralChaseFlags(ship, flags, slotPos, focusEnemyShip, div, myGroup);
                break;

            case HARASSING_MISSILE_ATTACK:
            case TOT_MISSILE_STRIKE:
                applyMissileStrikeFlags(
                        ship, flags, slotPos, focusEnemyShip, div, myGroup,
                        structuredFormation, combatSlotLeash, now);
                break;

            case TOT_ALPHA_STRIKE:
                applyTotAlphaStrikeFlags(ship, flags, slotPos, focusEnemyShip, div, dressTol);
                break;

            case PRESS_HOME:
                applyPressHomeFlags(engine, ship, shipIdx, flags, slotPos, focusEnemyShip, div, myGroup, structuredFormation, dressTol, now);
                break;

            case BREAK_OFF_AND_RETIRE:
            case HAUL_OUT_TO_VENT: {
                // Coordinated Division Extraction:
                //  - At most 1 healthy, non-ramming ship (flux < 0.40, hull >= 0.78) acts as Rearguard Cover, keeping
                //    weapons bearing on the enemy while backing toward slotPos.
                //  - All other ships (especially fast/ram-capable hulls like Rambunctious DDs, or ships above 40% flux)
                //    unset MANEUVER_TARGET and set RUN_QUICKLY + MOVEMENT_DEST = slotPos so they turn their bows toward
                //    the extraction corridor and sprint out of danger at 100% forward speed + escape drive!
                ShipSystemCoordinator.ShipSkillProfile sp = ShipSystemCoordinator.getSkillProfile(ship);
                boolean actAsRearguardCover = (shipIdx == 0 && div.getAliveMembers().size() >= 2)
                        && !sp.isDesignedToRam
                        && ship.getFluxLevel() < 0.40f
                        && ship.getHullLevel() >= 0.78f;
                if (actAsRearguardCover
                        && focusEnemyShip != null
                        && distance(ship.getLocation(), focusEnemyShip.getLocation()) <= Math.max(1400f, div.avgEngageRange * 1.20f)) {
                    directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
                } else {
                    flags.unsetFlag(AIFlags.MANEUVER_TARGET);
                    flags.setFlag(AIFlags.RUN_QUICKLY, FLAG_TTL * 1.4f);
                    if (distance(ship.getLocation(), slotPos) > 220f) {
                        flags.setFlag(AIFlags.MOVEMENT_DEST, FLAG_TTL * 1.4f, slotPos);
                    }
                }
                flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
                flags.unsetFlag(AIFlags.HARASS_MOVE_IN);
                flags.unsetFlag(AIFlags.PURSUING);
                flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL * 1.4f);
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL * 1.4f);
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL * 1.4f, slotPos);
                break;
            }

            case TRADE_SPACE_FOR_TIME:
            case FEIGNED_RETREAT:
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                if (focusEnemyShip != null) {
                    directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
                    float distToEnemy = distance(ship.getLocation(), focusEnemyShip.getLocation());
                    if (distToEnemy < div.standoffRange * 0.90f || div.currentIntent == DivisionIntentType.FEIGNED_RETREAT) {
                        flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
                    }
                }
                if (distance(ship.getLocation(), slotPos) > 220f) {
                    flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                }
                break;

            case TAKE_SCREENING_STATION:
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
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

            case WORK_FLANK:
                applyWorkFlankFlags(
                        ship, flags, slotPos, crowdedByPeer, focusEnemyShip, div,
                        structuredFormation, dressTol, combatSlotLeash, effectiveContactDist);
                break;

            case TAKE_AND_HOLD: {
                boolean cautiousAdvance = shouldAdvanceCautiouslyOnObjective(engine, div, myGroup, enemyGroup);
                float distToGoal = distance(ship.getLocation(), div.stationPoint);
                boolean objectiveStillUncaptured = div.targetObjective != null
                        && div.targetObjective.getOwner() != div.side;
                if (objectiveStillUncaptured && (div.underDirectObjectiveOrder || distToGoal <= 1350f)) {
                    // Absolute Player Objective Authority & Reliable Point Capture:
                    // Forbid ships from wandering off to chase nearby enemy ships while the Control Point is still
                    // uncaptured, and pin the lead ship (shipIdx == 0) directly onto targetObjective.getLocation()
                    // so a hull physically stands inside the capture circle until ownership flips!
                    flags.unsetFlag(AIFlags.BACK_OFF);
                    flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    Vector2f objLoc = div.targetObjective.getLocation();
                    Vector2f capGoal = (shipIdx == 0) ? objLoc : slotPos;
                    flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, capGoal);
                    if (shipIdx == 0 && distance(ship.getLocation(), objLoc) > 120f) {
                        flags.setFlag(AIFlags.MOVEMENT_DEST, FLAG_TTL, objLoc);
                    }
                } else if (!cautiousAdvance && distToGoal > 850f) {
                    flags.unsetFlag(AIFlags.BACK_OFF);
                    flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
                    if (distance(ship.getLocation(), slotPos) > (structuredFormation ? dressTol * 1.45f : Math.max(320f, div.stationSpacing * 0.65f)) || crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                } else if (distance(ship.getLocation(), slotPos) > (structuredFormation ? dressTol : Math.max(320f, div.stationSpacing * 0.65f)) || crowdedByPeer) {
                    flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
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
                applyCarrierIntentFlags(ship, flags, slotPos, crowdedByPeer, focusEnemyShip, div);
                break;

            case CLOSE_AND_ENGAGE:
            default:
                applyCloseAndEngageFlags(
                        ship, shipIdx, flags, slotPos, crowdedByPeer, focusEnemyShip, div,
                        structuredFormation, dressTol, individualEngageHorizon, combatSlotLeash, effectiveContactDist, now);
                break;
        }
    }

    private static boolean applyZoneVentingAndRankReliefFlags(
            CombatEngineAPI engine,
            ShipAPI ship,
            ShipwideAIFlags flags,
            Vector2f slotPos,
            ShipAPI focusEnemyShip,
            DivisionState div,
            BattleGroupState myGroup,
            float shipDistToEnemy,
            float now) {
        String shipId = ship.getId();
        TacticalZoneType shipZone = (shipId != null) ? div.shipZoneTypeByShipId.get(shipId) : null;
        ShipRankState rankState = (shipId != null) ? div.shipRankStateByShipId.get(shipId) : null;
        boolean fullAssault = OtcAI.isFullAssaultActive(engine, myGroup);
        if (shipZone == TacticalZoneType.SAFE_ZONE) {
            flags.unsetFlag(AIFlags.DO_NOT_VENT);
        } else if ((shipZone == TacticalZoneType.ENGAGEMENT_ZONE || shipZone == TacticalZoneType.KILL_ZONE)
                && div.localEnemyStrength >= 8f
                && ship.getFluxLevel() < 0.88f) {
            flags.setFlag(AIFlags.DO_NOT_VENT, FLAG_TTL);
        }
        if (fullAssault && ship.getFluxLevel() < 0.86f) {
            return false;
        }
        boolean strikeCommitted = div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE
                || div.currentIntent == DivisionIntentType.PRESS_HOME
                || div.currentIntent == DivisionIntentType.FEIGNED_RETREAT;
        boolean criticallyFluxedOnCommittedStrike = strikeCommitted && ship.getFluxLevel() >= 0.66f;
        if (shipZone == TacticalZoneType.KILL_ZONE
                && (!strikeCommitted || criticallyFluxedOnCommittedStrike)
                && ship.getFluxLevel() >= 0.50f
                && !isMartyrdomActive(div, myGroup, now)) {
            flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
            flags.unsetFlag(AIFlags.CAMP_LOCATION);
            flags.unsetFlag(AIFlags.HARASS_MOVE_IN);
            flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
            if (focusEnemyShip != null) directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
            return true;
        }
        if (div.inDoubleLineRelief
                && rankState == ShipRankState.RELIEF_SAFE
                && !isMartyrdomActive(div, myGroup, now)) {
            flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
            flags.unsetFlag(AIFlags.HARASS_MOVE_IN);
            flags.unsetFlag(AIFlags.PURSUING);
            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL * 1.4f);
            flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL * 1.4f);
            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL * 1.4f, slotPos);
            if (focusEnemyShip != null
                    && ship.getFluxLevel() < 0.84f
                    && shipDistToEnemy <= Math.max(1450f, div.avgEngageRange * 1.25f)) {
                directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
            }
            return true;
        }
        return false;
    }

    private static void applyFormLineAndFixFlags(
            ShipAPI ship,
            ShipwideAIFlags flags,
            Vector2f slotPos,
            boolean crowdedByPeer,
            ShipAPI focusEnemyShip,
            DivisionState div,
            boolean structuredFormation,
            float dressTol,
            float combatSlotLeash,
            float distToEnemy) {
        float distToSlot = distance(ship.getLocation(), slotPos);
        boolean inActiveGunRange = distToEnemy <= Math.max(1550f, div.standoffRange * 1.30f);
        boolean targetCorneredOrWeak = div.targetEnemyDivision != null
                && (div.targetEnemyDivision.edgeProximityPinch >= 0.28f
                || div.targetEnemyDivision.isUnableDivision
                || div.targetEnemyDivision.retreatingFraction >= 0.35f
                || div.targetEnemyDivision.combatThreatWeight <= 0.60f);
        if (!targetCorneredOrWeak) {
            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
        } else if (ship.getFluxLevel() < 0.58f) {
            flags.unsetFlag(AIFlags.DO_NOT_PURSUE);
            flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL);
            flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
        }
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
                    ? (targetCorneredOrWeak
                            ? Math.max(combatSlotLeash * 1.45f, 580f)
                            : Math.min(combatSlotLeash, Math.max(dressTol * 1.45f, 380f)))
                    : dressTol;
            if (distToSlot > activeTol) {
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
            }
        } else if ((!inActiveGunRange && (distToSlot > Math.max(450f, div.stationSpacing * 0.8f) || crowdedByPeer))
                || distToSlot > Math.max(920f, div.stationSpacing * 1.65f)) {
            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
        }
    }

    private static void applyGiveGeneralChaseFlags(
            ShipAPI ship,
            ShipwideAIFlags flags,
            Vector2f slotPos,
            ShipAPI focusEnemyShip,
            DivisionState div,
            BattleGroupState myGroup) {
        float dx = ship.getLocation().x - myGroup.fleetCentroid.x;
        float dy = ship.getLocation().y - myGroup.fleetCentroid.y;
        float fromFleet = (float) Math.sqrt(dx * dx + dy * dy);
        if (fromFleet > OtcBattlePlanner.CHASE_LEASH) {
            float k = (OtcBattlePlanner.CHASE_LEASH * 0.8f) / Math.max(1f, fromFleet);
            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL,
                    new Vector2f(myGroup.fleetCentroid.x + dx * k, myGroup.fleetCentroid.y + dy * k));
            return;
        }
        flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
        if (focusEnemyShip != null) {
            directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
        } else {
            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
        }
    }

    private static void applyMissileStrikeFlags(
            ShipAPI ship,
            ShipwideAIFlags flags,
            Vector2f slotPos,
            ShipAPI focusEnemyShip,
            DivisionState div,
            BattleGroupState myGroup,
            boolean structuredFormation,
            float combatSlotLeash,
            float now) {
        boolean waitingForMainLine = !isMartyrdomActive(div, myGroup, now)
                && now < 25f
                && myGroup != null
                && myGroup.mainEffortDivision != null
                && myGroup.mainEffortDivision != div
                && myGroup.mainEffortDivision.isCombatEffective()
                && myGroup.mainEffortDivision.inRangeFirepowerScore <= 0f
                && myGroup.mainEffortDivision.localEnemyStrength < 10f;
        if (waitingForMainLine) {
            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
            if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) <= 1650f) {
                directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
            }
            if (distance(ship.getLocation(), slotPos) > 200f) {
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
            }
            return;
        }
        if (ship.getFluxLevel() < 0.55f) {
            flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL * 1.5f);
        }
        if (focusEnemyShip != null) {
            directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
            float dEnemy = distance(ship.getLocation(), focusEnemyShip.getLocation());
            float minSafeStrikeDist = Math.max(680f, div.avgEngageRange * 0.80f);
            if (dEnemy < minSafeStrikeDist && ship.getFluxLevel() >= 0.35f) {
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
            } else if (dEnemy > 2100f || (structuredFormation && distance(ship.getLocation(), slotPos) > combatSlotLeash * 1.35f)) {
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
            }
        } else if (div.targetEnemyDivision != null) {
            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
        }
    }

    private static void applyTotAlphaStrikeFlags(
            ShipAPI ship,
            ShipwideAIFlags flags,
            Vector2f slotPos,
            ShipAPI focusEnemyShip,
            DivisionState div,
            float dressTol) {
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
            return;
        }
        boolean targetVulnerableForPush = div.targetEnemyDivision == null
                || div.targetEnemyDivision.avgFluxLevel >= 0.55f
                || div.targetEnemyDivision.avgHullLevel < 0.60f
                || div.targetEnemyDivision.totalFpStrength < 40f;
        boolean lowFluxForAlphaPush = ship.getFluxLevel() < 0.52f
                && (div.localForceRatio >= 0.82f || (targetVulnerableForPush && ship.getFluxLevel() < 0.38f));
        if (lowFluxForAlphaPush) {
            flags.unsetFlag(AIFlags.BACK_OFF);
            flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL * 1.2f);
            flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL * 1.4f);
            flags.setFlag(AIFlags.PURSUING, FLAG_TTL * 1.2f);
        } else if (ship.getFluxLevel() >= 0.65f) {
            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
        }
        if (focusEnemyShip != null) {
            directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
            float distToEnemy = distance(ship.getLocation(), focusEnemyShip.getLocation());
            float allGunsCloseRange = Math.max(640f, Math.min(840f, div.avgEngageRange * 0.78f));
            if (distToEnemy > allGunsCloseRange && lowFluxForAlphaPush) {
                Vector2f eLoc = focusEnemyShip.getLocation();
                float sdx = ship.getLocation().x - eLoc.x;
                float sdy = ship.getLocation().y - eLoc.y;
                float slen = (float) Math.sqrt(sdx * sdx + sdy * sdy);
                if (slen > 1f) {
                    Vector2f bandPt = new Vector2f(
                            eLoc.x + (sdx / slen) * allGunsCloseRange,
                            eLoc.y + (sdy / slen) * allGunsCloseRange);
                    flags.setFlag(AIFlags.MOVEMENT_DEST, FLAG_TTL, bandPt);
                } else {
                    flags.setFlag(AIFlags.MOVEMENT_DEST, FLAG_TTL, eLoc);
                }
            }
            if (distToEnemy > 1650f && distance(ship.getLocation(), slotPos) > dressTol * 1.25f) {
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
            }
        } else if (div.targetEnemyDivision != null) {
            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
        }
    }

    private static void applyPressHomeFlags(
            CombatEngineAPI engine,
            ShipAPI ship,
            int shipIdx,
            ShipwideAIFlags flags,
            Vector2f slotPos,
            ShipAPI focusEnemyShip,
            DivisionState div,
            BattleGroupState myGroup,
            boolean structuredFormation,
            float dressTol,
            float now) {
        boolean martyrdomOrFanatic = isMartyrdomActive(div, myGroup, now)
                || (myGroup.isPlayEnabled(OtcPlay.MARTYRDOM) && isVbiedOrFanaticHull(ship));
        if (martyrdomOrFanatic) {
            flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
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
            return;
        }
        boolean fullAssault = OtcAI.isFullAssaultActive(engine, myGroup);
        if (fullAssault && ship.getFluxLevel() < 0.86f) {
            flags.unsetFlag(AIFlags.BACK_OFF);
            flags.unsetFlag(AIFlags.DO_NOT_PURSUE);
            flags.unsetFlag(AIFlags.RUN_QUICKLY);
            flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL * 1.5f);
            flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL * 1.5f);
            flags.setFlag(AIFlags.PURSUING, FLAG_TTL * 1.5f);
            if (focusEnemyShip != null) {
                directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
                float distToEnemy = distance(ship.getLocation(), focusEnemyShip.getLocation());
                float closePushBand = Math.max(460f, Math.min(720f, div.avgEngageRange * 0.58f));
                if (distToEnemy > closePushBand) {
                    flags.setFlag(AIFlags.MOVEMENT_DEST, FLAG_TTL, focusEnemyShip.getLocation());
                }
            } else if (div.targetEnemyDivision != null) {
                flags.setFlag(AIFlags.MOVEMENT_DEST, FLAG_TTL, div.stationPoint);
            }
            return;
        }
        if (ship.getFluxLevel() < 0.56f && div.localForceRatio >= 0.90f) {
            flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
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
    }

    private static float computeNearestVisibleEnemyShipDist(
            CombatEngineAPI engine,
            int side,
            Vector2f shipLoc,
            DivisionState targetEnemyDiv,
            BattleGroupState enemyGroup,
            ShipAPI focusEnemyShip) {
        if (shipLoc == null) return Float.MAX_VALUE;
        float minDist = (focusEnemyShip != null && focusEnemyShip.isAlive() && !focusEnemyShip.isHulk() && focusEnemyShip.getLocation() != null)
                ? distance(shipLoc, focusEnemyShip.getLocation())
                : Float.MAX_VALUE;
        if (targetEnemyDiv != null) {
            List<ShipAPI> eMembers = targetEnemyDiv.getAliveMembers();
            for (int i = 0; i < eMembers.size(); i++) {
                ShipAPI es = eMembers.get(i);
                if (es == null || !OtcAI.isShipVisibleToSide(engine, side, es) || es.getLocation() == null) continue;
                if (targetEnemyDiv.detachedStragglerIds != null && targetEnemyDiv.detachedStragglerIds.contains(es.getId())) continue;
                float d = distance(shipLoc, es.getLocation());
                if (d < minDist) {
                    minDist = d;
                }
            }
        }
        if (enemyGroup != null) {
            List<DivisionState> effEnemyDivs = OtcAI.getEffectiveEnemyDivisions(engine, enemyGroup);
            for (int dIdx = 0; dIdx < effEnemyDivs.size(); dIdx++) {
                DivisionState eDiv = effEnemyDivs.get(dIdx);
                if (eDiv == null || eDiv == targetEnemyDiv || !eDiv.isCombatEffective() || eDiv.centroid == null) continue;
                if (eDiv.coreMemberCount < 2 && !BattlefieldFirepowerEvaluator.isStationBastionDivision(eDiv)) continue;
                if (distance(shipLoc, eDiv.centroid) > 2600f) continue;
                List<ShipAPI> eMembers = eDiv.getAliveMembers();
                for (int i = 0; i < eMembers.size(); i++) {
                    ShipAPI es = eMembers.get(i);
                    if (es == null || !OtcAI.isShipVisibleToSide(engine, side, es) || es.getLocation() == null) continue;
                    if (eDiv.detachedStragglerIds != null && eDiv.detachedStragglerIds.contains(es.getId())) continue;
                    float d = distance(shipLoc, es.getLocation());
                    if (d < minDist) {
                        minDist = d;
                    }
                }
            }
        }
        return minDist;
    }

    private static void applyWorkFlankFlags(
            ShipAPI ship,
            ShipwideAIFlags flags,
            Vector2f slotPos,
            boolean crowdedByPeer,
            ShipAPI focusEnemyShip,
            DivisionState div,
            boolean structuredFormation,
            float dressTol,
            float combatSlotLeash,
            float nearestEnemyDist) {
        float distToFocus = (focusEnemyShip != null)
                ? distance(ship.getLocation(), focusEnemyShip.getLocation())
                : Float.MAX_VALUE;
        float contactDist = Math.min(distToFocus, nearestEnemyDist);
        float distToSlot = distance(ship.getLocation(), slotPos);
        boolean targetCorneredOrWeak = div.targetEnemyDivision != null
                && (div.targetEnemyDivision.edgeProximityPinch >= 0.25f
                || div.targetEnemyDivision.isUnableDivision
                || div.targetEnemyDivision.retreatingFraction >= 0.35f
                || div.targetEnemyDivision.combatThreatWeight <= 0.62f);
        if (focusEnemyShip != null && distToFocus < 2200f) {
            directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
            if (targetCorneredOrWeak && !div.hasRouteWaypoint && ship.getFluxLevel() < 0.56f) {
                flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL);
                flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
                if (ship.getFluxLevel() < 0.48f) {
                    flags.unsetFlag(AIFlags.DO_NOT_PURSUE);
                    flags.setFlag(AIFlags.PURSUING, FLAG_TTL);
                }
            }
        }
        float minFlankFloor = Math.max(520f, div.standoffRange * 0.68f);
        if (!targetCorneredOrWeak && contactDist < minFlankFloor && ship.getFluxLevel() >= 0.22f) {
            // Prevent flanking ships under flux pressure from diving nose-first into the enemy battle line!
            flags.unsetFlag(AIFlags.HARASS_MOVE_IN);
            flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
            flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
            return;
        }
        float flankSlotTol = (targetCorneredOrWeak && !div.hasRouteWaypoint && contactDist <= Math.max(1350f, div.standoffRange * 1.35f))
                ? Math.max(combatSlotLeash * 1.35f, 520f)
                : (structuredFormation ? dressTol * 1.15f : Math.max(320f, div.stationSpacing * 0.65f));
        if (div.hasRouteWaypoint
                || distToSlot > flankSlotTol
                || (!structuredFormation && crowdedByPeer && contactDist > 1450f)) {
            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
        }
    }

    private static void applyCarrierIntentFlags(
            ShipAPI ship,
            ShipwideAIFlags flags,
            Vector2f slotPos,
            boolean crowdedByPeer,
            ShipAPI focusEnemyShip,
            DivisionState div) {
        if (div.archetype == DivisionArchetype.CARRIER_GROUP
                && div.flagship != null
                && ship != div.flagship
                && ship.getNumFighterBays() == 0) {
            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
            flags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, div.flagship);
            return;
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
    }

    private static void applyCloseAndEngageFlags(
            ShipAPI ship,
            int shipIdx,
            ShipwideAIFlags flags,
            Vector2f slotPos,
            boolean crowdedByPeer,
            ShipAPI focusEnemyShip,
            DivisionState div,
            boolean structuredFormation,
            float dressTol,
            float individualEngageHorizon,
            float combatSlotLeash,
            float nearestEnemyDist,
            float now) {
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

        if (div.archetype == DivisionArchetype.CARRIER_GROUP
                && div.flagship != null
                && ship != div.flagship
                && ship.getNumFighterBays() == 0) {
            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
            flags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, div.flagship);
        } else if (standoffPosture && focusEnemyShip != null) {
            applyStandoffEngageFlags(
                    ship, flags, slotPos, focusEnemyShip, div, isLightOrDestroyerDiv,
                    structuredFormation, dressTol, individualEngageHorizon, combatSlotLeash, nearestEnemyDist);
        } else if (focusEnemyShip != null) {
            applyDirectCloseEngageFlags(
                    ship, shipIdx, flags, slotPos, crowdedByPeer, focusEnemyShip, div, activeMission,
                    structuredFormation, dressTol, individualEngageHorizon, combatSlotLeash, nearestEnemyDist);
        } else if (div.targetEnemyDivision != null && (distance(ship.getLocation(), slotPos) > (structuredFormation ? dressTol : 950f) || crowdedByPeer)) {
            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
        }
    }

    private static void applyStandoffEngageFlags(
            ShipAPI ship,
            ShipwideAIFlags flags,
            Vector2f slotPos,
            ShipAPI focusEnemyShip,
            DivisionState div,
            boolean isLightOrDdDiv,
            boolean structuredFormation,
            float dressTol,
            float individualEngageHorizon,
            float combatSlotLeash,
            float nearestEnemyDist) {
        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
        float distToFocus = distance(ship.getLocation(), focusEnemyShip.getLocation());
        float contactDist = Math.min(distToFocus, nearestEnemyDist);
        float distToSlot = distance(ship.getLocation(), slotPos);
        int divAliveCount = div.getAliveMembers().size();
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
        if (contactDist < backOffThreshold) {
            flags.unsetFlag(AIFlags.HARASS_MOVE_IN);
            flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
            flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
        } else if (contactDist > individualEngageHorizon) {
            if (distToSlot > dressTol) {
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
            } else if (safeToNudgeIn && div.engagementLevel >= 0.25f) {
                flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL);
            }
        } else if (contactDist > effectiveStandoff * 1.08f) {
            if (safeToNudgeIn && (contactDist > effectiveStandoff * 1.18f || div.engagementLevel >= 0.25f)) {
                flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL);
            }
            if (structuredFormation && distToSlot > combatSlotLeash) {
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
            }
        } else {
            // Maintaining contact in the standoff band: hold formation station and forbid solo forward lunges
            flags.unsetFlag(AIFlags.HARASS_MOVE_IN);
            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
            float inBandLeash = outmatchedInEhp
                    ? Math.max(combatSlotLeash, 420f)
                    : Math.max(combatSlotLeash * 1.45f, 560f);
            if ((structuredFormation || isLoneOrOutnumberedFrigOrDd)
                    && distToSlot > inBandLeash) {
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
            }
        }
    }

    private static void applyDirectCloseEngageFlags(
            ShipAPI ship,
            int shipIdx,
            ShipwideAIFlags flags,
            Vector2f slotPos,
            boolean crowdedByPeer,
            ShipAPI focusEnemyShip,
            DivisionState div,
            DivisionMissionType activeMission,
            boolean structuredFormation,
            float dressTol,
            float individualEngageHorizon,
            float combatSlotLeash,
            float nearestEnemyDist) {
        float shipEhpRatio = BattlefieldFirepowerEvaluator.computeShipEffectiveHp(ship)
                / Math.max(1f, BattlefieldFirepowerEvaluator.computeShipMaxEffectiveHp(ship));
        boolean loneOrOutnumberedFrigate = ship.isFrigate()
                && (div.getAliveMembers().size() == 1 || div.localForceRatio < 0.72f);
        boolean pressuredDestroyer = ship.isDestroyer()
                && (div.localForceRatio < 0.85f || shipEhpRatio < 0.65f || ship.getFluxLevel() >= 0.45f);
        boolean flakDangerToLightHull = isEnemyShipFlakLethalToLightHull(ship, focusEnemyShip);

        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip, div);
        float distToFocus = distance(ship.getLocation(), focusEnemyShip.getLocation());
        float contactDist = Math.min(distToFocus, nearestEnemyDist);
        float distToSlot = distance(ship.getLocation(), slotPos);
        boolean isWingman = (div.flagship != null && ship != div.flagship) || (div.flagship == null && shipIdx > 0);
        float safeFlakFloor = ship.isFrigate() ? 820f : 760f;

        float effectiveStandoff = Math.max(540f,
                (div.avgEngageRange > 250f && div.standoffRange <= 1050f)
                        ? Math.min(div.standoffRange, Math.max(540f, div.avgEngageRange * 0.88f))
                        : div.standoffRange);
        boolean isLightHull = ship.isFrigate() || ship.isDestroyer();
        // Disciplined Maintaining-Contact Floor: even in CLOSE_ACTION at 0% flux (start of engagement), ships must
        // brake onto their battle-line firing distance rather than coasting/diving nose-first into the enemy blob!
        float minContactFloor = isLightHull
                ? Math.max(560f, Math.min(860f, effectiveStandoff * 0.82f))
                : Math.max(520f, Math.min(820f, effectiveStandoff * 0.75f));

        boolean enemyCollapsed = focusEnemyShip != null
                && ((focusEnemyShip.getFluxTracker() != null && focusEnemyShip.getFluxTracker().isOverloadedOrVenting())
                || focusEnemyShip.getFluxLevel() >= 0.82f
                || (focusEnemyShip.getHullLevel() < 0.40f && focusEnemyShip.getFluxLevel() >= 0.55f));
        boolean pressCollapsedTarget = enemyCollapsed
                && !loneOrOutnumberedFrigate
                && !pressuredDestroyer
                && !flakDangerToLightHull
                && div.localForceRatio >= 1.15f
                && ship.getFluxLevel() < 0.46f;

        if ((flakDangerToLightHull && contactDist < safeFlakFloor)
                || (!pressCollapsedTarget && contactDist < minContactFloor)
                || (loneOrOutnumberedFrigate && contactDist < div.standoffRange * 0.85f && ship.getFluxLevel() >= 0.28f)
                || (pressuredDestroyer && contactDist < div.standoffRange * 0.85f && (ship.getFluxLevel() >= 0.38f || shipEhpRatio < 0.60f))) {
            flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
            flags.unsetFlag(AIFlags.HARASS_MOVE_IN);
            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
            flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL);
            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
            return;
        }

        if (loneOrOutnumberedFrigate || pressuredDestroyer || flakDangerToLightHull || !pressCollapsedTarget) {
            if (contactDist <= effectiveStandoff * 1.28f) {
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
            }
        }
        if (activeMission == DivisionMissionType.CLOSE_ACTION
                && contactDist >= minContactFloor * 1.12f
                && ship.getFluxLevel() < 0.48f
                && div.localForceRatio >= 0.95f) {
            flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
            if (div.useConvergence && pressCollapsedTarget) {
                flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL);
            }
        }

        if (contactDist > individualEngageHorizon) {
            if (distToSlot > dressTol || distance(ship.getLocation(), div.stationPoint) > 380f) {
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
            }
        } else if (structuredFormation && activeMission != DivisionMissionType.CLOSE_ACTION) {
            if (contactDist <= div.standoffRange * 1.20f && distToSlot > Math.min(combatSlotLeash, dressTol * 1.20f)) {
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
            }
        } else if (structuredFormation && activeMission == DivisionMissionType.CLOSE_ACTION
                && distToSlot > Math.max(260f, Math.min(combatSlotLeash, dressTol * 1.22f))
                && ship.getFluxLevel() < 0.85f) {
            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
        } else if (structuredFormation && isWingman && contactDist > 900f
                && ship.getFluxLevel() < 0.65f
                && distToSlot > Math.max(260f, Math.min(combatSlotLeash, dressTol * 1.30f))) {
            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
        } else if (crowdedByPeer && contactDist > individualEngageHorizon) {
            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
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
    static void ensureTaskManagerHandles(Object tmObj) {
        if (resolvedTaskManagerHandles || tmObj == null) return;
        resolvedTaskManagerHandles = true;
        try {
            ClassLoader cl = tmObj.getClass().getClassLoader();
            obfShipClass = Class.forName("com.fs.starfarer.combat.entities.Ship", false, cl);
            obfFleetManagerClass = Class.forName("com.fs.starfarer.combat.CombatFleetManager", false, cl);
            obfTaskManagerClass = Class.forName("com.fs.starfarer.combat.tasks.CombatTaskManager", false, cl);
            obfDeployedMemberClass = Class.forName("com.fs.starfarer.combat.CombatFleetManager$O0", false, cl);
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            getDeployedFleetMemberHandle = lookup.findVirtual(
                    obfFleetManagerClass,
                    "getDeployedFleetMember",
                    MethodType.methodType(obfDeployedMemberClass, obfShipClass));
            hasDirectOrdersHandle = lookup.findVirtual(
                    obfTaskManagerClass,
                    "hasDirectOrders",
                    MethodType.methodType(boolean.class, obfDeployedMemberClass));
            clearAssignedOrdersHandle = lookup.findVirtual(
                    obfTaskManagerClass,
                    "clearAssignedOrders",
                    MethodType.methodType(void.class, obfDeployedMemberClass));
        } catch (Throwable t) {
            hasDirectOrdersHandle = null;
            clearAssignedOrdersHandle = null;
            if (tmObj != null && tmObj.getClass().getName().startsWith("com.fs.starfarer.combat.")) {
                LOG.warn("[DivisionAI] Obfuscated CombatTaskManager MethodHandle resolution failed: " + t);
            }
        }
    }

    public static boolean verifyCombatObfuscatedHandles(ClassLoader cl) {
        if (cl == null) cl = DivisionCommanderAI.class.getClassLoader();
        try {
            Class<?> shipCls = Class.forName("com.fs.starfarer.combat.entities.Ship", false, cl);
            Class<?> fmCls = Class.forName("com.fs.starfarer.combat.CombatFleetManager", false, cl);
            Class<?> tmCls = Class.forName("com.fs.starfarer.combat.tasks.CombatTaskManager", false, cl);
            Class<?> dfmCls = Class.forName("com.fs.starfarer.combat.CombatFleetManager$O0", false, cl);
            Class<?> basicAiCls = Class.forName("com.fs.starfarer.combat.ai.BasicShipAI", false, cl);
            Class<?> wrapperCls = Class.forName("com.fs.starfarer.combat.entities.Ship$ShipAIWrapper", false, cl);
            Class<?> wrMapCls = Class.forName("com.fs.starfarer.combat.new.b", false, cl);
            Class<?> arrowMgrCls = Class.forName("com.fs.starfarer.combat.new.O0OO", false, cl);
            Class<?> arrowEntryCls = Class.forName("com.fs.starfarer.combat.new.O0OO$o", false, cl);
            Class<?> faderCls = Class.forName("com.fs.graphics.util.Fader", false, cl);
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            lookup.findVirtual(fmCls, "getDeployedFleetMember", MethodType.methodType(dfmCls, shipCls));
            lookup.findVirtual(tmCls, "hasDirectOrders", MethodType.methodType(boolean.class, dfmCls));
            lookup.findVirtual(tmCls, "clearAssignedOrders", MethodType.methodType(void.class, dfmCls));
            lookup.findVirtual(wrapperCls, "getAI", MethodType.methodType(ShipAIPlugin.class));
            lookup.findVirtual(basicAiCls, "setTargetOverride", MethodType.methodType(void.class, shipCls));
            MethodHandles.privateLookupIn(wrMapCls, lookup).findGetter(wrMapCls, "\u00d3o0000", arrowMgrCls);
            MethodHandles.privateLookupIn(arrowMgrCls, lookup).findGetter(arrowMgrCls, "\u00d600000", List.class);
            MethodHandles.privateLookupIn(arrowEntryCls, lookup).findGetter(arrowEntryCls, "\u00d600000", faderCls);
            lookup.findVirtual(faderCls, "isFadedOut", MethodType.methodType(boolean.class));
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Throwable t) {
            LOG.warn("[DivisionAI] Combat obfuscated MethodHandle verification failed: " + t);
            return false;
        }
    }

    /**
     * Runs every frame inside {@code DivisionAICombatPlugin.advance} (immediately after
     * {@code CombatTaskManager.advance} runs {@code fillTasks()} and BEFORE {@code CombatState.traverse}
     * advances the Warroom order-arrow manager {@code com.fs.starfarer.combat.new.O0OO}).
     *
     * <p>Why this must run every frame rather than only on 0.50s C2 ticks:
     * Vanilla's {@code CombatTaskManager.advance} runs {@code fillTasks()} every 0.5–1.0s and auto-assigns
     * pooled tasks (such as a Warroom {@code CAPTURE} order on a Comm Relay) to ships across the fleet.
     * If {@code clearAssignedOrders} only runs on 0.50s C2 ticks, {@code O0OO.super(float)} sees all ~25 fleet
     * ships gain a task order on the {@code fillTasks()} frame and then lose it on the C2 tick frame, pushing
     * 25–50 fading-out {@code O0OO$o} arrow instances per second into {@code O0OO.\u00d600000} (a LinkedList
     * from which vanilla Starsector NEVER removes faded-out arrows, causing 50,000–100,000+ invisible OpenGL
     * quad draw calls per Warroom frame). Clearing pooled auto-assignments on the exact same frame inside
     * {@code engine.advance} prevents {@code O0OO} from ever observing the transient auto-assignment.
     */
    public static void scrubPooledAutoAssignmentsEveryFrame(CombatEngineAPI engine, BattleGroupState myGroup) {
        if (engine == null || myGroup == null || myGroup.divisions.isEmpty()) return;
        CombatFleetManagerAPI fm = engine.getFleetManager(myGroup.side);
        if (fm == null) return;
        for (int dIdx = 0, dSize = myGroup.divisions.size(); dIdx < dSize; dIdx++) {
            DivisionState div = myGroup.divisions.get(dIdx);
            if (div == null || div.members.isEmpty()) continue;
            for (int mIdx = 0, mSize = div.members.size(); mIdx < mSize; mIdx++) {
                ShipAPI ship = div.members.get(mIdx);
                if (ship == null || !ship.isAlive() || ship.isHulk()) continue;
                if (OtcAI.isPlayerManuallyPiloting(engine, ship)) continue;
                CombatTaskManagerAPI tm = fm.getTaskManager(ship.isAlly());
                if (tm == null) continue;
                hasExplicitCommandOrder(ship, div, myGroup, tm);
            }
        }
    }

    /**
     * Returns true when a ship has an explicit per-ship direct order in the Command Window
     * ({@code CombatTaskManager.hasDirectOrders(o0) == true}), as opposed to a pooled task auto-assigned by
     * {@code CombatTaskManager.fillTasks()} (which otherwise hijacks battleships/cruisers onto control points!).
     * In headless mock tests where obfuscated classes are absent, falls back to true.
     */
    static boolean isDirectPlayerShipOrder(ShipAPI ship, CombatTaskManagerAPI tm, boolean clearIfPooledAutoAssignment) {
        if (ship == null || tm == null) return false;
        ensureTaskManagerHandles(tm);
        if (hasDirectOrdersHandle == null
                || obfTaskManagerClass == null
                || !obfTaskManagerClass.isInstance(tm)
                || obfShipClass == null
                || !obfShipClass.isInstance(ship)) {
            return true;
        }
        try {
            CombatEngineAPI engine = com.fs.starfarer.api.Global.getCombatEngine();
            CombatFleetManagerAPI fm = (engine != null) ? engine.getFleetManager(ship.getOriginalOwner()) : null;
            if (fm == null || !obfFleetManagerClass.isInstance(fm)) return true;
            Object deployedMember = getDeployedFleetMemberHandle.invoke(fm, ship);
            if (deployedMember == null) return false;
            boolean direct = (boolean) hasDirectOrdersHandle.invoke(tm, deployedMember);
            if (!direct && clearIfPooledAutoAssignment && clearAssignedOrdersHandle != null) {
                clearAssignedOrdersHandle.invoke(tm, deployedMember);
            }
            return direct;
        } catch (Throwable ignored) {
            return true;
        }
    }

    static boolean hasExplicitCommandOrder(ShipAPI ship, DivisionState div, BattleGroupState myGroup, CombatTaskManagerAPI tm) {
        if (ship != null && (ship.isRetreating() || ship.isDirectRetreat())) return true;
        if (tm == null) return false;
        AssignmentInfo info = tm.getAssignmentFor(ship);
        if (info == null || info.getType() == null) return false;
        CombatAssignmentType t = info.getType();
        if (t == CombatAssignmentType.SEARCH_AND_DESTROY) {
            return false;
        }
        if (t == CombatAssignmentType.RETREAT || t == CombatAssignmentType.REPAIR_AND_REFIT) {
            return true;
        }
        // If the ship is assigned to capture/control/assault an objective and its division is already seizing that
        // exact objective, they are aligned.
        if (info.getTarget() instanceof BattleObjectiveAPI) {
            BattleObjectiveAPI obj = (BattleObjectiveAPI) info.getTarget();
            if (div.currentIntent == DivisionIntentType.TAKE_AND_HOLD && div.targetObjective == obj) {
                return false;
            }
            // If the objective still needs capturing and NO friendly division in myGroup is currently executing
            // TAKE_AND_HOLD on it, do NOT clear the ship's objective order — honor the player's capture order!
            boolean anyDivisionTakingObj = isObjectiveClaimedByOtherDivision(obj, null, myGroup);
            if (!anyDivisionTakingObj && obj.getOwner() != ship.getOwner()) {
                return true;
            }
            return isDirectPlayerShipOrder(ship, tm, true);
        }
        return isDirectPlayerShipOrder(ship, tm, true);
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
        List<DivisionState> enemyDivs = OtcAI.getEffectiveEnemyDivisions(engine, enemyGroup);
        boolean anyAbleVisibleEnemy = false;
        boolean anyAbleVisibleMobileCombatEnemy = false;
        for (DivisionState eDiv : enemyDivs) {
            if (eDiv.isAbleCombatEffective() && OtcAI.isDivisionVisibleToSide(engine, myDiv.side, eDiv)) {
                anyAbleVisibleEnemy = true;
                if (!BattlefieldFirepowerEvaluator.isStationBastionDivision(eDiv)
                        && eDiv.archetype != DivisionArchetype.SERVICE_GROUP
                        && eDiv.combatThreatWeight > 0.55f) {
                    anyAbleVisibleMobileCombatEnemy = true;
                }
            }
        }

        for (DivisionState eDiv : enemyDivs) {
            if (!eDiv.isCombatEffective()) continue;
            // Strict Fog of War check: cannot target enemy divisions hidden in the Fog of War!
            if (!OtcAI.isDivisionVisibleToSide(engine, myDiv.side, eDiv)) {
                continue;
            }

            boolean isStationBastion = BattlefieldFirepowerEvaluator.isStationBastionDivision(eDiv);
            Vector2f visCentroid = OtcAI.getVisibleCentroidForSide(engine, myDiv.side, eDiv);
            float rawVisFp = OtcAI.getVisibleFpForSide(engine, myDiv.side, eDiv);
            float threatW = Math.max(0.28f, eDiv.combatThreatWeight);
            float visFp = (isStationBastion ? Math.min(rawVisFp, 75f) : rawVisFp) * threatW;
            float dist = distance(myDiv.centroid, visCentroid);
            float score = -dist * (dist > 1500f ? 0.68f : 0.42f);
            if (isStationBastion && anyAbleVisibleMobileCombatEnemy && dist > 1550f) {
                score -= 380f;
            }

            // Item F: Heavily deprioritize retreating ("unable") enemy divisions as long as able enemy divisions remain
            if (anyAbleVisibleEnemy
                    && myGroup.currentPhase != BattlePhase.GENERAL_CHASE
                    && (eDiv.isUnableDivision || eDiv.retreatingFraction >= 0.5f)) {
                score -= 1350f;
            }

            // OTC Force-Distribution Overkill awareness for Division Commanders & Carriers:
            // Count friendly FP from OTHER friendly divisions already targeting eDiv; if eDiv has reached its
            // overkillCapFp and another visible able enemy division is not overkilled, redirect onto the CoG!
            if (myGroup != null && anyAbleVisibleEnemy) {
                float peerCommittedFp = 0f;
                for (DivisionState peer : myGroup.divisions) {
                    if (peer == null || peer == myDiv || !peer.isAbleCombatEffective()) continue;
                    DivisionState peerTgt = (peer.otcOrder != null && peer.otcOrder.targetEnemy != null)
                            ? peer.otcOrder.targetEnemy
                            : peer.targetEnemyDivision;
                    if (peerTgt == eDiv) {
                        peerCommittedFp += peer.totalFpStrength;
                    }
                }
                boolean otherUnderCapExists = false;
                for (DivisionState otherE : enemyDivs) {
                    if (otherE == null || otherE == eDiv || !otherE.isAbleCombatEffective()) continue;
                    if (!OtcAI.isDivisionVisibleToSide(engine, myDiv.side, otherE)) continue;
                    if (!otherE.isOverkilled) {
                        otherUnderCapExists = true;
                        break;
                    }
                }
                if (otherUnderCapExists && (peerCommittedFp >= eDiv.overkillCapFp || eDiv.isOverkilled)) {
                    score -= 1150f;
                }
                // Enemy Center of Gravity & Beleaguered Friendly Line Relief
                if (myGroup.hasEnemyCenterOfGravity) {
                    float distToCog = distance(visCentroid, myGroup.enemyCenterOfGravity);
                    score += Math.max(-280f, 150f - distToCog * 0.12f);
                }
                for (DivisionState friendDiv : myGroup.divisions) {
                    if (friendDiv == null || friendDiv == myDiv || !friendDiv.isAbleCombatEffective()) continue;
                    if (friendDiv.totalFpStrength >= 18f
                            && friendDiv.localEnemyStrength >= 12f
                            && friendDiv.localForceRatio < 0.88f) {
                        float dFriend = distance(friendDiv.centroid, visCentroid);
                        if (dFriend <= 2000f) {
                            score += (2000f - dFriend) * 0.25f;
                        }
                    }
                }
                if (myDiv.consentedWingSide != 0 && otherUnderCapExists) {
                    float latSide = Math.signum(visCentroid.x - myGroup.fleetCentroid.x);
                    if (latSide == myDiv.consentedWingSide) score += 180f;
                }
            }

            // Item D: Bonus for enemy divisions pinched against a map edge or corner (restricted maneuver room)
            score += BattlefieldFirepowerEvaluator.computeEdgeProximityPinch(engine, visCentroid, eDiv.freeTimeRadius) * 210f * threatW;

            // Item D (Independent Ship Disposition Alignment):
            // In disorganized/semi-organized fleets (organizationRatio < 0.75), strongly prefer enemy divisions
            // near our friendly independent ships' projected centroid so our divisions fight alongside our independents!
            if (myGroup != null && myGroup.friendlyIndependentFp >= 12f && myGroup.organizationRatio < 0.75f) {
                float dIndep = distance(myGroup.independentProjectedCentroid, visCentroid);
                if (dIndep <= 1800f) {
                    score += (1800f - dIndep) * 0.28f * (1f - myGroup.organizationRatio);
                }
            }

            // Item F Defensive Counter: deprioritize enemy divisions suspected of feigning retreat
            if (eDiv.enemyFeignedRetreatSuspicion >= 0.60f) {
                score -= 260f;
            }

            // Defeat-in-Detail: prefer isolated / collapsing enemy divisions (separated from mutual support or flux-locked)
            if (eDiv.stationKeepingRatio > 1.45f) {
                score += 320f;
            }
            if (visFp >= 18f) {
                score += eDiv.avgFluxLevel * 380f + (1.0f - eDiv.avgHullLevel) * 380f;
            }

            // Sticky target bonus prevents divisions from flip-flopping between two enemy divisions every few seconds
            if (eDiv == previousTarget && dist <= 1850f) {
                score += 280f;
            }

            // Deprioritize civilian/auxiliary/armed-freighter groups when combat divisions exist
            if (eDiv.archetype == DivisionArchetype.SERVICE_GROUP || threatW <= 0.55f) {
                score -= 480f;
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
                if (myGroup != null && eDiv == myGroup.primaryThreatEnemyDivision && dist <= 2400f) {
                    float orgC2Scale = Math.max(0f, Math.min(1f, (myGroup.organizationRatio - 0.93f) / 0.07f));
                    score += 320f * orgC2Scale;
                }
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
        float now = (com.fs.starfarer.api.Global.getCombatEngine() != null)
                ? com.fs.starfarer.api.Global.getCombatEngine().getTotalElapsedTime(false)
                : 0f;
        if (!ShipSystemCoordinator.shouldSuppressManeuverTargetForLinearSkill(ship, div, null, now)) {
            flags.setFlag(AIFlags.MANEUVER_TARGET, FLAG_TTL, focusEnemyShip);
        } else {
            flags.unsetFlag(AIFlags.MANEUVER_TARGET);
        }
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
        boolean convLock = div != null && distFocus <= (div.useConvergence ? 1850f : 1550f);
        boolean preferFocus = currTarget == null
                || !currTarget.isAlive()
                || currTarget.isHulk()
                || convLock
                || distFocus + 250f < distance(ship.getLocation(), currTarget.getLocation());
        CombatEngineAPI engine = com.fs.starfarer.api.Global.getCombatEngine();
        boolean isPlayerShip = (engine != null && ship == engine.getPlayerShip());
        // Never overwrite the human player's R-key target reticle (ship.setShipTarget) on engine.getPlayerShip(),
        // even when Autopilot is enabled (since players frequently use R to aim manual missile groups or inspect flux).
        if (preferFocus && !isPlayerShip) {
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
            } catch (Throwable t) {
                setTargetOverrideHandle = null;
                if (ai != null && ai.getClass().getName().startsWith("com.fs.starfarer.combat.")) {
                    LOG.warn("[DivisionAI] Obfuscated BasicShipAI.setTargetOverride MethodHandle resolution failed: " + t);
                }
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

    public static ShipAPI pickFocusEnemyShip(
            CombatEngineAPI engine,
            int observerSide,
            DivisionState enemyDiv,
            Vector2f fromPoint) {
        return pickFocusEnemyShip(engine, observerSide, enemyDiv, fromPoint, false);
    }

    public static ShipAPI pickFocusEnemyShip(
            CombatEngineAPI engine,
            int observerSide,
            DivisionState enemyDiv,
            Vector2f fromPoint,
            boolean useConvergence) {
        if (enemyDiv == null) return null;
        List<ShipAPI> enemies = enemyDiv.getAliveMembers();
        boolean isStationBastion = BattlefieldFirepowerEvaluator.isStationBastionDivision(enemyDiv);
        boolean hasVisibleArmedStationModule = false;
        if (isStationBastion) {
            for (int i = 0; i < enemies.size(); i++) {
                ShipAPI e = enemies.get(i);
                if (e == null || e.isStation() || !OtcAI.isShipVisibleToSide(engine, observerSide, e)) continue;
                if (BattlefieldFirepowerEvaluator.hasAnyUsableCombatWeapon(e) || e.getNumFighterBays() > 0) {
                    hasVisibleArmedStationModule = true;
                    break;
                }
            }
        }
        ShipAPI best = null;
        float bestScore = -Float.MAX_VALUE;
        Vector2f refPt = (fromPoint != null) ? fromPoint : enemyDiv.centroid;
        for (ShipAPI e : enemies) {
            if (!OtcAI.isShipVisibleToSide(engine, observerSide, e)) continue;
            float d = (refPt != null) ? distance(refPt, e.getLocation()) : 1000f;
            float inRangeBonus = (d <= 1150f) ? 280f : (d <= 1450f ? 120f : 0f);
            float stationModAdjust = 0f;
            if (isStationBastion && hasVisibleArmedStationModule) {
                boolean armedOrCarrierMod = BattlefieldFirepowerEvaluator.hasAnyUsableCombatWeapon(e)
                        || e.getNumFighterBays() > 0;
                boolean neverDetachesStrut = e.getVariant() != null
                        && e.getVariant().hasHullMod("never_detaches");
                if (e.isStation() && !armedOrCarrierMod) {
                    // Central shared_flux_sink station hub with no weapons of its own cannot be killed until modules die
                    stationModAdjust = -2500f;
                } else if (neverDetachesStrut && !armedOrCarrierMod) {
                    // Structural station spurs (ii_module_strut) are invulnerable connectors
                    stationModAdjust = -2200f;
                } else if (armedOrCarrierMod && !e.isStation()) {
                    // Prioritize active combat/hangar station modules facing our division!
                    stationModAdjust = 450f;
                } else if (!armedOrCarrierMod) {
                    // Passive armor plates only take incidental fire unless blocking the line of fire
                    stationModAdjust = -520f;
                }
            }
            float score;
            if (useConvergence) {
                // Convergence Focus-Fire Synchronization:
                // Lock onto the highest-vulnerability enemy hull that is actually within our division's gun envelope
                boolean ovl = e.getFluxTracker() != null && e.getFluxTracker().isOverloadedOrVenting();
                float hardFrac = (e.getFluxTracker() != null && e.getFluxTracker().getMaxFlux() > 1f)
                        ? (e.getFluxTracker().getHardFlux() / e.getFluxTracker().getMaxFlux())
                        : 0f;
                score = -d * 0.72f
                        + inRangeBonus
                        + stationModAdjust
                        + (ovl ? 750f : 0f)
                        + e.getFluxLevel() * 680f
                        + hardFrac * 380f
                        + (1f - e.getHullLevel()) * 540f;
            } else {
                // Standard Human Division Commander: strongly prefers the frontmost exposed/overloaded enemy ship in weapon range
                boolean ovl = e.getFluxTracker() != null && e.getFluxTracker().isOverloadedOrVenting();
                score = -d * 0.75f + inRangeBonus + stationModAdjust + (ovl ? 680f : 0f) + e.getFluxLevel() * 560f + (1f - e.getHullLevel()) * 440f;
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
        if (div.archetype.isCarrierArchetype()) {
            return null;
        }
        boolean divIsAlly = (div.flagship != null && div.flagship.isAlly());
        if (div.side != 0 || divIsAlly) return null;
        CombatFleetManagerAPI fm = engine.getFleetManager(div.side);
        CombatTaskManagerAPI tm = (fm != null) ? fm.getTaskManager(false) : null;
        if (tm == null) return null;

        // 1. Check if any ship in this division is directly assigned to a BattleObjectiveAPI
        for (ShipAPI s : div.getAliveMembers()) {
            AssignmentInfo info = tm.getAssignmentFor(s);
            if (info != null && info.getTarget() instanceof BattleObjectiveAPI
                    && isDirectPlayerShipOrder(s, tm, false)) {
                BattleObjectiveAPI obj = (BattleObjectiveAPI) info.getTarget();
                if (!isObjectiveClaimedByOtherDivision(obj, div, myGroup)
                        && !isObjectiveSecuredByOtherDivisions(engine, obj, div, myGroup)) {
                    return obj;
                }
            }
        }

        // 2. Check active objective assignments in CombatTaskManager:
        //    To guarantee absolute player authority when the player orders a Control Point captured mid-battle,
        //    select the best available non-carrier division in myGroup (strongly preferring Frigate/Phase/Destroyer
        //    divisions first, then Cruisers/Force, and only falling back to Heavy/Battleship divisions if no lighter
        //    division is alive or available).
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
                // Pooled (fleet-wide) objective orders only pull in a division while the point still needs taking or
                // is threatened. An owned point that friendly ships already garrison is not this division's job.
                if (obj.getOwner() == div.side
                        && (t == CombatAssignmentType.CONTROL || t == CombatAssignmentType.DEFEND
                        || isObjectiveSecuredByOtherDivisions(engine, obj, div, myGroup))) {
                    continue;
                }
                if (!isBestDivisionForPooledObjectiveOrder(obj, div, myGroup, previousObjective)) {
                    continue;
                }
                float d = distance(div.centroid, obj.getLocation());
                float sticky = (obj == previousObjective) ? 600f : 0f;
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

    private static boolean isBestDivisionForPooledObjectiveOrder(
            BattleObjectiveAPI obj,
            DivisionState candidateDiv,
            BattleGroupState myGroup,
            BattleObjectiveAPI candidatePrevObj) {
        if (obj == null || candidateDiv == null) return false;
        if (myGroup == null || myGroup.divisions == null || myGroup.divisions.size() <= 1) {
            return candidateDiv.archetype != DivisionArchetype.BATTLESHIP_DIVISION || myGroup == null || myGroup.divisions.size() == 1;
        }
        float myScore = scoreDivisionForObjectiveCapture(candidateDiv, obj, candidatePrevObj);
        if (myScore <= -900000f) return false;
        for (int i = 0; i < myGroup.divisions.size(); i++) {
            DivisionState other = myGroup.divisions.get(i);
            if (other == null || other == candidateDiv || !other.isCombatEffective()) continue;
            if (other.archetype.isCarrierArchetype()) continue;
            if (other.flagship != null && other.flagship.isAlly()) continue;
            if (other.avgFluxLevel >= 0.80f) continue;
            // If `other` is already committed to a DIFFERENT objective, don't count it as free for `obj`
            if (other.currentIntent == DivisionIntentType.TAKE_AND_HOLD
                    && other.targetObjective != null
                    && other.targetObjective != obj) {
                continue;
            }
            float otherScore = scoreDivisionForObjectiveCapture(other, obj, other.targetObjective);
            if (otherScore > myScore + 1f) {
                return false;
            }
        }
        return true;
    }

    private static float scoreDivisionForObjectiveCapture(
            DivisionState d,
            BattleObjectiveAPI obj,
            BattleObjectiveAPI prevObj) {
        if (d == null || obj == null || d.archetype.isCarrierArchetype()) return -1000000f;
        float roleBonus;
        if (d.archetype == DivisionArchetype.FRIGATE_DIVISION
                || d.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION
                || d.archetype == DivisionArchetype.PHASE_DIVISION
                || d.archetype == DivisionArchetype.DESTROYER_DIVISION
                || d.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION) {
            roleBonus = 2200f;
        } else if (d.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                || d.archetype == DivisionArchetype.MISSILE_BATTLESHIP_DIVISION) {
            roleBonus = -2400f;
        } else {
            roleBonus = 600f;
        }
        float dist = (d.centroid != null && obj.getLocation() != null)
                ? distance(d.centroid, obj.getLocation())
                : 3000f;
        float sticky = (prevObj == obj || d.targetObjective == obj) ? 750f : 0f;
        float fluxPenalty = d.avgFluxLevel * 800f;
        return roleBonus + sticky - dist - fluxPenalty;
    }

    private static Vector2f computePersonalExtractionPoint(
            ShipAPI ship,
            DivisionState div,
            BattleGroupState myGroup,
            ShipAPI focusEnemyShip) {
        if (ship == null || ship.getLocation() == null) {
            return (myGroup != null) ? myGroup.reserveStation : null;
        }
        // Prefer extracting 680u behind a healthy friendly division or behind our own division's front rank
        if (focusEnemyShip != null && focusEnemyShip.getLocation() != null) {
            Vector2f sLoc = ship.getLocation();
            Vector2f eLoc = focusEnemyShip.getLocation();
            float dx = sLoc.x - eLoc.x;
            float dy = sLoc.y - eLoc.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            float escX = len > 1f ? (dx / len) : 0f;
            float escY = len > 1f ? (dy / len) : (div.side == 0 ? -1f : 1f);
            float rearBias = (div.side == 0) ? -0.35f : 0.35f;
            escY += rearBias;
            float nLen = (float) Math.sqrt(escX * escX + escY * escY);
            if (nLen > 0.01f) {
                escX /= nLen;
                escY /= nLen;
            }
            return new Vector2f(sLoc.x + escX * 950f, sLoc.y + escY * 950f);
        }
        return (myGroup != null && myGroup.reserveStation != null)
                ? myGroup.reserveStation
                : div.stationPoint;
    }

    private static void applyOutOfCombatSpeedGovernor(
            ShipAPI ship,
            ShipwideAIFlags flags,
            Vector2f slotPos,
            List<ShipAPI> alive,
            DivisionState div,
            float minSustainableSpeed) {
        if (ship == null || ship.getLocation() == null || div == null || alive == null) return;
        float fwdX = div.formationHeadingX;
        float fwdY = div.formationHeadingY;
        if (Math.abs(fwdX) < 0.01f && Math.abs(fwdY) < 0.01f) {
            fwdY = (div.side == 0) ? 1f : -1f;
        }
        // Find the slowest core member's current forward projection along the march axis
        float slowestFwdProj = Float.MAX_VALUE;
        for (int i = 0; i < alive.size(); i++) {
            ShipAPI m = alive.get(i);
            if (m == null || !m.isAlive() || m.isHulk() || m.getLocation() == null) continue;
            if (div.detachedStragglerIds != null && div.detachedStragglerIds.contains(m.getId())) continue;
            float p = m.getLocation().x * fwdX + m.getLocation().y * fwdY;
            if (p < slowestFwdProj) {
                slowestFwdProj = p;
            }
        }
        if (slowestFwdProj >= Float.MAX_VALUE * 0.5f) return;

        float myFwdProj = ship.getLocation().x * fwdX + ship.getLocation().y * fwdY;
        float leadOverSlowest = myFwdProj - slowestFwdProj;
        // Account for natural formation depth (e.g. Forward Wedge point ship is up to ~0.6 * stationSpacing ahead)
        float allowedFormationDepthLead = Math.max(80f, div.stationSpacing * 0.45f);
        float excessLead = leadOverSlowest - allowedFormationDepthLead;
        if (excessLead > 15f) {
            flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
            if (slotPos != null) {
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
            }
            float curFwdVel = (ship.getVelocity() != null)
                    ? (ship.getVelocity().x * fwdX + ship.getVelocity().y * fwdY)
                    : 0f;
            if (curFwdVel > minSustainableSpeed + 2f || (excessLead > 65f && curFwdVel > minSustainableSpeed * 0.78f)) {
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                if (curFwdVel > minSustainableSpeed + 9f || excessLead > 105f) {
                    ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                }
            }
        }
    }

    private static void applyActiveHelmsmanshipCollisionAvoidance(
            CombatEngineAPI engine,
            ShipAPI ship,
            ShipwideAIFlags flags,
            List<ShipAPI> alive,
            DivisionState div,
            BattleGroupState myGroup) {
        if (ship == null || ship.getLocation() == null || ship.getVelocity() == null) return;
        float vx = ship.getVelocity().x;
        float vy = ship.getVelocity().y;
        float spd = (float) Math.sqrt(vx * vx + vy * vy);
        if (spd < 18f) return;

        ShipAPI obstacle = ShipSystemCoordinator.findImminentFriendlyInForwardPath(
                engine, ship, alive, myGroup, Math.max(240f, Math.min(540f, spd * 2.1f)));
        if (obstacle == null || obstacle.getLocation() == null) return;

        // During an active/marshaling division ram charge, same-division wingmen have non-crossing parallel target lanes;
        // do not swerve sideways into a neighbor's lane unless the obstacle is directly dead ahead on our bow!
        boolean sameDivPeer = alive != null && alive.contains(obstacle);
        ShipSystemCoordinator.ShipSkillProfile ramProf = ShipSystemCoordinator.getSkillProfile(ship);
        boolean inRamCharge = div != null && ramProf.isDesignedToRam
                && div.rammingMode != PermanentDivisionManager.DivisionRammingMode.OFF
                && ((ship.getSystem() != null && (ship.getSystem().isActive() || ship.getSystem().isChargeup()))
                || (div.synchronizedSkillManeuverActive && div.synchronizedSkillStage > 0)
                || (div.activeSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE && div.setpieceStage > 0));
        if (sameDivPeer && inRamCharge) {
            return;
        }

        float dx = obstacle.getLocation().x - ship.getLocation().x;
        float dy = obstacle.getLocation().y - ship.getLocation().y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;
        float nx = dx / dist;
        float ny = dy / dist;
        float oVx = (obstacle.getVelocity() != null) ? obstacle.getVelocity().x : 0f;
        float oVy = (obstacle.getVelocity() != null) ? obstacle.getVelocity().y : 0f;
        float closingSpd = (vx - oVx) * nx + (vy - oVy) * ny;
        float combinedR = Math.max(40f, ship.getCollisionRadius()) + Math.max(40f, obstacle.getCollisionRadius());

        if (closingSpd > 15f && dist <= combinedR * 1.45f + 130f) {
            ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
            if (dist <= combinedR * 1.20f + 70f && closingSpd > 28f) {
                ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            }
            if (flags != null && dist <= combinedR * 1.30f + 90f) {
                float perpX = -ny;
                float perpY = nx;
                double rad = Math.toRadians(ship.getFacing());
                float hdgX = (float) Math.cos(rad);
                float hdgY = (float) Math.sin(rad);
                float sideSign = (hdgX * perpX + hdgY * perpY >= 0f) ? 1f : -1f;
                Vector2f avoidPt = new Vector2f(
                        ship.getLocation().x + perpX * sideSign * (combinedR + 110f) - nx * 60f,
                        ship.getLocation().y + perpY * sideSign * (combinedR + 110f) - ny * 60f);
                flags.setFlag(AIFlags.MOVEMENT_DEST, 0.25f, avoidPt);
            }
        }
    }

    static BattleObjectiveAPI findNearestContestObjective(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            BattleObjectiveAPI previousObjective) {
        List<BattleObjectiveAPI> objs = engine.getObjectives();
        if (objs == null || objs.isEmpty()) return null;

        // First pass: prefer uncaptured objectives NOT already claimed by another friendly division,
        // weighted by Grand Formation Wing Consent so divisions take objectives in their consented lane
        // instead of crossing the fleet or swapping objectives after deployment!
        BattleObjectiveAPI bestUnclaimed = null;
        float bestScore = -Float.MAX_VALUE;
        for (BattleObjectiveAPI obj : objs) {
            if (obj == null || obj.getOwner() == div.side) continue;
            if (isObjectiveClaimedByOtherDivision(obj, div, myGroup)) continue;
            float d = distance(div.centroid, obj.getLocation());
            float sticky = (obj == previousObjective) ? 750f : 0f;
            float consentBonus = 0f;
            if (myGroup != null) {
                if (div.hasFleetFormationOffset) {
                    float laneX = myGroup.fleetCentroid.x + div.fleetFormationOffset.x;
                    float lateralDiff = Math.abs(obj.getLocation().x - laneX);
                    consentBonus -= lateralDiff * 0.25f;
                }
                if (div.consentedWingSide != 0) {
                    float objSide = Math.signum(obj.getLocation().x - myGroup.fleetCentroid.x);
                    if (objSide == div.consentedWingSide) {
                        consentBonus += 580f;
                    } else if (objSide == -div.consentedWingSide) {
                        consentBonus -= 680f;
                    }
                }
            }
            float score = -d + sticky + consentBonus;
            if (score > bestScore) {
                bestScore = score;
                bestUnclaimed = obj;
            }
        }
        return bestUnclaimed;
    }

    /**
     * True when an objective is already ours, garrisoned by hulls from OTHER friendly divisions, and no enemy
     * warship is near it. A capture-and-hold order on such a point is satisfied; holding an extra division
     * beside it (inter-division spacing keeps it off the crowded point) just parks it out of the battle.
     */
    static boolean isObjectiveSecuredByOtherDivisions(
            CombatEngineAPI engine,
            BattleObjectiveAPI obj,
            DivisionState myDiv,
            BattleGroupState myGroup) {
        if (engine == null || obj == null || myDiv == null || obj.getLocation() == null) return false;
        if (obj.getOwner() != myDiv.side) return false;
        java.util.Set<ShipAPI> mine = new java.util.HashSet<>(myDiv.getAliveMembers());
        float garrison = 0f;
        List<ShipAPI> ships = engine.getShips();
        if (ships == null) return false;
        for (ShipAPI s : ships) {
            if (s == null || !s.isAlive() || s.isHulk() || s.isFighter() || s.isShuttlePod()) continue;
            float d = distance(s.getLocation(), obj.getLocation());
            if (s.getOwner() == myDiv.side) {
                if (mine.contains(s) || d > 1100f || s.isRetreating()) continue;
                switch (s.getHullSize()) {
                    case CAPITAL_SHIP: garrison += 4f; break;
                    case CRUISER: garrison += 3f; break;
                    case DESTROYER: garrison += 2f; break;
                    default: garrison += 1f; break;
                }
            } else if (s.getOwner() != 100 && d <= 1700f) {
                return false; // contested: keep holding
            }
        }
        return garrison >= 3f;
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














    static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
