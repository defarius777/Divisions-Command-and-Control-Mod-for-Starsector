package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.ScrollPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.PermanentDivisionManager.DecisiveArm;
import divisionai.PermanentDivisionManager.EngagementDiscipline;
import divisionai.PermanentDivisionManager.MultiDivisionSetpiece;
import divisionai.PermanentDivisionManager.ObjectiveCapturePolicy;
import divisionai.PermanentDivisionManager.OperationalAnchor;
import divisionai.PermanentDivisionManager.OperationalTemplate;
import divisionai.PermanentDivisionManager.OtcPlay;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedded Deployment Screen modal allowing the player to switch high-level {@link OperationalTemplate}
 * Fighting Styles or customize the four operational sub-axes, multi-division setpiece, and tactical plays
 * temporarily for the current battle without overwriting saved Campaign Fleet Doctrine.
 */
public final class FightingStyleModalDialog {

    private FightingStyleModalDialog() {
    }

    public static CustomPanelAPI createWindowPanel(
            final DivisionDeploymentDialogOrganizer organizer,
            final UIPanelAPI innerPanel,
            final Object reservesGrid,
            final float width,
            final float height,
            final Runnable onStyleChangedCallback) {

        final CustomPanelAPI[] winRef = new CustomPanelAPI[1];
        final CustomPanelAPI[] bodyContainerRef = new CustomPanelAPI[1];
        final ScrollPanelAPI[] scrollerRef = new ScrollPanelAPI[1];
        final ButtonAPI[] closeBtnRef = new ButtonAPI[1];
        final ButtonAPI[] resetBtnRef = new ButtonAPI[1];
        final Map<ButtonAPI, String> activeButtons = new LinkedHashMap<>();
        final List<DivisionUiWidgets.OperationalTemplateBox> templateBoxes = new ArrayList<>();
        final float topBarH = 26f;
        final float bodyH = Math.max(120f, height - topBarH - 4f);
        final float bodyW = Math.max(320f, width - 4f);

        final Runnable[] rebuildBodyRef = new Runnable[1];

        final BaseCustomUIPanelPlugin subPanelPlugin = new BaseCustomUIPanelPlugin();

        final Runnable handleAction = new Runnable() {
            private String pendingCmd = null;

            @Override
            public void run() {
            }
        };
        final String[] pendingCmdHolder = new String[1];

        final Runnable dispatchPendingAction = new Runnable() {
            @Override
            public void run() {
                String cmd = pendingCmdHolder[0];
                pendingCmdHolder[0] = null;
                if (cmd == null) return;
                BattleGroupState pg = organizer.getPlayerGroup();
                if (pg == null) return;
                pg.ensureCampaignDoctrineInherited();

                if (cmd.startsWith("TPL:")) {
                    OperationalTemplate tpl = OperationalTemplate.fromId(cmd.substring(4), null);
                    if (tpl != null) {
                        DivisionDeploymentDialogOrganizer.applyFightingStyleTemplate(pg, tpl);
                    }
                } else if (cmd.startsWith("ANCHOR:")) {
                    OperationalAnchor a = OperationalAnchor.fromId(cmd.substring(7), pg.operationalAnchor);
                    pg.operationalAnchor = a;
                    pg.recordCustomFightingStyleIfNonPreset();
                    DivisionDeploymentDialogOrganizer.playDeploymentButtonSound();
                } else if (cmd.startsWith("POLICY:")) {
                    ObjectiveCapturePolicy p = ObjectiveCapturePolicy.fromId(cmd.substring(7), pg.objectiveCapturePolicy);
                    pg.objectiveCapturePolicy = p;
                    pg.recordCustomFightingStyleIfNonPreset();
                    DivisionDeploymentDialogOrganizer.playDeploymentButtonSound();
                } else if (cmd.startsWith("ARM:")) {
                    DecisiveArm d = DecisiveArm.fromId(cmd.substring(4), pg.decisiveArm);
                    pg.decisiveArm = d;
                    pg.recordCustomFightingStyleIfNonPreset();
                    DivisionDeploymentDialogOrganizer.playDeploymentButtonSound();
                } else if (cmd.startsWith("DISC:")) {
                    EngagementDiscipline e = EngagementDiscipline.fromId(cmd.substring(5), pg.engagementDiscipline);
                    pg.engagementDiscipline = e;
                    pg.recordCustomFightingStyleIfNonPreset();
                    DivisionDeploymentDialogOrganizer.playDeploymentButtonSound();
                } else if ("BATTLE_SETPIECE_PREV".equals(cmd)) {
                    DivisionDeploymentDialogOrganizer.cycleBattleMultiDivisionSetpiece(pg, -1);
                } else if ("BATTLE_SETPIECE_NEXT".equals(cmd)) {
                    DivisionDeploymentDialogOrganizer.cycleBattleMultiDivisionSetpiece(pg, 1);
                } else if (cmd.startsWith("PLAY:")) {
                    String playId = cmd.substring(5);
                    for (OtcPlay p : OtcPlay.values()) {
                        if (p.name().equals(playId)) {
                            if (pg.plays.contains(p)) {
                                pg.plays.remove(p);
                            } else {
                                pg.plays.add(p);
                            }
                            DivisionDeploymentDialogOrganizer.playDeploymentButtonSound();
                            break;
                        }
                    }
                }

                if (onStyleChangedCallback != null) {
                    onStyleChangedCallback.run();
                }
                if (rebuildBodyRef[0] != null) {
                    rebuildBodyRef[0].run();
                }
            }
        };

        final Runnable pollWindowButtons = new Runnable() {
            @Override
            public void run() {
                if (!organizer.isFightingStyleWindowOpen()) return;
                ButtonAPI closeBtn = closeBtnRef[0];
                if (closeBtn != null && closeBtn.isChecked()) {
                    closeBtn.setChecked(false);
                    DivisionDeploymentDialogOrganizer.playDeploymentButtonSound();
                    organizer.closeFightingStyleWindow(innerPanel, reservesGrid);
                    return;
                }
                ButtonAPI resetBtn = resetBtnRef[0];
                if (resetBtn != null && resetBtn.isChecked()) {
                    resetBtn.setChecked(false);
                    DivisionDeploymentDialogOrganizer.resetBattleDoctrineToCampaign(organizer.getPlayerGroup());
                    if (onStyleChangedCallback != null) {
                        onStyleChangedCallback.run();
                    }
                    if (rebuildBodyRef[0] != null) {
                        rebuildBodyRef[0].run();
                    }
                    return;
                }
                for (Map.Entry<ButtonAPI, String> entry : activeButtons.entrySet()) {
                    ButtonAPI b = entry.getKey();
                    if (b != null && b.isChecked()) {
                        b.setChecked(false);
                        pendingCmdHolder[0] = entry.getValue();
                        dispatchPendingAction.run();
                        return;
                    }
                }
                if (handleAction != null && pendingCmdHolder[0] != null) {
                    dispatchPendingAction.run();
                }
            }
        };
        organizer.setActiveFightingStylePollHook(pollWindowButtons);

        BaseCustomUIPanelPlugin winPlugin = new BaseCustomUIPanelPlugin() {
            @Override
            public void advance(float amount) {
                pollWindowButtons.run();
            }

            @Override
            public void processInput(List<InputEventAPI> events) {
                if (!organizer.isFightingStyleWindowOpen() || events == null) return;
                for (InputEventAPI ev : events) {
                    if (ev == null || ev.isConsumed()) continue;
                    if (ev.isLMBDownEvent()) {
                        float mx = ev.getX();
                        float my = ev.getY();
                        for (DivisionUiWidgets.OperationalTemplateBox box : templateBoxes) {
                            if (box.contains(mx, my)) {
                                ev.consume();
                                pendingCmdHolder[0] = "TPL:" + box.template.name();
                                dispatchPendingAction.run();
                                return;
                            }
                        }
                    }
                }
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
                GL11.glColor4f(0.05f, 0.16f, 0.24f, 0.92f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x + 1f, y + h - topBarH);
                GL11.glVertex2f(x + w - 1f, y + h - topBarH);
                GL11.glVertex2f(x + w - 1f, y + h - 1f);
                GL11.glVertex2f(x + 1f, y + h - 1f);
                GL11.glEnd();

                // Window border
                GL11.glLineWidth(1.5f);
                GL11.glColor4f(0.42f, 0.90f, 1.0f, 0.84f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(x + 1f, y + 1f);
                GL11.glVertex2f(x + w - 1f, y + 1f);
                GL11.glVertex2f(x + w - 1f, y + h - 1f);
                GL11.glVertex2f(x + 1f, y + h - 1f);
                GL11.glEnd();

                GL11.glPopAttrib();
            }
        };

        final CustomPanelAPI winPanel = Global.getSettings().createCustom(width, height, winPlugin);
        winRef[0] = winPanel;

        LabelAPI titleLbl = Global.getSettings().createLabel("BATTLE FIGHTING STYLE", Fonts.ORBITRON_12);
        titleLbl.setColor(new Color(125, 235, 255, 255));
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(titleLbl);
        winPanel.addComponent((UIComponentAPI) titleLbl).setSize(218f, 18f).inTL(10f, 4f);

        String scopeNote = "Applies to this battle only (Campaign Doctrine unchanged)";
        LabelAPI scopeLbl = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        scopeLbl.setColor(new Color(255, 212, 105, 245));
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(scopeLbl);
        float scopeMaxW = Math.max(160f, width - 460f);
        winPanel.addComponent((UIComponentAPI) scopeLbl).setSize(scopeMaxW, 16f).inTL(232f, 5f);
        scopeLbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(scopeNote, scopeLbl, scopeMaxW - 4f));

        TooltipMakerAPI topBtnBar = winPanel.createUIElement(width, topBarH, false);
        Color goldBase = new Color(245, 200, 65);
        Color cyanBase = new Color(115, 225, 250);
        Color dark = new Color(18, 30, 42);
        resetBtnRef[0] = topBtnBar.addButton("Campaign Default", "FIGHTING_STYLE_RESET_CAMPAIGN", cyanBase, dark,
                Alignment.MID, CutStyle.ALL, 134f, 18f, 0f);
        resetBtnRef[0].getPosition().inTL(width - 218f, 4f);
        DivisionUiWidgets.attachActionTooltip(topBtnBar, resetBtnRef[0], "FIGHTING_STYLE_RESET_CAMPAIGN");

        closeBtnRef[0] = topBtnBar.addButton("Close", "FIGHTING_STYLE_CLOSE", goldBase, dark,
                Alignment.MID, CutStyle.ALL, 68f, 18f, 0f);
        closeBtnRef[0].getPosition().inTL(width - 76f, 4f);
        DivisionUiWidgets.attachActionTooltip(topBtnBar, closeBtnRef[0], "FIGHTING_STYLE_CLOSE");
        topBtnBar.setHeightSoFar(topBarH);
        winPanel.addUIElement(topBtnBar).inTL(0f, 0f);

        rebuildBodyRef[0] = new Runnable() {
            @Override
            public void run() {
                float prevScroll = (scrollerRef[0] != null) ? scrollerRef[0].getYOffset() : 0f;
                if (bodyContainerRef[0] != null) {
                    winPanel.removeComponent(bodyContainerRef[0]);
                }
                activeButtons.clear();
                templateBoxes.clear();

                CustomPanelAPI bodyContainer = Global.getSettings().createCustom(bodyW, bodyH, subPanelPlugin);
                bodyContainerRef[0] = bodyContainer;
                TooltipMakerAPI scroller = bodyContainer.createUIElement(bodyW, bodyH, true);

                BattleGroupState pg = organizer.getPlayerGroup();
                if (pg != null) {
                    pg.ensureCampaignDoctrineInherited();
                }

                final OperationalTemplate matchedTpl = (pg != null)
                        ? pg.resolveMatchingOperationalTemplate()
                        : OperationalTemplate.LINEAR_WARFARE;
                final OperationalTemplate[] allTpls = OperationalTemplate.values();

                float rowW = bodyW - 18f;
                final float cardSize = 68f;
                final float topPad = 4f;
                final float colGap = 14f;
                final float colW = Math.max(180f, Math.min(260f, (rowW - colGap * 2f) / 3f));
                final float totalSpan = colW * 3f + colGap * 2f;
                final float colStartX = Math.max(6f, (rowW - totalSpan) * 0.5f);
                final float tplPanelH = topPad + cardSize + 50f;
                final CustomPanelAPI[] tplBannerRef = new CustomPanelAPI[1];

                BaseCustomUIPanelPlugin tplPlugin = new BaseCustomUIPanelPlugin() {
                    @Override
                    public void render(float alphaMult) {
                        CustomPanelAPI self = tplBannerRef[0];
                        if (self == null || self.getPosition() == null) return;
                        PositionAPI p = self.getPosition();
                        float px = p.getX();
                        float py = p.getY();
                        float ph = p.getHeight();
                        float cardBottomY = py + ph - topPad - cardSize;
                        for (int i = 0; i < allTpls.length; i++) {
                            OperationalTemplate tpl = allTpls[i];
                            float colMidX = colStartX + i * (colW + colGap) + colW * 0.5f;
                            float cardX = px + Math.round(colMidX - cardSize * 0.5f);
                            FleetDoctrineDialog.renderOperationalTemplateBox(
                                    tpl, cardX, cardBottomY, cardSize, cardSize, tpl == matchedTpl, false, alphaMult);
                        }
                    }
                };

                CustomPanelAPI tplPanel = Global.getSettings().createCustom(rowW, tplPanelH, tplPlugin);
                tplBannerRef[0] = tplPanel;
                TooltipMakerAPI tplUi = tplPanel.createUIElement(rowW, tplPanelH, false);

                Color baseColor = new Color(165, 215, 240);
                Color activeColor = new Color(110, 238, 255);
                Color goldActive = new Color(255, 218, 90);
                Color darkBg = new Color(16, 28, 40);

                float btnW = Math.min(192f, colW - 8f);
                for (int i = 0; i < allTpls.length; i++) {
                    OperationalTemplate tpl = allTpls[i];
                    boolean isActive = (tpl == matchedTpl);
                    float colMidX = colStartX + i * (colW + colGap) + colW * 0.5f;
                    float relCardX = Math.round(colMidX - cardSize * 0.5f);
                    float relCardY = tplPanelH - topPad - cardSize;
                    templateBoxes.add(new DivisionUiWidgets.OperationalTemplateBox(
                            tpl, tplPanel, relCardX, relCardY, cardSize, cardSize));

                    String act = "TPL:" + tpl.name();
                    ButtonAPI bTpl = tplUi.addButton(
                            tpl.shortName, act, isActive ? activeColor : baseColor, darkBg,
                            Alignment.MID, CutStyle.TL_BR, btnW, 22f, 0f);
                    if (isActive) {
                        bTpl.highlight();
                    }
                    bTpl.getPosition().inTL(Math.round(colMidX - btnW * 0.5f), topPad + cardSize + 4f);
                    activeButtons.put(bTpl, act);
                    DivisionUiWidgets.addTooltip(
                            tplUi,
                            bTpl,
                            "Battle Fighting Style: " + tpl.displayName,
                            tpl.summary + " Applies to this battle only; individual axes below can also be adjusted.");
                }

                String statusRaw = (matchedTpl != null)
                        ? matchedTpl.summary
                        : "Custom Battle Style active - select a preset above or fine-tune axes below.";
                LabelAPI sumLbl = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
                sumLbl.setColor(matchedTpl != null ? new Color(195, 225, 245, 245) : goldActive);
                sumLbl.setAlignment(Alignment.MID);
                DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(sumLbl);
                tplPanel.addComponent((UIComponentAPI) sumLbl).setSize(rowW - 20f, 16f).inTL(10f, topPad + cardSize + 30f);
                sumLbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(statusRaw, sumLbl, rowW - 28f));

                tplUi.setHeightSoFar(tplPanelH);
                tplPanel.addUIElement(tplUi).inTL(0f, 0f);
                scroller.addCustom(tplPanel, 4f);

                OperationalAnchor curAnchor = (pg != null) ? pg.operationalAnchor : OperationalAnchor.BATTLE_LINE;
                OperationalAnchor[] anchors = OperationalAnchor.values();
                String[] anchorNames = new String[anchors.length];
                String[] anchorSums = new String[anchors.length];
                String[] anchorCmds = new String[anchors.length];
                int anchorSel = 0;
                for (int i = 0; i < anchors.length; i++) {
                    anchorNames[i] = anchors[i].displayName;
                    anchorSums[i] = anchors[i].summary;
                    anchorCmds[i] = "ANCHOR:" + anchors[i].name();
                    if (anchors[i] == curAnchor) anchorSel = i;
                }
                addSegmentedAxisRow(
                        scroller, subPanelPlugin, activeButtons, rowW,
                        "Battle Group Anchor", anchorNames, anchorSums, anchorCmds, anchorSel,
                        baseColor, activeColor, darkBg);

                ObjectiveCapturePolicy curPol = (pg != null) ? pg.objectiveCapturePolicy : ObjectiveCapturePolicy.LIGHT_FORCES_ONLY;
                ObjectiveCapturePolicy[] pols = ObjectiveCapturePolicy.values();
                String[] polNames = new String[pols.length];
                String[] polSums = new String[pols.length];
                String[] polCmds = new String[pols.length];
                int polSel = 0;
                for (int i = 0; i < pols.length; i++) {
                    polNames[i] = pols[i].displayName;
                    polSums[i] = pols[i].summary;
                    polCmds[i] = "POLICY:" + pols[i].name();
                    if (pols[i] == curPol) polSel = i;
                }
                addSegmentedAxisRow(
                        scroller, subPanelPlugin, activeButtons, rowW,
                        "Objective Capture", polNames, polSums, polCmds, polSel,
                        baseColor, activeColor, darkBg);

                DecisiveArm curArm = (pg != null) ? pg.decisiveArm : DecisiveArm.SURFACE_GUNFIRE;
                DecisiveArm[] arms = DecisiveArm.values();
                String[] armNames = new String[arms.length];
                String[] armSums = new String[arms.length];
                String[] armCmds = new String[arms.length];
                int armSel = 0;
                for (int i = 0; i < arms.length; i++) {
                    armNames[i] = arms[i].displayName;
                    armSums[i] = arms[i].summary;
                    armCmds[i] = "ARM:" + arms[i].name();
                    if (arms[i] == curArm) armSel = i;
                }
                addSegmentedAxisRow(
                        scroller, subPanelPlugin, activeButtons, rowW,
                        "Primary Striking Arm", armNames, armSums, armCmds, armSel,
                        baseColor, activeColor, darkBg);

                EngagementDiscipline curDisc = (pg != null) ? pg.engagementDiscipline : EngagementDiscipline.LINE_COHESION;
                EngagementDiscipline[] discs = EngagementDiscipline.values();
                String[] discNames = new String[discs.length];
                String[] discSums = new String[discs.length];
                String[] discCmds = new String[discs.length];
                int discSel = 0;
                for (int i = 0; i < discs.length; i++) {
                    discNames[i] = discs[i].displayName;
                    discSums[i] = discs[i].summary;
                    discCmds[i] = "DISC:" + discs[i].name();
                    if (discs[i] == curDisc) discSel = i;
                }
                addSegmentedAxisRow(
                        scroller, subPanelPlugin, activeButtons, rowW,
                        "Engagement Discipline", discNames, discSums, discCmds, discSel,
                        baseColor, activeColor, darkBg);

                // Row 5: Multi-Division Setpiece Cycler
                MultiDivisionSetpiece curSp = (pg != null && pg.multiDivisionSetpieceDoctrine != null)
                        ? pg.multiDivisionSetpieceDoctrine
                        : MultiDivisionSetpiece.AUTO_DOCTRINAL;
                addSetpieceCycleRow(scroller, subPanelPlugin, activeButtons, rowW, curSp, baseColor, goldActive, darkBg);

                // Row 6: Tactical Plays Toggles
                addTacticalPlaysRow(scroller, subPanelPlugin, activeButtons, rowW, pg, baseColor, activeColor, darkBg);

                bodyContainer.addUIElement(scroller).inTL(2f, 0f);
                scrollerRef[0] = scroller.getExternalScroller();
                if (scrollerRef[0] != null && prevScroll > 0f) {
                    scrollerRef[0].setYOffset(prevScroll);
                }
                winPanel.addComponent(bodyContainer).setSize(bodyW, bodyH).inTL(2f, topBarH + 2f);
            }
        };

