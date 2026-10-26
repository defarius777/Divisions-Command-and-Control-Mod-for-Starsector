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
    private static final String PERSISTENT_STANDOFF_MIGRATION_KEY = "divisionai_standoff_gun_only_v1";

    /** Hard cap on division size regardless of individual hull maximums. */
    public static final int MAX_DIVISION_SIZE = 10;

    /** In-game length of the Paragon-class Battleship (from data/hulls/paragon.ship height = 364u, bounds = 354u). */
    public static final int PARAGON_LENGTH_UNITS = 364;

    public static final int MIN_INTRA_SPACING_UNITS = 150;
    public static final int MAX_INTRA_SPACING_UNITS = 1200;
    public static final int MIN_INTER_SPACING_UNITS = 400;
    public static final int MAX_INTER_SPACING_UNITS = 2200;
    public static final int MIN_STANDOFF_UNITS = 300;
    public static final int MAX_STANDOFF_UNITS = 2400;
    public static final int MIN_OPENING_DURATION_SEC = 30;
    public static final int MAX_OPENING_DURATION_SEC = 60;

    /**
     * Preferred battlefield depth zone for a division (Forward, Middle, Back).
     * Automatically selected by certain mission types, or manually set by the player.
     */
    public enum PreferredSpace {
        FORWARD("Forward", "Deploys and maneuvers ahead of the main battle line."),
        MIDDLE("Middle", "Maintains station along the main battle line."),
        BACK("Back", "Stays behind the main battle line in rear standoff or reserve.");

        public final String displayName;
        public final String summary;

        PreferredSpace(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public static PreferredSpace fromId(String id, PreferredSpace fallback) {
            if (id == null) return fallback;
            try {
                return PreferredSpace.valueOf(id);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Selectable Opening Mission (first 30-60s) and Main Mission (rest of battle) for Battlegroup
     * division types and individual divisions, including both General Combat Missions,
     * Carrier Missions & Tactics (a..f), and generic "Battlegroup Reserve".
     */
    public enum DivisionMissionType {
        // General Combat Missions
        RECONNAISSANCE(
                "a. \"Reconnaissance\"",
                "Reconnaissance",
                "Probe forward, seize unclaimed tactical objectives/buoys, and maintain standoff recon.",
                false,
                PreferredSpace.FORWARD),
        DOMINATE_SPACE_INTO_BRAWL(
                "b. \"Dominate Space into Brawl\"",
                "Dominate Space into Brawl",
                "Advance to contest space and close aggressively into a decisive main-line brawl.",
                false,
                PreferredSpace.FORWARD),
        STANDOFF_AT_RANGE(
                "c. \"Standoff at Range\"",
                "Standoff at Range",
                "Hold firing line at Line Standoff distance (longest weapon range) and kite back if pressed.",
                false,
                PreferredSpace.MIDDLE),
        OUTFLANK_AND_DESTROY(
                "d. \"Outflank and Destroy\"",
                "Outflank and Destroy",
                "Swing wide around enemy flanks to envelop and destroy isolated or vulnerable targets.",
                false,
                PreferredSpace.FORWARD),
        SCREEN(
                "e. \"Screen\"",
                "Screen",
                "Form a spaced forward/flank picket screen protecting heavier friendly divisions.",
                false,
                PreferredSpace.MIDDLE),

        // Carrier Missions and Tactics (a..f)
        CARRIER_RECON_STRIKE_CRAFT(
                "a. \"Reconnaissance by Strike Craft\"",
                "Recon by Strike Craft",
                "Keep carriers safely back while sending strike craft forward to spot and harass.",
                true,
                PreferredSpace.BACK),
        CARRIER_VOID_SUPERIORITY(
                "b. \"Void Superiority\"",
                "Void Superiority",
                "Contest fighter/air superiority and strip enemy strike craft and light flankers.",
                true,
                PreferredSpace.MIDDLE),
        CARRIER_PROTECT_FLEET(
                "c. \"Protect Fleet\"",
                "Protect Fleet",
                "Provide fleet air defense cover over friendly heavy/endangered divisions.",
                true,
                PreferredSpace.BACK),
        CARRIER_SUCCESSIVE_STRIKES(
                "d. \"Successive Strikes/Fire at Will\"",
                "Successive Strikes / Fire at Will",
                "Launch continuous strike craft attacks at will against the best visible enemy target.",
                true,
                PreferredSpace.BACK),
        CARRIER_DIVISION_COORDINATED_STRIKE(
                "e. \"Division Coordinated Strike\"",
                "Division Coordinated Strike",
                "Synchronize all carriers in this division onto the same focus target ship.",
                true,
                PreferredSpace.BACK),
        CARRIER_MULTI_DIVISION_STRIKE(
                "f. \"Multi-Division Coordinated Strike\"",
                "Multi-Division Coordinated Strike",
                "Synchronize strike craft with the Battlegroup Main Effort / missile divisions on primary threat.",
                true,
                PreferredSpace.BACK),

        // Universal Reserve Mission
        BATTLEGROUP_RESERVE(
                "g. \"Battlegroup Reserve\"",
                "Battlegroup Reserve",
                "Stay behind the front line in reserve until called up by the Battlegroup Commander for relief/tasks.",
                false,
                PreferredSpace.BACK);

        public final String dropdownLabel;
        public final String shortName;
        public final String summary;
        public final boolean carrierSpecific;
        public final boolean isCarrierMission;
        public final PreferredSpace autoPreferredSpace;

        DivisionMissionType(
                String dropdownLabel,
                String shortName,
                String summary,
                boolean carrierSpecific,
                PreferredSpace autoPreferredSpace) {
            this.dropdownLabel = dropdownLabel;
            this.shortName = shortName;
            this.summary = summary;
            this.carrierSpecific = carrierSpecific;
            this.isCarrierMission = carrierSpecific;
            this.autoPreferredSpace = autoPreferredSpace;
        }

        public static List<DivisionMissionType> getOptionsForDivisionType(boolean isCarrierType) {
            List<DivisionMissionType> list = new ArrayList<>();
            for (DivisionMissionType m : values()) {
                if (m == BATTLEGROUP_RESERVE) {
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

        public static DivisionMissionType fromId(String id, DivisionMissionType fallback) {
            if (id == null) return fallback;
            try {
                return DivisionMissionType.valueOf(id);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Configurable division types in Battlegroup Settings (Fleet Doctrine), with default:
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
                DivisionMissionType.RECONNAISSANCE,
                DivisionMissionType.OUTFLANK_AND_DESTROY,
                PreferredSpace.FORWARD),
        DESTROYER(
                "Destroyer Division",
                DivisionArchetype.DESTROYER_DIVISION,
                450,
                950,
                850,
                1.05f,
                DivisionMissionType.RECONNAISSANCE,
                DivisionMissionType.SCREEN,
                PreferredSpace.MIDDLE),
        CRUISER(
                "Cruiser Division",
                DivisionArchetype.CRUISER_DIVISION,
                525,
                1050,
                800,
                1.00f,
                DivisionMissionType.DOMINATE_SPACE_INTO_BRAWL,
                DivisionMissionType.DOMINATE_SPACE_INTO_BRAWL,
                PreferredSpace.MIDDLE),
        STRIKE_CRUISER(
                "Strike Cruiser Division",
                DivisionArchetype.STRIKE_CRUISER_DIVISION,
                550,
                1100,
                900,
                1.10f,
                DivisionMissionType.RECONNAISSANCE,
                DivisionMissionType.OUTFLANK_AND_DESTROY,
                PreferredSpace.FORWARD),
        BATTLE_CAPITAL(
                "Battle Division (Capital)",
                DivisionArchetype.BATTLE_DIVISION,
                675,
                1200,
                750,
                0.95f,
                DivisionMissionType.DOMINATE_SPACE_INTO_BRAWL,
                DivisionMissionType.DOMINATE_SPACE_INTO_BRAWL,
                PreferredSpace.FORWARD),
        FLEET_CARRIER(
                "Fleet Carrier Division (Capital)",
                DivisionArchetype.FLEET_CARRIER_DIVISION,
                650,
                1350,
                1600,
                0.95f,
                DivisionMissionType.CARRIER_RECON_STRIKE_CRAFT,
                DivisionMissionType.CARRIER_MULTI_DIVISION_STRIKE,
                PreferredSpace.BACK),
        LIGHT_CARRIER(
                "Light Carrier Division (Cruiser)",
                DivisionArchetype.LIGHT_CARRIER_DIVISION,
                550,
                1250,
                1450,
                0.95f,
                DivisionMissionType.CARRIER_RECON_STRIKE_CRAFT,
                DivisionMissionType.CARRIER_DIVISION_COORDINATED_STRIKE,
                PreferredSpace.BACK),
        ESCORT_CARRIER(
                "Escort Carrier Division (Destroyer & Below)",
                DivisionArchetype.ESCORT_CARRIER_DIVISION,
                475,
                1150,
                1350,
                0.95f,
                DivisionMissionType.CARRIER_PROTECT_FLEET,
                DivisionMissionType.CARRIER_SUCCESSIVE_STRIKES,
                PreferredSpace.BACK),
        CARRIER_ESCORT(
                "Mixed Carrier Division",
                DivisionArchetype.CARRIER_AND_ESCORT,
                575,
                1300,
                1450,
                0.95f,
                DivisionMissionType.CARRIER_RECON_STRIKE_CRAFT,
                DivisionMissionType.CARRIER_DIVISION_COORDINATED_STRIKE,
                PreferredSpace.BACK),
        PHASE_RAIDER(
                "Phase Division",
                DivisionArchetype.PHASE_DIVISION,
                475,
                1100,
                1050,
                1.25f,
                DivisionMissionType.RECONNAISSANCE,
                DivisionMissionType.OUTFLANK_AND_DESTROY,
                PreferredSpace.FORWARD),
        AUXILIARY(
                "Auxiliary Convoy",
                DivisionArchetype.AUXILIARY_CONVOY,
                450,
                1200,
                1500,
                0.90f,
                DivisionMissionType.BATTLEGROUP_RESERVE,
                DivisionMissionType.BATTLEGROUP_RESERVE,
                PreferredSpace.BACK);

        public final String displayName;
        public final DivisionArchetype primaryArchetype;
        public final int defaultIdealSpacingUnits;
        public final int defaultInterSpacingUnits;
        public final int defaultLineStandoffUnits;
        public final float defaultLeashMult;
        public final DivisionMissionType defaultOpeningMission;
        public final DivisionMissionType defaultMainMission;
        public final PreferredSpace defaultPreferredSpace;

        DoctrineDivisionType(
                String displayName,
                DivisionArchetype primaryArchetype,
                int defaultIdealSpacingUnits,
                int defaultInterSpacingUnits,
                int defaultLineStandoffUnits,
                float defaultLeashMult,
                DivisionMissionType defaultOpeningMission,
                DivisionMissionType defaultMainMission,
                PreferredSpace defaultPreferredSpace) {
            this.displayName = displayName;
            this.primaryArchetype = primaryArchetype;
            this.defaultIdealSpacingUnits = defaultIdealSpacingUnits;
            this.defaultInterSpacingUnits = defaultInterSpacingUnits;
            this.defaultLineStandoffUnits = defaultLineStandoffUnits;
            this.defaultLeashMult = defaultLeashMult;
            this.defaultOpeningMission = defaultOpeningMission;
            this.defaultMainMission = defaultMainMission;
            this.defaultPreferredSpace = defaultPreferredSpace;
        }

        public boolean isCarrierType() {
            return primaryArchetype != null && primaryArchetype.isCarrierArchetype();
        }

        public static DoctrineDivisionType forArchetype(DivisionArchetype arch) {
            if (arch == null) return CRUISER;
            switch (arch) {
                case FRIGATE_DIVISION:
                    return FRIGATE;
                case DESTROYER_DIVISION:
                case TORPEDO_DESTROYER_DIVISION:
                    return DESTROYER;
                case STRIKE_CRUISER_DIVISION:
                    return STRIKE_CRUISER;
                case BATTLE_DIVISION:
                    return BATTLE_CAPITAL;
                case FLEET_CARRIER_DIVISION:
                    return FLEET_CARRIER;
                case LIGHT_CARRIER_DIVISION:
                    return LIGHT_CARRIER;
                case ESCORT_CARRIER_DIVISION:
                    return ESCORT_CARRIER;
                case CARRIER_AND_ESCORT:
                    return CARRIER_ESCORT;
                case PHASE_DIVISION:
                    return PHASE_RAIDER;
                case AUXILIARY_CONVOY:
                    return AUXILIARY;
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
     * How much authority the Battlegroup Commander has over each division's configured Opening/Main Mission
     * when it orchestrates the battle (selectable in the Fleet Doctrine window).
     */
    public enum BattlegroupAuthority {
        HYBRID(
                "A. \"Hybrid\"",
                "Hybrid",
                "Missions set each division's style; the BG picks targets, timing, sectors and flank sides within it, "
                        + "and only fully re-tasks reserve, relief and pursuit divisions."),
        CENTRAL_PLANNING(
                "B. \"Central Planning\"",
                "Central Planning",
                "BG taskings replace missions whenever issued (fix, support, flank, relieve, pursue, strike); "
                        + "missions are only the default when the BG has no orders for a division."),
        MISSION_TACTICS(
                "C. \"Mission-Type Tactics\"",
                "Mission-Type Tactics",
                "The BG states intent (targets, flank sides, strike times, relief requests); divisions execute in their "
                        + "own mission style and may decline or deviate when their local situation demands it."),
        TARGET_COORDINATION(
                "D. \"Target Coordination Only\"",
                "Target Coordination Only",
                "The BG only allocates enemy targets and synchronizes strike timing; it never moves, re-tasks or "
                        + "positions divisions.");

        public final String dropdownLabel;
        public final String shortName;
        public final String summary;

        BattlegroupAuthority(String dropdownLabel, String shortName, String summary) {
            this.dropdownLabel = dropdownLabel;
            this.shortName = shortName;
            this.summary = summary;
        }

        /** Whether the BG may position divisions (anchors, flank sides) rather than only pick their targets. */
        public boolean allowsPositioning() {
            return this != TARGET_COORDINATION;
        }

        /** Whether the BG may re-task divisions onto relief, reserve-commitment and pursuit tasks. */
        public boolean allowsRetasking() {
            return this != TARGET_COORDINATION;
        }

        /** Whether BG taskings are binding (true) or requests a division may decline (false). */
        public boolean isBinding() {
            return this != MISSION_TACTICS;
        }

        public static BattlegroupAuthority fromId(String id, BattlegroupAuthority fallback) {
            if (id == null) return fallback;
            try {
                return BattlegroupAuthority.valueOf(id);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Battle-wide orchestration plays the Battlegroup Commander may run (each toggled in the Fleet Doctrine window).
     */
    public enum OrchestrationPlay {
        ANCHOR_AND_FIX(
                "Anchor & Fix",
                "Main Effort fixes the primary threat; supporting divisions get their own enemy divisions instead of piling onto the nearest."),
        PINCER_FLANKS(
                "Pincer Flanks",
                "Flanking divisions are given opposite flank sides so they envelop the same target from left and right."),
        TIMED_STRIKES(
                "Timed Strikes",
                "Missile and strike-craft divisions stage first, then commit together at a shared strike time on one focus target."),
        RESERVE_AND_EXPLOIT(
                "Reserve & Exploit",
                "One relief division per crisis, reserve held until needed, and fast divisions pursue broken enemies on a leash.");

        public final String displayName;
        public final String summary;

        OrchestrationPlay(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }
    }

    /**
     * Campaign-saved Battlegroup & Fleet Doctrine settings defining:
     *  1. Battlegroup Settings:
     *     - Opening Mission duration (30s - 60s, default 45s)
     *     - Per-division-type Intra-division spacing, Inter-division spacing, Line Standoff distance,
     *       Opening Mission, Main Mission, and cohesion leash.
     *  2. Minimum and maximum hulls per division for each of the 7 hull categories (capped at 10).
     */
    public static class FleetDoctrineConfig {
        private final Map<DoctrineHullCategory, Integer> minByCat = new LinkedHashMap<>();
        private final Map<DoctrineHullCategory, Integer> maxByCat = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, Integer> spacingByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, Integer> interSpacingByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, Integer> standoffByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, Float> leashByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, DivisionMissionType> openingMissionByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, DivisionMissionType> mainMissionByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, PreferredSpace> preferredSpaceByType = new LinkedHashMap<>();
        private final Set<OrchestrationPlay> enabledPlays = EnumSet.allOf(OrchestrationPlay.class);
        private BattlegroupAuthority bgAuthority = BattlegroupAuthority.HYBRID;
        private int openingDurationSeconds = 45;

        public FleetDoctrineConfig() {
            for (DoctrineHullCategory cat : DoctrineHullCategory.values()) {
                minByCat.put(cat, cat.defaultMin);
                maxByCat.put(cat, cat.defaultMax);
            }
            for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
                spacingByType.put(dt, dt.defaultIdealSpacingUnits);
                interSpacingByType.put(dt, dt.defaultInterSpacingUnits);
                standoffByType.put(dt, dt.defaultLineStandoffUnits);
                leashByType.put(dt, dt.defaultLeashMult);
                openingMissionByType.put(dt, dt.defaultOpeningMission);
                mainMissionByType.put(dt, dt.defaultMainMission);
                preferredSpaceByType.put(dt, dt.defaultPreferredSpace);
            }
        }

        public BattlegroupAuthority getBgAuthority() {
            return bgAuthority != null ? bgAuthority : BattlegroupAuthority.HYBRID;
        }

        public void setBgAuthority(BattlegroupAuthority authority) {
            if (authority != null) {
                this.bgAuthority = authority;
            }
        }

        public boolean isPlayEnabled(OrchestrationPlay play) {
            return play != null && enabledPlays.contains(play);
        }

        public void setPlayEnabled(OrchestrationPlay play, boolean enabled) {
            if (play == null) return;
            if (enabled) {
                enabledPlays.add(play);
            } else {
                enabledPlays.remove(play);
            }
        }

        public Set<OrchestrationPlay> getEnabledPlays() {
            return enabledPlays.isEmpty() ? EnumSet.noneOf(OrchestrationPlay.class) : EnumSet.copyOf(enabledPlays);
        }

        public PreferredSpace getPreferredSpace(DoctrineDivisionType dt) {
            if (dt == null) return PreferredSpace.MIDDLE;
            PreferredSpace ps = preferredSpaceByType.get(dt);
            return ps != null ? ps : dt.defaultPreferredSpace;
        }

        public void setPreferredSpace(DoctrineDivisionType dt, PreferredSpace ps) {
            if (dt == null || ps == null) return;
            preferredSpaceByType.put(dt, ps);
        }

        public int getOpeningDurationSeconds() {
            return Math.max(MIN_OPENING_DURATION_SEC, Math.min(MAX_OPENING_DURATION_SEC, openingDurationSeconds));
        }

        public void setOpeningDurationSeconds(int seconds) {
            this.openingDurationSeconds = Math.max(MIN_OPENING_DURATION_SEC, Math.min(MAX_OPENING_DURATION_SEC, seconds));
        }

        public int getIdealSpacing(DoctrineDivisionType dt) {
            if (dt == null) return 450;
            Integer v = spacingByType.get(dt);
            int raw = v != null ? v : dt.defaultIdealSpacingUnits;
            return Math.max(MIN_INTRA_SPACING_UNITS, Math.min(MAX_INTRA_SPACING_UNITS, raw));
        }

        public void setIdealSpacing(DoctrineDivisionType dt, int units) {
            if (dt == null) return;
            int clamped = Math.max(MIN_INTRA_SPACING_UNITS, Math.min(MAX_INTRA_SPACING_UNITS, units));
            spacingByType.put(dt, clamped);
        }

        public int getInterSpacing(DoctrineDivisionType dt) {
            if (dt == null) return 950;
            Integer v = interSpacingByType.get(dt);
            int raw = v != null ? v : dt.defaultInterSpacingUnits;
            return Math.max(MIN_INTER_SPACING_UNITS, Math.min(MAX_INTER_SPACING_UNITS, raw));
        }

        public void setInterSpacing(DoctrineDivisionType dt, int units) {
            if (dt == null) return;
            int clamped = Math.max(MIN_INTER_SPACING_UNITS, Math.min(MAX_INTER_SPACING_UNITS, units));
            interSpacingByType.put(dt, clamped);
        }

        public int getLineStandoff(DoctrineDivisionType dt) {
            if (dt == null) return 850;
            Integer v = standoffByType.get(dt);
            int raw = v != null ? v : dt.defaultLineStandoffUnits;
            return Math.max(MIN_STANDOFF_UNITS, Math.min(MAX_STANDOFF_UNITS, raw));
        }

        public void setLineStandoff(DoctrineDivisionType dt, int units) {
            if (dt == null) return;
            int clamped = Math.max(MIN_STANDOFF_UNITS, Math.min(MAX_STANDOFF_UNITS, units));
            standoffByType.put(dt, clamped);
        }

        public float getLeashMult(DoctrineDivisionType dt) {
            if (dt == null) return 1.0f;
            Float v = leashByType.get(dt);
            float raw = v != null ? v : dt.defaultLeashMult;
            return Math.max(0.65f, Math.min(1.85f, raw));
        }

        public void setLeashMult(DoctrineDivisionType dt, float mult) {
            if (dt == null) return;
            float clamped = Math.max(0.65f, Math.min(1.85f, mult));
            leashByType.put(dt, clamped);
        }

        public DivisionMissionType getOpeningMission(DoctrineDivisionType dt) {
            if (dt == null) return DivisionMissionType.RECONNAISSANCE;
            DivisionMissionType m = openingMissionByType.get(dt);
            return m != null ? m : dt.defaultOpeningMission;
        }

        public void setOpeningMission(DoctrineDivisionType dt, DivisionMissionType mission) {
            if (dt == null || mission == null) return;
            openingMissionByType.put(dt, mission);
        }

        public DivisionMissionType getMainMission(DoctrineDivisionType dt) {
            if (dt == null) return DivisionMissionType.DOMINATE_SPACE_INTO_BRAWL;
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
                cfg.setBgAuthority(BattlegroupAuthority.fromId((String) authObj, BattlegroupAuthority.HYBRID));
            }
            for (OrchestrationPlay play : OrchestrationPlay.values()) {
                Object playObj = map.get("bg_play_" + play.name());
                if (playObj instanceof Boolean) {
                    cfg.setPlayEnabled(play, (Boolean) playObj);
                }
            }
            for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
                Object spObj = map.get("spacing_" + dt.name());
                Object ispObj = map.get("inter_spacing_" + dt.name());
                Object stObj = map.get("standoff_" + dt.name());
                Object lsObj = map.get("leash_" + dt.name());
                Object opMisObj = map.get("opening_mission_" + dt.name());
                Object mnMisObj = map.get("main_mission_" + dt.name());
                Object psObj = map.get("pref_space_" + dt.name());
                if (spObj instanceof Number) {
                    cfg.setIdealSpacing(dt, ((Number) spObj).intValue());
                }
                if (ispObj instanceof Number) {
                    cfg.setInterSpacing(dt, ((Number) ispObj).intValue());
                }
                if (stObj instanceof Number) {
                    cfg.setLineStandoff(dt, ((Number) stObj).intValue());
                }
                if (lsObj instanceof Number) {
                    cfg.setLeashMult(dt, ((Number) lsObj).floatValue());
                }
                if (opMisObj instanceof String) {
                    cfg.setOpeningMission(dt, DivisionMissionType.fromId((String) opMisObj, dt.defaultOpeningMission));
                }
                if (mnMisObj instanceof String) {
                    cfg.setMainMission(dt, DivisionMissionType.fromId((String) mnMisObj, dt.defaultMainMission));
                }
                if (psObj instanceof String) {
                    cfg.setPreferredSpace(dt, PreferredSpace.fromId((String) psObj, dt.defaultPreferredSpace));
                }
            }
        }
        return cfg;
    }

    public static void saveFleetDoctrine(FleetDoctrineConfig cfg) {
        SectorAPI sector = Global.getSector();
        if (sector == null || cfg == null) return;
        Map<String, Object> serialized = new HashMap<>();
        serialized.put("opening_duration_sec", cfg.getOpeningDurationSeconds());
        serialized.put("bg_authority", cfg.getBgAuthority().name());
        for (OrchestrationPlay play : OrchestrationPlay.values()) {
            serialized.put("bg_play_" + play.name(), cfg.isPlayEnabled(play));
        }
        for (DoctrineHullCategory cat : DoctrineHullCategory.values()) {
            serialized.put("min_" + cat.name(), cfg.getMin(cat));
            serialized.put("max_" + cat.name(), cfg.getMax(cat));
        }
        for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
            serialized.put("spacing_" + dt.name(), cfg.getIdealSpacing(dt));
            serialized.put("inter_spacing_" + dt.name(), cfg.getInterSpacing(dt));
            serialized.put("standoff_" + dt.name(), cfg.getLineStandoff(dt));
            serialized.put("leash_" + dt.name(), cfg.getLeashMult(dt));
            serialized.put("opening_mission_" + dt.name(), cfg.getOpeningMission(dt).name());
            serialized.put("main_mission_" + dt.name(), cfg.getMainMission(dt).name());
            serialized.put("pref_space_" + dt.name(), cfg.getPreferredSpace(dt).name());
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

    public static void updateDivisionTypeSpacing(DoctrineDivisionType dt, int newSpacingUnits) {
        if (dt == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setIdealSpacing(dt, newSpacingUnits);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeInterSpacing(DoctrineDivisionType dt, int newInterSpacingUnits) {
        if (dt == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setInterSpacing(dt, newInterSpacingUnits);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeStandoff(DoctrineDivisionType dt, int newStandoffUnits) {
        if (dt == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setLineStandoff(dt, newStandoffUnits);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeLeash(DoctrineDivisionType dt, float newLeashMult) {
        if (dt == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setLeashMult(dt, newLeashMult);
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
        if (mission.autoPreferredSpace != null) {
            cfg.setPreferredSpace(dt, mission.autoPreferredSpace);
        }
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypePreferredSpace(DoctrineDivisionType dt, PreferredSpace ps) {
        if (dt == null || ps == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setPreferredSpace(dt, ps);
        saveFleetDoctrine(cfg);
    }

    public static void updateBattlegroupAuthority(BattlegroupAuthority authority) {
        if (authority == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setBgAuthority(authority);
        saveFleetDoctrine(cfg);
    }

    public static void updateOrchestrationPlay(OrchestrationPlay play, boolean enabled) {
        if (play == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setPlayEnabled(play, enabled);
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
        FLANK_STRIKE("Flank & Missile Strike", "Swings wide around enemy flanks and looks for missile strike windows."),
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

        // Per-Division Settings (null = inherit from Battlegroup Settings for this division's archetype;
        // customLineStandoff is auto-calculated from longest-range firepower on ship add or Auto-Set button, or manually set)
        public Integer customIntraSpacing = null;
        public Integer customInterSpacing = null;
        public Integer customLineStandoff = null;
        public DivisionMissionType customOpeningMission = null;
        public DivisionMissionType customMainMission = null;
        public PreferredSpace customPreferredSpace = null;

        public PermanentDivisionSpec(String id, String name, DivisionArchetype archetype) {
            this.id = id;
            this.name = name;
            this.archetype = archetype != null ? archetype : DivisionArchetype.CRUISER_DIVISION;
        }
    }

    public static int resolveEffectiveIntraSpacing(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customIntraSpacing != null) {
            return Math.max(MIN_INTRA_SPACING_UNITS, Math.min(MAX_INTRA_SPACING_UNITS, spec.customIntraSpacing));
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getIdealSpacing(dt) : dt.defaultIdealSpacingUnits;
    }

    public static int resolveEffectiveInterSpacing(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customInterSpacing != null) {
            return Math.max(MIN_INTER_SPACING_UNITS, Math.min(MAX_INTER_SPACING_UNITS, spec.customInterSpacing));
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getInterSpacing(dt) : dt.defaultInterSpacingUnits;
    }

    public static int resolveEffectiveLineStandoff(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customLineStandoff != null) {
            return Math.max(MIN_STANDOFF_UNITS, Math.min(MAX_STANDOFF_UNITS, spec.customLineStandoff));
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getLineStandoff(dt) : dt.defaultLineStandoffUnits;
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

    public static PreferredSpace resolveEffectivePreferredSpace(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customPreferredSpace != null) {
            return spec.customPreferredSpace;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getPreferredSpace(dt) : dt.defaultPreferredSpace;
    }

    /**
     * Calculates the longest-range non-PD gun firepower across all ships currently assigned to the division
     * (scanning fitted ballistic/energy weapons with ship stat range bonuses and any fitted offensive fighter wings).
     * Missiles are excluded: a single long-range missile rack would otherwise push the whole division's standoff
     * far beyond the range its guns can actually fight at.
     * Returns 0 if the division has no ships or no non-PD weapons.
     */
    public static int computeDivisionLongestWeaponRange(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
        return computeDivisionLongestWeaponRange(spec, fleet, false);
    }

    private static int computeDivisionLongestWeaponRange(PermanentDivisionSpec spec, CampaignFleetAPI fleet, boolean includeMissiles) {
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
                        // Cap carrier wing standoff contribution to MAX_STANDOFF_UNITS (2400u)
                        maxRange = Math.min(MAX_STANDOFF_UNITS, Math.max(maxRange, Math.min(1800f, wingSpec.getRange())));
                    }
                }
            }
        }

        if (maxRange <= 0f) return 0;
        int rounded = Math.round(maxRange / 25f) * 25;
        return Math.max(MIN_STANDOFF_UNITS, Math.min(MAX_STANDOFF_UNITS, rounded));
    }

    public static void recalculateDivisionLineStandoffFromFirepower(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
        if (spec == null) return;
        int longest = computeDivisionLongestWeaponRange(spec, fleet);
        if (longest > 0) {
            spec.customLineStandoff = longest;
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
            if (spec.customLineStandoff == null) continue;
            int oldAuto = computeDivisionLongestWeaponRange(spec, fleet, true);
            if (oldAuto > 0 && oldAuto == spec.customLineStandoff) {
                int gunOnly = computeDivisionLongestWeaponRange(spec, fleet, false);
                if (gunOnly > 0 && gunOnly != oldAuto) {
                    spec.customLineStandoff = gunOnly;
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

    public static void autoSetDivisionLineStandoff(String divisionId) {
        SectorAPI sector = Global.getSector();
        if (sector == null || divisionId == null) return;
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();
        for (PermanentDivisionSpec spec : specs) {
            if (divisionId.equals(spec.id)) {
                recalculateDivisionLineStandoffFromFirepower(spec, fleet);
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
                    spec.customIntraSpacing = (intraSpacing != null)
                            ? Math.max(MIN_INTRA_SPACING_UNITS, Math.min(MAX_INTRA_SPACING_UNITS, intraSpacing))
                            : null;
                }
                if (updateInter) {
                    spec.customInterSpacing = (interSpacing != null)
                            ? Math.max(MIN_INTER_SPACING_UNITS, Math.min(MAX_INTER_SPACING_UNITS, interSpacing))
                            : null;
                }
                if (updateStandoff) {
                    spec.customLineStandoff = (lineStandoff != null)
                            ? Math.max(MIN_STANDOFF_UNITS, Math.min(MAX_STANDOFF_UNITS, lineStandoff))
                            : null;
                }
                if (updateOpeningMission) {
                    spec.customOpeningMission = openingMission;
                    if (openingMission != null && openingMission.autoPreferredSpace != null) {
                        spec.customPreferredSpace = openingMission.autoPreferredSpace;
                    }
                }
                if (updateMainMission) {
                    spec.customMainMission = mainMission;
                    if (mainMission != null && mainMission.autoPreferredSpace != null) {
                        spec.customPreferredSpace = mainMission.autoPreferredSpace;
                    }
                }
                break;
            }
        }
        saveSpecsToPersistentData(pdata, specs);
    }

    public static void updateIndividualDivisionPreferredSpace(String divisionId, PreferredSpace preferredSpace) {
        SectorAPI sector = Global.getSector();
        if (sector == null || divisionId == null) return;
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();
        for (PermanentDivisionSpec spec : specs) {
            if (divisionId.equals(spec.id)) {
                spec.customPreferredSpace = preferredSpace;
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

        for (PermanentDivisionSpec s : specs) {
            if (s.customLineStandoff == null) {
                recalculateDivisionLineStandoffFromFirepower(s, fleet);
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
            recalculateDivisionLineStandoffFromFirepower(created, sector.getPlayerFleet());
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
                recalculateDivisionLineStandoffFromFirepower(spec, fleet);
            }
            if (targetDivisionId.equals(spec.id)) {
                target = spec;
            }
        }
        if (target != null && !target.memberIds.contains(memberId) && target.memberIds.size() < MAX_DIVISION_SIZE) {
            target.memberIds.add(memberId);
            ensureValidFlagship(target, fleet);
            recalculateDivisionLineStandoffFromFirepower(target, fleet);
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
                recalculateDivisionLineStandoffFromFirepower(spec, fleet);
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
                applyDoctrineModifiersToCombatDivision(existingCombatDiv, spec);
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
            applyDoctrineModifiersToCombatDivision(combatDiv, spec);
            bg.divisions.add(combatDiv);
            newlyCreatedOut.add(combatDiv);
        }

        return leftoverUnassigned;
    }

    private static void applyDoctrineModifiersToCombatDivision(DivisionState div, PermanentDivisionSpec spec) {
        if (div == null || spec == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        div.idealIntraSpacing = resolveEffectiveIntraSpacing(spec, cfg);
        div.idealInterSpacing = resolveEffectiveInterSpacing(spec, cfg);
        div.lineStandoffDistance = resolveEffectiveLineStandoff(spec, cfg);
        div.openingPhaseDurationSeconds = cfg.getOpeningDurationSeconds();
        div.openingMission = resolveEffectiveOpeningMission(spec, cfg);
        div.mainMission = resolveEffectiveMainMission(spec, cfg);
        div.preferredSpace = resolveEffectivePreferredSpace(spec, cfg);
        if (div.openingMission == DivisionMissionType.BATTLEGROUP_RESERVE
                || div.mainMission == DivisionMissionType.BATTLEGROUP_RESERVE) {
            div.isDesignatedReserve = true;
        }

        int count = Math.max(1, div.profiles.size());
        float spacingBasedRadius = div.idealIntraSpacing * (0.95f + 0.38f * Math.min(6, Math.max(1, count - 1)));
        div.desiredCohesionRadius = (Math.max(680f, spacingBasedRadius) * div.cohesionLeashMult)
                / Math.max(0.75f, div.commander.cohesionStrictness);

        DoctrineOption doctrine = spec.doctrine;
        if (doctrine == null) return;
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

        List<FleetMemberAPI> fleetCarriers = new ArrayList<>();
        List<FleetMemberAPI> lightCarriers = new ArrayList<>();
        List<FleetMemberAPI> escortCarriers = new ArrayList<>();
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
                case FLEET_CARRIER_DIVISION:
                    fleetCarriers.add(m);
                    break;
                case LIGHT_CARRIER_DIVISION:
                    lightCarriers.add(m);
                    break;
                case ESCORT_CARRIER_DIVISION:
                case CARRIER_AND_ESCORT:
                    escortCarriers.add(m);
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
            if (fleet != null && spec.archetype == DivisionArchetype.CARRIER_AND_ESCORT) {
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
            float score = m.getDeploymentPointsCost();
            if (m.getCaptain() != null && !m.getCaptain().isDefault()) {
                score += 25f + m.getCaptain().getStats().getLevel() * 5f;
            }
            if (preferCarrierFlagship && (m.isCarrier() || m.getNumFlightDecks() > 0)) {
                score += 500f;
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

            Object cIntra = map.get("customIntraSpacing");
            Object cInter = map.get("customInterSpacing");
            Object cStandoff = map.get("customLineStandoff");
            Object cOpenMis = map.get("customOpeningMission");
            Object cMainMis = map.get("customMainMission");
            Object cPrefSpace = map.get("customPreferredSpace");
            if (cIntra instanceof Number) spec.customIntraSpacing = ((Number) cIntra).intValue();
            if (cInter instanceof Number) spec.customInterSpacing = ((Number) cInter).intValue();
            if (cStandoff instanceof Number) spec.customLineStandoff = ((Number) cStandoff).intValue();
            if (cOpenMis instanceof String) spec.customOpeningMission = DivisionMissionType.fromId((String) cOpenMis, null);
            if (cMainMis instanceof String) spec.customMainMission = DivisionMissionType.fromId((String) cMainMis, null);
            if (cPrefSpace instanceof String) spec.customPreferredSpace = PreferredSpace.fromId((String) cPrefSpace, null);

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
            if (spec.customIntraSpacing != null) map.put("customIntraSpacing", spec.customIntraSpacing);
            if (spec.customInterSpacing != null) map.put("customInterSpacing", spec.customInterSpacing);
            if (spec.customLineStandoff != null) map.put("customLineStandoff", spec.customLineStandoff);
            if (spec.customOpeningMission != null) map.put("customOpeningMission", spec.customOpeningMission.name());
            if (spec.customMainMission != null) map.put("customMainMission", spec.customMainMission.name());
            if (spec.customPreferredSpace != null) map.put("customPreferredSpace", spec.customPreferredSpace.name());
            serialized.add(map);
        }
        pdata.put(PERSISTENT_DIVISIONS_KEY, serialized);
    }
}
