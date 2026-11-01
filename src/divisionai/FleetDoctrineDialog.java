package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.ScrollPanelAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionNomenclature.NumberingStyle;
import divisionai.DivisionNomenclature.PlayerNamingConfig;
import divisionai.DivisionNomenclature.PlayerNamingPreset;
import divisionai.PermanentDivisionManager.AutoAssignCapability;
import divisionai.PermanentDivisionManager.AutoAssignHull;
import divisionai.PermanentDivisionManager.DoctrineDivisionType;
import divisionai.PermanentDivisionManager.DoctrineHullCategory;
import divisionai.PermanentDivisionManager.DivisionFormation;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.DivisionMovementStickiness;
import divisionai.PermanentDivisionManager.DivisionSetpieceAttack;
import divisionai.PermanentDivisionManager.FleetDoctrineConfig;
import divisionai.PermanentDivisionManager.FleetWideFormation;
import divisionai.PermanentDivisionManager.MultiDivisionSetpiece;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.OtcPlay;
import divisionai.PermanentDivisionManager.LineStation;
import divisionai.PermanentDivisionManager.SquadronFormation;
import divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode;
import divisionai.PermanentDivisionManager.VoidstrikeTargetObservance;
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

    public static final float DIALOG_WIDTH = 1320f;
    public static final float DIALOG_HEIGHT = 800f;
    private static final float CONTROL_COL_X = 210f;

    private static final int[] STATION_SPACING_PRESETS = {200, 300, 400, 500, 600, 750, 900, 1100};
    private static final int[] DIVISION_INTERVAL_PRESETS = {500, 700, 900, 1100, 1300, 1500, 1800, 2100};
    private static final int[] STANDOFF_RANGE_PRESETS = {450, 650, 800, 950, 1150, 1400, 1700, 2000};
    private static final int[] OPENING_DURATION_PRESETS = {30, 35, 40, 45, 50, 55, 60};

    private enum ActiveTab {
        ALL_DOCTRINE,
        BATTLE_GROUP_COMMAND,
        DIVISION_MODIFIERS,
        AUTO_ASSIGN_SCHEMES,
        HULL_LIMITS,
        NAMING_SCHEME
    }

    private static final String OTC_AUTHORITY_DROPDOWN_KEY = "OTC_AUTHORITY";
    private static final String NAMING_PRESET_DROPDOWN_KEY = "NAMING_PRESET";
    private static final DivisionArchetype[] CUSTOMIZABLE_ARCHETYPES = {
            DivisionArchetype.BATTLESHIP_DIVISION,
            DivisionArchetype.MISSILE_BATTLESHIP_DIVISION,
            DivisionArchetype.FLEET_CARRIER_DIVISION,
            DivisionArchetype.LIGHT_CARRIER_DIVISION,
            DivisionArchetype.ESCORT_CARRIER_DIVISION,
            DivisionArchetype.CARRIER_GROUP,
            DivisionArchetype.CRUISER_DIVISION,
            DivisionArchetype.MISSILE_CRUISER_DIVISION,
            DivisionArchetype.STRIKE_CRUISER_DIVISION,
            DivisionArchetype.DESTROYER_DIVISION,
            DivisionArchetype.MISSILE_DESTROYER_DIVISION,
            DivisionArchetype.FRIGATE_DIVISION,
            DivisionArchetype.MISSILE_FRIGATE_DIVISION,
            DivisionArchetype.PHASE_DIVISION,
            DivisionArchetype.SERVICE_GROUP,
            DivisionArchetype.FORCE_DIVISION
    };

    private final UIPanelAPI parentCoreUI;
    private CustomPanelAPI rootOverlayPanel;
    private CustomPanelAPI contentContainer;
    private ScrollPanelAPI activeBodyScroller = null;
    private float savedScrollY = 0f;
    private boolean closed = false;
    private ActiveTab activeTab = ActiveTab.ALL_DOCTRINE;
    private String expandedDropdownKey = null;
    private String pendingConfirmationCmd = null;
    private int lastReinitFleetCount = -1;
    private int lastAppliedDoctrineFleetCount = -1;
    private int lastAppliedNamingFleetCount = -1;

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
        PlayerNamingConfig prevSnapshot = cfg.copy();
        if (customOtcTitleField != null) {
            cfg.setCustomOtcTitle(customOtcTitleField.getText());
        }
        boolean anyPrefixChanged = false;
        for (Map.Entry<DivisionArchetype, TextFieldAPI> e : prefixTextFields.entrySet()) {
            DivisionArchetype arch = e.getKey();
            TextFieldAPI tf = e.getValue();
            if (arch == null || tf == null) continue;
            String typed = tf.getText() != null ? tf.getText().trim() : "";
            String presetDefault = DivisionNomenclature.getDefaultPrefixForPreset(cfg.getPreset(), arch);
            String before = cfg.getPrefixForArchetype(arch);
            if (typed.isEmpty() || typed.equals(presetDefault)) {
                cfg.setCustomPrefixForArchetype(arch, null);
            } else {
                cfg.setCustomPrefixForArchetype(arch, typed);
            }
            if (!cfg.getPrefixForArchetype(arch).equals(before)) {
                anyPrefixChanged = true;
            }
        }
        if (anyPrefixChanged) {
            PermanentDivisionManager.updateDefaultNamedPlayerDivisionsAfterNamingChange(prevSnapshot);
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
        if (activeBodyScroller != null) {
            savedScrollY = Math.max(0f, activeBodyScroller.getYOffset());
            activeBodyScroller = null;
        }
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

        // 1. Header & Subtitle
        TooltipMakerAPI header = contentContainer.createUIElement(DIALOG_WIDTH - 64f, 48f, false);
        header.setTitleOrbitronLarge();
        header.addTitle("FLEET DOCTRINE", baseColor);
        header.addPara(
                "OTC command, division defaults, auto-assigner, hull limits (max %s/div), and nomenclature.",
                3f,
                highlight,
                String.valueOf(PermanentDivisionManager.MAX_DIVISION_SIZE));
        header.setHeightSoFar(48f);
        contentContainer.addUIElement(header).inTL(12f, 8f);

        // 2. Top Toolbar (Left: 5 Filter Tabs with 8px gaps | Right: 3 Action Buttons with 150px separation)
        float toolbarW = DIALOG_WIDTH - 24f;
        CustomPanelAPI toolbarRow = Global.getSettings().createCustom(toolbarW, 30f, subPanelPlugin);
        TooltipMakerAPI tb = toolbarRow.createUIElement(toolbarW, 30f, false);

        ButtonAPI bTabAll = registerButton(
                tb.addButton(
                        "All",
                        "TAB:ALL",
                        activeTab == ActiveTab.ALL_DOCTRINE ? highlight : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        72f,
                        24f,
                        0f),
                "TAB:ALL");
        if (activeTab == ActiveTab.ALL_DOCTRINE) bTabAll.highlight();
        bTabAll.getPosition().inTL(0f, 3f);

        ButtonAPI bTabOtc = registerButton(
                tb.addButton(
                        "OTC Command",
                        "TAB:OTC_COMMAND",
                        activeTab == ActiveTab.BATTLE_GROUP_COMMAND ? highlight : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        126f,
                        24f,
                        0f),
                "TAB:OTC_COMMAND");
        if (activeTab == ActiveTab.BATTLE_GROUP_COMMAND) bTabOtc.highlight();
        bTabOtc.getPosition().inTL(78f, 3f);

        ButtonAPI bTabMod = registerButton(
                tb.addButton(
                        "Division Defaults",
                        "TAB:MODIFIERS",
                        activeTab == ActiveTab.DIVISION_MODIFIERS ? highlight : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        142f,
                        24f,
                        0f),
                "TAB:MODIFIERS");
        if (activeTab == ActiveTab.DIVISION_MODIFIERS) bTabMod.highlight();
        bTabMod.getPosition().inTL(210f, 3f);

        ButtonAPI bTabAuto = registerButton(
                tb.addButton(
                        "Auto-Assigner",
                        "TAB:AUTO_ASSIGN",
                        activeTab == ActiveTab.AUTO_ASSIGN_SCHEMES ? highlight : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        126f,
                        24f,
                        0f),
                "TAB:AUTO_ASSIGN");
        if (activeTab == ActiveTab.AUTO_ASSIGN_SCHEMES) bTabAuto.highlight();
        bTabAuto.getPosition().inTL(358f, 3f);

        ButtonAPI bTabHull = registerButton(
                tb.addButton(
                        "Hull Limits",
                        "TAB:HULLS",
                        activeTab == ActiveTab.HULL_LIMITS ? highlight : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        108f,
                        24f,
                        0f),
                "TAB:HULLS");
        if (activeTab == ActiveTab.HULL_LIMITS) bTabHull.highlight();
        bTabHull.getPosition().inTL(490f, 3f);

        ButtonAPI bTabNaming = registerButton(
                tb.addButton(
                        "Nomenclature",
                        "TAB:NAMING",
                        activeTab == ActiveTab.NAMING_SCHEME ? highlight : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        122f,
                        24f,
                        0f),
                "TAB:NAMING");
        if (activeTab == ActiveTab.NAMING_SCHEME) bTabNaming.highlight();
        bTabNaming.getPosition().inTL(604f, 3f);

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
                        152f,
                        24f,
                        0f),
                "REINIT_SECTOR_DIVISIONS");
        bReinitSector.getPosition().inTL(toolbarW - 116f - 8f - 128f - 8f - 152f, 3f);

        ButtonAPI bReset = registerButton(
                tb.addButton(
                        "Reset Defaults",
                        "RESET_DEFAULTS",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        128f,
                        24f,
                        0f),
                "RESET_DEFAULTS");
        bReset.getPosition().inTL(toolbarW - 116f - 8f - 128f, 3f);

        ButtonAPI bClose = registerButton(
                tb.addButton(
                        "Close [Esc]",
                        "CLOSE",
                        Misc.getNegativeHighlightColor(),
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        116f,
                        24f,
                        0f),
                "CLOSE");
        bClose.getPosition().inTL(toolbarW - 116f, 3f);

        tb.setHeightSoFar(30f);
        toolbarRow.addUIElement(tb).inTL(0f, 0f);
        contentContainer.addComponent(toolbarRow).inTL(12f, 58f);

        if (pendingConfirmationCmd != null) {
            buildConfirmationScreen(contentContainer, baseColor, darkColor, highlight);
            return;
        }

        // 3. Scrollable Body
        float bodyTop = 94f;
        float bodyHeight = DIALOG_HEIGHT - bodyTop - 14f;
        float bodyWidth = DIALOG_WIDTH - 24f;

        TooltipMakerAPI body = contentContainer.createUIElement(bodyWidth, bodyHeight, true);

        if (activeTab != ActiveTab.NAMING_SCHEME) {
            float factionBarW = bodyWidth - 16f;
            CustomPanelAPI factionBar = Global.getSettings().createCustom(factionBarW, 30f, subPanelPlugin);
            TooltipMakerAPI fbUi = factionBar.createUIElement(factionBarW, 30f, false);
            String statusNote = lastAppliedDoctrineFleetCount >= 0
                    ? ("Applied to personal faction (" + lastAppliedDoctrineFleetCount + " colony fleet(s)).")
                    : "Apply this doctrine to your personal colony fleets.";
            fbUi.addPara(statusNote, 0f, lastAppliedDoctrineFleetCount >= 0 ? positive : gray).getPosition().inTL(6f, 6f);
            ButtonAPI bApplyDocFaction = registerButton(
                    fbUi.addButton(
                            "Apply to Colony Fleets",
                            "APPLY_DOCTRINE_TO_FACTION_FLEETS",
                            highlight,
                            darkColor,
                            Alignment.MID,
                            CutStyle.TL_BR,
                            248f,
                            24f,
                            0f),
                    "APPLY_DOCTRINE_TO_FACTION_FLEETS");
            bApplyDocFaction.getPosition().inTL(factionBarW - 254f, 3f);
            fbUi.setHeightSoFar(30f);
            factionBar.addUIElement(fbUi).inTL(0f, 0f);
            body.addCustom(factionBar, 2f);
        }

        if (activeTab == ActiveTab.ALL_DOCTRINE || activeTab == ActiveTab.BATTLE_GROUP_COMMAND) {
            body.addSectionHeading(
                    "OTC COMMAND & PLAYS",
                    highlight,
                    darkColor,
                    Alignment.LMID,
                    4f);

            addAuthorityDropdownRow(
                    body,
                    bodyWidth - 16f,
                    doctrine.getOtcAuthority(),
                    baseColor,
                    darkColor,
                    positive,
                    gray);

            addFleetWideFormationDropdownRow(
                    body,
                    bodyWidth - 16f,
                    doctrine.getFleetWideFormation(),
                    doctrine.isDeployInFleetFormation(),
                    baseColor,
                    darkColor,
                    positive,
                    gray);

            addMultiDivisionSetpieceDropdownRow(
                    body,
                    bodyWidth - 16f,
                    doctrine.getMultiDivisionSetpiece(),
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
                    "DIVISION DEFAULTS",
                    highlight,
                    darkColor,
                    Alignment.LMID,
                    4f);

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
                DivisionFormation currFormation = doctrine.getFormation(dt);
                DivisionMovementStickiness currStickiness = doctrine.getMovementStickiness(dt);

                String dtTitle = String.format(
                        Locale.ROOT,
                        "%s  |  %s  |  %s  |  %du / %du / %du  |  %s -> %s",
                        dt.displayName.toUpperCase(Locale.ROOT),
                        currLineStation.displayName,
                        currFormation.shortName,
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
                        String.format(Locale.ROOT, "Ship Spacing: %du", currSpacing),
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
                        String.format(Locale.ROOT, "Division Interval: %du", currInter),
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
                        String.format(Locale.ROOT, "Standoff Range: %du", currStandoff),
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
                        "Line Station: " + currLineStation.displayName,
                        currLineStation,
                        "SET_LINE_STATION:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        positive);

                // Row 4b: Naval Formation (with formation icons)
                addFormationSelectorRow(
                        body,
                        bodyWidth - 16f,
                        "Formation: " + currFormation.shortName,
                        currFormation,
                        "SET_FORMATION:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        highlight,
                        gray);

                // Row 4c: Doctrinal Division Movement Stickiness (Division Movement vs. Individual Movement)
                addMovementStickinessRow(
                        body,
                        bodyWidth - 16f,
                        "Cohesion: " + currStickiness.displayName,
                        currStickiness,
                        "SET_STICKINESS:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        positive,
                        gray);

                if (!dt.isCarrierType() && dt != DoctrineDivisionType.SERVICE_GROUP) {
                    DivisionSetpieceAttack currSetpiece = doctrine.getSetpieceAttack(dt);
                    addDivisionSetpieceAttackRow(
                            body,
                            bodyWidth - 16f,
                            "Setpiece: " + currSetpiece.shortName,
                            currSetpiece,
                            "SET_DIV_SETPIECE:" + dt.name() + ":",
                            baseColor,
                            darkColor,
                            highlight,
                            gray);
                }

                // Row 5: Opening Mission Drop-Down Menu (30s - 60s)
                addMissionDropdownRow(
                        body,
                        bodyWidth - 16f,
                        "Opening (0-" + doctrine.getOpeningDurationSeconds() + "s):",
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
                        "Main Mission:",
                        "MAIN_MIS_" + dt.name(),
                        currMainMis,
                        DivisionMissionType.missionsForDoctrineType(dt),
                        "SET_MAIN_MIS:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        highlight,
                        gray);

                // Rows 7a-7g: Carrier & Wing Voidstrike Behavior Doctrine (Items C, D, E, D2, F, G, H)
                VoidstrikeTargetObservance currVsObs = doctrine.getVoidstrikeObservance(dt);
                VoidstrikeCoordinationMode currVsCoord = doctrine.getVoidstrikeCoordination(dt);
                SquadronFormation currFtrFm = doctrine.getFighterFormation(dt);
                SquadronFormation currFtrVsFm = doctrine.getFighterVoidstrikeFormation(dt);
                SquadronFormation currBmbFm = doctrine.getBomberFormation(dt);
                SquadronFormation currBmbVsFm = doctrine.getBomberVoidstrikeFormation(dt);
                int currVsDepth = doctrine.getVoidstrikeSquadronDepth(dt);
                int currVsWidth = doctrine.getVoidstrikeSquadronWidth(dt);

                addVoidstrikeObservanceRow(
                        body,
                        bodyWidth - 16f,
                        "Strike Focus: " + currVsObs.displayName,
                        currVsObs,
                        "SET_VS_OBS:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        positive,
                        gray);

                addVoidstrikeCoordinationRow(
                        body,
                        bodyWidth - 16f,
                        "Strike Launch: " + currVsCoord.displayName,
                        currVsCoord,
                        "SET_VS_COORD:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        highlight,
                        gray);

                addSquadronFormationRow(
                        body,
                        bodyWidth - 16f,
                        "Fighter Form.: " + currFtrFm.shortName,
                        currFtrFm,
                        SquadronFormation.FIGHTER_GENERAL_OPTIONS,
                        "SET_FTR_FM:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        positive);

                addSquadronFormationRow(
                        body,
                        bodyWidth - 16f,
                        "Fighter Strike: " + currFtrVsFm.shortName,
                        currFtrVsFm,
                        SquadronFormation.FIGHTER_VOIDSTRIKE_OPTIONS,
                        "SET_FTR_VS_FM:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        highlight);

                addSquadronFormationRow(
                        body,
                        bodyWidth - 16f,
                        "Bomber Form.: " + currBmbFm.shortName,
                        currBmbFm,
                        SquadronFormation.BOMBER_GENERAL_OPTIONS,
                        "SET_BMB_FM:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        positive);

                addSquadronFormationRow(
                        body,
                        bodyWidth - 16f,
                        "Bomber Strike: " + currBmbVsFm.shortName,
                        currBmbVsFm,
                        SquadronFormation.BOMBER_VOIDSTRIKE_OPTIONS,
                        "SET_BMB_VS_FM:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        highlight);

                addVoidstrikeDepthRow(
                        body,
                        bodyWidth - 16f,
                        "Strike Depth: " + currVsDepth,
                        currVsDepth,
                        "SET_VS_DEPTH:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        positive);

                addVoidstrikeDepthRow(
                        body,
                        bodyWidth - 16f,
                        "Strike Width: " + currVsWidth,
                        currVsWidth,
                        "SET_VS_WIDTH:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        highlight);
            }
        }

        if (activeTab == ActiveTab.ALL_DOCTRINE || activeTab == ActiveTab.AUTO_ASSIGN_SCHEMES) {
            addAutoAssignSchemesSection(
                    body,
                    bodyWidth - 16f,
                    doctrine,
                    baseColor,
                    darkColor,
                    highlight,
                    positive,
                    gray);
        }

        if (activeTab == ActiveTab.ALL_DOCTRINE || activeTab == ActiveTab.HULL_LIMITS) {
            body.addSectionHeading(
                    "HULL LIMITS  (Max " + PermanentDivisionManager.MAX_DIVISION_SIZE + " / Div)",
                    highlight,
                    darkColor,
                    Alignment.LMID,
                    10f);

            for (DoctrineHullCategory cat : DoctrineHullCategory.values()) {
                int currMin = doctrine.getMin(cat);
                int currMax = doctrine.getMax(cat);

                String secTitle = String.format(
                        "%s  (%d - %d ships)",
                        cat.displayName.toUpperCase(Locale.ROOT),
                        currMin,
                        currMax);
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
        activeBodyScroller = body.getExternalScroller();
        if (activeBodyScroller != null && savedScrollY > 0f) {
            float contentH = body.getHeightSoFar();
            if (body.getPosition() != null && body.getPosition().getHeight() > contentH) {
                contentH = body.getPosition().getHeight();
            }
            float maxScroll = Math.max(0f, contentH - bodyHeight);
            if (maxScroll > 0f) {
                activeBodyScroller.setYOffset(Math.max(0f, Math.min(savedScrollY, maxScroll)));
            }
        }
    }

    private void addAutoAssignSchemesSection(
            TooltipMakerAPI body,
            float rowWidth,
            FleetDoctrineConfig doctrine,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive,
            Color gray) {

        body.addSectionHeading(
                "AUTO-ASSIGNER SCHEMES",
                highlight,
                darkColor,
                Alignment.LMID,
                8f);

        body.addSectionHeading(
                "BY CAPABILITY",
                baseColor,
                darkColor,
                Alignment.LMID,
                4f);

        for (AutoAssignCapability cap : AutoAssignCapability.values()) {
            addCapabilitySchemeRow(body, rowWidth, doctrine, cap, baseColor, darkColor, highlight, positive, gray);
        }

        body.addSectionHeading(
                "BY HULL CLASS",
                baseColor,
                darkColor,
                Alignment.LMID,
                8f);

        for (AutoAssignHull hull : AutoAssignHull.values()) {
            addHullSchemeRow(body, rowWidth, doctrine, hull, baseColor, darkColor, highlight, positive, gray);
        }
    }

    private void addCapabilitySchemeRow(
            TooltipMakerAPI body,
            float rowWidth,
            FleetDoctrineConfig doctrine,
            AutoAssignCapability cap,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive,
            Color gray) {
        float rowH = 32f;
        CustomPanelAPI row = Global.getSettings().createCustom(rowWidth, rowH, subPanelPlugin);
        TooltipMakerAPI ui = row.createUIElement(rowWidth, rowH, false);

        boolean capOn = doctrine.isCapabilityEnabled(cap);
        ui.addPara(cap.displayName + ":", 2f).getPosition().inTL(4f, 2f);
        ui.addPara(cap.summary, gray, 1f).getPosition().inTL(4f, 17f);

        float x = 285f;
        ButtonAPI bMaster = registerButton(
                ui.addButton(
                        capOn ? "Capability: ON" : "Capability: OFF",
                        "SET_AA_CAP:" + cap.name() + ":" + (capOn ? "OFF" : "ON"),
                        capOn ? positive : gray,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        136f,
                        22f,
                        0f),
                "SET_AA_CAP:" + cap.name() + ":" + (capOn ? "OFF" : "ON"));
        if (capOn) bMaster.highlight();
        bMaster.getPosition().inTL(x, 5f);
        x += 148f;

        for (AutoAssignHull hull : AutoAssignHull.values()) {
            if (!cap.supportsHull(hull)) continue;
            boolean effectiveOn = doctrine.isSchemeEnabled(cap, hull);
            boolean rawCellOn = doctrine.isRawSchemeCellEnabled(cap, hull);
            boolean nextState = !effectiveOn;
            String cmd = "SET_AA_SCHEME:" + cap.name() + ":" + hull.name() + ":" + (nextState ? "ON" : "OFF");
            ButtonAPI bHull = registerButton(
                    ui.addButton(
                            hull.shortName + ": " + (effectiveOn ? "ON" : "OFF"),
                            cmd,
                            effectiveOn ? highlight : (rawCellOn ? gray : Misc.getNegativeHighlightColor()),
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            132f,
                            22f,
                            0f),
                    cmd);
            if (effectiveOn) bHull.highlight();
            bHull.getPosition().inTL(x, 5f);
            x += 140f;
        }

        ui.setHeightSoFar(rowH);
        row.addUIElement(ui).inTL(0f, 0f);
        body.addCustom(row, 3f);
    }

    private void addHullSchemeRow(
            TooltipMakerAPI body,
            float rowWidth,
            FleetDoctrineConfig doctrine,
            AutoAssignHull hull,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive,
            Color gray) {
        float rowH = 32f;
        CustomPanelAPI row = Global.getSettings().createCustom(rowWidth, rowH, subPanelPlugin);
        TooltipMakerAPI ui = row.createUIElement(rowWidth, rowH, false);

        boolean hullOn = doctrine.isHullEnabled(hull);
        ui.addPara(hull.displayName + " Divisions:", 2f).getPosition().inTL(4f, 6f);

        float x = 285f;
        ButtonAPI bMaster = registerButton(
                ui.addButton(
                        hullOn ? "Hull: ON" : "Hull: OFF",
                        "SET_AA_HULL:" + hull.name() + ":" + (hullOn ? "OFF" : "ON"),
                        hullOn ? positive : gray,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        136f,
                        22f,
                        0f),
                "SET_AA_HULL:" + hull.name() + ":" + (hullOn ? "OFF" : "ON"));
        if (hullOn) bMaster.highlight();
        bMaster.getPosition().inTL(x, 5f);
        x += 148f;

        for (AutoAssignCapability cap : AutoAssignCapability.values()) {
            if (!cap.supportsHull(hull)) continue;
            boolean effectiveOn = doctrine.isSchemeEnabled(cap, hull);
            boolean rawCellOn = doctrine.isRawSchemeCellEnabled(cap, hull);
            boolean nextState = !effectiveOn;
            String cmd = "SET_AA_SCHEME:" + cap.name() + ":" + hull.name() + ":" + (nextState ? "ON" : "OFF");
            ButtonAPI bCap = registerButton(
                    ui.addButton(
                            cap.shortName + ": " + (effectiveOn ? "ON" : "OFF"),
                            cmd,
                            effectiveOn ? highlight : (rawCellOn ? gray : Misc.getNegativeHighlightColor()),
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            116f,
                            22f,
                            0f),
                    cmd);
            if (effectiveOn) bCap.highlight();
            bCap.getPosition().inTL(x, 5f);
            x += 122f;
        }

        ui.setHeightSoFar(rowH);
        row.addUIElement(ui).inTL(0f, 0f);
        body.addCustom(row, 3f);
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
                "FLEET NOMENCLATURE",
                highlight,
                darkColor,
                Alignment.LMID,
                10f);

        // 1. Naming Preset Dropdown
        boolean expanded = NAMING_PRESET_DROPDOWN_KEY.equals(expandedDropdownKey);
        CustomPanelAPI presetRow = Global.getSettings().createCustom(rowWidth, 30f, subPanelPlugin);
        TooltipMakerAPI presetUi = presetRow.createUIElement(rowWidth, 30f, false);
        presetUi.addPara("Naming Preset:", 0f, baseColor).getPosition().inTL(6f, 6f);

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
                        510f,
                        24f,
                        0f),
                toggleAct);
        if (expanded) bToggle.highlight();
        bToggle.getPosition().inTL(CONTROL_COL_X, 3f);

        ButtonAPI bApplyNamingFaction = registerButton(
                presetUi.addButton(
                        "Apply to Colony Fleets",
                        "APPLY_NAMING_TO_FACTION_FLEETS",
                        lastAppliedNamingFleetCount >= 0 ? positive : highlight,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        224f,
                        24f,
                        0f),
                "APPLY_NAMING_TO_FACTION_FLEETS");
        bApplyNamingFaction.getPosition().inTL(rowWidth - 210f - 8f - 224f, 3f);

        ButtonAPI bApplyExisting = registerButton(
                presetUi.addButton(
                        "Rename Fleet Divs",
                        "APPLY_NAMING_TO_EXISTING",
                        highlight,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        204f,
                        24f,
                        0f),
                "APPLY_NAMING_TO_EXISTING");
        bApplyExisting.getPosition().inTL(rowWidth - 210f, 3f);

        presetUi.setHeightSoFar(30f);
        presetRow.addUIElement(presetUi).inTL(0f, 0f);
        body.addCustom(presetRow, 3f);

        if (expanded) {
            for (PlayerNamingPreset opt : PlayerNamingPreset.values()) {
                boolean isCurrent = (opt == currentPreset);
                CustomPanelAPI optPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
                TooltipMakerAPI optUi = optPanel.createUIElement(rowWidth, 28f, false);
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
                                22f,
                                0f),
                        selAct);
                if (isCurrent) bOpt.highlight();
                bOpt.getPosition().inTL(CONTROL_COL_X + 12f, 3f);

                optUi.addPara(opt.summary, 0f, gray).getPosition().inTL(CONTROL_COL_X + 484f, 5f);
                optUi.setHeightSoFar(28f);
                optPanel.addUIElement(optUi).inTL(0f, 0f);
                body.addCustom(optPanel, 2f);
            }
        } else {
            CustomPanelAPI descPanel = Global.getSettings().createCustom(rowWidth, 22f, subPanelPlugin);
            TooltipMakerAPI descUi = descPanel.createUIElement(rowWidth - CONTROL_COL_X - 10f, 22f, false);
            descUi.addPara(currentPreset.summary, 0f, gray);
            descUi.setHeightSoFar(22f);
            descPanel.addUIElement(descUi).inTL(CONTROL_COL_X, 0f);
            body.addCustom(descPanel, 2f);
        }

        // 2. Numbering Style Row
        CustomPanelAPI numRow = Global.getSettings().createCustom(rowWidth, 30f, subPanelPlugin);
        TooltipMakerAPI numUi = numRow.createUIElement(rowWidth, 30f, false);
        numUi.addPara("Numbering Style:", 0f, baseColor).getPosition().inTL(6f, 6f);

        float xCursor = CONTROL_COL_X;
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
                            164f,
                            22f,
                            0f),
                    act);
            if (selected) bStyle.highlight();
            bStyle.getPosition().inTL(xCursor, 4f);
            xCursor += 172f;
        }
        numUi.setHeightSoFar(30f);
        numRow.addUIElement(numUi).inTL(0f, 0f);
        body.addCustom(numRow, 4f);

        // 3. Custom OTC Commander Title Row
        CustomPanelAPI otcTitleRow = Global.getSettings().createCustom(rowWidth, 30f, subPanelPlugin);
        TooltipMakerAPI otcTitleUi = otcTitleRow.createUIElement(rowWidth, 30f, false);
        otcTitleUi.addPara("Custom OTC Title:", 0f, baseColor).getPosition().inTL(6f, 6f);
        customOtcTitleField = otcTitleUi.addTextField(240f, 22f, "graphics/fonts/insignia15LTaa.fnt", 0f);
        customOtcTitleField.setText(namingCfg.getCustomOtcTitle());
        customOtcTitleField.getPosition().inTL(CONTROL_COL_X, 4f);

        String resolvedOtcPreview = DivisionNomenclature.resolveOtcCommanderTitle(
                currentPreset.category,
                true,
                false,
                "Player OTC");
        otcTitleUi.addPara(
                "Active OTC Tag: [%s]",
                0f,
                gray,
                positive,
                resolvedOtcPreview).getPosition().inTL(CONTROL_COL_X + 256f, 6f);

        ButtonAPI bSavePrefixes = registerButton(
                otcTitleUi.addButton(
                        "Save Custom Prefixes",
                        "SAVE_CUSTOM_PREFIXES",
                        positive,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        176f,
                        24f,
                        0f),
                "SAVE_CUSTOM_PREFIXES");
        bSavePrefixes.getPosition().inTL(rowWidth - 182f, 3f);

        otcTitleUi.setHeightSoFar(30f);
        otcTitleRow.addUIElement(otcTitleUi).inTL(0f, 0f);
        body.addCustom(otcTitleRow, 4f);

        // 4. Per-Archetype Custom Prefix Rows
        body.addSectionHeading(
                "DIVISION TYPE PREFIXES",
                baseColor,
                darkColor,
                Alignment.LMID,
                6f);

        for (DivisionArchetype arch : CUSTOMIZABLE_ARCHETYPES) {
            CustomPanelAPI archRow = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
            TooltipMakerAPI archUi = archRow.createUIElement(rowWidth, 28f, false);

            archUi.addPara(arch.shortPrefix + ":", 0f, baseColor).getPosition().inTL(6f, 5f);

            TextFieldAPI tf = archUi.addTextField(240f, 22f, "graphics/fonts/insignia15LTaa.fnt", 0f);
            tf.setText(namingCfg.getPrefixForArchetype(arch));
            tf.getPosition().inTL(CONTROL_COL_X, 3f);
            prefixTextFields.put(arch, tf);

            String sample1 = DivisionNomenclature.formatPlayerDivisionName(arch, 1);
            String sample2 = DivisionNomenclature.formatPlayerDivisionName(arch, 2);
            archUi.addPara(
                    "Preview:  \"%s\",  \"%s\"",
                    0f,
                    gray,
                    highlight,
                    sample1,
                    sample2).getPosition().inTL(CONTROL_COL_X + 256f, 5f);

            archUi.setHeightSoFar(28f);
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

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 28f, false);

        String label = String.format(Locale.ROOT, "Opening Duration: %ds", currentSeconds);
        rowUi.addPara(label, 0f, activeValueColor, label).getPosition().inTL(6f, 5f);

        float xCursor = CONTROL_COL_X;
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
                            68f,
                            22f,
                            0f),
                    act);
            if (selected) {
                bSec.highlight();
            }
            bSec.getPosition().inTL(xCursor, 3f);
            xCursor += 76f;
        }

        rowUi.setHeightSoFar(28f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 4f);
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

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 30f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 30f, false);
        rowUi.addPara("OTC Authority:", 0f, baseColor).getPosition().inTL(6f, 6f);

        String toggleAct = "TOGGLE_DD:" + OTC_AUTHORITY_DROPDOWN_KEY;
        String btnText = (expanded ? "[^] " : "[v] ") + current.dropdownLabel;
        ButtonAPI bToggle = registerButton(
                rowUi.addButton(
                        btnText,
                        toggleAct,
                        activeValueColor,
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        236f,
                        24f,
                        0f),
                toggleAct);
        if (expanded) {
            bToggle.highlight();
        }
        bToggle.getPosition().inTL(CONTROL_COL_X, 3f);

        rowUi.addPara(current.summary, 0f, expanded ? activeValueColor : gray)
                .getPosition()
                .inTL(CONTROL_COL_X + 252f, 6f);

        rowUi.setHeightSoFar(30f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 3f);

        if (expanded) {
            for (OtcAuthority option : OtcAuthority.values()) {
                boolean isCurrent = (option == current);
                CustomPanelAPI optPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
                TooltipMakerAPI optUi = optPanel.createUIElement(rowWidth, 28f, false);
                String selAct = "SET_OTC_AUTH:" + option.name();
                ButtonAPI bOpt = registerButton(
                        optUi.addButton(
                                (isCurrent ? "* " : "   ") + option.dropdownLabel,
                                selAct,
                                isCurrent ? activeValueColor : baseColor,
                                darkColor,
                                Alignment.LMID,
                                CutStyle.ALL,
                                224f,
                                22f,
                                0f),
                        selAct);
                if (isCurrent) {
                    bOpt.highlight();
                }
                bOpt.getPosition().inTL(CONTROL_COL_X + 12f, 3f);

                optUi.addPara(option.summary, 0f, isCurrent ? activeValueColor : gray)
                        .getPosition()
                        .inTL(CONTROL_COL_X + 252f, 5f);

                optUi.setHeightSoFar(28f);
                optPanel.addUIElement(optUi).inTL(0f, 0f);
                body.addCustom(optPanel, 2f);
            }
        }
    }

    private CustomPanelAPI createFleetFormationIconRow(
            float rowWidth,
            float rowHeight,
            final FleetWideFormation formation) {
        final CustomPanelAPI[] ref = new CustomPanelAPI[1];
        BaseCustomUIPanelPlugin plugin = new BaseCustomUIPanelPlugin() {
            @Override
            public void renderBelow(float alphaMult) {
                if (ref[0] == null || ref[0].getPosition() == null) return;
                PositionAPI p = ref[0].getPosition();
                DivisionDeploymentDialogOrganizer.renderFleetFormationIcon(
                        formation, p.getX() + CONTROL_COL_X - 15f, p.getY() + rowHeight * 0.5f, 22f, alphaMult);
            }
        };
        CustomPanelAPI panel = Global.getSettings().createCustom(rowWidth, rowHeight, plugin);
        ref[0] = panel;
        return panel;
    }

    private void addFleetWideFormationDropdownRow(
            TooltipMakerAPI body,
            float rowWidth,
            FleetWideFormation current,
            boolean deployInFormation,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {

        String ddKey = "FLEET_WIDE_FORMATION_DD";
        boolean expanded = ddKey.equals(expandedDropdownKey);

        CustomPanelAPI rowPanel = createFleetFormationIconRow(rowWidth, 30f, current);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 30f, false);
        rowUi.addPara("Fleet Formation:", 0f, baseColor).getPosition().inTL(6f, 6f);

        String toggleAct = "TOGGLE_DD:" + ddKey;
        String btnText = (expanded ? "[^] " : "[v] ") + current.displayName;
        ButtonAPI bToggle = registerButton(
                rowUi.addButton(
                        btnText,
                        toggleAct,
                        activeValueColor,
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        236f,
                        24f,
                        0f),
                toggleAct);
        if (expanded) {
            bToggle.highlight();
        }
        bToggle.getPosition().inTL(CONTROL_COL_X, 3f);

        String depAct = "SET_DEPLOY_IN_FORMATION:" + (deployInFormation ? "OFF" : "ON");
        ButtonAPI bDep = registerButton(
                rowUi.addButton(
                        "Spawn in Form: " + (deployInFormation ? "On" : "Off"),
                        depAct,
                        deployInFormation ? activeValueColor : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        132f,
                        22f,
                        0f),
                depAct);
        if (deployInFormation) {
            bDep.highlight();
        }
        bDep.getPosition().inTL(CONTROL_COL_X + 244f, 4f);

        rowUi.setHeightSoFar(30f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 3f);

        if (expanded) {
            for (FleetWideFormation option : FleetWideFormation.values()) {
                boolean isCurrent = (option == current);
                CustomPanelAPI optPanel = createFleetFormationIconRow(rowWidth, 28f, option);
                TooltipMakerAPI optUi = optPanel.createUIElement(rowWidth, 28f, false);
                String selAct = "SET_FLEET_FORMATION:" + option.name();
                ButtonAPI bOpt = registerButton(
                        optUi.addButton(
                                (isCurrent ? "* " : "   ") + option.displayName,
                                selAct,
                                isCurrent ? activeValueColor : baseColor,
                                darkColor,
                                Alignment.LMID,
                                CutStyle.ALL,
                                224f,
                                22f,
                                0f),
                        selAct);
                if (isCurrent) {
                    bOpt.highlight();
                }
                bOpt.getPosition().inTL(CONTROL_COL_X + 12f, 3f);

                optUi.setHeightSoFar(28f);
                optPanel.addUIElement(optUi).inTL(0f, 0f);
                body.addCustom(optPanel, 2f);
            }
        }
    }

    private void addMultiDivisionSetpieceDropdownRow(
            TooltipMakerAPI body,
            float rowWidth,
            MultiDivisionSetpiece current,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {

        String ddKey = "MULTI_DIV_SETPIECE_DD";
        boolean expanded = ddKey.equals(expandedDropdownKey);

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 30f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 30f, false);
        rowUi.addPara("Multi-Div Setpiece:", 0f, baseColor).getPosition().inTL(6f, 6f);

        String toggleAct = "TOGGLE_DD:" + ddKey;
        String btnText = (expanded ? "[^] " : "[v] ") + current.displayName;
        ButtonAPI bToggle = registerButton(
                rowUi.addButton(
                        btnText,
                        toggleAct,
                        activeValueColor,
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        236f,
                        24f,
                        0f),
                toggleAct);
        if (expanded) {
            bToggle.highlight();
        }
        bToggle.getPosition().inTL(CONTROL_COL_X, 3f);

        rowUi.addPara(current.summary, 0f, expanded ? activeValueColor : gray)
                .getPosition()
                .inTL(CONTROL_COL_X + 252f, 6f);

        rowUi.setHeightSoFar(30f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 3f);

        if (expanded) {
            for (MultiDivisionSetpiece option : MultiDivisionSetpiece.values()) {
                boolean isCurrent = (option == current);
                CustomPanelAPI optPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
                TooltipMakerAPI optUi = optPanel.createUIElement(rowWidth, 28f, false);
                String selAct = "SET_MULTI_SETPIECE:" + option.name();
                ButtonAPI bOpt = registerButton(
                        optUi.addButton(
                                (isCurrent ? "* " : "   ") + option.displayName,
                                selAct,
                                isCurrent ? activeValueColor : baseColor,
                                darkColor,
                                Alignment.LMID,
                                CutStyle.ALL,
                                224f,
                                22f,
                                0f),
                        selAct);
                if (isCurrent) {
                    bOpt.highlight();
                }
                bOpt.getPosition().inTL(CONTROL_COL_X + 12f, 3f);

                optUi.addPara(option.summary, 0f, isCurrent ? activeValueColor : gray)
                        .getPosition()
                        .inTL(CONTROL_COL_X + 252f, 5f);

                optUi.setHeightSoFar(28f);
                optPanel.addUIElement(optUi).inTL(0f, 0f);
                body.addCustom(optPanel, 2f);
            }
        }
    }

    private void addDivisionSetpieceAttackRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            DivisionSetpieceAttack current,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 28f, false);
        rowUi.addPara(labelText, 0f, baseColor, current.shortName).getPosition().inTL(6f, 5f);

        float x = CONTROL_COL_X;
        float btnW = 58f;
        for (DivisionSetpieceAttack opt : DivisionSetpieceAttack.values()) {
            boolean isCurr = (opt == current);
            String act = actionPrefix + opt.name();
            ButtonAPI btn = registerButton(
                    rowUi.addButton(
                            opt.shortName,
                            act,
                            isCurr ? activeValueColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            btnW,
                            22f,
                            0f),
                    act);
            if (isCurr) {
                btn.highlight();
            }
            btn.getPosition().inTL(x, 3f);
            x += btnW + 4f;
        }

        rowUi.addPara(current.summary, 0f, gray).getPosition().inTL(x + 8f, 5f);
        rowUi.setHeightSoFar(28f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);
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

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 30f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 30f, false);

        String label = play.displayName;
        rowUi.addPara(label, 0f, enabled ? activeValueColor : gray, label).getPosition().inTL(6f, 6f);

        String onAct = "SET_OTC_PLAY:" + play.name() + ":ON";
        ButtonAPI bOn = registerButton(
                rowUi.addButton(
                        "On",
                        onAct,
                        enabled ? activeValueColor : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        56f,
                        22f,
                        0f),
                onAct);
        if (enabled) bOn.highlight();
        bOn.getPosition().inTL(CONTROL_COL_X, 4f);

        String offAct = "SET_OTC_PLAY:" + play.name() + ":OFF";
        ButtonAPI bOff = registerButton(
                rowUi.addButton(
                        "Off",
                        offAct,
                        !enabled ? Misc.getNegativeHighlightColor() : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        56f,
                        22f,
                        0f),
                offAct);
        if (!enabled) bOff.highlight();
        bOff.getPosition().inTL(CONTROL_COL_X + 64f, 4f);

        rowUi.addPara(play.summary, 0f, gray).getPosition().inTL(CONTROL_COL_X + 136f, 6f);

        rowUi.setHeightSoFar(30f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);

        body.addCustom(rowPanel, 3f);
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

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 28f, false);

        rowUi.addPara(labelText, 0f, activeValueColor, labelText).getPosition().inTL(6f, 5f);

        float xCursor = CONTROL_COL_X;
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
                            22f,
                            0f),
                    act);
            if (selected) {
                bPs.highlight();
            }
            bPs.getPosition().inTL(xCursor, 3f);
            xCursor += 118f;
        }

        rowUi.setHeightSoFar(28f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);
    }

    private void addMovementStickinessRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            DivisionMovementStickiness currentStickiness,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 28f, false);

        rowUi.addPara(labelText, 0f, activeValueColor, labelText).getPosition().inTL(6f, 5f);

        float xCursor = CONTROL_COL_X;
        for (DivisionMovementStickiness ms : DivisionMovementStickiness.values()) {
            boolean selected = (ms == currentStickiness);
            String act = actionPrefix + ms.name();
            ButtonAPI bMs = registerButton(
                    rowUi.addButton(
                            ms.displayName,
                            act,
                            selected ? activeValueColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            74f,
                            22f,
                            0f),
                    act);
            if (selected) {
                bMs.highlight();
            }
            bMs.getPosition().inTL(xCursor, 3f);
            xCursor += 80f;
        }

        if (currentStickiness != null) {
            rowUi.addPara(currentStickiness.summary, 0f, gray).getPosition().inTL(xCursor + 8f, 6f);
        }

        rowUi.setHeightSoFar(28f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);
    }

    private void addVoidstrikeObservanceRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            VoidstrikeTargetObservance currentObs,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 28f, false);

        rowUi.addPara(labelText, 0f, activeValueColor, labelText).getPosition().inTL(6f, 5f);

        float xCursor = CONTROL_COL_X;
        for (VoidstrikeTargetObservance obs : VoidstrikeTargetObservance.values()) {
            boolean selected = (obs == currentObs);
            String act = actionPrefix + obs.name();
            ButtonAPI btn = registerButton(
                    rowUi.addButton(
                            obs.displayName,
                            act,
                            selected ? activeValueColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            84f,
                            22f,
                            0f),
                    act);
            if (selected) {
                btn.highlight();
            }
            btn.getPosition().inTL(xCursor, 3f);
            xCursor += 90f;
        }

        if (currentObs != null) {
            rowUi.addPara(currentObs.summary, 0f, gray).getPosition().inTL(xCursor + 8f, 6f);
        }

        rowUi.setHeightSoFar(28f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);
    }

    private void addVoidstrikeCoordinationRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            VoidstrikeCoordinationMode currentCoord,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 28f, false);

        rowUi.addPara(labelText, 0f, activeValueColor, labelText).getPosition().inTL(6f, 5f);

        float xCursor = CONTROL_COL_X;
        for (VoidstrikeCoordinationMode mode : VoidstrikeCoordinationMode.values()) {
            boolean selected = (mode == currentCoord);
            String act = actionPrefix + mode.name();
            ButtonAPI btn = registerButton(
                    rowUi.addButton(
                            mode.displayName,
                            act,
                            selected ? activeValueColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            116f,
                            22f,
                            0f),
                    act);
            if (selected) {
                btn.highlight();
            }
            btn.getPosition().inTL(xCursor, 3f);
            xCursor += 122f;
        }

        if (currentCoord != null) {
            rowUi.addPara(currentCoord.summary, 0f, gray).getPosition().inTL(xCursor + 8f, 6f);
        }

        rowUi.setHeightSoFar(28f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);
    }

    private void addSquadronFormationRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            final SquadronFormation currentFormation,
            final SquadronFormation[] options,
            String actionPrefix,
            final Color baseColor,
            Color darkColor,
            final Color activeValueColor) {

        final float btnW = 112f;
        final float btnGap = 6f;
        final float rowH = 28f;
        final ButtonAPI[] formButtons = new ButtonAPI[options.length];

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, rowH, new BaseCustomUIPanelPlugin() {
            @Override
            public void buttonPressed(Object buttonId) {
                FleetDoctrineDialog.this.buttonPressed(buttonId);
            }

            @Override
            public void render(float alphaMult) {
                for (int i = 0; i < options.length; i++) {
                    SquadronFormation sf = options[i];
                    ButtonAPI btn = formButtons[i];
                    if (btn == null || btn.getPosition() == null) continue;
                    PositionAPI bp = btn.getPosition();
                    boolean selected = (sf == currentFormation);
                    renderFormationIcon(
                            sf.iconFormation,
                            bp.getX() + 12f,
                            bp.getY() + bp.getHeight() * 0.5f,
                            15f,
                            selected ? activeValueColor : baseColor,
                            alphaMult);
                }
            }
        });

        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, rowH, false);
        rowUi.addPara(labelText, 0f, activeValueColor, labelText).getPosition().inTL(6f, 5f);

        for (int i = 0; i < options.length; i++) {
            SquadronFormation sf = options[i];
            boolean selected = (sf == currentFormation);
            float bx = CONTROL_COL_X + i * (btnW + btnGap);
            String act = actionPrefix + sf.name();
            ButtonAPI bForm = registerButton(
                    rowUi.addButton(
                            "    " + sf.shortName,
                            act,
                            selected ? activeValueColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            btnW,
                            22f,
                            0f),
                    act);
            if (selected) {
                bForm.highlight();
            }
            bForm.getPosition().inTL(bx, 3f);
            formButtons[i] = bForm;
        }

        rowUi.setHeightSoFar(rowH);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);
    }

    private void addVoidstrikeDepthRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            int currentValue,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 28f, false);

        rowUi.addPara(labelText, 0f, activeValueColor, labelText).getPosition().inTL(6f, 5f);

        float xCursor = CONTROL_COL_X;
        String decAct = actionPrefix + Math.max(PermanentDivisionManager.MIN_VOIDSTRIKE_SQUADRON_DEPTH, currentValue - 1);
        ButtonAPI bDec = registerButton(
                rowUi.addButton(
                        "-",
                        decAct,
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        32f,
                        22f,
                        0f),
                decAct);
        bDec.setEnabled(currentValue > PermanentDivisionManager.MIN_VOIDSTRIKE_SQUADRON_DEPTH);
        bDec.getPosition().inTL(xCursor, 3f);
        xCursor += 38f;

        for (int v = PermanentDivisionManager.MIN_VOIDSTRIKE_SQUADRON_DEPTH;
             v <= PermanentDivisionManager.MAX_VOIDSTRIKE_SQUADRON_DEPTH;
             v++) {
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
                            40f,
                            22f,
                            0f),
                    valAct);
            if (selected) {
                bVal.highlight();
            }
            bVal.getPosition().inTL(xCursor, 3f);
            xCursor += 46f;
        }

        String incAct = actionPrefix + Math.min(PermanentDivisionManager.MAX_VOIDSTRIKE_SQUADRON_DEPTH, currentValue + 1);
        ButtonAPI bInc = registerButton(
                rowUi.addButton(
                        "+",
                        incAct,
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        32f,
                        22f,
                        0f),
                incAct);
        bInc.setEnabled(currentValue < PermanentDivisionManager.MAX_VOIDSTRIKE_SQUADRON_DEPTH);
        bInc.getPosition().inTL(xCursor, 3f);

        rowUi.setHeightSoFar(28f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);
    }

    private void addFormationSelectorRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            final DivisionFormation currentFormation,
            String actionPrefix,
            final Color baseColor,
            Color darkColor,
            final Color activeValueColor,
            Color gray) {

        final DivisionFormation[] formations = DivisionFormation.values();
        final float btnW = 114f;
        final float btnGap = 5f;
        final int cols = 6;
        final float rowH = 52f;
        final ButtonAPI[] formButtons = new ButtonAPI[formations.length];

        final CustomPanelAPI[] rowPanelRef = new CustomPanelAPI[1];
        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, rowH, new BaseCustomUIPanelPlugin() {
            @Override
            public void buttonPressed(Object buttonId) {
                FleetDoctrineDialog.this.buttonPressed(buttonId);
            }

            @Override
            public void render(float alphaMult) {
                for (int i = 0; i < formations.length; i++) {
                    DivisionFormation f = formations[i];
                    ButtonAPI btn = formButtons[i];
                    if (btn == null || btn.getPosition() == null) continue;
                    PositionAPI bp = btn.getPosition();
                    boolean selected = (f == currentFormation);
                    renderFormationIcon(
                            f,
                            bp.getX() + 13f,
                            bp.getY() + bp.getHeight() * 0.5f,
                            16f,
                            selected ? activeValueColor : baseColor,
                            alphaMult);
                }
            }
        });
        rowPanelRef[0] = rowPanel;

        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, rowH, false);
        rowUi.addPara(labelText, 0f, activeValueColor, labelText).getPosition().inTL(6f, 5f);
        float summaryX = CONTROL_COL_X + cols * (btnW + btnGap) + 8f;
        rowUi.addPara(currentFormation.summary, 0f, gray).getPosition().inTL(summaryX, 16f);

        for (int i = 0; i < formations.length; i++) {
            DivisionFormation f = formations[i];
            boolean selected = (f == currentFormation);
            int r = i / cols;
            int c = i % cols;
            float bx = CONTROL_COL_X + c * (btnW + btnGap);
            float by = 2f + r * 24f;
            String act = actionPrefix + f.name();
            ButtonAPI bForm = registerButton(
                    rowUi.addButton(
                            "    " + f.shortName,
                            act,
                            selected ? activeValueColor : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            btnW,
                            22f,
                            0f),
                    act);
            if (selected) {
                bForm.highlight();
            }
            bForm.getPosition().inTL(bx, by);
            formButtons[i] = bForm;
        }

        rowUi.setHeightSoFar(rowH);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);
    }

    public static void renderFormationIcon(
            DivisionFormation formation,
            float centerX,
            float centerY,
            float size,
            Color tint,
            float alphaMult) {
        renderFormationIcon(formation, centerX, centerY, size, tint, alphaMult, false, true);
    }

    public static void renderFormationIcon(
            DivisionFormation formation,
            float centerX,
            float centerY,
            float size,
            Color tint,
            float alphaMult,
            boolean isFlagshipDivision) {
        renderFormationIcon(formation, centerX, centerY, size, tint, alphaMult, isFlagshipDivision, true);
    }

    public static void renderFormationIcon(
            DivisionFormation formation,
            float centerX,
            float centerY,
            float size,
            Color tint,
            float alphaMult,
            boolean isFlagshipDivision,
            boolean drawSquareBorder) {
        if (formation == null) return;
        try {
            if (drawSquareBorder) {
                renderFormationSquareBorder(centerX, centerY, size, isFlagshipDivision, alphaMult);
            }
            String iconKey = formation.getSettingsIconKey(isFlagshipDivision);
            String path = formation.getSpritePath(isFlagshipDivision);
            SpriteAPI sprite = loadFormationSpriteSafe(iconKey, path);
            if ((sprite == null || sprite.getTextureId() <= 0) && isFlagshipDivision) {
                sprite = loadFormationSpriteSafe(formation.settingsIconKey, formation.spritePath);
            }
            if (sprite != null) {
                float prevW = sprite.getWidth();
                float prevH = sprite.getHeight();
                float prevCx = sprite.getCenterX();
                float prevCy = sprite.getCenterY();
                Color prevColor = sprite.getColor();
                float prevAlpha = sprite.getAlphaMult();
                float innerSize = (float) Math.round(drawSquareBorder ? Math.max(8f, size - 2f) : size);
                float snapX = (float) Math.round(centerX - innerSize * 0.5f);
                float snapY = (float) Math.round(centerY - innerSize * 0.5f);
                sprite.setSize(innerSize, innerSize);
                sprite.setCenter(innerSize * 0.5f, innerSize * 0.5f);
                // Render formation icons in their native metallic silver or flagship gold gradient (Color.WHITE)
                sprite.setColor(Color.WHITE);
                sprite.setAlphaMult(alphaMult);
                sprite.render(snapX, snapY);
                sprite.setSize(prevW, prevH);
                sprite.setCenter(prevCx, prevCy);
                if (prevColor != null) {
                    sprite.setColor(prevColor);
                }
                sprite.setAlphaMult(prevAlpha);
            }
        } catch (Throwable ignored) {
        }
    }

    public static SpriteAPI loadFormationSpriteSafe(String iconKey, String path) {
        SpriteAPI sprite = null;
        if (iconKey != null && !iconKey.isEmpty()) {
            try {
                sprite = Global.getSettings().getSprite("division_ai", iconKey);
            } catch (Throwable ignored) {
            }
        }
        if ((sprite == null || sprite.getTextureId() <= 0) && path != null && !path.isEmpty()) {
            try {
                Global.getSettings().loadTexture(path);
            } catch (Throwable ignored) {
            }
            try {
                sprite = Global.getSettings().getSprite(path);
            } catch (Throwable ignored) {
            }
        }
        return sprite;
    }

    public static void renderFormationSquareBorder(
            float centerX,
            float centerY,
            float size,
            boolean isFlagshipDivision,
            float alphaMult) {
        float half = size * 0.5f;
        float x0 = (float) Math.round(centerX - half);
        float y0 = (float) Math.round(centerY - half);
        float x1 = (float) Math.round(centerX + half);
        float y1 = (float) Math.round(centerY + half);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // 1. Dark translucent backing plate for contrast against nebulae and bright UI backgrounds
        if (isFlagshipDivision) {
            GL11.glColor4f(0.10f, 0.09f, 0.05f, 0.80f * alphaMult);
        } else {
            GL11.glColor4f(0.06f, 0.08f, 0.11f, 0.80f * alphaMult);
        }
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x0, y0);
        GL11.glVertex2f(x1, y0);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x0, y1);
        GL11.glEnd();

        // 2. Dark charcoal outer frame (#303842 matching the icon SVG stroke)
        GL11.glLineWidth(1.0f);
        GL11.glColor4f(48f / 255f, 56f / 255f, 66f / 255f, 0.92f * alphaMult);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x0 - 0.8f, y0 - 0.8f);
        GL11.glVertex2f(x1 + 0.8f, y0 - 0.8f);
        GL11.glVertex2f(x1 + 0.8f, y1 + 0.8f);
        GL11.glVertex2f(x0 - 0.8f, y1 + 0.8f);
        GL11.glEnd();

        // 3. Beveled binary-rank metallic square border (Gold #FFF3C4->#E0B450->#A9761F vs Silver #F2F6FA->#C8D2DC->#8C98A6)
        float tlR, tlG, tlB;
        float brR, brG, brB;
        if (isFlagshipDivision) {
            tlR = 248f / 255f; tlG = 218f / 255f; tlB = 132f / 255f;
            brR = 198f / 255f; brG = 146f / 255f; brB = 44f / 255f;
        } else {
            tlR = 232f / 255f; tlG = 239f / 255f; tlB = 246f / 255f;
            brR = 154f / 255f; brG = 166f / 255f; brB = 180f / 255f;
        }
        GL11.glLineWidth(1.25f);
        GL11.glBegin(GL11.GL_LINES);
        // Top and Left edges (light source highlight)
        GL11.glColor4f(tlR, tlG, tlB, 0.96f * alphaMult);
        GL11.glVertex2f(x0, y1);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x0, y0);
        GL11.glVertex2f(x0, y1);
        // Bottom and Right edges (metallic mid/shadow tone)
        GL11.glColor4f(brR, brG, brB, 0.96f * alphaMult);
        GL11.glVertex2f(x0, y0);
        GL11.glVertex2f(x1, y0);
        GL11.glVertex2f(x1, y0);
        GL11.glVertex2f(x1, y1);
        GL11.glEnd();

        GL11.glPopAttrib();
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

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 28f, false);

        rowUi.addPara(labelText, 0f, baseColor).getPosition().inTL(6f, 5f);

        String toggleAct = "TOGGLE_DD:" + dropdownKey;
        String btnText = (expanded ? "[^] " : "[v] ") + currentMission.dropdownLabel;
        ButtonAPI bToggle = registerButton(
                rowUi.addButton(
                        btnText,
                        toggleAct,
                        activeValueColor,
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        268f,
                        22f,
                        0f),
                toggleAct);
        if (expanded) {
            bToggle.highlight();
        }
        bToggle.getPosition().inTL(CONTROL_COL_X, 3f);

        rowUi.addPara(currentMission.summary, 0f, gray).getPosition().inTL(CONTROL_COL_X + 280f, 5f);

        rowUi.setHeightSoFar(28f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);

        if (expanded) {
            DivisionMissionType[] optionsToShow = (primaryOptions != null && primaryOptions.length > 0)
                    ? primaryOptions
                    : DivisionMissionType.values();
            for (DivisionMissionType option : optionsToShow) {
                boolean isCurrent = (option == currentMission);
                CustomPanelAPI optPanel = Global.getSettings().createCustom(rowWidth, 26f, subPanelPlugin);
                TooltipMakerAPI optUi = optPanel.createUIElement(rowWidth, 26f, false);

                String selAct = selectActionPrefix + option.name();
                ButtonAPI bOpt = registerButton(
                        optUi.addButton(
                                (isCurrent ? "* " : "   ") + option.dropdownLabel,
                                selAct,
                                isCurrent ? activeValueColor : baseColor,
                                darkColor,
                                Alignment.LMID,
                                CutStyle.ALL,
                                256f,
                                20f,
                                0f),
                        selAct);
                if (isCurrent) {
                    bOpt.highlight();
                }
                bOpt.getPosition().inTL(CONTROL_COL_X + 12f, 3f);

                optUi.addPara(option.summary, 0f, isCurrent ? activeValueColor : gray)
                        .getPosition()
                        .inTL(CONTROL_COL_X + 280f, 4f);

                optUi.setHeightSoFar(26f);
                optPanel.addUIElement(optUi).inTL(0f, 0f);
                body.addCustom(optPanel, 2f);
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

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 28f, false);

        rowUi.addPara(labelText, 0f, activeValueColor, labelText).getPosition().inTL(6f, 5f);

        float xCursor = CONTROL_COL_X;

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
                        54f,
                        22f,
                        0f),
                decAct);
        bDec.setEnabled(currentValue > minVal);
        bDec.getPosition().inTL(xCursor, 3f);
        xCursor += 60f;

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
                            62f,
                            22f,
                            0f),
                    valAct);
            if (selected) {
                bVal.highlight();
            }
            bVal.getPosition().inTL(xCursor, 3f);
            xCursor += 68f;
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
                        54f,
                        22f,
                        0f),
                incAct);
        bInc.setEnabled(currentValue < maxVal);
        bInc.getPosition().inTL(xCursor, 3f);

        rowUi.setHeightSoFar(28f);
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

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 28f, false);

        rowUi.addPara(
                "%s:  %s",
                0f,
                activeValueColor,
                labelText,
                String.valueOf(currentValue)).getPosition().inTL(6f, 5f);

        float xCursor = CONTROL_COL_X;

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
                        32f,
                        22f,
                        0f),
                decAct);
        bDec.setEnabled(currentValue > 0);
        bDec.getPosition().inTL(xCursor, 3f);
        xCursor += 38f;

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
                            42f,
                            22f,
                            0f),
                    valAct);
            if (selected) {
                bVal.highlight();
            }
            bVal.getPosition().inTL(xCursor, 3f);
            xCursor += 48f;
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
                        32f,
                        22f,
                        0f),
                incAct);
        bInc.setEnabled(currentValue < PermanentDivisionManager.MAX_DIVISION_SIZE);
        bInc.getPosition().inTL(xCursor, 3f);

        rowUi.setHeightSoFar(28f);
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
        String promptText;
        String yesBtnLabel;
        if ("APPLY_DOCTRINE_TO_FACTION_FLEETS".equals(pendingConfirmationCmd)) {
            promptText = "Apply this Fleet Doctrine to your personal faction's colony fleets? "
                    + "(Does not affect commissioned preset faction worlds.)";
            yesBtnLabel = "Yes, Apply Doctrine";
        } else if ("APPLY_NAMING_TO_FACTION_FLEETS".equals(pendingConfirmationCmd)) {
            promptText = "Apply this Fleet Nomenclature to your personal faction's colony fleets? "
                    + "(Does not affect commissioned preset faction worlds.)";
            yesBtnLabel = "Yes, Apply Nomenclature";
        } else {
            promptText = "Reinitialize division lists for all AI fleets across the sector?";
            yesBtnLabel = "Yes, Reinitialize Sector";
        }
        ui.addPara(promptText, 10f, Misc.getTextColor());

        ButtonAPI bYes = registerButton(
                ui.addButton(
                        yesBtnLabel,
                        "CONFIRM_YES",
                        Misc.getPositiveHighlightColor(),
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        236f,
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
            } else if ("APPLY_DOCTRINE_TO_FACTION_FLEETS".equals(confirmed)) {
                lastAppliedDoctrineFleetCount = FactionFleetDoctrines.applyPlayerDoctrineToPersonalFactionFleets(Global.getSector());
            } else if ("APPLY_NAMING_TO_FACTION_FLEETS".equals(confirmed)) {
                lastAppliedNamingFleetCount = FactionFleetDoctrines.applyPlayerNomenclatureToPersonalFactionFleets(Global.getSector());
            }
            rebuildContent();
            return;
        }
        if ("REINIT_SECTOR_DIVISIONS".equals(cmd)
                || "APPLY_DOCTRINE_TO_FACTION_FLEETS".equals(cmd)
                || "APPLY_NAMING_TO_FACTION_FLEETS".equals(cmd)) {
            commitCustomPrefixTextFields();
            pendingConfirmationCmd = cmd;
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
            PlayerNamingConfig prevSnapshot = cfg.copy();
            cfg.clearCustomPrefixes();
            cfg.setPreset(preset);
            PermanentDivisionManager.updateDefaultNamedPlayerDivisionsAfterNamingChange(prevSnapshot);
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
            PlayerNamingConfig cfg = DivisionNomenclature.getPlayerNamingConfig();
            PlayerNamingConfig prevSnapshot = cfg.copy();
            cfg.setNumberingStyle(style);
            PermanentDivisionManager.updateDefaultNamedPlayerDivisionsAfterNamingChange(prevSnapshot);
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
        if (cmd.startsWith("SET_FLEET_FORMATION:")) {
            FleetWideFormation fw = FleetWideFormation.fromId(cmd.substring("SET_FLEET_FORMATION:".length()), null);
            if (fw != null) {
                PermanentDivisionManager.updateFleetWideFormation(fw);
            }
            expandedDropdownKey = null;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_DEPLOY_IN_FORMATION:")) {
            boolean enabled = "ON".equals(cmd.substring("SET_DEPLOY_IN_FORMATION:".length()));
            PermanentDivisionManager.updateDeployInFleetFormation(enabled);
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_MULTI_SETPIECE:")) {
            MultiDivisionSetpiece msp = MultiDivisionSetpiece.fromId(cmd.substring("SET_MULTI_SETPIECE:".length()), null);
            if (msp != null) {
                PermanentDivisionManager.updateMultiDivisionSetpiece(msp);
            }
            expandedDropdownKey = null;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_DIV_SETPIECE:")) {
            String rest = cmd.substring("SET_DIV_SETPIECE:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                DivisionSetpieceAttack sp = DivisionSetpieceAttack.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeSetpieceAttack(dt, sp);
                rebuildContent();
            }
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
        if ("TAB:AUTO_ASSIGN".equals(cmd)) {
            activeTab = ActiveTab.AUTO_ASSIGN_SCHEMES;
            rebuildContent();
            return;
        }
        if ("TAB:HULLS".equals(cmd)) {
            activeTab = ActiveTab.HULL_LIMITS;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_AA_CAP:")) {
            String rest = cmd.substring("SET_AA_CAP:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                AutoAssignCapability cap = AutoAssignCapability.valueOf(rest.substring(0, sep));
                boolean enabled = "ON".equals(rest.substring(sep + 1));
                PermanentDivisionManager.updateAutoAssignCapability(cap, enabled);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_AA_HULL:")) {
            String rest = cmd.substring("SET_AA_HULL:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                AutoAssignHull hull = AutoAssignHull.valueOf(rest.substring(0, sep));
                boolean enabled = "ON".equals(rest.substring(sep + 1));
                PermanentDivisionManager.updateAutoAssignHull(hull, enabled);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_AA_SCHEME:")) {
            String rest = cmd.substring("SET_AA_SCHEME:".length());
            String[] parts = rest.split(":");
            if (parts.length == 3) {
                AutoAssignCapability cap = AutoAssignCapability.valueOf(parts[0]);
                AutoAssignHull hull = AutoAssignHull.valueOf(parts[1]);
                boolean enabled = "ON".equals(parts[2]);
                if (enabled) {
                    // If user explicitly enables a cell while its parent capability or hull master toggle was off, enable the parent too
                    FleetDoctrineConfig cfg = PermanentDivisionManager.getFleetDoctrine();
                    if (!cfg.isCapabilityEnabled(cap)) {
                        PermanentDivisionManager.updateAutoAssignCapability(cap, true);
                    }
                    if (!cfg.isHullEnabled(hull)) {
                        PermanentDivisionManager.updateAutoAssignHull(hull, true);
                    }
                }
                PermanentDivisionManager.updateAutoAssignScheme(cap, hull, enabled);
                rebuildContent();
            }
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
        if (cmd.startsWith("SET_FORMATION:")) {
            String rest = cmd.substring("SET_FORMATION:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                String dtStr = rest.substring(0, sep);
                String fmStr = rest.substring(sep + 1);
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(dtStr);
                DivisionFormation fm = DivisionFormation.valueOf(fmStr);
                PermanentDivisionManager.updateDivisionTypeFormation(dt, fm);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_STICKINESS:")) {
            String rest = cmd.substring("SET_STICKINESS:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                String dtStr = rest.substring(0, sep);
                String msStr = rest.substring(sep + 1);
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(dtStr);
                DivisionMovementStickiness ms = DivisionMovementStickiness.valueOf(msStr);
                PermanentDivisionManager.updateDivisionTypeMovementStickiness(dt, ms);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_VS_OBS:")) {
            String rest = cmd.substring("SET_VS_OBS:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                VoidstrikeTargetObservance obs = VoidstrikeTargetObservance.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeVoidstrikeObservance(dt, obs);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_VS_COORD:")) {
            String rest = cmd.substring("SET_VS_COORD:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                VoidstrikeCoordinationMode mode = VoidstrikeCoordinationMode.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeVoidstrikeCoordination(dt, mode);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_FTR_FM:")) {
            String rest = cmd.substring("SET_FTR_FM:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                SquadronFormation fm = SquadronFormation.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeFighterFormation(dt, fm);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_FTR_VS_FM:")) {
            String rest = cmd.substring("SET_FTR_VS_FM:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                SquadronFormation fm = SquadronFormation.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeFighterVoidstrikeFormation(dt, fm);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_BMB_FM:")) {
            String rest = cmd.substring("SET_BMB_FM:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                SquadronFormation fm = SquadronFormation.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeBomberFormation(dt, fm);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_BMB_VS_FM:")) {
            String rest = cmd.substring("SET_BMB_VS_FM:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                SquadronFormation fm = SquadronFormation.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeBomberVoidstrikeFormation(dt, fm);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_VS_DEPTH:")) {
            String rest = cmd.substring("SET_VS_DEPTH:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                int depth = Integer.parseInt(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeVoidstrikeSquadronDepth(dt, depth);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_VS_WIDTH:")) {
            String rest = cmd.substring("SET_VS_WIDTH:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                int width = Integer.parseInt(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeVoidstrikeSquadronWidth(dt, width);
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
