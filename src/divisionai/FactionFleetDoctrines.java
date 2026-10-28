package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FactionDoctrineAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PersonImportance;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionNomenclature.NomenclatureCategory;
import divisionai.PermanentDivisionManager.DivisionFormation;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.DoctrineDivisionType;
import divisionai.PermanentDivisionManager.DoctrineHullCategory;
import divisionai.PermanentDivisionManager.FleetDoctrineConfig;
import divisionai.PermanentDivisionManager.LineStation;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.OtcPlay;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ascribes lore-accurate Fleet Doctrines to all vanilla Starsector factions, sorts NPC fleets
 * into divisions upon fleet initialization, maintains per-faction division unit record tables
 * (prioritizing Story/Special fleets first and reactivating destroyed divisions smaller-numbers-first),
 * keeps AI fleet divisions sticky across reinforcement/dispersal events, and executes the sector-wide
 * divisions pass.
 *
 * Player fleets are never forcibly assigned to divisions.
 */
public final class FactionFleetDoctrines {

    private static final Logger LOG = Global.getLogger(FactionFleetDoctrines.class);

    public static final String PERSISTENT_INITIAL_PASS_KEY = "divisionai_initial_divisions_pass_v1";
    public static final String PERSISTENT_FACTION_TABLES_KEY = "divisionai_faction_division_tables_v1";
    public static final String PERSISTENT_PLAYER_FACTION_DOCTRINE_KEY = "divisionai_player_faction_doctrine_v1";
    public static final String MEM_NPC_DIVISIONS_KEY = "$divisionai_npc_divisions_v1";
    public static final String MEM_NPC_SIGNATURE_KEY = "$divisionai_npc_sig_v1";

    private static final Map<String, FleetDoctrineConfig> DOCTRINE_CACHE = new HashMap<>();
    private static FleetDoctrineConfig inMemoryAppliedPlayerFactionDoctrine = null;

    private FactionFleetDoctrines() {
    }

    /**
     * Represents a single recorded division unit in a faction's Sector-wide Order of Battle table.
     * Destroyed/deactivated records (active == false) are reactivated smaller-numbers-first when
     * new fleets or reinforcements form divisions for that faction.
     */
    public static final class FactionDivisionRecord {
        public String recordId;
        public String factionId;
        public String bucketKey;
        public DivisionArchetype archetype;
        public int unitNumber;
        public long unitSeed;
        public String divisionName;
        public String assignedFleetId;
        public boolean heldByStoryFleet;
        public boolean active;
        public int timesActivated;
        public String flagshipMemberId;
        public final List<String> memberIds = new ArrayList<>();
    }

    /**
     * Transient campaign listener that sorts newly spawned NPC fleets into divisions upon initialization
     * and marks division records inactive when fleets despawn/are destroyed.
     */
    public static final class FleetInitializationDivisionListener extends BaseCampaignEventListener {
        public FleetInitializationDivisionListener() {
            super(false);
        }

        @Override
        public void reportFleetSpawned(CampaignFleetAPI fleet) {
            if (fleet == null || fleet.isPlayerFleet() || fleet.isStationMode()) {
                return;
            }
            try {
                consolidateWholeDivisionTransfersWithNearbyFleets(fleet);
                ensureFleetDivisionsSorted(fleet);
            } catch (Throwable t) {
                LOG.warn("[DivisionAI] Failed to sort newly spawned fleet into divisions: " + fleet.getName(), t);
            }
        }

        @Override
        public void reportFleetDespawned(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
            if (fleet == null || fleet.isPlayerFleet()) {
                return;
            }
            try {
                deactivateFleetDivisionsOnDespawn(fleet);
            } catch (Throwable t) {
                LOG.warn("[DivisionAI] Failed to deactivate divisions for despawned fleet: " + fleet.getName(), t);
            }
        }
    }

    /**
     * Registers the transient fleet initialization listener and runs the one-time "divisions pass"
     * across all already-instanced non-player fleets if it has not yet run on this save.
     */
    public static void onGameLoad(SectorAPI sector) {
        if (sector == null) return;
        sector.addTransientListener(new FleetInitializationDivisionListener());
        runInitialSectorDivisionsPassIfNeeded(sector);
    }

    /**
     * Clears all NPC faction division record tables and cached NPC fleet division lists, then
     * re-runs the sector-wide divisions pass (with Story/Special fleets prioritized first).
     * Never modifies the player's fleet.
     *
     * @return number of non-player fleets reinitialized across the sector.
     */
    public static int reinitializeSectorWideDivisionLists(SectorAPI sector) {
        if (sector == null) return 0;
        Map<String, Object> pdata = sector.getPersistentData();
        pdata.remove(PERSISTENT_INITIAL_PASS_KEY);
        pdata.remove(PERSISTENT_FACTION_TABLES_KEY);

        List<CampaignFleetAPI> allNpcFleets = collectAllInstancedNpcFleets(sector);
        for (CampaignFleetAPI fleet : allNpcFleets) {
            MemoryAPI mem = fleet.getMemoryWithoutUpdate();
            if (mem != null) {
                mem.unset(MEM_NPC_DIVISIONS_KEY);
                mem.unset(MEM_NPC_SIGNATURE_KEY);
            }
        }

        return executeSectorWideDivisionsPass(sector);
    }

