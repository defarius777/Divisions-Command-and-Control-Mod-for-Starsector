package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import divisionai.DivisionModel.BattlegroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Campaign-layer manager for Permanent Divisions:
 *  - Stores permanent divisions in SectorAPI.getPersistentData() using plain JDK Maps/Lists/Strings
 *    so XStream save serialization is 100% safe across mod updates.
 *  - Auto-initializes divisions from the player's campaign fleet on first load, and keeps member lists
 *    pruned when ships are sold/stored.
 *  - Provides CRUD operations for the Fleet Screen "Division Editor" and individual Division Screen.
 *  - Reconstructs combat DivisionState instances in the battle layer so campaign divisions are
 *    inherited directly in combat.
 */
public class PermanentDivisionManager {

    private static final Logger LOG = Global.getLogger(PermanentDivisionManager.class);
    private static final String PERSISTENT_DIVISIONS_KEY = "divisionai_permanent_divisions_v2";
    private static final String PERSISTENT_SEQ_KEY = "divisionai_permanent_divisions_seq_v2";
    private static final String PERSISTENT_DOCTRINE_KEY = "divisionai_fleet_doctrine_v1";

    /** Hard cap on division size regardless of individual hull maximums. */
    public static final int MAX_DIVISION_SIZE = 10;

    /**
     * Hull categories configurable in the Fleet Doctrine screen (minimum & maximum hulls per division).
     */
    public enum DoctrineHullCategory {
        CRUISER(
                "Cruiser Hulls",
                "Minimum cruiser hulls for division",
                "Maximum cruiser hulls for division",
                0,
                4,
                DivisionArchetype.CRUISER_DIVISION),
        CAPITAL(
                "Capital Hulls",
                "Minimum capital hulls for division",
                "Maximum capital hulls for division",
                0,
                2,
                DivisionArchetype.BATTLE_DIVISION),
        DESTROYER(
                "Destroyer Hulls",
                "Minimum destroyer hulls for division",
                "Maximum destroyer hulls for division",
                0,
                4,
                DivisionArchetype.DESTROYER_DIVISION),
        FRIGATE(
                "Frigate Hulls",
                "Minimum frigate hulls for division",
                "Maximum frigate hulls for division",
                0,
                5,
                DivisionArchetype.FRIGATE_DIVISION),
        FREIGHTER(
                "Freighter Hulls",
                "Minimum freighter hulls for division",
                "Maximum freighter hulls for division",
                0,
                4,
                DivisionArchetype.AUXILIARY_CONVOY),
        TANKER(
                "Tanker Hulls",
                "Minimum tanker hulls for division",
                "Maximum tanker hulls for division",
                0,
                4,
                DivisionArchetype.AUXILIARY_CONVOY),
        TRANSPORT(
                "Transport Hulls",
                "Minimum transport hulls for division",
                "Maximum transport hulls for division",
                0,
                4,
                DivisionArchetype.AUXILIARY_CONVOY);

        public final String displayName;
        public final String minLabel;
        public final String maxLabel;
        public final int defaultMin;
        public final int defaultMax;
        public final DivisionArchetype defaultArchetype;

        DoctrineHullCategory(
                String displayName,
                String minLabel,
                String maxLabel,
                int defaultMin,
                int defaultMax,
                DivisionArchetype defaultArchetype) {
            this.displayName = displayName;
            this.minLabel = minLabel;
            this.maxLabel = maxLabel;
            this.defaultMin = defaultMin;
            this.defaultMax = defaultMax;
            this.defaultArchetype = defaultArchetype;
        }
    }

    /**
     * Campaign-saved Fleet Doctrine settings defining minimum and maximum hulls per division
     * for each of the 7 hull categories, subject to MAX_DIVISION_SIZE = 10.
     */
    public static class FleetDoctrineConfig {
        private final Map<DoctrineHullCategory, Integer> minByCat = new LinkedHashMap<>();
        private final Map<DoctrineHullCategory, Integer> maxByCat = new LinkedHashMap<>();

        public FleetDoctrineConfig() {
            for (DoctrineHullCategory cat : DoctrineHullCategory.values()) {
                minByCat.put(cat, cat.defaultMin);
                maxByCat.put(cat, cat.defaultMax);
            }
        }

        public int getMin(DoctrineHullCategory cat) {
            Integer v = minByCat.get(cat);
            return v != null ? Math.max(0, Math.min(MAX_DIVISION_SIZE, v)) : 0;
        }

        public int getMax(DoctrineHullCategory cat) {
            Integer v = maxByCat.get(cat);
            int maxVal = v != null ? Math.max(0, Math.min(MAX_DIVISION_SIZE, v)) : cat.defaultMax;
            return Math.max(getMin(cat), maxVal);
        }

        public void setMin(DoctrineHullCategory cat, int value) {
            int clamped = Math.max(0, Math.min(MAX_DIVISION_SIZE, value));
            minByCat.put(cat, clamped);
            if (getMax(cat) < clamped) {
                maxByCat.put(cat, clamped);
            }
        }

