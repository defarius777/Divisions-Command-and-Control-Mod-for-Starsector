package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.FogOfWarAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.DivisionIntentType;
import divisionai.DivisionModel.DivisionState;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the Command Window (Tab) Tactical Map Division Overlay:
 *  - Strictly restricted to the Command Window tactical map where the player issues orders
 *    (attached directly to the warroom UIPanelAPI and suppressed whenever !isShowingCommandUI()
 *    or warroom.isShowingVideoFeed() is true; never renders on the full sprite battle view).
 *  - Uses java.lang.invoke.MethodHandles (never java.lang.reflect.*, which Starsector's
 *    script classloader blocks with SecurityException).
 *  - Communicates both Division Footprint and Tactical Intentions:
 *    1. Footprint: Translucent per-hull discs + an oriented Naval Battle-Line Frontage Bar
 *       (with swept wingtips perpendicular to the division's facing axis) + cohesion health
 *       coloring (green when coherent, amber when scattered/reforming).
 *    2. Intentions (Friendly Divisions): Tactical C2 symbology vectors from the division flagship
 *       to its maneuver anchor or target enemy division:
 *       - Attack / Strike: Target vector + crosshair reticle on the enemy division.
 *       - Standoff / Firing Line: Maneuver vector terminating in a perpendicular T-bar firing line.
 *       - Flank / Doubling: Angled flanking arrow with double chevron.
 *       - Screen / Escort: Protective curved shield arc oriented toward the threat.
 *       - Haul Out / Reserve / Close Up: Withdrawal vector or inward rally brackets.
 *    3. Two-Line HUD Readout above Flagship: Division Callsign + compact [INTENT | FLUX] sub-badge.
 */
public class DivisionTacticalMapOverlay extends BaseCustomUIPanelPlugin {

    private static final Logger LOG = Global.getLogger(DivisionTacticalMapOverlay.class);
    private static final int CIRCLE_SEGMENTS = 36;
    private static final float ENEMY_OPSEC_MAX_LINE_DIST = 1150f;
    private static final Color PLAYER_LABEL_COLOR = new Color(165, 255, 185, 235);
    private static final Color PLAYER_INTENT_COLOR = new Color(130, 235, 255, 225);
    private static final Color ALLY_LABEL_COLOR = new Color(255, 235, 110, 240);
    private static final Color ENEMY_LABEL_COLOR = new Color(255, 125, 120, 225);

    private CombatEngineAPI engine;
    private BattleGroupState playerGroup;
    private BattleGroupState alliedAiGroup;
    private BattleGroupState enemyGroup;
    private final Map<String, LabelAPI> labelCache = new HashMap<>();

    private boolean attachedToWarroom = false;
    private boolean loggedHookFailure = false;
    private Object warroomInstance = null;
    private Object mapDisplayInstance = null;
    private MethodHandle isShowingVideoFeedHandle = null;
    private MethodHandle worldToMapScreenHandle = null;

    public DivisionTacticalMapOverlay(CombatEngineAPI engine) {
        this.engine = engine;
    }

    public void setEngine(CombatEngineAPI engine) {
        this.engine = engine;
    }

    public void setBattleGroups(BattleGroupState playerGroup, BattleGroupState alliedAiGroup, BattleGroupState enemyGroup) {
        this.playerGroup = playerGroup;
        this.alliedAiGroup = alliedAiGroup;
        this.enemyGroup = enemyGroup;
    }

    /**
     * Attaches a 0x0 CustomPanelAPI directly to the Command Window warroom (OoOO, which implements UIPanelAPI)
     * using java.lang.invoke.MethodHandles so Starsector's classloader never blocks reflection.
     */
    public void ensureAttachedToWarroomUI() {
        if (attachedToWarroom || engine == null) return;
        CombatUIAPI ui = engine.getCombatUI();
        if (ui == null) return;

        try {
            ClassLoader cl = ui.getClass().getClassLoader();
            Class<?> wrClass = Class.forName("com.fs.starfarer.combat.new.OoOO", false, cl);
            Class<?> mdClass = Class.forName("com.fs.starfarer.combat.new.b", false, cl);

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle getWarroomHandle = lookup.findVirtual(
                    ui.getClass(),
                    "getWarroom",
                    MethodType.methodType(wrClass));

            Object wr = getWarroomHandle.invoke(ui);
            if (!(wr instanceof UIPanelAPI)) {
                return; // Warroom not yet constructed
            }

            MethodHandle getMapDisplayHandle = lookup.findVirtual(
                    wrClass,
                    "getMapDisplay",
                    MethodType.methodType(mdClass));
            Object md = getMapDisplayHandle.invoke(wr);
            if (md == null) {
                return;
            }

            this.warroomInstance = wr;
            this.mapDisplayInstance = md;
            this.isShowingVideoFeedHandle = lookup.findVirtual(
                    wrClass,
                    "isShowingVideoFeed",
                    MethodType.methodType(boolean.class));
            this.worldToMapScreenHandle = lookup.findVirtual(
                    mdClass,
                    "o00000",
                    MethodType.methodType(Vector2f.class, Vector2f.class));

            UIPanelAPI warroomPanel = (UIPanelAPI) wr;
            CustomPanelAPI custom = Global.getSettings().createCustom(0f, 0f, this);
            warroomPanel.addComponent(custom).inBL(0f, 0f);
            this.attachedToWarroom = true;
            LOG.info("[DivisionAI] Attached Command Window tactical overlay to warroom.");
        } catch (Throwable t) {
            if (!loggedHookFailure) {
                loggedHookFailure = true;
                LOG.warn("[DivisionAI] Failed to attach Command Window overlay to warroom", t);
            }
        }
    }

    @Override
    public void render(float alphaMult) {
        if (!attachedToWarroom || engine == null) return;
        if (!DivisionAISettings.isTacticalMapOverlayEnabled()) return;

        CombatUIAPI ui = engine.getCombatUI();
        if (ui == null || !ui.isShowingCommandUI()) {
            return;
        }

        float cmdOpacity = ui.getCommandUIOpacity();
        if (cmdOpacity <= 0.05f || alphaMult <= 0.05f) {
            return;
        }

        // Do not render if the player is viewing a live sprite Video Feed inside the Command UI
        if (warroomInstance != null && isShowingVideoFeedHandle != null) {
            try {
                boolean showingFeed = (boolean) isShowingVideoFeedHandle.invoke(warroomInstance);
                if (showingFeed) {
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        if (mapDisplayInstance == null || worldToMapScreenHandle == null) {
            return;
        }

        // Query the exact Command Window map transform from mapDisplay.o00000(Vector2f worldLoc)
        float scale;
        float offsetX;
        float offsetY;
        try {
            Vector2f p0 = (Vector2f) worldToMapScreenHandle.invoke(mapDisplayInstance, new Vector2f(0f, 0f));
            Vector2f p1 = (Vector2f) worldToMapScreenHandle.invoke(mapDisplayInstance, new Vector2f(1000f, 0f));
            if (p0 == null || p1 == null) return;
            scale = (p1.x - p0.x) / 1000f;
            if (scale <= 1e-5f) return;
            offsetX = p0.x;
            offsetY = p0.y;
        } catch (Throwable t) {
            return;
        }

        float fade = Math.min(1f, cmdOpacity * alphaMult);
        float baseCircleAlpha = DivisionAISettings.getDivisionCircleBaseAlpha() * fade;
        float friendlyLineAlpha = Math.min(0.84f, Math.max(0.56f, baseCircleAlpha * 2.85f)) * fade;
        float enemyLineAlpha = Math.min(0.56f, Math.max(0.36f, baseCircleAlpha * 1.85f)) * fade;

        FogOfWarAPI playerFog = engine.getFogOfWar(0);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        // 1. Render Player Divisions (Green) and Allied AI Divisions (Yellow)
        if (playerGroup != null && !playerGroup.divisions.isEmpty()) {
            renderBattleGroupCirclesAndLines(
                    playerGroup,
                    false,
                    null,
                    scale,
                    offsetX,
                    offsetY,
                    baseCircleAlpha,
                    friendlyLineAlpha);
        }
        if (alliedAiGroup != null && !alliedAiGroup.divisions.isEmpty()) {
            renderBattleGroupCirclesAndLines(
                    alliedAiGroup,
                    false,
                    null,
                    scale,
                    offsetX,
                    offsetY,
                    baseCircleAlpha,
                    friendlyLineAlpha);
        }

        // 2. Render Enemy Divisions (ONLY ships visible to the player, with sparing OpSec-safe lines)
        if (enemyGroup != null && !enemyGroup.divisions.isEmpty()) {
            renderBattleGroupCirclesAndLines(
                    enemyGroup,
                    true,
                    playerFog,
                    scale,
                    offsetX,
                    offsetY,
                    baseCircleAlpha,
                    enemyLineAlpha);
        }

        GL11.glPopMatrix();
        GL11.glPopAttrib();

        // 3. Render Flagship Division Labels (Player + Allied AI + OpSec-safe visible Enemy flagships)
        if (playerGroup != null && !playerGroup.divisions.isEmpty()) {
            renderBattleGroupLabels(playerGroup, false, null, scale, offsetX, offsetY, fade);
        }
        if (alliedAiGroup != null && !alliedAiGroup.divisions.isEmpty()) {
            renderBattleGroupLabels(alliedAiGroup, false, null, scale, offsetX, offsetY, fade);
        }
        if (enemyGroup != null && !enemyGroup.divisions.isEmpty()) {
            renderBattleGroupLabels(enemyGroup, true, playerFog, scale, offsetX, offsetY, fade);
        }
    }

    private void renderBattleGroupCirclesAndLines(
            BattleGroupState battleGroup,
            boolean isEnemy,
            FogOfWarAPI playerFog,
            float scale,
            float offsetX,
            float offsetY,
            float baseCircleAlpha,
            float lineAlpha) {

        for (DivisionState div : battleGroup.divisions) {
            List<ShipAPI> alive = div.getAliveMembers();
            if (alive.isEmpty()) continue;

            List<ShipAPI> visibleMembers;
            if (!isEnemy) {
                visibleMembers = alive;
            } else {
                visibleMembers = new ArrayList<>(alive.size());
                for (ShipAPI s : alive) {
                    if (isEnemyShipVisibleOnCommandMap(s, playerFog)) {
                        visibleMembers.add(s);
                    }
                }
                if (visibleMembers.isEmpty()) continue;
            }

            boolean scatteredCohesion = !isEnemy
                    && (div.currentIntent == DivisionIntentType.CLOSE_UP || div.stationKeepingRatio > 1.30f);

            // 1a. Project a borderless, soft-feathered translucent circle around each constituent member ship
            //     so overlapping circles flow together naturally into a unified maneuver footprint:
            //     - Player's directly controlled forces (!isEnemy && !member.isAlly()) = Green (Amber if CLOSE_UP)
            //     - Non-player-controlled friendly/allied forces (!isEnemy && member.isAlly()) = Yellow
            //     - Visible enemy forces (isEnemy) = Red
            float centerAlpha = Math.min(0.45f, baseCircleAlpha * 1.25f);
            float edgeAlpha = baseCircleAlpha * 0.42f;
            for (ShipAPI member : visibleMembers) {
                float mx = member.getLocation().x * scale + offsetX;
                float my = member.getLocation().y * scale + offsetY;
                float worldRadius = getMemberFootprintRadiusWorld(member);
                float radiusPx = clamp(worldRadius * scale, 14f, 52f);
                boolean isAlliedAi = !isEnemy && member.isAlly();

                float cr;
                float cg;
                float cb;
                if (isEnemy) {
                    cr = 0.96f;
                    cg = 0.20f;
                    cb = 0.20f;
                } else if (isAlliedAi) {
                    cr = 1.00f;
                    cg = 0.86f;
                    cb = 0.16f;
                } else if (scatteredCohesion) {
                    cr = 1.00f;
                    cg = 0.64f;
                    cb = 0.18f;
                } else {
                    cr = 0.18f;
                    cg = 0.92f;
                    cb = 0.38f;
                }

                GL11.glBegin(GL11.GL_TRIANGLE_FAN);
                GL11.glColor4f(cr, cg, cb, centerAlpha);
                GL11.glVertex2f(mx, my);
                GL11.glColor4f(cr, cg, cb, edgeAlpha);
                for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
                    double angle = (2.0 * Math.PI * i) / CIRCLE_SEGMENTS;
                    float px = mx + (float) Math.cos(angle) * radiusPx;
                    float py = my + (float) Math.sin(angle) * radiusPx;
                    GL11.glVertex2f(px, py);
                }
                GL11.glEnd();
            }

            // 1b. High-visibility lines from the division flagship toward constituent members.
            //     For enemy divisions, lines are drawn sparingly to prevent OpSec breaches:
            //     ONLY if the actual division flagship is visible to the player AND the visible
            //     member is within close visual cohesion range (<= 1150u) of that flagship.
            ShipAPI flagship = resolveActiveFlagship(div, alive);
            if (flagship == null) continue;

            if (isEnemy && !isEnemyShipVisibleOnCommandMap(flagship, playerFog)) {
                // Never draw lines if the enemy flagship is hidden in fog of war
                continue;
            }

            if (visibleMembers.size() > 1) {
                float fx = flagship.getLocation().x * scale + offsetX;
                float fy = flagship.getLocation().y * scale + offsetY;
                boolean isAlliedAiFlagship = !isEnemy && flagship.isAlly();

                GL11.glLineWidth(isEnemy ? 1.75f : 2.35f);
                if (isEnemy) {
                    GL11.glColor4f(1.00f, 0.42f, 0.38f, lineAlpha);
                } else if (isAlliedAiFlagship) {
                    GL11.glColor4f(1.00f, 0.94f, 0.40f, lineAlpha);
                } else if (scatteredCohesion) {
                    GL11.glColor4f(1.00f, 0.78f, 0.30f, lineAlpha);
                } else {
                    GL11.glColor4f(0.52f, 1.00f, 0.68f, lineAlpha);
                }
                GL11.glBegin(GL11.GL_LINES);
                for (ShipAPI member : visibleMembers) {
                    if (member == flagship) continue;
                    if (isEnemy && distance(flagship.getLocation(), member.getLocation()) > ENEMY_OPSEC_MAX_LINE_DIST) {
                        continue;
                    }
                    float mx = member.getLocation().x * scale + offsetX;
                    float my = member.getLocation().y * scale + offsetY;
                    GL11.glVertex2f(fx, fy);
                    GL11.glVertex2f(mx, my);
                }
                GL11.glEnd();
            }

            // 1c. Friendly Division Frontage Bar & Tactical Intent Vector Symbology
            if (!isEnemy) {
                renderFriendlyDivisionFrontageAndIntent(div, flagship, visibleMembers, scale, offsetX, offsetY, lineAlpha, playerFog);
            }
        }
    }

    /**
     * Renders an oriented Naval Battle-Line Frontage Bracket across the division's lateral axis
     * plus a tactical C2 maneuver / target vector symbol indicating the division's active intent.
     */
    private void renderFriendlyDivisionFrontageAndIntent(
            DivisionState div,
            ShipAPI flagship,
            List<ShipAPI> members,
            float scale,
            float offsetX,
            float offsetY,
            float lineAlpha,
            FogOfWarAPI playerFog) {

        float fx = flagship.getLocation().x * scale + offsetX;
        float fy = flagship.getLocation().y * scale + offsetY;
        boolean isAllied = flagship.isAlly();

        // Compute forward facing unit vector toward target enemy division, assigned anchor, or flagship facing
        float dirX;
        float dirY;
        if (div.targetEnemyDivision != null && !div.targetEnemyDivision.centroid.equals(new Vector2f(0f, 0f))) {
            float dx = div.targetEnemyDivision.centroid.x - flagship.getLocation().x;
            float dy = div.targetEnemyDivision.centroid.y - flagship.getLocation().y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1f) {
                dirX = dx / len;
                dirY = dy / len;
            } else {
                double rad = Math.toRadians(flagship.getFacing());
                dirX = (float) Math.cos(rad);
                dirY = (float) Math.sin(rad);
            }
        } else {
            double rad = Math.toRadians(flagship.getFacing());
            dirX = (float) Math.cos(rad);
            dirY = (float) Math.sin(rad);
        }
        float latX = -dirY;
        float latY = dirX;

        // 1. Oriented Formation Frontage Bracket (shows line-abreast width & facing orientation)
        if (members.size() >= 2) {
            float halfWidthWorld = Math.min(1100f, Math.max(260f, (members.size() - 1) * div.stationSpacing * 0.48f));
            float halfW = clamp(halfWidthWorld * scale, 18f, 95f);
            float fwdOffset = 14f;
            float wingSweep = 7f;

            float bx = fx + dirX * fwdOffset;
            float by = fy + dirY * fwdOffset;
            float lx = bx - latX * halfW;
            float ly = by - latY * halfW;
            float rx = bx + latX * halfW;
            float ry = by + latY * halfW;

            GL11.glLineWidth(2.4f);
            if (isAllied) {
                GL11.glColor4f(1.00f, 0.92f, 0.36f, Math.min(0.88f, lineAlpha * 1.15f));
            } else if (div.currentIntent == DivisionIntentType.CLOSE_UP) {
                GL11.glColor4f(1.00f, 0.76f, 0.26f, Math.min(0.90f, lineAlpha * 1.20f));
            } else {
                GL11.glColor4f(0.42f, 1.00f, 0.72f, Math.min(0.88f, lineAlpha * 1.15f));
            }

            GL11.glBegin(GL11.GL_LINE_STRIP);
            GL11.glVertex2f(lx - dirX * wingSweep, ly - dirY * wingSweep);
            GL11.glVertex2f(lx, ly);
            GL11.glVertex2f(rx, ry);
            GL11.glVertex2f(rx - dirX * wingSweep, ry - dirY * wingSweep);
            GL11.glEnd();
        }

        // 2. Tactical Intent Vector & Destination Symbol
        DivisionIntentType intent = div.currentIntent;
        Vector2f destWorld = null;
        boolean targetIsEnemy = false;

        if (div.targetEnemyDivision != null
                && (intent == DivisionIntentType.PRESS_HOME
                || intent == DivisionIntentType.FORM_LINE_AND_FIX
                || intent == DivisionIntentType.TOT_MISSILE_STRIKE
                || intent == DivisionIntentType.LAUNCH_DECKLOAD_STRIKE
                || intent == DivisionIntentType.LAUNCH_ALPHA_STRIKE
                || intent == DivisionIntentType.HARASSING_MISSILE_ATTACK
                || intent == DivisionIntentType.GIVE_GENERAL_CHASE)) {
            ShipAPI enemyFlag = div.targetEnemyDivision.flagship;
            if (enemyFlag != null && isEnemyShipVisibleOnCommandMap(enemyFlag, playerFog)) {
                destWorld = enemyFlag.getLocation();
                targetIsEnemy = true;
            } else if (div.stationPoint != null && (div.stationPoint.x != 0f || div.stationPoint.y != 0f)) {
                destWorld = div.stationPoint;
            }
        } else if (div.stationPoint != null && (div.stationPoint.x != 0f || div.stationPoint.y != 0f)) {
            destWorld = div.stationPoint;
        }

        if (destWorld == null) return;
        float tx = destWorld.x * scale + offsetX;
        float ty = destWorld.y * scale + offsetY;
        float dx = tx - fx;
        float dy = ty - fy;
        float distPx = (float) Math.sqrt(dx * dx + dy * dy);
        if (distPx < 16f) return;

        float ux = dx / distPx;
        float uy = dy / distPx;
        float px = -uy;
        float py = ux;

        float vecAlpha = Math.min(0.88f, lineAlpha * 1.15f);
        if (targetIsEnemy) {
            GL11.glColor4f(1.00f, 0.62f, 0.24f, vecAlpha);
        } else if (intent == DivisionIntentType.HAUL_OUT_TO_VENT || intent == DivisionIntentType.BREAK_OFF_AND_RETIRE) {
            GL11.glColor4f(0.50f, 0.88f, 1.00f, vecAlpha);
        } else if (intent == DivisionIntentType.TAKE_SCREENING_STATION || intent == DivisionIntentType.FLY_COMBAT_SPACE_PATROL) {
            GL11.glColor4f(0.40f, 0.98f, 0.88f, vecAlpha);
        } else {
            GL11.glColor4f(0.48f, 1.00f, 0.70f, vecAlpha);
        }

        float startOffset = 16f;
        float endOffset = targetIsEnemy ? 14f : 6f;
        float shaftLen = distPx - startOffset - endOffset;
        if (shaftLen > 8f) {
            GL11.glLineWidth(2.15f);
            int dashes = Math.max(3, Math.min(18, (int) (shaftLen / 14f)));
            float step = shaftLen / dashes;
            GL11.glBegin(GL11.GL_LINES);
            for (int i = 0; i < dashes; i++) {
                float s0 = startOffset + i * step;
                float s1 = startOffset + (i + 0.65f) * step;
                GL11.glVertex2f(fx + ux * s0, fy + uy * s0);
                GL11.glVertex2f(fx + ux * s1, fy + uy * s1);
            }
            GL11.glEnd();
        }

        GL11.glLineWidth(2.4f);
        if (targetIsEnemy) {
            float r = 9f;
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(tx + ux * r, ty + uy * r);
            GL11.glVertex2f(tx + px * r, ty + py * r);
            GL11.glVertex2f(tx - ux * r, ty - uy * r);
            GL11.glVertex2f(tx - px * r, ty - py * r);
            GL11.glEnd();
        } else if (intent == DivisionIntentType.CLOSE_AND_ENGAGE || intent == DivisionIntentType.FORM_LINE_AND_FIX) {
            float barHalf = 14f;
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(tx - px * barHalf, ty - py * barHalf);
            GL11.glVertex2f(tx + px * barHalf, ty + py * barHalf);
            GL11.glVertex2f(tx, ty);
            GL11.glVertex2f(tx - ux * 7f + px * 5f, ty - uy * 7f + py * 5f);
            GL11.glVertex2f(tx, ty);
            GL11.glVertex2f(tx - ux * 7f - px * 5f, ty - uy * 7f - py * 5f);
            GL11.glEnd();
        } else if (intent == DivisionIntentType.TAKE_SCREENING_STATION || intent == DivisionIntentType.FLY_COMBAT_SPACE_PATROL) {
            float arcW = 15f;
            float arcFwd = 5f;
            GL11.glBegin(GL11.GL_LINE_STRIP);
            GL11.glVertex2f(tx - px * arcW - ux * arcFwd, ty - py * arcW - uy * arcFwd);
            GL11.glVertex2f(tx - px * (arcW * 0.5f), ty - py * (arcW * 0.5f));
            GL11.glVertex2f(tx + ux * arcFwd, ty + uy * arcFwd);
            GL11.glVertex2f(tx + px * (arcW * 0.5f), ty + py * (arcW * 0.5f));
            GL11.glVertex2f(tx + px * arcW - ux * arcFwd, ty + py * arcW - uy * arcFwd);
            GL11.glEnd();
        } else {
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(tx, ty);
            GL11.glVertex2f(tx - ux * 8f + px * 5.5f, ty - uy * 8f + py * 5.5f);
            GL11.glVertex2f(tx, ty);
            GL11.glVertex2f(tx - ux * 8f - px * 5.5f, ty - uy * 8f - py * 5.5f);
            GL11.glEnd();
        }
    }

    private void renderBattleGroupLabels(
            BattleGroupState battleGroup,
            boolean isEnemy,
            FogOfWarAPI playerFog,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        for (DivisionState div : battleGroup.divisions) {
            List<ShipAPI> alive = div.getAliveMembers();
            if (alive.isEmpty()) continue;

            ShipAPI flagship = resolveActiveFlagship(div, alive);
            if (flagship == null) continue;

            if (isEnemy) {
                // OpSec protection: only show enemy division name if its flagship is visible
                if (!isEnemyShipVisibleOnCommandMap(flagship, playerFog)) {
                    continue;
                }
            }

            float fx = flagship.getLocation().x * scale + offsetX;
            float fy = flagship.getLocation().y * scale + offsetY;
            float offsetPx = clamp(getMemberFootprintRadiusWorld(flagship) * scale, 13f, 48f) + 4f;
            boolean isAlliedAiFlagship = !isEnemy && flagship.isAlly();

            DivisionNomenclature.NomenclatureCategory nomCat =
                    div.nomenclatureCategory != null
                            ? div.nomenclatureCategory
                            : battleGroup.nomenclatureCategory;
            String liveCallsign = DivisionNomenclature.resolveDynamicDivisionDisplayName(
                    nomCat,
                    div.archetype,
                    div.divisionNumber,
                    div.displayName);

            String cacheKeyOverride = (nomCat == DivisionNomenclature.NomenclatureCategory.THREAT_SWARM)
                    ? ("THREAT_DIV:" + div.id)
                    : null;
            LabelAPI label = getOrCreateLabel(liveCallsign, cacheKeyOverride, isEnemy, isAlliedAiFlagship, false);
            if (label != null) {
                float textW = label.computeTextWidth(liveCallsign);
                float textH = label.computeTextHeight(liveCallsign);
                label.getPosition().setSize(textW + 10f, textH + 4f);
                label.getPosition().setLocation(fx - (textW * 0.5f), fy + offsetPx + textH + (isEnemy ? 0f : 13f));
                label.render(fade * (isEnemy ? 0.82f : 0.94f));
            }

            if (!isEnemy) {
                String intentTag = (nomCat == DivisionNomenclature.NomenclatureCategory.ABYSSAL_ERROR)
                        ? "ERROR"
                        : buildCompactIntentTag(div);
                LabelAPI subLabel = getOrCreateLabel(intentTag, null, false, isAlliedAiFlagship, true);
                if (subLabel != null) {
                    float subW = subLabel.computeTextWidth(intentTag);
                    float subH = subLabel.computeTextHeight(intentTag);
                    subLabel.getPosition().setSize(subW + 10f, subH + 4f);
                    subLabel.getPosition().setLocation(fx - (subW * 0.5f), fy + offsetPx + subH);
                    subLabel.render(fade * 0.86f);
                }
            }
        }
    }

    private static String buildCompactIntentTag(DivisionState div) {
        String intentShort;
        switch (div.currentIntent) {
            case FORM_LINE_AND_FIX:
                intentShort = "FORM & FIX";
                break;
            case CLOSE_AND_ENGAGE:
                intentShort = "ENGAGE";
                break;
            case PRESS_HOME:
                intentShort = "PRESS HOME";
                break;
            case TAKE_SCREENING_STATION:
                intentShort = "SCREEN";
                break;
            case REINFORCE_DIVISION:
                intentShort = "REINFORCE";
                break;
            case WORK_FLANK:
                intentShort = "FLANK";
                break;
            case HARASSING_MISSILE_ATTACK:
                intentShort = "HARASS";
                break;
            case MARSHAL_FOR_STRIKE:
                intentShort = "MARSHAL";
                break;
            case TOT_MISSILE_STRIKE:
                intentShort = "TOT STRIKE";
                break;
            case BREAK_OFF_AND_RETIRE:
                intentShort = "RETIRE";
                break;
            case HAUL_OUT_TO_VENT:
                intentShort = "VENTING";
                break;
            case CLOSE_UP:
                intentShort = "CLOSE UP";
                break;
            case HOLD_IN_RESERVE:
                intentShort = "RESERVE";
                break;
            case TAKE_AND_HOLD:
                intentShort = "HOLD OBJ";
                break;
            case FLY_COMBAT_SPACE_PATROL:
                intentShort = "FLEET CAP";
                break;
            case FLY_CYCLIC_STRIKES:
                intentShort = "CYCLIC STRIKE";
                break;
            case LAUNCH_DECKLOAD_STRIKE:
                intentShort = "DECK STRIKE";
                break;
            case LAUNCH_ALPHA_STRIKE:
                intentShort = "ALPHA STRIKE";
                break;
            case FLY_ARMED_RECONNAISSANCE:
                intentShort = "RECON";
                break;
            case FLY_FIGHTER_SWEEP:
                intentShort = "FIGHTER SWEEP";
                break;
            case GIVE_GENERAL_CHASE:
                intentShort = "CHASE";
                break;
            default:
                intentShort = div.currentIntent.name();
                break;
        }
        int fluxPct = Math.round(div.avgFluxLevel * 100f);
        return String.format(Locale.ROOT, "[%s | %d%% FLUX]", intentShort, fluxPct);
    }

    private static boolean isEnemyShipVisibleOnCommandMap(ShipAPI ship, FogOfWarAPI playerFog) {
        if (ship == null || !ship.isAlive()) return false;
        if (playerFog == null) return true;
        return playerFog.isVisible(ship);
    }

    private static float getMemberFootprintRadiusWorld(ShipAPI ship) {
        HullSize size = ship != null ? ship.getHullSize() : HullSize.DESTROYER;
        if (size == null) return 440f;
        switch (size) {
            case CAPITAL_SHIP:
                return 600f;
            case CRUISER:
                return 510f;
            case DESTROYER:
                return 430f;
            case FRIGATE:
            default:
                return 360f;
        }
    }

    private LabelAPI getOrCreateLabel(
            String text,
            String cacheKeyOverride,
            boolean isEnemy,
            boolean isAlliedAi,
            boolean isIntentBadge) {
        String prefix = isEnemy ? "E:" : (isAlliedAi ? "A:" : (isIntentBadge ? "PI:" : "P:"));
        String key = prefix + (cacheKeyOverride != null ? cacheKeyOverride : text);
        LabelAPI label = labelCache.get(key);
        if (label == null) {
            // Use Starsector's blocky, geometric Orbitron display typeface:
            //  - ORBITRON_20AA for primary division callsigns
            //  - GROUP_NUM_FONT (orbitron12) for the compact tactical intent & flux readout
            String font = isIntentBadge ? Fonts.GROUP_NUM_FONT : Fonts.ORBITRON_20AA;
            label = Global.getSettings().createLabel(text, font);
            if (isEnemy) {
                label.setColor(ENEMY_LABEL_COLOR);
            } else if (isAlliedAi) {
                label.setColor(ALLY_LABEL_COLOR);
            } else if (isIntentBadge) {
                label.setColor(PLAYER_INTENT_COLOR);
            } else {
                label.setColor(PLAYER_LABEL_COLOR);
            }
            labelCache.put(key, label);
        } else if (cacheKeyOverride != null) {
            label.setText(text);
        }
        return label;
    }

    private static ShipAPI resolveActiveFlagship(DivisionState div, List<ShipAPI> alive) {
        if (div.flagship != null && alive.contains(div.flagship)) {
            return div.flagship;
        }
        div.flagship = alive.get(0);
        return div.flagship;
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
