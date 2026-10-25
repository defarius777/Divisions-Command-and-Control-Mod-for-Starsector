package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import divisionai.DivisionModel.BattlegroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionState;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime combat loop for Division AI.
 * Registered via data/config/settings.json ("plugins": {"divisionai_combat_plugin": ...})
 * so Starsector attaches an instance automatically to every campaign, mission, and simulator battle.
 */
public class DivisionAICombatPlugin extends BaseEveryFrameCombatPlugin {

    private static final Logger LOG = Global.getLogger(DivisionAICombatPlugin.class);
    private static final String ENGINE_CUSTOM_DATA_KEY = "divisionai_active_plugin";
    private static final Object HUD_STATUS_KEY_DIV = new Object();
    private static final Object HUD_STATUS_KEY_BG = new Object();

    private static final float DIVISION_EVAL_INTERVAL = 0.50f;
    private static final float BG_EVAL_INTERVAL = 1.50f;

    private CombatEngineAPI engine;
    private BattlegroupState alliedBg;
    private BattlegroupState enemyBg;
    private DivisionTacticalMapOverlay tacticalOverlay;

    private float divisionTimer = 0f;
    private float bgTimer = 0f;
    private boolean initializedBattle = false;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        this.initializedBattle = false;
        this.divisionTimer = 0f;
        this.bgTimer = 0f;
        this.tacticalOverlay = new DivisionTacticalMapOverlay(engine);
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

        if (engine.isPaused() || engine.isCombatOver()) {
            return;
        }

        float now = engine.getTotalElapsedTime(false);

        if (!initializedBattle) {
            CommanderProfile alliedCmdr = DivisionAutoFormer.resolveBattlegroupCommander(engine, 0);
            CommanderProfile enemyCmdr = DivisionAutoFormer.resolveBattlegroupCommander(engine, 1);

            alliedBg = new BattlegroupState(0, alliedCmdr);
            enemyBg = new BattlegroupState(1, enemyCmdr);

            alliedBg.missionContext = DivisionAutoFormer.detectMissionContext(engine, 0);
            enemyBg.missionContext = DivisionAutoFormer.detectMissionContext(engine, 1);
            tacticalOverlay.setBattlegroups(alliedBg, enemyBg);

            initializedBattle = true;
            LOG.info(String.format(
                    "[DivisionAI] Battle initialized (Allied Mission=%s, Cmdr=%s [%s] | Enemy Mission=%s, Cmdr=%s [%s])",
                    alliedBg.missionContext.displayName,
                    alliedCmdr.commanderName,
                    alliedCmdr.personalityId,
                    enemyBg.missionContext.displayName,
                    enemyCmdr.commanderName,
                    enemyCmdr.personalityId));
        }

        divisionTimer += amount;
        bgTimer += amount;

        if (divisionTimer >= DIVISION_EVAL_INTERVAL) {
            divisionTimer = 0f;

            // 1. Partition eligible combat hulls by side and sync into divisions
            List<ShipAPI> alliedHulls = new ArrayList<>();
            List<ShipAPI> enemyHulls = new ArrayList<>();
            for (ShipAPI s : engine.getShips()) {
                if (!DivisionAutoFormer.isEligibleCombatHull(s)) continue;
                if (s.getOwner() == 0) {
                    alliedHulls.add(s);
                } else if (s.getOwner() == 1) {
                    enemyHulls.add(s);
                }
            }

            List<DivisionState> newAlliedDivs = DivisionAutoFormer.syncDivisionsForSide(engine, alliedBg, alliedHulls);
            List<DivisionState> newEnemyDivs = DivisionAutoFormer.syncDivisionsForSide(engine, enemyBg, enemyHulls);

            for (DivisionState d : newAlliedDivs) {
                DivisionCombatLog.logDivisionFormed(engine, d, now);
            }
            for (DivisionState d : newEnemyDivs) {
                DivisionCombatLog.logDivisionFormed(engine, d, now);
            }

            // 2. Update division centroids, cohesion spread, flux pressure, and spatial-grid local overmatch
            BattlegroupCommanderAI.updateTelemetry(engine, alliedBg);
            BattlegroupCommanderAI.updateTelemetry(engine, enemyBg);

            // 3. Run Battlegroup Commander strategy on BG cadence (or immediately when new divisions form)
            if (bgTimer >= BG_EVAL_INTERVAL || !newAlliedDivs.isEmpty() || !newEnemyDivs.isEmpty()) {
                bgTimer = 0f;
                if (!alliedBg.divisions.isEmpty() && !enemyBg.divisions.isEmpty()) {
                    BattlegroupCommanderAI.evaluateBattlegroupStrategy(engine, alliedBg, enemyBg, now);
                    BattlegroupCommanderAI.evaluateBattlegroupStrategy(engine, enemyBg, alliedBg, now);
                }
            }

            // 4. Run Division Commander tactical evaluation, log intent changes, and apply ShipwideAIFlags
            if (!alliedBg.divisions.isEmpty() && !enemyBg.divisions.isEmpty()) {
                DivisionCommanderAI.evaluateAndExecuteDivisions(engine, alliedBg, enemyBg, now);
                DivisionCommanderAI.evaluateAndExecuteDivisions(engine, enemyBg, alliedBg, now);
            }
        }

        // Maintain concise player flagship HUD status items so the player can always see their division & BG phase
        maintainPlayerHudStatus();
    }

    private void maintainPlayerHudStatus() {
        ShipAPI playerShip = engine.getPlayerShip();
        if (playerShip == null || !playerShip.isAlive() || alliedBg == null) return;

        DivisionState playerDiv = alliedBg.shipIdToDivision.get(playerShip.getId());
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
                "BG Phase: " + alliedBg.currentPhase.displayName,
                String.format("Force Ratio %.2fx | %d Active Divs", alliedBg.forceRatio, alliedBg.divisions.size()),
                false);
    }
}
