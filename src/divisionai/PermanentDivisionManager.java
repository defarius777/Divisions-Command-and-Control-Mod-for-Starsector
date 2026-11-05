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
                "Cap simultaneous ramming attack slots per individual enemy ship."),
        ENEMY_DIVISION(
                "Enemy Division",
                "Division",
                "Cap simultaneous ramming attack slots across the target enemy division.");

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
        FLEET_CARRIER(
                "Fleet Carrier Division",
                DivisionArchetype.FLEET_CARRIER_DIVISION,
                650,
                1350,
                1600,
                0.95f,
                DivisionMissionType.ARMED_RECONNAISSANCE,
                DivisionMissionType.ALPHA_STRIKE,
                LineStation.REAR,
                DivisionFormation.LINE_ABREAST),
        LIGHT_CARRIER(
                "Light Carrier Division",
                DivisionArchetype.LIGHT_CARRIER_DIVISION,
                550,
                1250,
                1450,
                0.95f,
                DivisionMissionType.ARMED_RECONNAISSANCE,
                DivisionMissionType.DECKLOAD_STRIKE,
                LineStation.REAR,
                DivisionFormation.LINE_ABREAST),
        ESCORT_CARRIER(
                "Escort Carrier Division",
                DivisionArchetype.ESCORT_CARRIER_DIVISION,
                475,
                1150,
                1350,
                0.95f,
                DivisionMissionType.COMBAT_SPACE_PATROL,
                DivisionMissionType.CYCLIC_STRIKES,
                LineStation.REAR,
                DivisionFormation.FORWARD_SEMICIRCLE),
        CARRIER_GROUP(
                "Carrier Group",
                DivisionArchetype.CARRIER_GROUP,
                575,
                1300,
                1450,
                0.95f,
                DivisionMissionType.ARMED_RECONNAISSANCE,
                DivisionMissionType.DECKLOAD_STRIKE,
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
                case MISSILE_FRIGATE_DIVISION:
                    return MISSILE_FRIGATE;
                case DESTROYER_DIVISION:
                    return DESTROYER;
                case MISSILE_DESTROYER_DIVISION:
                    return MISSILE_DESTROYER;
                case MISSILE_CRUISER_DIVISION:
                    return MISSILE_CRUISER;
                case STRIKE_CRUISER_DIVISION:
                    return STRIKE_CRUISER;
                case BATTLESHIP_DIVISION:
                    return BATTLESHIP;
                case MISSILE_BATTLESHIP_DIVISION:
                    return MISSILE_BATTLESHIP;
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
        MISSILE("Missile", "Missile", "Missile and torpedo strike divisions."),
        STRIKE("Strike / Fast", "Strike", "High-speed flanking cruiser divisions."),
        CARRIER("Carrier", "Carrier", "Dedicated carrier divisions."),
        PHASE("Phase", "Phase", "Cloaked phase-skimmer divisions."),
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
            if (this == STRIKE) {
                return hull == AutoAssignHull.CRUISER;
            }
            return true;
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
        private final Map<DoctrineDivisionType, DivisionFormation> formationByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, VoidstrikeTargetObservance> voidstrikeObservanceByType = new LinkedHashMap<>();
        private final Map<DoctrineDivisionType, VoidstrikeCoordinationMode> voidstrikeCoordinationByType = new LinkedHashMap<>();
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
        private boolean deployInFleetFormation = true;
        private boolean orchestratedAdvance = true;
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
                formationByType.put(dt, dt.defaultFormation);
                voidstrikeObservanceByType.put(dt, defaultVoidstrikeObservanceFor(dt));
                voidstrikeCoordinationByType.put(dt, defaultVoidstrikeCoordinationFor(dt));
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
                case STRIKE_CRUISER:
                    return DivisionSetpieceAttack.LANCE_CHARGE;
                case FRIGATE:
                    return DivisionSetpieceAttack.HORNS_OF_THE_BULL;
                case PHASE:
                    return DivisionSetpieceAttack.ECHELON_STRIKE;
                case MISSILE_FRIGATE:
                case MISSILE_DESTROYER:
                case MISSILE_CRUISER:
                case MISSILE_BATTLESHIP:
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

        public static VoidstrikeTargetObservance defaultVoidstrikeObservanceFor(DoctrineDivisionType dt) {
            if (dt == DoctrineDivisionType.FLEET_CARRIER || dt == DoctrineDivisionType.LIGHT_CARRIER) {
                return VoidstrikeTargetObservance.HIGH;
            }
            if (dt == DoctrineDivisionType.ESCORT_CARRIER) {
                return VoidstrikeTargetObservance.MEDIUM;
            }
            return VoidstrikeTargetObservance.MEDIUM;
        }

        public static VoidstrikeCoordinationMode defaultVoidstrikeCoordinationFor(DoctrineDivisionType dt) {
            if (dt == DoctrineDivisionType.ESCORT_CARRIER) {
                return VoidstrikeCoordinationMode.UNCOORDINATED;
            }
            return VoidstrikeCoordinationMode.COORDINATED;
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
            if (dt == DoctrineDivisionType.FLEET_CARRIER) return 3;
            if (dt == DoctrineDivisionType.LIGHT_CARRIER || dt == DoctrineDivisionType.CARRIER_GROUP) return 2;
            return 2;
        }

        public static int defaultVoidstrikeSquadronWidthFor(DoctrineDivisionType dt) {
            if (dt == DoctrineDivisionType.FLEET_CARRIER) return 3;
            if (dt == DoctrineDivisionType.LIGHT_CARRIER || dt == DoctrineDivisionType.CARRIER_GROUP) return 2;
            return 2;
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
            if (dt == null) return 2;
            Integer v = voidstrikeSquadronDepthByType.get(dt);
            int raw = v != null ? v : defaultVoidstrikeSquadronDepthFor(dt);
            return Math.max(MIN_VOIDSTRIKE_SQUADRON_DEPTH, Math.min(MAX_VOIDSTRIKE_SQUADRON_DEPTH, raw));
        }

        public void setVoidstrikeSquadronDepth(DoctrineDivisionType dt, int depth) {
            if (dt == null) return;
            int clamped = Math.max(MIN_VOIDSTRIKE_SQUADRON_DEPTH, Math.min(MAX_VOIDSTRIKE_SQUADRON_DEPTH, depth));
            voidstrikeSquadronDepthByType.put(dt, clamped);
        }

        public int getVoidstrikeSquadronWidth(DoctrineDivisionType dt) {
            if (dt == null) return 2;
            Integer v = voidstrikeSquadronWidthByType.get(dt);
            int raw = v != null ? v : defaultVoidstrikeSquadronWidthFor(dt);
            return Math.max(MIN_VOIDSTRIKE_SQUADRON_WIDTH, Math.min(MAX_VOIDSTRIKE_SQUADRON_WIDTH, raw));
        }

        public void setVoidstrikeSquadronWidth(DoctrineDivisionType dt, int width) {
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
            Object depFmObj = map.get("bg_deploy_in_formation");
            if (depFmObj instanceof Boolean) {
                cfg.setDeployInFleetFormation((Boolean) depFmObj);
            }
            Object orchAdvObj = map.get("bg_orchestrated_advance");
            if (orchAdvObj instanceof Boolean) {
                cfg.setOrchestratedAdvance((Boolean) orchAdvObj);
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
                if (fmObj instanceof String) {
                    cfg.setFormation(dt, DivisionFormation.fromId((String) fmObj, dt.defaultFormation));
                }
                Object vsObsObj = map.get("vs_obs_" + dt.name());
                Object vsCoordObj = map.get("vs_coord_" + dt.name());
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
        serialized.put("bg_deploy_in_formation", cfg.isDeployInFleetFormation());
        serialized.put("bg_orchestrated_advance", cfg.isOrchestratedAdvance());
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
            serialized.put("vs_obs_" + dt.name(), cfg.getVoidstrikeObservance(dt).name());
            serialized.put("vs_coord_" + dt.name(), cfg.getVoidstrikeCoordination(dt).name());
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
        }
        return serialized;
    }

    public static void saveFleetDoctrine(FleetDoctrineConfig cfg) {
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
        public DivisionMissionType customOpeningMission = null;
        public DivisionMissionType customMainMission = null;
        public LineStation customLineStation = null;
        public DivisionFormation customFormation = null;
        public DivisionMovementStickiness customMovementStickiness = null;
        public VoidstrikeTargetObservance customVoidstrikeObservance = null;
        public VoidstrikeCoordinationMode customVoidstrikeCoordination = null;
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

            // Also check carrier fighter/bomber engagement range if this is a Carrier division fitted with offensive wings
            if (spec.archetype != null && spec.archetype.isCarrierArchetype() && variant.getFittedWings() != null) {
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

    private interface DivisionSpecMutator {
        void mutate(PermanentDivisionSpec spec, CampaignFleetAPI playerFleet);
    }

    private static void mutatePlayerDivisionSpec(String divisionId, DivisionSpecMutator mutator) {
        SectorAPI sector = Global.getSector();
        if (sector == null || divisionId == null || mutator == null) return;
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
            case MISSILE_BATTLESHIP_DIVISION: return 11;
            case FLEET_CARRIER_DIVISION:      return 12;
            case CARRIER_GROUP:               return 13;
            // 2. Cruiser Weight Class (Medium-Heavy Combat)
            case CRUISER_DIVISION:            return 20;
            case STRIKE_CRUISER_DIVISION:     return 21;
            case MISSILE_CRUISER_DIVISION:    return 22;
            case LIGHT_CARRIER_DIVISION:      return 23;
            // 3. Destroyer Weight Class (Medium-Light Combat)
            case DESTROYER_DIVISION:          return 30;
            case MISSILE_DESTROYER_DIVISION:  return 31;
            case ESCORT_CARRIER_DIVISION:     return 32;
            // 4. Frigate & Phase Weight Class (Light / Specialist Combat)
            case FRIGATE_DIVISION:            return 40;
            case MISSILE_FRIGATE_DIVISION:    return 41;
            case PHASE_DIVISION:              return 42;
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

            DivisionState combatDiv = instantiateCombatDivisionFromSpec(
                    battleGroup, spec, members, fallbackNumber++);
            applyDoctrineModifiersToCombatDivision(combatDiv, spec);
            battleGroup.divisions.add(combatDiv);
            newlyCreatedOut.add(combatDiv);
        }

        return leftoverUnassigned;
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
        div.standoffRange = (optionalSpec != null)
                ? resolveEffectiveStandoffRange(optionalSpec, cfg)
                : cfg.getStandoffRange(dt);
        div.doctrinalMovementStickiness = (optionalSpec != null)
                ? resolveEffectiveMovementStickiness(optionalSpec, cfg)
                : cfg.getMovementStickiness(dt);
        div.activeMovementStickiness = div.doctrinalMovementStickiness;
        div.stationKeepingMult = (optionalSpec != null)
                ? resolveEffectiveStationKeepingMult(optionalSpec, cfg)
                : cfg.getStationKeepingMult(dt);
        div.openingPhaseDurationSeconds = cfg.getOpeningDurationSeconds();
        div.openingMission = (optionalSpec != null)
                ? resolveEffectiveOpeningMission(optionalSpec, cfg)
                : cfg.getOpeningMission(dt);
        div.mainMission = (optionalSpec != null)
                ? resolveEffectiveMainMission(optionalSpec, cfg)
                : cfg.getMainMission(dt);
        div.lineStation = (optionalSpec != null)
                ? resolveEffectiveLineStation(optionalSpec, cfg)
                : cfg.getLineStation(dt);
        div.doctrinalFormation = (optionalSpec != null)
                ? resolveEffectiveFormation(optionalSpec, cfg)
                : cfg.getFormation(dt);
        if (div.lastFormationChangeTime < -100f) {
            div.activeFormation = div.doctrinalFormation;
        }
        div.voidstrikeObservance = (optionalSpec != null)
                ? resolveEffectiveVoidstrikeObservance(optionalSpec, cfg)
                : cfg.getVoidstrikeObservance(dt);
        div.voidstrikeCoordination = (optionalSpec != null)
                ? resolveEffectiveVoidstrikeCoordination(optionalSpec, cfg)
                : cfg.getVoidstrikeCoordination(dt);
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
        List<FleetMemberAPI> missileCapitals = buckets.get(DivisionArchetype.MISSILE_BATTLESHIP_DIVISION);
        List<FleetMemberAPI> lineCruisers = buckets.get(DivisionArchetype.CRUISER_DIVISION);
        List<FleetMemberAPI> missileCruisers = buckets.get(DivisionArchetype.MISSILE_CRUISER_DIVISION);
        List<FleetMemberAPI> strikeCruisers = buckets.get(DivisionArchetype.STRIKE_CRUISER_DIVISION);

        // 1. Capital Weight Class (Battleship, Missile Battleship, Fleet Carrier)
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
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.MISSILE_BATTLESHIP_DIVISION, missileCapitals, 2);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.FLEET_CARRIER_DIVISION, buckets.get(DivisionArchetype.FLEET_CARRIER_DIVISION), 2);

        // 2. Cruiser Weight Class (Line Cruiser, Strike Cruiser, Missile Cruiser, Light Carrier)
        if (strikeCruisers.size() == 1 && !lineCruisers.isEmpty()) {
            lineCruisers.add(strikeCruisers.remove(0));
        }
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.CRUISER_DIVISION, lineCruisers, 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.STRIKE_CRUISER_DIVISION, strikeCruisers, 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.MISSILE_CRUISER_DIVISION, missileCruisers, 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.LIGHT_CARRIER_DIVISION, buckets.get(DivisionArchetype.LIGHT_CARRIER_DIVISION), 3);

        // 3. Destroyer Weight Class (Destroyer, Missile Destroyer, Escort Carrier)
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.DESTROYER_DIVISION, buckets.get(DivisionArchetype.DESTROYER_DIVISION), 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.MISSILE_DESTROYER_DIVISION, buckets.get(DivisionArchetype.MISSILE_DESTROYER_DIVISION), 4);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.ESCORT_CARRIER_DIVISION, buckets.get(DivisionArchetype.ESCORT_CARRIER_DIVISION), 4);

        // 4. Frigate & Phase Weight Class (Frigate, Missile Frigate, Phase)
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.FRIGATE_DIVISION, buckets.get(DivisionArchetype.FRIGATE_DIVISION), 5);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.MISSILE_FRIGATE_DIVISION, buckets.get(DivisionArchetype.MISSILE_FRIGATE_DIVISION), 5);
        chunkFleetMembersIntoSpecs(specs, pdata, DivisionArchetype.PHASE_DIVISION, buckets.get(DivisionArchetype.PHASE_DIVISION), 4);

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

        boolean isPhase = member.isPhaseShip() || hints.contains(ShipTypeHints.PHASE);
        if (isPhase && (doctrine == null || doctrine.isSchemeEnabled(AutoAssignCapability.PHASE, aaHull))) {
            return DivisionArchetype.PHASE_DIVISION;
        }

        int bays = (variant != null && variant.getFittedWings() != null) ? variant.getFittedWings().size() : 0;
        if (bays == 0 && hullSpec != null) {
            bays = hullSpec.getFighterBays();
        }
        float missileWeight = 0f;
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
                    if (wt == WeaponType.MISSILE) {
                        missileWeight += wWeight;
                        if (sz == WeaponSize.LARGE) {
                            largeMissileCount++;
                        }
                    }
                }
            }
        }

        // Battlecarriers (e.g. Legion, Odyssey — capital hulls with fighter bays + COMBAT hint) default to Battle Divisions!
        boolean isBattlecarrier = ShipCapabilityProfile.isBattlecarrierHull(size, bays, hints, totalWeaponWeight);
        boolean isDedicatedCarrier = !isBattlecarrier
                && (member.isCarrier() || ShipCapabilityProfile.isDedicatedCarrierHull(size, bays, hints, totalWeaponWeight));
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

        String hullId = (hullSpec != null && hullSpec.getHullId() != null)
                ? hullSpec.getHullId().toLowerCase()
                : "";
        boolean heavyMissile = ShipCapabilityProfile.isHeavyMissileHullLoadout(
                size, hullId, isDedicatedCarrier, largeMissileCount, missileWeight, totalWeaponWeight, false);

        boolean missileAllowed = (doctrine == null || doctrine.isSchemeEnabled(AutoAssignCapability.MISSILE, aaHull));
        boolean lineAllowed = (doctrine == null || doctrine.isSchemeEnabled(AutoAssignCapability.LINE, aaHull));

        switch (size) {
            case CAPITAL_SHIP:
                if (missileAllowed && heavyMissile) {
                    return DivisionArchetype.MISSILE_BATTLESHIP_DIVISION;
                }
                return lineAllowed ? DivisionArchetype.BATTLESHIP_DIVISION : DivisionArchetype.FORCE_DIVISION;
            case CRUISER:
                if (missileAllowed && heavyMissile) {
                    return DivisionArchetype.MISSILE_CRUISER_DIVISION;
                }
                if ((doctrine == null || doctrine.isSchemeEnabled(AutoAssignCapability.STRIKE, AutoAssignHull.CRUISER))
                        && member.getStats() != null
                        && member.getStats().getMaxSpeed().getModifiedValue() >= 90f) {
                    return DivisionArchetype.STRIKE_CRUISER_DIVISION;
                }
                return lineAllowed ? DivisionArchetype.CRUISER_DIVISION : DivisionArchetype.FORCE_DIVISION;
            case DESTROYER:
                if (missileAllowed && heavyMissile) {
                    return DivisionArchetype.MISSILE_DESTROYER_DIVISION;
                }
                return lineAllowed ? DivisionArchetype.DESTROYER_DIVISION : DivisionArchetype.FORCE_DIVISION;
            case FRIGATE:
            default:
                if (missileAllowed && heavyMissile) {
                    return DivisionArchetype.MISSILE_FRIGATE_DIVISION;
                }
                return lineAllowed ? DivisionArchetype.FRIGATE_DIVISION : DivisionArchetype.FORCE_DIVISION;
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

        PermanentDivisionSpec spec = new PermanentDivisionSpec(id, name, arch);
        spec.flagshipMemberId = flagshipId;
        spec.doctrine = DoctrineOption.fromId(docStr);

        Object cIntra = map.get("customIntraSpacing");
        Object cInter = map.get("customInterSpacing");
        Object cStandoff = map.get("customLineStandoff");
        Object cOpenMis = map.get("customOpeningMission");
        Object cMainMis = map.get("customMainMission");
        Object cPrefSpace = map.get("customPreferredSpace");
        Object cForm = map.get("customFormation");
        Object cSticky = map.get("customMovementStickiness");
        Object cVsObs = map.get("customVoidstrikeObservance");
        Object cVsCoord = map.get("customVoidstrikeCoordination");
        Object cFtrFm = map.get("customFighterFormation");
        Object cFtrVsFm = map.get("customFighterVoidstrikeFormation");
        Object cBmbFm = map.get("customBomberFormation");
        Object cBmbVsFm = map.get("customBomberVoidstrikeFormation");
        Object cVsDepth = map.get("customVoidstrikeSquadronDepth");
        Object cVsWidth = map.get("customVoidstrikeSquadronWidth");
        if (cIntra instanceof Number) spec.customStationSpacing = ((Number) cIntra).intValue();
        if (cInter instanceof Number) spec.customDivisionInterval = ((Number) cInter).intValue();
        if (cStandoff instanceof Number) spec.customStandoffRange = ((Number) cStandoff).intValue();
        if (cOpenMis instanceof String) spec.customOpeningMission = DivisionMissionType.fromId((String) cOpenMis, null);
        if (cMainMis instanceof String) spec.customMainMission = DivisionMissionType.fromId((String) cMainMis, null);
        if (cPrefSpace instanceof String) spec.customLineStation = LineStation.fromId((String) cPrefSpace, null);
        if (cForm instanceof String) spec.customFormation = DivisionFormation.fromId((String) cForm, null);
        if (cSticky instanceof String) spec.customMovementStickiness = DivisionMovementStickiness.fromId((String) cSticky, null);
        if (cVsObs instanceof String) spec.customVoidstrikeObservance = VoidstrikeTargetObservance.fromId((String) cVsObs, null);
        if (cVsCoord instanceof String) spec.customVoidstrikeCoordination = VoidstrikeCoordinationMode.fromId((String) cVsCoord, null);
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
        if (spec.customStandoffRange != null) map.put("customLineStandoff", spec.customStandoffRange);
        if (spec.customOpeningMission != null) map.put("customOpeningMission", spec.customOpeningMission.name());
        if (spec.customMainMission != null) map.put("customMainMission", spec.customMainMission.name());
        if (spec.customLineStation != null) map.put("customPreferredSpace", spec.customLineStation.name());
        if (spec.customFormation != null) map.put("customFormation", spec.customFormation.name());
        if (spec.customMovementStickiness != null) map.put("customMovementStickiness", spec.customMovementStickiness.name());
        if (spec.customVoidstrikeObservance != null) map.put("customVoidstrikeObservance", spec.customVoidstrikeObservance.name());
        if (spec.customVoidstrikeCoordination != null) map.put("customVoidstrikeCoordination", spec.customVoidstrikeCoordination.name());
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
