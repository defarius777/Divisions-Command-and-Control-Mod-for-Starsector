package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionNomenclature.NumberingStyle;
import divisionai.DivisionNomenclature.PlayerNamingPreset;
import divisionai.PermanentDivisionManager.AutoAssignCapability;
import divisionai.PermanentDivisionManager.AutoAssignHull;
import divisionai.PermanentDivisionManager.DivisionFormation;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.DivisionMovementStickiness;
import divisionai.PermanentDivisionManager.DivisionRammingMode;
import divisionai.PermanentDivisionManager.DivisionRammingTargetScope;
import divisionai.PermanentDivisionManager.DivisionSetpieceAttack;
import divisionai.PermanentDivisionManager.FleetWideFormation;
import divisionai.PermanentDivisionManager.LineStation;
import divisionai.PermanentDivisionManager.MultiDivisionSetpiece;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.OtcPlay;
import divisionai.PermanentDivisionManager.ShipSkillCoordinationMode;
import divisionai.PermanentDivisionManager.SquadronFormation;
import divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode;
import divisionai.PermanentDivisionManager.VoidstrikeTargetObservance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared UI widget builders, OpenGL modal/pennant primitives, balanced text splitters,
 * and common geometry helpers used across {@link FleetDoctrineDialog}, {@link DivisionEditorDialog},
 * {@link DivisionDeploymentDialogOrganizer}, and {@link CampaignFleetTooltipOrganizer}.
 */
public final class DivisionUiWidgets {

    private DivisionUiWidgets() {
    }

    public interface ButtonRegistrar {
        ButtonAPI register(ButtonAPI button, String actionId);
    }

    public interface ButtonPressRelay {
        void onButtonPressed(Object buttonId);
    }

    public static final class OtcPennantBox {
        public final OtcAuthority authority;
        public final CustomPanelAPI hostPanel;
        public final float relX;
        public final float relY;
        public final float width;
        public final float height;

        public OtcPennantBox(
                OtcAuthority authority,
                CustomPanelAPI hostPanel,
                float relX,
                float relY,
                float width,
                float height) {
            this.authority = authority;
            this.hostPanel = hostPanel;
            this.relX = relX;
            this.relY = relY;
            this.width = width;
            this.height = height;
        }

        public boolean contains(float screenX, float screenY) {
            if (hostPanel == null || hostPanel.getPosition() == null) return false;
            PositionAPI p = hostPanel.getPosition();
            float bx = p.getX() + relX;
            float by = p.getY() + relY;
            return screenX >= bx && screenX <= (bx + width) && screenY >= by && screenY <= (by + height);
        }
    }

    public static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Renders the standard full-screen dimmer backdrop, solid dark modal panel fill, and player-color border.
     */
    public static void renderModalDialogBackdrop(
            CustomPanelAPI contentContainer,
            float dimR,
            float dimG,
            float dimB,
            float dimAlpha,
            float panelR,
            float panelG,
            float panelB,
            float borderAlpha,
            float alphaMult) {
        if (contentContainer == null || contentContainer.getPosition() == null) return;
        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        PositionAPI pos = contentContainer.getPosition();
        float x = pos.getX();
        float y = pos.getY();
        float w = pos.getWidth();
        float h = pos.getHeight();

        Color border = Misc.getBasePlayerColor();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // 1. Full-screen backdrop dimmer
        GL11.glColor4f(dimR, dimG, dimB, dimAlpha * alphaMult);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0f, 0f);
        GL11.glVertex2f(screenW, 0f);
        GL11.glVertex2f(screenW, screenH);
        GL11.glVertex2f(0f, screenH);
        GL11.glEnd();

        // 2. Modal panel background
        GL11.glColor4f(panelR, panelG, panelB, 0.96f * alphaMult);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();

        // 3. Outer border
        GL11.glLineWidth(2.0f);
        GL11.glColor4f(
                border.getRed() / 255f,
                border.getGreen() / 255f,
                border.getBlue() / 255f,
                borderAlpha * alphaMult);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();

