package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.FighterWingAPI;
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

    // Global spec-environment cache (computed lazily from SettingsAPI specs)
    private static boolean globalSpecInitialized = false;
    private static SettingsAPI cachedSettingsRef = null;
    private static float cachedGlobalGunRangeScale = 1.0f;
    private static float cachedGlobalFighterRangeScale = 1.0f;
    private static float cachedGlobalShortGunCeiling = 650f;
    private static float cachedGlobalHybridCloseCeiling = 700f;
    private static float cachedGlobalLongGunFloor = 900f;
    private static boolean cachedGlobalBeamDropoffEnabled = false;
    private static float cachedBeamDropoffOffset = 0.30f;
    private static float cachedBeamDropoffExp = 1.20f;
    private static float cachedBeamDropoffMult = 1.00f;

    // Live per-battle scale cache (updated O(N) at most once per C2 tick)
    private static CombatEngineAPI cachedCombatEngine = null;
    private static float cachedCombatEvalTime = -999f;
    private static int cachedCombatShipCount = -1;
    private static float cachedLiveWeaponRangeScale = 1.0f;
    private static float cachedLiveFighterRangeScale = 1.0f;
    private static float cachedLiveMapScale = 1.0f;
    private static float cachedLiveMaxFighterSpeedCeiling = 320f;

    // Per-ship listener inspection cache (cleared automatically by WeakHashMap)
    private static final WeakHashMap<ShipAPI, Boolean> SHIP_BEAM_DROPOFF_LISTENER_CACHE = new WeakHashMap<>();

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
    private static float[] obsX = new float[128];
    private static float[] obsY = new float[128];
    private static float[] obsVx = new float[128];
    private static float[] obsVy = new float[128];
    private static float[] obsHullR = new float[128];
    private static float[] obsShieldR = new float[128];

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

    private static float fastSinDeg(float deg) {
        int idx = Math.round(deg * DEG_TO_INDEX) & TRIG_MASK;
        return SIN_TABLE[idx];
    }

    private static float fastCosDeg(float deg) {
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
        clearObstacleSnapshot();
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
        cachedGlobalFighterRangeScale = 1.0f;
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

        // 1a. Measure loaded non-PD ballistic/energy WeaponSpec range distribution
        try {
            List<WeaponSpecAPI> allWeapons = settings.getAllWeaponSpecs();
            if (allWeapons != null && allWeapons.size() >= 12) {
                List<Float> directGunRanges = new ArrayList<>(allWeapons.size());
                for (int i = 0, n = allWeapons.size(); i < n; i++) {
                    WeaponSpecAPI ws = allWeapons.get(i);
                    if (ws == null) continue;
                    WeaponType wt = ws.getType();
                    if (wt != WeaponType.BALLISTIC && wt != WeaponType.ENERGY) continue;
                    float r = ws.getMaxRange();
                    if (r <= 150f || r > 25000f) continue;
                    Set<AIHints> hints = ws.getAIHints();
                    if (hints != null && (hints.contains(AIHints.PD_ONLY)
                            || (hints.contains(AIHints.PD) && !hints.contains(AIHints.PD_ALSO)))) {
                        continue;
                    }
                    directGunRanges.add(r);
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

        // 1c. Detect configured beam distance-dropoff curve if active (e.g. bcomSettings.json)
        try {
            if (settings.getModManager() != null && settings.getModManager().isModEnabled("bcom")) {
                Object bcomJson = JsonReflectBridge.loadMergedModJson("data/config/bcomSettings.json", "bcom");
                if (bcomJson != null) {
                    boolean dropFeat = JsonReflectBridge.optBoolean(bcomJson, "beamDamageDropoffFeature", false);
                    if (dropFeat) {
                        cachedGlobalBeamDropoffEnabled = true;
                        cachedBeamDropoffOffset = clamp(
                                JsonReflectBridge.optFloat(bcomJson, "beamDropoffOffset", 0.30f), 0.0f, 0.95f);
                        cachedBeamDropoffExp = clamp(
                                JsonReflectBridge.optFloat(bcomJson, "beamDropoffExp", 1.20f), 0.1f, 5.0f);
                        cachedBeamDropoffMult = clamp(
                                JsonReflectBridge.optFloat(bcomJson, "beamDropoffMult", 1.00f), 0.05f, 4.0f);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static float getGlobalWeaponRangeScale() {
        ensureGlobalSpecsEvaluated();
        return cachedGlobalGunRangeScale;
    }

    public static float getGlobalFighterRangeScale() {
        ensureGlobalSpecsEvaluated();
        return cachedGlobalFighterRangeScale;
    }

    public static float getShortWeaponRangeCeiling(float shipAvgWeaponRangeHint) {
        ensureGlobalSpecsEvaluated();
        if (cachedGlobalGunRangeScale > 1.01f) {
            return cachedGlobalShortGunCeiling;
        }
        // Fallback for standalone unit/sim harnesses with scaled ship weapons without global SettingsAPI specs
        if (shipAvgWeaponRangeHint > 1650f) {
            float estScale = clamp(shipAvgWeaponRangeHint / 900f, 1.0f, 5.5f);
            return 650f * estScale;
        }
        return 650f;
    }

    public static float getHybridCloseWeaponRangeCeiling(float shipAvgWeaponRangeHint) {
        ensureGlobalSpecsEvaluated();
        if (cachedGlobalGunRangeScale > 1.01f) {
            return cachedGlobalHybridCloseCeiling;
        }
        if (shipAvgWeaponRangeHint > 1650f) {
            float estScale = clamp(shipAvgWeaponRangeHint / 900f, 1.0f, 5.5f);
            return 700f * estScale;
        }
        return 700f;
    }

    public static float getLongWeaponRangeFloor(float shipAvgWeaponRangeHint) {
        ensureGlobalSpecsEvaluated();
        if (cachedGlobalGunRangeScale > 1.01f) {
            return cachedGlobalLongGunFloor;
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
        cachedLiveWeaponRangeScale = Math.max(cachedGlobalGunRangeScale, liveGunScale);

        float liveWingScale = (maxFighterEngageSeen > 5200f)
                ? clamp(maxFighterEngageSeen / VANILLA_FIGHTER_ENGAGE_RANGE, 1.0f, 8.0f)
                : 1.0f;
        cachedLiveFighterRangeScale = Math.max(cachedGlobalFighterRangeScale, liveWingScale);
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
        return (engine != null) ? cachedLiveWeaponRangeScale : cachedGlobalGunRangeScale;
    }

    public static float getFighterRangeScale(CombatEngineAPI engine) {
        if (engine == null) {
            try {
                engine = Global.getCombatEngine();
            } catch (Throwable ignored) {
            }
        }
        ensureLiveCombatEvaluated(engine);
        return (engine != null) ? cachedLiveFighterRangeScale : cachedGlobalFighterRangeScale;
    }

    public static float getMapScale(CombatEngineAPI engine) {
        if (engine == null) {
            try {
                engine = Global.getCombatEngine();
            } catch (Throwable ignored) {
            }
        }
        ensureLiveCombatEvaluated(engine);
        return (engine != null) ? cachedLiveMapScale : 1.0f;
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
        List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
        if (groups == null || groups.isEmpty()) {
            return;
        }
        if (inflation > 1.35f) {
            ensureFrameObstacleSnapshot(engine);
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
        List<ShipAPI> ships = engine.getShips();
        if (ships == null || ships.isEmpty()) {
            clearObstacleSnapshot();
            return;
        }
        float elapsed = engine.getTotalElapsedTime(false);
        int rawSize = ships.size();
        if (elapsed > 0f && engine == lastObsEngine && elapsed == lastObsElapsed && rawSize == lastObsRawListSize) {
            return;
        }
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
            obsX[out] = loc.x;
            obsY[out] = loc.y;
            obsVx[out] = (vel != null) ? vel.x : 0f;
            obsVy[out] = (vel != null) ? vel.y : 0f;
            obsHullR[out] = resolvePhysicalHullRadius(s);
            obsShieldR[out] = hulk ? obsHullR[out] : resolvePhysicalShieldOrHullRadius(s);
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
        obsX = new float[cap];
        obsY = new float[cap];
        obsVx = new float[cap];
        obsVy = new float[cap];
        obsHullR = new float[cap];
        obsShieldR = new float[cap];
        obsCount = 0;
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
