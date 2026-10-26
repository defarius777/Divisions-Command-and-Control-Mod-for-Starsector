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
import divisionai.DivisionModel.BattleGroupState;
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
    private static final String PERSISTENT_STANDOFF_MIGRATION_KEY = "divisionai_standoff_gun_only_v1";
    private static final String PERSISTENT_NAVAL_NAMES_MIGRATION_KEY = "divisionai_naval_names_v1";

    /** Hard cap on division size regardless of individual hull maximums. */
    public static final int MAX_DIVISION_SIZE = 10;

    /** In-game length of the Paragon-class Battleship (from data/hulls/paragon.ship height = 364u, bounds = 354u). */
    public static final int PARAGON_LENGTH_UNITS = 364;

    public static final int MIN_STATION_SPACING_UNITS = 150;
    public static final int MAX_STATION_SPACING_UNITS = 1200;
    public static final int MIN_DIVISION_INTERVAL_UNITS = 400;
    public static final int MAX_DIVISION_INTERVAL_UNITS = 2200;
    public static final int MIN_STANDOFF_RANGE_UNITS = 300;
    public static final int MAX_STANDOFF_RANGE_UNITS = 2400;
    public static final int MIN_OPENING_DURATION_SEC = 30;
    public static final int MAX_OPENING_DURATION_SEC = 60;

    /**
     * Preferred station in the line of battle for a division (Van, Center, Rear).
     * Automatically selected by certain mission types, or manually set by the player.
     */
    public enum LineStation {
        VAN("Van", "Leads the line of battle, ahead of the main body."),
        CENTER("Center", "Keeps station in the center of the line of battle."),
        REAR("Rear", "Stays astern of the line of battle, in standoff or in reserve.");

        public final String displayName;
        public final String summary;

        LineStation(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        /** Pre-v0.5.2 saved names (the enum was PreferredSpace: FORWARD / MIDDLE / BACK). */
        private static final Map<String, String> LEGACY_NAMES = legacyNames(
                "FORWARD", "VAN",
                "MIDDLE", "CENTER",
                "BACK", "REAR");

        public static LineStation fromId(String id, LineStation fallback) {
            if (id == null) return fallback;
            try {
                return LineStation.valueOf(LEGACY_NAMES.getOrDefault(id, id));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Selectable Opening Mission (first 30-60s) and Main Mission (rest of battle) for Battle Group
     * division types and individual divisions, including both General Combat Missions,
     * Carrier Missions & Tactics (a..f), and generic "Battle Group Reserve".
     */
    public enum DivisionMissionType {
        // General Combat Missions
        SCOUTING(
                "a. \"Scouting\"",
                "Scouting",
                "Scout ahead, take unclaimed objectives (buoys, relays, jammers) and keep contact at standoff range.",
                false,
                LineStation.VAN),
        CLOSE_ACTION(
                "b. \"Close Action\"",
                "Close Action",
                "Close the enemy and fight it out at close range: engage the enemy more closely.",
                false,
                LineStation.VAN),
        STANDOFF_ENGAGEMENT(
                "c. \"Standoff Engagement\"",
                "Standoff Engagement",
                "Hold the line at Standoff Range (longest gun range) and open the range if pressed.",
                false,
                LineStation.CENTER),
        ENVELOPMENT(
                "d. \"Envelopment\"",
                "Envelopment",
                "Work around the enemy flanks to envelop and destroy isolated or exposed ships.",
                false,
                LineStation.VAN),
        SCREEN(
                "e. \"Screen\"",
                "Screen",
                "Take spaced screening stations ahead of and abeam heavier friendly divisions.",
                false,
                LineStation.CENTER),

        // Carrier Missions and Tactics (a..f)
        ARMED_RECONNAISSANCE(
                "a. \"Armed Reconnaissance\"",
                "Armed Reconnaissance",
                "Carriers stay back while strike craft scout forward and hit targets of opportunity.",
                true,
                LineStation.REAR),
        SPACE_SUPERIORITY(
                "b. \"Space Superiority\"",
                "Space Superiority",
                "Fighter sweeps to win control of space: strip enemy strike craft and light escorts.",
                true,
                LineStation.CENTER),
        COMBAT_SPACE_PATROL(
                "c. \"Combat Space Patrol\"",
                "Combat Space Patrol",
                "Keep fighters on patrol over heavy or threatened friendly divisions.",
                true,
                LineStation.REAR),
        CYCLIC_STRIKES(
                "d. \"Cyclic Strikes\"",
                "Cyclic Strikes",
                "Launch, recover and relaunch strikes continuously against the best visible target.",
                true,
                LineStation.REAR),
        DECKLOAD_STRIKE(
                "e. \"Deckload Strike\"",
                "Deckload Strike",
                "Every carrier in the division launches together onto one focus target.",
                true,
                LineStation.REAR),
        ALPHA_STRIKE(
                "f. \"Alpha Strike\"",
                "Alpha Strike",
                "Full coordinated strike with the Main Effort and missile divisions on the primary threat (joins time-on-target strikes).",
                true,
                LineStation.REAR),

        // Universal Reserve Mission
        RESERVE(
                "g. \"Reserve\"",
                "Reserve",
                "Hold astern of the line in reserve until the OTC commits the division to reinforce or exploit.",
                false,
                LineStation.REAR);

        public final String dropdownLabel;
        public final String shortName;
        public final String summary;
        public final boolean carrierSpecific;
        public final boolean isCarrierMission;
        public final LineStation autoLineStation;

        DivisionMissionType(
                String dropdownLabel,
                String shortName,
                String summary,
                boolean carrierSpecific,
                LineStation autoLineStation) {
            this.dropdownLabel = dropdownLabel;
            this.shortName = shortName;
            this.summary = summary;
            this.carrierSpecific = carrierSpecific;
            this.isCarrierMission = carrierSpecific;
            this.autoLineStation = autoLineStation;
        }

        public static List<DivisionMissionType> getOptionsForDivisionType(boolean isCarrierType) {
            List<DivisionMissionType> list = new ArrayList<>();
            for (DivisionMissionType m : values()) {
                if (m == RESERVE) {
                    list.add(m);
                } else if (isCarrierType && m.carrierSpecific) {
                    list.add(m);
                } else if (!isCarrierType && !m.carrierSpecific) {
                    list.add(m);
                }
            }
            return list;
        }

        public static DivisionMissionType[] missionsForArchetype(DivisionArchetype archetype) {
            boolean isCarrier = archetype != null && archetype.isCarrierArchetype();
            List<DivisionMissionType> list = getOptionsForDivisionType(isCarrier);
            return list.toArray(new DivisionMissionType[0]);
        }

        public static DivisionMissionType[] missionsForDoctrineType(DoctrineDivisionType dt) {
            boolean isCarrier = dt != null && dt.primaryArchetype != null && dt.primaryArchetype.isCarrierArchetype();
            List<DivisionMissionType> list = getOptionsForDivisionType(isCarrier);
            return list.toArray(new DivisionMissionType[0]);
        }

        /** Pre-v0.5.2 saved mission names. */
        private static final Map<String, String> LEGACY_NAMES = legacyNames(
                "RECONNAISSANCE", "SCOUTING",
                "DOMINATE_SPACE_INTO_BRAWL", "CLOSE_ACTION",
                "STANDOFF_AT_RANGE", "STANDOFF_ENGAGEMENT",
                "OUTFLANK_AND_DESTROY", "ENVELOPMENT",
                "CARRIER_RECON_STRIKE_CRAFT", "ARMED_RECONNAISSANCE",
                "CARRIER_VOID_SUPERIORITY", "SPACE_SUPERIORITY",
                "CARRIER_PROTECT_FLEET", "COMBAT_SPACE_PATROL",
                "CARRIER_SUCCESSIVE_STRIKES", "CYCLIC_STRIKES",
                "CARRIER_DIVISION_COORDINATED_STRIKE", "DECKLOAD_STRIKE",
                "CARRIER_MULTI_DIVISION_STRIKE", "ALPHA_STRIKE",
                "BATTLEGROUP_RESERVE", "RESERVE");

        public static DivisionMissionType fromId(String id, DivisionMissionType fallback) {
            if (id == null) return fallback;
            try {
                return DivisionMissionType.valueOf(LEGACY_NAMES.getOrDefault(id, id));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Configurable division types in Battle Group Settings (Fleet Doctrine), with default:
     *  - Intra-division spacing (units)
     *  - Inter-division spacing (units)
     *  - Line Standoff distance (units)
     *  - Opening Mission (first 30-60s)
     *  - Main Mission (rest of battle)
     *  - Preferred Space (Forward, Middle, Back)
     */
    public enum DoctrineDivisionType {
        FRIGATE(
                "Frigate Division",
                DivisionArchetype.FRIGATE_DIVISION,
                400,
                900,
                950,
                1.15f,
                DivisionMissionType.SCOUTING,
                DivisionMissionType.ENVELOPMENT,
                LineStation.VAN),
        DESTROYER(
                "Destroyer Division",
                DivisionArchetype.DESTROYER_DIVISION,
                450,
                950,
                850,
                1.05f,
                DivisionMissionType.SCOUTING,
                DivisionMissionType.SCREEN,
                LineStation.CENTER),
        CRUISER(
                "Cruiser Division",
                DivisionArchetype.CRUISER_DIVISION,
                525,
                1050,
                800,
                1.00f,
                DivisionMissionType.CLOSE_ACTION,
                DivisionMissionType.CLOSE_ACTION,
                LineStation.CENTER),
        STRIKE_CRUISER(
                "Strike Cruiser Division",
                DivisionArchetype.STRIKE_CRUISER_DIVISION,
                550,
                1100,
                900,
                1.10f,
                DivisionMissionType.SCOUTING,
                DivisionMissionType.ENVELOPMENT,
                LineStation.VAN),
        BATTLESHIP(
                "Battleship Division (Capital)",
                DivisionArchetype.BATTLESHIP_DIVISION,
                675,
                1200,
                750,
                0.95f,
                DivisionMissionType.CLOSE_ACTION,
                DivisionMissionType.CLOSE_ACTION,
                LineStation.VAN),
        FLEET_CARRIER(
                "Fleet Carrier Division (Capital)",
                DivisionArchetype.FLEET_CARRIER_DIVISION,
                650,
                1350,
                1600,
                0.95f,
                DivisionMissionType.ARMED_RECONNAISSANCE,
                DivisionMissionType.ALPHA_STRIKE,
                LineStation.REAR),
        LIGHT_CARRIER(
                "Light Carrier Division (Cruiser)",
                DivisionArchetype.LIGHT_CARRIER_DIVISION,
                550,
                1250,
                1450,
                0.95f,
                DivisionMissionType.ARMED_RECONNAISSANCE,
                DivisionMissionType.DECKLOAD_STRIKE,
                LineStation.REAR),
        ESCORT_CARRIER(
                "Escort Carrier Division (Destroyer & Below)",
                DivisionArchetype.ESCORT_CARRIER_DIVISION,
                475,
                1150,
                1350,
                0.95f,
                DivisionMissionType.COMBAT_SPACE_PATROL,
                DivisionMissionType.CYCLIC_STRIKES,
                LineStation.REAR),
        CARRIER_GROUP(
                "Carrier Group (Carrier & Escorts)",
                DivisionArchetype.CARRIER_GROUP,
                575,
                1300,
                1450,
                0.95f,
                DivisionMissionType.ARMED_RECONNAISSANCE,
                DivisionMissionType.DECKLOAD_STRIKE,
                LineStation.REAR),
        PHASE(
                "Phase Division",
                DivisionArchetype.PHASE_DIVISION,
                475,
                1100,
                1050,
                1.25f,
                DivisionMissionType.SCOUTING,
                DivisionMissionType.ENVELOPMENT,
                LineStation.VAN),
        SERVICE_GROUP(
                "Service Group (Auxiliaries)",
                DivisionArchetype.SERVICE_GROUP,
                450,
                1200,
                1500,
                0.90f,
                DivisionMissionType.RESERVE,
                DivisionMissionType.RESERVE,
                LineStation.REAR);

        public final String displayName;
        public final DivisionArchetype primaryArchetype;
        public final int defaultStationSpacingUnits;
        public final int defaultDivisionIntervalUnits;
        public final int defaultStandoffRangeUnits;
        public final float defaultStationKeepingMult;
        public final DivisionMissionType defaultOpeningMission;
        public final DivisionMissionType defaultMainMission;
        public final LineStation defaultLineStation;

        DoctrineDivisionType(
                String displayName,
                DivisionArchetype primaryArchetype,
                int defaultStationSpacingUnits,
                int defaultDivisionIntervalUnits,
                int defaultStandoffRangeUnits,
                float defaultStationKeepingMult,
                DivisionMissionType defaultOpeningMission,
                DivisionMissionType defaultMainMission,
                LineStation defaultLineStation) {
            this.displayName = displayName;
            this.primaryArchetype = primaryArchetype;
            this.defaultStationSpacingUnits = defaultStationSpacingUnits;
            this.defaultDivisionIntervalUnits = defaultDivisionIntervalUnits;
            this.defaultStandoffRangeUnits = defaultStandoffRangeUnits;
            this.defaultStationKeepingMult = defaultStationKeepingMult;
            this.defaultOpeningMission = defaultOpeningMission;
            this.defaultMainMission = defaultMainMission;
            this.defaultLineStation = defaultLineStation;
        }

        public boolean isCarrierType() {
            return primaryArchetype != null && primaryArchetype.isCarrierArchetype();
        }

        /** The pre-v0.5.2 name used in saved doctrine keys, or null if the name did not change. */
        public String legacyName() {
            switch (this) {
                case BATTLESHIP:
                    return "BATTLE_CAPITAL";
                case CARRIER_GROUP:
                    return "CARRIER_ESCORT";
                case PHASE:
                    return "PHASE_RAIDER";
                case SERVICE_GROUP:
                    return "AUXILIARY";
                default:
                    return null;
            }
        }

        public static DoctrineDivisionType forArchetype(DivisionArchetype arch) {
            if (arch == null) return CRUISER;
            switch (arch) {
                case FRIGATE_DIVISION:
                    return FRIGATE;
                case DESTROYER_DIVISION:
                case MISSILE_DESTROYER_DIVISION:
                    return DESTROYER;
                case STRIKE_CRUISER_DIVISION:
                    return STRIKE_CRUISER;
                case BATTLESHIP_DIVISION:
                    return BATTLESHIP;
                case FLEET_CARRIER_DIVISION:
                    return FLEET_CARRIER;
                case LIGHT_CARRIER_DIVISION:
                    return LIGHT_CARRIER;
                case ESCORT_CARRIER_DIVISION:
                    return ESCORT_CARRIER;
                case CARRIER_GROUP:
                    return CARRIER_GROUP;
                case PHASE_DIVISION:
                    return PHASE;
                case SERVICE_GROUP:
                    return SERVICE_GROUP;
                case CRUISER_DIVISION:
                default:
                    return CRUISER;
            }
        }
    }

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
                DivisionArchetype.BATTLESHIP_DIVISION),
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
                DivisionArchetype.SERVICE_GROUP),
        TANKER(
                "Tanker Hulls",
                "Minimum tanker hulls for division",
                "Maximum tanker hulls for division",
                0,
                4,
                DivisionArchetype.SERVICE_GROUP),
        TRANSPORT(
                "Transport Hulls",
                "Minimum transport hulls for division",
                "Maximum transport hulls for division",
                0,
                4,
                DivisionArchetype.SERVICE_GROUP);

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
     * How much authority the Battle Group Commander (Officer in Tactical Command, OTC) has over each division's
     * configured Opening/Main Mission when it orchestrates the battle (selectable in the Fleet Doctrine window).
     */
    public enum OtcAuthority {
        COMMAND_BY_DIRECTION(
                "A. \"Command by Direction\"",
                "Command by Direction",
                "Missions set each division's style; the OTC directs targets, timing, stations and flank sides within it, "
                        + "and only fully re-tasks reserve, reinforcement and pursuit divisions."),
        POSITIVE_CONTROL(
                "B. \"Positive Control\"",
                "Positive Control",
                "OTC orders replace missions whenever issued (fix, engage, flank, reinforce, chase, strike); "
                        + "missions are only the default when the OTC has no orders for a division."),
        COMMAND_BY_NEGATION(
                "C. \"Command by Negation\"",
                "Command by Negation",
                "The OTC states intent (targets, flank sides, strike times, reinforcement requests); divisions act on their "
                        + "own initiative in their mission style and may decline or deviate unless the OTC overrides them."),
        FIRE_DISTRIBUTION_ONLY(
                "D. \"Fire Distribution Only\"",
                "Fire Distribution Only",
                "The OTC only distributes enemy targets and times strikes; it never moves, re-tasks or "
                        + "stations divisions.");

        public final String dropdownLabel;
        public final String shortName;
        public final String summary;

        OtcAuthority(String dropdownLabel, String shortName, String summary) {
            this.dropdownLabel = dropdownLabel;
            this.shortName = shortName;
            this.summary = summary;
        }

        /** Whether the OTC may position divisions (anchors, flank sides) rather than only pick their targets. */
        public boolean allowsPositioning() {
            return this != FIRE_DISTRIBUTION_ONLY;
        }

        /** Whether the OTC may re-task divisions onto relief, reserve-commitment and pursuit tasks. */
        public boolean allowsRetasking() {
            return this != FIRE_DISTRIBUTION_ONLY;
        }

        /** Whether OTC taskings are binding (true) or requests a division may decline (false). */
        public boolean isBinding() {
            return this != COMMAND_BY_NEGATION;
        }

        /** Pre-v0.5.2 saved names (the enum was BattlegroupAuthority). */
        private static final Map<String, String> LEGACY_NAMES = legacyNames(
                "HYBRID", "COMMAND_BY_DIRECTION",
                "CENTRAL_PLANNING", "POSITIVE_CONTROL",
                "MISSION_TACTICS", "COMMAND_BY_NEGATION",
                "TARGET_COORDINATION", "FIRE_DISTRIBUTION_ONLY");

        public static OtcAuthority fromId(String id, OtcAuthority fallback) {
            if (id == null) return fallback;
            try {
                return OtcAuthority.valueOf(LEGACY_NAMES.getOrDefault(id, id));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Battle-wide plays the Battle Group Commander (OTC) may run (each toggled in the Fleet Doctrine window).
     */
    public enum OtcPlay {
        LINE_AND_FIRE_DISTRIBUTION(
                "Line & Fire Distribution",
                "The Main Effort forms the line on the primary threat and fixes it; fire is distributed so each other division takes its own target."),
        DOUBLING(
                "Doubling",
                "Flankers on the same target are sent down opposite sides (port and starboard) to double it."),
        TIME_ON_TARGET_STRIKES(
                "Time-on-Target Strikes",
                "Missile and strike-craft divisions go to their marshal points, then strike together at one time on one focus ship."),
        RESERVE_AND_GENERAL_CHASE(
                "Reserve & General Chase",
                "One division sent to reinforce each crisis, the reserve held until needed, and fast divisions give general chase to broken enemies on a leash.");

        public final String displayName;
        public final String summary;

        OtcPlay(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        /** The pre-v0.5.2 name used in saved doctrine keys (the enum was OrchestrationPlay). */
        public String legacyName() {
            switch (this) {
                case LINE_AND_FIRE_DISTRIBUTION:
                    return "ANCHOR_AND_FIX";
                case DOUBLING:
                    return "PINCER_FLANKS";
                case TIME_ON_TARGET_STRIKES:
                    return "TIMED_STRIKES";
                case RESERVE_AND_GENERAL_CHASE:
                    return "RESERVE_AND_EXPLOIT";
                default:
                    return null;
            }
        }
    }

    /** Builds an old-name -> new-name lookup from alternating pairs, for loading pre-rename saves. */
    private static Map<String, String> legacyNames(String... oldNewPairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < oldNewPairs.length; i += 2) {
            map.put(oldNewPairs[i], oldNewPairs[i + 1]);
        }
        return map;
    }

    /**
     * Campaign-saved Battle Group & Fleet Doctrine settings defining:
     *  1. Battle Group Settings:
     *     - Opening Mission duration (30s - 60s, default 45s)
     *     - Per-division-type Intra-division spacing, Inter-division spacing, Line Standoff distance,
     *       Opening Mission, Main Mission, and cohesion leash.
     *  2. Minimum and maximum hulls per division for each of the 7 hull categories (capped at 10).
     */
    public static class FleetDoctrineConfig {
        private final Map<DoctrineHullCategory, Integer> minByCat = new LinkedHashMap<>();
        private final Map<DoctrineHullCategory, Integer> maxByCat = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, Integer> stationSpacingByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, Integer> divisionIntervalByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, Integer> standoffRangeByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, Float> stationKeepingByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, DivisionMissionType> openingMissionByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, DivisionMissionType> mainMissionByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, LineStation> lineStationByType = new LinkedHashMap<>();
        private final Set<OtcPlay> enabledPlays = EnumSet.allOf(OtcPlay.class);
        private OtcAuthority otcAuthority = OtcAuthority.COMMAND_BY_DIRECTION;
        private int openingDurationSeconds = 45;

        public FleetDoctrineConfig() {
            for (DoctrineHullCategory cat : DoctrineHullCategory.values()) {
                minByCat.put(cat, cat.defaultMin);
                maxByCat.put(cat, cat.defaultMax);
            }
            for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
                stationSpacingByType.put(dt, dt.defaultStationSpacingUnits);
                divisionIntervalByType.put(dt, dt.defaultDivisionIntervalUnits);
                standoffRangeByType.put(dt, dt.defaultStandoffRangeUnits);
                stationKeepingByType.put(dt, dt.defaultStationKeepingMult);
                openingMissionByType.put(dt, dt.defaultOpeningMission);
                mainMissionByType.put(dt, dt.defaultMainMission);
                lineStationByType.put(dt, dt.defaultLineStation);
            }
        }

        public OtcAuthority getOtcAuthority() {
            return otcAuthority != null ? otcAuthority : OtcAuthority.COMMAND_BY_DIRECTION;
        }

        public void setOtcAuthority(OtcAuthority authority) {
            if (authority != null) {
                this.otcAuthority = authority;
            }
        }

        public boolean isPlayEnabled(OtcPlay play) {
            return play != null && enabledPlays.contains(play);
        }

        public void setPlayEnabled(OtcPlay play, boolean enabled) {
            if (play == null) return;
            if (enabled) {
                enabledPlays.add(play);
            } else {
                enabledPlays.remove(play);
            }
        }

        public Set<OtcPlay> getEnabledPlays() {
            return enabledPlays.isEmpty() ? EnumSet.noneOf(OtcPlay.class) : EnumSet.copyOf(enabledPlays);
        }

        public LineStation getLineStation(DoctrineDivisionType dt) {
            if (dt == null) return LineStation.CENTER;
            LineStation ps = lineStationByType.get(dt);
            return ps != null ? ps : dt.defaultLineStation;
        }

        public void setLineStation(DoctrineDivisionType dt, LineStation ps) {
            if (dt == null || ps == null) return;
            lineStationByType.put(dt, ps);
        }

        public int getOpeningDurationSeconds() {
            return Math.max(MIN_OPENING_DURATION_SEC, Math.min(MAX_OPENING_DURATION_SEC, openingDurationSeconds));
        }

        public void setOpeningDurationSeconds(int seconds) {
            this.openingDurationSeconds = Math.max(MIN_OPENING_DURATION_SEC, Math.min(MAX_OPENING_DURATION_SEC, seconds));
        }

        public int getStationSpacing(DoctrineDivisionType dt) {
            if (dt == null) return 450;
            Integer v = stationSpacingByType.get(dt);
            int raw = v != null ? v : dt.defaultStationSpacingUnits;
            return Math.max(MIN_STATION_SPACING_UNITS, Math.min(MAX_STATION_SPACING_UNITS, raw));
        }

        public void setStationSpacing(DoctrineDivisionType dt, int units) {
            if (dt == null) return;
            int clamped = Math.max(MIN_STATION_SPACING_UNITS, Math.min(MAX_STATION_SPACING_UNITS, units));
            stationSpacingByType.put(dt, clamped);
        }

        public int getDivisionInterval(DoctrineDivisionType dt) {
            if (dt == null) return 950;
            Integer v = divisionIntervalByType.get(dt);
            int raw = v != null ? v : dt.defaultDivisionIntervalUnits;
            return Math.max(MIN_DIVISION_INTERVAL_UNITS, Math.min(MAX_DIVISION_INTERVAL_UNITS, raw));
        }

        public void setDivisionInterval(DoctrineDivisionType dt, int units) {
            if (dt == null) return;
            int clamped = Math.max(MIN_DIVISION_INTERVAL_UNITS, Math.min(MAX_DIVISION_INTERVAL_UNITS, units));
            divisionIntervalByType.put(dt, clamped);
        }

        public int getStandoffRange(DoctrineDivisionType dt) {
            if (dt == null) return 850;
            Integer v = standoffRangeByType.get(dt);
            int raw = v != null ? v : dt.defaultStandoffRangeUnits;
            return Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(MAX_STANDOFF_RANGE_UNITS, raw));
        }

        public void setStandoffRange(DoctrineDivisionType dt, int units) {
            if (dt == null) return;
            int clamped = Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(MAX_STANDOFF_RANGE_UNITS, units));
            standoffRangeByType.put(dt, clamped);
        }

        public float getStationKeepingMult(DoctrineDivisionType dt) {
            if (dt == null) return 1.0f;
            Float v = stationKeepingByType.get(dt);
            float raw = v != null ? v : dt.defaultStationKeepingMult;
            return Math.max(0.65f, Math.min(1.85f, raw));
        }

        public void setStationKeepingMult(DoctrineDivisionType dt, float mult) {
            if (dt == null) return;
            float clamped = Math.max(0.65f, Math.min(1.85f, mult));
            stationKeepingByType.put(dt, clamped);
        }

        public DivisionMissionType getOpeningMission(DoctrineDivisionType dt) {
            if (dt == null) return DivisionMissionType.SCOUTING;
            DivisionMissionType m = openingMissionByType.get(dt);
            return m != null ? m : dt.defaultOpeningMission;
        }

        public void setOpeningMission(DoctrineDivisionType dt, DivisionMissionType mission) {
            if (dt == null || mission == null) return;
            openingMissionByType.put(dt, mission);
        }

        public DivisionMissionType getMainMission(DoctrineDivisionType dt) {
            if (dt == null) return DivisionMissionType.CLOSE_ACTION;
            DivisionMissionType m = mainMissionByType.get(dt);
            return m != null ? m : dt.defaultMainMission;
        }

        public void setMainMission(DoctrineDivisionType dt, DivisionMissionType mission) {
            if (dt == null || mission == null) return;
            mainMissionByType.put(dt, mission);
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
            Object opDurObj = map.get("opening_duration_sec");
            if (opDurObj instanceof Number) {
                cfg.setOpeningDurationSeconds(((Number) opDurObj).intValue());
            }
            Object authObj = map.get("bg_authority");
            if (authObj instanceof String) {
                cfg.setOtcAuthority(OtcAuthority.fromId((String) authObj, OtcAuthority.COMMAND_BY_DIRECTION));
            }
            for (OtcPlay play : OtcPlay.values()) {
                Object playObj = getWithLegacyKey(map, "bg_play_", play.name(), play.legacyName());
                if (playObj instanceof Boolean) {
                    cfg.setPlayEnabled(play, (Boolean) playObj);
                }
            }
            for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
                Object spObj = getWithLegacyKey(map, "spacing_", dt.name(), dt.legacyName());
                Object ispObj = getWithLegacyKey(map, "inter_spacing_", dt.name(), dt.legacyName());
                Object stObj = getWithLegacyKey(map, "standoff_", dt.name(), dt.legacyName());
                Object lsObj = getWithLegacyKey(map, "leash_", dt.name(), dt.legacyName());
                Object opMisObj = getWithLegacyKey(map, "opening_mission_", dt.name(), dt.legacyName());
                Object mnMisObj = getWithLegacyKey(map, "main_mission_", dt.name(), dt.legacyName());
                Object psObj = getWithLegacyKey(map, "pref_space_", dt.name(), dt.legacyName());
                if (spObj instanceof Number) {
                    cfg.setStationSpacing(dt, ((Number) spObj).intValue());
                }
                if (ispObj instanceof Number) {
                    cfg.setDivisionInterval(dt, ((Number) ispObj).intValue());
                }
                if (stObj instanceof Number) {
                    cfg.setStandoffRange(dt, ((Number) stObj).intValue());
                }
                if (lsObj instanceof Number) {
                    cfg.setStationKeepingMult(dt, ((Number) lsObj).floatValue());
                }
                if (opMisObj instanceof String) {
                    cfg.setOpeningMission(dt, DivisionMissionType.fromId((String) opMisObj, dt.defaultOpeningMission));
                }
                if (mnMisObj instanceof String) {
                    cfg.setMainMission(dt, DivisionMissionType.fromId((String) mnMisObj, dt.defaultMainMission));
                }
                if (psObj instanceof String) {
                    cfg.setLineStation(dt, LineStation.fromId((String) psObj, dt.defaultLineStation));
                }
            }
        }
        return cfg;
    }

    /**
     * Reads a doctrine value keyed by an enum name, falling back to the pre-v0.5.2 enum name so doctrine
     * settings saved before the naval-terminology rename still load.
     */
    private static Object getWithLegacyKey(Map<String, Object> map, String prefix, String name, String legacyName) {
        Object value = map.get(prefix + name);
        if (value == null && legacyName != null) {
            value = map.get(prefix + legacyName);
        }
        return value;
    }

    public static void saveFleetDoctrine(FleetDoctrineConfig cfg) {
        SectorAPI sector = Global.getSector();
        if (sector == null || cfg == null) return;
        Map<String, Object> serialized = new HashMap<>();
        serialized.put("opening_duration_sec", cfg.getOpeningDurationSeconds());
        serialized.put("bg_authority", cfg.getOtcAuthority().name());
        for (OtcPlay play : OtcPlay.values()) {
            serialized.put("bg_play_" + play.name(), cfg.isPlayEnabled(play));
        }
        for (DoctrineHullCategory cat : DoctrineHullCategory.values()) {
            serialized.put("min_" + cat.name(), cfg.getMin(cat));
            serialized.put("max_" + cat.name(), cfg.getMax(cat));
        }
        for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
            serialized.put("spacing_" + dt.name(), cfg.getStationSpacing(dt));
            serialized.put("inter_spacing_" + dt.name(), cfg.getDivisionInterval(dt));
            serialized.put("standoff_" + dt.name(), cfg.getStandoffRange(dt));
            serialized.put("leash_" + dt.name(), cfg.getStationKeepingMult(dt));
            serialized.put("opening_mission_" + dt.name(), cfg.getOpeningMission(dt).name());
            serialized.put("main_mission_" + dt.name(), cfg.getMainMission(dt).name());
            serialized.put("pref_space_" + dt.name(), cfg.getLineStation(dt).name());
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

    public static void updateOpeningDurationSeconds(int seconds) {
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setOpeningDurationSeconds(seconds);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeStationSpacing(DoctrineDivisionType dt, int newSpacingUnits) {
        if (dt == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setStationSpacing(dt, newSpacingUnits);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeDivisionInterval(DoctrineDivisionType dt, int newInterSpacingUnits) {
        if (dt == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setDivisionInterval(dt, newInterSpacingUnits);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeStandoffRange(DoctrineDivisionType dt, int newStandoffUnits) {
        if (dt == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setStandoffRange(dt, newStandoffUnits);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeStationKeeping(DoctrineDivisionType dt, float newStationKeepingMult) {
        if (dt == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setStationKeepingMult(dt, newStationKeepingMult);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeMission(DoctrineDivisionType dt, boolean isOpening, DivisionMissionType mission) {
        if (dt == null || mission == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        if (isOpening) {
            cfg.setOpeningMission(dt, mission);
        } else {
            cfg.setMainMission(dt, mission);
        }
        if (mission.autoLineStation != null) {
            cfg.setLineStation(dt, mission.autoLineStation);
        }
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeLineStation(DoctrineDivisionType dt, LineStation ps) {
        if (dt == null || ps == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setLineStation(dt, ps);
        saveFleetDoctrine(cfg);
    }

    public static void updateOtcAuthority(OtcAuthority authority) {
        if (authority == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setOtcAuthority(authority);
        saveFleetDoctrine(cfg);
    }

    public static void updateOtcPlay(OtcPlay play, boolean enabled) {
        if (play == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setPlayEnabled(play, enabled);
        saveFleetDoctrine(cfg);
    }

    public static void resetFleetDoctrineToDefaults() {
        saveFleetDoctrine(new FleetDoctrineConfig());
    }

    public enum DoctrineOption {
        ADAPTIVE("Adaptive (Default)", "Balanced division tactics: screens, flanks, or presses home the attack as the action develops."),
        SPEARHEAD("Spearhead", "Closes the enemy early and presses home the attack wherever it has the advantage."),
        FIRE_SUPPORT("Fire Support", "Keeps to maximum gun or strike-craft range and avoids close action."),
        ESCORT_AND_SCREEN("Escort & Screen", "Takes screening station on friendly battleship and carrier divisions against flankers."),
        FLANKING_STRIKE("Flanking & Missile Strike", "Works around the enemy flanks and looks for missile strike opportunities."),
        RESERVE("Reserve", "Holds astern of the line until a friendly division needs reinforcing.");

        public final String displayName;
        public final String summary;

        DoctrineOption(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        /** Pre-v0.5.2 saved division doctrine names. */
        private static final Map<String, String> LEGACY_NAMES = legacyNames(
                "STANDOFF", "FIRE_SUPPORT",
                "SCREEN_ESCORT", "ESCORT_AND_SCREEN",
                "FLANK_STRIKE", "FLANKING_STRIKE",
                "RESERVE_HOLD", "RESERVE");

        public static DoctrineOption fromId(String id) {
            if (id == null) return ADAPTIVE;
            try {
                return DoctrineOption.valueOf(LEGACY_NAMES.getOrDefault(id, id));
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

        // Per-Division Settings (null = inherit from Battle Group Settings for this division's archetype;
        // customStandoffRange is auto-calculated from longest-range firepower on ship add or Auto-Set button, or manually set)
        public Integer customStationSpacing = null;
        public Integer customDivisionInterval = null;
        public Integer customStandoffRange = null;
        public DivisionMissionType customOpeningMission = null;
        public DivisionMissionType customMainMission = null;
        public LineStation customLineStation = null;

        public PermanentDivisionSpec(String id, String name, DivisionArchetype archetype) {
            this.id = id;
            this.name = name;
            this.archetype = archetype != null ? archetype : DivisionArchetype.CRUISER_DIVISION;
        }
    }

    public static int resolveEffectiveStationSpacing(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customStationSpacing != null) {
            return Math.max(MIN_STATION_SPACING_UNITS, Math.min(MAX_STATION_SPACING_UNITS, spec.customStationSpacing));
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getStationSpacing(dt) : dt.defaultStationSpacingUnits;
    }

    public static int resolveEffectiveDivisionInterval(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customDivisionInterval != null) {
            return Math.max(MIN_DIVISION_INTERVAL_UNITS, Math.min(MAX_DIVISION_INTERVAL_UNITS, spec.customDivisionInterval));
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getDivisionInterval(dt) : dt.defaultDivisionIntervalUnits;
    }

    public static int resolveEffectiveStandoffRange(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customStandoffRange != null) {
            return Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(MAX_STANDOFF_RANGE_UNITS, spec.customStandoffRange));
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getStandoffRange(dt) : dt.defaultStandoffRangeUnits;
    }

    public static DivisionMissionType resolveEffectiveOpeningMission(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customOpeningMission != null) {
            return spec.customOpeningMission;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getOpeningMission(dt) : dt.defaultOpeningMission;
    }

    public static DivisionMissionType resolveEffectiveMainMission(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customMainMission != null) {
            return spec.customMainMission;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getMainMission(dt) : dt.defaultMainMission;
    }

    public static LineStation resolveEffectiveLineStation(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customLineStation != null) {
            return spec.customLineStation;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getLineStation(dt) : dt.defaultLineStation;
    }

    /**
     * Calculates the longest-range non-PD gun firepower across all ships currently assigned to the division
     * (scanning fitted ballistic/energy weapons with ship stat range bonuses and any fitted offensive fighter wings).
     * Missiles are excluded: a single long-range missile rack would otherwise push the whole division's standoff
     * far beyond the range its guns can actually fight at.
     * Returns 0 if the division has no ships or no non-PD weapons.
     */
    public static int computeDivisionLongestGunRange(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
        return computeDivisionLongestGunRange(spec, fleet, false);
    }

    private static int computeDivisionLongestGunRange(PermanentDivisionSpec spec, CampaignFleetAPI fleet, boolean includeMissiles) {
        if (spec == null || fleet == null || spec.memberIds.isEmpty()) return 0;
        List<FleetMemberAPI> members = getMembersInDivision(spec, fleet);
        float maxRange = 0f;

        for (FleetMemberAPI m : members) {
            if (m == null) continue;
            ShipVariantAPI variant = m.getVariant();
            if (variant == null) continue;

            for (String slotId : variant.getFittedWeaponSlots()) {
                if (slotId == null) continue;
                WeaponSpecAPI wSpec = variant.getWeaponSpec(slotId);
                if (wSpec == null) continue;
                Set<AIHints> hints = wSpec.getAIHints();
                boolean pdOnly = hints != null && (hints.contains(AIHints.PD_ONLY)
                        || (hints.contains(AIHints.PD) && !hints.contains(AIHints.ANTI_FTR) && wSpec.getMaxRange() <= 550f));
                if (pdOnly) continue;
                if (!includeMissiles && wSpec.getType() == WeaponType.MISSILE) continue;

                float baseRange = wSpec.getMaxRange();
                if (baseRange <= 0f) continue;

                float effectiveRange = baseRange;
                if (m.getStats() != null) {
                    WeaponType wt = wSpec.getType();
                    if (wt == WeaponType.BALLISTIC) {
                        effectiveRange = m.getStats().getBallisticWeaponRangeBonus().computeEffective(baseRange);
                    } else if (wt == WeaponType.ENERGY) {
                        effectiveRange = m.getStats().getEnergyWeaponRangeBonus().computeEffective(baseRange);
                    } else if (wt == WeaponType.MISSILE) {
                        effectiveRange = m.getStats().getMissileWeaponRangeBonus().computeEffective(baseRange);
                    }
                    if (wSpec.isBeam()) {
                        effectiveRange = m.getStats().getBeamWeaponRangeBonus().computeEffective(effectiveRange);
                    }
                }
                if (effectiveRange > maxRange) {
                    maxRange = effectiveRange;
                }
            }

            // Also check carrier fighter/bomber engagement range if fitted with offensive wings
            if (variant.getFittedWings() != null) {
                for (String wingId : variant.getFittedWings()) {
                    if (wingId == null) continue;
                    FighterWingSpecAPI wingSpec = Global.getSettings().getFighterWingSpec(wingId);
                    if (wingSpec != null && wingSpec.getRange() > maxRange) {
                        // Cap carrier wing standoff contribution to MAX_STANDOFF_RANGE_UNITS (2400u)
                        maxRange = Math.min(MAX_STANDOFF_RANGE_UNITS, Math.max(maxRange, Math.min(1800f, wingSpec.getRange())));
                    }
                }
            }
        }

        if (maxRange <= 0f) return 0;
        int rounded = Math.round(maxRange / 25f) * 25;
        return Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(MAX_STANDOFF_RANGE_UNITS, rounded));
    }

    public static void recalculateDivisionStandoffRangeFromGuns(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
        if (spec == null) return;
        int longest = computeDivisionLongestGunRange(spec, fleet);
        if (longest > 0) {
            spec.customStandoffRange = longest;
        }
    }

    /**
     * One-time save migration (v0.5.0): divisions whose Line Standoff was auto-set by the old missile-inclusive
     * calculation are recalculated from gun range. Manually chosen standoff values are left untouched.
     */
    public static void migrateAutoStandoffToGunRange() {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPlayerFleet() == null) return;
        Map<String, Object> pdata = sector.getPersistentData();
        if (Boolean.TRUE.equals(pdata.get(PERSISTENT_STANDOFF_MIGRATION_KEY))) return;

        CampaignFleetAPI fleet = sector.getPlayerFleet();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();
        int migrated = 0;
        for (PermanentDivisionSpec spec : specs) {
            if (spec.customStandoffRange == null) continue;
            int oldAuto = computeDivisionLongestGunRange(spec, fleet, true);
            if (oldAuto > 0 && oldAuto == spec.customStandoffRange) {
                int gunOnly = computeDivisionLongestGunRange(spec, fleet, false);
                if (gunOnly > 0 && gunOnly != oldAuto) {
                    spec.customStandoffRange = gunOnly;
                    migrated++;
                }
            }
        }
        saveSpecsToPersistentData(pdata, specs);
        pdata.put(PERSISTENT_STANDOFF_MIGRATION_KEY, Boolean.TRUE);
        if (migrated > 0) {
            LOG.info("[DivisionAI] Recalculated auto Line Standoff from gun range (missiles excluded) for " + migrated + " divisions.");
        }
    }

    /**
     * One-time save migration (v0.5.1): auto-generated division names that used the old prefixes are renamed to the
     * naval terminology ("Battle Div 2" -> "Battleship Div 2", "Mixed Carrier Div 1" -> "Carrier Group 1",
     * "Aux Group 1" -> "Service Group 1"). Names the player typed in themselves are left untouched.
     */
    public static void migrateDivisionNamesToNavalTerms() {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPlayerFleet() == null) return;
        Map<String, Object> pdata = sector.getPersistentData();
        if (Boolean.TRUE.equals(pdata.get(PERSISTENT_NAVAL_NAMES_MIGRATION_KEY))) return;

        Map<String, String> oldToNewPrefix = new LinkedHashMap<>();
        oldToNewPrefix.put("Battle Div", DivisionArchetype.BATTLESHIP_DIVISION.shortPrefix);
        oldToNewPrefix.put("Mixed Carrier Div", DivisionArchetype.CARRIER_GROUP.shortPrefix);
        oldToNewPrefix.put("Aux Group", DivisionArchetype.SERVICE_GROUP.shortPrefix);

        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();
        Set<String> takenNames = new HashSet<>();
        for (PermanentDivisionSpec spec : specs) {
            if (spec.name != null) takenNames.add(spec.name);
        }
        int renamed = 0;
        for (PermanentDivisionSpec spec : specs) {
            if (spec.name == null) continue;
            for (Map.Entry<String, String> e : oldToNewPrefix.entrySet()) {
                String oldPrefix = e.getKey() + " ";
                if (!spec.name.startsWith(oldPrefix)) continue;
                String suffix = spec.name.substring(oldPrefix.length());
                if (!suffix.matches("\\d+")) continue;
                String newName = e.getValue() + " " + suffix;
                if (takenNames.contains(newName)) continue;
                takenNames.remove(spec.name);
                takenNames.add(newName);
                spec.name = newName;
                renamed++;
                break;
            }
        }
        saveSpecsToPersistentData(pdata, specs);
        pdata.put(PERSISTENT_NAVAL_NAMES_MIGRATION_KEY, Boolean.TRUE);
        if (renamed > 0) {
            LOG.info("[DivisionAI] Renamed " + renamed + " auto-named divisions to naval terminology.");
        }
    }

    public static void autoSetDivisionStandoffRange(String divisionId) {
        SectorAPI sector = Global.getSector();
        if (sector == null || divisionId == null) return;
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();
        for (PermanentDivisionSpec spec : specs) {
            if (divisionId.equals(spec.id)) {
                recalculateDivisionStandoffRangeFromGuns(spec, fleet);
                break;
            }
        }
        saveSpecsToPersistentData(pdata, specs);
    }

    public static void updateIndividualDivisionSettings(
            String divisionId,
            boolean updateIntra,
            Integer intraSpacing,
            boolean updateInter,
            Integer interSpacing,
            boolean updateStandoff,
            Integer lineStandoff,
            boolean updateOpeningMission,
            DivisionMissionType openingMission,
            boolean updateMainMission,
            DivisionMissionType mainMission) {
        SectorAPI sector = Global.getSector();
        if (sector == null || divisionId == null) return;
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();
        for (PermanentDivisionSpec spec : specs) {
            if (divisionId.equals(spec.id)) {
                if (updateIntra) {
                    spec.customStationSpacing = (intraSpacing != null)
                            ? Math.max(MIN_STATION_SPACING_UNITS, Math.min(MAX_STATION_SPACING_UNITS, intraSpacing))
                            : null;
                }
                if (updateInter) {
                    spec.customDivisionInterval = (interSpacing != null)
                            ? Math.max(MIN_DIVISION_INTERVAL_UNITS, Math.min(MAX_DIVISION_INTERVAL_UNITS, interSpacing))
                            : null;
                }
                if (updateStandoff) {
                    spec.customStandoffRange = (lineStandoff != null)
                            ? Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(MAX_STANDOFF_RANGE_UNITS, lineStandoff))
                            : null;
                }
                if (updateOpeningMission) {
                    spec.customOpeningMission = openingMission;
                    if (openingMission != null && openingMission.autoLineStation != null) {
                        spec.customLineStation = openingMission.autoLineStation;
                    }
                }
                if (updateMainMission) {
                    spec.customMainMission = mainMission;
                    if (mainMission != null && mainMission.autoLineStation != null) {
                        spec.customLineStation = mainMission.autoLineStation;
                    }
                }
                break;
            }
        }
        saveSpecsToPersistentData(pdata, specs);
    }

    public static void updateIndividualDivisionLineStation(String divisionId, LineStation lineStation) {
        SectorAPI sector = Global.getSector();
        if (sector == null || divisionId == null) return;
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();
        for (PermanentDivisionSpec spec : specs) {
            if (divisionId.equals(spec.id)) {
                spec.customLineStation = lineStation;
                break;
            }
        }
        saveSpecsToPersistentData(pdata, specs);
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
        // Sort unassigned so ships with commanders lead new divisions first!
        Collections.sort(unassigned, new Comparator<FleetMemberAPI>() {
            @Override
            public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                boolean aCmd = a.getCaptain() != null && !a.getCaptain().isDefault();
                boolean bCmd = b.getCaptain() != null && !b.getCaptain().isDefault();
                if (aCmd != bCmd) return aCmd ? -1 : 1;
                return Float.compare(b.getDeploymentPointsCost(), a.getDeploymentPointsCost());
            }
        });
        while (!unassigned.isEmpty()) {
            FleetMemberAPI lead = unassigned.remove(0);
            DoctrineHullCategory leadCat = classifyHullCategory(lead);
            DivisionArchetype arch = classifyFleetMemberArchetype(lead);
            int effectiveCatMax = Math.min(MAX_DIVISION_SIZE, Math.max(1, doctrine.getMax(leadCat)));

            PermanentDivisionSpec newSpec = createNewDivisionInternal(specs, pdata, arch);
            newSpec.memberIds.add(lead.getId());

            // Prefer filling remaining slots with non-commanded ships first if other same-category ships have commanders to lead the next division
            for (int pass = 0; pass < 2 && newSpec.memberIds.size() < MAX_DIVISION_SIZE; pass++) {
                for (int i = 0; i < unassigned.size() && newSpec.memberIds.size() < MAX_DIVISION_SIZE; ) {
                    FleetMemberAPI cand = unassigned.get(i);
                    DoctrineHullCategory cCat = classifyHullCategory(cand);
                    DivisionArchetype cArch = classifyFleetMemberArchetype(cand);
                    boolean candHasCmd = cand.getCaptain() != null && !cand.getCaptain().isDefault();
                    if (pass == 0 && candHasCmd) {
                        i++;
                        continue;
                    }
                    if (cCat == leadCat && cArch == arch
                            && countCategoryInDivision(newSpec, leadCat, fleetById) < effectiveCatMax) {
                        newSpec.memberIds.add(cand.getId());
                        unassigned.remove(i);
                    } else {
                        i++;
                    }
                }
            }
            ensureValidFlagship(newSpec, fleet);
        }

        for (PermanentDivisionSpec s : specs) {
            if (s.customStandoffRange == null) {
                recalculateDivisionStandoffRangeFromGuns(s, fleet);
            }
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
            recalculateDivisionStandoffRangeFromGuns(created, sector.getPlayerFleet());
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
            if (spec.memberIds.remove(memberId)) {
                ensureValidFlagship(spec, fleet);
                recalculateDivisionStandoffRangeFromGuns(spec, fleet);
            }
            if (targetDivisionId.equals(spec.id)) {
                target = spec;
            }
        }
        if (target != null && !target.memberIds.contains(memberId) && target.memberIds.size() < MAX_DIVISION_SIZE) {
            target.memberIds.add(memberId);
            ensureValidFlagship(target, fleet);
            recalculateDivisionStandoffRangeFromGuns(target, fleet);
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
                recalculateDivisionStandoffRangeFromGuns(spec, fleet);
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
            BattleGroupState battleGroup,
            List<ShipCapabilityProfile> unassignedPlayerHulls,
            List<DivisionState> newlyCreatedOut) {

        if (battleGroup == null || battleGroup.side != 0 || unassignedPlayerHulls.isEmpty()) {
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

            // Check if this permanent division already exists in the active combat BattleGroupState (e.g. reinforcement wave)
            DivisionState existingCombatDiv = findExistingCombatDivisionById(battleGroup, spec.id);
            if (existingCombatDiv != null) {
                existingCombatDiv.members.add(prof.ship);
                existingCombatDiv.profiles.add(prof);
                battleGroup.shipIdToDivision.put(prof.ship.getId(), existingCombatDiv);
                if (fmId.equals(spec.flagshipMemberId) || existingCombatDiv.flagship == null || !existingCombatDiv.flagship.isAlive()) {
                    existingCombatDiv.flagship = prof.ship;
                }
                DivisionAutoFormer.recomputeDivisionCapabilities(existingCombatDiv);
                applyDoctrineModifiersToCombatDivision(existingCombatDiv, spec);
                DivisionCombatLog.logReinforcementJoined(existingCombatDiv, prof.ship);
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

        int fallbackNumber = battleGroup.divisions.size() + 1;
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
            String resolvedDisplayName = DivisionNomenclature.resolveDynamicDivisionDisplayName(
                    battleGroup.nomenclatureCategory,
                    spec.archetype,
                    fallbackNumber,
                    spec.name);
            CommanderProfile cmdr = new CommanderProfile(captain, resolvedDisplayName + " Cmdr");
            DivisionState combatDiv = new DivisionState(
                    spec.id,
                    battleGroup.side,
                    fallbackNumber++,
                    spec.archetype,
                    resolvedDisplayName,
                    cmdr);
            combatDiv.nomenclatureCategory = battleGroup.nomenclatureCategory;
            combatDiv.flagship = leaderShip;
            for (ShipCapabilityProfile p : members) {
                combatDiv.members.add(p.ship);
                combatDiv.profiles.add(p);
                battleGroup.shipIdToDivision.put(p.ship.getId(), combatDiv);
            }
            DivisionAutoFormer.recomputeDivisionCapabilities(combatDiv);
            applyDoctrineModifiersToCombatDivision(combatDiv, spec);
            battleGroup.divisions.add(combatDiv);
            newlyCreatedOut.add(combatDiv);
        }

        return leftoverUnassigned;
    }

    private static void applyDoctrineModifiersToCombatDivision(DivisionState div, PermanentDivisionSpec spec) {
        if (div == null || spec == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        div.stationSpacing = resolveEffectiveStationSpacing(spec, cfg);
        div.divisionInterval = resolveEffectiveDivisionInterval(spec, cfg);
        div.standoffRange = resolveEffectiveStandoffRange(spec, cfg);
        div.openingPhaseDurationSeconds = cfg.getOpeningDurationSeconds();
        div.openingMission = resolveEffectiveOpeningMission(spec, cfg);
        div.mainMission = resolveEffectiveMainMission(spec, cfg);
        div.lineStation = resolveEffectiveLineStation(spec, cfg);
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

        DoctrineOption doctrine = spec.doctrine;
        if (doctrine == null) return;
        switch (doctrine) {
            case SPEARHEAD:
                div.commander.aggressionBias = Math.max(div.commander.aggressionBias, 1.35f);
                div.commander.commitForceRatio = Math.min(div.commander.commitForceRatio, 0.90f);
                break;
            case FIRE_SUPPORT:
                div.commander.aggressionBias = Math.min(div.commander.aggressionBias, 0.80f);
                div.commander.rotationFluxThreshold = Math.min(div.commander.rotationFluxThreshold, 0.65f);
                break;
            case ESCORT_AND_SCREEN:
                div.isScreenCapable = true;
                div.isFastFlanker = false;
                break;
            case FLANKING_STRIKE:
                div.isFastFlanker = true;
                break;
            case RESERVE:
                div.isDesignatedReserve = true;
                break;
            case ADAPTIVE:
            default:
                break;
        }
    }

    private static DivisionState findExistingCombatDivisionById(BattleGroupState battleGroup, String permDivId) {
        if (battleGroup == null || permDivId == null) return null;
        for (DivisionState d : battleGroup.divisions) {
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

        List<FleetMemberAPI> fleetCarriers = new ArrayList<>();
        List<FleetMemberAPI> lightCarriers = new ArrayList<>();
        List<FleetMemberAPI> escortCarriers = new ArrayList<>();
        List<FleetMemberAPI> capitals = new ArrayList<>();
        List<FleetMemberAPI> strikeCruisers = new ArrayList<>();
        List<FleetMemberAPI> lineCruisers = new ArrayList<>();
        List<FleetMemberAPI> missileDestroyers = new ArrayList<>();
        List<FleetMemberAPI> lineDestroyers = new ArrayList<>();
        List<FleetMemberAPI> frigates = new ArrayList<>();
        List<FleetMemberAPI> phaseShips = new ArrayList<>();
        List<FleetMemberAPI> auxiliaries = new ArrayList<>();

        for (FleetMemberAPI m : eligible) {
            DivisionArchetype arch = classifyFleetMemberArchetype(m);
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
                    missileDestroyers.add(m);
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

        // 1. Carrier Divisions (Fleet Carrier, Light Carrier, Escort Carrier)
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.FLEET_CARRIER_DIVISION, fleetCarriers, 2);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.LIGHT_CARRIER_DIVISION, lightCarriers, 3);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.ESCORT_CARRIER_DIVISION, escortCarriers, 4);

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
            addBuiltDivision(specs, pdata, DivisionArchetype.BATTLESHIP_DIVISION, group);
        }

        // 3. Cruiser Divisions
        if (strikeCruisers.size() == 1 && !lineCruisers.isEmpty()) {
            lineCruisers.add(strikeCruisers.remove(0));
        }
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.STRIKE_CRUISER_DIVISION, strikeCruisers, 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.CRUISER_DIVISION, lineCruisers, 4);

        // 4. Destroyer Divisions
        if (missileDestroyers.size() == 1 && lineDestroyers.size() == 1) {
            missileDestroyers.addAll(lineDestroyers);
            lineDestroyers.clear();
        }
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.MISSILE_DESTROYER_DIVISION, missileDestroyers, 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.DESTROYER_DIVISION, lineDestroyers, 4);

        // 5. Frigate, Phase, and Auxiliary Divisions
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.FRIGATE_DIVISION, frigates, 5);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.PHASE_DIVISION, phaseShips, 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.SERVICE_GROUP, auxiliaries, 5);

        return specs;
    }

    public static float computeFlagshipCandidateScore(FleetMemberAPI m, boolean preferCarrierFlagship) {
        if (m == null) return -1f;
        float score = m.getDeploymentPointsCost();
        if (m.getCaptain() != null && !m.getCaptain().isDefault()) {
            // Ships that already have a commander naturally take top priority as division flagships
            score += 1000f + m.getCaptain().getStats().getLevel() * 25f;
        }
        if (m.isFlagship()) {
            score += 500f;
        }
        if (preferCarrierFlagship && (m.isCarrier() || m.getNumFlightDecks() > 0)) {
            score += 400f;
        }
        return score;
    }

    public static List<List<FleetMemberAPI>> distributePoolWithCommandersFirst(
            List<FleetMemberAPI> pool,
            int maxPerDiv) {
        List<List<FleetMemberAPI>> chunks = new ArrayList<>();
        if (pool == null || pool.isEmpty()) return chunks;
        int cap = Math.max(1, Math.min(MAX_DIVISION_SIZE, maxPerDiv));

        List<FleetMemberAPI> withCmd = new ArrayList<>();
        List<FleetMemberAPI> withoutCmd = new ArrayList<>();
        for (FleetMemberAPI m : pool) {
            if (m == null) continue;
            if (m.getCaptain() != null && !m.getCaptain().isDefault()) {
                withCmd.add(m);
            } else {
                withoutCmd.add(m);
            }
        }
        Comparator<FleetMemberAPI> byScore = new Comparator<FleetMemberAPI>() {
            @Override
            public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                return Float.compare(
                        computeFlagshipCandidateScore(b, false),
                        computeFlagshipCandidateScore(a, false));
            }
        };
        Collections.sort(withCmd, byScore);
        Collections.sort(withoutCmd, byScore);

        // Compute division sizes so we don't leave a lone 1-ship tail when avoidable
        int total = withCmd.size() + withoutCmd.size();
        List<Integer> targetSizes = new ArrayList<>();
        int rem = total;
        while (rem > 0) {
            int take = Math.min(cap, rem);
            if (rem > cap && (rem - take) == 1 && take > 2) {
                take -= 1;
            }
            targetSizes.add(take);
            rem -= take;
        }

        for (int i = 0; i < targetSizes.size(); i++) {
            chunks.add(new ArrayList<FleetMemberAPI>());
        }

        // 1. Seed each division with 1 commander-led ship first (so every division gets a commander flagship if possible)
        int cmdIdx = 0;
        for (int c = 0; c < chunks.size() && cmdIdx < withCmd.size(); c++) {
            if (chunks.get(c).size() < targetSizes.get(c)) {
                chunks.get(c).add(withCmd.get(cmdIdx++));
            }
        }

        // 2. Remaining ships (any extra commander-led ships + non-commanded ships) fill out the divisions
        List<FleetMemberAPI> rest = new ArrayList<>();
        while (cmdIdx < withCmd.size()) {
            rest.add(withCmd.get(cmdIdx++));
        }
        rest.addAll(withoutCmd);

        int rIdx = 0;
        for (int c = 0; c < chunks.size(); c++) {
            List<FleetMemberAPI> divList = chunks.get(c);
            int target = targetSizes.get(c);
            while (divList.size() < target && rIdx < rest.size()) {
                divList.add(rest.get(rIdx++));
            }
        }
        return chunks;
    }

    private static void chunkFleetMembersIntoSpecs(
            List<PermanentDivisionSpec> specs,
            Map<String, Object> pdata,
            DivisionArchetype archetype,
            List<FleetMemberAPI> pool,
            int maxPerDiv) {
        List<List<FleetMemberAPI>> chunks = distributePoolWithCommandersFirst(pool, maxPerDiv);
        for (List<FleetMemberAPI> sub : chunks) {
            addBuiltDivision(specs, pdata, archetype, sub);
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
        boolean preferCarrier = archetype != null && archetype.isCarrierArchetype();
        for (FleetMemberAPI m : members) {
            spec.memberIds.add(m.getId());
            float score = computeFlagshipCandidateScore(m, preferCarrier);
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

        String bucketKey = DivisionNomenclature.resolvePrefixBucketKey(arch);
        int prefixNumber = 1;
        for (PermanentDivisionSpec existing : specs) {
            if (bucketKey.equals(DivisionNomenclature.resolvePrefixBucketKey(existing.archetype))) {
                prefixNumber++;
            }
        }

        String id = "perm_div_" + nextSeq;
        String name = DivisionNomenclature.formatPlayerDivisionName(arch, prefixNumber);
        PermanentDivisionSpec created = new PermanentDivisionSpec(id, name, arch);
        specs.add(created);
        return created;
    }

    /**
     * Applies the player's current DivisionNomenclature naming scheme (preset, numbering style,
     * and any custom archetype prefixes) to all existing permanent divisions in the player's fleet.
     */
    public static int applyPlayerNamingSchemeToExistingDivisions() {
        SectorAPI sector = Global.getSector();
        if (sector == null) return 0;
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();
        Map<String, Integer> seqByBucket = new LinkedHashMap<>();
        int updated = 0;
        for (PermanentDivisionSpec spec : specs) {
            if (spec == null) continue;
            DivisionArchetype arch = spec.archetype != null ? spec.archetype : DivisionArchetype.CRUISER_DIVISION;
            String bucketKey = DivisionNomenclature.resolvePrefixBucketKey(arch);
            int seq = (seqByBucket.containsKey(bucketKey) ? seqByBucket.get(bucketKey) : 0) + 1;
            seqByBucket.put(bucketKey, seq);
            String newName = DivisionNomenclature.formatPlayerDivisionName(arch, seq);
            if (!newName.equals(spec.name)) {
                spec.name = newName;
                updated++;
            }
        }
        saveSpecsToPersistentData(pdata, specs);
        return updated;
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
            return DivisionArchetype.SERVICE_GROUP;
        }

        boolean isPhase = member.isPhaseShip() || hints.contains(ShipTypeHints.PHASE);
        if (isPhase) {
            return DivisionArchetype.PHASE_DIVISION;
        }

        int bays = (variant != null && variant.getFittedWings() != null) ? variant.getFittedWings().size() : 0;
        if (bays == 0 && hullSpec != null) {
            bays = hullSpec.getFighterBays();
        }
        // Battlecarriers (e.g. Legion, Odyssey — hulls with fighter bays + COMBAT hint) default to Battle Divisions!
        boolean isBattlecarrier = (bays > 0) && hints.contains(ShipTypeHints.COMBAT);
        boolean isDedicatedCarrier = !isBattlecarrier
                && (member.isCarrier() || hints.contains(ShipTypeHints.CARRIER) || bays >= 2);
        if (isDedicatedCarrier) {
            switch (size) {
                case CAPITAL_SHIP:
                    return DivisionArchetype.FLEET_CARRIER_DIVISION;
                case CRUISER:
                    return DivisionArchetype.LIGHT_CARRIER_DIVISION;
                case DESTROYER:
                case FRIGATE:
                default:
                    return DivisionArchetype.ESCORT_CARRIER_DIVISION;
            }
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
                return DivisionArchetype.BATTLESHIP_DIVISION;
            case CRUISER:
                return (strikeWeaponCount >= 2)
                        ? DivisionArchetype.STRIKE_CRUISER_DIVISION
                        : DivisionArchetype.CRUISER_DIVISION;
            case DESTROYER:
                return (strikeWeaponCount >= 1)
                        ? DivisionArchetype.MISSILE_DESTROYER_DIVISION
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
            if (fleet != null && spec.archetype == DivisionArchetype.CARRIER_GROUP) {
                Map<String, FleetMemberAPI> byId = mapFleetMembersById(fleet);
                FleetMemberAPI currFlag = byId.get(spec.flagshipMemberId);
                boolean currIsCarrier = currFlag != null && (currFlag.isCarrier() || currFlag.getNumFlightDecks() > 0);
                if (!currIsCarrier) {
                    // Check if any member is a carrier; if so, fall through to promote the carrier to flagship!
                    boolean anyCarrier = false;
                    for (String id : spec.memberIds) {
                        FleetMemberAPI m = byId.get(id);
                        if (m != null && (m.isCarrier() || m.getNumFlightDecks() > 0)) {
                            anyCarrier = true;
                            break;
                        }
                    }
                    if (!anyCarrier) return;
                } else {
                    return;
                }
            } else {
                return;
            }
        }
        if (fleet == null) {
            spec.flagshipMemberId = spec.memberIds.get(0);
            return;
        }
        Map<String, FleetMemberAPI> byId = mapFleetMembersById(fleet);
        FleetMemberAPI best = null;
        float bestScore = -1f;
        boolean preferCarrierFlagship = spec.archetype != null && spec.archetype.isCarrierArchetype();
        for (String id : spec.memberIds) {
            FleetMemberAPI m = byId.get(id);
            if (m == null) continue;
            float score = computeFlagshipCandidateScore(m, preferCarrierFlagship);
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
                    arch = DivisionArchetype.valueOf(DivisionArchetype.resolveLegacyName(archStr));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (id == null || name == null) continue;

            PermanentDivisionSpec spec = new PermanentDivisionSpec(id, name, arch);
            spec.flagshipMemberId = flagshipId;
            spec.doctrine = DoctrineOption.fromId(docStr);

            Object cIntra = map.get("customIntraSpacing");
            Object cInter = map.get("customInterSpacing");
            Object cStandoff = map.get("customLineStandoff");
            Object cOpenMis = map.get("customOpeningMission");
            Object cMainMis = map.get("customMainMission");
            Object cPrefSpace = map.get("customPreferredSpace");
            if (cIntra instanceof Number) spec.customStationSpacing = ((Number) cIntra).intValue();
            if (cInter instanceof Number) spec.customDivisionInterval = ((Number) cInter).intValue();
            if (cStandoff instanceof Number) spec.customStandoffRange = ((Number) cStandoff).intValue();
            if (cOpenMis instanceof String) spec.customOpeningMission = DivisionMissionType.fromId((String) cOpenMis, null);
            if (cMainMis instanceof String) spec.customMainMission = DivisionMissionType.fromId((String) cMainMis, null);
            if (cPrefSpace instanceof String) spec.customLineStation = LineStation.fromId((String) cPrefSpace, null);

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
            if (spec.customStationSpacing != null) map.put("customIntraSpacing", spec.customStationSpacing);
            if (spec.customDivisionInterval != null) map.put("customInterSpacing", spec.customDivisionInterval);
            if (spec.customStandoffRange != null) map.put("customLineStandoff", spec.customStandoffRange);
            if (spec.customOpeningMission != null) map.put("customOpeningMission", spec.customOpeningMission.name());
            if (spec.customMainMission != null) map.put("customMainMission", spec.customMainMission.name());
            if (spec.customLineStation != null) map.put("customPreferredSpace", spec.customLineStation.name());
            serialized.add(map);
        }
        pdata.put(PERSISTENT_DIVISIONS_KEY, serialized);
    }
}
