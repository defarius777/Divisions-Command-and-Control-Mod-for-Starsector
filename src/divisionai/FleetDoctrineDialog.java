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
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionNomenclature.NumberingStyle;
import divisionai.DivisionNomenclature.PlayerNamingConfig;
import divisionai.DivisionNomenclature.PlayerNamingPreset;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.DoctrineDivisionType;
import divisionai.PermanentDivisionManager.DoctrineHullCategory;
import divisionai.PermanentDivisionManager.FleetDoctrineConfig;
import divisionai.PermanentDivisionManager.OtcPlay;
import divisionai.PermanentDivisionManager.LineStation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Campaign-layer Fleet Doctrine & Battle Group Settings modal screen accessible from the
 * "Fleet Doctrine" button on the left sidebar of the Fleet screen.
 *
 * Configures:
 *  0. Battle Group Command: the OTC's orchestration authority (A. Hybrid, B. Central Planning,
 *     C. Mission-Type Tactics, D. Target Coordination Only) and On/Off toggles for each orchestration play.
 *  1. Battle Group Settings (inherited by divisions of each type unless overridden in Division Settings):
 *     - Intra-division spacing (in units, with Paragon length = 364 units reference subtitle)
 *     - Inter-division spacing (in units)
 *     - Line Standoff distance (in units)
 *     - Opening Mission (drop-down menu: a. Reconnaissance, b. Dominate Space into Brawl,
 *       c. Standoff at Range, d. Outflank and Destroy, e. Screen; 30s - 60s window)
 *     - Main Mission (drop-down menu: a..e; rest of battle)
 *  2. The 14 hull-count rules (minimum & maximum hulls per division across all 7 hull categories,
 *     subject to the hard cap: Maximum division size no matter what is 10).
 */
public class FleetDoctrineDialog extends BaseCustomUIPanelPlugin {

    private static final float DIALOG_WIDTH = 1020f;
    private static final float DIALOG_HEIGHT = 680f;

    private static final int[] STATION_SPACING_PRESETS = {200, 300, 400, 500, 600, 750, 900, 1100};
    private static final int[] DIVISION_INTERVAL_PRESETS = {500, 700, 900, 1100, 1300, 1500, 1800, 2100};
    private static final int[] STANDOFF_RANGE_PRESETS = {450, 650, 800, 950, 1150, 1400, 1700, 2000};
    private static final int[] OPENING_DURATION_PRESETS = {30, 35, 40, 45, 50, 55, 60};
    private static final float[] STATION_KEEPING_PRESETS = {0.75f, 0.90f, 1.00f, 1.15f, 1.30f, 1.50f};

    private enum ActiveTab {
        ALL_DOCTRINE,
        BATTLE_GROUP_COMMAND,
        DIVISION_MODIFIERS,
        HULL_LIMITS,
        NAMING_SCHEME
    }

    private static final String OTC_AUTHORITY_DROPDOWN_KEY = "OTC_AUTHORITY";
    private static final String NAMING_PRESET_DROPDOWN_KEY = "NAMING_PRESET";
    private static final DivisionArchetype[] CUSTOMIZABLE_ARCHETYPES = {
            DivisionArchetype.BATTLESHIP_DIVISION,
            DivisionArchetype.FLEET_CARRIER_DIVISION,
            DivisionArchetype.LIGHT_CARRIER_DIVISION,
            DivisionArchetype.ESCORT_CARRIER_DIVISION,
            DivisionArchetype.CARRIER_GROUP,
            DivisionArchetype.CRUISER_DIVISION,
            DivisionArchetype.STRIKE_CRUISER_DIVISION,
            DivisionArchetype.DESTROYER_DIVISION,
            DivisionArchetype.FRIGATE_DIVISION,
            DivisionArchetype.PHASE_DIVISION,
            DivisionArchetype.SERVICE_GROUP
    };

    private final UIPanelAPI parentCoreUI;
    private CustomPanelAPI rootOverlayPanel;
    private CustomPanelAPI contentContainer;
    private boolean closed = false;
    private ActiveTab activeTab = ActiveTab.ALL_DOCTRINE;
    private String expandedDropdownKey = null;
    private String pendingConfirmationCmd = null;
    private int lastReinitFleetCount = -1;

