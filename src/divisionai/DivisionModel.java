package divisionai;

import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core data structures for Battlegroup Commander and Division Commander AI.
 */
public class DivisionModel {

    /**
     * How a division was formed and what kind of ship composition it represents.
     */
    public enum DivisionArchetype {
        BATTLE_DIVISION("Battle Div", true),
        CARRIER_AND_ESCORT("Carrier & Escort", true),
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
        HARASS_TORPEDO_ATTACK("launch harassing torpedo attack at"),
        COORDINATED_TORPEDO_STRIKE("execute time-on-target strike against"),
        PRESS_OVERMATCH("press local overmatch against"),
        SCREEN_FRIENDLY_DIVISION("screen and escort"),
        RELIEVE_FRIENDLY_DIVISION("move to relieve"),
        FLANK_ENEMY_DIVISION("swing wide to flank"),
        STRIKE_AND_WITHDRAW("complete strike window and withdraw to reserve from"),
        ROTATE_TO_RESERVE("rotate high-flux hulls back to reserve behind"),
        HOLD_IN_RESERVE("hold in reserve awaiting commitment"),
        SEIZE_OBJECTIVE("secure tactical objective");

        public final String verbPhrase;

        DivisionIntentType(String verbPhrase) {
            this.verbPhrase = verbPhrase;
        }
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
                    break;
                case Personalities.AGGRESSIVE:
                    this.aggressionBias = 1.28f;
                    this.cohesionStrictness = 0.95f + skillBonus * 0.5f;
                    this.rotationFluxThreshold = 0.72f;
                    this.overmatchCommitRatio = 0.88f;
                    this.decisionCadenceSeconds = Math.max(2.0f, 3.0f - skillBonus * 2f);
                    break;
                case Personalities.CAUTIOUS:
                    this.aggressionBias = 0.78f;
                    this.cohesionStrictness = 1.25f + skillBonus * 0.5f;
                    this.rotationFluxThreshold = 0.56f;
                    this.overmatchCommitRatio = 1.22f;
                    this.decisionCadenceSeconds = Math.max(2.2f, 3.2f - skillBonus * 2f);
                    break;
                case Personalities.TIMID:
                    this.aggressionBias = 0.55f;
                    this.cohesionStrictness = 1.38f + skillBonus * 0.5f;
                    this.rotationFluxThreshold = 0.48f;
                    this.overmatchCommitRatio = 1.45f;
                    this.decisionCadenceSeconds = Math.max(2.5f, 3.5f - skillBonus * 2f);
                    break;
                case Personalities.STEADY:
                default:
                    this.aggressionBias = 1.00f;
                    this.cohesionStrictness = 1.10f + skillBonus * 0.5f;
                    this.rotationFluxThreshold = 0.65f;
                    this.overmatchCommitRatio = 1.02f;
                    this.decisionCadenceSeconds = Math.max(2.0f, 3.0f - skillBonus * 2f);
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
        public boolean isDesignatedReserve = false;
        public boolean isDesignatedMainEffort = false;

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

        public BattlegroupState(int side, CommanderProfile bgCommander) {
            this.side = side;
            this.bgCommander = bgCommander;
        }
    }
}
