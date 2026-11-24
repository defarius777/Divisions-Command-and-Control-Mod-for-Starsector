package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;

import java.util.Map;

/**
 * Per-save state helper for the Fleet-screen soft intro tour.
 *
 * <p>Stored strictly inside the active save's {@link SectorAPI#getPersistentData()} map
 * ({@code divisionai_fleet_intro_done_v1}) using a plain JDK {@link Boolean} (matching
 * {@link PermanentDivisionManager}) so there is zero cross-save state in {@code saves/common/}
 * and 100% XStream save-removal safety.
 */
public final class DivisionAISharedData {

    public static final String PERSISTENT_KEY_FLEET_INTRO_DONE = "divisionai_fleet_intro_done_v1";
    public static final String MEM_KEY_FLEET_INTRO_DONE = "$divisionai_fleet_intro_done_v1";

    /** Optional deterministic override used by headless unit and UI simulator tests (null in live game). */
    public static Boolean testFleetIntroCompletedOverride = null;

    /** Queues a tour reset when "Replay Tour" is clicked from LunaSettings on the Title Screen before loading a save. */
    private static boolean pendingResetOnNextGameLoad = false;

    private DivisionAISharedData() {
    }

    /** Called from {@link DivisionAIModPlugin#onGameLoad(boolean)} to apply any Title-Screen reset request. */
    public static void onGameLoad() {
        if (pendingResetOnNextGameLoad) {
            pendingResetOnNextGameLoad = false;
            setSectorIntroFlag(false);
        }
    }

    /** Returns true if the active save has already completed or skipped the Fleet-tab soft tutorial. */
    public static boolean isFleetIntroCompleted() {
        if (testFleetIntroCompletedOverride != null) {
            return testFleetIntroCompletedOverride.booleanValue();
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector != null) {
                Map<String, Object> pd = sector.getPersistentData();
                if (pd != null && Boolean.TRUE.equals(pd.get(PERSISTENT_KEY_FLEET_INTRO_DONE))) {
                    return true;
                }
                if (sector.getMemoryWithoutUpdate() != null
                        && sector.getMemoryWithoutUpdate().getBoolean(MEM_KEY_FLEET_INTRO_DONE)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Marks the Fleet-tab soft tutorial as completed/dismissed in the current save. */
    public static void markFleetIntroCompleted() {
        if (testFleetIntroCompletedOverride != null) {
            testFleetIntroCompletedOverride = Boolean.TRUE;
            return;
        }
        setSectorIntroFlag(true);
    }

    /** Resets the Fleet-tab soft tutorial flag for the current save (or queues it for the next save load if at Title Screen). */
    public static void resetFleetIntro() {
        if (testFleetIntroCompletedOverride != null) {
            testFleetIntroCompletedOverride = Boolean.FALSE;
            return;
        }
        if (!setSectorIntroFlag(false)) {
            pendingResetOnNextGameLoad = true;
        }
    }

    private static boolean setSectorIntroFlag(boolean completed) {
        boolean wrote = false;
        try {
            SectorAPI sector = Global.getSector();
            if (sector != null) {
                Map<String, Object> pd = sector.getPersistentData();
                if (pd != null) {
                    if (completed) {
                        pd.put(PERSISTENT_KEY_FLEET_INTRO_DONE, Boolean.TRUE);
                    } else {
                        pd.remove(PERSISTENT_KEY_FLEET_INTRO_DONE);
                    }
                    wrote = true;
                }
                MemoryAPI mem = sector.getMemoryWithoutUpdate();
                if (mem != null) {
                    if (completed) {
                        mem.set(MEM_KEY_FLEET_INTRO_DONE, Boolean.TRUE);
                    } else {
                        mem.unset(MEM_KEY_FLEET_INTRO_DONE);
                    }
                    wrote = true;
                }
            }
        } catch (Throwable ignored) {
        }
        return wrote;
    }
}
