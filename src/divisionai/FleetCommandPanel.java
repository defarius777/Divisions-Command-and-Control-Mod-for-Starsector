package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.DivisionState;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.List;
import java.util.Locale;

/**
 * Top-bar 940x204 Fleet Command panel rendered in the pre-battle and mid-battle Deployment Dialog.
 * Extracted from {@link DivisionDeploymentDialogOrganizer}.
 */
public final class FleetCommandPanel {

    private FleetCommandPanel() {
    }

    public static CustomPanelAPI createPanel(
            final DivisionDeploymentDialogOrganizer organizer,
            final CombatEngineAPI engine,
            final Object fdd,
            final UIPanelAPI innerPanel,
            final Object reservesGrid,
            final float width,
            final float height,
            final List<FleetMemberAPI> deployedMembers,
            final List<FleetMemberAPI> reserveMembers) {

        final boolean preBattle = deployedMembers == null || deployedMembers.isEmpty();
        final FleetMemberAPI[] currentFlagshipRef = new FleetMemberAPI[]{
                organizer.resolvePlayerFlagshipMember(fdd, preBattle, deployedMembers, reserveMembers)
        };
        final BattleGroupState playerGroup = organizer.getPlayerGroup();
        final boolean[] isAlphaCoreRef = new boolean[]{
                DivisionDeploymentDialogOrganizer.isFlagshipAlphaCoreEquipped(engine, currentFlagshipRef[0], playerGroup)
        };
        final String[] portraitSpritePathRef = new String[]{DivisionDeploymentDialogOrganizer.ALPHA_CORE_PORTRAIT_SPRITE};

        final float topBarH = 20f;
        final float portraitTopY = topBarH + 6f;
        final float portraitH = Math.max(82f, Math.min(92f, height - topBarH - 42f));
        final float[] portraitWRef = new float[]{
                isAlphaCoreRef[0]
                        ? Math.min(148f, portraitH * DivisionDeploymentDialogOrganizer.ALPHA_CORE_PORTRAIT_STRETCH_ASPECT)
                        : portraitH
        };
        final float portraitX = 12f;
        final float leftSectionW = Math.round(width * 0.46f);
        final float rightSectionX = leftSectionW + 8f;
        final float rightSectionW = Math.max(280f, width - rightSectionX - 6f);
        final float cmdColW = Math.round((rightSectionW - 6f) * 0.48f);
        final float cmdColX = rightSectionX;
        final float formColX = rightSectionX + cmdColW + 6f;
        final float formColW = Math.max(140f, rightSectionW - cmdColW - 6f);
        final float rightStatusMaxW = Math.max(180f, rightSectionW - 204f);
        final float titleTopY = topBarH + 10f;
        final float[] masterBasePos = new float[]{portraitX + portraitWRef[0] + 12f, titleTopY};
        final PositionAPI[] masterPosRef = new PositionAPI[1];
        final PositionAPI[] masterGhostPosRef = new PositionAPI[1];
        final PositionAPI[] prePosRef = new PositionAPI[1];
        final PositionAPI[] sufPosRef = new PositionAPI[1];
        final PositionAPI[] sufLine2PosRef = new PositionAPI[1];
        final PositionAPI[] stdTitlePosRef = new PositionAPI[1];
        final PositionAPI[] stdTitleLine2PosRef = new PositionAPI[1];
        final PositionAPI[] flagshipPosRef = new PositionAPI[1];
        final PositionAPI[] flagshipLine2PosRef = new PositionAPI[1];
        final PositionAPI[] profilePosRef = new PositionAPI[1];
        final PositionAPI[] profileLine2PosRef = new PositionAPI[1];
        final CustomPanelAPI[] panelRef = new CustomPanelAPI[1];
        final ButtonAPI[] prevButtonRef = new ButtonAPI[1];
        final ButtonAPI[] nextButtonRef = new ButtonAPI[1];
        final ButtonAPI[] entryButtonRef = new ButtonAPI[1];
        final ButtonAPI[] authPrevButtonRef = new ButtonAPI[1];
        final ButtonAPI[] authNextButtonRef = new ButtonAPI[1];
        final ButtonAPI[] changeOtcButtonRef = new ButtonAPI[1];
        final float[] liveEngagementLevel = new float[]{0f};
        final float[] liveCommitmentRatio = new float[]{0f};
        final float[] liveCohesionRatio = new float[]{1f};

        final LabelAPI headerBannerLabel = Global.getSettings().createLabel("FLEET COMMAND", Fonts.ORBITRON_12);
        headerBannerLabel.setColor(isAlphaCoreRef[0] ? new Color(110, 235, 255, 255) : new Color(255, 215, 80, 255));
        final LabelAPI otcStandardTitleLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_20AA);
        otcStandardTitleLabel.setColor(new Color(255, 222, 90, 255));
        final LabelAPI otcStandardTitleLine2Label = Global.getSettings().createLabel("", Fonts.ORBITRON_20AA);
        otcStandardTitleLine2Label.setColor(new Color(255, 222, 90, 255));
        final LabelAPI cogPrefixLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        cogPrefixLabel.setColor(new Color(120, 235, 255, 255));
        final LabelAPI cogMasterGhostLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        cogMasterGhostLabel.setColor(new Color(255, 65, 95, 170));
        final LabelAPI cogMasterLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        cogMasterLabel.setColor(new Color(235, 252, 255, 255));
        final LabelAPI cogSuffixLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_20AA);
        cogSuffixLabel.setColor(new Color(150, 238, 255, 255));
        final LabelAPI cogSuffixLine2Label = Global.getSettings().createLabel("", Fonts.ORBITRON_20AA);
        cogSuffixLine2Label.setColor(new Color(150, 238, 255, 255));
        final LabelAPI flagshipLabel = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        flagshipLabel.setColor(new Color(215, 230, 242, 245));
        final LabelAPI flagshipLine2Label = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        flagshipLine2Label.setColor(new Color(215, 230, 242, 245));
        final LabelAPI profileLabel = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        profileLabel.setColor(new Color(175, 215, 235, 240));
        final LabelAPI profileLine2Label = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        profileLine2Label.setColor(new Color(175, 215, 235, 240));
        final LabelAPI cmdStyleHeaderLabel = Global.getSettings().createLabel(
                preBattle ? "COMMAND STYLE" : "", Fonts.ORBITRON_12);
        cmdStyleHeaderLabel.setColor(new Color(255, 215, 80, 255));
        cmdStyleHeaderLabel.setAlignment(Alignment.MID);
        final LabelAPI cmdStyleNameLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        cmdStyleNameLabel.setColor(new Color(255, 232, 130, 255));
        cmdStyleNameLabel.setAlignment(Alignment.MID);
        final LabelAPI selectorHeaderLabel = Global.getSettings().createLabel(
                preBattle ? "FLEET FORMATION" : "FLEET STATUS", Fonts.ORBITRON_12);
        selectorHeaderLabel.setColor(new Color(255, 215, 80, 255));
        selectorHeaderLabel.setAlignment(preBattle ? Alignment.MID : Alignment.LMID);
        final LabelAPI formationNameLabel = Global.getSettings().createLabel("", preBattle ? Fonts.ORBITRON_12 : Fonts.ORBITRON_20AA);
        formationNameLabel.setColor(new Color(235, 245, 255, 255));
        formationNameLabel.setAlignment(preBattle ? Alignment.MID : Alignment.LMID);
        final LabelAPI engagementLabel = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        engagementLabel.setColor(new Color(235, 245, 255, 255));
        final LabelAPI commitmentLabel = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        commitmentLabel.setColor(new Color(225, 238, 248, 250));
        final LabelAPI cohesionLabel = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        cohesionLabel.setColor(new Color(215, 240, 248, 245));
        final LabelAPI phaseLabel = Global.getSettings().createLabel("", "graphics/fonts/victor14.fnt");
        phaseLabel.setColor(new Color(145, 225, 245, 235));

        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(headerBannerLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(otcStandardTitleLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(otcStandardTitleLine2Label);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(cogPrefixLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(cogMasterGhostLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(cogMasterLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(cogSuffixLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(cogSuffixLine2Label);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(flagshipLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(flagshipLine2Label);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(profileLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(profileLine2Label);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(cmdStyleHeaderLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(cmdStyleNameLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(selectorHeaderLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(formationNameLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(engagementLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(commitmentLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(cohesionLabel);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(phaseLabel);

        BaseCustomUIPanelPlugin fcPlugin = new BaseCustomUIPanelPlugin() {
            private String lastOtcLayoutKey = null;
            private String lastSelectorLayoutKey = null;

            private void refreshOtcDisplay() {
                FleetMemberAPI fm = organizer.resolvePlayerFlagshipMember(fdd, preBattle, deployedMembers, reserveMembers);
                currentFlagshipRef[0] = fm;
                boolean alpha = DivisionDeploymentDialogOrganizer.isFlagshipAlphaCoreEquipped(engine, fm, playerGroup);
                isAlphaCoreRef[0] = alpha;
                if (playerGroup != null && fm != null && fm.getId() != null) {
                    if (playerGroup.preferredOtcShipId == null || playerGroup.preferredOtcShipId.isEmpty()) {
                        playerGroup.preferredOtcShipId = fm.getId();
                    }
                    if (preBattle && !DivisionOtcCommandManager.isExplicitPlayerOtcChoiceMade(playerGroup)) {
                        PersonAPI autoCap = DivisionOtcCommandManager.resolveCandidatePerson(fm, null);
                        String autoName = (autoCap != null && autoCap.getNameString() != null && !autoCap.getNameString().trim().isEmpty())
                                ? autoCap.getNameString().trim()
                                : "Fleet Commander";
                        playerGroup.otc = new DivisionModel.CommanderProfile(autoCap, autoName);
                    }
                    if (alpha) {
                        playerGroup.useConvergence = true;
                        DivisionOtcCommandManager.markInitialPlayerOtcAlphaCore(true);
                    } else if (preBattle) {
                        playerGroup.useConvergence = false;
                        DivisionOtcCommandManager.markInitialPlayerOtcAlphaCore(false);
                        DivisionOtcCommandManager.setPlayerConvergenceFlagshipOverride(null);
                        DivisionOtcCommandManager.clearPlayerAlphaCoreOtcLockInBattle(playerGroup);
                    }
                }
                portraitWRef[0] = alpha
                        ? Math.min(148f, portraitH * DivisionDeploymentDialogOrganizer.ALPHA_CORE_PORTRAIT_STRETCH_ASPECT)
                        : portraitH;

                PersonAPI resolvedPerson = (fm != null && fm.getCaptain() != null && !fm.getCaptain().isDefault())
                        ? fm.getCaptain()
                        : null;
                if (resolvedPerson == null) {
                    try {
                        if (Global.getSector() != null && Global.getSector().getPlayerPerson() != null) {
                            resolvedPerson = Global.getSector().getPlayerPerson();
                        }
                    } catch (Throwable ignored) {
                    }
                }
                String commanderName = (resolvedPerson != null && resolvedPerson.getNameString() != null
                        && !resolvedPerson.getNameString().trim().isEmpty())
                        ? resolvedPerson.getNameString().trim()
                        : (playerGroup != null && playerGroup.otc != null && playerGroup.otc.commanderName != null
                        ? playerGroup.otc.commanderName
                        : "Fleet Commander");
                String flagshipName = (fm != null && fm.getShipName() != null && !fm.getShipName().trim().isEmpty())
                        ? fm.getShipName().trim()
                        : "FLAGSHIP";
                String hullClassName = (fm != null && fm.getHullSpec() != null
                        && fm.getHullSpec().getHullNameWithDashClass() != null)
                        ? fm.getHullSpec().getHullNameWithDashClass()
                        : "Command Hull";
                String candidatePortrait = (resolvedPerson != null && resolvedPerson.getPortraitSprite() != null
                        && !resolvedPerson.getPortraitSprite().trim().isEmpty())
                        ? resolvedPerson.getPortraitSprite().trim()
                        : DivisionDeploymentDialogOrganizer.ALPHA_CORE_PORTRAIT_SPRITE;
                if (alpha) candidatePortrait = DivisionDeploymentDialogOrganizer.ALPHA_CORE_PORTRAIT_SPRITE;
                portraitSpritePathRef[0] = candidatePortrait;

                boolean otcDeployedOrSelected = organizer.isOtcMemberSelectedOrDeployed(engine, fdd, fm, deployedMembers);
                PermanentDivisionManager.FleetDoctrineConfig doc = PermanentDivisionManager.getFleetDoctrine();
                float now = (engine != null) ? engine.getTotalElapsedTime(false) : 0f;
                boolean disabledByAlpha = DivisionOtcCommandManager.isPlayerOtcLockedByAlphaCoreInBattle(
                        engine, fm, playerGroup, preBattle);
                boolean emergency = DivisionOtcCommandManager.isEmergencyPlayerOtcSelectionPending(playerGroup);
                boolean disabledByDisruption = !preBattle && !emergency
                        && playerGroup != null && DivisionOtcCommandManager.isOtcDisabled(playerGroup, now);
                int disruptRemSec = (playerGroup != null && DivisionOtcCommandManager.isOtcDisabled(playerGroup, now))
                        ? Math.round(DivisionOtcCommandManager.getOtcDisabledSecondsRemaining(playerGroup, now))
                        : -1;
                String layoutKey = (fm != null ? fm.getId() : "null")
                        + "|" + alpha
                        + "|" + otcDeployedOrSelected
                        + "|" + commanderName
                        + "|" + flagshipName
                        + "|" + hullClassName
                        + "|" + doc.getOtcAuthority().name()
                        + "|" + disabledByAlpha
                        + "|" + disabledByDisruption
                        + "|" + disruptRemSec;
                if (layoutKey.equals(lastOtcLayoutKey)) {
                    return;
                }
                lastOtcLayoutKey = layoutKey;
                if (!otcDeployedOrSelected) {
                    headerBannerLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                            "FLEET COMMAND - OTC NOT DEPLOYED", headerBannerLabel, Math.max(180f, leftSectionW - 20f)));
                    headerBannerLabel.setColor(new Color(255, 95, 75, 255));
                } else {
                    headerBannerLabel.setText("FLEET COMMAND");
                    headerBannerLabel.setColor(alpha ? new Color(110, 235, 255, 255) : new Color(255, 215, 80, 255));
                }
                float textLeftX = portraitX + portraitWRef[0] + 12f;
                float maxLeftTextW = Math.max(160f, leftSectionW - textLeftX - 6f);
                float maxWrapW = Math.max(110f, maxLeftTextW - 14f);
                float safeLabelBoxW = maxLeftTextW;
                float flagshipTop;
                float profileTop;

                if (alpha) {
                    otcStandardTitleLabel.setText("");
                    otcStandardTitleLine2Label.setText("");
                    if (stdTitlePosRef[0] != null) stdTitlePosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);
                    if (stdTitleLine2PosRef[0] != null) stdTitleLine2PosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);

                    String prefix = "ORDO COGITATOR/";
                    String masterWord = "MASTER";
                    String shipLine = flagshipName.toUpperCase(Locale.ROOT);
                    float effTitleTopY = topBarH + 6f;
                    if (prePosRef[0] != null) prePosRef[0].setSize(safeLabelBoxW, 14f).inTL(textLeftX, effTitleTopY);
                    cogPrefixLabel.setText(prefix);
                    float preW = CampaignFleetTooltipOrganizer.measureLineWidth(prefix, cogPrefixLabel);
                    float masterW = CampaignFleetTooltipOrganizer.measureLineWidth(masterWord, cogMasterLabel);
                    boolean wrapMasterLine = (preW + masterW + 6f > maxWrapW);
                    float masterX = wrapMasterLine ? textLeftX : (textLeftX + preW);
                    float masterY = wrapMasterLine ? (effTitleTopY + 14f) : effTitleTopY;
                    float remAfterPre = wrapMasterLine ? safeLabelBoxW : Math.max(60f, safeLabelBoxW - preW);
                    if (masterGhostPosRef[0] != null) masterGhostPosRef[0].setSize(remAfterPre, 14f).inTL(masterX, masterY);
                    if (masterPosRef[0] != null) masterPosRef[0].setSize(remAfterPre, 14f).inTL(masterX, masterY);
                    cogMasterGhostLabel.setText(masterWord);
                    cogMasterLabel.setText(masterWord);

                    float shipBaseTopY = wrapMasterLine ? (effTitleTopY + 30f) : (effTitleTopY + 16f);
                    // ORBITRON_20AA glyphs are wider than standard 15px fonts; use a tighter wrap budget so long AI ship names always wrap cleanly
                    float shipWrapBudget = Math.max(110f, maxWrapW - 12f);
                    String[] shipLines = DivisionDeploymentDialogOrganizer.wrapOtcTextIntoTwoLines(shipLine, cogSuffixLabel, shipWrapBudget, shipWrapBudget);
                    if (shipLines.length >= 2 && !shipLines[1].isEmpty()) {
                        if (sufPosRef[0] != null) sufPosRef[0].setSize(safeLabelBoxW, 18f).inTL(textLeftX, shipBaseTopY);
                        if (sufLine2PosRef[0] != null) sufLine2PosRef[0].setSize(safeLabelBoxW, 18f).inTL(textLeftX, shipBaseTopY + 18f);
                        cogSuffixLabel.setText(shipLines[0]);
                        cogSuffixLine2Label.setText(shipLines[1]);
                        flagshipTop = shipBaseTopY + 39f;
                    } else {
                        if (sufPosRef[0] != null) sufPosRef[0].setSize(safeLabelBoxW, 19f).inTL(textLeftX, shipBaseTopY + 2f);
                        if (sufLine2PosRef[0] != null) sufLine2PosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);
                        cogSuffixLabel.setText(shipLines[0]);
                        cogSuffixLine2Label.setText("");
                        flagshipTop = shipBaseTopY + 25f;
                    }
                    masterBasePos[0] = masterX;
                    masterBasePos[1] = masterY;
                } else {
                    cogPrefixLabel.setText("");
                    cogMasterGhostLabel.setText("");
                    cogMasterLabel.setText("");
                    cogSuffixLabel.setText("");
                    cogSuffixLine2Label.setText("");
                    if (sufPosRef[0] != null) sufPosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);
                    if (sufLine2PosRef[0] != null) sufLine2PosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);
                    String stdTitleRaw = DivisionDeploymentDialogOrganizer.formatFleetCommandOtcTitle(commanderName, flagshipName, false);
                    float stdWrapBudget = Math.max(120f, maxWrapW - 8f);
                    String[] stdLines = DivisionDeploymentDialogOrganizer.wrapOtcTextIntoTwoLines(stdTitleRaw, otcStandardTitleLabel, stdWrapBudget, stdWrapBudget);
                    if (stdLines.length >= 2 && !stdLines[1].isEmpty()) {
                        if (stdTitlePosRef[0] != null) stdTitlePosRef[0].setSize(safeLabelBoxW, 18f).inTL(textLeftX, titleTopY - 2f);
                        if (stdTitleLine2PosRef[0] != null) stdTitleLine2PosRef[0].setSize(safeLabelBoxW, 18f).inTL(textLeftX, titleTopY + 16f);
                        otcStandardTitleLabel.setText(stdLines[0]);
                        otcStandardTitleLine2Label.setText(stdLines[1]);
                        flagshipTop = titleTopY + 37f;
                    } else {
                        if (stdTitlePosRef[0] != null) stdTitlePosRef[0].setSize(safeLabelBoxW, 20f).inTL(textLeftX, titleTopY);
                        if (stdTitleLine2PosRef[0] != null) stdTitleLine2PosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);
                        otcStandardTitleLabel.setText(stdLines[0]);
                        otcStandardTitleLine2Label.setText("");
                        flagshipTop = titleTopY + 26f;
                    }
                }

                String personality;
                if (alpha) {
                    personality = "Alpha Core";
                } else if (resolvedPerson != null && resolvedPerson.getPersonalityAPI() != null
                        && resolvedPerson.getPersonalityAPI().getDisplayName() != null
                        && !resolvedPerson.getPersonalityAPI().getDisplayName().trim().isEmpty()) {
                    personality = resolvedPerson.getPersonalityAPI().getDisplayName().trim();
                } else if (resolvedPerson != null && resolvedPerson.getPersonalityAPI() != null
                        && resolvedPerson.getPersonalityAPI().getId() != null
                        && !resolvedPerson.getPersonalityAPI().getId().trim().isEmpty()) {
                    String rawP = resolvedPerson.getPersonalityAPI().getId().trim().toLowerCase(Locale.ROOT);
                    personality = Character.toUpperCase(rawP.charAt(0)) + rawP.substring(1);
                } else if (playerGroup != null && playerGroup.otc != null && playerGroup.otc.personalityId != null
                        && !playerGroup.otc.personalityId.isEmpty()) {
                    String rawP = playerGroup.otc.personalityId.trim().toLowerCase(Locale.ROOT);
                    personality = Character.toUpperCase(rawP.charAt(0)) + rawP.substring(1);
                } else {
                    personality = "Steady";
                }
                String flagshipLine = alpha
                        ? hullClassName
                        : String.format(Locale.ROOT, "%s (%s)", flagshipName, hullClassName);
                String profileLine;
                if (!otcDeployedOrSelected) {
                    profileLine = "Not Deployed: -75% Max CP, -50% Regen";
                    profileLabel.setColor(new Color(255, 120, 95, 250));
                    profileLine2Label.setColor(new Color(255, 120, 95, 250));
                } else if (playerGroup != null && DivisionOtcCommandManager.isOtcDisabled(playerGroup, now)) {
                    profileLine = String.format(Locale.ROOT, "Disrupted: %.0fs | %s",
                            DivisionOtcCommandManager.getOtcDisabledSecondsRemaining(playerGroup, now),
                            doc.getOtcAuthority().shortName);
                    profileLabel.setColor(new Color(255, 165, 90, 245));
                    profileLine2Label.setColor(new Color(255, 165, 90, 245));
                } else {
                    profileLine = String.format(Locale.ROOT, "%s | %s", personality, doc.getOtcAuthority().shortName);
                    profileLabel.setColor(new Color(175, 215, 235, 240));
                    profileLine2Label.setColor(new Color(175, 215, 235, 240));
                }

                String[] flagLines = DivisionDeploymentDialogOrganizer.wrapOtcTextIntoTwoLines(flagshipLine, flagshipLabel, maxWrapW, maxWrapW);
                if (flagLines.length >= 2 && !flagLines[1].isEmpty()) {
                    if (flagshipPosRef[0] != null) flagshipPosRef[0].setSize(safeLabelBoxW, 14f).inTL(textLeftX, flagshipTop);
                    flagshipLabel.setText(flagLines[0]);
                    if (flagshipLine2PosRef[0] != null) flagshipLine2PosRef[0].setSize(safeLabelBoxW, 14f).inTL(textLeftX, flagshipTop + 15f);
                    flagshipLine2Label.setText(flagLines[1]);
                    profileTop = flagshipTop + 32f;
                } else {
                    if (flagshipPosRef[0] != null) flagshipPosRef[0].setSize(safeLabelBoxW, 15f).inTL(textLeftX, flagshipTop);
                    flagshipLabel.setText(flagLines[0]);
                    if (flagshipLine2PosRef[0] != null) flagshipLine2PosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);
                    flagshipLine2Label.setText("");
                    profileTop = flagshipTop + 19f;
                }

                String[] profLines = DivisionDeploymentDialogOrganizer.wrapOtcTextIntoTwoLines(profileLine, profileLabel, maxWrapW, maxWrapW);
                if (profLines.length >= 2 && !profLines[1].isEmpty()) {
                    if (profilePosRef[0] != null) profilePosRef[0].setSize(safeLabelBoxW, 14f).inTL(textLeftX, profileTop);
                    profileLabel.setText(profLines[0]);
                    if (profileLine2PosRef[0] != null) profileLine2PosRef[0].setSize(safeLabelBoxW, 14f).inTL(textLeftX, profileTop + 14f);
                    profileLine2Label.setText(profLines[1]);
                } else {
                    if (profilePosRef[0] != null) profilePosRef[0].setSize(safeLabelBoxW, 15f).inTL(textLeftX, profileTop);
                    profileLabel.setText(profLines[0]);
                    if (profileLine2PosRef[0] != null) profileLine2PosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);
                    profileLine2Label.setText("");
                }

                ButtonAPI changeBtn = changeOtcButtonRef[0];
                if (changeBtn != null) {
                    changeBtn.getPosition().setSize(portraitWRef[0], 18f).inTL(portraitX, portraitTopY + portraitH + 5f);
                    changeBtn.setEnabled(!disabledByAlpha && !disabledByDisruption);
                }
            }

            private void updateSelectorText() {
                PermanentDivisionManager.FleetDoctrineConfig doc = PermanentDivisionManager.getFleetDoctrine();
                PermanentDivisionManager.FleetWideFormation fw;
                if (preBattle) {
                    fw = doc.getFleetWideFormation();
                    PermanentDivisionManager.OtcAuthority auth = doc.getOtcAuthority();
                    if (playerGroup != null) {
                        playerGroup.authority = auth;
                    }
                    String selKey = "PRE|" + auth.name() + "|" + fw.name() + "|" + doc.isOrchestratedAdvance();
                    if (selKey.equals(lastSelectorLayoutKey)) {
                        return;
                    }
                    lastSelectorLayoutKey = selKey;
                    cmdStyleNameLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                            auth.dropdownLabel, cmdStyleNameLabel, cmdColW - 64f));
                } else {
                    fw = (playerGroup != null
                            && playerGroup.activeFleetWideFormation != null
                            && playerGroup.activeFleetWideFormation != PermanentDivisionManager.FleetWideFormation.DOCTRINAL_AUTO)
                            ? playerGroup.activeFleetWideFormation
                            : doc.getFleetWideFormation();
                    String selKey = "LIVE|" + fw.name();
                    if (selKey.equals(lastSelectorLayoutKey)) {
                        return;
                    }
                    lastSelectorLayoutKey = selKey;
                }
                formationNameLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                        fw.displayName, formationNameLabel,
                        preBattle ? formColW - 64f : rightStatusMaxW));
                ButtonAPI entry = entryButtonRef[0];
                if (preBattle && entry != null) {
                    boolean orch = doc.isOrchestratedAdvance();
                    entry.setText("Orchestrated Advance: " + (orch ? "On" : "Off"));
                    if (orch) entry.highlight();
                    else entry.unhighlight();
                }
            }

            private void updateLiveStatusText() {
                float eng = 0f;
                if (organizer.getMusicController() != null) eng = Math.max(eng, organizer.getMusicController().getCurrentBattleEngagementLevel());
                if (playerGroup != null) {
                    eng = Math.max(eng, Math.max(playerGroup.smoothedEngagementLevel, playerGroup.fleetEngagementLevel));
                }
                BattleGroupState enemyGroup = organizer.getEnemyGroup();
                if (enemyGroup != null) {
                    eng = Math.max(eng, Math.max(enemyGroup.smoothedEngagementLevel, enemyGroup.fleetEngagementLevel));
                }
                liveEngagementLevel[0] = Math.max(0f, Math.min(1f, eng));

                float depDp = 0f;
                int depCount = 0;
                if (deployedMembers != null) {
                    for (FleetMemberAPI m : deployedMembers) {
                        if (m != null) {
                            depCount++;
                            depDp += m.getDeploymentPointsCost();
                        }
                    }
                }
                float resDp = 0f;
                int resCount = 0;
                if (reserveMembers != null) {
                    for (FleetMemberAPI m : reserveMembers) {
                        if (m != null) {
                            resCount++;
                            resDp += m.getDeploymentPointsCost();
                        }
                    }
                }
                liveCommitmentRatio[0] = Math.max(0f, Math.min(1f, depDp / Math.max(1f, depDp + resDp)));

                float coh = 1.0f;
                int activeDivCount = (playerGroup != null) ? playerGroup.divisions.size() : 0;
                int totalDivSpecs = PermanentDivisionManager.getNonEmptyPermanentDivisions().size();
                if (playerGroup != null && !playerGroup.divisions.isEmpty()) {
                    float sumReady = 0f;
                    int cnt = 0;
                    for (DivisionState d : playerGroup.divisions) {
                        if (d != null && d.isCombatEffective()) {
                            float dressScore = Math.max(0.25f, Math.min(1.0f,
                                    1.25f - Math.max(0f, d.stationKeepingRatio - 0.65f) * 0.55f));
                            float fluxReady = 1.0f - d.avgFluxLevel * 0.65f;
                            sumReady += 0.5f * (dressScore + fluxReady);
                            cnt++;
                        }
                    }
                    if (cnt > 0) coh = sumReady / cnt;
                }
                liveCohesionRatio[0] = Math.max(0f, Math.min(1f, coh));

                String phaseName = (playerGroup != null && playerGroup.currentPhase != null)
                        ? playerGroup.currentPhase.displayName
                        : "Deployment";
                String engState = (eng >= 0.70f) ? "Decisive brawl"
                        : (eng >= 0.38f ? "General engagement"
                        : (eng >= 0.12f ? "Skirmish contact" : "Maneuver"));
                engagementLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                        String.format(Locale.ROOT, "ENGAGEMENT %.0f%% - %s", eng * 100f, engState),
                        engagementLabel, rightStatusMaxW));
                commitmentLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                        String.format(Locale.ROOT, "COMMITTED %.0f%% (%.0f DP)",
                                liveCommitmentRatio[0] * 100f, depDp),
                        commitmentLabel, rightStatusMaxW));
                cohesionLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                        String.format(Locale.ROOT, "COHESION %.0f%% | Divisions %d/%d",
                                coh * 100f, activeDivCount, Math.max(activeDivCount, totalDivSpecs)),
                        cohesionLabel, rightStatusMaxW));
                phaseLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                        "PHASE: " + phaseName, phaseLabel, rightStatusMaxW));
            }

            private void pollButtons() {
                organizer.runActiveOtcSelectorPollHook();
                ButtonAPI changeBtn = changeOtcButtonRef[0];
                if (changeBtn != null && changeBtn.isChecked()) {
                    changeBtn.setChecked(false);
                    if (!DivisionOtcCommandManager.isPlayerOtcLockedByAlphaCoreInBattle(
                            engine, currentFlagshipRef[0], playerGroup, preBattle)) {
                        DivisionDeploymentDialogOrganizer.playDeploymentButtonSound();
                        if (organizer.isOtcSelectorWindowOpen()) {
                            organizer.closeOtcSelectorWindow(innerPanel, reservesGrid);
                        } else {
                            organizer.openOtcSelectorWindow(
                                    engine,
                                    innerPanel,
                                    reservesGrid,
                                    width,
                                    deployedMembers,
                                    reserveMembers,
                                    currentFlagshipRef,
                                    preBattle,
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            refreshOtcDisplay();
                                        }
                                    });
                        }
                    }
                }
                if (!preBattle) return;
                ButtonAPI authPrev = authPrevButtonRef[0];
                ButtonAPI authNext = authNextButtonRef[0];
                if (authPrev != null && authPrev.isChecked()) {
                    authPrev.setChecked(false);
                    DivisionDeploymentDialogOrganizer.selectOtcAuthority(playerGroup, -1);
                }
                if (authNext != null && authNext.isChecked()) {
                    authNext.setChecked(false);
                    DivisionDeploymentDialogOrganizer.selectOtcAuthority(playerGroup, 1);
                }
                ButtonAPI prev = prevButtonRef[0];
                ButtonAPI next = nextButtonRef[0];
                ButtonAPI entry = entryButtonRef[0];
                if (prev != null && prev.isChecked()) {
                    prev.setChecked(false);
                    DivisionDeploymentDialogOrganizer.selectFleetFormation(playerGroup, -1);
                }
                if (next != null && next.isChecked()) {
                    next.setChecked(false);
                    DivisionDeploymentDialogOrganizer.selectFleetFormation(playerGroup, 1);
                }
                if (entry != null && entry.isChecked()) {
                    entry.setChecked(false);
                    PermanentDivisionManager.FleetDoctrineConfig doc = PermanentDivisionManager.getFleetDoctrine();
                    boolean enabled = !doc.isOrchestratedAdvance();
                    PermanentDivisionManager.updateOrchestratedAdvance(enabled);
                    PermanentDivisionManager.updateDeployInFleetFormation(true);
                    if (playerGroup != null) {
                        playerGroup.orchestratedAdvance = enabled;
                        playerGroup.deployInFleetFormation =
                                doc.getFleetWideFormation() != PermanentDivisionManager.FleetWideFormation.NONE;
                    }
                    DivisionDeploymentDialogOrganizer.playDeploymentButtonSound();
                }
                updateSelectorText();
            }

            @Override
            public void advance(float amount) {
                refreshOtcDisplay();
                if (isAlphaCoreRef[0] && masterPosRef[0] != null) {
                    float[] jitter = DivisionDeploymentDialogOrganizer.computeMasterJitterOffset(System.nanoTime() * 1.0e-9);
                    masterPosRef[0].inTL(masterBasePos[0] + jitter[0], masterBasePos[1] + jitter[1]);
                    if (masterGhostPosRef[0] != null) {
                        masterGhostPosRef[0].inTL(
                                masterBasePos[0] - jitter[0] * 0.75f,
                                masterBasePos[1] - jitter[1] * 0.75f);
                    }
                }
                pollButtons();
                updateSelectorText();
                if (!preBattle) updateLiveStatusText();
            }

            private boolean prevPennantLmbDown = false;

            @Override
            public void processInput(List<com.fs.starfarer.api.input.InputEventAPI> events) {
                if (!preBattle || events == null || organizer.isOtcSelectorWindowOpen()) return;
                CustomPanelAPI self = panelRef[0];
                if (self == null || self.getPosition() == null) return;
                PositionAPI pos = self.getPosition();
                float px = pos.getX() + cmdColX;
                float py = pos.getY();
                float ph = pos.getHeight();
                float pCardW = 122f;
                float pCardH = 122f;
                float pStartX = px + Math.round((cmdColW - pCardW) * 0.5f);
                float pBottomY = py + ph - 68f - pCardH;
                for (com.fs.starfarer.api.input.InputEventAPI ev : events) {
                    if (ev == null || ev.isConsumed()) continue;
                    if (ev.isLMBDownEvent()) {
                        float ex = ev.getX();
                        float ey = ev.getY();
                        if (ex >= pStartX && ex <= pStartX + pCardW && ey >= pBottomY && ey <= pBottomY + pCardH) {
                            ev.consume();
                            DivisionDeploymentDialogOrganizer.selectOtcAuthority(playerGroup, 1);
                            updateSelectorText();
                            refreshOtcDisplay();
                            return;
                        }
                    }
                }
            }

            @Override
            public void renderBelow(float alphaMult) {
                CustomPanelAPI self = panelRef[0];
                if (self == null || self.getPosition() == null) return;
                PositionAPI pos = self.getPosition();
                float x = pos.getX();
                float y = pos.getY();
                float w = pos.getWidth();
                float h = pos.getHeight();
                boolean alpha = isAlphaCoreRef[0];
                float portraitW = portraitWRef[0];
                Color themeAccent = alpha ? new Color(75, 220, 255) : new Color(245, 200, 65);
                float ar = themeAccent.getRed() / 255f;
                float ag = themeAccent.getGreen() / 255f;
                float ab = themeAccent.getBlue() / 255f;

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glColor4f(0.02f, 0.05f, 0.09f, 0.94f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();
                GL11.glColor4f(ar * 0.22f, ag * 0.24f, ab * 0.28f, 0.90f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x + 1f, y + h - topBarH);
                GL11.glVertex2f(x + w - 1f, y + h - topBarH);
                GL11.glVertex2f(x + w - 1f, y + h - 1f);
                GL11.glVertex2f(x + 1f, y + h - 1f);
                GL11.glEnd();
                GL11.glLineWidth(1.5f);
                GL11.glColor4f(ar, ag, ab, 0.72f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(x + 1f, y + 1f);
                GL11.glVertex2f(x + w - 1f, y + 1f);
                GL11.glVertex2f(x + w - 1f, y + h - 1f);
                GL11.glVertex2f(x + 1f, y + h - 1f);
                GL11.glEnd();
                float divX = x + leftSectionW;
                GL11.glLineWidth(1.0f);
                GL11.glColor4f(ar, ag, ab, 0.40f * alphaMult);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex2f(divX, y + 6f);
                GL11.glVertex2f(divX, y + h - topBarH - 4f);
                GL11.glEnd();
                float rx = x + rightSectionX;
                float ry = y + 6f;
                float rw = rightSectionW;
                float rh = h - topBarH - 10f;
                if (preBattle) {
                    float cX = x + cmdColX;
                    float fX = x + formColX;
                    GL11.glColor4f(0.01f, 0.03f, 0.06f, 0.85f * alphaMult);
                    GL11.glBegin(GL11.GL_QUADS);
                    GL11.glVertex2f(cX, ry);
                    GL11.glVertex2f(cX + cmdColW, ry);
                    GL11.glVertex2f(cX + cmdColW, ry + rh);
                    GL11.glVertex2f(cX, ry + rh);
                    GL11.glVertex2f(fX, ry);
                    GL11.glVertex2f(fX + formColW, ry);
                    GL11.glVertex2f(fX + formColW, ry + rh);
                    GL11.glVertex2f(fX, ry + rh);
                    GL11.glEnd();
                    GL11.glColor4f(ar, ag, ab, 0.32f * alphaMult);
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2f(cX, ry);
                    GL11.glVertex2f(cX + cmdColW, ry);
                    GL11.glVertex2f(cX + cmdColW, ry + rh);
                    GL11.glVertex2f(cX, ry + rh);
                    GL11.glEnd();
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2f(fX, ry);
                    GL11.glVertex2f(fX + formColW, ry);
                    GL11.glVertex2f(fX + formColW, ry + rh);
                    GL11.glVertex2f(fX, ry + rh);
                    GL11.glEnd();
                } else {
                    GL11.glColor4f(0.01f, 0.03f, 0.06f, 0.85f * alphaMult);
                    GL11.glBegin(GL11.GL_QUADS);
                    GL11.glVertex2f(rx, ry);
                    GL11.glVertex2f(rx + rw, ry);
                    GL11.glVertex2f(rx + rw, ry + rh);
                    GL11.glVertex2f(rx, ry + rh);
                    GL11.glEnd();
                    GL11.glColor4f(ar, ag, ab, 0.32f * alphaMult);
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2f(rx, ry);
                    GL11.glVertex2f(rx + rw, ry);
                    GL11.glVertex2f(rx + rw, ry + rh);
                    GL11.glVertex2f(rx, ry + rh);
                    GL11.glEnd();
                    float barX = rx + 10f;
                    float barW = rightStatusMaxW;
                    renderStatusMeterBar(barX, y + h - 88f, barW, 5f,
                            liveEngagementLevel[0], new Color(90, 215, 255), alphaMult);
                    renderStatusMeterBar(barX, y + h - 114f, barW, 5f,
                            liveCommitmentRatio[0], new Color(255, 205, 65), alphaMult);
                    renderStatusMeterBar(barX, y + h - 140f, barW, 5f,
                            liveCohesionRatio[0], new Color(90, 235, 150), alphaMult);
                }
                GL11.glPopAttrib();

                float pCenterX = x + portraitX + portraitW * 0.5f;
                float pCenterY = y + h - portraitTopY - portraitH * 0.5f;
                try {
                    SpriteAPI portraitSprite = Global.getSettings().getSprite(portraitSpritePathRef[0]);
                    if (portraitSprite != null) {
                        float prevW = portraitSprite.getWidth();
                        float prevH = portraitSprite.getHeight();
                        float prevCx = portraitSprite.getCenterX();
                        float prevCy = portraitSprite.getCenterY();
                        Color prevColor = portraitSprite.getColor();
                        float prevAlpha = portraitSprite.getAlphaMult();
                        portraitSprite.setSize(portraitW, portraitH);
                        portraitSprite.setCenter(portraitW * 0.5f, portraitH * 0.5f);
                        portraitSprite.setColor(Color.WHITE);
                        portraitSprite.setAlphaMult(0.96f * alphaMult);
                        portraitSprite.renderAtCenter(pCenterX, pCenterY);
                        portraitSprite.setSize(prevW, prevH);
                        portraitSprite.setCenter(prevCx, prevCy);
                        if (prevColor != null) portraitSprite.setColor(prevColor);
                        portraitSprite.setAlphaMult(prevAlpha);
                    }
                } catch (Throwable ignored) {
                }
                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                float px0 = pCenterX - portraitW * 0.5f;
                float py0 = pCenterY - portraitH * 0.5f;
                if (alpha) {
                    GL11.glLineWidth(1.0f);
                    GL11.glColor4f(0.30f, 0.90f, 1.0f, 0.14f * alphaMult);
                    GL11.glBegin(GL11.GL_LINES);
                    for (float sy = py0 + 4f; sy < py0 + portraitH - 2f; sy += 5f) {
                        GL11.glVertex2f(px0 + 1f, sy);
                        GL11.glVertex2f(px0 + portraitW - 1f, sy);
                    }
                    GL11.glEnd();
                }
                GL11.glLineWidth(2.0f);
                GL11.glColor4f(ar, ag, ab, 0.92f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(px0, py0);
                GL11.glVertex2f(px0 + portraitW, py0);
                GL11.glVertex2f(px0 + portraitW, py0 + portraitH);
                GL11.glVertex2f(px0, py0 + portraitH);
                GL11.glEnd();
                GL11.glPopAttrib();

                PermanentDivisionManager.FleetDoctrineConfig doc = PermanentDivisionManager.getFleetDoctrine();
                PermanentDivisionManager.FleetWideFormation fw;
                if (preBattle) {
                    fw = doc.getFleetWideFormation();
                } else {
                    fw = (playerGroup != null
                            && playerGroup.activeFleetWideFormation != null
                            && playerGroup.activeFleetWideFormation != PermanentDivisionManager.FleetWideFormation.DOCTRINAL_AUTO)
                            ? playerGroup.activeFleetWideFormation
                            : doc.getFleetWideFormation();
                }
                if (preBattle) {
                    PermanentDivisionManager.OtcAuthority activeAuth = (doc != null && doc.getOtcAuthority() != null)
                            ? doc.getOtcAuthority()
                            : PermanentDivisionManager.OtcAuthority.COMMAND_BY_DIRECTION;
                    float pCardW = 122f;
                    float pCardH = 122f;
                    float pStartX = x + cmdColX + Math.round((cmdColW - pCardW) * 0.5f);
                    float pBottomY = y + h - 68f - pCardH;
                    FleetDoctrineDialog.renderOtcAuthorityPennantBox(
                            activeAuth, pStartX, pBottomY, pCardW, pCardH, true, false, alphaMult);
                    DivisionDeploymentDialogOrganizer.renderFleetFormationIcon(fw, x + formColX + formColW * 0.5f, y + h - 122f, 108f, alphaMult, false);
                } else {
                    PermanentDivisionManager.OtcAuthority activeAuth = (playerGroup != null && playerGroup.authority != null)
                            ? playerGroup.authority
                            : doc.getOtcAuthority();
                    float midIconSize = 86f;
                    float pennantX = rx + rw - midIconSize * 2f - 18f;
                    float pennantY = y + Math.round((h - topBarH - midIconSize) * 0.5f) - 2f;
                    float wheelCenterX = rx + rw - midIconSize * 0.5f - 8f;
                    float wheelCenterY = pennantY + midIconSize * 0.5f;
                    FleetDoctrineDialog.renderOtcAuthorityPennantBox(
                            activeAuth, pennantX, pennantY, midIconSize, midIconSize, true, false, alphaMult);
                    DivisionDeploymentDialogOrganizer.renderFleetFormationIcon(fw, wheelCenterX, wheelCenterY, midIconSize, alphaMult, true);
                }
            }
        };

        CustomPanelAPI panel = Global.getSettings().createCustom(width, height, fcPlugin);
        panelRef[0] = panel;
        panel.addComponent((UIComponentAPI) headerBannerLabel).setSize(width - 20f, 16f).inTL(10f, 2f);

        float initTextLeftX = portraitX + portraitWRef[0] + 12f;
        float initMaxLeftW = Math.max(160f, leftSectionW - initTextLeftX - 6f);
        prePosRef[0] = panel.addComponent((UIComponentAPI) cogPrefixLabel);
        prePosRef[0].setSize(initMaxLeftW, 15f).inTL(initTextLeftX, titleTopY);
        masterGhostPosRef[0] = panel.addComponent((UIComponentAPI) cogMasterGhostLabel);
        masterGhostPosRef[0].setSize(initMaxLeftW, 15f).inTL(initTextLeftX, titleTopY);
        masterPosRef[0] = panel.addComponent((UIComponentAPI) cogMasterLabel);
        masterPosRef[0].setSize(initMaxLeftW, 15f).inTL(initTextLeftX, titleTopY);
        sufPosRef[0] = panel.addComponent((UIComponentAPI) cogSuffixLabel);
        sufPosRef[0].setSize(initMaxLeftW, 20f).inTL(initTextLeftX, titleTopY + 24f);
        sufLine2PosRef[0] = panel.addComponent((UIComponentAPI) cogSuffixLine2Label);
        sufLine2PosRef[0].setSize(initMaxLeftW, 0f).inTL(initTextLeftX, 0f);
        stdTitlePosRef[0] = panel.addComponent((UIComponentAPI) otcStandardTitleLabel);
        stdTitlePosRef[0].setSize(initMaxLeftW, 20f).inTL(initTextLeftX, titleTopY);
        stdTitleLine2PosRef[0] = panel.addComponent((UIComponentAPI) otcStandardTitleLine2Label);
        stdTitleLine2PosRef[0].setSize(initMaxLeftW, 0f).inTL(initTextLeftX, 0f);
        flagshipPosRef[0] = panel.addComponent((UIComponentAPI) flagshipLabel);
        flagshipPosRef[0].setSize(initMaxLeftW, 16f).inTL(initTextLeftX, titleTopY + 31f);
        flagshipLine2PosRef[0] = panel.addComponent((UIComponentAPI) flagshipLine2Label);
        flagshipLine2PosRef[0].setSize(initMaxLeftW, 0f).inTL(initTextLeftX, 0f);
        profilePosRef[0] = panel.addComponent((UIComponentAPI) profileLabel);
        profilePosRef[0].setSize(initMaxLeftW, 16f).inTL(initTextLeftX, titleTopY + 55f);
        profileLine2PosRef[0] = panel.addComponent((UIComponentAPI) profileLine2Label);
        profileLine2PosRef[0].setSize(initMaxLeftW, 0f).inTL(initTextLeftX, 0f);

        float rightLabelX = rightSectionX + 8f;
        float rightMaxW = rightStatusMaxW;
        if (preBattle) {
            panel.addComponent((UIComponentAPI) cmdStyleHeaderLabel)
                    .setSize(cmdColW - 8f, 15f).inTL(cmdColX + 4f, topBarH + 4f);
            panel.addComponent((UIComponentAPI) cmdStyleNameLabel)
                    .setSize(cmdColW - 60f, 16f).inTL(cmdColX + 30f, topBarH + 24f);
            panel.addComponent((UIComponentAPI) selectorHeaderLabel)
                    .setSize(formColW - 8f, 15f).inTL(formColX + 4f, topBarH + 4f);
            panel.addComponent((UIComponentAPI) formationNameLabel)
                    .setSize(formColW - 60f, 16f).inTL(formColX + 30f, topBarH + 24f);
        } else {
            panel.addComponent((UIComponentAPI) selectorHeaderLabel)
                    .setSize(rightMaxW, 15f).inTL(rightLabelX, topBarH + 4f);
            panel.addComponent((UIComponentAPI) formationNameLabel)
                    .setSize(rightMaxW, 20f).inTL(rightLabelX, topBarH + 23f);
        }

        TooltipMakerAPI leftButtons = panel.createUIElement(leftSectionW, height, false);
        Color base = new Color(245, 200, 65);
        Color dark = new Color(24, 34, 44);
        changeOtcButtonRef[0] = leftButtons.addButton("Change", "OTC_CHANGE", base, dark,
                Alignment.MID, CutStyle.ALL, portraitWRef[0], 18f, 0f);
        changeOtcButtonRef[0].getPosition().inTL(portraitX, portraitTopY + portraitH + 5f);
        changeOtcButtonRef[0].setEnabled(!DivisionOtcCommandManager.isPlayerOtcLockedByAlphaCoreInBattle(
                engine, currentFlagshipRef[0], playerGroup, preBattle));
        DivisionUiWidgets.attachActionTooltip(leftButtons, changeOtcButtonRef[0], "OTC_CHANGE");
        leftButtons.setHeightSoFar(height);
        panel.addUIElement(leftButtons).inTL(0f, 0f);

        if (!preBattle) {
            panel.addComponent((UIComponentAPI) engagementLabel)
                    .setSize(rightMaxW, 15f).inTL(rightLabelX, 66f);
            panel.addComponent((UIComponentAPI) commitmentLabel)
                    .setSize(rightMaxW, 15f).inTL(rightLabelX, 92f);
            panel.addComponent((UIComponentAPI) cohesionLabel)
                    .setSize(rightMaxW, 15f).inTL(rightLabelX, 118f);
            panel.addComponent((UIComponentAPI) phaseLabel)
                    .setSize(rightMaxW, 14f).inTL(rightLabelX, 146f);
            DivisionUiWidgets.addTooltip(leftButtons, (UIComponentAPI) engagementLabel,
                    "Battlefield Engagement Intensity",
                    "Measures the proportion of deployed forces actively exchanging fire or in close tactical contact.");
            DivisionUiWidgets.addTooltip(leftButtons, (UIComponentAPI) commitmentLabel,
                    "Fleet Force Commitment",
                    "Percentage and Deployment Points (DP) of your battle group currently deployed on the battlefield versus held in reserve.");
            DivisionUiWidgets.addTooltip(leftButtons, (UIComponentAPI) cohesionLabel,
                    "Division Cohesion & Readiness",
                    "Average formation dressing and flux readiness across your active combat-effective divisions.");
            DivisionUiWidgets.addTooltip(leftButtons, (UIComponentAPI) phaseLabel,
                    "Operational Battle Phase",
                    "Current battle group phase directed by your Officer in Tactical Command (Approach, General Engagement, Coordinated Strike, Reinforce the Line, General Chase, or Fighting Retirement).");
        } else {
            TooltipMakerAPI buttons = panel.createUIElement(width, height, false);
            authPrevButtonRef[0] = buttons.addButton("<", "OTC_AUTH_PREV", base, dark,
                    Alignment.MID, CutStyle.ALL, 24f, 20f, 0f);
            authNextButtonRef[0] = buttons.addButton(">", "OTC_AUTH_NEXT", base, dark,
                    Alignment.MID, CutStyle.ALL, 24f, 20f, 0f);
            prevButtonRef[0] = buttons.addButton("<", "FLEET_FORMATION_PREV", base, dark,
                    Alignment.MID, CutStyle.ALL, 24f, 20f, 0f);
            nextButtonRef[0] = buttons.addButton(">", "FLEET_FORMATION_NEXT", base, dark,
                    Alignment.MID, CutStyle.ALL, 24f, 20f, 0f);
            entryButtonRef[0] = buttons.addButton("Orchestrated Advance: On", "FLEET_ORCHESTRATED_ADVANCE", base, dark,
                    Alignment.MID, CutStyle.ALL, formColW - 12f, 18f, 0f);
            authPrevButtonRef[0].getPosition().inTL(cmdColX + 4f, topBarH + 22f);
            authNextButtonRef[0].getPosition().inTL(cmdColX + cmdColW - 28f, topBarH + 22f);
            prevButtonRef[0].getPosition().inTL(formColX + 4f, topBarH + 22f);
            nextButtonRef[0].getPosition().inTL(formColX + formColW - 28f, topBarH + 22f);
            entryButtonRef[0].getPosition().inTL(formColX + 6f, height - 24f);
            DivisionUiWidgets.attachActionTooltip(buttons, authPrevButtonRef[0], "OTC_AUTH_PREV");
            DivisionUiWidgets.attachActionTooltip(buttons, authNextButtonRef[0], "OTC_AUTH_NEXT");
            DivisionUiWidgets.attachActionTooltip(buttons, prevButtonRef[0], "FLEET_FORMATION_PREV");
            DivisionUiWidgets.attachActionTooltip(buttons, nextButtonRef[0], "FLEET_FORMATION_NEXT");
            DivisionUiWidgets.attachActionTooltip(buttons, entryButtonRef[0], "FLEET_ORCHESTRATED_ADVANCE");
            buttons.setHeightSoFar(height);
            panel.addUIElement(buttons).inTL(0f, 0f);
        }
        fcPlugin.advance(0f);
        return panel;
    }

    private static void renderStatusMeterBar(
            float x,
            float y,
            float w,
            float h,
            float fillRatio,
            Color barColor,
            float alphaMult) {
        float r = barColor.getRed() / 255f;
        float g = barColor.getGreen() / 255f;
        float b = barColor.getBlue() / 255f;
        GL11.glColor4f(0.06f, 0.10f, 0.15f, 0.90f * alphaMult);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
        float fillW = Math.max(0f, Math.min(w, w * fillRatio));
        if (fillW > 0.5f) {
            GL11.glColor4f(r, g, b, 0.88f * alphaMult);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x + 1f, y + 1f);
            GL11.glVertex2f(x + fillW, y + 1f);
            GL11.glVertex2f(x + fillW, y + h - 1f);
            GL11.glVertex2f(x + 1f, y + h - 1f);
            GL11.glEnd();
        }
        GL11.glLineWidth(1.0f);
        GL11.glColor4f(r, g, b, 0.48f * alphaMult);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }
}
