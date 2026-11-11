package divisionai;

import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core data structures for OTC and Division Commander AI.
 */
public class DivisionModel {

    /**
     * How a division was formed and what kind of ship composition it represents.
     */
    public enum DivisionArchetype {
        BATTLESHIP_DIVISION("Battleship Div", true),
        MISSILE_BATTLESHIP_DIVISION("Missile Battleship Div", false),
        FLEET_CARRIER_DIVISION("Fleet Carrier Div", false),
        LIGHT_CARRIER_DIVISION("Light Carrier Div", false),
        ESCORT_CARRIER_DIVISION("Escort Carrier Div", false),
        CARRIER_GROUP("Carrier Group", true),
        CRUISER_DIVISION("Line Cruiser Div", false),
        MISSILE_CRUISER_DIVISION("Missile Cruiser Div", false),
        STRIKE_CRUISER_DIVISION("Strike Cruiser Div", false),
        DESTROYER_DIVISION("Destroyer Div", false),
        MISSILE_DESTROYER_DIVISION("Missile Destroyer Div", false),
        FRIGATE_DIVISION("Frigate Div", false),
        MISSILE_FRIGATE_DIVISION("Missile Frigate Div", false),
        PHASE_DIVISION("Phase Div", false),
        SERVICE_GROUP("Service Group", true),
        FORCE_DIVISION("Force", true);

        public final String shortPrefix;
        public final boolean isComposite;

        DivisionArchetype(String shortPrefix, boolean isComposite) {
            this.shortPrefix = shortPrefix;
            this.isComposite = isComposite;
        }

        /** Maps a pre-v0.5.2 saved archetype name to its current name (unchanged names pass through). */
        public static String resolveLegacyName(String name) {
            if (name == null) return null;
            switch (name) {
                case "BATTLE_DIVISION":
                    return BATTLESHIP_DIVISION.name();
                case "CARRIER_AND_ESCORT":
                    return CARRIER_GROUP.name();
                case "TORPEDO_DESTROYER_DIVISION":
                    return MISSILE_DESTROYER_DIVISION.name();
                case "LINE_CRUISER_DIVISION":
                    return CRUISER_DIVISION.name();
                case "AUXILIARY_CONVOY":
                    return SERVICE_GROUP.name();
                default:
                    return name;
            }
        }

        public boolean isCarrierArchetype() {
            return this == FLEET_CARRIER_DIVISION
                    || this == LIGHT_CARRIER_DIVISION
                    || this == ESCORT_CARRIER_DIVISION
                    || this == CARRIER_GROUP;
        }

        public boolean isMissileArchetype() {
            return this == MISSILE_BATTLESHIP_DIVISION
                    || this == MISSILE_CRUISER_DIVISION
                    || this == MISSILE_DESTROYER_DIVISION
                    || this == MISSILE_FRIGATE_DIVISION;
        }
    }

    /**
     * Mission-level context derived from BattleCreationContext, FleetGoal, and objectives.
     */
    public enum MissionContextType {
        CONTESTED_OBJECTIVES("Contested Objectives"),
        FLEET_ACTION("Fleet Action"),
        FIGHT_TO_THE_LAST("Fight to the Last"),
        REARGUARD_ACTION("Rearguard Action"),
        INTERCEPTION("Interception");

        public final String displayName;