        GL11.glLineWidth(1.0f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * Wraps a {@link LabelAPI} in-place to {@code maxWidth} pixels using Starsector's native bitmap
     * font wrapper ({@link LabelAPI#autoSizeToWidth(float)}), anchors it at {@code (x, y)}, and returns
     * its bottom Y extent ({@code y + wrappedHeight}).
     */
    public static float wrapLabelInPlace(LabelAPI lbl, float maxWidth, float x, float y) {
        if (lbl == null) return y;
        float safeW = Math.max(48f, (float) Math.floor(maxWidth));
        PositionAPI pos = lbl.autoSizeToWidth(safeW);
        if (pos == null) {
            pos = lbl.getPosition();
        }
        if (pos != null) {
            pos.inTL(x, y);
            return y + Math.max(15f, pos.getHeight());
        }
        return y + 16f;
    }

    /**
     * Resizes both a row's {@link CustomPanelAPI} and its {@link TooltipMakerAPI} when wrapped multi-line
     * labels or stacked sub-rows exceed {@code baseRowHeight}.
     */
    public static void resizeRowToFit(CustomPanelAPI rowPanel, TooltipMakerAPI rowUi, float rowWidth, float requiredHeight) {
        float h = (float) Math.ceil(requiredHeight);
        if (rowUi != null) {
            rowUi.setHeightSoFar(h);
            if (rowUi.getPosition() != null) {
                rowUi.getPosition().setSize(rowWidth, h);
            }
        }
        if (rowPanel != null && rowPanel.getPosition() != null) {
            rowPanel.getPosition().setSize(rowWidth, h);
        }
    }

    /**
     * Fast vertical screen-bounds check so off-screen rows inside a scroll panel skip custom OpenGL draws.
     */
    public static boolean isPanelVisibleOnScreen(CustomPanelAPI panel) {
        if (panel == null || panel.getPosition() == null) return false;
        PositionAPI p = panel.getPosition();
        float py = p.getY();
        float ph = p.getHeight();
        float screenH = (Global.getSettings() != null && Global.getSettings().getScreenHeight() > 0f)
                ? Global.getSettings().getScreenHeight()
                : 1080f;
        return (py + ph >= -32f) && (py <= screenH + 32f);
    }

    /**
     * Generic option-strip row builder used for Line Station, Movement Stickiness, Setpiece Attack,
     * Voidstrike Observance, and Voidstrike Coordination in both Fleet Doctrine and Configure Division.
     */
    public static void addOptionStripRow(
            TooltipMakerAPI parent,
            float rowWidth,
            float rowHeight,
            CustomUIPanelPlugin panelPlugin,
            ButtonRegistrar registrar,
            String labelText,
            String labelHighlightSpan,
            Color labelColor,
            float labelX,
            float labelY,
            float controlColX,
            String[] optionLabels,
            String[] optionActionIds,
            int selectedIndex,
            float btnWidth,
            float btnHeight,
            float btnStride,
            Color baseColor,
            Color darkColor,
            Color selectedBtnColor,
            String inheritActionId,
            boolean isCustomValue,
            float inheritBtnWidth,
            Color inheritActiveColor,
            String summaryText,
            Color summaryColor,
            float summaryY) {

        boolean hasInherit = (inheritActionId != null);
        float singleLineControlsEnd = controlColX + (optionLabels.length * btnStride) + (hasInherit ? inheritBtnWidth : 0f);
        boolean stackButtonsBelow = hasInherit && (singleLineControlsEnd > rowWidth - 6f);

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, rowHeight, panelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, rowHeight, false);
        LabelAPI rowLbl = rowUi.addPara(labelText, 0f, labelColor, labelHighlightSpan != null ? labelHighlightSpan : labelText);

        float maxLabelW = stackButtonsBelow
                ? (rowWidth - inheritBtnWidth - labelX - 12f)
                : (controlColX - labelX - 8f);
        float lblBottom = wrapLabelInPlace(rowLbl, maxLabelW, labelX, labelY);
        attachRowLabelTooltip(rowUi, (UIComponentAPI) rowLbl, labelText);

        float btnY = stackButtonsBelow ? Math.max(24f, lblBottom + 3f) : 3f;
        float xCursor = stackButtonsBelow ? labelX : controlColX;
        float effBtnStride = btnStride;
        float effBtnWidth = btnWidth;
        if (stackButtonsBelow && optionLabels.length > 0) {
            float availBtnW = rowWidth - labelX - 6f;
            if (optionLabels.length * effBtnStride > availBtnW) {
                effBtnStride = (float) Math.floor(availBtnW / optionLabels.length);
                effBtnWidth = Math.max(44f, effBtnStride - 4f);
            }
        }

        for (int i = 0; i < optionLabels.length; i++) {
            boolean selected = (i == selectedIndex);
            String act = optionActionIds[i];
            ButtonAPI btn = registrar.register(
                    rowUi.addButton(
                            optionLabels[i],
                            act,
                            selected ? selectedBtnColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            effBtnWidth,
                            btnHeight,
                            0f),
                    act);
            if (selected) {
                btn.highlight();
            }
            btn.getPosition().inTL(xCursor, btnY);
            xCursor += effBtnStride;
        }

        if (hasInherit) {
            ButtonAPI bInh = registrar.register(
                    rowUi.addButton(
                            "Inherit",
                            inheritActionId,
                            !isCustomValue ? inheritActiveColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            inheritBtnWidth,
                            btnHeight,
                            0f),
                    inheritActionId);
            if (!isCustomValue) {
                bInh.highlight();
            }
            if (stackButtonsBelow) {
                bInh.getPosition().inTL(rowWidth - inheritBtnWidth - 4f, 2f);
            } else {
                bInh.getPosition().inTL(xCursor, 3f);
                xCursor += inheritBtnWidth + 4f;
            }
        }

        float controlsBottom = btnY + btnHeight;
        float sumBottom = 0f;
        if (summaryText != null && !summaryText.isEmpty()) {
            LabelAPI sumLbl = rowUi.addPara(summaryText, 0f, summaryColor);
            float sumStartX = xCursor + 8f;
            float availRightW = rowWidth - sumStartX - 8f;
            if (!stackButtonsBelow && availRightW >= 180f) {
                sumBottom = wrapLabelInPlace(sumLbl, availRightW, sumStartX, summaryY);
            } else {
                float belowX = stackButtonsBelow ? labelX : controlColX;
                float belowY = Math.max(controlsBottom, lblBottom) + 3f;
                sumBottom = wrapLabelInPlace(sumLbl, rowWidth - belowX - 8f, belowX, belowY);
            }
            attachRowLabelTooltip(rowUi, (UIComponentAPI) sumLbl, labelText);
        }

        float finalRowH = Math.max(rowHeight, Math.max(Math.max(lblBottom, controlsBottom), sumBottom) + 4f);
        resizeRowToFit(rowPanel, rowUi, rowWidth, finalRowH);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        parent.addCustom(rowPanel, 2f);
    }

    /**
     * Shared Squadron Formation selector row builder for Fleet Doctrine (single-row) and Configure Division (2-line with Inherit).
     */
    public static void addSquadronFormationRow(
            TooltipMakerAPI parent,
            float rowWidth,
            float controlColX,
            final ButtonRegistrar registrar,
            final ButtonPressRelay pressRelay,
            String labelText,
            final SquadronFormation currentFormation,
            final SquadronFormation[] options,
            String actionPrefix,
            final boolean hasInheritButton,
            final boolean isCustom,
            final boolean isFlagshipDivision,
            final Color baseColor,
            Color darkColor,
            final Color highlight,
            final Color activeColor) {

        final float btnW = hasInheritButton ? 96f : 112f;
        final float btnGap = hasInheritButton ? 5f : 6f;
        final float baseRowH = hasInheritButton ? 50f : 28f;
        final float btnH = hasInheritButton ? 20f : 22f;
        final float iconXOffset = hasInheritButton ? 11f : 12f;
        final float iconSize = hasInheritButton ? 14f : 15f;
        final ButtonAPI[] formButtons = new ButtonAPI[options.length];
        final CustomPanelAPI[] rowPanelRef = new CustomPanelAPI[1];

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, baseRowH, new BaseCustomUIPanelPlugin() {
            @Override
            public void buttonPressed(Object buttonId) {
                if (pressRelay != null) {
                    pressRelay.onButtonPressed(buttonId);
                }
            }

            @Override
            public void render(float alphaMult) {
                if (!isPanelVisibleOnScreen(rowPanelRef[0])) return;
                for (int i = 0; i < options.length; i++) {
                    SquadronFormation sf = options[i];
                    ButtonAPI btn = formButtons[i];
                    if (btn == null || btn.getPosition() == null) continue;
                    PositionAPI bp = btn.getPosition();
                    boolean selected = (sf == currentFormation);
                    Color selCol = hasInheritButton ? (isCustom ? highlight : activeColor) : activeColor;
                    FleetDoctrineDialog.renderFormationIcon(
                            sf.iconFormation,
                            bp.getX() + iconXOffset,
                            bp.getY() + bp.getHeight() * 0.5f,
                            iconSize,
                            selected ? selCol : baseColor,
                            alphaMult,
                            isFlagshipDivision);
                }
            }
        });
        rowPanelRef[0] = rowPanel;

        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, baseRowH, false);
        Color lblColor = hasInheritButton ? (isCustom ? highlight : baseColor) : activeColor;
        LabelAPI rowLbl = rowUi.addPara(labelText, 0f, lblColor, labelText);
        float lblX = hasInheritButton ? 4f : 6f;
        float lblY = hasInheritButton ? 4f : 5f;
        float maxLblW = hasInheritButton ? (rowWidth - 96f) : (controlColX - lblX - 8f);
        float lblBottom = wrapLabelInPlace(rowLbl, maxLblW, lblX, lblY);
        attachRowLabelTooltip(rowUi, (UIComponentAPI) rowLbl, labelText);

        if (hasInheritButton) {
            String inhAct = actionPrefix + "INHERIT";
            ButtonAPI bInh = registrar.register(
                    rowUi.addButton(
                            "Inherit",
                            inhAct,
                            !isCustom ? activeColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            82f,
                            20f,
                            0f),
                    inhAct);
            if (!isCustom) bInh.highlight();
            bInh.getPosition().inTL(rowWidth - 86f, 2f);
        }

        float by = hasInheritButton ? Math.max(25f, lblBottom + 3f) : 3f;
        for (int i = 0; i < options.length; i++) {
            SquadronFormation sf = options[i];
            boolean selected = (sf == currentFormation);
            float bx = hasInheritButton ? (4f + i * (btnW + btnGap)) : (controlColX + i * (btnW + btnGap));
            String act = actionPrefix + sf.name();
            Color selCol = hasInheritButton ? (isCustom ? highlight : activeColor) : activeColor;
            ButtonAPI bForm = registrar.register(
                    rowUi.addButton(
                            (hasInheritButton ? "   " : "    ") + sf.shortName,
                            act,
                            selected ? selCol : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            btnW,
                            btnH,
                            0f),
                    act);
            if (selected) bForm.highlight();
            bForm.getPosition().inTL(bx, by);
            formButtons[i] = bForm;
        }

        float finalRowH = Math.max(baseRowH, Math.max(lblBottom, by + btnH) + 4f);
        resizeRowToFit(rowPanel, rowUi, rowWidth, finalRowH);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        parent.addCustom(rowPanel, 2f);
    }

    /**
     * Shared Opening / Main Mission dropdown row builder for Fleet Doctrine and Configure Division.
     */
    public static void addMissionDropdownRow(
            TooltipMakerAPI parent,
            float rowWidth,
            float controlColX,
            CustomUIPanelPlugin subPanelPlugin,
            ButtonRegistrar registrar,
            String labelText,
            String dropdownKey,
            String expandedDropdownKey,
            DivisionMissionType currentMission,
            DivisionMissionType[] primaryOptions,
            boolean includeAllFallbackOptions,
            boolean hasInheritOption,
            boolean isCustom,
            boolean showLineStationHint,
            String selectActionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeColor,
            Color gray) {

        boolean expanded = dropdownKey.equals(expandedDropdownKey);
        float mainRowH = hasInheritOption ? 26f : 28f;
        float optRowH = hasInheritOption ? 24f : 26f;
        float toggleBtnW = hasInheritOption ? 286f : 268f;
        float toggleBtnH = hasInheritOption ? 21f : 22f;
        float optBtnW = hasInheritOption ? 274f : 256f;
        float hintColX = controlColX + (hasInheritOption ? 294f : 280f);

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, mainRowH, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, mainRowH, false);

        LabelAPI rowLbl = rowUi.addPara(labelText, 0f, baseColor);
        float lblX = hasInheritOption ? 4f : 6f;
        float lblY = hasInheritOption ? 4f : 5f;
        float lblBottom = wrapLabelInPlace(rowLbl, controlColX - lblX - 8f, lblX, lblY);
        attachRowLabelTooltip(rowUi, (UIComponentAPI) rowLbl, labelText);

