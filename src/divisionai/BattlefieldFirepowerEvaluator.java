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
import divisionai.DivisionModel.DivisionIntentType;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionModel.ShipRankState;
import divisionai.DivisionModel.TacticalZoneType;
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
    // 0.10s TSFC keyframe cadence & 2.0s maximum keyframe age (keyframes older than 2.0s are binned/discarded)
    public static final float TSFC_EVAL_INTERVAL = 0.10f;
    public static final float TSFC_KEYFRAME_MAX_AGE = 2.0f;

    // Render-pass caches to avoid recomputing O(D^2 * 24) telemetry or copying FleetData lists every 60 FPS frame
    static CombatEngineAPI lastTimeSpaceConvEngine = null;
    static CombatEngineAPI lastRoseUpdateEngine = null;
    static float lastTimeSpaceConvUpdateTime = -100f;
    static float lastRoseUpdateTime = -100f;
    static int lastTimeSpaceConvDivCountSig = -1;

    // Sliding opacity battle-high watermarks (track highest values seen in the current battle)
    static float battleMaxShipSustainedPeak = 220f;
    static float battleMaxShipShortDotPeak = 300f;
    static float battleMaxShipAlphaPeak = 480f;
    static float battleMaxShipCombinedPower = 300f;
    static float battleMaxDivisionSpaceDominance = 30f;
    static float battleMaxDivisionFreeTimeRadius = 1200f;
    static float battleMaxDivisionConvergenceScore = 25f;
    static int battleActiveFirepowerUnitCount = 1;
    static int battleActiveDivisionCount = 1;

    // Asymmetric temporal stabilization time constants (seconds) tuned for 0.10s keyframe sampling
    static final float TAU_HEADING_SMOOTH = 0.22f;
    static final float TAU_RISE = 0.24f;
    static final float TAU_FALL = 1.15f;
    static final float TAU_VENT_DROP = 0.14f;

    // Tactical Time Horizon for Division Mobility (seconds)
    public static final float TIME_HORIZON_SECONDS = 18.0f;

    public static final float STICKY_ENEMY_VISIBILITY_GRACE_SECONDS = 2.75f;
    static final Map<String, Float> enemyShipLastSeenTimeById = new HashMap<>();
    static final Map<String, Vector2f> smoothedEnemyVisualCentroidByDivId = new HashMap<>();

    static BattleGroupState activePlayerGroupRef = null;
    static BattleGroupState activeAlliedGroupRef = null;
    static BattleGroupState activeEnemyGroupRef = null;

    static final Map<String, ShipFirepowerRose> rosesByShipId = new HashMap<>();
    static final Map<String, DivisionState> stationBastionsByShipId = new HashMap<>();

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
        FireClassification cached = weaponFireClassCache.get(w);
        if (cached != null) return cached;
        FireClassification computed = computeWeaponFireClassUncached(w);
        weaponFireClassCache.put(w, computed);
        return computed;
    }

    private static FireClassification computeWeaponFireClassUncached(WeaponAPI w) {
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

        // 0.10s Keyframe timestamps & previous-keyframe buffers (anything > 2.0s old is thrown into the bin)
        public float prevKeyframeTime = -100f;
        public float currKeyframeTime = -100f;
        public float prevFacingDeg;
        public float prevReadinessMult = 1f;
        public float prevPeakShortDot = 1f;
        public float prevPeakAlpha = 1f;
        public float prevPeakSustained = 1f;
        public final float[] prevAlpha = new float[NUM_SECTORS];
        public final float[] prevShortDot = new float[NUM_SECTORS];
        public final float[] prevSustainedDot = new float[NUM_SECTORS];
        public final float[] prevPdScore = new float[NUM_SECTORS];
        public final float[] prevGeneralRange = new float[NUM_SECTORS];
        public final float[] prevSustainedRange = new float[NUM_SECTORS];
        public final float[] prevShortRange = new float[NUM_SECTORS];
        public final float[] prevAlphaRange = new float[NUM_SECTORS];
        public final float[] prevPdRange = new float[NUM_SECTORS];

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
            float f = normalizeAngle360(ship.getFacing());
            this.smoothedFacingDeg = f;
            this.prevFacingDeg = f;
        }

        /**
         * Returns the C1 smoothstep keyframe interpolation factor in [0.0, 1.0] at {@code renderNow},
         * or -1.0f if this rose is uninitialized or its latest keyframe is older than {@link #TSFC_KEYFRAME_MAX_AGE}
         * (2.0 seconds — thrown into the bin).
         */
        public float computeKeyframeBlendFactor(float renderNow) {
            if (!initialized || currKeyframeTime < -50f) return -1f;
            float elapsedSinceCurr = renderNow - currKeyframeTime;
            if (elapsedSinceCurr > TSFC_KEYFRAME_MAX_AGE) {
                return -1f;
            }
            float span = currKeyframeTime - prevKeyframeTime;
            if (span <= 1e-4f || span > TSFC_KEYFRAME_MAX_AGE) {
                return 1.0f;
            }
            float u = clampVal(elapsedSinceCurr / span, 0.0f, 1.0f);
            return u;
        }

        public float getInterpolatedFacingDeg(float kfBlend) {
            if (kfBlend >= 0.999f) return smoothedFacingDeg;
            if (kfBlend <= 0.001f) return prevFacingDeg;
            float diff = shortestAngleDiffDeg(prevFacingDeg, smoothedFacingDeg);
            return normalizeAngle360(prevFacingDeg + diff * kfBlend);
        }

        static float sampleInterpolatedArrayAtRelAngle(float[] prevArr, float[] currArr, float kfBlend, float relAngleDeg) {
            float exactIdx = (relAngleDeg / SECTOR_DEG);
            int idx0 = ((int) Math.floor(exactIdx)) % NUM_SECTORS;
            if (idx0 < 0) idx0 += NUM_SECTORS;
            int idx1 = (idx0 + 1) % NUM_SECTORS;
            float frac = exactIdx - (float) Math.floor(exactIdx);
            float v0 = prevArr[idx0] + (currArr[idx0] - prevArr[idx0]) * kfBlend;
            float v1 = prevArr[idx1] + (currArr[idx1] - prevArr[idx1]) * kfBlend;
            return v0 * (1f - frac) + v1 * frac;
        }

        static float sampleInterpolatedRangeArrayAtRelAngle(float[] prevArr, float[] currArr, float kfBlend, float relAngleDeg) {
            float exactIdx = (relAngleDeg / SECTOR_DEG);
            int idx0 = ((int) Math.floor(exactIdx)) % NUM_SECTORS;
            if (idx0 < 0) idx0 += NUM_SECTORS;
            int idx1 = (idx0 + 1) % NUM_SECTORS;
            float frac = exactIdx - (float) Math.floor(exactIdx);
            float r0 = prevArr[idx0] + (currArr[idx0] - prevArr[idx0]) * kfBlend;
            float r1 = prevArr[idx1] + (currArr[idx1] - prevArr[idx1]) * kfBlend;
            if (r0 > 10f && r1 <= 10f) return r0;
            if (r1 > 10f && r0 <= 10f) return r1;
            return r0 * (1f - frac) + r1 * frac;
        }

        static float sampleRangeArrayAtRelAngle(float[] arr, float relAngleDeg) {
            float exactIdx = (relAngleDeg / SECTOR_DEG);
            int idx0 = ((int) Math.floor(exactIdx)) % NUM_SECTORS;
            if (idx0 < 0) idx0 += NUM_SECTORS;
            int idx1 = (idx0 + 1) % NUM_SECTORS;
            float frac = exactIdx - (float) Math.floor(exactIdx);
            float r0 = arr[idx0];
            float r1 = arr[idx1];
            if (r0 > 10f && r1 <= 10f) return r0;
            if (r1 > 10f && r0 <= 10f) return r1;
            return r0 * (1f - frac) + r1 * frac;
        }




        /** Interpolates Short DoT score at a world bearing (degrees). */
        public float sampleShortDotAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(shortDot, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
        }

        /** Interpolates Alpha Strike score at a world bearing (degrees). */
        public float sampleAlphaAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(alpha, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
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
        TsfcWarroomRenderer.resetForNewBattle();
        VoidstrikeCoordinator.resetForNewBattle();
        DivisionOtcCommandManager.resetAlphaCoreAndFlagshipStateForNewBattle();
        enemyShipLastSeenTimeById.clear();
        smoothedEnemyVisualCentroidByDivId.clear();
        activePlayerGroupRef = null;
        activeAlliedGroupRef = null;
        activeEnemyGroupRef = null;
        rosesByShipId.clear();
        stationBastionsByShipId.clear();
        lastTimeSpaceConvEngine = null;
        lastRoseUpdateEngine = null;
        lastTimeSpaceConvUpdateTime = -100f;
        lastRoseUpdateTime = -100f;
        lastTimeSpaceConvDivCountSig = -1;
        battleMaxShipSustainedPeak = 220f;
        battleMaxShipShortDotPeak = 300f;
        battleMaxShipAlphaPeak = 480f;
        battleMaxShipCombinedPower = 300f;
        battleMaxDivisionSpaceDominance = 30f;
        battleMaxDivisionFreeTimeRadius = 1200f;
        battleMaxDivisionConvergenceScore = 25f;
        battleActiveFirepowerUnitCount = 1;
        battleActiveDivisionCount = 1;
        weaponFireClassCache.clear();
        weaponMaxDirectRangeCache.clear();
        shipStaticWeaponProfileCache.clear();
        shipStaticLiveMaxFpCache.clear();
    }

    public static float getBattleMaxShipSustainedPeak() {
        return battleMaxShipSustainedPeak;
    }

    public static float getBattleMaxShipShortDotPeak() {
        return battleMaxShipShortDotPeak;
    }

    public static float getBattleMaxShipAlphaPeak() {
        return battleMaxShipAlphaPeak;
    }

    public static float getBattleMaxShipCombinedPower() {
        return battleMaxShipCombinedPower;
    }

    public static float getBattleMaxDivisionSpaceDominance() {
        return battleMaxDivisionSpaceDominance;
    }

    public static float getBattleMaxDivisionFreeTimeRadius() {
        return battleMaxDivisionFreeTimeRadius;
    }

    /**
     * Computes the sliding opacity multiplier for a single ship's Firepower Rose relative to the
     * highest firepower values seen in the battle so far, attenuated by total active firepower units
     * on the battlefield so large fleet engagements remain legible at a glance.
     */
    public static float computeShipSlidingOpacityScale(
            ShipFirepowerRose rose,
            float interpPeakSust,
            float interpPeakShort,
            float interpPeakAlpha) {
        if (rose == null) return 0.55f;
        float combined = interpPeakSust * 0.45f + interpPeakShort * 0.35f + (interpPeakAlpha * 0.35f) * 0.20f;
        float relToBattleMax = clampVal(combined / Math.max(200f, battleMaxShipCombinedPower), 0.0f, 1.0f);
        float slidingWeight = 0.38f + 0.62f * (float) Math.sqrt(relToBattleMax);
        float crowdAtten = clampVal(
                1.0f / (float) Math.sqrt(Math.max(1.0f, battleActiveFirepowerUnitCount / 14.0f)),
                0.58f,
                1.0f);
        return slidingWeight * crowdAtten;
    }

    public static float computeShipSlidingOpacityScale(ShipFirepowerRose rose) {
        if (rose == null) return 0.35f;
        return computeShipSlidingOpacityScale(
                rose, rose.peakSectorSustained, rose.peakSectorShortDot, rose.peakSectorAlpha);
    }

    /**
     * Computes the sliding opacity multiplier for a division's Space (Zone of Control) overlay
     * relative to the highest division Space dominance weight seen in the battle.
     */
    public static float computeDivisionSpaceSlidingOpacityScale(DivisionState div, float interpDomWeight) {
        if (div == null) return 0.45f;
        float rel = clampVal(interpDomWeight / Math.max(20f, battleMaxDivisionSpaceDominance), 0.0f, 1.0f);
        float slidingWeight = 0.28f + 0.72f * (float) Math.sqrt(rel);
        float crowdAtten = clampVal(
                1.0f / (float) Math.sqrt(Math.max(1.0f, battleActiveDivisionCount / 4.0f)),
                0.48f,
                1.0f);
        return slidingWeight * crowdAtten;
    }

    /**
     * Computes the sliding opacity multiplier for a division's Time & Convergence overlays
     * relative to the highest division mobility reach and dominance seen in the battle.
     */
    public static float computeDivisionTimeSlidingOpacityScale(DivisionState div, float interpFreeTimeRad) {
        if (div == null) return 0.50f;
        float rel = clampVal(interpFreeTimeRad / Math.max(600f, battleMaxDivisionFreeTimeRadius), 0.0f, 1.0f);
        float slidingWeight = 0.34f + 0.66f * (float) Math.sqrt(rel);
        float crowdAtten = clampVal(
                1.0f / (float) Math.sqrt(Math.max(1.0f, battleActiveDivisionCount / 4.0f)),
                0.48f,
                1.0f);
        return slidingWeight * crowdAtten;
    }

    public static void setActiveBattleGroups(
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup) {
        activePlayerGroupRef = playerGroup;
        activeAlliedGroupRef = alliedAiGroup;
        activeEnemyGroupRef = enemyGroup;
    }















    /**
     * Sticky fog-of-war visibility for enemy ships on the Warroom & TSFC layers:
     * once a living enemy ship is spotted by player sensors, it stays visually tracked for
     * {@link #STICKY_ENEMY_VISIBILITY_GRACE_SECONDS} after brief sensor/nebula occlusion so
     * enemy division bubbles and TSFC circles never flicker on/off from frame to frame.
     */
    public static boolean isEnemyShipVisibleSticky(CombatEngineAPI engine, ShipAPI ship, FogOfWarAPI playerFog) {
        if (ship == null || !ship.isAlive() || ship.isHulk() || ship.isExpired()) return false;
        if (playerFog == null) return true;
        float now = (engine != null) ? engine.getTotalElapsedTime(false) : 0f;
        String id = ship.getId();
        if (playerFog.isVisible(ship)) {
            if (id != null) {
                enemyShipLastSeenTimeById.put(id, now);
            }
            return true;
        }
        if (id != null && engine != null) {
            Float lastSeen = enemyShipLastSeenTimeById.get(id);
            if (lastSeen != null && now >= lastSeen && (now - lastSeen) <= STICKY_ENEMY_VISIBILITY_GRACE_SECONDS) {
                return true;
            }
        }
        return false;
    }

    public static float getEnemyShipStickyVisibilityAlpha(CombatEngineAPI engine, ShipAPI ship, FogOfWarAPI playerFog) {
        if (ship == null || !ship.isAlive() || ship.isHulk() || ship.isExpired()) return 0f;
        if (playerFog == null || playerFog.isVisible(ship)) {
            if (ship.getId() != null && engine != null) {
                enemyShipLastSeenTimeById.put(ship.getId(), engine.getTotalElapsedTime(false));
            }
            return 1.0f;
        }
        if (ship.getId() != null && engine != null) {
            Float lastSeen = enemyShipLastSeenTimeById.get(ship.getId());
            float now = engine.getTotalElapsedTime(false);
            if (lastSeen != null && now >= lastSeen) {
                float elapsed = now - lastSeen;
                if (elapsed <= STICKY_ENEMY_VISIBILITY_GRACE_SECONDS) {
                    float t = elapsed / STICKY_ENEMY_VISIBILITY_GRACE_SECONDS;
                    return Math.max(0.35f, 1.0f - t * 0.60f);
                }
            }
        }
        return 0f;
    }

    public static boolean isEnemyDivisionVisibleSticky(CombatEngineAPI engine, DivisionState div, FogOfWarAPI playerFog) {
        if (div == null) return false;
        for (ShipAPI s : div.getAliveMembers()) {
            if (isEnemyShipVisibleSticky(engine, s, playerFog)) {
                return true;
            }
        }
        return false;
    }

    public static Vector2f getSmoothedVisibleCentroidForEnemyDivision(
            CombatEngineAPI engine,
            DivisionState div,
            FogOfWarAPI playerFog) {
        if (div == null) return new Vector2f(0f, 0f);
        float sx = 0f;
        float sy = 0f;
        int count = 0;
        for (ShipAPI s : div.getAliveMembers()) {
            if (s != null && isEnemyShipVisibleSticky(engine, s, playerFog) && s.getLocation() != null) {
                sx += s.getLocation().x;
                sy += s.getLocation().y;
                count++;
            }
        }
        Vector2f raw = (count > 0)
                ? new Vector2f(sx / count, sy / count)
                : OtcAI.getVisibleCentroidForSide(engine, 0, div);
        if (div.id == null) return raw;
        Vector2f prev = smoothedEnemyVisualCentroidByDivId.get(div.id);
        if (prev == null || distance(prev, raw) > 1200f) {
            Vector2f copy = new Vector2f(raw.x, raw.y);
            smoothedEnemyVisualCentroidByDivId.put(div.id, copy);
            return copy;
        }
        prev.set(prev.x * 0.80f + raw.x * 0.20f, prev.y * 0.80f + raw.y * 0.20f);
        return prev;
    }


    public static ShipFirepowerRose getRoseForShip(ShipAPI ship) {
        if (ship == null) return null;
        return rosesByShipId.get(ship.getId());
    }

    public static int getRoseCount() {
        return rosesByShipId.size();
    }

    // Per-weapon & per-ship static caches: WeaponSpec data (damage, DPS, arc, fire classification) is immutable during a battle.
    private static final java.util.WeakHashMap<WeaponAPI, FireClassification> weaponFireClassCache = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<ShipAPI, Float> weaponMaxDirectRangeCache = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<ShipAPI, float[]> shipStaticWeaponProfileCache = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<ShipAPI, Float> shipStaticLiveMaxFpCache = new java.util.WeakHashMap<>();

    public static boolean isEligibleFirepowerUnit(ShipAPI ship) {
        if (ship == null || !ship.isAlive() || ship.isHulk() || ship.isExpired()) return false;
        if (ship.isShuttlePod() || ship.isDrone()) return false;
        if (ship.isStation() || ship.isStationModule() || ship.getParentStation() != null) {
            return hasAnyUsableCombatWeapon(ship);
        }
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

    public static boolean hasAnyUsableCombatWeapon(ShipAPI ship) {
        if (ship == null) return false;
        List<WeaponAPI> weapons = ship.getAllWeapons();
        if (weapons == null || weapons.isEmpty()) return false;
        for (int i = 0; i < weapons.size(); i++) {
            if (isUsableCombatWeapon(weapons.get(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isRootCombatStation(ShipAPI ship) {
        if (ship == null || !ship.isAlive() || ship.isHulk() || ship.isExpired()) return false;
        if (ship.isFighter() || ship.isShuttlePod() || ship.isDrone()) return false;
        if (ship.isStationModule() || ship.getParentStation() != null) return false;
        return ship.isStation();
    }

    public static boolean isStationOrModuleCombatUnit(ShipAPI ship) {
        if (ship == null || !ship.isAlive() || ship.isHulk() || ship.isExpired()) return false;
        if (ship.isFighter() || ship.isShuttlePod() || ship.isDrone()) return false;
        if (ship.isStation() || ship.isStationModule() || ship.getParentStation() != null) {
            return hasAnyUsableCombatWeapon(ship) || ship.getNumFighterBays() > 0;
        }
        return false;
    }

    public static boolean isStationBastionDivision(DivisionState div) {
        if (div == null) return false;
        if (div.id != null && div.id.startsWith("station_bastion_")) return true;
        return div.flagship != null
                && (div.flagship.isStation() || div.flagship.isStationModule() || div.flagship.getParentStation() != null);
    }

    // Preallocated zero-allocation scratch buffers for updateSingleShipRose (eliminates 14 float[24] allocations per ship every 0.10s)
    private static final float[] SCRATCH_RAW_ALPHA = new float[NUM_SECTORS];
    private static final float[] SCRATCH_RAW_SHORT = new float[NUM_SECTORS];
    private static final float[] SCRATCH_RAW_SUST = new float[NUM_SECTORS];
    private static final float[] SCRATCH_RAW_PD = new float[NUM_SECTORS];
    private static final float[] SCRATCH_RAW_HYBRID = new float[NUM_SECTORS];
    private static final float[] SCRATCH_RAW_GEN_RANGE = new float[NUM_SECTORS];
    private static final float[] SCRATCH_RAW_SUST_RANGE_SUM = new float[NUM_SECTORS];
    private static final float[] SCRATCH_RAW_SHORT_RANGE_SUM = new float[NUM_SECTORS];
    private static final float[] SCRATCH_RAW_ALPHA_RANGE_SUM = new float[NUM_SECTORS];
    private static final float[] SCRATCH_RAW_PD_RANGE = new float[NUM_SECTORS];
    private static final float[] SCRATCH_RAW_HYBRID_RANGE = new float[NUM_SECTORS];
    private static final float[] SCRATCH_RAW_SUST_RANGE = new float[NUM_SECTORS];
    private static final float[] SCRATCH_RAW_SHORT_RANGE = new float[NUM_SECTORS];
    private static final float[] SCRATCH_RAW_ALPHA_RANGE = new float[NUM_SECTORS];

    /**
     * Updates stabilized Polar Firepower Roses for all active ships and airborne strike-craft squadrons in the battle.
     * Called on the 0.10s TSFC keyframe cadence (and C2 evaluation cadence); keyframes older than 2.0s are thrown into the bin.
     */
    public static void updateBattlefieldRoses(CombatEngineAPI engine, float now) {
        if (engine == null) return;
        lastRoseUpdateEngine = engine;
        lastRoseUpdateTime = now;
        List<ShipAPI> ships = engine.getShips();
        if (ships != null && !ships.isEmpty()) {
            for (int i = 0, n = ships.size(); i < n; i++) {
                ShipAPI ship = ships.get(i);
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

        // Prune expired/destroyed ships and any rose whose keyframe is older than 2.0 seconds (thrown into the bin)
        int activeCount = 0;
        float liveMaxSust = 220f;
        float liveMaxShort = 300f;
        float liveMaxAlpha = 480f;
        float liveMaxComb = 300f;
        Iterator<Map.Entry<String, ShipFirepowerRose>> it = rosesByShipId.entrySet().iterator();
        while (it.hasNext()) {
            ShipFirepowerRose r = it.next().getValue();
            if (r.ship == null
                    || !r.ship.isAlive()
                    || r.ship.isHulk()
                    || r.ship.isExpired()
                    || !isEligibleFirepowerUnit(r.ship)
                    || (r.currKeyframeTime >= -50f && (now - r.currKeyframeTime) > TSFC_KEYFRAME_MAX_AGE)) {
                it.remove();
            } else {
                activeCount++;
                if (r.peakSectorSustained > liveMaxSust) {
                    liveMaxSust = r.peakSectorSustained;
                }
                if (r.peakSectorShortDot > liveMaxShort) {
                    liveMaxShort = r.peakSectorShortDot;
                }
                if (r.peakSectorAlpha > liveMaxAlpha) {
                    liveMaxAlpha = r.peakSectorAlpha;
                }
                float comb = r.peakSectorSustained * 0.45f + r.peakSectorShortDot * 0.35f + (r.peakSectorAlpha * 0.35f) * 0.20f;
                if (comb > liveMaxComb) {
                    liveMaxComb = comb;
                }
            }
        }
        battleMaxShipSustainedPeak = (liveMaxSust >= battleMaxShipSustainedPeak)
                ? liveMaxSust
                : Math.max(220f, battleMaxShipSustainedPeak * 0.96f + liveMaxSust * 0.04f);
        battleMaxShipShortDotPeak = (liveMaxShort >= battleMaxShipShortDotPeak)
                ? liveMaxShort
                : Math.max(300f, battleMaxShipShortDotPeak * 0.96f + liveMaxShort * 0.04f);
        battleMaxShipAlphaPeak = (liveMaxAlpha >= battleMaxShipAlphaPeak)
                ? liveMaxAlpha
                : Math.max(480f, battleMaxShipAlphaPeak * 0.96f + liveMaxAlpha * 0.04f);
        battleMaxShipCombinedPower = (liveMaxComb >= battleMaxShipCombinedPower)
                ? liveMaxComb
                : Math.max(300f, battleMaxShipCombinedPower * 0.96f + liveMaxComb * 0.04f);
        battleActiveFirepowerUnitCount = Math.max(1, activeCount);
    }

    static void updateSingleShipRose(ShipFirepowerRose rose, ShipAPI ship, float now) {
        // Anything past 2.0 seconds is thrown into the bin (discard stale keyframe history)
        float rawElapsed = now - rose.lastUpdateTime;
        if (rose.initialized && (rawElapsed > TSFC_KEYFRAME_MAX_AGE || rawElapsed < -1e-4f)) {
            rose.initialized = false;
        }
        boolean advancingKeyframe = rose.initialized && (now - rose.currKeyframeTime) > 1e-4f;
        if (advancingKeyframe) {
            float blendAtNow = rose.computeKeyframeBlendFactor(now);
            if (blendAtNow < 0f) blendAtNow = 1.0f;
            rose.prevFacingDeg = rose.getInterpolatedFacingDeg(blendAtNow);
            rose.prevReadinessMult = rose.prevReadinessMult + (rose.liveReadinessMult - rose.prevReadinessMult) * blendAtNow;
            rose.prevPeakShortDot = rose.prevPeakShortDot + (rose.peakSectorShortDot - rose.prevPeakShortDot) * blendAtNow;
            rose.prevPeakAlpha = rose.prevPeakAlpha + (rose.peakSectorAlpha - rose.prevPeakAlpha) * blendAtNow;
            rose.prevPeakSustained = rose.prevPeakSustained + (rose.peakSectorSustained - rose.prevPeakSustained) * blendAtNow;
            for (int s = 0; s < NUM_SECTORS; s++) {
                rose.prevAlpha[s] = rose.prevAlpha[s] + (rose.alpha[s] - rose.prevAlpha[s]) * blendAtNow;
                rose.prevShortDot[s] = rose.prevShortDot[s] + (rose.shortDot[s] - rose.prevShortDot[s]) * blendAtNow;
                rose.prevSustainedDot[s] = rose.prevSustainedDot[s] + (rose.sustainedDot[s] - rose.prevSustainedDot[s]) * blendAtNow;
                rose.prevPdScore[s] = rose.prevPdScore[s] + (rose.pdScore[s] - rose.prevPdScore[s]) * blendAtNow;
                rose.prevGeneralRange[s] = rose.prevGeneralRange[s] + (rose.generalRange[s] - rose.prevGeneralRange[s]) * blendAtNow;
                rose.prevSustainedRange[s] = rose.prevSustainedRange[s] + (rose.sustainedRange[s] - rose.prevSustainedRange[s]) * blendAtNow;
                rose.prevShortRange[s] = rose.prevShortRange[s] + (rose.shortRange[s] - rose.prevShortRange[s]) * blendAtNow;
                rose.prevAlphaRange[s] = rose.prevAlphaRange[s] + (rose.alphaRange[s] - rose.prevAlphaRange[s]) * blendAtNow;
                rose.prevPdRange[s] = rose.prevPdRange[s] + (rose.pdRange[s] - rose.prevPdRange[s]) * blendAtNow;
            }
            rose.prevKeyframeTime = rose.currKeyframeTime;
            rose.currKeyframeTime = now;
        }

        float dt = rose.initialized ? Math.max(0.02f, Math.min(TSFC_KEYFRAME_MAX_AGE, rawElapsed)) : TSFC_EVAL_INTERVAL;
        rose.lastUpdateTime = now;

        float rawFacing = normalizeAngle360(ship.getFacing());
        if (!rose.initialized) {
            rose.smoothedFacingDeg = rawFacing;
            rose.prevFacingDeg = rawFacing;
        } else {
            float diff = shortestAngleDiffDeg(rose.smoothedFacingDeg, rawFacing);
            float headingAlpha = Math.min(1.0f, dt / (TAU_HEADING_SMOOTH + dt));
            rose.smoothedFacingDeg = normalizeAngle360(rose.smoothedFacingDeg + diff * headingAlpha);
        }

        FluxTrackerAPI ft = ship.getFluxTracker();
        boolean overloadedOrVenting = false;
        float ovldVentRemaining = 0f;
        if (ft != null && ft.isOverloadedOrVenting()) {
            overloadedOrVenting = true;
            ovldVentRemaining = Math.max(ft.getOverloadTimeRemaining(), ft.getTimeToVent());
        }

        float fluxLevel = ship.getFluxLevel();
        boolean isPhaseShip = ship.isPhased()
                || ship.getPhaseCloak() != null
                || (ship.getShield() != null && ship.getShield().getType() == ShieldAPI.ShieldType.PHASE)
                || (ship.getHullSpec() != null && ship.getHullSpec().getHints() != null
                && ship.getHullSpec().getHints().contains(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.PHASE));
        float readiness;
        if (overloadedOrVenting && ovldVentRemaining > 0.6f) {
            readiness = 0.04f;
        } else if (isPhaseShip && ship.isPhased()) {
            readiness = Math.max(0.75f, 1.0f - Math.max(0f, fluxLevel - 0.80f) * 1.2f);
        } else if (fluxLevel > 0.70f) {
            readiness = Math.max(0.28f, 1.0f - (fluxLevel - 0.70f) * 2.2f);
        } else {
            readiness = 1.0f;
        }
        rose.liveReadinessMult = readiness;

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
            for (int i = 0, n = weapons.size(); i < n; i++) {
                WeaponAPI w = weapons.get(i);
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

        java.util.Arrays.fill(SCRATCH_RAW_ALPHA, 0f);
        java.util.Arrays.fill(SCRATCH_RAW_SHORT, 0f);
        java.util.Arrays.fill(SCRATCH_RAW_SUST, 0f);
        java.util.Arrays.fill(SCRATCH_RAW_PD, 0f);
        java.util.Arrays.fill(SCRATCH_RAW_HYBRID, 0f);
        java.util.Arrays.fill(SCRATCH_RAW_GEN_RANGE, 0f);
        java.util.Arrays.fill(SCRATCH_RAW_SUST_RANGE_SUM, 0f);
        java.util.Arrays.fill(SCRATCH_RAW_SHORT_RANGE_SUM, 0f);
        java.util.Arrays.fill(SCRATCH_RAW_ALPHA_RANGE_SUM, 0f);
        java.util.Arrays.fill(SCRATCH_RAW_PD_RANGE, 0f);
        java.util.Arrays.fill(SCRATCH_RAW_HYBRID_RANGE, 0f);

        float sectorReadiness = Math.max(0.55f, readiness);

        accumulateShipDirectWeaponSectors(
                ship, weapons, isPhaseShip, sectorReadiness, shipFluxSustainability,
                SCRATCH_RAW_ALPHA, SCRATCH_RAW_SHORT, SCRATCH_RAW_SUST, SCRATCH_RAW_PD, SCRATCH_RAW_HYBRID,
                SCRATCH_RAW_GEN_RANGE, SCRATCH_RAW_SUST_RANGE_SUM, SCRATCH_RAW_SHORT_RANGE_SUM, SCRATCH_RAW_ALPHA_RANGE_SUM,
                SCRATCH_RAW_PD_RANGE, SCRATCH_RAW_HYBRID_RANGE);

        accumulateShipFighterWingSectors(
                rose, ship, sectorReadiness,
                SCRATCH_RAW_ALPHA, SCRATCH_RAW_SHORT, SCRATCH_RAW_SUST, SCRATCH_RAW_PD,
                SCRATCH_RAW_GEN_RANGE, SCRATCH_RAW_SUST_RANGE_SUM, SCRATCH_RAW_SHORT_RANGE_SUM, SCRATCH_RAW_ALPHA_RANGE_SUM,
                SCRATCH_RAW_PD_RANGE);

        boolean wasColdInit = !rose.initialized;
        applySectorEmaAndUpdateRosePeaks(
                rose, dt, overloadedOrVenting, ovldVentRemaining,
                SCRATCH_RAW_ALPHA, SCRATCH_RAW_SHORT, SCRATCH_RAW_SUST, SCRATCH_RAW_PD, SCRATCH_RAW_HYBRID,
                SCRATCH_RAW_GEN_RANGE, SCRATCH_RAW_SUST_RANGE_SUM, SCRATCH_RAW_SHORT_RANGE_SUM, SCRATCH_RAW_ALPHA_RANGE_SUM,
                SCRATCH_RAW_PD_RANGE, SCRATCH_RAW_HYBRID_RANGE);

        if (wasColdInit || Math.abs(rose.currKeyframeTime - rose.prevKeyframeTime) <= 1e-4f) {
            rose.prevKeyframeTime = now;
            rose.currKeyframeTime = now;
            rose.prevFacingDeg = rose.smoothedFacingDeg;
            rose.prevReadinessMult = rose.liveReadinessMult;
            rose.prevPeakShortDot = rose.peakSectorShortDot;
            rose.prevPeakAlpha = rose.peakSectorAlpha;
            rose.prevPeakSustained = rose.peakSectorSustained;
            System.arraycopy(rose.alpha, 0, rose.prevAlpha, 0, NUM_SECTORS);
            System.arraycopy(rose.shortDot, 0, rose.prevShortDot, 0, NUM_SECTORS);
            System.arraycopy(rose.sustainedDot, 0, rose.prevSustainedDot, 0, NUM_SECTORS);
            System.arraycopy(rose.pdScore, 0, rose.prevPdScore, 0, NUM_SECTORS);
            System.arraycopy(rose.generalRange, 0, rose.prevGeneralRange, 0, NUM_SECTORS);
            System.arraycopy(rose.sustainedRange, 0, rose.prevSustainedRange, 0, NUM_SECTORS);
            System.arraycopy(rose.shortRange, 0, rose.prevShortRange, 0, NUM_SECTORS);
            System.arraycopy(rose.alphaRange, 0, rose.prevAlphaRange, 0, NUM_SECTORS);
            System.arraycopy(rose.pdRange, 0, rose.prevPdRange, 0, NUM_SECTORS);
        }
    }

    private static void accumulateShipDirectWeaponSectors(
            ShipAPI ship,
            List<WeaponAPI> weapons,
            boolean isPhaseShip,
            float readiness,
            float shipFluxSustainability,
            float[] rawAlpha,
            float[] rawShort,
            float[] rawSustained,
            float[] rawPd,
            float[] rawHybrid,
            float[] rawGenRange,
            float[] rawSustRangeWeightedSum,
            float[] rawShortRangeWeightedSum,
            float[] rawAlphaRangeWeightedSum,
            float[] rawPdRange,
            float[] rawHybridRange) {
        if (weapons == null || weapons.isEmpty()) return;
        float hullOmega = Math.abs(ship.getAngularVelocity());
        for (WeaponAPI w : weapons) {
            if (!isUsableCombatWeapon(w)) continue;
            if (w.isPermanentlyDisabled()) continue;
            float mountStatusMult = 1.0f;
            if (w.isDisabled()) {
                mountStatusMult = 0.42f;
            }
            if (w.usesAmmo() && w.getAmmo() <= 0) {
                if (w.getAmmoPerSecond() > 0f) {
                    mountStatusMult *= 0.35f;
                } else {
                    continue;
                }
            }

            float range = w.getRange();
            if (range <= 50f) continue;

            boolean isStrike = w.hasAIHint(AIHints.STRIKE);
            FireClassification fireClass = classifyWeaponFire(w);

            float pdFrac;
            float genFrac;
            float hybridFrac;
            if (fireClass == FireClassification.HYBRID) {
                pdFrac = 0.90f;
                genFrac = 0.85f;
                hybridFrac = 1.0f;
            } else if (fireClass == FireClassification.POINT_DEFENSE) {
                pdFrac = 1.0f;
                genFrac = 0.14f;
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
                float hullScale = (ship.getHullSize() == HullSize.CAPITAL_SHIP) ? 1.45f
                        : (ship.getHullSize() == HullSize.CRUISER ? 1.20f : 1.0f);
                burstDmg = sizeW * 150f * hullScale * (isStrike ? 2.2f : 1.0f);
                dps = sizeW * 95f * hullScale * (isStrike ? 1.4f : 1.0f);
                sustDps = sizeW * 75f * hullScale * (isStrike ? 0.55f : 1.0f);
            }

            DamageType dmgType = w.getDamageType();
            float typeMult = 1.0f;
            if (dmgType == DamageType.FRAGMENTATION && !isStrike) {
                typeMult = (fireClass == FireClassification.HYBRID) ? 0.82f : 0.45f;
            } else if (dmgType == DamageType.KINETIC || dmgType == DamageType.HIGH_EXPLOSIVE) {
                typeMult = 1.12f;
            }

            float wAlpha = burstDmg * typeMult * (isStrike ? 1.65f : 1.0f) * mountStatusMult;
            float wShortDot = (dps * 0.75f + (burstDmg / 4.5f) * 0.25f) * typeMult * (isStrike ? 1.25f : 1.0f) * mountStatusMult;
            float cdRem = w.getCooldownRemaining();
            if (cdRem > 0.20f && !isPhaseShip) {
                float alphaReloadMult = clampVal(1.0f - (cdRem - 0.20f) / 4.8f, 0.42f, 1.0f);
                float shortReloadMult = clampVal(1.0f - Math.max(0f, cdRem - 0.65f) / 6.5f, 0.52f, 1.0f);
                wAlpha *= alphaReloadMult;
                wShortDot *= shortReloadMult;
            }

            float ammoEndurance = 1.0f;
            if (w.usesAmmo() && w.getAmmoPerSecond() <= 0f) {
                ammoEndurance = Math.min(0.75f, Math.max(0.35f, w.getAmmo() / 16f));
            }
            float wSustainedDot = sustDps * typeMult * shipFluxSustainability * ammoEndurance * mountStatusMult;

            float arcFacingRel = normalizeAngle360(w.getArcFacing());
            float rawArc = w.getArc();
            if (rawArc <= 0f) {
                rawArc = isStrike ? 90f : 210f;
            }

            WeaponSlotAPI slot = w.getSlot();
            boolean isTurret = (slot == null) || slot.isTurret();
            float slewPad = Math.min(32f, hullOmega * 1.15f + (isTurret ? 14f : 8f));
            float halfArc = Math.min(180f, rawArc * 0.5f + slewPad * 0.5f);
            float shoulderWidth = 20f;

            for (int s = 0; s < NUM_SECTORS; s++) {
                float sectorCenterRel = s * SECTOR_DEG;
                float angDist = Math.abs(shortestAngleDiffDeg(sectorCenterRel, arcFacingRel));
                if (angDist > halfArc + shoulderWidth) continue;

                float angularWeight = (angDist <= halfArc)
                        ? 1.0f
                        : (0.5f * (1.0f + (float) Math.cos(((angDist - halfArc) / shoulderWidth) * Math.PI)));

                if (genFrac > 0f) {
                    float gw = angularWeight * genFrac * readiness;
                    float aContrib = wAlpha * gw;
                    float shContrib = wShortDot * gw;
                    float suContrib = wSustainedDot * gw;
                    rawAlpha[s] += aContrib;
                    rawShort[s] += shContrib;
                    rawSustained[s] += suContrib;
                    float effRange = range * (0.72f + 0.28f * angularWeight);
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

    private static void accumulateShipFighterWingSectors(
            ShipFirepowerRose rose,
            ShipAPI ship,
            float readiness,
            float[] rawAlpha,
            float[] rawShort,
            float[] rawSustained,
            float[] rawPd,
            float[] rawGenRange,
            float[] rawSustRangeWeightedSum,
            float[] rawShortRangeWeightedSum,
            float[] rawAlphaRangeWeightedSum,
            float[] rawPdRange) {
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
    }

    private static void applySectorEmaAndUpdateRosePeaks(
            ShipFirepowerRose rose,
            float dt,
            boolean overloadedOrVenting,
            float ovldVentRemaining,
            float[] rawAlpha,
            float[] rawShort,
            float[] rawSustained,
            float[] rawPd,
            float[] rawHybrid,
            float[] rawGenRange,
            float[] rawSustRangeWeightedSum,
            float[] rawShortRangeWeightedSum,
            float[] rawAlphaRangeWeightedSum,
            float[] rawPdRange,
            float[] rawHybridRange) {
        float[] rawSustRange = SCRATCH_RAW_SUST_RANGE;
        float[] rawShortRange = SCRATCH_RAW_SHORT_RANGE;
        float[] rawAlphaRange = SCRATCH_RAW_ALPHA_RANGE;
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

        // Items A, B & C: Compute live division firepower (discounting disabled/reloading/empty weapons, phase-exempt)
        // and Effective-Firepower-in-Range Uptime (how much of our ready firepower actually has a visible enemy in range & arc
        // vs how much enemy firepower currently has our ships in range & arc).
        updateDivisionFirepowerUptimeTelemetry(engine, div);
    }

    /**
     * Computes a ship's current live combat power (in FP-equivalent units) combining:
     *  - Live ready weapon firepower (from its ShipFirepowerRose / weapon mounts, discounting disabled, empty,
     *    and reloading weapons; phased ships are exempt from reload/cooldown suppression so their latent threat counts)
     *  - Live Effective HP (Shield HP + Armor HP + Hull HP)
     */
    public static float computeEffectiveShipCombatPower(ShipAPI ship) {
        if (ship == null || !ship.isAlive() || ship.isHulk()) return 0f;
        float baseFp = ShipCapabilityProfile.resolveShipFpStrength(ship);
        float readiness = Math.max(0.35f, ship.getHullLevel() * (1.15f - 0.5f * ship.getFluxLevel()));
        float ehpStrength = computeShipEffectiveHp(ship) / 1600f;
        float liveFp = computeShipLiveFirepowerScore(ship, false);
        float maxFp = Math.max(5f, computeShipLiveFirepowerScore(ship, true));
        // Item A: When all weapons are ready (or ship is phased), weaponReadinessMod = 1.0 (matching baseline FP calibration);
        // when weapons are reloading, disabled, or out of ammo, weaponReadinessMod scales the firepower score down toward 0.12.
        float weaponReadinessMod = clampVal(liveFp / maxFp, 0.12f, 1.0f);
        float firepowerTerm = baseFp * readiness * weaponReadinessMod;
        return 0.42f * firepowerTerm + 0.58f * ehpStrength;
    }

    /**
     * Computes a ship's live or peak weapon firepower score across all non-decorative weapons and fighter wings.
     * Reloading and disabled weapons produce less or zero firepower; phase ships are exempt from reload suppression.
     */
    public static float computeShipLiveFirepowerScore(ShipAPI ship, boolean maxCapacityOnly) {
        if (ship == null || !ship.isAlive() || ship.isHulk()) return 0f;
        boolean isPhaseShip = ship.isPhased()
                || ship.getPhaseCloak() != null
                || (ship.getShield() != null && ship.getShield().getType() == ShieldAPI.ShieldType.PHASE)
                || (ship.getHullSpec() != null && ship.getHullSpec().getHints() != null
                && ship.getHullSpec().getHints().contains(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.PHASE));

        FluxTrackerAPI ft = ship.getFluxTracker();
        boolean overloadedOrVenting = !maxCapacityOnly && ft != null && ft.isOverloadedOrVenting();
        if (overloadedOrVenting) {
            return 2f;
        }
        float fluxMult = 1.0f;
        if (!maxCapacityOnly && !isPhaseShip && ship.getFluxLevel() > 0.72f) {
            fluxMult = clampVal(1.0f - (ship.getFluxLevel() - 0.72f) * 2.4f, 0.22f, 1.0f);
        }

        float score = 0f;
        List<WeaponAPI> weapons = ship.getAllWeapons();
        if (weapons != null) {
            for (int i = 0; i < weapons.size(); i++) {
                WeaponAPI w = weapons.get(i);
                if (!isUsableCombatWeapon(w)) continue;
                if (w.isPermanentlyDisabled()) continue;
                if (!maxCapacityOnly) {
                    if (w.isDisabled()) continue;
                    if (w.usesAmmo() && w.getAmmo() <= 0) continue;
                }
                float range = w.getRange();
                if (range <= 50f) continue;

                float wScore = computeWeaponBaseAntiShipScore(w);
                if (!maxCapacityOnly && !isPhaseShip) {
                    float cdRem = w.getCooldownRemaining();
                    if (cdRem > 0.20f) {
                        float reloadMult = clampVal(1.0f - (cdRem - 0.20f) / 5.2f, 0.15f, 1.0f);
                        wScore *= reloadMult;
                    }
                }
                score += wScore * fluxMult;
            }
        }
        int bays = ship.getNumFighterBays();
        if (bays > 0) {
            score += bays * 135f * fluxMult;
        }
        return Math.max(5f, score);
    }

    /**
     * Computes how much of {@code attacker}'s ready firepower can currently bear on {@code target}
     * (within weapon range and firing arc, graded against target shields vs armor).
     */
    public static float computeShipInRangeFirepowerAgainstTarget(ShipAPI attacker, ShipAPI target) {
        if (attacker == null || target == null || !attacker.isAlive() || !target.isAlive()) return 0f;
        FluxTrackerAPI ft = attacker.getFluxTracker();
        if (ft != null && ft.isOverloadedOrVenting()) return 0f;

        boolean isPhaseShip = attacker.isPhased()
                || attacker.getPhaseCloak() != null
                || (attacker.getShield() != null && attacker.getShield().getType() == ShieldAPI.ShieldType.PHASE);
        float dist = distance(attacker.getLocation(), target.getLocation());
        int attackerBays = attacker.getNumFighterBays();
        ShipFirepowerRose attackerRose = rosesByShipId.get(attacker.getId());
        if (attackerBays <= 0 && attackerRose != null && attackerRose.initialized) {
            float maxR = Math.max(attackerRose.maxGeneralRange, attackerRose.maxPdRange);
            if (dist > maxR * 1.08f + 50f) {
                return 0f;
            }
        } else if (dist > 3400f) {
            return 0f;
        }
        float bearing = normalizeAngle360((float) Math.toDegrees(Math.atan2(
                target.getLocation().y - attacker.getLocation().y,
                target.getLocation().x - attacker.getLocation().x)));

        boolean targetShieldsActive = target.getShield() != null
                && target.getShield().getType() != ShieldAPI.ShieldType.NONE
                && target.getShield().getType() != ShieldAPI.ShieldType.PHASE
                && (target.getFluxTracker() == null || !target.getFluxTracker().isOverloadedOrVenting())
                && target.getFluxLevel() < 0.90f;

        float inRangeScore = 0f;
        List<WeaponAPI> weapons = attacker.getAllWeapons();
        if (weapons != null) {
            for (int i = 0; i < weapons.size(); i++) {
                WeaponAPI w = weapons.get(i);
                if (!isUsableCombatWeapon(w) || w.isDisabled() || w.isPermanentlyDisabled()) continue;
                if (w.usesAmmo() && w.getAmmo() <= 0) continue;
                float range = w.getRange();
                if (range <= 50f || dist > range * 1.08f) continue;

                float mountWorldFacing = normalizeAngle360(attacker.getFacing() + w.getArcFacing());
                float rawArc = w.getArc() > 0f ? w.getArc() : 210f;
                float offAxis = Math.abs(shortestAngleDiffDeg(mountWorldFacing, bearing));
                if (offAxis > rawArc * 0.5f + 22f) continue;

                if (w.hasAIHint(AIHints.STRIKE) && dist > 1300f) continue;
                DamageType dt = w.getDamageType();
                float defenseMatchMult = (dt == DamageType.KINETIC)
                        ? (targetShieldsActive ? 1.20f : 0.80f)
                        : (dt == DamageType.HIGH_EXPLOSIVE ? (targetShieldsActive ? 0.84f : 1.30f) : 1.0f);
                float wScore = computeWeaponBaseAntiShipScore(w) * defenseMatchMult;
                if (!isPhaseShip) {
                    float cdRem = w.getCooldownRemaining();
                    if (cdRem > 0.20f) {
                        wScore *= clampVal(1.0f - (cdRem - 0.20f) / 5.2f, 0.15f, 1.0f);
                    }
                }
                inRangeScore += wScore;
            }
        }
        int bays = attacker.getNumFighterBays();
        if (bays > 0 && dist <= 3200f) {
            inRangeScore += bays * 125f;
        }
        return inRangeScore;
    }

    private static void updateDivisionFirepowerUptimeTelemetry(CombatEngineAPI engine, DivisionState div) {
        List<ShipAPI> alive = div.getAliveMembers();
        if (alive.isEmpty()) {
            div.liveFirepowerScore = 0f;
            div.maxFirepowerScore = 0f;
            div.weaponReadinessRatio = 0f;
            div.inRangeFirepowerScore = 0f;
            div.firepowerInRangeUptime = 0f;
            div.incomingInRangeFirepower = 0f;
            div.firepowerExchangeRatio = 1.0f;
            return;
        }

        float sumLive = 0f;
        float sumMax = 0f;
        for (int i = 0; i < alive.size(); i++) {
            ShipAPI m = alive.get(i);
            sumLive += computeShipLiveFirepowerScore(m, false);
            sumMax += computeShipLiveFirepowerScore(m, true);
        }
        div.liveFirepowerScore = sumLive;
        div.maxFirepowerScore = Math.max(sumLive, sumMax);
        div.weaponReadinessRatio = clampVal(sumLive / Math.max(1f, div.maxFirepowerScore), 0.08f, 1.0f);

        if (engine == null) return;
        com.fs.starfarer.api.combat.CollisionGridAPI grid = engine.getAiGridShips();
        if (grid == null) grid = engine.getShipGrid();
        if (grid == null) return;

        List<ShipAPI> nearbyEnemies = new ArrayList<>();
        Iterator<Object> iter = grid.getCheckIterator(div.centroid, 6800f, 6800f);
        while (iter != null && iter.hasNext()) {
            Object obj = iter.next();
            if (!(obj instanceof ShipAPI)) continue;
            ShipAPI es = (ShipAPI) obj;
            if ((!DivisionAutoFormer.isEligibleCombatHull(es) && !isStationOrModuleCombatUnit(es))
                    || DivisionState.isShipUnable(es)) continue;
            if (es.getOwner() == div.side) continue;
            if (!OtcAI.isShipVisibleToSide(engine, div.side, es)) continue;
            nearbyEnemies.add(es);
        }

        float sumInRangeOut = 0f;
        float sumInRangeIn = 0f;
        int numAlive = alive.size();
        int numEnemies = nearbyEnemies.size();
        float[] shipOutFp = new float[numAlive];
        float[] shipInFp = new float[numAlive];
        if (numEnemies > 0) {
            // Compute each attacker->target pair's in-range firepower ONCE per tick (outFp[i][j] and inFp[j][i]).
            // Each enemy splits its fire among the ships of ours it can reach, so a ship's expected incoming fire is
            // sum_e(inFp(e->m) / targetsInReachOf(e)); comparable to its own single-target outgoing fire.
            float[][] inFpPair = new float[numEnemies][numAlive];
            int[] targetsInReach = new int[numEnemies];
            for (int j = 0; j < numEnemies; j++) {
                ShipAPI e = nearbyEnemies.get(j);
                float[] row = inFpPair[j];
                int c = 0;
                float bestIn = 0f;
                for (int i = 0; i < numAlive; i++) {
                    float inFp = computeShipInRangeFirepowerAgainstTarget(e, alive.get(i));
                    row[i] = inFp;
                    if (inFp > 0f) c++;
                    if (inFp > bestIn) bestIn = inFp;
                }
                targetsInReach[j] = Math.max(1, c);
                sumInRangeIn += bestIn;
            }
            for (int i = 0; i < numAlive; i++) {
                ShipAPI m = alive.get(i);
                float bestOut = 0f;
                float totalInOnShip = 0f;
                for (int j = 0; j < numEnemies; j++) {
                    ShipAPI e = nearbyEnemies.get(j);
                    float outFp = computeShipInRangeFirepowerAgainstTarget(m, e);
                    if (outFp > bestOut) bestOut = outFp;
                    totalInOnShip += inFpPair[j][i] / targetsInReach[j];
                }
                shipOutFp[i] = bestOut;
                shipInFp[i] = totalInOnShip;
                sumInRangeOut += bestOut;
            }
        }

        div.inRangeFirepowerScore = sumInRangeOut;
        div.incomingInRangeFirepower = sumInRangeIn;
        float instUptime = clampVal(sumInRangeOut / Math.max(1f, sumLive), 0f, 1f);
        if (div.firepowerInRangeUptime <= 0.001f) {
            div.firepowerInRangeUptime = instUptime;
        } else {
            div.firepowerInRangeUptime = div.firepowerInRangeUptime * 0.55f + instUptime * 0.45f;
        }
        div.firepowerExchangeRatio = (sumInRangeOut + 25f) / Math.max(25f, sumInRangeIn + 25f);

        updateDivisionZonesAndRankRelief(div, alive, nearbyEnemies, shipOutFp, shipInFp);
    }

    public static boolean ZONE_DEBUG = false;
    public static float zoneDbgN, zoneDbgIn, zoneDbgOut, zoneDbgCordon;

    /** Longest non-missile, non-PD weapon range on {@code s} (0 when it has none). */
    static float estimateMaxDirectWeaponRange(ShipAPI s) {
        if (s == null || s.getAllWeapons() == null) return 0f;
        Float cached = weaponMaxDirectRangeCache.get(s);
        if (cached != null) return cached;
        float best = 0f;
        for (WeaponAPI w : s.getAllWeapons()) {
            if (w == null || w.getType() == WeaponType.MISSILE) continue;
            if (w.hasAIHint(AIHints.PD) && w.getSize() == WeaponSize.SMALL) continue;
            if (w.getRange() > best) best = w.getRange();
        }
        weaponMaxDirectRangeCache.put(s, best);
        return best;
    }

    /**
     * Evaluates the T-S-F-C Tactical Zone (Safe Zone, Overmatch Band, Engagement Zone, Kill Zone) and
     * Intra-Division Rank Relief state (FRONT_ENGAGEMENT vs RELIEF_SAFE) for each member of {@code div},
     * plus samples the enemy firepower field behind {@code div} to find the exact safe relief-rank depth.
     */
    private static void updateDivisionZonesAndRankRelief(
            DivisionState div,
            List<ShipAPI> alive,
            List<ShipAPI> nearbyEnemies,
            float[] shipOutFp,
            float[] shipInFp) {

        if (alive == null || alive.isEmpty()) {
            div.inDoubleLineRelief = false;
            div.engagementZoneShipCount = 0;
            div.safeZoneShipCount = 0;
            div.engagementZoneFp = 0f;
            return;
        }

        boolean inKillZoneCordon = div.crossfireCordonIndex >= 0.45f && div.localEnemyStrength >= 10f;
        boolean fixingMission = div.isDesignatedMainEffort
                || div.currentIntent == DivisionIntentType.FORM_LINE_AND_FIX;
        float enterReliefFlux = fixingMission ? 0.65f : 0.60f;
        float enterReliefHardFlux = fixingMission ? 0.48f : 0.43f;
        float exitReliefFlux = 0.26f;

        int frontCount = 0;
        int reliefCount = 0;
        float frontFp = 0f;

        for (int i = 0; i < alive.size(); i++) {
            ShipAPI m = alive.get(i);
            if (m == null || m.getId() == null) continue;
            String id = m.getId();
            float outFp = shipOutFp[i];
            float inFp = shipInFp[i];

            if (ZONE_DEBUG && div.side == 0 && i == 0) {
                zoneDbgN++; zoneDbgIn += inFp; zoneDbgOut += outFp; if (inKillZoneCordon) zoneDbgCordon++;
            }
            TacticalZoneType zone;
            // Incoming is an upper bound (enemy fire on our other units is not excluded), so ratios are calibrated
            // against the observed ~3:1 in:out baseline of an even exchange.
            if (inKillZoneCordon || (inFp > 150f && inFp > outFp * 6.0f)) {
                zone = TacticalZoneType.KILL_ZONE;
            } else if (inFp <= 12f && outFp <= 10f) {
                zone = TacticalZoneType.SAFE_ZONE;
            } else if (outFp > 15f && inFp <= outFp * 1.2f) {
                zone = TacticalZoneType.OVERMATCH_BAND;
            } else {
                zone = TacticalZoneType.ENGAGEMENT_ZONE;
            }
            div.shipZoneTypeByShipId.put(id, zone);

            boolean ovlOrVent = m.getFluxTracker() != null && m.getFluxTracker().isOverloadedOrVenting();
            float flux = m.getFluxLevel();
            float hardFrac = (m.getFluxTracker() != null && m.getFluxTracker().getMaxFlux() > 1f)
                    ? (m.getFluxTracker().getHardFlux() / m.getFluxTracker().getMaxFlux())
                    : (flux * 0.55f);

            ShipRankState prevState = div.shipRankStateByShipId.get(id);
            ShipRankState nextState;
            if (prevState == ShipRankState.RELIEF_SAFE) {
                if (ovlOrVent || flux >= exitReliefFlux) {
                    nextState = ShipRankState.RELIEF_SAFE;
                } else {
                    nextState = ShipRankState.FRONT_ENGAGEMENT;
                }
            } else {
                boolean needsRelief = ovlOrVent
                        || flux >= enterReliefFlux
                        || hardFrac >= enterReliefHardFlux
                        || (m.getHullLevel() < 0.50f && flux >= 0.42f);
                nextState = needsRelief ? ShipRankState.RELIEF_SAFE : ShipRankState.FRONT_ENGAGEMENT;
            }
            div.shipRankStateByShipId.put(id, nextState);
            if (nextState == ShipRankState.RELIEF_SAFE) {
                reliefCount++;
            } else {
                frontCount++;
                frontFp += ShipCapabilityProfile.resolveShipFpStrength(m);
            }
        }

        div.engagementZoneShipCount = frontCount;
        div.safeZoneShipCount = reliefCount;
        div.engagementZoneFp = frontFp;

        if (inKillZoneCordon) {
            div.currentDivisionZone = TacticalZoneType.KILL_ZONE;
        } else if (div.incomingInRangeFirepower <= 15f && div.inRangeFirepowerScore <= 12f) {
            div.currentDivisionZone = TacticalZoneType.SAFE_ZONE;
        } else if (div.inRangeFirepowerScore > 25f && div.incomingInRangeFirepower <= div.inRangeFirepowerScore * 0.45f) {
            div.currentDivisionZone = TacticalZoneType.OVERMATCH_BAND;
        } else {
            div.currentDivisionZone = TacticalZoneType.ENGAGEMENT_ZONE;
        }

        // Minimum Front Rule for Defensive Rank Relief: keep a second rank as long as the division is not trapped
        // in a 3-sided U-cordon Kill Zone AND at least half the division's ships (or >= 42% of its FP) remain healthy
        // in the Front-Rank Engagement Zone. If more ships need relief than that allows, escalate to division rotation!
        int n = alive.size();
        boolean minFrontMaintained = frontCount >= Math.max(1, n / 2)
                || (frontCount >= 1 && frontFp >= div.totalFpStrength * 0.42f);
        boolean defensiveRankRelief = !inKillZoneCordon
                && !div.archetype.isCarrierArchetype()
                && div.archetype != DivisionArchetype.SERVICE_GROUP
                && n >= 2
                && reliefCount >= 1
                && minFrontMaintained;

        // Offensive Depth Column: when the division has local Space advantage (localForceRatio >= 1.30) and wants to
        // spearhead through a narrow enemy gap, stack healthy ships 1-2 wide into a deep column (Line Ahead / Wedge)
        // so the rear ships fire over the front instead of standing off to the side!
        boolean offensiveDepthColumn = !inKillZoneCordon
                && !div.archetype.isCarrierArchetype()
                && div.archetype != DivisionArchetype.SERVICE_GROUP
                && n >= 4
                && reliefCount == 0
                && div.localForceRatio >= 1.30f
                && div.currentIntent == DivisionIntentType.PRESS_HOME
                && div.avgFluxLevel < 0.42f;

        div.inDoubleLineRelief = defensiveRankRelief || offensiveDepthColumn;
        div.depthFormationMode = defensiveRankRelief ? -1 : (offensiveDepthColumn ? 1 : 0);

        // Sample the enemy firepower field along the retreat axis (-fwd) to find the exact Relief-Rank Safe Zone depth
        float retreatDirX = -div.formationHeadingX;
        float retreatDirY = -div.formationHeadingY;
        if (Math.abs(retreatDirX) < 0.01f && Math.abs(retreatDirY) < 0.01f) {
            retreatDirX = 0f;
            retreatDirY = (div.side == 0) ? -1f : 1f;
        }
        float chosenDepth = 480f;
        if (!nearbyEnemies.isEmpty() && div.centroid != null) {
            float[] sampleDepths = {420f, 520f, 620f, 720f};
            for (int dIdx = 0; dIdx < sampleDepths.length; dIdx++) {
                float candDepth = sampleDepths[dIdx];
                float sx = div.centroid.x + retreatDirX * candDepth;
                float sy = div.centroid.y + retreatDirY * candDepth;
                float enemyFireAtSample = 0f;
                for (int j = 0; j < nearbyEnemies.size(); j++) {
                    ShipAPI e = nearbyEnemies.get(j);
                    float eRange = estimateMaxDirectWeaponRange(e);
                    if (eRange <= 100f) eRange = 1050f;
                    float dx = sx - e.getLocation().x;
                    float dy = sy - e.getLocation().y;
                    if (dx * dx + dy * dy <= (eRange * 1.04f) * (eRange * 1.04f)) {
                        enemyFireAtSample += computeShipLiveFirepowerScore(e, false);
                    }
                }
                chosenDepth = candDepth;
                if (enemyFireAtSample <= Math.max(15f, div.incomingInRangeFirepower * 0.12f)) {
                    break;
                }
            }
        }
        div.reliefRankDepth = chosenDepth;
        if (div.centroid != null) {
            div.safeZonePoint.set(
                    div.centroid.x + retreatDirX * chosenDepth,
                    div.centroid.y + retreatDirY * chosenDepth);
        }

        // Cantabrian Circle (offensive / defensive) on circular formations:
        // Offensive when locally overmatching (orbit & fire), defensive when venting / absorbing heavy fire
        // (rotate exposed ships through the protected inner arc).
        boolean circularFormation = div.activeFormation == PermanentDivisionManager.DivisionFormation.CIRCLE
                || div.activeFormation == PermanentDivisionManager.DivisionFormation.PROTECTIVE_CIRCLE;
        div.cantabrianCircleActive = circularFormation && n >= 3;
        div.cantabrianOffensive = div.localForceRatio >= 1.05f && div.avgFluxLevel < 0.55f;
        div.cantabrianPhase += (div.cantabrianOffensive ? 0.055f : -0.045f);
        if (div.cantabrianPhase > 2f * Math.PI) div.cantabrianPhase -= 2f * Math.PI;
        else if (div.cantabrianPhase < 0f) div.cantabrianPhase += 2f * Math.PI;
    }

    /**
     * Item E ("Trade Space for Time" / "Tactical Retreat"):
     * Computes an offset vector (dx, dy) from {@code div.centroid} (or the current engagement line) that shifts
     * the unit's space centroid away from peak enemy firepower and L/U crossfire while keeping the unit at the outer
     * edge of its own effective gun range (0.94x..1.05x avgEngageRange) so weapons continue firing during withdrawal.
     */
    public static void computeTradeSpaceForTimeOffset(
            CombatEngineAPI engine,
            DivisionState div,
            DivisionState enemyDiv,
            BattleGroupState myGroup,
            Vector2f outOffset) {
        if (outOffset == null) return;
        outOffset.set(0f, 0f);
        if (div == null) return;

        Vector2f threatCenter = null;
        float enemyRange = 1050f;
        if (enemyDiv != null && enemyDiv.isCombatEffective()) {
            threatCenter = OtcAI.getVisibleCentroidForSide(engine, div.side, enemyDiv);
            enemyRange = Math.max(750f, enemyDiv.avgEngageRange);
        } else if (myGroup != null && myGroup.primaryThreatEnemyDivision != null
                && myGroup.primaryThreatEnemyDivision.isCombatEffective()) {
            threatCenter = OtcAI.getVisibleCentroidForSide(engine, div.side, myGroup.primaryThreatEnemyDivision);
            enemyRange = Math.max(750f, myGroup.primaryThreatEnemyDivision.avgEngageRange);
        }
        if (threatCenter == null) {
            outOffset.set(0f, (div.side == 0 ? -420f : 420f));
            return;
        }

        float dx = div.centroid.x - threatCenter.x;
        float dy = div.centroid.y - threatCenter.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) {
            dx = 0f;
            dy = (div.side == 0) ? -1f : 1f;
            dist = 1f;
        }
        float awayX = dx / dist;
        float awayY = dy / dist;
        float latX = -awayY;
        float latY = awayX;

        // Target distance: step back to the outer band of our own gun envelope (or just outside short-range enemy alpha)
        float myRange = Math.max(780f, div.avgEngageRange > 250f ? div.avgEngageRange : div.standoffRange);
        float desiredKiteDist = Math.max(myRange * 0.96f, Math.min(myRange * 1.06f, enemyRange * 0.98f));
        float radialStep = clampVal(desiredKiteDist - dist + 280f, 240f, 640f);

        // Check rear and lateral boundary clearance so we never back straight into a map corner;
        // if rear clearance is tight or we are in a crossfire cordon, slide laterally toward the open side!
        float rearClear = computeDirectionalBoundaryClearance(engine, div.centroid, awayX, awayY, 150f);
        float leftClear = computeDirectionalBoundaryClearance(engine, div.centroid, latX, latY, 150f);
        float rightClear = computeDirectionalBoundaryClearance(engine, div.centroid, -latX, -latY, 150f);

        float latSign = (leftClear >= rightClear) ? 1f : -1f;
        if (myGroup != null && myGroup.mainEffortDivision != null && myGroup.mainEffortDivision != div) {
            // Slide toward friendly Main Effort / independent mass for mutual fire support
            Vector2f anchor = (myGroup.organizationRatio < 0.65f && myGroup.friendlyIndependentFp > 15f)
                    ? myGroup.independentCentroid
                    : myGroup.mainEffortDivision.centroid;
            float toAnchor = (anchor.x - div.centroid.x) * latX + (anchor.y - div.centroid.y) * latY;
            if (Math.abs(toAnchor) > 120f) {
                latSign = Math.signum(toAnchor);
            }
        }

        float latStep = (div.crossfireCordonIndex >= 0.28f) ? 310f : 175f;
        if (rearClear < 900f) {
            float pinch = clampVal((900f - rearClear) / 700f, 0f, 0.75f);
            radialStep *= (1f - pinch * 0.65f);
            latStep += pinch * 340f;
        }
        outOffset.set(awayX * radialStep + latX * latSign * latStep, awayY * radialStep + latY * latSign * latStep);
    }

    /**
     * Item F ("Feigned Retreat"):
     * Computes the Stage-1 bait withdrawal offset (dx, dy) that lures {@code enemyDiv} forward along a corridor
     * flanked by {@code hammerDiv} (or by our own division's wings) so the enemy overextends into an alpha-strike kill zone.
     */
    public static void computeFeignedRetreatBaitOffset(
            CombatEngineAPI engine,
            DivisionState baitDiv,
            DivisionState enemyDiv,
            DivisionState hammerDiv,
            BattleGroupState myGroup,
            Vector2f outOffset) {
        if (outOffset == null) return;
        outOffset.set(0f, 0f);
        if (baitDiv == null || enemyDiv == null) return;

        Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, baitDiv.side, enemyDiv);
        float dx = baitDiv.centroid.x - ePos.x;
        float dy = baitDiv.centroid.y - ePos.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) {
            dx = 0f;
            dy = (baitDiv.side == 0) ? -1f : 1f;
            dist = 1f;
        }
        float awayX = dx / dist;
        float awayY = dy / dist;
        float latX = -awayY;
        float latY = awayX;

        // Pull back 420..560u while angling slightly away from hammerDiv so the enemy turns its side/stern to the hammer!
        float lureDepth = 480f;
        float latBias = 0f;
        if (hammerDiv != null && hammerDiv != baitDiv && hammerDiv.isCombatEffective()) {
            float hammerLat = (hammerDiv.centroid.x - baitDiv.centroid.x) * latX
                    + (hammerDiv.centroid.y - baitDiv.centroid.y) * latY;
            latBias = (hammerLat >= 0f ? -1f : 1f) * 210f;
        }
        outOffset.set(awayX * lureDepth + latX * latBias, awayY * lureDepth + latY * latBias);
    }

    /**
     * Item F Defensive Counter:
     * Evaluates visible enemy divisions for signs of a "Feigned Retreat" bait trap (an enemy unit moving away while
     * still at low flux, high hull, and high weapon readiness, especially with a second enemy division nearby on the flank).
     */
    public static void updateEnemyFeignedRetreatSuspicion(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup) {
        if (myGroup == null || enemyGroup == null || enemyGroup.divisions.isEmpty()) return;
        Vector2f myRef = (myGroup.organizationRatio < 0.50f && myGroup.friendlyIndependentFp > 15f)
                ? myGroup.independentCentroid
                : myGroup.fleetCentroid;

        for (int i = 0; i < enemyGroup.divisions.size(); i++) {
            DivisionState eDiv = enemyGroup.divisions.get(i);
            if (eDiv == null || !eDiv.isCombatEffective()) continue;

            // Compute closing velocity of eDiv toward our reference centroid (+ = closing on us, - = moving away)
            float toMyX = myRef.x - eDiv.centroid.x;
            float toMyY = myRef.y - eDiv.centroid.y;
            float toMyLen = (float) Math.sqrt(toMyX * toMyX + toMyY * toMyY);
            float closingSpeed = 0f;
            if (toMyLen > 1f) {
                List<ShipAPI> eAlive = eDiv.getAliveMembers();
                float vxSum = 0f;
                float vySum = 0f;
                int vCnt = 0;
                for (int k = 0; k < eAlive.size(); k++) {
                    ShipAPI es = eAlive.get(k);
                    if (es != null && es.getVelocity() != null) {
                        vxSum += es.getVelocity().x;
                        vySum += es.getVelocity().y;
                        vCnt++;
                    }
                }
                if (vCnt > 0) {
                    closingSpeed = ((vxSum / vCnt) * toMyX + (vySum / vCnt) * toMyY) / toMyLen;
                }
            }
            eDiv.closingSpeedToEnemy = closingSpeed;

            float suspicion = 0f;
            // If an enemy division is withdrawing (closingSpeed < -28) despite low flux, healthy hull, and ready weapons,
            // it is likely trading space or feigning retreat to bait us into an alpha strike!
            if (closingSpeed < -28f
                    && !eDiv.isUnableDivision
                    && eDiv.avgFluxLevel < 0.46f
                    && eDiv.avgHullLevel > 0.72f
                    && eDiv.weaponReadinessRatio >= 0.62f) {
                suspicion = 0.55f + clampVal((-closingSpeed - 28f) / 140f, 0f, 0.25f);
                // Check if another combat-ready enemy division is lurking within 2100u to spring a crossfire ambush
                for (int j = 0; j < enemyGroup.divisions.size(); j++) {
                    if (i == j) continue;
                    DivisionState lurker = enemyGroup.divisions.get(j);
                    if (lurker == null || !lurker.isAbleCombatEffective()) continue;
                    if (distance(eDiv.centroid, lurker.centroid) <= 2100f && lurker.avgFluxLevel < 0.55f) {
                        suspicion = Math.min(1.0f, suspicion + 0.30f);
                        break;
                    }
                }
            }
            eDiv.enemyFeignedRetreatSuspicion = eDiv.enemyFeignedRetreatSuspicion * 0.45f + suspicion * 0.55f;
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
        if (size == HullSize.FRIGATE
                && (ship.isStationModule() || ship.getParentStation() != null)
                && isStationOrModuleCombatUnit(ship)) {
            size = HullSize.CRUISER;
        }

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
            // A wiped-out / all-reserve division is a terminal TSFC state: mark its directional fields
            // initialized so the Warroom renderer never bypasses the cross-group re-evaluation throttle.
            div.directionalTimeInitialized = true;
            div.directionalSpaceInitialized = true;
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
            boolean isStationElement = s.isStation() || s.isStationModule() || s.getParentStation() != null;
            float spd = isStationElement
                    ? Math.max(48f, Math.min(115f, Math.max(850f, div.avgEngageRange) * 0.055f))
                    : Math.max(25f, s.getMaxSpeed());
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
        float coreHullEngageRange = Math.max(550f, div.avgEngageRange);
        float effRange = Math.max(coreHullEngageRange, avgWingReach * 0.68f);

        // Space Zone 1 (Directly Dominated Up Close — solid hull physical occupancy & direct gunline)
        // vs. Zone 2 (Dominated Nearby) & Zone 3 (Potentially Dominated):
        // Per Task F: Warships can physically fly/move right through strike craft squadrons (fighters have no
        // ship-to-ship hull collision blocking). Therefore, strike craft do NOT inflate Zone 1 (spaceInnerRadius),
        // which is strictly anchored by the division's solid warship hulls and direct hull weapon reach!
        // Strike craft instead project permeable sortie Space into Zone 2 (spaceDominatedRadius) and Zone 3 (spacePotentialRadius).
        float wingInnerBonus = 0f;
        float wingDomBonus = Math.min(340f, totalFighterWings * 60f * readiness);
        div.spaceInnerRadius = clampVal(
                (div.spreadRadius * 0.55f + Math.min(900f, coreHullEngageRange * 0.50f)) * ehpRadiusFactor + wingInnerBonus,
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
        // Strike craft exert permeable harassment/sortie Space weight rather than solid hull-blocking mass
        div.spaceDominanceWeight = Math.max(2f, (effectiveSpaceMass + totalFighterWings * 2.2f) * readiness * unableDiscount);
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
     * Synchronizes and returns synthetic Station Bastion {@link DivisionState}s for any live root orbital stations
     * belonging to {@code group} so stations project Time, Space, and Convergence without being issued mobile
     * maneuver orders or cluttering the map with mobile division callsigns.
     */
    private static CombatEngineAPI lastStationBastionSyncEngine = null;
    private static int lastStationBastionShipsSize = -1;
    private static float lastStationBastionSyncTime = -1000f;
    private static final Map<BattleGroupState, List<DivisionState>> cachedStationBastionsByGroup = new HashMap<>();

    public static List<DivisionState> syncAndGetStationBastionsForGroup(
            CombatEngineAPI engine,
            BattleGroupState group) {
        if (engine == null || group == null) return Collections.emptyList();
        List<ShipAPI> ships = engine.getShips();
        if (ships == null || ships.isEmpty()) return Collections.emptyList();

        float now = engine.getTotalElapsedTime(false);
        int shipsSize = ships.size();
        if (engine == lastStationBastionSyncEngine
                && shipsSize == lastStationBastionShipsSize
                && Math.abs(now - lastStationBastionSyncTime) < TSFC_EVAL_INTERVAL * 0.85f) {
            List<DivisionState> cached = cachedStationBastionsByGroup.get(group);
            if (cached != null) {
                return cached;
            }
        } else if (engine != lastStationBastionSyncEngine
                || shipsSize != lastStationBastionShipsSize
                || Math.abs(now - lastStationBastionSyncTime) >= 0.25f) {
            lastStationBastionSyncEngine = engine;
            lastStationBastionShipsSize = shipsSize;
            lastStationBastionSyncTime = now;
            cachedStationBastionsByGroup.clear();
        }

        List<DivisionState> bastions = new ArrayList<>();
        for (int i = 0; i < ships.size(); i++) {
            ShipAPI st = ships.get(i);
            if (!isRootCombatStation(st)) continue;
            if (st.getOwner() != group.side) continue;
            if (group.side == 0 && st.isAlly() != group.isAlliedAi) continue;

            String key = st.getId();
            DivisionState bastion = stationBastionsByShipId.get(key);
            if (bastion == null) {
                CommanderProfile cmdr = new CommanderProfile(st.getCaptain(), st.getName() != null ? st.getName() : "Orbital Station");
                bastion = new DivisionState(
                        "station_bastion_" + key,
                        group.side,
                        90 + bastions.size(),
                        DivisionArchetype.BATTLESHIP_DIVISION,
                        st.getName() != null ? st.getName() : "Orbital Station",
                        cmdr);
                bastion.doctrinalFormation = PermanentDivisionManager.DivisionFormation.CIRCLE;
                bastion.activeFormation = PermanentDivisionManager.DivisionFormation.CIRCLE;
                stationBastionsByShipId.put(key, bastion);
            }
            bastion.useConvergence = group.useConvergence || DivisionOtcCommandManager.isAlphaCoreControlledShip(st);
            bastion.flagship = st;
            bastion.members.clear();
            bastion.members.add(st);

            List<ShipAPI> childMods = st.getChildModulesCopy();
            if (childMods != null) {
                for (int mIdx = 0; mIdx < childMods.size(); mIdx++) {
                    ShipAPI mod = childMods.get(mIdx);
                    if (mod != null && mod.isAlive() && !mod.isHulk() && !mod.isExpired() && !bastion.members.contains(mod)) {
                        bastion.members.add(mod);
                    }
                }
            }
            for (int mIdx = 0; mIdx < ships.size(); mIdx++) {
                ShipAPI mod = ships.get(mIdx);
                if (mod != null && mod != st && mod.isAlive() && !mod.isHulk() && !mod.isExpired()
                        && mod.getParentStation() == st && !bastion.members.contains(mod)) {
                    bastion.members.add(mod);
                }
            }

            Vector2f stLoc = st.getLocation() != null ? st.getLocation() : new Vector2f(0f, 0f);
            bastion.centroid.set(stLoc);
            bastion.stationPoint.set(stLoc);
            bastion.velocity.set(0f, 0f);
            double hRad = Math.toRadians(st.getFacing());
            bastion.formationHeadingX = (float) Math.cos(hRad);
            bastion.formationHeadingY = (float) Math.sin(hRad);
            bastion.formationHeadingInitialized = true;

            float maxSpread = Math.max(220f, st.getCollisionRadius());
            float totalFp = 0f;
            float rangeWeightedSum = 0f;
            float rangeWeightSum = 0f;
            float fluxSum = 0f;
            float hullSum = 0f;
            float combatModFluxSum = 0f;
            float combatModHullSum = 0f;
            int combatModCount = 0;
            float totalEhp = 0f;
            int count = 0;
            for (int mIdx = 0; mIdx < bastion.members.size(); mIdx++) {
                ShipAPI m = bastion.members.get(mIdx);
                if (m == null || !m.isAlive() || m.isHulk()) continue;
                count++;
                if (m.getLocation() != null) {
                    float d = distance(stLoc, m.getLocation()) + Math.max(40f, m.getCollisionRadius() * 0.65f);
                    if (d > maxSpread) maxSpread = d;
                }
                boolean armedOrCarrierMod = hasAnyUsableCombatWeapon(m) || m.getNumFighterBays() > 0;
                float fp = ShipCapabilityProfile.resolveShipFpStrength(m);
                if (m == st || armedOrCarrierMod) {
                    totalFp += fp;
                    totalEhp += computeShipEffectiveHp(m);
                }
                fluxSum += m.getFluxLevel();
                hullSum += m.getHullLevel();
                if (armedOrCarrierMod && (!m.isStation() || bastion.members.size() == 1)) {
                    combatModFluxSum += m.getFluxLevel();
                    combatModHullSum += m.getHullLevel();
                    combatModCount++;
                }

                List<WeaponAPI> weapons = m.getAllWeapons();
                if (weapons != null) {
                    for (int wIdx = 0; wIdx < weapons.size(); wIdx++) {
                        WeaponAPI w = weapons.get(wIdx);
                        if (!isUsableCombatWeapon(w)) continue;
                        float wr = w.getRange();
                        if (wr <= 150f) continue;
                        float ww = weaponSizeWeight(w.getSize());
                        rangeWeightedSum += wr * ww;
                        rangeWeightSum += ww;
                    }
                }
            }
            bastion.spreadRadius = clampVal(maxSpread, 240f, 850f);
            bastion.totalFpStrength = Math.max(36f, totalFp);
            bastion.totalEffectiveHp = Math.max(8000f, totalEhp);
            bastion.effectiveHpRatio = (combatModCount > 0)
                    ? (combatModHullSum / combatModCount)
                    : ((count > 0) ? (hullSum / count) : 1f);
            bastion.avgEngageRange = (rangeWeightSum > 0f)
                    ? clampVal(rangeWeightedSum / rangeWeightSum, 650f, 2400f)
                    : 1150f;
            bastion.avgFluxLevel = (combatModCount > 0)
                    ? (combatModFluxSum / combatModCount)
                    : ((count > 0) ? (fluxSum / count) : 0f);
            bastion.avgHullLevel = (combatModCount > 0)
                    ? (combatModHullSum / combatModCount)
                    : ((count > 0) ? (hullSum / count) : 1f);
            bastion.coreMemberCount = Math.max(1, combatModCount > 0 ? combatModCount : count);
            bastion.ableMemberCount = Math.max(1, combatModCount > 0 ? combatModCount : count);
            bastion.isUnableDivision = false;
            bastion.retreatingFraction = 0f;

            PointFirepowerEval eval = evaluatePoint(engine, group.side, stLoc, Math.max(250f, st.getCollisionRadius()));
            bastion.incomingShortDotThreat = eval.incomingShortDot;
            bastion.crossfireCordonIndex = eval.cordonIndex;

            updateDivisionBaseTimeSpace(bastion);
            bastions.add(bastion);
        }
        cachedStationBastionsByGroup.put(group, bastions);
        return bastions;
    }

    public static List<DivisionState> getDivisionsAndStationBastionsForRendering(
            CombatEngineAPI engine,
            BattleGroupState group) {
        if (group == null) return Collections.emptyList();
        List<DivisionState> bastions = syncAndGetStationBastionsForGroup(engine, group);
        if (bastions.isEmpty()) {
            return group.divisions;
        }
        if (group.divisions.isEmpty()) {
            return bastions;
        }
        List<DivisionState> combined = new ArrayList<>(group.divisions.size() + bastions.size());
        combined.addAll(group.divisions);
        combined.addAll(bastions);
        return combined;
    }

    private static List<DivisionState> collectPeerDivisionsAndStations(
            CombatEngineAPI engine,
            BattleGroupState group,
            boolean includeSiblingSide0Groups) {
        if (group == null) return Collections.emptyList();
        List<DivisionState> out = new ArrayList<>(group.divisions);
        out.addAll(syncAndGetStationBastionsForGroup(engine, group));
        if (includeSiblingSide0Groups && group.side == 0) {
            BattleGroupState sibling = group.isAlliedAi ? activePlayerGroupRef : activeAlliedGroupRef;
            if (sibling != null && sibling != group) {
                for (int i = 0; i < sibling.divisions.size(); i++) {
                    DivisionState d = sibling.divisions.get(i);
                    if (d != null && !out.contains(d)) out.add(d);
                }
                List<DivisionState> sibStations = syncAndGetStationBastionsForGroup(engine, sibling);
                for (int i = 0; i < sibStations.size(); i++) {
                    DivisionState s = sibStations.get(i);
                    if (s != null && !out.contains(s)) out.add(s);
                }
            }
        } else if (includeSiblingSide0Groups && group.side == 1) {
            if (activePlayerGroupRef != null) {
                List<DivisionState> pStations = syncAndGetStationBastionsForGroup(engine, activePlayerGroupRef);
                for (int i = 0; i < pStations.size(); i++) {
                    DivisionState s = pStations.get(i);
                    if (s != null && !out.contains(s)) out.add(s);
                }
            }
            if (activeAlliedGroupRef != null) {
                for (int i = 0; i < activeAlliedGroupRef.divisions.size(); i++) {
                    DivisionState d = activeAlliedGroupRef.divisions.get(i);
                    if (d != null && !out.contains(d)) out.add(d);
                }
                List<DivisionState> aStations = syncAndGetStationBastionsForGroup(engine, activeAlliedGroupRef);
                for (int i = 0; i < aStations.size(); i++) {
                    DivisionState s = aStations.get(i);
                    if (s != null && !out.contains(s)) out.add(s);
                }
            }
        }
        return out;
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
        float evalNow = (engine != null)
                ? engine.getTotalElapsedTime(false)
                : (lastTimeSpaceConvUpdateTime >= 0f ? lastTimeSpaceConvUpdateTime + TSFC_EVAL_INTERVAL : 0f);
        if (engine != null) {
            lastTimeSpaceConvEngine = engine;
            lastTimeSpaceConvUpdateTime = evalNow;
        }
        DivisionOtcCommandManager.updateConvergenceCommandStates(engine, myGroup);
        if (enemyGroup != null) {
            DivisionOtcCommandManager.updateConvergenceCommandStates(engine, enemyGroup);
        }

        List<DivisionState> myEffectiveDivs = getDivisionsAndStationBastionsForRendering(engine, myGroup);
        List<DivisionState> myPeersWithAllies = collectPeerDivisionsAndStations(engine, myGroup, true);
        List<DivisionState> enemyEffectiveDivs = (enemyGroup != null)
                ? collectPeerDivisionsAndStations(engine, enemyGroup, myGroup.side == 1)
                : Collections.<DivisionState>emptyList();

        int activeDivsTotal = 0;
        for (int i = 0, n = myEffectiveDivs.size(); i < n; i++) {
            DivisionState d = myEffectiveDivs.get(i);
            if (d.isCombatEffective()) {
                activeDivsTotal++;
                if (d.freeTimeRadius <= 1f) {
                    updateDivisionBaseTimeSpace(d);
                }
            }
        }
        for (int i = 0, n = enemyEffectiveDivs.size(); i < n; i++) {
            DivisionState e = enemyEffectiveDivs.get(i);
            if (e.isCombatEffective()) {
                activeDivsTotal++;
                if (e.freeTimeRadius <= 1f) {
                    updateDivisionBaseTimeSpace(e);
                }
            }
        }
        battleActiveDivisionCount = Math.max(1, activeDivsTotal);

        for (int divIdx = 0, divLen = myEffectiveDivs.size(); divIdx < divLen; divIdx++) {
            DivisionState div = myEffectiveDivs.get(divIdx);
            if (!div.isCombatEffective()) continue;

            boolean advancingDivKeyframe = captureOrBinDivisionKeyframeBeforeUpdate(div, evalNow);

            // 1. Friendly Space mutual support from sibling divisions & friendly stations (excluding retreating/unable divisions)
            float friendlySupport = 0f;
            for (DivisionState peer : myPeersWithAllies) {
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
            if (!enemyEffectiveDivs.isEmpty()) {
                for (DivisionState eDiv : enemyEffectiveDivs) {
                    if (eDiv == null || !eDiv.isCombatEffective()) continue;
                    if (!OtcAI.isDivisionVisibleToSide(engine, div.side, eDiv)) continue;
                    Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, eDiv);
                    float d = distance(div.centroid, ePos);
                    // Symmetric base radii so whichever side evaluates first sees the same enemy space overlap as the other.
                    float eDomR = Math.max(650f, eDiv.spaceDominatedRadius);
                    float eInR = Math.max(380f, eDiv.spaceInnerRadius);
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
            float overmatchEdgeRelief = (div.localForceRatio >= 1.15f) ? 0.30f : 1.0f;
            float edgePinchTerm = div.edgeProximityPinch
                    * (0.14f + 0.26f * Math.max(spaceEngagement, firepowerEngagement))
                    * overmatchEdgeRelief;
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

            // 5. Strongpoint vs Weak Point classification (orbital stations are anchored fortress strongpoints)
            boolean isStationBastion = (div.flagship != null && div.flagship.isStation());
            div.isStrongpoint = isStationBastion
                    ? (div.avgFluxLevel < 0.78f)
                    : ((friendlySupport >= Math.max(24f, div.totalFpStrength * 0.58f)) && div.avgFluxLevel < 0.62f);
            div.isWeakPoint = !isStationBastion
                    && (friendlySupport < 12f)
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

            // Update battle-high watermarks for sliding opacity scaling
            if (div.spaceDominanceWeight > battleMaxDivisionSpaceDominance) {
                battleMaxDivisionSpaceDominance = div.spaceDominanceWeight;
            }
            if (div.freeTimeRadius > battleMaxDivisionFreeTimeRadius) {
                battleMaxDivisionFreeTimeRadius = div.freeTimeRadius;
            }
            if (div.convergenceScore > battleMaxDivisionConvergenceScore) {
                battleMaxDivisionConvergenceScore = div.convergenceScore;
            }

            finalizeDivisionKeyframeAfterUpdate(div, evalNow, advancingDivKeyframe);
        }
    }

    /**
     * Captures the division's current interpolated TSFC state into its {@code prev*} keyframe buffers
     * prior to a new 0.10s evaluation pass. If the existing keyframe is older than {@link #TSFC_KEYFRAME_MAX_AGE}
     * (2.0 seconds), it is thrown into the bin (discarded) so stale data is never interpolated.
     */
    static boolean captureOrBinDivisionKeyframeBeforeUpdate(DivisionState div, float evalNow) {
        if (div == null) return false;
        float elapsedSinceCurr = evalNow - div.currTsfcKeyframeTime;
        if (div.tsfcKeyframesInitialized && Math.abs(elapsedSinceCurr) <= 1e-4f) {
            return (div.currTsfcKeyframeTime - div.prevTsfcKeyframeTime) > 1e-4f;
        }
        if (div.tsfcKeyframesInitialized
                && (elapsedSinceCurr > TSFC_KEYFRAME_MAX_AGE || elapsedSinceCurr < -1e-4f)) {
            div.tsfcKeyframesInitialized = false;
            div.directionalTimeInitialized = false;
            div.directionalSpaceInitialized = false;
        }
        boolean advancing = div.tsfcKeyframesInitialized && elapsedSinceCurr > 1e-4f;
        if (advancing) {
            float blend = computeDivisionKeyframeBlendFactor(div, evalNow);
            if (blend < 0f) blend = 1.0f;
            div.prevFreeTimeRadius = div.prevFreeTimeRadius + (div.freeTimeRadius - div.prevFreeTimeRadius) * blend;
            div.prevEffectiveTimeRadius = div.prevEffectiveTimeRadius + (div.effectiveTimeRadius - div.prevEffectiveTimeRadius) * blend;
            div.prevEngagementLevel = div.prevEngagementLevel + (div.engagementLevel - div.prevEngagementLevel) * blend;
            div.prevSpaceInnerRadius = div.prevSpaceInnerRadius + (div.spaceInnerRadius - div.prevSpaceInnerRadius) * blend;
            div.prevSpaceDominatedRadius = div.prevSpaceDominatedRadius + (div.spaceDominatedRadius - div.prevSpaceDominatedRadius) * blend;
            div.prevSpacePotentialRadius = div.prevSpacePotentialRadius + (div.spacePotentialRadius - div.prevSpacePotentialRadius) * blend;
            div.prevSpaceDominanceWeight = div.prevSpaceDominanceWeight + (div.spaceDominanceWeight - div.prevSpaceDominanceWeight) * blend;
            div.prevCrossfireCordonIndex = div.prevCrossfireCordonIndex + (div.crossfireCordonIndex - div.prevCrossfireCordonIndex) * blend;
            div.prevConvergenceScore = div.prevConvergenceScore + (div.convergenceScore - div.prevConvergenceScore) * blend;
            div.prevConvergenceFocalPoint.set(
                    div.prevConvergenceFocalPoint.x + (div.convergenceFocalPoint.x - div.prevConvergenceFocalPoint.x) * blend,
                    div.prevConvergenceFocalPoint.y + (div.convergenceFocalPoint.y - div.prevConvergenceFocalPoint.y) * blend);
            int nDirs = DivisionState.TIME_DIRECTIONS;
            for (int s = 0; s < nDirs; s++) {
                div.prevDirectionalTimeRadii[s] = div.prevDirectionalTimeRadii[s]
                        + (div.directionalTimeRadii[s] - div.prevDirectionalTimeRadii[s]) * blend;
                div.prevDirectionalSpaceInnerRadii[s] = div.prevDirectionalSpaceInnerRadii[s]
                        + (div.directionalSpaceInnerRadii[s] - div.prevDirectionalSpaceInnerRadii[s]) * blend;
                div.prevDirectionalSpaceDominatedRadii[s] = div.prevDirectionalSpaceDominatedRadii[s]
                        + (div.directionalSpaceDominatedRadii[s] - div.prevDirectionalSpaceDominatedRadii[s]) * blend;
                div.prevDirectionalSpacePotentialRadii[s] = div.prevDirectionalSpacePotentialRadii[s]
                        + (div.directionalSpacePotentialRadii[s] - div.prevDirectionalSpacePotentialRadii[s]) * blend;
            }
            div.prevTsfcKeyframeTime = div.currTsfcKeyframeTime;
            div.currTsfcKeyframeTime = evalNow;
        }
        return advancing;
    }

    static void finalizeDivisionKeyframeAfterUpdate(DivisionState div, float evalNow, boolean wasAdvancing) {
        if (div == null) return;
        if (!wasAdvancing || !div.tsfcKeyframesInitialized || Math.abs(div.currTsfcKeyframeTime - div.prevTsfcKeyframeTime) <= 1e-4f) {
            div.prevTsfcKeyframeTime = evalNow;
            div.currTsfcKeyframeTime = evalNow;
            div.prevFreeTimeRadius = div.freeTimeRadius;
            div.prevEffectiveTimeRadius = div.effectiveTimeRadius;
            div.prevEngagementLevel = div.engagementLevel;
            div.prevSpaceInnerRadius = div.spaceInnerRadius;
            div.prevSpaceDominatedRadius = div.spaceDominatedRadius;
            div.prevSpacePotentialRadius = div.spacePotentialRadius;
            div.prevSpaceDominanceWeight = div.spaceDominanceWeight;
            div.prevCrossfireCordonIndex = div.crossfireCordonIndex;
            div.prevConvergenceScore = div.convergenceScore;
            div.prevConvergenceFocalPoint.set(div.convergenceFocalPoint);
            int nDirs = DivisionState.TIME_DIRECTIONS;
            System.arraycopy(div.directionalTimeRadii, 0, div.prevDirectionalTimeRadii, 0, nDirs);
            System.arraycopy(div.directionalSpaceInnerRadii, 0, div.prevDirectionalSpaceInnerRadii, 0, nDirs);
            System.arraycopy(div.directionalSpaceDominatedRadii, 0, div.prevDirectionalSpaceDominatedRadii, 0, nDirs);
            System.arraycopy(div.directionalSpacePotentialRadii, 0, div.prevDirectionalSpacePotentialRadii, 0, nDirs);
            div.tsfcKeyframesInitialized = true;
        }
    }

    /**
     * Returns the C1 smoothstep keyframe interpolation factor in [0.0, 1.0] for {@code div} at {@code renderNow},
     * or -1.0f if keyframes are uninitialized or older than {@link #TSFC_KEYFRAME_MAX_AGE} (2.0s — thrown into the bin).
     */
    public static float computeDivisionKeyframeBlendFactor(DivisionState div, float renderNow) {
        if (div == null || !div.tsfcKeyframesInitialized || div.currTsfcKeyframeTime < -50f) return -1f;
        float elapsedSinceCurr = renderNow - div.currTsfcKeyframeTime;
        if (elapsedSinceCurr > TSFC_KEYFRAME_MAX_AGE) {
            return -1f;
        }
        float span = div.currTsfcKeyframeTime - div.prevTsfcKeyframeTime;
        if (span <= 1e-4f || span > TSFC_KEYFRAME_MAX_AGE) {
            return 1.0f;
        }
        float u = clampVal(elapsedSinceCurr / span, 0.0f, 1.0f);
        return u;
    }

    /**
     * Lightweight 0.10s TSFC keyframe update for intermediate ticks between 0.20s Division C2 evaluations.
     * Refreshes ship Firepower Roses, base division Time/Space radii, and cross-group Time/Space/Convergence envelopes
     * so 60+ FPS rendering smoothly interpolates between 0.10s keyframes.
     */
    public static void updateTsfcKeyframeTick(
            CombatEngineAPI engine,
            float now,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup) {
        updateTsfcKeyframeTick(engine, now, playerGroup, alliedAiGroup, enemyGroup, null, null);
    }

    public static void updateTsfcKeyframeTick(
            CombatEngineAPI engine,
            float now,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup,
            BattleGroupState side1OpposingView,
            BattleGroupState side0OpposingView) {
        if (engine == null) return;
        activePlayerGroupRef = playerGroup;
        activeAlliedGroupRef = alliedAiGroup;
        activeEnemyGroupRef = enemyGroup;

        if ((now - lastRoseUpdateTime) >= TSFC_EVAL_INTERVAL * 0.85f || lastRoseUpdateTime < 0f) {
            updateBattlefieldRoses(engine, now);
        }
        refreshGroupBaseTimeSpaceForTsfcTick(engine, playerGroup);
        refreshGroupBaseTimeSpaceForTsfcTick(engine, alliedAiGroup);
        refreshGroupBaseTimeSpaceForTsfcTick(engine, enemyGroup);

        BattleGroupState oppForSide0 = (side1OpposingView != null && !side1OpposingView.divisions.isEmpty())
                ? side1OpposingView
                : enemyGroup;
        if (playerGroup != null) {
            updateCrossGroupTimeSpaceConvergence(engine, playerGroup, oppForSide0);
        }
        if (alliedAiGroup != null) {
            updateCrossGroupTimeSpaceConvergence(engine, alliedAiGroup, oppForSide0);
        }
        if (enemyGroup != null) {
            BattleGroupState primarySide0 = (side0OpposingView != null && !side0OpposingView.divisions.isEmpty())
                    ? side0OpposingView
                    : ((playerGroup != null && !playerGroup.divisions.isEmpty()) ? playerGroup : alliedAiGroup);
            updateCrossGroupTimeSpaceConvergence(engine, enemyGroup, primarySide0);
        }
    }

    public static void snapshotGroupDivisionKeyframesBeforeUpdate(
            CombatEngineAPI engine,
            float evalNow,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup) {
        snapshotSingleGroupDivisionKeyframes(engine, playerGroup, evalNow);
        snapshotSingleGroupDivisionKeyframes(engine, alliedAiGroup, evalNow);
        snapshotSingleGroupDivisionKeyframes(engine, enemyGroup, evalNow);
    }

    private static void snapshotSingleGroupDivisionKeyframes(
            CombatEngineAPI engine,
            BattleGroupState group,
            float evalNow) {
        if (group == null) return;
        List<DivisionState> divs = getDivisionsAndStationBastionsForRendering(engine, group);
        for (int i = 0, n = divs.size(); i < n; i++) {
            DivisionState div = divs.get(i);
            if (div != null && div.isCombatEffective()) {
                captureOrBinDivisionKeyframeBeforeUpdate(div, evalNow);
            }
        }
    }

    private static void refreshGroupBaseTimeSpaceForTsfcTick(CombatEngineAPI engine, BattleGroupState group) {
        if (group == null) return;
        float evalNow = engine != null ? engine.getTotalElapsedTime(false) : 0f;
        for (int i = 0, n = group.divisions.size(); i < n; i++) {
            DivisionState div = group.divisions.get(i);
            if (div == null || !div.isCombatEffective()) continue;
            captureOrBinDivisionKeyframeBeforeUpdate(div, evalNow);
            refreshDivisionLiveKinematicsForTsfcTick(div);
            float avgShieldArc = computeDivisionAvgShieldArc(div);
            PointFirepowerEval eval = evaluatePoint(engine, group.side, div.centroid, avgShieldArc);
            div.incomingShortDotThreat = eval.incomingShortDot;
            div.crossfireCordonIndex = eval.cordonIndex;
            updateDivisionBaseTimeSpace(div);
        }
    }

    private static void refreshDivisionLiveKinematicsForTsfcTick(DivisionState div) {
        List<ShipAPI> alive = div.getAliveMembers();
        if (alive.isEmpty()) return;
        float cx = 0f;
        float cy = 0f;
        float vx = 0f;
        float vy = 0f;
        float wSum = 0f;
        float fluxSum = 0f;
        float hullSum = 0f;
        int count = 0;
        for (int i = 0, n = alive.size(); i < n; i++) {
            ShipAPI s = alive.get(i);
            if (s == null || s.getLocation() == null) continue;
            if (div.detachedStragglerIds.contains(s.getId()) && div.coreMemberCount >= 1) continue;
            float baseW = Math.max(1f, ShipCapabilityProfile.resolveShipFpStrength(s));
            float w = (DivisionState.isShipUnable(s) && div.ableMemberCount > 0) ? (baseW * 0.12f) : baseW;
            cx += s.getLocation().x * w;
            cy += s.getLocation().y * w;
            if (s.getVelocity() != null) {
                vx += s.getVelocity().x * w;
                vy += s.getVelocity().y * w;
            }
            wSum += w;
            if (s.getFluxTracker() != null) {
                fluxSum += s.getFluxTracker().getFluxLevel();
            }
            hullSum += s.getHullLevel();
            count++;
        }
        if (wSum > 0f && count > 0) {
            div.centroid.set(cx / wSum, cy / wSum);
            div.velocity.set(vx / wSum, vy / wSum);
            div.avgFluxLevel = fluxSum / count;
            div.avgHullLevel = hullSum / count;
            float coreDistSqSum = 0f;
            int spreadSamples = 0;
            for (int i = 0, n = alive.size(); i < n; i++) {
                ShipAPI s = alive.get(i);
                if (s == null || s.getLocation() == null) continue;
                if (div.detachedStragglerIds.contains(s.getId()) && div.coreMemberCount >= 1) continue;
                float dx = s.getLocation().x - div.centroid.x;
                float dy = s.getLocation().y - div.centroid.y;
                coreDistSqSum += (dx * dx + dy * dy);
                spreadSamples++;
            }
            if (spreadSamples > 0) {
                div.spreadRadius = (float) Math.sqrt(coreDistSqSum / spreadSamples);
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
        List<DivisionState> myPeers = collectPeerDivisionsAndStations(engine, myGroup, true);
        List<DivisionState> enemyUnits = (enemyGroup != null)
                ? collectPeerDivisionsAndStations(engine, enemyGroup, div.side == 1)
                : Collections.<DivisionState>emptyList();

        // Precompute sector-invariant friendly peer & enemy division parameters once before the 24-sector loop
        int peerCap = myPeers.size();
        float[] peerCx = new float[peerCap];
        float[] peerCy = new float[peerCap];
        float[] peerInR = new float[peerCap];
        float[] peerDomR = new float[peerCap];
        float[] peerPotR = new float[peerCap];
        float[] peerDomW = new float[peerCap];
        int peerCount = 0;
        for (int i = 0; i < peerCap; i++) {
            DivisionState peer = myPeers.get(i);
            if (peer == null || peer == div || !peer.isCombatEffective()) continue;
            peerCx[peerCount] = peer.centroid.x;
            peerCy[peerCount] = peer.centroid.y;
            peerInR[peerCount] = peer.spaceInnerRadius;
            peerDomR[peerCount] = peer.spaceDominatedRadius;
            peerPotR[peerCount] = Math.max(peer.spaceDominatedRadius + 180f, peer.spacePotentialRadius);
            peerDomW[peerCount] = peer.spaceDominanceWeight;
            peerCount++;
        }

        int enemyCap = enemyUnits.size();
        float[] ePosX = new float[enemyCap];
        float[] ePosY = new float[enemyCap];
        float[] eEx = new float[enemyCap];
        float[] eEy = new float[enemyCap];
        float[] eRatioArr = new float[enemyCap];
        float[] eCloseGrappleArr = new float[enemyCap];
        boolean[] eInInteractRange = new boolean[enemyCap];
        float[] eInRArr = new float[enemyCap];
        float[] eDomRArr = new float[enemyCap];
        float[] ePotRArr = new float[enemyCap];
        float[] eDomWArr = new float[enemyCap];
        int enemyCount = 0;
        for (int i = 0; i < enemyCap; i++) {
            DivisionState eDiv = enemyUnits.get(i);
            if (eDiv == null || !eDiv.isCombatEffective()) continue;
            if (!OtcAI.isDivisionVisibleToSide(engine, div.side, eDiv)) continue;
            Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, eDiv);
            float dx = ePos.x - div.centroid.x;
            float dy = ePos.y - div.centroid.y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float eDomR = Math.max(850f, eDiv.spaceDominatedRadius);
            float eInR = Math.max(450f, eDiv.spaceInnerRadius);
            float maxInteractDist = freeR * 0.82f + eDomR;
            ePosX[enemyCount] = ePos.x;
            ePosY[enemyCount] = ePos.y;
            eInRArr[enemyCount] = eInR;
            eDomRArr[enemyCount] = eDomR;
            ePotRArr[enemyCount] = Math.max(1200f, eDiv.spacePotentialRadius);
            eDomWArr[enemyCount] = Math.max(10f, eDiv.spaceDominanceWeight);
            if (d < maxInteractDist) {
                float ov = clampVal(
                        1.0f - (d - eInR * 0.55f) / Math.max(350f, maxInteractDist - eInR * 0.55f),
                        0f,
                        1f);
                eRatioArr[enemyCount] = clampVal(
                        (ov * Math.max(12f, eDiv.spaceDominanceWeight)) / Math.max(16f, refStrength), 0f, 0.78f);
                eEx[enemyCount] = (d > 1f) ? (dx / d) : 1f;
                eEy[enemyCount] = (d > 1f) ? (dy / d) : 0f;
                eCloseGrappleArr[enemyCount] = (d < eInR) ? (0.20f * (1.0f - d / eInR)) : 0f;
                eInInteractRange[enemyCount] = true;
            }
            enemyCount++;
        }

        List<DivisionState> edgeEnemies = (enemyGroup != null)
                ? getDivisionsAndStationBastionsForRendering(engine, enemyGroup)
                : Collections.<DivisionState>emptyList();
        int edgeEnemyCap = edgeEnemies.size();
        float[] edgeEUx = new float[edgeEnemyCap];
        float[] edgeEUy = new float[edgeEnemyCap];
        float[] edgeEDist = new float[edgeEnemyCap];
        int edgeEnemyCount = 0;
        if (enemyGroup != null) {
            for (int i = 0; i < edgeEnemyCap; i++) {
                DivisionState eDiv = edgeEnemies.get(i);
                if (eDiv == null || !eDiv.isCombatEffective()) continue;
                if (!OtcAI.isDivisionVisibleToSide(engine, div.side, eDiv)) continue;
                Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, eDiv);
                float edx = ePos.x - div.centroid.x;
                float edy = ePos.y - div.centroid.y;
                float eDist = (float) Math.sqrt(edx * edx + edy * edy);
                if (eDist > 1f) {
                    edgeEUx[edgeEnemyCount] = edx / eDist;
                    edgeEUy[edgeEnemyCount] = edy / eDist;
                    edgeEDist[edgeEnemyCount] = eDist;
                    edgeEnemyCount++;
                }
            }
        }

        for (int s = 0; s < nDirs; s++) {
            float angleDeg = s * (360f / nDirs);
            double rad = Math.toRadians(angleDeg);
            float ux = (float) Math.cos(rad);
            float uy = (float) Math.sin(rad);

            float dirPinch = baseDrag;

            // 1. Directional Enemy Contact & Space Blocking Lobe
            for (int i = 0; i < enemyCount; i++) {
                if (!eInInteractRange[i]) continue;
                float cosAlign = ux * eEx[i] + uy * eEy[i];
                float closeGrapple = eCloseGrappleArr[i];
                float directionalFactor;
                if (cosAlign >= 0f) {
                    directionalFactor = 0.26f + 1.18f * cosAlign * cosAlign + closeGrapple;
                } else if (cosAlign >= -0.45f) {
                    float uLat = (cosAlign + 0.45f) / 0.45f;
                    directionalFactor = 0.04f + 0.22f * uLat * uLat + closeGrapple * 0.65f;
                } else {
                    directionalFactor = 0.02f + closeGrapple * 0.35f;
                }
                dirPinch += eRatioArr[i] * directionalFactor;
            }

            // 2. Plus/Minus External Space Control along the directional slide vector (P_step = centroid + u * 0.55 * freeR)
            float stepPtX = div.centroid.x + ux * (freeR * 0.55f);
            float stepPtY = div.centroid.y + uy * (freeR * 0.55f);
            float peerFriendlySpace = 0f;
            for (int i = 0; i < peerCount; i++) {
                float pdx = stepPtX - peerCx[i];
                float pdy = stepPtY - peerCy[i];
                float dist = (float) Math.sqrt(pdx * pdx + pdy * pdy);
                float potR = peerPotR[i];
                if (dist < potR) {
                    float inR = peerInR[i];
                    float domR = peerDomR[i];
                    float w = (dist <= inR) ? 1.0f
                            : (dist <= domR
                            ? (0.55f + 0.45f * (1f - (dist - inR) / Math.max(100f, domR - inR)))
                            : (0.55f * (1f - (dist - domR) / Math.max(100f, potR - domR))));
                    peerFriendlySpace += peerDomW[i] * Math.max(0f, w);
                }
            }
            float stepEnemySpace = 0f;
            for (int i = 0; i < enemyCount; i++) {
                float edx = stepPtX - ePosX[i];
                float edy = stepPtY - ePosY[i];
                float dist = (float) Math.sqrt(edx * edx + edy * edy);
                float potR = ePotRArr[i];
                if (dist < potR) {
                    float inR = eInRArr[i];
                    float domR = eDomRArr[i];
                    float w = (dist <= inR) ? 1.0f
                            : (dist <= domR
                            ? (0.55f + 0.45f * (1f - (dist - inR) / Math.max(100f, domR - inR)))
                            : (0.55f * (1f - (dist - domR) / Math.max(100f, potR - domR))));
                    stepEnemySpace += eDomWArr[i] * Math.max(0f, w);
                }
            }
            float externalNetSpace = peerFriendlySpace - stepEnemySpace;
            if (externalNetSpace < 0f) {
                dirPinch += clampVal((-externalNetSpace) / Math.max(20f, refStrength * 1.25f), 0f, 0.34f);
            } else if (externalNetSpace > 0f) {
                dirPinch -= clampVal(externalNetSpace / Math.max(24f, refStrength * 1.65f), 0f, 0.16f);
            }

            // 2b. Map-Edge Directional Pinch (Item D)
            float edgeDist = computeDirectionalBoundaryClearance(engine, div.centroid, ux, uy, 140f);
            if (edgeDist < freeR * 1.18f) {
                boolean enemyInEdgeCorridor = false;
                for (int i = 0; i < edgeEnemyCount; i++) {
                    if (edgeEDist[i] <= edgeDist + 280f) {
                        float dot = edgeEUx[i] * ux + edgeEUy[i] * uy;
                        if (dot >= 0.78f) {
                            enemyInEdgeCorridor = true;
                            break;
                        }
                    }
                }
                if (!enemyInEdgeCorridor) {
                    float pinchedR = pinchRadiusByBoundary(freeR, edgeDist);
                    float boundaryImpliedEng = clampVal(1.0f - (pinchedR / Math.max(100f, freeR)), 0.0f, 0.92f);
                    if (div.localForceRatio >= 1.15f) {
                        boundaryImpliedEng *= 0.45f;
                    }
                    dirPinch = Math.max(dirPinch, boundaryImpliedEng);
                }
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
                    engine, div.centroid, (float) Math.cos(rad), (float) Math.sin(rad), 80f);
            div.directionalTimeRadii[s] = Math.max(
                    pinchRadiusByBoundary(unpinchedR, edgeDist),
                    Math.min(unpinchedR, edgeDist + 240f));
        }
        div.directionalTimeInitialized = true;
    }

    /**
     * Computes the 24-sector Directional, Non-Circular, Pinchable Space (Zone of Control) envelope for {@code div}
     * across all three tiers (Inner Directly Dominated, Mid Dominated Nearby, Outer Potentially Dominated).
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
        List<DivisionState> myPeers = collectPeerDivisionsAndStations(engine, myGroup, true);
        List<DivisionState> enemyUnits = (enemyGroup != null)
                ? collectPeerDivisionsAndStations(engine, enemyGroup, div.side == 1)
                : Collections.<DivisionState>emptyList();
        float[] rawIn = new float[nDirs];
        float[] rawDom = new float[nDirs];
        float[] rawPot = new float[nDirs];

        // Precompute sector-invariant member ship, enemy division, enemy wing, and friendly peer arrays once before the 24-sector loop
        int aliveCap = alive.size();
        float[] mRelX = new float[aliveCap];
        float[] mRelY = new float[aliveCap];
        ShipFirepowerRose[] mRoses = new ShipFirepowerRose[aliveCap];
        float[] mFpWeights = new float[aliveCap];
        int mCount = 0;
        for (int mIdx = 0; mIdx < aliveCap; mIdx++) {
            ShipAPI m = alive.get(mIdx);
            if (m == null || (div.detachedStragglerIds.contains(m.getId()) && div.coreMemberCount >= 1)) continue;
            mRelX[mCount] = m.getLocation().x - div.centroid.x;
            mRelY[mCount] = m.getLocation().y - div.centroid.y;
            ShipFirepowerRose rose = rosesByShipId.get(m.getId());
            if (rose != null && rose.maxGeneralRange > 50f) {
                mRoses[mCount] = rose;
                mFpWeights[mCount] = ShipCapabilityProfile.resolveShipFpStrength(m);
            }
            mCount++;
        }

        int eUnitCap = enemyUnits.size();
        float[] eUx = new float[eUnitCap];
        float[] eUy = new float[eUnitCap];
        float[] ePinchCoeff = new float[eUnitCap];
        float[] eHullPinchCoeff = new float[eUnitCap];
        int eActiveCount = 0;
        List<Float> wingUxList = null;
        List<Float> wingUyList = null;
        List<Float> wingCoeffList = null;

        for (int i = 0; i < eUnitCap; i++) {
            DivisionState eDiv = enemyUnits.get(i);
            if (eDiv == null || !eDiv.isCombatEffective()) continue;
            if (!OtcAI.isDivisionVisibleToSide(engine, div.side, eDiv)) continue;
            Vector2f ePos = OtcAI.getVisibleCentroidForSide(engine, div.side, eDiv);
            float dx = ePos.x - div.centroid.x;
            float dy = ePos.y - div.centroid.y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float eDomR = Math.max(650f, eDiv.spaceDominatedRadius);
            float eInR = Math.max(380f, eDiv.spaceInnerRadius);
            float maxDist = basePot + eDomR;
            if (d < maxDist && d > 1f) {
                float overlap = clampVal(1.0f - (d / Math.max(400f, maxDist)), 0f, 1f);
                float weightRatio = eDiv.spaceDominanceWeight
                        / Math.max(8f, div.spaceDominanceWeight + eDiv.spaceDominanceWeight * 0.85f);
                eUx[eActiveCount] = dx / d;
                eUy[eActiveCount] = dy / d;
                ePinchCoeff[eActiveCount] = overlap * weightRatio * 0.58f;
                float hullBlockingSpan = baseInner + eInR * 1.35f;
                if (d < hullBlockingSpan) {
                    float hullOverlap = clampVal(1.0f - (d / Math.max(300f, hullBlockingSpan)), 0f, 1f);
                    eHullPinchCoeff[eActiveCount] = hullOverlap * weightRatio * 0.52f;
                }
                eActiveCount++;
            }
            List<ShipAPI> eMembers = eDiv.members;
            for (int esIdx = 0, esLen = eMembers.size(); esIdx < esLen; esIdx++) {
                ShipAPI es = eMembers.get(esIdx);
                if (es == null || !es.isAlive() || es.isHulk() || es.isExpired()) continue;
                List<com.fs.starfarer.api.combat.FighterWingAPI> eWings = es.getAllWings();
                if (eWings == null) continue;
                for (int wIdx = 0, wLen = eWings.size(); wIdx < wLen; wIdx++) {
                    com.fs.starfarer.api.combat.FighterWingAPI ew = eWings.get(wIdx);
                    ShipAPI wLead = resolveAliveWingLeader(ew);
                    if (wLead == null || !OtcAI.isShipVisibleToSide(engine, div.side, wLead)) continue;
                    if (distance(wLead.getLocation(), ePos) <= 260f) continue;
                    float wdx = wLead.getLocation().x - div.centroid.x;
                    float wdy = wLead.getLocation().y - div.centroid.y;
                    float wd = (float) Math.sqrt(wdx * wdx + wdy * wdy);
                    if (wd < basePot + 450f && wd > 1f) {
                        float wOv = clampVal(1.0f - (wd / Math.max(350f, basePot + 450f)), 0f, 1f);
                        if (wingUxList == null) {
                            wingUxList = new ArrayList<>(8);
                            wingUyList = new ArrayList<>(8);
                            wingCoeffList = new ArrayList<>(8);
                        }
                        wingUxList.add(wdx / wd);
                        wingUyList.add(wdy / wd);
                        wingCoeffList.add(wOv * 0.035f);
                    }
                }
            }
        }
        int wingCount = (wingUxList != null) ? wingUxList.size() : 0;

        int peerCap = myPeers.size();
        float[] pUx = new float[peerCap];
        float[] pUy = new float[peerCap];
        float[] pBolsterCoeff = new float[peerCap];
        int pActiveCount = 0;
        for (int i = 0; i < peerCap; i++) {
            DivisionState peer = myPeers.get(i);
            if (peer == null || peer == div || !peer.isAbleCombatEffective()) continue;
            float dx = peer.centroid.x - div.centroid.x;
            float dy = peer.centroid.y - div.centroid.y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float maxDist = baseDom + peer.spaceDominatedRadius;
            if (d < maxDist && d > 1f) {
                float overlap = clampVal(1.0f - (d / Math.max(400f, maxDist)), 0f, 1f);
                pUx[pActiveCount] = dx / d;
                pUy[pActiveCount] = dy / d;
                pBolsterCoeff[pActiveCount] = overlap * 0.14f;
                pActiveCount++;
            }
        }

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
                    formShape = 0.90f + 0.16f * (cosRight * cosRight) + (cosFwd >= 0f ? 0.14f * cosFwd : -0.14f * (-cosFwd));
                    break;
                case LINE_AHEAD:
                    formShape = 0.86f + 0.24f * (cosFwd * cosFwd);
                    break;
                case FORWARD_WEDGE:
                case FORWARD_SEMICIRCLE:
                    formShape = 0.92f + (cosFwd >= 0f ? 0.22f * cosFwd : -0.16f * (-cosFwd));
                    break;
                case REVERSE_WEDGE:
                case REVERSE_SEMICIRCLE:
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
                    formShape = 0.95f + 0.09f * cosFwd;
                    break;
            }

            // 1b. Member hull projection & directional weapon-rose reach along sector angleDeg
            float maxMemberProj = 0f;
            float roseReachSum = 0f;
            float roseWeightSum = 0f;
            for (int mIdx = 0; mIdx < mCount; mIdx++) {
                float proj = mRelX[mIdx] * ux + mRelY[mIdx] * uy;
                if (proj > maxMemberProj) {
                    maxMemberProj = proj;
                }
                ShipFirepowerRose rose = mRoses[mIdx];
                if (rose != null) {
                    float w = mFpWeights[mIdx];
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
            float enemyHullInnerPinch = 0f;
            float enemyStrikeCraftSoftPinch = 0f;
            for (int i = 0; i < eActiveCount; i++) {
                float cosAlign = eUx[i] * ux + eUy[i] * uy;
                if (cosAlign > 0.05f) {
                    float cosSq = cosAlign * cosAlign;
                    enemyPinch += ePinchCoeff[i] * cosSq;
                    if (eHullPinchCoeff[i] > 0f) {
                        enemyHullInnerPinch += eHullPinchCoeff[i] * cosSq;
                    }
                }
            }
            for (int wIdx = 0; wIdx < wingCount; wIdx++) {
                float wCos = wingUxList.get(wIdx) * ux + wingUyList.get(wIdx) * uy;
                if (wCos > 0.10f) {
                    enemyStrikeCraftSoftPinch += wingCoeffList.get(wIdx) * (wCos * wCos);
                }
            }

            float friendlyBolster = 0f;
            for (int i = 0; i < pActiveCount; i++) {
                float cosAlign = pUx[i] * ux + pUy[i] * uy;
                if (cosAlign > 0.15f) {
                    friendlyBolster += pBolsterCoeff[i] * (cosAlign * cosAlign);
                }
            }

            float netPinchMult = clampVal(
                    1.0f - clampVal(enemyPinch + enemyStrikeCraftSoftPinch, 0f, 0.52f) + clampVal(friendlyBolster, 0f, 0.14f),
                    0.46f,
                    1.14f);
            float innerPinchMult = clampVal(
                    1.0f - clampVal(enemyHullInnerPinch, 0f, 0.34f) + clampVal(friendlyBolster * 0.5f, 0f, 0.08f),
                    0.62f,
                    1.12f);

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
        if (engine == null) {
            try {
                engine = Global.getCombatEngine();
            } catch (Throwable ignored) {
            }
        }
        float halfW = Math.max(1200f, getMapHalfWidth(engine) - margin);
        float halfH = Math.max(1200f, getMapHalfHeight(engine) - margin);
        float rightWall = Math.max(halfW, pos.x + 250f);
        float leftWall = Math.min(-halfW, pos.x - 250f);
        float topWall = Math.max(halfH, pos.y + 250f);
        float botWall = Math.min(-halfH, pos.y - 250f);
        float tx = 99999f;
        if (ux > 1e-4f) {
            tx = (rightWall - pos.x) / ux;
        } else if (ux < -1e-4f) {
            tx = (leftWall - pos.x) / ux;
        }
        float ty = 99999f;
        if (uy > 1e-4f) {
            ty = (topWall - pos.y) / uy;
        } else if (uy < -1e-4f) {
            ty = (botWall - pos.y) / uy;
        }
        return Math.max(120f, Math.min(tx, ty));
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

        float enemyMinClear = 9999f;
        float eLen = 0f;
        if (enemyFocusPos != null) {
            float eClearX = halfW - Math.abs(enemyFocusPos.x);
            float eClearY = halfH - Math.abs(enemyFocusPos.y);
            enemyMinClear = Math.min(eClearX, eClearY);
            float edx = enemyFocusPos.x - cand.x;
            float edy = enemyFocusPos.y - cand.y;
            eLen = (float) Math.sqrt(edx * edx + edy * edy);
        }
        boolean enemyPinnedNearEdge = enemyMinClear < 1400f;
        float hardFloor = enemyPinnedNearEdge ? 360f : 650f;

        // Hard safety margin: allow closing to 360u when hunting an enemy pinned near the map boundary
        if (minClear < hardFloor) {
            return -(hardFloor - minClear) * 1.15f;
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
        if (enemyFocusPos != null && eLen > 1f) {
            float edx = enemyFocusPos.x - cand.x;
            float edy = enemyFocusPos.y - cand.y;
            // Negative when enemy is toward map center and the map wall is BEHIND our candidate station
            enemyDotWall = (edx * wallNx + edy * wallNy) / eLen;
        }

        if (defensivePosture && enemyDotWall <= -0.20f) {
            // Safe defensive bastion zone: back to the map edge (900u..2000u clearance), facing inward toward the attacker!
            float sweetSpot = 1.0f - Math.abs(minClear - 1350f) / 1050f;
            return Math.max(0f, sweetSpot) * (-enemyDotWall) * 260f;
        }

        // When the target enemy itself is pinned near the map edge (enemyPinnedNearEdge), do NOT repel our station
        // 1,350u inward away from the enemy; instead reward stations that press the cornered enemy at gun range!
        if (enemyPinnedNearEdge) {
            float safeEdgeFloor = Math.max(380f, enemyMinClear - 180f);
            if (minClear < safeEdgeFloor) {
                return -(safeEdgeFloor - minClear) * 0.45f;
            }
            if (enemyDotWall >= -0.15f && eLen >= 420f && eLen <= 920f) {
                return 135f;
            }
            return 0f;
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
                // Airborne Strike Craft squadrons project permeable sortie Space at their squadron location.
                // Per Task F: Because warships can physically move right through strike craft (no ship-to-ship
                // collision blocking), strike craft have NO solid inner hull-exclusion core (inR = 0) and exert
                // a permeable harassment/screening Space field (smoothly tapering from center to 720u) rather than
                // a solid hull-blocking zone.
                for (ShipAPI s : d.getAliveMembers()) {
                    List<com.fs.starfarer.api.combat.FighterWingAPI> wings = s.getAllWings();
                    if (wings == null) continue;
                    for (com.fs.starfarer.api.combat.FighterWingAPI wing : wings) {
                        ShipAPI wLead = resolveAliveWingLeader(wing);
                        if (wLead == null || distance(wLead.getLocation(), d.centroid) <= 260f) continue;
                        float wDist = distance(point, wLead.getLocation());
                        if (wDist < 720f) {
                            float ww = 0.55f * (1.0f - (wDist / 720f));
                            friendlySpace += 2.2f * Math.max(0f, ww);
                        }
                    }
                }
            }
        }
        if (enemyGroup != null) {
            for (DivisionState e : getDivisionsAndStationBastionsForRendering(engine, enemyGroup)) {
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
                // Enemy airborne Strike Craft squadrons also project permeable harassment Space at their squadron location
                // (never a solid inner hull-blocking core, since warships can move right through strike craft)
                for (ShipAPI s : e.getAliveMembers()) {
                    List<com.fs.starfarer.api.combat.FighterWingAPI> wings = s.getAllWings();
                    if (wings == null) continue;
                    for (com.fs.starfarer.api.combat.FighterWingAPI wing : wings) {
                        ShipAPI wLead = resolveAliveWingLeader(wing);
                        if (wLead == null || !OtcAI.isShipVisibleToSide(engine, observerSide, wLead)) continue;
                        if (distance(wLead.getLocation(), ePos) <= 260f) continue;
                        float wDist = distance(point, wLead.getLocation());
                        if (wDist < 720f) {
                            float ww = 0.55f * (1.0f - (wDist / 720f));
                            enemySpace += 2.2f * Math.max(0f, ww);
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

        // 3. Firepower / Flux Collapse Multiplier & Division Isolation (Defeat in Detail)
        float fluxCollapseMult = 1.0f + enemyDiv.avgFluxLevel * 1.90f + (1.0f - enemyDiv.avgHullLevel) * 1.25f;
        if (enemyDiv.stationKeepingRatio > 1.45f) {
            fluxCollapseMult *= 1.55f;
        }
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

        // Multiplicative phase-space product with bounded FP base so Weak Points & high-flux targets are prioritized.
        // Proximity Gate: a rearward enemy division 3,200u away that happens to be flux-locked is NOT a closer,
        // more dangerous target than the enemy main body already in gun range of our line!
        float proximityGate = 0.55f + timeCoincidence * 0.85f;
        float visFp = OtcAI.getVisibleFpForSide(engine, myDiv.side, enemyDiv);
        float baseValue = Math.min(440f, 165f + visFp * 4.2f) + blindSpot * 0.95f;
        return baseValue * enemyPinnedBonus * seamVulnerability * fluxCollapseMult
                * Math.min(1.85f, pincerSynergy) * unableTargetMult / proximityGate;
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
    // (Delegated to VoidstrikeCoordinator & DivisionOtcCommandManager)
    // =========================================================================

    public static void updateConvergenceCommandStates(CombatEngineAPI engine, BattleGroupState group) {
        DivisionOtcCommandManager.updateConvergenceCommandStates(engine, group);
    }

    public static void updateVoidstrikes(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            BattleGroupState alliedGroup,
            BattleGroupState enemyGroup,
            float now) {
        VoidstrikeCoordinator.updateVoidstrikes(engine, playerGroup, alliedGroup, enemyGroup, now);
    }

    public static List<VoidstrikeCoordinator.VoidstrikeRecord> getActiveVoidstrikes() {
        return VoidstrikeCoordinator.getActiveVoidstrikes();
    }




    private static float computeWeaponBaseAntiShipScore(WeaponAPI w) {
        FireClassification fc = classifyWeaponFire(w);
        float antiShipWeight = (fc == FireClassification.POINT_DEFENSE) ? 0.18f
                : (fc == FireClassification.HYBRID ? 0.85f : 1.0f);
        boolean isStrike = w.hasAIHint(AIHints.STRIKE);
        DerivedWeaponStatsAPI d = w.getDerivedStats();
        float dps;
        float burst;
        if (d != null && (d.getDps() > 0f || d.getBurstDamage() > 0f || d.getSustainedDps() > 0f)) {
            burst = Math.max(d.getBurstDamage(), d.getDamagePerShot());
            dps = Math.max(d.getSustainedDps(), d.getDps() * 0.75f);
            if (dps <= 1f && burst > 0f) {
                dps = burst / Math.max(1f, w.getCooldown());
            }
        } else {
            float szW = weaponSizeWeight(resolveEffectiveWeaponSize(w));
            burst = szW * 150f * (isStrike ? 2.0f : 1.0f);
            dps = szW * 85f * (isStrike ? 1.2f : 1.0f);
        }
        return (dps * 0.70f + (burst / 4.0f) * 0.30f) * antiShipWeight;
    }
}
