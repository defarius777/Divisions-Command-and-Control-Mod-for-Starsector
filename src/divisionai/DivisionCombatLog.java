package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.BattleGroupState;
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

    private static final Logger LOG = Logger.getLogger(DivisionCombatLog.class);
    private static final float MIN_DIVISION_LOG_INTERVAL = 7.5f;
    private static final float MIN_OTC_LOG_INTERVAL = 10.0f;

    /** Optional sink populated during headless tactical simulation runs (./test.sh). */
    public static List<String> simLogSink = null;

    private static void emitLogLine(float now, String text) {
        LOG.info(text);
        if (simLogSink != null) {
            simLogSink.add(String.format(java.util.Locale.ROOT, "[t=%5.1fs] %s", Math.max(0f, now), text));
        }
    }

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

        String divName = DivisionNomenclature.resolveDynamicDivisionDisplayName(
                div.nomenclatureCategory,
                div.archetype,
                div.divisionNumber,
                div.displayName);
        String text = String.format(
                "[C2] Formed \"%s\" (%d hulls: %s | Cmdr: %s [%s])",
                divName,
                alive.size(),
                hullSummary.toString(),
                div.commander.commanderName,
                div.commander.personalityId);
        emitLogLine(now, text);

        // Show friendly division formations in the HUD log if friendly division chatter is enabled
        CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
        if (ui != null && div.side == 0 && DivisionAISettings.isDivisionSignalsEnabled(div.side)) {
            Color divColor = Misc.getBrightPlayerColor();
            Color textColor = Misc.getTextColor();
            Color hlColor = Misc.getHighlightColor();
            if (div.nomenclatureCategory == DivisionNomenclature.NomenclatureCategory.ABYSSAL_ERROR) {
                ui.addMessage(1, Misc.getNegativeHighlightColor(), "ERROR");
            } else {
                ui.addMessage(
                        1,
                        divColor, "\"" + divName + "\"",
                        textColor, " formed (",
                        hlColor, alive.size() + " hulls: " + hullSummary,
                        textColor, ", Cmdr " + div.commander.personalityId + ").");
            }
        }
    }

    public static void logReinforcementJoined(DivisionState div, ShipAPI ship) {
        if (div == null || ship == null) return;
        String divName = DivisionNomenclature.resolveDynamicDivisionDisplayName(
                div.nomenclatureCategory,
                div.archetype,
                div.divisionNumber,
                div.displayName);
        String hullName = (ship.getHullSpec() != null && ship.getHullSpec().getHullName() != null)
                ? ship.getHullSpec().getHullName()
                : ship.getName();
        String sideTag = (div.side == 0)
                ? (ship.isAlly() ? "Allied" : "Friendly")
                : "Enemy";
        LOG.info(String.format(
                "[C2] Reinforcement (%s): %s (%s) joined \"%s\" (now %d hulls)",
                sideTag,
                ship.getName(),
                hullName,
                divName,
                div.getAliveMembers().size()));
    }

    private static String resolveOtcTag(BattleGroupState battleGroup) {
        if (battleGroup == null) return "[OTC]";
        String defaultTitle = (battleGroup.side == 0)
                ? (battleGroup.isAlliedAi ? "Allied OTC" : "Player OTC")
                : "Enemy OTC";
        String title = DivisionNomenclature.resolveOtcCommanderTitle(
                battleGroup.nomenclatureCategory,
                battleGroup.side == 0 && !battleGroup.isAlliedAi,
                battleGroup.isAlliedAi,
                defaultTitle);
        return "[" + title + "]";
    }

    public static void logOtcSignal(
            CombatEngineAPI engine,
            BattleGroupState battleGroup,
            String directiveSummary,
            float now) {
        if (battleGroup == null || directiveSummary == null) return;

        String sig = battleGroup.currentPhase.name() + "|" + directiveSummary;
        if (sig.equals(battleGroup.lastOtcSignalSignature) && (now - battleGroup.lastOtcSignalLogTime) < 25f) {
            return;
        }
        if ((now - battleGroup.lastOtcSignalLogTime) < MIN_OTC_LOG_INTERVAL && battleGroup.previousPhase == battleGroup.currentPhase) {
            return;
        }

        battleGroup.lastOtcSignalSignature = sig;
        battleGroup.lastOtcSignalLogTime = now;

        String sideTag = resolveOtcTag(battleGroup);
        emitLogLine(now, String.format("%s (%s) Phase=%s -> %s",
                sideTag, battleGroup.otc.personalityId, battleGroup.currentPhase.displayName, directiveSummary));

        CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
        if (ui != null && DivisionAISettings.isOtcSignalsEnabled(battleGroup.side)) {
            Color prefixColor = (battleGroup.side == 0) ? Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor();
            Color textColor = Misc.getTextColor();
            Color hlColor = Misc.getHighlightColor();
            if (battleGroup.nomenclatureCategory == DivisionNomenclature.NomenclatureCategory.ABYSSAL_ERROR) {
                ui.addMessage(1, Misc.getNegativeHighlightColor(), sideTag + " ERROR");
            } else {
                ui.addMessage(
                        1,
                        prefixColor, sideTag + " ",
                        hlColor, battleGroup.currentPhase.displayName,
                        textColor, " — " + directiveSummary);
            }
        }
    }

    /**
     * Logs a OTC orchestration order (strike plans, relief, pursuit, pincer sides).
     * Throttling is done by the caller per order key.
     */
    public static void logOtcOrder(CombatEngineAPI engine, BattleGroupState battleGroup, String orderText) {
        if (battleGroup == null || orderText == null) return;
        String sideTag = resolveOtcTag(battleGroup);
        float now = engine != null ? engine.getTotalElapsedTime(false) : 0f;
        emitLogLine(now, String.format("%s (%s) Order [%s]: %s",
                sideTag, battleGroup.otc.personalityId, battleGroup.authority.shortName, orderText));

        CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
        if (ui != null && DivisionAISettings.isOtcSignalsEnabled(battleGroup.side)) {
            Color prefixColor = (battleGroup.side == 0) ? Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor();
            if (battleGroup.nomenclatureCategory == DivisionNomenclature.NomenclatureCategory.ABYSSAL_ERROR) {
                ui.addMessage(1, Misc.getNegativeHighlightColor(), sideTag + " ERROR");
            } else {
                ui.addMessage(
                        1,
                        prefixColor, sideTag + " ",
                        Misc.getTextColor(), orderText);
            }
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
        if (div.lastLogAnnouncementTime > 0f && (now - div.lastLogAnnouncementTime) < 3.5f) {
            return;
        }
        if ((now - div.lastLogAnnouncementTime) < MIN_DIVISION_LOG_INTERVAL
                && div.currentIntent == DivisionIntentType.CLOSE_UP) {
            return;
        }

        div.lastLoggedSignature = sig;
        div.lastLogAnnouncementTime = now;

        String reasonSuffix = (tacticalReason != null && !tacticalReason.isEmpty())
                ? " (" + tacticalReason + ")"
                : "";

        String actorName = DivisionNomenclature.resolveDynamicDivisionDisplayName(
                div.nomenclatureCategory,
                div.archetype,
                div.divisionNumber,
                div.displayName);

        String fullLine;
        if (!cleanTarget.isEmpty()) {
            fullLine = String.format(
                    "\"%s\" intends to %s \"%s\"%s.",
                    actorName,
                    div.currentIntent.verbPhrase,
                    cleanTarget,
                    reasonSuffix);
        } else {
            fullLine = String.format(
                    "\"%s\" intends to %s%s.",
                    actorName,
                    div.currentIntent.verbPhrase,
                    reasonSuffix);
        }

        String sideTag = (div.side == 0)
                ? (div.flagship != null && div.flagship.isAlly() ? "[Allied]" : "[Friendly]")
                : "[Enemy]";
        emitLogLine(now, "[DivisionAI] " + sideTag + " " + fullLine);

        CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
        if (ui != null && DivisionAISettings.shouldShowIntentOnHud(div.side, div.currentIntent)) {
            if (div.nomenclatureCategory == DivisionNomenclature.NomenclatureCategory.ABYSSAL_ERROR) {
                ui.addMessage(1, Misc.getNegativeHighlightColor(), "ERROR");
                return;
            }
            Color actorColor = (div.side == 0) ? Misc.getBrightPlayerColor() : Misc.getNegativeHighlightColor();
            Color targetColor = resolveTargetColor(div, cleanTarget);
            Color textColor = Misc.getTextColor();
            Color grayColor = Misc.getGrayColor();

            if (!cleanTarget.isEmpty()) {
                if (!reasonSuffix.isEmpty()) {
                    ui.addMessage(
                            1,
                            actorColor, "\"" + actorName + "\"",
                            textColor, " intends to " + div.currentIntent.verbPhrase + " ",
                            targetColor, "\"" + cleanTarget + "\"",
                            grayColor, reasonSuffix + ".");
                } else {
                    ui.addMessage(
                            1,
                            actorColor, "\"" + actorName + "\"",
                            textColor, " intends to " + div.currentIntent.verbPhrase + " ",
                            targetColor, "\"" + cleanTarget + "\"",
                            textColor, ".");
                }
            } else {
                ui.addMessage(
                        1,
                        actorColor, "\"" + actorName + "\"",
                        textColor, " intends to " + div.currentIntent.verbPhrase,
                        grayColor, reasonSuffix + ".");
            }
        }
    }

    private static Color resolveTargetColor(DivisionState actor, String targetLabel) {
        if (targetLabel.startsWith("Enemy ")) {
            return Misc.getNegativeHighlightColor();
        }
        if (actor.currentIntent == DivisionIntentType.TAKE_SCREENING_STATION
                || actor.currentIntent == DivisionIntentType.REINFORCE_DIVISION
                || actor.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT) {
            return (actor.side == 0) ? Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor();
        }
        if (actor.currentIntent == DivisionIntentType.TAKE_AND_HOLD) {
            return Misc.getHighlightColor();
        }
        return (actor.side == 0) ? Misc.getNegativeHighlightColor() : Misc.getBrightPlayerColor();
    }
}
