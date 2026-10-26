package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.PermanentDivisionManager.DoctrineOption;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full-screen / modal Division Editor UI opened from the "Division Editor" button on the left side
 * of the Campaign Fleet screen (CoreUITabId.FLEET):
 *  - Screen 1 (Overview): Displays all permanent divisions in the player's fleet alongside the
 *    Unassigned Ships pool, allowing the player to create divisions, auto-assign/reset, or open any
 *    division.
 *  - Screen 2 (Dedicated Single-Division Screen): Clicking any division (or creating a new one)
 *    transitions the entire window into that division's own screen where the player can rename the
 *    division, choose its archetype & doctrine posture, designate its flagship, and put ships inside
 *    or remove them.
 */
public class DivisionEditorDialog extends BaseCustomUIPanelPlugin {

    private static final float DIALOG_WIDTH = 980f;
    private static final float DIALOG_HEIGHT = 650f;

    private final UIPanelAPI hostCoreUI;
    private CustomPanelAPI rootModalPanel;
    private CustomPanelAPI contentContainer;
    private TextFieldAPI activeNameField;

    // When null -> Screen 1 (Fleet Divisions Overview); when non-null -> Screen 2 (Dedicated Division Screen)
    private String activeDivisionId = null;
    private boolean closed = false;

    public DivisionEditorDialog(UIPanelAPI hostCoreUI) {
        this.hostCoreUI = hostCoreUI;
    }

    public void show() {
        if (hostCoreUI == null) return;
        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();

        PermanentDivisionManager.ensureInitializedForPlayerFleet();

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
        if (hostCoreUI != null && rootModalPanel != null) {
            hostCoreUI.removeComponent(rootModalPanel);
        }
        rootModalPanel = null;
        contentContainer = null;
        activeNameField = null;
    }

    private void rebuildContent() {
        if (rootModalPanel == null) return;
        if (contentContainer != null) {
            rootModalPanel.removeComponent(contentContainer);
            contentContainer = null;
        }
        activeNameField = null;

        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        float boxX = Math.max(20f, (screenW - DIALOG_WIDTH) * 0.5f);
        float boxYTop = Math.max(20f, (screenH - DIALOG_HEIGHT) * 0.5f);

        contentContainer = Global.getSettings().createCustom(DIALOG_WIDTH, DIALOG_HEIGHT, null);
        rootModalPanel.addComponent(contentContainer).inTL(boxX, boxYTop);

        if (activeDivisionId != null) {
            PermanentDivisionSpec spec = PermanentDivisionManager.getDivisionById(activeDivisionId);
            if (spec != null) {
                buildSingleDivisionScreen(contentContainer, spec);
                return;
            }
            activeDivisionId = null;
        }

        buildOverviewScreen(contentContainer);
    }

    // ========================================================================================
    // SCREEN 1: FLEET DIVISION EDITOR OVERVIEW
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
        Color gray = Misc.getGrayColor();

        // 1. Header & Top Toolbar (height 78f)
        TooltipMakerAPI header = container.createUIElement(DIALOG_WIDTH - 24f, 78f, false);
        header.setTitleOrbitronLarge();
        header.addTitle("FLEET DIVISION EDITOR — PERMANENT C2 ORGANIZATION", baseColor);
        header.addPara(
                "Permanent divisions are saved with your campaign fleet and inherited automatically in tactical battles. "
                        + "Active Divisions: %s  |  Assigned Combat Ships: %s  |  Unassigned Combat Ships: %s",
                4f,
                highlight,
                String.valueOf(divisions.size()),
                String.valueOf(assignedCount),
                String.valueOf(unassigned.size()));

        CustomPanelAPI toolbarRow = Global.getSettings().createCustom(DIALOG_WIDTH - 24f, 28f, null);
        TooltipMakerAPI tb = toolbarRow.createUIElement(DIALOG_WIDTH - 24f, 28f, false);
        ButtonAPI bCreate = tb.addButton(
                "+ Create New Division",
                "CREATE_DIV",
                baseColor,
                darkColor,
                Alignment.MID,
                CutStyle.TL_BR,
                220f,
                24f,
                0f);
        bCreate.getPosition().inTL(0f, 2f);

