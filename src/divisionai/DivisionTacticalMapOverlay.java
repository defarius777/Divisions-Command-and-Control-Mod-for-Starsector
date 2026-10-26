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
import java.util.Collections;
import java.util.Comparator;
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
 *  - Division-Entity-Centric Visualization:
 *    1. Fluid Transparent Division Polygon: A smooth, Chaikin-subdivided convex hull enveloping
 *       the division's ships as a single tactical entity rather than individual ship circles.
 *    2. Cohesion-Driven Variable Translucency: Tightly formed divisions render with rich translucency;
 *       divisions in poor cohesion fade smoothly down to nearly fully transparent.
 *    3. Thickly Striped Overlap Zones: When any two division polygons overlap, their exact geometric
 *       intersection (Sutherland-Hodgman convex clip) is filled with thick alternating diagonal stripes.
 *    4. Leading-Edge Effective-Frontage Orientation Bracket: Positioned at the leading edge of the
 *       division polygon toward the division's main objective or survival goal, spanning the full
 *       effective lateral frontage of the division, with the intent vector originating from the
 *       leading-edge bracket center and division labels anchored to the division entity center.
 */
public class DivisionTacticalMapOverlay extends BaseCustomUIPanelPlugin {

    private static final Logger LOG = Global.getLogger(DivisionTacticalMapOverlay.class);
    private static final int HULL_SAMPLE_POINTS_PER_SHIP = 16;
    private static final float ENEMY_OPSEC_MAX_LINE_DIST = 1350f;
    private static final float STRIPE_WIDTH_PX = 9.5f;
    private static final float STRIPE_PERIOD_PX = STRIPE_WIDTH_PX * 2.0f;
    private static final float INV_SQRT2 = 0.70710678f;

    private static final Color PLAYER_LABEL_COLOR = new Color(165, 255, 185, 235);
    private static final Color PLAYER_INTENT_COLOR = new Color(130, 235, 255, 225);
    private static final Color ALLY_LABEL_COLOR = new Color(255, 235, 110, 240);
    private static final Color ENEMY_LABEL_COLOR = new Color(255, 125, 120, 225);

    private static class RenderedDivisionPolygon {
        final DivisionState div;
        final BattleGroupState battleGroup;
        final boolean isEnemy;
        final boolean isAlliedAi;
        final List<ShipAPI> hullMembers;
        final List<ShipAPI> stragglerMembers;
        final List<Vector2f> polyScreen; // CCW smooth convex polygon in screen pixels
        final Vector2f centerScreen;     // Division entity center in screen pixels
        final float minX;
        final float maxX;
        final float minY;
        final float maxY;
        final float cr;
        final float cg;
        final float cb;
        final float cohesionAlphaMult;   // 1.0 = tight cohesion, ~0.08 = very poor cohesion

        RenderedDivisionPolygon(
                DivisionState div,
                BattleGroupState battleGroup,
                boolean isEnemy,
                boolean isAlliedAi,
                List<ShipAPI> hullMembers,
                List<ShipAPI> stragglerMembers,
                List<Vector2f> polyScreen,
                Vector2f centerScreen,
                float cr,
                float cg,
                float cb,
                float cohesionAlphaMult) {
            this.div = div;
            this.battleGroup = battleGroup;
            this.isEnemy = isEnemy;
            this.isAlliedAi = isAlliedAi;
            this.hullMembers = hullMembers;
            this.stragglerMembers = stragglerMembers;
            this.polyScreen = polyScreen;
            this.centerScreen = centerScreen;
            this.cr = cr;
            this.cg = cg;
            this.cb = cb;
            this.cohesionAlphaMult = cohesionAlphaMult;

            float x0 = Float.MAX_VALUE;
            float x1 = -Float.MAX_VALUE;
            float y0 = Float.MAX_VALUE;
            float y1 = -Float.MAX_VALUE;
            for (Vector2f v : polyScreen) {
                if (v.x < x0) x0 = v.x;
                if (v.x > x1) x1 = v.x;
                if (v.y < y0) y0 = v.y;
                if (v.y > y1) y1 = v.y;
            }
            this.minX = x0;
            this.maxX = x1;
            this.minY = y0;
            this.maxY = y1;
        }
    }

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
        float basePolyAlpha = DivisionAISettings.getDivisionCircleBaseAlpha() * fade;
        float friendlyLineAlpha = Math.min(0.88f, Math.max(0.58f, basePolyAlpha * 2.85f)) * fade;

        FogOfWarAPI playerFog = engine.getFogOfWar(0);