    /**
     * Checks whether a campaign fleet belongs strictly to the player's personal faction ("player")
     * — such as patrols, defense armadas, and trade fleets spawned from player-owned colonies —
     * and NOT the player's own flagship fleet, nor any fleet spawned by a preset faction's market
     * over which a commissioned player merely holds a governorship/mandate.
     */
    public static boolean isPlayerPersonalFactionFleet(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.isPlayerFleet() || fleet.isStationMode()) {
            return false;
        }
        if (fleet.getFaction() == null || !Factions.PLAYER.equals(fleet.getFaction().getId())) {
            return false;
        }
        try {
            MarketAPI sourceMarket = Misc.getSourceMarket(fleet);
            if (sourceMarket != null
                    && sourceMarket.getFactionId() != null
                    && !Factions.PLAYER.equals(sourceMarket.getFactionId())) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        MemoryAPI mem = fleet.getMemoryWithoutUpdate();
        if (mem != null) {
            Object rawSrc = mem.get(MemFlags.MEMORY_KEY_SOURCE_MARKET);
            if (rawSrc instanceof MarketAPI) {
                MarketAPI mkt = (MarketAPI) rawSrc;
                if (mkt.getFactionId() != null && !Factions.PLAYER.equals(mkt.getFactionId())) {
                    return false;
                }
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static FleetDoctrineConfig getAppliedPlayerFactionDoctrine() {
        SectorAPI sector = Global.getSector();
        if (sector != null && sector.getPersistentData() != null) {
            Object raw = sector.getPersistentData().get(PERSISTENT_PLAYER_FACTION_DOCTRINE_KEY);
            if (raw instanceof Map) {
                return PermanentDivisionManager.deserializeDoctrineConfig((Map<String, Object>) raw);
            }
        }
        return inMemoryAppliedPlayerFactionDoctrine;
    }

    public static void clearAppliedPlayerFactionDoctrineForTests() {
        inMemoryAppliedPlayerFactionDoctrine = null;
        synchronized (DOCTRINE_CACHE) {
            DOCTRINE_CACHE.remove(Factions.PLAYER);
        }
        SectorAPI sector = Global.getSector();
        if (sector != null && sector.getPersistentData() != null) {
            sector.getPersistentData().remove(PERSISTENT_PLAYER_FACTION_DOCTRINE_KEY);
        }
    }

    /**
     * Copies the player's current Fleet Doctrine to the player's personal faction ("player")
     * and reinitializes division assignments and division names across the player's personal
     * faction division record table and all instanced personal faction fleets.
     * Never affects commissioned preset faction worlds or fleets.
     */
    public static int applyPlayerDoctrineToPersonalFactionFleets(SectorAPI sector) {
        FleetDoctrineConfig snapshot = PermanentDivisionManager.getFleetDoctrine().copy();
        inMemoryAppliedPlayerFactionDoctrine = snapshot;
        synchronized (DOCTRINE_CACHE) {
            DOCTRINE_CACHE.put(Factions.PLAYER, snapshot);
        }
        if (sector != null && sector.getPersistentData() != null) {
            sector.getPersistentData().put(
                    PERSISTENT_PLAYER_FACTION_DOCTRINE_KEY,
                    PermanentDivisionManager.serializeDoctrineConfig(snapshot));
        }

        List<FactionDivisionRecord> playerFactionTable = getOrCreateFactionDivisionTable(Factions.PLAYER);
        playerFactionTable.clear();

        if (sector == null) {
            return 0;
        }

        List<CampaignFleetAPI> personalFleets = new ArrayList<>();
        for (CampaignFleetAPI fleet : collectAllInstancedNpcFleets(sector)) {
            if (isPlayerPersonalFactionFleet(fleet)) {
                personalFleets.add(fleet);
                MemoryAPI mem = fleet.getMemoryWithoutUpdate();
                if (mem != null) {
                    mem.unset(MEM_NPC_DIVISIONS_KEY);
                    mem.unset(MEM_NPC_SIGNATURE_KEY);
                }
            }
        }

        Collections.sort(personalFleets, new Comparator<CampaignFleetAPI>() {
            @Override
            public int compare(CampaignFleetAPI a, CampaignFleetAPI b) {
                return Float.compare(computeFleetPriorityScore(b), computeFleetPriorityScore(a));
            }
        });

        int reinitCount = 0;
        for (CampaignFleetAPI fleet : personalFleets) {
            List<PermanentDivisionSpec> divs = ensureFleetDivisionsSorted(fleet);
            if (!divs.isEmpty()) {
                reinitCount++;
            }
        }
        saveFactionDivisionTablesToPersistentData();
        LOG.info("[DivisionAI] Applied player Fleet Doctrine to personal faction ('player') and reinitialized "
                + reinitCount + " personal faction fleet(s).");
        return reinitCount;
    }

    /**
     * Applies the player's current Fleet Nomenclature (preset, numbering style, and custom prefixes)
     * to the player's personal faction ("player") and reinitializes division names across the player's
     * personal faction division record table and all instanced personal faction fleets.
     * Never affects commissioned preset faction worlds or fleets.
     */
    @SuppressWarnings("unchecked")
    public static int applyPlayerNomenclatureToPersonalFactionFleets(SectorAPI sector) {
        DivisionNomenclature.applyPlayerNamingConfigToPersonalFaction();
        NomenclatureCategory nomCat = DivisionNomenclature.getPlayerNomenclatureCategory();

        List<FactionDivisionRecord> playerFactionTable = getOrCreateFactionDivisionTable(Factions.PLAYER);
        Map<String, FactionDivisionRecord> recordById = new HashMap<>();
        for (FactionDivisionRecord rec : playerFactionTable) {
            if (rec == null) continue;
            rec.divisionName = DivisionNomenclature.formatFactionDivisionName(
                    Factions.PLAYER,
                    nomCat,
                    rec.archetype,
                    rec.unitNumber,
                    null,
                    rec.unitSeed);
            if (rec.recordId != null) {
                recordById.put(rec.recordId, rec);
            }
        }

        if (sector == null) {
            return 0;
        }

        int updatedFleets = 0;
        Map<String, Integer> fallbackSeqByBucket = new LinkedHashMap<>();
        for (CampaignFleetAPI fleet : collectAllInstancedNpcFleets(sector)) {
            if (!isPlayerPersonalFactionFleet(fleet)) continue;
            MemoryAPI mem = fleet.getMemoryWithoutUpdate();
            Object rawDivs = (mem != null) ? mem.get(MEM_NPC_DIVISIONS_KEY) : null;
            if (rawDivs instanceof List && !((List<?>) rawDivs).isEmpty()) {
                List<PermanentDivisionSpec> specs = (List<PermanentDivisionSpec>) rawDivs;
                for (PermanentDivisionSpec spec : specs) {
                    if (spec == null) continue;
                    FactionDivisionRecord rec = recordById.get(spec.id);
                    if (rec != null && rec.divisionName != null) {
                        spec.name = rec.divisionName;
                    } else {
                        String bucket = DivisionNomenclature.resolvePrefixBucketKey(spec.archetype);
                        int seq = (fallbackSeqByBucket.containsKey(bucket) ? fallbackSeqByBucket.get(bucket) : 0) + 1;
                        fallbackSeqByBucket.put(bucket, seq);
                        spec.name = DivisionNomenclature.formatFactionDivisionName(
                                Factions.PLAYER,
                                nomCat,
                                spec.archetype,
                                seq,
                                null,
                                0L);
                    }
                }
                updatedFleets++;
            } else {
                List<PermanentDivisionSpec> divs = ensureFleetDivisionsSorted(fleet);
                if (!divs.isEmpty()) {
                    updatedFleets++;
                }
            }
        }
        saveFactionDivisionTablesToPersistentData();
        LOG.info("[DivisionAI] Applied player Fleet Nomenclature to personal faction ('player') and updated division names on "
                + updatedFleets + " personal faction fleet(s).");
        return updatedFleets;
    }

    /**
     * Runs the one-time "divisions pass" through all already-instanced NPC fleets in hyperspace
     * and all star systems, assigning their ships to divisions based on their faction's Fleet Doctrine
     * (Story/Special fleets prioritized first). Never modifies the player's fleet.
     */
    public static void runInitialSectorDivisionsPassIfNeeded(SectorAPI sector) {
        if (sector == null) return;
        Map<String, Object> pdata = sector.getPersistentData();
        if (Boolean.TRUE.equals(pdata.get(PERSISTENT_INITIAL_PASS_KEY))) {
            return;
        }
        executeSectorWideDivisionsPass(sector);
    }

    private static int executeSectorWideDivisionsPass(SectorAPI sector) {
        if (sector == null) return 0;
        Map<String, Object> pdata = sector.getPersistentData();

        List<CampaignFleetAPI> allNpcFleets = collectAllInstancedNpcFleets(sector);
        // Sort fleets so Story Fleets / Special Fleets / Major Named Armadas get first priority
        // for permanent divisions (#1, #2, etc.) in their respective faction!
        Collections.sort(allNpcFleets, new Comparator<CampaignFleetAPI>() {
            @Override
            public int compare(CampaignFleetAPI a, CampaignFleetAPI b) {
                return Float.compare(computeFleetPriorityScore(b), computeFleetPriorityScore(a));
            }
        });

        int fleetsProcessed = 0;
        int divisionsCreated = 0;
        int shipsAssigned = 0;

        for (CampaignFleetAPI fleet : allNpcFleets) {
            List<PermanentDivisionSpec> divs = ensureFleetDivisionsSorted(fleet);
            if (!divs.isEmpty()) {
                fleetsProcessed++;
                divisionsCreated += divs.size();
                for (PermanentDivisionSpec d : divs) {
                    shipsAssigned += d.memberIds.size();
                }
            }
        }

        pdata.put(PERSISTENT_INITIAL_PASS_KEY, Boolean.TRUE);
        LOG.info(String.format(
                Locale.ROOT,
                "[DivisionAI] Completed sector-wide campaign divisions pass: sorted %d instanced NPC fleets into %d divisions (%d ships).",
                fleetsProcessed,
                divisionsCreated,
                shipsAssigned));
        return fleetsProcessed;
    }

    private static List<CampaignFleetAPI> collectAllInstancedNpcFleets(SectorAPI sector) {
        List<CampaignFleetAPI> out = new ArrayList<>();
        if (sector == null) return out;
        List<LocationAPI> locations = new ArrayList<>();
        if (sector.getHyperspace() != null) {
            locations.add(sector.getHyperspace());
        }
        if (sector.getStarSystems() != null) {
            for (StarSystemAPI sys : sector.getStarSystems()) {
                if (sys != null) {
                    locations.add(sys);
                }
            }
        }
        for (LocationAPI loc : locations) {
            List<CampaignFleetAPI> fleets = loc.getFleets();
            if (fleets == null) continue;
            for (CampaignFleetAPI fleet : fleets) {
                if (fleet == null || fleet.isPlayerFleet() || fleet.isStationMode()) {
                    continue;
                }
                out.add(fleet);
            }
        }
        return out;
    }

    /**
     * Determines whether an NPC fleet is a Story Fleet or Special Fleet that should receive
     * first priority for prestigious low-numbered divisions (e.g., Battle Div. I) in its faction.
     */
    public static boolean isStoryOrSpecialFleet(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.isPlayerFleet()) return false;
        MemoryAPI mem = fleet.getMemoryWithoutUpdate();
        if (mem != null) {
            if (mem.getBoolean(MemFlags.ENTITY_MISSION_IMPORTANT)
                    || mem.getBoolean("$storyFleet")
                    || mem.getBoolean("$specialFleet")
                    || mem.getBoolean("$uniqueFleet")
                    || mem.getBoolean("$bossFleet")) {
                return true;
            }
            Collection<String> keys = mem.getKeys();
            if (keys != null) {
                for (String k : keys) {
                    if (k == null) continue;
                    String kl = k.toLowerCase(Locale.ROOT);
                    if (kl.startsWith("$ga_")
                            || kl.startsWith("$pk_")
                            || kl.startsWith("$ziggurat")
                            || kl.contains("grandarmada")
                            || kl.contains("armada_leader")
                            || kl.contains("story")
                            || kl.contains("crisis")
                            || kl.contains("inspection_fleet")
                            || kl.contains("blockade_boss")
                            || kl.contains("sentinel_fleet")) {
                        return true;
                    }
                }
            }
        }
        PersonAPI cmdr = fleet.getCommander();
        if (cmdr != null && !cmdr.isDefault()) {
            PersonImportance imp = cmdr.getImportance();
            if (imp == PersonImportance.VERY_HIGH || imp == PersonImportance.HIGH) {
                return true;
            }
            if (cmdr.getStats() != null && cmdr.getStats().getLevel() >= 7 && fleet.getFleetPoints() >= 150) {
                return true;
            }
        }
        String fName = fleet.getName() != null ? fleet.getName().toLowerCase(Locale.ROOT) : "";
        if (fName.contains("armada") || fName.contains("grand") || fName.contains("first fleet")
                || fName.contains("invasion") || fName.contains("task force")) {
            if (fleet.getFleetPoints() >= 160) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes a priority score for sorting NPC fleets during division allocation so Story/Special
     * fleets and major capital-led armadas get #1 divisions first.
     */
    public static float computeFleetPriorityScore(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.isPlayerFleet()) return -1f;
        float score = fleet.getFleetPoints();
        if (isStoryOrSpecialFleet(fleet)) {
            score += 100000f;
        }
        PersonAPI cmdr = fleet.getCommander();
        if (cmdr != null && !cmdr.isDefault()) {
            int lvl = cmdr.getStats() != null ? cmdr.getStats().getLevel() : 1;
            score += 500f + lvl * 200f;
            PersonImportance imp = cmdr.getImportance();
            if (imp == PersonImportance.VERY_HIGH) score += 10000f;
            else if (imp == PersonImportance.HIGH) score += 5000f;
            else if (imp == PersonImportance.MEDIUM) score += 1500f;
        }
        if (fleet.getFleetData() != null) {
            for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
                if (m != null && m.getHullSpec() != null && m.getHullSpec().getHullSize() == HullSize.CAPITAL_SHIP) {
                    score += 350f;
                }
            }
        }
        return score;
    }

    /**
     * Returns the FleetDoctrineConfig for a campaign fleet (player's configured FleetDoctrineConfig
     * for the player fleet, or the faction's FleetDoctrineConfig for NPC fleets).
     */
    public static FleetDoctrineConfig getDoctrineForFleet(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.isPlayerFleet()) {
            return PermanentDivisionManager.getFleetDoctrine();
        }
        NomenclatureCategory nomCat = DivisionNomenclature.resolveCategoryForCampaignFleet(fleet);
        if (nomCat == NomenclatureCategory.ABYSSAL_ERROR) {
            return getDoctrineForFactionId("dweller", fleet.getFaction());
        }
        if (nomCat == NomenclatureCategory.THREAT_SWARM) {
            return getDoctrineForFactionId("threat", fleet.getFaction());
        }
        if (nomCat == NomenclatureCategory.POST_AI_WAR) {
            String fId = fleet.getFaction() != null ? fleet.getFaction().getId() : "remnant";
            if (!"derelict".equalsIgnoreCase(fId)) {
                fId = "remnant";
            }
            return getDoctrineForFactionId(fId, fleet.getFaction());
        }
        String factionId = fleet.getFaction() != null ? fleet.getFaction().getId() : "independent";
        return getDoctrineForFactionId(factionId, fleet.getFaction());
    }

    /**
     * Returns the lore-accurate FleetDoctrineConfig ascribed to a faction ID.
     */
    public static FleetDoctrineConfig getDoctrineForFactionId(String factionId, FactionAPI optionalFaction) {
        String key = (factionId != null && !factionId.trim().isEmpty())
                ? factionId.trim().toLowerCase(Locale.ROOT)
                : "independent";
        if ("player".equals(key)) {
            FleetDoctrineConfig applied = getAppliedPlayerFactionDoctrine();
            if (applied != null) {
                return applied;
            }
        }
        synchronized (DOCTRINE_CACHE) {
            FleetDoctrineConfig cached = DOCTRINE_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            FleetDoctrineConfig built = buildFactionDoctrine(key, optionalFaction);
            DOCTRINE_CACHE.put(key, built);
            return built;
        }
    }

    private static FleetDoctrineConfig buildFactionDoctrine(String id, FactionAPI optionalFaction) {
        FleetDoctrineConfig cfg = new FleetDoctrineConfig();

        switch (id) {
            case "hegemony": {
                // XIV Battlegroup / Domain Navy armored gunline & disciplined mutual support
                cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_DIRECTION);
                cfg.setOpeningDurationSeconds(45);
                cfg.setPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION, true);
                cfg.setPlayEnabled(OtcPlay.DOUBLING, true);
                cfg.setPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES, true);
                cfg.setPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE, true);