        if (!divisions.isEmpty()) {
            ButtonAPI bClearAll = tb.addButton(
                    "Disband All Divisions",
                    "CLEAR_ALL_DIVS",
                    Misc.getNegativeHighlightColor(),
                    darkColor,
                    Alignment.MID,
                    CutStyle.TL_BR,
                    205f,
                    24f,
                    0f);
            bClearAll.getPosition().inTL(230f, 2f);
        }

        ButtonAPI bClose = tb.addButton(
                "Close [Esc]",
                "CLOSE",
                Misc.getNegativeHighlightColor(),
                darkColor,
                Alignment.MID,
                CutStyle.TL_BR,
                140f,
                24f,
                0f);
        bClose.getPosition().inTL(DIALOG_WIDTH - 24f - 140f, 2f);

        toolbarRow.addUIElement(tb).inTL(0f, 0f);
        header.addCustom(toolbarRow, 6f);
        container.addUIElement(header).inTL(12f, 10f);

        // 2. Left Column: Permanent Divisions Scrollable List (width 595f, height 540f)
        float bodyHeight = DIALOG_HEIGHT - 102f;
        float leftWidth = 595f;
        float rightWidth = DIALOG_WIDTH - leftWidth - 36f;

        TooltipMakerAPI leftCol = container.createUIElement(leftWidth, bodyHeight, true);
        leftCol.addSectionHeading("Permanent Divisions (" + divisions.size() + ")", Alignment.MID, 4f);

        if (divisions.isEmpty()) {
            leftCol.addPara(
                    "No permanent divisions configured. Your fleet will use vanilla combat AI until you create at least 1 division and assign ships to it.",
                    gray,
                    10f);
        } else {
            for (PermanentDivisionSpec spec : divisions) {
                List<FleetMemberAPI> members = PermanentDivisionManager.getMembersInDivision(spec, fleet);
                float totalDp = 0f;
                FleetMemberAPI flagship = null;
                List<String> shipNames = new ArrayList<>();
                for (FleetMemberAPI m : members) {
                    totalDp += m.getDeploymentPointsCost();
                    if (m.getId().equals(spec.flagshipMemberId)) {
                        flagship = m;
                    }
                    shipNames.add(m.getHullSpec().getHullName());
                }
                if (flagship == null && !members.isEmpty()) {
                    flagship = members.get(0);
                }

                String headerTitle = String.format(
                        "%s  —  %s  (%d Ships, %.0f DP)",
                        spec.name,
                        spec.archetype.shortPrefix,
                        members.size(),
                        totalDp);
                leftCol.addSectionHeading(headerTitle, baseColor, darkColor, Alignment.LMID, 8f);

                if (!members.isEmpty()) {
                    leftCol.addShipList(Math.min(8, members.size()), 1, 46f, baseColor, members, 4f);
                }

                String flagshipDesc = (flagship != null)
                        ? (flagship.getShipName() + " (" + flagship.getHullSpec().getHullName() + ")")
                        : "None (Empty Division)";
                PersonAPI cap = (flagship != null) ? flagship.getCaptain() : null;
                String cmdrDesc = (cap != null && !cap.isDefault())
                        ? (cap.getNameString() + " [" + cap.getPersonalityAPI().getDisplayName() + "]")
                        : "Default Division Commander";

                leftCol.addPara(
                        "Flagship: %s  |  Cmdr: %s  |  Doctrine: %s",
                        3f,
                        highlight,
                        flagshipDesc,
                        cmdrDesc,
                        spec.doctrine.displayName);

                if (!shipNames.isEmpty()) {
                    leftCol.addPara("Constituent Hulls: " + joinStrings(shipNames, ", "), gray, 2f);
                } else {
                    leftCol.addPara("No ships assigned yet — open division screen to add ships.", gray, 2f);
                }

                CustomPanelAPI btnRow = Global.getSettings().createCustom(leftWidth - 16f, 26f, null);
                TooltipMakerAPI rowUi = btnRow.createUIElement(leftWidth - 16f, 26f, false);
                ButtonAPI bOpen = rowUi.addButton(
                        "Open Division Screen ->",
                        "OPEN_DIV:" + spec.id,
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        215f,
                        22f,
                        0f);
                bOpen.getPosition().inTL(0f, 2f);

                ButtonAPI bDel = rowUi.addButton(
                        "Disband Division",
                        "DELETE_DIV:" + spec.id,
                        Misc.getNegativeHighlightColor(),
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        145f,
                        22f,
                        0f);
                bDel.getPosition().inTL(225f, 2f);

                btnRow.addUIElement(rowUi).inTL(0f, 0f);
                leftCol.addCustom(btnRow, 4f);
            }
        }
        container.addUIElement(leftCol).inTL(12f, 92f);

