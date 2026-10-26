package divisionai;

import com.fs.starfarer.api.Global;
import divisionai.DivisionModel.DivisionIntentType;
import lunalib.lunaSettings.LunaSettings;

/**
 * Reads Mod Settings (via LunaLib's Mod Settings menu) to control on-screen HUD chatter
 * by side (Friendly vs Enemy) and level (OTC, Single-Division, Multi-Division),
 * as well as Tactical Map (Tab) division visualization.
 *
 * Note: starsector.log always records 100% of C2 telemetry regardless of HUD mute settings.
 */
public class DivisionAISettings {

    public static final String MOD_ID = "division_ai";

    private static boolean isLunaLibEnabled() {
        return Global.getSettings().getModManager().isModEnabled("lunalib");
    }

    private static boolean getBool(String key, boolean defaultVal) {
        if (!isLunaLibEnabled()) return defaultVal;
        try {
            Boolean val = LunaSettings.getBoolean(MOD_ID, key);
            return val != null ? val : defaultVal;
        } catch (Throwable t) {
            return defaultVal;
        }
    }

    private static int getInt(String key, int defaultVal) {
        if (!isLunaLibEnabled()) return defaultVal;
        try {
            Integer val = LunaSettings.getInt(MOD_ID, key);
            return val != null ? val : defaultVal;
        } catch (Throwable t) {
            return defaultVal;
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
        if (isMultiDivisionIntent(intent)) {
            return isMultiDivisionSignalsEnabled(side);
        }
        return isDivisionSignalsEnabled(side);
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
}
