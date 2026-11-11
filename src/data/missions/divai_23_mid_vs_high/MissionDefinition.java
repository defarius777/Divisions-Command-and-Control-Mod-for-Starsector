package data.missions.divai_23_mid_vs_high;

import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;
import divisionai.DivisionShowcaseMissions;
import divisionai.DivisionShowcaseMissions.ShowcaseFaction;

public class MissionDefinition implements MissionDefinitionPlugin {
    public void defineMission(MissionDefinitionAPI api) {
        DivisionShowcaseMissions.defineMission(
                api,
                "divai_23_mid_vs_high",
                ShowcaseFaction.MIDLINE,
                ShowcaseFaction.HIGH_TECH);
    }
}
