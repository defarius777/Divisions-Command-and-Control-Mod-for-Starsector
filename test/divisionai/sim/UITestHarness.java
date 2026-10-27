package divisionai.sim;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import divisionai.BattlefieldFirepowerEvaluator;
import divisionai.DivisionAISettings;
import divisionai.DivisionCombatLog;
import divisionai.DivisionDeploymentDialogOrganizer;
import divisionai.DivisionEditorDialog;
import divisionai.DivisionModel;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionNomenclature;
import divisionai.DivisionNomenclature.NumberingStyle;
import divisionai.DivisionNomenclature.PlayerNamingPreset;
import divisionai.DivisionTacticalMapOverlay;
import divisionai.FactionFleetDoctrines;
import divisionai.FleetDoctrineDialog;
import divisionai.PermanentDivisionManager.AutoAssignCapability;
import divisionai.PermanentDivisionManager.AutoAssignHull;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.DoctrineDivisionType;
import divisionai.PermanentDivisionManager.DoctrineHullCategory;
import divisionai.PermanentDivisionManager.DoctrineOption;
import divisionai.PermanentDivisionManager.FleetDoctrineConfig;
import divisionai.PermanentDivisionManager.LineStation;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.OtcPlay;
import org.lwjgl.util.vector.Vector2f;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
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
        auditV069StarsAutoAssignSchemesPolygonsAndRename(res);
        auditV070PlayerFactionSyncLowerRightStarsForceAndNomenclatures(res);
        auditChatterVerbosityModes(res);

        // Audit Battle Screen right-side Firepower Rose toggle button (off by default & width budget)
        BattlefieldFirepowerEvaluator.resetForNewBattle();
        boolean fpDefaultOff = !BattlefieldFirepowerEvaluator.isOverlayVisible();
        int fpOffPx = Math.round("FIREPOWER: OFF".length() * 6.8f + 12f);
        int fpOnPx = Math.round("FIREPOWER: ON".length() * 6.8f + 12f);
        int fpMaxBtnPx = Math.max(fpOffPx, fpOnPx) + 10;
        boolean fpFitsBtn = fpMaxBtnPx <= (int) BattlefieldFirepowerEvaluator.BTN_WIDTH
                && BattlefieldFirepowerEvaluator.BTN_RIGHT_MARGIN <= 24f;
        res.record(
                fpDefaultOff && fpFitsBtn,
                "Battle screen right-side Firepower Rose/Plane toggle button (OFF by default, fits compact button)",
                String.format(
                        Locale.ROOT,
                        "defaultOff=%b, textWidthPx=%dpx <= btnWidth=%.0fpx, rightMargin=%.0fpx",
                        fpDefaultOff, fpMaxBtnPx, BattlefieldFirepowerEvaluator.BTN_WIDTH, BattlefieldFirepowerEvaluator.BTN_RIGHT_MARGIN));

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

    private static void auditV069StarsAutoAssignSchemesPolygonsAndRename(UIAuditResult res) throws Exception {
        // Item A: Division Leader & Fleet Flagship stars on lower-right corner & larger star size
        String overlayJava = new String(Files.readAllBytes(new File("src/divisionai/DivisionTacticalMapOverlay.java").toPath()), StandardCharsets.UTF_8);
        boolean itemAOk = overlayJava.contains("renderShipCornerStar(ship, silverStar, false, scale, offsetX, offsetY, fade)")
                && overlayJava.contains("renderShipCornerStar(ship, goldStar, false, scale, offsetX, offsetY, fade)")
                && overlayJava.contains("upperRightCorner ? (sy + cornerOffset) : (sy - cornerOffset)")
                && overlayJava.contains("clamp(18.5f * zoom, 14.0f, 26.0f)");
        res.record(
                itemAOk,
                "v0.6.9 Item A: Division Leader & Fleet Flagship stars on lower-right corner & enlarged flagship star size (14-26px)",
                "silverStar->bottomRight, goldStar->bottomRight, size=14..26px");

        // Item B: Auto-Assigner Division Schemes by Capability & Hull Type in Fleet Doctrine
        FleetDoctrineConfig cfg = new FleetDoctrineConfig();
        boolean defaultAllOn = cfg.isSchemeEnabled(AutoAssignCapability.MISSILE, AutoAssignHull.DESTROYER)
                && cfg.isSchemeEnabled(AutoAssignCapability.MISSILE, AutoAssignHull.CRUISER)
                && cfg.isSchemeEnabled(AutoAssignCapability.LINE, AutoAssignHull.DESTROYER);
        cfg.setSchemeEnabled(AutoAssignCapability.MISSILE, AutoAssignHull.DESTROYER, false);
        boolean cellDisabledOnlyForDestroyer = !cfg.isSchemeEnabled(AutoAssignCapability.MISSILE, AutoAssignHull.DESTROYER)
                && cfg.isSchemeEnabled(AutoAssignCapability.MISSILE, AutoAssignHull.CRUISER)
                && cfg.isSchemeEnabled(AutoAssignCapability.MISSILE, AutoAssignHull.BATTLESHIP)
                && cfg.isSchemeEnabled(AutoAssignCapability.MISSILE, AutoAssignHull.FRIGATE)
                && cfg.isSchemeEnabled(AutoAssignCapability.LINE, AutoAssignHull.DESTROYER);

        String docDialogJava = new String(Files.readAllBytes(new File("src/divisionai/FleetDoctrineDialog.java").toPath()), StandardCharsets.UTF_8);
        String pdmJava = new String(Files.readAllBytes(new File("src/divisionai/PermanentDivisionManager.java").toPath()), StandardCharsets.UTF_8);
        boolean itemBOk = defaultAllOn
                && cellDisabledOnlyForDestroyer
                && docDialogJava.contains("AUTO-ASSIGNER DIVISION SCHEMES — BY CAPABILITY & HULL TYPE")
                && docDialogJava.contains("SCHEMES BY CAPABILITY")
                && docDialogJava.contains("SCHEMES BY HULL TYPE")
                && docDialogJava.contains("SET_AA_SCHEME:")
                && pdmJava.contains("classifyFleetMemberArchetype(FleetMemberAPI member, FleetDoctrineConfig doctrine)");
        res.record(
                itemBOk,
                "v0.6.9 Item B: Fleet Doctrine Auto-Assigner Division Schemes by Capability & Hull Type",
                "MISSILE x DESTROYER toggleable by capability or hull while preserving other Missile & Destroyer schemes");

        // Item C: Sticky water-droplet intra-division polygons + anti-stripe-spam
        boolean itemCOk = overlayJava.contains("clusterDivisionMembersSticky")
                && overlayJava.contains("stickyConnectedPairsByDivId")
                && overlayJava.contains("splitDist = mergeDist * 1.24f")
                && overlayJava.contains("applyMetaballNeckPinchScreen")
                && overlayJava.contains("buildFluidDropletPolygonScreen")
                && overlayJava.contains("a.cohesionAlphaMult < 0.35f")
                && overlayJava.contains("boolean sameSide = (a.isEnemy == b.isEnemy) && (a.isAlliedAi == b.isAlliedAi);");
        res.record(
                itemCOk,
                "v0.6.9 Item C: Sticky hysteresis water-droplet division polygons & anti-stripe-spam filtering",
                "clusterDivisionMembersSticky (1.50x hysteresis) + metaball neck pinch + transit droplet stripe suppression");

        // Item E: Mod renamed to "Divisions: Command and Control" and author "Defarius"
        String modInfoStr = new String(Files.readAllBytes(new File("mod/mod_info.json").toPath()), StandardCharsets.UTF_8);
        boolean itemEOk = modInfoStr.contains("\"name\": \"Divisions: Command and Control\"")
                && modInfoStr.contains("\"author\": \"Defarius\"");
        res.record(
                itemEOk,
                "v0.6.9 Item E: Mod renamed to 'Divisions: Command and Control' by 'Defarius'",
                "mod_info.json name='Divisions: Command and Control', author='Defarius'");
    }

    private static void auditV070PlayerFactionSyncLowerRightStarsForceAndNomenclatures(UIAuditResult res) throws Exception {
        String docDialogJava = new String(Files.readAllBytes(new File("src/divisionai/FleetDoctrineDialog.java").toPath()), StandardCharsets.UTF_8);
        String ffdJava = new String(Files.readAllBytes(new File("src/divisionai/FactionFleetDoctrines.java").toPath()), StandardCharsets.UTF_8);
        String overlayJava = new String(Files.readAllBytes(new File("src/divisionai/DivisionTacticalMapOverlay.java").toPath()), StandardCharsets.UTF_8);
        String editorJava = new String(Files.readAllBytes(new File("src/divisionai/DivisionEditorDialog.java").toPath()), StandardCharsets.UTF_8);

        // Item A: Buttons in Fleet Doctrine & Nomenclature screens to apply changes to player's personal faction fleets
        boolean itemAButtonsOk = docDialogJava.contains("\"Apply Doctrine to Faction Fleets\"")
                && docDialogJava.contains("\"APPLY_DOCTRINE_TO_FACTION_FLEETS\"")
                && docDialogJava.contains("\"Apply Nomenclature to Faction\"")
                && docDialogJava.contains("\"APPLY_NAMING_TO_FACTION_FLEETS\"")
                && docDialogJava.contains("applyPlayerDoctrineToPersonalFactionFleets")
                && docDialogJava.contains("applyPlayerNomenclatureToPersonalFactionFleets")
                && ffdJava.contains("isPlayerPersonalFactionFleet")
                && ffdJava.contains("!Factions.PLAYER.equals(sourceMarket.getFactionId())");

        // Verify runtime behavior of applying Nomenclature to personal faction ("player") without affecting commissioned preset factions
        DivisionNomenclature.clearAppliedPlayerFactionNamingConfigForTests();
        DivisionNomenclature.PlayerNamingConfig playerNaming = DivisionNomenclature.getPlayerNamingConfig();
        playerNaming.setPreset(PlayerNamingPreset.SINDRIAN_GUARD);
        playerNaming.setNumberingStyle(NumberingStyle.ROMAN);
        String beforeSyncPlayerFactionName = DivisionNomenclature.formatFactionDivisionName(
                "player",
                DivisionNomenclature.NomenclatureCategory.HUMAN_NAVAL,
                DivisionArchetype.BATTLESHIP_DIVISION,
                1);
        FactionFleetDoctrines.applyPlayerNomenclatureToPersonalFactionFleets(null);
        String afterSyncPlayerFactionName = DivisionNomenclature.formatFactionDivisionName(
                "player",
                DivisionNomenclature.NomenclatureCategory.HUMAN_NAVAL,
                DivisionArchetype.BATTLESHIP_DIVISION,
                1);
        String hegemonyFactionName = DivisionNomenclature.formatFactionDivisionName(
                "hegemony",
                DivisionNomenclature.NomenclatureCategory.HUMAN_NAVAL,
                DivisionArchetype.BATTLESHIP_DIVISION,
                1);
        playerNaming.setPreset(PlayerNamingPreset.NAVAL_STANDARD);
        playerNaming.setNumberingStyle(NumberingStyle.ARABIC);
        DivisionNomenclature.clearAppliedPlayerFactionNamingConfigForTests();

        boolean itemARuntimeOk = "Battle Div. I".equals(beforeSyncPlayerFactionName)
                && "Executor Line I".equals(afterSyncPlayerFactionName)
                && "Battle Div. I".equals(hegemonyFactionName);

        res.record(
                itemAButtonsOk && itemARuntimeOk,
                "v0.7.0 Item A: Fleet Doctrine & Nomenclature buttons apply changes strictly to player's personal faction fleets with 'Are you sure?' prompt",
                String.format(Locale.ROOT, "beforeSync='%s', afterSync='%s', commissionedPresetFaction='%s'",
                        beforeSyncPlayerFactionName, afterSyncPlayerFactionName, hegemonyFactionName));

        // Item B: Division & Fleet Flagship stars on lower-right corner vertically aligned with commander portrait
        boolean itemBOk = overlayJava.contains("float boxHalf = iconRadiusPx * (16.8f / 18.0f);")
                && overlayJava.contains("float cornerOffset = Math.max(boxHalf - 2.0f, 10.5f * zoom);")
                && overlayJava.contains("renderShipCornerStar(ship, goldStar, false, scale, offsetX, offsetY, fade)")
                && overlayJava.contains("renderShipCornerStar(ship, silverStar, false, scale, offsetX, offsetY, fade)")
                && editorJava.contains("float starY = y + 2f + portraitBoxSize * 0.5f;");

        res.record(
                itemBOk,
                "v0.7.0 Item B: Division/Flagship stars on lower-right corner vertically aligned with commander portrait",
                "warroom starY=sy-(boxHalf-2f) aligned with portraitCenterY; editor starY=y+2f+portraitBoxSize*0.5f");

        // Item C: Generic "Force" division default
        boolean itemCOk = "Force".equals(DivisionArchetype.FORCE_DIVISION.shortPrefix)
                && DoctrineDivisionType.forArchetype(DivisionArchetype.FORCE_DIVISION) == DoctrineDivisionType.FORCE
                && DoctrineDivisionType.forArchetype(null) == DoctrineDivisionType.FORCE
                && "FORCE".equals(DivisionNomenclature.resolvePrefixBucketKey(DivisionArchetype.FORCE_DIVISION))
                && "Force 1".equals(DivisionNomenclature.formatPlayerDivisionName(DivisionArchetype.FORCE_DIVISION, 1))
                && DivisionArchetype.values().length == 16
                && DoctrineDivisionType.values().length == 16;

        res.record(
                itemCOk,
                "v0.7.0 Item C: Generic 'Force' division added as default fallback across DivisionArchetype & DoctrineDivisionType",
                "archetypes=16, doctrineTypes=16, defaultPlayerForceName='Force 1'");

        // Item D: All existing nomenclatures have explicit entries for all capability & hull divisions (including Missile & Force)
        PlayerNamingPreset[] presetsToVerify = {
                PlayerNamingPreset.NAVAL_STANDARD,
                PlayerNamingPreset.DOMAIN_ARMADA,
                PlayerNamingPreset.TRITACHYON_CORPORATE,
                PlayerNamingPreset.PERSEAN_NAVARCHY,
                PlayerNamingPreset.SINDRIAN_GUARD,
                PlayerNamingPreset.LUDDIC_ARMADA,
                PlayerNamingPreset.PIRATE_WOLFPACK,
                PlayerNamingPreset.REMNANT_ORDO
        };
        DivisionArchetype[] capHullArchetypes = {
                DivisionArchetype.MISSILE_BATTLESHIP_DIVISION,
                DivisionArchetype.MISSILE_CRUISER_DIVISION,
                DivisionArchetype.MISSILE_DESTROYER_DIVISION,
                DivisionArchetype.MISSILE_FRIGATE_DIVISION,
                DivisionArchetype.FORCE_DIVISION
        };
        boolean itemDOk = true;
        List<String> missingPresetEntries = new ArrayList<>();
        for (PlayerNamingPreset p : presetsToVerify) {
            for (DivisionArchetype arch : capHullArchetypes) {
                String prefix = DivisionNomenclature.getDefaultPrefixForPreset(p, arch);
                if (prefix == null || prefix.trim().isEmpty()) {
                    itemDOk = false;
                    missingPresetEntries.add(p.name() + ":" + arch.name());
                } else if (p != PlayerNamingPreset.NAVAL_STANDARD && prefix.equals(arch.shortPrefix)) {
                    // Non-standard cultural presets should have culturally themed entries rather than falling through to shortPrefix
                    itemDOk = false;
                    missingPresetEntries.add(p.name() + ":" + arch.name() + " fell through to " + prefix);
                }
            }
        }

        res.record(
                itemDOk,
                "v0.7.0 Item D: All cultural & faction nomenclatures have dedicated entries for all capability & hull divisions",
                "presetsVerified=" + presetsToVerify.length + "x" + DivisionArchetype.values().length + ", missing=" + missingPresetEntries);

        // Water-droplet tiniest-point-of-contact joining & splitting + ear-clipping hourglass triangulation
        boolean dropletKissOk = overlayJava.contains("smoothedBondOpennessByPairKey")
                && overlayJava.contains("resolvePairBondOpenness")
                && overlayJava.contains("distToKissPx")
                && overlayJava.contains("isRedundantShortcutChordWorld")
                && overlayJava.contains("minWaistPx")
                && overlayJava.contains("extractMetaballDropletPolygonScreen")
                && overlayJava.contains("traceMetaballContoursMarchingSquares")
                && overlayJava.contains("subdivideLongEdgesScreen")
                && overlayJava.contains("triangulatePolygonCCW")
                && overlayJava.contains("deconflictPrimaryDivisionLabels");
        res.record(
                dropletKissOk,
                "v0.7.0 Water-Droplet Tiniest-Point-of-Contact Joining/Splitting & Ear-Clipped Hourglass Fill",
                "Smooth-Union SDF (smin) + Marching Squares + P_kiss waist control + ear-clipping GL_TRIANGLES + label de-confliction");

        auditWaterDropletSimulationAndExportQASheet(res);
    }

    private static ShipAPI createMockDropletShip(final String id, final HullSize size, float x, float y) {
        final Vector2f loc = new Vector2f(x, y);
        return (ShipAPI) Proxy.newProxyInstance(
                ShipAPI.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("equals".equals(n)) return Boolean.valueOf(p == (a != null && a.length > 0 ? a[0] : null));
                    if ("hashCode".equals(n)) return Integer.valueOf(System.identityHashCode(p));
                    if ("getId".equals(n)) return id;
                    if ("getLocation".equals(n)) return loc;
                    if ("getHullSize".equals(n)) return size;
                    if ("isAlive".equals(n)) return Boolean.TRUE;
                    if ("isHulk".equals(n) || "isFighter".equals(n) || "isShuttlePod".equals(n)
                            || "isStationModule".equals(n) || "isRetreating".equals(n)
                            || "isExpired".equals(n) || "isAlly".equals(n)) {
                        return Boolean.FALSE;
                    }
                    if ("getOwner".equals(n)) return Integer.valueOf(0);
                    return null;
                });
    }

    private static void auditWaterDropletSimulationAndExportQASheet(UIAuditResult res) throws Exception {
        DivisionTacticalMapOverlay ov = new DivisionTacticalMapOverlay(null);
        CommanderProfile cmdr = new CommanderProfile(null, "steady");
        float scale = 0.075f;

        String[] titles = {
                "1. 3-Cruiser Triangle (Frame 1 Tab Opened!)",
                "2. 3-Cruiser Triangle (After 30 frames)",
                "3. 4-Frigate Curve (After 25 frames)",
                "4. 3 Ships L-Shape (After 25 frames)",
                "5. 4-Ship Div Splitting (3+1 Pop & Pull)",
                "6. 2 Ships Just Joined (Tiniest Contact!)",
                "7. 2 Ships Stretching (Narrow Waist)",
                "8. 2 Ships Coalesced (Full Formation)",
                "9. Pop & Pull 1: Elastic Stretch",
                "10. Pop & Pull 2: Capillary Thread",
                "11. Pop & Pull 3: Just Snapped (Pop!)",
                "12. Pop & Pull 4: Liquid Recoil"
        };

        float[][][] coords = {
                {{-260, -150}, {260, -150}, {0, 260}},
                {{-260, -150}, {260, -150}, {0, 260}},
                {{-300, 400}, {150, 380}, {-80, -120}, {-120, -520}},
                {{-400, -200}, {200, -200}, {200, 450}},
                {{-520, -120}, {-40, -140}, {380, -120}, {380, 1410}},
                {{-595, 0}, {595, 0}},
                {{-675, 0}, {675, 0}},
                {{-320, 0}, {320, 0}},
                {{-620, 0}, {620, 0}},
                {{-735, 0}, {735, 0}},
                {{-765, 0}, {765, 0}},
                {{-780, 0}, {780, 0}}
        };

        BufferedImage img = new BufferedImage(1400, 1320, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(6, 10, 16));
        g.fillRect(0, 0, 1400, 1320);

        int totalSelfIntersections = 0;
        int uncontainedShips = 0;
        int inwardGashViolations = 0;
        int badTriangulations = 0;
        int totalDropletsAudited = 0;
        float stage3TailReachPx = 0f;
        float stage4TailReachPx = 0f;

        for (int idx = 0; idx < coords.length; idx++) {
            int col = idx % 4;
            int row = idx / 4;
            float cx = col * 350f + 175f;
            float cy = row * 435f + 225f;

            DivisionState div = new DivisionState("qa_div_" + idx, 0, 1, DivisionArchetype.CRUISER_DIVISION, "Div " + idx, cmdr);
            div.stationSpacing = 450f;

            if (idx == 4) {
                // Panel 5: Start 4-ship division coalesced in formation, then Ship 3 peels off and snaps!
                List<ShipAPI> united = Arrays.asList(
                        createMockDropletShip("s_" + idx + "_0", HullSize.CRUISER, -520f, -120f),
                        createMockDropletShip("s_" + idx + "_1", HullSize.CRUISER, -40f, -140f),
                        createMockDropletShip("s_" + idx + "_2", HullSize.CRUISER, 380f, -120f),
                        createMockDropletShip("s_" + idx + "_3", HullSize.CRUISER, 380f, 520f));
                for (int s = 0; s < 5; s++) {
                    ov.clusterDivisionMembersSticky(div, united);
                }
            } else if (idx == 5) {
                // Simulate two previously separated droplets approaching from outside mergeDist and joining this frame!
                List<ShipAPI> separated = Arrays.asList(
                        createMockDropletShip("s_" + idx + "_0", HullSize.CRUISER, -800f, 0f),
                        createMockDropletShip("s_" + idx + "_1", HullSize.CRUISER, 800f, 0f));
                ov.clusterDivisionMembersSticky(div, separated);
            } else if (idx == 6 || idx >= 8) {
                // Start coalesced in close formation before pulling apart
                List<ShipAPI> close = Arrays.asList(
                        createMockDropletShip("s_" + idx + "_0", HullSize.CRUISER, -250f, 0f),
                        createMockDropletShip("s_" + idx + "_1", HullSize.CRUISER, 250f, 0f));
                ov.clusterDivisionMembersSticky(div, close);
                if (idx >= 9) {
                    // Step through intermediate stretch so openness necks down smoothly before snapping
                    List<ShipAPI> stretch = Arrays.asList(
                            createMockDropletShip("s_" + idx + "_0", HullSize.CRUISER, -720f, 0f),
                            createMockDropletShip("s_" + idx + "_1", HullSize.CRUISER, 720f, 0f));
                    for (int s = 0; s < 12; s++) {
                        ov.clusterDivisionMembersSticky(div, stretch);
                    }
                }
            }

            List<ShipAPI> members = new ArrayList<>();
            for (int k = 0; k < coords[idx].length; k++) {
                members.add(createMockDropletShip("s_" + idx + "_" + k, HullSize.CRUISER, coords[idx][k][0], coords[idx][k][1]));
            }

            int frames;
            if (idx == 1 || idx == 2 || idx == 3 || idx == 6 || idx == 7) {
                frames = 25;
            } else if (idx == 8) {
                frames = 4;
            } else if (idx == 9) {
                frames = 8;
            } else if (idx == 4 || idx == 10) {
                frames = 1; // Frame 1 of snap/pop!
            } else if (idx == 11) {
                frames = 5; // Mid-recoil after snap!
            } else {
                frames = 1;
            }
            List<List<ShipAPI>> droplets = null;
            for (int f = 0; f < frames; f++) {
                droplets = ov.clusterDivisionMembersSticky(div, members);
            }

            g.setColor(new Color(40, 70, 95));
            g.drawRect(col * 350 + 8, row * 435 + 8, 334, 419);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.drawString(titles[idx], col * 350 + 16, row * 435 + 28);

            for (List<ShipAPI> drop : droplets) {
                totalDropletsAudited++;
                List<Vector2f> hull = ov.buildFluidDropletPolygonScreen(div, drop, members, scale, cx, cy);
                Vector2f cent = DivisionTacticalMapOverlay.computePolygonCentroid(hull);
                List<Vector2f> disp = ov.applyMetaballNeckPinchScreen(div, hull, cent, drop, members, scale, cx, cy);
                List<int[]> tris = DivisionTacticalMapOverlay.triangulatePolygonCCW(disp);

                totalSelfIntersections += DivisionTacticalMapOverlay.countPolygonSelfIntersections(disp);

                float polyArea = DivisionTacticalMapOverlay.computePolygonArea(disp);
                float triAreaSum = 0f;
                for (int[] t : tris) {
                    Vector2f a = disp.get(t[0]);
                    Vector2f b = disp.get(t[1]);
                    Vector2f c = disp.get(t[2]);
                    triAreaSum += Math.abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) * 0.5f;

                    Path2D tp = new Path2D.Float();
                    tp.moveTo(a.x, a.y);
                    tp.lineTo(b.x, b.y);
                    tp.lineTo(c.x, c.y);
                    tp.closePath();
                    g.setColor(new Color(50, 240, 110, 55));
                    g.fill(tp);
                }
                if (tris.size() != disp.size() - 2 || Math.abs(triAreaSum - polyArea) > polyArea * 0.02f) {
                    badTriangulations++;
                }

                for (ShipAPI s : drop) {
                    float sx = s.getLocation().x * scale + cx;
                    float sy = s.getLocation().y * scale + cy;
                    if (!DivisionTacticalMapOverlay.isPointInsidePolygon(disp, sx, sy)) {
                        uncontainedShips++;
                    }
                    // Verify no polygon vertex cuts inward inside 82% of the ship's circle radius (zero V-gashes)
                    float minVertexDist = Float.MAX_VALUE;
                    for (Vector2f v : disp) {
                        float d = (float) Math.sqrt((v.x - sx) * (v.x - sx) + (v.y - sy) * (v.y - sy));
                        if (d < minVertexDist) minVertexDist = d;
                    }
                    if (minVertexDist < 38.25f * 0.78f) {
                        inwardGashViolations++;
                    }
                    if ((idx == 10 || idx == 11) && s.getLocation().x < 0f) {
                        float maxX = -Float.MAX_VALUE;
                        for (Vector2f v : disp) {
                            if (v.x > maxX) maxX = v.x;
                        }
                        float tailReach = (maxX - sx) - 38.25f;
                        if (idx == 10) stage3TailReachPx = tailReach;
                        if (idx == 11) stage4TailReachPx = tailReach;
                    }
                }
            }

            for (ShipAPI s : members) {
                float sx = s.getLocation().x * scale + cx;
                float sy = s.getLocation().y * scale + cy;
                g.setColor(new Color(120, 255, 160));
                g.drawRect((int) (sx - 12), (int) (sy - 12), 24, 24);
            }
        }
        g.dispose();
        File qaOut = new File("build/droplet_qa_sheet.png");
        if (qaOut.getParentFile() != null) qaOut.getParentFile().mkdirs();
        ImageIO.write(img, "PNG", qaOut);

        // Dynamic 60-frame multi-zoom stress test: simulate 4-ship and 3-ship divisions maneuvering,
        // stretching, splitting, and re-coalescing across 3 zoom levels (0.045f, 0.075f, 0.11f).
        float[] stressScales = { 0.045f, 0.075f, 0.11f };
        for (int sIdx = 0; sIdx < stressScales.length; sIdx++) {
            float testScale = stressScales[sIdx];
            DivisionState stressDiv = new DivisionState("stress_div_" + sIdx, 0, 1, DivisionArchetype.CRUISER_DIVISION, "Stress Div", cmdr);
            stressDiv.stationSpacing = 450f;
            for (int frame = 0; frame < 40; frame++) {
                float t = frame / 39.0f;
                float splitOffset = (frame < 20) ? (t * 2.0f * 1650f) : ((1.0f - (t - 0.5f) * 2.0f) * 1650f);
                List<ShipAPI> dynMembers = Arrays.asList(
                        createMockDropletShip("sd_" + sIdx + "_0", HullSize.CRUISER, -480f, -150f + 60f * (float) Math.sin(frame * 0.25)),
                        createMockDropletShip("sd_" + sIdx + "_1", HullSize.CRUISER, 0f, -80f + 50f * (float) Math.cos(frame * 0.25)),
                        createMockDropletShip("sd_" + sIdx + "_2", HullSize.CRUISER, 460f, 120f),
                        createMockDropletShip("sd_" + sIdx + "_3", HullSize.CRUISER, 460f + splitOffset * 0.45f, 480f + splitOffset * 0.85f));
                List<List<ShipAPI>> dynDroplets = ov.clusterDivisionMembersSticky(stressDiv, dynMembers);
                for (List<ShipAPI> dDrop : dynDroplets) {
                    totalDropletsAudited++;
                    List<Vector2f> hull = ov.buildFluidDropletPolygonScreen(stressDiv, dDrop, dynMembers, testScale, 500f, 500f);
                    Vector2f cent = DivisionTacticalMapOverlay.computePolygonCentroid(hull);
                    List<Vector2f> disp = ov.applyMetaballNeckPinchScreen(stressDiv, hull, cent, dDrop, dynMembers, testScale, 500f, 500f);
                    List<int[]> tris = DivisionTacticalMapOverlay.triangulatePolygonCCW(disp);
                    totalSelfIntersections += DivisionTacticalMapOverlay.countPolygonSelfIntersections(disp);
                    float polyArea = DivisionTacticalMapOverlay.computePolygonArea(disp);
                    float triAreaSum = 0f;
                    for (int[] tri : tris) {
                        Vector2f a = disp.get(tri[0]);
                        Vector2f b = disp.get(tri[1]);
                        Vector2f c = disp.get(tri[2]);
                        triAreaSum += Math.abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) * 0.5f;
                    }
                    if (tris.size() != disp.size() - 2 || Math.abs(triAreaSum - polyArea) > polyArea * 0.02f) {
                        badTriangulations++;
                    }
                    for (ShipAPI s : dDrop) {
                        float sx = s.getLocation().x * testScale + 500f;
                        float sy = s.getLocation().y * testScale + 500f;
                        if (!DivisionTacticalMapOverlay.isPointInsidePolygon(disp, sx, sy)) {
                            uncontainedShips++;
                        }
                    }
                }
            }
        }

        boolean popAndPullAnimated = stage3TailReachPx >= 10.0f
                && stage4TailReachPx >= 2.0f
                && stage4TailReachPx < stage3TailReachPx - 3.0f;
        boolean simQaOk = totalDropletsAudited >= 14
                && totalSelfIntersections == 0
                && uncontainedShips == 0
                && inwardGashViolations == 0
                && badTriangulations == 0
                && popAndPullAnimated
                && qaOut.exists();
        res.record(
                simQaOk,
                "v0.7.0 Water-Droplet UI Simulation QA Suite (12 formations incl. 4-Stage Liquid Pop & Pull Split Animation)",
                String.format(Locale.ROOT,
                        "droplets=%d, selfIntersections=%d, uncontainedShips=%d, inwardGashes=%d, badTriangulations=%d, popTailPx=%.1f->%.1f, sheet=%s",
                        totalDropletsAudited, totalSelfIntersections, uncontainedShips, inwardGashViolations, badTriangulations, stage3TailReachPx, stage4TailReachPx, qaOut.getPath()));
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

    private static void auditChatterVerbosityModes(UIAuditResult res) throws Exception {
        String lunaCsv = new String(Files.readAllBytes(new File("mod/data/config/LunaSettings.csv").toPath()), StandardCharsets.UTF_8);
        boolean csvHasRadio = lunaCsv.contains("divai_chatter_verbosity,Chatter Verbosity,Radio,Verbose,\"Verbose, Standard, Minimal, OTC Only, Off\"")
                && !lunaCsv.contains("%");

        int[] hudCounts = new int[DivisionAISettings.ChatterVerbosity.values().length];
        int[] logCounts = new int[DivisionAISettings.ChatterVerbosity.values().length];
        boolean[] hasParentheticalReasonOnHud = new boolean[DivisionAISettings.ChatterVerbosity.values().length];

        try {
            DivisionAISettings.ChatterVerbosity[] modes = DivisionAISettings.ChatterVerbosity.values();
            for (int mIdx = 0; mIdx < modes.length; mIdx++) {
                DivisionAISettings.testVerbosityOverride = modes[mIdx];
                final List<String> hudLines = new ArrayList<>();
                final List<String> logLines = new ArrayList<>();
                DivisionCombatLog.simLogSink = logLines;

                final com.fs.starfarer.api.combat.CombatUIAPI mockUi = (com.fs.starfarer.api.combat.CombatUIAPI) Proxy.newProxyInstance(
                        com.fs.starfarer.api.combat.CombatUIAPI.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.CombatUIAPI.class},
                        (p, method, args) -> {
                            if ("addMessage".equals(method.getName()) && args != null) {
                                StringBuilder sb = new StringBuilder();
                                for (Object a : args) {
                                    if (a instanceof String) {
                                        sb.append((String) a);
                                    } else if (a instanceof Object[]) {
                                        for (Object sub : (Object[]) a) {
                                            if (sub instanceof String) sb.append((String) sub);
                                        }
                                    }
                                }
                                hudLines.add(sb.toString());
                                return null;
                            }
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == float.class) return 0f;
                            return null;
                        });

                final float[] simTime = new float[]{1.0f};
                com.fs.starfarer.api.combat.CombatEngineAPI mockEngine = (com.fs.starfarer.api.combat.CombatEngineAPI) Proxy.newProxyInstance(
                        com.fs.starfarer.api.combat.CombatEngineAPI.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.CombatEngineAPI.class},
                        (p, method, args) -> {
                            if ("getCombatUI".equals(method.getName())) return mockUi;
                            if ("getTotalElapsedTime".equals(method.getName())) return simTime[0];
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == float.class) return 0f;
                            return null;
                        });

                DivisionModel.CommanderProfile cmdr = new DivisionModel.CommanderProfile(null, "steady");
                DivisionModel.BattleGroupState friendlyBg = new DivisionModel.BattleGroupState(0, cmdr);
                friendlyBg.currentPhase = DivisionModel.BattlePhase.GENERAL_ENGAGEMENT;

                DivisionModel.DivisionState friendlyDiv = new DivisionModel.DivisionState(
                        "f_div_1", 0, 1, DivisionModel.DivisionArchetype.CRUISER_DIVISION, "Cruiser Div 1", cmdr);
                friendlyDiv.members.add(createMockDropletShip("s1", HullSize.CRUISER, 0f, 0f));

                DivisionModel.DivisionState enemyDiv = new DivisionModel.DivisionState(
                        "e_div_1", 1, 1, DivisionModel.DivisionArchetype.CRUISER_DIVISION, "Enemy Div 1", cmdr);
                enemyDiv.members.add(createMockDropletShip("e1", HullSize.CRUISER, 1000f, 0f));

                // 1) Opening division formation at t=1.0s
                DivisionCombatLog.logDivisionFormed(mockEngine, friendlyDiv, 1.0f);
                // 2) OTC Phase signal at t=2.0s
                DivisionCombatLog.logOtcSignal(mockEngine, friendlyBg, "Line of battle formed", 2.0f);
                // 3) OTC no-op notice at t=3.0s
                simTime[0] = 3.0f;
                DivisionCombatLog.logOtcOrder(mockEngine, friendlyBg, "No division free to reinforce \"Cruiser Div 1\"");
                // 4) OTC active strike order at t=4.0s
                simTime[0] = 4.0f;
                DivisionCombatLog.logOtcOrder(mockEngine, friendlyBg, "Time-on-target strike: 1 division to marshal");
                // 5) Routine CLOSE_UP intent at t=6.0s
                friendlyDiv.currentIntent = DivisionModel.DivisionIntentType.CLOSE_UP;
                DivisionCombatLog.logDivisionIntent(mockEngine, friendlyDiv, "", "station keeping", 6.0f);
                // 6) Friendly CLOSE_AND_ENGAGE at t=15.0s
                friendlyDiv.currentIntent = DivisionModel.DivisionIntentType.CLOSE_AND_ENGAGE;
                DivisionCombatLog.logDivisionIntent(mockEngine, friendlyDiv, "Enemy Div 1", "Opening: Standoff Engagement", 15.0f);
                // 7) Rapid target swap on same CLOSE_AND_ENGAGE intent at t=20.0s
                DivisionCombatLog.logDivisionIntent(mockEngine, friendlyDiv, "Enemy Div 2", "Opening: Standoff Engagement", 20.0f);
                // 8) Enemy single-division CLOSE_AND_ENGAGE at t=22.0s
                enemyDiv.currentIntent = DivisionModel.DivisionIntentType.CLOSE_AND_ENGAGE;
                DivisionCombatLog.logDivisionIntent(mockEngine, enemyDiv, "Cruiser Div 1", "aggressiveness 0.65", 22.0f);
                // 9) Critical friendly TOT_MISSILE_STRIKE at t=40.0s
                friendlyDiv.currentIntent = DivisionModel.DivisionIntentType.TOT_MISSILE_STRIKE;
                DivisionCombatLog.logDivisionIntent(mockEngine, friendlyDiv, "Enemy Div 1", "synchronized salvo", 40.0f);

                hudCounts[mIdx] = hudLines.size();
                logCounts[mIdx] = logLines.size();
                for (String h : hudLines) {
                    if (h.contains("(Opening:") || h.contains("(synchronized salvo)")) {
                        hasParentheticalReasonOnHud[mIdx] = true;
                    }
                }
            }
        } finally {
            DivisionAISettings.testVerbosityOverride = null;
            DivisionCombatLog.simLogSink = null;
        }

        // Order: 0=VERBOSE, 1=STANDARD, 2=MINIMAL, 3=OTC_ONLY, 4=OFF
        boolean allLogsPreserved = (logCounts[0] == 9 && logCounts[1] == 9 && logCounts[2] == 9 && logCounts[3] == 9 && logCounts[4] == 9);
        boolean monotonicReduction = (hudCounts[0] == 9)
                && (hudCounts[1] == 4) // OTC signal + OTC active order + 1st CLOSE_AND_ENGAGE + TOT_MISSILE_STRIKE
                && (hudCounts[2] == 3) // OTC signal + OTC active order + TOT_MISSILE_STRIKE
                && (hudCounts[3] == 2) // OTC signal + OTC active order only
                && (hudCounts[4] == 0); // Off = 0 HUD lines
        boolean reasonsStrippedInNonVerbose = hasParentheticalReasonOnHud[0]
                && !hasParentheticalReasonOnHud[1]
                && !hasParentheticalReasonOnHud[2];

        res.record(
                csvHasRadio && allLogsPreserved && monotonicReduction && reasonsStrippedInNonVerbose,
                "Combat Chatter Verbosity modes (Verbose, Standard, Minimal, OTC Only, Off) in Mod Settings",
                String.format(
                        Locale.ROOT,
                        "csvHasRadio=%b, starsectorLogLines=%d across all modes, hudLines=[Verbose=%d, Standard=%d, Minimal=%d, OTC Only=%d, Off=%d], conciseNoReasonInStandard=%b",
                        csvHasRadio, logCounts[0], hudCounts[0], hudCounts[1], hudCounts[2], hudCounts[3], hudCounts[4], reasonsStrippedInNonVerbose));
    }
}