        // 3. Right Column: Unassigned Combat Hulls Pool
        TooltipMakerAPI rightCol = container.createUIElement(rightWidth, bodyHeight, true);
        rightCol.addSectionHeading("Unassigned Combat Hulls (" + unassigned.size() + ")", Alignment.MID, 4f);

        if (unassigned.isEmpty()) {
            rightCol.addPara(
                    "All eligible combat hulls in your fleet are assigned to permanent divisions.",
                    Misc.getPositiveHighlightColor(),
                    8f);
            rightCol.addPara(
                    "Click 'Open Division Screen ->' on any division on the left to move ships, change flagships, or customize doctrine.",
                    gray,
                    6f);
        } else {
            rightCol.addPara(
                    "Open any division on the left to add these ships, or spin up a new division directly from a ship below:",
                    gray,
                    4f);
            for (FleetMemberAPI m : unassigned) {
                rightCol.addShipList(1, 1, 42f, baseColor, Collections.singletonList(m), 6f);
                PersonAPI cap = m.getCaptain();
                String officerStr = (cap != null && !cap.isDefault()) ? cap.getNameString() : "No Officer";
                rightCol.addPara(
                        "%s — %s (%s DP | %s)",
                        2f,
                        highlight,
                        m.getShipName(),
                        m.getHullSpec().getHullName(),
                        String.format("%.0f", m.getDeploymentPointsCost()),
                        officerStr);
                rightCol.addButton(
                        "+ New Division With " + m.getHullSpec().getHullName(),
                        "NEW_DIV_WITH:" + m.getId(),
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        rightWidth - 20f,
                        22f,
                        3f);
            }
        }
        container.addUIElement(rightCol).inTL(leftWidth + 24f, 92f);
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

        // 1. Top Control & Doctrine Header (height 188f)
        float headerH = 188f;
        TooltipMakerAPI top = container.createUIElement(DIALOG_WIDTH - 24f, headerH, false);

        // Row A: Back button, Title, Close button
        CustomPanelAPI navRow = Global.getSettings().createCustom(DIALOG_WIDTH - 24f, 28f, null);
        TooltipMakerAPI navUi = navRow.createUIElement(DIALOG_WIDTH - 24f, 28f, false);
        ButtonAPI bBack = navUi.addButton(
                "<- Back to All Divisions",
                "BACK_TO_LIST",
                baseColor,
                darkColor,
                Alignment.MID,
                CutStyle.TL_BR,
                195f,
                24f,
                0f);
        bBack.getPosition().inTL(0f, 2f);

        ButtonAPI bClose = navUi.addButton(
                "Close Editor [Esc]",
                "CLOSE",
                Misc.getNegativeHighlightColor(),
                darkColor,
                Alignment.MID,
                CutStyle.TL_BR,
                150f,
                24f,
                0f);
        bClose.getPosition().inTL(DIALOG_WIDTH - 24f - 150f, 2f);
        navRow.addUIElement(navUi).inTL(0f, 0f);
        top.addCustom(navRow, 2f);

