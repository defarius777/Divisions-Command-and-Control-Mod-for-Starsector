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
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.SquadronFormation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

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
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
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

        GL11.glPopAttrib();
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

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, rowHeight, panelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, rowHeight, false);
        rowUi.addPara(labelText, 0f, labelColor, labelHighlightSpan != null ? labelHighlightSpan : labelText)
                .getPosition()
                .inTL(labelX, labelY);

        float xCursor = controlColX;
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
                            btnWidth,
                            btnHeight,
                            0f),
                    act);
            if (selected) {
                btn.highlight();
            }
            btn.getPosition().inTL(xCursor, 3f);
            xCursor += btnStride;
        }

        if (inheritActionId != null) {
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
            bInh.getPosition().inTL(xCursor, 3f);
        }

        if (summaryText != null && !summaryText.isEmpty()) {
            rowUi.addPara(summaryText, 0f, summaryColor).getPosition().inTL(xCursor + 8f, summaryY);
        }

        rowUi.setHeightSoFar(rowHeight);
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
        final float rowH = hasInheritButton ? 50f : 28f;
        final float btnH = hasInheritButton ? 20f : 22f;
        final float iconXOffset = hasInheritButton ? 11f : 12f;
        final float iconSize = hasInheritButton ? 14f : 15f;
        final ButtonAPI[] formButtons = new ButtonAPI[options.length];

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, rowH, new BaseCustomUIPanelPlugin() {
            @Override
            public void buttonPressed(Object buttonId) {
                if (pressRelay != null) {
                    pressRelay.onButtonPressed(buttonId);
                }
            }

            @Override
            public void render(float alphaMult) {
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

        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, rowH, false);
        Color lblColor = hasInheritButton ? (isCustom ? highlight : baseColor) : activeColor;
        rowUi.addPara(labelText, 0f, lblColor, labelText).getPosition().inTL(hasInheritButton ? 4f : 6f, hasInheritButton ? 4f : 5f);

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

        for (int i = 0; i < options.length; i++) {
            SquadronFormation sf = options[i];
            boolean selected = (sf == currentFormation);
            float bx = hasInheritButton ? (4f + i * (btnW + btnGap)) : (controlColX + i * (btnW + btnGap));
            float by = hasInheritButton ? 25f : 3f;
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

        rowUi.setHeightSoFar(rowH);
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

        rowUi.addPara(labelText, 0f, baseColor).getPosition().inTL(hasInheritOption ? 4f : 6f, hasInheritOption ? 4f : 5f);

        String toggleAct = "TOGGLE_DD:" + dropdownKey;
        String btnText = (expanded ? "[^] " : "[v] ") + currentMission.dropdownLabel;
        Color toggleColor = hasInheritOption ? (isCustom ? activeColor : baseColor) : activeColor;
        ButtonAPI bToggle = registrar.register(
                rowUi.addButton(btnText, toggleAct, toggleColor, darkColor, Alignment.LMID, CutStyle.TL_BR, toggleBtnW, toggleBtnH, 0f),
                toggleAct);
        if (expanded) bToggle.highlight();
        bToggle.getPosition().inTL(controlColX, hasInheritOption ? 2f : 3f);

        String topHint = showLineStationHint
                ? (currentMission.autoLineStation != null ? currentMission.autoLineStation.displayName : "")
                : currentMission.summary;
        if (topHint != null && !topHint.isEmpty()) {
            rowUi.addPara(topHint, 0f, gray).getPosition().inTL(hintColX, hasInheritOption ? 4f : 5f);
        }

        rowUi.setHeightSoFar(mainRowH);
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
                bOpt.getPosition().inTL(controlColX + 12f, hasInheritOption ? 2f : 3f);
                optUi.addPara(hintText, 0f, selected ? activeColor : gray).getPosition().inTL(hintColX, 4f);
                optUi.setHeightSoFar(optRowH);
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
        rowUi.addPara(labelText, 0f, labelColor, labelText).getPosition().inTL(labelX, labelY);

        float xCursor = hasInheritButton ? (controlColX - 24f) : controlColX;
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

        rowUi.setHeightSoFar(rowHeight);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        parent.addCustom(rowPanel, 2f);
    }
}
