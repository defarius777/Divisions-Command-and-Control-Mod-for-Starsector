package divisionai;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;

/** Mod plugin initializing campaign Permanent Divisions, Fleet Screen Division Editor button, and Combat C2 AI. */
public class DivisionAIModPlugin extends BaseModPlugin {

    @Override
    public void onApplicationLoad() throws Exception {
        Global.getLogger(DivisionAIModPlugin.class).info("DivisionAI v0.5.0 loaded: Campaign Permanent Divisions, Fleet Doctrine, Battlegroup Orchestration & Combat C2 AI active.");
    }

    @Override
    public void onGameLoad(boolean newGame) {
        SectorAPI sector = Global.getSector();
        if (sector != null) {
            PermanentDivisionManager.ensureInitializedForPlayerFleet();
            PermanentDivisionManager.migrateAutoStandoffToGunRange();
            sector.removeTransientScriptsOfClass(DivisionFleetTabInjector.class);
            sector.addTransientScript(new DivisionFleetTabInjector());
        }
    }
}