        public void setMax(DoctrineHullCategory cat, int value) {
            int clamped = Math.max(0, Math.min(MAX_DIVISION_SIZE, value));
            maxByCat.put(cat, clamped);
            if (getMin(cat) > clamped) {
                minByCat.put(cat, clamped);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static FleetDoctrineConfig getFleetDoctrine() {
        FleetDoctrineConfig cfg = new FleetDoctrineConfig();
        SectorAPI sector = Global.getSector();
        if (sector == null) return cfg;
        Map<String, Object> pdata = sector.getPersistentData();
        Object raw = pdata.get(PERSISTENT_DOCTRINE_KEY);
        if (raw instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) raw;
            for (DoctrineHullCategory cat : DoctrineHullCategory.values()) {
                Object minObj = map.get("min_" + cat.name());
                Object maxObj = map.get("max_" + cat.name());
                if (minObj instanceof Number) {
                    cfg.setMin(cat, ((Number) minObj).intValue());
                }
                if (maxObj instanceof Number) {
                    cfg.setMax(cat, ((Number) maxObj).intValue());
                }
            }
        }
        return cfg;
    }

    public static void saveFleetDoctrine(FleetDoctrineConfig cfg) {
        SectorAPI sector = Global.getSector();
        if (sector == null || cfg == null) return;
        Map<String, Object> serialized = new HashMap<>();
        for (DoctrineHullCategory cat : DoctrineHullCategory.values()) {
            serialized.put("min_" + cat.name(), cfg.getMin(cat));
            serialized.put("max_" + cat.name(), cfg.getMax(cat));
        }
        sector.getPersistentData().put(PERSISTENT_DOCTRINE_KEY, serialized);
    }

    public static void updateFleetDoctrineLimit(DoctrineHullCategory cat, boolean isMin, int newValue) {
        if (cat == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        if (isMin) {
            cfg.setMin(cat, newValue);
        } else {
            cfg.setMax(cat, newValue);
        }
        saveFleetDoctrine(cfg);
    }

    public static void resetFleetDoctrineToDefaults() {
        saveFleetDoctrine(new FleetDoctrineConfig());
    }

    public enum DoctrineOption {
        ADAPTIVE("Adaptive C2 (Default)", "Balanced division C2: screens, flanks, or presses overmatch based on live telemetry."),
        SPEARHEAD("Aggressive Spearhead", "Commits early to line contact and presses local overmatch aggressively."),
        STANDOFF("Standoff Fire Support", "Maintains maximum weapon/fighter standoff range and avoids close brawls."),
        SCREEN_ESCORT("Screen & Escort", "Prioritizes screening friendly capital and carrier divisions against flankers."),
        FLANK_STRIKE("Flank & Torpedo Strike", "Swings wide around enemy flanks and looks for torpedo/missile strike windows."),
        RESERVE_HOLD("Tactical Reserve", "Holds behind the main battle line until a friendly division requires relief.");

        public final String displayName;
        public final String summary;

        DoctrineOption(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public static DoctrineOption fromId(String id) {
            if (id == null) return ADAPTIVE;
            try {
                return DoctrineOption.valueOf(id);
            } catch (IllegalArgumentException e) {
                return ADAPTIVE;
            }
        }
    }

    public static class PermanentDivisionSpec {
        public String id;
        public String name;
        public DivisionArchetype archetype;
        public String flagshipMemberId;
        public List<String> memberIds = new ArrayList<>();
        public DoctrineOption doctrine = DoctrineOption.ADAPTIVE;

        public PermanentDivisionSpec(String id, String name, DivisionArchetype archetype) {
            this.id = id;
            this.name = name;
            this.archetype = archetype != null ? archetype : DivisionArchetype.CRUISER_DIVISION;
        }
    }

    /**
     * Loads the player's permanent divisions from campaign persistent data and prunes any
     * ship IDs that are no longer in the player's fleet.
     * Never forcibly assigns player ships to divisions automatically.
     */
    public static List<PermanentDivisionSpec> ensureInitializedForPlayerFleet() {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPlayerFleet() == null) {
            return new ArrayList<>();
        }

        CampaignFleetAPI playerFleet = sector.getPlayerFleet();
        Map<String, Object> pdata = sector.getPersistentData();

        List<PermanentDivisionSpec> specs = loadSpecsFromPersistentData(pdata);
        pruneRemovedFleetMembers(specs, playerFleet);
        saveSpecsToPersistentData(pdata, specs);
        return specs;
    }

    public static List<PermanentDivisionSpec> getPermanentDivisions() {
        return ensureInitializedForPlayerFleet();
    }

    /**
     * Returns only the player's permanent divisions that have at least 1 ship currently in the player's fleet.
     */
    public static List<PermanentDivisionSpec> getNonEmptyPermanentDivisions() {
        List<PermanentDivisionSpec> nonEmpty = new ArrayList<>();
        for (PermanentDivisionSpec spec : ensureInitializedForPlayerFleet()) {
            if (spec != null && spec.memberIds != null && !spec.memberIds.isEmpty()) {
                nonEmpty.add(spec);
            }
        }
        return nonEmpty;
    }

    public static void clearAllDivisions() {
        SectorAPI sector = Global.getSector();
        if (sector == null) return;
        Map<String, Object> pdata = sector.getPersistentData();
        saveSpecsToPersistentData(pdata, new ArrayList<PermanentDivisionSpec>());
        LOG.info("[DivisionAI] Disbanded all permanent divisions.");
    }

    public static PermanentDivisionSpec getDivisionById(String divisionId) {
        if (divisionId == null) return null;
        for (PermanentDivisionSpec spec : ensureInitializedForPlayerFleet()) {
            if (divisionId.equals(spec.id)) {
                return spec;
            }
        }
        return null;
    }

    public static PermanentDivisionSpec getDivisionForMemberId(String fleetMemberId) {
        if (fleetMemberId == null) return null;
        for (PermanentDivisionSpec spec : ensureInitializedForPlayerFleet()) {
            if (spec.memberIds.contains(fleetMemberId)) {
                return spec;
            }
        }
        return null;
    }

    public static void resetAndAutoFormFromPlayerFleet() {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPlayerFleet() == null) return;
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = buildDefaultPermanentDivisions(sector.getPlayerFleet(), pdata);
        saveSpecsToPersistentData(pdata, specs);
        LOG.info("[DivisionAI] Reset and auto-formed " + specs.size() + " permanent divisions.");
    }

    /**
     * Auto-assigns all unassigned ships in the player's fleet:
     *  1. First assigns ships to existing divisions if there is space (filling any unmet Fleet Doctrine
     *     minimum hull counts first, then filling up to Fleet Doctrine maximum hull counts per category,
     *     strictly capped at MAX_DIVISION_SIZE = 10 ships per division).
     *  2. Then automatically creates new divisions for any remaining unassigned ships (or all ships if
     *     no divisions existed yet), following the Fleet Doctrine min/max hull limits and MAX_DIVISION_SIZE = 10.
     */
    public static void autoAssignUnassignedMembers() {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPlayerFleet() == null) return;
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();
        FleetDoctrineConfig doctrine = getFleetDoctrine();

        List<FleetMemberAPI> unassigned = getUnassignedCombatMembers(fleet, specs);
        if (unassigned.isEmpty()) return;

        Collections.sort(unassigned, new Comparator<FleetMemberAPI>() {
            @Override
            public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                return Float.compare(b.getDeploymentPointsCost(), a.getDeploymentPointsCost());
            }
        });

