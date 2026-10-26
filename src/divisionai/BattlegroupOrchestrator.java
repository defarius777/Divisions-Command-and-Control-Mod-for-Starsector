package divisionai;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import divisionai.DivisionModel.BattlePhase;
import divisionai.DivisionModel.BattlegroupState;
import divisionai.DivisionModel.BgTaskType;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionIntentType;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionModel.StrikePlan;
import divisionai.PermanentDivisionManager.BattlegroupAuthority;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.OrchestrationPlay;
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
 * Battlegroup Commander orchestration: turns battle-wide intent into per-division {@link DivisionModel.BgTasking}s.
 * Runs on the BG cadence (1.5s) after the phase, Main Effort and Reserve have been chosen.
 *
 * Plays (each toggled in the Fleet Doctrine window, see {@link OrchestrationPlay}):
 *  - Timed Strikes: missile/strike-craft divisions stage, then commit together onto one focus ship.
 *  - Reserve & Exploit: one relief division per crisis, reserve held until needed, leashed pursuit of broken enemies.
 *  - Anchor & Fix: Main Effort fixes the primary threat; other divisions get allocated enemy divisions.
 *  - Pincer Flanks: flankers on the same target get opposite flank sides.
 *
 * Authority ({@link BattlegroupAuthority}) limits which taskings are issued here; whether a tasking is binding
 * or merely a request is decided by the Division Commander.
 */
public class BattlegroupOrchestrator {

    public static final float TASK_TTL = 4.5f;
    public static final float STRIKE_WINDOW = 14f;
    public static final float PURSUIT_LEASH = 4200f;

    private static final float STRIKE_COOLDOWN = 20f;
    private static final float STRIKE_MAX_RANGE = 5500f;
    private static final float STRIKE_STAGE_MIN = 6f;
    private static final float STRIKE_STAGE_MAX = 20f;
    private static final float TORPEDO_STAGE_DIST = 1900f;
    private static final float RELIEF_MAX_DIST = 5000f;
    private static final float LOCAL_RELIEF_MAX_DIST = 2500f;
    private static final float ORDER_LOG_INTERVAL = 20f;

