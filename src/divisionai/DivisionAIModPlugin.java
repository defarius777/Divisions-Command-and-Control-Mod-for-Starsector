package divisionai;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;

/** Mod plugin initializing campaign Permanent Divisions, Fleet Screen Fleet Doctrine & Fleet Organization buttons, and Combat C2 AI for Divisions: Command and Control. */
public class DivisionAIModPlugin extends BaseModPlugin {

    @Override
    public void onApplicationLoad() throws Exception {
        ClassLoader cl = DivisionAIModPlugin.class.getClassLoader();
        boolean uiOk = DivisionDeploymentDialogOrganizer.verifyObfuscatedHandles(cl);
        boolean combatOk = DivisionCommanderAI.verifyCombatObfuscatedHandles(cl);
        boolean audioOk = DynamicBattleMusicController.verifyAudioHandles();
        if (!uiOk || !combatOk || !audioOk) {
            Global.getLogger(DivisionAIModPlugin.class).warn(String.format(
                    "[DivisionAI] Startup obfuscated MethodHandle check: ui=%b, combat=%b, audio=%b (graceful fallback active where false).",
                    uiOk, combatOk, audioOk));
        }
        DivisionAISettings.captureBaselineFleetAndBattleSettings();
        DivisionAISettings.applyFleetAndBattleSizeOverrides();
        Global.getLogger(DivisionAIModPlugin.class).info("Divisions: Command and Control v1.1.2 loaded: Campaign Permanent Divisions, Faction Division Record Tables, Cultural Nomenclature, Core-Cluster Cohesion & Wide-Arc Battlespace Routing active.");
    }

    @Override
    public void onNewGameAfterTimePass() {
        SectorAPI sector = Global.getSector();
        if (sector != null) {
            FactionFleetDoctrines.onGameLoad(sector);
        }
    }

    @Override
    public void onGameLoad(boolean newGame) {
        DivisionAISettings.applyFleetAndBattleSizeOverrides();
        SectorAPI sector = Global.getSector();
        if (sector != null) {
            PermanentDivisionManager.ensureInitializedForPlayerFleet();
            PermanentDivisionManager.migrateAutoStandoffToGunRange();
            PermanentDivisionManager.migrateDivisionNamesToNavalTerms();
            PermanentDivisionManager.migrateFleetWideFormationDefaultToAuto();
            DivisionNomenclature.sanitizePersistentNamingData(sector);
            FactionFleetDoctrines.onGameLoad(sector);
            DivisionAISettings.consumePendingReinitOnGameLoad();
            sector.removeTransientScriptsOfClass(DivisionFleetTabInjector.class);
            sector.addTransientScript(new DivisionFleetTabInjector());
        }
    }

    @Override
    public void beforeGameSave() {
        SectorAPI sector = Global.getSector();
        if (sector != null) {
            DivisionNomenclature.sanitizePersistentNamingData(sector);
            FactionFleetDoctrines.sanitizeAllNpcFleetMemories(sector);
        }
    }
}
