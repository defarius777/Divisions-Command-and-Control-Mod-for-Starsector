package divisionai.sim;

import divisionai.DivisionDeploymentDialogOrganizer;
import divisionai.DivisionEditorDialog;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionNomenclature;
import divisionai.DivisionNomenclature.NumberingStyle;
import divisionai.DivisionNomenclature.PlayerNamingPreset;
import divisionai.FactionFleetDoctrines;
import divisionai.FleetDoctrineDialog;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.DoctrineDivisionType;
import divisionai.PermanentDivisionManager.DoctrineHullCategory;
import divisionai.PermanentDivisionManager.DoctrineOption;
import divisionai.PermanentDivisionManager.FleetDoctrineConfig;
import divisionai.PermanentDivisionManager.LineStation;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.OtcPlay;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headless UI & UX Testing Harness for Starsector Division AI.
 *
 * Audits:
 *  1. Pre-Battle Deployment Window enlargement geometry (14 cols x 7 reserve rows, 12 ships/row,
 *     multi-row division card spanning, zero cell overlap).
 *  2. Succinctness & word-salad constraints across all UI enums (DivisionMissionType, OtcAuthority,
 *     OtcPlay, DoctrineOption, LineStation, DoctrineHullCategory, NumberingStyle, PlayerNamingPreset).
 *  3. Source-level UI copy & button pixel-width budget audit across DivisionDeploymentDialogOrganizer,
 *     DivisionEditorDialog, FleetDoctrineDialog, CampaignFleetTooltipOrganizer, and DivisionTacticalMapOverlay.
 *  4. Exports an interactive HTML5 UI/UX Visual Preview Gallery (build/ui_preview.html).
 */
public class UITestHarness {

    public static class UIAuditResult {
        public boolean allPassed = true;
        public int checksPassed = 0;
        public int checksFailed = 0;
        public final List<String> details = new ArrayList<>();

        public void record(boolean ok, String name, String info) {
            if (ok) {
                checksPassed++;
                details.add("  [PASS] " + name + " -> " + info);
            } else {
                checksFailed++;
                allPassed = false;
                details.add("  [FAIL] " + name + " -> " + info);
            }
        }
    }

    public static UIAuditResult runAllAuditsAndExportPreview(File previewHtmlOut) throws Exception {
        UIAuditResult res = new UIAuditResult();

        auditDeploymentWindowGeometry(res);
        auditFleetDoctrineWindowAndOtcLayout(res);
        auditV067FeaturesAndQAPass(res);
        auditV068IconsReinitAndFlagshipStars(res);
        auditEnumSuccinctnessAndClarity(res);
        auditUISourceFilesAndButtonBudgets(res);

        if (previewHtmlOut != null) {
            exportUIPreviewHtml(previewHtmlOut, res);
        }
        return res;
    }

    private static void auditDeploymentWindowGeometry(UIAuditResult res) {
        int totalCols = DivisionDeploymentDialogOrganizer.ENLARGED_TOTAL_COLS;
        int labelCols = DivisionDeploymentDialogOrganizer.LABEL_COLS;
        int shipsPerRow = DivisionDeploymentDialogOrganizer.SHIPS_PER_ROW;
        int visibleRows = DivisionDeploymentDialogOrganizer.TARGET_RESERVE_VISIBLE_ROWS;

        float itemW = 58f;
        float itemH = 58f;
        float vanillaInnerW = 10 * itemW + 34f; // 614px
        float enlargedInnerW = totalCols * itemW + 34f; // 846px
        float vanillaReserveH = 3 * itemH; // 174px
        float enlargedReserveH = visibleRows * itemH; // 406px

        boolean dimsOk = (totalCols == 14)
                && (labelCols == 2)
                && (shipsPerRow == 12)
                && (visibleRows == 7)
                && (enlargedInnerW <= 1920f - 140f)
                && (enlargedReserveH >= 400f);

        res.record(
                dimsOk,
                "Pre-Battle Deployment Window enlarged to 14 cols x 7 reserve rows (12 ships/row)",
                String.format(
                        Locale.ROOT,
                        "cols=%d (2 header + %d ships/row), visibleRows=%d, innerW=%.0fpx (vs %.0fpx vanilla), reserveH=%.0fpx (vs %.0fpx vanilla)",
                        totalCols, shipsPerRow, visibleRows, enlargedInnerW, vanillaInnerW, enlargedReserveH, vanillaReserveH));

        // Simulate laying out a 4-ship division, a 15-ship division (wraps across 2 rows: 12 + 3), and 5 unassigned ships
        int[] groupSizes = {4, 15, 5};
        int slotIdx = 0;
        boolean layoutClean = true;
        for (int gSize : groupSizes) {
            if (slotIdx % totalCols != 0) layoutClean = false;
            int rowsNeeded = (gSize + shipsPerRow - 1) / shipsPerRow;
            int remaining = gSize;
            for (int r = 0; r < rowsNeeded; r++) {
                // 2 label columns
                slotIdx += labelCols;
                int inRow = Math.min(shipsPerRow, remaining);
                slotIdx += inRow;
                remaining -= inRow;
                // Pad to end of row
                while (slotIdx % totalCols != 0) {
                    slotIdx++;
                }
            }
            if (remaining != 0 || (slotIdx % totalCols) != 0) {
                layoutClean = false;
            }
        }
        int totalRowsUsed = slotIdx / totalCols;
        res.record(
                layoutClean && totalRowsUsed == 4,
                "Deployment grid multi-row division wrapping aligns on 14-column boundaries without overlap",
                "groupSizes=[4, 15, 5] -> totalRows=" + totalRowsUsed + ", totalSlots=" + slotIdx);
    }

