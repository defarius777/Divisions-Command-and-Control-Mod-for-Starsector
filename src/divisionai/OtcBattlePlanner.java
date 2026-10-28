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
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.DivisionMissionType;
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
            if (d.isCombatEffective()) active.add(d);
        }
        List<DivisionState> visibleEnemies = new ArrayList<>();
        for (DivisionState e : enemyGroup.divisions) {
            if (e.isCombatEffective() && OtcAI.isDivisionVisibleToSide(engine, myGroup.side, e)) {
                visibleEnemies.add(e);
            }
        }

        Set<DivisionState> tasked = new HashSet<>();
        float expires = now + TASK_TTL;

        // 1. Timed strikes (claims its participants first so nothing else re-tasks them mid-run)
        if (myGroup.isPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES)) {
            runStrikePlan(engine, myGroup, active, now, expires, tasked);
        } else if (myGroup.strikePlan != null) {
            endStrikePlan(myGroup, now);
        }

        boolean reservePlay = myGroup.isPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE) && auth.allowsRetasking();

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

        // 4. Reserve hold
        if (reservePlay) {
            for (DivisionState d : active) {
                if (tasked.contains(d)) continue;
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
            boolean targetValid = plan.target != null && plan.target.isCombatEffective()
                    && OtcAI.isDivisionVisibleToSide(engine, myGroup.side, plan.target);
            for (Iterator<DivisionState> it = plan.participants.iterator(); it.hasNext(); ) {
                DivisionState p = it.next();
                if (!p.isCombatEffective()
                        || p.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                        || (plan.committed && p.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE)) {
                    it.remove();
                }
            }
            if (!targetValid || plan.participants.isEmpty()) {
                if (!plan.committed && plan.target != null) {
                    logOrder(engine, myGroup, "strike_abort", String.format(
                            "Strike on \"%s\" cancelled (%s).",
                            plan.target.displayName,
                            targetValid ? "no strike divisions left" : "contact lost"), now);
                }
                endStrikePlan(myGroup, now);
                plan = null;
            } else if (plan.committed && now - plan.commitTime > STRIKE_WINDOW) {
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
        if (plan == null) return;

        Vector2f targetPos = OtcAI.getVisibleCentroidForSide(engine, myGroup.side, plan.target);
        for (DivisionState p : plan.participants) {
            Vector2f offset = plan.marshalOffsets.get(p.id);
            OtcOrderType type = plan.committed ? OtcOrderType.EXECUTE_STRIKE : OtcOrderType.MARSHAL;
            String why = plan.committed
                    ? "OTC: execute, strike \"" + plan.target.displayName + "\""
                    : String.format("OTC: marshal, strike in %.0fs", Math.max(0f, plan.strikeTime - now));
            p.otcOrder.set(type, plan.target, null, 0f, expires, why);
            if (myGroup.authority.allowsPositioning()) {
                if (plan.committed) {
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
        if (strikers.isEmpty() || (strikers.size() < 2 && !mainEffortCanFix)) {
            return null;
        }

        StrikePlan plan = new StrikePlan();
        plan.target = target;
        plan.createdTime = now;
        float maxEta = 0f;
        for (DivisionState d : strikers) {
            float dx = d.centroid.x - targetPos.x;
            float dy = d.centroid.y - targetPos.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1f) {
                dx = 0f;
                dy = (myGroup.side == 0) ? -1f : 1f;
                len = 1f;
            }
            float marshalDist = d.archetype.isCarrierArchetype()
                    ? Math.max(2200f, d.standoffRange)
                    : (DivisionCommanderAI.resolveActiveMission(d, now) == DivisionMissionType.TOT_ALPHA_STRIKE
                    ? Math.min(1550f, Math.max(1150f, d.avgEngageRange * 1.35f))
                    : MISSILE_MARSHAL_DIST);
            Vector2f offset = new Vector2f(dx / len * marshalDist, dy / len * marshalDist);
            plan.marshalOffsets.put(d.id, offset);
            plan.participants.add(d);

            float travel = Math.max(0f, distance(d.centroid, new Vector2f(targetPos.x + offset.x, targetPos.y + offset.y)) - 250f);
            float effectiveSpd = Math.max(45f, d.weightedTimeSpeed > 1f
                    ? (d.weightedTimeSpeed * Math.max(0.45f, d.timeFreedomFactor))
                    : (d.avgMaxSpeed * 0.8f));
            float eta = travel / effectiveSpd;
            maxEta = Math.max(maxEta, eta);
        }
        plan.strikeTime = now + Math.max(MARSHAL_TIME_MIN, Math.min(MARSHAL_TIME_MAX, maxEta));

        logOrder(engine, myGroup, "strike_plan", String.format(
                "Time-on-target strike: %d division%s to marshal against \"%s\", strike in %.0fs%s.",
                strikers.size(),
                strikers.size() == 1 ? "" : "s",
                target.displayName,
                plan.strikeTime - now,
                mainEffortCanFix ? " (\"" + myGroup.mainEffortDivision.displayName + "\" fixes the target)" : ""), now);
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
        for (ShipAPI s : target.getAliveMembers()) {
            if (!OtcAI.isShipVisibleToSide(engine, side, s)) continue;
            // Biggest, most flux-locked, most damaged ship in the target division
            float score = ShipCapabilityProfile.resolveShipFpStrength(s) * 8f
                    + s.getFluxLevel() * 300f
                    + (1f - s.getHullLevel()) * 250f;
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
        for (DivisionState e : visibleEnemies) {
            neededFp.put(e, OtcAI.getVisibleFpForSide(engine, myGroup.side, e) * 1.3f);
            assignedFp.put(e, 0f);
        }
        // Divisions already committed elsewhere still count toward the enemy they are engaging
        for (DivisionState d : active) {
            if (tasked.contains(d) && d.otcOrder.targetEnemy != null && assignedFp.containsKey(d.otcOrder.targetEnemy)) {
                assignedFp.put(d.otcOrder.targetEnemy, assignedFp.get(d.otcOrder.targetEnemy) + d.totalFpStrength);
            }
        }

        DivisionState primary = myGroup.primaryThreatEnemyDivision;
        if (primary != null && !visibleEnemies.contains(primary)) primary = null;

        // Main Effort fixes the primary threat
        DivisionState main = myGroup.mainEffortDivision;
        if (main != null && main.isCombatEffective() && !tasked.contains(main) && primary != null) {
            Vector2f primaryPos = OtcAI.getVisibleCentroidForSide(engine, myGroup.side, primary);
            boolean newOrder = !(main.otcOrder.type == OtcOrderType.FORM_LINE && main.otcOrder.targetEnemy == primary);
            main.otcOrder.set(OtcOrderType.FORM_LINE, primary, null, 0f, expires,
                    "OTC: Main Effort, form the line on \"" + primary.displayName + "\"");
            if (myGroup.authority == OtcAuthority.POSITIVE_CONTROL
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
                main.otcOrder.hasStation = true;
                main.otcOrder.station.set(primaryPos.x + dx / len * hold, primaryPos.y + dy / len * hold);
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
                float score = -dist * 0.30f;
                if (e == previous) score += 300f;
                if (e == primary) score += 120f;
                if (e.archetype == DivisionArchetype.SERVICE_GROUP) score -= 450f;

                if (myGroup.useConvergence) {
                    // Convergence OTC (AI / Threat / Abyssal or Alpha Core flagship):
                    // Non-linear synthesis of Time reachability, Space seam isolation, and orthogonal Firepower
                    score += BattlefieldFirepowerEvaluator.computeConvergenceTargetScore(engine, d, e, myGroup) * 0.45f;
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
                    score -= 400f * (have / need - 1f);
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = e;
                }
            }
            if (best == null) continue;

            d.otcOrder.set(OtcOrderType.ENGAGE_ASSIGNED, best, null, 0f, expires, "OTC: fire distribution, engage \"" + best.displayName + "\"");
            d.otcOrder.hasStation = false;
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

            for (DivisionState d : flankers) {
                float previous = (d.otcOrder.type == OtcOrderType.FLANK && d.otcOrder.targetEnemy == target) ? d.otcOrder.flankSide : 0f;
                float side;
                if (previous != 0f) {
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