                configureDivisionType(cfg, DoctrineDivisionType.BATTLESHIP,
                        625, 1100, 700, 0.90f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.CLOSE_ACTION, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.CRUISER,
                        500, 1000, 750, 0.92f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.CLOSE_ACTION, LineStation.CENTER);
                configureDivisionType(cfg, DoctrineDivisionType.DESTROYER,
                        425, 900, 750, 0.95f,
                        DivisionMissionType.SCREEN, DivisionMissionType.SCREEN, LineStation.CENTER);
                configureDivisionType(cfg, DoctrineDivisionType.FRIGATE,
                        375, 850, 800, 1.00f,
                        DivisionMissionType.SCOUTING, DivisionMissionType.SCREEN, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.LIGHT_CARRIER,
                        600, 1150, 1450, 0.95f,
                        DivisionMissionType.ARMED_RECONNAISSANCE, DivisionMissionType.COMBAT_SPACE_PATROL, LineStation.REAR);
                configureDivisionType(cfg, DoctrineDivisionType.ESCORT_CARRIER,
                        500, 1000, 1300, 0.95f,
                        DivisionMissionType.COMBAT_SPACE_PATROL, DivisionMissionType.CYCLIC_STRIKES, LineStation.REAR);

                cfg.setFormation(DoctrineDivisionType.BATTLESHIP, DivisionFormation.FORWARD_WEDGE);
                cfg.setFormation(DoctrineDivisionType.CRUISER, DivisionFormation.LINE_ABREAST);
                cfg.setFormation(DoctrineDivisionType.DESTROYER, DivisionFormation.FORWARD_SEMICIRCLE);
                cfg.setFormation(DoctrineDivisionType.FRIGATE, DivisionFormation.FORWARD_SEMICIRCLE);
                cfg.setMax(DoctrineHullCategory.CAPITAL, 2);
                cfg.setMax(DoctrineHullCategory.CRUISER, 4);
                cfg.setMax(DoctrineHullCategory.DESTROYER, 4);
                cfg.setMax(DoctrineHullCategory.FRIGATE, 5);
                return cfg;
            }

            case "tritachyon": {
                // Elite high-tech corporate security: Paragon/Astral standoff + Aurora/Phase wolfpack envelopment
                cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_DIRECTION);
                cfg.setOpeningDurationSeconds(40);
                cfg.setPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION, true);
                cfg.setPlayEnabled(OtcPlay.DOUBLING, true);
                cfg.setPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES, true);
                cfg.setPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE, true);

                configureDivisionType(cfg, DoctrineDivisionType.BATTLESHIP,
                        725, 1300, 1050, 1.00f,
                        DivisionMissionType.STANDOFF_ENGAGEMENT, DivisionMissionType.STANDOFF_ENGAGEMENT, LineStation.CENTER);
                configureDivisionType(cfg, DoctrineDivisionType.FLEET_CARRIER,
                        750, 1400, 1750, 0.95f,
                        DivisionMissionType.ARMED_RECONNAISSANCE, DivisionMissionType.ALPHA_STRIKE, LineStation.REAR);
                configureDivisionType(cfg, DoctrineDivisionType.CRUISER,
                        575, 1150, 850, 1.15f,
                        DivisionMissionType.SCOUTING, DivisionMissionType.ENVELOPMENT, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.DESTROYER,
                        475, 1000, 800, 1.15f,
                        DivisionMissionType.SCOUTING, DivisionMissionType.ENVELOPMENT, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.FRIGATE,
                        425, 950, 850, 1.20f,
                        DivisionMissionType.SCOUTING, DivisionMissionType.ENVELOPMENT, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.PHASE,
                        500, 1150, 950, 1.30f,
                        DivisionMissionType.SCOUTING, DivisionMissionType.ENVELOPMENT, LineStation.VAN);

                cfg.setFormation(DoctrineDivisionType.BATTLESHIP, DivisionFormation.REVERSE_WEDGE);
                cfg.setFormation(DoctrineDivisionType.CRUISER, DivisionFormation.REVERSE_SEMICIRCLE);
                cfg.setFormation(DoctrineDivisionType.DESTROYER, DivisionFormation.ECHELON_LEFT);
                cfg.setFormation(DoctrineDivisionType.FRIGATE, DivisionFormation.REVERSE_SEMICIRCLE);
                cfg.setFormation(DoctrineDivisionType.PHASE, DivisionFormation.ECHELON_RIGHT);
                cfg.setMax(DoctrineHullCategory.CAPITAL, 2);
                cfg.setMax(DoctrineHullCategory.CRUISER, 3);
                cfg.setMax(DoctrineHullCategory.DESTROYER, 4);
                cfg.setMax(DoctrineHullCategory.FRIGATE, 5);
                return cfg;
            }

            case "persean": {
                // Kazeron Navarchy: Pegasus/Conquest/Champion/Gryphon standoff DEM missile saturation + Monitor screens
                cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_DIRECTION);
                cfg.setOpeningDurationSeconds(45);
                cfg.setPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION, true);
                cfg.setPlayEnabled(OtcPlay.DOUBLING, true);
                cfg.setPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES, true);
                cfg.setPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE, true);

                configureDivisionType(cfg, DoctrineDivisionType.BATTLESHIP,
                        700, 1250, 1050, 1.00f,
                        DivisionMissionType.STANDOFF_ENGAGEMENT, DivisionMissionType.STANDOFF_ENGAGEMENT, LineStation.CENTER);
                configureDivisionType(cfg, DoctrineDivisionType.CRUISER,
                        550, 1100, 950, 1.02f,
                        DivisionMissionType.STANDOFF_ENGAGEMENT, DivisionMissionType.STANDOFF_ENGAGEMENT, LineStation.CENTER);
                configureDivisionType(cfg, DoctrineDivisionType.DESTROYER,
                        450, 950, 900, 1.00f,
                        DivisionMissionType.SCOUTING, DivisionMissionType.SCREEN, LineStation.CENTER);
                configureDivisionType(cfg, DoctrineDivisionType.FRIGATE,
                        400, 900, 850, 0.95f,
                        DivisionMissionType.SCREEN, DivisionMissionType.SCREEN, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.LIGHT_CARRIER,
                        625, 1200, 1600, 0.95f,
                        DivisionMissionType.ARMED_RECONNAISSANCE, DivisionMissionType.ALPHA_STRIKE, LineStation.REAR);
                configureDivisionType(cfg, DoctrineDivisionType.ESCORT_CARRIER,
                        525, 1050, 1450, 0.95f,
                        DivisionMissionType.ARMED_RECONNAISSANCE, DivisionMissionType.ALPHA_STRIKE, LineStation.REAR);

                cfg.setMax(DoctrineHullCategory.CAPITAL, 2);
                cfg.setMax(DoctrineHullCategory.CRUISER, 4);
                cfg.setMax(DoctrineHullCategory.DESTROYER, 4);
                cfg.setMax(DoctrineHullCategory.FRIGATE, 5);
                return cfg;
            }

            case "sindrian_diktat":
            case "lions_guard": {
                // Supreme Executor Philip Andrada's "Great Plan": rigid centralized Positive Control
                cfg.setOtcAuthority(OtcAuthority.POSITIVE_CONTROL);
                cfg.setOpeningDurationSeconds(35);
                cfg.setPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION, true);
                cfg.setPlayEnabled(OtcPlay.DOUBLING, "lions_guard".equals(id));
                cfg.setPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES, true);
                cfg.setPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE, true);

                configureDivisionType(cfg, DoctrineDivisionType.BATTLESHIP,
                        600, 1050, 850, 0.85f,
                        DivisionMissionType.STANDOFF_ENGAGEMENT, DivisionMissionType.CLOSE_ACTION, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.CRUISER,
                        475, 950, 800, 0.85f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.CLOSE_ACTION, LineStation.CENTER);
                configureDivisionType(cfg, DoctrineDivisionType.DESTROYER,
                        425, 900, 800, 0.88f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.SCREEN, LineStation.CENTER);
                configureDivisionType(cfg, DoctrineDivisionType.FRIGATE,
                        375, 850, 750, 0.90f,
                        DivisionMissionType.SCREEN, DivisionMissionType.SCREEN, LineStation.VAN);

                cfg.setMax(DoctrineHullCategory.CAPITAL, 2);
                cfg.setMax(DoctrineHullCategory.CRUISER, 4);
                cfg.setMax(DoctrineHullCategory.DESTROYER, 4);
                cfg.setMax(DoctrineHullCategory.FRIGATE, 5);
                return cfg;
            }

            case "luddic_church":
            case "knights_of_ludd": {
                // Massed armored wall of faith + Perdition deckload strikes (Martyrdom OFF for Luddic Church)
                cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_DIRECTION);
                cfg.setOpeningDurationSeconds(40);
                cfg.setPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION, true);
                cfg.setPlayEnabled(OtcPlay.DOUBLING, false);
                cfg.setPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES, true);
                cfg.setPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE, true);
                cfg.setPlayEnabled(OtcPlay.MARTYRDOM, false);

                configureDivisionType(cfg, DoctrineDivisionType.BATTLESHIP,
                        625, 1100, 650, 0.88f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.CLOSE_ACTION, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.CRUISER,
                        475, 950, 650, 0.92f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.CLOSE_ACTION, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.DESTROYER,
                        425, 900, 700, 0.95f,
                        DivisionMissionType.SCREEN, DivisionMissionType.CLOSE_ACTION, LineStation.CENTER);
                configureDivisionType(cfg, DoctrineDivisionType.FRIGATE,
                        375, 850, 600, 0.95f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.SCREEN, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.LIGHT_CARRIER,
                        600, 1150, 1400, 0.95f,
                        DivisionMissionType.COMBAT_SPACE_PATROL, DivisionMissionType.DECKLOAD_STRIKE, LineStation.REAR);
                configureDivisionType(cfg, DoctrineDivisionType.ESCORT_CARRIER,
                        500, 1000, 1250, 0.95f,
                        DivisionMissionType.COMBAT_SPACE_PATROL, DivisionMissionType.DECKLOAD_STRIKE, LineStation.REAR);

                cfg.setMax(DoctrineHullCategory.CAPITAL, 2);
                cfg.setMax(DoctrineHullCategory.CRUISER, 5);
                cfg.setMax(DoctrineHullCategory.DESTROYER, 5);
                cfg.setMax(DoctrineHullCategory.FRIGATE, 6);
                return cfg;
            }

            case "luddic_path": {
                // Fanatical Safety-Overrides & VBIED cells charging into point-blank Martyrdom
                cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_NEGATION);
                cfg.setOpeningDurationSeconds(30);
                cfg.setPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION, false);
                cfg.setPlayEnabled(OtcPlay.DOUBLING, true);
                cfg.setPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES, false);
                cfg.setPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE, true);
                cfg.setPlayEnabled(OtcPlay.MARTYRDOM, true);

                configureDivisionType(cfg, DoctrineDivisionType.BATTLESHIP,
                        550, 1000, 300, 1.25f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.MARTYRDOM, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.MISSILE_BATTLESHIP,
                        550, 1000, 300, 1.25f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.MARTYRDOM, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.CRUISER,
                        450, 900, 300, 1.30f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.MARTYRDOM, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.MISSILE_CRUISER,
                        450, 900, 300, 1.30f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.MARTYRDOM, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.STRIKE_CRUISER,
                        450, 900, 300, 1.30f,
                        DivisionMissionType.MARTYRDOM, DivisionMissionType.MARTYRDOM, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.DESTROYER,
                        400, 850, 300, 1.35f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.MARTYRDOM, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.MISSILE_DESTROYER,
                        400, 850, 300, 1.35f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.MARTYRDOM, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.FRIGATE,
                        375, 800, 300, 1.40f,
                        DivisionMissionType.MARTYRDOM, DivisionMissionType.MARTYRDOM, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.MISSILE_FRIGATE,
                        375, 800, 300, 1.40f,
                        DivisionMissionType.MARTYRDOM, DivisionMissionType.MARTYRDOM, LineStation.VAN);

                for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
                    cfg.setFormation(dt, DivisionFormation.NONE);
                }
                cfg.setMax(DoctrineHullCategory.CAPITAL, 2);
                cfg.setMax(DoctrineHullCategory.CRUISER, 5);
                cfg.setMax(DoctrineHullCategory.DESTROYER, 5);
                cfg.setMax(DoctrineHullCategory.FRIGATE, 7);
                return cfg;
            }

            case "pirates": {
                // Outlaw wolfpacks and converted freighter fire-support
                cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_NEGATION);
                cfg.setOpeningDurationSeconds(35);
                cfg.setPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION, true);
                cfg.setPlayEnabled(OtcPlay.DOUBLING, true);
                cfg.setPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES, true);
                cfg.setPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE, true);

                configureDivisionType(cfg, DoctrineDivisionType.BATTLESHIP,
                        650, 1150, 950, 1.10f,
                        DivisionMissionType.STANDOFF_ENGAGEMENT, DivisionMissionType.STANDOFF_ENGAGEMENT, LineStation.CENTER);
                configureDivisionType(cfg, DoctrineDivisionType.CRUISER,
                        525, 1050, 700, 1.20f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.ENVELOPMENT, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.DESTROYER,
                        450, 950, 700, 1.25f,
                        DivisionMissionType.SCOUTING, DivisionMissionType.ENVELOPMENT, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.FRIGATE,
                        400, 900, 650, 1.30f,
                        DivisionMissionType.SCOUTING, DivisionMissionType.ENVELOPMENT, LineStation.VAN);

                cfg.setFormation(DoctrineDivisionType.FRIGATE, DivisionFormation.NONE);
                cfg.setFormation(DoctrineDivisionType.DESTROYER, DivisionFormation.FORWARD_WEDGE);
                cfg.setMax(DoctrineHullCategory.CAPITAL, 3);
                cfg.setMax(DoctrineHullCategory.CRUISER, 5);
                cfg.setMax(DoctrineHullCategory.DESTROYER, 6);
                cfg.setMax(DoctrineHullCategory.FRIGATE, 7);
                return cfg;
            }

            case "mercenary": {
                // Veteran charter companies: tight, disciplined combined-arms
                cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_DIRECTION);
                cfg.setOpeningDurationSeconds(40);
                cfg.setPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION, true);
                cfg.setPlayEnabled(OtcPlay.DOUBLING, true);
                cfg.setPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES, true);
                cfg.setPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE, true);

                for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
                    cfg.setStationKeepingMult(dt, 0.90f);
                }
                return cfg;
            }

            case "remnant":
            case "remnants":
            case "derelict": {
                // Post-AI War AI Fleets: ORDO COGITATOR/MASTER Positive Control machine synchronization
                cfg.setOtcAuthority(OtcAuthority.POSITIVE_CONTROL);
                cfg.setOpeningDurationSeconds(35);
                cfg.setPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION, true);
                cfg.setPlayEnabled(OtcPlay.DOUBLING, true);
                cfg.setPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES, true);
                cfg.setPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE, true);

                configureDivisionType(cfg, DoctrineDivisionType.BATTLESHIP,
                        650, 1150, 750, 0.85f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.CLOSE_ACTION, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.CRUISER,
                        525, 1000, 750, 0.85f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.CLOSE_ACTION, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.DESTROYER,
                        425, 900, 700, 0.88f,
                        DivisionMissionType.SCOUTING, DivisionMissionType.ENVELOPMENT, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.FRIGATE,
                        375, 850, 650, 0.90f,
                        DivisionMissionType.SCOUTING, DivisionMissionType.ENVELOPMENT, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.PHASE,
                        450, 950, 700, 0.95f,
                        DivisionMissionType.SCOUTING, DivisionMissionType.ENVELOPMENT, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.LIGHT_CARRIER,
                        600, 1150, 1450, 0.88f,
                        DivisionMissionType.ARMED_RECONNAISSANCE, DivisionMissionType.ALPHA_STRIKE, LineStation.REAR);

                cfg.setMax(DoctrineHullCategory.CAPITAL, 3);
                cfg.setMax(DoctrineHullCategory.CRUISER, 4);
                cfg.setMax(DoctrineHullCategory.DESTROYER, 5);
                cfg.setMax(DoctrineHullCategory.FRIGATE, 6);
                return cfg;
            }

            case "threat": {
                // The Threat: <<UNKNOWN?? FRAGMENT COMPILER>> self-fabricating swarm convergence
                cfg.setOtcAuthority(OtcAuthority.POSITIVE_CONTROL);
                cfg.setOpeningDurationSeconds(30);
                cfg.setPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION, true);
                cfg.setPlayEnabled(OtcPlay.DOUBLING, true);
                cfg.setPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES, true);
                cfg.setPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE, true);

                configureDivisionType(cfg, DoctrineDivisionType.BATTLESHIP,
                        625, 1100, 700, 0.90f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.CLOSE_ACTION, LineStation.CENTER);
                configureDivisionType(cfg, DoctrineDivisionType.CRUISER,
                        475, 950, 650, 0.92f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.CLOSE_ACTION, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.DESTROYER,
                        400, 850, 600, 0.95f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.ENVELOPMENT, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.FRIGATE,
                        350, 800, 500, 1.00f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.ENVELOPMENT, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.LIGHT_CARRIER,
                        575, 1100, 1200, 0.90f,
                        DivisionMissionType.COMBAT_SPACE_PATROL, DivisionMissionType.ALPHA_STRIKE, LineStation.REAR);

                cfg.setMax(DoctrineHullCategory.CAPITAL, 3);
                cfg.setMax(DoctrineHullCategory.CRUISER, 5);
                cfg.setMax(DoctrineHullCategory.DESTROYER, 6);
                cfg.setMax(DoctrineHullCategory.FRIGATE, 8);
                return cfg;
            }

            case "dweller":
            case "omega": {
                // Abyssal / Otherworldly: "ERROR"
                cfg.setOtcAuthority(OtcAuthority.POSITIVE_CONTROL);
                cfg.setOpeningDurationSeconds(30);
                cfg.setPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION, true);
                cfg.setPlayEnabled(OtcPlay.DOUBLING, true);
                cfg.setPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES, true);
                cfg.setPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE, true);

                configureDivisionType(cfg, DoctrineDivisionType.BATTLESHIP,
                        600, 1100, 550, 0.90f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.CLOSE_ACTION, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.CRUISER,
                        475, 950, 550, 0.95f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.CLOSE_ACTION, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.DESTROYER,
                        400, 850, 500, 1.00f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.ENVELOPMENT, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.FRIGATE,
                        350, 800, 450, 1.05f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.ENVELOPMENT, LineStation.VAN);

                cfg.setMax(DoctrineHullCategory.CAPITAL, 3);
                cfg.setMax(DoctrineHullCategory.CRUISER, 5);
                cfg.setMax(DoctrineHullCategory.DESTROYER, 6);
                cfg.setMax(DoctrineHullCategory.FRIGATE, 7);
                return cfg;
            }

            default: {
                // Dynamic fallback from FactionDoctrineAPI if present
                if (optionalFaction != null && optionalFaction.getDoctrine() != null) {
                    FactionDoctrineAPI fd = optionalFaction.getDoctrine();
                    int aggr = fd.getAggression();
                    if (fd.isStrictComposition()) {
                        cfg.setOtcAuthority(OtcAuthority.POSITIVE_CONTROL);
                    } else if (aggr >= 5) {
                        cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_NEGATION);
                    } else {
                        cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_DIRECTION);
                    }
                    if (aggr >= 4) {
                        cfg.setOpeningDurationSeconds(35);
                        cfg.setOpeningMission(DoctrineDivisionType.BATTLESHIP, DivisionMissionType.CLOSE_ACTION);
                        cfg.setMainMission(DoctrineDivisionType.BATTLESHIP, DivisionMissionType.CLOSE_ACTION);
                        cfg.setOpeningMission(DoctrineDivisionType.CRUISER, DivisionMissionType.CLOSE_ACTION);
                        cfg.setMainMission(DoctrineDivisionType.CRUISER, DivisionMissionType.CLOSE_ACTION);
                    }
                }
                return cfg;
            }
        }
    }

    private static void configureDivisionType(
            FleetDoctrineConfig cfg,
            DoctrineDivisionType dt,
            int stationSpacing,
            int divisionInterval,
            int standoffRange,
            float stationKeepingMult,
            DivisionMissionType openingMission,
            DivisionMissionType mainMission,
            LineStation lineStation) {
        cfg.setStationSpacing(dt, stationSpacing);
        cfg.setDivisionInterval(dt, divisionInterval);
        cfg.setStandoffRange(dt, standoffRange);
        cfg.setStationKeepingMult(dt, stationKeepingMult);
        cfg.setOpeningMission(dt, openingMission);
        cfg.setMainMission(dt, mainMission);
        cfg.setLineStation(dt, lineStation);
    }

    /**
     * Ensures that a campaign fleet's divisions are sorted and up to date:
     *  - For the player fleet: never forcibly assigns ships; returns PermanentDivisionManager.getNonEmptyPermanentDivisions().
     *  - For NPC fleets: sorts eligible ships into divisions based on the faction's Fleet Doctrine
     *    and stores the sorted PermanentDivisionSpec list in the fleet's MemoryAPI.
     */
    @SuppressWarnings("unchecked")
    public static List<PermanentDivisionSpec> ensureFleetDivisionsSorted(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.getFleetData() == null) {
            return new ArrayList<>();
        }
        if (fleet.isPlayerFleet()) {
            return PermanentDivisionManager.getNonEmptyPermanentDivisions();
        }
        if (fleet.isStationMode()) {
            return new ArrayList<>();
        }

        List<FleetMemberAPI> eligible = new ArrayList<>();
        StringBuilder sigBuilder = new StringBuilder();
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            if (PermanentDivisionManager.isEligibleFleetMember(m)) {
                eligible.add(m);
                sigBuilder.append(m.getId()).append('|');
            }
        }
        if (eligible.isEmpty()) {
            return new ArrayList<>();
        }

        String currentSig = sigBuilder.toString();
        MemoryAPI mem = fleet.getMemoryWithoutUpdate();
        if (mem != null) {
            Object cachedSig = mem.get(MEM_NPC_SIGNATURE_KEY);
            Object cachedDivs = mem.get(MEM_NPC_DIVISIONS_KEY);
            if (currentSig.equals(cachedSig) && cachedDivs instanceof List) {
                return (List<PermanentDivisionSpec>) cachedDivs;
            }
        }

        FleetDoctrineConfig doctrine = getDoctrineForFleet(fleet);
        NomenclatureCategory nomCat = DivisionNomenclature.resolveCategoryForCampaignFleet(fleet);
        String factionId = fleet.getFaction() != null ? fleet.getFaction().getId() : "independent";

        List<PermanentDivisionSpec> sorted;
        Object existingDivsObj = (mem != null) ? mem.get(MEM_NPC_DIVISIONS_KEY) : null;
        if (existingDivsObj instanceof List && !((List<?>) existingDivsObj).isEmpty()) {
            // Sticky AI fleet divisions: preserve existing divisions across casualties, merges, and reinforcements
            sorted = updateStickyNpcFleetDivisions(
                    fleet,
                    (List<PermanentDivisionSpec>) existingDivsObj,
                    eligible,
                    doctrine,
                    factionId,
                    nomCat);
        } else {
            sorted = sortMembersByDoctrine(fleet, eligible, doctrine, factionId, nomCat);
        }

        if (mem != null) {
            mem.set(MEM_NPC_SIGNATURE_KEY, currentSig);
            mem.set(MEM_NPC_DIVISIONS_KEY, sorted);
        }
        return sorted;
    }

    /**
     * When a newly spawned/split AI fleet appears near a parent fleet of the same faction that just
     * transferred part of its divisions into this fleet, swaps same-hull-size ships between the two
     * fleets so whole divisions are transferred intact whenever possible.
     */
    @SuppressWarnings("unchecked")
    public static void consolidateWholeDivisionTransfersWithNearbyFleets(CampaignFleetAPI childFleet) {
        if (childFleet == null || childFleet.isPlayerFleet() || childFleet.getFleetData() == null) return;
        LocationAPI loc = childFleet.getContainingLocation();
        if (loc == null || loc.getFleets() == null) return;
        String factionId = childFleet.getFaction() != null ? childFleet.getFaction().getId() : null;
        if (factionId == null) return;

        Map<String, FleetMemberAPI> childById = new HashMap<>();
        for (FleetMemberAPI m : childFleet.getFleetData().getMembersListCopy()) {
            if (m != null && m.getId() != null) {
                childById.put(m.getId(), m);
            }
        }
        if (childById.isEmpty()) return;

        for (CampaignFleetAPI other : loc.getFleets()) {
            if (other == null || other == childFleet || other.isPlayerFleet() || other.isStationMode()) continue;
            if (other.getFaction() == null || !factionId.equals(other.getFaction().getId())) continue;
            if (other.getFleetData() == null) continue;

            MemoryAPI otherMem = other.getMemoryWithoutUpdate();
            if (otherMem == null) continue;
            Object rawDivs = otherMem.get(MEM_NPC_DIVISIONS_KEY);
            if (!(rawDivs instanceof List)) continue;
            List<PermanentDivisionSpec> parentDivs = (List<PermanentDivisionSpec>) rawDivs;
            if (parentDivs.isEmpty()) continue;

            Map<String, FleetMemberAPI> parentById = new HashMap<>();
            for (FleetMemberAPI m : other.getFleetData().getMembersListCopy()) {
                if (m != null && m.getId() != null) {
                    parentById.put(m.getId(), m);
                }
            }

            // Find if any parent divisions were split across parentFleet and childFleet
            List<PermanentDivisionSpec> majorityInChild = new ArrayList<>();
            List<PermanentDivisionSpec> minorityInChild = new ArrayList<>();
            for (PermanentDivisionSpec d : parentDivs) {
                if (d == null || d.memberIds.isEmpty()) continue;
                int inChild = 0;
                int inParent = 0;
                for (String mId : d.memberIds) {
                    if (childById.containsKey(mId)) inChild++;
                    else if (parentById.containsKey(mId)) inParent++;
                }
                if (inChild > 0 && inParent > 0) {
                    if (inChild >= inParent) {
                        majorityInChild.add(d);
                    } else {
                        minorityInChild.add(d);
                    }
                }
            }

            if (majorityInChild.isEmpty() || minorityInChild.isEmpty()) continue;

            // Swap compatible-hull-size ships between majorityInChild (in parent) and minorityInChild (in child)
            // so whole divisions stay together!
            for (PermanentDivisionSpec majDiv : majorityInChild) {
                for (String mId : new ArrayList<>(majDiv.memberIds)) {
                    FleetMemberAPI stayInParent = parentById.get(mId);
                    if (stayInParent == null || stayInParent.isFlagship()) continue;
                    HullSize targetSize = stayInParent.getHullSpec() != null
                            ? stayInParent.getHullSpec().getHullSize()
                            : HullSize.FRIGATE;

                    FleetMemberAPI swapFromChild = null;
                    for (PermanentDivisionSpec minDiv : minorityInChild) {
                        for (String cId : minDiv.memberIds) {
                            FleetMemberAPI cand = childById.get(cId);
                            if (cand != null && !cand.isFlagship()
                                    && cand.getHullSpec() != null
                                    && cand.getHullSpec().getHullSize() == targetSize) {
                                swapFromChild = cand;
                                break;
                            }
                        }
                        if (swapFromChild != null) break;
                    }

                    if (swapFromChild != null) {
                        other.getFleetData().removeFleetMember(stayInParent);
                        childFleet.getFleetData().removeFleetMember(swapFromChild);
                        childFleet.getFleetData().addFleetMember(stayInParent);
                        other.getFleetData().addFleetMember(swapFromChild);

                        parentById.remove(stayInParent.getId());
                        childById.remove(swapFromChild.getId());
                        childById.put(stayInParent.getId(), stayInParent);
                        parentById.put(swapFromChild.getId(), swapFromChild);
                    }
                }
            }
        }
    }

    private static List<PermanentDivisionSpec> updateStickyNpcFleetDivisions(
            CampaignFleetAPI fleet,
            List<PermanentDivisionSpec> existingDivs,
            List<FleetMemberAPI> eligible,
            FleetDoctrineConfig doctrine,
            String factionId,
            NomenclatureCategory nomCat) {

        Map<String, FleetMemberAPI> eligibleById = new LinkedHashMap<>();
        for (FleetMemberAPI m : eligible) {
            if (m != null && m.getId() != null) {
                eligibleById.put(m.getId(), m);
            }
        }

        List<FactionDivisionRecord> factionTable = getOrCreateFactionDivisionTable(factionId);
        Map<String, FactionDivisionRecord> recordById = new HashMap<>();
        Map<String, FactionDivisionRecord> recordByMemberId = new HashMap<>();
        for (FactionDivisionRecord rec : factionTable) {
            recordById.put(rec.recordId, rec);
            for (String mId : rec.memberIds) {
                recordByMemberId.put(mId, rec);
            }
        }

        List<PermanentDivisionSpec> retained = new ArrayList<>();
        Set<String> assignedMemberIds = new HashSet<>();

        // 1. Keep existing divisions in this fleet sticky
        for (PermanentDivisionSpec spec : existingDivs) {
            if (spec == null) continue;
            for (int i = spec.memberIds.size() - 1; i >= 0; i--) {
                String mId = spec.memberIds.get(i);
                if (!eligibleById.containsKey(mId) || assignedMemberIds.contains(mId)) {
                    spec.memberIds.remove(i);
                } else {
                    assignedMemberIds.add(mId);
                }
            }

            if (spec.memberIds.isEmpty()) {
                FactionDivisionRecord rec = recordById.get(spec.id);
                if (rec != null && (fleet.getId() == null || fleet.getId().equals(rec.assignedFleetId))) {
                    rec.active = false;
                    rec.assignedFleetId = null;
                    rec.heldByStoryFleet = false;
                    rec.memberIds.clear();
                    rec.flagshipMemberId = null;
                }
                continue;
            }

            refreshNpcDivisionFlagship(spec, eligibleById);
            PermanentDivisionManager.recalculateDivisionStandoffRangeFromGuns(spec, fleet);
            retained.add(spec);

            FactionDivisionRecord rec = recordById.get(spec.id);
            if (rec != null) {
                rec.active = true;
                rec.assignedFleetId = fleet.getId();
                rec.heldByStoryFleet = isStoryOrSpecialFleet(fleet);
                rec.memberIds.clear();
                rec.memberIds.addAll(spec.memberIds);
                rec.flagshipMemberId = spec.flagshipMemberId;
            }
        }

        // 2. Check if any newly arrived ships in this fleet belong to a division transferred from another fleet
        Map<String, List<FleetMemberAPI>> transferredByRecordId = new LinkedHashMap<>();
        for (FleetMemberAPI m : eligible) {
            if (assignedMemberIds.contains(m.getId())) continue;
            FactionDivisionRecord prevRec = recordByMemberId.get(m.getId());
            if (prevRec != null && !prevRec.recordId.isEmpty()) {
                List<FleetMemberAPI> bucket = transferredByRecordId.get(prevRec.recordId);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    transferredByRecordId.put(prevRec.recordId, bucket);
                }
                bucket.add(m);
            }
        }

        for (Map.Entry<String, List<FleetMemberAPI>> entry : transferredByRecordId.entrySet()) {
            FactionDivisionRecord rec = recordById.get(entry.getKey());
            List<FleetMemberAPI> transferredMembers = entry.getValue();
            if (rec == null || transferredMembers.isEmpty()) continue;

            // Adopt the transferred division intact if it isn't already in retained
            PermanentDivisionSpec targetSpec = null;
            for (PermanentDivisionSpec s : retained) {
                if (rec.recordId.equals(s.id)) {
                    targetSpec = s;
                    break;
                }
            }
            if (targetSpec == null) {
                targetSpec = new PermanentDivisionSpec(rec.recordId, rec.divisionName, rec.archetype);
                retained.add(targetSpec);
            }
            for (FleetMemberAPI tm : transferredMembers) {
                if (!targetSpec.memberIds.contains(tm.getId())) {
                    targetSpec.memberIds.add(tm.getId());
                    assignedMemberIds.add(tm.getId());
                }
            }
            refreshNpcDivisionFlagship(targetSpec, eligibleById);
            PermanentDivisionManager.recalculateDivisionStandoffRangeFromGuns(targetSpec, fleet);

            rec.active = true;
            rec.assignedFleetId = fleet.getId();
            rec.heldByStoryFleet = isStoryOrSpecialFleet(fleet);
            rec.memberIds.clear();
            rec.memberIds.addAll(targetSpec.memberIds);
            rec.flagshipMemberId = targetSpec.flagshipMemberId;
        }

        // 3. Any remaining genuinely new ships: first top up under-strength divisions of matching archetype,
        // then form/reactivate divisions for any leftover ships
        List<FleetMemberAPI> remainingNew = new ArrayList<>();
        for (FleetMemberAPI m : eligible) {
            if (!assignedMemberIds.contains(m.getId())) {
                remainingNew.add(m);
            }
        }

        if (!remainingNew.isEmpty()) {
            for (int i = remainingNew.size() - 1; i >= 0; i--) {
                FleetMemberAPI m = remainingNew.get(i);
                DivisionArchetype arch = classifyNpcFleetMemberArchetype(m, factionId, nomCat, doctrine);
                int maxCap = getMaxForArchetype(doctrine, arch);
                PermanentDivisionSpec bestUnderStrength = null;
                for (PermanentDivisionSpec s : retained) {
                    if (s.archetype == arch && s.memberIds.size() < maxCap) {
                        bestUnderStrength = s;
                        break;
                    }
                }
                if (bestUnderStrength != null) {
                    bestUnderStrength.memberIds.add(m.getId());
                    assignedMemberIds.add(m.getId());
                    remainingNew.remove(i);
                    refreshNpcDivisionFlagship(bestUnderStrength, eligibleById);
                    PermanentDivisionManager.recalculateDivisionStandoffRangeFromGuns(bestUnderStrength, fleet);
                    FactionDivisionRecord rec = recordById.get(bestUnderStrength.id);
                    if (rec != null) {
                        rec.memberIds.clear();
                        rec.memberIds.addAll(bestUnderStrength.memberIds);
                        rec.flagshipMemberId = bestUnderStrength.flagshipMemberId;
                    }
                }
            }

            if (!remainingNew.isEmpty()) {
                List<PermanentDivisionSpec> newlyFormed = sortMembersByDoctrine(
                        fleet,
                        remainingNew,
                        doctrine,
                        factionId,
                        nomCat);
                retained.addAll(newlyFormed);
            }
        }

        saveFactionDivisionTablesToPersistentData();
        return retained;
    }

    private static int getMaxForArchetype(FleetDoctrineConfig doctrine, DivisionArchetype arch) {
        if (doctrine == null || arch == null) return 4;
        switch (arch) {
            case BATTLESHIP_DIVISION:
            case MISSILE_BATTLESHIP_DIVISION:
            case FLEET_CARRIER_DIVISION:
                return Math.max(1, doctrine.getMax(DoctrineHullCategory.CAPITAL));
            case LIGHT_CARRIER_DIVISION:
                return Math.min(3, Math.max(1, doctrine.getMax(DoctrineHullCategory.CRUISER)));
            case ESCORT_CARRIER_DIVISION:
                return Math.min(4, Math.max(1, doctrine.getMax(DoctrineHullCategory.DESTROYER)));
            case CARRIER_GROUP:
                return 3;
            case CRUISER_DIVISION:
            case MISSILE_CRUISER_DIVISION:
            case STRIKE_CRUISER_DIVISION:
                return Math.max(1, doctrine.getMax(DoctrineHullCategory.CRUISER));
            case DESTROYER_DIVISION:
            case MISSILE_DESTROYER_DIVISION:
                return Math.max(1, doctrine.getMax(DoctrineHullCategory.DESTROYER));
            case PHASE_DIVISION:
                return Math.min(4, Math.max(1, doctrine.getMax(DoctrineHullCategory.FRIGATE)));
            case SERVICE_GROUP:
                return Math.max(4, doctrine.getMax(DoctrineHullCategory.FREIGHTER));
            case FORCE_DIVISION:
                return Math.max(1, doctrine.getMax(DoctrineHullCategory.CRUISER));
            case FRIGATE_DIVISION:
            case MISSILE_FRIGATE_DIVISION:
            default:
                return Math.max(1, doctrine.getMax(DoctrineHullCategory.FRIGATE));
        }
    }

    private static void refreshNpcDivisionFlagship(
            PermanentDivisionSpec spec,
            Map<String, FleetMemberAPI> eligibleById) {
        if (spec == null || spec.memberIds.isEmpty()) return;
        boolean preferCarrier = spec.archetype != null && spec.archetype.isCarrierArchetype();
        FleetMemberAPI best = null;
        float bestScore = -1f;
        for (String mId : spec.memberIds) {
            FleetMemberAPI m = eligibleById.get(mId);
            if (m == null) continue;
            float score = PermanentDivisionManager.computeFlagshipCandidateScore(m, preferCarrier);
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        spec.flagshipMemberId = (best != null) ? best.getId() : spec.memberIds.get(0);
    }

    private static List<PermanentDivisionSpec> sortMembersByDoctrine(
            CampaignFleetAPI fleet,
            List<FleetMemberAPI> eligible,
            FleetDoctrineConfig doctrine,
            String factionId,
            NomenclatureCategory nomCat) {

        List<FleetMemberAPI> sortedEligible = new ArrayList<>(eligible);
        Collections.sort(sortedEligible, new Comparator<FleetMemberAPI>() {
            @Override
            public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                return Float.compare(b.getDeploymentPointsCost(), a.getDeploymentPointsCost());
            }
        });

        Map<DivisionArchetype, List<FleetMemberAPI>> buckets = PermanentDivisionManager.newArchetypeBuckets();
        for (FleetMemberAPI m : sortedEligible) {
            DivisionArchetype arch = classifyNpcFleetMemberArchetype(m, factionId, nomCat, doctrine);
            if (arch == null) arch = DivisionArchetype.FORCE_DIVISION;
            if (arch == DivisionArchetype.CARRIER_GROUP) {
                arch = DivisionArchetype.ESCORT_CARRIER_DIVISION;
            }
            buckets.get(arch).add(m);
        }

        List<FleetMemberAPI> fleetCarriers = buckets.get(DivisionArchetype.FLEET_CARRIER_DIVISION);
        List<FleetMemberAPI> lightCarriers = buckets.get(DivisionArchetype.LIGHT_CARRIER_DIVISION);
        List<FleetMemberAPI> escortCarriers = buckets.get(DivisionArchetype.ESCORT_CARRIER_DIVISION);
        List<FleetMemberAPI> capitals = buckets.get(DivisionArchetype.BATTLESHIP_DIVISION);
        List<FleetMemberAPI> missileCapitals = buckets.get(DivisionArchetype.MISSILE_BATTLESHIP_DIVISION);
        List<FleetMemberAPI> strikeCruisers = buckets.get(DivisionArchetype.STRIKE_CRUISER_DIVISION);
        List<FleetMemberAPI> missileCruisers = buckets.get(DivisionArchetype.MISSILE_CRUISER_DIVISION);
        List<FleetMemberAPI> lineCruisers = buckets.get(DivisionArchetype.CRUISER_DIVISION);
        List<FleetMemberAPI> missileDestroyers = buckets.get(DivisionArchetype.MISSILE_DESTROYER_DIVISION);
        List<FleetMemberAPI> destroyers = buckets.get(DivisionArchetype.DESTROYER_DIVISION);
        List<FleetMemberAPI> missileFrigates = buckets.get(DivisionArchetype.MISSILE_FRIGATE_DIVISION);
        List<FleetMemberAPI> frigates = buckets.get(DivisionArchetype.FRIGATE_DIVISION);
        List<FleetMemberAPI> phaseShips = buckets.get(DivisionArchetype.PHASE_DIVISION);
        List<FleetMemberAPI> auxiliaries = buckets.get(DivisionArchetype.SERVICE_GROUP);
        List<FleetMemberAPI> forceShips = buckets.get(DivisionArchetype.FORCE_DIVISION);

        int capMax = Math.max(1, doctrine.getMax(DoctrineHullCategory.CAPITAL));
        int cruMax = Math.max(1, doctrine.getMax(DoctrineHullCategory.CRUISER));
        int desMax = Math.max(1, doctrine.getMax(DoctrineHullCategory.DESTROYER));
        int friMax = Math.max(1, doctrine.getMax(DoctrineHullCategory.FRIGATE));
        int auxMax = Math.max(4, doctrine.getMax(DoctrineHullCategory.FREIGHTER));

        List<PermanentDivisionSpec> specs = new ArrayList<>();
        Map<DivisionArchetype, Integer> seqByArch = new LinkedHashMap<>();

        // 1. Battle & Missile Battleship Divisions
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.MISSILE_BATTLESHIP_DIVISION, missileCapitals, capMax, factionId, nomCat);
        if (!capitals.isEmpty()) {
            if (capitals.size() == 1 && lineCruisers.size() == 1 && strikeCruisers.isEmpty() && missileCruisers.isEmpty()) {
                capitals.add(lineCruisers.remove(0));
            }
            chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.BATTLESHIP_DIVISION, capitals, capMax, factionId, nomCat);
        }

        // 2. Carrier Divisions (merge lone light/escort carriers into a Carrier Group if small)
        if (fleetCarriers.size() + lightCarriers.size() + escortCarriers.size() > 0) {
            if (fleetCarriers.size() + lightCarriers.size() + escortCarriers.size() <= 3
                    && ((fleetCarriers.isEmpty() ? 0 : 1) + (lightCarriers.isEmpty() ? 0 : 1) + (escortCarriers.isEmpty() ? 0 : 1)) >= 2) {
                List<FleetMemberAPI> combinedCarriers = new ArrayList<>();
                combinedCarriers.addAll(fleetCarriers);
                combinedCarriers.addAll(lightCarriers);
                combinedCarriers.addAll(escortCarriers);
                chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.CARRIER_GROUP, combinedCarriers, 3, factionId, nomCat);
            } else {
                chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.FLEET_CARRIER_DIVISION, fleetCarriers, capMax, factionId, nomCat);
                chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.LIGHT_CARRIER_DIVISION, lightCarriers, Math.min(3, cruMax), factionId, nomCat);
                chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.ESCORT_CARRIER_DIVISION, escortCarriers, Math.min(4, desMax), factionId, nomCat);
            }
        }

        // 3. Cruiser Divisions
        if (strikeCruisers.size() == 1 && !lineCruisers.isEmpty()) {
            lineCruisers.add(strikeCruisers.remove(0));
        }
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.CRUISER_DIVISION, lineCruisers, cruMax, factionId, nomCat);
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.MISSILE_CRUISER_DIVISION, missileCruisers, cruMax, factionId, nomCat);
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.STRIKE_CRUISER_DIVISION, strikeCruisers, cruMax, factionId, nomCat);

        // 4. Destroyer Divisions
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.MISSILE_DESTROYER_DIVISION, missileDestroyers, desMax, factionId, nomCat);
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.DESTROYER_DIVISION, destroyers, desMax, factionId, nomCat);

        // 5. Frigate & Phase Divisions
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.MISSILE_FRIGATE_DIVISION, missileFrigates, friMax, factionId, nomCat);
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.FRIGATE_DIVISION, frigates, friMax, factionId, nomCat);
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.PHASE_DIVISION, phaseShips, Math.min(4, friMax), factionId, nomCat);

        // 6. Auxiliary / Service Groups & Generic Force Divisions
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.SERVICE_GROUP, auxiliaries, auxMax, factionId, nomCat);
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.FORCE_DIVISION, forceShips, cruMax, factionId, nomCat);

        saveFactionDivisionTablesToPersistentData();
        return specs;
    }

    private static DivisionArchetype classifyNpcFleetMemberArchetype(
            FleetMemberAPI m,
            String factionId,
            NomenclatureCategory nomCat,
            FleetDoctrineConfig doctrine) {
        if (m == null) return DivisionArchetype.FORCE_DIVISION;
        if (nomCat == NomenclatureCategory.THREAT_SWARM && m.getHullSpec() != null) {
            String hullId = m.getHullSpec().getHullId();
            if ("fabricator_unit".equalsIgnoreCase(hullId)) {
                return DivisionArchetype.SERVICE_GROUP;
            }
            if ("hive_unit".equalsIgnoreCase(hullId)) {
                return DivisionArchetype.LIGHT_CARRIER_DIVISION;
            }
            if ("standoff_unit".equalsIgnoreCase(hullId)) {
                return DivisionArchetype.BATTLESHIP_DIVISION;
            }
            if ("overseer_unit".equalsIgnoreCase(hullId)) {
                return DivisionArchetype.CRUISER_DIVISION;
            }
            if ("assault_unit".equalsIgnoreCase(hullId)) {
                return DivisionArchetype.DESTROYER_DIVISION;
            }
            if ("skirmish_unit".equalsIgnoreCase(hullId) || "attack_swarm".equalsIgnoreCase(hullId)) {
                return DivisionArchetype.FRIGATE_DIVISION;
            }
        }
        DivisionArchetype classified = PermanentDivisionManager.classifyFleetMemberArchetype(m, doctrine);
        return classified != null ? classified : DivisionArchetype.FORCE_DIVISION;
    }

    private static void chunkIntoNpcDivisions(
            List<PermanentDivisionSpec> out,
            Map<DivisionArchetype, Integer> seqByArch,
            CampaignFleetAPI fleet,
            DivisionArchetype archetype,
            List<FleetMemberAPI> pool,
            int maxPerDiv,
            String factionId,
            NomenclatureCategory nomCat) {
        if (pool == null || pool.isEmpty()) return;

        List<List<FleetMemberAPI>> chunks = PermanentDivisionManager.distributePoolWithCommandersFirst(pool, maxPerDiv);
        boolean preferCarrier = archetype != null && archetype.isCarrierArchetype();

        for (List<FleetMemberAPI> sub : chunks) {
            if (sub.isEmpty()) continue;

            FleetMemberAPI bestFlagship = null;
            float bestScore = -1f;
            List<String> memberIdList = new ArrayList<>(sub.size());
            for (FleetMemberAPI m : sub) {
                memberIdList.add(m.getId());
                float score = PermanentDivisionManager.computeFlagshipCandidateScore(m, preferCarrier);
                if (score > bestScore) {
                    bestScore = score;
                    bestFlagship = m;
                }
            }
            if (bestFlagship == null) {
                bestFlagship = sub.get(0);
            }
            PersonAPI flagCommander = bestFlagship.getCaptain();
            if ((flagCommander == null || flagCommander.isDefault()) && fleet != null && bestFlagship.isFlagship()) {
                flagCommander = fleet.getCommander();
            }

            FactionDivisionRecord record = allocateOrReactivateFactionDivisionRecord(
                    factionId,
                    nomCat,
                    archetype,
                    fleet,
                    flagCommander,
                    memberIdList,
                    bestFlagship.getId());

            PermanentDivisionSpec spec = new PermanentDivisionSpec(record.recordId, record.divisionName, archetype);
            spec.memberIds.addAll(memberIdList);
            spec.flagshipMemberId = bestFlagship.getId();
            PermanentDivisionManager.recalculateDivisionStandoffRangeFromGuns(spec, fleet);
            out.add(spec);
        }
    }

    private static final Map<String, List<FactionDivisionRecord>> RUNTIME_FACTION_TABLES = new HashMap<>();
    private static SectorAPI lastLoadedTablesSector = null;

    @SuppressWarnings("unchecked")
    public static List<FactionDivisionRecord> getOrCreateFactionDivisionTable(String factionId) {
        String fId = (factionId != null && !factionId.isEmpty()) ? factionId.toLowerCase(Locale.ROOT) : "independent";
        SectorAPI sector = Global.getSector();
        if (sector != null && sector != lastLoadedTablesSector) {
            RUNTIME_FACTION_TABLES.clear();
            loadFactionDivisionTablesFromPersistentData(sector);
            lastLoadedTablesSector = sector;
        }
        List<FactionDivisionRecord> table = RUNTIME_FACTION_TABLES.get(fId);
        if (table == null) {
            table = new ArrayList<>();
            RUNTIME_FACTION_TABLES.put(fId, table);
        }
        return table;
    }

    /**
     * Marks all division records assigned to a despawned/destroyed fleet as inactive (destroyed)
     * unless their ships were transferred to another live fleet in the sector.
     */
    public static void deactivateFleetDivisionsOnDespawn(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.isPlayerFleet()) return;
        String factionId = fleet.getFaction() != null ? fleet.getFaction().getId() : "independent";
        List<FactionDivisionRecord> table = getOrCreateFactionDivisionTable(factionId);
        String fId = fleet.getId();
        boolean changed = false;
        for (FactionDivisionRecord rec : table) {
            if (rec.active && fId != null && fId.equals(rec.assignedFleetId)) {
                rec.active = false;
                rec.assignedFleetId = null;
                rec.heldByStoryFleet = false;
                rec.memberIds.clear();
                rec.flagshipMemberId = null;
                changed = true;
            }
        }
        if (changed) {
            saveFactionDivisionTablesToPersistentData();
        }
    }

    private static FactionDivisionRecord allocateOrReactivateFactionDivisionRecord(
            String factionId,
            NomenclatureCategory nomCat,
            DivisionArchetype archetype,
            CampaignFleetAPI fleet,
            PersonAPI flagCommander,
            List<String> memberIds,
            String flagshipMemberId) {

        String fId = (factionId != null && !factionId.isEmpty()) ? factionId.toLowerCase(Locale.ROOT) : "independent";
        String bucketKey = DivisionNomenclature.resolvePrefixBucketKey(archetype);
        List<FactionDivisionRecord> table = getOrCreateFactionDivisionTable(fId);
        reconcileFactionTableLiveness(fId, table);

        boolean isStory = isStoryOrSpecialFleet(fleet);
        String fleetId = fleet != null ? fleet.getId() : null;

        // Sort matching bucket records by unitNumber ascending (smaller numbers first!)
        List<FactionDivisionRecord> bucketRecords = new ArrayList<>();
        int maxUnitNum = 0;
        for (FactionDivisionRecord rec : table) {
            if (bucketKey.equals(rec.bucketKey)) {
                bucketRecords.add(rec);
                if (rec.unitNumber > maxUnitNum) {
                    maxUnitNum = rec.unitNumber;
                }
            }
        }
        Collections.sort(bucketRecords, new Comparator<FactionDivisionRecord>() {
            @Override
            public int compare(FactionDivisionRecord a, FactionDivisionRecord b) {
                return Integer.compare(a.unitNumber, b.unitNumber);
            }
        });

        // 1. Look for the lowest-numbered inactive (destroyed) record to reactivate!
        FactionDivisionRecord chosen = null;
        for (FactionDivisionRecord rec : bucketRecords) {
            if (!rec.active) {
                chosen = rec;
                break;
            }
        }

        // 2. If this is a Story/Special fleet and all lower-numbered records are active, check if Unit #1 (or a lower number)
        // is currently held by a non-story fleet; if so, preempt it so the Story Fleet gets Unit #1 (e.g. Battle Div. I)!
        if (isStory) {
            for (FactionDivisionRecord rec : bucketRecords) {
                if (rec.active && !rec.heldByStoryFleet && (chosen == null || rec.unitNumber < chosen.unitNumber)) {
                    // Bump the non-story fleet's division to the next available number
                    bumpPreemptedRecordToNewNumber(fId, nomCat, table, rec, maxUnitNum + 1);
                    chosen = rec;
                    break;
                }
            }
        }

        if (chosen == null) {
            int nextNum = maxUnitNum + 1;
            chosen = new FactionDivisionRecord();
            chosen.factionId = fId;
            chosen.bucketKey = bucketKey;
            chosen.unitNumber = nextNum;
            chosen.recordId = fId + "_" + bucketKey + "_" + nextNum;
            chosen.unitSeed = fId.hashCode() * 6364136223846793005L
                    + bucketKey.hashCode() * 1442695040888963407L
                    + nextNum * 2654435761L;
            table.add(chosen);
        }

        chosen.archetype = archetype;
        chosen.active = true;
        chosen.timesActivated++;
        chosen.assignedFleetId = fleetId;
        chosen.heldByStoryFleet = isStory;
        chosen.memberIds.clear();
        if (memberIds != null) {
            chosen.memberIds.addAll(memberIds);
        }
        chosen.flagshipMemberId = flagshipMemberId;
        chosen.divisionName = DivisionNomenclature.formatFactionDivisionName(
                fId,
                nomCat,
                archetype,
                chosen.unitNumber,
                flagCommander,
                chosen.unitSeed);
        return chosen;
    }

    @SuppressWarnings("unchecked")
    private static void bumpPreemptedRecordToNewNumber(
            String factionId,
            NomenclatureCategory nomCat,
            List<FactionDivisionRecord> table,
            FactionDivisionRecord preempted,
            int newUnitNumber) {

        String oldFleetId = preempted.assignedFleetId;
        FactionDivisionRecord replacement = new FactionDivisionRecord();
        replacement.factionId = factionId;
        replacement.bucketKey = preempted.bucketKey;
        replacement.archetype = preempted.archetype;
        replacement.unitNumber = newUnitNumber;
        replacement.recordId = factionId + "_" + preempted.bucketKey + "_" + newUnitNumber;
        replacement.unitSeed = factionId.hashCode() * 6364136223846793005L
                + preempted.bucketKey.hashCode() * 1442695040888963407L
                + newUnitNumber * 2654435761L;
        replacement.active = true;
        replacement.timesActivated = 1;
        replacement.assignedFleetId = oldFleetId;
        replacement.heldByStoryFleet = false;
        replacement.memberIds.addAll(preempted.memberIds);
        replacement.flagshipMemberId = preempted.flagshipMemberId;
        replacement.divisionName = DivisionNomenclature.formatFactionDivisionName(
                factionId,
                nomCat,
                replacement.archetype,
                newUnitNumber,
                null,
                replacement.unitSeed);
        table.add(replacement);

        // Update the bumped fleet's cached PermanentDivisionSpec if that fleet is currently instanced
        SectorAPI sector = Global.getSector();
        if (sector != null && oldFleetId != null) {
            CampaignFleetAPI oldFleet = findLiveFleetById(sector, oldFleetId);
            if (oldFleet != null && oldFleet.getMemoryWithoutUpdate() != null) {
                Object rawDivs = oldFleet.getMemoryWithoutUpdate().get(MEM_NPC_DIVISIONS_KEY);
                if (rawDivs instanceof List) {
                    List<PermanentDivisionSpec> specs = (List<PermanentDivisionSpec>) rawDivs;
                    for (int i = 0; i < specs.size(); i++) {
                        PermanentDivisionSpec s = specs.get(i);
                        if (s != null && preempted.recordId.equals(s.id)) {
                            PermanentDivisionSpec updated = new PermanentDivisionSpec(
                                    replacement.recordId,
                                    replacement.divisionName,
                                    replacement.archetype);
                            updated.memberIds.addAll(s.memberIds);
                            updated.flagshipMemberId = s.flagshipMemberId;
                            updated.customStandoffRange = s.customStandoffRange;
                            specs.set(i, updated);
                            break;
                        }
                    }
                }
            }
        }
    }

    private static void reconcileFactionTableLiveness(String factionId, List<FactionDivisionRecord> table) {
        SectorAPI sector = Global.getSector();
        if (sector == null || table == null || table.isEmpty()) return;
        for (FactionDivisionRecord rec : table) {
            if (!rec.active) continue;
            if (rec.assignedFleetId == null) {
                rec.active = false;
                rec.heldByStoryFleet = false;
                continue;
            }
            CampaignFleetAPI liveFleet = findLiveFleetById(sector, rec.assignedFleetId);
            if (liveFleet == null || !liveFleet.isAlive() || liveFleet.isDespawning()) {
                rec.active = false;
                rec.assignedFleetId = null;
                rec.heldByStoryFleet = false;
                rec.memberIds.clear();
                rec.flagshipMemberId = null;
            }
        }
    }

    private static CampaignFleetAPI findLiveFleetById(SectorAPI sector, String fleetId) {
        if (sector == null || fleetId == null) return null;
        if (sector.getHyperspace() != null) {
            CampaignFleetAPI f = findFleetInLocation(sector.getHyperspace(), fleetId);
            if (f != null) return f;
        }
        if (sector.getStarSystems() != null) {
            for (StarSystemAPI sys : sector.getStarSystems()) {
                if (sys == null) continue;
                CampaignFleetAPI f = findFleetInLocation(sys, fleetId);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static CampaignFleetAPI findFleetInLocation(LocationAPI loc, String fleetId) {
        if (loc == null || loc.getFleets() == null) return null;
        for (CampaignFleetAPI f : loc.getFleets()) {
            if (f != null && fleetId.equals(f.getId())) {
                return f;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void loadFactionDivisionTablesFromPersistentData(SectorAPI sector) {
        if (sector == null) return;
        Object raw = sector.getPersistentData().get(PERSISTENT_FACTION_TABLES_KEY);
        if (!(raw instanceof Map)) return;
        Map<String, Object> byFaction = (Map<String, Object>) raw;
        for (Map.Entry<String, Object> entry : byFaction.entrySet()) {
            String fId = entry.getKey();
            if (!(entry.getValue() instanceof List)) continue;
            List<?> rawRecords = (List<?>) entry.getValue();
            List<FactionDivisionRecord> table = new ArrayList<>();
            for (Object item : rawRecords) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) item;
                FactionDivisionRecord rec = new FactionDivisionRecord();
                rec.recordId = (String) m.get("recordId");
                rec.factionId = fId;
                rec.bucketKey = (String) m.get("bucketKey");
                String archStr = (String) m.get("archetype");
                if (archStr != null) {
                    try {
                        rec.archetype = DivisionArchetype.valueOf(archStr);
                    } catch (IllegalArgumentException ignored) {
                        rec.archetype = DivisionArchetype.CRUISER_DIVISION;
                    }
                }
                Object uNum = m.get("unitNumber");
                rec.unitNumber = (uNum instanceof Number) ? ((Number) uNum).intValue() : 1;
                Object uSeed = m.get("unitSeed");
                rec.unitSeed = (uSeed instanceof Number) ? ((Number) uSeed).longValue() : 0L;
                rec.divisionName = (String) m.get("divisionName");
                rec.assignedFleetId = (String) m.get("assignedFleetId");
                rec.heldByStoryFleet = Boolean.TRUE.equals(m.get("heldByStoryFleet"));
                rec.active = Boolean.TRUE.equals(m.get("active"));
                Object tAct = m.get("timesActivated");
                rec.timesActivated = (tAct instanceof Number) ? ((Number) tAct).intValue() : 1;
                rec.flagshipMemberId = (String) m.get("flagshipMemberId");
                Object mIds = m.get("memberIds");
                if (mIds instanceof List) {
                    for (Object idObj : (List<?>) mIds) {
                        if (idObj instanceof String) {
                            rec.memberIds.add((String) idObj);
                        }
                    }
                }
                if (rec.recordId != null && rec.bucketKey != null) {
                    table.add(rec);
                }
            }
            RUNTIME_FACTION_TABLES.put(fId, table);
        }
    }

    private static void saveFactionDivisionTablesToPersistentData() {
        SectorAPI sector = Global.getSector();
        if (sector == null) return;
        Map<String, Object> serializedByFaction = new HashMap<>();
        for (Map.Entry<String, List<FactionDivisionRecord>> entry : RUNTIME_FACTION_TABLES.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (FactionDivisionRecord rec : entry.getValue()) {
                Map<String, Object> m = new HashMap<>();
                m.put("recordId", rec.recordId);
                m.put("bucketKey", rec.bucketKey);
                m.put("archetype", rec.archetype != null ? rec.archetype.name() : DivisionArchetype.CRUISER_DIVISION.name());
                m.put("unitNumber", rec.unitNumber);
                m.put("unitSeed", rec.unitSeed);
                m.put("divisionName", rec.divisionName);
                m.put("assignedFleetId", rec.assignedFleetId);
                m.put("heldByStoryFleet", rec.heldByStoryFleet);
                m.put("active", rec.active);
                m.put("timesActivated", rec.timesActivated);
                m.put("flagshipMemberId", rec.flagshipMemberId);
                m.put("memberIds", new ArrayList<>(rec.memberIds));
                list.add(m);
            }
            serializedByFaction.put(entry.getKey(), list);
        }
        sector.getPersistentData().put(PERSISTENT_FACTION_TABLES_KEY, serializedByFaction);
    }

    /**
     * In the combat layer, matches deployed NPC ships (Allied AI or Enemy) against their source
     * CampaignFleetAPI's sorted divisions so that the exact divisions seen on the campaign map
     * are preserved in battle, with their faction's FleetDoctrineConfig applied.
     */
    public static List<ShipCapabilityProfile> applyCampaignNpcDivisionsInCombat(
            BattleGroupState battleGroup,
            List<ShipCapabilityProfile> unassignedNpcHulls,
            List<DivisionState> newlyCreatedOut) {

        if (battleGroup == null || unassignedNpcHulls == null || unassignedNpcHulls.isEmpty()) {
            return unassignedNpcHulls;
        }

        // Collect all source NPC CampaignFleetAPIs represented by these hulls
        Map<String, PermanentDivisionSpec> memberToSpec = new HashMap<>();
        Map<String, CampaignFleetAPI> specIdToFleet = new HashMap<>();

        for (ShipCapabilityProfile prof : unassignedNpcHulls) {
            if (prof == null || prof.ship == null || prof.ship.getFleetMember() == null) continue;
            FleetMemberAPI fm = prof.ship.getFleetMember();
            CampaignFleetAPI sourceFleet = (fm.getFleetData() != null) ? fm.getFleetData().getFleet() : null;
            if (sourceFleet == null || sourceFleet.isPlayerFleet()) continue;

            List<PermanentDivisionSpec> fleetSpecs = ensureFleetDivisionsSorted(sourceFleet);
            String fleetPrefix = "f" + System.identityHashCode(sourceFleet) + "_";
            for (PermanentDivisionSpec spec : fleetSpecs) {
                String uniqueSpecId = fleetPrefix + spec.id;
                specIdToFleet.put(uniqueSpecId, sourceFleet);
                for (String mId : spec.memberIds) {
                    if (!memberToSpec.containsKey(mId)) {
                        PermanentDivisionSpec copy = new PermanentDivisionSpec(uniqueSpecId, spec.name, spec.archetype);
                        copy.flagshipMemberId = spec.flagshipMemberId;
                        copy.memberIds.addAll(spec.memberIds);
                        copy.doctrine = spec.doctrine;
                        copy.customStationSpacing = spec.customStationSpacing;
                        copy.customDivisionInterval = spec.customDivisionInterval;
                        copy.customStandoffRange = spec.customStandoffRange;
                        copy.customOpeningMission = spec.customOpeningMission;
                        copy.customMainMission = spec.customMainMission;
                        copy.customLineStation = spec.customLineStation;
                        copy.customFormation = spec.customFormation;
                        memberToSpec.put(mId, copy);
                    }
                }
            }
        }

        if (memberToSpec.isEmpty()) {
            return unassignedNpcHulls;
        }

        List<ShipCapabilityProfile> leftover = new ArrayList<>();
        Map<String, List<ShipCapabilityProfile>> groupedBySpecId = new LinkedHashMap<>();
        Map<String, PermanentDivisionSpec> specById = new HashMap<>();

        for (ShipCapabilityProfile prof : unassignedNpcHulls) {
            String fmId = (prof.ship.getFleetMember() != null) ? prof.ship.getFleetMember().getId() : null;
            PermanentDivisionSpec spec = (fmId != null) ? memberToSpec.get(fmId) : null;
            if (spec == null) {
                leftover.add(prof);
                continue;
            }

            DivisionState existingCombatDiv = null;
            for (DivisionState d : battleGroup.divisions) {
                if (spec.id.equals(d.id)) {
                    existingCombatDiv = d;
                    break;
                }
            }

            if (existingCombatDiv != null && existingCombatDiv.members.size() < PermanentDivisionManager.MAX_DIVISION_SIZE) {
                existingCombatDiv.members.add(prof.ship);
                existingCombatDiv.profiles.add(prof);
                battleGroup.shipIdToDivision.put(prof.ship.getId(), existingCombatDiv);
                if (fmId.equals(spec.flagshipMemberId) || existingCombatDiv.flagship == null || !existingCombatDiv.flagship.isAlive()) {
                    existingCombatDiv.flagship = prof.ship;
                }
                DivisionAutoFormer.recomputeDivisionCapabilities(existingCombatDiv);
                CampaignFleetAPI sourceFleet = specIdToFleet.get(spec.id);
                applyFactionDoctrineToCombatDivision(existingCombatDiv, spec, getDoctrineForFleet(sourceFleet));
                DivisionCombatLog.logReinforcementJoined(existingCombatDiv, prof.ship);
            } else {
                specById.put(spec.id, spec);
                List<ShipCapabilityProfile> list = groupedBySpecId.get(spec.id);
                if (list == null) {
                    list = new ArrayList<>();
                    groupedBySpecId.put(spec.id, list);
                }
                list.add(prof);
            }
        }

        int fallbackNumber = battleGroup.divisions.size() + 1;
        for (Map.Entry<String, List<ShipCapabilityProfile>> entry : groupedBySpecId.entrySet()) {
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

            int divNum = fallbackNumber++;
            String displayName = DivisionNomenclature.resolveDynamicDivisionDisplayName(
                    battleGroup.nomenclatureCategory,
                    spec.archetype,
                    divNum,
                    spec.name);

            CommanderProfile cmdr = new CommanderProfile(leaderShip.getCaptain(), displayName + " Cmdr");
            DivisionState combatDiv = new DivisionState(
                    spec.id,
                    battleGroup.side,
                    divNum,
                    spec.archetype,
                    displayName,
                    cmdr);
            combatDiv.nomenclatureCategory = battleGroup.nomenclatureCategory;
            combatDiv.flagship = leaderShip;
            for (ShipCapabilityProfile p : members) {
                combatDiv.members.add(p.ship);
                combatDiv.profiles.add(p);
                battleGroup.shipIdToDivision.put(p.ship.getId(), combatDiv);
            }
            DivisionAutoFormer.recomputeDivisionCapabilities(combatDiv);
            CampaignFleetAPI sourceFleet = specIdToFleet.get(spec.id);
            applyFactionDoctrineToCombatDivision(combatDiv, spec, getDoctrineForFleet(sourceFleet));
            battleGroup.divisions.add(combatDiv);
            newlyCreatedOut.add(combatDiv);
        }

        return leftover;
    }

    public static void applyFactionDoctrineToCombatDivision(
            DivisionState div,
            PermanentDivisionSpec optionalSpec,
            FleetDoctrineConfig cfg) {
        PermanentDivisionManager.applyDoctrineModifiersToCombatDivision(div, optionalSpec, cfg);
    }
}