        top.setTitleOrbitronLarge();
        top.addTitle("DIVISION COMMAND & DOCTRINE: " + spec.name.toUpperCase(), highlight);

        // Row B: Editable Callsign TextField + Apply Name Button
        CustomPanelAPI nameRow = Global.getSettings().createCustom(DIALOG_WIDTH - 24f, 30f, null);
        TooltipMakerAPI nameUi = nameRow.createUIElement(DIALOG_WIDTH - 24f, 30f, false);
        activeNameField = nameUi.addTextField(260f, Fonts.DEFAULT_SMALL, 0f);
        activeNameField.setText(spec.name);
        activeNameField.setMaxChars(32);
        activeNameField.getPosition().inTL(0f, 3f);

        ButtonAPI bSaveName = nameUi.addButton(
                "Apply Division Name",
                "APPLY_NAME",
                baseColor,
                darkColor,
                Alignment.MID,
                CutStyle.TL_BR,
                165f,
                24f,
                0f);
        bSaveName.getPosition().inTL(270f, 3f);
        nameRow.addUIElement(nameUi).inTL(0f, 0f);
        top.addCustom(nameRow, 4f);

        // Row C: Division Archetype Selector
        DivisionArchetype[] archetypes = new DivisionArchetype[]{
                DivisionArchetype.BATTLE_DIVISION,
                DivisionArchetype.CRUISER_DIVISION,
                DivisionArchetype.STRIKE_CRUISER_DIVISION,
                DivisionArchetype.DESTROYER_DIVISION,
                DivisionArchetype.TORPEDO_DESTROYER_DIVISION,
                DivisionArchetype.FRIGATE_DIVISION,
                DivisionArchetype.CARRIER_AND_ESCORT,
                DivisionArchetype.PHASE_DIVISION,
                DivisionArchetype.AUXILIARY_CONVOY
        };
        CustomPanelAPI archRow = Global.getSettings().createCustom(DIALOG_WIDTH - 24f, 26f, null);
        TooltipMakerAPI archUi = archRow.createUIElement(DIALOG_WIDTH - 24f, 26f, false);
        float ax = 0f;
        for (DivisionArchetype arch : archetypes) {
            boolean selected = (spec.archetype == arch);
            Color fg = selected ? highlight : baseColor;
            Color bg = selected ? baseColor.darker() : darkColor;
            ButtonAPI bArch = archUi.addButton(
                    arch.shortPrefix,
                    "SET_ARCH:" + arch.name(),
                    fg,
                    bg,
                    Alignment.MID,
                    CutStyle.TL_BR,
                    102f,
                    22f,
                    0f);
            bArch.getPosition().inTL(ax, 2f);
            ax += 106f;
        }
        archRow.addUIElement(archUi).inTL(0f, 0f);
        top.addCustom(archRow, 4f);

        // Row D: Division Doctrine Selector (Foundation for Customizable Doctrine)
        DoctrineOption[] doctrines = DoctrineOption.values();
        CustomPanelAPI docRow = Global.getSettings().createCustom(DIALOG_WIDTH - 24f, 26f, null);
        TooltipMakerAPI docUi = docRow.createUIElement(DIALOG_WIDTH - 24f, 26f, false);
        float dx = 0f;
        for (DoctrineOption doc : doctrines) {
            boolean selected = (spec.doctrine == doc);
            Color fg = selected ? positive : baseColor;
            Color bg = selected ? baseColor.darker() : darkColor;
            ButtonAPI bDoc = docUi.addButton(
                    doc.displayName,
                    "SET_DOC:" + doc.name(),
                    fg,
                    bg,
                    Alignment.MID,
                    CutStyle.TL_BR,
                    154f,
                    22f,
                    0f);
            bDoc.getPosition().inTL(dx, 2f);
            dx += 159f;
        }
        docRow.addUIElement(docUi).inTL(0f, 0f);
        top.addCustom(docRow, 4f);

