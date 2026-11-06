package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
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
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Standalone & embedded OTC (Officer in Tactical Command) Selection Modal Dialog.
 * Extracted from {@link DivisionDeploymentDialogOrganizer}.
 */
public final class OtcSelectionModalDialog {

    private OtcSelectionModalDialog() {
    }

    public static CustomPanelAPI createWindowPanel(
            final DivisionDeploymentDialogOrganizer organizer,
            final CombatEngineAPI engine,
            final UIPanelAPI innerPanel,
            final Object reservesGrid,
            final float width,
            final float height,
            final List<FleetMemberAPI> deployedMembers,
            final List<FleetMemberAPI> reserveMembers,
            final FleetMemberAPI[] currentFlagshipRef,
            final boolean preBattle,
            final Runnable onOtcChangedCallback) {

        final CustomPanelAPI[] winRef = new CustomPanelAPI[1];
        final ButtonAPI[] closeBtnRef = new ButtonAPI[1];
        final List<ButtonAPI> candidateButtons = new ArrayList<>();
        final List<String> candidateMemberIds = new ArrayList<>();
        final float topBarH = 26f;

        final Runnable pollWindowButtons = new Runnable() {
            @Override
            public void run() {
                if (!organizer.isOtcSelectorWindowOpen()) return;
                ButtonAPI closeBtn = closeBtnRef[0];
                if (closeBtn != null && closeBtn.isChecked()) {
                    closeBtn.setChecked(false);
                    DivisionDeploymentDialogOrganizer.playDeploymentButtonSound();
                    organizer.closeOtcSelectorWindow(innerPanel, reservesGrid);
                    return;
                }
                for (int i = 0; i < candidateButtons.size(); i++) {
                    ButtonAPI b = candidateButtons.get(i);
                    if (b != null && b.isChecked()) {
                        b.setChecked(false);
                        String targetId = candidateMemberIds.get(i);
                        boolean changed = DivisionOtcCommandManager.selectPlayerOtcByMemberId(
                                engine,
                                organizer.getPlayerGroup(),
                                deployedMembers,
                                reserveMembers,
                                currentFlagshipRef[0],
                                targetId,
                                preBattle);
                        if (changed) {
                            DivisionDeploymentDialogOrganizer.playDeploymentButtonSound();
                            if (onOtcChangedCallback != null) {
                                onOtcChangedCallback.run();
                            }
                        }
                        organizer.closeOtcSelectorWindow(innerPanel, reservesGrid);
                        return;
                    }
                }
            }
        };
        organizer.setActiveOtcSelectorPollHook(pollWindowButtons);

        BaseCustomUIPanelPlugin winPlugin = new BaseCustomUIPanelPlugin() {
            @Override
            public void advance(float amount) {
                pollWindowButtons.run();
            }

            @Override
            public void renderBelow(float alphaMult) {
                CustomPanelAPI self = winRef[0];
                if (self == null || self.getPosition() == null) return;
                PositionAPI pos = self.getPosition();
                float x = pos.getX();
                float y = pos.getY();
                float w = pos.getWidth();
                float h = pos.getHeight();

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                // Window background
                GL11.glColor4f(0.02f, 0.05f, 0.09f, 0.96f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                // Header bar fill
                GL11.glColor4f(0.20f, 0.17f, 0.06f, 0.90f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x + 1f, y + h - topBarH);
                GL11.glVertex2f(x + w - 1f, y + h - topBarH);
                GL11.glVertex2f(x + w - 1f, y + h - 1f);
                GL11.glVertex2f(x + 1f, y + h - 1f);
                GL11.glEnd();

                // Window border
                GL11.glLineWidth(1.5f);
                GL11.glColor4f(0.96f, 0.78f, 0.25f, 0.80f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(x + 1f, y + 1f);
                GL11.glVertex2f(x + w - 1f, y + 1f);
                GL11.glVertex2f(x + w - 1f, y + h - 1f);
                GL11.glVertex2f(x + 1f, y + h - 1f);
                GL11.glEnd();

                GL11.glPopAttrib();
            }
        };

        CustomPanelAPI winPanel = Global.getSettings().createCustom(width, height, winPlugin);
        winRef[0] = winPanel;

        boolean emergencyPending = DivisionOtcCommandManager.isEmergencyPlayerOtcSelectionPending(organizer.getPlayerGroup());
        String headerTitleText = emergencyPending
                ? "OTC DESTROYED - SELECT NEW OTC"
                : "SELECT TACTICAL COMMANDER";
        LabelAPI titleLbl = Global.getSettings().createLabel(headerTitleText, Fonts.ORBITRON_12);
        titleLbl.setColor(emergencyPending ? new Color(255, 105, 85, 255) : new Color(255, 215, 80, 255));
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(titleLbl);
        winPanel.addComponent((UIComponentAPI) titleLbl).setSize(265f, 18f).inTL(10f, 4f);

        int cpCost = (preBattle || emergencyPending) ? 0 : DivisionOtcCommandManager.getOtcReassignmentCommandPointCost(engine);
        String costInfo = emergencyPending
                ? "0 CP | Undeployed OTC: -75% Max CP, -50% Regen"
                : (preBattle
                ? "0 CP | Undeployed OTC: -75% Max CP, -50% Regen"
                : String.format(Locale.ROOT, "%d CP | Undeployed OTC: -75%% Max CP, -50%% Regen", cpCost));
        LabelAPI costLbl = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        costLbl.setColor(preBattle || emergencyPending ? new Color(255, 195, 95, 245) : new Color(175, 215, 235, 240));
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(costLbl);
        float costMaxW = Math.max(180f, width - 355f);
        winPanel.addComponent((UIComponentAPI) costLbl).setSize(costMaxW, 16f).inTL(278f, 5f);
        costLbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(costInfo, costLbl, costMaxW - 4f));

        TooltipMakerAPI topBtnBar = winPanel.createUIElement(width, topBarH, false);
        Color base = new Color(245, 200, 65);
        Color dark = new Color(24, 34, 44);
        closeBtnRef[0] = topBtnBar.addButton("Close", "OTC_WINDOW_CLOSE", base, dark,
                Alignment.MID, CutStyle.ALL, 68f, 18f, 0f);
        closeBtnRef[0].getPosition().inTL(width - 76f, 4f);
        DivisionUiWidgets.attachActionTooltip(topBtnBar, closeBtnRef[0], "OTC_WINDOW_CLOSE");
        DivisionUiWidgets.addTooltip(
                topBtnBar,
                (UIComponentAPI) costLbl,
                "OTC Reassignment & Deployment Rules",
                "Pre-battle or emergency OTC selection is free (0 CP). Voluntary mid-battle OTC reassignment costs 75% of max Command Points. If your designated OTC ship is not deployed into battle, your fleet suffers -75% Max CP and -50% CP regeneration.");
        topBtnBar.setHeightSoFar(topBarH);
        winPanel.addUIElement(topBtnBar).inTL(0f, 0f);

        float bodyH = Math.max(120f, height - topBarH - 4f);
        TooltipMakerAPI scroller = winPanel.createUIElement(width - 4f, bodyH, true);

        List<DivisionOtcCommandManager.OtcCandidate> allCandidates =
                DivisionOtcCommandManager.collectPlayerOtcCandidates(engine, organizer.getPlayerGroup(), deployedMembers, reserveMembers);
        String currentOtcId = (currentFlagshipRef[0] != null && currentFlagshipRef[0].getId() != null)
                ? currentFlagshipRef[0].getId()
                : DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(organizer.getPlayerGroup());

        // Group candidates by division row (preserving division order, with Unassigned at the end)
        Map<String, List<DivisionOtcCommandManager.OtcCandidate>> rowsByGroupKey = new LinkedHashMap<>();
        for (DivisionOtcCommandManager.OtcCandidate c : allCandidates) {
            if (c == null) continue;
            String key = (c.divisionId != null && !c.divisionId.isEmpty()) ? c.divisionId : "UNASSIGNED";
            List<DivisionOtcCommandManager.OtcCandidate> list = rowsByGroupKey.get(key);
            if (list == null) {
                list = new ArrayList<>();
                rowsByGroupKey.put(key, list);
            }
            list.add(c);
        }

        float rowFullW = width - 16f;
        float headerW = 122f;
        float cardW = 128f;
        float cardH = 126f;
        float gapX = 6f;
        float gapY = 4f;
        float cardsStartX = headerW + 6f;
        int cardsPerRow = Math.max(1, (int) ((rowFullW - cardsStartX + gapX) / (cardW + gapX)));
        float curY = 4f;

        for (Map.Entry<String, List<DivisionOtcCommandManager.OtcCandidate>> entry : rowsByGroupKey.entrySet()) {
            List<DivisionOtcCommandManager.OtcCandidate> groupList = entry.getValue();
            if (groupList == null || groupList.isEmpty()) continue;

            DivisionOtcCommandManager.OtcCandidate first = groupList.get(0);
            int subRows = Math.max(1, (groupList.size() + cardsPerRow - 1) / cardsPerRow);
            float rowBlockH = subRows * cardH + (subRows - 1) * gapY;

            CustomPanelAPI rowPanel = createOtcDivisionRowPanel(
                    first.divisionTitle,
                    first.inDivision,
                    first.divisionFormation,
                    first.isFlagshipDivision,
                    groupList,
                    currentOtcId,
                    rowFullW,
                    rowBlockH,
                    headerW,
                    cardsStartX,
                    cardW,
                    cardH,
                    gapX,
                    gapY,
                    cardsPerRow,
                    candidateButtons,
                    candidateMemberIds);
            scroller.addCustom(rowPanel, 0f).getPosition().inTL(4f, curY);
            curY += rowBlockH + 6f;
        }

        scroller.setHeightSoFar(curY + 4f);
        winPanel.addUIElement(scroller).inTL(2f, topBarH + 2f);
        return winPanel;
    }

    private static CustomPanelAPI createOtcDivisionRowPanel(
            final String groupTitle,
            final boolean isDivisionRow,
            final PermanentDivisionManager.DivisionFormation divFm,
            final boolean isFlagshipDiv,
            final List<DivisionOtcCommandManager.OtcCandidate> groupCandidates,
            final String currentOtcId,
            final float rowWidth,
            final float rowHeight,
            final float headerWidth,
            final float cardsStartX,
            final float cardW,
            final float cardH,
            final float gapX,
            final float gapY,
            final int cardsPerRow,
            final List<ButtonAPI> outButtons,
            final List<String> outMemberIds) {

        final CustomPanelAPI[] rowRef = new CustomPanelAPI[1];
        final Color accentColor = isDivisionRow ? DivisionDeploymentDialogOrganizer.DIV_ACCENT_COLOR : DivisionDeploymentDialogOrganizer.UNASSIGNED_ACCENT_COLOR;
        Color titleColor = isDivisionRow ? DivisionDeploymentDialogOrganizer.DIV_HEADER_TEXT_COLOR : DivisionDeploymentDialogOrganizer.UNASSIGNED_TEXT_COLOR;

        LabelAPI line1Label = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        line1Label.setColor(titleColor);
        line1Label.setAlignment(Alignment.LMID);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(line1Label);

        LabelAPI line2Label = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        line2Label.setColor(titleColor);
        line2Label.setAlignment(Alignment.LMID);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(line2Label);

        LabelAPI countLabel = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        countLabel.setColor(new Color(175, 215, 235, 235));
        countLabel.setAlignment(Alignment.LMID);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(countLabel);

        boolean hasFormationBadge = isDivisionRow && divFm != null;
        float line1LeftX = hasFormationBadge ? 20f : 6f;
        float line2LeftX = 6f;
        float maxLine1W = Math.max(42f, headerWidth - line1LeftX - 4f);
        float maxLine2W = Math.max(54f, headerWidth - line2LeftX - 4f);
        String[] lines = DivisionDeploymentDialogOrganizer.splitDeploymentCardTitleLines(groupTitle, line1Label, maxLine1W, maxLine2W);
        final boolean isTwoLineTitle = (lines.length >= 2 && lines[1] != null && !lines[1].isEmpty());
        final float iconCenterTopY = isTwoLineTitle ? 12.5f : 18.5f;

        BaseCustomUIPanelPlugin rowPlugin = new BaseCustomUIPanelPlugin() {
            @Override
            public void renderBelow(float alphaMult) {
                CustomPanelAPI self = rowRef[0];
                if (self == null || self.getPosition() == null) return;
                PositionAPI pos = self.getPosition();
                float x = pos.getX();
                float y = pos.getY();
                float h = pos.getHeight();
                float hw = headerWidth - 2f;

                float r = accentColor.getRed() / 255f;
                float g = accentColor.getGreen() / 255f;
                float b = accentColor.getBlue() / 255f;

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                // Left division header card backdrop
                GL11.glColor4f(0.03f, 0.06f, 0.10f, 0.94f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + hw, y);
                GL11.glVertex2f(x + hw, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                // Left vertical accent stripe
                GL11.glColor4f(r, g, b, 0.92f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + 3f, y);
                GL11.glVertex2f(x + 3f, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                // Enclosing division row frame
                GL11.glLineWidth(1.0f);
                GL11.glColor4f(r, g, b, 0.28f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + rowWidth - 2f, y);
                GL11.glVertex2f(x + rowWidth - 2f, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                GL11.glPopAttrib();

                if (isDivisionRow && divFm != null) {
                    float iconCx = x + 11.5f;
                    float iconCy = y + h - iconCenterTopY;
                    FleetDoctrineDialog.renderFormationIcon(
                            divFm,
                            iconCx,
                            iconCy,
                            12f,
                            Color.WHITE,
                            0.95f * alphaMult,
                            isFlagshipDiv);
                }
            }
        };

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, rowHeight, rowPlugin);
        rowRef[0] = rowPanel;

        PositionAPI p1 = rowPanel.addComponent((UIComponentAPI) line1Label);
        PositionAPI p2 = rowPanel.addComponent((UIComponentAPI) line2Label);
        PositionAPI pc = rowPanel.addComponent((UIComponentAPI) countLabel);
        float boxW1 = Math.max(20f, headerWidth - line1LeftX - 3f);
        float boxW2 = Math.max(20f, headerWidth - line2LeftX - 3f);
        if (!isTwoLineTitle) {
            String l1 = lines.length > 0 ? lines[0] : "";
            p1.setSize(boxW1, 14f).inTL(line1LeftX, 11f);
            line1Label.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(l1, line1Label, boxW1 - 2f));
            p2.setSize(boxW2, 0f).inTL(line2LeftX, 0f);
            line2Label.setText("");
        } else {
            p1.setSize(boxW1, 13f).inTL(line1LeftX, 5f);
            line1Label.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(lines[0], line1Label, boxW1 - 2f));
            p2.setSize(boxW2, 13f).inTL(line2LeftX, 19f);
            line2Label.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(lines[1], line2Label, boxW2 - 2f));
        }
        String countTxt = groupCandidates.size() == 1 ? "1 Commander" : (groupCandidates.size() + " Commanders");
        pc.setSize(boxW2, 14f).inTL(6f, 38f);
        countLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(countTxt, countLabel, boxW2 - 2f));