    private final Map<ButtonAPI, String> activeButtons = new LinkedHashMap<>();
    private final Map<DivisionArchetype, TextFieldAPI> prefixTextFields = new LinkedHashMap<>();
    private TextFieldAPI customOtcTitleField = null;
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
        commitCustomPrefixTextFields();
        closed = true;
        activeButtons.clear();
        prefixTextFields.clear();
        customOtcTitleField = null;
        if (parentCoreUI != null && rootOverlayPanel != null) {
            parentCoreUI.removeComponent(rootOverlayPanel);
        }
    }

    private void commitCustomPrefixTextFields() {
        if (prefixTextFields.isEmpty() && customOtcTitleField == null) return;
        PlayerNamingConfig cfg = DivisionNomenclature.getPlayerNamingConfig();
        if (customOtcTitleField != null) {
            cfg.setCustomOtcTitle(customOtcTitleField.getText());
        }
        for (Map.Entry<DivisionArchetype, TextFieldAPI> e : prefixTextFields.entrySet()) {
            DivisionArchetype arch = e.getKey();
            TextFieldAPI tf = e.getValue();
            if (arch == null || tf == null) continue;
            String typed = tf.getText() != null ? tf.getText().trim() : "";
            String presetDefault = DivisionNomenclature.getDefaultPrefixForPreset(cfg.getPreset(), arch);
            if (typed.isEmpty() || typed.equals(presetDefault)) {
                cfg.setCustomPrefixForArchetype(arch, null);
            } else {
                cfg.setCustomPrefixForArchetype(arch, typed);
            }
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
        prefixTextFields.clear();
        customOtcTitleField = null;

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

        // 1. Header & Subtitle (including Paragon's in-game length = 364 units)
        TooltipMakerAPI header = contentContainer.createUIElement(DIALOG_WIDTH - 64f, 62f, false);
        header.setTitleOrbitronLarge();
        header.addTitle("FLEET DOCTRINE — BATTLE GROUP COMMAND, DIVISION MODIFIERS & HULL LIMITS", baseColor);
        header.addPara(
                "Configure OTC command doctrine, division-type station spacing and standoff range, and min/max hull limits (Hard Cap: %s ships/division).",
                3f,
                highlight,
                String.valueOf(PermanentDivisionManager.MAX_DIVISION_SIZE));
        header.addPara(
                "Reference scale: Paragon-class Battleship length = %s in-game units (hull bounds 354u, width 330u).",
                2f,
                positive,
                String.valueOf(PermanentDivisionManager.PARAGON_LENGTH_UNITS));
        header.setHeightSoFar(62f);
        contentContainer.addUIElement(header).inTL(12f, 6f);

        // 2. Top Toolbar (Filter Tabs + Reset Defaults + Close)
        CustomPanelAPI toolbarRow = Global.getSettings().createCustom(DIALOG_WIDTH - 24f, 28f, subPanelPlugin);
        TooltipMakerAPI tb = toolbarRow.createUIElement(DIALOG_WIDTH - 24f, 28f, false);

        ButtonAPI bTabAll = registerButton(
                tb.addButton(
                        "All Settings",
                        "TAB:ALL",
                        activeTab == ActiveTab.ALL_DOCTRINE ? highlight : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        115f,
                        24f,
                        0f),
                "TAB:ALL");
        if (activeTab == ActiveTab.ALL_DOCTRINE) bTabAll.highlight();
        bTabAll.getPosition().inTL(0f, 2f);

        ButtonAPI bTabOtc = registerButton(
                tb.addButton(
                        "Battle Group Command",
                        "TAB:OTC_COMMAND",
                        activeTab == ActiveTab.BATTLE_GROUP_COMMAND ? highlight : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        162f,
                        24f,
                        0f),
                "TAB:OTC_COMMAND");
        if (activeTab == ActiveTab.BATTLE_GROUP_COMMAND) bTabOtc.highlight();
        bTabOtc.getPosition().inTL(120f, 2f);

        ButtonAPI bTabMod = registerButton(
                tb.addButton(
                        "Division Modifiers",
                        "TAB:MODIFIERS",
                        activeTab == ActiveTab.DIVISION_MODIFIERS ? highlight : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        148f,
                        24f,
                        0f),
                "TAB:MODIFIERS");
        if (activeTab == ActiveTab.DIVISION_MODIFIERS) bTabMod.highlight();
        bTabMod.getPosition().inTL(287f, 2f);

        ButtonAPI bTabHull = registerButton(
                tb.addButton(
                        "Hull Min/Max Limits",
                        "TAB:HULLS",
                        activeTab == ActiveTab.HULL_LIMITS ? highlight : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        145f,
                        24f,
                        0f),
                "TAB:HULLS");
        if (activeTab == ActiveTab.HULL_LIMITS) bTabHull.highlight();
        bTabHull.getPosition().inTL(440f, 2f);

        ButtonAPI bTabNaming = registerButton(
                tb.addButton(
                        "Division Naming",
                        "TAB:NAMING",
                        activeTab == ActiveTab.NAMING_SCHEME ? highlight : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        136f,
                        24f,
                        0f),
                "TAB:NAMING");
        if (activeTab == ActiveTab.NAMING_SCHEME) bTabNaming.highlight();
        bTabNaming.getPosition().inTL(590f, 2f);

        String reinitLabel = lastReinitFleetCount >= 0
                ? ("Reinitialized (" + lastReinitFleetCount + ")!")
                : "Reinit Sector Divs";
        ButtonAPI bReinitSector = registerButton(
                tb.addButton(
                        reinitLabel,
                        "REINIT_SECTOR_DIVISIONS",
                        lastReinitFleetCount >= 0 ? positive : highlight,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        148f,
                        24f,
                        0f),
                "REINIT_SECTOR_DIVISIONS");
        bReinitSector.getPosition().inTL(DIALOG_WIDTH - 24f - 120f - 136f - 154f, 2f);

        ButtonAPI bReset = registerButton(
                tb.addButton(
                        "Reset Defaults",
                        "RESET_DEFAULTS",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        130f,
                        24f,
                        0f),
                "RESET_DEFAULTS");
        bReset.getPosition().inTL(DIALOG_WIDTH - 24f - 120f - 136f, 2f);

        ButtonAPI bClose = registerButton(
                tb.addButton(
                        "Close [Esc]",
                        "CLOSE",
                        Misc.getNegativeHighlightColor(),
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        120f,
                        24f,
                        0f),
                "CLOSE");
        bClose.getPosition().inTL(DIALOG_WIDTH - 24f - 120f, 2f);

        tb.setHeightSoFar(28f);
        toolbarRow.addUIElement(tb).inTL(0f, 0f);
        contentContainer.addComponent(toolbarRow).inTL(12f, 70f);

        if (pendingConfirmationCmd != null) {
            buildConfirmationScreen(contentContainer, baseColor, darkColor, highlight);
            return;
        }

        // 3. Scrollable Body
        float bodyTop = 104f;
        float bodyHeight = DIALOG_HEIGHT - bodyTop - 14f;
        float bodyWidth = DIALOG_WIDTH - 24f;

        TooltipMakerAPI body = contentContainer.createUIElement(bodyWidth, bodyHeight, true);

        if (activeTab == ActiveTab.ALL_DOCTRINE || activeTab == ActiveTab.BATTLE_GROUP_COMMAND) {
            body.addSectionHeading(
                    "BATTLE GROUP COMMAND — OTC AUTHORITY & PLAYS",
                    highlight,
                    darkColor,
                    Alignment.LMID,
                    4f);
            body.addPara(
                    "How your Battle Group Commander, the Officer in Tactical Command (OTC), fights the battle across divisions. Authority sets how far its "
                            + "orders may override each division's Opening/Main Mission; plays switch individual behaviours on or off.",
                    4f,
                    gray);

            addAuthorityDropdownRow(
                    body,
                    bodyWidth - 16f,
                    doctrine.getOtcAuthority(),
                    baseColor,
                    darkColor,
                    positive,
                    gray);

            for (OtcPlay play : OtcPlay.values()) {
                addPlayToggleRow(
                        body,
                        bodyWidth - 16f,
                        play,
                        doctrine.isPlayEnabled(play),
                        baseColor,
                        darkColor,
                        positive,
                        gray);
            }
        }

        if (activeTab == ActiveTab.ALL_DOCTRINE || activeTab == ActiveTab.DIVISION_MODIFIERS) {
            body.addSectionHeading(
                    "BATTLE GROUP SETTINGS — STATION SPACING, STANDOFF RANGE & MISSIONS  (Paragon Length = "
                            + PermanentDivisionManager.PARAGON_LENGTH_UNITS + "u)",
                    highlight,
                    darkColor,
                    Alignment.LMID,
                    4f);
            body.addPara(
                    "Battle Group Settings are inherited by all divisions of each type unless overridden on an individual division in the Division Editor. "
                            + "Opening Mission runs for the first 30-60s (%ss); Main Mission runs for the rest of the battle.",
                    4f,
                    gray,
                    highlight,
                    String.valueOf(doctrine.getOpeningDurationSeconds()));

            // Opening Mission Duration (30s - 60s)
            addOpeningDurationRow(
                    body,
                    bodyWidth - 16f,
                    doctrine.getOpeningDurationSeconds(),
                    baseColor,
                    darkColor,
                    positive);

            for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
                int currSpacing = doctrine.getStationSpacing(dt);
                int currInter = doctrine.getDivisionInterval(dt);
                int currStandoff = doctrine.getStandoffRange(dt);
                DivisionMissionType currOpenMis = doctrine.getOpeningMission(dt);
                DivisionMissionType currMainMis = doctrine.getMainMission(dt);
                LineStation currLineStation = doctrine.getLineStation(dt);

                float spacingParagons = currSpacing / (float) PermanentDivisionManager.PARAGON_LENGTH_UNITS;
                float interParagons = currInter / (float) PermanentDivisionManager.PARAGON_LENGTH_UNITS;
                float standoffParagons = currStandoff / (float) PermanentDivisionManager.PARAGON_LENGTH_UNITS;

                String dtTitle = String.format(
                        Locale.ROOT,
                        "%s  —  Station: %s  |  Spacing: %du  |  Interval: %du  |  Standoff: %du  |  %s -> %s",
                        dt.displayName.toUpperCase(Locale.ROOT),
                        currLineStation.displayName,
                        currSpacing,
                        currInter,
                        currStandoff,
                        currOpenMis.shortName,
                        currMainMis.shortName);
                body.addSectionHeading(dtTitle, baseColor, darkColor, Alignment.LMID, 6f);

                // Row 1: Intra-division spacing (in units)
                addUnitModifierRow(
                        body,
                        bodyWidth - 16f,
                        String.format(
                                Locale.ROOT,
                                "Station spacing (ship to ship): %du (%.2fx Paragon)",
                                currSpacing,
                                spacingParagons),
                        currSpacing,
                        50,
                        PermanentDivisionManager.MIN_STATION_SPACING_UNITS,
                        PermanentDivisionManager.MAX_STATION_SPACING_UNITS,
                        STATION_SPACING_PRESETS,
                        "SET_STATION_SPACING:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        positive);

                // Row 2: Inter-division spacing (in units)
                addUnitModifierRow(
                        body,
                        bodyWidth - 16f,
                        String.format(
                                Locale.ROOT,
                                "Division interval (division to division): %du (%.2fx Paragon)",
                                currInter,
                                interParagons),
                        currInter,
                        100,
                        PermanentDivisionManager.MIN_DIVISION_INTERVAL_UNITS,
                        PermanentDivisionManager.MAX_DIVISION_INTERVAL_UNITS,
                        DIVISION_INTERVAL_PRESETS,
                        "SET_DIVISION_INTERVAL:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        positive);

                // Row 3: Line Standoff distance (in units)
                addUnitModifierRow(
                        body,
                        bodyWidth - 16f,
                        String.format(
                                Locale.ROOT,
                                "Standoff range: %du (%.2fx Paragon)",
                                currStandoff,
                                standoffParagons),
                        currStandoff,
                        100,
                        PermanentDivisionManager.MIN_STANDOFF_RANGE_UNITS,
                        PermanentDivisionManager.MAX_STANDOFF_RANGE_UNITS,
                        STANDOFF_RANGE_PRESETS,
                        "SET_STANDOFF_RANGE:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        highlight);

                // Row 4: Preferred Space (Forward, Middle, Back)
                addPreferredSpaceRow(
                        body,
                        bodyWidth - 16f,
                        dt.displayName + " Station in Line: " + currLineStation.displayName,
                        currLineStation,
                        "SET_LINE_STATION:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        positive);

                // Row 5: Opening Mission Drop-Down Menu (30s - 60s)
                addMissionDropdownRow(
                        body,
                        bodyWidth - 16f,
                        dt.displayName + " Opening Mission (0-" + doctrine.getOpeningDurationSeconds() + "s):",
                        "OPEN_MIS_" + dt.name(),
                        currOpenMis,
                        DivisionMissionType.missionsForDoctrineType(dt),
                        "SET_OPEN_MIS:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        positive,
                        gray);

                // Row 6: Main Mission Drop-Down Menu (rest of battle)
                addMissionDropdownRow(
                        body,
                        bodyWidth - 16f,
                        dt.displayName + " Main Mission (rest of battle):",
                        "MAIN_MIS_" + dt.name(),
                        currMainMis,
                        DivisionMissionType.missionsForDoctrineType(dt),
                        "SET_MAIN_MIS:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        highlight,
                        gray);
            }
        }

        if (activeTab == ActiveTab.ALL_DOCTRINE || activeTab == ActiveTab.HULL_LIMITS) {
            body.addSectionHeading(
                    "DIVISION HULL COMPOSITION LIMITS  (Hard Cap: "
                            + PermanentDivisionManager.MAX_DIVISION_SIZE + " Ships / Division)",
                    highlight,
                    darkColor,
                    Alignment.LMID,
                    10f);

            for (DoctrineHullCategory cat : DoctrineHullCategory.values()) {
                int currMin = doctrine.getMin(cat);
                int currMax = doctrine.getMax(cat);

                String secTitle = String.format(
                        "%s  —  Min: %d  |  Max: %d  (Hard Cap: %d)",
                        cat.displayName.toUpperCase(Locale.ROOT),
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
        }

        if (activeTab == ActiveTab.ALL_DOCTRINE || activeTab == ActiveTab.NAMING_SCHEME) {
            addPlayerNamingSchemeSection(
                    body,
                    bodyWidth - 16f,
                    baseColor,
                    darkColor,
                    highlight,
                    positive,
                    gray);
        }

        contentContainer.addUIElement(body).inTL(12f, bodyTop);
    }

    private void addPlayerNamingSchemeSection(
            TooltipMakerAPI body,
            float rowWidth,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive,
            Color gray) {

        PlayerNamingConfig namingCfg = DivisionNomenclature.getPlayerNamingConfig();
        PlayerNamingPreset currentPreset = namingCfg.getPreset();
        NumberingStyle currentNumbering = namingCfg.getNumberingStyle();

        body.addSectionHeading(
                "DIVISION NOMENCLATURE — PLAYER FLEET NAMING SCHEME",
                highlight,
                darkColor,
                Alignment.LMID,
                10f);
        body.addPara(
                "Customize the naming scheme for your fleet's divisions and OTC Commander. "
                        + "Select a faction/naval preset, choose a numbering style, or enter custom prefixes for each division type.",
                4f,
                gray);

        // 1. Naming Preset Dropdown
        boolean expanded = NAMING_PRESET_DROPDOWN_KEY.equals(expandedDropdownKey);
        CustomPanelAPI presetRow = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI presetUi = presetRow.createUIElement(rowWidth, 28f, false);
        presetUi.addPara("Naming Preset:", 4f, baseColor).getPosition().inTL(4f, 4f);

        String toggleAct = "TOGGLE_DD:" + NAMING_PRESET_DROPDOWN_KEY;
        String btnText = (expanded ? "[^] " : "[v] ") + currentPreset.displayName;
        ButtonAPI bToggle = registerButton(
                presetUi.addButton(
                        btnText,
                        toggleAct,
                        positive,
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        560f,
                        22f,
                        0f),
                toggleAct);
        if (expanded) bToggle.highlight();
        bToggle.getPosition().inTL(220f, 3f);

        ButtonAPI bApplyExisting = registerButton(
                presetUi.addButton(
                        "Apply Scheme to Existing Divisions",
                        "APPLY_NAMING_TO_EXISTING",
                        highlight,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        196f,
                        22f,
                        0f),
                "APPLY_NAMING_TO_EXISTING");
        bApplyExisting.getPosition().inTL(rowWidth - 200f, 3f);

        presetUi.setHeightSoFar(28f);
        presetRow.addUIElement(presetUi).inTL(0f, 0f);
        body.addCustom(presetRow, 3f);

        if (expanded) {
            for (PlayerNamingPreset opt : PlayerNamingPreset.values()) {
                boolean isCurrent = (opt == currentPreset);
                CustomPanelAPI optPanel = Global.getSettings().createCustom(rowWidth, 38f, subPanelPlugin);
                TooltipMakerAPI optUi = optPanel.createUIElement(rowWidth, 22f, false);
                String selAct = "SET_NAMING_PRESET:" + opt.name();
                ButtonAPI bOpt = registerButton(
                        optUi.addButton(
                                (isCurrent ? "* " : "   ") + opt.displayName,
                                selAct,
                                isCurrent ? positive : baseColor,
                                darkColor,
                                Alignment.LMID,
                                CutStyle.ALL,
                                460f,
                                20f,
                                0f),
                        selAct);
                if (isCurrent) bOpt.highlight();
                bOpt.getPosition().inTL(235f, 1f);
                optUi.setHeightSoFar(22f);
                optPanel.addUIElement(optUi).inTL(0f, 0f);

                TooltipMakerAPI optDesc = optPanel.createUIElement(rowWidth - 245f, 18f, false);
                optDesc.addPara(opt.summary, 0f, gray);
                optPanel.addUIElement(optDesc).inTL(235f, 21f);
                body.addCustom(optPanel, 1f);
            }
        } else {
            CustomPanelAPI descPanel = Global.getSettings().createCustom(rowWidth, 22f, subPanelPlugin);
            TooltipMakerAPI descUi = descPanel.createUIElement(rowWidth - 230f, 22f, false);
            descUi.addPara(currentPreset.summary, 0f, gray);
            descPanel.addUIElement(descUi).inTL(220f, 0f);
            body.addCustom(descPanel, 2f);
        }

        // 2. Numbering Style Row
        CustomPanelAPI numRow = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI numUi = numRow.createUIElement(rowWidth, 28f, false);
        numUi.addPara("Numbering Style:", 4f, baseColor).getPosition().inTL(4f, 4f);

        float xCursor = 220f;
        for (NumberingStyle style : NumberingStyle.values()) {
            boolean selected = (style == currentNumbering);
            String act = "SET_NUMBERING_STYLE:" + style.name();
            ButtonAPI bStyle = registerButton(
                    numUi.addButton(
                            style.displayName,
                            act,
                            selected ? positive : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            146f,
                            21f,
                            0f),
                    act);
            if (selected) bStyle.highlight();
            bStyle.getPosition().inTL(xCursor, 3f);
            xCursor += 151f;
        }
        numUi.setHeightSoFar(28f);
        numRow.addUIElement(numUi).inTL(0f, 0f);
        body.addCustom(numRow, 4f);

        // 3. Custom OTC Commander Title Row
        CustomPanelAPI otcTitleRow = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI otcTitleUi = otcTitleRow.createUIElement(rowWidth, 28f, false);
        otcTitleUi.addPara("Custom OTC Title (optional):", 4f, baseColor).getPosition().inTL(4f, 4f);
        customOtcTitleField = otcTitleUi.addTextField(240f, 22f, "graphics/fonts/insignia15LTaa.fnt", 0f);
        customOtcTitleField.setText(namingCfg.getCustomOtcTitle());
        customOtcTitleField.getPosition().inTL(220f, 3f);

        String resolvedOtcPreview = DivisionNomenclature.resolveOtcCommanderTitle(
                currentPreset.category,
                true,
                false,
                "Player OTC");
        otcTitleUi.addPara(
                "Active OTC Tag: [%s]",
                4f,
                gray,
                positive,
                resolvedOtcPreview).getPosition().inTL(475f, 4f);

        ButtonAPI bSavePrefixes = registerButton(
                otcTitleUi.addButton(
                        "Save Custom Prefixes",
                        "SAVE_CUSTOM_PREFIXES",
                        positive,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        165f,
                        22f,
                        0f),
                "SAVE_CUSTOM_PREFIXES");
        bSavePrefixes.getPosition().inTL(rowWidth - 170f, 3f);

        otcTitleUi.setHeightSoFar(28f);
        otcTitleRow.addUIElement(otcTitleUi).inTL(0f, 0f);
        body.addCustom(otcTitleRow, 4f);

        // 4. Per-Archetype Custom Prefix Rows
        body.addSectionHeading(
                "CUSTOM DIVISION PREFIXES BY TYPE  (Edit text & click 'Save Custom Prefixes' or 'Apply Scheme to Existing Divisions')",
                baseColor,
                darkColor,
                Alignment.LMID,
                6f);

        for (DivisionArchetype arch : CUSTOMIZABLE_ARCHETYPES) {
            CustomPanelAPI archRow = Global.getSettings().createCustom(rowWidth, 26f, subPanelPlugin);
            TooltipMakerAPI archUi = archRow.createUIElement(rowWidth, 26f, false);

            archUi.addPara(arch.shortPrefix + ":", 4f, baseColor).getPosition().inTL(4f, 3f);

            TextFieldAPI tf = archUi.addTextField(230f, 21f, "graphics/fonts/insignia15LTaa.fnt", 0f);
            tf.setText(namingCfg.getPrefixForArchetype(arch));
            tf.getPosition().inTL(220f, 2f);
            prefixTextFields.put(arch, tf);

            String sample1 = DivisionNomenclature.formatPlayerDivisionName(arch, 1);
            String sample2 = DivisionNomenclature.formatPlayerDivisionName(arch, 2);
            archUi.addPara(
                    "Preview:  \"%s\",  \"%s\"",
                    4f,
                    gray,
                    highlight,
                    sample1,
                    sample2).getPosition().inTL(465f, 3f);

            archUi.setHeightSoFar(26f);
            archRow.addUIElement(archUi).inTL(0f, 0f);
            body.addCustom(archRow, 2f);
        }
    }

    private void addOpeningDurationRow(
            TooltipMakerAPI body,
            float rowWidth,
            int currentSeconds,
            Color baseColor,
            Color darkColor,
            Color activeValueColor) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 26f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 26f, false);

        String label = String.format(Locale.ROOT, "Opening Mission Window (30s-60s): %ds", currentSeconds);
        rowUi.addPara(label, 4f, activeValueColor, label).getPosition().inTL(4f, 4f);

        float xCursor = 355f;
        for (int sec : OPENING_DURATION_PRESETS) {
            boolean selected = (sec == currentSeconds);
            String act = "SET_OPEN_DUR:" + sec;
            ButtonAPI bSec = registerButton(
                    rowUi.addButton(
                            sec + "s",
                            act,
                            selected ? activeValueColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            62f,
                            21f,
                            0f),
                    act);
            if (selected) {
                bSec.highlight();
            }
            bSec.getPosition().inTL(xCursor, 2f);
            xCursor += 68f;
        }

        rowUi.setHeightSoFar(26f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 3f);
    }

    private void addAuthorityDropdownRow(
            TooltipMakerAPI body,
            float rowWidth,
            OtcAuthority current,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {

        boolean expanded = OTC_AUTHORITY_DROPDOWN_KEY.equals(expandedDropdownKey);

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 26f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 26f, false);
        rowUi.addPara("OTC authority:", 4f, baseColor).getPosition().inTL(4f, 4f);

        String toggleAct = "TOGGLE_DD:" + OTC_AUTHORITY_DROPDOWN_KEY;
        String btnText = (expanded ? "[^] " : "[v] ") + current.dropdownLabel + "  (Click to change)";
        ButtonAPI bToggle = registerButton(
                rowUi.addButton(
                        btnText,
                        toggleAct,
                        activeValueColor,
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        420f,
                        22f,
                        0f),
                toggleAct);
        if (expanded) {
            bToggle.highlight();
        }
        bToggle.getPosition().inTL(355f, 2f);
        rowUi.setHeightSoFar(26f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);

        if (expanded) {
            for (OtcAuthority option : OtcAuthority.values()) {
                boolean isCurrent = (option == current);
                CustomPanelAPI optPanel = Global.getSettings().createCustom(rowWidth, 42f, subPanelPlugin);
                TooltipMakerAPI optUi = optPanel.createUIElement(rowWidth, 22f, false);
                String selAct = "SET_OTC_AUTH:" + option.name();
                ButtonAPI bOpt = registerButton(
                        optUi.addButton(
                                (isCurrent ? "* " : "   ") + option.dropdownLabel,
                                selAct,
                                isCurrent ? activeValueColor : baseColor,
                                darkColor,
                                Alignment.LMID,
                                CutStyle.ALL,
                                285f,
                                20f,
                                0f),
                        selAct);
                if (isCurrent) {
                    bOpt.highlight();
                }
                bOpt.getPosition().inTL(370f, 1f);
                optUi.setHeightSoFar(22f);
                optPanel.addUIElement(optUi).inTL(0f, 0f);

                TooltipMakerAPI optDesc = optPanel.createUIElement(rowWidth - 380f, 20f, false);
                optDesc.addPara(option.summary, 0f, gray);
                optPanel.addUIElement(optDesc).inTL(370f, 22f);
                body.addCustom(optPanel, 1f);
            }
        } else {
            CustomPanelAPI descPanel = Global.getSettings().createCustom(rowWidth, 34f, subPanelPlugin);
            TooltipMakerAPI descUi = descPanel.createUIElement(rowWidth - 365f, 34f, false);
            descUi.addPara(current.summary, 0f, gray);
            descPanel.addUIElement(descUi).inTL(355f, 0f);
            body.addCustom(descPanel, 1f);
        }
    }

    private void addPlayToggleRow(
            TooltipMakerAPI body,
            float rowWidth,
            OtcPlay play,
            boolean enabled,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 36f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 26f, false);

        String label = "Play: " + play.displayName;
        rowUi.addPara(label, 4f, enabled ? activeValueColor : gray, label).getPosition().inTL(4f, 4f);

        String onAct = "SET_OTC_PLAY:" + play.name() + ":ON";
        ButtonAPI bOn = registerButton(
                rowUi.addButton(
                        "On",
                        onAct,
                        enabled ? activeValueColor : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        52f,
                        21f,
                        0f),
                onAct);
        if (enabled) bOn.highlight();
        bOn.getPosition().inTL(355f, 2f);

        String offAct = "SET_OTC_PLAY:" + play.name() + ":OFF";
        ButtonAPI bOff = registerButton(
                rowUi.addButton(
                        "Off",
                        offAct,
                        !enabled ? Misc.getNegativeHighlightColor() : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        52f,
                        21f,
                        0f),
                offAct);
        if (!enabled) bOff.highlight();
        bOff.getPosition().inTL(413f, 2f);

        rowUi.setHeightSoFar(26f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);

        TooltipMakerAPI descUi = rowPanel.createUIElement(rowWidth - 490f, 34f, false);
        descUi.addPara(play.summary, 0f, gray);
        rowPanel.addUIElement(descUi).inTL(480f, 2f);

        body.addCustom(rowPanel, 2f);
    }

    private void addPreferredSpaceRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            LineStation currentSpace,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 26f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 26f, false);

        rowUi.addPara(labelText, 4f, activeValueColor, labelText).getPosition().inTL(4f, 4f);

        float xCursor = 355f;
        for (LineStation ps : LineStation.values()) {
            boolean selected = (ps == currentSpace);
            String act = actionPrefix + ps.name();
            ButtonAPI bPs = registerButton(
                    rowUi.addButton(
                            ps.displayName,
                            act,
                            selected ? activeValueColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            110f,
                            21f,
                            0f),
                    act);
            if (selected) {
                bPs.highlight();
            }
            bPs.getPosition().inTL(xCursor, 2f);
            xCursor += 118f;
        }

        rowUi.setHeightSoFar(26f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);
    }

    private void addMissionDropdownRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            String dropdownKey,
            DivisionMissionType currentMission,
            DivisionMissionType[] primaryOptions,
            String selectActionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {

        boolean expanded = dropdownKey.equals(expandedDropdownKey);

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 26f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 26f, false);

        rowUi.addPara(labelText, 4f, baseColor).getPosition().inTL(4f, 4f);

        String toggleAct = "TOGGLE_DD:" + dropdownKey;
        String btnText = (expanded ? "[^] " : "[v] ") + currentMission.dropdownLabel + "  (Click to change)";
        ButtonAPI bToggle = registerButton(
                rowUi.addButton(
                        btnText,
                        toggleAct,
                        activeValueColor,
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        420f,
                        22f,
                        0f),
                toggleAct);
        if (expanded) {
            bToggle.highlight();
        }
        bToggle.getPosition().inTL(355f, 2f);

        rowUi.setHeightSoFar(26f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);

        if (expanded) {
            DivisionMissionType[] optionsToShow = (primaryOptions != null && primaryOptions.length > 0)
                    ? primaryOptions
                    : DivisionMissionType.values();
            for (DivisionMissionType option : optionsToShow) {
                boolean isCurrent = (option == currentMission);
                CustomPanelAPI optPanel = Global.getSettings().createCustom(rowWidth, 24f, subPanelPlugin);
                TooltipMakerAPI optUi = optPanel.createUIElement(rowWidth, 24f, false);

                String selAct = selectActionPrefix + option.name();
                ButtonAPI bOpt = registerButton(
                        optUi.addButton(
                                (isCurrent ? "* " : "   ") + option.dropdownLabel,
                                selAct,
                                isCurrent ? activeValueColor : baseColor,
                                darkColor,
                                Alignment.LMID,
                                CutStyle.ALL,
                                285f,
                                20f,
                                0f),
                        selAct);
                if (isCurrent) {
                    bOpt.highlight();
                }
                bOpt.getPosition().inTL(370f, 2f);

                optUi.addPara(option.summary, 3f, gray).getPosition().inTL(665f, 3f);

                optUi.setHeightSoFar(24f);
                optPanel.addUIElement(optUi).inTL(0f, 0f);
                body.addCustom(optPanel, 1f);
            }
        }
    }

    private void addUnitModifierRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            int currentValue,
            int step,
            int minVal,
            int maxVal,
            int[] presets,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 26f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 26f, false);

        rowUi.addPara(labelText, 4f, activeValueColor, labelText).getPosition().inTL(4f, 4f);

        float xCursor = 355f;

        // [-step] button
        String decAct = actionPrefix + Math.max(minVal, currentValue - step);
        ButtonAPI bDec = registerButton(
                rowUi.addButton(
                        "-" + step + "u",
                        decAct,
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        48f,
                        21f,
                        0f),
                decAct);
        bDec.setEnabled(currentValue > minVal);
        bDec.getPosition().inTL(xCursor, 2f);
        xCursor += 52f;

        for (int p : presets) {
            boolean selected = Math.abs(p - currentValue) < 10;
            String valAct = actionPrefix + p;
            ButtonAPI bVal = registerButton(
                    rowUi.addButton(
                            p + "u",
                            valAct,
                            selected ? activeValueColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            56f,
                            21f,
                            0f),
                    valAct);
            if (selected) {
                bVal.highlight();
            }
            bVal.getPosition().inTL(xCursor, 2f);
            xCursor += 60f;
        }

        // [+step] button
        String incAct = actionPrefix + Math.min(maxVal, currentValue + step);
        ButtonAPI bInc = registerButton(
                rowUi.addButton(
                        "+" + step + "u",
                        incAct,
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        48f,
                        21f,
                        0f),
                incAct);
        bInc.setEnabled(currentValue < maxVal);
        bInc.getPosition().inTL(xCursor + 2f, 2f);

        rowUi.setHeightSoFar(26f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);
    }

    private void addStationKeepingRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            float currentValue,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 26f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 26f, false);

        rowUi.addPara(labelText, 4f, activeValueColor, labelText).getPosition().inTL(4f, 4f);

        float xCursor = 355f;

        for (float p : STATION_KEEPING_PRESETS) {
            boolean selected = Math.abs(p - currentValue) < 0.04f;
            String formatted = String.format(Locale.ROOT, "%.2fx", p);
            String valAct = actionPrefix + formatted;
            ButtonAPI bVal = registerButton(
                    rowUi.addButton(
                            formatted,
                            valAct,
                            selected ? activeValueColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            68f,
                            21f,
                            0f),
                    valAct);
            if (selected) {
                bVal.highlight();
            }
            bVal.getPosition().inTL(xCursor, 2f);
            xCursor += 74f;
        }

        rowUi.setHeightSoFar(26f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);
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

    private void buildConfirmationScreen(
            CustomPanelAPI parent,
            Color baseColor,
            Color darkColor,
            Color highlight) {

        float boxW = 600f;
        float boxH = 220f;
        float boxX = Math.round((DIALOG_WIDTH - boxW) * 0.5f);
        float boxY = Math.round((DIALOG_HEIGHT - boxH) * 0.5f);

        CustomPanelAPI confirmBox = Global.getSettings().createCustom(boxW, boxH, subPanelPlugin);
        TooltipMakerAPI ui = confirmBox.createUIElement(boxW, boxH, false);

        ui.setTitleOrbitronLarge();
        ui.addTitle("ARE YOU SURE?", highlight);
        ui.addPara(
                "Reinitialize sector-wide division lists for all AI fleets across the sector? "
                        + "Story and special fleets will be prioritized first for low-numbered permanent divisions, "
                        + "and faction division record tables will be rebuilt.",
                10f,
                Misc.getTextColor());

        ButtonAPI bYes = registerButton(
                ui.addButton(
                        "Yes, Reinitialize Sector Lists",
                        "CONFIRM_YES",
                        Misc.getPositiveHighlightColor(),
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        230f,
                        30f,
                        0f),
                "CONFIRM_YES");
        bYes.getPosition().inTL(boxW * 0.5f - 240f, boxH - 46f);

        ButtonAPI bNo = registerButton(
                ui.addButton(
                        "Cancel",
                        "CONFIRM_NO",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        180f,
                        30f,
                        0f),
                "CONFIRM_NO");
        bNo.getPosition().inTL(boxW * 0.5f + 20f, boxH - 46f);

        ui.setHeightSoFar(boxH);
        confirmBox.addUIElement(ui).inTL(0f, 0f);
        parent.addComponent(confirmBox).inTL(boxX, boxY);
    }

    private void handleAction(String cmd) {
        if ("CLOSE".equals(cmd)) {
            close();
            return;
        }
        if ("CONFIRM_NO".equals(cmd)) {
            pendingConfirmationCmd = null;
            rebuildContent();
            return;
        }
        if ("CONFIRM_YES".equals(cmd)) {
            String confirmed = pendingConfirmationCmd;
            pendingConfirmationCmd = null;
            if ("REINIT_SECTOR_DIVISIONS".equals(confirmed)) {
                if (Global.getSector() != null) {
                    lastReinitFleetCount = FactionFleetDoctrines.reinitializeSectorWideDivisionLists(Global.getSector());
                }
            }
            rebuildContent();
            return;
        }
        if ("REINIT_SECTOR_DIVISIONS".equals(cmd)) {
            pendingConfirmationCmd = "REINIT_SECTOR_DIVISIONS";
            rebuildContent();
            return;
        }
        // Save any edits in custom prefix fields before switching tabs or applying actions
        commitCustomPrefixTextFields();

        if ("TAB:ALL".equals(cmd)) {
            activeTab = ActiveTab.ALL_DOCTRINE;
            rebuildContent();
            return;
        }
        if ("TAB:OTC_COMMAND".equals(cmd)) {
            activeTab = ActiveTab.BATTLE_GROUP_COMMAND;
            rebuildContent();
            return;
        }
        if ("TAB:NAMING".equals(cmd)) {
            activeTab = ActiveTab.NAMING_SCHEME;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_NAMING_PRESET:")) {
            PlayerNamingPreset preset = PlayerNamingPreset.fromId(
                    cmd.substring("SET_NAMING_PRESET:".length()),
                    PlayerNamingPreset.NAVAL_STANDARD);
            PlayerNamingConfig cfg = DivisionNomenclature.getPlayerNamingConfig();
            cfg.clearCustomPrefixes();
            cfg.setPreset(preset);
            prefixTextFields.clear();
            customOtcTitleField = null;
            expandedDropdownKey = null;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_NUMBERING_STYLE:")) {
            NumberingStyle style = NumberingStyle.fromId(
                    cmd.substring("SET_NUMBERING_STYLE:".length()),
                    NumberingStyle.ARABIC);
            DivisionNomenclature.getPlayerNamingConfig().setNumberingStyle(style);
            rebuildContent();
            return;
        }
        if ("SAVE_CUSTOM_PREFIXES".equals(cmd)) {
            rebuildContent();
            return;
        }
        if ("APPLY_NAMING_TO_EXISTING".equals(cmd)) {
            PermanentDivisionManager.applyPlayerNamingSchemeToExistingDivisions();
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_OTC_AUTH:")) {
            OtcAuthority auth = OtcAuthority.fromId(cmd.substring("SET_OTC_AUTH:".length()), null);
            if (auth != null) {
                PermanentDivisionManager.updateOtcAuthority(auth);
            }
            expandedDropdownKey = null;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_OTC_PLAY:")) {
            String rest = cmd.substring("SET_OTC_PLAY:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                OtcPlay play = OtcPlay.valueOf(rest.substring(0, sep));
                PermanentDivisionManager.updateOtcPlay(play, "ON".equals(rest.substring(sep + 1)));
                rebuildContent();
            }
            return;
        }
        if ("TAB:MODIFIERS".equals(cmd)) {
            activeTab = ActiveTab.DIVISION_MODIFIERS;
            rebuildContent();
            return;
        }
        if ("TAB:HULLS".equals(cmd)) {
            activeTab = ActiveTab.HULL_LIMITS;
            rebuildContent();
            return;
        }
        if ("RESET_DEFAULTS".equals(cmd)) {
            PermanentDivisionManager.resetFleetDoctrineToDefaults();
            expandedDropdownKey = null;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("TOGGLE_DD:")) {
            String key = cmd.substring("TOGGLE_DD:".length());
            expandedDropdownKey = key.equals(expandedDropdownKey) ? null : key;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_OPEN_DUR:")) {
            int sec = Integer.parseInt(cmd.substring("SET_OPEN_DUR:".length()));
            PermanentDivisionManager.updateOpeningDurationSeconds(sec);
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_STATION_SPACING:")) {
            String rest = cmd.substring("SET_STATION_SPACING:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                String dtStr = rest.substring(0, sep);
                int val = Integer.parseInt(rest.substring(sep + 1));
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(dtStr);
                PermanentDivisionManager.updateDivisionTypeStationSpacing(dt, val);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_DIVISION_INTERVAL:")) {
            String rest = cmd.substring("SET_DIVISION_INTERVAL:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                String dtStr = rest.substring(0, sep);
                int val = Integer.parseInt(rest.substring(sep + 1));
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(dtStr);
                PermanentDivisionManager.updateDivisionTypeDivisionInterval(dt, val);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_STANDOFF_RANGE:")) {
            String rest = cmd.substring("SET_STANDOFF_RANGE:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                String dtStr = rest.substring(0, sep);
                int val = Integer.parseInt(rest.substring(sep + 1));
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(dtStr);
                PermanentDivisionManager.updateDivisionTypeStandoffRange(dt, val);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_LINE_STATION:")) {
            String rest = cmd.substring("SET_LINE_STATION:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                String dtStr = rest.substring(0, sep);
                String psStr = rest.substring(sep + 1);
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(dtStr);
                LineStation ps = LineStation.valueOf(psStr);
                PermanentDivisionManager.updateDivisionTypeLineStation(dt, ps);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_OPEN_MIS:")) {
            String rest = cmd.substring("SET_OPEN_MIS:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                String dtStr = rest.substring(0, sep);
                String misStr = rest.substring(sep + 1);
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(dtStr);
                DivisionMissionType mis = DivisionMissionType.valueOf(misStr);
                PermanentDivisionManager.updateDivisionTypeMission(dt, true, mis);
                expandedDropdownKey = null;
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_MAIN_MIS:")) {
            String rest = cmd.substring("SET_MAIN_MIS:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                String dtStr = rest.substring(0, sep);
                String misStr = rest.substring(sep + 1);
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(dtStr);
                DivisionMissionType mis = DivisionMissionType.valueOf(misStr);
                PermanentDivisionManager.updateDivisionTypeMission(dt, false, mis);
                expandedDropdownKey = null;
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_STATION_KEEPING:")) {
            String rest = cmd.substring("SET_STATION_KEEPING:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                String dtStr = rest.substring(0, sep);
                float val = Float.parseFloat(rest.substring(sep + 1));
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(dtStr);
                PermanentDivisionManager.updateDivisionTypeStationKeeping(dt, val);
                rebuildContent();
            }
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
