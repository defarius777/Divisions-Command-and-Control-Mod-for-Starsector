package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import divisionai.DivisionModel.BattlegroupState;
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
 *  2. If the player fleet has 0 active divisions in battle, neither the Player Battlegroup Commander
 *     nor any Player Division Commander initializes — player ships default 100% to vanilla combat AI.
 *  3. If an Allied AI fleet (ship.isAlly()) joins a player fleet that has no divisions, the Allied AI
 *     fleet's own Battlegroup Commander and Division Commanders still initialize and function as normal.
 *  4. Reorganizes the Deployment Window into rows following the player's list of non-empty divisions.
 */
public class DivisionAICombatPlugin extends BaseEveryFrameCombatPlugin {

    private static final Logger LOG = Global.getLogger(DivisionAICombatPlugin.class);
    private static final String ENGINE_CUSTOM_DATA_KEY = "divisionai_active_plugin";
    private static final Object HUD_STATUS_KEY_DIV = new Object();
    private static final Object HUD_STATUS_KEY_BG = new Object();

    private static final float DIVISION_EVAL_INTERVAL = 0.50f;
    private static final float BG_EVAL_INTERVAL = 1.50f;

    private CombatEngineAPI engine;
    private BattlegroupState playerBg;
    private BattlegroupState alliedAiBg;
    private BattlegroupState enemyBg;
    private DivisionTacticalMapOverlay tacticalOverlay;
    private DivisionDeploymentDialogOrganizer deploymentOrganizer;

