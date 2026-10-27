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
     */
    public enum DivisionIntentType {
        CLOSE_UP("close up and re-form"),
        CLOSE_AND_ENGAGE("close and engage"),
        HARASSING_MISSILE_ATTACK("make a harassing missile attack on"),
        TOT_MISSILE_STRIKE("deliver a time-on-target missile strike on"),
        PRESS_HOME("press home the attack on"),
        TAKE_SCREENING_STATION("take screening station on"),
        REINFORCE_DIVISION("move to reinforce"),
        WORK_FLANK("work around the flank of"),
        BREAK_OFF_AND_RETIRE("break off and retire from"),
        HAUL_OUT_TO_VENT("haul out of the line to vent behind"),
        HOLD_IN_RESERVE("hold in reserve, awaiting orders"),
        TAKE_AND_HOLD("take and hold"),
        FLY_ARMED_RECONNAISSANCE("fly armed reconnaissance toward"),
        FLY_FIGHTER_SWEEP("fly fighter sweeps for space superiority against"),
        FLY_COMBAT_SPACE_PATROL("fly combat space patrol over"),
        FLY_CYCLIC_STRIKES("fly cyclic strikes against"),
        LAUNCH_DECKLOAD_STRIKE("launch a deckload strike on"),
        LAUNCH_ALPHA_STRIKE("launch an alpha strike on"),
        FORM_LINE_AND_FIX("form the line and fix"),
        MARSHAL_FOR_STRIKE("proceed to the marshal point to strike"),
        GIVE_GENERAL_CHASE("give general chase to");

        public final String verbPhrase;

        DivisionIntentType(String verbPhrase) {
            this.verbPhrase = verbPhrase;
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
        GENERAL_CHASE            // exploit: pursue a broken enemy division within the pursuit leash
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
            hasStation = false;
            expiresTime = expires;
            reason = why != null ? why : "";
        }
    }

    /**
     * A synchronized multi-division strike: participants stage, then all commit at the strike time
     * onto one focus ship of the target division.
     */
    public static class StrikePlan {
        public DivisionState target;
        public final List<DivisionState> participants = new ArrayList<>();
        public final Map<String, Vector2f> marshalOffsets = new HashMap<>();
        public float createdTime;
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
     * Runtime state of a single division on either side.
     */
    public static class DivisionState {
        public final String id;
        public final int side; // 0 = player side, 1 = enemy side
        public final int divisionNumber;
        public final DivisionArchetype archetype;
        public final String displayName;
        public final CommanderProfile commander;
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
        public float openingPhaseDurationSeconds = 45f;
        public PermanentDivisionManager.DivisionMissionType openingMission =
                PermanentDivisionManager.DivisionMissionType.SCOUTING;
        public PermanentDivisionManager.DivisionMissionType mainMission =
                PermanentDivisionManager.DivisionMissionType.CLOSE_ACTION;
        public PermanentDivisionManager.LineStation lineStation =
                PermanentDivisionManager.LineStation.CENTER;

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
        public float localFriendlyStrength;
        public float localEnemyStrength;
        public float localForceRatio; // localFriendlyStrength / max(1, localEnemyStrength)
        public float incomingAlphaThreat;
        public float incomingShortDotThreat;
        public float incomingSustainedDotThreat;
        public float incomingPdThreat;
        public float crossfireCordonIndex; // 0.0 = unidirectional, >= 0.35 = L-cordon, >= 0.60 = U-cordon
        public float smoothedDangerScore;
        public boolean hasShipNeedingHelp;
        public ShipAPI shipNeedingHelp;
        public int usableStrikeAmmoCount;

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
        public boolean isDesignatedMainEffort = false;

        // Battle Group orchestration & intent commitment
        public final OtcOrder otcOrder = new OtcOrder();
        public int appliedOtcOrderVersion = -1;
        public float intentCommitUntil = -100f;
        public final Set<String> fighterPullbackShipIds = new HashSet<>();

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

        public List<ShipAPI> getAliveMembers() {
            List<ShipAPI> alive = new ArrayList<>(members.size());
            for (ShipAPI s : members) {
                if (s != null && s.isAlive() && !s.isHulk() && !s.isExpired()) {
                    alive.add(s);
                }
            }
            return alive;
        }

        public boolean isCombatEffective() {
            return !getAliveMembers().isEmpty();
        }
    }

    /**
     * Runtime state of a side's OTC and all of its divisions.
     */
    public static class BattleGroupState {
        public final int side;
        public boolean isAlliedAi = false;
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
        public float totalFriendlyStrength;
        public float totalEnemyStrength;
        public float forceRatio = 1.0f;

        public DivisionState mainEffortDivision;
        public DivisionState reserveDivision;
        public DivisionState primaryThreatEnemyDivision;

        public float lastOtcEvaluationTime = -100f;
        public float lastOtcSignalLogTime = -100f;
        public String lastOtcSignalSignature = "";
        public float lastOtcHudLogTime = -100f;
        public BattlePhase lastOtcHudPhase = null;

        // Battle Group orchestration (configured in Fleet Doctrine for the player; defaults for AI battlegroups)
        public PermanentDivisionManager.OtcAuthority authority =
                PermanentDivisionManager.OtcAuthority.COMMAND_BY_DIRECTION;
        public final Set<PermanentDivisionManager.OtcPlay> plays =
                EnumSet.allOf(PermanentDivisionManager.OtcPlay.class);
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