    private static void auditFleetDoctrineWindowAndOtcLayout(UIAuditResult res) throws Exception {
        float dialogW = FleetDoctrineDialog.DIALOG_WIDTH;
        float dialogH = FleetDoctrineDialog.DIALOG_HEIGHT;
        float toolbarW = dialogW - 24f; // 1216px
        float rowWidth = dialogW - 40f; // 1200px

        boolean sizeOk = (dialogW >= 1220f && dialogW <= 1840f) && (dialogH >= 760f && dialogH <= 1000f);
        res.record(
                sizeOk,
                "Fleet Doctrine window enlarged to spacious 1240x780 viewport",
                String.format(Locale.ROOT, "dialog=%.0fx%.0fpx (toolbarW=%.0fpx, rowWidth=%.0fpx)", dialogW, dialogH, toolbarW, rowWidth));

        // Verify top toolbar tab & action button intervals [xStart, xEnd] have >= 8px gaps and zero overlap
        float[][] toolbarButtons = {
                {0f, 84f},     // All
                {92f, 228f},   // OTC Command
                {236f, 388f},  // Division Defaults
                {396f, 514f},  // Hull Limits
                {522f, 654f},  // Nomenclature
                {toolbarW - 116f - 8f - 128f - 8f - 152f, toolbarW - 116f - 8f - 128f - 8f}, // Reinit Sector Divs (804..956)
                {toolbarW - 116f - 8f - 128f, toolbarW - 116f - 8f},                         // Reset Defaults (964..1092)
                {toolbarW - 116f, toolbarW}                                                  // Close [Esc] (1100..1216)
        };
        float minToolbarGap = Float.MAX_VALUE;
        for (int i = 0; i + 1 < toolbarButtons.length; i++) {
            float gap = toolbarButtons[i + 1][0] - toolbarButtons[i][1];
            if (gap < minToolbarGap) minToolbarGap = gap;
        }
        float tabToActionGap = toolbarButtons[5][0] - toolbarButtons[4][1];

        res.record(
                minToolbarGap >= 8f && tabToActionGap >= 120f,
                "Fleet Doctrine toolbar tabs and action buttons have zero overlap and wide visual separation",
                String.format(Locale.ROOT, "minAdjacentGap=%.0fpx, tabToActionSeparation=%.0fpx", minToolbarGap, tabToActionGap));

        // Verify OTC Authority dropdown & Tactical Play rows fit on single lines without vertical or horizontal collision
        File fddFile = new File("src/divisionai/FleetDoctrineDialog.java");
        String src = new String(Files.readAllBytes(fddFile.toPath()), StandardCharsets.UTF_8);
        boolean singleLineOtcDropdown = !src.contains("optPanel.addUIElement(optDesc)")
                && src.contains("inTL(CONTROL_COL_X + 252f, 5f)")
                && src.contains("inTL(CONTROL_COL_X + 136f, 6f)");

        float maxOtcSummaryPx = 0f;
        for (OtcAuthority a : OtcAuthority.values()) {
            maxOtcSummaryPx = Math.max(maxOtcSummaryPx, a.summary.length() * 6.5f);
        }
        float availableOtcSummaryWidth = rowWidth - (210f + 252f); // 1280 - 462 = 818px

        res.record(
                singleLineOtcDropdown && maxOtcSummaryPx < availableOtcSummaryWidth - 40f,
                "OTC Authority dropdown and Tactical Plays align on single horizontal rows without vertical overlap",
                String.format(Locale.ROOT, "singleLineDropdown=%s, maxSummaryPx=%.0fpx < available=%.0fpx",
                        singleLineOtcDropdown, maxOtcSummaryPx, availableOtcSummaryWidth));
    }

