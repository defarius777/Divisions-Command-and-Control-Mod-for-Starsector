package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.ScrollPanelAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.PermanentDivisionManager.DivisionFormation;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.StrikeCraftMissionType;
import divisionai.PermanentDivisionManager.DivisionMovementStickiness;
import divisionai.PermanentDivisionManager.DivisionSetpieceAttack;
import divisionai.PermanentDivisionManager.DoctrineHullCategory;
import divisionai.PermanentDivisionManager.DoctrineOption;
import divisionai.PermanentDivisionManager.FleetDoctrineConfig;
import divisionai.PermanentDivisionManager.LineStation;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import divisionai.PermanentDivisionManager.SquadronFormation;
import divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode;
import divisionai.PermanentDivisionManager.VoidstrikeTargetObservance;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full-screen / modal Division Editor UI opened from the "Division Editor" button on the left side
 * of the Campaign Fleet screen (CoreUITabId.FLEET):
 *  - Screen 1 (Overview): Displays all permanent divisions in the player's fleet alongside the
 *    Unassigned Fleet Ships pool with interactive visual Ship Icon Tiles:
 *      * Left-click a division card to select it as the active quick-assign target (or double-click
 *        its header / click "Deep Editor ->" to open its dedicated single-division screen).
 *      * Drag-and-drop ship icons between any Division Card and the Unassigned Fleet Pool (or between divisions).
 *      * Right-click any unassigned ship icon to assign it to the selected division; right-click any ship
 *        icon inside a division to unassign it back to the fleet pool.
 *      * Left-click a ship icon inside a division to designate it as that division's Flagship.
 *  - Screen 2 (Deep Division Editor): Dedicated single-division command & doctrine screen with visual
 *    Ship Icon Tiles for both the division bay and the unassigned/transfer fleet pool, supporting the
 *    exact same drag-and-drop, right-click assign/unassign, and left-click flagship designation.
 */
public class DivisionEditorDialog extends BaseCustomUIPanelPlugin {

    public static final float DIALOG_WIDTH = 1320f;
    public static final float DIALOG_HEIGHT = 800f;
    static final float CONTROL_COL_X = 250f;
    private static final float DRAG_START_THRESHOLD_PX = 6f;

    private static class ShipIconTile {
        final FleetMemberAPI member;
        final String sourceDivisionId; // null if in Unassigned Fleet Pool
        final boolean isFlagship;
        final CustomPanelAPI tilePanel;
        final UIPanelAPI clipContainer;

        ShipIconTile(
                FleetMemberAPI member,
                String sourceDivisionId,
                boolean isFlagship,
                CustomPanelAPI tilePanel,
                UIPanelAPI clipContainer) {
            this.member = member;
            this.sourceDivisionId = sourceDivisionId;
            this.isFlagship = isFlagship;
            this.tilePanel = tilePanel;
            this.clipContainer = clipContainer;
        }
    }

    private static class DropTargetZone {
        final String targetDivisionId; // null = Unassigned Fleet Pool
        final String label;
        final UIPanelAPI panel;
        final UIPanelAPI clipContainer;

        DropTargetZone(String targetDivisionId, String label, UIPanelAPI panel, UIPanelAPI clipContainer) {
            this.targetDivisionId = targetDivisionId;
            this.label = label;
            this.panel = panel;
            this.clipContainer = clipContainer;
        }
    }

    private final UIPanelAPI hostCoreUI;
    private CustomPanelAPI rootModalPanel;
    private CustomPanelAPI contentContainer;
    TextFieldAPI activeNameField;

    private ScrollPanelAPI overviewLeftScroller = null;
    private ScrollPanelAPI overviewRightScroller = null;
    ScrollPanelAPI singleLeftScroller = null;
    ScrollPanelAPI singleRightScroller = null;
    private float savedOverviewLeftScrollY = 0f;
    private float savedOverviewRightScrollY = 0f;
    float savedSingleLeftScrollY = 0f;
    float savedSingleRightScrollY = 0f;

    // When null -> Screen 1 (Fleet Divisions Overview); when non-null -> Screen 2 (Dedicated Division Screen)
    String activeDivisionId = null;
    // Selected division on Screen 1 (Overview) for quick right-click ship assignment
    private String selectedOverviewDivisionId = null;
    String expandedDropdownKey = null;
    boolean carrierOptionsExpanded = false;
    boolean rammingOptionsExpanded = false;
    private boolean closed = false;

    private static final class CheckboxBinding {
        final String actionId;
        boolean expectedChecked;

        CheckboxBinding(String actionId, boolean expectedChecked) {
            this.actionId = actionId;
            this.expectedChecked = expectedChecked;
        }
    }

    private final Map<ButtonAPI, String> activeButtons = new LinkedHashMap<>();
    private final Map<ButtonAPI, CheckboxBinding> activeCheckboxes = new LinkedHashMap<>();
    private final List<ShipIconTile> shipIconTiles = new ArrayList<>();
    private final List<DropTargetZone> dropTargetZones = new ArrayList<>();
    private String pendingAction = null;
    private String pendingConfirmAction = null;
    private String pendingConfirmTitle = null;
    private String pendingConfirmMessage = null;
    private String pendingConfirmYesLabel = null;

    // Mouse & Drag-and-Drop state
    private float mouseX = 0f;
    private float mouseY = 0f;
    private ShipIconTile pressedTile = null;
    private float pressStartX = 0f;
    private float pressStartY = 0f;
    private boolean isDraggingShip = false;
    private String lastClickedDivId = null;
    private long lastDivClickTimeMs = 0L;

    private LabelAPI dragFloatingLabel = null;

    final CustomUIPanelPlugin subPanelPlugin = new BaseCustomUIPanelPlugin() {
        @Override
        public void buttonPressed(Object buttonId) {
            DivisionEditorDialog.this.buttonPressed(buttonId);
        }
    };

    private TooltipMakerAPI dialogTooltipHost = null;

    public DivisionEditorDialog(UIPanelAPI hostCoreUI) {
        this.hostCoreUI = hostCoreUI;
    }

    ButtonAPI registerButton(ButtonAPI button, String actionId) {
        if (button != null && actionId != null) {
            activeButtons.put(button, actionId);
            DivisionUiWidgets.attachActionTooltip(dialogTooltipHost, button, actionId);
        }
        return button;
    }

    ButtonAPI registerCheckbox(ButtonAPI checkbox, String actionId, boolean initialChecked) {
        if (checkbox != null && actionId != null) {
            checkbox.setChecked(initialChecked);
            activeCheckboxes.put(checkbox, new CheckboxBinding(actionId, initialChecked));
            DivisionUiWidgets.attachActionTooltip(dialogTooltipHost, checkbox, actionId);
        }
        return checkbox;
    }

    public void show() {
        if (hostCoreUI == null) return;
        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();

        PermanentDivisionManager.ensureInitializedForPlayerFleet();
        List<PermanentDivisionSpec> divs = PermanentDivisionManager.getPermanentDivisions();
        if (selectedOverviewDivisionId == null && !divs.isEmpty()) {
            selectedOverviewDivisionId = divs.get(0).id;
        }

        rootModalPanel = Global.getSettings().createCustom(screenW, screenH, this);
        hostCoreUI.addComponent(rootModalPanel).inTL(0f, 0f);
        hostCoreUI.bringComponentToTop(rootModalPanel);

        rebuildContent();
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        if (closed) return;
        savePendingNameFieldIfAny();
        closed = true;
        activeButtons.clear();
        activeCheckboxes.clear();
        shipIconTiles.clear();
        dropTargetZones.clear();
        pressedTile = null;
        isDraggingShip = false;
        if (hostCoreUI != null && rootModalPanel != null) {
            hostCoreUI.removeComponent(rootModalPanel);
        }
        rootModalPanel = null;
        contentContainer = null;
        dialogTooltipHost = null;
        DivisionUiWidgets.resetFallbackTooltipHost();
        activeNameField = null;
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

        if (pendingAction == null) {
            for (Map.Entry<ButtonAPI, CheckboxBinding> entry : activeCheckboxes.entrySet()) {
                ButtonAPI cb = entry.getKey();
                CheckboxBinding bind = entry.getValue();
                if (cb != null && bind != null && cb.isChecked() != bind.expectedChecked) {
                    bind.expectedChecked = cb.isChecked();
                    pendingAction = bind.actionId;
                    break;
                }
            }
        }

        if (pendingAction != null) {
            String cmd = pendingAction;
            pendingAction = null;
            handleAction(cmd);
        }
    }


    private final List<DivisionUiWidgets.OtcPennantBox> otcPennantTiles = new ArrayList<>();

    void rebuildContent() {
        if (rootModalPanel == null) return;
        if (overviewLeftScroller != null) {
            savedOverviewLeftScrollY = Math.max(0f, overviewLeftScroller.getYOffset());
            overviewLeftScroller = null;
        }
        if (overviewRightScroller != null) {
            savedOverviewRightScrollY = Math.max(0f, overviewRightScroller.getYOffset());
            overviewRightScroller = null;
        }
        if (singleLeftScroller != null) {
            savedSingleLeftScrollY = Math.max(0f, singleLeftScroller.getYOffset());
            singleLeftScroller = null;
        }
        if (singleRightScroller != null) {
            savedSingleRightScrollY = Math.max(0f, singleRightScroller.getYOffset());
            singleRightScroller = null;
        }
        activeButtons.clear();
        activeCheckboxes.clear();
        shipIconTiles.clear();
        otcPennantTiles.clear();
        dropTargetZones.clear();
        pressedTile = null;
        isDraggingShip = false;

        if (contentContainer != null) {
            rootModalPanel.removeComponent(contentContainer);
            contentContainer = null;
        }
        dialogTooltipHost = null;
        DivisionUiWidgets.resetFallbackTooltipHost();
        activeNameField = null;

        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        float boxX = Math.max(20f, (screenW - DIALOG_WIDTH) * 0.5f);
        float boxYTop = Math.max(20f, (screenH - DIALOG_HEIGHT) * 0.5f);

        contentContainer = Global.getSettings().createCustom(DIALOG_WIDTH, DIALOG_HEIGHT, subPanelPlugin);
        rootModalPanel.addComponent(contentContainer).inTL(boxX, boxYTop);
        dialogTooltipHost = contentContainer.createUIElement(16f, 16f, false);

        List<PermanentDivisionSpec> divisions = PermanentDivisionManager.getPermanentDivisions();
        if (selectedOverviewDivisionId != null && PermanentDivisionManager.getDivisionById(selectedOverviewDivisionId) == null) {
            selectedOverviewDivisionId = divisions.isEmpty() ? null : divisions.get(0).id;
        } else if (selectedOverviewDivisionId == null && !divisions.isEmpty()) {
            selectedOverviewDivisionId = divisions.get(0).id;
        }

        if (pendingConfirmAction != null) {
            buildConfirmationScreen(contentContainer);
            return;
        }

        if (activeDivisionId != null) {
            PermanentDivisionSpec spec = PermanentDivisionManager.getDivisionById(activeDivisionId);
            if (spec != null) {
                selectedOverviewDivisionId = spec.id;
                buildSingleDivisionScreen(contentContainer, spec);
                return;
            }
            activeDivisionId = null;
        }

        buildOverviewScreen(contentContainer);
    }

