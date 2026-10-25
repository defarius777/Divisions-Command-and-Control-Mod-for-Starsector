package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.BattlegroupState;
import divisionai.DivisionModel.DivisionIntentType;
import divisionai.DivisionModel.DivisionState;
import org.apache.log4j.Logger;

import java.awt.Color;
import java.util.List;

/**
 * Emits readable C2 intent messages to the in-game combat log (bottom-left feed)
 * and to starsector.log, with anti-spam throttling so the player sees meaningful
 * tactical transitions such as:
 *   "Destroyer Div 1" intends to launch harassing torpedo attack at "Enemy Destroyer Div 4".
 *   "Destroyer Div 1" intends to screen and escort "Battle Div 2".
 */
public class DivisionCombatLog {

    private static final Logger LOG = Global.getLogger(DivisionCombatLog.class);
    private static final float MIN_DIVISION_LOG_INTERVAL = 7.5f;
    private static final float MIN_BG_LOG_INTERVAL = 10.0f;

    public static void logDivisionFormed(CombatEngineAPI engine, DivisionState div, float now) {
        if (div == null) return;
        List<ShipAPI> alive = div.getAliveMembers();
        if (alive.isEmpty()) return;

        StringBuilder hullSummary = new StringBuilder();
        int shown = Math.min(3, alive.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) hullSummary.append(", ");
            ShipAPI s = alive.get(i);
            String hullName = (s.getHullSpec() != null && s.getHullSpec().getHullName() != null)
                    ? s.getHullSpec().getHullName()
                    : s.getName();
            hullSummary.append(hullName);
        }
        if (alive.size() > shown) {
            hullSummary.append(" +").append(alive.size() - shown);
        }

        String text = String.format(
                "[C2] Formed \"%s\" (%d hulls: %s | Cmdr: %s [%s])",
                div.displayName,
                alive.size(),
                hullSummary.toString(),
                div.commander.commanderName,
                div.commander.personalityId);
        LOG.info(text);

        // Show friendly division formations + major enemy divisions in the HUD log
        CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
        if (ui != null && div.side == 0) {
            Color divColor = Misc.getBrightPlayerColor();
            Color textColor = Misc.getTextColor();
            Color hlColor = Misc.getHighlightColor();
            ui.addMessage(
                    1,
                    divColor, "\"" + div.displayName + "\"",
                    textColor, " formed (",
                    hlColor, alive.size() + " hulls: " + hullSummary,
                    textColor, ", Cmdr " + div.commander.personalityId + ").");
        }
    }

    public static void logBattlegroupDirective(
            CombatEngineAPI engine,
            BattlegroupState bg,
            String directiveSummary,
            float now) {
        if (bg == null || directiveSummary == null) return;

        String sig = bg.currentPhase.name() + "|" + directiveSummary;
        if (sig.equals(bg.lastBgOrderSignature) && (now - bg.lastBgOrderLogTime) < 25f) {
            return;
        }
        if ((now - bg.lastBgOrderLogTime) < MIN_BG_LOG_INTERVAL && bg.previousPhase == bg.currentPhase) {
            return;
        }

        bg.lastBgOrderSignature = sig;
        bg.lastBgOrderLogTime = now;

        String sideTag = (bg.side == 0) ? "[Allied BG Cmdr]" : "[Enemy BG Cmdr]";
        LOG.info(String.format("%s (%s) Phase=%s -> %s",
                sideTag, bg.bgCommander.personalityId, bg.currentPhase.displayName, directiveSummary));

        CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
        if (ui != null) {
            Color prefixColor = (bg.side == 0) ? Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor();
            Color textColor = Misc.getTextColor();
            Color hlColor = Misc.getHighlightColor();
            ui.addMessage(
                    1,
                    prefixColor, sideTag + " ",
                    hlColor, bg.currentPhase.displayName,
                    textColor, " — " + directiveSummary);
        }
    }

    public static void logDivisionIntent(
            CombatEngineAPI engine,
            DivisionState div,
            String targetLabel,
            String tacticalReason,
            float now) {
        if (div == null || div.currentIntent == null) return;

        String cleanTarget = (targetLabel != null && !targetLabel.isEmpty()) ? targetLabel : "";
        String sig = div.currentIntent.name() + "|" + cleanTarget;

        // Suppress duplicate announcements of the exact same intent+target
        if (sig.equals(div.lastLoggedSignature) && (now - div.lastLogAnnouncementTime) < 24f) {
            return;
        }
        // Throttle rapid re-evaluations so the battle log remains crisp and readable
        if ((now - div.lastLogAnnouncementTime) < MIN_DIVISION_LOG_INTERVAL
                && div.currentIntent == DivisionIntentType.HOLD_AND_COHERE) {
            return;
        }

        div.lastLoggedSignature = sig;
        div.lastLogAnnouncementTime = now;

        String reasonSuffix = (tacticalReason != null && !tacticalReason.isEmpty())
                ? " (" + tacticalReason + ")"
                : "";

        String fullLine;
        if (!cleanTarget.isEmpty()) {
            fullLine = String.format(
                    "\"%s\" intends to %s \"%s\"%s.",
                    div.displayName,
                    div.currentIntent.verbPhrase,
                    cleanTarget,
                    reasonSuffix);
        } else {
            fullLine = String.format(
                    "\"%s\" intends to %s%s.",
                    div.displayName,
                    div.currentIntent.verbPhrase,
                    reasonSuffix);
        }

        LOG.info("[DivisionAI] " + fullLine);

        CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
        if (ui != null) {
            Color actorColor = (div.side == 0) ? Misc.getBrightPlayerColor() : Misc.getNegativeHighlightColor();
            Color targetColor = resolveTargetColor(div, cleanTarget);
            Color textColor = Misc.getTextColor();
            Color grayColor = Misc.getGrayColor();

            if (!cleanTarget.isEmpty()) {
                if (!reasonSuffix.isEmpty()) {
                    ui.addMessage(
                            1,
                            actorColor, "\"" + div.displayName + "\"",
                            textColor, " intends to " + div.currentIntent.verbPhrase + " ",
                            targetColor, "\"" + cleanTarget + "\"",
                            grayColor, reasonSuffix + ".");
                } else {
                    ui.addMessage(
                            1,
                            actorColor, "\"" + div.displayName + "\"",
                            textColor, " intends to " + div.currentIntent.verbPhrase + " ",
                            targetColor, "\"" + cleanTarget + "\"",
                            textColor, ".");
                }
            } else {
                ui.addMessage(
                        1,
                        actorColor, "\"" + div.displayName + "\"",
                        textColor, " intends to " + div.currentIntent.verbPhrase,
                        grayColor, reasonSuffix + ".");
            }
        }
    }

    private static Color resolveTargetColor(DivisionState actor, String targetLabel) {
        if (targetLabel.startsWith("Enemy ")) {
            return Misc.getNegativeHighlightColor();
        }
        if (actor.currentIntent == DivisionIntentType.SCREEN_FRIENDLY_DIVISION
                || actor.currentIntent == DivisionIntentType.RELIEVE_FRIENDLY_DIVISION
                || actor.currentIntent == DivisionIntentType.ROTATE_TO_RESERVE) {
            return (actor.side == 0) ? Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor();
        }
        if (actor.currentIntent == DivisionIntentType.SEIZE_OBJECTIVE) {
            return Misc.getHighlightColor();
        }
        return (actor.side == 0) ? Misc.getNegativeHighlightColor() : Misc.getBrightPlayerColor();
    }
}
