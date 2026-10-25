package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import divisionai.DivisionModel.BattlegroupState;
import divisionai.DivisionModel.DivisionState;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the Tactical Map (Tab) Division Overlay:
 *  1. Projects a translucent green circle around each division's constituent members.
 *     Because circles are drawn with standard alpha blending (GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA),
 *     any region where two or more divisions' circles overlap automatically becomes a less translucent
 *     (more opaque/vivid) green.
 *  2. Projects faint green command lines from the division flagship to each constituent member.
 *  3. Renders the name of the division directly over the division flagship.
 *
 * Attaches a 0x0 CustomPanelAPI to CombatState's widgetPanel so the overlay renders above the
 * warroom's dark background dimmer without intercepting any mouse clicks, and uses
 * warroom.computeWorldLocation(x, y) to match the exact tactical map pan/zoom transform.
 */
public class DivisionTacticalMapOverlay extends BaseCustomUIPanelPlugin {

    private static final int CIRCLE_SEGMENTS = 56;
    private static final Color LABEL_COLOR = new Color(165, 255, 185, 235);

    private final CombatEngineAPI engine;
    private BattlegroupState alliedBg;
    private final Map<String, LabelAPI> labelCache = new HashMap<>();

    private boolean widgetAttached = false;
    private boolean widgetAttachFailed = false;
    private Object warroomInstance = null;
    private Method computeWorldLocMethod = null;

    public DivisionTacticalMapOverlay(CombatEngineAPI engine) {
        this.engine = engine;
    }

    public void setAlliedBattlegroup(BattlegroupState alliedBg) {
        this.alliedBg = alliedBg;
    }

    /**
     * Attaches a 0x0 CustomPanelAPI to the combat UI's widgetPanel so render() runs
     * after the warroom's background dimmer quad, keeping colors crisp on the Tab map.
     */
    public void ensureAttachedToWarroomUI() {
        if (widgetAttached || widgetAttachFailed || engine == null) return;
        CombatUIAPI ui = engine.getCombatUI();
        if (ui == null) return;

        try {
            Method getWarroom = ui.getClass().getMethod("getWarroom");
            Object wr = getWarroom.invoke(ui);
            if (wr == null) return; // Warroom not yet constructed

            this.warroomInstance = wr;
            this.computeWorldLocMethod = wr.getClass().getMethod("computeWorldLocation", float.class, float.class);

            Method getWidgetPanel = ui.getClass().getMethod("getWidgetPanel");
            Object wp = getWidgetPanel.invoke(ui);
            if (wp instanceof UIPanelAPI) {
                UIPanelAPI panel = (UIPanelAPI) wp;
                CustomPanelAPI custom = Global.getSettings().createCustom(0f, 0f, this);
                panel.addComponent(custom).inBL(0f, 0f);
                widgetAttached = true;
            } else {
                widgetAttachFailed = true;
            }
        } catch (Throwable t) {
            widgetAttachFailed = true;
        }
    }

    public boolean isWidgetAttached() {
        return widgetAttached;
    }

    @Override
    public void render(float alphaMult) {
        if (!widgetAttached) return;
        renderTacticalOverlay(null);
    }

    /**
     * Fallback entrypoint called from EveryFrameCombatPlugin.renderInUICoords(ViewportAPI)
     * if attaching the 0x0 CustomPanelAPI was not possible.
     */
    public void renderFallbackFromUICoords(ViewportAPI viewport) {
        if (widgetAttached) return;
        renderTacticalOverlay(viewport);
    }

