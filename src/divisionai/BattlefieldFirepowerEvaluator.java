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
    static final int HIGH_CONTOUR_STEPS = 72; // High fidelity: 5-degree smooth contour interpolation
    static final int LOW_CONTOUR_STEPS = 48;  // Low fidelity: 7.5-degree 2x sector supersampling
    static final int RENDER_CONTOUR_STEPS = HIGH_CONTOUR_STEPS;

    // Precomputed unit-circle trig lookup tables & zero-allocation scratch arrays for 60+ FPS T-S-F-C rendering
    static final float[] UNIT_COS_72 = new float[72];
    static final float[] UNIT_SIN_72 = new float[72];
    static final float[] UNIT_COS_48 = new float[48];
    static final float[] UNIT_SIN_48 = new float[48];
    static final float[] UNIT_COS_36 = new float[36];
    static final float[] UNIT_SIN_36 = new float[36];
    static final float[] UNIT_COS_24 = new float[24];
    static final float[] UNIT_SIN_24 = new float[24];

    static final float[] SCRATCH_SUST_X = new float[HIGH_CONTOUR_STEPS];
    static final float[] SCRATCH_SUST_Y = new float[HIGH_CONTOUR_STEPS];
    static final float[] SCRATCH_SHORT_X = new float[HIGH_CONTOUR_STEPS];
    static final float[] SCRATCH_SHORT_Y = new float[HIGH_CONTOUR_STEPS];
    static final float[] SCRATCH_ALPHA_X = new float[HIGH_CONTOUR_STEPS];
    static final float[] SCRATCH_ALPHA_Y = new float[HIGH_CONTOUR_STEPS];
    static final float[] SCRATCH_PD_X = new float[HIGH_CONTOUR_STEPS];
    static final float[] SCRATCH_PD_Y = new float[HIGH_CONTOUR_STEPS];
    static final float[] SCRATCH_TIME_OUTER_X = new float[72];
    static final float[] SCRATCH_TIME_OUTER_Y = new float[72];
    static final float[] SCRATCH_TIME_MID_X = new float[72];
    static final float[] SCRATCH_TIME_MID_Y = new float[72];

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
    static CombatEngineAPI lastTimeSpaceConvEngine = null;
    static float lastTimeSpaceConvUpdateTime = -100f;
    static int lastTimeSpaceConvDivCountSig = -1;
    static CombatEngineAPI lastVoidstrikeUpdateEngine = null;
    static float lastVoidstrikeUpdateTime = -100f;
    static CombatEngineAPI lastAlphaCoreCheckEngine = null;
    static float lastAlphaCoreCheckTime = -100f;
    static int lastAlphaCoreCheckShipsSize = -1;
    static boolean cachedHasAlphaCoreInPlayerFleet = false;

    // Asymmetric temporal stabilization time constants (seconds)
    static final float TAU_HEADING_SMOOTH = 0.42f;
    static final float TAU_RISE = 0.35f;
    static final float TAU_FALL = 1.75f;
    static final float TAU_VENT_DROP = 0.18f;

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

    static boolean overlayVisible = false;            // Firepower overlay: OFF by default
    static boolean spaceOverlayVisible = false;       // Space overlay: OFF by default
    static boolean timeOverlayVisible = false;        // Time overlay: OFF by default
    static boolean convergenceOverlayVisible = false; // Convergence overlay: OFF by default
    static boolean testForcePlayerHasAlphaCore = false;

    static ShipAPI playerConvergenceFlagshipOverride = null;
    static ShipAPI initialDefaultPlayerFlagship = null;
    static BattleGroupState initialDefaultFlagshipGroupRef = null;
    static ShipAPI pendingCommsAlphaCoreShip = null;
    static final Set<String> askedAlphaCoreShipIds = new HashSet<>();
    static int urgentCommsPromptSequence = 0;
    static int activeCommsPhraseIndex = 0;

    public static final int VOIDSTRIKE_SIDE_PLAYER = 0;
    public static final int VOIDSTRIKE_SIDE_ENEMY = 1;
    public static final int VOIDSTRIKE_SIDE_ALLIED = 2;

    // Active Voidstrikes registered in the current battle (0 = Player, 1 = Enemy, 2 = Allies)
    static final List<VoidstrikeRecord> activeVoidstrikes = new ArrayList<>();
    static final List<VoidstrikeRecord> recentVoidstrikeHistory = new ArrayList<>();
    static final int[] nextVoidstrikeNumberBySide = new int[]{1, 1, 1};

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
    static final String[][] URGENT_COMMS_PHRASES = new String[][]{
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
    static BattleGroupState activePlayerGroupRef = null;
    static BattleGroupState activeAlliedGroupRef = null;
    static BattleGroupState activeEnemyGroupRef = null;

    static float lastMouseX = -1f;
    static float lastMouseY = -1f;
    static float lastToggleRealTime = -10f;

    static final Map<String, ShipFirepowerRose> rosesByShipId = new HashMap<>();
    static final Map<String, LabelAPI> uiLabelCache = new HashMap<>();

    /**
     * Three-way tactical fire classification:
     *  - GENERAL:       Anti-ship weaponry (main guns, strike missiles, heavy beams).
     *  - POINT_DEFENSE: Dedicated anti-strike-craft / anti-missile point defense (small PD lasers, Vulcans, Light MGs).
     *  - HYBRID:        Dual-purpose weaponry effective against BOTH ships and strike craft
     *                   (Large/Medium Flak Cannons, Devastator Cannons, Dual Flak, Heavy Machine Guns, Paladin PD, PD_ALSO guns).
     */
    public enum FireClassification {
        GENERAL,
        POINT_DEFENSE,
        HYBRID
    }

    public static WeaponSize resolveEffectiveWeaponSize(WeaponAPI w) {
        if (w == null) return WeaponSize.SMALL;
        WeaponSize specSize = w.getSize();
        WeaponSize slotSize = (w.getSlot() != null) ? w.getSlot().getSlotSize() : null;
        if (specSize == WeaponSize.LARGE || slotSize == WeaponSize.LARGE) return WeaponSize.LARGE;
        if (specSize == WeaponSize.MEDIUM || slotSize == WeaponSize.MEDIUM) return WeaponSize.MEDIUM;
        return specSize != null ? specSize : (slotSize != null ? slotSize : WeaponSize.SMALL);
    }

    public static FireClassification classifyWeaponFire(WeaponAPI w) {
        if (w == null) return FireClassification.GENERAL;
        boolean isStrike = w.hasAIHint(AIHints.STRIKE);
        if (isStrike) return FireClassification.GENERAL;

        boolean isPdOnly = w.hasAIHint(AIHints.PD_ONLY) || w.hasAIHint(AIHints.ANTI_FTR);
        boolean isPdAlso = w.hasAIHint(AIHints.PD_ALSO);
        boolean isPd = isPdOnly || isPdAlso || w.hasAIHint(AIHints.PD);

        WeaponSize size = resolveEffectiveWeaponSize(w);
        String wId = w.getId() != null ? w.getId().toLowerCase(Locale.ROOT) : "";
        String wName = (w.getDisplayName() != null) ? w.getDisplayName().toLowerCase(Locale.ROOT) : "";

        // Hybrid weapons: explicit PD_ALSO weapons, Flak/Devastator/Paladin/HMG families,
        // any LARGE PD mount, or any MEDIUM non-PD_ONLY PD weapon
        boolean isNamedHybrid = wId.contains("flak") || wId.contains("devastator")
                || wId.contains("heavymg") || wId.contains("guardian") || wId.contains("paladin")
                || wName.contains("flak") || wName.contains("devastator");
        if (isPdAlso || isNamedHybrid
                || (isPd && size == WeaponSize.LARGE)
                || (isPd && !isPdOnly && size == WeaponSize.MEDIUM)) {
            return FireClassification.HYBRID;
        }
        if (isPd) {
            return FireClassification.POINT_DEFENSE;
        }
        return FireClassification.GENERAL;
    }

    public static float getShipHybridFirepower(ShipAPI ship) {
        if (ship == null) return 0f;
        ShipFirepowerRose rose = rosesByShipId.get(ship.getId());
        if (rose != null && rose.initialized) {
            return rose.peakSectorHybrid;
        }
        float totalHybrid = 0f;
        List<WeaponAPI> weapons = ship.getAllWeapons();
        if (weapons != null) {
            for (WeaponAPI w : weapons) {
                if (!isUsableCombatWeapon(w) || w.isDisabled() || w.isPermanentlyDisabled()) continue;
                if (classifyWeaponFire(w) == FireClassification.HYBRID) {
                    WeaponSize sz = resolveEffectiveWeaponSize(w);
                    totalHybrid += (sz == WeaponSize.LARGE ? 240f : (sz == WeaponSize.MEDIUM ? 135f : 55f));
                }
            }
        }
        return totalHybrid;
    }

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

        // Smoothed 3-horizon General scores, PD score, & Hybrid score per 15-degree sector
        public final float[] alpha = new float[NUM_SECTORS];
        public final float[] shortDot = new float[NUM_SECTORS];
        public final float[] sustainedDot = new float[NUM_SECTORS];
        public final float[] pdScore = new float[NUM_SECTORS];
        public final float[] hybridScore = new float[NUM_SECTORS];

        // Smoothed weapon reach (world units) per sector, weighted by potential damage per horizon
        public final float[] generalRange = new float[NUM_SECTORS];
        public final float[] sustainedRange = new float[NUM_SECTORS];
        public final float[] shortRange = new float[NUM_SECTORS];
        public final float[] alphaRange = new float[NUM_SECTORS];
        public final float[] pdRange = new float[NUM_SECTORS];
        public final float[] hybridRange = new float[NUM_SECTORS];

        // Summary metrics for fast queries, potential-damage weighting, and blind-spot comparison
        public float peakSectorShortDot = 1f;
        public float peakSectorAlpha = 1f;
        public float peakSectorSustained = 1f;
        public float peakSectorHybrid = 0f;
        public float maxGeneralRange = 600f;
        public float maxPdRange = 400f;
        public float maxHybridRange = 400f;
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

        /** Interpolates Hybrid weapon reach at a world bearing (degrees). */
        public float sampleHybridRangeAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(hybridRange, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
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

        /** Interpolates Hybrid (dual anti-ship + anti-strike-craft) score at a world bearing (degrees). */
        public float sampleHybridAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(hybridScore, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
        }

        static float sampleArrayAtRelAngle(float[] arr, float relAngleDeg) {
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
        public float incomingHybrid;

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

    static boolean computeHasAlphaCoreInPlayerFleetUncached(CombatEngineAPI engine) {
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
        if (group.forceConvergenceOverride != null) {
            boolean forced = group.forceConvergenceOverride;
            group.useConvergence = forced;
            for (DivisionState div : group.divisions) {
                div.useConvergence = forced && !div.getAliveMembers().isEmpty();
            }
            return;
        }
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

    static void updateSingleShipRose(ShipFirepowerRose rose, ShipAPI ship, float now) {
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
        float[] rawHybrid = new float[NUM_SECTORS];
        float[] rawGenRange = new float[NUM_SECTORS];
        float[] rawSustRangeWeightedSum = new float[NUM_SECTORS];
        float[] rawShortRangeWeightedSum = new float[NUM_SECTORS];
        float[] rawAlphaRangeWeightedSum = new float[NUM_SECTORS];
        float[] rawPdRange = new float[NUM_SECTORS];
        float[] rawHybridRange = new float[NUM_SECTORS];

        float hullOmega = Math.abs(ship.getAngularVelocity());

        if (weapons != null && !weapons.isEmpty()) {
            for (WeaponAPI w : weapons) {
                if (!isUsableCombatWeapon(w)) continue;
                if (w.isDisabled() || w.isPermanentlyDisabled()) continue;
                if (w.usesAmmo() && w.getAmmo() <= 0) continue;

                float range = w.getRange();
                if (range <= 50f) continue;

                boolean isStrike = w.hasAIHint(AIHints.STRIKE);
                FireClassification fireClass = classifyWeaponFire(w);

                float pdFrac;
                float genFrac;
                float hybridFrac;
                if (fireClass == FireClassification.HYBRID) {
                    // Hybrid weapons (Large/Medium Flak, Devastators, Dual Flak, HMG, Paladin PD, PD_ALSO guns)
                    // are effective against BOTH ships (especially frigates/destroyers) AND strike craft!
                    pdFrac = 0.90f;
                    genFrac = 0.85f;
                    hybridFrac = 1.0f;
                } else if (fireClass == FireClassification.POINT_DEFENSE) {
                    pdFrac = 1.0f;
                    genFrac = 0.14f; // light close-in pressure against frigates
                    hybridFrac = 0.0f;
                } else {
                    pdFrac = 0.0f;
                    genFrac = 1.0f;
                    hybridFrac = 0.0f;
                }

                float sizeW = weaponSizeWeight(resolveEffectiveWeaponSize(w));
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
                    // Hybrid Flak Cannons (Flak / Dual Flak) shred frigates, destroyers, and exposed hulls
                    // far harder than light fragmentation PD!
                    typeMult = (fireClass == FireClassification.HYBRID) ? 0.82f : 0.45f;
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
                    if (hybridFrac > 0f) {
                        float hw = angularWeight * hybridFrac * readiness;
                        rawHybrid[s] += (wShortDot * 0.75f + wAlpha * 0.25f) * hw;
                        float effHybridRange = range * (0.76f + 0.24f * angularWeight);
                        if (effHybridRange > rawHybridRange[s]) {
                            rawHybridRange[s] = effHybridRange;
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
        float peakHyb = 0f;
        float maxGenR = 300f;
        float maxPdR = 200f;
        float maxHybR = 200f;

        for (int s = 0; s < NUM_SECTORS; s++) {
            if (!rose.initialized) {
                rose.alpha[s] = rawAlpha[s];
                rose.shortDot[s] = rawShort[s];
                rose.sustainedDot[s] = rawSustained[s];
                rose.pdScore[s] = rawPd[s];
                rose.hybridScore[s] = rawHybrid[s];
                rose.generalRange[s] = rawGenRange[s];
                rose.sustainedRange[s] = rawSustRange[s];
                rose.shortRange[s] = rawShortRange[s];
                rose.alphaRange[s] = rawAlphaRange[s];
                rose.pdRange[s] = rawPdRange[s];
                rose.hybridRange[s] = rawHybridRange[s];
            } else {
                rose.alpha[s] = blendAsymmetric(rose.alpha[s], rawAlpha[s], alphaRise, alphaFall);
                rose.shortDot[s] = blendAsymmetric(rose.shortDot[s], rawShort[s], alphaRise, alphaFall);
                rose.sustainedDot[s] = blendAsymmetric(rose.sustainedDot[s], rawSustained[s], alphaRise, alphaFall);
                rose.pdScore[s] = blendAsymmetric(rose.pdScore[s], rawPd[s], alphaRise, alphaFall);
                rose.hybridScore[s] = blendAsymmetric(rose.hybridScore[s], rawHybrid[s], alphaRise, alphaFall);
                rose.generalRange[s] = blendAsymmetric(rose.generalRange[s], rawGenRange[s], alphaRise, alphaFall * 0.7f);
                rose.sustainedRange[s] = blendAsymmetric(rose.sustainedRange[s], rawSustRange[s], alphaRise, alphaFall * 0.7f);
                rose.shortRange[s] = blendAsymmetric(rose.shortRange[s], rawShortRange[s], alphaRise, alphaFall * 0.7f);
                rose.alphaRange[s] = blendAsymmetric(rose.alphaRange[s], rawAlphaRange[s], alphaRise, alphaFall * 0.7f);
                rose.pdRange[s] = blendAsymmetric(rose.pdRange[s], rawPdRange[s], alphaRise, alphaFall * 0.7f);
                rose.hybridRange[s] = blendAsymmetric(rose.hybridRange[s], rawHybridRange[s], alphaRise, alphaFall * 0.7f);
            }

            if (rose.shortDot[s] > peakShort) peakShort = rose.shortDot[s];
            if (rose.alpha[s] > peakAlpha) peakAlpha = rose.alpha[s];
            if (rose.sustainedDot[s] > peakSust) peakSust = rose.sustainedDot[s];
            if (rose.hybridScore[s] > peakHyb) peakHyb = rose.hybridScore[s];
            if (rose.generalRange[s] > maxGenR) maxGenR = rose.generalRange[s];
            if (rose.pdRange[s] > maxPdR) maxPdR = rose.pdRange[s];
            if (rose.hybridRange[s] > maxHybR) maxHybR = rose.hybridRange[s];
        }

        rose.peakSectorShortDot = peakShort;
        rose.peakSectorAlpha = peakAlpha;
        rose.peakSectorSustained = peakSust;
        rose.peakSectorHybrid = peakHyb;
        rose.maxGeneralRange = maxGenR;
        rose.maxPdRange = maxPdR;
        rose.maxHybridRange = maxHybR;
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
                float hybridReach = ShipFirepowerRose.sampleArrayAtRelAngle(rose.hybridRange, relAngle);
                if (hybridReach > 50f && dist <= hybridReach + 180f) {
                    float hybFactor = (dist <= hybridReach) ? 1.0f : Math.max(0f, 1.0f - (dist - hybridReach) / 180f);
                    eval.incomingHybrid += ShipFirepowerRose.sampleArrayAtRelAngle(rose.hybridScore, relAngle) * hybFactor;
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
        div.incomingHybridThreat = eval.incomingHybrid;
        div.crossfireCordonIndex = eval.cordonIndex;

        boolean isLightOrDestroyerDiv = div.archetype == DivisionModel.DivisionArchetype.FRIGATE_DIVISION
                || div.archetype == DivisionModel.DivisionArchetype.MISSILE_FRIGATE_DIVISION
                || div.archetype == DivisionModel.DivisionArchetype.DESTROYER_DIVISION
                || div.archetype == DivisionModel.DivisionArchetype.MISSILE_DESTROYER_DIVISION
                || div.archetype == DivisionModel.DivisionArchetype.PHASE_DIVISION;
        float dangerScore = eval.effectiveDanger;
        if (isLightOrDestroyerDiv && (eval.incomingHybrid > 0f || eval.incomingPd > 0f)) {
            // Hybrid weapons (Large/Medium Flak, Devastator, Dual Flak, HMG, Paladin PD) are especially
            // lethal to Frigates and Destroyers at close range!
            dangerScore += eval.incomingHybrid * 0.65f + eval.incomingPd * 0.25f;
        }

        // Asymmetric smoothing on division danger score
        if (div.smoothedDangerScore <= 0f) {
            div.smoothedDangerScore = dangerScore;
        } else if (dangerScore >= div.smoothedDangerScore) {
            div.smoothedDangerScore = div.smoothedDangerScore * 0.55f + dangerScore * 0.45f;
        } else {
            div.smoothedDangerScore = div.smoothedDangerScore * 0.80f + dangerScore * 0.20f;
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
            float bearingShortDot = Math.max(
                    rose.sampleShortDotAtWorldAngle(worldBearing),
                    rose.sampleHybridAtWorldAngle(worldBearing) * 0.90f);
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

            // Map-edge tactical awareness (Item D):
            //  - Penalize getting wedged into a narrow edge trap when attacking/flanking,
            //  - Reward defensive map-edge anchoring when outnumbered/pressured and the map edge is behind our division
            //    (blocking rear encirclement and concentrating fire into a restricted frontal arc).
            score += evaluateMapEdgeStationScore(engine, myDiv, arcCenter, cand, myDiv.localForceRatio < 0.85f);

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

    /**
     * Computes a ship's current Effective HP (Shield HP + Armor HP + Hull HP):
     *  - Hull HP: remaining hull hitpoints (with hull-size fallback for headless mock ships).
     *  - Armor HP: remaining armor pool weighted by armor rating damage reduction and armor grid integrity.
     *  - Shield HP: remaining flux capacity divided by shield flux/damage efficiency and weighted by shield arc coverage
     *    (0 if overloaded or venting; phase cloak uses remaining soft-flux evasion capacity).
     */
    public static float computeShipEffectiveHp(ShipAPI ship) {
        return computeShipEffectiveHpInternal(ship, false);
    }

    /**
     * Computes a ship's maximum uninjured/unfluxed Effective HP (Max Shield HP + Max Armor HP + Max Hull HP).
     */
    public static float computeShipMaxEffectiveHp(ShipAPI ship) {
        return computeShipEffectiveHpInternal(ship, true);
    }

    static float computeShipEffectiveHpInternal(ShipAPI ship, boolean maxOnly) {
        if (ship == null) return 1000f;
        HullSize size = ship.getHullSize() != null ? ship.getHullSize() : HullSize.DESTROYER;

        // 1. Hull HP
        float defaultMaxHull;
        float defaultArmor;
        float defaultMaxFlux;
        switch (size) {
            case CAPITAL_SHIP:
                defaultMaxHull = 22500f;
                defaultArmor = 1500f;
                defaultMaxFlux = 18000f;
                break;
            case CRUISER:
                defaultMaxHull = 11500f;
                defaultArmor = 1000f;
                defaultMaxFlux = 10500f;
                break;
            case DESTROYER:
                defaultMaxHull = 5500f;
                defaultArmor = 550f;
                defaultMaxFlux = 5000f;
                break;
            case FIGHTER:
                defaultMaxHull = 600f;
                defaultArmor = 120f;
                defaultMaxFlux = 400f;
                break;
            case FRIGATE:
            default:
                defaultMaxHull = 2500f;
                defaultArmor = 300f;
                defaultMaxFlux = 2600f;
                break;
        }

        float maxHull = ship.getMaxHitpoints() > 1f ? ship.getMaxHitpoints() : defaultMaxHull;
        float hullLevel = clampVal(ship.getHullLevel(), 0f, 1f);
        float currHull;
        if (maxOnly) {
            currHull = maxHull;
        } else if (ship.getHitpoints() > 1f && ship.getMaxHitpoints() > 1f) {
            currHull = Math.min(maxHull, ship.getHitpoints());
        } else {
            currHull = maxHull * (hullLevel > 0f ? hullLevel : 1.0f);
        }

        // 2. Armor Effective HP (Armor Rating * Pool * Armor DR multiplier * remaining cell integrity)
        com.fs.starfarer.api.combat.ArmorGridAPI grid = ship.getArmorGrid();
        float armorRating = (grid != null && grid.getArmorRating() > 0f)
                ? grid.getArmorRating()
                : ((ship.getHullSpec() != null && ship.getHullSpec().getArmorRating() > 0f)
                ? ship.getHullSpec().getArmorRating()
                : defaultArmor);
        float armorIntegrity = 1.0f;
        if (!maxOnly) {
            if (grid != null && grid.getGrid() != null && grid.getMaxArmorInCell() > 0f) {
                float[][] cells = grid.getGrid();
                float maxCell = grid.getMaxArmorInCell();
                float sum = 0f;
                int count = 0;
                for (int x = 0; x < cells.length; x++) {
                    float[] col = cells[x];
                    if (col == null) continue;
                    for (int y = 0; y < col.length; y++) {
                        sum += clampVal(col[y] / maxCell, 0f, 1f);
                        count++;
                    }
                }
                armorIntegrity = count > 0 ? clampVal(sum / count, 0.08f, 1.0f) : Math.max(0.15f, hullLevel);
            } else {
                armorIntegrity = Math.max(0.15f, hullLevel);
            }
        }
        float maxArmorEhp = armorRating * 7.5f * (1.0f + armorRating / 550f);
        float armorEhp = maxOnly ? maxArmorEhp : (maxArmorEhp * armorIntegrity);

        // 3. Shield Effective HP (Remaining Flux Capacity / Shield Flux-per-Damage * Arc Coverage)
        FluxTrackerAPI ft = ship.getFluxTracker();
        float maxFlux = (ft != null && ft.getMaxFlux() > 1f) ? ft.getMaxFlux() : defaultMaxFlux;
        float fluxLevel = maxOnly ? 0f : clampVal(ship.getFluxLevel(), 0f, 1f);
        float currFlux = maxOnly
                ? 0f
                : ((ft != null && ft.getMaxFlux() > 1f && ft.getCurrFlux() >= 0f)
                ? Math.max(ft.getCurrFlux(), maxFlux * fluxLevel)
                : (maxFlux * fluxLevel));
        boolean ovldOrVent = !maxOnly && ft != null && ft.isOverloadedOrVenting();

        ShieldAPI sh = ship.getShield();
        float shieldEhp = 0f;
        if (sh != null && sh.getType() != ShieldAPI.ShieldType.NONE && sh.getType() != ShieldAPI.ShieldType.PHASE) {
            if (!ovldOrVent) {
                float fluxPerDam = sh.getFluxPerPointOfDamage() > 0.05f ? sh.getFluxPerPointOfDamage() : 1.0f;
                float arc = sh.getArc() > 0f ? sh.getArc() : 180f;
                float arcFactor = clampVal(0.60f + 0.40f * (arc / 240f), 0.60f, 1.15f);
                float remFlux = Math.max(0f, maxFlux - currFlux);
                shieldEhp = (remFlux / fluxPerDam) * arcFactor;
            }
        } else if (ship.getPhaseCloak() != null || (sh != null && sh.getType() == ShieldAPI.ShieldType.PHASE)) {
            if (!ovldOrVent) {
                float remFlux = Math.max(0f, maxFlux - currFlux);
                shieldEhp = remFlux * 1.25f;
            }
        } else if (sh == null && ship.getHullSpec() == null && size != HullSize.FIGHTER) {
            // Headless SimArena mock ship without explicit ShieldAPI mock: estimate baseline shield capacity from flux
            if (!ovldOrVent) {
                float remFlux = Math.max(0f, maxFlux - currFlux);
                shieldEhp = remFlux * 0.80f;
            }
        }

        return Math.max(100f, currHull + armorEhp + shieldEhp);
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
        float totalCurrEhp = 0f;
        float totalMaxEhp = 0f;

        for (ShipAPI s : alive) {
            if (div.detachedStragglerIds.contains(s.getId()) && div.coreMemberCount >= 1) {
                continue;
            }
            if (DivisionState.isShipUnable(s) && div.ableMemberCount >= 1) {
                continue;
            }
            float w = ShipCapabilityProfile.resolveShipFpStrength(s) * (s == div.flagship ? 1.35f : 1.0f);
            float spd = Math.max(25f, s.getMaxSpeed());
            weightedSpeedSum += spd * w;
            weightSum += w;
            if (spd < minCoreSpeed) {
                minCoreSpeed = spd;
            }
            totalCurrEhp += computeShipEffectiveHp(s);
            totalMaxEhp += computeShipMaxEffectiveHp(s);

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

        // Effective HP (Shield HP + Armor HP + Hull HP) & Combat Readiness for the 'Space' Domain:
        // Glass-cannon hulls (e.g. Hammerheads/Sunders) or divisions with depleted shields/armor/hull project less
        // sustainable Space dominance than heavily armored/shielded line divisions of the same FP cost!
        div.totalEffectiveHp = Math.max(100f, totalCurrEhp);
        div.maxEffectiveHp = Math.max(div.totalEffectiveHp, totalMaxEhp);
        div.effectiveHpRatio = clampVal(div.totalEffectiveHp / Math.max(1f, div.maxEffectiveHp), 0.18f, 1.0f);

        float ehpEquivalentStrength = div.totalEffectiveHp / 1600f;
        float ehpDensityRatio = ehpEquivalentStrength / Math.max(4f, div.totalFpStrength);
        float ehpRadiusFactor = clampVal(
                (0.74f + 0.26f * (float) Math.sqrt(clampVal(ehpDensityRatio, 0.35f, 2.5f)))
                        * (0.68f + 0.32f * div.effectiveHpRatio),
                0.58f,
                1.22f);

        float readiness = clampVal(
                (1.05f - 0.55f * div.avgFluxLevel) * Math.max(0.38f, div.avgHullLevel) * (0.45f + 0.55f * div.effectiveHpRatio),
                0.24f,
                1.10f);
        float avgWingReach = totalFighterWings > 0 ? (wingRangeAccum / totalFighterWings) : 0f;
        float effRange = Math.max(550f, Math.max(div.avgEngageRange, avgWingReach * 0.68f));

        // Space Zone 1 (Directly Dominated Up Close) & Zone 2 (Dominated Nearby), weighted by Effective HP & Strike Craft
        float wingInnerBonus = Math.min(240f, totalFighterWings * 42f);
        float wingDomBonus = Math.min(480f, totalFighterWings * 85f * readiness);
        div.spaceInnerRadius = clampVal(
                (div.spreadRadius * 0.55f + Math.min(900f, effRange * 0.50f)) * ehpRadiusFactor + wingInnerBonus,
                380f,
                1520f);
        div.spaceDominatedRadius = clampVal(
                (div.spreadRadius * 0.72f + effRange * (0.84f + 0.22f * readiness)) * ehpRadiusFactor + wingDomBonus,
                div.spaceInnerRadius + 220f,
                2680f);
        float effectiveSpaceMass = 0.38f * div.totalFpStrength + 0.62f * ehpEquivalentStrength;
        float unableDiscount = div.isUnableDivision
                ? 0.22f
                : Math.max(0.25f, 1.0f - 0.72f * div.retreatingFraction);
        div.spaceDominanceWeight = Math.max(2f, (effectiveSpaceMass + totalFighterWings * 4.5f) * readiness * unableDiscount);
        if (div.isUnableDivision || div.retreatingFraction > 0.25f) {
            float spaceShrink = Math.max(0.42f, 1.0f - 0.55f * div.retreatingFraction);
            div.spaceInnerRadius *= spaceShrink;
            div.spaceDominatedRadius *= spaceShrink;
        }

        // Default effectiveTimeRadius & spacePotentialRadius before cross-group engagement reduction
        if (div.effectiveTimeRadius <= 1f) {
            div.timeFreedomFactor = 1.0f - clampVal(div.engagementLevel, 0f, 0.82f);
            div.effectiveTimeRadius = div.freeTimeRadius * div.timeFreedomFactor;
        }
        float fpScale = clampVal((float) Math.sqrt(div.totalFpStrength / 38f), 0.55f, 1.35f) * unableDiscount;
        div.spacePotentialRadius = clampVal(
                div.spaceDominatedRadius + div.effectiveTimeRadius * 0.42f * fpScale,
                div.spaceDominatedRadius + 180f,
                4100f);
        if (!div.directionalSpaceInitialized) {
            for (int s = 0; s < DivisionState.TIME_DIRECTIONS; s++) {
                div.directionalSpaceInnerRadii[s] = div.spaceInnerRadius;
                div.directionalSpaceDominatedRadii[s] = div.spaceDominatedRadius;
                div.directionalSpacePotentialRadii[s] = div.spacePotentialRadius;
            }
        }
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

            // 1. Friendly Space mutual support from sibling divisions (excluding retreating/unable divisions)
            float friendlySupport = 0f;
            for (DivisionState peer : myGroup.divisions) {
                if (peer == null || peer == div || !peer.isAbleCombatEffective()) continue;
                float d = distance(div.centroid, peer.centroid);
                float bearingToDiv = (float) Math.toDegrees(Math.atan2(div.centroid.y - peer.centroid.y, div.centroid.x - peer.centroid.x));
                float peerDomR = getDirectionalSpaceDominatedRadius(peer, bearingToDiv);
                float maxSupportDist = peerDomR + div.spaceInnerRadius;
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
                    float bearingFromEnemy = (float) Math.toDegrees(Math.atan2(div.centroid.y - ePos.y, div.centroid.x - ePos.x));
                    float eDomR = getDirectionalSpaceDominatedRadius(eDiv, bearingFromEnemy);
                    float eInR = getDirectionalSpaceInnerRadius(eDiv, bearingFromEnemy);
                    float sumTimeAndEnemySpace = div.freeTimeRadius * 0.72f + eDomR;
                    if (d < sumTimeAndEnemySpace) {
                        float ov = clampVal(
                                1.0f - (d - eInR * 0.6f)
                                        / Math.max(350f, sumTimeAndEnemySpace - eInR * 0.6f),
                                0f,
                                1f);
                        enemySpaceOverlap += ov * eDiv.spaceDominanceWeight;
                    }
                }
            }
            div.hostileSpacePressure = enemySpaceOverlap;
            div.netSpaceControl = (friendlySupport + div.spaceDominanceWeight * 0.65f) - enemySpaceOverlap;

            // 2b. Map-Edge Proximity Pinch (Item D):
            //     A division close to a map edge or corner has fewer maneuver directions available; if enemy pressure
            //     is also present, the map boundary pinches its overall Time freedom.
            div.edgeProximityPinch = computeEdgeProximityPinch(engine, div.centroid, div.freeTimeRadius);

            // 3. Engagement Level = function of Enemy Space overlap + Enemy Firepower overlap + close flux load + map-edge pinch
            float refStrength = Math.max(16f, div.totalFpStrength * 1.25f);
            float spaceEngagement = clampVal(enemySpaceOverlap / refStrength, 0f, 0.62f);
            float firepowerEngagement = clampVal(
                    (div.incomingShortDotThreat / Math.max(160f, div.totalFpStrength * 15f)) * 0.45f
                            + div.crossfireCordonIndex * 0.36f,
                    0f,
                    0.62f);
            float fluxEngagement = clampVal(div.avgFluxLevel * 0.26f, 0f, 0.22f);
            float edgePinchTerm = div.edgeProximityPinch * (0.14f + 0.26f * Math.max(spaceEngagement, firepowerEngagement));
            float rawEngagement = clampVal(
                    Math.max(spaceEngagement, firepowerEngagement)
                            + 0.38f * Math.min(spaceEngagement, firepowerEngagement)
                            + fluxEngagement
                            + edgePinchTerm,
                    0f,
                    0.85f);

            if (div.engagementLevel <= 0.001f) {
                div.engagementLevel = rawEngagement;
            } else {
                div.engagementLevel = div.engagementLevel * 0.60f + rawEngagement * 0.40f;
            }
            div.timeFreedomFactor = 1.0f - clampVal(div.engagementLevel, 0f, 0.85f);
            div.effectiveTimeRadius = div.freeTimeRadius * div.timeFreedomFactor;

            // 4. Space Potential Radius is a function of effective Time and Firepower
            float unableDiscount = div.isUnableDivision
                    ? 0.22f
                    : Math.max(0.25f, 1.0f - 0.72f * div.retreatingFraction);
            float fpScale = clampVal((float) Math.sqrt(div.totalFpStrength / 38f), 0.55f, 1.35f) * unableDiscount;
            div.spacePotentialRadius = clampVal(
                    div.spaceDominatedRadius + div.effectiveTimeRadius * 0.42f * fpScale,
                    div.spaceDominatedRadius + 180f,
                    4100f);

            // 4b. Directional Engagement Pinch across 24 polar sectors (15 deg each):
            //     A division engaged on a given bearing is strongly pinched in the direction of enemy contact,
            //     Enemy Space, and Map Edges, while retaining much greater Time freedom to slide sideways or fall back.
            computeDirectionalTimeEnvelope(engine, div, myGroup, enemyGroup, refStrength, fluxEngagement);

            // 4c. Directional, Non-Circular, Pinchable Space Envelope across 24 polar sectors (Items D & E):
            //     Space is shaped by formation & weapon-arc anisotropy, directionally pinched by opposing Enemy Space
            //     and Firepower, bolstered along friendly seams, and pinched against the combat map edges!
            computeDirectionalSpaceEnvelope(engine, div, myGroup, enemyGroup, refStrength);

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
                float fx = visEnemy.x + focusEnemy.velocity.x * lookahead * 0.65f;
                float fy = visEnemy.y + focusEnemy.velocity.y * lookahead * 0.65f;
                if (engine != null) {
                    float maxW = Math.max(1500f, getMapHalfWidth(engine) - 350f);
                    float maxH = Math.max(1500f, getMapHalfHeight(engine) - 350f);
                    fx = clampVal(fx, -maxW, maxW);
                    fy = clampVal(fy, -maxH, maxH);
                }
                div.convergenceFocalPoint.set(fx, fy);
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

            // 2b. Map-Edge Directional Pinch (Item D):
            //     When the map boundary is closer than freeR * 1.18 in direction (ux, uy), the map wall directly
            //     pinches Time projection along this ray so Time cannot project past the battlefield edge.
            float edgeDist = computeDirectionalBoundaryClearance(engine, div.centroid, ux, uy, 140f);
            if (edgeDist < freeR * 1.18f) {
                float pinchedR = pinchRadiusByBoundary(freeR, edgeDist);
                float boundaryImpliedEng = clampVal(1.0f - (pinchedR / Math.max(100f, freeR)), 0.0f, 0.92f);
                dirPinch = Math.max(dirPinch, boundaryImpliedEng);
            }

            rawDirEng[s] = clampVal(dirPinch, 0.0f, 0.90f);
        }

        // 3. Circular 3-tap angular smoothing + temporal EMA so the polar Time contour is smooth and organic
        for (int s = 0; s < nDirs; s++) {
            int prev = (s + nDirs - 1) % nDirs;
            int next = (s + 1) % nDirs;
            float smoothedAng = 0.24f * rawDirEng[prev] + 0.52f * rawDirEng[s] + 0.24f * rawDirEng[next];
            smoothedAng = clampVal(smoothedAng, 0.0f, 0.90f);
            if (!div.directionalTimeInitialized) {
                div.directionalEngagement[s] = smoothedAng;
            } else {
                div.directionalEngagement[s] = div.directionalEngagement[s] * 0.55f + smoothedAng * 0.45f;
            }
            float unpinchedR = freeR * (1.0f - clampVal(div.directionalEngagement[s], 0.0f, 0.90f));
            float angleDeg = s * (360f / nDirs);
            double rad = Math.toRadians(angleDeg);
            float edgeDist = computeDirectionalBoundaryClearance(
                    engine, div.centroid, (float) Math.cos(rad), (float) Math.sin(rad), 120f);
            div.directionalTimeRadii[s] = pinchRadiusByBoundary(unpinchedR, edgeDist);
        }
        div.directionalTimeInitialized = true;
    }

    /**
     * Computes the 24-sector Directional, Non-Circular, Pinchable Space (Zone of Control) envelope for {@code div}
     * across all three tiers (Inner Directly Dominated, Mid Dominated Nearby, Outer Potentially Dominated):
     *   - Anisotropic baseline shape: shaped by the division's active formation geometry (e.g. Line Abreast broadside/front
     *     projection vs Line Ahead axial elongation vs Semicircle dome), actual member distribution around the centroid,
     *     and directional weapon-arc reach from member firepower roses.
     *   - Directional Enemy Space & Firepower Pinch: sectors facing overlapping Enemy Space or heavy incoming Firepower
     *     are compressed/pinched inward, while sectors linking up with adjacent Friendly Space are bolstered.
     *   - Map-Edge Boundary Pinch: all three Space tiers are pinched by the combat map boundaries along every sector ray.
     */
    public static void computeDirectionalSpaceEnvelope(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            float refStrength) {
        if (div == null) return;
        int nDirs = DivisionState.TIME_DIRECTIONS;
        float baseInner = Math.max(220f, div.spaceInnerRadius);
        float baseDom = Math.max(baseInner + 140f, div.spaceDominatedRadius);
        float basePot = Math.max(baseDom + 140f, div.spacePotentialRadius);

        float fwdX = div.formationHeadingInitialized ? div.formationHeadingX : 0f;
        float fwdY = div.formationHeadingInitialized ? div.formationHeadingY : (div.side == 0 ? 1f : -1f);
        float fLen = (float) Math.sqrt(fwdX * fwdX + fwdY * fwdY);
        if (fLen > 1e-4f) {
            fwdX /= fLen;
            fwdY /= fLen;
        } else {
            fwdX = 0f;
            fwdY = (div.side == 0) ? 1f : -1f;
        }
        float rightX = fwdY;
        float rightY = -fwdX;

        PermanentDivisionManager.DivisionFormation fm = div.activeFormation != null
                ? div.activeFormation
                : (div.doctrinalFormation != null ? div.doctrinalFormation : PermanentDivisionManager.DivisionFormation.LINE_ABREAST);

        List<ShipAPI> alive = div.getAliveMembers();
        float[] rawIn = new float[nDirs];
        float[] rawDom = new float[nDirs];
        float[] rawPot = new float[nDirs];

        for (int s = 0; s < nDirs; s++) {
            float angleDeg = s * (360f / nDirs);
            double rad = Math.toRadians(angleDeg);
            float ux = (float) Math.cos(rad);
            float uy = (float) Math.sin(rad);

            // 1. Formation & Hull Orientation Anisotropy (Space is not a perfect circle!)
            float cosFwd = ux * fwdX + uy * fwdY;
            float cosRight = ux * rightX + uy * rightY;
            float formShape = 1.0f;
            switch (fm) {
                case LINE_ABREAST:
                    // Wide lateral footprint + strong forward gunline projection, shallower astern
                    formShape = 0.90f + 0.16f * (cosRight * cosRight) + (cosFwd >= 0f ? 0.14f * cosFwd : -0.14f * (-cosFwd));
                    break;
                case LINE_AHEAD:
                    // Elongated along the column axis, narrower on the beam
                    formShape = 0.86f + 0.24f * (cosFwd * cosFwd);
                    break;
                case FORWARD_WEDGE:
                case FORWARD_SEMICIRCLE:
                    // Concentrated forward spearhead / dome, tapered rear
                    formShape = 0.92f + (cosFwd >= 0f ? 0.22f * cosFwd : -0.16f * (-cosFwd));
                    break;
                case REVERSE_WEDGE:
                case REVERSE_SEMICIRCLE:
                    // Concave pocket opening forward with extended flank horns
                    formShape = 0.90f + 0.18f * (cosRight * cosRight) + (cosFwd >= 0f ? 0.10f * cosFwd : -0.12f * (-cosFwd));
                    break;
                case ECHELON_LEFT: {
                    float diag = 0.7071f * (cosFwd - cosRight);
                    formShape = 0.90f + 0.20f * (diag * diag) + (cosFwd >= 0f ? 0.08f * cosFwd : -0.08f * (-cosFwd));
                    break;
                }
                case ECHELON_RIGHT: {
                    float diag = 0.7071f * (cosFwd + cosRight);
                    formShape = 0.90f + 0.20f * (diag * diag) + (cosFwd >= 0f ? 0.08f * cosFwd : -0.08f * (-cosFwd));
                    break;
                }
                default:
                    // Even in Circle/Protective Circle, forward hemisphere where shields/weapons orient is slightly fuller
                    formShape = 0.95f + 0.09f * cosFwd;
                    break;
            }

            // 1b. Member hull projection & directional weapon-rose reach along sector angleDeg
            float maxMemberProj = 0f;
            float roseReachSum = 0f;
            float roseWeightSum = 0f;
            for (int mIdx = 0; mIdx < alive.size(); mIdx++) {
                ShipAPI m = alive.get(mIdx);
                if (m == null || (div.detachedStragglerIds.contains(m.getId()) && div.coreMemberCount >= 1)) continue;
                float proj = (m.getLocation().x - div.centroid.x) * ux + (m.getLocation().y - div.centroid.y) * uy;
                if (proj > maxMemberProj) {
                    maxMemberProj = proj;
                }
                ShipFirepowerRose rose = rosesByShipId.get(m.getId());
                if (rose != null && rose.maxGeneralRange > 50f) {
                    float w = ShipCapabilityProfile.resolveShipFpStrength(m);
                    float relAngle = normalizeAngle360(angleDeg - rose.smoothedFacingDeg);
                    float secRange = ShipFirepowerRose.sampleArrayAtRelAngle(rose.generalRange, relAngle);
                    float ratio = clampVal(secRange / Math.max(250f, rose.maxGeneralRange), 0.55f, 1.15f);
                    roseReachSum += ratio * w;
                    roseWeightSum += w;
                }
            }
            float memberReachBoost = clampVal(maxMemberProj / Math.max(350f, baseInner), 0f, 0.22f);
            float weaponArcFactor = (roseWeightSum > 0f) ? (roseReachSum / roseWeightSum) : 1.0f;
            float sectorAnisotropy = clampVal(formShape * (0.78f + 0.22f * weaponArcFactor) + memberReachBoost * 0.5f, 0.72f, 1.26f);

            // 2. Directional Pinching by Opposing Enemy Space & Enemy Firepower (and bolstering by Friendly Space)
            float enemyPinch = 0f;
            if (enemyGroup != null) {
                for (DivisionState eDiv : enemyGroup.divisions) {
                    if (eDiv == null || !eDiv.isCombatEffective()) continue;
                    if (!OtcAI.isDivisionVisibleToSide(engine, div.side, eDiv)) continue;
                    Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, eDiv);
                    float dx = ePos.x - div.centroid.x;
                    float dy = ePos.y - div.centroid.y;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    float eDomR = Math.max(650f, eDiv.spaceDominatedRadius);
                    float maxDist = basePot + eDomR;
                    if (d < maxDist && d > 1f) {
                        float cosAlign = (dx * ux + dy * uy) / d;
                        if (cosAlign > 0.05f) {
                            float overlap = clampVal(1.0f - (d / Math.max(400f, maxDist)), 0f, 1f);
                            float weightRatio = eDiv.spaceDominanceWeight
                                    / Math.max(8f, div.spaceDominanceWeight + eDiv.spaceDominanceWeight * 0.85f);
                            enemyPinch += overlap * weightRatio * (cosAlign * cosAlign) * 0.58f;
                        }
                    }
                }
            }

            float friendlyBolster = 0f;
            if (myGroup != null) {
                for (DivisionState peer : myGroup.divisions) {
                    if (peer == null || peer == div || !peer.isAbleCombatEffective()) continue;
                    float dx = peer.centroid.x - div.centroid.x;
                    float dy = peer.centroid.y - div.centroid.y;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    float maxDist = baseDom + peer.spaceDominatedRadius;
                    if (d < maxDist && d > 1f) {
                        float cosAlign = (dx * ux + dy * uy) / d;
                        if (cosAlign > 0.15f) {
                            float overlap = clampVal(1.0f - (d / Math.max(400f, maxDist)), 0f, 1f);
                            friendlyBolster += overlap * (cosAlign * cosAlign) * 0.14f;
                        }
                    }
                }
            }

            float netPinchMult = clampVal(1.0f - clampVal(enemyPinch, 0f, 0.52f) + clampVal(friendlyBolster, 0f, 0.14f), 0.46f, 1.14f);
            float innerPinchMult = clampVal(1.0f - clampVal(enemyPinch * 0.55f, 0f, 0.34f) + clampVal(friendlyBolster * 0.5f, 0f, 0.08f), 0.62f, 1.12f);

            // Couple Potential Space to directional Time freedom in this sector so pinned sectors cannot project Potential Space
            float dirTimeR = div.directionalTimeInitialized ? div.directionalTimeRadii[s] : div.effectiveTimeRadius;
            float timeRatio = clampVal(dirTimeR / Math.max(250f, div.freeTimeRadius), 0.20f, 1.05f);

            float secIn = baseInner * sectorAnisotropy * innerPinchMult;
            float secDom = Math.max(secIn + 110f, baseDom * sectorAnisotropy * netPinchMult);
            float secPot = Math.max(secDom + 110f, secDom + (basePot - baseDom) * sectorAnisotropy * netPinchMult * (0.45f + 0.55f * timeRatio));

            rawIn[s] = secIn;
            rawDom[s] = secDom;
            rawPot[s] = secPot;
        }

        // 3. Circular 3-tap angular smoothing + temporal EMA + Map-Edge Boundary Pinching
        for (int s = 0; s < nDirs; s++) {
            int prev = (s + nDirs - 1) % nDirs;
            int next = (s + 1) % nDirs;
            float smIn = 0.24f * rawIn[prev] + 0.52f * rawIn[s] + 0.24f * rawIn[next];
            float smDom = 0.24f * rawDom[prev] + 0.52f * rawDom[s] + 0.24f * rawDom[next];
            float smPot = 0.24f * rawPot[prev] + 0.52f * rawPot[s] + 0.24f * rawPot[next];

            float angleDeg = s * (360f / nDirs);
            double rad = Math.toRadians(angleDeg);
            float edgeDist = computeDirectionalBoundaryClearance(
                    engine, div.centroid, (float) Math.cos(rad), (float) Math.sin(rad), 110f);

            smIn = pinchRadiusByBoundary(smIn, Math.max(60f, edgeDist - 40f));
            smDom = Math.max(smIn + 25f, pinchRadiusByBoundary(smDom, Math.max(85f, edgeDist - 20f)));
            smPot = Math.max(smDom + 25f, pinchRadiusByBoundary(smPot, edgeDist));

            if (!div.directionalSpaceInitialized) {
                div.directionalSpaceInnerRadii[s] = smIn;
                div.directionalSpaceDominatedRadii[s] = smDom;
                div.directionalSpacePotentialRadii[s] = smPot;
            } else {
                div.directionalSpaceInnerRadii[s] = div.directionalSpaceInnerRadii[s] * 0.55f + smIn * 0.45f;
                div.directionalSpaceDominatedRadii[s] = div.directionalSpaceDominatedRadii[s] * 0.55f + smDom * 0.45f;
                div.directionalSpacePotentialRadii[s] = div.directionalSpacePotentialRadii[s] * 0.55f + smPot * 0.45f;
            }
        }
        div.directionalSpaceInitialized = true;
    }

    public static float getDirectionalSpaceInnerRadius(DivisionState div, float worldAngleDeg) {
        if (div == null) return 0f;
        if (!div.directionalSpaceInitialized) {
            return Math.max(100f, div.spaceInnerRadius);
        }
        return sampleDirectionalDivisionArray(div.directionalSpaceInnerRadii, worldAngleDeg);
    }

    public static float getDirectionalSpaceDominatedRadius(DivisionState div, float worldAngleDeg) {
        if (div == null) return 0f;
        if (!div.directionalSpaceInitialized) {
            return Math.max(200f, div.spaceDominatedRadius);
        }
        return sampleDirectionalDivisionArray(div.directionalSpaceDominatedRadii, worldAngleDeg);
    }

    public static float getDirectionalSpacePotentialRadius(DivisionState div, float worldAngleDeg) {
        if (div == null) return 0f;
        if (!div.directionalSpaceInitialized) {
            return Math.max(300f, div.spacePotentialRadius);
        }
        return sampleDirectionalDivisionArray(div.directionalSpacePotentialRadii, worldAngleDeg);
    }

    static float sampleDirectionalDivisionArray(float[] arr, float worldAngleDeg) {
        int nDirs = DivisionState.TIME_DIRECTIONS;
        float norm = normalizeAngle360(worldAngleDeg);
        float exactIdx = norm / (360f / nDirs);
        int i0 = ((int) Math.floor(exactIdx)) % nDirs;
        int i1 = (i0 + 1) % nDirs;
        float frac = exactIdx - (float) Math.floor(exactIdx);
        float w = 0.5f * (1.0f - (float) Math.cos(frac * Math.PI));
        return arr[i0] * (1.0f - w) + arr[i1] * w;
    }

    public static float getMapHalfWidth(CombatEngineAPI engine) {
        if (engine == null || engine.getMapWidth() <= 100f) return 9000f;
        return engine.getMapWidth() * 0.5f;
    }

    public static float getMapHalfHeight(CombatEngineAPI engine) {
        if (engine == null || engine.getMapHeight() <= 100f) return 9000f;
        return engine.getMapHeight() * 0.5f;
    }

    /**
     * Exact ray distance from {@code pos} along unit direction {@code (ux, uy)} to the combat map boundary
     * (inset by {@code margin}).
     */
    public static float computeDirectionalBoundaryClearance(
            CombatEngineAPI engine,
            Vector2f pos,
            float ux,
            float uy,
            float margin) {
        if (pos == null) return 99999f;
        float halfW = Math.max(1200f, getMapHalfWidth(engine) - margin);
        float halfH = Math.max(1200f, getMapHalfHeight(engine) - margin);
        float tx = 99999f;
        if (ux > 1e-4f) {
            tx = (halfW - pos.x) / ux;
        } else if (ux < -1e-4f) {
            tx = (-halfW - pos.x) / ux;
        }
        float ty = 99999f;
        if (uy > 1e-4f) {
            ty = (halfH - pos.y) / uy;
        } else if (uy < -1e-4f) {
            ty = (-halfH - pos.y) / uy;
        }
        return Math.max(40f, Math.min(tx, ty));
    }

    /**
     * Smoothly pinches an unconstrained radius {@code radius} against directional map-edge clearance {@code edgeClearance}
     * so Time, Space, Firepower, and Convergence envelopes compress organically against the map walls and never spill past.
     */
    public static float pinchRadiusByBoundary(float radius, float edgeClearance) {
        if (radius <= 1f) return 0f;
        if (edgeClearance >= radius * 1.35f) return radius;
        float effEdge = Math.max(35f, edgeClearance);
        float scaledR = radius * 0.82f;
        float softPinch = (radius * effEdge) / (float) Math.sqrt(scaledR * scaledR + effEdge * effEdge);
        return Math.min(effEdge, softPinch);
    }

    /**
     * Returns [0..1] measuring how strongly {@code pos} is pinched against the nearest map edge or corner
     * relative to {@code referenceRadius}.
     */
    public static float computeEdgeProximityPinch(CombatEngineAPI engine, Vector2f pos, float referenceRadius) {
        if (pos == null) return 0f;
        float halfW = getMapHalfWidth(engine);
        float halfH = getMapHalfHeight(engine);
        float clearX = Math.max(0f, halfW - Math.abs(pos.x));
        float clearY = Math.max(0f, halfH - Math.abs(pos.y));
        float ref = Math.max(900f, referenceRadius * 0.85f);
        float pinchX = clearX < ref ? (1.0f - clearX / ref) : 0f;
        float pinchY = clearY < ref ? (1.0f - clearY / ref) : 0f;
        // Corner pinch is stronger than a single edge pinch because two orthogonal escape axes are blocked
        return clampVal(Math.max(pinchX, pinchY) + 0.45f * Math.min(pinchX, pinchY), 0f, 1.0f);
    }

    /**
     * Evaluates the tactical value of map edges at candidate station {@code cand} (Item D):
     *   - When outnumbered / defensive ({@code defensivePosture == true}), anchoring with a map edge behind our division
     *     (at 850u..2100u clearance, with the enemy inward toward the map center) is a SAFE ZONE because the map edge
     *     blocks rear flanking/encirclement and allows a balled-up fleet to concentrate fire in a narrow forward arc!
     *   - Conversely, getting wedged between an enemy division and a map edge on the offensive (< 850u from the wall
     *     or with the enemy blocking our inward escape) is heavily penalized.
     */
    public static float evaluateMapEdgeStationScore(
            CombatEngineAPI engine,
            DivisionState myDiv,
            Vector2f enemyFocusPos,
            Vector2f cand,
            boolean defensivePosture) {
        if (cand == null) return 0f;
        float halfW = getMapHalfWidth(engine);
        float halfH = getMapHalfHeight(engine);
        float clearX = halfW - Math.abs(cand.x);
        float clearY = halfH - Math.abs(cand.y);
        float minClear = Math.min(clearX, clearY);

        // Hard safety margin: never hug closer than 650u to the raw map boundary
        if (minClear < 650f) {
            return -(650f - minClear) * 1.15f;
        }
        if (minClear > 2400f) {
            return 0f;
        }

        // Outward normal pointing toward the nearest map wall
        float wallNx = 0f;
        float wallNy = 0f;
        if (clearX < clearY) {
            wallNx = cand.x >= 0f ? 1f : -1f;
        } else {
            wallNy = cand.y >= 0f ? 1f : -1f;
        }

        float enemyDotWall = 0f;
        if (enemyFocusPos != null) {
            float edx = enemyFocusPos.x - cand.x;
            float edy = enemyFocusPos.y - cand.y;
            float eLen = (float) Math.sqrt(edx * edx + edy * edy);
            if (eLen > 1f) {
                // Negative when enemy is toward map center and the map wall is BEHIND our candidate station
                enemyDotWall = (edx * wallNx + edy * wallNy) / eLen;
            }
        }

        if (defensivePosture && enemyDotWall <= -0.20f) {
            // Safe defensive bastion zone: back to the map edge (900u..2000u clearance), facing inward toward the attacker!
            float sweetSpot = 1.0f - Math.abs(minClear - 1350f) / 1050f;
            return Math.max(0f, sweetSpot) * (-enemyDotWall) * 260f;
        }

        // If enemy is between us and the map center (enemyDotWall > 0.15f) or we are flanking into a tight wall gap (< 1250u),
        // penalize getting pinched against the map edge!
        if (minClear < 1350f) {
            float trapFactor = (1350f - minClear) / 700f;
            float trappedByEnemyMult = (enemyDotWall < -0.15f) ? 1.35f : 0.75f;
            return -trapFactor * 195f * trappedByEnemyMult;
        }
        return 0f;
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
                float bearing = (float) Math.toDegrees(Math.atan2(point.y - d.centroid.y, point.x - d.centroid.x));
                float inR = getDirectionalSpaceInnerRadius(d, bearing);
                float domR = getDirectionalSpaceDominatedRadius(d, bearing);
                float potR = getDirectionalSpacePotentialRadius(d, bearing);
                if (dist < potR) {
                    float w = (dist <= inR) ? 1.0f
                            : (dist <= domR
                            ? (0.55f + 0.45f * (1f - (dist - inR) / Math.max(100f, domR - inR)))
                            : (0.55f * (1f - (dist - domR) / Math.max(100f, potR - domR))));
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
                float bearing = (float) Math.toDegrees(Math.atan2(point.y - ePos.y, point.x - ePos.x));
                float potR = Math.max(950f, getDirectionalSpacePotentialRadius(e, bearing));
                float domR = Math.max(680f, getDirectionalSpaceDominatedRadius(e, bearing));
                float inR = Math.max(380f, getDirectionalSpaceInnerRadius(e, bearing));
                float domW = Math.max(4f, e.spaceDominanceWeight);
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

        // 1. Temporal Reachability Coincidence (how cleanly myDiv's directional effectiveTimeRadius + weapon/strike-craft reach covers ePos)
        float bearingToEnemy = (float) Math.toDegrees(Math.atan2(ePos.y - myDiv.centroid.y, ePos.x - myDiv.centroid.x));
        float dirTimeReach = getDirectionalTimeRadius(myDiv, bearingToEnemy);
        float strikeCraftReachBonus = myDiv.hasCarrierCapability ? 650f : 0f;
        float reachHorizon = Math.max(900f, dirTimeReach + myDiv.avgEngageRange * 0.75f + strikeCraftReachBonus);
        float timeCoincidence = clampVal(1.35f - (dist / Math.max(600f, reachHorizon)), 0.15f, 1.35f);

        // 2. Target Pinning, Map-Edge Compression & Space Seam Vulnerability (Items D, E, F)
        float enemyEdgePinch = computeEdgeProximityPinch(engine, ePos, enemyDiv.freeTimeRadius);
        float enemyPinnedBonus = 1.0f + enemyDiv.engagementLevel * 0.85f + enemyEdgePinch * 0.35f;
        float seamVulnerability = enemyDiv.isWeakPoint ? 1.52f : (enemyDiv.isStrongpoint ? 0.68f : 1.08f);
        if (myDiv.hasCarrierCapability && !enemyDiv.isScreenCapable) {
            seamVulnerability *= 1.22f;
        }
        // Discount retreating ("unable") enemy divisions while able enemy divisions remain on the field
        float unableTargetMult = 1.0f;
        if (enemyDiv.isUnableDivision || enemyDiv.retreatingFraction >= 0.5f) {
            unableTargetMult = (myGroup != null && myGroup.currentPhase == DivisionModel.BattlePhase.GENERAL_CHASE) ? 0.85f : 0.35f;
        }

        // 3. Firepower / Flux Collapse Multiplier
        float fluxCollapseMult = 1.0f + enemyDiv.avgFluxLevel * 1.45f + (1.0f - enemyDiv.avgHullLevel) * 0.90f;
        float blindSpot = evaluateBlindSpotAdvantage(engine, myDiv.side, enemyDiv, myDiv.centroid);

        // 4. Multi-Division Phase-Locked Orthogonal Convergence (reward ~90 deg L/U pincer angles & focus fire)
        float pincerSynergy = 1.0f;
        if (myGroup != null) {
            if (enemyDiv == myGroup.primaryThreatEnemyDivision) {
                pincerSynergy += 0.28f;
            }
            float myBearing = (float) Math.toDegrees(Math.atan2(myDiv.centroid.y - ePos.y, myDiv.centroid.x - ePos.x));
            for (DivisionState peer : myGroup.divisions) {
                if (peer == null || peer == myDiv || !peer.isAbleCombatEffective()) continue;
                if (peer.targetEnemyDivision == enemyDiv || peer == myGroup.mainEffortDivision) {
                    float peerBearing = (float) Math.toDegrees(Math.atan2(peer.centroid.y - ePos.y, peer.centroid.x - ePos.x));
                    float angDiff = Math.abs(shortestAngleDiffDeg(myBearing, peerBearing));
                    if (angDiff >= 22f && angDiff <= 68f) {
                        pincerSynergy += 0.38f;
                    } else if (angDiff < 22f) {
                        pincerSynergy += 0.24f;
                    } else {
                        pincerSynergy += 0.10f;
                    }
                }
            }
        }

        // Multiplicative phase-space product with bounded FP base so Weak Points & high-flux targets are prioritized
        float visFp = OtcAI.getVisibleFpForSide(engine, myDiv.side, enemyDiv);
        float baseValue = Math.min(440f, 165f + visFp * 4.2f) + blindSpot * 0.95f;
        return baseValue * timeCoincidence * enemyPinnedBonus * seamVulnerability * fluxCollapseMult
                * Math.min(1.85f, pincerSynergy) * unableTargetMult;
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

        PointFirepowerEval prefEval = evaluatePoint(engine, myDiv.side, preferredPos, shieldArc);
        if (prefEval.cordonIndex < 0.18f && prefEval.effectiveDanger < 120f) {
            outStation.set(preferredPos);
            return;
        }

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
                        if (sep >= 24f && sep <= 58f) {
                            crossfireSynergy += 220f;
                        } else if (sep < 16f) {
                            crossfireSynergy -= 120f; // avoid stacking into the exact same firing lane
                        } else if (sep > 72f) {
                            crossfireSynergy -= (sep - 72f) * 4.5f; // avoid isolating divisions beyond mutual support range
                        }
                    }
                }
            }

            // Time reachability: prefer stations inside myDiv's directional effectiveTimeRadius
            float distFromDiv = distance(cand, myDiv.centroid);
            float bearingFromDiv = (float) Math.toDegrees(Math.atan2(cand.y - myDiv.centroid.y, cand.x - myDiv.centroid.x));
            float dirTimeR = getDirectionalTimeRadius(myDiv, bearingFromDiv);
            float timePenalty = (distFromDiv <= dirTimeR)
                    ? (distFromDiv * 0.05f)
                    : (dirTimeR * 0.05f + (distFromDiv - dirTimeR) * 0.22f);

            boolean defensivePosture = (myGroup != null && myGroup.forceRatio < 0.85f) || myDiv.localForceRatio < 0.82f;
            float edgeTacticalScore = evaluateMapEdgeStationScore(engine, myDiv, arcCenter, cand, defensivePosture);

            float score = blindSpot * 1.45f
                    + crossfireSynergy
                    + netSpace * 1.65f
                    + fpEval.friendlyShortDot * 0.12f
                    + edgeTacticalScore
                    - fpEval.effectiveDanger * 0.65f
                    - fpEval.cordonIndex * 640f
                    - timePenalty * 0.65f
                    - Math.abs(offsetFraction) * 160f;
            if (myDiv.avgFluxLevel >= 0.40f || myDiv.maxMemberFluxLevel >= 0.60f) {
                score -= fpEval.incomingAlpha * 0.45f;
            }

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

    static float clampVal(float v, float min, float max) {
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
     * Delegates screen-space Firepower Rose rendering to {@link TsfcWarroomRenderer}.
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
        TsfcWarroomRenderer.renderFirepowerRosesScreenSpace(
                engine, scale, offsetX, offsetY, fade, playerGroup, alliedAiGroup, enemyGroup);
    }

    /**
     * Delegates screen-space Time, Space, and Convergence rendering to {@link TsfcWarroomRenderer}.
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
        TsfcWarroomRenderer.renderTimeSpaceAndConvergenceScreenSpace(
                engine, scale, offsetX, offsetY, fade, playerGroup, alliedAiGroup, enemyGroup);
    }

    public static void renderToggleButtonAndLegendUI(float fade) {
        TsfcWarroomRenderer.renderToggleButtonAndLegendUI(fade);
    }

    public static void renderToggleButtonAndLegendUI(CombatEngineAPI engine, float fade) {
        TsfcWarroomRenderer.renderToggleButtonAndLegendUI(engine, fade);
    }

    public static String formatAiShipCallsignOnly(ShipAPI ship) {
        return TsfcWarroomRenderer.formatAiShipCallsignOnly(ship);
    }

    // =========================================================================
    // Math & Weapon Helpers
    // =========================================================================

    static boolean isUsableCombatWeapon(WeaponAPI w) {
        if (w == null || w.isDecorative()) return false;
        WeaponType type = w.getType();
        return type != WeaponType.DECORATIVE
                && type != WeaponType.LAUNCH_BAY
                && type != WeaponType.SYSTEM
                && type != WeaponType.STATION_MODULE;
    }

    static float weaponSizeWeight(WeaponSize size) {
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

    static float blendAsymmetric(float current, float target, float alphaRise, float alphaFall) {
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

    static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // =========================================================================
    // Voidstrike Instantiation, Multi-Division Merging & Lifecycle Tracking
    // (Delegated to VoidstrikeCoordinator)
    // =========================================================================

    public static List<VoidstrikeRecord> getActiveVoidstrikes() {
        return VoidstrikeCoordinator.getActiveVoidstrikes();
    }

    public static int getNextVoidstrikeNumberForSide(int side) {
        return VoidstrikeCoordinator.getNextVoidstrikeNumberForSide(side);
    }

    public static void refreshVoidstrikesForRender(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup,
            float now) {
        VoidstrikeCoordinator.refreshVoidstrikesForRender(
                engine, playerGroup, alliedAiGroup, enemyGroup, now);
    }

    public static void updateVoidstrikes(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup,
            float now) {
        VoidstrikeCoordinator.updateVoidstrikes(
                engine, playerGroup, alliedAiGroup, enemyGroup, now);
    }
}
