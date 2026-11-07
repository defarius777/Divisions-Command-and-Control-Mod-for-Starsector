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
import divisionai.BattlefieldFirepowerEvaluator.ShipFirepowerRose;

/**
 * OpenGL Warroom & Command View Renderer for the T-S-F-C (Time, Space, Firepower, Convergence)
 * tactical overlay, right-side Warroom square-bordered icon buttons, legend, and Urgent Alpha Core
 * Comms modal.
 *
 * Extracted from {@link BattlefieldFirepowerEvaluator} to keep mathematical evaluation/telemetry
 * cleanly separated from OpenGL screen-space rendering.
 */
public class TsfcWarroomRenderer {

    static final int HIGH_CONTOUR_STEPS = 72; // High fidelity: 5-degree smooth contour interpolation
    static final int LOW_CONTOUR_STEPS = 48;  // Low fidelity: 7.5-degree 2x sector supersampling

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

    // Right-side Warroom square-bordered icon buttons
    public static final float BTN_WIDTH = 46f;
    public static final float BTN_HEIGHT = 34f;
    public static final float BTN_SQUARE_W = 36f;
    public static final float BTN_SQUARE_H = 34f;
    public static final float BTN_ICON_SIZE = 28f;
    public static final float BTN_VERTICAL_STRIDE = 43f;
    public static final float BTN_RIGHT_MARGIN = 20f;
    public static final float BTN_Y_FRACTION = 0.52f;
    public static final String FIREPOWER_ICON_PATH = "graphics/icons/warroom/icon_overlay_firepower.png";
    public static final String SPACE_ICON_PATH = "graphics/icons/warroom/icon_overlay_space.png";
    public static final String TIME_ICON_PATH = "graphics/icons/warroom/icon_overlay_time.png";
    public static final String CONVERGENCE_ICON_PATH = "graphics/icons/warroom/icon_overlay_convergence.png";

    // Urgent Comms Pop-Up Modal geometry (centered on Command View Window)
    public static final float COMMS_MODAL_W = 460f;
    public static final float COMMS_MODAL_H = 268f;
    public static final float COMMS_BTN_W = 116f;
    public static final float COMMS_BTN_H = 32f;
    public static final float COMMS_BTN_Y_OFFSET = 22f;

    static boolean overlayVisible = false;
    static boolean spaceOverlayVisible = false;
    static boolean timeOverlayVisible = false;
    static boolean convergenceOverlayVisible = false;

    static float lastMouseX = -1f;
    static float lastMouseY = -1f;
    static float lastToggleRealTime = -10f;
    static final Map<String, LabelAPI> uiLabelCache = new HashMap<>();

    public static void resetForNewBattle() {
        overlayVisible = false;
        spaceOverlayVisible = false;
        timeOverlayVisible = false;
        convergenceOverlayVisible = false;
        lastToggleRealTime = -10f;
        uiLabelCache.clear();
        uiLabelLastTextCache.clear();
    }

    public static boolean isOverlayVisible() {
        return overlayVisible;
    }

    public static void toggleOverlayVisible() {
        overlayVisible = !overlayVisible;
    }

    public static boolean isSpaceOverlayVisible() {
        return spaceOverlayVisible;
    }

    public static void toggleSpaceOverlayVisible() {
        spaceOverlayVisible = !spaceOverlayVisible;
    }

    public static boolean isTimeOverlayVisible() {
        return timeOverlayVisible;
    }

    public static void toggleTimeOverlayVisible() {
        timeOverlayVisible = !timeOverlayVisible;
    }

    public static boolean isConvergenceOverlayVisible() {
        return convergenceOverlayVisible;
    }

    public static void toggleConvergenceOverlayVisible() {
        convergenceOverlayVisible = !convergenceOverlayVisible;
    }

    public static float getOverlayButtonY(float screenH, int buttonIndex) {
        float centerMidY = screenH * BTN_Y_FRACTION;
        return Math.round(centerMidY + (1 - buttonIndex) * BTN_VERTICAL_STRIDE);
    }

    public static float getOverlayButtonX(float screenW) {
        return Math.round(screenW - BTN_WIDTH - BTN_RIGHT_MARGIN);
    }

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
        if (!BattlefieldFirepowerEvaluator.isCommandViewWindowActive(engine)) return;
        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        float btnX = getOverlayButtonX(screenW);
        int maxButtons = DivisionOtcCommandManager.hasAlphaCoreInPlayerFleet(engine) ? 4 : 3;

        for (InputEventAPI event : events) {
            if (event == null || event.isConsumed()) continue;
            if (event.isMouseEvent()) {
                lastMouseX = event.getX();
                lastMouseY = event.getY();
            }
            if (event.isLMBDownEvent()) {
                float mx = event.getX();
                float my = event.getY();

                if (DivisionOtcCommandManager.pendingCommsAlphaCoreShip != null) {
                    float modalX = Math.round((screenW - COMMS_MODAL_W) * 0.5f);
                    float modalY = Math.round((screenH - COMMS_MODAL_H) * 0.5f);
                    float yesX = modalX + 86f;
                    float noX = modalX + COMMS_MODAL_W - 86f - COMMS_BTN_W;
                    float choiceBtnY = modalY + COMMS_BTN_Y_OFFSET;

                    if (mx >= yesX - 2f && mx <= yesX + COMMS_BTN_W + 2f
                            && my >= choiceBtnY - 2f && my <= choiceBtnY + COMMS_BTN_H + 2f) {
                        event.consume();
                        DivisionOtcCommandManager.acceptPendingAlphaCoreCommand(engine);
                        try {
                            Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f);
                        } catch (Throwable ignored) {
                        }
                        return;
                    }
                    if (mx >= noX - 2f && mx <= noX + COMMS_BTN_W + 2f
                            && my >= choiceBtnY - 2f && my <= choiceBtnY + COMMS_BTN_H + 2f) {
                        event.consume();
                        DivisionOtcCommandManager.refusePendingAlphaCoreCommand(engine);
                        try {
                            Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f);
                        } catch (Throwable ignored) {
                        }
                        return;
                    }
                    if (mx >= modalX && mx <= modalX + COMMS_MODAL_W
                            && my >= modalY && my <= modalY + COMMS_MODAL_H) {
                        event.consume();
                        return;
                    }
                }

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
                                    DivisionOtcCommandManager.handleConvergenceButtonClick(engine);
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

    public static boolean isAnyTsfcOverlayVisible() {
        return timeOverlayVisible || spaceOverlayVisible || overlayVisible || convergenceOverlayVisible;
    }

