package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.AdmiralAIPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.mission.FleetSide;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import org.apache.log4j.Logger;

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
 * {@code 0.98a-RC8} and {@code 0.98.5a}) to both the Enemy {@link AdmiralAIPlugin} and the Allied AI
 * {@link AdmiralAIPlugin}. Within each vanilla wheel role category, it enforces:
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
 * <p>Partitioning strictly by vanilla's exact role-slot signature and DP/CR eligibility guarantees that whenever
 * vanilla's wheel asks for a role that exists in reserve within the remaining DP budget, at least one ship is
 * always permitted — never starving {@code admiral.G}'s 30-retry counter or leaving unused DP on the table.
 */
public final class DivisionDeploymentPlanner {

    private static final Logger LOG = Global.getLogger(DivisionDeploymentPlanner.class);

    /**
     * Exact disjoint partition of {@code FleetMemberAPI}s aligned with vanilla {@code admiral.G}'s wheel predicates
     * so every non-empty wheel slot always has an eligible designated division.
     */
    enum VanillaWheelBucket {
        CAPITAL_CARRIER,
        OTHER_CARRIER,
        CAPITAL_COMBAT,
        CRUISER_COMBAT,
        DESTROYER_COMBAT,
        FRIGATE_OR_WING,
        CIVILIAN_ONLY
    }

    private static final IdentityHashMap<AdmiralAIPlugin, CohesiveAdmiralDelegate> ATTACHED_DELEGATES =
            new IdentityHashMap<>();

    private DivisionDeploymentPlanner() {
    }

    public static void resetForNewBattle() {
        ATTACHED_DELEGATES.clear();
    }

    /**
     * Attaches cohesive division deployment delegates onto Enemy and Allied AI {@link AdmiralAIPlugin} instances.
     * Safe to invoke every frame (O(1) identity check once attached).
     */
    public static void ensureAttached(CombatEngineAPI engine) {
        if (engine == null || BattlefieldFirepowerEvaluator.isSimulationEngine(engine)) {
            return;
        }
        attachForFleetSide(engine, FleetSide.ENEMY, false);
        attachForFleetSide(engine, FleetSide.PLAYER, true);
    }