        List<RenderedDivisionPolygon> renderedPolys = new ArrayList<>();
        if (playerGroup != null && !playerGroup.divisions.isEmpty()) {
            collectDivisionPolygons(playerGroup, false, null, scale, offsetX, offsetY, renderedPolys);
        }
        if (alliedAiGroup != null && !alliedAiGroup.divisions.isEmpty()) {
            collectDivisionPolygons(alliedAiGroup, false, null, scale, offsetX, offsetY, renderedPolys);
        }
        if (enemyGroup != null && !enemyGroup.divisions.isEmpty()) {
            collectDivisionPolygons(enemyGroup, true, playerFog, scale, offsetX, offsetY, renderedPolys);
        }

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        // 1. Render fluid division polygons with variable cohesion translucency
        for (RenderedDivisionPolygon rp : renderedPolys) {
            renderSingleDivisionPolygon(rp, basePolyAlpha, scale, offsetX, offsetY);
        }

        // 2. Render thickly striped overlap zones wherever two division polygons intersect
        if (renderedPolys.size() >= 2) {
            renderOverlappingPolygonStripes(renderedPolys, basePolyAlpha);
        }

        // 3. Render leading-edge effective-frontage orientation brackets & intention vectors for friendly divisions
        for (RenderedDivisionPolygon rp : renderedPolys) {
            if (!rp.isEnemy) {
                renderDivisionLeadingEdgeBracketAndIntent(rp, scale, offsetX, offsetY, friendlyLineAlpha, playerFog);
            }
        }

        GL11.glPopMatrix();
        GL11.glPopAttrib();

