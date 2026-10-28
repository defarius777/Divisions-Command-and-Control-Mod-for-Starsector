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

    private static class RenderedDivisionPolygon {
        final DivisionState div;
        final BattleGroupState battleGroup;
        final boolean isEnemy;
        final boolean isAlliedAi;
        final boolean isPrimaryDroplet;
        final List<ShipAPI> hullMembers;
        final List<Vector2f> polyScreen;        // CCW smooth convex polygon in screen pixels (for clipping)
        final List<Vector2f> displayPolyScreen; // Star-convex metaball/water-drop contour in screen pixels (for rendering)
        final Vector2f centerScreen;            // Droplet entity center in screen pixels
        float labelX;                           // De-conflicted label X in screen pixels
        float labelY;                           // De-conflicted label Y in screen pixels
        final float minX;
        final float maxX;
        final float minY;
        final float maxY;
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
            this.div = div;
            this.battleGroup = battleGroup;
            this.isEnemy = isEnemy;
            this.isAlliedAi = isAlliedAi;
            this.isPrimaryDroplet = isPrimaryDroplet;
            this.hullMembers = hullMembers;
            this.polyScreen = polyScreen;
            this.displayPolyScreen = displayPolyScreen != null ? displayPolyScreen : polyScreen;
            this.centerScreen = centerScreen;
            this.labelX = centerScreen != null ? centerScreen.x : 0f;
            this.labelY = centerScreen != null ? centerScreen.y : 0f;
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
    private final Map<String, Float> smoothedCohesionByDivId = new HashMap<>();
    private final Map<String, Set<String>> stickyConnectedPairsByDivId = new HashMap<>();
    private final Map<String, Float> smoothedBondOpennessByPairKey = new HashMap<>();
    private final Map<String, Float> smoothedMeniscusReachByPairKey = new HashMap<>();

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
        BattlefieldFirepowerEvaluator.processInputEvents(engine, events);
    }

    @Override
    public void render(float alphaMult) {
        if (!attachedToWarroom || engine == null) return;
        if (!BattlefieldFirepowerEvaluator.isCommandViewWindowActive(engine)) return;
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

        // 1. Render unbroken fluid division polygons with mild shading & variable cohesion translucency
        for (RenderedDivisionPolygon rp : renderedPolys) {
            renderSingleDivisionPolygon(rp, basePolyAlpha);
        }

        // 2. Render thickly striped overlap zones wherever two division polygons intersect
        if (renderedPolys.size() >= 2) {
            renderOverlappingPolygonStripes(renderedPolys, basePolyAlpha);
        }

        // 3. Render division waypoints & tactical intent vectors for friendly divisions (once per division, from primary droplet)
        for (RenderedDivisionPolygon rp : renderedPolys) {
            if (!rp.isEnemy && rp.isPrimaryDroplet) {
                renderDivisionWaypointsAndIntent(rp, scale, offsetX, offsetY, friendlyLineAlpha, playerFog);
            }
        }

        GL11.glPopMatrix();
        GL11.glPopAttrib();

        // 4. Re-render Warroom Ship Sprites & Status Bars on the TOP LAYER above division polygons
        renderShipSpritesOnTopLayer(renderedPolys, scale, offsetX, offsetY, fade);

        // 4b. Render Fleet Flagship (gold star, upper-right) and Division Leader (silver star, bottom-right) badges
        renderFlagshipStarsOnTopLayer(playerFog, scale, offsetX, offsetY, fade);

        // 4c. Render Time, Space, Firepower, and Convergence map layers (when toggled ON)
        BattlefieldFirepowerEvaluator.renderTimeSpaceAndConvergenceScreenSpace(
                engine, scale, offsetX, offsetY, fade, playerGroup, alliedAiGroup, enemyGroup);
        BattlefieldFirepowerEvaluator.renderFirepowerRosesScreenSpace(
                engine, scale, offsetX, offsetY, fade, playerGroup, alliedAiGroup, enemyGroup);

        // 5. De-conflict and render division labels anchored to the primary division droplet entity
        deconflictPrimaryDivisionLabels(renderedPolys);
        for (RenderedDivisionPolygon rp : renderedPolys) {
            if (rp.isPrimaryDroplet) {
                renderDivisionEntityLabel(rp, fade);
            }
        }

        // 5b. Update & render "Voidstrike #" labels following the live center of strike craft groups
        // (friendly and visible enemy Voidstrikes; disappears when strike craft return)
        BattlefieldFirepowerEvaluator.updateVoidstrikes(
                engine,
                playerGroup,
                alliedAiGroup,
                enemyGroup,
                engine.getTotalElapsedTime(false));
        renderVoidstrikeLabels(playerFog, scale, offsetX, offsetY, fade);

        // 6. Render right-side Warroom Buttons & Urgent Alpha Core Comms Modal on the ABSOLUTE TOP LAYER
        // above all division polygons, ship sprites, overlays, and division text labels
        BattlefieldFirepowerEvaluator.renderToggleButtonAndLegendUI(engine, fade);
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

            ShipAPI activeFlag = resolveActiveFlagship(div, alive);
            List<ShipAPI> hullMembers = new ArrayList<>(alive.size() + 4);
            if (!isEnemy) {
                hullMembers.addAll(alive);
            } else {
                if (activeFlag == null || !isEnemyShipVisibleOnCommandMap(activeFlag, playerFog)) {
                    continue;
                }
                for (ShipAPI s : alive) {
                    if (isEnemyShipVisibleOnCommandMap(s, playerFog)) {
                        hullMembers.add(s);
                    }
                }
                if (hullMembers.isEmpty()) continue;
            }

            // Include each carrier's active Strike Craft squadron (represented by its Wing Leader) in the
            // division's water-droplet footprint so launched wings stretch and bud off from their carrier!
            int hullCount = hullMembers.size();
            for (int i = 0; i < hullCount; i++) {
                ShipAPI carrier = hullMembers.get(i);
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
                        if (!isEnemy || isEnemyShipVisibleOnCommandMap(wingRep, playerFog)) {
                            if (!hullMembers.contains(wingRep)) {
                                hullMembers.add(wingRep);
                            }
                        }
                    }
                }
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
                // Keep division droplets clearly visible even when reinforcements arrive from the map edge
                // or while a division is closing up (each droplet is already a tight sub-cluster).
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

                List<Vector2f> polyScreen = buildFluidDropletPolygonScreen(
                        div,
                        dropletMembers,
                        hullMembers,
                        scale,
                        offsetX,
                        offsetY);
                if (polyScreen.size() < 3) continue;

                Vector2f centerScreen = computePolygonCentroid(polyScreen);
                List<Vector2f> displayPolyScreen = applyMetaballNeckPinchScreen(
                        div,
                        polyScreen,
                        centerScreen,
                        dropletMembers,
                        hullMembers,
                        scale,
                        offsetX,
                        offsetY);
                if (displayPolyScreen.size() >= 3) {
                    centerScreen = computePolygonCentroid(displayPolyScreen);
                }

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
                float dist = distance(a.getLocation(), b.getLocation());
                float mergeDist = (ra + rb) * 1.18f;
                float splitDist = mergeDist * 1.24f;
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
            float dik = distance(pi, pk);
            float djk = distance(pj, pk);
            if (dik < distIJ && djk < distIJ && (dik * dik + djk * djk) < distSqIJ * 1.12f) {
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
        float dist = distance(a.getLocation(), b.getLocation());
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
        float dist = distance(a.getLocation(), b.getLocation());
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
        return smoothed;
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
                    float distW = distance(m.getLocation(), other.getLocation());
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
                float distW = distance(shipI.getLocation(), shipJ.getLocation());
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
        float stepPx = clamp(maxSpan / 52f, 2.8f, 5.5f);

        float minWaistPx = Math.max(3.0f, stepPx * 0.84f);
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
        return bestLoop != null ? bestLoop : loops.get(0);
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

    private static float evalMetaballSdf(
            float x,
            float y,
            float[] sx,
            float[] sy,
            float[] sr,
            int mCount,
            int total,
            float[][] kPair,
            float minWaistPx) {
        float bestCircle = Float.MAX_VALUE;
        for (int i = 0; i < mCount; i++) {
            float dx = x - sx[i];
            float dy = y - sy[i];
            float di = (float) Math.sqrt(dx * dx + dy * dy) - sr[i];
            if (di < bestCircle) {
                bestCircle = di;
            }
        }

        float bestBridge = Float.MAX_VALUE;
        for (int i = 0; i < mCount; i++) {
            float ax = sx[i];
            float ay = sy[i];
            float ra = sr[i];
            // Connected intra-droplet shoulder-anchored concave hourglass bridges
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
                float ux = abx / len;
                float uy = aby / len;

                float sKiss = len * (ra / Math.max(1f, ra + rb));
                // Anchor the bridge on the wide shoulders of Circle A and Circle B (so the concave taper
                // happens OUTSIDE the circles rather than being buried inside [0, ra]!)
                float sa = Math.min(ra * 0.45f, sKiss * 0.55f);
                float sb = len - Math.min(rb * 0.45f, (len - sKiss) * 0.55f);
                if (sb <= sa + 1.0f) continue;

                float ya = (float) Math.sqrt(Math.max(1f, ra * ra - sa * sa)) * 0.94f;
                float lb = len - sb;
                float yb = (float) Math.sqrt(Math.max(1f, rb * rb - lb * lb)) * 0.94f;

                float proj = (x - ax) * ux + (y - ay) * uy;
                float sClamped = clamp(proj, sa, sb);
                float qx = ax + ux * sClamped;
                float qy = ay + uy * sClamped;
                float distToAxis = (float) Math.sqrt((x - qx) * (x - qx) + (y - qy) * (y - qy));

                float minR = Math.min(ra, rb);
                // At openness=0.02f (just joined or right before split), waist radius is minWaistPx (Nyquist-safe
                // tiniest point of contact so Marching Squares never drops half a droplet!)
                // At openness=1.0f (snug in formation), waist radius is 0.84 * minR
                float maxWaist = Math.max(minWaistPx + 1.0f, minR * 0.84f);
                float rWaist = minWaistPx + openness * (maxWaist - minWaistPx);
                float rBridge;
                if (sClamped <= sKiss) {
                    float u = clamp((sClamped - sa) / Math.max(1f, sKiss - sa), 0f, 1f);
                    float oneMinusU = 1.0f - u;
                    rBridge = rWaist + (ya - rWaist) * oneMinusU * oneMinusU;
                } else {
                    float v = clamp((sb - sClamped) / Math.max(1f, sb - sKiss), 0f, 1f);
                    float oneMinusV = 1.0f - v;
                    rBridge = rWaist + (yb - rWaist) * oneMinusV * oneMinusV;
                }
                float dBridge = distToAxis - rBridge;
                bestBridge = Math.min(bestBridge, dBridge);
            }
            // Approaching or post-split recoiling inter-droplet liquid meniscus tail toward P_kiss
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
                // Cap maximum tail extension outside Circle A's front edge to 0.48 * ra so it always forms
                // a proportional liquid teardrop meniscus and never shoots a long spear across the map.
                float maxExtPx = Math.min(ra * 0.48f, Math.max(2.0f, distToKissPx - ra * 0.94f));
                float s0 = ra * 0.52f;
                float s1 = ra + reach * maxExtPx;
                if (s1 <= s0 + 1.0f) continue;

                float yShoulder = (float) Math.sqrt(Math.max(1f, ra * ra - s0 * s0)) * (0.74f + 0.20f * reach);
                float proj = (x - ax) * ux + (y - ay) * uy;
                float sClamped = clamp(proj, s0, s1);
                float qx = ax + ux * sClamped;
                float qy = ay + uy * sClamped;
                float distToAxis = (float) Math.sqrt((x - qx) * (x - qx) + (y - qy) * (y - qy));
                float u = clamp((sClamped - s0) / Math.max(1f, s1 - s0), 0f, 1f);
                float oneMinusU = 1.0f - u;
                float rTip = 2.4f + (1.0f - reach) * Math.min(4.8f, ra * 0.14f);
                float rCone = rTip + (yShoulder - rTip) * oneMinusU * oneMinusU;
                float dCone = distToAxis - rCone;
                bestBridge = Math.min(bestBridge, dCone);
            }
        }

        // Fill interior of connected 3-ship triangles so the center of a 3+ circle cluster never traps an inverted hole
        if (mCount >= 3) {
            for (int i = 0; i < mCount; i++) {
                for (int j = i + 1; j < mCount; j++) {
                    if (kPair[i][j] <= 0f) continue;
                    for (int l = j + 1; l < mCount; l++) {
                        if (kPair[j][l] <= 0f || kPair[i][l] <= 0f) continue;
                        float dTri = signedDistanceToTriangle2D(
                                x, y, sx[i], sy[i], sx[j], sy[j], sx[l], sy[l]);
                        float minR = Math.min(sr[i], Math.min(sr[j], sr[l]));
                        float avgOpen = (kPair[i][j] + kPair[j][l] + kPair[i][l]) / 3.0f;
                        float pad = minWaistPx + avgOpen * minR * 0.45f;
                        bestBridge = sminPoly(bestBridge, dTri - pad, 6.0f);
                    }
                }
            }
        }

        return sminPoly(bestCircle, bestBridge, 2.5f);
    }

    private static float signedDistanceToTriangle2D(
            float px, float py,
            float ax, float ay,
            float bx, float by,
            float cx, float cy) {
        // Ensure CCW order (A, B, C)
        float cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (Math.abs(cross) < 1e-3f) {
            return Float.MAX_VALUE;
        }
        if (cross < 0f) {
            float tx = bx; bx = cx; cx = tx;
            float ty = by; by = cy; cy = ty;
        }
        float d0 = distSqPointToSegment(px, py, ax, ay, bx, by);
        float d1 = distSqPointToSegment(px, py, bx, by, cx, cy);
        float d2 = distSqPointToSegment(px, py, cx, cy, ax, ay);
        float minDist = (float) Math.sqrt(Math.min(d0, Math.min(d1, d2)));
        float s0 = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
        float s1 = (cx - bx) * (py - by) - (cy - by) * (px - bx);
        float s2 = (ax - cx) * (py - cy) - (ay - cy) * (px - cx);
        boolean inside = (s0 >= 0f && s1 >= 0f && s2 >= 0f);
        return inside ? -minDist : minDist;
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
        float[][] grid = new float[rows + 1][cols + 1];
        for (int r = 0; r <= rows; r++) {
            float y = minY + r * stepPx;
            for (int c = 0; c <= cols; c++) {
                float x = minX + c * stepPx;
                grid[r][c] = evalMetaballSdf(x, y, sx, sy, sr, mCount, total, kPair, minWaistPx);
            }
        }

        Map<Long, Vector2f> nodePos = new HashMap<>();
        Map<Long, List<Long>> adj = new HashMap<>();

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                float v0 = grid[r][c];
                float v1 = grid[r][c + 1];
                float v2 = grid[r + 1][c + 1];
                float v3 = grid[r + 1][c];
                int mask = (v0 <= 0f ? 1 : 0)
                        | (v1 <= 0f ? 2 : 0)
                        | (v2 <= 0f ? 4 : 0)
                        | (v3 <= 0f ? 8 : 0);
                if (mask == 0 || mask == 15) continue;

                long eBottom = msEdgeId(r, c, 0);
                long eRight = msEdgeId(r, c + 1, 1);
                long eTop = msEdgeId(r + 1, c, 0);
                long eLeft = msEdgeId(r, c, 1);

                float x0 = minX + c * stepPx;
                float y0 = minY + r * stepPx;
                if ((mask & 1) != ((mask >> 1) & 1) && !nodePos.containsKey(eBottom)) {
                    nodePos.put(eBottom, new Vector2f(x0 + msInterp(v0, v1) * stepPx, y0));
                }
                if (((mask >> 1) & 1) != ((mask >> 2) & 1) && !nodePos.containsKey(eRight)) {
                    nodePos.put(eRight, new Vector2f(x0 + stepPx, y0 + msInterp(v1, v2) * stepPx));
                }
                if (((mask >> 3) & 1) != ((mask >> 2) & 1) && !nodePos.containsKey(eTop)) {
                    nodePos.put(eTop, new Vector2f(x0 + msInterp(v3, v2) * stepPx, y0 + stepPx));
                }
                if ((mask & 1) != ((mask >> 3) & 1) && !nodePos.containsKey(eLeft)) {
                    nodePos.put(eLeft, new Vector2f(x0, y0 + msInterp(v0, v3) * stepPx));
                }

                float centerVal = 0.25f * (v0 + v1 + v2 + v3);
                switch (mask) {
                    case 1:
                        msAddDirEdge(adj, eLeft, eBottom);
                        break;
                    case 2:
                        msAddDirEdge(adj, eBottom, eRight);
                        break;
                    case 3:
                        msAddDirEdge(adj, eLeft, eRight);
                        break;
                    case 4:
                        msAddDirEdge(adj, eRight, eTop);
                        break;
                    case 5:
                        if (centerVal <= 0f) {
                            msAddDirEdge(adj, eLeft, eTop);
                            msAddDirEdge(adj, eRight, eBottom);
                        } else {
                            msAddDirEdge(adj, eLeft, eBottom);
                            msAddDirEdge(adj, eRight, eTop);
                        }
                        break;
                    case 6:
                        msAddDirEdge(adj, eBottom, eTop);
                        break;
                    case 7:
                        msAddDirEdge(adj, eLeft, eTop);
                        break;
                    case 8:
                        msAddDirEdge(adj, eTop, eLeft);
                        break;
                    case 9:
                        msAddDirEdge(adj, eTop, eBottom);
                        break;
                    case 10:
                        if (centerVal <= 0f) {
                            msAddDirEdge(adj, eBottom, eLeft);
                            msAddDirEdge(adj, eTop, eRight);
                        } else {
                            msAddDirEdge(adj, eBottom, eRight);
                            msAddDirEdge(adj, eTop, eLeft);
                        }
                        break;
                    case 11:
                        msAddDirEdge(adj, eTop, eRight);
                        break;
                    case 12:
                        msAddDirEdge(adj, eRight, eLeft);
                        break;
                    case 13:
                        msAddDirEdge(adj, eRight, eBottom);
                        break;
                    case 14:
                        msAddDirEdge(adj, eBottom, eLeft);
                        break;
                    default:
                        break;
                }
            }
        }

        List<List<Vector2f>> loops = new ArrayList<>();
        Set<Long> usedEdges = new HashSet<>();
        for (Map.Entry<Long, List<Long>> entry : adj.entrySet()) {
            long start = entry.getKey();
            for (long firstNext : entry.getValue()) {
                long dirKey = (start << 32) ^ (firstNext & 0xFFFFFFFFL);
                if (usedEdges.contains(dirKey)) continue;
                List<Vector2f> poly = new ArrayList<>();
                long curr = start;
                long next = firstNext;
                int guard = 2000;
                while (guard-- > 0) {
                    usedEdges.add((curr << 32) ^ (next & 0xFFFFFFFFL));
                    Vector2f pt = nodePos.get(curr);
                    if (pt != null) poly.add(pt);
                    if (next == start) break;
                    curr = next;
                    List<Long> outs = adj.get(curr);
                    if (outs == null || outs.isEmpty()) break;
                    long chosen = outs.get(0);
                    for (long cand : outs) {
                        if (!usedEdges.contains((curr << 32) ^ (cand & 0xFFFFFFFFL))) {
                            chosen = cand;
                            break;
                        }
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

    private static long msEdgeId(int r, int c, int type) {
        return ((long) type << 28) | ((long) r << 14) | (long) c;
    }

    private static float msInterp(float a, float b) {
        float denom = a - b;
        if (Math.abs(denom) < 1e-6f) return 0.5f;
        return clamp(a / denom, 0.02f, 0.98f);
    }

    private static void msAddDirEdge(Map<Long, List<Long>> adj, long from, long to) {
        // In Y-up screen coordinates, orient directed edges so that inside regions (SDF <= 0) lie on the LEFT
        // of each edge: outer droplet perimeters trace Counter-Clockwise (signedArea > 0) and interior holes
        // trace Clockwise (signedArea < 0).
        List<Long> list = adj.get(to);
        if (list == null) {
            list = new ArrayList<>(2);
            adj.put(to, list);
        }
        list.add(from);
    }

    // =========================================================================
    // Section 4: Layer Renderers (Polygons, Top-Layer Ship Sprites, Flagship Stars, Overlap Stripes, Waypoints & Labels)
    // =========================================================================

    private void renderSingleDivisionPolygon(
            RenderedDivisionPolygon rp,
            float basePolyAlpha) {

        List<Vector2f> poly = rp.displayPolyScreen;
        if (poly.size() < 3) return;

        // Mild shading so ship sprites and map grid remain clear underneath and on top
        float effectiveAlpha = basePolyAlpha * rp.cohesionAlphaMult;
        float centerAlpha = Math.min(0.18f, effectiveAlpha * 0.72f);
        float edgeAlpha = Math.min(0.13f, effectiveAlpha * 0.50f);

        // Triangulate the droplet contour via Ear-Clipping so narrow-waisted hourglass / figure-8
        // water droplets (pinched down to a tiny point of contact) fill cleanly without fan-ray bleed
        List<int[]> triangles = triangulatePolygonCCW(poly);
        if (!triangles.isEmpty()) {
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
            for (RenderedDivisionPolygon rp : renderedPolys) {
                for (ShipAPI ship : rp.hullMembers) {
                    if (ship == null || !ship.isAlive()) continue;
                    Object icon = mapGetIconHandle.invoke(mapDisplayInstance, (Object) ship);
                    if (icon != null) {
                        iconRenderSpriteHandle.invoke(icon, false, scale, fade);
                    }
                }
            }
            for (RenderedDivisionPolygon rp : renderedPolys) {
                for (ShipAPI ship : rp.hullMembers) {
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

        SpriteAPI goldStar = null;
        SpriteAPI silverStar = null;
        try {
            goldStar = Global.getSettings().getSprite("division_ai", "star_gold");
            silverStar = Global.getSettings().getSprite("division_ai", "star_silver");
        } catch (Throwable ignored) {
            return;
        }
        if (goldStar == null && silverStar == null) return;

        Set<ShipAPI> fleetFlagships = resolveFleetFlagships(playerFog);
        Set<ShipAPI> divisionFlagships = resolveDivisionFlagships(playerFog, fleetFlagships);
        if (fleetFlagships.isEmpty() && divisionFlagships.isEmpty()) return;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        try {
            if (silverStar != null) {
                for (ShipAPI ship : divisionFlagships) {
                    renderShipCornerStar(ship, silverStar, false, scale, offsetX, offsetY, fade);
                }
            }
            if (goldStar != null) {
                for (ShipAPI ship : fleetFlagships) {
                    renderShipCornerStar(ship, goldStar, false, scale, offsetX, offsetY, fade);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
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
        if (mapDisplayInstance != null && mapGetIconHandle != null && iconGetRadiusHandle != null) {
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

        // Both Fleet Flagship (gold star) and Division Leader (silver star) render on the lower-right corner,
        // vertically aligned with the commander portrait on the lower-left of the warroom ship icon
        // (in com.fs.starfarer.ui.B.new, boxHalf = iconRadiusPx * (16.8f / 18.0f), portraitCenter = (sx - boxHalf + 2f, sy - boxHalf + 2f)).
        float boxHalf = iconRadiusPx * (16.8f / 18.0f);
        float cornerOffset = Math.max(boxHalf - 2.0f, 10.5f * zoom);
        float starX = (float) Math.round(sx + cornerOffset);
        float starY = (float) Math.round(upperRightCorner ? (sy + cornerOffset) : (sy - cornerOffset));
        float starSize = (float) (Math.round(clamp(18.5f * zoom, 14.0f, 26.0f) * 0.5f) * 2);

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

        ShipAPI alphaCoreFlagOverride = BattlefieldFirepowerEvaluator.getPlayerConvergenceFlagshipOverride();
        if (alphaCoreFlagOverride != null && isEligibleCommandMapShip(alphaCoreFlagOverride, playerFog)) {
            result.add(alphaCoreFlagOverride);
            hasPlayerFleetFlag = true;
        } else if (playerGroup != null && !playerGroup.divisions.isEmpty()) {
            ShipAPI resolvedPlayerFlag = BattlefieldFirepowerEvaluator.resolveActiveFleetFlagship(engine, playerGroup);
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
     * Finds every pair of overlapping division polygons, computes their exact convex intersection
     * via Sutherland-Hodgman clipping, and fills the overlap region with thick alternating diagonal stripes.
     */
    private void renderOverlappingPolygonStripes(
            List<RenderedDivisionPolygon> renderedPolys,
            float basePolyAlpha) {

        int n = renderedPolys.size();
        for (int i = 0; i < n; i++) {
            RenderedDivisionPolygon a = renderedPolys.get(i);
            // Skip stripe generation for dispersed/low-cohesion blobs or single-ship transit droplets
            if (a.cohesionAlphaMult < 0.35f) continue;
            for (int j = i + 1; j < n; j++) {
                RenderedDivisionPolygon b = renderedPolys.get(j);
                if (a.div == b.div) continue;
                if (b.cohesionAlphaMult < 0.35f) continue;

                // Only render contested diagonal stripes when OPPOSING or distinct battle groups overlap
                // (never spam purple/blue or cyan stripes across friendly-on-friendly or enemy-on-enemy droplets)
                boolean sameSide = (a.isEnemy == b.isEnemy) && (a.isAlliedAi == b.isAlliedAi);
                if (sameSide) {
                    continue;
                }

                // Fast AABB reject
                if (a.maxX < b.minX || b.maxX < a.minX || a.maxY < b.minY || b.maxY < a.minY) {
                    continue;
                }

                // Verify that at least one pair of actual ship footprints from A and B overlap in screen space
                // (prevents phantom stripes in empty concave bays of L-shaped or curved divisions)
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

                List<Vector2f> intersection = clipConvexPolygon(a.polyScreen, b.polyScreen);
                if (intersection.size() < 3 || computePolygonArea(intersection) < 380f) {
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

                float pairCohesion = Math.max(0.12f, Math.min(a.cohesionAlphaMult, b.cohesionAlphaMult));
                float stripeAlphaA = Math.min(0.28f, Math.max(0.08f, basePolyAlpha * 1.15f * pairCohesion));
                float stripeAlphaB = Math.min(0.24f, Math.max(0.06f, basePolyAlpha * 0.95f * pairCohesion));

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
            }
        }
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
    private void renderDivisionEntityLabel(RenderedDivisionPolygon rp, float fade) {
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
        LabelAPI label = getOrCreateLabel(liveCallsign, cacheKeyOverride, rp.isEnemy, rp.isAlliedAi, false);
        if (label != null) {
            float textW = label.computeTextWidth(liveCallsign);
            float textH = label.computeTextHeight(liveCallsign);
            float labelY = cy + (rp.isEnemy ? (textH * 0.5f) : (textH + 4f));
            label.getPosition().setSize(textW + 10f, textH + 4f);
            label.getPosition().setLocation(cx - (textW * 0.5f), labelY);
            label.render(fade * (rp.isEnemy ? 0.84f : 0.95f));

            PermanentDivisionManager.DivisionFormation activeForm = div.activeFormation != null
                    ? div.activeFormation
                    : div.doctrinalFormation;
            if (activeForm != null) {
                Color iconTint = new Color(
                        Math.min(255, Math.round(rp.cr * 255f + 45f)),
                        Math.min(255, Math.round(rp.cg * 255f + 45f)),
                        Math.min(255, Math.round(rp.cb * 255f + 45f)));
                FleetDoctrineDialog.renderFormationIcon(
                        activeForm,
                        cx - (textW * 0.5f) - 11f,
                        labelY - textH * 0.45f,
                        15f,
                        iconTint,
                        fade * (rp.isEnemy ? 0.82f : 0.95f));
            }
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
        List<BattlefieldFirepowerEvaluator.VoidstrikeRecord> voidstrikes =
                BattlefieldFirepowerEvaluator.getActiveVoidstrikes();
        if (voidstrikes == null || voidstrikes.isEmpty()) return;

        for (BattlefieldFirepowerEvaluator.VoidstrikeRecord vs : voidstrikes) {
            if (vs == null || vs.activeFighters.isEmpty() || vs.voidstrikeNumber <= 0) continue;

            boolean isEnemy = (vs.side == BattlefieldFirepowerEvaluator.VOIDSTRIKE_SIDE_ENEMY);
            boolean isAlly = (vs.side == BattlefieldFirepowerEvaluator.VOIDSTRIKE_SIDE_ALLIED);
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

            LabelAPI label = getOrCreateLabel(
                    text,
                    "VOIDSTRIKE_" + vs.side + "_" + vs.voidstrikeNumber,
                    isEnemy,
                    isAlly,
                    false);
            if (label != null) {
                float textW = label.computeTextWidth(text);
                float textH = label.computeTextHeight(text);
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

    public static List<Vector2f> subdivideLongEdgesScreen(List<Vector2f> poly, float maxEdgeLenPx) {
        if (poly == null || poly.size() < 3 || maxEdgeLenPx <= 1f) {
            return poly;
        }
        int n = poly.size();
        List<Vector2f> out = new ArrayList<>(n * 2);
        for (int i = 0; i < n; i++) {
            Vector2f p0 = poly.get(i);
            Vector2f p1 = poly.get((i + 1) % n);
            float dx = p1.x - p0.x;
            float dy = p1.y - p0.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            int steps = Math.max(1, Math.min(32, (int) Math.ceil(len / maxEdgeLenPx)));
            for (int s = 0; s < steps; s++) {
                float t = (float) s / (float) steps;
                out.add(new Vector2f(p0.x + dx * t, p0.y + dy * t));
            }
        }
        return out;
    }

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
        return Math.abs(computeSignedPolygonArea(poly));
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
        PermanentDivisionManager.DivisionFormation f = div.activeFormation != null
                ? div.activeFormation
                : div.doctrinalFormation;
        if (f != null) {
            return String.format(Locale.ROOT, "%s · %s - %d%%", intentShort, f.shortName.toUpperCase(Locale.ROOT), fluxPct);
        }
        return String.format(Locale.ROOT, "%s - %d%%", intentShort, fluxPct);
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
            case FIGHTER:
                return 260f;
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
