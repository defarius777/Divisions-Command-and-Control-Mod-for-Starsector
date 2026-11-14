package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.FogOfWarAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private static final int HULL_SAMPLE_POINTS_PER_SHIP = 48;
    private static final float STRIPE_WIDTH_PX = 12.0f;
    private static final float STRIPE_PERIOD_PX = STRIPE_WIDTH_PX * 2.0f;
    private static final float INV_SQRT2 = 0.70710678f;

    private static final Color PLAYER_LABEL_COLOR = new Color(172, 255, 128, 240);
    private static final Color PLAYER_INTENT_COLOR = new Color(145, 232, 255, 228);
    private static final Color ALLY_LABEL_COLOR = new Color(255, 232, 118, 240);
    private static final Color ENEMY_LABEL_COLOR = new Color(255, 122, 98, 232);
    private static final Color PLAYER_STRIKE_LABEL_COLOR = new Color(135, 224, 255, 238);
    private static final Color ALLY_STRIKE_LABEL_COLOR = new Color(255, 238, 152, 238);
    private static final Color ENEMY_STRIKE_LABEL_COLOR = new Color(255, 162, 92, 234);

    public static class SquadronEnvelopeUnit {
        final com.fs.starfarer.api.combat.FighterWingAPI wing;
        final ShipAPI fallbackShip;
        final Vector2f worldCenter;
        final float worldRadius;
        final int voidstrikeNumber;

        SquadronEnvelopeUnit(
                com.fs.starfarer.api.combat.FighterWingAPI wing,
                ShipAPI fallbackShip,
                Vector2f worldCenter,
                float worldRadius) {
            this(wing, fallbackShip, worldCenter, worldRadius, 0);
        }

        SquadronEnvelopeUnit(
                com.fs.starfarer.api.combat.FighterWingAPI wing,
                ShipAPI fallbackShip,
                Vector2f worldCenter,
                float worldRadius,
                int voidstrikeNumber) {
            this.wing = wing;
            this.fallbackShip = fallbackShip;
            this.worldCenter = new Vector2f(worldCenter.x, worldCenter.y);
            this.worldRadius = worldRadius;
            this.voidstrikeNumber = voidstrikeNumber;
        }

        Vector2f resolveLiveWorldCenter(Vector2f scratch) {
            if (wing != null) {
                ShipAPI lead = BattlefieldFirepowerEvaluator.resolveAliveWingLeader(wing);
                if (lead != null) {
                    return BattlefieldFirepowerEvaluator.resolveSquadronLiveCentroid(wing, lead, scratch);
                }
            }
            if (fallbackShip != null && fallbackShip.isAlive() && !fallbackShip.isHulk() && fallbackShip.getLocation() != null) {
                scratch.set(fallbackShip.getLocation().x, fallbackShip.getLocation().y);
                return scratch;
            }
            return null;
        }
    }

    public static class RenderedDivisionPolygon {
        final DivisionState div;
        final BattleGroupState battleGroup;
        final boolean isEnemy;
        final boolean isAlliedAi;
        final boolean isPrimaryDroplet;
        final boolean isStrikeCraftPolygon;
        final List<ShipAPI> hullMembers;
        final List<SquadronEnvelopeUnit> squadronUnits;
        final List<Vector2f> polyScreen;        // CCW contour in screen pixels (for clipping/bounds)
        public final List<Vector2f> displayPolyScreen; // Smooth metaball contour in screen pixels (for rendering)
        public final List<Vector2f> innerGlowPolyScreen; // Curvature-clamped inward gradient ring in screen pixels
        final List<int[]> triangles;            // Precomputed ear-clipping vertex index triples (invariant under pan/zoom)
        public final Vector2f centerScreen;     // Droplet entity center in screen pixels
        float labelX;                           // De-conflicted label X in screen pixels
        float labelY;                           // De-conflicted label Y in screen pixels
        float lastWorldCenterX;
        float lastWorldCenterY;
        float minX;
        float maxX;
        float minY;
        float maxY;
        List<Vector2f> clipHullScreen;
        final float cr;
        final float cg;
        final float cb;
        final float cohesionAlphaMult;          // 1.0 = tight cohesion, ~0.05 = very poor cohesion
        final int voidstrikeNumber;             // > 0 when representing an active Voidstrike air package

        // Per-vertex 60 FPS multi-unit Linear Blend Skinning (LBS) anchors & weights
        int skinUnitCount;
        float[] skinLastWorldX;
        float[] skinLastWorldY;
        float[] skinDeltaPxX;
        float[] skinDeltaPxY;
        float[] skinWeights;
        float skinInitialSpreadWorld;

        RenderedDivisionPolygon(
                DivisionState div,
                BattleGroupState battleGroup,
                boolean isEnemy,
                boolean isAlliedAi,
                boolean isPrimaryDroplet,
                List<ShipAPI> hullMembers,
                List<Vector2f> polyScreen,
                List<Vector2f> displayPolyScreen,
                Vector2f centerScreen,
                float cr,
                float cg,
                float cb,
                float cohesionAlphaMult) {
            this(div, battleGroup, isEnemy, isAlliedAi, isPrimaryDroplet, false,
                    hullMembers, null, polyScreen, displayPolyScreen, centerScreen,
                    cr, cg, cb, cohesionAlphaMult, 1.0f, Float.NaN, Float.NaN, 0);
        }

        RenderedDivisionPolygon(
                DivisionState div,
                BattleGroupState battleGroup,
                boolean isEnemy,
                boolean isAlliedAi,
                boolean isPrimaryDroplet,
                boolean isStrikeCraftPolygon,
                List<ShipAPI> hullMembers,
                List<Vector2f> polyScreen,
                List<Vector2f> displayPolyScreen,
                Vector2f centerScreen,
                float cr,
                float cg,
                float cb,
                float cohesionAlphaMult) {
            this(div, battleGroup, isEnemy, isAlliedAi, isPrimaryDroplet, isStrikeCraftPolygon,
                    hullMembers, null, polyScreen, displayPolyScreen, centerScreen,
                    cr, cg, cb, cohesionAlphaMult, 1.0f, Float.NaN, Float.NaN, 0);
        }

        RenderedDivisionPolygon(
                DivisionState div,
                BattleGroupState battleGroup,
                boolean isEnemy,
                boolean isAlliedAi,
                boolean isPrimaryDroplet,
                boolean isStrikeCraftPolygon,
                List<ShipAPI> hullMembers,
                List<SquadronEnvelopeUnit> squadronUnits,
                List<Vector2f> polyScreen,
                List<Vector2f> displayPolyScreen,
                Vector2f centerScreen,
                float cr,
                float cg,
                float cb,
                float cohesionAlphaMult,
                float buildScale) {
            this(div, battleGroup, isEnemy, isAlliedAi, isPrimaryDroplet, isStrikeCraftPolygon,
                    hullMembers, squadronUnits, polyScreen, displayPolyScreen, centerScreen,
                    cr, cg, cb, cohesionAlphaMult, buildScale, Float.NaN, Float.NaN, 0);
        }

        RenderedDivisionPolygon(
                DivisionState div,
                BattleGroupState battleGroup,
                boolean isEnemy,
                boolean isAlliedAi,
                boolean isPrimaryDroplet,
                boolean isStrikeCraftPolygon,
                List<ShipAPI> hullMembers,
                List<SquadronEnvelopeUnit> squadronUnits,
                List<Vector2f> polyScreen,
                List<Vector2f> displayPolyScreen,
                Vector2f centerScreen,
                float cr,
                float cg,
                float cb,
                float cohesionAlphaMult,
                float buildScale,
                float buildOffsetX,
                float buildOffsetY,
                int voidstrikeNumber) {
            this.div = div;
            this.battleGroup = battleGroup;
            this.isEnemy = isEnemy;
            this.isAlliedAi = isAlliedAi;
            this.isPrimaryDroplet = isPrimaryDroplet;
            this.isStrikeCraftPolygon = isStrikeCraftPolygon;
            this.hullMembers = hullMembers;
            this.squadronUnits = squadronUnits;
            this.polyScreen = polyScreen;
            this.displayPolyScreen = displayPolyScreen != null ? displayPolyScreen : polyScreen;
            this.triangles = triangulatePolygonCCW(this.displayPolyScreen);
            float targetGlowPx = isStrikeCraftPolygon
                    ? clamp(150f * Math.max(0.05f, buildScale), 8.0f, 18.0f)
                    : clamp(245f * Math.max(0.05f, buildScale), 12.0f, 26.0f);
            this.innerGlowPolyScreen = computeSafeInwardGradientRing(this.displayPolyScreen, targetGlowPx);
            this.centerScreen = centerScreen;
            this.labelX = centerScreen != null ? centerScreen.x : 0f;
            this.labelY = centerScreen != null ? centerScreen.y : 0f;
            this.cr = cr;
            this.cg = cg;
            this.cb = cb;
            this.cohesionAlphaMult = cohesionAlphaMult;
            this.voidstrikeNumber = voidstrikeNumber;
            initSkinningState(buildScale, buildOffsetX, buildOffsetY);
            recomputeBounds();
        }

        private void initSkinningState(float buildScale, float buildOffsetX, float buildOffsetY) {
            float effScale = (buildScale > 1e-5f) ? buildScale : 0.08f;
            boolean hasExactOffset = !Float.isNaN(buildOffsetX) && !Float.isNaN(buildOffsetY);
            if (squadronUnits != null && !squadronUnits.isEmpty()) {
                int m = squadronUnits.size();
                this.skinUnitCount = m;
                this.skinLastWorldX = new float[m];
                this.skinLastWorldY = new float[m];
                float wx = 0f, wy = 0f;
                for (int i = 0; i < m; i++) {
                    SquadronEnvelopeUnit u = squadronUnits.get(i);
                    this.skinLastWorldX[i] = u.worldCenter.x;
                    this.skinLastWorldY[i] = u.worldCenter.y;
                    wx += u.worldCenter.x;
                    wy += u.worldCenter.y;
                }
                this.lastWorldCenterX = wx / m;
                this.lastWorldCenterY = wy / m;
                if (m >= 2) {
                    float sumSq = 0f;
                    for (int i = 0; i < m; i++) {
                        float dx = this.skinLastWorldX[i] - this.lastWorldCenterX;
                        float dy = this.skinLastWorldY[i] - this.lastWorldCenterY;
                        sumSq += dx * dx + dy * dy;
                    }
                    this.skinInitialSpreadWorld = (float) Math.sqrt(sumSq / m);
                }
                if (m >= 2 && displayPolyScreen != null && !displayPolyScreen.isEmpty()) {
                    this.skinDeltaPxX = new float[m];
                    this.skinDeltaPxY = new float[m];
                    int vCount = displayPolyScreen.size();
                    this.skinWeights = new float[vCount * m];
                    float cxScr = centerScreen != null ? centerScreen.x : 0f;
                    float cyScr = centerScreen != null ? centerScreen.y : 0f;
                    for (int v = 0; v < vCount; v++) {
                        Vector2f pv = displayPolyScreen.get(v);
                        float vWx = hasExactOffset
                                ? ((pv.x - buildOffsetX) / effScale)
                                : (lastWorldCenterX + (pv.x - cxScr) / effScale);
                        float vWy = hasExactOffset
                                ? ((pv.y - buildOffsetY) / effScale)
                                : (lastWorldCenterY + (pv.y - cyScr) / effScale);
                        float wSum = 0f;
                        int base = v * m;
                        for (int i = 0; i < m; i++) {
                            SquadronEnvelopeUnit u = squadronUnits.get(i);
                            float dx = vWx - u.worldCenter.x;
                            float dy = vWy - u.worldCenter.y;
                            float dist = (float) Math.sqrt(dx * dx + dy * dy);
                            float surfDist = Math.max(0f, dist - u.worldRadius * 0.55f) + u.worldRadius * 0.35f + 40f;
                            float w = 1.0f / (surfDist * surfDist);
                            this.skinWeights[base + i] = w;
                            wSum += w;
                        }
                        float invSum = (wSum > 1e-8f) ? (1.0f / wSum) : (1.0f / m);
                        for (int i = 0; i < m; i++) {
                            this.skinWeights[base + i] *= invSum;
                        }
                    }
                }
                return;
            }

            if (hullMembers != null && !hullMembers.isEmpty()) {
                int m = hullMembers.size();
                this.skinUnitCount = m;
                this.skinLastWorldX = new float[m];
                this.skinLastWorldY = new float[m];
                float wx = 0f, wy = 0f;
                int cnt = 0;
                for (int i = 0; i < m; i++) {
                    ShipAPI s = hullMembers.get(i);
                    if (s != null && s.getLocation() != null) {
                        this.skinLastWorldX[i] = s.getLocation().x;
                        this.skinLastWorldY[i] = s.getLocation().y;
                        wx += s.getLocation().x;
                        wy += s.getLocation().y;
                        cnt++;
                    }
                }
                if (cnt > 0) {
                    this.lastWorldCenterX = wx / cnt;
                    this.lastWorldCenterY = wy / cnt;
                }
                if (cnt >= 2) {
                    float sumSq = 0f;
                    for (int i = 0; i < m; i++) {
                        ShipAPI s = hullMembers.get(i);
                        if (s == null || s.getLocation() == null) continue;
                        float dx = this.skinLastWorldX[i] - this.lastWorldCenterX;
                        float dy = this.skinLastWorldY[i] - this.lastWorldCenterY;
                        sumSq += dx * dx + dy * dy;
                    }
                    this.skinInitialSpreadWorld = (float) Math.sqrt(sumSq / cnt);
                }
                if (m >= 2 && cnt >= 2 && displayPolyScreen != null && !displayPolyScreen.isEmpty()) {
                    this.skinDeltaPxX = new float[m];
                    this.skinDeltaPxY = new float[m];
                    int vCount = displayPolyScreen.size();
                    this.skinWeights = new float[vCount * m];
                    float cxScr = centerScreen != null ? centerScreen.x : 0f;
                    float cyScr = centerScreen != null ? centerScreen.y : 0f;
                    for (int v = 0; v < vCount; v++) {
                        Vector2f pv = displayPolyScreen.get(v);
                        float vWx = hasExactOffset
                                ? ((pv.x - buildOffsetX) / effScale)
                                : (lastWorldCenterX + (pv.x - cxScr) / effScale);
                        float vWy = hasExactOffset
                                ? ((pv.y - buildOffsetY) / effScale)
                                : (lastWorldCenterY + (pv.y - cyScr) / effScale);
                        float wSum = 0f;
                        int base = v * m;
                        for (int i = 0; i < m; i++) {
                            ShipAPI s = hullMembers.get(i);
                            if (s == null || s.getLocation() == null) {
                                this.skinWeights[base + i] = 0f;
                                continue;
                            }
                            float rW = getMemberFootprintRadiusWorld(s);
                            float dx = vWx - this.skinLastWorldX[i];
                            float dy = vWy - this.skinLastWorldY[i];
                            float dist = (float) Math.sqrt(dx * dx + dy * dy);
                            float surfDist = Math.max(0f, dist - rW * 0.55f) + rW * 0.35f + 45f;
                            float w = 1.0f / (surfDist * surfDist);
                            this.skinWeights[base + i] = w;
                            wSum += w;
                        }
                        float invSum = (wSum > 1e-8f) ? (1.0f / wSum) : (1.0f / Math.max(1, cnt));
                        for (int i = 0; i < m; i++) {
                            this.skinWeights[base + i] *= invSum;
                        }
                    }
                }
            }
        }

        void recomputeBounds() {
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

    private static class CachedOverlapStripeBand {
        final List<Vector2f> verts;
        final float r;
        final float g;
        final float b;
        final float alphaScale;
        final boolean isBandA;
        final RenderedDivisionPolygon polyA;
        final RenderedDivisionPolygon polyB;
        float anchorMidX;
        float anchorMidY;

        CachedOverlapStripeBand(
                List<Vector2f> verts,
                float r,
                float g,
                float b,
                float alphaScale,
                boolean isBandA,
                RenderedDivisionPolygon polyA,
                RenderedDivisionPolygon polyB) {
            List<Vector2f> copy = new ArrayList<>(verts.size());
            for (int i = 0, n = verts.size(); i < n; i++) {
                Vector2f v = verts.get(i);
                copy.add(new Vector2f(v.x, v.y));
            }
            this.verts = copy;
            this.r = r;
            this.g = g;
            this.b = b;
            this.alphaScale = alphaScale;
            this.isBandA = isBandA;
            this.polyA = polyA;
            this.polyB = polyB;
            if (polyA != null && polyB != null && polyA.centerScreen != null && polyB.centerScreen != null) {
                this.anchorMidX = 0.5f * (polyA.centerScreen.x + polyB.centerScreen.x);
                this.anchorMidY = 0.5f * (polyA.centerScreen.y + polyB.centerScreen.y);
            }
        }
    }

    private CombatEngineAPI engine;
    private BattleGroupState playerGroup;
    private BattleGroupState alliedAiGroup;
    private BattleGroupState enemyGroup;
    private final Map<String, LabelAPI> labelCache = new HashMap<>();
    private final Map<String, String> labelLastTextCache = new HashMap<>();
    private final Map<String, Float> labelWidthCache = new HashMap<>();
    private final Map<String, Float> labelHeightCache = new HashMap<>();
    private final Map<String, Float> smoothedCohesionByDivId = new HashMap<>();
    private final Map<String, Set<String>> stickyConnectedPairsByDivId = new HashMap<>();
    private final Map<String, Float> smoothedBondOpennessByPairKey = new HashMap<>();
    private final Map<String, Float> smoothedMeniscusReachByPairKey = new HashMap<>();

    private static class CachedDivisionGeometry {
        int signature;
        int positionSignature;
        float builtAtSimTime;
        float lastTranslatedSimTime;
        float scale;
        float offsetX;
        float offsetY;
        final List<RenderedDivisionPolygon> droplets = new ArrayList<>();
    }

    public static final class WarroomPerfSnapshot {
        public final int frames;
        public final int rebuildFrames;
        public final int divisionsRebuilt;
        public final int polyCount;
        public final int overlapBandCount;
        public final float avgTotalMs;
        public final float p95TotalMs;
        public final float maxTotalMs;
        public final float avgGeomMs;
        public final float maxGeomMs;
        public final float avgPolyMs;
        public final float avgStarsMs;
        public final float avgTsfcMs;
        public final float avgLabelsMs;
        public final float avgUiMs;

        public WarroomPerfSnapshot(
                int frames,
                int rebuildFrames,
                int divisionsRebuilt,
                int polyCount,
                int overlapBandCount,
                float avgTotalMs,
                float p95TotalMs,
                float maxTotalMs,
                float avgGeomMs,
                float maxGeomMs,
                float avgPolyMs,
                float avgStarsMs,
                float avgTsfcMs,
                float avgLabelsMs,
                float avgUiMs) {
            this.frames = frames;
            this.rebuildFrames = rebuildFrames;
            this.divisionsRebuilt = divisionsRebuilt;
            this.polyCount = polyCount;
            this.overlapBandCount = overlapBandCount;
            this.avgTotalMs = avgTotalMs;
            this.p95TotalMs = p95TotalMs;
            this.maxTotalMs = maxTotalMs;
            this.avgGeomMs = avgGeomMs;
            this.maxGeomMs = maxGeomMs;
            this.avgPolyMs = avgPolyMs;
            this.avgStarsMs = avgStarsMs;
            this.avgTsfcMs = avgTsfcMs;
            this.avgLabelsMs = avgLabelsMs;
            this.avgUiMs = avgUiMs;
        }
    }

    // Warroom render-frame geometry cache (eliminates all Marching-Squares / Ear-Clipping / Overlap-Stripe work
    // on paused frames, camera pans/zooms, and caps live combat recomputation per-division while preserving full render smoothness)
    private final List<RenderedDivisionPolygon> cachedRenderedPolys = new ArrayList<>();
    private final List<RenderedDivisionPolygon> cachedStrikeCraftPolys = new ArrayList<>();
    private final List<CachedOverlapStripeBand> cachedOverlapStripeBands = new ArrayList<>();
    private final Map<String, CachedDivisionGeometry> perDivisionGeometryCache = new HashMap<>();
    private final Map<String, CachedDivisionGeometry> perDivisionStrikeCraftCache = new HashMap<>();
    private Set<ShipAPI> cachedFleetFlagships = Collections.emptySet();
    private Set<ShipAPI> cachedDivisionFlagships = Collections.emptySet();
    private SpriteAPI cachedGoldStarSprite = null;
    private SpriteAPI cachedSilverStarSprite = null;
    private CombatEngineAPI cachedPolyEngine = null;
    private BattleGroupState cachedPolyPlayerGroup = null;
    private BattleGroupState cachedPolyAlliedGroup = null;
    private BattleGroupState cachedPolyEnemyGroup = null;
    private boolean cachedPolyPaused = false;
    private float cachedPolyNow = -1000f;
    private int cachedPolySignature = 0;
    private float lastLiveTranslateSimTime = -1000f;
    private float lastStripeRebuildSimTime = -1000f;
    private float cachedPolyScale = Float.NaN;
    private float cachedPolyOffsetX = Float.NaN;
    private float cachedPolyOffsetY = Float.NaN;

    // Rolling Warroom frame-time telemetry profiler
    private static final int PERF_WINDOW_FRAMES = 120;
    private final float[] perfTotalMsRing = new float[PERF_WINDOW_FRAMES];
    private int perfWindowCount = 0;
    private int perfRebuildFrames = 0;
    private int perfDivisionsRebuilt = 0;
    private float perfSumTotalMs = 0f;
    private float perfMaxTotalMs = 0f;
    private float perfSumGeomMs = 0f;
    private float perfMaxGeomMs = 0f;
    private float perfSumPolyMs = 0f;
    private float perfSumStarsMs = 0f;
    private float perfSumTsfcMs = 0f;
    private float perfSumLabelsMs = 0f;
    private float perfSumUiMs = 0f;
    private long lastPerfLogWallMs = 0L;
    private WarroomPerfSnapshot lastWarroomPerfSnapshot = null;

    public WarroomPerfSnapshot getLastWarroomPerfSnapshot() {
        return lastWarroomPerfSnapshot;
    }

    public void resetWarroomPerfMetrics() {
        perfWindowCount = 0;
        perfRebuildFrames = 0;
        perfDivisionsRebuilt = 0;
        perfSumTotalMs = 0f;
        perfMaxTotalMs = 0f;
        perfSumGeomMs = 0f;
        perfMaxGeomMs = 0f;
        perfSumPolyMs = 0f;
        perfSumStarsMs = 0f;
        perfSumTsfcMs = 0f;
        perfSumLabelsMs = 0f;
        perfSumUiMs = 0f;
        lastWarroomPerfSnapshot = null;
    }

    public void invalidateGeometryCache() {
        clearCachedPolygonsOnMapHidden();
        this.regularCommandSpritesSuppressed = false;
    }

    private void clearCachedPolygonsOnMapHidden() {
        this.cachedPolyNow = -1000f;
        this.cachedPolyPaused = false;
        this.cachedPolySignature = 0;
        this.lastLiveTranslateSimTime = -1000f;
        this.lastStripeRebuildSimTime = -1000f;
        this.perDivisionGeometryCache.clear();
        this.perDivisionStrikeCraftCache.clear();
        this.cachedRenderedPolys.clear();
        this.cachedStrikeCraftPolys.clear();
        this.cachedOverlapStripeBands.clear();
    }

    private boolean attachedToWarroom = false;
    private boolean loggedHookFailure = false;
    private Object warroomInstance = null;
    private Object mapDisplayInstance = null;
    private MethodHandle isShowingVideoFeedHandle = null;
    private MethodHandle getEntityToFollowV2Handle = null;
    private MethodHandle areWeaponArcsOnHandle = null;
    private Object arcRendererInstance = null;
    private MethodHandle arcRendererAnimProgressGetter = null;
    private MethodHandle arcRendererStateGetter = null;
    private MethodHandle arcRendererAnimObjGetter = null;
    private MethodHandle baseAnimGetProgressHandle = null;
    private MethodHandle arcRendererCurrentGroupGetter = null;
    private MethodHandle worldToMapScreenHandle = null;
    private MethodHandle mapGetIconHandle = null;
    private MethodHandle iconRenderSpriteHandle = null;
    private MethodHandle iconRenderBarsHandle = null;
    private MethodHandle iconGetRadiusHandle = null;
    private List<?> warroomFadingArrowsList = null;
    private MethodHandle arrowEntryFaderGetter = null;
    private MethodHandle faderIsFadedOutHandle = null;

    // Handles to disable regular Warroom command sprites (com.fs.starfarer.combat.new.A / com.fs.starfarer.ui.B.new
    // and com.fs.starfarer.combat.new.o0Oo) while TSFC overlays are active so only the mini 1:1 red/green/yellow
    // hull sprites render above TSFC.
    private Class<?> warroomShipIconClass = null;
    private Class<?> warroomFighterIconClass = null;
    private MethodHandle mdIconsMapGetter = null;
    private MethodHandle mdIconSetGetter = null;
    private MethodHandle shipIconBNewGetter = null;
    private MethodHandle shipIconArrowSpriteGetter = null;
    private MethodHandle fighterIconSpriteGetter = null;
    private MethodHandle bNewSpriteGetter = null;
    private MethodHandle bNewHoloSetter = null;
    private MethodHandle bNewModulesSetter = null;
    private MethodHandle bNewStatusSetter = null;
    private MethodHandle spriteTexGetter = null;
    private MethodHandle spriteTexSetter = null;
    private MethodHandle spriteReadResolveHandle = null;
    private final Map<Object, Object> savedIconSpriteTextures = new java.util.IdentityHashMap<>();
    private boolean regularCommandSpritesSuppressed = false;

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
        BattlefieldFirepowerEvaluator.setActiveBattleGroups(playerGroup, alliedAiGroup, enemyGroup);
    }

    /**
     * Attaches a 0x0 CustomPanelAPI directly to the Command Window warroom (OoOO, which implements UIPanelAPI)
     * using java.lang.invoke.MethodHandles so Starsector's classloader never blocks reflection.
     */
    public void ensureAttachedToWarroomUI() {
        if (attachedToWarroom || engine == null) return;
        if (!BattlefieldFirepowerEvaluator.isRealCombatEngine(engine)) return;
        CombatUIAPI ui = engine.getCombatUI();
        if (ui == null) return;
        if (!"com.fs.starfarer.combat.CombatState".equals(ui.getClass().getName())) return;

        try {
            ClassLoader cl = ui.getClass().getClassLoader();
            Class<?> wrClass = ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                    cl, "com.fs.starfarer.combat.new.OoOO", ui.getClass().getName(), "getWarroom");
            Class<?> mdClass = ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                    cl, "com.fs.starfarer.combat.new.b", wrClass.getName(), "getMapDisplay");
            Class<?> iconClass = null;
            try {
                iconClass = ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                        cl, "com.fs.starfarer.combat.new.OOoO", wrClass.getName(), "findClosestIcon");
            } catch (Throwable ignored) {
            }

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
            try {
                this.isShowingVideoFeedHandle = lookup.findVirtual(
                        wrClass,
                        "isShowingVideoFeed",
                        MethodType.methodType(boolean.class));
            } catch (Throwable ignored) {
                this.isShowingVideoFeedHandle = null;
            }
            try {
                this.getEntityToFollowV2Handle = lookup.findVirtual(
                        ui.getClass(),
                        "getEntityToFollowV2",
                        MethodType.methodType(com.fs.starfarer.api.combat.CombatEntityAPI.class));
            } catch (Throwable ignored) {
                this.getEntityToFollowV2Handle = null;
            }
            try {
                this.areWeaponArcsOnHandle = lookup.findVirtual(
                        ui.getClass(),
                        "areWeaponArcsOn",
                        MethodType.methodType(boolean.class));
            } catch (Throwable ignored) {
                this.areWeaponArcsOnHandle = null;
            }
            try {
                Class<?> arcRenCls = ObfuscatedBytecodeBridge.resolveClassOrFieldType(
                        cl, "com.fs.starfarer.renderers.super", ui.getClass().getName(), "arcRenderer");
                MethodHandles.Lookup csLookup = MethodHandles.privateLookupIn(ui.getClass(), lookup);
                MethodHandle getArcRenHandle = csLookup.findGetter(ui.getClass(), "arcRenderer", arcRenCls);
                this.arcRendererInstance = getArcRenHandle.invoke(ui);
                if (this.arcRendererInstance != null) {
                    MethodHandles.Lookup arLookup = MethodHandles.privateLookupIn(arcRenCls, lookup);
                    String animField = ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                            cl, arcRenCls, "F", "\u00d300000");
                    this.arcRendererAnimProgressGetter = arLookup.findGetter(arcRenCls, animField, float.class);
                    ObfuscatedBytecodeBridge.ClassBytecodeInfo arInfo =
                            ObfuscatedBytecodeBridge.readClassInfo(cl, arcRenCls.getName());
                    if (arInfo != null) {
                        for (int i = 0, n = arInfo.fields.size(); i < n; i++) {
                            ObfuscatedBytecodeBridge.MemberEntry f = arInfo.fields.get(i);
                            if (f.descriptor == null || !f.descriptor.startsWith("L") || !f.descriptor.endsWith(";")) {
                                continue;
                            }
                            String fieldTypeName = f.descriptor.substring(1, f.descriptor.length() - 1).replace('/', '.');
                            try {
                                Class<?> fieldCls = Class.forName(fieldTypeName, false, cl);
                                if (fieldCls.isEnum() && this.arcRendererStateGetter == null) {
                                    this.arcRendererStateGetter = arLookup.findGetter(arcRenCls, f.name, fieldCls);
                                } else if (fieldTypeName.endsWith(".BaseAnimation") && this.arcRendererAnimObjGetter == null) {
                                    this.arcRendererAnimObjGetter = arLookup.findGetter(arcRenCls, f.name, fieldCls);
                                    this.baseAnimGetProgressHandle = lookup.findVirtual(
                                            fieldCls, "getProgress", MethodType.methodType(float.class));
                                } else if (com.fs.starfarer.api.combat.WeaponGroupAPI.class.isAssignableFrom(fieldCls)
                                        && !"return".equals(f.name)
                                        && this.arcRendererCurrentGroupGetter == null) {
                                    this.arcRendererCurrentGroupGetter = arLookup.findGetter(arcRenCls, f.name, fieldCls);
                                }
                            } catch (Throwable ignoredField) {
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                this.arcRendererInstance = null;
                this.arcRendererAnimProgressGetter = null;
                this.arcRendererStateGetter = null;
                this.arcRendererAnimObjGetter = null;
                this.baseAnimGetProgressHandle = null;
                this.arcRendererCurrentGroupGetter = null;
            }
            String worldToMapScreenName = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                    cl, mdClass, "o00000", "(Lorg/lwjgl/util/vector/Vector2f;)Lorg/lwjgl/util/vector/Vector2f;");
            this.worldToMapScreenHandle = lookup.findVirtual(
                    mdClass,
                    worldToMapScreenName,
                    MethodType.methodType(Vector2f.class, Vector2f.class));
            if (iconClass != null) {
                try {
                    String mapGetIconName = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                            cl, mdClass, "o00000", "(Ljava/lang/Object;)" + ObfuscatedBytecodeBridge.toDescriptor(iconClass));
                    this.mapGetIconHandle = lookup.findVirtual(
                            mdClass,
                            mapGetIconName,
                            MethodType.methodType(iconClass, Object.class));
                    String renderSpriteName = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                            cl, iconClass, "\u00d300000", "(ZFF)V", 1);
                    this.iconRenderSpriteHandle = lookup.findVirtual(
                            iconClass,
                            renderSpriteName,
                            MethodType.methodType(void.class, boolean.class, float.class, float.class));
                    String renderBarsName = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                            cl, iconClass, "\u00d200000", "(ZFF)V", 2);
                    this.iconRenderBarsHandle = lookup.findVirtual(
                            iconClass,
                            renderBarsName,
                            MethodType.methodType(void.class, boolean.class, float.class, float.class));
                } catch (Throwable ignored) {
                    this.mapGetIconHandle = null;
                    this.iconRenderSpriteHandle = null;
                    this.iconRenderBarsHandle = null;
                }
                try {
                    String radiusMethodName = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                            cl, iconClass, "super", "(F)F");
                    this.iconGetRadiusHandle = lookup.findVirtual(
                            iconClass,
                            radiusMethodName,
                            MethodType.methodType(float.class, float.class));
                } catch (Throwable ignored) {
                    this.iconGetRadiusHandle = null;
                }
            }
            try {
                Class<?> arrowMgrClass = ObfuscatedBytecodeBridge.resolveWarroomArrowMgrClass(cl, mdClass);
                Class<?> arrowEntryClass = ObfuscatedBytecodeBridge.resolveWarroomArrowEntryClass(cl, arrowMgrClass);
                Class<?> faderClass = Class.forName("com.fs.graphics.util.Fader", false, cl);
                MethodHandles.Lookup mdLookup = MethodHandles.privateLookupIn(mdClass, lookup);
                MethodHandles.Lookup arrowMgrLookup = MethodHandles.privateLookupIn(arrowMgrClass, lookup);
                MethodHandles.Lookup arrowEntryLookup = MethodHandles.privateLookupIn(arrowEntryClass, lookup);
                String arrowMgrField = ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                        cl, mdClass, ObfuscatedBytecodeBridge.toDescriptor(arrowMgrClass), "\u00d3o0000");
                MethodHandle getArrowMgrHandle = mdLookup.findGetter(mdClass, arrowMgrField, arrowMgrClass);
                Object arrowMgr = getArrowMgrHandle.invoke(md);
                if (arrowMgr != null) {
                    String fadingListField = ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                            cl, arrowMgrClass, "Ljava/util/List;", "\u00d600000");
                    MethodHandle getFadingListHandle = arrowMgrLookup.findGetter(arrowMgrClass, fadingListField, List.class);
                    Object listObj = getFadingListHandle.invoke(arrowMgr);
                    if (listObj instanceof List) {
                        this.warroomFadingArrowsList = (List<?>) listObj;
                        String entryFaderField = ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                                cl, arrowEntryClass, ObfuscatedBytecodeBridge.toDescriptor(faderClass), "\u00d600000");
                        this.arrowEntryFaderGetter = arrowEntryLookup.findGetter(arrowEntryClass, entryFaderField, faderClass);
                        this.faderIsFadedOutHandle = lookup.findVirtual(faderClass, "isFadedOut", MethodType.methodType(boolean.class));
                    }
                }
            } catch (Throwable ignored) {
                this.warroomFadingArrowsList = null;
            }

            try {
                Class<?> fddCls = ObfuscatedBytecodeBridge.resolveClassOrFieldType(
                        cl, "com.fs.starfarer.ui.impl.oO0O", ui.getClass().getName(), "fdd");
                Class<?> sGridCls = ObfuscatedBytecodeBridge.resolveClassOrFieldByPackage(
                        cl, "com.fs.starfarer.campaign.ui.S", fddCls.getName(), "com.fs.starfarer.campaign.ui.");
                Class<?> bNewCls = ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                        cl, "com.fs.starfarer.ui.B.new", sGridCls.getName(), "getIconForMember");
                Class<?> spriteCls = Class.forName("com.fs.graphics.Sprite", false, cl);
                Class<?> texObjCls = ObfuscatedBytecodeBridge.resolveClassOrFieldType(
                        cl, "com.fs.graphics.Object", spriteCls.getName(), "texture");
                Class<?> shipIconCls = ObfuscatedBytecodeBridge.resolveWarroomShipIconClass(cl, mdClass, bNewCls);
                Class<?> fighterIconCls = ObfuscatedBytecodeBridge.resolveWarroomFighterIconClass(cl, mdClass, shipIconCls);
                MethodHandles.Lookup mdLookup2 = MethodHandles.privateLookupIn(mdClass, lookup);
                MethodHandles.Lookup bNewLookup = MethodHandles.privateLookupIn(bNewCls, lookup);
                MethodHandles.Lookup spriteLookup = MethodHandles.privateLookupIn(spriteCls, lookup);
                this.warroomShipIconClass = shipIconCls;
                this.warroomFighterIconClass = fighterIconCls;
                String iconsMapMethod = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                        cl, mdClass, "class", "()Ljava/util/Map;");
                this.mdIconsMapGetter = lookup.findVirtual(mdClass, iconsMapMethod, MethodType.methodType(Map.class));
                String iconSetField = ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                        cl, mdClass, "Ljava/util/Set;", "\u00d2o0000");
                this.mdIconSetGetter = mdLookup2.findGetter(mdClass, iconSetField, Set.class);
                String shipIconBNewMethod = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                        cl, shipIconCls, "\u00d3O0000", "()" + ObfuscatedBytecodeBridge.toDescriptor(bNewCls));
                this.shipIconBNewGetter = lookup.findVirtual(shipIconCls, shipIconBNewMethod, MethodType.methodType(bNewCls));
                String bNewSpriteField = ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                        cl, bNewCls, ObfuscatedBytecodeBridge.toDescriptor(spriteCls), "\u00d4\u00d50000");
                this.bNewSpriteGetter = bNewLookup.findGetter(bNewCls, bNewSpriteField, spriteCls);
                String holoMethod = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                        cl, bNewCls, "\u00d4O0000", "(Z)V", 1);
                this.bNewHoloSetter = lookup.findVirtual(bNewCls, holoMethod, MethodType.methodType(void.class, boolean.class));
                String modulesMethod = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                        cl, bNewCls, "\u00f400000", "(Z)V", 4);
                this.bNewModulesSetter = lookup.findVirtual(bNewCls, modulesMethod, MethodType.methodType(void.class, boolean.class));
                String statusMethod = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                        cl, bNewCls, "\u00d500000", "(Z)V", 10);
                this.bNewStatusSetter = lookup.findVirtual(bNewCls, statusMethod, MethodType.methodType(void.class, boolean.class));
                this.spriteTexGetter = spriteLookup.findGetter(spriteCls, "texture", texObjCls);
                this.spriteTexSetter = spriteLookup.findSetter(spriteCls, "texture", texObjCls);
                this.spriteReadResolveHandle = spriteLookup.findVirtual(spriteCls, "readResolve", MethodType.methodType(Object.class));
                try {
                    MethodHandles.Lookup aLookup = MethodHandles.privateLookupIn(shipIconCls, lookup);
                    String arrowSpriteField = ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                            cl, shipIconCls, ObfuscatedBytecodeBridge.toDescriptor(spriteCls), "interface.new");
                    this.shipIconArrowSpriteGetter = aLookup.findGetter(shipIconCls, arrowSpriteField, spriteCls);
                } catch (Throwable ignored) {
                    this.shipIconArrowSpriteGetter = null;
                }
                try {
                    MethodHandles.Lookup fighterLookup = MethodHandles.privateLookupIn(fighterIconCls, lookup);
                    String fighterSpriteField = ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                            cl, fighterIconCls, ObfuscatedBytecodeBridge.toDescriptor(spriteCls), "float.super");
                    this.fighterIconSpriteGetter = fighterLookup.findGetter(fighterIconCls, fighterSpriteField, spriteCls);
                } catch (Throwable ignored) {
                    this.fighterIconSpriteGetter = null;
                }
            } catch (Throwable t) {
                this.warroomShipIconClass = null;
                this.mdIconsMapGetter = null;
                this.mdIconSetGetter = null;
            }

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

    public static boolean verifyWarroomObfuscatedHandles(ClassLoader cl) {
        try {
            if (cl == null) cl = DivisionTacticalMapOverlay.class.getClassLoader();
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> csClass = Class.forName("com.fs.starfarer.combat.CombatState", false, cl);
            Class<?> wrClass = ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                    cl, "com.fs.starfarer.combat.new.OoOO", csClass.getName(), "getWarroom");
            Class<?> mdClass = ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                    cl, "com.fs.starfarer.combat.new.b", wrClass.getName(), "getMapDisplay");
            Class<?> iconClass = ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                    cl, "com.fs.starfarer.combat.new.OOoO", wrClass.getName(), "findClosestIcon");
            lookup.findVirtual(csClass, "getWarroom", MethodType.methodType(wrClass));
            lookup.findVirtual(wrClass, "getMapDisplay", MethodType.methodType(mdClass));
            lookup.findVirtual(wrClass, "isShowingVideoFeed", MethodType.methodType(boolean.class));
            String worldToMapScreenName = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                    cl, mdClass, "o00000", "(Lorg/lwjgl/util/vector/Vector2f;)Lorg/lwjgl/util/vector/Vector2f;");
            lookup.findVirtual(mdClass, worldToMapScreenName, MethodType.methodType(Vector2f.class, Vector2f.class));
            String mapGetIconName = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                    cl, mdClass, "o00000", "(Ljava/lang/Object;)" + ObfuscatedBytecodeBridge.toDescriptor(iconClass));
            lookup.findVirtual(mdClass, mapGetIconName, MethodType.methodType(iconClass, Object.class));
            String renderSpriteName = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                    cl, iconClass, "\u00d300000", "(ZFF)V", 1);
            lookup.findVirtual(iconClass, renderSpriteName, MethodType.methodType(void.class, boolean.class, float.class, float.class));
            String renderBarsName = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                    cl, iconClass, "\u00d200000", "(ZFF)V", 2);
            lookup.findVirtual(iconClass, renderBarsName, MethodType.methodType(void.class, boolean.class, float.class, float.class));
            String radiusMethodName = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                    cl, iconClass, "super", "(F)F");
            lookup.findVirtual(iconClass, radiusMethodName, MethodType.methodType(float.class, float.class));
            Class<?> arrowMgrClass = ObfuscatedBytecodeBridge.resolveWarroomArrowMgrClass(cl, mdClass);
            Class<?> arrowEntryClass = ObfuscatedBytecodeBridge.resolveWarroomArrowEntryClass(cl, arrowMgrClass);
            Class<?> faderClass = Class.forName("com.fs.graphics.util.Fader", false, cl);
            String arrowMgrField = ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                    cl, mdClass, ObfuscatedBytecodeBridge.toDescriptor(arrowMgrClass), "\u00d3o0000");
            MethodHandles.privateLookupIn(mdClass, lookup).findGetter(mdClass, arrowMgrField, arrowMgrClass);
            String fadingListField = ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                    cl, arrowMgrClass, "Ljava/util/List;", "\u00d600000");
            MethodHandles.privateLookupIn(arrowMgrClass, lookup).findGetter(arrowMgrClass, fadingListField, List.class);
            String entryFaderField = ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                    cl, arrowEntryClass, ObfuscatedBytecodeBridge.toDescriptor(faderClass), "\u00d600000");
            MethodHandles.privateLookupIn(arrowEntryClass, lookup).findGetter(arrowEntryClass, entryFaderField, faderClass);
            Class<?> fddCls = ObfuscatedBytecodeBridge.resolveClassOrFieldType(
                    cl, "com.fs.starfarer.ui.impl.oO0O", csClass.getName(), "fdd");
            Class<?> sGridCls = ObfuscatedBytecodeBridge.resolveClassOrFieldByPackage(
                    cl, "com.fs.starfarer.campaign.ui.S", fddCls.getName(), "com.fs.starfarer.campaign.ui.");
            Class<?> bNewCls = ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                    cl, "com.fs.starfarer.ui.B.new", sGridCls.getName(), "getIconForMember");
            Class<?> spriteCls = Class.forName("com.fs.graphics.Sprite", false, cl);
            Class<?> texObjCls = ObfuscatedBytecodeBridge.resolveClassOrFieldType(
                    cl, "com.fs.graphics.Object", spriteCls.getName(), "texture");
            Class<?> shipIconCls = ObfuscatedBytecodeBridge.resolveWarroomShipIconClass(cl, mdClass, bNewCls);
            Class<?> fighterIconCls = ObfuscatedBytecodeBridge.resolveWarroomFighterIconClass(cl, mdClass, shipIconCls);
            String iconsMapMethod = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                    cl, mdClass, "class", "()Ljava/util/Map;");
            lookup.findVirtual(mdClass, iconsMapMethod, MethodType.methodType(Map.class));
            String iconSetField = ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                    cl, mdClass, "Ljava/util/Set;", "\u00d2o0000");
            MethodHandles.privateLookupIn(mdClass, lookup).findGetter(mdClass, iconSetField, Set.class);
            String shipIconBNewMethod = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                    cl, shipIconCls, "\u00d3O0000", "()" + ObfuscatedBytecodeBridge.toDescriptor(bNewCls));
            lookup.findVirtual(shipIconCls, shipIconBNewMethod, MethodType.methodType(bNewCls));
            String bNewSpriteMethod = ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                    cl, bNewCls, "\u00d3o0000", "()" + ObfuscatedBytecodeBridge.toDescriptor(spriteCls));
            lookup.findVirtual(bNewCls, bNewSpriteMethod, MethodType.methodType(spriteCls));
            MethodHandles.Lookup spriteLookup = MethodHandles.privateLookupIn(spriteCls, lookup);
            spriteLookup.findGetter(spriteCls, "texture", texObjCls);
            spriteLookup.findSetter(spriteCls, "texture", texObjCls);
            spriteLookup.findVirtual(spriteCls, "readResolve", MethodType.methodType(Object.class));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isShowingVideoFeed() {
        if (!attachedToWarroom) {
            ensureAttachedToWarroomUI();
        }
        if (warroomInstance != null && isShowingVideoFeedHandle != null) {
            try {
                return (boolean) isShowingVideoFeedHandle.invoke(warroomInstance);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public ShipAPI resolveVideoFeedShip() {
        if (engine == null) return null;
        if (!attachedToWarroom) {
            ensureAttachedToWarroomUI();
        }
        if (getEntityToFollowV2Handle != null && engine.getCombatUI() != null) {
            try {
                Object ent = getEntityToFollowV2Handle.invoke(engine.getCombatUI());
                if (ent instanceof ShipAPI) {
                    return (ShipAPI) ent;
                }
            } catch (Throwable ignored) {
            }
        }
        return engine.getPlayerShip();
    }

    public boolean areWeaponArcsOn() {
        if (engine == null) return true;
        if (!attachedToWarroom) {
            ensureAttachedToWarroomUI();
        }
        if (areWeaponArcsOnHandle != null && engine.getCombatUI() != null) {
            try {
                return (boolean) areWeaponArcsOnHandle.invoke(engine.getCombatUI());
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    public float getArcRendererAnimProgress() {
        if (!attachedToWarroom) {
            ensureAttachedToWarroomUI();
        }
        if (arcRendererInstance != null) {
            if (arcRendererStateGetter != null) {
                try {
                    Object stateObj = arcRendererStateGetter.invoke(arcRendererInstance);
                    if (stateObj instanceof Enum<?>) {
                        int ord = ((Enum<?>) stateObj).ordinal();
                        // com.fs.starfarer.renderers.super$o ordinals:
                        //   0 = OPENING (anim.getProgress())
                        //   1 = CLOSING (1.0f - anim.getProgress())
                        //   2 = OPEN    (1.0f)
                        //   3 = IDLE    (0.0f)
                        if (ord == 2) {
                            return 1.0f;
                        }
                        if (ord == 3) {
                            return 0.0f;
                        }
                        float animProg = 1.0f;
                        if (arcRendererAnimObjGetter != null && baseAnimGetProgressHandle != null) {
                            Object animObj = arcRendererAnimObjGetter.invoke(arcRendererInstance);
                            if (animObj != null) {
                                animProg = (float) baseAnimGetProgressHandle.invoke(animObj);
                            }
                        }
                        float val = (ord == 0) ? animProg : (1.0f - animProg);
                        if (!Float.isNaN(val)) {
                            return Math.max(0f, Math.min(1f, val));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (arcRendererAnimProgressGetter != null) {
                try {
                    float val = (float) arcRendererAnimProgressGetter.invoke(arcRendererInstance);
                    if (!Float.isNaN(val)) {
                        return Math.max(0f, Math.min(1f, val));
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return 1.0f;
    }

    public com.fs.starfarer.api.combat.WeaponGroupAPI getArcRendererActiveWeaponGroup(ShipAPI fallbackShip) {
        if (!attachedToWarroom) {
            ensureAttachedToWarroomUI();
        }
        if (arcRendererInstance != null && arcRendererCurrentGroupGetter != null) {
            try {
                Object grp = arcRendererCurrentGroupGetter.invoke(arcRendererInstance);
                if (grp instanceof com.fs.starfarer.api.combat.WeaponGroupAPI) {
                    return (com.fs.starfarer.api.combat.WeaponGroupAPI) grp;
                }
                return null;
            } catch (Throwable ignored) {
            }
        }
        return fallbackShip != null ? fallbackShip.getSelectedGroupAPI() : null;
    }

    @Override
    public void processInput(List<InputEventAPI> events) {
        if (!attachedToWarroom || !BattlefieldFirepowerEvaluator.isCommandViewWindowActive(engine)) {
            return;
        }
        TsfcWarroomRenderer.processInputEvents(engine, events);
        if (isShowingVideoFeed()) {
            return;
        }
        syncRegularCommandSpritesVisibility(
                TsfcWarroomRenderer.isAnyTsfcOverlayVisible() && DivisionAISettings.isTsfcMiniShipSpritesEnabled());
    }

    @Override
    public void advance(float amount) {
        if (!attachedToWarroom || engine == null) return;
        syncRegularCommandSpritesVisibility(
                TsfcWarroomRenderer.isAnyTsfcOverlayVisible() && DivisionAISettings.isTsfcMiniShipSpritesEnabled());
    }

    /**
     * Prunes faded-out order arrows from vanilla Starsector's {@code com.fs.starfarer.combat.new.O0OO.\u00d600000}
     * LinkedList. Vanilla Starsector appends old {@code O0OO$o} arrows to {@code \u00d600000} whenever a ship's
     * order changes, advances their faders, and renders 18 OpenGL quads per entry every Warroom frame, but NEVER
     * removes entries after {@code Fader.isFadedOut()} becomes true.
     */
    public void pruneLeakedWarroomOrderArrows() {
        List<?> list = this.warroomFadingArrowsList;
        if (list == null || list.isEmpty()) return;
        try {
            if (arrowEntryFaderGetter != null && faderIsFadedOutHandle != null) {
                java.util.Iterator<?> it = list.iterator();
                while (it.hasNext()) {
                    Object entry = it.next();
                    if (entry == null) {
                        it.remove();
                        continue;
                    }
                    Object fader = arrowEntryFaderGetter.invoke(entry);
                    if (fader != null && (boolean) faderIsFadedOutHandle.invoke(fader)) {
                        it.remove();
                    }
                }
            }
            while (list.size() > 48) {
                list.remove(0);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void render(float alphaMult) {
        pruneLeakedWarroomOrderArrows();
        if (!attachedToWarroom || engine == null) return;
        if (!BattlefieldFirepowerEvaluator.isCommandViewWindowActive(engine)) return;
        if (!DivisionAISettings.isTacticalMapOverlayEnabled()) return;

        CombatUIAPI ui = engine.getCombatUI();
        if (ui == null || !ui.isShowingCommandUI()) {
            clearCachedPolygonsOnMapHidden();
            return;
        }

        float cmdOpacity = ui.getCommandUIOpacity();
        if (cmdOpacity <= 0.05f || alphaMult <= 0.05f) {
            clearCachedPolygonsOnMapHidden();
            return;
        }

        // Do not render Warroom polygons or truescale ship/hulk icons if the player is viewing a live sprite
        // Video Feed ("F" key) inside the Command UI; instead, battle-screen TSFC renders underneath the live ships
        // on CombatEngineLayers.BELOW_SHIPS_LAYER, and we keep only the right-side TSFC toggle buttons accessible.
        if (isShowingVideoFeed()) {
            clearCachedPolygonsOnMapHidden();
            TsfcWarroomRenderer.renderToggleButtonAndLegendUI(engine, Math.min(1f, cmdOpacity * alphaMult));
            return;
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

        long tFrameStart = System.nanoTime();

        // Reuse computed division metaball polygons, strike craft polygons, and overlap stripes across render frames:
        //  - When paused: zero Marching-Squares / Ear-Clipping work even while panning/zooming.
        //  - During live combat: per-division caching + staggered refresh windows so a single enemy ship flickering
        //    across fog-of-war only rebuilds that one division (not all 39 divisions at once!), and live frames
        //    smoothly translate cached polygons in-place between staggered rebuilds.
        boolean paused = engine.isPaused();
        float now = engine.getTotalElapsedTime(false);
        boolean sameEngineAndGroups = cachedPolyEngine == engine
                && cachedPolyPlayerGroup == playerGroup
                && cachedPolyAlliedGroup == alliedAiGroup
                && cachedPolyEnemyGroup == enemyGroup;
        if (!sameEngineAndGroups) {
            perDivisionGeometryCache.clear();
            perDivisionStrikeCraftCache.clear();
            cachedRenderedPolys.clear();
            cachedStrikeCraftPolys.clear();
            cachedOverlapStripeBands.clear();
            lastStripeRebuildSimTime = -1000f;
        }

        // Refresh Voidstrike memberships & centroids up front so Strike Craft Voidstrike envelopes and
        // "Voidstrike #" labels stay 100% phase-locked on every Warroom frame.
        VoidstrikeCoordinator.refreshVoidstrikesForRender(
                engine,
                playerGroup,
                alliedAiGroup,
                enemyGroup,
                now);

        int geomSignature = computeBattleGroupsGeometrySignature(
                playerGroup, alliedAiGroup, enemyGroup, playerFog, paused);
        boolean sameContext = sameEngineAndGroups
                && cachedPolySignature == geomSignature
                && (!cachedRenderedPolys.isEmpty() || !cachedStrikeCraftPolys.isEmpty());
        boolean reuseAllCached = sameContext && paused && cachedPolyPaused && Math.abs(now - cachedPolyNow) < 1e-4f;

        List<RenderedDivisionPolygon> renderedPolys = cachedRenderedPolys;
        List<RenderedDivisionPolygon> strikeCraftPolys = cachedStrikeCraftPolys;
        int divisionsRebuiltThisFrame = 0;

        if (reuseAllCached) {
            if (Float.compare(cachedPolyScale, scale) != 0
                    || Float.compare(cachedPolyOffsetX, offsetX) != 0
                    || Float.compare(cachedPolyOffsetY, offsetY) != 0) {
                transformCachedGeometryInPlace(scale, offsetX, offsetY);
                cachedPolyScale = scale;
                cachedPolyOffsetX = offsetX;
                cachedPolyOffsetY = offsetY;
            }
        } else {
            boolean cameraChanged = !Float.isNaN(cachedPolyScale)
                    && (Float.compare(cachedPolyScale, scale) != 0
                    || Float.compare(cachedPolyOffsetX, offsetX) != 0
                    || Float.compare(cachedPolyOffsetY, offsetY) != 0);
            if (cameraChanged && !cachedOverlapStripeBands.isEmpty()) {
                float ratio = scale / cachedPolyScale;
                for (CachedOverlapStripeBand band : cachedOverlapStripeBands) {
                    for (Vector2f v : band.verts) {
                        v.x = (v.x - cachedPolyOffsetX) * ratio + offsetX;
                        v.y = (v.y - cachedPolyOffsetY) * ratio + offsetY;
                    }
                    band.anchorMidX = (band.anchorMidX - cachedPolyOffsetX) * ratio + offsetX;
                    band.anchorMidY = (band.anchorMidY - cachedPolyOffsetY) * ratio + offsetY;
                }
            }

            boolean coldBuild = (!sameEngineAndGroups || cachedPolyNow < -500f);
            renderedPolys.clear();
            strikeCraftPolys.clear();
            // Spread Marching-Squares SDF contour rebuilds to at most 2 divisions per unpaused frame (~0.6ms max)
            // and 4 divisions on pause transition (eliminating 24ms Spacebar spikes) while 60 FPS multi-ship LBS
            // skinning keeps every ship lobe tracking live hulls continuously between rebuilds.
            int[] rebuildBudget = new int[] { coldBuild ? 256 : (paused ? 4 : 2) };

            if (playerGroup != null && !playerGroup.divisions.isEmpty()) {
                divisionsRebuiltThisFrame += collectDivisionPolygonsCached(
                        playerGroup, false, null, now, paused, scale, offsetX, offsetY, rebuildBudget, renderedPolys);
            }
            if (alliedAiGroup != null && !alliedAiGroup.divisions.isEmpty()) {
                divisionsRebuiltThisFrame += collectDivisionPolygonsCached(
                        alliedAiGroup, false, null, now, paused, scale, offsetX, offsetY, rebuildBudget, renderedPolys);
            }
            if (enemyGroup != null && !enemyGroup.divisions.isEmpty()) {
                divisionsRebuiltThisFrame += collectDivisionPolygonsCached(
                        enemyGroup, true, playerFog, now, paused, scale, offsetX, offsetY, rebuildBudget, renderedPolys);
            }

            // Collect squadron-aware & Voidstrike-unified Strike Craft envelopes per side (player, allied, enemy),
            // including standalone/unassigned carriers when Voidstrikes are active.
            collectStrikeCraftSidePolygonsCached(
                    playerGroup, VoidstrikeCoordinator.VOIDSTRIKE_SIDE_PLAYER, false, false, null,
                    now, paused, scale, offsetX, offsetY, strikeCraftPolys);
            collectStrikeCraftSidePolygonsCached(
                    alliedAiGroup, VoidstrikeCoordinator.VOIDSTRIKE_SIDE_ALLIED, false, true, null,
                    now, paused, scale, offsetX, offsetY, strikeCraftPolys);
            collectStrikeCraftSidePolygonsCached(
                    enemyGroup, VoidstrikeCoordinator.VOIDSTRIKE_SIDE_ENEMY, true, false, playerFog,
                    now, paused, scale, offsetX, offsetY, strikeCraftPolys);

            // Never stack overlap-stripe Sutherland-Hodgman clipping onto the same unpaused frame that just rebuilt a division SDF
            boolean rebuildStripesAndFlags = lastStripeRebuildSimTime < -500f
                    || (coldBuild && divisionsRebuiltThisFrame > 0)
                    || (divisionsRebuiltThisFrame == 0 && Math.abs(now - lastStripeRebuildSimTime) >= 0.35f);
            if (rebuildStripesAndFlags) {
                cachedOverlapStripeBands.clear();
                if (renderedPolys.size() >= 2) {
                    buildOverlappingPolygonStripesCache(renderedPolys, cachedOverlapStripeBands);
                }
                cachedFleetFlagships = resolveFleetFlagships(playerFog);
                cachedDivisionFlagships = resolveDivisionFlagships(playerFog, cachedFleetFlagships);
                lastStripeRebuildSimTime = now;
            } else {
                translateCachedOverlapStripeBandsInPlace(cachedOverlapStripeBands);
                if (!paused && cachedPolySignature != geomSignature) {
                    cachedFleetFlagships = resolveFleetFlagships(playerFog);
                    cachedDivisionFlagships = resolveDivisionFlagships(playerFog, cachedFleetFlagships);
                }
            }
            deconflictPrimaryDivisionLabels(renderedPolys);
            cachedPolyEngine = engine;
            cachedPolyPlayerGroup = playerGroup;
            cachedPolyAlliedGroup = alliedAiGroup;
            cachedPolyEnemyGroup = enemyGroup;
            cachedPolyPaused = paused;
            cachedPolyNow = now;
            cachedPolySignature = geomSignature;
            lastLiveTranslateSimTime = now;
            cachedPolyScale = scale;
            cachedPolyOffsetX = offsetX;
            cachedPolyOffsetY = offsetY;
        }

        long tAfterGeom = System.nanoTime();

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        // 1. Layer 1: Render unbroken fluid warship division polygons in a single batched GL_TRIANGLES pass
        renderAllDivisionPolygonsBatched(renderedPolys, basePolyAlpha);

        // 2. Layer 1b: Render thickly striped overlap zones in a single batched GL_TRIANGLES pass
        if (!cachedOverlapStripeBands.isEmpty()) {
            renderCachedOverlapPolygonStripes(cachedOverlapStripeBands, basePolyAlpha);
        }

        // 2b. Layer 2 (Feature A): Render Strike Craft division polygons on their own dedicated layer above ship polygons
        //     with dashed borders mimicking the Point Defense (PD) projection in the Firepower overlay.
        if (!strikeCraftPolys.isEmpty()) {
            renderStrikeCraftDivisionPolygons(strikeCraftPolys, basePolyAlpha, fade);
        }

        GL11.glPopMatrix();
        GL11.glPopAttrib();

        long tAfterPolys = System.nanoTime();

        boolean anyTsfcVisible = TsfcWarroomRenderer.isAnyTsfcOverlayVisible();
        boolean useMiniSpritesForTsfc = anyTsfcVisible && DivisionAISettings.isTsfcMiniShipSpritesEnabled();
        syncRegularCommandSpritesVisibility(useMiniSpritesForTsfc);

        // 3. Render Time, Space, Firepower, and Convergence map layers (when toggled ON) UNDER ship sprites & waypoints
        float tsfcFade = fade * DivisionAISettings.getTsfcWarroomOpacityMult();
        TsfcWarroomRenderer.renderTimeSpaceAndConvergenceScreenSpace(
                engine, scale, offsetX, offsetY, tsfcFade, playerGroup, alliedAiGroup, enemyGroup);
        TsfcWarroomRenderer.renderFirepowerRosesScreenSpace(
                engine, scale, offsetX, offsetY, tsfcFade, playerGroup, alliedAiGroup, enemyGroup);

        // 3b. When TSFC is active and T-S-F-C Mini Ship Sprites is enabled in Mod Settings, regular command sprites
        //     are disabled and replaced by mini red/green/yellow 1:1 hull sprites + status bars above TSFC.
        //     Otherwise, render regular command sprites above polygons (and above TSFC when TSFC is active).
        if (useMiniSpritesForTsfc) {
            TsfcWarroomRenderer.renderWarroomShipSpritesAboveTsfc(engine, scale, offsetX, offsetY, fade);
            renderShipBarsAboveTsfc(renderedPolys, scale, offsetX, offsetY, fade);
        } else {
            if (anyTsfcVisible) {
                TsfcWarroomRenderer.renderWarroomHulkSpritesAboveTsfc(engine, scale, offsetX, offsetY, fade);
            }
            renderShipSpritesOnTopLayer(renderedPolys, scale, offsetX, offsetY, fade);
        }

        long tAfterTsfc = System.nanoTime();

        // 4. Render division waypoints & tactical intent vectors for friendly divisions above TSFC & ship sprites
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        for (RenderedDivisionPolygon rp : renderedPolys) {
            if (!rp.isEnemy && rp.isPrimaryDroplet) {
                renderDivisionWaypointsAndIntent(rp, scale, offsetX, offsetY, friendlyLineAlpha, playerFog);
            }
        }
        GL11.glPopMatrix();
        GL11.glPopAttrib();

        // 4b. Render Fleet Flagship (gold star) and Division Leader (silver star) badges on top of ship sprites & TSFC
        renderFlagshipStarsOnTopLayer(cachedFleetFlagships, cachedDivisionFlagships, scale, offsetX, offsetY, fade);

        long tAfterStars = System.nanoTime();

        // 5. Render de-conflicted division labels anchored to the primary division droplet entity
        for (RenderedDivisionPolygon rp : renderedPolys) {
            if (rp.isPrimaryDroplet) {
                renderDivisionEntityLabel(rp, fade, cachedFleetFlagships);
            }
        }

        // 5b. Render "Voidstrike #" labels anchored cleanly to their visible Strike Craft envelopes
        // (refreshed at top of render() in lockstep with Strike Craft Voidstrike envelopes)
        renderVoidstrikeLabels(playerFog, scale, offsetX, offsetY, fade, renderedPolys, strikeCraftPolys);

        long tAfterLabels = System.nanoTime();

        // 6. Render right-side Warroom Buttons & Urgent Alpha Core Comms Modal on the ABSOLUTE TOP LAYER
        // above all division polygons, ship sprites, overlays, and division text labels
        TsfcWarroomRenderer.renderToggleButtonAndLegendUI(engine, fade);

        long tFrameEnd = System.nanoTime();
        recordWarroomFrameTelemetry(
                tFrameStart,
                tAfterGeom,
                tAfterPolys,
                tAfterStars,
                tAfterTsfc,
                tAfterLabels,
                tFrameEnd,
                divisionsRebuiltThisFrame,
                renderedPolys.size(),
                cachedOverlapStripeBands.size());
    }

    private void recordWarroomFrameTelemetry(
            long tFrameStart,
            long tAfterGeom,
            long tAfterPolys,
            long tAfterStars,
            long tAfterTsfc,
            long tAfterLabels,
            long tFrameEnd,
            int divisionsRebuiltThisFrame,
            int polyCount,
            int overlapBandCount) {
        float totalMs = (tFrameEnd - tFrameStart) / 1e6f;
        float geomMs = (tAfterGeom - tFrameStart) / 1e6f;
        float polyMs = (tAfterPolys - tAfterGeom) / 1e6f;
        float tsfcMs = (tAfterTsfc - tAfterPolys) / 1e6f;
        float starsMs = (tAfterStars - tAfterTsfc) / 1e6f;
        float labelsMs = (tAfterLabels - tAfterStars) / 1e6f;
        float uiMs = (tFrameEnd - tAfterLabels) / 1e6f;

        int ringIdx = perfWindowCount % PERF_WINDOW_FRAMES;
        perfTotalMsRing[ringIdx] = totalMs;
        perfWindowCount++;
        if (divisionsRebuiltThisFrame > 0) {
            perfRebuildFrames++;
            perfDivisionsRebuilt += divisionsRebuiltThisFrame;
        }
        perfSumTotalMs += totalMs;
        if (totalMs > perfMaxTotalMs) perfMaxTotalMs = totalMs;
        perfSumGeomMs += geomMs;
        if (geomMs > perfMaxGeomMs) perfMaxGeomMs = geomMs;
        perfSumPolyMs += polyMs;
        perfSumStarsMs += starsMs;
        perfSumTsfcMs += tsfcMs;
        perfSumLabelsMs += labelsMs;
        perfSumUiMs += uiMs;

        int sampleLen = Math.min(perfWindowCount, PERF_WINDOW_FRAMES);
        if (perfWindowCount >= PERF_WINDOW_FRAMES || totalMs >= 12.0f) {
            float[] sorted = java.util.Arrays.copyOf(perfTotalMsRing, sampleLen);
            java.util.Arrays.sort(sorted);
            float p95Ms = sorted[Math.min(sampleLen - 1, (int) Math.floor(sampleLen * 0.95f))];
            float inv = 1f / Math.max(1, perfWindowCount);
            WarroomPerfSnapshot snap = new WarroomPerfSnapshot(
                    perfWindowCount,
                    perfRebuildFrames,
                    perfDivisionsRebuilt,
                    polyCount,
                    overlapBandCount,
                    perfSumTotalMs * inv,
                    p95Ms,
                    perfMaxTotalMs,
                    perfSumGeomMs * inv,
                    perfMaxGeomMs,
                    perfSumPolyMs * inv,
                    perfSumStarsMs * inv,
                    perfSumTsfcMs * inv,
                    perfSumLabelsMs * inv,
                    perfSumUiMs * inv);
            lastWarroomPerfSnapshot = snap;

            long wallNow = System.currentTimeMillis();
            boolean slowSpike = snap.maxTotalMs >= 4.0f || snap.avgTotalMs >= 2.0f;
            boolean periodicTelemetry = (wallNow - lastPerfLogWallMs >= 10000L);
            if ((slowSpike && (wallNow - lastPerfLogWallMs >= 2000L)) || periodicTelemetry) {
                lastPerfLogWallMs = wallNow;
                LOG.info(String.format(
                        Locale.ROOT,
                        "[DivisionAI] Warroom perf (%d frames, rebuilds=%d/%d divs, polys=%d, stripes=%d): avg=%.2fms p95=%.2fms max=%.2fms | geom=%.2fms(max %.2fms) poly=%.2fms stars=%.2fms tsfc=%.2fms labels=%.2fms ui=%.2fms",
                        snap.frames,
                        snap.rebuildFrames,
                        snap.divisionsRebuilt,
                        snap.polyCount,
                        snap.overlapBandCount,
                        snap.avgTotalMs,
                        snap.p95TotalMs,
                        snap.maxTotalMs,
                        snap.avgGeomMs,
                        snap.maxGeomMs,
                        snap.avgPolyMs,
                        snap.avgStarsMs,
                        snap.avgTsfcMs,
                        snap.avgLabelsMs,
                        snap.avgUiMs));
            }

            if (perfWindowCount >= PERF_WINDOW_FRAMES) {
                perfWindowCount = 0;
                perfRebuildFrames = 0;
                perfDivisionsRebuilt = 0;
                perfSumTotalMs = 0f;
                perfMaxTotalMs = 0f;
                perfSumGeomMs = 0f;
                perfMaxGeomMs = 0f;
                perfSumPolyMs = 0f;
                perfSumStarsMs = 0f;
                perfSumTsfcMs = 0f;
                perfSumLabelsMs = 0f;
                perfSumUiMs = 0f;
            }
        }
    }

    private int computeSingleDivisionGeometrySignature(
            DivisionState div,
            boolean isEnemy,
            FogOfWarAPI playerFog,
            boolean includeQuantizedPositions) {
        if (div == null) return 0;
        int h = (div.id != null ? div.id.hashCode() : 17);
        for (int j = 0, m = div.members.size(); j < m; j++) {
            ShipAPI s = div.members.get(j);
            if (s == null || !s.isAlive() || s.isHulk() || s.isFighter()) continue;
            if (isEnemy && !isEnemyShipVisibleOnCommandMap(this.engine, s, playerFog)) continue;
            h = 31 * h + (s.getId() != null ? s.getId().hashCode() : j);
            if (includeQuantizedPositions && s.getLocation() != null) {
                h = 31 * h + Math.round(s.getLocation().x);
                h = 31 * h + Math.round(s.getLocation().y);
            }
        }
        return h;
    }

    public int collectDivisionPolygonsCached(
            BattleGroupState battleGroup,
            boolean isEnemy,
            FogOfWarAPI playerFog,
            float now,
            boolean paused,
            float scale,
            float offsetX,
            float offsetY,
            int[] rebuildBudget,
            List<RenderedDivisionPolygon> outList) {
        int rebuiltCount = 0;
        if (battleGroup == null || battleGroup.divisions.isEmpty()) return 0;

        for (int divIdx = 0, divLen = battleGroup.divisions.size(); divIdx < divLen; divIdx++) {
            DivisionState div = battleGroup.divisions.get(divIdx);
            if (div == null) continue;
            String cacheKey = (isEnemy ? "E:" : (battleGroup.isAlliedAi ? "A:" : "P:"))
                    + (div.id != null ? div.id : ("idx_" + divIdx));
            int divSig = computeSingleDivisionGeometrySignature(div, isEnemy, playerFog, false);
            int divPosSig = computeSingleDivisionGeometrySignature(div, isEnemy, playerFog, true);
            CachedDivisionGeometry entry = perDivisionGeometryCache.get(cacheKey);
            float staggerWindow = 0.24f + (Math.abs(cacheKey.hashCode()) % 17) * 0.02f;

            boolean sigMatches = (entry != null && entry.signature == divSig);
            float simAge = (entry != null) ? Math.abs(now - entry.builtAtSimTime) : Float.MAX_VALUE;
            boolean hasBudget = (rebuildBudget == null || rebuildBudget[0] > 0);
            boolean deformed = !paused && hasBudget && entry != null && hasLargeSkinTopologyDeformation(entry.droplets);
            boolean withinTimeWindow = (entry != null) && !deformed && (paused
                    ? (entry.positionSignature == divPosSig && simAge < staggerWindow)
                    : (simAge < staggerWindow));
            boolean allowBudgetDeferral = !paused
                    && sigMatches
                    && simAge < staggerWindow * 2.5f
                    && rebuildBudget != null
                    && rebuildBudget[0] <= 0
                    && entry != null
                    && !entry.droplets.isEmpty();
            boolean reuseEntry = (entry != null) && ((sigMatches && withinTimeWindow) || allowBudgetDeferral);

            if (reuseEntry) {
                if (!entry.droplets.isEmpty()) {
                    if (Float.compare(entry.scale, scale) != 0
                            || Float.compare(entry.offsetX, offsetX) != 0
                            || Float.compare(entry.offsetY, offsetY) != 0) {
                        float ratio = (entry.scale > 1e-5f) ? (scale / entry.scale) : 1f;
                        transformPolygonListInPlace(
                                entry.droplets, ratio, entry.offsetX, entry.offsetY, offsetX, offsetY);
                        entry.scale = scale;
                        entry.offsetX = offsetX;
                        entry.offsetY = offsetY;
                    }
                    if (Math.abs(now - entry.lastTranslatedSimTime) > 1e-4f) {
                        translateCachedPolygonsByLiveShipMotion(entry.droplets, scale);
                        entry.lastTranslatedSimTime = now;
                    }
                    outList.addAll(entry.droplets);
                }
                continue;
            }

            if (entry == null) {
                entry = new CachedDivisionGeometry();
                perDivisionGeometryCache.put(cacheKey, entry);
            } else {
                entry.droplets.clear();
            }
            buildSingleDivisionDroplets(battleGroup, div, isEnemy, playerFog, scale, offsetX, offsetY, entry.droplets);
            entry.signature = divSig;
            entry.positionSignature = divPosSig;
            entry.builtAtSimTime = now;
            entry.lastTranslatedSimTime = now;
            entry.scale = scale;
            entry.offsetX = offsetX;
            entry.offsetY = offsetY;
            if (!entry.droplets.isEmpty()) {
                outList.addAll(entry.droplets);
            }
            rebuiltCount++;
            if (rebuildBudget != null && rebuildBudget[0] > 0) {
                rebuildBudget[0]--;
            }
        }
        return rebuiltCount;
    }

    private void collectDivisionPolygons(
            BattleGroupState battleGroup,
            boolean isEnemy,
            FogOfWarAPI playerFog,
            float scale,
            float offsetX,
            float offsetY,
            List<RenderedDivisionPolygon> outList) {
        if (battleGroup == null || battleGroup.divisions.isEmpty()) return;
        for (DivisionState div : battleGroup.divisions) {
            buildSingleDivisionDroplets(battleGroup, div, isEnemy, playerFog, scale, offsetX, offsetY, outList);
        }
    }

    private void buildSingleDivisionDroplets(
            BattleGroupState battleGroup,
            DivisionState div,
            boolean isEnemy,
            FogOfWarAPI playerFog,
            float scale,
            float offsetX,
            float offsetY,
            List<RenderedDivisionPolygon> outList) {
        if (div == null) return;
        List<ShipAPI> alive = div.getAliveMembers();
        if (alive.isEmpty()) return;

        ShipAPI activeFlag = resolveActiveFlagship(div, alive);
        List<ShipAPI> hullMembers = new ArrayList<>(alive.size());
        if (!isEnemy) {
            for (ShipAPI s : alive) {
                if (s != null && !s.isFighter()) {
                    hullMembers.add(s);
                }
            }
        } else {
            for (ShipAPI s : alive) {
                if (s != null && !s.isFighter() && isEnemyShipVisibleOnCommandMap(this.engine, s, playerFog)) {
                    hullMembers.add(s);
                }
            }
        }
        if (hullMembers.isEmpty()) return;
        if (activeFlag == null || !hullMembers.contains(activeFlag)) {
            ShipAPI bestVisFlag = hullMembers.get(0);
            float bestVisFp = -1f;
            for (ShipAPI vis : hullMembers) {
                float fp = ShipCapabilityProfile.resolveShipFpStrength(vis);
                if (fp > bestVisFp) {
                    bestVisFp = fp;
                    bestVisFlag = vis;
                }
            }
            activeFlag = bestVisFlag;
        }

        boolean isAlliedAi = !isEnemy && (battleGroup.isAlliedAi || hullMembers.get(0).isAlly());

        float cr;
        float cg;
        float cb;
        if (isEnemy) {
            cr = 0.98f;
            cg = 0.22f;
            cb = 0.16f;
        } else if (isAlliedAi) {
            cr = 1.00f;
            cg = 0.82f;
            cb = 0.20f;
        } else {
            // Starsector-native phosphor tactical green (harmonized with friendly ship widgets, distinct from TSFC cyan)
            cr = 0.42f;
            cg = 0.96f;
            cb = 0.24f;
        }

        // Cluster division members into sticky water-droplet sub-blobs (hysteresis merge vs split thresholds)
        List<List<ShipAPI>> droplets = clusterDivisionMembersSticky(div, hullMembers);
        int primaryIdx = resolvePrimaryDropletIndex(droplets, activeFlag);

        for (int dIdx = 0; dIdx < droplets.size(); dIdx++) {
            List<ShipAPI> dropletMembers = droplets.get(dIdx);
            boolean isPrimary = (dIdx == primaryIdx);

            float liveRmsSpread = computeInstantaneousRmsSpreadWorld(dropletMembers);
            float overallRatio = liveRmsSpread / Math.max(300f, div.stationKeepingRadius);
            float scatterPenalty = clamp((overallRatio - 0.85f) / 1.25f, 0f, 1f);
            float targetCohesionAlpha = 1.0f - scatterPenalty * 0.18f;
            if (dropletMembers.size() == 1 && hullMembers.size() > 1) {
                targetCohesionAlpha *= 0.90f;
            }

            String cohKey = isPrimary ? div.id : (div.id + "#" + dIdx);
            Float prevCoh = smoothedCohesionByDivId.get(cohKey);
            float cohesionAlphaMult = (prevCoh == null)
                    ? targetCohesionAlpha
                    : (prevCoh * 0.84f + targetCohesionAlpha * 0.16f);
            smoothedCohesionByDivId.put(cohKey, cohesionAlphaMult);

            List<Vector2f> fallbackCircleOrHull = null;
            if (dropletMembers.size() == 1) {
                fallbackCircleOrHull = buildFluidDropletPolygonScreen(
                        div,
                        dropletMembers,
                        hullMembers,
                        scale,
                        offsetX,
                        offsetY);
            }
            List<Vector2f> displayPolyScreen = applyMetaballNeckPinchScreen(
                    div,
                    fallbackCircleOrHull,
                    null,
                    dropletMembers,
                    hullMembers,
                    scale,
                    offsetX,
                    offsetY);
            if (displayPolyScreen == null || displayPolyScreen.size() < 3) {
                displayPolyScreen = (fallbackCircleOrHull != null && fallbackCircleOrHull.size() >= 3)
                        ? fallbackCircleOrHull
                        : buildFluidDropletPolygonScreen(
                                div,
                                dropletMembers,
                                hullMembers,
                                scale,
                                offsetX,
                                offsetY);
            }
            if (displayPolyScreen == null || displayPolyScreen.size() < 3) continue;
            List<Vector2f> polyScreen = displayPolyScreen;
            Vector2f centerScreen = computePolygonCentroid(displayPolyScreen);

            outList.add(new RenderedDivisionPolygon(
                    div,
                    battleGroup,
                    isEnemy,
                    isAlliedAi,
                    isPrimary,
                    false,
                    dropletMembers,
                    null,
                    polyScreen,
                    displayPolyScreen,
                    centerScreen,
                    cr,
                    cg,
                    cb,
                    cohesionAlphaMult,
                    scale,
                    offsetX,
                    offsetY,
                    0));
        }
    }

    private void collectStrikeCraftSidePolygonsCached(
            BattleGroupState battleGroup,
            int voidstrikeSide,
            boolean isEnemy,
            boolean isAlliedAi,
            FogOfWarAPI playerFog,
            float now,
            boolean paused,
            float scale,
            float offsetX,
            float offsetY,
            List<RenderedDivisionPolygon> outStrikeList) {
        String groupKey = isEnemy ? "SC_E" : (isAlliedAi ? "SC_A" : "SC_P");
        CachedDivisionGeometry entry = perDivisionStrikeCraftCache.get(groupKey);
        int strikeSig = computeStrikeCraftSideSignature(battleGroup, voidstrikeSide, isEnemy, playerFog, false);
        int strikePosSig = computeStrikeCraftSideSignature(battleGroup, voidstrikeSide, isEnemy, playerFog, true);
        float simAge = (entry != null) ? Math.abs(now - entry.builtAtSimTime) : Float.MAX_VALUE;
        boolean withinTimeWindow = (entry != null) && (paused
                ? (entry.positionSignature == strikePosSig && simAge < 0.22f)
                : (simAge < 0.16f));
        if (entry != null && entry.signature == strikeSig && withinTimeWindow) {
            if (!entry.droplets.isEmpty()) {
                if (Float.compare(entry.scale, scale) != 0
                        || Float.compare(entry.offsetX, offsetX) != 0
                        || Float.compare(entry.offsetY, offsetY) != 0) {
                    float ratio = (entry.scale > 1e-5f) ? (scale / entry.scale) : 1f;
                    transformPolygonListInPlace(
                            entry.droplets, ratio, entry.offsetX, entry.offsetY, offsetX, offsetY);
                    entry.scale = scale;
                    entry.offsetX = offsetX;
                    entry.offsetY = offsetY;
                }
                if (Math.abs(now - entry.lastTranslatedSimTime) > 1e-4f) {
                    translateCachedPolygonsByLiveShipMotion(entry.droplets, scale);
                    entry.lastTranslatedSimTime = now;
                }
                outStrikeList.addAll(entry.droplets);
            }
            return;
        }
        if (entry == null) {
            entry = new CachedDivisionGeometry();
            perDivisionStrikeCraftCache.put(groupKey, entry);
        } else {
            entry.droplets.clear();
        }
        collectStrikeCraftSidePolygons(
                battleGroup, voidstrikeSide, isEnemy, isAlliedAi, playerFog, scale, offsetX, offsetY, entry.droplets);
        entry.signature = strikeSig;
        entry.positionSignature = strikePosSig;
        entry.builtAtSimTime = now;
        entry.lastTranslatedSimTime = now;
        entry.scale = scale;
        entry.offsetX = offsetX;
        entry.offsetY = offsetY;
        if (!entry.droplets.isEmpty()) {
            outStrikeList.addAll(entry.droplets);
        }
    }

    private int computeStrikeCraftSideSignature(
            BattleGroupState battleGroup,
            int voidstrikeSide,
            boolean isEnemy,
            FogOfWarAPI playerFog,
            boolean includeQuantizedPositions) {
        int h = 23 + voidstrikeSide;
        Vector2f scratch = new Vector2f();
        List<VoidstrikeCoordinator.VoidstrikeRecord> activeVs = VoidstrikeCoordinator.getActiveVoidstrikes();
        if (activeVs != null) {
            for (int i = 0, n = activeVs.size(); i < n; i++) {
                VoidstrikeCoordinator.VoidstrikeRecord vs = activeVs.get(i);
                if (vs == null || vs.side != voidstrikeSide || vs.activeFighters.isEmpty()) continue;
                h = 31 * h + vs.voidstrikeNumber;
                for (int j = 0, m = vs.activeFighters.size(); j < m; j++) {
                    ShipAPI fm = vs.activeFighters.get(j);
                    if (fm == null || !fm.isAlive() || fm.isHulk() || fm.isExpired()) continue;
                    if (isEnemy && !isEnemyShipVisibleOnCommandMap(this.engine, fm, playerFog)) continue;
                    com.fs.starfarer.api.combat.FighterWingAPI w = fm.getWing();
                    h = 31 * h + (w != null ? System.identityHashCode(w) : System.identityHashCode(fm));
                    if (includeQuantizedPositions && fm.getLocation() != null) {
                        h = 31 * h + Math.round(fm.getLocation().x * 0.25f);
                        h = 31 * h + Math.round(fm.getLocation().y * 0.25f);
                    }
                }
            }
        }
        if (battleGroup != null && !battleGroup.divisions.isEmpty()) {
            for (int d = 0, dn = battleGroup.divisions.size(); d < dn; d++) {
                DivisionState div = battleGroup.divisions.get(d);
                if (div == null) continue;
                for (int sIdx = 0, sm = div.members.size(); sIdx < sm; sIdx++) {
                    ShipAPI carrier = div.members.get(sIdx);
                    if (carrier == null || !carrier.isAlive() || carrier.isHulk()) continue;
                    List<com.fs.starfarer.api.combat.FighterWingAPI> wings = carrier.getAllWings();
                    if (wings == null || wings.isEmpty()) continue;
                    for (int wIdx = 0, wn = wings.size(); wIdx < wn; wIdx++) {
                        com.fs.starfarer.api.combat.FighterWingAPI wing = wings.get(wIdx);
                        if (wing == null) continue;
                        ShipAPI lead = BattlefieldFirepowerEvaluator.resolveAliveWingLeader(wing);
                        if (lead == null) continue;
                        Vector2f sqLoc = BattlefieldFirepowerEvaluator.resolveSquadronLiveCentroid(wing, lead, scratch);
                        if (carrier.getLocation() != null && DivisionUiWidgets.distance(sqLoc, carrier.getLocation()) <= 235f) {
                            continue;
                        }
                        if (isEnemy && !isEnemyShipVisibleOnCommandMap(this.engine, lead, playerFog)) continue;
                        h = 31 * h + System.identityHashCode(wing);
                        if (includeQuantizedPositions) {
                            h = 31 * h + Math.round(sqLoc.x * 0.25f);
                            h = 31 * h + Math.round(sqLoc.y * 0.25f);
                        }
                    }
                }
            }
        }
        return h;
    }

    /**
     * Feature A: Collects Strike Craft division polygons on their own dedicated layer so launched fighter/bomber
     * squadrons never conflict with warship division polygons or ship overlap stripes.
     *
     * Upgraded to whole-squadron geometry + Voidstrike package unification:
     *  1. Anchors every squadron at its live wing centroid ({@code resolveSquadronLiveCentroid}) with radius scaled
     *     by actual wing spread ({@code computeSquadronSpreadRadius}) so wingmates never spill outside their ring.
     *  2. Groups multi-division Voidstrikes into unified tangent-capsule metaball envelopes matching {@code VoidstrikeRecord}.
     *  3. Renders independent airborne CAP/Sweep/Recon squadrons via the same smooth metaball SDF (never bloated convex pills).
     */
    public void collectStrikeCraftDivisionPolygons(
            BattleGroupState battleGroup,
            boolean isEnemy,
            FogOfWarAPI playerFog,
            float scale,
            float offsetX,
            float offsetY,
            List<RenderedDivisionPolygon> outStrikeList) {
        if (battleGroup == null) return;
        boolean isAlliedAi = !isEnemy && battleGroup.isAlliedAi;
        int vsSide = isEnemy
                ? VoidstrikeCoordinator.VOIDSTRIKE_SIDE_ENEMY
                : (isAlliedAi ? VoidstrikeCoordinator.VOIDSTRIKE_SIDE_ALLIED : VoidstrikeCoordinator.VOIDSTRIKE_SIDE_PLAYER);
        collectStrikeCraftSidePolygons(
                battleGroup, vsSide, isEnemy, isAlliedAi, playerFog, scale, offsetX, offsetY, outStrikeList);
    }

    private void collectStrikeCraftSidePolygons(
            BattleGroupState battleGroup,
            int voidstrikeSide,
            boolean isEnemy,
            boolean isAlliedAi,
            FogOfWarAPI playerFog,
            float scale,
            float offsetX,
            float offsetY,
            List<RenderedDivisionPolygon> outStrikeList) {
        float cr, cg, cb;
        if (isEnemy) {
            // Hot interceptor amber-orange (instantly distinct from crimson surface divisions, zero pastel pink)
            cr = 1.00f;
            cg = 0.46f;
            cb = 0.18f;
        } else if (isAlliedAi) {
            // Luminous solar gold-ivory air track
            cr = 1.00f;
            cg = 0.88f;
            cb = 0.42f;
        } else {
            // Crisp sky-azure air track (distinct from green surface divisions and teal Space contours)
            cr = 0.26f;
            cg = 0.76f;
            cb = 1.00f;
        }

        Set<com.fs.starfarer.api.combat.FighterWingAPI> claimedWings =
                Collections.newSetFromMap(new java.util.IdentityHashMap<com.fs.starfarer.api.combat.FighterWingAPI, Boolean>());
        Set<ShipAPI> claimedStandaloneFighters =
                Collections.newSetFromMap(new java.util.IdentityHashMap<ShipAPI, Boolean>());
        Vector2f scratchCentroid = new Vector2f();

        // 1. Unified Voidstrike Air-Package Envelopes (multi-division strikes share cohesive metaball packages!)
        List<VoidstrikeCoordinator.VoidstrikeRecord> activeVs = VoidstrikeCoordinator.getActiveVoidstrikes();
        if (activeVs != null && !activeVs.isEmpty()) {
            for (int vIdx = 0, vLen = activeVs.size(); vIdx < vLen; vIdx++) {
                VoidstrikeCoordinator.VoidstrikeRecord vs = activeVs.get(vIdx);
                if (vs == null || vs.side != voidstrikeSide || vs.activeFighters.isEmpty()) continue;

                List<SquadronEnvelopeUnit> vsUnits = new ArrayList<>();
                for (int fIdx = 0, fLen = vs.activeFighters.size(); fIdx < fLen; fIdx++) {
                    ShipAPI fm = vs.activeFighters.get(fIdx);
                    if (fm == null || !fm.isAlive() || fm.isHulk() || fm.isExpired()) continue;
                    com.fs.starfarer.api.combat.FighterWingAPI wing = fm.getWing();
                    if (wing != null) {
                        if (claimedWings.contains(wing)) continue;
                        ShipAPI wLead = BattlefieldFirepowerEvaluator.resolveAliveWingLeader(wing);
                        if (wLead == null) continue;
                        if (isEnemy && !hasAnyVisibleFighterInWing(wing, playerFog)) continue;
                        claimedWings.add(wing);
                        Vector2f sqCenter = resolveVisibleSquadronCentroid(wing, wLead, isEnemy, playerFog, scratchCentroid);
                        float sqSpread = computeTrimmedSquadronSpreadRadius(wing, sqCenter);
                        float sqWorldR = clamp(150f + sqSpread * 1.02f, 175f, 335f);
                        vsUnits.add(new SquadronEnvelopeUnit(wing, wLead, sqCenter, sqWorldR, vs.voidstrikeNumber));
                    } else {
                        if (claimedStandaloneFighters.contains(fm)) continue;
                        if (isEnemy && !isEnemyShipVisibleOnCommandMap(this.engine, fm, playerFog)) continue;
                        claimedStandaloneFighters.add(fm);
                        if (fm.getLocation() != null) {
                            vsUnits.add(new SquadronEnvelopeUnit(null, fm, fm.getLocation(), 190f, vs.voidstrikeNumber));
                        }
                    }
                }
                if (vsUnits.isEmpty()) continue;

                DivisionState ownerDiv = null;
                if (!vs.participatingDivisions.isEmpty()) {
                    ownerDiv = vs.participatingDivisions.iterator().next();
                } else if (battleGroup != null && !battleGroup.divisions.isEmpty()) {
                    ownerDiv = battleGroup.divisions.get(0);
                }

                emitClusteredSquadronPolygons(
                        ownerDiv, battleGroup, isEnemy, isAlliedAi, vsUnits, 1.22f, vs.voidstrikeNumber,
                        cr, cg, cb, 0.94f, scale, offsetX, offsetY, outStrikeList);
            }
        }

        // 2. Non-Voidstrike Airborne Squadrons (deployed Sweep / Recon / Intercept wings outside carrier hull orbit)
        if (battleGroup != null && !battleGroup.divisions.isEmpty()) {
            List<SquadronEnvelopeUnit> capUnits = new ArrayList<>();
            DivisionState primaryCapDiv = battleGroup.divisions.get(0);
            for (int dIdx = 0, dLen = battleGroup.divisions.size(); dIdx < dLen; dIdx++) {
                DivisionState div = battleGroup.divisions.get(dIdx);
                if (div == null) continue;
                List<ShipAPI> alive = div.getAliveMembers();
                for (int sIdx = 0, sLen = alive.size(); sIdx < sLen; sIdx++) {
                    ShipAPI carrier = alive.get(sIdx);
                    if (carrier == null || !carrier.isAlive() || carrier.isHulk()) continue;
                    if (carrier.isFighter()) {
                        if (carrier.getWing() != null && claimedWings.contains(carrier.getWing())) continue;
                        if (claimedStandaloneFighters.contains(carrier)) continue;
                        if (!isEnemy || isEnemyShipVisibleOnCommandMap(this.engine, carrier, playerFog)) {
                            claimedStandaloneFighters.add(carrier);
                            if (carrier.getLocation() != null) {
                                capUnits.add(new SquadronEnvelopeUnit(carrier.getWing(), carrier, carrier.getLocation(), 190f, 0));
                                primaryCapDiv = div;
                            }
                        }
                        continue;
                    }
                    List<com.fs.starfarer.api.combat.FighterWingAPI> wings = carrier.getAllWings();
                    if (wings == null || wings.isEmpty()) continue;
                    for (int wIdx = 0, wLen = wings.size(); wIdx < wLen; wIdx++) {
                        com.fs.starfarer.api.combat.FighterWingAPI wing = wings.get(wIdx);
                        if (wing == null || claimedWings.contains(wing)) continue;
                        ShipAPI wLead = BattlefieldFirepowerEvaluator.resolveAliveWingLeader(wing);
                        if (wLead == null) continue;
                        if (isEnemy && !hasAnyVisibleFighterInWing(wing, playerFog)) continue;
                        Vector2f sqCenter = resolveVisibleSquadronCentroid(wing, wLead, isEnemy, playerFog, scratchCentroid);
                        // Suppress parked carrier-deck / inner-hull orbit wings so idle CAP doesn't double-ring the carrier
                        if (carrier.getLocation() != null
                                && DivisionUiWidgets.distance(sqCenter, carrier.getLocation()) <= 235f) {
                            continue;
                        }
                        claimedWings.add(wing);
                        float sqSpread = computeTrimmedSquadronSpreadRadius(wing, sqCenter);
                        float sqWorldR = clamp(150f + sqSpread * 1.02f, 175f, 325f);
                        capUnits.add(new SquadronEnvelopeUnit(wing, wLead, sqCenter, sqWorldR, 0));
                        primaryCapDiv = div;
                    }
                }
            }
            if (!capUnits.isEmpty()) {
                emitClusteredSquadronPolygons(
                        primaryCapDiv, battleGroup, isEnemy, isAlliedAi, capUnits, 1.08f, 0,
                        cr, cg, cb, 0.84f, scale, offsetX, offsetY, outStrikeList);
            }
        }
    }

    private boolean hasAnyVisibleFighterInWing(
            com.fs.starfarer.api.combat.FighterWingAPI wing,
            FogOfWarAPI playerFog) {
        if (wing == null) return false;
        if (playerFog == null) return true;
        List<ShipAPI> members = wing.getWingMembers();
        if (members != null) {
            for (int i = 0, n = members.size(); i < n; i++) {
                ShipAPI fm = members.get(i);
                if (fm != null && fm.isAlive() && !fm.isHulk() && isEnemyShipVisibleOnCommandMap(this.engine, fm, playerFog)) {
                    return true;
                }
            }
        }
        ShipAPI lead = wing.getLeader();
        return lead != null && lead.isAlive() && !lead.isHulk() && isEnemyShipVisibleOnCommandMap(this.engine, lead, playerFog);
    }

    private Vector2f resolveVisibleSquadronCentroid(
            com.fs.starfarer.api.combat.FighterWingAPI wing,
            ShipAPI fallbackLeader,
            boolean isEnemy,
            FogOfWarAPI playerFog,
            Vector2f out) {
        if (!isEnemy || playerFog == null) {
            return BattlefieldFirepowerEvaluator.resolveSquadronLiveCentroid(wing, fallbackLeader, out);
        }
        List<ShipAPI> members = wing != null ? wing.getWingMembers() : null;
        if (members != null && !members.isEmpty()) {
            float sx = 0f, sy = 0f;
            int cnt = 0;
            for (int i = 0, n = members.size(); i < n; i++) {
                ShipAPI fm = members.get(i);
                if (fm != null && fm.isAlive() && !fm.isHulk() && fm.getLocation() != null
                        && isEnemyShipVisibleOnCommandMap(this.engine, fm, playerFog)) {
                    sx += fm.getLocation().x;
                    sy += fm.getLocation().y;
                    cnt++;
                }
            }
            if (cnt > 0) {
                out.set(sx / cnt, sy / cnt);
                return out;
            }
        }
        return BattlefieldFirepowerEvaluator.resolveSquadronLiveCentroid(wing, fallbackLeader, out);
    }

    private static float computeTrimmedSquadronSpreadRadius(
            com.fs.starfarer.api.combat.FighterWingAPI wing,
            Vector2f centroid) {
        if (wing == null || centroid == null) return 0f;
        List<ShipAPI> members = wing.getWingMembers();
        if (members == null || members.size() <= 1) return 0f;
        float maxSq = 0f;
        float secondMaxSq = 0f;
        float sumSq = 0f;
        int cnt = 0;
        for (int i = 0, n = members.size(); i < n; i++) {
            ShipAPI fm = members.get(i);
            if (fm == null || !fm.isAlive() || fm.isHulk() || fm.getLocation() == null) continue;
            float dx = fm.getLocation().x - centroid.x;
            float dy = fm.getLocation().y - centroid.y;
            float dSq = dx * dx + dy * dy;
            sumSq += dSq;
            cnt++;
            if (dSq >= maxSq) {
                secondMaxSq = maxSq;
                maxSq = dSq;
            } else if (dSq > secondMaxSq) {
                secondMaxSq = dSq;
            }
        }
        if (cnt <= 1) return 0f;
        float maxD = (float) Math.sqrt(maxSq);
        if (cnt <= 3) {
            return Math.min(maxD, 220f);
        }
        // For 4+ fighter wings, cap single-straggler dogfighter outliers via 2nd-furthest + 1.55x RMS spread
        float secondD = (float) Math.sqrt(secondMaxSq);
        float rmsD = (float) Math.sqrt(sumSq / cnt);
        return Math.min(maxD, Math.max(secondD * 1.08f, rmsD * 1.55f));
    }

    private static boolean hasLargeSkinTopologyDeformation(List<RenderedDivisionPolygon> droplets) {
        if (droplets == null || droplets.isEmpty()) return false;
        for (int d = 0, dn = droplets.size(); d < dn; d++) {
            RenderedDivisionPolygon rp = droplets.get(d);
            if (rp == null || rp.skinUnitCount < 2 || rp.hullMembers == null) continue;
            int m = rp.hullMembers.size();
            float wx = 0f, wy = 0f;
            int cnt = 0;
            for (int i = 0; i < m; i++) {
                ShipAPI s = rp.hullMembers.get(i);
                if (s != null && s.isAlive() && s.getLocation() != null) {
                    wx += s.getLocation().x;
                    wy += s.getLocation().y;
                    cnt++;
                }
            }
            if (cnt < 2) continue;
            wx /= cnt;
            wy /= cnt;
            float sumSq = 0f;
            for (int i = 0; i < m; i++) {
                ShipAPI s = rp.hullMembers.get(i);
                if (s == null || !s.isAlive() || s.getLocation() == null) continue;
                float dx = s.getLocation().x - wx;
                float dy = s.getLocation().y - wy;
                sumSq += dx * dx + dy * dy;
            }
            float liveSpread = (float) Math.sqrt(sumSq / cnt);
            float baseSpread = Math.max(220f, rp.skinInitialSpreadWorld);
            if (Math.abs(liveSpread - rp.skinInitialSpreadWorld) > baseSpread * 0.36f) {
                return true;
            }
        }
        return false;
    }

    private void emitClusteredSquadronPolygons(
            DivisionState ownerDiv,
            BattleGroupState battleGroup,
            boolean isEnemy,
            boolean isAlliedAi,
            List<SquadronEnvelopeUnit> units,
            float mergeMult,
            int voidstrikeNumber,
            float cr,
            float cg,
            float cb,
            float cohesionAlpha,
            float scale,
            float offsetX,
            float offsetY,
            List<RenderedDivisionPolygon> outStrikeList) {
        int n = units.size();
        boolean[] visited = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (visited[i]) continue;
            List<SquadronEnvelopeUnit> cluster = new ArrayList<>();
            cluster.add(units.get(i));
            visited[i] = true;
            int head = 0;
            while (head < cluster.size()) {
                SquadronEnvelopeUnit curr = cluster.get(head++);
                for (int j = 0; j < n; j++) {
                    if (visited[j]) continue;
                    SquadronEnvelopeUnit cand = units.get(j);
                    float dx = curr.worldCenter.x - cand.worldCenter.x;
                    float dy = curr.worldCenter.y - cand.worldCenter.y;
                    float mergeDist = (curr.worldRadius + cand.worldRadius) * mergeMult;
                    if (dx * dx + dy * dy <= mergeDist * mergeDist) {
                        visited[j] = true;
                        cluster.add(cand);
                    }
                }
            }

            List<Vector2f> polyScreen = buildSquadronClusterMetaballPolygonScreen(cluster, scale, offsetX, offsetY);
            if (polyScreen == null || polyScreen.size() < 3) continue;
            Vector2f centerScreen = computePolygonCentroid(polyScreen);
            List<ShipAPI> repMembers = new ArrayList<>(cluster.size());
            for (int k = 0; k < cluster.size(); k++) {
                if (cluster.get(k).fallbackShip != null) {
                    repMembers.add(cluster.get(k).fallbackShip);
                }
            }
            outStrikeList.add(new RenderedDivisionPolygon(
                    ownerDiv,
                    battleGroup,
                    isEnemy,
                    isAlliedAi,
                    i == 0,
                    true,
                    repMembers,
                    cluster,
                    polyScreen,
                    polyScreen,
                    centerScreen,
                    cr,
                    cg,
                    cb,
                    cohesionAlpha,
                    scale,
                    offsetX,
                    offsetY,
                    voidstrikeNumber));
        }
    }

    private List<Vector2f> buildSquadronClusterMetaballPolygonScreen(
            List<SquadronEnvelopeUnit> cluster,
            float scale,
            float offsetX,
            float offsetY) {
        int mCount = cluster.size();
        if (mCount == 0) return Collections.emptyList();
        if (mCount == 1) {
            SquadronEnvelopeUnit u = cluster.get(0);
            float cx = u.worldCenter.x * scale + offsetX;
            float cy = u.worldCenter.y * scale + offsetY;
            float rPx = Math.max(6.5f, u.worldRadius * scale);
            int steps = 56;
            List<Vector2f> ring = new ArrayList<>(steps);
            for (int i = 0; i < steps; i++) {
                double ang = (2.0 * Math.PI * i) / steps;
                ring.add(new Vector2f(cx + (float) Math.cos(ang) * rPx, cy + (float) Math.sin(ang) * rPx));
            }
            return ring;
        }

        float[] sx = new float[mCount];
        float[] sy = new float[mCount];
        float[] sr = new float[mCount];
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < mCount; i++) {
            SquadronEnvelopeUnit u = cluster.get(i);
            sx[i] = u.worldCenter.x * scale + offsetX;
            sy[i] = u.worldCenter.y * scale + offsetY;
            sr[i] = Math.max(6.5f, u.worldRadius * scale);
            float pad = sr[i] * 1.45f + 16f;
            if (sx[i] - pad < minX) minX = sx[i] - pad;
            if (sx[i] + pad > maxX) maxX = sx[i] + pad;
            if (sy[i] - pad < minY) minY = sy[i] - pad;
            if (sy[i] + pad > maxY) maxY = sy[i] + pad;
        }

        float[][] kPair = new float[mCount][mCount];
        for (int i = 0; i < mCount; i++) {
            SquadronEnvelopeUnit ui = cluster.get(i);
            for (int j = i + 1; j < mCount; j++) {
                SquadronEnvelopeUnit uj = cluster.get(j);
                float dxW = ui.worldCenter.x - uj.worldCenter.x;
                float dyW = ui.worldCenter.y - uj.worldCenter.y;
                float distW = (float) Math.sqrt(dxW * dxW + dyW * dyW);
                boolean shortcut = false;
                float distSqIJ = distW * distW;
                for (int k = 0; k < mCount; k++) {
                    if (k == i || k == j) continue;
                    SquadronEnvelopeUnit uk = cluster.get(k);
                    float dikSq = (ui.worldCenter.x - uk.worldCenter.x) * (ui.worldCenter.x - uk.worldCenter.x)
                            + (ui.worldCenter.y - uk.worldCenter.y) * (ui.worldCenter.y - uk.worldCenter.y);
                    if (dikSq >= distSqIJ) continue;
                    float djkSq = (uj.worldCenter.x - uk.worldCenter.x) * (uj.worldCenter.x - uk.worldCenter.x)
                            + (uj.worldCenter.y - uk.worldCenter.y) * (uj.worldCenter.y - uk.worldCenter.y);
                    if (djkSq < distSqIJ && (dikSq + djkSq) < distSqIJ * 1.15f) {
                        shortcut = true;
                        break;
                    }
                }
                if (shortcut) continue;
                float sumR = ui.worldRadius + uj.worldRadius;
                float fullOpen = sumR * 0.88f;
                float maxBridge = sumR * 1.32f;
                if (distW <= maxBridge) {
                    float tOpen = clamp(1.0f - Math.max(0f, distW - fullOpen) / Math.max(1f, maxBridge - fullOpen), 0.08f, 1.0f);
                    kPair[i][j] = tOpen * (0.42f + 0.58f * tOpen);
                    kPair[j][i] = kPair[i][j];
                }
            }
        }

        float maxSpan = Math.max(maxX - minX, maxY - minY);
        float stepPx = Math.max(2.6f, maxSpan / 64f);
        float minWaistPx = Math.max(3.2f, stepPx * 1.05f);
        List<List<Vector2f>> loops = traceMetaballContoursMarchingSquares(
                sx, sy, sr, mCount, mCount, kPair, minX, maxX, minY, maxY, stepPx, minWaistPx);
        List<Vector2f> best = selectBestContainingLoop(loops, sx, sy, mCount);
        float activeWaistPx = minWaistPx;
        if (best == null || countContainedShips(best, sx, sy, mCount) < mCount) {
            activeWaistPx = stepPx * 1.45f;
            loops = traceMetaballContoursMarchingSquares(
                    sx, sy, sr, mCount, mCount, kPair, minX, maxX, minY, maxY, stepPx, activeWaistPx);
            List<Vector2f> retry = selectBestContainingLoop(loops, sx, sy, mCount);
            if (retry != null) {
                best = retry;
            }
        }
        int targetMaxVerts = Math.min(136, Math.max(72, mCount * 18));
        if (best != null && best.size() >= 6) {
            PrecomputedSdfPrimitives prims = new PrecomputedSdfPrimitives(sx, sy, sr, mCount, mCount, kPair, activeWaistPx);
            return projectAndResampleSdfContour(best, sx, sy, sr, mCount, prims, stepPx, targetMaxVerts);
        }
        List<Vector2f> cloud = new ArrayList<>(mCount * 28);
        for (int i = 0; i < mCount; i++) {
            for (int a = 0; a < 28; a++) {
                double ang = (2.0 * Math.PI * a) / 28.0;
                cloud.add(new Vector2f(sx[i] + (float) Math.cos(ang) * sr[i], sy[i] + (float) Math.sin(ang) * sr[i]));
            }
        }
        List<Vector2f> hull = computeConvexHullCCW(cloud);
        if (hull.size() < 3) return hull;
        return simplifyContourForTriangulation(chaikinSmoothClosed(chaikinSmoothClosed(hull)), 0.45f, targetMaxVerts);
    }

    private int computeBattleGroupsGeometrySignature(
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup,
            FogOfWarAPI playerFog,
            boolean includeQuantizedPositions) {
        int h = 17;
        h = 31 * h + computeSingleGroupGeometrySignature(playerGroup, false, null, includeQuantizedPositions);
        h = 31 * h + computeSingleGroupGeometrySignature(alliedAiGroup, false, null, includeQuantizedPositions);
        h = 31 * h + computeSingleGroupGeometrySignature(enemyGroup, true, playerFog, includeQuantizedPositions);
        h = 31 * h + computeStrikeCraftSideSignature(
                playerGroup, VoidstrikeCoordinator.VOIDSTRIKE_SIDE_PLAYER, false, null, includeQuantizedPositions);
        h = 31 * h + computeStrikeCraftSideSignature(
                alliedAiGroup, VoidstrikeCoordinator.VOIDSTRIKE_SIDE_ALLIED, false, null, includeQuantizedPositions);
        h = 31 * h + computeStrikeCraftSideSignature(
                enemyGroup, VoidstrikeCoordinator.VOIDSTRIKE_SIDE_ENEMY, true, playerFog, includeQuantizedPositions);
        return h;
    }

    private int computeSingleGroupGeometrySignature(
            BattleGroupState group,
            boolean isEnemy,
            FogOfWarAPI playerFog,
            boolean includeQuantizedPositions) {
        if (group == null || group.divisions.isEmpty()) return 0;
        int h = group.divisions.size();
        for (int i = 0, n = group.divisions.size(); i < n; i++) {
            DivisionState div = group.divisions.get(i);
            if (div == null) continue;
            h = 31 * h + (div.id != null ? div.id.hashCode() : i);
            for (int j = 0, m = div.members.size(); j < m; j++) {
                ShipAPI s = div.members.get(j);
                if (s == null || !s.isAlive() || s.isHulk() || s.isFighter()) continue;
                if (isEnemy && !isEnemyShipVisibleOnCommandMap(this.engine, s, playerFog)) continue;
                h = 31 * h + (s.getId() != null ? s.getId().hashCode() : j);
                if (includeQuantizedPositions && s.getLocation() != null) {
                    h = 31 * h + Math.round(s.getLocation().x);
                    h = 31 * h + Math.round(s.getLocation().y);
                }
            }
        }
        return h;
    }

    private void transformCachedGeometryInPlace(float newScale, float newOffsetX, float newOffsetY) {
        if (cachedPolyScale <= 1e-5f || Float.isNaN(cachedPolyScale)) return;
        float ratio = newScale / cachedPolyScale;
        float oldOffX = cachedPolyOffsetX;
        float oldOffY = cachedPolyOffsetY;
        transformPolygonListInPlace(cachedRenderedPolys, ratio, oldOffX, oldOffY, newOffsetX, newOffsetY);
        transformPolygonListInPlace(cachedStrikeCraftPolys, ratio, oldOffX, oldOffY, newOffsetX, newOffsetY);
        for (CachedDivisionGeometry entry : perDivisionGeometryCache.values()) {
            entry.scale = newScale;
            entry.offsetX = newOffsetX;
            entry.offsetY = newOffsetY;
        }
        for (CachedDivisionGeometry entry : perDivisionStrikeCraftCache.values()) {
            entry.scale = newScale;
            entry.offsetX = newOffsetX;
            entry.offsetY = newOffsetY;
        }
        for (CachedOverlapStripeBand band : cachedOverlapStripeBands) {
            for (Vector2f v : band.verts) {
                v.x = (v.x - oldOffX) * ratio + newOffsetX;
                v.y = (v.y - oldOffY) * ratio + newOffsetY;
            }
            band.anchorMidX = (band.anchorMidX - oldOffX) * ratio + newOffsetX;
            band.anchorMidY = (band.anchorMidY - oldOffY) * ratio + newOffsetY;
        }
    }

    private static void transformPolygonListInPlace(
            List<RenderedDivisionPolygon> polys,
            float ratio,
            float oldOffX,
            float oldOffY,
            float newOffX,
            float newOffY) {
        for (RenderedDivisionPolygon rp : polys) {
            for (Vector2f v : rp.polyScreen) {
                v.x = (v.x - oldOffX) * ratio + newOffX;
                v.y = (v.y - oldOffY) * ratio + newOffY;
            }
            if (rp.displayPolyScreen != rp.polyScreen) {
                for (Vector2f v : rp.displayPolyScreen) {
                    v.x = (v.x - oldOffX) * ratio + newOffX;
                    v.y = (v.y - oldOffY) * ratio + newOffY;
                }
            }
            if (rp.innerGlowPolyScreen != null
                    && rp.innerGlowPolyScreen != rp.displayPolyScreen
                    && rp.innerGlowPolyScreen != rp.polyScreen) {
                for (Vector2f v : rp.innerGlowPolyScreen) {
                    v.x = (v.x - oldOffX) * ratio + newOffX;
                    v.y = (v.y - oldOffY) * ratio + newOffY;
                }
            }
            if (rp.clipHullScreen != null && rp.clipHullScreen != rp.polyScreen) {
                for (Vector2f v : rp.clipHullScreen) {
                    v.x = (v.x - oldOffX) * ratio + newOffX;
                    v.y = (v.y - oldOffY) * ratio + newOffY;
                }
            }
            if (rp.centerScreen != null) {
                rp.centerScreen.x = (rp.centerScreen.x - oldOffX) * ratio + newOffX;
                rp.centerScreen.y = (rp.centerScreen.y - oldOffY) * ratio + newOffY;
            }
            rp.labelX = (rp.labelX - oldOffX) * ratio + newOffX;
            rp.labelY = (rp.labelY - oldOffY) * ratio + newOffY;
            rp.recomputeBounds();
        }
    }

    /**
     * 60 FPS Continuous Linear-Blend Skinning (LBS) & Live Motion Updater:
     *  - Single-unit envelopes translate rigidly with exact live ship/squadron centroid motion.
     *  - Multi-ship divisions & multi-squadron Voidstrikes deform every contour & inner-gradient vertex
     *    smoothly at 60 FPS using precomputed inverse-distance unit skinning weights (zero heap allocations),
     *    so turning/wheeling formations never poke hulls outside frozen walls between SDF cache rebuilds!
     */
    private static void translateCachedPolygonsByLiveShipMotion(
            List<RenderedDivisionPolygon> polys,
            float scale) {
        Vector2f sqScratch = null;
        for (int pIdx = 0, pLen = polys.size(); pIdx < pLen; pIdx++) {
            RenderedDivisionPolygon rp = polys.get(pIdx);
            int uCount = rp.skinUnitCount;
            if (uCount <= 0) continue;

            // 1. Multi-unit LBS per-ship / per-squadron deformation path
            if (uCount >= 2 && rp.skinWeights != null && rp.skinLastWorldX != null && rp.skinDeltaPxX != null) {
                float maxDeltaPx = 0f;
                float sumWx = 0f, sumWy = 0f;
                int validCnt = 0;
                if (rp.squadronUnits != null && rp.squadronUnits.size() == uCount) {
                    if (sqScratch == null) sqScratch = new Vector2f();
                    for (int u = 0; u < uCount; u++) {
                        SquadronEnvelopeUnit su = rp.squadronUnits.get(u);
                        Vector2f liveLoc = su.resolveLiveWorldCenter(sqScratch);
                        float nx = (liveLoc != null) ? liveLoc.x : rp.skinLastWorldX[u];
                        float ny = (liveLoc != null) ? liveLoc.y : rp.skinLastWorldY[u];
                        float uxPx = (nx - rp.skinLastWorldX[u]) * scale;
                        float uyPx = (ny - rp.skinLastWorldY[u]) * scale;
                        rp.skinDeltaPxX[u] = uxPx;
                        rp.skinDeltaPxY[u] = uyPx;
                        float dPx = Math.max(Math.abs(uxPx), Math.abs(uyPx));
                        if (dPx > maxDeltaPx) maxDeltaPx = dPx;
                        sumWx += nx;
                        sumWy += ny;
                        validCnt++;
                    }
                } else if (rp.hullMembers != null && rp.hullMembers.size() == uCount) {
                    for (int u = 0; u < uCount; u++) {
                        ShipAPI s = rp.hullMembers.get(u);
                        float nx = (s != null && s.isAlive() && s.getLocation() != null)
                                ? s.getLocation().x
                                : rp.skinLastWorldX[u];
                        float ny = (s != null && s.isAlive() && s.getLocation() != null)
                                ? s.getLocation().y
                                : rp.skinLastWorldY[u];
                        float uxPx = (nx - rp.skinLastWorldX[u]) * scale;
                        float uyPx = (ny - rp.skinLastWorldY[u]) * scale;
                        rp.skinDeltaPxX[u] = uxPx;
                        rp.skinDeltaPxY[u] = uyPx;
                        float dPx = Math.max(Math.abs(uxPx), Math.abs(uyPx));
                        if (dPx > maxDeltaPx) maxDeltaPx = dPx;
                        if (s != null && s.isAlive() && s.getLocation() != null) {
                            sumWx += nx;
                            sumWy += ny;
                            validCnt++;
                        }
                    }
                }
                if (maxDeltaPx < 0.05f || validCnt <= 0) continue;

                float avgWx = sumWx / validCnt;
                float avgWy = sumWy / validCnt;
                float avgDxPx = (avgWx - rp.lastWorldCenterX) * scale;
                float avgDyPx = (avgWy - rp.lastWorldCenterY) * scale;
                rp.lastWorldCenterX = avgWx;
                rp.lastWorldCenterY = avgWy;

                int nVerts = rp.displayPolyScreen.size();
                boolean sharedPoly = (rp.polyScreen == rp.displayPolyScreen);
                boolean hasInner = (rp.innerGlowPolyScreen != null
                        && rp.innerGlowPolyScreen != rp.displayPolyScreen
                        && rp.innerGlowPolyScreen.size() == nVerts);
                float[] weights = rp.skinWeights;
                for (int vIdx = 0; vIdx < nVerts; vIdx++) {
                    float vDx = 0f;
                    float vDy = 0f;
                    int wBase = vIdx * uCount;
                    for (int u = 0; u < uCount; u++) {
                        float w = weights[wBase + u];
                        if (w <= 0f) continue;
                        vDx += w * rp.skinDeltaPxX[u];
                        vDy += w * rp.skinDeltaPxY[u];
                    }
                    Vector2f dv = rp.displayPolyScreen.get(vIdx);
                    dv.x += vDx;
                    dv.y += vDy;
                    if (!sharedPoly && vIdx < rp.polyScreen.size()) {
                        Vector2f pv = rp.polyScreen.get(vIdx);
                        pv.x += vDx;
                        pv.y += vDy;
                    }
                    if (hasInner) {
                        Vector2f iv = rp.innerGlowPolyScreen.get(vIdx);
                        iv.x += vDx;
                        iv.y += vDy;
                    }
                }
                if (scale > 1e-6f) {
                    float invScale = 1.0f / scale;
                    for (int u = 0; u < uCount; u++) {
                        rp.skinLastWorldX[u] += rp.skinDeltaPxX[u] * invScale;
                        rp.skinLastWorldY[u] += rp.skinDeltaPxY[u] * invScale;
                    }
                }
                if (rp.clipHullScreen != null && rp.clipHullScreen != rp.polyScreen) {
                    for (int i = 0, n = rp.clipHullScreen.size(); i < n; i++) {
                        Vector2f v = rp.clipHullScreen.get(i);
                        v.x += avgDxPx;
                        v.y += avgDyPx;
                    }
                }
                if (rp.centerScreen != null) {
                    rp.centerScreen.x += avgDxPx;
                    rp.centerScreen.y += avgDyPx;
                }
                rp.labelX += avgDxPx;
                rp.labelY += avgDyPx;
                rp.recomputeBounds();
                continue;
            }

            // 2. Single-unit fast rigid translation path (supports both lone ships and lone squadrons)
            float wx = 0f, wy = 0f;
            int cnt = 0;
            if (rp.squadronUnits != null && !rp.squadronUnits.isEmpty()) {
                if (sqScratch == null) sqScratch = new Vector2f();
                for (int i = 0, n = rp.squadronUnits.size(); i < n; i++) {
                    Vector2f loc = rp.squadronUnits.get(i).resolveLiveWorldCenter(sqScratch);
                    if (loc != null) {
                        wx += loc.x;
                        wy += loc.y;
                        cnt++;
                    }
                }
            } else if (rp.hullMembers != null && !rp.hullMembers.isEmpty()) {
                for (int i = 0, n = rp.hullMembers.size(); i < n; i++) {
                    ShipAPI s = rp.hullMembers.get(i);
                    if (s != null && s.isAlive() && s.getLocation() != null) {
                        wx += s.getLocation().x;
                        wy += s.getLocation().y;
                        cnt++;
                    }
                }
            }
            if (cnt <= 0) continue;
            wx /= cnt;
            wy /= cnt;
            float dxPx = (wx - rp.lastWorldCenterX) * scale;
            float dyPx = (wy - rp.lastWorldCenterY) * scale;
            if (Math.abs(dxPx) < 0.05f && Math.abs(dyPx) < 0.05f) continue;
            rp.lastWorldCenterX = wx;
            rp.lastWorldCenterY = wy;
            for (int i = 0, n = rp.polyScreen.size(); i < n; i++) {
                Vector2f v = rp.polyScreen.get(i);
                v.x += dxPx;
                v.y += dyPx;
            }
            if (rp.displayPolyScreen != rp.polyScreen) {
                for (int i = 0, n = rp.displayPolyScreen.size(); i < n; i++) {
                    Vector2f v = rp.displayPolyScreen.get(i);
                    v.x += dxPx;
                    v.y += dyPx;
                }
            }
            if (rp.innerGlowPolyScreen != null
                    && rp.innerGlowPolyScreen != rp.displayPolyScreen
                    && rp.innerGlowPolyScreen != rp.polyScreen) {
                for (int i = 0, n = rp.innerGlowPolyScreen.size(); i < n; i++) {
                    Vector2f v = rp.innerGlowPolyScreen.get(i);
                    v.x += dxPx;
                    v.y += dyPx;
                }
            }
            if (rp.clipHullScreen != null && rp.clipHullScreen != rp.polyScreen) {
                for (int i = 0, n = rp.clipHullScreen.size(); i < n; i++) {
                    Vector2f v = rp.clipHullScreen.get(i);
                    v.x += dxPx;
                    v.y += dyPx;
                }
            }
            if (rp.centerScreen != null) {
                rp.centerScreen.x += dxPx;
                rp.centerScreen.y += dyPx;
            }
            rp.labelX += dxPx;
            rp.labelY += dyPx;
            rp.minX += dxPx;
            rp.maxX += dxPx;
            rp.minY += dyPx;
            rp.maxY += dyPx;
        }
    }

    /**
     * Groups a division's visible alive ships into sticky connected sub-clusters ("water droplets"):
     *  - Ships within mergeDist coalesce into the same droplet.
     *  - Ships already in the same droplet stay connected up to splitDist = 1.50 * mergeDist (sticky hysteresis),
     *    splitting apart cleanly when dispersed (e.g. reinforcements arriving from the map edge) and
     *    re-combining like drops of water when they approach within mergeDist.
     */
    public List<List<ShipAPI>> clusterDivisionMembersSticky(DivisionState div, List<ShipAPI> members) {
        int n = members.size();
        if (n <= 1) {
            List<List<ShipAPI>> single = new ArrayList<>(1);
            if (n == 1) {
                single.add(new ArrayList<>(members));
            }
            return single;
        }

        String divId = (div != null && div.id != null) ? div.id : "default_div";
        float baseSpacing = (div != null && div.stationSpacing > 0f) ? div.stationSpacing : 450f;
        boolean firstSeenDiv = !stickyConnectedPairsByDivId.containsKey(divId);
        Set<String> prevPairs = stickyConnectedPairsByDivId.get(divId);
        if (prevPairs == null) {
            prevPairs = Collections.emptySet();
        }

        boolean[][] adj = new boolean[n][n];
        Set<String> nextPairs = new HashSet<>();
        for (int i = 0; i < n; i++) {
            adj[i][i] = true;
            ShipAPI a = members.get(i);
            float ra = getMemberFootprintRadiusWorld(a);
            for (int j = i + 1; j < n; j++) {
                ShipAPI b = members.get(j);
                float rb = getMemberFootprintRadiusWorld(b);
                float dist = DivisionUiWidgets.distance(a.getLocation(), b.getLocation());
                float mergeDist = (ra + rb) * 1.18f;
                float splitMult = (div != null && div.side == 1) ? 1.38f : 1.24f;
                float splitDist = mergeDist * splitMult;
                String key = canonicalPairKey(a.getId(), b.getId());
                String bondKey = divId + ":" + key;
                boolean wasConnected = prevPairs.contains(key);
                Float prevOpen = smoothedBondOpennessByPairKey.get(bondKey);

                // Suppress redundant shortcut chords across an intermediate stepping-stone ship k (Gabriel / RNG condition)
                boolean shortcutChord = isRedundantShortcutChordWorld(members, i, j, dist);
                boolean connectedNow;
                if (shortcutChord) {
                    connectedNow = false;
                } else if (!wasConnected) {
                    connectedNow = (dist <= mergeDist);
                } else if (dist <= splitDist) {
                    connectedNow = true;
                } else if (dist <= splitDist * 1.16f && prevOpen != null && prevOpen > 0.08f) {
                    // Hold the liquid thread for a brief neck-down transition if it was still thick when crossing splitDist
                    connectedNow = true;
                } else {
                    connectedNow = false;
                }

                if (connectedNow) {
                    adj[i][j] = true;
                    adj[j][i] = true;
                    nextPairs.add(key);
                    float fullOpenDist = (ra + rb) * 0.88f;
                    float geomOpenness;
                    if (dist > splitDist) {
                        geomOpenness = 0.02f;
                    } else if (dist <= fullOpenDist) {
                        geomOpenness = 1.0f;
                    } else {
                        float tOpen = clamp(1.0f - (dist - fullOpenDist) / Math.max(1f, splitDist - fullOpenDist), 0.02f, 1.0f);
                        geomOpenness = Math.max(0.02f, tOpen * (0.35f + 0.65f * tOpen));
                    }
                    float nextOpen;
                    if (firstSeenDiv) {
                        // On the very first frame a formed division is seen on the Tab map, use its natural geometric openness
                        nextOpen = geomOpenness;
                    } else if (!wasConnected || prevOpen == null) {
                        // When two previously separated droplets newly coalesce in battle, start at the tiniest point of contact (0.02f)
                        nextOpen = 0.02f;
                    } else if (dist > splitDist) {
                        // Rapidly neck down the stretched capillary thread right before snapping
                        nextOpen = prevOpen * 0.42f - 0.03f;
                    } else if (geomOpenness < prevOpen) {
                        // Smooth elastic liquid pull as ships stretch apart toward splitDist
                        nextOpen = prevOpen + (geomOpenness - prevOpen) * 0.32f - 0.01f;
                    } else {
                        // Smooth liquid relaxation as ships settle into formation
                        nextOpen = prevOpen + (geomOpenness - prevOpen) * 0.22f;
                    }
                    smoothedBondOpennessByPairKey.put(bondKey, clamp(nextOpen, 0.02f, 1.0f));
                    smoothedMeniscusReachByPairKey.remove(bondKey);
                } else {
                    smoothedBondOpennessByPairKey.remove(bondKey);
                    if (shortcutChord) {
                        smoothedMeniscusReachByPairKey.remove(bondKey);
                        continue;
                    }
                    float attractMaxDist = mergeDist * 1.24f;
                    float geomReach = 0f;
                    if (!wasConnected && dist < attractMaxDist) {
                        float tReach = clamp(1.0f - (dist - mergeDist) / Math.max(1f, attractMaxDist - mergeDist), 0f, 1f);
                        geomReach = tReach * tReach * (3f - 2f * tReach) * 0.92f;
                    }
                    float nextReach;
                    if (wasConnected) {
                        // Just snapped apart! Initialize the recoiling meniscus tails at 0.92f of P_kiss ("The Pop")
                        nextReach = 0.92f;
                    } else {
                        Float prevReachObj = smoothedMeniscusReachByPairKey.get(bondKey);
                        float prevReach = (prevReachObj != null) ? prevReachObj : (firstSeenDiv ? geomReach : 0f);
                        if (prevReach > geomReach) {
                            // Smooth surface-tension recoil pulling the snapped tail back into the droplet circle
                            nextReach = geomReach + (prevReach - geomReach) * 0.72f - 0.03f;
                        } else {
                            // Smooth surface-tension extension as two droplets approach to kiss
                            nextReach = prevReach + (geomReach - prevReach) * 0.28f;
                        }
                    }
                    if (nextReach > 0.02f && dist < splitDist * 1.45f) {
                        smoothedMeniscusReachByPairKey.put(bondKey, clamp(nextReach, 0f, 0.92f));
                    } else {
                        smoothedMeniscusReachByPairKey.remove(bondKey);
                    }
                }
            }
        }

        boolean[] visited = new boolean[n];
        List<List<ShipAPI>> clusters = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (visited[i]) continue;
            List<Integer> compIndices = new ArrayList<>();
            compIndices.add(i);
            visited[i] = true;
            int head = 0;
            while (head < compIndices.size()) {
                int curr = compIndices.get(head++);
                for (int nb = 0; nb < n; nb++) {
                    if (!visited[nb] && adj[curr][nb]) {
                        visited[nb] = true;
                        compIndices.add(nb);
                    }
                }
            }
            List<ShipAPI> cluster = new ArrayList<>(compIndices.size());
            for (int idx : compIndices) {
                cluster.add(members.get(idx));
            }
            clusters.add(cluster);
        }

        stickyConnectedPairsByDivId.put(divId, nextPairs);
        return clusters;
    }

    /**
     * Returns true if ship pair (i, j) is bypassed by an intermediate stepping-stone ship k in the same division
     * (Gabriel Graph / Relative Neighborhood Graph condition: d(i,k)^2 + d(j,k)^2 < d(i,j)^2 * 1.12).
     * Suppressing shortcut chords prevents L-shaped, curved, or linear formations from stringing thin bow-string
     * bridges across their concave side or trapping interior holes.
     */
    private static boolean isRedundantShortcutChordWorld(List<ShipAPI> members, int i, int j, float distIJ) {
        int n = members.size();
        if (n <= 2 || distIJ <= 1e-3f) return false;
        Vector2f pi = members.get(i).getLocation();
        Vector2f pj = members.get(j).getLocation();
        float distSqIJ = distIJ * distIJ;
        for (int k = 0; k < n; k++) {
            if (k == i || k == j) continue;
            Vector2f pk = members.get(k).getLocation();
            float dxik = pi.x - pk.x;
            float dyik = pi.y - pk.y;
            float dikSq = dxik * dxik + dyik * dyik;
            if (dikSq >= distSqIJ) continue;
            float dxjk = pj.x - pk.x;
            float dyjk = pj.y - pk.y;
            float djkSq = dxjk * dxjk + dyjk * dyjk;
            if (djkSq < distSqIJ && (dikSq + djkSq) < distSqIJ * 1.12f) {
                return true;
            }
        }
        return false;
    }

    private static String canonicalPairKey(String idA, String idB) {
        if (idA == null) idA = "";
        if (idB == null) idB = "";
        return (idA.compareTo(idB) <= 0) ? (idA + "|" + idB) : (idB + "|" + idA);
    }

    private static int resolvePrimaryDropletIndex(List<List<ShipAPI>> droplets, ShipAPI activeFlag) {
        if (droplets.isEmpty()) return 0;
        if (activeFlag != null) {
            for (int i = 0; i < droplets.size(); i++) {
                if (droplets.get(i).contains(activeFlag)) {
                    return i;
                }
            }
        }
        int bestIdx = 0;
        int bestSize = -1;
        for (int i = 0; i < droplets.size(); i++) {
            int sz = droplets.get(i).size();
            if (sz > bestSize) {
                bestSize = sz;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static float computeInstantaneousRmsSpreadWorld(List<ShipAPI> members) {
        int n = members.size();
        if (n <= 1) return 0f;
        float cx = 0f;
        float cy = 0f;
        for (ShipAPI s : members) {
            cx += s.getLocation().x;
            cy += s.getLocation().y;
        }
        cx /= n;
        cy /= n;
        float sumSq = 0f;
        for (ShipAPI s : members) {
            float dx = s.getLocation().x - cx;
            float dy = s.getLocation().y - cy;
            sumSq += (dx * dx + dy * dy);
        }
        return (float) Math.sqrt(sumSq / n);
    }

    public float resolvePairBondOpenness(DivisionState div, ShipAPI a, ShipAPI b) {
        if (a == null || b == null) return 1.0f;
        String divId = (div != null && div.id != null) ? div.id : "default_div";
        String bondKey = divId + ":" + canonicalPairKey(a.getId(), b.getId());
        Float smoothed = smoothedBondOpennessByPairKey.get(bondKey);
        if (smoothed != null) {
            return clamp(smoothed, 0.02f, 1.0f);
        }
        float ra = getMemberFootprintRadiusWorld(a);
        float rb = getMemberFootprintRadiusWorld(b);
        float dist = DivisionUiWidgets.distance(a.getLocation(), b.getLocation());
        float mergeDist = (ra + rb) * 1.18f;
        float splitDist = mergeDist * 1.24f;
        float fullOpenDist = (ra + rb) * 0.72f;
        float tOpen = clamp(1.0f - (dist - fullOpenDist) / Math.max(1f, splitDist - fullOpenDist), 0.02f, 1.0f);
        return Math.max(0.02f, tOpen * tOpen);
    }

    public float resolvePairMeniscusReach(DivisionState div, ShipAPI a, ShipAPI b) {
        if (a == null || b == null) return 0f;
        String divId = (div != null && div.id != null) ? div.id : "default_div";
        String bondKey = divId + ":" + canonicalPairKey(a.getId(), b.getId());
        Float smoothed = smoothedMeniscusReachByPairKey.get(bondKey);
        if (smoothed != null) {
            return clamp(smoothed, 0f, 0.92f);
        }
        float ra = getMemberFootprintRadiusWorld(a);
        float rb = getMemberFootprintRadiusWorld(b);
        float dist = DivisionUiWidgets.distance(a.getLocation(), b.getLocation());
        float mergeDist = (ra + rb) * 1.18f;
        float attractMaxDist = mergeDist * 1.24f;
        if (dist > mergeDist && dist < attractMaxDist) {
            float t = clamp(1.0f - (dist - mergeDist) / Math.max(1f, attractMaxDist - mergeDist), 0f, 1f);
            return t * t * (3f - 2f * t) * 0.92f;
        }
        return 0f;
    }

    /**
     * Builds a smooth, fluid CCW polygon in screen space around a division droplet's member hulls,
     * including surface-tension metaball attraction toward sibling droplets of the same division
     * when approaching/splitting so droplets kiss at the tiniest point of contact (P_kiss) and
     * combine/separate without any popping.
     */
    public List<Vector2f> buildFluidDropletPolygonScreen(
            DivisionState div,
            List<ShipAPI> dropletMembers,
            List<ShipAPI> allDivisionMembers,
            float scale,
            float offsetX,
            float offsetY) {

        List<Vector2f> cloud = new ArrayList<>(dropletMembers.size() * HULL_SAMPLE_POINTS_PER_SHIP);
        for (ShipAPI m : dropletMembers) {
            float mx = m.getLocation().x * scale + offsetX;
            float my = m.getLocation().y * scale + offsetY;
            float worldR = getMemberFootprintRadiusWorld(m);
            // Pure world-space radius scaled linearly with map zoom (only tiny 5.5px floor at extreme zoom-out),
            // matching extractMetaballDropletPolygonScreen and TsfcWarroomRenderer with zero zoom-clamp popping!
            float rPx = Math.max(5.5f, worldR * scale);

            // Check if a sibling ship in another droplet of the same division is near the coalescence/split threshold
            // and extend a pointed teardrop meniscus tip toward the exact radius-weighted kissing point P_kiss.
            float pullDirX = 0f;
            float pullDirY = 0f;
            float pullMagPx = 0f;
            if (allDivisionMembers != null && allDivisionMembers.size() > dropletMembers.size()) {
                float bestPull = 0f;
                for (ShipAPI other : allDivisionMembers) {
                    if (dropletMembers.contains(other)) continue;
                    float dxW = other.getLocation().x - m.getLocation().x;
                    float dyW = other.getLocation().y - m.getLocation().y;
                    float distW = (float) Math.sqrt(dxW * dxW + dyW * dyW);
                    if (distW < 1e-3f) continue;
                    float otherWorldR = getMemberFootprintRadiusWorld(other);
                    float otherRPx = Math.max(5.5f, otherWorldR * scale);
                    float mergeDist = (getMemberFootprintRadiusWorld(m) + otherWorldR) * 1.18f;
                    float splitDist = mergeDist * 1.15f;
                    float attractMaxDist = splitDist * 1.12f;
                    if (distW < attractMaxDist) {
                        float t = clamp(1.0f - (distW - mergeDist) / Math.max(1f, attractMaxDist - mergeDist), 0f, 1f);
                        float smooth = t * t * (3f - 2f * t);
                        if (smooth > bestPull) {
                            bestPull = smooth;
                            pullDirX = dxW / distW;
                            pullDirY = dyW / distW;
                            float distPx = distW * scale;
                            float distToKissPx = distPx * (rPx / Math.max(1f, rPx + otherRPx));
                            float maxTipReachPx = Math.min(rPx * 0.28f, Math.max(0f, distToKissPx - rPx + 0.5f));
                            pullMagPx = maxTipReachPx * smooth;
                        }
                    }
                }
            }

            if (dropletMembers.size() == 1 && pullMagPx <= 0.25f) {
                int steps = 64;
                List<Vector2f> exactRing = new ArrayList<>(steps);
                for (int i = 0; i < steps; i++) {
                    double ang = (2.0 * Math.PI * i) / steps;
                    exactRing.add(new Vector2f(
                            mx + (float) Math.cos(ang) * rPx,
                            my + (float) Math.sin(ang) * rPx));
                }
                return exactRing;
            }

            for (int i = 0; i < HULL_SAMPLE_POINTS_PER_SHIP; i++) {
                double ang = (2.0 * Math.PI * i) / HULL_SAMPLE_POINTS_PER_SHIP;
                float ux = (float) Math.cos(ang);
                float uy = (float) Math.sin(ang);
                float extraPull = 0f;
                if (pullMagPx > 0.25f) {
                    float dot = ux * pullDirX + uy * pullDirY;
                    if (dot > 0f) {
                        extraPull = pullMagPx * dot * dot * dot;
                    }
                }
                cloud.add(new Vector2f(
                        mx + ux * (rPx + extraPull),
                        my + uy * (rPx + extraPull)));
            }
        }

        List<Vector2f> hull = computeConvexHullCCW(cloud);
        if (hull.size() < 3) return hull;

        List<Vector2f> smoothed = chaikinSmoothClosed(hull);
        smoothed = chaikinSmoothClosed(smoothed);
        return simplifyContourForTriangulation(smoothed, 0.45f, 80);
    }

    /**
     * Resamples a closed 2D contour uniformly by cumulative perimeter arc-length up to {@code maxVerts} and applies
     * curvature-preserving zero-shrinkage Taubin (lambda/mu) fairing. Unlike index-stride decimation, uniform
     * arc-length sampling distributes vertices evenly around circular caps and concave bridges with zero 45-degree
     * Marching-Squares corner bunching and zero radius shrinkage.
     */
    public static List<Vector2f> simplifyContourForTriangulation(List<Vector2f> poly, float tolerancePx, int maxVerts) {
        if (poly == null || poly.size() <= 6) return poly;
        int n = poly.size();
        float[] cum = new float[n + 1];
        for (int i = 0; i < n; i++) {
            Vector2f p0 = poly.get(i);
            Vector2f p1 = poly.get((i + 1) % n);
            float dx = p1.x - p0.x;
            float dy = p1.y - p0.y;
            cum[i + 1] = cum[i] + (float) Math.sqrt(dx * dx + dy * dy);
        }
        float totalPerim = cum[n];
        if (totalPerim < 1e-3f) return poly;

        int minVerts = Math.min(maxVerts, Math.min(n, 40));
        float minStepPx = Math.max(3.6f, tolerancePx * 6.5f);
        int targetVerts = Math.max(minVerts, Math.min(maxVerts, Math.round(totalPerim / minStepPx)));
        if (targetVerts < 6) targetVerts = Math.min(n, maxVerts);

        List<Vector2f> resampled = resampleClosedContourUniformArcLength(poly, cum, totalPerim, targetVerts);
        return taubinSmoothClosedZeroShrink(resampled, 0.30f, -0.31f, 1);
    }

    private static List<Vector2f> resampleClosedContourUniformArcLength(
            List<Vector2f> poly,
            float[] cum,
            float totalPerim,
            int targetVerts) {
        int n = poly.size();
        List<Vector2f> out = new ArrayList<>(targetVerts);
        int seg = 0;
        for (int k = 0; k < targetVerts; k++) {
            float s = (k * totalPerim) / targetVerts;
            while (seg < n - 1 && cum[seg + 1] < s) {
                seg++;
            }
            float segLen = cum[seg + 1] - cum[seg];
            float t = (segLen > 1e-6f) ? clamp((s - cum[seg]) / segLen, 0f, 1f) : 0f;
            Vector2f p0 = poly.get(seg);
            Vector2f p1 = poly.get((seg + 1) % n);
            out.add(new Vector2f(p0.x + (p1.x - p0.x) * t, p0.y + (p1.y - p0.y) * t));
        }
        return out;
    }

    private static List<Vector2f> taubinSmoothClosedZeroShrink(
            List<Vector2f> poly,
            float lambda,
            float mu,
            int passes) {
        if (poly == null || poly.size() < 5 || passes <= 0) return poly;
        int n = poly.size();
        List<Vector2f> curr = poly;
        for (int p = 0; p < passes; p++) {
            for (int step = 0; step < 2; step++) {
                float w = (step == 0) ? lambda : mu;
                List<Vector2f> next = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    Vector2f prevV = curr.get((i + n - 1) % n);
                    Vector2f curV = curr.get(i);
                    Vector2f nextV = curr.get((i + 1) % n);
                    float mx = 0.5f * (prevV.x + nextV.x);
                    float my = 0.5f * (prevV.y + nextV.y);
                    next.add(new Vector2f(
                            curV.x + w * (mx - curV.x),
                            curV.y + w * (my - curV.y)));
                }
                curr = next;
            }
        }
        return curr;
    }

    private static List<Vector2f> projectAndResampleSdfContour(
            List<Vector2f> rawLoop,
            float[] sx,
            float[] sy,
            float[] sr,
            int mCount,
            PrecomputedSdfPrimitives prims,
            float stepPx,
            int maxVerts) {
        if (rawLoop == null || rawLoop.size() < 4) return rawLoop;
        int n = rawLoop.size();
        float[] cum = new float[n + 1];
        for (int i = 0; i < n; i++) {
            Vector2f p0 = rawLoop.get(i);
            Vector2f p1 = rawLoop.get((i + 1) % n);
            float dx = p1.x - p0.x;
            float dy = p1.y - p0.y;
            cum[i + 1] = cum[i] + (float) Math.sqrt(dx * dx + dy * dy);
        }
        float totalPerim = cum[n];
        if (totalPerim < 1e-3f) return rawLoop;

        int targetVerts = Math.max(Math.min(maxVerts, 48), Math.min(maxVerts, Math.round(totalPerim / 4.2f)));
        List<Vector2f> pts = resampleClosedContourUniformArcLength(rawLoop, cum, totalPerim, targetVerts);

        // Project every resampled vertex along the exact analytical SDF gradient onto SDF(x, y) == 0,
        // guarded by monotonic residual decrease so razor-thin capillary bridges never overshoot!
        float eps = Math.max(0.45f, stepPx * 0.14f);
        float invTwoEps = 1.0f / (2.0f * eps);
        float maxSnapStep = Math.min(4.2f, stepPx * 0.85f);
        float cutoff = Float.MAX_VALUE;
        for (int i = 0, m = pts.size(); i < m; i++) {
            Vector2f p = pts.get(i);
            float d = evalMetaballSdfFast(p.x, p.y, sx, sy, sr, mCount, prims, cutoff);
            if (Math.abs(d) < 0.015f || Math.abs(d) > stepPx * 1.35f) continue;
            float gx = (evalMetaballSdfFast(p.x + eps, p.y, sx, sy, sr, mCount, prims, cutoff)
                    - evalMetaballSdfFast(p.x - eps, p.y, sx, sy, sr, mCount, prims, cutoff)) * invTwoEps;
            float gy = (evalMetaballSdfFast(p.x, p.y + eps, sx, sy, sr, mCount, prims, cutoff)
                    - evalMetaballSdfFast(p.x, p.y - eps, sx, sy, sr, mCount, prims, cutoff)) * invTwoEps;
            float gl = (float) Math.sqrt(gx * gx + gy * gy);
            if (gl < 0.35f) continue;
            float delta = clamp(d / gl, -maxSnapStep, maxSnapStep);
            float nx = p.x - (gx / gl) * delta;
            float ny = p.y - (gy / gl) * delta;
            float dNew = evalMetaballSdfFast(nx, ny, sx, sy, sr, mCount, prims, cutoff);
            if (Math.abs(dNew) < Math.abs(d)) {
                p.set(nx, ny);
            } else {
                float hx = p.x - (gx / gl) * (delta * 0.5f);
                float hy = p.y - (gy / gl) * (delta * 0.5f);
                float dHalf = evalMetaballSdfFast(hx, hy, sx, sy, sr, mCount, prims, cutoff);
                if (Math.abs(dHalf) < Math.abs(d)) {
                    p.set(hx, hy);
                }
            }
        }
        return taubinSmoothClosedZeroShrink(pts, 0.26f, -0.27f, 1);
    }

    public List<Vector2f> applyMetaballNeckPinchScreen(
            List<Vector2f> convexPolyScreen,
            Vector2f centerScreen,
            List<ShipAPI> dropletMembers,
            float scale,
            float offsetX,
            float offsetY) {
        return applyMetaballNeckPinchScreen(
                null,
                convexPolyScreen,
                centerScreen,
                dropletMembers,
                dropletMembers,
                scale,
                offsetX,
                offsetY);
    }

    public List<Vector2f> applyMetaballNeckPinchScreen(
            DivisionState div,
            List<Vector2f> convexPolyScreen,
            Vector2f centerScreen,
            List<ShipAPI> dropletMembers,
            float scale,
            float offsetX,
            float offsetY) {
        return applyMetaballNeckPinchScreen(
                div,
                convexPolyScreen,
                centerScreen,
                dropletMembers,
                dropletMembers,
                scale,
                offsetX,
                offsetY);
    }

    /**
     * Generates a 100% smooth, gash-free, spike-free 2D metaball water-droplet contour around
     * dropletMembers via a Polynomial Smooth-Minimum Signed Distance Field (smin) + Marching Squares:
     *  - Because smin(d_a, d_b, k) <= min(d_a, d_b), the contour is mathematically guaranteed to
     *    enclose 100% of every ship's circle without ever cutting inward into a V-gash or spike.
     *  - For each connected neighbor pair (a, b) with bond openness in [0.02, 1.0], setting
     *    k_ab = 2 * gapPx + 4 * deltaNeck makes the SDF value at the kissing point P_kiss equal
     *    to -deltaNeck (pinching to a tiny ~1.5px point of contact at openness=0.02f when joining/splitting,
     *    and widening smoothly as openness -> 1.0f).
     *  - Only positive-signed-area outer CCW loops are retained (automatically suppressing interior pinholes).
     */
    public List<Vector2f> applyMetaballNeckPinchScreen(
            DivisionState div,
            List<Vector2f> convexPolyScreen,
            Vector2f centerScreen,
            List<ShipAPI> dropletMembers,
            List<ShipAPI> allDivisionMembers,
            float scale,
            float offsetX,
            float offsetY) {
        if (dropletMembers == null || dropletMembers.isEmpty()) {
            return convexPolyScreen;
        }
        if (dropletMembers.size() == 1) {
            // If this 1-ship droplet has an active approaching or recoiling liquid meniscus tail toward a sibling
            // ship (e.g. during the "pop and pull" split animation), trace its concave SDF contour via Marching Squares;
            // otherwise use the fast 96-vertex circle hull directly.
            boolean hasActiveMeniscus = false;
            if (allDivisionMembers != null && allDivisionMembers.size() > 1) {
                ShipAPI sole = dropletMembers.get(0);
                for (ShipAPI other : allDivisionMembers) {
                    if (other == sole) continue;
                    if (resolvePairMeniscusReach(div, sole, other) > 0.02f) {
                        hasActiveMeniscus = true;
                        break;
                    }
                }
            }
            if (!hasActiveMeniscus) {
                return convexPolyScreen;
            }
        }

        List<Vector2f> sdfContour = extractMetaballDropletPolygonScreen(
                div,
                dropletMembers,
                allDivisionMembers,
                scale,
                offsetX,
                offsetY);
        if (sdfContour != null && sdfContour.size() >= 6) {
            return sdfContour;
        }
        return convexPolyScreen;
    }

    public List<Vector2f> extractMetaballDropletPolygonScreen(
            DivisionState div,
            List<ShipAPI> dropletMembers,
            List<ShipAPI> allDivisionMembers,
            float scale,
            float offsetX,
            float offsetY) {
        int mCount = dropletMembers.size();

        // Collect the droplet's ships plus at most ONE closest sibling ship with active meniscus reach
        // (approaching or recoiling) so a droplet never sprouts multiple meniscus tails at once.
        List<ShipAPI> evalShips = new ArrayList<>(mCount + 1);
        evalShips.addAll(dropletMembers);
        int bestMeniscusMemberIdx = -1;
        ShipAPI bestMeniscusOther = null;
        float bestMeniscusScore = 0f;
        float bestMeniscusReach = 0f;
        if (allDivisionMembers != null && allDivisionMembers.size() > mCount) {
            for (int i = 0; i < mCount; i++) {
                ShipAPI m = dropletMembers.get(i);
                int mAllIdx = allDivisionMembers.indexOf(m);
                for (int oIdx = 0; oIdx < allDivisionMembers.size(); oIdx++) {
                    ShipAPI other = allDivisionMembers.get(oIdx);
                    if (dropletMembers.contains(other)) continue;
                    float distW = DivisionUiWidgets.distance(m.getLocation(), other.getLocation());
                    if (mAllIdx >= 0 && isRedundantShortcutChordWorld(allDivisionMembers, mAllIdx, oIdx, distW)) {
                        continue;
                    }
                    float reach = resolvePairMeniscusReach(div, m, other);
                    if (reach > 0.02f) {
                        float score = reach * 1000f - distW * 0.1f;
                        if (score > bestMeniscusScore) {
                            bestMeniscusScore = score;
                            bestMeniscusReach = reach;
                            bestMeniscusMemberIdx = i;
                            bestMeniscusOther = other;
                        }
                    }
                }
            }
            if (bestMeniscusOther != null && bestMeniscusMemberIdx >= 0) {
                evalShips.add(bestMeniscusOther);
            }
        }

        int total = evalShips.size();
        float[] sx = new float[total];
        float[] sy = new float[total];
        float[] sr = new float[total];
        for (int i = 0; i < total; i++) {
            ShipAPI s = evalShips.get(i);
            float worldR = getMemberFootprintRadiusWorld(s);
            sx[i] = s.getLocation().x * scale + offsetX;
            sy[i] = s.getLocation().y * scale + offsetY;
            sr[i] = Math.max(5.5f, worldR * scale);
        }

        // Build pairwise hourglass bridge openness matrix kPair[i][j]
        //   > 0: connected intra-droplet direct-neighbor pair with openness in [0.02, 1.0]
        //   < 0: single active approaching/recoiling inter-droplet meniscus pair with reach in [-0.92, -0.02]
        float[][] kPair = new float[total][total];
        for (int i = 0; i < mCount; i++) {
            ShipAPI shipI = evalShips.get(i);
            float rWi = getMemberFootprintRadiusWorld(shipI);
            // Intra-droplet direct-neighbor pairs
            for (int j = i + 1; j < mCount; j++) {
                ShipAPI shipJ = evalShips.get(j);
                float rWj = getMemberFootprintRadiusWorld(shipJ);
                float distW = DivisionUiWidgets.distance(shipI.getLocation(), shipJ.getLocation());
                if (isRedundantShortcutChordWorld(dropletMembers, i, j, distW)) {
                    continue;
                }
                float mergeDist = (rWi + rWj) * 1.18f;
                float splitDist = mergeDist * 1.24f;
                if (distW <= splitDist * 1.18f) {
                    float openness = resolvePairBondOpenness(div, shipI, shipJ);
                    kPair[i][j] = clamp(openness, 0.02f, 1.0f);
                    kPair[j][i] = kPair[i][j];
                }
            }
        }
        if (bestMeniscusOther != null && bestMeniscusMemberIdx >= 0 && total > mCount) {
            kPair[bestMeniscusMemberIdx][mCount] = -bestMeniscusReach;
            kPair[mCount][bestMeniscusMemberIdx] = -bestMeniscusReach;
        }

        // Compute bounding box around the droplet's member ships and any active meniscus tails
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < mCount; i++) {
            float pad = sr[i] * 1.45f + 18f;
            if (sx[i] - pad < minX) minX = sx[i] - pad;
            if (sx[i] + pad > maxX) maxX = sx[i] + pad;
            if (sy[i] - pad < minY) minY = sy[i] - pad;
            if (sy[i] + pad > maxY) maxY = sy[i] + pad;
            for (int j = mCount; j < total; j++) {
                float reach = -kPair[i][j];
                if (reach > 0.02f) {
                    float tKiss = sr[i] / Math.max(1f, sr[i] + sr[j]);
                    float tipX = sx[i] + (sx[j] - sx[i]) * tKiss * reach;
                    float tipY = sy[i] + (sy[j] - sy[i]) * tKiss * reach;
                    if (tipX - 16f < minX) minX = tipX - 16f;
                    if (tipX + 16f > maxX) maxX = tipX + 16f;
                    if (tipY - 16f < minY) minY = tipY - 16f;
                    if (tipY + 16f > maxY) maxY = tipY + 16f;
                }
            }
        }
        float maxSpan = Math.max(maxX - minX, maxY - minY);
        // Keep Marching Squares grid resolution bounded (~58 cells) while analytical SDF projection snaps
        // every resampled contour vertex onto SDF == 0 with sub-pixel circular precision!
        float stepPx = Math.max(2.8f, maxSpan / 58f);

        float minWaistPx = Math.max(3.2f, stepPx * (maxSpan > 160f ? 1.15f : 0.88f));
        float activeWaistPx = minWaistPx;
        List<List<Vector2f>> loops = traceMetaballContoursMarchingSquares(
                sx, sy, sr, mCount, total, kPair, minX, maxX, minY, maxY, stepPx, minWaistPx);
        if (loops.isEmpty()) {
            return Collections.emptyList();
        }

        List<Vector2f> bestLoop = selectBestContainingLoop(loops, sx, sy, mCount);
        if (bestLoop == null || (mCount > 1 && countContainedShips(bestLoop, sx, sy, mCount) < mCount)) {
            // Guarantee that a thin bridge never pinches between Marching Squares grid vertices and drops a ship
            activeWaistPx = stepPx * 1.38f;
            loops = traceMetaballContoursMarchingSquares(
                    sx, sy, sr, mCount, total, kPair, minX, maxX, minY, maxY, stepPx, activeWaistPx);
            List<Vector2f> retryLoop = selectBestContainingLoop(loops, sx, sy, mCount);
            if (retryLoop != null) {
                bestLoop = retryLoop;
            }
        }
        List<Vector2f> chosen = (bestLoop != null) ? bestLoop : loops.get(0);
        PrecomputedSdfPrimitives prims = new PrecomputedSdfPrimitives(sx, sy, sr, mCount, total, kPair, activeWaistPx);
        return projectAndResampleSdfContour(chosen, sx, sy, sr, mCount, prims, stepPx, 84);
    }

    private static List<Vector2f> selectBestContainingLoop(
            List<List<Vector2f>> loops,
            float[] sx,
            float[] sy,
            int mCount) {
        List<Vector2f> bestLoop = null;
        float bestScore = -Float.MAX_VALUE;
        for (List<Vector2f> loop : loops) {
            float signedArea = computeSignedPolygonArea(loop);
            if (signedArea <= 60f) continue; // Reject CW interior holes (signedArea < 0) and tiny noise specks
            int containedMembers = countContainedShips(loop, sx, sy, mCount);
            if (containedMembers <= 0) continue; // Never select an empty interior pocket
            float score = containedMembers * 100000f + signedArea;
            if (score > bestScore) {
                bestScore = score;
                bestLoop = loop;
            }
        }
        return bestLoop;
    }

    private static int countContainedShips(List<Vector2f> loop, float[] sx, float[] sy, int mCount) {
        int contained = 0;
        for (int i = 0; i < mCount; i++) {
            if (isPointInsidePolygon(loop, sx[i], sy[i])) {
                contained++;
            }
        }
        return contained;
    }

    private static float sminPoly(float a, float b, float k) {
        if (k <= 1e-4f) return Math.min(a, b);
        float h = Math.max(k - Math.abs(a - b), 0f) / k;
        return Math.min(a, b) - h * h * k * 0.25f;
    }

    private static final class PrecomputedSdfPrimitives {
        final float smoothK;
        final int brCount;
        final float[] brAx, brAy, brUx, brUy, brSa, brSb, brSKiss, brYa, brYb, brRWaist, brInvSpanA, brInvSpanB;
        final float[] brMinX, brMaxX, brMinY, brMaxY;

        final int menCount;
        final float[] menAx, menAy, menUx, menUy, menS0, menS1, menInvSpan, menYShoulder, menRTip;
        final float[] menMinX, menMaxX, menMinY, menMaxY;

        final int triCount;
        final float[] triAx, triAy, triBx, triBy, triCx, triCy, triPad;
        final float[] triMinX, triMaxX, triMinY, triMaxY;

        PrecomputedSdfPrimitives(
                float[] sx, float[] sy, float[] sr,
                int mCount, int total, float[][] kPair, float minWaistPx) {
            float sumR = 0f;
            for (int i = 0; i < mCount; i++) {
                sumR += sr[i];
            }
            float avgR = (mCount > 0) ? (sumR / mCount) : 24f;
            this.smoothK = clamp(avgR * 0.22f, 3.0f, 10.0f);

            int maxBr = (mCount * (mCount - 1)) / 2;
            brAx = new float[maxBr]; brAy = new float[maxBr];
            brUx = new float[maxBr]; brUy = new float[maxBr];
            brSa = new float[maxBr]; brSb = new float[maxBr]; brSKiss = new float[maxBr];
            brYa = new float[maxBr]; brYb = new float[maxBr]; brRWaist = new float[maxBr];
            brInvSpanA = new float[maxBr]; brInvSpanB = new float[maxBr];
            brMinX = new float[maxBr]; brMaxX = new float[maxBr];
            brMinY = new float[maxBr]; brMaxY = new float[maxBr];
            int bIdx = 0;

            int maxMen = Math.max(0, total - mCount) * mCount;
            menAx = new float[maxMen]; menAy = new float[maxMen];
            menUx = new float[maxMen]; menUy = new float[maxMen];
            menS0 = new float[maxMen]; menS1 = new float[maxMen]; menInvSpan = new float[maxMen];
            menYShoulder = new float[maxMen]; menRTip = new float[maxMen];
            menMinX = new float[maxMen]; menMaxX = new float[maxMen];
            menMinY = new float[maxMen]; menMaxY = new float[maxMen];
            int mIdx = 0;

            for (int i = 0; i < mCount; i++) {
                float ax = sx[i];
                float ay = sy[i];
                float ra = sr[i];
                for (int j = i + 1; j < mCount; j++) {
                    float openness = kPair[i][j];
                    if (openness <= 0f) continue;
                    float bx = sx[j];
                    float by = sy[j];
                    float rb = sr[j];
                    float abx = bx - ax;
                    float aby = by - ay;
                    float lenSq = abx * abx + aby * aby;
                    if (lenSq < 1e-3f) continue;
                    float len = (float) Math.sqrt(lenSq);
                    float sKiss = len * (ra / Math.max(1f, ra + rb));
                    float sa = Math.min(ra * 0.42f, sKiss * 0.52f);
                    float sb = len - Math.min(rb * 0.42f, (len - sKiss) * 0.52f);
                    if (sb <= sa + 1.0f) continue;

                    float maxShoulderR = Math.max(ra, rb) * 1.18f + 14f;
                    brAx[bIdx] = ax;
                    brAy[bIdx] = ay;
                    brUx[bIdx] = abx / len;
                    brUy[bIdx] = aby / len;
                    brSa[bIdx] = sa;
                    brSb[bIdx] = sb;
                    brSKiss[bIdx] = sKiss;
                    // Relax tight-formation shoulder & waist up to full tangent capsule width (0.985 * minR) when
                    // openness -> 1 so Line Abreast / Column hulls form sleek stadiums without caterpillar dents,
                    // while still pinching smoothly down to minWaistPx as ships separate!
                    float shoulderScale = 0.94f + 0.055f * openness;
                    brYa[bIdx] = (float) Math.sqrt(Math.max(1f, ra * ra - sa * sa)) * shoulderScale;
                    float lb = len - sb;
                    brYb[bIdx] = (float) Math.sqrt(Math.max(1f, rb * rb - lb * lb)) * shoulderScale;
                    float minR = Math.min(ra, rb);
                    float maxWaist = Math.max(minWaistPx + 1.0f, minR * 0.985f);
                    float smoothOpen = openness * (0.35f + 0.65f * openness);
                    brRWaist[bIdx] = minWaistPx + smoothOpen * (maxWaist - minWaistPx);
                    brInvSpanA[bIdx] = 1.0f / Math.max(1f, sKiss - sa);
                    brInvSpanB[bIdx] = 1.0f / Math.max(1f, sb - sKiss);
                    brMinX[bIdx] = Math.min(ax, bx) - maxShoulderR;
                    brMaxX[bIdx] = Math.max(ax, bx) + maxShoulderR;
                    brMinY[bIdx] = Math.min(ay, by) - maxShoulderR;
                    brMaxY[bIdx] = Math.max(ay, by) + maxShoulderR;
                    bIdx++;
                }

                for (int j = mCount; j < total; j++) {
                    float reach = -kPair[i][j];
                    if (reach <= 0.02f) continue;
                    float bx = sx[j];
                    float by = sy[j];
                    float rb = sr[j];
                    float abx = bx - ax;
                    float aby = by - ay;
                    float lenSq = abx * abx + aby * aby;
                    if (lenSq < 1e-3f) continue;
                    float len = (float) Math.sqrt(lenSq);
                    float ux = abx / len;
                    float uy = aby / len;
                    float distToKissPx = len * (ra / Math.max(1f, ra + rb));
                    float maxExtPx = Math.min(ra * 0.44f, Math.max(2.0f, distToKissPx - ra * 0.95f));
                    float s0 = ra * 0.46f;
                    float s1 = ra + reach * maxExtPx;
                    if (s1 <= s0 + 1.0f) continue;
                    float yShoulder = (float) Math.sqrt(Math.max(1f, ra * ra - s0 * s0)) * (0.84f + 0.14f * reach);
                    float rTip = 2.6f + (1.0f - reach) * Math.min(5.2f, ra * 0.16f);
                    float endX = ax + ux * s1;
                    float endY = ay + uy * s1;
                    float pad = yShoulder + 8f;
                    menAx[mIdx] = ax;
                    menAy[mIdx] = ay;
                    menUx[mIdx] = ux;
                    menUy[mIdx] = uy;
                    menS0[mIdx] = s0;
                    menS1[mIdx] = s1;
                    menInvSpan[mIdx] = 1.0f / Math.max(1f, s1 - s0);
                    menYShoulder[mIdx] = yShoulder;
                    menRTip[mIdx] = rTip;
                    menMinX[mIdx] = Math.min(ax, endX) - pad;
                    menMaxX[mIdx] = Math.max(ax, endX) + pad;
                    menMinY[mIdx] = Math.min(ay, endY) - pad;
                    menMaxY[mIdx] = Math.max(ay, endY) + pad;
                    mIdx++;
                }
            }
            this.brCount = bIdx;
            this.menCount = mIdx;

            int maxTri = (mCount >= 3) ? (mCount * (mCount - 1) * (mCount - 2)) / 6 : 0;
            triAx = new float[maxTri]; triAy = new float[maxTri];
            triBx = new float[maxTri]; triBy = new float[maxTri];
            triCx = new float[maxTri]; triCy = new float[maxTri];
            triPad = new float[maxTri];
            triMinX = new float[maxTri]; triMaxX = new float[maxTri];
            triMinY = new float[maxTri]; triMaxY = new float[maxTri];
            int tIdx = 0;
            if (mCount >= 3) {
                for (int i = 0; i < mCount; i++) {
                    for (int j = i + 1; j < mCount; j++) {
                        if (kPair[i][j] <= 0f) continue;
                        for (int l = j + 1; l < mCount; l++) {
                            if (kPair[j][l] <= 0f || kPair[i][l] <= 0f) continue;
                            float ax = sx[i], ay = sy[i];
                            float bx = sx[j], by = sy[j];
                            float cx = sx[l], cy = sy[l];
                            float cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
                            if (Math.abs(cross) < 1e-3f) continue;
                            if (cross < 0f) {
                                float tx = bx; bx = cx; cx = tx;
                                float ty = by; by = cy; cy = ty;
                            }
                            float minR = Math.min(sr[i], Math.min(sr[j], sr[l]));
                            float avgOpen = (kPair[i][j] + kPair[j][l] + kPair[i][l]) / 3.0f;
                            float pad = minWaistPx + avgOpen * minR * 0.45f;
                            float margin = pad + 8.0f;
                            triAx[tIdx] = ax; triAy[tIdx] = ay;
                            triBx[tIdx] = bx; triBy[tIdx] = by;
                            triCx[tIdx] = cx; triCy[tIdx] = cy;
                            triPad[tIdx] = pad;
                            triMinX[tIdx] = Math.min(ax, Math.min(bx, cx)) - margin;
                            triMaxX[tIdx] = Math.max(ax, Math.max(bx, cx)) + margin;
                            triMinY[tIdx] = Math.min(ay, Math.min(by, cy)) - margin;
                            triMaxY[tIdx] = Math.max(ay, Math.max(by, cy)) + margin;
                            tIdx++;
                        }
                    }
                }
            }
            this.triCount = tIdx;
        }
    }

    private static float evalMetaballSdfRowCell(
            float x, float y,
            float[] sx, float[] sr, int mCount,
            float[] rowDyAbs, float[] rowDySq,
            PrecomputedSdfPrimitives prims, float deepInteriorCutoffPx,
            int[] rowBrIdx, int rBrCount, float rBrMinX, float rBrMaxX,
            int[] rowMenIdx, int rMenCount, float rMenMinX, float rMenMaxX,
            int[] rowTriIdx, int rTriCount, float rTriMinX, float rTriMaxX) {
        float bestCircle = Float.MAX_VALUE;
        for (int i = 0; i < mCount; i++) {
            float rad = sr[i];
            if (rowDyAbs[i] - rad >= bestCircle) continue;
            float dx = Math.abs(x - sx[i]);
            if (dx - rad >= bestCircle) continue;
            float di = (float) Math.sqrt(dx * dx + rowDySq[i]) - rad;
            if (di < bestCircle) {
                bestCircle = di;
            }
        }
        if (bestCircle <= -deepInteriorCutoffPx) {
            return bestCircle;
        }

        float bestBridge = Float.MAX_VALUE;
        if (rBrCount > 0 && x >= rBrMinX && x <= rBrMaxX) {
            bestBridge = evalRowBridgesSdf(x, y, prims, rowBrIdx, rBrCount, bestBridge);
        }
        if (rMenCount > 0 && x >= rMenMinX && x <= rMenMaxX) {
            bestBridge = evalRowMeniscusesSdf(x, y, prims, rowMenIdx, rMenCount, bestBridge);
        }
        if (rTriCount > 0 && x >= rTriMinX && x <= rTriMaxX && bestBridge > -deepInteriorCutoffPx) {
            for (int k = 0; k < rTriCount; k++) {
                int t = rowTriIdx[k];
                if (x < prims.triMinX[t] || x > prims.triMaxX[t]) continue;
                float dTri = signedDistanceToTriangleCCW2D(
                        x, y,
                        prims.triAx[t], prims.triAy[t],
                        prims.triBx[t], prims.triBy[t],
                        prims.triCx[t], prims.triCy[t],
                        prims.triPad[t]);
                bestBridge = sminPoly(bestBridge, dTri - prims.triPad[t], 6.0f);
            }
        }
        return sminPoly(bestCircle, bestBridge, prims.smoothK);
    }

    private static float evalRowBridgesSdf(
            float x, float y, PrecomputedSdfPrimitives prims, int[] rowBrIdx, int rBrCount, float bestBridge) {
        for (int k = 0; k < rBrCount; k++) {
            int b = rowBrIdx[k];
            if (x < prims.brMinX[b] || x > prims.brMaxX[b]) continue;
            float ax = prims.brAx[b];
            float ay = prims.brAy[b];
            float ux = prims.brUx[b];
            float uy = prims.brUy[b];
            float sa = prims.brSa[b];
            float sb = prims.brSb[b];
            float sKiss = prims.brSKiss[b];
            float proj = (x - ax) * ux + (y - ay) * uy;
            float sClamped = clamp(proj, sa, sb);
            float qx = ax + ux * sClamped;
            float qy = ay + uy * sClamped;
            float distToAxis = (float) Math.sqrt((x - qx) * (x - qx) + (y - qy) * (y - qy));
            float rWaist = prims.brRWaist[b];
            float rBridge;
            if (sClamped <= sKiss) {
                float u = clamp((sClamped - sa) * prims.brInvSpanA[b], 0f, 1f);
                float oneMinusU = 1.0f - u;
                rBridge = rWaist + (prims.brYa[b] - rWaist) * oneMinusU * oneMinusU;
            } else {
                float v = clamp((sb - sClamped) * prims.brInvSpanB[b], 0f, 1f);
                float oneMinusV = 1.0f - v;
                rBridge = rWaist + (prims.brYb[b] - rWaist) * oneMinusV * oneMinusV;
            }
            float dBridge = distToAxis - rBridge;
            if (dBridge < bestBridge) {
                bestBridge = dBridge;
            }
        }
        return bestBridge;
    }

    private static float evalRowMeniscusesSdf(
            float x, float y, PrecomputedSdfPrimitives prims, int[] rowMenIdx, int rMenCount, float bestBridge) {
        for (int k = 0; k < rMenCount; k++) {
            int m = rowMenIdx[k];
            if (x < prims.menMinX[m] || x > prims.menMaxX[m]) continue;
            float ax = prims.menAx[m];
            float ay = prims.menAy[m];
            float ux = prims.menUx[m];
            float uy = prims.menUy[m];
            float s0 = prims.menS0[m];
            float s1 = prims.menS1[m];
            float proj = (x - ax) * ux + (y - ay) * uy;
            float sClamped = clamp(proj, s0, s1);
            float qx = ax + ux * sClamped;
            float qy = ay + uy * sClamped;
            float distToAxis = (float) Math.sqrt((x - qx) * (x - qx) + (y - qy) * (y - qy));
            float u = clamp((sClamped - s0) * prims.menInvSpan[m], 0f, 1f);
            float oneMinusU = 1.0f - u;
            float rTip = prims.menRTip[m];
            float rCone = rTip + (prims.menYShoulder[m] - rTip) * oneMinusU * oneMinusU;
            float dCone = distToAxis - rCone;
            if (dCone < bestBridge) {
                bestBridge = dCone;
            }
        }
        return bestBridge;
    }

    private static float evalMetaballSdfFast(
            float x,
            float y,
            float[] sx,
            float[] sy,
            float[] sr,
            int mCount,
            PrecomputedSdfPrimitives prims,
            float deepInteriorCutoffPx) {
        float bestCircle = Float.MAX_VALUE;
        for (int i = 0; i < mCount; i++) {
            float dx = x - sx[i];
            float dy = y - sy[i];
            float di = (float) Math.sqrt(dx * dx + dy * dy) - sr[i];
            if (di < bestCircle) {
                bestCircle = di;
            }
        }
        // Any grid vertex deeper inside a circle than deepInteriorCutoffPx has all 4 adjacent grid cells < 0,
        // so sminPoly(bestCircle, bestBridge, 2.5f) cannot change any Marching Squares cell mask or edge interpolation!
        if (bestCircle <= -deepInteriorCutoffPx) {
            return bestCircle;
        }

        float bestBridge = Float.MAX_VALUE;
        int brCount = prims.brCount;
        for (int b = 0; b < brCount; b++) {
            if (x < prims.brMinX[b] || x > prims.brMaxX[b] || y < prims.brMinY[b] || y > prims.brMaxY[b]) {
                continue;
            }
            float ax = prims.brAx[b];
            float ay = prims.brAy[b];
            float ux = prims.brUx[b];
            float uy = prims.brUy[b];
            float sa = prims.brSa[b];
            float sb = prims.brSb[b];
            float sKiss = prims.brSKiss[b];
            float proj = (x - ax) * ux + (y - ay) * uy;
            float sClamped = clamp(proj, sa, sb);
            float qx = ax + ux * sClamped;
            float qy = ay + uy * sClamped;
            float distToAxis = (float) Math.sqrt((x - qx) * (x - qx) + (y - qy) * (y - qy));
            float rWaist = prims.brRWaist[b];
            float rBridge;
            if (sClamped <= sKiss) {
                float u = clamp((sClamped - sa) * prims.brInvSpanA[b], 0f, 1f);
                float oneMinusU = 1.0f - u;
                rBridge = rWaist + (prims.brYa[b] - rWaist) * oneMinusU * oneMinusU;
            } else {
                float v = clamp((sb - sClamped) * prims.brInvSpanB[b], 0f, 1f);
                float oneMinusV = 1.0f - v;
                rBridge = rWaist + (prims.brYb[b] - rWaist) * oneMinusV * oneMinusV;
            }
            float dBridge = distToAxis - rBridge;
            if (dBridge < bestBridge) {
                bestBridge = dBridge;
            }
        }

        int menCount = prims.menCount;
        for (int m = 0; m < menCount; m++) {
            if (x < prims.menMinX[m] || x > prims.menMaxX[m] || y < prims.menMinY[m] || y > prims.menMaxY[m]) {
                continue;
            }
            float ax = prims.menAx[m];
            float ay = prims.menAy[m];
            float ux = prims.menUx[m];
            float uy = prims.menUy[m];
            float s0 = prims.menS0[m];
            float s1 = prims.menS1[m];
            float proj = (x - ax) * ux + (y - ay) * uy;
            float sClamped = clamp(proj, s0, s1);
            float qx = ax + ux * sClamped;
            float qy = ay + uy * sClamped;
            float distToAxis = (float) Math.sqrt((x - qx) * (x - qx) + (y - qy) * (y - qy));
            float u = clamp((sClamped - s0) * prims.menInvSpan[m], 0f, 1f);
            float oneMinusU = 1.0f - u;
            float rTip = prims.menRTip[m];
            float rCone = rTip + (prims.menYShoulder[m] - rTip) * oneMinusU * oneMinusU;
            float dCone = distToAxis - rCone;
            if (dCone < bestBridge) {
                bestBridge = dCone;
            }
        }

        int triCount = prims.triCount;
        if (triCount > 0 && bestBridge > -deepInteriorCutoffPx) {
            for (int t = 0; t < triCount; t++) {
                if (x < prims.triMinX[t] || x > prims.triMaxX[t] || y < prims.triMinY[t] || y > prims.triMaxY[t]) {
                    continue;
                }
                float dTri = signedDistanceToTriangleCCW2D(
                        x, y,
                        prims.triAx[t], prims.triAy[t],
                        prims.triBx[t], prims.triBy[t],
                        prims.triCx[t], prims.triCy[t],
                        prims.triPad[t]);
                bestBridge = sminPoly(bestBridge, dTri - prims.triPad[t], 6.0f);
            }
        }

        return sminPoly(bestCircle, bestBridge, prims.smoothK);
    }

    private static float signedDistanceToTriangleCCW2D(
            float px, float py,
            float ax, float ay,
            float bx, float by,
            float cx, float cy,
            float pad) {
        float s0 = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
        float s1 = (cx - bx) * (py - by) - (cy - by) * (px - bx);
        float s2 = (ax - cx) * (py - cy) - (ay - cy) * (px - cx);
        if (s0 >= 0f && s1 >= 0f && s2 >= 0f) {
            // Point is strictly inside the connected 3-ship triangle: dTri <= 0, so dTri - pad <= -pad < 0
            return -2.0f;
        }
        float d0 = distSqPointToSegment(px, py, ax, ay, bx, by);
        float d1 = distSqPointToSegment(px, py, bx, by, cx, cy);
        float d2 = distSqPointToSegment(px, py, cx, cy, ax, ay);
        float minSq = Math.min(d0, Math.min(d1, d2));
        float maxInfluence = pad + 6.0f;
        if (minSq >= maxInfluence * maxInfluence) {
            return maxInfluence;
        }
        return (float) Math.sqrt(minSq);
    }

    private static float distSqPointToSegment(
            float px, float py,
            float x0, float y0,
            float x1, float y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float lenSq = dx * dx + dy * dy;
        if (lenSq <= 1e-5f) {
            float ex = px - x0;
            float ey = py - y0;
            return ex * ex + ey * ey;
        }
        float t = clamp(((px - x0) * dx + (py - y0) * dy) / lenSq, 0f, 1f);
        float qx = x0 + t * dx;
        float qy = y0 + t * dy;
        float ex = px - qx;
        float ey = py - qy;
        return ex * ex + ey * ey;
    }

    private static List<List<Vector2f>> traceMetaballContoursMarchingSquares(
            float[] sx,
            float[] sy,
            float[] sr,
            int mCount,
            int total,
            float[][] kPair,
            float minX,
            float maxX,
            float minY,
            float maxY,
            float stepPx,
            float minWaistPx) {
        int cols = Math.max(8, Math.min(104, (int) Math.ceil((maxX - minX) / stepPx)));
        int rows = Math.max(8, Math.min(104, (int) Math.ceil((maxY - minY) / stepPx)));
        PrecomputedSdfPrimitives prims = new PrecomputedSdfPrimitives(sx, sy, sr, mCount, total, kPair, minWaistPx);
        // Exact triangle-inequality interior bound accounting for sminPoly(..., smoothK) shift (<= 0.25 * smoothK):
        // if bestCircle <= -deepInteriorCutoffPx, neither this grid point nor any cardinal neighbor can have SDF > 0!
        float deepInteriorCutoffPx = stepPx + prims.smoothK * 0.28f + 0.6f;
        float[][] grid = new float[rows + 1][cols + 1];
        float[] rowDyAbs = new float[mCount];
        float[] rowDySq = new float[mCount];
        int[] rowBrIdx = new int[prims.brCount];
        int[] rowMenIdx = new int[prims.menCount];
        int[] rowTriIdx = new int[prims.triCount];

        for (int r = 0; r <= rows; r++) {
            float y = minY + r * stepPx;
            float[] row = grid[r];

            for (int i = 0; i < mCount; i++) {
                float dy = Math.abs(y - sy[i]);
                rowDyAbs[i] = dy;
                rowDySq[i] = dy * dy;
            }

            int rBrCount = 0;
            float rBrMinX = Float.MAX_VALUE;
            float rBrMaxX = -Float.MAX_VALUE;
            for (int b = 0; b < prims.brCount; b++) {
                if (y >= prims.brMinY[b] && y <= prims.brMaxY[b]) {
                    rowBrIdx[rBrCount++] = b;
                    if (prims.brMinX[b] < rBrMinX) rBrMinX = prims.brMinX[b];
                    if (prims.brMaxX[b] > rBrMaxX) rBrMaxX = prims.brMaxX[b];
                }
            }

            int rMenCount = 0;
            float rMenMinX = Float.MAX_VALUE;
            float rMenMaxX = -Float.MAX_VALUE;
            for (int m = 0; m < prims.menCount; m++) {
                if (y >= prims.menMinY[m] && y <= prims.menMaxY[m]) {
                    rowMenIdx[rMenCount++] = m;
                    if (prims.menMinX[m] < rMenMinX) rMenMinX = prims.menMinX[m];
                    if (prims.menMaxX[m] > rMenMaxX) rMenMaxX = prims.menMaxX[m];
                }
            }

            int rTriCount = 0;
            float rTriMinX = Float.MAX_VALUE;
            float rTriMaxX = -Float.MAX_VALUE;
            for (int t = 0; t < prims.triCount; t++) {
                if (y >= prims.triMinY[t] && y <= prims.triMaxY[t]) {
                    rowTriIdx[rTriCount++] = t;
                    if (prims.triMinX[t] < rTriMinX) rTriMinX = prims.triMinX[t];
                    if (prims.triMaxX[t] > rTriMaxX) rTriMaxX = prims.triMaxX[t];
                }
            }

            for (int c = 0; c <= cols; c++) {
                float x = minX + c * stepPx;
                row[c] = evalMetaballSdfRowCell(
                        x, y, sx, sr, mCount, rowDyAbs, rowDySq,
                        prims, deepInteriorCutoffPx,
                        rowBrIdx, rBrCount, rBrMinX, rBrMaxX,
                        rowMenIdx, rMenCount, rMenMinX, rMenMaxX,
                        rowTriIdx, rTriCount, rTriMinX, rTriMaxX);
            }
        }

        // Flat primitive edge graph (zero Long boxing, zero HashMap/HashSet/ArrayList per-edge allocations):
        // Each grid edge is uniquely indexed in [0, 2 * strideR * strideC), and has at most 2 outgoing directed edges.
        int strideC = cols + 2;
        int planeSize = (rows + 2) * strideC;
        int maxEdges = planeSize * 2;
        float[] nodeX = new float[maxEdges];
        float[] nodeY = new float[maxEdges];
        boolean[] hasNode = new boolean[maxEdges];
        int[] adjOut0 = new int[maxEdges];
        int[] adjOut1 = new int[maxEdges];
        java.util.Arrays.fill(adjOut0, -1);
        java.util.Arrays.fill(adjOut1, -1);
        int[] activeEdgeStarts = new int[Math.min(maxEdges, (rows + cols) * 8 + 64)];
        int activeEdgeCount = 0;

        for (int r = 0; r < rows; r++) {
            float[] row0 = grid[r];
            float[] row1 = grid[r + 1];
            float y0 = minY + r * stepPx;
            int baseR0 = r * strideC;
            int baseR1 = (r + 1) * strideC;
            for (int c = 0; c < cols; c++) {
                float v0 = row0[c];
                float v1 = row0[c + 1];
                float v2 = row1[c + 1];
                float v3 = row1[c];
                int mask = (v0 <= 0f ? 1 : 0)
                        | (v1 <= 0f ? 2 : 0)
                        | (v2 <= 0f ? 4 : 0)
                        | (v3 <= 0f ? 8 : 0);
                if (mask == 0 || mask == 15) continue;

                int eBottom = baseR0 + c;
                int eRight = planeSize + baseR0 + (c + 1);
                int eTop = baseR1 + c;
                int eLeft = planeSize + baseR0 + c;

                float x0 = minX + c * stepPx;
                if ((mask & 1) != ((mask >> 1) & 1) && !hasNode[eBottom]) {
                    hasNode[eBottom] = true;
                    nodeX[eBottom] = x0 + msInterp(v0, v1) * stepPx;
                    nodeY[eBottom] = y0;
                }
                if (((mask >> 1) & 1) != ((mask >> 2) & 1) && !hasNode[eRight]) {
                    hasNode[eRight] = true;
                    nodeX[eRight] = x0 + stepPx;
                    nodeY[eRight] = y0 + msInterp(v1, v2) * stepPx;
                }
                if (((mask >> 3) & 1) != ((mask >> 2) & 1) && !hasNode[eTop]) {
                    hasNode[eTop] = true;
                    nodeX[eTop] = x0 + msInterp(v3, v2) * stepPx;
                    nodeY[eTop] = y0 + stepPx;
                }
                if ((mask & 1) != ((mask >> 3) & 1) && !hasNode[eLeft]) {
                    hasNode[eLeft] = true;
                    nodeX[eLeft] = x0;
                    nodeY[eLeft] = y0 + msInterp(v0, v3) * stepPx;
                }

                float centerVal = 0.25f * (v0 + v1 + v2 + v3);
                switch (mask) {
                    case 1:
                        activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eLeft, eBottom);
                        break;
                    case 2:
                        activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eBottom, eRight);
                        break;
                    case 3:
                        activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eLeft, eRight);
                        break;
                    case 4:
                        activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eRight, eTop);
                        break;
                    case 5:
                        if (centerVal <= 0f) {
                            activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eLeft, eTop);
                            activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eRight, eBottom);
                        } else {
                            activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eLeft, eBottom);
                            activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eRight, eTop);
                        }
                        break;
                    case 6:
                        activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eBottom, eTop);
                        break;
                    case 7:
                        activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eLeft, eTop);
                        break;
                    case 8:
                        activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eTop, eLeft);
                        break;
                    case 9:
                        activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eTop, eBottom);
                        break;
                    case 10:
                        if (centerVal <= 0f) {
                            activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eBottom, eLeft);
                            activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eTop, eRight);
                        } else {
                            activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eBottom, eRight);
                            activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eTop, eLeft);
                        }
                        break;
                    case 11:
                        activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eTop, eRight);
                        break;
                    case 12:
                        activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eRight, eLeft);
                        break;
                    case 13:
                        activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eRight, eBottom);
                        break;
                    case 14:
                        activeEdgeCount = msAddFlatEdge(adjOut0, adjOut1, activeEdgeStarts, activeEdgeCount, eBottom, eLeft);
                        break;
                    default:
                        break;
                }
            }
        }

        List<List<Vector2f>> loops = new ArrayList<>();
        boolean[] usedOut0 = new boolean[maxEdges];
        boolean[] usedOut1 = new boolean[maxEdges];
        for (int idx = 0; idx < activeEdgeCount; idx++) {
            int start = activeEdgeStarts[idx];
            for (int slot = 0; slot < 2; slot++) {
                int firstNext = (slot == 0) ? adjOut0[start] : adjOut1[start];
                if (firstNext < 0) continue;
                boolean alreadyUsed = (slot == 0) ? usedOut0[start] : usedOut1[start];
                if (alreadyUsed) continue;

                List<Vector2f> poly = new ArrayList<>();
                int curr = start;
                int next = firstNext;
                if (slot == 0) usedOut0[start] = true;
                else usedOut1[start] = true;

                int guard = 2000;
                while (guard-- > 0) {
                    if (hasNode[curr]) {
                        poly.add(new Vector2f(nodeX[curr], nodeY[curr]));
                    }
                    if (next == start) break;
                    curr = next;
                    int c0 = adjOut0[curr];
                    if (c0 < 0) break;
                    int chosen;
                    if (!usedOut0[curr]) {
                        usedOut0[curr] = true;
                        chosen = c0;
                    } else if (adjOut1[curr] >= 0 && !usedOut1[curr]) {
                        usedOut1[curr] = true;
                        chosen = adjOut1[curr];
                    } else {
                        break;
                    }
                    next = chosen;
                }
                if (poly.size() >= 4) {
                    List<Vector2f> sm = chaikinSmoothClosed(poly);
                    sm = chaikinSmoothClosed(sm);
                    if (computePolygonArea(sm) > 60f) {
                        loops.add(sm);
                    }
                }
            }
        }
        return loops;
    }

    private static float msInterp(float a, float b) {
        float denom = a - b;
        if (Math.abs(denom) < 1e-6f) return 0.5f;
        return clamp(a / denom, 0.02f, 0.98f);
    }

    private static int msAddFlatEdge(
            int[] adjOut0,
            int[] adjOut1,
            int[] activeStarts,
            int activeCount,
            int from,
            int to) {
        // In Y-up screen coordinates, orient directed edges so that inside regions (SDF <= 0) lie on the LEFT
        // of each edge: outer droplet perimeters trace Counter-Clockwise (signedArea > 0) and interior holes
        // trace Clockwise (signedArea < 0).
        if (adjOut0[to] < 0) {
            adjOut0[to] = from;
            if (activeCount < activeStarts.length) {
                activeStarts[activeCount++] = to;
            }
        } else if (adjOut1[to] < 0) {
            adjOut1[to] = from;
        }
        return activeCount;
    }

    // =========================================================================
    // Section 4: Layer Renderers (Polygons, Top-Layer Ship Sprites, Flagship Stars, Overlap Stripes, Waypoints & Labels)
    // =========================================================================

    /**
     * Computes a self-intersection-free inward gradient offset ring for arbitrary concave/pinched metaball contours.
     * Clamps each vertex's inward normal step against opposing non-adjacent boundary segments (preventing hourglass
     * neck or capillary-thread fold-overs), then enforces cyclic Lipschitz continuity + Laplacian smoothing.
     */
    public static List<Vector2f> computeSafeInwardGradientRing(List<Vector2f> poly, float targetSkirtPx) {
        if (poly == null || poly.size() < 3) return poly;
        int n = poly.size();
        float signedArea = computeSignedPolygonArea(poly);
        float sign = (signedArea >= 0f) ? 1.0f : -1.0f;

        float[] edgeLen = new float[n];
        float[] nx = new float[n];
        float[] ny = new float[n];
        for (int i = 0; i < n; i++) {
            Vector2f a = poly.get(i);
            Vector2f b = poly.get((i + 1) % n);
            float ex = b.x - a.x;
            float ey = b.y - a.y;
            float len = (float) Math.sqrt(ex * ex + ey * ey);
            if (len < 1e-5f) len = 1e-5f;
            edgeLen[i] = len;
            nx[i] = (-sign * ey) / len;
            ny[i] = (sign * ex) / len;
        }

        float[] rx = new float[n];
        float[] ry = new float[n];
        float[] maxS = new float[n];
        for (int i = 0; i < n; i++) {
            int prev = (i + n - 1) % n;
            float bx = nx[prev] + nx[i];
            float by = ny[prev] + ny[i];
            float bl = (float) Math.sqrt(bx * bx + by * by);
            if (bl < 1e-4f) {
                rx[i] = nx[i];
                ry[i] = ny[i];
            } else {
                rx[i] = bx / bl;
                ry[i] = by / bl;
            }
            Vector2f pPrev = poly.get(prev);
            Vector2f pCurr = poly.get(i);
            Vector2f pNext = poly.get((i + 1) % n);
            float e1x = pCurr.x - pPrev.x;
            float e1y = pCurr.y - pPrev.y;
            float e2x = pNext.x - pCurr.x;
            float e2y = pNext.y - pCurr.y;
            float cross = (e1x * e2y - e1y * e2x) * sign;
            float minAdjEdge = Math.min(edgeLen[prev], edgeLen[i]);
            float sLim = targetSkirtPx;
            if (cross < 0f) {
                sLim = Math.min(sLim, 0.75f * minAdjEdge);
            } else if (cross > 0f) {
                // Clamp against convex focal-point caustic (evolute) on sharp recoil meniscus tips
                float sinTurn = clamp(cross / Math.max(1e-5f, edgeLen[prev] * edgeLen[i]), 0f, 1f);
                if (sinTurn > 0.18f) {
                    float focalRadius = (0.48f * minAdjEdge) / sinTurn;
                    sLim = Math.min(sLim, focalRadius);
                }
            }
            maxS[i] = sLim;
        }

        // Clamp inward ray distance against every non-incident boundary segment so narrow necks & tips never cross the medial axis
        for (int i = 0; i < n; i++) {
            Vector2f p = poly.get(i);
            float rxi = rx[i];
            float ryi = ry[i];
            int prevEdge = (i + n - 1) % n;
            for (int j = 0; j < n; j++) {
                if (j == i || j == prevEdge) continue;
                Vector2f a = poly.get(j);
                Vector2f b = poly.get((j + 1) % n);
                float abx = b.x - a.x;
                float aby = b.y - a.y;
                float abSq = abx * abx + aby * aby;
                float t = (abSq > 1e-6f) ? clamp(((p.x - a.x) * abx + (p.y - a.y) * aby) / abSq, 0f, 1f) : 0f;
                float vx = (a.x + t * abx) - p.x;
                float vy = (a.y + t * aby) - p.y;
                float d = (float) Math.sqrt(vx * vx + vy * vy);
                if (d < 1e-4f) continue;
                float proj = vx * rxi + vy * ryi;
                if (proj > 0.12f * d) {
                    float clearLim = 0.38f * d;
                    if (clearLim < maxS[i]) {
                        maxS[i] = clearLim;
                    }
                }
            }
        }

        float[] s = new float[n];
        System.arraycopy(maxS, 0, s, 0, n);
        for (int pass = 0; pass < 4; pass++) {
            for (int i = 0; i < n; i++) {
                int nxt = (i + 1) % n;
                float lim = s[i] + 0.38f * edgeLen[i];
                if (s[nxt] > lim) s[nxt] = lim;
            }
            for (int i = n - 1; i >= 0; i--) {
                int nxt = (i + 1) % n;
                float lim = s[nxt] + 0.38f * edgeLen[i];
                if (s[i] > lim) s[i] = lim;
            }
        }
        float[] sm = new float[n];
        for (int i = 0; i < n; i++) {
            int prev = (i + n - 1) % n;
            int nxt = (i + 1) % n;
            float avg = 0.20f * s[prev] + 0.60f * s[i] + 0.20f * s[nxt];
            sm[i] = clamp(Math.min(avg, maxS[i]), 0.35f, targetSkirtPx);
        }

        List<Vector2f> inner = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Vector2f p = poly.get(i);
            inner.add(new Vector2f(p.x + rx[i] * sm[i], p.y + ry[i] * sm[i]));
        }
        return inner;
    }

    private void renderAllDivisionPolygonsBatched(
            List<RenderedDivisionPolygon> renderedPolys,
            float basePolyAlpha) {
        if (renderedPolys == null || renderedPolys.isEmpty()) return;

        // Pass 1: Single batched GL_TRIANGLES draw for translucent inner plateau + smooth inward gradient skirt
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int pIdx = 0, pLen = renderedPolys.size(); pIdx < pLen; pIdx++) {
            RenderedDivisionPolygon rp = renderedPolys.get(pIdx);
            List<Vector2f> outer = rp.displayPolyScreen;
            int n = (outer != null) ? outer.size() : 0;
            if (n < 3) continue;
            List<Vector2f> inner = (rp.innerGlowPolyScreen != null && rp.innerGlowPolyScreen.size() == n)
                    ? rp.innerGlowPolyScreen
                    : outer;

            float effectiveAlpha = basePolyAlpha * rp.cohesionAlphaMult;
            // Holographic inward edge-bevel: luminous rim bevel at the outer perimeter feathering smoothly inward
            // to a high-clarity interior so ship sprites, status bars, and TSFC Firepower roses stay crystal-clear
            // with zero inner Mach-band crease!
            float coreAlpha = Math.min(0.026f, effectiveAlpha * 0.10f);
            float skirtOuterAlpha = Math.min(0.130f, effectiveAlpha * 0.50f);

            // 1a. Inner plateau fill (triangulated once, zero Venn-diagram multi-lobe overlap hotspots)
            GL11.glColor4f(rp.cr, rp.cg, rp.cb, coreAlpha);
            List<int[]> triangles = rp.triangles;
            if (triangles != null && !triangles.isEmpty()) {
                for (int t = 0, tLen = triangles.size(); t < tLen; t++) {
                    int[] tri = triangles.get(t);
                    Vector2f v0 = inner.get(tri[0]);
                    Vector2f v1 = inner.get(tri[1]);
                    Vector2f v2 = inner.get(tri[2]);
                    GL11.glVertex2f(v0.x, v0.y);
                    GL11.glVertex2f(v1.x, v1.y);
                    GL11.glVertex2f(v2.x, v2.y);
                }
            } else {
                Vector2f v0 = inner.get(0);
                for (int i = 1; i < n - 1; i++) {
                    Vector2f v1 = inner.get(i);
                    Vector2f v2 = inner.get(i + 1);
                    GL11.glVertex2f(v0.x, v0.y);
                    GL11.glVertex2f(v1.x, v1.y);
                    GL11.glVertex2f(v2.x, v2.y);
                }
            }

            // 1b. Smooth outer-to-inner gradient skirt (2 triangles per contour edge in the same GL_TRIANGLES batch)
            if (inner != outer) {
                for (int i = 0; i < n; i++) {
                    int nxt = (i + 1) % n;
                    Vector2f o0 = outer.get(i);
                    Vector2f o1 = outer.get(nxt);
                    Vector2f i0 = inner.get(i);
                    Vector2f i1 = inner.get(nxt);

                    GL11.glColor4f(rp.cr, rp.cg, rp.cb, skirtOuterAlpha);
                    GL11.glVertex2f(o0.x, o0.y);
                    GL11.glVertex2f(o1.x, o1.y);
                    GL11.glColor4f(rp.cr, rp.cg, rp.cb, coreAlpha);
                    GL11.glVertex2f(i1.x, i1.y);

                    GL11.glColor4f(rp.cr, rp.cg, rp.cb, skirtOuterAlpha);
                    GL11.glVertex2f(o0.x, o0.y);
                    GL11.glColor4f(rp.cr, rp.cg, rp.cb, coreAlpha);
                    GL11.glVertex2f(i1.x, i1.y);
                    GL11.glVertex2f(i0.x, i0.y);
                }
            }
        }
        GL11.glEnd();

        // Pass 2: Crisp anti-aliased TSFC-harmonic dual-stroke perimeter rim (soft outer halo + bright tactical wire)
        for (int pIdx = 0, pLen = renderedPolys.size(); pIdx < pLen; pIdx++) {
            RenderedDivisionPolygon rp = renderedPolys.get(pIdx);
            List<Vector2f> outer = rp.displayPolyScreen;
            int n = (outer != null) ? outer.size() : 0;
            if (n < 3) continue;
            renderDivisionHarmonicRim(rp, outer, basePolyAlpha);
        }
        GL11.glLineWidth(1.0f);
    }

    private static void renderDivisionHarmonicRim(
            RenderedDivisionPolygon rp,
            List<Vector2f> outer,
            float basePolyAlpha) {
        int n = outer.size();
        float effectiveAlpha = basePolyAlpha * rp.cohesionAlphaMult;
        float haloAlpha = Math.min(0.15f, effectiveAlpha * 0.58f);
        float wireAlpha = Math.min(0.72f, Math.max(0.24f, effectiveAlpha * 2.35f));
        // Keep high-chroma Starsector phosphor saturation (only 10% crisp highlight, zero chalky pastel wash)
        float wr = Math.min(1f, rp.cr * 0.90f + 0.10f);
        float wg = Math.min(1f, rp.cg * 0.90f + 0.10f);
        float wb = Math.min(1f, rp.cb * 0.90f + 0.10f);

        GL11.glLineWidth(2.6f);
        GL11.glColor4f(rp.cr, rp.cg, rp.cb, haloAlpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < n; i++) {
            Vector2f v = outer.get(i);
            GL11.glVertex2f(v.x, v.y);
        }
        GL11.glEnd();

        GL11.glLineWidth(1.35f);
        GL11.glColor4f(wr, wg, wb, wireAlpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < n; i++) {
            Vector2f v = outer.get(i);
            GL11.glVertex2f(v.x, v.y);
        }
        GL11.glEnd();
    }

    private void renderSingleDivisionPolygon(
            RenderedDivisionPolygon rp,
            float basePolyAlpha) {
        List<Vector2f> outer = rp.displayPolyScreen;
        int n = (outer != null) ? outer.size() : 0;
        if (n < 3) return;
        List<Vector2f> inner = (rp.innerGlowPolyScreen != null && rp.innerGlowPolyScreen.size() == n)
                ? rp.innerGlowPolyScreen
                : outer;

        float effectiveAlpha = basePolyAlpha * rp.cohesionAlphaMult;
        float coreAlpha = Math.min(0.026f, effectiveAlpha * 0.10f);
        float skirtOuterAlpha = Math.min(0.130f, effectiveAlpha * 0.50f);

        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glColor4f(rp.cr, rp.cg, rp.cb, coreAlpha);
        List<int[]> triangles = rp.triangles;
        if (triangles != null && !triangles.isEmpty()) {
            for (int[] tri : triangles) {
                Vector2f v0 = inner.get(tri[0]);
                Vector2f v1 = inner.get(tri[1]);
                Vector2f v2 = inner.get(tri[2]);
                GL11.glVertex2f(v0.x, v0.y);
                GL11.glVertex2f(v1.x, v1.y);
                GL11.glVertex2f(v2.x, v2.y);
            }
        } else {
            Vector2f v0 = inner.get(0);
            for (int i = 1; i < n - 1; i++) {
                Vector2f v1 = inner.get(i);
                Vector2f v2 = inner.get(i + 1);
                GL11.glVertex2f(v0.x, v0.y);
                GL11.glVertex2f(v1.x, v1.y);
                GL11.glVertex2f(v2.x, v2.y);
            }
        }
        if (inner != outer) {
            for (int i = 0; i < n; i++) {
                int nxt = (i + 1) % n;
                Vector2f o0 = outer.get(i);
                Vector2f o1 = outer.get(nxt);
                Vector2f i0 = inner.get(i);
                Vector2f i1 = inner.get(nxt);

                GL11.glColor4f(rp.cr, rp.cg, rp.cb, skirtOuterAlpha);
                GL11.glVertex2f(o0.x, o0.y);
                GL11.glVertex2f(o1.x, o1.y);
                GL11.glColor4f(rp.cr, rp.cg, rp.cb, coreAlpha);
                GL11.glVertex2f(i1.x, i1.y);

                GL11.glColor4f(rp.cr, rp.cg, rp.cb, skirtOuterAlpha);
                GL11.glVertex2f(o0.x, o0.y);
                GL11.glColor4f(rp.cr, rp.cg, rp.cb, coreAlpha);
                GL11.glVertex2f(i1.x, i1.y);
                GL11.glVertex2f(i0.x, i0.y);
            }
        }
        GL11.glEnd();

        renderDivisionHarmonicRim(rp, outer, basePolyAlpha);
        GL11.glLineWidth(1.0f);
    }

    /**
     * Feature A: Renders Strike Craft & Voidstrike air-package polygons on their own dedicated layer above warship
     * division polygons, using inward gradient shading (concave-safe ear-clipped core + soft edge skirt) and
     * uniform arc-length-parameterized dashed borders.
     */
    private void renderStrikeCraftDivisionPolygons(
            List<RenderedDivisionPolygon> strikePolys,
            float basePolyAlpha,
            float fade) {
        if (strikePolys == null || strikePolys.isEmpty()) return;

        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int pIdx = 0, pLen = strikePolys.size(); pIdx < pLen; pIdx++) {
            RenderedDivisionPolygon rp = strikePolys.get(pIdx);
            List<Vector2f> outer = rp.displayPolyScreen;
            int n = (outer != null) ? outer.size() : 0;
            if (n < 3) continue;
            List<Vector2f> inner = (rp.innerGlowPolyScreen != null && rp.innerGlowPolyScreen.size() == n)
                    ? rp.innerGlowPolyScreen
                    : outer;

            float effectiveAlpha = basePolyAlpha * rp.cohesionAlphaMult;
            float coreAlpha = Math.min(0.015f, effectiveAlpha * 0.06f);
            float skirtOuterAlpha = Math.min(0.088f, effectiveAlpha * 0.35f);

            GL11.glColor4f(rp.cr, rp.cg, rp.cb, coreAlpha);
            List<int[]> triangles = rp.triangles;
            if (triangles != null && !triangles.isEmpty()) {
                for (int t = 0, tLen = triangles.size(); t < tLen; t++) {
                    int[] tri = triangles.get(t);
                    Vector2f v0 = inner.get(tri[0]);
                    Vector2f v1 = inner.get(tri[1]);
                    Vector2f v2 = inner.get(tri[2]);
                    GL11.glVertex2f(v0.x, v0.y);
                    GL11.glVertex2f(v1.x, v1.y);
                    GL11.glVertex2f(v2.x, v2.y);
                }
            } else {
                Vector2f v0 = inner.get(0);
                for (int i = 1; i < n - 1; i++) {
                    Vector2f v1 = inner.get(i);
                    Vector2f v2 = inner.get(i + 1);
                    GL11.glVertex2f(v0.x, v0.y);
                    GL11.glVertex2f(v1.x, v1.y);
                    GL11.glVertex2f(v2.x, v2.y);
                }
            }

            if (inner != outer) {
                for (int i = 0; i < n; i++) {
                    int nxt = (i + 1) % n;
                    Vector2f o0 = outer.get(i);
                    Vector2f o1 = outer.get(nxt);
                    Vector2f i0 = inner.get(i);
                    Vector2f i1 = inner.get(nxt);

                    GL11.glColor4f(rp.cr, rp.cg, rp.cb, skirtOuterAlpha);
                    GL11.glVertex2f(o0.x, o0.y);
                    GL11.glVertex2f(o1.x, o1.y);
                    GL11.glColor4f(rp.cr, rp.cg, rp.cb, coreAlpha);
                    GL11.glVertex2f(i1.x, i1.y);

                    GL11.glColor4f(rp.cr, rp.cg, rp.cb, skirtOuterAlpha);
                    GL11.glVertex2f(o0.x, o0.y);
                    GL11.glColor4f(rp.cr, rp.cg, rp.cb, coreAlpha);
                    GL11.glVertex2f(i1.x, i1.y);
                    GL11.glVertex2f(i0.x, i0.y);
                }
            }
        }
        GL11.glEnd();

        for (int pIdx = 0, pLen = strikePolys.size(); pIdx < pLen; pIdx++) {
            RenderedDivisionPolygon rp = strikePolys.get(pIdx);
            List<Vector2f> poly = rp.displayPolyScreen;
            if (poly == null || poly.size() < 3) continue;
            renderStrikeCraftDashedBorder(rp, poly, fade, basePolyAlpha);
        }
        GL11.glLineWidth(1.0f);
    }

    /**
     * Draws a uniform arc-length-parameterized anti-aliased dashed border around a Strike Craft / Voidstrike polygon.
     * Accumulating perimeter distance continuously across vertices guarantees identical dash & gap lengths on both
     * tight curved caps and straight flanks (eliminating vertex-count dash bunching).
     */
    public static void renderStrikeCraftDashedBorder(
            RenderedDivisionPolygon rp,
            List<Vector2f> poly,
            float fade) {
        renderStrikeCraftDashedBorder(rp, poly, fade, DivisionAISettings.getDivisionCircleBaseAlpha() * fade);
    }

    public static void renderStrikeCraftDashedBorder(
            RenderedDivisionPolygon rp,
            List<Vector2f> poly,
            float fade,
            float basePolyAlpha) {
        int n = (poly != null) ? poly.size() : 0;
        if (n < 3) return;

        // Measure total closed-loop perimeter so dash pitch snaps cleanly into an integer number of periods (seam-free!)
        float totalPerim = 0f;
        for (int i = 0; i < n; i++) {
            Vector2f p0 = poly.get(i);
            Vector2f p1 = poly.get((i + 1) % n);
            float dx = p1.x - p0.x;
            float dy = p1.y - p0.y;
            totalPerim += (float) Math.sqrt(dx * dx + dy * dy);
        }
        if (totalPerim < 6f) return;

        int periodCount = Math.max(6, Math.round(totalPerim / 18.5f));
        float period = totalPerim / periodCount;
        float dashLen = period * 0.50f;

        // Preserve pure air-track hue (sky-azure / solar-gold / interceptor-amber) with subtle 12% crisp highlight
        float br = Math.min(1f, rp.cr * 0.88f + 0.12f);
        float bg = Math.min(1f, rp.cg * 0.88f + 0.12f);
        float bb = Math.min(1f, rp.cb * 0.88f + 0.12f);
        float effAlpha = basePolyAlpha * rp.cohesionAlphaMult;
        float borderAlpha = Math.min(0.68f, Math.max(0.18f * fade, effAlpha * 2.25f));
        float haloAlpha = borderAlpha * 0.18f;

        // Subtle anti-aliasing underglow (tight enough that 50% dash gaps stay completely crisp)
        GL11.glLineWidth(2.2f);
        GL11.glColor4f(rp.cr, rp.cg, rp.cb, haloAlpha);
        emitArcLengthDashedLoopVertices(poly, n, period, dashLen);

        // Crisp architectural tactical core dash pass
        GL11.glLineWidth(1.25f);
        GL11.glColor4f(br, bg, bb, borderAlpha);
        emitArcLengthDashedLoopVertices(poly, n, period, dashLen);
    }

    private static void emitArcLengthDashedLoopVertices(
            List<Vector2f> poly,
            int n,
            float period,
            float dashLen) {
        GL11.glBegin(GL11.GL_LINES);
        float cumS = 0f;
        for (int i = 0; i < n; i++) {
            Vector2f p0 = poly.get(i);
            Vector2f p1 = poly.get((i + 1) % n);
            float dx = p1.x - p0.x;
            float dy = p1.y - p0.y;
            float segLen = (float) Math.sqrt(dx * dx + dy * dy);
            if (segLen < 1e-4f) continue;
            float ux = dx / segLen;
            float uy = dy / segLen;

            float localPos = 0f;
            while (localPos < segLen - 1e-4f) {
                float sGlobal = cumS + localPos;
                float phase = sGlobal - (float) Math.floor(sGlobal / period) * period;
                if (phase < dashLen - 1e-4f) {
                    float remDash = dashLen - phase;
                    float segStep = Math.min(remDash, segLen - localPos);
                    if (segStep > 0.15f) {
                        float xA = p0.x + ux * localPos;
                        float yA = p0.y + uy * localPos;
                        float xB = p0.x + ux * (localPos + segStep);
                        float yB = p0.y + uy * (localPos + segStep);
                        GL11.glVertex2f(xA, yA);
                        GL11.glVertex2f(xB, yB);
                    }
                    localPos += segStep;
                } else {
                    float remGap = period - phase;
                    localPos += Math.max(1e-3f, Math.min(remGap, segLen - localPos));
                }
            }
            cumS += segLen;
        }
        GL11.glEnd();
    }

    /**
     * Re-renders the Command Window warroom ship icons (sprites, facing arrows, selection rings,
     * and HP/CR bars) on the TOP LAYER above the division polygons so every ship is immediately
     * identifiable for rapid order issuing.
     */
    private void renderShipSpritesOnTopLayer(
            List<RenderedDivisionPolygon> renderedPolys,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {
        if (mapDisplayInstance == null
                || mapGetIconHandle == null
                || iconRenderSpriteHandle == null
                || iconRenderBarsHandle == null
                || renderedPolys.isEmpty()) {
            return;
        }

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glTranslatef((float) ((int) offsetX), (float) ((int) offsetY), 0.0f);

        try {
            List<Object> resolvedIcons = new ArrayList<>(64);
            for (int pIdx = 0, pLen = renderedPolys.size(); pIdx < pLen; pIdx++) {
                RenderedDivisionPolygon rp = renderedPolys.get(pIdx);
                List<ShipAPI> members = rp.hullMembers;
                for (int sIdx = 0, sLen = members.size(); sIdx < sLen; sIdx++) {
                    ShipAPI ship = members.get(sIdx);
                    if (ship == null || !ship.isAlive()) continue;
                    Object icon = mapGetIconHandle.invoke(mapDisplayInstance, (Object) ship);
                    if (icon != null) {
                        resolvedIcons.add(icon);
                        iconRenderSpriteHandle.invoke(icon, false, scale, fade);
                    }
                }
            }
            for (int i = 0, n = resolvedIcons.size(); i < n; i++) {
                iconRenderBarsHandle.invoke(resolvedIcons.get(i), false, scale, fade);
            }
        } catch (Throwable ignored) {
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    /**
     * Renders only the lightweight Warroom tactical HP/flux/CR status bars above TSFC overlays
     * (paired with {@link TsfcWarroomRenderer#renderWarroomShipSpritesAboveTsfc}) so TSFC stays
     * underneath ship sprites and status bars without invoking vanilla's weapon-turret loop.
     */
    private void renderShipBarsAboveTsfc(
            List<RenderedDivisionPolygon> renderedPolys,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {
        if (mapDisplayInstance == null
                || mapGetIconHandle == null
                || iconRenderBarsHandle == null
                || renderedPolys.isEmpty()) {
            return;
        }

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glTranslatef((float) ((int) offsetX), (float) ((int) offsetY), 0.0f);

        try {
            for (int pIdx = 0, pLen = renderedPolys.size(); pIdx < pLen; pIdx++) {
                RenderedDivisionPolygon rp = renderedPolys.get(pIdx);
                List<ShipAPI> members = rp.hullMembers;
                for (int sIdx = 0, sLen = members.size(); sIdx < sLen; sIdx++) {
                    ShipAPI ship = members.get(sIdx);
                    if (ship == null || !ship.isAlive()) continue;
                    Object icon = mapGetIconHandle.invoke(mapDisplayInstance, (Object) ship);
                    if (icon != null) {
                        iconRenderBarsHandle.invoke(icon, false, scale, fade);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    /**
     * Disables vanilla Warroom regular command sprites ({@code com.fs.starfarer.combat.new.A} /
     * {@code com.fs.starfarer.ui.B.new} and {@code com.fs.starfarer.combat.new.o0Oo}) when any TSFC overlay is active
     * and {@code T-S-F-C Mini Ship Sprites} is enabled in Mod Settings, so that only the crisp 1:1 mini
     * red/green/yellow ship silhouettes render above TSFC, and restores them when TSFC or the setting is turned off.
     */
    void syncRegularCommandSpritesVisibility(boolean tsfcActive) {
        if (mapDisplayInstance == null
                || (mdIconsMapGetter == null && mdIconSetGetter == null)
                || warroomShipIconClass == null
                || shipIconBNewGetter == null
                || bNewSpriteGetter == null
                || spriteTexGetter == null
                || spriteTexSetter == null) {
            return;
        }
        if (!tsfcActive && !regularCommandSpritesSuppressed && savedIconSpriteTextures.isEmpty()) {
            return;
        }
        try {
            if (tsfcActive && engine != null && mapGetIconHandle != null) {
                try {
                    List<ShipAPI> ships = engine.getShips();
                    if (ships != null) {
                        for (int i = 0, n = ships.size(); i < n; i++) {
                            ShipAPI s = ships.get(i);
                            if (s != null && !s.isExpired()) {
                                Object ignoredIcon = mapGetIconHandle.invoke(mapDisplayInstance, (Object) s);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (mdIconsMapGetter != null) {
                Object mapObj = mdIconsMapGetter.invoke(mapDisplayInstance);
                if (mapObj instanceof Map) {
                    for (Object icon : ((Map<?, ?>) mapObj).values()) {
                        applyCommandSpriteSuppressionToIcon(icon, tsfcActive);
                    }
                }
            }
            if (mdIconSetGetter != null) {
                Object setObj = mdIconSetGetter.invoke(mapDisplayInstance);
                if (setObj instanceof Set) {
                    for (Object icon : (Set<?>) setObj) {
                        applyCommandSpriteSuppressionToIcon(icon, tsfcActive);
                    }
                }
            }
            if (!tsfcActive && !savedIconSpriteTextures.isEmpty()) {
                for (Map.Entry<Object, Object> e : savedIconSpriteTextures.entrySet()) {
                    Object sprite = e.getKey();
                    Object origTex = e.getValue();
                    if (sprite != null && origTex != null && spriteTexGetter.invoke(sprite) == null) {
                        spriteTexSetter.invoke(sprite, origTex);
                    }
                }
                savedIconSpriteTextures.clear();
            }
            regularCommandSpritesSuppressed = tsfcActive;
        } catch (Throwable t) {
            LOG.warn("[DivisionAI] syncRegularCommandSpritesVisibility failed", t);
        }
    }

    private void applyCommandSpriteSuppressionToIcon(Object icon, boolean suppress) throws Throwable {
        if (icon == null) return;
        if (warroomShipIconClass.isInstance(icon)) {
            Object bNew = shipIconBNewGetter.invoke(icon);
            if (bNew != null) {
                Object sprite = bNewSpriteGetter.invoke(bNew);
                suppressOrRestoreSpriteTexture(sprite, suppress);
                if (bNewHoloSetter != null) bNewHoloSetter.invoke(bNew, !suppress);
                if (bNewModulesSetter != null) bNewModulesSetter.invoke(bNew, !suppress);
                if (bNewStatusSetter != null) bNewStatusSetter.invoke(bNew, !suppress);
            }
            if (shipIconArrowSpriteGetter != null) {
                Object arrowSprite = shipIconArrowSpriteGetter.invoke(icon);
                suppressOrRestoreSpriteTexture(arrowSprite, suppress);
            }
        } else if (warroomFighterIconClass != null && warroomFighterIconClass.isInstance(icon) && fighterIconSpriteGetter != null) {
            Object fighterSprite = fighterIconSpriteGetter.invoke(icon);
            suppressOrRestoreSpriteTexture(fighterSprite, suppress);
        }
    }

    private void suppressOrRestoreSpriteTexture(Object sprite, boolean suppress) throws Throwable {
        if (sprite == null) return;
        if (suppress) {
            Object currTex = spriteTexGetter.invoke(sprite);
            if (currTex != null) {
                savedIconSpriteTextures.put(sprite, currTex);
                spriteTexSetter.invoke(sprite, (Object) null);
            }
        } else {
            if (spriteTexGetter.invoke(sprite) == null) {
                Object savedTex = savedIconSpriteTextures.remove(sprite);
                if (savedTex != null) {
                    spriteTexSetter.invoke(sprite, savedTex);
                } else if (spriteReadResolveHandle != null) {
                    spriteReadResolveHandle.invoke(sprite);
                }
            }
        }
    }

    /**
     * Renders a gold star on the upper-right corner of the Fleet flagship(s) (player fleet flagship,
     * allied fleet flagship(s), enemy fleet flagship(s)) and a silver star on the upper-right corner
     * of every Division flagship in the Tab Command Window.
     */
    private void renderFlagshipStarsOnTopLayer(
            FogOfWarAPI playerFog,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {
        if (engine == null) return;
        Set<ShipAPI> fleetFlagships = resolveFleetFlagships(playerFog);
        Set<ShipAPI> divisionFlagships = resolveDivisionFlagships(playerFog, fleetFlagships);
        renderFlagshipStarsOnTopLayer(fleetFlagships, divisionFlagships, scale, offsetX, offsetY, fade);
    }

    private void renderFlagshipStarsOnTopLayer(
            Set<ShipAPI> fleetFlagships,
            Set<ShipAPI> divisionFlagships,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {
        if (engine == null) return;
        if ((fleetFlagships == null || fleetFlagships.isEmpty())
                && (divisionFlagships == null || divisionFlagships.isEmpty())) {
            return;
        }

        try {
            if (cachedGoldStarSprite == null || cachedGoldStarSprite.getTextureId() <= 0) {
                cachedGoldStarSprite = Global.getSettings().getSprite("division_ai", "star_gold");
            }
            if (cachedSilverStarSprite == null || cachedSilverStarSprite.getTextureId() <= 0) {
                cachedSilverStarSprite = Global.getSettings().getSprite("division_ai", "star_silver");
            }
        } catch (Throwable ignored) {
            return;
        }
        SpriteAPI goldStar = cachedGoldStarSprite;
        SpriteAPI silverStar = cachedSilverStarSprite;
        if (goldStar == null && silverStar == null) return;

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        try {
            if (silverStar != null && divisionFlagships != null) {
                for (ShipAPI ship : divisionFlagships) {
                    renderShipCornerStar(ship, silverStar, false, scale, offsetX, offsetY, fade);
                }
            }
            if (goldStar != null && fleetFlagships != null) {
                for (ShipAPI ship : fleetFlagships) {
                    renderShipCornerStar(ship, goldStar, false, scale, offsetX, offsetY, fade);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void renderShipCornerStar(
            ShipAPI ship,
            SpriteAPI starSprite,
            boolean upperRightCorner,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {
        if (ship == null || !ship.isAlive() || ship.getLocation() == null) return;

        float iconRadiusPx = 0f;
        if (ship.isStation() && mapDisplayInstance != null && mapGetIconHandle != null && iconGetRadiusHandle != null) {
            try {
                Object icon = mapGetIconHandle.invoke(mapDisplayInstance, (Object) ship);
                if (icon != null) {
                    iconRadiusPx = (float) iconGetRadiusHandle.invoke(icon, scale);
                }
            } catch (Throwable ignored) {
                iconRadiusPx = 0f;
            }
        }
        float zoom = clamp(scale * 1000f / 75f, 0.65f, 1.65f);
        if (iconRadiusPx <= 2f) {
            iconRadiusPx = Math.max(12f * zoom, ship.getCollisionRadius() * 1.1f * scale + 75f * scale);
        }

        float sx = ship.getLocation().x * scale + (float) ((int) offsetX);
        float sy = ship.getLocation().y * scale + (float) ((int) offsetY);

        // In com.fs.starfarer.ui.B.new, the commander portrait sits on the upper-left of the ship icon:
        // boxHalf = iconRadiusPx * (16.8f / 18.0f), portraitSize = boxHalf * 0.5f,
        // portraitCenter = (sx - boxHalf + portraitSize * 0.5f + 2f * zoom, sy + boxHalf - portraitSize * 0.5f - 2f * zoom).
        // Per Item D: If a commander exists, the star renders directly under the commander portrait on the left side
        // of the ship icon; if there is no commander, the star replaces the commander portrait.
        // In com.fs.starfarer.combat.new.A and com.fs.starfarer.ui.B.new, every non-station ship's
        // tactical map widget uses a fixed 42x42 base box scaled by bScale = scale * 1000f / 75f
        // (stations use iconRadiusPx * 1.7f). Within B.new:
        //   boxHalf = 21.0f * bScale, portraitSize = 10.5f * bScale (width / 4),
        //   portraitCenter = (sx - boxHalf + portraitSize * 0.5f + 2f * bScale,
        //                     sy + boxHalf - portraitSize * 0.5f - 2f * bScale).
        // Using the exact B.new boxHalf rather than hull-size-dependent iconRadiusPx ensures the
        // Fleet Flagship gold star (including on large Capital flagships) is centered directly under
        // the commander portrait at every zoom level.
        float bScale = scale * 1000f / 75f;
        float boxHalf = ship.isStation() ? (iconRadiusPx * 0.85f) : (21.0f * bScale);
        float portraitSize = boxHalf * 0.5f;
        float portraitCenterX = sx - boxHalf + portraitSize * 0.5f + 2.0f * bScale;
        float portraitCenterY = sy + boxHalf - portraitSize * 0.5f - 2.0f * bScale;
        boolean hasCommander = (ship.getCaptain() != null && !ship.getCaptain().isDefault());

        float starSize = (float) (Math.round(clamp(16.0f * zoom, 12.0f, 22.0f) * 0.5f) * 2);
        float starX = portraitCenterX;
        float starY = hasCommander
                ? (portraitCenterY - portraitSize * 0.5f - starSize * 0.5f - 1.5f * bScale)
                : portraitCenterY;

        starSprite.setSize(starSize, starSize);
        starSprite.setColor(Color.WHITE);
        starSprite.setAlphaMult(Math.min(1f, fade * 0.96f));
        starSprite.setAngle(0f);
        starSprite.renderAtCenter(starX, starY);
    }

    public Set<ShipAPI> resolveFleetFlagships(FogOfWarAPI playerFog) {
        Set<ShipAPI> result = new HashSet<>();
        if (engine == null) return result;

        boolean hasPlayerFleetFlag = false;
        boolean hasAlliedFleetFlag = false;
        boolean hasEnemyFleetFlag = false;

        ShipAPI alphaCoreFlagOverride = DivisionOtcCommandManager.getPlayerConvergenceFlagshipOverride();
        if (alphaCoreFlagOverride != null && isEligibleCommandMapShip(alphaCoreFlagOverride, playerFog)) {
            result.add(alphaCoreFlagOverride);
            hasPlayerFleetFlag = true;
        } else if (playerGroup != null && !playerGroup.divisions.isEmpty()) {
            ShipAPI resolvedPlayerFlag = DivisionOtcCommandManager.resolveActiveFleetFlagship(engine, playerGroup);
            if (resolvedPlayerFlag != null && isEligibleCommandMapShip(resolvedPlayerFlag, playerFog)) {
                result.add(resolvedPlayerFlag);
                hasPlayerFleetFlag = true;
            }
        }

        ShipAPI playerPiloted = engine.getPlayerShip();
        for (ShipAPI s : engine.getShips()) {
            if (!isEligibleCommandMapShip(s, playerFog)) continue;
            if (hasPlayerFleetFlag && s.getOwner() == 0 && !s.isAlly()) continue;
            boolean isDesignatedFlag = (s == playerPiloted)
                    || (s.getFleetMember() != null && s.getFleetMember().isFlagship());
            if (isDesignatedFlag) {
                result.add(s);
                if (s.getOwner() == 0 && !s.isAlly()) hasPlayerFleetFlag = true;
                else if (s.getOwner() == 0 && s.isAlly()) hasAlliedFleetFlag = true;
                else if (s.getOwner() == 1) hasEnemyFleetFlag = true;
            }
        }

        // Fallback for custom/skirmish/simulator battles or when a fleet's campaign flagship isn't marked via FleetMemberAPI.isFlagship()
        if (!hasPlayerFleetFlag) {
            ShipAPI fallback = resolveFallbackBattleGroupFlagship(playerGroup, 0, false, playerFog);
            if (fallback != null) result.add(fallback);
        }
        if (!hasAlliedFleetFlag) {
            ShipAPI fallback = resolveFallbackBattleGroupFlagship(alliedAiGroup, 0, true, playerFog);
            if (fallback != null) result.add(fallback);
        }
        if (!hasEnemyFleetFlag) {
            ShipAPI fallback = resolveFallbackBattleGroupFlagship(enemyGroup, 1, false, playerFog);
            if (fallback != null) result.add(fallback);
        }
        return result;
    }

    private ShipAPI resolveFallbackBattleGroupFlagship(
            BattleGroupState group,
            int owner,
            boolean isAlly,
            FogOfWarAPI playerFog) {
        if (group == null) return null;
        if (group.otc != null && group.otc.person != null && !group.otc.person.isDefault()) {
            for (ShipAPI s : engine.getShips()) {
                if (!isEligibleCommandMapShip(s, playerFog)) continue;
                if (s.getOwner() != owner || s.isAlly() != isAlly) continue;
                if (s.getCaptain() == group.otc.person) {
                    return s;
                }
            }
        }
        ShipAPI bestFlag = null;
        float bestFp = -1f;
        for (DivisionState div : group.divisions) {
            List<ShipAPI> alive = div.getAliveMembers();
            if (alive.isEmpty()) continue;
            ShipAPI flag = resolveActiveFlagship(div, alive);
            if (flag == null || !isEligibleCommandMapShip(flag, playerFog)) continue;
            float fp = ShipCapabilityProfile.resolveShipFpStrength(flag);
            if (fp > bestFp) {
                bestFp = fp;
                bestFlag = flag;
            }
        }
        return bestFlag;
    }

    public Set<ShipAPI> resolveDivisionFlagships(FogOfWarAPI playerFog, Set<ShipAPI> fleetFlagships) {
        Set<ShipAPI> result = new HashSet<>();
        BattleGroupState[] groups = new BattleGroupState[] { playerGroup, alliedAiGroup, enemyGroup };
        for (BattleGroupState group : groups) {
            if (group == null || group.divisions.isEmpty()) continue;
            for (DivisionState div : group.divisions) {
                List<ShipAPI> alive = div.getAliveMembers();
                if (alive.isEmpty()) continue;
                ShipAPI flag = resolveActiveFlagship(div, alive);
                if (flag == null || !isEligibleCommandMapShip(flag, playerFog)) continue;
                if (fleetFlagships == null || !fleetFlagships.contains(flag)) {
                    result.add(flag);
                }
            }
        }
        return result;
    }

    private static boolean isEligibleCommandMapShip(ShipAPI s, FogOfWarAPI playerFog) {
        if (s == null || !s.isAlive() || s.isHulk() || s.isExpired()) return false;
        if (s.isFighter() || s.isShuttlePod() || s.isStationModule()) return false;
        if (s.getOwner() == 1 && !isEnemyShipVisibleOnCommandMap(s, playerFog)) return false;
        return true;
    }

    /**
     * Precomputes every pair of overlapping warship division polygons, their exact convex intersection
     * via Sutherland-Hodgman clipping, and their 45-degree diagonal stripe bands so cached/panned/zoomed
     * Warroom render frames draw overlap stripes with zero geometry clipping cost.
     */
    private void buildOverlappingPolygonStripesCache(
            List<RenderedDivisionPolygon> renderedPolys,
            List<CachedOverlapStripeBand> outBands) {

        int n = renderedPolys.size();
        for (int i = 0; i < n; i++) {
            RenderedDivisionPolygon a = renderedPolys.get(i);
            if (a.isStrikeCraftPolygon || a.cohesionAlphaMult < 0.35f) continue;
            for (int j = i + 1; j < n; j++) {
                RenderedDivisionPolygon b = renderedPolys.get(j);
                if (b.isStrikeCraftPolygon || a.div == b.div) continue;
                if (b.cohesionAlphaMult < 0.35f) continue;

                // Only render contested diagonal stripes when OPPOSING or distinct battle groups overlap
                boolean sameSide = (a.isEnemy == b.isEnemy) && (a.isAlliedAi == b.isAlliedAi);
                if (sameSide) {
                    continue;
                }

                // Fast AABB reject
                if (a.maxX < b.minX || b.maxX < a.minX || a.maxY < b.minY || b.maxY < a.minY) {
                    continue;
                }

                // Verify that at least one pair of actual ship footprints from A and B overlap in world space
                boolean shipFootprintsOverlap = false;
                for (ShipAPI sa : a.hullMembers) {
                    float sax = sa.getLocation().x;
                    float say = sa.getLocation().y;
                    float raW = getMemberFootprintRadiusWorld(sa);
                    for (ShipAPI sb : b.hullMembers) {
                        float dx = sax - sb.getLocation().x;
                        float dy = say - sb.getLocation().y;
                        float maxOverlapW = (raW + getMemberFootprintRadiusWorld(sb)) * 1.05f;
                        if (dx * dx + dy * dy <= maxOverlapW * maxOverlapW) {
                            shipFootprintsOverlap = true;
                            break;
                        }
                    }
                    if (shipFootprintsOverlap) break;
                }
                if (!shipFootprintsOverlap) {
                    continue;
                }

                List<Vector2f> polyA = a.displayPolyScreen;
                List<Vector2f> polyB = b.displayPolyScreen;
                if (polyA == null || polyB == null || polyA.size() < 3 || polyB.size() < 3) {
                    continue;
                }
                if (a.clipHullScreen == null) {
                    a.clipHullScreen = computeConvexHullCCW(polyA);
                }
                if (b.clipHullScreen == null) {
                    b.clipHullScreen = computeConvexHullCCW(polyB);
                }
                List<Vector2f> hullA = a.clipHullScreen;
                List<Vector2f> hullB = b.clipHullScreen;
                List<Vector2f> hullInter = clipConvexPolygon(hullA, hullB);
                if (hullInter.size() < 3 || computePolygonArea(hullInter) < 320f) {
                    continue;
                }

                float polyAreaA = Math.max(1f, computePolygonArea(polyA));
                float polyAreaB = Math.max(1f, computePolygonArea(polyB));
                boolean aConcave = computePolygonArea(hullA) > polyAreaA * 1.07f && a.triangles != null && !a.triangles.isEmpty();
                boolean bConcave = computePolygonArea(hullB) > polyAreaB * 1.07f && b.triangles != null && !b.triangles.isEmpty();

                List<List<Vector2f>> overlapCells;
                if (!aConcave && !bConcave) {
                    overlapCells = java.util.Collections.singletonList(hullInter);
                } else {
                    float ix0 = Float.MAX_VALUE, ix1 = -Float.MAX_VALUE;
                    float iy0 = Float.MAX_VALUE, iy1 = -Float.MAX_VALUE;
                    for (int k = 0, kn = hullInter.size(); k < kn; k++) {
                        Vector2f iv = hullInter.get(k);
                        if (iv.x < ix0) ix0 = iv.x;
                        if (iv.x > ix1) ix1 = iv.x;
                        if (iv.y < iy0) iy0 = iv.y;
                        if (iv.y > iy1) iy1 = iv.y;
                    }
                    overlapCells = new ArrayList<>();
                    float totalOverlapArea = 0f;
                    List<Vector2f> triPolyA = new ArrayList<>(3);
                    if (aConcave && !bConcave) {
                        List<int[]> trisA = a.triangles;
                        for (int ta = 0, nA = trisA.size(); ta < nA; ta++) {
                            int[] ia = trisA.get(ta);
                            Vector2f a0 = polyA.get(ia[0]), a1 = polyA.get(ia[1]), a2 = polyA.get(ia[2]);
                            if (Math.max(a0.x, Math.max(a1.x, a2.x)) < ix0 || Math.min(a0.x, Math.min(a1.x, a2.x)) > ix1
                                    || Math.max(a0.y, Math.max(a1.y, a2.y)) < iy0 || Math.min(a0.y, Math.min(a1.y, a2.y)) > iy1) {
                                continue;
                            }
                            triPolyA.clear();
                            triPolyA.add(a0);
                            triPolyA.add(a1);
                            triPolyA.add(a2);
                            if (computeSignedPolygonArea(triPolyA) < 0f) {
                                triPolyA.set(1, a2);
                                triPolyA.set(2, a1);
                            }
                            List<Vector2f> cell = clipConvexPolygon(triPolyA, hullB);
                            if (cell.size() >= 3) {
                                float area = computePolygonArea(cell);
                                if (area > 2.0f) {
                                    overlapCells.add(cell);
                                    totalOverlapArea += area;
                                }
                            }
                        }
                    } else if (!aConcave) {
                        List<int[]> trisB = b.triangles;
                        for (int tb = 0, nB = trisB.size(); tb < nB; tb++) {
                            int[] ib = trisB.get(tb);
                            Vector2f b0 = polyB.get(ib[0]), b1 = polyB.get(ib[1]), b2 = polyB.get(ib[2]);
                            if (Math.max(b0.x, Math.max(b1.x, b2.x)) < ix0 || Math.min(b0.x, Math.min(b1.x, b2.x)) > ix1
                                    || Math.max(b0.y, Math.max(b1.y, b2.y)) < iy0 || Math.min(b0.y, Math.min(b1.y, b2.y)) > iy1) {
                                continue;
                            }
                            triPolyA.clear();
                            triPolyA.add(b0);
                            triPolyA.add(b1);
                            triPolyA.add(b2);
                            if (computeSignedPolygonArea(triPolyA) < 0f) {
                                triPolyA.set(1, b2);
                                triPolyA.set(2, b1);
                            }
                            List<Vector2f> cell = clipConvexPolygon(triPolyA, hullA);
                            if (cell.size() >= 3) {
                                float area = computePolygonArea(cell);
                                if (area > 2.0f) {
                                    overlapCells.add(cell);
                                    totalOverlapArea += area;
                                }
                            }
                        }
                    } else {
                        List<int[]> trisA = a.triangles;
                        List<int[]> trisB = b.triangles;
                        List<Vector2f> triPolyB = new ArrayList<>(3);
                        for (int ta = 0, nA = trisA.size(); ta < nA; ta++) {
                            int[] ia = trisA.get(ta);
                            Vector2f a0 = polyA.get(ia[0]), a1 = polyA.get(ia[1]), a2 = polyA.get(ia[2]);
                            float ax0 = Math.min(a0.x, Math.min(a1.x, a2.x)), ax1 = Math.max(a0.x, Math.max(a1.x, a2.x));
                            float ay0 = Math.min(a0.y, Math.min(a1.y, a2.y)), ay1 = Math.max(a0.y, Math.max(a1.y, a2.y));
                            if (ax1 < ix0 || ax0 > ix1 || ay1 < iy0 || ay0 > iy1) continue;
                            triPolyA.clear();
                            triPolyA.add(a0);
                            triPolyA.add(a1);
                            triPolyA.add(a2);
                            if (computeSignedPolygonArea(triPolyA) < 0f) {
                                triPolyA.set(1, a2);
                                triPolyA.set(2, a1);
                            }
                            for (int tb = 0, nB = trisB.size(); tb < nB; tb++) {
                                int[] ib = trisB.get(tb);
                                Vector2f b0 = polyB.get(ib[0]), b1 = polyB.get(ib[1]), b2 = polyB.get(ib[2]);
                                if (ax1 < Math.min(b0.x, Math.min(b1.x, b2.x)) || ax0 > Math.max(b0.x, Math.max(b1.x, b2.x))
                                        || ay1 < Math.min(b0.y, Math.min(b1.y, b2.y)) || ay0 > Math.max(b0.y, Math.max(b1.y, b2.y))) {
                                    continue;
                                }
                                triPolyB.clear();
                                triPolyB.add(b0);
                                triPolyB.add(b1);
                                triPolyB.add(b2);
                                if (computeSignedPolygonArea(triPolyB) < 0f) {
                                    triPolyB.set(1, b2);
                                    triPolyB.set(2, b1);
                                }
                                List<Vector2f> cell = clipConvexPolygon(triPolyA, triPolyB);
                                if (cell.size() >= 3) {
                                    float area = computePolygonArea(cell);
                                    if (area > 2.0f) {
                                        overlapCells.add(cell);
                                        totalOverlapArea += area;
                                    }
                                }
                            }
                        }
                    }
                    if (overlapCells.isEmpty() || totalOverlapArea < 320f) {
                        continue;
                    }
                }

                float pairCohesion = Math.max(0.12f, Math.min(a.cohesionAlphaMult, b.cohesionAlphaMult));
                boolean sameColorFamily = (a.isEnemy == b.isEnemy) && (a.isAlliedAi == b.isAlliedAi);
                float aCr = Math.min(1f, a.cr + 0.10f);
                float aCg = Math.min(1f, a.cg + 0.10f);
                float aCb = Math.min(1f, a.cb + 0.10f);
                float bCr = sameColorFamily ? (a.cr * 0.25f) : b.cr;
                float bCg = sameColorFamily ? Math.min(1f, a.cg * 0.45f + 0.15f) : b.cg;
                float bCb = sameColorFamily ? Math.min(1f, a.cb + 0.35f) : b.cb;

                for (int cIdx = 0, cLen = overlapCells.size(); cIdx < cLen; cIdx++) {
                    List<Vector2f> cell = overlapCells.get(cIdx);
                    float minU = Float.MAX_VALUE;
                    float maxU = -Float.MAX_VALUE;
                    for (int vIdx = 0, vLen = cell.size(); vIdx < vLen; vIdx++) {
                        Vector2f v = cell.get(vIdx);
                        float u = (v.x + v.y) * INV_SQRT2;
                        if (u < minU) minU = u;
                        if (u > maxU) maxU = u;
                    }
                    int firstBand = (int) Math.floor(minU / STRIPE_PERIOD_PX);
                    int lastBand = (int) Math.ceil(maxU / STRIPE_PERIOD_PX);
                    for (int k = firstBand; k <= lastBand; k++) {
                        float s0 = k * STRIPE_PERIOD_PX;
                        float sMid = s0 + STRIPE_WIDTH_PX;
                        float s1 = s0 + STRIPE_PERIOD_PX;

                        List<Vector2f> bandA = clipPolygonToDiagonalSlab(cell, s0, sMid);
                        if (bandA.size() >= 3) {
                            outBands.add(new CachedOverlapStripeBand(bandA, aCr, aCg, aCb, 0.85f * pairCohesion, true, a, b));
                        }
                        List<Vector2f> bandB = clipPolygonToDiagonalSlab(cell, sMid, s1);
                        if (bandB.size() >= 3) {
                            outBands.add(new CachedOverlapStripeBand(bandB, bCr, bCg, bCb, 0.72f * pairCohesion, false, a, b));
                        }
                    }
                }
            }
        }
    }

    private static void translateCachedOverlapStripeBandsInPlace(List<CachedOverlapStripeBand> bands) {
        if (bands == null || bands.isEmpty()) return;
        for (int i = 0, n = bands.size(); i < n; i++) {
            CachedOverlapStripeBand band = bands.get(i);
            if (band.polyA == null || band.polyB == null
                    || band.polyA.centerScreen == null || band.polyB.centerScreen == null) {
                continue;
            }
            float liveMidX = 0.5f * (band.polyA.centerScreen.x + band.polyB.centerScreen.x);
            float liveMidY = 0.5f * (band.polyA.centerScreen.y + band.polyB.centerScreen.y);
            float dx = liveMidX - band.anchorMidX;
            float dy = liveMidY - band.anchorMidY;
            if (Math.abs(dx) < 0.05f && Math.abs(dy) < 0.05f) continue;
            band.anchorMidX = liveMidX;
            band.anchorMidY = liveMidY;
            for (int vIdx = 0, vLen = band.verts.size(); vIdx < vLen; vIdx++) {
                Vector2f v = band.verts.get(vIdx);
                v.x += dx;
                v.y += dy;
            }
        }
    }

    private void renderCachedOverlapPolygonStripes(
            List<CachedOverlapStripeBand> bands,
            float basePolyAlpha) {
        if (bands == null || bands.isEmpty()) return;
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int bIdx = 0, bLen = bands.size(); bIdx < bLen; bIdx++) {
            CachedOverlapStripeBand band = bands.get(bIdx);
            List<Vector2f> verts = band.verts;
            int n = (verts != null) ? verts.size() : 0;
            if (n < 3) continue;
            float alpha = band.isBandA
                    ? Math.min(0.19f, Math.max(0.05f, basePolyAlpha * band.alphaScale))
                    : Math.min(0.16f, Math.max(0.04f, basePolyAlpha * band.alphaScale));
            GL11.glColor4f(band.r, band.g, band.b, alpha);
            Vector2f v0 = verts.get(0);
            for (int i = 1; i < n - 1; i++) {
                Vector2f v1 = verts.get(i);
                Vector2f v2 = verts.get(i + 1);
                GL11.glVertex2f(v0.x, v0.y);
                GL11.glVertex2f(v1.x, v1.y);
                GL11.glVertex2f(v2.x, v2.y);
            }
        }
        GL11.glEnd();
    }

    /**
     * Renders the division waypoints (wide-arc out-of-contact route waypoint + final tactical goal)
     * originating directly from the locational center of the division entity itself.
     */
    private void renderDivisionWaypointsAndIntent(
            RenderedDivisionPolygon rp,
            float scale,
            float offsetX,
            float offsetY,
            float lineAlpha,
            FogOfWarAPI playerFog) {

        DivisionState div = rp.div;
        DivisionIntentType intent = div.currentIntent;

        Vector2f destWorld = resolveDivisionFinalGoalWorld(div, intent, playerFog);
        if (destWorld == null) return;

        boolean targetIsEnemy = isDirectEnemyTargetIntent(div, intent, playerFog);
        boolean drawWideArcDogleg = !targetIsEnemy
                && div.hasRouteWaypoint
                && div.routeWaypoint != null
                && (div.routeWaypoint.x != 0f || div.routeWaypoint.y != 0f);

        // Originate directly from the locational center of the division entity
        float startX = rp.centerScreen.x;
        float startY = rp.centerScreen.y;
        float tx = destWorld.x * scale + offsetX;
        float ty = destWorld.y * scale + offsetY;

        float dx = tx - startX;
        float dy = ty - startY;
        float distPx = (float) Math.sqrt(dx * dx + dy * dy);
        if (distPx < 14f) return;

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

        // Subtle origin pip at the division's locational center
        float pipR = 3.2f;
        GL11.glLineWidth(1.8f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(startX, startY + pipR);
        GL11.glVertex2f(startX + pipR, startY);
        GL11.glVertex2f(startX, startY - pipR);
        GL11.glVertex2f(startX - pipR, startY);
        GL11.glEnd();

        float startOffset = 4f;
        float endOffset = targetIsEnemy ? 14f : 6f;

        if (drawWideArcDogleg) {
            float wx = div.routeWaypoint.x * scale + offsetX;
            float wy = div.routeWaypoint.y * scale + offsetY;
            float leg1Dx = wx - startX;
            float leg1Dy = wy - startY;
            float leg1Len = (float) Math.sqrt(leg1Dx * leg1Dx + leg1Dy * leg1Dy);
            float leg2Dx = tx - wx;
            float leg2Dy = ty - wy;
            float leg2Len = (float) Math.sqrt(leg2Dx * leg2Dx + leg2Dy * leg2Dy);

            if (leg1Len > 12f && leg2Len > 12f) {
                float u2x = leg2Dx / leg2Len;
                float u2y = leg2Dy / leg2Len;

                GL11.glLineWidth(2.15f);
                drawDashedSegment(startX, startY, wx, wy, startOffset, 0f, 14f);
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
            drawDashedSegment(startX, startY, tx, ty, startOffset, endOffset, 14f);
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
                && intent != DivisionIntentType.TOT_ALPHA_STRIKE
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

    private void deconflictPrimaryDivisionLabels(List<RenderedDivisionPolygon> renderedPolys) {
        List<RenderedDivisionPolygon> primaries = new ArrayList<>();
        for (RenderedDivisionPolygon rp : renderedPolys) {
            if (rp.isPrimaryDroplet) {
                rp.labelX = rp.centerScreen.x;
                // Anchor division nameplates cleanly just outside the division's rim (below friendly rims, above enemy rims)
                // so interior ship icons, flagship stars, status bars, and TSFC Firepower roses stay 100% unobstructed!
                rp.labelY = rp.isEnemy ? (rp.maxY + 12f) : (rp.minY - 26f);
                primaries.add(rp);
            }
        }
        int m = primaries.size();
        if (m < 2) return;
        float minDx = 148f;
        float minDy = 38f;
        for (int pass = 0; pass < 4; pass++) {
            for (int i = 0; i < m; i++) {
                RenderedDivisionPolygon a = primaries.get(i);
                for (int j = i + 1; j < m; j++) {
                    RenderedDivisionPolygon b = primaries.get(j);
                    float dx = b.labelX - a.labelX;
                    float dy = b.labelY - a.labelY;
                    if (Math.abs(dx) < minDx && Math.abs(dy) < minDy) {
                        float overlapY = (minDy - Math.abs(dy)) * 0.54f;
                        float signY = (dy >= 0f) ? 1f : -1f;
                        a.labelY -= signY * overlapY;
                        b.labelY += signY * overlapY;
                    }
                }
            }
        }
    }

    /**
     * Renders the division callsign and intent/flux sub-badge centered on the division entity itself.
     */
    public static boolean isFlagshipDivision(DivisionState div, Set<ShipAPI> fleetFlagships) {
        if (div == null || fleetFlagships == null || fleetFlagships.isEmpty()) {
            return false;
        }
        if (div.flagship != null && fleetFlagships.contains(div.flagship)) {
            return true;
        }
        for (ShipAPI m : div.getAliveMembers()) {
            if (fleetFlagships.contains(m)) {
                return true;
            }
        }
        return false;
    }

    private void renderDivisionEntityLabel(
            RenderedDivisionPolygon rp,
            float fade,
            Set<ShipAPI> fleetFlagships) {
        DivisionState div = rp.div;
        float cx = rp.labelX;
        float cy = rp.labelY;

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
        String mainCacheKey = getLabelMapKey(liveCallsign, cacheKeyOverride, rp.isEnemy, rp.isAlliedAi, false);
        LabelAPI label = getOrCreateLabel(liveCallsign, cacheKeyOverride, rp.isEnemy, rp.isAlliedAi, false);
        if (label != null) {
            float textW = getCachedLabelWidth(mainCacheKey, label, liveCallsign);
            float textH = getCachedLabelHeight(mainCacheKey, label, liveCallsign);
            float labelY = cy + (rp.isEnemy ? (textH * 0.5f) : (textH + 4f));

            PermanentDivisionManager.DivisionFormation activeForm = div.activeFormation != null
                    ? div.activeFormation
                    : div.doctrinalFormation;
            float iconSize = 16f;
            float iconGap = 6f;
            float totalRowW = (activeForm != null) ? (iconSize + iconGap + textW) : textW;
            float rowStartX = cx - (totalRowW * 0.5f);
            float textX = (activeForm != null) ? (rowStartX + iconSize + iconGap) : rowStartX;
            float textCenterY = labelY + (textH * 0.5f);

            label.getPosition().setSize(textW + 16f, textH + 4f);
            label.getPosition().setLocation(textX, labelY);
            label.render(fade * (rp.isEnemy ? 0.84f : 0.95f));

            if (activeForm != null) {
                Color iconTint = new Color(
                        Math.min(255, Math.round(rp.cr * 255f + 45f)),
                        Math.min(255, Math.round(rp.cg * 255f + 45f)),
                        Math.min(255, Math.round(rp.cb * 255f + 45f)));
                FleetDoctrineDialog.renderFormationIcon(
                        activeForm,
                        rowStartX + (iconSize * 0.5f),
                        textCenterY,
                        iconSize,
                        iconTint,
                        fade * (rp.isEnemy ? 0.82f : 0.95f),
                        isFlagshipDivision(div, fleetFlagships));
            }
        }

        if (!rp.isEnemy) {
            String intentTag = (nomCat == DivisionNomenclature.NomenclatureCategory.ABYSSAL_ERROR)
                    ? "ERROR"
                    : buildCompactIntentTag(div);
            String subKeyOverride = "INTENT_SUB:" + div.id;
            String subMapKey = getLabelMapKey(intentTag, subKeyOverride, false, rp.isAlliedAi, true);
            LabelAPI subLabel = getOrCreateLabel(
                    intentTag, subKeyOverride, false, rp.isAlliedAi, true);
            if (subLabel != null) {
                float subW = getCachedLabelWidth(subMapKey, subLabel, intentTag);
                float subH = getCachedLabelHeight(subMapKey, subLabel, intentTag);
                subLabel.getPosition().setSize(subW + 10f, subH + 4f);
                subLabel.getPosition().setLocation(cx - (subW * 0.5f), cy - 2f);
                subLabel.render(fade * 0.88f);
            }
        }
    }

    /**
     * Renders "Voidstrike #" text following the live center of each active group of strike craft
     * assigned a target (merging multi-division voidstrikes into one label, disappearing as soon
     * as the strike craft return, and rendering enemy voidstrikes only when visible in playerFog).
     */
    private void renderVoidstrikeLabels(
            FogOfWarAPI playerFog,
            float scale,
            float offsetX,
            float offsetY,
            float fade,
            List<RenderedDivisionPolygon> divisionPolys,
            List<RenderedDivisionPolygon> strikeCraftPolys) {
        List<VoidstrikeCoordinator.VoidstrikeRecord> voidstrikes =
                VoidstrikeCoordinator.getActiveVoidstrikes();
        if (voidstrikes == null || voidstrikes.isEmpty()) return;

        List<Vector2f> placedLabels = new ArrayList<>();
        if (divisionPolys != null) {
            for (int i = 0, n = divisionPolys.size(); i < n; i++) {
                RenderedDivisionPolygon dp = divisionPolys.get(i);
                if (dp.isPrimaryDroplet) {
                    placedLabels.add(new Vector2f(dp.labelX, dp.labelY));
                }
            }
        }

        for (VoidstrikeCoordinator.VoidstrikeRecord vs : voidstrikes) {
            if (vs == null || vs.activeFighters.isEmpty() || vs.voidstrikeNumber <= 0) continue;

            boolean isEnemy = (vs.side == VoidstrikeCoordinator.VOIDSTRIKE_SIDE_ENEMY);
            boolean isAlly = (vs.side == VoidstrikeCoordinator.VOIDSTRIKE_SIDE_ALLIED);

            // Prefer anchoring directly to the largest visible Strike Craft envelope for this Voidstrike
            // so split-axis Pincer packages never leave "Voidstrike #" floating in empty space between arms!
            RenderedDivisionPolygon bestStrikeEnv = null;
            int bestSquadronCount = -1;
            if (strikeCraftPolys != null) {
                for (int i = 0, n = strikeCraftPolys.size(); i < n; i++) {
                    RenderedDivisionPolygon sp = strikeCraftPolys.get(i);
                    if (sp.voidstrikeNumber == vs.voidstrikeNumber
                            && sp.isEnemy == isEnemy
                            && sp.isAlliedAi == isAlly) {
                        int sqCnt = (sp.squadronUnits != null) ? sp.squadronUnits.size() : 1;
                        if (sqCnt > bestSquadronCount) {
                            bestSquadronCount = sqCnt;
                            bestStrikeEnv = sp;
                        }
                    }
                }
            }

            float sx;
            float sy;
            if (bestStrikeEnv != null && bestStrikeEnv.centerScreen != null) {
                sx = bestStrikeEnv.centerScreen.x;
                sy = isEnemy ? (bestStrikeEnv.maxY + 14f) : (bestStrikeEnv.minY - 8f);
            } else {
                float wx;
                float wy;
                if (isEnemy) {
                    float visSumX = 0f;
                    float visSumY = 0f;
                    int visCount = 0;
                    for (ShipAPI f : vs.activeFighters) {
                        if (f != null && f.isAlive() && !f.isHulk() && isEnemyShipVisibleOnCommandMap(this.engine, f, playerFog)) {
                            visSumX += f.getLocation().x;
                            visSumY += f.getLocation().y;
                            visCount++;
                        }
                    }
                    if (visCount == 0) {
                        continue;
                    }
                    wx = visSumX / visCount;
                    wy = visSumY / visCount;
                } else {
                    wx = vs.center.x;
                    wy = vs.center.y;
                }
                sx = wx * scale + offsetX;
                sy = wy * scale + offsetY;
            }

            for (int pass = 0; pass < 3; pass++) {
                for (int i = 0, n = placedLabels.size(); i < n; i++) {
                    Vector2f other = placedLabels.get(i);
                    if (Math.abs(sx - other.x) < 126f && Math.abs(sy - other.y) < 28f) {
                        float dir = (sy >= other.y) ? 1f : -1f;
                        sy = other.y + dir * 28f;
                    }
                }
            }
            placedLabels.add(new Vector2f(sx, sy));

            String text = vs.getDisplayLabel();
            String vsKeyOverride = "VOIDSTRIKE_" + vs.side + "_" + vs.voidstrikeNumber;
            String vsMapKey = getLabelMapKey(text, vsKeyOverride, isEnemy, isAlly, false);
            LabelAPI label = getOrCreateLabel(
                    text,
                    vsKeyOverride,
                    isEnemy,
                    isAlly,
                    false);
            if (label != null) {
                label.setColor(isEnemy ? ENEMY_STRIKE_LABEL_COLOR
                        : (isAlly ? ALLY_STRIKE_LABEL_COLOR : PLAYER_STRIKE_LABEL_COLOR));
                float textW = getCachedLabelWidth(vsMapKey, label, text);
                float textH = getCachedLabelHeight(vsMapKey, label, text);
                label.getPosition().setSize(textW + 10f, textH + 4f);
                label.getPosition().setLocation(sx - (textW * 0.5f), sy + (textH * 0.5f));
                label.render(fade * (isEnemy ? 0.90f : 0.96f));
            }
        }
    }

    // =========================================================================
    // Section 5: 2D Computational Geometry, Ear-Clipping Triangulation & Sutherland-Hodgman Clipping
    // =========================================================================

    /**
     * Ear-clipping triangulation of a simple 2D polygon (handles narrow-waisted hourglass and curved metaball contours)
     * with multi-stage ear relaxation so collinear Marching Squares edges never abort triangulation early.
     */
    public static List<int[]> triangulatePolygonCCW(List<Vector2f> poly) {
        int n = poly != null ? poly.size() : 0;
        if (n < 3) return Collections.emptyList();
        List<int[]> tris = new ArrayList<>(n - 2);
        int[] indices = new int[n];
        float area = 0f;
        for (int i = 0; i < n; i++) {
            Vector2f p = poly.get(i);
            Vector2f q = poly.get((i + 1) % n);
            area += (p.x * q.y - q.x * p.y);
        }
        if (area >= 0f) {
            for (int i = 0; i < n; i++) indices[i] = i;
        } else {
            for (int i = 0; i < n; i++) indices[i] = (n - 1) - i;
        }

        int nv = n;
        int v = nv - 1;
        int failStreak = 0;
        while (nv > 2) {
            if (v >= nv) v = 0;
            int u = (v == 0) ? (nv - 1) : (v - 1);
            int w = (v + 1 >= nv) ? 0 : (v + 1);

            boolean clipNow = false;
            if (failStreak < nv) {
                clipNow = isEarVertex(poly, u, v, w, nv, indices, 1e-4f, -1e-4f);
            } else if (failStreak < 2 * nv) {
                // Stage 2: relax interior point test so vertices on/near ear diagonals don't block clipping
                clipNow = isEarVertex(poly, u, v, w, nv, indices, 1e-5f, 0.75f);
            } else {
                // Stage 3: pick the most convex remaining vertex so triangulation always completes (nv -> 2)
                int bestV = v;
                float bestCross = -Float.MAX_VALUE;
                for (int cand = 0; cand < nv; cand++) {
                    int cu = (cand == 0) ? (nv - 1) : (cand - 1);
                    int cw = (cand + 1 >= nv) ? 0 : (cand + 1);
                    Vector2f a = poly.get(indices[cu]);
                    Vector2f b = poly.get(indices[cand]);
                    Vector2f c = poly.get(indices[cw]);
                    float cr = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
                    if (cr > bestCross) {
                        bestCross = cr;
                        bestV = cand;
                    }
                }
                v = bestV;
                u = (v == 0) ? (nv - 1) : (v - 1);
                w = (v + 1 >= nv) ? 0 : (v + 1);
                clipNow = true;
            }

            if (clipNow) {
                tris.add(new int[] { indices[u], indices[v], indices[w] });
                for (int s = v, t = v + 1; t < nv; s++, t++) {
                    indices[s] = indices[t];
                }
                nv--;
                failStreak = 0;
            } else {
                v++;
                failStreak++;
            }
        }
        return tris;
    }

    private static boolean isEarVertex(
            List<Vector2f> poly,
            int u,
            int v,
            int w,
            int nv,
            int[] indices,
            float minCross,
            float insideMargin) {
        Vector2f a = poly.get(indices[u]);
        Vector2f b = poly.get(indices[v]);
        Vector2f c = poly.get(indices[w]);
        float crossVal = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
        if (crossVal <= minCross) return false;
        for (int p = 0; p < nv; p++) {
            if (p == u || p == v || p == w) continue;
            Vector2f pt = poly.get(indices[p]);
            if (pointInTriangleCCW(a, b, c, pt, insideMargin)) return false;
        }
        return true;
    }

    private static boolean pointInTriangleCCW(Vector2f a, Vector2f b, Vector2f c, Vector2f p, float margin) {
        if ((p.x == a.x && p.y == a.y) || (p.x == b.x && p.y == b.y) || (p.x == c.x && p.y == c.y)) {
            return false;
        }
        float c1 = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x);
        float c2 = (c.x - b.x) * (p.y - b.y) - (c.y - b.y) * (p.x - b.x);
        float c3 = (a.x - c.x) * (p.y - c.y) - (a.y - c.y) * (p.x - c.x);
        return c1 >= margin && c2 >= margin && c3 >= margin;
    }

    public static float computeSignedPolygonArea(List<Vector2f> poly) {
        if (poly == null || poly.size() < 3) return 0f;
        float sum = 0f;
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            Vector2f a = poly.get(i);
            Vector2f b = poly.get((i + 1) % n);
            sum += (a.x * b.y - b.x * a.y);
        }
        return sum * 0.5f;
    }

    public static boolean isPointInsidePolygon(List<Vector2f> poly, float x, float y) {
        if (poly == null || poly.size() < 3) return false;
        boolean inside = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            Vector2f pi = poly.get(i);
            Vector2f pj = poly.get(j);
            if (((pi.y > y) != (pj.y > y))
                    && (x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y + 1e-7f) + pi.x)) {
                inside = !inside;
            }
        }
        return inside;
    }

    public static int countPolygonSelfIntersections(List<Vector2f> poly) {
        if (poly == null || poly.size() < 4) return 0;
        int n = poly.size();
        int count = 0;
        for (int i = 0; i < n; i++) {
            Vector2f a1 = poly.get(i);
            Vector2f a2 = poly.get((i + 1) % n);
            for (int j = i + 2; j < n; j++) {
                if (i == 0 && j == n - 1) continue;
                Vector2f b1 = poly.get(j);
                Vector2f b2 = poly.get((j + 1) % n);
                if (segmentsProperlyIntersect(a1, a2, b1, b2)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean segmentsProperlyIntersect(Vector2f a, Vector2f b, Vector2f c, Vector2f d) {
        float d1 = cross(c, d, a);
        float d2 = cross(c, d, b);
        float d3 = cross(a, b, c);
        float d4 = cross(a, b, d);
        return ((d1 > 1e-3f && d2 < -1e-3f) || (d1 < -1e-3f && d2 > 1e-3f))
                && ((d3 > 1e-3f && d4 < -1e-3f) || (d3 < -1e-3f && d4 > 1e-3f));
    }


    public static List<Vector2f> computeConvexHullCCW(List<Vector2f> pts) {
        if (pts == null || pts.isEmpty()) {
            return Collections.emptyList();
        }
        if (pts.size() <= 3) {
            List<Vector2f> copy = new ArrayList<>(pts.size());
            for (int i = 0, n = pts.size(); i < n; i++) {
                Vector2f p = pts.get(i);
                copy.add(new Vector2f(p.x, p.y));
            }
            return copy;
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
            lower.add(new Vector2f(p.x, p.y));
        }

        List<Vector2f> upper = new ArrayList<>(sorted.size());
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Vector2f p = sorted.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= 0f) {
                upper.remove(upper.size() - 1);
            }
            upper.add(new Vector2f(p.x, p.y));
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
        return Math.abs(computeSignedPolygonArea(poly));
    }

    /**
     * Sutherland-Hodgman clipping of convex subjectPoly against convex clipPoly (both CCW).
     */
    public static List<Vector2f> clipConvexPolygon(List<Vector2f> subjectPoly, List<Vector2f> clipPoly) {
        if (subjectPoly == null || clipPoly == null) return Collections.emptyList();
        int subjLen = subjectPoly.size();
        int clipLen = clipPoly.size();
        if (subjLen < 3 || clipLen < 3) return Collections.emptyList();

        float sMinX = Float.MAX_VALUE, sMaxX = -Float.MAX_VALUE;
        float sMinY = Float.MAX_VALUE, sMaxY = -Float.MAX_VALUE;
        for (int i = 0; i < subjLen; i++) {
            Vector2f v = subjectPoly.get(i);
            if (v.x < sMinX) sMinX = v.x;
            if (v.x > sMaxX) sMaxX = v.x;
            if (v.y < sMinY) sMinY = v.y;
            if (v.y > sMaxY) sMaxY = v.y;
        }
        float cMinX = Float.MAX_VALUE, cMaxX = -Float.MAX_VALUE;
        float cMinY = Float.MAX_VALUE, cMaxY = -Float.MAX_VALUE;
        for (int i = 0; i < clipLen; i++) {
            Vector2f v = clipPoly.get(i);
            if (v.x < cMinX) cMinX = v.x;
            if (v.x > cMaxX) cMaxX = v.x;
            if (v.y < cMinY) cMinY = v.y;
            if (v.y > cMaxY) cMaxY = v.y;
        }
        if (sMaxX < cMinX || cMaxX < sMinX || sMaxY < cMinY || cMaxY < sMinY) {
            return Collections.emptyList();
        }

        List<Vector2f> bufA = new ArrayList<>(subjLen + 8);
        List<Vector2f> bufB = new ArrayList<>(subjLen + 8);
        bufA.addAll(subjectPoly);
        List<Vector2f> input = bufA;
        List<Vector2f> output = bufB;

        for (int i = 0; i < clipLen; i++) {
            int inLen = input.size();
            if (inLen == 0) return Collections.emptyList();
            Vector2f c1 = clipPoly.get(i);
            Vector2f c2 = clipPoly.get((i + 1) % clipLen);
            float edgeDx = c2.x - c1.x;
            float edgeDy = c2.y - c1.y;

            output.clear();
            Vector2f s = input.get(inLen - 1);
            float sCross = edgeDx * (s.y - c1.y) - edgeDy * (s.x - c1.x);
            boolean sInside = sCross >= -1e-3f;
            for (int j = 0; j < inLen; j++) {
                Vector2f e = input.get(j);
                float eCross = edgeDx * (e.y - c1.y) - edgeDy * (e.x - c1.x);
                boolean eInside = eCross >= -1e-3f;

                if (eInside) {
                    if (!sInside) {
                        output.add(intersectEdge(s, e, sCross, eCross));
                    }
                    output.add(new Vector2f(e.x, e.y));
                } else if (sInside) {
                    output.add(intersectEdge(s, e, sCross, eCross));
                }
                s = e;
                sCross = eCross;
                sInside = eInside;
            }
            List<Vector2f> tmp = input;
            input = output;
            output = tmp;
        }
        return input;
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
                out.add(new Vector2f(e.x, e.y));
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
            case TOT_ALPHA_STRIKE:
                intentShort = "TOT ALPHA";
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
        String spTag = (div.activeSetpieceAttack != null
                && div.activeSetpieceAttack != PermanentDivisionManager.DivisionSetpieceAttack.NONE
                && div.setpieceStage > 0)
                ? " [" + div.activeSetpieceAttack.shortName.toUpperCase(Locale.ROOT) + "]"
                : "";
        return String.format(Locale.ROOT, "%s%s - %d%%", intentShort, spTag, fluxPct);
    }

    private static boolean isEnemyShipVisibleOnCommandMap(ShipAPI ship, FogOfWarAPI playerFog) {
        return isEnemyShipVisibleOnCommandMap(null, ship, playerFog);
    }

    private static boolean isEnemyShipVisibleOnCommandMap(
            CombatEngineAPI engine,
            ShipAPI ship,
            FogOfWarAPI playerFog) {
        if (ship == null || !ship.isAlive()) return false;
        if (playerFog == null) return true;
        CombatEngineAPI eng = engine;
        if (eng == null) {
            try {
                eng = Global.getCombatEngine();
            } catch (Throwable ignored) {
            }
        }
        return BattlefieldFirepowerEvaluator.isEnemyShipVisibleSticky(eng, ship, playerFog);
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
            case FIGHTER:
                return 260f;
            case FRIGATE:
            default:
                return 360f;
        }
    }

    private static String getLabelMapKey(
            String text,
            String cacheKeyOverride,
            boolean isEnemy,
            boolean isAlliedAi,
            boolean isIntentBadge) {
        String prefix = isEnemy ? "E:" : (isAlliedAi ? "A:" : (isIntentBadge ? "PI:" : "P:"));
        return prefix + (cacheKeyOverride != null ? cacheKeyOverride : text);
    }

    private float getCachedLabelWidth(String mapKey, LabelAPI label, String text) {
        Float w = labelWidthCache.get(mapKey);
        if (w == null) {
            w = Float.valueOf(label.computeTextWidth(text));
            labelWidthCache.put(mapKey, w);
        }
        return w.floatValue();
    }

    private float getCachedLabelHeight(String mapKey, LabelAPI label, String text) {
        Float h = labelHeightCache.get(mapKey);
        if (h == null) {
            h = Float.valueOf(label.computeTextHeight(text));
            labelHeightCache.put(mapKey, h);
        }
        return h.floatValue();
    }

    private LabelAPI getOrCreateLabel(
            String text,
            String cacheKeyOverride,
            boolean isEnemy,
            boolean isAlliedAi,
            boolean isIntentBadge) {
        String key = getLabelMapKey(text, cacheKeyOverride, isEnemy, isAlliedAi, isIntentBadge);
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
            labelLastTextCache.put(key, text);
            labelWidthCache.remove(key);
            labelHeightCache.remove(key);
        } else if (cacheKeyOverride != null) {
            String prevText = labelLastTextCache.get(key);
            if (!text.equals(prevText)) {
                label.getPosition().setSize(1000f, 40f);
                label.setText(text);
                labelLastTextCache.put(key, text);
                labelWidthCache.remove(key);
                labelHeightCache.remove(key);
            }
        }
        return label;
    }

    // Lightweight test-only accessors (no reflection; used by UITestHarness to verify cache stability)
    public int getCachedLabelCountForTest() {
        return labelCache.size();
    }

    public void getOrCreateIntentBadgeForTest(String divId, boolean isAlliedAi, String intentTag) {
        getOrCreateLabel(intentTag, "INTENT_SUB:" + divId, false, isAlliedAi, true);
    }

    private static ShipAPI resolveActiveFlagship(DivisionState div, List<ShipAPI> alive) {
        if (div.flagship != null && alive.contains(div.flagship)) {
            return div.flagship;
        }
        ShipAPI succ = DivisionOtcCommandManager.resolveBestDivisionFlagshipSuccessor(div);
        if (succ != null && alive.contains(succ)) {
            return succ;
        }
        div.flagship = alive.get(0);
        return div.flagship;
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

}
