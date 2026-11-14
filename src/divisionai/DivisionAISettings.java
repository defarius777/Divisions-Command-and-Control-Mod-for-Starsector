package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.DivisionIntentType;
import lunalib.backend.ui.components.LunaUIRadioButton;
import lunalib.backend.ui.components.LunaUITextFieldWithSlider;
import lunalib.backend.ui.components.base.LunaUIBaseElement;
import lunalib.backend.ui.components.base.LunaUIButton;
import lunalib.backend.ui.settings.ChangedSetting;
import lunalib.backend.ui.settings.LunaSettingsData;
import lunalib.backend.ui.settings.LunaSettingsLoader;
import lunalib.backend.ui.settings.LunaSettingsUISettingsPanel;
import lunalib.lunaSettings.LunaSettings;

import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Mod Settings (via LunaLib's Mod Settings menu) to control on-screen HUD chatter
 * by side (Friendly vs Enemy) and level (OTC, Single-Division, Multi-Division),
 * as well as Tactical Map (Tab) division visualization and the "Reinitialize Sector-Wide
 * Division Lists" action button with an "Are you sure?" confirmation prompt.
 *
 * Note: starsector.log always records 100% of C2 telemetry regardless of HUD mute settings.
 */
public class DivisionAISettings {

    public static final String MOD_ID = "division_ai";
    public static final String REINIT_FIELD_ID = "divai_reinit_sector_divisions";
    public static final String CHATTER_VERBOSITY_FIELD_ID = "divai_chatter_verbosity";
    public static final String TSFC_GRAPHICS_QUALITY_FIELD_ID = "divai_tsfc_graphics_quality";
    public static final String TSFC_MINI_SHIP_SPRITES_FIELD_ID = "divai_tsfc_mini_ship_sprites";
    public static final String TSFC_WARROOM_OPACITY_PCT_FIELD_ID = "divai_tsfc_warroom_opacity_pct";
    public static final String TSFC_BATTLE_OPACITY_PCT_FIELD_ID = "divai_tsfc_battle_opacity_pct";
    public static final String ENABLE_BATTLE_MUSIC_FIELD_ID = "divai_enable_battle_music";
    public static final String BATTLE_MUSIC_CONTROL_FIELD_ID = "divai_battle_music_control";
    public static final String SETTINGS_JSON_ENABLE_BATTLE_MUSIC_KEY = "divisionai_enableDynamicBattleMusic";
    public static final String ENABLE_DEBUG_LOGGING_FIELD_ID = "divai_enable_debug_logging";
    public static final String VERBOSE_AI_DEBUG_LOGGING_FIELD_ID = "divai_verbose_ai_debug_logging";
    public static final String SETTINGS_JSON_ENABLE_DEBUG_LOGGING_KEY = "divisionai_enableDebugLogging";
    public static final String SETTINGS_JSON_VERBOSE_AI_DEBUG_LOGGING_KEY = "divisionai_verboseAiDebugLogging";

    /** Optional overrides used by headless unit tests (null in normal gameplay). */
    public static Boolean testDebugLoggingEnabledOverride = null;
    public static Boolean testVerboseAiDebugLoggingOverride = null;

    public static final String OVERRIDE_FLEET_BATTLE_SIZE_FIELD_ID = "divai_override_fleet_battle_size";
    public static final String MAX_BATTLE_SIZE_FIELD_ID = "divai_max_battle_size";
    public static final String MIN_BATTLE_SIZE_FIELD_ID = "divai_min_battle_size";
    public static final String MAX_SHIPS_IN_FLEET_FIELD_ID = "divai_max_ships_in_fleet";
    public static final String MAX_SHIPS_IN_AI_FLEET_FIELD_ID = "divai_max_ships_in_ai_fleet";
    public static final String MAX_OFFICERS_IN_AI_FLEET_FIELD_ID = "divai_max_officers_in_ai_fleet";
    public static final String BASE_NUM_OFFICERS_FIELD_ID = "divai_base_num_officers";
    private static final String FLEET_BATTLE_SEEDED_KEY = "divai_fleet_battle_seeded_v1";

    private static boolean baselineSettingsCaptured = false;
    private static int baselineBattleSize = 400;
    private static int baselineMaxBattleSize = 400;
    private static int baselineMinBattleSize = 200;
    private static int baselineMaxShipsInFleet = 30;
    private static int baselineMaxShipsInAIFleet = 30;
    private static int baselineMaxOfficersInAIFleet = 15;
    private static int baselineBaseNumOfficers = 8;
    private static boolean overridesCurrentlyApplied = false;
    private static int lastAppliedMaxBattleSize = -1;
    private static int lastAppliedMinBattleSize = -1;
    private static int lastAppliedMaxShipsInFleet = -1;
    private static int lastAppliedMaxShipsInAIFleet = -1;
    private static int lastAppliedMaxOfficersInAIFleet = -1;
    private static int lastAppliedBaseNumOfficers = -1;
    private static MethodHandle setBattleSizeApiHandle = null;
    private static MethodHandle setBattleSizeStaticHandle = null;
    private static MethodHandle setMaxShipsInFleetStaticHandle = null;
    private static boolean starfarerSettingsHandlesResolved = false;

    public enum BattleMusicControlMode {
        MOD_DYNAMIC("Division AI Dynamic Music"),
        VANILLA_DEFAULT("Vanilla Default");

        public final String displayName;

        BattleMusicControlMode(String displayName) {
            this.displayName = displayName;
        }

        public static BattleMusicControlMode fromSettingString(String raw) {
            if (raw == null) return MOD_DYNAMIC;
            String trimmed = raw.trim();
            if ("Vanilla".equalsIgnoreCase(trimmed)
                    || "Vanilla Default".equalsIgnoreCase(trimmed)
                    || "VANILLA_DEFAULT".equalsIgnoreCase(trimmed)
                    || "Disabled".equalsIgnoreCase(trimmed)
                    || "Off".equalsIgnoreCase(trimmed)
                    || "false".equalsIgnoreCase(trimmed)) {
                return VANILLA_DEFAULT;
            }
            return MOD_DYNAMIC;
        }
    }

    /** Optional override used by headless unit tests or runtime programmatic toggles (null in normal gameplay). */
    public static Boolean testBattleMusicEnabledOverride = null;

    public enum TsfcGraphicsQuality {
        HIGH("High"),
        LOW("Low");

        public final String displayName;

        TsfcGraphicsQuality(String displayName) {
            this.displayName = displayName;
        }

        public static TsfcGraphicsQuality fromSettingString(String raw) {
            if (raw == null) return HIGH;
            String trimmed = raw.trim();
            for (TsfcGraphicsQuality q : values()) {
                if (q.displayName.equalsIgnoreCase(trimmed) || q.name().equalsIgnoreCase(trimmed)) {
                    return q;
                }
            }
            return HIGH;
        }
    }

    /** Optional override used by headless unit tests (null in normal gameplay). */
    public static TsfcGraphicsQuality testTsfcQualityOverride = null;
    public static Boolean testTsfcMiniShipSpritesOverride = null;
    public static Float testTsfcWarroomOpacityOverride = null;
    public static Float testTsfcBattleOpacityOverride = null;

    public enum ChatterVerbosity {
        VERBOSE("Verbose"),
        STANDARD("Standard"),
        MINIMAL("Minimal"),
        OTC_ONLY("OTC Only"),
        OFF("Off");

        public final String displayName;

        ChatterVerbosity(String displayName) {
            this.displayName = displayName;
        }

        public static ChatterVerbosity fromSettingString(String raw) {
            if (raw == null) return MINIMAL;
            String trimmed = raw.trim();
            if ("Medium".equalsIgnoreCase(trimmed)) {
                return STANDARD;
            }
            for (ChatterVerbosity v : values()) {
                if (v.displayName.equalsIgnoreCase(trimmed) || v.name().equalsIgnoreCase(trimmed)) {
                    return v;
                }
            }
            return MINIMAL;
        }
    }

    /** Optional override used by headless unit tests (null in normal gameplay). */
    public static ChatterVerbosity testVerbosityOverride = null;

    private static Object trackedReinitButton = null;
    private static boolean lastObservedButtonValue = false;
    private static int reinitPromptState = 0; // 0 = idle, 1 = "Are you sure?", 2 = completed, 3 = queued for campaign load
    private static long promptStateTimestampMs = 0L;
    private static int lastReinitializedFleetCount = 0;
    private static boolean pendingReinitOnGameLoad = false;

    public static void consumePendingReinitOnGameLoad() {
        if (pendingReinitOnGameLoad) {
            pendingReinitOnGameLoad = false;
            SectorAPI sector = Global.getSector();
            if (sector != null) {
                FactionFleetDoctrines.reinitializeSectorWideDivisionLists(sector);
            }
        }
    }

    private static boolean isLunaLibEnabled() {
        try {
            return Global.getSettings() != null
                    && Global.getSettings().getModManager() != null
                    && Global.getSettings().getModManager().isModEnabled("lunalib");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean getBool(String key, boolean defaultVal) {
        Object live = liveSettingOverrides.get(key);
        if (live instanceof Boolean) return (Boolean) live;
        if (!isLunaLibEnabled()) return defaultVal;
        try {
            ensureSettingsCacheFresh();
            Object cached = cachedLunaValues.get(key);
            if (cached instanceof Boolean) return (Boolean) cached;
            Boolean val = LunaSettings.getBoolean(MOD_ID, key);
            if (val != null) {
                cachedLunaValues.put(key, val);
                return val;
            }
            cachedLunaValues.put(key, Boolean.valueOf(defaultVal));
            return defaultVal;
        } catch (Throwable t) {
            return defaultVal;
        }
    }

    private static int getInt(String key, int defaultVal) {
        Object live = liveSettingOverrides.get(key);
        if (live instanceof Number) return ((Number) live).intValue();
        if (!isLunaLibEnabled()) return defaultVal;
        try {
            ensureSettingsCacheFresh();
            Object cached = cachedLunaValues.get(key);
            if (cached instanceof Number) return ((Number) cached).intValue();
            Integer val = LunaSettings.getInt(MOD_ID, key);
            if (val != null) {
                cachedLunaValues.put(key, val);
                return val;
            }
            cachedLunaValues.put(key, Integer.valueOf(defaultVal));
            return defaultVal;
        } catch (Throwable t) {
            return defaultVal;
        }
    }

    private static String getString(String key, String defaultVal) {
        Object live = liveSettingOverrides.get(key);
        if (live instanceof String) return (String) live;
        if (!isLunaLibEnabled()) return defaultVal;
        try {
            ensureSettingsCacheFresh();
            Object cached = cachedLunaValues.get(key);
            if (cached instanceof String) return (String) cached;
            String val = LunaSettings.getString(MOD_ID, key);
            if (val != null) {
                cachedLunaValues.put(key, val);
                return val;
            }
            cachedLunaValues.put(key, defaultVal);
            return defaultVal;
        } catch (Throwable t) {
            return defaultVal;
        }
    }

    private static final Map<String, Object> liveSettingOverrides = new HashMap<String, Object>();
    private static final Map<String, Object> cachedLunaValues = new HashMap<String, Object>();
    private static long lastSettingsCacheRefreshMs = 0L;
    private static long lastLunaSyncPersistMs = 0L;

    private static void ensureSettingsCacheFresh() {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastSettingsCacheRefreshMs < 250L && !cachedLunaValues.isEmpty()) {
            return;
        }
        lastSettingsCacheRefreshMs = nowMs;
        cachedLunaValues.clear();
        // LunaLib's Mod Settings UI only exists on the Title Screen / Campaign map, never inside tactical combat
        try {
            if (Global.getCurrentState() != com.fs.starfarer.api.GameState.COMBAT) {
                syncAndPersistLunaSettings();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Works around two LunaLib Mod Settings quirks:
     * 1. LunaSettingsUISettingsPanel.createRadioCard loops over radioButton.getButtons() BEFORE
     *    subpanel.addUIElement(subpanelElement) triggers LunaUIRadioButton.positionChanged(), so
     *    clicking a Radio option never sets unsaved=true or highlights "Save All".
     * 2. Closing Mod Settings via ESC/Close without clicking "Save All" leaves widget changes in
     *    LunaSettingsUISettingsPanel.Companion.getAddedElements() / getChangedSettings() without
     *    committing them to LunaSettingsLoader.getSettings().
     * This method synchronizes live widget values into LunaSettingsLoader.getSettings() and
     * persists them immediately to division_ai.json.data.
     */
    public static void syncAndPersistLunaSettings() {
        if (!isLunaLibEnabled()) return;
        try {
            Map<String, ?> allSettings = LunaSettingsLoader.getSettings();
            Object modJson = (allSettings != null) ? allSettings.get(MOD_ID) : null;
            boolean anyChanged = false;

            List<ChangedSetting> changedList = LunaSettingsUISettingsPanel.Companion.getChangedSettings();
            if (changedList != null) {
                for (int i = 0; i < changedList.size(); i++) {
                    ChangedSetting cs = changedList.get(i);
                    if (cs != null && MOD_ID.equals(cs.getModID()) && !REINIT_FIELD_ID.equals(cs.getFieldID())) {
                        Object val = cs.getData();
                        if (val != null && applySettingIfChanged(modJson, cs.getFieldID(), val)) {
                            anyChanged = true;
                        }
                    }
                }
            }

            List<LunaUIBaseElement> elements = LunaSettingsUISettingsPanel.Companion.getAddedElements();
            if (elements != null) {
                for (int i = 0; i < elements.size(); i++) {
                    LunaUIBaseElement el = elements.get(i);
                    if (el == null) continue;
                    Object keyObj = el.getKey();
                    if (!(keyObj instanceof LunaSettingsData)) continue;
                    LunaSettingsData data = (LunaSettingsData) keyObj;
                    if (!MOD_ID.equals(data.getModID())) continue;
                    String fieldId = data.getFieldID();
                    if (fieldId == null || REINIT_FIELD_ID.equals(fieldId)) continue;

                    Object widgetVal = null;
                    if (el instanceof LunaUIRadioButton) {
                        widgetVal = ((LunaUIRadioButton) el).getValue();
                    } else if (el instanceof LunaUIButton) {
                        widgetVal = Boolean.valueOf(((LunaUIButton) el).getValue());
                    } else if (el instanceof LunaUITextFieldWithSlider) {
                        Number num = ((LunaUITextFieldWithSlider<?>) el).getValue();
                        if (num != null) {
                            widgetVal = Integer.valueOf(num.intValue());
                        }
                    }

                    if (widgetVal != null && applySettingIfChanged(modJson, fieldId, widgetVal)) {
                        anyChanged = true;
                        if (el instanceof LunaUIRadioButton) {
                            try {
                                LunaSettingsUISettingsPanel.Companion.setUnsaved(true);
                                LunaSettingsUISettingsPanel.Companion.getUnsavedCounter().put(fieldId, Integer.valueOf(1));
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }

            if (anyChanged && modJson != null) {
                try {
                    MethodHandle saveHandle = MethodHandles.publicLookup().findVirtual(
                            modJson.getClass(), "save", MethodType.methodType(void.class));
                    saveHandle.invoke(modJson);
                } catch (Throwable ignored) {}
            }
            if (anyChanged) {
                cachedLunaValues.clear();
                lastSettingsCacheRefreshMs = 0L;
                applyFleetAndBattleSizeOverrides();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean applySettingIfChanged(Object modJson, String fieldId, Object newVal) {
        if (fieldId == null || newVal == null) return false;
        Object prevLive = liveSettingOverrides.get(fieldId);
        boolean changed = (prevLive == null) ? true : !prevLive.equals(newVal);
        liveSettingOverrides.put(fieldId, newVal);
        cachedLunaValues.put(fieldId, newVal);

        if (modJson != null) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                Class<?> jsonCls = modJson.getClass();
                MethodHandle hasHandle = lookup.findVirtual(jsonCls, "has", MethodType.methodType(boolean.class, String.class));
                boolean hasKey = (boolean) hasHandle.invoke(modJson, fieldId);
                Object existing = null;
                if (hasKey) {
                    MethodHandle getHandle = lookup.findVirtual(jsonCls, "get", MethodType.methodType(Object.class, String.class));
                    existing = getHandle.invoke(modJson, fieldId);
                }
                boolean jsonDiffers = (existing == null) || !String.valueOf(existing).equals(String.valueOf(newVal));
                if (jsonDiffers) {
                    MethodHandle putHandle = lookup.findVirtual(
                            jsonCls, "put", MethodType.methodType(jsonCls.getSuperclass(), String.class, Object.class));
                    putHandle.invoke(modJson, fieldId, newVal);
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return changed;
    }

    public static ChatterVerbosity getChatterVerbosity() {
        if (testVerbosityOverride != null) {
            return testVerbosityOverride;
        }
        return ChatterVerbosity.fromSettingString(getString(CHATTER_VERBOSITY_FIELD_ID, ChatterVerbosity.MINIMAL.displayName));
    }

    /**
     * Returns true if debug/telemetry logging to starsector.log is enabled (default false).
     * Controlled via LunaSettings 'divai_enable_debug_logging' or settings.json 'divisionai_enableDebugLogging'.
     */
    public static boolean isDebugLoggingEnabled() {
        if (testDebugLoggingEnabledOverride != null) {
            return testDebugLoggingEnabledOverride;
        }
        boolean defaultVal = false;
        try {
            if (Global.getSettings() != null) {
                defaultVal = Global.getSettings().getBoolean(SETTINGS_JSON_ENABLE_DEBUG_LOGGING_KEY);
            }
        } catch (Throwable ignored) {
        }
        return getBool(ENABLE_DEBUG_LOGGING_FIELD_ID, defaultVal);
    }

    /**
     * Returns true if extended tactical AI debug trace logging to starsector.log is enabled (default false).
     * Requires isDebugLoggingEnabled() to also be true.
     */
    public static boolean isVerboseAiDebugLoggingEnabled() {
        if (!isDebugLoggingEnabled()) {
            return false;
        }
        if (testVerboseAiDebugLoggingOverride != null) {
            return testVerboseAiDebugLoggingOverride;
        }
        boolean defaultVal = false;
        try {
            if (Global.getSettings() != null) {
                defaultVal = Global.getSettings().getBoolean(SETTINGS_JSON_VERBOSE_AI_DEBUG_LOGGING_KEY);
            }
        } catch (Throwable ignored) {
        }
        return getBool(VERBOSE_AI_DEBUG_LOGGING_FIELD_ID, defaultVal);
    }

    public static boolean isCriticalDivisionIntent(DivisionIntentType intent) {
        if (intent == null) return false;
        switch (intent) {
            case TOT_MISSILE_STRIKE:
            case TOT_ALPHA_STRIKE:
            case LAUNCH_ALPHA_STRIKE:
            case LAUNCH_DECKLOAD_STRIKE:
            case REINFORCE_DIVISION:
            case HAUL_OUT_TO_VENT:
            case BREAK_OFF_AND_RETIRE:
            case FEIGNED_RETREAT:
                return true;
            default:
                return false;
        }
    }

    public static boolean isRoutineNoisyIntent(DivisionIntentType intent) {
        if (intent == null) return true;
        switch (intent) {
            case CLOSE_UP:
            case HOLD_IN_RESERVE:
            case FLY_ARMED_RECONNAISSANCE:
                return true;
            default:
                return false;
        }
    }

    public static boolean isOtcSignalsEnabled(int side) {
        if (side != 0 && testVerbosityOverride != null) return true;
        return (side == 0)
                ? getBool("divai_friendly_bg_chatter", true)
                : getBool("divai_enemy_bg_chatter", false);
    }

    public static boolean isDivisionSignalsEnabled(int side) {
        if (side != 0 && testVerbosityOverride != null) return true;
        return (side == 0)
                ? getBool("divai_friendly_div_chatter", true)
                : getBool("divai_enemy_div_chatter", false);
    }

    public static boolean isMultiDivisionSignalsEnabled(int side) {
        if (side != 0 && testVerbosityOverride != null) return true;
        return (side == 0)
                ? getBool("divai_friendly_multidiv_chatter", true)
                : getBool("divai_enemy_multidiv_chatter", false);
    }

    public static boolean shouldShowIntentOnHud(int side, DivisionIntentType intent) {
        if (intent == null) return false;
        ChatterVerbosity verbosity = getChatterVerbosity();
        if (verbosity == ChatterVerbosity.OFF || verbosity == ChatterVerbosity.OTC_ONLY) {
            return false;
        }
        if (isMultiDivisionIntent(intent)) {
            if (!isMultiDivisionSignalsEnabled(side)) return false;
        } else {
            if (!isDivisionSignalsEnabled(side)) return false;
        }
        if (verbosity == ChatterVerbosity.MINIMAL) {
            // In Minimal mode, only show critical friendly division plays on the HUD
            return side == 0 && isCriticalDivisionIntent(intent);
        }
        if (verbosity == ChatterVerbosity.STANDARD) {
            // In Standard mode, suppress routine cohesion/station-keeping spam and routine enemy single-division chatter
            if (isRoutineNoisyIntent(intent)) return false;
            if (side != 0 && !isMultiDivisionIntent(intent)) return false;
        }
        return true;
    }

    /**
     * Distinguishes cross-division / multi-division interactions (screening another division,
     * relieving another division, rotating behind another division, coordinated time-on-target
     * strikes, harassing torpedo attacks, and flanking maneuvers) from internal single-division
     * actions (cohesion, line advance, pressing local overmatch, objective capture, reserve hold).
     */
    public static boolean isMultiDivisionIntent(DivisionIntentType intent) {
        switch (intent) {
            case TAKE_SCREENING_STATION:
            case REINFORCE_DIVISION:
            case HAUL_OUT_TO_VENT:
            case BREAK_OFF_AND_RETIRE:
            case TOT_MISSILE_STRIKE:
            case TOT_ALPHA_STRIKE:
            case HARASSING_MISSILE_ATTACK:
            case WORK_FLANK:
            case FLY_COMBAT_SPACE_PATROL:
            case LAUNCH_DECKLOAD_STRIKE:
            case LAUNCH_ALPHA_STRIKE:
            case FORM_LINE_AND_FIX:
            case MARSHAL_FOR_STRIKE:
            case FEIGNED_RETREAT:
            case TRADE_SPACE_FOR_TIME:
                return true;
            case CLOSE_UP:
            case CLOSE_AND_ENGAGE:
            case PRESS_HOME:
            case HOLD_IN_RESERVE:
            case TAKE_AND_HOLD:
            case FLY_ARMED_RECONNAISSANCE:
            case FLY_FIGHTER_SWEEP:
            case FLY_CYCLIC_STRIKES:
            case GIVE_GENERAL_CHASE:
            default:
                return false;
        }
    }

    public static boolean isTacticalMapOverlayEnabled() {
        return getBool("divai_show_tactical_overlay", true);
    }

    public static float getDivisionCircleBaseAlpha() {
        int pct = getInt("divai_circle_alpha_pct", 18);
        return Math.max(0.05f, Math.min(0.50f, pct / 100f));
    }

    public static TsfcGraphicsQuality getTsfcGraphicsQuality() {
        if (testTsfcQualityOverride != null) {
            return testTsfcQualityOverride;
        }
        String raw = getString(TSFC_GRAPHICS_QUALITY_FIELD_ID, "High");
        return TsfcGraphicsQuality.fromSettingString(raw);
    }

    public static boolean isTsfcHighGraphics() {
        return getTsfcGraphicsQuality() == TsfcGraphicsQuality.HIGH;
    }

    public static boolean isTsfcMiniShipSpritesEnabled() {
        if (testTsfcMiniShipSpritesOverride != null) {
            return testTsfcMiniShipSpritesOverride.booleanValue();
        }
        return getBool(TSFC_MINI_SHIP_SPRITES_FIELD_ID, true);
    }

    public static int getTsfcWarroomOpacityPct() {
        int pct = getInt(TSFC_WARROOM_OPACITY_PCT_FIELD_ID, 100);
        return Math.max(5, Math.min(150, pct));
    }

    public static float getTsfcWarroomOpacityMult() {
        if (testTsfcWarroomOpacityOverride != null) {
            return Math.max(0f, Math.min(1.50f, testTsfcWarroomOpacityOverride.floatValue()));
        }
        return getTsfcWarroomOpacityPct() / 100f;
    }

    public static int getTsfcBattleOpacityPct() {
        int pct = getInt(TSFC_BATTLE_OPACITY_PCT_FIELD_ID, 24);
        return Math.max(0, Math.min(100, pct));
    }

    public static float getTsfcBattleOpacityMult() {
        if (testTsfcBattleOpacityOverride != null) {
            return Math.max(0f, Math.min(1.00f, testTsfcBattleOpacityOverride.floatValue()));
        }
        return getTsfcBattleOpacityPct() / 100f;
    }

    /**
     * Returns true when Division AI's dynamic battle music engine is enabled, or false when disabled
     * (in which case combat music control is yielded to vanilla Starsector default).
     */
    public static boolean isDynamicBattleMusicEnabled() {
        if (testBattleMusicEnabledOverride != null) {
            return testBattleMusicEnabledOverride.booleanValue();
        }
        boolean defaultFromSettingsJson = true;
        try {
            if (Global.getSettings() != null) {
                defaultFromSettingsJson = Global.getSettings().getBoolean(SETTINGS_JSON_ENABLE_BATTLE_MUSIC_KEY);
            }
        } catch (Throwable ignored) {
            defaultFromSettingsJson = true;
        }
        boolean enabledToggle = getBool(ENABLE_BATTLE_MUSIC_FIELD_ID, defaultFromSettingsJson);
        if (!enabledToggle) {
            return false;
        }
        String controlRaw = getString(BATTLE_MUSIC_CONTROL_FIELD_ID, BattleMusicControlMode.MOD_DYNAMIC.displayName);
        if (BattleMusicControlMode.fromSettingString(controlRaw) == BattleMusicControlMode.VANILLA_DEFAULT) {
            return false;
        }
        return true;
    }


    public static void setDynamicBattleMusicEnabled(Boolean enabled) {
        testBattleMusicEnabledOverride = enabled;
        if (enabled != null) {
            liveSettingOverrides.put(ENABLE_BATTLE_MUSIC_FIELD_ID, enabled);
            liveSettingOverrides.put(
                    BATTLE_MUSIC_CONTROL_FIELD_ID,
                    enabled.booleanValue()
                            ? BattleMusicControlMode.MOD_DYNAMIC.displayName
                            : BattleMusicControlMode.VANILLA_DEFAULT.displayName);
        } else {
            liveSettingOverrides.remove(ENABLE_BATTLE_MUSIC_FIELD_ID);
            liveSettingOverrides.remove(BATTLE_MUSIC_CONTROL_FIELD_ID);
        }
    }

    private static void resolveStarfarerSettingsHandles() {
        if (starfarerSettingsHandlesResolved) return;
        starfarerSettingsHandlesResolved = true;
        ClassLoader cl = DivisionAISettings.class.getClassLoader();
        try {
            setBattleSizeApiHandle = MethodHandles.publicLookup().findVirtual(
                    com.fs.starfarer.api.SettingsAPI.class,
                    "setBattleSize",
                    MethodType.methodType(void.class, int.class));
        } catch (Throwable ignored) {
        }
        try {
            Class<?> cls = Class.forName("com.fs.starfarer.settings.StarfarerSettings", false, cl);
            String setBsMethod = ObfuscatedBytecodeBridge.resolveStarfarerSetBattleSizeMethodName(cl);
            try {
                setBattleSizeStaticHandle = MethodHandles.publicLookup().findStatic(
                        cls, setBsMethod, MethodType.methodType(void.class, int.class));
            } catch (Throwable ignoredStatic) {
            }
            MethodHandles.Lookup priv = MethodHandles.privateLookupIn(cls, MethodHandles.lookup());
            String maxShipsField = ObfuscatedBytecodeBridge.resolveStarfarerMaxShipsFieldName(cl);
            setMaxShipsInFleetStaticHandle = priv.findStaticSetter(cls, maxShipsField, float.class);
        } catch (Throwable ignored) {
        }
    }

    public static boolean verifySettingsObfuscatedHandles(ClassLoader cl) {
        try {
            if (cl == null) cl = DivisionAISettings.class.getClassLoader();
            Class<?> cls = Class.forName("com.fs.starfarer.settings.StarfarerSettings", false, cl);
            String setBsMethod = ObfuscatedBytecodeBridge.resolveStarfarerSetBattleSizeMethodName(cl);
            MethodHandles.publicLookup().findStatic(cls, setBsMethod, MethodType.methodType(void.class, int.class));
            MethodHandles.Lookup priv = MethodHandles.privateLookupIn(cls, MethodHandles.lookup());
            String maxShipsField = ObfuscatedBytecodeBridge.resolveStarfarerMaxShipsFieldName(cl);
            priv.findStaticSetter(cls, maxShipsField, float.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Reads and snapshots the player's initial settings.json / SettingsAPI fleet & battle size values
     * BEFORE any Mod Settings overrides are applied, and seeds the LunaLib sliders with the player's
     * current values on first run.
     */
    public static void captureBaselineFleetAndBattleSettings() {
        if (baselineSettingsCaptured) return;
        if (Global.getSettings() == null) return;
        try {
            int bs = Global.getSettings().getBattleSize();
            if (bs > 0) baselineBattleSize = bs;
        } catch (Throwable ignored) {}
        try {
            int maxBs = Global.getSettings().getInt("maxBattleSize");
            if (maxBs > 0) baselineMaxBattleSize = maxBs;
        } catch (Throwable ignored) {}
        try {
            int minBs = Global.getSettings().getInt("minBattleSize");
            if (minBs > 0) baselineMinBattleSize = minBs;
        } catch (Throwable ignored) {}
        try {
            int maxShips = Global.getSettings().getMaxShipsInFleet();
            if (maxShips <= 0) maxShips = Global.getSettings().getInt("maxShipsInFleet");
            if (maxShips > 0) baselineMaxShipsInFleet = maxShips;
        } catch (Throwable ignored) {}
        try {
            int maxAiShips = Global.getSettings().getInt("maxShipsInAIFleet");
            if (maxAiShips > 0) baselineMaxShipsInAIFleet = maxAiShips;
        } catch (Throwable ignored) {}
        try {
            int maxAiOff = Global.getSettings().getInt("maxOfficersInAIFleet");
            if (maxAiOff > 0) baselineMaxOfficersInAIFleet = maxAiOff;
        } catch (Throwable ignored) {}
        try {
            int baseOff = Global.getSettings().getInt("baseNumOfficers");
            if (baseOff > 0) baselineBaseNumOfficers = baseOff;
        } catch (Throwable ignored) {}
        baselineSettingsCaptured = true;
        seedLunaSettingsFromPlayerBaselineIfNeeded();
    }

    private static void seedLunaSettingsFromPlayerBaselineIfNeeded() {
        if (!isLunaLibEnabled()) return;
        try {
            Map<String, ?> allSettings = LunaSettingsLoader.getSettings();
            Object modJson = (allSettings != null) ? allSettings.get(MOD_ID) : null;
            if (modJson == null) return;
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> jsonCls = modJson.getClass();
            MethodHandle hasHandle = lookup.findVirtual(jsonCls, "has", MethodType.methodType(boolean.class, String.class));
            boolean alreadySeeded = (boolean) hasHandle.invoke(modJson, FLEET_BATTLE_SEEDED_KEY);
            if (alreadySeeded) return;

            MethodHandle putHandle = lookup.findVirtual(
                    jsonCls, "put", MethodType.methodType(jsonCls.getSuperclass(), String.class, Object.class));
            int effectiveBattleSize = Math.max(baselineBattleSize, baselineMaxBattleSize);
            putHandle.invoke(modJson, MAX_BATTLE_SIZE_FIELD_ID, Integer.valueOf(effectiveBattleSize));
            putHandle.invoke(modJson, MIN_BATTLE_SIZE_FIELD_ID, Integer.valueOf(baselineMinBattleSize));
            putHandle.invoke(modJson, MAX_SHIPS_IN_FLEET_FIELD_ID, Integer.valueOf(baselineMaxShipsInFleet));
            putHandle.invoke(modJson, MAX_SHIPS_IN_AI_FLEET_FIELD_ID, Integer.valueOf(baselineMaxShipsInAIFleet));
            putHandle.invoke(modJson, MAX_OFFICERS_IN_AI_FLEET_FIELD_ID, Integer.valueOf(baselineMaxOfficersInAIFleet));
            putHandle.invoke(modJson, BASE_NUM_OFFICERS_FIELD_ID, Integer.valueOf(baselineBaseNumOfficers));
            putHandle.invoke(modJson, FLEET_BATTLE_SEEDED_KEY, Boolean.TRUE);
            MethodHandle saveHandle = lookup.findVirtual(jsonCls, "save", MethodType.methodType(void.class));
            saveHandle.invoke(modJson);
        } catch (Throwable ignored) {
        }
    }


    public static int getMaxBattleSizeOverride() {
        captureBaselineFleetAndBattleSettings();
        return Math.max(50, getInt(MAX_BATTLE_SIZE_FIELD_ID, Math.max(baselineBattleSize, baselineMaxBattleSize)));
    }

    public static int getMinBattleSizeOverride() {
        captureBaselineFleetAndBattleSettings();
        return Math.max(50, getInt(MIN_BATTLE_SIZE_FIELD_ID, baselineMinBattleSize));
    }

    public static int getMaxShipsInPlayerFleetOverride() {
        captureBaselineFleetAndBattleSettings();
        return Math.max(1, getInt(MAX_SHIPS_IN_FLEET_FIELD_ID, baselineMaxShipsInFleet));
    }

    public static int getMaxShipsInAIFleetOverride() {
        captureBaselineFleetAndBattleSettings();
        return Math.max(1, getInt(MAX_SHIPS_IN_AI_FLEET_FIELD_ID, baselineMaxShipsInAIFleet));
    }

    public static int getMaxOfficersInAIFleetOverride() {
        captureBaselineFleetAndBattleSettings();
        return Math.max(1, getInt(MAX_OFFICERS_IN_AI_FLEET_FIELD_ID, baselineMaxOfficersInAIFleet));
    }

    public static int getBaseNumOfficersOverride() {
        captureBaselineFleetAndBattleSettings();
        return Math.max(1, getInt(BASE_NUM_OFFICERS_FIELD_ID, baselineBaseNumOfficers));
    }

    public static int getBaselineMaxBattleSize() {
        captureBaselineFleetAndBattleSettings();
        return baselineMaxBattleSize;
    }

    public static int getBaselineMaxShipsInFleet() {
        captureBaselineFleetAndBattleSettings();
        return baselineMaxShipsInFleet;
    }

    /**
     * Applies (or restores) the player's Fleet & Battle Size overrides to Starsector's live SettingsAPI
     * and StarfarerSettings static fields. Safe to call at startup, on game load, and mid-game whenever
     * Mod Settings change.
     */
    public static void applyFleetAndBattleSizeOverrides() {
        if (Global.getSettings() == null) return;
        captureBaselineFleetAndBattleSettings();
        boolean enabled = getBool(OVERRIDE_FLEET_BATTLE_SIZE_FIELD_ID, false);
        if (!enabled) {
            if (overridesCurrentlyApplied) {
                overridesCurrentlyApplied = false;
                applyRawFleetAndBattleValues(
                        baselineBattleSize,
                        baselineMaxBattleSize,
                        baselineMinBattleSize,
                        baselineMaxShipsInFleet,
                        baselineMaxShipsInAIFleet,
                        baselineMaxOfficersInAIFleet,
                        baselineBaseNumOfficers);
                lastAppliedMaxBattleSize = -1;
                lastAppliedMinBattleSize = -1;
                lastAppliedMaxShipsInFleet = -1;
                lastAppliedMaxShipsInAIFleet = -1;
                lastAppliedMaxOfficersInAIFleet = -1;
                lastAppliedBaseNumOfficers = -1;
            }
            return;
        }

        int maxBs = getMaxBattleSizeOverride();
        int minBs = Math.min(maxBs, getMinBattleSizeOverride());
        int maxPlayerShips = getMaxShipsInPlayerFleetOverride();
        int maxAiShips = getMaxShipsInAIFleetOverride();
        int maxAiOfficers = getMaxOfficersInAIFleetOverride();
        int baseOfficers = getBaseNumOfficersOverride();

        if (overridesCurrentlyApplied
                && maxBs == lastAppliedMaxBattleSize
                && minBs == lastAppliedMinBattleSize
                && maxPlayerShips == lastAppliedMaxShipsInFleet
                && maxAiShips == lastAppliedMaxShipsInAIFleet
                && maxAiOfficers == lastAppliedMaxOfficersInAIFleet
                && baseOfficers == lastAppliedBaseNumOfficers) {
            return;
        }

        overridesCurrentlyApplied = true;
        lastAppliedMaxBattleSize = maxBs;
        lastAppliedMinBattleSize = minBs;
        lastAppliedMaxShipsInFleet = maxPlayerShips;
        lastAppliedMaxShipsInAIFleet = maxAiShips;
        lastAppliedMaxOfficersInAIFleet = maxAiOfficers;
        lastAppliedBaseNumOfficers = baseOfficers;

        applyRawFleetAndBattleValues(
                maxBs,
                maxBs,
                minBs,
                maxPlayerShips,
                maxAiShips,
                maxAiOfficers,
                baseOfficers);
    }

    public static void applyRawFleetAndBattleValues(
            int activeBattleSize,
            int maxBattleSize,
            int minBattleSize,
            int maxShipsInFleet,
            int maxShipsInAIFleet,
            int maxOfficersInAIFleet,
            int baseNumOfficers) {
        if (Global.getSettings() == null) return;
        resolveStarfarerSettingsHandles();
        try {
            Global.getSettings().setFloat("minBattleSize", Float.valueOf((float) minBattleSize));
            Global.getSettings().setFloat("defaultBattleSize", Float.valueOf((float) activeBattleSize));
            Global.getSettings().setFloat("maxBattleSize", Float.valueOf((float) maxBattleSize));
            Global.getSettings().setFloat("maxShipsInFleet", Float.valueOf((float) maxShipsInFleet));
            Global.getSettings().setFloat("maxShipsInAIFleet", Float.valueOf((float) maxShipsInAIFleet));
            Global.getSettings().setFloat("maxOfficersInAIFleet", Float.valueOf((float) maxOfficersInAIFleet));
            Global.getSettings().setFloat("baseNumOfficers", Float.valueOf((float) baseNumOfficers));
        } catch (Throwable ignored) {
        }
        try {
            if (setBattleSizeApiHandle != null) {
                setBattleSizeApiHandle.invoke(Global.getSettings(), activeBattleSize);
            } else if (setBattleSizeStaticHandle != null) {
                setBattleSizeStaticHandle.invokeExact(activeBattleSize);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (setMaxShipsInFleetStaticHandle != null) {
                setMaxShipsInFleetStaticHandle.invokeExact((float) maxShipsInFleet);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Drives the "Reinitialize Sector-Wide Division Lists" button inside LunaLib's Mod Settings
     * UI so clicking it once shows an "Are you sure? Click to Confirm" prompt, and clicking a
     * second time executes FactionFleetDoctrines.reinitializeSectorWideDivisionLists(sector).
     */
    public static void advanceLunaSettingsReinitButton() {
        if (!isLunaLibEnabled()) return;
        try {
            List<LunaUIBaseElement> elements = LunaSettingsUISettingsPanel.Companion.getAddedElements();
            if (elements == null || elements.isEmpty()) {
                trackedReinitButton = null;
                return;
            }

            LunaUIButton foundBtn = null;
            for (LunaUIBaseElement el : elements) {
                if (el instanceof LunaUIButton) {
                    Object keyObj = el.getKey();
                    if (keyObj instanceof LunaSettingsData) {
                        LunaSettingsData data = (LunaSettingsData) keyObj;
                        if (MOD_ID.equals(data.getModID()) && REINIT_FIELD_ID.equals(data.getFieldID())) {
                            foundBtn = (LunaUIButton) el;
                            break;
                        }
                    }
                }
            }

            if (foundBtn == null) {
                trackedReinitButton = null;
                return;
            }

            long nowSyncMs = System.currentTimeMillis();
            if (nowSyncMs - lastLunaSyncPersistMs >= 100L) {
                lastLunaSyncPersistMs = nowSyncMs;
                syncAndPersistLunaSettings();
            }

            foundBtn.setRegularButton(false);

            long nowMs = System.currentTimeMillis();
            if (foundBtn != trackedReinitButton) {
                trackedReinitButton = foundBtn;
                foundBtn.unselect();
                lastObservedButtonValue = foundBtn.getValue();
                reinitPromptState = 0;
                promptStateTimestampMs = nowMs;
            }

            boolean curVal = foundBtn.getValue();
            boolean clicked = (curVal != lastObservedButtonValue) || foundBtn.isSelected();
            if (clicked) {
                foundBtn.unselect();
                lastObservedButtonValue = curVal;
                try {
                    if (Global.getSoundPlayer() != null) {
                        Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f);
                    }
                } catch (Throwable ignored) {}

                if (reinitPromptState != 1) {
                    reinitPromptState = 1;
                    promptStateTimestampMs = nowMs;
                } else {
                    if (Global.getCurrentState() == com.fs.starfarer.api.GameState.CAMPAIGN
                            && Global.getSector() != null) {
                        lastReinitializedFleetCount = FactionFleetDoctrines.reinitializeSectorWideDivisionLists(Global.getSector());
                        reinitPromptState = 2;
                    } else {
                        pendingReinitOnGameLoad = true;
                        lastReinitializedFleetCount = 0;
                        reinitPromptState = 3;
                    }
                    promptStateTimestampMs = nowMs;
                }
            }

            if ((reinitPromptState == 1 || reinitPromptState == 2 || reinitPromptState == 3)
                    && (nowMs - promptStateTimestampMs) > 6000L) {
                reinitPromptState = 0;
            }

            LabelAPI lbl = foundBtn.getButtonText();
            if (lbl != null) {
                String labelStr;
                Color textColor;
                Color borderColor;
                if (reinitPromptState == 1) {
                    labelStr = "Are you sure? Click to Confirm";
                    textColor = Misc.getHighlightColor();
                    borderColor = Misc.getHighlightColor();
                } else if (reinitPromptState == 2) {
                    labelStr = "Reinitialized (" + lastReinitializedFleetCount + " fleets)!";
                    textColor = Misc.getPositiveHighlightColor();
                    borderColor = Misc.getPositiveHighlightColor();
                } else if (reinitPromptState == 3) {
                    labelStr = "Queued for Campaign Load!";
                    textColor = Misc.getPositiveHighlightColor();
                    borderColor = Misc.getPositiveHighlightColor();
                } else {
                    labelStr = "Reinitialize Lists";
                    textColor = Misc.getBasePlayerColor();
                    borderColor = Misc.getDarkPlayerColor();
                }

                lbl.setText(labelStr);
                lbl.setColor(textColor);
                foundBtn.setBorderColor(borderColor);
                float tw = lbl.computeTextWidth(labelStr);
                float th = lbl.computeTextHeight(labelStr);
                lbl.getPosition().inTL(
                        Math.max(4f, (foundBtn.getWidth() - tw) * 0.5f),
                        Math.max(2f, (foundBtn.getHeight() - th) * 0.5f));
            }
        } catch (Throwable ignored) {
        }
    }
}
