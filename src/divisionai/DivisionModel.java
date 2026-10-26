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
 * Core data structures for Battlegroup Commander and Division Commander AI.
 */
public class DivisionModel {

    /**
     * How a division was formed and what kind of ship composition it represents.
     */
    public enum DivisionArchetype {
        BATTLE_DIVISION("Battle Div", true),
        FLEET_CARRIER_DIVISION("Fleet Carrier Div", false),
        LIGHT_CARRIER_DIVISION("Light Carrier Div", false),
        ESCORT_CARRIER_DIVISION("Escort Carrier Div", false),
        CARRIER_AND_ESCORT("Mixed Carrier Div", true),
        CRUISER_DIVISION("Cruiser Div", false),
        STRIKE_CRUISER_DIVISION("Strike Cruiser Div", false),
        DESTROYER_DIVISION("Destroyer Div", false),
        TORPEDO_DESTROYER_DIVISION("Destroyer Div", false),
        FRIGATE_DIVISION("Frigate Div", false),
        PHASE_DIVISION("Phase Div", false),
        AUXILIARY_CONVOY("Aux Group", true);

        public final String shortPrefix;
        public final boolean isComposite;

        DivisionArchetype(String shortPrefix, boolean isComposite) {
            this.shortPrefix = shortPrefix;
            this.isComposite = isComposite;
        }

        public boolean isCarrierArchetype() {
            return this == FLEET_CARRIER_DIVISION
                    || this == LIGHT_CARRIER_DIVISION
                    || this == ESCORT_CARRIER_DIVISION
                    || this == CARRIER_AND_ESCORT;
        }
    }

    /**
     * Mission-level context derived from BattleCreationContext, FleetGoal, and objectives.
     */
    public enum MissionContextType {
        OBJECTIVE_CONTEST("Objective Contest"),
        FULL_PITCHED_BATTLE("Pitched Battle"),
        FIGHT_TO_THE_LAST("Fight to the Last"),
        FLEET_ESCAPE_REARGUARD("Rearguard Escape"),
        PURSUIT_INTERCEPTION("Pursuit Interception");

        public final String displayName;