        MissionContextType(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * High-level operational phase chosen by the Battle Group Commander (OTC).
     */
    public enum BattlePhase {
        APPROACH("Approach & Scouting"),
        GENERAL_ENGAGEMENT("General Engagement"),
        COORDINATED_STRIKE("Coordinated Strike"),
        REINFORCE_THE_LINE("Reinforce the Line"),
        GENERAL_CHASE("General Chase"),
        FIGHTING_RETIREMENT("Fighting Retirement");

        public final String displayName;

        BattlePhase(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * Concrete tactical intent chosen by a Division Commander (or directed by OTC).
     *  - verbPhrase: full verbose phrase logged to starsector.log and shown in Verbose HUD mode.
     *  - mediumPhrase: reduced chatter alternative for Standard/Medium HUD mode.
     *  - minimalPhrase: ultra-terse proword alternative for Minimal HUD mode.
     */
    public enum DivisionIntentType {
        CLOSE_UP("close up and re-form", "re-forming ranks", "Re-form"),
        CLOSE_AND_ENGAGE("close and engage", "engaging", "Engage ->"),
        HARASSING_MISSILE_ATTACK("make a harassing missile attack on", "harassing with missiles:", "Harass ->"),
        TOT_MISSILE_STRIKE("deliver a time-on-target missile strike on", "launching ToT missile strike on", "ToT Salvo ->"),
        TOT_ALPHA_STRIKE("execute a time-on-target alpha strike on", "executing ToT alpha strike on", "ToT Alpha ->"),
        PRESS_HOME("press home the attack on", "pressing attack on", "Press ->"),
        TAKE_SCREENING_STATION("take screening station on", "screening", "Screen ->"),
        REINFORCE_DIVISION("move to reinforce", "reinforcing", "Reinforce ->"),
        WORK_FLANK("work around the flank of", "flanking", "Flank ->"),
        BREAK_OFF_AND_RETIRE("break off and retire from", "retiring from", "Retire <-"),
        HAUL_OUT_TO_VENT("haul out of the line to vent behind", "venting behind", "Venting"),
        HOLD_IN_RESERVE("hold in reserve, awaiting orders", "holding in reserve", "Reserve"),
        TAKE_AND_HOLD("take and hold", "seizing", "Capture ->"),
        FLY_ARMED_RECONNAISSANCE("fly armed reconnaissance toward", "flying armed recon toward", "Recon ->"),
        FLY_FIGHTER_SWEEP("fly fighter sweeps for space superiority against", "flying fighter sweep on", "Sweep ->"),
        FLY_COMBAT_SPACE_PATROL("fly combat space patrol over", "patrolling over", "CAP ->"),
        FLY_CYCLIC_STRIKES("fly cyclic strikes against", "cycling strikes on", "Strike ->"),
        LAUNCH_DECKLOAD_STRIKE("launch a deckload strike on", "launching deck strike on", "Deck Strike ->"),
        LAUNCH_ALPHA_STRIKE("launch an alpha strike on", "launching alpha strike on", "Alpha Strike ->"),
        FORM_LINE_AND_FIX("form the line and fix", "fixing", "Fix ->"),
        MARSHAL_FOR_STRIKE("proceed to the marshal point to strike", "marshalling to strike", "Marshal ->"),
        GIVE_GENERAL_CHASE("give general chase to", "chasing", "Chase ->"),
        TRADE_SPACE_FOR_TIME("trade space for time against", "trading space for time on", "Kiting <-"),
        FEIGNED_RETREAT("feign retreat to draw in", "feigning retreat from", "Lure <-");

        public final String verbPhrase;
        public final String mediumPhrase;
        public final String minimalPhrase;

        DivisionIntentType(String verbPhrase, String mediumPhrase, String minimalPhrase) {
            this.verbPhrase = verbPhrase;
            this.mediumPhrase = mediumPhrase;
            this.minimalPhrase = minimalPhrase;
        }
    }

    /**
     * Kind of task the OTC hands a division while orchestrating the battle.
     */
    public enum OtcOrderType {
        NONE,
        FORM_LINE,      // Main Effort: hold a fixing position on the OTC primary threat
        ENGAGE_ASSIGNED,   // engage a specific allocated enemy division
        FLANK,            // envelop the target enemy division from an assigned flank side
        MARSHAL,     // move to a staging point and wait for the strike time
        EXECUTE_STRIKE,    // strike time reached: everyone commits onto the shared focus target
        REINFORCE,          // the one division sent to relieve the current crisis
        HOLD_RESERVE,     // stay in battlegroup reserve until committed
        GENERAL_CHASE,           // exploit: pursue a broken enemy division within the pursuit leash
        TACTICAL_RETREAT,        // Item E: offset space centroid by (dx, dy) to trade space for time without losing gun uptime
        FEIGN_RETREAT_TRAP       // Item F: deliberate bait withdrawal that springs a synchronized alpha strike on pursuers
    }

    /**
     * Coarse fleet-wide general signals that the OTC can hoist for friendly independent ships
     * (ships without a Division Commander, intra-division formation slots, or division target links).
     */
    public enum FleetIndependentCommand {
        NONE("No Independent Ships"),
        RALLY_ON_FLEET_CENTER("Signal: Rally on Fleet Center"),
        ENGAGE_PRIMARY_SECTOR("Signal: Advance on Primary Threat Sector"),
        REINFORCE_THREATENED_SECTOR("Signal: Rally to Threatened Sector"),
        PRESS_COORDINATED_STRIKE("Signal: Press Fleet Strike"),
        GENERAL_RETREAT_RALLY("Signal: General Recall to Reserve"),
        FULL_ASSAULT_CHARGE("Signal: General Chase / Full Assault");

        public final String displayName;

        FleetIndependentCommand(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * A OTC tasking for one division. Re-issued every OTC evaluation; it lapses at
     * {@link #expiresTime} if the OTC stops issuing it.
     */
    public static class OtcOrder {
        public OtcOrderType type = OtcOrderType.NONE;
        public DivisionState targetEnemy;
        public DivisionState targetFriendly;
        public final Vector2f station = new Vector2f();
        public boolean hasStation;
        public float flankSide; // +1 / -1 relative to the enemy's facing axis, 0 = unassigned
        public PermanentDivisionManager.DivisionFormation orderedFormation = null;
        public PermanentDivisionManager.DivisionSetpieceAttack orderedSetpieceAttack = null;
        public float expiresTime = -100f;
        public String reason = "";
        /** Incremented whenever the type or target changes, so division commanders re-evaluate immediately. */
        public int version;

        public boolean isActive(float now) {
            return type != OtcOrderType.NONE && now <= expiresTime;
        }

        public void clear() {
            if (type != OtcOrderType.NONE) {
                version++;
            }
            type = OtcOrderType.NONE;
            targetEnemy = null;
            targetFriendly = null;
            hasStation = false;
            flankSide = 0f;
            orderedFormation = null;
            orderedSetpieceAttack = null;
            reason = "";
        }

        /** Sets the tasking and bumps {@link #version} only if it actually changed. */
        public void set(OtcOrderType newType, DivisionState enemy, DivisionState friendly, float side, float expires, String why) {
            if (newType != type || enemy != targetEnemy || friendly != targetFriendly || Math.signum(side) != Math.signum(flankSide)) {
                version++;
            }
            type = newType;
            targetEnemy = enemy;
            targetFriendly = friendly;
            flankSide = side;
            orderedFormation = null;
            orderedSetpieceAttack = null;
            hasStation = false;
            expiresTime = expires;
            reason = why != null ? why : "";
        }
    }

    /**
     * A synchronized multi-division strike / setpiece: participants stage at their formation IPs,
     * then all commit at the strike time onto the target division in coordinated formation carriages.
     */
    public static class StrikePlan {
        public DivisionState target;
        public PermanentDivisionManager.MultiDivisionSetpiece setpieceType =
                PermanentDivisionManager.MultiDivisionSetpiece.NONE;
        public final List<DivisionState> participants = new ArrayList<>();
        public final Map<String, Vector2f> marshalOffsets = new HashMap<>();
        public final Map<String, Vector2f> passTargetOffsets = new HashMap<>();
        public final Map<String, PermanentDivisionManager.DivisionSetpieceAttack> participantSetpieces = new HashMap<>();
        public final Map<String, PermanentDivisionManager.DivisionFormation> participantFormations = new HashMap<>();
        public float createdTime;
        public float lastTargetVisibleTime;
        public float strikeTime;
        public boolean committed;
        public float commitTime = -100f;
        public ShipAPI focusShip;
    }

    /**
     * RPG/Commander profile derived from an officer or fleet commander's personality and level.
     */
    public static class CommanderProfile {
        public final PersonAPI person;
        public final String commanderName;
        public final String personalityId;
        public final int level;

        public float aggressionBias;      // 0.3 (timid) .. 1.6 (reckless)
        public float stationKeepingStrictness;  // higher = tighter spacing & stricter pursuit leash
        public float rotationFluxThreshold; // avg flux/hull pressure at which division rotates out
        public float commitForceRatio;  // friendly/enemy ratio needed to press or strike
        public float decisionCadenceSeconds;
        public float intentCommitSeconds;   // how long a non-urgent intent is held before it may change

        public CommanderProfile(PersonAPI person, String fallbackTitle) {
            this.person = person;
            if (person != null && person.getNameString() != null && !person.getNameString().trim().isEmpty()) {
                this.commanderName = person.getNameString().trim();
            } else {
                this.commanderName = fallbackTitle;
            }

            String pId = Personalities.STEADY;
            if (person != null && person.getPersonalityAPI() != null && person.getPersonalityAPI().getId() != null) {
                pId = person.getPersonalityAPI().getId();
            }
            this.personalityId = pId;

            int lvl = 1;
            if (person != null && person.getStats() != null) {
                lvl = Math.max(1, person.getStats().getLevel());
            }
            this.level = lvl;

            float skillBonus = Math.min(0.25f, (lvl - 1) * 0.035f);
            switch (pId) {
                case Personalities.RECKLESS:
                    this.aggressionBias = 1.55f;
                    this.stationKeepingStrictness = 0.78f + skillBonus * 0.5f;
                    this.rotationFluxThreshold = 0.80f;
                    this.commitForceRatio = 0.72f;
                    this.decisionCadenceSeconds = Math.max(2.2f, 3.2f - skillBonus * 2f);
                    this.intentCommitSeconds = 5.0f;
                    break;
                case Personalities.AGGRESSIVE:
                    this.aggressionBias = 1.28f;
                    this.stationKeepingStrictness = 0.95f + skillBonus * 0.5f;
                    this.rotationFluxThreshold = 0.72f;
                    this.commitForceRatio = 0.88f;
                    this.decisionCadenceSeconds = Math.max(2.0f, 3.0f - skillBonus * 2f);
                    this.intentCommitSeconds = 6.5f;
                    break;
                case Personalities.CAUTIOUS:
                    this.aggressionBias = 0.78f;
                    this.stationKeepingStrictness = 1.25f + skillBonus * 0.5f;
                    this.rotationFluxThreshold = 0.56f;
                    this.commitForceRatio = 1.22f;
                    this.decisionCadenceSeconds = Math.max(2.2f, 3.2f - skillBonus * 2f);
                    this.intentCommitSeconds = 9.5f;
                    break;
                case Personalities.TIMID:
                    this.aggressionBias = 0.55f;
                    this.stationKeepingStrictness = 1.38f + skillBonus * 0.5f;
                    this.rotationFluxThreshold = 0.48f;
                    this.commitForceRatio = 1.45f;
                    this.decisionCadenceSeconds = Math.max(2.5f, 3.5f - skillBonus * 2f);
                    this.intentCommitSeconds = 10.0f;
                    break;
                case Personalities.STEADY:
                default:
                    this.aggressionBias = 1.00f;
                    this.stationKeepingStrictness = 1.10f + skillBonus * 0.5f;
                    this.rotationFluxThreshold = 0.65f;
                    this.commitForceRatio = 1.02f;
                    this.decisionCadenceSeconds = Math.max(2.0f, 3.0f - skillBonus * 2f);
                    this.intentCommitSeconds = 8.0f;
                    break;
            }
        }
    }

    /**
     * T-S-F-C Tactical Zone classification for any point or ship position on the battlefield.
     */
    public enum TacticalZoneType {
        SAFE_ZONE("Safe Zone"),
        OVERMATCH_BAND("Overmatch Band"),
        ENGAGEMENT_ZONE("Engagement Zone"),
        KILL_ZONE("Kill Zone");

        public final String displayName;

        TacticalZoneType(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * Intra-division rank-relief state for individual ships within a fighting division.
     */
    public enum ShipRankState {
        FRONT_ENGAGEMENT,
        RELIEF_SAFE,
        RETURNING_FRONT
    }

    /**
     * runtime state of a single division on either side.
     */
    public static class DivisionState {
        public final String id;
        public final int side; // 0 = player, 1 = enemy
        public final int divisionNumber;
        public DivisionArchetype archetype;
        public final String displayName;
        public CommanderProfile commander;
        public DivisionNomenclature.NomenclatureCategory nomenclatureCategory =
                DivisionNomenclature.NomenclatureCategory.HUMAN_NAVAL;

        public final List<ShipAPI> members = new ArrayList<>();
        public final List<ShipCapabilityProfile> profiles = new ArrayList<>();
        public ShipAPI flagship;

        // Capabilities aggregated from member profiles
        public boolean hasMissileStrikeCapability;
        public boolean hasCarrierCapability;
        public boolean isScreenCapable;
        public boolean isFastFlanker;
        public float totalFpStrength;
        public float avgMaxSpeed;
        public float avgEngageRange;

        // Customizable Fleet Doctrine & Division-specific settings (in-game units; Paragon length = 364u)
        public float stationSpacing = 450f;
        public float divisionInterval = 950f;
        public float standoffRange = 850f;
        public float stationKeepingMult = 1.0f;
        public PermanentDivisionManager.DivisionMovementStickiness doctrinalMovementStickiness =
                PermanentDivisionManager.DivisionMovementStickiness.BALANCED;
        public PermanentDivisionManager.DivisionMovementStickiness activeMovementStickiness =
                PermanentDivisionManager.DivisionMovementStickiness.BALANCED;
        public boolean inIndividualCombatMovementMode = false;
        public float openingPhaseDurationSeconds = 45f;
        public PermanentDivisionManager.DivisionMissionType openingMission =
                PermanentDivisionManager.DivisionMissionType.SCOUTING;
        public PermanentDivisionManager.DivisionMissionType mainMission =
                PermanentDivisionManager.DivisionMissionType.CLOSE_ACTION;
        public PermanentDivisionManager.LineStation lineStation =
                PermanentDivisionManager.LineStation.CENTER;
        public PermanentDivisionManager.DivisionFormation doctrinalFormation =
                PermanentDivisionManager.DivisionFormation.LINE_ABREAST;
        public PermanentDivisionManager.DivisionFormation activeFormation =
                PermanentDivisionManager.DivisionFormation.LINE_ABREAST;
        public PermanentDivisionManager.DivisionFormation otcOrderedFormation = null;
        public float formationHeadingX = 0f;
        public float formationHeadingY = 1f;
        public boolean formationHeadingInitialized = false;
        public float lastFormationChangeTime = -999f;

        // Scope Item 1 & 3: Setpiece Division Attacks in Formation & Multi-Division / Fleet-Wide Formation stationing
        public PermanentDivisionManager.DivisionSetpieceAttack doctrinalSetpieceAttack =
                PermanentDivisionManager.DivisionSetpieceAttack.AUTO_TACTICAL;
        public PermanentDivisionManager.DivisionSetpieceAttack activeSetpieceAttack =
                PermanentDivisionManager.DivisionSetpieceAttack.NONE;
        public int setpieceStage = 0; // 0 = Inactive, 1 = Dressing at IP, 2 = Executing Formation Pass
        public float setpieceStageStartTime = -999f;
        public float lastSetpieceEndTime = -999f;
        public final Vector2f setpieceIpPoint = new Vector2f();
        public final Vector2f setpiecePassTarget = new Vector2f();
        public final Vector2f fleetFormationOffset = new Vector2f();
        public boolean hasFleetFormationOffset = false;
        public int fleetFormationRank = 0;
        public int fleetFormationWingSide = 0; // -1 = port wing, 0 = center/apex spearhead, +1 = starboard wing
        public int fleetFormationSlotIndex = -1;
        public int consentedWingSide = 0; // Persistent wing side (-1 port, 0 center, +1 starboard) under OTC/Grand Formation Consent
        public int pursuitSpawnZone = 0;  // -1 = deployed Left (port flank), 0 = Center/Regular, +1 = deployed Right (starboard flank)
        public boolean initialDeploymentMarshalled = false;

        // OTC Force-Distribution Overkill & Combat Threat Weight telemetry
        public float combatThreatWeight = 1.0f; // [0.25..1.0] discounts stray freighters/auxiliaries relative to warships
        public float committedFriendlyFpAgainst = 0f;
        public float overkillCapFp = 0f;
        public boolean isOverkilled = false;

        // Dynamic telemetry updated every evaluation tick
        public final Vector2f centroid = new Vector2f();
        public final Vector2f velocity = new Vector2f();
        public float spreadRadius;
        public float rawAllMemberSpreadRadius;
        public float stationKeepingRadius;
        public float stationKeepingRatio; // spreadRadius / stationKeepingRadius (measured on the core cluster)
        public final Set<String> detachedStragglerIds = new HashSet<>();
        public int coreMemberCount;
        public float coreFpRatio = 1.0f;
        public float avgFluxLevel;
        public float maxMemberFluxLevel;
        public float avgHullLevel;
        public float totalEffectiveHp;
        public float maxEffectiveHp;
        public float effectiveHpRatio = 1.0f;
        public float localFriendlyStrength;
        public float localEnemyStrength;
        public float localForceRatio; // localFriendlyStrength / max(1, localEnemyStrength)
        public float incomingAlphaThreat;
        public float incomingShortDotThreat;
        public float incomingSustainedDotThreat;
        public float incomingPdThreat;
        public float incomingHybridThreat;
        public float crossfireCordonIndex; // 0.0 = unidirectional, >= 0.35 = L-cordon, >= 0.60 = U-cordon
        public float smoothedDangerScore;
        public boolean hasShipNeedingHelp;
        public ShipAPI shipNeedingHelp;
        public int usableStrikeAmmoCount;
        // Explicit violent contact / engagement telemetry (0.0 = out of contact, 1.0 = full weapon/flux exchange)
        public float activeWeaponFiringRatio = 0f;
        public float violentContactIntensity = 0f;
        public float engagedFpMass = 0f;
        // Live weapon firepower & effective-firepower-in-range uptime (Items A, B, C)
        public float liveFirepowerScore = 0f;         // Current ready weapon firepower (discounts reloading/disabled/empty mounts; phase ships keep latent threat)
        public float maxFirepowerScore = 0f;          // Unreloaded / uninjured peak firepower capacity
        public float weaponReadinessRatio = 1.0f;     // liveFirepowerScore / max(1, maxFirepowerScore)
        public float inRangeFirepowerScore = 0f;      // Friendly weapon firepower currently in range & arc of visible enemies
        public float firepowerInRangeUptime = 0f;     // Smoothed ratio of live firepower currently bearing in range (0..1)
        public float incomingInRangeFirepower = 0f;   // Hostile weapon firepower currently in range & arc of this division
        public float firepowerExchangeRatio = 1.0f;   // (inRangeFirepowerScore + 15) / (incomingInRangeFirepower + 15)

        // Time Layer (Mobility horizon & Directional Engagement Level / Fixing)
        public static final int TIME_DIRECTIONS = 24;
        public float weightedTimeSpeed;       // Weighted division speed (FP-weighted + slowest core hull anchor)
        public float freeTimeRadius;          // Unencumbered reach over tactical time horizon
        public float engagementLevel;         // 0.0 = free to maneuver, up to 0.85 = pinned by enemy Space/Firepower
        public float timeFreedomFactor = 1.0f;// 1.0 - engagementLevel
        public float effectiveTimeRadius;     // freeTimeRadius * timeFreedomFactor
        public final float[] directionalEngagement = new float[TIME_DIRECTIONS];
        public final float[] directionalTimeRadii = new float[TIME_DIRECTIONS];
        public boolean directionalTimeInitialized = false;

        // Space Layer (Zone of Control: Dominated Up Close, Dominated Nearby, Potentially Dominated)
        public float spaceInnerRadius;        // Directly dominated up close
        public float spaceDominatedRadius;    // Dominated nearby (sustained weapon reach)
        public float spacePotentialRadius;    // Potentially dominated given Time + Firepower
        public float spaceDominanceWeight;    // Firepower & readiness weighted authority
        public float friendlySpaceSupport;    // Overlapping friendly Space authority at division centroid
        public float hostileSpacePressure;    // Overlapping enemy Space authority at division centroid
        public float netSpaceControl;         // friendlySpaceSupport - hostileSpacePressure
        public boolean isStrongpoint;         // High mutual Space overlap in line of battle
        public boolean isWeakPoint;           // Exposed seam / low mutual Space overlap
        public final float[] directionalSpaceInnerRadii = new float[TIME_DIRECTIONS];
        public final float[] directionalSpaceDominatedRadii = new float[TIME_DIRECTIONS];
        public final float[] directionalSpacePotentialRadii = new float[TIME_DIRECTIONS];
        public final float[] directionalFirepowerRadii = new float[TIME_DIRECTIONS];
        public boolean directionalSpaceInitialized = false;
        public float edgeProximityPinch = 0f; // 0.0 in open center, up to 1.0 when pinched against map boundary

        // "Unable" / retreating unit state (Item F)
        public int ableMemberCount = 0;
        public int retreatingMemberCount = 0;
        public float retreatingFraction = 0f;
        public boolean isUnableDivision = false;

        // Convergence Layer (AI / Threat / Abyssal & Alpha Core-commanded flagship decision-making)
        public boolean useConvergence = false;
        public Boolean forceConvergenceOverride = null;
        public float convergenceScore = 0f;
        public final Vector2f convergenceFocalPoint = new Vector2f();

        // 0.10s TSFC Keyframe Interpolation State (keyframes older than 2.0s are binned/discarded)
        public float prevTsfcKeyframeTime = -100f;
        public float currTsfcKeyframeTime = -100f;
        public boolean tsfcKeyframesInitialized = false;
        public float prevFreeTimeRadius = 0f;
        public float prevEffectiveTimeRadius = 0f;
        public float prevEngagementLevel = 0f;
        public float prevSpaceInnerRadius = 0f;
        public float prevSpaceDominatedRadius = 0f;
        public float prevSpacePotentialRadius = 0f;
        public float prevSpaceDominanceWeight = 0f;
        public float prevCrossfireCordonIndex = 0f;
        public float prevConvergenceScore = 0f;
        public final Vector2f prevConvergenceFocalPoint = new Vector2f();
        public final float[] prevDirectionalTimeRadii = new float[TIME_DIRECTIONS];
        public final float[] prevDirectionalSpaceInnerRadii = new float[TIME_DIRECTIONS];
        public final float[] prevDirectionalSpaceDominatedRadii = new float[TIME_DIRECTIONS];
        public final float[] prevDirectionalSpacePotentialRadii = new float[TIME_DIRECTIONS];
        public final float[] prevDirectionalFirepowerRadii = new float[TIME_DIRECTIONS];

        // Current C2 orders, station endpoint, and out-of-contact wide-arc route waypoint
        public DivisionIntentType currentIntent = DivisionIntentType.CLOSE_UP;
        public DivisionState targetEnemyDivision;
        public DivisionState targetFriendlyDivision;
        public BattleObjectiveAPI targetObjective;
        public final Vector2f stationPoint = new Vector2f();
        public final Vector2f routeWaypoint = new Vector2f();
        public boolean hasRouteWaypoint = false;
        public float routeSkirtSide = 0f;
        public final Map<String, Float> stragglerSkirtSideByShipId = new HashMap<>();
        // Persistent intra-division station-keeping slot assignment (shipId -> slot index 0..n-1)
        public final Map<String, Integer> assignedSlotByShipId = new HashMap<>();
        public PermanentDivisionManager.DivisionFormation lastSlotAssignedFormation = null;
        public int lastSlotAssignedCount = 0;
        // Tracks wings that already completed a Voidstrike sortie against the current target so non-returning
        // fighter escorts do not keep an eternal Voidstrike alive after the strike wave culminates
        public final Set<String> culminatedVoidstrikeWingKeys = new HashSet<>();
        public String culminatedVoidstrikeTargetKey = null;

        public AssignmentTargetAPI activeWaypoint;
        public AssignmentInfo activeAssignment;
        public float lastIntentChangeTime = -100f;
        public float lastLogAnnouncementTime = -100f;
        public String lastLoggedSignature = "";
        public float lastHudAnnouncementTime = -100f;
        public DivisionIntentType lastHudIntent = null;
        public float strikeCommittedTimestamp = -100f;
        public float lastBreakOffTimestamp = -100f;
        public boolean isDesignatedReserve = false;
        /** True while the division is executing a player Command Window objective order (outranks OTC tasking). */
        public boolean underDirectObjectiveOrder = false;
        public boolean isDesignatedMainEffort = false;

        // Battle Group orchestration & intent commitment
        public final OtcOrder otcOrder = new OtcOrder();
        public int appliedOtcOrderVersion = -1;
        public float intentCommitUntil = -100f;
        public final Set<String> fighterPullbackShipIds = new HashSet<>();

        // Carrier & Wing Voidstrike Doctrine & Staging (Items C, D, E, D2, F, G, H)
        public PermanentDivisionManager.VoidstrikeTargetObservance voidstrikeObservance =
                PermanentDivisionManager.VoidstrikeTargetObservance.MEDIUM;
        public PermanentDivisionManager.VoidstrikeCoordinationMode voidstrikeCoordination =
                PermanentDivisionManager.VoidstrikeCoordinationMode.COORDINATED;
        public PermanentDivisionManager.VoidstrikeAttackGeometry voidstrikeAttackGeometry =
                PermanentDivisionManager.VoidstrikeAttackGeometry.DIRECT_ASSAULT;
        public PermanentDivisionManager.VoidstrikeSplitMode voidstrikeSplitMode =
                PermanentDivisionManager.VoidstrikeSplitMode.UNIFIED;
        public PermanentDivisionManager.VoidstrikeGunneryMode voidstrikeGunneryMode =
                PermanentDivisionManager.VoidstrikeGunneryMode.PRECISION;
        public PermanentDivisionManager.VoidstrikeAoaCorrection voidstrikeAoaCorrection =
                PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG;
        public PermanentDivisionManager.ShipSkillCoordinationMode shipSkillCoordination =
                PermanentDivisionManager.ShipSkillCoordinationMode.SYNCHRONIZED;
        public PermanentDivisionManager.DivisionRammingMode rammingMode =
                PermanentDivisionManager.DivisionRammingMode.OFF;
        public PermanentDivisionManager.DivisionRammingTargetScope rammingTargetScope =
                PermanentDivisionManager.DivisionRammingTargetScope.SINGLE_SHIP;
        public int maxRammingAttackSlots = 1;

        // Synchronized Ship Skill telemetry & active maneuver state
        public int ramCapableMemberCount = 0;
        public int linearThrustMemberCount = 0;
        public int offensiveAmpMemberCount = 0;
        public int blinkMemberCount = 0;
        public float readySkillMemberRatio = 0f;
        public boolean hasActiveLinearSkillBurn = false;
        public boolean synchronizedSkillManeuverActive = false;
        public int synchronizedSkillStage = 0; // 0 = Inactive, 1 = Marshaling/Aligning, 2 = Executing Synchronized Skill
        public float synchronizedSkillStageStartTime = -999f;
        public float lastSynchronizedSkillEndTime = -999f;
        public String activeSkillManeuverLabel = "";
        public PermanentDivisionManager.SquadronFormation fighterFormation =
                PermanentDivisionManager.SquadronFormation.WEDGE;
        public PermanentDivisionManager.SquadronFormation fighterVoidstrikeFormation =
                PermanentDivisionManager.SquadronFormation.WEDGE;
        public PermanentDivisionManager.SquadronFormation bomberFormation =
                PermanentDivisionManager.SquadronFormation.LINE_ABREAST;
        public PermanentDivisionManager.SquadronFormation bomberVoidstrikeFormation =
                PermanentDivisionManager.SquadronFormation.LINE_ABREAST;
        public int voidstrikeSquadronDepth = 2;
        public int voidstrikeSquadronWidth = 2;

        // Coordinated Voidstrike staging (0 = Orbit Carrier, 1 = Form Up at Marshal Point, 2 = Attack Run)
        public int voidstrikeStage = 2;
        public float voidstrikeStageStartTime = -999f;
        public String voidstrikeLastTargetKey = null;
        public boolean voidstrikeEgressActive = false;
        /**
         * Voidstrike number of this division's current strike sortie (0 = none). A division keeps ONE number for the
         * whole sortie (launch -> culmination/egress), so wing retargets, fog flicker or cluster splits re-attach to it
         * instead of burning fresh numbers. Cleared when egress begins.
         */
        public int activeVoidstrikeSortieNumber = 0;
        public boolean voidstrikeEmergencyRecallActive = false;
        public float lastVoidstrikePackageMass = 0f;
        public float lastVoidstrikeEffectiveSpeed = 180f;
        public float lastVoidstrikeApproachTimeoutSec = 45f;
        public float lastVoidstrikePassTimeoutSec = 24f;
        public final Vector2f voidstrikeMarshalPoint = new Vector2f(0f, 0f);
        public int voidstrikeTerminalAlignedCraftCount = 0;
        public int voidstrikeFireGatedCraftCount = 0;
        public float lastVoidstrikeSpanWidth = 0f;

        // Item E (Trade Space for Time / Tactical Retreat) & Item F (Feigned Retreat & Trap Suspicion)
        public final Vector2f spaceOffsetVector = new Vector2f(0f, 0f);
        public float tradeSpaceStartTime = -100f;
        public float lastTradeSpaceEndTime = -100f;
        public int feignedRetreatStage = 0; // 0 = inactive, 1 = bait withdrawal, 2 = spring alpha-strike trap
        public float feignedRetreatStartTime = -100f;
        public float lastFeignedRetreatEndTime = -100f;
        public float closingSpeedToEnemy = 0f;
        public float enemyFeignedRetreatSuspicion = 0f;

        // T-S-F-C Engagement Zone & Safe Zone Hierarchy + Intra-Division Double-Line Rank Relief
        public final Map<String, ShipRankState> shipRankStateByShipId = new HashMap<>();
        public final Map<String, TacticalZoneType> shipZoneTypeByShipId = new HashMap<>();
        public final Map<String, Integer> assignedReliefSlotByShipId = new HashMap<>();
        /** True when this division has compressed its frontage and added a rear relief rank (defensive or offensive depth). */
        public boolean inDoubleLineRelief = false;
        public int engagementZoneShipCount = 0;
        public int safeZoneShipCount = 0;
        public float engagementZoneFp = 0f;
        public float reliefRankDepth = 460f;
        public final Vector2f safeZonePoint = new Vector2f(0f, 0f);
        public TacticalZoneType currentDivisionZone = TacticalZoneType.SAFE_ZONE;
        /** +1 = offensive depth column (spearhead through a narrow gap), -1 = defensive rank relief (venting), 0 = none. */
        public int depthFormationMode = 0;
        /** Cantabrian Circle (offensive / defensive) on CIRCLE / PROTECTIVE_CIRCLE formations. */
        public boolean cantabrianCircleActive = false;
        public boolean cantabrianOffensive = false;
        public float cantabrianPhase = 0f;
        // Inter-division Mutual Cover & Fleet Line-Passing state
        public DivisionState coveringFriendDivision = null;
        public boolean isCoveringFriend = false;
        public boolean underCoverByFriend = false;
        public boolean linePassActive = false;
        public float linePassUntil = -100f;
        public int fleetBattleSequenceStage = 0; // 0 = none, 1 = Trade Space for Time, 2 = Feigned Retreat lure, 3 = Alpha Strike counter

        public DivisionState(
                String id,
                int side,
                int divisionNumber,
                DivisionArchetype archetype,
                String displayName,
                CommanderProfile commander) {
            this.id = id;
            this.side = side;
            this.divisionNumber = divisionNumber;
            this.archetype = archetype;
            this.displayName = displayName;
            this.commander = commander;
        }

        public static boolean isShipUnable(ShipAPI s) {
            if (s == null || !s.isAlive() || s.isHulk() || s.isExpired()) return true;
            if (s.isDirectRetreat()) return true;
            if (!s.isRetreating()) return false;
            try {
                com.fs.starfarer.api.combat.CombatEngineAPI engine = com.fs.starfarer.api.Global.getCombatEngine();
                if (engine != null) {
                    int owner = s.getOwner();
                    com.fs.starfarer.api.combat.BattleCreationContext ctx = engine.getContext();
                    if (ctx != null) {
                        com.fs.starfarer.api.fleet.FleetGoal goal = (owner == 0) ? ctx.getPlayerGoal() : ctx.getOtherGoal();
                        if (goal == com.fs.starfarer.api.fleet.FleetGoal.ESCAPE) {
                            return false;
                        }
                    }
                    com.fs.starfarer.api.combat.CombatFleetManagerAPI fm = engine.getFleetManager(owner);
                    if (fm != null && fm.getGoal() == com.fs.starfarer.api.fleet.FleetGoal.ESCAPE) {
                        return false;
                    }
                }
            } catch (Throwable ignored) {
            }
            return true;
        }

        public static boolean isDivisionEscapingInPursuit(
                com.fs.starfarer.api.combat.CombatEngineAPI engine,
                DivisionState div) {
            if (div == null) return false;
            if (div.isUnableDivision || div.retreatingFraction >= 0.35f) return true;
            for (int i = 0, n = div.members.size(); i < n; i++) {
                ShipAPI s = div.members.get(i);
                if (s != null && s.isAlive() && !s.isHulk() && s.isRetreating()) {
                    return true;
                }
            }
            if (engine != null) {
                try {
                    com.fs.starfarer.api.combat.BattleCreationContext ctx = engine.getContext();
                    if (ctx != null) {
                        com.fs.starfarer.api.fleet.FleetGoal goal = (div.side == 0) ? ctx.getPlayerGoal() : ctx.getOtherGoal();
                        if (goal == com.fs.starfarer.api.fleet.FleetGoal.ESCAPE) {
                            return true;
                        }
                    }
                    com.fs.starfarer.api.combat.CombatFleetManagerAPI fm = engine.getFleetManager(div.side);
                    if (fm != null && fm.getGoal() == com.fs.starfarer.api.fleet.FleetGoal.ESCAPE) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            return false;
        }

        public List<ShipAPI> getAliveMembers() {
            List<ShipAPI> alive = new ArrayList<>(members.size());
            for (ShipAPI s : members) {
                if (s != null && s.isAlive() && !s.isHulk() && !s.isExpired()) {
                    alive.add(s);
                }
            }
            return alive;
        }

        public List<ShipAPI> getAbleMembers() {
            List<ShipAPI> able = new ArrayList<>(members.size());
            for (ShipAPI s : members) {
                if (!isShipUnable(s)) {
                    able.add(s);
                }
            }
            return able;
        }

        public boolean isCombatEffective() {
            for (int i = 0, n = members.size(); i < n; i++) {
                ShipAPI s = members.get(i);
                if (s != null && s.isAlive() && !s.isHulk() && !s.isExpired()) {
                    return true;
                }
            }
            return false;
        }

        public boolean isAbleCombatEffective() {
            if (isUnableDivision) return false;
            for (int i = 0, n = members.size(); i < n; i++) {
                if (!isShipUnable(members.get(i))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Runtime state of a side's OTC and all of its divisions.
     */
    public static class BattleGroupState {
        public final int side;
        public boolean isAlliedAi = false;
        public boolean isPassiveOpposingView = false;
        public CommanderProfile otc;
        public DivisionNomenclature.NomenclatureCategory nomenclatureCategory =
                DivisionNomenclature.NomenclatureCategory.HUMAN_NAVAL;
        public String factionId = "independent";
        public MissionContextType missionContext = MissionContextType.FLEET_ACTION;
        public BattlePhase currentPhase = BattlePhase.APPROACH;
        public BattlePhase previousPhase = null;

        public final List<DivisionState> divisions = new ArrayList<>();
        public final Map<String, DivisionState> shipIdToDivision = new HashMap<>();

        public final Vector2f fleetCentroid = new Vector2f();
        public final Vector2f reserveStation = new Vector2f();
        public final Vector2f fleetSafeCorridorCenter = new Vector2f();
        public final Vector2f fleetEngagementZoneCenter = new Vector2f();
        public float totalFriendlyStrength;
        public float totalEnemyStrength;
        public float forceRatio = 1.0f;

        // Explicit Fleet-Wide Engagement / Violent Contact Telemetry (mass-weighted across all divisions)
        public float engagedFpMass = 0f;
        public float engagedMassFraction = 0f;
        public float fleetEngagementLevel = 0f;
        public float smoothedEngagementLevel = 0f;
        public int engagedDivisionCount = 0;
        public boolean mainEffortEngaged = false;
        public float fleetLiveFirepower = 0f;
        public float fleetInRangeFirepower = 0f;
        public float fleetFirepowerUptime = 0f;

        // Item D: Awareness of Friendly Independent Ships (disposition, centroid, velocity, FP, and org ratio)
        public final List<ShipAPI> friendlyIndependentShips = new ArrayList<>();
        public final Vector2f independentCentroid = new Vector2f(0f, 0f);
        public final Vector2f independentVelocity = new Vector2f(0f, 0f);
        public final Vector2f independentProjectedCentroid = new Vector2f(0f, 0f);
        public float friendlyIndependentFp = 0f;
        public float friendlyIndependentLiveFirepower = 0f;
        public float friendlyIndependentEhp = 0f;
        public float organizationRatio = 1.0f;
        /** Simple fleet-wide general signal issued by the OTC to all friendly independent ships. */
        public FleetIndependentCommand activeIndependentCommand = FleetIndependentCommand.NONE;
        public final Vector2f fleetIndependentCommandPoint = new Vector2f(0f, 0f);
        public final Vector2f fleetIndependentCarrierPoint = new Vector2f(0f, 0f);
        public float lastIndependentCommandUpdateTime = -100f;

        // Item E & F: Fleet-Wide Trade Space for Time & Feigned Retreat state
        public final Vector2f fleetSpaceOffsetVector = new Vector2f(0f, 0f);
        public boolean fleetTradeSpaceActive = false;
        public float fleetTradeSpaceUntil = -100f;
        public boolean fleetFeignedRetreatActive = false;
        public int fleetFeignedRetreatStage = 0; // 0 = inactive, 1 = lure, 2 = spring alpha strike
        public float fleetFeignedRetreatStartTime = -100f;
        public float lastFleetFeignedRetreatEndTime = -100f;
        public DivisionState feignedRetreatBaitDivision = null;
        public DivisionState feignedRetreatTargetEnemy = null;
        // Fleet-Wide Engage -> Trade Space -> Feigned Retreat -> Alpha Strike battle-sequence state
        public int fleetBattleSequenceStage = 0;
        public float fleetBattleSequenceStartTime = -100f;
        public float lastFleetBattleSequenceEndTime = -100f;
        public final Map<String, DivisionState> linePassCovererByDivisionId = new HashMap<>();

        public DivisionState mainEffortDivision;
        public DivisionState reserveDivision;
        public DivisionState primaryThreatEnemyDivision;
        public boolean useConvergence = false;
        public Boolean forceConvergenceOverride = null;
        public ShipAPI activeFleetFlagship = null;

        // Player OTC reassignment / command disruption
        /** Ship id explicitly selected as the player's OTC vessel (survives across battles until flagship dies). */
        public String preferredOtcShipId = null;
        /** True when the currently active OTC was explicitly chosen by the player. */
        public boolean otcManuallyAssigned = false;
        /** Combat time until which this side's OTC command is disabled (flagship destruction). */
        public float otcDisabledUntilTime = -100f;
        /** True while the player's OTC disabled countdown is actively running. */
        public boolean otcDisabledWarningActive = false;
        /** Last player OTC reassignment time (prevents multi-click charge stacking). */
        public float lastOtcReassignmentTime = -100f;
        /** True once an Alpha Core assumes OTC during battle (deliberate swap, Convergence takeover, or deployed start). */
        public boolean alphaCoreOtcLockedInBattle = false;
        /** True when the player's OTC ship is destroyed and the game pauses to show the OTC selection screen. */
        public boolean emergencyOtcSelectionPending = false;
        /** Successor flagship chosen by the OTC/Flagship loss succession hierarchy. */
        public ShipAPI designatedSuccessorFlagship = null;
        /** Number of OTC/Flagship successions that have occurred on this side during battle. */
        public int successionCount = 0;
        /** True when the player side's designated OTC ship is not deployed in battle (-75% max CP, -50% CP regen). */
        public boolean otcNotDeployedPenaltyActive = false;

        public float lastOtcEvaluationTime = -100f;
        public float lastOtcSignalLogTime = -100f;
        public String lastOtcSignalSignature = "";
        public float lastOtcHudLogTime = -100f;
        public BattlePhase lastOtcHudPhase = null;

        // Battle Group orchestration (configured in Fleet Doctrine for the player; defaults for AI battlegroups)
        public PermanentDivisionManager.OtcAuthority authority =
                PermanentDivisionManager.OtcAuthority.COMMAND_BY_DIRECTION;
        public PermanentDivisionManager.FleetWideFormation fleetWideFormation =
                PermanentDivisionManager.FleetWideFormation.DOCTRINAL_AUTO;
        public PermanentDivisionManager.FleetWideFormation activeFleetWideFormation =
                PermanentDivisionManager.FleetWideFormation.DOCTRINAL_AUTO;
        public PermanentDivisionManager.MultiDivisionSetpiece multiDivisionSetpieceDoctrine =
                PermanentDivisionManager.MultiDivisionSetpiece.AUTO_DOCTRINAL;
        public PermanentDivisionManager.MultiDivisionSetpiece activeMultiDivisionSetpiece =
                PermanentDivisionManager.MultiDivisionSetpiece.NONE;
        public boolean deployInFleetFormation = true;
        public boolean orchestratedAdvance = true;
        public boolean orchestratedAdvanceContactMade = false;
        public boolean initialDeploymentDone = false;
        public boolean grandFormationConsentLocked = false;
        public int consentedDivisionCount = 0;
        public final Vector2f enemyCenterOfGravity = new Vector2f();
        public boolean hasEnemyCenterOfGravity = false;
        public final Set<String> formationSpawnHijackedShipIds = new HashSet<>();
        public final Set<PermanentDivisionManager.OtcPlay> plays =
                EnumSet.of(
                        PermanentDivisionManager.OtcPlay.LINE_AND_FIRE_DISTRIBUTION,
                        PermanentDivisionManager.OtcPlay.DOUBLING,
                        PermanentDivisionManager.OtcPlay.TIME_ON_TARGET_STRIKES,
                        PermanentDivisionManager.OtcPlay.RESERVE_AND_GENERAL_CHASE);
        public float phaseEnteredTime = 0f;
        public DivisionState crisisDivision;
        public DivisionState reinforcingDivision;
        public boolean reserveCommitted = false;
        public StrikePlan strikePlan;
        public float lastStrikePlanEndTime = -100f;
        public final Map<String, Float> orderLogTimes = new HashMap<>();

        public boolean isPlayEnabled(PermanentDivisionManager.OtcPlay play) {
            return plays.contains(play);
        }

        public BattleGroupState(int side, CommanderProfile otc) {
            this.side = side;
            this.otc = otc;
        }
    }
}