        Map<String, FleetMemberAPI> fleetById = mapFleetMembersById(fleet);

        // Pass 1: Satisfy unmet Fleet Doctrine minimum hull counts in existing divisions that have space (< 10)
        for (PermanentDivisionSpec spec : specs) {
            if (spec.memberIds.size() >= MAX_DIVISION_SIZE) continue;
            for (DoctrineHullCategory cat : DoctrineHullCategory.values()) {
                int minReq = doctrine.getMin(cat);
                if (minReq <= 0) continue;
                int currentCatCount = countCategoryInDivision(spec, cat, fleetById);
                while (currentCatCount < minReq && spec.memberIds.size() < MAX_DIVISION_SIZE) {
                    FleetMemberAPI match = popFirstUnassignedOfCategory(unassigned, cat);
                    if (match == null) break;
                    spec.memberIds.add(match.getId());
                    ensureValidFlagship(spec, fleet);
                    currentCatCount++;
                }
            }
        }

        // Pass 2: Assign remaining unassigned ships into existing divisions that have space
        // (up to doctrine.getMax(cat) and strictly < MAX_DIVISION_SIZE = 10)
        List<FleetMemberAPI> afterExistingPass = new ArrayList<>();
        for (FleetMemberAPI m : unassigned) {
            DoctrineHullCategory cat = classifyHullCategory(m);
            DivisionArchetype desiredArch = classifyFleetMemberArchetype(m);
            int catMax = doctrine.getMax(cat);

            PermanentDivisionSpec bestMatch = null;
            if (catMax > 0) {
                // First prefer an existing division of the matching archetype
                for (PermanentDivisionSpec spec : specs) {
                    if (spec.memberIds.size() >= MAX_DIVISION_SIZE) continue;
                    if (spec.archetype == desiredArch
                            && countCategoryInDivision(spec, cat, fleetById) < catMax) {
                        bestMatch = spec;
                        break;
                    }
                }
                // Next check any existing division that already includes this hull category or has composite min > 0
                if (bestMatch == null) {
                    for (PermanentDivisionSpec spec : specs) {
                        if (spec.memberIds.size() >= MAX_DIVISION_SIZE) continue;
                        int currCat = countCategoryInDivision(spec, cat, fleetById);
                        if (currCat < catMax && (currCat > 0 || doctrine.getMin(cat) > 0)) {
                            bestMatch = spec;
                            break;
                        }
                    }
                }
            }

            if (bestMatch != null) {
                bestMatch.memberIds.add(m.getId());
                ensureValidFlagship(bestMatch, fleet);
            } else {
                afterExistingPass.add(m);
            }
        }
        unassigned = afterExistingPass;

        // Pass 3: Automatically create new divisions for all remaining unassigned ships
        boolean hasAnyPositiveMin = false;
        for (DoctrineHullCategory cat : DoctrineHullCategory.values()) {
            if (doctrine.getMin(cat) > 0) {
                hasAnyPositiveMin = true;
                break;
            }
        }

        // 3a. If the player configured positive minimums in Fleet Doctrine, form composite divisions first
        if (hasAnyPositiveMin && !unassigned.isEmpty()) {
            while (!unassigned.isEmpty()) {
                List<FleetMemberAPI> seedMembers = new ArrayList<>();
                for (DoctrineHullCategory cat : DoctrineHullCategory.values()) {
                    int minReq = doctrine.getMin(cat);
                    for (int i = 0; i < minReq && seedMembers.size() < MAX_DIVISION_SIZE; i++) {
                        FleetMemberAPI picked = popFirstUnassignedOfCategory(unassigned, cat);
                        if (picked != null) {
                            seedMembers.add(picked);
                        }
                    }
                }
                if (seedMembers.isEmpty()) break;

                // Top up this new division up to max hulls (capped at 10) from remaining unassigned ships
                DivisionArchetype arch = classifyFleetMemberArchetype(seedMembers.get(0));
                PermanentDivisionSpec newSpec = createNewDivisionInternal(specs, pdata, arch);
                for (FleetMemberAPI sm : seedMembers) {
                    newSpec.memberIds.add(sm.getId());
                }

                for (int i = 0; i < unassigned.size() && newSpec.memberIds.size() < MAX_DIVISION_SIZE; ) {
                    FleetMemberAPI cand = unassigned.get(i);
                    DoctrineHullCategory cCat = classifyHullCategory(cand);
                    int cMax = doctrine.getMax(cCat);
                    if (cMax > 0 && countCategoryInDivision(newSpec, cCat, fleetById) < cMax
                            && (classifyFleetMemberArchetype(cand) == arch || doctrine.getMin(cCat) > 0)) {
                        newSpec.memberIds.add(cand.getId());
                        unassigned.remove(i);
                    } else {
                        i++;
                    }
                }
                ensureValidFlagship(newSpec, fleet);
            }
        }