        MissionContextType(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * High-level operational phase chosen by the Battlegroup Commander.
     */
    public enum BattlePhase {
        OPENING_PROBE("Opening Probe & Objective Seizure"),
        LINE_CONTACT("Main Line Engagement"),
        COORDINATED_STRIKE("Coordinated Multi-Division Strike"),
        STABILIZE_AND_RELIEVE("Line Stabilization & Relief"),
        EXPLOIT_BREAKTHROUGH("Breakthrough Exploitation"),
        ORDERED_WITHDRAWAL("Ordered Phased Withdrawal");

        public final String displayName;

        BattlePhase(String displayName) {
            this.displayName = displayName;
        }
    }

    /**
     * Concrete tactical intent chosen by a Division Commander (or directed by BG Commander).
     */
    public enum DivisionIntentType {
        HOLD_AND_COHERE("form up and maintain division cohesion"),
        ADVANCE_ENGAGE_LINE("advance and engage"),
        HARASS_TORPEDO_ATTACK("launch harassing missile attack at"),
        COORDINATED_TORPEDO_STRIKE("execute coordinated missile strike against"),
        PRESS_OVERMATCH("press local overmatch against"),
        SCREEN_FRIENDLY_DIVISION("screen and escort"),
        RELIEVE_FRIENDLY_DIVISION("move to relieve"),
        FLANK_ENEMY_DIVISION("swing wide to flank"),
        STRIKE_AND_WITHDRAW("complete strike window and withdraw to reserve from"),
        ROTATE_TO_RESERVE("rotate high-flux hulls back to reserve behind"),
        HOLD_IN_RESERVE("hold in battlegroup reserve awaiting commitment"),
        SEIZE_OBJECTIVE("secure tactical objective"),
        CARRIER_STRIKE_CRAFT_RECON("conduct strike craft reconnaissance toward"),
        CARRIER_VOID_SUPERIORITY_CAP("contest void superiority with strike craft against"),
        CARRIER_PROTECT_FLEET_CAP("provide strike craft fleet air defense over"),
        CARRIER_STRIKE_AT_WILL("launch successive strike craft attacks on"),
        CARRIER_DIVISION_STRIKE("execute division coordinated strike craft attack on"),
        CARRIER_MULTI_DIV_STRIKE("execute multi-division coordinated strike craft attack on"),
        FIX_ENEMY_DIVISION("anchor the battle line and fix"),
        STAGE_FOR_STRIKE("stage for synchronized strike against"),
        PURSUE_BROKEN_ENEMY("pursue and destroy broken");

        public final String verbPhrase;

        DivisionIntentType(String verbPhrase) {
            this.verbPhrase = verbPhrase;
        }
    }

    /**
     * Kind of task the Battlegroup Commander hands a division while orchestrating the battle.
     */
    public enum BgTaskType {
        NONE,
        FIX_PRIMARY,      // Main Effort: hold a fixing position on the BG primary threat
        SUPPORT_ATTACK,   // engage a specific allocated enemy division
        FLANK,            // envelop the target enemy division from an assigned flank side
        STRIKE_STAGE,     // move to a staging point and wait for the strike time
        STRIKE_COMMIT,    // strike time reached: everyone commits onto the shared focus target
        RELIEVE,          // the one division sent to relieve the current crisis
        HOLD_RESERVE,     // stay in battlegroup reserve until committed
        PURSUE            // exploit: pursue a broken enemy division within the pursuit leash
    }

    /**
     * A Battlegroup Commander tasking for one division. Re-issued every BG evaluation; it lapses at
     * {@link #expiresTime} if the BG stops issuing it.
     */
    public static class BgTasking {
        public BgTaskType type = BgTaskType.NONE;
        public DivisionState targetEnemy;
        public DivisionState targetFriendly;
        public final Vector2f anchor = new Vector2f();
        public boolean hasAnchor;
        public float flankSide; // +1 / -1 relative to the enemy's facing axis, 0 = unassigned
        public float expiresTime = -100f;
        public String reason = "";
        /** Incremented whenever the type or target changes, so division commanders re-evaluate immediately. */
        public int version;

        public boolean isActive(float now) {
            return type != BgTaskType.NONE && now <= expiresTime;
        }

        public void clear() {
            if (type != BgTaskType.NONE) {
                version++;
            }
            type = BgTaskType.NONE;
            targetEnemy = null;
            targetFriendly = null;
            hasAnchor = false;
            flankSide = 0f;
            reason = "";
        }

        /** Sets the tasking and bumps {@link #version} only if it actually changed. */
        public void set(BgTaskType newType, DivisionState enemy, DivisionState friendly, float side, float expires, String why) {
            if (newType != type || enemy != targetEnemy || friendly != targetFriendly || Math.signum(side) != Math.signum(flankSide)) {
                version++;
            }
            type = newType;
            targetEnemy = enemy;
            targetFriendly = friendly;
            flankSide = side;
            hasAnchor = false;
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
        public final Map<String, Vector2f> stagingAnchors = new HashMap<>();
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
        public float cohesionStrictness;  // higher = tighter spacing & stricter pursuit leash
        public float rotationFluxThreshold; // avg flux/hull pressure at which division rotates out
        public float overmatchCommitRatio;  // friendly/enemy ratio needed to press or strike
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
                    this.cohesionStrictness = 0.78f + skillBonus * 0.5f;
                    this.rotationFluxThreshold = 0.80f;
                    this.overmatchCommitRatio = 0.72f;
                    this.decisionCadenceSeconds = Math.max(2.2f, 3.2f - skillBonus * 2f);
                    this.intentCommitSeconds = 5.0f;
                    break;
                case Personalities.AGGRESSIVE:
                    this.aggressionBias = 1.28f;
                    this.cohesionStrictness = 0.95f + skillBonus * 0.5f;
                    this.rotationFluxThreshold = 0.72f;
                    this.overmatchCommitRatio = 0.88f;
                    this.decisionCadenceSeconds = Math.max(2.0f, 3.0f - skillBonus * 2f);
                    this.intentCommitSeconds = 6.5f;
                    break;
                case Personalities.CAUTIOUS:
                    this.aggressionBias = 0.78f;
                    this.cohesionStrictness = 1.25f + skillBonus * 0.5f;
                    this.rotationFluxThreshold = 0.56f;
                    this.overmatchCommitRatio = 1.22f;
                    this.decisionCadenceSeconds = Math.max(2.2f, 3.2f - skillBonus * 2f);
                    this.intentCommitSeconds = 9.5f;
                    break;
                case Personalities.TIMID:
                    this.aggressionBias = 0.55f;
                    this.cohesionStrictness = 1.38f + skillBonus * 0.5f;
                    this.rotationFluxThreshold = 0.48f;
                    this.overmatchCommitRatio = 1.45f;
                    this.decisionCadenceSeconds = Math.max(2.5f, 3.5f - skillBonus * 2f);
                    this.intentCommitSeconds = 10.0f;
                    break;
                case Personalities.STEADY:
                default:
                    this.aggressionBias = 1.00f;
                    this.cohesionStrictness = 1.10f + skillBonus * 0.5f;
                    this.rotationFluxThreshold = 0.65f;
                    this.overmatchCommitRatio = 1.02f;
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

        public final List<ShipAPI> members = new ArrayList<>();
        public final List<ShipCapabilityProfile> profiles = new ArrayList<>();
        public ShipAPI flagship;

        // Capabilities aggregated from member profiles
        public boolean hasTorpedoStrikeCapability;
        public boolean hasCarrierCapability;
        public boolean isScreenCapable;
        public boolean isFastFlanker;
        public float totalFpStrength;
        public float avgMaxSpeed;
        public float avgEngageRange;

        // Customizable Fleet Doctrine & Division-specific settings (in-game units; Paragon length = 364u)
        public float idealIntraSpacing = 450f;
        public float idealInterSpacing = 950f;
        public float lineStandoffDistance = 850f;
        public float cohesionLeashMult = 1.0f;
        public float openingPhaseDurationSeconds = 45f;
        public PermanentDivisionManager.DivisionMissionType openingMission =
                PermanentDivisionManager.DivisionMissionType.RECONNAISSANCE;
        public PermanentDivisionManager.DivisionMissionType mainMission =
                PermanentDivisionManager.DivisionMissionType.DOMINATE_SPACE_INTO_BRAWL;
        public PermanentDivisionManager.PreferredSpace preferredSpace =
                PermanentDivisionManager.PreferredSpace.MIDDLE;

        // Dynamic telemetry updated every evaluation tick
        public final Vector2f centroid = new Vector2f();
        public final Vector2f velocity = new Vector2f();
        public float spreadRadius;
        public float desiredCohesionRadius;
        public float cohesionRatio; // spreadRadius / desiredCohesionRadius
        public float avgFluxLevel;
        public float maxMemberFluxLevel;
        public float avgHullLevel;
        public float localFriendlyStrength;
        public float localEnemyStrength;
        public float localOvermatchRatio; // localFriendlyStrength / max(1, localEnemyStrength)
        public boolean hasShipNeedingHelp;
        public ShipAPI shipNeedingHelp;
        public int usableStrikeAmmoCount;

        // Current C2 orders and intent
        public DivisionIntentType currentIntent = DivisionIntentType.HOLD_AND_COHERE;
        public DivisionState targetEnemyDivision;
        public DivisionState targetFriendlyDivision;
        public BattleObjectiveAPI targetObjective;
        public final Vector2f maneuverAnchor = new Vector2f();

        public AssignmentTargetAPI activeWaypoint;
        public AssignmentInfo activeAssignment;
        public float lastIntentChangeTime = -100f;
        public float lastLogAnnouncementTime = -100f;
        public String lastLoggedSignature = "";
        public float strikeCommittedTimestamp = -100f;
        public float lastStrikeWithdrawTimestamp = -100f;
        public boolean isDesignatedReserve = false;
        public boolean isDesignatedMainEffort = false;

        // Battlegroup orchestration & intent commitment
        public final BgTasking bgTask = new BgTasking();
        public int appliedBgTaskVersion = -1;
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
                if (s != null && s.isAlive() && !s.isHulk() && !s.isExpired() && !s.isRetreating()) {
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
     * Runtime state of a side's Battlegroup Commander and all of its divisions.
     */
    public static class BattlegroupState {
        public final int side;
        public boolean isAlliedAi = false;
        public CommanderProfile bgCommander;
        public MissionContextType missionContext = MissionContextType.FULL_PITCHED_BATTLE;
        public BattlePhase currentPhase = BattlePhase.OPENING_PROBE;
        public BattlePhase previousPhase = null;

        public final List<DivisionState> divisions = new ArrayList<>();
        public final Map<String, DivisionState> shipIdToDivision = new HashMap<>();

        public final Vector2f fleetCentroid = new Vector2f();
        public final Vector2f reserveAnchor = new Vector2f();
        public float totalFriendlyStrength;
        public float totalEnemyStrength;
        public float forceRatio = 1.0f;

        public DivisionState mainEffortDivision;
        public DivisionState reserveDivision;
        public DivisionState primaryThreatEnemyDivision;

        public float lastBgEvaluationTime = -100f;
        public float lastBgOrderLogTime = -100f;
        public String lastBgOrderSignature = "";

        // Battlegroup orchestration (configured in Fleet Doctrine for the player; defaults for AI battlegroups)
        public PermanentDivisionManager.BattlegroupAuthority authority =
                PermanentDivisionManager.BattlegroupAuthority.HYBRID;
        public final Set<PermanentDivisionManager.OrchestrationPlay> plays =
                EnumSet.allOf(PermanentDivisionManager.OrchestrationPlay.class);
        public float phaseEnteredTime = 0f;
        public DivisionState crisisDivision;
        public DivisionState reliefDivision;
        public boolean reserveCommitted = false;
        public StrikePlan strikePlan;
        public float lastStrikePlanEndTime = -100f;
        public final Map<String, Float> orderLogTimes = new HashMap<>();

        public boolean isPlayEnabled(PermanentDivisionManager.OrchestrationPlay play) {
            return plays.contains(play);
        }

        public BattlegroupState(int side, CommanderProfile bgCommander) {
            this.side = side;
            this.bgCommander = bgCommander;
        }
    }
}
