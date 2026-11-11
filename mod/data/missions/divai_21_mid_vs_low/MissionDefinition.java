package data.missions.divai_21_mid_vs_low;

import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;
import divisionai.DivisionShowcaseMissions;
import divisionai.DivisionShowcaseMissions.ShowcaseFaction;

public class MissionDefinition implements MissionDefinitionPlugin {
    public void defineMission(MissionDefinitionAPI api) {
        DivisionShowcaseMissions.defineMission(
                api,
                "divai_21_mid_vs_low",
                ShowcaseFaction.MIDLINE,
                ShowcaseFaction.LOW_TECH);
    }
}