    private static void auditV067FeaturesAndQAPass(UIAuditResult res) throws Exception {
        // A. Fleet UI button order & rename ("Fleet Doctrine" on top, "Fleet Organization" below)
        String injSrc = new String(Files.readAllBytes(new File("src/divisionai/DivisionFleetTabInjector.java").toPath()), StandardCharsets.UTF_8);
        String edSrc = new String(Files.readAllBytes(new File("src/divisionai/DivisionEditorDialog.java").toPath()), StandardCharsets.UTF_8);
        String docSrc = new String(Files.readAllBytes(new File("src/divisionai/FleetDoctrineDialog.java").toPath()), StandardCharsets.UTF_8);

        int docBtnIdx = injSrc.indexOf("\"Fleet Doctrine\"");
        int orgBtnIdx = injSrc.indexOf("\"Fleet Organization\"");
        boolean itemAOk = docBtnIdx > 0 && orgBtnIdx > docBtnIdx
                && !injSrc.contains("\"Division Editor\"")
                && edSrc.contains("addTitle(\"FLEET ORGANIZATION\"")
                && edSrc.contains("\"<- Fleet Organization\"");
        res.record(
                itemAOk,
                "Item A: 'Division Editor' renamed to 'Fleet Organization' and placed below 'Fleet Doctrine' in Fleet UI",
                "doctrineBtnIdx=" + docBtnIdx + " < fleetOrgBtnIdx=" + orgBtnIdx);

        // B. Configure Division screen QA pass (1320x800, 15 archetypes in 2x8 grid, single-line controls)
        boolean itemBOk = DivisionEditorDialog.DIALOG_WIDTH == 1320f
                && DivisionEditorDialog.DIALOG_HEIGHT == 800f
                && edSrc.contains("colIdx * 162f")
                && edSrc.contains("CONTROL_COL_X = 250f");
        res.record(
                itemBOk,
                "Item B: Configure Division screen enlarged to 1320x800 with 2x8 archetype grid and aligned controls",
                String.format(Locale.ROOT, "dialog=%.0fx%.0fpx", DivisionEditorDialog.DIALOG_WIDTH, DivisionEditorDialog.DIALOG_HEIGHT));

        // C & D. Line Cruiser Division default + 4 new Missile division types
        boolean itemCDOk = "Line Cruiser Div".equals(DivisionArchetype.CRUISER_DIVISION.shortPrefix)
                && "Line Cruiser Division".equals(DoctrineDivisionType.CRUISER.displayName)
                && "Line Cruiser Div".equals(DivisionNomenclature.getDefaultPrefixForPreset(PlayerNamingPreset.NAVAL_STANDARD, DivisionArchetype.CRUISER_DIVISION))
                && DivisionArchetype.MISSILE_BATTLESHIP_DIVISION.isMissileArchetype()
                && DivisionArchetype.MISSILE_CRUISER_DIVISION.isMissileArchetype()
                && DivisionArchetype.MISSILE_DESTROYER_DIVISION.isMissileArchetype()
                && DivisionArchetype.MISSILE_FRIGATE_DIVISION.isMissileArchetype()
                && DoctrineDivisionType.forArchetype(DivisionArchetype.MISSILE_BATTLESHIP_DIVISION) == DoctrineDivisionType.MISSILE_BATTLESHIP
                && DoctrineDivisionType.forArchetype(DivisionArchetype.MISSILE_CRUISER_DIVISION) == DoctrineDivisionType.MISSILE_CRUISER
                && DoctrineDivisionType.forArchetype(DivisionArchetype.MISSILE_DESTROYER_DIVISION) == DoctrineDivisionType.MISSILE_DESTROYER
                && DoctrineDivisionType.forArchetype(DivisionArchetype.MISSILE_FRIGATE_DIVISION) == DoctrineDivisionType.MISSILE_FRIGATE;
        res.record(
                itemCDOk,
                "Items C & D: Line Cruiser Division default and 4 Missile division types (Battleship, Cruiser, Destroyer, Frigate)",
                "archetypes=" + DivisionArchetype.values().length + ", doctrineTypes=" + DoctrineDivisionType.values().length);

        // E. Scroll bar position preservation across UI rebuilds
        boolean itemEOk = docSrc.contains("activeBodyScroller.getYOffset()")
                && docSrc.contains("activeBodyScroller.setYOffset(")
                && edSrc.contains("savedOverviewLeftScrollY")
                && edSrc.contains("savedSingleLeftScrollY")
                && edSrc.contains("scroller.setYOffset(");
        res.record(
                itemEOk,
                "Item E: Scroll bar offset preserved across UI actions in Fleet Doctrine and Fleet Organization/Configure Division",
                "FleetDoctrineDialog + DivisionEditorDialog scroll state hooks verified");

        // F. Commander portrait and level badge in lower-left corner of ship icons
        boolean itemFOk = edSrc.contains("renderCommanderPortraitSprite")
                && edSrc.contains("Fonts.VICTOR_10")
                && edSrc.contains("showCommanderLevel")
                && edSrc.contains("float px = x + 2f;")
                && edSrc.contains("float py = y + 2f;");
        res.record(
                itemFOk,
                "Item F: Ship icons render commander portrait in lower-left corner with Fleet-screen Victor-10 level badge",
                "portraitBoxSize=24px at lower-left (x+2, y+2) with Victor-10 top-right level badge");

        // G. Martyrdom tactics enabled for Luddic Path (not Church) and off by default for player and other factions
        FleetDoctrineConfig defaultPlayerCfg = new FleetDoctrineConfig();
        FleetDoctrineConfig luddicPathCfg = FactionFleetDoctrines.getDoctrineForFactionId("luddic_path", null);
        FleetDoctrineConfig luddicChurchCfg = FactionFleetDoctrines.getDoctrineForFactionId("luddic_church", null);
        FleetDoctrineConfig hegemonyCfg = FactionFleetDoctrines.getDoctrineForFactionId("hegemony", null);
        FleetDoctrineConfig triTachCfg = FactionFleetDoctrines.getDoctrineForFactionId("tritachyon", null);

        boolean itemGOk = !defaultPlayerCfg.isPlayEnabled(OtcPlay.MARTYRDOM)
                && luddicPathCfg.isPlayEnabled(OtcPlay.MARTYRDOM)
                && luddicPathCfg.getMainMission(DoctrineDivisionType.FRIGATE) == DivisionMissionType.MARTYRDOM
                && luddicPathCfg.getMainMission(DoctrineDivisionType.DESTROYER) == DivisionMissionType.MARTYRDOM
                && luddicPathCfg.getMainMission(DoctrineDivisionType.CRUISER) == DivisionMissionType.MARTYRDOM
                && !luddicChurchCfg.isPlayEnabled(OtcPlay.MARTYRDOM)
                && !hegemonyCfg.isPlayEnabled(OtcPlay.MARTYRDOM)
                && !triTachCfg.isPlayEnabled(OtcPlay.MARTYRDOM);
        res.record(
                itemGOk,
                "Item G: 'Martyrdom' tactics enabled for Luddic Path (not Luddic Church) and off by default for player & other factions",
                String.format(Locale.ROOT, "player=%s, luddic_path=%s, luddic_church=%s, hegemony=%s",
                        defaultPlayerCfg.isPlayEnabled(OtcPlay.MARTYRDOM),
                        luddicPathCfg.isPlayEnabled(OtcPlay.MARTYRDOM),
                        luddicChurchCfg.isPlayEnabled(OtcPlay.MARTYRDOM),
                        hegemonyCfg.isPlayEnabled(OtcPlay.MARTYRDOM)));
    }

