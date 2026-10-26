package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FactionDoctrineAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionNomenclature.NomenclatureCategory;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ascribes lore-accurate Fleet Doctrines to all vanilla Starsector factions, sorts NPC fleets
 * into divisions upon fleet initialization, and executes the one-time "divisions pass" across
 * all already-instanced campaign fleets on first load.
 *
 * Player fleets are never forcibly assigned to divisions.
 */
public final class FactionFleetDoctrines {

    private static final Logger LOG = Global.getLogger(FactionFleetDoctrines.class);

    public static final String PERSISTENT_INITIAL_PASS_KEY = "divisionai_initial_divisions_pass_v1";
    public static final String MEM_NPC_DIVISIONS_KEY = "$divisionai_npc_divisions_v1";
    public static final String MEM_NPC_SIGNATURE_KEY = "$divisionai_npc_sig_v1";

    private static final Map<String, FleetDoctrineConfig> DOCTRINE_CACHE = new HashMap<>();

    private FactionFleetDoctrines() {
    }

    /**
     * Transient campaign listener that sorts newly spawned NPC fleets into divisions upon initialization.
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
                ensureFleetDivisionsSorted(fleet);
            } catch (Throwable t) {
                LOG.warn("[DivisionAI] Failed to sort newly spawned fleet into divisions: " + fleet.getName(), t);
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
     * Runs the one-time "divisions pass" through all already-instanced NPC fleets in hyperspace
     * and all star systems, assigning their ships to divisions based on their faction's Fleet Doctrine.
     * Never modifies the player's fleet.
     */
    public static void runInitialSectorDivisionsPassIfNeeded(SectorAPI sector) {
        if (sector == null) return;
        Map<String, Object> pdata = sector.getPersistentData();
        if (Boolean.TRUE.equals(pdata.get(PERSISTENT_INITIAL_PASS_KEY))) {
            return;
        }

        int fleetsProcessed = 0;
        int divisionsCreated = 0;
        int shipsAssigned = 0;

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
                List<PermanentDivisionSpec> divs = ensureFleetDivisionsSorted(fleet);
                if (!divs.isEmpty()) {
                    fleetsProcessed++;
                    divisionsCreated += divs.size();
                    for (PermanentDivisionSpec d : divs) {
                        shipsAssigned += d.memberIds.size();
                    }
                }
            }
        }

        pdata.put(PERSISTENT_INITIAL_PASS_KEY, Boolean.TRUE);
        LOG.info(String.format(
                Locale.ROOT,
                "[DivisionAI] Completed initial campaign divisions pass: sorted %d instanced NPC fleets into %d divisions (%d ships).",
                fleetsProcessed,
                divisionsCreated,
                shipsAssigned));
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
            return PermanentDivisionManager.getFleetDoctrine();
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
                // Massed armored wall of faith + Perdition deckload strikes
                cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_DIRECTION);
                cfg.setOpeningDurationSeconds(40);
                cfg.setPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION, true);
                cfg.setPlayEnabled(OtcPlay.DOUBLING, false);
                cfg.setPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES, true);
                cfg.setPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE, true);

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
                // Fanatical Safety-Overrides cells charging into point-blank brawl
                cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_NEGATION);
                cfg.setOpeningDurationSeconds(30);
                cfg.setPlayEnabled(OtcPlay.LINE_AND_FIRE_DISTRIBUTION, false);
                cfg.setPlayEnabled(OtcPlay.DOUBLING, true);
                cfg.setPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES, false);
                cfg.setPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE, true);

                configureDivisionType(cfg, DoctrineDivisionType.BATTLESHIP,
                        550, 1000, 450, 1.25f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.CLOSE_ACTION, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.CRUISER,
                        450, 900, 450, 1.30f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.CLOSE_ACTION, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.DESTROYER,
                        400, 850, 450, 1.35f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.CLOSE_ACTION, LineStation.VAN);
                configureDivisionType(cfg, DoctrineDivisionType.FRIGATE,
                        375, 800, 450, 1.40f,
                        DivisionMissionType.CLOSE_ACTION, DivisionMissionType.ENVELOPMENT, LineStation.VAN);

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

        List<PermanentDivisionSpec> sorted = sortMembersByDoctrine(fleet, eligible, doctrine, factionId, nomCat);
        if (mem != null) {
            mem.set(MEM_NPC_SIGNATURE_KEY, currentSig);
            mem.set(MEM_NPC_DIVISIONS_KEY, sorted);
        }
        return sorted;
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

        List<FleetMemberAPI> fleetCarriers = new ArrayList<>();
        List<FleetMemberAPI> lightCarriers = new ArrayList<>();
        List<FleetMemberAPI> escortCarriers = new ArrayList<>();
        List<FleetMemberAPI> capitals = new ArrayList<>();
        List<FleetMemberAPI> strikeCruisers = new ArrayList<>();
        List<FleetMemberAPI> lineCruisers = new ArrayList<>();
        List<FleetMemberAPI> destroyers = new ArrayList<>();
        List<FleetMemberAPI> frigates = new ArrayList<>();
        List<FleetMemberAPI> phaseShips = new ArrayList<>();
        List<FleetMemberAPI> auxiliaries = new ArrayList<>();

        for (FleetMemberAPI m : sortedEligible) {
            DivisionArchetype arch = classifyNpcFleetMemberArchetype(m, factionId, nomCat);
            switch (arch) {
                case SERVICE_GROUP:
                    auxiliaries.add(m);
                    break;
                case PHASE_DIVISION:
                    phaseShips.add(m);
                    break;
                case FLEET_CARRIER_DIVISION:
                    fleetCarriers.add(m);
                    break;
                case LIGHT_CARRIER_DIVISION:
                    lightCarriers.add(m);
                    break;
                case ESCORT_CARRIER_DIVISION:
                case CARRIER_GROUP:
                    escortCarriers.add(m);
                    break;
                case BATTLESHIP_DIVISION:
                    capitals.add(m);
                    break;
                case STRIKE_CRUISER_DIVISION:
                    strikeCruisers.add(m);
                    break;
                case CRUISER_DIVISION:
                    lineCruisers.add(m);
                    break;
                case MISSILE_DESTROYER_DIVISION:
                case DESTROYER_DIVISION:
                    destroyers.add(m);
                    break;
                case FRIGATE_DIVISION:
                default:
                    frigates.add(m);
                    break;
            }
        }

        int capMax = Math.max(1, doctrine.getMax(DoctrineHullCategory.CAPITAL));
        int cruMax = Math.max(1, doctrine.getMax(DoctrineHullCategory.CRUISER));
        int desMax = Math.max(1, doctrine.getMax(DoctrineHullCategory.DESTROYER));
        int friMax = Math.max(1, doctrine.getMax(DoctrineHullCategory.FRIGATE));
        int auxMax = Math.max(4, doctrine.getMax(DoctrineHullCategory.FREIGHTER));

        List<PermanentDivisionSpec> specs = new ArrayList<>();
        Map<DivisionArchetype, Integer> seqByArch = new LinkedHashMap<>();

        // 1. Battle Divisions (Capitals; single capital pairs with a line cruiser if only 1 cruiser exists)
        if (!capitals.isEmpty()) {
            if (capitals.size() == 1 && lineCruisers.size() == 1 && strikeCruisers.isEmpty()) {
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
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.STRIKE_CRUISER_DIVISION, strikeCruisers, cruMax, factionId, nomCat);

        // 4. Destroyer Divisions
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.DESTROYER_DIVISION, destroyers, desMax, factionId, nomCat);

        // 5. Frigate & Phase Divisions
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.FRIGATE_DIVISION, frigates, friMax, factionId, nomCat);
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.PHASE_DIVISION, phaseShips, Math.min(4, friMax), factionId, nomCat);

        // 6. Auxiliary / Service Groups
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.SERVICE_GROUP, auxiliaries, auxMax, factionId, nomCat);

        return specs;
    }

    private static DivisionArchetype classifyNpcFleetMemberArchetype(
            FleetMemberAPI m,
            String factionId,
            NomenclatureCategory nomCat) {
        if (m == null) return DivisionArchetype.FRIGATE_DIVISION;
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
        return PermanentDivisionManager.classifyFleetMemberArchetype(m);
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
        int cap = Math.max(1, Math.min(PermanentDivisionManager.MAX_DIVISION_SIZE, maxPerDiv));
        int idx = 0;
        while (idx < pool.size()) {
            int remaining = pool.size() - idx;
            int take = Math.min(cap, remaining);
            if (remaining > cap && (remaining - take) == 1 && take > 2) {
                take -= 1;
            }
            List<FleetMemberAPI> sub = pool.subList(idx, idx + take);
            int seq = (seqByArch.containsKey(archetype) ? seqByArch.get(archetype) : 0) + 1;
            seqByArch.put(archetype, seq);

            String divId = "npc_div_" + (out.size() + 1);
            String divName = DivisionNomenclature.formatFactionDivisionName(factionId, nomCat, archetype, seq);
            PermanentDivisionSpec spec = new PermanentDivisionSpec(divId, divName, archetype);

            FleetMemberAPI bestFlagship = null;
            float bestScore = -1f;
            for (FleetMemberAPI m : sub) {
                spec.memberIds.add(m.getId());
                float score = m.getDeploymentPointsCost();
                if (m.getCaptain() != null && !m.getCaptain().isDefault()) {
                    score += 25f + m.getCaptain().getStats().getLevel() * 5f;
                }
                if (m.isFlagship()) {
                    score += 100f;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestFlagship = m;
                }
            }
            spec.flagshipMemberId = (bestFlagship != null) ? bestFlagship.getId() : sub.get(0).getId();
            PermanentDivisionManager.recalculateDivisionStandoffRangeFromGuns(spec, fleet);
            out.add(spec);

            idx += take;
        }
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
        if (div == null || cfg == null) return;
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(div.archetype);
        div.stationSpacing = (optionalSpec != null)
                ? PermanentDivisionManager.resolveEffectiveStationSpacing(optionalSpec, cfg)
                : cfg.getStationSpacing(dt);
        div.divisionInterval = (optionalSpec != null)
                ? PermanentDivisionManager.resolveEffectiveDivisionInterval(optionalSpec, cfg)
                : cfg.getDivisionInterval(dt);
        div.standoffRange = (optionalSpec != null)
                ? PermanentDivisionManager.resolveEffectiveStandoffRange(optionalSpec, cfg)
                : cfg.getStandoffRange(dt);
        div.stationKeepingMult = cfg.getStationKeepingMult(dt);
        div.openingPhaseDurationSeconds = cfg.getOpeningDurationSeconds();
        div.openingMission = (optionalSpec != null)
                ? PermanentDivisionManager.resolveEffectiveOpeningMission(optionalSpec, cfg)
                : cfg.getOpeningMission(dt);
        div.mainMission = (optionalSpec != null)
                ? PermanentDivisionManager.resolveEffectiveMainMission(optionalSpec, cfg)
                : cfg.getMainMission(dt);
        div.lineStation = (optionalSpec != null)
                ? PermanentDivisionManager.resolveEffectiveLineStation(optionalSpec, cfg)
                : cfg.getLineStation(dt);

        if (div.openingMission == DivisionMissionType.RESERVE
                || div.mainMission == DivisionMissionType.RESERVE) {
            div.isDesignatedReserve = true;
        }

        int count = Math.max(1, div.profiles.size());
        div.stationKeepingRadius = DivisionAutoFormer.computeStationKeepingRadius(
                div.archetype,
                count,
                div.stationSpacing,
                div.stationKeepingMult,
                div.commander.stationKeepingStrictness);
    }
}
