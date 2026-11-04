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
    private boolean initializedBattle = false;
    private int lastConvStateSyncShipsCount = -1;
    private boolean loggedPlayerOtcInit = false;
    private boolean loggedAlliedAiOtcInit = false;
    private boolean loggedEnemyOtcInit = false;
    private boolean preRenderHookAttached = false;
    private CombatEngineAPI preRenderHookEngine = null;
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
        try {
            if (!BattlefieldFirepowerEvaluator.isRealCombatEngine(engine)) return;
        } catch (Throwable ignored) {
            return;
        }
        final DivisionAICombatPlugin owner = this;
        final CombatEngineAPI hookEngine = engine;
        engine.addLayeredRenderingPlugin(new BaseCombatLayeredRenderingPlugin(CombatEngineLayers.BELOW_PLANETS) {
            @Override
            public EnumSet<CombatEngineLayers> getActiveLayers() {
                return EnumSet.of(CombatEngineLayers.BELOW_PLANETS);
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
                owner.interceptBeforeRender();
            }
        });
        preRenderHookAttached = true;
        preRenderHookEngine = engine;
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
        // Fallback only in case the layered hook could not be attached
        if (!preRenderHookAttached) {
            interceptBeforeRender();
        }
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (!BattlefieldFirepowerEvaluator.isCommandViewWindowActive(engine)) return;
        TsfcWarroomRenderer.processInputEvents(engine, events);
    }

    public static final float BATTLE_SCREEN_TSFC_OPACITY_MULT = 0.22f;

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        // Render TSFC (Time, Space, Firepower, Convergence) in the live Battle Screen (outside the Warroom)
        // at vastly reduced opacity so a player manually piloting their ship can still read TSFC geometry.
        renderBattleScreenTsfcOverlay(viewport);
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
        float cmdOpacity = (ui != null && ui.isShowingCommandUI()) ? ui.getCommandUIOpacity() : 0f;
        if (cmdOpacity >= 0.95f) {
            return;
        }
        float battleFade = BATTLE_SCREEN_TSFC_OPACITY_MULT * Math.max(0f, 1f - cmdOpacity);
        if (battleFade <= 0.04f) {
            return;
        }
        float offsetX = viewport.convertWorldXtoScreenX(0f);
        float offsetY = viewport.convertWorldYtoScreenY(0f);
        float scale = (viewport.convertWorldXtoScreenX(1000f) - offsetX) / 1000f;
        if (scale <= 1e-5f) {
            return;
        }
        TsfcWarroomRenderer.renderTimeSpaceAndConvergenceScreenSpace(
                engine, scale, offsetX, offsetY, battleFade, playerGroup, alliedAiGroup, enemyGroup);
        TsfcWarroomRenderer.renderFirepowerRosesScreenSpace(
                engine, scale, offsetX, offsetY, battleFade, playerGroup, alliedAiGroup, enemyGroup);
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

        if (divisionTimer >= DIVISION_EVAL_INTERVAL) {
            divisionTimer = 0f;
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
            markTelemetryNs = System.nanoTime();

            // Build passive opposing threat views for both sides so OTC/Division Commanders can always spot, fix,
            // and hunt down independent/unassigned opposing ships (even when all enemy divisions are destroyed or
            // when fighting a 0%-organization fleet)!
            BattleGroupState side0OpposingView = buildSide0OpposingViewForEnemy(playerHulls);
            BattleGroupState side1OpposingView = buildSide1OpposingViewForPlayer(enemyHulls);
            markViewNs = System.nanoTime();

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
        }

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
            for (DivisionState d : formed) {
                DivisionCombatLog.logDivisionFormed(engine, d, now);
            }
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
        }

        if (newAlliedWave != null && alliedAiGroup != null) {
            if (!alliedAiHulls.isEmpty() && !loggedAlliedAiOtcInit) {
                alliedAiGroup.otc = DivisionAutoFormer.resolveAlliedAiOtc(engine, alliedAiHulls);
            }
            List<DivisionState> formed = DivisionAutoFormer.syncDivisionsForSide(engine, alliedAiGroup, alliedAiHulls);
            for (DivisionState d : formed) {
                DivisionCombatLog.logDivisionFormed(engine, d, now);
            }
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
        }

        if (newEnemyWave != null && enemyGroup != null) {
            List<DivisionState> formed = DivisionAutoFormer.syncDivisionsForSide(engine, enemyGroup, enemyHulls);
            for (DivisionState d : formed) {
                DivisionCombatLog.logDivisionFormed(engine, d, now);
            }
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
        }
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
        if (unassignedHulls == null || unassignedHulls.isEmpty()) return;
        List<ShipAPI> remaining = new ArrayList<>(unassignedHulls);
        int clusterIdx = 0;
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

            while (pool.size() <= clusterIdx) {
                int num = pool.size() + 1;
                CommanderProfile dummyCmdr = new CommanderProfile(lead.getCaptain(), namePrefix + num);
                pool.add(new DivisionState(
                        idPrefix + num,
                        side,
                        90 + num,
                        DivisionArchetype.CRUISER_DIVISION,
                        namePrefix + num,
                        dummyCmdr));
            }
            DivisionState contactDiv = pool.get(clusterIdx++);
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
            engine.maintainStatusForPlayerShip(
                    HUD_STATUS_KEY_OTC,
                    "graphics/icons/hullsys/reserve_deployment.png",
                    "OTC Phase: " + playerGroup.currentPhase.displayName,
                    String.format("Force Ratio %.2fx | Engaged %.0f%% | %d Divs",
                            playerGroup.forceRatio,
                            playerGroup.smoothedEngagementLevel * 100f,
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
            List<com.fs.starfarer.api.fleet.FleetMemberAPI> list = null;
            if ("deployed".equals(bucket)) list = fm.getDeployedCopy();
            else if ("destroyed".equals(bucket)) list = fm.getDestroyedCopy();
            else if ("retreated".equals(bucket)) list = fm.getRetreatedCopy();
            if (list == null) return 0f;
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