        top.addPara(
                "Active Doctrine — %s: %s",
                4f,
                positive,
                spec.doctrine.displayName,
                spec.doctrine.summary);

        container.addUIElement(top).inTL(12f, 10f);

        // 2. Two-Column Ship Assignment Body (height = DIALOG_HEIGHT - 210f)
        float bodyTop = 202f;
        float bodyHeight = DIALOG_HEIGHT - bodyTop - 12f;
        float colWidth = (DIALOG_WIDTH - 36f) * 0.5f;

        // Left Column: Ships Currently Assigned to This Division
        TooltipMakerAPI leftCol = container.createUIElement(colWidth, bodyHeight, true);
        leftCol.addSectionHeading(
                "Ships in " + spec.name + " (" + divMembers.size() + ")",
                Alignment.MID,
                4f);

        if (!divMembers.isEmpty()) {
            leftCol.addShipList(Math.min(7, divMembers.size()), 1, 48f, baseColor, divMembers, 4f);
        }

        if (divMembers.isEmpty()) {
            leftCol.addPara(
                    "This division currently has no ships. Click '<- Put in Division' on any ship in the right column to add it.",
                    gray,
                    10f);
        } else {
            for (FleetMemberAPI m : divMembers) {
                boolean isFlagship = m.getId().equals(spec.flagshipMemberId);
                String badge = isFlagship ? "[FLAGSHIP]" : "[MEMBER]";
                PersonAPI cap = m.getCaptain();
                String officerStr = (cap != null && !cap.isDefault())
                        ? (cap.getNameString() + " [" + cap.getPersonalityAPI().getDisplayName() + "]")
                        : "No Officer";

                leftCol.addPara(
                        "%s %s — %s (%s DP | %s)",
                        6f,
                        isFlagship ? positive : highlight,
                        badge,
                        m.getShipName(),
                        m.getHullSpec().getHullName(),
                        String.format("%.0f", m.getDeploymentPointsCost()),
                        officerStr);

                CustomPanelAPI rowBtns = Global.getSettings().createCustom(colWidth - 20f, 24f, null);
                TooltipMakerAPI rUi = rowBtns.createUIElement(colWidth - 20f, 24f, false);

                if (!isFlagship) {
                    ButtonAPI bFlag = rUi.addButton(
                            "Designate Flagship",
                            "SET_FLAGSHIP:" + m.getId(),
                            baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.TL_BR,
                            160f,
                            21f,
                            0f);
                    bFlag.getPosition().inTL(0f, 1f);
                }

                ButtonAPI bRem = rUi.addButton(
                        "Remove from Division ->",
                        "REMOVE_SHIP:" + m.getId(),
                        Misc.getNegativeHighlightColor(),
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        185f,
                        21f,
                        0f);
                bRem.getPosition().inTL(isFlagship ? 0f : 170f, 1f);

                rowBtns.addUIElement(rUi).inTL(0f, 0f);
                leftCol.addCustom(rowBtns, 2f);
            }
        }
        container.addUIElement(leftCol).inTL(12f, bodyTop);

        // Right Column: Unassigned Ships + Ships in Other Divisions
        TooltipMakerAPI rightCol = container.createUIElement(colWidth, bodyHeight, true);
        rightCol.addSectionHeading("Unassigned Fleet Ships (" + unassigned.size() + ")", Alignment.MID, 4f);

        if (unassigned.isEmpty()) {
            rightCol.addPara("No unassigned combat ships in fleet.", gray, 4f);
        } else {
            for (FleetMemberAPI m : unassigned) {
                PersonAPI cap = m.getCaptain();
                String officerStr = (cap != null && !cap.isDefault()) ? cap.getNameString() : "No Officer";
                rightCol.addPara(
                        "%s — %s (%s DP | %s)",
                        5f,
                        highlight,
                        m.getShipName(),
                        m.getHullSpec().getHullName(),
                        String.format("%.0f", m.getDeploymentPointsCost()),
                        officerStr);
                rightCol.addButton(
                        "<- Put in " + spec.name,
                        "ADD_SHIP:" + m.getId(),
                        positive,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        220f,
                        21f,
                        2f);
            }
        }