    private void renderTacticalOverlay(ViewportAPI fallbackViewport) {
        if (engine == null || alliedBg == null || alliedBg.divisions.isEmpty()) return;
        if (!DivisionAISettings.isTacticalMapOverlayEnabled()) return;

        CombatUIAPI ui = engine.getCombatUI();
        if (ui == null) return;

        float cmdOpacity = ui.getCommandUIOpacity();
        if (!ui.isShowingCommandUI() && cmdOpacity <= 0.02f) {
            return;
        }
        float fade = Math.max(0.15f, cmdOpacity);

        // Compute linear transform from world coordinates -> tactical map screen pixels:
        //   screenX = worldX * scale + offsetX
        //   screenY = worldY * scale + offsetY
        float scale = 0f;
        float offsetX = 0f;
        float offsetY = 0f;

        if (warroomInstance != null && computeWorldLocMethod != null) {
            try {
                Vector2f w0 = (Vector2f) computeWorldLocMethod.invoke(warroomInstance, 0f, 0f);
                Vector2f w1 = (Vector2f) computeWorldLocMethod.invoke(warroomInstance, 1000f, 1000f);
                if (w0 != null && w1 != null && Math.abs(w1.x - w0.x) > 1e-4f) {
                    scale = 1000f / (w1.x - w0.x);
                    offsetX = -w0.x * scale;
                    offsetY = -w0.y * scale;
                }
            } catch (Throwable ignored) {
            }
        }

        if (scale <= 0f) {
            ViewportAPI vp = fallbackViewport != null ? fallbackViewport : engine.getViewport();
            if (vp == null) return;
            float sx0 = vp.convertWorldXtoScreenX(0f);
            float sy0 = vp.convertWorldYtoScreenY(0f);
            float sx1 = vp.convertWorldXtoScreenX(1000f);
            scale = (sx1 - sx0) / 1000f;
            offsetX = sx0;
            offsetY = sy0;
        }

        float baseCircleAlpha = DivisionAISettings.getDivisionCircleBaseAlpha() * fade;
        float ringAlpha = Math.min(0.55f, baseCircleAlpha * 1.9f);
        float linkAlpha = Math.min(0.45f, baseCircleAlpha * 1.65f);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        // Pass 1: Draw translucent green division circles and flagship-to-member lines
        for (DivisionState div : alliedBg.divisions) {
            List<ShipAPI> alive = div.getAliveMembers();
            if (alive.isEmpty()) continue;

            ShipAPI flagship = resolveActiveFlagship(div, alive);

            // Compute bounding circle in world coordinates that encloses all non-straggling division members
            Vector2f centerWorld = new Vector2f(div.centroid);
            float maxMemberDist = 260f;
            for (ShipAPI member : alive) {
                float d = distance(centerWorld, member.getLocation()) + member.getCollisionRadius() + 140f;
                // Cap extreme straggler radius so one ship 6000u away doesn't make the circle cover the whole map
                float capped = Math.min(d, div.desiredCohesionRadius * 2.15f);
                if (capped > maxMemberDist) {
                    maxMemberDist = capped;
                }
            }

            float cx = centerWorld.x * scale + offsetX;
            float cy = centerWorld.y * scale + offsetY;
            float radiusPx = Math.max(22f, maxMemberDist * scale);

            // 1a. Translucent green filled circle (overlapping circles blend to a less translucent green)
            GL11.glColor4f(0.18f, 0.92f, 0.38f, baseCircleAlpha);
            GL11.glBegin(GL11.GL_TRIANGLE_FAN);
            GL11.glVertex2f(cx, cy);
            for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
                double angle = (2.0 * Math.PI * i) / CIRCLE_SEGMENTS;
                float px = cx + (float) Math.cos(angle) * radiusPx;
                float py = cy + (float) Math.sin(angle) * radiusPx;
                GL11.glVertex2f(px, py);
            }
            GL11.glEnd();

            // 1b. Subtle perimeter ring around the division circle
            GL11.glLineWidth(1.5f);
            GL11.glColor4f(0.32f, 0.98f, 0.50f, ringAlpha);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
                double angle = (2.0 * Math.PI * i) / CIRCLE_SEGMENTS;
                float px = cx + (float) Math.cos(angle) * radiusPx;
                float py = cy + (float) Math.sin(angle) * radiusPx;
                GL11.glVertex2f(px, py);
            }
            GL11.glEnd();

            // 1c. Faint lines projecting from the division flagship towards each constituent member
            if (flagship != null && alive.size() > 1) {
                float fx = flagship.getLocation().x * scale + offsetX;
                float fy = flagship.getLocation().y * scale + offsetY;

                GL11.glLineWidth(1.25f);
                GL11.glColor4f(0.42f, 1.0f, 0.58f, linkAlpha);
                GL11.glBegin(GL11.GL_LINES);
                for (ShipAPI member : alive) {
                    if (member == flagship) continue;
                    float mx = member.getLocation().x * scale + offsetX;
                    float my = member.getLocation().y * scale + offsetY;
                    GL11.glVertex2f(fx, fy);
                    GL11.glVertex2f(mx, my);
                }
                GL11.glEnd();
            }
        }

        GL11.glPopMatrix();
        GL11.glPopAttrib();

        // Pass 2: Render the name of each division directly over its division flagship
        for (DivisionState div : alliedBg.divisions) {
            List<ShipAPI> alive = div.getAliveMembers();
            if (alive.isEmpty()) continue;

            ShipAPI flagship = resolveActiveFlagship(div, alive);
            if (flagship == null) continue;

            float fx = flagship.getLocation().x * scale + offsetX;
            float fy = flagship.getLocation().y * scale + offsetY;
            float shipIconOffsetPx = Math.max(16f, flagship.getCollisionRadius() * scale + 12f);

            LabelAPI label = getOrCreateLabel(div.displayName);
            if (label != null) {
                float textW = label.computeTextWidth(div.displayName);
                float textH = label.computeTextHeight(div.displayName);
                label.getPosition().setSize(textW + 10f, textH + 4f);
                label.getPosition().setLocation(fx - (textW * 0.5f), fy + shipIconOffsetPx + textH);
                label.render(fade * 0.92f);
            }
        }
    }

    private LabelAPI getOrCreateLabel(String text) {
        LabelAPI label = labelCache.get(text);
        if (label == null) {
            label = Global.getSettings().createLabel(text, Fonts.DEFAULT_SMALL);
            label.setColor(LABEL_COLOR);
            labelCache.put(text, label);
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

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
