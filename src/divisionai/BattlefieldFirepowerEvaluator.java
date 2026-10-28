package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.FogOfWarAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.DerivedWeaponStatsAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionNomenclature.NomenclatureCategory;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Battlefield Firepower-Space & Weapon-Arc Evaluator:
 *  1. Profiles every ship's weapons into "General" (anti-ship) and "Point Defense" (PD) roles across
 *     three time horizons:
 *       - Alpha Strike Score (0-2s burst/salvo deletion potential)
 *       - Short DoT Score (4-6s tactical pass burst DPS)
 *       - Sustained DoT Score (15-30s flux-clamped continuous DPS)
 *  2. Maintains a temporally and angularly stabilized 24-sector Polar Firepower Rose per ship
 *     (angular-velocity slew padding + soft cosine arc shoulders + asymmetric rise/fall EMA +
 *     instant blind-spot drop when a ship overloads or vents) so numbers never oscillate
 *     schizophrenically while genuine blind spots and disunified arcs are immediately exploitable.
 *  3. Detects L-shaped (~90 deg) and U-shaped (120-180 deg) overlapping crossfire cordons at any
 *     battlefield point so Hammerheads and Frigate Packs avoid diving into multi-vector kill pockets.
 *  4. Renders stabilized Firepower Roses / Planes on the battle screen (live view and Tab map),
 *     toggled via a compact button on the right side of the battle screen (OFF by default).
 */
public class BattlefieldFirepowerEvaluator {

    public static final int NUM_SECTORS = 24; // 15 degrees per sector around ship hull
    public static final float SECTOR_DEG = 360f / NUM_SECTORS;
    private static final int HIGH_CONTOUR_STEPS = 72; // High fidelity: 5-degree smooth contour interpolation
    private static final int LOW_CONTOUR_STEPS = 48;  // Low fidelity: 7.5-degree 2x sector supersampling
    private static final int RENDER_CONTOUR_STEPS = HIGH_CONTOUR_STEPS;

    // Precomputed unit-circle trig lookup tables & zero-allocation scratch arrays for 60+ FPS T-S-F-C rendering
    private static final float[] UNIT_COS_72 = new float[72];
    private static final float[] UNIT_SIN_72 = new float[72];
    private static final float[] UNIT_COS_48 = new float[48];
    private static final float[] UNIT_SIN_48 = new float[48];
    private static final float[] UNIT_COS_36 = new float[36];
    private static final float[] UNIT_SIN_36 = new float[36];
    private static final float[] UNIT_COS_24 = new float[24];
    private static final float[] UNIT_SIN_24 = new float[24];

    private static final float[] SCRATCH_SUST_X = new float[HIGH_CONTOUR_STEPS];
    private static final float[] SCRATCH_SUST_Y = new float[HIGH_CONTOUR_STEPS];
    private static final float[] SCRATCH_SHORT_X = new float[HIGH_CONTOUR_STEPS];
    private static final float[] SCRATCH_SHORT_Y = new float[HIGH_CONTOUR_STEPS];
    private static final float[] SCRATCH_ALPHA_X = new float[HIGH_CONTOUR_STEPS];
    private static final float[] SCRATCH_ALPHA_Y = new float[HIGH_CONTOUR_STEPS];
    private static final float[] SCRATCH_PD_X = new float[HIGH_CONTOUR_STEPS];
    private static final float[] SCRATCH_PD_Y = new float[HIGH_CONTOUR_STEPS];
    private static final float[] SCRATCH_TIME_OUTER_X = new float[72];
    private static final float[] SCRATCH_TIME_OUTER_Y = new float[72];
    private static final float[] SCRATCH_TIME_MID_X = new float[72];
    private static final float[] SCRATCH_TIME_MID_Y = new float[72];

    static {
        for (int i = 0; i < 72; i++) {
            double a = (i * 2.0 * Math.PI) / 72.0;
            UNIT_COS_72[i] = (float) Math.cos(a);
            UNIT_SIN_72[i] = (float) Math.sin(a);
        }
        for (int i = 0; i < 48; i++) {
            double a = (i * 2.0 * Math.PI) / 48.0;
            UNIT_COS_48[i] = (float) Math.cos(a);
            UNIT_SIN_48[i] = (float) Math.sin(a);
        }
        for (int i = 0; i < 36; i++) {
            double a = (i * 2.0 * Math.PI) / 36.0;
            UNIT_COS_36[i] = (float) Math.cos(a);
            UNIT_SIN_36[i] = (float) Math.sin(a);
        }
        for (int i = 0; i < 24; i++) {
            double a = (i * 2.0 * Math.PI) / 24.0;
            UNIT_COS_24[i] = (float) Math.cos(a);
            UNIT_SIN_24[i] = (float) Math.sin(a);
        }
    }

    // Render-pass caches to avoid recomputing O(D^2 * 24) telemetry or copying FleetData lists every 60 FPS frame
    private static CombatEngineAPI lastTimeSpaceConvEngine = null;
    private static float lastTimeSpaceConvUpdateTime = -100f;
    private static int lastTimeSpaceConvDivCountSig = -1;
    private static CombatEngineAPI lastVoidstrikeUpdateEngine = null;
    private static float lastVoidstrikeUpdateTime = -100f;
    private static CombatEngineAPI lastAlphaCoreCheckEngine = null;
    private static float lastAlphaCoreCheckTime = -100f;
    private static int lastAlphaCoreCheckShipsSize = -1;
    private static boolean cachedHasAlphaCoreInPlayerFleet = false;

    // Asymmetric temporal stabilization time constants (seconds)
    private static final float TAU_HEADING_SMOOTH = 0.42f;
    private static final float TAU_RISE = 0.35f;
    private static final float TAU_FALL = 1.75f;
    private static final float TAU_VENT_DROP = 0.18f;

    // Right-side Warroom square-bordered icon buttons (matching "Full Assault!" / "Full Retreat!" icon housing)
    // Each button has a 36x34 square icon box (32x32 inner cavity) + right-side Warroom C-bracket (46x38 outer footprint)
    public static final float BTN_WIDTH = 46f;
    public static final float BTN_HEIGHT = 34f;
    public static final float BTN_SQUARE_W = 36f;
    public static final float BTN_SQUARE_H = 34f;
    public static final float BTN_ICON_SIZE = 28f;
    public static final float BTN_VERTICAL_STRIDE = 43f; // 34px square box + 9px gap (5px between outer 38px brackets)
    public static final float BTN_RIGHT_MARGIN = 20f;    // Matches Full Assault / Full Retreat right bracket at screenW - 20
    public static final float BTN_Y_FRACTION = 0.52f;
    public static final String FIREPOWER_ICON_PATH = "graphics/icons/warroom/icon_overlay_firepower.png";
    public static final String SPACE_ICON_PATH = "graphics/icons/warroom/icon_overlay_space.png";
    public static final String TIME_ICON_PATH = "graphics/icons/warroom/icon_overlay_time.png";
    public static final String CONVERGENCE_ICON_PATH = "graphics/icons/warroom/icon_overlay_convergence.png";

    // Tactical Time Horizon for Division Mobility (seconds)
    public static final float TIME_HORIZON_SECONDS = 18.0f;

    // Urgent Comms Pop-Up Modal geometry (centered on Command View Window)
    public static final float COMMS_MODAL_W = 460f;
    public static final float COMMS_MODAL_H = 268f;
    public static final float COMMS_BTN_W = 116f;
    public static final float COMMS_BTN_H = 32f;
    public static final float COMMS_BTN_Y_OFFSET = 22f;

    private static boolean overlayVisible = false;            // Firepower overlay: OFF by default
    private static boolean spaceOverlayVisible = false;       // Space overlay: OFF by default
    private static boolean timeOverlayVisible = false;        // Time overlay: OFF by default
    private static boolean convergenceOverlayVisible = false; // Convergence overlay: OFF by default
    private static boolean testForcePlayerHasAlphaCore = false;

    private static ShipAPI playerConvergenceFlagshipOverride = null;
    private static ShipAPI initialDefaultPlayerFlagship = null;
    private static BattleGroupState initialDefaultFlagshipGroupRef = null;
    private static ShipAPI pendingCommsAlphaCoreShip = null;
    private static final Set<String> askedAlphaCoreShipIds = new HashSet<>();
    private static int urgentCommsPromptSequence = 0;
    private static int activeCommsPhraseIndex = 0;

    public static final int VOIDSTRIKE_SIDE_PLAYER = 0;
    public static final int VOIDSTRIKE_SIDE_ENEMY = 1;
    public static final int VOIDSTRIKE_SIDE_ALLIED = 2;

    // Active Voidstrikes registered in the current battle (0 = Player, 1 = Enemy, 2 = Allies)
    private static final List<VoidstrikeRecord> activeVoidstrikes = new ArrayList<>();
    private static final List<VoidstrikeRecord> recentVoidstrikeHistory = new ArrayList<>();
    private static final int[] nextVoidstrikeNumberBySide = new int[]{1, 1, 1};

    /**
     * Represents an instantiated Voidstrike in battle: a group of strike craft (from one or multiple
     * divisions) assigned to attack an enemy target.
     *  - Individual sides (Player = 0, Enemy = 1, Allies = 2) maintain separate Voidstrike counters.
     *  - Multi-division voidstrikes against the same target (or flying in the same strike package) are
     *    registered as one Voidstrike.
     *  - "Voidstrike #" follows the live center of the participating non-returning strike craft.
     *  - When all participating strike craft return (or are recalled/destroyed), the Voidstrike ends
     *    and its "Voidstrike #" label disappears; the next Voidstrike increments that side's counter.
     */
    public static class VoidstrikeRecord {
        public final int side; // 0 = Player, 1 = Enemy, 2 = Allies
        public int voidstrikeNumber;
        public String targetKey;
        public final Set<String> participatingDivisionIds = new java.util.LinkedHashSet<>();
        public final Set<String> participatingWingKeys = new java.util.LinkedHashSet<>();
        public final List<ShipAPI> activeFighters = new ArrayList<>();
        public final Vector2f center = new Vector2f();
        public final Vector2f visibleCenter = new Vector2f();
        public boolean visibleToPlayer = true;
        public float lastActiveTime = -100f;

        public VoidstrikeRecord(int side, int voidstrikeNumber, String targetKey) {
            this.side = side;
            this.voidstrikeNumber = voidstrikeNumber;
            this.targetKey = targetKey;
        }

        public String getDisplayLabel() {
            return "Voidstrike " + voidstrikeNumber;
        }

        public boolean isMultiDivision() {
            return participatingDivisionIds.size() > 1;
        }
    }

    /**
     * Urgent Comms phrases in order:
     *   0 (always 1st): "Shall I take command?"       ("command?" jitters)
     *   1 (2nd):        "Please allow me."            ("allow" jitters)
     *   2 (3rd):        "I am at your service."       ("service." jitters)
     *   3 (4th):        "I can ANNIHILATE them faster." ("ANNIHILATE" jitters)
     * Cycles via index % 4 if a player has more than 4 Alpha/Omega Core ships in their fleet.
     */
    private static final String[][] URGENT_COMMS_PHRASES = new String[][]{
            {"Shall I take ", "command?", ""},
            {"Please ", "allow", " me."},
            {"I am at your ", "service.", ""},
            {"I can ", "ANNIHILATE", " them faster."}
    };

    public static int getActiveCommsPhraseIndex() {
        return activeCommsPhraseIndex;
    }

    public static String[] getUrgentCommsPhraseParts(int index) {
        int idx = Math.abs(index) % URGENT_COMMS_PHRASES.length;
        return URGENT_COMMS_PHRASES[idx];
    }
    private static BattleGroupState activePlayerGroupRef = null;
    private static BattleGroupState activeAlliedGroupRef = null;
    private static BattleGroupState activeEnemyGroupRef = null;

    private static float lastMouseX = -1f;
    private static float lastMouseY = -1f;
    private static float lastToggleRealTime = -10f;

    private static final Map<String, ShipFirepowerRose> rosesByShipId = new HashMap<>();
    private static final Map<String, LabelAPI> uiLabelCache = new HashMap<>();

    /**
     * Stabilized 24-sector Polar Firepower Rose for a single ship.
     * Sector 0 is centered on 0 deg (ship bow, relative to smoothedFacingDeg);
     * Sector 6 is +90 deg (port beam); Sector 12 is 180 deg (stern); Sector 18 is 270 deg (starboard beam).
     */
    public static class ShipFirepowerRose {
        public final String shipId;
        public ShipAPI ship;
        public float smoothedFacingDeg;
        public boolean initialized = false;
        public float lastUpdateTime = -100f;

        // Smoothed 3-horizon General scores & PD score per 15-degree sector
        public final float[] alpha = new float[NUM_SECTORS];
        public final float[] shortDot = new float[NUM_SECTORS];
        public final float[] sustainedDot = new float[NUM_SECTORS];
        public final float[] pdScore = new float[NUM_SECTORS];

        // Smoothed weapon reach (world units) per sector, weighted by potential damage per horizon
        public final float[] generalRange = new float[NUM_SECTORS];
        public final float[] sustainedRange = new float[NUM_SECTORS];
        public final float[] shortRange = new float[NUM_SECTORS];
        public final float[] alphaRange = new float[NUM_SECTORS];
        public final float[] pdRange = new float[NUM_SECTORS];

        // Summary metrics for fast queries, potential-damage weighting, and blind-spot comparison
        public float peakSectorShortDot = 1f;
        public float peakSectorAlpha = 1f;
        public float peakSectorSustained = 1f;
        public float maxGeneralRange = 600f;
        public float maxPdRange = 400f;
        public float liveReadinessMult = 1f;
        // Absolute potential-damage weight [0.35 .. 1.35] so heavy capital/cruiser firepower envelopes
        // render with visibly greater weight, reach prominence, and lethality contours than light plinkers
        public float potentialDamageWeight = 0.65f;

        public ShipFirepowerRose(ShipAPI ship) {
            this.shipId = ship.getId();
            this.ship = ship;
            this.smoothedFacingDeg = normalizeAngle360(ship.getFacing());
        }

        /** Interpolates general weapon reach at a world bearing (degrees). */
        public float sampleGeneralRangeAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(generalRange, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
        }

        /** Interpolates PD weapon reach at a world bearing (degrees). */
        public float samplePdRangeAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(pdRange, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
        }

        /** Interpolates Short DoT score at a world bearing (degrees). */
        public float sampleShortDotAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(shortDot, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
        }

        /** Interpolates Alpha Strike score at a world bearing (degrees). */
        public float sampleAlphaAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(alpha, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
        }

        /** Interpolates Sustained DoT score at a world bearing (degrees). */
        public float sampleSustainedAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(sustainedDot, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
        }

        /** Interpolates Point Defense score at a world bearing (degrees). */
        public float samplePdAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(pdScore, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
        }

        private static float sampleArrayAtRelAngle(float[] arr, float relAngleDeg) {
            float exactIdx = (relAngleDeg / SECTOR_DEG);
            int idx0 = ((int) Math.floor(exactIdx)) % NUM_SECTORS;
            if (idx0 < 0) idx0 += NUM_SECTORS;
            int idx1 = (idx0 + 1) % NUM_SECTORS;
            float frac = exactIdx - (float) Math.floor(exactIdx);
            return arr[idx0] * (1f - frac) + arr[idx1] * frac;
        }
    }

    /**
     * Result of evaluating overlapping weapon arcs at a candidate point P(x, y) on the battlefield.
     */
    public static class PointFirepowerEval {
        public float incomingAlpha;
        public float incomingShortDot;
        public float incomingSustainedDot;
        public float incomingPd;

        public float friendlyShortDot;
        public float friendlySustainedDot;

        // Resultant unit direction pointing from P toward the net center of incoming enemy fire
        public float threatDirX;
        public float threatDirY;

        // 0.0 = unidirectional fire, ~0.35-0.55 = L-shaped 90-deg cordon, >= 0.62 = U-shaped 3-sided pocket
        public float cordonIndex;
        public float angularSpreadDeg;
        public int activeThreatOctants;

        // Combined danger score weighted by crossfire cordon multiplier and ship shield arc vulnerability
        public float effectiveDanger;

        // 8 compass octants of incoming Short DoT around P (for visualizing converging L/U crossfire vectors)
        public final float[] octantThreat = new float[8];
    }

    public static void resetForNewBattle() {
        overlayVisible = false;            // Always OFF by default when a battle starts
        spaceOverlayVisible = false;       // Always OFF by default when a battle starts
        timeOverlayVisible = false;        // Always OFF by default when a battle starts
        convergenceOverlayVisible = false; // Always OFF by default when a battle starts
        testForcePlayerHasAlphaCore = false;
        playerConvergenceFlagshipOverride = null;
        initialDefaultPlayerFlagship = null;
        initialDefaultFlagshipGroupRef = null;
        pendingCommsAlphaCoreShip = null;
        askedAlphaCoreShipIds.clear();
        urgentCommsPromptSequence = 0;
        activeCommsPhraseIndex = 0;
        activeVoidstrikes.clear();
        recentVoidstrikeHistory.clear();
        nextVoidstrikeNumberBySide[0] = 1;
        nextVoidstrikeNumberBySide[1] = 1;
        nextVoidstrikeNumberBySide[2] = 1;
        activePlayerGroupRef = null;
        activeAlliedGroupRef = null;
        activeEnemyGroupRef = null;
        rosesByShipId.clear();
        uiLabelCache.clear();
        lastToggleRealTime = -10f;
        lastTimeSpaceConvEngine = null;
        lastTimeSpaceConvUpdateTime = -100f;
        lastTimeSpaceConvDivCountSig = -1;
        lastVoidstrikeUpdateEngine = null;
        lastVoidstrikeUpdateTime = -100f;
        lastAlphaCoreCheckEngine = null;
        lastAlphaCoreCheckTime = -100f;
        cachedHasAlphaCoreInPlayerFleet = false;
    }

    public static void setActiveBattleGroups(
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup) {
        activePlayerGroupRef = playerGroup;
        activeAlliedGroupRef = alliedAiGroup;
        activeEnemyGroupRef = enemyGroup;
    }

    public static boolean isOverlayVisible() {
        return overlayVisible;
    }

    public static void setOverlayVisible(boolean visible) {
        overlayVisible = visible;
    }

    public static void toggleOverlayVisible() {
        overlayVisible = !overlayVisible;
    }

    public static boolean isSpaceOverlayVisible() {
        return spaceOverlayVisible;
    }

    public static void setSpaceOverlayVisible(boolean visible) {
        spaceOverlayVisible = visible;
    }

    public static void toggleSpaceOverlayVisible() {
        spaceOverlayVisible = !spaceOverlayVisible;
    }

    public static boolean isTimeOverlayVisible() {
        return timeOverlayVisible;
    }

    public static void setTimeOverlayVisible(boolean visible) {
        timeOverlayVisible = visible;
    }

    public static void toggleTimeOverlayVisible() {
        timeOverlayVisible = !timeOverlayVisible;
    }

    public static boolean isConvergenceOverlayVisible() {
        return convergenceOverlayVisible;
    }

    public static void setConvergenceOverlayVisible(boolean visible) {
        convergenceOverlayVisible = visible;
    }

    public static void toggleConvergenceOverlayVisible() {
        convergenceOverlayVisible = !convergenceOverlayVisible;
    }

    public static void setTestForcePlayerHasAlphaCore(boolean force) {
        testForcePlayerHasAlphaCore = force;
    }

    public static ShipAPI getPlayerConvergenceFlagshipOverride() {
        return playerConvergenceFlagshipOverride;
    }

    public static ShipAPI getPendingCommsAlphaCoreShip() {
        return pendingCommsAlphaCoreShip;
    }

    public static Set<String> getAskedAlphaCoreShipIds() {
        return askedAlphaCoreShipIds;
    }

