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
import static divisionai.BattlefieldFirepowerEvaluator.*;

/**
 * OpenGL Warroom & Command View Renderer for the T-S-F-C (Time, Space, Firepower, Convergence)
 * tactical overlay, right-side Warroom square-bordered icon buttons, legend, and Urgent Alpha Core
 * Comms modal.
 *
 * Extracted from {@link BattlefieldFirepowerEvaluator} to keep mathematical evaluation/telemetry
 * cleanly separated from OpenGL screen-space rendering.
 */
public class TsfcWarroomRenderer {

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
        setActiveBattleGroups(playerGroup, alliedAiGroup, enemyGroup);
        if (!timeOverlayVisible && !spaceOverlayVisible && !convergenceOverlayVisible) return;

        // Throttle cross-group Time/Space/Convergence telemetry recomputation so it does NOT run 3x on every 60+ FPS render frame
        float now = engine.getTotalElapsedTime(false);
        int divCountSig = ((playerGroup != null ? playerGroup.divisions.size() : 0) << 16)
                ^ ((alliedAiGroup != null ? alliedAiGroup.divisions.size() : 0) << 8)
                ^ (enemyGroup != null ? enemyGroup.divisions.size() : 0);
        boolean needsUninitBase = hasAnyUninitializedDivisionTimeSpace(engine, playerGroup)
                || hasAnyUninitializedDivisionTimeSpace(engine, alliedAiGroup)
                || hasAnyUninitializedDivisionTimeSpace(engine, enemyGroup);
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

