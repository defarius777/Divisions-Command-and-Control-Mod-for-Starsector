package divisionai;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

/** Smoke test: verifies the toolchain compiles against the pinned API jar. */
public class DivisionAIModPlugin extends BaseModPlugin {
    @Override
    public void onApplicationLoad() throws Exception {
        Global.getLogger(DivisionAIModPlugin.class).info("DivisionAI v0.3.2 loaded: Battlegroup Commander & Division Commander C2 AI active.");
    }
}
