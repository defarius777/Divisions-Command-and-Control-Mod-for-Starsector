package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import divisionai.PermanentDivisionManager.DoctrineHullCategory;
import divisionai.PermanentDivisionManager.FleetDoctrineConfig;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Campaign-layer Fleet Doctrine modal screen accessible from the "Fleet Doctrine" button on the
 * left sidebar of the Fleet screen.
 *
 * Configures the 14 hull-count rules used when auto-assigning and forming divisions:
 *  - Minimum cruiser hulls for division   / Maximum cruiser hulls for division
 *  - Minimum capital hulls for division   / Maximum capital hulls for division
 *  - Minimum destroyer hulls for division / Maximum destroyer hulls for division
 *  - Minimum frigate hulls for division   / Maximum frigate hulls for division
 *  - Minimum freighter hulls for division / Maximum freighter hulls for division
 *  - Minimum tanker hulls for division    / Maximum tanker hulls for division
 *  - Minimum transport hulls for division / Maximum transport hulls for division
 *  - Enforces the hard cap: Maximum division size no matter what is 10.
 */
public class FleetDoctrineDialog extends BaseCustomUIPanelPlugin {

    private static final float DIALOG_WIDTH = 980f;
    private static final float DIALOG_HEIGHT = 640f;

    private final UIPanelAPI parentCoreUI;
    private CustomPanelAPI rootOverlayPanel;
    private CustomPanelAPI contentContainer;
    private boolean closed = false;

    private final Map<ButtonAPI, String> activeButtons = new LinkedHashMap<>();
    private String pendingAction = null;

    private final CustomUIPanelPlugin subPanelPlugin = new BaseCustomUIPanelPlugin() {
        @Override
        public void buttonPressed(Object buttonId) {
            FleetDoctrineDialog.this.buttonPressed(buttonId);
        }
    };

    public FleetDoctrineDialog(UIPanelAPI parentCoreUI) {
        this.parentCoreUI = parentCoreUI;
    }

    public boolean isClosed() {
        return closed;
    }

    public void show() {
        if (parentCoreUI == null) return;
        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();

        rootOverlayPanel = Global.getSettings().createCustom(screenW, screenH, this);
        parentCoreUI.addComponent(rootOverlayPanel).inTL(0f, 0f);
        parentCoreUI.bringComponentToTop(rootOverlayPanel);
        rebuildContent();
    }

    public void close() {
        if (closed) return;
        closed = true;
        activeButtons.clear();
        if (parentCoreUI != null && rootOverlayPanel != null) {
            parentCoreUI.removeComponent(rootOverlayPanel);
        }
    }

    private ButtonAPI registerButton(ButtonAPI button, String actionId) {
        if (button != null && actionId != null) {
            activeButtons.put(button, actionId);
        }
        return button;
    }