    /**
     * @param endangeredDiv the significant division whose trouble drives Stabilize & Relieve (may be null)
     * @param minorEndangeredDiv the worst-off division of any size, relieved locally when there is no major crisis
     */
    public static void orchestrate(
            CombatEngineAPI engine,
            BattlegroupState myBg,
            BattlegroupState enemyBg,
            DivisionState endangeredDiv,
            DivisionState minorEndangeredDiv,
            float now) {

        BattlegroupAuthority auth = myBg.authority;
        List<DivisionState> active = new ArrayList<>();
        for (DivisionState d : myBg.divisions) {
            if (d.isCombatEffective()) active.add(d);
        }
        List<DivisionState> visibleEnemies = new ArrayList<>();
        for (DivisionState e : enemyBg.divisions) {
            if (e.isCombatEffective() && BattlegroupCommanderAI.isDivisionVisibleToSide(engine, myBg.side, e)) {
                visibleEnemies.add(e);
            }
        }

        Set<DivisionState> tasked = new HashSet<>();
        float expires = now + TASK_TTL;

        // 1. Timed strikes (claims its participants first so nothing else re-tasks them mid-run)
        if (myBg.isPlayEnabled(OrchestrationPlay.TIMED_STRIKES)) {
            runStrikePlan(engine, myBg, active, now, expires, tasked);
        } else if (myBg.strikePlan != null) {
            endStrikePlan(myBg, now);
        }

        boolean reservePlay = myBg.isPlayEnabled(OrchestrationPlay.RESERVE_AND_EXPLOIT) && auth.allowsRetasking();

        // 2. Crisis relief: exactly one relief division per crisis. A major crisis (Stabilize & Relieve) may pull a
        //    division from across the field; a minor one (a small division in trouble) only gets a close neighbour.
        myBg.crisisDivision = (myBg.currentPhase == BattlePhase.STABILIZE_AND_RELIEVE) ? endangeredDiv : null;
        if (reservePlay && myBg.crisisDivision != null) {
            assignRelief(engine, myBg, myBg.crisisDivision, RELIEF_MAX_DIST, true, active, now, expires, tasked);
        } else if (reservePlay && minorEndangeredDiv != null && minorEndangeredDiv.isCombatEffective()) {
            assignRelief(engine, myBg, minorEndangeredDiv, LOCAL_RELIEF_MAX_DIST, false, active, now, expires, tasked);
        } else {
            myBg.reliefDivision = null;
        }

        // 3. Exploitation: commit the reserve and send fast divisions after broken enemies
        if (reservePlay && myBg.currentPhase == BattlePhase.EXPLOIT_BREAKTHROUGH) {
            if (!myBg.reserveCommitted && myBg.reserveDivision != null) {
                myBg.reserveCommitted = true;
                logOrder(engine, myBg, "exploit_reserve",
                        String.format("Committing reserve \"%s\" to exploit the breakthrough.", myBg.reserveDivision.displayName), now);
                myBg.reserveDivision.isDesignatedReserve = false;
                myBg.reserveDivision = null;
            }
            assignPursuit(engine, myBg, active, visibleEnemies, now, expires, tasked);
        }

        // 4. Reserve hold
        if (reservePlay) {
            for (DivisionState d : active) {
                if (tasked.contains(d)) continue;
                boolean reserveMission = DivisionCommanderAI.resolveActiveMission(d, now) == DivisionMissionType.BATTLEGROUP_RESERVE;
                boolean holdAsReserve = (d == myBg.reserveDivision)
                        || (reserveMission && myBg.currentPhase != BattlePhase.EXPLOIT_BREAKTHROUGH
                        && myBg.currentPhase != BattlePhase.ORDERED_WITHDRAWAL);
                if (holdAsReserve) {
                    d.bgTask.set(BgTaskType.HOLD_RESERVE, null, null, 0f, expires, "held in battlegroup reserve");
                    d.bgTask.hasAnchor = true;
                    d.bgTask.anchor.set(myBg.reserveAnchor);
                    tasked.add(d);
                }
            }
        }

        // 5. Anchor & fix + target allocation
        Map<DivisionState, DivisionState> allocation = new HashMap<>();
        if (myBg.isPlayEnabled(OrchestrationPlay.ANCHOR_AND_FIX)) {
            allocateTargets(engine, myBg, active, visibleEnemies, now, expires, tasked, allocation);
        }

        // 6. Pincer flank sides
        if (myBg.isPlayEnabled(OrchestrationPlay.PINCER_FLANKS) && auth.allowsPositioning()) {
            assignFlankSides(engine, myBg, active, allocation, now, expires, tasked);
        }

        // Divisions the BG did not task this cycle go back to their own missions immediately
        for (DivisionState d : active) {
            if (!tasked.contains(d)) {
                d.bgTask.clear();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Timed strikes
    // ---------------------------------------------------------------------------------------------

    private static void runStrikePlan(
            CombatEngineAPI engine,
            BattlegroupState myBg,
            List<DivisionState> active,
            float now,
            float expires,
            Set<DivisionState> tasked) {

        StrikePlan plan = myBg.strikePlan;

        if (plan != null) {
            boolean targetValid = plan.target != null && plan.target.isCombatEffective()
                    && BattlegroupCommanderAI.isDivisionVisibleToSide(engine, myBg.side, plan.target);
            for (Iterator<DivisionState> it = plan.participants.iterator(); it.hasNext(); ) {
                DivisionState p = it.next();
                if (!p.isCombatEffective()
                        || p.currentIntent == DivisionIntentType.ROTATE_TO_RESERVE
                        || (plan.committed && p.currentIntent == DivisionIntentType.STRIKE_AND_WITHDRAW)) {
                    it.remove();
                }
            }
            if (!targetValid || plan.participants.isEmpty()) {
                if (!plan.committed && plan.target != null) {
                    logOrder(engine, myBg, "strike_abort", String.format(
                            "Timed strike on \"%s\" aborted (%s).",
                            plan.target.displayName,
                            targetValid ? "no strike divisions left" : "target lost"), now);
                }
                endStrikePlan(myBg, now);
                plan = null;
            } else if (plan.committed && now - plan.commitTime > STRIKE_WINDOW) {
                endStrikePlan(myBg, now);
                plan = null;
            } else if (!plan.committed && (now >= plan.strikeTime || allStaged(engine, myBg, plan))) {
                plan.committed = true;
                plan.commitTime = now;
                plan.focusShip = pickStrikeFocusShip(engine, myBg.side, plan.target);
                logOrder(engine, myBg, "strike_commit", String.format(
                        "STRIKE: %d divisions commit on \"%s\"%s.",
                        plan.participants.size(),
                        plan.target.displayName,
                        plan.focusShip != null ? " (focus " + plan.focusShip.getName() + ")" : ""), now);
            }
        }

        if (plan == null && (now - myBg.lastStrikePlanEndTime) >= STRIKE_COOLDOWN) {
            plan = tryCreateStrikePlan(engine, myBg, active, now);
            myBg.strikePlan = plan;
        }
        if (plan == null) return;

        Vector2f targetPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, myBg.side, plan.target);
        for (DivisionState p : plan.participants) {
            Vector2f offset = plan.stagingAnchors.get(p.id);
            BgTaskType type = plan.committed ? BgTaskType.STRIKE_COMMIT : BgTaskType.STRIKE_STAGE;
            String why = plan.committed
                    ? "strike time: commit on \"" + plan.target.displayName + "\""
                    : String.format("strike in %.0fs", Math.max(0f, plan.strikeTime - now));
            p.bgTask.set(type, plan.target, null, 0f, expires, why);
            if (offset != null) {
                p.bgTask.hasAnchor = true;
                p.bgTask.anchor.set(targetPos.x + offset.x, targetPos.y + offset.y);
            } else {
                p.bgTask.hasAnchor = false;
            }
            tasked.add(p);
        }
    }

    private static StrikePlan tryCreateStrikePlan(
            CombatEngineAPI engine,
            BattlegroupState myBg,
            List<DivisionState> active,
            float now) {

        DivisionState target = myBg.primaryThreatEnemyDivision;
        if (target == null || !target.isCombatEffective()
                || !BattlegroupCommanderAI.isDivisionVisibleToSide(engine, myBg.side, target)) {
            return null;
        }
        Vector2f targetPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, myBg.side, target);

        List<DivisionState> strikers = new ArrayList<>();
        for (DivisionState d : active) {
            if (d == myBg.mainEffortDivision || d == myBg.reserveDivision || d == myBg.reliefDivision) continue;
            if (!isStrikeCandidate(d, myBg, now)) continue;
            if (distance(d.centroid, targetPos) > STRIKE_MAX_RANGE) continue;
            strikers.add(d);
        }
        boolean mainEffortCanFix = myBg.mainEffortDivision != null
                && myBg.mainEffortDivision.isCombatEffective()
                && distance(myBg.mainEffortDivision.centroid, targetPos) < 4000f;
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
                dy = (myBg.side == 0) ? -1f : 1f;
                len = 1f;
            }
            float stageDist = d.archetype.isCarrierArchetype()
                    ? Math.max(2200f, d.lineStandoffDistance)
                    : TORPEDO_STAGE_DIST;
            Vector2f offset = new Vector2f(dx / len * stageDist, dy / len * stageDist);
            plan.stagingAnchors.put(d.id, offset);
            plan.participants.add(d);

            float travel = Math.max(0f, distance(d.centroid, new Vector2f(targetPos.x + offset.x, targetPos.y + offset.y)) - 250f);
            float eta = travel / Math.max(60f, d.avgMaxSpeed * 0.8f);
            maxEta = Math.max(maxEta, eta);
        }
        plan.strikeTime = now + Math.max(STRIKE_STAGE_MIN, Math.min(STRIKE_STAGE_MAX, maxEta));

