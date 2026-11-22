package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Generic, mod-agnostic physics and combat-envelope analyzer.
 *
 * <p>Measures the active combat environment from live {@link WeaponSpecAPI}, {@link FighterWingSpecAPI},
 * {@link ShipAPI}, {@link ShipHullSpecAPI}, and {@link CombatEngineAPI} telemetry rather than hardcoding
 * vanilla range/speed/radius constants:
 * <ul>
 *   <li>Resolves to exact {@code 1.0f} identity when specs and kinematics fall within vanilla envelopes,
 *       preserving 100% parity on vanilla Starsector and standard ship/faction packs.</li>
 *   <li>Smoothly adapts standoff ceilings, weapon short/long classification, T-S-F-C range horizons,
 *       physical hull spacing vs. broadphase {@code collisionRadius} inflation, Newtonian braking
 *       horizons, friendly-fire clearance, beam distance dropoff, Voidstrike speeds, and map leashes
 *       under extreme combat overhauls (such as Better Combat, Realistic Combat, or custom configs).</li>
 * </ul>
 */
public final class CombatPhysicsProfile {

    /** Vanilla reference medians and baselines. */
    public static final float VANILLA_DIRECT_GUN_MEDIAN_RANGE = 700f;
    public static final float VANILLA_FIGHTER_ENGAGE_RANGE = 4000f;
    public static final float VANILLA_REFERENCE_MAP_SPAN = 18000f;
    public static final float VANILLA_MAX_STOPPING_DIST_THRESHOLD = 420f;
    public static final float VANILLA_MAX_STOPPING_TIME_THRESHOLD = 4.2f;

    // Global spec-environment cache (computed lazily from SettingsAPI specs + bcomSettings.json / LunaSettings)
    private static boolean globalSpecInitialized = false;
    private static SettingsAPI cachedSettingsRef = null;
    private static float cachedGlobalGunRangeScale = 1.0f;
    private static float cachedGlobalMissileRangeScale = 1.0f;
    private static float cachedGlobalFighterRangeScale = 1.0f;
    private static float cachedGlobalShipBoundsScale = 1.0f;
    private static float cachedGlobalSightScale = 1.0f;
    private static float cachedGlobalMapScale = 1.0f;
    private static float cachedGlobalShortGunCeiling = 650f;
    private static float cachedGlobalHybridCloseCeiling = 700f;
    private static float cachedGlobalLongGunFloor = 900f;
    private static boolean cachedGlobalBeamDropoffEnabled = false;
    private static float cachedBeamDropoffOffset = 0.30f;
    private static float cachedBeamDropoffExp = 1.20f;
    private static float cachedBeamDropoffMult = 1.00f;

    // Deterministic global-multiplier test override for headless unit tests
    private static boolean testGlobalScaleOverrideActive = false;
    private static float testGlobalGunScale = 1.0f;
    private static float testGlobalMissileScale = 1.0f;
    private static float testGlobalFighterScale = 1.0f;
    private static float testGlobalBoundsScale = 1.0f;
    private static float testGlobalMapScale = 1.0f;

    // Live per-battle scale cache (updated O(N) at most once per C2 tick)
    private static CombatEngineAPI cachedCombatEngine = null;
    private static float cachedCombatEvalTime = -999f;
    private static int cachedCombatShipCount = -1;
    private static float cachedLiveWeaponRangeScale = 1.0f;
    private static float cachedLiveFighterRangeScale = 1.0f;
    private static float cachedLiveMapScale = 1.0f;
    private static float cachedLiveMaxFighterSpeedCeiling = 320f;

    // Per-ship listener & weapon-reach caches (cleared automatically by WeakHashMap)
    private static final WeakHashMap<ShipAPI, Boolean> SHIP_BEAM_DROPOFF_LISTENER_CACHE = new WeakHashMap<>();
    private static final WeakHashMap<ShipAPI, float[]> SHIP_MAX_GUN_REACH_CACHE = new WeakHashMap<>();

    // Deterministic test override for headless unit tests
    private static Boolean testBeamDropoffOverride = null;
    private static float testBeamDropoffOffset = 0.30f;
    private static float testBeamDropoffExp = 1.20f;
    private static float testBeamDropoffMult = 1.00f;

    // Per-frame flat primitive obstacle snapshot (eliminates O(ships * weapons * ships) nested loops
    // and keeps every method small/flat so experimental Shenandoah-IU + VectorReboxing C2 flags never segfault)
    private static CombatEngineAPI lastObsEngine = null;
    private static float lastObsElapsed = -999f;
    private static int lastObsRawListSize = -1;
    private static int obsCount = 0;
    private static ShipAPI[] obsShips = new ShipAPI[128];
    private static ShipAPI[] obsParents = new ShipAPI[128];
    private static int[] obsOwners = new int[128];
    private static boolean[] obsIsHulk = new boolean[128];
    private static boolean[] obsOverloadedOrVenting = new boolean[128];
    private static float[] obsX = new float[128];
    private static float[] obsY = new float[128];
    private static float[] obsVx = new float[128];
    private static float[] obsVy = new float[128];
    private static float[] obsHullR = new float[128];
    private static float[] obsShieldR = new float[128];
    private static float[] obsMaxGunReach = new float[128];

    // Per-frame flat primitive armed-missile snapshot (for O(missiles) ShipCaptainAI incoming-salvo defense)
    private static CombatEngineAPI lastMisEngine = null;
    private static float lastMisElapsed = -999f;
    private static int misCount = 0;
    private static int[] misOwners = new int[256];
    private static boolean[] misGuided = new boolean[256];
    private static float[] misX = new float[256];
    private static float[] misY = new float[256];
    private static float[] misVx = new float[256];
    private static float[] misVy = new float[256];
    private static float[] misDmg = new float[256];

    // 4096-entry fast degree trig & 257-entry unit power LUTs:
    // Eliminates HotSpot C2 CallLeafNoFP -> ProjNode -> ConvD2FNode graphs (Math.sin/cos/atan2/pow)
    // inside 60 Hz combat loops, preventing OpenJDK 17.0.10 Shenandoah-IU C2 barrier/PhaseIterGVN segfaults.
    private static final int TRIG_BITS = 12;
    private static final int TRIG_MASK = (1 << TRIG_BITS) - 1;
    private static final float DEG_TO_INDEX = (1 << TRIG_BITS) / 360.0f;
    private static final float[] SIN_TABLE = new float[1 << TRIG_BITS];
    private static float cachedPowLutExp = -1.0f;
    private static final float[] UNIT_POW_LUT = new float[257];

    static {
        for (int i = 0; i < (1 << TRIG_BITS); i++) {
            SIN_TABLE[i] = (float) StrictMath.sin((i * 2.0 * Math.PI) / (1 << TRIG_BITS));
        }
        rebuildUnitPowLut(1.20f);
    }

    private static void rebuildUnitPowLut(float exp) {
        cachedPowLutExp = exp;
        UNIT_POW_LUT[0] = 0f;
        UNIT_POW_LUT[256] = 1f;
        for (int i = 1; i < 256; i++) {
            UNIT_POW_LUT[i] = (float) StrictMath.pow(i / 256.0, exp);
        }
    }

    private static float fastUnitPow(float ratio, float exp) {
        if (ratio <= 0f) return 0f;
        if (ratio >= 1f) return 1f;
        if (Math.abs(exp - 1.0f) < 0.005f) return ratio;
        if (Math.abs(exp - cachedPowLutExp) > 0.002f) {
            rebuildUnitPowLut(exp);
        }
        float scaled = ratio * 256.0f;
        int idx = (int) scaled;
        if (idx >= 256) return 1f;
        float frac = scaled - idx;
        return UNIT_POW_LUT[idx] + (UNIT_POW_LUT[idx + 1] - UNIT_POW_LUT[idx]) * frac;
    }

    public static float fastSinDeg(float deg) {
        int idx = Math.round(deg * DEG_TO_INDEX) & TRIG_MASK;
        return SIN_TABLE[idx];
    }

    public static float fastCosDeg(float deg) {
        int idx = (Math.round(deg * DEG_TO_INDEX) + (1 << (TRIG_BITS - 2))) & TRIG_MASK;
        return SIN_TABLE[idx];
    }