    /**
     * Renders stabilized Polar Firepower Roses / Planes for all visible combat ships in screen space
     * (worldToScreen: sx = offsetX + wx * scale, sy = offsetY + wy * scale).
     * Evaluates on a 0.10s cadence and smoothly keyframe-interpolates between snapshots (anything > 2.0s old is thrown in the bin).
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

        float now = engine.getTotalElapsedTime(false);
        // Ensure roses exist and refresh on the 0.10s keyframe cadence if needed
        if (BattlefieldFirepowerEvaluator.rosesByShipId.isEmpty()
                || engine != BattlefieldFirepowerEvaluator.lastRoseUpdateEngine
                || (now - BattlefieldFirepowerEvaluator.lastRoseUpdateTime) >= BattlefieldFirepowerEvaluator.TSFC_EVAL_INTERVAL) {
            BattlefieldFirepowerEvaluator.updateBattlefieldRoses(engine, now);
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
        for (ShipFirepowerRose rose : BattlefieldFirepowerEvaluator.rosesByShipId.values()) {
            ShipAPI s = rose.ship;
            if (s == null || !s.isAlive() || s.isHulk() || s.isExpired()) continue;
            // Anything past 2.0 seconds is thrown into the bin
            float kfBlend = rose.computeKeyframeBlendFactor(now);
            if (kfBlend < 0f) continue;

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
            float roseFade = fade;
            if (isEnemy && playerFog != null) {
                if (!BattlefieldFirepowerEvaluator.isEnemyShipVisibleSticky(engine, s, playerFog)) {
                    continue;
                }
                roseFade *= BattlefieldFirepowerEvaluator.getEnemyShipStickyVisibilityAlpha(engine, s, playerFog);
            }

            renderSingleShipFirepowerRose(rose, s, isEnemy, isAlly, scale, offsetX, offsetY, roseFade, kfBlend);
        }

        // 2. Render L-Cordon / U-Cordon crossfire pocket indicators for divisions experiencing crossfire
        renderDivisionCordonIndicators(engine, playerGroup, false, scale, offsetX, offsetY, fade);
        renderDivisionCordonIndicators(engine, alliedAiGroup, false, scale, offsetX, offsetY, fade);
        renderDivisionCordonIndicators(engine, enemyGroup, true, scale, offsetX, offsetY, fade);

        GL11.glPopMatrix();
        GL11.glPopAttrib();

        // 3. Render compact L-CORDON / U-CORDON warning labels on divisions in crossfire (Warroom only)
        if (fade > 0.35f) {
            renderDivisionCordonLabels(engine, playerGroup, false, scale, offsetX, offsetY, fade);
        }
    }

    static boolean isCircleVisibleOnScreen(float cx, float cy, float radPx, float screenW, float screenH) {
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
        BattlefieldFirepowerEvaluator.setActiveBattleGroups(playerGroup, alliedAiGroup, enemyGroup);
        if (!timeOverlayVisible && !spaceOverlayVisible && !convergenceOverlayVisible) return;

        // Throttle cross-group Time/Space/Convergence telemetry recomputation to the 0.10s TSFC keyframe cadence
        float now = engine.getTotalElapsedTime(false);
        int divCountSig = ((playerGroup != null ? playerGroup.divisions.size() : 0) << 16)
                ^ ((alliedAiGroup != null ? alliedAiGroup.divisions.size() : 0) << 8)
                ^ (enemyGroup != null ? enemyGroup.divisions.size() : 0);
        boolean staleTime = engine != BattlefieldFirepowerEvaluator.lastTimeSpaceConvEngine
                || divCountSig != BattlefieldFirepowerEvaluator.lastTimeSpaceConvDivCountSig
                || Math.abs(now - BattlefieldFirepowerEvaluator.lastTimeSpaceConvUpdateTime) >= BattlefieldFirepowerEvaluator.TSFC_EVAL_INTERVAL;
        if (staleTime) {
            if (playerGroup != null) {
                for (DivisionState d : BattlefieldFirepowerEvaluator.getDivisionsAndStationBastionsForRendering(engine, playerGroup)) {
                    if (d.isCombatEffective() && d.freeTimeRadius <= 1f) {
                        BattlefieldFirepowerEvaluator.updateDivisionBaseTimeSpace(d);
                    }
                }
                BattlefieldFirepowerEvaluator.updateCrossGroupTimeSpaceConvergence(engine, playerGroup, enemyGroup);
            }
            if (alliedAiGroup != null) {
                for (DivisionState d : BattlefieldFirepowerEvaluator.getDivisionsAndStationBastionsForRendering(engine, alliedAiGroup)) {
                    if (d.isCombatEffective() && d.freeTimeRadius <= 1f) {
                        BattlefieldFirepowerEvaluator.updateDivisionBaseTimeSpace(d);
                    }
                }
                BattlefieldFirepowerEvaluator.updateCrossGroupTimeSpaceConvergence(engine, alliedAiGroup, enemyGroup);
            }
            if (enemyGroup != null) {
                for (DivisionState e : BattlefieldFirepowerEvaluator.getDivisionsAndStationBastionsForRendering(engine, enemyGroup)) {
                    if (e.isCombatEffective() && e.freeTimeRadius <= 1f) {
                        BattlefieldFirepowerEvaluator.updateDivisionBaseTimeSpace(e);
                    }
                }
                BattlefieldFirepowerEvaluator.updateCrossGroupTimeSpaceConvergence(engine, enemyGroup, playerGroup);
            }
            BattlefieldFirepowerEvaluator.lastTimeSpaceConvEngine = engine;
            BattlefieldFirepowerEvaluator.lastTimeSpaceConvUpdateTime = now;
            BattlefieldFirepowerEvaluator.lastTimeSpaceConvDivCountSig = divCountSig;
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

    public static boolean hasAnyUninitializedDivisionTimeSpace(CombatEngineAPI engine, BattleGroupState group) {
        if (group == null) return false;
        List<DivisionState> allDivs = BattlefieldFirepowerEvaluator.getDivisionsAndStationBastionsForRendering(engine, group);
        for (DivisionState d : allDivs) {
            if (d != null && d.isCombatEffective()
                    && (d.freeTimeRadius <= 1f || !d.directionalTimeInitialized || !d.directionalSpaceInitialized)) {
                return true;
            }
        }
        return false;
    }

    static void renderGroupTimeCircles(
            CombatEngineAPI engine,
            BattleGroupState group,
            boolean isEnemy,
            boolean isAlly,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        if (group == null) return;
        List<DivisionState> renderDivs = BattlefieldFirepowerEvaluator.getDivisionsAndStationBastionsForRendering(engine, group);
        if (renderDivs.isEmpty()) return;
        float screenW = Global.getSettings() != null ? Global.getSettings().getScreenWidth() : 1920f;
        float screenH = Global.getSettings() != null ? Global.getSettings().getScreenHeight() : 1080f;
        FogOfWarAPI playerFog = engine.getFogOfWar(0);
        float now = engine.getTotalElapsedTime(false);

        for (DivisionState div : renderDivs) {
            if (!div.isCombatEffective()) continue;
            if (isEnemy && !BattlefieldFirepowerEvaluator.isEnemyDivisionVisibleSticky(engine, div, playerFog)) continue;
            float kfBlend = BattlefieldFirepowerEvaluator.computeDivisionKeyframeBlendFactor(div, now);
            if (kfBlend < 0f) {
                if (div.tsfcKeyframesInitialized) continue; // > 2.0s old keyframe thrown into the bin
                kfBlend = 1.0f;
            }

            Vector2f cWorld = resolveLiveRenderDivisionCentroid(engine, div, isEnemy, playerFog);

            float interpEffTimeR = div.tsfcKeyframesInitialized
                    ? (div.prevEffectiveTimeRadius + (div.effectiveTimeRadius - div.prevEffectiveTimeRadius) * kfBlend)
                    : div.effectiveTimeRadius;
            float interpFreeTimeR = div.tsfcKeyframesInitialized
                    ? (div.prevFreeTimeRadius + (div.freeTimeRadius - div.prevFreeTimeRadius) * kfBlend)
                    : div.freeTimeRadius;
            float interpEng = div.tsfcKeyframesInitialized
                    ? (div.prevEngagementLevel + (div.engagementLevel - div.prevEngagementLevel) * kfBlend)
                    : div.engagementLevel;

            float cx = offsetX + cWorld.x * scale;
            float cy = offsetY + cWorld.y * scale;
            float effRadPx = Math.max(18f, interpEffTimeR * scale);
            float freeRadPx = Math.max(effRadPx, interpFreeTimeR * scale);
            float timeSlide = BattlefieldFirepowerEvaluator.computeDivisionTimeSlidingOpacityScale(div, interpFreeTimeR);
            float timeFade = fade * timeSlide;

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
                drawDirectionalTimeGradient(cx, cy, div, scale, kfBlend, r, g, b, 0.14f * timeFade, 48);

                // Crisp inner horizon contour at 50% directional Time horizon and outer directional Time contour
                drawDirectionalTimeOutline(cx, cy, div, scale, 0.50f, r, g, b, 0.14f * timeFade, 1.1f, 48, true);
                drawDirectionalTimeOutline(cx, cy, div, scale, 1.00f, r, g, b, 0.36f * timeFade, 1.45f, 48, false);

                // When Engagement Level pinches any sector of the Time circle, render the unencumbered freeTimeRadius as a faint dashed ring
                if (interpEng > 0.06f && freeRadPx > 20f) {
                    drawCircleOutline(cx, cy, freeRadPx, r, g * 0.75f, b * 0.75f, 0.20f * timeFade, 1.10f, 36, true);
                }
            }

            // Render airborne Strike Craft squadron Time horizon rings when deployed away from the carrier centroid
            for (ShipAPI s : div.getAliveMembers()) {
                List<com.fs.starfarer.api.combat.FighterWingAPI> wings = s.getAllWings();
                if (wings == null) continue;
                for (com.fs.starfarer.api.combat.FighterWingAPI wing : wings) {
                    ShipAPI wLead = BattlefieldFirepowerEvaluator.resolveAliveWingLeader(wing);
                    if (wLead == null || BattlefieldFirepowerEvaluator.distance(wLead.getLocation(), cWorld) <= 320f) continue;
                    if (isEnemy && playerFog != null && !BattlefieldFirepowerEvaluator.isEnemyShipVisibleSticky(engine, wLead, playerFog)) continue;
                    float wx = offsetX + wLead.getLocation().x * scale;
                    float wy = offsetY + wLead.getLocation().y * scale;
                    float wSpd = Math.max(180f, wLead.getMaxSpeed());
                    float wTimeRadPx = Math.max(12f, (wSpd * 3.8f) * scale);
                    if (!isCircleVisibleOnScreen(wx, wy, wTimeRadPx + 8f, screenW, screenH)) continue;
                    drawIncreasinglyTransparentCircle(wx, wy, wTimeRadPx, r, g, b, 0.10f * timeFade, 24);
                    drawCircleOutline(wx, wy, wTimeRadPx, r, g, b, 0.26f * timeFade, 1.15f, 24, true);
                }
            }
        }
    }

    static void populateDirectionalTimeScratchCoords(float cx, float cy, DivisionState div, float scale) {
        populateDirectionalTimeScratchCoords(cx, cy, div, scale, 1.0f);
    }

    static void populateDirectionalTimeScratchCoords(float cx, float cy, DivisionState div, float scale, float kfBlend) {
        boolean init = div.directionalTimeInitialized;
        boolean hasKf = div.tsfcKeyframesInitialized && kfBlend < 0.999f;
        float effR = hasKf
                ? (div.prevEffectiveTimeRadius + (div.effectiveTimeRadius - div.prevEffectiveTimeRadius) * kfBlend)
                : div.effectiveTimeRadius;
        float freeR = hasKf
                ? (div.prevFreeTimeRadius + (div.freeTimeRadius - div.prevFreeTimeRadius) * kfBlend)
                : div.freeTimeRadius;
        float fallbackWorldR = Math.max(100f, effR > 1f ? effR : freeR);
        for (int i = 0; i < 48; i++) {
            float worldR;
            if (!init) {
                worldR = fallbackWorldR;
            } else {
                int s0 = i >> 1;
                float r0 = hasKf
                        ? (div.prevDirectionalTimeRadii[s0] + (div.directionalTimeRadii[s0] - div.prevDirectionalTimeRadii[s0]) * kfBlend)
                        : div.directionalTimeRadii[s0];
                if ((i & 1) == 0) {
                    worldR = r0;
                } else {
                    int s1 = (s0 + 1) % DivisionState.TIME_DIRECTIONS;
                    float r1 = hasKf
                            ? (div.prevDirectionalTimeRadii[s1] + (div.directionalTimeRadii[s1] - div.prevDirectionalTimeRadii[s1]) * kfBlend)
                            : div.directionalTimeRadii[s1];
                    worldR = 0.5f * (r0 + r1);
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

    static void drawDirectionalTimeGradient(
            float cx,
            float cy,
            DivisionState div,
            float scale,
            float r,
            float g,
            float b,
            float centerAlpha,
            int segs) {
        drawDirectionalTimeGradient(cx, cy, div, scale, 1.0f, r, g, b, centerAlpha, segs);
    }

    static void drawDirectionalTimeGradient(
            float cx,
            float cy,
            DivisionState div,
            float scale,
            float kfBlend,
            float r,
            float g,
            float b,
            float centerAlpha,
            int segs) {
        if (div == null || scale <= 1e-5f || centerAlpha <= 0.002f) return;
        populateDirectionalTimeScratchCoords(cx, cy, div, scale, kfBlend);
        renderScratchDirectionalGradient(cx, cy, r, g, b, centerAlpha);
    }

    static void drawDirectionalTimeOutline(
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

    static void renderGroupSpaceCircles(
            CombatEngineAPI engine,
            BattleGroupState group,
            boolean isEnemy,
            boolean isAlly,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        if (group == null) return;
        List<DivisionState> renderDivs = BattlefieldFirepowerEvaluator.getDivisionsAndStationBastionsForRendering(engine, group);
        if (renderDivs.isEmpty()) return;
        float screenW = Global.getSettings() != null ? Global.getSettings().getScreenWidth() : 1920f;
        float screenH = Global.getSettings() != null ? Global.getSettings().getScreenHeight() : 1080f;
        FogOfWarAPI playerFog = engine.getFogOfWar(0);
        float now = engine.getTotalElapsedTime(false);

        for (DivisionState div : renderDivs) {
            if (!div.isCombatEffective()) continue;
            if (isEnemy && !BattlefieldFirepowerEvaluator.isEnemyDivisionVisibleSticky(engine, div, playerFog)) continue;
            float kfBlend = BattlefieldFirepowerEvaluator.computeDivisionKeyframeBlendFactor(div, now);
            if (kfBlend < 0f) {
                if (div.tsfcKeyframesInitialized) continue; // > 2.0s old keyframe thrown into the bin
                kfBlend = 1.0f;
            }

            Vector2f cWorld = resolveLiveRenderDivisionCentroid(engine, div, isEnemy, playerFog);

            float interpInR = div.tsfcKeyframesInitialized
                    ? (div.prevSpaceInnerRadius + (div.spaceInnerRadius - div.prevSpaceInnerRadius) * kfBlend)
                    : div.spaceInnerRadius;
            float interpDomR = div.tsfcKeyframesInitialized
                    ? (div.prevSpaceDominatedRadius + (div.spaceDominatedRadius - div.prevSpaceDominatedRadius) * kfBlend)
                    : div.spaceDominatedRadius;
            float interpPotR = div.tsfcKeyframesInitialized
                    ? (div.prevSpacePotentialRadius + (div.spacePotentialRadius - div.prevSpacePotentialRadius) * kfBlend)
                    : div.spacePotentialRadius;
            float interpDomWeight = div.tsfcKeyframesInitialized
                    ? (div.prevSpaceDominanceWeight + (div.spaceDominanceWeight - div.prevSpaceDominanceWeight) * kfBlend)
                    : div.spaceDominanceWeight;

            float cx = offsetX + cWorld.x * scale;
            float cy = offsetY + cWorld.y * scale;
            float inRadPx = Math.max(14f, interpInR * scale);
            float domRadPx = Math.max(inRadPx + 8f, interpDomR * scale);
            float potRadPx = Math.max(domRadPx + 8f, interpPotR * scale);
            float spaceSlide = BattlefieldFirepowerEvaluator.computeDivisionSpaceSlidingOpacityScale(div, interpDomWeight);
            float spaceFade = fade * spaceSlide;

            float r, g, b;
            if (isEnemy) {
                r = 0.98f; g = 0.26f; b = 0.30f;
            } else if (isAlly) {
                r = 0.62f; g = 0.92f; b = 0.34f;
            } else {
                r = 0.20f; g = 0.78f; b = 0.96f;
            }

            if (isCircleVisibleOnScreen(cx, cy, potRadPx + 16f, screenW, screenH)) {
                // Tier 3 (Outer): Potentially Dominated Space given Time to maneuver (directional, non-circular, map-edge pinched)
                drawDirectionalSpaceTierGradient(cx, cy, div, 2, scale, kfBlend, r, g, b, 0.068f * spaceFade);
                drawDirectionalSpaceTierOutline(cx, cy, div, 2, scale, kfBlend, 1.0f, r, g, b, 0.16f * spaceFade, 1.05f, true);

                // Tier 2 (Middle): Dominated Nearby Space (directional, non-circular, map-edge pinched)
                drawDirectionalSpaceTierGradient(cx, cy, div, 1, scale, kfBlend, r, g, b, 0.098f * spaceFade);
                drawDirectionalSpaceTierOutline(cx, cy, div, 1, scale, kfBlend, 1.0f, r, g, b, 0.28f * spaceFade, 1.30f, false);

                // Tier 1 (Inner Core): Directly Dominated Up Close Space (directional, non-circular, map-edge pinched)
                drawDirectionalSpaceTierGradient(cx, cy, div, 0, scale, kfBlend, r, g, b, 0.125f * spaceFade);
                drawDirectionalSpaceTierOutline(cx, cy, div, 0, scale, kfBlend, 1.0f, r, g, b, 0.40f * spaceFade, 1.50f, false);

                // Highlight Strongpoint (bastion) or Weak Point (seam) on the inner Space perimeter
                if (div.isStrongpoint) {
                    drawDirectionalSpaceTierOutline(cx, cy, div, 0, scale, kfBlend, 0.75f, 0.45f, 0.98f, 0.88f, 0.45f * spaceFade, 1.7f, false);
                } else if (div.isWeakPoint) {
                    drawDirectionalSpaceTierOutline(cx, cy, div, 0, scale, kfBlend, 0.82f, 1.00f, 0.75f, 0.22f, 0.48f * spaceFade, 1.7f, true);
                }
            }

            // Render airborne Strike Craft squadron permeable Space Zones of Control when deployed away from the carrier centroid.
            // Per Task F: Because warships can physically move through strike craft, strike craft do NOT render a solid
            // Tier-1 Inner Hull-Exclusion ring; they render only a dashed permeable outer Space contour.
            for (ShipAPI s : div.getAliveMembers()) {
                List<com.fs.starfarer.api.combat.FighterWingAPI> wings = s.getAllWings();
                if (wings == null) continue;
                for (com.fs.starfarer.api.combat.FighterWingAPI wing : wings) {
                    ShipAPI wLead = BattlefieldFirepowerEvaluator.resolveAliveWingLeader(wing);
                    if (wLead == null || BattlefieldFirepowerEvaluator.distance(wLead.getLocation(), cWorld) <= 320f) continue;
                    if (isEnemy && playerFog != null && !BattlefieldFirepowerEvaluator.isEnemyShipVisibleSticky(engine, wLead, playerFog)) continue;
                    float wx = offsetX + wLead.getLocation().x * scale;
                    float wy = offsetY + wLead.getLocation().y * scale;
                    float wDomPx = Math.max(12f, 480f * scale);
                    if (!isCircleVisibleOnScreen(wx, wy, wDomPx + 8f, screenW, screenH)) continue;
                    drawIncreasinglyTransparentCircle(wx, wy, wDomPx, r, g, b, 0.06f * spaceFade, 24);
                    drawCircleOutline(wx, wy, wDomPx, r, g, b, 0.22f * spaceFade, 1.05f, 24, true);
                }
            }
        }
    }

    static void renderGroupConvergenceLayer(
            CombatEngineAPI engine,
            BattleGroupState group,
            boolean isEnemy,
            boolean isAlly,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        if (group == null) return;
        List<DivisionState> renderDivs = BattlefieldFirepowerEvaluator.getDivisionsAndStationBastionsForRendering(engine, group);
        if (renderDivs.isEmpty()) return;
        float screenW = Global.getSettings() != null ? Global.getSettings().getScreenWidth() : 1920f;
        float screenH = Global.getSettings() != null ? Global.getSettings().getScreenHeight() : 1080f;
        FogOfWarAPI playerFog = engine.getFogOfWar(0);
        float now = engine.getTotalElapsedTime(false);

        for (DivisionState div : renderDivs) {
            if (!div.isCombatEffective()) continue;
            if (isEnemy && !BattlefieldFirepowerEvaluator.isEnemyDivisionVisibleSticky(engine, div, playerFog)) continue;
            float kfBlend = BattlefieldFirepowerEvaluator.computeDivisionKeyframeBlendFactor(div, now);
            if (kfBlend < 0f) {
                if (div.tsfcKeyframesInitialized) continue; // > 2.0s old keyframe thrown into the bin
                kfBlend = 1.0f;
            }

            Vector2f cWorld = resolveLiveRenderDivisionCentroid(engine, div, isEnemy, playerFog);

            float interpEffTimeR = div.tsfcKeyframesInitialized
                    ? (div.prevEffectiveTimeRadius + (div.effectiveTimeRadius - div.prevEffectiveTimeRadius) * kfBlend)
                    : div.effectiveTimeRadius;
            float interpFreeTimeR = div.tsfcKeyframesInitialized
                    ? (div.prevFreeTimeRadius + (div.freeTimeRadius - div.prevFreeTimeRadius) * kfBlend)
                    : div.freeTimeRadius;
            float interpDomR = div.tsfcKeyframesInitialized
                    ? (div.prevSpaceDominatedRadius + (div.spaceDominatedRadius - div.prevSpaceDominatedRadius) * kfBlend)
                    : div.spaceDominatedRadius;
            float interpConvScore = div.tsfcKeyframesInitialized
                    ? (div.prevConvergenceScore + (div.convergenceScore - div.prevConvergenceScore) * kfBlend)
                    : div.convergenceScore;

            float cx = offsetX + cWorld.x * scale;
            float cy = offsetY + cWorld.y * scale;
            // Convergence radius synthesizes Time, Space, and Firepower into a unified phase envelope
            float convWorldRad = 0.38f * interpEffTimeR
                    + 0.38f * interpDomR
                    + 0.24f * Math.max(500f, div.avgEngageRange);
            float radPx = Math.max(24f, convWorldRad * scale);
            float convSlide = BattlefieldFirepowerEvaluator.computeDivisionTimeSlidingOpacityScale(div, interpFreeTimeR);
            float convFade = fade * convSlide;

            float r, g, b;
            if (isEnemy) {
                r = 1.00f; g = 0.34f; b = 0.58f;
            } else if (isAlly) {
                r = 0.68f; g = 0.96f; b = 0.52f;
            } else {
                // Tri-Tachyon / Alpha Core electric cyan-azure
                r = 0.38f; g = 0.92f; b = 1.00f;
            }

            float activeBoost = (group.useConvergence || div.useConvergence) ? 1.15f : 0.82f;
            if (isCircleVisibleOnScreen(cx, cy, radPx + 16f, screenW, screenH)) {
                // Directional & map-edge-pinched Convergence envelope
                drawDirectionalConvergenceEnvelope(
                        engine, cx, cy, cWorld, div, scale, kfBlend, r, g, b,
                        0.11f * convFade * activeBoost,
                        0.38f * convFade * activeBoost);

                // Render the iconic Convergence battle glyph: an inscribed triangle inside an inscribed square inside the pinched envelope
                float minEdgeClear = Math.min(
                        BattlefieldFirepowerEvaluator.computeDirectionalBoundaryClearance(engine, cWorld, 1f, 0f, 110f),
                        Math.min(
                                BattlefieldFirepowerEvaluator.computeDirectionalBoundaryClearance(engine, cWorld, -1f, 0f, 110f),
                                Math.min(
                                        BattlefieldFirepowerEvaluator.computeDirectionalBoundaryClearance(engine, cWorld, 0f, 1f, 110f),
                                        BattlefieldFirepowerEvaluator.computeDirectionalBoundaryClearance(engine, cWorld, 0f, -1f, 110f))));
                float glyphWorldR = BattlefieldFirepowerEvaluator.pinchRadiusByBoundary(convWorldRad, minEdgeClear);
                float glyphRadPx = Math.max(18f, glyphWorldR * scale);
                float sqHalf = glyphRadPx * 0.70710678f; // inscribed square half-side = R / sqrt(2)
                GL11.glLineWidth(1.30f);
                GL11.glColor4f(r, g, b, 0.32f * convFade * activeBoost);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(cx - sqHalf, cy - sqHalf);
                GL11.glVertex2f(cx + sqHalf, cy - sqHalf);
                GL11.glVertex2f(cx + sqHalf, cy + sqHalf);
                GL11.glVertex2f(cx - sqHalf, cy + sqHalf);
                GL11.glEnd();

                // Inscribed equilateral triangle inside the square
                GL11.glLineWidth(1.40f);
                GL11.glColor4f(r, g, b, 0.44f * convFade * activeBoost);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(cx, cy + sqHalf * 0.94f);
                GL11.glVertex2f(cx - sqHalf * 0.88f, cy - sqHalf * 0.78f);
                GL11.glVertex2f(cx + sqHalf * 0.88f, cy - sqHalf * 0.78f);
                GL11.glEnd();
            }

            // Phase-locked Convergence vector to focal point when targeting an enemy division
            if (!isEnemy && interpConvScore > 1f
                    && (div.convergenceFocalPoint.x != 0f || div.convergenceFocalPoint.y != 0f)) {
                float focalWx = div.tsfcKeyframesInitialized
                        ? (div.prevConvergenceFocalPoint.x + (div.convergenceFocalPoint.x - div.prevConvergenceFocalPoint.x) * kfBlend)
                        : div.convergenceFocalPoint.x;
                float focalWy = div.tsfcKeyframesInitialized
                        ? (div.prevConvergenceFocalPoint.y + (div.convergenceFocalPoint.y - div.prevConvergenceFocalPoint.y) * kfBlend)
                        : div.convergenceFocalPoint.y;
                float fx = offsetX + focalWx * scale;
                float fy = offsetY + focalWy * scale;
                GL11.glLineWidth(1.45f);
                GL11.glColor4f(r, g, b, 0.38f * convFade * activeBoost);
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
                    ShipAPI wLead = BattlefieldFirepowerEvaluator.resolveAliveWingLeader(wing);
                    if (wLead == null || BattlefieldFirepowerEvaluator.distance(wLead.getLocation(), cWorld) <= 320f) continue;
                    if (isEnemy && playerFog != null && !BattlefieldFirepowerEvaluator.isEnemyShipVisibleSticky(engine, wLead, playerFog)) continue;
                    float wx = offsetX + wLead.getLocation().x * scale;
                    float wy = offsetY + wLead.getLocation().y * scale;
                    float wRadPx = Math.max(12f, 380f * scale);
                    if (!isCircleVisibleOnScreen(wx, wy, wRadPx + 8f, screenW, screenH)) continue;
                    float wSq = wRadPx * 0.70710678f;
                    drawCircleOutline(wx, wy, wRadPx, r, g, b, 0.32f * convFade * activeBoost, 1.15f, 28, false);
                    GL11.glLineWidth(1.05f);
                    GL11.glColor4f(r, g, b, 0.28f * convFade * activeBoost);
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

    static void populateDirectionalSpaceScratchCoords(float cx, float cy, DivisionState div, int tier, float scale) {
        populateDirectionalSpaceScratchCoords(cx, cy, div, tier, scale, 1.0f);
    }

    static void populateDirectionalSpaceScratchCoords(float cx, float cy, DivisionState div, int tier, float scale, float kfBlend) {
        boolean init = div.directionalSpaceInitialized;
        boolean hasKf = div.tsfcKeyframesInitialized && kfBlend < 0.999f;
        float fallbackWorldR;
        if (tier == 0) {
            float inR = hasKf ? (div.prevSpaceInnerRadius + (div.spaceInnerRadius - div.prevSpaceInnerRadius) * kfBlend) : div.spaceInnerRadius;
            fallbackWorldR = Math.max(100f, inR);
        } else if (tier == 1) {
            float domR = hasKf ? (div.prevSpaceDominatedRadius + (div.spaceDominatedRadius - div.prevSpaceDominatedRadius) * kfBlend) : div.spaceDominatedRadius;
            fallbackWorldR = Math.max(200f, domR);
        } else {
            float potR = hasKf ? (div.prevSpacePotentialRadius + (div.spacePotentialRadius - div.prevSpacePotentialRadius) * kfBlend) : div.spacePotentialRadius;
            fallbackWorldR = Math.max(300f, potR);
        }
        float[] src = (tier == 0)
                ? div.directionalSpaceInnerRadii
                : ((tier == 1) ? div.directionalSpaceDominatedRadii : div.directionalSpacePotentialRadii);
        float[] prevSrc = (tier == 0)
                ? div.prevDirectionalSpaceInnerRadii
                : ((tier == 1) ? div.prevDirectionalSpaceDominatedRadii : div.prevDirectionalSpacePotentialRadii);
        for (int i = 0; i < 48; i++) {
            float worldR;
            if (!init || src == null) {
                worldR = fallbackWorldR;
            } else {
                int s0 = i >> 1;
                float r0 = (hasKf && prevSrc != null)
                        ? (prevSrc[s0] + (src[s0] - prevSrc[s0]) * kfBlend)
                        : src[s0];
                if ((i & 1) == 0) {
                    worldR = r0;
                } else {
                    int s1 = (s0 + 1) % DivisionState.TIME_DIRECTIONS;
                    float r1 = (hasKf && prevSrc != null)
                            ? (prevSrc[s1] + (src[s1] - prevSrc[s1]) * kfBlend)
                            : src[s1];
                    worldR = 0.5f * (r0 + r1);
                }
            }
            float outerRadPx = Math.max(12f, worldR * scale);
            float midRadPx = outerRadPx * 0.55f;
            float cos = UNIT_COS_48[i];
            float sin = UNIT_SIN_48[i];
            SCRATCH_TIME_OUTER_X[i] = cx + cos * outerRadPx;
            SCRATCH_TIME_OUTER_Y[i] = cy + sin * outerRadPx;
            SCRATCH_TIME_MID_X[i] = cx + cos * midRadPx;
            SCRATCH_TIME_MID_Y[i] = cy + sin * midRadPx;
        }
    }

    static void drawDirectionalSpaceTierGradient(
            float cx,
            float cy,
            DivisionState div,
            int tier,
            float scale,
            float r,
            float g,
            float b,
            float centerAlpha) {
        drawDirectionalSpaceTierGradient(cx, cy, div, tier, scale, 1.0f, r, g, b, centerAlpha);
    }

    static void drawDirectionalSpaceTierGradient(
            float cx,
            float cy,
            DivisionState div,
            int tier,
            float scale,
            float kfBlend,
            float r,
            float g,
            float b,
            float centerAlpha) {
        if (div == null || scale <= 1e-5f || centerAlpha <= 0.002f) return;
        populateDirectionalSpaceScratchCoords(cx, cy, div, tier, scale, kfBlend);
        renderScratchDirectionalGradient(cx, cy, r, g, b, centerAlpha);
    }

    static void drawDirectionalSpaceTierOutline(
            float cx,
            float cy,
            DivisionState div,
            int tier,
            float scale,
            float radScale,
            float r,
            float g,
            float b,
            float alpha,
            float lineWidth,
            boolean dashed) {
        drawDirectionalSpaceTierOutline(cx, cy, div, tier, scale, 1.0f, radScale, r, g, b, alpha, lineWidth, dashed);
    }

    static void drawDirectionalSpaceTierOutline(
            float cx,
            float cy,
            DivisionState div,
            int tier,
            float scale,
            float kfBlend,
            float radScale,
            float r,
            float g,
            float b,
            float alpha,
            float lineWidth,
            boolean dashed) {
        if (div == null || scale <= 1e-5f || alpha <= 0.002f) return;
        populateDirectionalSpaceScratchCoords(cx, cy, div, tier, scale, kfBlend);
        drawDirectionalTimeOutline(cx, cy, div, scale, radScale, r, g, b, alpha, lineWidth, 48, dashed);
    }

    static void drawDirectionalConvergenceEnvelope(
            CombatEngineAPI engine,
            float cx,
            float cy,
            Vector2f cWorld,
            DivisionState div,
            float scale,
            float r,
            float g,
            float b,
            float centerAlpha,
            float outlineAlpha) {
        drawDirectionalConvergenceEnvelope(engine, cx, cy, cWorld, div, scale, 1.0f, r, g, b, centerAlpha, outlineAlpha);
    }

    static void drawDirectionalConvergenceEnvelope(
            CombatEngineAPI engine,
            float cx,
            float cy,
            Vector2f cWorld,
            DivisionState div,
            float scale,
            float kfBlend,
            float r,
            float g,
            float b,
            float centerAlpha,
            float outlineAlpha) {
        if (div == null || scale <= 1e-5f) return;
        boolean hasKf = div.tsfcKeyframesInitialized && kfBlend < 0.999f;
        float weaponPart = 0.24f * Math.max(500f, div.avgEngageRange);
        for (int i = 0; i < 48; i++) {
            int s0 = i >> 1;
            int s1 = (s0 + 1) % DivisionState.TIME_DIRECTIONS;
            float tR0 = hasKf
                    ? (div.prevDirectionalTimeRadii[s0] + (div.directionalTimeRadii[s0] - div.prevDirectionalTimeRadii[s0]) * kfBlend)
                    : div.directionalTimeRadii[s0];
            float sR0 = hasKf
                    ? (div.prevDirectionalSpaceDominatedRadii[s0] + (div.directionalSpaceDominatedRadii[s0] - div.prevDirectionalSpaceDominatedRadii[s0]) * kfBlend)
                    : div.directionalSpaceDominatedRadii[s0];
            float tR;
            float sR;
            if ((i & 1) == 0) {
                tR = tR0;
                sR = sR0;
            } else {
                float tR1 = hasKf
                        ? (div.prevDirectionalTimeRadii[s1] + (div.directionalTimeRadii[s1] - div.prevDirectionalTimeRadii[s1]) * kfBlend)
                        : div.directionalTimeRadii[s1];
                float sR1 = hasKf
                        ? (div.prevDirectionalSpaceDominatedRadii[s1] + (div.directionalSpaceDominatedRadii[s1] - div.prevDirectionalSpaceDominatedRadii[s1]) * kfBlend)
                        : div.directionalSpaceDominatedRadii[s1];
                tR = 0.5f * (tR0 + tR1);
                sR = 0.5f * (sR0 + sR1);
            }
            if (!div.directionalTimeInitialized) tR = Math.max(200f, div.effectiveTimeRadius);
            if (!div.directionalSpaceInitialized) sR = Math.max(300f, div.spaceDominatedRadius);
            float worldR = 0.38f * tR + 0.38f * sR + weaponPart;
            float cos = UNIT_COS_48[i];
            float sin = UNIT_SIN_48[i];
            if (cWorld != null) {
                float edgeDist = BattlefieldFirepowerEvaluator.computeDirectionalBoundaryClearance(engine, cWorld, cos, sin, 110f);
                worldR = BattlefieldFirepowerEvaluator.pinchRadiusByBoundary(worldR, edgeDist);
            }
            float outerRadPx = Math.max(16f, worldR * scale);
            float midRadPx = outerRadPx * 0.55f;
            SCRATCH_TIME_OUTER_X[i] = cx + cos * outerRadPx;
            SCRATCH_TIME_OUTER_Y[i] = cy + sin * outerRadPx;
            SCRATCH_TIME_MID_X[i] = cx + cos * midRadPx;
            SCRATCH_TIME_MID_Y[i] = cy + sin * midRadPx;
        }
        renderScratchDirectionalGradient(cx, cy, r, g, b, centerAlpha);

        drawDirectionalTimeOutline(cx, cy, div, scale, 1.0f, r, g, b, outlineAlpha, 1.5f, 48, false);
    }

    static void drawIncreasinglyTransparentCircle(
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

    static void drawCircleOutline(
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

    private static final Vector2f SCRATCH_LIVE_DIV_CENTROID = new Vector2f();

    /**
     * Resolves a frame-accurate 60+ FPS world-space centroid for {@code div} directly from its live member ship
     * locations so Time, Space, Convergence, and Cordon overlays glide smoothly with moving ships every frame
     * rather than stepping only on telemetry ticks.
     */
    static Vector2f resolveLiveRenderDivisionCentroid(
            CombatEngineAPI engine,
            DivisionState div,
            boolean isEnemy,
            FogOfWarAPI playerFog) {
        if (div == null) return SCRATCH_LIVE_DIV_CENTROID;
        if (isEnemy) {
            return BattlefieldFirepowerEvaluator.getSmoothedVisibleCentroidForEnemyDivision(engine, div, playerFog);
        }
        List<ShipAPI> alive = div.getAliveMembers();
        if (alive == null || alive.isEmpty()) {
            return div.centroid;
        }
        float cx = 0f;
        float cy = 0f;
        float wSum = 0f;
        for (int i = 0, n = alive.size(); i < n; i++) {
            ShipAPI s = alive.get(i);
            if (s == null || s.getLocation() == null) continue;
            if (div.detachedStragglerIds.contains(s.getId()) && div.coreMemberCount >= 1) continue;
            float w = Math.max(1f, ShipCapabilityProfile.resolveShipFpStrength(s)) * (s == div.flagship ? 1.35f : 1.0f);
            cx += s.getLocation().x * w;
            cy += s.getLocation().y * w;
            wSum += w;
        }
        if (wSum > 0f) {
            SCRATCH_LIVE_DIV_CENTROID.set(cx / wSum, cy / wSum);
            return SCRATCH_LIVE_DIV_CENTROID;
        }
        return div.centroid;
    }

