package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.PermanentDivisionManager.DoctrineOption;
import divisionai.PermanentDivisionManager.DivisionFormation;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.StrikeCraftMissionType;
import divisionai.PermanentDivisionManager.DivisionMovementStickiness;
import divisionai.PermanentDivisionManager.DivisionRammingMode;
import divisionai.PermanentDivisionManager.DivisionRammingTargetScope;
import divisionai.PermanentDivisionManager.DivisionSetpieceAttack;
import divisionai.PermanentDivisionManager.FleetDoctrineConfig;
import divisionai.PermanentDivisionManager.LineStation;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import divisionai.PermanentDivisionManager.SquadronFormation;
import divisionai.PermanentDivisionManager.ShipSkillCoordinationMode;
import divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection;
import divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry;
import divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode;
import divisionai.PermanentDivisionManager.VoidstrikeGunneryMode;
import divisionai.PermanentDivisionManager.VoidstrikeSplitMode;
import divisionai.PermanentDivisionManager.VoidstrikeTargetingBias;
import divisionai.PermanentDivisionManager.VoidstrikeTargetObservance;

import java.awt.Color;
import java.util.List;
import java.util.Locale;

/**
 * Screen 2 ("Configure Division") of {@link DivisionEditorDialog}, providing individual division
 * callsign/posture/archetype controls, formation/mission/Voidstrike overrides, and two-column ship assignment.
 */
final class DivisionConfigureSubscreen {

    private static final float DIALOG_WIDTH = DivisionEditorDialog.DIALOG_WIDTH;
    private static final float DIALOG_HEIGHT = DivisionEditorDialog.DIALOG_HEIGHT;
    private static final float CONTROL_COL_X = DivisionEditorDialog.CONTROL_COL_X;

    private DivisionConfigureSubscreen() {
    }

    // ========================================================================================
    // SCREEN 2: DEDICATED INDIVIDUAL DIVISION SCREEN ("wallops into its own screen")
    // ========================================================================================
    static void buildSingleDivisionScreen(final DivisionEditorDialog dialog, CustomPanelAPI container, PermanentDivisionSpec spec) {
        CampaignFleetAPI fleet = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
        List<PermanentDivisionSpec> allDivs = PermanentDivisionManager.getPermanentDivisions();
        List<FleetMemberAPI> divMembers = PermanentDivisionManager.getMembersInDivision(spec, fleet);
        List<FleetMemberAPI> unassigned = PermanentDivisionManager.getUnassignedCombatMembers(fleet, allDivs);

        Color baseColor = Misc.getBasePlayerColor();
        Color darkColor = Misc.getDarkPlayerColor();
        Color highlight = Misc.getHighlightColor();
        Color positive = Misc.getPositiveHighlightColor();
        Color gray = Misc.getGrayColor();

        buildDivisionHeaderSection(dialog, container, spec, baseColor, darkColor, highlight, positive);

        float bodyTop = 108f;
        float bodyHeight = DIALOG_HEIGHT - bodyTop - 12f;
        float colWidth = (DIALOG_WIDTH - 36f) * 0.5f;

        buildDivisionLeftSettingsAndShipsColumn(
                dialog, container, spec, fleet, divMembers, bodyTop, bodyHeight, colWidth,
                baseColor, darkColor, highlight, positive, gray);

        buildDivisionRightPoolColumn(
                dialog, container, spec, fleet, allDivs, unassigned, bodyTop, bodyHeight, colWidth,
                baseColor, darkColor, highlight, gray);
    }

