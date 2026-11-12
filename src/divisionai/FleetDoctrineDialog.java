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
import divisionai.DivisionNomenclature.NumberingStyle;
import divisionai.DivisionNomenclature.PlayerNamingConfig;
import divisionai.DivisionNomenclature.PlayerNamingPreset;
import divisionai.PermanentDivisionManager.AutoAssignCapability;
import divisionai.PermanentDivisionManager.AutoAssignHull;
import divisionai.PermanentDivisionManager.DoctrineDivisionType;
import divisionai.PermanentDivisionManager.DoctrineHullCategory;
import divisionai.PermanentDivisionManager.DivisionFormation;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.StrikeCraftMissionType;
import divisionai.PermanentDivisionManager.DivisionMovementStickiness;
import divisionai.PermanentDivisionManager.DivisionRammingMode;
import divisionai.PermanentDivisionManager.DivisionRammingTargetScope;
import divisionai.PermanentDivisionManager.DivisionSetpieceAttack;
import divisionai.PermanentDivisionManager.FleetDoctrineConfig;
import divisionai.PermanentDivisionManager.FleetWideFormation;
import divisionai.PermanentDivisionManager.MultiDivisionSetpiece;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.OtcPlay;
import divisionai.PermanentDivisionManager.LineStation;
import divisionai.PermanentDivisionManager.SquadronFormation;
import divisionai.PermanentDivisionManager.ShipSkillCoordinationMode;
import divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection;
import divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry;
import divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode;
import divisionai.PermanentDivisionManager.VoidstrikeGunneryMode;
import divisionai.PermanentDivisionManager.VoidstrikeSplitMode;
import divisionai.PermanentDivisionManager.VoidstrikeTargetingBias;
import divisionai.PermanentDivisionManager.VoidstrikeTargetObservance;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
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

    public enum DoctrineHullGroup {
        BATTLESHIPS(
                "Battleships & Capitals",
                DoctrineDivisionType.BATTLESHIP,
                DoctrineDivisionType.MISSILE_BATTLESHIP),
        CRUISERS(
                "Cruisers",
                DoctrineDivisionType.CRUISER,
                DoctrineDivisionType.MISSILE_CRUISER,
                DoctrineDivisionType.STRIKE_CRUISER),
        DESTROYERS(
                "Destroyers",
                DoctrineDivisionType.DESTROYER,
                DoctrineDivisionType.MISSILE_DESTROYER),
        FRIGATES(
                "Frigates",
                DoctrineDivisionType.FRIGATE,
                DoctrineDivisionType.MISSILE_FRIGATE),
        CARRIERS(
                "Carriers & Strike Wings",
                DoctrineDivisionType.FLEET_CARRIER,
                DoctrineDivisionType.LIGHT_CARRIER,
                DoctrineDivisionType.ESCORT_CARRIER,
                DoctrineDivisionType.CARRIER_GROUP),
        SPECIAL(
                "Phase, Auxiliaries & Mixed",
                DoctrineDivisionType.PHASE,
                DoctrineDivisionType.SERVICE_GROUP,
                DoctrineDivisionType.FORCE);

        public final String displayName;
        public final DoctrineDivisionType[] divisionTypes;

        DoctrineHullGroup(String displayName, DoctrineDivisionType... divisionTypes) {
            this.displayName = displayName;
            this.divisionTypes = divisionTypes;
        }

        public static DoctrineHullGroup forDivisionType(DoctrineDivisionType dt) {
            if (dt == null) return SPECIAL;
            for (DoctrineHullGroup g : values()) {
                for (DoctrineDivisionType member : g.divisionTypes) {
                    if (member == dt) return g;
                }
            }
            return SPECIAL;
        }
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
    private ActiveTab activeTab = ActiveTab.BATTLE_GROUP_COMMAND;
    private String expandedDropdownKey = null;
    private String pendingConfirmationCmd = null;
    private int lastReinitFleetCount = -1;
    private int lastAppliedDoctrineFleetCount = -1;
    private int lastAppliedNamingFleetCount = -1;

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
    private final Map<DivisionArchetype, TextFieldAPI> prefixTextFields = new LinkedHashMap<>();
    private final java.util.Set<DoctrineHullGroup> expandedHullGroups =
            java.util.EnumSet.noneOf(DoctrineHullGroup.class);
    private final java.util.Set<DoctrineDivisionType> collapsedDivisionTypes =
            java.util.EnumSet.noneOf(DoctrineDivisionType.class);
    private final java.util.Set<DoctrineDivisionType> expandedCarrierOptionsTypes =
            java.util.EnumSet.noneOf(DoctrineDivisionType.class);
    private final java.util.Set<DoctrineDivisionType> expandedRammingOptionsTypes =
            java.util.EnumSet.noneOf(DoctrineDivisionType.class);
    private TextFieldAPI customOtcTitleField = null;
    private TooltipMakerAPI dialogTooltipHost = null;
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
        activeCheckboxes.clear();
        prefixTextFields.clear();
        customOtcTitleField = null;
        dialogTooltipHost = null;
        DivisionUiWidgets.resetFallbackTooltipHost();
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
            DivisionUiWidgets.attachActionTooltip(dialogTooltipHost, button, actionId);
        }
        return button;
    }

    private ButtonAPI registerCheckbox(ButtonAPI checkbox, String actionId, boolean initialChecked) {
        if (checkbox != null && actionId != null) {
            checkbox.setChecked(initialChecked);
            activeCheckboxes.put(checkbox, new CheckboxBinding(actionId, initialChecked));
            DivisionUiWidgets.attachActionTooltip(dialogTooltipHost, checkbox, actionId);
        }
        return checkbox;
    }

    private void rebuildContent() {
        if (rootOverlayPanel == null) return;
        if (activeBodyScroller != null) {
            savedScrollY = Math.max(0f, activeBodyScroller.getYOffset());
            activeBodyScroller = null;
        }
        activeButtons.clear();
        activeCheckboxes.clear();
        otcPennantBoxes.clear();
        prefixTextFields.clear();
        customOtcTitleField = null;
        dialogTooltipHost = null;
        DivisionUiWidgets.resetFallbackTooltipHost();

        if (contentContainer != null) {
            rootOverlayPanel.removeComponent(contentContainer);
        }

        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        float x = Math.round((screenW - DIALOG_WIDTH) * 0.5f);
        float y = Math.round((screenH - DIALOG_HEIGHT) * 0.5f);

        contentContainer = Global.getSettings().createCustom(DIALOG_WIDTH, DIALOG_HEIGHT, subPanelPlugin);
        rootOverlayPanel.addComponent(contentContainer).inTL(x, y);
        dialogTooltipHost = contentContainer.createUIElement(16f, 16f, false);

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

        // 1. Header (subtitle removed per UI Item A)
        TooltipMakerAPI header = contentContainer.createUIElement(DIALOG_WIDTH - 64f, 28f, false);
        header.setTitleOrbitronLarge();
        header.addTitle("FLEET DOCTRINE", baseColor);
        header.setHeightSoFar(28f);
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
                        "Command Scheme",
                        "TAB:OTC_COMMAND",
                        activeTab == ActiveTab.BATTLE_GROUP_COMMAND ? highlight : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        136f,
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
        bTabMod.getPosition().inTL(220f, 3f);

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
        bTabAuto.getPosition().inTL(368f, 3f);

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
        bTabHull.getPosition().inTL(500f, 3f);

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
        bTabNaming.getPosition().inTL(614f, 3f);

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
        contentContainer.addComponent(toolbarRow).inTL(12f, 38f);

        if (pendingConfirmationCmd != null) {
            buildConfirmationScreen(contentContainer, baseColor, darkColor, highlight);
            return;
        }

        // 3. Scrollable Body
        float bodyTop = 74f;
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
            LabelAPI statusLbl = fbUi.addPara(statusNote, 0f, lastAppliedDoctrineFleetCount >= 0 ? positive : gray);
            float noteBottom = DivisionUiWidgets.wrapLabelInPlace(statusLbl, factionBarW - 268f, 6f, 6f);
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
            DivisionUiWidgets.resizeRowToFit(factionBar, fbUi, factionBarW, Math.max(30f, noteBottom + 4f));
            factionBar.addUIElement(fbUi).inTL(0f, 0f);
            body.addCustom(factionBar, 2f);
        }

        if (activeTab == ActiveTab.ALL_DOCTRINE || activeTab == ActiveTab.BATTLE_GROUP_COMMAND) {
            body.addSectionHeading(
                    "Command Scheme and Preferred Tactics",
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
                    doctrine.isOrchestratedAdvance(),
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
                    "DIVISION DEFAULTS  (Click a Hull Group to Expand / Collapse)",
                    highlight,
                    darkColor,
                    Alignment.LMID,
                    4f);

            // Opening Mission Duration (30s - 60s) + Expand All / Collapse All buttons
            addOpeningDurationRow(
                    body,
                    bodyWidth - 16f,
                    doctrine.getOpeningDurationSeconds(),
                    baseColor,
                    darkColor,
                    positive);

            for (DoctrineHullGroup group : DoctrineHullGroup.values()) {
                boolean groupExpanded = expandedHullGroups.contains(group);
                addHullGroupAccordionRow(
                        body,
                        bodyWidth - 16f,
                        group,
                        groupExpanded,
                        doctrine,
                        baseColor,
                        darkColor,
                        highlight,
                        positive,
                        gray);
                if (!groupExpanded) {
                    continue;
                }

                for (DoctrineDivisionType dt : group.divisionTypes) {
                    int currSpacing = doctrine.getStationSpacing(dt);
                    int currInter = doctrine.getDivisionInterval(dt);
                    int currStandoff = doctrine.getStandoffRange(dt);
                    DivisionMissionType currOpenMis = doctrine.getOpeningMission(dt);
                    DivisionMissionType currMainMis = doctrine.getMainMission(dt);
                    LineStation currLineStation = doctrine.getLineStation(dt);
                    DivisionFormation currFormation = doctrine.getFormation(dt);
                    DivisionMovementStickiness currStickiness = doctrine.getMovementStickiness(dt);

                    boolean dtCollapsed = collapsedDivisionTypes.contains(dt);
                    addDivisionTypeCardHeaderRow(
                            body,
                            bodyWidth - 16f,
                            dt,
                            !dtCollapsed,
                            currLineStation,
                            currFormation,
                            currSpacing,
                            currInter,
                            currStandoff,
                            currOpenMis,
                            currMainMis,
                            baseColor,
                            darkColor,
                            highlight,
                            positive,
                            gray);
                    if (dtCollapsed) {
                        continue;
                    }

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

                ShipSkillCoordinationMode currSkillCoord = doctrine.getShipSkillCoordination(dt);
                addShipSkillCoordinationRow(
                        body,
                        bodyWidth - 16f,
                        "Ship Skills: " + currSkillCoord.displayName,
                        currSkillCoord,
                        "SET_SKILL_COORD:" + dt.name() + ":",
                        baseColor,
                        darkColor,
                        positive,
                        gray);

                if (!dt.isCarrierType() && dt != DoctrineDivisionType.SERVICE_GROUP) {
                    DivisionRammingMode currRamMode = doctrine.getRammingMode(dt);
                    DivisionRammingTargetScope currRamScope = doctrine.getRammingTargetScope(dt);
                    int currRamSlots = doctrine.getMaxRammingSlots(dt);
                    boolean ramOptsExpanded = expandedRammingOptionsTypes.contains(dt);
                    addRammingOptionsToggleRow(
                            body,
                            bodyWidth - 16f,
                            dt,
                            ramOptsExpanded,
                            currRamMode,
                            currRamScope,
                            currRamSlots,
                            baseColor,
                            darkColor,
                            highlight,
                            positive,
                            gray);
                    if (ramOptsExpanded) {
                        addRammingModeRow(
                                body,
                                bodyWidth - 16f,
                                "Ramming: " + currRamMode.displayName,
                                currRamMode,
                                "SET_RAM_MODE:" + dt.name() + ":",
                                baseColor,
                                darkColor,
                                positive,
                                gray);
                        addRammingTargetScopeRow(
                                body,
                                bodyWidth - 16f,
                                "Ram Target: " + currRamScope.displayName,
                                currRamScope,
                                "SET_RAM_SCOPE:" + dt.name() + ":",
                                baseColor,
                                darkColor,
                                highlight,
                                gray);
                        DivisionUiWidgets.addVoidstrikeDepthRow(
                                body,
                                bodyWidth - 16f,
                                28f,
                                CONTROL_COL_X,
                                subPanelPlugin,
                                buttonRegistrar,
                                "Max Ram Slots: " + currRamSlots,
                                highlight,
                                6f,
                                5f,
                                currRamSlots,
                                "SET_RAM_SLOTS:" + dt.name() + ":",
                                true,
                                false,
                                true,
                                28f,
                                22f,
                                32f,
                                baseColor,
                                darkColor,
                                highlight,
                                positive);
                    }
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

                // Rows 7a-7g: Strike Craft & Wing Voidstrike Behavior Doctrine (collapsed under "Strike Craft Options")
                StrikeCraftMissionType currScOpenMis = doctrine.getStrikeCraftOpeningMission(dt);
                StrikeCraftMissionType currScMainMis = doctrine.getStrikeCraftMainMission(dt);
                VoidstrikeTargetingBias currVsBias = doctrine.getVoidstrikeTargetingBias(dt);
                boolean currVsFocusCv = doctrine.isVoidstrikeFocusCarriers(dt);
                VoidstrikeTargetObservance currVsObs = doctrine.getVoidstrikeObservance(dt);
                VoidstrikeCoordinationMode currVsCoord = doctrine.getVoidstrikeCoordination(dt);
                VoidstrikeAttackGeometry currVsGeom = doctrine.getVoidstrikeAttackGeometry(dt);
                VoidstrikeSplitMode currVsSplit = doctrine.getVoidstrikeSplitMode(dt);
                VoidstrikeGunneryMode currVsGun = doctrine.getVoidstrikeGunneryMode(dt);
                VoidstrikeAoaCorrection currVsAoa = doctrine.getVoidstrikeAoaCorrection(dt);
                SquadronFormation currFtrFm = doctrine.getFighterFormation(dt);
                SquadronFormation currFtrVsFm = doctrine.getFighterVoidstrikeFormation(dt);
                SquadronFormation currBmbFm = doctrine.getBomberFormation(dt);
                SquadronFormation currBmbVsFm = doctrine.getBomberVoidstrikeFormation(dt);
                int currVsDepth = doctrine.getVoidstrikeSquadronDepth(dt);
                int currVsWidth = doctrine.getVoidstrikeSquadronWidth(dt);

                boolean carrierOptsExpanded = expandedCarrierOptionsTypes.contains(dt);
                addCarrierOptionsToggleRow(
                        body,
                        bodyWidth - 16f,
                        dt,
                        carrierOptsExpanded,
                        currScOpenMis,
                        currScMainMis,
                        currVsObs,
                        currVsCoord,
                        currFtrFm,
                        currFtrVsFm,
                        currBmbFm,
                        currBmbVsFm,
                        currVsDepth,
                        currVsWidth,
                        baseColor,
                        darkColor,
                        highlight,
                        positive,
                        gray);

                if (carrierOptsExpanded) {
                    addStrikeCraftMissionDropdownRow(
                            body,
                            bodyWidth - 16f,
                            "Opening Strike Craft:",
                            "SC_OPEN_DD:" + dt.name(),
                            currScOpenMis,
                            "SET_SC_OPEN_MIS:" + dt.name() + ":",
                            baseColor,
                            darkColor,
                            positive,
                            gray);

                    addStrikeCraftMissionDropdownRow(
                            body,
                            bodyWidth - 16f,
                            "Main Strike Craft:",
                            "SC_MAIN_DD:" + dt.name(),
                            currScMainMis,
                            "SET_SC_MAIN_MIS:" + dt.name() + ":",
                            baseColor,
                            darkColor,
                            highlight,
                            gray);

                    addVoidstrikeTargetingBiasRow(
                            body,
                            bodyWidth - 16f,
                            "Target Bias: " + currVsBias.displayName,
                            currVsBias,
                            "SET_VS_BIAS:" + dt.name() + ":",
                            baseColor,
                            darkColor,
                            highlight,
                            gray);

                    addVoidstrikeFocusCarriersRow(
                            body,
                            bodyWidth - 16f,
                            currVsFocusCv,
                            "SET_VS_CV_FOCUS:" + dt.name() + ":TOGGLE",
                            baseColor,
                            highlight,
                            positive,
                            gray);

                    addVoidstrikeObservanceRow(
                            body,
                            bodyWidth - 16f,
                            "Voidstrike Focus: " + currVsObs.displayName,
                            currVsObs,
                            "SET_VS_OBS:" + dt.name() + ":",
                            baseColor,
                            darkColor,
                            positive,
                            gray);

                    addVoidstrikeCoordinationRow(
                            body,
                            bodyWidth - 16f,
                            "Voidstrike Launch: " + currVsCoord.displayName,
                            currVsCoord,
                            "SET_VS_COORD:" + dt.name() + ":",
                            baseColor,
                            darkColor,
                            highlight,
                            gray);

                    addVoidstrikeAttackGeometryRow(
                            body,
                            bodyWidth - 16f,
                            "Strike Geometry: " + currVsGeom.displayName,
                            currVsGeom,
                            "SET_VS_GEOM:" + dt.name() + ":",
                            baseColor,
                            darkColor,
                            positive,
                            gray);

                    addVoidstrikeSplitModeRow(
                            body,
                            bodyWidth - 16f,
                            "Wing Split: " + currVsSplit.displayName,
                            currVsSplit,
                            "SET_VS_SPLIT:" + dt.name() + ":",
                            baseColor,
                            darkColor,
                            highlight,
                            gray);

                    addVoidstrikeGunneryModeRow(
                            body,
                            bodyWidth - 16f,
                            "Strike Gunnery: " + currVsGun.displayName,
                            currVsGun,
                            "SET_VS_GUN:" + dt.name() + ":",
                            baseColor,
                            darkColor,
                            highlight,
                            gray);

                    addVoidstrikeAoaCorrectionRow(
                            body,
                            bodyWidth - 16f,
                            "AoA Correction: " + currVsAoa.displayName,
                            currVsAoa,
                            "SET_VS_AOA:" + dt.name() + ":",
                            baseColor,
                            darkColor,
                            positive,
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
                            "Fighter Voidstrike: " + currFtrVsFm.shortName,
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
                            "Bomber Voidstrike: " + currBmbVsFm.shortName,
                            currBmbVsFm,
                            SquadronFormation.BOMBER_VOIDSTRIKE_OPTIONS,
                            "SET_BMB_VS_FM:" + dt.name() + ":",
                            baseColor,
                            darkColor,
                            highlight);

                    addVoidstrikeDepthRow(
                            body,
                            bodyWidth - 16f,
                            "Voidstrike Depth: " + currVsDepth,
                            currVsDepth,
                            "SET_VS_DEPTH:" + dt.name() + ":",
                            baseColor,
                            darkColor,
                            positive);

                        addVoidstrikeDepthRow(
                                body,
                                bodyWidth - 16f,
                                "Voidstrike Width: " + currVsWidth,
                                currVsWidth,
                                "SET_VS_WIDTH:" + dt.name() + ":",
                                baseColor,
                                darkColor,
                                highlight);
                    }
                }
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
            body.addPara(
                    "Sets the minimum and maximum ships of each hull class that Fleet Organization and Auto-Assign will group into a single division (up to %s ships total per division).",
                    4f,
                    gray,
                    highlight,
                    String.valueOf(PermanentDivisionManager.MAX_DIVISION_SIZE));

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
        float rowH = 34f;
        CustomPanelAPI row = Global.getSettings().createCustom(rowWidth, rowH, subPanelPlugin);
        TooltipMakerAPI ui = row.createUIElement(rowWidth, rowH, false);

        boolean capOn = doctrine.isCapabilityEnabled(cap);
        LabelAPI titleLbl = ui.addPara(cap.displayName + ":", 0f, capOn ? positive : baseColor);
        float titleBot = DivisionUiWidgets.wrapLabelInPlace(titleLbl, 273f, 4f, 2f);
        LabelAPI descLbl = ui.addPara(cap.summary, 0f, gray);
        float descBot = DivisionUiWidgets.wrapLabelInPlace(descLbl, 273f, 4f, titleBot + 1f);

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

        DivisionUiWidgets.resizeRowToFit(row, ui, rowWidth, Math.max(rowH, descBot + 4f));
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
        LabelAPI hullLbl = ui.addPara(hull.displayName + " Divisions:", 0f, hullOn ? positive : baseColor);
        float lblBot = DivisionUiWidgets.wrapLabelInPlace(hullLbl, 273f, 4f, 6f);

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

        DivisionUiWidgets.resizeRowToFit(row, ui, rowWidth, Math.max(rowH, lblBot + 4f));
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
            for (PlayerNamingPreset opt : PlayerNamingPreset.getPlayerSelectablePresets()) {
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

                LabelAPI optSumLbl = optUi.addPara(opt.summary, 0f, gray);
                float sumBot = DivisionUiWidgets.wrapLabelInPlace(
                        optSumLbl, rowWidth - (CONTROL_COL_X + 484f) - 8f, CONTROL_COL_X + 484f, 5f);
                DivisionUiWidgets.resizeRowToFit(optPanel, optUi, rowWidth, Math.max(28f, sumBot + 4f));
                optPanel.addUIElement(optUi).inTL(0f, 0f);
                body.addCustom(optPanel, 2f);
            }
        } else {
            float descW = rowWidth - CONTROL_COL_X - 10f;
            CustomPanelAPI descPanel = Global.getSettings().createCustom(rowWidth, 22f, subPanelPlugin);
            TooltipMakerAPI descUi = descPanel.createUIElement(descW, 22f, false);
            LabelAPI descLbl = descUi.addPara(currentPreset.summary, 0f, gray);
            float descBot = DivisionUiWidgets.wrapLabelInPlace(descLbl, descW - 6f, 0f, 2f);
            float descH = Math.max(22f, descBot + 3f);
            descUi.setHeightSoFar(descH);
            if (descUi.getPosition() != null) descUi.getPosition().setSize(descW, descH);
            if (descPanel.getPosition() != null) descPanel.getPosition().setSize(rowWidth, descH);
            descPanel.addUIElement(descUi).inTL(CONTROL_COL_X, 0f);
            body.addCustom(descPanel, 2f);
        }

        // 2. Numbering Style Row
        CustomPanelAPI numRow = Global.getSettings().createCustom(rowWidth, 30f, subPanelPlugin);
        TooltipMakerAPI numUi = numRow.createUIElement(rowWidth, 30f, false);
        LabelAPI numLbl = numUi.addPara("Numbering Style:", 0f, baseColor);
        DivisionUiWidgets.wrapLabelInPlace(numLbl, CONTROL_COL_X - 14f, 6f, 6f);

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

        // 3. Custom Officer in Tactical Command Title Row
        CustomPanelAPI otcTitleRow = Global.getSettings().createCustom(rowWidth, 30f, subPanelPlugin);
        TooltipMakerAPI otcTitleUi = otcTitleRow.createUIElement(rowWidth, 30f, false);
        LabelAPI otcTitleLbl = otcTitleUi.addPara("Officer in Tactical Command:", 0f, baseColor);
        float otcLblBot = DivisionUiWidgets.wrapLabelInPlace(otcTitleLbl, CONTROL_COL_X - 14f, 6f, 6f);
        customOtcTitleField = otcTitleUi.addTextField(240f, 22f, "graphics/fonts/insignia15LTaa.fnt", 0f);
        customOtcTitleField.setText(namingCfg.getCustomOtcTitle());
        customOtcTitleField.getPosition().inTL(CONTROL_COL_X, 4f);

        String resolvedOtcPreview = DivisionNomenclature.resolveOtcCommanderTitle(
                currentPreset.category,
                true,
                false,
                currentPreset.defaultOtcTitle);
        LabelAPI otcPrevLbl = otcTitleUi.addPara(
                "Active Officer in Tactical Command Tag: [%s]",
                0f,
                gray,
                positive,
                resolvedOtcPreview);
        float prevX = CONTROL_COL_X + 256f;
        float saveBtnX = rowWidth - 182f;
        float otcPrevBot = DivisionUiWidgets.wrapLabelInPlace(otcPrevLbl, saveBtnX - prevX - 10f, prevX, 6f);

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
        bSavePrefixes.getPosition().inTL(saveBtnX, 3f);

        DivisionUiWidgets.resizeRowToFit(
                otcTitleRow, otcTitleUi, rowWidth, Math.max(30f, Math.max(otcLblBot, otcPrevBot) + 4f));
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

            LabelAPI archLbl = archUi.addPara(arch.shortPrefix + ":", 0f, baseColor);
            float archLblBot = DivisionUiWidgets.wrapLabelInPlace(archLbl, CONTROL_COL_X - 14f, 6f, 5f);

            TextFieldAPI tf = archUi.addTextField(240f, 22f, "graphics/fonts/insignia15LTaa.fnt", 0f);
            tf.setText(namingCfg.getPrefixForArchetype(arch));
            tf.getPosition().inTL(CONTROL_COL_X, 3f);
            prefixTextFields.put(arch, tf);

            String sample1 = DivisionNomenclature.formatPlayerDivisionName(arch, 1);
            String sample2 = DivisionNomenclature.formatPlayerDivisionName(arch, 2);
            LabelAPI sampleLbl = archUi.addPara(
                    "Preview:  \"%s\",  \"%s\"",
                    0f,
                    gray,
                    highlight,
                    sample1,
                    sample2);
            float sampleBot = DivisionUiWidgets.wrapLabelInPlace(
                    sampleLbl, rowWidth - (CONTROL_COL_X + 256f) - 8f, CONTROL_COL_X + 256f, 5f);

            DivisionUiWidgets.resizeRowToFit(
                    archRow, archUi, rowWidth, Math.max(28f, Math.max(archLblBot, sampleBot) + 4f));
            archRow.addUIElement(archUi).inTL(0f, 0f);
            body.addCustom(archRow, 2f);
        }
    }

    private void addHullGroupAccordionRow(
            TooltipMakerAPI body,
            float rowWidth,
            DoctrineHullGroup group,
            boolean expanded,
            FleetDoctrineConfig doctrine,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive,
            Color gray) {
        float baseH = 32f;
        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, baseH, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, baseH, false);

        String toggleAct = "TOGGLE_HULL_GROUP:" + group.name();
        String btnLabel = String.format(
                Locale.ROOT,
                "%s %s (%d)",
                expanded ? "[-]" : "[+]",
                group.displayName.toUpperCase(Locale.ROOT),
                group.divisionTypes.length);
        ButtonAPI bGroup = registerButton(
                rowUi.addButton(
                        btnLabel,
                        toggleAct,
                        expanded ? highlight : positive,
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        286f,
                        24f,
                        0f),
                toggleAct);
        if (expanded) {
            bGroup.highlight();
        }
        bGroup.getPosition().inTL(4f, 4f);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < group.divisionTypes.length; i++) {
            DoctrineDivisionType dt = group.divisionTypes[i];
            if (i > 0) sb.append("  |  ");
            sb.append(dt.displayName.replace(" Division", ""))
                    .append(": ")
                    .append(doctrine.getFormation(dt).shortName)
                    .append(" (")
                    .append(doctrine.getOpeningMission(dt).shortName)
                    .append(" -> ")
                    .append(doctrine.getMainMission(dt).shortName);
            if (dt.isCarrierType()) {
                sb.append(", ").append(doctrine.getVoidstrikeTargetingBias(dt).shortName);
                if (doctrine.isVoidstrikeFocusCarriers(dt)) {
                    sb.append(" +CV");
                }
            }
            sb.append(")");
        }
        LabelAPI digestLbl = rowUi.addPara(sb.toString(), 0f, expanded ? baseColor : gray);
        float digestBot = DivisionUiWidgets.wrapLabelInPlace(digestLbl, rowWidth - 308f, 300f, 7f);

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(baseH, digestBot + 5f));
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, expanded ? 4f : 3f);
    }

    private void addDivisionTypeCardHeaderRow(
            TooltipMakerAPI body,
            float rowWidth,
            DoctrineDivisionType dt,
            boolean expanded,
            LineStation currLineStation,
            DivisionFormation currFormation,
            int currSpacing,
            int currInter,
            int currStandoff,
            DivisionMissionType currOpenMis,
            DivisionMissionType currMainMis,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive,
            Color gray) {
        float baseH = 28f;
        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, baseH, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, baseH, false);

        String toggleAct = "TOGGLE_DT_CARD:" + dt.name();
        String btnLabel = (expanded ? "[-] " : "[+] ") + dt.displayName.toUpperCase(Locale.ROOT);
        ButtonAPI bCard = registerButton(
                rowUi.addButton(
                        btnLabel,
                        toggleAct,
                        expanded ? highlight : baseColor,
                        darkColor,
                        Alignment.LMID,
                        CutStyle.ALL,
                        258f,
                        22f,
                        0f),
                toggleAct);
        if (expanded) {
            bCard.highlight();
        }
        bCard.getPosition().inTL(14f, 3f);

        FleetDoctrineConfig docMeta = PermanentDivisionManager.getFleetDoctrine();
        StrikeCraftMissionType metaScOpen = docMeta.getStrikeCraftOpeningMission(dt);
        StrikeCraftMissionType metaScMain = docMeta.getStrikeCraftMainMission(dt);
        String scSuffix = (metaScOpen != StrikeCraftMissionType.MATCH_DIVISION
                || metaScMain != StrikeCraftMissionType.MATCH_DIVISION)
                ? ("  |  SC: " + metaScOpen.shortName + " -> " + metaScMain.shortName)
                : "";
        String meta = String.format(
                Locale.ROOT,
                "%s  |  %s  |  Spacing %du / Interval %du / Standoff %du  |  %s -> %s%s",
                currLineStation.displayName,
                currFormation.shortName,
                currSpacing,
                currInter,
                currStandoff,
                currOpenMis.shortName,
                currMainMis.shortName,
                scSuffix);
        LabelAPI metaLbl = rowUi.addPara(meta, 0f, expanded ? positive : gray);
        float metaBot = DivisionUiWidgets.wrapLabelInPlace(metaLbl, rowWidth - 290f, 282f, 5f);

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(baseH, metaBot + 4f));
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 4f);
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
        LabelAPI durLbl = rowUi.addPara(label, 0f, activeValueColor, label);
        float lblBot = DivisionUiWidgets.wrapLabelInPlace(durLbl, CONTROL_COL_X - 14f, 6f, 5f);

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

        ButtonAPI bExpandAll = registerButton(
                rowUi.addButton(
                        "Expand All",
                        "EXPAND_ALL_HULL_GROUPS",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        116f,
                        22f,
                        0f),
                "EXPAND_ALL_HULL_GROUPS");
        bExpandAll.getPosition().inTL(rowWidth - 116f - 8f - 116f, 3f);

        ButtonAPI bCollapseAll = registerButton(
                rowUi.addButton(
                        "Collapse All",
                        "COLLAPSE_ALL_HULL_GROUPS",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        116f,
                        22f,
                        0f),
                "COLLAPSE_ALL_HULL_GROUPS");
        bCollapseAll.getPosition().inTL(rowWidth - 116f, 3f);

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(28f, lblBot + 4f));
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 4f);
    }


    private final List<DivisionUiWidgets.OtcPennantBox> otcPennantBoxes = new ArrayList<>();

    public static void renderOtcAuthorityPennantBox(
            OtcAuthority authority,
            float boxX,
            float boxY,
            float boxW,
            float boxH,
            boolean isSelected,
            boolean isHovered,
            float alphaMult) {
        if (authority == null) return;
        float bx = (float) Math.round(boxX);
        float by = (float) Math.round(boxY);
        float bw = (float) Math.round(boxW);
        float bh = (float) Math.round(boxH);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // 1. Dark backing plate + subtle golden deployment wash when selected
        if (isSelected) {
            GL11.glColor4f(0.06f, 0.10f, 0.16f, 0.92f * alphaMult);
        } else if (isHovered) {
            GL11.glColor4f(0.07f, 0.12f, 0.20f, 0.88f * alphaMult);
        } else {
            GL11.glColor4f(0.03f, 0.06f, 0.10f, 0.84f * alphaMult);
        }
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(bx, by);
        GL11.glVertex2f(bx + bw, by);
        GL11.glVertex2f(bx + bw, by + bh);
        GL11.glVertex2f(bx, by + bh);
        GL11.glEnd();

        if (isSelected) {
            GL11.glColor4f(1.0f, 0.84f, 0.16f, 0.11f * alphaMult);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(bx + 1f, by + 1f);
            GL11.glVertex2f(bx + bw - 1f, by + 1f);
            GL11.glVertex2f(bx + bw - 1f, by + bh - 1f);
            GL11.glVertex2f(bx + 1f, by + bh - 1f);
            GL11.glEnd();
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        // 2. Render Naval Pennant sprite centered in box
        try {
            SpriteAPI sprite = loadFormationSpriteSafe(authority.getSettingsIconKey(), authority.getSpritePath());
            if (sprite != null) {
                float prevW = sprite.getWidth();
                float prevH = sprite.getHeight();
                float prevCx = sprite.getCenterX();
                float prevCy = sprite.getCenterY();
                Color prevColor = sprite.getColor();
                float prevAlpha = sprite.getAlphaMult();
                float drawSize = (float) Math.round(Math.max(16f, Math.min(bw, bh) - 6f));
                float snapX = (float) Math.round(bx + (bw - drawSize) * 0.5f);
                float snapY = (float) Math.round(by + (bh - drawSize) * 0.5f);
                sprite.setSize(drawSize, drawSize);
                sprite.setCenter(drawSize * 0.5f, drawSize * 0.5f);
                sprite.setColor(Color.WHITE);
                sprite.setAlphaMult((isSelected ? 1.0f : (isHovered ? 0.95f : 0.82f)) * alphaMult);
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

        // 3. Draw deployment-style double-lined golden box when selected, or slate border when unselected
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (isSelected) {
            GL11.glLineWidth(2.0f);
            GL11.glColor4f(1.0f, 0.85f, 0.18f, 0.96f * alphaMult);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(bx, by);
            GL11.glVertex2f(bx + bw, by);
            GL11.glVertex2f(bx + bw, by + bh);
            GL11.glVertex2f(bx, by + bh);
            GL11.glEnd();

            if (bw > 8f && bh > 8f) {
                GL11.glLineWidth(1.0f);
                GL11.glColor4f(1.0f, 0.94f, 0.55f, 0.55f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(bx + 2f, by + 2f);
                GL11.glVertex2f(bx + bw - 2f, by + 2f);
                GL11.glVertex2f(bx + bw - 2f, by + bh - 2f);
                GL11.glVertex2f(bx + 2f, by + bh - 2f);
                GL11.glEnd();
            }
        } else {
            GL11.glLineWidth(isHovered ? 1.5f : 1.0f);
            GL11.glColor4f(0.26f, 0.36f, 0.48f, (isHovered ? 0.88f : 0.48f) * alphaMult);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(bx, by);
            GL11.glVertex2f(bx + bw, by);
            GL11.glVertex2f(bx + bw, by + bh);
            GL11.glVertex2f(bx, by + bh);
            GL11.glEnd();
        }
        GL11.glLineWidth(1.0f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private void addAuthorityDropdownRow(
            TooltipMakerAPI body,
            float rowWidth,
            final OtcAuthority current,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {

        final OtcAuthority[] allAuths = OtcAuthority.values();
        final float cardW = 168f;
        final float cardH = 168f;
        final float cardGap = 24f;
        final float totalCardsW = allAuths.length * cardW + (allAuths.length - 1) * cardGap;
        final float startX = Math.max(CONTROL_COL_X, Math.round((rowWidth - totalCardsW) * 0.5f));
        final float textW = rowWidth - 24f;
        final int maxChars = Math.max(80, Math.round(textW / 7.2f));
        List<String> descLines = DivisionEditorDialog.wrapTextToLines(current.summary, maxChars);
        List<String> posLines = DivisionEditorDialog.wrapTextToLines(current.positiveText, maxChars);
        List<String> negLines = DivisionEditorDialog.wrapTextToLines(current.negativeText, maxChars);
        final float lineStep = 17f;
        final float bannerPanelH = 204f
                + (descLines.size() + posLines.size() + negLines.size()) * lineStep
                + 12f;
        final CustomPanelAPI[] bannerRef = new CustomPanelAPI[1];

        BaseCustomUIPanelPlugin bannerPlugin = new BaseCustomUIPanelPlugin() {
            @Override
            public void renderBelow(float alphaMult) {
                if (!DivisionUiWidgets.isPanelVisibleOnScreen(bannerRef[0])) return;
                PositionAPI p = bannerRef[0].getPosition();
                float px = p.getX();
                float py = p.getY();
                float ph = p.getHeight();
                float cardBottomY = py + ph - 6f - cardH;
                for (int i = 0; i < allAuths.length; i++) {
                    OtcAuthority auth = allAuths[i];
                    float cx = px + startX + i * (cardW + cardGap);
                    renderOtcAuthorityPennantBox(auth, cx, cardBottomY, cardW, cardH, auth == current, false, alphaMult);
                }
            }
        };
        CustomPanelAPI bannerPanel = Global.getSettings().createCustom(rowWidth, bannerPanelH, bannerPlugin);
        bannerRef[0] = bannerPanel;
        for (int i = 0; i < allAuths.length; i++) {
            float relX = startX + i * (cardW + cardGap);
            float relY = bannerPanelH - 6f - cardH;
            otcPennantBoxes.add(new DivisionUiWidgets.OtcPennantBox(allAuths[i], bannerPanel, relX, relY, cardW, cardH));
        }
        LabelAPI headerLbl = Global.getSettings().createLabel(current.dropdownLabel, Fonts.ORBITRON_20AA);
        headerLbl.setColor(new Color(255, 222, 90, 255));
        headerLbl.setAlignment(Alignment.MID);
        bannerPanel.addComponent((UIComponentAPI) headerLbl).setSize(textW, 22f).inTL(12f, 180f);
        DivisionUiWidgets.addTooltip(
                body,
                (UIComponentAPI) headerLbl,
                "OTC Command Style: " + current.dropdownLabel,
                current.summary + " Click any of the four naval pennants above to switch command authority.",
                current.positiveText,
                current.negativeText);

        float textY = 204f;
        Color descColor = new Color(195, 220, 240, 245);
        for (String line : descLines) {
            LabelAPI summaryLbl = Global.getSettings().createLabel(line, Fonts.GROUP_NUM_FONT);
            summaryLbl.setColor(descColor);
            summaryLbl.setAlignment(Alignment.MID);
            bannerPanel.addComponent((UIComponentAPI) summaryLbl).setSize(textW, 16f).inTL(12f, textY);
            textY += lineStep;
        }
        textY += 2f;
        for (String line : posLines) {
            LabelAPI posLbl = Global.getSettings().createLabel(line, Fonts.GROUP_NUM_FONT);
            posLbl.setColor(Misc.getPositiveHighlightColor());
            posLbl.setAlignment(Alignment.MID);
            bannerPanel.addComponent((UIComponentAPI) posLbl).setSize(textW, 16f).inTL(12f, textY);
            textY += lineStep;
        }
        textY += 2f;
        for (String line : negLines) {
            LabelAPI negLbl = Global.getSettings().createLabel(line, Fonts.GROUP_NUM_FONT);
            negLbl.setColor(Misc.getNegativeHighlightColor());
            negLbl.setAlignment(Alignment.MID);
            bannerPanel.addComponent((UIComponentAPI) negLbl).setSize(textW, 16f).inTL(12f, textY);
            textY += lineStep;
        }

        body.addCustom(bannerPanel, 4f);
    }

    private CustomPanelAPI createFleetFormationIconRow(
            float rowWidth,
            float rowHeight,
            final FleetWideFormation formation) {
        final CustomPanelAPI[] ref = new CustomPanelAPI[1];
        BaseCustomUIPanelPlugin plugin = new BaseCustomUIPanelPlugin() {
            @Override
            public void renderBelow(float alphaMult) {
                if (!DivisionUiWidgets.isPanelVisibleOnScreen(ref[0])) return;
                PositionAPI p = ref[0].getPosition();
                DivisionDeploymentDialogOrganizer.renderFleetFormationIcon(
                        formation, p.getX() + CONTROL_COL_X - 18f, p.getY() + p.getHeight() * 0.5f, 28f, alphaMult, true);
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
        LabelAPI fwLbl = rowUi.addPara("Fleet Formation:", 0f, baseColor);
        float fwLblBot = DivisionUiWidgets.wrapLabelInPlace(fwLbl, CONTROL_COL_X - 38f, 6f, 6f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (UIComponentAPI) fwLbl, "Fleet Formation:");

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
        DivisionUiWidgets.addTooltip(
                rowUi,
                bToggle,
                "Fleet Formation: " + current.displayName,
                current.summary);

        String depAct = "SET_ORCHESTRATED_ADVANCE:" + (deployInFormation ? "OFF" : "ON");
        ButtonAPI bDep = registerButton(
                rowUi.addButton(
                        "Orchestrated Advance: " + (deployInFormation ? "On" : "Off"),
                        depAct,
                        deployInFormation ? activeValueColor : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        182f,
                        22f,
                        0f),
                depAct);
        if (deployInFormation) {
            bDep.highlight();
        }
        bDep.getPosition().inTL(CONTROL_COL_X + 244f, 4f);

        LabelAPI fwSumLbl = rowUi.addPara(current.summary, 0f, gray);
        float fwSumX = CONTROL_COL_X + 438f;
        float fwSumBot = DivisionUiWidgets.wrapLabelInPlace(fwSumLbl, rowWidth - fwSumX - 8f, fwSumX, 6f);

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(30f, Math.max(fwLblBot, fwSumBot) + 4f));
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

                LabelAPI optSum = optUi.addPara(option.summary, 0f, isCurrent ? activeValueColor : gray);
                float optSumBot = DivisionUiWidgets.wrapLabelInPlace(
                        optSum, rowWidth - (CONTROL_COL_X + 252f) - 8f, CONTROL_COL_X + 252f, 5f);

                DivisionUiWidgets.resizeRowToFit(optPanel, optUi, rowWidth, Math.max(28f, optSumBot + 4f));
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
        LabelAPI mspLbl = rowUi.addPara("Multi-Div Setpiece:", 0f, baseColor);
        float mspLblBot = DivisionUiWidgets.wrapLabelInPlace(mspLbl, CONTROL_COL_X - 14f, 6f, 6f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (UIComponentAPI) mspLbl, "Multi-Div Setpiece:");

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
        DivisionUiWidgets.addTooltip(
                rowUi,
                bToggle,
                "Multi-Div Setpiece: " + current.displayName,
                current.summary);

        LabelAPI curSumLbl = rowUi.addPara(current.summary, 0f, expanded ? activeValueColor : gray);
        float curSumBot = DivisionUiWidgets.wrapLabelInPlace(
                curSumLbl, rowWidth - (CONTROL_COL_X + 252f) - 8f, CONTROL_COL_X + 252f, 6f);

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(30f, Math.max(mspLblBot, curSumBot) + 4f));
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

                LabelAPI optSumLbl = optUi.addPara(option.summary, 0f, isCurrent ? activeValueColor : gray);
                float optSumBot = DivisionUiWidgets.wrapLabelInPlace(
                        optSumLbl, rowWidth - (CONTROL_COL_X + 252f) - 8f, CONTROL_COL_X + 252f, 5f);

                DivisionUiWidgets.resizeRowToFit(optPanel, optUi, rowWidth, Math.max(28f, optSumBot + 4f));
                optPanel.addUIElement(optUi).inTL(0f, 0f);
                body.addCustom(optPanel, 2f);
            }
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

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 30f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 30f, false);

        String label = play.displayName;
        LabelAPI playLbl = rowUi.addPara(label, 0f, enabled ? activeValueColor : gray, label);
        float lblBot = DivisionUiWidgets.wrapLabelInPlace(playLbl, CONTROL_COL_X - 14f, 6f, 6f);
        DivisionUiWidgets.addTooltip(
                rowUi,
                (UIComponentAPI) playLbl,
                "Tactical Play: " + play.displayName,
                play.summary);

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

        LabelAPI playSumLbl = rowUi.addPara(play.summary, 0f, gray);
        playSumLbl.autoSizeToWidth(Math.max(120f, rowWidth - (CONTROL_COL_X + 136f) - 8f)).inTL(CONTROL_COL_X + 136f, 6f);
        float sumBot = 6f + Math.max(16f, playSumLbl.getPosition().getHeight());

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(30f, Math.max(lblBot, sumBot) + 4f));
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);

        body.addCustom(rowPanel, 3f);
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
                if (!DivisionUiWidgets.isPanelVisibleOnScreen(rowPanelRef[0])) return;
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
        LabelAPI fmLbl = rowUi.addPara(labelText, 0f, activeValueColor, labelText);
        float fmLblBot = DivisionUiWidgets.wrapLabelInPlace(fmLbl, CONTROL_COL_X - 14f, 6f, 5f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (UIComponentAPI) fmLbl, labelText);
        float summaryX = CONTROL_COL_X + cols * (btnW + btnGap) + 8f;
        LabelAPI fmSumLbl = rowUi.addPara(currentFormation.summary, 0f, gray);
        float fmSumBot = DivisionUiWidgets.wrapLabelInPlace(fmSumLbl, rowWidth - summaryX - 8f, summaryX, 4f);

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

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(rowH, Math.max(fmLblBot, fmSumBot) + 4f));
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

    private static final java.util.Map<String, SpriteAPI> FORMATION_SPRITE_CACHE = new java.util.HashMap<>();

    public static SpriteAPI loadFormationSpriteSafe(String iconKey, String path) {
        String cacheKey = (iconKey != null ? iconKey : "") + "|" + (path != null ? path : "");
        SpriteAPI cached = FORMATION_SPRITE_CACHE.get(cacheKey);
        if (cached != null && cached.getTextureId() > 0) {
            return cached;
        }
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
        if (sprite != null && sprite.getTextureId() > 0) {
            FORMATION_SPRITE_CACHE.put(cacheKey, sprite);
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

        // 2. Dark charcoal outer frame + 3. Beveled binary-rank metallic square border batched in a single GL_LINES pass
        float tlR, tlG, tlB;
        float brR, brG, brB;
        if (isFlagshipDivision) {
            tlR = 248f / 255f; tlG = 218f / 255f; tlB = 132f / 255f;
            brR = 198f / 255f; brG = 146f / 255f; brB = 44f / 255f;
        } else {
            tlR = 232f / 255f; tlG = 239f / 255f; tlB = 246f / 255f;
            brR = 154f / 255f; brG = 166f / 255f; brB = 180f / 255f;
        }
        GL11.glLineWidth(1.2f);
        GL11.glBegin(GL11.GL_LINES);
        // Outer charcoal frame (#303842)
        GL11.glColor4f(48f / 255f, 56f / 255f, 66f / 255f, 0.92f * alphaMult);
        GL11.glVertex2f(x0 - 0.8f, y0 - 0.8f);
        GL11.glVertex2f(x1 + 0.8f, y0 - 0.8f);
        GL11.glVertex2f(x1 + 0.8f, y0 - 0.8f);
        GL11.glVertex2f(x1 + 0.8f, y1 + 0.8f);
        GL11.glVertex2f(x1 + 0.8f, y1 + 0.8f);
        GL11.glVertex2f(x0 - 0.8f, y1 + 0.8f);
        GL11.glVertex2f(x0 - 0.8f, y1 + 0.8f);
        GL11.glVertex2f(x0 - 0.8f, y0 - 0.8f);
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

        GL11.glLineWidth(1.0f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
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

        LabelAPI unitLbl = rowUi.addPara(labelText, 0f, activeValueColor, labelText);
        float unitLblBot = DivisionUiWidgets.wrapLabelInPlace(unitLbl, CONTROL_COL_X - 14f, 6f, 5f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (UIComponentAPI) unitLbl, labelText);

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

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(28f, unitLblBot + 4f));
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

        LabelAPI limLbl = rowUi.addPara(
                "%s:  %s",
                0f,
                activeValueColor,
                labelText,
                String.valueOf(currentValue));
        float limLblBot = DivisionUiWidgets.wrapLabelInPlace(limLbl, CONTROL_COL_X - 14f, 6f, 5f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (UIComponentAPI) limLbl, labelText);

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

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(28f, limLblBot + 4f));
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
            savedScrollY = 0f;
            rebuildContent();
            return;
        }
        if ("TAB:OTC_COMMAND".equals(cmd)) {
            activeTab = ActiveTab.BATTLE_GROUP_COMMAND;
            savedScrollY = 0f;
            rebuildContent();
            return;
        }
        if ("TAB:NAMING".equals(cmd)) {
            activeTab = ActiveTab.NAMING_SCHEME;
            savedScrollY = 0f;
            rebuildContent();
            return;
        }
        if ("EXPAND_ALL_HULL_GROUPS".equals(cmd)) {
            for (DoctrineHullGroup g : DoctrineHullGroup.values()) {
                expandedHullGroups.add(g);
            }
            collapsedDivisionTypes.clear();
            rebuildContent();
            return;
        }
        if ("COLLAPSE_ALL_HULL_GROUPS".equals(cmd)) {
            expandedHullGroups.clear();
            rebuildContent();
            return;
        }
        if (cmd.startsWith("TOGGLE_HULL_GROUP:")) {
            try {
                DoctrineHullGroup g = DoctrineHullGroup.valueOf(cmd.substring("TOGGLE_HULL_GROUP:".length()));
                if (!expandedHullGroups.remove(g)) {
                    expandedHullGroups.add(g);
                }
                rebuildContent();
            } catch (IllegalArgumentException ignored) {
            }
            return;
        }
        if (cmd.startsWith("TOGGLE_DT_CARD:")) {
            try {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(cmd.substring("TOGGLE_DT_CARD:".length()));
                if (!collapsedDivisionTypes.remove(dt)) {
                    collapsedDivisionTypes.add(dt);
                }
                rebuildContent();
            } catch (IllegalArgumentException ignored) {
            }
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
                DivisionDeploymentDialogOrganizer.autoHarmonizePermanentDivisionsToFleetFormation(fw);
            }
            expandedDropdownKey = null;
            rebuildContent();
            return;
        }
        if (cmd.startsWith("SET_ORCHESTRATED_ADVANCE:")) {
            boolean enabled = "ON".equals(cmd.substring("SET_ORCHESTRATED_ADVANCE:".length()));
            PermanentDivisionManager.updateOrchestratedAdvance(enabled);
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
        if (cmd.startsWith("SET_SKILL_COORD:")) {
            String rest = cmd.substring("SET_SKILL_COORD:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                ShipSkillCoordinationMode mode = ShipSkillCoordinationMode.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeShipSkillCoordination(dt, mode);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("TOGGLE_RAMMING_OPTS:")) {
            try {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(cmd.substring("TOGGLE_RAMMING_OPTS:".length()));
                expandedHullGroups.add(DoctrineHullGroup.forDivisionType(dt));
                collapsedDivisionTypes.remove(dt);
                if (!expandedRammingOptionsTypes.remove(dt)) {
                    expandedRammingOptionsTypes.add(dt);
                }
                rebuildContent();
            } catch (IllegalArgumentException ignored) {
            }
            return;
        }
        if (cmd.startsWith("SET_RAM_MODE:")) {
            String rest = cmd.substring("SET_RAM_MODE:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                DivisionRammingMode mode = DivisionRammingMode.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeRammingMode(dt, mode);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_RAM_SCOPE:")) {
            String rest = cmd.substring("SET_RAM_SCOPE:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                DivisionRammingTargetScope scope = DivisionRammingTargetScope.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeRammingTargetScope(dt, scope);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_RAM_SLOTS:")) {
            String rest = cmd.substring("SET_RAM_SLOTS:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                int slots = Integer.parseInt(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeMaxRammingSlots(dt, slots);
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
            savedScrollY = 0f;
            rebuildContent();
            return;
        }
        if ("TAB:AUTO_ASSIGN".equals(cmd)) {
            activeTab = ActiveTab.AUTO_ASSIGN_SCHEMES;
            savedScrollY = 0f;
            rebuildContent();
            return;
        }
        if ("TAB:HULLS".equals(cmd)) {
            activeTab = ActiveTab.HULL_LIMITS;
            savedScrollY = 0f;
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
        if (cmd.startsWith("TOGGLE_CARRIER_OPTS:")) {
            try {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(cmd.substring("TOGGLE_CARRIER_OPTS:".length()));
                expandedHullGroups.add(DoctrineHullGroup.forDivisionType(dt));
                collapsedDivisionTypes.remove(dt);
                if (!expandedCarrierOptionsTypes.remove(dt)) {
                    expandedCarrierOptionsTypes.add(dt);
                }
                rebuildContent();
            } catch (IllegalArgumentException ignored) {
            }
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
        if (cmd.startsWith("SET_VS_BIAS:")) {
            String rest = cmd.substring("SET_VS_BIAS:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                VoidstrikeTargetingBias bias = VoidstrikeTargetingBias.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeVoidstrikeTargetingBias(dt, bias);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_VS_CV_FOCUS:")) {
            String rest = cmd.substring("SET_VS_CV_FOCUS:".length());
            int sep = rest.indexOf(':');
            String dtStr = sep > 0 ? rest.substring(0, sep) : rest;
            DoctrineDivisionType dt = DoctrineDivisionType.valueOf(dtStr);
            boolean curr = PermanentDivisionManager.getFleetDoctrine().isVoidstrikeFocusCarriers(dt);
            PermanentDivisionManager.updateDivisionTypeVoidstrikeFocusCarriers(dt, !curr);
            rebuildContent();
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
        if (cmd.startsWith("SET_VS_GEOM:")) {
            String rest = cmd.substring("SET_VS_GEOM:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                VoidstrikeAttackGeometry geom = VoidstrikeAttackGeometry.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeVoidstrikeAttackGeometry(dt, geom);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_VS_SPLIT:")) {
            String rest = cmd.substring("SET_VS_SPLIT:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                VoidstrikeSplitMode split = VoidstrikeSplitMode.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeVoidstrikeSplitMode(dt, split);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_VS_GUN:")) {
            String rest = cmd.substring("SET_VS_GUN:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                VoidstrikeGunneryMode gun = VoidstrikeGunneryMode.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeVoidstrikeGunneryMode(dt, gun);
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_VS_AOA:")) {
            String rest = cmd.substring("SET_VS_AOA:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                VoidstrikeAoaCorrection aoa = VoidstrikeAoaCorrection.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeVoidstrikeAoaCorrection(dt, aoa);
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
        if (cmd.startsWith("SET_SC_OPEN_MIS:")) {
            String rest = cmd.substring("SET_SC_OPEN_MIS:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                StrikeCraftMissionType mis = StrikeCraftMissionType.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeStrikeCraftMission(dt, true, mis);
                expandedDropdownKey = null;
                rebuildContent();
            }
            return;
        }
        if (cmd.startsWith("SET_SC_MAIN_MIS:")) {
            String rest = cmd.substring("SET_SC_MAIN_MIS:".length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                DoctrineDivisionType dt = DoctrineDivisionType.valueOf(rest.substring(0, sep));
                StrikeCraftMissionType mis = StrikeCraftMissionType.valueOf(rest.substring(sep + 1));
                PermanentDivisionManager.updateDivisionTypeStrikeCraftMission(dt, false, mis);
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
            if (event.isLMBDownEvent()) {
                float ex = event.getX();
                float ey = event.getY();
                for (DivisionUiWidgets.OtcPennantBox box : otcPennantBoxes) {
                    if (box != null && box.contains(ex, ey)) {
                        event.consume();
                        try {
                            Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f);
                        } catch (Throwable ignored) {
                        }
                        handleAction("SET_OTC_AUTH:" + box.authority.name());
                        return;
                    }
                }
            }
            if (!event.isMouseUpEvent()) {
                event.consume();
            }
        }
    }

    private final DivisionUiWidgets.ButtonRegistrar buttonRegistrar = new DivisionUiWidgets.ButtonRegistrar() {
        @Override
        public ButtonAPI register(ButtonAPI button, String actionId) {
            return registerButton(button, actionId);
        }
    };

    private final DivisionUiWidgets.ButtonPressRelay buttonPressRelay = new DivisionUiWidgets.ButtonPressRelay() {
        @Override
        public void onButtonPressed(Object buttonId) {
            FleetDoctrineDialog.this.buttonPressed(buttonId);
        }
    };

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
        DivisionSetpieceAttack[] opts = DivisionSetpieceAttack.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].shortName;
            acts[i] = actionPrefix + opts[i].name();
            if (opts[i] == current) sel = i;
        }
        DivisionUiWidgets.addOptionStripRow(
                body, rowWidth, 28f, subPanelPlugin, buttonRegistrar,
                labelText, current.shortName, baseColor, 6f, 5f, CONTROL_COL_X,
                labels, acts, sel, 58f, 22f, 62f, baseColor, darkColor, activeValueColor,
                null, true, 0f, activeValueColor, current.summary, gray, 5f);
    }

    private void addShipSkillCoordinationRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            ShipSkillCoordinationMode currentCoord,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {
        ShipSkillCoordinationMode[] opts = ShipSkillCoordinationMode.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = actionPrefix + opts[i].name();
            if (opts[i] == currentCoord) sel = i;
        }
        DivisionUiWidgets.addOptionStripRow(
                body, rowWidth, 28f, subPanelPlugin, buttonRegistrar,
                labelText, labelText, activeValueColor, 6f, 5f, CONTROL_COL_X,
                labels, acts, sel, 116f, 22f, 122f, baseColor, darkColor, activeValueColor,
                null, true, 0f, activeValueColor,
                currentCoord != null ? currentCoord.summary : null, gray, 6f);
    }

    private void addRammingOptionsToggleRow(
            TooltipMakerAPI body,
            float rowWidth,
            DoctrineDivisionType dt,
            boolean expanded,
            DivisionRammingMode currRamMode,
            DivisionRammingTargetScope currRamScope,
            int currRamSlots,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive,
            Color gray) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 28f, false);

        LabelAPI ramOptLbl = rowUi.addPara("Ramming Options:", 0f, baseColor);
        float ramLblBot = DivisionUiWidgets.wrapLabelInPlace(ramOptLbl, CONTROL_COL_X - 14f, 6f, 5f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (UIComponentAPI) ramOptLbl, "Ramming Options:");

        String toggleAct = "TOGGLE_RAMMING_OPTS:" + dt.name();
        String btnText = (expanded ? "[^] " : "[v] ") + "Ramming Options";
        ButtonAPI bToggle = registerButton(
                rowUi.addButton(
                        btnText,
                        toggleAct,
                        expanded ? highlight : (currRamMode != DivisionRammingMode.OFF ? positive : baseColor),
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        204f,
                        22f,
                        0f),
                toggleAct);
        if (expanded || currRamMode != DivisionRammingMode.OFF) {
            bToggle.highlight();
        }
        bToggle.getPosition().inTL(CONTROL_COL_X, 3f);

        String summary = (currRamMode == DivisionRammingMode.OFF)
                ? "Off (Auto-enabled when auto-assigner detects ram-capable ships in a division)"
                : String.format(
                        Locale.ROOT,
                        "%s  |  %s  |  Max Slots: %d",
                        currRamMode.displayName,
                        currRamScope.displayName,
                        currRamSlots);
        LabelAPI ramSumLbl = rowUi.addPara(summary, 0f, currRamMode != DivisionRammingMode.OFF ? positive : gray);
        float ramSumBot = DivisionUiWidgets.wrapLabelInPlace(
                ramSumLbl, rowWidth - (CONTROL_COL_X + 216f) - 8f, CONTROL_COL_X + 216f, 5f);

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(28f, Math.max(ramLblBot, ramSumBot) + 4f));
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);
    }

    private void addRammingModeRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            DivisionRammingMode currentMode,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {
        DivisionRammingMode[] opts = DivisionRammingMode.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].shortName;
            acts[i] = actionPrefix + opts[i].name();
            if (opts[i] == currentMode) sel = i;
        }
        DivisionUiWidgets.addOptionStripRow(
                body, rowWidth, 28f, subPanelPlugin, buttonRegistrar,
                labelText, labelText, activeValueColor, 6f, 5f, CONTROL_COL_X,
                labels, acts, sel, 76f, 22f, 80f, baseColor, darkColor, activeValueColor,
                null, true, 0f, activeValueColor,
                currentMode != null ? currentMode.summary : null, gray, 6f);
    }

    private void addRammingTargetScopeRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            DivisionRammingTargetScope currentScope,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {
        DivisionRammingTargetScope[] opts = DivisionRammingTargetScope.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = actionPrefix + opts[i].name();
            if (opts[i] == currentScope) sel = i;
        }
        DivisionUiWidgets.addOptionStripRow(
                body, rowWidth, 28f, subPanelPlugin, buttonRegistrar,
                labelText, labelText, activeValueColor, 6f, 5f, CONTROL_COL_X,
                labels, acts, sel, 116f, 22f, 122f, baseColor, darkColor, activeValueColor,
                null, true, 0f, activeValueColor,
                currentScope != null ? currentScope.summary : null, gray, 6f);
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
        LineStation[] opts = LineStation.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = actionPrefix + opts[i].name();
            if (opts[i] == currentSpace) sel = i;
        }
        DivisionUiWidgets.addOptionStripRow(
                body, rowWidth, 28f, subPanelPlugin, buttonRegistrar,
                labelText, labelText, activeValueColor, 6f, 5f, CONTROL_COL_X,
                labels, acts, sel, 110f, 22f, 118f, baseColor, darkColor, activeValueColor,
                null, true, 0f, activeValueColor, null, null, 5f);
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
        DivisionMovementStickiness[] opts = DivisionMovementStickiness.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = actionPrefix + opts[i].name();
            if (opts[i] == currentStickiness) sel = i;
        }
        DivisionUiWidgets.addOptionStripRow(
                body, rowWidth, 28f, subPanelPlugin, buttonRegistrar,
                labelText, labelText, activeValueColor, 6f, 5f, CONTROL_COL_X,
                labels, acts, sel, 74f, 22f, 80f, baseColor, darkColor, activeValueColor,
                null, true, 0f, activeValueColor,
                currentStickiness != null ? currentStickiness.summary : null, gray, 6f);
    }

    private void addCarrierOptionsToggleRow(
            TooltipMakerAPI body,
            float rowWidth,
            DoctrineDivisionType dt,
            boolean expanded,
            StrikeCraftMissionType currScOpenMis,
            StrikeCraftMissionType currScMainMis,
            VoidstrikeTargetObservance currVsObs,
            VoidstrikeCoordinationMode currVsCoord,
            SquadronFormation currFtrFm,
            SquadronFormation currFtrVsFm,
            SquadronFormation currBmbFm,
            SquadronFormation currBmbVsFm,
            int currVsDepth,
            int currVsWidth,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive,
            Color gray) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 28f, false);

        LabelAPI carOptLbl = rowUi.addPara("Strike Craft Options:", 0f, baseColor);
        float carLblBot = DivisionUiWidgets.wrapLabelInPlace(carOptLbl, CONTROL_COL_X - 14f, 6f, 5f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (UIComponentAPI) carOptLbl, "Strike Craft Options:");

        String toggleAct = "TOGGLE_CARRIER_OPTS:" + dt.name();
        String btnText = (expanded ? "[^] " : "[v] ") + "Strike Craft Options";
        ButtonAPI bToggle = registerButton(
                rowUi.addButton(
                        btnText,
                        toggleAct,
                        expanded ? highlight : positive,
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        204f,
                        22f,
                        0f),
                toggleAct);
        if (expanded) {
            bToggle.highlight();
        }
        bToggle.getPosition().inTL(CONTROL_COL_X, 3f);

        FleetDoctrineConfig docNow = PermanentDivisionManager.getFleetDoctrine();
        String biasSummary = docNow.getVoidstrikeTargetingBias(dt).shortName
                + (docNow.isVoidstrikeFocusCarriers(dt) ? " (+Carriers)" : "");
        String scMisSummary = (currScOpenMis == currScMainMis)
                ? currScOpenMis.shortName
                : (currScOpenMis.shortName + " -> " + currScMainMis.shortName);
        String summary = String.format(
                Locale.ROOT,
                "Missions: %s  |  Bias: %s  |  Voidstrike: %s / %s  |  %dx%d  |  Ftr: %s/%s  |  Bmb: %s/%s",
                scMisSummary,
                biasSummary,
                currVsObs.displayName,
                currVsCoord.displayName,
                currVsDepth,
                currVsWidth,
                currFtrFm.shortName,
                currFtrVsFm.shortName,
                currBmbFm.shortName,
                currBmbVsFm.shortName);
        LabelAPI carSumLbl = rowUi.addPara(summary, 0f, expanded ? highlight : gray);
        float carSumBot = DivisionUiWidgets.wrapLabelInPlace(
                carSumLbl, rowWidth - (CONTROL_COL_X + 216f) - 8f, CONTROL_COL_X + 216f, 5f);

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(28f, Math.max(carLblBot, carSumBot) + 4f));
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        body.addCustom(rowPanel, 2f);
    }

    private void addVoidstrikeTargetingBiasRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            VoidstrikeTargetingBias currentBias,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {
        VoidstrikeTargetingBias[] opts = VoidstrikeTargetingBias.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = actionPrefix + opts[i].name();
            if (opts[i] == currentBias) sel = i;
        }
        DivisionUiWidgets.addOptionStripRow(
                body, rowWidth, 28f, subPanelPlugin, buttonRegistrar,
                labelText, labelText, activeValueColor, 6f, 5f, CONTROL_COL_X,
                labels, acts, sel, 136f, 22f, 142f, baseColor, darkColor, activeValueColor,
                null, true, 0f, activeValueColor,
                currentBias != null ? currentBias.summary : null, gray, 6f);
    }

    private void addVoidstrikeFocusCarriersRow(
            TooltipMakerAPI body,
            float rowWidth,
            boolean focusCarriers,
            String toggleActionId,
            Color baseColor,
            Color highlight,
            Color positive,
            Color gray) {
        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 28f, subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 28f, false);
        String labelText = "Carrier Priority: " + (focusCarriers ? "Focus On" : "Standard");
        LabelAPI rowLbl = rowUi.addPara(labelText, 0f, focusCarriers ? positive : baseColor, labelText);
        float rowLblBot = DivisionUiWidgets.wrapLabelInPlace(rowLbl, CONTROL_COL_X - 14f, 6f, 5f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (UIComponentAPI) rowLbl, labelText);

        ButtonAPI cb = registerCheckbox(
                rowUi.addCheckbox(
                        180f,
                        22f,
                        "Focus on Carriers",
                        toggleActionId,
                        focusCarriers ? highlight : baseColor,
                        ButtonAPI.UICheckboxSize.SMALL,
                        0f),
                toggleActionId,
                focusCarriers);
        cb.getPosition().inTL(CONTROL_COL_X, 3f);

        LabelAPI sumLbl = rowUi.addPara(
                "Prioritize enemy Carrier divisions and carrier hulls within target bias.",
                0f,
                gray);
        float sumBot = DivisionUiWidgets.wrapLabelInPlace(
                sumLbl, rowWidth - (CONTROL_COL_X + 194f) - 8f, CONTROL_COL_X + 194f, 6f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (UIComponentAPI) sumLbl, labelText);

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(28f, Math.max(rowLblBot, sumBot) + 4f));
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
        VoidstrikeTargetObservance[] opts = VoidstrikeTargetObservance.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = actionPrefix + opts[i].name();
            if (opts[i] == currentObs) sel = i;
        }
        DivisionUiWidgets.addOptionStripRow(
                body, rowWidth, 28f, subPanelPlugin, buttonRegistrar,
                labelText, labelText, activeValueColor, 6f, 5f, CONTROL_COL_X,
                labels, acts, sel, 84f, 22f, 90f, baseColor, darkColor, activeValueColor,
                null, true, 0f, activeValueColor,
                currentObs != null ? currentObs.summary : null, gray, 6f);
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
        VoidstrikeCoordinationMode[] opts = VoidstrikeCoordinationMode.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = actionPrefix + opts[i].name();
            if (opts[i] == currentCoord) sel = i;
        }
        DivisionUiWidgets.addOptionStripRow(
                body, rowWidth, 28f, subPanelPlugin, buttonRegistrar,
                labelText, labelText, activeValueColor, 6f, 5f, CONTROL_COL_X,
                labels, acts, sel, 116f, 22f, 122f, baseColor, darkColor, activeValueColor,
                null, true, 0f, activeValueColor,
                currentCoord != null ? currentCoord.summary : null, gray, 6f);
    }

    private void addVoidstrikeAttackGeometryRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            VoidstrikeAttackGeometry currentGeom,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {
        VoidstrikeAttackGeometry[] opts = VoidstrikeAttackGeometry.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = actionPrefix + opts[i].name();
            if (opts[i] == currentGeom) sel = i;
        }
        DivisionUiWidgets.addOptionStripRow(
                body, rowWidth, 28f, subPanelPlugin, buttonRegistrar,
                labelText, labelText, activeValueColor, 6f, 5f, CONTROL_COL_X,
                labels, acts, sel, 94f, 22f, 100f, baseColor, darkColor, activeValueColor,
                null, true, 0f, activeValueColor,
                currentGeom != null ? currentGeom.summary : null, gray, 6f);
    }

    private void addVoidstrikeSplitModeRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            VoidstrikeSplitMode currentSplit,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {
        VoidstrikeSplitMode[] opts = VoidstrikeSplitMode.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = actionPrefix + opts[i].name();
            if (opts[i] == currentSplit) sel = i;
        }
        DivisionUiWidgets.addOptionStripRow(
                body, rowWidth, 28f, subPanelPlugin, buttonRegistrar,
                labelText, labelText, activeValueColor, 6f, 5f, CONTROL_COL_X,
                labels, acts, sel, 94f, 22f, 100f, baseColor, darkColor, activeValueColor,
                null, true, 0f, activeValueColor,
                currentSplit != null ? currentSplit.summary : null, gray, 6f);
    }

    private void addVoidstrikeGunneryModeRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            VoidstrikeGunneryMode currentGun,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {
        VoidstrikeGunneryMode[] opts = VoidstrikeGunneryMode.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = actionPrefix + opts[i].name();
            if (opts[i] == currentGun) sel = i;
        }
        DivisionUiWidgets.addOptionStripRow(
                body, rowWidth, 28f, subPanelPlugin, buttonRegistrar,
                labelText, labelText, activeValueColor, 6f, 5f, CONTROL_COL_X,
                labels, acts, sel, 102f, 22f, 108f, baseColor, darkColor, activeValueColor,
                null, true, 0f, activeValueColor,
                currentGun != null ? currentGun.summary : null, gray, 6f);
    }

    private void addVoidstrikeAoaCorrectionRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            VoidstrikeAoaCorrection currentAoa,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {
        VoidstrikeAoaCorrection[] opts = VoidstrikeAoaCorrection.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = actionPrefix + opts[i].name();
            if (opts[i] == currentAoa) sel = i;
        }
        DivisionUiWidgets.addOptionStripRow(
                body, rowWidth, 28f, subPanelPlugin, buttonRegistrar,
                labelText, labelText, activeValueColor, 6f, 5f, CONTROL_COL_X,
                labels, acts, sel, 94f, 22f, 100f, baseColor, darkColor, activeValueColor,
                null, true, 0f, activeValueColor,
                currentAoa != null ? currentAoa.summary : null, gray, 6f);
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
        DivisionUiWidgets.addVoidstrikeDepthRow(
                body, rowWidth, 28f, CONTROL_COL_X, subPanelPlugin, buttonRegistrar,
                labelText, activeValueColor, 6f, 5f, currentValue, actionPrefix,
                true, false, false, 40f, 22f, 46f, baseColor, darkColor, activeValueColor, activeValueColor);
    }

    private void addSquadronFormationRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            SquadronFormation currentFormation,
            SquadronFormation[] options,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor) {
        DivisionUiWidgets.addSquadronFormationRow(
                body, rowWidth, CONTROL_COL_X, buttonRegistrar, buttonPressRelay,
                labelText, currentFormation, options, actionPrefix,
                false, false, false, baseColor, darkColor, activeValueColor, activeValueColor);
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
        DivisionUiWidgets.addMissionDropdownRow(
                body, rowWidth, CONTROL_COL_X, subPanelPlugin, buttonRegistrar,
                labelText, dropdownKey, expandedDropdownKey, currentMission, primaryOptions,
                false, false, false, false, selectActionPrefix,
                baseColor, darkColor, activeValueColor, gray);
    }

    private void addStrikeCraftMissionDropdownRow(
            TooltipMakerAPI body,
            float rowWidth,
            String labelText,
            String dropdownKey,
            StrikeCraftMissionType currentMission,
            String selectActionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeValueColor,
            Color gray) {
        DivisionUiWidgets.addStrikeCraftMissionDropdownRow(
                body, rowWidth, CONTROL_COL_X, subPanelPlugin, buttonRegistrar,
                labelText, dropdownKey, expandedDropdownKey, currentMission,
                false, false, selectActionPrefix,
                baseColor, darkColor, activeValueColor, gray);
    }

    @Override
    public void renderBelow(float alphaMult) {
        if (closed || contentContainer == null) return;
        DivisionUiWidgets.renderModalDialogBackdrop(
                contentContainer, 0.02f, 0.03f, 0.05f, 0.78f, 0.05f, 0.08f, 0.12f, 0.88f, alphaMult);
    }
}