    static void renderSingleShipFirepowerRose(
            ShipFirepowerRose rose,
            ShipAPI ship,
            boolean isEnemy,
            boolean isAlly,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {
        renderSingleShipFirepowerRose(rose, ship, isEnemy, isAlly, scale, offsetX, offsetY, fade, 1.0f);
    }

    static void renderSingleShipFirepowerRose(
            ShipFirepowerRose rose,
            ShipAPI ship,
            boolean isEnemy,
            boolean isAlly,
            float scale,
            float offsetX,
            float offsetY,
            float fade,
            float kfBlend) {

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

        boolean hasKf = kfBlend < 0.999f;
        float interpReadiness = hasKf
                ? (rose.prevReadinessMult + (rose.liveReadinessMult - rose.prevReadinessMult) * kfBlend)
                : rose.liveReadinessMult;
        float interpPeakSust = hasKf
                ? (rose.prevPeakSustained + (rose.peakSectorSustained - rose.prevPeakSustained) * kfBlend)
                : rose.peakSectorSustained;
        float interpPeakShort = hasKf
                ? (rose.prevPeakShortDot + (rose.peakSectorShortDot - rose.prevPeakShortDot) * kfBlend)
                : rose.peakSectorShortDot;
        float interpPeakAlpha = hasKf
                ? (rose.prevPeakAlpha + (rose.peakSectorAlpha - rose.prevPeakAlpha) * kfBlend)
                : rose.peakSectorAlpha;

        float dmgWeight = BattlefieldFirepowerEvaluator.clampVal(rose.potentialDamageWeight, 0.38f, 1.32f);
        // Sliding opacity based on the highest seen firepower values in the battle + active fleet density attenuation
        float slidingOpacityScale = BattlefieldFirepowerEvaluator.computeShipSlidingOpacityScale(
                rose, interpPeakSust, interpPeakShort, interpPeakAlpha);
        float readinessFade = Math.max(0.30f, interpReadiness)
                * fade
                * slidingOpacityScale;
        float contourFade = Math.max(0.35f, interpReadiness)
                * fade
                * (0.55f + 0.45f * slidingOpacityScale);

        // Normalize sector intensity against both the ship's own peak and the highest values seen in the battle
        float battleMaxSust = BattlefieldFirepowerEvaluator.getBattleMaxShipSustainedPeak();
        float battleMaxShort = BattlefieldFirepowerEvaluator.getBattleMaxShipShortDotPeak();
        float battleMaxAlpha = BattlefieldFirepowerEvaluator.getBattleMaxShipAlphaPeak();
        float normSust = Math.max(110f, interpPeakSust * 0.50f + battleMaxSust * 0.30f + 280f * 0.20f);
        float normShort = Math.max(140f, interpPeakShort * 0.50f + battleMaxShort * 0.30f + 380f * 0.20f);
        float normAlpha = Math.max(220f, interpPeakAlpha * 0.50f + battleMaxAlpha * 0.30f + 640f * 0.20f);

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

        float interpFacingDeg = (ship != null && ship.getLocation() != null)
                ? ship.getFacing()
                : rose.getInterpolatedFacingDeg(kfBlend);
        double facingRad = Math.toRadians(interpFacingDeg);
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
                if (hasKf) {
                    genReachPx = ShipFirepowerRose.sampleInterpolatedArrayAtRelAngle(rose.prevGeneralRange, rose.generalRange, kfBlend, relDeg) * scale;
                    sustReachPx = ShipFirepowerRose.sampleInterpolatedArrayAtRelAngle(rose.prevSustainedRange, rose.sustainedRange, kfBlend, relDeg) * scale;
                    shortReachPx = ShipFirepowerRose.sampleInterpolatedArrayAtRelAngle(rose.prevShortRange, rose.shortRange, kfBlend, relDeg) * scale;
                    alphaReachPx = ShipFirepowerRose.sampleInterpolatedArrayAtRelAngle(rose.prevAlphaRange, rose.alphaRange, kfBlend, relDeg) * scale;
                    sustVal = ShipFirepowerRose.sampleInterpolatedArrayAtRelAngle(rose.prevSustainedDot, rose.sustainedDot, kfBlend, relDeg);
                    shortVal = ShipFirepowerRose.sampleInterpolatedArrayAtRelAngle(rose.prevShortDot, rose.shortDot, kfBlend, relDeg);
                    alphaVal = ShipFirepowerRose.sampleInterpolatedArrayAtRelAngle(rose.prevAlpha, rose.alpha, kfBlend, relDeg);
                    pdReachPx = ShipFirepowerRose.sampleInterpolatedArrayAtRelAngle(rose.prevPdRange, rose.pdRange, kfBlend, relDeg) * scale;
                    pdVal = ShipFirepowerRose.sampleInterpolatedArrayAtRelAngle(rose.prevPdScore, rose.pdScore, kfBlend, relDeg);
                } else {
                    genReachPx = ShipFirepowerRose.sampleArrayAtRelAngle(rose.generalRange, relDeg) * scale;
                    sustReachPx = ShipFirepowerRose.sampleArrayAtRelAngle(rose.sustainedRange, relDeg) * scale;
                    shortReachPx = ShipFirepowerRose.sampleArrayAtRelAngle(rose.shortRange, relDeg) * scale;
                    alphaReachPx = ShipFirepowerRose.sampleArrayAtRelAngle(rose.alphaRange, relDeg) * scale;
                    sustVal = ShipFirepowerRose.sampleArrayAtRelAngle(rose.sustainedDot, relDeg);
                    shortVal = ShipFirepowerRose.sampleArrayAtRelAngle(rose.shortDot, relDeg);
                    alphaVal = ShipFirepowerRose.sampleArrayAtRelAngle(rose.alpha, relDeg);
                    pdReachPx = ShipFirepowerRose.sampleArrayAtRelAngle(rose.pdRange, relDeg) * scale;
                    pdVal = ShipFirepowerRose.sampleArrayAtRelAngle(rose.pdScore, relDeg);
                }
            } else {
                int s0 = i >> 1;
                float g0 = hasKf ? (rose.prevGeneralRange[s0] + (rose.generalRange[s0] - rose.prevGeneralRange[s0]) * kfBlend) : rose.generalRange[s0];
                float suR0 = hasKf ? (rose.prevSustainedRange[s0] + (rose.sustainedRange[s0] - rose.prevSustainedRange[s0]) * kfBlend) : rose.sustainedRange[s0];
                float shR0 = hasKf ? (rose.prevShortRange[s0] + (rose.shortRange[s0] - rose.prevShortRange[s0]) * kfBlend) : rose.shortRange[s0];
                float alR0 = hasKf ? (rose.prevAlphaRange[s0] + (rose.alphaRange[s0] - rose.prevAlphaRange[s0]) * kfBlend) : rose.alphaRange[s0];
                float suV0 = hasKf ? (rose.prevSustainedDot[s0] + (rose.sustainedDot[s0] - rose.prevSustainedDot[s0]) * kfBlend) : rose.sustainedDot[s0];
                float shV0 = hasKf ? (rose.prevShortDot[s0] + (rose.shortDot[s0] - rose.prevShortDot[s0]) * kfBlend) : rose.shortDot[s0];
                float alV0 = hasKf ? (rose.prevAlpha[s0] + (rose.alpha[s0] - rose.prevAlpha[s0]) * kfBlend) : rose.alpha[s0];
                float pdR0 = hasKf ? (rose.prevPdRange[s0] + (rose.pdRange[s0] - rose.prevPdRange[s0]) * kfBlend) : rose.pdRange[s0];
                float pdV0 = hasKf ? (rose.prevPdScore[s0] + (rose.pdScore[s0] - rose.prevPdScore[s0]) * kfBlend) : rose.pdScore[s0];
                if ((i & 1) == 0) {
                    genReachPx = g0 * scale;
                    sustReachPx = suR0 * scale;
                    shortReachPx = shR0 * scale;
                    alphaReachPx = alR0 * scale;
                    sustVal = suV0;
                    shortVal = shV0;
                    alphaVal = alV0;
                    pdReachPx = pdR0 * scale;
                    pdVal = pdV0;
                } else {
                    int s1 = (s0 + 1) % BattlefieldFirepowerEvaluator.NUM_SECTORS;
                    float g1 = hasKf ? (rose.prevGeneralRange[s1] + (rose.generalRange[s1] - rose.prevGeneralRange[s1]) * kfBlend) : rose.generalRange[s1];
                    float suR1 = hasKf ? (rose.prevSustainedRange[s1] + (rose.sustainedRange[s1] - rose.prevSustainedRange[s1]) * kfBlend) : rose.sustainedRange[s1];
                    float shR1 = hasKf ? (rose.prevShortRange[s1] + (rose.shortRange[s1] - rose.prevShortRange[s1]) * kfBlend) : rose.shortRange[s1];
                    float alR1 = hasKf ? (rose.prevAlphaRange[s1] + (rose.alphaRange[s1] - rose.prevAlphaRange[s1]) * kfBlend) : rose.alphaRange[s1];
                    float suV1 = hasKf ? (rose.prevSustainedDot[s1] + (rose.sustainedDot[s1] - rose.prevSustainedDot[s1]) * kfBlend) : rose.sustainedDot[s1];
                    float shV1 = hasKf ? (rose.prevShortDot[s1] + (rose.shortDot[s1] - rose.prevShortDot[s1]) * kfBlend) : rose.shortDot[s1];
                    float alV1 = hasKf ? (rose.prevAlpha[s1] + (rose.alpha[s1] - rose.prevAlpha[s1]) * kfBlend) : rose.alpha[s1];
                    float pdR1 = hasKf ? (rose.prevPdRange[s1] + (rose.pdRange[s1] - rose.prevPdRange[s1]) * kfBlend) : rose.pdRange[s1];
                    float pdV1 = hasKf ? (rose.prevPdScore[s1] + (rose.pdScore[s1] - rose.prevPdScore[s1]) * kfBlend) : rose.pdScore[s1];
                    genReachPx = 0.5f * (g0 + g1) * scale;
                    sustReachPx = 0.5f * (suR0 + suR1) * scale;
                    shortReachPx = 0.5f * (shR0 + shR1) * scale;
                    alphaReachPx = 0.5f * (alR0 + alR1) * scale;
                    sustVal = 0.5f * (suV0 + suV1);
                    shortVal = 0.5f * (shV0 + shV1);
                    alphaVal = 0.5f * (alV0 + alV1);
                    pdReachPx = 0.5f * (pdR0 + pdR1) * scale;
                    pdVal = 0.5f * (pdV0 + pdV1);
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

            // Map-Edge Pinching for Firepower Roses (Item D):
            // Pinch rSust, rShort, rAlpha, and pdReachPx against the combat map boundary along ray (cos, sin)
            float edgeClearWorld = BattlefieldFirepowerEvaluator.computeDirectionalBoundaryClearance(BattlefieldFirepowerEvaluator.lastTimeSpaceConvEngine, ship.getLocation(), cos, sin, 90f);
            float edgeClearPx = Math.max(baseShipRadPx, edgeClearWorld * scale);
            if (rSust > edgeClearPx * 0.72f) {
                rSust = Math.max(baseShipRadPx * 0.45f, BattlefieldFirepowerEvaluator.pinchRadiusByBoundary(rSust, edgeClearPx));
            }
            if (rShort > edgeClearPx * 0.72f) {
                rShort = Math.max(baseShipRadPx * 0.35f, Math.min(rSust, BattlefieldFirepowerEvaluator.pinchRadiusByBoundary(rShort, edgeClearPx)));
            }
            if (rAlpha > edgeClearPx * 0.72f) {
                rAlpha = Math.max(baseShipRadPx * 0.25f, Math.min(Math.max(rSust, edgeClearPx), BattlefieldFirepowerEvaluator.pinchRadiusByBoundary(rAlpha, edgeClearPx)));
            }
            if (pdReachPx > edgeClearPx * 0.72f) {
                pdReachPx = BattlefieldFirepowerEvaluator.pinchRadiusByBoundary(pdReachPx, edgeClearPx);
            }

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

        // Plane 1: Sustained DoT Outer Polar Plane (sliding battle-high scaling)
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(sustR, sustG, sustB, 0.17f * readinessFade);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(sustR, sustG, sustB, 0.085f * readinessFade);
        for (int i = 0; i <= contourSteps; i++) {
            int idx = i % contourSteps;
            GL11.glVertex2f(sustX[idx], sustY[idx]);
        }
        GL11.glEnd();

        // Outer Sustained DoT contour line (line weight scaled by potentialDamageWeight)
        GL11.glLineWidth(1.15f + 0.45f * dmgWeight);
        GL11.glColor4f(sustR, sustG, sustB, 0.52f * contourFade);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < contourSteps; i++) {
            GL11.glVertex2f(sustX[i], sustY[i]);
        }
        GL11.glEnd();

        // Plane 2: Short DoT (4-6s Burst) Mid Polar Plane + Detailed Mid-Horizon Iso-Damage Contour
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(shortR, shortG, shortB, 0.19f * readinessFade);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(shortR, shortG, shortB, 0.10f * readinessFade);
        for (int i = 0; i <= contourSteps; i++) {
            int idx = i % contourSteps;
            GL11.glVertex2f(shortX[idx], shortY[idx]);
        }
        GL11.glEnd();

        GL11.glLineWidth(1.10f + 0.38f * dmgWeight);
        GL11.glColor4f(shortR, shortG, shortB, 0.48f * contourFade);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < contourSteps; i++) {
            GL11.glVertex2f(shortX[i], shortY[i]);
        }
        GL11.glEnd();

        // Plane 3: Alpha Strike (0-2s Salvo) Hot Inner Core + High-Lethality Salvo Contour
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(alphaR, alphaG, alphaB, 0.24f * readinessFade);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(alphaR, alphaG, alphaB, 0.125f * readinessFade);
        for (int i = 0; i <= contourSteps; i++) {
            int idx = i % contourSteps;
            GL11.glVertex2f(alphaX[idx], alphaY[idx]);
        }
        GL11.glEnd();

        if (interpPeakAlpha >= 140f) {
            GL11.glLineWidth(1.20f + 0.42f * dmgWeight);
            GL11.glColor4f(alphaR, alphaG, alphaB, 0.60f * contourFade);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < contourSteps; i++) {
                GL11.glVertex2f(alphaX[i], alphaY[i]);
            }
            GL11.glEnd();
        }