        // 4. Render division labels anchored to the division entity itself
        for (RenderedDivisionPolygon rp : renderedPolys) {
            renderDivisionEntityLabel(rp, fade);
        }
    }

    private void collectDivisionPolygons(
            BattleGroupState battleGroup,
            boolean isEnemy,
            FogOfWarAPI playerFog,
            float scale,
            float offsetX,
            float offsetY,
            List<RenderedDivisionPolygon> outList) {

        for (DivisionState div : battleGroup.divisions) {
            List<ShipAPI> alive = div.getAliveMembers();
            if (alive.isEmpty()) continue;

            List<ShipAPI> hullMembers = new ArrayList<>(alive.size());
            List<ShipAPI> stragglerMembers = new ArrayList<>();

            if (!isEnemy) {
                for (ShipAPI s : alive) {
                    if (div.detachedStragglerIds.contains(s.getId())) {
                        stragglerMembers.add(s);
                    } else {
                        hullMembers.add(s);
                    }
                }
                if (hullMembers.isEmpty()) {
                    hullMembers.addAll(alive);
                    stragglerMembers.clear();
                }
            } else {
                ShipAPI flag = resolveActiveFlagship(div, alive);
                if (flag == null || !isEnemyShipVisibleOnCommandMap(flag, playerFog)) {
                    continue;
                }
                for (ShipAPI s : alive) {
                    if (isEnemyShipVisibleOnCommandMap(s, playerFog)
                            && distance(flag.getLocation(), s.getLocation()) <= ENEMY_OPSEC_MAX_LINE_DIST) {
                        hullMembers.add(s);
                    }
                }
                if (hullMembers.isEmpty()) continue;
            }

            boolean isAlliedAi = !isEnemy && (battleGroup.isAlliedAi || hullMembers.get(0).isAlly());

            // Cohesion-driven variable translucency:
            // Measure overall dispersion (including rawAllMemberSpreadRadius so stragglers or wide scatter
            // reduce polygon opacity). At ratio <= 0.85 -> 1.0x opacity; at ratio >= 2.15 -> 0.08x (nearly transparent).
            float overallSpread = Math.max(div.spreadRadius, div.rawAllMemberSpreadRadius);
            float overallRatio = overallSpread / Math.max(300f, div.stationKeepingRadius);
            float scatterPenalty = clamp((overallRatio - 0.85f) / 1.30f, 0f, 1f);
            float cohesionAlphaMult = 1.0f - scatterPenalty * 0.92f;
            if (!isEnemy && div.currentIntent == DivisionIntentType.CLOSE_UP) {
                cohesionAlphaMult = Math.min(cohesionAlphaMult, 0.22f);
            }

            float cr;
            float cg;
            float cb;
            if (isEnemy) {
                cr = 0.96f;
                cg = 0.22f;
                cb = 0.22f;
            } else if (isAlliedAi) {
                cr = 1.00f;
                cg = 0.86f;
                cb = 0.18f;
            } else {
                cr = 0.20f;
                cg = 0.94f;
                cb = 0.44f;
            }

            List<Vector2f> polyScreen = buildFluidDivisionPolygonScreen(hullMembers, scale, offsetX, offsetY);
            if (polyScreen.size() < 3) continue;

            Vector2f centerScreen = computePolygonCentroid(polyScreen);
            outList.add(new RenderedDivisionPolygon(
                    div,
                    battleGroup,
                    isEnemy,
                    isAlliedAi,
                    hullMembers,
                    stragglerMembers,
                    polyScreen,
                    centerScreen,
                    cr,
                    cg,
                    cb,
                    cohesionAlphaMult));
        }
    }

    /**
     * Builds a smooth, fluid CCW polygon in screen space around the division's member hulls:
     *  1. Samples HULL_SAMPLE_POINTS_PER_SHIP perimeter points around each member's padded hull radius.
     *  2. Computes the 2D Monotone Chain convex hull of all perimeter points.
     *  3. Applies 2 passes of Chaikin corner-cutting subdivision so the polygon flows smoothly
     *     around the division's formation footprint.
     */
    private List<Vector2f> buildFluidDivisionPolygonScreen(
            List<ShipAPI> members,
            float scale,
            float offsetX,
            float offsetY) {

        List<Vector2f> cloud = new ArrayList<>(members.size() * HULL_SAMPLE_POINTS_PER_SHIP);
        for (ShipAPI m : members) {
            float mx = m.getLocation().x * scale + offsetX;
            float my = m.getLocation().y * scale + offsetY;
            float worldR = getMemberFootprintRadiusWorld(m);
            float rPx = clamp(worldR * scale, 15f, 54f);
            for (int i = 0; i < HULL_SAMPLE_POINTS_PER_SHIP; i++) {
                double ang = (2.0 * Math.PI * i) / HULL_SAMPLE_POINTS_PER_SHIP;
                cloud.add(new Vector2f(
                        mx + (float) Math.cos(ang) * rPx,
                        my + (float) Math.sin(ang) * rPx));
            }
        }

        List<Vector2f> hull = computeConvexHullCCW(cloud);
        if (hull.size() < 3) return hull;

        List<Vector2f> smoothed = chaikinSmoothClosed(hull);
        smoothed = chaikinSmoothClosed(smoothed);
        return smoothed;
    }

    private void renderSingleDivisionPolygon(
            RenderedDivisionPolygon rp,
            float basePolyAlpha,
            float scale,
            float offsetX,
            float offsetY) {

        List<Vector2f> poly = rp.polyScreen;
        if (poly.size() < 3) return;

        float effectiveAlpha = basePolyAlpha * rp.cohesionAlphaMult;
        float centerAlpha = Math.min(0.46f, effectiveAlpha * 1.25f);
        float edgeAlpha = Math.min(0.36f, effectiveAlpha * 0.82f);
        float borderAlpha = Math.min(0.75f, effectiveAlpha * 2.10f);

        // 1. Fluid translucent polygon fill
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(rp.cr, rp.cg, rp.cb, centerAlpha);
        GL11.glVertex2f(rp.centerScreen.x, rp.centerScreen.y);
        GL11.glColor4f(rp.cr, rp.cg, rp.cb, edgeAlpha);
        for (int i = 0; i < poly.size(); i++) {
            Vector2f v = poly.get(i);
            GL11.glVertex2f(v.x, v.y);
        }
        Vector2f first = poly.get(0);
        GL11.glVertex2f(first.x, first.y);
        GL11.glEnd();

        // 2. Soft fluid perimeter contour (fades with cohesionAlphaMult)
        GL11.glLineWidth(rp.isEnemy ? 1.5f : 1.9f);
        GL11.glColor4f(
                Math.min(1f, rp.cr + 0.15f),
                Math.min(1f, rp.cg + 0.15f),
                Math.min(1f, rp.cb + 0.15f),
                borderAlpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (Vector2f v : poly) {
            GL11.glVertex2f(v.x, v.y);
        }
        GL11.glEnd();

        // 3. If a friendly division has detached stragglers, draw a subtle amber marker and dashed tether to the division
        if (!rp.isEnemy && !rp.stragglerMembers.isEmpty()) {
            for (ShipAPI stray : rp.stragglerMembers) {
                float sx = stray.getLocation().x * scale + offsetX;
                float sy = stray.getLocation().y * scale + offsetY;
                float sr = clamp(getMemberFootprintRadiusWorld(stray) * scale * 0.65f, 10f, 28f);

                GL11.glLineWidth(1.5f);
                GL11.glColor4f(1.00f, 0.68f, 0.22f, Math.min(0.65f, basePolyAlpha * 2.2f));
                GL11.glBegin(GL11.GL_LINE_LOOP);
                for (int i = 0; i < 12; i++) {
                    double ang = (2.0 * Math.PI * i) / 12;
                    GL11.glVertex2f(sx + (float) Math.cos(ang) * sr, sy + (float) Math.sin(ang) * sr);
                }
                GL11.glEnd();

                drawDashedSegment(sx, sy, rp.centerScreen.x, rp.centerScreen.y, sr + 4f, 8f, 12f);
            }
        }
    }

    /**
     * Finds every pair of overlapping division polygons, computes their exact convex intersection
     * via Sutherland-Hodgman clipping, and fills the overlap region with thick alternating diagonal stripes.
     */
    private void renderOverlappingPolygonStripes(
            List<RenderedDivisionPolygon> renderedPolys,
            float basePolyAlpha) {

        int n = renderedPolys.size();
        for (int i = 0; i < n; i++) {
            RenderedDivisionPolygon a = renderedPolys.get(i);
            for (int j = i + 1; j < n; j++) {
                RenderedDivisionPolygon b = renderedPolys.get(j);

                // Fast AABB reject
                if (a.maxX < b.minX || b.maxX < a.minX || a.maxY < b.minY || b.maxY < a.minY) {
                    continue;
                }

                List<Vector2f> intersection = clipConvexPolygon(a.polyScreen, b.polyScreen);
                if (intersection.size() < 3 || computePolygonArea(intersection) < 30f) {
                    continue;
                }

                // Project intersection vertices onto 45-degree diagonal normal u = (1/sqrt(2), 1/sqrt(2))
                float minU = Float.MAX_VALUE;
                float maxU = -Float.MAX_VALUE;
                for (Vector2f v : intersection) {
                    float u = (v.x + v.y) * INV_SQRT2;
                    if (u < minU) minU = u;
                    if (u > maxU) maxU = u;
                }

                float pairCohesion = Math.max(0.22f, Math.min(a.cohesionAlphaMult, b.cohesionAlphaMult));
                float stripeAlphaA = Math.min(0.62f, Math.max(0.22f, basePolyAlpha * 2.15f * pairCohesion));
                float stripeAlphaB = Math.min(0.58f, Math.max(0.20f, basePolyAlpha * 1.95f * pairCohesion));

                // When two same-side divisions overlap, alternate between bright primary and a distinct dark/contrasting band
                boolean sameColorFamily = (a.isEnemy == b.isEnemy) && (a.isAlliedAi == b.isAlliedAi);
                float bCr = sameColorFamily ? (a.cr * 0.25f) : b.cr;
                float bCg = sameColorFamily ? Math.min(1f, a.cg * 0.45f + 0.15f) : b.cg;
                float bCb = sameColorFamily ? Math.min(1f, a.cb + 0.35f) : b.cb;

                int firstBand = (int) Math.floor(minU / STRIPE_PERIOD_PX);
                int lastBand = (int) Math.ceil(maxU / STRIPE_PERIOD_PX);

                for (int k = firstBand; k <= lastBand; k++) {
                    float s0 = k * STRIPE_PERIOD_PX;
                    float sMid = s0 + STRIPE_WIDTH_PX;
                    float s1 = s0 + STRIPE_PERIOD_PX;

                    // Band A: [s0, sMid]
                    List<Vector2f> bandA = clipPolygonToDiagonalSlab(intersection, s0, sMid);
                    if (bandA.size() >= 3) {
                        GL11.glColor4f(
                                Math.min(1f, a.cr + 0.12f),
                                Math.min(1f, a.cg + 0.12f),
                                Math.min(1f, a.cb + 0.12f),
                                stripeAlphaA);
                        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
                        for (Vector2f v : bandA) {
                            GL11.glVertex2f(v.x, v.y);
                        }
                        GL11.glEnd();
                    }

                    // Band B: [sMid, s1]
                    List<Vector2f> bandB = clipPolygonToDiagonalSlab(intersection, sMid, s1);
                    if (bandB.size() >= 3) {
                        GL11.glColor4f(bCr, bCg, bCb, stripeAlphaB);
                        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
                        for (Vector2f v : bandB) {
                            GL11.glVertex2f(v.x, v.y);
                        }
                        GL11.glEnd();
                    }
                }

                // Crisp border around the overlapping zone
                GL11.glLineWidth(2.0f);
                GL11.glColor4f(1.0f, 1.0f, 1.0f, stripeAlphaA * 0.85f);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                for (Vector2f v : intersection) {
                    GL11.glVertex2f(v.x, v.y);
                }
                GL11.glEnd();
            }
        }
    }

    /**
     * Renders the orientation bracket at the LEADING EDGE of the division polygon toward what the
     * division considers its main objective or survival goal, wide enough to encompass the division's
     * effective frontage, and draws the tactical intent vector from that leading-edge bracket.
     */
    private void renderDivisionLeadingEdgeBracketAndIntent(
            RenderedDivisionPolygon rp,
            float scale,
            float offsetX,
            float offsetY,
            float lineAlpha,
            FogOfWarAPI playerFog) {

        DivisionState div = rp.div;
        DivisionIntentType intent = div.currentIntent;

        float cx = rp.centerScreen.x;
        float cy = rp.centerScreen.y;

        // 1. Resolve the division's final objective/survival goal (destWorld) and immediate heading goal (headingWorld)
        Vector2f destWorld = resolveDivisionFinalGoalWorld(div, intent, playerFog);
        boolean targetIsEnemy = isDirectEnemyTargetIntent(div, intent, playerFog);

        Vector2f headingWorld = destWorld;
        if (!targetIsEnemy && div.hasRouteWaypoint && div.routeWaypoint != null
                && (div.routeWaypoint.x != 0f || div.routeWaypoint.y != 0f)) {
            headingWorld = div.routeWaypoint;
        }

        // Compute unit forward direction (dirX, dirY) from the division entity center toward its heading goal
        float dirX = 0f;
        float dirY = 1f;
        if (headingWorld != null) {
            float hx = headingWorld.x * scale + offsetX;
            float hy = headingWorld.y * scale + offsetY;
            float dx = hx - cx;
            float dy = hy - cy;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 8f) {
                dirX = dx / len;
                dirY = dy / len;
            } else if (div.targetEnemyDivision != null && isDivisionVisibleOnCommandMap(div.targetEnemyDivision, playerFog)) {
                Vector2f visE = OtcAI.getVisibleCentroidForSide(engine, 0, div.targetEnemyDivision);
                float ex = (visE.x * scale + offsetX) - cx;
                float ey = (visE.y * scale + offsetY) - cy;
                float eLen = (float) Math.sqrt(ex * ex + ey * ey);
                if (eLen > 1f) {
                    dirX = ex / eLen;
                    dirY = ey / eLen;
                }
            } else if (div.flagship != null) {
                double rad = Math.toRadians(div.flagship.getFacing());
                dirX = (float) Math.cos(rad);
                dirY = (float) Math.sin(rad);
            }
        }
        float latX = -dirY;
        float latY = dirX;

        // 2. Measure the division's leading edge along (dirX, dirY) and effective lateral frontage along (latX, latY)
        float maxFwdPx = 14f;
        float minLatPx = -18f;
        float maxLatPx = 18f;
        boolean firstProj = true;

        for (Vector2f v : rp.polyScreen) {
            float rx = v.x - cx;
            float ry = v.y - cy;
            float fwd = rx * dirX + ry * dirY;
            float lat = rx * latX + ry * latY;
            if (firstProj) {
                maxFwdPx = fwd;
                minLatPx = lat;
                maxLatPx = lat;
                firstProj = false;
            } else {
                if (fwd > maxFwdPx) maxFwdPx = fwd;
                if (lat < minLatPx) minLatPx = lat;
                if (lat > maxLatPx) maxLatPx = lat;
            }
        }

        float leadAdvancePx = maxFwdPx + 4f;
        float midLatPx = 0.5f * (minLatPx + maxLatPx);
        float halfFrontagePx = clamp(0.5f * (maxLatPx - minLatPx), 18f, 185f);
        float wingSweepPx = clamp(halfFrontagePx * 0.22f, 6f, 16f);

        // Leading-edge anchor point of the division entity
        float leadCenterX = cx + dirX * leadAdvancePx + latX * midLatPx;
        float leadCenterY = cy + dirY * leadAdvancePx + latY * midLatPx;

        float leftX = leadCenterX - latX * halfFrontagePx;
        float leftY = leadCenterY - latY * halfFrontagePx;
        float rightX = leadCenterX + latX * halfFrontagePx;
        float rightY = leadCenterY + latY * halfFrontagePx;

        // Render Leading-Edge Effective-Frontage Orientation Bracket
        GL11.glLineWidth(2.6f);
        if (rp.isAlliedAi) {
            GL11.glColor4f(1.00f, 0.92f, 0.36f, Math.min(0.92f, lineAlpha * 1.15f));
        } else if (intent == DivisionIntentType.HAUL_OUT_TO_VENT || intent == DivisionIntentType.BREAK_OFF_AND_RETIRE) {
            GL11.glColor4f(0.50f, 0.88f, 1.00f, Math.min(0.92f, lineAlpha * 1.15f));
        } else if (intent == DivisionIntentType.CLOSE_UP) {
            GL11.glColor4f(1.00f, 0.76f, 0.26f, Math.min(0.92f, lineAlpha * 1.15f));
        } else {
            GL11.glColor4f(0.42f, 1.00f, 0.72f, Math.min(0.92f, lineAlpha * 1.15f));
        }

        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glVertex2f(leftX - dirX * wingSweepPx, leftY - dirY * wingSweepPx);
        GL11.glVertex2f(leftX, leftY);
        GL11.glVertex2f(rightX, rightY);
        GL11.glVertex2f(rightX - dirX * wingSweepPx, rightY - dirY * wingSweepPx);
        GL11.glEnd();

        // 3. Tactical Intent Vector & Destination Symbol originating from the Leading-Edge Bracket Center
        if (destWorld == null) return;
        float tx = destWorld.x * scale + offsetX;
        float ty = destWorld.y * scale + offsetY;
        float dx = tx - leadCenterX;
        float dy = ty - leadCenterY;
        float distPx = (float) Math.sqrt(dx * dx + dy * dy);
        if (distPx < 12f) return;

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

        float startOffset = 3f;
        float endOffset = targetIsEnemy ? 14f : 6f;

        boolean drawWideArcDogleg = !targetIsEnemy
                && div.hasRouteWaypoint
                && div.routeWaypoint != null
                && (div.routeWaypoint.x != 0f || div.routeWaypoint.y != 0f);

        if (drawWideArcDogleg) {
            float wx = div.routeWaypoint.x * scale + offsetX;
            float wy = div.routeWaypoint.y * scale + offsetY;
            float leg1Dx = wx - leadCenterX;
            float leg1Dy = wy - leadCenterY;
            float leg1Len = (float) Math.sqrt(leg1Dx * leg1Dx + leg1Dy * leg1Dy);
            float leg2Dx = tx - wx;
            float leg2Dy = ty - wy;
            float leg2Len = (float) Math.sqrt(leg2Dx * leg2Dx + leg2Dy * leg2Dy);

            if (leg1Len > 12f && leg2Len > 12f) {
                float u2x = leg2Dx / leg2Len;
                float u2y = leg2Dy / leg2Len;

                GL11.glLineWidth(2.15f);
                drawDashedSegment(leadCenterX, leadCenterY, wx, wy, startOffset, 0f, 14f);
                drawDashedSegment(wx, wy, tx, ty, 0f, endOffset, 14f);

                float wr = 4.5f;
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(wx, wy + wr);
                GL11.glVertex2f(wx + wr, wy);
                GL11.glVertex2f(wx, wy - wr);
                GL11.glVertex2f(wx - wr, wy);
                GL11.glEnd();

                ux = u2x;
                uy = u2y;
                px = -uy;
                py = ux;
            } else {
                drawWideArcDogleg = false;
            }
        }

        if (!drawWideArcDogleg) {
            GL11.glLineWidth(2.15f);
            drawDashedSegment(leadCenterX, leadCenterY, tx, ty, startOffset, endOffset, 14f);
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

    private Vector2f resolveDivisionFinalGoalWorld(
            DivisionState div,
            DivisionIntentType intent,
            FogOfWarAPI playerFog) {
        if (isDirectEnemyTargetIntent(div, intent, playerFog)) {
            ShipAPI enemyFlag = div.targetEnemyDivision.flagship;
            if (enemyFlag != null && isEnemyShipVisibleOnCommandMap(enemyFlag, playerFog)) {
                return enemyFlag.getLocation();
            }
            return OtcAI.getVisibleCentroidForSide(engine, 0, div.targetEnemyDivision);
        }
        if (div.stationPoint != null && (div.stationPoint.x != 0f || div.stationPoint.y != 0f)) {
            return div.stationPoint;
        }
        if (div.targetEnemyDivision != null && isDivisionVisibleOnCommandMap(div.targetEnemyDivision, playerFog)) {
            return OtcAI.getVisibleCentroidForSide(engine, 0, div.targetEnemyDivision);
        }
        return null;
    }

    private static boolean isDirectEnemyTargetIntent(
            DivisionState div,
            DivisionIntentType intent,
            FogOfWarAPI playerFog) {
        if (div.targetEnemyDivision == null) return false;
        if (intent != DivisionIntentType.PRESS_HOME
                && intent != DivisionIntentType.FORM_LINE_AND_FIX
                && intent != DivisionIntentType.TOT_MISSILE_STRIKE
                && intent != DivisionIntentType.LAUNCH_DECKLOAD_STRIKE
                && intent != DivisionIntentType.LAUNCH_ALPHA_STRIKE
                && intent != DivisionIntentType.HARASSING_MISSILE_ATTACK
                && intent != DivisionIntentType.GIVE_GENERAL_CHASE) {
            return false;
        }
        return isDivisionVisibleOnCommandMap(div.targetEnemyDivision, playerFog);
    }

    private static boolean isDivisionVisibleOnCommandMap(DivisionState enemyDiv, FogOfWarAPI playerFog) {
        if (enemyDiv == null) return false;
        for (ShipAPI s : enemyDiv.getAliveMembers()) {
            if (isEnemyShipVisibleOnCommandMap(s, playerFog)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders the division callsign and [INTENT | FLUX] sub-badge centered on the division entity itself.
     */
    private void renderDivisionEntityLabel(RenderedDivisionPolygon rp, float fade) {
        DivisionState div = rp.div;
        float cx = rp.centerScreen.x;
        float cy = rp.centerScreen.y;

        DivisionNomenclature.NomenclatureCategory nomCat =
                div.nomenclatureCategory != null
                        ? div.nomenclatureCategory
                        : rp.battleGroup.nomenclatureCategory;
        String liveCallsign = DivisionNomenclature.resolveDynamicDivisionDisplayName(
                nomCat,
                div.archetype,
                div.divisionNumber,
                div.displayName);

        String cacheKeyOverride = (nomCat == DivisionNomenclature.NomenclatureCategory.THREAT_SWARM)
                ? ("THREAT_DIV:" + div.id)
                : null;
        LabelAPI label = getOrCreateLabel(liveCallsign, cacheKeyOverride, rp.isEnemy, rp.isAlliedAi, false);
        if (label != null) {
            float textW = label.computeTextWidth(liveCallsign);
            float textH = label.computeTextHeight(liveCallsign);
            label.getPosition().setSize(textW + 10f, textH + 4f);
            label.getPosition().setLocation(cx - (textW * 0.5f), cy + (rp.isEnemy ? (textH * 0.5f) : (textH + 4f)));
            label.render(fade * (rp.isEnemy ? 0.84f : 0.95f));
        }

        if (!rp.isEnemy) {
            String intentTag = (nomCat == DivisionNomenclature.NomenclatureCategory.ABYSSAL_ERROR)
                    ? "ERROR"
                    : buildCompactIntentTag(div);
            LabelAPI subLabel = getOrCreateLabel(intentTag, null, false, rp.isAlliedAi, true);
            if (subLabel != null) {
                float subW = subLabel.computeTextWidth(intentTag);
                float subH = subLabel.computeTextHeight(intentTag);
                subLabel.getPosition().setSize(subW + 10f, subH + 4f);
                subLabel.getPosition().setLocation(cx - (subW * 0.5f), cy - 2f);
                subLabel.render(fade * 0.88f);
            }
        }
    }

    // =========================================================================
    // 2D Convex Hull, Chaikin Smoothing & Sutherland-Hodgman Clipping Helpers
    // =========================================================================

    public static List<Vector2f> computeConvexHullCCW(List<Vector2f> pts) {
        if (pts == null || pts.size() <= 3) {
            return pts != null ? new ArrayList<>(pts) : Collections.emptyList();
        }
        List<Vector2f> sorted = new ArrayList<>(pts);
        Collections.sort(sorted, new Comparator<Vector2f>() {
            @Override
            public int compare(Vector2f a, Vector2f b) {
                int cmp = Float.compare(a.x, b.x);
                return (cmp != 0) ? cmp : Float.compare(a.y, b.y);
            }
        });

        List<Vector2f> lower = new ArrayList<>(sorted.size());
        for (Vector2f p : sorted) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), p) <= 0f) {
                lower.remove(lower.size() - 1);
            }
            lower.add(p);
        }

        List<Vector2f> upper = new ArrayList<>(sorted.size());
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Vector2f p = sorted.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= 0f) {
                upper.remove(upper.size() - 1);
            }
            upper.add(p);
        }

        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        return lower;
    }

    private static float cross(Vector2f o, Vector2f a, Vector2f b) {
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
    }

    public static List<Vector2f> chaikinSmoothClosed(List<Vector2f> poly) {
        int n = poly.size();
        if (n < 3) return poly;
        List<Vector2f> out = new ArrayList<>(n * 2);
        for (int i = 0; i < n; i++) {
            Vector2f p0 = poly.get(i);
            Vector2f p1 = poly.get((i + 1) % n);
            float dx = p1.x - p0.x;
            float dy = p1.y - p0.y;
            if ((dx * dx + dy * dy) < 9f) {
                out.add(new Vector2f(p0));
                continue;
            }
            out.add(new Vector2f(0.75f * p0.x + 0.25f * p1.x, 0.75f * p0.y + 0.25f * p1.y));
            out.add(new Vector2f(0.25f * p0.x + 0.75f * p1.x, 0.25f * p0.y + 0.75f * p1.y));
        }
        return out;
    }

    public static Vector2f computePolygonCentroid(List<Vector2f> poly) {
        if (poly == null || poly.isEmpty()) return new Vector2f(0f, 0f);
        float cx = 0f;
        float cy = 0f;
        float signedArea = 0f;
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            Vector2f p0 = poly.get(i);
            Vector2f p1 = poly.get((i + 1) % n);
            float a = p0.x * p1.y - p1.x * p0.y;
            signedArea += a;
            cx += (p0.x + p1.x) * a;
            cy += (p0.y + p1.y) * a;
        }
        if (Math.abs(signedArea) < 1e-3f) {
            float sx = 0f;
            float sy = 0f;
            for (Vector2f p : poly) {
                sx += p.x;
                sy += p.y;
            }
            return new Vector2f(sx / n, sy / n);
        }
        signedArea *= 0.5f;
        return new Vector2f(cx / (6f * signedArea), cy / (6f * signedArea));
    }

    public static float computePolygonArea(List<Vector2f> poly) {
        if (poly == null || poly.size() < 3) return 0f;
        float sum = 0f;
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            Vector2f a = poly.get(i);
            Vector2f b = poly.get((i + 1) % n);
            sum += (a.x * b.y - b.x * a.y);
        }
        return Math.abs(sum) * 0.5f;
    }

    /**
     * Sutherland-Hodgman clipping of convex subjectPoly against convex clipPoly (both CCW).
     */
    public static List<Vector2f> clipConvexPolygon(List<Vector2f> subjectPoly, List<Vector2f> clipPoly) {
        List<Vector2f> output = new ArrayList<>(subjectPoly);
        int clipLen = clipPoly.size();
        for (int i = 0; i < clipLen; i++) {
            if (output.isEmpty()) break;
            Vector2f c1 = clipPoly.get(i);
            Vector2f c2 = clipPoly.get((i + 1) % clipLen);
            float edgeDx = c2.x - c1.x;
            float edgeDy = c2.y - c1.y;

            List<Vector2f> input = output;
            output = new ArrayList<>(input.size() + 2);
            int inLen = input.size();
            for (int j = 0; j < inLen; j++) {
                Vector2f s = input.get(j);
                Vector2f e = input.get((j + 1) % inLen);
                float sCross = edgeDx * (s.y - c1.y) - edgeDy * (s.x - c1.x);
                float eCross = edgeDx * (e.y - c1.y) - edgeDy * (e.x - c1.x);
                boolean sInside = sCross >= -1e-3f;
                boolean eInside = eCross >= -1e-3f;

                if (eInside) {
                    if (!sInside) {
                        output.add(intersectEdge(s, e, sCross, eCross));
                    }
                    output.add(e);
                } else if (sInside) {
                    output.add(intersectEdge(s, e, sCross, eCross));
                }
            }
        }
        return output;
    }

    private static Vector2f intersectEdge(Vector2f s, Vector2f e, float sVal, float eVal) {
        float denom = sVal - eVal;
        float t = (Math.abs(denom) > 1e-6f) ? (sVal / denom) : 0.5f;
        t = clamp(t, 0f, 1f);
        return new Vector2f(s.x + (e.x - s.x) * t, s.y + (e.y - s.y) * t);
    }

    /**
     * Clips a convex polygon against the 45-degree diagonal slab minU <= (x + y)/sqrt(2) <= maxU.
     */
    public static List<Vector2f> clipPolygonToDiagonalSlab(List<Vector2f> poly, float minU, float maxU) {
        List<Vector2f> step1 = clipPolygonHalfPlaneDiag(poly, minU, true);
        if (step1.size() < 3) return Collections.emptyList();
        return clipPolygonHalfPlaneDiag(step1, maxU, false);
    }

    private static List<Vector2f> clipPolygonHalfPlaneDiag(List<Vector2f> poly, float boundaryU, boolean keepGreater) {
        int n = poly.size();
        if (n == 0) return Collections.emptyList();
        List<Vector2f> out = new ArrayList<>(n + 2);
        for (int i = 0; i < n; i++) {
            Vector2f s = poly.get(i);
            Vector2f e = poly.get((i + 1) % n);
            float sU = (s.x + s.y) * INV_SQRT2 - boundaryU;
            float eU = (e.x + e.y) * INV_SQRT2 - boundaryU;
            if (!keepGreater) {
                sU = -sU;
                eU = -eU;
            }
            boolean sIn = sU >= -1e-4f;
            boolean eIn = eU >= -1e-4f;
            if (eIn) {
                if (!sIn) {
                    out.add(intersectEdge(s, e, sU, eU));
                }
                out.add(e);
            } else if (sIn) {
                out.add(intersectEdge(s, e, sU, eU));
            }
        }
        return out;
    }

    private static void drawDashedSegment(
            float x0,
            float y0,
            float x1,
            float y1,
            float startOffset,
            float endOffset,
            float dashPitch) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float usable = len - startOffset - endOffset;
        if (usable <= 6f) return;
        float ux = dx / len;
        float uy = dy / len;
        int dashes = Math.max(2, Math.min(20, (int) (usable / dashPitch)));
        float step = usable / dashes;
        GL11.glBegin(GL11.GL_LINES);
        for (int i = 0; i < dashes; i++) {
            float s0 = startOffset + i * step;
            float s1 = startOffset + (i + 0.65f) * step;
            GL11.glVertex2f(x0 + ux * s0, y0 + uy * s0);
            GL11.glVertex2f(x0 + ux * s1, y0 + uy * s1);
        }
        GL11.glEnd();
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
