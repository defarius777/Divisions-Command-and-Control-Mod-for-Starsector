package divisionai;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import divisionai.DivisionModel.BattlePhase;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.OtcOrderType;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionIntentType;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionModel.StrikePlan;
import divisionai.PermanentDivisionManager.DivisionFormation;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.DivisionSetpieceAttack;
import divisionai.PermanentDivisionManager.FleetWideFormation;
import divisionai.PermanentDivisionManager.MultiDivisionSetpiece;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.OtcPlay;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OTC orchestration: turns battle-wide intent into per-division {@link DivisionModel.OtcOrder}s.
 * Runs on the OTC cadence (1.5s) after the phase, Main Effort and Reserve have been chosen.
 *
 * Plays (each toggled in the Fleet Doctrine window, see {@link OtcPlay}):
 *  - Timed Strikes: missile/strike-craft divisions stage, then commit together onto one focus ship.
 *  - Reserve & Exploit: one relief division per crisis, reserve held until needed, leashed pursuit of broken enemies.
 *  - Anchor & Fix: Main Effort fixes the primary threat; other divisions get allocated enemy divisions.
 *  - Pincer Flanks: flankers on the same target get opposite flank sides.
 *
 * Authority ({@link OtcAuthority}) limits which taskings are issued here; whether a tasking is binding
 * or merely a request is decided by the Division Commander.
 */
public class OtcBattlePlanner {

    public static final float TASK_TTL = 4.5f;
    public static final float STRIKE_WINDOW = 14f;
    public static final float CHASE_LEASH = 4200f;

    private static final float STRIKE_COOLDOWN = 20f;
    private static final float STRIKE_MAX_RANGE = 5500f;
    private static final float MARSHAL_TIME_MIN = 6f;
    private static final float MARSHAL_TIME_MAX = 20f;
    private static final float MISSILE_MARSHAL_DIST = 1900f;
    private static final float REINFORCE_MAX_DIST = 5000f;
    private static final float CLOSE_REINFORCE_MAX_DIST = 2500f;
    private static final float ORDER_LOG_INTERVAL = 20f;

    /**
     * @param endangeredDiv the significant division whose trouble drives Stabilize & Relieve (may be null)
     * @param minorEndangeredDiv the worst-off division of any size, relieved locally when there is no major crisis
     */
    public static void planBattle(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            DivisionState endangeredDiv,
            DivisionState minorEndangeredDiv,
            float now) {

        OtcAuthority auth = myGroup.authority;
        List<DivisionState> active = new ArrayList<>();
        for (DivisionState d : myGroup.divisions) {
            if (d.isAbleCombatEffective()) active.add(d);
        }
        List<DivisionState> visibleEnemies = new ArrayList<>();
        boolean anyAbleVisibleEnemy = false;
        for (DivisionState e : enemyGroup.divisions) {
            if (e.isCombatEffective() && OtcAI.isDivisionVisibleToSide(engine, myGroup.side, e)) {
                visibleEnemies.add(e);
                if (e.isAbleCombatEffective() && e.retreatingFraction < 0.5f) {
                    anyAbleVisibleEnemy = true;
                }
            }
        }
        // Item F: While able enemy divisions are still fighting on the field, exclude retreating ("unable") enemy
        // divisions from standard battle-line fire distribution & strike plans (they are handled by General Chase).
        if (anyAbleVisibleEnemy && myGroup.currentPhase != BattlePhase.GENERAL_CHASE) {
            for (Iterator<DivisionState> it = visibleEnemies.iterator(); it.hasNext(); ) {
                DivisionState e = it.next();
                if (e.isUnableDivision || e.retreatingFraction >= 0.5f) {
                    it.remove();
                }
            }
        }

        Set<DivisionState> tasked = new HashSet<>();
        float expires = now + TASK_TTL;
        boolean fullAssault = OtcAI.isFullAssaultActive(engine, myGroup);
        if (fullAssault && myGroup.strikePlan != null && !myGroup.strikePlan.committed) {
            myGroup.strikePlan.committed = true;
            myGroup.strikePlan.commitTime = now;
        }

        // 1. Timed strikes (claims its participants first so nothing else re-tasks them mid-run)
        if (myGroup.isPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES)) {
            runStrikePlan(engine, myGroup, active, now, expires, tasked);
        } else if (myGroup.strikePlan != null) {
            endStrikePlan(myGroup, now);
        }

        // 1b. Item F (Fleet-Wide / Multi-Division "Feigned Retreat") & Item E ("Trade Space for Time"):
        // Highly organized fleets (organizationRatio >= 0.75 or Convergence) can coordinate multi-division
        // space-centroid offsets (Trade Space for Time) and 2-stage Feigned Retreat alpha-strike traps!
        if (!fullAssault && auth.allowsPositioning()) {
            evaluateAndAssignFleetFeignedRetreat(engine, myGroup, active, visibleEnemies, now, expires, tasked);
            evaluateAndAssignFleetTradeSpaceForTime(engine, myGroup, active, visibleEnemies, now, expires, tasked);
        }