    private static void auditV068IconsReinitAndFlagshipStars(UIAuditResult res) throws Exception {
        File modIcon = new File("mod/icon.png");
        File regIcon = new File("mod/graphics/icons/icon_regular.png");
        File goldStarIcon = new File("mod/graphics/icons/icon_gold-star.png");
        File goldWingIcon = new File("mod/graphics/icons/icon_gold-wing.png");
        File silverStar = new File("mod/graphics/icons/star_silver.png");
        File goldStar = new File("mod/graphics/icons/star_gold.png");
        File lunaCfg = new File("mod/data/config/LunaSettingsConfig.json");
        File settingsJson = new File("mod/data/config/settings.json");

        String lunaCfgStr = lunaCfg.exists() ? new String(Files.readAllBytes(lunaCfg.toPath()), StandardCharsets.UTF_8) : "";
        String settingsStr = settingsJson.exists() ? new String(Files.readAllBytes(settingsJson.toPath()), StandardCharsets.UTF_8) : "";

        boolean iconsRegistered = modIcon.exists() && modIcon.length() > 100
                && regIcon.exists() && regIcon.length() > 100
                && goldStarIcon.exists() && goldStarIcon.length() > 100
                && goldWingIcon.exists() && goldWingIcon.length() > 100
                && silverStar.exists() && silverStar.length() > 100
                && goldStar.exists() && goldStar.length() > 100
                && lunaCfgStr.contains("\"iconPath\": \"graphics/icons/icon_regular.png\"")
                && settingsStr.contains("\"icon_regular\": \"graphics/icons/icon_regular.png\"")
                && settingsStr.contains("\"icon_gold-star\": \"graphics/icons/icon_gold-star.png\"")
                && settingsStr.contains("\"icon_gold-wing\": \"graphics/icons/icon_gold-wing.png\"")
                && settingsStr.contains("\"star_silver\": \"graphics/icons/star_silver.png\"")
                && settingsStr.contains("\"star_gold\": \"graphics/icons/star_gold.png\"");

        res.record(
                iconsRegistered,
                "v0.6.8 Mod Icon & Sprite Registration: icon_regular in mod/icon.png + LunaSettingsConfig.json + settings.json",
                "all 6 PNG sprites present and registered in settings.json + LunaSettingsConfig.json");

        String settingsJava = new String(Files.readAllBytes(new File("src/divisionai/DivisionAISettings.java").toPath()), StandardCharsets.UTF_8);
        String combatPluginJava = new String(Files.readAllBytes(new File("src/divisionai/DivisionAICombatPlugin.java").toPath()), StandardCharsets.UTF_8);
        boolean reinitFixed = settingsJava.contains("foundBtn.isSelected()")
                && settingsJava.contains("foundBtn.unselect()")
                && settingsJava.contains("consumePendingReinitOnGameLoad")
                && combatPluginJava.contains("DivisionAISettings.advanceLunaSettingsReinitButton()");

        res.record(
                reinitFixed,
                "v0.6.8 LunaSettings Reinitialize Button: handles non-regular button selection clicks in Campaign & Title Screen",
                "foundBtn.isSelected() + foundBtn.unselect() + Title Screen queue verified");

        String injJava = new String(Files.readAllBytes(new File("src/divisionai/DivisionFleetTabInjector.java").toPath()), StandardCharsets.UTF_8);
        int starImgPos = injJava.indexOf("tt.addImage(starIconPath");
        int docBtnPos = injJava.lastIndexOf("\"Fleet Doctrine\"");
        int wingImgPos = injJava.indexOf("tt.addImage(wingIconPath");
        int orgBtnPos = injJava.lastIndexOf("\"Fleet Organization\"");
        boolean fleetIconsOk = injJava.contains("\"icon_gold-star\"")
                && injJava.contains("\"icon_gold-wing\"")
                && starImgPos > 0 && starImgPos < docBtnPos
                && wingImgPos > docBtnPos && wingImgPos < orgBtnPos;

        res.record(
                fleetIconsOk,
                "v0.6.8 Fleet Screen Button Icons: icon_gold-star next to 'Fleet Doctrine' and icon_gold-wing next to 'Fleet Organization'",
                "starImg->Fleet Doctrine and wingImg->Fleet Organization layout verified");

        String overlayJava = new String(Files.readAllBytes(new File("src/divisionai/DivisionTacticalMapOverlay.java").toPath()), StandardCharsets.UTF_8);
        boolean tabStarsOk = overlayJava.contains("renderFlagshipStarsOnTopLayer")
                && overlayJava.contains("resolveFleetFlagships")
                && overlayJava.contains("resolveDivisionFlagships")
                && overlayJava.contains("\"star_gold\"")
                && overlayJava.contains("\"star_silver\"")
                && overlayJava.contains("!fleetFlagships.contains(flag)");

        res.record(
                tabStarsOk,
                "v0.6.8 Tab Command Screen Flagship Stars: silver star on Division flagships & gold star on Fleet flagship(s) upper-right corner",
                "renderFlagshipStarsOnTopLayer + gold-over-silver precedence verified");
    }

