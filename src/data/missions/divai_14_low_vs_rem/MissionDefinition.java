package data.missions.divai_14_low_vs_rem;

import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;
import divisionai.DivisionShowcaseMissions;
import divisionai.DivisionShowcaseMissions.ShowcaseFaction;

public class MissionDefinition implements MissionDefinitionPlugin {
    public void defineMission(MissionDefinitionAPI api) {
        DivisionShowcaseMissions.defineMission(
                api,
                "divai_14_low_vs_rem",
                ShowcaseFaction.LOW_TECH,
                ShowcaseFaction.REMNANT);
    }
}