        boolean reservePlay = myGroup.isPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE) && auth.allowsRetasking();

        // 1c. Fleet-Wide Line Passing: assign healthy divisions to cover (line-pass) any division that is hauling out
        //     to vent, and run the Engage -> Trade Space -> Feigned Retreat -> Alpha Strike fleet battle sequence.
        if (!fullAssault && auth.allowsPositioning()) {
            assignFleetLinePassing(engine, myGroup, active, visibleEnemies, now, expires, tasked);
            evaluateFleetBattleSequence(engine, myGroup, active, visibleEnemies, now, expires, tasked);
        }

        // 2. Crisis relief: exactly one relief division per crisis. A major crisis (Stabilize & Relieve) may pull a
        //    division from across the field; a minor one (a small division in trouble) only gets a close neighbour.
        myGroup.crisisDivision = (myGroup.currentPhase == BattlePhase.REINFORCE_THE_LINE) ? endangeredDiv : null;
        if (reservePlay && myGroup.crisisDivision != null) {
            assignReinforcement(engine, myGroup, myGroup.crisisDivision, REINFORCE_MAX_DIST, true, active, now, expires, tasked);
        } else if (reservePlay && minorEndangeredDiv != null && minorEndangeredDiv.isCombatEffective()) {
            assignReinforcement(engine, myGroup, minorEndangeredDiv, CLOSE_REINFORCE_MAX_DIST, false, active, now, expires, tasked);
        } else {
            myGroup.reinforcingDivision = null;
        }

        // 3. Exploitation: commit the reserve and send fast divisions after broken enemies
        if (fullAssault && myGroup.reserveDivision != null) {
            myGroup.reserveCommitted = true;
            myGroup.reserveDivision.isDesignatedReserve = false;
            myGroup.reserveDivision = null;
        }
        if (reservePlay && myGroup.currentPhase == BattlePhase.GENERAL_CHASE) {
            if (!myGroup.reserveCommitted && myGroup.reserveDivision != null) {
                myGroup.reserveCommitted = true;
                logOrder(engine, myGroup, "chase_reserve",
                        String.format("Committing the reserve, \"%s\", to the general chase.", myGroup.reserveDivision.displayName), now);
                myGroup.reserveDivision.isDesignatedReserve = false;
                myGroup.reserveDivision = null;
            }
            assignGeneralChase(engine, myGroup, active, visibleEnemies, now, expires, tasked);
        }

        // 4. Reserve hold (suspended during Full Assault or when frontline divisions are engaged and firepower uptime is needed)
        if (reservePlay && !fullAssault) {
            for (DivisionState d : active) {
                if (tasked.contains(d)) continue;
                // Item C: Never hold a division idle in reserve if enemies are already in range/contact with it
                // or if the fleet's engaged battle line has low firepower-in-range uptime and needs every gun on the line!
                boolean divisionAlreadyInContact = d.inRangeFirepowerScore > 0f
                        || d.incomingInRangeFirepower > 20f
                        || d.localEnemyStrength >= 6f;
                boolean frontlineNeedsGunsNow = myGroup.engagedDivisionCount >= 1
                        && (myGroup.forceRatio <= 1.15f || myGroup.fleetFirepowerUptime < 0.58f);
                if (divisionAlreadyInContact || frontlineNeedsGunsNow) {
                    continue;
                }
                boolean reserveMission = DivisionCommanderAI.resolveActiveMission(d, now) == DivisionMissionType.RESERVE;
                boolean holdAsReserve = (d == myGroup.reserveDivision)
                        || (reserveMission && myGroup.currentPhase != BattlePhase.GENERAL_CHASE
                        && myGroup.currentPhase != BattlePhase.FIGHTING_RETIREMENT);
                if (holdAsReserve) {
                    d.otcOrder.set(OtcOrderType.HOLD_RESERVE, null, null, 0f, expires, "OTC: held in reserve");
                    d.otcOrder.hasStation = true;
                    d.otcOrder.station.set(myGroup.reserveStation);
                    tasked.add(d);
                }
            }
        }

        // 5. Anchor & fix + target allocation
        Map<DivisionState, DivisionState> allocation = new HashMap<>();
        if (myGroup.isPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION)) {
            distributeFire(engine, myGroup, active, visibleEnemies, now, expires, tasked, allocation);
        }

        // 6. Pincer flank sides
        if (myGroup.isPlayEnabled(OtcPlay.DOUBLING) && auth.allowsPositioning()) {
            assignDoublingSides(engine, myGroup, active, allocation, now, expires, tasked);
        }

        // Divisions the OTC did not task this cycle go back to their own missions immediately
        for (DivisionState d : active) {
            if (!tasked.contains(d)) {
                d.otcOrder.clear();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Timed strikes
    // ---------------------------------------------------------------------------------------------

    private static void runStrikePlan(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            List<DivisionState> active,
            float now,
            float expires,
            Set<DivisionState> tasked) {

        StrikePlan plan = myGroup.strikePlan;

        if (plan != null) {
            if (!plan.committed
                    && myGroup.primaryThreatEnemyDivision != null
                    && myGroup.primaryThreatEnemyDivision.isAbleCombatEffective()
                    && plan.target != myGroup.primaryThreatEnemyDivision
                    && OtcAI.isDivisionVisibleToSide(engine, myGroup.side, myGroup.primaryThreatEnemyDivision)) {
                plan.target = myGroup.primaryThreatEnemyDivision;
            }
            boolean currentlyVis = plan.target != null && OtcAI.isDivisionVisibleToSide(engine, myGroup.side, plan.target);
            if (currentlyVis) {
                plan.lastTargetVisibleTime = now;
            }
            boolean targetValid = plan.target != null && plan.target.isAbleCombatEffective()
                    && (currentlyVis || (now - plan.lastTargetVisibleTime) <= 6.5f);
            boolean hasCarrierParticipant = false;
            for (Iterator<DivisionState> it = plan.participants.iterator(); it.hasNext(); ) {
                DivisionState p = it.next();
                if (!p.isAbleCombatEffective()
                        || p.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                        || (plan.committed && p.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE)) {
                    it.remove();
                } else if (p.archetype != null && p.archetype.isCarrierArchetype()) {
                    hasCarrierParticipant = true;
                }
            }
            float effStrikeWindow = hasCarrierParticipant ? 24f : STRIKE_WINDOW;
            if (!targetValid || plan.participants.isEmpty()) {
                if (!plan.committed && plan.target != null) {
                    logOrder(engine, myGroup, "strike_abort", String.format(
                            "Strike on \"%s\" cancelled (%s).",
                            plan.target.displayName,
                            targetValid ? "no strike divisions left" : "contact lost"), now);
                }
                endStrikePlan(myGroup, now);
                plan = null;
            } else if (plan.committed && now - plan.commitTime > effStrikeWindow) {
                endStrikePlan(myGroup, now);
                plan = null;
            } else if (!plan.committed && (now >= plan.strikeTime || allMarshalled(engine, myGroup, plan))) {
                plan.committed = true;
                plan.commitTime = now;
                plan.focusShip = pickStrikeFocusShip(engine, myGroup.side, plan.target);
                logOrder(engine, myGroup, "strike_commit", String.format(
                        "Execute: %d division(s) strike \"%s\" together%s.",
                        plan.participants.size(),
                        plan.target.displayName,
                        plan.focusShip != null ? " (focus " + plan.focusShip.getName() + ")" : ""), now);
            }
        }

        if (plan == null && (now - myGroup.lastStrikePlanEndTime) >= STRIKE_COOLDOWN) {
            plan = tryCreateStrikePlan(engine, myGroup, active, now);
            myGroup.strikePlan = plan;
        }
        if (plan == null) {
            myGroup.activeMultiDivisionSetpiece = MultiDivisionSetpiece.NONE;
            return;
        }
        myGroup.activeMultiDivisionSetpiece = plan.setpieceType != null
                ? plan.setpieceType
                : MultiDivisionSetpiece.CONVERGENCE_KILL_BOX;

        Vector2f targetPos = OtcAI.getVisibleCentroidForSide(engine, myGroup.side, plan.target);
        String spLabel = (plan.setpieceType != null && plan.setpieceType != MultiDivisionSetpiece.NONE)
                ? " [" + plan.setpieceType.shortName + "]"
                : "";
        for (DivisionState p : plan.participants) {
            Vector2f offset = plan.marshalOffsets.get(p.id);
            OtcOrderType type = plan.committed ? OtcOrderType.EXECUTE_STRIKE : OtcOrderType.MARSHAL;
            DivisionSetpieceAttack pSetpiece = plan.participantSetpieces.get(p.id);
            DivisionFormation pForm = plan.participantFormations.get(p.id);
            String why = plan.committed
                    ? "OTC: execute" + spLabel + ", strike \"" + plan.target.displayName + "\""
                    : String.format("OTC: marshal%s, strike in %.0fs", spLabel, Math.max(0f, plan.strikeTime - now));
            p.otcOrder.set(type, plan.target, null, 0f, expires, why);
            p.otcOrder.orderedSetpieceAttack = pSetpiece;
            if (myGroup.authority.allowsPositioning()) {
                if (pForm != null) {
                    p.otcOrder.orderedFormation = pForm;
                } else if (plan.committed) {
                    DivisionMissionType pMis = DivisionCommanderAI.resolveActiveMission(p, now);
                    p.otcOrder.orderedFormation = (pMis == DivisionMissionType.TOT_ALPHA_STRIKE || !p.hasMissileStrikeCapability)
                            ? DivisionFormation.FORWARD_WEDGE
                            : DivisionFormation.REVERSE_SEMICIRCLE;
                } else {
                    p.otcOrder.orderedFormation = DivisionFormation.LINE_ABREAST;
                }
            }
            if (offset != null) {
                p.otcOrder.hasStation = true;
                p.otcOrder.station.set(targetPos.x + offset.x, targetPos.y + offset.y);
            } else {
                p.otcOrder.hasStation = false;
            }
            tasked.add(p);
        }
    }

    private static StrikePlan tryCreateStrikePlan(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            List<DivisionState> active,
            float now) {

        DivisionState target = myGroup.primaryThreatEnemyDivision;
        if (target == null || !target.isCombatEffective()
                || !OtcAI.isDivisionVisibleToSide(engine, myGroup.side, target)) {
            return null;
        }
        Vector2f targetPos = OtcAI.getVisibleCentroidForSide(engine, myGroup.side, target);

        List<DivisionState> strikers = new ArrayList<>();
        for (DivisionState d : active) {
            if (d == myGroup.mainEffortDivision || d == myGroup.reserveDivision || d == myGroup.reinforcingDivision) continue;
            if (!isStrikeCandidate(d, myGroup, now)) continue;
            if (distance(d.centroid, targetPos) > STRIKE_MAX_RANGE) continue;
            strikers.add(d);
        }
        boolean mainEffortCanFix = myGroup.mainEffortDivision != null
                && myGroup.mainEffortDivision.isCombatEffective()
                && distance(myGroup.mainEffortDivision.centroid, targetPos) < 4000f;

        // Scope Item 2: If Multi-Division Setpiece Doctrine is enabled and we have >= 2 combat-ready divisions
        // within reach of the target, allow non-missile maneuver divisions to join a coordinated Multi-Division Setpiece!
        MultiDivisionSetpiece docSp = myGroup.multiDivisionSetpieceDoctrine != null
                ? myGroup.multiDivisionSetpieceDoctrine
                : MultiDivisionSetpiece.AUTO_DOCTRINAL;
        if (strikers.isEmpty()
                && docSp != MultiDivisionSetpiece.NONE
                && docSp != MultiDivisionSetpiece.AUTO_DOCTRINAL
                && active.size() >= 2
                && mainEffortCanFix) {
            for (DivisionState d : active) {
                if (d == myGroup.mainEffortDivision || d == myGroup.reserveDivision || d == myGroup.reinforcingDivision) continue;
                if (d.archetype == DivisionArchetype.SERVICE_GROUP || !d.detachedStragglerIds.isEmpty()) continue;
                if (d.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                        || d.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE) continue;
                if (d.avgFluxLevel >= 0.48f || d.effectiveHpRatio < 0.55f) continue;
                if (distance(d.centroid, targetPos) <= 3400f) {
                    strikers.add(d);
                }
            }
        }
        if (strikers.isEmpty() || (strikers.size() < 2 && !mainEffortCanFix)) {
            return null;
        }

        MultiDivisionSetpiece chosenSetpiece = docSp;
        if (chosenSetpiece == MultiDivisionSetpiece.AUTO_DOCTRINAL) {
            boolean anyCarrierStriker = false;
            int flankCapable = 0;
            for (DivisionState s : strikers) {
                if (s.archetype != null && s.archetype.isCarrierArchetype()) anyCarrierStriker = true;
                if (s.isFastFlanker || s.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION) flankCapable++;
            }
            if (myGroup.useConvergence || anyCarrierStriker) {
                chosenSetpiece = MultiDivisionSetpiece.CONVERGENCE_KILL_BOX;
            } else if (flankCapable >= 2) {
                chosenSetpiece = MultiDivisionSetpiece.CANNAAE_DOUBLE_ENVELOPMENT;
            } else if (mainEffortCanFix && flankCapable >= 1) {
                chosenSetpiece = MultiDivisionSetpiece.HAMMER_AND_ANVIL;
            } else if (target.isStrongpoint) {
                chosenSetpiece = MultiDivisionSetpiece.ECHELONED_WAVE_BREACH;
            } else {
                chosenSetpiece = MultiDivisionSetpiece.CROSSING_THE_T_COLUMN;
            }
        }

        StrikePlan plan = new StrikePlan();
        plan.target = target;
        plan.setpieceType = chosenSetpiece;
        plan.createdTime = now;
        plan.lastTargetVisibleTime = now;
        float maxEta = 0f;

        // Compute front axis from target toward our fleet centroid
        float fdx = myGroup.fleetCentroid.x - targetPos.x;
        float fdy = myGroup.fleetCentroid.y - targetPos.y;
        float flen = (float) Math.sqrt(fdx * fdx + fdy * fdy);
        if (flen < 1f) {
            fdx = 0f;
            fdy = (myGroup.side == 0) ? -1f : 1f;
            flen = 1f;
        }
        fdx /= flen;
        fdy /= flen;
        float fperpX = -fdy;
        float fperpY = fdx;

        for (int idx = 0; idx < strikers.size(); idx++) {
            DivisionState d = strikers.get(idx);
            float dx = d.centroid.x - targetPos.x;
            float dy = d.centroid.y - targetPos.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1f) {
                dx = fdx;
                dy = fdy;
                len = 1f;
            }
            float marshalDist = d.archetype.isCarrierArchetype()
                    ? Math.max(2000f, d.standoffRange)
                    : (DivisionCommanderAI.resolveActiveMission(d, now) == DivisionMissionType.TOT_ALPHA_STRIKE
                    ? Math.min(1150f, Math.max(800f, d.avgEngageRange * 1.05f))
                    : Math.max(850f, Math.min(1250f, d.avgEngageRange > 100f ? d.avgEngageRange * 1.05f : d.standoffRange)));

            DivisionSetpieceAttack spAtk = DivisionSetpieceAttack.WALL_SALVO_PASS;
            DivisionFormation spForm = DivisionFormation.LINE_ABREAST;
            Vector2f offset;

            if (d.archetype.isCarrierArchetype()) {
                spAtk = DivisionSetpieceAttack.NONE;
                spForm = d.doctrinalFormation != null ? d.doctrinalFormation : DivisionFormation.PROTECTIVE_CIRCLE;
                offset = new Vector2f(dx / len * marshalDist, dy / len * marshalDist);
            } else {
                switch (chosenSetpiece) {
                    case HAMMER_AND_ANVIL: {
                        boolean missileWing = d.hasMissileStrikeCapability && d.usableStrikeAmmoCount > 0;
                        spAtk = missileWing
                                ? DivisionSetpieceAttack.WALL_SALVO_PASS
                                : (d.getAliveMembers().size() >= 3 ? DivisionSetpieceAttack.HORNS_OF_THE_BULL : DivisionSetpieceAttack.LANCE_CHARGE);
                        spForm = missileWing
                                ? DivisionFormation.LINE_ABREAST
                                : DivisionFormation.FORWARD_WEDGE;
                        float side = ((d.centroid.x - targetPos.x) * fperpX + (d.centroid.y - targetPos.y) * fperpY) >= 0f ? 1f : -1f;
                        float vx = fdx * 0.86f + fperpX * side * 0.50f;
                        float vy = fdy * 0.86f + fperpY * side * 0.50f;
                        offset = new Vector2f(vx * marshalDist, vy * marshalDist);
                        break;
                    }
                    case CANNAAE_DOUBLE_ENVELOPMENT: {
                        float side = (idx % 2 == 0) ? 1f : -1f;
                        spAtk = (d.getAliveMembers().size() >= 3)
                                ? DivisionSetpieceAttack.HORNS_OF_THE_BULL
                                : DivisionSetpieceAttack.ECHELON_STRIKE;
                        spForm = (spAtk == DivisionSetpieceAttack.HORNS_OF_THE_BULL)
                                ? DivisionFormation.FORWARD_SEMICIRCLE
                                : (side > 0f ? DivisionFormation.ECHELON_LEFT : DivisionFormation.ECHELON_RIGHT);
                        float vx = fdx * 0.45f + fperpX * side * 0.89f;
                        float vy = fdy * 0.45f + fperpY * side * 0.89f;
                        offset = new Vector2f(vx * marshalDist, vy * marshalDist);
                        break;
                    }
                    case CROSSING_THE_T_COLUMN: {
                        spAtk = DivisionSetpieceAttack.BROADSIDE_ROLL;
                        spForm = DivisionFormation.LINE_AHEAD;
                        float side = (idx % 2 == 0) ? 1f : -1f;
                        float vx = fdx * 0.85f + fperpX * side * 0.52f;
                        float vy = fdy * 0.85f + fperpY * side * 0.52f;
                        offset = new Vector2f(vx * marshalDist, vy * marshalDist);
                        break;
                    }
                    case ECHELONED_WAVE_BREACH: {
                        spAtk = DivisionSetpieceAttack.LANCE_CHARGE;
                        spForm = DivisionFormation.FORWARD_WEDGE;
                        float waveDist = marshalDist + idx * 360f;
                        offset = new Vector2f(dx / len * waveDist, dy / len * waveDist);
                        break;
                    }
                    case CONVERGENCE_KILL_BOX:
                    default: {
                        float side = (idx % 2 == 0) ? 1f : -1f;
                        spAtk = d.hasMissileStrikeCapability
                                ? DivisionSetpieceAttack.WALL_SALVO_PASS
                                : DivisionSetpieceAttack.HORNS_OF_THE_BULL;
                        spForm = d.hasMissileStrikeCapability
                                ? DivisionFormation.LINE_ABREAST
                                : DivisionFormation.LINE_ABREAST;
                        float vx = fdx * 0.93f + fperpX * side * 0.36f;
                        float vy = fdy * 0.93f + fperpY * side * 0.36f;
                        offset = new Vector2f(vx * marshalDist, vy * marshalDist);
                        break;
                    }
                }
            }

            plan.marshalOffsets.put(d.id, offset);
            plan.participantSetpieces.put(d.id, spAtk);
            plan.participantFormations.put(d.id, spForm);
            plan.participants.add(d);

            float travel = Math.max(0f, distance(d.centroid, new Vector2f(targetPos.x + offset.x, targetPos.y + offset.y)) - 250f);
            float effectiveSpd = Math.max(45f, d.weightedTimeSpeed > 1f
                    ? (d.weightedTimeSpeed * Math.max(0.45f, d.timeFreedomFactor))
                    : (d.avgMaxSpeed * 0.8f));
            float eta = travel / effectiveSpd;
            maxEta = Math.max(maxEta, eta);
        }
        boolean anyParticipantInCombatRange = false;
        for (DivisionState p : plan.participants) {
            if (!p.archetype.isCarrierArchetype()
                    && (p.inRangeFirepowerScore > 0f
                    || p.incomingInRangeFirepower > 25f
                    || distance(p.centroid, targetPos) <= Math.max(1050f, p.avgEngageRange * 1.08f))) {
                anyParticipantInCombatRange = true;
                break;
            }
        }
        plan.strikeTime = now + Math.max(2.0f, Math.min(8.0f, maxEta * 0.65f));
        if (maxEta <= 3.5f || anyParticipantInCombatRange) {
            plan.committed = true;
            plan.commitTime = now;
            plan.focusShip = pickStrikeFocusShip(engine, myGroup.side, plan.target);
        }

        logOrder(engine, myGroup, "strike_plan", String.format(
                "Setpiece [%s]: %d division%s to marshal against \"%s\", strike in %.0fs%s.",
                chosenSetpiece.displayName,
                strikers.size(),
                strikers.size() == 1 ? "" : "s",
                target.displayName,
                plan.strikeTime - now,
                mainEffortCanFix ? " (\"" + myGroup.mainEffortDivision.displayName + "\" fixes as anvil)" : ""), now);
        return plan;
    }

    private static boolean isStrikeCandidate(DivisionState d, BattleGroupState myGroup, float now) {
        if (d.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                || d.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE) {
            return false;
        }
        DivisionMissionType mission = DivisionCommanderAI.resolveActiveMission(d, now);
        if (d.archetype.isCarrierArchetype()) {
            if (d.avgFluxLevel >= 0.60f) return false;
            if (mission == DivisionMissionType.ALPHA_STRIKE) return true;
            return myGroup.authority == OtcAuthority.POSITIVE_CONTROL
                    && mission != DivisionMissionType.COMBAT_SPACE_PATROL
                    && mission != DivisionMissionType.SPACE_SUPERIORITY
                    && mission != DivisionMissionType.RESERVE;
        }
        if (mission == DivisionMissionType.TOT_ALPHA_STRIKE) {
            return d.avgFluxLevel < 0.48f && (now - d.lastBreakOffTimestamp) >= 12f;
        }
        return d.hasMissileStrikeCapability
                && d.usableStrikeAmmoCount > 0
                && d.avgFluxLevel < 0.55f
                && (now - d.lastBreakOffTimestamp) >= 12f;
    }

    private static boolean allMarshalled(CombatEngineAPI engine, BattleGroupState myGroup, StrikePlan plan) {
        Vector2f targetPos = OtcAI.getVisibleCentroidForSide(engine, myGroup.side, plan.target);
        for (DivisionState p : plan.participants) {
            // Item C: If any non-carrier strike division is already exchanging fire or within gun range,
            // do not keep the strike group waiting in MARSHAL with 0% gun uptime — commit immediately!
            if (!p.archetype.isCarrierArchetype()
                    && (p.inRangeFirepowerScore > 0f
                    || p.incomingInRangeFirepower > 25f
                    || distance(p.centroid, targetPos) <= Math.max(1050f, p.avgEngageRange * 1.08f))) {
                return true;
            }
            Vector2f offset = plan.marshalOffsets.get(p.id);
            if (offset == null) continue;
            float marshalDist = offset.length();
            if (distance(p.centroid, targetPos) > marshalDist + 350f) {
                return false;
            }
        }
        return true;
    }

    private static ShipAPI pickStrikeFocusShip(CombatEngineAPI engine, int side, DivisionState target) {
        ShipAPI best = null;
        float bestScore = -Float.MAX_VALUE;
        Vector2f refPt = target.centroid;
        for (ShipAPI s : target.getAliveMembers()) {
            if (!OtcAI.isShipVisibleToSide(engine, side, s)) continue;
            boolean ovl = s.getFluxTracker() != null && s.getFluxTracker().isOverloadedOrVenting();
            float dCenter = (refPt != null) ? distance(refPt, s.getLocation()) : 0f;
            float score = ShipCapabilityProfile.resolveShipFpStrength(s) * 5f
                    - dCenter * 0.25f
                    + (ovl ? 680f : 0f)
                    + s.getFluxLevel() * 480f
                    + (1f - s.getHullLevel()) * 420f;
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private static void endStrikePlan(BattleGroupState myGroup, float now) {
        if (myGroup.strikePlan != null) {
            for (DivisionState p : myGroup.strikePlan.participants) {
                if (p.otcOrder.type == OtcOrderType.MARSHAL || p.otcOrder.type == OtcOrderType.EXECUTE_STRIKE) {
                    p.otcOrder.clear();
                }
            }
        }
        myGroup.strikePlan = null;
        myGroup.lastStrikePlanEndTime = now;
    }

    /** True while a committed strike is running against the given enemy division. */
    public static boolean isStrikeExecutingOn(BattleGroupState battleGroup, DivisionState enemyDiv) {
        return battleGroup != null && battleGroup.strikePlan != null && battleGroup.strikePlan.committed
                && enemyDiv != null && battleGroup.strikePlan.target == enemyDiv;
    }

    // ---------------------------------------------------------------------------------------------
    // Reserve, relief & exploitation
    // ---------------------------------------------------------------------------------------------

    private static void assignReinforcement(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            DivisionState crisis,
            float maxDist,
            boolean majorCrisis,
            List<DivisionState> active,
            float now,
            float expires,
            Set<DivisionState> tasked) {

        if (crisis == null || !crisis.isCombatEffective() || crisis.localForceRatio >= 0.85f) {
            myGroup.reinforcingDivision = null;
            return;
        }
        boolean linePlay = myGroup.isPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION);
        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;

        for (DivisionState d : active) {
            if (d == crisis || tasked.contains(d)) continue;
            if (d.archetype.isCarrierArchetype() || d.archetype == DivisionArchetype.SERVICE_GROUP) continue;
            if (linePlay && d == myGroup.mainEffortDivision) continue;
            if (d.avgFluxLevel >= 0.55f) continue;
            boolean selfEngaged = d.localEnemyStrength >= 12f && d.localForceRatio < 1.0f;
            if (selfEngaged) continue;
            float dist = distance(d.centroid, crisis.centroid);
            if (dist > maxDist) continue;

            // Do not send a small Destroyer/Frigate division to commit suicide inside a deep multi-division
            // U-shaped crossfire pocket where combined friendly strength would still be heavily overmatched (< 0.55x).
            boolean isLightReinforcer = d.totalFpStrength <= 26f
                    && (d.archetype == DivisionArchetype.DESTROYER_DIVISION
                    || d.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION
                    || d.archetype == DivisionArchetype.FRIGATE_DIVISION
                    || d.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION);
            if (isLightReinforcer && crisis.localEnemyStrength > 32f) {
                float combinedRatio = (crisis.localFriendlyStrength + d.totalFpStrength) / Math.max(1f, crisis.localEnemyStrength);
                if (combinedRatio < 0.52f || (crisis.crossfireCordonIndex >= 0.45f && combinedRatio < 0.68f)) {
                    continue;
                }
            }

            boolean isReserve = (d == myGroup.reserveDivision)
                    || DivisionCommanderAI.resolveActiveMission(d, now) == DivisionMissionType.RESERVE;
            // The reserve is kept for real crises, not for a lone frigate in trouble
            if (isReserve && !majorCrisis) continue;
            // Time freedom bonus: prefer divisions whose effectiveTimeRadius can reach the crisis without being pinned
            float timeReachBonus = (dist <= d.effectiveTimeRadius ? 260f : 0f) + d.timeFreedomFactor * 180f;
            float score = -dist * 0.15f - d.avgFluxLevel * 400f + timeReachBonus;
            if (isReserve) score += 800f;
            if (d == myGroup.reinforcingDivision) score += 400f;
            if (d.isFastFlanker) score += 150f;
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }

        DivisionState previousReinforcement = myGroup.reinforcingDivision;
        myGroup.reinforcingDivision = best;
        if (best == null) {
            if (majorCrisis) {
                logOrder(engine, myGroup, "reinforce_none:" + crisis.id, String.format(
                        "No division free to reinforce \"%s\" (local ratio %.2f).",
                        crisis.displayName, crisis.localForceRatio), now);
            }
            return;
        }

        best.otcOrder.set(OtcOrderType.REINFORCE, null, crisis, 0f, expires,
                String.format("OTC: reinforce \"%s\" (local ratio %.2f)", crisis.displayName, crisis.localForceRatio));
        tasked.add(best);

        if (best != previousReinforcement) {
            boolean fromReserve = (best == myGroup.reserveDivision);
            if (fromReserve) {
                myGroup.reserveCommitted = true;
                best.isDesignatedReserve = false;
                myGroup.reserveDivision = null;
            }
            logOrder(engine, myGroup, "relief:" + best.id + ">" + crisis.id, String.format(
                    "%s \"%s\" to %sreinforce \"%s\" (local ratio %.2f).",
                    fromReserve ? "Committing the reserve," : "Detaching",
                    best.displayName,
                    majorCrisis ? "" : "close-in ",
                    crisis.displayName, crisis.localForceRatio), now);
        }
    }

    private static void assignGeneralChase(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            List<DivisionState> active,
            List<DivisionState> visibleEnemies,
            float now,
            float expires,
            Set<DivisionState> tasked) {

        List<DivisionState> broken = new ArrayList<>();
        for (DivisionState e : visibleEnemies) {
            if (isVisiblyBroken(engine, myGroup.side, e)
                    && distance(myGroup.fleetCentroid, OtcAI.getVisibleCentroidForSide(engine, myGroup.side, e)) < CHASE_LEASH) {
                broken.add(e);
            }
        }
        if (broken.isEmpty()) return;

        int chasers = 0;
        for (DivisionState d : active) {
            if (chasers >= 2) break;
            if (tasked.contains(d) || d == myGroup.mainEffortDivision) continue;
            if (!d.isFastFlanker || d.archetype.isCarrierArchetype()) continue;
            if (d.avgFluxLevel >= 0.50f) continue;

            DivisionState nearest = null;
            float nearestDist = Float.MAX_VALUE;
            for (DivisionState e : broken) {
                float dist = distance(d.centroid, OtcAI.getVisibleCentroidForSide(engine, myGroup.side, e));
                if (d.otcOrder.type == OtcOrderType.GENERAL_CHASE && d.otcOrder.targetEnemy == e) {
                    dist -= 1000f;
                }
                if (e.archetype == DivisionArchetype.PHASE_DIVISION && d.archetype != DivisionArchetype.PHASE_DIVISION) {
                    dist += 1100f;
                }
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = e;
                }
            }
            if (nearest == null || nearestDist > 4000f) continue;

            boolean newOrder = !(d.otcOrder.type == OtcOrderType.GENERAL_CHASE && d.otcOrder.targetEnemy == nearest);
            d.otcOrder.set(OtcOrderType.GENERAL_CHASE, nearest, null, 0f, expires, "OTC: general chase on \"" + nearest.displayName + "\"");
            if (myGroup.authority.allowsPositioning()) {
                d.otcOrder.orderedFormation = DivisionFormation.NONE;
            }
            tasked.add(d);
            chasers++;
            if (newOrder) {
                logOrder(engine, myGroup, "pursue:" + d.id + ">" + nearest.id, String.format(
                        "General chase: \"%s\" to pursue \"%s\" (leash %.0fu).", d.displayName, nearest.displayName, CHASE_LEASH), now);
            }
        }
    }

    /** Broken = what we can see of it is badly damaged, flux-locked, or running away. */
    private static boolean isVisiblyBroken(CombatEngineAPI engine, int observerSide, DivisionState e) {
        int visible = 0;
        int retreating = 0;
        float hullSum = 0f;
        float fluxSum = 0f;
        for (ShipAPI s : e.members) {
            if (s == null || !s.isAlive() || s.isHulk()) continue;
            if (!OtcAI.isShipVisibleToSide(engine, observerSide, s)) continue;
            visible++;
            hullSum += s.getHullLevel();
            fluxSum += s.getFluxLevel();
            if (s.isRetreating()) retreating++;
        }
        if (visible == 0) return false;
        return (hullSum / visible) < 0.50f
                || (fluxSum / visible) > 0.78f
                || retreating * 2 >= visible;
    }

    // ---------------------------------------------------------------------------------------------
    // Anchor & fix, target allocation, pincer flanks
    // ---------------------------------------------------------------------------------------------

    private static void distributeFire(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            List<DivisionState> active,
            List<DivisionState> visibleEnemies,
            float now,
            float expires,
            Set<DivisionState> tasked,
            Map<DivisionState, DivisionState> allocationOut) {

        if (visibleEnemies.isEmpty()) return;

        Map<DivisionState, Float> assignedFp = new HashMap<>();
        Map<DivisionState, Float> neededFp = new HashMap<>();
        DivisionState primary = myGroup.primaryThreatEnemyDivision;
        if (primary != null && !visibleEnemies.contains(primary)) primary = null;
        for (DivisionState e : visibleEnemies) {
            // Concentrate overwhelming overmatch (3.35x) into the Fleet Engagement Zone (primary frontline target)
            // to achieve defeat-in-detail before splitting fire onto secondary contacts!
            float mult = (e == primary) ? 3.35f : 1.40f;
            neededFp.put(e, OtcAI.getVisibleFpForSide(engine, myGroup.side, e) * mult);
            assignedFp.put(e, 0f);
        }
        // Divisions already committed elsewhere still count toward the enemy they are engaging
        for (DivisionState d : active) {
            if (tasked.contains(d) && d.otcOrder.targetEnemy != null && assignedFp.containsKey(d.otcOrder.targetEnemy)) {
                assignedFp.put(d.otcOrder.targetEnemy, assignedFp.get(d.otcOrder.targetEnemy) + d.totalFpStrength);
            }
        }

        boolean fullAssault = OtcAI.isFullAssaultActive(engine, myGroup);

        // Main Effort fixes the primary threat (or leads the spearhead in GRAND_WEDGE_SPEARHEAD / REFUSED_FLANK_OBLIQUE)
        // When Full Assault is ordered, divisions no longer adhere to multi-division formations or standoff fixing lines.
        boolean wedgeOrOblique = !fullAssault
                && (myGroup.activeFleetWideFormation == FleetWideFormation.GRAND_WEDGE_SPEARHEAD
                || myGroup.activeFleetWideFormation == FleetWideFormation.REFUSED_FLANK_OBLIQUE)
                && active.size() >= 3;
        DivisionState main = myGroup.mainEffortDivision;
        if (!fullAssault && main != null && main.isCombatEffective() && !tasked.contains(main) && primary != null) {
            Vector2f primaryPos = OtcAI.getVisibleCentroidForSide(engine, myGroup.side, primary);
            boolean newOrder = !(main.otcOrder.type == OtcOrderType.FORM_LINE && main.otcOrder.targetEnemy == primary);
            main.otcOrder.set(OtcOrderType.FORM_LINE, primary, null, 0f, expires,
                    "OTC: Main Effort, form the line on \"" + primary.displayName + "\"");
            // Coordinated Strike Execution: the Main Effort does not simply hold the line against a collapsing
            // enemy division while its own strike divisions dive in — it presses home onto the struck target
            // so the alpha strike lands on a broken enemy instead of absorbing the enemy's main body alone!
            boolean strikeExecuting = isStrikeExecutingOn(myGroup, primary)
                    && myGroup.strikePlan != null
                    && !myGroup.strikePlan.participants.isEmpty();
            if (strikeExecuting
                    && !main.archetype.isCarrierArchetype()
                    && main.avgFluxLevel < 0.62f
                    && main.effectiveHpRatio >= 0.58f) {
                main.otcOrder.set(OtcOrderType.EXECUTE_STRIKE, primary, null, 0f, expires,
                        "OTC: Main Effort, press home on \"" + primary.displayName + "\"");
            }
            if (wedgeOrOblique && myGroup.authority != OtcAuthority.COMMAND_BY_NEGATION) {
                main.otcOrder.orderedFormation = DivisionFormation.FORWARD_WEDGE;
            } else if (myGroup.authority == OtcAuthority.POSITIVE_CONTROL
                    && (main.doctrinalFormation == null || main.doctrinalFormation == DivisionFormation.NONE)) {
                main.otcOrder.orderedFormation = DivisionFormation.LINE_ABREAST;
            }
            if (myGroup.authority.allowsPositioning()) {
                float dx = myGroup.fleetCentroid.x - primaryPos.x;
                float dy = myGroup.fleetCentroid.y - primaryPos.y;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1f) {
                    dx = 0f;
                    dy = (myGroup.side == 0) ? -1f : 1f;
                    len = 1f;
                }
                float hold = Math.max(500f, main.standoffRange * 0.9f);
                if (wedgeOrOblique && myGroup.forceRatio >= 1.12f) {
                    hold = Math.max(400f, hold * 0.78f);
                }
                // Explicit Fleet Engagement OTC adaptation: if vanguard/light divisions are in violent contact
                // (engagedDivisionCount >= 1) while the heavy Main Effort is still uncommitted (!mainEffortEngaged),
                // tighten the Main Effort's fixing station by 22% so the battleships rapidly close into gun range
                // to relieve the vanguard rather than hanging back out of range!
                if (myGroup.engagedDivisionCount >= 1
                        && !myGroup.mainEffortEngaged
                        && myGroup.forceRatio >= 0.88f) {
                    hold = Math.max(460f, hold * 0.78f);
                }
                float stX = primaryPos.x + dx / len * hold;
                float stY = primaryPos.y + dy / len * hold;
                // Item D (Disorganized Fleet OTC): When organizationRatio < 0.65 and friendly independent ships are present,
                // blend the Main Effort's fixing station toward the friendly independent battleline so our organized core
                // fights shoulder-to-shoulder with its independent swarm instead of holding a distant isolated station!
                if (myGroup.organizationRatio < 0.65f && myGroup.friendlyIndependentFp >= 15f) {
                    float indepBlend = Math.min(0.45f, (0.68f - myGroup.organizationRatio) * 0.70f);
                    stX = stX * (1f - indepBlend) + myGroup.independentCentroid.x * indepBlend;
                    stY = stY * (1f - indepBlend) + myGroup.independentCentroid.y * indepBlend;
                }
                // Item D: If our fleet is outnumbered or balled up in a protective bastion, pull the Main Effort's
                // fixing station slightly back toward the friendly map edge so our back is protected by the boundary
                // and the attacker must advance into our concentrated frontal fire!
                if (myGroup.forceRatio < 0.84f
                        || myGroup.activeFleetWideFormation == FleetWideFormation.FLEET_PROTECTIVE_BASTION) {
                    float retreatDirY = (myGroup.side == 0) ? -1f : 1f;
                    stY += retreatDirY * 140f;
                }
                main.otcOrder.hasStation = true;
                main.otcOrder.station.set(stX, stY);
            } else {
                main.otcOrder.hasStation = false;
            }
            tasked.add(main);
            allocationOut.put(main, primary);
            assignedFp.put(primary, assignedFp.get(primary) + main.totalFpStrength);
            if (newOrder) {
                logOrder(engine, myGroup, "fix:" + main.id + ">" + primary.id, String.format(
                        "Main Effort \"%s\" to form the line on \"%s\" and fix it.", main.displayName, primary.displayName), now);
            }
        }

        // Remaining divisions: economy-of-force allocation, strongest first
        List<DivisionState> pool = new ArrayList<>();
        for (DivisionState d : active) {
            if (!tasked.contains(d)) pool.add(d);
        }
        Collections.sort(pool, new Comparator<DivisionState>() {
            @Override
            public int compare(DivisionState a, DivisionState b) {
                return Float.compare(b.totalFpStrength, a.totalFpStrength);
            }
        });

        for (DivisionState d : pool) {
            DivisionState best = null;
            float bestScore = -Float.MAX_VALUE;
            DivisionState previous = (d.otcOrder.type == OtcOrderType.ENGAGE_ASSIGNED || d.otcOrder.type == OtcOrderType.FLANK)
                    ? d.otcOrder.targetEnemy : null;
            for (DivisionState e : visibleEnemies) {
                Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, myGroup.side, e);
                float need = Math.max(4f, neededFp.get(e));
                float have = assignedFp.get(e);
                float dist = distance(d.centroid, ePos);
                float visFp = OtcAI.getVisibleFpForSide(engine, myGroup.side, e);
                float score = -dist * (dist > 1500f ? 0.62f : 0.48f);
                if (e == previous && dist <= 2200f) score += 260f;
                if (e == primary) score += (dist <= 2400f) ? 720f : 460f;
                if (e.archetype == DivisionArchetype.SERVICE_GROUP) score -= 450f;
                // Item D: Align fire distribution with friendly independent ships when organizationRatio < 0.75
                if (myGroup.friendlyIndependentFp >= 12f && myGroup.organizationRatio < 0.75f) {
                    float dIndep = distance(myGroup.independentProjectedCentroid, ePos);
                    if (dIndep <= 1750f) {
                        score += (1750f - dIndep) * 0.25f * (1f - myGroup.organizationRatio);
                    }
                }
                // Item F Defensive Counter: avoid focusing on an enemy division suspected of feigning retreat
                if (e.enemyFeignedRetreatSuspicion >= 0.60f && visibleEnemies.size() > 1) {
                    score -= 280f;
                }

                // Defeat-in-Detail: reward isolated & collapsing targets so our heavy divisions focus them down
                if (e.divisionNumber < 90 && e.stationKeepingRatio > 1.45f) {
                    score += 220f;
                }
                if (visFp >= 18f) {
                    score += e.avgFluxLevel * 280f + (1.0f - e.avgHullLevel) * 260f;
                }

                // Prevent heavy battleline divisions (Battleships/Cruisers) from being baited away by tiny light screens
                if (d.totalFpStrength >= 32f && visFp < 20f && e != primary) {
                    score -= 420f;
                } else if (d.totalFpStrength >= 32f && visFp >= 28f) {
                    score += Math.min(280f, visFp * 3.5f);
                }

                if (myGroup.useConvergence) {
                    // Convergence OTC (AI / Threat / Abyssal or Alpha Core flagship):
                    // Non-linear synthesis of Time reachability, Space seam isolation, and orthogonal Firepower
                    score += BattlefieldFirepowerEvaluator.computeConvergenceTargetScore(engine, d, e, myGroup) * 0.48f;
                } else {
                    // Human OTC: weights Time reachability and Space Weak Points vs Strongpoints
                    if (dist <= d.effectiveTimeRadius + d.avgEngageRange) {
                        score += 140f * d.timeFreedomFactor;
                    }
                    if (e.isWeakPoint) score += 180f;
                    else if (e.isStrongpoint) score -= 90f;
                }

                if (have < need) {
                    score += 250f;
                } else {
                    float overAllocPenalty = (visibleEnemies.size() >= 3) ? 160f : 320f;
                    score -= overAllocPenalty * (have / need - 1f);
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = e;
                }
            }
            if (best == null) continue;

            d.otcOrder.set(OtcOrderType.ENGAGE_ASSIGNED, best, null, 0f, expires, "OTC: fire distribution, engage \"" + best.displayName + "\"");
            d.otcOrder.hasStation = false;
            if (wedgeOrOblique
                    && myGroup.authority.allowsPositioning()
                    && d.hasFleetFormationOffset
                    && main != null
                    && main.hasFleetFormationOffset
                    && !d.isFastFlanker
                    && !d.archetype.isCarrierArchetype()
                    && d.archetype != DivisionArchetype.SERVICE_GROUP
                    && DivisionCommanderAI.resolveActiveMission(d, now) != DivisionMissionType.ENVELOPMENT
                    && DivisionCommanderAI.resolveActiveMission(d, now) != DivisionMissionType.SCOUTING) {
                Vector2f bPos = OtcAI.getVisibleCentroidForSide(engine, myGroup.side, best);
                float distToTarget = distance(d.centroid, bPos);
                if (d.fleetFormationWingSide < 0) {
                    d.otcOrder.orderedFormation = DivisionFormation.ECHELON_LEFT;
                } else if (d.fleetFormationWingSide > 0) {
                    d.otcOrder.orderedFormation = DivisionFormation.ECHELON_RIGHT;
                }
                if (best == primary
                        && myGroup.engagedDivisionCount == 0
                        && distToTarget > Math.max(1850f, d.standoffRange * 1.85f)) {
                    float anchorX = main.otcOrder.hasStation ? main.otcOrder.station.x : main.centroid.x;
                    float anchorY = main.otcOrder.hasStation ? main.otcOrder.station.y : main.centroid.y;
                    float relX = d.fleetFormationOffset.x - main.fleetFormationOffset.x;
                    float relY = d.fleetFormationOffset.y - main.fleetFormationOffset.y;
                    d.otcOrder.hasStation = true;
                    d.otcOrder.station.set(anchorX + relX, anchorY + relY);
                }
            }
            tasked.add(d);
            allocationOut.put(d, best);
            float weight = d.archetype.isCarrierArchetype() ? 0.5f : 1f;
            assignedFp.put(best, assignedFp.get(best) + d.totalFpStrength * weight);
        }
    }

    private static void assignDoublingSides(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            List<DivisionState> active,
            Map<DivisionState, DivisionState> allocation,
            float now,
            float expires,
            Set<DivisionState> tasked) {

        DivisionState primary = myGroup.primaryThreatEnemyDivision;
        boolean mainIsFixing = myGroup.mainEffortDivision != null
                && myGroup.mainEffortDivision.otcOrder.type == OtcOrderType.FORM_LINE;
        boolean central = myGroup.authority == OtcAuthority.POSITIVE_CONTROL;

        // Flankers grouped by the enemy division they envelop
        Map<DivisionState, List<DivisionState>> flankersByTarget = new HashMap<>();
        int centralExtraFlankers = 0;
        for (DivisionState d : active) {
            OtcOrderType t = d.otcOrder.type;
            boolean free = !tasked.contains(d) || t == OtcOrderType.ENGAGE_ASSIGNED;
            if (!free || d == myGroup.mainEffortDivision || d.archetype.isCarrierArchetype()) continue;

            boolean flankMission = DivisionCommanderAI.resolveActiveMission(d, now) == DivisionMissionType.ENVELOPMENT;
            boolean centralFlanker = central && mainIsFixing && d.isFastFlanker && d.avgFluxLevel < 0.55f
                    && centralExtraFlankers < 2;
            if (!flankMission && !centralFlanker) continue;
            if (!flankMission) centralExtraFlankers++;

            DivisionState target = allocation.get(d);
            if (target == null) target = primary;
            // Keep existing flank target sticky unless it is no longer combat-effective or a new target is >1400u closer
            if (d.otcOrder.type == OtcOrderType.FLANK
                    && d.otcOrder.targetEnemy != null
                    && d.otcOrder.targetEnemy.isCombatEffective()
                    && OtcAI.isDivisionVisibleToSide(engine, myGroup.side, d.otcOrder.targetEnemy)) {
                if (target == null || distance(d.centroid, d.otcOrder.targetEnemy.centroid) <= distance(d.centroid, target.centroid) + 1400f) {
                    target = d.otcOrder.targetEnemy;
                }
            }
            if (target == null || !target.isCombatEffective()) continue;
            List<DivisionState> list = flankersByTarget.get(target);
            if (list == null) {
                list = new ArrayList<>();
                flankersByTarget.put(target, list);
            }
            list.add(d);
        }

        for (Map.Entry<DivisionState, List<DivisionState>> entry : flankersByTarget.entrySet()) {
            DivisionState target = entry.getKey();
            List<DivisionState> flankers = entry.getValue();
            Vector2f tPos = OtcAI.getVisibleCentroidForSide(engine, myGroup.side, target);
            float ax = tPos.x - myGroup.fleetCentroid.x;
            float ay = tPos.y - myGroup.fleetCentroid.y;
            float aLen = (float) Math.sqrt(ax * ax + ay * ay);
            if (aLen < 1f) {
                ax = 0f;
                ay = (myGroup.side == 0) ? 1f : -1f;
                aLen = 1f;
            }
            float perpX = -ay / aLen;
            float perpY = ax / aLen;

            Collections.sort(flankers, new Comparator<DivisionState>() {
                @Override
                public int compare(DivisionState a, DivisionState b) {
                    return Float.compare(b.totalFpStrength, a.totalFpStrength);
                }
            });

            // Count already-committed flank sides first so existing flankers don't flip port <-> starboard
            int left = 0;
            int right = 0;
            for (DivisionState d : flankers) {
                if (d.otcOrder.type == OtcOrderType.FLANK && d.otcOrder.targetEnemy == target && d.otcOrder.flankSide != 0f) {
                    if (d.otcOrder.flankSide > 0f) right++;
                    else left++;
                }
            }

            // Item D: Check map-edge clearance on port (+perp) vs starboard (-perp) of the target division.
            // If one flank of the enemy division is pinched against a map edge (< 1050u clearance), avoid sending
            // flankers into the pinched wall gap; instead swing on the open flank to pin the enemy against the map edge!
            float portClearance = BattlefieldFirepowerEvaluator.computeDirectionalBoundaryClearance(
                    engine, tPos, perpX, perpY, 200f);
            float starboardClearance = BattlefieldFirepowerEvaluator.computeDirectionalBoundaryClearance(
                    engine, tPos, -perpX, -perpY, 200f);
            boolean portPinchedByWall = portClearance < 1050f && starboardClearance > portClearance + 450f;
            boolean starboardPinchedByWall = starboardClearance < 1050f && portClearance > starboardClearance + 450f;

            for (DivisionState d : flankers) {
                float previous = (d.otcOrder.type == OtcOrderType.FLANK && d.otcOrder.targetEnemy == target) ? d.otcOrder.flankSide : 0f;
                float side;
                if (portPinchedByWall) {
                    side = -1f; // port is pinched against map wall -> envelop from starboard to pin enemy into port wall
                } else if (starboardPinchedByWall) {
                    side = 1f;  // starboard is pinched against map wall -> envelop from port to pin enemy into starboard wall
                } else if (previous != 0f) {
                    side = previous;
                } else {
                    float natural = Math.signum((d.centroid.x - tPos.x) * perpX + (d.centroid.y - tPos.y) * perpY);
                    if (natural == 0f) natural = 1f;
                    side = natural;
                    // Balance newly assigned flankers across port and starboard
                    if (side > 0f && right > left) side = -1f;
                    else if (side < 0f && left > right) side = 1f;
                    if (side > 0f) right++;
                    else left++;
                }

                boolean newOrder = !(d.otcOrder.type == OtcOrderType.FLANK && d.otcOrder.targetEnemy == target
                        && Math.signum(d.otcOrder.flankSide) == Math.signum(side));
                d.otcOrder.set(OtcOrderType.FLANK, target, null, side, expires,
                        String.format("OTC: %s flank of \"%s\"", side > 0f ? "port" : "starboard", target.displayName));
                if (myGroup.authority.allowsPositioning()) {
                    if (myGroup.useConvergence && d.getAliveMembers().size() >= 3) {
                        d.otcOrder.orderedFormation = DivisionFormation.REVERSE_SEMICIRCLE;
                    } else if (myGroup.authority == OtcAuthority.POSITIVE_CONTROL
                            || d.doctrinalFormation == DivisionFormation.LINE_ABREAST
                            || d.doctrinalFormation == DivisionFormation.FORWARD_SEMICIRCLE) {
                        d.otcOrder.orderedFormation = (side > 0f)
                                ? DivisionFormation.ECHELON_LEFT
                                : DivisionFormation.ECHELON_RIGHT;
                    }
                }
                d.otcOrder.hasStation = false;
                tasked.add(d);
                if (newOrder && flankers.size() >= 2) {
                    logOrder(engine, myGroup, "flank:" + d.id, String.format(
                            "Doubling: \"%s\" takes the %s flank of \"%s\".",
                            d.displayName, side > 0f ? "port" : "starboard", target.displayName), now);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Item E (Multi-Division / Fleet-Wide "Trade Space for Time" / "Tactical Retreat"):
     * In highly organized fleets (organizationRatio >= 0.75 or Convergence), when multiple frontline divisions are
     * absorbing heavy enemy firepower at an unfavorable exchange ratio (or recovering from a salvo), orders a
     * synchronized centroid offset (dx, dy) so the battleline steps back in unison while keeping weapons bearing.
     */
    private static void evaluateAndAssignFleetTradeSpaceForTime(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            List<DivisionState> active,
            List<DivisionState> visibleEnemies,
            float now,
            float expires,
            Set<DivisionState> tasked) {
        if (myGroup == null || visibleEnemies.isEmpty() || active.size() < 2) return;
        boolean highOrg = myGroup.organizationRatio >= 0.75f || myGroup.useConvergence;
        if (!highOrg) return;

        DivisionState primary = myGroup.primaryThreatEnemyDivision;
        if (primary == null || !primary.isCombatEffective()) return;

        int pressuredCount = 0;
        float sumFlux = 0f;
        float sumInRangeOut = 0f;
        float sumInRangeIn = 0f;
        List<DivisionState> candidates = new ArrayList<>();
        for (DivisionState d : active) {
            if (tasked.contains(d) || d.archetype.isCarrierArchetype() || d.archetype == DivisionArchetype.SERVICE_GROUP) {
                continue;
            }
            if (d.incomingInRangeFirepower > 35f || d.localEnemyStrength >= 14f) {
                sumFlux += d.avgFluxLevel;
                sumInRangeOut += d.inRangeFirepowerScore;
                sumInRangeIn += d.incomingInRangeFirepower;
                boolean reliefHolding = d.inDoubleLineRelief && d.avgFluxLevel < 0.60f && d.crossfireCordonIndex < 0.36f;
                if (!reliefHolding
                        && ((d.avgFluxLevel >= 0.52f && d.firepowerExchangeRatio < 0.78f && d.localForceRatio < 0.82f)
                        || (d.avgFluxLevel >= 0.56f && d.localForceRatio < 0.78f)
                        || (d.crossfireCordonIndex >= 0.38f && d.avgFluxLevel >= 0.44f))) {
                    pressuredCount++;
                }
                candidates.add(d);
            }
        }

        boolean stayInTradeSpace = myGroup.fleetTradeSpaceActive
                && now < myGroup.fleetTradeSpaceUntil
                && pressuredCount >= 1
                && !candidates.isEmpty()
                && (sumFlux / candidates.size()) >= 0.42f;
        boolean triggerFleetTradeSpace = pressuredCount >= 2
                && sumInRangeIn > sumInRangeOut * 1.18f
                && (now - myGroup.fleetTradeSpaceUntil) >= 10.0f;

        if (!stayInTradeSpace && !triggerFleetTradeSpace) {
            myGroup.fleetTradeSpaceActive = false;
            return;
        }
        if (triggerFleetTradeSpace && !myGroup.fleetTradeSpaceActive) {
            myGroup.fleetTradeSpaceActive = true;
            myGroup.fleetTradeSpaceUntil = now + 5.5f;
        }

        DivisionState refDiv = (myGroup.mainEffortDivision != null && myGroup.mainEffortDivision.isCombatEffective())
                ? myGroup.mainEffortDivision
                : candidates.get(0);
        BattlefieldFirepowerEvaluator.computeTradeSpaceForTimeOffset(
                engine, refDiv, primary, myGroup, myGroup.fleetSpaceOffsetVector);

        for (DivisionState d : candidates) {
            // Healthy divisions (or divisions whose front rank is still holding via Double-Line Rank Relief)
            // stay on the line to cover the pressured divisions rather than retreating alongside them!
            if (d.avgFluxLevel < 0.48f || (d.inDoubleLineRelief && d.avgFluxLevel < 0.58f && d.crossfireCordonIndex < 0.36f)) {
                continue;
            }
            d.spaceOffsetVector.set(myGroup.fleetSpaceOffsetVector);
            d.otcOrder.set(OtcOrderType.TACTICAL_RETREAT, primary, null, 0f, expires,
                    "OTC: Trade Space for Time against \"" + primary.displayName + "\"");
            d.otcOrder.hasStation = true;
            d.otcOrder.station.set(
                    d.centroid.x + myGroup.fleetSpaceOffsetVector.x,
                    d.centroid.y + myGroup.fleetSpaceOffsetVector.y);
            tasked.add(d);
        }
        if (triggerFleetTradeSpace) {
            logOrder(engine, myGroup, "trade_space:" + primary.id, String.format(
                    "Tactical Retreat [Trade Space for Time]: %d divisions offsetting centroid by (%.0f, %.0f) against \"%s\".",
                    candidates.size(), myGroup.fleetSpaceOffsetVector.x, myGroup.fleetSpaceOffsetVector.y, primary.displayName), now);
        }
    }

    /**
     * Item F (Multi-Division / Fleet-Wide "Feigned Retreat"):
     * An offensive version of Tactical Retreat that deliberately baits an enemy division into overextending
     * along a retreat corridor, then springs a synchronized multi-division alpha strike!
     * Only doable fleet-wide in highly organized fleets (organizationRatio >= 0.80 or Convergence).
     */
    private static void evaluateAndAssignFleetFeignedRetreat(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            List<DivisionState> active,
            List<DivisionState> visibleEnemies,
            float now,
            float expires,
            Set<DivisionState> tasked) {
        if (myGroup == null || visibleEnemies.isEmpty() || active.size() < 2) return;
        boolean highOrg = myGroup.organizationRatio >= 0.80f || myGroup.useConvergence;
        if (!highOrg) return;

        // If a Fleet Feigned Retreat is already active, advance its 2-stage state machine (Stage 1 Lure -> Stage 2 Alpha Trap)
        if (myGroup.fleetFeignedRetreatActive) {
            DivisionState bait = myGroup.feignedRetreatBaitDivision;
            DivisionState target = myGroup.feignedRetreatTargetEnemy;
            float elapsed = now - myGroup.fleetFeignedRetreatStartTime;
            if (bait == null || !bait.isAbleCombatEffective()
                    || target == null || !target.isCombatEffective()
                    || !OtcAI.isDivisionVisibleToSide(engine, myGroup.side, target)
                    || bait.avgFluxLevel >= 0.68f
                    || elapsed > 14.0f) {
                myGroup.fleetFeignedRetreatActive = false;
                myGroup.fleetFeignedRetreatStage = 0;
                myGroup.lastFleetFeignedRetreatEndTime = now;
                return;
            }

            Vector2f tPos = OtcAI.getVisibleCentroidForSide(engine, myGroup.side, target);
            float distBaitToTarget = distance(bait.centroid, tPos);
            // Transition from Stage 1 (Lure) to Stage 2 (Spring Alpha Strike) when enemy closes into the kill zone or after 5.5s
            if (myGroup.fleetFeignedRetreatStage == 1
                    && (distBaitToTarget <= Math.max(980f, bait.avgEngageRange * 0.92f)
                    || (elapsed >= 4.0f && target.closingSpeedToEnemy > 25f)
                    || elapsed >= 6.0f)) {
                myGroup.fleetFeignedRetreatStage = 2;
                logOrder(engine, myGroup, "feign_spring:" + target.id, String.format(
                        "Feigned Retreat [SPRING TRAP]: \"%s\" & wing divisions unleash alpha strike on overextended \"%s\"!",
                        bait.displayName, target.displayName), now);
            }

            if (myGroup.fleetFeignedRetreatStage == 1) {
                // Stage 1: Bait division lures backward while wing/hammer divisions converge on the flanks
                DivisionState hammer = null;
                for (DivisionState d : active) {
                    if (d != bait && !d.archetype.isCarrierArchetype() && d.avgFluxLevel < 0.52f) {
                        hammer = d;
                        break;
                    }
                }
                BattlefieldFirepowerEvaluator.computeFeignedRetreatBaitOffset(
                        engine, bait, target, hammer, myGroup, bait.spaceOffsetVector);
                bait.feignedRetreatStage = 1;
                bait.otcOrder.set(OtcOrderType.FEIGN_RETREAT_TRAP, target, null, 0f, expires,
                        "OTC: Feigned Retreat (bait lure) against \"" + target.displayName + "\"");
                bait.otcOrder.hasStation = true;
                bait.otcOrder.station.set(
                        bait.centroid.x + bait.spaceOffsetVector.x,
                        bait.centroid.y + bait.spaceOffsetVector.y);
                tasked.add(bait);
            } else {
                // Stage 2: Spring the trap — all nearby ready divisions execute a synchronized alpha strike on target!
                for (DivisionState d : active) {
                    if (tasked.contains(d) || d.archetype.isCarrierArchetype() || d.avgFluxLevel >= 0.65f) continue;
                    if (!d.detachedStragglerIds.isEmpty()) continue;
                    if (distance(d.centroid, tPos) <= 2250f) {
                        d.feignedRetreatStage = 2;
                        d.otcOrder.set(OtcOrderType.EXECUTE_STRIKE, target, null, 0f, expires,
                                "OTC: Feigned Retreat trap sprung on \"" + target.displayName + "\"");
                        d.otcOrder.orderedSetpieceAttack = DivisionSetpieceAttack.HORNS_OF_THE_BULL;
                        tasked.add(d);
                    }
                }
            }
            return;
        }

        // Check cooldown and conditions to initiate a new Fleet Feigned Retreat
        if ((now - myGroup.lastFleetFeignedRetreatEndTime) < 28.0f) return;
        if (myGroup.forceRatio < 0.78f || myGroup.forceRatio > 1.55f) return;

        DivisionState target = myGroup.primaryThreatEnemyDivision;
        if (target == null || !target.isAbleCombatEffective() || target.archetype.isCarrierArchetype()) return;
        if (target.enemyFeignedRetreatSuspicion >= 0.50f || target.avgFluxLevel >= 0.55f) return;

        Vector2f tPos = OtcAI.getVisibleCentroidForSide(engine, myGroup.side, target);
        DivisionState baitCandidate = null;
        DivisionState hammerCandidate = null;
        for (DivisionState d : active) {
            if (tasked.contains(d) || d.archetype.isCarrierArchetype() || d.archetype == DivisionArchetype.SERVICE_GROUP) {
                continue;
            }
            float dist = distance(d.centroid, tPos);
            if (dist >= 880f && dist <= 1650f && d.avgFluxLevel < 0.36f && d.weaponReadinessRatio >= 0.68f) {
                if (baitCandidate == null || d == myGroup.mainEffortDivision) {
                    baitCandidate = d;
                } else if (hammerCandidate == null) {
                    hammerCandidate = d;
                }
            }
        }
        if (baitCandidate != null && hammerCandidate != null) {
            myGroup.fleetFeignedRetreatActive = true;
            myGroup.fleetFeignedRetreatStage = 1;
            myGroup.fleetFeignedRetreatStartTime = now;
            myGroup.feignedRetreatBaitDivision = baitCandidate;
            myGroup.feignedRetreatTargetEnemy = target;
            BattlefieldFirepowerEvaluator.computeFeignedRetreatBaitOffset(
                    engine, baitCandidate, target, hammerCandidate, myGroup, baitCandidate.spaceOffsetVector);
            baitCandidate.feignedRetreatStage = 1;
            baitCandidate.feignedRetreatStartTime = now;
            baitCandidate.otcOrder.set(OtcOrderType.FEIGN_RETREAT_TRAP, target, null, 0f, expires,
                    "OTC: Feigned Retreat (bait lure) against \"" + target.displayName + "\"");
            baitCandidate.otcOrder.hasStation = true;
            baitCandidate.otcOrder.station.set(
                    baitCandidate.centroid.x + baitCandidate.spaceOffsetVector.x,
                    baitCandidate.centroid.y + baitCandidate.spaceOffsetVector.y);
            tasked.add(baitCandidate);
            logOrder(engine, myGroup, "feign_start:" + target.id, String.format(
                    "Feigned Retreat [LURE]: \"%s\" feigns withdrawal to bait \"%s\" into \"%s\"'s alpha strike.",
                    baitCandidate.displayName, target.displayName, hammerCandidate.displayName), now);
        }
    }


    // ---------------------------------------------------------------------------------------------
    // Fleet-Wide Line Passing (Inter-Division Mutual Cover) & Fleet Battle Sequence
    // ---------------------------------------------------------------------------------------------

    /**
     * Fleet-Wide Line Passing: whenever a division is hauling out to vent (or about to), assign the nearest healthy
     * friendly division to slide forward and cover its withdrawal, so the venting division passes THROUGH the line
     * into the fleet safe corridor while the covering division takes over its segment of the battle line.
     * At high authority (Central Planning / Positive Control) the covering division is overridden into REINFORCE.
     */
    private static void assignFleetLinePassing(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            List<DivisionState> active,
            List<DivisionState> visibleEnemies,
            float now,
            float expires,
            Set<DivisionState> tasked) {

        myGroup.linePassCovererByDivisionId.clear();
        // Cover relationships are re-derived every OTC tick so a coverer never stays locked in cover mode.
        for (DivisionState d : myGroup.divisions) {
            d.isCoveringFriend = false;
            d.underCoverByFriend = false;
            if (d.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT
                    && d.currentIntent != DivisionIntentType.BREAK_OFF_AND_RETIRE) {
                d.coveringFriendDivision = null;
                d.linePassActive = false;
            }
        }
        for (DivisionState venting : active) {
            if (venting.isFastFlanker && venting.totalFpStrength < 24f) continue;
            boolean needsCover = venting.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                    || venting.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE
                    || (venting.avgFluxLevel >= 0.62f && venting.incomingInRangeFirepower > 35f);
            if (!needsCover) continue;

            DivisionState bestCover = null;
            float bestScore = -Float.MAX_VALUE;
            for (DivisionState cand : active) {
                if (cand == venting || tasked.contains(cand)) continue;
                if (cand.archetype.isCarrierArchetype() || cand.archetype == DivisionArchetype.SERVICE_GROUP) continue;
                if (cand.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                        || cand.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE) continue;
                if (cand.avgFluxLevel >= 0.52f) continue;
                float dist = distance(cand.centroid, venting.centroid);
                if (dist > 1150f) continue;
                float score = -dist * 0.45f + cand.totalFpStrength * 5f - cand.avgFluxLevel * 300f;
                if (cand.coveringFriendDivision == venting) score += 320f;
                if (score > bestScore) {
                    bestScore = score;
                    bestCover = cand;
                }
            }
            if (bestCover != null) {
                myGroup.linePassCovererByDivisionId.put(venting.id, bestCover);
                bestCover.coveringFriendDivision = null;
                bestCover.isCoveringFriend = true;
                venting.underCoverByFriend = true;
                venting.coveringFriendDivision = bestCover;
                venting.linePassActive = true;
                venting.linePassUntil = now + 10f;
                // At high authority, if the coverer is not already engaged in gun range and is not the Main Effort,
                // order it to REINFORCE so it slides onto the venting division's line segment
                if (myGroup.authority == OtcAuthority.POSITIVE_CONTROL
                        && bestCover != myGroup.mainEffortDivision
                        && bestCover.inRangeFirepowerScore <= 0f
                        && !tasked.contains(bestCover)) {
                    bestCover.otcOrder.set(OtcOrderType.REINFORCE, null, venting, 0f, expires,
                            "OTC: line-pass, cover withdrawal of \"" + venting.displayName + "\"");
                    tasked.add(bestCover);
                }
                logOrder(engine, myGroup, "linepass:" + bestCover.id + ">" + venting.id, String.format(
                        "Line passing: \"%s\" covers the withdrawal of \"%s\".",
                        bestCover.displayName, venting.displayName), now);
            }
        }
    }

    /**
     * Fleet Battle Sequence: Engage -> Trade Space for Time -> Feigned Retreat -> Alpha Strike.
     * Stage 1: when 2+ frontline divisions are pressured, order a synchronized trade-space offset.
     * Stage 2: after ~5s, if the enemy keeps closing and our flux has dropped, escalate to a fleet feigned retreat lure.
     * Stage 3: once the enemy overextends into the engagement zone, spring a synchronized multi-division alpha strike.
     */
    private static void evaluateFleetBattleSequence(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            List<DivisionState> active,
            List<DivisionState> visibleEnemies,
            float now,
            float expires,
            Set<DivisionState> tasked) {

        if (myGroup.organizationRatio < 0.75f && !myGroup.useConvergence) return;
        if (visibleEnemies.isEmpty() || active.size() < 2) return;

        DivisionState primary = myGroup.primaryThreatEnemyDivision;
        if (primary == null || !primary.isCombatEffective()) return;

        // Advance an already-running sequence
        if (myGroup.fleetBattleSequenceStage > 0) {
            float elapsed = now - myGroup.fleetBattleSequenceStartTime;
            if (elapsed > 22f) {
                myGroup.fleetBattleSequenceStage = 0;
                myGroup.lastFleetBattleSequenceEndTime = now;
                return;
            }
            if (myGroup.fleetBattleSequenceStage == 1 && elapsed >= 5.0f) {
                // Escalate Trade Space -> Feigned Retreat lure if the enemy is still pressing us
                if (primary.closingSpeedToEnemy > 10f && !myGroup.fleetFeignedRetreatActive) {
                    myGroup.fleetBattleSequenceStage = 2;
                    logOrder(engine, myGroup, "battleseq_feign:" + primary.id, String.format(
                            "Battle sequence: Trade Space for Time -> Feigned Retreat lure on \"%s\".", primary.displayName), now);
                }
            }
            if (myGroup.fleetBattleSequenceStage == 2 && myGroup.fleetFeignedRetreatStage == 2) {
                myGroup.fleetBattleSequenceStage = 3;
                logOrder(engine, myGroup, "battleseq_alpha:" + primary.id, String.format(
                        "Battle sequence: Feigned Retreat -> synchronized Alpha Strike on \"%s\"!", primary.displayName), now);
            }
            if (myGroup.fleetBattleSequenceStage == 3 && elapsed >= 18f) {
                myGroup.fleetBattleSequenceStage = 0;
                myGroup.lastFleetBattleSequenceEndTime = now;
            }
            return;
        }

        // Initiate a new sequence (respect cooldown and the existing fleet trade-space trigger)
        if ((now - myGroup.lastFleetBattleSequenceEndTime) < 30f) return;
        if (!myGroup.fleetTradeSpaceActive) return;
        myGroup.fleetBattleSequenceStage = 1;
        myGroup.fleetBattleSequenceStartTime = now;
        logOrder(engine, myGroup, "battleseq_start:" + primary.id, String.format(
                "Battle sequence initiated against \"%s\": Engage -> Trade Space for Time.", primary.displayName), now);
    }

    private static void logOrder(CombatEngineAPI engine, BattleGroupState battleGroup, String key, String text, float now) {
        Float last = battleGroup.orderLogTimes.get(key);
        if (last != null && (now - last) < ORDER_LOG_INTERVAL) return;
        battleGroup.orderLogTimes.put(key, now);
        DivisionCombatLog.logOtcOrder(engine, battleGroup, text);
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