        String toggleAct = "TOGGLE_DD:" + dropdownKey;
        String btnText = (expanded ? "[^] " : "[v] ") + currentMission.dropdownLabel;
        Color toggleColor = hasInheritOption ? (isCustom ? activeColor : baseColor) : activeColor;
        ButtonAPI bToggle = registrar.register(
                rowUi.addButton(btnText, toggleAct, toggleColor, darkColor, Alignment.LMID, CutStyle.TL_BR, toggleBtnW, toggleBtnH, 0f),
                toggleAct);
        if (expanded) bToggle.highlight();
        float btnY = hasInheritOption ? 2f : 3f;
        bToggle.getPosition().inTL(controlColX, btnY);
        addTooltip(
                rowUi,
                bToggle,
                labelText.replace(":", "") + ": " + currentMission.dropdownLabel,
                currentMission.summary
                        + (currentMission.autoLineStation != null
                        ? (" (Default Line Station: " + currentMission.autoLineStation.displayName + ").")
                        : ""));

        float hintBottom = 0f;
        String topHint = showLineStationHint
                ? (currentMission.autoLineStation != null ? currentMission.autoLineStation.displayName : "")
                : currentMission.summary;
        if (topHint != null && !topHint.isEmpty()) {
            LabelAPI hintLbl = rowUi.addPara(topHint, 0f, gray);
            float availHintW = rowWidth - hintColX - 8f;
            if (availHintW >= 68f) {
                hintBottom = wrapLabelInPlace(hintLbl, availHintW, hintColX, hasInheritOption ? 4f : 5f);
            } else {
                hintBottom = wrapLabelInPlace(hintLbl, rowWidth - controlColX - 8f, controlColX, btnY + toggleBtnH + 3f);
            }
            addTooltip(
                    rowUi,
                    (UIComponentAPI) hintLbl,
                    currentMission.dropdownLabel,
                    currentMission.summary);
        }

        float finalMainH = Math.max(mainRowH, Math.max(Math.max(lblBottom, btnY + toggleBtnH), hintBottom) + 4f);
        resizeRowToFit(rowPanel, rowUi, rowWidth, finalMainH);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        parent.addCustom(rowPanel, 2f);