    private void rebuildContent() {
        if (rootOverlayPanel == null) return;
        activeButtons.clear();

        if (contentContainer != null) {
            rootOverlayPanel.removeComponent(contentContainer);
        }

        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        float x = Math.round((screenW - DIALOG_WIDTH) * 0.5f);
        float y = Math.round((screenH - DIALOG_HEIGHT) * 0.5f);

        contentContainer = Global.getSettings().createCustom(DIALOG_WIDTH, DIALOG_HEIGHT, subPanelPlugin);
        rootOverlayPanel.addComponent(contentContainer).inTL(x, y);

        FleetDoctrineConfig doctrine = PermanentDivisionManager.getFleetDoctrine();

        Color baseColor = Misc.getBasePlayerColor();
        Color darkColor = Misc.getDarkPlayerColor();
        Color highlight = Misc.getHighlightColor();
        Color positive = Misc.getPositiveHighlightColor();
        Color gray = Misc.getGrayColor();

        // Top-right [X] button
        TooltipMakerAPI topCloseUi = contentContainer.createUIElement(36f, 24f, false);
        ButtonAPI bTopX = registerButton(
                topCloseUi.addButton(
                        "X",
                        "CLOSE",
                        Misc.getNegativeHighlightColor(),
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        32f,
                        22f,
                        0f),
                "CLOSE");
        bTopX.getPosition().inTL(2f, 1f);
        topCloseUi.setHeightSoFar(24f);
        contentContainer.addUIElement(topCloseUi).inTL(DIALOG_WIDTH - 46f, 8f);

        // 1. Header & Top Toolbar
        TooltipMakerAPI header = contentContainer.createUIElement(DIALOG_WIDTH - 64f, 50f, false);
        header.setTitleOrbitronLarge();
        header.addTitle("FLEET DOCTRINE — DIVISION HULL COMPOSITION LIMITS", baseColor);
        header.addPara(
                "Configure minimum and maximum hulls per division for each hull category. "
                        + "Hard Cap: Maximum division size no matter what is %s ships.",
                4f,
                highlight,
                String.valueOf(PermanentDivisionManager.MAX_DIVISION_SIZE));
        header.setHeightSoFar(50f);
        contentContainer.addUIElement(header).inTL(12f, 8f);

        CustomPanelAPI toolbarRow = Global.getSettings().createCustom(DIALOG_WIDTH - 24f, 28f, subPanelPlugin);
        TooltipMakerAPI tb = toolbarRow.createUIElement(DIALOG_WIDTH - 24f, 28f, false);

        ButtonAPI bReset = registerButton(
                tb.addButton(
                        "Reset Defaults",
                        "RESET_DEFAULTS",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        180f,
                        24f,
                        0f),
                "RESET_DEFAULTS");
        bReset.getPosition().inTL(0f, 2f);

        ButtonAPI bClose = registerButton(
                tb.addButton(
                        "Close [Esc]",
                        "CLOSE",
                        Misc.getNegativeHighlightColor(),
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        140f,
                        24f,
                        0f),
                "CLOSE");
        bClose.getPosition().inTL(DIALOG_WIDTH - 24f - 140f, 2f);

        tb.setHeightSoFar(28f);
        toolbarRow.addUIElement(tb).inTL(0f, 0f);
        contentContainer.addComponent(toolbarRow).inTL(12f, 60f);

        // 2. Scrollable Body with the 7 Hull Categories (Minimum & Maximum rows for each)
        float bodyTop = 94f;
        float bodyHeight = DIALOG_HEIGHT - bodyTop - 14f;
        float bodyWidth = DIALOG_WIDTH - 24f;

        TooltipMakerAPI body = contentContainer.createUIElement(bodyWidth, bodyHeight, true);

        for (DoctrineHullCategory cat : DoctrineHullCategory.values()) {
            int currMin = doctrine.getMin(cat);
            int currMax = doctrine.getMax(cat);

            String secTitle = String.format(
                    "%s  —  Min: %d  |  Max: %d  (Hard Cap: %d)",
                    cat.displayName.toUpperCase(),
                    currMin,
                    currMax,
                    PermanentDivisionManager.MAX_DIVISION_SIZE);
            body.addSectionHeading(secTitle, baseColor, darkColor, Alignment.LMID, 6f);

            // Row A: Minimum <cat> hulls for division
            addLimitRow(
                    body,
                    bodyWidth - 16f,
                    cat.minLabel,
                    currMin,
                    "SET_MIN:" + cat.name() + ":",
                    baseColor,
                    darkColor,
                    positive,
                    gray);

            // Row B: Maximum <cat> hulls for division
            addLimitRow(
                    body,
                    bodyWidth - 16f,
                    cat.maxLabel,
                    currMax,
                    "SET_MAX:" + cat.name() + ":",
                    baseColor,
                    darkColor,
                    highlight,
                    gray);
        }

        contentContainer.addUIElement(body).inTL(12f, bodyTop);
    }

