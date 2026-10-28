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
            if (raw == null) return VERBOSE;
            String trimmed = raw.trim();
            for (ChatterVerbosity v : values()) {
                if (v.displayName.equalsIgnoreCase(trimmed) || v.name().equalsIgnoreCase(trimmed)) {
                    return v;
                }
            }
            return VERBOSE;
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
        if (!isLunaLibEnabled()) return defaultVal;
        try {
            syncAndPersistLunaSettings();
            Boolean val = LunaSettings.getBoolean(MOD_ID, key);
            if (val != null) return val;
            Object live = liveSettingOverrides.get(key);
            if (live instanceof Boolean) return (Boolean) live;
            return defaultVal;
        } catch (Throwable t) {
            Object live = liveSettingOverrides.get(key);
            if (live instanceof Boolean) return (Boolean) live;
            return defaultVal;
        }
    }

    private static int getInt(String key, int defaultVal) {
        if (!isLunaLibEnabled()) return defaultVal;
        try {
            syncAndPersistLunaSettings();
            Integer val = LunaSettings.getInt(MOD_ID, key);
            if (val != null) return val;
            Object live = liveSettingOverrides.get(key);
            if (live instanceof Number) return ((Number) live).intValue();
            return defaultVal;
        } catch (Throwable t) {
            Object live = liveSettingOverrides.get(key);
            if (live instanceof Number) return ((Number) live).intValue();
            return defaultVal;
        }
    }

    private static String getString(String key, String defaultVal) {
        if (!isLunaLibEnabled()) return defaultVal;
        try {
            syncAndPersistLunaSettings();
            String val = LunaSettings.getString(MOD_ID, key);
            if (val != null) return val;
            Object live = liveSettingOverrides.get(key);
            if (live instanceof String) return (String) live;
            return defaultVal;
        } catch (Throwable t) {
            Object live = liveSettingOverrides.get(key);
            if (live instanceof String) return (String) live;
            return defaultVal;
        }
    }

    private static final Map<String, Object> liveSettingOverrides = new HashMap<String, Object>();

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
        } catch (Throwable ignored) {
        }
    }

    private static boolean applySettingIfChanged(Object modJson, String fieldId, Object newVal) {
        if (fieldId == null || newVal == null) return false;
        Object prevLive = liveSettingOverrides.get(fieldId);
        boolean changed = (prevLive == null) ? true : !prevLive.equals(newVal);
        liveSettingOverrides.put(fieldId, newVal);

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
        return ChatterVerbosity.fromSettingString(getString(CHATTER_VERBOSITY_FIELD_ID, ChatterVerbosity.VERBOSE.displayName));
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
        return (side == 0)
                ? getBool("divai_friendly_bg_chatter", true)
                : getBool("divai_enemy_bg_chatter", true);
    }

    public static boolean isDivisionSignalsEnabled(int side) {
        return (side == 0)
                ? getBool("divai_friendly_div_chatter", true)
                : getBool("divai_enemy_div_chatter", true);
    }

    public static boolean isMultiDivisionSignalsEnabled(int side) {
        return (side == 0)
                ? getBool("divai_friendly_multidiv_chatter", true)
                : getBool("divai_enemy_multidiv_chatter", true);
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

    /**
     * Drives the "Reinitialize Sector-Wide Division Lists" button inside LunaLib's Mod Settings
     * UI so clicking it once shows an "Are you sure? Click to Confirm" prompt, and clicking a
     * second time executes FactionFleetDoctrines.reinitializeSectorWideDivisionLists(sector).
     */
    public static void advanceLunaSettingsReinitButton() {
        if (!isLunaLibEnabled()) return;
        syncAndPersistLunaSettings();
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
