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
import java.util.Map;

/**
 * Renders the Command Window (Tab) Tactical Map Division Overlay:
 *  - Strictly restricted to the Command Window tactical map where the player issues orders
 *    (attached directly to the warroom UIPanelAPI and suppressed whenever !isShowingCommandUI()
 *    or warroom.isShowingVideoFeed() is true; never renders on the full sprite battle view).
 *  - Uses java.lang.invoke.MethodHandles (never java.lang.reflect.*, which Starsector's
 *    script classloader blocks with SecurityException).
 *  - Every alive constituent member of a division projects a small translucent green circle.
 *    Combined circles within a division and overlapping circles between divisions blend via
 *    standard alpha blending (GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA) into a less translucent green,
 *    informing the player of the location and footprint of maneuver units.
 *  - Over each division flagship is the division name, with faint lines projecting from the
 *    flagship toward its constituent members.
 *  - Visible enemy ships project the same small translucent circles, with sparing OpSec-safe
 *    flagship-to-member lines (only when both flagship and member are visible to the player's
 *    FogOfWarAPI and within close formation range <= 1150u, preventing fog-of-war leaks).
 */
public class DivisionTacticalMapOverlay extends BaseCustomUIPanelPlugin {

    private static final Logger LOG = Global.getLogger(DivisionTacticalMapOverlay.class);
    private static final int CIRCLE_SEGMENTS = 36;
    private static final float ENEMY_OPSEC_MAX_LINE_DIST = 1150f;
    private static final Color PLAYER_LABEL_COLOR = new Color(165, 255, 185, 235);
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
        float ringAlpha = Math.min(0.48f, baseCircleAlpha * 1.65f);
        float friendlyLineAlpha = Math.min(0.42f, baseCircleAlpha * 1.55f);
        // Sparing, fainter line alpha on visible enemy ships
        float enemyLineAlpha = Math.min(0.26f, baseCircleAlpha * 0.95f);

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
                    ringAlpha,
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
                    ringAlpha,
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
                    ringAlpha,
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
            float ringAlpha,
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

            // 1a. Project a small translucent circle around each constituent member ship:
            //     - Player's directly controlled forces (!isEnemy && !member.isAlly()) = Green
            //     - Non-player-controlled friendly/allied forces (!isEnemy && member.isAlly()) = Yellow
            //     - Visible enemy forces (isEnemy) = Red
            for (ShipAPI member : visibleMembers) {
                float mx = member.getLocation().x * scale + offsetX;
                float my = member.getLocation().y * scale + offsetY;
                float worldRadius = getMemberFootprintRadiusWorld(member);
                float radiusPx = clamp(worldRadius * scale, 13f, 48f);
                boolean isAlliedAi = !isEnemy && member.isAlly();

                if (isEnemy) {
                    GL11.glColor4f(0.96f, 0.20f, 0.20f, baseCircleAlpha);
                } else if (isAlliedAi) {
                    GL11.glColor4f(1.00f, 0.86f, 0.16f, baseCircleAlpha);
                } else {
                    GL11.glColor4f(0.18f, 0.92f, 0.38f, baseCircleAlpha);
                }
                GL11.glBegin(GL11.GL_TRIANGLE_FAN);
                GL11.glVertex2f(mx, my);
                for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
                    double angle = (2.0 * Math.PI * i) / CIRCLE_SEGMENTS;
                    float px = mx + (float) Math.cos(angle) * radiusPx;
                    float py = my + (float) Math.sin(angle) * radiusPx;
                    GL11.glVertex2f(px, py);
                }
                GL11.glEnd();

                GL11.glLineWidth(1.15f);
                if (isEnemy) {
                    GL11.glColor4f(1.00f, 0.34f, 0.32f, ringAlpha);
                } else if (isAlliedAi) {
                    GL11.glColor4f(1.00f, 0.92f, 0.32f, ringAlpha);
                } else {
                    GL11.glColor4f(0.32f, 0.98f, 0.50f, ringAlpha);
                }
                GL11.glBegin(GL11.GL_LINE_LOOP);
                for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
                    double angle = (2.0 * Math.PI * i) / CIRCLE_SEGMENTS;
                    float px = mx + (float) Math.cos(angle) * radiusPx;
                    float py = my + (float) Math.sin(angle) * radiusPx;
                    GL11.glVertex2f(px, py);
                }
                GL11.glEnd();
            }

            // 1b. Faint lines from the division flagship toward constituent members.
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

                GL11.glLineWidth(isEnemy ? 1.0f : 1.2f);
                if (isEnemy) {
                    GL11.glColor4f(1.00f, 0.32f, 0.30f, lineAlpha);
                } else if (isAlliedAiFlagship) {
                    GL11.glColor4f(1.00f, 0.90f, 0.30f, lineAlpha);
                } else {
                    GL11.glColor4f(0.42f, 1.00f, 0.58f, lineAlpha);
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

            LabelAPI label = getOrCreateLabel(div.displayName, isEnemy, isAlliedAiFlagship);
            if (label != null) {
                float textW = label.computeTextWidth(div.displayName);
                float textH = label.computeTextHeight(div.displayName);
                label.getPosition().setSize(textW + 10f, textH + 4f);
                label.getPosition().setLocation(fx - (textW * 0.5f), fy + offsetPx + textH);
                label.render(fade * (isEnemy ? 0.82f : 0.92f));
            }
        }
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

    private LabelAPI getOrCreateLabel(String text, boolean isEnemy, boolean isAlliedAi) {
        String prefix = isEnemy ? "E:" : (isAlliedAi ? "A:" : "P:");
        String key = prefix + text;
        LabelAPI label = labelCache.get(key);
        if (label == null) {
            label = Global.getSettings().createLabel(text, Fonts.DEFAULT_SMALL);
            if (isEnemy) {
                label.setColor(ENEMY_LABEL_COLOR);
            } else if (isAlliedAi) {
                label.setColor(ALLY_LABEL_COLOR);
            } else {
                label.setColor(PLAYER_LABEL_COLOR);
            }
            labelCache.put(key, label);
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
