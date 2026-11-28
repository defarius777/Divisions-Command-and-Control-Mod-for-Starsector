package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.AdmiralAIPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.mission.FleetSide;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import org.apache.log4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Division-Cohesive AI Fleet Deployment & Reinforcement Planner.
 *
 * <p>Vanilla Starsector's AI deployment manager ({@code com.fs.starfarer.combat.ai.admiral.G}) copies
 * {@code getReserves()}, runs {@code Collections.shuffle(...)}, and steps through a 16-slot hull-size wheel
 * picking the first random ship matching each hull size. On large DP-capped fleets organized into campaign
 * divisions, that random draw plucks 1 ship out of 8 different divisions instead of deploying intact,
 * mutually-supporting 3-4 ship divisions, and trickles replacements in piecemeal.
 *
 * <p>This planner attaches an {@link AdmiralAIPlugin.AdmiralPluginDelegate} (public API in both
 * {@code 0.98a-RC8} and {@code 0.98.5a-RC9+}) to both the Enemy {@link AdmiralAIPlugin} and the Allied AI
 * {@link AdmiralAIPlugin}, chaining cleanly around any existing mod/boss {@code AdmiralPluginDelegate}.
 * Within each vanilla wheel role &amp; tier bucket, it enforces:
 * <ol>
 *   <li><b>Complete Active Divisions First</b>: If a division already has at least one hull on the field or
 *       queued in the current deployment batch ({@code deployingSoFar}) and still has affordable wingmen in
 *       reserve, complete that division before opening another division of the same role.</li>
 *   <li><b>Doctrinal Opening Priority</b>: When opening a new division in a role category, prioritize the
 *       OTC / Fleet Commander flagship's division first, then intact multi-ship divisions with strong officers
 *       and affordable wingman depth over 1-ship fragments or unassigned stragglers.</li>
 *   <li><b>Division Leader First</b>: When DP caps can only fit part of a newly opened division, prefer its
 *       designated Division Leader ({@code flagshipMemberId}) first so the unit enters combat under command.</li>
 * </ol>
 *
 * <p>Anti-soft-lock invariants verified against {@code admiral.G} bytecode ({@code 0.98a-RC8} and {@code 0.98.5a-RC9}):
 * <ul>
 *   <li>18-bucket exact disjoint partition over {@code (hullSize, isCarrier, hasFittedWings, isCivilian)} so
 *       wingless {@code CARRIER}-hinted hulls (e.g. drone destroyers or empty-bay carriers) and size-rewritten
 *       civilian hulls never block normal combat hulls or each other under anti-phase-cheese wheel rewrites.</li>
 *   <li>Allied AI Pursuit {@code CR <= 0.10f} parity ({@code owner == 0 && ally && ATTACK vs ESCAPE}).</li>
 *   <li>{@code 0.98.5a-RC9} {@code AdmiralAIConfig.softDeploymentLimits} / {@code softDeploymentLimitFilters}
 *       parity (e.g. Threat {@code fabricator_unit} caps) resolved via {@link MethodHandles#publicLookup()}.</li>
 *   <li>Station-aware &amp; deployability-aware mid-battle trickle batching so high-DP Orbital Stations or
 *       non-deployable mixed-division wingmen never starve Cruiser/Capital reinforcements.</li>
 *   <li>Upstream {@code AdmiralPluginDelegate} chaining + peer pre-filtering (e.g. {@code ShipMasterySystem}
 *       beacon bosses) plus a stateful consecutive peer-veto circuit breaker that fails open if vanilla ever
 *       rejects a preferred peer repeatedly within the same deployment pass.</li>
 * </ul>
 */
public final class DivisionDeploymentPlanner {

    private static final Logger LOG = Global.getLogger(DivisionDeploymentPlanner.class);

    /**
     * Exact 18-way disjoint partition of {@link FleetMemberAPI}s isomorphic to vanilla {@code admiral.G}'s
     * {@code tableswitch} predicates across all battle modes ({@code normal}, {@code ESCAPE},
     * {@code enemyDeployAll}, {@code onlyCarriersOrCivs}, and {@code phase_cheese} wheel rewrites).
     */
    public enum VanillaWheelBucket {
        // Fitted-wing carriers (vanilla ANY_CARRIER / LARGE_CARRIER slots: isCarrier() && !fittedWings.isEmpty())
        CAPITAL_CARRIER,
        OTHER_CARRIER,

        // Tier 1: Normal combat hulls (!isCarrier() && !isCivilian())
        CAPITAL_COMBAT,
        CRUISER_COMBAT,
        DESTROYER_COMBAT,
        FRIGATE_OR_WING,

        // Tier 2: Wingless combat carriers (isCarrier() && fittedWings.isEmpty() && !isCivilian()) —
        // blocked by vanilla until all Tier 1 combat hulls have deployed (onlyCarriersOrCivs == true)
        CAPITAL_WINGLESS_CARRIER,
        CRUISER_WINGLESS_CARRIER,
        DESTROYER_WINGLESS_CARRIER,
        FRIGATE_WINGLESS_CARRIER,

        // Tier 3: Civilian non-carriers (isCivilian() && !isCarrier()) —
        // partitioned by hull size so phase-cheese FRIGATE->DESTROYER wheel rewrites never cross-block
        CAPITAL_CIVILIAN,
        CRUISER_CIVILIAN,
        DESTROYER_CIVILIAN,
        FRIGATE_CIVILIAN,

        // Tier 4: Civilian wingless carriers (isCivilian() && isCarrier() && fittedWings.isEmpty()) —
        // require BOTH allowCivs == true AND onlyCarriersOrCivs == true in vanilla admiral.G
        CAPITAL_CIVILIAN_WINGLESS_CARRIER,
        CRUISER_CIVILIAN_WINGLESS_CARRIER,
        DESTROYER_CIVILIAN_WINGLESS_CARRIER,
        FRIGATE_CIVILIAN_WINGLESS_CARRIER
    }

    /**
     * Reason-coded evaluation outcome so the stateful circuit breaker distinguishes peer-preference vetoes
     * from intentional high-DP pair-batching holds and unaffordable/upstream blocks.
     */
    public enum CohesiveDecision {
        ALLOW,
        BLOCK_UNAFFORDABLE_OR_UPSTREAM,
        HOLD_FOR_PAIR_AT_HIGH_DP,
        VETO_FOR_BETTER_PEER
    }

    private static final IdentityHashMap<AdmiralAIPlugin, CohesiveAdmiralDelegate> ATTACHED_DELEGATES =
            new IdentityHashMap<>();

    // Zero-reflection MethodHandles caches (100% ScriptClassLoader-safe across 0.98a-RC8 and 0.98.5a-RC9+)
    private static final Object MISSING_HANDLE = new Object();
    private static final ConcurrentHashMap<Class<?>, Object> GET_DELEGATE_HANDLES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Object> GET_CONFIG_HANDLES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Object> SOFT_LIMITS_GETTER_HANDLES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Object> SOFT_FILTER_LIMITS_GETTER_HANDLES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Object> FILTER_MATCHES_HANDLES = new ConcurrentHashMap<>();

    private DivisionDeploymentPlanner() {
    }

    public static void resetForNewBattle() {
        ATTACHED_DELEGATES.clear();
    }

    /**
     * Attaches cohesive division deployment delegates onto Enemy and Allied AI {@link AdmiralAIPlugin} instances,
     * preserving and wrapping any pre-existing or late-installed mod {@code AdmiralPluginDelegate}.
     * Safe to invoke every frame (O(1) identity check once attached).
     */
    public static void ensureAttached(CombatEngineAPI engine) {
        if (engine == null || BattlefieldFirepowerEvaluator.isSimulationEngine(engine)) {
            return;
        }
        CombatFleetManagerAPI enemyFm = engine.getFleetManager(FleetSide.ENEMY);
        CombatFleetManagerAPI playerFm = engine.getFleetManager(FleetSide.PLAYER);
        attachForFleetSide(enemyFm, playerFm, false);
        attachForFleetSide(playerFm, enemyFm, true);
    }

    private static void attachForFleetSide(
            CombatFleetManagerAPI fm,
            CombatFleetManagerAPI opposingFm,
            boolean allyOnlyMode) {
        if (fm == null) return;
        AdmiralAIPlugin admiralAI = fm.getAdmiralAI();
        if (admiralAI == null) return;

        CohesiveAdmiralDelegate existingOur = ATTACHED_DELEGATES.get(admiralAI);
        AdmiralAIPlugin.AdmiralPluginDelegate current = getExistingDelegate(admiralAI);

        if (existingOur != null) {
            // Fast path: still attached (or mock AdmiralAIPlugin without concrete getDelegate() accessor)
            if (current == null || current == existingOur) {
                return;
            }
            // Another mod (e.g. a boss/beacon script) called setDelegate(...) after us on a later frame:
            // wrap their delegate cleanly as our upstreamDelegate (bounded to prevent ping-pong loops).
            if (!(current instanceof CohesiveAdmiralDelegate) && existingOur.reattachCount < 2) {
                existingOur.setUpstreamDelegate(current);
                existingOur.reattachCount++;
                admiralAI.setDelegate(existingOur);
            }
            return;
        }

        if (current instanceof CohesiveAdmiralDelegate) {
            ATTACHED_DELEGATES.put(admiralAI, (CohesiveAdmiralDelegate) current);
            return;
        }

        if (allyOnlyMode) {
            boolean hasAnyAllyReserve = false;
            List<FleetMemberAPI> reserves = fm.getReservesCopy();
            if (reserves != null) {
                for (int i = 0, n = reserves.size(); i < n; i++) {
                    FleetMemberAPI m = reserves.get(i);
                    if (m != null && m.isAlly()) {
                        hasAnyAllyReserve = true;
                        break;
                    }
                }
            }
            if (!hasAnyAllyReserve) {
                return;
            }
        }

        CohesiveAdmiralDelegate delegate = new CohesiveAdmiralDelegate(fm, opposingFm, admiralAI, allyOnlyMode, current);
        admiralAI.setDelegate(delegate);
        ATTACHED_DELEGATES.put(admiralAI, delegate);
    }

    public static boolean hasFittedFighterWings(FleetMemberAPI fm) {
        return fm != null
                && fm.getVariant() != null
                && fm.getVariant().getFittedWings() != null
                && !fm.getVariant().getFittedWings().isEmpty();
    }

    public static boolean isWinglessCarrier(FleetMemberAPI fm) {
        return fm != null && fm.isCarrier() && !hasFittedFighterWings(fm);
    }

    /**
     * Classifies a {@link FleetMemberAPI} into the exact 18-way disjoint bucket matching vanilla {@code admiral.G}'s
     * reinforcement picker predicates.
     */
    public static VanillaWheelBucket classifyWheelBucket(FleetMemberAPI fm) {
        if (fm == null) return VanillaWheelBucket.FRIGATE_CIVILIAN;
        boolean fittedWings = hasFittedFighterWings(fm);
        if (fm.isCarrier() && fittedWings) {
            return fm.isCapital() ? VanillaWheelBucket.CAPITAL_CARRIER : VanillaWheelBucket.OTHER_CARRIER;
        }
        boolean civ = fm.isCivilian();
        boolean winglessCarrier = fm.isCarrier(); // fittedWings is false here
        if (civ && winglessCarrier) {
            if (fm.isCapital()) return VanillaWheelBucket.CAPITAL_CIVILIAN_WINGLESS_CARRIER;
            if (fm.isCruiser()) return VanillaWheelBucket.CRUISER_CIVILIAN_WINGLESS_CARRIER;
            if (fm.isDestroyer()) return VanillaWheelBucket.DESTROYER_CIVILIAN_WINGLESS_CARRIER;
            return VanillaWheelBucket.FRIGATE_CIVILIAN_WINGLESS_CARRIER;
        }
        if (civ) {
            if (fm.isCapital()) return VanillaWheelBucket.CAPITAL_CIVILIAN;
            if (fm.isCruiser()) return VanillaWheelBucket.CRUISER_CIVILIAN;
            if (fm.isDestroyer()) return VanillaWheelBucket.DESTROYER_CIVILIAN;
            return VanillaWheelBucket.FRIGATE_CIVILIAN;
        }
        if (winglessCarrier) {
            if (fm.isCapital()) return VanillaWheelBucket.CAPITAL_WINGLESS_CARRIER;
            if (fm.isCruiser()) return VanillaWheelBucket.CRUISER_WINGLESS_CARRIER;
            if (fm.isDestroyer()) return VanillaWheelBucket.DESTROYER_WINGLESS_CARRIER;
            return VanillaWheelBucket.FRIGATE_WINGLESS_CARRIER;
        }
        if (fm.isCapital()) return VanillaWheelBucket.CAPITAL_COMBAT;
        if (fm.isCruiser()) return VanillaWheelBucket.CRUISER_COMBAT;
        if (fm.isDestroyer()) return VanillaWheelBucket.DESTROYER_COMBAT;
        return VanillaWheelBucket.FRIGATE_OR_WING;
    }

    /**
     * Public stateless entry point preserved for callers and {@code DivisionAITestSuite}.
     */
    public static boolean isCandidateAllowedForCohesiveDeployment(
            CombatFleetManagerAPI fleetManager,
            boolean allyOnlyMode,
            Map<String, PermanentDivisionSpec> divisionByMemberId,
            List<FleetMemberAPI> deployingSoFar,
            FleetMemberAPI candidate) {
        return evaluateCandidateForCohesiveDeployment(
                fleetManager,
                null,
                null,
                null,
                null,
                allyOnlyMode,
                divisionByMemberId,
                deployingSoFar,
                candidate) == CohesiveDecision.ALLOW;
    }

    /**
     * Extended public entry point supporting explicit opposing fleet manager, upstream mod delegate,
     * and {@code 0.98.5a-RC9} {@code softDeploymentLimits} injection for deterministic simulation tests.
     */
    public static CohesiveDecision evaluateCandidateForCohesiveDeployment(
            CombatFleetManagerAPI fleetManager,
            CombatFleetManagerAPI opposingFleetManager,
            AdmiralAIPlugin admiralAI,
            AdmiralAIPlugin.AdmiralPluginDelegate upstreamDelegate,
            Map<String, Integer> softDeploymentLimitsOverride,
            boolean allyOnlyMode,
            Map<String, PermanentDivisionSpec> divisionByMemberId,
            List<FleetMemberAPI> deployingSoFar,
            FleetMemberAPI candidate) {
        if (candidate == null || fleetManager == null) {
            return CohesiveDecision.ALLOW;
        }
        if (upstreamDelegate != null && !safeUpstreamAllowed(upstreamDelegate, deployingSoFar, candidate)) {
            return CohesiveDecision.BLOCK_UNAFFORDABLE_OR_UPSTREAM;
        }
        if (candidate.isAlly() != allyOnlyMode) {
            return CohesiveDecision.ALLOW;
        }
        List<FleetMemberAPI> rawReserves = fleetManager.getReservesCopy();
        if (rawReserves == null || rawReserves.isEmpty()) {
            return CohesiveDecision.ALLOW;
        }

        // Compute remaining DP budget after ships already queued in deployingSoFar
        float dpQueued = 0f;
        Set<String> queuedIds = new HashSet<>();
        if (deployingSoFar != null) {
            for (int i = 0, n = deployingSoFar.size(); i < n; i++) {
                FleetMemberAPI m = deployingSoFar.get(i);
                if (m != null) {
                    dpQueued += m.getDeploymentPointsCost();
                    if (m.getId() != null) queuedIds.add(m.getId());
                }
            }
        }
        float remainingDp = computeAvailableSideDp(fleetManager, allyOnlyMode, rawReserves) - dpQueued;
        if (candidate.getDeploymentPointsCost() > remainingDp + 0.01f) {
            return CohesiveDecision.BLOCK_UNAFFORDABLE_OR_UPSTREAM;
        }

        boolean alliedPursuit = isAlliedPursuitBattle(fleetManager, opposingFleetManager, allyOnlyMode);
        float candCr = (candidate.getRepairTracker() != null) ? candidate.getRepairTracker().getCR() : 1.0f;
        // In Allied Pursuit (owner==0, ally, ATTACK vs ESCAPE), vanilla rejects CR <= 0.10f right after allowedToDeploy.
        // Pass through cleanly so we never waste peer-veto budget on ships vanilla itself will skip.
        if (alliedPursuit && candCr <= 0.10f) {
            return CohesiveDecision.ALLOW;
        }

        List<FleetMemberAPI> currentlyDeployed = fleetManager.getDeployedCopy();
        Object admiralCfg = getAdmiralConfig(admiralAI);
        Map<String, Integer> softHullLimits = (softDeploymentLimitsOverride != null)
                ? softDeploymentLimitsOverride
                : getSoftDeploymentLimitsMap(admiralCfg);
        Map<?, Integer> softFilterLimits = getSoftDeploymentLimitFiltersMap(admiralCfg);

        VanillaWheelBucket bucket = classifyWheelBucket(candidate);
        boolean candidateHighCr = candCr >= 0.40f;

        // Collect all peer reserve candidates in the EXACT SAME 18-way vanilla wheel bucket that fit remainingDp,
        // pass CR gates, pass 0.98.5a-RC9 softDeploymentLimits, and pass any upstream mod delegate!
        List<FleetMemberAPI> eligiblePeers = new ArrayList<>();
        boolean reserveHasNormalCombat = false;
        boolean fieldOrQueueHasNonCivilian = false;
        if (currentlyDeployed != null) {
            for (int i = 0, n = currentlyDeployed.size(); i < n; i++) {
                FleetMemberAPI d = currentlyDeployed.get(i);
                if (d != null && !d.isCivilian()) {
                    fieldOrQueueHasNonCivilian = true;
                    break;
                }
            }
        }
        if (!fieldOrQueueHasNonCivilian && deployingSoFar != null) {
            for (int i = 0, n = deployingSoFar.size(); i < n; i++) {
                FleetMemberAPI q = deployingSoFar.get(i);
                if (q != null && !q.isCivilian()) {
                    fieldOrQueueHasNonCivilian = true;
                    break;
                }
            }
        }

        for (int i = 0, n = rawReserves.size(); i < n; i++) {
            FleetMemberAPI m = rawReserves.get(i);
            if (m == null || m.isAlly() != allyOnlyMode) continue;
            if (m.getId() != null && queuedIds.contains(m.getId())) continue;
            if (isBlockedBySoftDeploymentLimit(m, softHullLimits, softFilterLimits, currentlyDeployed, deployingSoFar)) {
                continue;
            }
            if (!m.isCivilian() && !isWinglessCarrier(m)) {
                reserveHasNormalCombat = true;
                fieldOrQueueHasNonCivilian = true;
            }
            if (m.getDeploymentPointsCost() > remainingDp + 0.01f) continue;
            float mCr = (m.getRepairTracker() != null) ? m.getRepairTracker().getCR() : 1.0f;
            if (alliedPursuit && mCr <= 0.10f) continue;
            if (candidateHighCr && mCr < 0.40f) continue;
            if (classifyWheelBucket(m) != bucket) continue;
            if (upstreamDelegate != null && m != candidate && !safeUpstreamAllowed(upstreamDelegate, deployingSoFar, m)) {
                continue;
            }
            eligiblePeers.add(m);
        }

        // Count already-committed hulls per division + mobile (non-station) active DP and ship count
        Map<String, Integer> committedByDivId = new HashMap<>();
        int mobileActiveShips = 0;
        float stationDp = 0f;
        if (currentlyDeployed != null) {
            for (int i = 0, n = currentlyDeployed.size(); i < n; i++) {
                FleetMemberAPI d = currentlyDeployed.get(i);
                if (d == null) continue;
                if (d.isStation()) {
                    stationDp += d.getDeploymentPointsCost();
                } else if (d.isAlly() == allyOnlyMode) {
                    mobileActiveShips++;
                }
                if (d.isAlly() != allyOnlyMode || d.getId() == null) continue;
                PermanentDivisionSpec spec = divisionByMemberId.get(d.getId());
                if (spec != null) {
                    committedByDivId.put(spec.id, committedByDivId.getOrDefault(spec.id, 0) + 1);
                }
            }
        }
        if (deployingSoFar != null) {
            for (int i = 0, n = deployingSoFar.size(); i < n; i++) {
                FleetMemberAPI q = deployingSoFar.get(i);
                if (q == null) continue;
                if (q.isStation()) {
                    stationDp += q.getDeploymentPointsCost();
                } else if (q.isAlly() == allyOnlyMode) {
                    mobileActiveShips++;
                }
                if (q.isAlly() != allyOnlyMode || q.getId() == null) continue;
                PermanentDivisionSpec spec = divisionByMemberId.get(q.getId());
                if (spec != null) {
                    committedByDivId.put(spec.id, committedByDivId.getOrDefault(spec.id, 0) + 1);
                }
            }
        }

        PermanentDivisionSpec candSpec = (candidate.getId() != null) ? divisionByMemberId.get(candidate.getId()) : null;
        String candDivId = (candSpec != null) ? candSpec.id : "__UNASSIGNED__";

        // Mid-Battle Trickle-Reinforcement Batching Guard (station-aware & deployability-aware!):
        // Runs BEFORE `eligiblePeers.size() <= 1` so mixed-DP or mixed-bucket Cruiser/Capital pairs
        // (e.g. Eagle+Dominator or Onslaught+Legion) wait for 2 hulls to fit at >= 72% mobile field strength,
        // while NEVER starving orbital-station battles or divisions whose 2nd member cannot deploy mid-battle.
        if (candSpec != null
                && committedByDivId.getOrDefault(candDivId, 0) == 0
                && candSpec.memberIds != null
                && candSpec.memberIds.size() >= 2
                && (bucket == VanillaWheelBucket.CRUISER_COMBAT
                || bucket == VanillaWheelBucket.CAPITAL_COMBAT
                || bucket == VanillaWheelBucket.CAPITAL_CARRIER
                || bucket == VanillaWheelBucket.OTHER_CARRIER)) {
            float mobileMaxDp = Math.max(0f, fleetManager.getMaxStrength() - stationDp);
            float mobileCurDp = Math.max(0f, (fleetManager.getCurrStrength() + dpQueued) - stationDp);
            if (mobileActiveShips >= 4 && mobileMaxDp > 0f && mobileCurDp >= mobileMaxDp * 0.72f) {
                List<Float> deployableDivCosts = collectDeployableReserveDivCosts(
                        candDivId,
                        rawReserves,
                        queuedIds,
                        allyOnlyMode,
                        divisionByMemberId,
                        alliedPursuit,
                        fieldOrQueueHasNonCivilian,
                        reserveHasNormalCombat,
                        softHullLimits,
                        softFilterLimits,
                        currentlyDeployed,
                        deployingSoFar,
                        upstreamDelegate);
                if (deployableDivCosts.size() >= 2) {
                    float cheapestPairDp = deployableDivCosts.get(0).floatValue() + deployableDivCosts.get(1).floatValue();
                    // Only hold for a 2-ship batch if 2 ships CAN actually fit into mobileMaxDp once mobile hulls fall!
                    if (cheapestPairDp <= mobileMaxDp + 0.01f) {
                        int fitInRemaining = countFittingSortedCosts(deployableDivCosts, remainingDp);
                        if (fitInRemaining < 2) {
                            return CohesiveDecision.HOLD_FOR_PAIR_AT_HIGH_DP;
                        }
                    }
                }
            }
        }

        if (eligiblePeers.size() <= 1) {
            return CohesiveDecision.ALLOW;
        }

        // Collect all side members (deployed + reserve) so mixed-bucket divisions (e.g. Onslaught + Legion)
        // recognize their Fleet Commander / flagship regardless of which vanilla wheel bucket is queried
        Set<String> commanderDivIds = new HashSet<>();
        PersonAPI fleetCmdr = fleetManager.getFleetCommander();
        markCommanderDivisions(currentlyDeployed, allyOnlyMode, fleetCmdr, divisionByMemberId, commanderDivIds);
        markCommanderDivisions(rawReserves, allyOnlyMode, fleetCmdr, divisionByMemberId, commanderDivIds);

        // Score every division represented among eligiblePeers
        String bestDivId = null;
        float bestDivScore = -1e9f;
        Map<String, Float> scoreCache = new HashMap<>();

        for (int i = 0, n = eligiblePeers.size(); i < n; i++) {
            FleetMemberAPI peer = eligiblePeers.get(i);
            PermanentDivisionSpec spec = (peer.getId() != null) ? divisionByMemberId.get(peer.getId()) : null;
            String divId = (spec != null) ? spec.id : "__UNASSIGNED__";
            Float cachedScore = scoreCache.get(divId);
            if (cachedScore == null) {
                float s = scoreDivisionForBucket(
                        spec, divId, eligiblePeers, divisionByMemberId, committedByDivId,
                        commanderDivIds.contains(divId), remainingDp);
                cachedScore = Float.valueOf(s);
                scoreCache.put(divId, cachedScore);
            }
            float score = cachedScore.floatValue();
            if (score > bestDivScore || (score == bestDivScore && bestDivId != null && divId.compareTo(bestDivId) < 0)) {
                bestDivScore = score;
                bestDivId = divId;
            }
        }

        if (!candDivId.equals(bestDivId)) {
            return CohesiveDecision.VETO_FOR_BETTER_PEER;
        }

        // Within the winning division: if its designated Division Leader (flagshipMemberId) is ALSO in
        // eligiblePeers right now (same wheel bucket, fits remainingDp, not yet deployed), deploy the
        // Division Leader first so partial DP caps never leave the division's officer sitting in reserve!
        if (candSpec != null && candSpec.flagshipMemberId != null
                && !candSpec.flagshipMemberId.equals(candidate.getId())) {
            for (int i = 0, n = eligiblePeers.size(); i < n; i++) {
                FleetMemberAPI peer = eligiblePeers.get(i);
                if (peer != null && candSpec.flagshipMemberId.equals(peer.getId())) {
                    return CohesiveDecision.VETO_FOR_BETTER_PEER;
                }
            }
        }
        return CohesiveDecision.ALLOW;
    }

    /**
     * Creates a live {@link AdmiralAIPlugin.AdmiralPluginDelegate} with explicit roster/upstream injection
     * so unit tests can exercise stateful circuit-breaker and delegate-chaining behavior directly.
     */
    public static AdmiralAIPlugin.AdmiralPluginDelegate createDelegateForTesting(
            CombatFleetManagerAPI fleetManager,
            CombatFleetManagerAPI opposingFleetManager,
            boolean allyOnlyMode,
            AdmiralAIPlugin.AdmiralPluginDelegate upstreamDelegate,
            Map<String, PermanentDivisionSpec> explicitRoster) {
        CohesiveAdmiralDelegate d = new CohesiveAdmiralDelegate(
                fleetManager, opposingFleetManager, null, allyOnlyMode, upstreamDelegate);
        if (explicitRoster != null) {
            d.cachedRoster = explicitRoster;
            d.cachedMemberCount = explicitRoster.size();
        }
        return d;
    }

    private static List<Float> collectDeployableReserveDivCosts(
            String divId,
            List<FleetMemberAPI> rawReserves,
            Set<String> queuedIds,
            boolean allyOnlyMode,
            Map<String, PermanentDivisionSpec> divisionByMemberId,
            boolean alliedPursuit,
            boolean fieldOrQueueHasNonCivilian,
            boolean reserveHasNormalCombat,
            Map<String, Integer> softHullLimits,
            Map<?, Integer> softFilterLimits,
            List<FleetMemberAPI> currentlyDeployed,
            List<FleetMemberAPI> deployingSoFar,
            AdmiralAIPlugin.AdmiralPluginDelegate upstreamDelegate) {
        List<Float> costs = new ArrayList<>(4);
        for (int i = 0, n = rawReserves.size(); i < n; i++) {
            FleetMemberAPI p = rawReserves.get(i);
            if (p == null || p.isAlly() != allyOnlyMode) continue;
            if (p.getId() == null || queuedIds.contains(p.getId())) continue;
            PermanentDivisionSpec sp = divisionByMemberId.get(p.getId());
            if (sp == null || !divId.equals(sp.id)) continue;
            // Exclude division members that vanilla will refuse to deploy mid-battle right now!
            if (p.isCivilian() && fieldOrQueueHasNonCivilian) continue;
            if (isWinglessCarrier(p) && reserveHasNormalCombat) continue;
            float cr = (p.getRepairTracker() != null) ? p.getRepairTracker().getCR() : 1.0f;
            if (alliedPursuit && cr <= 0.10f) continue;
            if (isBlockedBySoftDeploymentLimit(p, softHullLimits, softFilterLimits, currentlyDeployed, deployingSoFar)) {
                continue;
            }
            if (upstreamDelegate != null && !safeUpstreamAllowed(upstreamDelegate, deployingSoFar, p)) {
                continue;
            }
            costs.add(Float.valueOf(p.getDeploymentPointsCost()));
        }
        Collections.sort(costs);
        return costs;
    }

    private static int countFittingSortedCosts(List<Float> sortedCosts, float remainingDp) {
        float sum = 0f;
        int fit = 0;
        for (int i = 0, n = sortedCosts.size(); i < n; i++) {
            float c = sortedCosts.get(i).floatValue();
            if (sum + c <= remainingDp + 0.01f) {
                sum += c;
                fit++;
            }
        }
        return fit;
    }

    private static boolean isAlliedPursuitBattle(
            CombatFleetManagerAPI fleetManager,
            CombatFleetManagerAPI opposingFleetManager,
            boolean allyOnlyMode) {
        if (!allyOnlyMode || fleetManager == null) return false;
        if (fleetManager.getOwner() != 0 || fleetManager.getGoal() != FleetGoal.ATTACK) {
            return false;
        }
        CombatFleetManagerAPI enemyFm = opposingFleetManager;
        if (enemyFm == null) {
            try {
                CombatEngineAPI eng = Global.getCombatEngine();
                if (eng != null && !BattlefieldFirepowerEvaluator.isSimulationEngine(eng)) {
                    enemyFm = eng.getFleetManager(FleetSide.ENEMY);
                }
            } catch (Throwable ignored) {
            }
        }
        return enemyFm != null && enemyFm.getGoal() == FleetGoal.ESCAPE;
    }

    private static boolean safeUpstreamAllowed(
            AdmiralAIPlugin.AdmiralPluginDelegate upstream,
            List<FleetMemberAPI> deployingSoFar,
            FleetMemberAPI member) {
        if (upstream == null) return true;
        try {
            List<FleetMemberAPI> copy = (deployingSoFar != null)
                    ? new ArrayList<>(deployingSoFar)
                    : new ArrayList<FleetMemberAPI>();
            return upstream.allowedToDeploy(copy, member);
        } catch (Throwable ignored) {
            return true;
        }
    }

    // =========================================================================
    // 0.98a-RC8 & 0.98.5a-RC9+ MethodHandles Bridges (Zero java.lang.reflect.*)
    // =========================================================================

    static AdmiralAIPlugin.AdmiralPluginDelegate getExistingDelegate(AdmiralAIPlugin admiralAI) {
        if (admiralAI == null) return null;
        try {
            Class<?> cls = admiralAI.getClass();
            Object cached = GET_DELEGATE_HANDLES.get(cls);
            if (cached == null) {
                Object resolved = MISSING_HANDLE;
                for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                    try {
                        resolved = MethodHandles.publicLookup().findVirtual(
                                c,
                                "getDelegate",
                                MethodType.methodType(AdmiralAIPlugin.AdmiralPluginDelegate.class));
                        break;
                    } catch (Throwable ignored) {
                    }
                }
                GET_DELEGATE_HANDLES.putIfAbsent(cls, resolved);
                cached = resolved;
            }
            if (cached instanceof MethodHandle) {
                return (AdmiralAIPlugin.AdmiralPluginDelegate) ((MethodHandle) cached).invoke(admiralAI);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object getAdmiralConfig(AdmiralAIPlugin admiralAI) {
        if (admiralAI == null) return null;
        try {
            Class<?> cls = admiralAI.getClass();
            Object cached = GET_CONFIG_HANDLES.get(cls);
            if (cached == null) {
                Object resolved = MISSING_HANDLE;
                try {
                    Class<?> cfgCls = Class.forName(
                            "com.fs.starfarer.api.combat.AdmiralAIConfig", false, cls.getClassLoader());
                    for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                        try {
                            resolved = MethodHandles.publicLookup().findVirtual(
                                    c, "getConfig", MethodType.methodType(cfgCls));
                            break;
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
                GET_CONFIG_HANDLES.putIfAbsent(cls, resolved);
                cached = resolved;
            }
            if (cached instanceof MethodHandle) {
                return ((MethodHandle) cached).invoke(admiralAI);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> getSoftDeploymentLimitsMap(Object admiralConfig) {
        if (admiralConfig == null) return null;
        try {
            Class<?> cls = admiralConfig.getClass();
            Object cached = SOFT_LIMITS_GETTER_HANDLES.get(cls);
            if (cached == null) {
                Object resolved = MISSING_HANDLE;
                try {
                    // Added in Starsector 0.98.5a-RC9 (e.g. "fabricator_unit" -> ThreatCombatStrategyAI.MAX_FABRICATORS)
                    resolved = MethodHandles.publicLookup().findGetter(cls, "softDeploymentLimits", Map.class);
                } catch (Throwable ignored) {
                }
                SOFT_LIMITS_GETTER_HANDLES.putIfAbsent(cls, resolved);
                cached = resolved;
            }
            if (cached instanceof MethodHandle) {
                return (Map<String, Integer>) ((MethodHandle) cached).invoke(admiralConfig);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<?, Integer> getSoftDeploymentLimitFiltersMap(Object admiralConfig) {
        if (admiralConfig == null) return null;
        try {
            Class<?> cls = admiralConfig.getClass();
            Object cached = SOFT_FILTER_LIMITS_GETTER_HANDLES.get(cls);
            if (cached == null) {
                Object resolved = MISSING_HANDLE;
                try {
                    resolved = MethodHandles.publicLookup().findGetter(cls, "softDeploymentLimitFilters", Map.class);
                } catch (Throwable ignored) {
                }
                SOFT_FILTER_LIMITS_GETTER_HANDLES.putIfAbsent(cls, resolved);
                cached = resolved;
            }
            if (cached instanceof MethodHandle) {
                return (Map<?, Integer>) ((MethodHandle) cached).invoke(admiralConfig);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean invokeSoftFilterMatches(Object filterObj, FleetMemberAPI member) {
        if (filterObj == null || member == null) return false;
        try {
            Class<?> cls = filterObj.getClass();
            Object cached = FILTER_MATCHES_HANDLES.get(cls);
            if (cached == null) {
                Object resolved = MISSING_HANDLE;
                try {
                    // Bind against the public FleetMemberFilter interface first so synthetic Java lambdas
                    // and anonymous inner classes never fail publicLookup() access checks!
                    Class<?> filterIface = Class.forName(
                            "com.fs.starfarer.api.combat.AdmiralAIConfig$FleetMemberFilter",
                            false,
                            cls.getClassLoader());
                    if (filterIface.isAssignableFrom(cls)) {
                        resolved = MethodHandles.publicLookup().findVirtual(
                                filterIface, "matches", MethodType.methodType(boolean.class, FleetMemberAPI.class));
                    }
                } catch (Throwable ignored) {
                }
                if (resolved == MISSING_HANDLE) {
                    try {
                        resolved = MethodHandles.publicLookup().findVirtual(
                                cls, "matches", MethodType.methodType(boolean.class, FleetMemberAPI.class));
                    } catch (Throwable ignored) {
                    }
                }
                FILTER_MATCHES_HANDLES.putIfAbsent(cls, resolved);
                cached = resolved;
            }
            if (cached instanceof MethodHandle) {
                return (boolean) ((MethodHandle) cached).invoke(filterObj, member);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isBlockedBySoftDeploymentLimit(
            FleetMemberAPI member,
            Map<String, Integer> hullLimits,
            Map<?, Integer> filterLimits,
            List<FleetMemberAPI> currentlyDeployed,
            List<FleetMemberAPI> deployingSoFar) {
        if (member == null) return false;
        if (hullLimits != null && !hullLimits.isEmpty()) {
            String baseHullId = (member.getHullSpec() != null) ? member.getHullSpec().getBaseHullId() : null;
            if (baseHullId != null) {
                Integer limit = hullLimits.get(baseHullId);
                if (limit != null) {
                    int active = countMatchingBaseHullId(baseHullId, currentlyDeployed)
                            + countMatchingBaseHullId(baseHullId, deployingSoFar);
                    if (active >= limit.intValue()) {
                        return true;
                    }
                }
            }
        }
        if (filterLimits != null && !filterLimits.isEmpty()) {
            for (Map.Entry<?, Integer> entry : filterLimits.entrySet()) {
                Object filterObj = entry.getKey();
                Integer limit = entry.getValue();
                if (filterObj == null || limit == null) continue;
                if (invokeSoftFilterMatches(filterObj, member)) {
                    int active = countMatchingFilter(filterObj, currentlyDeployed)
                            + countMatchingFilter(filterObj, deployingSoFar);
                    if (active >= limit.intValue()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int countMatchingBaseHullId(String baseHullId, List<FleetMemberAPI> list) {
        if (list == null || list.isEmpty()) return 0;
        int count = 0;
        for (int i = 0, n = list.size(); i < n; i++) {
            FleetMemberAPI m = list.get(i);
            if (m != null && m.getHullSpec() != null && baseHullId.equals(m.getHullSpec().getBaseHullId())) {
                count++;
            }
        }
        return count;
    }

    private static int countMatchingFilter(Object filterObj, List<FleetMemberAPI> list) {
        if (list == null || list.isEmpty()) return 0;
        int count = 0;
        for (int i = 0, n = list.size(); i < n; i++) {
            FleetMemberAPI m = list.get(i);
            if (m != null && invokeSoftFilterMatches(filterObj, m)) {
                count++;
            }
        }
        return count;
    }

    private static void markCommanderDivisions(
            List<FleetMemberAPI> members,
            boolean allyOnlyMode,
            PersonAPI fleetCmdr,
            Map<String, PermanentDivisionSpec> divisionByMemberId,
            Set<String> outDivIds) {
        if (members == null) return;
        for (int i = 0, n = members.size(); i < n; i++) {
            FleetMemberAPI m = members.get(i);
            if (m == null || m.isAlly() != allyOnlyMode || m.getId() == null) continue;
            PermanentDivisionSpec spec = divisionByMemberId.get(m.getId());
            if (spec == null) continue;
            PersonAPI cap = m.getCaptain();
            if (m.isFlagship() || (fleetCmdr != null && cap == fleetCmdr && !cap.isDefault())) {
                outDivIds.add(spec.id);
            }
        }
    }

    private static float scoreDivisionForBucket(
            PermanentDivisionSpec spec,
            String divId,
            List<FleetMemberAPI> eligiblePeers,
            Map<String, PermanentDivisionSpec> divisionByMemberId,
            Map<String, Integer> committedByDivId,
            boolean divHasFleetCommander,
            float remainingDp) {
        if (spec == null || "__UNASSIGNED__".equals(divId)) {
            // Unassigned independent hulls deploy only after organized divisions in the same role bucket
            return -5000f;
        }
        int committed = committedByDivId.getOrDefault(divId, 0);
        int bucketPeersInDiv = 0;
        List<Float> peerDpCosts = new ArrayList<>(6);
        boolean hasFleetCommander = divHasFleetCommander;
        int maxOfficerLevel = 0;
        float totalFp = 0f;

        for (int i = 0, n = eligiblePeers.size(); i < n; i++) {
            FleetMemberAPI p = eligiblePeers.get(i);
            PermanentDivisionSpec pSpec = (p.getId() != null) ? divisionByMemberId.get(p.getId()) : null;
            if (pSpec == null || !divId.equals(pSpec.id)) continue;
            bucketPeersInDiv++;
            totalFp += p.getFleetPointCost();
            peerDpCosts.add(Float.valueOf(p.getDeploymentPointsCost()));
            PersonAPI cap = p.getCaptain();
            if (cap != null && !cap.isDefault()) {
                if (p.isFlagship()) {
                    hasFleetCommander = true;
                }
                int lvl = cap.getStats() != null ? cap.getStats().getLevel() : 0;
                if (lvl > maxOfficerLevel) {
                    maxOfficerLevel = lvl;
                }
            } else if (p.isFlagship()) {
                hasFleetCommander = true;
            }
        }

        Collections.sort(peerDpCosts);
        int affordableFitCount = countFittingSortedCosts(peerDpCosts, remainingDp);

        float score = 0f;
        // Tier 1: Already-committed division on the field or in deployingSoFar -> finish deploying its wingmen!
        if (committed > 0) {
            score += 100_000f + committed * 2_500f;
        }
        // Tier 2: OTC / Fleet Flagship's division takes priority when opening a new division in its role
        if (hasFleetCommander) {
            score += 20_000f;
        }
        // Tier 3: Prefer intact multi-ship divisions whose wingmen fit into remainingDp over 1-ship fragments
        int totalUnitSize = Math.max(bucketPeersInDiv + committed, spec.memberIds != null ? spec.memberIds.size() : 1);
        score += Math.min(4, affordableFitCount) * 1_800f;
        score += Math.min(5, totalUnitSize) * 450f;
        score += maxOfficerLevel * 120f;
        score += Math.min(120f, totalFp * 2.5f);
        return score;
    }

    private static float computeAvailableSideDp(
            CombatFleetManagerAPI fm,
            boolean allyOnlyMode,
            List<FleetMemberAPI> reserves) {
        float rawAvail = fm.getMaxStrength() - fm.getCurrStrength();
        if (!allyOnlyMode || rawAvail <= 0f) {
            return Math.max(0f, rawAvail);
        }
        // Mirror vanilla admiral.G.\u00d3\u00d8O000() ally DP share reservation so we match its exact budget
        float totalCombatDp = 0f;
        float playerCombatDp = 0f;
        float playerFlagshipDp = 0f;
        for (int i = 0, n = reserves.size(); i < n; i++) {
            FleetMemberAPI m = reserves.get(i);
            if (m == null || m.isCivilian()) continue;
            float dp = m.getDeploymentPointsCost();
            totalCombatDp += dp;
            if (!m.isAlly()) {
                playerCombatDp += dp;
                if (m.isFlagship()) {
                    playerFlagshipDp = dp;
                }
            }
        }
        if (totalCombatDp <= 0f) {
            return Math.max(0f, rawAvail);
        }
        return (float) Math.floor(Math.min(
                rawAvail * (totalCombatDp - playerCombatDp) / totalCombatDp,
                rawAvail - playerFlagshipDp));
    }

    /**
     * Builds the complete {@code memberId -> PermanentDivisionSpec} roster across all deployed + reserve
     * ships of a side, using campaign NPC divisions when available and falling back to deterministic
     * doctrinal chunking for custom missions / skirmishes.
     */
    static Map<String, PermanentDivisionSpec> buildDivisionRosterForFleetSide(
            CombatFleetManagerAPI fm,
            boolean allyOnlyMode) {
        List<FleetMemberAPI> allMembers = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        collectSideMembers(fm.getDeployedCopy(), allyOnlyMode, allMembers, seenIds);
        collectSideMembers(fm.getReservesCopy(), allyOnlyMode, allMembers, seenIds);
        if (allMembers.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, PermanentDivisionSpec> campaignMap =
                FactionFleetDoctrines.buildCampaignDivisionMapForMembers(allMembers);
        if (!campaignMap.isEmpty()) {
            return campaignMap;
        }

        // Fallback for standalone/custom skirmish missions without a CampaignFleetAPI:
        // Group non-civilian hulls deterministically into 4-ship doctrinal divisions by archetype.
        Map<DivisionArchetype, List<FleetMemberAPI>> byArch = new LinkedHashMap<>();
        for (int i = 0, n = allMembers.size(); i < n; i++) {
            FleetMemberAPI m = allMembers.get(i);
            if (m == null || m.getId() == null || m.isFighterWing()) continue;
            if (m.isCivilian() && !ShipCapabilityProfile.isCombatFreighterHull(m.getHullSpec())) continue;
            DivisionArchetype arch = PermanentDivisionManager.classifyFleetMemberArchetype(m);
            if (arch == null) arch = DivisionArchetype.CRUISER_DIVISION;
            List<FleetMemberAPI> bucket = byArch.get(arch);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byArch.put(arch, bucket);
            }
            bucket.add(m);
        }
        Map<String, PermanentDivisionSpec> syntheticMap = new HashMap<>();
        int seq = 1;
        for (Map.Entry<DivisionArchetype, List<FleetMemberAPI>> entry : byArch.entrySet()) {
            List<FleetMemberAPI> group = entry.getValue();
            Collections.sort(group, new Comparator<FleetMemberAPI>() {
                @Override
                public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                    int fpCmp = Float.compare(b.getFleetPointCost(), a.getFleetPointCost());
                    if (fpCmp != 0) return fpCmp;
                    return String.valueOf(a.getId()).compareTo(String.valueOf(b.getId()));
                }
            });
            for (int idx = 0; idx < group.size(); idx += 4) {
                int end = Math.min(group.size(), idx + 4);
                String specId = "skirmish_div_" + (seq++);
                PermanentDivisionSpec spec = new PermanentDivisionSpec(specId, specId, entry.getKey());
                spec.flagshipMemberId = group.get(idx).getId();
                for (int k = idx; k < end; k++) {
                    String mId = group.get(k).getId();
                    spec.memberIds.add(mId);
                    syntheticMap.put(mId, spec);
                }
            }
        }
        return syntheticMap;
    }

    private static void collectSideMembers(
            Collection<FleetMemberAPI> src,
            boolean allyOnlyMode,
            List<FleetMemberAPI> out,
            Set<String> seenIds) {
        if (src == null) return;
        for (FleetMemberAPI m : src) {
            if (m == null || m.isAlly() != allyOnlyMode || m.getId() == null) continue;
            if (seenIds.add(m.getId())) {
                out.add(m);
            }
        }
    }

    /**
     * Package-private delegate implementation with upstream mod chaining and a consecutive peer-veto
     * circuit-breaker failsafe.
     */
    static final class CohesiveAdmiralDelegate implements AdmiralAIPlugin.AdmiralPluginDelegate {
        private final CombatFleetManagerAPI fleetManager;
        private final CombatFleetManagerAPI opposingFleetManager;
        private final AdmiralAIPlugin admiralAI;
        private final boolean allyOnlyMode;
        private AdmiralAIPlugin.AdmiralPluginDelegate upstreamDelegate;
        int reattachCount = 0;

        Map<String, PermanentDivisionSpec> cachedRoster = null;
        int cachedMemberCount = -1;

        // Stateful circuit-breaker tracking against unforeseen modded admiral.G predicate overrides
        private int lastDeployedCount = -1;
        private int lastQueueSize = -1;
        private int consecutivePeerVetoes = 0;
        private boolean circuitBreakerOpen = false;
        private boolean loggedCircuitBreakerWarning = false;

        CohesiveAdmiralDelegate(
                CombatFleetManagerAPI fleetManager,
                CombatFleetManagerAPI opposingFleetManager,
                AdmiralAIPlugin admiralAI,
                boolean allyOnlyMode,
                AdmiralAIPlugin.AdmiralPluginDelegate upstreamDelegate) {
            this.fleetManager = fleetManager;
            this.opposingFleetManager = opposingFleetManager;
            this.admiralAI = admiralAI;
            this.allyOnlyMode = allyOnlyMode;
            setUpstreamDelegate(upstreamDelegate);
        }

        void setUpstreamDelegate(AdmiralAIPlugin.AdmiralPluginDelegate upstream) {
            if (upstream != this && !(upstream instanceof CohesiveAdmiralDelegate)) {
                this.upstreamDelegate = upstream;
            }
        }

        private Map<String, PermanentDivisionSpec> ensureRoster() {
            int depCount = fleetManager.getDeployedCopy() != null ? fleetManager.getDeployedCopy().size() : 0;
            int resCount = fleetManager.getReservesCopy() != null ? fleetManager.getReservesCopy().size() : 0;
            int total = depCount + resCount;
            if (cachedRoster == null || (cachedRoster.isEmpty() && total != cachedMemberCount)) {
                cachedRoster = buildDivisionRosterForFleetSide(fleetManager, allyOnlyMode);
                cachedMemberCount = total;
                if (!cachedRoster.isEmpty()) {
                    Set<String> uniqueDivs = new HashSet<>();
                    for (PermanentDivisionSpec s : cachedRoster.values()) {
                        if (s != null) uniqueDivs.add(s.id);
                    }
                    LOG.info(String.format(
                            "[DivisionAI] Attached division-cohesive deployment planner (%s%s): %d ships mapped across %d divisions.",
                            allyOnlyMode ? "Allied AI" : "Enemy AI",
                            upstreamDelegate != null ? ", chained=" + upstreamDelegate.getClass().getName() : "",
                            cachedRoster.size(),
                            uniqueDivs.size()));
                }
            }
            return cachedRoster;
        }

        @Override
        public void doAdditionalInitialDeployment() {
            if (upstreamDelegate != null) {
                try {
                    upstreamDelegate.doAdditionalInitialDeployment();
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public boolean allowedToDeploy(List<FleetMemberAPI> deployingSoFar, FleetMemberAPI candidate) {
            try {
                // 1. Always honor any upstream mod/boss delegate first (without counting against our circuit breaker)
                if (upstreamDelegate != null && !safeUpstreamAllowed(upstreamDelegate, deployingSoFar, candidate)) {
                    return false;
                }

                // 2. Reset circuit-breaker state whenever field deployment or deployingSoFar progresses
                List<FleetMemberAPI> dep = fleetManager.getDeployedCopy();
                int depCount = (dep != null) ? dep.size() : 0;
                int qSize = (deployingSoFar != null) ? deployingSoFar.size() : 0;
                if (depCount != lastDeployedCount || qSize != lastQueueSize) {
                    lastDeployedCount = depCount;
                    lastQueueSize = qSize;
                    consecutivePeerVetoes = 0;
                    circuitBreakerOpen = false;
                }

                if (circuitBreakerOpen) {
                    return true;
                }

                Map<String, PermanentDivisionSpec> roster = ensureRoster();
                if (roster == null || roster.isEmpty()) {
                    return true;
                }

                CohesiveDecision decision = evaluateCandidateForCohesiveDeployment(
                        fleetManager,
                        opposingFleetManager,
                        admiralAI,
                        upstreamDelegate,
                        null,
                        allyOnlyMode,
                        roster,
                        deployingSoFar,
                        candidate);

                if (decision == CohesiveDecision.ALLOW) {
                    consecutivePeerVetoes = 0;
                    return true;
                }
                if (decision == CohesiveDecision.VETO_FOR_BETTER_PEER) {
                    consecutivePeerVetoes++;
                    List<FleetMemberAPI> res = fleetManager.getReservesCopy();
                    int resCount = (res != null) ? res.size() : 1;
                    int tripThreshold = Math.max(16, resCount * 3);
                    if (consecutivePeerVetoes >= tripThreshold) {
                        circuitBreakerOpen = true;
                        if (!loggedCircuitBreakerWarning) {
                            loggedCircuitBreakerWarning = true;
                            LOG.warn(String.format(
                                    "[DivisionAI] Deployment planner circuit-breaker tripped after %d consecutive peer vetoes (%s, reserves=%d, queued=%d) — failing open so deployment cannot stall.",
                                    consecutivePeerVetoes,
                                    allyOnlyMode ? "Allied AI" : "Enemy AI",
                                    resCount,
                                    qSize));
                        }
                        return true;
                    }
                }
                return false;
            } catch (Throwable ignored) {
                return true;
            }
        }
    }
}
