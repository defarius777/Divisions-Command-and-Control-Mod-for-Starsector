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
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.DoctrineOption;
import divisionai.PermanentDivisionManager.FleetDoctrineConfig;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import divisionai.PermanentDivisionManager.LineStation;
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

    private static final float DIALOG_WIDTH = 1040f;
    private static final float DIALOG_HEIGHT = 680f;
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
    private TextFieldAPI activeNameField;

    // When null -> Screen 1 (Fleet Divisions Overview); when non-null -> Screen 2 (Dedicated Division Screen)
    private String activeDivisionId = null;
    // Selected division on Screen 1 (Overview) for quick right-click ship assignment
    private String selectedOverviewDivisionId = null;
    private String expandedDropdownKey = null;
    private boolean closed = false;

    private final Map<ButtonAPI, String> activeButtons = new LinkedHashMap<>();
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

    private final CustomUIPanelPlugin subPanelPlugin = new BaseCustomUIPanelPlugin() {
        @Override
        public void buttonPressed(Object buttonId) {
            DivisionEditorDialog.this.buttonPressed(buttonId);
        }
    };

    public DivisionEditorDialog(UIPanelAPI hostCoreUI) {
        this.hostCoreUI = hostCoreUI;
    }

    private ButtonAPI registerButton(ButtonAPI button, String actionId) {
        if (button != null && actionId != null) {
            activeButtons.put(button, actionId);
        }
        return button;
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
        shipIconTiles.clear();
        dropTargetZones.clear();
        pressedTile = null;
        isDraggingShip = false;
        if (hostCoreUI != null && rootModalPanel != null) {
            hostCoreUI.removeComponent(rootModalPanel);
        }
        rootModalPanel = null;
        contentContainer = null;
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

        if (pendingAction != null) {
            String cmd = pendingAction;
            pendingAction = null;
            handleAction(cmd);
        }
    }

    private void rebuildContent() {
        if (rootModalPanel == null) return;
        activeButtons.clear();
        shipIconTiles.clear();
        dropTargetZones.clear();
        pressedTile = null;
        isDraggingShip = false;

        if (contentContainer != null) {
            rootModalPanel.removeComponent(contentContainer);
            contentContainer = null;
        }
        activeNameField = null;

        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        float boxX = Math.max(20f, (screenW - DIALOG_WIDTH) * 0.5f);
        float boxYTop = Math.max(20f, (screenH - DIALOG_HEIGHT) * 0.5f);

        contentContainer = Global.getSettings().createCustom(DIALOG_WIDTH, DIALOG_HEIGHT, subPanelPlugin);
        rootModalPanel.addComponent(contentContainer).inTL(boxX, boxYTop);

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
        header.addTitle("FLEET DIVISION EDITOR — PERMANENT C2 ORGANIZATION", baseColor);
        header.addPara(
                "Selected Division: %s  |  Drag & Drop ship icons between divisions and fleet  |  Right-Click ship: Assign / Unassign  |  Left-Click ship in division: Set Flagship",
                4f,
                highlight,
                selectedDivName);
        header.setHeightSoFar(52f);
        container.addUIElement(header).inTL(12f, 6f);

        CustomPanelAPI toolbarRow = Global.getSettings().createCustom(DIALOG_WIDTH - 24f, 28f, subPanelPlugin);
        TooltipMakerAPI tb = toolbarRow.createUIElement(DIALOG_WIDTH - 24f, 28f, false);
        ButtonAPI bCreate = registerButton(
                tb.addButton(
                        "+ Establish New Division",
                        "CREATE_DIV_OVERVIEW",
                        positive,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        210f,
                        24f,
                        0f),
                "CREATE_DIV_OVERVIEW");
        bCreate.getPosition().inTL(0f, 2f);

        ButtonAPI bAutoAssign = registerButton(
                tb.addButton(
                        "Auto-Assign Fleet Ships (" + unassigned.size() + ")",
                        "AUTO_ASSIGN",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        240f,
                        24f,
                        0f),
                "AUTO_ASSIGN");
        bAutoAssign.setEnabled(!unassigned.isEmpty());
        bAutoAssign.getPosition().inTL(218f, 2f);

        if (selectedSpec != null) {
            String openSelectedAct = "OPEN_DIV:" + selectedSpec.id;
            ButtonAPI bOpenSel = registerButton(
                    tb.addButton(
                            "Deep Editor: " + truncateText(selectedSpec.name, 16) + " ->",
                            openSelectedAct,
                            highlight,
                            darkColor,
                            Alignment.MID,
                            CutStyle.TL_BR,
                            215f,
                            24f,
                            0f),
                    openSelectedAct);
            bOpenSel.getPosition().inTL(466f, 2f);
        }

        if (!divisions.isEmpty()) {
            ButtonAPI bClearAll = registerButton(
                    tb.addButton(
                            "Disestablish All",
                            "CLEAR_ALL_DIVS",
                            Misc.getNegativeHighlightColor(),
                            darkColor,
                            Alignment.MID,
                            CutStyle.TL_BR,
                            150f,
                            24f,
                            0f),
                    "CLEAR_ALL_DIVS");
            bClearAll.getPosition().inTL(689f, 2f);
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

        // 2. Left Column: Permanent Divisions Scrollable List (width 625f)
        float bodyHeight = DIALOG_HEIGHT - 104f;
        float leftWidth = 625f;
        float rightWidth = DIALOG_WIDTH - leftWidth - 36f;

        TooltipMakerAPI leftCol = container.createUIElement(leftWidth, bodyHeight, true);
        leftCol.addSectionHeading(
                "Permanent Divisions (" + divisions.size() + " Active | " + assignedCount + " Ships Assigned)",
                Alignment.MID,
                4f);

        if (divisions.isEmpty()) {
            leftCol.addPara(
                    "No permanent divisions configured. Click '+ Establish New Division' or 'Auto-Assign Fleet Ships' above, or click '+ New Div' on a ship on the right.",
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

        // 3. Right Column: Unassigned Combat Hulls Pool (DropTargetZone with targetDivisionId == null)
        TooltipMakerAPI rightCol = container.createUIElement(rightWidth, bodyHeight, true);
        String poolHeading = selectedSpec != null
                ? ("Unassigned Fleet Ships (" + unassigned.size() + ") — Right-Click -> " + truncateText(selectedSpec.name, 14))
                : ("Unassigned Fleet Ships (" + unassigned.size() + ")");
        rightCol.addSectionHeading(poolHeading, Alignment.MID, 4f);

        if (unassigned.isEmpty()) {
            rightCol.addPara(
                    "All eligible combat hulls in your fleet are assigned to permanent divisions.",
                    positive,
                    8f);
            rightCol.addPara(
                    "Drag any ship icon from a division on the left and drop it here (or Right-Click it) to unassign it back to the fleet pool.",
                    gray,
                    6f);
        } else {
            rightCol.addPara(
                    "Drag ship icons onto any division on the left, or Right-Click a ship icon to assign it to the selected division:",
                    gray,
                    3f);
            addInteractiveShipGrid(
                    rightCol,
                    rightWidth - 16f,
                    unassigned,
                    null,
                    null,
                    4,
                    true);
        }
        container.addUIElement(rightCol).inTL(leftWidth + 24f, 94f);
        dropTargetZones.add(new DropTargetZone(null, "Unassigned Fleet Pool", rightCol, null));
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

        int cols = 6;
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
        String selectLabel = (isSelected ? "[SELECTED] " : "[Select] ")
                + spec.name + " — " + spec.archetype.shortPrefix
                + String.format(Locale.ROOT, " (%d/%d | %.0f DP)", members.size(), PermanentDivisionManager.MAX_DIVISION_SIZE, totalDp);
        ButtonAPI bSelect = registerButton(
                topUi.addButton(
                        selectLabel,
                        selectAct,
                        isSelected ? highlight : baseColor,
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        cardWidth - 268f,
                        22f,
                        0f),
                selectAct);
        if (isSelected) bSelect.highlight();
        bSelect.getPosition().inTL(4f, 4f);

        String openAct = "OPEN_DIV:" + spec.id;
        ButtonAPI bOpen = registerButton(
                topUi.addButton(
                        "Deep Editor ->",
                        openAct,
                        positive,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        136f,
                        22f,
                        0f),
                openAct);
        bOpen.getPosition().inTL(cardWidth - 260f, 4f);

        String delAct = "DELETE_DIV:" + spec.id;
        ButtonAPI bDel = registerButton(
                topUi.addButton(
                        "Disestablish",
                        delAct,
                        Misc.getNegativeHighlightColor(),
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        112f,
                        22f,
                        0f),
                delAct);
        bDel.getPosition().inTL(cardWidth - 118f, 4f);

        int effIntra = PermanentDivisionManager.resolveEffectiveStationSpacing(spec, doctrineCfg);
        int effStandoff = PermanentDivisionManager.resolveEffectiveStandoffRange(spec, doctrineCfg);
        LineStation effSpace = PermanentDivisionManager.resolveEffectiveLineStation(spec, doctrineCfg);
        DivisionMissionType effOpen = PermanentDivisionManager.resolveEffectiveOpeningMission(spec, doctrineCfg);
        DivisionMissionType effMain = PermanentDivisionManager.resolveEffectiveMainMission(spec, doctrineCfg);

        PersonAPI cap = (flagship != null) ? flagship.getCaptain() : null;
        String cmdrDesc = (cap != null && !cap.isDefault())
                ? (cap.getNameString() + " [" + cap.getPersonalityAPI().getDisplayName() + "]")
                : "Default Cmdr";

        LabelAPI sub1 = topUi.addPara(
                "Cmdr: %s  |  Station: %s  |  Spacing: %su  |  Standoff: %su  |  %s -> %s",
                2f,
                isSelected ? positive : gray,
                cmdrDesc,
                effSpace.displayName,
                String.valueOf(effIntra),
                String.valueOf(effStandoff),
                effOpen.shortName,
                effMain.shortName);
        sub1.getPosition().inTL(6f, 30f);

        topUi.setHeightSoFar(56f);
        cardPanel.addUIElement(topUi).inTL(2f, 2f);

        // Ship Icon Grid inside card
        if (members.isEmpty()) {
            TooltipMakerAPI emptyUi = cardPanel.createUIElement(cardWidth - 16f, 44f, false);
            emptyUi.addPara(
                    isSelected
                            ? "Empty Division — Drag ships here or Right-Click ships in the Unassigned Fleet Pool to assign."
                            : "Empty Division — Left-click this division to select it, or drag ships here.",
                    gray,
                    10f);
            emptyUi.setHeightSoFar(44f);
            cardPanel.addUIElement(emptyUi).inTL(8f, 60f);
        } else {
            float tileW = 94f;
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

    // ========================================================================================
    // SCREEN 2: DEDICATED INDIVIDUAL DIVISION SCREEN ("wallops into its own screen")
    // ========================================================================================
    private void buildSingleDivisionScreen(CustomPanelAPI container, PermanentDivisionSpec spec) {
        CampaignFleetAPI fleet = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
        List<PermanentDivisionSpec> allDivs = PermanentDivisionManager.getPermanentDivisions();
        List<FleetMemberAPI> divMembers = PermanentDivisionManager.getMembersInDivision(spec, fleet);
        List<FleetMemberAPI> unassigned = PermanentDivisionManager.getUnassignedCombatMembers(fleet, allDivs);

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

        // 1. Top Control & Doctrine Header (height 212f)
        float headerH = 212f;
        TooltipMakerAPI top = container.createUIElement(DIALOG_WIDTH - 24f, headerH, false);

        // Row A: Back button, Title, Close button
        CustomPanelAPI navRow = Global.getSettings().createCustom(DIALOG_WIDTH - 72f, 28f, subPanelPlugin);
        TooltipMakerAPI navUi = navRow.createUIElement(DIALOG_WIDTH - 72f, 28f, false);
        ButtonAPI bBack = registerButton(
                navUi.addButton(
                        "<- Back to All Divisions",
                        "BACK_TO_LIST",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        195f,
                        24f,
                        0f),
                "BACK_TO_LIST");
        bBack.getPosition().inTL(0f, 2f);

        ButtonAPI bClose = registerButton(
                navUi.addButton(
                        "Close Editor [Esc]",
                        "CLOSE",
                        Misc.getNegativeHighlightColor(),
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        150f,
                        24f,
                        0f),
                "CLOSE");
        bClose.getPosition().inTL(DIALOG_WIDTH - 72f - 150f, 2f);
        navUi.setHeightSoFar(28f);
        navRow.addUIElement(navUi).inTL(0f, 0f);
        top.addCustom(navRow, 2f);

        top.setTitleOrbitronLarge();
        top.addTitle("DIVISION COMMAND & DOCTRINE: " + spec.name.toUpperCase(), highlight);

        // Row B: Editable Callsign TextField + Apply Name Button
        CustomPanelAPI nameRow = Global.getSettings().createCustom(DIALOG_WIDTH - 24f, 30f, subPanelPlugin);
        TooltipMakerAPI nameUi = nameRow.createUIElement(DIALOG_WIDTH - 24f, 30f, false);
        activeNameField = nameUi.addTextField(260f, Fonts.DEFAULT_SMALL, 0f);
        activeNameField.setText(spec.name);
        activeNameField.setMaxChars(32);
        activeNameField.getPosition().inTL(0f, 3f);

        ButtonAPI bSaveName = registerButton(
                nameUi.addButton(
                        "Apply Division Name",
                        "APPLY_NAME",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        165f,
                        24f,
                        0f),
                "APPLY_NAME");
        bSaveName.getPosition().inTL(270f, 3f);
        nameUi.setHeightSoFar(30f);
        nameRow.addUIElement(nameUi).inTL(0f, 0f);
        top.addCustom(nameRow, 4f);

        // Row C: Division Archetype Selector (2 compact rows of 6 buttons covering Combat & Carrier Divisions)
        DivisionArchetype[] archetypes = new DivisionArchetype[]{
                DivisionArchetype.BATTLESHIP_DIVISION,
                DivisionArchetype.CRUISER_DIVISION,
                DivisionArchetype.STRIKE_CRUISER_DIVISION,
                DivisionArchetype.DESTROYER_DIVISION,
                DivisionArchetype.MISSILE_DESTROYER_DIVISION,
                DivisionArchetype.FRIGATE_DIVISION,
                DivisionArchetype.FLEET_CARRIER_DIVISION,
                DivisionArchetype.LIGHT_CARRIER_DIVISION,
                DivisionArchetype.ESCORT_CARRIER_DIVISION,
                DivisionArchetype.CARRIER_GROUP,
                DivisionArchetype.PHASE_DIVISION,
                DivisionArchetype.SERVICE_GROUP
        };
        CustomPanelAPI archRow = Global.getSettings().createCustom(DIALOG_WIDTH - 24f, 50f, subPanelPlugin);
        TooltipMakerAPI archUi = archRow.createUIElement(DIALOG_WIDTH - 24f, 50f, false);
        for (int i = 0; i < archetypes.length; i++) {
            DivisionArchetype arch = archetypes[i];
            int colIdx = i % 6;
            int rowIdx = i / 6;
            float ax = colIdx * 159f;
            float ay = 2f + rowIdx * 24f;
            boolean selected = (spec.archetype == arch);
            Color fg = selected ? highlight : baseColor;
            Color battleGroup = selected ? baseColor.darker() : darkColor;
            String archAct = "SET_ARCH:" + arch.name();
            ButtonAPI bArch = registerButton(
                    archUi.addButton(
                            arch.shortPrefix,
                            archAct,
                            fg,
                            battleGroup,
                            Alignment.MID,
                            CutStyle.TL_BR,
                            154f,
                            21f,
                            0f),
                    archAct);
            if (selected) bArch.highlight();
            bArch.getPosition().inTL(ax, ay);
        }
        archUi.setHeightSoFar(50f);
        archRow.addUIElement(archUi).inTL(0f, 0f);
        top.addCustom(archRow, 3f);

        // Row D: Division Doctrine Selector (Foundation for Customizable Doctrine)
        DoctrineOption[] doctrines = DoctrineOption.values();
        CustomPanelAPI docRow = Global.getSettings().createCustom(DIALOG_WIDTH - 24f, 26f, subPanelPlugin);
        TooltipMakerAPI docUi = docRow.createUIElement(DIALOG_WIDTH - 24f, 26f, false);
        float dx = 0f;
        for (DoctrineOption doc : doctrines) {
            boolean selected = (spec.doctrine == doc);
            Color fg = selected ? positive : baseColor;
            Color battleGroup = selected ? baseColor.darker() : darkColor;
            String docAct = "SET_DOC:" + doc.name();
            ButtonAPI bDoc = registerButton(
                    docUi.addButton(
                            doc.displayName,
                            docAct,
                            fg,
                            battleGroup,
                            Alignment.MID,
                            CutStyle.TL_BR,
                            154f,
                            22f,
                            0f),
                    docAct);
            bDoc.getPosition().inTL(dx, 2f);
            dx += 159f;
        }
        docUi.setHeightSoFar(26f);
        docRow.addUIElement(docUi).inTL(0f, 0f);
        top.addCustom(docRow, 3f);

        top.addPara(
                "Active Doctrine — %s: %s",
                3f,
                positive,
                spec.doctrine.displayName,
                spec.doctrine.summary);

        top.setHeightSoFar(headerH);
        container.addUIElement(top).inTL(12f, 10f);

        // 2. Two-Column Ship Assignment Body (height = DIALOG_HEIGHT - 234f)
        float bodyTop = 224f;
        float bodyHeight = DIALOG_HEIGHT - bodyTop - 12f;
        float colWidth = (DIALOG_WIDTH - 36f) * 0.5f;

        boolean divisionAtCap = divMembers.size() >= PermanentDivisionManager.MAX_DIVISION_SIZE;

        // Left Column: Division Settings (Inherited / Auto-Updated / Manually Set) + Assigned Ships
        TooltipMakerAPI leftCol = container.createUIElement(colWidth, bodyHeight, true);

        FleetDoctrineConfig doctrineCfg = PermanentDivisionManager.getFleetDoctrine();
        int effIntra = PermanentDivisionManager.resolveEffectiveStationSpacing(spec, doctrineCfg);
        int effInter = PermanentDivisionManager.resolveEffectiveDivisionInterval(spec, doctrineCfg);
        int effStandoff = PermanentDivisionManager.resolveEffectiveStandoffRange(spec, doctrineCfg);
        int longestGunRange = PermanentDivisionManager.computeDivisionLongestGunRange(spec, fleet);
        LineStation effPrefSpace = PermanentDivisionManager.resolveEffectiveLineStation(spec, doctrineCfg);
        DivisionMissionType effOpenMis = PermanentDivisionManager.resolveEffectiveOpeningMission(spec, doctrineCfg);
        DivisionMissionType effMainMis = PermanentDivisionManager.resolveEffectiveMainMission(spec, doctrineCfg);

        leftCol.addSectionHeading(
                "Division Settings (Paragon Length = " + PermanentDivisionManager.PARAGON_LENGTH_UNITS + "u)",
                highlight,
                darkColor,
                Alignment.LMID,
                2f);

        // 1. Intra-division spacing row
        addDivisionSpacingRow(
                leftCol,
                colWidth - 16f,
                String.format(
                        Locale.ROOT,
                        "Station spacing: %du (%.2fx Paragon) [%s]",
                        effIntra,
                        effIntra / (float) PermanentDivisionManager.PARAGON_LENGTH_UNITS,
                        spec.customStationSpacing != null ? "Custom" : "Inherited"),
                effIntra,
                50,
                PermanentDivisionManager.MIN_STATION_SPACING_UNITS,
                PermanentDivisionManager.MAX_STATION_SPACING_UNITS,
                "DIV_STATION_SPACING:",
                spec.customStationSpacing != null,
                baseColor,
                darkColor,
                positive);

        // 2. Inter-division spacing row
        addDivisionSpacingRow(
                leftCol,
                colWidth - 16f,
                String.format(
                        Locale.ROOT,
                        "Division interval: %du (%.2fx Paragon) [%s]",
                        effInter,
                        effInter / (float) PermanentDivisionManager.PARAGON_LENGTH_UNITS,
                        spec.customDivisionInterval != null ? "Custom" : "Inherited"),
                effInter,
                100,
                PermanentDivisionManager.MIN_DIVISION_INTERVAL_UNITS,
                PermanentDivisionManager.MAX_DIVISION_INTERVAL_UNITS,
                "DIV_DIVISION_INTERVAL:",
                spec.customDivisionInterval != null,
                baseColor,
                darkColor,
                positive);

        // 3. Line Standoff distance row (with Auto-Set from Longest Firepower button)
        addDivisionStandoffRow(
                leftCol,
                colWidth - 16f,
                effStandoff,
                longestGunRange,
                spec.customStandoffRange != null,
                baseColor,
                darkColor,
                highlight,
                positive);

        // 4. Preferred Space row (Forward, Middle, Back — auto-selected by some missions or manually set)
        addDivisionPreferredSpaceRow(
                leftCol,
                colWidth - 16f,
                effPrefSpace,
                spec.customLineStation != null,
                baseColor,
                darkColor,
                positive);

        // 5. Opening Mission Drop-Down (0 - openingDurationSeconds)
        addDivisionMissionDropdownRow(
                leftCol,
                colWidth - 16f,
                "Opening Mission (0-" + doctrineCfg.getOpeningDurationSeconds() + "s):",
                "DIV_OPEN_DD",
                effOpenMis,
                spec.archetype,
                spec.customOpeningMission != null,
                "DIV_OPEN_MIS:",
                baseColor,
                darkColor,
                positive,
                gray);

        // 6. Main Mission Drop-Down (rest of battle)
        addDivisionMissionDropdownRow(
                leftCol,
                colWidth - 16f,
                "Main Mission (rest of battle):",
                "DIV_MAIN_DD",
                effMainMis,
                spec.archetype,
                spec.customMainMission != null,
                "DIV_MAIN_MIS:",
                baseColor,
                darkColor,
                highlight,
                gray);

        // Visual Ship Icon Bay in Deep Division Editor
        leftCol.addSectionHeading(
                "Ships in " + spec.name + " (" + divMembers.size() + "/" + PermanentDivisionManager.MAX_DIVISION_SIZE + " Max)",
                Alignment.MID,
                8f);
        leftCol.addPara(
                "Left-Click ship icon: Designate Flagship  |  Right-Click or Drag to Right Column: Unassign",
                gray,
                3f);

        if (divMembers.isEmpty()) {
            leftCol.addPara(
                    "No ships in this division. Drag ship icons from the right column and drop them here, or Right-Click any ship on the right.",
                    gray,
                    8f);
        } else {
            String flagId = spec.flagshipMemberId;
            if (flagId == null && !divMembers.isEmpty()) {
                flagId = divMembers.get(0).getId();
            }
            addInteractiveShipGrid(
                    leftCol,
                    colWidth - 16f,
                    divMembers,
                    spec.id,
                    flagId,
                    5,
                    false);
        }

        container.addUIElement(leftCol).inTL(12f, bodyTop);
        dropTargetZones.add(new DropTargetZone(spec.id, spec.name, leftCol, null));

        // Right Column: Visual Ship Icons for Unassigned Ships + Ships in Other Divisions
        TooltipMakerAPI rightCol = container.createUIElement(colWidth, bodyHeight, true);
        rightCol.addSectionHeading(
                "Unassigned Fleet Ships (" + unassigned.size() + ") — Drag or Right-Click to Assign",
                Alignment.MID,
                4f);

        if (unassigned.isEmpty()) {
            rightCol.addPara("No unassigned combat ships in fleet. Drag ships here to unassign them.", gray, 4f);
        } else {
            rightCol.addPara(
                    "Drag any ship icon to the left panel (or Right-Click / Left-Click it) to add to " + spec.name + ":",
                    gray,
                    3f);
            addInteractiveShipGrid(
                    rightCol,
                    colWidth - 16f,
                    unassigned,
                    null,
                    null,
                    5,
                    false);
        }

        rightCol.addSectionHeading("Transfer From Other Divisions (Drag or Right-Click)", Alignment.MID, 10f);
        boolean anyOther = false;
        for (PermanentDivisionSpec other : allDivs) {
            if (other.id.equals(spec.id)) continue;
            List<FleetMemberAPI> otherMembers = PermanentDivisionManager.getMembersInDivision(other, fleet);
            if (otherMembers.isEmpty()) continue;
            anyOther = true;
            rightCol.addPara(
                    other.name + " (" + other.archetype.shortPrefix + ", " + otherMembers.size() + " ships):",
                    highlight,
                    5f);
            addInteractiveShipGrid(
                    rightCol,
                    colWidth - 16f,
                    otherMembers,
                    other.id,
                    other.flagshipMemberId,
                    5,
                    false);
        }
        if (!anyOther) {
            rightCol.addPara("No ships in other divisions.", gray, 4f);
        }

        container.addUIElement(rightCol).inTL(colWidth + 24f, bodyTop);
        dropTargetZones.add(new DropTargetZone(null, "Unassigned Fleet Pool", rightCol, null));
    }

    /**
     * Builds a multi-column grid of interactive ShipIconTile widgets inside a TooltipMakerAPI column.
     */
    private void addInteractiveShipGrid(
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

        final CustomPanelAPI[] panelRef = new CustomPanelAPI[1];
        CustomPanelAPI tilePanel = Global.getSettings().createCustom(width, height, new BaseCustomUIPanelPlugin() {
            @Override
            public void render(float alphaMult) {
                CustomPanelAPI selfPanel = panelRef[0];
                if (selfPanel == null) return;
                PositionAPI p = selfPanel.getPosition();
                if (p == null) return;

                float x = p.getX();
                float y = p.getY();
                float w = p.getWidth();
                float h = p.getHeight();

                boolean insideClip = clipContainer == null || isPointInside(mouseX, mouseY, clipContainer.getPosition());
                boolean hovered = insideClip && isPointInside(mouseX, mouseY, p);
                boolean beingDragged = isDraggingShip && pressedTile != null && pressedTile.member == member;

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

                // Officer pip (top-right corner)
                PersonAPI cap = member.getCaptain();
                if (cap != null && !cap.isDefault()) {
                    GL11.glColor4f(0.30f, 0.88f, 1.00f, 0.95f * alphaMult);
                    GL11.glBegin(GL11.GL_QUADS);
                    GL11.glVertex2f(x + w - 9f, y + h - 9f);
                    GL11.glVertex2f(x + w - 3f, y + h - 9f);
                    GL11.glVertex2f(x + w - 3f, y + h - 3f);
                    GL11.glVertex2f(x + w - 9f, y + h - 3f);
                    GL11.glEnd();
                }

                GL11.glPopAttrib();

                // Render Ship Hull Sprite centered in upper portion of tile
                renderShipMemberSprite(
                        member,
                        x + w * 0.5f,
                        y + h * 0.56f,
                        w - 14f,
                        h - 30f,
                        beingDragged ? 0.45f * alphaMult : alphaMult);
            }
        });
        panelRef[0] = tilePanel;

        // Text overlay labels inside the tile (top badge: FLAG/DP, bottom label: hull name)
        TooltipMakerAPI textUi = tilePanel.createUIElement(width, height, false);
        String topBadge = (isFlagship ? "★FLAG " : "")
                + String.format(Locale.ROOT, "%.0fDP", member.getDeploymentPointsCost());
        LabelAPI topLbl = textUi.addPara(topBadge, isFlagship ? highlight : positive, 0f);
        topLbl.getPosition().inTL(3f, 2f);

        String hullName = member.getHullSpec() != null ? member.getHullSpec().getHullName() : member.getShipName();
        LabelAPI botLbl = textUi.addPara(truncateText(hullName, 11), baseColor, 0f);
        botLbl.getPosition().inTL(3f, height - 17f);

        textUi.setHeightSoFar(height);
        tilePanel.addUIElement(textUi).inTL(0f, 0f);

        shipIconTiles.add(new ShipIconTile(member, sourceDivisionId, isFlagship, tilePanel, clipContainer));
        return tilePanel;
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

            float scale = Math.min(maxW / origW, maxH / origH);
            scale = Math.min(scale, 0.85f);
            float drawW = origW * scale;
            float drawH = origH * scale;

            Color oldColor = sprite.getColor();
            float oldAngle = sprite.getAngle();

            sprite.setSize(drawW, drawH);
            sprite.setAngle(0f);
            sprite.setColor(new Color(255, 255, 255, Math.max(0, Math.min(255, Math.round(alpha * 255f)))));
            sprite.renderAtCenter(centerX, centerY);

            sprite.setSize(origW, origH);
            sprite.setAngle(oldAngle);
            if (oldColor != null) {
                sprite.setColor(oldColor);
            }
        } catch (Throwable ignored) {
        }
    }

    private void addDivisionSpacingRow(
            TooltipMakerAPI col,
            float rowWidth,
            String labelText,
            int currentVal,
            int step,
            int minVal,
            int maxVal,
            String actionPrefix,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color activeColor) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 25f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 25f, false);

        rowUi.addPara(labelText, 3f, isCustom ? activeColor : baseColor, labelText).getPosition().inTL(2f, 4f);

        float x = rowWidth - 196f;
        String decAct = actionPrefix + Math.max(minVal, currentVal - step);
        ButtonAPI bDec = registerButton(
                rowUi.addButton("-" + step + "u", decAct, baseColor, darkColor, Alignment.MID, CutStyle.ALL, 52f, 20f, 0f),
                decAct);
        bDec.setEnabled(currentVal > minVal);
        bDec.getPosition().inTL(x, 2f);

        String incAct = actionPrefix + Math.min(maxVal, currentVal + step);
        ButtonAPI bInc = registerButton(
                rowUi.addButton("+" + step + "u", incAct, baseColor, darkColor, Alignment.MID, CutStyle.ALL, 52f, 20f, 0f),
                incAct);
        bInc.setEnabled(currentVal < maxVal);
        bInc.getPosition().inTL(x + 56f, 2f);

        String inhAct = actionPrefix + "INHERIT";
        ButtonAPI bInh = registerButton(
                rowUi.addButton("Inherit", inhAct, isCustom ? baseColor : activeColor, darkColor, Alignment.MID, CutStyle.ALL, 82f, 20f, 0f),
                inhAct);
        if (!isCustom) bInh.highlight();
        bInh.getPosition().inTL(x + 112f, 2f);

        rowUi.setHeightSoFar(25f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        col.addCustom(rowPanel, 2f);
    }

    private void addDivisionStandoffRow(
            TooltipMakerAPI col,
            float rowWidth,
            int effStandoff,
            int longestGunRange,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 48f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 48f, false);

        String statusTag = isCustom
                ? ((longestGunRange > 0 && Math.abs(effStandoff - longestGunRange) <= 25) ? "Auto (Guns)" : "Custom")
                : "Inherited";
        String label = String.format(
                Locale.ROOT,
                "Standoff range: %du (%.2fx Paragon) [%s | Max Gun Range: %du]",
                effStandoff,
                effStandoff / (float) PermanentDivisionManager.PARAGON_LENGTH_UNITS,
                statusTag,
                longestGunRange);
        rowUi.addPara(label, 3f, highlight, label).getPosition().inTL(2f, 3f);

        String decAct = "DIV_STANDOFF_RANGE:" + Math.max(PermanentDivisionManager.MIN_STANDOFF_RANGE_UNITS, effStandoff - 100);
        ButtonAPI bDec = registerButton(
                rowUi.addButton("-100u", decAct, baseColor, darkColor, Alignment.MID, CutStyle.ALL, 56f, 20f, 0f),
                decAct);
        bDec.getPosition().inTL(2f, 25f);

        String incAct = "DIV_STANDOFF_RANGE:" + Math.min(PermanentDivisionManager.MAX_STANDOFF_RANGE_UNITS, effStandoff + 100);
        ButtonAPI bInc = registerButton(
                rowUi.addButton("+100u", incAct, baseColor, darkColor, Alignment.MID, CutStyle.ALL, 56f, 20f, 0f),
                incAct);
        bInc.getPosition().inTL(62f, 25f);

        String autoLabel = longestGunRange > 0
                ? ("Auto-Set From Max Gun Range (" + longestGunRange + "u)")
                : "Auto-Set From Max Weapon";
        ButtonAPI bAuto = registerButton(
                rowUi.addButton(autoLabel, "DIV_STANDOFF_RANGE:AUTO", positive, darkColor, Alignment.MID, CutStyle.TL_BR, 230f, 20f, 0f),
                "DIV_STANDOFF_RANGE:AUTO");
        bAuto.setEnabled(longestGunRange > 0);
        bAuto.getPosition().inTL(122f, 25f);

        ButtonAPI bInh = registerButton(
                rowUi.addButton("Inherit", "DIV_STANDOFF_RANGE:INHERIT", !isCustom ? positive : baseColor, darkColor, Alignment.MID, CutStyle.ALL, 88f, 20f, 0f),
                "DIV_STANDOFF_RANGE:INHERIT");
        if (!isCustom) bInh.highlight();
        bInh.getPosition().inTL(356f, 25f);

        rowUi.setHeightSoFar(48f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        col.addCustom(rowPanel, 2f);
    }

    private void addDivisionPreferredSpaceRow(
            TooltipMakerAPI col,
            float rowWidth,
            LineStation effSpace,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color activeColor) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 25f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 25f, false);

        String labelText = String.format(
                Locale.ROOT,
                "Station in Line: %s [%s]",
                effSpace.displayName,
                isCustom ? "Custom/Auto" : "Inherited");
        rowUi.addPara(labelText, 3f, isCustom ? activeColor : baseColor, labelText).getPosition().inTL(2f, 4f);

        float x = rowWidth - 266f;
        for (LineStation ps : LineStation.values()) {
            boolean selected = (ps == effSpace);
            String act = "DIV_LINE_STATION:" + ps.name();
            ButtonAPI bPs = registerButton(
                    rowUi.addButton(
                            ps.displayName,
                            act,
                            selected ? activeColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            58f,
                            20f,
                            0f),
                    act);
            if (selected) bPs.highlight();
            bPs.getPosition().inTL(x, 2f);
            x += 61f;
        }

        String inhAct = "DIV_LINE_STATION:INHERIT";
        ButtonAPI bInh = registerButton(
                rowUi.addButton(
                        "Inherit",
                        inhAct,
                        !isCustom ? activeColor : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        80f,
                        20f,
                        0f),
                inhAct);
        if (!isCustom) bInh.highlight();
        bInh.getPosition().inTL(x, 2f);

        rowUi.setHeightSoFar(25f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        col.addCustom(rowPanel, 2f);
    }

    private void addDivisionMissionDropdownRow(
            TooltipMakerAPI col,
            float rowWidth,
            String labelText,
            String dropdownKey,
            DivisionMissionType effMission,
            DivisionArchetype archetype,
            boolean isCustom,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeColor,
            Color gray) {

        boolean expanded = dropdownKey.equals(expandedDropdownKey);

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 25f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 25f, false);

        rowUi.addPara(labelText, 3f, baseColor).getPosition().inTL(2f, 4f);

        String toggleAct = "TOGGLE_DD:" + dropdownKey;
        String prefix = isCustom ? "" : "[Doctrine] ";
        String btnText = (expanded ? "[^] " : "[v] ") + prefix + effMission.dropdownLabel;
        ButtonAPI bToggle = registerButton(
                rowUi.addButton(btnText, toggleAct, activeColor, darkColor, Alignment.LMID, CutStyle.TL_BR, 265f, 21f, 0f),
                toggleAct);
        if (expanded) bToggle.highlight();
        bToggle.getPosition().inTL(rowWidth - 268f, 2f);

        rowUi.setHeightSoFar(25f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        col.addCustom(rowPanel, 2f);

        if (expanded) {
            // Option 0: Inherit Battle Group Default
            CustomPanelAPI inhPanel = Global.getSettings().createCustom(rowWidth, 22f, subPanelPlugin);
            TooltipMakerAPI inhUi = inhPanel.createUIElement(rowWidth, 22f, false);
            String inhAct = actionPrefix + "INHERIT";
            ButtonAPI bInh = registerButton(
                    inhUi.addButton(
                            (!isCustom ? "* " : "   ") + "Inherit Battle Group Default",
                            inhAct,
                            !isCustom ? activeColor : baseColor,
                            darkColor,
                            Alignment.LMID,
                            CutStyle.ALL,
                            rowWidth - 24f,
                            19f,
                            0f),
                    inhAct);
            if (!isCustom) bInh.highlight();
            bInh.getPosition().inTL(16f, 1f);
            inhUi.setHeightSoFar(22f);
            inhPanel.addUIElement(inhUi).inTL(0f, 0f);
            col.addCustom(inhPanel, 1f);

            // Show archetype-preferred missions first, then the remaining missions so a carrier in any division can select carrier missions
            DivisionMissionType[] primary = DivisionMissionType.missionsForArchetype(archetype);
            List<DivisionMissionType> ordered = new ArrayList<>();
            for (DivisionMissionType m : primary) {
                ordered.add(m);
            }
            for (DivisionMissionType m : DivisionMissionType.values()) {
                if (!ordered.contains(m)) {
                    ordered.add(m);
                }
            }

            for (DivisionMissionType opt : ordered) {
                boolean selected = isCustom && (opt == effMission);
                CustomPanelAPI optPanel = Global.getSettings().createCustom(rowWidth, 22f, subPanelPlugin);
                TooltipMakerAPI optUi = optPanel.createUIElement(rowWidth, 22f, false);
                String selAct = actionPrefix + opt.name();
                String spaceHint = (opt.autoLineStation != null)
                        ? ("  [->" + opt.autoLineStation.displayName + "]")
                        : "";
                ButtonAPI bOpt = registerButton(
                        optUi.addButton(
                                (selected ? "* " : "   ") + opt.dropdownLabel + spaceHint,
                                selAct,
                                selected ? activeColor : baseColor,
                                darkColor,
                                Alignment.LMID,
                                CutStyle.ALL,
                                rowWidth - 24f,
                                19f,
                                0f),
                        selAct);
                if (selected) bOpt.highlight();
                bOpt.getPosition().inTL(16f, 1f);
                optUi.setHeightSoFar(22f);
                optPanel.addUIElement(optUi).inTL(0f, 0f);
                col.addCustom(optPanel, 1f);
            }
        }
    }

    private void savePendingNameFieldIfAny() {
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
        if (cmd.startsWith("DIV_STATION_SPACING:") && activeDivisionId != null) {
            savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_STATION_SPACING:".length());
            Integer val = "INHERIT".equals(arg) ? null : Integer.parseInt(arg);
            PermanentDivisionManager.updateIndividualDivisionSettings(
                    activeDivisionId, true, val, false, null, false, null, false, null, false, null);
            rebuildContent();
            return;
        }
        if (cmd.startsWith("DIV_DIVISION_INTERVAL:") && activeDivisionId != null) {
            savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_DIVISION_INTERVAL:".length());
            Integer val = "INHERIT".equals(arg) ? null : Integer.parseInt(arg);
            PermanentDivisionManager.updateIndividualDivisionSettings(
                    activeDivisionId, false, null, true, val, false, null, false, null, false, null);
            rebuildContent();
            return;
        }
        if (cmd.startsWith("DIV_STANDOFF_RANGE:") && activeDivisionId != null) {
            savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_STANDOFF_RANGE:".length());
            if ("AUTO".equals(arg)) {
                PermanentDivisionManager.autoSetDivisionStandoffRange(activeDivisionId);
            } else {
                Integer val = "INHERIT".equals(arg) ? null : Integer.parseInt(arg);
                PermanentDivisionManager.updateIndividualDivisionSettings(
                        activeDivisionId, false, null, false, null, true, val, false, null, false, null);
            }
            rebuildContent();
            return;
        }
        if (cmd.startsWith("DIV_LINE_STATION:") && activeDivisionId != null) {
            savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_LINE_STATION:".length());
            LineStation ps = "INHERIT".equals(arg) ? null : LineStation.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionLineStation(activeDivisionId, ps);
            rebuildContent();
            return;
        }
        if (cmd.startsWith("DIV_OPEN_MIS:") && activeDivisionId != null) {
            savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_OPEN_MIS:".length());
            DivisionMissionType mis = "INHERIT".equals(arg) ? null : DivisionMissionType.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionSettings(
                    activeDivisionId, false, null, false, null, false, null, true, mis, false, null);
            expandedDropdownKey = null;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("DIV_MAIN_MIS:") && activeDivisionId != null) {
            savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_MAIN_MIS:".length());
            DivisionMissionType mis = "INHERIT".equals(arg) ? null : DivisionMissionType.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionSettings(
                    activeDivisionId, false, null, false, null, false, null, false, null, true, mis);
            expandedDropdownKey = null;
            rebuildContent();
            return;
        }
        if ("APPLY_NAME".equals(cmd)) {
            savePendingNameFieldIfAny();
            rebuildContent();
            return;
        }
        if ("CREATE_DIV_OVERVIEW".equals(cmd)) {
            PermanentDivisionSpec created = PermanentDivisionManager.createNewDivision(
                    DivisionArchetype.CRUISER_DIVISION,
                    null);
            if (created != null) {
                selectedOverviewDivisionId = created.id;
            }
            rebuildContent();
            return;
        }
        if ("CREATE_DIV".equals(cmd)) {
            PermanentDivisionSpec created = PermanentDivisionManager.createNewDivision(
                    DivisionArchetype.CRUISER_DIVISION,
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
            pendingConfirmTitle = "DELETE ALL DIVISIONS — ARE YOU SURE?";
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
            pendingConfirmTitle = "AUTO-ASSIGN SHIPS — ARE YOU SURE?";
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
            pendingConfirmTitle = "RESET & AUTO-FORM — ARE YOU SURE?";
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
            pendingConfirmTitle = "DELETE DIVISION — ARE YOU SURE?";
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
            DivisionArchetype arch = DivisionArchetype.CRUISER_DIVISION;
            CampaignFleetAPI fleet = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
            if (fleet != null && fleet.getFleetData() != null) {
                for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
                    if (mId.equals(m.getId())) {
                        arch = PermanentDivisionManager.classifyFleetMemberArchetype(m);
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
    public void renderBelow(float alphaMult) {
        if (rootModalPanel == null || contentContainer == null) return;
        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();

        PositionAPI cPos = contentContainer.getPosition();
        float x = cPos.getX();
        float y = cPos.getY();
        float w = cPos.getWidth();
        float h = cPos.getHeight();

        Color borderCol = Misc.getBasePlayerColor();
        float br = borderCol.getRed() / 255f;
        float battleGroup = borderCol.getGreen() / 255f;
        float bb = borderCol.getBlue() / 255f;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // 1. Full-screen dark dimmer over the underlying Fleet tab
        GL11.glColor4f(0.0f, 0.0f, 0.0f, 0.72f * alphaMult);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0f, 0f);
        GL11.glVertex2f(screenW, 0f);
        GL11.glVertex2f(screenW, screenH);
        GL11.glVertex2f(0f, screenH);
        GL11.glEnd();

        // 2. Solid dark panel background for the Division Editor window
        GL11.glColor4f(0.04f, 0.06f, 0.09f, 0.96f * alphaMult);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();

        // 3. Crisp outer faction border
        GL11.glLineWidth(2.0f);
        GL11.glColor4f(br, battleGroup, bb, 0.90f * alphaMult);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();

        GL11.glPopAttrib();
    }

    @Override
    public void render(float alphaMult) {
        if (closed || !isDraggingShip || pressedTile == null || pressedTile.member == null) return;

        FleetMemberAPI m = pressedTile.member;
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
        if (hoveredZone != null) {
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
                ShipIconTile rmbTile = findShipTileAt(event.getX(), event.getY());
                if (rmbTile != null && rmbTile.member != null) {
                    event.consume();
                    handleShipTileRightClick(rmbTile);
                    return;
                }
            }

            // Left-Mouse Down -> Start potential drag on a Ship Icon Tile, or select a Division Card
            if (event.isLMBDownEvent()) {
                ShipIconTile lmbTile = findShipTileAt(event.getX(), event.getY());
                if (lmbTile != null && lmbTile.member != null) {
                    pressedTile = lmbTile;
                    pressStartX = event.getX();
                    pressStartY = event.getY();
                    isDraggingShip = false;
                    event.consume();
                    continue;
                }

                // On Screen 1 (Overview), clicking inside a Division Card (outside its buttons) selects that division
                if (activeDivisionId == null && !isPointOverAnyButton(event.getX(), event.getY())) {
                    DropTargetZone clickedZone = findDropZoneAt(event.getX(), event.getY());
                    if (clickedZone != null && clickedZone.targetDivisionId != null) {
                        event.consume();
                        handleAction("SELECT_DIV:" + clickedZone.targetDivisionId);
                        return;
                    }
                }
            }

            // Mouse Move while holding a Ship Icon Tile -> Transition to active Drag-and-Drop
            if (event.isMouseMoveEvent() && pressedTile != null) {
                float dx = event.getX() - pressStartX;
                float dy = event.getY() - pressStartY;
                if ((dx * dx + dy * dy) >= (DRAG_START_THRESHOLD_PX * DRAG_START_THRESHOLD_PX)) {
                    isDraggingShip = true;
                }
                event.consume();
                continue;
            }

            // Left-Mouse Up -> Complete Drag-and-Drop OR execute clean Left-Click on Ship Icon Tile
            if (event.isLMBUpEvent() && pressedTile != null) {
                ShipIconTile finishedTile = pressedTile;
                boolean wasDragging = isDraggingShip;
                pressedTile = null;
                isDraggingShip = false;
                event.consume();

                if (wasDragging) {
                    DropTargetZone dropZone = findDropZoneAt(event.getX(), event.getY());
                    if (dropZone != null) {
                        handleShipTileDrop(finishedTile, dropZone);
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

    private void handleShipTileDrop(ShipIconTile tile, DropTargetZone dropZone) {
        if (tile == null || tile.member == null || dropZone == null) return;
        savePendingNameFieldIfAny();
        String memberId = tile.member.getId();
        String targetDivId = dropZone.targetDivisionId;

        if (targetDivId == null) {
            // Dropped onto the Unassigned Fleet Pool -> Unassign from source division
            if (tile.sourceDivisionId != null) {
                PermanentDivisionManager.removeShipFromDivision(memberId, tile.sourceDivisionId);
                rebuildContent();
            }
        } else {
            // Dropped onto a Division Card / Division Bay -> Assign or transfer to that division
            selectedOverviewDivisionId = targetDivId;
            if (!targetDivId.equals(tile.sourceDivisionId)) {
                PermanentDivisionManager.assignShipToDivision(memberId, targetDivId);
                rebuildContent();
            }
        }
    }

    private ShipIconTile findShipTileAt(float x, float y) {
        for (int i = shipIconTiles.size() - 1; i >= 0; i--) {
            ShipIconTile tile = shipIconTiles.get(i);
            if (tile.tilePanel == null) continue;
            if (tile.clipContainer != null && !isPointInside(x, y, tile.clipContainer.getPosition())) {
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
            if (zone.clipContainer != null && !isPointInside(x, y, zone.clipContainer.getPosition())) {
                continue;
            }
            if (isPointInside(x, y, zone.panel.getPosition())) {
                return zone;
            }
        }
        return null;
    }

    private boolean isPointOverAnyButton(float x, float y) {
        for (ButtonAPI btn : activeButtons.keySet()) {
            if (btn != null && isPointInside(x, y, btn.getPosition())) {
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
}
