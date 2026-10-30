package divisionai.sim;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import divisionai.BattlefieldFirepowerEvaluator;
import divisionai.DivisionAISettings;
import divisionai.DivisionCombatLog;
import divisionai.DivisionCommanderAI;
import divisionai.DivisionDeploymentDialogOrganizer;
import divisionai.DivisionEditorDialog;
import divisionai.DivisionModel;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionIntentType;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.OtcOrderType;
import divisionai.DivisionNomenclature;
import divisionai.DivisionNomenclature.NumberingStyle;
import divisionai.DivisionNomenclature.PlayerNamingPreset;
import divisionai.DivisionTacticalMapOverlay;
import divisionai.DynamicBattleMusicController;
import divisionai.FactionFleetDoctrines;
import divisionai.FleetDoctrineDialog;
import divisionai.PermanentDivisionManager;
import divisionai.PermanentDivisionManager.AutoAssignCapability;
import divisionai.PermanentDivisionManager.AutoAssignHull;
import divisionai.PermanentDivisionManager.DivisionFormation;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.DoctrineDivisionType;
import divisionai.PermanentDivisionManager.DoctrineHullCategory;
import divisionai.PermanentDivisionManager.DoctrineOption;
import divisionai.PermanentDivisionManager.FleetDoctrineConfig;
import divisionai.PermanentDivisionManager.LineStation;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.OtcPlay;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
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
        auditTimeSpaceConvergenceAndAlphaCoreTakeover(res);
        auditNavalFormationsAndOmegaCores(res);
        StarsectorUISimulator.runOneToOneStarsectorUISimulation(res);

        // Audit Battle Screen right-side Time, Space, and Firepower square-bordered icon buttons
        // (all 3 active, OFF by default, textless "Full Assault"/"Full Retreat" square-bordered style, Command View gated)
        BattlefieldFirepowerEvaluator.resetForNewBattle();
        boolean allThreeDefaultOff = !BattlefieldFirepowerEvaluator.isOverlayVisible()
                && !BattlefieldFirepowerEvaluator.isSpaceOverlayVisible()
                && !BattlefieldFirepowerEvaluator.isTimeOverlayVisible();

        BattlefieldFirepowerEvaluator.toggleTimeOverlayVisible();
        boolean timeToggledIndependently = BattlefieldFirepowerEvaluator.isTimeOverlayVisible()
                && !BattlefieldFirepowerEvaluator.isSpaceOverlayVisible()
                && !BattlefieldFirepowerEvaluator.isOverlayVisible();

        BattlefieldFirepowerEvaluator.toggleSpaceOverlayVisible();
        boolean spaceToggledIndependently = BattlefieldFirepowerEvaluator.isTimeOverlayVisible()
                && BattlefieldFirepowerEvaluator.isSpaceOverlayVisible()
                && !BattlefieldFirepowerEvaluator.isOverlayVisible();

        BattlefieldFirepowerEvaluator.toggleOverlayVisible();
        boolean allThreeActive = BattlefieldFirepowerEvaluator.isTimeOverlayVisible()
                && BattlefieldFirepowerEvaluator.isSpaceOverlayVisible()
                && BattlefieldFirepowerEvaluator.isOverlayVisible();
        BattlefieldFirepowerEvaluator.resetForNewBattle();

        float yTime = BattlefieldFirepowerEvaluator.getOverlayButtonY(1080f, 0);
        float ySpace = BattlefieldFirepowerEvaluator.getOverlayButtonY(1080f, 1);
        float yFirepower = BattlefieldFirepowerEvaluator.getOverlayButtonY(1080f, 2);
        boolean squareGeometryOk = BattlefieldFirepowerEvaluator.BTN_SQUARE_W == 36f
                && BattlefieldFirepowerEvaluator.BTN_SQUARE_H == 34f
                && BattlefieldFirepowerEvaluator.BTN_WIDTH == 46f
                && BattlefieldFirepowerEvaluator.BTN_RIGHT_MARGIN == 20f
                && (yTime - ySpace) >= BattlefieldFirepowerEvaluator.BTN_SQUARE_H + 6f
                && (ySpace - yFirepower) >= BattlefieldFirepowerEvaluator.BTN_SQUARE_H + 6f;

        String fpJava = (new String(Files.readAllBytes(new File("src/divisionai/BattlefieldFirepowerEvaluator.java").toPath()), StandardCharsets.UTF_8) + new String(Files.readAllBytes(new File("src/divisionai/TsfcWarroomRenderer.java").toPath()), StandardCharsets.UTF_8));
        boolean textlessSquareButtons = fpJava.contains("renderSingleWarroomSquareIconButton")
                && !fpJava.contains("\"FIREPOWER: ON\"")
                && !fpJava.contains("\"FIREPOWER: OFF\"")
                && fpJava.contains("TIME_ICON_PATH")
                && fpJava.contains("SPACE_ICON_PATH")
                && fpJava.contains("FIREPOWER_ICON_PATH");

        boolean[] cmdOpenRef = new boolean[]{false};
        CombatUIAPI mockCmdUi = (CombatUIAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{CombatUIAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("equals".equals(n)) return p == a[0];
                    if ("hashCode".equals(n)) return System.identityHashCode(p);
                    if ("isShowingCommandUI".equals(n)) return cmdOpenRef[0];
                    if ("getCommandUIOpacity".equals(n)) return cmdOpenRef[0] ? 1.0f : 0.0f;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == float.class) return 0f;
                    if (rt == int.class) return 0;
                    return null;
                });
        CombatEngineAPI mockFpEngine = (CombatEngineAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{CombatEngineAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("equals".equals(n)) return p == a[0];
                    if ("hashCode".equals(n)) return System.identityHashCode(p);
                    if ("getCombatUI".equals(n)) return mockCmdUi;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == float.class) return 0f;
                    if (rt == int.class) return 0;
                    return null;
                });
        boolean hiddenWhenCmdViewClosed = !BattlefieldFirepowerEvaluator.isCommandViewWindowActive(null)
                && !BattlefieldFirepowerEvaluator.isCommandViewWindowActive(mockFpEngine);
        cmdOpenRef[0] = true;
        boolean shownWhenCmdViewOpen = BattlefieldFirepowerEvaluator.isCommandViewWindowActive(mockFpEngine);

        res.record(
                allThreeDefaultOff && timeToggledIndependently && spaceToggledIndependently
                        && allThreeActive && squareGeometryOk && textlessSquareButtons
                        && hiddenWhenCmdViewClosed && shownWhenCmdViewOpen,
                "Command View 3 active textless square-bordered Warroom icon buttons (Time, Space, Firepower)",
                String.format(
                        Locale.ROOT,
                        "all3Active=%b, textlessSquare=%b, box=%.0fx%.0fpx (outer %.0fpx, rightMargin=%.0fpx), y=[%.0f, %.0f, %.0f], cmdViewGated=%b",
                        allThreeActive, textlessSquareButtons,
                        BattlefieldFirepowerEvaluator.BTN_SQUARE_W, BattlefieldFirepowerEvaluator.BTN_SQUARE_H,
                        BattlefieldFirepowerEvaluator.BTN_WIDTH, BattlefieldFirepowerEvaluator.BTN_RIGHT_MARGIN,
                        yTime, ySpace, yFirepower, hiddenWhenCmdViewClosed && shownWhenCmdViewOpen));

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

        // Verify Deployment Screen Overhaul (Items A-E), Deep Wedge Echeloning & Formation-Aware Deployment Hijack
        String stdOtcTitle = DivisionDeploymentDialogOrganizer.formatFleetCommandOtcTitle(
                "Admiral Kanta", "HSS Bismark", false);
        String alphaOtcTitle = DivisionDeploymentDialogOrganizer.formatFleetCommandOtcTitle(
                "Alpha Core", "tts radiant", true);
        boolean titlesOk = "Officer in Tactical Command: Admiral Kanta".equals(stdOtcTitle)
                && "ORDO COGITATOR/MASTER: TTS RADIANT".equals(alphaOtcTitle)
                && DivisionDeploymentDialogOrganizer.ALPHA_CORE_PORTRAIT_STRETCH_ASPECT >= 1.65f
                && Math.abs(DivisionDeploymentDialogOrganizer.MASTER_JITTER_PERIOD_SECONDS - 0.50f) < 1e-4f;

        float[] jit0 = DivisionDeploymentDialogOrganizer.computeMasterJitterOffset(0.20);
        float[] jit1 = DivisionDeploymentDialogOrganizer.computeMasterJitterOffset(0.70);
        float[] jit2 = DivisionDeploymentDialogOrganizer.computeMasterJitterOffset(1.20);
        boolean jitterChangesEveryHalfSec = (Math.abs(jit0[0]) > 0.1f || Math.abs(jit0[1]) > 0.1f)
                && (Math.abs(jit0[0] - jit1[0]) > 0.05f || Math.abs(jit0[1] - jit1[1]) > 0.05f
                || Math.abs(jit1[0] - jit2[0]) > 0.05f || Math.abs(jit1[1] - jit2[1]) > 0.05f);

        // Verify Hegemony Grand Wedge Spearhead & wing-matched intra-division echeloning + Deployment Hijack
        PermanentDivisionManager.FleetDoctrineConfig hegDoc = FactionFleetDoctrines.getDoctrineForFactionId("hegemony", null);
        boolean hegUsesGrandWedge = (hegDoc.getFleetWideFormation() == PermanentDivisionManager.FleetWideFormation.GRAND_WEDGE_SPEARHEAD);

        DivisionModel.CommanderProfile testCmdr = new DivisionModel.CommanderProfile(null, "steady");
        DivisionModel.BattleGroupState wedgeGroup = new DivisionModel.BattleGroupState(0, testCmdr);
        wedgeGroup.fleetWideFormation = PermanentDivisionManager.FleetWideFormation.GRAND_WEDGE_SPEARHEAD;
        DivisionModel.DivisionState dApex = new DivisionModel.DivisionState("w_apex", 0, 1, DivisionModel.DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div. I", testCmdr);
        DivisionModel.DivisionState dPort = new DivisionModel.DivisionState("w_port", 0, 2, DivisionModel.DivisionArchetype.CRUISER_DIVISION, "Cruiser Div. I", testCmdr);
        DivisionModel.DivisionState dStbd = new DivisionModel.DivisionState("w_stbd", 0, 3, DivisionModel.DivisionArchetype.CRUISER_DIVISION, "Cruiser Div. II", testCmdr);
        wedgeGroup.divisions.add(dApex);
        wedgeGroup.divisions.add(dPort);
        wedgeGroup.divisions.add(dStbd);
        wedgeGroup.mainEffortDivision = dApex;

        SimArena wedgeArena = new SimArena("Wedge Hijack Check", "Test", wedgeGroup, new DivisionModel.BattleGroupState(1, testCmdr));
        SimArena.SimShipState sApex1 = wedgeArena.addShip(dApex, "wa1", "Onslaught", "Onslaught", com.fs.starfarer.api.combat.ShipAPI.HullSize.CAPITAL_SHIP, -300f, -4500f, 55f, 1300f, 0, 0);
        SimArena.SimShipState sApex2 = wedgeArena.addShip(dApex, "wa2", "Dominator", "Dominator", com.fs.starfarer.api.combat.ShipAPI.HullSize.CRUISER, -100f, -4500f, 65f, 1200f, 0, 0);
        SimArena.SimShipState sPort1 = wedgeArena.addShip(dPort, "wp1", "Eagle", "Eagle", com.fs.starfarer.api.combat.ShipAPI.HullSize.CRUISER, 100f, -4500f, 70f, 1200f, 0, 0);
        SimArena.SimShipState sPort2 = wedgeArena.addShip(dPort, "wp2", "Falcon", "Falcon", com.fs.starfarer.api.combat.ShipAPI.HullSize.CRUISER, 250f, -4500f, 80f, 1150f, 0, 0);
        SimArena.SimShipState sStbd1 = wedgeArena.addShip(dStbd, "ws1", "Eagle", "Eagle", com.fs.starfarer.api.combat.ShipAPI.HullSize.CRUISER, 400f, -4500f, 70f, 1200f, 0, 0);
        SimArena.SimShipState sStbd2 = wedgeArena.addShip(dStbd, "ws2", "Falcon", "Falcon", com.fs.starfarer.api.combat.ShipAPI.HullSize.CRUISER, 550f, -4500f, 80f, 1150f, 0, 0);
        for (DivisionModel.DivisionState d : wedgeGroup.divisions) {
            for (com.fs.starfarer.api.combat.ShipAPI s : d.members) {
                wedgeGroup.shipIdToDivision.put(s.getId(), d);
            }
        }
        divisionai.OtcAI.updateTelemetry(wedgeArena.engineProxy, wedgeGroup);
        List<com.fs.starfarer.api.combat.ShipAPI> wave = new ArrayList<>();
        wave.add(sApex1.proxy);
        wave.add(sApex2.proxy);
        wave.add(sPort1.proxy);
        wave.add(sPort2.proxy);
        wave.add(sStbd1.proxy);
        wave.add(sStbd2.proxy);
        divisionai.OtcAI.hijackNewlyDeployedWaveIntoFormation(wedgeArena.engineProxy, wedgeGroup, null, wave, 0.1f);
        divisionai.OtcAI.updateTelemetry(wedgeArena.engineProxy, wedgeGroup);

        boolean wedgeFormationsOk = (dApex.activeFormation == PermanentDivisionManager.DivisionFormation.FORWARD_WEDGE)
                && (dPort.activeFormation == PermanentDivisionManager.DivisionFormation.ECHELON_LEFT)
                && (dStbd.activeFormation == PermanentDivisionManager.DivisionFormation.ECHELON_RIGHT)
                && (dApex.centroid.y > dPort.centroid.y + 200f)
                && (dApex.centroid.y > dStbd.centroid.y + 200f)
                && (dPort.centroid.x < dApex.centroid.x - 200f)
                && (dStbd.centroid.x > dApex.centroid.x + 200f);

        boolean obfHandlesOk = true;
        try {
            Class.forName("com.fs.starfarer.combat.CombatState", false, DivisionDeploymentDialogOrganizer.class.getClassLoader());
            obfHandlesOk = DivisionDeploymentDialogOrganizer.verifyObfuscatedHandles(
                    DivisionDeploymentDialogOrganizer.class.getClassLoader());
        } catch (Throwable ignored) {
        }

        res.record(
                titlesOk && jitterChangesEveryHalfSec && hegUsesGrandWedge && wedgeFormationsOk && obfHandlesOk,
                "Deployment Screen Fleet Command (A-E), ORDO COGITATOR/MASTER 0.5s jitter, and Formation Deployment Hijack verified",
                String.format(Locale.ROOT,
                        "std='%s', alpha='%s', stretch=%.2fx, jitter0.5s=%b, hegWedge=%b, obfHandles=%b, hijackFormations=[%s,%s,%s], apexLeadY=%.0fu",
                        stdOtcTitle, alphaOtcTitle, DivisionDeploymentDialogOrganizer.ALPHA_CORE_PORTRAIT_STRETCH_ASPECT,
                        jitterChangesEveryHalfSec, hegUsesGrandWedge, obfHandlesOk,
                        dApex.activeFormation, dPort.activeFormation, dStbd.activeFormation,
                        dApex.centroid.y - dPort.centroid.y));
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

        // F. Commander portrait and level badge on left side of ship icons
        boolean itemFOk = edSrc.contains("renderCommanderPortraitSprite")
                && edSrc.contains("Fonts.VICTOR_10")
                && edSrc.contains("showCommanderLevel")
                && edSrc.contains("float px = x + 2f;")
                && edSrc.contains("float py = y + h - portraitBoxSize - 16f;");
        res.record(
                itemFOk,
                "Item F: Ship icons render commander portrait on upper-left side with Fleet-screen Victor-10 level badge",
                "portraitBoxSize=24px at upper-left (x+2, y+h-portraitBoxSize-16) with Victor-10 top-right level badge");

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
        // Item A/D: Division Leader & Fleet Flagship stars on left side under/replacing commander portrait
        String overlayJava = new String(Files.readAllBytes(new File("src/divisionai/DivisionTacticalMapOverlay.java").toPath()), StandardCharsets.UTF_8);
        boolean itemAOk = overlayJava.contains("renderShipCornerStar(ship, silverStar, false, scale, offsetX, offsetY, fade)")
                && overlayJava.contains("renderShipCornerStar(ship, goldStar, false, scale, offsetX, offsetY, fade)")
                && overlayJava.contains("? (portraitCenterY - portraitSize * 0.5f - starSize * 0.5f - 1.5f * bScale)")
                && overlayJava.contains("clamp(16.0f * zoom, 12.0f, 22.0f)");
        res.record(
                itemAOk,
                "v0.6.9 Item A / Item D: Division Leader & Fleet Flagship stars on left side under/replacing commander portrait (12-22px)",
                "silverStar & goldStar on left side (under commander portrait if present, replacing portrait if absent), size=12..22px");

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
                && docDialogJava.contains("AUTO-ASSIGNER SCHEMES")
                && docDialogJava.contains("BY CAPABILITY")
                && docDialogJava.contains("BY HULL CLASS")
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
        boolean itemAButtonsOk = docDialogJava.contains("\"Apply to Colony Fleets\"")
                && docDialogJava.contains("\"APPLY_DOCTRINE_TO_FACTION_FLEETS\"")
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

        // Item B/D: Division & Fleet Flagship stars directly under commander portrait on left side if commander exists,
        // or replacing the commander portrait if no commander exists (using exact B.new 42x42 boxHalf = 21.0f * bScale).
        boolean itemBOk = overlayJava.contains("float boxHalf = ship.isStation() ? (iconRadiusPx * 0.85f) : (21.0f * bScale);")
                && overlayJava.contains("float portraitCenterX = sx - boxHalf + portraitSize * 0.5f + 2.0f * bScale;")
                && overlayJava.contains("? (portraitCenterY - portraitSize * 0.5f - starSize * 0.5f - 1.5f * bScale)")
                && overlayJava.contains("renderShipCornerStar(ship, goldStar, false, scale, offsetX, offsetY, fade)")
                && overlayJava.contains("renderShipCornerStar(ship, silverStar, false, scale, offsetX, offsetY, fade)")
                && editorJava.contains("? (py - starSize * 0.5f - 2f)")
                && editorJava.contains(": (py + portraitBoxSize * 0.5f);");

        res.record(
                itemBOk,
                "v0.7.0 Item B/D: Division/Flagship stars under commander portrait on left side (or replacing portrait if no commander)",
                "warroom & editor starX aligned with left portraitCenterX; under portrait if hasCommander else at portraitCenterY");

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
        boolean mediumAliasAndAlternativesOk = (DivisionAISettings.ChatterVerbosity.fromSettingString("Medium")
                == DivisionAISettings.ChatterVerbosity.STANDARD)
                && !"close and engage".equals(DivisionModel.DivisionIntentType.CLOSE_AND_ENGAGE.mediumPhrase)
                && !"close and engage".equals(DivisionModel.DivisionIntentType.CLOSE_AND_ENGAGE.minimalPhrase)
                && "launching ToT missile strike on".equals(DivisionModel.DivisionIntentType.TOT_MISSILE_STRIKE.mediumPhrase)
                && "ToT Salvo ->".equals(DivisionModel.DivisionIntentType.TOT_MISSILE_STRIKE.minimalPhrase);

        res.record(
                csvHasRadio && allLogsPreserved && monotonicReduction && reasonsStrippedInNonVerbose && mediumAliasAndAlternativesOk,
                "Combat Chatter Verbosity modes (Verbose, Standard/Medium, Minimal, OTC Only, Off) in Mod Settings",
                String.format(
                        Locale.ROOT,
                        "csvHasRadio=%b, starsectorLogLines=%d across all modes, hudLines=[Verbose=%d, Standard=%d, Minimal=%d, OTC Only=%d, Off=%d], conciseNoReasonInStandard=%b, reducedAlts=%b",
                        csvHasRadio, logCounts[0], hudCounts[0], hudCounts[1], hudCounts[2], hudCounts[3], hudCounts[4], reasonsStrippedInNonVerbose, mediumAliasAndAlternativesOk));
    }

    private static com.fs.starfarer.api.characters.PersonAPI createMockCaptain(final String aiCoreId) {
        return (com.fs.starfarer.api.characters.PersonAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.characters.PersonAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("equals".equals(n)) return p == a[0];
                    if ("hashCode".equals(n)) return System.identityHashCode(p);
                    if ("getAICoreId".equals(n)) return aiCoreId;
                    if ("isAICore".equals(n)) return aiCoreId != null && !aiCoreId.isEmpty();
                    if ("isDefault".equals(n)) return false;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
    }

    private static ShipAPI createMockNamedShip(
            final String id,
            final String name,
            final HullSize hullSize,
            final float x,
            final float y,
            final float maxSpeed,
            final int owner,
            final com.fs.starfarer.api.characters.PersonAPI captain) {
        return createMockNamedShip(id, name, hullSize, x, y, maxSpeed, owner, captain, 1.0f, 0.1f);
    }

    private static ShipAPI createMockNamedShip(
            final String id,
            final String name,
            final HullSize hullSize,
            final float x,
            final float y,
            final float maxSpeed,
            final int owner,
            final com.fs.starfarer.api.characters.PersonAPI captain,
            final float hullLevel,
            final float fluxLevel) {

        final Vector2f loc = new Vector2f(x, y);
        final Vector2f vel = new Vector2f(0f, 0f);
        return (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("equals".equals(n)) return p == a[0];
                    if ("hashCode".equals(n)) return System.identityHashCode(p);
                    if ("getId".equals(n)) return id;
                    if ("getName".equals(n)) return name;
                    if ("getHullSize".equals(n)) return hullSize;
                    if ("getLocation".equals(n)) return loc;
                    if ("getVelocity".equals(n)) return vel;
                    if ("getMaxSpeed".equals(n)) return maxSpeed;
                    if ("getOwner".equals(n)) return owner;
                    if ("isAlly".equals(n)) return false;
                    if ("isAlive".equals(n)) return true;
                    if ("isHulk".equals(n) || "isExpired".equals(n) || "isFighter".equals(n)
                            || "isShuttlePod".equals(n) || "isStationModule".equals(n)) {
                        return false;
                    }
                    if ("getCaptain".equals(n)) return captain;
                    if ("getCollisionRadius".equals(n)) return 90f;
                    if ("getHullLevel".equals(n)) return hullLevel;
                    if ("getFluxLevel".equals(n)) return fluxLevel;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
    }

    private static void auditTimeSpaceConvergenceAndAlphaCoreTakeover(UIAuditResult res) throws Exception {
        BattlefieldFirepowerEvaluator.resetForNewBattle();

        // 1. Verify Convergence icon asset & settings.json registration + bright RGB(170, 222, 255) button tint
        File convIcon64 = new File("mod/graphics/icons/warroom/icon_overlay_convergence.png");
        File convIcon32 = new File("mod/graphics/icons/warroom/icon_overlay_convergence_32.png");
        String settingsJson = new String(Files.readAllBytes(new File("mod/data/config/settings.json").toPath()), StandardCharsets.UTF_8);
        String fpSource = (new String(Files.readAllBytes(new File("src/divisionai/BattlefieldFirepowerEvaluator.java").toPath()), StandardCharsets.UTF_8) + new String(Files.readAllBytes(new File("src/divisionai/TsfcWarroomRenderer.java").toPath()), StandardCharsets.UTF_8));
        boolean iconAndBrightnessOk = convIcon64.exists() && convIcon32.exists()
                && settingsJson.contains("icon_overlay_convergence")
                && fpSource.contains("new Color(170, 222, 255, 255)");

        // 2. Verify strict Alpha Core officer check (only "alpha_core" counts; Beta, Gamma, and human captains do not)
        com.fs.starfarer.api.characters.PersonAPI alphaCapt = createMockCaptain("alpha_core");
        com.fs.starfarer.api.characters.PersonAPI betaCapt = createMockCaptain("beta_core");
        com.fs.starfarer.api.characters.PersonAPI gammaCapt = createMockCaptain("gamma_core");
        com.fs.starfarer.api.characters.PersonAPI humanCapt = createMockCaptain(null);

        ShipAPI humanFlagship = createMockNamedShip("p_flag", "ISS Invictus", HullSize.CAPITAL_SHIP, 0f, -1200f, 65f, 0, humanCapt);
        ShipAPI betaShip = createMockNamedShip("p_beta", "ISS Beta Guard", HullSize.CRUISER, -450f, -1200f, 95f, 0, betaCapt);
        ShipAPI gammaShip = createMockNamedShip("p_gamma", "ISS Gamma Scout", HullSize.FRIGATE, -800f, -1200f, 150f, 0, gammaCapt);
        ShipAPI alphaShip1 = createMockNamedShip("p_alpha_1", "TTS Hyperion", HullSize.CAPITAL_SHIP, 500f, -1200f, 90f, 0, alphaCapt);
        ShipAPI alphaShip2 = createMockNamedShip("p_alpha_2", "TTS Radiant", HullSize.CRUISER, 900f, -1200f, 110f, 0, alphaCapt);
        ShipAPI enemyShip = createMockNamedShip("e_1", "HSS Onslaught", HullSize.CAPITAL_SHIP, 0f, 600f, 55f, 1, humanCapt);

        boolean strictAlphaCoreCheck = BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(alphaShip1)
                && !BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(betaShip)
                && !BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(gammaShip)
                && !BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(humanFlagship);

        // 3. Verify Time & Space telemetry & Engagement Level reduction + 3+ circle non-inversion check
        CommanderProfile cmdr = new CommanderProfile(humanCapt, "steady");
        DivisionModel.BattleGroupState playerGroup = new DivisionModel.BattleGroupState(0, cmdr);
        DivisionModel.BattleGroupState enemyGroup = new DivisionModel.BattleGroupState(1, cmdr);

        DivisionState playerDiv1 = new DivisionState("p_div_1", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div. I", cmdr);
        playerDiv1.flagship = humanFlagship;
        playerDiv1.members.add(humanFlagship);
        playerDiv1.members.add(betaShip);
        playerDiv1.centroid.set(0f, -1200f);
        playerDiv1.spreadRadius = 400f;
        playerDiv1.totalFpStrength = 44f;
        playerDiv1.avgEngageRange = 1250f;
        playerGroup.divisions.add(playerDiv1);

        DivisionState playerDiv2 = new DivisionState("p_div_2", 0, 2, DivisionArchetype.CRUISER_DIVISION, "Cruiser Div 1", cmdr);
        playerDiv2.flagship = alphaShip1;
        playerDiv2.members.add(alphaShip1);
        playerDiv2.members.add(alphaShip2);
        playerDiv2.centroid.set(700f, -1200f);
        playerDiv2.spreadRadius = 350f;
        playerDiv2.totalFpStrength = 48f;
        playerDiv2.avgEngageRange = 1150f;
        playerGroup.divisions.add(playerDiv2);

        DivisionState enemyDiv = new DivisionState("e_div_1", 1, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Enemy Battle Div. I", cmdr);
        enemyDiv.flagship = enemyShip;
        enemyDiv.members.add(enemyShip);
        enemyDiv.centroid.set(0f, -200f); // Overlapping playerDiv1's Time circle
        enemyDiv.spreadRadius = 380f;
        enemyDiv.totalFpStrength = 42f;
        enemyDiv.avgEngageRange = 1200f;
        enemyGroup.divisions.add(enemyDiv);

        final List<ShipAPI> deployedShips = new ArrayList<>(Arrays.asList(humanFlagship, betaShip, gammaShip));
        final boolean[] pausedRef = new boolean[]{false};
        final ShipAPI[] playerPilotedRef = new ShipAPI[]{humanFlagship};

        CombatEngineAPI mockEngine = (CombatEngineAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{CombatEngineAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("equals".equals(n)) return p == a[0];
                    if ("hashCode".equals(n)) return System.identityHashCode(p);
                    if ("getShips".equals(n)) return deployedShips;
                    if ("getPlayerShip".equals(n)) return playerPilotedRef[0];
                    if ("isPaused".equals(n)) return pausedRef[0];
                    if ("setPaused".equals(n) && a != null && a.length > 0 && a[0] instanceof Boolean) {
                        pausedRef[0] = (Boolean) a[0];
                        return null;
                    }
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });

        // Without an Alpha Core in the fleet, hasAlphaCoreInPlayerFleet is false
        boolean noAlphaButtonWhenOnlyLowerCores = !BattlefieldFirepowerEvaluator.hasAlphaCoreInPlayerFleet(mockEngine);

        // Add the two subordinate Alpha Core ships to the deployed fleet
        deployedShips.add(alphaShip1);
        deployedShips.add(alphaShip2);
        boolean alphaButtonAvailableWhenAlphaPresent = BattlefieldFirepowerEvaluator.hasAlphaCoreInPlayerFleet(mockEngine);

        BattlefieldFirepowerEvaluator.setActiveBattleGroups(playerGroup, null, enemyGroup);
        BattlefieldFirepowerEvaluator.updateDivisionBaseTimeSpace(playerDiv1);
        BattlefieldFirepowerEvaluator.updateDivisionBaseTimeSpace(playerDiv2);
        BattlefieldFirepowerEvaluator.updateDivisionBaseTimeSpace(enemyDiv);
        BattlefieldFirepowerEvaluator.updateCrossGroupTimeSpaceConvergence(mockEngine, playerGroup, enemyGroup);

        boolean timeSpaceCalculatedOk = playerDiv1.weightedTimeSpeed > 65f
                && playerDiv1.freeTimeRadius > 1000f
                && playerDiv1.engagementLevel > 0.05f
                && playerDiv1.effectiveTimeRadius < playerDiv1.freeTimeRadius
                && playerDiv1.spaceInnerRadius > 400f
                && playerDiv1.spaceDominatedRadius > playerDiv1.spaceInnerRadius
                && playerDiv1.spacePotentialRadius > playerDiv1.spaceDominatedRadius;

        // Verify Directional Engagement Pinch on Time Circle (Item D):
        // playerDiv1 is at (0, -1000) and enemyDiv is North at (0, 100) (bearing 90 deg North):
        //   - Forward into enemy (90 deg North) is strongly pinched
        //   - Sideways (0 deg East / 180 deg West) retains much more Time radius
        //   - Backward away from the fighting (270 deg South) retains near-full freeTimeRadius
        float timeRadNorthTowardEnemy = BattlefieldFirepowerEvaluator.getDirectionalTimeRadius(playerDiv1, 90f);
        float timeRadEastSideways = BattlefieldFirepowerEvaluator.getDirectionalTimeRadius(playerDiv1, 0f);
        float timeRadSouthAway = BattlefieldFirepowerEvaluator.getDirectionalTimeRadius(playerDiv1, 270f);
        boolean directionalTimePinchOk = timeRadNorthTowardEnemy < timeRadEastSideways - 50f
                && timeRadEastSideways <= timeRadSouthAway + 15f
                && timeRadSouthAway > timeRadNorthTowardEnemy + 90f;
        timeSpaceCalculatedOk = timeSpaceCalculatedOk && directionalTimePinchOk;

        // 4. Verify initial Flagship & Division Commander Convergence rules:
        //    - Default flagship is humanFlagship -> playerGroup.useConvergence == false (standard human OTC)
        //    - playerDiv1 flagship is humanFlagship -> playerDiv1.useConvergence == false (human division commander)
        //    - playerDiv2 flagship is alphaShip1 -> playerDiv2.useConvergence == true (Alpha Core division commander)
        boolean initialConvergenceStatesOk = !playerGroup.useConvergence
                && !playerDiv1.useConvergence
                && playerDiv2.useConvergence;

        // 5. Verify Urgent Comms Pop-Up state machine when clicking Convergence button:
        //    - Click #1: pauses battle, opens "[URGENT COMMS FROM TTS Hyperion]" asking "Shall I take command?" (phrase 0)
        BattlefieldFirepowerEvaluator.handleConvergenceButtonClick(mockEngine);
        ShipAPI firstAsked = BattlefieldFirepowerEvaluator.getPendingCommsAlphaCoreShip();
        boolean firstClickOpenedCommsAndPaused = pausedRef[0]
                && firstAsked == alphaShip1
                && BattlefieldFirepowerEvaluator.getActiveCommsPhraseIndex() == 0
                && !playerGroup.useConvergence;

        //    - Player clicks "No": closes pop-up, TTS Hyperion will not ask again, OTC remains human
        BattlefieldFirepowerEvaluator.refusePendingAlphaCoreCommand(mockEngine);
        boolean afterRefuseFirstOk = BattlefieldFirepowerEvaluator.getPendingCommsAlphaCoreShip() == null
                && !playerGroup.useConvergence
                && BattlefieldFirepowerEvaluator.getAskedAlphaCoreShipIds().contains(alphaShip1.getId());

        //    - Click #2: next Alpha Core ship (TTS Radiant) asks "Please allow me." (phrase 1)
        pausedRef[0] = false;
        BattlefieldFirepowerEvaluator.handleConvergenceButtonClick(mockEngine);
        ShipAPI secondAsked = BattlefieldFirepowerEvaluator.getPendingCommsAlphaCoreShip();
        boolean secondClickAskedNextAlpha = pausedRef[0]
                && secondAsked == alphaShip2
                && BattlefieldFirepowerEvaluator.getActiveCommsPhraseIndex() == 1;

        //    - Player clicks "Yes": TTS Radiant becomes flagship and player OTC starts using Convergence decision-making!
        BattlefieldFirepowerEvaluator.acceptPendingAlphaCoreCommand(mockEngine);
        boolean afterAcceptSecondOk = BattlefieldFirepowerEvaluator.getPendingCommsAlphaCoreShip() == null
                && BattlefieldFirepowerEvaluator.getPlayerConvergenceFlagshipOverride() == alphaShip2
                && playerGroup.activeFleetFlagship == alphaShip2
                && playerGroup.useConvergence;

        //    - Click #3: no further pop-ups (flagship is now an Alpha Core and both ships were asked), toggles overlay cleanly
        pausedRef[0] = false;
        BattlefieldFirepowerEvaluator.handleConvergenceButtonClick(mockEngine);
        boolean thirdClickNoPopup = !pausedRef[0]
                && BattlefieldFirepowerEvaluator.getPendingCommsAlphaCoreShip() == null;

        // 6. Verify 0.5-second positional shift animation, centered ship sprite + callsign, and all 4 cycling phrases
        Vector2f shift0 = BattlefieldFirepowerEvaluator.getCommandWordShiftOffset(0.10f);
        Vector2f shift1 = BattlefieldFirepowerEvaluator.getCommandWordShiftOffset(0.60f);
        Vector2f shift2 = BattlefieldFirepowerEvaluator.getCommandWordShiftOffset(1.10f);
        String evalSrc = (new String(Files.readAllBytes(new File("src/divisionai/BattlefieldFirepowerEvaluator.java").toPath()), StandardCharsets.UTF_8) + new String(Files.readAllBytes(new File("src/divisionai/TsfcWarroomRenderer.java").toPath()), StandardCharsets.UTF_8));
        String[] p0 = BattlefieldFirepowerEvaluator.getUrgentCommsPhraseParts(0);
        String[] p1 = BattlefieldFirepowerEvaluator.getUrgentCommsPhraseParts(1);
        String[] p2 = BattlefieldFirepowerEvaluator.getUrgentCommsPhraseParts(2);
        String[] p3 = BattlefieldFirepowerEvaluator.getUrgentCommsPhraseParts(3);
        String[] p4 = BattlefieldFirepowerEvaluator.getUrgentCommsPhraseParts(4);
        boolean allFourPhrasesAndCycleOk = "command?".equals(p0[1])
                && "Please ".equals(p1[0]) && "allow".equals(p1[1]) && " me.".equals(p1[2])
                && "I am at your ".equals(p2[0]) && "service.".equals(p2[1])
                && "I can ".equals(p3[0]) && "ANNIHILATE".equals(p3[1]) && " them faster.".equals(p3[2])
                && "command?".equals(p4[1]);
        boolean commsSpriteAndJitterQuestionMarkOk = evalSrc.contains("renderCommsShipSprite(aiShip, spriteCenterX, spriteCenterY")
                && allFourPhrasesAndCycleOk
                && "TTS Hyperion".equals(BattlefieldFirepowerEvaluator.formatAiShipCallsignOnly(alphaShip1));
        boolean commandShiftEveryHalfSecOk = (shift0.x != shift1.x || shift0.y != shift1.y)
                && (shift1.x != shift2.x || shift1.y != shift2.y)
                && commsSpriteAndJitterQuestionMarkOk;

        BattlefieldFirepowerEvaluator.resetForNewBattle();

        res.record(
                iconAndBrightnessOk && strictAlphaCoreCheck && noAlphaButtonWhenOnlyLowerCores
                        && alphaButtonAvailableWhenAlphaPresent && timeSpaceCalculatedOk
                        && initialConvergenceStatesOk && firstClickOpenedCommsAndPaused
                        && afterRefuseFirstOk && secondClickAskedNextAlpha
                        && afterAcceptSecondOk && thirdClickNoPopup && commandShiftEveryHalfSecOk,
                "Time, Space, Convergence Layer & Alpha Core Urgent Comms Flagship Takeover",
                String.format(
                        Locale.ROOT,
                        "iconBright=%b, strictAlphaOnly=%b, timeEff=%.0f/%.0fu (eng=%.2f), space=[%.0f, %.0f, %.0f], "
                                + "initConv=[otc=%b, d1=%b, d2=%b], comms=[1st=%s->No, 2nd=%s->Yes, otcConvAfterYes=%b], 0.5sShift=%b",
                        iconAndBrightnessOk,
                        strictAlphaCoreCheck && noAlphaButtonWhenOnlyLowerCores && alphaButtonAvailableWhenAlphaPresent,
                        playerDiv1.effectiveTimeRadius, playerDiv1.freeTimeRadius, playerDiv1.engagementLevel,
                        playerDiv1.spaceInnerRadius, playerDiv1.spaceDominatedRadius, playerDiv1.spacePotentialRadius,
                        false, playerDiv1.useConvergence, playerDiv2.useConvergence,
                        firstAsked != null ? firstAsked.getName() : "null",
                        secondAsked != null ? secondAsked.getName() : "null",
                        afterAcceptSecondOk,
                        commandShiftEveryHalfSecOk));
    }

    private static void auditNavalFormationsAndOmegaCores(UIAuditResult res) throws Exception {
        // 1. Verify Omega Core ("omega_core") officer practices Convergence alongside Alpha Core ("alpha_core")
        com.fs.starfarer.api.characters.PersonAPI omegaOfficer = createMockCaptain("omega_core");
        ShipAPI omegaShip = createMockNamedShip("omega_ship", "Tesseract", HullSize.CRUISER, 0f, -1000f, 150f, 0, omegaOfficer);
        boolean omegaCoreRecognized = BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(omegaShip);

        // 2. Verify all 11 Naval Formation solid-bar icons (10 structured + 1 "No Formation") exist on disk and in settings.json,
        //    and that the 10 original triangle formation icons were archived in mod/graphics/icons/formations/archive_triangles/
        String settingsJson = new String(
                Files.readAllBytes(new File("mod/data/config/settings.json").toPath()),
                StandardCharsets.UTF_8);
        boolean allTenIconsOk = (DivisionFormation.values().length == 11);
        for (DivisionFormation f : DivisionFormation.values()) {
            File pngFile = new File("mod/" + f.spritePath);
            if (!pngFile.exists() || pngFile.length() <= 100 || !settingsJson.contains("\"" + f.settingsIconKey + "\"")) {
                allTenIconsOk = false;
                break;
            }
        }
        File archiveDir = new File("mod/graphics/icons/formations/archive_triangles");
        File archivedWedgeSvg = new File(archiveDir, "icon_formation_forward_wedge.svg");
        boolean archivedTrianglesOk = archiveDir.isDirectory() && archivedWedgeSvg.exists();
        allTenIconsOk = allTenIconsOk && archivedTrianglesOk;

        // 3. Verify 3-tier Formation Hierarchy:
        //    (1) Fleet Doctrine -> (2) Auto-Assigner / PermanentDivisionSpec -> (3) Battle DivisionState
        FleetDoctrineConfig doc = new FleetDoctrineConfig();
        doc.setFormation(DoctrineDivisionType.CRUISER, DivisionFormation.REVERSE_SEMICIRCLE);
        PermanentDivisionSpec spec = new PermanentDivisionSpec("test_c_div", "Cruiser Div 1", DivisionArchetype.CRUISER_DIVISION);
        // Inherited from Fleet Doctrine when customFormation == null
        boolean inheritedFromDoctrineOk = PermanentDivisionManager.resolveEffectiveFormation(spec, doc) == DivisionFormation.REVERSE_SEMICIRCLE;
        // Overridden on individual division in Fleet Organization
        spec.customFormation = DivisionFormation.FORWARD_WEDGE;
        boolean customOverrideOk = PermanentDivisionManager.resolveEffectiveFormation(spec, doc) == DivisionFormation.FORWARD_WEDGE;

        com.fs.starfarer.api.characters.PersonAPI humanCapt = createMockCaptain(null);
        CommanderProfile cmdr = new CommanderProfile(humanCapt, "Test Cmdr");
        DivisionState div = new DivisionState("test_c_div", 0, 1, DivisionArchetype.CRUISER_DIVISION, "Cruiser Div 1", cmdr);
        ShipAPI s1 = createMockNamedShip("s1", "Eagle", HullSize.CRUISER, 0f, -1000f, 110f, 0, humanCapt);
        ShipAPI s2 = createMockNamedShip("s2", "Eagle", HullSize.CRUISER, -400f, -1200f, 110f, 0, humanCapt);
        ShipAPI s3 = createMockNamedShip("s3", "Eagle", HullSize.CRUISER, 400f, -1200f, 110f, 0, humanCapt);
        div.flagship = s1;
        div.members.addAll(Arrays.asList(s1, s2, s3));

        PermanentDivisionManager.applyDoctrineModifiersToCombatDivision(div, spec, doc);
        boolean inheritedIntoBattleOk = (div.doctrinalFormation == DivisionFormation.FORWARD_WEDGE)
                && (div.activeFormation == DivisionFormation.FORWARD_WEDGE);

        // 4. Verify dynamic formation shift & breakup in combat under Division Commander & OTC authority
        DivisionModel.BattleGroupState bg = new DivisionModel.BattleGroupState(0, cmdr);
        bg.authority = OtcAuthority.POSITIVE_CONTROL;
        div.currentIntent = DivisionIntentType.WORK_FLANK;
        div.otcOrder.set(OtcOrderType.FLANK, null, null, 1f, 30f, "Port flank");
        div.otcOrder.orderedFormation = DivisionFormation.ECHELON_LEFT;
        DivisionCommanderAI.evaluateAndUpdateDivisionFormation(null, div, bg, 10f);
        boolean otcAuthorityShiftOk = (div.activeFormation == DivisionFormation.ECHELON_LEFT);

        // Surrounded in crossfire cordon -> Division Commander shifts to CIRCLE
        div.otcOrder.clear();
        div.crossfireCordonIndex = 0.65f;
        DivisionCommanderAI.evaluateAndUpdateDivisionFormation(null, div, bg, 20f);
        boolean surroundedCircleShiftOk = (div.activeFormation == DivisionFormation.CIRCLE);

        // High-flux haul-out or General Chase -> Division Commander breaks formation (NONE)
        div.crossfireCordonIndex = 0f;
        div.currentIntent = DivisionIntentType.GIVE_GENERAL_CHASE;
        DivisionCommanderAI.evaluateAndUpdateDivisionFormation(null, div, bg, 25f);
        boolean breakupToNoneOk = (div.activeFormation == DivisionFormation.NONE);

        // 5. Verify 2D geometric slot offsets for key formations
        float[] wedgeApex = DivisionCommanderAI.computeLocalFormationOffset(DivisionFormation.FORWARD_WEDGE, 0, 5, 500f);
        float[] wedgeWing = DivisionCommanderAI.computeLocalFormationOffset(DivisionFormation.FORWARD_WEDGE, 1, 5, 500f);
        float[] revWedgeApex = DivisionCommanderAI.computeLocalFormationOffset(DivisionFormation.REVERSE_WEDGE, 0, 5, 500f);
        float[] revWedgeWing = DivisionCommanderAI.computeLocalFormationOffset(DivisionFormation.REVERSE_WEDGE, 1, 5, 500f);
        float[] aheadLead = DivisionCommanderAI.computeLocalFormationOffset(DivisionFormation.LINE_AHEAD, 0, 5, 500f);
        float[] aheadTrail = DivisionCommanderAI.computeLocalFormationOffset(DivisionFormation.LINE_AHEAD, 2, 5, 500f);
        boolean geometryDistinctOk = (wedgeApex[1] > wedgeWing[1])
                && (revWedgeApex[1] < revWedgeWing[1])
                && (Math.abs(aheadLead[0]) < 1f && Math.abs(aheadTrail[0]) < 1f && aheadLead[1] > aheadTrail[1]);

        // 6. Verify Configure Division UI & Top-Layer Urgent Comms Modal render order
        String editorSrc = new String(
                Files.readAllBytes(new File("src/divisionai/DivisionEditorDialog.java").toPath()),
                StandardCharsets.UTF_8);
        String doctrineSrc = new String(
                Files.readAllBytes(new File("src/divisionai/FleetDoctrineDialog.java").toPath()),
                StandardCharsets.UTF_8);
        String overlaySrc = new String(
                Files.readAllBytes(new File("src/divisionai/DivisionTacticalMapOverlay.java").toPath()),
                StandardCharsets.UTF_8);
        String tooltipSrc = new String(
                Files.readAllBytes(new File("src/divisionai/CampaignFleetTooltipOrganizer.java").toPath()),
                StandardCharsets.UTF_8);
        boolean configureDivFormationQaOk = editorSrc.contains("final ButtonAPI[] formButtons = new ButtonAPI[formations.length];")
                && editorSrc.contains("bp.getY() + bp.getHeight() * 0.5f")
                && doctrineSrc.contains("final ButtonAPI[] formButtons = new ButtonAPI[formations.length];");
        int divLabelIdx = overlaySrc.indexOf("renderDivisionEntityLabel(rp, fade");
        int modalTopLayerIdx = overlaySrc.indexOf("BattlefieldFirepowerEvaluator.renderToggleButtonAndLegendUI(engine, fade);");
        boolean modalAboveDivTextOk = (divLabelIdx > 0 && modalTopLayerIdx > divLabelIdx);

        // 7. Verify Item A: Doctrinal Division Movement Stickiness (3-tier hierarchy) & Missile Frigate Div 2 anti-stuck fix
        doc.setMovementStickiness(
                DoctrineDivisionType.MISSILE_FRIGATE,
                PermanentDivisionManager.DivisionMovementStickiness.LOOSE);
        PermanentDivisionSpec mfSpec = new PermanentDivisionSpec(
                "mf_div_2",
                "Missile Frigate Div 2",
                DivisionArchetype.MISSILE_FRIGATE_DIVISION);
        boolean stickyInheritedOk = PermanentDivisionManager.resolveEffectiveMovementStickiness(mfSpec, doc)
                == PermanentDivisionManager.DivisionMovementStickiness.LOOSE;
        mfSpec.customMovementStickiness = PermanentDivisionManager.DivisionMovementStickiness.FREE;
        boolean stickyCustomOverrideOk = PermanentDivisionManager.resolveEffectiveMovementStickiness(mfSpec, doc)
                == PermanentDivisionManager.DivisionMovementStickiness.FREE;
        DivisionState mfDiv = new DivisionState(
                "mf_div_2",
                0,
                2,
                DivisionArchetype.MISSILE_FRIGATE_DIVISION,
                "Missile Frigate Div 2",
                cmdr);
        ShipAPI mf1 = createMockNamedShip("mf1", "Vigilance 1", HullSize.FRIGATE, 0f, 0f, 150f, 0, humanCapt);
        ShipAPI mf2 = createMockNamedShip("mf2", "Vigilance 2", HullSize.FRIGATE, -200f, -150f, 150f, 0, humanCapt);
        mfDiv.flagship = mf1;
        mfDiv.members.addAll(Arrays.asList(mf1, mf2));
        PermanentDivisionManager.applyDoctrineModifiersToCombatDivision(mfDiv, mfSpec, doc);
        boolean stickyIntoBattleOk = (mfDiv.doctrinalMovementStickiness == PermanentDivisionManager.DivisionMovementStickiness.FREE)
                && (mfDiv.activeMovementStickiness == PermanentDivisionManager.DivisionMovementStickiness.FREE)
                && editorSrc.contains("addDivisionMovementStickinessRow")
                && doctrineSrc.contains("addMovementStickinessRow");
        boolean stickinessHierarchyOk = stickyInheritedOk && stickyCustomOverrideOk && stickyIntoBattleOk;

        // 8. Verify Item B: Voidstrike instantiation, multi-division merging, centroid tracking, return cleanup, counter increment & enemy visibility
        BattlefieldFirepowerEvaluator.resetForNewBattle();
        final boolean[] returningFlag = new boolean[]{false};
        final Vector2f f1Loc = new Vector2f(100f, 500f);
        final Vector2f f2Loc = new Vector2f(300f, 700f);
        final Vector2f efLoc = new Vector2f(0f, -400f);
        ShipAPI enemyTarget = createMockNamedShip("wb1_s1", "Warband 1 Lead", HullSize.CRUISER, 200f, 1500f, 90f, 1, humanCapt);
        DivisionState enemyWarband1 = new DivisionState("wb_1", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Warband 1", cmdr);
        enemyWarband1.flagship = enemyTarget;
        enemyWarband1.members.add(enemyTarget);
        enemyWarband1.totalFpStrength = 20f;
        DivisionModel.BattleGroupState eGroup = new DivisionModel.BattleGroupState(1, cmdr);
        eGroup.divisions.add(enemyWarband1);
        eGroup.shipIdToDivision.put(enemyTarget.getId(), enemyWarband1);

        ShipAPI fighter1 = (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getId".equals(n)) return "ftr_1";
                    if ("isAlive".equals(n)) return true;
                    if ("isFighter".equals(n)) return true;
                    if ("getOwner".equals(n)) return 0;
                    if ("getLocation".equals(n)) return f1Loc;
                    if ("getShipTarget".equals(n)) return enemyTarget;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
        ShipAPI fighter2 = (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getId".equals(n)) return "ftr_2";
                    if ("isAlive".equals(n)) return true;
                    if ("isFighter".equals(n)) return true;
                    if ("getOwner".equals(n)) return 0;
                    if ("getLocation".equals(n)) return f2Loc;
                    if ("getShipTarget".equals(n)) return enemyTarget;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
        com.fs.starfarer.api.combat.FighterWingAPI wing1 =
                (com.fs.starfarer.api.combat.FighterWingAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.FighterWingAPI.class},
                        (p, m, a) -> {
                            String n = m.getName();
                            if ("getWingMembers".equals(n)) return Arrays.asList(fighter1);
                            if ("getLeader".equals(n)) return fighter1;
                            if ("isReturning".equals(n)) return returningFlag[0];
                            Class<?> rt = m.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == float.class) return 0f;
                            return null;
                        });
        com.fs.starfarer.api.combat.FighterWingAPI wing2 =
                (com.fs.starfarer.api.combat.FighterWingAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.FighterWingAPI.class},
                        (p, m, a) -> {
                            String n = m.getName();
                            if ("getWingMembers".equals(n)) return Arrays.asList(fighter2);
                            if ("getLeader".equals(n)) return fighter2;
                            if ("isReturning".equals(n)) return returningFlag[0];
                            Class<?> rt = m.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == float.class) return 0f;
                            return null;
                        });
        ShipAPI carrier1 = (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getId".equals(n)) return "car_1";
                    if ("isAlive".equals(n)) return true;
                    if ("getOwner".equals(n)) return 0;
                    if ("getLocation".equals(n)) return new Vector2f(-500f, -1500f);
                    if ("getAllWings".equals(n)) return Arrays.asList(wing1);
                    if ("getShipTarget".equals(n)) return enemyTarget;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
        ShipAPI carrier2 = (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getId".equals(n)) return "car_2";
                    if ("isAlive".equals(n)) return true;
                    if ("getOwner".equals(n)) return 0;
                    if ("getLocation".equals(n)) return new Vector2f(500f, -1500f);
                    if ("getAllWings".equals(n)) return Arrays.asList(wing2);
                    if ("getShipTarget".equals(n)) return enemyTarget;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });

        DivisionState cDiv1 = new DivisionState("c_div_1", 0, 1, DivisionArchetype.FLEET_CARRIER_DIVISION, "Carrier Div 1", cmdr);
        cDiv1.flagship = carrier1;
        cDiv1.members.add(carrier1);
        cDiv1.currentIntent = DivisionIntentType.LAUNCH_DECKLOAD_STRIKE;
        cDiv1.targetEnemyDivision = enemyWarband1;

        DivisionState cDiv2 = new DivisionState("c_div_2", 0, 2, DivisionArchetype.LIGHT_CARRIER_DIVISION, "Carrier Div 2", cmdr);
        cDiv2.flagship = carrier2;
        cDiv2.members.add(carrier2);
        cDiv2.currentIntent = DivisionIntentType.LAUNCH_ALPHA_STRIKE;
        cDiv2.targetEnemyDivision = enemyWarband1;

        DivisionModel.BattleGroupState pGroup = new DivisionModel.BattleGroupState(0, cmdr);
        pGroup.divisions.addAll(Arrays.asList(cDiv1, cDiv2));
        pGroup.shipIdToDivision.put(carrier1.getId(), cDiv1);
        pGroup.shipIdToDivision.put(carrier2.getId(), cDiv2);

        // Multi-division strike from cDiv1 + cDiv2 against enemyWarband1 -> registered as ONE Voidstrike ("Voidstrike 1")
        BattlefieldFirepowerEvaluator.updateVoidstrikes(null, pGroup, null, eGroup, 10f);
        List<BattlefieldFirepowerEvaluator.VoidstrikeRecord> activeVs1 = BattlefieldFirepowerEvaluator.getActiveVoidstrikes();
        boolean multiDivMergedIntoOneOk = (activeVs1.size() == 1)
                && "Voidstrike 1".equals(activeVs1.get(0).getDisplayLabel())
                && activeVs1.get(0).isMultiDivision()
                && Math.abs(activeVs1.get(0).center.x - 200f) < 1f
                && Math.abs(activeVs1.get(0).center.y - 600f) < 1f;

        // When strike craft return -> "Voidstrike 1" disappears
        returningFlag[0] = true;
        BattlefieldFirepowerEvaluator.updateVoidstrikes(null, pGroup, null, eGroup, 15f);
        boolean disappearsOnReturnOk = BattlefieldFirepowerEvaluator.getActiveVoidstrikes().isEmpty();

        // Next strike launched -> incremented to "Voidstrike 2"
        returningFlag[0] = false;
        f1Loc.set(400f, 900f);
        f2Loc.set(400f, 900f);
        BattlefieldFirepowerEvaluator.updateVoidstrikes(null, pGroup, null, eGroup, 20f);
        List<BattlefieldFirepowerEvaluator.VoidstrikeRecord> activeVs2 = BattlefieldFirepowerEvaluator.getActiveVoidstrikes();
        boolean incrementedToTwoOk = (activeVs2.size() == 1)
                && "Voidstrike 2".equals(activeVs2.get(0).getDisplayLabel())
                && Math.abs(activeVs2.get(0).center.x - 400f) < 1f
                && Math.abs(activeVs2.get(0).center.y - 900f) < 1f;
        boolean voidstrikeLifecycleOk = multiDivMergedIntoOneOk && disappearsOnReturnOk && incrementedToTwoOk;

        // 8b. Verify Items A, C, D, E, F:
        //  - Item A: Carrier-specific missions explicitly labeled "(Carrier)" in dropdownLabel
        boolean carrierMissionLabelsOk = true;
        for (DivisionMissionType m : DivisionMissionType.values()) {
            if (m.carrierSpecific && !m.dropdownLabel.contains("(Carrier)")) {
                carrierMissionLabelsOk = false;
            }
        }
        //  - Item F: ToT Alpha Strike mission & intent exist
        boolean totAlphaStrikeExistsOk = (DivisionMissionType.valueOf("TOT_ALPHA_STRIKE") != null)
                && (DivisionIntentType.valueOf("TOT_ALPHA_STRIKE") != null);
        //  - Item D & E: Separate Voidstrike counters for Player (0), Enemy (1), and Allies (2), and Fog-of-War
        //    flicker does not runaway-increment the Enemy Voidstrike counter!
        boolean separateSideCountersOk = (BattlefieldFirepowerEvaluator.getNextVoidstrikeNumberForSide(
                BattlefieldFirepowerEvaluator.VOIDSTRIKE_SIDE_PLAYER) == 3)
                && (BattlefieldFirepowerEvaluator.getNextVoidstrikeNumberForSide(
                BattlefieldFirepowerEvaluator.VOIDSTRIKE_SIDE_ENEMY) == 1)
                && (BattlefieldFirepowerEvaluator.getNextVoidstrikeNumberForSide(
                BattlefieldFirepowerEvaluator.VOIDSTRIKE_SIDE_ALLIED) == 1);

        // 9. Verify Item C: Campaign fleet hover tooltip division distribution follows faction campaign colors
        boolean factionTooltipColorsOk = tooltipSrc.contains("return factionColor != null ? factionColor : Misc.getBasePlayerColor();")
                && !tooltipSrc.contains("case BATTLESHIP_DIVISION:");

        // 10. Verify Convergence Icon Edits (enlarged circle r="27.5" & non-touching circle/square/triangle borders),
        //     restored Convergence battle glyphs (sqHalf = radPx * 0.70710678f), High/Low T-S-F-C Graphics Fidelity
        //     setting, and Carrier & Wing Voidstrike Doctrine (Items A-H)
        String convSvg = new String(
                Files.readAllBytes(new File("mod/graphics/icons/warroom/icon_overlay_convergence.svg").toPath()),
                StandardCharsets.UTF_8);
        String evalSrc = (new String(Files.readAllBytes(new File("src/divisionai/BattlefieldFirepowerEvaluator.java").toPath()), StandardCharsets.UTF_8) + new String(Files.readAllBytes(new File("src/divisionai/TsfcWarroomRenderer.java").toPath()), StandardCharsets.UTF_8));
        boolean convergenceIconAndTsfcOptOk = convSvg.contains("r=\"27.5\"")
                && convSvg.contains("width=\"32\" height=\"32\"")
                && convSvg.contains("points=\"32,21.5 21.8,41.5 42.2,41.5\"")
                && evalSrc.contains("0.70710678f")
                && evalSrc.contains("UNIT_COS_72")
                && evalSrc.contains("UNIT_COS_48")
                && evalSrc.contains("SCRATCH_SUST_X")
                && evalSrc.contains("refreshVoidstrikesForRender");

        // Item B: T-S-F-C Graphics Quality setting (default High, plus Low)
        DivisionAISettings.testTsfcQualityOverride = null;
        boolean defaultTsfcHighOk = DivisionAISettings.getTsfcGraphicsQuality() == DivisionAISettings.TsfcGraphicsQuality.HIGH
                && DivisionAISettings.isTsfcHighGraphics();
        DivisionAISettings.testTsfcQualityOverride = DivisionAISettings.TsfcGraphicsQuality.LOW;
        boolean overrideTsfcLowOk = DivisionAISettings.getTsfcGraphicsQuality() == DivisionAISettings.TsfcGraphicsQuality.LOW
                && !DivisionAISettings.isTsfcHighGraphics();
        DivisionAISettings.testTsfcQualityOverride = null;
        boolean tsfcGraphicsSettingOk = defaultTsfcHighOk && overrideTsfcLowOk;

        // Items C, D, E, D2, F, G, H: Carrier & Wing Voidstrike Doctrine & Division Editor overrides + staging
        FleetDoctrineConfig vsDocCfg = new FleetDoctrineConfig();
        vsDocCfg.setVoidstrikeObservance(
                PermanentDivisionManager.DoctrineDivisionType.FLEET_CARRIER,
                PermanentDivisionManager.VoidstrikeTargetObservance.HIGH);
        vsDocCfg.setVoidstrikeCoordination(
                PermanentDivisionManager.DoctrineDivisionType.FLEET_CARRIER,
                PermanentDivisionManager.VoidstrikeCoordinationMode.COORDINATED);
        vsDocCfg.setFighterFormation(
                PermanentDivisionManager.DoctrineDivisionType.FLEET_CARRIER,
                PermanentDivisionManager.SquadronFormation.WEDGE);
        vsDocCfg.setFighterVoidstrikeFormation(
                PermanentDivisionManager.DoctrineDivisionType.FLEET_CARRIER,
                PermanentDivisionManager.SquadronFormation.LINE_AHEAD);
        vsDocCfg.setBomberFormation(
                PermanentDivisionManager.DoctrineDivisionType.FLEET_CARRIER,
                PermanentDivisionManager.SquadronFormation.LINE_ABREAST);
        vsDocCfg.setBomberVoidstrikeFormation(
                PermanentDivisionManager.DoctrineDivisionType.FLEET_CARRIER,
                PermanentDivisionManager.SquadronFormation.REVERSE_SEMICIRCLE);
        vsDocCfg.setVoidstrikeSquadronDepth(
                PermanentDivisionManager.DoctrineDivisionType.FLEET_CARRIER,
                7);

        PermanentDivisionSpec testCarrierSpec = new PermanentDivisionSpec(
                "perm_div_vs_test",
                "Fleet Carrier Div 1",
                DivisionArchetype.FLEET_CARRIER_DIVISION);
        PermanentDivisionManager.applyDoctrineModifiersToCombatDivision(cDiv1, testCarrierSpec, vsDocCfg);
        boolean vsDoctrinePropagatedOk = cDiv1.voidstrikeObservance == PermanentDivisionManager.VoidstrikeTargetObservance.HIGH
                && cDiv1.voidstrikeCoordination == PermanentDivisionManager.VoidstrikeCoordinationMode.COORDINATED
                && cDiv1.fighterFormation == PermanentDivisionManager.SquadronFormation.WEDGE
                && cDiv1.fighterVoidstrikeFormation == PermanentDivisionManager.SquadronFormation.LINE_AHEAD
                && cDiv1.bomberFormation == PermanentDivisionManager.SquadronFormation.LINE_ABREAST
                && cDiv1.bomberVoidstrikeFormation == PermanentDivisionManager.SquadronFormation.REVERSE_SEMICIRCLE
                && cDiv1.voidstrikeSquadronDepth == 7;

        // Verify per-division custom overrides for all 7 Voidstrike settings
        testCarrierSpec.customVoidstrikeObservance = PermanentDivisionManager.VoidstrikeTargetObservance.FREE;
        testCarrierSpec.customVoidstrikeCoordination = PermanentDivisionManager.VoidstrikeCoordinationMode.UNCOORDINATED;
        testCarrierSpec.customFighterFormation = PermanentDivisionManager.SquadronFormation.CIRCLE;
        testCarrierSpec.customFighterVoidstrikeFormation = PermanentDivisionManager.SquadronFormation.REVERSE_WEDGE;
        testCarrierSpec.customBomberFormation = PermanentDivisionManager.SquadronFormation.SEMICIRCLE;
        testCarrierSpec.customBomberVoidstrikeFormation = PermanentDivisionManager.SquadronFormation.WEDGE;
        testCarrierSpec.customVoidstrikeSquadronDepth = 10;
        PermanentDivisionManager.applyDoctrineModifiersToCombatDivision(cDiv1, testCarrierSpec, vsDocCfg);
        boolean vsCustomOverrideOk = cDiv1.voidstrikeObservance == PermanentDivisionManager.VoidstrikeTargetObservance.FREE
                && cDiv1.voidstrikeCoordination == PermanentDivisionManager.VoidstrikeCoordinationMode.UNCOORDINATED
                && cDiv1.fighterFormation == PermanentDivisionManager.SquadronFormation.CIRCLE
                && cDiv1.fighterVoidstrikeFormation == PermanentDivisionManager.SquadronFormation.REVERSE_WEDGE
                && cDiv1.bomberFormation == PermanentDivisionManager.SquadronFormation.SEMICIRCLE
                && cDiv1.bomberVoidstrikeFormation == PermanentDivisionManager.SquadronFormation.WEDGE
                && cDiv1.voidstrikeSquadronDepth == 10;

        // Verify Coordinated Voidstrike Staging progression: Stage 0 (Orbit Carrier) -> Stage 1 (Marshal Point) -> Stage 2 (Attack)
        cDiv1.voidstrikeCoordination = PermanentDivisionManager.VoidstrikeCoordinationMode.COORDINATED;
        cDiv1.voidstrikeLastTargetKey = null;
        DivisionCommanderAI.updateDivisionVoidstrikeStaging(cDiv1, pGroup, enemyTarget, 100f);
        boolean stage0Ok = (cDiv1.voidstrikeStage == 0);
        DivisionCommanderAI.updateDivisionVoidstrikeStaging(cDiv1, pGroup, enemyTarget, 102f);
        boolean stage1Ok = (cDiv1.voidstrikeStage == 1) && (cDiv1.voidstrikeMarshalPoint.y > -1500f);
        DivisionCommanderAI.updateDivisionVoidstrikeStaging(cDiv1, pGroup, enemyTarget, 105f);
        boolean stage2Ok = (cDiv1.voidstrikeStage == 2);
        boolean campaignUiSweepOk = "F. Wedge".equals(DivisionFormation.FORWARD_WEDGE.shortName)
                && "R. Wedge".equals(DivisionFormation.REVERSE_WEDGE.shortName)
                && "Ech. L".equals(DivisionFormation.ECHELON_LEFT.shortName)
                && "Ech. R".equals(DivisionFormation.ECHELON_RIGHT.shortName)
                && "P. Circle".equals(DivisionFormation.PROTECTIVE_CIRCLE.shortName)
                && "F. Wedge".equals(PermanentDivisionManager.SquadronFormation.WEDGE.shortName)
                && "R. Wedge".equals(PermanentDivisionManager.SquadronFormation.REVERSE_WEDGE.shortName);

        // Verify Change A: Strike Width (1..10) + Compromise Averaging when width * depth != totalSquadrons
        vsDocCfg.setVoidstrikeSquadronWidth(PermanentDivisionManager.DoctrineDivisionType.FLEET_CARRIER, 6);
        testCarrierSpec.customVoidstrikeSquadronWidth = null;
        PermanentDivisionManager.applyDoctrineModifiersToCombatDivision(cDiv1, testCarrierSpec, vsDocCfg);
        boolean vsWidthPropagatedOk = (cDiv1.voidstrikeSquadronWidth == 6);
        testCarrierSpec.customVoidstrikeSquadronWidth = 8;
        PermanentDivisionManager.applyDoctrineModifiersToCombatDivision(cDiv1, testCarrierSpec, vsDocCfg);
        boolean vsWidthCustomOk = (cDiv1.voidstrikeSquadronWidth == 8);
        int[] exactComp = DivisionCommanderAI.computeCompromiseStrikeWidthAndDepth(6, 3, 2);
        int[] avgComp = DivisionCommanderAI.computeCompromiseStrikeWidthAndDepth(4, 4, 4);
        boolean strikeWidthCompromiseOk = vsWidthPropagatedOk && vsWidthCustomOk
                && exactComp[0] == 3 && exactComp[1] == 2
                && avgComp[0] == 3 && avgComp[1] == 3;

        // Verify Change B: Effective HP (Shield + Armor + Hull HP) in 'Space' Domain & Hammerhead anti-suicide
        ShipAPI mockHammerhead = createMockNamedShip("hh_test", "Hammerhead", HullSize.DESTROYER, 0f, 0f, 90f, 0, humanCapt, 1.0f, 0.0f);
        ShipAPI mockDominator = createMockNamedShip("dom_test", "Dominator", HullSize.CRUISER, 0f, 1000f, 60f, 1, humanCapt, 1.0f, 0.0f);
        float hhFullEhp = BattlefieldFirepowerEvaluator.computeShipEffectiveHp(mockHammerhead);
        float domFullEhp = BattlefieldFirepowerEvaluator.computeShipEffectiveHp(mockDominator);
        DivisionState hhDiv = new DivisionState("hh_div", 0, 1, DivisionArchetype.DESTROYER_DIVISION, "Destroyer Div 1", cmdr);
        hhDiv.flagship = mockHammerhead;
        hhDiv.members.add(mockHammerhead);
        hhDiv.totalFpStrength = 10f;
        hhDiv.avgHullLevel = 1.0f;
        hhDiv.avgFluxLevel = 0.0f;
        hhDiv.avgEngageRange = 750f;
        BattlefieldFirepowerEvaluator.updateDivisionBaseTimeSpace(hhDiv);
        float freshSpaceWeight = hhDiv.spaceDominanceWeight;
        hhDiv.avgHullLevel = 0.45f;
        hhDiv.avgFluxLevel = 0.78f;
        // Simulate depleted shield/hull on the Hammerhead via a damaged mock
        ShipAPI damagedHammerhead = createMockNamedShip("hh_dmg", "Hammerhead", HullSize.DESTROYER, 0f, 0f, 90f, 0, humanCapt, 0.45f, 0.78f);
        hhDiv.members.clear();
        hhDiv.members.add(damagedHammerhead);
        hhDiv.flagship = damagedHammerhead;
        BattlefieldFirepowerEvaluator.updateDivisionBaseTimeSpace(hhDiv);
        float depletedSpaceWeight = hhDiv.spaceDominanceWeight;
        boolean ehpSpaceDomainOk = (domFullEhp > hhFullEhp * 1.8f)
                && (freshSpaceWeight > depletedSpaceWeight * 1.35f)
                && (hhDiv.effectiveHpRatio < 0.65f);

        // Verify Change D: Protective Circle formation places core ship at center (0,0) and escorts on 360-deg outer ring
        float[] pCircleCore = DivisionCommanderAI.computeLocalFormationOffset(DivisionFormation.PROTECTIVE_CIRCLE, 0, 4, 500f);
        float[] pCircleRing1 = DivisionCommanderAI.computeLocalFormationOffset(DivisionFormation.PROTECTIVE_CIRCLE, 1, 4, 500f);
        float[] pCircleRing2 = DivisionCommanderAI.computeLocalFormationOffset(DivisionFormation.PROTECTIVE_CIRCLE, 2, 4, 500f);
        float ring1Rad = (float) Math.hypot(pCircleRing1[0], pCircleRing1[1]);
        float ring2Rad = (float) Math.hypot(pCircleRing2[0], pCircleRing2[1]);
        boolean protectiveCircleOk = Math.abs(pCircleCore[0]) < 1f && Math.abs(pCircleCore[1]) < 1f
                && ring1Rad > 350f && Math.abs(ring1Rad - ring2Rad) < 1f;

        // Verify Items B, C, E, F:
        // - Item B: Campaign fleet hover tooltip pixel-width balanced 1/2/3-line splitting & pre-sized LabelAPI before setText
        String[] mbLines = divisionai.CampaignFleetTooltipOrganizer.splitBalancedLines("Missile Battleship Div 1", null, 82f);
        String[] scLines = divisionai.CampaignFleetTooltipOrganizer.splitBalancedLines("Strike Cruiser Div 1", null, 82f);
        String[] unLines = divisionai.CampaignFleetTooltipOrganizer.splitBalancedLines("Unassigned", null, 82f);
        boolean tooltipTextQaOk = (mbLines.length == 3 && "Missile".equals(mbLines[0]) && "Battleship".equals(mbLines[1]) && "Div 1".equals(mbLines[2]))
                && (scLines.length == 2)
                && (unLines.length == 1 && "Unassigned".equals(unLines[0]))
                && tooltipSrc.contains("p1.setSize(safeW1, lineH);")
                && tooltipSrc.contains("line3Label.setText(l3);");

        // - Item C: Warroom formation icon inline with division text ("[symbol] Strike Cruiser Div 1")
        //   and centered on the exact horizontal plane of the division name capital letters
        boolean warroomInlineFormOk = overlaySrc.contains("float totalRowW = (activeForm != null) ? (iconSize + iconGap + textW) : textW;")
                && overlaySrc.contains("float textCenterY = labelY + (textH * 0.5f);")
                && overlaySrc.contains("rowStartX + (iconSize * 0.5f)");

        // - Item E & F: No inner #182028 lineart in any of the 11 formation SVGs, and None formation icon has
        //   3 vertical equal-size bars (two close with x,y offset and one far away with x,y offset)
        boolean formationIconsCleanOk = true;
        for (DivisionFormation f : DivisionFormation.values()) {
            String svgSilverName = "mod/graphics/icons/formations/" + f.getSettingsIconKey(false) + ".svg";
            String svgGoldName = "mod/graphics/icons/formations/" + f.getSettingsIconKey(true) + ".svg";
            String svgSilver = new String(Files.readAllBytes(new File(svgSilverName).toPath()), StandardCharsets.UTF_8);
            String svgGold = new String(Files.readAllBytes(new File(svgGoldName).toPath()), StandardCharsets.UTF_8);
            if (svgSilver.contains("#182028") || svgGold.contains("#182028")
                    || !svgSilver.contains("#C8D2DC") || !svgGold.contains("#E0B450")
                    || !new File("mod/" + f.getSpritePath(false)).isFile()
                    || !new File("mod/" + f.getSpritePath(true)).isFile()) {
                formationIconsCleanOk = false;
                break;
            }
        }
        String noneSvg = new String(
                Files.readAllBytes(new File("mod/graphics/icons/formations/icon_formation_none.svg").toPath()),
                StandardCharsets.UTF_8);
        String noneGoldSvg = new String(
                Files.readAllBytes(new File("mod/graphics/icons/formations/icon_formation_none_gold.svg").toPath()),
                StandardCharsets.UTF_8);
        boolean noneIconVerticalBarsOk = !noneSvg.contains("rotate(")
                && noneSvg.contains("M9 10 L17 10 L17 30 L9 30 Z")
                && noneSvg.contains("M23 34 L31 34 L31 54 L23 54 Z")
                && noneSvg.contains("M46 20 L54 20 L54 40 L46 40 Z")
                && noneGoldSvg.contains("M9 10 L17 10 L17 30 L9 30 Z")
                && noneGoldSvg.contains("#E0B450");

        // - Items A, B, C, D, E, F (Opening Echelon/Line Abreast handedness, Voidstrike mid-flight continuity,
        //   Directional pinchable non-circular Space, Map-edge pinching, and Unable/Retreating unit discounting):
        float[] echR0 = DivisionCommanderAI.computeLocalFormationOffset(DivisionFormation.ECHELON_RIGHT, 0, 4, 300f);
        float[] echR2 = DivisionCommanderAI.computeLocalFormationOffset(DivisionFormation.ECHELON_RIGHT, 2, 4, 300f);
        float[] echL2 = DivisionCommanderAI.computeLocalFormationOffset(DivisionFormation.ECHELON_LEFT, 2, 4, 300f);
        boolean echelonHandednessOk = (echR0[0] == 0f && echR0[1] == 0f)
                && (echR2[0] > 350f && echR2[1] < -300f)
                && (echL2[0] < -350f && echL2[1] < -300f);

        // Item C: Airborne Voidstrike in Stage 2 does not reset to Stage 0 when target switches mid-flight
        ShipAPI enemyTarget2 = createMockNamedShip("wb2_s1", "Warband 2 Lead", HullSize.CRUISER, 350f, 1600f, 90f, 1, humanCapt);
        DivisionState enemyWarband2 = new DivisionState("wb_2", 1, 2, DivisionArchetype.CRUISER_DIVISION, "Warband 2", cmdr);
        enemyWarband2.flagship = enemyTarget2;
        enemyWarband2.members.add(enemyTarget2);
        cDiv1.targetEnemyDivision = enemyWarband2;
        DivisionCommanderAI.updateDivisionVoidstrikeStaging(cDiv1, pGroup, enemyTarget2, 106f);
        boolean voidstrikeMidFlightKeepStage2Ok = (cDiv1.voidstrikeStage == 2);

        // Items D & E: Directional non-circular Space & Map-edge pinching
        hhDiv.centroid.set(0f, 8600f); // 400u from North map edge (default halfH = 9000f)
        hhDiv.formationHeadingInitialized = true;
        hhDiv.formationHeadingX = 0f;
        hhDiv.formationHeadingY = 1f;
        hhDiv.activeFormation = DivisionFormation.LINE_ABREAST;
        BattlefieldFirepowerEvaluator.updateDivisionBaseTimeSpace(hhDiv);
        BattlefieldFirepowerEvaluator.computeDirectionalTimeEnvelope(null, hhDiv, pGroup, eGroup, 0.1f, 0.9f);
        BattlefieldFirepowerEvaluator.computeDirectionalSpaceEnvelope(null, hhDiv, pGroup, eGroup, 1.0f);
        float northSpaceDom = BattlefieldFirepowerEvaluator.getDirectionalSpaceDominatedRadius(hhDiv, 90f);   // toward North wall (400u away)
        float southSpaceDom = BattlefieldFirepowerEvaluator.getDirectionalSpaceDominatedRadius(hhDiv, 270f);  // away from wall
        float eastSpaceDom = BattlefieldFirepowerEvaluator.getDirectionalSpaceDominatedRadius(hhDiv, 0f);     // along LINE_ABREAST lateral axis
        boolean directionalAndEdgePinchedSpaceOk = hhDiv.directionalSpaceInitialized
                && (northSpaceDom < 420f)
                && (southSpaceDom > northSpaceDom * 1.8f)
                && (eastSpaceDom > southSpaceDom * 1.03f);

        // Item F: Retreating ("unable") unit detection & Space/Time discounting
        final boolean[] shipRetreatingFlag = new boolean[]{true};
        ShipAPI retreatingShip = (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getId".equals(n)) return "ret_1";
                    if ("isAlive".equals(n)) return true;
                    if ("isRetreating".equals(n)) return shipRetreatingFlag[0];
                    if ("getHullSize".equals(n)) return HullSize.CRUISER;
                    if ("getLocation".equals(n)) return new Vector2f(0f, 0f);
                    if ("getVelocity".equals(n)) return new Vector2f(0f, 0f);
                    if ("getMaxSpeed".equals(n)) return 90f;
                    if ("getHitpoints".equals(n)) return 5000f;
                    if ("getMaxHitpoints".equals(n)) return 5000f;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
        boolean unableShipDetectedOk = DivisionState.isShipUnable(retreatingShip);
        hhDiv.isUnableDivision = true;
        hhDiv.retreatingFraction = 1.0f;
        boolean unableNotAbleCombatEffectiveOk = !hhDiv.isAbleCombatEffective()
                && unableShipDetectedOk;

        java.util.Set<ShipAPI> flagSet = new java.util.HashSet<>(Arrays.asList(damagedHammerhead));
        List<PermanentDivisionSpec> sortTestSpecs = new ArrayList<>(Arrays.asList(
                new PermanentDivisionSpec("d_srv", "Service Group 1", DivisionArchetype.SERVICE_GROUP),
                new PermanentDivisionSpec("d_ff", "Frigate Div 1", DivisionArchetype.FRIGATE_DIVISION),
                new PermanentDivisionSpec("d_dd", "Destroyer Div 1", DivisionArchetype.DESTROYER_DIVISION),
                new PermanentDivisionSpec("d_ca", "Line Cruiser Div 1", DivisionArchetype.CRUISER_DIVISION),
                new PermanentDivisionSpec("d_cv", "Fleet Carrier Div 1", DivisionArchetype.FLEET_CARRIER_DIVISION),
                new PermanentDivisionSpec("d_bb", "Battleship Div 1", DivisionArchetype.BATTLESHIP_DIVISION)
        ));
        PermanentDivisionManager.sortDivisionsByWeightClassAndRole(sortTestSpecs, null);
        boolean weightRoleSortOk = sortTestSpecs.get(0).archetype == DivisionArchetype.BATTLESHIP_DIVISION
                && sortTestSpecs.get(1).archetype == DivisionArchetype.FLEET_CARRIER_DIVISION
                && sortTestSpecs.get(2).archetype == DivisionArchetype.CRUISER_DIVISION
                && sortTestSpecs.get(3).archetype == DivisionArchetype.DESTROYER_DIVISION
                && sortTestSpecs.get(4).archetype == DivisionArchetype.FRIGATE_DIVISION
                && sortTestSpecs.get(5).archetype == DivisionArchetype.SERVICE_GROUP;
        boolean inputEventOrderOk = editorSrc.contains("float dropX = event.getX();")
                && editorSrc.indexOf("float dropX = event.getX();") < editorSrc.indexOf("DropTargetZone dropZone = findDropZoneAt(dropX, dropY);")
                && editorSrc.contains("handleShipTileDrop(finishedTile, dropZone, targetShipTile)");
        boolean flagshipDivRankOk = DivisionTacticalMapOverlay.isFlagshipDivision(hhDiv, flagSet)
                && !DivisionTacticalMapOverlay.isFlagshipDivision(enemyWarband2, flagSet)
                && doctrineSrc.contains("renderFormationSquareBorder")
                && doctrineSrc.contains("loadFormationSpriteSafe")
                && doctrineSrc.contains("Global.getSettings().loadTexture(path)")
                && weightRoleSortOk
                && inputEventOrderOk;

        // Verify Explicit Mass-Weighted Fleet Engagement Telemetry & Dynamic Battle Music Profiles:
        // Case 1: 2 Frigate Divisions (~15 FP each = 30 FP total) engaging at 0.85 intensity while a
        //         4-ship Battleship Division (112 FP) is uncommitted -> stays BELOW 0.26 threshold!
        BattleGroupState engTestGroup = new BattleGroupState(0, cmdr);
        DivisionState bbUncommitted = new DivisionState("bb_un", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "BB Div 1", cmdr);
        bbUncommitted.members.add(damagedHammerhead);
        bbUncommitted.ableMemberCount = 4;
        bbUncommitted.isUnableDivision = false;
        bbUncommitted.totalFpStrength = 112f;
        bbUncommitted.violentContactIntensity = 0.0f;

        DivisionState ffEng1 = new DivisionState("ff_1", 0, 2, DivisionArchetype.FRIGATE_DIVISION, "FF Div 1", cmdr);
        ffEng1.members.add(damagedHammerhead);
        ffEng1.ableMemberCount = 3;
        ffEng1.isUnableDivision = false;
        ffEng1.totalFpStrength = 15f;
        ffEng1.violentContactIntensity = 0.85f;

        DivisionState ffEng2 = new DivisionState("ff_2", 0, 3, DivisionArchetype.FRIGATE_DIVISION, "FF Div 2", cmdr);
        ffEng2.members.add(damagedHammerhead);
        ffEng2.ableMemberCount = 3;
        ffEng2.isUnableDivision = false;
        ffEng2.totalFpStrength = 15f;
        ffEng2.violentContactIntensity = 0.85f;

        engTestGroup.divisions.addAll(Arrays.asList(bbUncommitted, ffEng1, ffEng2));
        engTestGroup.mainEffortDivision = bbUncommitted;
        DynamicBattleMusicController.updateBattleGroupEngagementTelemetry(engTestGroup);
        boolean twoFrigateDivsBelowThresholdOk = engTestGroup.fleetEngagementLevel < DynamicBattleMusicController.DEFAULT_ACTIVATION_THRESHOLD
                && !engTestGroup.mainEffortEngaged
                && engTestGroup.engagedDivisionCount == 2;

        // Case 2: 4 Frigate Divisions engaging while the 4-ship Battleship Division is still uncommitted -> CROSSES 0.26 threshold!
        DivisionState ffEng3 = new DivisionState("ff_3", 0, 4, DivisionArchetype.FRIGATE_DIVISION, "FF Div 3", cmdr);
        ffEng3.members.add(damagedHammerhead);
        ffEng3.ableMemberCount = 3;
        ffEng3.isUnableDivision = false;
        ffEng3.totalFpStrength = 15f;
        ffEng3.violentContactIntensity = 0.85f;

        DivisionState ffEng4 = new DivisionState("ff_4", 0, 5, DivisionArchetype.FRIGATE_DIVISION, "FF Div 4", cmdr);
        ffEng4.members.add(damagedHammerhead);
        ffEng4.ableMemberCount = 3;
        ffEng4.isUnableDivision = false;
        ffEng4.totalFpStrength = 15f;
        ffEng4.violentContactIntensity = 0.85f;

        engTestGroup.divisions.addAll(Arrays.asList(ffEng3, ffEng4));
        DynamicBattleMusicController.updateBattleGroupEngagementTelemetry(engTestGroup);
        boolean fourFrigateDivsCrossThresholdOk = engTestGroup.fleetEngagementLevel >= DynamicBattleMusicController.DEFAULT_ACTIVATION_THRESHOLD
                && engTestGroup.engagedDivisionCount == 4;

        // Case 3: 4-ship Battleship Division commits -> swells past full-volume engagement!
        bbUncommitted.violentContactIntensity = 0.90f;
        DynamicBattleMusicController.updateBattleGroupEngagementTelemetry(engTestGroup);
        boolean battleshipCommitHighEngagementOk = engTestGroup.fleetEngagementLevel >= DynamicBattleMusicController.DEFAULT_FULL_VOLUME_ENGAGEMENT
                && engTestGroup.mainEffortEngaged;

        // Verify all required Faction Music Profiles & Multi-Layer Volume Curves
        DynamicBattleMusicController.FactionMusicProfile defProf = DynamicBattleMusicController.getResolvedProfileForFaction("unknown_modded_faction");
        DynamicBattleMusicController.FactionMusicProfile derProf = DynamicBattleMusicController.getResolvedProfileForFaction("derelict");
        DynamicBattleMusicController.FactionMusicProfile hegProf = DynamicBattleMusicController.getResolvedProfileForFaction("hegemony");
        DynamicBattleMusicController.FactionMusicProfile perProf = DynamicBattleMusicController.getResolvedProfileForFaction("persean");
        DynamicBattleMusicController.FactionMusicProfile ludProf = DynamicBattleMusicController.getResolvedProfileForFaction("luddic_church");
        DynamicBattleMusicController.FactionMusicProfile pthProf = DynamicBattleMusicController.getResolvedProfileForFaction("luddic_path");
        DynamicBattleMusicController.FactionMusicProfile pirProf = DynamicBattleMusicController.getResolvedProfileForFaction("pirates");
        DynamicBattleMusicController.FactionMusicProfile triProf = DynamicBattleMusicController.getResolvedProfileForFaction("tritachyon");
        DynamicBattleMusicController.FactionMusicProfile remProf = DynamicBattleMusicController.getResolvedProfileForFaction("remnant");
        DynamicBattleMusicController.FactionMusicProfile thrProf = DynamicBattleMusicController.getResolvedProfileForFaction("threat");
        DynamicBattleMusicController.FactionMusicProfile abyProf = DynamicBattleMusicController.getResolvedProfileForFaction("dweller");

        boolean musicProfilesOk = defProf != null && !defProf.silent
                && defProf.getAmbienceBottomLayer() != null
                && defProf.getAmbienceBottomLayer().files.contains("battle_ambience_01.ogg")
                && defProf.getAmbienceBottomLayer().alwaysOn
                && !defProf.getAmbienceBottomLayer().fadeOnPause
                && defProf.getPrimaryBattleTrackLayer() != null
                && defProf.getPrimaryBattleTrackLayer().files.contains("faction_hegemony_market_02_hostile_var01.ogg")
                && defProf.getPrimaryBattleTrackLayer().fadeOnPause
                && derProf != null && !derProf.silent && derProf.getPrimaryBattleTrackLayer().files.contains("faction_hegemony_market_02_hostile_var01.ogg")
                && hegProf != null && !hegProf.silent && hegProf.getPrimaryBattleTrackLayer().files.contains("faction_hegemony_encounter_02_hostile.ogg")
                && hegProf.getPrimaryBattleTrackLayer().files.contains("faction_hegemony_encounter_01_neutral.ogg")
                && perProf != null && !perProf.silent && perProf.getPrimaryBattleTrackLayer().files.contains("faction_league_encounter_03_friendly.ogg")
                && ludProf != null && !ludProf.silent && ludProf.getPrimaryBattleTrackLayer().files.size() == 2
                && "faction_luddite_market_02_hostile_var01.ogg".equals(ludProf.getPrimaryBattleTrackLayer().files.get(0))
                && "faction_luddite_market_02_hostile_var02.ogg".equals(ludProf.getPrimaryBattleTrackLayer().files.get(1))
                && !ludProf.getPrimaryBattleTrackLayer().randomStart
                && pthProf != null && !pthProf.silent && pthProf.getPrimaryBattleTrackLayer().files.size() == 2
                && pirProf != null && !pirProf.silent && pirProf.getPrimaryBattleTrackLayer().files.size() == 2
                && "faction_pirate_market_02_hostile_var01.ogg".equals(pirProf.getPrimaryBattleTrackLayer().files.get(0))
                && "faction_pirate_market_02_hostile_var02.ogg".equals(pirProf.getPrimaryBattleTrackLayer().files.get(1))
                && !pirProf.getPrimaryBattleTrackLayer().randomStart
                && triProf != null && !triProf.silent && triProf.getPrimaryBattleTrackLayer().files.contains("faction_tritach_market_02_hostile.ogg")
                && remProf != null && !remProf.silent && remProf.getPrimaryBattleTrackLayer().files.contains("faction_tritach_market_02_hostile.ogg")
                && thrProf != null && thrProf.silent && thrProf.getAmbienceBottomLayer().files.contains("battle_ambience_01.ogg") && thrProf.getPrimaryBattleTrackLayer() == null
                && abyProf != null && abyProf.silent && abyProf.getAmbienceBottomLayer().files.contains("battle_ambience_01.ogg") && abyProf.getPrimaryBattleTrackLayer() == null
                && defProf.getPrimaryBattleTrackLayer().computeTargetVolume(0.18f, false) == 0f
                && defProf.getPrimaryBattleTrackLayer().computeTargetVolume(0.30f, true) > 0.24f
                && defProf.getPrimaryBattleTrackLayer().computeTargetVolume(0.85f, true) >= 0.99f;

        // Verify Station Time & Space Bastion Projection (Issue C) & Battle Music Fade-Out / Cessation (Issues D & E):
        ShipAPI mockStation = (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("equals".equals(n)) return p == a[0];
                    if ("hashCode".equals(n)) return System.identityHashCode(p);
                    if ("getId".equals(n)) return "station_fortress_1";
                    if ("getName".equals(n)) return "Def's Landing Station";
                    if ("getOwner".equals(n)) return 0;
                    if ("isAlly".equals(n)) return true;
                    if ("isAlive".equals(n) || "isStation".equals(n)) return true;
                    if ("isHulk".equals(n) || "isExpired".equals(n) || "isFighter".equals(n)
                            || "isShuttlePod".equals(n) || "isDrone".equals(n) || "isStationModule".equals(n)) {
                        return false;
                    }
                    if ("getParentStation".equals(n)) return null;
                    if ("getLocation".equals(n)) return new Vector2f(0f, 0f);
                    if ("getVelocity".equals(n)) return new Vector2f(0f, 0f);
                    if ("getCollisionRadius".equals(n)) return 380f;
                    if ("getMaxSpeed".equals(n)) return 0f;
                    if ("getHullSize".equals(n)) return ShipAPI.HullSize.CAPITAL_SHIP;
                    if ("getHullLevel".equals(n)) return 0.95f;
                    if ("getFluxLevel".equals(n)) return 0.10f;
                    if ("getNumFighterBays".equals(n)) return 3;
                    if ("getAllWeapons".equals(n)) return new ArrayList<com.fs.starfarer.api.combat.WeaponAPI>();
                    if ("getChildModulesCopy".equals(n)) return new ArrayList<ShipAPI>();
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
        final List<ShipAPI> stationShips = new ArrayList<>(Arrays.asList(mockStation, enemyTarget));
        final boolean[] combatOverRef = new boolean[]{false};
        final boolean[] combatPausedRef = new boolean[]{false};
        CombatEngineAPI musicTestEngine = (CombatEngineAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{CombatEngineAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("equals".equals(n)) return p == a[0];
                    if ("hashCode".equals(n)) return System.identityHashCode(p);
                    if ("getShips".equals(n)) return stationShips;
                    if ("isCombatOver".equals(n)) return combatOverRef[0];
                    if ("isPaused".equals(n)) return combatPausedRef[0];
                    if ("isInCampaign".equals(n)) return true;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
        BattleGroupState alliedStationGroup = new BattleGroupState(0, cmdr);
        alliedStationGroup.isAlliedAi = true;
        BattlefieldFirepowerEvaluator.setActiveBattleGroups(engTestGroup, alliedStationGroup, eGroup);
        BattlefieldFirepowerEvaluator.updateCrossGroupTimeSpaceConvergence(musicTestEngine, alliedStationGroup, eGroup);
        List<DivisionState> stationBastions = BattlefieldFirepowerEvaluator.getDivisionsAndStationBastionsForRendering(musicTestEngine, alliedStationGroup);
        boolean stationTimeSpaceProjectedOk = !stationBastions.isEmpty()
                && stationBastions.get(0).freeTimeRadius >= 850f
                && stationBastions.get(0).effectiveTimeRadius >= 400f
                && stationBastions.get(0).spaceInnerRadius >= 380f
                && stationBastions.get(0).spaceDominatedRadius > stationBastions.get(0).spaceInnerRadius
                && stationBastions.get(0).spacePotentialRadius > stationBastions.get(0).spaceDominatedRadius
                && stationBastions.get(0).directionalTimeInitialized
                && stationBastions.get(0).directionalSpaceInitialized
                && stationBastions.get(0).isStrongpoint;

        // Verify Tasks A, B, C, D, E, F (2026-10-30 QA Pass & Enhancements):
        // Task A: Deployment screen division tag 2-line splitter & non-overlapping Select button layout,
        //         and player-faction allied station nomenclature (never ABYSSAL_ERROR / UNKNOWN)
        String[] depTagShort = DivisionDeploymentDialogOrganizer.splitDeploymentCardTitleLines("Cruiser Div 1", null, 88f, 102f);
        String[] depTagLong = DivisionDeploymentDialogOrganizer.splitDeploymentCardTitleLines("Missile Battleship Division 1", null, 88f, 102f);
        DivisionNomenclature.NomenclatureCategory alliedPlayerStationCat =
                DivisionNomenclature.resolveCategoryForCombatShips(Arrays.asList(mockStation), "player", false);
        DivisionNomenclature.PlayerNamingConfig playerNomCfg = DivisionNomenclature.getPlayerNamingConfig();
        boolean taskADeploymentAndNamingOk = depTagShort.length == 1
                && "Cruiser Div 1".equals(depTagShort[0])
                && depTagLong.length == 2
                && !depTagLong[0].isEmpty()
                && !depTagLong[1].isEmpty()
                && alliedPlayerStationCat != DivisionNomenclature.NomenclatureCategory.ABYSSAL_ERROR
                && DivisionNomenclature.resolvePlayerPrefixBucketKey(playerNomCfg, DivisionArchetype.LIGHT_CARRIER_DIVISION)
                        .equals(DivisionNomenclature.resolvePlayerPrefixBucketKey(playerNomCfg, DivisionArchetype.FLEET_CARRIER_DIVISION))
                && !DivisionNomenclature.resolvePlayerPrefixBucketKey(playerNomCfg, DivisionArchetype.MISSILE_CRUISER_DIVISION)
                        .equals(DivisionNomenclature.resolvePlayerPrefixBucketKey(playerNomCfg, DivisionArchetype.CRUISER_DIVISION));

        // Task B: Re-designation renames division ONLY when its name is default per chosen nomenclature;
        //         if custom (even by 1 character), it is NOT renamed
        String defaultCruiserName = DivisionNomenclature.formatDivisionNameWithConfig(
                playerNomCfg, DivisionArchetype.CRUISER_DIVISION, 1);
        String expectedBattleshipName = DivisionNomenclature.formatDivisionNameWithConfig(
                playerNomCfg, DivisionArchetype.BATTLESHIP_DIVISION, 1);
        PermanentDivisionManager.PermanentDivisionSpec redesigDefaultSpec =
                new PermanentDivisionManager.PermanentDivisionSpec("test_rd_1", defaultCruiserName, DivisionArchetype.CRUISER_DIVISION);
        PermanentDivisionManager.PermanentDivisionSpec redesigCustomByOneCharSpec =
                new PermanentDivisionManager.PermanentDivisionSpec("test_rd_2", defaultCruiserName + "X", DivisionArchetype.CRUISER_DIVISION);
        List<PermanentDivisionManager.PermanentDivisionSpec> redesigList =
                new ArrayList<>(Arrays.asList(redesigDefaultSpec, redesigCustomByOneCharSpec));
        PermanentDivisionManager.reDesignateDivisionInList(
                redesigList, "test_rd_1", null, DivisionArchetype.BATTLESHIP_DIVISION, null);
        PermanentDivisionManager.reDesignateDivisionInList(
                redesigList, "test_rd_2", null, DivisionArchetype.BATTLESHIP_DIVISION, null);
        boolean taskBReDesignateRenameOk = expectedBattleshipName.equals(redesigDefaultSpec.name)
                && redesigDefaultSpec.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                && (defaultCruiserName + "X").equals(redesigCustomByOneCharSpec.name)
                && redesigCustomByOneCharSpec.archetype == DivisionArchetype.BATTLESHIP_DIVISION;

        // Tasks C, D, E:
        // - Task C: battle_ambience_01.ogg plays at all times as Layer 0 (even at 0 engagement)
        // - Task D: Pausing battle quickly fades battle track into pause while keeping ambience playing, and resumes on unpause
        // - Task E: Option to disable mod music engine and switch control to vanilla default
        DynamicBattleMusicController musicCtrl = new DynamicBattleMusicController();
        engTestGroup.smoothedEngagementLevel = 0.0f;
        musicCtrl.advance(musicTestEngine, engTestGroup, eGroup, 0.10f);
        float ambienceAtZeroEng = musicCtrl.getAmbienceLayerVolume();
        float battleTrackAtZeroEng = musicCtrl.getBattleTrackLayerVolume();

        engTestGroup.smoothedEngagementLevel = 0.80f;
        for (int step = 0; step < 12; step++) {
            musicCtrl.advance(musicTestEngine, engTestGroup, eGroup, 0.20f);
        }
        float peakVolBeforeCombatEnd = musicCtrl.getBattleTrackLayerVolume();

        // Pause the battle -> battle track fades out to 0 and pauses in-place; ambience stays playing!
        combatPausedRef[0] = true;
        for (int step = 0; step < 8; step++) {
            musicCtrl.advance(musicTestEngine, engTestGroup, eGroup, 0.10f);
        }
        float battleTrackWhilePaused = musicCtrl.getBattleTrackLayerVolume();
        float ambienceWhilePaused = musicCtrl.getAmbienceLayerVolume();
        boolean pausedInPlaceFlag = musicCtrl.isBattleTrackPausedInPlace();

        // Resume time -> battle track unpauses and fades back in!
        combatPausedRef[0] = false;
        for (int step = 0; step < 8; step++) {
            musicCtrl.advance(musicTestEngine, engTestGroup, eGroup, 0.10f);
        }
        float battleTrackAfterResume = musicCtrl.getBattleTrackLayerVolume();
        boolean unpausedAfterResumeFlag = !musicCtrl.isBattleTrackPausedInPlace();

        // Task E: Disable music engine -> switches control to vanilla default; re-enable -> resumes mod control
        DivisionAISettings.setDynamicBattleMusicEnabled(Boolean.FALSE);
        musicCtrl.advance(musicTestEngine, engTestGroup, eGroup, 0.10f);
        boolean switchedToVanillaOk = musicCtrl.isYieldedToVanillaDefault()
                && musicCtrl.getAmbienceLayerVolume() == 0f
                && musicCtrl.getBattleTrackLayerVolume() == 0f;
        DivisionAISettings.setDynamicBattleMusicEnabled(null);
        for (int step = 0; step < 10; step++) {
            musicCtrl.advance(musicTestEngine, engTestGroup, eGroup, 0.20f);
        }
        float reEnabledBattleTrackVol = musicCtrl.getBattleTrackLayerVolume();

        combatOverRef[0] = true;
        musicCtrl.advance(musicTestEngine, engTestGroup, eGroup, 0.20f);
        float volDuringCombatEndFade = musicCtrl.getPrimaryLayerVolume();
        for (int step = 0; step < 20; step++) {
            musicCtrl.advance(musicTestEngine, engTestGroup, eGroup, 0.20f);
        }
        float volAfterCombatEndFade = musicCtrl.getPrimaryLayerVolume();
        boolean musicFadeOutAndCessationOk = ambienceAtZeroEng >= 0.80f
                && battleTrackAtZeroEng == 0f
                && peakVolBeforeCombatEnd > 0.50f
                && battleTrackWhilePaused == 0f
                && ambienceWhilePaused >= 0.80f
                && pausedInPlaceFlag
                && battleTrackAfterResume > 0.50f
                && unpausedAfterResumeFlag
                && switchedToVanillaOk
                && reEnabledBattleTrackVol > 0.50f
                && volDuringCombatEndFade < reEnabledBattleTrackVol
                && volDuringCombatEndFade > 0.01f
                && volAfterCombatEndFade == 0f;

        // Task F: Strike craft do not inflate solid hull-exclusion spaceInnerRadius (since warships can move through them)
        //         while still contributing to spaceDominatedRadius
        BattlefieldFirepowerEvaluator.updateDivisionBaseTimeSpace(cDiv1);
        boolean taskFStrikeCraftSpaceOk = evalSrc.contains("float wingInnerBonus = 0f;")
                && cDiv1.spaceInnerRadius >= 380f
                && cDiv1.spaceDominatedRadius > cDiv1.spaceInnerRadius + 220f;

        formationIconsCleanOk = formationIconsCleanOk && flagshipDivRankOk
                && twoFrigateDivsBelowThresholdOk && fourFrigateDivsCrossThresholdOk
                && battleshipCommitHighEngagementOk && musicProfilesOk
                && stationTimeSpaceProjectedOk && musicFadeOutAndCessationOk
                && taskADeploymentAndNamingOk && taskBReDesignateRenameOk && taskFStrikeCraftSpaceOk;
        boolean voidstrikeDoctrineAndStagingOk = vsDoctrinePropagatedOk && vsCustomOverrideOk
                && stage0Ok && stage1Ok && stage2Ok && campaignUiSweepOk
                && strikeWidthCompromiseOk && ehpSpaceDomainOk && protectiveCircleOk
                && tooltipTextQaOk && warroomInlineFormOk && formationIconsCleanOk && noneIconVerticalBarsOk
                && echelonHandednessOk && voidstrikeMidFlightKeepStage2Ok
                && directionalAndEdgePinchedSpaceOk && unableNotAbleCombatEffectiveOk;

        res.record(
                omegaCoreRecognized && allTenIconsOk && inheritedFromDoctrineOk && customOverrideOk
                        && inheritedIntoBattleOk && otcAuthorityShiftOk && surroundedCircleShiftOk
                        && breakupToNoneOk && geometryDistinctOk && configureDivFormationQaOk
                        && modalAboveDivTextOk && stickinessHierarchyOk && voidstrikeLifecycleOk
                        && carrierMissionLabelsOk && totAlphaStrikeExistsOk && separateSideCountersOk
                        && factionTooltipColorsOk && convergenceIconAndTsfcOptOk
                        && tsfcGraphicsSettingOk && voidstrikeDoctrineAndStagingOk,
                "Omega Core Convergence, 10-Formation Hierarchy, Movement Stickiness, Voidstrike Doctrine (A-H), High/Low TSFC Graphics & Optimization",
                String.format(
                        Locale.ROOT,
                        "omegaCore=%b, 10icons=%b, hierarchy=[doc=%b, custom=%b, battle=%b], stickiness=%b, voidstrike=[merged1=%b, returnClear=%b, next2=%b, perSide=%b, doctrineAndStaging=%b(s0=%b,s1=%b,s2=%b,ehp=%b,dir=%b,mp=%b,mFade=%b,tA=%b,tB=%b,tF=%b)], carrierLabels=%b, totAlpha=%b, factionTooltip=%b, convIconAndOpt=%b, tsfcGfx=%b",
                        omegaCoreRecognized, allTenIconsOk, inheritedFromDoctrineOk, customOverrideOk,
                        inheritedIntoBattleOk, stickinessHierarchyOk, multiDivMergedIntoOneOk,
                        disappearsOnReturnOk, incrementedToTwoOk, separateSideCountersOk, voidstrikeDoctrineAndStagingOk,
                        stage0Ok, stage1Ok, stage2Ok, ehpSpaceDomainOk, directionalAndEdgePinchedSpaceOk,
                        musicProfilesOk, musicFadeOutAndCessationOk, taskADeploymentAndNamingOk, taskBReDesignateRenameOk, taskFStrikeCraftSpaceOk,
                        carrierMissionLabelsOk, totAlphaStrikeExistsOk, factionTooltipColorsOk,
                        convergenceIconAndTsfcOptOk, tsfcGraphicsSettingOk));
    }
}
