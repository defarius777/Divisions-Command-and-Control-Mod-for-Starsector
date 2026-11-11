package data.missions.divai_12_low_vs_mid;

import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;
import divisionai.DivisionShowcaseMissions;
import divisionai.DivisionShowcaseMissions.ShowcaseFaction;

public class MissionDefinition implements MissionDefinitionPlugin {
    public void defineMission(MissionDefinitionAPI api) {
        DivisionShowcaseMissions.defineMission(
                api,
                "divai_12_low_vs_mid",
                ShowcaseFaction.LOW_TECH,
                ShowcaseFaction.MIDLINE);
    }
}