    private CombatPhysicsProfile() {
    }

    public static void resetForNewBattle() {
        cachedCombatEngine = null;
        cachedCombatEvalTime = -999f;
        cachedCombatShipCount = -1;
        cachedLiveWeaponRangeScale = 1.0f;
        cachedLiveFighterRangeScale = 1.0f;
        cachedLiveMapScale = 1.0f;
        cachedLiveMaxFighterSpeedCeiling = 320f;
        SHIP_BEAM_DROPOFF_LISTENER_CACHE.clear();
        SHIP_MAX_GUN_REACH_CACHE.clear();
        clearObstacleSnapshot();
        clearMissileSnapshot();
    }

    private static void clearMissileSnapshot() {
        misCount = 0;
        lastMisEngine = null;
        lastMisElapsed = -999f;
    }

    private static void clearObstacleSnapshot() {
        for (int i = 0; i < obsCount; i++) {
            obsShips[i] = null;
            obsParents[i] = null;
        }
        obsCount = 0;
        lastObsEngine = null;
        lastObsElapsed = -999f;
        lastObsRawListSize = -1;
    }

    public static void invalidateGlobalSpecCache() {
        globalSpecInitialized = false;
        cachedSettingsRef = null;
        resetForNewBattle();
    }

    public static void setTestBeamDropoffOverride(Boolean enabled, float offset, float exp, float mult) {
        testBeamDropoffOverride = enabled;
        testBeamDropoffOffset = offset;
        testBeamDropoffExp = exp;
        testBeamDropoffMult = mult;
        SHIP_BEAM_DROPOFF_LISTENER_CACHE.clear();
    }

    public static void setTestGlobalMultipliersOverride(
            boolean active,
            float gunScale,
            float missileScale,
            float fighterScale,
            float boundsScale,
            float mapScale) {
        testGlobalScaleOverrideActive = active;
        testGlobalGunScale = Math.max(1.0f, gunScale);
        testGlobalMissileScale = Math.max(1.0f, missileScale);
        testGlobalFighterScale = Math.max(1.0f, fighterScale);
        testGlobalBoundsScale = Math.max(1.0f, boundsScale);
        testGlobalMapScale = Math.max(1.0f, mapScale);
        resetForNewBattle();
    }

    // =========================================================================
    // 1. GLOBAL SPEC-ENVIRONMENT SCALING (Campaign UI, Auto-Assigner & Defaults)
    // =========================================================================