    private static void attachForFleetSide(CombatEngineAPI engine, FleetSide side, boolean allyOnlyMode) {
        CombatFleetManagerAPI fm = engine.getFleetManager(side);
        if (fm == null) return;
        AdmiralAIPlugin admiralAI = fm.getAdmiralAI();
        if (admiralAI == null || ATTACHED_DELEGATES.containsKey(admiralAI)) {
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
        CohesiveAdmiralDelegate delegate = new CohesiveAdmiralDelegate(fm, allyOnlyMode);
        admiralAI.setDelegate(delegate);
        ATTACHED_DELEGATES.put(admiralAI, delegate);
    }

    /**
     * Classifies a {@link FleetMemberAPI} into the exact disjoint bucket used by vanilla {@code admiral.G}'s
     * reinforcement picker.
     */
    static VanillaWheelBucket classifyWheelBucket(FleetMemberAPI fm) {
        if (fm == null) return VanillaWheelBucket.CIVILIAN_ONLY;
        boolean hasFittedWings = fm.getVariant() != null
                && fm.getVariant().getFittedWings() != null
                && !fm.getVariant().getFittedWings().isEmpty();
        if (fm.isCarrier() && hasFittedWings) {
            return fm.isCapital() ? VanillaWheelBucket.CAPITAL_CARRIER : VanillaWheelBucket.OTHER_CARRIER;
        }
        if (fm.isCivilian()) {
            return VanillaWheelBucket.CIVILIAN_ONLY;
        }
        if (fm.isCapital()) {
            return VanillaWheelBucket.CAPITAL_COMBAT;
        }
        if (fm.isCruiser()) {
            return VanillaWheelBucket.CRUISER_COMBAT;
        }
        if (fm.isDestroyer()) {
            return VanillaWheelBucket.DESTROYER_COMBAT;
        }
        return VanillaWheelBucket.FRIGATE_OR_WING;
    }

    /**
     * Stateless/self-contained core decision engine (exposed package-private for direct unit testing in
     * {@code DivisionAITestSuite} as well as live {@link CohesiveAdmiralDelegate} callbacks).
     */
    public static boolean isCandidateAllowedForCohesiveDeployment(
            CombatFleetManagerAPI fleetManager,
            boolean allyOnlyMode,
            Map<String, PermanentDivisionSpec> divisionByMemberId,
            List<FleetMemberAPI> deployingSoFar,
            FleetMemberAPI candidate) {
        if (candidate == null || fleetManager == null) {
            return true;
        }
        if (candidate.isAlly() != allyOnlyMode) {
            return true;
        }
        List<FleetMemberAPI> rawReserves = fleetManager.getReservesCopy();
        if (rawReserves == null || rawReserves.size() <= 1) {
            return true;
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
            return false;
        }

        VanillaWheelBucket bucket = classifyWheelBucket(candidate);
        float candCr = (candidate.getRepairTracker() != null) ? candidate.getRepairTracker().getCR() : 1.0f;
        boolean candidateHighCr = candCr >= 0.40f;

        // Collect all peer reserve candidates in the SAME vanilla wheel bucket that fit remainingDp
        // (matching candidate's CR tier so vanilla's first-attempt `cr >= 0.4f` check never stalls!)
        List<FleetMemberAPI> eligiblePeers = new ArrayList<>();
        for (int i = 0, n = rawReserves.size(); i < n; i++) {
            FleetMemberAPI m = rawReserves.get(i);
            if (m == null || m.isAlly() != allyOnlyMode) continue;
            if (m.getId() != null && queuedIds.contains(m.getId())) continue;
            if (m.getDeploymentPointsCost() > remainingDp + 0.01f) continue;
            float mCr = (m.getRepairTracker() != null) ? m.getRepairTracker().getCR() : 1.0f;
            if (candidateHighCr && mCr < 0.40f) continue;
            if (classifyWheelBucket(m) != bucket) continue;
            eligiblePeers.add(m);
        }
        // Count already-committed hulls per division (currently deployed on map + queued in deployingSoFar)
        Map<String, Integer> committedByDivId = new HashMap<>();
        List<FleetMemberAPI> currentlyDeployed = fleetManager.getDeployedCopy();
        if (currentlyDeployed != null) {
            for (int i = 0, n = currentlyDeployed.size(); i < n; i++) {
                FleetMemberAPI d = currentlyDeployed.get(i);
                if (d == null || d.isAlly() != allyOnlyMode || d.getId() == null) continue;
                PermanentDivisionSpec spec = divisionByMemberId.get(d.getId());
                if (spec != null) {
                    committedByDivId.put(spec.id, committedByDivId.getOrDefault(spec.id, 0) + 1);
                }
            }
        }
        if (deployingSoFar != null) {
            for (int i = 0, n = deployingSoFar.size(); i < n; i++) {
                FleetMemberAPI q = deployingSoFar.get(i);
                if (q == null || q.isAlly() != allyOnlyMode || q.getId() == null) continue;
                PermanentDivisionSpec spec = divisionByMemberId.get(q.getId());
                if (spec != null) {
                    committedByDivId.put(spec.id, committedByDivId.getOrDefault(spec.id, 0) + 1);
                }
            }
        }

        PermanentDivisionSpec candSpec = (candidate.getId() != null) ? divisionByMemberId.get(candidate.getId()) : null;
        String candDivId = (candSpec != null) ? candSpec.id : "__UNASSIGNED__";

        // Mid-Battle Trickle-Reinforcement Batching Guard (runs BEFORE `eligiblePeers.size() <= 1` so mixed-DP or
        // mixed-bucket Cruiser/Capital pairs like Eagle+Dominator or Onslaught+Legion are also held cohesively!):
        if (candSpec != null
                && committedByDivId.getOrDefault(candDivId, 0) == 0
                && candSpec.memberIds != null
                && candSpec.memberIds.size() >= 2
                && (bucket == VanillaWheelBucket.CRUISER_COMBAT
                || bucket == VanillaWheelBucket.CAPITAL_COMBAT
                || bucket == VanillaWheelBucket.CAPITAL_CARRIER
                || bucket == VanillaWheelBucket.OTHER_CARRIER)) {
            int activeSideShips = (currentlyDeployed != null ? currentlyDeployed.size() : 0)
                    + (deployingSoFar != null ? deployingSoFar.size() : 0);
            float maxDp = fleetManager.getMaxStrength();
            float curDp = fleetManager.getCurrStrength() + dpQueued;
            if (activeSideShips >= 4 && maxDp > 0f && curDp >= maxDp * 0.72f) {
                int fitInCandDiv = countAffordableMembersInReserveDiv(
                        candDivId, rawReserves, queuedIds, allyOnlyMode, divisionByMemberId, remainingDp);
                if (fitInCandDiv < 2 && hasSecondMemberInReserve(candDivId, rawReserves, queuedIds, divisionByMemberId)) {
                    return false;
                }
            }
        }

        if (eligiblePeers.size() <= 1) {
            return true;
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
            return false;
        }

        // Within the winning division: if its designated Division Leader (flagshipMemberId) is ALSO in
        // eligiblePeers right now (same wheel bucket, fits remainingDp, not yet deployed), deploy the
        // Division Leader first so partial DP caps never leave the division's officer sitting in reserve!
        if (candSpec != null && candSpec.flagshipMemberId != null
                && !candSpec.flagshipMemberId.equals(candidate.getId())) {
            for (int i = 0, n = eligiblePeers.size(); i < n; i++) {
                FleetMemberAPI peer = eligiblePeers.get(i);
                if (peer != null && candSpec.flagshipMemberId.equals(peer.getId())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int countAffordableMembersInReserveDiv(
            String divId,
            List<FleetMemberAPI> rawReserves,
            Set<String> queuedIds,
            boolean allyOnlyMode,
            Map<String, PermanentDivisionSpec> divisionByMemberId,
            float remainingDp) {
        List<Float> costs = new ArrayList<>(4);
        for (int i = 0, n = rawReserves.size(); i < n; i++) {
            FleetMemberAPI p = rawReserves.get(i);
            if (p == null || p.isAlly() != allyOnlyMode) continue;
            if (p.getId() != null && queuedIds.contains(p.getId())) continue;
            PermanentDivisionSpec sp = (p.getId() != null) ? divisionByMemberId.get(p.getId()) : null;
            if (sp != null && divId.equals(sp.id)) {
                costs.add(Float.valueOf(p.getDeploymentPointsCost()));
            }
        }
        Collections.sort(costs);
        float sum = 0f;
        int fit = 0;
        for (int i = 0, n = costs.size(); i < n; i++) {
            float c = costs.get(i).floatValue();
            if (sum + c <= remainingDp + 0.01f) {
                sum += c;
                fit++;
            }
        }
        return fit;
    }

    private static boolean hasSecondMemberInReserve(
            String divId,
            List<FleetMemberAPI> rawReserves,
            Set<String> queuedIds,
            Map<String, PermanentDivisionSpec> divisionByMemberId) {
        int count = 0;
        for (int i = 0, n = rawReserves.size(); i < n; i++) {
            FleetMemberAPI m = rawReserves.get(i);
            if (m == null || m.getId() == null || queuedIds.contains(m.getId())) continue;
            PermanentDivisionSpec sp = divisionByMemberId.get(m.getId());
            if (sp != null && divId.equals(sp.id)) {
                if (++count >= 2) return true;
            }
        }
        return false;
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
        float affordableDpSum = 0f;
        int affordableFitCount = 0;
        for (int i = 0, n = peerDpCosts.size(); i < n; i++) {
            float c = peerDpCosts.get(i).floatValue();
            if (affordableDpSum + c <= remainingDp + 0.01f) {
                affordableDpSum += c;
                affordableFitCount++;
            }
        }

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

    private static final class CohesiveAdmiralDelegate implements AdmiralAIPlugin.AdmiralPluginDelegate {
        private final CombatFleetManagerAPI fleetManager;
        private final boolean allyOnlyMode;
        private Map<String, PermanentDivisionSpec> cachedRoster = null;
        private int cachedMemberCount = -1;

        CohesiveAdmiralDelegate(CombatFleetManagerAPI fleetManager, boolean allyOnlyMode) {
            this.fleetManager = fleetManager;
            this.allyOnlyMode = allyOnlyMode;
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
                            "[DivisionAI] Attached division-cohesive deployment planner (%s): %d ships mapped across %d divisions.",
                            allyOnlyMode ? "Allied AI" : "Enemy AI",
                            cachedRoster.size(),
                            uniqueDivs.size()));
                }
            }
            return cachedRoster;
        }

        @Override
        public void doAdditionalInitialDeployment() {
            // Standard initial deployment already runs through allowedToDeploy
        }

        @Override
        public boolean allowedToDeploy(List<FleetMemberAPI> deployingSoFar, FleetMemberAPI candidate) {
            try {
                Map<String, PermanentDivisionSpec> roster = ensureRoster();
                if (roster == null || roster.isEmpty()) {
                    return true;
                }
                return isCandidateAllowedForCohesiveDeployment(
                        fleetManager, allyOnlyMode, roster, deployingSoFar, candidate);
            } catch (Throwable ignored) {
                return true;
            }
        }
    }
}
