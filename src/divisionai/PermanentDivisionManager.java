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
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
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

    public static int getEffectiveMaxStationSpacingUnits() {
        float sMult = CombatPhysicsProfile.getGlobalStationSpacingMult();
        if (sMult <= 1.05f) return MAX_STATION_SPACING_UNITS;
        int scaled = Math.round((MAX_STATION_SPACING_UNITS * sMult) / 50f) * 50;
        return Math.max(MAX_STATION_SPACING_UNITS, Math.min(4800, scaled));
    }

    public static int getEffectiveMaxDivisionIntervalUnits() {
        float iMult = CombatPhysicsProfile.getGlobalDivisionIntervalMult();
        if (iMult <= 1.05f) return MAX_DIVISION_INTERVAL_UNITS;
        int scaled = Math.round((MAX_DIVISION_INTERVAL_UNITS * iMult) / 100f) * 100;
        return Math.max(MAX_DIVISION_INTERVAL_UNITS, Math.min(9600, scaled));
    }

    public static int getEffectiveMaxStandoffRangeUnits() {
        float scale = Math.max(
                CombatPhysicsProfile.getGlobalWeaponRangeScale(),
                Math.max(CombatPhysicsProfile.getGlobalMissileRangeScale(),
                        CombatPhysicsProfile.getGlobalFighterRangeScale() * 0.85f));
        if (scale <= 1.15f) return MAX_STANDOFF_RANGE_UNITS;
        int scaled = Math.round((MAX_STANDOFF_RANGE_UNITS * scale) / 100f) * 100;
        return Math.max(MAX_STANDOFF_RANGE_UNITS, Math.min(16000, scaled));
    }

    public static int scaleVanillaStationSpacing(int vanillaUnits) {
        float mult = CombatPhysicsProfile.getGlobalStationSpacingMult();
        if (mult <= 1.01f) return vanillaUnits;
        int scaled = Math.round((vanillaUnits * mult) / 25f) * 25;
        return Math.max(MIN_STATION_SPACING_UNITS, Math.min(getEffectiveMaxStationSpacingUnits(), scaled));
    }

    public static int scaleVanillaDivisionInterval(int vanillaUnits) {
        float mult = CombatPhysicsProfile.getGlobalDivisionIntervalMult();
        if (mult <= 1.01f) return vanillaUnits;
        int scaled = Math.round((vanillaUnits * mult) / 50f) * 50;
        return Math.max(MIN_DIVISION_INTERVAL_UNITS, Math.min(getEffectiveMaxDivisionIntervalUnits(), scaled));
    }

    public static int scaleVanillaStandoffRange(DoctrineDivisionType dt, int vanillaUnits) {
        boolean isCarrier = dt != null && dt.isCarrierType();
        boolean isMissile = dt != null && dt.primaryArchetype != null && dt.primaryArchetype.isMissileArchetype();
        float mult = CombatPhysicsProfile.getGlobalStandoffMult(isCarrier, isMissile);
        if (mult <= 1.01f) return vanillaUnits;
        int scaled = Math.round((vanillaUnits * mult) / 50f) * 50;
        return Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(getEffectiveMaxStandoffRangeUnits(), scaled));
    }

    public static int getEffectiveDefaultStationSpacingUnits(DoctrineDivisionType dt) {
        if (dt == null) return scaleVanillaStationSpacing(450);
        return scaleVanillaStationSpacing(dt.defaultStationSpacingUnits);
    }

    public static int getEffectiveDefaultDivisionIntervalUnits(DoctrineDivisionType dt) {
        if (dt == null) return scaleVanillaDivisionInterval(950);
        return scaleVanillaDivisionInterval(dt.defaultDivisionIntervalUnits);
    }

    public static int getEffectiveDefaultStandoffRangeUnits(DoctrineDivisionType dt) {
        if (dt == null) return scaleVanillaStandoffRange(null, 850);
        return scaleVanillaStandoffRange(dt, dt.defaultStandoffRangeUnits);
    }

    /**
     * Scales an uncustomized doctrinal standoff value proportionally when global or live combat
     * weapon/fighter envelopes exceed vanilla ranges, while preserving exact vanilla values when
     * {@code scale == 1.0f} and preserving faction/doctrine posture ratios (e.g. close brawl vs standoff).
     */
    public static float adaptDoctrinalStandoffToLiveEnvelope(
            DivisionState div,
            DoctrineDivisionType dt,
            float configuredStandoff,
            boolean hasExplicitDivisionCustomStandoff) {
        if (div == null || hasExplicitDivisionCustomStandoff) {
            return configuredStandoff;
        }
        float wScale = CombatPhysicsProfile.getWeaponRangeScale(null);
        float fScale = CombatPhysicsProfile.getFighterRangeScale(null);
        boolean carrierArch = div.archetype != null && div.archetype.isCarrierArchetype();
        boolean missileArch = div.archetype != null && div.archetype.isMissileArchetype();
        boolean scaledEnv = (wScale > 1.25f)
                || (carrierArch && fScale > 1.25f)
                || (div.avgEngageRange > 1650f);
        if (!scaledEnv) {
            return configuredStandoff; // Exact 1:1 vanilla identity
        }
        float vanillaDefault = (dt != null && dt.defaultStandoffRangeUnits > 0)
                ? dt.defaultStandoffRangeUnits
                : 950f;
        float effDefault = getEffectiveDefaultStandoffRangeUnits(dt);
        // If configuredStandoff was already scaled globally (e.g. Better Combat FleetDoctrineConfig), measure postureMult
        // relative to effDefault so we never double-multiply; if configuredStandoff is still in 1.0x vanilla range
        // (e.g. standalone SimArena battle without global SettingsAPI specs), measure relative to vanillaDefault.
        float refDefault = (CombatPhysicsProfile.isGlobalScaledEnvironment() && configuredStandoff > vanillaDefault * 1.45f)
                ? Math.max(300f, effDefault)
                : vanillaDefault;
        float postureMult = Math.max(0.55f, Math.min(1.45f, configuredStandoff / refDefault));
        float liveRangeScaleCap = (div.avgEngageRange > 1650f) ? (div.avgEngageRange * 1.15f) : 0f;
        float maxCap = Math.min(16000f, Math.max(
                Math.max(getEffectiveMaxStandoffRangeUnits(), liveRangeScaleCap),
                Math.round(MAX_STANDOFF_RANGE_UNITS * Math.max(wScale, carrierArch ? fScale * 0.85f : 1f))));
        if (carrierArch) {
            float liveFighterCap = Math.max(
                    effDefault * 1.15f,
                    Math.max(div.avgEngageRange * 0.92f, vanillaDefault * Math.max(wScale * 1.10f, fScale * 0.82f)));
            float effCarrierRef = Math.min(liveFighterCap, Math.max(
                    Math.max(div.avgEngageRange * 0.85f, CombatPhysicsProfile.isGlobalScaledEnvironment() ? effDefault : 0f),
                    vanillaDefault * Math.max(wScale * 1.05f, fScale * 0.78f)));
            return Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(Math.min(maxCap, liveFighterCap), effCarrierRef * Math.min(1.15f, postureMult)));
        }
        if (missileArch) {
            float mScale = CombatPhysicsProfile.getMissileRangeScale(null);
            float missileTarget = vanillaDefault * Math.max(wScale, mScale) * postureMult;
            if (div.avgEngageRange > 350f) {
                missileTarget = Math.max(missileTarget, div.avgEngageRange * 0.92f * postureMult);
            }
            // Never let a corrupted/double-scaled standoff park a Missile division thousands of units outside its own
            // live missile envelope (e.g. 10,200u on 3,600-5,400u Missile Frigates).
            float liveMissileCeiling = (div.avgEngageRange > 350f)
                    ? Math.max(effDefault * 1.25f, div.avgEngageRange * 0.96f)
                    : Math.max(effDefault * 1.35f, vanillaDefault * Math.max(wScale, mScale) * 1.35f);
            float boundedConfig = Math.min(configuredStandoff, liveMissileCeiling);
            float boundedTarget = Math.min(missileTarget, liveMissileCeiling);
            return Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(maxCap, Math.max(boundedConfig, boundedTarget)));
        }
        float gunRefRange = (div.medianMainBatteryRange > 350f) ? div.medianMainBatteryRange : div.avgEngageRange;
        if (gunRefRange > 350f) {
            // Preserve hull-role standoff fraction relative to live median main-battery reach (never exceeding 94% of own gun range!)
            float roleFrac = (div.archetype != null && div.archetype.isLightSkirmisherArchetype())
                    ? 0.84f
                    : 0.88f;
            float liveTarget = Math.min(gunRefRange * 0.94f, gunRefRange * roleFrac * postureMult);
            float floorTarget = Math.min(gunRefRange * 0.94f, configuredStandoff);
            return Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(maxCap, Math.max(floorTarget, liveTarget)));
        }
        float fallbackScaled = (CombatPhysicsProfile.isGlobalScaledEnvironment() && configuredStandoff > vanillaDefault * 1.45f)
                ? configuredStandoff
                : (configuredStandoff * wScale);
        return Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(maxCap, fallbackScaled));
    }

    /**
     * Resolves a gun division's mission-coupled combat standoff range at battle timestamp {@code now}:
     * <ul>
     *   <li>{@link DivisionMissionType#CLOSE_ACTION} under scaled physics anchors to the shortest-ranged member's
     *       main battery ({@code 0.72x minMainBatteryRange}) so every capital/cruiser in a mixed gun division brings
     *       decisive broadsides to bear instead of hanging back at the longest ship's range.</li>
     *   <li>{@link DivisionMissionType#STANDOFF} anchors to the division median main battery ({@code 0.88x..0.92x}).</li>
     *   <li>Explicit custom player standoffs ({@code div.hasExplicitCustomStandoff}) and 1.0x vanilla defaults are preserved.</li>
     * </ul>
     */
    public static float resolveEffectiveCombatGunStandoff(DivisionState div, float now) {
        if (div == null) return 800f;
        float base = Math.max(MIN_STANDOFF_RANGE_UNITS, div.standoffRange);
        if (div.hasExplicitCustomStandoff
                || (div.archetype != null && (div.archetype.isCarrierArchetype() || div.archetype.isMissileArchetype()))) {
            return base;
        }
        float wScale = CombatPhysicsProfile.getWeaponRangeScale(null);
        DivisionMissionType activeMission = DivisionCommanderAI.resolveActiveMission(div, now);
        if (wScale <= 1.25f && div.avgEngageRange <= 1650f) {
            if (activeMission == DivisionMissionType.CLOSE_ACTION) {
                float shortBat = (div.minMainBatteryRange > 350f) ? div.minMainBatteryRange : div.avgEngageRange;
                if (shortBat > 350f && base > shortBat * 0.88f) {
                    return Math.max(650f, Math.min(base, shortBat * 0.85f));
                }
            }
            return base;
        }
        if (activeMission == DivisionMissionType.CLOSE_ACTION) {
            float shortBat = (div.minMainBatteryRange > 350f) ? div.minMainBatteryRange : div.avgEngageRange;
            if (shortBat > 350f) {
                return Math.max(520f, Math.min(base, shortBat * 0.72f));
            }
        } else if (activeMission == DivisionMissionType.STANDOFF_ENGAGEMENT) {
            float medBat = (div.medianMainBatteryRange > 350f) ? div.medianMainBatteryRange : div.avgEngageRange;
            if (medBat > 350f) {
                return Math.max(540f, Math.min(medBat * 0.92f, Math.max(base, medBat * 0.88f)));
            }
        }
        return base;
    }

    /**
     * Preferred station in the line of battle for a division (Van, Center, Rear).
     * Automatically selected by certain mission types, or manually set by the player.
     */
    public enum LineStation {
        VAN("Van", "Leads ahead of the main body."),
        CENTER("Center", "Holds the center of the line."),
        REAR("Rear", "Stands off astern or in reserve.");

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
     * Naval Formations practiced by a division:
     *  1. Set per division type in Fleet Doctrine.
     *  2. Inherited into the division auto-assigner and customizable per division in Fleet Organization.
     *  3. Inherited into battle, where it changes or breaks up at the discretion of the Division Commander
     *     or an OTC with enough authority.
     */
    public enum DivisionFormation {
        LINE_ABREAST(
                "Line Abreast",
                "Abreast",
                "icon_formation_line_abreast",
                "graphics/icons/formations/icon_formation_line_abreast.png",
                "Broad front for maximum forward gun arcs."),
        LINE_AHEAD(
                "Line Ahead",
                "Ahead",
                "icon_formation_line_ahead",
                "graphics/icons/formations/icon_formation_line_ahead.png",
                "Single-file column following the division leader."),
        FORWARD_WEDGE(
                "Forward Wedge",
                "F. Wedge",
                "icon_formation_forward_wedge",
                "graphics/icons/formations/icon_formation_forward_wedge.png",
                "Division leader at apex; wingmen stepped back."),
        REVERSE_WEDGE(
                "Reverse Wedge",
                "R. Wedge",
                "icon_formation_reverse_wedge",
                "graphics/icons/formations/icon_formation_reverse_wedge.png",
                "Wingmen forward on shoulders; division leader at rear."),
        ECHELON_LEFT(
                "Echelon Left",
                "Ech. L",
                "icon_formation_echelon_left",
                "graphics/icons/formations/icon_formation_echelon_left.png",
                "Stepped back to port for oblique fire."),
        ECHELON_RIGHT(
                "Echelon Right",
                "Ech. R",
                "icon_formation_echelon_right",
                "graphics/icons/formations/icon_formation_echelon_right.png",
                "Stepped back to starboard for oblique fire."),
        CIRCLE(
                "Circle",
                "Circle",
                "icon_formation_circle",
                "graphics/icons/formations/icon_formation_circle.png",
                "All-around defensive ring facing outward."),
        PROTECTIVE_CIRCLE(
                "Protective Circle",
                "P. Circle",
                "icon_formation_protective_circle",
                "graphics/icons/formations/icon_formation_protective_circle.png",
                "Small ships form a defensive ring around larger ships."),
        FORWARD_SEMICIRCLE(
                "Forward Semicircle",
                "F. Arc",
                "icon_formation_forward_semicircle",
                "graphics/icons/formations/icon_formation_forward_semicircle.png",
                "Convex arc bowing toward the enemy."),
        REVERSE_SEMICIRCLE(
                "Reverse Semicircle",
                "R. Arc",
                "icon_formation_reverse_semicircle",
                "graphics/icons/formations/icon_formation_reverse_semicircle.png",
                "Concave crescent for converging inward fire."),
        NONE(
                "No Formation",
                "None",
                "icon_formation_none",
                "graphics/icons/formations/icon_formation_none.png",
                "Free-maneuver swarm without fixed geometry.");

        public final String displayName;
        public final String shortName;
        public final String settingsIconKey;
        public final String spritePath;
        public final String goldSettingsIconKey;
        public final String goldSpritePath;
        public final String summary;

        DivisionFormation(
                String displayName,
                String shortName,
                String settingsIconKey,
                String spritePath,
                String summary) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.settingsIconKey = settingsIconKey;
            this.spritePath = spritePath;
            this.goldSettingsIconKey = settingsIconKey + "_gold";
            this.goldSpritePath = spritePath.endsWith(".png")
                    ? (spritePath.substring(0, spritePath.length() - 4) + "_gold.png")
                    : (spritePath + "_gold");
            this.summary = summary;
        }

        public String getSettingsIconKey(boolean isFlagshipDivision) {
            return isFlagshipDivision ? goldSettingsIconKey : settingsIconKey;
        }

        public String getSpritePath(boolean isFlagshipDivision) {
            return isFlagshipDivision ? goldSpritePath : spritePath;
        }

        public static DivisionFormation fromId(String id, DivisionFormation fallback) {
            if (id == null) return fallback;
            try {
                return DivisionFormation.valueOf(id.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Doctrinal Division Movement Stickiness (3-tier hierarchy):
     *  1. Set per division type in Fleet Doctrine (and faction presets).
     *  2. Inherited into the division auto-assigner and customizable per division in Configure Division.
     *  3. Inherited into battle, governing how tightly ships stick to division movement vs. breaking into
     *     individual ship combat movement when engaging enemy divisions.
     */
    public enum DivisionMovementStickiness {
        STRICT(
                "Strict",
                "Strict",
                0.80f,
                "Holds formation tightly until inside gun range."),
        BALANCED(
                "Balanced",
                "Balanced",
                1.00f,
                "Forms up out of contact; fights individually in range."),
        LOOSE(
                "Loose",
                "Loose",
                1.20f,
                "Wide skirmish and kiting freedom in combat."),
        FREE(
                "Free",
                "Free",
                1.45f,
                "Individual ship movement; shared division targets.");

        public final String displayName;
        public final String shortName;
        public final float stationKeepingMult;
        public final String summary;

        DivisionMovementStickiness(
                String displayName,
                String shortName,
                float stationKeepingMult,
                String summary) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.stationKeepingMult = stationKeepingMult;
            this.summary = summary;
        }

        public static DivisionMovementStickiness fromStationKeepingMult(float mult) {
            if (mult <= 0.90f) return STRICT;
            if (mult <= 1.09f) return BALANCED;
            if (mult <= 1.32f) return LOOSE;
            return FREE;
        }

        public static DivisionMovementStickiness fromId(String id, DivisionMovementStickiness fallback) {
            if (id == null) return fallback;
            try {
                return DivisionMovementStickiness.valueOf(id.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Naval Engine Order Telegraph governing maximum ship speed & braking envelope:
     *  - AUTO: C2 automatically selects 1/3, 1/2, Full, or Flank by tactical intent and hull stopping inertia.
     *  - ONE_THIRD: Tight battleline dress / station crawl; clamps runaway inertia so heavy capitals stop cleanly.
     *  - ONE_HALF: Controlled combat maneuver, kiting, and standoff hold without overshooting the line.
     *  - FULL: Brisk closing and flank transit while suppressing runaway zero-flux boost.
     *  - FLANK: Uncapped maximum burn and zero-flux speed boost.
     */
    public enum DivisionEngineTelegraph {
        AUTO("Auto", "Auto", "Picks 1/3, 1/2, Full, or Flank automatically to keep ships in formation.", 1.00f, 99f),
        ONE_THIRD("1/3 Ahead", "1/3", "Slow speed for holding the line and stopping heavy ships on station.", 0.34f, 2.90f),
        ONE_HALF("1/2 Ahead", "1/2", "Steady combat speed for firing on the move without drifting out of line.", 0.52f, 4.55f),
        FULL("Full Ahead", "Full", "Fast cruising speed for closing or flanking without zero-flux boost.", 0.78f, 6.50f),
        FLANK("Flank", "Flank", "Full top speed, including zero-flux boost.", 1.00f, 99f);

        public final String displayName;
        public final String shortName;
        public final String summary;
        public final float vanillaSpeedFraction;
        public final float maxBrakingSeconds;

        DivisionEngineTelegraph(
                String displayName,
                String shortName,
                String summary,
                float vanillaSpeedFraction,
                float maxBrakingSeconds) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.summary = summary;
            this.vanillaSpeedFraction = vanillaSpeedFraction;
            this.maxBrakingSeconds = maxBrakingSeconds;
        }

        public static DivisionEngineTelegraph fromId(String id, DivisionEngineTelegraph fallback) {
            if (id == null) return fallback;
            try {
                return DivisionEngineTelegraph.valueOf(id.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Player-friendly quick combat role presets exposed directly on Fleet Organization division cards
     * and at the top of Configure Division. Each preset writes existing PermanentDivisionSpec fields
     * (Line Station, Opening/Main Missions, Cohesion, and Standoff) without introducing separate C2 state.
     */
    public enum DivisionQuickRole {
        DOCTRINE_DEFAULT(
                "Doctrine Default",
                "Default",
                "Uses Fleet Doctrine defaults for this division's ship type."),
        BATTLE_LINE(
                "Battle Line",
                "Battle Line",
                "Holds the main battle line and fights at effective gun range."),
        AGGRESSIVE(
                "Aggressive",
                "Aggressive",
                "Pushes in close to brawl and finish damaged enemy ships."),
        STANDOFF(
                "Standoff",
                "Standoff",
                "Exchanges fire at max gun range and gives ground when pressed."),
        ARTILLERY(
                "Artillery",
                "Artillery",
                "Stays behind the front line and bombards targets from max range."),
        SCOUT(
                "Scout",
                "Scout",
                "Captures control points early and screens the flanks at range."),
        HARASSER(
                "Harasser",
                "Harasser",
                "Sweeps wide around the enemy line to pressure flanks and rear."),
        HUNTER_KILLER(
                "Hunter-Killer",
                "Hunter-Killer",
                "Runs coordinated torpedo and strike passes on vulnerable targets."),
        SCREEN_ESCORT(
                "Screen / Escort",
                "Screen",
                "Screens friendly capitals and carriers against flankers and missiles.");

        public final String displayName;
        public final String shortName;
        public final String summary;

        DivisionQuickRole(String displayName, String shortName, String summary) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.summary = summary;
        }

        public static DivisionQuickRole fromId(String id, DivisionQuickRole fallback) {
            if (id == null) return fallback;
            try {
                return DivisionQuickRole.valueOf(id.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Player-friendly flight-deck posture presets for divisions with fighter bays or carrier archetypes.
     */
    public enum DivisionQuickDeck {
        DOCTRINE_DEFAULT(
                "Doctrine Default",
                "Default",
                "Uses Fleet Doctrine defaults for this division's flight decks."),
        NOT_A_CARRIER(
                "Not a Carrier",
                "Not a Carrier",
                "Fights as a surface warship; any bays fly local defensive CAP."),
        BATTLECARRIER(
                "Battlecarrier",
                "Battlecarrier",
                "Fights in the battle line while wings launch voidstrikes."),
        FLEET_CARRIER(
                "Fleet Carrier",
                "Fleet Carrier",
                "Stays safely behind the line while wings scout and voidstrike."),
        ESCORT_CARRIER(
                "Escort CAP",
                "Escort CAP",
                "Stays behind the line while fighters fly protective CAP cover.");

        public final String displayName;
        public final String shortName;
        public final String summary;

        DivisionQuickDeck(String displayName, String shortName, String summary) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.summary = summary;
        }

        public static DivisionQuickDeck fromId(String id, DivisionQuickDeck fallback) {
            if (id == null) return fallback;
            try {
                return DivisionQuickDeck.valueOf(id.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Scope Item 1: Setpiece Division Attacks executed in locked formation carriage.
     *  - Stage 1 (Dressing at IP): Division forms up tightly into the setpiece's signature formation at an Initial Point.
     *  - Stage 2 (Executing Pass): The entire formation carriage translates along the strike vector in unison.
     *  - Stage 3 (Recovery): Division reforms at egress or returns to tactical stationing.
     */
    public enum DivisionSetpieceAttack {
        AUTO_TACTICAL(
                "Auto (Tactical)",
                "Auto",
                null,
                "Commander picks the setpiece pass to suit the situation."),
        NONE(
                "None (Standard)",
                "Std",
                null,
                "Standard division movement without locked setpiece passes."),
        LANCE_CHARGE(
                "Wedge Lance Charge",
                "Lance",
                DivisionFormation.FORWARD_WEDGE,
                "Dresses into Forward Wedge at IP, then drives a high-speed charge through the enemy weak point."),
        BROADSIDE_ROLL(
                "Line Ahead Broadside Roll",
                "Roll",
                DivisionFormation.LINE_AHEAD,
                "Forms Line Ahead and wheels across the enemy bow ('Crossing the T'), rolling sequential broadsides."),
        WALL_SALVO_PASS(
                "Line Abreast Salvo Pass",
                "Salvo",
                DivisionFormation.LINE_ABREAST,
                "Advances as a locked Line Abreast wall to gun/missile range, unleashes a synchronized volley, and recoils."),
        HORNS_OF_THE_BULL(
                "Semicircle Envelopment",
                "Crescent",
                DivisionFormation.FORWARD_SEMICIRCLE,
                "Sweeps forward in a Forward Semicircle crescent to pocket the target inside a multi-angle crossfire."),
        ECHELON_STRIKE(
                "Echelon Oblique Strike",
                "Oblique",
                DivisionFormation.ECHELON_RIGHT,
                "Approaches obliquely in Echelon on the enemy's quarter to roll up their outer flank ship-by-ship.");

        public final String displayName;
        public final String shortName;
        public final DivisionFormation signatureFormation;
        public final String summary;

        DivisionSetpieceAttack(
                String displayName,
                String shortName,
                DivisionFormation signatureFormation,
                String summary) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.signatureFormation = signatureFormation;
            this.summary = summary;
        }

        public static DivisionSetpieceAttack fromId(String id, DivisionSetpieceAttack fallback) {
            if (id == null) return fallback;
            try {
                return DivisionSetpieceAttack.valueOf(id.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Scope Item 2: OTC-coordinated Multi-Division Setpiece Attacks in Formation.
     * Coordinates 2+ divisions with synchronized Time-on-Target (ToT) and complementary formation passes.
     */
    public enum MultiDivisionSetpiece {
        AUTO_DOCTRINAL(
                "Auto (Tactical)",
                "Auto",
                "Officer in Tactical Command picks a multi-division setpiece to suit the situation, or none."),
        NONE(
                "None (Standard)",
                "Std",
                "Standard Officer in Tactical Command orchestration without multi-division setpieces."),
        HAMMER_AND_ANVIL(
                "Hammer & Anvil",
                "H&A",
                "Anvil division fixes enemy front in Line Abreast while Hammer division drives a Wedge Lance Charge into their flank."),
        CANNAAE_DOUBLE_ENVELOPMENT(
                "Double Envelopment",
                "Double Env.",
                "Center fixes in Reverse Semicircle while Port and Starboard wings pinch both flanks in Echelon and Semicircle."),
        CROSSING_THE_T_COLUMN(
                "Grand Column Crossing the T",
                "Cross-T",
                "Divisions form an echeloned Line Ahead column wheeling across the enemy bow to concentrate broadsides."),
        ECHELONED_WAVE_BREACH(
                "Echeloned Wave Breach",
                "Wave",
                "Successive divisions execute timed Wedge Lance Charges along the same breach axis to crack enemy strongpoints."),
        CONVERGENCE_KILL_BOX(
                "Kill-Box",
                "KillBox",
                "Surrounds an isolated enemy division on multiple vectors with synchronized Salvo Passes and Voidstrikes.");

        public final String displayName;
        public final String shortName;
        public final String summary;

        MultiDivisionSetpiece(String displayName, String shortName, String summary) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.summary = summary;
        }

        public static MultiDivisionSetpiece fromId(String id, MultiDivisionSetpiece fallback) {
            if (id == null) return fallback;
            try {
                return MultiDivisionSetpiece.valueOf(id.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Scope Items 3 & 4: Multi-Division / Fleet-Wide Formations governing how divisions deploy at battle start
     * and station relative to one another across the battlefield.
     */
    public enum FleetWideFormation {
        DOCTRINAL_AUTO(
                "Auto",
                "Auto",
                DivisionFormation.LINE_ABREAST,
                "Officer in Tactical Command selects the fleet formation dynamically based on doctrine and the battlefield."),
        GRAND_LINE_ABREAST(
                "Grand Line Abreast",
                "G. Line",
                DivisionFormation.LINE_ABREAST,
                "Frontline divisions deployed side-by-side in a wide battle line abreast; carriers/reserves centered behind."),
        GRAND_COLUMN_IN_DEPTH(
                "Grand Column in Depth",
                "G. Column",
                DivisionFormation.LINE_AHEAD,
                "Divisions arrayed in sequential depth along the axis of advance for deep breakthrough or succession relief."),
        GRAND_WEDGE_SPEARHEAD(
                "Grand Spearhead Wedge",
                "G. Wedge",
                DivisionFormation.FORWARD_WEDGE,
                "Main Effort division forms the forward apex with wing divisions stepped back on port and starboard quarters."),
        REVERSE_WEDGE(
                "Grand Reverse Wedge",
                "R. Wedge",
                DivisionFormation.REVERSE_WEDGE,
                "Center division holds back at the pocket apex while wing divisions advance on both flanks to form a V-funnel."),
        REFUSED_FLANK_OBLIQUE(
                "Refused Flank (Right)",
                "Refused R",
                DivisionFormation.ECHELON_RIGHT,
                "Concentrates heavy and strike divisions forward on the starboard wing in echelon while the port wing holds standoff."),
        REFUSED_FLANK_LEFT(
                "Refused Flank (Left)",
                "Refused L",
                DivisionFormation.ECHELON_LEFT,
                "Concentrates heavy and strike divisions forward on the port wing in echelon while the starboard wing holds standoff."),
        CRESCENT_HORNS(
                "Grand Crescent",
                "Crescent",
                DivisionFormation.FORWARD_SEMICIRCLE,
                "Center division holds back while port and starboard wing divisions curve forward to envelop both flanks."),
        REVERSE_CRESCENT(
                "Reverse Crescent",
                "R. Crescent",
                DivisionFormation.REVERSE_SEMICIRCLE,
                "Center division bows forward in a convex arc while port and starboard wing divisions curve back to refuse both flanks."),
        CHECKERBOARD_QUINCUNX(
                "Checkerboard (Quincunx)",
                "Quincunx",
                DivisionFormation.REVERSE_WEDGE,
                "Staggered two-rank checkerboard of divisions with open firing lanes for second-rank line and carrier divisions."),
        FLEET_PROTECTIVE_BASTION(
                "Fleet Protective Bastion",
                "Bastion",
                DivisionFormation.PROTECTIVE_CIRCLE,
                "Carriers and support divisions in the protected core ringed by frontline combat divisions around the perimeter."),
        NONE(
                "None (Independent)",
                "None",
                DivisionFormation.NONE,
                "No fleet-wide formation; each division maneuvers independently along its own mission vector.");

        public final String displayName;
        public final String shortName;
        public final DivisionFormation iconFormation;
        public final String summary;

        FleetWideFormation(
                String displayName,
                String shortName,
                DivisionFormation iconFormation,
                String summary) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.iconFormation = iconFormation;
            this.summary = summary;
        }

        public static FleetWideFormation fromId(String id, FleetWideFormation fallback) {
            if (id == null) return fallback;
            try {
                return FleetWideFormation.valueOf(id.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    public static final int MIN_VOIDSTRIKE_SQUADRON_DEPTH = 1;
    public static final int MAX_VOIDSTRIKE_SQUADRON_DEPTH = 10;
    public static final int MIN_VOIDSTRIKE_SQUADRON_WIDTH = 1;
    public static final int MAX_VOIDSTRIKE_SQUADRON_WIDTH = 10;
    public static final int MIN_RAMMING_ATTACK_SLOTS = 1;
    public static final int MAX_RAMMING_ATTACK_SLOTS = 10;

    /**
     * Per-division and doctrinal ramming mode.
     * Defaults to OFF unless the auto-assigner detects ram-capable ships in a division.
     */
    public enum DivisionRammingMode {
        OFF(
                "Off",
                "Off",
                "Forbid offensive ramming; use ram drives only for catch-up and extraction."),
        GLANCING_SKIRMISH(
                "Glancing Pass",
                "Glancing",
                "Sideswipe enemy flanks at low flux out of crossfire up to the slot cap."),
        SYNCHRONIZED_LANCE(
                "Lance Charge",
                "Lance",
                "Marshal the division first, then surge together up to the attack slot cap."),
        DIRECT_RAM(
                "Direct Ram",
                "Direct",
                "Execute direct center-mass ramming passes whenever ready up to the slot cap.");

        public final String displayName;
        public final String shortName;
        public final String summary;

        DivisionRammingMode(String displayName, String shortName, String summary) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.summary = summary;
        }

        public static DivisionRammingMode fromId(String id, DivisionRammingMode fallback) {
            if (id == null) return fallback;
            try {
                return DivisionRammingMode.valueOf(id.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Whether a division's maximum ramming attack slots (1..10) apply against a single ship target
     * or across the entire target enemy division.
     */
    public enum DivisionRammingTargetScope {
        SINGLE_SHIP(
                "Single Ship",
                "Ship",
                "Focus ramming attack slots (1-10) onto a single priority enemy target ship."),
        ENEMY_DIVISION(
                "Enemy Division",
                "Division",
                "Select multiple targets in a localized space conducive to ramming (1-10 slots).");

        public final String displayName;
        public final String shortName;
        public final String summary;

        DivisionRammingTargetScope(String displayName, String shortName, String summary) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.summary = summary;
        }

        public static DivisionRammingTargetScope fromId(String id, DivisionRammingTargetScope fallback) {
            if (id == null) return fallback;
            try {
                return DivisionRammingTargetScope.valueOf(id.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Doctrinal proportion of non-bomber squadrons held back on Adaptive Umbrella Combat Space Patrol (CSP)
     * while the division's remaining strike package executes offensive Voidstrike / Search & Destroy / Recon missions.
     * Never holds pure Bomber wings (`WingRole.BOMBER` / `spec.isBomber()`) on CAP.
     */
    public enum StrikeCraftCapReserve {
        NONE(
                "None",
                "None",
                "Commit all non-support wings to strike missions."),
        LIGHT_25(
                "25% Reserve",
                "25%",
                "Hold 25% of non-bomber wings on CAP umbrella."),
        BALANCED_50(
                "50% Reserve",
                "50%",
                "Hold 50% of non-bomber wings on CAP umbrella."),
        ALL_INTERCEPTORS(
                "Interceptors",
                "Int. Only",
                "Hold all Interceptor wings on CAP; never Bombers.");

        public final String displayName;
        public final String shortName;
        public final String summary;

        StrikeCraftCapReserve(String displayName, String shortName, String summary) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.summary = summary;
        }

        public static StrikeCraftCapReserve fromId(String id, StrikeCraftCapReserve fallback) {
            if (id == null) return fallback;
            try {
                return StrikeCraftCapReserve.valueOf(id.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Doctrinal target-class selection bias for carrier Voidstrikes.
     */
    public enum VoidstrikeTargetingBias {
        FREE_SELECTION(
                "Free Selection",
                "Free",
                "Engage any viable enemy division by distance and threat."),
        CRUISERS_AND_CAPITALS(
                "Cruisers & Capitals",
                "Cruiser/Cap",
                "Prioritize enemy Cruiser and Capital divisions while heavy hulls remain."),
        CAPITALS_FIRST(
                "Capitals First",
                "Capitals 1st",
                "Prioritize enemy Capitals first, then Cruisers, before light screens.");

        public final String displayName;
        public final String shortName;
        public final String summary;

        VoidstrikeTargetingBias(String displayName, String shortName, String summary) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.summary = summary;
        }

        public static VoidstrikeTargetingBias fromId(String id, VoidstrikeTargetingBias fallback) {
            if (id == null) return fallback;
            try {
                return VoidstrikeTargetingBias.valueOf(id.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Item C: How closely strike craft obey the order to destroy the designated target in a Voidstrike.
     */
    public enum VoidstrikeTargetObservance {
        FREE(
                "Free",
                "Engage targets of opportunity along flight path."),
        MEDIUM(
                "Medium",
                "Prioritize target; engage threats within 700u."),
        HIGH(
                "High",
                "Lock strictly onto designated target.");

        public final String displayName;
        public final String summary;

        VoidstrikeTargetObservance(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public static VoidstrikeTargetObservance fromId(String id, VoidstrikeTargetObservance fallback) {
            if (id == null) return fallback;
            try {
                return VoidstrikeTargetObservance.valueOf(id.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Item D: Whether deckload strikes and multi-division strikes launch as a coordinated package
     * (orbit carrier -> marshal point nearby -> synchronized attack) or in uncoordinated dispersed waves.
     */
    public enum VoidstrikeCoordinationMode {
        COORDINATED(
                "Coordinated",
                "Orbit carrier, form up at marshal point, voidstrike together."),
        UNCOORDINATED(
                "Uncoordinated",
                "Attack immediately in successive voidstrike waves.");

        public final String displayName;
        public final String summary;

        VoidstrikeCoordinationMode(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public static VoidstrikeCoordinationMode fromId(String id, VoidstrikeCoordinationMode fallback) {
            if (id == null) return fallback;
            try {
                return VoidstrikeCoordinationMode.valueOf(id.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Multi-axis approach geometry for Voidstrike packages, particularly effective for unguided
     * torpedo/rocket bombers forcing an enemy ship's shield facing across orthogonal or converging vectors.
     */
    public enum VoidstrikeAttackGeometry {
        DIRECT_ASSAULT(
                "Direct",
                "All strike squadrons approach along the primary Voidstrike axis."),
        ANVIL_CROSSFIRE(
                "L-Crossfire",
                "L-shaped hammer-and-anvil crossfire: splits squadrons into orthogonal 0 deg and 90 deg attack legs so targets cannot shield both axes."),
        PINCER_ENVELOPMENT(
                "Pincer",
                "Converging +-55 deg flanking envelope attacking both target beams simultaneously.");

        public final String displayName;
        public final String summary;

        VoidstrikeAttackGeometry(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public static VoidstrikeAttackGeometry fromId(String id, VoidstrikeAttackGeometry fallback) {
            if (id == null) return fallback;
            String norm = id.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
            if ("DIRECT".equals(norm)) return DIRECT_ASSAULT;
            if ("L_CROSSFIRE".equals(norm) || "CROSSFIRE".equals(norm) || "L_SHAPED".equals(norm)) {
                return ANVIL_CROSSFIRE;
            }
            if ("PINCER".equals(norm)) return PINCER_ENVELOPMENT;
            try {
                return VoidstrikeAttackGeometry.valueOf(norm);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Doctrinal wing-splitting mode within a single Voidstrike entity:
     *  - UNIFIED: All squadrons in the division attack as one unified wing on the primary target.
     *  - SPLIT_AXES: Splits squadrons into multi-axis sub-wings within the same Voidstrike entity.
     *  - SPLIT_TARGETS: Splits squadrons across multiple enemy division ships and axes within the same Voidstrike entity.
     */
    public enum VoidstrikeSplitMode {
        UNIFIED(
                "Unified",
                "All squadrons attack as one unified wing on the primary target."),
        SPLIT_AXES(
                "Split Axes",
                "Splits squadrons into multi-axis sub-wings in the same Voidstrike."),
        SPLIT_TARGETS(
                "Split Targets",
                "Splits squadrons across enemy division ships in the same Voidstrike.");

        public final String displayName;
        public final String summary;

        VoidstrikeSplitMode(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public static VoidstrikeSplitMode fromId(String id, VoidstrikeSplitMode fallback) {
            if (id == null) return fallback;
            String norm = id.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
            if ("SINGLE".equals(norm) || "NONE".equals(norm) || "CONCENTRATED".equals(norm)) return UNIFIED;
            if ("AXES".equals(norm) || "SPLIT_WING".equals(norm) || "SPLIT_WINGS".equals(norm)) return SPLIT_AXES;
            if ("TARGETS".equals(norm) || "MULTI_TARGET".equals(norm)) return SPLIT_TARGETS;
            try {
                return VoidstrikeSplitMode.valueOf(norm);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Doctrinal gunnery targeting mode for Voidstrike ordnance release:
     * PRECISION aims all craft at the target ship's core intercept point;
     * AREA_SATURATION assigns hierarchical firing sectors (Voidstrike -> Squadron -> Craft)
     * across the target ship's silhouette and lateral evasion envelope.
     */
    public enum VoidstrikeGunneryMode {
        PRECISION(
                "Precision",
                "All strike craft aim directly at the target ship's core intercept point for concentrated pinpoint strikes."),
        AREA_SATURATION(
                "Area",
                "Hierarchical firing sectors (Voidstrike -> Squadron -> Craft) saturate the target ship's silhouette and evasion envelope with an evenly spaced ordnance spread.");

        public final String displayName;
        public final String summary;

        VoidstrikeGunneryMode(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public static VoidstrikeGunneryMode fromId(String id, VoidstrikeGunneryMode fallback) {
            if (id == null) return fallback;
            String norm = id.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
            if ("AREA".equals(norm) || "SATURATION".equals(norm)) return AREA_SATURATION;
            try {
                return VoidstrikeGunneryMode.valueOf(norm);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Terminal Angle-of-Attack (AoA) correction intensity for strike craft during the attack run,
     * governing ballistic lead calculation, lateral slip velocity damping, nose slew rate, and
     * off-boresight fire-gating for unguided munitions.
     */
    public enum VoidstrikeAoaCorrection {
        LOW(
                "Low",
                "Gentle terminal run-in alignment, light slip damping, and partial ballistic lead without off-boresight fire-gating."),
        STRONG(
                "Strong",
                "Aggressive terminal run-in corridor alignment, full ballistic intercept lead, heavy lateral slip damping, and off-boresight fire-gating (<= 6.5 deg) for unguided munitions.");

        public final String displayName;
        public final String summary;

        VoidstrikeAoaCorrection(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public static VoidstrikeAoaCorrection fromId(String id, VoidstrikeAoaCorrection fallback) {
            if (id == null) return fallback;
            String norm = id.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
            if ("HIGH".equals(norm)) return STRONG;
            try {
                return VoidstrikeAoaCorrection.valueOf(norm);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Doctrinal and per-division option controlling whether division members synchronize their movement
     * and combat ship systems under Division Commander / OTC Positive Control (preventing premature solo
     * scouting bursts, marshaling freely out of fire and cautiously under fire, and firing linear/offensive
     * skills in unison) or trigger ship systems independently.
     */
    public enum ShipSkillCoordinationMode {
        SYNCHRONIZED(
                "Synchronized",
                "Hold premature solo bursts; marshal and trigger ship skills in unison under division/OTC control."),
        INDEPENDENT(
                "Independent",
                "Ships trigger their own movement and combat systems individually at their own AI discretion.");

        public final String displayName;
        public final String summary;

        ShipSkillCoordinationMode(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public static ShipSkillCoordinationMode fromId(String id, ShipSkillCoordinationMode fallback) {
            if (id == null) return fallback;
            try {
                return ShipSkillCoordinationMode.valueOf(id.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Items E, D2, F, G: Preferred Fighter & Bomber Squadron Formations (general patrol vs. in Voidstrike).
     */
    public enum SquadronFormation {
        LINE_ABREAST("Line Abreast", "Abreast", DivisionFormation.LINE_ABREAST),
        LINE_AHEAD("Line Ahead", "Ahead", DivisionFormation.LINE_AHEAD),
        WEDGE("Forward Wedge", "F. Wedge", DivisionFormation.FORWARD_WEDGE),
        REVERSE_WEDGE("Reverse Wedge", "R. Wedge", DivisionFormation.REVERSE_WEDGE),
        CIRCLE("Circle", "Circle", DivisionFormation.CIRCLE),
        SEMICIRCLE("Semicircle", "F. Arc", DivisionFormation.FORWARD_SEMICIRCLE),
        REVERSE_SEMICIRCLE("Reverse Semicircle", "R. Arc", DivisionFormation.REVERSE_SEMICIRCLE);

        public final String displayName;
        public final String shortName;
        public final DivisionFormation iconFormation;

        SquadronFormation(String displayName, String shortName, DivisionFormation iconFormation) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.iconFormation = iconFormation;
        }

        public static final SquadronFormation[] FIGHTER_GENERAL_OPTIONS = new SquadronFormation[]{
                LINE_ABREAST, WEDGE, REVERSE_WEDGE, CIRCLE, SEMICIRCLE, REVERSE_SEMICIRCLE
        };

        public static final SquadronFormation[] FIGHTER_VOIDSTRIKE_OPTIONS = new SquadronFormation[]{
                LINE_AHEAD, WEDGE, REVERSE_WEDGE, CIRCLE, SEMICIRCLE, REVERSE_SEMICIRCLE
        };

        public static final SquadronFormation[] BOMBER_GENERAL_OPTIONS = new SquadronFormation[]{
                LINE_ABREAST, WEDGE, REVERSE_WEDGE, CIRCLE, SEMICIRCLE, REVERSE_SEMICIRCLE
        };

        public static final SquadronFormation[] BOMBER_VOIDSTRIKE_OPTIONS = new SquadronFormation[]{
                LINE_ABREAST, WEDGE, REVERSE_WEDGE, CIRCLE, SEMICIRCLE, REVERSE_SEMICIRCLE
        };

        public static SquadronFormation fromId(String id, SquadronFormation fallback) {
            if (id == null) return fallback;
            try {
                return SquadronFormation.valueOf(id.trim().toUpperCase());
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
                "Scouting",
                "Scouting",
                "Captures objectives and holds enemy contact at range without overcommitting.",
                false,
                LineStation.VAN),
        CLOSE_ACTION(
                "Close Action",
                "Close Action",
                "Advances into effective weapon range to fix and destroy the enemy line.",
                false,
                LineStation.VAN),
        STANDOFF_ENGAGEMENT(
                "Standoff Engagement",
                "Standoff",
                "Exchanges fire at maximum gun range and gives ground when pressed.",
                false,
                LineStation.CENTER),
        CARRIER_STANDOFF(
                "Carrier Standoff",
                "Carrier Standoff",
                "Holds astern out of enemy gun range while flight decks operate.",
                false,
                LineStation.REAR),
        ENVELOPMENT(
                "Envelopment",
                "Envelopment",
                "Sweeps wide around the enemy line to attack exposed flanks and rear.",
                false,
                LineStation.VAN),
        TOT_ALPHA_STRIKE(
                "ToT Alpha Strike",
                "ToT Alpha",
                "Marshals outside range, surges in for a synchronized salvo, then breaks off.",
                false,
                LineStation.VAN),
        SCREEN(
                "Screen",
                "Screen",
                "Screens ahead of friendly capitals and carriers against flankers and missiles.",
                false,
                LineStation.CENTER),

        // Carrier Missions and Tactics (explicitly labeled as Carrier-Only in UI)
        ARMED_RECONNAISSANCE(
                "Armed Recon (Carrier)",
                "Armed Recon",
                "Holds carriers astern while sending wings ahead to scout and voidstrike.",
                true,
                LineStation.REAR),
        SPACE_SUPERIORITY(
                "Space Superiority (Carrier)",
                "Space Sup.",
                "Sweeps enemy fighters, bombers, and light escorts from contested space.",
                true,
                LineStation.CENTER),
        COMBAT_SPACE_PATROL(
                "Combat Space Patrol (Carrier)",
                "CAP",
                "Maintains defensive fighter cover over friendly frontline and carrier divisions.",
                true,
                LineStation.REAR),
        CYCLIC_STRIKES(
                "Cyclic Voidstrikes (Carrier)",
                "Cyclic Voidstrikes",
                "Launches continuous voidstrike waves against visible enemy targets.",
                true,
                LineStation.REAR),
        DECKLOAD_STRIKE(
                "Deckload Voidstrike (Carrier)",
                "Deck Voidstrike",
                "Marshals all division wings into a single synchronized voidstrike.",
                true,
                LineStation.REAR),
        ALPHA_STRIKE(
                "Alpha Voidstrike (Carrier)",
                "Alpha Voidstrike",
                "Coordinates multi-division carrier voidstrikes against the Main Effort target.",
                true,
                LineStation.REAR),

        // Universal Reserve Mission
        RESERVE(
                "Reserve",
                "Reserve",
                "Holds astern of the battle line until committed by the Officer in Tactical Command.",
                false,
                LineStation.REAR),

        // Fanatical Martyrdom Mission (Luddic Path & VBIEDs)
        MARTYRDOM(
                "Martyrdom",
                "Martyrdom",
                "Charges directly into point-blank range without backing off or venting.",
                false,
                LineStation.VAN);

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
            if (isCarrierType) {
                list.add(CARRIER_STANDOFF);
                list.add(STANDOFF_ENGAGEMENT);
                list.add(CLOSE_ACTION);
                list.add(SCREEN);
                list.add(SCOUTING);
                list.add(ENVELOPMENT);
                list.add(TOT_ALPHA_STRIKE);
                list.add(RESERVE);
                list.add(MARTYRDOM);
            } else {
                for (DivisionMissionType m : values()) {
                    if (!m.carrierSpecific) {
                        list.add(m);
                    }
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
     * Independent Strike Craft Mission (Opening & Main) assignable to ANY division under Strike Craft Options.
     * Decouples what a division's fighter/bomber squadrons do from what its ship hulls do (e.g. Converted Hangar
     * Destroyers or Battlecarriers fighting in Close Action while their wings run CAP or Alpha Voidstrikes).
     */
    public enum StrikeCraftMissionType {
        MATCH_DIVISION(
                "Match Division",
                "Match Div.",
                "Squadrons support the division's hull mission and target."),
        ARMED_RECONNAISSANCE(
                "Armed Recon",
                "Armed Recon",
                "Squadrons scout ahead and strike targets of opportunity."),
        SEARCH_AND_DESTROY(
                "Search & Destroy",
                "S&D Strike",
                "Send max strike package to enemy center to find and destroy."),
        SPACE_SUPERIORITY(
                "Space Superiority",
                "Space Sup.",
                "Squadrons sweep enemy bombers, fighters, and light escorts."),
        COMBAT_SPACE_PATROL(
                "Combat Space Patrol",
                "CAP",
                "Squadrons maintain a defensive interceptor umbrella over friendlies."),
        CYCLIC_STRIKES(
                "Cyclic Voidstrikes",
                "Cyclic Voidstrikes",
                "Squadrons launch continuous voidstrike waves against enemy ships."),
        DECKLOAD_STRIKE(
                "Deckload Voidstrike",
                "Deck Voidstrike",
                "Squadrons marshal together into synchronized division voidstrikes."),
        ALPHA_STRIKE(
                "Alpha Voidstrike",
                "Alpha Voidstrike",
                "Squadrons coordinate multi-division voidstrikes on priority targets."),
        HOLD_ON_DECK(
                "Hold on Deck",
                "Hold on Deck",
                "Keeps strike craft recalled on deck until ordered otherwise.");

        public final String dropdownLabel;
        public final String shortName;
        public final String summary;

        StrikeCraftMissionType(String dropdownLabel, String shortName, String summary) {
            this.dropdownLabel = dropdownLabel;
            this.shortName = shortName;
            this.summary = summary;
        }

        public static StrikeCraftMissionType fromLegacyCarrierMission(DivisionMissionType mis) {
            if (mis == null) return null;
            switch (mis) {
                case ARMED_RECONNAISSANCE:
                    return ARMED_RECONNAISSANCE;
                case SPACE_SUPERIORITY:
                    return SPACE_SUPERIORITY;
                case COMBAT_SPACE_PATROL:
                    return COMBAT_SPACE_PATROL;
                case CYCLIC_STRIKES:
                    return CYCLIC_STRIKES;
                case DECKLOAD_STRIKE:
                    return DECKLOAD_STRIKE;
                case ALPHA_STRIKE:
                    return ALPHA_STRIKE;
                default:
                    return null;
            }
        }

        public static StrikeCraftMissionType fromId(String id, StrikeCraftMissionType fallback) {
            if (id == null) return fallback;
            try {
                return StrikeCraftMissionType.valueOf(id.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                DivisionMissionType legacy = DivisionMissionType.fromId(id.trim(), null);
                StrikeCraftMissionType mapped = fromLegacyCarrierMission(legacy);
                return mapped != null ? mapped : fallback;
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
                LineStation.VAN,
                DivisionFormation.FORWARD_SEMICIRCLE),
        MISSILE_FRIGATE(
                "Missile Frigate Division",
                DivisionArchetype.MISSILE_FRIGATE_DIVISION,
                425,
                925,
                1100,
                1.15f,
                DivisionMissionType.SCOUTING,
                DivisionMissionType.STANDOFF_ENGAGEMENT,
                LineStation.VAN,
                DivisionFormation.REVERSE_SEMICIRCLE),
        STRIKE_FRIGATE(
                "Strike Frigate Division",
                DivisionArchetype.STRIKE_FRIGATE_DIVISION,
                390,
                900,
                700,
                1.18f,
                DivisionMissionType.SCOUTING,
                DivisionMissionType.TOT_ALPHA_STRIKE,
                LineStation.VAN,
                DivisionFormation.FORWARD_WEDGE),
        DESTROYER(
                "Destroyer Division",
                DivisionArchetype.DESTROYER_DIVISION,
                450,
                950,
                850,
                1.05f,
                DivisionMissionType.SCOUTING,
                DivisionMissionType.SCREEN,
                LineStation.CENTER,
                DivisionFormation.LINE_ABREAST),
        STRIKE_DESTROYER(
                "Strike Destroyer Division",
                DivisionArchetype.STRIKE_DESTROYER_DIVISION,
                460,
                980,
                780,
                1.12f,
                DivisionMissionType.SCOUTING,
                DivisionMissionType.ENVELOPMENT,
                LineStation.VAN,
                DivisionFormation.FORWARD_WEDGE),
        MISSILE_DESTROYER(
                "Missile Destroyer Division",
                DivisionArchetype.MISSILE_DESTROYER_DIVISION,
                475,
                1000,
                1150,
                1.10f,
                DivisionMissionType.SCOUTING,
                DivisionMissionType.STANDOFF_ENGAGEMENT,
                LineStation.CENTER,
                DivisionFormation.REVERSE_SEMICIRCLE),
        CRUISER(
                "Line Cruiser Division",
                DivisionArchetype.CRUISER_DIVISION,
                525,
                1050,
                800,
                1.00f,
                DivisionMissionType.CLOSE_ACTION,
                DivisionMissionType.CLOSE_ACTION,
                LineStation.CENTER,
                DivisionFormation.LINE_ABREAST),
        MISSILE_CRUISER(
                "Missile Cruiser Division",
                DivisionArchetype.MISSILE_CRUISER_DIVISION,
                550,
                1100,
                1250,
                1.05f,
                DivisionMissionType.STANDOFF_ENGAGEMENT,
                DivisionMissionType.STANDOFF_ENGAGEMENT,
                LineStation.CENTER,
                DivisionFormation.REVERSE_WEDGE),
        STRIKE_CRUISER(
                "Strike Cruiser Division",
                DivisionArchetype.STRIKE_CRUISER_DIVISION,
                550,
                1100,
                900,
                1.10f,
                DivisionMissionType.SCOUTING,
                DivisionMissionType.ENVELOPMENT,
                LineStation.VAN,
                DivisionFormation.FORWARD_WEDGE),
        PHASE_CRUISER(
                "Phase Cruiser Division",
                DivisionArchetype.PHASE_CRUISER_DIVISION,
                525,
                1120,
                880,
                1.18f,
                DivisionMissionType.STANDOFF_ENGAGEMENT,
                DivisionMissionType.ENVELOPMENT,
                LineStation.VAN,
                DivisionFormation.ECHELON_RIGHT),
        BATTLESHIP(
                "Battleship Division",
                DivisionArchetype.BATTLESHIP_DIVISION,
                675,
                1200,
                750,
                0.95f,
                DivisionMissionType.CLOSE_ACTION,
                DivisionMissionType.CLOSE_ACTION,
                LineStation.VAN,
                DivisionFormation.LINE_ABREAST),
        BATTLECRUISER(
                "Battlecruiser Division",
                DivisionArchetype.BATTLECRUISER_DIVISION,
                625,
                1180,
                950,
                1.06f,
                DivisionMissionType.STANDOFF_ENGAGEMENT,
                DivisionMissionType.ENVELOPMENT,
                LineStation.VAN,
                DivisionFormation.ECHELON_RIGHT),
        MISSILE_BATTLESHIP(
                "Missile Battleship Division",
                DivisionArchetype.MISSILE_BATTLESHIP_DIVISION,
                700,
                1250,
                1350,
                1.00f,
                DivisionMissionType.STANDOFF_ENGAGEMENT,
                DivisionMissionType.STANDOFF_ENGAGEMENT,
                LineStation.CENTER,
                DivisionFormation.LINE_ABREAST),
        BATTLECARRIER(
                "Battlecarrier Division",
                DivisionArchetype.BATTLECARRIER_DIVISION,
                650,
                1220,
                1050,
                0.96f,
                DivisionMissionType.STANDOFF_ENGAGEMENT,
                DivisionMissionType.CLOSE_ACTION,
                LineStation.CENTER,
                DivisionFormation.LINE_ABREAST),
        FLEET_CARRIER(
                "Fleet Carrier Division",
                DivisionArchetype.FLEET_CARRIER_DIVISION,
                650,
                1350,
                1600,
                0.95f,
                DivisionMissionType.CARRIER_STANDOFF,
                DivisionMissionType.CARRIER_STANDOFF,
                LineStation.REAR,
                DivisionFormation.LINE_ABREAST),
        LIGHT_CARRIER(
                "Light Carrier Division",
                DivisionArchetype.LIGHT_CARRIER_DIVISION,
                550,
                1250,
                1450,
                0.95f,
                DivisionMissionType.CARRIER_STANDOFF,
                DivisionMissionType.CARRIER_STANDOFF,
                LineStation.REAR,
                DivisionFormation.LINE_ABREAST),
        ESCORT_CARRIER(
                "Escort Carrier Division",
                DivisionArchetype.ESCORT_CARRIER_DIVISION,
                475,
                1150,
                1350,
                0.95f,
                DivisionMissionType.CARRIER_STANDOFF,
                DivisionMissionType.CARRIER_STANDOFF,
                LineStation.REAR,
                DivisionFormation.FORWARD_SEMICIRCLE),
        CARRIER_GROUP(
                "Carrier Group",
                DivisionArchetype.CARRIER_GROUP,
                575,
                1300,
                1450,
                0.95f,
                DivisionMissionType.CARRIER_STANDOFF,
                DivisionMissionType.CARRIER_STANDOFF,
                LineStation.REAR,
                DivisionFormation.PROTECTIVE_CIRCLE),
        PHASE(
                "Phase Division",
                DivisionArchetype.PHASE_DIVISION,
                475,
                1100,
                1050,
                1.25f,
                DivisionMissionType.SCOUTING,
                DivisionMissionType.ENVELOPMENT,
                LineStation.VAN,
                DivisionFormation.ECHELON_RIGHT),
        MISSILE_PHASE(
                "Missile Phase Division",
                DivisionArchetype.MISSILE_PHASE_DIVISION,
                480,
                1100,
                1220,
                1.20f,
                DivisionMissionType.STANDOFF_ENGAGEMENT,
                DivisionMissionType.TOT_ALPHA_STRIKE,
                LineStation.VAN,
                DivisionFormation.LINE_ABREAST),
        SERVICE_GROUP(
                "Service Group",
                DivisionArchetype.SERVICE_GROUP,
                450,
                1200,
                1500,
                0.90f,
                DivisionMissionType.RESERVE,
                DivisionMissionType.RESERVE,
                LineStation.REAR,
                DivisionFormation.CIRCLE),
        FORCE(
                "Force Division",
                DivisionArchetype.FORCE_DIVISION,
                500,
                1050,
                850,
                1.00f,
                DivisionMissionType.SCOUTING,
                DivisionMissionType.CLOSE_ACTION,
                LineStation.CENTER,
                DivisionFormation.LINE_ABREAST);

        public final String displayName;
        public final DivisionArchetype primaryArchetype;
        public final int defaultStationSpacingUnits;
        public final int defaultDivisionIntervalUnits;
        public final int defaultStandoffRangeUnits;
        public final float defaultStationKeepingMult;
        public final DivisionMissionType defaultOpeningMission;
        public final DivisionMissionType defaultMainMission;
        public final LineStation defaultLineStation;
        public final DivisionFormation defaultFormation;

        DoctrineDivisionType(
                String displayName,
                DivisionArchetype primaryArchetype,
                int defaultStationSpacingUnits,
                int defaultDivisionIntervalUnits,
                int defaultStandoffRangeUnits,
                float defaultStationKeepingMult,
                DivisionMissionType defaultOpeningMission,
                DivisionMissionType defaultMainMission,
                LineStation defaultLineStation,
                DivisionFormation defaultFormation) {
            this.displayName = displayName;
            this.primaryArchetype = primaryArchetype;
            this.defaultStationSpacingUnits = defaultStationSpacingUnits;
            this.defaultDivisionIntervalUnits = defaultDivisionIntervalUnits;
            this.defaultStandoffRangeUnits = defaultStandoffRangeUnits;
            this.defaultStationKeepingMult = defaultStationKeepingMult;
            this.defaultOpeningMission = defaultOpeningMission;
            this.defaultMainMission = defaultMainMission;
            this.defaultLineStation = defaultLineStation;
            this.defaultFormation = defaultFormation;
        }

        public boolean isCarrierType() {
            return primaryArchetype != null && primaryArchetype.isCarrierArchetype();
        }

        public boolean hasStrikeCraftDoctrine() {
            return isCarrierType() || this == BATTLECARRIER;
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
            if (arch == null) return FORCE;
            switch (arch) {
                case FRIGATE_DIVISION:
                    return FRIGATE;
                case STRIKE_FRIGATE_DIVISION:
                    return STRIKE_FRIGATE;
                case MISSILE_FRIGATE_DIVISION:
                    return MISSILE_FRIGATE;
                case DESTROYER_DIVISION:
                    return DESTROYER;
                case STRIKE_DESTROYER_DIVISION:
                    return STRIKE_DESTROYER;
                case MISSILE_DESTROYER_DIVISION:
                    return MISSILE_DESTROYER;
                case MISSILE_CRUISER_DIVISION:
                    return MISSILE_CRUISER;
                case STRIKE_CRUISER_DIVISION:
                    return STRIKE_CRUISER;
                case PHASE_CRUISER_DIVISION:
                    return PHASE_CRUISER;
                case BATTLESHIP_DIVISION:
                    return BATTLESHIP;
                case BATTLECRUISER_DIVISION:
                    return BATTLECRUISER;
                case MISSILE_BATTLESHIP_DIVISION:
                    return MISSILE_BATTLESHIP;
                case BATTLECARRIER_DIVISION:
                    return BATTLECARRIER;
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
                case MISSILE_PHASE_DIVISION:
                    return MISSILE_PHASE;
                case SERVICE_GROUP:
                    return SERVICE_GROUP;
                case FORCE_DIVISION:
                    return FORCE;
                case CRUISER_DIVISION:
                default:
                    return CRUISER;
            }
        }
    }

    /**
     * Division capability families that can be enabled or disabled per hull size for the player fleet's auto-assigner.
     */
    public enum AutoAssignCapability {
        LINE("Line Combat", "Line", "Standard gun-line combat divisions."),
        STRIKE("Strike / Fast", "Strike", "Battlecruisers and fast strike divisions."),
        MISSILE("Missile", "Missile", "Missile and torpedo strike divisions."),
        BATTLECARRIER("Battlecarrier", "BCV", "Hybrid gun-line capital carrier divisions."),
        CARRIER("Carrier", "Carrier", "Dedicated carrier divisions."),
        PHASE("Phase", "Phase", "Cloaked phase cruiser and skimmer divisions."),
        MISSILE_PHASE("Missile Phase", "Msl Phase", "Standoff missile and autoforge phase divisions."),
        SERVICE("Service / Aux", "Service", "Civilian logistics and auxiliary groups.");

        public final String displayName;
        public final String shortName;
        public final String summary;

        AutoAssignCapability(String displayName, String shortName, String summary) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.summary = summary;
        }

        public boolean supportsHull(AutoAssignHull hull) {
            if (hull == null) return false;
            if (this == BATTLECARRIER) {
                return hull == AutoAssignHull.BATTLESHIP;
            }
            if (this == PHASE || this == MISSILE_PHASE) {
                return hull != AutoAssignHull.BATTLESHIP;
            }
            return true;
        }

        public static AutoAssignCapability fromId(String id, AutoAssignCapability fallback) {
            if (id == null || id.trim().isEmpty()) return fallback;
            try {
                return AutoAssignCapability.valueOf(id.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * Hull size classes that can be enabled or disabled per capability for the player fleet's auto-assigner.
     */
    public enum AutoAssignHull {
        BATTLESHIP("Battleship", "Battleship", HullSize.CAPITAL_SHIP),
        CRUISER("Cruiser", "Cruiser", HullSize.CRUISER),
        DESTROYER("Destroyer", "Destroyer", HullSize.DESTROYER),
        FRIGATE("Frigate", "Frigate", HullSize.FRIGATE);

        public final String displayName;
        public final String shortName;
        public final HullSize hullSize;

        AutoAssignHull(String displayName, String shortName, HullSize hullSize) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.hullSize = hullSize;
        }

        public static AutoAssignHull fromId(String id, AutoAssignHull fallback) {
            if (id == null || id.trim().isEmpty()) return fallback;
            String norm = id.trim().toUpperCase(java.util.Locale.ROOT);
            if ("CAPITAL".equals(norm) || "CAPITAL_SHIP".equals(norm)) return BATTLESHIP;
            try {
                return AutoAssignHull.valueOf(norm);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }

        public static AutoAssignHull forHullSize(HullSize size) {
            if (size == null) return FRIGATE;
            switch (size) {
                case CAPITAL_SHIP:
                    return BATTLESHIP;
                case CRUISER:
                    return CRUISER;
                case DESTROYER:
                    return DESTROYER;
                case FRIGATE:
                default:
                    return FRIGATE;
            }
        }
    }

    /**
     * Hull categories configurable in the Fleet Doctrine screen (minimum & maximum hulls per division).
     */
    public enum DoctrineHullCategory {
        CRUISER(
                "Cruiser Hulls",
                "Min Ships",
                "Max Ships",
                0,
                4,
                DivisionArchetype.CRUISER_DIVISION),
        CAPITAL(
                "Capital Hulls",
                "Min Ships",
                "Max Ships",
                0,
                2,
                DivisionArchetype.BATTLESHIP_DIVISION),
        DESTROYER(
                "Destroyer Hulls",
                "Min Ships",
                "Max Ships",
                0,
                4,
                DivisionArchetype.DESTROYER_DIVISION),
        FRIGATE(
                "Frigate Hulls",
                "Min Ships",
                "Max Ships",
                0,
                5,
                DivisionArchetype.FRIGATE_DIVISION),
        FREIGHTER(
                "Freighter Hulls",
                "Min Ships",
                "Max Ships",
                0,
                4,
                DivisionArchetype.SERVICE_GROUP),
        TANKER(
                "Tanker Hulls",
                "Min Ships",
                "Max Ships",
                0,
                4,
                DivisionArchetype.SERVICE_GROUP),
        TRANSPORT(
                "Transport Hulls",
                "Min Ships",
                "Max Ships",
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
        POSITIVE_CONTROL(
                "Positive Control",
                "Positive Control",
                "The Officer in Tactical Command will directly control all division orders.",
                "+ The Officer in Tactical Command will synchronize all fleet maneuvers and fire.",
                "- Division commanders will not exercise independent initiative or mission autonomy.",
                "otc_positive_control"),
        COMMAND_BY_DIRECTION(
                "Command by Direction",
                "Command by Direction",
                "The Officer in Tactical Command will assign targets, stations, flanks, and reserves.",
                "+ Balances fleet-wide coordination with each division's configured mission.",
                "- Heavily engaged divisions may delay or adapt orders from the Officer in Tactical Command.",
                "otc_command_by_direction"),
        COMMAND_BY_NEGATION(
                "Command by Negation",
                "Command by Negation",
                "The Officer in Tactical Command will designate targets while divisions act on initiative.",
                "+ Division commanders will have full autonomy to exploit local tactical openings.",
                "- The Officer in Tactical Command will only intervene to counter critical threats.",
                "otc_command_by_negation"),
        FIRE_DISTRIBUTION_ONLY(
                "Fire Distribution Only",
                "Fire Distribution Only",
                "The Officer in Tactical Command will assign targets and voidstrikes without moving divisions.",
                "+ Divisions will strictly follow their own missions while sharing fleet target locks.",
                "- The Officer in Tactical Command will not reposition divisions or commit reserves.",
                "otc_fire_distribution_only");

        public final String dropdownLabel;
        public final String shortName;
        public final String summary;
        public final String positiveText;
        public final String negativeText;
        public final String iconKey;

        OtcAuthority(
                String dropdownLabel,
                String shortName,
                String summary,
                String positiveText,
                String negativeText,
                String iconKey) {
            this.dropdownLabel = dropdownLabel;
            this.shortName = shortName;
            this.summary = summary;
            this.positiveText = positiveText;
            this.negativeText = negativeText;
            this.iconKey = iconKey;
        }

        public String getSettingsIconKey() {
            return iconKey;
        }

        public String getSpritePath() {
            return "graphics/icons/otc_emblems/" + iconKey + ".png";
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
                "Main Effort fixes primary threat; others distribute fire."),
        DOUBLING(
                "Doubling",
                "Flankers attack the same target from opposite sides."),
        TIME_ON_TARGET_STRIKES(
                "Time-on-Target Strikes",
                "Missile and carrier divisions launch synchronized salvos and voidstrikes."),
        RESERVE_AND_GENERAL_CHASE(
                "Reserve & General Chase",
                "Holds reserves for relief, then pursues broken enemies."),
        MARTYRDOM(
                "Martyrdom",
                "Commits fanatical hulls to point-blank ramming runs.");

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

    /**
     * High-level naval operational presets on the Command Scheme tab. Selecting a template populates the four
     * editable operational options below it ({@link OperationalAnchor}, {@link ObjectiveCapturePolicy},
     * {@link DecisiveArm}, and {@link EngagementDiscipline}), which the player may adjust individually at any time.
     */
    public enum OperationalTemplate {
        LIGHT_FORCES_TORPEDO(
                "Light Forces and Strike Warfare",
                "Light & Strike Warfare",
                "Flotillas contest the map and converge with torpedo and strike runs.",
                "icon_template_light_strike_warfare",
                "graphics/icons/operational_templates/pennant_light_strike_warfare.png",
                OperationalAnchor.OPEN_FLOTILLA,
                ObjectiveCapturePolicy.ACTIVE_CONTEST,
                DecisiveArm.TORPEDO_STRIKES,
                EngagementDiscipline.SKIRMISH_WITHDRAW),
        LINEAR_WARFARE(
                "Linear Warfare",
                "Linear Warfare",
                "Gunline fixes the enemy center while light forces work the flanks.",
                "icon_template_linear_warfare",
                "graphics/icons/operational_templates/pennant_linear_warfare.png",
                OperationalAnchor.BATTLE_LINE,
                ObjectiveCapturePolicy.LIGHT_FORCES_ONLY,
                DecisiveArm.SURFACE_GUNFIRE,
                EngagementDiscipline.LINE_COHESION),
        CARRIER_CENTRIC(
                "Carrier-Centric Operations",
                "Carrier-Centric Ops",
                "Surface warships screen the carrier group while air wings strike.",
                "icon_template_carrier_centric",
                "graphics/icons/operational_templates/pennant_carrier_centric.png",
                OperationalAnchor.CARRIER_SCREEN,
                ObjectiveCapturePolicy.FLEET_AXIS_ONLY,
                DecisiveArm.CARRIER_STRIKE,
                EngagementDiscipline.CLOSE_SCREEN);

        public static final String CUSTOM_CONDUCT_ICON_KEY = "icon_template_custom_conduct";
        public static final String CUSTOM_CONDUCT_SPRITE_PATH =
                "graphics/icons/operational_templates/pennant_custom_conduct.png";

        public final String displayName;
        public final String shortName;
        public final String summary;
        public final String settingsIconKey;
        public final String spritePath;
        public final OperationalAnchor defaultAnchor;
        public final ObjectiveCapturePolicy defaultObjectivePolicy;
        public final DecisiveArm defaultDecisiveArm;
        public final EngagementDiscipline defaultDiscipline;

        OperationalTemplate(
                String displayName,
                String shortName,
                String summary,
                String settingsIconKey,
                String spritePath,
                OperationalAnchor defaultAnchor,
                ObjectiveCapturePolicy defaultObjectivePolicy,
                DecisiveArm defaultDecisiveArm,
                EngagementDiscipline defaultDiscipline) {
            this.displayName = displayName;
            this.shortName = shortName;
            this.summary = summary;
            this.settingsIconKey = settingsIconKey;
            this.spritePath = spritePath;
            this.defaultAnchor = defaultAnchor;
            this.defaultObjectivePolicy = defaultObjectivePolicy;
            this.defaultDecisiveArm = defaultDecisiveArm;
            this.defaultDiscipline = defaultDiscipline;
        }

        public String getSettingsIconKey() {
            return settingsIconKey;
        }

        public String getSpritePath() {
            return spritePath;
        }

        public static OperationalTemplate fromId(String id, OperationalTemplate fallback) {
            if (id == null) return fallback;
            for (OperationalTemplate v : values()) {
                if (v.name().equalsIgnoreCase(id)) return v;
            }
            return fallback;
        }
    }

    /** What forms the battle group's center of gravity under OTC orchestration. */
    public enum OperationalAnchor {
        BATTLE_LINE(
                "Battle Line",
                "Heavy gunline forms the line of battle and fixes the enemy center."),
        OPEN_FLOTILLA(
                "Open Flotilla",
                "No static gunline; divisions maneuver in dispersed flotillas."),
        CARRIER_SCREEN(
                "Carrier Screen",
                "Surface warships form a protective screen around the carrier group.");

        public final String displayName;
        public final String summary;

        OperationalAnchor(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public static OperationalAnchor fromId(String id, OperationalAnchor fallback) {
            if (id == null) return fallback;
            for (OperationalAnchor v : values()) {
                if (v.name().equalsIgnoreCase(id)) return v;
            }
            return fallback;
        }
    }

    /** Which divisions may autonomously detach to seize uncaptured battlefield objectives. */
    public enum ObjectiveCapturePolicy {
        LIGHT_FORCES_ONLY(
                "Light Forces Only",
                "Frigates and destroyers take flank points; heavy hulls stay in line."),
        ACTIVE_CONTEST(
                "Active Contest",
                "Light forces, destroyers, and fast cruisers contest points map-wide."),
        FLEET_AXIS_ONLY(
                "Fleet Axis Only",
                "Divisions only capture nearby points along the fleet advance axis."),
        DIRECT_ORDERS_ONLY(
                "Direct Orders Only",
                "No autonomous point capture; divisions only cap on player order.");

        public final String displayName;
        public final String summary;

        ObjectiveCapturePolicy(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public static ObjectiveCapturePolicy fromId(String id, ObjectiveCapturePolicy fallback) {
            if (id == null) return fallback;
            for (ObjectiveCapturePolicy v : values()) {
                if (v.name().equalsIgnoreCase(id)) return v;
            }
            return fallback;
        }
    }

    /** Primary striking arm that governs OTC target priority and engagement tempo. */
    public enum DecisiveArm {
        SURFACE_GUNFIRE(
                "Surface Gunfire",
                "Engage and break the enemy main body in gun range."),
        TORPEDO_STRIKES(
                "Torpedo & Strike Runs",
                "Target isolated or high-flux hulls with coordinated strike salvos."),
        CARRIER_STRIKE(
                "Carrier Strike",
                "Hold standoff and trade space while air wings attrit heavy hulls.");

        public final String displayName;
        public final String summary;

        DecisiveArm(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public static DecisiveArm fromId(String id, DecisiveArm fallback) {
            if (id == null) return fallback;
            for (DecisiveArm v : values()) {
                if (v.name().equalsIgnoreCase(id)) return v;
            }
            return fallback;
        }
    }

    /** Surface division advance leash and flux/ordnance withdrawal discipline. */
    public enum EngagementDiscipline {
        LINE_COHESION(
                "Line Cohesion",
                "Standard mutual-support interval and standard flux rotation."),
        SKIRMISH_WITHDRAW(
                "Skirmish & Withdraw",
                "Wide maneuver freedom and earlier post-salvo or high-flux withdrawal."),
        CLOSE_SCREEN(
                "Close Screen",
                "Tight screen leash ahead of carriers; warships do not chase bait.");

        public final String displayName;
        public final String summary;

        EngagementDiscipline(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public static EngagementDiscipline fromId(String id, EngagementDiscipline fallback) {
            if (id == null) return fallback;
            for (EngagementDiscipline v : values()) {
                if (v.name().equalsIgnoreCase(id)) return v;
            }
            return fallback;
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
        private final Map<DoctrineDivisionType, DivisionEngineTelegraph> engineTelegraphByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, DivisionMissionType> openingMissionByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, DivisionMissionType> mainMissionByType = new LinkedHashMap<>();
        private Map<DoctrineDivisionType, StrikeCraftMissionType> strikeCraftOpeningMissionByType = new LinkedHashMap<>();
        private Map<DoctrineDivisionType, StrikeCraftMissionType> strikeCraftMainMissionByType = new LinkedHashMap<>();
        private Map<DoctrineDivisionType, StrikeCraftCapReserve> strikeCraftCapReserveByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, LineStation> lineStationByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, DivisionFormation> formationByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, VoidstrikeTargetingBias> voidstrikeTargetingBiasByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, Boolean> voidstrikeFocusCarriersByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, VoidstrikeTargetObservance> voidstrikeObservanceByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, VoidstrikeCoordinationMode> voidstrikeCoordinationByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, VoidstrikeAttackGeometry> voidstrikeAttackGeometryByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, VoidstrikeSplitMode> voidstrikeSplitModeByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, VoidstrikeGunneryMode> voidstrikeGunneryModeByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, VoidstrikeAoaCorrection> voidstrikeAoaCorrectionByType = new LinkedHashMap<>();
        private boolean migratedUnifiedVoidstrikeDefaultV1 = true;
        private boolean migratedCarrierStrikeWidth4V1 = true;

        private void ensureUnifiedVoidstrikeDefaultsMigrated() {
            if (!migratedUnifiedVoidstrikeDefaultV1) {
                migratedUnifiedVoidstrikeDefaultV1 = true;
                for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
                    voidstrikeAttackGeometryByType.put(dt, defaultVoidstrikeAttackGeometryFor(dt));
                    voidstrikeSplitModeByType.put(dt, defaultVoidstrikeSplitModeFor(dt));
                }
            }
            if (!migratedCarrierStrikeWidth4V1) {
                migratedCarrierStrikeWidth4V1 = true;
                for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
                    Integer curD = voidstrikeSquadronDepthByType.get(dt);
                    Integer curW = voidstrikeSquadronWidthByType.get(dt);
                    if (curD == null || curD == 3) {
                        voidstrikeSquadronDepthByType.put(dt, defaultVoidstrikeSquadronDepthFor(dt));
                    }
                    if (curW == null || curW <= 3) {
                        voidstrikeSquadronWidthByType.put(dt, defaultVoidstrikeSquadronWidthFor(dt));
                    }
                }
            }
        }
        private final Map<DoctrineDivisionType, SquadronFormation> fighterFormationByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, SquadronFormation> fighterVoidstrikeFormationByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, SquadronFormation> bomberFormationByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, SquadronFormation> bomberVoidstrikeFormationByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, Integer> voidstrikeSquadronDepthByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, Integer> voidstrikeSquadronWidthByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, DivisionSetpieceAttack> setpieceAttackByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, ShipSkillCoordinationMode> shipSkillCoordinationByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, DivisionRammingMode> rammingModeByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, DivisionRammingTargetScope> rammingTargetScopeByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, Integer> maxRammingSlotsByType = new LinkedHashMap<>();
        private final Set<OtcPlay> enabledPlays = EnumSet.of(
                OtcPlay.LINE_AND_FIRE_DISTRIBUTION,
                OtcPlay.DOUBLING,
                OtcPlay.TIME_ON_TARGET_STRIKES,
                OtcPlay.RESERVE_AND_GENERAL_CHASE);
        private final Set<AutoAssignCapability> enabledAutoCapabilities = EnumSet.allOf(AutoAssignCapability.class);
        private final Set<AutoAssignHull> enabledAutoHulls = EnumSet.allOf(AutoAssignHull.class);
        private final Map<String, Boolean> enabledAutoSchemeMatrix = new LinkedHashMap<>();
        private OtcAuthority otcAuthority = OtcAuthority.COMMAND_BY_DIRECTION;
        private FleetWideFormation fleetWideFormation = FleetWideFormation.DOCTRINAL_AUTO;
        private MultiDivisionSetpiece multiDivisionSetpiece = MultiDivisionSetpiece.AUTO_DOCTRINAL;
        private OperationalAnchor operationalAnchor = OperationalAnchor.BATTLE_LINE;
        private ObjectiveCapturePolicy objectiveCapturePolicy = ObjectiveCapturePolicy.LIGHT_FORCES_ONLY;
        private DecisiveArm decisiveArm = DecisiveArm.SURFACE_GUNFIRE;
        private EngagementDiscipline engagementDiscipline = EngagementDiscipline.LINE_COHESION;
        private boolean deployInFleetFormation = true;
        private boolean orchestratedAdvance = true;
        private int openingDurationSeconds = 45;

        public FleetDoctrineConfig() {
            for (DoctrineHullCategory cat : DoctrineHullCategory.values()) {
                minByCat.put(cat, cat.defaultMin);
                maxByCat.put(cat, cat.defaultMax);
            }
            for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
                stationSpacingByType.put(dt, getEffectiveDefaultStationSpacingUnits(dt));
                divisionIntervalByType.put(dt, getEffectiveDefaultDivisionIntervalUnits(dt));
                standoffRangeByType.put(dt, getEffectiveDefaultStandoffRangeUnits(dt));
                stationKeepingByType.put(dt, dt.defaultStationKeepingMult);
                engineTelegraphByType.put(dt, DivisionEngineTelegraph.AUTO);
                openingMissionByType.put(dt, dt.defaultOpeningMission);
                mainMissionByType.put(dt, dt.defaultMainMission);
                strikeCraftOpeningMissionByType.put(dt, defaultStrikeCraftOpeningMissionFor(dt));
                strikeCraftMainMissionByType.put(dt, defaultStrikeCraftMainMissionFor(dt));
                strikeCraftCapReserveByType.put(dt, defaultStrikeCraftCapReserveFor(dt));
                lineStationByType.put(dt, dt.defaultLineStation);
                formationByType.put(dt, dt.defaultFormation);
                voidstrikeTargetingBiasByType.put(dt, defaultVoidstrikeTargetingBiasFor(dt));
                voidstrikeFocusCarriersByType.put(dt, defaultVoidstrikeFocusCarriersFor(dt));
                voidstrikeObservanceByType.put(dt, defaultVoidstrikeObservanceFor(dt));
                voidstrikeCoordinationByType.put(dt, defaultVoidstrikeCoordinationFor(dt));
                voidstrikeAttackGeometryByType.put(dt, defaultVoidstrikeAttackGeometryFor(dt));
                voidstrikeSplitModeByType.put(dt, defaultVoidstrikeSplitModeFor(dt));
                voidstrikeGunneryModeByType.put(dt, defaultVoidstrikeGunneryModeFor(dt));
                voidstrikeAoaCorrectionByType.put(dt, defaultVoidstrikeAoaCorrectionFor(dt));
                fighterFormationByType.put(dt, defaultFighterFormationFor(dt));
                fighterVoidstrikeFormationByType.put(dt, defaultFighterVoidstrikeFormationFor(dt));
                bomberFormationByType.put(dt, defaultBomberFormationFor(dt));
                bomberVoidstrikeFormationByType.put(dt, defaultBomberVoidstrikeFormationFor(dt));
                voidstrikeSquadronDepthByType.put(dt, defaultVoidstrikeSquadronDepthFor(dt));
                voidstrikeSquadronWidthByType.put(dt, defaultVoidstrikeSquadronWidthFor(dt));
                setpieceAttackByType.put(dt, defaultSetpieceAttackFor(dt));
                shipSkillCoordinationByType.put(dt, defaultShipSkillCoordinationFor(dt));
                rammingModeByType.put(dt, defaultRammingModeFor(dt));
                rammingTargetScopeByType.put(dt, defaultRammingTargetScopeFor(dt));
                maxRammingSlotsByType.put(dt, defaultMaxRammingSlotsFor(dt));
            }
            for (AutoAssignCapability cap : AutoAssignCapability.values()) {
                for (AutoAssignHull hull : AutoAssignHull.values()) {
                    if (cap.supportsHull(hull)) {
                        enabledAutoSchemeMatrix.put(schemeKey(cap, hull), Boolean.TRUE);
                    }
                }
            }
        }

        private static String schemeKey(AutoAssignCapability cap, AutoAssignHull hull) {
            return cap.name() + "__" + hull.name();
        }

        public boolean isCapabilityEnabled(AutoAssignCapability cap) {
            return cap != null && enabledAutoCapabilities.contains(cap);
        }

        public void setCapabilityEnabled(AutoAssignCapability cap, boolean enabled) {
            if (cap == null) return;
            if (enabled) {
                enabledAutoCapabilities.add(cap);
            } else {
                enabledAutoCapabilities.remove(cap);
            }
        }

        public boolean isHullEnabled(AutoAssignHull hull) {
            return hull != null && enabledAutoHulls.contains(hull);
        }

        public void setHullEnabled(AutoAssignHull hull, boolean enabled) {
            if (hull == null) return;
            if (enabled) {
                enabledAutoHulls.add(hull);
            } else {
                enabledAutoHulls.remove(hull);
            }
        }

        public boolean isRawSchemeCellEnabled(AutoAssignCapability cap, AutoAssignHull hull) {
            if (cap == null || hull == null || !cap.supportsHull(hull)) return false;
            Boolean val = enabledAutoSchemeMatrix.get(schemeKey(cap, hull));
            return val == null || val;
        }

        public boolean isSchemeEnabled(AutoAssignCapability cap, AutoAssignHull hull) {
            if (cap == null || hull == null || !cap.supportsHull(hull)) return false;
            if (!isCapabilityEnabled(cap) || !isHullEnabled(hull)) return false;
            return isRawSchemeCellEnabled(cap, hull);
        }

        public void setSchemeEnabled(AutoAssignCapability cap, AutoAssignHull hull, boolean enabled) {
            if (cap == null || hull == null || !cap.supportsHull(hull)) return;
            enabledAutoSchemeMatrix.put(schemeKey(cap, hull), enabled);
        }

        public OtcAuthority getOtcAuthority() {
            return otcAuthority != null ? otcAuthority : OtcAuthority.COMMAND_BY_DIRECTION;
        }

        public void setOtcAuthority(OtcAuthority authority) {
            if (authority != null) {
                this.otcAuthority = authority;
            }
        }

        public FleetWideFormation getFleetWideFormation() {
            return fleetWideFormation != null ? fleetWideFormation : FleetWideFormation.DOCTRINAL_AUTO;
        }

        public void setFleetWideFormation(FleetWideFormation formation) {
            if (formation != null) {
                this.fleetWideFormation = formation;
            }
        }

        public MultiDivisionSetpiece getMultiDivisionSetpiece() {
            return multiDivisionSetpiece != null ? multiDivisionSetpiece : MultiDivisionSetpiece.AUTO_DOCTRINAL;
        }

        public void setMultiDivisionSetpiece(MultiDivisionSetpiece setpiece) {
            if (setpiece != null) {
                this.multiDivisionSetpiece = setpiece;
            }
        }

        public OperationalAnchor getOperationalAnchor() {
            return operationalAnchor != null ? operationalAnchor : OperationalAnchor.BATTLE_LINE;
        }

        public void setOperationalAnchor(OperationalAnchor anchor) {
            if (anchor != null) {
                this.operationalAnchor = anchor;
            }
        }

        public ObjectiveCapturePolicy getObjectiveCapturePolicy() {
            return objectiveCapturePolicy != null ? objectiveCapturePolicy : ObjectiveCapturePolicy.LIGHT_FORCES_ONLY;
        }

        public void setObjectiveCapturePolicy(ObjectiveCapturePolicy policy) {
            if (policy != null) {
                this.objectiveCapturePolicy = policy;
            }
        }

        public DecisiveArm getDecisiveArm() {
            return decisiveArm != null ? decisiveArm : DecisiveArm.SURFACE_GUNFIRE;
        }

        public void setDecisiveArm(DecisiveArm arm) {
            if (arm != null) {
                this.decisiveArm = arm;
            }
        }

        public EngagementDiscipline getEngagementDiscipline() {
            return engagementDiscipline != null ? engagementDiscipline : EngagementDiscipline.LINE_COHESION;
        }

        public void setEngagementDiscipline(EngagementDiscipline discipline) {
            if (discipline != null) {
                this.engagementDiscipline = discipline;
            }
        }

        public void applyOperationalTemplate(OperationalTemplate template) {
            if (template == null) return;
            this.operationalAnchor = template.defaultAnchor;
            this.objectiveCapturePolicy = template.defaultObjectivePolicy;
            this.decisiveArm = template.defaultDecisiveArm;
            this.engagementDiscipline = template.defaultDiscipline;
        }

        /**
         * Returns the {@link OperationalTemplate} whose 4 defaults exactly match the current operational options,
         * or {@code null} when the player has customized individual sub-options away from all three presets.
         */
        public OperationalTemplate resolveMatchingOperationalTemplate() {
            OperationalAnchor a = getOperationalAnchor();
            ObjectiveCapturePolicy o = getObjectiveCapturePolicy();
            DecisiveArm d = getDecisiveArm();
            EngagementDiscipline e = getEngagementDiscipline();
            for (OperationalTemplate t : OperationalTemplate.values()) {
                if (t.defaultAnchor == a
                        && t.defaultObjectivePolicy == o
                        && t.defaultDecisiveArm == d
                        && t.defaultDiscipline == e) {
                    return t;
                }
            }
            return null;
        }

        private Boolean speedGovernorOverride = null;

        public boolean isSpeedGovernorEnabled() {
            return speedGovernorOverride != null
                    ? speedGovernorOverride.booleanValue()
                    : CombatPhysicsProfile.isSpeedGoverningEnvironment();
        }

        public Boolean getSpeedGovernorOverride() {
            return speedGovernorOverride;
        }

        public void setSpeedGovernorOverride(Boolean override) {
            this.speedGovernorOverride = override;
        }

        public boolean isDeployInFleetFormation() {
            return deployInFleetFormation && getFleetWideFormation() != FleetWideFormation.NONE;
        }

        public void setDeployInFleetFormation(boolean deployInFleetFormation) {
            this.deployInFleetFormation = deployInFleetFormation;
        }

        public boolean isOrchestratedAdvance() {
            return orchestratedAdvance;
        }

        public void setOrchestratedAdvance(boolean orchestratedAdvance) {
            this.orchestratedAdvance = orchestratedAdvance;
        }

        public static DivisionSetpieceAttack defaultSetpieceAttackFor(DoctrineDivisionType dt) {
            if (dt == null || dt.isCarrierType() || dt == DoctrineDivisionType.SERVICE_GROUP) {
                return DivisionSetpieceAttack.NONE;
            }
            switch (dt) {
                case BATTLECRUISER:
                case STRIKE_CRUISER:
                case STRIKE_DESTROYER:
                    return DivisionSetpieceAttack.LANCE_CHARGE;
                case FRIGATE:
                case STRIKE_FRIGATE:
                    return DivisionSetpieceAttack.HORNS_OF_THE_BULL;
                case PHASE:
                case PHASE_CRUISER:
                    return DivisionSetpieceAttack.ECHELON_STRIKE;
                case MISSILE_FRIGATE:
                case MISSILE_DESTROYER:
                case MISSILE_CRUISER:
                case MISSILE_BATTLESHIP:
                case MISSILE_PHASE:
                    return DivisionSetpieceAttack.WALL_SALVO_PASS;
                default:
                    return DivisionSetpieceAttack.AUTO_TACTICAL;
            }
        }

        public DivisionSetpieceAttack getSetpieceAttack(DoctrineDivisionType dt) {
            if (dt == null) return DivisionSetpieceAttack.AUTO_TACTICAL;
            DivisionSetpieceAttack sp = setpieceAttackByType.get(dt);
            return sp != null ? sp : defaultSetpieceAttackFor(dt);
        }

        public void setSetpieceAttack(DoctrineDivisionType dt, DivisionSetpieceAttack setpiece) {
            if (dt == null || setpiece == null) return;
            setpieceAttackByType.put(dt, setpiece);
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

        public DivisionFormation getFormation(DoctrineDivisionType dt) {
            if (dt == null) return DivisionFormation.LINE_ABREAST;
            DivisionFormation f = formationByType.get(dt);
            return f != null ? f : dt.defaultFormation;
        }

        public void setFormation(DoctrineDivisionType dt, DivisionFormation formation) {
            if (dt == null || formation == null) return;
            formationByType.put(dt, formation);
        }

        public DivisionMovementStickiness getMovementStickiness(DoctrineDivisionType dt) {
            if (dt == null) return DivisionMovementStickiness.BALANCED;
            return DivisionMovementStickiness.fromStationKeepingMult(getStationKeepingMult(dt));
        }

        public void setMovementStickiness(DoctrineDivisionType dt, DivisionMovementStickiness stickiness) {
            if (dt == null || stickiness == null) return;
            setStationKeepingMult(dt, stickiness.stationKeepingMult);
        }

        public DivisionEngineTelegraph getEngineTelegraph(DoctrineDivisionType dt) {
            if (dt == null) return DivisionEngineTelegraph.AUTO;
            DivisionEngineTelegraph t = engineTelegraphByType.get(dt);
            return t != null ? t : DivisionEngineTelegraph.AUTO;
        }

        public void setEngineTelegraph(DoctrineDivisionType dt, DivisionEngineTelegraph telegraph) {
            if (dt == null || telegraph == null) return;
            engineTelegraphByType.put(dt, telegraph);
        }

        public static StrikeCraftMissionType defaultStrikeCraftOpeningMissionFor(DoctrineDivisionType dt) {
            if (dt == DoctrineDivisionType.FLEET_CARRIER
                    || dt == DoctrineDivisionType.LIGHT_CARRIER
                    || dt == DoctrineDivisionType.CARRIER_GROUP) {
                return StrikeCraftMissionType.ARMED_RECONNAISSANCE;
            }
            if (dt == DoctrineDivisionType.ESCORT_CARRIER
                    || dt == DoctrineDivisionType.BATTLECARRIER) {
                return StrikeCraftMissionType.COMBAT_SPACE_PATROL;
            }
            return StrikeCraftMissionType.MATCH_DIVISION;
        }

        public static StrikeCraftMissionType defaultStrikeCraftMainMissionFor(DoctrineDivisionType dt) {
            if (dt == DoctrineDivisionType.FLEET_CARRIER
                    || dt == DoctrineDivisionType.BATTLECARRIER) {
                return StrikeCraftMissionType.ALPHA_STRIKE;
            }
            if (dt == DoctrineDivisionType.LIGHT_CARRIER
                    || dt == DoctrineDivisionType.CARRIER_GROUP) {
                return StrikeCraftMissionType.DECKLOAD_STRIKE;
            }
            if (dt == DoctrineDivisionType.ESCORT_CARRIER) {
                return StrikeCraftMissionType.CYCLIC_STRIKES;
            }
            return StrikeCraftMissionType.MATCH_DIVISION;
        }

        public static VoidstrikeTargetingBias defaultVoidstrikeTargetingBiasFor(DoctrineDivisionType dt) {
            if (dt == DoctrineDivisionType.FLEET_CARRIER
                    || dt == DoctrineDivisionType.BATTLECARRIER
                    || dt == DoctrineDivisionType.LIGHT_CARRIER
                    || dt == DoctrineDivisionType.CARRIER_GROUP) {
                return VoidstrikeTargetingBias.CRUISERS_AND_CAPITALS;
            }
            return VoidstrikeTargetingBias.FREE_SELECTION;
        }

        public static boolean defaultVoidstrikeFocusCarriersFor(DoctrineDivisionType dt) {
            return false;
        }

        public static VoidstrikeTargetObservance defaultVoidstrikeObservanceFor(DoctrineDivisionType dt) {
            if (dt == DoctrineDivisionType.FLEET_CARRIER
                    || dt == DoctrineDivisionType.BATTLECARRIER
                    || dt == DoctrineDivisionType.LIGHT_CARRIER
                    || dt == DoctrineDivisionType.CARRIER_GROUP) {
                return VoidstrikeTargetObservance.HIGH;
            }
            if (dt == DoctrineDivisionType.ESCORT_CARRIER) {
                return VoidstrikeTargetObservance.MEDIUM;
            }
            return VoidstrikeTargetObservance.MEDIUM;
        }

        public static VoidstrikeCoordinationMode defaultVoidstrikeCoordinationFor(DoctrineDivisionType dt) {
            return VoidstrikeCoordinationMode.COORDINATED;
        }

        public static VoidstrikeAttackGeometry defaultVoidstrikeAttackGeometryFor(DoctrineDivisionType dt) {
            return VoidstrikeAttackGeometry.DIRECT_ASSAULT;
        }

        public static VoidstrikeSplitMode defaultVoidstrikeSplitModeFor(DoctrineDivisionType dt) {
            return VoidstrikeSplitMode.UNIFIED;
        }

        public static VoidstrikeGunneryMode defaultVoidstrikeGunneryModeFor(DoctrineDivisionType dt) {
            if (dt == DoctrineDivisionType.FLEET_CARRIER
                    || dt == DoctrineDivisionType.BATTLECARRIER
                    || dt == DoctrineDivisionType.CARRIER_GROUP) {
                return VoidstrikeGunneryMode.AREA_SATURATION;
            }
            return VoidstrikeGunneryMode.PRECISION;
        }

        public static VoidstrikeAoaCorrection defaultVoidstrikeAoaCorrectionFor(DoctrineDivisionType dt) {
            if (dt == DoctrineDivisionType.FLEET_CARRIER
                    || dt == DoctrineDivisionType.BATTLECARRIER
                    || dt == DoctrineDivisionType.LIGHT_CARRIER
                    || dt == DoctrineDivisionType.ESCORT_CARRIER
                    || dt == DoctrineDivisionType.CARRIER_GROUP) {
                return VoidstrikeAoaCorrection.STRONG;
            }
            return VoidstrikeAoaCorrection.LOW;
        }

        public static ShipSkillCoordinationMode defaultShipSkillCoordinationFor(DoctrineDivisionType dt) {
            return ShipSkillCoordinationMode.SYNCHRONIZED;
        }

        public static DivisionRammingMode defaultRammingModeFor(DoctrineDivisionType dt) {
            return DivisionRammingMode.OFF;
        }

        public static DivisionRammingTargetScope defaultRammingTargetScopeFor(DoctrineDivisionType dt) {
            return DivisionRammingTargetScope.SINGLE_SHIP;
        }

        public static int defaultMaxRammingSlotsFor(DoctrineDivisionType dt) {
            return 1;
        }

        public static SquadronFormation defaultFighterFormationFor(DoctrineDivisionType dt) {
            if (dt == DoctrineDivisionType.ESCORT_CARRIER || dt == DoctrineDivisionType.CARRIER_GROUP) {
                return SquadronFormation.SEMICIRCLE;
            }
            return SquadronFormation.WEDGE;
        }

        public static SquadronFormation defaultFighterVoidstrikeFormationFor(DoctrineDivisionType dt) {
            if (dt == DoctrineDivisionType.FLEET_CARRIER) {
                return SquadronFormation.WEDGE;
            }
            if (dt == DoctrineDivisionType.LIGHT_CARRIER) {
                return SquadronFormation.LINE_AHEAD;
            }
            return SquadronFormation.WEDGE;
        }

        public static SquadronFormation defaultBomberFormationFor(DoctrineDivisionType dt) {
            return SquadronFormation.LINE_ABREAST;
        }

        public static SquadronFormation defaultBomberVoidstrikeFormationFor(DoctrineDivisionType dt) {
            if (dt == DoctrineDivisionType.FLEET_CARRIER) {
                return SquadronFormation.WEDGE;
            }
            return SquadronFormation.LINE_ABREAST;
        }

        public static int defaultVoidstrikeSquadronDepthFor(DoctrineDivisionType dt) {
            return 2;
        }

        public static int defaultVoidstrikeSquadronWidthFor(DoctrineDivisionType dt) {
            return 4;
        }

        public static StrikeCraftCapReserve defaultStrikeCraftCapReserveFor(DoctrineDivisionType dt) {
            return StrikeCraftCapReserve.NONE;
        }

        public StrikeCraftCapReserve getStrikeCraftCapReserve(DoctrineDivisionType dt) {
            if (dt == null) return StrikeCraftCapReserve.NONE;
            if (strikeCraftCapReserveByType == null) {
                strikeCraftCapReserveByType = new LinkedHashMap<>();
            }
            StrikeCraftCapReserve v = strikeCraftCapReserveByType.get(dt);
            return v != null ? v : defaultStrikeCraftCapReserveFor(dt);
        }

        public void setStrikeCraftCapReserve(DoctrineDivisionType dt, StrikeCraftCapReserve reserve) {
            if (dt == null || reserve == null) return;
            if (strikeCraftCapReserveByType == null) {
                strikeCraftCapReserveByType = new LinkedHashMap<>();
            }
            strikeCraftCapReserveByType.put(dt, reserve);
        }

        public VoidstrikeTargetingBias getVoidstrikeTargetingBias(DoctrineDivisionType dt) {
            if (dt == null) return VoidstrikeTargetingBias.FREE_SELECTION;
            VoidstrikeTargetingBias v = voidstrikeTargetingBiasByType.get(dt);
            return v != null ? v : defaultVoidstrikeTargetingBiasFor(dt);
        }

        public void setVoidstrikeTargetingBias(DoctrineDivisionType dt, VoidstrikeTargetingBias bias) {
            if (dt == null || bias == null) return;
            voidstrikeTargetingBiasByType.put(dt, bias);
        }

        public boolean isVoidstrikeFocusCarriers(DoctrineDivisionType dt) {
            if (dt == null) return false;
            Boolean v = voidstrikeFocusCarriersByType.get(dt);
            return v != null ? v.booleanValue() : defaultVoidstrikeFocusCarriersFor(dt);
        }

        public void setVoidstrikeFocusCarriers(DoctrineDivisionType dt, boolean focusCarriers) {
            if (dt == null) return;
            voidstrikeFocusCarriersByType.put(dt, Boolean.valueOf(focusCarriers));
        }

        public VoidstrikeTargetObservance getVoidstrikeObservance(DoctrineDivisionType dt) {
            if (dt == null) return VoidstrikeTargetObservance.MEDIUM;
            VoidstrikeTargetObservance v = voidstrikeObservanceByType.get(dt);
            return v != null ? v : defaultVoidstrikeObservanceFor(dt);
        }

        public void setVoidstrikeObservance(DoctrineDivisionType dt, VoidstrikeTargetObservance obs) {
            if (dt == null || obs == null) return;
            voidstrikeObservanceByType.put(dt, obs);
        }

        public VoidstrikeCoordinationMode getVoidstrikeCoordination(DoctrineDivisionType dt) {
            if (dt == null) return VoidstrikeCoordinationMode.COORDINATED;
            VoidstrikeCoordinationMode v = voidstrikeCoordinationByType.get(dt);
            return v != null ? v : defaultVoidstrikeCoordinationFor(dt);
        }

        public void setVoidstrikeCoordination(DoctrineDivisionType dt, VoidstrikeCoordinationMode mode) {
            if (dt == null || mode == null) return;
            voidstrikeCoordinationByType.put(dt, mode);
        }

        public VoidstrikeAttackGeometry getVoidstrikeAttackGeometry(DoctrineDivisionType dt) {
            ensureUnifiedVoidstrikeDefaultsMigrated();
            if (dt == null) return VoidstrikeAttackGeometry.DIRECT_ASSAULT;
            VoidstrikeAttackGeometry v = voidstrikeAttackGeometryByType.get(dt);
            return v != null ? v : defaultVoidstrikeAttackGeometryFor(dt);
        }

        public void setVoidstrikeAttackGeometry(DoctrineDivisionType dt, VoidstrikeAttackGeometry geom) {
            ensureUnifiedVoidstrikeDefaultsMigrated();
            if (dt == null || geom == null) return;
            voidstrikeAttackGeometryByType.put(dt, geom);
        }

        public VoidstrikeSplitMode getVoidstrikeSplitMode(DoctrineDivisionType dt) {
            ensureUnifiedVoidstrikeDefaultsMigrated();
            if (dt == null) return VoidstrikeSplitMode.UNIFIED;
            VoidstrikeSplitMode v = voidstrikeSplitModeByType.get(dt);
            return v != null ? v : defaultVoidstrikeSplitModeFor(dt);
        }

        public void setVoidstrikeSplitMode(DoctrineDivisionType dt, VoidstrikeSplitMode splitMode) {
            ensureUnifiedVoidstrikeDefaultsMigrated();
            if (dt == null || splitMode == null) return;
            voidstrikeSplitModeByType.put(dt, splitMode);
        }

        public VoidstrikeGunneryMode getVoidstrikeGunneryMode(DoctrineDivisionType dt) {
            if (dt == null) return VoidstrikeGunneryMode.PRECISION;
            VoidstrikeGunneryMode v = voidstrikeGunneryModeByType.get(dt);
            return v != null ? v : defaultVoidstrikeGunneryModeFor(dt);
        }

        public void setVoidstrikeGunneryMode(DoctrineDivisionType dt, VoidstrikeGunneryMode mode) {
            if (dt == null || mode == null) return;
            voidstrikeGunneryModeByType.put(dt, mode);
        }

        public VoidstrikeAoaCorrection getVoidstrikeAoaCorrection(DoctrineDivisionType dt) {
            if (dt == null) return VoidstrikeAoaCorrection.STRONG;
            VoidstrikeAoaCorrection v = voidstrikeAoaCorrectionByType.get(dt);
            return v != null ? v : defaultVoidstrikeAoaCorrectionFor(dt);
        }

        public void setVoidstrikeAoaCorrection(DoctrineDivisionType dt, VoidstrikeAoaCorrection aoa) {
            if (dt == null || aoa == null) return;
            voidstrikeAoaCorrectionByType.put(dt, aoa);
        }

        public ShipSkillCoordinationMode getShipSkillCoordination(DoctrineDivisionType dt) {
            if (dt == null) return ShipSkillCoordinationMode.SYNCHRONIZED;
            ShipSkillCoordinationMode v = shipSkillCoordinationByType.get(dt);
            return v != null ? v : defaultShipSkillCoordinationFor(dt);
        }

        public void setShipSkillCoordination(DoctrineDivisionType dt, ShipSkillCoordinationMode mode) {
            if (dt == null || mode == null) return;
            shipSkillCoordinationByType.put(dt, mode);
        }

        public DivisionRammingMode getRammingMode(DoctrineDivisionType dt) {
            if (dt == null) return DivisionRammingMode.OFF;
            DivisionRammingMode v = rammingModeByType.get(dt);
            return v != null ? v : defaultRammingModeFor(dt);
        }

        public void setRammingMode(DoctrineDivisionType dt, DivisionRammingMode mode) {
            if (dt == null || mode == null) return;
            rammingModeByType.put(dt, mode);
        }

        public DivisionRammingTargetScope getRammingTargetScope(DoctrineDivisionType dt) {
            if (dt == null) return DivisionRammingTargetScope.SINGLE_SHIP;
            DivisionRammingTargetScope v = rammingTargetScopeByType.get(dt);
            return v != null ? v : defaultRammingTargetScopeFor(dt);
        }

        public void setRammingTargetScope(DoctrineDivisionType dt, DivisionRammingTargetScope scope) {
            if (dt == null || scope == null) return;
            rammingTargetScopeByType.put(dt, scope);
        }

        public int getMaxRammingSlots(DoctrineDivisionType dt) {
            if (dt == null) return 1;
            Integer v = maxRammingSlotsByType.get(dt);
            int raw = v != null ? v : defaultMaxRammingSlotsFor(dt);
            return Math.max(MIN_RAMMING_ATTACK_SLOTS, Math.min(MAX_RAMMING_ATTACK_SLOTS, raw));
        }

        public void setMaxRammingSlots(DoctrineDivisionType dt, int slots) {
            if (dt == null) return;
            int clamped = Math.max(MIN_RAMMING_ATTACK_SLOTS, Math.min(MAX_RAMMING_ATTACK_SLOTS, slots));
            maxRammingSlotsByType.put(dt, clamped);
        }

        public SquadronFormation getFighterFormation(DoctrineDivisionType dt) {
            if (dt == null) return SquadronFormation.WEDGE;
            SquadronFormation v = fighterFormationByType.get(dt);
            return v != null ? v : defaultFighterFormationFor(dt);
        }

        public void setFighterFormation(DoctrineDivisionType dt, SquadronFormation fm) {
            if (dt == null || fm == null) return;
            fighterFormationByType.put(dt, fm);
        }

        public SquadronFormation getFighterVoidstrikeFormation(DoctrineDivisionType dt) {
            if (dt == null) return SquadronFormation.WEDGE;
            SquadronFormation v = fighterVoidstrikeFormationByType.get(dt);
            return v != null ? v : defaultFighterVoidstrikeFormationFor(dt);
        }

        public void setFighterVoidstrikeFormation(DoctrineDivisionType dt, SquadronFormation fm) {
            if (dt == null || fm == null) return;
            fighterVoidstrikeFormationByType.put(dt, fm);
        }

        public SquadronFormation getBomberFormation(DoctrineDivisionType dt) {
            if (dt == null) return SquadronFormation.LINE_ABREAST;
            SquadronFormation v = bomberFormationByType.get(dt);
            return v != null ? v : defaultBomberFormationFor(dt);
        }

        public void setBomberFormation(DoctrineDivisionType dt, SquadronFormation fm) {
            if (dt == null || fm == null) return;
            bomberFormationByType.put(dt, fm);
        }

        public SquadronFormation getBomberVoidstrikeFormation(DoctrineDivisionType dt) {
            if (dt == null) return SquadronFormation.LINE_ABREAST;
            SquadronFormation v = bomberVoidstrikeFormationByType.get(dt);
            return v != null ? v : defaultBomberVoidstrikeFormationFor(dt);
        }

        public void setBomberVoidstrikeFormation(DoctrineDivisionType dt, SquadronFormation fm) {
            if (dt == null || fm == null) return;
            bomberVoidstrikeFormationByType.put(dt, fm);
        }

        public int getVoidstrikeSquadronDepth(DoctrineDivisionType dt) {
            ensureUnifiedVoidstrikeDefaultsMigrated();
            if (dt == null) return 2;
            Integer v = voidstrikeSquadronDepthByType.get(dt);
            int raw = v != null ? v : defaultVoidstrikeSquadronDepthFor(dt);
            return Math.max(MIN_VOIDSTRIKE_SQUADRON_DEPTH, Math.min(MAX_VOIDSTRIKE_SQUADRON_DEPTH, raw));
        }

        public void setVoidstrikeSquadronDepth(DoctrineDivisionType dt, int depth) {
            ensureUnifiedVoidstrikeDefaultsMigrated();
            if (dt == null) return;
            int clamped = Math.max(MIN_VOIDSTRIKE_SQUADRON_DEPTH, Math.min(MAX_VOIDSTRIKE_SQUADRON_DEPTH, depth));
            voidstrikeSquadronDepthByType.put(dt, clamped);
        }

        public int getVoidstrikeSquadronWidth(DoctrineDivisionType dt) {
            ensureUnifiedVoidstrikeDefaultsMigrated();
            if (dt == null) return 4;
            Integer v = voidstrikeSquadronWidthByType.get(dt);
            int raw = v != null ? v : defaultVoidstrikeSquadronWidthFor(dt);
            return Math.max(MIN_VOIDSTRIKE_SQUADRON_WIDTH, Math.min(MAX_VOIDSTRIKE_SQUADRON_WIDTH, raw));
        }

        public void setVoidstrikeSquadronWidth(DoctrineDivisionType dt, int width) {
            ensureUnifiedVoidstrikeDefaultsMigrated();
            if (dt == null) return;
            int clamped = Math.max(MIN_VOIDSTRIKE_SQUADRON_WIDTH, Math.min(MAX_VOIDSTRIKE_SQUADRON_WIDTH, width));
            voidstrikeSquadronWidthByType.put(dt, clamped);
        }

        public int getOpeningDurationSeconds() {
            return Math.max(MIN_OPENING_DURATION_SEC, Math.min(MAX_OPENING_DURATION_SEC, openingDurationSeconds));
        }

        public void setOpeningDurationSeconds(int seconds) {
            this.openingDurationSeconds = Math.max(MIN_OPENING_DURATION_SEC, Math.min(MAX_OPENING_DURATION_SEC, seconds));
        }

        public int getStationSpacing(DoctrineDivisionType dt) {
            if (dt == null) return getEffectiveDefaultStationSpacingUnits(null);
            Integer v = stationSpacingByType.get(dt);
            int raw = v != null ? v : getEffectiveDefaultStationSpacingUnits(dt);
            return Math.max(MIN_STATION_SPACING_UNITS, Math.min(getEffectiveMaxStationSpacingUnits(), raw));
        }

        public void setStationSpacing(DoctrineDivisionType dt, int units) {
            if (dt == null) return;
            int clamped = Math.max(MIN_STATION_SPACING_UNITS, Math.min(getEffectiveMaxStationSpacingUnits(), units));
            stationSpacingByType.put(dt, clamped);
        }

        public int getDivisionInterval(DoctrineDivisionType dt) {
            if (dt == null) return getEffectiveDefaultDivisionIntervalUnits(null);
            Integer v = divisionIntervalByType.get(dt);
            int raw = v != null ? v : getEffectiveDefaultDivisionIntervalUnits(dt);
            return Math.max(MIN_DIVISION_INTERVAL_UNITS, Math.min(getEffectiveMaxDivisionIntervalUnits(), raw));
        }

        public void setDivisionInterval(DoctrineDivisionType dt, int units) {
            if (dt == null) return;
            int clamped = Math.max(MIN_DIVISION_INTERVAL_UNITS, Math.min(getEffectiveMaxDivisionIntervalUnits(), units));
            divisionIntervalByType.put(dt, clamped);
        }

        public int getStandoffRange(DoctrineDivisionType dt) {
            if (dt == null) return getEffectiveDefaultStandoffRangeUnits(null);
            Integer v = standoffRangeByType.get(dt);
            int raw = v != null ? v : getEffectiveDefaultStandoffRangeUnits(dt);
            return Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(getEffectiveMaxStandoffRangeUnits(), raw));
        }

        public void setStandoffRange(DoctrineDivisionType dt, int units) {
            if (dt == null) return;
            int clamped = Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(getEffectiveMaxStandoffRangeUnits(), units));
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
            if (m != null && m.isCarrierMission) {
                return DivisionMissionType.CARRIER_STANDOFF;
            }
            return m != null ? m : dt.defaultOpeningMission;
        }

        public void setOpeningMission(DoctrineDivisionType dt, DivisionMissionType mission) {
            if (dt == null || mission == null) return;
            if (mission.isCarrierMission) {
                StrikeCraftMissionType sc = StrikeCraftMissionType.fromLegacyCarrierMission(mission);
                if (sc != null) {
                    setStrikeCraftOpeningMission(dt, sc);
                }
                openingMissionByType.put(dt, DivisionMissionType.CARRIER_STANDOFF);
            } else {
                openingMissionByType.put(dt, mission);
            }
        }

        public DivisionMissionType getMainMission(DoctrineDivisionType dt) {
            if (dt == null) return DivisionMissionType.CLOSE_ACTION;
            DivisionMissionType m = mainMissionByType.get(dt);
            if (m != null && m.isCarrierMission) {
                return DivisionMissionType.CARRIER_STANDOFF;
            }
            return m != null ? m : dt.defaultMainMission;
        }

        public void setMainMission(DoctrineDivisionType dt, DivisionMissionType mission) {
            if (dt == null || mission == null) return;
            if (mission.isCarrierMission) {
                StrikeCraftMissionType sc = StrikeCraftMissionType.fromLegacyCarrierMission(mission);
                if (sc != null) {
                    setStrikeCraftMainMission(dt, sc);
                }
                mainMissionByType.put(dt, DivisionMissionType.CARRIER_STANDOFF);
            } else {
                mainMissionByType.put(dt, mission);
            }
        }

        public StrikeCraftMissionType getStrikeCraftOpeningMission(DoctrineDivisionType dt) {
            if (dt == null) return StrikeCraftMissionType.MATCH_DIVISION;
            if (strikeCraftOpeningMissionByType != null) {
                StrikeCraftMissionType sc = strikeCraftOpeningMissionByType.get(dt);
                if (sc != null) return sc;
            }
            DivisionMissionType legacy = openingMissionByType.get(dt);
            StrikeCraftMissionType mapped = StrikeCraftMissionType.fromLegacyCarrierMission(legacy);
            return mapped != null ? mapped : defaultStrikeCraftOpeningMissionFor(dt);
        }

        public void setStrikeCraftOpeningMission(DoctrineDivisionType dt, StrikeCraftMissionType mission) {
            if (dt == null || mission == null) return;
            if (strikeCraftOpeningMissionByType == null) {
                strikeCraftOpeningMissionByType = new LinkedHashMap<>();
            }
            strikeCraftOpeningMissionByType.put(dt, mission);
        }

        public StrikeCraftMissionType getStrikeCraftMainMission(DoctrineDivisionType dt) {
            if (dt == null) return StrikeCraftMissionType.MATCH_DIVISION;
            if (strikeCraftMainMissionByType != null) {
                StrikeCraftMissionType sc = strikeCraftMainMissionByType.get(dt);
                if (sc != null) return sc;
            }
            DivisionMissionType legacy = mainMissionByType.get(dt);
            StrikeCraftMissionType mapped = StrikeCraftMissionType.fromLegacyCarrierMission(legacy);
            return mapped != null ? mapped : defaultStrikeCraftMainMissionFor(dt);
        }

        public void setStrikeCraftMainMission(DoctrineDivisionType dt, StrikeCraftMissionType mission) {
            if (dt == null || mission == null) return;
            if (strikeCraftMainMissionByType == null) {
                strikeCraftMainMissionByType = new LinkedHashMap<>();
            }
            strikeCraftMainMissionByType.put(dt, mission);
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

        public FleetDoctrineConfig copy() {
            return deserializeDoctrineConfig(serializeDoctrineConfig(this));
        }
    }

    private static Object cachedDoctrineRawRef = null;
    private static FleetDoctrineConfig cachedDoctrineConfig = null;
    private static Object cachedSpecsRawRef = null;
    private static int cachedSpecsFleetSig = 0;
    private static List<PermanentDivisionSpec> cachedSpecsList = null;

    @SuppressWarnings("unchecked")
    public static FleetDoctrineConfig getFleetDoctrine() {
        MissionScenarioBundle missionBundle = resolveActiveMissionScenario();
        if (missionBundle != null && missionBundle.playerDoctrine != null) {
            return missionBundle.playerDoctrine;
        }
        SectorAPI sector = Global.getSector();
        if (sector == null) return new FleetDoctrineConfig();
        Map<String, Object> pdata = sector.getPersistentData();
        Object raw = pdata != null ? pdata.get(PERSISTENT_DOCTRINE_KEY) : null;
        if (raw instanceof Map) {
            if (raw == cachedDoctrineRawRef && cachedDoctrineConfig != null) {
                return cachedDoctrineConfig;
            }
            FleetDoctrineConfig cfg = deserializeDoctrineConfig((Map<String, Object>) raw);
            cachedDoctrineRawRef = raw;
            cachedDoctrineConfig = cfg;
            return cfg;
        }
        return new FleetDoctrineConfig();
    }

    public static FleetDoctrineConfig deserializeDoctrineConfig(Map<String, Object> map) {
        FleetDoctrineConfig cfg = new FleetDoctrineConfig();
        if (map != null) {
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
            Object fwFmObj = map.get("bg_fleet_formation");
            if (fwFmObj instanceof String) {
                cfg.setFleetWideFormation(FleetWideFormation.fromId((String) fwFmObj, FleetWideFormation.DOCTRINAL_AUTO));
            }
            Object mdSpObj = map.get("bg_multi_setpiece");
            if (mdSpObj instanceof String) {
                cfg.setMultiDivisionSetpiece(MultiDivisionSetpiece.fromId((String) mdSpObj, MultiDivisionSetpiece.AUTO_DOCTRINAL));
            }
            Object opTplObj = map.get("bg_op_template");
            if (opTplObj instanceof String) {
                OperationalTemplate tpl = OperationalTemplate.fromId((String) opTplObj, null);
                if (tpl != null) {
                    cfg.applyOperationalTemplate(tpl);
                }
            }
            Object opAncObj = map.get("bg_op_anchor");
            if (opAncObj instanceof String) {
                cfg.setOperationalAnchor(OperationalAnchor.fromId((String) opAncObj, cfg.getOperationalAnchor()));
            }
            Object objPolObj = map.get("bg_obj_policy");
            if (objPolObj instanceof String) {
                cfg.setObjectiveCapturePolicy(ObjectiveCapturePolicy.fromId((String) objPolObj, cfg.getObjectiveCapturePolicy()));
            }
            Object decArmObj = map.get("bg_decisive_arm");
            if (decArmObj instanceof String) {
                cfg.setDecisiveArm(DecisiveArm.fromId((String) decArmObj, cfg.getDecisiveArm()));
            }
            Object engDiscObj = map.get("bg_eng_discipline");
            if (engDiscObj instanceof String) {
                cfg.setEngagementDiscipline(EngagementDiscipline.fromId((String) engDiscObj, cfg.getEngagementDiscipline()));
            }
            Object depFmObj = map.get("bg_deploy_in_formation");
            if (depFmObj instanceof Boolean) {
                cfg.setDeployInFleetFormation((Boolean) depFmObj);
            }
            Object orchAdvObj = map.get("bg_orchestrated_advance");
            if (orchAdvObj instanceof Boolean) {
                cfg.setOrchestratedAdvance((Boolean) orchAdvObj);
            }
            Object spdGovObj = map.get("bg_speed_governor_override");
            if (spdGovObj instanceof Boolean) {
                cfg.setSpeedGovernorOverride((Boolean) spdGovObj);
            }
            for (OtcPlay play : OtcPlay.values()) {
                Object playObj = getWithLegacyKey(map, "bg_play_", play.name(), play.legacyName());
                if (playObj instanceof Boolean) {
                    cfg.setPlayEnabled(play, (Boolean) playObj);
                }
            }
            for (AutoAssignCapability cap : AutoAssignCapability.values()) {
                Object capObj = map.get("aa_cap_" + cap.name());
                if (capObj instanceof Boolean) {
                    cfg.setCapabilityEnabled(cap, (Boolean) capObj);
                }
                for (AutoAssignHull hull : AutoAssignHull.values()) {
                    if (!cap.supportsHull(hull)) continue;
                    Object cellObj = map.get("aa_scheme_" + cap.name() + "__" + hull.name());
                    if (cellObj instanceof Boolean) {
                        cfg.setSchemeEnabled(cap, hull, (Boolean) cellObj);
                    }
                }
            }
            for (AutoAssignHull hull : AutoAssignHull.values()) {
                Object hullObj = map.get("aa_hull_" + hull.name());
                if (hullObj instanceof Boolean) {
                    cfg.setHullEnabled(hull, (Boolean) hullObj);
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
                Object fmObj = getWithLegacyKey(map, "formation_", dt.name(), dt.legacyName());
                if (spObj instanceof Number) {
                    int spVal = ((Number) spObj).intValue();
                    if (CombatPhysicsProfile.isGlobalScaledEnvironment() && spVal == dt.defaultStationSpacingUnits) {
                        spVal = getEffectiveDefaultStationSpacingUnits(dt);
                    }
                    cfg.setStationSpacing(dt, spVal);
                }
                if (ispObj instanceof Number) {
                    int ispVal = ((Number) ispObj).intValue();
                    if (CombatPhysicsProfile.isGlobalScaledEnvironment() && ispVal == dt.defaultDivisionIntervalUnits) {
                        ispVal = getEffectiveDefaultDivisionIntervalUnits(dt);
                    }
                    cfg.setDivisionInterval(dt, ispVal);
                }
                if (stObj instanceof Number) {
                    int stVal = ((Number) stObj).intValue();
                    if (CombatPhysicsProfile.isGlobalScaledEnvironment() && stVal == dt.defaultStandoffRangeUnits) {
                        stVal = getEffectiveDefaultStandoffRangeUnits(dt);
                    }
                    cfg.setStandoffRange(dt, stVal);
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
                if (fmObj instanceof String) {
                    cfg.setFormation(dt, DivisionFormation.fromId((String) fmObj, dt.defaultFormation));
                }
                Object scCapResObj = map.get("sc_cap_res_" + dt.name());
                if (scCapResObj instanceof String) {
                    cfg.setStrikeCraftCapReserve(dt, StrikeCraftCapReserve.fromId(
                            (String) scCapResObj, FleetDoctrineConfig.defaultStrikeCraftCapReserveFor(dt)));
                }
                Object vsBiasObj = map.get("vs_bias_" + dt.name());
                Object engTelObj = map.get("eng_telegraph_" + dt.name());
                if (engTelObj instanceof String) {
                    cfg.setEngineTelegraph(dt, DivisionEngineTelegraph.fromId((String) engTelObj, DivisionEngineTelegraph.AUTO));
                }
                Object vsCvFocusObj = map.get("vs_cv_focus_" + dt.name());
                if (vsBiasObj instanceof String) {
                    cfg.setVoidstrikeTargetingBias(dt, VoidstrikeTargetingBias.fromId(
                            (String) vsBiasObj, FleetDoctrineConfig.defaultVoidstrikeTargetingBiasFor(dt)));
                }
                if (vsCvFocusObj instanceof Boolean) {
                    cfg.setVoidstrikeFocusCarriers(dt, ((Boolean) vsCvFocusObj).booleanValue());
                }
                Object vsObsObj = map.get("vs_obs_" + dt.name());
                Object vsCoordObj = map.get("vs_coord_" + dt.name());
                Object vsGeomObj = map.get("vs_geom_" + dt.name());
                Object vsSplitObj = map.get("vs_split_" + dt.name());
                Object vsGunObj = map.get("vs_gun_" + dt.name());
                Object vsAoaObj = map.get("vs_aoa_" + dt.name());
                Object ftrFmObj = map.get("ftr_fm_" + dt.name());
                Object ftrVsFmObj = map.get("ftr_vs_fm_" + dt.name());
                Object bmbFmObj = map.get("bmb_fm_" + dt.name());
                Object bmbVsFmObj = map.get("bmb_vs_fm_" + dt.name());
                Object vsDepthObj = map.get("vs_depth_" + dt.name());
                Object vsWidthObj = map.get("vs_width_" + dt.name());
                if (vsObsObj instanceof String) {
                    cfg.setVoidstrikeObservance(dt, VoidstrikeTargetObservance.fromId(
                            (String) vsObsObj, FleetDoctrineConfig.defaultVoidstrikeObservanceFor(dt)));
                }
                if (vsCoordObj instanceof String) {
                    cfg.setVoidstrikeCoordination(dt, VoidstrikeCoordinationMode.fromId(
                            (String) vsCoordObj, FleetDoctrineConfig.defaultVoidstrikeCoordinationFor(dt)));
                }
                if (vsGeomObj instanceof String) {
                    cfg.setVoidstrikeAttackGeometry(dt, VoidstrikeAttackGeometry.fromId(
                            (String) vsGeomObj, FleetDoctrineConfig.defaultVoidstrikeAttackGeometryFor(dt)));
                }
                if (vsSplitObj instanceof String) {
                    cfg.setVoidstrikeSplitMode(dt, VoidstrikeSplitMode.fromId(
                            (String) vsSplitObj, FleetDoctrineConfig.defaultVoidstrikeSplitModeFor(dt)));
                }
                if (vsGunObj instanceof String) {
                    cfg.setVoidstrikeGunneryMode(dt, VoidstrikeGunneryMode.fromId(
                            (String) vsGunObj, FleetDoctrineConfig.defaultVoidstrikeGunneryModeFor(dt)));
                }
                if (vsAoaObj instanceof String) {
                    cfg.setVoidstrikeAoaCorrection(dt, VoidstrikeAoaCorrection.fromId(
                            (String) vsAoaObj, FleetDoctrineConfig.defaultVoidstrikeAoaCorrectionFor(dt)));
                }
                if (ftrFmObj instanceof String) {
                    cfg.setFighterFormation(dt, SquadronFormation.fromId(
                            (String) ftrFmObj, FleetDoctrineConfig.defaultFighterFormationFor(dt)));
                }
                if (ftrVsFmObj instanceof String) {
                    cfg.setFighterVoidstrikeFormation(dt, SquadronFormation.fromId(
                            (String) ftrVsFmObj, FleetDoctrineConfig.defaultFighterVoidstrikeFormationFor(dt)));
                }
                if (bmbFmObj instanceof String) {
                    cfg.setBomberFormation(dt, SquadronFormation.fromId(
                            (String) bmbFmObj, FleetDoctrineConfig.defaultBomberFormationFor(dt)));
                }
                if (bmbVsFmObj instanceof String) {
                    cfg.setBomberVoidstrikeFormation(dt, SquadronFormation.fromId(
                            (String) bmbVsFmObj, FleetDoctrineConfig.defaultBomberVoidstrikeFormationFor(dt)));
                }
                if (vsDepthObj instanceof Number) {
                    cfg.setVoidstrikeSquadronDepth(dt, ((Number) vsDepthObj).intValue());
                }
                if (vsWidthObj instanceof Number) {
                    cfg.setVoidstrikeSquadronWidth(dt, ((Number) vsWidthObj).intValue());
                }
                Object spAtkObj = map.get("setpiece_atk_" + dt.name());
                if (spAtkObj instanceof String) {
                    cfg.setSetpieceAttack(dt, DivisionSetpieceAttack.fromId(
                            (String) spAtkObj, FleetDoctrineConfig.defaultSetpieceAttackFor(dt)));
                }
                Object skCoordObj = map.get("skill_coord_" + dt.name());
                if (skCoordObj instanceof String) {
                    cfg.setShipSkillCoordination(dt, ShipSkillCoordinationMode.fromId(
                            (String) skCoordObj, FleetDoctrineConfig.defaultShipSkillCoordinationFor(dt)));
                }
                Object ramModeObj = map.get("ram_mode_" + dt.name());
                if (ramModeObj instanceof String) {
                    cfg.setRammingMode(dt, DivisionRammingMode.fromId(
                            (String) ramModeObj, FleetDoctrineConfig.defaultRammingModeFor(dt)));
                }
                Object ramScopeObj = map.get("ram_scope_" + dt.name());
                if (ramScopeObj instanceof String) {
                    cfg.setRammingTargetScope(dt, DivisionRammingTargetScope.fromId(
                            (String) ramScopeObj, FleetDoctrineConfig.defaultRammingTargetScopeFor(dt)));
                }
                Object ramSlotsObj = map.get("ram_slots_" + dt.name());
                if (ramSlotsObj instanceof Number) {
                    cfg.setMaxRammingSlots(dt, ((Number) ramSlotsObj).intValue());
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

    public static Map<String, Object> serializeDoctrineConfig(FleetDoctrineConfig cfg) {
        Map<String, Object> serialized = new HashMap<>();
        if (cfg == null) return serialized;
        serialized.put("opening_duration_sec", cfg.getOpeningDurationSeconds());
        serialized.put("bg_authority", cfg.getOtcAuthority().name());
        serialized.put("bg_fleet_formation", cfg.getFleetWideFormation().name());
        serialized.put("bg_multi_setpiece", cfg.getMultiDivisionSetpiece().name());
        serialized.put("bg_op_anchor", cfg.getOperationalAnchor().name());
        serialized.put("bg_obj_policy", cfg.getObjectiveCapturePolicy().name());
        serialized.put("bg_decisive_arm", cfg.getDecisiveArm().name());
        serialized.put("bg_eng_discipline", cfg.getEngagementDiscipline().name());
        serialized.put("bg_deploy_in_formation", cfg.isDeployInFleetFormation());
        serialized.put("bg_orchestrated_advance", cfg.isOrchestratedAdvance());
        if (cfg.getSpeedGovernorOverride() != null) {
            serialized.put("bg_speed_governor_override", cfg.getSpeedGovernorOverride());
        }
        for (OtcPlay play : OtcPlay.values()) {
            serialized.put("bg_play_" + play.name(), cfg.isPlayEnabled(play));
        }
        for (AutoAssignCapability cap : AutoAssignCapability.values()) {
            serialized.put("aa_cap_" + cap.name(), cfg.isCapabilityEnabled(cap));
            for (AutoAssignHull hull : AutoAssignHull.values()) {
                if (!cap.supportsHull(hull)) continue;
                serialized.put("aa_scheme_" + cap.name() + "__" + hull.name(), cfg.isRawSchemeCellEnabled(cap, hull));
            }
        }
        for (AutoAssignHull hull : AutoAssignHull.values()) {
            serialized.put("aa_hull_" + hull.name(), cfg.isHullEnabled(hull));
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
            serialized.put("formation_" + dt.name(), cfg.getFormation(dt).name());
            serialized.put("sc_cap_res_" + dt.name(), cfg.getStrikeCraftCapReserve(dt).name());
            serialized.put("vs_bias_" + dt.name(), cfg.getVoidstrikeTargetingBias(dt).name());
            serialized.put("vs_cv_focus_" + dt.name(), Boolean.valueOf(cfg.isVoidstrikeFocusCarriers(dt)));
            serialized.put("vs_obs_" + dt.name(), cfg.getVoidstrikeObservance(dt).name());
            serialized.put("vs_coord_" + dt.name(), cfg.getVoidstrikeCoordination(dt).name());
            serialized.put("vs_geom_" + dt.name(), cfg.getVoidstrikeAttackGeometry(dt).name());
            serialized.put("vs_split_" + dt.name(), cfg.getVoidstrikeSplitMode(dt).name());
            serialized.put("vs_gun_" + dt.name(), cfg.getVoidstrikeGunneryMode(dt).name());
            serialized.put("vs_aoa_" + dt.name(), cfg.getVoidstrikeAoaCorrection(dt).name());
            serialized.put("ftr_fm_" + dt.name(), cfg.getFighterFormation(dt).name());
            serialized.put("ftr_vs_fm_" + dt.name(), cfg.getFighterVoidstrikeFormation(dt).name());
            serialized.put("bmb_fm_" + dt.name(), cfg.getBomberFormation(dt).name());
            serialized.put("bmb_vs_fm_" + dt.name(), cfg.getBomberVoidstrikeFormation(dt).name());
            serialized.put("vs_depth_" + dt.name(), cfg.getVoidstrikeSquadronDepth(dt));
            serialized.put("vs_width_" + dt.name(), cfg.getVoidstrikeSquadronWidth(dt));
            serialized.put("setpiece_atk_" + dt.name(), cfg.getSetpieceAttack(dt).name());
            serialized.put("skill_coord_" + dt.name(), cfg.getShipSkillCoordination(dt).name());
            serialized.put("ram_mode_" + dt.name(), cfg.getRammingMode(dt).name());
            serialized.put("ram_scope_" + dt.name(), cfg.getRammingTargetScope(dt).name());
            serialized.put("ram_slots_" + dt.name(), cfg.getMaxRammingSlots(dt));
            serialized.put("eng_telegraph_" + dt.name(), cfg.getEngineTelegraph(dt).name());
        }
        return serialized;
    }

    public static void saveFleetDoctrine(FleetDoctrineConfig cfg) {
        MissionScenarioBundle missionBundle = resolveActiveMissionScenario();
        if (missionBundle != null && cfg != null) {
            missionBundle.playerDoctrine = cfg;
            return;
        }
        SectorAPI sector = Global.getSector();
        if (sector == null || cfg == null) return;
        Map<String, Object> serialized = serializeDoctrineConfig(cfg);
        sector.getPersistentData().put(PERSISTENT_DOCTRINE_KEY, serialized);
        cachedDoctrineRawRef = serialized;
        cachedDoctrineConfig = cfg;
        cachedSpecsRawRef = null;
    }

    /**
     * One-time migration for saves created when GRAND_LINE_ABREAST was at ordinal 0 of FleetWideFormation,
     * ensuring the player's default fleet formation starts on DOCTRINAL_AUTO ("Auto").
     */
    public static void migrateFleetWideFormationDefaultToAuto() {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPersistentData() == null) return;
        String migKey = "divisionai_migrated_fleet_formation_auto_v1";
        if (Boolean.TRUE.equals(sector.getPersistentData().get(migKey))) {
            return;
        }
        sector.getPersistentData().put(migKey, Boolean.TRUE);
        FleetDoctrineConfig cfg = getFleetDoctrine();
        if (cfg != null && cfg.getFleetWideFormation() == FleetWideFormation.GRAND_LINE_ABREAST) {
            cfg.setFleetWideFormation(FleetWideFormation.DOCTRINAL_AUTO);
            saveFleetDoctrine(cfg);
        }
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

    public static void updateDivisionTypeStrikeCraftMission(
            DoctrineDivisionType dt,
            boolean isOpening,
            StrikeCraftMissionType mission) {
        if (dt == null || mission == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        if (isOpening) {
            cfg.setStrikeCraftOpeningMission(dt, mission);
        } else {
            cfg.setStrikeCraftMainMission(dt, mission);
        }
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeLineStation(DoctrineDivisionType dt, LineStation ps) {
        if (dt == null || ps == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setLineStation(dt, ps);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeFormation(DoctrineDivisionType dt, DivisionFormation formation) {
        if (dt == null || formation == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setFormation(dt, formation);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeMovementStickiness(
            DoctrineDivisionType dt,
            DivisionMovementStickiness stickiness) {
        if (dt == null || stickiness == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setMovementStickiness(dt, stickiness);
        saveFleetDoctrine(cfg);
    }

    public static void updateSpeedGovernorOverride(Boolean override) {
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setSpeedGovernorOverride(override);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeEngineTelegraph(
            DoctrineDivisionType dt,
            DivisionEngineTelegraph telegraph) {
        if (dt == null || telegraph == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setEngineTelegraph(dt, telegraph);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeStrikeCraftCapReserve(
            DoctrineDivisionType dt,
            StrikeCraftCapReserve reserve) {
        if (dt == null || reserve == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setStrikeCraftCapReserve(dt, reserve);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeVoidstrikeTargetingBias(
            DoctrineDivisionType dt,
            VoidstrikeTargetingBias bias) {
        if (dt == null || bias == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setVoidstrikeTargetingBias(dt, bias);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeVoidstrikeFocusCarriers(
            DoctrineDivisionType dt,
            boolean focusCarriers) {
        if (dt == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setVoidstrikeFocusCarriers(dt, focusCarriers);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeVoidstrikeObservance(
            DoctrineDivisionType dt,
            VoidstrikeTargetObservance observance) {
        if (dt == null || observance == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setVoidstrikeObservance(dt, observance);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeVoidstrikeCoordination(
            DoctrineDivisionType dt,
            VoidstrikeCoordinationMode coordination) {
        if (dt == null || coordination == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setVoidstrikeCoordination(dt, coordination);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeVoidstrikeAttackGeometry(
            DoctrineDivisionType dt,
            VoidstrikeAttackGeometry geometry) {
        if (dt == null || geometry == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setVoidstrikeAttackGeometry(dt, geometry);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeVoidstrikeSplitMode(
            DoctrineDivisionType dt,
            VoidstrikeSplitMode splitMode) {
        if (dt == null || splitMode == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setVoidstrikeSplitMode(dt, splitMode);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeVoidstrikeGunneryMode(
            DoctrineDivisionType dt,
            VoidstrikeGunneryMode gunneryMode) {
        if (dt == null || gunneryMode == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setVoidstrikeGunneryMode(dt, gunneryMode);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeVoidstrikeAoaCorrection(
            DoctrineDivisionType dt,
            VoidstrikeAoaCorrection aoaCorrection) {
        if (dt == null || aoaCorrection == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setVoidstrikeAoaCorrection(dt, aoaCorrection);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeShipSkillCoordination(
            DoctrineDivisionType dt,
            ShipSkillCoordinationMode coordination) {
        if (dt == null || coordination == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setShipSkillCoordination(dt, coordination);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeRammingMode(
            DoctrineDivisionType dt,
            DivisionRammingMode mode) {
        if (dt == null || mode == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setRammingMode(dt, mode);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeRammingTargetScope(
            DoctrineDivisionType dt,
            DivisionRammingTargetScope scope) {
        if (dt == null || scope == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setRammingTargetScope(dt, scope);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeMaxRammingSlots(
            DoctrineDivisionType dt,
            int slots) {
        if (dt == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setMaxRammingSlots(dt, slots);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeFighterFormation(
            DoctrineDivisionType dt,
            SquadronFormation formation) {
        if (dt == null || formation == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setFighterFormation(dt, formation);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeFighterVoidstrikeFormation(
            DoctrineDivisionType dt,
            SquadronFormation formation) {
        if (dt == null || formation == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setFighterVoidstrikeFormation(dt, formation);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeBomberFormation(
            DoctrineDivisionType dt,
            SquadronFormation formation) {
        if (dt == null || formation == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setBomberFormation(dt, formation);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeBomberVoidstrikeFormation(
            DoctrineDivisionType dt,
            SquadronFormation formation) {
        if (dt == null || formation == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setBomberVoidstrikeFormation(dt, formation);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeVoidstrikeSquadronDepth(
            DoctrineDivisionType dt,
            int depth) {
        if (dt == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setVoidstrikeSquadronDepth(dt, depth);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeVoidstrikeSquadronWidth(
            DoctrineDivisionType dt,
            int width) {
        if (dt == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setVoidstrikeSquadronWidth(dt, width);
        saveFleetDoctrine(cfg);
    }

    public static void updateOtcAuthority(OtcAuthority authority) {
        if (authority == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setOtcAuthority(authority);
        saveFleetDoctrine(cfg);
    }

    public static void updateFleetWideFormation(FleetWideFormation formation) {
        if (formation == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setFleetWideFormation(formation);
        saveFleetDoctrine(cfg);
    }

    public static void updateMultiDivisionSetpiece(MultiDivisionSetpiece setpiece) {
        if (setpiece == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setMultiDivisionSetpiece(setpiece);
        saveFleetDoctrine(cfg);
    }

    public static void updateOperationalTemplate(OperationalTemplate template) {
        if (template == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.applyOperationalTemplate(template);
        saveFleetDoctrine(cfg);
    }

    public static void updateOperationalAnchor(OperationalAnchor anchor) {
        if (anchor == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setOperationalAnchor(anchor);
        saveFleetDoctrine(cfg);
    }

    public static void updateObjectiveCapturePolicy(ObjectiveCapturePolicy policy) {
        if (policy == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setObjectiveCapturePolicy(policy);
        saveFleetDoctrine(cfg);
    }

    public static void updateDecisiveArm(DecisiveArm arm) {
        if (arm == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setDecisiveArm(arm);
        saveFleetDoctrine(cfg);
    }

    public static void updateEngagementDiscipline(EngagementDiscipline discipline) {
        if (discipline == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setEngagementDiscipline(discipline);
        saveFleetDoctrine(cfg);
    }

    public static void updateDeployInFleetFormation(boolean enabled) {
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setDeployInFleetFormation(enabled);
        saveFleetDoctrine(cfg);
    }

    public static void updateOrchestratedAdvance(boolean enabled) {
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setOrchestratedAdvance(enabled);
        saveFleetDoctrine(cfg);
    }

    public static void updateDivisionTypeSetpieceAttack(
            DoctrineDivisionType dt,
            DivisionSetpieceAttack setpiece) {
        if (dt == null || setpiece == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setSetpieceAttack(dt, setpiece);
        saveFleetDoctrine(cfg);
    }

    public static void updateOtcPlay(OtcPlay play, boolean enabled) {
        if (play == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setPlayEnabled(play, enabled);
        saveFleetDoctrine(cfg);
    }

    public static void updateAutoAssignCapability(AutoAssignCapability cap, boolean enabled) {
        if (cap == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setCapabilityEnabled(cap, enabled);
        saveFleetDoctrine(cfg);
    }

    public static void updateAutoAssignHull(AutoAssignHull hull, boolean enabled) {
        if (hull == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setHullEnabled(hull, enabled);
        saveFleetDoctrine(cfg);
    }

    public static void updateAutoAssignScheme(AutoAssignCapability cap, AutoAssignHull hull, boolean enabled) {
        if (cap == null || hull == null) return;
        FleetDoctrineConfig cfg = getFleetDoctrine();
        cfg.setSchemeEnabled(cap, hull, enabled);
        saveFleetDoctrine(cfg);
    }

    public static void resetFleetDoctrineToDefaults() {
        saveFleetDoctrine(new FleetDoctrineConfig());
    }

    public enum DoctrineOption {
        ADAPTIVE("Adaptive", "Follows its division type and mission normally, adapting to local combat."),
        SPEARHEAD("Spearhead", "Presses attacks aggressively and commits to close action at lower odds."),
        FIRE_SUPPORT("Fire Support", "Fights cautiously at standoff range and rotates out early at 65% flux."),
        ESCORT_AND_SCREEN("Escort & Screen", "Stays with the battle line to screen capitals and carriers instead of flanking."),
        FLANKING_STRIKE("Mobile Flanker", "Detaches from the main line to seize objectives, turn flanks, and pursue."),
        RESERVE("Reserve", "Holds astern of the battle line until committed to reinforce.");

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
        /** Spec 01 C2-INV-07: True ONLY when the player manually adjusted Line Standoff via +/- (false for auto-set longest-gun defaults). */
        public boolean customStandoffManual = false;
        public DivisionMissionType customOpeningMission = null;
        public DivisionMissionType customMainMission = null;
        public StrikeCraftMissionType customStrikeCraftOpeningMission = null;
        public StrikeCraftMissionType customStrikeCraftMainMission = null;
        public StrikeCraftCapReserve customStrikeCraftCapReserve = null;
        public LineStation customLineStation = null;
        public DivisionFormation customFormation = null;
        public DivisionMovementStickiness customMovementStickiness = null;
        public DivisionEngineTelegraph customEngineTelegraph = null;
        public VoidstrikeTargetingBias customVoidstrikeTargetingBias = null;
        public Boolean customVoidstrikeFocusCarriers = null;
        public VoidstrikeTargetObservance customVoidstrikeObservance = null;
        public VoidstrikeCoordinationMode customVoidstrikeCoordination = null;
        public VoidstrikeAttackGeometry customVoidstrikeAttackGeometry = null;
        public VoidstrikeSplitMode customVoidstrikeSplitMode = null;
        public VoidstrikeGunneryMode customVoidstrikeGunneryMode = null;
        public VoidstrikeAoaCorrection customVoidstrikeAoaCorrection = null;
        public SquadronFormation customFighterFormation = null;
        public SquadronFormation customFighterVoidstrikeFormation = null;
        public SquadronFormation customBomberFormation = null;
        public SquadronFormation customBomberVoidstrikeFormation = null;
        public Integer customVoidstrikeSquadronDepth = null;
        public Integer customVoidstrikeSquadronWidth = null;
        public DivisionSetpieceAttack customSetpieceAttack = null;
        public ShipSkillCoordinationMode customShipSkillCoordination = null;
        public DivisionRammingMode customRammingMode = null;
        public DivisionRammingTargetScope customRammingTargetScope = null;
        public Integer customMaxRammingSlots = null;

        /**
         * Members temporarily absent from the player fleet (disabled awaiting recovery, mid
         * restoration / D-mod repair hull swap, loaned out by a mod script, etc.). Keyed by member
         * id -> {fingerprint, sinceTimestamp}. They are re-attached automatically when the same id
         * (or an unassigned ship with the same name + base hull) reappears, and only expire after
         * DORMANT_MEMBER_EXPIRY_DAYS of continuous absence.
         */
        public Map<String, DormantMember> dormantMembers = new LinkedHashMap<>();

        public PermanentDivisionSpec(String id, String name, DivisionArchetype archetype) {
            this.id = id;
            this.name = name;
            this.archetype = archetype != null ? archetype : DivisionArchetype.CRUISER_DIVISION;
        }

        /**
         * XStream legacy-save migration hook: if an older save serialized a PermanentDivisionSpec
         * instance directly into a fleet's MemoryAPI ($divisionai_npc_divisions_v1), immediately
         * resolve it to a plain JDK Map<String, Object> during XML unmarshalling so zero custom
         * divisionai.* classes remain anywhere in the Sector object graph.
         */
        private Object readResolve() {
            return serializeSpecToMap(this);
        }
    }

    public static class DormantMember {
        public String fingerprint;
        public long sinceTimestamp;
        public int slotIndex;

        public DormantMember(String fingerprint, long sinceTimestamp, int slotIndex) {
            this.fingerprint = fingerprint;
            this.sinceTimestamp = sinceTimestamp;
            this.slotIndex = slotIndex;
        }
    }

    /** Campaign days a missing member is remembered before it is considered permanently gone. */
    static final float DORMANT_MEMBER_EXPIRY_DAYS = 90f;

    static String memberFingerprint(FleetMemberAPI m) {
        if (m == null) return null;
        String name = m.getShipName();
        String base = null;
        ShipHullSpecAPI hs = m.getHullSpec();
        if (hs != null) {
            base = hs.getBaseHullId();
            if (base == null || base.isEmpty()) base = hs.getHullId();
            // D-mod hulls are "<base>_default_D" in vanilla and mods; strip so restore/damage keeps identity.
            if (base != null && base.endsWith("_default_D")) base = base.substring(0, base.length() - "_default_D".length());
        }
        if (name == null || name.trim().isEmpty() || base == null) return null;
        String trimmed = name.trim();
        // Ignore fleet-prefix differences ("RDS Xilonen" vs "Xilonen").
        int sp = trimmed.indexOf(' ');
        if (sp >= 2 && sp <= 4) {
            String head = trimmed.substring(0, sp);
            if (head.equals(head.toUpperCase()) && !head.equals(head.toLowerCase())) {
                trimmed = trimmed.substring(sp + 1).trim();
            }
        }
        String n = trimmed.toLowerCase();
        return n + "|" + base.toLowerCase();
    }

    public static StrikeCraftCapReserve resolveEffectiveStrikeCraftCapReserve(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customStrikeCraftCapReserve != null) {
            return spec.customStrikeCraftCapReserve;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getStrikeCraftCapReserve(dt) : FleetDoctrineConfig.defaultStrikeCraftCapReserveFor(dt);
    }

    public static VoidstrikeTargetingBias resolveEffectiveVoidstrikeTargetingBias(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customVoidstrikeTargetingBias != null) {
            return spec.customVoidstrikeTargetingBias;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getVoidstrikeTargetingBias(dt) : FleetDoctrineConfig.defaultVoidstrikeTargetingBiasFor(dt);
    }

    public static boolean resolveEffectiveVoidstrikeFocusCarriers(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customVoidstrikeFocusCarriers != null) {
            return spec.customVoidstrikeFocusCarriers.booleanValue();
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.isVoidstrikeFocusCarriers(dt) : FleetDoctrineConfig.defaultVoidstrikeFocusCarriersFor(dt);
    }

    public static VoidstrikeTargetObservance resolveEffectiveVoidstrikeObservance(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customVoidstrikeObservance != null) {
            return spec.customVoidstrikeObservance;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getVoidstrikeObservance(dt) : FleetDoctrineConfig.defaultVoidstrikeObservanceFor(dt);
    }

    public static VoidstrikeCoordinationMode resolveEffectiveVoidstrikeCoordination(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customVoidstrikeCoordination != null) {
            return spec.customVoidstrikeCoordination;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getVoidstrikeCoordination(dt) : FleetDoctrineConfig.defaultVoidstrikeCoordinationFor(dt);
    }

    public static VoidstrikeAttackGeometry resolveEffectiveVoidstrikeAttackGeometry(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customVoidstrikeAttackGeometry != null) {
            return spec.customVoidstrikeAttackGeometry;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getVoidstrikeAttackGeometry(dt) : FleetDoctrineConfig.defaultVoidstrikeAttackGeometryFor(dt);
    }

    public static VoidstrikeSplitMode resolveEffectiveVoidstrikeSplitMode(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customVoidstrikeSplitMode != null) {
            return spec.customVoidstrikeSplitMode;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getVoidstrikeSplitMode(dt) : FleetDoctrineConfig.defaultVoidstrikeSplitModeFor(dt);
    }

    public static VoidstrikeGunneryMode resolveEffectiveVoidstrikeGunneryMode(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customVoidstrikeGunneryMode != null) {
            return spec.customVoidstrikeGunneryMode;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getVoidstrikeGunneryMode(dt) : FleetDoctrineConfig.defaultVoidstrikeGunneryModeFor(dt);
    }

    public static VoidstrikeAoaCorrection resolveEffectiveVoidstrikeAoaCorrection(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customVoidstrikeAoaCorrection != null) {
            return spec.customVoidstrikeAoaCorrection;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getVoidstrikeAoaCorrection(dt) : FleetDoctrineConfig.defaultVoidstrikeAoaCorrectionFor(dt);
    }

    public static SquadronFormation resolveEffectiveFighterFormation(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customFighterFormation != null) {
            return spec.customFighterFormation;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getFighterFormation(dt) : FleetDoctrineConfig.defaultFighterFormationFor(dt);
    }

    public static SquadronFormation resolveEffectiveFighterVoidstrikeFormation(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customFighterVoidstrikeFormation != null) {
            return spec.customFighterVoidstrikeFormation;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getFighterVoidstrikeFormation(dt) : FleetDoctrineConfig.defaultFighterVoidstrikeFormationFor(dt);
    }

    public static SquadronFormation resolveEffectiveBomberFormation(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customBomberFormation != null) {
            return spec.customBomberFormation;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getBomberFormation(dt) : FleetDoctrineConfig.defaultBomberFormationFor(dt);
    }

    public static SquadronFormation resolveEffectiveBomberVoidstrikeFormation(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customBomberVoidstrikeFormation != null) {
            return spec.customBomberVoidstrikeFormation;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getBomberVoidstrikeFormation(dt) : FleetDoctrineConfig.defaultBomberVoidstrikeFormationFor(dt);
    }

    public static int resolveEffectiveVoidstrikeSquadronDepth(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customVoidstrikeSquadronDepth != null) {
            return Math.max(MIN_VOIDSTRIKE_SQUADRON_DEPTH,
                    Math.min(MAX_VOIDSTRIKE_SQUADRON_DEPTH, spec.customVoidstrikeSquadronDepth));
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getVoidstrikeSquadronDepth(dt) : FleetDoctrineConfig.defaultVoidstrikeSquadronDepthFor(dt);
    }

    public static int resolveEffectiveVoidstrikeSquadronWidth(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customVoidstrikeSquadronWidth != null) {
            return Math.max(MIN_VOIDSTRIKE_SQUADRON_WIDTH,
                    Math.min(MAX_VOIDSTRIKE_SQUADRON_WIDTH, spec.customVoidstrikeSquadronWidth));
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getVoidstrikeSquadronWidth(dt) : FleetDoctrineConfig.defaultVoidstrikeSquadronWidthFor(dt);
    }

    public static DivisionFormation resolveEffectiveFormation(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customFormation != null) {
            return spec.customFormation;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getFormation(dt) : dt.defaultFormation;
    }

    public static DivisionSetpieceAttack resolveEffectiveSetpieceAttack(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customSetpieceAttack != null) {
            return spec.customSetpieceAttack;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getSetpieceAttack(dt) : FleetDoctrineConfig.defaultSetpieceAttackFor(dt);
    }

    public static ShipSkillCoordinationMode resolveEffectiveShipSkillCoordination(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customShipSkillCoordination != null) {
            return spec.customShipSkillCoordination;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getShipSkillCoordination(dt) : FleetDoctrineConfig.defaultShipSkillCoordinationFor(dt);
    }

    public static DivisionRammingMode resolveEffectiveRammingMode(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customRammingMode != null) {
            return spec.customRammingMode;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        DivisionRammingMode docMode = cfg != null ? cfg.getRammingMode(dt) : FleetDoctrineConfig.defaultRammingModeFor(dt);
        if (docMode != DivisionRammingMode.OFF) {
            return docMode;
        }
        if (spec != null && Global.getSector() != null && Global.getSector().getPlayerFleet() != null) {
            if (countRamCapableShipsInDivision(spec, Global.getSector().getPlayerFleet()) > 0) {
                return DivisionRammingMode.GLANCING_SKIRMISH;
            }
        }
        return DivisionRammingMode.OFF;
    }

    public static DivisionRammingTargetScope resolveEffectiveRammingTargetScope(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customRammingTargetScope != null) {
            return spec.customRammingTargetScope;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getRammingTargetScope(dt) : FleetDoctrineConfig.defaultRammingTargetScopeFor(dt);
    }

    public static int resolveEffectiveMaxRammingSlots(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customMaxRammingSlots != null) {
            return Math.max(MIN_RAMMING_ATTACK_SLOTS, Math.min(MAX_RAMMING_ATTACK_SLOTS, spec.customMaxRammingSlots));
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getMaxRammingSlots(dt) : FleetDoctrineConfig.defaultMaxRammingSlotsFor(dt);
    }

    public static int countRamCapableShipsInDivision(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
        if (spec == null || spec.memberIds == null || spec.memberIds.isEmpty() || fleet == null) return 0;
        int count = 0;
        for (FleetMemberAPI m : getMembersInDivision(spec, fleet)) {
            if (m != null && ShipSystemCoordinator.isFleetMemberDesignedToRam(m)) {
                count++;
            }
        }
        return count;
    }

    public static void autoEnableRammingIfDivisionHasRamShips(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
        if (spec == null || fleet == null) return;
        int ramShips = countRamCapableShipsInDivision(spec, fleet);
        if (ramShips > 0) {
            if (spec.customRammingMode == null) {
                spec.customRammingMode = DivisionRammingMode.GLANCING_SKIRMISH;
            }
            if (spec.customRammingTargetScope == null) {
                spec.customRammingTargetScope = DivisionRammingTargetScope.SINGLE_SHIP;
            }
            if (spec.customMaxRammingSlots == null) {
                spec.customMaxRammingSlots = 1;
            }
        }
    }

    public static DivisionMovementStickiness resolveEffectiveMovementStickiness(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customMovementStickiness != null) {
            return spec.customMovementStickiness;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null
                ? cfg.getMovementStickiness(dt)
                : DivisionMovementStickiness.fromStationKeepingMult(dt.defaultStationKeepingMult);
    }

    public static DivisionEngineTelegraph resolveEffectiveEngineTelegraph(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customEngineTelegraph != null) {
            return spec.customEngineTelegraph;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getEngineTelegraph(dt) : DivisionEngineTelegraph.AUTO;
    }

    public static float resolveEffectiveStationKeepingMult(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customMovementStickiness != null) {
            return spec.customMovementStickiness.stationKeepingMult;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getStationKeepingMult(dt) : dt.defaultStationKeepingMult;
    }

    public static int resolveEffectiveStationSpacing(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customStationSpacing != null) {
            return Math.max(MIN_STATION_SPACING_UNITS, Math.min(getEffectiveMaxStationSpacingUnits(), spec.customStationSpacing));
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getStationSpacing(dt) : getEffectiveDefaultStationSpacingUnits(dt);
    }

    public static int resolveEffectiveDivisionInterval(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customDivisionInterval != null) {
            return Math.max(MIN_DIVISION_INTERVAL_UNITS, Math.min(getEffectiveMaxDivisionIntervalUnits(), spec.customDivisionInterval));
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getDivisionInterval(dt) : getEffectiveDefaultDivisionIntervalUnits(dt);
    }

    public static int resolveEffectiveStandoffRange(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customStandoffRange != null) {
            return Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(getEffectiveMaxStandoffRangeUnits(), spec.customStandoffRange));
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getStandoffRange(dt) : getEffectiveDefaultStandoffRangeUnits(dt);
    }

    public static DivisionMissionType resolveEffectiveOpeningMission(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customOpeningMission != null) {
            if (spec.customOpeningMission.isCarrierMission) {
                return DivisionMissionType.CARRIER_STANDOFF;
            }
            return spec.customOpeningMission;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getOpeningMission(dt) : dt.defaultOpeningMission;
    }

    public static DivisionMissionType resolveEffectiveMainMission(PermanentDivisionSpec spec, FleetDoctrineConfig cfg) {
        if (spec != null && spec.customMainMission != null) {
            if (spec.customMainMission.isCarrierMission) {
                return DivisionMissionType.CARRIER_STANDOFF;
            }
            return spec.customMainMission;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null ? cfg.getMainMission(dt) : dt.defaultMainMission;
    }

    public static StrikeCraftMissionType resolveEffectiveStrikeCraftOpeningMission(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customStrikeCraftOpeningMission != null) {
            return spec.customStrikeCraftOpeningMission;
        }
        if (spec != null && spec.customOpeningMission != null && spec.customOpeningMission.isCarrierMission) {
            StrikeCraftMissionType mapped = StrikeCraftMissionType.fromLegacyCarrierMission(spec.customOpeningMission);
            if (mapped != null) return mapped;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null
                ? cfg.getStrikeCraftOpeningMission(dt)
                : FleetDoctrineConfig.defaultStrikeCraftOpeningMissionFor(dt);
    }

    public static StrikeCraftMissionType resolveEffectiveStrikeCraftMainMission(
            PermanentDivisionSpec spec,
            FleetDoctrineConfig cfg) {
        if (spec != null && spec.customStrikeCraftMainMission != null) {
            return spec.customStrikeCraftMainMission;
        }
        if (spec != null && spec.customMainMission != null && spec.customMainMission.isCarrierMission) {
            StrikeCraftMissionType mapped = StrikeCraftMissionType.fromLegacyCarrierMission(spec.customMainMission);
            if (mapped != null) return mapped;
        }
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec != null ? spec.archetype : null);
        return cfg != null
                ? cfg.getStrikeCraftMainMission(dt)
                : FleetDoctrineConfig.defaultStrikeCraftMainMissionFor(dt);
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
        return computeDivisionLongestGunRangeFromMembers(spec, members, includeMissiles);
    }

    private static int computeDivisionLongestGunRangeFromMembers(
            PermanentDivisionSpec spec,
            List<FleetMemberAPI> members,
            boolean includeMissiles) {
        if (spec == null || members == null || members.isEmpty()) return 0;
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
                WeaponType wt = wSpec.getType();
                if (m.getStats() != null) {
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
                effectiveRange = applyShipWeaponRangeThreshold(m.getStats(), variant, wt, effectiveRange);
                if (effectiveRange > maxRange) {
                    maxRange = effectiveRange;
                }
            }

            // Also check carrier fighter/bomber engagement range if this is a Carrier division fitted with offensive wings
            // (skipping Defensive Targeting Array / 0-range tethered wings and Support escort wings)
            if (spec.archetype != null
                    && spec.archetype.isCarrierArchetype()
                    && !variant.hasHullMod(VoidstrikeCoordinator.DEFENSIVE_TARGETING_ARRAY_HULLMOD_ID)
                    && variant.getFittedWings() != null) {
                for (String wingId : variant.getFittedWings()) {
                    if (wingId == null) continue;
                    FighterWingSpecAPI wingSpec = Global.getSettings().getFighterWingSpec(wingId);
                    if (wingSpec == null || wingSpec.isSupport() || wingSpec.hasTag("auto_escort")) continue;
                    float effWingRange = m.getStats().getFighterWingRange().computeEffective(wingSpec.getRange());
                    if (effWingRange > maxRange) {
                        int effMaxStandoff = getEffectiveMaxStandoffRangeUnits();
                        float wingCap = 1800f * Math.max(1f, CombatPhysicsProfile.getGlobalFighterRangeScale() * 0.80f);
                        maxRange = Math.min(effMaxStandoff, Math.max(maxRange, Math.min(wingCap, effWingRange)));
                    }
                }
            }
        }

        if (maxRange <= 0f) return 0;
        int rounded = Math.round(maxRange / 25f) * 25;
        return Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(getEffectiveMaxStandoffRangeUnits(), rounded));
    }

    /**
     * Applies Starsector's exact {@code getThresholdAdjustedRange} weapon-range clamping
     * ({@code SafetyOverrides}: 450u threshold + 0.25x past threshold, {@code HighScatterAmplifier}, etc.)
     * to non-missile weapons in campaign standoff auto-calculation and headless ship profiling.
     */
    public static float applyShipWeaponRangeThreshold(
            com.fs.starfarer.api.combat.MutableShipStatsAPI stats,
            ShipVariantAPI variant,
            WeaponType weaponType,
            float rawOrBonusedRange) {
        if (rawOrBonusedRange <= 0f || weaponType == WeaponType.MISSILE) {
            return rawOrBonusedRange;
        }
        float threshold = 0f;
        float multPast = 1f;
        boolean statResolved = false;
        if (stats != null) {
            try {
                if (stats.getWeaponRangeThreshold() != null && stats.getWeaponRangeMultPastThreshold() != null) {
                    threshold = stats.getWeaponRangeThreshold().getModifiedValue();
                    multPast = stats.getWeaponRangeMultPastThreshold().getModifiedValue();
                    statResolved = (threshold > 0f && multPast < 0.999f);
                }
            } catch (Throwable ignored) {
            }
        }
        if (!statResolved && variant != null && variant.hasHullMod("safetyoverrides")) {
            threshold = 450f;
            multPast = 0.25f;
        }
        if (threshold > 0f && multPast < 0.999f && rawOrBonusedRange > threshold) {
            return threshold + (rawOrBonusedRange - threshold) * Math.max(0f, multPast);
        }
        return rawOrBonusedRange;
    }

    public static void recalculateDivisionStandoffRangeFromGuns(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
        if (spec == null) return;
        int longest = computeDivisionLongestGunRange(spec, fleet);
        if (longest > 0) {
            spec.customStandoffRange = longest;
            spec.customStandoffManual = false;
        }
    }

    public static void recalculateDivisionStandoffRangeFromMembers(
            PermanentDivisionSpec spec,
            List<FleetMemberAPI> resolvedMembers) {
        if (spec == null || resolvedMembers == null || resolvedMembers.isEmpty()) return;
        int longest = computeDivisionLongestGunRangeFromMembers(spec, resolvedMembers, false);
        if (longest > 0) {
            spec.customStandoffRange = longest;
            spec.customStandoffManual = false;
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

    private static final String PERSISTENT_PHYSICS_SCALE_STAMP_KEY = "divisionai_physics_scale_stamp_v1";
    private static final String PERSISTENT_PHYSICS_SPACING_STAMP_KEY = "divisionai_physics_spacing_stamp_v2";
    private static final String PERSISTENT_PHYSICS_INTERVAL_STAMP_KEY = "divisionai_physics_interval_stamp_v2";

    /**
     * Synchronizes persistent player {@link FleetDoctrineConfig} defaults and auto-calculated division standoffs
     * whenever the active combat-physics scale or mutual-support spacing/interval curve changes.
     */
    public static void syncDoctrineAndDivisionDefaultsWithPhysicsScale() {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPersistentData() == null) return;
        Map<String, Object> pdata = sector.getPersistentData();
        float currGunScale = CombatPhysicsProfile.getGlobalWeaponRangeScale();
        float currSpacingMult = CombatPhysicsProfile.getGlobalStationSpacingMult();
        float currIntervalMult = CombatPhysicsProfile.getGlobalDivisionIntervalMult();

        float prevGunScale = 1.0f;
        Object prevObj = pdata.get(PERSISTENT_PHYSICS_SCALE_STAMP_KEY);
        if (prevObj instanceof Number) {
            prevGunScale = Math.max(1.0f, ((Number) prevObj).floatValue());
        }
        float prevSpacingMult = 1.0f;
        Object prevSpObj = pdata.get(PERSISTENT_PHYSICS_SPACING_STAMP_KEY);
        if (prevSpObj instanceof Number) {
            prevSpacingMult = Math.max(1.0f, ((Number) prevSpObj).floatValue());
        } else if (prevObj != null && prevGunScale > 1.25f) {
            // Migrate v1 Better Combat saves that stamped gunScale under the legacy 2.55x/2.85x spacing/interval formula
            float estBounds = CombatPhysicsProfile.getGlobalShipBoundsScale();
            prevSpacingMult = Math.max(estBounds * 0.85f, 0.45f + 0.55f * prevGunScale);
        }
        float prevIntervalMult = 1.0f;
        Object prevIntObj = pdata.get(PERSISTENT_PHYSICS_INTERVAL_STAMP_KEY);
        if (prevIntObj instanceof Number) {
            prevIntervalMult = Math.max(1.0f, ((Number) prevIntObj).floatValue());
        } else if (prevObj != null && prevGunScale > 1.25f) {
            float estBounds = CombatPhysicsProfile.getGlobalShipBoundsScale();
            prevIntervalMult = Math.max(estBounds * 0.95f, 0.30f + 0.70f * prevGunScale);
        }

        boolean scaleChanged = (prevObj == null && CombatPhysicsProfile.isGlobalScaledEnvironment())
                || (prevObj != null && Math.abs(currGunScale - prevGunScale) > 0.15f)
                || (prevObj != null && Math.abs(currSpacingMult - prevSpacingMult) > 0.12f)
                || (prevObj != null && Math.abs(currIntervalMult - prevIntervalMult) > 0.12f);
        pdata.put(PERSISTENT_PHYSICS_SCALE_STAMP_KEY, currGunScale);
        pdata.put(PERSISTENT_PHYSICS_SPACING_STAMP_KEY, currSpacingMult);
        pdata.put(PERSISTENT_PHYSICS_INTERVAL_STAMP_KEY, currIntervalMult);

        // Auto-heal any previously double-scaled standoff values (e.g. 2,800u * 3.64 = 10,200u on MISSILE_FRIGATE / LIGHT_CARRIER
        // or 10,000u on BATTLESHIP from saves predating PERSISTENT_PHYSICS_SCALE_STAMP_KEY) even when physics scale stamps have not changed.
        FleetDoctrineConfig checkCfg = getFleetDoctrine();
        if (!scaleChanged && checkCfg != null && CombatPhysicsProfile.isGlobalScaledEnvironment()) {
            boolean healedAny = false;
            for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
                int effDef = getEffectiveDefaultStandoffRangeUnits(dt);
                int curSt = checkCfg.getStandoffRange(dt);
                if (curSt > Math.round(effDef * 1.45f)) {
                    checkCfg.setStandoffRange(dt, effDef);
                    healedAny = true;
                }
            }
            if (healedAny) {
                saveFleetDoctrine(checkCfg);
            }
        }
        if (!scaleChanged) {
            if (currGunScale <= 1.05f) {
                CampaignFleetAPI vanFleet = sector.getPlayerFleet();
                if (vanFleet != null) {
                    List<PermanentDivisionSpec> vanSpecs = ensureInitializedForPlayerFleet();
                    boolean vanHealed = false;
                    for (PermanentDivisionSpec spec : vanSpecs) {
                        if (spec == null || spec.customStandoffRange == null || spec.customStandoffManual) continue;
                        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec.archetype);
                        int liveGun = computeDivisionLongestGunRange(spec, vanFleet);
                        if (liveGun > 0 && (spec.customStandoffRange > MAX_STANDOFF_RANGE_UNITS
                                || (spec.customStandoffRange >= 2600
                                        && spec.customStandoffRange > Math.max(liveGun * 1.25f, dt.defaultStandoffRangeUnits * 1.50f)))) {
                            spec.customStandoffRange = liveGun;
                            vanHealed = true;
                        }
                    }
                    if (vanHealed) {
                        saveSpecsToPersistentData(pdata, vanSpecs);
                    }
                }
            }
            return;
        }

        FleetDoctrineConfig cfg = getFleetDoctrine();
        if (cfg != null) {
            float gunRatio = currGunScale / Math.max(1.0f, prevGunScale);
            float spRatio = currSpacingMult / Math.max(1.0f, prevSpacingMult);
            float intRatio = currIntervalMult / Math.max(1.0f, prevIntervalMult);
            for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
                int newDefSp = getEffectiveDefaultStationSpacingUnits(dt);
                int newDefInt = getEffectiveDefaultDivisionIntervalUnits(dt);
                int newDefSt = getEffectiveDefaultStandoffRangeUnits(dt);
                int oldDefSp = Math.round((dt.defaultStationSpacingUnits * prevSpacingMult) / 25f) * 25;
                int oldDefInt = Math.round((dt.defaultDivisionIntervalUnits * prevIntervalMult) / 50f) * 50;
                int oldDefSt = Math.round((dt.defaultStandoffRangeUnits * prevGunScale) / 50f) * 50;

                int curSp = cfg.getStationSpacing(dt);
                if (curSp == dt.defaultStationSpacingUnits
                        || (currSpacingMult <= 1.02f && curSp > MAX_STATION_SPACING_UNITS)
                        || Math.abs(curSp - oldDefSp) <= 50) {
                    cfg.setStationSpacing(dt, newDefSp);
                } else if (!(prevObj == null && curSp > dt.defaultStationSpacingUnits * 1.35f)) {
                    cfg.setStationSpacing(dt, Math.round((curSp * spRatio) / 25f) * 25);
                }

                int curInt = cfg.getDivisionInterval(dt);
                if (curInt == dt.defaultDivisionIntervalUnits
                        || (currIntervalMult <= 1.02f && curInt > MAX_DIVISION_INTERVAL_UNITS)
                        || Math.abs(curInt - oldDefInt) <= 100) {
                    cfg.setDivisionInterval(dt, newDefInt);
                } else if (!(prevObj == null && curInt > dt.defaultDivisionIntervalUnits * 1.35f)) {
                    cfg.setDivisionInterval(dt, Math.round((curInt * intRatio) / 50f) * 50);
                }

                int curSt = cfg.getStandoffRange(dt);
                boolean alreadyScaledBeforeStamp = (prevObj == null && curSt > dt.defaultStandoffRangeUnits * 1.45f);
                if (curSt == dt.defaultStandoffRangeUnits
                        || (currGunScale <= 1.05f && curSt > MAX_STANDOFF_RANGE_UNITS)
                        || curSt > Math.round(newDefSt * 1.80f)
                        || Math.abs(curSt - oldDefSt) <= 100) {
                    cfg.setStandoffRange(dt, newDefSt);
                } else if (!alreadyScaledBeforeStamp && Math.abs(gunRatio - 1.0f) > 0.08f) {
                    cfg.setStandoffRange(dt, Math.round((curSt * gunRatio) / 50f) * 50);
                }
            }
            saveFleetDoctrine(cfg);
        }

        CampaignFleetAPI fleet = sector.getPlayerFleet();
        if (fleet != null) {
            List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();
            boolean specsUpdated = false;
            for (PermanentDivisionSpec spec : specs) {
                if (spec == null) continue;
                DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec.archetype);
                int oldSpecDefSp = Math.round((dt.defaultStationSpacingUnits * prevSpacingMult) / 25f) * 25;
                int oldSpecDefInt = Math.round((dt.defaultDivisionIntervalUnits * prevIntervalMult) / 50f) * 50;
                if (spec.customStationSpacing != null
                        && ((spec.customStationSpacing == dt.defaultStationSpacingUnits && CombatPhysicsProfile.isGlobalScaledEnvironment())
                        || Math.abs(spec.customStationSpacing - oldSpecDefSp) <= 50)) {
                    spec.customStationSpacing = getEffectiveDefaultStationSpacingUnits(dt);
                    specsUpdated = true;
                }
                if (spec.customDivisionInterval != null
                        && ((spec.customDivisionInterval == dt.defaultDivisionIntervalUnits && CombatPhysicsProfile.isGlobalScaledEnvironment())
                        || Math.abs(spec.customDivisionInterval - oldSpecDefInt) <= 100)) {
                    spec.customDivisionInterval = getEffectiveDefaultDivisionIntervalUnits(dt);
                    specsUpdated = true;
                }
                if (spec.customStandoffRange != null && !spec.customStandoffManual) {
                    int liveGun = computeDivisionLongestGunRange(spec, fleet);
                    if (liveGun > 0 && ((currGunScale > 1.25f && spec.customStandoffRange < liveGun * 0.65f)
                            || (currGunScale <= 1.05f
                                    && (spec.customStandoffRange > MAX_STANDOFF_RANGE_UNITS
                                            || spec.customStandoffRange > Math.max(liveGun * 1.25f, dt.defaultStandoffRangeUnits * 1.50f))))) {
                        spec.customStandoffRange = liveGun;
                        specsUpdated = true;
                    }
                }
            }
            if (specsUpdated) {
                saveSpecsToPersistentData(pdata, specs);
            }
        }
        LOG.info(String.format(java.util.Locale.ROOT,
                "[DivisionAI] Synchronized division & doctrine defaults to combat physics scale (gun=%.2fx, spacing=%.2fx, interval=%.2fx).",
                currGunScale, currSpacingMult, currIntervalMult));
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

    private interface DivisionSpecMutator {
        void mutate(PermanentDivisionSpec spec, CampaignFleetAPI playerFleet);
    }

    private static void mutatePlayerDivisionSpec(String divisionId, DivisionSpecMutator mutator) {
        if (divisionId == null || mutator == null) return;
        MissionScenarioBundle missionBundle = resolveActiveMissionScenario();
        if (missionBundle != null) {
            for (PermanentDivisionSpec spec : missionBundle.playerDivisions) {
                if (spec != null && divisionId.equals(spec.id)) {
                    mutator.mutate(spec, null);
                    break;
                }
            }
            return;
        }
        SectorAPI sector = Global.getSector();
        if (sector == null) return;
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();
        for (PermanentDivisionSpec spec : specs) {
            if (divisionId.equals(spec.id)) {
                mutator.mutate(spec, fleet);
                break;
            }
        }
        saveSpecsToPersistentData(pdata, specs);
    }

    public static void autoSetDivisionStandoffRange(String divisionId) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                recalculateDivisionStandoffRangeFromGuns(spec, fleet);
            }
        });
    }

    public static void updateIndividualDivisionSettings(
            String divisionId,
            final boolean updateIntra,
            final Integer intraSpacing,
            final boolean updateInter,
            final Integer interSpacing,
            final boolean updateStandoff,
            final Integer lineStandoff,
            final boolean updateOpeningMission,
            final DivisionMissionType openingMission,
            final boolean updateMainMission,
            final DivisionMissionType mainMission) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                if (updateIntra) {
                    spec.customStationSpacing = (intraSpacing != null)
                            ? Math.max(MIN_STATION_SPACING_UNITS, Math.min(getEffectiveMaxStationSpacingUnits(), intraSpacing))
                            : null;
                }
                if (updateInter) {
                    spec.customDivisionInterval = (interSpacing != null)
                            ? Math.max(MIN_DIVISION_INTERVAL_UNITS, Math.min(getEffectiveMaxDivisionIntervalUnits(), interSpacing))
                            : null;
                }
                if (updateStandoff) {
                    spec.customStandoffRange = (lineStandoff != null)
                            ? Math.max(MIN_STANDOFF_RANGE_UNITS, Math.min(getEffectiveMaxStandoffRangeUnits(), lineStandoff))
                            : null;
                    spec.customStandoffManual = (lineStandoff != null);
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
            }
        });
    }

    public static void updateIndividualDivisionLineStation(String divisionId, final LineStation lineStation) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customLineStation = lineStation;
            }
        });
    }

    public static void updateIndividualDivisionFormation(String divisionId, final DivisionFormation formation) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customFormation = formation;
            }
        });
    }

    public static void updateIndividualDivisionMovementStickiness(
            String divisionId,
            final DivisionMovementStickiness stickiness) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customMovementStickiness = stickiness;
            }
        });
    }

    public static void updateIndividualDivisionEngineTelegraph(
            String divisionId,
            final DivisionEngineTelegraph telegraph) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customEngineTelegraph = telegraph;
            }
        });
    }

    public static void updateIndividualDivisionSetpieceAttack(
            String divisionId,
            final DivisionSetpieceAttack setpiece) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customSetpieceAttack = setpiece;
            }
        });
    }

    public static void updateIndividualDivisionStrikeCraftMission(
            String divisionId,
            final boolean isOpening,
            final StrikeCraftMissionType mission) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                if (isOpening) {
                    spec.customStrikeCraftOpeningMission = mission;
                    if (spec.customOpeningMission != null && spec.customOpeningMission.isCarrierMission) {
                        spec.customOpeningMission = DivisionMissionType.CARRIER_STANDOFF;
                    }
                } else {
                    spec.customStrikeCraftMainMission = mission;
                    if (spec.customMainMission != null && spec.customMainMission.isCarrierMission) {
                        spec.customMainMission = DivisionMissionType.CARRIER_STANDOFF;
                    }
                }
            }
        });
    }

    public static void updateIndividualDivisionStrikeCraftCapReserve(
            String divisionId,
            final StrikeCraftCapReserve reserve) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customStrikeCraftCapReserve = reserve;
            }
        });
    }

    public static boolean hasDivisionFlightDeck(PermanentDivisionSpec spec, List<FleetMemberAPI> resolvedMembers) {
        if (spec != null && spec.archetype != null && spec.archetype.hasStrikeCraftCapability()) {
            return true;
        }
        if (resolvedMembers != null) {
            for (FleetMemberAPI m : resolvedMembers) {
                if (m == null) continue;
                if (m.getStats() != null
                        && m.getStats().getNumFighterBays() != null
                        && m.getStats().getNumFighterBays().getModifiedInt() > 0) {
                    return true;
                }
                if (m.getHullSpec() != null && m.getHullSpec().getFighterBays() > 0) {
                    return true;
                }
                if (m.getVariant() != null) {
                    if (m.getVariant().getFittedWings() != null && !m.getVariant().getFittedWings().isEmpty()) {
                        return true;
                    }
                    if (m.getVariant().hasHullMod("converted_hangar")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static DivisionArchetype inferSurfaceArchetypeForMembers(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
        ShipAPI.HullSize maxHull = ShipAPI.HullSize.CRUISER;
        if (spec != null && fleet != null) {
            List<FleetMemberAPI> members = getMembersInDivision(spec, fleet);
            if (!members.isEmpty()) {
                maxHull = ShipAPI.HullSize.FRIGATE;
                for (FleetMemberAPI m : members) {
                    if (m == null || m.getHullSpec() == null || m.getHullSpec().getHullSize() == null) continue;
                    ShipAPI.HullSize hs = m.getHullSpec().getHullSize();
                    if (hs.ordinal() > maxHull.ordinal()) {
                        maxHull = hs;
                    }
                }
            }
        }
        switch (maxHull) {
            case CAPITAL_SHIP:
                return DivisionArchetype.BATTLESHIP_DIVISION;
            case DESTROYER:
                return DivisionArchetype.DESTROYER_DIVISION;
            case FRIGATE:
                return DivisionArchetype.FRIGATE_DIVISION;
            default:
                return DivisionArchetype.CRUISER_DIVISION;
        }
    }

    private static DivisionArchetype inferCarrierArchetypeForMembers(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
        if (spec != null && fleet != null) {
            List<FleetMemberAPI> members = getMembersInDivision(spec, fleet);
            if (!members.isEmpty()) {
                boolean hasCapOrCruiser = false;
                for (FleetMemberAPI m : members) {
                    if (m == null || m.getHullSpec() == null || m.getHullSpec().getHullSize() == null) continue;
                    ShipAPI.HullSize hs = m.getHullSpec().getHullSize();
                    if (hs == ShipAPI.HullSize.CAPITAL_SHIP || hs == ShipAPI.HullSize.CRUISER) {
                        hasCapOrCruiser = true;
                        break;
                    }
                }
                if (!hasCapOrCruiser) {
                    return DivisionArchetype.LIGHT_CARRIER_DIVISION;
                }
            }
        }
        return DivisionArchetype.FLEET_CARRIER_DIVISION;
    }

    public static DivisionQuickRole resolveMatchingQuickRole(PermanentDivisionSpec spec) {
        if (spec == null) return DivisionQuickRole.DOCTRINE_DEFAULT;
        if (spec.customLineStation == null
                && spec.customOpeningMission == null
                && spec.customMainMission == null
                && spec.customMovementStickiness == null
                && !spec.customStandoffManual) {
            return DivisionQuickRole.DOCTRINE_DEFAULT;
        }
        if (spec.customLineStation == LineStation.VAN
                && spec.customOpeningMission == DivisionMissionType.CLOSE_ACTION
                && spec.customMainMission == DivisionMissionType.CLOSE_ACTION
                && spec.customMovementStickiness == DivisionMovementStickiness.STRICT
                && !spec.customStandoffManual) {
            return DivisionQuickRole.BATTLE_LINE;
        }
        if (spec.customLineStation == LineStation.VAN
                && spec.customOpeningMission == DivisionMissionType.CLOSE_ACTION
                && spec.customMainMission == DivisionMissionType.CLOSE_ACTION
                && spec.customMovementStickiness == DivisionMovementStickiness.LOOSE
                && spec.customStandoffManual) {
            return DivisionQuickRole.AGGRESSIVE;
        }
        if (spec.customLineStation == LineStation.CENTER
                && spec.customOpeningMission == DivisionMissionType.STANDOFF_ENGAGEMENT
                && spec.customMainMission == DivisionMissionType.STANDOFF_ENGAGEMENT
                && spec.customMovementStickiness == DivisionMovementStickiness.BALANCED
                && !spec.customStandoffManual) {
            return DivisionQuickRole.STANDOFF;
        }
        if (spec.customLineStation == LineStation.REAR
                && spec.customOpeningMission == DivisionMissionType.STANDOFF_ENGAGEMENT
                && spec.customMainMission == DivisionMissionType.STANDOFF_ENGAGEMENT
                && spec.customMovementStickiness == DivisionMovementStickiness.STRICT) {
            return DivisionQuickRole.ARTILLERY;
        }
        if (spec.customLineStation == LineStation.VAN
                && spec.customOpeningMission == DivisionMissionType.SCOUTING
                && spec.customMainMission == DivisionMissionType.SCOUTING
                && spec.customMovementStickiness == DivisionMovementStickiness.LOOSE
                && !spec.customStandoffManual) {
            return DivisionQuickRole.SCOUT;
        }
        if (spec.customLineStation == LineStation.VAN
                && spec.customOpeningMission == DivisionMissionType.SCOUTING
                && spec.customMainMission == DivisionMissionType.ENVELOPMENT
                && spec.customMovementStickiness == DivisionMovementStickiness.LOOSE
                && !spec.customStandoffManual) {
            return DivisionQuickRole.HARASSER;
        }
        if (spec.customLineStation == LineStation.VAN
                && spec.customOpeningMission == DivisionMissionType.SCOUTING
                && spec.customMainMission == DivisionMissionType.TOT_ALPHA_STRIKE
                && spec.customMovementStickiness == DivisionMovementStickiness.BALANCED
                && !spec.customStandoffManual) {
            return DivisionQuickRole.HUNTER_KILLER;
        }
        if (spec.customLineStation == LineStation.CENTER
                && spec.customOpeningMission == DivisionMissionType.SCREEN
                && spec.customMainMission == DivisionMissionType.SCREEN
                && spec.customMovementStickiness == DivisionMovementStickiness.BALANCED
                && !spec.customStandoffManual) {
            return DivisionQuickRole.SCREEN_ESCORT;
        }
        return null;
    }

    public static DivisionQuickDeck resolveMatchingQuickDeck(PermanentDivisionSpec spec) {
        if (spec == null) return DivisionQuickDeck.DOCTRINE_DEFAULT;
        boolean carrierArch = spec.archetype != null && spec.archetype.isCarrierArchetype();
        boolean strikeCapArch = spec.archetype != null && spec.archetype.hasStrikeCraftCapability();
        if (spec.customStrikeCraftOpeningMission == null
                && spec.customStrikeCraftMainMission == null
                && (carrierArch || spec.customMainMission != DivisionMissionType.CARRIER_STANDOFF)) {
            return DivisionQuickDeck.DOCTRINE_DEFAULT;
        }
        if (!strikeCapArch
                && spec.customStrikeCraftOpeningMission == StrikeCraftMissionType.COMBAT_SPACE_PATROL
                && spec.customStrikeCraftMainMission == StrikeCraftMissionType.COMBAT_SPACE_PATROL
                && spec.customMainMission != DivisionMissionType.CARRIER_STANDOFF) {
            return DivisionQuickDeck.NOT_A_CARRIER;
        }
        if (spec.archetype == DivisionArchetype.BATTLECARRIER_DIVISION
                && spec.customStrikeCraftOpeningMission == StrikeCraftMissionType.ARMED_RECONNAISSANCE
                && spec.customStrikeCraftMainMission == StrikeCraftMissionType.DECKLOAD_STRIKE
                && spec.customMainMission != DivisionMissionType.CARRIER_STANDOFF) {
            return DivisionQuickDeck.BATTLECARRIER;
        }
        if (carrierArch
                && spec.customMainMission == DivisionMissionType.CARRIER_STANDOFF
                && spec.customStrikeCraftOpeningMission == StrikeCraftMissionType.ARMED_RECONNAISSANCE
                && spec.customStrikeCraftMainMission == StrikeCraftMissionType.DECKLOAD_STRIKE) {
            return DivisionQuickDeck.FLEET_CARRIER;
        }
        if (carrierArch
                && spec.customMainMission == DivisionMissionType.CARRIER_STANDOFF
                && spec.customStrikeCraftOpeningMission == StrikeCraftMissionType.COMBAT_SPACE_PATROL
                && spec.customStrikeCraftMainMission == StrikeCraftMissionType.COMBAT_SPACE_PATROL) {
            return DivisionQuickDeck.ESCORT_CARRIER;
        }
        return null;
    }

    public static String formatQuickRolePillLabel(PermanentDivisionSpec spec) {
        DivisionQuickRole matched = resolveMatchingQuickRole(spec);
        if (matched != null) {
            return matched.shortName;
        }
        if (spec != null && spec.customMainMission == DivisionMissionType.CARRIER_STANDOFF) {
            return "Carrier Hold";
        }
        return "Custom";
    }

    public static String formatQuickDeckPillLabel(PermanentDivisionSpec spec) {
        DivisionQuickDeck matched = resolveMatchingQuickDeck(spec);
        if (matched == DivisionQuickDeck.DOCTRINE_DEFAULT) {
            if (spec != null && spec.archetype != null) {
                if (spec.archetype.isCarrierArchetype()) return "Auto (Carrier)";
                if (spec.archetype == DivisionArchetype.BATTLECARRIER_DIVISION) return "Auto (Battlecarrier)";
            }
            return "Auto (Not Carrier)";
        }
        return matched != null ? matched.shortName : "Custom";
    }

    public static void applyQuickRoleToSpec(
            PermanentDivisionSpec spec,
            CampaignFleetAPI fleet,
            DivisionQuickRole role) {
        if (spec == null || role == null) return;
        if (role != DivisionQuickRole.DOCTRINE_DEFAULT
                && spec.archetype != null
                && spec.archetype.isCarrierArchetype()) {
            // Switching a carrier division onto an active surface warship role turns it into a Battlecarrier
            // so the hull executes the chosen role instead of remaining in rear Carrier Standoff.
            spec.archetype = DivisionArchetype.BATTLECARRIER_DIVISION;
            if (spec.customStrikeCraftOpeningMission == null) {
                spec.customStrikeCraftOpeningMission = StrikeCraftMissionType.ARMED_RECONNAISSANCE;
            }
            if (spec.customStrikeCraftMainMission == null) {
                spec.customStrikeCraftMainMission = StrikeCraftMissionType.DECKLOAD_STRIKE;
            }
        }
        switch (role) {
            case DOCTRINE_DEFAULT:
                spec.customLineStation = null;
                spec.customOpeningMission = null;
                spec.customMainMission = null;
                spec.customMovementStickiness = null;
                spec.customStandoffManual = false;
                recalculateDivisionStandoffRangeFromGuns(spec, fleet);
                break;
            case BATTLE_LINE:
                spec.customLineStation = LineStation.VAN;
                spec.customOpeningMission = DivisionMissionType.CLOSE_ACTION;
                spec.customMainMission = DivisionMissionType.CLOSE_ACTION;
                spec.customMovementStickiness = DivisionMovementStickiness.STRICT;
                spec.customStandoffManual = false;
                recalculateDivisionStandoffRangeFromGuns(spec, fleet);
                break;
            case AGGRESSIVE: {
                spec.customLineStation = LineStation.VAN;
                spec.customOpeningMission = DivisionMissionType.CLOSE_ACTION;
                spec.customMainMission = DivisionMissionType.CLOSE_ACTION;
                spec.customMovementStickiness = DivisionMovementStickiness.LOOSE;
                int baseGun = computeDivisionLongestGunRange(spec, fleet);
                if (baseGun <= 0) {
                    DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(spec.archetype);
                    baseGun = getFleetDoctrine().getStandoffRange(dt);
                }
                int brawlRange = Math.max(
                        MIN_STANDOFF_RANGE_UNITS,
                        Math.min(getEffectiveMaxStandoffRangeUnits(), Math.round((baseGun * 0.65f) / 25f) * 25));
                spec.customStandoffRange = brawlRange;
                spec.customStandoffManual = true;
                break;
            }
            case STANDOFF:
                spec.customLineStation = LineStation.CENTER;
                spec.customOpeningMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
                spec.customMainMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
                spec.customMovementStickiness = DivisionMovementStickiness.BALANCED;
                spec.customStandoffManual = false;
                recalculateDivisionStandoffRangeFromGuns(spec, fleet);
                break;
            case ARTILLERY: {
                spec.customLineStation = LineStation.REAR;
                spec.customOpeningMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
                spec.customMainMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
                spec.customMovementStickiness = DivisionMovementStickiness.STRICT;
                int maxWep = computeDivisionLongestGunRange(spec, fleet, true);
                int gunOnly = computeDivisionLongestGunRange(spec, fleet, false);
                if (maxWep > gunOnly) {
                    spec.customStandoffRange = maxWep;
                    spec.customStandoffManual = true;
                } else {
                    spec.customStandoffManual = false;
                    recalculateDivisionStandoffRangeFromGuns(spec, fleet);
                }
                break;
            }
            case SCOUT:
                spec.customLineStation = LineStation.VAN;
                spec.customOpeningMission = DivisionMissionType.SCOUTING;
                spec.customMainMission = DivisionMissionType.SCOUTING;
                spec.customMovementStickiness = DivisionMovementStickiness.LOOSE;
                spec.customStandoffManual = false;
                recalculateDivisionStandoffRangeFromGuns(spec, fleet);
                break;
            case HARASSER:
                spec.customLineStation = LineStation.VAN;
                spec.customOpeningMission = DivisionMissionType.SCOUTING;
                spec.customMainMission = DivisionMissionType.ENVELOPMENT;
                spec.customMovementStickiness = DivisionMovementStickiness.LOOSE;
                spec.customStandoffManual = false;
                recalculateDivisionStandoffRangeFromGuns(spec, fleet);
                break;
            case HUNTER_KILLER:
                spec.customLineStation = LineStation.VAN;
                spec.customOpeningMission = DivisionMissionType.SCOUTING;
                spec.customMainMission = DivisionMissionType.TOT_ALPHA_STRIKE;
                spec.customMovementStickiness = DivisionMovementStickiness.BALANCED;
                spec.customStandoffManual = false;
                recalculateDivisionStandoffRangeFromGuns(spec, fleet);
                break;
            case SCREEN_ESCORT:
                spec.customLineStation = LineStation.CENTER;
                spec.customOpeningMission = DivisionMissionType.SCREEN;
                spec.customMainMission = DivisionMissionType.SCREEN;
                spec.customMovementStickiness = DivisionMovementStickiness.BALANCED;
                spec.customStandoffManual = false;
                recalculateDivisionStandoffRangeFromGuns(spec, fleet);
                break;
        }
    }

    public static void applyQuickDeckToSpec(
            PermanentDivisionSpec spec,
            CampaignFleetAPI fleet,
            DivisionQuickDeck deck) {
        if (spec == null || deck == null) return;
        switch (deck) {
            case DOCTRINE_DEFAULT:
                spec.customStrikeCraftOpeningMission = null;
                spec.customStrikeCraftMainMission = null;
                spec.customStrikeCraftCapReserve = null;
                if (spec.customMainMission == DivisionMissionType.CARRIER_STANDOFF) {
                    spec.customLineStation = null;
                    spec.customOpeningMission = null;
                    spec.customMainMission = null;
                }
                if (!spec.customStandoffManual) {
                    if (spec.archetype != null && spec.archetype.isCarrierArchetype()) {
                        spec.customStandoffRange = null;
                    } else {
                        recalculateDivisionStandoffRangeFromGuns(spec, fleet);
                    }
                }
                break;
            case NOT_A_CARRIER:
                if (spec.archetype != null && spec.archetype.hasStrikeCraftCapability()) {
                    spec.archetype = inferSurfaceArchetypeForMembers(spec, fleet);
                }
                if (spec.customMainMission == DivisionMissionType.CARRIER_STANDOFF
                        || (spec.customMainMission != null && spec.customMainMission.isCarrierMission)) {
                    spec.customLineStation = null;
                    spec.customOpeningMission = null;
                    spec.customMainMission = null;
                }
                if (spec.customOpeningMission == DivisionMissionType.CARRIER_STANDOFF
                        || (spec.customOpeningMission != null && spec.customOpeningMission.isCarrierMission)) {
                    spec.customOpeningMission = null;
                }
                spec.customStrikeCraftOpeningMission = StrikeCraftMissionType.COMBAT_SPACE_PATROL;
                spec.customStrikeCraftMainMission = StrikeCraftMissionType.COMBAT_SPACE_PATROL;
                if (!spec.customStandoffManual) {
                    recalculateDivisionStandoffRangeFromGuns(spec, fleet);
                }
                break;
            case BATTLECARRIER:
                spec.archetype = DivisionArchetype.BATTLECARRIER_DIVISION;
                if (spec.customMainMission == null
                        || spec.customMainMission == DivisionMissionType.CARRIER_STANDOFF
                        || spec.customMainMission.isCarrierMission) {
                    spec.customLineStation = LineStation.VAN;
                    spec.customOpeningMission = DivisionMissionType.CLOSE_ACTION;
                    spec.customMainMission = DivisionMissionType.CLOSE_ACTION;
                }
                spec.customStrikeCraftOpeningMission = StrikeCraftMissionType.ARMED_RECONNAISSANCE;
                spec.customStrikeCraftMainMission = StrikeCraftMissionType.DECKLOAD_STRIKE;
                if (!spec.customStandoffManual) {
                    recalculateDivisionStandoffRangeFromGuns(spec, fleet);
                }
                break;
            case FLEET_CARRIER:
                if (spec.archetype == null
                        || !spec.archetype.isCarrierArchetype()
                        || spec.archetype == DivisionArchetype.ESCORT_CARRIER_DIVISION) {
                    spec.archetype = inferCarrierArchetypeForMembers(spec, fleet);
                }
                spec.customLineStation = LineStation.REAR;
                spec.customOpeningMission = DivisionMissionType.CARRIER_STANDOFF;
                spec.customMainMission = DivisionMissionType.CARRIER_STANDOFF;
                spec.customStrikeCraftOpeningMission = StrikeCraftMissionType.ARMED_RECONNAISSANCE;
                spec.customStrikeCraftMainMission = StrikeCraftMissionType.DECKLOAD_STRIKE;
                spec.customStandoffManual = false;
                spec.customStandoffRange = null;
                break;
            case ESCORT_CARRIER:
                spec.archetype = DivisionArchetype.ESCORT_CARRIER_DIVISION;
                spec.customLineStation = LineStation.REAR;
                spec.customOpeningMission = DivisionMissionType.CARRIER_STANDOFF;
                spec.customMainMission = DivisionMissionType.CARRIER_STANDOFF;
                spec.customStrikeCraftOpeningMission = StrikeCraftMissionType.COMBAT_SPACE_PATROL;
                spec.customStrikeCraftMainMission = StrikeCraftMissionType.COMBAT_SPACE_PATROL;
                spec.customStandoffManual = false;
                spec.customStandoffRange = null;
                break;
        }
    }

    public static void applyDivisionQuickRole(String divisionId, final DivisionQuickRole role) {
        if (divisionId == null || role == null) return;
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                applyQuickRoleToSpec(spec, fleet, role);
            }
        });
    }

    public static void applyDivisionQuickDeck(String divisionId, final DivisionQuickDeck deck) {
        if (divisionId == null || deck == null) return;
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                applyQuickDeckToSpec(spec, fleet, deck);
            }
        });
    }

    public static void updateIndividualDivisionVoidstrikeTargetingBias(
            String divisionId,
            final VoidstrikeTargetingBias bias) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customVoidstrikeTargetingBias = bias;
            }
        });
    }

    public static void updateIndividualDivisionVoidstrikeFocusCarriers(
            String divisionId,
            final Boolean focusCarriers) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customVoidstrikeFocusCarriers = focusCarriers;
            }
        });
    }

    public static void updateIndividualDivisionVoidstrikeObservance(
            String divisionId,
            final VoidstrikeTargetObservance observance) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customVoidstrikeObservance = observance;
            }
        });
    }

    public static void updateIndividualDivisionVoidstrikeCoordination(
            String divisionId,
            final VoidstrikeCoordinationMode coordination) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customVoidstrikeCoordination = coordination;
            }
        });
    }

    public static void updateIndividualDivisionVoidstrikeAttackGeometry(
            String divisionId,
            final VoidstrikeAttackGeometry geometry) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customVoidstrikeAttackGeometry = geometry;
            }
        });
    }

    public static void updateIndividualDivisionVoidstrikeSplitMode(
            String divisionId,
            final VoidstrikeSplitMode splitMode) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customVoidstrikeSplitMode = splitMode;
            }
        });
    }

    public static void updateIndividualDivisionVoidstrikeGunneryMode(
            String divisionId,
            final VoidstrikeGunneryMode gunneryMode) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customVoidstrikeGunneryMode = gunneryMode;
            }
        });
    }

    public static void updateIndividualDivisionVoidstrikeAoaCorrection(
            String divisionId,
            final VoidstrikeAoaCorrection aoaCorrection) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customVoidstrikeAoaCorrection = aoaCorrection;
            }
        });
    }

    public static void updateIndividualDivisionShipSkillCoordination(
            String divisionId,
            final ShipSkillCoordinationMode coordination) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customShipSkillCoordination = coordination;
            }
        });
    }

    public static void updateIndividualDivisionRammingMode(
            String divisionId,
            final DivisionRammingMode mode) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customRammingMode = mode;
            }
        });
    }

    public static void updateIndividualDivisionRammingTargetScope(
            String divisionId,
            final DivisionRammingTargetScope scope) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customRammingTargetScope = scope;
            }
        });
    }

    public static void updateIndividualDivisionMaxRammingSlots(
            String divisionId,
            final Integer slots) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customMaxRammingSlots = (slots != null)
                        ? Math.max(MIN_RAMMING_ATTACK_SLOTS, Math.min(MAX_RAMMING_ATTACK_SLOTS, slots))
                        : null;
            }
        });
    }

    public static void updateIndividualDivisionFighterFormation(
            String divisionId,
            final SquadronFormation formation) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customFighterFormation = formation;
            }
        });
    }

    public static void updateIndividualDivisionFighterVoidstrikeFormation(
            String divisionId,
            final SquadronFormation formation) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customFighterVoidstrikeFormation = formation;
            }
        });
    }

    public static void updateIndividualDivisionBomberFormation(
            String divisionId,
            final SquadronFormation formation) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customBomberFormation = formation;
            }
        });
    }

    public static void updateIndividualDivisionBomberVoidstrikeFormation(
            String divisionId,
            final SquadronFormation formation) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customBomberVoidstrikeFormation = formation;
            }
        });
    }

    public static void updateIndividualDivisionVoidstrikeSquadronDepth(
            String divisionId,
            final Integer depth) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customVoidstrikeSquadronDepth = (depth != null)
                        ? Math.max(MIN_VOIDSTRIKE_SQUADRON_DEPTH, Math.min(MAX_VOIDSTRIKE_SQUADRON_DEPTH, depth))
                        : null;
            }
        });
    }

    public static void updateIndividualDivisionVoidstrikeSquadronWidth(
            String divisionId,
            final Integer width) {
        mutatePlayerDivisionSpec(divisionId, new DivisionSpecMutator() {
            @Override
            public void mutate(PermanentDivisionSpec spec, CampaignFleetAPI fleet) {
                spec.customVoidstrikeSquadronWidth = (width != null)
                        ? Math.max(MIN_VOIDSTRIKE_SQUADRON_WIDTH, Math.min(MAX_VOIDSTRIKE_SQUADRON_WIDTH, width))
                        : null;
            }
        });
    }

    /**
     * Shared combat DivisionState builder from a campaign PermanentDivisionSpec and its deployed ShipCapabilityProfiles.
     */
    public static DivisionState instantiateCombatDivisionFromSpec(
            BattleGroupState battleGroup,
            PermanentDivisionSpec spec,
            List<ShipCapabilityProfile> members,
            int divisionNumber) {
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
        String resolvedDisplayName = DivisionNomenclature.resolveDynamicDivisionDisplayName(
                battleGroup.nomenclatureCategory,
                spec.archetype,
                divisionNumber,
                spec.name);
        CommanderProfile cmdr = new CommanderProfile(leaderShip.getCaptain(), resolvedDisplayName + " Cmdr");
        DivisionState combatDiv = new DivisionState(
                spec.id,
                battleGroup.side,
                divisionNumber,
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
        return combatDiv;
    }

    /**
     * Loads the player's permanent divisions from campaign persistent data and prunes any
     * ship IDs that are no longer in the player's fleet.
     * Never forcibly assigns player ships to divisions automatically.
     */
    private static int computePlayerFleetSignature(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.getFleetData() == null) return 0;
        List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
        if (members == null) return 0;
        int h = members.size();
        for (int i = 0, n = members.size(); i < n; i++) {
            FleetMemberAPI m = members.get(i);
            if (m == null) continue;
            h = 31 * h + (m.getId() != null ? m.getId().hashCode() : i);
            h = 31 * h + (m.isFlagship() ? 1 : 0);
            h = 31 * h + (m.isMothballed() ? 2 : 0);
            if (m.getCaptain() != null && m.getCaptain().getId() != null) {
                h = 31 * h + m.getCaptain().getId().hashCode();
            }
        }
        return h;
    }

    public static List<PermanentDivisionSpec> ensureInitializedForPlayerFleet() {
        MissionScenarioBundle missionBundle = resolveActiveMissionScenario();
        if (missionBundle != null) {
            return new ArrayList<>(missionBundle.playerDivisions);
        }
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPlayerFleet() == null) {
            return new ArrayList<>();
        }

        CampaignFleetAPI playerFleet = sector.getPlayerFleet();
        Map<String, Object> pdata = sector.getPersistentData();
        Object raw = pdata != null ? pdata.get(PERSISTENT_DIVISIONS_KEY) : null;
        if (raw != null && raw == cachedSpecsRawRef && cachedSpecsList != null
                && BattlefieldFirepowerEvaluator.isRealCombatEngine(Global.getCombatEngine())) {
            // During active combat, campaign fleet membership does not change frame-to-frame; avoid allocating
            // fleet.getFleetData().getMembersListCopy() on every UI frame while the Deployment Dialog is open.
            return new ArrayList<>(cachedSpecsList);
        }
        int fleetSig = computePlayerFleetSignature(playerFleet);
        if (raw != null && raw == cachedSpecsRawRef && fleetSig == cachedSpecsFleetSig && cachedSpecsList != null) {
            return new ArrayList<>(cachedSpecsList);
        }

        List<PermanentDivisionSpec> specs = loadSpecsFromPersistentData(pdata);
        pruneRemovedFleetMembers(specs, playerFleet);
        sortDivisionsByWeightClassAndRole(specs, playerFleet);
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

        List<FleetMemberAPI> rawUnassigned = getUnassignedCombatMembers(fleet, specs);
        if (rawUnassigned.isEmpty()) return;

        List<FleetMemberAPI> unassigned = new ArrayList<>();
        for (FleetMemberAPI m : rawUnassigned) {
            if (classifyFleetMemberArchetype(m, doctrine) != null) {
                unassigned.add(m);
            }
        }
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
            DivisionArchetype desiredArch = classifyFleetMemberArchetype(m, doctrine);
            if (desiredArch == null) continue;
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
                DivisionArchetype arch = classifyFleetMemberArchetype(seedMembers.get(0), doctrine);
                if (arch == null) break;
                PermanentDivisionSpec newSpec = createNewDivisionInternal(specs, pdata, arch);
                for (FleetMemberAPI sm : seedMembers) {
                    newSpec.memberIds.add(sm.getId());
                }

                for (int i = 0; i < unassigned.size() && newSpec.memberIds.size() < MAX_DIVISION_SIZE; ) {
                    FleetMemberAPI cand = unassigned.get(i);
                    DoctrineHullCategory cCat = classifyHullCategory(cand);
                    int cMax = doctrine.getMax(cCat);
                    if (cMax > 0 && countCategoryInDivision(newSpec, cCat, fleetById) < cMax
                            && (classifyFleetMemberArchetype(cand, doctrine) == arch || doctrine.getMin(cCat) > 0)) {
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
        // Sort unassigned by Weight Class & Role first (Heavy Capital -> Cruiser -> Destroyer -> Frigate -> Non-Combatant),
        // and within the same weight class/role prefer ships with commanders so they lead new divisions!
        final FleetDoctrineConfig sortDocRef = doctrine;
        Collections.sort(unassigned, new Comparator<FleetMemberAPI>() {
            @Override
            public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                int rA = getArchetypeWeightAndRoleSortOrder(classifyFleetMemberArchetype(a, sortDocRef));
                int rB = getArchetypeWeightAndRoleSortOrder(classifyFleetMemberArchetype(b, sortDocRef));
                if (rA != rB) return Integer.compare(rA, rB);
                boolean aCmd = a.getCaptain() != null && !a.getCaptain().isDefault();
                boolean bCmd = b.getCaptain() != null && !b.getCaptain().isDefault();
                if (aCmd != bCmd) return aCmd ? -1 : 1;
                return Float.compare(b.getDeploymentPointsCost(), a.getDeploymentPointsCost());
            }
        });
        while (!unassigned.isEmpty()) {
            FleetMemberAPI lead = unassigned.remove(0);
            DoctrineHullCategory leadCat = classifyHullCategory(lead);
            DivisionArchetype arch = classifyFleetMemberArchetype(lead, doctrine);
            if (arch == null) continue;
            int effectiveCatMax = Math.min(MAX_DIVISION_SIZE, Math.max(1, doctrine.getMax(leadCat)));

            PermanentDivisionSpec newSpec = createNewDivisionInternal(specs, pdata, arch);
            newSpec.memberIds.add(lead.getId());

            // Prefer filling remaining slots with non-commanded ships first if other same-category ships have commanders to lead the next division
            for (int pass = 0; pass < 2 && newSpec.memberIds.size() < MAX_DIVISION_SIZE; pass++) {
                for (int i = 0; i < unassigned.size() && newSpec.memberIds.size() < MAX_DIVISION_SIZE; ) {
                    FleetMemberAPI cand = unassigned.get(i);
                    DoctrineHullCategory cCat = classifyHullCategory(cand);
                    DivisionArchetype cArch = classifyFleetMemberArchetype(cand, doctrine);
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
            autoEnableRammingIfDivisionHasRamShips(s, fleet);
        }
        sortDivisionsByWeightClassAndRole(specs, fleet);
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
            autoEnableRammingIfDivisionHasRamShips(created, sector.getPlayerFleet());
        }
        sortDivisionsByWeightClassAndRole(specs, sector.getPlayerFleet());
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
            if (spec.dormantMembers != null) spec.dormantMembers.remove(memberId);
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
            autoEnableRammingIfDivisionHasRamShips(target, fleet);
        }
        sortDivisionsByWeightClassAndRole(specs, fleet);
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
                if (spec.dormantMembers != null) spec.dormantMembers.remove(memberId);
                ensureValidFlagship(spec, fleet);
                recalculateDivisionStandoffRangeFromGuns(spec, fleet);
                break;
            }
        }
        sortDivisionsByWeightClassAndRole(specs, fleet);
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

    /**
     * Swaps or reorders two ships within the same permanent division when dragging-and-dropping
     * one ship tile onto another ship tile in the Division Editor. If dropped onto the current
     * division flagship tile (or slot 0), promotes the dragged ship to division flagship.
     */
    public static void swapOrReorderShipsInDivision(
            String divisionId,
            String draggedMemberId,
            String targetMemberId) {
        SectorAPI sector = Global.getSector();
        if (sector == null || divisionId == null || draggedMemberId == null || targetMemberId == null) return;
        if (draggedMemberId.equals(targetMemberId)) return;
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();

        for (PermanentDivisionSpec spec : specs) {
            if (divisionId.equals(spec.id)) {
                int idxFrom = spec.memberIds.indexOf(draggedMemberId);
                int idxTo = spec.memberIds.indexOf(targetMemberId);
                if (idxFrom >= 0 && idxTo >= 0 && idxFrom != idxTo) {
                    Collections.swap(spec.memberIds, idxFrom, idxTo);
                    if (targetMemberId.equals(spec.flagshipMemberId) || idxTo == 0) {
                        spec.flagshipMemberId = draggedMemberId;
                    } else if (draggedMemberId.equals(spec.flagshipMemberId) && idxFrom == 0) {
                        spec.flagshipMemberId = targetMemberId;
                    }
                }
                break;
            }
        }
        saveSpecsToPersistentData(pdata, specs);
    }

    /**
     * Canonical sort order by Weight Class and Role, from Heavy Combat at the top (0)
     * to Non-Combatant Auxiliary at the bottom (90).
     */
    public static int getArchetypeWeightAndRoleSortOrder(DivisionArchetype arch) {
        if (arch == null) return 50;
        switch (arch) {
            // 1. Capital Weight Class (Heavy Combat - Top)
            case BATTLESHIP_DIVISION:         return 10;
            case BATTLECRUISER_DIVISION:      return 11;
            case MISSILE_BATTLESHIP_DIVISION: return 12;
            case BATTLECARRIER_DIVISION:      return 13;
            case FLEET_CARRIER_DIVISION:      return 14;
            case CARRIER_GROUP:               return 15;
            // 2. Cruiser Weight Class (Medium-Heavy Combat)
            case CRUISER_DIVISION:            return 20;
            case STRIKE_CRUISER_DIVISION:     return 21;
            case MISSILE_CRUISER_DIVISION:    return 22;
            case PHASE_CRUISER_DIVISION:      return 23;
            case LIGHT_CARRIER_DIVISION:      return 24;
            // 3. Destroyer Weight Class (Medium-Light Combat)
            case DESTROYER_DIVISION:          return 30;
            case STRIKE_DESTROYER_DIVISION:   return 31;
            case MISSILE_DESTROYER_DIVISION:  return 32;
            case ESCORT_CARRIER_DIVISION:     return 33;
            // 4. Frigate & Phase Weight Class (Light / Specialist Combat)
            case FRIGATE_DIVISION:            return 40;
            case STRIKE_FRIGATE_DIVISION:     return 41;
            case MISSILE_FRIGATE_DIVISION:    return 42;
            case PHASE_DIVISION:              return 43;
            case MISSILE_PHASE_DIVISION:      return 44;
            // 5. General / Mixed Force
            case FORCE_DIVISION:              return 50;
            // 6. Non-Combatant / Auxiliary (Bottom)
            case SERVICE_GROUP:               return 90;
            default:                          return 50;
        }
    }

    /**
     * Sorts permanent divisions in-place by weight class and role from heavy combat at the top
     * to non-combatant auxiliary (Service Group / civilian) at the bottom.
     */
    public static void sortDivisionsByWeightClassAndRole(
            List<PermanentDivisionSpec> specs,
            CampaignFleetAPI fleet) {
        if (specs == null || specs.size() <= 1) return;
        final Map<String, FleetMemberAPI> byId = mapFleetMembersById(fleet);
        final FleetDoctrineConfig doctrine = getFleetDoctrine();

        Collections.sort(specs, new Comparator<PermanentDivisionSpec>() {
            @Override
            public int compare(PermanentDivisionSpec a, PermanentDivisionSpec b) {
                if (a == b) return 0;
                if (a == null) return 1;
                if (b == null) return -1;

                int rankA = resolveEffectiveDivisionSortRank(a, byId, doctrine);
                int rankB = resolveEffectiveDivisionSortRank(b, byId, doctrine);
                if (rankA != rankB) {
                    return Integer.compare(rankA, rankB);
                }

                // Within the same weight class & role, keep Flagship Division first
                boolean flagA = containsFleetFlagship(a, byId);
                boolean flagB = containsFleetFlagship(b, byId);
                if (flagA != flagB) {
                    return flagA ? -1 : 1;
                }

                // Keep non-empty divisions above empty divisions of the same archetype
                boolean emptyA = (a.memberIds == null || a.memberIds.isEmpty());
                boolean emptyB = (b.memberIds == null || b.memberIds.isEmpty());
                if (emptyA != emptyB) {
                    return emptyA ? 1 : -1;
                }

                // Preserve stable division sequence number (e.g. BatDiv 1 before BatDiv 2)
                int seqA = extractTrailingNumber(a.name, extractTrailingNumber(a.id, 999));
                int seqB = extractTrailingNumber(b.name, extractTrailingNumber(b.id, 999));
                if (seqA != seqB) {
                    return Integer.compare(seqA, seqB);
                }
                return String.valueOf(a.id).compareTo(String.valueOf(b.id));
            }
        });
    }

    private static int resolveEffectiveDivisionSortRank(
            PermanentDivisionSpec spec,
            Map<String, FleetMemberAPI> byId,
            FleetDoctrineConfig doctrine) {
        if (spec == null) return 99;
        DivisionArchetype arch = spec.archetype != null ? spec.archetype : DivisionArchetype.FORCE_DIVISION;
        if (arch == DivisionArchetype.SERVICE_GROUP) {
            return 90;
        }
        if (byId != null && !byId.isEmpty() && spec.memberIds != null && !spec.memberIds.isEmpty()) {
            boolean allCivilian = true;
            int bestMemberRank = Integer.MAX_VALUE;
            for (String mId : spec.memberIds) {
                FleetMemberAPI m = byId.get(mId);
                if (m == null) continue;
                DoctrineHullCategory cat = classifyHullCategory(m);
                if (cat != DoctrineHullCategory.FREIGHTER
                        && cat != DoctrineHullCategory.TANKER
                        && cat != DoctrineHullCategory.TRANSPORT
                        && !m.isCivilian()) {
                    allCivilian = false;
                }
                DivisionArchetype mArch = classifyFleetMemberArchetype(m, doctrine);
                if (mArch != null) {
                    bestMemberRank = Math.min(bestMemberRank, getArchetypeWeightAndRoleSortOrder(mArch));
                }
            }
            if (allCivilian) {
                return 90;
            }
            if (arch == DivisionArchetype.FORCE_DIVISION && bestMemberRank < Integer.MAX_VALUE) {
                return bestMemberRank;
            }
        }
        return getArchetypeWeightAndRoleSortOrder(arch);
    }

    private static boolean containsFleetFlagship(PermanentDivisionSpec spec, Map<String, FleetMemberAPI> byId) {
        if (spec == null || spec.memberIds == null || byId == null) return false;
        for (String mId : spec.memberIds) {
            FleetMemberAPI m = byId.get(mId);
            if (m != null && m.isFlagship()) return true;
        }
        return false;
    }

    private static int extractTrailingNumber(String text, int fallback) {
        if (text == null) return fallback;
        int end = text.length() - 1;
        while (end >= 0 && !Character.isDigit(text.charAt(end))) {
            end--;
        }
        if (end < 0) return fallback;
        int start = end;
        while (start >= 0 && Character.isDigit(text.charAt(start))) {
            start--;
        }
        try {
            return Integer.parseInt(text.substring(start + 1, end + 1));
        } catch (NumberFormatException e) {
            return fallback;
        }
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

        reDesignateDivisionInList(specs, divisionId, newName, newArchetype, newDoctrine);
        sortDivisionsByWeightClassAndRole(specs, sector.getPlayerFleet());
        saveSpecsToPersistentData(pdata, specs);
    }

    /**
     * Updates a division's name, archetype, and/or doctrine within {@code specs}.
     * Per Task B: If the player re-designates a division ({@code newArchetype != spec.archetype}) while its name
     * is default as per the player's chosen nomenclature, the division is renamed according to that same nomenclature
     * for the new archetype. If the name is custom (even by one character), the division is not renamed.
     */
    public static void reDesignateDivisionInList(
            List<PermanentDivisionSpec> specs,
            String divisionId,
            String newName,
            DivisionArchetype newArchetype,
            DoctrineOption newDoctrine) {
        if (specs == null || divisionId == null) return;
        for (PermanentDivisionSpec spec : specs) {
            if (spec != null && divisionId.equals(spec.id)) {
                boolean hasExplicitName = (newName != null && !newName.trim().isEmpty());
                if (hasExplicitName) {
                    spec.name = newName.trim();
                }
                if (newArchetype != null) {
                    boolean archetypeChanged = (newArchetype != spec.archetype);
                    DivisionArchetype oldArchetype = spec.archetype;
                    spec.archetype = newArchetype;
                    if (archetypeChanged && !hasExplicitName) {
                        DivisionNomenclature.PlayerNamingConfig cfg = DivisionNomenclature.getPlayerNamingConfig();
                        if (DivisionNomenclature.isDefaultDivisionNameForConfig(spec.name, oldArchetype, cfg)) {
                            spec.name = findNextAvailablePlayerDivisionName(specs, newArchetype, spec.id);
                        }
                    }
                }
                if (newDoctrine != null) {
                    spec.doctrine = newDoctrine;
                }
                break;
            }
        }
    }

    /**
     * Finds the lowest sequence number {@code seq >= 1} under the player's chosen nomenclature such that
     * {@code formatPlayerDivisionName(archetype, seq)} is not already used by another division in {@code specs}.
     */
    public static String findNextAvailablePlayerDivisionName(
            List<PermanentDivisionSpec> specs,
            DivisionArchetype archetype,
            String excludeDivisionId) {
        DivisionArchetype arch = (archetype != null) ? archetype : DivisionArchetype.FORCE_DIVISION;
        DivisionNomenclature.PlayerNamingConfig cfg = DivisionNomenclature.getPlayerNamingConfig();
        String firstCandidate = DivisionNomenclature.formatDivisionNameWithConfig(cfg, arch, 1);
        String secondCandidate = DivisionNomenclature.formatDivisionNameWithConfig(cfg, arch, 2);
        if (firstCandidate.equals(secondCandidate) || specs == null || specs.isEmpty()) {
            return firstCandidate;
        }
        for (int seq = 1; seq <= 999; seq++) {
            String candidate = DivisionNomenclature.formatDivisionNameWithConfig(cfg, arch, seq);
            boolean taken = false;
            for (PermanentDivisionSpec existing : specs) {
                if (existing == null) continue;
                if (excludeDivisionId != null && excludeDivisionId.equals(existing.id)) continue;
                if (existing.name != null && candidate.equalsIgnoreCase(existing.name.trim())) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return candidate;
            }
        }
        return firstCandidate;
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

    public static final String MISSION_SCENARIO_CUSTOM_DATA_KEY = "divisionai_showcase_mission_id";
    public static final int MISSION_SCENARIO_MIN_MAX_STRENGTH = 600;

    public static final class MissionScenarioBundle {
        public final String missionId;
        public final String playerFactionId;
        public final String enemyFactionId;
        public final DivisionNomenclature.NomenclatureCategory playerNomenclature;
        public final DivisionNomenclature.NomenclatureCategory enemyNomenclature;
        public FleetDoctrineConfig playerDoctrine;
        public FleetDoctrineConfig enemyDoctrine;
        public final List<PermanentDivisionSpec> playerDivisions = new ArrayList<>();
        public final List<PermanentDivisionSpec> enemyDivisions = new ArrayList<>();

        public MissionScenarioBundle(
                String missionId,
                String playerFactionId,
                String enemyFactionId,
                DivisionNomenclature.NomenclatureCategory playerNomenclature,
                DivisionNomenclature.NomenclatureCategory enemyNomenclature,
                FleetDoctrineConfig playerDoctrine,
                FleetDoctrineConfig enemyDoctrine,
                List<PermanentDivisionSpec> playerDivs,
                List<PermanentDivisionSpec> enemyDivs) {
            this.missionId = missionId;
            this.playerFactionId = playerFactionId;
            this.enemyFactionId = enemyFactionId;
            this.playerNomenclature = playerNomenclature;
            this.enemyNomenclature = enemyNomenclature;
            this.playerDoctrine = playerDoctrine != null ? deserializeDoctrineConfig(serializeDoctrineConfig(playerDoctrine)) : new FleetDoctrineConfig();
            this.enemyDoctrine = enemyDoctrine != null ? deserializeDoctrineConfig(serializeDoctrineConfig(enemyDoctrine)) : new FleetDoctrineConfig();
            if (playerDivs != null) {
                for (PermanentDivisionSpec s : playerDivs) {
                    if (s != null) this.playerDivisions.add(copyPermanentDivisionSpec(s, s.id, s.name));
                }
            }
            if (enemyDivs != null) {
                for (PermanentDivisionSpec s : enemyDivs) {
                    if (s != null) this.enemyDivisions.add(copyPermanentDivisionSpec(s, s.id, s.name));
                }
            }
        }

        public MissionScenarioBundle deepCopy() {
            return new MissionScenarioBundle(
                    missionId,
                    playerFactionId,
                    enemyFactionId,
                    playerNomenclature,
                    enemyNomenclature,
                    playerDoctrine,
                    enemyDoctrine,
                    playerDivisions,
                    enemyDivisions);
        }
    }

    private static final Map<String, MissionScenarioBundle> SCENARIO_TEMPLATE_BY_MISSION_ID = new LinkedHashMap<>();
    private static final Map<String, String> MISSION_ID_BY_MEMBER_ID = new HashMap<>();
    private static Object activeMissionEngineRef = null;
    private static MissionScenarioBundle activeWorkingScenario = null;

    public static FleetDoctrineConfig copyDoctrineConfig(FleetDoctrineConfig src) {
        return src != null ? deserializeDoctrineConfig(serializeDoctrineConfig(src)) : new FleetDoctrineConfig();
    }

    public static PermanentDivisionSpec copyPermanentDivisionSpec(
            PermanentDivisionSpec spec,
            String newId,
            String newName) {
        PermanentDivisionSpec copy = new PermanentDivisionSpec(newId, newName, spec.archetype);
        copy.flagshipMemberId = spec.flagshipMemberId;
        copy.memberIds.addAll(spec.memberIds);
        copy.doctrine = spec.doctrine;
        copy.customStationSpacing = spec.customStationSpacing;
        copy.customDivisionInterval = spec.customDivisionInterval;
        copy.customStandoffRange = spec.customStandoffRange;
        copy.customStandoffManual = spec.customStandoffManual;
        copy.customMovementStickiness = spec.customMovementStickiness;
        copy.customEngineTelegraph = spec.customEngineTelegraph;
        copy.customOpeningMission = spec.customOpeningMission;
        copy.customMainMission = spec.customMainMission;
        copy.customLineStation = spec.customLineStation;
        copy.customFormation = spec.customFormation;
        copy.customSetpieceAttack = spec.customSetpieceAttack;
        copy.customVoidstrikeObservance = spec.customVoidstrikeObservance;
        copy.customVoidstrikeCoordination = spec.customVoidstrikeCoordination;
        copy.customVoidstrikeAttackGeometry = spec.customVoidstrikeAttackGeometry;
        copy.customVoidstrikeSplitMode = spec.customVoidstrikeSplitMode;
        copy.customVoidstrikeGunneryMode = spec.customVoidstrikeGunneryMode;
        copy.customVoidstrikeAoaCorrection = spec.customVoidstrikeAoaCorrection;
        copy.customFighterFormation = spec.customFighterFormation;
        copy.customFighterVoidstrikeFormation = spec.customFighterVoidstrikeFormation;
        copy.customBomberFormation = spec.customBomberFormation;
        copy.customBomberVoidstrikeFormation = spec.customBomberVoidstrikeFormation;
        copy.customVoidstrikeSquadronDepth = spec.customVoidstrikeSquadronDepth;
        copy.customVoidstrikeSquadronWidth = spec.customVoidstrikeSquadronWidth;
        copy.customShipSkillCoordination = spec.customShipSkillCoordination;
        copy.customRammingMode = spec.customRammingMode;
        copy.customRammingTargetScope = spec.customRammingTargetScope;
        copy.customMaxRammingSlots = spec.customMaxRammingSlots;
        return copy;
    }

    public static void registerMissionScenario(MissionScenarioBundle bundle) {
        if (bundle == null || bundle.missionId == null) return;
        SCENARIO_TEMPLATE_BY_MISSION_ID.put(bundle.missionId, bundle.deepCopy());
        for (PermanentDivisionSpec s : bundle.playerDivisions) {
            if (s == null) continue;
            for (String mid : s.memberIds) {
                if (mid != null) MISSION_ID_BY_MEMBER_ID.put(mid, bundle.missionId);
            }
        }
        for (PermanentDivisionSpec s : bundle.enemyDivisions) {
            if (s == null) continue;
            for (String mid : s.memberIds) {
                if (mid != null) MISSION_ID_BY_MEMBER_ID.put(mid, bundle.missionId);
            }
        }
    }

    public static MissionScenarioBundle getRegisteredMissionScenarioTemplate(String missionId) {
        return missionId != null ? SCENARIO_TEMPLATE_BY_MISSION_ID.get(missionId) : null;
    }

    public static void activateMissionScenarioForEngine(com.fs.starfarer.api.combat.CombatEngineAPI engine, String missionId) {
        if (engine == null || missionId == null) return;
        if (engine.getCustomData() != null) {
            engine.getCustomData().put(MISSION_SCENARIO_CUSTOM_DATA_KEY, missionId);
        }
        MissionScenarioBundle template = SCENARIO_TEMPLATE_BY_MISSION_ID.get(missionId);
        if (template != null) {
            activeMissionEngineRef = engine;
            activeWorkingScenario = template.deepCopy();
            ensureMissionDeploymentPointCap(engine);
        }
    }

    private static volatile Object lastNonMissionEngineRef = null;

    public static void clearActiveMissionScenario() {
        activeMissionEngineRef = null;
        activeWorkingScenario = null;
        lastNonMissionEngineRef = null;
    }

    private static boolean hasPopulatedCombatFleetsForMissionCheck(com.fs.starfarer.api.combat.CombatEngineAPI engine) {
        if (engine == null) return false;
        for (int side = 0; side <= 1; side++) {
            try {
                com.fs.starfarer.api.combat.CombatFleetManagerAPI fm = engine.getFleetManager(side);
                if (fm == null) continue;
                List<FleetMemberAPI> res = fm.getReservesCopy();
                if (res != null && !res.isEmpty()) return true;
                List<FleetMemberAPI> dep = fm.getDeployedCopy();
                if (dep != null && !dep.isEmpty()) return true;
            } catch (Throwable ignored) {
            }
        }
        List<ShipAPI> ships = engine.getShips();
        return ships != null && !ships.isEmpty();
    }

    private static String detectMissionIdFromEngine(com.fs.starfarer.api.combat.CombatEngineAPI engine) {
        if (engine == null || SCENARIO_TEMPLATE_BY_MISSION_ID.isEmpty()) return null;
        try {
            if (engine.getCustomData() != null) {
                Object rawId = engine.getCustomData().get(MISSION_SCENARIO_CUSTOM_DATA_KEY);
                if (rawId instanceof String && SCENARIO_TEMPLATE_BY_MISSION_ID.containsKey(rawId)) {
                    return (String) rawId;
                }
            }
        } catch (Throwable ignored) {
        }
        for (int side = 0; side <= 1; side++) {
            try {
                com.fs.starfarer.api.combat.CombatFleetManagerAPI fm = engine.getFleetManager(side);
                if (fm == null) continue;
                List<FleetMemberAPI> res = fm.getReservesCopy();
                if (res != null) {
                    for (FleetMemberAPI m : res) {
                        if (m != null && m.getId() != null) {
                            String found = MISSION_ID_BY_MEMBER_ID.get(m.getId());
                            if (found != null) return found;
                        }
                    }
                }
                List<FleetMemberAPI> dep = fm.getDeployedCopy();
                if (dep != null) {
                    for (FleetMemberAPI m : dep) {
                        if (m != null && m.getId() != null) {
                            String found = MISSION_ID_BY_MEMBER_ID.get(m.getId());
                            if (found != null) return found;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            if (engine.getShips() != null) {
                for (ShipAPI s : engine.getShips()) {
                    if (s != null && s.getFleetMember() != null && s.getFleetMember().getId() != null) {
                        String found = MISSION_ID_BY_MEMBER_ID.get(s.getFleetMember().getId());
                        if (found != null) return found;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static MissionScenarioBundle resolveActiveMissionScenario() {
        com.fs.starfarer.api.combat.CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || !BattlefieldFirepowerEvaluator.isRealCombatEngine(engine)) {
            return null;
        }
        if (engine == activeMissionEngineRef && activeWorkingScenario != null) {
            return activeWorkingScenario;
        }
        if (engine == lastNonMissionEngineRef) {
            try {
                if (engine.getCustomData() == null
                        || !(engine.getCustomData().get(MISSION_SCENARIO_CUSTOM_DATA_KEY) instanceof String)) {
                    return null;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
        String detectedId = detectMissionIdFromEngine(engine);
        if (detectedId == null) {
            if (hasPopulatedCombatFleetsForMissionCheck(engine)) {
                lastNonMissionEngineRef = engine;
            }
            return null;
        }
        if (engine == activeMissionEngineRef
                && activeWorkingScenario != null
                && detectedId.equals(activeWorkingScenario.missionId)) {
            return activeWorkingScenario;
        }
        MissionScenarioBundle template = SCENARIO_TEMPLATE_BY_MISSION_ID.get(detectedId);
        if (template == null) {
            return null;
        }
        try {
            if (engine.getCustomData() != null) {
                engine.getCustomData().put(MISSION_SCENARIO_CUSTOM_DATA_KEY, detectedId);
            }
        } catch (Throwable ignored) {
        }
        activeMissionEngineRef = engine;
        activeWorkingScenario = template.deepCopy();
        ensureMissionDeploymentPointCap(engine);
        return activeWorkingScenario;
    }

    public static void ensureMissionDeploymentPointCap(com.fs.starfarer.api.combat.CombatEngineAPI engine) {
        if (engine == null) return;
        for (int side = 0; side <= 1; side++) {
            try {
                com.fs.starfarer.api.combat.CombatFleetManagerAPI fm = engine.getFleetManager(side);
                if (fm != null && fm.getMaxStrength() < MISSION_SCENARIO_MIN_MAX_STRENGTH) {
                    fm.setMaxStrength(MISSION_SCENARIO_MIN_MAX_STRENGTH);
                }
            } catch (Throwable ignored) {
            }
        }
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
        List<PermanentDivisionSpec> permSpecs = ensureInitializedForPlayerFleet();
        if (permSpecs.isEmpty()) {
            return unassignedPlayerHulls;
        }
        return applySpecsToBattleGroupInCombat(
                battleGroup,
                permSpecs,
                getFleetDoctrine(),
                unassignedPlayerHulls,
                newlyCreatedOut);
    }

    public static List<ShipCapabilityProfile> applyMissionEnemyDivisionsInCombat(
            BattleGroupState battleGroup,
            List<ShipCapabilityProfile> unassignedEnemyHulls,
            List<DivisionState> newlyCreatedOut) {

        if (battleGroup == null || battleGroup.side != 1 || unassignedEnemyHulls.isEmpty()) {
            return unassignedEnemyHulls;
        }
        MissionScenarioBundle bundle = resolveActiveMissionScenario();
        if (bundle == null || bundle.enemyDivisions.isEmpty()) {
            return unassignedEnemyHulls;
        }
        return applySpecsToBattleGroupInCombat(
                battleGroup,
                bundle.enemyDivisions,
                bundle.enemyDoctrine,
                unassignedEnemyHulls,
                newlyCreatedOut);
    }

    private static List<ShipCapabilityProfile> applySpecsToBattleGroupInCombat(
            BattleGroupState battleGroup,
            List<PermanentDivisionSpec> permSpecs,
            FleetDoctrineConfig doctrineCfg,
            List<ShipCapabilityProfile> unassignedHulls,
            List<DivisionState> newlyCreatedOut) {

        Map<String, PermanentDivisionSpec> memberToSpec = new HashMap<>();
        for (PermanentDivisionSpec spec : permSpecs) {
            for (String mId : spec.memberIds) {
                memberToSpec.put(mId, spec);
            }
        }

        List<ShipCapabilityProfile> leftoverUnassigned = new ArrayList<>();
        Map<String, List<ShipCapabilityProfile>> groupedByPermId = new LinkedHashMap<>();
        Map<String, PermanentDivisionSpec> specById = new HashMap<>();
        Map<String, Integer> zoneByGroupKey = new HashMap<>();

        com.fs.starfarer.api.combat.CombatEngineAPI engine = Global.getCombatEngine();
        boolean pursuitBattle = OtcAI.isPursuitBattle(engine, battleGroup);
        Map<String, Set<Integer>> zonesPerSpecId = new HashMap<>();
        if (pursuitBattle) {
            for (ShipCapabilityProfile prof : unassignedHulls) {
                String fmId = (prof.ship.getFleetMember() != null) ? prof.ship.getFleetMember().getId() : null;
                PermanentDivisionSpec spec = (fmId != null) ? memberToSpec.get(fmId) : null;
                if (spec == null) continue;
                int z = OtcAI.resolveShipPursuitSpawnZone(engine, prof.ship);
                Set<Integer> zSet = zonesPerSpecId.get(spec.id);
                if (zSet == null) {
                    zSet = new HashSet<>();
                    zonesPerSpecId.put(spec.id, zSet);
                }
                zSet.add(z);
                DivisionState existing = findExistingCombatDivisionById(battleGroup, spec.id);
                if (existing != null) {
                    zSet.add(existing.pursuitSpawnZone);
                }
            }
        }

        for (ShipCapabilityProfile prof : unassignedHulls) {
            String fmId = (prof.ship.getFleetMember() != null) ? prof.ship.getFleetMember().getId() : null;
            PermanentDivisionSpec spec = (fmId != null) ? memberToSpec.get(fmId) : null;
            if (spec == null) {
                leftoverUnassigned.add(prof);
                continue;
            }

            int shipZone = pursuitBattle ? OtcAI.resolveShipPursuitSpawnZone(engine, prof.ship) : 0;
            Set<Integer> specZones = zonesPerSpecId.get(spec.id);
            boolean splitAcrossZones = pursuitBattle && specZones != null && specZones.size() > 1;
            String groupKey = (splitAcrossZones && shipZone != 0)
                    ? (spec.id + (shipZone < 0 ? "#L" : "#R"))
                    : spec.id;

            // Check if this permanent division (or flank detachment) already exists in the active combat BattleGroupState
            DivisionState existingCombatDiv = findExistingCombatDivisionById(battleGroup, groupKey);
            if (existingCombatDiv != null && (!pursuitBattle || existingCombatDiv.pursuitSpawnZone == shipZone)) {
                existingCombatDiv.members.add(prof.ship);
                existingCombatDiv.profiles.add(prof);
                battleGroup.shipIdToDivision.put(prof.ship.getId(), existingCombatDiv);
                if (fmId.equals(spec.flagshipMemberId) || existingCombatDiv.flagship == null || !existingCombatDiv.flagship.isAlive()) {
                    existingCombatDiv.flagship = prof.ship;
                }
                DivisionAutoFormer.recomputeDivisionCapabilities(existingCombatDiv);
                applyDoctrineModifiersToCombatDivision(existingCombatDiv, spec, doctrineCfg);
                applyBattleFormationOverrideIfPresent(battleGroup, existingCombatDiv, spec.id, doctrineCfg);
                DivisionCombatLog.logReinforcementJoined(existingCombatDiv, prof.ship);
            } else {
                specById.put(groupKey, spec);
                zoneByGroupKey.put(groupKey, shipZone);
                List<ShipCapabilityProfile> list = groupedByPermId.get(groupKey);
                if (list == null) {
                    list = new ArrayList<>();
                    groupedByPermId.put(groupKey, list);
                }
                list.add(prof);
            }
        }

        int fallbackNumber = battleGroup.divisions.size() + 1;
        for (Map.Entry<String, List<ShipCapabilityProfile>> entry : groupedByPermId.entrySet()) {
            String groupKey = entry.getKey();
            PermanentDivisionSpec spec = specById.get(groupKey);
            List<ShipCapabilityProfile> members = entry.getValue();
            if (spec == null || members.isEmpty()) continue;

            int zone = zoneByGroupKey.containsKey(groupKey) ? zoneByGroupKey.get(groupKey) : 0;
            PermanentDivisionSpec effectiveSpec = spec;
            if (!groupKey.equals(spec.id)) {
                String suffix = (zone < 0) ? " (Left Flank)" : " (Right Flank)";
                effectiveSpec = copyPermanentDivisionSpec(spec, groupKey, spec.name + suffix);
            }

            DivisionState combatDiv = instantiateCombatDivisionFromSpec(
                    battleGroup, effectiveSpec, members, fallbackNumber++);
            combatDiv.pursuitSpawnZone = zone;
            combatDiv.fleetFormationWingSide = zone;
            if (zone != 0) {
                combatDiv.consentedWingSide = zone;
            }
            applyDoctrineModifiersToCombatDivision(combatDiv, effectiveSpec, doctrineCfg);
            applyBattleFormationOverrideIfPresent(battleGroup, combatDiv, spec.id, doctrineCfg);
            battleGroup.divisions.add(combatDiv);
            newlyCreatedOut.add(combatDiv);
        }

        return leftoverUnassigned;
    }

    public static void applyBattleFormationOverrideIfPresent(
            BattleGroupState battleGroup,
            DivisionState combatDiv,
            String baseSpecId,
            FleetDoctrineConfig doctrineCfg) {
        if (battleGroup == null || combatDiv == null || !battleGroup.battleDivisionFormationsHarmonized) {
            return;
        }
        DivisionFormation overrideFm = (baseSpecId != null)
                ? battleGroup.battleDivisionFormationOverrides.get(baseSpecId)
                : null;
        if (overrideFm != null) {
            combatDiv.doctrinalFormation = overrideFm;
        } else if (doctrineCfg != null) {
            DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(combatDiv.archetype);
            combatDiv.doctrinalFormation = doctrineCfg.getFormation(dt);
        }
        if (combatDiv.lastFormationChangeTime < -100f) {
            combatDiv.activeFormation = combatDiv.doctrinalFormation;
        }
    }

    public static void applyDoctrineModifiersToCombatDivision(DivisionState div, PermanentDivisionSpec spec) {
        applyDoctrineModifiersToCombatDivision(div, spec, getFleetDoctrine());
    }

    public static void applyDoctrineModifiersToCombatDivision(
            DivisionState div,
            PermanentDivisionSpec optionalSpec,
            FleetDoctrineConfig cfg) {
        if (div == null || cfg == null) return;
        DoctrineDivisionType dt = DoctrineDivisionType.forArchetype(div.archetype);
        div.stationSpacing = (optionalSpec != null)
                ? resolveEffectiveStationSpacing(optionalSpec, cfg)
                : cfg.getStationSpacing(dt);
        div.divisionInterval = (optionalSpec != null)
                ? resolveEffectiveDivisionInterval(optionalSpec, cfg)
                : cfg.getDivisionInterval(dt);
        div.openingPhaseDurationSeconds = cfg.getOpeningDurationSeconds();
        div.openingMission = (optionalSpec != null)
                ? resolveEffectiveOpeningMission(optionalSpec, cfg)
                : cfg.getOpeningMission(dt);
        div.mainMission = (optionalSpec != null)
                ? resolveEffectiveMainMission(optionalSpec, cfg)
                : cfg.getMainMission(dt);
        float rawConfiguredStandoff = (optionalSpec != null)
                ? resolveEffectiveStandoffRange(optionalSpec, cfg)
                : cfg.getStandoffRange(dt);
        if (!CombatPhysicsProfile.isGlobalScaledEnvironment()
                && optionalSpec != null
                && optionalSpec.customStandoffRange != null
                && !optionalSpec.customStandoffManual
                && optionalSpec.customStandoffRange >= 2600
                && div.archetype != null
                && !div.archetype.isCarrierArchetype()
                && !div.archetype.isMissileArchetype()) {
            float maxMemberGun = div.avgEngageRange;
            if (div.profiles != null) {
                for (int i = 0, n = div.profiles.size(); i < n; i++) {
                    ShipCapabilityProfile p = div.profiles.get(i);
                    if (p == null) continue;
                    if (p.mainBatteryGunRange > maxMemberGun) maxMemberGun = p.mainBatteryGunRange;
                    if (p.avgWeaponRange > maxMemberGun) maxMemberGun = p.avgWeaponRange;
                    if (p.ship != null && p.ship.getAllWeapons() != null) {
                        for (com.fs.starfarer.api.combat.WeaponAPI w : p.ship.getAllWeapons()) {
                            if (w == null || w.isDecorative() || w.getType() == WeaponType.MISSILE) continue;
                            if (w.hasAIHint(AIHints.PD_ONLY) || (w.hasAIHint(AIHints.PD) && !w.hasAIHint(AIHints.ANTI_FTR) && w.getRange() <= 550f)) continue;
                            if (w.getRange() > maxMemberGun) maxMemberGun = w.getRange();
                        }
                    }
                }
            }
            if (maxMemberGun > 350f && rawConfiguredStandoff > Math.max(cfg.getStandoffRange(dt) * 1.35f, maxMemberGun * 1.25f)) {
                rawConfiguredStandoff = Math.max(cfg.getStandoffRange(dt), maxMemberGun);
            }
        }
        // Spec 01 C2-INV-07: Only an explicit manual player Line Standoff override (`customStandoffManual == true`)
        // bypasses mission-coupled CLOSE_ACTION / live-envelope standoff scaling; auto-calculated longest-gun values
        // (e.g. 5,275u from a single HIL on an escort in a CLOSE_ACTION Battle Division) must never hold the division in the rear!
        boolean hasExplicitManualDivStandoff = optionalSpec != null
                && optionalSpec.customStandoffRange != null
                && optionalSpec.customStandoffManual;
        div.hasExplicitCustomStandoff = hasExplicitManualDivStandoff;
        div.standoffRange = adaptDoctrinalStandoffToLiveEnvelope(
                div, dt, rawConfiguredStandoff, hasExplicitManualDivStandoff);
        div.doctrinalMovementStickiness = (optionalSpec != null)
                ? resolveEffectiveMovementStickiness(optionalSpec, cfg)
                : cfg.getMovementStickiness(dt);
        div.activeMovementStickiness = div.doctrinalMovementStickiness;
        div.speedGovernorEnabled = cfg.isSpeedGovernorEnabled();
        div.doctrinalEngineTelegraph = (optionalSpec != null)
                ? resolveEffectiveEngineTelegraph(optionalSpec, cfg)
                : cfg.getEngineTelegraph(dt);
        if (!div.speedGovernorEnabled) {
            div.activeEngineTelegraph = DivisionEngineTelegraph.FLANK;
        } else if (div.doctrinalEngineTelegraph != DivisionEngineTelegraph.AUTO) {
            div.activeEngineTelegraph = div.doctrinalEngineTelegraph;
        }
        div.stationKeepingMult = (optionalSpec != null)
                ? resolveEffectiveStationKeepingMult(optionalSpec, cfg)
                : cfg.getStationKeepingMult(dt);
        div.strikeCraftOpeningMission = (optionalSpec != null)
                ? resolveEffectiveStrikeCraftOpeningMission(optionalSpec, cfg)
                : cfg.getStrikeCraftOpeningMission(dt);
        div.strikeCraftMainMission = (optionalSpec != null)
                ? resolveEffectiveStrikeCraftMainMission(optionalSpec, cfg)
                : cfg.getStrikeCraftMainMission(dt);
        div.strikeCraftCapReserve = (optionalSpec != null)
                ? resolveEffectiveStrikeCraftCapReserve(optionalSpec, cfg)
                : cfg.getStrikeCraftCapReserve(dt);
        div.lineStation = (optionalSpec != null)
                ? resolveEffectiveLineStation(optionalSpec, cfg)
                : cfg.getLineStation(dt);
        div.doctrinalFormation = (optionalSpec != null)
                ? resolveEffectiveFormation(optionalSpec, cfg)
                : cfg.getFormation(dt);
        if (div.lastFormationChangeTime < -100f) {
            div.activeFormation = div.doctrinalFormation;
        }
        div.voidstrikeTargetingBias = (optionalSpec != null)
                ? resolveEffectiveVoidstrikeTargetingBias(optionalSpec, cfg)
                : cfg.getVoidstrikeTargetingBias(dt);
        div.voidstrikeFocusCarriers = (optionalSpec != null)
                ? resolveEffectiveVoidstrikeFocusCarriers(optionalSpec, cfg)
                : cfg.isVoidstrikeFocusCarriers(dt);
        div.voidstrikeObservance = (optionalSpec != null)
                ? resolveEffectiveVoidstrikeObservance(optionalSpec, cfg)
                : cfg.getVoidstrikeObservance(dt);
        div.voidstrikeCoordination = (optionalSpec != null)
                ? resolveEffectiveVoidstrikeCoordination(optionalSpec, cfg)
                : cfg.getVoidstrikeCoordination(dt);
        div.voidstrikeAttackGeometry = (optionalSpec != null)
                ? resolveEffectiveVoidstrikeAttackGeometry(optionalSpec, cfg)
                : cfg.getVoidstrikeAttackGeometry(dt);
        div.voidstrikeSplitMode = (optionalSpec != null)
                ? resolveEffectiveVoidstrikeSplitMode(optionalSpec, cfg)
                : cfg.getVoidstrikeSplitMode(dt);
        div.voidstrikeGunneryMode = (optionalSpec != null)
                ? resolveEffectiveVoidstrikeGunneryMode(optionalSpec, cfg)
                : cfg.getVoidstrikeGunneryMode(dt);
        div.voidstrikeAoaCorrection = (optionalSpec != null)
                ? resolveEffectiveVoidstrikeAoaCorrection(optionalSpec, cfg)
                : cfg.getVoidstrikeAoaCorrection(dt);
        div.fighterFormation = (optionalSpec != null)
                ? resolveEffectiveFighterFormation(optionalSpec, cfg)
                : cfg.getFighterFormation(dt);
        div.fighterVoidstrikeFormation = (optionalSpec != null)
                ? resolveEffectiveFighterVoidstrikeFormation(optionalSpec, cfg)
                : cfg.getFighterVoidstrikeFormation(dt);
        div.bomberFormation = (optionalSpec != null)
                ? resolveEffectiveBomberFormation(optionalSpec, cfg)
                : cfg.getBomberFormation(dt);
        div.bomberVoidstrikeFormation = (optionalSpec != null)
                ? resolveEffectiveBomberVoidstrikeFormation(optionalSpec, cfg)
                : cfg.getBomberVoidstrikeFormation(dt);
        div.voidstrikeSquadronDepth = (optionalSpec != null)
                ? resolveEffectiveVoidstrikeSquadronDepth(optionalSpec, cfg)
                : cfg.getVoidstrikeSquadronDepth(dt);
        div.voidstrikeSquadronWidth = (optionalSpec != null)
                ? resolveEffectiveVoidstrikeSquadronWidth(optionalSpec, cfg)
                : cfg.getVoidstrikeSquadronWidth(dt);
        div.doctrinalSetpieceAttack = (optionalSpec != null)
                ? resolveEffectiveSetpieceAttack(optionalSpec, cfg)
                : cfg.getSetpieceAttack(dt);
        div.shipSkillCoordination = (optionalSpec != null)
                ? resolveEffectiveShipSkillCoordination(optionalSpec, cfg)
                : cfg.getShipSkillCoordination(dt);
        if (optionalSpec != null) {
            div.rammingMode = resolveEffectiveRammingMode(optionalSpec, cfg);
            div.rammingTargetScope = resolveEffectiveRammingTargetScope(optionalSpec, cfg);
            div.maxRammingAttackSlots = resolveEffectiveMaxRammingSlots(optionalSpec, cfg);
        } else {
            DivisionRammingMode docRam = cfg.getRammingMode(dt);
            if (docRam != DivisionRammingMode.OFF) {
                div.rammingMode = docRam;
            } else {
                boolean anyRamMember = false;
                for (ShipAPI m : div.members) {
                    if (m != null && ShipSystemCoordinator.getSkillProfile(m).isDesignedToRam) {
                        anyRamMember = true;
                        break;
                    }
                }
                div.rammingMode = anyRamMember ? DivisionRammingMode.GLANCING_SKIRMISH : DivisionRammingMode.OFF;
            }
            div.rammingTargetScope = cfg.getRammingTargetScope(dt);
            div.maxRammingAttackSlots = cfg.getMaxRammingSlots(dt);
        }

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

        DoctrineOption doctrine = optionalSpec != null ? optionalSpec.doctrine : null;
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

    public static Map<DivisionArchetype, List<FleetMemberAPI>> newArchetypeBuckets() {
        Map<DivisionArchetype, List<FleetMemberAPI>> buckets = new EnumMap<>(DivisionArchetype.class);
        for (DivisionArchetype arch : DivisionArchetype.values()) {
            buckets.put(arch, new ArrayList<FleetMemberAPI>());
        }
        return buckets;
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

        Map<DivisionArchetype, List<FleetMemberAPI>> buckets = newArchetypeBuckets();
        FleetDoctrineConfig doctrine = getFleetDoctrine();
        for (FleetMemberAPI m : eligible) {
            DivisionArchetype arch = classifyFleetMemberArchetype(m, doctrine);
            if (arch == null) continue;
            if (arch == DivisionArchetype.CARRIER_GROUP) {
                arch = DivisionArchetype.ESCORT_CARRIER_DIVISION;
            }
            buckets.get(arch).add(m);
        }

        List<FleetMemberAPI> capitals = buckets.get(DivisionArchetype.BATTLESHIP_DIVISION);
        List<FleetMemberAPI> battlecruisers = buckets.get(DivisionArchetype.BATTLECRUISER_DIVISION);
        List<FleetMemberAPI> missileCapitals = buckets.get(DivisionArchetype.MISSILE_BATTLESHIP_DIVISION);
        List<FleetMemberAPI> battlecarriers = buckets.get(DivisionArchetype.BATTLECARRIER_DIVISION);
        List<FleetMemberAPI> lineCruisers = buckets.get(DivisionArchetype.CRUISER_DIVISION);
        List<FleetMemberAPI> missileCruisers = buckets.get(DivisionArchetype.MISSILE_CRUISER_DIVISION);
        List<FleetMemberAPI> strikeCruisers = buckets.get(DivisionArchetype.STRIKE_CRUISER_DIVISION);
        List<FleetMemberAPI> phaseCruisers = buckets.get(DivisionArchetype.PHASE_CRUISER_DIVISION);

        // 1. Capital Weight Class (Battleship, Battlecruiser, Missile Battleship, Battlecarrier, Fleet Carrier)
        for (int i = 0; i < capitals.size(); i += 2) {
            List<FleetMemberAPI> group = new ArrayList<>();
            group.add(capitals.get(i));
            if (i + 1 < capitals.size()) {
                group.add(capitals.get(i + 1));
            }
            if (group.size() == 1 && lineCruisers.size() == 1 && strikeCruisers.isEmpty() && missileCruisers.isEmpty()) {
                group.add(lineCruisers.remove(0));
            }
            addBuiltDivision(specs, pdata, DivisionArchetype.BATTLESHIP_DIVISION, group);
        }
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.BATTLECRUISER_DIVISION, battlecruisers, 2);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.MISSILE_BATTLESHIP_DIVISION, missileCapitals, 2);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.BATTLECARRIER_DIVISION, battlecarriers, 2);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.FLEET_CARRIER_DIVISION, buckets.get(DivisionArchetype.FLEET_CARRIER_DIVISION), 2);

        // 2. Cruiser Weight Class (Line Cruiser, Strike Cruiser, Missile Cruiser, Phase Cruiser, Light Carrier)
        if (strikeCruisers.size() == 1 && !lineCruisers.isEmpty()) {
            lineCruisers.add(strikeCruisers.remove(0));
        }
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.CRUISER_DIVISION, lineCruisers, 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.STRIKE_CRUISER_DIVISION, strikeCruisers, 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.MISSILE_CRUISER_DIVISION, missileCruisers, 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.PHASE_CRUISER_DIVISION, phaseCruisers, 3);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.LIGHT_CARRIER_DIVISION, buckets.get(DivisionArchetype.LIGHT_CARRIER_DIVISION), 3);

        // 3. Destroyer Weight Class (Destroyer, Strike Destroyer, Missile Destroyer, Escort Carrier)
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.DESTROYER_DIVISION, buckets.get(DivisionArchetype.DESTROYER_DIVISION), 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.STRIKE_DESTROYER_DIVISION, buckets.get(DivisionArchetype.STRIKE_DESTROYER_DIVISION), 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.MISSILE_DESTROYER_DIVISION, buckets.get(DivisionArchetype.MISSILE_DESTROYER_DIVISION), 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.ESCORT_CARRIER_DIVISION, buckets.get(DivisionArchetype.ESCORT_CARRIER_DIVISION), 4);

        // 4. Frigate & Phase Weight Class (Frigate, Strike Frigate, Missile Frigate, Phase, Missile Phase)
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.FRIGATE_DIVISION, buckets.get(DivisionArchetype.FRIGATE_DIVISION), 5);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.STRIKE_FRIGATE_DIVISION, buckets.get(DivisionArchetype.STRIKE_FRIGATE_DIVISION), 5);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.MISSILE_FRIGATE_DIVISION, buckets.get(DivisionArchetype.MISSILE_FRIGATE_DIVISION), 5);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.PHASE_DIVISION, buckets.get(DivisionArchetype.PHASE_DIVISION), 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.MISSILE_PHASE_DIVISION, buckets.get(DivisionArchetype.MISSILE_PHASE_DIVISION), 4);

        // 5. General Force & Non-Combatant Auxiliary (Service Group at the bottom)
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.FORCE_DIVISION, buckets.get(DivisionArchetype.FORCE_DIVISION), 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.SERVICE_GROUP, buckets.get(DivisionArchetype.SERVICE_GROUP), 5);

        sortDivisionsByWeightClassAndRole(specs, fleet);
        return specs;
    }

    public static float computeFlagshipCandidateScore(FleetMemberAPI m, boolean preferCarrierFlagship) {
        if (m == null) return -1f;
        float score = m.getDeploymentPointsCost();
        if (m.isFlagship()) {
            // The Fleet Flagship always takes highest priority so it is seeded into Division 1 and leads its division
            score += 5000f;
        }
        if (m.getCaptain() != null && !m.getCaptain().isDefault()) {
            // Ships that already have a commander naturally take top priority as division flagships
            score += 1000f + m.getCaptain().getStats().getLevel() * 25f;
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
            if (m.isFlagship() || (m.getCaptain() != null && !m.getCaptain().isDefault())) {
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
        boolean anyRamShip = false;
        for (FleetMemberAPI m : members) {
            spec.memberIds.add(m.getId());
            if (ShipSystemCoordinator.isFleetMemberDesignedToRam(m)) {
                anyRamShip = true;
            }
            float score = computeFlagshipCandidateScore(m, preferCarrier);
            if (score > bestScore) {
                bestScore = score;
                bestFlagship = m;
            }
        }
        spec.flagshipMemberId = (bestFlagship != null) ? bestFlagship.getId() : members.get(0).getId();
        if (anyRamShip) {
            spec.customRammingMode = DivisionRammingMode.GLANCING_SKIRMISH;
            spec.customRammingTargetScope = DivisionRammingTargetScope.SINGLE_SHIP;
            spec.customMaxRammingSlots = 1;
        }
    }

    private static PermanentDivisionSpec createNewDivisionInternal(
            List<PermanentDivisionSpec> specs,
            Map<String, Object> pdata,
            DivisionArchetype archetype) {
        DivisionArchetype arch = (archetype != null) ? archetype : DivisionArchetype.FORCE_DIVISION;
        int nextSeq = 1;
        Object rawSeq = pdata.get(PERSISTENT_SEQ_KEY);
        if (rawSeq instanceof Integer) {
            nextSeq = ((Integer) rawSeq) + 1;
        } else {
            nextSeq = specs.size() + 1;
        }
        pdata.put(PERSISTENT_SEQ_KEY, nextSeq);

        String id = "perm_div_" + nextSeq;
        String name = findNextAvailablePlayerDivisionName(specs, arch, id);
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
        sortDivisionsByWeightClassAndRole(specs, sector.getPlayerFleet());
        DivisionNomenclature.PlayerNamingConfig cfg = DivisionNomenclature.getPlayerNamingConfig();
        Map<String, Integer> seqByBucket = new LinkedHashMap<>();
        int updated = 0;
        for (PermanentDivisionSpec spec : specs) {
            if (spec == null) continue;
            DivisionArchetype arch = spec.archetype != null ? spec.archetype : DivisionArchetype.CRUISER_DIVISION;
            String bucketKey = DivisionNomenclature.resolvePlayerPrefixBucketKey(cfg, arch);
            int seq = (seqByBucket.containsKey(bucketKey) ? seqByBucket.get(bucketKey) : 0) + 1;
            seqByBucket.put(bucketKey, seq);
            String newName = DivisionNomenclature.formatDivisionNameWithConfig(cfg, arch, seq);
            if (!newName.equals(spec.name)) {
                spec.name = newName;
                updated++;
            }
        }
        sortDivisionsByWeightClassAndRole(specs, sector.getPlayerFleet());
        saveSpecsToPersistentData(pdata, specs);
        return updated;
    }

    /**
     * When the player changes their Fleet Nomenclature preset, numbering style, or custom prefixes,
     * automatically updates any division in {@code specs} whose name was default under {@code previousCfg}
     * (or current {@code newCfg}), while leaving any custom-named division untouched.
     */
    public static int updateDefaultNamedDivisionsInList(
            List<PermanentDivisionSpec> specs,
            DivisionNomenclature.PlayerNamingConfig previousCfg,
            DivisionNomenclature.PlayerNamingConfig newCfg) {
        if (specs == null || specs.isEmpty()) return 0;
        DivisionNomenclature.PlayerNamingConfig effNew = newCfg != null
                ? newCfg
                : DivisionNomenclature.getPlayerNamingConfig();
        DivisionNomenclature.PlayerNamingConfig effPrev = previousCfg != null ? previousCfg : effNew;

        Map<String, Integer> seqByBucket = new LinkedHashMap<>();
        int updated = 0;
        for (PermanentDivisionSpec spec : specs) {
            if (spec == null) continue;
            DivisionArchetype arch = spec.archetype != null ? spec.archetype : DivisionArchetype.CRUISER_DIVISION;
            String bucketKey = DivisionNomenclature.resolvePlayerPrefixBucketKey(effNew, arch);
            int seq = (seqByBucket.containsKey(bucketKey) ? seqByBucket.get(bucketKey) : 0) + 1;
            seqByBucket.put(bucketKey, seq);

            boolean wasDefault = DivisionNomenclature.isDefaultDivisionNameForConfig(spec.name, arch, effPrev)
                    || DivisionNomenclature.isDefaultDivisionNameForConfig(spec.name, arch, effNew);
            if (wasDefault) {
                String newName = DivisionNomenclature.formatDivisionNameWithConfig(effNew, arch, seq);
                if (!newName.equals(spec.name)) {
                    spec.name = newName;
                    updated++;
                }
            }
        }
        return updated;
    }

    public static int updateDefaultNamedPlayerDivisionsAfterNamingChange(
            DivisionNomenclature.PlayerNamingConfig previousCfg) {
        SectorAPI sector = Global.getSector();
        if (sector == null) return 0;
        Map<String, Object> pdata = sector.getPersistentData();
        List<PermanentDivisionSpec> specs = ensureInitializedForPlayerFleet();
        sortDivisionsByWeightClassAndRole(specs, sector.getPlayerFleet());
        int updated = updateDefaultNamedDivisionsInList(
                specs,
                previousCfg,
                DivisionNomenclature.getPlayerNamingConfig());
        if (updated > 0) {
            sortDivisionsByWeightClassAndRole(specs, sector.getPlayerFleet());
            saveSpecsToPersistentData(pdata, specs);
        }
        return updated;
    }

    public static DivisionArchetype classifyFleetMemberArchetype(FleetMemberAPI member) {
        return classifyFleetMemberArchetype(member, null);
    }

    /**
     * Classifies a fleet member into a {@link DivisionArchetype}, honoring any disabled capabilities
     * or hull types in {@code doctrine} (when non-null, e.g. for the player fleet's auto-assigner).
     * If a specialized capability (e.g. Missile, Strike, Carrier, Phase) is disabled for the member's
     * hull type, classification falls back to the next eligible capability (such as Line Combat).
     * Returns {@code null} if all applicable division schemes for this member's hull are disabled.
     */
    public static DivisionArchetype classifyFleetMemberArchetype(FleetMemberAPI member, FleetDoctrineConfig doctrine) {
        if (member == null) return DivisionArchetype.FORCE_DIVISION;
        ShipHullSpecAPI hullSpec = member.getHullSpec();
        ShipVariantAPI variant = member.getVariant();
        HullSize size = member.getHullSpec() != null ? member.getHullSpec().getHullSize() : HullSize.FRIGATE;
        AutoAssignHull aaHull = AutoAssignHull.forHullSize(size);
        if (doctrine != null && !doctrine.isHullEnabled(aaHull)) {
            return null;
        }
        EnumSet<ShipTypeHints> hints = (hullSpec != null && hullSpec.getHints() != null)
                ? hullSpec.getHints()
                : EnumSet.noneOf(ShipTypeHints.class);

        boolean isAux = member.isCivilian()
                || hints.contains(ShipTypeHints.CIVILIAN)
                || hints.contains(ShipTypeHints.FREIGHTER)
                || hints.contains(ShipTypeHints.TANKER)
                || hints.contains(ShipTypeHints.LINER);
        if (isAux) {
            if (doctrine == null || doctrine.isSchemeEnabled(AutoAssignCapability.SERVICE, aaHull)) {
                return DivisionArchetype.SERVICE_GROUP;
            }
            return DivisionArchetype.FORCE_DIVISION;
        }

        boolean isPhase = isTruePhaseFleetMember(member);
        boolean safetyOverrides = variant != null && variant.hasHullMod("safetyoverrides");
        String desgLower = (hullSpec != null && hullSpec.getDesignation() != null)
                ? hullSpec.getDesignation().toLowerCase(java.util.Locale.ROOT)
                : "";
        String sysIdLower = (hullSpec != null && hullSpec.getShipSystemId() != null)
                ? hullSpec.getShipSystemId().toLowerCase(java.util.Locale.ROOT)
                : "";
        boolean hasAutoforge = sysIdLower.contains("forge") || sysIdLower.contains("missile_reload");
        float baseMaxSpeed = member.getStats() != null
                ? member.getStats().getMaxSpeed().getBaseValue()
                : 0f;
        float modMaxSpeed = member.getStats() != null
                ? member.getStats().getMaxSpeed().getModifiedValue()
                : baseMaxSpeed;
        if (baseMaxSpeed <= 0f) {
            baseMaxSpeed = modMaxSpeed;
        }

        int bays = (variant != null && variant.getFittedWings() != null) ? variant.getFittedWings().size() : 0;
        if (bays == 0 && hullSpec != null) {
            bays = hullSpec.getFighterBays();
        }
        float missileWeight = 0f;
        float antiShipMissileWeight = 0f;
        float purePdSum = 0f;
        float pdSum = 0f;
        float longWeight = 0f;
        float closeWeight = 0f;
        float totalWeaponWeight = 0f;
        int largeMissileCount = 0;
        if (variant != null) {
            java.util.Collection<String> slotIds = variant.getFittedWeaponSlots();
            if (slotIds == null || slotIds.isEmpty()) {
                slotIds = variant.getNonBuiltInWeaponSlots();
            }
            if (slotIds != null) {
                for (String slotId : slotIds) {
                    if (slotId == null) continue;
                    WeaponSpecAPI wSpec = variant.getWeaponSpec(slotId);
                    if (wSpec == null) {
                        String wId = variant.getWeaponId(slotId);
                        if (wId != null && Global.getSettings() != null) {
                            wSpec = Global.getSettings().getWeaponSpec(wId);
                        }
                    }
                    if (wSpec == null) continue;
                    WeaponType wt = wSpec.getType();
                    if (wt == WeaponType.DECORATIVE || wt == WeaponType.LAUNCH_BAY
                            || wt == WeaponType.SYSTEM || wt == WeaponType.STATION_MODULE) {
                        continue;
                    }
                    WeaponSize sz = wSpec.getSize();
                    if (hullSpec != null && hullSpec.getWeaponSlotAPI(slotId) != null
                            && hullSpec.getWeaponSlotAPI(slotId).getSlotSize() != null) {
                        WeaponSize slotSz = hullSpec.getWeaponSlotAPI(slotId).getSlotSize();
                        if (slotSz == WeaponSize.LARGE
                                || (slotSz == WeaponSize.MEDIUM && sz == WeaponSize.SMALL)) {
                            sz = slotSz;
                        }
                    }
                    float wWeight = ShipCapabilityProfile.sizeWeight(sz != null ? sz : WeaponSize.SMALL);
                    totalWeaponWeight += wWeight;
                    Set<com.fs.starfarer.api.combat.WeaponAPI.AIHints> wHints = wSpec.getAIHints();
                    boolean isPdOnly = wHints != null && (wHints.contains(com.fs.starfarer.api.combat.WeaponAPI.AIHints.PD_ONLY)
                            || (wHints.contains(com.fs.starfarer.api.combat.WeaponAPI.AIHints.PD)
                            && !wHints.contains(com.fs.starfarer.api.combat.WeaponAPI.AIHints.PD_ALSO)));
                    boolean isHybridPd = !isPdOnly && wHints != null
                            && (wHints.contains(com.fs.starfarer.api.combat.WeaponAPI.AIHints.PD)
                            || wHints.contains(com.fs.starfarer.api.combat.WeaponAPI.AIHints.PD_ALSO));
                    float wRange = wSpec.getMaxRange();
                    if (wt == WeaponType.MISSILE) {
                        missileWeight += wWeight;
                        if (sz == WeaponSize.LARGE) {
                            largeMissileCount++;
                        }
                        if (!isPdOnly) {
                            antiShipMissileWeight += wWeight;
                        }
                    }
                    float longGunFloor = CombatPhysicsProfile.getLongWeaponRangeFloor(0f);
                    float shortGunCeil = CombatPhysicsProfile.getShortWeaponRangeCeiling(0f);
                    float hybridCloseCeil = CombatPhysicsProfile.getHybridCloseWeaponRangeCeiling(0f);
                    if (isPdOnly) {
                        purePdSum += wWeight;
                        pdSum += wWeight;
                    } else if (isHybridPd) {
                        pdSum += wWeight * 0.9f;
                        if (wRange >= longGunFloor) longWeight += wWeight * 0.85f;
                        else if (wRange <= hybridCloseCeil) closeWeight += wWeight * 0.85f;
                    } else {
                        if (wRange >= longGunFloor) longWeight += wWeight;
                        else if (wRange <= shortGunCeil) closeWeight += wWeight;
                    }
                }
            }
        }

        String hullId = (hullSpec != null && hullSpec.getHullId() != null)
                ? hullSpec.getHullId().toLowerCase(java.util.Locale.ROOT)
                : "";
        float nonPdWeaponWeight = Math.max(0f, totalWeaponWeight - purePdSum);
        boolean isBattlecarrier = ShipCapabilityProfile.isBattlecarrierHull(
                size, bays, hints, desgLower, nonPdWeaponWeight);
        boolean isDedicatedCarrier = !isBattlecarrier
                && (member.isCarrier() || ShipCapabilityProfile.isDedicatedCarrierHull(
                size, bays, hints, desgLower, nonPdWeaponWeight))
                && !(size == HullSize.CAPITAL_SHIP && desgLower.contains("battlecruiser"));

        boolean primaryMissileBoat = !isDedicatedCarrier
                && antiShipMissileWeight >= 2.0f
                && (nonPdWeaponWeight <= 0.5f
                || (antiShipMissileWeight / Math.max(1f, nonPdWeaponWeight)) >= 0.62f
                || (hasAutoforge && (antiShipMissileWeight / Math.max(1f, nonPdWeaponWeight)) >= 0.42f));
        boolean heavyMissile = ShipCapabilityProfile.isHeavyMissileHullLoadout(
                size, hullId, isDedicatedCarrier, largeMissileCount, missileWeight, totalWeaponWeight, false)
                || (isPhase && primaryMissileBoat)
                || (hasAutoforge && missileWeight >= 3.0f && (missileWeight / Math.max(1f, totalWeaponWeight)) >= 0.34f);

        if (isPhase) {
            AutoAssignHull phaseHull = (size == HullSize.CAPITAL_SHIP) ? AutoAssignHull.CRUISER : aaHull;
            if ((heavyMissile || primaryMissileBoat)
                    && (doctrine == null || doctrine.isSchemeEnabled(AutoAssignCapability.MISSILE_PHASE, phaseHull))) {
                return DivisionArchetype.MISSILE_PHASE_DIVISION;
            }
            if (size == HullSize.CRUISER || size == HullSize.CAPITAL_SHIP) {
                if (doctrine == null || doctrine.isSchemeEnabled(AutoAssignCapability.PHASE, AutoAssignHull.CRUISER)) {
                    return DivisionArchetype.PHASE_CRUISER_DIVISION;
                }
            } else if (doctrine == null || doctrine.isSchemeEnabled(AutoAssignCapability.PHASE, phaseHull)) {
                return DivisionArchetype.PHASE_DIVISION;
            }
            return DivisionArchetype.FORCE_DIVISION;
        }

        // Battlecarriers (e.g. Legion, modded capital BCVs): form Battlecarrier Divisions when enabled,
        // or fold onto the line of battle inside Battleship Divisions when disabled (e.g. Hegemony)!
        if (isBattlecarrier
                && (doctrine == null || doctrine.isSchemeEnabled(AutoAssignCapability.BATTLECARRIER, AutoAssignHull.BATTLESHIP))) {
            return DivisionArchetype.BATTLECARRIER_DIVISION;
        }

        if (isDedicatedCarrier && (doctrine == null || doctrine.isSchemeEnabled(AutoAssignCapability.CARRIER, aaHull))) {
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

        boolean missileAllowed = (doctrine == null || doctrine.isSchemeEnabled(AutoAssignCapability.MISSILE, aaHull));
        boolean strikeAllowed = (doctrine == null || doctrine.isSchemeEnabled(AutoAssignCapability.STRIKE, aaHull));
        boolean lineAllowed = (doctrine == null || doctrine.isSchemeEnabled(AutoAssignCapability.LINE, aaHull));
        boolean pdScreenCapable = totalWeaponWeight > 0f && (pdSum / totalWeaponWeight) >= 0.32f;
        float speedCheck = Math.max(baseMaxSpeed, modMaxSpeed);

        switch (size) {
            case CAPITAL_SHIP:
                if (missileAllowed && heavyMissile) {
                    return DivisionArchetype.MISSILE_BATTLESHIP_DIVISION;
                }
                if (strikeAllowed && ShipCapabilityProfile.isFastBattlecruiserHull(size, speedCheck, desgLower, safetyOverrides)) {
                    return DivisionArchetype.BATTLECRUISER_DIVISION;
                }
                return lineAllowed ? DivisionArchetype.BATTLESHIP_DIVISION : DivisionArchetype.FORCE_DIVISION;
            case CRUISER:
                if (missileAllowed && heavyMissile) {
                    return DivisionArchetype.MISSILE_CRUISER_DIVISION;
                }
                if (strikeAllowed && (modMaxSpeed >= 90f
                        || ShipCapabilityProfile.isFastStrikeCruiserHull(baseMaxSpeed, desgLower, closeWeight, longWeight, safetyOverrides))) {
                    return DivisionArchetype.STRIKE_CRUISER_DIVISION;
                }
                return lineAllowed ? DivisionArchetype.CRUISER_DIVISION : DivisionArchetype.FORCE_DIVISION;
            case DESTROYER:
                if (missileAllowed && heavyMissile) {
                    return DivisionArchetype.MISSILE_DESTROYER_DIVISION;
                }
                if (strikeAllowed && ShipCapabilityProfile.isFastStrikeDestroyerHull(
                        speedCheck, pdScreenCapable, closeWeight, longWeight, safetyOverrides)) {
                    return DivisionArchetype.STRIKE_DESTROYER_DIVISION;
                }
                return lineAllowed ? DivisionArchetype.DESTROYER_DIVISION : DivisionArchetype.FORCE_DIVISION;
            case FRIGATE:
            default:
                if (missileAllowed && heavyMissile) {
                    return DivisionArchetype.MISSILE_FRIGATE_DIVISION;
                }
                if (strikeAllowed && ShipCapabilityProfile.isFastStrikeFrigateHull(
                        speedCheck, desgLower, pdScreenCapable, closeWeight, longWeight, safetyOverrides)) {
                    return DivisionArchetype.STRIKE_FRIGATE_DIVISION;
                }
                return lineAllowed ? DivisionArchetype.FRIGATE_DIVISION : DivisionArchetype.FORCE_DIVISION;
        }
    }

    public static boolean isTruePhaseFleetMember(FleetMemberAPI member) {
        if (member == null) return false;
        ShipHullSpecAPI hullSpec = member.getHullSpec();
        EnumSet<ShipTypeHints> hints = hullSpec != null ? hullSpec.getHints() : EnumSet.noneOf(ShipTypeHints.class);
        if (hints != null && hints.contains(ShipTypeHints.PHASE)) {
            return true;
        }
        if (!member.isPhaseShip()) {
            return false;
        }
        String defId = (hullSpec != null) ? hullSpec.getShipDefenseId() : null;
        if (defId == null || defId.isEmpty()) {
            return true; // Mock FleetMemberAPI fallback
        }
        try {
            if (Global.getSettings() != null) {
                com.fs.starfarer.api.combat.ShipSystemSpecAPI sysSpec = Global.getSettings().getShipSystemSpec(defId);
                if (sysSpec != null) {
                    if (sysSpec.isPhaseCloak()) return true;
                    return defId.toLowerCase(java.util.Locale.ROOT).contains("phase");
                }
            }
        } catch (Throwable ignored) {
        }
        String lower = defId.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("phase") || lower.contains("cloak");
    }

    public static boolean isAsteroidOrInertPropFleetMember(FleetMemberAPI m) {
        if (m == null) return true;
        if (m.getVariant() != null && m.getVariant().hasHullMod("wfpasm_valuable_asteroid")) {
            return true;
        }
        ShipHullSpecAPI hs = m.getHullSpec();
        if (hs != null) {
            java.util.List<String> builtIns = hs.getBuiltInMods();
            if (builtIns != null && builtIns.contains("wfpasm_valuable_asteroid")) {
                return true;
            }
            if (hs.getEngineSpec() != null
                    && hs.getEngineSpec().getMaxSpeed() > 0f
                    && hs.getEngineSpec().getAcceleration() <= 0f
                    && hs.getEngineSpec().getTurnAcceleration() <= 0f
                    && hs.getFluxCapacity() <= 0f
                    && hs.getMaxCrew() <= 0f) {
                return true;
            }
        }
        return false;
    }

    public static boolean isEligibleFleetMember(FleetMemberAPI m) {
        if (m == null || m.isFighterWing() || m.isMothballed() || m.isStation()) return false;
        if (isAsteroidOrInertPropFleetMember(m)) return false;
        HullSize size = m.getHullSpec() != null ? m.getHullSpec().getHullSize() : null;
        return size == HullSize.FRIGATE
                || size == HullSize.DESTROYER
                || size == HullSize.CRUISER
                || size == HullSize.CAPITAL_SHIP;
    }

    /**
     * Never permanently drops a member just because it is missing from the fleet this instant.
     * Disabled ships awaiting recovery, ships mid D-mod repair/restoration (vanilla refit restore,
     * IndEvo Restoration Docks, SiC/ShipMastery/StarshipLegends scripts that swap hull specs or
     * re-add the member) and post-battle dialogs all make a member transiently absent. Missing ids
     * are moved to {@link PermanentDivisionSpec#dormantMembers} and restored to their old slot
     * when the ship returns (same id, or an unassigned ship with the same name + base hull).
     */
    private static void pruneRemovedFleetMembers(List<PermanentDivisionSpec> specs, CampaignFleetAPI fleet) {
        if (specs == null || fleet == null || fleet.getFleetData() == null) return;
        Map<String, FleetMemberAPI> currentById = mapFleetMembersById(fleet);
        long nowTs = 0L;
        SectorAPI sector = Global.getSector();
        boolean haveClock = sector != null && sector.getClock() != null;
        if (haveClock) nowTs = sector.getClock().getTimestamp();

        Set<String> assignedEverywhere = new HashSet<>();
        for (PermanentDivisionSpec spec : specs) {
            for (String id : spec.memberIds) {
                if (currentById.containsKey(id)) assignedEverywhere.add(id);
            }
        }

        for (PermanentDivisionSpec spec : specs) {
            if (spec.dormantMembers == null) spec.dormantMembers = new LinkedHashMap<>();
            // 1) Move missing members to dormant (keep fingerprint + slot for restoration).
            for (int i = spec.memberIds.size() - 1; i >= 0; i--) {
                String mId = spec.memberIds.get(i);
                if (!currentById.containsKey(mId)) {
                    spec.memberIds.remove(i);
                    if (!spec.dormantMembers.containsKey(mId)) {
                        String fp = lookupLastKnownFingerprint(mId);
                        spec.dormantMembers.put(mId, new DormantMember(fp, nowTs, i));
                        LOG.info("[DivisionAI] \"" + spec.name + "\": member " + mId
                                + (fp != null ? " (" + fp + ")" : "")
                                + " temporarily absent from fleet; holding its division slot.");
                    }
                }
            }
            // 2) Restore dormant members that came back.
            if (!spec.dormantMembers.isEmpty()) {
                List<String> dormantIds = new ArrayList<>(spec.dormantMembers.keySet());
                for (String dId : dormantIds) {
                    DormantMember dm = spec.dormantMembers.get(dId);
                    String restoredId = null;
                    if (currentById.containsKey(dId) && !assignedEverywhere.contains(dId)) {
                        restoredId = dId;
                    } else if (dm != null && dm.fingerprint != null) {
                        for (FleetMemberAPI m : currentById.values()) {
                            if (assignedEverywhere.contains(m.getId())) continue;
                            if (!isEligibleFleetMember(m)) continue;
                            if (dm.fingerprint.equals(memberFingerprint(m))) {
                                restoredId = m.getId();
                                break;
                            }
                        }
                    }
                    if (restoredId != null) {
                        spec.dormantMembers.remove(dId);
                        if (spec.memberIds.size() < MAX_DIVISION_SIZE && !spec.memberIds.contains(restoredId)) {
                            int slot = dm != null ? Math.max(0, Math.min(dm.slotIndex, spec.memberIds.size())) : spec.memberIds.size();
                            spec.memberIds.add(slot, restoredId);
                            assignedEverywhere.add(restoredId);
                            if (dId.equals(spec.flagshipMemberId) || spec.flagshipMemberId == null) {
                                spec.flagshipMemberId = restoredId;
                            }
                            LOG.info("[DivisionAI] \"" + spec.name + "\": restored member " + restoredId
                                    + (restoredId.equals(dId) ? "" : " (was " + dId + ")") + " to its division.");
                        }
                    } else if (dm != null && haveClock && dm.sinceTimestamp != 0L
                            && sector.getClock().getElapsedDaysSince(dm.sinceTimestamp) > DORMANT_MEMBER_EXPIRY_DAYS) {
                        spec.dormantMembers.remove(dId);
                    }
                }
            }
            // Keep the previous flagship if it is merely dormant: ensureValidFlagship would pick a new one,
            // and the restore step above re-promotes it on return.
            ensureValidFlagship(spec, fleet);
            rememberFingerprints(spec, currentById);
        }
    }

    /** Last fingerprint seen per member id (session cache, also persisted with dormant entries). */
    private static final Map<String, String> LAST_KNOWN_FINGERPRINT = new HashMap<>();

    private static void rememberFingerprints(PermanentDivisionSpec spec, Map<String, FleetMemberAPI> currentById) {
        for (String id : spec.memberIds) {
            FleetMemberAPI m = currentById.get(id);
            String fp = memberFingerprint(m);
            if (fp != null) LAST_KNOWN_FINGERPRINT.put(id, fp);
        }
    }

    private static String lookupLastKnownFingerprint(String memberId) {
        return LAST_KNOWN_FINGERPRINT.get(memberId);
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
        if (pdata == null) return new ArrayList<>();
        return deserializeSpecList(pdata.get(PERSISTENT_DIVISIONS_KEY));
    }

    /**
     * Deserializes a list of division specs from a raw collection stored in persistent data or
     * fleet MemoryAPI. Accepts both plain JDK Map<String, Object> entries and legacy
     * PermanentDivisionSpec instances for backward compatibility.
     */
    @SuppressWarnings("unchecked")
    public static List<PermanentDivisionSpec> deserializeSpecList(Object raw) {
        List<PermanentDivisionSpec> result = new ArrayList<>();
        if (!(raw instanceof List)) return result;

        List<?> rawList = (List<?>) raw;
        for (Object item : rawList) {
            PermanentDivisionSpec spec = deserializeSpecFromObject(item);
            if (spec != null) {
                result.add(spec);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static PermanentDivisionSpec deserializeSpecFromObject(Object item) {
        if (item instanceof PermanentDivisionSpec) {
            return (PermanentDivisionSpec) item;
        }
        if (!(item instanceof Map)) return null;
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
        if (id == null || name == null) return null;
        name = DivisionNomenclature.migrateLegacyDivisionName(name, arch);

        PermanentDivisionSpec spec = new PermanentDivisionSpec(id, name, arch);
        spec.flagshipMemberId = flagshipId;
        spec.doctrine = DoctrineOption.fromId(docStr);

        Object cIntra = map.get("customIntraSpacing");
        Object cInter = map.get("customInterSpacing");
        Object cStandoff = map.get("customLineStandoff");
        Object cStandoffManual = map.get("customLineStandoffManual");
        Object cOpenMis = map.get("customOpeningMission");
        Object cMainMis = map.get("customMainMission");
        Object cScOpenMis = map.get("customStrikeCraftOpeningMission");
        Object cScMainMis = map.get("customStrikeCraftMainMission");
        if (cScOpenMis instanceof String) {
            spec.customStrikeCraftOpeningMission = StrikeCraftMissionType.fromId((String) cScOpenMis, null);
        }
        if (cScMainMis instanceof String) {
            spec.customStrikeCraftMainMission = StrikeCraftMissionType.fromId((String) cScMainMis, null);
        }
        Object cPrefSpace = map.get("customPreferredSpace");
        Object cForm = map.get("customFormation");
        Object cSticky = map.get("customMovementStickiness");
        Object cEngTel = map.get("customEngineTelegraph");
        if (cEngTel instanceof String) {
            spec.customEngineTelegraph = DivisionEngineTelegraph.fromId((String) cEngTel, null);
        }
        Object cScCapRes = map.get("customStrikeCraftCapReserve");
        if (cScCapRes instanceof String) {
            spec.customStrikeCraftCapReserve = StrikeCraftCapReserve.fromId((String) cScCapRes, null);
        }
        Object cVsBias = map.get("customVoidstrikeTargetingBias");
        Object cVsCvFocus = map.get("customVoidstrikeFocusCarriers");
        if (cVsBias instanceof String) {
            spec.customVoidstrikeTargetingBias = VoidstrikeTargetingBias.fromId((String) cVsBias, null);
        }
        if (cVsCvFocus instanceof Boolean) {
            spec.customVoidstrikeFocusCarriers = (Boolean) cVsCvFocus;
        }
        Object cVsObs = map.get("customVoidstrikeObservance");
        Object cVsCoord = map.get("customVoidstrikeCoordination");
        Object cVsGeom = map.get("customVoidstrikeAttackGeometry");
        Object cVsSplit = map.get("customVoidstrikeSplitMode");
        Object cVsGun = map.get("customVoidstrikeGunneryMode");
        Object cVsAoa = map.get("customVoidstrikeAoaCorrection");
        Object cFtrFm = map.get("customFighterFormation");
        Object cFtrVsFm = map.get("customFighterVoidstrikeFormation");
        Object cBmbFm = map.get("customBomberFormation");
        Object cBmbVsFm = map.get("customBomberVoidstrikeFormation");
        Object cVsDepth = map.get("customVoidstrikeSquadronDepth");
        Object cVsWidth = map.get("customVoidstrikeSquadronWidth");
        if (cIntra instanceof Number) spec.customStationSpacing = ((Number) cIntra).intValue();
        if (cInter instanceof Number) spec.customDivisionInterval = ((Number) cInter).intValue();
        if (cStandoff instanceof Number) spec.customStandoffRange = ((Number) cStandoff).intValue();
        spec.customStandoffManual = Boolean.TRUE.equals(cStandoffManual);
        if (cOpenMis instanceof String) spec.customOpeningMission = DivisionMissionType.fromId((String) cOpenMis, null);
        if (cMainMis instanceof String) spec.customMainMission = DivisionMissionType.fromId((String) cMainMis, null);
        if (cPrefSpace instanceof String) spec.customLineStation = LineStation.fromId((String) cPrefSpace, null);
        if (cForm instanceof String) spec.customFormation = DivisionFormation.fromId((String) cForm, null);
        if (cSticky instanceof String) spec.customMovementStickiness = DivisionMovementStickiness.fromId((String) cSticky, null);
        if (cVsObs instanceof String) spec.customVoidstrikeObservance = VoidstrikeTargetObservance.fromId((String) cVsObs, null);
        if (cVsCoord instanceof String) spec.customVoidstrikeCoordination = VoidstrikeCoordinationMode.fromId((String) cVsCoord, null);
        if (cVsGeom instanceof String) spec.customVoidstrikeAttackGeometry = VoidstrikeAttackGeometry.fromId((String) cVsGeom, null);
        if (cVsSplit instanceof String) spec.customVoidstrikeSplitMode = VoidstrikeSplitMode.fromId((String) cVsSplit, null);
        if (cVsGun instanceof String) spec.customVoidstrikeGunneryMode = VoidstrikeGunneryMode.fromId((String) cVsGun, null);
        if (cVsAoa instanceof String) spec.customVoidstrikeAoaCorrection = VoidstrikeAoaCorrection.fromId((String) cVsAoa, null);
        if (cFtrFm instanceof String) spec.customFighterFormation = SquadronFormation.fromId((String) cFtrFm, null);
        if (cFtrVsFm instanceof String) spec.customFighterVoidstrikeFormation = SquadronFormation.fromId((String) cFtrVsFm, null);
        if (cBmbFm instanceof String) spec.customBomberFormation = SquadronFormation.fromId((String) cBmbFm, null);
        if (cBmbVsFm instanceof String) spec.customBomberVoidstrikeFormation = SquadronFormation.fromId((String) cBmbVsFm, null);
        if (cVsDepth instanceof Number) {
            spec.customVoidstrikeSquadronDepth = Math.max(MIN_VOIDSTRIKE_SQUADRON_DEPTH,
                    Math.min(MAX_VOIDSTRIKE_SQUADRON_DEPTH, ((Number) cVsDepth).intValue()));
        }
        if (cVsWidth instanceof Number) {
            spec.customVoidstrikeSquadronWidth = Math.max(MIN_VOIDSTRIKE_SQUADRON_WIDTH,
                    Math.min(MAX_VOIDSTRIKE_SQUADRON_WIDTH, ((Number) cVsWidth).intValue()));
        }
        Object cSetpiece = map.get("customSetpieceAttack");
        if (cSetpiece instanceof String) {
            spec.customSetpieceAttack = DivisionSetpieceAttack.fromId((String) cSetpiece, null);
        }
        Object cSkillCoord = map.get("customShipSkillCoordination");
        if (cSkillCoord instanceof String) {
            spec.customShipSkillCoordination = ShipSkillCoordinationMode.fromId((String) cSkillCoord, null);
        }
        Object cRamMode = map.get("customRammingMode");
        if (cRamMode instanceof String) {
            spec.customRammingMode = DivisionRammingMode.fromId((String) cRamMode, null);
        }
        Object cRamScope = map.get("customRammingTargetScope");
        if (cRamScope instanceof String) {
            spec.customRammingTargetScope = DivisionRammingTargetScope.fromId((String) cRamScope, null);
        }
        Object cRamSlots = map.get("customMaxRammingSlots");
        if (cRamSlots instanceof Number) {
            spec.customMaxRammingSlots = Math.max(MIN_RAMMING_ATTACK_SLOTS,
                    Math.min(MAX_RAMMING_ATTACK_SLOTS, ((Number) cRamSlots).intValue()));
        }

        Object dormantObj = map.get("dormantMembers");
        if (dormantObj instanceof List) {
            for (Object d : (List<?>) dormantObj) {
                if (!(d instanceof Map)) continue;
                Map<?, ?> dmap = (Map<?, ?>) d;
                Object did = dmap.get("id");
                if (!(did instanceof String)) continue;
                Object dfp = dmap.get("fp");
                Object dts = dmap.get("since");
                Object dslot = dmap.get("slot");
                spec.dormantMembers.put((String) did, new DormantMember(
                        dfp instanceof String ? (String) dfp : null,
                        dts instanceof Number ? ((Number) dts).longValue() : 0L,
                        dslot instanceof Number ? ((Number) dslot).intValue() : 99));
                if (dfp instanceof String) LAST_KNOWN_FINGERPRINT.put((String) did, (String) dfp);
            }
        }

        Object membersObj = map.get("memberIds");
        if (membersObj instanceof List) {
            for (Object m : (List<?>) membersObj) {
                if (m instanceof String && !spec.memberIds.contains((String) m)) {
                    spec.memberIds.add((String) m);
                }
            }
        }
        return spec;
    }

    private static void saveSpecsToPersistentData(Map<String, Object> pdata, List<PermanentDivisionSpec> specs) {
        if (pdata == null || specs == null) return;
        List<Map<String, Object>> serialized = serializeSpecList(specs);
        pdata.put(PERSISTENT_DIVISIONS_KEY, serialized);
        cachedSpecsRawRef = serialized;
        SectorAPI sector = Global.getSector();
        cachedSpecsFleetSig = computePlayerFleetSignature(sector != null ? sector.getPlayerFleet() : null);
        cachedSpecsList = new ArrayList<>(specs);
    }

    /**
     * Serializes a list of division specs into plain JDK Maps/Lists/Strings/Numbers so XStream
     * never writes custom divisionai.* classes into campaign.xml.
     */
    public static List<Map<String, Object>> serializeSpecList(List<PermanentDivisionSpec> specs) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        if (specs == null) return serialized;
        for (PermanentDivisionSpec spec : specs) {
            if (spec == null) continue;
            serialized.add(serializeSpecToMap(spec));
        }
        return serialized;
    }

    public static Map<String, Object> serializeSpecToMap(PermanentDivisionSpec spec) {
        Map<String, Object> map = new HashMap<>();
        if (spec == null) return map;
        map.put("id", spec.id);
        map.put("name", spec.name);
        map.put("archetype", spec.archetype != null ? spec.archetype.name() : DivisionArchetype.CRUISER_DIVISION.name());
        map.put("doctrine", spec.doctrine != null ? spec.doctrine.name() : DoctrineOption.ADAPTIVE.name());
        map.put("flagshipMemberId", spec.flagshipMemberId);
        map.put("memberIds", spec.memberIds != null ? new ArrayList<>(spec.memberIds) : new ArrayList<String>());
        if (spec.dormantMembers != null && !spec.dormantMembers.isEmpty()) {
            List<Map<String, Object>> dl = new ArrayList<>();
            for (Map.Entry<String, DormantMember> e : spec.dormantMembers.entrySet()) {
                if (e.getValue() == null) continue;
                Map<String, Object> dm = new HashMap<>();
                dm.put("id", e.getKey());
                if (e.getValue().fingerprint != null) dm.put("fp", e.getValue().fingerprint);
                dm.put("since", Long.valueOf(e.getValue().sinceTimestamp));
                dm.put("slot", Integer.valueOf(e.getValue().slotIndex));
                dl.add(dm);
            }
            map.put("dormantMembers", dl);
        }
        if (spec.customStationSpacing != null) map.put("customIntraSpacing", spec.customStationSpacing);
        if (spec.customDivisionInterval != null) map.put("customInterSpacing", spec.customDivisionInterval);
        if (spec.customStandoffRange != null) {
            map.put("customLineStandoff", spec.customStandoffRange);
            if (spec.customStandoffManual) {
                map.put("customLineStandoffManual", Boolean.TRUE);
            }
        }
        if (spec.customOpeningMission != null) map.put("customOpeningMission", spec.customOpeningMission.name());
        if (spec.customMainMission != null) map.put("customMainMission", spec.customMainMission.name());
        if (spec.customStrikeCraftOpeningMission != null) {
            map.put("customStrikeCraftOpeningMission", spec.customStrikeCraftOpeningMission.name());
        }
        if (spec.customStrikeCraftMainMission != null) {
            map.put("customStrikeCraftMainMission", spec.customStrikeCraftMainMission.name());
        }
        if (spec.customStrikeCraftCapReserve != null) {
            map.put("customStrikeCraftCapReserve", spec.customStrikeCraftCapReserve.name());
        }
        if (spec.customLineStation != null) map.put("customPreferredSpace", spec.customLineStation.name());
        if (spec.customFormation != null) map.put("customFormation", spec.customFormation.name());
        if (spec.customMovementStickiness != null) map.put("customMovementStickiness", spec.customMovementStickiness.name());
        if (spec.customEngineTelegraph != null) map.put("customEngineTelegraph", spec.customEngineTelegraph.name());
        if (spec.customVoidstrikeTargetingBias != null) map.put("customVoidstrikeTargetingBias", spec.customVoidstrikeTargetingBias.name());
        if (spec.customVoidstrikeFocusCarriers != null) map.put("customVoidstrikeFocusCarriers", spec.customVoidstrikeFocusCarriers);
        if (spec.customVoidstrikeObservance != null) map.put("customVoidstrikeObservance", spec.customVoidstrikeObservance.name());
        if (spec.customVoidstrikeCoordination != null) map.put("customVoidstrikeCoordination", spec.customVoidstrikeCoordination.name());
        if (spec.customVoidstrikeAttackGeometry != null) map.put("customVoidstrikeAttackGeometry", spec.customVoidstrikeAttackGeometry.name());
        if (spec.customVoidstrikeSplitMode != null) map.put("customVoidstrikeSplitMode", spec.customVoidstrikeSplitMode.name());
        if (spec.customVoidstrikeGunneryMode != null) map.put("customVoidstrikeGunneryMode", spec.customVoidstrikeGunneryMode.name());
        if (spec.customVoidstrikeAoaCorrection != null) map.put("customVoidstrikeAoaCorrection", spec.customVoidstrikeAoaCorrection.name());
        if (spec.customFighterFormation != null) map.put("customFighterFormation", spec.customFighterFormation.name());
        if (spec.customFighterVoidstrikeFormation != null) map.put("customFighterVoidstrikeFormation", spec.customFighterVoidstrikeFormation.name());
        if (spec.customBomberFormation != null) map.put("customBomberFormation", spec.customBomberFormation.name());
        if (spec.customBomberVoidstrikeFormation != null) map.put("customBomberVoidstrikeFormation", spec.customBomberVoidstrikeFormation.name());
        if (spec.customVoidstrikeSquadronDepth != null) map.put("customVoidstrikeSquadronDepth", spec.customVoidstrikeSquadronDepth);
        if (spec.customVoidstrikeSquadronWidth != null) map.put("customVoidstrikeSquadronWidth", spec.customVoidstrikeSquadronWidth);
        if (spec.customSetpieceAttack != null) map.put("customSetpieceAttack", spec.customSetpieceAttack.name());
        if (spec.customShipSkillCoordination != null) map.put("customShipSkillCoordination", spec.customShipSkillCoordination.name());
        if (spec.customRammingMode != null) map.put("customRammingMode", spec.customRammingMode.name());
        if (spec.customRammingTargetScope != null) map.put("customRammingTargetScope", spec.customRammingTargetScope.name());
        if (spec.customMaxRammingSlots != null) map.put("customMaxRammingSlots", spec.customMaxRammingSlots);
        return map;
    }
}