        for (int i = 0; i < groupCandidates.size(); i++) {
            DivisionOtcCommandManager.OtcCandidate cand = groupCandidates.get(i);
            int col = i % cardsPerRow;
            int subRow = i / cardsPerRow;
            float cx = cardsStartX + col * (cardW + gapX);
            float cy = subRow * (cardH + gapY);
            boolean isCurrent = (currentOtcId != null && currentOtcId.equals(cand.memberId));
            CustomPanelAPI card = createOtcCommanderPortraitCard(cand, isCurrent, cardW, cardH, outButtons, outMemberIds);
            rowPanel.addComponent(card).setSize(cardW, cardH).inTL(cx, cy);
        }

        return rowPanel;
    }

    private static CustomPanelAPI createOtcCommanderPortraitCard(
            final DivisionOtcCommandManager.OtcCandidate cand,
            final boolean isCurrentOtc,
            final float cardW,
            final float cardH,
            final List<ButtonAPI> outButtons,
            final List<String> outMemberIds) {

        final CustomPanelAPI[] cardRef = new CustomPanelAPI[1];
        final float portraitH = 58f;
        final float portraitW = cand.alphaCore
                ? Math.min(104f, portraitH * DivisionDeploymentDialogOrganizer.ALPHA_CORE_PORTRAIT_STRETCH_ASPECT)
                : portraitH;
        final float portraitTopY = 4f;

        BaseCustomUIPanelPlugin cardPlugin = new BaseCustomUIPanelPlugin() {
            @Override
            public void renderBelow(float alphaMult) {
                CustomPanelAPI self = cardRef[0];
                if (self == null || self.getPosition() == null) return;
                PositionAPI pos = self.getPosition();
                float x = pos.getX() + 1f;
                float y = pos.getY() + 1f;
                float w = pos.getWidth() - 2f;
                float h = pos.getHeight() - 2f;

                Color borderCol = isCurrentOtc
                        ? new Color(255, 215, 55)
                        : (cand.alphaCore ? new Color(80, 225, 255)
                        : (cand.divisionLeader ? new Color(85, 220, 135) : new Color(135, 165, 195)));
                float br = borderCol.getRed() / 255f;
                float bg = borderCol.getGreen() / 255f;
                float bb = borderCol.getBlue() / 255f;

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                // Card background
                GL11.glColor4f(isCurrentOtc ? 0.08f : 0.02f, isCurrentOtc ? 0.08f : 0.04f, 0.08f, 0.94f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                // Card border
                GL11.glLineWidth(isCurrentOtc ? 2.0f : 1.2f);
                GL11.glColor4f(br, bg, bb, (isCurrentOtc ? 0.95f : 0.65f) * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();
                GL11.glPopAttrib();

                // Render Medium/Large Commander Portrait
                float pCx = pos.getX() + pos.getWidth() * 0.5f;
                float pCy = pos.getY() + pos.getHeight() - portraitTopY - portraitH * 0.5f;
                try {
                    SpriteAPI sp = Global.getSettings().getSprite(cand.portraitSpritePath);
                    if (sp != null) {
                        float prevW = sp.getWidth();
                        float prevH = sp.getHeight();
                        float prevCx = sp.getCenterX();
                        float prevCy = sp.getCenterY();
                        Color prevColor = sp.getColor();
                        float prevAlpha = sp.getAlphaMult();
                        sp.setSize(portraitW, portraitH);
                        sp.setCenter(portraitW * 0.5f, portraitH * 0.5f);
                        sp.setColor(Color.WHITE);
                        sp.setAlphaMult(0.96f * alphaMult);
                        sp.renderAtCenter(pCx, pCy);
                        sp.setSize(prevW, prevH);
                        sp.setCenter(prevCx, prevCy);
                        if (prevColor != null) sp.setColor(prevColor);
                        sp.setAlphaMult(prevAlpha);
                    }
                } catch (Throwable ignored) {
                }

                // Portrait frame
                float px0 = pCx - portraitW * 0.5f;
                float py0 = pCy - portraitH * 0.5f;
                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glLineWidth(1.5f);
                GL11.glColor4f(br, bg, bb, 0.90f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(px0, py0);
                GL11.glVertex2f(px0 + portraitW, py0);
                GL11.glVertex2f(px0 + portraitW, py0 + portraitH);
                GL11.glVertex2f(px0, py0 + portraitH);
                GL11.glEnd();
                GL11.glPopAttrib();

                // Render Gold/Silver Star badge on upper-left of portrait if Fleet Flagship or Division Leader
                boolean isFleetFlag = (cand.member != null && cand.member.isFlagship()) || isCurrentOtc;
                if (isFleetFlag || cand.divisionLeader) {
                    try {
                        String starKey = isFleetFlag ? "star_gold" : "star_silver";
                        SpriteAPI starSp = Global.getSettings().getSprite("division_ai", starKey);
                        if (starSp != null) {
                            float sw = starSp.getWidth();
                            float sh = starSp.getHeight();
                            starSp.setSize(14f, 14f);
                            starSp.setCenter(7f, 7f);
                            starSp.setAlphaMult(0.95f * alphaMult);
                            starSp.renderAtCenter(px0 + 8f, py0 + portraitH - 8f);
                            starSp.setSize(sw, sh);
                            starSp.setCenter(sw * 0.5f, sh * 0.5f);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        };

        CustomPanelAPI card = Global.getSettings().createCustom(cardW, cardH, cardPlugin);
        cardRef[0] = card;

        LabelAPI cmdrLbl = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        cmdrLbl.setColor(cand.alphaCore ? new Color(130, 238, 255, 255) : new Color(235, 245, 255, 255));
        cmdrLbl.setAlignment(Alignment.MID);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(cmdrLbl);

        LabelAPI persLbl = Global.getSettings().createLabel("", "graphics/fonts/victor14.fnt");
        Color persColor;
        if (cand.alphaCore) {
            persColor = new Color(110, 235, 255, 255);
        } else if ("reckless".equalsIgnoreCase(cand.personalityId)) {
            persColor = new Color(255, 145, 110, 255);
        } else if ("aggressive".equalsIgnoreCase(cand.personalityId)) {
            persColor = new Color(255, 205, 95, 255);
        } else if ("cautious".equalsIgnoreCase(cand.personalityId) || "timid".equalsIgnoreCase(cand.personalityId)) {
            persColor = new Color(150, 230, 185, 255);
        } else {
            persColor = new Color(175, 215, 245, 255);
        }
        persLbl.setColor(persColor);
        persLbl.setAlignment(Alignment.MID);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(persLbl);

        LabelAPI shipLbl = Global.getSettings().createLabel("", "graphics/fonts/victor14.fnt");
        shipLbl.setColor(new Color(180, 210, 230, 240));
        shipLbl.setAlignment(Alignment.MID);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(shipLbl);

        float textBoxW = cardW - 6f;
        card.addComponent((UIComponentAPI) cmdrLbl).setSize(textBoxW, 13f).inTL(3f, 64f);
        cmdrLbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                cand.alphaCore ? "ALPHA CORE" : cand.commanderName, cmdrLbl, textBoxW - 6f));

        String persText = (cand.personalityDisplay != null && !cand.personalityDisplay.isEmpty())
                ? cand.personalityDisplay
                : "Steady";
        card.addComponent((UIComponentAPI) persLbl).setSize(textBoxW, 12f).inTL(3f, 77f);
        persLbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                persText, persLbl, textBoxW - 6f));

        card.addComponent((UIComponentAPI) shipLbl).setSize(textBoxW, 12f).inTL(3f, 90f);
        shipLbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                cand.shipName, shipLbl, textBoxW - 6f));

        TooltipMakerAPI btnHolder = card.createUIElement(cardW, cardH, false);
        Color btnBase = isCurrentOtc
                ? new Color(255, 215, 70)
                : (cand.alphaCore ? new Color(90, 225, 255) : new Color(110, 225, 145));
        Color btnDark = new Color(20, 34, 44);
        String btnText = isCurrentOtc ? "Active OTC" : "Select";
        ButtonAPI selectBtn = btnHolder.addButton(
                btnText,
                "OTC_SELECT:" + cand.memberId,
                btnBase,
                btnDark,
                Alignment.MID,
                CutStyle.ALL,
                cardW - 10f,
                16f,
                0f);
        selectBtn.getPosition().inTL(5f, 105f);
        selectBtn.setEnabled(!isCurrentOtc);
        if (isCurrentOtc) {
            selectBtn.highlight();
        }
        DivisionUiWidgets.addTooltip(
                btnHolder,
                selectBtn,
                (cand.alphaCore ? "Alpha Core OTC: " : "Officer in Tactical Command: ") + cand.commanderName,
                "Ship: " + cand.shipName + " | Personality: " + persText + " | Division: " + cand.divisionTitle
                        + ". Click to designate this commander as your battle group's Officer in Tactical Command (OTC).",
                cand.alphaCore ? "+ Alpha Core OTC unlocks Time-Space-Firepower Convergence." : null,
                "- If this ship is not deployed into battle, your fleet suffers -75% Max CP and -50% CP regeneration.");
        btnHolder.setHeightSoFar(cardH);
        card.addUIElement(btnHolder).inTL(0f, 0f);

        if (outButtons != null && outMemberIds != null) {
            outButtons.add(selectBtn);
            outMemberIds.add(cand.memberId);
        }

        return card;
    }
}