    public static boolean hasAnyUninitializedDivisionTimeSpace(CombatEngineAPI engine, BattleGroupState group) {
        if (group == null) return false;
        List<DivisionState> allDivs = getDivisionsAndStationBastionsForRendering(engine, group);
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
        List<DivisionState> renderDivs = getDivisionsAndStationBastionsForRendering(engine, group);
        if (renderDivs.isEmpty()) return;
        float screenW = Global.getSettings() != null ? Global.getSettings().getScreenWidth() : 1920f;
        float screenH = Global.getSettings() != null ? Global.getSettings().getScreenHeight() : 1080f;
        FogOfWarAPI playerFog = engine.getFogOfWar(0);

        for (DivisionState div : renderDivs) {
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

    static void populateDirectionalTimeScratchCoords(float cx, float cy, DivisionState div, float scale) {
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
        List<DivisionState> renderDivs = getDivisionsAndStationBastionsForRendering(engine, group);
        if (renderDivs.isEmpty()) return;
        float screenW = Global.getSettings() != null ? Global.getSettings().getScreenWidth() : 1920f;
        float screenH = Global.getSettings() != null ? Global.getSettings().getScreenHeight() : 1080f;
        FogOfWarAPI playerFog = engine.getFogOfWar(0);

        for (DivisionState div : renderDivs) {
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
                // Tier 3 (Outer): Potentially Dominated Space given Time to maneuver (directional, non-circular, map-edge pinched)
                drawDirectionalSpaceTierGradient(cx, cy, div, 2, scale, r, g, b, 0.11f * fade);
                drawDirectionalSpaceTierOutline(cx, cy, div, 2, scale, 1.0f, r, g, b, 0.22f * fade, 1.1f, true);

                // Tier 2 (Middle): Dominated Nearby Space (directional, non-circular, map-edge pinched)
                drawDirectionalSpaceTierGradient(cx, cy, div, 1, scale, r, g, b, 0.16f * fade);
                drawDirectionalSpaceTierOutline(cx, cy, div, 1, scale, 1.0f, r, g, b, 0.38f * fade, 1.4f, false);

                // Tier 1 (Inner Core): Directly Dominated Up Close Space (directional, non-circular, map-edge pinched)
                drawDirectionalSpaceTierGradient(cx, cy, div, 0, scale, r, g, b, 0.20f * fade);
                drawDirectionalSpaceTierOutline(cx, cy, div, 0, scale, 1.0f, r, g, b, 0.52f * fade, 1.6f, false);

                // Highlight Strongpoint (bastion) or Weak Point (seam) on the inner Space perimeter
                if (div.isStrongpoint) {
                    drawDirectionalSpaceTierOutline(cx, cy, div, 0, scale, 0.75f, 0.45f, 0.98f, 0.88f, 0.55f * fade, 1.8f, false);
                } else if (div.isWeakPoint) {
                    drawDirectionalSpaceTierOutline(cx, cy, div, 0, scale, 0.82f, 1.00f, 0.75f, 0.22f, 0.60f * fade, 1.8f, true);
                }
            }

            // Render airborne Strike Craft squadron permeable Space Zones of Control when deployed away from the carrier centroid.
            // Per Task F: Because warships can physically move through strike craft, strike craft do NOT render a solid
            // Tier-1 Inner Hull-Exclusion ring; they render only a dashed permeable outer Space contour.
            for (ShipAPI s : div.getAliveMembers()) {
                List<com.fs.starfarer.api.combat.FighterWingAPI> wings = s.getAllWings();
                if (wings == null) continue;
                for (com.fs.starfarer.api.combat.FighterWingAPI wing : wings) {
                    ShipAPI wLead = resolveAliveWingLeader(wing);
                    if (wLead == null || distance(wLead.getLocation(), cWorld) <= 320f) continue;
                    if (isEnemy && playerFog != null && !playerFog.isVisible(wLead)) continue;
                    float wx = offsetX + wLead.getLocation().x * scale;
                    float wy = offsetY + wLead.getLocation().y * scale;
                    float wDomPx = Math.max(12f, 480f * scale);
                    if (!isCircleVisibleOnScreen(wx, wy, wDomPx + 8f, screenW, screenH)) continue;
                    drawIncreasinglyTransparentCircle(wx, wy, wDomPx, r, g, b, 0.09f * fade, 24);
                    drawCircleOutline(wx, wy, wDomPx, r, g, b, 0.28f * fade, 1.10f, 24, true);
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
        List<DivisionState> renderDivs = getDivisionsAndStationBastionsForRendering(engine, group);
        if (renderDivs.isEmpty()) return;
        float screenW = Global.getSettings() != null ? Global.getSettings().getScreenWidth() : 1920f;
        float screenH = Global.getSettings() != null ? Global.getSettings().getScreenHeight() : 1080f;
        FogOfWarAPI playerFog = engine.getFogOfWar(0);

        for (DivisionState div : renderDivs) {
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
                // Directional & map-edge-pinched Convergence envelope
                drawDirectionalConvergenceEnvelope(engine, cx, cy, cWorld, div, scale, r, g, b, 0.18f * fade * activeBoost, 0.52f * fade * activeBoost);

                // Render the iconic Convergence battle glyph: an inscribed triangle inside an inscribed square inside the pinched envelope
                float minEdgeClear = Math.min(
                        computeDirectionalBoundaryClearance(engine, cWorld, 1f, 0f, 110f),
                        Math.min(
                                computeDirectionalBoundaryClearance(engine, cWorld, -1f, 0f, 110f),
                                Math.min(
                                        computeDirectionalBoundaryClearance(engine, cWorld, 0f, 1f, 110f),
                                        computeDirectionalBoundaryClearance(engine, cWorld, 0f, -1f, 110f))));
                float glyphWorldR = pinchRadiusByBoundary(convWorldRad, minEdgeClear);
                float glyphRadPx = Math.max(18f, glyphWorldR * scale);
                float sqHalf = glyphRadPx * 0.70710678f; // inscribed square half-side = R / sqrt(2)
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

    static void populateDirectionalSpaceScratchCoords(float cx, float cy, DivisionState div, int tier, float scale) {
        boolean init = div.directionalSpaceInitialized;
        float fallbackWorldR = (tier == 0)
                ? Math.max(100f, div.spaceInnerRadius)
                : ((tier == 1) ? Math.max(200f, div.spaceDominatedRadius) : Math.max(300f, div.spacePotentialRadius));
        float[] src = (tier == 0)
                ? div.directionalSpaceInnerRadii
                : ((tier == 1) ? div.directionalSpaceDominatedRadii : div.directionalSpacePotentialRadii);
        for (int i = 0; i < 48; i++) {
            float worldR;
            if (!init || src == null) {
                worldR = fallbackWorldR;
            } else {
                int s0 = i >> 1;
                if ((i & 1) == 0) {
                    worldR = src[s0];
                } else {
                    int s1 = (s0 + 1) % DivisionState.TIME_DIRECTIONS;
                    worldR = 0.5f * (src[s0] + src[s1]);
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
        if (div == null || scale <= 1e-5f || centerAlpha <= 0.002f) return;
        populateDirectionalSpaceScratchCoords(cx, cy, div, tier, scale);
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
        if (div == null || scale <= 1e-5f || alpha <= 0.002f) return;
        populateDirectionalSpaceScratchCoords(cx, cy, div, tier, scale);
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
        if (div == null || scale <= 1e-5f) return;
        float weaponPart = 0.24f * Math.max(500f, div.avgEngageRange);
        for (int i = 0; i < 48; i++) {
            float deg = i * (360f / 48f);
            float tR = getDirectionalTimeRadius(div, deg);
            float sR = getDirectionalSpaceDominatedRadius(div, deg);
            float worldR = 0.38f * tR + 0.38f * sR + weaponPart;
            float cos = UNIT_COS_48[i];
            float sin = UNIT_SIN_48[i];
            if (cWorld != null) {
                float edgeDist = computeDirectionalBoundaryClearance(engine, cWorld, cos, sin, 110f);
                worldR = pinchRadiusByBoundary(worldR, edgeDist);
            }
            float outerRadPx = Math.max(16f, worldR * scale);
            float midRadPx = outerRadPx * 0.55f;
            SCRATCH_TIME_OUTER_X[i] = cx + cos * outerRadPx;
            SCRATCH_TIME_OUTER_Y[i] = cy + sin * outerRadPx;
            SCRATCH_TIME_MID_X[i] = cx + cos * midRadPx;
            SCRATCH_TIME_MID_Y[i] = cy + sin * midRadPx;
        }
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

        drawDirectionalTimeOutline(cx, cy, div, scale, 1.0f, r, g, b, outlineAlpha, 1.6f, 48, false);
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

    static void renderSingleShipFirepowerRose(
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

            // Map-Edge Pinching for Firepower Roses (Item D):
            // Pinch rSust, rShort, rAlpha, and pdReachPx against the combat map boundary along ray (cos, sin)
            float edgeClearWorld = computeDirectionalBoundaryClearance(lastTimeSpaceConvEngine, ship.getLocation(), cos, sin, 90f);
            float edgeClearPx = Math.max(baseShipRadPx, edgeClearWorld * scale);
            if (rSust > edgeClearPx * 0.72f) {
                rSust = Math.max(baseShipRadPx * 0.45f, pinchRadiusByBoundary(rSust, edgeClearPx));
            }
            if (rShort > edgeClearPx * 0.72f) {
                rShort = Math.max(baseShipRadPx * 0.35f, Math.min(rSust, pinchRadiusByBoundary(rShort, edgeClearPx)));
            }
            if (rAlpha > edgeClearPx * 0.72f) {
                rAlpha = Math.max(baseShipRadPx * 0.25f, Math.min(Math.max(rSust, edgeClearPx), pinchRadiusByBoundary(rAlpha, edgeClearPx)));
            }
            if (pdReachPx > edgeClearPx * 0.72f) {
                pdReachPx = pinchRadiusByBoundary(pdReachPx, edgeClearPx);
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

    static void renderDivisionCordonLabels(
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
    static void renderUrgentAlphaCoreCommsModal(
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

    static void renderSingleWarroomSquareIconButton(
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
            } else {
                lbl.setText(text);
            }
            lbl.setColor(color);
            return lbl;
        } catch (Throwable ignored) {
            return null;
        }
    }

}
