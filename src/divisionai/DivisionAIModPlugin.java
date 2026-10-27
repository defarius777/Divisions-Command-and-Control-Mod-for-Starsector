package divisionai;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;

/** Mod plugin initializing campaign Permanent Divisions, Fleet Screen Fleet Doctrine & Fleet Organization buttons, and Combat C2 AI for Divisions: Command and Control. */
public class DivisionAIModPlugin extends BaseModPlugin {

    @Override
    public void onApplicationLoad() throws Exception {
        Global.getLogger(DivisionAIModPlugin.class).info("Divisions: Command and Control v0.6.9 loaded: Campaign Permanent Divisions, Faction Division Record Tables, Cultural Nomenclature, Core-Cluster Cohesion & Wide-Arc Battlespace Routing active.");
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
        SectorAPI sector = Global.getSector();
        if (sector != null) {
            PermanentDivisionManager.ensureInitializedForPlayerFleet();
            PermanentDivisionManager.migrateAutoStandoffToGunRange();
            PermanentDivisionManager.migrateDivisionNamesToNavalTerms();
            FactionFleetDoctrines.onGameLoad(sector);
            DivisionAISettings.consumePendingReinitOnGameLoad();
            sector.removeTransientScriptsOfClass(DivisionFleetTabInjector.class);
            sector.addTransientScript(new DivisionFleetTabInjector());
        }
    }
}
