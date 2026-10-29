package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import org.apache.log4j.Logger;

import java.util.ArrayList;
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
    private DivisionState passiveContactGroup;
    private BattleGroupState side0OpposingView;

    private float divisionTimer = 0f;
    private float otcTimer = 0f;
    private boolean initializedBattle = false;
    private boolean loggedPlayerOtcInit = false;
    private boolean loggedAlliedAiOtcInit = false;
    private boolean loggedEnemyOtcInit = false;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        this.initializedBattle = false;
        this.loggedPlayerOtcInit = false;
        this.loggedAlliedAiOtcInit = false;
        this.loggedEnemyOtcInit = false;
        this.divisionTimer = 0f;
        this.otcTimer = 0f;
        this.passiveContactGroup = null;
        this.side0OpposingView = null;
        this.tacticalOverlay = new DivisionTacticalMapOverlay(engine);
        this.deploymentOrganizer = new DivisionDeploymentDialogOrganizer();
        this.musicController = new DynamicBattleMusicController();
        BattlefieldFirepowerEvaluator.resetForNewBattle();
        if (engine != null) {
            engine.getCustomData().put(ENGINE_CUSTOM_DATA_KEY, this);
        }
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (!BattlefieldFirepowerEvaluator.isCommandViewWindowActive(engine)) return;
        BattlefieldFirepowerEvaluator.processInputEvents(engine, events);
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        // Tactical Map & Firepower Overlay render strictly inside the Command View Window (Tab)
        // via DivisionTacticalMapOverlay attached to CombatState's warroom.
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
            BattlefieldFirepowerEvaluator.processInputEvents(engine, events);
        }
        if (tacticalOverlay == null) {
            tacticalOverlay = new DivisionTacticalMapOverlay(engine);
        } else {
            tacticalOverlay.setEngine(engine);
        }
        tacticalOverlay.ensureAttachedToWarroomUI();

        if (deploymentOrganizer == null) {
            deploymentOrganizer = new DivisionDeploymentDialogOrganizer();
        }
        // Organize the Deployment Window even while the combat engine is paused during deployment
        deploymentOrganizer.advance(engine);

        if (initializedBattle) {
            tacticalOverlay.setBattleGroups(playerGroup, alliedAiGroup, enemyGroup);
            BattlefieldFirepowerEvaluator.updateConvergenceCommandStates(engine, playerGroup);
            BattlefieldFirepowerEvaluator.updateConvergenceCommandStates(engine, alliedAiGroup);
            BattlefieldFirepowerEvaluator.updateConvergenceCommandStates(engine, enemyGroup);
        }

        if (engine.isCombatOver()) {
            if (musicController != null) {
                BattleGroupState musicPlayerView = (side0OpposingView != null && !side0OpposingView.divisions.isEmpty())
                        ? side0OpposingView
                        : playerGroup;
                musicController.advance(engine, musicPlayerView, enemyGroup, amount);
            }
            return;
        }
        if (engine.isPaused()) {
            return;
        }

        float now = engine.getTotalElapsedTime(false);

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
            playerGroup.plays.clear();
            playerGroup.plays.addAll(doctrine.getEnabledPlays());

            playerGroup.missionContext = DivisionAutoFormer.detectMissionContext(engine, 0);
            alliedAiGroup.missionContext = playerGroup.missionContext;
            enemyGroup.missionContext = DivisionAutoFormer.detectMissionContext(engine, 1);

            tacticalOverlay.setBattleGroups(playerGroup, alliedAiGroup, enemyGroup);
            initializedBattle = true;
            LOG.info(String.format("[DivisionAI] Player OTC doctrine: authority=%s, plays=%s",
                    playerGroup.authority.shortName, playerGroup.plays));
        }

        divisionTimer += amount;
        otcTimer += amount;

        if (divisionTimer >= DIVISION_EVAL_INTERVAL) {
            divisionTimer = 0f;

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

            boolean playerHasDivisions = !playerGroup.divisions.isEmpty();
            boolean alliedAiHasDivisions = !alliedAiGroup.divisions.isEmpty();
            boolean enemyHasDivisions = !enemyGroup.divisions.isEmpty();

            BattlefieldFirepowerEvaluator.updateConvergenceCommandStates(engine, playerGroup);
            BattlefieldFirepowerEvaluator.updateConvergenceCommandStates(engine, alliedAiGroup);
            BattlefieldFirepowerEvaluator.updateConvergenceCommandStates(engine, enemyGroup);

            if (playerHasDivisions && !loggedPlayerOtcInit) {
                loggedPlayerOtcInit = true;
                LOG.info(String.format(
                        "[DivisionAI] Player Battle Group Commander (OTC) initialized (Mission=%s, Cmdr=%s [%s], Convergence=%b, ActiveDivs=%d)",
                        playerGroup.missionContext.displayName,
                        playerGroup.otc.commanderName,
                        playerGroup.otc.personalityId,
                        playerGroup.useConvergence,
                        playerGroup.divisions.size()));
            }
            if (alliedAiHasDivisions && !loggedAlliedAiOtcInit) {
                loggedAlliedAiOtcInit = true;
                LOG.info(String.format(
                        "[DivisionAI] Allied AI Battle Group Commander (OTC) initialized (Mission=%s, Cmdr=%s [%s], Convergence=%b, ActiveDivs=%d)",
                        alliedAiGroup.missionContext.displayName,
                        alliedAiGroup.otc.commanderName,
                        alliedAiGroup.otc.personalityId,
                        alliedAiGroup.useConvergence,
                        alliedAiGroup.divisions.size()));
            }
            if (enemyHasDivisions && !loggedEnemyOtcInit) {
                loggedEnemyOtcInit = true;
                LOG.info(String.format(
                        "[DivisionAI] Enemy Battle Group Commander (OTC) initialized (Mission=%s, Cmdr=%s [%s], Convergence=%b, ActiveDivs=%d)",
                        enemyGroup.missionContext.displayName,
                        enemyGroup.otc.commanderName,
                        enemyGroup.otc.personalityId,
                        enemyGroup.useConvergence,
                        enemyGroup.divisions.size()));
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
                OtcAI.applyInitialFleetFormationDeployment(engine, playerGroup, enemyGroup, now);
                OtcAI.updateTelemetry(engine, playerGroup);
            }
            if (alliedAiHasDivisions) {
                OtcAI.updateTelemetry(engine, alliedAiGroup);
                OtcAI.applyInitialFleetFormationDeployment(engine, alliedAiGroup, enemyGroup, now);
                OtcAI.updateTelemetry(engine, alliedAiGroup);
            }
            if (enemyHasDivisions) {
                OtcAI.updateTelemetry(engine, enemyGroup);
                OtcAI.applyInitialFleetFormationDeployment(engine, enemyGroup, playerGroup, now);
                OtcAI.updateTelemetry(engine, enemyGroup);
            }

            // Build a passive Side-0 opposing threat view for enemyGroup so Enemy OTC/Division Commanders can still
            // spot and fight unassigned player ships even when the player fleet has 0 divisions.
            BattleGroupState side0OpposingView = buildSide0OpposingViewForEnemy(playerHulls);

            // 3. Run OTC strategy ONLY for battlegroups that have at least 1 division
            boolean anyNewDivs = !newPlayerDivs.isEmpty() || !newAlliedAiDivs.isEmpty() || !newEnemyDivs.isEmpty();
            if (otcTimer >= OTC_EVAL_INTERVAL || anyNewDivs) {
                otcTimer = 0f;
                if (playerHasDivisions) {
                    OtcAI.evaluateOtcStrategy(engine, playerGroup, enemyGroup, now);
                }
                if (alliedAiHasDivisions) {
                    OtcAI.evaluateOtcStrategy(engine, alliedAiGroup, enemyGroup, now);
                }
                if (enemyHasDivisions && !side0OpposingView.divisions.isEmpty()) {
                    OtcAI.evaluateOtcStrategy(engine, enemyGroup, side0OpposingView, now);
                }
            }

            // 4. Run Division Commander tactical evaluation ONLY for battlegroups that have at least 1 division
            if (playerHasDivisions) {
                DivisionCommanderAI.evaluateAndExecuteDivisions(engine, playerGroup, enemyGroup, now);
            }
            if (alliedAiHasDivisions) {
                DivisionCommanderAI.evaluateAndExecuteDivisions(engine, alliedAiGroup, enemyGroup, now);
            }
            if (enemyHasDivisions && !side0OpposingView.divisions.isEmpty()) {
                DivisionCommanderAI.evaluateAndExecuteDivisions(engine, enemyGroup, side0OpposingView, now);
            }

            // 5. Update active Voidstrike instantiation, multi-division merging, and return cleanup
            BattlefieldFirepowerEvaluator.updateVoidstrikes(
                    engine,
                    playerGroup,
                    alliedAiGroup,
                    enemyGroup,
                    now);
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

    /**
     * Constructs a passive read-only opposing BattleGroupState for enemyGroup evaluation containing:
     *  - All active divisions in playerGroup
     *  - All active divisions in alliedAiGroup
     *  - A passive contact-tracking group for any unassigned player ships (never issued orders, never rendered)
     */
    private BattleGroupState buildSide0OpposingViewForEnemy(List<ShipAPI> playerHulls) {
        if (side0OpposingView == null) {
            side0OpposingView = new BattleGroupState(0, playerGroup.otc);
        }
        BattleGroupState view = side0OpposingView;
        view.otc = playerGroup.otc;
        view.missionContext = playerGroup.missionContext;
        view.divisions.clear();
        view.divisions.addAll(playerGroup.divisions);
        view.divisions.addAll(alliedAiGroup.divisions);

        List<ShipAPI> unassignedPlayerHulls = new ArrayList<>();
        for (ShipAPI s : playerHulls) {
            if (s != null && s.isAlive() && !s.isHulk() && !playerGroup.shipIdToDivision.containsKey(s.getId())) {
                unassignedPlayerHulls.add(s);
            }
        }

        if (!unassignedPlayerHulls.isEmpty()) {
            ShipAPI lead = unassignedPlayerHulls.get(0);
            float bestFp = -1f;
            for (ShipAPI s : unassignedPlayerHulls) {
                float fp = ShipCapabilityProfile.resolveShipFpStrength(s);
                if (fp > bestFp) {
                    bestFp = fp;
                    lead = s;
                }
            }
            // Reuse one contact-group object so enemy taskings and strike plans aimed at it stay stable across ticks
            if (passiveContactGroup == null) {
                CommanderProfile dummyCmdr = new CommanderProfile(lead.getCaptain(), "Player Fleet");
                passiveContactGroup = new DivisionState(
                        "unassigned_player_contacts",
                        0,
                        99,
                        DivisionArchetype.CRUISER_DIVISION,
                        "Player Unassigned Element",
                        dummyCmdr);
            }
            passiveContactGroup.flagship = lead;
            passiveContactGroup.members.clear();
            passiveContactGroup.members.addAll(unassignedPlayerHulls);
            view.divisions.add(passiveContactGroup);
        }

        if (!view.divisions.isEmpty()) {
            OtcAI.updateTelemetry(engine, view);
        }
        return view;
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