    private static void ensureGlobalSpecsEvaluated() {
        SettingsAPI settings = null;
        try {
            settings = Global.getSettings();
        } catch (Throwable ignored) {
        }
        if (globalSpecInitialized && settings == cachedSettingsRef) {
            return;
        }
        globalSpecInitialized = true;
        cachedSettingsRef = settings;
        cachedGlobalGunRangeScale = 1.0f;
        cachedGlobalMissileRangeScale = 1.0f;
        cachedGlobalFighterRangeScale = 1.0f;
        cachedGlobalShipBoundsScale = 1.0f;
        cachedGlobalSightScale = 1.0f;
        cachedGlobalMapScale = 1.0f;
        cachedGlobalShortGunCeiling = 650f;
        cachedGlobalHybridCloseCeiling = 700f;
        cachedGlobalLongGunFloor = 900f;
        cachedGlobalBeamDropoffEnabled = false;
        cachedBeamDropoffOffset = 0.30f;
        cachedBeamDropoffExp = 1.20f;
        cachedBeamDropoffMult = 1.00f;

        if (settings == null) {
            return;
        }

        // 1a. Measure loaded non-PD ballistic/energy & missile WeaponSpec range distributions
        try {
            List<WeaponSpecAPI> allWeapons = settings.getAllWeaponSpecs();
            if (allWeapons != null && allWeapons.size() >= 12) {
                List<Float> directGunRanges = new ArrayList<>(allWeapons.size());
                List<Float> missileRanges = new ArrayList<>(allWeapons.size() / 2);
                for (int i = 0, n = allWeapons.size(); i < n; i++) {
                    WeaponSpecAPI ws = allWeapons.get(i);
                    if (ws == null) continue;
                    WeaponType wt = ws.getType();
                    float r = ws.getMaxRange();
                    if (r <= 150f || r > 40000f) continue;
                    Set<AIHints> hints = ws.getAIHints();
                    if (hints != null && (hints.contains(AIHints.PD_ONLY)
                            || (hints.contains(AIHints.PD) && !hints.contains(AIHints.PD_ALSO)))) {
                        continue;
                    }
                    if (wt == WeaponType.BALLISTIC || wt == WeaponType.ENERGY) {
                        if (r <= 25000f) {
                            directGunRanges.add(r);
                        }
                    } else if (wt == WeaponType.MISSILE) {
                        if (hints == null || !hints.contains(AIHints.BOMB)) {
                            missileRanges.add(r);
                        }
                    }
                }
                if (directGunRanges.size() >= 10) {
                    Collections.sort(directGunRanges);
                    int sz = directGunRanges.size();
                    float p25 = directGunRanges.get(Math.min(sz - 1, (int) (sz * 0.25f)));
                    float p50 = directGunRanges.get(sz / 2);
                    float p68 = directGunRanges.get(Math.min(sz - 1, (int) (sz * 0.68f)));
                    float rawScale = p50 / VANILLA_DIRECT_GUN_MEDIAN_RANGE;
                    if (rawScale > 1.28f) {
                        cachedGlobalGunRangeScale = clamp(rawScale, 1.0f, 6.0f);
                        cachedGlobalShortGunCeiling = Math.max(650f, p25);
                        cachedGlobalHybridCloseCeiling = Math.max(700f, p25 * 1.05f);
                        cachedGlobalLongGunFloor = Math.max(cachedGlobalShortGunCeiling + 180f, Math.max(900f, p68));
                    }
                }
                if (missileRanges.size() >= 8) {
                    Collections.sort(missileRanges);
                    float m50 = missileRanges.get(missileRanges.size() / 2);
                    float rawMisScale = m50 / 1500f;
                    if (rawMisScale > 1.28f) {
                        cachedGlobalMissileRangeScale = clamp(rawMisScale, 1.0f, 6.0f);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 1b. Measure loaded non-support FighterWingSpec engagement range distribution
        try {
            List<FighterWingSpecAPI> allWings = settings.getAllFighterWingSpecs();
            if (allWings != null && allWings.size() >= 6) {
                List<Float> wingRanges = new ArrayList<>(allWings.size());
                for (int i = 0, n = allWings.size(); i < n; i++) {
                    FighterWingSpecAPI fw = allWings.get(i);
                    if (fw == null || fw.isSupport() || fw.hasTag("auto_escort")) continue;
                    float wr = fw.getRange();
                    if (wr > 400f && wr < 100000f) {
                        wingRanges.add(wr);
                    }
                }
                if (wingRanges.size() >= 4) {
                    Collections.sort(wingRanges);
                    float medWing = wingRanges.get(wingRanges.size() / 2);
                    float rawWingScale = medWing / VANILLA_FIGHTER_ENGAGE_RANGE;
                    if (rawWingScale > 1.25f) {
                        cachedGlobalFighterRangeScale = clamp(rawWingScale, 1.0f, 8.0f);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 1c. Read Better Combat (bcomSettings.json + optional LunaLib override) multipliers & beam dropoff curve
        try {
            if (settings.getModManager() != null && settings.getModManager().isModEnabled("bcom")) {
                Object bcomJson = JsonReflectBridge.loadMergedModJson("data/config/bcomSettings.json", "bcom");
                boolean useLuna = false;
                try {
                    if (settings.getModManager().isModEnabled("lunalib")) {
                        Boolean lEn = lunalib.lunaSettings.LunaSettings.getBoolean("bcom", "LUNALIB_SETTINGS_ENABLED");
                        useLuna = Boolean.TRUE.equals(lEn);
                    }
                } catch (Throwable ignored) {
                }
                if (bcomJson != null || useLuna) {
                    boolean dropFeat = readBcomBool(bcomJson, useLuna, "beamDamageDropoffFeature", "ENABLE_BEAM_DROPOFF", false);
                    if (dropFeat) {
                        cachedGlobalBeamDropoffEnabled = true;
                        cachedBeamDropoffOffset = clamp(
                                readBcomFloat(bcomJson, useLuna, "beamDropoffOffset", "BEAM_DROPOFF_OFFSET", 0.30f), 0.0f, 0.95f);
                        cachedBeamDropoffExp = clamp(
                                readBcomFloat(bcomJson, useLuna, "beamDropoffExp", "BEAM_DROPOFF_EXP", 1.20f), 0.1f, 5.0f);
                        cachedBeamDropoffMult = clamp(
                                readBcomFloat(bcomJson, useLuna, "beamDropoffMult", "BEAM_DROPOFF_MULT", 1.00f), 0.05f, 4.0f);
                    }
                    if (readBcomBool(bcomJson, useLuna, "weaponRangeFeature", "ENABLE_WEAPON_RANGE", false)) {
                        float wExp = readBcomFloat(bcomJson, useLuna, "weaponRangeExponent", "WEAPON_RANGE_EXPONENT", 1.0f);
                        float wMult = readBcomFloat(bcomJson, useLuna, "weaponRangeMultiplier", "WEAPON_RANGE_MULT", 1.0f);
                        float wAdd = readBcomFloat(bcomJson, useLuna, "weaponRangeAddition", "WEAPON_RANGE_ADDITION", 0.0f);
                        float bcomMed = ((float) StrictMath.pow(VANILLA_DIRECT_GUN_MEDIAN_RANGE, wExp)) * wMult + wAdd;
                        float bcomGunScale = clamp(bcomMed / VANILLA_DIRECT_GUN_MEDIAN_RANGE, 1.0f, 6.0f);
                        if (bcomGunScale > cachedGlobalGunRangeScale) {
                            cachedGlobalGunRangeScale = bcomGunScale;
                            float bcomP25 = ((float) StrictMath.pow(550f, wExp)) * wMult + wAdd;
                            float bcomP68 = ((float) StrictMath.pow(850f, wExp)) * wMult + wAdd;
                            cachedGlobalShortGunCeiling = Math.max(cachedGlobalShortGunCeiling, bcomP25);
                            cachedGlobalHybridCloseCeiling = Math.max(cachedGlobalHybridCloseCeiling, bcomP25 * 1.05f);
                            cachedGlobalLongGunFloor = Math.max(cachedGlobalShortGunCeiling + 180f,
                                    Math.max(cachedGlobalLongGunFloor, bcomP68));
                        }
                    }
                    if (readBcomBool(bcomJson, useLuna, "missileRangeFeature", "ENABLE_MISSILE_RANGE", false)) {
                        float mMult = readBcomFloat(bcomJson, useLuna, "missileRangeMultiplierStat", "MISSILE_RANGE_MULT_STAT", 1.0f);
                        cachedGlobalMissileRangeScale = Math.max(cachedGlobalMissileRangeScale, clamp(mMult, 1.0f, 6.0f));
                    }
                    if (readBcomBool(bcomJson, useLuna, "fighterRangeFeature", "ENABLE_FIGHTER_RANGE", false)) {
                        float fMult = readBcomFloat(bcomJson, useLuna, "fighterRangeMultiplier", "FIGHTER_RANGE_MULT", 1.0f);
                        cachedGlobalFighterRangeScale = Math.max(cachedGlobalFighterRangeScale, clamp(fMult, 1.0f, 8.0f));
                    }
                    if (readBcomBool(bcomJson, useLuna, "shipBoundsFeature", "ENABLE_SHIP_BOUNDS", false)) {
                        float bMult = readBcomFloat(bcomJson, useLuna, "shipBoundsMultiplier", "SHIP_BOUNDS_MULT", 1.0f);
                        cachedGlobalShipBoundsScale = Math.max(cachedGlobalShipBoundsScale, clamp(bMult, 1.0f, 5.0f));
                    }
                    if (readBcomBool(bcomJson, useLuna, "sightFeature", "ENABLE_SIGHT", false)) {
                        float sMult = readBcomFloat(bcomJson, useLuna, "sightMult", "SIGHT_MULT", 1.0f);
                        cachedGlobalSightScale = Math.max(cachedGlobalSightScale, clamp(sMult, 1.0f, 6.0f));
                    }
                    if (readBcomBool(bcomJson, useLuna, "mapFeature", "ENABLE_MAP", false)) {
                        float mapMult = readBcomFloat(bcomJson, useLuna, "mapSizeFactor", "MAP_SIZE_FACTOR", 1.0f);
                        cachedGlobalMapScale = Math.max(cachedGlobalMapScale, clamp(mapMult, 1.0f, 6.0f));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean readBcomBool(Object bcomJson, boolean useLuna, String jsonKey, String lunaKey, boolean defVal) throws Throwable {
        if (useLuna) {
            try {
                Boolean b = lunalib.lunaSettings.LunaSettings.getBoolean("bcom", lunaKey);
                if (b != null) return b;
            } catch (Throwable ignored) {
            }
        }
        return bcomJson != null ? JsonReflectBridge.optBoolean(bcomJson, jsonKey, defVal) : defVal;
    }

    private static float readBcomFloat(Object bcomJson, boolean useLuna, String jsonKey, String lunaKey, float defVal) throws Throwable {
        if (useLuna) {
            try {
                Double d = lunalib.lunaSettings.LunaSettings.getDouble("bcom", lunaKey);
                if (d != null) return d.floatValue();
            } catch (Throwable ignored) {
            }
        }
        return bcomJson != null ? JsonReflectBridge.optFloat(bcomJson, jsonKey, defVal) : defVal;
    }

    public static float getGlobalWeaponRangeScale() {
        if (testGlobalScaleOverrideActive) return testGlobalGunScale;
        ensureGlobalSpecsEvaluated();
        return cachedGlobalGunRangeScale;
    }

    public static float getGlobalMissileRangeScale() {
        if (testGlobalScaleOverrideActive) return testGlobalMissileScale;
        ensureGlobalSpecsEvaluated();
        return cachedGlobalMissileRangeScale;
    }

    public static float getGlobalFighterRangeScale() {
        if (testGlobalScaleOverrideActive) return testGlobalFighterScale;
        ensureGlobalSpecsEvaluated();
        return cachedGlobalFighterRangeScale;
    }

    public static float getGlobalShipBoundsScale() {
        if (testGlobalScaleOverrideActive) return testGlobalBoundsScale;
        ensureGlobalSpecsEvaluated();
        return cachedGlobalShipBoundsScale;
    }

    public static float getGlobalSightScale() {
        if (testGlobalScaleOverrideActive) return Math.max(1.0f, testGlobalGunScale * 0.85f);
        ensureGlobalSpecsEvaluated();
        return cachedGlobalSightScale;
    }

    public static float getGlobalMapScale() {
        if (testGlobalScaleOverrideActive) return testGlobalMapScale;
        ensureGlobalSpecsEvaluated();
        return cachedGlobalMapScale;
    }

    public static boolean isGlobalScaledEnvironment() {
        return getGlobalWeaponRangeScale() > 1.18f
                || getGlobalShipBoundsScale() > 1.18f
                || getGlobalFighterRangeScale() > 1.22f;
    }

    /**
     * Multiplicative factor applied to vanilla intra-division {@code stationSpacing} defaults ({@code 1.0f} in vanilla).
     * Accounts for both inflated flocking/broadphase bounds (e.g. Better Combat {@code shipBoundsMultiplier = 3})
     * and expanded weapon envelopes so ships hold clean, repulsion-free spacing out of the box.
     */
    public static float getGlobalStationSpacingMult() {
        float wScale = getGlobalWeaponRangeScale();
        float bScale = getGlobalShipBoundsScale();
        if (wScale <= 1.18f && bScale <= 1.18f) {
            return 1.0f;
        }
        return clamp(Math.max(bScale * 0.85f, 0.45f + 0.55f * wScale), 1.0f, 4.0f);
    }

    /**
     * Multiplicative factor applied to vanilla inter-division {@code divisionInterval} defaults ({@code 1.0f} in vanilla).
     */
    public static float getGlobalDivisionIntervalMult() {
        float wScale = getGlobalWeaponRangeScale();
        float bScale = getGlobalShipBoundsScale();
        if (wScale <= 1.18f && bScale <= 1.18f) {
            return 1.0f;
        }
        return clamp(Math.max(bScale * 0.95f, 0.30f + 0.70f * wScale), 1.0f, 4.5f);
    }

    /**
     * Multiplicative factor applied to vanilla doctrinal {@code standoffRange} defaults ({@code 1.0f} in vanilla).
     */
    public static float getGlobalStandoffMult(boolean carrierType, boolean missileType) {
        float wScale = getGlobalWeaponRangeScale();
        float mScale = getGlobalMissileRangeScale();
        float fScale = getGlobalFighterRangeScale();
        if (wScale <= 1.18f && mScale <= 1.18f && (!carrierType || fScale <= 1.22f)) {
            return 1.0f;
        }
        if (carrierType) {
            return clamp(Math.max(wScale * 1.10f, fScale * 0.80f), 1.0f, 6.0f);
        }
        if (missileType) {
            return clamp(Math.max(wScale, mScale), 1.0f, 5.5f);
        }
        return clamp(wScale, 1.0f, 5.5f);
    }

    public static float getMissileRangeScale(CombatEngineAPI engine) {
        float wScale = getWeaponRangeScale(engine);
        float mScale = getGlobalMissileRangeScale();
        if (wScale <= 1.18f && mScale <= 1.18f) {
            return 1.0f;
        }
        return clamp(Math.max(mScale, wScale * 0.85f), 1.0f, 6.0f);
    }

    public static float getShortWeaponRangeCeiling(float shipAvgWeaponRangeHint) {
        float gScale = getGlobalWeaponRangeScale();
        if (gScale > 1.01f) {
            return testGlobalScaleOverrideActive ? (650f * gScale * 0.90f) : cachedGlobalShortGunCeiling;
        }
        // Fallback for standalone unit/sim harnesses with scaled ship weapons without global SettingsAPI specs
        if (shipAvgWeaponRangeHint > 1650f) {
            float estScale = clamp(shipAvgWeaponRangeHint / 900f, 1.0f, 5.5f);
            return 650f * estScale;
        }
        return 650f;
    }

    public static float getHybridCloseWeaponRangeCeiling(float shipAvgWeaponRangeHint) {
        float gScale = getGlobalWeaponRangeScale();
        if (gScale > 1.01f) {
            return testGlobalScaleOverrideActive ? (700f * gScale * 0.90f) : cachedGlobalHybridCloseCeiling;
        }
        if (shipAvgWeaponRangeHint > 1650f) {
            float estScale = clamp(shipAvgWeaponRangeHint / 900f, 1.0f, 5.5f);
            return 700f * estScale;
        }
        return 700f;
    }

    public static float getLongWeaponRangeFloor(float shipAvgWeaponRangeHint) {
        float gScale = getGlobalWeaponRangeScale();
        if (gScale > 1.01f) {
            return testGlobalScaleOverrideActive ? (900f * gScale * 0.95f) : cachedGlobalLongGunFloor;
        }
        if (shipAvgWeaponRangeHint > 1650f) {
            float estScale = clamp(shipAvgWeaponRangeHint / 900f, 1.0f, 5.5f);
            return 900f * estScale;
        }
        return 900f;
    }

    // =========================================================================
    // 2. LIVE COMBAT SCALE TELEMETRY (Cached per C2 tick)
    // =========================================================================

    private static void ensureLiveCombatEvaluated(CombatEngineAPI engine) {
        ensureGlobalSpecsEvaluated();
        if (engine == null) {
            return;
        }
        float now = engine.getTotalElapsedTime(false);
        if (engine == cachedCombatEngine && now > 0f && Math.abs(now - cachedCombatEvalTime) < 0.45f) {
            return;
        }
        List<ShipAPI> ships = engine.getShips();
        int shipCount = (ships != null) ? ships.size() : 0;
        if (engine == cachedCombatEngine
                && shipCount == cachedCombatShipCount
                && Math.abs(now - cachedCombatEvalTime) < 0.45f) {
            return;
        }
        cachedCombatEngine = engine;
        cachedCombatShipCount = shipCount;
        cachedCombatEvalTime = now;

        // Map scale relative to 18,000u vanilla standard span (deadzone <= 1.22 -> 1.0f)
        float mapW = engine.getMapWidth();
        float mapH = engine.getMapHeight();
        float minSpan = (mapW > 0f && mapH > 0f) ? Math.min(mapW, mapH) : VANILLA_REFERENCE_MAP_SPAN;
        float rawMapScale = minSpan / VANILLA_REFERENCE_MAP_SPAN;
        cachedLiveMapScale = (rawMapScale > 1.22f) ? clamp(rawMapScale, 1.0f, 5.0f) : 1.0f;

        // Live deployed ship gun & fighter scale
        float sumMaxGunRange = 0f;
        int gunShipSamples = 0;
        float maxFighterEngageSeen = 0f;
        float maxFighterSpeedSeen = 320f;

        if (ships != null) {
            for (int i = 0; i < shipCount; i++) {
                ShipAPI s = ships.get(i);
                if (s == null || !s.isAlive() || s.isHulk()) continue;
                if (s.isFighter()) {
                    float fSpd = s.getMaxSpeed();
                    if (fSpd > maxFighterSpeedSeen) {
                        maxFighterSpeedSeen = Math.min(900f, fSpd);
                    }
                    continue;
                }
                List<WeaponAPI> weapons = s.getAllWeapons();
                if (weapons != null && !weapons.isEmpty()) {
                    float shipBestGun = 0f;
                    for (int wIdx = 0, wLen = weapons.size(); wIdx < wLen; wIdx++) {
                        WeaponAPI w = weapons.get(wIdx);
                        if (w == null || w.isDecorative()) continue;
                        WeaponType wt = w.getType();
                        if (wt != WeaponType.BALLISTIC && wt != WeaponType.ENERGY) continue;
                        if (w.hasAIHint(AIHints.PD_ONLY)
                                || (w.hasAIHint(AIHints.PD) && !w.hasAIHint(AIHints.PD_ALSO))) {
                            continue;
                        }
                        float wr = w.getRange();
                        if (wr > shipBestGun && wr < 25000f) {
                            shipBestGun = wr;
                        }
                    }
                    if (shipBestGun > 200f) {
                        sumMaxGunRange += shipBestGun;
                        gunShipSamples++;
                    }
                }
                List<FighterWingAPI> wings = s.getAllWings();
                if (wings != null && !wings.isEmpty()) {
                    for (int fIdx = 0, fLen = wings.size(); fIdx < fLen; fIdx++) {
                        FighterWingAPI fw = wings.get(fIdx);
                        if (fw == null || fw.getSpec() == null || fw.getSpec().isSupport()) continue;
                        float wr = VoidstrikeCoordinator.resolveEffectiveWingEngagementRange(s, fw);
                        if (wr > maxFighterEngageSeen && wr < 100000f) {
                            maxFighterEngageSeen = wr;
                        }
                        List<ShipAPI> fMembers = fw.getWingMembers();
                        if (fMembers != null && !fMembers.isEmpty()) {
                            ShipAPI fm0 = fMembers.get(0);
                            if (fm0 != null && fm0.getMaxSpeed() > maxFighterSpeedSeen) {
                                maxFighterSpeedSeen = Math.min(900f, fm0.getMaxSpeed());
                            }
                        }
                    }
                }
            }
        }

        float liveGunScale = 1.0f;
        if (gunShipSamples >= 2) {
            float avgBestGun = sumMaxGunRange / gunShipSamples;
            // Vanilla fleet average longest direct gun (including ITU capitals) is ~950-1180u; deadzone at 1380u
            if (avgBestGun > 1380f) {
                liveGunScale = clamp(avgBestGun / 1050f, 1.0f, 6.0f);
            }
        }
        float gGunScale = getGlobalWeaponRangeScale();
        float gFighterScale = getGlobalFighterRangeScale();
        cachedLiveWeaponRangeScale = Math.max(gGunScale, liveGunScale);

        float liveWingScale = (maxFighterEngageSeen > 5200f)
                ? clamp(maxFighterEngageSeen / VANILLA_FIGHTER_ENGAGE_RANGE, 1.0f, 8.0f)
                : 1.0f;
        cachedLiveFighterRangeScale = Math.max(gFighterScale, liveWingScale);
        cachedLiveMaxFighterSpeedCeiling = Math.max(320f, maxFighterSpeedSeen * 1.08f);
    }

    public static float getWeaponRangeScale(CombatEngineAPI engine) {
        if (engine == null) {
            try {
                engine = Global.getCombatEngine();
            } catch (Throwable ignored) {
            }
        }
        ensureLiveCombatEvaluated(engine);
        return (engine != null) ? Math.max(getGlobalWeaponRangeScale(), cachedLiveWeaponRangeScale) : getGlobalWeaponRangeScale();
    }

    public static float getFighterRangeScale(CombatEngineAPI engine) {
        if (engine == null) {
            try {
                engine = Global.getCombatEngine();
            } catch (Throwable ignored) {
            }
        }
        ensureLiveCombatEvaluated(engine);
        return (engine != null) ? Math.max(getGlobalFighterRangeScale(), cachedLiveFighterRangeScale) : getGlobalFighterRangeScale();
    }

    public static float getMapScale(CombatEngineAPI engine) {
        if (engine == null) {
            try {
                engine = Global.getCombatEngine();
            } catch (Throwable ignored) {
            }
        }
        ensureLiveCombatEvaluated(engine);
        return (engine != null) ? cachedLiveMapScale : getGlobalMapScale();
    }

    public static float getFighterPackageSpeedCeiling(CombatEngineAPI engine) {
        if (engine == null) {
            try {
                engine = Global.getCombatEngine();
            } catch (Throwable ignored) {
            }
        }
        ensureLiveCombatEvaluated(engine);
        return (engine != null) ? cachedLiveMaxFighterSpeedCeiling : 320f;
    }

    // =========================================================================
    // 3. PHYSICAL HULL BOUNDS vs. BROADPHASE COLLISION-RADIUS INFLATION
    // =========================================================================

    /**
     * Returns the ship's true physical hull collision radius from {@link ShipHullSpecAPI#getCollisionRadius()},
     * unaffected by combat plugins that inflate {@code ShipAPI.setCollisionRadius(r * mult)} for broadphase
     * raycasting. Falls back cleanly to {@link ShipAPI#getCollisionRadius()} in headless mocks.
     */
    public static float resolvePhysicalHullRadius(ShipAPI ship) {
        if (ship == null) return 45f;
        float liveCol = ship.getCollisionRadius();
        ShipHullSpecAPI spec = ship.getHullSpec();
        if (spec != null) {
            float specCol = spec.getCollisionRadius();
            if (specCol > 12f && (liveCol <= 1f || liveCol > specCol * 1.30f)) {
                return specCol;
            }
        }
        return liveCol;
    }

    /**
     * Returns the ship's true physical combat envelope (hull radius or active/fitted shield radius, whichever is larger),
     * still immune to unshielded broadphase {@code collisionRadius} inflation.
     */
    public static float resolvePhysicalShieldOrHullRadius(ShipAPI ship) {
        if (ship == null) return 45f;
        float physHull = resolvePhysicalHullRadius(ship);
        ShieldAPI shield = ship.getShield();
        if (shield != null && shield.getType() != ShieldAPI.ShieldType.NONE && shield.getType() != ShieldAPI.ShieldType.PHASE) {
            float sr = shield.getRadius();
            if (sr > physHull && sr < physHull * 2.4f) {
                return sr;
            }
        }
        return physHull;
    }

    /**
     * Ratio of {@code ship.getCollisionRadius()} to {@code ship.getHullSpec().getCollisionRadius()} ({@code 1.0f} in vanilla).
     */
    public static float resolveCollisionRadiusInflation(ShipAPI ship) {
        if (ship == null) return 1.0f;
        ShipHullSpecAPI spec = ship.getHullSpec();
        if (spec == null) return 1.0f;
        float specCol = spec.getCollisionRadius();
        if (specCol <= 12f) return 1.0f;
        float ratio = ship.getCollisionRadius() / specCol;
        return ratio > 1.05f ? ratio : 1.0f;
    }

    /**
     * Reads dynamic {@code explosion_radius_mult} stat so death-blast stand-clear distance scales automatically
     * with hullmods or combat overhauls ({@code 1.0f} in vanilla).
     */
    public static float resolveExplosionRadiusMult(ShipAPI ship) {
        if (ship == null) return 1.0f;
        try {
            MutableShipStatsAPI stats = ship.getMutableStats();
            if (stats != null && stats.getDynamic() != null) {
                float m = stats.getDynamic().getStat("explosion_radius_mult").getModifiedValue();
                if (m > 0.1f && m < 10f) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return 1.0f;
    }

    // =========================================================================
    // 4. NEWTONIAN KINEMATICS & STOPPING HORIZONS
    // =========================================================================

    public static float computeStoppingDistance(float speed, float decel) {
        if (speed <= 1f) return 0f;
        float effDecel = Math.max(8f, decel);
        return (speed * speed) / (2f * effDecel);
    }

    public static float computeStoppingTime(float speed, float decel) {
        if (speed <= 1f) return 0f;
        return speed / Math.max(8f, decel);
    }

    public static float computeShipMaxStoppingDistance(ShipAPI ship) {
        if (ship == null) return 0f;
        float vMax = Math.max(ship.getMaxSpeed(),
                ship.getVelocity() != null ? ship.getVelocity().length() : 0f);
        return computeStoppingDistance(vMax, ship.getDeceleration());
    }

    /**
     * Returns {@code true} when a ship's stopping distance or stopping time exceeds vanilla {@code BasicShipAI}'s
     * hardcoded {@code 200u-500u} / {@code 5.0s} braking lookahead caps. Strictly {@code false} for vanilla hulls.
     */
    public static boolean hasExtremeInertia(ShipAPI ship) {
        if (ship == null || ship.isFighter() || ship.isStation() || ship.isStationModule()) {
            return false;
        }
        float decel = Math.max(8f, ship.getDeceleration());
        float vMax = Math.max(ship.getMaxSpeed(),
                ship.getVelocity() != null ? ship.getVelocity().length() : 0f);
        float dStop = (vMax * vMax) / (2f * decel);
        float tStop = vMax / decel;
        return dStop > VANILLA_MAX_STOPPING_DIST_THRESHOLD || tStop > VANILLA_MAX_STOPPING_TIME_THRESHOLD;
    }

    // =========================================================================
    // 5. BEAM DISTANCE-DROPOFF AWARENESS
    // =========================================================================

    public static boolean isBeamDropoffActive(ShipAPI ship) {
        if (testBeamDropoffOverride != null) {
            return testBeamDropoffOverride.booleanValue();
        }
        ensureGlobalSpecsEvaluated();
        if (cachedGlobalBeamDropoffEnabled) {
            return true;
        }
        if (ship == null) {
            return false;
        }
        Boolean cached = SHIP_BEAM_DROPOFF_LISTENER_CACHE.get(ship);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean found = false;
        try {
            List<DamageDealtModifier> mods = ship.getListeners(DamageDealtModifier.class);
            if (mods != null && !mods.isEmpty()) {
                for (int i = 0, n = mods.size(); i < n; i++) {
                    DamageDealtModifier m = mods.get(i);
                    if (m == null) continue;
                    String cn = m.getClass().getName().toLowerCase(Locale.ROOT);
                    if (cn.contains("beamdamage") || cn.contains("beamdropoff")) {
                        found = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        SHIP_BEAM_DROPOFF_LISTENER_CACHE.put(ship, Boolean.valueOf(found));
        return found;
    }

    /**
     * Evaluates effective beam damage multiplier at {@code dist} relative to {@code weapon.getRange()}
     * ({@code 1.0f} when no beam distance-dropoff modifier is active).
     */
    public static float evaluateBeamDamageMultiplier(ShipAPI ship, WeaponAPI weapon, float dist) {
        if (weapon == null || !weapon.isBeam() || !isBeamDropoffActive(ship)) {
            return 1.0f;
        }
        float range = Math.max(1f, weapon.getRange());
        float offsetRatio = (testBeamDropoffOverride != null) ? testBeamDropoffOffset : cachedBeamDropoffOffset;
        float exp = (testBeamDropoffOverride != null) ? testBeamDropoffExp : cachedBeamDropoffExp;
        float mult = (testBeamDropoffOverride != null) ? testBeamDropoffMult : cachedBeamDropoffMult;

        float offsetDist = offsetRatio * range;
        if (dist <= offsetDist) {
            return 1.0f;
        }
        float past = dist - offsetDist;
        float ratio = Math.max(0f, 1.0f - (past / range));
        float dmgMult = fastUnitPow(ratio, exp) * mult;
        return clamp(dmgMult, 0f, 1.25f);
    }

    /**
     * Returns the effective tactical engagement range of a beam weapon accounting for distance dropoff
     * (so beam-heavy ships seek ranges where beams deal meaningful DPS instead of tickling at max range).
     */
    public static float resolveEffectiveTacticalBeamRange(ShipAPI ship, WeaponAPI weapon) {
        if (weapon == null) return 0f;
        float maxRange = weapon.getRange();
        if (!weapon.isBeam() || !isBeamDropoffActive(ship)) {
            return maxRange;
        }
        float offsetRatio = (testBeamDropoffOverride != null) ? testBeamDropoffOffset : cachedBeamDropoffOffset;
        // Aim for ~65-72% of max range (where dropoff still retains ~55-68% damage)
        float effFrac = clamp(offsetRatio + 0.38f, 0.48f, 0.85f);
        return maxRange * effFrac;
    }

    // =========================================================================
    // 6. PHANTOM FRIENDLY-FIRE UNBLOCKER & BEAM FLUX DISCIPLINE (60 Hz Helper)
    // =========================================================================

    /**
     * Rescues vanilla {@code AutofireAI} when inflated broadphase {@code collisionRadius} (e.g. {@code 3x})
     * plus high ship speeds create 2,500-3,500u-wide phantom friendly-fire hold-fire shadows, and cuts
     * continuous flux-hungry beams when firing into extreme distance dropoff at elevated ship flux.
     *
     * <p>Uses strictly public {@link WeaponAPI#setForceFireOneFrame(boolean)} and
     * {@link WeaponAPI#setForceNoFireOneFrame(boolean)} (never {@code ShipCommand.FIRE}), and steps aside
     * whenever a third-party autofire AI ({@code !grp.isUsingDefaultAI(w)}) is managing the weapon.
     */
    public static void applyAutofireBoundsAndBeamAssist(
            CombatEngineAPI engine,
            ShipAPI ship,
            boolean doNotUseFlux) {
        if (engine == null || ship == null || !ship.isAlive() || ship.isHulk() || ship.isPhased()) {
            return;
        }
        float inflation = resolveCollisionRadiusInflation(ship);
        boolean beamDrop = isBeamDropoffActive(ship);
        if (inflation <= 1.35f && !beamDrop) {
            return; // 0% touch on vanilla Starsector
        }
        ensureFrameObstacleSnapshot(engine);
        if (!hasEnemyWithinShipEngagementHorizon(engine, ship)) {
            return; // Zero weapon-group list allocations when no enemy warship is near gun range
        }
        List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
        if (groups == null || groups.isEmpty()) {
            return;
        }
        float fluxLevel = ship.getFluxLevel();
        boolean holdFire = ship.isHoldFire() || doNotUseFlux;
        for (int gIdx = 0, gLen = groups.size(); gIdx < gLen; gIdx++) {
            WeaponGroupAPI grp = groups.get(gIdx);
            if (grp != null) {
                assistWeaponGroup(ship, grp, inflation, beamDrop, holdFire, fluxLevel);
            }
        }
    }

    private static void assistWeaponGroup(
            ShipAPI ship,
            WeaponGroupAPI grp,
            float inflation,
            boolean beamDrop,
            boolean holdFire,
            float fluxLevel) {
        List<WeaponAPI> weapons = grp.getWeaponsCopy();
        if (weapons == null || weapons.isEmpty()) {
            return;
        }
        boolean groupAutofiring = grp.isAutofiring();
        for (int wIdx = 0, wLen = weapons.size(); wIdx < wLen; wIdx++) {
            WeaponAPI w = weapons.get(wIdx);
            if (w != null && !w.isDisabled() && !w.isPermanentlyDisabled() && !w.isDecorative()) {
                assistSingleWeapon(ship, grp, w, groupAutofiring, inflation, beamDrop, holdFire, fluxLevel);
            }
        }
    }

    private static void assistSingleWeapon(
            ShipAPI ship,
            WeaponGroupAPI grp,
            WeaponAPI w,
            boolean groupAutofiring,
            float inflation,
            boolean beamDrop,
            boolean holdFire,
            float fluxLevel) {
        // A. Beam Flux Discipline under severe distance dropoff
        if (beamDrop && tryBeamDropoffHoldFire(ship, grp, w, groupAutofiring, fluxLevel)) {
            return;
        }
        // B. Phantom Friendly-Fire Unblocker (only for default vanilla autofire groups blocked by inflated bounds)
        if (inflation <= 1.35f || holdFire || fluxLevel >= 0.72f || !groupAutofiring) {
            return;
        }
        if (!grp.isUsingDefaultAI(w)) {
            return; // Respect custom autofire mods (e.g. AI Tweaks)
        }
        if (w.getType() == WeaponType.MISSILE || w.hasAIHint(AIHints.STRIKE)) {
            return; // Never force-fire finite missiles or strike ordnance
        }
        if (w.getCooldownRemaining() > 0f || w.isFiring()) {
            return;
        }
        if (w.usesAmmo() && w.getAmmo() <= 0) {
            return;
        }
        if (w.getDamageType() == DamageType.FRAGMENTATION && w.hasAIHint(AIHints.PD_ONLY)) {
            return;
        }
        tryRescueBlockedAutofireTurret(ship, grp, w, beamDrop);
    }

    private static boolean tryBeamDropoffHoldFire(
            ShipAPI ship,
            WeaponGroupAPI grp,
            WeaponAPI w,
            boolean groupAutofiring,
            float fluxLevel) {
        if (!w.isBeam() || w.isBurstBeam() || w.getFluxCostToFire() <= 0f || fluxLevel < 0.45f) {
            return false;
        }
        AutofireAIPlugin af = groupAutofiring ? grp.getAutofirePlugin(w) : null;
        ShipAPI tgt = (af != null && af.getTargetShip() != null) ? af.getTargetShip() : ship.getShipTarget();
        if (tgt == null || !tgt.isAlive() || tgt.getLocation() == null || w.getLocation() == null) {
            return false;
        }
        float d = DivisionCommanderAI.distance(w.getLocation(), tgt.getLocation())
                - resolvePhysicalShieldOrHullRadius(tgt);
        if (d > w.getRange() * 0.84f && evaluateBeamDamageMultiplier(ship, w, d) < 0.38f) {
            w.setForceNoFireOneFrame(true);
            return true;
        }
        return false;
    }

    private static void tryRescueBlockedAutofireTurret(
            ShipAPI ship,
            WeaponGroupAPI grp,
            WeaponAPI w,
            boolean beamDrop) {
        AutofireAIPlugin af = grp.getAutofirePlugin(w);
        if (af == null || af.shouldFire()) {
            return;
        }
        ShipAPI tgtShip = af.getTargetShip();
        Vector2f wLoc = w.getLocation();
        if (tgtShip == null || !tgtShip.isAlive() || tgtShip.isHulk()
                || tgtShip.getOwner() == ship.getOwner()
                || tgtShip.getLocation() == null
                || wLoc == null) {
            return;
        }
        Vector2f aimPt = (af.getTarget() != null) ? af.getTarget() : tgtShip.getLocation();
        float dx = aimPt.x - wLoc.x;
        float dy = aimPt.y - wLoc.y;
        float distToAim = (float) Math.sqrt(dx * dx + dy * dy);
        float tgtPhysR = resolvePhysicalShieldOrHullRadius(tgtShip);
        if (distToAim <= 20f || distToAim > w.getRange() + tgtPhysR * 0.65f) {
            return;
        }
        if (beamDrop && w.isBeam() && !w.isBurstBeam()
                && evaluateBeamDamageMultiplier(ship, w, distToAim - tgtPhysR) < 0.25f) {
            return;
        }
        float barrelDeg = w.getCurrAngle();
        float rx = fastCosDeg(barrelDeg);
        float ry = fastSinDeg(barrelDeg);
        float forwardDot = dx * rx + dy * ry;
        if (forwardDot <= 0f) {
            return;
        }
        float perpAim = Math.abs(dx * (-ry) + dy * rx);
        float effDist = Math.max(80f, distToAim);
        float radAllow = tgtPhysR * 0.85f;
        float sinAllow = Math.max(0.0349f, radAllow / (float) Math.sqrt(effDist * effDist + radAllow * radAllow));
        if (perpAim > distToAim * sinAllow) {
            return;
        }
        float projSpeed = w.isBeam() ? 100000f : Math.max(250f, w.getProjectileSpeed());
        if (isSnapshotLineOfFireClear(ship, tgtShip, wLoc.x, wLoc.y, rx, ry, distToAim, projSpeed)) {
            w.setForceFireOneFrame(true);
        }
    }

    private static void ensureFrameObstacleSnapshot(CombatEngineAPI engine) {
        if (engine == null) {
            clearObstacleSnapshot();
            return;
        }
        float elapsed = engine.getTotalElapsedTime(false);
        if (elapsed > 0f && engine == lastObsEngine && elapsed == lastObsElapsed) {
            return;
        }
        List<ShipAPI> ships = engine.getShips();
        if (ships == null || ships.isEmpty()) {
            clearObstacleSnapshot();
            return;
        }
        int rawSize = ships.size();
        growObstacleSnapshotCapacity(rawSize);
        int out = 0;
        for (int i = 0; i < rawSize; i++) {
            ShipAPI s = ships.get(i);
            if (s == null || s.isFighter()) continue;
            boolean hulk = s.isHulk();
            if (!hulk && !s.isAlive()) continue;
            Vector2f loc = s.getLocation();
            if (loc == null) continue;
            Vector2f vel = s.getVelocity();
            obsShips[out] = s;
            obsParents[out] = s.getParentStation();
            obsOwners[out] = s.getOwner();
            obsIsHulk[out] = hulk;
            obsOverloadedOrVenting[out] = hulk
                    || (s.getFluxTracker() != null && s.getFluxTracker().isOverloadedOrVenting());
            obsX[out] = loc.x;
            obsY[out] = loc.y;
            obsVx[out] = (vel != null) ? vel.x : 0f;
            obsVy[out] = (vel != null) ? vel.y : 0f;
            obsHullR[out] = resolvePhysicalHullRadius(s);
            obsShieldR[out] = hulk ? obsHullR[out] : resolvePhysicalShieldOrHullRadius(s);
            obsMaxGunReach[out] = hulk ? 0f : resolveCachedMaxNonMissileReach(s, elapsed);
            out++;
        }
        for (int k = out; k < obsCount; k++) {
            obsShips[k] = null;
            obsParents[k] = null;
        }
        obsCount = out;
        lastObsEngine = engine;
        lastObsElapsed = elapsed;
        lastObsRawListSize = rawSize;
    }

    private static float resolveCachedMaxNonMissileReach(ShipAPI ship, float elapsed) {
        float[] slot = SHIP_MAX_GUN_REACH_CACHE.get(ship);
        if (slot != null && elapsed > 0f && Math.abs(elapsed - slot[1]) < 1.5f) {
            return slot[0];
        }
        float best = 0f;
        List<WeaponAPI> weapons = ship.getAllWeapons();
        if (weapons != null) {
            for (int i = 0, n = weapons.size(); i < n; i++) {
                WeaponAPI w = weapons.get(i);
                if (w == null || w.isDisabled() || w.isDecorative() || w.getType() == WeaponType.MISSILE) continue;
                float r = w.isBeam() ? resolveEffectiveTacticalBeamRange(ship, w) : w.getRange();
                if (r > best) {
                    best = r;
                }
            }
        }
        if (slot == null) {
            slot = new float[]{best, elapsed};
            SHIP_MAX_GUN_REACH_CACHE.put(ship, slot);
        } else {
            slot[0] = best;
            slot[1] = elapsed;
        }
        return best;
    }

    private static boolean hasEnemyWithinShipEngagementHorizon(CombatEngineAPI engine, ShipAPI ship) {
        int n = obsCount;
        if (n <= 0 || ship.getLocation() == null) {
            return false;
        }
        float myReach = resolveCachedMaxNonMissileReach(ship, engine.getTotalElapsedTime(false));
        if (myReach <= 50f) {
            // In minimal unit-test mocks where getAllWeapons() is empty (only getWeaponGroupsCopy() mocked), allow scan
            ShipAPI directTgt = ship.getShipTarget();
            if (directTgt != null && directTgt.isAlive()) {
                return true;
            }
            myReach = 4500f;
        }
        float horizon = myReach + 650f;
        float horizonSq = horizon * horizon;
        float mx = ship.getLocation().x;
        float my = ship.getLocation().y;
        int myOwner = ship.getOwner();
        for (int i = 0; i < n; i++) {
            if (obsIsHulk[i] || obsOwners[i] == myOwner || obsOwners[i] == 100) continue;
            float dx = obsX[i] - mx;
            float dy = obsY[i] - my;
            if (dx * dx + dy * dy <= horizonSq) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fast O(warships) flat-array nearest enemy warship lookup (avoids calling {@code engine.getShips()} or scanning fighters).
     */
    public static ShipAPI findNearestEnemyWarship(CombatEngineAPI engine, ShipAPI ship, float maxDist) {
        if (engine == null || ship == null || ship.getLocation() == null) {
            return null;
        }
        ensureFrameObstacleSnapshot(engine);
        int n = obsCount;
        if (n <= 0) return null;
        float mx = ship.getLocation().x;
        float my = ship.getLocation().y;
        int myOwner = ship.getOwner();
        ShipAPI best = null;
        float bestDistSq = maxDist * maxDist;
        for (int i = 0; i < n; i++) {
            if (obsIsHulk[i]) continue;
            int owner = obsOwners[i];
            if (owner == myOwner || owner == 100) continue;
            float dx = obsX[i] - mx;
            float dy = obsY[i] - my;
            float dSq = dx * dx + dy * dy;
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = obsShips[i];
            }
        }
        return best;
    }

    /**
     * Fast O(warships) flat-array check for whether any non-overloaded enemy warship currently holds {@code ship}
     * inside its live non-missile gun envelope (zero {@code getShips()} or {@code getAllWeapons()} calls per query).
     */
    public static boolean isInsideLiveEnemyGunEnvelope(CombatEngineAPI engine, ShipAPI ship) {
        if (engine == null || ship == null || ship.getLocation() == null) {
            return false;
        }
        ensureFrameObstacleSnapshot(engine);
        int n = obsCount;
        if (n <= 0) return false;
        float mx = ship.getLocation().x;
        float my = ship.getLocation().y;
        int myOwner = ship.getOwner();
        float myRad = resolvePhysicalShieldOrHullRadius(ship);
        for (int i = 0; i < n; i++) {
            if (obsIsHulk[i] || obsOverloadedOrVenting[i]) continue;
            int owner = obsOwners[i];
            if (owner == myOwner || owner == 100) continue;
            float maxEnemyGun = obsMaxGunReach[i];
            if (maxEnemyGun <= 400f) continue;
            float limit = maxEnemyGun * 0.96f + myRad;
            float dx = obsX[i] - mx;
            float dy = obsY[i] - my;
            if (dx * dx + dy * dy <= limit * limit) {
                return true;
            }
        }
        return false;
    }

    private static void growObstacleSnapshotCapacity(int needed) {
        if (needed <= obsShips.length) {
            return;
        }
        int cap = obsShips.length;
        while (cap < needed) {
            cap <<= 1;
        }
        obsShips = new ShipAPI[cap];
        obsParents = new ShipAPI[cap];
        obsOwners = new int[cap];
        obsIsHulk = new boolean[cap];
        obsOverloadedOrVenting = new boolean[cap];
        obsX = new float[cap];
        obsY = new float[cap];
        obsVx = new float[cap];
        obsVy = new float[cap];
        obsHullR = new float[cap];
        obsShieldR = new float[cap];
        obsMaxGunReach = new float[cap];
        obsCount = 0;
    }

    // =========================================================================
    // 7. FLAT ARMED-MISSILE SALVO SNAPSHOT (60 Hz Incoming-Ordnance Defense)
    // =========================================================================

    private static void ensureFrameMissileSnapshot(CombatEngineAPI engine) {
        if (engine == null) {
            clearMissileSnapshot();
            return;
        }
        float elapsed = engine.getTotalElapsedTime(false);
        if (elapsed > 0f && engine == lastMisEngine && elapsed == lastMisElapsed) {
            return;
        }
        List<MissileAPI> missiles = engine.getMissiles();
        if (missiles == null || missiles.isEmpty()) {
            misCount = 0;
            lastMisEngine = engine;
            lastMisElapsed = elapsed;
            return;
        }
        int rawSize = missiles.size();
        growMissileSnapshotCapacity(rawSize);
        int out = 0;
        for (int i = 0; i < rawSize; i++) {
            MissileAPI m = missiles.get(i);
            if (m == null || m.isFizzling() || m.isFlare() || m.isExpired()) continue;
            float dmg = m.getDamageAmount();
            if (dmg < 260f) continue;
            Vector2f loc = m.getLocation();
            Vector2f vel = m.getVelocity();
            if (loc == null || vel == null) continue;
            misOwners[out] = m.getOwner();
            misGuided[out] = m.isGuided();
            misX[out] = loc.x;
            misY[out] = loc.y;
            misVx[out] = vel.x;
            misVy[out] = vel.y;
            misDmg[out] = dmg;
            out++;
        }
        misCount = out;
        lastMisEngine = engine;
        lastMisElapsed = elapsed;
    }

    private static void growMissileSnapshotCapacity(int needed) {
        if (needed <= misOwners.length) {
            return;
        }
        int cap = misOwners.length;
        while (cap < needed) {
            cap <<= 1;
        }
        misOwners = new int[cap];
        misGuided = new boolean[cap];
        misX = new float[cap];
        misY = new float[cap];
        misVx = new float[cap];
        misVy = new float[cap];
        misDmg = new float[cap];
        misCount = 0;
    }

    /**
     * Evaluates incoming high-damage enemy missiles/torpedoes closing on {@code ship} within {@code maxTtiSeconds}
     * using contiguous primitive arrays (zero allocations, zero C2-unsafe trig).
     *
     * @param outSummary length >= 5:
     *                   [0] totalInboundDamage,
     *                   [1] minTimeToImpact,
     *                   [2] unitThreatDirX (from ship toward incoming salvo),
     *                   [3] unitThreatDirY,
     *                   [4] lateralMissSign (+1 or -1 perpendicular to threat vector for evasive strafe)
     */
    public static boolean evaluateInboundMissileSalvo(
            CombatEngineAPI engine,
            ShipAPI ship,
            float maxTtiSeconds,
            float[] outSummary) {
        if (engine == null || ship == null || ship.getLocation() == null || outSummary == null || outSummary.length < 5) {
            return false;
        }
        ensureFrameMissileSnapshot(engine);
        int n = misCount;
        if (n <= 0) {
            return false;
        }
        Vector2f sLoc = ship.getLocation();
        Vector2f sVel = ship.getVelocity();
        float sx = sLoc.x;
        float sy = sLoc.y;
        float svx = (sVel != null) ? sVel.x : 0f;
        float svy = (sVel != null) ? sVel.y : 0f;
        int myOwner = ship.getOwner();
        float hitRad = resolvePhysicalShieldOrHullRadius(ship);

        float totalDmg = 0f;
        float minTti = maxTtiSeconds + 1f;
        float sumTx = 0f;
        float sumTy = 0f;
        float sumLatSign = 0f;

        for (int i = 0; i < n; i++) {
            int mOwner = misOwners[i];
            if (mOwner == myOwner || mOwner == 100) continue;
            // Vector from missile -> ship
            float rx = sx - misX[i];
            float ry = sy - misY[i];
            float distSq = rx * rx + ry * ry;
            if (distSq < 4f || distSq > 4200f * 4200f) continue;

            // Relative velocity of missile toward ship
            float rvx = misVx[i] - svx;
            float rvy = misVy[i] - svy;
            float relSpdSq = rvx * rvx + rvy * rvy;
            if (relSpdSq < 140f * 140f) continue;

            float dist = (float) Math.sqrt(distSq);
            float invDist = 1.0f / dist;
            float ux = rx * invDist;
            float uy = ry * invDist;
            float closingSpd = rvx * ux + rvy * uy;
            if (closingSpd <= 120f) continue;

            float effDist = Math.max(1f, dist - hitRad);
            float tti = effDist / closingSpd;
            if (tti > maxTtiSeconds) continue;

            // Closest-approach perpendicular miss distance
            float relSpd = (float) Math.sqrt(relSpdSq);
            float mvx = rvx / relSpd;
            float mvy = rvy / relSpd;
            float signedPerp = rx * (-mvy) + ry * mvx;
            float allowMiss = hitRad + (misGuided[i] ? 240f : 95f);
            if (Math.abs(signedPerp) > allowMiss) continue;

            float w = misDmg[i];
            totalDmg += w;
            if (tti < minTti) {
                minTti = tti;
            }
            // Direction from ship TOWARD incoming missile (`-ux, -uy`)
            sumTx += (-ux) * w;
            sumTy += (-uy) * w;
            sumLatSign += (signedPerp >= 0f ? 1f : -1f) * w;
        }

        if (totalDmg <= 0f) {
            return false;
        }
        float tLen = (float) Math.sqrt(sumTx * sumTx + sumTy * sumTy);
        outSummary[0] = totalDmg;
        outSummary[1] = minTti;
        outSummary[2] = (tLen > 1e-4f) ? (sumTx / tLen) : 0f;
        outSummary[3] = (tLen > 1e-4f) ? (sumTy / tLen) : 1f;
        outSummary[4] = (sumLatSign >= 0f) ? 1f : -1f;
        return true;
    }

    private static boolean isSnapshotLineOfFireClear(
            ShipAPI shooter,
            ShipAPI target,
            float ox,
            float oy,
            float rx,
            float ry,
            float targetDist,
            float projSpeed) {
        int n = obsCount;
        if (n <= 0) {
            return true;
        }
        int myOwner = shooter.getOwner();
        ShipAPI myParent = shooter.getParentStation();
        float maxAlong = targetDist - 15f;

        for (int i = 0; i < n; i++) {
            ShipAPI other = obsShips[i];
            if (other == shooter || other == target) continue;
            if (obsParents[i] == shooter || myParent == other) continue;

            boolean isHulk = obsIsHulk[i];
            boolean isFriendly = !isHulk && (obsOwners[i] == myOwner);
            if (!isFriendly && !isHulk) continue;

            float vx = obsX[i] - ox;
            float vy = obsY[i] - oy;
            float along = vx * rx + vy * ry;
            if (along <= 15f || along >= maxAlong) continue;

            float signedPerp = vx * (-ry) + vy * rx;
            float perp = Math.abs(signedPerp);
            float safety = isFriendly ? (obsShieldR[i] * 1.18f + 18f) : (obsHullR[i] * 1.02f + 18f);
            if (isFriendly && projSpeed < 50000f) {
                float allyPerpVel = obsVx[i] * (-ry) + obsVy[i] * rx;
                if (signedPerp * allyPerpVel < 0f) {
                    float tFlight = Math.min(2.0f, along / projSpeed);
                    safety += Math.abs(allyPerpVel) * tFlight * 0.85f;
                }
            }
            if (perp < safety) {
                return false;
            }
        }
        return true;
    }

    private static float shortestAngleDiff(float fromDeg, float toDeg) {
        float d = (toDeg - fromDeg) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
