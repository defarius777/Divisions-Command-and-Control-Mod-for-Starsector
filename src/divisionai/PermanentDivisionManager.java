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
    private static final String PERSISTENT_DIVISIONS_KEY = "divisionai_permanent_divisions_v1";
    private static final String PERSISTENT_INIT_FLAG_KEY = "divisionai_permanent_divisions_init_v1";
    private static final String PERSISTENT_SEQ_KEY = "divisionai_permanent_divisions_seq_v1";

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
     * Ensures permanent divisions are initialized for the player's campaign fleet and prunes any
     * ship IDs that are no longer in the player's fleet.
     */
    public static List<PermanentDivisionSpec> ensureInitializedForPlayerFleet() {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPlayerFleet() == null) {
            return new ArrayList<>();
        }

        CampaignFleetAPI playerFleet = sector.getPlayerFleet();
        Map<String, Object> pdata = sector.getPersistentData();
        boolean alreadyInitialized = Boolean.TRUE.equals(pdata.get(PERSISTENT_INIT_FLAG_KEY));

        List<PermanentDivisionSpec> specs = loadSpecsFromPersistentData(pdata);
        pruneRemovedFleetMembers(specs, playerFleet);

        if (!alreadyInitialized && specs.isEmpty()) {
            specs = buildDefaultPermanentDivisions(playerFleet, pdata);
            pdata.put(PERSISTENT_INIT_FLAG_KEY, Boolean.TRUE);
            saveSpecsToPersistentData(pdata, specs);
            LOG.info("[DivisionAI] Initialized " + specs.size() + " permanent divisions for player campaign fleet.");
        } else {
            saveSpecsToPersistentData(pdata, specs);
        }

        return specs;
    }

    public static List<PermanentDivisionSpec> getPermanentDivisions() {
        return ensureInitializedForPlayerFleet();
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
        pdata.put(PERSISTENT_INIT_FLAG_KEY, Boolean.TRUE);
        saveSpecsToPersistentData(pdata, specs);
        LOG.info("[DivisionAI] Reset and auto-formed " + specs.size() + " permanent divisions.");
    }

    public static void autoAssignUnassignedMembers() {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPlayerFleet() == null) return;
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();

        List<FleetMemberAPI> unassigned = getUnassignedCombatMembers(fleet, specs);
        if (unassigned.isEmpty()) return;

        for (FleetMemberAPI m : unassigned) {
            DivisionArchetype desiredArch = classifyFleetMemberArchetype(m);
            PermanentDivisionSpec bestMatch = null;
            int maxCap = (desiredArch == DivisionArchetype.FRIGATE_DIVISION
                    || desiredArch == DivisionArchetype.AUXILIARY_CONVOY) ? 5 : 4;

            for (PermanentDivisionSpec spec : specs) {
                if (spec.archetype == desiredArch && spec.memberIds.size() < maxCap) {
                    bestMatch = spec;
                    break;
                }
            }
            if (bestMatch == null) {
                bestMatch = createNewDivisionInternal(specs, pdata, desiredArch);
            }
            bestMatch.memberIds.add(m.getId());
            ensureValidFlagship(bestMatch, fleet);
        }

        saveSpecsToPersistentData(pdata, specs);
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
        if (target != null && !target.memberIds.contains(memberId)) {
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

        boolean isCivilian = member.isCivilian() || hints.contains(ShipTypeHints.CIVILIAN);
        if (isCivilian) {
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