    private static void buildDivisionHeaderSection(
            final DivisionEditorDialog dialog,
            CustomPanelAPI container,
            PermanentDivisionSpec spec,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive) {
        // Top-right [X] button
        TooltipMakerAPI topCloseUi = container.createUIElement(36f, 24f, false);
        ButtonAPI bTopX = dialog.registerButton(
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

        // 1. Top Control & Doctrine Header (height 96f; Division Type moved to dropdown per UI Item G)
        float headerH = 96f;
        TooltipMakerAPI top = container.createUIElement(DIALOG_WIDTH - 24f, headerH, false);

        // Row A: Back button ("<- Fleet Organization"), Title, Disband button, Close button
        CustomPanelAPI navRow = Global.getSettings().createCustom(DIALOG_WIDTH - 72f, 28f, dialog.subPanelPlugin);
        TooltipMakerAPI navUi = navRow.createUIElement(DIALOG_WIDTH - 72f, 28f, false);
        ButtonAPI bBack = dialog.registerButton(
                navUi.addButton(
                        "<- Fleet Organization",
                        "BACK_TO_LIST",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        184f,
                        24f,
                        0f),
                "BACK_TO_LIST");
        bBack.getPosition().inTL(0f, 2f);

        navUi.setTitleOrbitronLarge();
        LabelAPI titleLbl = navUi.addTitle(
                spec.name.toUpperCase(Locale.ROOT) + " - " + spec.archetype.shortPrefix.toUpperCase(Locale.ROOT),
                highlight);
        float disbandX = DIALOG_WIDTH - 72f - 130f - 8f - 144f;
        DivisionUiWidgets.wrapLabelInPlace(titleLbl, disbandX - 198f - 10f, 198f, 3f);

        String delAct = "DELETE_DIV:" + spec.id;
        ButtonAPI bDisband = dialog.registerButton(
                navUi.addButton(
                        "Disband Division",
                        delAct,
                        Misc.getNegativeHighlightColor(),
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        144f,
                        24f,
                        0f),
                delAct);
        bDisband.getPosition().inTL(DIALOG_WIDTH - 72f - 130f - 8f - 144f, 2f);

        ButtonAPI bClose = dialog.registerButton(
                navUi.addButton(
                        "Close [Esc]",
                        "CLOSE",
                        Misc.getNegativeHighlightColor(),
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        130f,
                        24f,
                        0f),
                "CLOSE");
        bClose.getPosition().inTL(DIALOG_WIDTH - 72f - 130f, 2f);
        navUi.setHeightSoFar(28f);
        navRow.addUIElement(navUi).inTL(0f, 0f);
        top.addCustom(navRow, 2f);

        // Row B: Editable Callsign TextField + Rename Button + Doctrine Posture Buttons on the same row
        CustomPanelAPI nameRow = Global.getSettings().createCustom(DIALOG_WIDTH - 24f, 28f, dialog.subPanelPlugin);
        TooltipMakerAPI nameUi = nameRow.createUIElement(DIALOG_WIDTH - 24f, 28f, false);
        LabelAPI callLbl = nameUi.addPara("Callsign:", 0f, baseColor);
        DivisionUiWidgets.wrapLabelInPlace(callLbl, 60f, 2f, 5f);
        DivisionUiWidgets.attachRowLabelTooltip(nameUi, (com.fs.starfarer.api.ui.UIComponentAPI) callLbl, "Callsign:");
        dialog.activeNameField = nameUi.addTextField(230f, Fonts.DEFAULT_SMALL, 0f);
        dialog.activeNameField.setText(spec.name);
        dialog.activeNameField.setMaxChars(32);
        dialog.activeNameField.getPosition().inTL(66f, 2f);

        ButtonAPI bSaveName = dialog.registerButton(
                nameUi.addButton(
                        "Rename",
                        "APPLY_NAME",
                        baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        96f,
                        22f,
                        0f),
                "APPLY_NAME");
        bSaveName.getPosition().inTL(304f, 3f);

        LabelAPI postLbl = nameUi.addPara("Posture:", 0f, baseColor);
        DivisionUiWidgets.wrapLabelInPlace(postLbl, 58f, 416f, 5f);
        DivisionUiWidgets.attachRowLabelTooltip(nameUi, (com.fs.starfarer.api.ui.UIComponentAPI) postLbl, "Posture:");
        DoctrineOption[] doctrines = DoctrineOption.values();
        float dx = 478f;
        for (DoctrineOption doc : doctrines) {
            boolean selected = (spec.doctrine == doc);
            Color fg = selected ? positive : baseColor;
            Color bg = selected ? baseColor.darker() : darkColor;
            String docAct = "SET_DOC:" + doc.name();
            ButtonAPI bDoc = dialog.registerButton(
                    nameUi.addButton(
                            doc.displayName,
                            docAct,
                            fg,
                            bg,
                            Alignment.MID,
                            CutStyle.TL_BR,
                            132f,
                            22f,
                            0f),
                    docAct);
            if (selected) bDoc.highlight();
            bDoc.getPosition().inTL(dx, 3f);
            dx += 136f;
        }
        nameUi.setHeightSoFar(28f);
        nameRow.addUIElement(nameUi).inTL(0f, 0f);
        top.addCustom(nameRow, 4f);

        top.addPara(
                "%s - %s",
                3f,
                positive,
                spec.doctrine.displayName,
                spec.doctrine.summary);

        top.setHeightSoFar(headerH);
        container.addUIElement(top).inTL(12f, 8f);
    }

    private static void buildDivisionLeftSettingsAndShipsColumn(
            final DivisionEditorDialog dialog,
            CustomPanelAPI container,
            PermanentDivisionSpec spec,
            CampaignFleetAPI fleet,
            List<FleetMemberAPI> divMembers,
            float bodyTop,
            float bodyHeight,
            float colWidth,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive,
            Color gray) {
        // Left Column: Division Settings (Inherited / Auto-Updated / Manually Set) + Assigned Ships
        TooltipMakerAPI leftCol = container.createUIElement(colWidth, bodyHeight, true);

        FleetDoctrineConfig doctrineCfg = PermanentDivisionManager.getFleetDoctrine();
        int effIntra = PermanentDivisionManager.resolveEffectiveStationSpacing(spec, doctrineCfg);
        int effInter = PermanentDivisionManager.resolveEffectiveDivisionInterval(spec, doctrineCfg);
        int effStandoff = PermanentDivisionManager.resolveEffectiveStandoffRange(spec, doctrineCfg);
        int longestGunRange = PermanentDivisionManager.computeDivisionLongestGunRange(spec, fleet);
        LineStation effPrefSpace = PermanentDivisionManager.resolveEffectiveLineStation(spec, doctrineCfg);
        DivisionFormation effFormation = PermanentDivisionManager.resolveEffectiveFormation(spec, doctrineCfg);
        DivisionMovementStickiness effStickiness =
                PermanentDivisionManager.resolveEffectiveMovementStickiness(spec, doctrineCfg);
        DivisionMissionType effOpenMis = PermanentDivisionManager.resolveEffectiveOpeningMission(spec, doctrineCfg);
        DivisionMissionType effMainMis = PermanentDivisionManager.resolveEffectiveMainMission(spec, doctrineCfg);

        leftCol.addSectionHeading(
                "Formation & Missions",
                highlight,
                darkColor,
                Alignment.LMID,
                2f);

        // 0. Division Type Drop-Down Menu (UI Item G)
        addDivisionArchetypeDropdownRow(
                dialog,
                leftCol,
                colWidth - 16f,
                spec.archetype,
                baseColor,
                darkColor,
                highlight,
                positive,
                gray);

        // 1. Intra-division spacing row
        addDivisionSpacingRow(
                dialog,
                leftCol,
                colWidth - 16f,
                String.format(Locale.ROOT, "Ship Spacing: %du", effIntra),
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
                dialog,
                leftCol,
                colWidth - 16f,
                String.format(Locale.ROOT, "Division Interval: %du", effInter),
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
                dialog,
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
                dialog,
                leftCol,
                colWidth - 16f,
                effPrefSpace,
                spec.customLineStation != null,
                baseColor,
                darkColor,
                positive);

        // 4b. Naval Formation row (10 formations with icons + Inherit)
        addDivisionFormationRow(
                dialog,
                leftCol,
                colWidth - 16f,
                effFormation,
                spec.customFormation != null,
                baseColor,
                darkColor,
                highlight,
                positive);

        // 4c. Doctrinal Division Movement Stickiness row (Strict / Balanced / Loose / Free + Inherit)
        addDivisionMovementStickinessRow(
                dialog,
                leftCol,
                colWidth - 16f,
                effStickiness,
                effStickiness,
                spec.customMovementStickiness != null,
                baseColor,
                darkColor,
                positive);

        if (spec.archetype != null && !spec.archetype.isCarrierArchetype()
                && spec.archetype != DivisionArchetype.SERVICE_GROUP) {
            DivisionSetpieceAttack effSetpiece =
                    PermanentDivisionManager.resolveEffectiveSetpieceAttack(spec, doctrineCfg);
            addDivisionSetpieceAttackRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    effSetpiece,
                    spec.customSetpieceAttack != null,
                    baseColor,
                    darkColor,
                    highlight);
        }

        ShipSkillCoordinationMode effSkillCoord =
                PermanentDivisionManager.resolveEffectiveShipSkillCoordination(spec, doctrineCfg);
        addDivisionShipSkillCoordinationRow(
                dialog,
                leftCol,
                colWidth - 16f,
                effSkillCoord,
                spec.customShipSkillCoordination != null,
                baseColor,
                darkColor,
                highlight,
                positive);

        DivisionRammingMode effRamMode =
                PermanentDivisionManager.resolveEffectiveRammingMode(spec, doctrineCfg);
        DivisionRammingTargetScope effRamScope =
                PermanentDivisionManager.resolveEffectiveRammingTargetScope(spec, doctrineCfg);
        int effRamSlots =
                PermanentDivisionManager.resolveEffectiveMaxRammingSlots(spec, doctrineCfg);
        addDivisionRammingOptionsToggleRow(
                dialog,
                leftCol,
                colWidth - 16f,
                dialog.rammingOptionsExpanded,
                effRamMode,
                effRamScope,
                effRamSlots,
                baseColor,
                darkColor,
                highlight,
                positive,
                gray);
        if (dialog.rammingOptionsExpanded) {
            addDivisionRammingModeRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    effRamMode,
                    spec.customRammingMode != null,
                    baseColor,
                    darkColor,
                    highlight,
                    positive);
            addDivisionRammingTargetScopeRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    effRamScope,
                    spec.customRammingTargetScope != null,
                    baseColor,
                    darkColor,
                    highlight,
                    positive);
            addDivisionVoidstrikeDepthRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    "Max Ram Slots: " + effRamSlots,
                    effRamSlots,
                    spec.customMaxRammingSlots != null,
                    "DIV_RAM_SLOTS:",
                    baseColor,
                    darkColor,
                    highlight,
                    positive);
        }

        // 5. Opening Mission Drop-Down (0 - openingDurationSeconds)
        addDivisionMissionDropdownRow(
                dialog,
                leftCol,
                colWidth - 16f,
                "Opening (0-" + doctrineCfg.getOpeningDurationSeconds() + "s):",
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
                dialog,
                leftCol,
                colWidth - 16f,
                "Main Mission:",
                "DIV_MAIN_DD",
                effMainMis,
                spec.archetype,
                spec.customMainMission != null,
                "DIV_MAIN_MIS:",
                baseColor,
                darkColor,
                highlight,
                gray);

        // 7. Strike Craft & Wing Voidstrike Behavior Settings (collapsed under "Strike Craft Options")
        StrikeCraftMissionType effScOpenMis =
                PermanentDivisionManager.resolveEffectiveStrikeCraftOpeningMission(spec, doctrineCfg);
        StrikeCraftMissionType effScMainMis =
                PermanentDivisionManager.resolveEffectiveStrikeCraftMainMission(spec, doctrineCfg);
        VoidstrikeTargetingBias effVsBias =
                PermanentDivisionManager.resolveEffectiveVoidstrikeTargetingBias(spec, doctrineCfg);
        boolean effVsFocusCv =
                PermanentDivisionManager.resolveEffectiveVoidstrikeFocusCarriers(spec, doctrineCfg);
        VoidstrikeTargetObservance effVsObs =
                PermanentDivisionManager.resolveEffectiveVoidstrikeObservance(spec, doctrineCfg);
        VoidstrikeCoordinationMode effVsCoord =
                PermanentDivisionManager.resolveEffectiveVoidstrikeCoordination(spec, doctrineCfg);
        VoidstrikeAttackGeometry effVsGeom =
                PermanentDivisionManager.resolveEffectiveVoidstrikeAttackGeometry(spec, doctrineCfg);
        VoidstrikeSplitMode effVsSplit =
                PermanentDivisionManager.resolveEffectiveVoidstrikeSplitMode(spec, doctrineCfg);
        VoidstrikeGunneryMode effVsGun =
                PermanentDivisionManager.resolveEffectiveVoidstrikeGunneryMode(spec, doctrineCfg);
        VoidstrikeAoaCorrection effVsAoa =
                PermanentDivisionManager.resolveEffectiveVoidstrikeAoaCorrection(spec, doctrineCfg);
        SquadronFormation effFtrFm =
                PermanentDivisionManager.resolveEffectiveFighterFormation(spec, doctrineCfg);
        SquadronFormation effFtrVsFm =
                PermanentDivisionManager.resolveEffectiveFighterVoidstrikeFormation(spec, doctrineCfg);
        SquadronFormation effBmbFm =
                PermanentDivisionManager.resolveEffectiveBomberFormation(spec, doctrineCfg);
        SquadronFormation effBmbVsFm =
                PermanentDivisionManager.resolveEffectiveBomberVoidstrikeFormation(spec, doctrineCfg);
        int effVsDepth =
                PermanentDivisionManager.resolveEffectiveVoidstrikeSquadronDepth(spec, doctrineCfg);
        int effVsWidth =
                PermanentDivisionManager.resolveEffectiveVoidstrikeSquadronWidth(spec, doctrineCfg);

        addDivisionCarrierOptionsToggleRow(
                dialog,
                leftCol,
                colWidth - 16f,
                dialog.carrierOptionsExpanded,
                effScOpenMis,
                effScMainMis,
                effVsObs,
                effVsCoord,
                effVsDepth,
                effVsWidth,
                baseColor,
                darkColor,
                highlight,
                positive,
                gray);

        if (dialog.carrierOptionsExpanded) {
            addDivisionStrikeCraftMissionDropdownRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    "Opening Strike Craft:",
                    "DIV_SC_OPEN_DD",
                    effScOpenMis,
                    spec.customStrikeCraftOpeningMission != null,
                    "DIV_SC_OPEN_MIS:",
                    baseColor,
                    darkColor,
                    positive,
                    gray);

            addDivisionStrikeCraftMissionDropdownRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    "Main Strike Craft:",
                    "DIV_SC_MAIN_DD",
                    effScMainMis,
                    spec.customStrikeCraftMainMission != null,
                    "DIV_SC_MAIN_MIS:",
                    baseColor,
                    darkColor,
                    highlight,
                    gray);

            addDivisionVoidstrikeTargetingBiasRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    effVsBias,
                    spec.customVoidstrikeTargetingBias != null,
                    baseColor,
                    darkColor,
                    highlight,
                    positive);

            addDivisionVoidstrikeFocusCarriersRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    effVsFocusCv,
                    spec.customVoidstrikeFocusCarriers != null,
                    baseColor,
                    darkColor,
                    highlight,
                    positive);

            addDivisionVoidstrikeObservanceRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    effVsObs,
                    spec.customVoidstrikeObservance != null,
                    baseColor,
                    darkColor,
                    positive);

            addDivisionVoidstrikeCoordinationRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    effVsCoord,
                    spec.customVoidstrikeCoordination != null,
                    baseColor,
                    darkColor,
                    highlight,
                    positive);

            addDivisionVoidstrikeAttackGeometryRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    effVsGeom,
                    spec.customVoidstrikeAttackGeometry != null,
                    baseColor,
                    darkColor,
                    highlight,
                    positive);

            addDivisionVoidstrikeSplitModeRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    effVsSplit,
                    spec.customVoidstrikeSplitMode != null,
                    baseColor,
                    darkColor,
                    highlight,
                    positive);

            addDivisionVoidstrikeGunneryModeRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    effVsGun,
                    spec.customVoidstrikeGunneryMode != null,
                    baseColor,
                    darkColor,
                    highlight,
                    positive);

            addDivisionVoidstrikeAoaCorrectionRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    effVsAoa,
                    spec.customVoidstrikeAoaCorrection != null,
                    baseColor,
                    darkColor,
                    highlight,
                    positive);

            addDivisionSquadronFormationRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    "Fighter Form.",
                    effFtrFm,
                    SquadronFormation.FIGHTER_GENERAL_OPTIONS,
                    spec.customFighterFormation != null,
                    "DIV_FTR_FM:",
                    baseColor,
                    darkColor,
                    highlight,
                    positive);

            addDivisionSquadronFormationRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    "Fighter Voidstrike",
                    effFtrVsFm,
                    SquadronFormation.FIGHTER_VOIDSTRIKE_OPTIONS,
                    spec.customFighterVoidstrikeFormation != null,
                    "DIV_FTR_VS_FM:",
                    baseColor,
                    darkColor,
                    highlight,
                    positive);

            addDivisionSquadronFormationRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    "Bomber Form.",
                    effBmbFm,
                    SquadronFormation.BOMBER_GENERAL_OPTIONS,
                    spec.customBomberFormation != null,
                    "DIV_BMB_FM:",
                    baseColor,
                    darkColor,
                    highlight,
                    positive);

            addDivisionSquadronFormationRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    "Bomber Voidstrike",
                    effBmbVsFm,
                    SquadronFormation.BOMBER_VOIDSTRIKE_OPTIONS,
                    spec.customBomberVoidstrikeFormation != null,
                    "DIV_BMB_VS_FM:",
                    baseColor,
                    darkColor,
                    highlight,
                    positive);

            addDivisionVoidstrikeDepthRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    "Voidstrike Depth: " + effVsDepth,
                    effVsDepth,
                    spec.customVoidstrikeSquadronDepth != null,
                    "DIV_VS_DEPTH:",
                    baseColor,
                    darkColor,
                    highlight,
                    positive);

            addDivisionVoidstrikeDepthRow(
                    dialog,
                    leftCol,
                    colWidth - 16f,
                    "Voidstrike Width: " + effVsWidth,
                    effVsWidth,
                    spec.customVoidstrikeSquadronWidth != null,
                    "DIV_VS_WIDTH:",
                    baseColor,
                    darkColor,
                    highlight,
                    positive);
        }

        // Visual Ship Icon Bay in Deep Division Editor
        leftCol.addSectionHeading(
                "Assigned Ships (" + divMembers.size() + "/" + PermanentDivisionManager.MAX_DIVISION_SIZE + ")",
                baseColor,
                darkColor,
                Alignment.MID,
                8f);
        leftCol.addPara(
                "Left-Click: Division Leader  |  Right-Click / Drag: Unassign",
                gray,
                3f);

        if (divMembers.isEmpty()) {
            leftCol.addPara(
                    "Empty division - drag or right-click ships from the right panel.",
                    gray,
                    8f);
        } else {
            String flagId = spec.flagshipMemberId;
            if (flagId == null && !divMembers.isEmpty()) {
                flagId = divMembers.get(0).getId();
            }
            dialog.addInteractiveShipGrid(
                    leftCol,
                    colWidth - 16f,
                    divMembers,
                    spec.id,
                    flagId,
                    6,
                    false);
        }

        container.addUIElement(leftCol).inTL(12f, bodyTop);
        dialog.singleLeftScroller = leftCol.getExternalScroller();
        dialog.restoreScrollOffset(leftCol, dialog.singleLeftScroller, bodyHeight, dialog.savedSingleLeftScrollY);
        dialog.registerDropTargetZone(spec.id, spec.name, leftCol, null);
    }

    private static void buildDivisionRightPoolColumn(
            final DivisionEditorDialog dialog,
            CustomPanelAPI container,
            PermanentDivisionSpec spec,
            CampaignFleetAPI fleet,
            List<PermanentDivisionSpec> allDivs,
            List<FleetMemberAPI> unassigned,
            float bodyTop,
            float bodyHeight,
            float colWidth,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color gray) {
        // Right Column: Visual Ship Icons for Unassigned Ships + Ships in Other Divisions
        TooltipMakerAPI rightCol = container.createUIElement(colWidth, bodyHeight, true);
        rightCol.addSectionHeading(
                "Unassigned Ships (" + unassigned.size() + ")",
                baseColor,
                darkColor,
                Alignment.MID,
                4f);

        if (unassigned.isEmpty()) {
            rightCol.addPara("All combat ships assigned. Drag ships here to unassign.", gray, 4f);
        } else {
            dialog.addInteractiveShipGrid(
                    rightCol,
                    colWidth - 16f,
                    unassigned,
                    null,
                    null,
                    6,
                    false);
        }

        rightCol.addSectionHeading("Other Divisions", baseColor, darkColor, Alignment.MID, 10f);
        boolean anyOther = false;
        for (PermanentDivisionSpec other : allDivs) {
            if (other.id.equals(spec.id)) continue;
            List<FleetMemberAPI> otherMembers = PermanentDivisionManager.getMembersInDivision(other, fleet);
            if (otherMembers.isEmpty()) continue;
            anyOther = true;
            rightCol.addPara(
                    other.name + " | " + other.archetype.shortPrefix + " (" + otherMembers.size() + ")",
                    highlight,
                    5f);
            dialog.addInteractiveShipGrid(
                    rightCol,
                    colWidth - 16f,
                    otherMembers,
                    other.id,
                    other.flagshipMemberId,
                    6,
                    false);
        }
        if (!anyOther) {
            rightCol.addPara("No ships in other divisions.", gray, 4f);
        }

        container.addUIElement(rightCol).inTL(colWidth + 24f, bodyTop);
        dialog.singleRightScroller = rightCol.getExternalScroller();
        dialog.restoreScrollOffset(rightCol, dialog.singleRightScroller, bodyHeight, dialog.savedSingleRightScrollY);
        dialog.registerDropTargetZone(null, "Unassigned Fleet Pool", rightCol, null);
    }

    private static void addDivisionSpacingRow(
            final DivisionEditorDialog dialog,
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

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 26f, dialog.subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 26f, false);

        LabelAPI unitLbl = rowUi.addPara(labelText, 0f, isCustom ? activeColor : baseColor, labelText);
        float unitLblBot = DivisionUiWidgets.wrapLabelInPlace(unitLbl, CONTROL_COL_X - 12f, 4f, 4f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (com.fs.starfarer.api.ui.UIComponentAPI) unitLbl, labelText);

        float x = CONTROL_COL_X;
        String decAct = actionPrefix + Math.max(minVal, currentVal - step);
        ButtonAPI bDec = dialog.registerButton(
                rowUi.addButton("-" + step + "u", decAct, baseColor, darkColor, Alignment.MID, CutStyle.ALL, 56f, 20f, 0f),
                decAct);
        bDec.setEnabled(currentVal > minVal);
        bDec.getPosition().inTL(x, 3f);

        String incAct = actionPrefix + Math.min(maxVal, currentVal + step);
        ButtonAPI bInc = dialog.registerButton(
                rowUi.addButton("+" + step + "u", incAct, baseColor, darkColor, Alignment.MID, CutStyle.ALL, 56f, 20f, 0f),
                incAct);
        bInc.setEnabled(currentVal < maxVal);
        bInc.getPosition().inTL(x + 62f, 3f);

        String inhAct = actionPrefix + "INHERIT";
        ButtonAPI bInh = dialog.registerButton(
                rowUi.addButton("Inherit", inhAct, isCustom ? baseColor : activeColor, darkColor, Alignment.MID, CutStyle.ALL, 86f, 20f, 0f),
                inhAct);
        if (!isCustom) bInh.highlight();
        bInh.getPosition().inTL(x + 124f, 3f);

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(26f, unitLblBot + 4f));
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        col.addCustom(rowPanel, 2f);
    }

    private static void addDivisionStandoffRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            int effStandoff,
            int longestGunRange,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 26f, dialog.subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 26f, false);

        String label = longestGunRange > 0
                ? String.format(Locale.ROOT, "Standoff: %du (Guns %du)", effStandoff, longestGunRange)
                : String.format(Locale.ROOT, "Standoff: %du", effStandoff);
        LabelAPI stLbl = rowUi.addPara(label, 0f, isCustom ? highlight : baseColor, label);
        float stLblBot = DivisionUiWidgets.wrapLabelInPlace(stLbl, CONTROL_COL_X - 12f, 4f, 4f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (com.fs.starfarer.api.ui.UIComponentAPI) stLbl, "Standoff Range:");

        float x = CONTROL_COL_X;
        String decAct = "DIV_STANDOFF_RANGE:" + Math.max(PermanentDivisionManager.MIN_STANDOFF_RANGE_UNITS, effStandoff - 100);
        ButtonAPI bDec = dialog.registerButton(
                rowUi.addButton("-100u", decAct, baseColor, darkColor, Alignment.MID, CutStyle.ALL, 56f, 20f, 0f),
                decAct);
        bDec.getPosition().inTL(x, 3f);

        String incAct = "DIV_STANDOFF_RANGE:" + Math.min(PermanentDivisionManager.MAX_STANDOFF_RANGE_UNITS, effStandoff + 100);
        ButtonAPI bInc = dialog.registerButton(
                rowUi.addButton("+100u", incAct, baseColor, darkColor, Alignment.MID, CutStyle.ALL, 56f, 20f, 0f),
                incAct);
        bInc.getPosition().inTL(x + 62f, 3f);

        String autoLabel = longestGunRange > 0
                ? ("Match (" + longestGunRange + "u)")
                : "Match Guns";
        ButtonAPI bAuto = dialog.registerButton(
                rowUi.addButton(autoLabel, "DIV_STANDOFF_RANGE:AUTO", positive, darkColor, Alignment.MID, CutStyle.TL_BR, 148f, 20f, 0f),
                "DIV_STANDOFF_RANGE:AUTO");
        bAuto.setEnabled(longestGunRange > 0);
        bAuto.getPosition().inTL(x + 124f, 3f);

        ButtonAPI bInh = dialog.registerButton(
                rowUi.addButton("Inherit", "DIV_STANDOFF_RANGE:INHERIT", !isCustom ? positive : baseColor, darkColor, Alignment.MID, CutStyle.ALL, 86f, 20f, 0f),
                "DIV_STANDOFF_RANGE:INHERIT");
        if (!isCustom) bInh.highlight();
        bInh.getPosition().inTL(x + 278f, 3f);

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(26f, stLblBot + 4f));
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        col.addCustom(rowPanel, 2f);
    }

    private static void addDivisionFormationRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            final DivisionFormation effFormation,
            final boolean isCustom,
            final Color baseColor,
            Color darkColor,
            final Color highlight,
            final Color activeColor) {

        final DivisionFormation[] formations = DivisionFormation.values();
        final float btnW = 98f;
        final float btnGap = 4f;
        final int cols = 6;
        final float rowH = 76f;
        final ButtonAPI[] formButtons = new ButtonAPI[formations.length];

        final CustomPanelAPI[] rowPanelRef = new CustomPanelAPI[1];
        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, rowH, new BaseCustomUIPanelPlugin() {
            @Override
            public void buttonPressed(Object buttonId) {
                dialog.buttonPressed(buttonId);
            }

            @Override
            public void render(float alphaMult) {
                CustomPanelAPI self = rowPanelRef[0];
                if (!DivisionUiWidgets.isPanelVisibleOnScreen(self)) return;
                PositionAPI rp = self.getPosition();
                boolean flagshipDivSelected = dialog.isSelectedDivisionFlagship();
                // Render active formation preview icon in the header bar at CONTROL_COL_X
                FleetDoctrineDialog.renderFormationIcon(
                        effFormation,
                        rp.getX() + CONTROL_COL_X + 9f,
                        rp.getY() + rp.getHeight() - 12f,
                        16f,
                        isCustom ? highlight : activeColor,
                        alphaMult,
                        flagshipDivSelected);
                // Render each button's formation icon directly from the button's live PositionAPI
                for (int i = 0; i < formations.length; i++) {
                    DivisionFormation f = formations[i];
                    ButtonAPI btn = formButtons[i];
                    if (btn == null || btn.getPosition() == null) continue;
                    PositionAPI bp = btn.getPosition();
                    boolean selected = (f == effFormation);
                    FleetDoctrineDialog.renderFormationIcon(
                            f,
                            bp.getX() + 12f,
                            bp.getY() + bp.getHeight() * 0.5f,
                            15f,
                            selected ? (isCustom ? highlight : activeColor) : baseColor,
                            alphaMult,
                            flagshipDivSelected);
                }
            }
        });
        rowPanelRef[0] = rowPanel;

        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, rowH, false);
        String labelText = "Formation: " + effFormation.shortName;
        LabelAPI fmLbl = rowUi.addPara(labelText, 0f, isCustom ? highlight : baseColor, labelText);
        DivisionUiWidgets.wrapLabelInPlace(fmLbl, CONTROL_COL_X - 10f, 4f, 4f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (com.fs.starfarer.api.ui.UIComponentAPI) fmLbl, labelText);

        String inhAct = "DIV_FORMATION:INHERIT";
        ButtonAPI bInh = dialog.registerButton(
                rowUi.addButton(
                        "Inherit",
                        inhAct,
                        !isCustom ? activeColor : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        86f,
                        20f,
                        0f),
                inhAct);
        if (!isCustom) bInh.highlight();
        bInh.getPosition().inTL(rowWidth - 90f, 2f);

        for (int i = 0; i < formations.length; i++) {
            DivisionFormation f = formations[i];
            boolean selected = (f == effFormation);
            int r = i / cols;
            int c = i % cols;
            float bx = 4f + c * (btnW + btnGap);
            float by = 26f + r * 24f;
            String act = "DIV_FORMATION:" + f.name();
            ButtonAPI bForm = dialog.registerButton(
                    rowUi.addButton(
                            "    " + f.shortName,
                            act,
                            selected ? (isCustom ? highlight : activeColor) : baseColor,
                            darkColor,
                            Alignment.MID,
                            CutStyle.ALL,
                            btnW,
                            21f,
                            0f),
                    act);
            if (selected) bForm.highlight();
            bForm.getPosition().inTL(bx, by);
            formButtons[i] = bForm;
        }

        rowUi.setHeightSoFar(rowH);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        col.addCustom(rowPanel, 2f);
    }

    private static void addDivisionPreferredSpaceRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            LineStation effSpace,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color activeColor) {
        LineStation[] opts = LineStation.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = "DIV_LINE_STATION:" + opts[i].name();
            if (opts[i] == effSpace) sel = i;
        }
        String labelText = "Line Station: " + effSpace.displayName;
        DivisionUiWidgets.addOptionStripRow(
                col, rowWidth, 26f, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, labelText, isCustom ? activeColor : baseColor, 4f, 4f, CONTROL_COL_X,
                labels, acts, sel, 68f, 20f, 74f, baseColor, darkColor, activeColor,
                "DIV_LINE_STATION:INHERIT", isCustom, 86f, activeColor, null, null, 4f);
    }

    private static void addDivisionMovementStickinessRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            DivisionMovementStickiness effStickiness,
            DivisionMovementStickiness defStickiness,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color activeColor) {
        DivisionMovementStickiness[] opts = DivisionMovementStickiness.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = "DIV_STICKINESS:" + opts[i].name();
            if (opts[i] == effStickiness) sel = i;
        }
        String labelText = "Cohesion: " + effStickiness.displayName;
        DivisionUiWidgets.addOptionStripRow(
                col, rowWidth, 26f, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, labelText, isCustom ? activeColor : baseColor, 4f, 4f, CONTROL_COL_X,
                labels, acts, sel, 62f, 20f, 66f, baseColor, darkColor, activeColor,
                "DIV_STICKINESS:INHERIT", isCustom, 72f, activeColor, null, null, 4f);
    }

    private static void addDivisionSetpieceAttackRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            DivisionSetpieceAttack effSetpiece,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color activeColor) {
        DivisionSetpieceAttack[] opts = DivisionSetpieceAttack.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].shortName;
            acts[i] = "DIV_SETPIECE:" + opts[i].name();
            if (opts[i] == effSetpiece) sel = i;
        }
        String labelText = "Setpiece: " + effSetpiece.shortName;
        DivisionUiWidgets.addOptionStripRow(
                col, rowWidth, 26f, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, labelText, isCustom ? activeColor : baseColor, 4f, 4f, CONTROL_COL_X,
                labels, acts, sel, 46f, 20f, 49f, baseColor, darkColor, activeColor,
                "DIV_SETPIECE:INHERIT", isCustom, 56f, activeColor, null, null, 4f);
    }

    private static void addDivisionShipSkillCoordinationRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            ShipSkillCoordinationMode effCoord,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color activeColor) {
        ShipSkillCoordinationMode[] opts = ShipSkillCoordinationMode.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = "DIV_SKILL_COORD:" + opts[i].name();
            if (opts[i] == effCoord) sel = i;
        }
        String labelText = "Ship Skills: " + effCoord.displayName;
        DivisionUiWidgets.addOptionStripRow(
                col, rowWidth, 26f, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, labelText, isCustom ? highlight : baseColor, 4f, 4f, CONTROL_COL_X,
                labels, acts, sel, 110f, 20f, 114f, baseColor, darkColor, isCustom ? highlight : activeColor,
                "DIV_SKILL_COORD:INHERIT", isCustom, 72f, activeColor, null, null, 4f);
    }

    private static void addDivisionRammingOptionsToggleRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            boolean expanded,
            DivisionRammingMode effRamMode,
            DivisionRammingTargetScope effRamScope,
            int effRamSlots,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive,
            Color gray) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 26f, dialog.subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 26f, false);

        LabelAPI ramLbl = rowUi.addPara("Ramming Options:", 0f, baseColor);
        float ramLblBot = DivisionUiWidgets.wrapLabelInPlace(ramLbl, CONTROL_COL_X - 12f, 4f, 4f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (com.fs.starfarer.api.ui.UIComponentAPI) ramLbl, "Ramming Options:");

        String toggleAct = "TOGGLE_DIV_RAMMING_OPTIONS";
        String btnText = (expanded ? "[^] " : "[v] ") + "Ramming Options";
        ButtonAPI bToggle = dialog.registerButton(
                rowUi.addButton(
                        btnText,
                        toggleAct,
                        expanded ? highlight : (effRamMode != DivisionRammingMode.OFF ? positive : baseColor),
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        204f,
                        21f,
                        0f),
                toggleAct);
        if (expanded || effRamMode != DivisionRammingMode.OFF) {
            bToggle.highlight();
        }
        bToggle.getPosition().inTL(CONTROL_COL_X, 2f);

        String summary = (effRamMode == DivisionRammingMode.OFF)
                ? "Off"
                : String.format(
                        Locale.ROOT,
                        "%s / %s (Max %d)",
                        effRamMode.displayName,
                        effRamScope.displayName,
                        effRamSlots);
        LabelAPI ramSumLbl = rowUi.addPara(summary, 0f, effRamMode != DivisionRammingMode.OFF ? positive : gray);
        float sumX = CONTROL_COL_X + 212f;
        float availRight = rowWidth - sumX - 6f;
        float ramSumBot = (availRight >= 80f)
                ? DivisionUiWidgets.wrapLabelInPlace(ramSumLbl, availRight, sumX, 4f)
                : DivisionUiWidgets.wrapLabelInPlace(ramSumLbl, rowWidth - CONTROL_COL_X - 8f, CONTROL_COL_X, 25f);

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(26f, Math.max(ramLblBot, ramSumBot) + 4f));
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        col.addCustom(rowPanel, 2f);
    }

    private static void addDivisionRammingModeRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            DivisionRammingMode effMode,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color activeColor) {
        DivisionRammingMode[] opts = DivisionRammingMode.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].shortName;
            acts[i] = "DIV_RAM_MODE:" + opts[i].name();
            if (opts[i] == effMode) sel = i;
        }
        String labelText = "Ramming: " + effMode.displayName;
        DivisionUiWidgets.addOptionStripRow(
                col, rowWidth, 26f, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, labelText, isCustom ? highlight : baseColor, 4f, 4f, CONTROL_COL_X,
                labels, acts, sel, 72f, 20f, 76f, baseColor, darkColor, isCustom ? highlight : activeColor,
                "DIV_RAM_MODE:INHERIT", isCustom, 72f, activeColor, null, null, 4f);
    }

    private static void addDivisionRammingTargetScopeRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            DivisionRammingTargetScope effScope,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color activeColor) {
        DivisionRammingTargetScope[] opts = DivisionRammingTargetScope.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = "DIV_RAM_SCOPE:" + opts[i].name();
            if (opts[i] == effScope) sel = i;
        }
        String labelText = "Ram Target: " + effScope.displayName;
        DivisionUiWidgets.addOptionStripRow(
                col, rowWidth, 26f, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, labelText, isCustom ? highlight : baseColor, 4f, 4f, CONTROL_COL_X,
                labels, acts, sel, 110f, 20f, 114f, baseColor, darkColor, isCustom ? highlight : activeColor,
                "DIV_RAM_SCOPE:INHERIT", isCustom, 72f, activeColor, null, null, 4f);
    }

    private static void addDivisionVoidstrikeTargetingBiasRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            VoidstrikeTargetingBias effBias,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color activeColor) {
        VoidstrikeTargetingBias[] opts = VoidstrikeTargetingBias.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].shortName;
            acts[i] = "DIV_VS_BIAS:" + opts[i].name();
            if (opts[i] == effBias) sel = i;
        }
        String labelText = "Target Bias: " + effBias.displayName;
        DivisionUiWidgets.addOptionStripRow(
                col, rowWidth, 26f, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, labelText, isCustom ? highlight : baseColor, 4f, 4f, CONTROL_COL_X,
                labels, acts, sel, 94f, 20f, 98f, baseColor, darkColor, isCustom ? highlight : activeColor,
                "DIV_VS_BIAS:INHERIT", isCustom, 72f, activeColor, null, null, 4f);
    }

    private static void addDivisionVoidstrikeFocusCarriersRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            boolean effFocusCarriers,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color activeColor) {
        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 26f, dialog.subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 26f, false);
        String labelText = "Carrier Priority: " + (effFocusCarriers ? "Focus On" : "Standard");
        LabelAPI rowLbl = rowUi.addPara(labelText, 0f, isCustom ? highlight : baseColor, labelText);
        float rowLblBot = DivisionUiWidgets.wrapLabelInPlace(rowLbl, CONTROL_COL_X - 12f, 4f, 4f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (com.fs.starfarer.api.ui.UIComponentAPI) rowLbl, labelText);

        String toggleAct = "DIV_VS_CV_FOCUS:TOGGLE";
        ButtonAPI cb = dialog.registerCheckbox(
                rowUi.addCheckbox(
                        178f,
                        20f,
                        "Focus on Carriers",
                        (Object) toggleAct,
                        Fonts.DEFAULT_SMALL,
                        effFocusCarriers ? (isCustom ? highlight : activeColor) : baseColor,
                        ButtonAPI.UICheckboxSize.SMALL,
                        0f),
                toggleAct,
                effFocusCarriers);
        cb.getPosition().inTL(CONTROL_COL_X, 3f);

        String inhAct = "DIV_VS_CV_FOCUS:INHERIT";
        ButtonAPI bInh = dialog.registerButton(
                rowUi.addButton(
                        "Inherit",
                        inhAct,
                        !isCustom ? activeColor : baseColor,
                        darkColor,
                        Alignment.MID,
                        CutStyle.ALL,
                        72f,
                        20f,
                        0f),
                inhAct);
        if (!isCustom) {
            bInh.highlight();
        }
        bInh.getPosition().inTL(CONTROL_COL_X + 186f, 3f);

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(26f, rowLblBot + 4f));
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        col.addCustom(rowPanel, 2f);
    }

    private static void addDivisionVoidstrikeObservanceRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            VoidstrikeTargetObservance effObs,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color activeColor) {
        VoidstrikeTargetObservance[] opts = VoidstrikeTargetObservance.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = "DIV_VS_OBS:" + opts[i].name();
            if (opts[i] == effObs) sel = i;
        }
        String labelText = "Voidstrike Focus: " + effObs.displayName;
        DivisionUiWidgets.addOptionStripRow(
                col, rowWidth, 26f, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, labelText, isCustom ? activeColor : baseColor, 4f, 4f, CONTROL_COL_X,
                labels, acts, sel, 72f, 20f, 76f, baseColor, darkColor, activeColor,
                "DIV_VS_OBS:INHERIT", isCustom, 72f, activeColor, null, null, 4f);
    }

    private static void addDivisionVoidstrikeCoordinationRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            VoidstrikeCoordinationMode effCoord,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color activeColor) {
        VoidstrikeCoordinationMode[] opts = VoidstrikeCoordinationMode.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = "DIV_VS_COORD:" + opts[i].name();
            if (opts[i] == effCoord) sel = i;
        }
        String labelText = "Voidstrike Launch: " + effCoord.displayName;
        DivisionUiWidgets.addOptionStripRow(
                col, rowWidth, 26f, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, labelText, isCustom ? highlight : baseColor, 4f, 4f, CONTROL_COL_X,
                labels, acts, sel, 110f, 20f, 114f, baseColor, darkColor, isCustom ? highlight : activeColor,
                "DIV_VS_COORD:INHERIT", isCustom, 72f, activeColor, null, null, 4f);
    }

    private static void addDivisionVoidstrikeAttackGeometryRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            VoidstrikeAttackGeometry effGeom,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color activeColor) {
        VoidstrikeAttackGeometry[] opts = VoidstrikeAttackGeometry.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = "DIV_VS_GEOM:" + opts[i].name();
            if (opts[i] == effGeom) sel = i;
        }
        String labelText = "Strike Geometry: " + effGeom.displayName;
        DivisionUiWidgets.addOptionStripRow(
                col, rowWidth, 26f, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, labelText, isCustom ? highlight : baseColor, 4f, 4f, CONTROL_COL_X,
                labels, acts, sel, 82f, 20f, 86f, baseColor, darkColor, isCustom ? highlight : activeColor,
                "DIV_VS_GEOM:INHERIT", isCustom, 72f, activeColor, null, null, 4f);
    }

    private static void addDivisionVoidstrikeSplitModeRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            VoidstrikeSplitMode effSplit,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color activeColor) {
        VoidstrikeSplitMode[] opts = VoidstrikeSplitMode.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = "DIV_VS_SPLIT:" + opts[i].name();
            if (opts[i] == effSplit) sel = i;
        }
        String labelText = "Wing Split: " + effSplit.displayName;
        DivisionUiWidgets.addOptionStripRow(
                col, rowWidth, 26f, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, labelText, isCustom ? highlight : baseColor, 4f, 4f, CONTROL_COL_X,
                labels, acts, sel, 82f, 20f, 86f, baseColor, darkColor, isCustom ? highlight : activeColor,
                "DIV_VS_SPLIT:INHERIT", isCustom, 72f, activeColor, null, null, 4f);
    }

    private static void addDivisionVoidstrikeGunneryModeRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            VoidstrikeGunneryMode effGun,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color activeColor) {
        VoidstrikeGunneryMode[] opts = VoidstrikeGunneryMode.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = "DIV_VS_GUN:" + opts[i].name();
            if (opts[i] == effGun) sel = i;
        }
        String labelText = "Strike Gunnery: " + effGun.displayName;
        DivisionUiWidgets.addOptionStripRow(
                col, rowWidth, 26f, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, labelText, isCustom ? highlight : baseColor, 4f, 4f, CONTROL_COL_X,
                labels, acts, sel, 96f, 20f, 100f, baseColor, darkColor, isCustom ? highlight : activeColor,
                "DIV_VS_GUN:INHERIT", isCustom, 72f, activeColor, null, null, 4f);
    }

    private static void addDivisionVoidstrikeAoaCorrectionRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            VoidstrikeAoaCorrection effAoa,
            boolean isCustom,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color activeColor) {
        VoidstrikeAoaCorrection[] opts = VoidstrikeAoaCorrection.values();
        String[] labels = new String[opts.length];
        String[] acts = new String[opts.length];
        int sel = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i].displayName;
            acts[i] = "DIV_VS_AOA:" + opts[i].name();
            if (opts[i] == effAoa) sel = i;
        }
        String labelText = "AoA Correction: " + effAoa.displayName;
        DivisionUiWidgets.addOptionStripRow(
                col, rowWidth, 26f, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, labelText, isCustom ? highlight : baseColor, 4f, 4f, CONTROL_COL_X,
                labels, acts, sel, 86f, 20f, 90f, baseColor, darkColor, isCustom ? highlight : activeColor,
                "DIV_VS_AOA:INHERIT", isCustom, 72f, activeColor, null, null, 4f);
    }

    private static void addDivisionVoidstrikeDepthRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            String labelText,
            int effVal,
            boolean isCustom,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color activeColor) {
        DivisionUiWidgets.addVoidstrikeDepthRow(
                col, rowWidth, 26f, CONTROL_COL_X, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, isCustom ? highlight : baseColor, 4f, 4f, effVal, actionPrefix,
                false, true, isCustom, 27f, 20f, 30f, baseColor, darkColor,
                isCustom ? highlight : activeColor, activeColor);
    }

    private static void addDivisionSquadronFormationRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            String shortTitle,
            SquadronFormation effFormation,
            SquadronFormation[] options,
            boolean isCustom,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color activeColor) {
        DivisionUiWidgets.addSquadronFormationRow(
                col, rowWidth, CONTROL_COL_X, dialog.buttonRegistrar, dialog.buttonPressRelay,
                shortTitle + ": " + effFormation.shortName, effFormation, options, actionPrefix,
                true, isCustom, dialog.isSelectedDivisionFlagship(),
                baseColor, darkColor, highlight, activeColor);
    }

    private static final DivisionArchetype[] CONFIGURABLE_ARCHETYPES = new DivisionArchetype[]{
            DivisionArchetype.BATTLESHIP_DIVISION,
            DivisionArchetype.MISSILE_BATTLESHIP_DIVISION,
            DivisionArchetype.CRUISER_DIVISION,
            DivisionArchetype.MISSILE_CRUISER_DIVISION,
            DivisionArchetype.STRIKE_CRUISER_DIVISION,
            DivisionArchetype.DESTROYER_DIVISION,
            DivisionArchetype.MISSILE_DESTROYER_DIVISION,
            DivisionArchetype.FRIGATE_DIVISION,
            DivisionArchetype.MISSILE_FRIGATE_DIVISION,
            DivisionArchetype.FLEET_CARRIER_DIVISION,
            DivisionArchetype.LIGHT_CARRIER_DIVISION,
            DivisionArchetype.ESCORT_CARRIER_DIVISION,
            DivisionArchetype.CARRIER_GROUP,
            DivisionArchetype.PHASE_DIVISION,
            DivisionArchetype.SERVICE_GROUP,
            DivisionArchetype.FORCE_DIVISION
    };

    private static void addDivisionArchetypeDropdownRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            DivisionArchetype currentArchetype,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive,
            Color gray) {

        String dropdownKey = "DIV_ARCH_DD";
        boolean expanded = dropdownKey.equals(dialog.expandedDropdownKey);
        DivisionArchetype curr = currentArchetype != null ? currentArchetype : DivisionArchetype.FORCE_DIVISION;

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 26f, dialog.subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 26f, false);
        LabelAPI archLbl = rowUi.addPara("Division Type:", 0f, baseColor);
        DivisionUiWidgets.wrapLabelInPlace(archLbl, CONTROL_COL_X - 12f, 4f, 4f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (com.fs.starfarer.api.ui.UIComponentAPI) archLbl, "Division Type:");

        String toggleAct = "TOGGLE_DD:" + dropdownKey;
        String btnText = (expanded ? "[^] " : "[v] ") + curr.shortPrefix;
        ButtonAPI bToggle = dialog.registerButton(
                rowUi.addButton(
                        btnText,
                        toggleAct,
                        highlight,
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        286f,
                        21f,
                        0f),
                toggleAct);
        if (expanded) {
            bToggle.highlight();
        }
        bToggle.getPosition().inTL(CONTROL_COL_X, 2f);

        rowUi.setHeightSoFar(26f);
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        col.addCustom(rowPanel, 2f);

        if (expanded) {
            for (DivisionArchetype arch : CONFIGURABLE_ARCHETYPES) {
                boolean selected = (arch == curr);
                CustomPanelAPI optPanel = Global.getSettings().createCustom(rowWidth, 24f, dialog.subPanelPlugin);
                TooltipMakerAPI optUi = optPanel.createUIElement(rowWidth, 24f, false);
                String selAct = "SET_ARCH:" + arch.name();
                ButtonAPI bOpt = dialog.registerButton(
                        optUi.addButton(
                                (selected ? "* " : "   ") + arch.shortPrefix,
                                selAct,
                                selected ? positive : baseColor,
                                darkColor,
                                Alignment.LMID,
                                CutStyle.ALL,
                                274f,
                                20f,
                                0f),
                        selAct);
                if (selected) {
                    bOpt.highlight();
                }
                bOpt.getPosition().inTL(CONTROL_COL_X + 12f, 2f);
                optUi.setHeightSoFar(24f);
                optPanel.addUIElement(optUi).inTL(0f, 0f);
                col.addCustom(optPanel, 1f);
            }
        }
    }

    private static void addDivisionCarrierOptionsToggleRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            boolean expanded,
            StrikeCraftMissionType effScOpenMis,
            StrikeCraftMissionType effScMainMis,
            VoidstrikeTargetObservance effVsObs,
            VoidstrikeCoordinationMode effVsCoord,
            int effVsDepth,
            int effVsWidth,
            Color baseColor,
            Color darkColor,
            Color highlight,
            Color positive,
            Color gray) {

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, 26f, dialog.subPanelPlugin);
        TooltipMakerAPI rowUi = rowPanel.createUIElement(rowWidth, 26f, false);

        LabelAPI carLbl = rowUi.addPara("Strike Craft Options:", 0f, baseColor);
        float carLblBot = DivisionUiWidgets.wrapLabelInPlace(carLbl, CONTROL_COL_X - 12f, 4f, 4f);
        DivisionUiWidgets.attachRowLabelTooltip(rowUi, (com.fs.starfarer.api.ui.UIComponentAPI) carLbl, "Strike Craft Options:");

        String toggleAct = "TOGGLE_DIV_CARRIER_OPTIONS";
        String btnText = (expanded ? "[^] " : "[v] ") + "Strike Craft Options";
        ButtonAPI bToggle = dialog.registerButton(
                rowUi.addButton(
                        btnText,
                        toggleAct,
                        expanded ? highlight : positive,
                        darkColor,
                        Alignment.LMID,
                        CutStyle.TL_BR,
                        204f,
                        21f,
                        0f),
                toggleAct);
        if (expanded) {
            bToggle.highlight();
        }
        bToggle.getPosition().inTL(CONTROL_COL_X, 2f);

        String scMisSummary = (effScOpenMis == effScMainMis)
                ? effScOpenMis.shortName
                : (effScOpenMis.shortName + " -> " + effScMainMis.shortName);
        String summary = String.format(
                Locale.ROOT,
                "%s  |  %s / %s  |  %dx%d",
                scMisSummary,
                effVsObs.displayName,
                effVsCoord.displayName,
                effVsDepth,
                effVsWidth);
        LabelAPI carSumLbl = rowUi.addPara(summary, 0f, expanded ? highlight : gray);
        float sumX = CONTROL_COL_X + 212f;
        float availRight = rowWidth - sumX - 6f;
        float carSumBot = (availRight >= 80f)
                ? DivisionUiWidgets.wrapLabelInPlace(carSumLbl, availRight, sumX, 4f)
                : DivisionUiWidgets.wrapLabelInPlace(carSumLbl, rowWidth - CONTROL_COL_X - 8f, CONTROL_COL_X, 25f);

        DivisionUiWidgets.resizeRowToFit(rowPanel, rowUi, rowWidth, Math.max(26f, Math.max(carLblBot, carSumBot) + 4f));
        rowPanel.addUIElement(rowUi).inTL(0f, 0f);
        col.addCustom(rowPanel, 2f);
    }

    private static void addDivisionMissionDropdownRow(
            final DivisionEditorDialog dialog,
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
        DivisionUiWidgets.addMissionDropdownRow(
                col, rowWidth, CONTROL_COL_X, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, dropdownKey, dialog.expandedDropdownKey, effMission,
                DivisionMissionType.missionsForArchetype(archetype),
                true, true, isCustom, true, actionPrefix,
                baseColor, darkColor, activeColor, gray);
    }

    private static void addDivisionStrikeCraftMissionDropdownRow(
            final DivisionEditorDialog dialog,
            TooltipMakerAPI col,
            float rowWidth,
            String labelText,
            String dropdownKey,
            StrikeCraftMissionType effMission,
            boolean isCustom,
            String actionPrefix,
            Color baseColor,
            Color darkColor,
            Color activeColor,
            Color gray) {
        DivisionUiWidgets.addStrikeCraftMissionDropdownRow(
                col, rowWidth, CONTROL_COL_X, dialog.subPanelPlugin, dialog.buttonRegistrar,
                labelText, dropdownKey, dialog.expandedDropdownKey, effMission,
                true, isCustom, actionPrefix,
                baseColor, darkColor, activeColor, gray);
    }

    static boolean handleDivisionOverrideAction(DivisionEditorDialog dialog, String cmd) {
        if ("TOGGLE_DIV_CARRIER_OPTIONS".equals(cmd)) {
            dialog.savePendingNameFieldIfAny();
            dialog.carrierOptionsExpanded = !dialog.carrierOptionsExpanded;
            dialog.rebuildContent();
            return true;
        }
        if ("TOGGLE_DIV_RAMMING_OPTIONS".equals(cmd)) {
            dialog.savePendingNameFieldIfAny();
            dialog.rammingOptionsExpanded = !dialog.rammingOptionsExpanded;
            dialog.rebuildContent();
            return true;
        }
        String activeDivisionId = dialog.activeDivisionId;
        if (cmd.startsWith("DIV_STATION_SPACING:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_STATION_SPACING:".length());
            Integer val = "INHERIT".equals(arg) ? null : Integer.parseInt(arg);
            PermanentDivisionManager.updateIndividualDivisionSettings(
                    activeDivisionId, true, val, false, null, false, null, false, null, false, null);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_DIVISION_INTERVAL:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_DIVISION_INTERVAL:".length());
            Integer val = "INHERIT".equals(arg) ? null : Integer.parseInt(arg);
            PermanentDivisionManager.updateIndividualDivisionSettings(
                    activeDivisionId, false, null, true, val, false, null, false, null, false, null);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_STANDOFF_RANGE:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_STANDOFF_RANGE:".length());
            if ("AUTO".equals(arg)) {
                PermanentDivisionManager.autoSetDivisionStandoffRange(activeDivisionId);
            } else {
                Integer val = "INHERIT".equals(arg) ? null : Integer.parseInt(arg);
                PermanentDivisionManager.updateIndividualDivisionSettings(
                        activeDivisionId, false, null, false, null, true, val, false, null, false, null);
            }
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_LINE_STATION:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_LINE_STATION:".length());
            LineStation ps = "INHERIT".equals(arg) ? null : LineStation.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionLineStation(activeDivisionId, ps);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_FORMATION:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_FORMATION:".length());
            DivisionFormation fm = "INHERIT".equals(arg) ? null : DivisionFormation.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionFormation(activeDivisionId, fm);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_STICKINESS:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_STICKINESS:".length());
            DivisionMovementStickiness ms =
                    "INHERIT".equals(arg) ? null : DivisionMovementStickiness.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionMovementStickiness(activeDivisionId, ms);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_SETPIECE:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_SETPIECE:".length());
            DivisionSetpieceAttack sp =
                    "INHERIT".equals(arg) ? null : DivisionSetpieceAttack.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionSetpieceAttack(activeDivisionId, sp);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_SKILL_COORD:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_SKILL_COORD:".length());
            ShipSkillCoordinationMode mode =
                    "INHERIT".equals(arg) ? null : ShipSkillCoordinationMode.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionShipSkillCoordination(activeDivisionId, mode);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_RAM_MODE:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_RAM_MODE:".length());
            DivisionRammingMode mode =
                    "INHERIT".equals(arg) ? null : DivisionRammingMode.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionRammingMode(activeDivisionId, mode);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_RAM_SCOPE:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_RAM_SCOPE:".length());
            DivisionRammingTargetScope scope =
                    "INHERIT".equals(arg) ? null : DivisionRammingTargetScope.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionRammingTargetScope(activeDivisionId, scope);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_RAM_SLOTS:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_RAM_SLOTS:".length());
            Integer slots = "INHERIT".equals(arg) ? null : Integer.parseInt(arg);
            PermanentDivisionManager.updateIndividualDivisionMaxRammingSlots(activeDivisionId, slots);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_SC_OPEN_MIS:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_SC_OPEN_MIS:".length());
            StrikeCraftMissionType mis =
                    "INHERIT".equals(arg) ? null : StrikeCraftMissionType.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionStrikeCraftMission(activeDivisionId, true, mis);
            dialog.expandedDropdownKey = null;
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_SC_MAIN_MIS:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_SC_MAIN_MIS:".length());
            StrikeCraftMissionType mis =
                    "INHERIT".equals(arg) ? null : StrikeCraftMissionType.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionStrikeCraftMission(activeDivisionId, false, mis);
            dialog.expandedDropdownKey = null;
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_VS_BIAS:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_VS_BIAS:".length());
            VoidstrikeTargetingBias bias =
                    "INHERIT".equals(arg) ? null : VoidstrikeTargetingBias.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionVoidstrikeTargetingBias(activeDivisionId, bias);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_VS_CV_FOCUS:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_VS_CV_FOCUS:".length());
            if ("INHERIT".equals(arg)) {
                PermanentDivisionManager.updateIndividualDivisionVoidstrikeFocusCarriers(activeDivisionId, null);
            } else {
                PermanentDivisionSpec curSpec = null;
                for (PermanentDivisionSpec s : PermanentDivisionManager.getPermanentDivisions()) {
                    if (s != null && activeDivisionId.equals(s.id)) {
                        curSpec = s;
                        break;
                    }
                }
                boolean currEff = PermanentDivisionManager.resolveEffectiveVoidstrikeFocusCarriers(
                        curSpec, PermanentDivisionManager.getFleetDoctrine());
                PermanentDivisionManager.updateIndividualDivisionVoidstrikeFocusCarriers(
                        activeDivisionId, Boolean.valueOf(!currEff));
            }
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_VS_OBS:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_VS_OBS:".length());
            VoidstrikeTargetObservance obs =
                    "INHERIT".equals(arg) ? null : VoidstrikeTargetObservance.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionVoidstrikeObservance(activeDivisionId, obs);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_VS_COORD:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_VS_COORD:".length());
            VoidstrikeCoordinationMode mode =
                    "INHERIT".equals(arg) ? null : VoidstrikeCoordinationMode.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionVoidstrikeCoordination(activeDivisionId, mode);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_VS_GEOM:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_VS_GEOM:".length());
            VoidstrikeAttackGeometry geom =
                    "INHERIT".equals(arg) ? null : VoidstrikeAttackGeometry.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionVoidstrikeAttackGeometry(activeDivisionId, geom);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_VS_SPLIT:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_VS_SPLIT:".length());
            VoidstrikeSplitMode split =
                    "INHERIT".equals(arg) ? null : VoidstrikeSplitMode.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionVoidstrikeSplitMode(activeDivisionId, split);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_VS_GUN:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_VS_GUN:".length());
            VoidstrikeGunneryMode gun =
                    "INHERIT".equals(arg) ? null : VoidstrikeGunneryMode.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionVoidstrikeGunneryMode(activeDivisionId, gun);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_VS_AOA:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_VS_AOA:".length());
            VoidstrikeAoaCorrection aoa =
                    "INHERIT".equals(arg) ? null : VoidstrikeAoaCorrection.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionVoidstrikeAoaCorrection(activeDivisionId, aoa);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_FTR_FM:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_FTR_FM:".length());
            SquadronFormation fm =
                    "INHERIT".equals(arg) ? null : SquadronFormation.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionFighterFormation(activeDivisionId, fm);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_FTR_VS_FM:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_FTR_VS_FM:".length());
            SquadronFormation fm =
                    "INHERIT".equals(arg) ? null : SquadronFormation.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionFighterVoidstrikeFormation(activeDivisionId, fm);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_BMB_FM:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_BMB_FM:".length());
            SquadronFormation fm =
                    "INHERIT".equals(arg) ? null : SquadronFormation.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionBomberFormation(activeDivisionId, fm);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_BMB_VS_FM:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_BMB_VS_FM:".length());
            SquadronFormation fm =
                    "INHERIT".equals(arg) ? null : SquadronFormation.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionBomberVoidstrikeFormation(activeDivisionId, fm);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_VS_DEPTH:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_VS_DEPTH:".length());
            Integer depth = "INHERIT".equals(arg) ? null : Integer.parseInt(arg);
            PermanentDivisionManager.updateIndividualDivisionVoidstrikeSquadronDepth(activeDivisionId, depth);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_VS_WIDTH:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_VS_WIDTH:".length());
            Integer width = "INHERIT".equals(arg) ? null : Integer.parseInt(arg);
            PermanentDivisionManager.updateIndividualDivisionVoidstrikeSquadronWidth(activeDivisionId, width);
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_OPEN_MIS:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_OPEN_MIS:".length());
            DivisionMissionType mis = "INHERIT".equals(arg) ? null : DivisionMissionType.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionSettings(
                    activeDivisionId, false, null, false, null, false, null, true, mis, false, null);
            dialog.expandedDropdownKey = null;
            dialog.rebuildContent();
            return true;
        }
        if (cmd.startsWith("DIV_MAIN_MIS:") && activeDivisionId != null) {
            dialog.savePendingNameFieldIfAny();
            String arg = cmd.substring("DIV_MAIN_MIS:".length());
            DivisionMissionType mis = "INHERIT".equals(arg) ? null : DivisionMissionType.valueOf(arg);
            PermanentDivisionManager.updateIndividualDivisionSettings(
                    activeDivisionId, false, null, false, null, false, null, false, null, true, mis);
            dialog.expandedDropdownKey = null;
            dialog.rebuildContent();
            return true;
        }
        return false;
    }
}