        // 3b. Group any remaining unassigned ships into new divisions (up to doctrine.getMax(cat) and MAX_DIVISION_SIZE = 10)
        while (!unassigned.isEmpty()) {
            FleetMemberAPI lead = unassigned.remove(0);
            DoctrineHullCategory leadCat = classifyHullCategory(lead);
            DivisionArchetype arch = classifyFleetMemberArchetype(lead);
            int effectiveCatMax = Math.min(MAX_DIVISION_SIZE, Math.max(1, doctrine.getMax(leadCat)));

            PermanentDivisionSpec newSpec = createNewDivisionInternal(specs, pdata, arch);
            newSpec.memberIds.add(lead.getId());

            for (int i = 0; i < unassigned.size() && newSpec.memberIds.size() < MAX_DIVISION_SIZE; ) {
                FleetMemberAPI cand = unassigned.get(i);
                DoctrineHullCategory cCat = classifyHullCategory(cand);
                DivisionArchetype cArch = classifyFleetMemberArchetype(cand);
                if (cCat == leadCat && cArch == arch
                        && countCategoryInDivision(newSpec, leadCat, fleetById) < effectiveCatMax) {
                    newSpec.memberIds.add(cand.getId());
                    unassigned.remove(i);
                } else {
                    i++;
                }
            }
            ensureValidFlagship(newSpec, fleet);
        }

        saveSpecsToPersistentData(pdata, specs);
        LOG.info("[DivisionAI] Auto-assigned fleet ships across " + specs.size() + " permanent divisions.");
    }

    private static int countCategoryInDivision(
            PermanentDivisionSpec spec,
            DoctrineHullCategory cat,
            Map<String, FleetMemberAPI> fleetById) {
        if (spec == null || cat == null || fleetById == null) return 0;
        int count = 0;
        for (String id : spec.memberIds) {
            FleetMemberAPI m = fleetById.get(id);
            if (m != null && classifyHullCategory(m) == cat) {
                count++;
            }
        }
        return count;
    }

    private static FleetMemberAPI popFirstUnassignedOfCategory(
            List<FleetMemberAPI> unassigned,
            DoctrineHullCategory cat) {
        for (int i = 0; i < unassigned.size(); i++) {
            FleetMemberAPI m = unassigned.get(i);
            if (classifyHullCategory(m) == cat) {
                return unassigned.remove(i);
            }
        }
        return null;
    }

    public static DoctrineHullCategory classifyHullCategory(FleetMemberAPI member) {
        if (member == null) return DoctrineHullCategory.FRIGATE;
        ShipHullSpecAPI hullSpec = member.getHullSpec();
        HullSize size = hullSpec != null ? hullSpec.getHullSize() : HullSize.FRIGATE;
        EnumSet<ShipTypeHints> hints = (hullSpec != null && hullSpec.getHints() != null)
                ? hullSpec.getHints()
                : EnumSet.noneOf(ShipTypeHints.class);

        if (hints.contains(ShipTypeHints.TANKER)) {
            return DoctrineHullCategory.TANKER;
        }
        if (hints.contains(ShipTypeHints.LINER)) {
            return DoctrineHullCategory.TRANSPORT;
        }
        if (hints.contains(ShipTypeHints.FREIGHTER)) {
            return DoctrineHullCategory.FREIGHTER;
        }
        if (member.isCivilian() || hints.contains(ShipTypeHints.CIVILIAN)) {
            float fuel = member.getFuelCapacity();
            float cargo = member.getCargoCapacity();
            float extraCrew = Math.max(0f, member.getMaxCrew() - member.getMinCrew());
            if (fuel > cargo && fuel >= 100f) {
                return DoctrineHullCategory.TANKER;
            }
            if (extraCrew > cargo && extraCrew >= 50f) {
                return DoctrineHullCategory.TRANSPORT;
            }
            return DoctrineHullCategory.FREIGHTER;
        }

        switch (size) {
            case CAPITAL_SHIP:
                return DoctrineHullCategory.CAPITAL;
            case CRUISER:
                return DoctrineHullCategory.CRUISER;
            case DESTROYER:
                return DoctrineHullCategory.DESTROYER;
            case FRIGATE:
            default:
                return DoctrineHullCategory.FRIGATE;
        }
    }

    public static PermanentDivisionSpec createNewDivision(DivisionArchetype archetype, String optionalFirstMemberId) {
        SectorAPI sector = Global.getSector();
        if (sector == null) return null;
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();

        if (optionalFirstMemberId != null) {
            for (PermanentDivisionSpec existing : specs) {
                existing.memberIds.remove(optionalFirstMemberId);
                ensureValidFlagship(existing, sector.getPlayerFleet());
            }
        }

        PermanentDivisionSpec created = createNewDivisionInternal(specs, pdata, archetype);
        if (optionalFirstMemberId != null) {
            created.memberIds.add(optionalFirstMemberId);
            created.flagshipMemberId = optionalFirstMemberId;
        }
        saveSpecsToPersistentData(pdata, specs);
        return created;
    }

    public static void deleteDivision(String divisionId) {
        SectorAPI sector = Global.getSector();
        if (sector == null || divisionId == null) return;
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();
        for (int i = specs.size() - 1; i >= 0; i--) {
            if (divisionId.equals(specs.get(i).id)) {
                specs.remove(i);
            }
        }
        saveSpecsToPersistentData(pdata, specs);
    }

    public static void assignShipToDivision(String memberId, String targetDivisionId) {
        SectorAPI sector = Global.getSector();
        if (sector == null || memberId == null || targetDivisionId == null) return;
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();

        PermanentDivisionSpec target = null;
        for (PermanentDivisionSpec spec : specs) {
            spec.memberIds.remove(memberId);
            ensureValidFlagship(spec, fleet);
            if (targetDivisionId.equals(spec.id)) {
                target = spec;
            }
        }
        if (target != null && !target.memberIds.contains(memberId) && target.memberIds.size() < MAX_DIVISION_SIZE) {
            target.memberIds.add(memberId);
            ensureValidFlagship(target, fleet);
        }
        saveSpecsToPersistentData(pdata, specs);
    }