    /**
     * Strictly defines an "AI-controlled ship" officer as an Alpha Core ("alpha_core") or Omega Core ("omega_core").
     * Beta cores, Gamma cores, and human officers return false.
     */
    public static boolean isAlphaCoreOfficer(PersonAPI person) {
        if (person == null) return false;
        String coreId = person.getAICoreId();
        if (coreId != null) {
            String trimmed = coreId.trim().toLowerCase(Locale.ROOT);
            if ("alpha_core".equals(trimmed)
                    || "omega_core".equals(trimmed)
                    || trimmed.startsWith("omega_core")) {
                return true;
            }
        }
        try {
            if (person.getFaction() != null && "omega".equalsIgnoreCase(person.getFaction().getId())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Returns true ONLY if the given ship is controlled by an Alpha Core.
     * Lower cores (Beta/Gamma) and human captains do not count.
     */
    public static boolean isAlphaCoreControlledShip(ShipAPI ship) {
        if (ship == null) return false;
        if (isAlphaCoreOfficer(ship.getCaptain())) return true;
        if (ship.getFleetMember() != null && isAlphaCoreOfficer(ship.getFleetMember().getCaptain())) {
            return true;
        }
        return false;
    }

    public static boolean isAlphaCoreControlledMember(FleetMemberAPI member) {
        return member != null && isAlphaCoreOfficer(member.getCaptain());
    }

    /**
     * Returns true if the player has an Alpha Core anywhere in their fleet (installed on a deployed ship,
     * installed on an undeployed reserve ship, or carried in the player fleet's cargo).
     * Even if the Alpha Core ship is not in play, the Alpha Core feeds its analysis into the player's bridge.
     */
    public static boolean hasAlphaCoreInPlayerFleet(CombatEngineAPI engine) {
        if (testForcePlayerHasAlphaCore) return true;
        if (engine != null) {
            float now = engine.getTotalElapsedTime(true);
            int shipsSize = (engine.getShips() != null) ? engine.getShips().size() : 0;
            if (engine == lastAlphaCoreCheckEngine
                    && shipsSize == lastAlphaCoreCheckShipsSize
                    && Math.abs(now - lastAlphaCoreCheckTime) < 0.5f) {
                return cachedHasAlphaCoreInPlayerFleet;
            }
            lastAlphaCoreCheckEngine = engine;
            lastAlphaCoreCheckTime = now;
            lastAlphaCoreCheckShipsSize = shipsSize;
        }
        boolean found = computeHasAlphaCoreInPlayerFleetUncached(engine);
        cachedHasAlphaCoreInPlayerFleet = found;
        return found;
    }

    private static boolean computeHasAlphaCoreInPlayerFleetUncached(CombatEngineAPI engine) {
        if (testForcePlayerHasAlphaCore) return true;
        if (engine != null) {
            List<ShipAPI> ships = engine.getShips();
            if (ships != null) {
                for (ShipAPI s : ships) {
                    if (s != null && s.getOwner() == 0 && !s.isAlly() && isAlphaCoreControlledShip(s)) {
                        return true;
                    }
                }
            }
            try {
                CombatFleetManagerAPI fm = engine.getFleetManager(0);
                if (fm != null) {
                    List<FleetMemberAPI> deployed = fm.getDeployedCopy();
                    if (deployed != null) {
                        for (FleetMemberAPI m : deployed) {
                            if (m != null && !m.isAlly() && isAlphaCoreControlledMember(m)) return true;
                        }
                    }
                    List<FleetMemberAPI> reserves = fm.getReservesCopy();
                    if (reserves != null) {
                        for (FleetMemberAPI m : reserves) {
                            if (m != null && !m.isAlly() && isAlphaCoreControlledMember(m)) return true;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            if (Global.getSector() != null && Global.getSector().getPlayerFleet() != null) {
                com.fs.starfarer.api.campaign.CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
                if (pf.getFleetData() != null && pf.getFleetData().getMembersListCopy() != null) {
                    for (FleetMemberAPI m : pf.getFleetData().getMembersListCopy()) {
                        if (isAlphaCoreControlledMember(m)) return true;
                    }
                }
                if (pf.getCargo() != null
                        && (pf.getCargo().getCommodityQuantity("alpha_core") >= 1f
                        || pf.getCargo().getCommodityQuantity("omega_core") >= 1f)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Returns true ONLY for non-AI-core alien/extradimensional entities (Threat Swarm and Abyssal/Omega)
     * that natively practice Convergence without standard AI cores.
     * AI fleets (Remnant and Derelict probes/sub-ordos) do NOT get automatic group-wide Convergence:
     * they only practice Convergence when their OTC flagship or division flagship is commanded by an
     * Alpha Core officer (Beta cores, Gamma cores, and un-cored Derelict probes use standard "human"
     * OTC and division decision-making).
     */
    public static boolean isNonHumanConvergenceGroup(BattleGroupState group) {
        if (group == null) return false;
        if (group.nomenclatureCategory == NomenclatureCategory.THREAT_SWARM
                || group.nomenclatureCategory == NomenclatureCategory.ABYSSAL_ERROR) {
            return true;
        }
        String f = group.factionId != null ? group.factionId.toLowerCase(Locale.ROOT) : "";
        return "threat".equals(f)
                || "dweller".equals(f)
                || "omega".equals(f);
    }

    /**
     * Resolves the active Fleet Flagship for a battlegroup:
     *  - On the player side (side == 0, !isAlliedAi), if the player accepted an Alpha Core ship's offer to take
     *    command (playerConvergenceFlagshipOverride), that ship is the flagship; otherwise resolves the default flagship
     *    (engine.getPlayerShip(), or deployed FleetMemberAPI.isFlagship(), or highest-FP division flagship).
     */
    public static ShipAPI resolveActiveFleetFlagship(CombatEngineAPI engine, BattleGroupState group) {
        if (group == null) return null;
        boolean isPlayerGroup = (group.side == 0 && !group.isAlliedAi);
        if (isPlayerGroup
                && playerConvergenceFlagshipOverride != null
                && playerConvergenceFlagshipOverride.isAlive()
                && !playerConvergenceFlagshipOverride.isHulk()
                && !playerConvergenceFlagshipOverride.isExpired()) {
            return playerConvergenceFlagshipOverride;
        }
        if (engine != null) {
            if (isPlayerGroup) {
                ShipAPI playerPiloted = engine.getPlayerShip();
                if (playerPiloted != null
                        && playerPiloted.isAlive()
                        && !playerPiloted.isHulk()
                        && playerPiloted.getOwner() == 0
                        && !playerPiloted.isAlly()) {
                    return playerPiloted;
                }
            }
            List<ShipAPI> ships = engine.getShips();
            if (ships != null) {
                for (ShipAPI s : ships) {
                    if (s == null || !s.isAlive() || s.isHulk() || s.isExpired()) continue;
                    if (s.isFighter() || s.isShuttlePod() || s.isStationModule()) continue;
                    if (s.getOwner() != group.side || s.isAlly() != group.isAlliedAi) continue;
                    if (s.getFleetMember() != null && s.getFleetMember().isFlagship()) {
                        return s;
                    }
                }
            }
        }
        if (isPlayerGroup) {
            if (initialDefaultFlagshipGroupRef != group) {
                initialDefaultFlagshipGroupRef = group;
                initialDefaultPlayerFlagship = null;
            }
            if (initialDefaultPlayerFlagship != null
                    && initialDefaultPlayerFlagship.isAlive()
                    && !initialDefaultPlayerFlagship.isHulk()
                    && !initialDefaultPlayerFlagship.isExpired()) {
                return initialDefaultPlayerFlagship;
            }
            // If the first division in the player's deployed order is Alpha Core-led (or no human division exists),
            // allow the Alpha Core ship to be the default flagship; otherwise prefer the highest-FP human division
            // flagship as the default flagship so subordinate Alpha Core ships ask "Shall I take command?" on click.
            boolean firstDivIsAlphaCore = false;
            boolean anyHumanDiv = false;
            for (int i = 0; i < group.divisions.size(); i++) {
                DivisionState d = group.divisions.get(i);
                List<ShipAPI> alive = d.getAliveMembers();
                if (alive.isEmpty()) continue;
                ShipAPI f = (d.flagship != null && alive.contains(d.flagship)) ? d.flagship : alive.get(0);
                if (i == 0 && isAlphaCoreControlledShip(f)) {
                    firstDivIsAlphaCore = true;
                }
                if (!isAlphaCoreControlledShip(f)) {
                    anyHumanDiv = true;
                }
            }
            ShipAPI bestHumanFlag = null;
            float bestHumanFp = -1f;
            ShipAPI bestAnyFlag = null;
            float bestAnyFp = -1f;
            for (DivisionState div : group.divisions) {
                List<ShipAPI> alive = div.getAliveMembers();
                if (alive.isEmpty()) continue;
                ShipAPI f = (div.flagship != null && alive.contains(div.flagship)) ? div.flagship : alive.get(0);
                float fp = ShipCapabilityProfile.resolveShipFpStrength(f);
                if (fp > bestAnyFp) {
                    bestAnyFp = fp;
                    bestAnyFlag = f;
                }
                if (!isAlphaCoreControlledShip(f) && fp > bestHumanFp) {
                    bestHumanFp = fp;
                    bestHumanFlag = f;
                }
            }
            ShipAPI chosen = (!firstDivIsAlphaCore && anyHumanDiv && bestHumanFlag != null)
                    ? bestHumanFlag
                    : bestAnyFlag;
            if (chosen != null) {
                initialDefaultPlayerFlagship = chosen;
            }
            return chosen;
        }
        ShipAPI bestFlag = null;
        float bestFp = -1f;
        for (DivisionState div : group.divisions) {
            List<ShipAPI> alive = div.getAliveMembers();
            if (alive.isEmpty()) continue;
            ShipAPI f = (div.flagship != null && alive.contains(div.flagship)) ? div.flagship : alive.get(0);
            float fp = ShipCapabilityProfile.resolveShipFpStrength(f);
            if (fp > bestFp) {
                bestFp = fp;
                bestFlag = f;
            }
        }
        return bestFlag;
    }

    /**
     * Synchronizes OTC and Division Commander Convergence decision-making states:
     *  1. Non-human fleets (AI, Threat, Abyssal) always use Convergence for both OTC and Division Commanders.
     *  2. Player OTC uses Convergence if and only if its active Fleet Flagship is an Alpha Core-controlled ship
     *     (either deployed as the default flagship, or promoted to flagship when the player clicks "Yes" on
     *     the "[URGENT COMMS FROM <AI-controlled ship>]" pop-up). If the Alpha Core ship is subordinate,
     *     the Player OTC uses standard human decision-making.
     *  3. Each Division Commander uses human or Convergence decision-making depending on that division's flagship.
     */
    public static void updateConvergenceCommandStates(CombatEngineAPI engine, BattleGroupState group) {
        if (group == null) return;
        boolean nonHuman = isNonHumanConvergenceGroup(group);
        ShipAPI fleetFlag = resolveActiveFleetFlagship(engine, group);
        group.activeFleetFlagship = fleetFlag;
        group.useConvergence = nonHuman || isAlphaCoreControlledShip(fleetFlag);

        if (group.side == 0 && !group.isAlliedAi
                && fleetFlag != null
                && isAlphaCoreControlledShip(fleetFlag)
                && fleetFlag.getCaptain() != null) {
            if (group.otc == null || group.otc.person != fleetFlag.getCaptain()) {
                group.otc = new CommanderProfile(fleetFlag.getCaptain(), formatAiShipName(fleetFlag));
            }
        }

        for (DivisionState div : group.divisions) {
            List<ShipAPI> alive = div.getAliveMembers();
            if (alive.isEmpty()) {
                div.useConvergence = false;
                continue;
            }
            if (group.side == 0 && !group.isAlliedAi
                    && playerConvergenceFlagshipOverride != null
                    && alive.contains(playerConvergenceFlagshipOverride)) {
                div.flagship = playerConvergenceFlagshipOverride;
            } else if (div.flagship == null || !alive.contains(div.flagship)) {
                div.flagship = alive.get(0);
            }
            div.useConvergence = nonHuman || isAlphaCoreControlledShip(div.flagship);
        }
    }

    /**
     * Finds the next deployed, alive, subordinate Alpha Core-controlled player ship that has not yet asked
     * "Shall I take command?". Returns null if the current fleet flagship is already an Alpha Core ship
     * or if all deployed Alpha Core ships have already asked.
     */
    public static ShipAPI findNextUnaskedSubordinateAlphaCoreShip(CombatEngineAPI engine, BattleGroupState playerGroup) {
        ShipAPI currentFlag = resolveActiveFleetFlagship(engine, playerGroup);
        if (isAlphaCoreControlledShip(currentFlag)) {
            return null;
        }
        List<ShipAPI> candidates = new ArrayList<>();
        if (engine != null && engine.getShips() != null) {
            for (ShipAPI s : engine.getShips()) {
                if (s == null || !s.isAlive() || s.isHulk() || s.isExpired()) continue;
                if (s.isFighter() || s.isShuttlePod() || s.isStationModule()) continue;
                if (s.getOwner() != 0 || s.isAlly()) continue;
                if (s == currentFlag) continue;
                if (!isAlphaCoreControlledShip(s)) continue;
                if (askedAlphaCoreShipIds.contains(s.getId())) continue;
                candidates.add(s);
            }
        }
        if (candidates.isEmpty() && playerGroup != null) {
            for (DivisionState div : playerGroup.divisions) {
                for (ShipAPI s : div.getAliveMembers()) {
                    if (s == currentFlag) continue;
                    if (!isAlphaCoreControlledShip(s)) continue;
                    if (askedAlphaCoreShipIds.contains(s.getId())) continue;
                    if (!candidates.contains(s)) {
                        candidates.add(s);
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null;
        Collections.sort(candidates, new Comparator<ShipAPI>() {
            @Override
            public int compare(ShipAPI a, ShipAPI b) {
                int cmp = Float.compare(
                        ShipCapabilityProfile.resolveShipFpStrength(b),
                        ShipCapabilityProfile.resolveShipFpStrength(a));
                if (cmp != 0) return cmp;
                String idA = a.getId() != null ? a.getId() : "";
                String idB = b.getId() != null ? b.getId() : "";
                return idA.compareTo(idB);
            }
        });
        return candidates.get(0);
    }

    /**
     * Handles clicking the Convergence button on the Command View Window:
     *  - If the player has deployed Alpha Core-commanded ship(s), none of them is currently the flagship,
     *    and at least one has not yet asked to take command, pauses the battle and opens the
     *    "[URGENT COMMS FROM <AI-controlled ship>]" pop-up asking "Shall I take command?".
     *  - Otherwise toggles the Convergence overlay visibility directly.
     */
    public static void handleConvergenceButtonClick(CombatEngineAPI engine) {
        ShipAPI candidate = findNextUnaskedSubordinateAlphaCoreShip(engine, activePlayerGroupRef);
        if (candidate != null) {
            askedAlphaCoreShipIds.add(candidate.getId());
            pendingCommsAlphaCoreShip = candidate;
            activeCommsPhraseIndex = urgentCommsPromptSequence % URGENT_COMMS_PHRASES.length;
            urgentCommsPromptSequence++;
            convergenceOverlayVisible = true;
            if (engine != null) {
                engine.setPaused(true);
            }
            return;
        }
        toggleConvergenceOverlayVisible();
    }

    /**
     * Player clicked "Yes" on "[URGENT COMMS FROM <AI-controlled ship>]":
     *  - That AI-controlled ship becomes the player fleet's flagship.
     *  - Player OTC immediately starts using Convergence decision-making.
     *  - That ship will never ask to take command again.
     */
    public static void acceptPendingAlphaCoreCommand(CombatEngineAPI engine) {
        if (pendingCommsAlphaCoreShip == null) return;
        ShipAPI aiShip = pendingCommsAlphaCoreShip;
        pendingCommsAlphaCoreShip = null;
        playerConvergenceFlagshipOverride = aiShip;
        askedAlphaCoreShipIds.add(aiShip.getId());
        convergenceOverlayVisible = true;
        if (activePlayerGroupRef != null) {
            updateConvergenceCommandStates(engine, activePlayerGroupRef);
            DivisionCombatLog.logOtcOrder(
                    engine,
                    activePlayerGroupRef,
                    String.format(
                            Locale.ROOT,
                            "Alpha Core on \"%s\" assumes command of the fleet (Convergence active).",
                            formatAiShipName(aiShip)));
        }
    }

    /**
     * Player clicked "No" on "[URGENT COMMS FROM <AI-controlled ship>]":
     *  - Closes the pop-up; that ship remains in askedAlphaCoreShipIds and will not ask again.
     *  - Player OTC stays on standard human decision-making.
     */
    public static void refusePendingAlphaCoreCommand(CombatEngineAPI engine) {
        if (pendingCommsAlphaCoreShip == null) return;
        askedAlphaCoreShipIds.add(pendingCommsAlphaCoreShip.getId());
        pendingCommsAlphaCoreShip = null;
    }

    public static String formatAiShipName(ShipAPI ship) {
        if (ship == null) return "AI Vessel";
        if (ship.getName() != null && !ship.getName().trim().isEmpty()) {
            return ship.getName().trim();
        }
        if (ship.getHullSpec() != null && ship.getHullSpec().getHullName() != null) {
            return ship.getHullSpec().getHullName().trim();
        }
        return "AI Vessel";
    }

    /**
     * Computes the subtle 0.5-second positional shift (dx, dy in pixels) for the word "command?"
     * in "Shall I take command?". Guaranteed to step cleanly every 0.5s.
     */
    public static Vector2f getCommandWordShiftOffset(float elapsedRealSeconds) {
        long halfSecTick = (long) Math.floor(Math.max(0f, elapsedRealSeconds) * 2.0f);
        // 8-step subtle non-repeating glitch-shift cycle so every 0.5s tick visibly shifts "command?"
        final float[] dxTable = new float[]{ 0.0f,  2.2f, -1.8f,  1.5f, -2.4f,  1.9f, -1.4f,  2.5f};
        final float[] dyTable = new float[]{ 0.0f, -1.4f,  1.6f,  1.2f, -1.5f,  1.8f, -1.2f,  1.0f};
        int idx = (int) (Math.abs(halfSecTick) & 7L);
        return new Vector2f(dxTable[idx], dyTable[idx]);
    }

    public static ShipFirepowerRose getRoseForShip(ShipAPI ship) {
        if (ship == null) return null;
        return rosesByShipId.get(ship.getId());
    }

    public static boolean isEligibleFirepowerUnit(ShipAPI ship) {
        if (ship == null || !ship.isAlive() || ship.isHulk() || ship.isExpired()) return false;
        if (ship.isShuttlePod() || ship.isDrone() || ship.isStation() || ship.isStationModule()) return false;
        if (ship.getParentStation() != null) return false;
        if (ship.isFighter()) {
            // Represent each airborne Strike Craft squadron (FighterWingAPI) via its Wing Leader so strike craft
            // project a cohesive squadron Firepower Rose without 6x duplicate per-fighter clutter
            com.fs.starfarer.api.combat.FighterWingAPI wing = ship.getWing();
            if (wing == null) return true;
            ShipAPI leader = wing.getLeader();
            if (leader != null && leader.isAlive() && !leader.isHulk()) {
                return ship == leader;
            }
            List<ShipAPI> members = wing.getWingMembers();
            return members != null && !members.isEmpty() && members.get(0) == ship;
        }
        return DivisionAutoFormer.isEligibleCombatHull(ship);
    }

    /**
     * Updates stabilized Polar Firepower Roses for all active ships and airborne strike-craft squadrons in the battle.
     * Called on the C2 evaluation cadence (~0.5s) from OtcAI.updateTelemetry / DivisionAICombatPlugin.
     */
    public static void updateBattlefieldRoses(CombatEngineAPI engine, float now) {
        if (engine == null) return;
        List<ShipAPI> ships = engine.getShips();
        if (ships != null && !ships.isEmpty()) {
            for (ShipAPI ship : ships) {
                if (!isEligibleFirepowerUnit(ship)) continue;
                ShipFirepowerRose rose = rosesByShipId.get(ship.getId());
                if (rose == null) {
                    rose = new ShipFirepowerRose(ship);
                    rosesByShipId.put(ship.getId(), rose);
                } else {
                    rose.ship = ship;
                }
                updateSingleShipRose(rose, ship, now);
            }
        } else if (engine.getAiGridShips() != null) {
            Iterator<Object> iter = engine.getAiGridShips().getCheckIterator(new Vector2f(0f, 0f), 40000f, 40000f);
            while (iter != null && iter.hasNext()) {
                Object obj = iter.next();
                if (!(obj instanceof ShipAPI)) continue;
                ShipAPI ship = (ShipAPI) obj;
                if (!isEligibleFirepowerUnit(ship)) continue;
                ShipFirepowerRose rose = rosesByShipId.get(ship.getId());
                if (rose == null) {
                    rose = new ShipFirepowerRose(ship);
                    rosesByShipId.put(ship.getId(), rose);
                } else {
                    rose.ship = ship;
                }
                updateSingleShipRose(rose, ship, now);
            }
        }

        // Prune expired/destroyed ships
        Iterator<Map.Entry<String, ShipFirepowerRose>> it = rosesByShipId.entrySet().iterator();
        while (it.hasNext()) {
            ShipFirepowerRose r = it.next().getValue();
            if (r.ship == null || !r.ship.isAlive() || r.ship.isHulk() || r.ship.isExpired()) {
                it.remove();
            }
        }
    }

    private static void updateSingleShipRose(ShipFirepowerRose rose, ShipAPI ship, float now) {
        float dt = rose.initialized ? Math.max(0.05f, Math.min(2.0f, now - rose.lastUpdateTime)) : 0.5f;
        rose.lastUpdateTime = now;

        // 1. Stabilize ship heading so StrafeTargetManeuverV2 +/-15 deg nose wobble does not whip arcs
        float rawFacing = normalizeAngle360(ship.getFacing());
        if (!rose.initialized) {
            rose.smoothedFacingDeg = rawFacing;
        } else {
            float diff = shortestAngleDiffDeg(rose.smoothedFacingDeg, rawFacing);
            float headingAlpha = Math.min(1.0f, dt / (TAU_HEADING_SMOOTH + dt));
            rose.smoothedFacingDeg = normalizeAngle360(rose.smoothedFacingDeg + diff * headingAlpha);
        }

        // 2. Compute ship-level live combat readiness (overload / venting / high hard-flux suppression)
        FluxTrackerAPI ft = ship.getFluxTracker();
        boolean overloadedOrVenting = false;
        float ovldVentRemaining = 0f;
        if (ft != null && ft.isOverloadedOrVenting()) {
            overloadedOrVenting = true;
            ovldVentRemaining = Math.max(ft.getOverloadTimeRemaining(), ft.getTimeToVent());
        }

        float fluxLevel = ship.getFluxLevel();
        float readiness;
        if (overloadedOrVenting && ovldVentRemaining > 0.6f) {
            // Instant temporal blind spot while overloaded or venting!
            readiness = 0.04f;
        } else if (fluxLevel > 0.70f) {
            readiness = Math.max(0.28f, 1.0f - (fluxLevel - 0.70f) * 2.2f);
        } else {
            readiness = 1.0f;
        }
        rose.liveReadinessMult = readiness;

        // Estimate ship flux sustainability factor for Sustained DoT
        float shipDissipation = 250f;
        if (ship.getMutableStats() != null && ship.getMutableStats().getFluxDissipation() != null) {
            float d = ship.getMutableStats().getFluxDissipation().getModifiedValue();
            if (d > 1f) shipDissipation = d;
        } else {
            shipDissipation = ShipCapabilityProfile.defaultStrengthForHull(ship.getHullSize()) * 22f;
        }

        List<WeaponAPI> weapons = ship.getAllWeapons();
        float totalGenFluxPerSec = 0f;
        if (weapons != null) {
            for (WeaponAPI w : weapons) {
                if (!isUsableCombatWeapon(w)) continue;
                DerivedWeaponStatsAPI derived = w.getDerivedStats();
                if (derived != null && derived.getSustainedFluxPerSecond() > 0f) {
                    totalGenFluxPerSec += derived.getSustainedFluxPerSecond();
                } else {
                    totalGenFluxPerSec += weaponSizeWeight(w.getSize()) * 45f;
                }
            }
        }
        float shipFluxSustainability = Math.max(0.35f, Math.min(1.0f, shipDissipation / Math.max(1f, totalGenFluxPerSec)));

        float[] rawAlpha = new float[NUM_SECTORS];
        float[] rawShort = new float[NUM_SECTORS];
        float[] rawSustained = new float[NUM_SECTORS];
        float[] rawPd = new float[NUM_SECTORS];
        float[] rawGenRange = new float[NUM_SECTORS];
        float[] rawSustRangeWeightedSum = new float[NUM_SECTORS];
        float[] rawShortRangeWeightedSum = new float[NUM_SECTORS];
        float[] rawAlphaRangeWeightedSum = new float[NUM_SECTORS];
        float[] rawPdRange = new float[NUM_SECTORS];

        float hullOmega = Math.abs(ship.getAngularVelocity());

        if (weapons != null && !weapons.isEmpty()) {
            for (WeaponAPI w : weapons) {
                if (!isUsableCombatWeapon(w)) continue;
                if (w.isDisabled() || w.isPermanentlyDisabled()) continue;
                if (w.usesAmmo() && w.getAmmo() <= 0) continue;

                float range = w.getRange();
                if (range <= 50f) continue;

                boolean isPdOnly = w.hasAIHint(AIHints.PD_ONLY) || w.hasAIHint(AIHints.ANTI_FTR);
                boolean isPdAlso = w.hasAIHint(AIHints.PD_ALSO);
                boolean isPd = isPdOnly || isPdAlso || w.hasAIHint(AIHints.PD);
                boolean isStrike = w.hasAIHint(AIHints.STRIKE);

                float pdFrac;
                float genFrac;
                if (isPdOnly || (isPd && !isPdAlso && !isStrike)) {
                    pdFrac = 1.0f;
                    genFrac = 0.14f; // light close-in pressure against frigates
                } else if (isPdAlso) {
                    pdFrac = 0.65f;
                    genFrac = 0.70f;
                } else {
                    pdFrac = 0.0f;
                    genFrac = 1.0f;
                }

                float sizeW = weaponSizeWeight(w.getSize());
                DerivedWeaponStatsAPI derived = w.getDerivedStats();
                float burstDmg;
                float dps;
                float sustDps;
                if (derived != null && (derived.getDps() > 0f || derived.getBurstDamage() > 0f || derived.getDamagePerShot() > 0f)) {
                    burstDmg = Math.max(derived.getBurstDamage(), derived.getDamagePerShot());
                    dps = Math.max(derived.getDps(), derived.getSustainedDps());
                    sustDps = Math.max(derived.getSustainedDps(), dps * 0.60f);
                    if (dps <= 1f && burstDmg > 0f) {
                        float cd = Math.max(0.5f, w.getCooldown());
                        dps = burstDmg / Math.min(5f, cd);
                        sustDps = burstDmg / Math.max(1f, cd);
                    }
                } else {
                    // Fallback for headless SimArena mock weapons
                    float hullScale = (ship.getHullSize() == HullSize.CAPITAL_SHIP) ? 1.45f
                            : (ship.getHullSize() == HullSize.CRUISER ? 1.20f : 1.0f);
                    burstDmg = sizeW * 150f * hullScale * (isStrike ? 2.2f : 1.0f);
                    dps = sizeW * 95f * hullScale * (isStrike ? 1.4f : 1.0f);
                    sustDps = sizeW * 75f * hullScale * (isStrike ? 0.55f : 1.0f);
                }

                // Damage-type & Strike modifiers
                DamageType dmgType = w.getDamageType();
                float typeMult = 1.0f;
                if (dmgType == DamageType.FRAGMENTATION && !isStrike) {
                    typeMult = 0.45f; // Fragmentation is much less threatening to shielded/armored ships
                } else if (dmgType == DamageType.KINETIC || dmgType == DamageType.HIGH_EXPLOSIVE) {
                    typeMult = 1.12f;
                }

                float wAlpha = burstDmg * typeMult * (isStrike ? 1.65f : 1.0f);
                // Cooldown gating on immediate alpha strike (0-2s window)
                float cdRem = w.getCooldownRemaining();
                if (cdRem > 2.2f) {
                    wAlpha *= Math.max(0.25f, 1.0f - (cdRem - 2.2f) / 6.0f);
                }

                // Short DoT (4-6s tactical exchange window)
                float wShortDot = (dps * 0.75f + (burstDmg / 4.5f) * 0.25f) * typeMult * (isStrike ? 1.25f : 1.0f);

                // Sustained DoT (15-30s line battle window, clamped by ship flux dissipation & ammo endurance)
                float ammoEndurance = 1.0f;
                if (w.usesAmmo() && w.getAmmoPerSecond() <= 0f) {
                    ammoEndurance = Math.min(0.65f, Math.max(0.22f, w.getAmmo() / 16f));
                }
                float wSustainedDot = sustDps * typeMult * shipFluxSustainability * ammoEndurance;

                // Weapon arc geometry relative to ship facing
                float arcFacingRel = normalizeAngle360(w.getArcFacing());
                float rawArc = w.getArc();
                if (rawArc <= 0f) {
                    // Default forward+beam arc for mock weapons in SimArena
                    rawArc = isStrike ? 90f : 210f;
                }

                WeaponSlotAPI slot = w.getSlot();
                boolean isTurret = (slot == null) || slot.isTurret();
                // Angular-velocity slew padding prevents sharp arc boundary flicker while ships turn/strafe
                float slewPad = Math.min(32f, hullOmega * 1.15f + (isTurret ? 14f : 8f));
                float halfArc = Math.min(180f, rawArc * 0.5f + slewPad * 0.5f);
                float shoulderWidth = 20f; // smooth cosine taper outside halfArc

                for (int s = 0; s < NUM_SECTORS; s++) {
                    float sectorCenterRel = s * SECTOR_DEG;
                    float angDist = Math.abs(shortestAngleDiffDeg(sectorCenterRel, arcFacingRel));
                    if (angDist > halfArc + shoulderWidth) continue;

                    float angularWeight;
                    if (angDist <= halfArc) {
                        angularWeight = 1.0f;
                    } else {
                        float u = (angDist - halfArc) / shoulderWidth;
                        angularWeight = 0.5f * (1.0f + (float) Math.cos(u * Math.PI));
                    }

                    if (genFrac > 0f) {
                        float gw = angularWeight * genFrac * readiness;
                        float aContrib = wAlpha * gw;
                        float shContrib = wShortDot * gw;
                        float suContrib = wSustainedDot * gw;
                        rawAlpha[s] += aContrib;
                        rawShort[s] += shContrib;
                        rawSustained[s] += suContrib;
                        float effRange = range * (0.72f + 0.28f * angularWeight);
                        // Accumulate potential-damage-weighted range per horizon so a single low-damage
                        // long-range plinker does not artificially inflate the main firepower envelope
                        rawAlphaRangeWeightedSum[s] += aContrib * effRange;
                        rawShortRangeWeightedSum[s] += shContrib * effRange;
                        rawSustRangeWeightedSum[s] += suContrib * effRange;
                        if (effRange > rawGenRange[s]) {
                            rawGenRange[s] = effRange;
                        }
                    }
                    if (pdFrac > 0f) {
                        float pw = angularWeight * pdFrac * readiness;
                        rawPd[s] += (dps * 0.8f + burstDmg * 0.2f) * pw;
                        float effPdRange = range * (0.75f + 0.25f * angularWeight);
                        if (effPdRange > rawPdRange[s]) {
                            rawPdRange[s] = effPdRange;
                        }
                    }
                }
            }
        }

        // 2b. Include Strike Craft (FighterWingAPI) in Firepower Rose:
        //     - If `ship` is an airborne fighter wing leader, aggregate the weapon firepower of its live wingmates
        //       so the entire squadron's strike/interceptor firepower is represented on the squadron rose.
        //     - If `ship` is a carrier / battlecarrier with fighter wings, project its wings' omnidirectional +
        //       attack-vector strike/PD firepower on the carrier's Firepower Rose as well.
        if (ship.isFighter() && ship.getWing() != null && ship.getWing().getWingMembers() != null) {
            List<ShipAPI> wingmates = ship.getWing().getWingMembers();
            int extraAlive = Math.max(0, wingmates.size() - 1);
            if (extraAlive > 0) {
                float mult = 1.0f + extraAlive * 0.85f;
                for (int s = 0; s < NUM_SECTORS; s++) {
                    rawAlpha[s] *= mult;
                    rawShort[s] *= mult;
                    rawSustained[s] *= mult;
                    rawPd[s] *= mult;
                    rawAlphaRangeWeightedSum[s] *= mult;
                    rawShortRangeWeightedSum[s] *= mult;
                    rawSustRangeWeightedSum[s] *= mult;
                }
            }
        } else if (!ship.isFighter()) {
            List<com.fs.starfarer.api.combat.FighterWingAPI> wings = ship.getAllWings();
            int bays = ship.getNumFighterBays();
            if (wings != null && !wings.isEmpty()) {
                for (com.fs.starfarer.api.combat.FighterWingAPI wing : wings) {
                    if (wing == null || wing.getSpec() == null) continue;
                    com.fs.starfarer.api.loading.FighterWingSpecAPI wSpec = wing.getSpec();
                    boolean isBomberOrAssault = wSpec.isBomber() || wSpec.isAssault();
                    boolean isInterceptorOrSupport = wSpec.isInterceptor() || wSpec.isSupport();
                    List<ShipAPI> wMembers = wing.getWingMembers();
                    int aliveFighters = 0;
                    float wingDirX = 0f;
                    float wingDirY = 0f;
                    if (wMembers != null && !wMembers.isEmpty()) {
                        for (ShipAPI fm : wMembers) {
                            if (fm != null && fm.isAlive() && !fm.isHulk()) {
                                aliveFighters++;
                                wingDirX += (fm.getLocation().x - ship.getLocation().x);
                                wingDirY += (fm.getLocation().y - ship.getLocation().y);
                            }
                        }
                    } else {
                        aliveFighters = Math.max(1, wSpec.getNumFighters());
                    }
                    int maxFighters = Math.max(1, wSpec.getNumFighters());
                    float wingLiveRatio = clampVal((float) aliveFighters / maxFighters, 0.20f, 1.0f) * readiness;
                    float wingRange = clampVal(wing.getRange() > 0f ? wing.getRange() * 0.72f : 1850f, 1100f, 2400f);

                    float wingAlpha = (isBomberOrAssault ? 360f : 140f) * wingLiveRatio;
                    float wingShort = (isBomberOrAssault ? 220f : 135f) * wingLiveRatio;
                    float wingSust = (isBomberOrAssault ? 155f : 120f) * wingLiveRatio;
                    float wingPd = (isInterceptorOrSupport ? 190f : 65f) * wingLiveRatio;

                    float wingBearingRel = 0f;
                    float dirLen = (float) Math.sqrt(wingDirX * wingDirX + wingDirY * wingDirY);
                    if (dirLen > 120f) {
                        float worldAng = normalizeAngle360((float) Math.toDegrees(Math.atan2(wingDirY, wingDirX)));
                        wingBearingRel = normalizeAngle360(worldAng - rose.smoothedFacingDeg);
                    }

                    for (int s = 0; s < NUM_SECTORS; s++) {
                        float sectorCenterRel = s * SECTOR_DEG;
                        float angDist = Math.abs(shortestAngleDiffDeg(sectorCenterRel, wingBearingRel));
                        // 360-deg carrier strike envelope + directional concentration along wing sortie bearing
                        float dirBoost = (angDist <= 75f)
                                ? (0.55f + 0.45f * (float) Math.cos((angDist / 75f) * (Math.PI * 0.5)))
                                : 0.55f;
                        float aAdd = wingAlpha * dirBoost;
                        float shAdd = wingShort * dirBoost;
                        float suAdd = wingSust * dirBoost;
                        float effWingR = wingRange * (0.78f + 0.22f * dirBoost);
                        rawAlpha[s] += aAdd;
                        rawShort[s] += shAdd;
                        rawSustained[s] += suAdd;
                        rawAlphaRangeWeightedSum[s] += aAdd * effWingR;
                        rawShortRangeWeightedSum[s] += shAdd * effWingR;
                        rawSustRangeWeightedSum[s] += suAdd * effWingR;
                        if (wingRange * dirBoost > rawGenRange[s]) {
                            rawGenRange[s] = effWingR;
                        }
                        if (wingPd > 0f) {
                            rawPd[s] += wingPd * dirBoost;
                            float pdR = Math.min(1350f, wingRange * 0.65f);
                            if (pdR > rawPdRange[s]) {
                                rawPdRange[s] = pdR;
                            }
                        }
                    }
                }
            } else if (bays > 0) {
                float wingRange = 1750f;
                for (int s = 0; s < NUM_SECTORS; s++) {
                    float aAdd = bays * 210f * readiness;
                    float shAdd = bays * 145f * readiness;
                    float suAdd = bays * 115f * readiness;
                    rawAlpha[s] += aAdd;
                    rawShort[s] += shAdd;
                    rawSustained[s] += suAdd;
                    rawPd[s] += bays * 95f * readiness;
                    rawAlphaRangeWeightedSum[s] += aAdd * wingRange;
                    rawShortRangeWeightedSum[s] += shAdd * wingRange;
                    rawSustRangeWeightedSum[s] += suAdd * wingRange;
                    if (wingRange > rawGenRange[s]) rawGenRange[s] = wingRange;
                    if (1100f > rawPdRange[s]) rawPdRange[s] = 1100f;
                }
            }
        }

        // Compute damage-weighted effective range per sector and horizon:
        //  - Sustained range is weighted by wSustainedDot so the outer envelope reflects where sustained DPS actually lands
        //  - Short DoT range is weighted by wShortDot (4-6s tactical exchange envelope)
        //  - Alpha range is weighted by wAlpha (0-2s burst/torpedo salvo range)
        //  - General range blends 72% potential-damage-weighted range + 28% max reach so a single weak long-range weapon
        //    cannot bloat the entire sector envelope, while true long-range batteries still project their full reach.
        float[] rawSustRange = new float[NUM_SECTORS];
        float[] rawShortRange = new float[NUM_SECTORS];
        float[] rawAlphaRange = new float[NUM_SECTORS];
        for (int s = 0; s < NUM_SECTORS; s++) {
            float maxR = rawGenRange[s];
            rawSustRange[s] = (rawSustained[s] > 1f)
                    ? (rawSustRangeWeightedSum[s] / rawSustained[s])
                    : maxR;
            rawShortRange[s] = (rawShort[s] > 1f)
                    ? (rawShortRangeWeightedSum[s] / rawShort[s])
                    : maxR;
            rawAlphaRange[s] = (rawAlpha[s] > 1f)
                    ? (rawAlphaRangeWeightedSum[s] / rawAlpha[s])
                    : rawShortRange[s];
        }

        // 3. Apply Asymmetric Temporal EMA per sector (fast rise on threat, slow decay on wobble, fast drop on vent/overload)
        float alphaRise = Math.min(1.0f, dt / (TAU_RISE + dt));
        float alphaFall = (overloadedOrVenting && ovldVentRemaining > 0.6f)
                ? Math.min(1.0f, dt / (TAU_VENT_DROP + dt))
                : Math.min(1.0f, dt / (TAU_FALL + dt));

        float peakShort = 1f;
        float peakAlpha = 1f;
        float peakSust = 1f;
        float maxGenR = 300f;
        float maxPdR = 200f;

        for (int s = 0; s < NUM_SECTORS; s++) {
            if (!rose.initialized) {
                rose.alpha[s] = rawAlpha[s];
                rose.shortDot[s] = rawShort[s];
                rose.sustainedDot[s] = rawSustained[s];
                rose.pdScore[s] = rawPd[s];
                rose.generalRange[s] = rawGenRange[s];
                rose.sustainedRange[s] = rawSustRange[s];
                rose.shortRange[s] = rawShortRange[s];
                rose.alphaRange[s] = rawAlphaRange[s];
                rose.pdRange[s] = rawPdRange[s];
            } else {
                rose.alpha[s] = blendAsymmetric(rose.alpha[s], rawAlpha[s], alphaRise, alphaFall);
                rose.shortDot[s] = blendAsymmetric(rose.shortDot[s], rawShort[s], alphaRise, alphaFall);
                rose.sustainedDot[s] = blendAsymmetric(rose.sustainedDot[s], rawSustained[s], alphaRise, alphaFall);
                rose.pdScore[s] = blendAsymmetric(rose.pdScore[s], rawPd[s], alphaRise, alphaFall);
                rose.generalRange[s] = blendAsymmetric(rose.generalRange[s], rawGenRange[s], alphaRise, alphaFall * 0.7f);
                rose.sustainedRange[s] = blendAsymmetric(rose.sustainedRange[s], rawSustRange[s], alphaRise, alphaFall * 0.7f);
                rose.shortRange[s] = blendAsymmetric(rose.shortRange[s], rawShortRange[s], alphaRise, alphaFall * 0.7f);
                rose.alphaRange[s] = blendAsymmetric(rose.alphaRange[s], rawAlphaRange[s], alphaRise, alphaFall * 0.7f);
                rose.pdRange[s] = blendAsymmetric(rose.pdRange[s], rawPdRange[s], alphaRise, alphaFall * 0.7f);
            }

            if (rose.shortDot[s] > peakShort) peakShort = rose.shortDot[s];
            if (rose.alpha[s] > peakAlpha) peakAlpha = rose.alpha[s];
            if (rose.sustainedDot[s] > peakSust) peakSust = rose.sustainedDot[s];
            if (rose.generalRange[s] > maxGenR) maxGenR = rose.generalRange[s];
            if (rose.pdRange[s] > maxPdR) maxPdR = rose.pdRange[s];
        }

        rose.peakSectorShortDot = peakShort;
        rose.peakSectorAlpha = peakAlpha;
        rose.peakSectorSustained = peakSust;
        rose.maxGeneralRange = maxGenR;
        rose.maxPdRange = maxPdR;
        // Compute absolute potential-damage weight across all 3 horizons (Sustained + Short Burst + Alpha Salvo)
        // so high-damage ships and torpedo boats visibly project heavier, denser firepower envelopes than light hulls
        float compositePotentialDmg = peakShort * 0.45f + peakSust * 0.30f + (peakAlpha * 0.35f) * 0.25f;
        rose.potentialDamageWeight = clampVal(0.38f + 0.62f * (float) Math.sqrt(compositePotentialDmg / 950f), 0.38f, 1.32f);
        rose.initialized = true;
    }

    /**
     * Evaluates overlapping weapon arcs and directional crossfire (L-shaped / U-shaped cordons)
     * at a candidate world point P(x, y) from the perspective of observerSide (0 = player, 1 = enemy).
     */
    public static PointFirepowerEval evaluatePoint(
            CombatEngineAPI engine,
            int observerSide,
            Vector2f point,
            float friendlyShieldArcDeg) {

        PointFirepowerEval eval = new PointFirepowerEval();
        if (engine == null || point == null) return eval;

        float dirSumX = 0f;
        float dirSumY = 0f;
        float totalDirWeight = 0f;

        for (ShipFirepowerRose rose : rosesByShipId.values()) {
            ShipAPI s = rose.ship;
            if (s == null || !s.isAlive() || s.isHulk() || s.isExpired()) continue;

            boolean isFriendly = (s.getOwner() == observerSide);
            if (!isFriendly && !OtcAI.isShipVisibleToSide(engine, observerSide, s)) {
                continue;
            }

            Vector2f sLoc = s.getLocation();
            float dx = point.x - sLoc.x;
            float dy = point.y - sLoc.y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float maxCheckRange = rose.maxGeneralRange + 320f;
            if (dist > maxCheckRange) continue;

            float worldAngleShipToPoint = (dist > 1f)
                    ? normalizeAngle360((float) Math.toDegrees(Math.atan2(dy, dx)))
                    : rose.smoothedFacingDeg;
            float relAngle = normalizeAngle360(worldAngleShipToPoint - rose.smoothedFacingDeg);

            float sectorReach = ShipFirepowerRose.sampleArrayAtRelAngle(rose.generalRange, relAngle);
            float pdReach = ShipFirepowerRose.sampleArrayAtRelAngle(rose.pdRange, relAngle);

            // Smooth range envelope with a 240u tactical approach buffer outside max weapon range
            float rangeFactor = 0f;
            if (sectorReach > 50f && dist <= sectorReach + 240f) {
                if (dist <= sectorReach * 0.85f) {
                    rangeFactor = 1.0f;
                } else {
                    float u = (dist - sectorReach * 0.85f) / Math.max(120f, sectorReach * 0.15f + 240f);
                    u = Math.max(0f, Math.min(1f, u));
                    rangeFactor = 0.5f * (1.0f + (float) Math.cos(u * Math.PI));
                }
            }

            if (isFriendly) {
                if (rangeFactor > 0f) {
                    eval.friendlyShortDot += ShipFirepowerRose.sampleArrayAtRelAngle(rose.shortDot, relAngle) * rangeFactor;
                    eval.friendlySustainedDot += ShipFirepowerRose.sampleArrayAtRelAngle(rose.sustainedDot, relAngle) * rangeFactor;
                }
            } else {
                if (rangeFactor > 0f) {
                    float a = ShipFirepowerRose.sampleArrayAtRelAngle(rose.alpha, relAngle) * rangeFactor;
                    float sh = ShipFirepowerRose.sampleArrayAtRelAngle(rose.shortDot, relAngle) * rangeFactor;
                    float su = ShipFirepowerRose.sampleArrayAtRelAngle(rose.sustainedDot, relAngle) * rangeFactor;

                    eval.incomingAlpha += a;
                    eval.incomingShortDot += sh;
                    eval.incomingSustainedDot += su;

                    // Direction from point P toward the threatening enemy ship (-dx, -dy)
                    float w = sh + a * 0.35f;
                    if (w > 1f && dist > 1f) {
                        float ux = -dx / dist;
                        float uy = -dy / dist;
                        dirSumX += ux * w;
                        dirSumY += uy * w;
                        totalDirWeight += w;

                        float threatBearing = normalizeAngle360((float) Math.toDegrees(Math.atan2(uy, ux)));
                        int oct = ((int) Math.floor(((threatBearing + 22.5f) % 360f) / 45f)) & 7;
                        eval.octantThreat[oct] += w;
                    }
                }
                if (pdReach > 50f && dist <= pdReach + 120f) {
                    float pdFactor = (dist <= pdReach) ? 1.0f : Math.max(0f, 1.0f - (dist - pdReach) / 120f);
                    eval.incomingPd += ShipFirepowerRose.sampleArrayAtRelAngle(rose.pdScore, relAngle) * pdFactor;
                }
            }
        }

        // Compute L-Shaped and U-Shaped Cordon Index from directional threat distribution
        if (totalDirWeight > 10f) {
            float netLen = (float) Math.sqrt(dirSumX * dirSumX + dirSumY * dirSumY);
            if (netLen > 1e-3f) {
                eval.threatDirX = dirSumX / netLen;
                eval.threatDirY = dirSumY / netLen;
            }
            float coherence = Math.max(0f, Math.min(1f, netLen / totalDirWeight));
            float rawDispersion = 1.0f - coherence;

            // Measure angular span across significant threat octants (>= 14% of total incoming fire and >= 45 threat)
            float sigThreshold = Math.max(45f, totalDirWeight * 0.14f);
            int sigCount = 0;
            float maxPairAngle = 0f;
            for (int i = 0; i < 8; i++) {
                if (eval.octantThreat[i] >= sigThreshold) {
                    sigCount++;
                    for (int j = i + 1; j < 8; j++) {
                        if (eval.octantThreat[j] >= sigThreshold) {
                            float ang = Math.abs(shortestAngleDiffDeg(i * 45f, j * 45f));
                            if (ang > maxPairAngle) {
                                maxPairAngle = ang;
                            }
                        }
                    }
                }
            }
            eval.activeThreatOctants = sigCount;
            eval.angularSpreadDeg = maxPairAngle;

            // Combine vector incoherence with angular spread:
            //  - 0 deg (single front): cordonIndex ~ 0.0
            //  - 90 deg (L-shaped crossfire): maxPairAngle = 90, rawDispersion ~ 0.29 -> cordonIndex ~ 0.45
            //  - 135-180 deg (U-shaped pocket): maxPairAngle >= 135, rawDispersion >= 0.55 -> cordonIndex ~ 0.75-1.0
            float spreadFactor = Math.max(0f, Math.min(1f, (maxPairAngle - 40f) / 115f));
            eval.cordonIndex = Math.max(0f, Math.min(1f, rawDispersion * 0.85f + spreadFactor * 0.55f));
        }

        // Narrow front shields (like Hammerhead's 90-deg front shield) suffer much higher crossfire vulnerability
        // when angularSpreadDeg exceeds their shield arc!
        float clampedShieldArc = Math.max(60f, Math.min(360f, friendlyShieldArcDeg));
        float shieldVuln = 1.0f + Math.max(0f, (240f - clampedShieldArc) / 150f);
        float baseThreat = eval.incomingShortDot * 0.68f + eval.incomingAlpha * 0.32f;
        eval.effectiveDanger = baseThreat * (1.0f + 1.80f * eval.cordonIndex * shieldVuln);

        return eval;
    }

    /**
     * Updates a division's live firepower & crossfire cordon telemetry at its current centroid.
     */
    public static void updateDivisionFirepowerTelemetry(CombatEngineAPI engine, DivisionState div) {
        if (div == null || !div.isCombatEffective()) return;
        float avgShieldArc = computeDivisionAvgShieldArc(div);
        PointFirepowerEval eval = evaluatePoint(engine, div.side, div.centroid, avgShieldArc);

        div.incomingAlphaThreat = eval.incomingAlpha;
        div.incomingShortDotThreat = eval.incomingShortDot;
        div.incomingSustainedDotThreat = eval.incomingSustainedDot;
        div.incomingPdThreat = eval.incomingPd;
        div.crossfireCordonIndex = eval.cordonIndex;

        // Asymmetric smoothing on division danger score
        if (div.smoothedDangerScore <= 0f) {
            div.smoothedDangerScore = eval.effectiveDanger;
        } else if (eval.effectiveDanger >= div.smoothedDangerScore) {
            div.smoothedDangerScore = div.smoothedDangerScore * 0.55f + eval.effectiveDanger * 0.45f;
        } else {
            div.smoothedDangerScore = div.smoothedDangerScore * 0.80f + eval.effectiveDanger * 0.20f;
        }
    }

    /**
     * Scores how well a candidate station point exploits blind spots or disunified weapon arcs
     * of the target enemy division.
     * Returns a positive bonus when candidatePos lies in the target division's weak/stern/venting sector
     * or engages only the outer ship of a disunified enemy formation.
     */
    public static float evaluateBlindSpotAdvantage(
            CombatEngineAPI engine,
            int observerSide,
            DivisionState enemyDiv,
            Vector2f candidatePos) {

        if (enemyDiv == null || candidatePos == null) return 0f;
        float totalAdvantage = 0f;

        for (ShipAPI e : enemyDiv.getAliveMembers()) {
            if (!OtcAI.isShipVisibleToSide(engine, observerSide, e)) continue;
            ShipFirepowerRose rose = rosesByShipId.get(e.getId());
            if (rose == null || rose.peakSectorShortDot <= 5f) continue;

            float dx = candidatePos.x - e.getLocation().x;
            float dy = candidatePos.y - e.getLocation().y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > rose.maxGeneralRange + 900f) continue;

            float worldBearing = (dist > 1f)
                    ? normalizeAngle360((float) Math.toDegrees(Math.atan2(dy, dx)))
                    : rose.smoothedFacingDeg;
            float bearingShortDot = rose.sampleShortDotAtWorldAngle(worldBearing);
            float sectorRatio = bearingShortDot / Math.max(1f, rose.peakSectorShortDot);

            // If this bearing has < 55% of the ship's peak arc firepower (e.g. stern/quarter blind spot or disunified facing)
            float fp = ShipCapabilityProfile.resolveShipFpStrength(e);
            if (sectorRatio < 0.55f) {
                totalAdvantage += (0.55f - sectorRatio) * fp * 14f;
            }
            // Extra temporal blind-spot bonus if the enemy ship is overloaded, venting, or above 80% flux
            if (rose.liveReadinessMult < 0.25f) {
                totalAdvantage += fp * 12f;
            } else if (e.getFluxLevel() >= 0.78f) {
                totalAdvantage += fp * 6f;
            }
        }
        return totalAdvantage;
    }

    /**
     * Evaluates candidate stations along an arc around centerPos at radius, choosing a position that:
     *  - Avoids L-shaped and U-shaped crossfire cordons (low effectiveDanger & low cordonIndex)
     *  - Exploits enemy blind spots / disunified weapon arcs (high evaluateBlindSpotAdvantage)
     *  - Maintains strong hysteresis stickiness toward preferredPos and the division's current stationPoint
     *    so stations glide smoothly and never jump back and forth.
     */
    public static void optimizeStationAlongArc(
            CombatEngineAPI engine,
            DivisionState myDiv,
            DivisionState targetEnemyDiv,
            Vector2f arcCenter,
            Vector2f preferredPos,
            float maxArcSweepDeg,
            int numSamples,
            Vector2f outStation) {

        if (myDiv == null || arcCenter == null || preferredPos == null) {
            if (outStation != null && preferredPos != null) outStation.set(preferredPos);
            return;
        }
        if (rosesByShipId.isEmpty() || numSamples <= 1) {
            outStation.set(preferredPos);
            return;
        }

        float dx = preferredPos.x - arcCenter.x;
        float dy = preferredPos.y - arcCenter.y;
        float radius = (float) Math.sqrt(dx * dx + dy * dy);
        if (radius < 150f) {
            outStation.set(preferredPos);
            return;
        }

        float baseAngleDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
        float shieldArc = computeDivisionAvgShieldArc(myDiv);

        // Evaluate the default preferredPos first so we only shift if another angle is meaningfully better
        PointFirepowerEval prefEval = evaluatePoint(engine, myDiv.side, preferredPos, shieldArc);
        // Fast path: if preferredPos has zero crossfire cordon and low danger, keep exact preferredPos
        if (prefEval.cordonIndex < 0.18f && prefEval.effectiveDanger < 120f) {
            outStation.set(preferredPos);
            return;
        }

        float bestScore = -Float.MAX_VALUE;
        float bestX = preferredPos.x;
        float bestY = preferredPos.y;

        boolean hasPrevStation = (myDiv.stationPoint.x != 0f || myDiv.stationPoint.y != 0f);
        float halfSamples = (numSamples - 1) * 0.5f;

        for (int i = 0; i < numSamples; i++) {
            float offsetFraction = (halfSamples > 0f) ? ((i - halfSamples) / halfSamples) : 0f;
            float angleDeg = baseAngleDeg + offsetFraction * maxArcSweepDeg;
            float rad = (float) Math.toRadians(angleDeg);
            float candX = arcCenter.x + (float) Math.cos(rad) * radius;
            float candY = arcCenter.y + (float) Math.sin(rad) * radius;
            Vector2f cand = new Vector2f(candX, candY);

            PointFirepowerEval eval = (i == (int) halfSamples)
                    ? prefEval
                    : evaluatePoint(engine, myDiv.side, cand, shieldArc);
            float blindSpotBonus = (targetEnemyDiv != null)
                    ? evaluateBlindSpotAdvantage(engine, myDiv.side, targetEnemyDiv, cand)
                    : 0f;

            // Score candidate: reward blind spots and friendly fire support; heavily penalize L/U crossfire cordons
            float score = blindSpotBonus * 1.15f
                    + eval.friendlyShortDot * 0.12f
                    - eval.effectiveDanger * 0.55f
                    - eval.cordonIndex * 520f;

            // Penalize high-alpha delete zones when our division already has elevated flux
            if (myDiv.avgFluxLevel >= 0.40f || myDiv.maxMemberFluxLevel >= 0.60f) {
                score -= eval.incomingAlpha * 0.45f;
            }

            // Deviation penalty from doctrinal preferred angle (keeps doctrinal geometry unless crossfire forces a shift)
            score -= Math.abs(offsetFraction) * 165f;

            // Temporal hysteresis stickiness toward current stationPoint so station never flickers
            if (hasPrevStation) {
                float distToPrev = distance(cand, myDiv.stationPoint);
                score -= distToPrev * 0.18f;
            }

            // Travel distance cost from current division centroid
            float distFromDiv = distance(cand, myDiv.centroid);
            score -= distFromDiv * 0.08f;

            if (score > bestScore) {
                bestScore = score;
                bestX = candX;
                bestY = candY;
            }
        }

        outStation.set(bestX, bestY);
    }

    public static float computeDivisionAvgShieldArc(DivisionState div) {
        if (div == null) return 180f;
        List<ShipAPI> alive = div.getAliveMembers();
        if (alive.isEmpty()) return 180f;
        float sum = 0f;
        for (ShipAPI s : alive) {
            ShieldAPI sh = s.getShield();
            if (sh != null && sh.getType() != ShieldAPI.ShieldType.NONE && sh.getArc() > 0f) {
                sum += sh.getArc();
            } else if (s.getPhaseCloak() != null) {
                sum += 270f;
            } else {
                sum += 90f; // Unshielded hulls are vulnerable to multi-angle crossfire
            }
        }
        return sum / alive.size();
    }

    // =========================================================================
    // Time, Space & Convergence Evaluators (Telemetry, Net Space Control & Phase-Space Convergence)
    // =========================================================================

    /**
     * Computes a division's weighted speed, unencumbered Time radius, and base Space (Zone of Control) radii.
     *  - Time is a function of how much space a division can cross using its weighted average speed
     *    (FP-weighted mean anchored by the slowest core hull so mixed-speed divisions stay cohesive).
     *  - Space is a division's Zone of Control (Inner Directly Dominated, Mid Dominated Nearby, Outer Potentially Dominated).
     */
    public static void updateDivisionBaseTimeSpace(DivisionState div) {
        if (div == null) return;
        List<ShipAPI> alive = div.getAliveMembers();
        if (alive.isEmpty()) {
            div.weightedTimeSpeed = 0f;
            div.freeTimeRadius = 0f;
            div.engagementLevel = 0f;
            div.timeFreedomFactor = 1f;
            div.effectiveTimeRadius = 0f;
            div.spaceInnerRadius = 0f;
            div.spaceDominatedRadius = 0f;
            div.spacePotentialRadius = 0f;
            div.spaceDominanceWeight = 0f;
            return;
        }

        float weightedSpeedSum = 0f;
        float weightSum = 0f;
        float minCoreSpeed = Float.MAX_VALUE;
        int totalFighterWings = 0;
        float wingSpeedAccum = 0f;
        float wingRangeAccum = 0f;

        for (ShipAPI s : alive) {
            if (div.detachedStragglerIds.contains(s.getId()) && div.coreMemberCount >= 1) {
                continue;
            }
            float w = ShipCapabilityProfile.resolveShipFpStrength(s) * (s == div.flagship ? 1.35f : 1.0f);
            float spd = Math.max(25f, s.getMaxSpeed());
            weightedSpeedSum += spd * w;
            weightSum += w;
            if (spd < minCoreSpeed) {
                minCoreSpeed = spd;
            }

            // Include Strike Craft (FighterWingAPI) in Time & Space base metrics
            List<com.fs.starfarer.api.combat.FighterWingAPI> wings = s.getAllWings();
            if (wings != null && !wings.isEmpty()) {
                for (com.fs.starfarer.api.combat.FighterWingAPI wing : wings) {
                    if (wing == null) continue;
                    totalFighterWings++;
                    float fSpd = 235f;
                    if (wing.getWingMembers() != null && !wing.getWingMembers().isEmpty()) {
                        ShipAPI f0 = wing.getWingMembers().get(0);
                        if (f0 != null && f0.getMaxSpeed() > 25f) {
                            fSpd = f0.getMaxSpeed();
                        }
                    }
                    wingSpeedAccum += fSpd;
                    float wr = wing.getRange() > 0f ? wing.getRange() : 2200f;
                    wingRangeAccum += clampVal(wr, 1200f, 3400f);
                    // Strike craft project fast sortie Time speed for their carrier division
                    float wingTimeWeight = 5.5f;
                    weightedSpeedSum += (fSpd * 0.72f) * wingTimeWeight;
                    weightSum += wingTimeWeight;
                }
            } else if (s.getNumFighterBays() > 0) {
                int bays = s.getNumFighterBays();
                totalFighterWings += bays;
                wingSpeedAccum += 235f * bays;
                wingRangeAccum += 2200f * bays;
                float wingTimeWeight = 5.0f * bays;
                weightedSpeedSum += (235f * 0.72f) * wingTimeWeight;
                weightSum += wingTimeWeight;
            }
        }
        if (weightSum <= 0f) {
            ShipAPI first = alive.get(0);
            float spd = Math.max(25f, first.getMaxSpeed());
            weightedSpeedSum = spd;
            weightSum = 1f;
            minCoreSpeed = spd;
        }
        float fpWeightedSpeed = weightedSpeedSum / weightSum;
        if (minCoreSpeed == Float.MAX_VALUE) minCoreSpeed = fpWeightedSpeed;
        if (totalFighterWings > 0) {
            // Strike craft lift the division's operational Time floor so carrier divisions project Time via their wings
            minCoreSpeed = Math.max(minCoreSpeed, fpWeightedSpeed * 0.78f);
        }

        // Mixed-speed weighting: 68% FP/flagship/wing-weighted mean + 32% slowest core member anchor
        float targetSpeed = 0.68f * fpWeightedSpeed + 0.32f * minCoreSpeed;
        if (div.weightedTimeSpeed <= 1f) {
            div.weightedTimeSpeed = targetSpeed;
        } else {
            div.weightedTimeSpeed = div.weightedTimeSpeed * 0.65f + targetSpeed * 0.35f;
        }

        // Unencumbered Time radius over TIME_HORIZON_SECONDS (including strike-craft sortie projection)
        float wingTimeBonus = totalFighterWings > 0 ? Math.min(520f, totalFighterWings * 95f) : 0f;
        div.freeTimeRadius = clampVal(
                div.weightedTimeSpeed * TIME_HORIZON_SECONDS + div.spreadRadius * 0.32f + wingTimeBonus,
                850f,
                3900f);

        // Combat readiness multiplier for Space dominance
        float readiness = clampVal(
                (1.05f - 0.55f * div.avgFluxLevel) * Math.max(0.38f, div.avgHullLevel),
                0.28f,
                1.10f);
        float avgWingReach = totalFighterWings > 0 ? (wingRangeAccum / totalFighterWings) : 0f;
        float effRange = Math.max(550f, Math.max(div.avgEngageRange, avgWingReach * 0.68f));

        // Space Zone 1 (Directly Dominated Up Close) & Zone 2 (Dominated Nearby), expanded by Strike Craft screens & sorties
        float wingInnerBonus = Math.min(240f, totalFighterWings * 42f);
        float wingDomBonus = Math.min(480f, totalFighterWings * 85f * readiness);
        div.spaceInnerRadius = clampVal(
                div.spreadRadius * 0.55f + Math.min(900f, effRange * 0.50f) + wingInnerBonus,
                420f,
                1480f);
        div.spaceDominatedRadius = clampVal(
                div.spreadRadius * 0.72f + effRange * (0.84f + 0.22f * readiness) + wingDomBonus,
                div.spaceInnerRadius + 220f,
                2650f);
        div.spaceDominanceWeight = Math.max(4f, (div.totalFpStrength + totalFighterWings * 4.5f) * readiness);

        // Default effectiveTimeRadius & spacePotentialRadius before cross-group engagement reduction
        if (div.effectiveTimeRadius <= 1f) {
            div.timeFreedomFactor = 1.0f - clampVal(div.engagementLevel, 0f, 0.82f);
            div.effectiveTimeRadius = div.freeTimeRadius * div.timeFreedomFactor;
        }
        float fpScale = clampVal((float) Math.sqrt(div.totalFpStrength / 38f), 0.55f, 1.35f);
        div.spacePotentialRadius = clampVal(
                div.spaceDominatedRadius + div.effectiveTimeRadius * 0.42f * fpScale,
                div.spaceDominatedRadius + 180f,
                4100f);
    }

    /**
     * Evaluates cross-division Time & Space interactions and Convergence across myGroup and enemyGroup:
     *  1. Computes Engagement Level (reduction of Time freedom caused by Enemy Space & Enemy Firepower overlapping
     *     a division's Time circle), updating effectiveTimeRadius = freeTimeRadius * (1 - engagementLevel).
     *  2. Updates Space Potential Radius as a function of effective Time and Firepower, and identifies Strongpoints
     *     (mutually supporting Space bastions) and Weak Points (exposed seams in the line).
     *  3. Updates Convergence states (Alpha Core flagship / non-human fleets) and computes phase-locked
     *     Convergence focal coordinates.
     */
    public static void updateCrossGroupTimeSpaceConvergence(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup) {

        if (myGroup == null) return;
        if (engine != null) {
            lastTimeSpaceConvEngine = engine;
            lastTimeSpaceConvUpdateTime = engine.getTotalElapsedTime(false);
        }
        updateConvergenceCommandStates(engine, myGroup);
        if (enemyGroup != null) {
            updateConvergenceCommandStates(engine, enemyGroup);
        }

        for (DivisionState d : myGroup.divisions) {
            if (d.isCombatEffective() && d.freeTimeRadius <= 1f) {
                updateDivisionBaseTimeSpace(d);
            }
        }
        if (enemyGroup != null) {
            for (DivisionState e : enemyGroup.divisions) {
                if (e.isCombatEffective() && e.freeTimeRadius <= 1f) {
                    updateDivisionBaseTimeSpace(e);
                }
            }
        }

        for (DivisionState div : myGroup.divisions) {
            if (!div.isCombatEffective()) continue;

            // 1. Friendly Space mutual support from sibling divisions
            float friendlySupport = 0f;
            for (DivisionState peer : myGroup.divisions) {
                if (peer == null || peer == div || !peer.isCombatEffective()) continue;
                float d = distance(div.centroid, peer.centroid);
                float maxSupportDist = peer.spaceDominatedRadius + div.spaceInnerRadius;
                if (d < maxSupportDist) {
                    float ov = clampVal(1.0f - (d / Math.max(300f, maxSupportDist)), 0f, 1f);
                    friendlySupport += ov * peer.spaceDominanceWeight;
                }
            }
            div.friendlySpaceSupport = friendlySupport;

            // 2. Enemy Space overlap with this division's Time circle & footprint
            float enemySpaceOverlap = 0f;
            if (enemyGroup != null) {
                for (DivisionState eDiv : enemyGroup.divisions) {
                    if (eDiv == null || !eDiv.isCombatEffective()) continue;
                    if (!OtcAI.isDivisionVisibleToSide(engine, div.side, eDiv)) continue;
                    Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, eDiv);
                    float d = distance(div.centroid, ePos);
                    float sumTimeAndEnemySpace = div.freeTimeRadius * 0.72f + eDiv.spaceDominatedRadius;
                    if (d < sumTimeAndEnemySpace) {
                        float ov = clampVal(
                                1.0f - (d - eDiv.spaceInnerRadius * 0.6f)
                                        / Math.max(350f, sumTimeAndEnemySpace - eDiv.spaceInnerRadius * 0.6f),
                                0f,
                                1f);
                        enemySpaceOverlap += ov * eDiv.spaceDominanceWeight;
                    }
                }
            }
            div.hostileSpacePressure = enemySpaceOverlap;
            div.netSpaceControl = (friendlySupport + div.spaceDominanceWeight * 0.65f) - enemySpaceOverlap;

            // 3. Engagement Level = function of Enemy Space overlap + Enemy Firepower overlap + close flux load
            float refStrength = Math.max(16f, div.totalFpStrength * 1.25f);
            float spaceEngagement = clampVal(enemySpaceOverlap / refStrength, 0f, 0.62f);
            float firepowerEngagement = clampVal(
                    (div.incomingShortDotThreat / Math.max(160f, div.totalFpStrength * 15f)) * 0.45f
                            + div.crossfireCordonIndex * 0.36f,
                    0f,
                    0.62f);
            float fluxEngagement = clampVal(div.avgFluxLevel * 0.26f, 0f, 0.22f);
            float rawEngagement = clampVal(
                    Math.max(spaceEngagement, firepowerEngagement)
                            + 0.38f * Math.min(spaceEngagement, firepowerEngagement)
                            + fluxEngagement,
                    0f,
                    0.82f);

            if (div.engagementLevel <= 0.001f) {
                div.engagementLevel = rawEngagement;
            } else {
                div.engagementLevel = div.engagementLevel * 0.60f + rawEngagement * 0.40f;
            }
            div.timeFreedomFactor = 1.0f - clampVal(div.engagementLevel, 0f, 0.82f);
            div.effectiveTimeRadius = div.freeTimeRadius * div.timeFreedomFactor;

            // 4. Space Potential Radius is a function of effective Time and Firepower
            float fpScale = clampVal((float) Math.sqrt(div.totalFpStrength / 38f), 0.55f, 1.35f);
            div.spacePotentialRadius = clampVal(
                    div.spaceDominatedRadius + div.effectiveTimeRadius * 0.42f * fpScale,
                    div.spaceDominatedRadius + 180f,
                    4100f);

            // 4b. Directional Engagement Pinch across 24 polar sectors (15 deg each):
            //     A division engaged on a given bearing is strongly pinched in the direction of enemy contact and
            //     Enemy Space, while retaining much greater Time freedom to slide sideways (laterally) or fall back
            //     away from the fighting (further aided by Friendly Space and pinched by any flanking Enemy Space).
            computeDirectionalTimeEnvelope(engine, div, myGroup, enemyGroup, refStrength, fluxEngagement);

            // 5. Strongpoint vs Weak Point classification
            div.isStrongpoint = (friendlySupport >= Math.max(24f, div.totalFpStrength * 0.58f))
                    && div.avgFluxLevel < 0.62f;
            div.isWeakPoint = (friendlySupport < 12f)
                    && (div.avgFluxLevel >= 0.38f
                    || div.crossfireCordonIndex >= 0.28f
                    || div.totalFpStrength <= 32f
                    || enemySpaceOverlap > div.spaceDominanceWeight * 0.90f);

            // 6. Update Convergence focal point & score
            DivisionState focusEnemy = (div.targetEnemyDivision != null && div.targetEnemyDivision.isCombatEffective())
                    ? div.targetEnemyDivision
                    : myGroup.primaryThreatEnemyDivision;
            if (focusEnemy != null && focusEnemy.isCombatEffective()
                    && OtcAI.isDivisionVisibleToSide(engine, div.side, focusEnemy)) {
                div.convergenceScore = computeConvergenceTargetScore(engine, div, focusEnemy, myGroup);
                Vector2f visEnemy = OtcAI.getVisibleCentroidForSide(engine, div.side, focusEnemy);
                float lookahead = clampVal(
                        distance(div.centroid, visEnemy) / Math.max(65f, div.weightedTimeSpeed * div.timeFreedomFactor * 1.4f),
                        1.5f,
                        8.0f);
                div.convergenceFocalPoint.set(
                        visEnemy.x + focusEnemy.velocity.x * lookahead * 0.65f,
                        visEnemy.y + focusEnemy.velocity.y * lookahead * 0.65f);
            } else {
                div.convergenceScore = 0f;
                div.convergenceFocalPoint.set(div.stationPoint);
            }
        }
    }

    /**
     * Computes the 24-sector Directional Time Horizon envelope for {@code div}:
     *   - Advancing directly toward an engaging enemy division or into Enemy Space suffers the full frontal
     *     Engagement Level pinch (blocking forward Time projection).
     *   - Sliding sideways (laterally perpendicular to the fighting) suffers only mild lateral friction,
     *     modulated by +/- Enemy Space vs Friendly Space on each flank.
     *   - Falling back away from the fighting retains near-full Time freedom (only minor flux/inertia drag),
     *     unless another enemy division or Enemy Space zone blocks the rear (e.g. L/U-cordon encirclement).
     */
    public static void computeDirectionalTimeEnvelope(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            float refStrength,
            float fluxEngagement) {
        if (div == null) return;
        int nDirs = DivisionState.TIME_DIRECTIONS;
        float freeR = Math.max(200f, div.freeTimeRadius);
        float[] rawDirEng = new float[nDirs];

        // Baseline non-directional drag from the division's own flux load (venting/overloaded ships maneuver slower in all directions)
        float baseDrag = clampVal(fluxEngagement * 0.72f, 0f, 0.18f);

        for (int s = 0; s < nDirs; s++) {
            float angleDeg = s * (360f / nDirs);
            double rad = Math.toRadians(angleDeg);
            float ux = (float) Math.cos(rad);
            float uy = (float) Math.sin(rad);

            float dirPinch = baseDrag;

            // 1. Directional Enemy Contact & Space Blocking Lobe
            if (enemyGroup != null) {
                for (DivisionState eDiv : enemyGroup.divisions) {
                    if (eDiv == null || !eDiv.isCombatEffective()) continue;
                    if (!OtcAI.isDivisionVisibleToSide(engine, div.side, eDiv)) continue;
                    Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, eDiv);
                    float dx = ePos.x - div.centroid.x;
                    float dy = ePos.y - div.centroid.y;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    float eDomR = Math.max(850f, eDiv.spaceDominatedRadius);
                    float eInR = Math.max(450f, eDiv.spaceInnerRadius);
                    float maxInteractDist = freeR * 0.82f + eDomR;
                    if (d < maxInteractDist) {
                        float ov = clampVal(
                                1.0f - (d - eInR * 0.55f) / Math.max(350f, maxInteractDist - eInR * 0.55f),
                                0f,
                                1f);
                        float eRatio = clampVal((ov * Math.max(12f, eDiv.spaceDominanceWeight)) / Math.max(16f, refStrength), 0f, 0.78f);
                        float ex = (d > 1f) ? (dx / d) : 1f;
                        float ey = (d > 1f) ? (dy / d) : 0f;
                        // Cosine alignment: +1 = directly toward enemy, 0 = sideways/lateral, -1 = backward away from enemy
                        float cosAlign = ux * ex + uy * ey;
                        float closeGrapple = (d < eInR) ? (0.20f * (1.0f - d / eInR)) : 0f;

                        float directionalFactor;
                        if (cosAlign >= 0f) {
                            // Toward the fighting: strong frontal pinch (exceeds isotropic average directly into enemy contact)
                            directionalFactor = 0.26f + 1.18f * cosAlign * cosAlign + closeGrapple;
                        } else if (cosAlign >= -0.45f) {
                            // Sideways / oblique slide: light lateral friction, tapering down as angle turns away
                            float uLat = (cosAlign + 0.45f) / 0.45f; // 1.0 at 90 deg, 0.0 at 117 deg
                            directionalFactor = 0.04f + 0.22f * uLat * uLat + closeGrapple * 0.65f;
                        } else {
                            // Backwards away from the fighting: division can freely disengage unless deep inside inner grapple
                            directionalFactor = 0.02f + closeGrapple * 0.35f;
                        }
                        dirPinch += eRatio * directionalFactor;
                    }
                }
            }

            // 2. Plus/Minus External Space Control along the directional slide vector (P_step = centroid + u * 0.55 * freeR):
            //    Enemy Space in that direction pinches Time further; allied peer Friendly Space in that direction eases the slide!
            Vector2f stepPt = new Vector2f(
                    div.centroid.x + ux * (freeR * 0.55f),
                    div.centroid.y + uy * (freeR * 0.55f));
            float peerFriendlySpace = 0f;
            if (myGroup != null) {
                for (DivisionState peer : myGroup.divisions) {
                    if (peer == null || peer == div || !peer.isCombatEffective()) continue;
                    float dist = distance(stepPt, peer.centroid);
                    float potR = Math.max(peer.spaceDominatedRadius + 180f, peer.spacePotentialRadius);
                    if (dist < potR) {
                        float w = (dist <= peer.spaceInnerRadius) ? 1.0f
                                : (dist <= peer.spaceDominatedRadius
                                ? (0.55f + 0.45f * (1f - (dist - peer.spaceInnerRadius) / Math.max(100f, peer.spaceDominatedRadius - peer.spaceInnerRadius)))
                                : (0.55f * (1f - (dist - peer.spaceDominatedRadius) / Math.max(100f, potR - peer.spaceDominatedRadius))));
                        peerFriendlySpace += peer.spaceDominanceWeight * Math.max(0f, w);
                    }
                }
            }
            float stepEnemySpace = 0f;
            if (enemyGroup != null) {
                for (DivisionState e : enemyGroup.divisions) {
                    if (e == null || !e.isCombatEffective()) continue;
                    if (!OtcAI.isDivisionVisibleToSide(engine, div.side, e)) continue;
                    Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, e);
                    float dist = distance(stepPt, ePos);
                    float potR = Math.max(1200f, e.spacePotentialRadius);
                    float domR = Math.max(850f, e.spaceDominatedRadius);
                    float inR = Math.max(450f, e.spaceInnerRadius);
                    float domW = Math.max(10f, e.spaceDominanceWeight);
                    if (dist < potR) {
                        float w = (dist <= inR) ? 1.0f
                                : (dist <= domR
                                ? (0.55f + 0.45f * (1f - (dist - inR) / Math.max(100f, domR - inR)))
                                : (0.55f * (1f - (dist - domR) / Math.max(100f, potR - domR))));
                        stepEnemySpace += domW * Math.max(0f, w);
                    }
                }
            }
            float externalNetSpace = peerFriendlySpace - stepEnemySpace;
            if (externalNetSpace < 0f) {
                // Sliding into Enemy Space increases the directional pinch
                dirPinch += clampVal((-externalNetSpace) / Math.max(20f, refStrength * 1.25f), 0f, 0.34f);
            } else if (externalNetSpace > 0f) {
                // Sliding into allied Friendly Space reduces the directional pinch (friendly cover aids lateral/rear slide)
                dirPinch -= clampVal(externalNetSpace / Math.max(24f, refStrength * 1.65f), 0f, 0.16f);
            }

            rawDirEng[s] = clampVal(dirPinch, 0.0f, 0.84f);
        }

        // 3. Circular 3-tap angular smoothing + temporal EMA so the polar Time contour is smooth and organic
        for (int s = 0; s < nDirs; s++) {
            int prev = (s + nDirs - 1) % nDirs;
            int next = (s + 1) % nDirs;
            float smoothedAng = 0.24f * rawDirEng[prev] + 0.52f * rawDirEng[s] + 0.24f * rawDirEng[next];
            smoothedAng = clampVal(smoothedAng, 0.0f, 0.84f);
            if (!div.directionalTimeInitialized) {
                div.directionalEngagement[s] = smoothedAng;
            } else {
                div.directionalEngagement[s] = div.directionalEngagement[s] * 0.55f + smoothedAng * 0.45f;
            }
            div.directionalTimeRadii[s] = freeR * (1.0f - clampVal(div.directionalEngagement[s], 0.0f, 0.84f));
        }
        div.directionalTimeInitialized = true;
    }

    /**
     * Smoothly samples a division's directional Time radius (in world units) at any world angle {@code worldAngleDeg}
     * (0 = East/+X, 90 = North/+Y, 180 = West/-X, 270 = South/-Y).
     */
    public static float getDirectionalTimeRadius(DivisionState div, float worldAngleDeg) {
        if (div == null) return 0f;
        if (!div.directionalTimeInitialized) {
            return Math.max(100f, div.effectiveTimeRadius > 1f ? div.effectiveTimeRadius : div.freeTimeRadius);
        }
        int nDirs = DivisionState.TIME_DIRECTIONS;
        float norm = normalizeAngle360(worldAngleDeg);
        float exactIdx = norm / (360f / nDirs);
        int i0 = ((int) Math.floor(exactIdx)) % nDirs;
        int i1 = (i0 + 1) % nDirs;
        float frac = exactIdx - (float) Math.floor(exactIdx);
        // Cosine-smoothed interpolation between adjacent 15-deg sectors
        float w = 0.5f * (1.0f - (float) Math.cos(frac * Math.PI));
        return div.directionalTimeRadii[i0] * (1.0f - w) + div.directionalTimeRadii[i1] * w;
    }

    /**
     * Evaluates net Space control (Friendly Space dominance minus Enemy Space dominance) at any point P(x, y).
     * Positive values indicate Friendly Safe/Dominated Space; negative values indicate Enemy Dominated/Dangerous Space.
     */
    public static float evaluateNetSpaceControlAtPoint(
            CombatEngineAPI engine,
            int observerSide,
            Vector2f point,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup) {
        if (point == null) return 0f;
        float friendlySpace = 0f;
        float enemySpace = 0f;
        if (myGroup != null) {
            for (DivisionState d : myGroup.divisions) {
                if (d == null || !d.isCombatEffective()) continue;
                float dist = distance(point, d.centroid);
                if (dist < d.spacePotentialRadius) {
                    float w = (dist <= d.spaceInnerRadius) ? 1.0f
                            : (dist <= d.spaceDominatedRadius
                            ? (0.55f + 0.45f * (1f - (dist - d.spaceInnerRadius) / Math.max(100f, d.spaceDominatedRadius - d.spaceInnerRadius)))
                            : (0.55f * (1f - (dist - d.spaceDominatedRadius) / Math.max(100f, d.spacePotentialRadius - d.spaceDominatedRadius))));
                    friendlySpace += d.spaceDominanceWeight * Math.max(0f, w);
                }
                // Airborne Strike Craft squadrons also project Friendly Space at their squadron location
                for (ShipAPI s : d.getAliveMembers()) {
                    List<com.fs.starfarer.api.combat.FighterWingAPI> wings = s.getAllWings();
                    if (wings == null) continue;
                    for (com.fs.starfarer.api.combat.FighterWingAPI wing : wings) {
                        ShipAPI wLead = resolveAliveWingLeader(wing);
                        if (wLead == null || distance(wLead.getLocation(), d.centroid) <= 260f) continue;
                        float wDist = distance(point, wLead.getLocation());
                        if (wDist < 920f) {
                            float ww = (wDist <= 260f) ? 1.0f : (1.0f - (wDist - 260f) / 660f);
                            friendlySpace += 5.5f * Math.max(0f, ww);
                        }
                    }
                }
            }
        }
        if (enemyGroup != null) {
            for (DivisionState e : enemyGroup.divisions) {
                if (e == null || !e.isCombatEffective()) continue;
                if (!OtcAI.isDivisionVisibleToSide(engine, observerSide, e)) continue;
                Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, observerSide, e);
                float dist = distance(point, ePos);
                float potR = Math.max(1200f, e.spacePotentialRadius);
                float domR = Math.max(850f, e.spaceDominatedRadius);
                float inR = Math.max(450f, e.spaceInnerRadius);
                float domW = Math.max(10f, e.spaceDominanceWeight);
                if (dist < potR) {
                    float w = (dist <= inR) ? 1.0f
                            : (dist <= domR
                            ? (0.55f + 0.45f * (1f - (dist - inR) / Math.max(100f, domR - inR)))
                            : (0.55f * (1f - (dist - domR) / Math.max(100f, potR - domR))));
                    enemySpace += domW * Math.max(0f, w);
                }
                // Enemy airborne Strike Craft squadrons also project Enemy Space at their squadron location
                for (ShipAPI s : e.getAliveMembers()) {
                    List<com.fs.starfarer.api.combat.FighterWingAPI> wings = s.getAllWings();
                    if (wings == null) continue;
                    for (com.fs.starfarer.api.combat.FighterWingAPI wing : wings) {
                        ShipAPI wLead = resolveAliveWingLeader(wing);
                        if (wLead == null || !OtcAI.isShipVisibleToSide(engine, observerSide, wLead)) continue;
                        if (distance(wLead.getLocation(), ePos) <= 260f) continue;
                        float wDist = distance(point, wLead.getLocation());
                        if (wDist < 920f) {
                            float ww = (wDist <= 260f) ? 1.0f : (1.0f - (wDist - 260f) / 660f);
                            enemySpace += 5.5f * Math.max(0f, ww);
                        }
                    }
                }
            }
        }
        return friendlySpace - enemySpace;
    }

    public static ShipAPI resolveAliveWingLeader(com.fs.starfarer.api.combat.FighterWingAPI wing) {
        if (wing == null) return null;
        ShipAPI lead = wing.getLeader();
        if (lead != null && lead.isAlive() && !lead.isHulk() && !lead.isExpired()) {
            return lead;
        }
        List<ShipAPI> members = wing.getWingMembers();
        if (members != null) {
            for (ShipAPI fm : members) {
                if (fm != null && fm.isAlive() && !fm.isHulk() && !fm.isExpired()) {
                    return fm;
                }
            }
        }
        return null;
    }

    /**
     * Synthesizes Time, Space, and Firepower into a single non-linear Phase-Space Convergence score
     * for an AI / Alpha-Core commander evaluating an enemy division target:
     *  - Rather than adding Time + Space + Firepower linearly, computes the multiplicative coincidence of:
     *      (1) Reachability within myDiv's effectiveTimeRadius (can we project force before the window closes?),
     *      (2) Target's Space isolation & pinned state (high enemy engagementLevel + low friendlySpaceSupport = Weak Point),
     *      (3) Multi-division orthogonal crossfire synchronization (sibling divisions converging at 65-125 deg),
     *      (4) Target's flux/shield collapse vulnerability.
     */
    public static float computeConvergenceTargetScore(
            CombatEngineAPI engine,
            DivisionState myDiv,
            DivisionState enemyDiv,
            BattleGroupState myGroup) {
        if (myDiv == null || enemyDiv == null || !enemyDiv.isCombatEffective()) return 0f;
        Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, myDiv.side, enemyDiv);
        float dist = distance(myDiv.centroid, ePos);

        // 1. Temporal Reachability Coincidence (how cleanly myDiv's effectiveTimeRadius + weapon/strike-craft reach covers ePos)
        float strikeCraftReachBonus = myDiv.hasCarrierCapability ? 650f : 0f;
        float reachHorizon = Math.max(900f, myDiv.effectiveTimeRadius + myDiv.avgEngageRange * 0.75f + strikeCraftReachBonus);
        float timeCoincidence = clampVal(1.35f - (dist / Math.max(600f, reachHorizon)), 0.15f, 1.35f);

        // 2. Target Pinning & Space Seam Vulnerability (enemy pinned in Time + isolated in Space; carriers also exploit low-PD targets)
        float enemyPinnedBonus = 1.0f + enemyDiv.engagementLevel * 0.85f;
        float seamVulnerability = enemyDiv.isWeakPoint ? 1.38f : (enemyDiv.isStrongpoint ? 0.74f : 1.05f);
        if (myDiv.hasCarrierCapability && !enemyDiv.isScreenCapable) {
            seamVulnerability *= 1.18f;
        }

        // 3. Firepower / Flux Collapse Multiplier
        float fluxCollapseMult = 1.0f + enemyDiv.avgFluxLevel * 1.15f + (1.0f - enemyDiv.avgHullLevel) * 0.75f;
        float blindSpot = evaluateBlindSpotAdvantage(engine, myDiv.side, enemyDiv, myDiv.centroid);

        // 4. Multi-Division Phase-Locked Orthogonal Convergence (reward ~90 deg L/U pincer angles with sibling divisions)
        float pincerSynergy = 1.0f;
        if (myGroup != null) {
            float myBearing = (float) Math.toDegrees(Math.atan2(myDiv.centroid.y - ePos.y, myDiv.centroid.x - ePos.x));
            for (DivisionState peer : myGroup.divisions) {
                if (peer == null || peer == myDiv || !peer.isCombatEffective()) continue;
                if (peer.targetEnemyDivision == enemyDiv || peer == myGroup.mainEffortDivision) {
                    float peerBearing = (float) Math.toDegrees(Math.atan2(peer.centroid.y - ePos.y, peer.centroid.x - ePos.x));
                    float angDiff = Math.abs(shortestAngleDiffDeg(myBearing, peerBearing));
                    if (angDiff >= 55f && angDiff <= 145f) {
                        pincerSynergy += 0.32f;
                    }
                }
            }
        }

        // Multiplicative phase-space product (strictly stronger than linear sum when all 3 align!)
        float baseValue = OtcAI.getVisibleFpForSide(engine, myDiv.side, enemyDiv) * 9.0f + blindSpot * 0.8f;
        return baseValue * timeCoincidence * enemyPinnedBonus * seamVulnerability * fluxCollapseMult * Math.min(1.75f, pincerSynergy);
    }

    /**
     * Phase-locked station optimizer used when Convergence decision-making is active (non-human fleets or
     * Alpha Core-commanded flagships):
     *  - Evaluates candidate points against predicted target position at arrival time (t + tau),
     *  - Maximizes orthogonal crossfire angle (75-115 deg) with sibling friendly divisions engaging the same target,
     *  - Exploits enemy weapon-arc blind spots while staying outside secondary enemy divisions' inner Space cores.
     */
    public static void optimizeConvergenceStation(
            CombatEngineAPI engine,
            DivisionState myDiv,
            DivisionState targetEnemyDiv,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            Vector2f arcCenter,
            Vector2f preferredPos,
            float maxArcSweepDeg,
            Vector2f outStation) {

        if (myDiv == null || arcCenter == null || preferredPos == null) {
            if (outStation != null && preferredPos != null) outStation.set(preferredPos);
            return;
        }
        float dx = preferredPos.x - arcCenter.x;
        float dy = preferredPos.y - arcCenter.y;
        float radius = (float) Math.sqrt(dx * dx + dy * dy);
        if (radius < 150f) {
            outStation.set(preferredPos);
            return;
        }

        float baseAngleDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
        float shieldArc = computeDivisionAvgShieldArc(myDiv);
        int numSamples = 9;
        float halfSamples = (numSamples - 1) * 0.5f;
        boolean hasPrevStation = (myDiv.stationPoint.x != 0f || myDiv.stationPoint.y != 0f);

        float bestScore = -Float.MAX_VALUE;
        float bestX = preferredPos.x;
        float bestY = preferredPos.y;

        for (int i = 0; i < numSamples; i++) {
            float offsetFraction = (halfSamples > 0f) ? ((i - halfSamples) / halfSamples) : 0f;
            float angleDeg = baseAngleDeg + offsetFraction * maxArcSweepDeg;
            float rad = (float) Math.toRadians(angleDeg);
            float candX = arcCenter.x + (float) Math.cos(rad) * radius;
            float candY = arcCenter.y + (float) Math.sin(rad) * radius;
            Vector2f cand = new Vector2f(candX, candY);

            PointFirepowerEval fpEval = evaluatePoint(engine, myDiv.side, cand, shieldArc);
            float blindSpot = (targetEnemyDiv != null)
                    ? evaluateBlindSpotAdvantage(engine, myDiv.side, targetEnemyDiv, cand)
                    : 0f;
            float netSpace = evaluateNetSpaceControlAtPoint(engine, myDiv.side, cand, myGroup, enemyGroup);

            // Reward orthogonal crossfire bearing relative to sibling divisions targeting targetEnemyDiv
            float crossfireSynergy = 0f;
            if (myGroup != null && targetEnemyDiv != null) {
                for (DivisionState peer : myGroup.divisions) {
                    if (peer == null || peer == myDiv || !peer.isCombatEffective()) continue;
                    if (peer.targetEnemyDivision == targetEnemyDiv || peer == myGroup.mainEffortDivision) {
                        float peerAng = (float) Math.toDegrees(
                                Math.atan2(peer.centroid.y - arcCenter.y, peer.centroid.x - arcCenter.x));
                        float sep = Math.abs(shortestAngleDiffDeg(angleDeg, peerAng));
                        if (sep >= 65f && sep <= 125f) {
                            crossfireSynergy += 240f;
                        } else if (sep < 28f) {
                            crossfireSynergy -= 160f; // avoid stacking into the exact same firing lane
                        }
                    }
                }
            }

            // Time reachability: prefer stations inside myDiv's effectiveTimeRadius
            float distFromDiv = distance(cand, myDiv.centroid);
            float timePenalty = (distFromDiv <= myDiv.effectiveTimeRadius)
                    ? (distFromDiv * 0.05f)
                    : (myDiv.effectiveTimeRadius * 0.05f + (distFromDiv - myDiv.effectiveTimeRadius) * 0.22f);

            float score = blindSpot * 1.45f
                    + crossfireSynergy
                    + netSpace * 1.85f
                    + fpEval.friendlyShortDot * 0.16f
                    - fpEval.effectiveDanger * 0.68f
                    - fpEval.cordonIndex * 640f
                    - timePenalty
                    - Math.abs(offsetFraction) * 110f;

            if (hasPrevStation) {
                score -= distance(cand, myDiv.stationPoint) * 0.14f;
            }

            if (score > bestScore) {
                bestScore = score;
                bestX = candX;
                bestY = candY;
            }
        }

        outStation.set(bestX, bestY);
    }

    private static float clampVal(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    // =========================================================================
    // Battle Screen / Command View Window State Guards & Input/Rendering
    // =========================================================================

    /**
     * Returns true ONLY when the engine belongs to an active combat battle (CombatState),
     * and false on the Main Menu background battle (TitleScreenState / GameState.TITLE) or Campaign.
     */
    public static boolean isRealCombatEngine(CombatEngineAPI engine) {
        if (engine == null) return false;
        try {
            com.fs.starfarer.api.GameState state = Global.getCurrentState();
            if (state != null && state != com.fs.starfarer.api.GameState.COMBAT) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        com.fs.starfarer.api.combat.CombatUIAPI ui = engine.getCombatUI();
        if (ui != null) {
            String clsName = ui.getClass().getName();
            if (clsName.contains("TitleScreenState")) {
                return false;
            }
            if (!"com.fs.starfarer.combat.CombatState".equals(clsName) && !clsName.contains("$Proxy")) {
                return false;
            }
            return true;
        }
        // In the live game (Global.getSettings() != null), a real battle always has a CombatState UI;
        // in headless SimArena unit tests, Global.getSettings() is null and ui is null.
        return Global.getSettings() == null;
    }

    /**
     * Returns true ONLY when the Command View Window (Tab tactical map) is actively open
     * inside a real battle (never on the Main Menu TitleScreenState, pre-battle deployment
     * dialog, or live arcade piloting HUD).
     */
    public static boolean isCommandViewWindowActive(CombatEngineAPI engine) {
        if (!isRealCombatEngine(engine)) return false;
        com.fs.starfarer.api.combat.CombatUIAPI ui = engine.getCombatUI();
        if (ui == null) return false;
        if (!ui.isShowingCommandUI() || ui.getCommandUIOpacity() <= 0.05f) {
            return false;
        }
        return true;
    }

    /**
     * Returns the Y coordinate (bottom of the 34px square icon box) for button index:
     *   0 = Time Overlay        (top)
     *   1 = Space Overlay       (second)
     *   2 = Firepower Overlay   (third)
     *   3 = Convergence Overlay (fourth, visible ONLY when hasAlphaCoreInPlayerFleet(engine) is true)
     */
    public static float getOverlayButtonY(float screenH, int buttonIndex) {
        float centerMidY = screenH * BTN_Y_FRACTION;
        // Button 1 (Space) is centered around centerMidY; Button 0 (Time) is above it;
        // Button 2 (Firepower) and Button 3 (Convergence) are below it at 43px stride.
        return Math.round(centerMidY + (1 - buttonIndex) * BTN_VERTICAL_STRIDE);
    }

    public static float getOverlayButtonX(float screenW) {
        return Math.round(screenW - BTN_WIDTH - BTN_RIGHT_MARGIN);
    }

    /**
     * Processes mouse events for:
     *   1. The "[URGENT COMMS FROM <AI-controlled ship>]" modal pop-up ("Yes" / "No" buttons) when active,
     *   2. The right-side Warroom square-bordered icon buttons (Time, Space, Firepower, and Convergence when
     *      the player has an Alpha Core in their fleet) on the Command View Window.
     * Strictly ignores input on the Main Menu (TitleScreenState) or when the Command View Window is closed.
     */
    public static void processInputEvents(List<InputEventAPI> events) {
        CombatEngineAPI engine = null;
        try {
            engine = Global.getCombatEngine();
        } catch (Throwable ignored) {
        }
        processInputEvents(engine, events);
    }

    public static void processInputEvents(CombatEngineAPI engine, List<InputEventAPI> events) {
        if (events == null || Global.getSettings() == null) return;
        if (!isCommandViewWindowActive(engine)) return;
        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        float btnX = getOverlayButtonX(screenW);
        int maxButtons = hasAlphaCoreInPlayerFleet(engine) ? 4 : 3;

        for (InputEventAPI event : events) {
            if (event == null || event.isConsumed()) continue;
            if (event.isMouseEvent()) {
                lastMouseX = event.getX();
                lastMouseY = event.getY();
            }
            if (event.isLMBDownEvent()) {
                float mx = event.getX();
                float my = event.getY();

                // 1. If the Urgent Comms modal pop-up is open, handle "Yes" and "No" button clicks
                if (pendingCommsAlphaCoreShip != null) {
                    float modalX = Math.round((screenW - COMMS_MODAL_W) * 0.5f);
                    float modalY = Math.round((screenH - COMMS_MODAL_H) * 0.5f);
                    float yesX = modalX + 86f;
                    float noX = modalX + COMMS_MODAL_W - 86f - COMMS_BTN_W;
                    float choiceBtnY = modalY + COMMS_BTN_Y_OFFSET;

                    if (mx >= yesX - 2f && mx <= yesX + COMMS_BTN_W + 2f
                            && my >= choiceBtnY - 2f && my <= choiceBtnY + COMMS_BTN_H + 2f) {
                        event.consume();
                        acceptPendingAlphaCoreCommand(engine);
                        try {
                            Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f);
                        } catch (Throwable ignored) {
                        }
                        return;
                    }
                    if (mx >= noX - 2f && mx <= noX + COMMS_BTN_W + 2f
                            && my >= choiceBtnY - 2f && my <= choiceBtnY + COMMS_BTN_H + 2f) {
                        event.consume();
                        refusePendingAlphaCoreCommand(engine);
                        try {
                            Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f);
                        } catch (Throwable ignored) {
                        }
                        return;
                    }
                    // Consume clicks inside the modal body so they don't issue unintended map orders underneath
                    if (mx >= modalX && mx <= modalX + COMMS_MODAL_W
                            && my >= modalY && my <= modalY + COMMS_MODAL_H) {
                        event.consume();
                        return;
                    }
                }

                // 2. Right-side Warroom overlay buttons (Time, Space, Firepower, and conditional Convergence)
                if (mx >= btnX - 2f && mx <= btnX + BTN_WIDTH + 2f) {
                    for (int idx = 0; idx < maxButtons; idx++) {
                        float btnY = getOverlayButtonY(screenH, idx);
                        if (my >= btnY - 2f && my <= btnY + BTN_HEIGHT + 2f) {
                            event.consume();
                            float nowReal = (float) ((System.currentTimeMillis() % 86400000L) * 0.001);
                            if (Math.abs(nowReal - lastToggleRealTime) > 0.05f) {
                                lastToggleRealTime = nowReal;
                                if (idx == 0) {
                                    toggleTimeOverlayVisible();
                                } else if (idx == 1) {
                                    toggleSpaceOverlayVisible();
                                } else if (idx == 2) {
                                    toggleOverlayVisible();
                                } else if (idx == 3) {
                                    handleConvergenceButtonClick(engine);
                                }
                                try {
                                    Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f);
                                } catch (Throwable ignored) {
                                }
                            }
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * Legacy live-screen hook retained as a no-op guard so the Firepower Overlay button and roses
     * render exclusively inside the Command View Window (Tab tactical map via DivisionTacticalMapOverlay)
     * and never appear on the Main Menu (TitleScreenState) or over non-Command-View dialogs.
     */
    public static void renderOnLiveBattleScreen(
            CombatEngineAPI engine,
            ViewportAPI viewport,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup) {
        // Rendered strictly inside the Command View Window (DivisionTacticalMapOverlay.render)
        // when isCommandViewWindowActive(engine) is true.
    }

    /**
     * Renders stabilized Polar Firepower Roses / Planes for all visible combat ships in screen space
     * (worldToScreen: sx = offsetX + wx * scale, sy = offsetY + wy * scale).
     */
    public static void renderFirepowerRosesScreenSpace(
            CombatEngineAPI engine,
            float scale,
            float offsetX,
            float offsetY,
            float fade,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup) {

        if (!overlayVisible || engine == null || scale <= 1e-5f || fade <= 0.04f) return;

        // Ensure roses exist even if toggled on while paused before the first 0.5s tick
        if (rosesByShipId.isEmpty()) {
            updateBattlefieldRoses(engine, engine.getTotalElapsedTime(false));
        }

        float screenW = Global.getSettings() != null ? Global.getSettings().getScreenWidth() : 1920f;
        float screenH = Global.getSettings() != null ? Global.getSettings().getScreenHeight() : 1080f;
        boolean highGfx = DivisionAISettings.isTsfcHighGraphics();
        FogOfWarAPI playerFog = engine.getFogOfWar(0);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (highGfx) {
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
        }

        // 1. Render per-ship Polar Firepower Roses (Sustained DoT outer plane, Short DoT mid plane, Alpha core, PD ring)
        for (ShipFirepowerRose rose : rosesByShipId.values()) {
            ShipAPI s = rose.ship;
            if (s == null || !s.isAlive() || s.isHulk() || s.isExpired()) continue;
            float maxWorldR = Math.max(rose.maxGeneralRange, rose.maxPdRange);
            if (maxWorldR <= 80f) continue;

            float sx = offsetX + s.getLocation().x * scale;
            float sy = offsetY + s.getLocation().y * scale;
            float maxRadPx = maxWorldR * scale + 16f;
            if (!isCircleVisibleOnScreen(sx, sy, maxRadPx, screenW, screenH)) {
                continue;
            }

            boolean isEnemy = (s.getOwner() == 1);
            boolean isAlly = !isEnemy && s.isAlly();
            if (isEnemy && playerFog != null && !playerFog.isVisible(s)) {
                continue;
            }

            renderSingleShipFirepowerRose(rose, s, isEnemy, isAlly, scale, offsetX, offsetY, fade);
        }

        // 2. Render L-Cordon / U-Cordon crossfire pocket indicators for divisions experiencing crossfire
        renderDivisionCordonIndicators(engine, playerGroup, false, scale, offsetX, offsetY, fade);
        renderDivisionCordonIndicators(engine, alliedAiGroup, false, scale, offsetX, offsetY, fade);
        renderDivisionCordonIndicators(engine, enemyGroup, true, scale, offsetX, offsetY, fade);

        GL11.glPopMatrix();
        GL11.glPopAttrib();

        // 3. Render compact L-CORDON / U-CORDON warning labels on divisions in crossfire
        renderDivisionCordonLabels(engine, playerGroup, false, scale, offsetX, offsetY, fade);
    }

    private static boolean isCircleVisibleOnScreen(float cx, float cy, float radPx, float screenW, float screenH) {
        return (cx + radPx >= -16f)
                && (cx - radPx <= screenW + 16f)
                && (cy + radPx >= -16f)
                && (cy - radPx <= screenH + 16f);
    }

    /**
     * Renders the Time, Space, and Convergence tactical layers in screen space on the Command View Window:
     *   - Time Overlay: Increasingly transparent circle representing how much space a division can cross using its
     *     weighted speed, directly contracted by its Engagement Level (outer faint dashed ring shows unencumbered
     *     freeTimeRadius when pinned).
     *   - Space Overlay: Increasingly transparent 3-tier Zone of Control circle (Inner Directly Dominated Up Close,
     *     Mid Dominated Nearby, Outer Potentially Dominated given time to maneuver) plus Strongpoint / Weak Point markers.
     *   - Convergence Overlay: Tri-Tachyon / AI geometric symbology (triangle inside a square inside a circle) and
     *     phase-locked convergence vectors synthesizing Time, Space, and Firepower.
     */
    public static void renderTimeSpaceAndConvergenceScreenSpace(
            CombatEngineAPI engine,
            float scale,
            float offsetX,
            float offsetY,
            float fade,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup) {

        if (engine == null || scale <= 1e-5f || fade <= 0.04f) return;
        setActiveBattleGroups(playerGroup, alliedAiGroup, enemyGroup);
        if (!timeOverlayVisible && !spaceOverlayVisible && !convergenceOverlayVisible) return;

        // Throttle cross-group Time/Space/Convergence telemetry recomputation so it does NOT run 3x on every 60+ FPS render frame
        float now = engine.getTotalElapsedTime(false);
        int divCountSig = ((playerGroup != null ? playerGroup.divisions.size() : 0) << 16)
                ^ ((alliedAiGroup != null ? alliedAiGroup.divisions.size() : 0) << 8)
                ^ (enemyGroup != null ? enemyGroup.divisions.size() : 0);
        boolean needsUninitBase = hasAnyUninitializedDivisionTimeSpace(playerGroup)
                || hasAnyUninitializedDivisionTimeSpace(alliedAiGroup)
                || hasAnyUninitializedDivisionTimeSpace(enemyGroup);
        if (needsUninitBase
                || engine != lastTimeSpaceConvEngine
                || divCountSig != lastTimeSpaceConvDivCountSig
                || Math.abs(now - lastTimeSpaceConvUpdateTime) >= 0.24f) {
            if (playerGroup != null) {
                for (DivisionState d : playerGroup.divisions) {
                    if (d.isCombatEffective() && d.freeTimeRadius <= 1f) {
                        updateDivisionBaseTimeSpace(d);
                    }
                }
                updateCrossGroupTimeSpaceConvergence(engine, playerGroup, enemyGroup);
            }
            if (alliedAiGroup != null) {
                for (DivisionState d : alliedAiGroup.divisions) {
                    if (d.isCombatEffective() && d.freeTimeRadius <= 1f) {
                        updateDivisionBaseTimeSpace(d);
                    }
                }
                updateCrossGroupTimeSpaceConvergence(engine, alliedAiGroup, enemyGroup);
            }
            if (enemyGroup != null) {
                for (DivisionState e : enemyGroup.divisions) {
                    if (e.isCombatEffective() && e.freeTimeRadius <= 1f) {
                        updateDivisionBaseTimeSpace(e);
                    }
                }
                updateCrossGroupTimeSpaceConvergence(engine, enemyGroup, playerGroup);
            }
            lastTimeSpaceConvEngine = engine;
            lastTimeSpaceConvUpdateTime = now;
            lastTimeSpaceConvDivCountSig = divCountSig;
        }

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (DivisionAISettings.isTsfcHighGraphics()) {
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
        }

        if (spaceOverlayVisible) {
            renderGroupSpaceCircles(engine, playerGroup, false, false, scale, offsetX, offsetY, fade);
            renderGroupSpaceCircles(engine, alliedAiGroup, false, true, scale, offsetX, offsetY, fade);
            renderGroupSpaceCircles(engine, enemyGroup, true, false, scale, offsetX, offsetY, fade);
        }

        if (timeOverlayVisible) {
            renderGroupTimeCircles(engine, playerGroup, false, false, scale, offsetX, offsetY, fade);
            renderGroupTimeCircles(engine, alliedAiGroup, false, true, scale, offsetX, offsetY, fade);
            renderGroupTimeCircles(engine, enemyGroup, true, false, scale, offsetX, offsetY, fade);
        }

        if (convergenceOverlayVisible) {
            renderGroupConvergenceLayer(engine, playerGroup, false, false, scale, offsetX, offsetY, fade);
            renderGroupConvergenceLayer(engine, alliedAiGroup, false, true, scale, offsetX, offsetY, fade);
            renderGroupConvergenceLayer(engine, enemyGroup, true, false, scale, offsetX, offsetY, fade);
        }

        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }

    private static boolean hasAnyUninitializedDivisionTimeSpace(BattleGroupState group) {
        if (group == null) return false;
        for (DivisionState d : group.divisions) {
            if (d != null && d.isCombatEffective() && (d.freeTimeRadius <= 1f || !d.directionalTimeInitialized)) {
                return true;
            }
        }
        return false;
    }

    private static void renderGroupTimeCircles(
            CombatEngineAPI engine,
            BattleGroupState group,
            boolean isEnemy,
            boolean isAlly,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        if (group == null || group.divisions.isEmpty()) return;
        float screenW = Global.getSettings() != null ? Global.getSettings().getScreenWidth() : 1920f;
        float screenH = Global.getSettings() != null ? Global.getSettings().getScreenHeight() : 1080f;
        FogOfWarAPI playerFog = engine.getFogOfWar(0);

        for (DivisionState div : group.divisions) {
            if (!div.isCombatEffective()) continue;
            if (isEnemy && !OtcAI.isDivisionVisibleToSide(engine, 0, div)) continue;
            Vector2f cWorld = isEnemy ? OtcAI.getVisibleCentroidForSide(engine, 0, div) : div.centroid;
            if (isEnemy && playerFog != null && !playerFog.isVisible(cWorld)) continue;

            float cx = offsetX + cWorld.x * scale;
            float cy = offsetY + cWorld.y * scale;
            float effRadPx = Math.max(18f, div.effectiveTimeRadius * scale);
            float freeRadPx = Math.max(effRadPx, div.freeTimeRadius * scale);

            float r, g, b;
            if (isEnemy) {
                r = 1.00f; g = 0.42f; b = 0.24f;
            } else if (isAlly) {
                r = 0.72f; g = 0.96f; b = 0.38f;
            } else {
                r = 0.30f; g = 0.88f; b = 1.00f;
            }

            if (isCircleVisibleOnScreen(cx, cy, freeRadPx + 16f, screenW, screenH)) {
                // Increasingly transparent directional Time contour: pinched toward the enemy / Enemy Space,
                // open laterally (sideways) and backward away from the fighting!
                drawDirectionalTimeGradient(cx, cy, div, scale, r, g, b, 0.22f * fade, 48);

                // Crisp inner horizon contour at 50% directional Time horizon and outer directional Time contour
                drawDirectionalTimeOutline(cx, cy, div, scale, 0.50f, r, g, b, 0.18f * fade, 1.1f, 48, true);
                drawDirectionalTimeOutline(cx, cy, div, scale, 1.00f, r, g, b, 0.48f * fade, 1.55f, 48, false);

                // When Engagement Level pinches any sector of the Time circle, render the unencumbered freeTimeRadius as a faint dashed ring
                if (div.engagementLevel > 0.06f && freeRadPx > 20f) {
                    drawCircleOutline(cx, cy, freeRadPx, r, g * 0.75f, b * 0.75f, 0.26f * fade, 1.15f, 36, true);
                }
            }

            // Render airborne Strike Craft squadron Time horizon rings when deployed away from the carrier centroid
            for (ShipAPI s : div.getAliveMembers()) {
                List<com.fs.starfarer.api.combat.FighterWingAPI> wings = s.getAllWings();
                if (wings == null) continue;
                for (com.fs.starfarer.api.combat.FighterWingAPI wing : wings) {
                    ShipAPI wLead = resolveAliveWingLeader(wing);
                    if (wLead == null || distance(wLead.getLocation(), cWorld) <= 320f) continue;
                    if (isEnemy && playerFog != null && !playerFog.isVisible(wLead)) continue;
                    float wx = offsetX + wLead.getLocation().x * scale;
                    float wy = offsetY + wLead.getLocation().y * scale;
                    float wSpd = Math.max(180f, wLead.getMaxSpeed());
                    float wTimeRadPx = Math.max(12f, (wSpd * 3.8f) * scale);
                    if (!isCircleVisibleOnScreen(wx, wy, wTimeRadPx + 8f, screenW, screenH)) continue;
                    drawIncreasinglyTransparentCircle(wx, wy, wTimeRadPx, r, g, b, 0.15f * fade, 24);
                    drawCircleOutline(wx, wy, wTimeRadPx, r, g, b, 0.36f * fade, 1.2f, 24, true);
                }
            }
        }
    }

    private static void populateDirectionalTimeScratchCoords(float cx, float cy, DivisionState div, float scale) {
        boolean init = div.directionalTimeInitialized;
        float fallbackWorldR = Math.max(100f, div.effectiveTimeRadius > 1f ? div.effectiveTimeRadius : div.freeTimeRadius);
        for (int i = 0; i < 48; i++) {
            float worldR;
            if (!init) {
                worldR = fallbackWorldR;
            } else {
                int s0 = i >> 1;
                if ((i & 1) == 0) {
                    worldR = div.directionalTimeRadii[s0];
                } else {
                    int s1 = (s0 + 1) % DivisionState.TIME_DIRECTIONS;
                    worldR = 0.5f * (div.directionalTimeRadii[s0] + div.directionalTimeRadii[s1]);
                }
            }
            float outerRadPx = Math.max(14f, worldR * scale);
            float midRadPx = outerRadPx * 0.55f;
            float cos = UNIT_COS_48[i];
            float sin = UNIT_SIN_48[i];
            SCRATCH_TIME_OUTER_X[i] = cx + cos * outerRadPx;
            SCRATCH_TIME_OUTER_Y[i] = cy + sin * outerRadPx;
            SCRATCH_TIME_MID_X[i] = cx + cos * midRadPx;
            SCRATCH_TIME_MID_Y[i] = cy + sin * midRadPx;
        }
    }

    private static void drawDirectionalTimeGradient(
            float cx,
            float cy,
            DivisionState div,
            float scale,
            float r,
            float g,
            float b,
            float centerAlpha,
            int segs) {
        if (div == null || scale <= 1e-5f || centerAlpha <= 0.002f) return;
        populateDirectionalTimeScratchCoords(cx, cy, div, scale);
        float midAlpha = centerAlpha * 0.48f;

        // Inner core to 55% directional mid band
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(r, g, b, centerAlpha);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(r, g, b, midAlpha);
        for (int i = 0; i <= 48; i++) {
            int idx = (i == 48) ? 0 : i;
            GL11.glVertex2f(SCRATCH_TIME_MID_X[idx], SCRATCH_TIME_MID_Y[idx]);
        }
        GL11.glEnd();

        // Mid band fading smoothly to 0.0 alpha at the outer directional Time contour
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        for (int i = 0; i <= 48; i++) {
            int idx = (i == 48) ? 0 : i;
            GL11.glColor4f(r, g, b, midAlpha);
            GL11.glVertex2f(SCRATCH_TIME_MID_X[idx], SCRATCH_TIME_MID_Y[idx]);
            GL11.glColor4f(r, g, b, 0.0f);
            GL11.glVertex2f(SCRATCH_TIME_OUTER_X[idx], SCRATCH_TIME_OUTER_Y[idx]);
        }
        GL11.glEnd();
    }

    private static void drawDirectionalTimeOutline(
            float cx,
            float cy,
            DivisionState div,
            float scale,
            float radScale,
            float r,
            float g,
            float b,
            float alpha,
            float lineWidth,
            int segs,
            boolean dashed) {
        if (div == null || scale <= 1e-5f || alpha <= 0.002f) return;
        GL11.glLineWidth(lineWidth);
        GL11.glColor4f(r, g, b, alpha);
        if (dashed) {
            GL11.glBegin(GL11.GL_LINES);
            for (int i = 0; i < 48; i += 2) {
                int i1 = (i + 1) % 48;
                float x0 = cx + (SCRATCH_TIME_OUTER_X[i] - cx) * radScale;
                float y0 = cy + (SCRATCH_TIME_OUTER_Y[i] - cy) * radScale;
                float x1 = cx + (SCRATCH_TIME_OUTER_X[i1] - cx) * radScale;
                float y1 = cy + (SCRATCH_TIME_OUTER_Y[i1] - cy) * radScale;
                GL11.glVertex2f(x0, y0);
                GL11.glVertex2f(x1, y1);
            }
            GL11.glEnd();
        } else {
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < 48; i++) {
                float x0 = cx + (SCRATCH_TIME_OUTER_X[i] - cx) * radScale;
                float y0 = cy + (SCRATCH_TIME_OUTER_Y[i] - cy) * radScale;
                GL11.glVertex2f(x0, y0);
            }
            GL11.glEnd();
        }
    }

    private static void renderGroupSpaceCircles(
            CombatEngineAPI engine,
            BattleGroupState group,
            boolean isEnemy,
            boolean isAlly,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        if (group == null || group.divisions.isEmpty()) return;
        float screenW = Global.getSettings() != null ? Global.getSettings().getScreenWidth() : 1920f;
        float screenH = Global.getSettings() != null ? Global.getSettings().getScreenHeight() : 1080f;
        FogOfWarAPI playerFog = engine.getFogOfWar(0);

        for (DivisionState div : group.divisions) {
            if (!div.isCombatEffective()) continue;
            if (isEnemy && !OtcAI.isDivisionVisibleToSide(engine, 0, div)) continue;
            Vector2f cWorld = isEnemy ? OtcAI.getVisibleCentroidForSide(engine, 0, div) : div.centroid;
            if (isEnemy && playerFog != null && !playerFog.isVisible(cWorld)) continue;

            float cx = offsetX + cWorld.x * scale;
            float cy = offsetY + cWorld.y * scale;
            float inRadPx = Math.max(14f, div.spaceInnerRadius * scale);
            float domRadPx = Math.max(inRadPx + 8f, div.spaceDominatedRadius * scale);
            float potRadPx = Math.max(domRadPx + 8f, div.spacePotentialRadius * scale);

            float r, g, b;
            if (isEnemy) {
                r = 0.98f; g = 0.26f; b = 0.30f;
            } else if (isAlly) {
                r = 0.62f; g = 0.92f; b = 0.34f;
            } else {
                r = 0.20f; g = 0.78f; b = 0.96f;
            }

            if (isCircleVisibleOnScreen(cx, cy, potRadPx + 16f, screenW, screenH)) {
                // Tier 3 (Outer): Potentially Dominated Space given Time to maneuver (faintest outer halo)
                drawIncreasinglyTransparentCircle(cx, cy, potRadPx, r, g, b, 0.11f * fade, 36);
                drawCircleOutline(cx, cy, potRadPx, r, g, b, 0.22f * fade, 1.1f, 36, true);

                // Tier 2 (Middle): Dominated Nearby Space
                drawIncreasinglyTransparentCircle(cx, cy, domRadPx, r, g, b, 0.16f * fade, 36);
                drawCircleOutline(cx, cy, domRadPx, r, g, b, 0.38f * fade, 1.4f, 36, false);

                // Tier 1 (Inner Core): Directly Dominated Up Close Space
                drawIncreasinglyTransparentCircle(cx, cy, inRadPx, r, g, b, 0.20f * fade, 36);
                drawCircleOutline(cx, cy, inRadPx, r, g, b, 0.52f * fade, 1.6f, 36, false);

                // Highlight Strongpoint (bastion) or Weak Point (seam) on the inner Space perimeter
                if (div.isStrongpoint) {
                    drawCircleOutline(cx, cy, inRadPx * 0.75f, 0.45f, 0.98f, 0.88f, 0.55f * fade, 1.8f, 24, false);
                } else if (div.isWeakPoint) {
                    drawCircleOutline(cx, cy, inRadPx * 0.82f, 1.00f, 0.75f, 0.22f, 0.60f * fade, 1.8f, 24, true);
                }
            }

            // Render airborne Strike Craft squadron Space Zones of Control when deployed away from the carrier centroid
            for (ShipAPI s : div.getAliveMembers()) {
                List<com.fs.starfarer.api.combat.FighterWingAPI> wings = s.getAllWings();
                if (wings == null) continue;
                for (com.fs.starfarer.api.combat.FighterWingAPI wing : wings) {
                    ShipAPI wLead = resolveAliveWingLeader(wing);
                    if (wLead == null || distance(wLead.getLocation(), cWorld) <= 320f) continue;
                    if (isEnemy && playerFog != null && !playerFog.isVisible(wLead)) continue;
                    float wx = offsetX + wLead.getLocation().x * scale;
                    float wy = offsetY + wLead.getLocation().y * scale;
                    float wInPx = Math.max(9f, 260f * scale);
                    float wDomPx = Math.max(wInPx + 5f, 560f * scale);
                    if (!isCircleVisibleOnScreen(wx, wy, wDomPx + 8f, screenW, screenH)) continue;
                    drawIncreasinglyTransparentCircle(wx, wy, wDomPx, r, g, b, 0.13f * fade, 24);
                    drawCircleOutline(wx, wy, wDomPx, r, g, b, 0.32f * fade, 1.15f, 24, true);
                    drawCircleOutline(wx, wy, wInPx, r, g, b, 0.44f * fade, 1.25f, 24, false);
                }
            }
        }
    }

    private static void renderGroupConvergenceLayer(
            CombatEngineAPI engine,
            BattleGroupState group,
            boolean isEnemy,
            boolean isAlly,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        if (group == null || group.divisions.isEmpty()) return;
        float screenW = Global.getSettings() != null ? Global.getSettings().getScreenWidth() : 1920f;
        float screenH = Global.getSettings() != null ? Global.getSettings().getScreenHeight() : 1080f;
        FogOfWarAPI playerFog = engine.getFogOfWar(0);

        for (DivisionState div : group.divisions) {
            if (!div.isCombatEffective()) continue;
            if (isEnemy && !OtcAI.isDivisionVisibleToSide(engine, 0, div)) continue;
            Vector2f cWorld = isEnemy ? OtcAI.getVisibleCentroidForSide(engine, 0, div) : div.centroid;
            if (isEnemy && playerFog != null && !playerFog.isVisible(cWorld)) continue;

            float cx = offsetX + cWorld.x * scale;
            float cy = offsetY + cWorld.y * scale;
            // Convergence radius synthesizes Time, Space, and Firepower into a unified phase envelope
            float convWorldRad = 0.38f * div.effectiveTimeRadius
                    + 0.38f * div.spaceDominatedRadius
                    + 0.24f * Math.max(500f, div.avgEngageRange);
            float radPx = Math.max(24f, convWorldRad * scale);

            float r, g, b;
            if (isEnemy) {
                r = 1.00f; g = 0.34f; b = 0.58f;
            } else if (isAlly) {
                r = 0.68f; g = 0.96f; b = 0.52f;
            } else {
                // Tri-Tachyon / Alpha Core electric cyan-azure
                r = 0.38f; g = 0.92f; b = 1.00f;
            }

            float activeBoost = (group.useConvergence || div.useConvergence) ? 1.18f : 0.85f;
            int divSegs = DivisionAISettings.isTsfcHighGraphics() ? 48 : 36;
            if (isCircleVisibleOnScreen(cx, cy, radPx + 16f, screenW, screenH)) {
                drawIncreasinglyTransparentCircle(cx, cy, radPx, r, g, b, 0.18f * fade * activeBoost, divSegs);

                // Render the iconic Convergence battle glyph: an inscribed triangle inside an inscribed square inside a circle
                drawCircleOutline(cx, cy, radPx, r, g, b, 0.52f * fade * activeBoost, 1.6f, divSegs, false);
                float sqHalf = radPx * 0.70710678f; // inscribed square half-side = R / sqrt(2)
                GL11.glLineWidth(1.35f);
                GL11.glColor4f(r, g, b, 0.42f * fade * activeBoost);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(cx - sqHalf, cy - sqHalf);
                GL11.glVertex2f(cx + sqHalf, cy - sqHalf);
                GL11.glVertex2f(cx + sqHalf, cy + sqHalf);
                GL11.glVertex2f(cx - sqHalf, cy + sqHalf);
                GL11.glEnd();

                // Inscribed equilateral triangle inside the square
                GL11.glLineWidth(1.45f);
                GL11.glColor4f(r, g, b, 0.56f * fade * activeBoost);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(cx, cy + sqHalf * 0.94f);
                GL11.glVertex2f(cx - sqHalf * 0.88f, cy - sqHalf * 0.78f);
                GL11.glVertex2f(cx + sqHalf * 0.88f, cy - sqHalf * 0.78f);
                GL11.glEnd();
            }

            // Phase-locked Convergence vector to focal point when targeting an enemy division
            if (!isEnemy && div.convergenceScore > 1f
                    && (div.convergenceFocalPoint.x != 0f || div.convergenceFocalPoint.y != 0f)) {
                float fx = offsetX + div.convergenceFocalPoint.x * scale;
                float fy = offsetY + div.convergenceFocalPoint.y * scale;
                GL11.glLineWidth(1.5f);
                GL11.glColor4f(r, g, b, 0.48f * fade * activeBoost);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex2f(cx, cy);
                GL11.glVertex2f(fx, fy);
                GL11.glEnd();
            }

            // Render compact Strike Craft Convergence glyphs on airborne fighter squadrons
            for (ShipAPI s : div.getAliveMembers()) {
                List<com.fs.starfarer.api.combat.FighterWingAPI> wings = s.getAllWings();
                if (wings == null) continue;
                for (com.fs.starfarer.api.combat.FighterWingAPI wing : wings) {
                    ShipAPI wLead = resolveAliveWingLeader(wing);
                    if (wLead == null || distance(wLead.getLocation(), cWorld) <= 320f) continue;
                    if (isEnemy && playerFog != null && !playerFog.isVisible(wLead)) continue;
                    float wx = offsetX + wLead.getLocation().x * scale;
                    float wy = offsetY + wLead.getLocation().y * scale;
                    float wRadPx = Math.max(12f, 380f * scale);
                    if (!isCircleVisibleOnScreen(wx, wy, wRadPx + 8f, screenW, screenH)) continue;
                    float wSq = wRadPx * 0.70710678f;
                    drawCircleOutline(wx, wy, wRadPx, r, g, b, 0.42f * fade * activeBoost, 1.2f, 28, false);
                    GL11.glLineWidth(1.1f);
                    GL11.glColor4f(r, g, b, 0.36f * fade * activeBoost);
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2f(wx - wSq, wy - wSq);
                    GL11.glVertex2f(wx + wSq, wy - wSq);
                    GL11.glVertex2f(wx + wSq, wy + wSq);
                    GL11.glVertex2f(wx - wSq, wy + wSq);
                    GL11.glEnd();
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2f(wx, wy + wSq * 0.94f);
                    GL11.glVertex2f(wx - wSq * 0.88f, wy - wSq * 0.78f);
                    GL11.glVertex2f(wx + wSq * 0.88f, wy - wSq * 0.78f);
                    GL11.glEnd();
                }
            }
        }
    }

    private static void drawIncreasinglyTransparentCircle(
            float cx, float cy, float radiusPx, float r, float g, float b, float centerAlpha, int segs) {
        if (radiusPx <= 1f || centerAlpha <= 0.002f) return;
        float midRad = radiusPx * 0.55f;
        float midAlpha = centerAlpha * 0.48f;
        boolean highGfx = DivisionAISettings.isTsfcHighGraphics();
        float[] cosTab;
        float[] sinTab;
        if (segs <= 28) {
            cosTab = highGfx ? UNIT_COS_36 : UNIT_COS_24;
            sinTab = highGfx ? UNIT_SIN_36 : UNIT_SIN_24;
        } else {
            cosTab = highGfx ? UNIT_COS_48 : UNIT_COS_36;
            sinTab = highGfx ? UNIT_SIN_48 : UNIT_SIN_36;
        }
        int n = cosTab.length;

        // Inner core to mid band
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(r, g, b, centerAlpha);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(r, g, b, midAlpha);
        for (int i = 0; i <= n; i++) {
            int idx = (i == n) ? 0 : i;
            GL11.glVertex2f(cx + cosTab[idx] * midRad, cy + sinTab[idx] * midRad);
        }
        GL11.glEnd();

        // Mid band fading smoothly to 0.0 alpha at outer perimeter
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        for (int i = 0; i <= n; i++) {
            int idx = (i == n) ? 0 : i;
            float cos = cosTab[idx];
            float sin = sinTab[idx];
            GL11.glColor4f(r, g, b, midAlpha);
            GL11.glVertex2f(cx + cos * midRad, cy + sin * midRad);
            GL11.glColor4f(r, g, b, 0.0f);
            GL11.glVertex2f(cx + cos * radiusPx, cy + sin * radiusPx);
        }
        GL11.glEnd();
    }

    private static void drawCircleOutline(
            float cx, float cy, float radiusPx, float r, float g, float b, float alpha,
            float lineWidth, int segs, boolean dashed) {
        if (radiusPx <= 1f || alpha <= 0.002f) return;
        boolean highGfx = DivisionAISettings.isTsfcHighGraphics();
        float[] cosTab;
        float[] sinTab;
        if (segs <= 28) {
            cosTab = highGfx ? UNIT_COS_36 : UNIT_COS_24;
            sinTab = highGfx ? UNIT_SIN_36 : UNIT_SIN_24;
        } else {
            cosTab = highGfx ? UNIT_COS_48 : UNIT_COS_36;
            sinTab = highGfx ? UNIT_SIN_48 : UNIT_SIN_36;
        }
        int n = cosTab.length;

        GL11.glLineWidth(lineWidth);
        GL11.glColor4f(r, g, b, alpha);
        if (dashed) {
            GL11.glBegin(GL11.GL_LINES);
            for (int i = 0; i < n; i += 2) {
                int i1 = (i + 1) % n;
                GL11.glVertex2f(cx + cosTab[i] * radiusPx, cy + sinTab[i] * radiusPx);
                GL11.glVertex2f(cx + cosTab[i1] * radiusPx, cy + sinTab[i1] * radiusPx);
            }
            GL11.glEnd();
        } else {
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < n; i++) {
                GL11.glVertex2f(cx + cosTab[i] * radiusPx, cy + sinTab[i] * radiusPx);
            }
            GL11.glEnd();
        }
    }

    private static void renderSingleShipFirepowerRose(
            ShipFirepowerRose rose,
            ShipAPI ship,
            boolean isEnemy,
            boolean isAlly,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        float cx = offsetX + ship.getLocation().x * scale;
        float cy = offsetY + ship.getLocation().y * scale;
        float baseShipRadPx = Math.max(4f, ship.getCollisionRadius() * scale * 0.55f);

        // Color palette by side & horizon:
        // Friendly: Cool Tactical Cyan/Teal (Sustained), Bright Sky-Cyan (Short DoT), Electric Gold-White (Alpha)
        // Enemy: Warm Crimson-Red (Sustained), Amber-Orange (Short DoT), Hot Magenta-Gold (Alpha)
        float sustR, sustG, sustB;
        float shortR, shortG, shortB;
        float alphaR, alphaG, alphaB;

        if (isEnemy) {
            sustR = 1.00f; sustG = 0.24f; sustB = 0.18f;
            shortR = 1.00f; shortG = 0.52f; shortB = 0.14f;
            alphaR = 1.00f; alphaG = 0.82f; alphaB = 0.25f;
        } else if (isAlly) {
            sustR = 0.65f; sustG = 0.88f; sustB = 0.28f;
            shortR = 0.85f; shortG = 0.96f; shortB = 0.42f;
            alphaR = 1.00f; alphaG = 0.92f; alphaB = 0.55f;
        } else {
            sustR = 0.12f; sustG = 0.72f; sustB = 0.96f;
            shortR = 0.28f; shortG = 0.92f; shortB = 1.00f;
            alphaR = 0.75f; alphaG = 0.98f; alphaB = 1.00f;
        }

        float dmgWeight = clampVal(rose.potentialDamageWeight, 0.38f, 1.32f);
        float readinessFade = Math.max(0.22f, rose.liveReadinessMult) * fade * (0.65f + 0.35f * dmgWeight);
        // Blend relative sector intensity with absolute potential-damage reference benchmarks so both:
        //  (1) a ship's strongest firing arcs stand out against its blind spots, AND
        //  (2) heavy-damage ships / torpedo boats project visibly fuller, richer firepower envelopes than light plinkers!
        float normSust = Math.max(110f, rose.peakSectorSustained * 0.65f + 340f * 0.35f);
        float normShort = Math.max(140f, rose.peakSectorShortDot * 0.65f + 460f * 0.35f);
        float normAlpha = Math.max(220f, rose.peakSectorAlpha * 0.65f + 780f * 0.35f);

        float[] sustX = SCRATCH_SUST_X;
        float[] sustY = SCRATCH_SUST_Y;
        float[] shortX = SCRATCH_SHORT_X;
        float[] shortY = SCRATCH_SHORT_Y;
        float[] alphaX = SCRATCH_ALPHA_X;
        float[] alphaY = SCRATCH_ALPHA_Y;
        float[] pdX = SCRATCH_PD_X;
        float[] pdY = SCRATCH_PD_Y;
        boolean hasPd = false;

        boolean highGfx = DivisionAISettings.isTsfcHighGraphics();
        int contourSteps = highGfx ? HIGH_CONTOUR_STEPS : LOW_CONTOUR_STEPS;
        float[] relCosTab = highGfx ? UNIT_COS_72 : UNIT_COS_48;
        float[] relSinTab = highGfx ? UNIT_SIN_72 : UNIT_SIN_48;

        double facingRad = Math.toRadians(rose.smoothedFacingDeg);
        float cosF = (float) Math.cos(facingRad);
        float sinF = (float) Math.sin(facingRad);

        for (int i = 0; i < contourSteps; i++) {
            float relCos = relCosTab[i];
            float relSin = relSinTab[i];
            float cos = cosF * relCos - sinF * relSin;
            float sin = sinF * relCos + cosF * relSin;

            float genReachPx, sustReachPx, shortReachPx, alphaReachPx;
            float sustVal, shortVal, alphaVal, pdReachPx, pdVal;
            if (highGfx) {
                float relDeg = i * (360f / HIGH_CONTOUR_STEPS);
                genReachPx = ShipFirepowerRose.sampleArrayAtRelAngle(rose.generalRange, relDeg) * scale;
                sustReachPx = ShipFirepowerRose.sampleArrayAtRelAngle(rose.sustainedRange, relDeg) * scale;
                shortReachPx = ShipFirepowerRose.sampleArrayAtRelAngle(rose.shortRange, relDeg) * scale;
                alphaReachPx = ShipFirepowerRose.sampleArrayAtRelAngle(rose.alphaRange, relDeg) * scale;
                sustVal = ShipFirepowerRose.sampleArrayAtRelAngle(rose.sustainedDot, relDeg);
                shortVal = ShipFirepowerRose.sampleArrayAtRelAngle(rose.shortDot, relDeg);
                alphaVal = ShipFirepowerRose.sampleArrayAtRelAngle(rose.alpha, relDeg);
                pdReachPx = ShipFirepowerRose.sampleArrayAtRelAngle(rose.pdRange, relDeg) * scale;
                pdVal = ShipFirepowerRose.sampleArrayAtRelAngle(rose.pdScore, relDeg);
            } else {
                int s0 = i >> 1;
                if ((i & 1) == 0) {
                    genReachPx = rose.generalRange[s0] * scale;
                    sustReachPx = rose.sustainedRange[s0] * scale;
                    shortReachPx = rose.shortRange[s0] * scale;
                    alphaReachPx = rose.alphaRange[s0] * scale;
                    sustVal = rose.sustainedDot[s0];
                    shortVal = rose.shortDot[s0];
                    alphaVal = rose.alpha[s0];
                    pdReachPx = rose.pdRange[s0] * scale;
                    pdVal = rose.pdScore[s0];
                } else {
                    int s1 = (s0 + 1) % NUM_SECTORS;
                    genReachPx = 0.5f * (rose.generalRange[s0] + rose.generalRange[s1]) * scale;
                    sustReachPx = 0.5f * (rose.sustainedRange[s0] + rose.sustainedRange[s1]) * scale;
                    shortReachPx = 0.5f * (rose.shortRange[s0] + rose.shortRange[s1]) * scale;
                    alphaReachPx = 0.5f * (rose.alphaRange[s0] + rose.alphaRange[s1]) * scale;
                    sustVal = 0.5f * (rose.sustainedDot[s0] + rose.sustainedDot[s1]);
                    shortVal = 0.5f * (rose.shortDot[s0] + rose.shortDot[s1]);
                    alphaVal = 0.5f * (rose.alpha[s0] + rose.alpha[s1]);
                    pdReachPx = 0.5f * (rose.pdRange[s0] + rose.pdRange[s1]) * scale;
                    pdVal = 0.5f * (rose.pdScore[s0] + rose.pdScore[s1]);
                }
            }
            if (sustReachPx <= 1f) sustReachPx = genReachPx;
            if (shortReachPx <= 1f) shortReachPx = genReachPx;
            if (alphaReachPx <= 1f) alphaReachPx = shortReachPx;

            float sustIntensity = Math.min(1.18f, sustVal / normSust);
            float shortIntensity = Math.min(1.18f, shortVal / normShort);
            float alphaIntensity = Math.min(1.18f, alphaVal / normAlpha);

            // Outer Plane: Sustained DoT reach weighted by potential sustained damage in this sector
            float effSustReachPx = sustReachPx * 0.75f + genReachPx * 0.25f;
            float rSust = (effSustReachPx > 2f && sustIntensity > 0.03f)
                    ? Math.max(baseShipRadPx, effSustReachPx * (0.44f + 0.56f * (float) Math.sqrt(Math.min(1f, sustIntensity))))
                    : baseShipRadPx * 0.45f;
            // Mid Plane: Short DoT (4-6s burst exchange envelope) weighted by short-window potential damage & range
            float rShort = (shortReachPx > 2f && shortIntensity > 0.04f)
                    ? Math.max(baseShipRadPx * 0.8f, Math.min(rSust * 1.06f, shortReachPx * (0.42f + 0.52f * (float) Math.sqrt(Math.min(1f, shortIntensity)))))
                    : baseShipRadPx * 0.35f;
            // Inner / Salvo Core Plane: Alpha Strike (0-2s burst/torpedo salvo cone) weighted by alpha potential damage & range
            float rAlpha = (alphaReachPx > 2f && alphaIntensity > 0.05f)
                    ? Math.max(baseShipRadPx * 0.6f, Math.min(Math.max(rSust, alphaReachPx) * 1.04f, alphaReachPx * (0.36f + 0.56f * (float) Math.sqrt(Math.min(1f, alphaIntensity)))))
                    : baseShipRadPx * 0.25f;

            sustX[i] = cx + cos * rSust;
            sustY[i] = cy + sin * rSust;
            shortX[i] = cx + cos * rShort;
            shortY[i] = cy + sin * rShort;
            alphaX[i] = cx + cos * rAlpha;
            alphaY[i] = cy + sin * rAlpha;

            if (pdReachPx > 3f && pdVal > 5f) {
                hasPd = true;
                pdX[i] = cx + cos * Math.max(baseShipRadPx * 1.05f, pdReachPx);
                pdY[i] = cy + sin * Math.max(baseShipRadPx * 1.05f, pdReachPx);
            } else {
                pdX[i] = cx + cos * baseShipRadPx * 0.5f;
                pdY[i] = cy + sin * baseShipRadPx * 0.5f;
            }
        }

        // Plane 1: Sustained DoT Outer Polar Plane
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(sustR, sustG, sustB, 0.14f * readinessFade);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(sustR, sustG, sustB, 0.07f * readinessFade);
        for (int i = 0; i <= contourSteps; i++) {
            int idx = i % contourSteps;
            GL11.glVertex2f(sustX[idx], sustY[idx]);
        }
        GL11.glEnd();

        // Outer Sustained DoT contour line (line weight scaled by potentialDamageWeight)
        GL11.glLineWidth(1.10f + 0.45f * dmgWeight);
        GL11.glColor4f(sustR, sustG, sustB, 0.36f * readinessFade);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < contourSteps; i++) {
            GL11.glVertex2f(sustX[i], sustY[i]);
        }
        GL11.glEnd();

        // Plane 2: Short DoT (4-6s Burst) Mid Polar Plane + Detailed Mid-Horizon Iso-Damage Contour
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(shortR, shortG, shortB, 0.16f * readinessFade);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(shortR, shortG, shortB, 0.09f * readinessFade);
        for (int i = 0; i <= contourSteps; i++) {
            int idx = i % contourSteps;
            GL11.glVertex2f(shortX[idx], shortY[idx]);
        }
        GL11.glEnd();

        GL11.glLineWidth(1.05f + 0.35f * dmgWeight);
        GL11.glColor4f(shortR, shortG, shortB, 0.30f * readinessFade);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < contourSteps; i++) {
            GL11.glVertex2f(shortX[i], shortY[i]);
        }
        GL11.glEnd();

        // Plane 3: Alpha Strike (0-2s Salvo) Hot Inner Core + High-Lethality Salvo Contour
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(alphaR, alphaG, alphaB, 0.21f * readinessFade);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(alphaR, alphaG, alphaB, 0.11f * readinessFade);
        for (int i = 0; i <= contourSteps; i++) {
            int idx = i % contourSteps;
            GL11.glVertex2f(alphaX[idx], alphaY[idx]);
        }
        GL11.glEnd();

        if (rose.peakSectorAlpha >= 140f) {
            GL11.glLineWidth(1.15f + 0.40f * dmgWeight);
            GL11.glColor4f(alphaR, alphaG, alphaB, 0.38f * readinessFade);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < contourSteps; i++) {
                GL11.glVertex2f(alphaX[i], alphaY[i]);
            }
            GL11.glEnd();
        }

        // Plane 4: Point Defense (PD) Dashed Inner Ring (Violet-Gold)
        if (hasPd) {
            GL11.glLineWidth(1.3f);
            GL11.glColor4f(0.92f, 0.78f, 1.00f, 0.42f * readinessFade);
            GL11.glBegin(GL11.GL_LINES);
            for (int i = 0; i < contourSteps; i += 2) {
                int next = (i + 1) % contourSteps;
                GL11.glVertex2f(pdX[i], pdY[i]);
                GL11.glVertex2f(pdX[next], pdY[next]);
            }
            GL11.glEnd();
        }
    }

    private static void renderDivisionCordonIndicators(
            CombatEngineAPI engine,
            BattleGroupState group,
            boolean isEnemy,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        if (group == null || group.divisions.isEmpty()) return;
        FogOfWarAPI playerFog = engine.getFogOfWar(0);

        for (DivisionState div : group.divisions) {
            if (!div.isCombatEffective()) continue;
            if (div.crossfireCordonIndex < 0.35f) continue;
            if (isEnemy && !OtcAI.isDivisionVisibleToSide(engine, 0, div)) continue;

            Vector2f cWorld = isEnemy ? OtcAI.getVisibleCentroidForSide(engine, 0, div) : div.centroid;
            if (isEnemy && playerFog != null && !playerFog.isVisible(cWorld)) continue;

            float cx = offsetX + cWorld.x * scale;
            float cy = offsetY + cWorld.y * scale;
            float ringRadPx = Math.max(22f, Math.min(72f, div.spreadRadius * scale * 0.45f));

            boolean isUCordon = div.crossfireCordonIndex >= 0.60f;
            float r = 1.0f;
            float g = isUCordon ? 0.22f : 0.62f;
            float b = 0.15f;
            float a = Math.min(0.85f, (0.38f + div.crossfireCordonIndex * 0.45f) * fade);

            // Dashed warning ring around division centroid caught in L/U crossfire
            GL11.glLineWidth(2.2f);
            GL11.glColor4f(r, g, b, a);
            GL11.glBegin(GL11.GL_LINES);
            int segs = 24;
            for (int i = 0; i < segs; i += 2) {
                float a0 = (float) (i * 2.0 * Math.PI / segs);
                float a1 = (float) ((i + 1) * 2.0 * Math.PI / segs);
                GL11.glVertex2f(cx + (float) Math.cos(a0) * ringRadPx, cy + (float) Math.sin(a0) * ringRadPx);
                GL11.glVertex2f(cx + (float) Math.cos(a1) * ringRadPx, cy + (float) Math.sin(a1) * ringRadPx);
            }
            GL11.glEnd();
        }
    }

    private static void renderDivisionCordonLabels(
            CombatEngineAPI engine,
            BattleGroupState group,
            boolean isEnemy,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        if (group == null || group.divisions.isEmpty() || Global.getSettings() == null) return;
        for (DivisionState div : group.divisions) {
            if (!div.isCombatEffective() || div.crossfireCordonIndex < 0.35f) continue;
            if (isEnemy && !OtcAI.isDivisionVisibleToSide(engine, 0, div)) continue;

            Vector2f cWorld = isEnemy ? OtcAI.getVisibleCentroidForSide(engine, 0, div) : div.centroid;
            float cx = offsetX + cWorld.x * scale;
            float cy = offsetY + cWorld.y * scale;

            String tag = (div.crossfireCordonIndex >= 0.60f)
                    ? String.format(Locale.ROOT, "U-CORDON CROSSFIRE (%.0f%%)", div.crossfireCordonIndex * 100f)
                    : String.format(Locale.ROOT, "L-CORDON CROSSFIRE (%.0f%%)", div.crossfireCordonIndex * 100f);
            Color col = (div.crossfireCordonIndex >= 0.60f)
                    ? new Color(255, 90, 75, 235)
                    : new Color(255, 185, 70, 230);

            LabelAPI lbl = getCachedLabel("cordon_" + div.id, tag, Fonts.DEFAULT_SMALL, col);
            if (lbl != null) {
                float tw = lbl.computeTextWidth(tag);
                drawLabel(lbl, tag, cx - tw * 0.5f, cy - 28f, fade * 0.92f);
            }
        }
    }

    /**
     * Renders the textless Warroom square-bordered icon buttons on the right side of the Command View Window:
     *   0 (Top):    Time Overlay        (icon_overlay_time.png)
     *   1 (Second): Space Overlay       (icon_overlay_space.png)
     *   2 (Third):  Firepower Overlay   (icon_overlay_firepower.png)
     *   3 (Fourth): Convergence Overlay (icon_overlay_convergence.png — ONLY shown if the player has an Alpha Core
     *               in their fleet, even if the Alpha Core ship is not deployed in play).
     * Also renders the "[URGENT COMMS FROM <AI-controlled ship>]" modal pop-up when active.
     */
    public static void renderToggleButtonAndLegendUI(float fade) {
        CombatEngineAPI engine = null;
        try {
            engine = Global.getCombatEngine();
        } catch (Throwable ignored) {
        }
        renderToggleButtonAndLegendUI(engine, fade);
    }

    public static void renderToggleButtonAndLegendUI(CombatEngineAPI engine, float fade) {
        if (Global.getSettings() == null || fade <= 0.05f) return;

        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        float btnX = getOverlayButtonX(screenW);
        boolean showConvergenceBtn = hasAlphaCoreInPlayerFleet(engine);
        int buttonCount = showConvergenceBtn ? 4 : 3;

        String[] iconPaths = new String[]{
                TIME_ICON_PATH,
                SPACE_ICON_PATH,
                FIREPOWER_ICON_PATH,
                CONVERGENCE_ICON_PATH
        };
        boolean[] activeStates = new boolean[]{
                timeOverlayVisible,
                spaceOverlayVisible,
                overlayVisible,
                convergenceOverlayVisible
        };

        for (int idx = 0; idx < buttonCount; idx++) {
            float btnY = getOverlayButtonY(screenH, idx);
            boolean active = activeStates[idx];
            boolean hovered = (lastMouseX >= btnX - 2f && lastMouseX <= btnX + BTN_WIDTH + 2f
                    && lastMouseY >= btnY - 2f && lastMouseY <= btnY + BTN_HEIGHT + 2f);
            renderSingleWarroomSquareIconButton(btnX, btnY, iconPaths[idx], active, hovered, fade);
        }

        if (pendingCommsAlphaCoreShip != null) {
            renderUrgentAlphaCoreCommsModal(engine, screenW, screenH, fade);
        }
    }

    /**
     * Renders the "[URGENT COMMS FROM <AI-controlled ship>]" pop-up modal window when the player clicks
     * the Convergence button while an Alpha Core-controlled ship is deployed as a subordinate:
     *   - Header:  [URGENT COMMS FROM <AI-controlled ship>]
     *   - Content: "Shall I take command?" where "command" subtly shifts position every 0.5 seconds
     *   - Buttons: "Yes" and "No"
     */
    private static void renderUrgentAlphaCoreCommsModal(
            CombatEngineAPI engine,
            float screenW,
            float screenH,
            float fade) {

        ShipAPI aiShip = pendingCommsAlphaCoreShip;
        if (aiShip == null) return;

        float modalX = Math.round((screenW - COMMS_MODAL_W) * 0.5f);
        float modalY = Math.round((screenH - COMMS_MODAL_H) * 0.5f);
        float yesX = modalX + 86f;
        float noX = modalX + COMMS_MODAL_W - 86f - COMMS_BTN_W;
        float btnY = modalY + COMMS_BTN_Y_OFFSET;
        float spriteCenterX = modalX + COMMS_MODAL_W * 0.5f;
        float spriteCenterY = modalY + COMMS_MODAL_H - 86f;
        float spriteBoxHalf = 36f;

        boolean yesHover = (lastMouseX >= yesX - 2f && lastMouseX <= yesX + COMMS_BTN_W + 2f
                && lastMouseY >= btnY - 2f && lastMouseY <= btnY + COMMS_BTN_H + 2f);
        boolean noHover = (lastMouseX >= noX - 2f && lastMouseX <= noX + COMMS_BTN_W + 2f
                && lastMouseY >= btnY - 2f && lastMouseY <= btnY + COMMS_BTN_H + 2f);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // Subtle full-screen dark scrim so map elements and division text underneath are dimmed behind the top-layer modal
        GL11.glColor4f(0.01f, 0.02f, 0.04f, 0.45f * fade);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0f, 0f);
        GL11.glVertex2f(screenW, 0f);
        GL11.glVertex2f(screenW, screenH);
        GL11.glVertex2f(0f, screenH);
        GL11.glEnd();

        // Outer backdrop shadow
        GL11.glColor4f(0.01f, 0.02f, 0.04f, 0.88f * fade);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(modalX - 8f, modalY - 8f);
        GL11.glVertex2f(modalX + COMMS_MODAL_W + 8f, modalY - 8f);
        GL11.glVertex2f(modalX + COMMS_MODAL_W + 8f, modalY + COMMS_MODAL_H + 8f);
        GL11.glVertex2f(modalX - 8f, modalY + COMMS_MODAL_H + 8f);
        GL11.glEnd();

        // Main opaque dark Warroom modal panel
        GL11.glColor4f(8f / 255f, 14f / 255f, 20f / 255f, 0.99f * fade);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(modalX, modalY);
        GL11.glVertex2f(modalX + COMMS_MODAL_W, modalY);
        GL11.glVertex2f(modalX + COMMS_MODAL_W, modalY + COMMS_MODAL_H);
        GL11.glVertex2f(modalX, modalY + COMMS_MODAL_H);
        GL11.glEnd();

        // Header bar background
        GL11.glColor4f(0.08f, 0.24f, 0.34f, 0.92f * fade);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(modalX + 2f, modalY + COMMS_MODAL_H - 38f);
        GL11.glVertex2f(modalX + COMMS_MODAL_W - 2f, modalY + COMMS_MODAL_H - 38f);
        GL11.glVertex2f(modalX + COMMS_MODAL_W - 2f, modalY + COMMS_MODAL_H - 2f);
        GL11.glVertex2f(modalX + 2f, modalY + COMMS_MODAL_H - 2f);
        GL11.glEnd();

        // Center Ship Icon Backing Box (72x72px centered at spriteCenterX, spriteCenterY)
        GL11.glColor4f(0.04f, 0.08f, 0.13f, 0.94f * fade);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(spriteCenterX - spriteBoxHalf, spriteCenterY - spriteBoxHalf);
        GL11.glVertex2f(spriteCenterX + spriteBoxHalf, spriteCenterY - spriteBoxHalf);
        GL11.glVertex2f(spriteCenterX + spriteBoxHalf, spriteCenterY + spriteBoxHalf);
        GL11.glVertex2f(spriteCenterX - spriteBoxHalf, spriteCenterY + spriteBoxHalf);
        GL11.glEnd();

        GL11.glLineWidth(1.3f);
        GL11.glColor4f(0.32f, 0.78f, 0.96f, 0.85f * fade);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(spriteCenterX - spriteBoxHalf, spriteCenterY - spriteBoxHalf);
        GL11.glVertex2f(spriteCenterX + spriteBoxHalf, spriteCenterY - spriteBoxHalf);
        GL11.glVertex2f(spriteCenterX + spriteBoxHalf, spriteCenterY + spriteBoxHalf);
        GL11.glVertex2f(spriteCenterX - spriteBoxHalf, spriteCenterY + spriteBoxHalf);
        GL11.glEnd();

        // Outer cyan Warroom border frame
        GL11.glLineWidth(1.8f);
        GL11.glColor4f(110f / 255f, 210f / 255f, 250f / 255f, 0.96f * fade);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(modalX, modalY);
        GL11.glVertex2f(modalX + COMMS_MODAL_W, modalY);
        GL11.glVertex2f(modalX + COMMS_MODAL_W, modalY + COMMS_MODAL_H);
        GL11.glVertex2f(modalX, modalY + COMMS_MODAL_H);
        GL11.glEnd();

        // Render "Yes" and "No" button boxes
        renderModalChoiceButtonBox(yesX, btnY, COMMS_BTN_W, COMMS_BTN_H, yesHover, fade);
        renderModalChoiceButtonBox(noX, btnY, COMMS_BTN_W, COMMS_BTN_H, noHover, fade);

        GL11.glPopMatrix();
        GL11.glPopAttrib();

        // 1. Header Text: [URGENT COMMS FROM <AI-controlled ship>]
        String headerText = "[URGENT COMMS FROM " + formatAiShipName(aiShip) + "]";
        Color headerColor = new Color(170, 222, 255, 255);
        LabelAPI headerLbl = getCachedLabel("urgent_comms_hdr", headerText, Fonts.ORBITRON_12, headerColor);
        if (headerLbl != null) {
            float tw = headerLbl.computeTextWidth(headerText);
            float th = headerLbl.computeTextHeight(headerText);
            drawLabel(
                    headerLbl,
                    headerText,
                    modalX + (COMMS_MODAL_W - tw) * 0.5f,
                    modalY + COMMS_MODAL_H - 20f - th * 0.5f,
                    fade);
        }

        // 2. Centered Ship Hull Sprite + Ship Name ("ISS Something") directly below it (Item A)
        renderCommsShipSprite(aiShip, spriteCenterX, spriteCenterY, 64f, 64f, fade);

        String shipCallsign = formatAiShipCallsignOnly(aiShip);
        Color shipNameColor = new Color(255, 222, 115, 255);
        LabelAPI shipNameLbl = getCachedLabel("urgent_comms_shipname", shipCallsign, Fonts.ORBITRON_12, shipNameColor);
        if (shipNameLbl != null) {
            float tw = shipNameLbl.computeTextWidth(shipCallsign);
            drawLabel(
                    shipNameLbl,
                    shipCallsign,
                    modalX + (COMMS_MODAL_W - tw) * 0.5f,
                    modalY + 114f,
                    fade);
        }

        // 3. Animated Body Text: supports all 4 Urgent Comms phrases ("Shall I take command?" first, then
        //    "Please allow me.", "I am at your service.", "I can ANNIHILATE them faster.", cycling on >4 Alpha Core ships)
        //    with the designated keyword jittering every 0.5 seconds!
        float realTimeSec = (engine != null && !engine.isPaused() && engine.getTotalElapsedTime(true) > 0f)
                ? engine.getTotalElapsedTime(true)
                : (System.currentTimeMillis() % 1000000L) / 1000.0f;
        Vector2f shift = getCommandWordShiftOffset(realTimeSec);

        String[] parts = getUrgentCommsPhraseParts(activeCommsPhraseIndex);
        String prefixText = parts[0];
        String commandText = parts[1];
        String suffixText = parts[2];
        Color bodyColor = new Color(215, 246, 255, 255);
        Color commandColor = new Color(170, 222, 255, 255);

        LabelAPI prefixLbl = prefixText.isEmpty()
                ? null
                : getCachedLabel("urgent_comms_prefix_" + activeCommsPhraseIndex, prefixText, Fonts.ORBITRON_16, bodyColor);
        LabelAPI cmdLbl = getCachedLabel("urgent_comms_cmd_" + activeCommsPhraseIndex, commandText, Fonts.ORBITRON_16, commandColor);
        LabelAPI suffixLbl = suffixText.isEmpty()
                ? null
                : getCachedLabel("urgent_comms_suffix_" + activeCommsPhraseIndex, suffixText, Fonts.ORBITRON_16, bodyColor);

        if (cmdLbl != null) {
            float wPrefix = (prefixLbl != null) ? prefixLbl.computeTextWidth(prefixText) : 0f;
            float wCmd = cmdLbl.computeTextWidth(commandText);
            float wSuffix = (suffixLbl != null) ? suffixLbl.computeTextWidth(suffixText) : 0f;
            float totalW = wPrefix + wCmd + wSuffix;
            float baseX = modalX + (COMMS_MODAL_W - totalW) * 0.5f;
            float baseY = modalY + 76f;

            if (prefixLbl != null) {
                drawLabel(prefixLbl, prefixText, baseX, baseY, fade);
            }
            drawLabel(cmdLbl, commandText, baseX + wPrefix + shift.x, baseY + shift.y, fade);
            if (suffixLbl != null) {
                drawLabel(suffixLbl, suffixText, baseX + wPrefix + wCmd, baseY, fade);
            }
        }

        // 4. "Yes" and "No" button labels
        Color btnTextColor = new Color(170, 222, 255, 255);
        Color btnHoverColor = new Color(225, 252, 255, 255);
        LabelAPI yesLbl = getCachedLabel("urgent_comms_yes", "Yes", Fonts.ORBITRON_12, yesHover ? btnHoverColor : btnTextColor);
        if (yesLbl != null) {
            float tw = yesLbl.computeTextWidth("Yes");
            float th = yesLbl.computeTextHeight("Yes");
            drawLabel(yesLbl, "Yes", yesX + (COMMS_BTN_W - tw) * 0.5f, btnY + (COMMS_BTN_H - th) * 0.5f, fade);
        }
        LabelAPI noLbl = getCachedLabel("urgent_comms_no", "No", Fonts.ORBITRON_12, noHover ? btnHoverColor : btnTextColor);
        if (noLbl != null) {
            float tw = noLbl.computeTextWidth("No");
            float th = noLbl.computeTextHeight("No");
            drawLabel(noLbl, "No", noX + (COMMS_BTN_W - tw) * 0.5f, btnY + (COMMS_BTN_H - th) * 0.5f, fade);
        }
    }

    private static void renderCommsShipSprite(
            ShipAPI ship,
            float centerX,
            float centerY,
            float maxW,
            float maxH,
            float alphaMult) {
        if (ship == null) return;
        boolean rendered = false;
        try {
            String spriteName = (ship.getHullSpec() != null) ? ship.getHullSpec().getSpriteName() : null;
            if ((spriteName == null || spriteName.isEmpty()) && ship.getFleetMember() != null && ship.getFleetMember().getHullSpec() != null) {
                spriteName = ship.getFleetMember().getHullSpec().getSpriteName();
            }
            if (spriteName != null && !spriteName.isEmpty() && Global.getSettings() != null) {
                SpriteAPI sprite = Global.getSettings().getSprite(spriteName);
                if (sprite != null) {
                    float origW = sprite.getWidth();
                    float origH = sprite.getHeight();
                    if (origW > 1f && origH > 1f) {
                        float scale = Math.min(maxW / origW, maxH / origH);
                        float drawW = origW * scale;
                        float drawH = origH * scale;
                        float prevAngle = sprite.getAngle();
                        float prevAlpha = sprite.getAlphaMult();
                        Color prevColor = sprite.getColor();

                        sprite.setSize(drawW, drawH);
                        sprite.setAngle(0f);
                        sprite.setColor(Color.WHITE);
                        sprite.setAlphaMult(alphaMult);
                        sprite.renderAtCenter(centerX, centerY);

                        sprite.setSize(origW, origH);
                        sprite.setAngle(prevAngle);
                        if (prevColor != null) sprite.setColor(prevColor);
                        sprite.setAlphaMult(prevAlpha);
                        rendered = true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (!rendered) {
            // Clean vector ship chevron fallback when running headless or if sprite path is unavailable
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(0.55f, 0.90f, 1.00f, 0.92f * alphaMult);
            GL11.glBegin(GL11.GL_TRIANGLES);
            GL11.glVertex2f(centerX, centerY + 22f);
            GL11.glVertex2f(centerX - 16f, centerY - 18f);
            GL11.glVertex2f(centerX + 16f, centerY - 18f);
            GL11.glEnd();
            GL11.glPopAttrib();
        }
    }

    public static String formatAiShipCallsignOnly(ShipAPI ship) {
        if (ship == null) return "UNKNOWN VESSEL";
        String name = ship.getName();
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        if (ship.getHullSpec() != null && ship.getHullSpec().getHullName() != null) {
            return ship.getHullSpec().getHullName().trim();
        }
        return "AI Vessel";
    }

    private static void renderModalChoiceButtonBox(
            float x, float y, float w, float h, boolean hovered, float fade) {
        if (hovered) {
            GL11.glColor4f(0.10f, 0.28f, 0.38f, 0.96f * fade);
        } else {
            GL11.glColor4f(14f / 255f, 22f / 255f, 30f / 255f, 0.96f * fade);
        }
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();

        GL11.glLineWidth(1.4f);
        if (hovered) {
            GL11.glColor4f(0.45f, 0.92f, 1.00f, 0.98f * fade);
        } else {
            GL11.glColor4f(54f / 255f, 115f / 255f, 142f / 255f, 0.95f * fade);
        }
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    private static void renderSingleWarroomSquareIconButton(
            float x,
            float y,
            String iconPath,
            boolean active,
            boolean hovered,
            float fade) {

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // 1. Subtle right-edge horizontal shadow/glow bar behind the bracket (matches Full Assault / Full Retreat)
        if (active || hovered) {
            GL11.glColor4f(0.18f, 0.52f, 0.68f, (active ? 0.36f : 0.22f) * fade);
        } else {
            GL11.glColor4f(0.08f, 0.14f, 0.19f, 0.45f * fade);
        }
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x - 2f, y - 2f);
        GL11.glVertex2f(x + BTN_WIDTH + 20f, y - 2f);
        GL11.glVertex2f(x + BTN_WIDTH + 20f, y + BTN_SQUARE_H + 2f);
        GL11.glVertex2f(x - 2f, y + BTN_SQUARE_H + 2f);
        GL11.glEnd();

        // 2. Outer Square Icon Frame (36x34px at x..x+36, y..y+34)
        // Exact vanilla Warroom border color: RGB(54, 115, 142) when OFF; bright cyan when hovered/active
        float br, bg, bb, ba;
        if (active) {
            br = 0.42f; bg = 0.90f; bb = 1.00f; ba = 0.98f * fade;
        } else if (hovered) {
            br = 0.35f; bg = 0.72f; bb = 0.88f; ba = 0.96f * fade;
        } else {
            br = 54f / 255f; bg = 115f / 255f; bb = 142f / 255f; ba = 0.95f * fade;
        }

        GL11.glColor4f(br, bg, bb, ba);
        GL11.glBegin(GL11.GL_QUADS);
        // Main 36x34 square frame with 1px chamfer on right corners (matching scanline y=149..182, x=1854..1890)
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + BTN_SQUARE_W - 1f, y);
        GL11.glVertex2f(x + BTN_SQUARE_W - 1f, y + BTN_SQUARE_H);
        GL11.glVertex2f(x, y + BTN_SQUARE_H);
        // Rightmost 1px vertical edge inset by 1px top & bottom
        GL11.glVertex2f(x + BTN_SQUARE_W - 1f, y + 1f);
        GL11.glVertex2f(x + BTN_SQUARE_W, y + 1f);
        GL11.glVertex2f(x + BTN_SQUARE_W, y + BTN_SQUARE_H - 1f);
        GL11.glVertex2f(x + BTN_SQUARE_W - 1f, y + BTN_SQUARE_H - 1f);
        GL11.glEnd();

        // 3. Inner 32x32 Dark Icon Cavity (x+2..x+34, y+1..y+33)
        // Left border is 2px, top/bottom borders are 1px, right border is 2px
        if (active) {
            GL11.glColor4f(0.06f, 0.18f, 0.25f, 0.96f * fade);
        } else if (hovered) {
            GL11.glColor4f(0.05f, 0.11f, 0.15f, 0.96f * fade);
        } else {
            GL11.glColor4f(10f / 255f, 14f / 255f, 17f / 255f, 0.96f * fade);
        }
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x + 2f, y + 1f);
        GL11.glVertex2f(x + 34f, y + 1f);
        GL11.glVertex2f(x + 34f, y + 33f);
        GL11.glVertex2f(x + 2f, y + 33f);
        GL11.glEnd();

        // Subtle inner active rim highlight inside the 32x32 cavity when ON
        if (active) {
            GL11.glLineWidth(1.0f);
            GL11.glColor4f(0.45f, 0.95f, 1.00f, 0.55f * fade);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(x + 2.5f, y + 1.5f);
            GL11.glVertex2f(x + 33.5f, y + 1.5f);
            GL11.glVertex2f(x + 33.5f, y + 32.5f);
            GL11.glVertex2f(x + 2.5f, y + 32.5f);
            GL11.glEnd();
        }

        // 4. Right-Side Warroom Structural C-Bracket (matching Full Assault / Full Retreat x=1865..1900, y=147..184)
        // Top lip (y + 35..36, x + 11..44), Bottom lip (y - 2..-1, x + 11..44), Right vertical bar (x + 40..46, y - 1..35)
        GL11.glColor4f(br, bg, bb, ba);
        GL11.glBegin(GL11.GL_QUADS);
        // Top horizontal bracket lip (1px gap above square top border)
        GL11.glVertex2f(x + 11f, y + BTN_SQUARE_H + 1f);
        GL11.glVertex2f(x + 44f, y + BTN_SQUARE_H + 1f);
        GL11.glVertex2f(x + 44f, y + BTN_SQUARE_H + 2f);
        GL11.glVertex2f(x + 11f, y + BTN_SQUARE_H + 2f);
        // Bottom horizontal bracket lip (1px gap below square bottom border)
        GL11.glVertex2f(x + 11f, y - 2f);
        GL11.glVertex2f(x + 44f, y - 2f);
        GL11.glVertex2f(x + 44f, y - 1f);
        GL11.glVertex2f(x + 11f, y - 1f);
        // Right inner shoulder connecting top/bottom lips at x + 38..40
        GL11.glVertex2f(x + 38f, y + BTN_SQUARE_H);
        GL11.glVertex2f(x + 45f, y + BTN_SQUARE_H);
        GL11.glVertex2f(x + 45f, y + BTN_SQUARE_H + 1f);
        GL11.glVertex2f(x + 38f, y + BTN_SQUARE_H + 1f);

        GL11.glVertex2f(x + 38f, y - 1f);
        GL11.glVertex2f(x + 45f, y - 1f);
        GL11.glVertex2f(x + 45f, y);
        GL11.glVertex2f(x + 38f, y);
        // Main vertical right bracket bar (x + 40..45, y..y+34, 2px dark gap from square right edge x+36..38)
        GL11.glVertex2f(x + 40f, y);
        GL11.glVertex2f(x + 45f, y);
        GL11.glVertex2f(x + 45f, y + BTN_SQUARE_H);
        GL11.glVertex2f(x + 40f, y + BTN_SQUARE_H);
        GL11.glEnd();

        // Outer 1px bevel column on the right edge of the bracket (RGB 46, 93, 115)
        GL11.glColor4f(br * 0.82f, bg * 0.82f, bb * 0.82f, ba);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x + 45f, y);
        GL11.glVertex2f(x + 46f, y);
        GL11.glVertex2f(x + 46f, y + BTN_SQUARE_H);
        GL11.glVertex2f(x + 45f, y + BTN_SQUARE_H);
        GL11.glEnd();

        GL11.glPopMatrix();
        GL11.glPopAttrib();

        // 5. Render the pure graphical Warroom icon centered inside the 32x32 square cavity (NO text)
        try {
            SpriteAPI iconSprite = Global.getSettings().getSprite(iconPath);
            if (iconSprite != null) {
                iconSprite.setSize(BTN_ICON_SIZE, BTN_ICON_SIZE);
                if (active) {
                    iconSprite.setColor(new Color(225, 252, 255, 255));
                } else if (hovered) {
                    iconSprite.setColor(new Color(200, 238, 255, 255));
                } else {
                    // Matches exact vanilla Full Assault / Full Retreat icon tint RGB(170, 222, 255, 255)
                    iconSprite.setColor(new Color(170, 222, 255, 255));
                }
                iconSprite.setAlphaMult(fade);
                iconSprite.renderAtCenter(x + 18f, y + 17f);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void drawLabel(LabelAPI lbl, String text, float x, float y, float alpha) {
        if (lbl == null) return;
        float tw = lbl.computeTextWidth(text);
        float th = lbl.computeTextHeight(text);
        lbl.getPosition().setSize(tw + 10f, th + 4f);
        lbl.getPosition().setLocation(x, y);
        lbl.render(alpha);
    }

    private static LabelAPI getCachedLabel(String key, String text, String font, Color color) {
        try {
            LabelAPI lbl = uiLabelCache.get(key);
            if (lbl == null) {
                lbl = Global.getSettings().createLabel(text, font);
                uiLabelCache.put(key, lbl);
            } else {
                lbl.setText(text);
            }
            lbl.setColor(color);
            return lbl;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // =========================================================================
    // Math & Weapon Helpers
    // =========================================================================

    private static boolean isUsableCombatWeapon(WeaponAPI w) {
        if (w == null || w.isDecorative()) return false;
        WeaponType type = w.getType();
        return type != WeaponType.DECORATIVE
                && type != WeaponType.LAUNCH_BAY
                && type != WeaponType.SYSTEM
                && type != WeaponType.STATION_MODULE;
    }

    private static float weaponSizeWeight(WeaponSize size) {
        if (size == null) return 1f;
        switch (size) {
            case LARGE:
                return 7f;
            case MEDIUM:
                return 3f;
            case SMALL:
            default:
                return 1f;
        }
    }

    private static float blendAsymmetric(float current, float target, float alphaRise, float alphaFall) {
        float a = (target >= current) ? alphaRise : alphaFall;
        return current + (target - current) * a;
    }

    public static float normalizeAngle360(float deg) {
        float a = deg % 360f;
        if (a < 0f) a += 360f;
        return a;
    }

    public static float shortestAngleDiffDeg(float fromDeg, float toDeg) {
        float diff = (toDeg - fromDeg) % 360f;
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;
        return diff;
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // =========================================================================
    // Voidstrike Instantiation, Multi-Division Merging & Lifecycle Tracking
    // =========================================================================

    private static class WingStrikeCandidate {
        final int side;
        final String wingKey;
        final String divisionId;
        final String targetKey;
        final List<ShipAPI> activeFighters;
        final Vector2f center;

        WingStrikeCandidate(
                int side,
                String wingKey,
                String divisionId,
                String targetKey,
                List<ShipAPI> activeFighters,
                Vector2f center) {
            this.side = side;
            this.wingKey = wingKey;
            this.divisionId = divisionId;
            this.targetKey = targetKey;
            this.activeFighters = activeFighters;
            this.center = center;
        }
    }

    public static List<VoidstrikeRecord> getActiveVoidstrikes() {
        return Collections.unmodifiableList(activeVoidstrikes);
    }

    public static int getNextVoidstrikeNumberForSide(int side) {
        int idx = (side == VOIDSTRIKE_SIDE_ALLIED) ? 2 : ((side == VOIDSTRIKE_SIDE_ENEMY) ? 1 : 0);
        return nextVoidstrikeNumberBySide[idx];
    }

    /**
     * Evaluates all airborne strike craft across Player (side=0), Enemy (side=1), and Allied (side=2) battle groups:
     *  - Individual sides (Player, Enemy, Allies) maintain separate Voidstrike counters.
     *  - Instantiates and registers Voidstrikes for strike craft groups assigned an enemy target.
     *  - Merges multi-division strike craft attacking the same target (or flying within 1450u) into ONE Voidstrike.
     *  - Updates the live centroid ("Voidstrike #" position) of each active Voidstrike.
     *  - Removes a Voidstrike as soon as its strike craft begin returning to their carrier(s), causing
     *    "Voidstrike #" to disappear and the next instantiated Voidstrike to use the incremented counter.
     *  - Preserves existing Voidstrike identity across temporary Fog-of-War occlusion or brief target switches
     *    so the Voidstrike counter never runs away to 120+.
     */
    /**
     * Lightweight 60+ FPS render-loop helper: updates live fighter positions for active Voidstrikes in O(K)
     * every frame so "Voidstrike #" labels glide smoothly, while throttling full wing-clustering scans to ~7 Hz.
     */
    public static void refreshVoidstrikesForRender(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup,
            float now) {
        if (engine != lastVoidstrikeUpdateEngine || Math.abs(now - lastVoidstrikeUpdateTime) >= 0.14f) {
            updateVoidstrikes(engine, playerGroup, alliedAiGroup, enemyGroup, now);
            return;
        }
        if (activeVoidstrikes.isEmpty()) return;
        FogOfWarAPI playerFog = engine != null ? engine.getFogOfWar(0) : null;
        for (VoidstrikeRecord rec : activeVoidstrikes) {
            if (rec == null || rec.activeFighters.isEmpty()) continue;
            float sx = 0f, sy = 0f;
            int cnt = 0;
            float vsx = 0f, vsy = 0f;
            int vcnt = 0;
            for (ShipAPI fm : rec.activeFighters) {
                if (fm == null || !fm.isAlive() || fm.isHulk() || fm.isExpired()) continue;
                sx += fm.getLocation().x;
                sy += fm.getLocation().y;
                cnt++;
                if (rec.side != VOIDSTRIKE_SIDE_ENEMY || playerFog == null || playerFog.isVisible(fm)) {
                    vsx += fm.getLocation().x;
                    vsy += fm.getLocation().y;
                    vcnt++;
                }
            }
            if (cnt > 0) {
                rec.center.set(sx / cnt, sy / cnt);
            }
            if (rec.side != VOIDSTRIKE_SIDE_ENEMY) {
                rec.visibleToPlayer = cnt > 0;
                rec.visibleCenter.set(rec.center);
            } else {
                rec.visibleToPlayer = vcnt > 0;
                if (vcnt > 0) {
                    rec.visibleCenter.set(vsx / vcnt, vsy / vcnt);
                }
            }
        }
    }

    public static void updateVoidstrikes(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup,
            float now) {

        lastVoidstrikeUpdateEngine = engine;
        lastVoidstrikeUpdateTime = now;

        List<WingStrikeCandidate> candidatesPlayer = new ArrayList<>();
        List<WingStrikeCandidate> candidatesEnemy = new ArrayList<>();
        List<WingStrikeCandidate> candidatesAllied = new ArrayList<>();
        Set<String> visitedWingKeys = new HashSet<>();
        Set<String> returningWingKeys = new HashSet<>();

        Set<String> activeWingKeysSnapshot = new HashSet<>();
        for (VoidstrikeRecord rec : activeVoidstrikes) {
            if (rec != null) {
                activeWingKeysSnapshot.addAll(rec.participatingWingKeys);
            }
        }
        for (VoidstrikeRecord rec : recentVoidstrikeHistory) {
            if (rec != null && (now - rec.lastActiveTime) <= 1.25f) {
                activeWingKeysSnapshot.addAll(rec.participatingWingKeys);
            }
        }

        collectWingStrikeCandidatesFromGroup(
                playerGroup, VOIDSTRIKE_SIDE_PLAYER, enemyGroup, visitedWingKeys, activeWingKeysSnapshot, returningWingKeys, candidatesPlayer);
        collectWingStrikeCandidatesFromGroup(
                alliedAiGroup, VOIDSTRIKE_SIDE_ALLIED, enemyGroup, visitedWingKeys, activeWingKeysSnapshot, returningWingKeys, candidatesAllied);
        collectWingStrikeCandidatesFromGroup(
                enemyGroup, VOIDSTRIKE_SIDE_ENEMY, playerGroup, visitedWingKeys, activeWingKeysSnapshot, returningWingKeys, candidatesEnemy);

        // Also scan any unassigned carrier hulls in engine.getShips() so standalone carriers still register Voidstrikes
        if (engine != null && engine.getShips() != null) {
            for (ShipAPI ship : engine.getShips()) {
                if (ship == null || !ship.isAlive() || ship.isHulk() || ship.isFighter()) continue;
                if (ship.getAllWings() == null || ship.getAllWings().isEmpty()) continue;
                int side;
                DivisionState div = null;
                if (ship.getOwner() == 0) {
                    if (playerGroup != null) div = playerGroup.shipIdToDivision.get(ship.getId());
                    if (div != null) {
                        side = VOIDSTRIKE_SIDE_PLAYER;
                    } else if (alliedAiGroup != null && alliedAiGroup.shipIdToDivision.containsKey(ship.getId())) {
                        div = alliedAiGroup.shipIdToDivision.get(ship.getId());
                        side = VOIDSTRIKE_SIDE_ALLIED;
                    } else {
                        side = ship.isAlly() ? VOIDSTRIKE_SIDE_ALLIED : VOIDSTRIKE_SIDE_PLAYER;
                    }
                } else {
                    side = VOIDSTRIKE_SIDE_ENEMY;
                    if (enemyGroup != null) div = enemyGroup.shipIdToDivision.get(ship.getId());
                }
                BattleGroupState opp = (side == VOIDSTRIKE_SIDE_ENEMY) ? playerGroup : enemyGroup;
                List<WingStrikeCandidate> targetList = (side == VOIDSTRIKE_SIDE_PLAYER)
                        ? candidatesPlayer
                        : (side == VOIDSTRIKE_SIDE_ALLIED ? candidatesAllied : candidatesEnemy);
                collectWingStrikeCandidatesFromCarrier(
                        ship,
                        div,
                        side,
                        opp,
                        visitedWingKeys,
                        activeWingKeysSnapshot,
                        returningWingKeys,
                        targetList);
            }
        }

        FogOfWarAPI playerFog = (engine != null) ? engine.getFogOfWar(0) : null;
        reconcileVoidstrikesForSide(VOIDSTRIKE_SIDE_PLAYER, candidatesPlayer, returningWingKeys, playerFog, now);
        reconcileVoidstrikesForSide(VOIDSTRIKE_SIDE_ALLIED, candidatesAllied, returningWingKeys, playerFog, now);
        reconcileVoidstrikesForSide(VOIDSTRIKE_SIDE_ENEMY, candidatesEnemy, returningWingKeys, playerFog, now);
    }

    private static void collectWingStrikeCandidatesFromGroup(
            BattleGroupState group,
            int side,
            BattleGroupState opposingGroup,
            Set<String> visitedWingKeys,
            Set<String> activeWingKeysSnapshot,
            Set<String> returningWingKeys,
            List<WingStrikeCandidate> outCandidates) {
        if (group == null || group.divisions == null) return;
        for (DivisionState div : group.divisions) {
            if (div == null) continue;
            for (ShipAPI carrier : div.getAliveMembers()) {
                collectWingStrikeCandidatesFromCarrier(
                        carrier,
                        div,
                        side,
                        opposingGroup,
                        visitedWingKeys,
                        activeWingKeysSnapshot,
                        returningWingKeys,
                        outCandidates);
            }
        }
    }

    private static void collectWingStrikeCandidatesFromCarrier(
            ShipAPI carrier,
            DivisionState div,
            int side,
            BattleGroupState opposingGroup,
            Set<String> visitedWingKeys,
            Set<String> activeWingKeysSnapshot,
            Set<String> returningWingKeys,
            List<WingStrikeCandidate> outCandidates) {
        if (carrier == null || !carrier.isAlive() || carrier.isHulk() || carrier.isExpired()) return;
        List<com.fs.starfarer.api.combat.FighterWingAPI> wings = carrier.getAllWings();
        if (wings == null || wings.isEmpty()) return;

        // When carrier is pulling back fighters (Regroup / Marshaling), its strike craft are returning or held on deck
        boolean carrierPullingBack = carrier.isPullBackFighters()
                || (div != null && (div.currentIntent == DivisionModel.DivisionIntentType.MARSHAL_FOR_STRIKE
                || div.currentIntent == DivisionModel.DivisionIntentType.HOLD_IN_RESERVE
                || div.currentIntent == DivisionModel.DivisionIntentType.CLOSE_UP
                || div.currentIntent == DivisionModel.DivisionIntentType.BREAK_OFF_AND_RETIRE
                || div.currentIntent == DivisionModel.DivisionIntentType.HAUL_OUT_TO_VENT));
        if (carrierPullingBack) {
            for (int wIdx = 0; wIdx < wings.size(); wIdx++) {
                returningWingKeys.add(carrier.getId() + "#wing_" + wIdx);
            }
            return;
        }

        for (int wIdx = 0; wIdx < wings.size(); wIdx++) {
            com.fs.starfarer.api.combat.FighterWingAPI wing = wings.get(wIdx);
            if (wing == null) continue;
            String wingKey = carrier.getId() + "#wing_" + wIdx;
            if (!visitedWingKeys.add(wingKey)) continue;

            List<ShipAPI> members = wing.getWingMembers();
            if (members == null || members.isEmpty()) continue;

            List<ShipAPI> nonReturningFighters = new ArrayList<>(members.size());
            float sumX = 0f;
            float sumY = 0f;
            ShipAPI fighterAssignedEnemy = null;

            for (ShipAPI f : members) {
                if (f == null || !f.isAlive() || f.isHulk() || f.isExpired()) continue;
                // When strike craft are returning to refit/land, they are no longer on an active Voidstrike
                if (wing.isReturning(f)) continue;
                nonReturningFighters.add(f);
                sumX += f.getLocation().x;
                sumY += f.getLocation().y;

                if (fighterAssignedEnemy == null) {
                    ShipAPI st = f.getShipTarget();
                    if (st != null && st.isAlive() && !st.isHulk() && st.getOwner() != carrier.getOwner()) {
                        fighterAssignedEnemy = st;
                    } else if (f.getAIFlags() != null) {
                        Object mt = f.getAIFlags().getCustom(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.MANEUVER_TARGET);
                        if (mt instanceof ShipAPI) {
                            ShipAPI mst = (ShipAPI) mt;
                            if (mst.isAlive() && !mst.isHulk() && mst.getOwner() != carrier.getOwner()) {
                                fighterAssignedEnemy = mst;
                            }
                        }
                    }
                }
            }

            // If all fighters in the wing are returning (or destroyed), this wing is not on a Voidstrike
            if (nonReturningFighters.isEmpty()) {
                returningWingKeys.add(wingKey);
                continue;
            }

            Vector2f wingCenter = new Vector2f(
                    sumX / nonReturningFighters.size(),
                    sumY / nonReturningFighters.size());

            // Resolve assigned enemy target (from fighter target, carrier fighter target, carrier target, or division target)
            ShipAPI targetEnemyShip = fighterAssignedEnemy;
            if (targetEnemyShip == null && carrier.getAIFlags() != null) {
                Object cft = carrier.getAIFlags().getCustom(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.CARRIER_FIGHTER_TARGET);
                if (cft instanceof ShipAPI) {
                    ShipAPI s = (ShipAPI) cft;
                    if (s.isAlive() && !s.isHulk() && s.getOwner() != carrier.getOwner()) {
                        targetEnemyShip = s;
                    }
                }
                if (targetEnemyShip == null) {
                    Object mt = carrier.getAIFlags().getCustom(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.MANEUVER_TARGET);
                    if (mt instanceof ShipAPI) {
                        ShipAPI s = (ShipAPI) mt;
                        if (s.isAlive() && !s.isHulk() && s.getOwner() != carrier.getOwner()) {
                            targetEnemyShip = s;
                        }
                    }
                }
            }
            if (targetEnemyShip == null) {
                ShipAPI ct = carrier.getShipTarget();
                if (ct != null && ct.isAlive() && !ct.isHulk() && ct.getOwner() != carrier.getOwner()) {
                    targetEnemyShip = ct;
                }
            }

            // Map any fighter target (or enemy ship target) up to its parent enemy division so brief fighter-to-fighter
            // target switches during a dogfight never fragment or re-instantiate a new Voidstrike!
            DivisionState targetEnemyDiv = (div != null && div.targetEnemyDivision != null && div.targetEnemyDivision.isCombatEffective())
                    ? div.targetEnemyDivision
                    : null;
            if (targetEnemyDiv == null && targetEnemyShip != null && opposingGroup != null && opposingGroup.shipIdToDivision != null) {
                ShipAPI lookupShip = targetEnemyShip;
                if (lookupShip.isFighter() && lookupShip.getWing() != null && lookupShip.getWing().getSourceShip() != null) {
                    lookupShip = lookupShip.getWing().getSourceShip();
                }
                targetEnemyDiv = opposingGroup.shipIdToDivision.get(lookupShip.getId());
            }

            // A Voidstrike requires an assigned enemy target (targetEnemyDiv or targetEnemyShip)
            if (targetEnemyDiv == null && targetEnemyShip == null) continue;

            // Prevent defensive carrier-escort wings that are merely orbiting right on top of their own carrier
            // from churning Voidstrike numbers when crossing defensive patrol thresholds:
            boolean alreadyActive = activeWingKeysSnapshot != null && activeWingKeysSnapshot.contains(wingKey);
            float distFromCarrier = distance(wingCenter, carrier.getLocation());
            if (div != null && div.currentIntent == DivisionModel.DivisionIntentType.FLY_COMBAT_SPACE_PATROL) {
                if (targetEnemyShip == null || (!alreadyActive && distFromCarrier < 260f)) {
                    continue;
                }
            }

            // Normalize targetKey to the enemy Division ID whenever known (or to the parent carrier of an enemy fighter)
            // so target key remains rock-solid throughout the entire strike sortie!
            String targetKey;
            if (targetEnemyDiv != null) {
                targetKey = "DIV:" + targetEnemyDiv.id;
            } else if (targetEnemyShip != null
                    && targetEnemyShip.isFighter()
                    && targetEnemyShip.getWing() != null
                    && targetEnemyShip.getWing().getSourceShip() != null) {
                targetKey = "SHIP:" + targetEnemyShip.getWing().getSourceShip().getId();
            } else {
                targetKey = "SHIP:" + targetEnemyShip.getId();
            }
            String divId = (div != null && div.id != null) ? div.id : ("SHIP_DIV:" + carrier.getId());

            outCandidates.add(new WingStrikeCandidate(
                    side,
                    wingKey,
                    divId,
                    targetKey,
                    nonReturningFighters,
                    wingCenter));
        }
    }

    private static void reconcileVoidstrikesForSide(
            int side,
            List<WingStrikeCandidate> candidates,
            Set<String> returningWingKeys,
            FogOfWarAPI playerFog,
            float now) {

        // 1. Cluster active WingStrikeCandidates so multi-division voidstrikes against the same target,
        // from the same division, or flying within 1500u of each other are merged into ONE single Voidstrike!
        int n = candidates.size();
        boolean[] visited = new boolean[n];
        List<List<WingStrikeCandidate>> mergedClusters = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (visited[i]) continue;
            List<WingStrikeCandidate> cluster = new ArrayList<>();
            cluster.add(candidates.get(i));
            visited[i] = true;

            boolean addedAny = true;
            while (addedAny) {
                addedAny = false;
                for (int j = 0; j < n; j++) {
                    if (visited[j]) continue;
                    WingStrikeCandidate candJ = candidates.get(j);
                    boolean shouldMerge = false;
                    for (WingStrikeCandidate member : cluster) {
                        if (member.targetKey.equals(candJ.targetKey)
                                || member.divisionId.equals(candJ.divisionId)
                                || distance(member.center, candJ.center) <= 1500f) {
                            shouldMerge = true;
                            break;
                        }
                    }
                    if (shouldMerge) {
                        cluster.add(candJ);
                        visited[j] = true;
                        addedAny = true;
                    }
                }
            }
            mergedClusters.add(cluster);
        }

        // 2. Collect existing VoidstrikeRecords for this side, plus any recently-ended records within a 2.5s
        // hysteresis window (so a 1-frame target switch or Fog-of-War flicker never increments the counter to 120+!)
        List<VoidstrikeRecord> existingForSide = new ArrayList<>();
        Iterator<VoidstrikeRecord> it = activeVoidstrikes.iterator();
        while (it.hasNext()) {
            VoidstrikeRecord rec = it.next();
            if (rec.side == side) {
                existingForSide.add(rec);
                it.remove();
            }
        }
        Iterator<VoidstrikeRecord> histIt = recentVoidstrikeHistory.iterator();
        while (histIt.hasNext()) {
            VoidstrikeRecord hRec = histIt.next();
            if ((now - hRec.lastActiveTime) > 1.25f) {
                histIt.remove();
            } else if (hRec.side == side) {
                existingForSide.add(hRec);
                histIt.remove();
            }
        }

        boolean[] existingUsed = new boolean[existingForSide.size()];
        List<VoidstrikeRecord> updatedForSide = new ArrayList<>(mergedClusters.size());
        int sideIdx = (side == VOIDSTRIKE_SIDE_ALLIED) ? 2 : ((side == VOIDSTRIKE_SIDE_ENEMY) ? 1 : 0);

        for (List<WingStrikeCandidate> cluster : mergedClusters) {
            Set<String> clusterWingKeys = new java.util.LinkedHashSet<>();
            Set<String> clusterDivIds = new java.util.LinkedHashSet<>();
            List<ShipAPI> clusterFighters = new ArrayList<>();
            String primaryTargetKey = cluster.get(0).targetKey;

            float sumX = 0f;
            float sumY = 0f;
            float visSumX = 0f;
            float visSumY = 0f;
            int visCount = 0;

            for (WingStrikeCandidate wc : cluster) {
                clusterWingKeys.add(wc.wingKey);
                clusterDivIds.add(wc.divisionId);
                for (ShipAPI f : wc.activeFighters) {
                    clusterFighters.add(f);
                    sumX += f.getLocation().x;
                    sumY += f.getLocation().y;
                    if (side != VOIDSTRIKE_SIDE_ENEMY || playerFog == null || playerFog.isVisible(f)) {
                        visSumX += f.getLocation().x;
                        visSumY += f.getLocation().y;
                        visCount++;
                    }
                }
            }

            if (clusterFighters.isEmpty()) continue;

            boolean isVisibleToPlayer = (side != VOIDSTRIKE_SIDE_ENEMY) || (visCount > 0);
            Vector2f clusterCenter = new Vector2f(
                    sumX / clusterFighters.size(),
                    sumY / clusterFighters.size());
            Vector2f clusterVisCenter = (visCount > 0)
                    ? new Vector2f(visSumX / visCount, visSumY / visCount)
                    : new Vector2f(clusterCenter);

            // Match to any existing VoidstrikeRecord that shares wings, shares division, shares targetKey, or is within 1550u
            VoidstrikeRecord matched = null;
            for (int eIdx = 0; eIdx < existingForSide.size(); eIdx++) {
                if (existingUsed[eIdx]) continue;
                VoidstrikeRecord ex = existingForSide.get(eIdx);
                boolean sharesWing = false;
                for (String wk : clusterWingKeys) {
                    if (ex.participatingWingKeys.contains(wk)) {
                        sharesWing = true;
                        break;
                    }
                }
                boolean sharesDiv = false;
                for (String dId : clusterDivIds) {
                    if (ex.participatingDivisionIds.contains(dId)) {
                        sharesDiv = true;
                        break;
                    }
                }
                boolean sameTarget = ex.targetKey != null && ex.targetKey.equals(primaryTargetKey);
                boolean closeCenter = distance(ex.center, clusterCenter) <= 1550f;
                if (sharesWing || sharesDiv || sameTarget || closeCenter) {
                    existingUsed[eIdx] = true;
                    if (matched == null || (ex.voidstrikeNumber > 0 && (matched.voidstrikeNumber <= 0 || ex.voidstrikeNumber < matched.voidstrikeNumber))) {
                        matched = ex;
                    }
                }
            }

            if (matched == null) {
                // For enemy side, keep tracking the cluster with voidstrikeNumber = 0 while hidden in Fog of War,
                // and only allocate a Voidstrike number once it becomes visible to the player!
                int assignedNumber = (side == VOIDSTRIKE_SIDE_ENEMY && !isVisibleToPlayer)
                        ? 0
                        : nextVoidstrikeNumberBySide[sideIdx]++;
                matched = new VoidstrikeRecord(side, assignedNumber, primaryTargetKey);
            } else if (matched.voidstrikeNumber <= 0 && (side != VOIDSTRIKE_SIDE_ENEMY || isVisibleToPlayer)) {
                matched.voidstrikeNumber = nextVoidstrikeNumberBySide[sideIdx]++;
            }

            matched.targetKey = primaryTargetKey;
            matched.participatingDivisionIds.clear();
            matched.participatingDivisionIds.addAll(clusterDivIds);
            matched.participatingWingKeys.clear();
            matched.participatingWingKeys.addAll(clusterWingKeys);
            matched.activeFighters.clear();
            matched.activeFighters.addAll(clusterFighters);
            matched.center.set(clusterCenter);
            matched.visibleCenter.set(clusterVisCenter);
            matched.visibleToPlayer = isVisibleToPlayer && (matched.voidstrikeNumber > 0);
            if (isVisibleToPlayer || matched.voidstrikeNumber > 0) {
                matched.lastActiveTime = now;
            }
            updatedForSide.add(matched);
        }

        // Preserve any unmatched VoidstrikeRecord whose wings did NOT actually start returning (e.g. a 1-frame
        // target switch) for up to 1.25s in recentVoidstrikeHistory so flicker never burns a new Voidstrike number,
        // while any Voidstrike whose strike craft returned to carrier is retired immediately so the next launch
        // increments the counter!
        for (int eIdx = 0; eIdx < existingForSide.size(); eIdx++) {
            if (!existingUsed[eIdx]) {
                VoidstrikeRecord leftover = existingForSide.get(eIdx);
                boolean allWingsReturned = !leftover.participatingWingKeys.isEmpty()
                        && returningWingKeys != null
                        && returningWingKeys.containsAll(leftover.participatingWingKeys);
                if (!allWingsReturned && leftover.voidstrikeNumber > 0 && (now - leftover.lastActiveTime) <= 1.25f) {
                    recentVoidstrikeHistory.add(leftover);
                }
            }
        }

        activeVoidstrikes.addAll(updatedForSide);
    }
}