        rebuildBodyRef[0].run();
        return winPanel;
    }

    private static void addSegmentedAxisRow(
            TooltipMakerAPI scroller,
            BaseCustomUIPanelPlugin subPanelPlugin,
            Map<ButtonAPI, String> activeButtons,
            float rowW,
            String rowTitle,
            String[] names,
            String[] summaries,
            String[] cmds,
            int selectedIdx,
            Color baseColor,
            Color activeColor,
            Color darkBg) {
        float rowH = 28f;
        float labelColW = 178f;
        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowW, rowH, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowW, rowH, false);

        LabelAPI lbl = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        lbl.setColor(new Color(215, 232, 245, 250));
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(lbl);
        rowPanel.addComponent((UIComponentAPI) lbl).setSize(labelColW - 8f, 16f).inTL(6f, 6f);
        lbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(rowTitle, lbl, labelColW - 12f));

        float availW = Math.max(240f, rowW - labelColW - 6f);
        float gap = 6f;
        int n = names.length;
        float pillW = (float) Math.floor((availW - gap * (n - 1)) / n);
        for (int i = 0; i < n; i++) {
            boolean active = (i == selectedIdx);
            ButtonAPI btn = rowUi.addButton(
                    names[i], cmds[i], active ? activeColor : baseColor, darkBg,
                    Alignment.MID, CutStyle.TL_BR, pillW, 22f, 0f);
            if (active) {
                btn.highlight();
            }
            btn.getPosition().inTL(labelColW + i * (pillW + gap), 3f);
            activeButtons.put(btn, cmds[i]);
            DivisionUiWidgets.addTooltip(rowUi, btn, rowTitle + ": " + names[i], summaries[i]);
        }

        rowUi.setHeightSoFar(rowH);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        scroller.addCustom(rowPanel, 3f);
    }

    private static void addSetpieceCycleRow(
            TooltipMakerAPI scroller,
            BaseCustomUIPanelPlugin subPanelPlugin,
            Map<ButtonAPI, String> activeButtons,
            float rowW,
            MultiDivisionSetpiece curSp,
            Color baseColor,
            Color goldActive,
            Color darkBg) {
        float rowH = 28f;
        float labelColW = 178f;
        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowW, rowH, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowW, rowH, false);

        LabelAPI lbl = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        lbl.setColor(new Color(215, 232, 245, 250));
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(lbl);
        rowPanel.addComponent((UIComponentAPI) lbl).setSize(labelColW - 8f, 16f).inTL(6f, 6f);
        lbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth("Fleet Setpiece", lbl, labelColW - 12f));

        ButtonAPI bPrev = rowUi.addButton("<", "BATTLE_SETPIECE_PREV", goldActive, darkBg,
                Alignment.MID, CutStyle.ALL, 24f, 22f, 0f);
        bPrev.getPosition().inTL(labelColW, 3f);
        activeButtons.put(bPrev, "BATTLE_SETPIECE_PREV");
        DivisionUiWidgets.attachActionTooltip(rowUi, bPrev, "BATTLE_SETPIECE_PREV");

        float valBoxW = 216f;
        LabelAPI valLbl = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        valLbl.setColor(goldActive);
        valLbl.setAlignment(Alignment.MID);
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(valLbl);
        rowPanel.addComponent((UIComponentAPI) valLbl).setSize(valBoxW, 18f).inTL(labelColW + 28f, 5f);
        valLbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(curSp.displayName, valLbl, valBoxW - 6f));

        ButtonAPI bNext = rowUi.addButton(">", "BATTLE_SETPIECE_NEXT", goldActive, darkBg,
                Alignment.MID, CutStyle.ALL, 24f, 22f, 0f);
        bNext.getPosition().inTL(labelColW + 28f + valBoxW + 4f, 3f);
        activeButtons.put(bNext, "BATTLE_SETPIECE_NEXT");
        DivisionUiWidgets.attachActionTooltip(rowUi, bNext, "BATTLE_SETPIECE_NEXT");

        float sumX = labelColW + 28f + valBoxW + 38f;
        float sumW = Math.max(120f, rowW - sumX - 6f);
        LabelAPI spSumLbl = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        spSumLbl.setColor(new Color(175, 208, 228, 240));
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(spSumLbl);
        rowPanel.addComponent((UIComponentAPI) spSumLbl).setSize(sumW, 16f).inTL(sumX, 6f);
        spSumLbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(curSp.summary, spSumLbl, sumW - 6f));

        rowUi.setHeightSoFar(rowH);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        scroller.addCustom(rowPanel, 3f);
    }

    private static void addTacticalPlaysRow(
            TooltipMakerAPI scroller,
            BaseCustomUIPanelPlugin subPanelPlugin,
            Map<ButtonAPI, String> activeButtons,
            float rowW,
            BattleGroupState pg,
            Color baseColor,
            Color activeColor,
            Color darkBg) {
        float rowH = 28f;
        float labelColW = 178f;
        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowW, rowH, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowW, rowH, false);

        LabelAPI lbl = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        lbl.setColor(new Color(215, 232, 245, 250));
        DivisionDeploymentDialogOrganizer.assertCoolTacticalFonts(lbl);
        rowPanel.addComponent((UIComponentAPI) lbl).setSize(labelColW - 8f, 16f).inTL(6f, 6f);
        lbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth("OTC Tactical Plays", lbl, labelColW - 12f));

        OtcPlay[] plays = OtcPlay.values();
        String[] shortNames = {
                "Line & Fire: ",
                "Doubling: ",
                "Time-on-Target: ",
                "Reserve & Chase: "
        };
        float availW = Math.max(240f, rowW - labelColW - 6f);
        float gap = 6f;
        int n = plays.length;
        float pillW = (float) Math.floor((availW - gap * (n - 1)) / n);
        for (int i = 0; i < n; i++) {
            OtcPlay p = plays[i];
            boolean enabled = (pg == null) || pg.plays.contains(p);
            String prefix = (i < shortNames.length) ? shortNames[i] : (p.displayName + ": ");
            String txt = prefix + (enabled ? "On" : "Off");
            String cmd = "PLAY:" + p.name();
            ButtonAPI btn = rowUi.addButton(
                    txt, cmd, enabled ? activeColor : baseColor, darkBg,
                    Alignment.MID, CutStyle.TL_BR, pillW, 22f, 0f);
            if (enabled) {
                btn.highlight();
            }
            btn.getPosition().inTL(labelColW + i * (pillW + gap), 3f);
            activeButtons.put(btn, cmd);
            DivisionUiWidgets.addTooltip(rowUi, btn, p.displayName, p.summary);
        }

        rowUi.setHeightSoFar(rowH);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        scroller.addCustom(rowPanel, 4f);
    }
}