    public static void removeShipFromDivision(String memberId, String divisionId) {
        SectorAPI sector = Global.getSector();
        if (sector == null || memberId == null || divisionId == null) return;
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();

        for (PermanentDivisionSpec spec : specs) {
            if (divisionId.equals(spec.id)) {
                spec.memberIds.remove(memberId);
                ensureValidFlagship(spec, fleet);
                break;
            }
        }
        saveSpecsToPersistentData(pdata, specs);
    }

    public static void setDivisionFlagship(String divisionId, String flagshipMemberId) {
        SectorAPI sector = Global.getSector();
        if (sector == null || divisionId == null || flagshipMemberId == null) return;
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();

        for (PermanentDivisionSpec spec : specs) {
            if (divisionId.equals(spec.id) && spec.memberIds.contains(flagshipMemberId)) {
                spec.flagshipMemberId = flagshipMemberId;
                spec.memberIds.remove(flagshipMemberId);
                spec.memberIds.add(0, flagshipMemberId);
                break;
            }
        }
        saveSpecsToPersistentData(pdata, specs);
    }

    public static void updateDivisionMetadata(
            String divisionId,
            String newName,
            DivisionArchetype newArchetype,
            DoctrineOption newDoctrine) {
        SectorAPI sector = Global.getSector();
        if (sector == null || divisionId == null) return;
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();

        for (PermanentDivisionSpec spec : specs) {
            if (divisionId.equals(spec.id)) {
                if (newName != null && !newName.trim().isEmpty()) {
                    spec.name = newName.trim();
                }
                if (newArchetype != null) {
                    spec.archetype = newArchetype;
                }
                if (newDoctrine != null) {
                    spec.doctrine = newDoctrine;
                }
                break;
            }
        }
        saveSpecsToPersistentData(pdata, specs);
    }

    public static List<FleetMemberAPI> getMembersInDivision(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
        List<FleetMemberAPI> out = new ArrayList<>();
        if (spec == null || fleet == null || fleet.getFleetData() == null) return out;
        Map<String, FleetMemberAPI> byId = mapFleetMembersById(fleet);
        for (String id : spec.memberIds) {
            FleetMemberAPI m = byId.get(id);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }

    public static List<FleetMemberAPI> getUnassignedCombatMembers(
            CampaignFleetAPI fleet,
            List<PermanentDivisionSpec> specs) {
        List<FleetMemberAPI> out = new ArrayList<>();
        if (fleet == null || fleet.getFleetData() == null) return out;

        Set<String> assigned = new HashSet<>();
        if (specs != null) {
            for (PermanentDivisionSpec s : specs) {
                assigned.addAll(s.memberIds);
            }
        }

        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            if (!isEligibleFleetMember(m)) continue;
            if (!assigned.contains(m.getId())) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Called in the combat layer by DivisionAutoFormer:
     * Matches newly deployed player-controlled hulls (!ship.isAlly()) against campaign Permanent Divisions,
     * creating or reinforcing the corresponding combat DivisionState and returning any leftover unassigned hulls.
     */
    public static List<ShipCapabilityProfile> applyPermanentDivisionsInCombat(
            BattlegroupState bg,
            List<ShipCapabilityProfile> unassignedPlayerHulls,
            List<DivisionState> newlyCreatedOut) {

        if (bg == null || bg.side != 0 || unassignedPlayerHulls.isEmpty()) {
            return unassignedPlayerHulls;
        }
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPlayerFleet() == null) {
            return unassignedPlayerHulls;
        }

        List<PermanentDivisionSpec> permSpecs = ensureInitializedForPlayerFleet();
        if (permSpecs.isEmpty()) {
            return unassignedPlayerHulls;
        }

        Map<String, PermanentDivisionSpec> memberToSpec = new HashMap<>();
        for (PermanentDivisionSpec spec : permSpecs) {
            for (String mId : spec.memberIds) {
                memberToSpec.put(mId, spec);
            }
        }

        List<ShipCapabilityProfile> leftoverUnassigned = new ArrayList<>();
        Map<String, List<ShipCapabilityProfile>> groupedByPermId = new LinkedHashMap<>();
        Map<String, PermanentDivisionSpec> specById = new HashMap<>();

        for (ShipCapabilityProfile prof : unassignedPlayerHulls) {
            String fmId = (prof.ship.getFleetMember() != null) ? prof.ship.getFleetMember().getId() : null;
            PermanentDivisionSpec spec = (fmId != null) ? memberToSpec.get(fmId) : null;
            if (spec == null) {
                leftoverUnassigned.add(prof);
                continue;
            }

            // Check if this permanent division already exists in the active combat BattlegroupState (e.g. reinforcement wave)
            DivisionState existingCombatDiv = findExistingCombatDivisionById(bg, spec.id);
            if (existingCombatDiv != null) {
                existingCombatDiv.members.add(prof.ship);
                existingCombatDiv.profiles.add(prof);
                bg.shipIdToDivision.put(prof.ship.getId(), existingCombatDiv);
                if (fmId.equals(spec.flagshipMemberId) || existingCombatDiv.flagship == null || !existingCombatDiv.flagship.isAlive()) {
                    existingCombatDiv.flagship = prof.ship;
                }
                DivisionAutoFormer.recomputeDivisionCapabilities(existingCombatDiv);
            } else {
                specById.put(spec.id, spec);
                List<ShipCapabilityProfile> list = groupedByPermId.get(spec.id);
                if (list == null) {
                    list = new ArrayList<>();
                    groupedByPermId.put(spec.id, list);
                }
                list.add(prof);
            }
        }

        int fallbackNumber = bg.divisions.size() + 1;
        for (Map.Entry<String, List<ShipCapabilityProfile>> entry : groupedByPermId.entrySet()) {
            PermanentDivisionSpec spec = specById.get(entry.getKey());
            List<ShipCapabilityProfile> members = entry.getValue();
            if (spec == null || members.isEmpty()) continue;

            ShipAPI leaderShip = null;
            for (ShipCapabilityProfile p : members) {
                String fmId = (p.ship.getFleetMember() != null) ? p.ship.getFleetMember().getId() : null;
                if (fmId != null && fmId.equals(spec.flagshipMemberId)) {
                    leaderShip = p.ship;
                    break;
                }
            }
            if (leaderShip == null) {
                leaderShip = members.get(0).ship;
            }

            PersonAPI captain = leaderShip.getCaptain();
            CommanderProfile cmdr = new CommanderProfile(captain, spec.name + " Cmdr");
            DivisionState combatDiv = new DivisionState(
                    spec.id,
                    bg.side,
                    fallbackNumber++,
                    spec.archetype,
                    spec.name,
                    cmdr);
            combatDiv.flagship = leaderShip;
            for (ShipCapabilityProfile p : members) {
                combatDiv.members.add(p.ship);
                combatDiv.profiles.add(p);
                bg.shipIdToDivision.put(p.ship.getId(), combatDiv);
            }
            DivisionAutoFormer.recomputeDivisionCapabilities(combatDiv);
            applyDoctrineModifiersToCombatDivision(combatDiv, spec.doctrine);
            bg.divisions.add(combatDiv);
            newlyCreatedOut.add(combatDiv);
        }

        return leftoverUnassigned;
    }

    private static void applyDoctrineModifiersToCombatDivision(DivisionState div, DoctrineOption doctrine) {
        if (div == null || doctrine == null) return;
        switch (doctrine) {
            case SPEARHEAD:
                div.commander.aggressionBias = Math.max(div.commander.aggressionBias, 1.35f);
                div.commander.overmatchCommitRatio = Math.min(div.commander.overmatchCommitRatio, 0.90f);
                break;
            case STANDOFF:
                div.commander.aggressionBias = Math.min(div.commander.aggressionBias, 0.80f);
                div.commander.rotationFluxThreshold = Math.min(div.commander.rotationFluxThreshold, 0.65f);
                break;
            case SCREEN_ESCORT:
                div.isScreenCapable = true;
                div.isFastFlanker = false;
                break;
            case FLANK_STRIKE:
                div.isFastFlanker = true;
                break;
            case RESERVE_HOLD:
                div.isDesignatedReserve = true;
                break;
            case ADAPTIVE:
            default:
                break;
        }
    }

    private static DivisionState findExistingCombatDivisionById(BattlegroupState bg, String permDivId) {
        if (bg == null || permDivId == null) return null;
        for (DivisionState d : bg.divisions) {
            if (permDivId.equals(d.id)) {
                return d;
            }
        }
        return null;
    }

    private static List<PermanentDivisionSpec> buildDefaultPermanentDivisions(
            CampaignFleetAPI fleet,
            Map<String, Object> pdata) {
        List<PermanentDivisionSpec> specs = new ArrayList<>();
        if (fleet == null || fleet.getFleetData() == null) return specs;

        List<FleetMemberAPI> eligible = new ArrayList<>();
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            if (isEligibleFleetMember(m)) {
                eligible.add(m);
            }
        }

        Collections.sort(eligible, new Comparator<FleetMemberAPI>() {
            @Override
            public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                return Float.compare(b.getDeploymentPointsCost(), a.getDeploymentPointsCost());
            }
        });

