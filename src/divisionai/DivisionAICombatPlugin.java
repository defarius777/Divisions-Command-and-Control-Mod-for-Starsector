package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import org.apache.log4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Runtime combat loop for Division AI:
 *  1. Never forcibly assigns player-controlled ships to divisions on the battle layer (only inherits
 *     the player's campaign Permanent Divisions).
 *  2. If the player fleet has 0 active divisions in battle, neither the Player OTC
 *     nor any Player Division Commander initializes — player ships default 100% to vanilla combat AI.
 *  3. If an Allied AI fleet (ship.isAlly()) joins a player fleet that has no divisions, the Allied AI
 *     fleet's own OTC and Division Commanders still initialize and function as normal.
 *  4. Reorganizes the Deployment Window into rows following the player's list of non-empty divisions.
 */
public class DivisionAICombatPlugin extends BaseEveryFrameCombatPlugin {

    private static final Logger LOG = Global.getLogger(DivisionAICombatPlugin.class);
    private static final String ENGINE_CUSTOM_DATA_KEY = "divisionai_active_plugin";
    private static final Object HUD_STATUS_KEY_DIV = new Object();
    private static final Object HUD_STATUS_KEY_OTC = new Object();

    private static final float DIVISION_EVAL_INTERVAL = 0.50f;
    private static final float OTC_EVAL_INTERVAL = 1.50f;
    public static final float TSFC_EVAL_INTERVAL = BattlefieldFirepowerEvaluator.TSFC_EVAL_INTERVAL; // 0.10s keyframe cadence

    private CombatEngineAPI engine;
    private BattleGroupState playerGroup;
    private BattleGroupState alliedAiGroup;
    private BattleGroupState enemyGroup;
    private DivisionTacticalMapOverlay tacticalOverlay;
    private DivisionDeploymentDialogOrganizer deploymentOrganizer;
    private DynamicBattleMusicController musicController;
    private final List<DivisionState> passiveContactGroups = new ArrayList<>();
    private final List<DivisionState> passiveEnemyContactGroups = new ArrayList<>();
    private BattleGroupState side0OpposingView;
    private BattleGroupState side1OpposingView;

    private float divisionTimer = 0f;
    private float otcTimer = 0f;
    private float tsfcTimer = 0f;
    private boolean initializedBattle = false;
    private int lastConvStateSyncShipsCount = -1;
    private boolean loggedPlayerOtcInit = false;
    private boolean loggedAlliedAiOtcInit = false;
    private boolean loggedEnemyOtcInit = false;
    private boolean preRenderHookAttached = false;
    private CombatEngineAPI preRenderHookEngine = null;
    private boolean renderedBelowShipsLayerThisFrame = false;
    private boolean loggedBattleSummary = false;
    private final java.util.Set<String> loggedObfuscatedHookFailures = new java.util.HashSet<>();

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        this.initializedBattle = false;
        this.lastConvStateSyncShipsCount = -1;
        this.loggedPlayerOtcInit = false;
        this.loggedAlliedAiOtcInit = false;
        this.loggedEnemyOtcInit = false;
        this.divisionTimer = 0f;
        this.otcTimer = 0f;
        this.tsfcTimer = 0f;
        this.loggedBattleSummary = false;
        this.loggedObfuscatedHookFailures.clear();
        this.passiveContactGroups.clear();
        this.passiveEnemyContactGroups.clear();
        this.side0OpposingView = null;
        this.side1OpposingView = null;
        this.tacticalOverlay = new DivisionTacticalMapOverlay(engine);
        this.deploymentOrganizer = new DivisionDeploymentDialogOrganizer();
        this.musicController = new DynamicBattleMusicController();
        BattlefieldFirepowerEvaluator.resetForNewBattle();
        DivisionOtcCommandManager.resetForNewBattle();
        if (engine != null) {
            engine.getCustomData().put(ENGINE_CUSTOM_DATA_KEY, this);
        }
        this.preRenderHookAttached = false;
        this.renderedBelowShipsLayerThisFrame = false;
        ensurePreRenderDeploymentHook();
    }

    /**
     * Vanilla's deployment dialog spawns ships inside CombatState's widget-panel advance, which runs AFTER every
     * EveryFrameCombatPlugin.advance on that frame and BEFORE the frame renders. A lowest-layer
     * CombatLayeredRenderingPlugin renders before any ship sprite, so placing new waves here puts them in their
     * doctrinal formation on the exact frame they first appear (no vanilla spawn grid is ever drawn).
     */
    private void ensurePreRenderDeploymentHook() {
        if (engine == null) return;
        if (preRenderHookAttached && preRenderHookEngine == engine) return;
        final DivisionAICombatPlugin owner = this;
        final CombatEngineAPI hookEngine = engine;
        try {
            engine.addLayeredRenderingPlugin(new BaseCombatLayeredRenderingPlugin(CombatEngineLayers.BELOW_PLANETS) {
                @Override
                public EnumSet<CombatEngineLayers> getActiveLayers() {
                    return EnumSet.of(
                            CombatEngineLayers.BELOW_PLANETS,
                            CombatEngineLayers.BELOW_SHIPS_LAYER);
                }

                @Override
                public float getRenderRadius() {
                    return 1.0e7f;
                }

                @Override
                public boolean isExpired() {
                    return owner.engine != hookEngine;
                }

                @Override
                public void render(CombatEngineLayers layer, com.fs.starfarer.api.combat.ViewportAPI viewport) {
                    if (layer == CombatEngineLayers.BELOW_PLANETS) {
                        owner.interceptBeforeRender();
                    } else if (layer == CombatEngineLayers.BELOW_SHIPS_LAYER) {
                        owner.renderedBelowShipsLayerThisFrame = true;
                        owner.renderBattleScreenTsfcBelowShipsLayer(viewport);
                    }
                }
            });
            preRenderHookAttached = true;
            preRenderHookEngine = engine;
        } catch (Throwable ignored) {
        }
    }

    /** Runs the deployment-wave interception from render hooks (before ships are drawn). */
    void interceptBeforeRender() {
        try {
            if (engine == null || !initializedBattle || engine.isCombatOver()) return;
            interceptNewlyDeployedWaves(engine.getTotalElapsedTime(false));
        } catch (Throwable t) {
            LOG.warn("[DivisionAI] Pre-render deployment interception failed: " + t);
        }
    }

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        ensurePreRenderDeploymentHook();
        // Fallback in case BELOW_SHIPS_LAYER has not yet been flushed by executeAdds() on the very first frame
        if (!renderedBelowShipsLayerThisFrame) {
            interceptBeforeRender();
            renderBattleScreenTsfcBelowShipsLayer(viewport);
        }
        renderedBelowShipsLayerThisFrame = false;
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (!BattlefieldFirepowerEvaluator.isCommandViewWindowActive(engine)) return;
        TsfcWarroomRenderer.processInputEvents(engine, events);
    }

    public static final float BATTLE_SCREEN_TSFC_OPACITY_MULT = 0.24f;

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        // Battle screen TSFC now renders underneath ship sprites on CombatEngineLayers.BELOW_SHIPS_LAYER.
        // Only fall back to UI-layer rendering if the layered rendering hook could not be attached.
        if (!preRenderHookAttached) {
            renderBattleScreenTsfcOverlay(viewport);
        }
    }

    /**
     * Renders the Battle Screen TSFC overlays and high-contrast weapon firing arcs on
     * {@link CombatEngineLayers#BELOW_SHIPS_LAYER} so that Time, Space, Firepower, Convergence, and
     * high-contrast firing arc geometries render underneath ship sprites, shields, strike craft, and
     * missiles/munitions when the player is captaining a ship or using the camera feed ("F" key) on a
     * friendly ship, without rendering Warroom truescale ship/hulk icons on the battle graphics layer.
     */
    void renderBattleScreenTsfcBelowShipsLayer(ViewportAPI viewport) {
        if (engine == null || !initializedBattle || engine.isCombatOver() || viewport == null) {
            return;
        }
        if (!TsfcWarroomRenderer.isTimeOverlayVisible()
                && !TsfcWarroomRenderer.isSpaceOverlayVisible()
                && !TsfcWarroomRenderer.isOverlayVisible()
                && !TsfcWarroomRenderer.isConvergenceOverlayVisible()) {
            return;
        }
        float screenW = Global.getSettings() != null ? Global.getSettings().getScreenWidth() : 1920f;
        float screenH = Global.getSettings() != null ? Global.getSettings().getScreenHeight() : 1080f;
        float visW = viewport.getVisibleWidth();
        float visH = viewport.getVisibleHeight();
        float llx = viewport.getLLX();
        float lly = viewport.getLLY();
        float invScaleX;
        float invScaleY;
        if (visW > 1f && visH > 1f && screenW > 1f && screenH > 1f) {
            invScaleX = visW / screenW;
            invScaleY = visH / screenH;
        } else {
            float offsetX = viewport.convertWorldXtoScreenX(0f);
            float scale = (viewport.convertWorldXtoScreenX(1000f) - offsetX) / 1000f;
            if (scale <= 1e-5f) {
                return;
            }
            invScaleX = 1f / scale;
            invScaleY = 1f / scale;
            llx = -offsetX * invScaleX;
            lly = -viewport.convertWorldYtoScreenY(0f) * invScaleY;
        }
        // CombatState.render sets GL_PROJECTION to glOrtho(llx, llx + visW, lly, lly + visH, -4000, 4000)
        // and GL_MODELVIEW to Identity before calling CombatEngine.render(false).
        // TsfcWarroomRenderer emits screen-space vertices sx = (wx - llx) * (screenW / visW) and
        // sy = (wy - lly) * (screenH / visH), so translating by (llx, lly) and scaling by (visW / screenW, visH / screenH)
        // on GL_MODELVIEW reconstructs exact world coordinates (wx, wy) 1:1 with ship sprites at every zoom and camera position.
        org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glLoadIdentity();
        org.lwjgl.opengl.GL11.glTranslatef(llx, lly, 0f);
        org.lwjgl.opengl.GL11.glScalef(invScaleX, invScaleY, 1f);
        try {
            renderBattleScreenTsfcOverlay(viewport);
        } finally {
            org.lwjgl.opengl.GL11.glMatrixMode(org.lwjgl.opengl.GL11.GL_MODELVIEW);
            org.lwjgl.opengl.GL11.glPopMatrix();
        }
        // Render high-contrast firing arcs in world coordinates on BELOW_SHIPS_LAYER immediately above TSFC petals
        // and underneath ships, strike craft, and missiles/munitions.
        renderBattleScreenHighContrastFiringArcs(viewport);
    }

    public void renderBattleScreenTsfcOverlay(ViewportAPI viewport) {
        if (engine == null || !initializedBattle || engine.isCombatOver() || viewport == null) {
            return;
        }
        if (!BattlefieldFirepowerEvaluator.isRealCombatEngine(engine)) {
            return;
        }
        if (!TsfcWarroomRenderer.isTimeOverlayVisible()
                && !TsfcWarroomRenderer.isSpaceOverlayVisible()
                && !TsfcWarroomRenderer.isOverlayVisible()
                && !TsfcWarroomRenderer.isConvergenceOverlayVisible()) {
            return;
        }
        com.fs.starfarer.api.combat.CombatUIAPI ui = engine.getCombatUI();
        if (ui != null && ui.isShowingDeploymentDialog()) {
            return;
        }
        // When the player is using the camera feed ("F" key) on a friendly ship inside the Command UI
        // (warroom.isShowingVideoFeed() == true), ui.isShowingCommandUI() remains true and getCommandUIOpacity() is 1.0f,
        // even though the tactical map is hidden and the live battle graphics layer is displayed.
        boolean showingCameraFeed = (tacticalOverlay != null && tacticalOverlay.isShowingVideoFeed());
        float cmdMapOpacity = (ui != null && ui.isShowingCommandUI() && !showingCameraFeed)
                ? ui.getCommandUIOpacity()
                : 0f;
        if (cmdMapOpacity >= 0.95f) {
            return;
        }
        float configuredBattleMult = DivisionAISettings.getTsfcBattleOpacityMult();
        float battleFade = configuredBattleMult * Math.max(0f, 1f - cmdMapOpacity);
        if (battleFade <= 0.01f) {
            return;
        }
        float screenW = Global.getSettings() != null ? Global.getSettings().getScreenWidth() : 1920f;
        float screenH = Global.getSettings() != null ? Global.getSettings().getScreenHeight() : 1080f;
        float visW = viewport.getVisibleWidth();
        float visH = viewport.getVisibleHeight();
        float scale;
        float offsetX;
        float offsetY;
        if (visW > 1f && visH > 1f && screenW > 1f && screenH > 1f) {
            scale = screenW / visW;
            offsetX = -viewport.getLLX() * scale;
            offsetY = -viewport.getLLY() * (screenH / visH);
        } else {
            offsetX = viewport.convertWorldXtoScreenX(0f);
            offsetY = viewport.convertWorldYtoScreenY(0f);
            scale = (viewport.convertWorldXtoScreenX(1000f) - offsetX) / 1000f;
        }
        if (scale <= 1e-5f) {
            return;
        }
        float tscBattleFade = Math.min(1.15f, battleFade * 3.20f);
        // Render only the TSFC overlays (Time, Space, Convergence, Firepower) on the battle graphics layer;
        // never render Warroom truescale ship or hulk icons on the battle graphics layer.
        TsfcWarroomRenderer.renderTimeSpaceAndConvergenceScreenSpace(
                engine, scale, offsetX, offsetY, tscBattleFade, playerGroup, alliedAiGroup, enemyGroup);
        TsfcWarroomRenderer.renderFirepowerRosesScreenSpace(
                engine, scale, offsetX, offsetY, battleFade, playerGroup, alliedAiGroup, enemyGroup);
    }

    void renderBattleScreenHighContrastFiringArcs(ViewportAPI viewport) {
        if (engine == null || !initializedBattle || engine.isCombatOver() || viewport == null) {
            return;
        }
        if (!BattlefieldFirepowerEvaluator.isRealCombatEngine(engine)) {
            return;
        }
        if (!TsfcWarroomRenderer.isAnyTsfcOverlayVisible()) {
            return;
        }
        com.fs.starfarer.api.combat.CombatUIAPI ui = engine.getCombatUI();
        if (ui != null && ui.isShowingDeploymentDialog()) {
            return;
        }
        boolean showingCameraFeed = (tacticalOverlay != null && tacticalOverlay.isShowingVideoFeed());
        float cmdMapOpacity = (ui != null && ui.isShowingCommandUI() && !showingCameraFeed)
                ? ui.getCommandUIOpacity()
                : 0f;
        if (cmdMapOpacity >= 0.95f) {
            return;
        }
        float configuredBattleMult = DivisionAISettings.getTsfcBattleOpacityMult();
        if (configuredBattleMult <= 0.01f) {
            return;
        }
        ShipAPI focusShip = (showingCameraFeed && tacticalOverlay != null)
                ? tacticalOverlay.resolveVideoFeedShip()
                : engine.getPlayerShip();
        if (focusShip == null) {
            return;
        }
        if (!showingCameraFeed && tacticalOverlay != null && !tacticalOverlay.areWeaponArcsOn()) {
            return;
        }
        float animProgress = (!showingCameraFeed && tacticalOverlay != null)
                ? tacticalOverlay.getArcRendererAnimProgress()
                : 1.0f;
        if (animProgress <= 0.005f) {
            return;
        }
        float arcAlpha = Math.min(1.0f, Math.max(0.65f, configuredBattleMult * 3.5f)) * Math.max(0f, 1f - cmdMapOpacity);
        com.fs.starfarer.api.combat.WeaponGroupAPI activeGroup = (!showingCameraFeed && tacticalOverlay != null)
                ? tacticalOverlay.getArcRendererActiveWeaponGroup(focusShip)
                : focusShip.getSelectedGroupAPI();
        TsfcWarroomRenderer.renderHighContrastCaptaincyFiringArcsWorldSpace(
                engine, viewport, focusShip, activeGroup, arcAlpha, animProgress);
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        DivisionAISettings.advanceLunaSettingsReinitButton();
        if (engine == null) {
            engine = Global.getCombatEngine();
            if (engine == null) return;
        }
        // Never run combat C2 or UI overlays on the Main Menu background battle (TitleScreenState)
        if (!BattlefieldFirepowerEvaluator.isRealCombatEngine(engine)) {
            DynamicBattleMusicController.ensureCleanedUpOutsideCombat();
            return;
        }
        if (BattlefieldFirepowerEvaluator.isCommandViewWindowActive(engine)) {
            TsfcWarroomRenderer.processInputEvents(engine, events);
        }
        if (tacticalOverlay == null) {
            tacticalOverlay = new DivisionTacticalMapOverlay(engine);
        } else {
            tacticalOverlay.setEngine(engine);
        }
        tacticalOverlay.ensureAttachedToWarroomUI();
        ensurePreRenderDeploymentHook();

        if (!initializedBattle) {
            CommanderProfile playerCmdr = DivisionAutoFormer.resolveOtc(engine, 0);
            CommanderProfile alliedAiCmdr = DivisionAutoFormer.resolveAlliedAiOtc(engine, null);
            CommanderProfile enemyCmdr = DivisionAutoFormer.resolveOtc(engine, 1);

            playerGroup = new BattleGroupState(0, playerCmdr);
            alliedAiGroup = new BattleGroupState(0, alliedAiCmdr);
            alliedAiGroup.isAlliedAi = true;
            enemyGroup = new BattleGroupState(1, enemyCmdr);

            // Player OTC orchestration comes from Fleet Doctrine; Allied AI and Enemy battle groups use the Hybrid defaults
            PermanentDivisionManager.FleetDoctrineConfig doctrine = PermanentDivisionManager.getFleetDoctrine();
            playerGroup.authority = doctrine.getOtcAuthority();
            playerGroup.fleetWideFormation = doctrine.getFleetWideFormation();
            playerGroup.multiDivisionSetpieceDoctrine = doctrine.getMultiDivisionSetpiece();
            playerGroup.deployInFleetFormation = doctrine.isDeployInFleetFormation();
            playerGroup.orchestratedAdvance = doctrine.isOrchestratedAdvance();
            playerGroup.plays.clear();
            playerGroup.plays.addAll(doctrine.getEnabledPlays());

            playerGroup.missionContext = DivisionAutoFormer.detectMissionContext(engine, 0);
            alliedAiGroup.missionContext = playerGroup.missionContext;
            enemyGroup.missionContext = DivisionAutoFormer.detectMissionContext(engine, 1);

            tacticalOverlay.setBattleGroups(playerGroup, alliedAiGroup, enemyGroup);
            initializedBattle = true;
            if (DivisionAISettings.isDebugLoggingEnabled()) {
                LOG.info(String.format("[DivisionAI] Player OTC doctrine: authority=%s, plays=%s",
                        playerGroup.authority.shortName, playerGroup.plays));
            }
        }

        if (deploymentOrganizer == null) {
            deploymentOrganizer = new DivisionDeploymentDialogOrganizer();
        }
        if (PermanentDivisionManager.resolveActiveMissionScenario() != null) {
            PermanentDivisionManager.ensureMissionDeploymentPointCap(engine);
        }
        // Organize the Deployment Window even while the combat engine is paused during deployment
        deploymentOrganizer.setBattleContext(playerGroup, enemyGroup, musicController);
        deploymentOrganizer.advance(engine);

        if (initializedBattle) {
            tacticalOverlay.setBattleGroups(playerGroup, alliedAiGroup, enemyGroup);
            int currShipsCount = (engine.getShips() != null) ? engine.getShips().size() : 0;
            if (currShipsCount != lastConvStateSyncShipsCount) {
                lastConvStateSyncShipsCount = currShipsCount;
                DivisionOtcCommandManager.updateConvergenceCommandStates(engine, playerGroup);
                DivisionOtcCommandManager.updateConvergenceCommandStates(engine, alliedAiGroup);
                DivisionOtcCommandManager.updateConvergenceCommandStates(engine, enemyGroup);
            }
        }

        if (engine.isCombatOver()) {
            if (!loggedBattleSummary) {
                loggedBattleSummary = true;
                logBattleSummary(engine, playerGroup, enemyGroup);
            }
            if (!engine.isPaused()) {
                float overNow = engine.getTotalElapsedTime(false);
                tsfcTimer += amount;
                if (tsfcTimer >= TSFC_EVAL_INTERVAL) {
                    tsfcTimer = 0f;
                    BattlefieldFirepowerEvaluator.updateTsfcKeyframeTick(
                            engine,
                            overNow,
                            playerGroup,
                            alliedAiGroup,
                            enemyGroup,
                            side1OpposingView,
                            side0OpposingView);
                }
            }
            if (musicController != null) {
                BattleGroupState musicPlayerView = (side0OpposingView != null && !side0OpposingView.divisions.isEmpty())
                        ? side0OpposingView
                        : playerGroup;
                musicController.advance(engine, musicPlayerView, enemyGroup, amount);
            }
            return;
        }

        float now = engine.getTotalElapsedTime(false);

        // Flagship-loss command disruption (120s for fleet commander, 30s for lower-rank / no-rank)
        if (!engine.isPaused() || DivisionOtcCommandManager.isEmergencyModalAutoOpenRequested()) {
            DivisionOtcCommandManager.updateFlagshipLossCommandDisruption(
                    engine, playerGroup, alliedAiGroup, enemyGroup, now);
            if (DivisionOtcCommandManager.isEmergencyModalAutoOpenRequested() && deploymentOrganizer != null) {
                deploymentOrganizer.advance(engine);
            }
        }

        // Immediately intercept newly deployed ship waves on their first frame (even while paused right after closing
        // the deployment dialog) so they form divisions and zoom into battle in formation immediately
        interceptNewlyDeployedWaves(now);

        if (tacticalOverlay != null) {
            tacticalOverlay.pruneLeakedWarroomOrderArrows();
        }
        if (initializedBattle) {
            DivisionCommanderAI.scrubPooledAutoAssignmentsEveryFrame(engine, playerGroup);
            DivisionCommanderAI.scrubPooledAutoAssignmentsEveryFrame(engine, alliedAiGroup);
        }

        if (engine.isPaused()) {
            if (musicController != null && initializedBattle) {
                BattleGroupState musicPlayerView = (side0OpposingView != null && !side0OpposingView.divisions.isEmpty())
                        ? side0OpposingView
                        : playerGroup;
                musicController.advance(engine, musicPlayerView, enemyGroup, amount);
            }
            return;
        }

        divisionTimer += amount;
        otcTimer += amount;
        tsfcTimer += amount;

        if (divisionTimer >= DIVISION_EVAL_INTERVAL) {
            divisionTimer = 0f;
            tsfcTimer = 0f;
            long tickStartNs = System.nanoTime();
            long markSyncNs = 0L, markTelemetryNs = 0L, markViewNs = 0L, markOtcNs = 0L, markDivNs = 0L, markVoidNs = 0L;

            // 1. Partition eligible combat hulls into Player (!isAlly), Allied AI (isAlly), and Enemy (owner == 1)
            List<ShipAPI> playerHulls = new ArrayList<>();
            List<ShipAPI> alliedAiHulls = new ArrayList<>();
            List<ShipAPI> enemyHulls = new ArrayList<>();
            for (ShipAPI s : engine.getShips()) {
                if (!DivisionAutoFormer.isEligibleCombatHull(s)) continue;
                if (s.getOwner() == 0) {
                    if (s.isAlly()) {
                        alliedAiHulls.add(s);
                    } else {
                        playerHulls.add(s);
                    }
                } else if (s.getOwner() == 1) {
                    enemyHulls.add(s);
                }
            }

            // Player fleet ONLY forms divisions from campaign Permanent Divisions (never forcibly auto-assigned)
            List<DivisionState> newPlayerDivs = DivisionAutoFormer.syncPlayerPermanentDivisionsOnly(
                    engine,
                    playerGroup,
                    playerHulls);

            // Allied AI fleet and Enemy fleet auto-form divisions independently
            if (!alliedAiHulls.isEmpty() && !loggedAlliedAiOtcInit) {
                alliedAiGroup.otc = DivisionAutoFormer.resolveAlliedAiOtc(engine, alliedAiHulls);
            }
            List<DivisionState> newAlliedAiDivs = DivisionAutoFormer.syncDivisionsForSide(
                    engine,
                    alliedAiGroup,
                    alliedAiHulls);
            List<DivisionState> newEnemyDivs = DivisionAutoFormer.syncDivisionsForSide(
                    engine,
                    enemyGroup,
                    enemyHulls);

            markSyncNs = System.nanoTime();
            boolean playerHasDivisions = !playerGroup.divisions.isEmpty();
            boolean alliedAiHasDivisions = !alliedAiGroup.divisions.isEmpty();
            boolean enemyHasDivisions = !enemyGroup.divisions.isEmpty();

            DivisionOtcCommandManager.updateConvergenceCommandStates(engine, playerGroup);
            DivisionOtcCommandManager.updateConvergenceCommandStates(engine, alliedAiGroup);
            DivisionOtcCommandManager.updateConvergenceCommandStates(engine, enemyGroup);

            if (playerHasDivisions && !loggedPlayerOtcInit) {
                loggedPlayerOtcInit = true;
                if (DivisionAISettings.isDebugLoggingEnabled()) {
                    LOG.info(String.format(
                            "[DivisionAI] Player Battle Group Commander (OTC) initialized (Mission=%s, Cmdr=%s [%s], Convergence=%b, ActiveDivs=%d)",
                            playerGroup.missionContext.displayName,
                            playerGroup.otc.commanderName,
                            playerGroup.otc.personalityId,
                            playerGroup.useConvergence,
                            playerGroup.divisions.size()));
                }
            }
            if (alliedAiHasDivisions && !loggedAlliedAiOtcInit) {
                loggedAlliedAiOtcInit = true;
                if (DivisionAISettings.isDebugLoggingEnabled()) {
                    LOG.info(String.format(
                            "[DivisionAI] Allied AI Battle Group Commander (OTC) initialized (Mission=%s, Cmdr=%s [%s], Convergence=%b, ActiveDivs=%d)",
                            alliedAiGroup.missionContext.displayName,
                            alliedAiGroup.otc.commanderName,
                            alliedAiGroup.otc.personalityId,
                            alliedAiGroup.useConvergence,
                            alliedAiGroup.divisions.size()));
                }
            }
            if (enemyHasDivisions && !loggedEnemyOtcInit) {
                loggedEnemyOtcInit = true;
                if (DivisionAISettings.isDebugLoggingEnabled()) {
                    LOG.info(String.format(
                            "[DivisionAI] Enemy Battle Group Commander (OTC) initialized (Mission=%s, Cmdr=%s [%s], Convergence=%b, ActiveDivs=%d)",
                            enemyGroup.missionContext.displayName,
                            enemyGroup.otc.commanderName,
                            enemyGroup.otc.personalityId,
                            enemyGroup.useConvergence,
                            enemyGroup.divisions.size()));
                }
            }

            for (DivisionState d : newPlayerDivs) {
                DivisionCombatLog.logDivisionFormed(engine, d, now);
            }
            for (DivisionState d : newAlliedAiDivs) {
                DivisionCombatLog.logDivisionFormed(engine, d, now);
            }
            for (DivisionState d : newEnemyDivs) {
                DivisionCombatLog.logDivisionFormed(engine, d, now);
            }

            // 2. Update telemetry ONLY for active battlegroups
            //    Before mutating division telemetry on the 0.20s C2 tick, snapshot TSFC keyframes so 0.10s interpolation stays continuous.
            BattlefieldFirepowerEvaluator.snapshotGroupDivisionKeyframesBeforeUpdate(
                    engine, now, playerGroup, alliedAiGroup, enemyGroup);
            if (playerHasDivisions) {
                OtcAI.updateTelemetry(engine, playerGroup);
                boolean placedWave = OtcAI.applyInitialFleetFormationDeployment(engine, playerGroup, enemyGroup, now);
                if (placedWave) {
                    OtcAI.updateTelemetry(engine, playerGroup);
                    if (tacticalOverlay != null) tacticalOverlay.invalidateGeometryCache();
                }
            }
            if (alliedAiHasDivisions) {
                OtcAI.updateTelemetry(engine, alliedAiGroup);
                boolean placedWave = OtcAI.applyInitialFleetFormationDeployment(engine, alliedAiGroup, enemyGroup, now);
                if (placedWave) {
                    OtcAI.updateTelemetry(engine, alliedAiGroup);
                    if (tacticalOverlay != null) tacticalOverlay.invalidateGeometryCache();
                }
            }
            if (enemyHasDivisions) {
                OtcAI.updateTelemetry(engine, enemyGroup);
                boolean placedWave = OtcAI.applyInitialFleetFormationDeployment(engine, enemyGroup, playerGroup, now);
                if (placedWave) {
                    OtcAI.updateTelemetry(engine, enemyGroup);
                    if (tacticalOverlay != null) tacticalOverlay.invalidateGeometryCache();
                }
            }
            if (!playerHasDivisions && !alliedAiHasDivisions && !enemyHasDivisions) {
                BattlefieldFirepowerEvaluator.updateBattlefieldRoses(engine, now);
            }
            markTelemetryNs = System.nanoTime();

            // Build passive opposing threat views for both sides so OTC/Division Commanders can always spot, fix,
            // and hunt down independent/unassigned opposing ships (even when all enemy divisions are destroyed or
            // when fighting a 0%-organization fleet)!
            BattleGroupState side0OpposingView = buildSide0OpposingViewForEnemy(playerHulls);
            BattleGroupState side1OpposingView = buildSide1OpposingViewForPlayer(enemyHulls);
            BattlefieldFirepowerEvaluator.setActiveOpposingViews(side1OpposingView, side0OpposingView);
            markViewNs = System.nanoTime();

            // Evaluate cross-group Time/Space/Convergence ONCE for all active battle groups before OTC & Division AI
            // run so every group's TSFC keyframes and battleActiveDivisionCount are 100% consistent on every tick.
            BattlefieldFirepowerEvaluator.evaluateAllGroupsTimeSpaceConvergenceOncePerTick(
                    engine,
                    now,
                    playerGroup,
                    alliedAiGroup,
                    enemyGroup,
                    side1OpposingView,
                    side0OpposingView);

            // 3. Run OTC strategy ONLY for battlegroups that have at least 1 division
            boolean anyNewDivs = !newPlayerDivs.isEmpty() || !newAlliedAiDivs.isEmpty() || !newEnemyDivs.isEmpty();
            if (otcTimer >= OTC_EVAL_INTERVAL || anyNewDivs) {
                otcTimer = 0f;
                if (playerHasDivisions) {
                    OtcAI.evaluateOtcStrategy(engine, playerGroup, side1OpposingView, now);
                }
                if (alliedAiHasDivisions) {
                    OtcAI.evaluateOtcStrategy(engine, alliedAiGroup, side1OpposingView, now);
                }
                if (enemyHasDivisions && !enemyGroup.isPassiveOpposingView) {
                    OtcAI.evaluateOtcStrategy(engine, enemyGroup, side0OpposingView, now);
                }
            }

            markOtcNs = System.nanoTime();
            // 4. Run Division Commander tactical evaluation ONLY for battlegroups that have at least 1 division
            if (playerHasDivisions) {
                DivisionCommanderAI.evaluateAndExecuteDivisions(engine, playerGroup, side1OpposingView, now);
            }
            if (alliedAiHasDivisions) {
                DivisionCommanderAI.evaluateAndExecuteDivisions(engine, alliedAiGroup, side1OpposingView, now);
            }
            if (enemyHasDivisions && !side0OpposingView.divisions.isEmpty()) {
                DivisionCommanderAI.evaluateAndExecuteDivisions(engine, enemyGroup, side0OpposingView, now);
            }
            // Refresh Convergence focal points (if Division Commanders switched targets on their 2.5s cadence)
            // and finalize deterministic division watermarks across all active battle groups.
            BattlefieldFirepowerEvaluator.refreshConvergenceTargetsAndWatermarksAfterC2(
                    engine,
                    now,
                    playerGroup,
                    alliedAiGroup,
                    enemyGroup);

            markDivNs = System.nanoTime();
            // 5. Update active Voidstrike instantiation, multi-division merging, and return cleanup
            VoidstrikeCoordinator.updateVoidstrikes(
                    engine,
                    playerGroup,
                    alliedAiGroup,
                    enemyGroup,
                    now);
            markVoidNs = System.nanoTime();
            long totalMs = (markVoidNs - tickStartNs) / 1000000L;
            if (totalMs >= 5L) {
                LOG.info(String.format(
                        "[DivisionAI] C2 tick %d ms (now=%.2fs): sync=%.1fms telemetry=%.1fms views=%.1fms otc=%.1fms divisions=%.1fms voidstrikes=%.1fms | divs=%d/%d/%d ships=%d roses=%d",
                        totalMs, now,
                        (markSyncNs - tickStartNs) / 1e6f,
                        (markTelemetryNs - markSyncNs) / 1e6f,
                        (markViewNs - markTelemetryNs) / 1e6f,
                        (markOtcNs - markViewNs) / 1e6f,
                        (markDivNs - markOtcNs) / 1e6f,
                        (markVoidNs - markDivNs) / 1e6f,
                        playerGroup.divisions.size(), alliedAiGroup.divisions.size(), enemyGroup.divisions.size(),
                        engine.getShips() != null ? engine.getShips().size() : 0,
                        BattlefieldFirepowerEvaluator.getRoseCount()));
            }
        } else if (tsfcTimer >= TSFC_EVAL_INTERVAL) {
            tsfcTimer = 0f;
            BattlefieldFirepowerEvaluator.updateTsfcKeyframeTick(
                    engine,
                    now,
                    playerGroup,
                    alliedAiGroup,
                    enemyGroup,
                    side1OpposingView,
                    side0OpposingView);
        }

        // Enforce 60 Hz frame-accurate ship system coordination, standoff braking cutoffs, scouting discipline,
        // and terminal Voidstrike strike craft gunnery / AoA fire-gating
        ShipSystemCoordinator.advanceEveryFrame(engine, playerGroup, alliedAiGroup, enemyGroup, now);
        VoidstrikeCoordinator.advanceEveryFrameStrikeCraftGunnery(engine, playerGroup, alliedAiGroup, enemyGroup, now);

        // Ensure every active FighterWing has a non-null leader and non-null leaderFlockingAI so vanilla
        // FighterAI.advance never throws a NullPointerException when a wing leader is killed while returning
        // to carrier (RETURN_TO_CARRIER) and a replacement fighter spawns.
        ensureAllFighterWingsHaveValidLeaders(engine);

        // Advance dynamic engagement-driven battle music every frame
        if (musicController != null) {
            BattleGroupState musicPlayerView = (side0OpposingView != null && !side0OpposingView.divisions.isEmpty())
                    ? side0OpposingView
                    : playerGroup;
            musicController.advance(engine, musicPlayerView, enemyGroup, amount);
        }

        // Maintain HUD status on the player flagship ONLY if the player fleet has at least 1 active division
        maintainPlayerHudStatus();
    }

    private static MethodHandle mhWingSetLeader;
    private static MethodHandle mhWingGetLeaderFlockingAI;
    private static MethodHandle mhWingSetLeaderFlockingAI;
    private static MethodHandle mhFighterAIGetFlockingAI;
    private static boolean mhFighterWingHandlesResolved = false;

    /**
     * Guards against a vanilla Starsector 0.98a crash in {@code com.fs.starfarer.combat.ai.FighterAI.advance}:
     * When a fighter wing has order {@code RETURN_TO_CARRIER} (e.g. {@code carrier.isPullBackFighters() == true})
     * and its previous wing leader dies, vanilla {@code M.selectNewLeader()} skips all returning wingmates and
     * returns {@code null}. If a replacement fighter spawns into that wing, {@code M.getLeaderFlockingAI()} is
     * {@code null} and {@code M.isLeader(ship)} is {@code false} for every member, causing {@code FighterAI.advance}
     * to throw {@code NullPointerException: Cannot invoke "...A.o00000(float)" because the return value of
     * "com.fs.starfarer.combat.ai.M.getLeaderFlockingAI()" is null}.
     */
    public static void ensureAllFighterWingsHaveValidLeaders(CombatEngineAPI engine) {
        if (engine == null) return;
        List<ShipAPI> ships = engine.getShips();
        if (ships == null || ships.isEmpty()) return;
        for (int i = 0, n = ships.size(); i < n; i++) {
            ShipAPI s = ships.get(i);
            if (s == null || !s.isAlive()) continue;
            if (s.isFighter()) {
                com.fs.starfarer.api.combat.FighterWingAPI wing = s.getWing();
                if (wing != null) {
                    ensureFighterWingLeaderAndFlockingAI(wing);
                }
            } else if (s.hasLaunchBays()) {
                List<com.fs.starfarer.api.combat.FighterWingAPI> wings = s.getAllWings();
                if (wings != null) {
                    for (int w = 0, wn = wings.size(); w < wn; w++) {
                        com.fs.starfarer.api.combat.FighterWingAPI wing = wings.get(w);
                        if (wing != null) {
                            ensureFighterWingLeaderAndFlockingAI(wing);
                        }
                    }
                }
            }
        }
    }

    public static ShipAPI ensureFighterWingLeaderAndFlockingAI(com.fs.starfarer.api.combat.FighterWingAPI wing) {
        if (wing == null) return null;
        List<ShipAPI> members = wing.getWingMembers();
        if (members == null || members.isEmpty()) return null;

        ShipAPI leader = wing.getLeader();
        if (leader == null || !leader.isAlive() || leader.isHulk()) {
            ShipAPI fallback = null;
            for (int i = 0, n = members.size(); i < n; i++) {
                ShipAPI m = members.get(i);
                if (m != null && m.isAlive() && !m.isHulk()) {
                    fallback = m;
                    break;
                }
            }
            if (fallback == null) {
                return null;
            }
            leader = fallback;
            resolveFighterWingHandlesIfNeeded(wing, leader);
            if (mhWingSetLeader != null) {
                try {
                    mhWingSetLeader.invoke(wing, leader);
                } catch (Throwable ignored) {
                }
            }
        }

        resolveFighterWingHandlesIfNeeded(wing, leader);
        if (mhWingGetLeaderFlockingAI != null && mhWingSetLeaderFlockingAI != null && mhFighterAIGetFlockingAI != null) {
            try {
                Object leaderFlocking = mhWingGetLeaderFlockingAI.invoke(wing);
                if (leaderFlocking == null) {
                    for (int i = -1, n = members.size(); i < n; i++) {
                        ShipAPI candidate = (i < 0) ? leader : members.get(i);
                        if (candidate == null || !candidate.isAlive()) continue;
                        Object shipAi = candidate.getShipAI();
                        if (shipAi != null && "com.fs.starfarer.combat.ai.FighterAI".equals(shipAi.getClass().getName())) {
                            Object flockAi = mhFighterAIGetFlockingAI.invoke(shipAi);
                            if (flockAi != null) {
                                mhWingSetLeaderFlockingAI.invoke(wing, flockAi);
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return leader;
    }

    private static void resolveFighterWingHandlesIfNeeded(
            com.fs.starfarer.api.combat.FighterWingAPI wing,
            ShipAPI sampleFighter) {
        if (mhFighterWingHandlesResolved || wing == null || sampleFighter == null) return;
        String wingClassName = wing.getClass().getName();
        if (!wingClassName.startsWith("com.fs.starfarer.combat.")) return;
        mhFighterWingHandlesResolved = true;
        try {
            ClassLoader cl = wing.getClass().getClassLoader();
            Class<?> wingClass = wing.getClass();
            Class<?> shipImplClass = ObfuscatedBytecodeBridge.resolveMethodReturnClass(
                    cl, wingClassName, "getLeader", "()");
            if (shipImplClass == null) {
                shipImplClass = Class.forName("com.fs.starfarer.combat.entities.Ship", false, cl);
            }
            Class<?> flockingAiClass = ObfuscatedBytecodeBridge.resolveMethodReturnClass(
                    cl, wingClassName, "getLeaderFlockingAI", "()");
            Class<?> fighterAiClass = Class.forName("com.fs.starfarer.combat.ai.FighterAI", false, cl);
            if (flockingAiClass == null) {
                flockingAiClass = ObfuscatedBytecodeBridge.resolveMethodReturnClass(
                        cl, fighterAiClass.getName(), "getFlockingAI", "()");
            }
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            if (shipImplClass != null) {
                mhWingSetLeader = lookup.findVirtual(
                        wingClass, "setLeader", MethodType.methodType(void.class, shipImplClass));
            }
            if (flockingAiClass != null) {
                mhWingGetLeaderFlockingAI = lookup.findVirtual(
                        wingClass, "getLeaderFlockingAI", MethodType.methodType(flockingAiClass));
                mhWingSetLeaderFlockingAI = lookup.findVirtual(
                        wingClass, "setLeaderFlockingAI", MethodType.methodType(void.class, flockingAiClass));
                mhFighterAIGetFlockingAI = lookup.findVirtual(
                        fighterAiClass, "getFlockingAI", MethodType.methodType(flockingAiClass));
            }
        } catch (Throwable ignored) {
        }
    }

    private static MethodHandle mhExecuteAdds;
    private static boolean mhExecuteAddsResolved = false;
    private static CombatEngineAPI lastFlushAddsEngine = null;
    private static float lastFlushAddsSimTime = -100f;

    private static void flushPendingEngineAdds(CombatEngineAPI engine) {
        if (engine == null) return;
        float now = engine.getTotalElapsedTime(false);
        boolean paused = engine.isPaused();
        if (engine == lastFlushAddsEngine && !paused && now > 5.0f && Math.abs(now - lastFlushAddsSimTime) < 0.25f) {
            return;
        }
        lastFlushAddsEngine = engine;
        lastFlushAddsSimTime = now;
        try {
            boolean hasUnaddedDeployed = false;
            for (int side = 0; side <= 1; side++) {
                CombatFleetManagerAPI fm = engine.getFleetManager(side);
                if (fm == null) continue;
                List<com.fs.starfarer.api.combat.DeployedFleetMemberAPI> deployedList = fm.getDeployedCopyDFM();
                if (deployedList == null || deployedList.isEmpty()) continue;
                for (int i = 0, n = deployedList.size(); i < n; i++) {
                    com.fs.starfarer.api.combat.DeployedFleetMemberAPI dfm = deployedList.get(i);
                    if (dfm == null || dfm.isFighterWing()) continue;
                    ShipAPI s = dfm.getShip();
                    if (s != null && !s.isAlive() && !s.isHulk() && !s.isExpired() && s.getFullTimeDeployed() <= 5.0f) {
                        hasUnaddedDeployed = true;
                        break;
                    }
                }
            }
            if (!hasUnaddedDeployed) return;
            if (!mhExecuteAddsResolved) {
                mhExecuteAddsResolved = true;
                try {
                    mhExecuteAdds = MethodHandles.lookup().findVirtual(
                            engine.getClass(), "executeAdds", MethodType.methodType(void.class));
                } catch (Throwable t) {
                    if (engine.getClass().getName().startsWith("com.fs.starfarer.combat.")) {
                        LOG.warn("[DivisionAI] Obfuscated CombatEngine.executeAdds MethodHandle resolution failed: " + t);
                    }
                }
            }
            if (mhExecuteAdds != null) {
                mhExecuteAdds.invoke(engine);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Detects any newly deployed combat ships on their first frame (including while paused right after clicking Deploy)
     * and invokes {@link OtcAI#hijackNewlyDeployedWaveIntoFormation} so fleets and divisions enter the battlefield
     * under travel drive already formed up in their Fleet-Wide and Intra-Division formations.
     */
    private void interceptNewlyDeployedWaves(float now) {
        if (engine == null || engine.getShips() == null) return;
        flushPendingEngineAdds(engine);
        List<ShipAPI> newPlayerWave = null;
        List<ShipAPI> newAlliedWave = null;
        List<ShipAPI> newEnemyWave = null;

        for (ShipAPI s : engine.getShips()) {
            if (!DivisionAutoFormer.isEligibleCombatHull(s)) continue;
            String id = s.getId();
            if (id == null) continue;
            // Vanilla CombatFleetManager.new pre-advances newly instantiated ships 10 x 0.2s = 2.0s at creation!
            if (s.getFullTimeDeployed() > 5.0f) continue;
            if (s.getOwner() == 0) {
                if (s.isAlly()) {
                    if (alliedAiGroup != null && !alliedAiGroup.formationSpawnHijackedShipIds.contains(id)) {
                        if (newAlliedWave == null) newAlliedWave = new ArrayList<>();
                        newAlliedWave.add(s);
                    }
                } else {
                    if (playerGroup != null && !playerGroup.formationSpawnHijackedShipIds.contains(id)) {
                        if (newPlayerWave == null) newPlayerWave = new ArrayList<>();
                        newPlayerWave.add(s);
                    }
                }
            } else if (s.getOwner() == 1) {
                if (enemyGroup != null && !enemyGroup.formationSpawnHijackedShipIds.contains(id)) {
                    if (newEnemyWave == null) newEnemyWave = new ArrayList<>();
                    newEnemyWave.add(s);
                }
            }
        }

        if (newPlayerWave == null && newAlliedWave == null && newEnemyWave == null) {
            return;
        }
        if (tacticalOverlay != null) {
            tacticalOverlay.invalidateGeometryCache();
        }

        List<ShipAPI> playerHulls = new ArrayList<>();
        List<ShipAPI> alliedAiHulls = new ArrayList<>();
        List<ShipAPI> enemyHulls = new ArrayList<>();
        for (ShipAPI s : engine.getShips()) {
            if (!DivisionAutoFormer.isEligibleCombatHull(s)) continue;
            if (s.getOwner() == 0) {
                if (s.isAlly()) alliedAiHulls.add(s);
                else playerHulls.add(s);
            } else if (s.getOwner() == 1) {
                enemyHulls.add(s);
            }
        }

        BattlefieldFirepowerEvaluator.snapshotGroupDivisionKeyframesBeforeUpdate(
                engine, now, playerGroup, alliedAiGroup, enemyGroup);

        if (newPlayerWave != null && playerGroup != null) {
            PermanentDivisionManager.FleetDoctrineConfig doctrine = PermanentDivisionManager.getFleetDoctrine();
            if (doctrine != null) {
                playerGroup.authority = doctrine.getOtcAuthority();
                playerGroup.fleetWideFormation = doctrine.getFleetWideFormation();
                playerGroup.multiDivisionSetpieceDoctrine = doctrine.getMultiDivisionSetpiece();
                playerGroup.deployInFleetFormation = doctrine.isDeployInFleetFormation();
                playerGroup.orchestratedAdvance = doctrine.isOrchestratedAdvance();
            }
            List<DivisionState> formed = DivisionAutoFormer.syncPlayerPermanentDivisionsOnly(engine, playerGroup, playerHulls);
            if (!playerGroup.divisions.isEmpty()) {
                OtcAI.updateTelemetry(engine, playerGroup);
                OtcAI.synchronizeGrandFormationConsent(engine, playerGroup, enemyGroup, now);
                OtcAI.hijackNewlyDeployedWaveIntoFormation(engine, playerGroup, enemyGroup, newPlayerWave, now);
                OtcAI.updateTelemetry(engine, playerGroup);
                otcTimer = OTC_EVAL_INTERVAL;
            } else {
                // Divisions were just synced; ships with no division stay on vanilla AI
                for (ShipAPI s : newPlayerWave) {
                    if (s != null && s.getId() != null) playerGroup.formationSpawnHijackedShipIds.add(s.getId());
                }
            }
            for (DivisionState d : formed) {
                DivisionCombatLog.logDivisionFormed(engine, d, now);
            }
        }

        if (newAlliedWave != null && alliedAiGroup != null) {
            if (!alliedAiHulls.isEmpty() && !loggedAlliedAiOtcInit) {
                alliedAiGroup.otc = DivisionAutoFormer.resolveAlliedAiOtc(engine, alliedAiHulls);
            }
            List<DivisionState> formed = DivisionAutoFormer.syncDivisionsForSide(engine, alliedAiGroup, alliedAiHulls);
            if (!alliedAiGroup.divisions.isEmpty()) {
                OtcAI.updateTelemetry(engine, alliedAiGroup);
                OtcAI.synchronizeGrandFormationConsent(engine, alliedAiGroup, enemyGroup, now);
                OtcAI.hijackNewlyDeployedWaveIntoFormation(engine, alliedAiGroup, enemyGroup, newAlliedWave, now);
                OtcAI.updateTelemetry(engine, alliedAiGroup);
                otcTimer = OTC_EVAL_INTERVAL;
            } else {
                // Divisions were just synced; ships with no division stay on vanilla AI
                for (ShipAPI s : newAlliedWave) {
                    if (s != null && s.getId() != null) alliedAiGroup.formationSpawnHijackedShipIds.add(s.getId());
                }
            }
            for (DivisionState d : formed) {
                DivisionCombatLog.logDivisionFormed(engine, d, now);
            }
        }

        if (newEnemyWave != null && enemyGroup != null) {
            List<DivisionState> formed = DivisionAutoFormer.syncDivisionsForSide(engine, enemyGroup, enemyHulls);
            if (!enemyGroup.divisions.isEmpty()) {
                OtcAI.updateTelemetry(engine, enemyGroup);
                OtcAI.synchronizeGrandFormationConsent(engine, enemyGroup, playerGroup, now);
                OtcAI.hijackNewlyDeployedWaveIntoFormation(engine, enemyGroup, playerGroup, newEnemyWave, now);
                OtcAI.updateTelemetry(engine, enemyGroup);
                otcTimer = OTC_EVAL_INTERVAL;
            } else {
                // Divisions were just synced; ships with no division stay on vanilla AI
                for (ShipAPI s : newEnemyWave) {
                    if (s != null && s.getId() != null) enemyGroup.formationSpawnHijackedShipIds.add(s.getId());
                }
            }
            for (DivisionState d : formed) {
                DivisionCombatLog.logDivisionFormed(engine, d, now);
            }
        }
        // Always ensure newly deployed reinforcement ships get their Firepower Rose initialized immediately
        // even if no side currently has a multi-ship division, and keep cross-group TSFC pinched & watermarked.
        BattlefieldFirepowerEvaluator.updateBattlefieldRoses(engine, now);
        BattleGroupState s0View = buildSide0OpposingViewForEnemy(playerHulls);
        BattleGroupState s1View = buildSide1OpposingViewForPlayer(enemyHulls);
        BattlefieldFirepowerEvaluator.setActiveOpposingViews(s1View, s0View);
        BattlefieldFirepowerEvaluator.evaluateAllGroupsTimeSpaceConvergenceOncePerTick(
                engine, now, playerGroup, alliedAiGroup, enemyGroup, s1View, s0View);
    }

    /**
     * Constructs a passive read-only opposing BattleGroupState for enemyGroup evaluation containing:
     *  - All active divisions in playerGroup
     *  - All active divisions in alliedAiGroup
     *  - A passive contact-tracking group for any unassigned player ships (never issued orders, never rendered)
     */
    private BattleGroupState buildSide0OpposingViewForEnemy(List<ShipAPI> playerHulls) {
        if (side0OpposingView == null) {
            side0OpposingView = new BattleGroupState(0, playerGroup.otc);
            side0OpposingView.isPassiveOpposingView = true;
        }
        BattleGroupState view = side0OpposingView;
        view.isPassiveOpposingView = true;
        view.otc = playerGroup.otc;
        view.missionContext = playerGroup.missionContext;
        view.divisions.clear();
        view.divisions.addAll(playerGroup.divisions);
        view.divisions.addAll(alliedAiGroup.divisions);

        List<ShipAPI> unassignedPlayerHulls = new ArrayList<>();
        for (ShipAPI s : playerHulls) {
            if (s != null && s.isAlive() && !s.isHulk()
                    && !playerGroup.shipIdToDivision.containsKey(s.getId())
                    && OtcAI.isShipVisibleToSide(engine, 1, s)) {
                unassignedPlayerHulls.add(s);
            }
        }

        if (!unassignedPlayerHulls.isEmpty()) {
            populateSpatialContactClusters(
                    unassignedPlayerHulls,
                    passiveContactGroups,
                    view,
                    0,
                    "unassigned_player_contacts_",
                    "Player Unassigned ");
        }

        if (!view.divisions.isEmpty()) {
            OtcAI.updateTelemetry(engine, view);
        }
        return view;
    }

    /**
     * Constructs a passive read-only opposing BattleGroupState for playerGroup & alliedAiGroup evaluation containing:
     *  - All active divisions in enemyGroup
     *  - A passive contact-tracking group for any independent/unassigned enemy ships (never issued orders, never rendered)
     */
    private BattleGroupState buildSide1OpposingViewForPlayer(List<ShipAPI> enemyHulls) {
        if (side1OpposingView == null) {
            side1OpposingView = new BattleGroupState(1, enemyGroup.otc);
            side1OpposingView.isPassiveOpposingView = true;
        }
        BattleGroupState view = side1OpposingView;
        view.isPassiveOpposingView = true;
        view.otc = enemyGroup.otc;
        view.missionContext = enemyGroup.missionContext;
        view.divisions.clear();
        view.shipIdToDivision.clear();
        view.divisions.addAll(enemyGroup.divisions);
        view.shipIdToDivision.putAll(enemyGroup.shipIdToDivision);

        List<ShipAPI> unassignedEnemyHulls = new ArrayList<>();
        for (ShipAPI s : enemyHulls) {
            if (s != null && s.isAlive() && !s.isHulk()
                    && !enemyGroup.shipIdToDivision.containsKey(s.getId())
                    && OtcAI.isShipVisibleToSide(engine, 0, s)) {
                unassignedEnemyHulls.add(s);
            }
        }

        if (!unassignedEnemyHulls.isEmpty()) {
            populateSpatialContactClusters(
                    unassignedEnemyHulls,
                    passiveEnemyContactGroups,
                    view,
                    1,
                    "unassigned_enemy_contacts_",
                    "Independent Contact ");
        }

        if (!view.divisions.isEmpty()) {
            OtcAI.updateTelemetry(engine, view);
        }
        return view;
    }

    /**
     * Groups unassigned/independent opposing ships into spatial contact clusters (up to 5 ships within 1200u)
     * so OTC and Division Commanders perceive realistic local threat centroids and EHP rather than a single
     * map-wide 20-ship mega-division.
     */
    public static void populateSpatialContactClusters(
            List<ShipAPI> unassignedHulls,
            List<DivisionState> pool,
            BattleGroupState view,
            int side,
            String idPrefix,
            String namePrefix) {
        if (pool != null && view != null && view.divisions != null) {
            view.divisions.removeAll(pool);
        }
        if (unassignedHulls == null || unassignedHulls.isEmpty()) return;
        List<ShipAPI> remaining = new ArrayList<>(unassignedHulls);
        List<List<ShipAPI>> builtClusters = new ArrayList<>();
        List<DivisionArchetype> builtArchs = new ArrayList<>();

        while (!remaining.isEmpty()) {
            // Seed cluster with the heaviest remaining ship
            int bestSeedIdx = 0;
            float bestFp = -1f;
            for (int i = 0; i < remaining.size(); i++) {
                ShipAPI s = remaining.get(i);
                float fp = ShipCapabilityProfile.resolveShipFpStrength(s);
                if (fp > bestFp) {
                    bestFp = fp;
                    bestSeedIdx = i;
                }
            }
            ShipAPI lead = remaining.remove(bestSeedIdx);
            List<ShipAPI> cluster = new ArrayList<>();
            cluster.add(lead);

            // Greedily add up to 4 nearest unassigned ships within 1200u of the seed ship
            while (cluster.size() < 5 && !remaining.isEmpty()) {
                int nearestIdx = -1;
                float nearestDist = 1200f;
                for (int i = 0; i < remaining.size(); i++) {
                    ShipAPI cand = remaining.get(i);
                    float dx = cand.getLocation().x - lead.getLocation().x;
                    float dy = cand.getLocation().y - lead.getLocation().y;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    if (d <= nearestDist) {
                        nearestDist = d;
                        nearestIdx = i;
                    }
                }
                if (nearestIdx < 0) break;
                cluster.add(remaining.remove(nearestIdx));
            }

            DivisionArchetype contactArch = DivisionArchetype.FRIGATE_DIVISION;
            if (lead.getHullSize() != null) {
                switch (lead.getHullSize()) {
                    case CAPITAL_SHIP:
                        contactArch = DivisionArchetype.BATTLESHIP_DIVISION;
                        break;
                    case CRUISER:
                        contactArch = DivisionArchetype.CRUISER_DIVISION;
                        break;
                    case DESTROYER:
                        contactArch = DivisionArchetype.DESTROYER_DIVISION;
                        break;
                    default:
                        contactArch = DivisionArchetype.FRIGATE_DIVISION;
                        break;
                }
            }
            builtClusters.add(cluster);
            builtArchs.add(contactArch);
        }

        int numClusters = builtClusters.size();
        DivisionState[] assignedDivs = new DivisionState[numClusters];
        java.util.Set<DivisionState> claimedPoolSlots = new java.util.HashSet<>();

        // Pass 1: Exact lead ship ID match against existing pool entries
        for (int cIdx = 0; cIdx < numClusters; cIdx++) {
            ShipAPI lead = builtClusters.get(cIdx).get(0);
            if (lead.getId() == null) continue;
            for (DivisionState existing : pool) {
                if (existing != null
                        && !claimedPoolSlots.contains(existing)
                        && !view.divisions.contains(existing)
                        && existing.flagship != null
                        && lead.getId().equals(existing.flagship.getId())) {
                    assignedDivs[cIdx] = existing;
                    claimedPoolSlots.add(existing);
                    break;
                }
            }
        }

        // Pass 2: Member overlap match (e.g., when a heavier ship joins a cluster and becomes the new seed,
        // or when the previous flagship died/left LoS while its wingmen remain in the cluster)
        for (int cIdx = 0; cIdx < numClusters; cIdx++) {
            if (assignedDivs[cIdx] != null) continue;
            List<ShipAPI> cluster = builtClusters.get(cIdx);
            DivisionState bestMatch = null;
            int bestScore = 0;
            for (DivisionState existing : pool) {
                if (existing == null || claimedPoolSlots.contains(existing) || view.divisions.contains(existing)) {
                    continue;
                }
                int score = 0;
                if (existing.flagship != null && cluster.contains(existing.flagship)) {
                    score += 100;
                }
                for (ShipAPI s : cluster) {
                    if (existing.members.contains(s)) {
                        score += 10;
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = existing;
                }
            }
            if (bestMatch != null) {
                assignedDivs[cIdx] = bestMatch;
                claimedPoolSlots.add(bestMatch);
            }
        }

        // Pass 3: Reuse any remaining unclaimed pool slot (prevents pool slot leaks when clusters merge or die),
        // allocating a new slot ONLY when simultaneous active clusters exceed pool.size()
        for (int cIdx = 0; cIdx < numClusters; cIdx++) {
            if (assignedDivs[cIdx] == null) {
                for (DivisionState existing : pool) {
                    if (existing != null && !claimedPoolSlots.contains(existing) && !view.divisions.contains(existing)) {
                        assignedDivs[cIdx] = existing;
                        claimedPoolSlots.add(existing);
                        break;
                    }
                }
            }
            if (assignedDivs[cIdx] == null) {
                ShipAPI lead = builtClusters.get(cIdx).get(0);
                DivisionArchetype contactArch = builtArchs.get(cIdx);
                int num = pool.size() + 1;
                CommanderProfile dummyCmdr = new CommanderProfile(lead.getCaptain(), namePrefix + num);
                DivisionState created = new DivisionState(
                        idPrefix + num,
                        side,
                        90 + num,
                        contactArch,
                        namePrefix + num,
                        dummyCmdr);
                pool.add(created);
                assignedDivs[cIdx] = created;
                claimedPoolSlots.add(created);
            }
        }

        for (int cIdx = 0; cIdx < numClusters; cIdx++) {
            List<ShipAPI> cluster = builtClusters.get(cIdx);
            ShipAPI lead = cluster.get(0);
            DivisionArchetype contactArch = builtArchs.get(cIdx);
            DivisionState contactDiv = assignedDivs[cIdx];
            contactDiv.archetype = contactArch;
            contactDiv.flagship = lead;
            contactDiv.members.clear();
            contactDiv.members.addAll(cluster);
            DivisionAutoFormer.recomputeDivisionCapabilities(contactDiv);
            for (ShipAPI s : cluster) {
                view.shipIdToDivision.put(s.getId(), contactDiv);
            }
            view.divisions.add(contactDiv);
        }
    }



    private void maintainPlayerHudStatus() {
        if (playerGroup == null || playerGroup.divisions.isEmpty()) {
            return;
        }
        ShipAPI playerShip = engine.getPlayerShip();
        if (playerShip == null || !playerShip.isAlive()) return;

        DivisionState playerDiv = playerGroup.shipIdToDivision.get(playerShip.getId());
        if (playerDiv != null) {
            String targetStr = "";
            if (playerDiv.targetEnemyDivision != null) {
                targetStr = " -> " + playerDiv.targetEnemyDivision.displayName;
            } else if (playerDiv.targetFriendlyDivision != null) {
                targetStr = " -> " + playerDiv.targetFriendlyDivision.displayName;
            }
            engine.maintainStatusForPlayerShip(
                    HUD_STATUS_KEY_DIV,
                    "graphics/icons/hullsys/targeting_feed.png",
                    playerDiv.displayName,
                    playerDiv.currentIntent.verbPhrase + targetStr,
                    false);
        }

        float now = engine.getTotalElapsedTime(false);
        if (DivisionOtcCommandManager.isOtcDisabled(playerGroup, now)) {
            float rem = DivisionOtcCommandManager.getOtcDisabledSecondsRemaining(playerGroup, now);
            engine.maintainStatusForPlayerShip(
                    HUD_STATUS_KEY_OTC,
                    "graphics/icons/hullsys/reserve_deployment.png",
                    "OTC DISRUPTED (FLAGSHIP LOST)",
                    String.format("Command channel restoring in %.0fs", rem),
                    true);
        } else {
            float safeRatio = (Float.isNaN(playerGroup.forceRatio) || Float.isInfinite(playerGroup.forceRatio))
                    ? 1.0f
                    : playerGroup.forceRatio;
            float safeEngPct = (Float.isNaN(playerGroup.smoothedEngagementLevel) || Float.isInfinite(playerGroup.smoothedEngagementLevel))
                    ? 0.0f
                    : (playerGroup.smoothedEngagementLevel * 100f);
            engine.maintainStatusForPlayerShip(
                    HUD_STATUS_KEY_OTC,
                    "graphics/icons/hullsys/reserve_deployment.png",
                    "OTC Phase: " + playerGroup.currentPhase.displayName,
                    String.format("Force Ratio %.2fx | Engaged %.0f%% | %d Divs",
                            safeRatio,
                            safeEngPct,
                            playerGroup.divisions.size()),
                    false);
        }
    }

    /**
     * Logs a structured one-line end-of-battle summary to starsector.log when combat concludes.
     */
    public static void logBattleSummary(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            BattleGroupState enemyGroup) {
        if (engine == null) return;
        try {
            float duration = engine.getTotalElapsedTime(false);
            int winSide = -1;
            try {
                winSide = engine.getWinningSideId();
            } catch (Throwable ignored) {
            }
            float pDeployedDp = sumFleetMemberDp(engine, 0, "deployed");
            float pDestroyedDp = sumFleetMemberDp(engine, 0, "destroyed");
            float pRetreatedDp = sumFleetMemberDp(engine, 0, "retreated");
            float eDeployedDp = sumFleetMemberDp(engine, 1, "deployed");
            float eDestroyedDp = sumFleetMemberDp(engine, 1, "destroyed");
            float eRetreatedDp = sumFleetMemberDp(engine, 1, "retreated");

            String outcome = (winSide == 0)
                    ? "PLAYER_VICTORY"
                    : (winSide == 1 ? "ENEMY_VICTORY" : (eDestroyedDp > pDestroyedDp ? "PLAYER_ADVANTAGE" : "DISENGAGE_OR_DRAW"));
            String pAuth = (playerGroup != null && playerGroup.authority != null) ? playerGroup.authority.shortName : "Vanilla";
            boolean pConv = (playerGroup != null && playerGroup.useConvergence);
            String pForm = (playerGroup != null && playerGroup.activeFleetWideFormation != null)
                    ? playerGroup.activeFleetWideFormation.displayName
                    : "None";
            int pDivs = (playerGroup != null) ? playerGroup.divisions.size() : 0;
            int eDivs = (enemyGroup != null) ? enemyGroup.divisions.size() : 0;
            String eFaction = (enemyGroup != null && enemyGroup.factionId != null) ? enemyGroup.factionId : "unknown";

            String line = String.format(
                    java.util.Locale.ROOT,
                    "[DivisionAI] BATTLE SUMMARY: outcome=%s duration=%.1fs | Player(divs=%d, auth=%s, conv=%b, form=%s, survDP=%.0f, lostDP=%.0f, retDP=%.0f) vs Enemy(faction=%s, divs=%d, survDP=%.0f, lostDP=%.0f, retDP=%.0f)",
                    outcome,
                    duration,
                    pDivs, pAuth, pConv, pForm, pDeployedDp, pDestroyedDp, pRetreatedDp,
                    eFaction, eDivs, eDeployedDp, eDestroyedDp, eRetreatedDp);
            if (DivisionAISettings.isDebugLoggingEnabled()) {
                LOG.info(line);
                if (DivisionCombatLog.simLogSink != null) {
                    DivisionCombatLog.simLogSink.add(line);
                }
            }
        } catch (Throwable t) {
            LOG.warn("[DivisionAI] Failed to emit battle summary: " + t);
        }
    }

    private static float sumFleetMemberDp(CombatEngineAPI engine, int side, String bucket) {
        if (engine == null) return 0f;
        try {
            CombatFleetManagerAPI fm = engine.getFleetManager(side);
            if (fm == null) return 0f;
            List<com.fs.starfarer.api.fleet.FleetMemberAPI> list = new java.util.ArrayList<>();
            if ("deployed".equals(bucket)) {
                if (fm.getDeployedCopy() != null) list.addAll(fm.getDeployedCopy());
            } else if ("destroyed".equals(bucket)) {
                if (fm.getDestroyedCopy() != null) list.addAll(fm.getDestroyedCopy());
                if (fm.getDisabledCopy() != null) {
                    for (com.fs.starfarer.api.fleet.FleetMemberAPI dm : fm.getDisabledCopy()) {
                        if (dm != null && !list.contains(dm)) {
                            list.add(dm);
                        }
                    }
                }
            } else if ("retreated".equals(bucket)) {
                if (fm.getRetreatedCopy() != null) list.addAll(fm.getRetreatedCopy());
            }
            float sum = 0f;
            for (com.fs.starfarer.api.fleet.FleetMemberAPI m : list) {
                if (m != null && !m.isFighterWing()) {
                    sum += m.getDeploymentPointsCost();
                }
            }
            return sum;
        } catch (Throwable ignored) {
            return 0f;
        }
    }
}