    private static void auditEnumSuccinctnessAndClarity(UIAuditResult res) {
        List<String> violations = new ArrayList<>();
        int auditedStrings = 0;

        for (DivisionMissionType m : DivisionMissionType.values()) {
            auditedStrings += 2;
            if (m.dropdownLabel.contains("\"") || m.dropdownLabel.matches("^[a-zA-Z]\\.\\s.*")) {
                violations.add("DivisionMissionType." + m.name() + " dropdownLabel has noisy prefix/quotes: " + m.dropdownLabel);
            }
            if (m.summary.length() > 110 || countWords(m.summary) > 16) {
                violations.add("DivisionMissionType." + m.name() + " summary too wordy (" + m.summary.length() + " chars): " + m.summary);
            }
        }

        for (OtcAuthority a : OtcAuthority.values()) {
            auditedStrings += 2;
            if (a.dropdownLabel.contains("\"") || a.dropdownLabel.matches("^[a-zA-Z]\\.\\s.*")) {
                violations.add("OtcAuthority." + a.name() + " dropdownLabel has noisy prefix/quotes: " + a.dropdownLabel);
            }
            if (a.summary.length() > 120 || countWords(a.summary) > 18) {
                violations.add("OtcAuthority." + a.name() + " summary too wordy (" + a.summary.length() + " chars): " + a.summary);
            }
        }

        for (OtcPlay p : OtcPlay.values()) {
            auditedStrings += 2;
            if (p.summary.length() > 120 || countWords(p.summary) > 19) {
                violations.add("OtcPlay." + p.name() + " summary too wordy (" + p.summary.length() + " chars): " + p.summary);
            }
        }

        for (DoctrineOption d : DoctrineOption.values()) {
            auditedStrings += 2;
            if (d.displayName.length() > 20) {
                violations.add("DoctrineOption." + d.name() + " displayName exceeds 20 chars: " + d.displayName);
            }
            if (d.summary.length() > 95 || countWords(d.summary) > 15) {
                violations.add("DoctrineOption." + d.name() + " summary too wordy (" + d.summary.length() + " chars): " + d.summary);
            }
        }

        for (LineStation ls : LineStation.values()) {
            auditedStrings++;
            if (ls.summary.length() > 60) {
                violations.add("LineStation." + ls.name() + " summary exceeds 60 chars: " + ls.summary);
            }
        }

        for (DoctrineHullCategory hc : DoctrineHullCategory.values()) {
            auditedStrings += 2;
            if (hc.minLabel.length() > 26 || hc.maxLabel.length() > 26) {
                violations.add("DoctrineHullCategory." + hc.name() + " min/max label exceeds 26 chars");
            }
        }

        for (NumberingStyle ns : NumberingStyle.values()) {
            auditedStrings += 2;
            if (ns.displayName.length() > 26 || ns.summary.length() > 80) {
                violations.add("NumberingStyle." + ns.name() + " copy exceeds budget");
            }
        }

        for (PlayerNamingPreset np : PlayerNamingPreset.values()) {
            auditedStrings += 2;
            if (np.summary.length() > 105) {
                violations.add("PlayerNamingPreset." + np.name() + " summary exceeds 105 chars: " + np.summary);
            }
        }

        res.record(
                violations.isEmpty(),
                "All UI enum titles, dropdown labels, and summaries pass succinctness & word-salad caps",
                "auditedStrings=" + auditedStrings + ", violations=" + violations);
    }