        List<FleetMemberAPI> carriers = new ArrayList<>();
        List<FleetMemberAPI> capitals = new ArrayList<>();
        List<FleetMemberAPI> strikeCruisers = new ArrayList<>();
        List<FleetMemberAPI> lineCruisers = new ArrayList<>();
        List<FleetMemberAPI> torpedoDestroyers = new ArrayList<>();
        List<FleetMemberAPI> lineDestroyers = new ArrayList<>();
        List<FleetMemberAPI> frigates = new ArrayList<>();
        List<FleetMemberAPI> phaseShips = new ArrayList<>();
        List<FleetMemberAPI> auxiliaries = new ArrayList<>();

        for (FleetMemberAPI m : eligible) {
            DivisionArchetype arch = classifyFleetMemberArchetype(m);
            switch (arch) {
                case AUXILIARY_CONVOY:
                    auxiliaries.add(m);
                    break;
                case PHASE_DIVISION:
                    phaseShips.add(m);
                    break;
                case CARRIER_AND_ESCORT:
                    carriers.add(m);
                    break;
                case BATTLE_DIVISION:
                    capitals.add(m);
                    break;
                case STRIKE_CRUISER_DIVISION:
                    strikeCruisers.add(m);
                    break;
                case CRUISER_DIVISION:
                    lineCruisers.add(m);
                    break;
                case TORPEDO_DESTROYER_DIVISION:
                    torpedoDestroyers.add(m);
                    break;
                case DESTROYER_DIVISION:
                    lineDestroyers.add(m);
                    break;
                case FRIGATE_DIVISION:
                default:
                    frigates.add(m);
                    break;
            }
        }

        // 1. Carrier & Escort divisions
        for (int i = 0; i < carriers.size(); i += 2) {
            List<FleetMemberAPI> group = new ArrayList<>();
            FleetMemberAPI c1 = carriers.get(i);
            group.add(c1);
            if (i + 1 < carriers.size() && !c1.isCapital()) {
                group.add(carriers.get(i + 1));
            }
            if (frigates.size() > 2) {
                group.add(frigates.remove(frigates.size() - 1));
            }
            addBuiltDivision(specs, pdata, DivisionArchetype.CARRIER_AND_ESCORT, group);
        }

