package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
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
    private String expandedDropdownKey = null;
    private boolean closed = false;

    private final Map<ButtonAPI, String> activeButtons = new LinkedHashMap<>();
    private String pendingAction = null;

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
        TooltipMakerAPI header = container.createUIElement(DIALOG_WIDTH - 64f, 50f, false);
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
        header.setHeightSoFar(50f);
        container.addUIElement(header).inTL(12f, 8f);

        CustomPanelAPI toolbarRow = Global.getSettings().createCustom(DIALOG_WIDTH - 24f, 28f, subPanelPlugin);
        TooltipMakerAPI tb = toolbarRow.createUIElement(DIALOG_WIDTH - 24f, 28f, false);
        ButtonAPI bCreate = registerButton(
                tb.addButton(
                        "+ Establish New Division",
                        "CREATE_DIV",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        210f,
                        24f,
                        0f),
                "CREATE_DIV");
        bCreate.getPosition().inTL(0f, 2f);

        ButtonAPI bAutoAssign = registerButton(
                tb.addButton(
                        "Auto-Assign Fleet Ships (" + unassigned.size() + ")",
                        "AUTO_ASSIGN",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        250f,
                        24f,
                        0f),
                "AUTO_ASSIGN");
        bAutoAssign.setEnabled(!unassigned.isEmpty());
        bAutoAssign.getPosition().inTL(220f, 2f);

        if (!divisions.isEmpty()) {
            ButtonAPI bClearAll = registerButton(
                    tb.addButton(
                            "Disestablish All Divisions",
                            "CLEAR_ALL_DIVS",
                            Misc.getNegativeHighlightColor(),
                            darkColor,
                            Alignment.MID,
                            CutStyle.TL_BR,
                            205f,
                            24f,
                            0f),
                    "CLEAR_ALL_DIVS");
            bClearAll.getPosition().inTL(480f, 2f);
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

        // 2. Left Column: Permanent Divisions Scrollable List (width 595f, height 540f)
        float bodyHeight = DIALOG_HEIGHT - 104f;
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
                        "%s  —  %s  (%d/%d Ships, %.0f DP)",
                        spec.name,
                        spec.archetype.shortPrefix,
                        members.size(),
                        PermanentDivisionManager.MAX_DIVISION_SIZE,
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

                FleetDoctrineConfig doctrineCfg = PermanentDivisionManager.getFleetDoctrine();
                int effIntra = PermanentDivisionManager.resolveEffectiveStationSpacing(spec, doctrineCfg);
                int effInter = PermanentDivisionManager.resolveEffectiveDivisionInterval(spec, doctrineCfg);
                int effStandoff = PermanentDivisionManager.resolveEffectiveStandoffRange(spec, doctrineCfg);
                LineStation effSpace = PermanentDivisionManager.resolveEffectiveLineStation(spec, doctrineCfg);
                DivisionMissionType effOpen = PermanentDivisionManager.resolveEffectiveOpeningMission(spec, doctrineCfg);
                DivisionMissionType effMain = PermanentDivisionManager.resolveEffectiveMainMission(spec, doctrineCfg);
                leftCol.addPara(
                        "Division Settings: Station %s | Spacing %su | Interval %su | Standoff %su | %s -> %s",
                        2f,
                        Misc.getPositiveHighlightColor(),
                        effSpace.displayName,
                        String.valueOf(effIntra),
                        String.valueOf(effInter),
                        String.valueOf(effStandoff),
                        effOpen.shortName,
                        effMain.shortName);

                if (!shipNames.isEmpty()) {
                    leftCol.addPara("Constituent Hulls: " + joinStrings(shipNames, ", "), gray, 2f);
                } else {
                    leftCol.addPara("No ships assigned yet — open division screen to add ships.", gray, 2f);
                }

                CustomPanelAPI btnRow = Global.getSettings().createCustom(leftWidth - 16f, 26f, subPanelPlugin);
                TooltipMakerAPI rowUi = btnRow.createUIElement(leftWidth - 16f, 26f, false);
                String openAct = "OPEN_DIV:" + spec.id;
                ButtonAPI bOpen = registerButton(
                        rowUi.addButton(
                                "Open Division Screen ->",
                                openAct,
                                baseColor,
                                darkColor,
                                Alignment.MID,
                                CutStyle.TL_BR,
                                215f,
                                22f,
                                0f),
                        openAct);
                bOpen.getPosition().inTL(0f, 2f);

                String delAct = "DELETE_DIV:" + spec.id;
                ButtonAPI bDel = registerButton(
                        rowUi.addButton(
                                "Disestablish Division",
                                delAct,
                                Misc.getNegativeHighlightColor(),
                                darkColor,
                                Alignment.MID,
                                CutStyle.TL_BR,
                                170f,
                                22f,
                                0f),
                        delAct);
                bDel.getPosition().inTL(225f, 2f);

                rowUi.setHeightSoFar(26f);
                btnRow.addUIElement(rowUi).inTL(0f, 0f);
                leftCol.addCustom(btnRow, 4f);
            }
        }
        container.addUIElement(leftCol).inTL(12f, 94f);

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
                String newDivAct = "NEW_DIV_WITH:" + m.getId();
                registerButton(
                        rightCol.addButton(
                                "+ New Division With " + m.getHullSpec().getHullName(),
                                newDivAct,
                                baseColor,
                                darkColor,
                                Alignment.MID,
                                CutStyle.TL_BR,
                                rightWidth - 20f,
                                22f,
                                3f),
                        newDivAct);
            }
        }
        container.addUIElement(rightCol).inTL(leftWidth + 24f, 94f);
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

        leftCol.addSectionHeading(
                "Ships in " + spec.name + " (" + divMembers.size() + "/" + PermanentDivisionManager.MAX_DIVISION_SIZE + " Max)",
                Alignment.MID,
                8f);

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

                CustomPanelAPI rowBtns = Global.getSettings().createCustom(colWidth - 20f, 24f, subPanelPlugin);
                TooltipMakerAPI rUi = rowBtns.createUIElement(colWidth - 20f, 24f, false);

                if (!isFlagship) {
                    String flagAct = "SET_FLAGSHIP:" + m.getId();
                    ButtonAPI bFlag = registerButton(
                            rUi.addButton(
                                    "Designate Flagship",
                                    flagAct,
                                    baseColor,
                                    darkColor,
                                    Alignment.MID,
                                    CutStyle.TL_BR,
                                    160f,
                                    21f,
                                    0f),
                            flagAct);
                    bFlag.getPosition().inTL(0f, 1f);
                }

                String remAct = "REMOVE_SHIP:" + m.getId();
                ButtonAPI bRem = registerButton(
                        rUi.addButton(
                                "Remove from Division ->",
                                remAct,
                                Misc.getNegativeHighlightColor(),
                                darkColor,
                                Alignment.MID,
                                CutStyle.TL_BR,
                                185f,
                                21f,
                                0f),
                        remAct);
                bRem.getPosition().inTL(isFlagship ? 0f : 170f, 1f);

                rUi.setHeightSoFar(24f);
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
                String addAct = "ADD_SHIP:" + m.getId();
                ButtonAPI bAdd = registerButton(
                        rightCol.addButton(
                                divisionAtCap ? "Division Full (10/10 Max)" : ("<- Put in " + spec.name),
                                addAct,
                                positive,
                                darkColor,
                                Alignment.MID,
                                CutStyle.TL_BR,
                                220f,
                                21f,
                                2f),
                        addAct);
                bAdd.setEnabled(!divisionAtCap);
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
                String transAct = "ADD_SHIP:" + m.getId();
                ButtonAPI bTrans = registerButton(
                        rightCol.addButton(
                                divisionAtCap ? "Division Full (10/10 Max)" : ("<- Transfer to " + spec.name),
                                transAct,
                                baseColor,
                                darkColor,
                                Alignment.MID,
                                CutStyle.TL_BR,
                                220f,
                                21f,
                                2f),
                        transAct);
                bTrans.setEnabled(!divisionAtCap);
            }
        }
        if (!anyOther) {
            rightCol.addPara("No ships in other divisions.", gray, 4f);
        }

        container.addUIElement(rightCol).inTL(colWidth + 24f, bodyTop);
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