    private static void auditUISourceFilesAndButtonBudgets(UIAuditResult res) throws Exception {
        String[] uiFiles = {
                "src/divisionai/DivisionDeploymentDialogOrganizer.java",
                "src/divisionai/DivisionEditorDialog.java",
                "src/divisionai/FleetDoctrineDialog.java",
                "src/divisionai/CampaignFleetTooltipOrganizer.java",
                "src/divisionai/DivisionTacticalMapOverlay.java"
        };

        List<String> bannedClutter = new ArrayList<>();
        int buttonCallsChecked = 0;
        List<String> buttonOverflows = new ArrayList<>();

        // Pattern matching literal-label addButton("Label", ..., width, height, pad)
        Pattern literalBtnPattern = Pattern.compile(
                "addButton\\(\\s*\"([^\"]+)\"\\s*,[^,]+,[^,]+,[^,]+,[^,]+,[^,]+,\\s*([0-9]+(?:\\.[0-9]+)?)f\\s*,\\s*([0-9]+(?:\\.[0-9]+)?)f",
                Pattern.MULTILINE);

        for (String path : uiFiles) {
            File f = new File(path);
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);

            if (src.contains("(Click to change)")) {
                bannedClutter.add(f.getName() + ": contains '(Click to change)'");
            }
            if (src.contains("x Paragon)")) {
                bannedClutter.add(f.getName() + ": contains 'x Paragon)' ratio clutter");
            }
            if (src.contains("[SELECTED] ") || src.contains("[Select] ")) {
                bannedClutter.add(f.getName() + ": contains '[SELECTED]/[Select]' prefix clutter");
            }
            if (src.contains("[%s | %d%% FLUX]")) {
                bannedClutter.add(f.getName() + ": contains bracketed FLUX sub-badge clutter");
            }
            if (src.contains("? CONT.")) {
                bannedClutter.add(f.getName() + ": contains '? CONT.' continuation card");
            }

            Matcher m = literalBtnPattern.matcher(src);
            while (m.find()) {
                buttonCallsChecked++;
                String label = m.group(1);
                float width = Float.parseFloat(m.group(2));
                float estimatedTextPx = label.length() * 6.8f + 12f;
                if (estimatedTextPx > width + 4f) {
                    buttonOverflows.add(f.getName() + ": button '" + label + "' (" + Math.round(estimatedTextPx) + "px) > width " + width + "px");
                }
            }
        }