        // 2. Battle Divisions
        for (int i = 0; i < capitals.size(); i += 2) {
            List<FleetMemberAPI> group = new ArrayList<>();
            group.add(capitals.get(i));
            if (i + 1 < capitals.size()) {
                group.add(capitals.get(i + 1));
            }
            if (group.size() == 1 && lineCruisers.size() == 1 && strikeCruisers.isEmpty()) {
                group.add(lineCruisers.remove(0));
            }
            addBuiltDivision(specs, pdata, DivisionArchetype.BATTLE_DIVISION, group);
        }

        // 3. Cruiser Divisions
        if (strikeCruisers.size() == 1 && !lineCruisers.isEmpty()) {
            lineCruisers.add(strikeCruisers.remove(0));
        }
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.STRIKE_CRUISER_DIVISION, strikeCruisers, 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.CRUISER_DIVISION, lineCruisers, 4);

        // 4. Destroyer Divisions
        if (torpedoDestroyers.size() == 1 && lineDestroyers.size() == 1) {
            torpedoDestroyers.addAll(lineDestroyers);
            lineDestroyers.clear();
        }
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.TORPEDO_DESTROYER_DIVISION, torpedoDestroyers, 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.DESTROYER_DIVISION, lineDestroyers, 4);

        // 5. Frigate, Phase, and Auxiliary Divisions
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.FRIGATE_DIVISION, frigates, 5);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.PHASE_DIVISION, phaseShips, 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.AUXILIARY_CONVOY, auxiliaries, 5);

        return specs;
    }

    private static void chunkFleetMembersIntoSpecs(
            List<PermanentDivisionSpec> specs,
            Map<String, Object> pdata,
            DivisionArchetype archetype,
            List<FleetMemberAPI> pool,
            int maxPerDiv) {
        int idx = 0;
        while (idx < pool.size()) {
            int remaining = pool.size() - idx;
            int take = Math.min(maxPerDiv, remaining);
            if (remaining > maxPerDiv && (remaining - take) == 1 && take > 2) {
                take -= 1;
            }
            List<FleetMemberAPI> sub = new ArrayList<>(pool.subList(idx, idx + take));
            addBuiltDivision(specs, pdata, archetype, sub);
            idx += take;
        }
    }

    private static void addBuiltDivision(
            List<PermanentDivisionSpec> specs,
            Map<String, Object> pdata,
            DivisionArchetype archetype,
            List<FleetMemberAPI> members) {
        if (members.isEmpty()) return;
        PermanentDivisionSpec spec = createNewDivisionInternal(specs, pdata, archetype);
        FleetMemberAPI bestFlagship = null;
        float bestScore = -1f;
        for (FleetMemberAPI m : members) {
            spec.memberIds.add(m.getId());
            float score = m.getDeploymentPointsCost();
            if (m.getCaptain() != null && !m.getCaptain().isDefault()) {
                score += 25f + m.getCaptain().getStats().getLevel() * 5f;
            }
            if (score > bestScore) {
                bestScore = score;
                bestFlagship = m;
            }
        }
        spec.flagshipMemberId = (bestFlagship != null) ? bestFlagship.getId() : members.get(0).getId();
    }

    private static PermanentDivisionSpec createNewDivisionInternal(
            List<PermanentDivisionSpec> specs,
            Map<String, Object> pdata,
            DivisionArchetype archetype) {
        DivisionArchetype arch = (archetype != null) ? archetype : DivisionArchetype.CRUISER_DIVISION;
        int nextSeq = 1;
        Object rawSeq = pdata.get(PERSISTENT_SEQ_KEY);
        if (rawSeq instanceof Integer) {
            nextSeq = ((Integer) rawSeq) + 1;
        } else {
            nextSeq = specs.size() + 1;
        }
        pdata.put(PERSISTENT_SEQ_KEY, nextSeq);

        int prefixNumber = 1;
        for (PermanentDivisionSpec existing : specs) {
            if (existing.name != null && existing.name.startsWith(arch.shortPrefix + " ")) {
                prefixNumber++;
            }
        }

        String id = "perm_div_" + nextSeq;
        String name = arch.shortPrefix + " " + prefixNumber;
        PermanentDivisionSpec created = new PermanentDivisionSpec(id, name, arch);
        specs.add(created);
        return created;
    }

    public static DivisionArchetype classifyFleetMemberArchetype(FleetMemberAPI member) {
        if (member == null) return DivisionArchetype.FRIGATE_DIVISION;
        ShipHullSpecAPI hullSpec = member.getHullSpec();
        ShipVariantAPI variant = member.getVariant();
        HullSize size = member.getHullSpec() != null ? member.getHullSpec().getHullSize() : HullSize.FRIGATE;
        EnumSet<ShipTypeHints> hints = (hullSpec != null && hullSpec.getHints() != null)
                ? hullSpec.getHints()
                : EnumSet.noneOf(ShipTypeHints.class);

        boolean isAux = member.isCivilian()
                || hints.contains(ShipTypeHints.CIVILIAN)
                || hints.contains(ShipTypeHints.FREIGHTER)
                || hints.contains(ShipTypeHints.TANKER)
                || hints.contains(ShipTypeHints.LINER);
        if (isAux) {
            return DivisionArchetype.AUXILIARY_CONVOY;
        }

        boolean isPhase = member.isPhaseShip() || hints.contains(ShipTypeHints.PHASE);
        if (isPhase) {
            return DivisionArchetype.PHASE_DIVISION;
        }

        int bays = (variant != null && variant.getFittedWings() != null) ? variant.getFittedWings().size() : 0;
        boolean isCarrier = member.isCarrier()
                || hints.contains(ShipTypeHints.CARRIER)
                || (bays >= 2 && !hints.contains(ShipTypeHints.COMBAT));
        if (isCarrier) {
            return DivisionArchetype.CARRIER_AND_ESCORT;
        }

        int strikeWeaponCount = 0;
        if (variant != null) {
            for (String slotId : variant.getNonBuiltInWeaponSlots()) {
                String wId = variant.getWeaponId(slotId);
                if (wId == null) continue;
                WeaponSpecAPI wSpec = Global.getSettings().getWeaponSpec(wId);
                if (wSpec == null) continue;
                if (wSpec.getType() == WeaponType.MISSILE
                        && (wSpec.getAIHints().contains(AIHints.STRIKE)
                        || wSpec.getAIHints().contains(AIHints.DANGEROUS)
                        || wSpec.getMaxAmmo() > 0 && wSpec.getMaxAmmo() <= 24)) {
                    strikeWeaponCount++;
                }
            }
        }

        switch (size) {
            case CAPITAL_SHIP:
                return DivisionArchetype.BATTLE_DIVISION;
            case CRUISER:
                return (strikeWeaponCount >= 2)
                        ? DivisionArchetype.STRIKE_CRUISER_DIVISION
                        : DivisionArchetype.CRUISER_DIVISION;
            case DESTROYER:
                return (strikeWeaponCount >= 1)
                        ? DivisionArchetype.TORPEDO_DESTROYER_DIVISION
                        : DivisionArchetype.DESTROYER_DIVISION;
            case FRIGATE:
            default:
                return DivisionArchetype.FRIGATE_DIVISION;
        }
    }

    public static boolean isEligibleFleetMember(FleetMemberAPI m) {
        if (m == null || m.isFighterWing() || m.isMothballed() || m.isStation()) return false;
        HullSize size = m.getHullSpec() != null ? m.getHullSpec().getHullSize() : null;
        return size == HullSize.FRIGATE
                || size == HullSize.DESTROYER
                || size == HullSize.CRUISER
                || size == HullSize.CAPITAL_SHIP;
    }

    private static void pruneRemovedFleetMembers(List<PermanentDivisionSpec> specs, CampaignFleetAPI fleet) {
        if (specs == null || fleet == null || fleet.getFleetData() == null) return;
        Map<String, FleetMemberAPI> currentById = mapFleetMembersById(fleet);
        for (PermanentDivisionSpec spec : specs) {
            for (int i = spec.memberIds.size() - 1; i >= 0; i--) {
                String mId = spec.memberIds.get(i);
                if (!currentById.containsKey(mId)) {
                    spec.memberIds.remove(i);
                }
            }
            ensureValidFlagship(spec, fleet);
        }
    }

    private static void ensureValidFlagship(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
        if (spec == null) return;
        if (spec.memberIds.isEmpty()) {
            spec.flagshipMemberId = null;
            return;
        }
        if (spec.flagshipMemberId != null && spec.memberIds.contains(spec.flagshipMemberId)) {
            return;
        }
        if (fleet == null) {
            spec.flagshipMemberId = spec.memberIds.get(0);
            return;
        }
        Map<String, FleetMemberAPI> byId = mapFleetMembersById(fleet);
        FleetMemberAPI best = null;
        float bestScore = -1f;
        for (String id : spec.memberIds) {
            FleetMemberAPI m = byId.get(id);
            if (m == null) continue;
            float score = m.getDeploymentPointsCost();
            if (m.getCaptain() != null && !m.getCaptain().isDefault()) {
                score += 25f + m.getCaptain().getStats().getLevel() * 5f;
            }
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        spec.flagshipMemberId = (best != null) ? best.getId() : spec.memberIds.get(0);
    }

    private static Map<String, FleetMemberAPI> mapFleetMembersById(CampaignFleetAPI fleet) {
        Map<String, FleetMemberAPI> map = new HashMap<>();
        if (fleet == null || fleet.getFleetData() == null) return map;
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            if (m != null && m.getId() != null) {
                map.put(m.getId(), m);
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<PermanentDivisionSpec> loadSpecsFromPersistentData(Map<String, Object> pdata) {
        List<PermanentDivisionSpec> result = new ArrayList<>();
        if (pdata == null) return result;
        Object raw = pdata.get(PERSISTENT_DIVISIONS_KEY);
        if (!(raw instanceof List)) return result;

        List<?> rawList = (List<?>) raw;
        for (Object item : rawList) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> map = (Map<String, Object>) item;
            String id = (String) map.get("id");
            String name = (String) map.get("name");
            String archStr = (String) map.get("archetype");
            String docStr = (String) map.get("doctrine");
            String flagshipId = (String) map.get("flagshipMemberId");

            DivisionArchetype arch = DivisionArchetype.CRUISER_DIVISION;
            if (archStr != null) {
                try {
                    arch = DivisionArchetype.valueOf(archStr);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (id == null || name == null) continue;

            PermanentDivisionSpec spec = new PermanentDivisionSpec(id, name, arch);
            spec.flagshipMemberId = flagshipId;
            spec.doctrine = DoctrineOption.fromId(docStr);

            Object membersObj = map.get("memberIds");
            if (membersObj instanceof List) {
                for (Object m : (List<?>) membersObj) {
                    if (m instanceof String && !spec.memberIds.contains((String) m)) {
                        spec.memberIds.add((String) m);
                    }
                }
            }
            result.add(spec);
        }
        return result;
    }

    private static void saveSpecsToPersistentData(Map<String, Object> pdata, List<PermanentDivisionSpec> specs) {
        if (pdata == null || specs == null) return;
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (PermanentDivisionSpec spec : specs) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", spec.id);
            map.put("name", spec.name);
            map.put("archetype", spec.archetype != null ? spec.archetype.name() : DivisionArchetype.CRUISER_DIVISION.name());
            map.put("doctrine", spec.doctrine != null ? spec.doctrine.name() : DoctrineOption.ADAPTIVE.name());
            map.put("flagshipMemberId", spec.flagshipMemberId);
            map.put("memberIds", new ArrayList<>(spec.memberIds));
            serialized.add(map);
        }
        pdata.put(PERSISTENT_DIVISIONS_KEY, serialized);
    }
}