    private void addLimitRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            int currentValue,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 26f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 26f, false);

        rowUi.addPara(
                "%s:  %s",
                4f,
                activeValueColor,
                labelText,
                String.valueOf(currentValue)).getPosition().inTL(4f, 4f);

        float xCursor = 340f;

        // [-] button
        String decAct = actionPrefix + Math.max(0, currentValue - 1);
        ButtonAPI bDec = registerButton(
                rowUi.addButton(
                        "-",
                        decAct,
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        28f,
                        21f,
                        0f),
                decAct);
        bDec.setEnabled(currentValue > 0);
        bDec.getPosition().inTL(xCursor, 2f);
        xCursor += 34f;

        // Direct 0..10 selector buttons
        for (int v = 0; v <= PermanentDivisionManager.MAX_DIVISION_SIZE; v++) {
            boolean selected = (v == currentValue);
            String valAct = actionPrefix + v;
            ButtonAPI bVal = registerButton(
                    rowUi.addButton(
                            String.valueOf(v),
                            valAct,
                            selected ? activeValueColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            38f,
                            21f,
                            0f),
                    valAct);
            if (selected) {
                bVal.highlight();
            }
            bVal.getPosition().inTL(xCursor, 2f);
            xCursor += 42f;
        }

        // [+] button
        String incAct = actionPrefix + Math.min(PermanentDivisionManager.MAX_DIVISION_SIZE, currentValue + 1);
        ButtonAPI bInc = registerButton(
                rowUi.addButton(
                        "+",
                        incAct,
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        28f,
                        21f,
                        0f),
                incAct);
        bInc.setEnabled(currentValue < PermanentDivisionManager.MAX_DIVISION_SIZE);
        bInc.getPosition().inTL(xCursor + 4f, 2f);

        rowUi.setHeightSoFar(26f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);
    }

    @Override
    public void buttonPressed(Object buttonId) {
        if (closed || !(buttonId instanceof String)) return;
        pendingAction = (String) buttonId;
    }

    @Override
    public void advance(float amount) {
        if (closed) return;

        for (Map.Entry<ButtonAPI, String> entry : activeButtons.entrySet()) {
            ButtonAPI btn = entry.getKey();
            if (btn != null && btn.isChecked()) {
                btn.setChecked(false);
                pendingAction = entry.getValue();
                break;
            }
        }

        if (pendingAction != null) {
            String cmd = pendingAction;
            pendingAction = null;
            handleAction(cmd);
        }
    }

    private void handleAction(String cmd) {
        if ("CLOSE".equals(cmd)) {
            close();
            return;
        }
        if ("RESET_DEFAULTS".equals(cmd)) {
            PermanentDivisionManager.resetFleetDoctrineToDefaults();
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_MIN:")) {
            String rest = cmd.substring("SET_MIN:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                String catStr = rest.substring(0, sep);
                int val = Integer.parseInt(rest.substring(sep + 1));
                DoctrineHullCategory cat = DoctrineHullCategory.valueOf(catStr);
                PermanentDivisionManager.updateFleetDoctrineLimit(cat, true, val);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_MAX:")) {
            String rest = cmd.substring("SET_MAX:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                String catStr = rest.substring(0, sep);
                int val = Integer.parseInt(rest.substring(sep + 1));
                DoctrineHullCategory cat = DoctrineHullCategory.valueOf(catStr);
                PermanentDivisionManager.updateFleetDoctrineLimit(cat, false, val);
                rebuildContent();
            }
        }
    }

    @Override
    public void processInput(List<InputEventAPI> events) {
        if (closed || events == null) return;
        for (InputEventAPI event : events) {
            if (event.isConsumed()) continue;
            if (event.isKeyDownEvent() && event.getEventValue() == Keyboard.KEY_ESCAPE) {
                event.consume();
                close();
                return;
            }
            if (!event.isMouseUpEvent()) {
                event.consume();
            }
        }
    }

    @Override
    public void renderBelow(float alphaMult) {
        if (closed || contentContainer == null) return;

        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        PositionAPI pos = contentContainer.getPosition();
        float x = pos.getX();
        float y = pos.getY();
        float w = pos.getWidth();
        float h = pos.getHeight();

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // Backdrop dim
        GL11.glColor4f(0.02f, 0.03f, 0.05f, 0.78f * alphaMult);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0f, 0f);
        GL11.glVertex2f(screenW, 0f);
        GL11.glVertex2f(screenW, screenH);
        GL11.glVertex2f(0f, screenH);
        GL11.glEnd();

        // Modal panel background
        GL11.glColor4f(0.05f, 0.08f, 0.12f, 0.96f * alphaMult);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();

        // Modal panel border
        Color border = Misc.getBasePlayerColor();
        GL11.glLineWidth(2.0f);
        GL11.glColor4f(
                border.getRed() / 255f,
                border.getGreen() / 255f,
                border.getBlue() / 255f,
                0.88f * alphaMult);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();

        GL11.glPopAttrib();
    }
}
