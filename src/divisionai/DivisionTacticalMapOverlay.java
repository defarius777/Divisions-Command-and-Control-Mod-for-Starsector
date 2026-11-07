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
    private static final int HULL_SAMPLE_POINTS_PER_SHIP = 24;
    private static final float STRIPE_WIDTH_PX = 12.0f;
    private static final float STRIPE_PERIOD_PX = STRIPE_WIDTH_PX * 2.0f;
    private static final float INV_SQRT2 = 0.70710678f;

    private static final Color PLAYER_LABEL_COLOR = new Color(165, 255, 185, 235);
    private static final Color PLAYER_INTENT_COLOR = new Color(130, 235, 255, 225);
    private static final Color ALLY_LABEL_COLOR = new Color(255, 235, 110, 240);
    private static final Color ENEMY_LABEL_COLOR = new Color(255, 125, 120, 225);

    public static class RenderedDivisionPolygon {
        final DivisionState div;
        final BattleGroupState battleGroup;
        final boolean isEnemy;
        final boolean isAlliedAi;
        final boolean isPrimaryDroplet;
        final boolean isStrikeCraftPolygon;
        final List<ShipAPI> hullMembers;
        final List<Vector2f> polyScreen;        // CCW smooth convex polygon in screen pixels (for clipping)
        final List<Vector2f> displayPolyScreen; // Star-convex metaball/water-drop contour in screen pixels (for rendering)
        final List<int[]> triangles;            // Precomputed ear-clipping vertex index triples (invariant under pan/zoom)
        final Vector2f centerScreen;            // Droplet entity center in screen pixels
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
                    hullMembers, polyScreen, displayPolyScreen, centerScreen, cr, cg, cb, cohesionAlphaMult);
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
            this.div = div;
            this.battleGroup = battleGroup;
            this.isEnemy = isEnemy;
            this.isAlliedAi = isAlliedAi;
            this.isPrimaryDroplet = isPrimaryDroplet;
            this.isStrikeCraftPolygon = isStrikeCraftPolygon;
            this.hullMembers = hullMembers;
            this.polyScreen = polyScreen;
            this.displayPolyScreen = displayPolyScreen != null ? displayPolyScreen : polyScreen;
            this.triangles = isStrikeCraftPolygon
                    ? Collections.<int[]>emptyList()
                    : triangulatePolygonCCW(this.displayPolyScreen);
            this.centerScreen = centerScreen;
            this.labelX = centerScreen != null ? centerScreen.x : 0f;
            this.labelY = centerScreen != null ? centerScreen.y : 0f;
            this.cr = cr;
            this.cg = cg;
            this.cb = cb;
            this.cohesionAlphaMult = cohesionAlphaMult;
            if (hullMembers != null && !hullMembers.isEmpty()) {
                float wx = 0f, wy = 0f;
                int cnt = 0;
                for (int i = 0, n = hullMembers.size(); i < n; i++) {
                    ShipAPI s = hullMembers.get(i);
                    if (s != null && s.getLocation() != null) {
                        wx += s.getLocation().x;
                        wy += s.getLocation().y;
                        cnt++;
                    }
                }
                if (cnt > 0) {
                    this.lastWorldCenterX = wx / cnt;
                    this.lastWorldCenterY = wy / cnt;
                }
            }
            recomputeBounds();
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

        CachedOverlapStripeBand(List<Vector2f> verts, float r, float g, float b, float alphaScale, boolean isBandA) {
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
        this.cachedPolyNow = -1000f;
        this.cachedPolyPaused = false;
        this.cachedPolySignature = 0;
        this.lastLiveTranslateSimTime = -1000f;
        this.lastStripeRebuildSimTime = -1000f;
        this.perDivisionGeometryCache.clear();
        this.perDivisionStrikeCraftCache.clear();
        this.regularCommandSpritesSuppressed = false;
    }

    private boolean attachedToWarroom = false;
    private boolean loggedHookFailure = false;
    private Object warroomInstance = null;
    private Object mapDisplayInstance = null;
    private MethodHandle isShowingVideoFeedHandle = null;
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
            Class<?> wrClass = Class.forName("com.fs.starfarer.combat.new.OoOO", false, cl);
            Class<?> mdClass = Class.forName("com.fs.starfarer.combat.new.b", false, cl);
            Class<?> iconClass = Class.forName("com.fs.starfarer.combat.new.OOoO", false, cl);

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
            this.mapGetIconHandle = lookup.findVirtual(
                    mdClass,
                    "o00000",
                    MethodType.methodType(iconClass, Object.class));
            this.iconRenderSpriteHandle = lookup.findVirtual(
                    iconClass,
                    "\u00d300000",
                    MethodType.methodType(void.class, boolean.class, float.class, float.class));
            this.iconRenderBarsHandle = lookup.findVirtual(
                    iconClass,
                    "\u00d200000",
                    MethodType.methodType(void.class, boolean.class, float.class, float.class));
            try {
                this.iconGetRadiusHandle = lookup.findVirtual(
                        iconClass,
                        "super",
                        MethodType.methodType(float.class, float.class));
            } catch (Throwable ignored) {
                this.iconGetRadiusHandle = null;
            }
            try {
                Class<?> arrowMgrClass = Class.forName("com.fs.starfarer.combat.new.O0OO", false, cl);
                Class<?> arrowEntryClass = Class.forName("com.fs.starfarer.combat.new.O0OO$o", false, cl);
                Class<?> faderClass = Class.forName("com.fs.graphics.util.Fader", false, cl);
                MethodHandles.Lookup mdLookup = MethodHandles.privateLookupIn(mdClass, lookup);
                MethodHandles.Lookup arrowMgrLookup = MethodHandles.privateLookupIn(arrowMgrClass, lookup);
                MethodHandles.Lookup arrowEntryLookup = MethodHandles.privateLookupIn(arrowEntryClass, lookup);
                MethodHandle getArrowMgrHandle = mdLookup.findGetter(mdClass, "\u00d3o0000", arrowMgrClass);
                Object arrowMgr = getArrowMgrHandle.invoke(md);
                if (arrowMgr != null) {
                    MethodHandle getFadingListHandle = arrowMgrLookup.findGetter(arrowMgrClass, "\u00d600000", List.class);
                    Object listObj = getFadingListHandle.invoke(arrowMgr);
                    if (listObj instanceof List) {
                        this.warroomFadingArrowsList = (List<?>) listObj;
                        this.arrowEntryFaderGetter = arrowEntryLookup.findGetter(arrowEntryClass, "\u00d600000", faderClass);
                        this.faderIsFadedOutHandle = lookup.findVirtual(faderClass, "isFadedOut", MethodType.methodType(boolean.class));
                    }
                }
            } catch (Throwable ignored) {
                this.warroomFadingArrowsList = null;
            }

            try {
                Class<?> shipIconCls = Class.forName("com.fs.starfarer.combat.new.A", false, cl);
                Class<?> fighterIconCls = Class.forName("com.fs.starfarer.combat.new.o0Oo", false, cl);
                Class<?> bNewCls = Class.forName("com.fs.starfarer.ui.B.new", false, cl);
                Class<?> spriteCls = Class.forName("com.fs.graphics.Sprite", false, cl);
                Class<?> texObjCls = Class.forName("com.fs.graphics.Object", false, cl);
                MethodHandles.Lookup mdLookup2 = MethodHandles.privateLookupIn(mdClass, lookup);
                MethodHandles.Lookup bNewLookup = MethodHandles.privateLookupIn(bNewCls, lookup);
                MethodHandles.Lookup spriteLookup = MethodHandles.privateLookupIn(spriteCls, lookup);
                this.warroomShipIconClass = shipIconCls;
                this.warroomFighterIconClass = fighterIconCls;
                this.mdIconsMapGetter = lookup.findVirtual(mdClass, "class", MethodType.methodType(Map.class));
                this.mdIconSetGetter = mdLookup2.findGetter(mdClass, "\u00d2o0000", Set.class);
                this.shipIconBNewGetter = lookup.findVirtual(shipIconCls, "\u00d3O0000", MethodType.methodType(bNewCls));
                this.bNewSpriteGetter = bNewLookup.findGetter(bNewCls, "\u00d4\u00d50000", spriteCls);
                this.bNewHoloSetter = lookup.findVirtual(bNewCls, "\u00d4O0000", MethodType.methodType(void.class, boolean.class));
                this.bNewModulesSetter = lookup.findVirtual(bNewCls, "\u00f400000", MethodType.methodType(void.class, boolean.class));
                this.bNewStatusSetter = lookup.findVirtual(bNewCls, "\u00d500000", MethodType.methodType(void.class, boolean.class));
                this.spriteTexGetter = spriteLookup.findGetter(spriteCls, "texture", texObjCls);
                this.spriteTexSetter = spriteLookup.findSetter(spriteCls, "texture", texObjCls);
                this.spriteReadResolveHandle = spriteLookup.findVirtual(spriteCls, "readResolve", MethodType.methodType(Object.class));
                try {
                    MethodHandles.Lookup aLookup = MethodHandles.privateLookupIn(shipIconCls, lookup);
                    this.shipIconArrowSpriteGetter = aLookup.findGetter(shipIconCls, "interface.new", spriteCls);
                } catch (Throwable ignored) {
                    this.shipIconArrowSpriteGetter = null;
                }
                try {
                    MethodHandles.Lookup fighterLookup = MethodHandles.privateLookupIn(fighterIconCls, lookup);
                    this.fighterIconSpriteGetter = fighterLookup.findGetter(fighterIconCls, "float.super", spriteCls);
                } catch (Throwable ignored) {
                    this.fighterIconSpriteGetter = null;
                }
            } catch (Throwable t) {
                LOG.warn("[DivisionAI] Failed to resolve Warroom command sprite handles", t);
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

    @Override
    public void processInput(List<InputEventAPI> events) {
        if (!attachedToWarroom || !BattlefieldFirepowerEvaluator.isCommandViewWindowActive(engine)) {
            return;
        }
        if (warroomInstance != null && isShowingVideoFeedHandle != null) {
            try {
                if ((boolean) isShowingVideoFeedHandle.invoke(warroomInstance)) {
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        TsfcWarroomRenderer.processInputEvents(engine, events);
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
            cachedPolyPaused = false;
            cachedPolyNow = -1000f;
            return;
        }

        float cmdOpacity = ui.getCommandUIOpacity();
        if (cmdOpacity <= 0.05f || alphaMult <= 0.05f) {
            cachedPolyPaused = false;
            cachedPolyNow = -1000f;
            return;
        }

        // Do not render if the player is viewing a live sprite Video Feed inside the Command UI
        if (warroomInstance != null && isShowingVideoFeedHandle != null) {
            try {
                boolean showingFeed = (boolean) isShowingVideoFeedHandle.invoke(warroomInstance);
                if (showingFeed) {
                    cachedPolyPaused = false;
                    cachedPolyNow = -1000f;
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

        int geomSignature = computeBattleGroupsGeometrySignature(
                playerGroup, alliedAiGroup, enemyGroup, playerFog, paused);
        boolean sameContext = sameEngineAndGroups
                && cachedPolySignature == geomSignature
                && (!cachedRenderedPolys.isEmpty() || !cachedStrikeCraftPolys.isEmpty());
        boolean reuseAllCached = sameContext && paused && Math.abs(now - cachedPolyNow) < 1e-4f;

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
                }
            }

            boolean coldBuild = (!sameEngineAndGroups || cachedPolyNow < -500f || paused);
            renderedPolys.clear();
            strikeCraftPolys.clear();
            // Spread Marching-Squares SDF contour rebuilds to at most 1 division per unpaused frame (~0.35ms max)
            // so a 60 Hz VSync frame never stacks multiple division rebuilds onto a single frame.
            int[] rebuildBudget = new int[] { coldBuild ? 256 : 1 };

            if (playerGroup != null && !playerGroup.divisions.isEmpty()) {
                divisionsRebuiltThisFrame += collectDivisionPolygonsCached(
                        playerGroup, false, null, now, paused, scale, offsetX, offsetY, rebuildBudget, renderedPolys);
                collectStrikeCraftDivisionPolygonsCached(
                        playerGroup, false, null, now, paused || divisionsRebuiltThisFrame > 0, scale, offsetX, offsetY, strikeCraftPolys);
            }
            if (alliedAiGroup != null && !alliedAiGroup.divisions.isEmpty()) {
                divisionsRebuiltThisFrame += collectDivisionPolygonsCached(
                        alliedAiGroup, false, null, now, paused, scale, offsetX, offsetY, rebuildBudget, renderedPolys);
                collectStrikeCraftDivisionPolygonsCached(
                        alliedAiGroup, false, null, now, paused || divisionsRebuiltThisFrame > 0, scale, offsetX, offsetY, strikeCraftPolys);
            }
            if (enemyGroup != null && !enemyGroup.divisions.isEmpty()) {
                divisionsRebuiltThisFrame += collectDivisionPolygonsCached(
                        enemyGroup, true, playerFog, now, paused, scale, offsetX, offsetY, rebuildBudget, renderedPolys);
                collectStrikeCraftDivisionPolygonsCached(
                        enemyGroup, true, playerFog, now, paused || divisionsRebuiltThisFrame > 0, scale, offsetX, offsetY, strikeCraftPolys);
            }

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
            } else if (!paused && cachedPolySignature != geomSignature) {
                cachedFleetFlagships = resolveFleetFlagships(playerFog);
                cachedDivisionFlagships = resolveDivisionFlagships(playerFog, cachedFleetFlagships);
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
        TsfcWarroomRenderer.renderTimeSpaceAndConvergenceScreenSpace(
                engine, scale, offsetX, offsetY, fade, playerGroup, alliedAiGroup, enemyGroup);
        TsfcWarroomRenderer.renderFirepowerRosesScreenSpace(
                engine, scale, offsetX, offsetY, fade, playerGroup, alliedAiGroup, enemyGroup);

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

        // 5b. Update & render "Voidstrike #" labels following the live center of strike craft groups
        // (friendly and visible enemy Voidstrikes; disappears when strike craft return)
        VoidstrikeCoordinator.refreshVoidstrikesForRender(
                engine,
                playerGroup,
                alliedAiGroup,
                enemyGroup,
                engine.getTotalElapsedTime(false));
        renderVoidstrikeLabels(playerFog, scale, offsetX, offsetY, fade);

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
            int divSig = computeSingleDivisionGeometrySignature(div, isEnemy, playerFog, paused);
            CachedDivisionGeometry entry = perDivisionGeometryCache.get(cacheKey);
            float staggerWindow = 0.45f + (Math.abs(cacheKey.hashCode()) % 17) * 0.03f;

            boolean sigMatches = (entry != null && entry.signature == divSig);
            boolean withinTimeWindow = (entry != null && (paused || Math.abs(now - entry.builtAtSimTime) < staggerWindow));
            boolean reuseEntry = (entry != null)
                    && ((sigMatches && withinTimeWindow)
                    || (sigMatches && !paused && rebuildBudget != null && rebuildBudget[0] <= 0 && !entry.droplets.isEmpty()));

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
                    if (!paused && Math.abs(now - entry.lastTranslatedSimTime) > 1e-4f) {
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

            List<Vector2f> displayPolyScreen = applyMetaballNeckPinchScreen(
                    div,
                    null,
                    null,
                    dropletMembers,
                    hullMembers,
                    scale,
                    offsetX,
                    offsetY);
            if (displayPolyScreen == null || displayPolyScreen.size() < 3) {
                displayPolyScreen = buildFluidDropletPolygonScreen(
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
                    dropletMembers,
                    polyScreen,
                    displayPolyScreen,
                    centerScreen,
                    cr,
                    cg,
                    cb,
                    cohesionAlphaMult));
        }
    }

    private void collectStrikeCraftDivisionPolygonsCached(
            BattleGroupState battleGroup,
            boolean isEnemy,
            FogOfWarAPI playerFog,
            float now,
            boolean paused,
            float scale,
            float offsetX,
            float offsetY,
            List<RenderedDivisionPolygon> outStrikeList) {
        if (battleGroup == null || battleGroup.divisions.isEmpty()) return;
        String groupKey = isEnemy ? "SC_E" : (battleGroup.isAlliedAi ? "SC_A" : "SC_P");
        CachedDivisionGeometry entry = perDivisionStrikeCraftCache.get(groupKey);
        int groupSig = computeSingleGroupGeometrySignature(battleGroup, isEnemy, playerFog, paused);
        if (entry != null
                && entry.signature == groupSig
                && (paused || Math.abs(now - entry.builtAtSimTime) < 0.45f)) {
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
                if (!paused && Math.abs(now - entry.lastTranslatedSimTime) > 1e-4f) {
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
        collectStrikeCraftDivisionPolygons(battleGroup, isEnemy, playerFog, scale, offsetX, offsetY, entry.droplets);
        entry.signature = groupSig;
        entry.builtAtSimTime = now;
        entry.lastTranslatedSimTime = now;
        entry.scale = scale;
        entry.offsetX = offsetX;
        entry.offsetY = offsetY;
        if (!entry.droplets.isEmpty()) {
            outStrikeList.addAll(entry.droplets);
        }
    }

    /**
     * Feature A: Collects Strike Craft division polygons on their own dedicated layer so launched fighter/bomber
     * squadrons never conflict with warship division polygons or ship overlap stripes.
     */
    public void collectStrikeCraftDivisionPolygons(
            BattleGroupState battleGroup,
            boolean isEnemy,
            FogOfWarAPI playerFog,
            float scale,
            float offsetX,
            float offsetY,
            List<RenderedDivisionPolygon> outStrikeList) {
        if (battleGroup == null || battleGroup.divisions.isEmpty()) return;

        for (DivisionState div : battleGroup.divisions) {
            List<ShipAPI> alive = div.getAliveMembers();
            if (alive.isEmpty()) continue;

            List<ShipAPI> strikeMembers = new ArrayList<>();
            for (ShipAPI carrier : alive) {
                if (carrier == null || !carrier.isAlive() || carrier.isHulk()) continue;
                if (carrier.isFighter()) {
                    if (!isEnemy || isEnemyShipVisibleOnCommandMap(carrier, playerFog)) {
                        strikeMembers.add(carrier);
                    }
                    continue;
                }
                List<com.fs.starfarer.api.combat.FighterWingAPI> wings = carrier.getAllWings();
                if (wings == null || wings.isEmpty()) continue;
                for (com.fs.starfarer.api.combat.FighterWingAPI wing : wings) {
                    if (wing == null) continue;
                    ShipAPI wingRep = wing.getLeader();
                    if ((wingRep == null || !wingRep.isAlive() || wingRep.isHulk())
                            && wing.getWingMembers() != null && !wing.getWingMembers().isEmpty()) {
                        for (ShipAPI fm : wing.getWingMembers()) {
                            if (fm != null && fm.isAlive() && !fm.isHulk()) {
                                wingRep = fm;
                                break;
                            }
                        }
                    }
                    if (wingRep != null && wingRep.isAlive() && !wingRep.isHulk() && !wingRep.isExpired()) {
                        // Only draw airborne strike craft polygons once the squadron has cleared its carrier deck
                        if (DivisionUiWidgets.distance(wingRep.getLocation(), carrier.getLocation()) <= 120f) continue;
                        if (!isEnemy || isEnemyShipVisibleOnCommandMap(wingRep, playerFog)) {
                            if (!strikeMembers.contains(wingRep)) {
                                strikeMembers.add(wingRep);
                            }
                        }
                    }
                }
            }
            if (strikeMembers.isEmpty()) continue;

            boolean isAlliedAi = !isEnemy && (battleGroup.isAlliedAi || strikeMembers.get(0).isAlly());
            float cr, cg, cb;
            if (isEnemy) {
                cr = 0.96f;
                cg = 0.28f;
                cb = 0.32f;
            } else if (isAlliedAi) {
                cr = 1.00f;
                cg = 0.86f;
                cb = 0.24f;
            } else {
                cr = 0.26f;
                cg = 0.94f;
                cb = 0.56f;
            }

            List<List<ShipAPI>> strikeDroplets = clusterStrikeCraftMembers(strikeMembers);
            for (int dIdx = 0; dIdx < strikeDroplets.size(); dIdx++) {
                List<ShipAPI> dropletMembers = strikeDroplets.get(dIdx);
                List<Vector2f> polyScreen = buildFluidDropletPolygonScreen(
                        div,
                        dropletMembers,
                        strikeMembers,
                        scale,
                        offsetX,
                        offsetY);
                if (polyScreen.size() < 3) continue;
                Vector2f centerScreen = computePolygonCentroid(polyScreen);
                outStrikeList.add(new RenderedDivisionPolygon(
                        div,
                        battleGroup,
                        isEnemy,
                        isAlliedAi,
                        dIdx == 0,
                        true,
                        dropletMembers,
                        polyScreen,
                        polyScreen,
                        centerScreen,
                        cr,
                        cg,
                        cb,
                        0.85f));
            }
        }
    }

    private static List<List<ShipAPI>> clusterStrikeCraftMembers(List<ShipAPI> strikeMembers) {
        int n = strikeMembers.size();
        if (n <= 1) {
            List<List<ShipAPI>> single = new ArrayList<>(1);
            if (n == 1) single.add(new ArrayList<>(strikeMembers));
            return single;
        }
        float mergeDist = 680f;
        float mergeDistSq = mergeDist * mergeDist;
        boolean[] visited = new boolean[n];
        List<List<ShipAPI>> clusters = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (visited[i]) continue;
            List<ShipAPI> cluster = new ArrayList<>();
            cluster.add(strikeMembers.get(i));
            visited[i] = true;
            int head = 0;
            while (head < cluster.size()) {
                ShipAPI curr = cluster.get(head++);
                float cx = curr.getLocation().x;
                float cy = curr.getLocation().y;
                for (int j = 0; j < n; j++) {
                    if (visited[j]) continue;
                    ShipAPI cand = strikeMembers.get(j);
                    float dx = cx - cand.getLocation().x;
                    float dy = cy - cand.getLocation().y;
                    if (dx * dx + dy * dy <= mergeDistSq) {
                        visited[j] = true;
                        cluster.add(cand);
                    }
                }
            }
            clusters.add(cluster);
        }
        return clusters;
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

    private static void translateCachedPolygonsByLiveShipMotion(
            List<RenderedDivisionPolygon> polys,
            float scale) {
        for (int pIdx = 0, pLen = polys.size(); pIdx < pLen; pIdx++) {
            RenderedDivisionPolygon rp = polys.get(pIdx);
            List<ShipAPI> members = rp.hullMembers;
            if (members == null || members.isEmpty()) continue;
            float wx = 0f, wy = 0f;
            int cnt = 0;
            for (int i = 0, n = members.size(); i < n; i++) {
                ShipAPI s = members.get(i);
                if (s != null && s.isAlive() && s.getLocation() != null) {
                    wx += s.getLocation().x;
                    wy += s.getLocation().y;
                    cnt++;
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
            float rPx = clamp(worldR * scale, 14f, 54f);

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
                    float otherRPx = clamp(otherWorldR * scale, 14f, 54f);
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
        return simplifyContourForTriangulation(smoothed, 0.55f, 80);
    }

    /**
     * Removes near-collinear contour vertices (perpendicular deviation < tolerancePx) and evenly decimates
     * oversized contours down to maxVerts. This preserves the smooth metaball water-droplet silhouette while
     * bounding ear-clipping triangulation to O(80^2) instead of O(240^2) on stretched late-combat divisions.
     */
    public static List<Vector2f> simplifyContourForTriangulation(List<Vector2f> poly, float tolerancePx, int maxVerts) {
        if (poly == null || poly.size() <= 6) return poly;
        int n = poly.size();
        List<Vector2f> simplified = new ArrayList<>(Math.min(n, maxVerts));
        for (int i = 0; i < n; i++) {
            Vector2f prev = poly.get((i + n - 1) % n);
            Vector2f curr = poly.get(i);
            Vector2f next = poly.get((i + 1) % n);
            float dx = next.x - prev.x;
            float dy = next.y - prev.y;
            float lenSq = dx * dx + dy * dy;
            float devSq;
            if (lenSq < 1e-6f) {
                devSq = 0f;
            } else {
                float t = clamp(((curr.x - prev.x) * dx + (curr.y - prev.y) * dy) / lenSq, 0f, 1f);
                float qx = prev.x + dx * t;
                float qy = prev.y + dy * t;
                devSq = (curr.x - qx) * (curr.x - qx) + (curr.y - qy) * (curr.y - qy);
            }
            if (devSq >= tolerancePx * tolerancePx) {
                simplified.add(curr);
            }
        }
        if (simplified.size() < 3) simplified = new ArrayList<>(poly);
        if (simplified.size() > maxVerts) {
            List<Vector2f> reduced = new ArrayList<>(maxVerts);
            int m = simplified.size();
            for (int k = 0; k < maxVerts; k++) {
                reduced.add(simplified.get((int) ((long) k * m / maxVerts)));
            }
            simplified = reduced;
        }
        return simplified;
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
            sr[i] = clamp(worldR * scale, 12f, 68f);
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
        // Keep Marching Squares grid resolution bounded (~52 cells) even when ships and carrier fighter wings
        // stretch across the map. Capping stepPx at 5.5f caused 104x104 = 10,920 SDF evaluations per division!
        float stepPx = Math.max(3.0f, maxSpan / 52f);

        float minWaistPx = Math.max(3.2f, stepPx * (maxSpan > 160f ? 1.15f : 0.88f));
        List<List<Vector2f>> loops = traceMetaballContoursMarchingSquares(
                sx, sy, sr, mCount, total, kPair, minX, maxX, minY, maxY, stepPx, minWaistPx);
        if (loops.isEmpty()) {
            return Collections.emptyList();
        }

        List<Vector2f> bestLoop = selectBestContainingLoop(loops, sx, sy, mCount);
        if (bestLoop == null || (mCount > 1 && countContainedShips(bestLoop, sx, sy, mCount) < mCount)) {
            // Guarantee that a thin bridge never pinches between Marching Squares grid vertices and drops a ship
            loops = traceMetaballContoursMarchingSquares(
                    sx, sy, sr, mCount, total, kPair, minX, maxX, minY, maxY, stepPx, stepPx * 1.35f);
            List<Vector2f> retryLoop = selectBestContainingLoop(loops, sx, sy, mCount);
            if (retryLoop != null) {
                bestLoop = retryLoop;
            }
        }
        List<Vector2f> chosen = (bestLoop != null) ? bestLoop : loops.get(0);
        return simplifyContourForTriangulation(chosen, 0.55f, 80);
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
                    float sa = Math.min(ra * 0.45f, sKiss * 0.55f);
                    float sb = len - Math.min(rb * 0.45f, (len - sKiss) * 0.55f);
                    if (sb <= sa + 1.0f) continue;

                    float maxShoulderR = Math.max(ra, rb) * 1.15f + 12f;
                    brAx[bIdx] = ax;
                    brAy[bIdx] = ay;
                    brUx[bIdx] = abx / len;
                    brUy[bIdx] = aby / len;
                    brSa[bIdx] = sa;
                    brSb[bIdx] = sb;
                    brSKiss[bIdx] = sKiss;
                    brYa[bIdx] = (float) Math.sqrt(Math.max(1f, ra * ra - sa * sa)) * 0.94f;
                    float lb = len - sb;
                    brYb[bIdx] = (float) Math.sqrt(Math.max(1f, rb * rb - lb * lb)) * 0.94f;
                    float minR = Math.min(ra, rb);
                    float maxWaist = Math.max(minWaistPx + 1.0f, minR * 0.84f);
                    brRWaist[bIdx] = minWaistPx + openness * (maxWaist - minWaistPx);
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
                    float maxExtPx = Math.min(ra * 0.48f, Math.max(2.0f, distToKissPx - ra * 0.94f));
                    float s0 = ra * 0.52f;
                    float s1 = ra + reach * maxExtPx;
                    if (s1 <= s0 + 1.0f) continue;
                    float yShoulder = (float) Math.sqrt(Math.max(1f, ra * ra - s0 * s0)) * (0.74f + 0.20f * reach);
                    float rTip = 2.4f + (1.0f - reach) * Math.min(4.8f, ra * 0.14f);
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
        return sminPoly(bestCircle, bestBridge, 2.5f);
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

        return sminPoly(bestCircle, bestBridge, 2.5f);
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
        // Exact triangle-inequality interior bound: if bestCircle <= -stepPx, neither this grid point nor any
        // of its 4 cardinal neighbors can have SDF > 0, so it never touches a Marching Squares contour edge!
        float deepInteriorCutoffPx = stepPx + 0.5f;
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

    private void renderAllDivisionPolygonsBatched(
            List<RenderedDivisionPolygon> renderedPolys,
            float basePolyAlpha) {
        if (renderedPolys == null || renderedPolys.isEmpty()) return;
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int pIdx = 0, pLen = renderedPolys.size(); pIdx < pLen; pIdx++) {
            RenderedDivisionPolygon rp = renderedPolys.get(pIdx);
            List<Vector2f> poly = rp.displayPolyScreen;
            int n = (poly != null) ? poly.size() : 0;
            if (n < 3) continue;
            float effectiveAlpha = basePolyAlpha * rp.cohesionAlphaMult;
            float centerAlpha = Math.min(0.18f, effectiveAlpha * 0.72f);
            float edgeAlpha = Math.min(0.13f, effectiveAlpha * 0.50f);
            float triAlpha = (centerAlpha + edgeAlpha) * 0.5f;
            GL11.glColor4f(rp.cr, rp.cg, rp.cb, triAlpha);

            List<int[]> triangles = rp.triangles;
            if (triangles != null && !triangles.isEmpty()) {
                for (int t = 0, tLen = triangles.size(); t < tLen; t++) {
                    int[] tri = triangles.get(t);
                    Vector2f v0 = poly.get(tri[0]);
                    Vector2f v1 = poly.get(tri[1]);
                    Vector2f v2 = poly.get(tri[2]);
                    GL11.glVertex2f(v0.x, v0.y);
                    GL11.glVertex2f(v1.x, v1.y);
                    GL11.glVertex2f(v2.x, v2.y);
                }
            } else {
                Vector2f v0 = poly.get(0);
                for (int i = 1; i < n - 1; i++) {
                    Vector2f v1 = poly.get(i);
                    Vector2f v2 = poly.get(i + 1);
                    GL11.glVertex2f(v0.x, v0.y);
                    GL11.glVertex2f(v1.x, v1.y);
                    GL11.glVertex2f(v2.x, v2.y);
                }
            }
        }
        GL11.glEnd();
    }

    private void renderSingleDivisionPolygon(
            RenderedDivisionPolygon rp,
            float basePolyAlpha) {

        List<Vector2f> poly = rp.displayPolyScreen;
        if (poly.size() < 3) return;

        // Mild shading so ship sprites and map grid remain clear underneath and on top
        float effectiveAlpha = basePolyAlpha * rp.cohesionAlphaMult;
        float centerAlpha = Math.min(0.18f, effectiveAlpha * 0.72f);
        float edgeAlpha = Math.min(0.13f, effectiveAlpha * 0.50f);

        // Use precomputed ear-clipping triangulation (computed once when the polygon contour is built)
        List<int[]> triangles = rp.triangles;
        if (triangles != null && !triangles.isEmpty()) {
            GL11.glBegin(GL11.GL_TRIANGLES);
            GL11.glColor4f(rp.cr, rp.cg, rp.cb, (centerAlpha + edgeAlpha) * 0.5f);
            for (int[] tri : triangles) {
                Vector2f v0 = poly.get(tri[0]);
                Vector2f v1 = poly.get(tri[1]);
                Vector2f v2 = poly.get(tri[2]);
                GL11.glVertex2f(v0.x, v0.y);
                GL11.glVertex2f(v1.x, v1.y);
                GL11.glVertex2f(v2.x, v2.y);
            }
            GL11.glEnd();
        } else {
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
        }
    }

    /**
     * Feature A: Renders Strike Craft division polygons on their own dedicated layer above warship division
     * polygons, with dashed borders mimicking the Point Defense (PD) projection in the Firepower overlay.
     */
    private void renderStrikeCraftDivisionPolygons(
            List<RenderedDivisionPolygon> strikePolys,
            float basePolyAlpha,
            float fade) {
        for (RenderedDivisionPolygon rp : strikePolys) {
            List<Vector2f> poly = rp.displayPolyScreen;
            if (poly == null || poly.size() < 3) continue;

            float effectiveAlpha = basePolyAlpha * rp.cohesionAlphaMult;
            float centerAlpha = Math.min(0.11f, effectiveAlpha * 0.46f);
            float edgeAlpha = Math.min(0.07f, effectiveAlpha * 0.28f);

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

            renderStrikeCraftDashedBorder(rp, poly, fade);
        }
    }

    /**
     * Draws a crisp dashed border around a Strike Craft division polygon, mimicking the Point Defense (PD)
     * dashed inner ring projection in the Firepower overlay (GL_LINES alternating even/odd contour segments).
     */
    public static void renderStrikeCraftDashedBorder(
            RenderedDivisionPolygon rp,
            List<Vector2f> poly,
            float fade) {
        int n = (poly != null) ? poly.size() : 0;
        if (n < 3) return;
        // Blend side color slightly toward the Firepower PD violet-gold highlight (0.92, 0.78, 1.00)
        float br = Math.min(1f, rp.cr * 0.72f + 0.92f * 0.28f);
        float bg = Math.min(1f, rp.cg * 0.72f + 0.78f * 0.28f);
        float bb = Math.min(1f, rp.cb * 0.72f + 1.00f * 0.28f);
        float borderAlpha = Math.min(0.68f, Math.max(0.36f, 0.48f * fade));

        GL11.glLineWidth(1.35f);
        GL11.glColor4f(br, bg, bb, borderAlpha);
        GL11.glBegin(GL11.GL_LINES);
        for (int i = 0; i < n; i++) {
            Vector2f p0 = poly.get(i);
            Vector2f p1 = poly.get((i + 1) % n);
            float dx = p1.x - p0.x;
            float dy = p1.y - p0.y;
            float segLen = (float) Math.sqrt(dx * dx + dy * dy);
            if (segLen <= 12f) {
                if ((i & 1) == 0) {
                    GL11.glVertex2f(p0.x, p0.y);
                    GL11.glVertex2f(p1.x, p1.y);
                }
            } else {
                // Subdivide longer convex edges into uniform PD-style dashes (~7px dash, ~5px gap)
                float pitch = 12.0f;
                int dashes = Math.max(1, Math.min(24, (int) (segLen / pitch)));
                float step = segLen / dashes;
                float ux = dx / segLen;
                float uy = dy / segLen;
                for (int d = 0; d < dashes; d++) {
                    float s0 = d * step;
                    float s1 = s0 + step * 0.58f;
                    GL11.glVertex2f(p0.x + ux * s0, p0.y + uy * s0);
                    GL11.glVertex2f(p0.x + ux * s1, p0.y + uy * s1);
                }
            }
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

                List<Vector2f> clipHullA = a.clipHullScreen;
                List<Vector2f> clipHullB = b.clipHullScreen;
                if (clipHullA == null || clipHullB == null) {
                    clipHullA = computeConvexHullCCW(a.polyScreen);
                    clipHullB = computeConvexHullCCW(b.polyScreen);
                    a.clipHullScreen = clipHullA;
                    b.clipHullScreen = clipHullB;
                }
                List<Vector2f> intersection = clipConvexPolygon(clipHullA, clipHullB);
                if (intersection.size() < 3 || computePolygonArea(intersection) < 380f) {
                    continue;
                }

                float minU = Float.MAX_VALUE;
                float maxU = -Float.MAX_VALUE;
                for (Vector2f v : intersection) {
                    float u = (v.x + v.y) * INV_SQRT2;
                    if (u < minU) minU = u;
                    if (u > maxU) maxU = u;
                }

                float pairCohesion = Math.max(0.12f, Math.min(a.cohesionAlphaMult, b.cohesionAlphaMult));
                boolean sameColorFamily = (a.isEnemy == b.isEnemy) && (a.isAlliedAi == b.isAlliedAi);
                float aCr = Math.min(1f, a.cr + 0.12f);
                float aCg = Math.min(1f, a.cg + 0.12f);
                float aCb = Math.min(1f, a.cb + 0.12f);
                float bCr = sameColorFamily ? (a.cr * 0.25f) : b.cr;
                float bCg = sameColorFamily ? Math.min(1f, a.cg * 0.45f + 0.15f) : b.cg;
                float bCb = sameColorFamily ? Math.min(1f, a.cb + 0.35f) : b.cb;

                int firstBand = (int) Math.floor(minU / STRIPE_PERIOD_PX);
                int lastBand = (int) Math.ceil(maxU / STRIPE_PERIOD_PX);

                for (int k = firstBand; k <= lastBand; k++) {
                    float s0 = k * STRIPE_PERIOD_PX;
                    float sMid = s0 + STRIPE_WIDTH_PX;
                    float s1 = s0 + STRIPE_PERIOD_PX;

                    List<Vector2f> bandA = clipPolygonToDiagonalSlab(intersection, s0, sMid);
                    if (bandA.size() >= 3) {
                        outBands.add(new CachedOverlapStripeBand(bandA, aCr, aCg, aCb, 1.15f * pairCohesion, true));
                    }
                    List<Vector2f> bandB = clipPolygonToDiagonalSlab(intersection, sMid, s1);
                    if (bandB.size() >= 3) {
                        outBands.add(new CachedOverlapStripeBand(bandB, bCr, bCg, bCb, 0.95f * pairCohesion, false));
                    }
                }
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
                    ? Math.min(0.28f, Math.max(0.08f, basePolyAlpha * band.alphaScale))
                    : Math.min(0.24f, Math.max(0.06f, basePolyAlpha * band.alphaScale));
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
                rp.labelY = rp.centerScreen.y;
                primaries.add(rp);
            }
        }
        int m = primaries.size();
        if (m < 2) return;
        float minDx = 128f;
        float minDy = 36f;
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < m; i++) {
                RenderedDivisionPolygon a = primaries.get(i);
                for (int j = i + 1; j < m; j++) {
                    RenderedDivisionPolygon b = primaries.get(j);
                    float dx = b.labelX - a.labelX;
                    float dy = b.labelY - a.labelY;
                    if (Math.abs(dx) < minDx && Math.abs(dy) < minDy) {
                        float overlapY = (minDy - Math.abs(dy)) * 0.52f;
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
            float fade) {
        List<VoidstrikeCoordinator.VoidstrikeRecord> voidstrikes =
                VoidstrikeCoordinator.getActiveVoidstrikes();
        if (voidstrikes == null || voidstrikes.isEmpty()) return;

        for (VoidstrikeCoordinator.VoidstrikeRecord vs : voidstrikes) {
            if (vs == null || vs.activeFighters.isEmpty() || vs.voidstrikeNumber <= 0) continue;

            boolean isEnemy = (vs.side == VoidstrikeCoordinator.VOIDSTRIKE_SIDE_ENEMY);
            boolean isAlly = (vs.side == VoidstrikeCoordinator.VOIDSTRIKE_SIDE_ALLIED);
            float wx;
            float wy;

            if (isEnemy) {
                float visSumX = 0f;
                float visSumY = 0f;
                int visCount = 0;
                for (ShipAPI f : vs.activeFighters) {
                    if (f != null && f.isAlive() && !f.isHulk() && isEnemyShipVisibleOnCommandMap(f, playerFog)) {
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

            float sx = wx * scale + offsetX;
            float sy = wy * scale + offsetY;
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