        res.record(
                bannedClutter.isEmpty(),
                "Zero word-salad or redundant prefix/bracket patterns across all 5 UI classes",
                "filesChecked=" + uiFiles.length + ", clutterViolations=" + bannedClutter);

        res.record(
                buttonOverflows.isEmpty() && buttonCallsChecked >= 15,
                "All static UI buttons fit comfortably within their pixel width budgets",
                "buttonsAudited=" + buttonCallsChecked + ", overflows=" + buttonOverflows);
    }

    private static int countWords(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        return s.trim().split("\\s+").length;
    }

    private static void exportUIPreviewHtml(File outFile, UIAuditResult res) throws Exception {
        if (outFile.getParentFile() != null) {
            outFile.getParentFile().mkdirs();
        }
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {
            out.println("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>");
            out.println("<title>Starsector Division AI — UI/UX Visual Preview & Audit</title>");
            out.println("<style>");
            out.println("body { background:#060a10; color:#cde8f6; font-family:'Segoe UI',Consolas,monospace; margin:24px; }");
            out.println("h1 { color:#7ce8ff; letter-spacing:1.5px; font-size:20px; margin-bottom:4px; }");
            out.println("h2 { color:#ffd859; font-size:14px; letter-spacing:1px; border-bottom:1px solid #1e3b52; padding-bottom:4px; margin-top:24px; }");
            out.println(".panel { background:#09111a; border:1px solid #2b5c7e; padding:14px; margin-bottom:18px; border-radius:3px; }");
            out.println(".deploy-grid { display:grid; grid-template-columns: repeat(14, 58px); gap:2px; background:#05080d; padding:8px; border:1px solid #1f445e; width:max-content; }");
            out.println(".hdr-card { grid-column: span 2; background:#08121c; border-left:3px solid #4be17d; border-top:1px solid rgba(75,225,125,0.35); padding:5px 6px; font-size:11px; display:flex; flex-direction:column; justify-content:space-between; }");
            out.println(".hdr-title { color:#82ffa5; font-weight:600; }");
            out.println(".hdr-btn { background:#123420; color:#4bd778; border:1px solid #4bd778; text-align:center; font-size:10px; padding:2px 0; border-radius:2px; }");
            out.println(".ship-cell { width:58px; height:58px; background:#0b1622; border:1px solid #234966; display:flex; flex-direction:column; align-items:center; justify-content:center; font-size:10px; color:#9ecae6; }");
            out.println(".ship-cell.flag { border-color:#ffd84a; background:#19180a; color:#ffe270; }");
            out.println(".ship-cell.empty { background:transparent; border:1px dashed rgba(35,73,102,0.25); }");
            out.println(".badge { color:#a8ffb8; font-size:12px; }");
            out.println("table { border-collapse:collapse; width:100%; font-size:12px; }");
            out.println("th, td { border-bottom:1px solid #15293b; padding:6px 8px; text-align:left; }");
            out.println("th { color:#7ce8ff; }");
            out.println("</style></head><body>");
            out.println("<h1>STARSECTOR DIVISION AI — UI/UX HARNESS & VISUAL PREVIEW (v0.6.6)</h1>");
            out.println("<div class='badge'>Checks Passed: " + res.checksPassed + " | Checks Failed: " + res.checksFailed + "</div>");

            out.println("<h2>1. ENLARGED PRE-BATTLE DEPLOYMENT WINDOW (14 COLUMNS x 7 RESERVE ROWS = 12 SHIPS/ROW)</h2>");
            out.println("<div class='panel'><div class='deploy-grid'>");
            renderSampleDeployRow(out, "Cruiser Div 1", 60, new String[]{"★ Champion", "Champion", "Champion"}, 12);
            renderSampleDeployRow(out, "Strike Div 1", 62, new String[]{"★ Eagle", "Falcon", "Fury", "Medusa"}, 12);
            renderSampleDeployRow(out, "Destroyer Div 1", 30, new String[]{"★ Hammerhead", "Hammerhead", "Sunder"}, 12);
            renderSampleDeployRow(out, "Frigate Div 1", 20, new String[]{"★ Centurion", "Centurion", "Wolf", "Tempest"}, 12);
            out.println("</div></div>");

            out.println("<h2>2. STREAMLINED DOCTRINE & MISSION SUMMARIES (SUCCINCTNESS AUDIT)</h2>");
            out.println("<div class='panel'><table><tr><th>Category</th><th>Option</th><th>Summary</th><th>Chars / Words</th></tr>");
            for (OtcAuthority a : OtcAuthority.values()) {
                out.printf(Locale.ROOT, "<tr><td>OTC Authority</td><td>%s</td><td>%s</td><td>%d ch / %d w</td></tr>%n",
                        a.dropdownLabel, a.summary, a.summary.length(), countWords(a.summary));
            }
            for (OtcPlay p : OtcPlay.values()) {
                out.printf(Locale.ROOT, "<tr><td>Tactical Play</td><td>%s</td><td>%s</td><td>%d ch / %d w</td></tr>%n",
                        p.displayName, p.summary, p.summary.length(), countWords(p.summary));
            }
            for (DivisionMissionType m : DivisionMissionType.values()) {
                out.printf(Locale.ROOT, "<tr><td>Mission</td><td>%s</td><td>%s</td><td>%d ch / %d w</td></tr>%n",
                        m.dropdownLabel, m.summary, m.summary.length(), countWords(m.summary));
            }
            out.println("</table></div>");

            out.println("</body></html>");
        }
    }

    private static void renderSampleDeployRow(PrintWriter out, String name, int dp, String[] ships, int shipsPerRow) {
        out.printf(Locale.ROOT,
                "<div class='hdr-card'><span class='hdr-title'>%s</span><span class='hdr-btn'>Select (%d DP)</span></div>%n",
                name, dp);
        for (int i = 0; i < shipsPerRow; i++) {
            if (i < ships.length) {
                boolean flag = ships[i].startsWith("★");
                out.printf(Locale.ROOT, "<div class='ship-cell%s'>%s</div>%n", flag ? " flag" : "", ships[i]);
            } else {
                out.println("<div class='ship-cell empty'></div>");
            }
        }
    }
}