        if (expanded) {
            if (hasInheritOption) {
                CustomPanelAPI inhPanel = Global.getSettings().createCustom(rowWidth, optRowH, subPanelPlugin);
                TooltipMakerAPI inhUi = inhPanel.createUIElement(rowWidth, optRowH, false);
                String inhAct = selectActionPrefix + "INHERIT";
                ButtonAPI bInh = registrar.register(
                        inhUi.addButton(
                                (!isCustom ? "* " : "   ") + "Inherit Doctrine Default",
                                inhAct,
                                !isCustom ? activeColor : baseColor,
                                darkColor,
                                Alignment.LMID,
                                CutStyle.ALL,
                                optBtnW,
                                20f,
                                0f),
                        inhAct);
                if (!isCustom) bInh.highlight();
                bInh.getPosition().inTL(controlColX + 12f, 2f);
                inhUi.setHeightSoFar(optRowH);
                inhPanel.addUIElement(inhUi).inTL(0f, 0f);
                parent.addCustom(inhPanel, 1f);
            }

            List<DivisionMissionType> ordered = new ArrayList<>();
            if (primaryOptions != null) {
                for (DivisionMissionType m : primaryOptions) {
                    ordered.add(m);
                }
            }
            if (includeAllFallbackOptions || ordered.isEmpty()) {
                for (DivisionMissionType m : DivisionMissionType.values()) {
                    if (!ordered.contains(m)) {
                        ordered.add(m);
                    }
                }
            }

            for (DivisionMissionType opt : ordered) {
                boolean selected = hasInheritOption ? (isCustom && opt == currentMission) : (opt == currentMission);
                CustomPanelAPI optPanel = Global.getSettings().createCustom(rowWidth, optRowH, subPanelPlugin);
                TooltipMakerAPI optUi = optPanel.createUIElement(rowWidth, optRowH, false);
                String selAct = selectActionPrefix + opt.name();
                String hintText = showLineStationHint
                        ? (opt.autoLineStation != null ? opt.autoLineStation.displayName : "")
                        : opt.summary;
                ButtonAPI bOpt = registrar.register(
                        optUi.addButton(
                                (selected ? "* " : "   ") + opt.dropdownLabel,
                                selAct,
                                selected ? activeColor : baseColor,
                                darkColor,
                                Alignment.LMID,
                                CutStyle.ALL,
                                optBtnW,
                                20f,
                                0f),
                        selAct);
                if (selected) bOpt.highlight();
                float optBtnY = hasInheritOption ? 2f : 3f;
                bOpt.getPosition().inTL(controlColX + 12f, optBtnY);
                float optHintBottom = 0f;
                if (hintText != null && !hintText.isEmpty()) {
                    LabelAPI optHintLbl = optUi.addPara(hintText, 0f, selected ? activeColor : gray);
                    float availOptHintW = rowWidth - hintColX - 8f;
                    if (availOptHintW >= 68f) {
                        optHintBottom = wrapLabelInPlace(optHintLbl, availOptHintW, hintColX, 4f);
                    } else {
                        optHintBottom = wrapLabelInPlace(optHintLbl, rowWidth - (controlColX + 12f) - 8f, controlColX + 12f, optBtnY + 22f);
                    }
                }
                float finalOptH = Math.max(optRowH, Math.max(optBtnY + 20f, optHintBottom) + 3f);
                resizeRowToFit(optPanel, optUi, rowWidth, finalOptH);
                optPanel.addUIElement(optUi).inTL(0f, 0f);
                parent.addCustom(optPanel, hasInheritOption ? 1f : 2f);
            }
        }
    }

    /**
     * Shared Strike Depth / Strike Width numeric button strip for Fleet Doctrine and Configure Division.
     */
    public static void addVoidstrikeDepthRow(
            TooltipMakerAPI parent,
            float rowWidth,
            float rowHeight,
            float controlColX,
            CustomUIPanelPlugin subPanelPlugin,
            ButtonRegistrar registrar,
            String labelText,
            Color labelColor,
            float labelX,
            float labelY,
            int currentValue,
            String actionPrefix,
            boolean hasPlusMinusButtons,
            boolean hasInheritButton,
            boolean isCustom,
            float btnW,
            float btnH,
            float btnStride,
            Color baseColor,
            Color darkColor,
            Color selectedBtnColor,
            Color inheritActiveColor) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, rowHeight, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, rowHeight, false);
        LabelAPI rowLbl = rowUi.addPara(labelText, 0f, labelColor, labelText);
        float startX = hasInheritButton ? (controlColX - 24f) : controlColX;
        float lblBottom = wrapLabelInPlace(rowLbl, startX - labelX - 8f, labelX, labelY);
        attachRowLabelTooltip(rowUi, (UIComponentAPI) rowLbl, labelText);

        float xCursor = startX;
        if (hasPlusMinusButtons) {
            String decAct = actionPrefix + Math.max(PermanentDivisionManager.MIN_VOIDSTRIKE_SQUADRON_DEPTH, currentValue - 1);
            ButtonAPI bDec = registrar.register(
                    rowUi.addButton("-", decAct, baseColor, darkColor, Alignment.MID, CutStyle.ALL, 32f, btnH, 0f),
                    decAct);
            bDec.setEnabled(currentValue > PermanentDivisionManager.MIN_VOIDSTRIKE_SQUADRON_DEPTH);
            bDec.getPosition().inTL(xCursor, 3f);
            xCursor += 38f;
        }

        for (int v = PermanentDivisionManager.MIN_VOIDSTRIKE_SQUADRON_DEPTH;
             v <= PermanentDivisionManager.MAX_VOIDSTRIKE_SQUADRON_DEPTH;
             v++) {
            boolean selected = (v == currentValue);
            String valAct = actionPrefix + v;
            ButtonAPI bVal = registrar.register(
                    rowUi.addButton(
                            String.valueOf(v),
                            valAct,
                            selected ? selectedBtnColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            btnW,
                            btnH,
                            0f),
                    valAct);
            if (selected) bVal.highlight();
            bVal.getPosition().inTL(xCursor, 3f);
            xCursor += btnStride;
        }

        if (hasPlusMinusButtons) {
            String incAct = actionPrefix + Math.min(PermanentDivisionManager.MAX_VOIDSTRIKE_SQUADRON_DEPTH, currentValue + 1);
            ButtonAPI bInc = registrar.register(
                    rowUi.addButton("+", incAct, baseColor, darkColor, Alignment.MID, CutStyle.ALL, 32f, btnH, 0f),
                    incAct);
            bInc.setEnabled(currentValue < PermanentDivisionManager.MAX_VOIDSTRIKE_SQUADRON_DEPTH);
            bInc.getPosition().inTL(xCursor, 3f);
        }

        if (hasInheritButton) {
            String inhAct = actionPrefix + "INHERIT";
            ButtonAPI bInh = registrar.register(
                    rowUi.addButton(
                            "Inherit",
                            inhAct,
                            !isCustom ? inheritActiveColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            68f,
                            btnH,
                            0f),
                    inhAct);
            if (!isCustom) bInh.highlight();
            bInh.getPosition().inTL(xCursor + 4f, 3f);
        }

        float finalRowH = Math.max(rowHeight, Math.max(lblBottom, 3f + btnH) + 4f);
        resizeRowToFit(rowPanel, rowUi, rowWidth, finalRowH);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        parent.addCustom(rowPanel, 2f);
    }

    public static final float DEFAULT_TOOLTIP_WIDTH = 390f;
    private static TooltipMakerAPI fallbackTooltipHost = null;

    public static void resetFallbackTooltipHost() {
        fallbackTooltipHost = null;
    }

    private static TooltipMakerAPI resolveTooltipHost(TooltipMakerAPI host) {
        if (host != null) {
            return host;
        }
        if (fallbackTooltipHost == null) {
            try {
                if (Global.getSettings() != null) {
                    CustomPanelAPI dummy = Global.getSettings().createCustom(16f, 16f, null);
                    if (dummy != null) {
                        fallbackTooltipHost = dummy.createUIElement(16f, 16f, false);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return fallbackTooltipHost;
    }

    public static void addTooltip(
            TooltipMakerAPI host,
            UIComponentAPI target,
            final String title,
            final String body) {
        addTooltip(host, target, title, body, null, null);
    }

    public static void addTooltip(
            TooltipMakerAPI host,
            UIComponentAPI target,
            final String title,
            final String body,
            final String positiveNote,
            final String negativeNote) {
        if (target == null || (title == null && body == null)) return;
        TooltipMakerAPI effHost = resolveTooltipHost(host);
        if (effHost == null) return;
        try {
            effHost.addTooltipTo(new TooltipMakerAPI.TooltipCreator() {
                @Override
                public boolean isTooltipExpandable(Object tooltipParam) {
                    return false;
                }

                @Override
                public float getTooltipWidth(Object tooltipParam) {
                    return DEFAULT_TOOLTIP_WIDTH;
                }

                @Override
                public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
                    if (tooltip == null) return;
                    boolean hasTitle = (title != null && !title.trim().isEmpty());
                    if (hasTitle) {
                        tooltip.addPara(title, Misc.getHighlightColor(), 0f);
                    }
                    if (body != null && !body.trim().isEmpty()) {
                        tooltip.addPara(body, Misc.getTextColor(), hasTitle ? 4f : 0f);
                    }
                    if (positiveNote != null && !positiveNote.trim().isEmpty()) {
                        tooltip.addPara(positiveNote, Misc.getPositiveHighlightColor(), 4f);
                    }
                    if (negativeNote != null && !negativeNote.trim().isEmpty()) {
                        tooltip.addPara(negativeNote, Misc.getNegativeHighlightColor(), 2f);
                    }
                }
            }, target, TooltipMakerAPI.TooltipLocation.BELOW);
        } catch (Throwable ignored) {
        }
    }

    public static void attachActionTooltip(TooltipMakerAPI host, UIComponentAPI target, String actionId) {
        if (target == null || actionId == null) return;
        String[] spec = resolveActionTooltipSpec(actionId);
        if (spec != null && spec.length >= 2) {
            String pos = spec.length >= 3 ? spec[2] : null;
            String neg = spec.length >= 4 ? spec[3] : null;
            addTooltip(host, target, spec[0], spec[1], pos, neg);
        }
    }

    public static void attachRowLabelTooltip(TooltipMakerAPI host, UIComponentAPI labelComp, String labelText) {
        if (labelComp == null || labelText == null) return;
        String[] spec = resolveRowLabelTooltipSpec(labelText);
        if (spec != null && spec.length >= 2) {
            addTooltip(host, labelComp, spec[0], spec[1]);
        }
    }

    private static String[] inheritTooltipSpec(String settingName) {
        return new String[]{
                "Inherit from Fleet Doctrine (" + settingName + ")",
                "Clears this division's custom " + settingName + " override and inherits the doctrinal default for this division's archetype."
        };
    }

    private static String extractTrailingToken(String actionId) {
        int idx = actionId.lastIndexOf(':');
        return idx >= 0 ? actionId.substring(idx + 1) : actionId;
    }

    public static String[] resolveActionTooltipSpec(String actionId) {
        if (actionId == null || actionId.isEmpty()) return null;
        switch (actionId) {
            case "TAB:ALL":
                return new String[]{
                        "All Doctrine Settings",
                        "View and configure all Fleet Doctrine sections (Battle Group Command, Auto-Assign Schemes, Division Modifiers, Hull Limits, and Nomenclature) in one unified list."
                };
            case "TAB:OTC_COMMAND":
                return new String[]{
                        "Battle Group Command",
                        "Configure Officer in Tactical Command (OTC) authority, fleet-wide formations, orchestrated advance, multi-division setpieces, and tactical plays."
                };
            case "TAB:MODIFIERS":
                return new String[]{
                        "Division Modifiers",
                        "Configure doctrinal missions, formations, station spacing, division intervals, standoff ranges, cohesion, setpiece passes, carrier voidstrikes, and offensive ramming per division type."
                };
            case "TAB:AUTO_ASSIGN":
                return new String[]{
                        "Auto-Assign Schemes",
                        "Configure which division capability families (Line, Missile, Strike, Carrier, Phase, Service) and hull size classes are formed when auto-assigning ships."
                };
            case "TAB:HULLS":
                return new String[]{
                        "Hull Limits",
                        "Configure minimum and maximum ship counts per division for each hull category (subject to the hard cap of 10 ships per division)."
                };
            case "TAB:NAMING":
                return new String[]{
                        "Division Nomenclature",
                        "Configure division naming presets, numbering styles, custom prefixes per division archetype, and OTC flagship titles."
                };
            case "APPLY_DOCTRINE_TO_FACTION_FLEETS":
                return new String[]{
                        "Apply Doctrine to Faction Fleets",
                        "Applies your current Fleet Doctrine settings to all AI fleets spawned by your personal player faction's colonies."
                };
            case "APPLY_NAMING_TO_FACTION_FLEETS":
                return new String[]{
                        "Apply Naming to Faction Fleets",
                        "Applies your current Division Nomenclature and numbering style to all AI fleets spawned by your personal player faction's colonies."
                };
            case "RESET_DEFAULTS":
                return new String[]{
                        "Reset Doctrine to Defaults",
                        "Restores all Fleet Doctrine settings, per-division-type modifiers, auto-assign schemes, and hull limits to their default values."
                };
            case "REINIT_SECTOR_DIVISIONS":
                return new String[]{
                        "Reinitialize Sector Divisions",
                        "Rebuilds division lists and doctrinal assignments for all active AI fleets across the sector."
                };
            case "CLOSE":
            case "OTC_WINDOW_CLOSE":
                return new String[]{
                        "Close Window",
                        "Closes this window and saves any pending text field changes."
                };
            case "CONFIRM_YES":
                return new String[]{
                        "Confirm Action",
                        "Proceeds with the selected action."
                };
            case "CONFIRM_NO":
            case "CONFIRM_CANCEL":
                return new String[]{
                        "Cancel",
                        "Cancels and returns to the previous screen without making changes."
                };
            case "CREATE_DIV_OVERVIEW":
            case "CREATE_DIV":
                return new String[]{
                        "Create New Division",
                        "Creates a new empty permanent division in your fleet. Assign up to 10 ships via drag-and-drop or right-click."
                };
            case "AUTO_ASSIGN":
                return new String[]{
                        "Auto-Assign Unassigned Ships",
                        "Automatically assigns all unassigned combat ships in your fleet into existing or new divisions according to your Fleet Doctrine and Auto-Assign Schemes."
                };
            case "RESET_AUTO":
                return new String[]{
                        "Reset & Auto-Form Fleet",
                        "Clears all existing divisions and automatically forms a fresh set of divisions across your entire combat fleet based on your Fleet Doctrine."
                };
            case "CLEAR_ALL_DIVS":
                return new String[]{
                        "Disband All Divisions",
                        "Disbands all permanent divisions in your fleet and returns every assigned ship to the Unassigned Ships pool."
                };
            case "BACK_TO_LIST":
                return new String[]{
                        "Return to Fleet Organization",
                        "Saves any pending name changes and returns to the Fleet Organization overview screen."
                };
            case "APPLY_NAME":
                return new String[]{
                        "Rename Division",
                        "Applies the custom division name typed into the text box."
                };
            case "SAVE_CUSTOM_PREFIXES":
                return new String[]{
                        "Save Custom Prefixes",
                        "Saves any custom division archetype prefixes and custom OTC title entered in the text fields."
                };
            case "APPLY_NAMING_TO_EXISTING":
                return new String[]{
                        "Rename Existing Divisions",
                        "Updates the names of your existing player fleet divisions to match the active Naming Preset, Numbering Style, and custom prefixes."
                };
            case "TOGGLE_DIV_CARRIER_OPTIONS":
                return new String[]{
                        "Carrier & Voidstrike Options",
                        "Expand or collapse carrier air-wing overrides for this division: Voidstrike Focus, Voidstrike Launch, Squadron Depth & Width, and Fighter/Bomber Patrol & Voidstrike Formations."
                };
            case "TOGGLE_DIV_RAMMING_OPTIONS":
                return new String[]{
                        "Offensive Ramming Options",
                        "Expand or collapse offensive ramming overrides for this division: Ramming Mode, Ram Target Scope, and Max Ramming Attack Slots."
                };
            case "EXPAND_ALL_HULL_GROUPS":
                return new String[]{
                        "Expand All Hull Groups",
                        "Expand every hull-class group in Division Defaults so all 16 division types are visible."
                };
            case "COLLAPSE_ALL_HULL_GROUPS":
                return new String[]{
                        "Collapse All Hull Groups",
                        "Collapse all hull-class groups in Division Defaults into a compact overview."
                };
            case "OTC_CHANGE":
                return new String[]{
                        "Change Tactical Commander (OTC)",
                        "Open the Tactical Commander selection window to designate which officer commands your battle group. Pre-battle reassignment is free (0 CP); mid-battle reassignment costs 75% of max Command Points unless your previous OTC flagship was destroyed.",
                        "+ Alpha Core OTC enables Time-Space-Firepower Convergence.",
                        "- Undeployed OTC incurs -75% Max CP and -50% CP regeneration."
                };
            case "OTC_AUTH_PREV":
            case "OTC_AUTH_NEXT":
                return new String[]{
                        "Cycle OTC Command Style",
                        "Cycles how much authority the Officer in Tactical Command (OTC) exercises over individual division commanders in battle (Positive Control, Command by Direction, Command by Negation, Fire Distribution Only)."
                };
            case "FLEET_FORMATION_PREV":
            case "FLEET_FORMATION_NEXT":
                return new String[]{
                        "Cycle Fleet Formation",
                        "Cycles the battle group's Fleet Formation, governing how friendly divisions deploy and station relative to one another across the battlefield."
                };
            case "FLEET_ORCHESTRATED_ADVANCE":
                return new String[]{
                        "Orchestrated Advance",
                        "When On, divisions regulate their advance speed during the opening approach so faster divisions do not outrun slower capitals, keeping the battle group dressed in fleet formation until contact."
                };
            case "TOGGLE_ROW":
                return new String[]{
                        "Select / Deselect Division Row",
                        "Selects all deployable reserve ships in this division row for combat deployment, or deselects them if all are already selected."
                };
            default:
                break;
        }

        if (actionId.startsWith("TOGGLE_HULL_GROUP:")) {
            return new String[]{
                    "Hull-Class Division Defaults",
                    "Click to expand or collapse doctrinal division defaults for all division types in this hull class."
            };
        }
        if (actionId.startsWith("TOGGLE_DT_CARD:")) {
            return new String[]{
                    "Division Type Settings",
                    "Click to expand or collapse doctrinal spacing, formation, mission, and tactical modifiers for this division type."
            };
        }

        if (actionId.startsWith("TOGGLE_DD:")) {
            String key = actionId.substring("TOGGLE_DD:".length());
            if ("OTC_AUTHORITY".equals(key)) {
                return new String[]{
                        "OTC Command Style",
                        "Selects how much authority the Officer in Tactical Command (OTC) exercises over each division's configured Opening and Main Missions during combat."
                };
            }
            if ("FLEET_WIDE_FORMATION_DD".equals(key)) {
                return new String[]{
                        "Fleet-Wide Formation",
                        "Selects the macro-formation in which friendly divisions deploy and station relative to one another across the battle line."
                };
            }
            if ("MULTI_DIV_SETPIECE_DD".equals(key)) {
                return new String[]{
                        "Multi-Division Setpiece",
                        "Selects coordinated multi-division setpiece maneuvers orchestrated by the OTC with synchronized Time-on-Target and complementary formation passes."
                };
            }
            if ("NAMING_PRESET".equals(key)) {
                return new String[]{
                        "Division Naming Preset",
                        "Selects the faction or naval nomenclature preset used to generate division names and OTC flagship titles."
                };
            }
            if ("DIV_ARCH_DD".equals(key)) {
                return new String[]{
                        "Division Type (Archetype)",
                        "Selects this division's tactical archetype, which determines the doctrinal defaults it inherits from Fleet Doctrine and whether Carrier-specific missions are available."
                };
            }
            if (key.contains("OPEN_MIS")) {
                return new String[]{
                        "Opening Mission",
                        "Selects the tactical mission executed during the opening phase of battle (first 30-60 seconds) while fleets close into contact."
                };
            }
            if (key.contains("MAIN_MIS")) {
                return new String[]{
                        "Main Mission",
                        "Selects the primary tactical mission executed after the opening phase for the remainder of the battle."
                };
            }
        }

        if (actionId.startsWith("SET_OTC_AUTH:")) {
            OtcAuthority auth = OtcAuthority.fromId(extractTrailingToken(actionId), null);
            if (auth != null) {
                return new String[]{"OTC Command Style: " + auth.dropdownLabel, auth.summary, auth.positiveText, auth.negativeText};
            }
        }
        if (actionId.startsWith("SET_FLEET_FORMATION:")) {
            FleetWideFormation fw = FleetWideFormation.fromId(extractTrailingToken(actionId), null);
            if (fw != null) {
                return new String[]{"Fleet Formation: " + fw.displayName, fw.summary};
            }
        }
        if (actionId.startsWith("SET_ORCHESTRATED_ADVANCE:") || actionId.startsWith("SET_DEPLOY_IN_FORMATION:")) {
            return new String[]{
                    "Orchestrated Advance",
                    "When enabled, divisions regulate their advance speed during the opening approach so the battle group stays dressed in fleet formation rather than faster divisions outrunning slower capitals."
            };
        }
        if (actionId.startsWith("SET_MULTI_SETPIECE:")) {
            MultiDivisionSetpiece msp = MultiDivisionSetpiece.fromId(extractTrailingToken(actionId), null);
            if (msp != null) {
                return new String[]{"Multi-Div Setpiece: " + msp.displayName, msp.summary};
            }
        }
        if (actionId.startsWith("SET_OTC_PLAY:")) {
            String rest = actionId.substring("SET_OTC_PLAY:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                try {
                    OtcPlay play = OtcPlay.valueOf(rest.substring(0, sep));
                    boolean on = "ON".equals(rest.substring(sep + 1));
                    return new String[]{
                            (on ? "Enable Play: " : "Disable Play: ") + play.displayName,
                            play.summary + " Controls whether the Officer in Tactical Command is permitted to call this play during battle."
                    };
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (actionId.startsWith("SET_AA_CAP:")) {
            String rest = actionId.substring("SET_AA_CAP:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                try {
                    AutoAssignCapability cap = AutoAssignCapability.valueOf(rest.substring(0, sep));
                    return new String[]{
                            "Master Capability Toggle: " + cap.displayName,
                            cap.summary + " Toggling this master switch enables or disables automatic division formation across all hull sizes for this capability family."
                    };
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (actionId.startsWith("SET_AA_HULL:")) {
            String rest = actionId.substring("SET_AA_HULL:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                try {
                    AutoAssignHull hull = AutoAssignHull.valueOf(rest.substring(0, sep));
                    return new String[]{
                            "Master Hull Toggle: " + hull.displayName,
                            "Enables or disables automatic division formation for " + hull.displayName + "-class combat hulls across all capability families."
                    };
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (actionId.startsWith("SET_AA_SCHEME:")) {
            String[] parts = actionId.substring("SET_AA_SCHEME:".length()).split(":");
            if (parts.length >= 2) {
                try {
                    AutoAssignCapability cap = AutoAssignCapability.valueOf(parts[0]);
                    AutoAssignHull hull = AutoAssignHull.valueOf(parts[1]);
                    return new String[]{
                            "Auto-Assign Scheme: " + cap.shortName + " " + hull.displayName,
                            "Controls whether the auto-assigner forms dedicated " + cap.shortName + " " + hull.displayName
                                    + " divisions (" + cap.summary + "). When disabled, matching hulls are routed to other enabled schemes or general Force divisions."
                    };
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (actionId.startsWith("SET_OPEN_DUR:")) {
            String sec = extractTrailingToken(actionId);
            return new String[]{
                    "Opening Mission Duration: " + sec + "s",
                    "Divisions execute their Opening Mission for the first " + sec + " seconds of battle before transitioning to their Main Mission."
            };
        }
        if (actionId.startsWith("SET_STATION_SPACING:") || actionId.startsWith("DIV_STATION_SPACING:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Station Spacing");
            return new String[]{
                    "Station Spacing: " + tok + "u",
                    "Center-to-center lateral distance in combat units between adjacent ships inside a division's formation. Lower spacing concentrates fire; higher spacing gives ships more room to maneuver. Reference: a Paragon-class battleship is 364u long."
            };
        }
        if (actionId.startsWith("SET_DIVISION_INTERVAL:") || actionId.startsWith("DIV_DIVISION_INTERVAL:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Division Interval");
            return new String[]{
                    "Division Interval: " + tok + "u",
                    "Minimum lateral separation in combat units maintained between this division's formation footprint and neighboring friendly divisions in the battle line. Reference: a Paragon-class battleship is 364u long."
            };
        }
        if (actionId.startsWith("SET_STANDOFF_RANGE:") || actionId.startsWith("DIV_STANDOFF_RANGE:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Standoff Range");
            if ("AUTO".equals(tok)) {
                return new String[]{
                        "Auto-Calculate Gun Standoff Range",
                        "Analyzes the non-missile, non-point-defense gun ranges of all ships currently assigned to this division and sets Standoff Range to their optimal effective firing distance."
                };
            }
            return new String[]{
                    "Standoff Range: " + tok + "u",
                    "Preferred engagement distance in combat units maintained from enemy targets when holding station in the battle line or executing Standoff Engagement. Reference: a Paragon-class battleship is 364u long."
            };
        }
        if (actionId.startsWith("SET_LINE_STATION:") || actionId.startsWith("DIV_LINE_STATION:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Line Station");
            LineStation ls = LineStation.fromId(tok, null);
            if (ls != null) {
                return new String[]{
                        "Line Station: " + ls.displayName,
                        ls.summary + " Controls whether this division stations in the Van (front rank), Center (main battle line), or Rear (behind the line)."
                };
            }
        }
        if (actionId.startsWith("SET_FORMATION:") || actionId.startsWith("DIV_FORMATION:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Division Formation");
            DivisionFormation fm = DivisionFormation.fromId(tok, null);
            if (fm != null) {
                return new String[]{"Division Formation: " + fm.displayName, fm.summary};
            }
        }
        if (actionId.startsWith("SET_STICKINESS:") || actionId.startsWith("DIV_STICKINESS:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Cohesion");
            DivisionMovementStickiness ms = DivisionMovementStickiness.fromId(tok, null);
            if (ms != null) {
                return new String[]{
                        "Cohesion: " + ms.displayName,
                        ms.summary + String.format(Locale.ROOT, " (Station-keeping leash multiplier: %.2fx).", ms.stationKeepingMult)
                };
            }
        }
        if (actionId.startsWith("SET_DIV_SETPIECE:") || actionId.startsWith("DIV_SETPIECE:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Setpiece Attack");
            DivisionSetpieceAttack sp = DivisionSetpieceAttack.fromId(tok, null);
            if (sp != null) {
                return new String[]{
                        "Setpiece Attack: " + sp.displayName,
                        sp.summary + " Executes in three stages: (1) Dress at Initial Point, (2) Translate in locked formation carriage along the strike vector, (3) Reform."
                };
            }
        }
        if (actionId.startsWith("SET_SKILL_COORD:") || actionId.startsWith("DIV_SKILL_COORD:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Ship Skill Coordination");
            ShipSkillCoordinationMode mode = ShipSkillCoordinationMode.fromId(tok, null);
            if (mode != null) {
                return new String[]{"Ship Skills: " + mode.displayName, mode.summary};
            }
        }
        if (actionId.startsWith("TOGGLE_CARRIER_OPTS:")) {
            return new String[]{
                    "Carrier & Voidstrike Options",
                    "Expand or collapse doctrinal carrier air-wing settings for this division type: Voidstrike Focus, Voidstrike Launch, Squadron Depth & Width, and Fighter/Bomber Patrol & Voidstrike Formations."
            };
        }
        if (actionId.startsWith("SET_VS_BIAS:") || actionId.startsWith("DIV_VS_BIAS:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Target Bias");
            PermanentDivisionManager.VoidstrikeTargetingBias bias =
                    PermanentDivisionManager.VoidstrikeTargetingBias.fromId(tok, null);
            if (bias != null) {
                return new String[]{"Voidstrike Target Bias: " + bias.displayName, bias.summary};
            }
        }
        if (actionId.startsWith("SET_VS_CV_FOCUS:") || actionId.startsWith("DIV_VS_CV_FOCUS:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Focus on Carriers");
            return new String[]{
                    "Focus on Carriers",
                    "When checked, Voidstrikes prioritize enemy Carrier divisions and carrier hulls (ships with fighter bays) within the active Target Bias tier."
            };
        }
        if (actionId.startsWith("SET_VS_OBS:") || actionId.startsWith("DIV_VS_OBS:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Voidstrike Focus");
            VoidstrikeTargetObservance obs = VoidstrikeTargetObservance.fromId(tok, null);
            if (obs != null) {
                return new String[]{"Voidstrike Focus: " + obs.displayName, obs.summary};
            }
        }
        if (actionId.startsWith("SET_VS_COORD:") || actionId.startsWith("DIV_VS_COORD:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Voidstrike Launch");
            VoidstrikeCoordinationMode coord = VoidstrikeCoordinationMode.fromId(tok, null);
            if (coord != null) {
                return new String[]{"Voidstrike Launch: " + coord.displayName, coord.summary};
            }
        }
        if (actionId.startsWith("SET_VS_GEOM:") || actionId.startsWith("DIV_VS_GEOM:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Strike Geometry");
            PermanentDivisionManager.VoidstrikeAttackGeometry geom =
                    PermanentDivisionManager.VoidstrikeAttackGeometry.fromId(tok, null);
            if (geom != null) {
                return new String[]{"Strike Geometry: " + geom.displayName, geom.summary};
            }
        }
        if (actionId.startsWith("SET_VS_GUN:") || actionId.startsWith("DIV_VS_GUN:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Strike Gunnery");
            PermanentDivisionManager.VoidstrikeGunneryMode gun =
                    PermanentDivisionManager.VoidstrikeGunneryMode.fromId(tok, null);
            if (gun != null) {
                return new String[]{"Strike Gunnery: " + gun.displayName, gun.summary};
            }
        }
        if (actionId.startsWith("SET_VS_AOA:") || actionId.startsWith("DIV_VS_AOA:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("AoA Correction");
            PermanentDivisionManager.VoidstrikeAoaCorrection aoa =
                    PermanentDivisionManager.VoidstrikeAoaCorrection.fromId(tok, null);
            if (aoa != null) {
                return new String[]{"AoA Correction: " + aoa.displayName, aoa.summary};
            }
        }
        if (actionId.startsWith("SET_VS_DEPTH:") || actionId.startsWith("DIV_VS_DEPTH:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Voidstrike Depth");
            return new String[]{
                    "Voidstrike Squadron Depth: " + tok,
                    "Number of consecutive fighter/bomber wave ranks stacked front-to-back in a coordinated Voidstrike package (1-10). Higher depth forms a deeper strike column."
            };
        }
        if (actionId.startsWith("SET_VS_WIDTH:") || actionId.startsWith("DIV_VS_WIDTH:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Voidstrike Width");
            return new String[]{
                    "Voidstrike Squadron Width: " + tok,
                    "Number of fighter/bomber squadrons arrayed abreast per rank in a coordinated Voidstrike package (1-10). Higher width spreads wings across a wider attack front."
            };
        }
        if (actionId.startsWith("SET_FTR_FM:") || actionId.startsWith("DIV_FTR_FM:")
                || actionId.startsWith("SET_FTR_VS_FM:") || actionId.startsWith("DIV_FTR_VS_FM:")
                || actionId.startsWith("SET_BMB_FM:") || actionId.startsWith("DIV_BMB_FM:")
                || actionId.startsWith("SET_BMB_VS_FM:") || actionId.startsWith("DIV_BMB_VS_FM:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Squadron Formation");
            SquadronFormation sf = SquadronFormation.fromId(tok, null);
            if (sf != null) {
                boolean isBomber = actionId.contains("BMB");
                boolean isVs = actionId.contains("_VS_");
                String wingType = isBomber ? "Bomber" : "Fighter";
                String phaseType = isVs ? "Voidstrike" : "Patrol";
                String desc = isVs
                        ? (wingType + " squadrons form into " + sf.displayName + " when marshaling and executing a Voidstrike attack run.")
                        : (wingType + " squadrons fly in " + sf.displayName + " during general patrol, escort, and Combat Space Patrol.");
                if (sf.iconFormation != null && sf.iconFormation.summary != null) {
                    desc += " " + sf.iconFormation.summary;
                }
                return new String[]{wingType + " " + phaseType + " Formation: " + sf.displayName, desc};
            }
        }
        if (actionId.startsWith("TOGGLE_RAMMING_OPTS:")) {
            return new String[]{
                    "Offensive Ramming Options",
                    "Expand or collapse doctrinal offensive ramming settings for this division type: Ramming Mode, Ram Target Scope, and Max Ramming Attack Slots."
            };
        }
        if (actionId.startsWith("SET_RAM_MODE:") || actionId.startsWith("DIV_RAM_MODE:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Ramming Mode");
            DivisionRammingMode mode = DivisionRammingMode.fromId(tok, null);
            if (mode != null) {
                return new String[]{"Ramming Mode: " + mode.displayName, mode.summary};
            }
        }
        if (actionId.startsWith("SET_RAM_SCOPE:") || actionId.startsWith("DIV_RAM_SCOPE:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Ram Target Scope");
            DivisionRammingTargetScope scope = DivisionRammingTargetScope.fromId(tok, null);
            if (scope != null) {
                return new String[]{"Ram Target Scope: " + scope.displayName, scope.summary};
            }
        }
        if (actionId.startsWith("SET_RAM_SLOTS:") || actionId.startsWith("DIV_RAM_SLOTS:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Max Ram Slots");
            return new String[]{
                    "Max Ramming Attack Slots: " + tok,
                    "Maximum number of ships in this division permitted to execute offensive ramming passes simultaneously (1-10) against the selected target scope."
            };
        }
        if (actionId.startsWith("SET_OPEN_MIS:") || actionId.startsWith("DIV_OPEN_MIS:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Opening Mission");
            DivisionMissionType mis = DivisionMissionType.fromId(tok, null);
            if (mis != null) {
                return new String[]{
                        "Opening Mission: " + mis.dropdownLabel,
                        mis.summary + " Executed during the first 30-60 seconds of battle while fleets close into contact."
                };
            }
        }
        if (actionId.startsWith("SET_MAIN_MIS:") || actionId.startsWith("DIV_MAIN_MIS:")) {
            String tok = extractTrailingToken(actionId);
            if ("INHERIT".equals(tok)) return inheritTooltipSpec("Main Mission");
            DivisionMissionType mis = DivisionMissionType.fromId(tok, null);
            if (mis != null) {
                return new String[]{
                        "Main Mission: " + mis.dropdownLabel,
                        mis.summary + " Executed after the opening phase for the remainder of the battle."
                };
            }
        }
        if (actionId.startsWith("SET_MIN:")) {
            String tok = extractTrailingToken(actionId);
            return new String[]{
                    "Minimum Hulls per Division: " + tok,
                    "Sets the minimum number of ships required before the auto-assigner forms a dedicated division of this hull category (0 allows any size up to Max Ships)."
            };
        }
        if (actionId.startsWith("SET_MAX:")) {
            String tok = extractTrailingToken(actionId);
            return new String[]{
                    "Maximum Hulls per Division: " + tok,
                    "Sets the maximum number of ships the auto-assigner places into a single division of this hull category before splitting into a new division (hard cap: 10 ships)."
            };
        }
        if (actionId.startsWith("SET_NAMING_PRESET:")) {
            PlayerNamingPreset preset = PlayerNamingPreset.fromId(extractTrailingToken(actionId), null);
            if (preset != null) {
                return new String[]{
                        "Naming Preset: " + preset.displayName,
                        "Applies " + preset.displayName + " division prefixes and OTC flagship command titles."
                };
            }
        }
        if (actionId.startsWith("SET_NUMBERING_STYLE:")) {
            NumberingStyle style = NumberingStyle.fromId(extractTrailingToken(actionId), null);
            if (style != null) {
                return new String[]{
                        "Numbering Style: " + style.displayName,
                        "Formats division sequence numbers using " + style.displayName + " notation."
                };
            }
        }
        if (actionId.startsWith("SELECT_DIV:")) {
            return new String[]{
                    "Select Division",
                    "Left-click to select this division as the active target for quick right-click ship assignment. Double-click to open its Deep Division Editor."
            };
        }
        if (actionId.startsWith("OPEN_DIV:")) {
            return new String[]{
                    "Configure Division",
                    "Opens the Deep Division Editor for this division to customize its archetype, missions, formation, spacing, standoff range, cohesion, setpieces, and air-wing/ramming options."
            };
        }
        if (actionId.startsWith("DELETE_DIV:")) {
            return new String[]{
                    "Disband Division",
                    "Disbands this division and returns all of its assigned ships to the Unassigned Ships pool."
            };
        }
        if (actionId.startsWith("NEW_DIV_WITH:")) {
            return new String[]{
                    "Form New Division With Ship",
                    "Creates a new permanent division classified for this ship's hull and loadout, designating this ship as its initial Division Leader."
            };
        }
        if (actionId.startsWith("SET_ARCH:")) {
            try {
                DivisionArchetype arch = DivisionArchetype.valueOf(extractTrailingToken(actionId));
                return new String[]{
                        "Division Type: " + arch.shortPrefix,
                        "Sets this division's tactical archetype to " + arch.shortPrefix
                                + ", determining which doctrinal defaults it inherits from Fleet Doctrine and whether Carrier-specific missions are unlocked."
                };
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (actionId.startsWith("SET_DOC:")) {
            PermanentDivisionManager.DoctrineOption doc =
                    PermanentDivisionManager.DoctrineOption.fromId(extractTrailingToken(actionId));
            if (doc != null) {
                return new String[]{
                        "Division Posture: " + doc.displayName,
                        doc.summary
                };
            }
        }
        if (actionId.startsWith("OTC_SELECT:")) {
            return new String[]{
                    "Designate Officer in Tactical Command (OTC)",
                    "Assigns this officer and ship as your battle group's Officer in Tactical Command. If your chosen OTC ship is not deployed into combat, your fleet incurs -75% Max Command Points and -50% CP regeneration."
            };
        }
        return null;
    }

    public static String[] resolveRowLabelTooltipSpec(String labelText) {
        if (labelText == null || labelText.isEmpty()) return null;
        String lower = labelText.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("battle line structure") || lower.startsWith("line structure")) {
            return new String[]{
                    "Battle Line Structure",
                    "Controls how your main battle line divisions array across the front: Single Continuous Line, Split Flanks, Center & Wings, or Echeloned Line."
            };
        }
        if (lower.startsWith("station spacing") || lower.startsWith("line spacing")) {
            return new String[]{
                    "Station Spacing (Intra-Division)",
                    "Center-to-center lateral distance in combat units between adjacent ships inside a division's formation. Reference: a Paragon-class battleship is 364u long."
            };
        }
        if (lower.startsWith("division interval")) {
            return new String[]{
                    "Division Interval (Inter-Division)",
                    "Minimum lateral separation in combat units maintained between this division's formation footprint and neighboring friendly divisions in the battle line."
            };
        }
        if (lower.startsWith("standoff")) {
            return new String[]{
                    "Standoff Range",
                    "Preferred engagement distance in combat units maintained from enemy targets when holding station in the battle line or executing Standoff Engagement."
            };
        }
        if (lower.startsWith("opening duration")) {
            return new String[]{
                    "Opening Mission Duration",
                    "Duration in seconds (30s to 60s) that divisions execute their Opening Mission at the start of battle before transitioning to their Main Mission."
            };
        }
        if (lower.startsWith("line station")) {
            return new String[]{
                    "Line Station (Van / Center / Rear)",
                    "Preferred depth station in the line of battle: Van leads ahead of the main body, Center holds the primary battle line, and Rear stands off astern or in reserve."
            };
        }
        if (lower.startsWith("fleet formation")) {
            return new String[]{
                    "Fleet-Wide Formation",
                    "Macro-formation governing how friendly divisions deploy at battle start and station relative to one another across the battlefield."
            };
        }
        if (lower.startsWith("squadron formation") || lower.startsWith("formation")) {
            return new String[]{
                    "Division / Squadron Formation",
                    "Naval formation geometry practiced by ships or strike craft squadrons relative to their leader."
            };
        }
        if (lower.startsWith("cohesion") || lower.startsWith("division cohesion")) {
            return new String[]{
                    "Division Cohesion (Movement Stickiness)",
                    "Governs how tightly ships hold their formation slots versus breaking into individual ship combat movement when engaging enemy divisions."
            };
        }
        if (lower.startsWith("reserve depth")) {
            return new String[]{
                    "Reserve & Voidstrike Depth",
                    "Number of consecutive wave ranks or reserve depth tiers maintained behind the front battle line."
            };
        }
        if (lower.startsWith("multi-div setpiece")) {
            return new String[]{
                    "Multi-Division Setpiece",
                    "OTC-coordinated multi-division maneuver synchronizing 2+ divisions with shared Time-on-Target (ToT) and complementary formation passes."
            };
        }
        if (lower.startsWith("setpiece")) {
            return new String[]{
                    "Division Setpiece Attack",
                    "Synchronized formation pass executed in locked carriage: (1) Dress at Initial Point, (2) Execute Pass along the strike vector, (3) Reform."
            };
        }
        if (lower.startsWith("ship skills")) {
            return new String[]{
                    "Ship Skill Coordination",
                    "Controls whether division members hold premature solo mobility bursts and trigger movement/combat ship systems in unison under division control, or fire them independently."
            };
        }
        if (lower.startsWith("ramming options") || lower.startsWith("ramming:")) {
            return new String[]{
                    "Offensive Ramming Mode",
                    "Controls whether ram-capable ships in this division forbid offensive ramming (Off), sideswipe flanks (Glancing Pass), marshal and charge together (Lance Charge), or ram center-mass whenever ready (Direct Ram)."
            };
        }
        if (lower.startsWith("ram target")) {
            return new String[]{
                    "Ramming Target Scope",
                    "Controls whether simultaneous ramming attack slots focus onto a Single Ship priority target or distribute across an Enemy Division."
            };
        }
        if (lower.startsWith("max ram slots")) {
            return new String[]{
                    "Maximum Ramming Attack Slots",
                    "Maximum number of ships in this division allowed to execute offensive ramming passes simultaneously (1-10)."
            };
        }
        if (lower.startsWith("carrier options")) {
            return new String[]{
                    "Carrier & Voidstrike Options",
                    "Configures air-wing Voidstrike target focus, launch coordination, wave depth & width, and fighter/bomber squadron formations."
            };
        }
        if (lower.startsWith("target bias")) {
            return new String[]{
                    "Voidstrike Targeting Bias",
                    "Controls which enemy hull classes carrier Voidstrikes prioritize: Free Selection (any viable target), Cruisers & Capitals, or Capitals First."
            };
        }
        if (lower.startsWith("carrier priority") || lower.startsWith("focus on carriers")) {
            return new String[]{
                    "Focus on Carriers Modifier",
                    "When enabled, Voidstrikes hunt enemy Carrier divisions and carrier hulls first within the selected Target Bias tier."
            };
        }
        if (lower.startsWith("voidstrike focus")) {
            return new String[]{
                    "Voidstrike Target Focus (Observance)",
                    "Controls how strictly strike craft lock onto the designated Voidstrike target versus engaging targets of opportunity along their flight path."
            };
        }
        if (lower.startsWith("voidstrike launch")) {
            return new String[]{
                    "Voidstrike Launch Coordination",
                    "Controls whether carrier wings orbit and marshal into a single synchronized strike package (Coordinated) or launch immediately in successive waves (Uncoordinated)."
            };
        }
        if (lower.startsWith("strike geometry")) {
            return new String[]{
                    "Voidstrike Attack Geometry",
                    "Controls the multi-axis approach geometry of strike squadrons: Direct (frontal), L-Crossfire (90 deg hammer-and-anvil crossfire), or Pincer (+-55 deg converging jaws)."
            };
        }
        if (lower.startsWith("strike gunnery")) {
            return new String[]{
                    "Voidstrike Gunnery Mode",
                    "Controls whether strike craft aim at the target ship's core (Precision) or partition the target's silhouette and evasion envelope into hierarchical Voidstrike -> Squadron -> Craft firing sectors (Area)."
            };
        }
        if (lower.startsWith("aoa correction")) {
            return new String[]{
                    "Terminal Angle-of-Attack Correction",
                    "Controls terminal run-in alignment, ballistic lead, lateral slip damping, and off-boresight fire-gating (<= 6.5 deg under Strong) for unguided torpedo/rocket bombers."
            };
        }
        if (lower.startsWith("voidstrike depth")) {
            return new String[]{
                    "Voidstrike Squadron Depth",
                    "Number of consecutive fighter/bomber wave ranks stacked front-to-back in a coordinated Voidstrike package (1-10)."
            };
        }
        if (lower.startsWith("voidstrike width")) {
            return new String[]{
                    "Voidstrike Squadron Width",
                    "Number of fighter/bomber squadrons arrayed abreast per rank in a coordinated Voidstrike package (1-10)."
            };
        }
        if (lower.startsWith("fighter patrol")) {
            return new String[]{
                    "Fighter Patrol Formation",
                    "Squadron formation flown by fighter wings during general patrol, escort, and Combat Space Patrol."
            };
        }
        if (lower.startsWith("fighter voidstrike")) {
            return new String[]{
                    "Fighter Voidstrike Formation",
                    "Squadron formation flown by fighter wings when marshaling and executing a Voidstrike attack run."
            };
        }
        if (lower.startsWith("bomber patrol")) {
            return new String[]{
                    "Bomber Patrol Formation",
                    "Squadron formation flown by bomber wings while orbiting carriers or holding station outside an active strike."
            };
        }
        if (lower.startsWith("bomber voidstrike")) {
            return new String[]{
                    "Bomber Voidstrike Formation",
                    "Squadron formation flown by bomber wings during a Voidstrike attack run to concentrate torpedo and bomb salvos."
            };
        }
        if (lower.startsWith("opening mission")) {
            return new String[]{
                    "Opening Mission",
                    "Tactical mission executed during the opening phase of battle (first 30-60 seconds) while fleets close into contact."
            };
        }
        if (lower.startsWith("main mission")) {
            return new String[]{
                    "Main Mission",
                    "Primary tactical mission executed after the opening phase for the remainder of the battle."
            };
        }
        if (lower.startsWith("division type")) {
            return new String[]{
                    "Division Type (Archetype)",
                    "Determines which doctrinal defaults this division inherits from Fleet Doctrine and whether Carrier-specific missions are available."
            };
        }
        if (lower.startsWith("numbering style")) {
            return new String[]{
                    "Division Numbering Style",
                    "Controls how division sequence numbers are formatted (Arabic 1st/2nd, Roman I/II, Greek Alpha/Beta, English One/Two, Zero-Padded 01/02, Heavenly Stems, or Numbered Squads)."
            };
        }
        if (lower.startsWith("naming preset")) {
            return new String[]{
                    "Division Naming Preset",
                    "Selects the naval or faction nomenclature preset used for default division names and OTC flagship titles."
            };
        }
        if (lower.startsWith("custom otc title")) {
            return new String[]{
                    "Custom OTC Title",
                    "Optional custom command title displayed for your Officer in Tactical Command (leave blank to use the active Naming Preset's default title)."
            };
        }
        if (lower.startsWith("posture")) {
            return new String[]{
                    "Division Tactical Posture",
                    "Modifies how aggressively or cautiously this division's commander interprets its assigned mission (Adaptive, Spearhead, Fire Support, Escort & Screen, Mobile Flanker, or Reserve)."
            };
        }
        if (lower.startsWith("callsign")) {
            return new String[]{
                    "Division Callsign",
                    "Custom callsign or designation for this division displayed in the Fleet Organization screen, pre-battle Deployment Window, and combat HUD."
            };
        }
        if (lower.startsWith("min ships")) {
            return new String[]{
                    "Minimum Hulls per Division",
                    "Minimum number of hulls required before the auto-assigner forms a dedicated division of this hull category."
            };
        }
        if (lower.startsWith("max ships")) {
            return new String[]{
                    "Maximum Hulls per Division",
                    "Maximum number of hulls the auto-assigner places into a single division of this hull category before splitting into a new division (hard cap: 10)."
            };
        }
        return null;
    }
}