        // Plane 4: Point Defense (PD) Dashed Inner Ring (Violet-Gold)
        if (hasPd) {
            GL11.glLineWidth(1.30f);
            GL11.glColor4f(0.92f, 0.78f, 1.00f, 0.56f * contourFade);
            GL11.glBegin(GL11.GL_LINES);
            for (int i = 0; i < contourSteps; i += 2) {
                int next = (i + 1) % contourSteps;
                GL11.glVertex2f(pdX[i], pdY[i]);
                GL11.glVertex2f(pdX[next], pdY[next]);
            }
            GL11.glEnd();
        }
    }

    private static final Color WARROOM_HULL_FRIENDLY_TINT = new Color(155, 255, 50);
    private static final Color WARROOM_HULL_ALLY_TINT = new Color(255, 215, 45);
    private static final Color WARROOM_HULL_ENEMY_TINT = new Color(255, 65, 45);

    /**
     * Renders visible ship hull sprites in screen space on top of the TSFC overlays in the Warroom
     * so that TSFC (Time, Space, Firepower, Convergence) appears cleanly UNDER the ship sprites
     * without invoking the heavy per-weapon turret loops of vanilla {@code com.fs.starfarer.ui.B.new}.
     */
    public static void renderWarroomShipSpritesAboveTsfc(
            CombatEngineAPI engine,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {
        if (!isAnyTsfcOverlayVisible() || engine == null || scale <= 1e-5f || fade <= 0.04f) {
            return;
        }
        List<ShipAPI> ships = engine.getShips();
        if (ships == null || ships.isEmpty()) return;

        float screenW = Global.getSettings() != null ? Global.getSettings().getScreenWidth() : 1920f;
        float screenH = Global.getSettings() != null ? Global.getSettings().getScreenHeight() : 1080f;
        FogOfWarAPI playerFog = engine.getFogOfWar(0);

        for (int i = 0, n = ships.size(); i < n; i++) {
            ShipAPI s = ships.get(i);
            if (s == null || !s.isAlive() || s.isHulk() || s.isExpired()) continue;
            SpriteAPI sprite = s.getSpriteAPI();
            if (sprite == null) continue;
            Vector2f loc = s.getLocation();
            if (loc == null) continue;

            float sx = offsetX + loc.x * scale;
            float sy = offsetY + loc.y * scale;
            float colRadPx = Math.max(8f, s.getCollisionRadius() * scale);
            if (!isCircleVisibleOnScreen(sx, sy, colRadPx + 12f, screenW, screenH)) {
                continue;
            }

            boolean isEnemy = (s.getOwner() == 1);
            float hullAlpha = fade;
            if (isEnemy && playerFog != null) {
                if (!BattlefieldFirepowerEvaluator.isEnemyShipVisibleSticky(engine, s, playerFog)) {
                    continue;
                }
                hullAlpha *= BattlefieldFirepowerEvaluator.getEnemyShipStickyVisibilityAlpha(engine, s, playerFog);
            }
            if (hullAlpha <= 0.04f) continue;

            float origW = sprite.getWidth();
            float origH = sprite.getHeight();
            float origCX = sprite.getCenterX();
            float origCY = sprite.getCenterY();
            float origAngle = sprite.getAngle();
            Color origColor = sprite.getColor();
            float origAlpha = sprite.getAlphaMult();

            Color tint = isEnemy
                    ? WARROOM_HULL_ENEMY_TINT
                    : (s.isAlly() ? WARROOM_HULL_ALLY_TINT : WARROOM_HULL_FRIENDLY_TINT);

            float minFloorPx = s.isFighter() ? 4.5f : 6.5f;
            float drawW = Math.max(minFloorPx, origW * scale);
            float drawH = Math.max(minFloorPx, origH * scale);
            float ratioW = (origW > 1e-4f) ? (drawW / origW) : scale;
            float ratioH = (origH > 1e-4f) ? (drawH / origH) : scale;

            try {
                sprite.setSize(drawW, drawH);
                sprite.setCenter(origCX * ratioW, origCY * ratioH);
                sprite.setAngle(s.getFacing() - 90f);
                sprite.setColor(tint);
                sprite.setNormalBlend();
                sprite.setAlphaMult(hullAlpha * 0.94f);
                sprite.renderAtCenter(sx, sy);
            } finally {
                sprite.setSize(origW, origH);
                sprite.setCenter(origCX, origCY);
                sprite.setAngle(origAngle);
                if (origColor != null) {
                    sprite.setColor(origColor);
                }
                sprite.setAlphaMult(origAlpha);
                sprite.setNormalBlend();
            }
        }
    }

    static void renderDivisionCordonIndicators(
            CombatEngineAPI engine,
            BattleGroupState group,
            boolean isEnemy,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        if (group == null || group.divisions.isEmpty()) return;
        FogOfWarAPI playerFog = engine.getFogOfWar(0);
        float now = engine.getTotalElapsedTime(false);

        for (DivisionState div : group.divisions) {
            if (!div.isCombatEffective()) continue;
            float kfBlend = BattlefieldFirepowerEvaluator.computeDivisionKeyframeBlendFactor(div, now);
            if (kfBlend < 0f) {
                if (div.tsfcKeyframesInitialized) continue;
                kfBlend = 1.0f;
            }
            float interpCordon = div.tsfcKeyframesInitialized
                    ? (div.prevCrossfireCordonIndex + (div.crossfireCordonIndex - div.prevCrossfireCordonIndex) * kfBlend)
                    : div.crossfireCordonIndex;
            if (interpCordon < 0.35f) continue;
            if (isEnemy && !BattlefieldFirepowerEvaluator.isEnemyDivisionVisibleSticky(engine, div, playerFog)) continue;

            Vector2f cWorld = resolveLiveRenderDivisionCentroid(engine, div, isEnemy, playerFog);

            float cx = offsetX + cWorld.x * scale;
            float cy = offsetY + cWorld.y * scale;
            float ringRadPx = Math.max(22f, Math.min(72f, div.spreadRadius * scale * 0.45f));

            boolean isUCordon = interpCordon >= 0.60f;
            float r = 1.0f;
            float g = isUCordon ? 0.22f : 0.62f;
            float b = 0.15f;
            float a = Math.min(0.85f, (0.38f + interpCordon * 0.45f) * fade);

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

    static void renderDivisionCordonLabels(
            CombatEngineAPI engine,
            BattleGroupState group,
            boolean isEnemy,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        if (group == null || group.divisions.isEmpty() || Global.getSettings() == null) return;
        FogOfWarAPI playerFog = engine.getFogOfWar(0);
        for (DivisionState div : group.divisions) {
            if (!div.isCombatEffective() || div.crossfireCordonIndex < 0.35f) continue;
            if (isEnemy && !OtcAI.isDivisionVisibleToSide(engine, 0, div)) continue;

            Vector2f cWorld = resolveLiveRenderDivisionCentroid(engine, div, isEnemy, playerFog);
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
                float tw = getCachedUiLabelWidth("cordon_" + div.id, lbl, tag);
                drawLabelCached("cordon_" + div.id, lbl, tag, cx - tw * 0.5f, cy - 28f, fade * 0.92f);
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
        boolean showConvergenceBtn = DivisionOtcCommandManager.hasAlphaCoreInPlayerFleet(engine);
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

        if (DivisionOtcCommandManager.pendingCommsAlphaCoreShip != null) {
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
    static void renderUrgentAlphaCoreCommsModal(
            CombatEngineAPI engine,
            float screenW,
            float screenH,
            float fade) {

        ShipAPI aiShip = DivisionOtcCommandManager.pendingCommsAlphaCoreShip;
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
        String headerText = "[URGENT COMMS FROM " + DivisionOtcCommandManager.formatAiShipName(aiShip) + "]";
        Color headerColor = new Color(170, 222, 255, 255);
        LabelAPI headerLbl = getCachedLabel("urgent_comms_hdr", headerText, Fonts.ORBITRON_12, headerColor);
        if (headerLbl != null) {
            float tw = getCachedUiLabelWidth("urgent_comms_hdr", headerLbl, headerText);
            float th = getCachedUiLabelHeight("urgent_comms_hdr", headerLbl, headerText);
            drawLabelCached(
                    "urgent_comms_hdr",
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
            float tw = getCachedUiLabelWidth("urgent_comms_shipname", shipNameLbl, shipCallsign);
            drawLabelCached(
                    "urgent_comms_shipname",
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
        Vector2f shift = DivisionOtcCommandManager.getCommandWordShiftOffset(realTimeSec);

        String[] parts = DivisionOtcCommandManager.getUrgentCommsPhraseParts(DivisionOtcCommandManager.activeCommsPhraseIndex);
        String prefixText = parts[0];
        String commandText = parts[1];
        String suffixText = parts[2];
        Color bodyColor = new Color(215, 246, 255, 255);
        Color commandColor = new Color(170, 222, 255, 255);

        String prefixKey = "urgent_comms_prefix_" + DivisionOtcCommandManager.activeCommsPhraseIndex;
        String cmdKey = "urgent_comms_cmd_" + DivisionOtcCommandManager.activeCommsPhraseIndex;
        String suffixKey = "urgent_comms_suffix_" + DivisionOtcCommandManager.activeCommsPhraseIndex;
        LabelAPI prefixLbl = prefixText.isEmpty()
                ? null
                : getCachedLabel(prefixKey, prefixText, Fonts.ORBITRON_16, bodyColor);
        LabelAPI cmdLbl = getCachedLabel(cmdKey, commandText, Fonts.ORBITRON_16, commandColor);
        LabelAPI suffixLbl = suffixText.isEmpty()
                ? null
                : getCachedLabel(suffixKey, suffixText, Fonts.ORBITRON_16, bodyColor);

        if (cmdLbl != null) {
            float wPrefix = (prefixLbl != null) ? getCachedUiLabelWidth(prefixKey, prefixLbl, prefixText) : 0f;
            float wCmd = getCachedUiLabelWidth(cmdKey, cmdLbl, commandText);
            float wSuffix = (suffixLbl != null) ? getCachedUiLabelWidth(suffixKey, suffixLbl, suffixText) : 0f;
            float totalW = wPrefix + wCmd + wSuffix;
            float baseX = modalX + (COMMS_MODAL_W - totalW) * 0.5f;
            float baseY = modalY + 76f;

            if (prefixLbl != null) {
                drawLabelCached(prefixKey, prefixLbl, prefixText, baseX, baseY, fade);
            }
            drawLabelCached(cmdKey, cmdLbl, commandText, baseX + wPrefix + shift.x, baseY + shift.y, fade);
            if (suffixLbl != null) {
                drawLabelCached(suffixKey, suffixLbl, suffixText, baseX + wPrefix + wCmd, baseY, fade);
            }
        }

        // 4. "Yes" and "No" button labels
        Color btnTextColor = new Color(170, 222, 255, 255);
        Color btnHoverColor = new Color(225, 252, 255, 255);
        LabelAPI yesLbl = getCachedLabel("urgent_comms_yes", "Yes", Fonts.ORBITRON_12, yesHover ? btnHoverColor : btnTextColor);
        if (yesLbl != null) {
            float tw = getCachedUiLabelWidth("urgent_comms_yes", yesLbl, "Yes");
            float th = getCachedUiLabelHeight("urgent_comms_yes", yesLbl, "Yes");
            drawLabelCached("urgent_comms_yes", yesLbl, "Yes", yesX + (COMMS_BTN_W - tw) * 0.5f, btnY + (COMMS_BTN_H - th) * 0.5f, fade);
        }
        LabelAPI noLbl = getCachedLabel("urgent_comms_no", "No", Fonts.ORBITRON_12, noHover ? btnHoverColor : btnTextColor);
        if (noLbl != null) {
            float tw = getCachedUiLabelWidth("urgent_comms_no", noLbl, "No");
            float th = getCachedUiLabelHeight("urgent_comms_no", noLbl, "No");
            drawLabelCached("urgent_comms_no", noLbl, "No", noX + (COMMS_BTN_W - tw) * 0.5f, btnY + (COMMS_BTN_H - th) * 0.5f, fade);
        }
    }

    static void renderCommsShipSprite(
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
                        float prevCx = sprite.getCenterX();
                        float prevCy = sprite.getCenterY();
                        float prevAngle = sprite.getAngle();
                        float prevAlpha = sprite.getAlphaMult();
                        Color prevColor = sprite.getColor();

                        sprite.setSize(drawW, drawH);
                        sprite.setCenter(drawW * 0.5f, drawH * 0.5f);
                        sprite.setAngle(0f);
                        sprite.setColor(Color.WHITE);
                        sprite.setAlphaMult(alphaMult);
                        sprite.renderAtCenter(centerX, centerY);

                        sprite.setSize(origW, origH);
                        sprite.setCenter(prevCx, prevCy);
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

    static void renderModalChoiceButtonBox(
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

    private static final Map<String, SpriteAPI> warroomBtnSpriteCache = new HashMap<>();

    static void renderSingleWarroomSquareIconButton(
            float x,
            float y,
            String iconPath,
            boolean active,
            boolean hovered,
            float fade) {

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        float br, bg, bb, ba;
        if (active) {
            br = 0.42f; bg = 0.90f; bb = 1.00f; ba = 0.98f * fade;
        } else if (hovered) {
            br = 0.35f; bg = 0.72f; bb = 0.88f; ba = 0.96f * fade;
        } else {
            br = 54f / 255f; bg = 115f / 255f; bb = 142f / 255f; ba = 0.95f * fade;
        }

        // Batch all 5 button quad layers into a single GL_QUADS pass
        GL11.glBegin(GL11.GL_QUADS);

        // 1. Subtle right-edge horizontal shadow/glow bar behind the bracket
        if (active || hovered) {
            GL11.glColor4f(0.18f, 0.52f, 0.68f, (active ? 0.36f : 0.22f) * fade);
        } else {
            GL11.glColor4f(0.08f, 0.14f, 0.19f, 0.45f * fade);
        }
        GL11.glVertex2f(x - 2f, y - 2f);
        GL11.glVertex2f(x + BTN_WIDTH + 20f, y - 2f);
        GL11.glVertex2f(x + BTN_WIDTH + 20f, y + BTN_SQUARE_H + 2f);
        GL11.glVertex2f(x - 2f, y + BTN_SQUARE_H + 2f);

        // 2. Outer Square Icon Frame (36x34px at x..x+36, y..y+34)
        GL11.glColor4f(br, bg, bb, ba);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + BTN_SQUARE_W - 1f, y);
        GL11.glVertex2f(x + BTN_SQUARE_W - 1f, y + BTN_SQUARE_H);
        GL11.glVertex2f(x, y + BTN_SQUARE_H);
        GL11.glVertex2f(x + BTN_SQUARE_W - 1f, y + 1f);
        GL11.glVertex2f(x + BTN_SQUARE_W, y + 1f);
        GL11.glVertex2f(x + BTN_SQUARE_W, y + BTN_SQUARE_H - 1f);
        GL11.glVertex2f(x + BTN_SQUARE_W - 1f, y + BTN_SQUARE_H - 1f);

        // 3. Inner 32x32 Dark Icon Cavity (x+2..x+34, y+1..y+33)
        if (active) {
            GL11.glColor4f(0.06f, 0.18f, 0.25f, 0.96f * fade);
        } else if (hovered) {
            GL11.glColor4f(0.05f, 0.11f, 0.15f, 0.96f * fade);
        } else {
            GL11.glColor4f(10f / 255f, 14f / 255f, 17f / 255f, 0.96f * fade);
        }
        GL11.glVertex2f(x + 2f, y + 1f);
        GL11.glVertex2f(x + 34f, y + 1f);
        GL11.glVertex2f(x + 34f, y + 33f);
        GL11.glVertex2f(x + 2f, y + 33f);

        // 4. Right-Side Warroom Structural C-Bracket
        GL11.glColor4f(br, bg, bb, ba);
        GL11.glVertex2f(x + 11f, y + BTN_SQUARE_H + 1f);
        GL11.glVertex2f(x + 44f, y + BTN_SQUARE_H + 1f);
        GL11.glVertex2f(x + 44f, y + BTN_SQUARE_H + 2f);
        GL11.glVertex2f(x + 11f, y + BTN_SQUARE_H + 2f);

        GL11.glVertex2f(x + 11f, y - 2f);
        GL11.glVertex2f(x + 44f, y - 2f);
        GL11.glVertex2f(x + 44f, y - 1f);
        GL11.glVertex2f(x + 11f, y - 1f);

        GL11.glVertex2f(x + 38f, y + BTN_SQUARE_H);
        GL11.glVertex2f(x + 45f, y + BTN_SQUARE_H);
        GL11.glVertex2f(x + 45f, y + BTN_SQUARE_H + 1f);
        GL11.glVertex2f(x + 38f, y + BTN_SQUARE_H + 1f);

        GL11.glVertex2f(x + 38f, y - 1f);
        GL11.glVertex2f(x + 45f, y - 1f);
        GL11.glVertex2f(x + 45f, y);
        GL11.glVertex2f(x + 38f, y);

        GL11.glVertex2f(x + 40f, y);
        GL11.glVertex2f(x + 45f, y);
        GL11.glVertex2f(x + 45f, y + BTN_SQUARE_H);
        GL11.glVertex2f(x + 40f, y + BTN_SQUARE_H);

        // Outer 1px bevel column on the right edge of the bracket
        GL11.glColor4f(br * 0.82f, bg * 0.82f, bb * 0.82f, ba);
        GL11.glVertex2f(x + 45f, y);
        GL11.glVertex2f(x + 46f, y);
        GL11.glVertex2f(x + 46f, y + BTN_SQUARE_H);
        GL11.glVertex2f(x + 45f, y + BTN_SQUARE_H);
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

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        // 5. Render the pure graphical Warroom icon centered inside the 32x32 square cavity (NO text)
        try {
            SpriteAPI iconSprite = warroomBtnSpriteCache.get(iconPath);
            if (iconSprite == null || iconSprite.getTextureId() <= 0) {
                iconSprite = Global.getSettings().getSprite(iconPath);
                if (iconSprite != null && iconSprite.getTextureId() > 0) {
                    warroomBtnSpriteCache.put(iconPath, iconSprite);
                }
            }
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

    private static final Map<String, String> uiLabelLastTextCache = new HashMap<>();
    private static final Map<String, Float> uiLabelWidthCache = new HashMap<>();
    private static final Map<String, Float> uiLabelHeightCache = new HashMap<>();

    static float getCachedUiLabelWidth(String key, LabelAPI lbl, String text) {
        if (lbl == null) return 0f;
        Float w = uiLabelWidthCache.get(key);
        if (w == null) {
            w = Float.valueOf(lbl.computeTextWidth(text));
            uiLabelWidthCache.put(key, w);
        }
        return w.floatValue();
    }

    static float getCachedUiLabelHeight(String key, LabelAPI lbl, String text) {
        if (lbl == null) return 0f;
        Float h = uiLabelHeightCache.get(key);
        if (h == null) {
            h = Float.valueOf(lbl.computeTextHeight(text));
            uiLabelHeightCache.put(key, h);
        }
        return h.floatValue();
    }

    static void drawLabelCached(String key, LabelAPI lbl, String text, float x, float y, float alpha) {
        if (lbl == null) return;
        float tw = getCachedUiLabelWidth(key, lbl, text);
        float th = getCachedUiLabelHeight(key, lbl, text);
        lbl.getPosition().setSize(tw + 10f, th + 4f);
        lbl.getPosition().setLocation(x, y);
        lbl.render(alpha);
    }

    static void drawLabel(LabelAPI lbl, String text, float x, float y, float alpha) {
        if (lbl == null) return;
        float tw = lbl.computeTextWidth(text);
        float th = lbl.computeTextHeight(text);
        lbl.getPosition().setSize(tw + 10f, th + 4f);
        lbl.getPosition().setLocation(x, y);
        lbl.render(alpha);
    }

    static LabelAPI getCachedLabel(String key, String text, String font, Color color) {
        try {
            LabelAPI lbl = uiLabelCache.get(key);
            if (lbl == null) {
                lbl = Global.getSettings().createLabel(text, font);
                uiLabelCache.put(key, lbl);
                uiLabelLastTextCache.put(key, text);
                uiLabelWidthCache.remove(key);
                uiLabelHeightCache.remove(key);
            } else if (!text.equals(uiLabelLastTextCache.get(key))) {
                lbl.setText(text);
                uiLabelLastTextCache.put(key, text);
                uiLabelWidthCache.remove(key);
                uiLabelHeightCache.remove(key);
            }
            lbl.setColor(color);
            return lbl;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void renderScratchDirectionalGradient(
            float cx, float cy, float r, float g, float b, float centerAlpha) {
        float midAlpha = centerAlpha * 0.48f;
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(r, g, b, centerAlpha);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(r, g, b, midAlpha);
        for (int i = 0; i <= 48; i++) {
            int idx = (i == 48) ? 0 : i;
            GL11.glVertex2f(SCRATCH_TIME_MID_X[idx], SCRATCH_TIME_MID_Y[idx]);
        }
        GL11.glEnd();

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
}