    private void buildConfirmationScreen(CustomPanelAPI container) {
        Color baseColor = Misc.getBasePlayerColor();
        Color darkColor = Misc.getDarkPlayerColor();
        Color highlight = Misc.getHighlightColor();
        Color negative = Misc.getNegativeHighlightColor();
        Color positive = Misc.getPositiveHighlightColor();

        float boxW = 560f;
        float boxH = 220f;
        float boxX = (DIALOG_WIDTH - boxW) * 0.5f;
        float boxY = (DIALOG_HEIGHT - boxH) * 0.5f;

        final CustomPanelAPI[] cardRef = new CustomPanelAPI[1];
        CustomPanelAPI confirmPanel = Global.getSettings().createCustom(boxW, boxH, new BaseCustomUIPanelPlugin() {
            @Override
            public void renderBelow(float alphaMult) {
                CustomPanelAPI self = cardRef[0];
                if (self == null) return;
                PositionAPI p = self.getPosition();
                if (p == null) return;
                float x = p.getX();
                float y = p.getY();
                float w = p.getWidth();
                float h = p.getHeight();

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                GL11.glColor4f(0.03f, 0.05f, 0.08f, 0.98f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                GL11.glLineWidth(2.0f);
                GL11.glColor4f(1.0f, 0.78f, 0.22f, 0.95f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();
                GL11.glPopAttrib();
            }

            @Override
            public void buttonPressed(Object buttonId) {
                DivisionEditorDialog.this.buttonPressed(buttonId);
            }
        });
        cardRef[0] = confirmPanel;

        TooltipMakerAPI tm = confirmPanel.createUIElement(boxW - 24f, boxH - 20f, false);
        tm.setTitleOrbitronLarge();
        tm.addTitle(pendingConfirmTitle != null ? pendingConfirmTitle : "ARE YOU SURE?", highlight);
        tm.addPara(pendingConfirmMessage != null ? pendingConfirmMessage : "Are you sure you want to proceed?", 14f);

        String yesText = pendingConfirmYesLabel != null ? pendingConfirmYesLabel : "Yes, Confirm";
        boolean isDelete = pendingConfirmAction != null
                && (pendingConfirmAction.startsWith("EXEC_DELETE_DIV:") || pendingConfirmAction.equals("EXEC_CLEAR_ALL_DIVS"));
        ButtonAPI bYes = registerButton(
                tm.addButton(
                        yesText,
                        "CONFIRM_YES",
                        isDelete ? negative : positive,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        230f,
                        28f,
                        0f),
                "CONFIRM_YES");
        bYes.getPosition().inTL(24f, boxH - 58f);

        ButtonAPI bCancel = registerButton(
                tm.addButton(
                        "Cancel",
                        "CONFIRM_CANCEL",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.BL_TR,
                        180f,
                        28f,
                        0f),
                "CONFIRM_CANCEL");
        bCancel.getPosition().inTL(boxW - 228f, boxH - 58f);

        tm.setHeightSoFar(boxH - 20f);
        confirmPanel.addUIElement(tm).inTL(12f, 10f);
        container.addComponent(confirmPanel).inTL(boxX, boxY);
    }

    // ========================================================================================
    // SCREEN 1: FLEET DIVISION EDITOR OVERVIEW (Drag & Drop + Left-Click Select + Right-Click Assign)
    // ========================================================================================
    private void buildOverviewScreen(CustomPanelAPI container) {
        CampaignFleetAPI fleet = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
        List<PermanentDivisionSpec> divisions = PermanentDivisionManager.getPermanentDivisions();
        List<FleetMemberAPI> unassigned = PermanentDivisionManager.getUnassignedCombatMembers(fleet, divisions);

        int assignedCount = 0;
        for (PermanentDivisionSpec d : divisions) {
            assignedCount += d.memberIds.size();
        }

        Color baseColor = Misc.getBasePlayerColor();
        Color darkColor = Misc.getDarkPlayerColor();
        Color highlight = Misc.getHighlightColor();
        Color positive = Misc.getPositiveHighlightColor();
        Color gray = Misc.getGrayColor();

        // Top-right [X] button
        TooltipMakerAPI topCloseUi = container.createUIElement(36f, 24f, false);
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
        container.addUIElement(topCloseUi).inTL(DIALOG_WIDTH - 46f, 8f);

        // 1. Header & Top Toolbar
        PermanentDivisionSpec selectedSpec = selectedOverviewDivisionId != null
                ? PermanentDivisionManager.getDivisionById(selectedOverviewDivisionId)
                : null;
        String selectedDivName = selectedSpec != null ? selectedSpec.name : "None";

        TooltipMakerAPI header = container.createUIElement(DIALOG_WIDTH - 64f, 52f, false);
        header.setTitleOrbitronLarge();
        header.addTitle("FLEET ORGANIZATION", baseColor);
        header.addPara(
                "Selected: %s  |  Drag / Right-Click: Assign or Unassign  |  Left-Click: Set Division Leader",
                4f,
                highlight,
                selectedDivName);
        header.setHeightSoFar(52f);
        container.addUIElement(header).inTL(12f, 6f);

        CustomPanelAPI toolbarRow = Global.getSettings().createCustom(DIALOG_WIDTH - 24f, 28f, subPanelPlugin);
        TooltipMakerAPI tb = toolbarRow.createUIElement(DIALOG_WIDTH - 24f, 28f, false);
        ButtonAPI bCreate = registerButton(
                tb.addButton(
                        "+ New Division",
                        "CREATE_DIV_OVERVIEW",
                        positive,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        180f,
                        24f,
                        0f),
                "CREATE_DIV_OVERVIEW");
        bCreate.getPosition().inTL(0f, 2f);

        ButtonAPI bAutoAssign = registerButton(
                tb.addButton(
                        "Auto-Assign (" + unassigned.size() + ")",
                        "AUTO_ASSIGN",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        180f,
                        24f,
                        0f),
                "AUTO_ASSIGN");
        bAutoAssign.setEnabled(!unassigned.isEmpty());
        bAutoAssign.getPosition().inTL(188f, 2f);

        if (selectedSpec != null) {
            String openSelectedAct = "OPEN_DIV:" + selectedSpec.id;
            ButtonAPI bOpenSel = registerButton(
                    tb.addButton(
                            "Configure: " + truncateText(selectedSpec.name, 16) + " ->",
                            openSelectedAct,
                            highlight,
                            darkColor,
                            Alignment.MID,
                            CutStyle.TL_BR,
                            215f,
                            24f,
                            0f),
                    openSelectedAct);
            bOpenSel.getPosition().inTL(376f, 2f);
        }

        if (!divisions.isEmpty()) {
            ButtonAPI bClearAll = registerButton(
                    tb.addButton(
                            "Disband All",
                            "CLEAR_ALL_DIVS",
                            Misc.getNegativeHighlightColor(),
                            darkColor,
                            Alignment.MID,
                            CutStyle.TL_BR,
                            130f,
                            24f,
                            0f),
                    "CLEAR_ALL_DIVS");
            bClearAll.getPosition().inTL(599f, 2f);
        }

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
        container.addComponent(toolbarRow).inTL(12f, 60f);

        // 2. Left Column: Permanent Divisions Scrollable List (width 760f)
        float bodyHeight = DIALOG_HEIGHT - 104f;
        float leftWidth = 760f;
        float rightWidth = DIALOG_WIDTH - leftWidth - 36f;

        TooltipMakerAPI leftCol = container.createUIElement(leftWidth, bodyHeight, true);
        leftCol.addSectionHeading(
                "Divisions (" + divisions.size() + " Active | " + assignedCount + " Ships)",
                baseColor,
                darkColor,
                Alignment.MID,
                4f);

        if (divisions.isEmpty()) {
            leftCol.addPara(
                    "No divisions formed. Click '+ New Division' or 'Auto-Assign' above.",
                    gray,
                    10f);
        } else {
            FleetDoctrineConfig doctrineCfg = PermanentDivisionManager.getFleetDoctrine();
            for (PermanentDivisionSpec spec : divisions) {
                boolean isSelected = spec.id.equals(selectedOverviewDivisionId);
                List<FleetMemberAPI> members = PermanentDivisionManager.getMembersInDivision(spec, fleet);
                float totalDp = 0f;
                FleetMemberAPI flagship = null;
                for (FleetMemberAPI m : members) {
                    totalDp += m.getDeploymentPointsCost();
                    if (m.getId().equals(spec.flagshipMemberId)) {
                        flagship = m;
                    }
                }
                if (flagship == null && !members.isEmpty()) {
                    flagship = members.get(0);
                }

                // Division Card Container (also acts as a DropTargetZone and Left-Click Selection Area)
                CustomPanelAPI divCard = createDivisionCardPanel(spec, isSelected, leftWidth - 14f, members, flagship, totalDp, doctrineCfg, leftCol);
                leftCol.addCustom(divCard, 6f);
                dropTargetZones.add(new DropTargetZone(spec.id, spec.name, divCard, leftCol));
            }
        }
        container.addUIElement(leftCol).inTL(12f, 94f);
        overviewLeftScroller = leftCol.getExternalScroller();
        restoreScrollOffset(leftCol, overviewLeftScroller, bodyHeight, savedOverviewLeftScrollY);

        // 3. Right Column: OTC Command Style Pennants + Unassigned Combat Hulls Pool (DropTargetZone with targetDivisionId == null)
        TooltipMakerAPI rightCol = container.createUIElement(rightWidth, bodyHeight, true);
        FleetDoctrineConfig rightDocCfg = PermanentDivisionManager.getFleetDoctrine();
        rightCol.addSectionHeading("OTC Command Style", baseColor, darkColor, Alignment.MID, 4f);
        CustomPanelAPI otcPennantPanel = createOtcCommandStylePennantPanel(
                rightWidth - 16f,
                rightDocCfg.getOtcAuthority());
        rightCol.addCustom(otcPennantPanel, 4f);

        String poolHeading = "Unassigned Ships (" + unassigned.size() + ")";
        rightCol.addSectionHeading(poolHeading, baseColor, darkColor, Alignment.MID, 6f);

        if (unassigned.isEmpty()) {
            rightCol.addPara(
                    "All combat ships assigned. Drag or Right-Click any division ship to unassign.",
                    positive,
                    8f);
        } else {
            addInteractiveShipGrid(
                    rightCol,
                    rightWidth - 16f,
                    unassigned,
                    null,
                    null,
                    5,
                    true);
        }
        container.addUIElement(rightCol).inTL(leftWidth + 24f, 94f);
        overviewRightScroller = rightCol.getExternalScroller();
        restoreScrollOffset(rightCol, overviewRightScroller, bodyHeight, savedOverviewRightScrollY);
        dropTargetZones.add(new DropTargetZone(null, "Unassigned Fleet Pool", rightCol, null));
    }

    private CustomPanelAPI createOtcCommandStylePennantPanel(
            float panelWidth,
            final OtcAuthority currentAuthority) {
        final OtcAuthority current = (currentAuthority != null)
                ? currentAuthority
                : OtcAuthority.COMMAND_BY_DIRECTION;
        final OtcAuthority[] allAuths = OtcAuthority.values();
        final float cardW = 104f;
        final float cardH = 104f;
        final float cardGap = 14f;
        final float totalCardsW = allAuths.length * cardW + (allAuths.length - 1) * cardGap;
        final float startX = Math.max(8f, Math.round((panelWidth - totalCardsW) * 0.5f));
        final float textWidth = panelWidth - 16f;
        final int wrapChars = Math.max(48, (int) Math.floor(textWidth / 7.2f));
        final List<String> descLines = wrapTextToLines(current.summary, wrapChars);
        final List<String> posLines = wrapTextToLines(current.positiveText, wrapChars);
        final List<String> negLines = wrapTextToLines(current.negativeText, wrapChars);
        final int totalWrappedLines = descLines.size() + posLines.size() + negLines.size();
        final float lineStep = 15f;
        final float panelHeight = 142f + totalWrappedLines * lineStep + 14f;
        final CustomPanelAPI[] panelRef = new CustomPanelAPI[1];

        BaseCustomUIPanelPlugin plugin = new BaseCustomUIPanelPlugin() {
            @Override
            public void renderBelow(float alphaMult) {
                if (panelRef[0] == null || panelRef[0].getPosition() == null) return;
                PositionAPI p = panelRef[0].getPosition();
                float px = p.getX();
                float py = p.getY();
                float pw = p.getWidth();
                float ph = p.getHeight();

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glColor4f(0.03f, 0.05f, 0.09f, 0.78f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(px, py);
                GL11.glVertex2f(px + pw, py);
                GL11.glVertex2f(px + pw, py + ph);
                GL11.glVertex2f(px, py + ph);
                GL11.glEnd();
                GL11.glLineWidth(1.2f);
                GL11.glColor4f(0.22f, 0.34f, 0.46f, 0.55f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(px, py);
                GL11.glVertex2f(px + pw, py);
                GL11.glVertex2f(px + pw, py + ph);
                GL11.glVertex2f(px, py + ph);
                GL11.glEnd();
                GL11.glPopAttrib();

                float cardBottomY = py + ph - 6f - cardH;
                for (int i = 0; i < allAuths.length; i++) {
                    OtcAuthority auth = allAuths[i];
                    float cx = px + startX + i * (cardW + cardGap);
                    boolean hovered = (mouseX >= cx && mouseX <= cx + cardW
                            && mouseY >= cardBottomY && mouseY <= cardBottomY + cardH);
                    FleetDoctrineDialog.renderOtcAuthorityPennantBox(
                            auth, cx, cardBottomY, cardW, cardH, auth == current, hovered, alphaMult);
                }
            }
        };

        CustomPanelAPI panel = Global.getSettings().createCustom(panelWidth, panelHeight, plugin);
        panelRef[0] = panel;
        for (int i = 0; i < allAuths.length; i++) {
            float relX = startX + i * (cardW + cardGap);
            float relY = panelHeight - 6f - cardH;
            otcPennantTiles.add(new DivisionUiWidgets.OtcPennantBox(allAuths[i], panel, relX, relY, cardW, cardH));
        }

        LabelAPI headerLbl = Global.getSettings().createLabel(current.dropdownLabel, Fonts.ORBITRON_20AA);
        headerLbl.setColor(new Color(255, 222, 90, 255));
        headerLbl.setAlignment(Alignment.MID);
        panel.addComponent((UIComponentAPI) headerLbl).setSize(textWidth, 22f).inTL(8f, 114f);
        DivisionUiWidgets.addTooltip(
                null,
                (UIComponentAPI) headerLbl,
                "OTC Command Style: " + current.dropdownLabel,
                current.summary + " Click any of the four naval pennants above to switch command authority.",
                current.positiveText,
                current.negativeText);

        float textY = 138f;
        Color descColor = new Color(195, 220, 240, 245);
        for (String line : descLines) {
            LabelAPI lbl = Global.getSettings().createLabel(line, Fonts.GROUP_NUM_FONT);
            lbl.setColor(descColor);
            lbl.setAlignment(Alignment.MID);
            panel.addComponent((UIComponentAPI) lbl).setSize(textWidth, 15f).inTL(8f, textY);
            textY += lineStep;
        }
        textY += 3f;
        Color posColor = Misc.getPositiveHighlightColor();
        for (String line : posLines) {
            LabelAPI lbl = Global.getSettings().createLabel(line, Fonts.GROUP_NUM_FONT);
            lbl.setColor(posColor);
            lbl.setAlignment(Alignment.MID);
            panel.addComponent((UIComponentAPI) lbl).setSize(textWidth, 15f).inTL(8f, textY);
            textY += lineStep;
        }
        textY += 3f;
        Color negColor = Misc.getNegativeHighlightColor();
        for (String line : negLines) {
            LabelAPI lbl = Global.getSettings().createLabel(line, Fonts.GROUP_NUM_FONT);
            lbl.setColor(negColor);
            lbl.setAlignment(Alignment.MID);
            panel.addComponent((UIComponentAPI) lbl).setSize(textWidth, 15f).inTL(8f, textY);
            textY += lineStep;
        }

        return panel;
    }

    public static List<String> wrapTextToLines(String text, int maxCharsPerLine) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return lines;
        }
        String remaining = text.trim();
        int limit = Math.max(24, maxCharsPerLine);
        while (remaining.length() > limit) {
            int splitIdx = remaining.lastIndexOf(' ', limit);
            if (splitIdx <= 0) {
                splitIdx = remaining.indexOf(' ', limit);
            }
            if (splitIdx <= 0) {
                break;
            }
            lines.add(remaining.substring(0, splitIdx).trim());
            remaining = remaining.substring(splitIdx + 1).trim();
        }
        if (!remaining.isEmpty()) {
            lines.add(remaining);
        }
        return lines;
    }

    private CustomPanelAPI createDivisionCardPanel(
            final PermanentDivisionSpec spec,
            final boolean isSelected,
            float cardWidth,
            List<FleetMemberAPI> members,
            FleetMemberAPI flagship,
            float totalDp,
            FleetDoctrineConfig doctrineCfg,
            UIPanelAPI clipContainer) {

        Color baseColor = Misc.getBasePlayerColor();
        Color darkColor = Misc.getDarkPlayerColor();
        Color highlight = Misc.getHighlightColor();
        Color positive = Misc.getPositiveHighlightColor();
        Color gray = Misc.getGrayColor();

        int cols = 7;
        int iconRows = members.isEmpty() ? 1 : ((members.size() + cols - 1) / cols);
        float gridHeight = members.isEmpty() ? 52f : (iconRows * 84f + 6f);
        float cardHeight = 68f + gridHeight;

        final String divId = spec.id;
        CustomPanelAPI cardPanel = Global.getSettings().createCustom(cardWidth, cardHeight, new BaseCustomUIPanelPlugin() {
            @Override
            public void buttonPressed(Object buttonId) {
                DivisionEditorDialog.this.buttonPressed(buttonId);
            }

            @Override
            public void renderBelow(float alphaMult) {
                PositionAPI p = getPanelPosition();
                if (p == null) return;
                float x = p.getX();
                float y = p.getY();
                float w = p.getWidth();
                float h = p.getHeight();

                boolean hoveredDrop = isDraggingShip && isPointInside(mouseX, mouseY, p);

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                if (hoveredDrop) {
                    GL11.glColor4f(0.10f, 0.32f, 0.18f, 0.85f * alphaMult);
                } else if (isSelected) {
                    GL11.glColor4f(0.08f, 0.16f, 0.24f, 0.88f * alphaMult);
                } else {
                    GL11.glColor4f(0.05f, 0.08f, 0.13f, 0.72f * alphaMult);
                }
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                GL11.glLineWidth(hoveredDrop ? 2.5f : (isSelected ? 2.2f : 1.2f));
                if (hoveredDrop) {
                    GL11.glColor4f(0.30f, 1.00f, 0.48f, 0.98f * alphaMult);
                } else if (isSelected) {
                    GL11.glColor4f(1.00f, 0.84f, 0.22f, 0.95f * alphaMult);
                } else {
                    GL11.glColor4f(
                            baseColor.getRed() / 255f,
                            baseColor.getGreen() / 255f,
                            baseColor.getBlue() / 255f,
                            0.55f * alphaMult);
                }
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();
                GL11.glPopAttrib();
            }

            private PositionAPI getPanelPosition() {
                for (DropTargetZone z : dropTargetZones) {
                    if (z.panel != null && divId.equals(z.targetDivisionId)) {
                        return z.panel.getPosition();
                    }
                }
                return null;
            }
        });

        // Header bar inside card
        TooltipMakerAPI topUi = cardPanel.createUIElement(cardWidth - 8f, 60f, false);

        String selectAct = "SELECT_DIV:" + spec.id;
        String selectLabel = spec.name + "  |  " + spec.archetype.shortPrefix
                + String.format(Locale.ROOT, "  (%d/%d | %.0f DP)", members.size(), PermanentDivisionManager.MAX_DIVISION_SIZE, totalDp);
        ButtonAPI bSelect = registerButton(
                topUi.addButton(
                        selectLabel,
                        selectAct,
                        isSelected ? highlight : baseColor,
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        cardWidth - 246f,
                        22f,
                        0f),
                selectAct);
        if (isSelected) bSelect.highlight();
        bSelect.getPosition().inTL(4f, 4f);

        String openAct = "OPEN_DIV:" + spec.id;
        ButtonAPI bOpen = registerButton(
                topUi.addButton(
                        "Configure ->",
                        openAct,
                        positive,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        126f,
                        22f,
                        0f),
                openAct);
        bOpen.getPosition().inTL(cardWidth - 238f, 4f);

        String delAct = "DELETE_DIV:" + spec.id;
        ButtonAPI bDel = registerButton(
                topUi.addButton(
                        "Disband",
                        delAct,
                        Misc.getNegativeHighlightColor(),
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        100f,
                        22f,
                        0f),
                delAct);
        bDel.getPosition().inTL(cardWidth - 106f, 4f);

        int effIntra = PermanentDivisionManager.resolveEffectiveStationSpacing(spec, doctrineCfg);
        int effStandoff = PermanentDivisionManager.resolveEffectiveStandoffRange(spec, doctrineCfg);
        LineStation effSpace = PermanentDivisionManager.resolveEffectiveLineStation(spec, doctrineCfg);
        final DivisionFormation effForm = PermanentDivisionManager.resolveEffectiveFormation(spec, doctrineCfg);
        DivisionMissionType effOpen = PermanentDivisionManager.resolveEffectiveOpeningMission(spec, doctrineCfg);
        DivisionMissionType effMain = PermanentDivisionManager.resolveEffectiveMainMission(spec, doctrineCfg);
        StrikeCraftMissionType effScOpen = PermanentDivisionManager.resolveEffectiveStrikeCraftOpeningMission(spec, doctrineCfg);
        StrikeCraftMissionType effScMain = PermanentDivisionManager.resolveEffectiveStrikeCraftMainMission(spec, doctrineCfg);
        boolean hasIndepSc = effScOpen != StrikeCraftMissionType.MATCH_DIVISION
                || effScMain != StrikeCraftMissionType.MATCH_DIVISION;

        PersonAPI cap = (flagship != null) ? flagship.getCaptain() : null;
        String rawCmdrDesc = (cap != null && !cap.isDefault())
                ? (cap.getNameString() + " [" + cap.getPersonalityAPI().getDisplayName() + "]")
                : "No Officer";
        String cmdrDesc = truncateText(rawCmdrDesc, hasIndepSc ? 18 : 26);
        String scTag = hasIndepSc
                ? ("  |  SC: " + (effScOpen == effScMain ? effScOpen.shortName : (effScOpen.shortName + "->" + effScMain.shortName)))
                : "";

        LabelAPI sub1 = topUi.addPara(
                "%s  |  %s  |  %su / %su  |  %s -> %s%s",
                2f,
                isSelected ? positive : gray,
                cmdrDesc,
                effSpace.displayName,
                String.valueOf(effIntra),
                String.valueOf(effStandoff),
                effOpen.shortName,
                effMain.shortName,
                scTag);
        sub1.getPosition().inTL(26f, 30f);
        DivisionUiWidgets.addTooltip(
                topUi,
                (UIComponentAPI) sub1,
                spec.name + " (" + spec.archetype.shortPrefix + ")",
                "Commander: " + rawCmdrDesc
                        + " | Line Station: " + effSpace.displayName + " (" + effSpace.summary + ")"
                        + " | Station Spacing: " + effIntra + "u | Standoff Range: " + effStandoff + "u"
                        + " | Hull Mission: " + effOpen.dropdownLabel + " -> " + effMain.dropdownLabel
                        + " | Strike Craft Mission: " + effScOpen.dropdownLabel + " -> " + effScMain.dropdownLabel + ".");

        final boolean flagshipDivCard = isFlagshipDivision(spec, members);
        final CustomPanelAPI[] badgeRef = new CustomPanelAPI[1];
        CustomPanelAPI formBadge = Global.getSettings().createCustom(18f, 18f, new BaseCustomUIPanelPlugin() {
            @Override
            public void buttonPressed(Object buttonId) {
                DivisionEditorDialog.this.buttonPressed(buttonId);
            }

            @Override
            public void render(float alphaMult) {
                CustomPanelAPI self = badgeRef[0];
                if (self == null || self.getPosition() == null) return;
                PositionAPI p = self.getPosition();
                FleetDoctrineDialog.renderFormationIcon(
                        effForm,
                        p.getX() + 9f,
                        p.getY() + 9f,
                        16f,
                        isSelected ? positive : highlight,
                        alphaMult,
                        flagshipDivCard);
            }
        });
        badgeRef[0] = formBadge;
        cardPanel.addComponent(formBadge).inTL(6f, 30f);
        DivisionUiWidgets.addTooltip(
                topUi,
                formBadge,
                "Division Formation: " + effForm.displayName,
                effForm.summary);

        topUi.setHeightSoFar(56f);
        cardPanel.addUIElement(topUi).inTL(2f, 2f);

        // Ship Icon Grid inside card
        if (members.isEmpty()) {
            TooltipMakerAPI emptyUi = cardPanel.createUIElement(cardWidth - 16f, 44f, false);
            emptyUi.addPara(
                    "Empty division - drag or right-click ships from the right panel to assign.",
                    gray,
                    10f);
            emptyUi.setHeightSoFar(44f);
            cardPanel.addUIElement(emptyUi).inTL(8f, 60f);
        } else {
            float tileW = 98f;
            float tileH = 78f;
            float gapX = 6f;
            float gapY = 6f;
            String flagId = (flagship != null) ? flagship.getId() : spec.flagshipMemberId;

            for (int i = 0; i < members.size(); i++) {
                FleetMemberAPI m = members.get(i);
                int r = i / cols;
                int c = i % cols;
                float tx = 8f + c * (tileW + gapX);
                float ty = 62f + r * (tileH + gapY);
                boolean isFlag = m.getId().equals(flagId);
                CustomPanelAPI tile = createInteractiveShipTile(m, spec.id, isFlag, tileW, tileH, clipContainer);
                cardPanel.addComponent(tile).inTL(tx, ty);
            }
        }

        return cardPanel;
    }

    private void buildSingleDivisionScreen(CustomPanelAPI container, PermanentDivisionSpec spec) {
        DivisionConfigureSubscreen.buildSingleDivisionScreen(this, container, spec);
    }

    void registerDropTargetZone(String targetDivisionId, String label, UIPanelAPI panel, UIPanelAPI clipContainer) {
        dropTargetZones.add(new DropTargetZone(targetDivisionId, label, panel, clipContainer));
    }

    /**
     * Builds a multi-column grid of interactive ShipIconTile widgets inside a TooltipMakerAPI column.
     */
    void addInteractiveShipGrid(
            TooltipMakerAPI col,
            float gridWidth,
            List<FleetMemberAPI> ships,
            String sourceDivisionId,
            String flagshipId,
            int numCols,
            boolean showNewDivButtonBelow) {

        if (ships == null || ships.isEmpty()) return;

        float gapX = 6f;
        float tileW = (float) Math.floor((gridWidth - (numCols - 1) * gapX) / numCols);
        float tileH = 78f;

        int rows = (ships.size() + numCols - 1) / numCols;
        float rowBlockH = tileH + (showNewDivButtonBelow ? 22f : 0f);
        float totalH = rows * rowBlockH + (rows - 1) * 6f + 4f;

        CustomPanelAPI gridPanel = Global.getSettings().createCustom(gridWidth, totalH, subPanelPlugin);
        TooltipMakerAPI btnLayer = showNewDivButtonBelow ? gridPanel.createUIElement(gridWidth, totalH, false) : null;

        Color baseColor = Misc.getBasePlayerColor();
        Color darkColor = Misc.getDarkPlayerColor();

        for (int i = 0; i < ships.size(); i++) {
            FleetMemberAPI m = ships.get(i);
            int r = i / numCols;
            int c = i % numCols;
            float tx = c * (tileW + gapX);
            float ty = r * (rowBlockH + 6f);
            boolean isFlag = (flagshipId != null && flagshipId.equals(m.getId()));

            CustomPanelAPI tile = createInteractiveShipTile(m, sourceDivisionId, isFlag, tileW, tileH, col);
            gridPanel.addComponent(tile).inTL(tx, ty);

            if (showNewDivButtonBelow && btnLayer != null) {
                String newDivAct = "NEW_DIV_WITH:" + m.getId();
                ButtonAPI bNew = registerButton(
                        btnLayer.addButton(
                                "+ New Div",
                                newDivAct,
                                baseColor,
                                darkColor,
                                Alignment.MID,
                                CutStyle.ALL,
                                tileW,
                                18f,
                                0f),
                        newDivAct);
                bNew.getPosition().inTL(tx, ty + tileH + 2f);
            }
        }

        if (btnLayer != null) {
            btnLayer.setHeightSoFar(totalH);
            gridPanel.addUIElement(btnLayer).inTL(0f, 0f);
        }

        col.addCustom(gridPanel, 4f);
    }

    /**
     * Creates a single interactive ShipIconTile that renders the ship's actual hull sprite,
     * flagship badge, officer badge, DP cost, and hull name, and registers it for drag-and-drop
     * and left/right-click actions.
     */
    private CustomPanelAPI createInteractiveShipTile(
            final FleetMemberAPI member,
            final String sourceDivisionId,
            final boolean isFlagship,
            final float width,
            final float height,
            final UIPanelAPI clipContainer) {

        final Color baseColor = Misc.getBasePlayerColor();
        final Color highlight = Misc.getHighlightColor();
        final Color positive = Misc.getPositiveHighlightColor();

        final PersonAPI captain = member.getCaptain();
        final boolean hasCommander = (captain != null && !captain.isDefault());
        final int commanderLevel = (hasCommander && captain.getStats() != null)
                ? captain.getStats().getLevel()
                : 0;
        final boolean showCommanderLevel = hasCommander && commanderLevel >= 1;
        final float portraitBoxSize = 24f;

        final CustomPanelAPI[] panelRef = new CustomPanelAPI[1];
        CustomPanelAPI tilePanel = Global.getSettings().createCustom(width, height, new BaseCustomUIPanelPlugin() {
            @Override
            public void renderBelow(float alphaMult) {
                CustomPanelAPI selfPanel = panelRef[0];
                if (selfPanel == null) return;
                PositionAPI p = selfPanel.getPosition();
                if (p == null) return;

                float x = p.getX();
                float y = p.getY();
                float w = p.getWidth();
                float h = p.getHeight();

                boolean insideClip = clipContainer == null || isPointInsidePanel(mouseX, mouseY, clipContainer);
                boolean hovered = insideClip && isPointInside(mouseX, mouseY, p);
                boolean beingDragged = isDraggingShip && pressedTile != null && pressedTile.member == member;
                float effAlpha = beingDragged ? 0.45f * alphaMult : alphaMult;

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                // Tile background
                if (beingDragged) {
                    GL11.glColor4f(0.18f, 0.24f, 0.30f, 0.45f * alphaMult);
                } else if (hovered) {
                    GL11.glColor4f(0.12f, 0.24f, 0.34f, 0.92f * alphaMult);
                } else if (isFlagship) {
                    GL11.glColor4f(0.14f, 0.14f, 0.05f, 0.85f * alphaMult);
                } else {
                    GL11.glColor4f(0.04f, 0.07f, 0.11f, 0.85f * alphaMult);
                }
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                // Tile border
                GL11.glLineWidth(hovered ? 2.0f : (isFlagship ? 1.8f : 1.1f));
                if (hovered) {
                    GL11.glColor4f(0.45f, 0.95f, 1.00f, 0.98f * alphaMult);
                } else if (isFlagship) {
                    GL11.glColor4f(1.00f, 0.84f, 0.20f, 0.95f * alphaMult);
                } else {
                    GL11.glColor4f(
                            baseColor.getRed() / 255f,
                            baseColor.getGreen() / 255f,
                            baseColor.getBlue() / 255f,
                            0.65f * alphaMult);
                }
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                GL11.glPopAttrib();

                // Render Ship Hull Sprite centered in upper portion of tile
                renderShipMemberSprite(
                        member,
                        x + w * 0.5f,
                        y + h * 0.56f,
                        w - 14f,
                        h - 30f,
                        effAlpha);

                // Render Commander Portrait on the upper-left side of the ship icon box
                float px = x + 2f;
                float py = y + h - portraitBoxSize - 16f;
                if (hasCommander) {
                    GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                    // Dark portrait backing box
                    GL11.glColor4f(0.02f, 0.04f, 0.07f, 0.92f * effAlpha);
                    GL11.glBegin(GL11.GL_QUADS);
                    GL11.glVertex2f(px, py);
                    GL11.glVertex2f(px + portraitBoxSize, py);
                    GL11.glVertex2f(px + portraitBoxSize, py + portraitBoxSize);
                    GL11.glVertex2f(px, py + portraitBoxSize);
                    GL11.glEnd();
                    GL11.glPopAttrib();

                    renderCommanderPortraitSprite(
                            captain,
                            px + portraitBoxSize * 0.5f,
                            py + portraitBoxSize * 0.5f,
                            portraitBoxSize - 2f,
                            portraitBoxSize - 2f,
                            effAlpha);

                    GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                    // Crisp border around commander portrait
                    GL11.glLineWidth(1.1f);
                    GL11.glColor4f(
                            baseColor.getRed() / 255f,
                            baseColor.getGreen() / 255f,
                            baseColor.getBlue() / 255f,
                            0.90f * effAlpha);
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2f(px, py);
                    GL11.glVertex2f(px + portraitBoxSize, py);
                    GL11.glVertex2f(px + portraitBoxSize, py + portraitBoxSize);
                    GL11.glVertex2f(px, py + portraitBoxSize);
                    GL11.glEnd();

                    // Soft backing behind the top-right level text inside the portrait box (Fleet screen style).
                    // Drawn in renderBelow() so the level label (a child element) renders ON TOP of it; in render()
                    // this quad was painted over the label and showed up as an opaque black square.
                    if (showCommanderLevel) {
                        float badgeW = commanderLevel >= 10 ? 13f : 9f;
                        float badgeH = 10f;
                        float bx = px + portraitBoxSize - badgeW;
                        float by = py + portraitBoxSize - badgeH;
                        GL11.glColor4f(0.0f, 0.0f, 0.0f, 0.45f * effAlpha);
                        GL11.glBegin(GL11.GL_QUADS);
                        GL11.glVertex2f(bx, by);
                        GL11.glVertex2f(px + portraitBoxSize, by);
                        GL11.glVertex2f(px + portraitBoxSize, py + portraitBoxSize);
                        GL11.glVertex2f(bx, py + portraitBoxSize);
                        GL11.glEnd();
                    }

                    GL11.glPopAttrib();
                }

                // Per Item D: Division/Fleet flagship star renders directly under the commander portrait on the left side
                // of the ship icon if a commander exists; if there is no commander, the star replaces the commander portrait.
                boolean isFleetFlag = member.isFlagship();
                if (isFleetFlag || isFlagship) {
                    try {
                        SpriteAPI starSprite = Global.getSettings().getSprite(
                                "division_ai",
                                isFleetFlag ? "star_gold" : "star_silver");
                        if (starSprite != null) {
                            float starSize = 16f;
                            float starX = px + portraitBoxSize * 0.5f;
                            float starY = hasCommander
                                    ? (py - starSize * 0.5f - 2f)
                                    : (py + portraitBoxSize * 0.5f);
                            starSprite.setSize(starSize, starSize);
                            starSprite.setColor(Color.WHITE);
                            starSprite.setAlphaMult(Math.min(1f, effAlpha * 0.96f));
                            starSprite.setAngle(0f);
                            starSprite.renderAtCenter(starX, starY);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        panelRef[0] = tilePanel;

        // Text overlay labels inside the tile (top badge: FLAG/DP, left portrait level, bottom label: hull name)
        TooltipMakerAPI textUi = tilePanel.createUIElement(width, height, false);
        String topBadge = (isFlagship ? "*LDR " : "")
                + String.format(Locale.ROOT, "%.0f DP", member.getDeploymentPointsCost());
        LabelAPI topLbl = textUi.addPara(topBadge, isFlagship ? highlight : positive, 0f);
        topLbl.getPosition().inTL(3f, 2f);

        String hullName = member.getHullSpec() != null ? member.getHullSpec().getHullName() : member.getShipName();
        int maxHullChars = 12;
        LabelAPI botLbl = textUi.addPara(truncateText(hullName, maxHullChars), baseColor, 0f);
        botLbl.getPosition().inTL(3f, height - 17f);

        String officerDesc = hasCommander
                ? (captain.getNameString()
                + (captain.getPersonalityAPI() != null ? " [" + captain.getPersonalityAPI().getDisplayName() + "]" : "")
                + (commanderLevel > 0 ? " (Lvl " + commanderLevel + ")" : ""))
                : "No Officer";
        String roleDesc = isFlagship
                ? "Division Leader (*LDR)"
                : (sourceDivisionId != null ? "Assigned Division Member" : "Unassigned Fleet Pool");
        String tileTipTitle = (member.getShipName() != null ? member.getShipName() + " - " : "")
                + hullName + String.format(Locale.ROOT, " (%.0f DP)", member.getDeploymentPointsCost());
        String tileTipBody = "Officer: " + officerDesc + " | Status: " + roleDesc
                + ". Left-Click: Set Division Leader (in division) or assign to selected division. "
                + "Right-Click: Quick-assign or unassign. Drag & Drop: Move between divisions or swap order.";
        DivisionUiWidgets.addTooltip(textUi, (UIComponentAPI) topLbl, tileTipTitle, tileTipBody);
        DivisionUiWidgets.addTooltip(textUi, (UIComponentAPI) botLbl, tileTipTitle, tileTipBody);

        if (showCommanderLevel) {
            // Match vanilla Fleet screen (com.fs.starfarer.coreui.OOoo): Victor 10 font at top-right of the portrait box
            textUi.setParaFont(Fonts.VICTOR_10);
            Color levelColor = (captain.getFaction() != null && captain.getFaction().getBrightUIColor() != null)
                    ? captain.getFaction().getBrightUIColor()
                    : Misc.getBrightPlayerColor();
            String lvlStr = String.valueOf(commanderLevel);
            LabelAPI lvlLbl = textUi.addPara(lvlStr, levelColor, 0f);
            float lvlW = lvlLbl.computeTextWidth(lvlStr);
            float lvlX = 2f + portraitBoxSize - lvlW - 1f;
            float lvlY = 16f;
            lvlLbl.getPosition().inTL(lvlX, lvlY);
            textUi.setParaFontDefault();
        }

        textUi.setHeightSoFar(height);
        tilePanel.addUIElement(textUi).inTL(0f, 0f);

        shipIconTiles.add(new ShipIconTile(member, sourceDivisionId, isFlagship, tilePanel, clipContainer));
        return tilePanel;
    }

    private static void renderCommanderPortraitSprite(
            PersonAPI captain,
            float centerX,
            float centerY,
            float drawW,
            float drawH,
            float alpha) {
        if (captain == null || captain.getPortraitSprite() == null || captain.getPortraitSprite().isEmpty()) {
            return;
        }
        try {
            SpriteAPI sprite = Global.getSettings().getSprite(captain.getPortraitSprite());
            if (sprite == null) return;
            float origW = sprite.getWidth();
            float origH = sprite.getHeight();
            float origCx = sprite.getCenterX();
            float origCy = sprite.getCenterY();
            Color oldColor = sprite.getColor();
            float oldAngle = sprite.getAngle();

            sprite.setSize(drawW, drawH);
            sprite.setCenter(drawW * 0.5f, drawH * 0.5f);
            sprite.setAngle(0f);
            sprite.setColor(new Color(255, 255, 255, Math.max(0, Math.min(255, Math.round(alpha * 255f)))));
            sprite.renderAtCenter(centerX, centerY);

            sprite.setSize(origW, origH);
            sprite.setCenter(origCx, origCy);
            sprite.setAngle(oldAngle);
            if (oldColor != null) {
                sprite.setColor(oldColor);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void renderShipMemberSprite(
            FleetMemberAPI member,
            float centerX,
            float centerY,
            float maxW,
            float maxH,
            float alpha) {
        if (member == null || member.getHullSpec() == null) return;
        String spriteName = member.getHullSpec().getSpriteName();
        if (spriteName == null || spriteName.isEmpty()) return;

        try {
            SpriteAPI sprite = Global.getSettings().getSprite(spriteName);
            if (sprite == null) return;

            float origW = sprite.getWidth();
            float origH = sprite.getHeight();
            if (origW <= 0f || origH <= 0f) return;
            float origCx = sprite.getCenterX();
            float origCy = sprite.getCenterY();

            float scale = Math.min(maxW / origW, maxH / origH);
            scale = Math.min(scale, 0.85f);
            float drawW = origW * scale;
            float drawH = origH * scale;

            Color oldColor = sprite.getColor();
            float oldAngle = sprite.getAngle();

            sprite.setSize(drawW, drawH);
            sprite.setCenter(drawW * 0.5f, drawH * 0.5f);
            sprite.setAngle(0f);
            sprite.setColor(new Color(255, 255, 255, Math.max(0, Math.min(255, Math.round(alpha * 255f)))));
            sprite.renderAtCenter(centerX, centerY);

            sprite.setSize(origW, origH);
            sprite.setCenter(origCx, origCy);
            sprite.setAngle(oldAngle);
            if (oldColor != null) {
                sprite.setColor(oldColor);
            }
        } catch (Throwable ignored) {
        }
    }









    public static boolean isFlagshipDivision(PermanentDivisionSpec spec, List<FleetMemberAPI> resolvedMembers) {
        if (spec == null) return false;
        if (resolvedMembers != null) {
            for (FleetMemberAPI m : resolvedMembers) {
                if (m != null && m.isFlagship()) {
                    return true;
                }
            }
        }
        try {
            CampaignFleetAPI pf = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
            if (pf != null) {
                if (resolvedMembers == null) {
                    List<FleetMemberAPI> members = PermanentDivisionManager.getMembersInDivision(spec, pf);
                    for (FleetMemberAPI m : members) {
                        if (m != null && m.isFlagship()) {
                            return true;
                        }
                    }
                }
                if (pf.getFlagship() != null) {
                    String fid = pf.getFlagship().getId();
                    if (fid != null) {
                        if (fid.equals(spec.flagshipMemberId)) return true;
                        if (spec.memberIds != null && spec.memberIds.contains(fid)) return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    boolean isSelectedDivisionFlagship() {
        String divId = (activeDivisionId != null) ? activeDivisionId : selectedOverviewDivisionId;
        if (divId == null) return false;
        PermanentDivisionSpec s = PermanentDivisionManager.getDivisionById(divId);
        return isFlagshipDivision(s, null);
    }



    void savePendingNameFieldIfAny() {
        if (activeDivisionId != null && activeNameField != null) {
            String text = activeNameField.getText();
            if (text != null && !text.trim().isEmpty()) {
                PermanentDivisionManager.updateDivisionMetadata(activeDivisionId, text.trim(), null, null);
            }
        }
    }

    @Override
    public void buttonPressed(Object buttonId) {
        if (closed || !(buttonId instanceof String)) return;
        pendingAction = (String) buttonId;
    }

    private void handleAction(String cmd) {
        if (closed || cmd == null) return;

        if ("CLOSE".equals(cmd)) {
            close();
            return;
        }
        if ("BACK_TO_LIST".equals(cmd)) {
            savePendingNameFieldIfAny();
            activeDivisionId = null;
            expandedDropdownKey = null;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SELECT_DIV:")) {
            String divId = cmd.substring("SELECT_DIV:".length());
            long nowMs = System.currentTimeMillis();
            if (divId.equals(lastClickedDivId) && (nowMs - lastDivClickTimeMs) < 380L) {
                activeDivisionId = divId;
                selectedOverviewDivisionId = divId;
            } else {
                selectedOverviewDivisionId = divId;
            }
            lastClickedDivId = divId;
            lastDivClickTimeMs = nowMs;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("TOGGLE_DD:")) {
            savePendingNameFieldIfAny();
            String key = cmd.substring("TOGGLE_DD:".length());
            expandedDropdownKey = key.equals(expandedDropdownKey) ? null : key;
            rebuildContent();
            return;
        }
        if (DivisionConfigureSubscreen.handleDivisionOverrideAction(this, cmd)) {
            return;
        }
        if ("APPLY_NAME".equals(cmd)) {
            savePendingNameFieldIfAny();
            rebuildContent();
            return;
        }
        if ("CREATE_DIV_OVERVIEW".equals(cmd)) {
            PermanentDivisionSpec created = PermanentDivisionManager.createNewDivision(
                    DivisionArchetype.FORCE_DIVISION,
                    null);
            if (created != null) {
                selectedOverviewDivisionId = created.id;
            }
            rebuildContent();
            return;
        }
        if ("CREATE_DIV".equals(cmd)) {
            PermanentDivisionSpec created = PermanentDivisionManager.createNewDivision(
                    DivisionArchetype.FORCE_DIVISION,
                    null);
            if (created != null) {
                activeDivisionId = created.id;
                selectedOverviewDivisionId = created.id;
            }
            rebuildContent();
            return;
        }
        if ("CONFIRM_CANCEL".equals(cmd)) {
            pendingConfirmAction = null;
            pendingConfirmTitle = null;
            pendingConfirmMessage = null;
            pendingConfirmYesLabel = null;
            rebuildContent();
            return;
        }
        if ("CONFIRM_YES".equals(cmd)) {
            String execCmd = pendingConfirmAction;
            pendingConfirmAction = null;
            pendingConfirmTitle = null;
            pendingConfirmMessage = null;
            pendingConfirmYesLabel = null;
            if (execCmd != null) {
                handleAction(execCmd);
            } else {
                rebuildContent();
            }
            return;
        }
        if ("CLEAR_ALL_DIVS".equals(cmd)) {
            pendingConfirmAction = "EXEC_CLEAR_ALL_DIVS";
            pendingConfirmTitle = "DELETE ALL DIVISIONS - ARE YOU SURE?";
            pendingConfirmMessage = "Are you sure you want to delete all permanent divisions in your fleet? All ships will return to the unassigned fleet pool.";
            pendingConfirmYesLabel = "Yes, Delete All";
            rebuildContent();
            return;
        }
        if ("EXEC_CLEAR_ALL_DIVS".equals(cmd)) {
            PermanentDivisionManager.clearAllDivisions();
            activeDivisionId = null;
            selectedOverviewDivisionId = null;
            rebuildContent();
            return;
        }
        if ("AUTO_ASSIGN".equals(cmd)) {
            pendingConfirmAction = "EXEC_AUTO_ASSIGN";
            pendingConfirmTitle = "AUTO-ASSIGN SHIPS - ARE YOU SURE?";
            pendingConfirmMessage = "Are you sure you want to automatically assign unassigned fleet ships to divisions based on your Fleet Doctrine?";
            pendingConfirmYesLabel = "Yes, Auto-Assign";
            rebuildContent();
            return;
        }
        if ("EXEC_AUTO_ASSIGN".equals(cmd)) {
            PermanentDivisionManager.autoAssignUnassignedMembers();
            rebuildContent();
            return;
        }
        if ("RESET_AUTO".equals(cmd)) {
            pendingConfirmAction = "EXEC_RESET_AUTO";
            pendingConfirmTitle = "RESET & AUTO-FORM - ARE YOU SURE?";
            pendingConfirmMessage = "Are you sure you want to clear existing divisions and auto-form new divisions across your fleet?";
            pendingConfirmYesLabel = "Yes, Reset & Auto-Form";
            rebuildContent();
            return;
        }
        if ("EXEC_RESET_AUTO".equals(cmd)) {
            PermanentDivisionManager.resetAndAutoFormFromPlayerFleet();
            activeDivisionId = null;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("OPEN_DIV:")) {
            activeDivisionId = cmd.substring("OPEN_DIV:".length());
            selectedOverviewDivisionId = activeDivisionId;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("DELETE_DIV:")) {
            String delId = cmd.substring("DELETE_DIV:".length());
            PermanentDivisionSpec delSpec = PermanentDivisionManager.getDivisionById(delId);
            String divName = delSpec != null ? delSpec.name : delId;
            pendingConfirmAction = "EXEC_DELETE_DIV:" + delId;
            pendingConfirmTitle = "DELETE DIVISION - ARE YOU SURE?";
            pendingConfirmMessage = "Are you sure you want to delete division \"" + divName + "\"? Its ships will return to the unassigned fleet pool.";
            pendingConfirmYesLabel = "Yes, Delete Division";
            rebuildContent();
            return;
        }
        if (cmd.startsWith("EXEC_DELETE_DIV:")) {
            String delId = cmd.substring("EXEC_DELETE_DIV:".length());
            PermanentDivisionManager.deleteDivision(delId);
            if (delId.equals(activeDivisionId)) {
                activeDivisionId = null;
            }
            if (delId.equals(selectedOverviewDivisionId)) {
                selectedOverviewDivisionId = null;
            }
            rebuildContent();
            return;
        }
        if (cmd.startsWith("NEW_DIV_WITH:")) {
            String mId = cmd.substring("NEW_DIV_WITH:".length());
            DivisionArchetype arch = DivisionArchetype.FORCE_DIVISION;
            CampaignFleetAPI fleet = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
            if (fleet != null && fleet.getFleetData() != null) {
                for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
                    if (mId.equals(m.getId())) {
                        DivisionArchetype classified = PermanentDivisionManager.classifyFleetMemberArchetype(
                                m,
                                PermanentDivisionManager.getFleetDoctrine());
                        arch = (classified != null) ? classified : DivisionArchetype.FORCE_DIVISION;
                        break;
                    }
                }
            }
            PermanentDivisionSpec created = PermanentDivisionManager.createNewDivision(arch, mId);
            if (created != null) {
                selectedOverviewDivisionId = created.id;
            }
            rebuildContent();
            return;
        }
        if (cmd.startsWith("ADD_SHIP:") && activeDivisionId != null) {
            savePendingNameFieldIfAny();
            String mId = cmd.substring("ADD_SHIP:".length());
            PermanentDivisionManager.assignShipToDivision(mId, activeDivisionId);
            rebuildContent();
            return;
        }
        if (cmd.startsWith("REMOVE_SHIP:") && activeDivisionId != null) {
            savePendingNameFieldIfAny();
            String mId = cmd.substring("REMOVE_SHIP:".length());
            PermanentDivisionManager.removeShipFromDivision(mId, activeDivisionId);
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_FLAGSHIP:") && activeDivisionId != null) {
            savePendingNameFieldIfAny();
            String mId = cmd.substring("SET_FLAGSHIP:".length());
            PermanentDivisionManager.setDivisionFlagship(activeDivisionId, mId);
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_ARCH:") && activeDivisionId != null) {
            savePendingNameFieldIfAny();
            String archName = cmd.substring("SET_ARCH:".length());
            try {
                DivisionArchetype arch = DivisionArchetype.valueOf(archName);
                PermanentDivisionManager.updateDivisionMetadata(activeDivisionId, null, arch, null);
            } catch (IllegalArgumentException ignored) {
            }
            expandedDropdownKey = null;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_OTC_AUTH:")) {
            savePendingNameFieldIfAny();
            String authName = cmd.substring("SET_OTC_AUTH:".length());
            OtcAuthority auth = OtcAuthority.fromId(authName, null);
            if (auth != null) {
                PermanentDivisionManager.updateOtcAuthority(auth);
            }
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_DOC:") && activeDivisionId != null) {
            savePendingNameFieldIfAny();
            String docName = cmd.substring("SET_DOC:".length());
            DoctrineOption doc = DoctrineOption.fromId(docName);
            PermanentDivisionManager.updateDivisionMetadata(activeDivisionId, null, null, doc);
            rebuildContent();
        }
    }

    @Override

    public void render(float alphaMult) {
        if (closed || !isDraggingShip || pressedTile == null || pressedTile.member == null) return;

        FleetMemberAPI m = pressedTile.member;
        ShipIconTile hoveredShipTile = findShipTileAt(mouseX, mouseY);
        DropTargetZone hoveredZone = findDropZoneAt(mouseX, mouseY);

        float ghostW = 96f;
        float ghostH = 78f;
        float gx = mouseX - ghostW * 0.5f;
        float gy = mouseY - ghostH * 0.5f;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL11.glColor4f(0.06f, 0.16f, 0.24f, 0.90f * alphaMult);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(gx, gy);
        GL11.glVertex2f(gx + ghostW, gy);
        GL11.glVertex2f(gx + ghostW, gy + ghostH);
        GL11.glVertex2f(gx, gy + ghostH);
        GL11.glEnd();

        GL11.glLineWidth(2.2f);
        if (hoveredZone != null) {
            GL11.glColor4f(0.35f, 1.00f, 0.50f, 0.98f * alphaMult);
        } else {
            GL11.glColor4f(1.00f, 0.85f, 0.25f, 0.95f * alphaMult);
        }
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(gx, gy);
        GL11.glVertex2f(gx + ghostW, gy);
        GL11.glVertex2f(gx + ghostW, gy + ghostH);
        GL11.glVertex2f(gx, gy + ghostH);
        GL11.glEnd();
        GL11.glPopAttrib();

        renderShipMemberSprite(m, mouseX, mouseY + 4f, ghostW - 14f, ghostH - 24f, 0.95f * alphaMult);

        String actionDesc;
        if (hoveredShipTile != null
                && hoveredShipTile.member != null
                && !hoveredShipTile.member.getId().equals(m.getId())
                && hoveredShipTile.sourceDivisionId != null
                && hoveredShipTile.sourceDivisionId.equals(pressedTile.sourceDivisionId)) {
            actionDesc = hoveredShipTile.isFlagship
                    ? ("Drop -> Set Division Leader (" + m.getHullSpec().getHullName() + ")")
                    : ("Drop -> Swap with " + hoveredShipTile.member.getHullSpec().getHullName());
        } else if (hoveredZone != null) {
            actionDesc = (hoveredZone.targetDivisionId == null)
                    ? ("Drop -> Unassign " + m.getHullSpec().getHullName())
                    : ("Drop -> Assign to " + hoveredZone.label);
        } else {
            actionDesc = "Dragging " + m.getHullSpec().getHullName();
        }

        if (dragFloatingLabel == null || !actionDesc.equals(dragFloatingLabel.getText())) {
            dragFloatingLabel = Global.getSettings().createLabel(actionDesc, Fonts.DEFAULT_SMALL);
            dragFloatingLabel.setColor(hoveredZone != null ? Misc.getPositiveHighlightColor() : Misc.getHighlightColor());
        }
        float tw = dragFloatingLabel.computeTextWidth(actionDesc);
        dragFloatingLabel.getPosition().setSize(tw + 10f, 18f);
        dragFloatingLabel.getPosition().setLocation(mouseX - tw * 0.5f, gy - 18f);
        dragFloatingLabel.render(alphaMult);
    }

    @Override
    public void processInput(List<InputEventAPI> events) {
        if (events == null || closed) return;
        for (InputEventAPI event : events) {
            if (event.isConsumed()) continue;

            if (event.isMouseEvent()) {
                mouseX = event.getX();
                mouseY = event.getY();
            }

            if (event.isKeyDownEvent() && event.getEventValue() == Keyboard.KEY_ESCAPE) {
                event.consume();
                if (pendingConfirmAction != null) {
                    pendingConfirmAction = null;
                    pendingConfirmTitle = null;
                    pendingConfirmMessage = null;
                    pendingConfirmYesLabel = null;
                    rebuildContent();
                    return;
                }
                if (isDraggingShip) {
                    isDraggingShip = false;
                    pressedTile = null;
                    return;
                }
                if (activeDivisionId != null) {
                    savePendingNameFieldIfAny();
                    activeDivisionId = null;
                    rebuildContent();
                } else {
                    close();
                }
                return;
            }
            if (event.isKeyDownEvent() && event.getEventValue() == Keyboard.KEY_RETURN && activeNameField != null) {
                savePendingNameFieldIfAny();
            }

            // Right-Click on a Ship Icon Tile -> Quick Assign / Unassign
            if (event.isRMBDownEvent()) {
                float ex = event.getX();
                float ey = event.getY();
                ShipIconTile rmbTile = findShipTileAt(ex, ey);
                if (rmbTile != null && rmbTile.member != null) {
                    event.consume();
                    handleShipTileRightClick(rmbTile);
                    return;
                }
            }

            // Left-Mouse Down -> Start potential drag on a Ship Icon Tile, or select a Division Card
            if (event.isLMBDownEvent()) {
                float ex = event.getX();
                float ey = event.getY();
                if (activeDivisionId == null) {
                    for (DivisionUiWidgets.OtcPennantBox pTile : otcPennantTiles) {
                        if (pTile != null && pTile.contains(ex, ey)) {
                            event.consume();
                            try {
                                Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f);
                            } catch (Throwable ignored) {
                            }
                            handleAction("SET_OTC_AUTH:" + pTile.authority.name());
                            return;
                        }
                    }
                }
                ShipIconTile lmbTile = findShipTileAt(ex, ey);
                if (lmbTile != null && lmbTile.member != null) {
                    pressedTile = lmbTile;
                    pressStartX = ex;
                    pressStartY = ey;
                    isDraggingShip = false;
                    event.consume();
                    continue;
                }

                // On Screen 1 (Overview), clicking inside a Division Card (outside its buttons) selects that division
                if (activeDivisionId == null && !isPointOverAnyButton(ex, ey)) {
                    DropTargetZone clickedZone = findDropZoneAt(ex, ey);
                    if (clickedZone != null && clickedZone.targetDivisionId != null) {
                        event.consume();
                        handleAction("SELECT_DIV:" + clickedZone.targetDivisionId);
                        return;
                    }
                }
            }

            // Mouse Move while holding a Ship Icon Tile -> Transition to active Drag-and-Drop
            if (event.isMouseMoveEvent() && pressedTile != null) {
                float ex = event.getX();
                float ey = event.getY();
                float dx = ex - pressStartX;
                float dy = ey - pressStartY;
                if ((dx * dx + dy * dy) >= (DRAG_START_THRESHOLD_PX * DRAG_START_THRESHOLD_PX)) {
                    isDraggingShip = true;
                }
                event.consume();
                continue;
            }

            // Left-Mouse Up -> Complete Drag-and-Drop OR execute clean Left-Click on Ship Icon Tile
            if (event.isLMBUpEvent() && pressedTile != null) {
                float dropX = event.getX();
                float dropY = event.getY();
                ShipIconTile finishedTile = pressedTile;
                boolean wasDragging = isDraggingShip;
                pressedTile = null;
                isDraggingShip = false;
                event.consume();

                if (wasDragging) {
                    ShipIconTile targetShipTile = findShipTileAt(dropX, dropY);
                    DropTargetZone dropZone = findDropZoneAt(dropX, dropY);
                    if (dropZone != null || targetShipTile != null) {
                        handleShipTileDrop(finishedTile, dropZone, targetShipTile);
                    }
                } else {
                    handleShipTileLeftClick(finishedTile);
                }
                return;
            }

            // Consume all remaining mouse/key events so clicks don't leak through to the Fleet screen behind the modal
            if (!event.isMouseUpEvent()) {
                event.consume();
            }
        }
    }

    private void handleShipTileLeftClick(ShipIconTile tile) {
        if (tile == null || tile.member == null) return;
        savePendingNameFieldIfAny();
        String memberId = tile.member.getId();

        if (activeDivisionId != null) {
            // Deep Division Editor (Screen 2)
            if (activeDivisionId.equals(tile.sourceDivisionId)) {
                // Left-clicking a ship inside this division designates it as Flagship
                PermanentDivisionManager.setDivisionFlagship(activeDivisionId, memberId);
                rebuildContent();
            } else {
                // Left-clicking a ship in Unassigned / Other Division pool assigns it to activeDivisionId
                PermanentDivisionManager.assignShipToDivision(memberId, activeDivisionId);
                rebuildContent();
            }
        } else {
            // Overview Screen (Screen 1)
            if (tile.sourceDivisionId != null) {
                // Select that division and designate the clicked ship as its Flagship
                selectedOverviewDivisionId = tile.sourceDivisionId;
                PermanentDivisionManager.setDivisionFlagship(tile.sourceDivisionId, memberId);
                rebuildContent();
            } else if (selectedOverviewDivisionId != null) {
                // Left-clicking an unassigned ship assigns it to the currently selected division
                PermanentDivisionManager.assignShipToDivision(memberId, selectedOverviewDivisionId);
                rebuildContent();
            }
        }
    }

    private void handleShipTileRightClick(ShipIconTile tile) {
        if (tile == null || tile.member == null) return;
        savePendingNameFieldIfAny();
        String memberId = tile.member.getId();

        if (activeDivisionId != null) {
            // Deep Division Editor (Screen 2)
            if (activeDivisionId.equals(tile.sourceDivisionId)) {
                // Right-clicking a ship inside this division unassigns it back to the Fleet Pool
                PermanentDivisionManager.removeShipFromDivision(memberId, activeDivisionId);
            } else {
                // Right-clicking a ship in Unassigned / Other Division assigns it to this division
                PermanentDivisionManager.assignShipToDivision(memberId, activeDivisionId);
            }
            rebuildContent();
        } else {
            // Overview Screen (Screen 1)
            if (tile.sourceDivisionId != null) {
                // Right-clicking a ship inside any division unassigns it back to the Fleet Pool
                selectedOverviewDivisionId = tile.sourceDivisionId;
                PermanentDivisionManager.removeShipFromDivision(memberId, tile.sourceDivisionId);
                rebuildContent();
            } else {
                // Right-clicking an unassigned ship assigns it to the currently selected division
                String targetDiv = selectedOverviewDivisionId;
                if (targetDiv == null) {
                    List<PermanentDivisionSpec> divs = PermanentDivisionManager.getPermanentDivisions();
                    if (!divs.isEmpty()) {
                        targetDiv = divs.get(0).id;
                        selectedOverviewDivisionId = targetDiv;
                    }
                }
                if (targetDiv != null) {
                    PermanentDivisionManager.assignShipToDivision(memberId, targetDiv);
                    rebuildContent();
                }
            }
        }
    }

    private void handleShipTileDrop(ShipIconTile tile, DropTargetZone dropZone, ShipIconTile targetShipTile) {
        if (tile == null || tile.member == null) return;
        if (dropZone == null && targetShipTile == null) return;
        savePendingNameFieldIfAny();
        String memberId = tile.member.getId();
        String targetDivId = (dropZone != null)
                ? dropZone.targetDivisionId
                : (targetShipTile != null ? targetShipTile.sourceDivisionId : null);

        if (targetDivId == null) {
            // Dropped onto the Unassigned Fleet Pool -> Unassign from source division
            if (tile.sourceDivisionId != null) {
                PermanentDivisionManager.removeShipFromDivision(memberId, tile.sourceDivisionId);
                rebuildContent();
            }
        } else {
            // Dropped onto a Division Card / Division Bay -> Assign, transfer, or swap within the same division
            selectedOverviewDivisionId = targetDivId;
            if (!targetDivId.equals(tile.sourceDivisionId)) {
                PermanentDivisionManager.assignShipToDivision(memberId, targetDivId);
                if (targetShipTile != null
                        && targetShipTile.member != null
                        && targetDivId.equals(targetShipTile.sourceDivisionId)) {
                    PermanentDivisionManager.swapOrReorderShipsInDivision(
                            targetDivId,
                            memberId,
                            targetShipTile.member.getId());
                }
                rebuildContent();
            } else if (targetShipTile != null
                    && targetShipTile.member != null
                    && !memberId.equals(targetShipTile.member.getId())
                    && targetDivId.equals(targetShipTile.sourceDivisionId)) {
                PermanentDivisionManager.swapOrReorderShipsInDivision(
                        targetDivId,
                        memberId,
                        targetShipTile.member.getId());
                rebuildContent();
            }
        }
    }

    private ShipIconTile findShipTileAt(float x, float y) {
        for (int i = shipIconTiles.size() - 1; i >= 0; i--) {
            ShipIconTile tile = shipIconTiles.get(i);
            if (tile.tilePanel == null) continue;
            if (tile.clipContainer != null && !isPointInsidePanel(x, y, tile.clipContainer)) {
                continue;
            }
            if (isPointInside(x, y, tile.tilePanel.getPosition())) {
                return tile;
            }
        }
        return null;
    }

    private DropTargetZone findDropZoneAt(float x, float y) {
        for (int i = dropTargetZones.size() - 1; i >= 0; i--) {
            DropTargetZone zone = dropTargetZones.get(i);
            if (zone.panel == null) continue;
            if (zone.clipContainer != null && !isPointInsidePanel(x, y, zone.clipContainer)) {
                continue;
            }
            if (isPointInsidePanel(x, y, zone.panel)) {
                return zone;
            }
        }
        return null;
    }

    static void restoreScrollOffset(
            TooltipMakerAPI col,
            ScrollPanelAPI scroller,
            float viewportHeight,
            float savedYOffset) {
        if (col == null || scroller == null || savedYOffset <= 0f) return;
        float contentH = col.getHeightSoFar();
        if (col.getPosition() != null && col.getPosition().getHeight() > contentH) {
            contentH = col.getPosition().getHeight();
        }
        float maxScroll = Math.max(0f, contentH - viewportHeight);
        if (maxScroll > 0f) {
            scroller.setYOffset(Math.max(0f, Math.min(savedYOffset, maxScroll)));
        }
    }

    private static boolean isPointInsidePanel(float x, float y, UIPanelAPI panel) {
        if (panel == null) return false;
        if (panel instanceof TooltipMakerAPI) {
            ScrollPanelAPI scroller = ((TooltipMakerAPI) panel).getExternalScroller();
            if (scroller != null && scroller.getPosition() != null) {
                return isPointInside(x, y, scroller.getPosition());
            }
        }
        return isPointInside(x, y, panel.getPosition());
    }

    private boolean isPointOverAnyButton(float x, float y) {
        for (ButtonAPI btn : activeButtons.keySet()) {
            if (btn != null && isPointInside(x, y, btn.getPosition())) {
                return true;
            }
        }
        for (ButtonAPI cb : activeCheckboxes.keySet()) {
            if (cb != null && isPointInside(x, y, cb.getPosition())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPointInside(float x, float y, PositionAPI pos) {
        if (pos == null) return false;
        float px = pos.getX();
        float py = pos.getY();
        float pw = pos.getWidth();
        float ph = pos.getHeight();
        return x >= px && x <= (px + pw) && y >= py && y <= (py + ph);
    }

    private static String truncateText(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, Math.max(1, maxLen - 2)) + "..";
    }
    final DivisionUiWidgets.ButtonRegistrar buttonRegistrar = new DivisionUiWidgets.ButtonRegistrar() {
        @Override
        public ButtonAPI register(ButtonAPI button, String actionId) {
            return registerButton(button, actionId);
        }
    };

    final DivisionUiWidgets.ButtonPressRelay buttonPressRelay = new DivisionUiWidgets.ButtonPressRelay() {
        @Override
        public void onButtonPressed(Object buttonId) {
            DivisionEditorDialog.this.buttonPressed(buttonId);
        }
    };


    @Override
    public void renderBelow(float alphaMult) {
        if (rootModalPanel == null || contentContainer == null) return;
        DivisionUiWidgets.renderModalDialogBackdrop(
                contentContainer, 0.0f, 0.0f, 0.0f, 0.72f, 0.04f, 0.06f, 0.09f, 0.90f, alphaMult);
    }
}