        logOrder(engine, myBg, "strike_plan", String.format(
                "Timed strike: %d division%s staging against \"%s\", strike in %.0fs%s.",
                strikers.size(),
                strikers.size() == 1 ? "" : "s",
                target.displayName,
                plan.strikeTime - now,
                mainEffortCanFix ? " (\"" + myBg.mainEffortDivision.displayName + "\" fixes)" : ""), now);
        return plan;
    }

    private static boolean isStrikeCandidate(DivisionState d, BattlegroupState myBg, float now) {
        if (d.currentIntent == DivisionIntentType.ROTATE_TO_RESERVE
                || d.currentIntent == DivisionIntentType.STRIKE_AND_WITHDRAW) {
            return false;
        }
        if (d.archetype.isCarrierArchetype()) {
            if (d.avgFluxLevel >= 0.60f) return false;
            DivisionMissionType mission = DivisionCommanderAI.resolveActiveMission(d, now);
            if (mission == DivisionMissionType.CARRIER_MULTI_DIVISION_STRIKE) return true;
            return myBg.authority == BattlegroupAuthority.CENTRAL_PLANNING
                    && mission != DivisionMissionType.CARRIER_PROTECT_FLEET
                    && mission != DivisionMissionType.CARRIER_VOID_SUPERIORITY
                    && mission != DivisionMissionType.BATTLEGROUP_RESERVE;
        }
        return d.hasTorpedoStrikeCapability
                && d.usableStrikeAmmoCount > 0
                && d.avgFluxLevel < 0.55f
                && (now - d.lastStrikeWithdrawTimestamp) >= 12f;
    }

    private static boolean allStaged(CombatEngineAPI engine, BattlegroupState myBg, StrikePlan plan) {
        Vector2f targetPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, myBg.side, plan.target);
        for (DivisionState p : plan.participants) {
            Vector2f offset = plan.stagingAnchors.get(p.id);
            if (offset == null) continue;
            float stageDist = offset.length();
            if (distance(p.centroid, targetPos) > stageDist + 350f) {
                return false;
            }
        }
        return true;
    }

    private static ShipAPI pickStrikeFocusShip(CombatEngineAPI engine, int side, DivisionState target) {
        ShipAPI best = null;
        float bestScore = -Float.MAX_VALUE;
        for (ShipAPI s : target.getAliveMembers()) {
            if (!BattlegroupCommanderAI.isShipVisibleToSide(engine, side, s)) continue;
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

    private static void endStrikePlan(BattlegroupState myBg, float now) {
        if (myBg.strikePlan != null) {
            for (DivisionState p : myBg.strikePlan.participants) {
                if (p.bgTask.type == BgTaskType.STRIKE_STAGE || p.bgTask.type == BgTaskType.STRIKE_COMMIT) {
                    p.bgTask.clear();
                }
            }
        }
        myBg.strikePlan = null;
        myBg.lastStrikePlanEndTime = now;
    }

    /** True while a committed strike is running against the given enemy division. */
    public static boolean isStrikeCommittedOn(BattlegroupState bg, DivisionState enemyDiv) {
        return bg != null && bg.strikePlan != null && bg.strikePlan.committed
                && enemyDiv != null && bg.strikePlan.target == enemyDiv;
    }

    // ---------------------------------------------------------------------------------------------
    // Reserve, relief & exploitation
    // ---------------------------------------------------------------------------------------------

    private static void assignRelief(
            CombatEngineAPI engine,
            BattlegroupState myBg,
            DivisionState crisis,
            float maxDist,
            boolean majorCrisis,
            List<DivisionState> active,
            float now,
            float expires,
            Set<DivisionState> tasked) {

        boolean anchorPlay = myBg.isPlayEnabled(OrchestrationPlay.ANCHOR_AND_FIX);
        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;

        for (DivisionState d : active) {
            if (d == crisis || tasked.contains(d)) continue;
            if (d.archetype.isCarrierArchetype() || d.archetype == DivisionArchetype.AUXILIARY_CONVOY) continue;
            if (anchorPlay && d == myBg.mainEffortDivision) continue;
            if (d.avgFluxLevel >= 0.55f) continue;
            boolean selfEngaged = d.localEnemyStrength >= 12f && d.localOvermatchRatio < 1.0f;
            if (selfEngaged) continue;
            float dist = distance(d.centroid, crisis.centroid);
            if (dist > maxDist) continue;

            boolean isReserve = (d == myBg.reserveDivision)
                    || DivisionCommanderAI.resolveActiveMission(d, now) == DivisionMissionType.BATTLEGROUP_RESERVE;
            // The reserve is kept for real crises, not for a lone frigate in trouble
            if (isReserve && !majorCrisis) continue;
            float score = -dist * 0.15f - d.avgFluxLevel * 400f;
            if (isReserve) score += 800f;
            if (d == myBg.reliefDivision) score += 400f;
            if (d.isFastFlanker) score += 150f;
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }

        DivisionState previousRelief = myBg.reliefDivision;
        myBg.reliefDivision = best;
        if (best == null) {
            if (majorCrisis) {
                logOrder(engine, myBg, "relief_none:" + crisis.id, String.format(
                        "No uncommitted division free to relieve \"%s\" (ratio %.2f).",
                        crisis.displayName, crisis.localOvermatchRatio), now);
            }
            return;
        }

        best.bgTask.set(BgTaskType.RELIEVE, null, crisis, 0f, expires,
                String.format("BG: relieve \"%s\" (ratio %.2f)", crisis.displayName, crisis.localOvermatchRatio));
        tasked.add(best);

        if (best != previousRelief) {
            boolean fromReserve = (best == myBg.reserveDivision);
            if (fromReserve) {
                myBg.reserveCommitted = true;
                best.isDesignatedReserve = false;
                myBg.reserveDivision = null;
            }
            logOrder(engine, myBg, "relief:" + best.id + ">" + crisis.id, String.format(
                    "%s \"%s\" to %srelieve \"%s\" (local ratio %.2f).",
                    fromReserve ? "Committing reserve" : "Ordering",
                    best.displayName,
                    majorCrisis ? "" : "locally ",
                    crisis.displayName, crisis.localOvermatchRatio), now);
        }
    }

    private static void assignPursuit(
            CombatEngineAPI engine,
            BattlegroupState myBg,
            List<DivisionState> active,
            List<DivisionState> visibleEnemies,
            float now,
            float expires,
            Set<DivisionState> tasked) {

        List<DivisionState> broken = new ArrayList<>();
        for (DivisionState e : visibleEnemies) {
            if (isVisiblyBroken(engine, myBg.side, e)
                    && distance(myBg.fleetCentroid, BattlegroupCommanderAI.getVisibleCentroidForSide(engine, myBg.side, e)) < PURSUIT_LEASH) {
                broken.add(e);
            }
        }
        if (broken.isEmpty()) return;

        int pursuers = 0;
        for (DivisionState d : active) {
            if (pursuers >= 2) break;
            if (tasked.contains(d) || d == myBg.mainEffortDivision) continue;
            if (!d.isFastFlanker || d.archetype.isCarrierArchetype()) continue;
            if (d.avgFluxLevel >= 0.50f) continue;

            DivisionState nearest = null;
            float nearestDist = Float.MAX_VALUE;
            for (DivisionState e : broken) {
                float dist = distance(d.centroid, BattlegroupCommanderAI.getVisibleCentroidForSide(engine, myBg.side, e));
                if (d.bgTask.type == BgTaskType.PURSUE && d.bgTask.targetEnemy == e) dist -= 600f;
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = e;
                }
            }
            if (nearest == null || nearestDist > 4000f) continue;

            boolean newOrder = !(d.bgTask.type == BgTaskType.PURSUE && d.bgTask.targetEnemy == nearest);
            d.bgTask.set(BgTaskType.PURSUE, nearest, null, 0f, expires, "BG: pursue broken \"" + nearest.displayName + "\"");
            tasked.add(d);
            pursuers++;
            if (newOrder) {
                logOrder(engine, myBg, "pursue:" + d.id + ">" + nearest.id, String.format(
                        "\"%s\" to pursue broken \"%s\" (leash %.0fu).", d.displayName, nearest.displayName, PURSUIT_LEASH), now);
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
            if (!BattlegroupCommanderAI.isShipVisibleToSide(engine, observerSide, s)) continue;
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

    private static void allocateTargets(
            CombatEngineAPI engine,
            BattlegroupState myBg,
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
            neededFp.put(e, BattlegroupCommanderAI.getVisibleFpForSide(engine, myBg.side, e) * 1.3f);
            assignedFp.put(e, 0f);
        }
        // Divisions already committed elsewhere still count toward the enemy they are engaging
        for (DivisionState d : active) {
            if (tasked.contains(d) && d.bgTask.targetEnemy != null && assignedFp.containsKey(d.bgTask.targetEnemy)) {
                assignedFp.put(d.bgTask.targetEnemy, assignedFp.get(d.bgTask.targetEnemy) + d.totalFpStrength);
            }
        }

        DivisionState primary = myBg.primaryThreatEnemyDivision;
        if (primary != null && !visibleEnemies.contains(primary)) primary = null;

        // Main Effort fixes the primary threat
        DivisionState main = myBg.mainEffortDivision;
        if (main != null && main.isCombatEffective() && !tasked.contains(main) && primary != null) {
            Vector2f primaryPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, myBg.side, primary);
            boolean newOrder = !(main.bgTask.type == BgTaskType.FIX_PRIMARY && main.bgTask.targetEnemy == primary);
            main.bgTask.set(BgTaskType.FIX_PRIMARY, primary, null, 0f, expires,
                    "BG Main Effort: fix \"" + primary.displayName + "\"");
            if (myBg.authority.allowsPositioning()) {
                float dx = myBg.fleetCentroid.x - primaryPos.x;
                float dy = myBg.fleetCentroid.y - primaryPos.y;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1f) {
                    dx = 0f;
                    dy = (myBg.side == 0) ? -1f : 1f;
                    len = 1f;
                }
                float hold = Math.max(500f, main.lineStandoffDistance * 0.9f);
                main.bgTask.hasAnchor = true;
                main.bgTask.anchor.set(primaryPos.x + dx / len * hold, primaryPos.y + dy / len * hold);
            } else {
                main.bgTask.hasAnchor = false;
            }
            tasked.add(main);
            allocationOut.put(main, primary);
            assignedFp.put(primary, assignedFp.get(primary) + main.totalFpStrength);
            if (newOrder) {
                logOrder(engine, myBg, "fix:" + main.id + ">" + primary.id, String.format(
                        "Main Effort \"%s\" to fix \"%s\".", main.displayName, primary.displayName), now);
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
            DivisionState previous = (d.bgTask.type == BgTaskType.SUPPORT_ATTACK || d.bgTask.type == BgTaskType.FLANK)
                    ? d.bgTask.targetEnemy : null;
            for (DivisionState e : visibleEnemies) {
                Vector2f ePos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, myBg.side, e);
                float need = Math.max(4f, neededFp.get(e));
                float have = assignedFp.get(e);
                float score = -distance(d.centroid, ePos) * 0.30f;
                if (e == previous) score += 300f;
                if (e == primary) score += 120f;
                if (e.archetype == DivisionArchetype.AUXILIARY_CONVOY) score -= 450f;
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

            d.bgTask.set(BgTaskType.SUPPORT_ATTACK, best, null, 0f, expires, "BG: engage \"" + best.displayName + "\"");
            d.bgTask.hasAnchor = false;
            tasked.add(d);
            allocationOut.put(d, best);
            float weight = d.archetype.isCarrierArchetype() ? 0.5f : 1f;
            assignedFp.put(best, assignedFp.get(best) + d.totalFpStrength * weight);
        }
    }

    private static void assignFlankSides(
            CombatEngineAPI engine,
            BattlegroupState myBg,
            List<DivisionState> active,
            Map<DivisionState, DivisionState> allocation,
            float now,
            float expires,
            Set<DivisionState> tasked) {

        DivisionState primary = myBg.primaryThreatEnemyDivision;
        boolean mainIsFixing = myBg.mainEffortDivision != null
                && myBg.mainEffortDivision.bgTask.type == BgTaskType.FIX_PRIMARY;
        boolean central = myBg.authority == BattlegroupAuthority.CENTRAL_PLANNING;

        // Flankers grouped by the enemy division they envelop
        Map<DivisionState, List<DivisionState>> flankersByTarget = new HashMap<>();
        int centralExtraFlankers = 0;
        for (DivisionState d : active) {
            BgTaskType t = d.bgTask.type;
            boolean free = !tasked.contains(d) || t == BgTaskType.SUPPORT_ATTACK;
            if (!free || d == myBg.mainEffortDivision || d.archetype.isCarrierArchetype()) continue;

            boolean flankMission = DivisionCommanderAI.resolveActiveMission(d, now) == DivisionMissionType.OUTFLANK_AND_DESTROY;
            boolean centralFlanker = central && mainIsFixing && d.isFastFlanker && d.avgFluxLevel < 0.55f
                    && centralExtraFlankers < 2;
            if (!flankMission && !centralFlanker) continue;
            if (!flankMission) centralExtraFlankers++;

            DivisionState target = allocation.get(d);
            if (target == null) target = primary;
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
            Vector2f tPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, myBg.side, target);
            float ax = tPos.x - myBg.fleetCentroid.x;
            float ay = tPos.y - myBg.fleetCentroid.y;
            float aLen = (float) Math.sqrt(ax * ax + ay * ay);
            if (aLen < 1f) {
                ax = 0f;
                ay = (myBg.side == 0) ? 1f : -1f;
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

            int left = 0;
            int right = 0;
            for (DivisionState d : flankers) {
                float natural = Math.signum((d.centroid.x - tPos.x) * perpX + (d.centroid.y - tPos.y) * perpY);
                if (natural == 0f) natural = 1f;
                float previous = (d.bgTask.type == BgTaskType.FLANK && d.bgTask.targetEnemy == target) ? d.bgTask.flankSide : 0f;
                float side = previous != 0f ? previous : natural;
                // Balance: never put a second flanker on a side while the other side is empty
                if (side > 0f && right > left) side = -1f;
                else if (side < 0f && left > right) side = 1f;
                if (side > 0f) right++;
                else left++;

                boolean newOrder = !(d.bgTask.type == BgTaskType.FLANK && d.bgTask.targetEnemy == target
                        && Math.signum(d.bgTask.flankSide) == Math.signum(side));
                d.bgTask.set(BgTaskType.FLANK, target, null, side, expires,
                        String.format("BG: %s flank of \"%s\"", side > 0f ? "right" : "left", target.displayName));
                d.bgTask.hasAnchor = false;
                tasked.add(d);
                if (newOrder && flankers.size() >= 2) {
                    logOrder(engine, myBg, "flank:" + d.id + ">" + target.id + (side > 0f ? "R" : "L"), String.format(
                            "Pincer: \"%s\" takes the %s flank of \"%s\".",
                            d.displayName, side > 0f ? "right" : "left", target.displayName), now);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static void logOrder(CombatEngineAPI engine, BattlegroupState bg, String key, String text, float now) {
        Float last = bg.orderLogTimes.get(key);
        if (last != null && (now - last) < ORDER_LOG_INTERVAL) return;
        bg.orderLogTimes.put(key, now);
        DivisionCombatLog.logBattlegroupOrder(engine, bg, text);
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