    private float divisionTimer = 0f;
    private float bgTimer = 0f;
    private boolean initializedBattle = false;
    private boolean loggedPlayerBgInit = false;
    private boolean loggedAlliedAiBgInit = false;
    private boolean loggedEnemyBgInit = false;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        this.initializedBattle = false;
        this.loggedPlayerBgInit = false;
        this.loggedAlliedAiBgInit = false;
        this.loggedEnemyBgInit = false;
        this.divisionTimer = 0f;
        this.bgTimer = 0f;
        this.tacticalOverlay = new DivisionTacticalMapOverlay(engine);
        this.deploymentOrganizer = new DivisionDeploymentDialogOrganizer();
        if (engine != null) {
            engine.getCustomData().put(ENGINE_CUSTOM_DATA_KEY, this);
        }
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null) {
            engine = Global.getCombatEngine();
            if (engine == null) return;
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

        if (engine.isPaused() || engine.isCombatOver()) {
            return;
        }

        float now = engine.getTotalElapsedTime(false);

        if (!initializedBattle) {
            CommanderProfile playerCmdr = DivisionAutoFormer.resolveBattlegroupCommander(engine, 0);
            CommanderProfile alliedAiCmdr = DivisionAutoFormer.resolveAlliedAiBattlegroupCommander(engine, null);
            CommanderProfile enemyCmdr = DivisionAutoFormer.resolveBattlegroupCommander(engine, 1);

            playerBg = new BattlegroupState(0, playerCmdr);
            alliedAiBg = new BattlegroupState(0, alliedAiCmdr);
            enemyBg = new BattlegroupState(1, enemyCmdr);

            playerBg.missionContext = DivisionAutoFormer.detectMissionContext(engine, 0);
            alliedAiBg.missionContext = playerBg.missionContext;
            enemyBg.missionContext = DivisionAutoFormer.detectMissionContext(engine, 1);

            tacticalOverlay.setBattlegroups(playerBg, alliedAiBg, enemyBg);
            initializedBattle = true;
        }

        divisionTimer += amount;
        bgTimer += amount;

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
                    playerBg,
                    playerHulls);

            // Allied AI fleet and Enemy fleet auto-form divisions independently
            if (!alliedAiHulls.isEmpty() && !loggedAlliedAiBgInit) {
                alliedAiBg.bgCommander = DivisionAutoFormer.resolveAlliedAiBattlegroupCommander(engine, alliedAiHulls);
            }
            List<DivisionState> newAlliedAiDivs = DivisionAutoFormer.syncDivisionsForSide(
                    engine,
                    alliedAiBg,
                    alliedAiHulls);
            List<DivisionState> newEnemyDivs = DivisionAutoFormer.syncDivisionsForSide(
                    engine,
                    enemyBg,
                    enemyHulls);

            boolean playerHasDivisions = !playerBg.divisions.isEmpty();
            boolean alliedAiHasDivisions = !alliedAiBg.divisions.isEmpty();
            boolean enemyHasDivisions = !enemyBg.divisions.isEmpty();

            if (playerHasDivisions && !loggedPlayerBgInit) {
                loggedPlayerBgInit = true;
                LOG.info(String.format(
                        "[DivisionAI] Player Battlegroup Commander initialized (Mission=%s, Cmdr=%s [%s], ActiveDivs=%d)",
                        playerBg.missionContext.displayName,
                        playerBg.bgCommander.commanderName,
                        playerBg.bgCommander.personalityId,
                        playerBg.divisions.size()));
            }
            if (alliedAiHasDivisions && !loggedAlliedAiBgInit) {
                loggedAlliedAiBgInit = true;
                LOG.info(String.format(
                        "[DivisionAI] Allied AI Battlegroup Commander initialized (Mission=%s, Cmdr=%s [%s], ActiveDivs=%d)",
                        alliedAiBg.missionContext.displayName,
                        alliedAiBg.bgCommander.commanderName,
                        alliedAiBg.bgCommander.personalityId,
                        alliedAiBg.divisions.size()));
            }
            if (enemyHasDivisions && !loggedEnemyBgInit) {
                loggedEnemyBgInit = true;
                LOG.info(String.format(
                        "[DivisionAI] Enemy Battlegroup Commander initialized (Mission=%s, Cmdr=%s [%s], ActiveDivs=%d)",
                        enemyBg.missionContext.displayName,
                        enemyBg.bgCommander.commanderName,
                        enemyBg.bgCommander.personalityId,
                        enemyBg.divisions.size()));
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
                BattlegroupCommanderAI.updateTelemetry(engine, playerBg);
            }
            if (alliedAiHasDivisions) {
                BattlegroupCommanderAI.updateTelemetry(engine, alliedAiBg);
            }
            if (enemyHasDivisions) {
                BattlegroupCommanderAI.updateTelemetry(engine, enemyBg);
            }

            // Build a passive Side-0 opposing threat view for enemyBg so Enemy BG/Division Commanders can still
            // spot and fight unassigned player ships even when the player fleet has 0 divisions.
            BattlegroupState side0OpposingView = buildSide0OpposingViewForEnemy(playerHulls);

            // 3. Run Battlegroup Commander strategy ONLY for battlegroups that have at least 1 division
            boolean anyNewDivs = !newPlayerDivs.isEmpty() || !newAlliedAiDivs.isEmpty() || !newEnemyDivs.isEmpty();
            if (bgTimer >= BG_EVAL_INTERVAL || anyNewDivs) {
                bgTimer = 0f;
                if (playerHasDivisions && enemyHasDivisions) {
                    BattlegroupCommanderAI.evaluateBattlegroupStrategy(engine, playerBg, enemyBg, now);
                }
                if (alliedAiHasDivisions && enemyHasDivisions) {
                    BattlegroupCommanderAI.evaluateBattlegroupStrategy(engine, alliedAiBg, enemyBg, now);
                }
                if (enemyHasDivisions && !side0OpposingView.divisions.isEmpty()) {
                    BattlegroupCommanderAI.evaluateBattlegroupStrategy(engine, enemyBg, side0OpposingView, now);
                }
            }

            // 4. Run Division Commander tactical evaluation ONLY for battlegroups that have at least 1 division
            if (playerHasDivisions && enemyHasDivisions) {
                DivisionCommanderAI.evaluateAndExecuteDivisions(engine, playerBg, enemyBg, now);
            }
            if (alliedAiHasDivisions && enemyHasDivisions) {
                DivisionCommanderAI.evaluateAndExecuteDivisions(engine, alliedAiBg, enemyBg, now);
            }
            if (enemyHasDivisions && !side0OpposingView.divisions.isEmpty()) {
                DivisionCommanderAI.evaluateAndExecuteDivisions(engine, enemyBg, side0OpposingView, now);
            }
        }

        // Maintain HUD status on the player flagship ONLY if the player fleet has at least 1 active division
        maintainPlayerHudStatus();
    }

    /**
     * Constructs a passive read-only opposing BattlegroupState for enemyBg evaluation containing:
     *  - All active divisions in playerBg
     *  - All active divisions in alliedAiBg
     *  - A passive contact-tracking group for any unassigned player ships (never issued orders, never rendered)
     */
    private BattlegroupState buildSide0OpposingViewForEnemy(List<ShipAPI> playerHulls) {
        BattlegroupState view = new BattlegroupState(0, playerBg.bgCommander);
        view.missionContext = playerBg.missionContext;
        view.divisions.addAll(playerBg.divisions);
        view.divisions.addAll(alliedAiBg.divisions);

        List<ShipAPI> unassignedPlayerHulls = new ArrayList<>();
        for (ShipAPI s : playerHulls) {
            if (s != null && s.isAlive() && !s.isHulk() && !playerBg.shipIdToDivision.containsKey(s.getId())) {
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
            CommanderProfile dummyCmdr = new CommanderProfile(lead.getCaptain(), "Player Fleet");
            DivisionState passiveContactGroup = new DivisionState(
                    "unassigned_player_contacts",
                    0,
                    99,
                    DivisionArchetype.CRUISER_DIVISION,
                    "Player Unassigned Element",
                    dummyCmdr);
            passiveContactGroup.flagship = lead;
            passiveContactGroup.members.addAll(unassignedPlayerHulls);
            view.divisions.add(passiveContactGroup);
        }

        if (!view.divisions.isEmpty()) {
            BattlegroupCommanderAI.updateTelemetry(engine, view);
        }
        return view;
    }

    private void maintainPlayerHudStatus() {
        if (playerBg == null || playerBg.divisions.isEmpty()) {
            return;
        }
        ShipAPI playerShip = engine.getPlayerShip();
        if (playerShip == null || !playerShip.isAlive()) return;

        DivisionState playerDiv = playerBg.shipIdToDivision.get(playerShip.getId());
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
                HUD_STATUS_KEY_BG,
                "graphics/icons/hullsys/reserve_deployment.png",
                "BG Phase: " + playerBg.currentPhase.displayName,
                String.format("Force Ratio %.2fx | %d Active Divs", playerBg.forceRatio, playerBg.divisions.size()),
                false);
    }
}