        // Also list ships from other divisions so the player can transfer ships directly
        rightCol.addSectionHeading("Transfer From Other Divisions", Alignment.MID, 10f);
        boolean anyOther = false;
        for (PermanentDivisionSpec other : allDivs) {
            if (other.id.equals(spec.id)) continue;
            List<FleetMemberAPI> otherMembers = PermanentDivisionManager.getMembersInDivision(other, fleet);
            for (FleetMemberAPI m : otherMembers) {
                anyOther = true;
                rightCol.addPara(
                        "[%s] %s — %s (%s DP)",
                        5f,
                        gray,
                        other.name,
                        m.getShipName(),
                        m.getHullSpec().getHullName(),
                        String.format("%.0f", m.getDeploymentPointsCost()));
                rightCol.addButton(
                        "<- Transfer to " + spec.name,
                        "ADD_SHIP:" + m.getId(),
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        220f,
                        21f,
                        2f);
            }
        }
        if (!anyOther) {
            rightCol.addPara("No ships in other divisions.", gray, 4f);
        }

        container.addUIElement(rightCol).inTL(colWidth + 24f, bodyTop);
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
        if (!(buttonId instanceof String)) return;
        String cmd = (String) buttonId;

        if ("CLOSE".equals(cmd)) {
            close();
            return;
        }
        if ("BACK_TO_LIST".equals(cmd)) {
            savePendingNameFieldIfAny();
            activeDivisionId = null;
            rebuildContent();
            return;
        }
        if ("APPLY_NAME".equals(cmd)) {
            savePendingNameFieldIfAny();
            rebuildContent();
            return;
        }
        if ("CREATE_DIV".equals(cmd)) {
            PermanentDivisionSpec created = PermanentDivisionManager.createNewDivision(
                    DivisionArchetype.CRUISER_DIVISION,
                    null);
            if (created != null) {
                activeDivisionId = created.id;
            }
            rebuildContent();
            return;
        }
        if ("CLEAR_ALL_DIVS".equals(cmd)) {
            PermanentDivisionManager.clearAllDivisions();
            activeDivisionId = null;
            rebuildContent();
            return;
        }
        if ("AUTO_ASSIGN".equals(cmd)) {
            PermanentDivisionManager.autoAssignUnassignedMembers();
            rebuildContent();
            return;
        }
        if ("RESET_AUTO".equals(cmd)) {
            PermanentDivisionManager.resetAndAutoFormFromPlayerFleet();
            activeDivisionId = null;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("OPEN_DIV:")) {
            activeDivisionId = cmd.substring("OPEN_DIV:".length());
            rebuildContent();
            return;
        }
        if (cmd.startsWith("DELETE_DIV:")) {
            String delId = cmd.substring("DELETE_DIV:".length());
            PermanentDivisionManager.deleteDivision(delId);
            if (delId.equals(activeDivisionId)) {
                activeDivisionId = null;
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
                activeDivisionId = created.id;
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
        float bg = borderCol.getGreen() / 255f;
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
        GL11.glColor4f(br, bg, bb, 0.90f * alphaMult);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();

        GL11.glPopAttrib();
    }

    @Override
    public void processInput(List<InputEventAPI> events) {
        if (events == null || closed) return;
        for (InputEventAPI event : events) {
            if (event.isConsumed()) continue;
            if (event.isKeyDownEvent() && event.getEventValue() == Keyboard.KEY_ESCAPE) {
                event.consume();
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
            // Consume all remaining mouse/key events so clicks don't leak through to the Fleet screen behind the modal
            if (!event.isMouseUpEvent()) {
                event.consume();
            }
        }
    }

    private static String joinStrings(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
