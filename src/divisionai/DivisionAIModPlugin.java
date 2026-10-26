package divisionai;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;

/** Mod plugin initializing campaign Permanent Divisions, Fleet Screen Division Editor button, and Combat C2 AI. */
public class DivisionAIModPlugin extends BaseModPlugin {

    @Override
    public void onApplicationLoad() throws Exception {
        Global.getLogger(DivisionAIModPlugin.class).info("DivisionAI v0.6.3 loaded: Campaign Permanent Divisions, Faction Division Record Tables, Cultural Nomenclature, Core-Cluster Cohesion & Wide-Arc Battlespace Routing active.");
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
            sector.removeTransientScriptsOfClass(DivisionFleetTabInjector.class);
            sector.addTransientScript(new DivisionFleetTabInjector());
        }
    }
}
