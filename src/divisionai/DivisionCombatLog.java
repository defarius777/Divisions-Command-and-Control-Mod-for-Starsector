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

    private static Color safeBrightPlayerColor() {
        try {
            if (Global.getSettings() != null) return Misc.getBrightPlayerColor();
        } catch (Throwable ignored) {
        }
        return new Color(110, 230, 145);
    }

    private static Color safePositiveHighlightColor() {
        try {
            if (Global.getSettings() != null) return Misc.getPositiveHighlightColor();
        } catch (Throwable ignored) {
        }
        return new Color(110, 230, 145);
    }

    private static Color safeNegativeHighlightColor() {
        try {
            if (Global.getSettings() != null) return Misc.getNegativeHighlightColor();
        } catch (Throwable ignored) {
        }
        return new Color(255, 100, 100);
    }

    private static Color safeHighlightColor() {
        try {
            if (Global.getSettings() != null) return Misc.getHighlightColor();
        } catch (Throwable ignored) {
        }
        return new Color(255, 215, 90);
    }

    private static Color safeTextColor() {
        try {
            if (Global.getSettings() != null) return Misc.getTextColor();
        } catch (Throwable ignored) {
        }
        return new Color(225, 235, 245);
    }

    private static Color safeGrayColor() {
        try {
            if (Global.getSettings() != null) return Misc.getGrayColor();
        } catch (Throwable ignored) {
        }
        return new Color(155, 165, 175);
    }

    public static void logVoidstrikeCulminated(int side, int number, String reason, float now) {
        String sideName = side == 0 ? "Player" : (side == 1 ? "Enemy" : "Allied");
        emitLogLine(now, "[DivisionAI] " + sideName + " Voidstrike " + number + " culminated (" + reason + "); strike craft egress.");
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

        // Show friendly division formations in the HUD log only in Verbose mode when friendly division chatter is enabled
        CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
        DivisionAISettings.ChatterVerbosity verbosity = DivisionAISettings.getChatterVerbosity();
        if (ui != null
                && verbosity == DivisionAISettings.ChatterVerbosity.VERBOSE
                && div.side == 0
                && DivisionAISettings.isDivisionSignalsEnabled(div.side)) {
            Color divColor = safeBrightPlayerColor();
            Color textColor = safeTextColor();
            Color hlColor = safeHighlightColor();
            if (div.nomenclatureCategory == DivisionNomenclature.NomenclatureCategory.ABYSSAL_ERROR) {
                ui.addMessage(1, safeNegativeHighlightColor(), "ERROR");
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
        DivisionAISettings.ChatterVerbosity verbosity = DivisionAISettings.getChatterVerbosity();
        if (ui != null
                && verbosity != DivisionAISettings.ChatterVerbosity.OFF
                && DivisionAISettings.isOtcSignalsEnabled(battleGroup.side)) {
            boolean phaseChanged = (battleGroup.lastOtcHudPhase != battleGroup.currentPhase);
            float hudCooldown = (verbosity == DivisionAISettings.ChatterVerbosity.VERBOSE)
                    ? MIN_OTC_LOG_INTERVAL
                    : (verbosity == DivisionAISettings.ChatterVerbosity.MINIMAL ? 30f : 20f);
            boolean allowHud = true;
            if (verbosity == DivisionAISettings.ChatterVerbosity.MINIMAL && battleGroup.side != 0 && !phaseChanged) {
                allowHud = false;
            } else if (!phaseChanged && (now - battleGroup.lastOtcHudLogTime) < hudCooldown) {
                allowHud = false;
            }
            if (allowHud) {
                battleGroup.lastOtcHudLogTime = now;
                battleGroup.lastOtcHudPhase = battleGroup.currentPhase;
                Color prefixColor = (battleGroup.side == 0) ? safePositiveHighlightColor() : safeNegativeHighlightColor();
                Color textColor = safeTextColor();
                Color hlColor = safeHighlightColor();
                if (battleGroup.nomenclatureCategory == DivisionNomenclature.NomenclatureCategory.ABYSSAL_ERROR) {
                    ui.addMessage(1, safeNegativeHighlightColor(), sideTag + " ERROR");
                } else if (verbosity == DivisionAISettings.ChatterVerbosity.VERBOSE) {
                    ui.addMessage(
                            1,
                            prefixColor, sideTag + " ",
                            hlColor, battleGroup.currentPhase.displayName,
                            textColor, " - " + directiveSummary);
                } else if (verbosity == DivisionAISettings.ChatterVerbosity.MINIMAL) {
                    ui.addMessage(
                            1,
                            prefixColor, sideTag + " ",
                            hlColor, formatMinimalOtcPhase(battleGroup.currentPhase));
                } else {
                    // STANDARD ("Medium") and OTC_ONLY: reduced tactical radio alternative
                    ui.addMessage(
                            1,
                            prefixColor, sideTag + " ",
                            hlColor, formatMediumOtcPhase(battleGroup.currentPhase),
                            textColor, " - " + formatMediumOtcDirective(directiveSummary));
                }
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
        DivisionAISettings.ChatterVerbosity verbosity = DivisionAISettings.getChatterVerbosity();
        if (ui != null
                && verbosity != DivisionAISettings.ChatterVerbosity.OFF
                && DivisionAISettings.isOtcSignalsEnabled(battleGroup.side)) {
            if (verbosity != DivisionAISettings.ChatterVerbosity.VERBOSE) {
                if (orderText.startsWith("No division free to reinforce")
                        || orderText.startsWith("Doubling:")
                        || orderText.startsWith("General chase:")) {
                    return;
                }
                boolean isHighPriorityOrder = orderText.startsWith("Execute:")
                        || orderText.startsWith("Committing the reserve")
                        || orderText.startsWith("Time-on-target strike:")
                        || orderText.contains("assumes Battle Group command");
                if (verbosity == DivisionAISettings.ChatterVerbosity.MINIMAL) {
                    if (battleGroup.side != 0 || !isHighPriorityOrder) {
                        return;
                    }
                } else {
                    if (battleGroup.side != 0 && orderText.startsWith("Main Effort")) {
                        return;
                    }
                    float orderCooldown = (verbosity == DivisionAISettings.ChatterVerbosity.OTC_ONLY) ? 6f : 10f;
                    if (!isHighPriorityOrder
                            && battleGroup.lastOtcHudLogTime > 0f
                            && (now - battleGroup.lastOtcHudLogTime) < orderCooldown) {
                        return;
                    }
                }
            }
            if (!orderText.startsWith("Execute:")
                    && !orderText.startsWith("Committing the reserve")
                    && !orderText.startsWith("Time-on-target strike:")) {
                battleGroup.lastOtcHudLogTime = now;
            }
            Color prefixColor = (battleGroup.side == 0) ? safePositiveHighlightColor() : safeNegativeHighlightColor();
            if (battleGroup.nomenclatureCategory == DivisionNomenclature.NomenclatureCategory.ABYSSAL_ERROR) {
                ui.addMessage(1, safeNegativeHighlightColor(), sideTag + " ERROR");
            } else {
                String hudOrderText;
                if (verbosity == DivisionAISettings.ChatterVerbosity.VERBOSE) {
                    hudOrderText = orderText;
                } else if (verbosity == DivisionAISettings.ChatterVerbosity.MINIMAL) {
                    hudOrderText = formatMinimalOtcOrder(orderText);
                } else {
                    hudOrderText = formatMediumOtcOrder(orderText);
                }
                ui.addMessage(
                        1,
                        prefixColor, sideTag + " ",
                        safeTextColor(), hudOrderText);
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
        // Always write the full verbose chatter to starsector.log regardless of HUD verbosity
        emitLogLine(now, "[DivisionAI] " + sideTag + " " + fullLine);

        CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
        if (ui != null && DivisionAISettings.shouldShowIntentOnHud(div.side, div.currentIntent)) {
            DivisionAISettings.ChatterVerbosity verbosity = DivisionAISettings.getChatterVerbosity();
            if (verbosity != DivisionAISettings.ChatterVerbosity.VERBOSE) {
                float minHudGap = (verbosity == DivisionAISettings.ChatterVerbosity.MINIMAL) ? 18.0f : 14.0f;
                boolean sameIntentAsLastHud = (div.lastHudIntent == div.currentIntent);
                if (div.lastHudAnnouncementTime > 0f && (now - div.lastHudAnnouncementTime) < minHudGap) {
                    return;
                }
                if (sameIntentAsLastHud && (now - div.lastHudAnnouncementTime) < 32.0f) {
                    return;
                }
            }
            div.lastHudAnnouncementTime = now;
            div.lastHudIntent = div.currentIntent;

            if (div.nomenclatureCategory == DivisionNomenclature.NomenclatureCategory.ABYSSAL_ERROR) {
                ui.addMessage(1, safeNegativeHighlightColor(), "ERROR");
                return;
            }
            Color actorColor = (div.side == 0) ? safeBrightPlayerColor() : safeNegativeHighlightColor();
            Color targetColor = resolveTargetColor(div, cleanTarget);
            Color textColor = safeTextColor();
            Color grayColor = safeGrayColor();

            if (verbosity == DivisionAISettings.ChatterVerbosity.VERBOSE) {
                boolean showReasonOnHud = !reasonSuffix.isEmpty();
                if (!cleanTarget.isEmpty()) {
                    if (showReasonOnHud) {
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
                    if (showReasonOnHud) {
                        ui.addMessage(
                                1,
                                actorColor, "\"" + actorName + "\"",
                                textColor, " intends to " + div.currentIntent.verbPhrase,
                                grayColor, reasonSuffix + ".");
                    } else {
                        ui.addMessage(
                                1,
                                actorColor, "\"" + actorName + "\"",
                                textColor, " intends to " + div.currentIntent.verbPhrase + ".");
                    }
                }
            } else if (verbosity == DivisionAISettings.ChatterVerbosity.MINIMAL) {
                // Minimal HUD dummy alternative: ultra-terse proword callout
                if (!cleanTarget.isEmpty()) {
                    ui.addMessage(
                            1,
                            actorColor, "\"" + actorName + "\"",
                            textColor, ": " + div.currentIntent.minimalPhrase + " ",
                            targetColor, "\"" + cleanTarget + "\"");
                } else {
                    ui.addMessage(
                            1,
                            actorColor, "\"" + actorName + "\"",
                            textColor, ": " + div.currentIntent.minimalPhrase);
                }
            } else {
                // Standard ("Medium") HUD dummy alternative: reduced radio call without "intends to" or reason suffix
                if (!cleanTarget.isEmpty()) {
                    ui.addMessage(
                            1,
                            actorColor, "\"" + actorName + "\"",
                            textColor, " " + div.currentIntent.mediumPhrase + " ",
                            targetColor, "\"" + cleanTarget + "\"",
                            textColor, ".");
                } else {
                    ui.addMessage(
                            1,
                            actorColor, "\"" + actorName + "\"",
                            textColor, " " + div.currentIntent.mediumPhrase + ".");
                }
            }
        }
    }

    private static Color resolveTargetColor(DivisionState actor, String targetLabel) {
        if (targetLabel.startsWith("Enemy ")) {
            return safeNegativeHighlightColor();
        }
        if (actor.currentIntent == DivisionIntentType.TAKE_SCREENING_STATION
                || actor.currentIntent == DivisionIntentType.REINFORCE_DIVISION
                || actor.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT) {
            return (actor.side == 0) ? safePositiveHighlightColor() : safeNegativeHighlightColor();
        }
        if (actor.currentIntent == DivisionIntentType.TAKE_AND_HOLD) {
            return safeHighlightColor();
        }
        return (actor.side == 0) ? safeNegativeHighlightColor() : safeBrightPlayerColor();
    }

    public static String formatMediumOtcPhase(DivisionModel.BattlePhase phase) {
        if (phase == null) return "Command";
        switch (phase) {
            case APPROACH:
                return "Approach";
            case GENERAL_ENGAGEMENT:
                return "Engagement";
            case COORDINATED_STRIKE:
                return "Coord. Strike";
            case REINFORCE_THE_LINE:
                return "Reinforce Line";
            case GENERAL_CHASE:
                return "General Chase";
            case FIGHTING_RETIREMENT:
                return "Retirement";
            default:
                return phase.displayName;
        }
    }

    public static String formatMinimalOtcPhase(DivisionModel.BattlePhase phase) {
        if (phase == null) return "OTC";
        switch (phase) {
            case APPROACH:
                return "Phase: Approach";
            case GENERAL_ENGAGEMENT:
                return "Phase: Engage";
            case COORDINATED_STRIKE:
                return "Phase: Strike";
            case REINFORCE_THE_LINE:
                return "Phase: Reinforce";
            case GENERAL_CHASE:
                return "Phase: Chase";
            case FIGHTING_RETIREMENT:
                return "Phase: Retire";
            default:
                return "Phase: " + phase.displayName;
        }
    }

    public static String formatMediumOtcDirective(String directiveSummary) {
        if (directiveSummary == null || directiveSummary.isEmpty()) return "";
        return directiveSummary
                .replace(", to take objectives and gain contact.", ".")
                .replace("; divisions engaging assigned targets.", ".")
                .replace("Synchronizing missile and carrier strike wings on ", "Strike wings -> ")
                .replace("Holding fire distribution; moving support to relieve ", "Relieving ")
                .replace("Enemy line broken; all fast divisions press the pursuit.", "Enemy broken; pursue.")
                .replace("Conducting fighting withdrawal toward reserve rally line.", "Fighting withdrawal.");
    }

    public static String formatMediumOtcOrder(String orderText) {
        if (orderText == null || orderText.isEmpty()) return "";
        String s = orderText
                .replace("Time-on-target strike: ", "ToT Strike: ")
                .replace(" division(s) to marshal against ", " div(s) marshal vs ")
                .replace(" division to marshal", " div marshal")
                .replace("Execute: ", "Execute Strike: ")
                .replace(" division(s) strike ", " div(s) -> ")
                .replace(" together", "")
                .replace("Supporting divisions envelop ", "Envelop ")
                .replace(" from port and starboard.", " (pincer).")
                .replace("Committing the reserve: ", "Commit Reserve: ")
                .replace("Reserve committed: ", "Commit Reserve: ")
                .replace(" advances to exploit.", " -> exploit.");
        int parenIdx = s.indexOf(" (");
        if (parenIdx > 0 && s.endsWith(").")) {
            s = s.substring(0, parenIdx) + ".";
        } else if (parenIdx > 0 && s.endsWith(")")) {
            s = s.substring(0, parenIdx);
        }
        return s;
    }

    public static String formatMinimalOtcOrder(String orderText) {
        if (orderText == null || orderText.isEmpty()) return "";
        int q1 = orderText.indexOf('"');
        int q2 = (q1 >= 0) ? orderText.indexOf('"', q1 + 1) : -1;
        String firstQuoted = (q1 >= 0 && q2 > q1) ? orderText.substring(q1, q2 + 1) : "";
        if (orderText.startsWith("Time-on-target strike:")) {
            return firstQuoted.isEmpty() ? "ToT Marshal" : ("ToT Marshal -> " + firstQuoted);
        }
        if (orderText.startsWith("Execute:")) {
            return firstQuoted.isEmpty() ? "Execute Strike!" : ("Execute Strike -> " + firstQuoted);
        }
        if (orderText.startsWith("Committing the reserve") || orderText.startsWith("Reserve committed:")) {
            return firstQuoted.isEmpty() ? "Commit Reserve" : ("Reserve -> " + firstQuoted);
        }
        if (orderText.contains("reinforce")) {
            return firstQuoted.isEmpty() ? "Reinforce Line" : ("Reinforce -> " + firstQuoted);
        }
        if (orderText.startsWith("Supporting divisions envelop")) {
            return firstQuoted.isEmpty() ? "Envelop Target" : ("Pincer -> " + firstQuoted);
        }
        if (orderText.contains("assumes Battle Group command")) {
            return "Alpha Core assumes OTC";
        }
        return formatMediumOtcOrder(orderText);
    }
}
