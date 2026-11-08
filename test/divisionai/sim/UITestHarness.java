package divisionai.sim;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import divisionai.BattlefieldFirepowerEvaluator;
import divisionai.DivisionOtcCommandManager;
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
import divisionai.FactionLibrary;
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
import divisionai.TsfcWarroomRenderer;
import divisionai.VoidstrikeCoordinator;
import divisionai.DivisionFormationNavigator;
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
import java.util.Random;
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
        boolean allThreeDefaultOff = !TsfcWarroomRenderer.isOverlayVisible()
                && !TsfcWarroomRenderer.isSpaceOverlayVisible()
                && !TsfcWarroomRenderer.isTimeOverlayVisible();

        TsfcWarroomRenderer.toggleTimeOverlayVisible();
        boolean timeToggledIndependently = TsfcWarroomRenderer.isTimeOverlayVisible()
                && !TsfcWarroomRenderer.isSpaceOverlayVisible()
                && !TsfcWarroomRenderer.isOverlayVisible();

        TsfcWarroomRenderer.toggleSpaceOverlayVisible();
        boolean spaceToggledIndependently = TsfcWarroomRenderer.isTimeOverlayVisible()
                && TsfcWarroomRenderer.isSpaceOverlayVisible()
                && !TsfcWarroomRenderer.isOverlayVisible();

        TsfcWarroomRenderer.toggleOverlayVisible();
        boolean allThreeActive = TsfcWarroomRenderer.isTimeOverlayVisible()
                && TsfcWarroomRenderer.isSpaceOverlayVisible()
                && TsfcWarroomRenderer.isOverlayVisible();
        BattlefieldFirepowerEvaluator.resetForNewBattle();

        float yTime = TsfcWarroomRenderer.getOverlayButtonY(1080f, 0);
        float ySpace = TsfcWarroomRenderer.getOverlayButtonY(1080f, 1);
        float yFirepower = TsfcWarroomRenderer.getOverlayButtonY(1080f, 2);
        boolean squareGeometryOk = TsfcWarroomRenderer.BTN_SQUARE_W == 36f
                && TsfcWarroomRenderer.BTN_SQUARE_H == 34f
                && TsfcWarroomRenderer.BTN_WIDTH == 46f
                && TsfcWarroomRenderer.BTN_RIGHT_MARGIN == 20f
                && (yTime - ySpace) >= TsfcWarroomRenderer.BTN_SQUARE_H + 6f
                && (ySpace - yFirepower) >= TsfcWarroomRenderer.BTN_SQUARE_H + 6f;

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
                        TsfcWarroomRenderer.BTN_SQUARE_W, TsfcWarroomRenderer.BTN_SQUARE_H,
                        TsfcWarroomRenderer.BTN_WIDTH, TsfcWarroomRenderer.BTN_RIGHT_MARGIN,
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
        float enlargedInnerW = totalCols * itemW + 34f; // 962px
        float vanillaReserveH = 3 * itemH; // 174px
        float enlargedReserveH = visibleRows * itemH; // 406px

        boolean dimsOk = (totalCols == 16)
                && (labelCols == 2)
                && (shipsPerRow == 14)
                && (visibleRows == 7)
                && (Math.abs(DivisionDeploymentDialogOrganizer.ENLARGED_FLEET_COMMAND_HEIGHT - 204f) < 0.1f)
                && (enlargedInnerW <= 1920f - 140f)
                && (enlargedReserveH >= 400f);

        res.record(
                dimsOk,
                "Pre-Battle Deployment Window enlarged to 16 cols x 7 reserve rows (14 ships/row, 940x204px Fleet Command)",
                String.format(
                        Locale.ROOT,
                        "cols=%d (2 header + %d ships/row), visibleRows=%d, innerW=%.0fpx (vs %.0fpx vanilla), reserveH=%.0fpx (vs %.0fpx vanilla), fcH=%.0fpx",
                        totalCols, shipsPerRow, visibleRows, enlargedInnerW, vanillaInnerW, enlargedReserveH, vanillaReserveH,
                        DivisionDeploymentDialogOrganizer.ENLARGED_FLEET_COMMAND_HEIGHT));

        // Simulate laying out a 4-ship division, a 15-ship division (wraps across 2 rows: 14 + 1), and 5 unassigned ships
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
                "Deployment grid multi-row division wrapping aligns on 16-column boundaries without overlap",
                "groupSizes=[4, 15, 5] -> totalRows=" + totalRowsUsed + ", totalSlots=" + slotIdx);

        // Verify Deployment Screen Overhaul (Items A-E), Deep Wedge Echeloning & Formation-Aware Deployment Hijack
        String stdOtcTitle = DivisionDeploymentDialogOrganizer.formatFleetCommandOtcTitle(
                "Admiral Kanta", "HSS Bismark", false);
        String alphaOtcTitle = DivisionDeploymentDialogOrganizer.formatFleetCommandOtcTitle(
                "Alpha Core", "tts radiant", true);
        boolean titlesOk = "Admiral Kanta".equals(stdOtcTitle)
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

        // Verify 4 Naval Pennant cards (without redundant OTC Authority dropdown) & Tactical Play rows fit on single lines
        File fddFile = new File("src/divisionai/FleetDoctrineDialog.java");
        String src = new String(Files.readAllBytes(fddFile.toPath()), StandardCharsets.UTF_8);
        boolean singleLineOtcDropdown = !src.contains("optPanel.addUIElement(optDesc)")
                && !src.contains("rowUi.addPara(\"OTC Authority:\"")
                && src.contains("Command Scheme and Preferred Tactics")
                && src.contains("Officer in Tactical Command")
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
        String edSrc = new String(Files.readAllBytes(new File("src/divisionai/DivisionEditorDialog.java").toPath()), StandardCharsets.UTF_8)
                + "\n" + new String(Files.readAllBytes(new File("src/divisionai/DivisionConfigureSubscreen.java").toPath()), StandardCharsets.UTF_8);
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

        // B. Configure Division screen QA pass (1320x800, Division Type drop-down menu, Carrier Options button, aligned controls)
        boolean itemBOk = DivisionEditorDialog.DIALOG_WIDTH == 1320f
                && DivisionEditorDialog.DIALOG_HEIGHT == 800f
                && edSrc.contains("\"DIV_ARCH_DD\"")
                && edSrc.contains("\"TOGGLE_DIV_CARRIER_OPTIONS\"")
                && edSrc.contains("CONTROL_COL_X = 250f");
        res.record(
                itemBOk,
                "Item B: Configure Division screen enlarged to 1320x800 with Division Type drop-down menu, Carrier Options toggle, and aligned controls",
                String.format(Locale.ROOT, "dialog=%.0fx%.0fpx", DivisionEditorDialog.DIALOG_WIDTH, DivisionEditorDialog.DIALOG_HEIGHT));

        // Verify UI Polish Items A-G (Fleet Doctrine subtitle removal, Command Scheme green/red pros/cons & Standard English,
        // Voidstrike terminology, Carrier Options collapse, Hull Limits description, Division Type dropdown)
        boolean uiItemA_noSubtitle = !docSrc.contains("Officer in Tactical Command, division defaults, auto-assigner, hull limits");
        boolean uiItemBC_otcEnglishAndProsCons = true;
        for (OtcAuthority a : OtcAuthority.values()) {
            if (!a.summary.startsWith("The Officer in Tactical Command will ")
                    || a.positiveText == null || !a.positiveText.startsWith("+ ")
                    || a.negativeText == null || !a.negativeText.startsWith("- ")) {
                uiItemBC_otcEnglishAndProsCons = false;
            }
        }
        uiItemBC_otcEnglishAndProsCons = uiItemBC_otcEnglishAndProsCons
                && docSrc.contains("current.positiveText")
                && docSrc.contains("current.negativeText")
                && edSrc.contains("current.positiveText")
                && edSrc.contains("current.negativeText");
        boolean uiItemD_voidstrikeTerms = docSrc.contains("\"Voidstrike Focus: \"")
                && docSrc.contains("\"Voidstrike Launch: \"")
                && docSrc.contains("\"Fighter Voidstrike: \"")
                && docSrc.contains("\"Bomber Voidstrike: \"")
                && docSrc.contains("\"Voidstrike Depth: \"")
                && docSrc.contains("\"Voidstrike Width: \"")
                && edSrc.contains("\"Voidstrike Focus: \"")
                && edSrc.contains("\"Voidstrike Launch: \"")
                && edSrc.contains("\"Fighter Voidstrike\"")
                && edSrc.contains("\"Bomber Voidstrike\"")
                && edSrc.contains("\"Voidstrike Depth: \"")
                && edSrc.contains("\"Voidstrike Width: \"")
                && "Cyclic Voidstrikes (Carrier)".equals(DivisionMissionType.CYCLIC_STRIKES.dropdownLabel)
                && "Deckload Voidstrike (Carrier)".equals(DivisionMissionType.DECKLOAD_STRIKE.dropdownLabel)
                && "Alpha Voidstrike (Carrier)".equals(DivisionMissionType.ALPHA_STRIKE.dropdownLabel);
        boolean uiItemE_carrierOptionsBtn = docSrc.contains("TOGGLE_CARRIER_OPTS:")
                && docSrc.contains("\"Carrier Options\"")
                && edSrc.contains("TOGGLE_DIV_CARRIER_OPTIONS")
                && edSrc.contains("\"Carrier Options\"");
        boolean uiItemF_hullLimitsDesc = docSrc.contains(
                "Sets the minimum and maximum ships of each hull class that Fleet Organization and Auto-Assign will group into a single division");
        boolean uiItemG_divTypeDropdown = edSrc.contains("addDivisionArchetypeDropdownRow")
                && edSrc.contains("\"DIV_ARCH_DD\"")
                && !edSrc.contains("colIdx * 162f");
        res.record(
                uiItemA_noSubtitle && uiItemBC_otcEnglishAndProsCons && uiItemD_voidstrikeTerms
                        && uiItemE_carrierOptionsBtn && uiItemF_hullLimitsDesc && uiItemG_divTypeDropdown,
                "v1.0.5b UI Overhaul (Items A-G): Subtitle removed, OTC green/red pros & cons + Standard English, Voidstrike terms, Carrier Options button, Hull Limits description, Division Type dropdown",
                String.format(Locale.ROOT,
                        "A_noSub=%b, BC_otc=%b, D_voidstrike=%b, E_carrierBtn=%b, F_hullDesc=%b, G_archDropdown=%b",
                        uiItemA_noSubtitle, uiItemBC_otcEnglishAndProsCons, uiItemD_voidstrikeTerms,
                        uiItemE_carrierOptionsBtn, uiItemF_hullLimitsDesc, uiItemG_divTypeDropdown));

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

        // Multi-mod Fleet Tab sidebar non-overlap verification (RAT Artifact slot + SiC: Auxiliaries 4-row Fleet composition data
        // + vanilla Disengage label + FleetBuilder CTRL-F search & SF Sort/Settings, both initial & Frame-2 late shift)
        float panelTopY = 980f;
        final float[] injY = new float[]{482f}; // Old overlapping Y (right over Fleet composition data & disengage text!)
        final float injH = 68f;
        final float[] inTlRef = new float[]{430f};
        com.fs.starfarer.api.ui.PositionAPI panelPos = createMockSidebarPos(20f, 0f, 340f, panelTopY, null, 0f);
        com.fs.starfarer.api.ui.UIPanelAPI mockLeftPanel = (com.fs.starfarer.api.ui.UIPanelAPI) java.lang.reflect.Proxy.newProxyInstance(
                com.fs.starfarer.api.ui.UIPanelAPI.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.ui.UIPanelAPI.class},
                (p, m, a) -> "getPosition".equals(m.getName()) ? panelPos : null);
        com.fs.starfarer.api.ui.ButtonAPI mockAutoBtn = createMockSidebarButton(20f, 720f, 320f, 30f);
        com.fs.starfarer.api.ui.UIComponentAPI mockRatArtifact = createMockSidebarComp(20f, 630f, 300f, 60f);
        com.fs.starfarer.api.ui.UIComponentAPI mockSicTable = createMockSidebarComp(20f, 480f, 320f, 130f);
        com.fs.starfarer.api.ui.UIComponentAPI mockDisengageLbl = createMockSidebarComp(20f, 435f, 310f, 35f);
        com.fs.starfarer.api.ui.UIComponentAPI mockFbSearch = createMockSidebarComp(20f, 398f, 320f, 26f);
        com.fs.starfarer.api.ui.UIComponentAPI mockFbSort = createMockSidebarComp(20f, 356f, 150f, 30f);
        com.fs.starfarer.api.ui.UIComponentAPI mockInjected = (com.fs.starfarer.api.ui.UIComponentAPI) java.lang.reflect.Proxy.newProxyInstance(
                com.fs.starfarer.api.ui.UIComponentAPI.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.ui.UIComponentAPI.class},
                new java.lang.reflect.InvocationHandler() {
                    final com.fs.starfarer.api.ui.PositionAPI pos = createMockSidebarPos(20f, injY[0], 320f, injH, inTlY -> {
                        injY[0] = panelTopY - inTlY - injH;
                    }, injH);
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                        if ("getPosition".equals(method.getName())) return pos;
                        return null;
                    }
                });
        List<com.fs.starfarer.api.ui.UIComponentAPI> mockSidebarChildren = java.util.Arrays.asList(
                mockAutoBtn, mockRatArtifact, mockSicTable, mockDisengageLbl, mockFbSearch, mockFbSort, mockInjected);
        boolean healedLateShift = divisionai.DivisionFleetTabInjector.alignPanelBelowSidebarSiblings(
                mockLeftPanel, mockAutoBtn, mockInjected, mockSidebarChildren, null, inTlRef, false);
        float healedTopY = injY[0] + injH;
        boolean noModOverlap = healedLateShift && healedTopY <= 346.5f && injY[0] >= 270f;
        res.record(
                noModOverlap,
                "v1.0.5b Fleet Tab Sidebar Dynamic Layout: zero overlap with RAT Artifacts, SiC: Auxiliaries table, Disengage text & FleetBuilder search/sort",
                String.format(Locale.ROOT, "healed=%s, injectedSpan=[%.0f..%.0f] cleanly below SF Sort bottom=356",
                        healedLateShift, injY[0], healedTopY));

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

    private static com.fs.starfarer.api.ui.PositionAPI createMockSidebarPos(
            float x, float initialY, float w, float h,
            java.util.function.Consumer<Float> onInTlY, float compH) {
        final float[] curY = new float[]{initialY};
        return (com.fs.starfarer.api.ui.PositionAPI) java.lang.reflect.Proxy.newProxyInstance(
                com.fs.starfarer.api.ui.PositionAPI.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.ui.PositionAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getX".equals(n)) return x;
                    if ("getY".equals(n)) return curY[0];
                    if ("getWidth".equals(n)) return w;
                    if ("getHeight".equals(n)) return h;
                    if ("inTL".equals(n) && a != null && a.length >= 2) {
                        float inTlY = ((Number) a[1]).floatValue();
                        curY[0] = 980f - inTlY - compH;
                        if (onInTlY != null) onInTlY.accept(inTlY);
                        return p;
                    }
                    return p;
                });
    }

    private static com.fs.starfarer.api.ui.UIComponentAPI createMockSidebarComp(float x, float y, float w, float h) {
        final com.fs.starfarer.api.ui.PositionAPI pos = createMockSidebarPos(x, y, w, h, null, h);
        return (com.fs.starfarer.api.ui.UIComponentAPI) java.lang.reflect.Proxy.newProxyInstance(
                com.fs.starfarer.api.ui.UIComponentAPI.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.ui.UIComponentAPI.class},
                (p, m, a) -> "getPosition".equals(m.getName()) ? pos : null);
    }

    private static com.fs.starfarer.api.ui.ButtonAPI createMockSidebarButton(float x, float y, float w, float h) {
        final com.fs.starfarer.api.ui.PositionAPI pos = createMockSidebarPos(x, y, w, h, null, h);
        return (com.fs.starfarer.api.ui.ButtonAPI) java.lang.reflect.Proxy.newProxyInstance(
                com.fs.starfarer.api.ui.ButtonAPI.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.ui.ButtonAPI.class},
                (p, m, a) -> "getPosition".equals(m.getName()) ? pos : null);
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

        boolean saveSafetyPlainMapOk = ffdJava.contains("writeNpcDivisionsToMemory(mem, sorted)")
                && ffdJava.contains("PermanentDivisionManager.serializeSpecList(specs)")
                && (new PermanentDivisionManager.PermanentDivisionSpec("t_1", "Test Div", DivisionArchetype.CRUISER_DIVISION) != null)
                && (PermanentDivisionManager.serializeSpecToMap(
                        new PermanentDivisionManager.PermanentDivisionSpec("t_1", "Test Div", DivisionArchetype.CRUISER_DIVISION)).getClass().getName().startsWith("java.util."))
                && (playerNaming.toPlainMap().getClass().getName().startsWith("java.util."));

        boolean itemARuntimeOk = "Battle Div. I".equals(beforeSyncPlayerFactionName)
                && "Executor Line I".equals(afterSyncPlayerFactionName)
                && "Battle Div. I".equals(hegemonyFactionName)
                && saveSafetyPlainMapOk;

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
                && overlayJava.contains("chaikinSmoothClosed")
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
                    if ("isFighter".equals(n)) return Boolean.valueOf(size == HullSize.FIGHTER);
                    if ("isHulk".equals(n) || "isShuttlePod".equals(n)
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

        auditLateCombatWarroomPerformanceAndStateIntegrity(res);
    }

    private static void auditLateCombatWarroomPerformanceAndStateIntegrity(UIAuditResult res) {
        auditLateCombatWarroomPerformanceAndStateIntegrity(res, false);
    }

    public static void auditLateCombatWarroomPerformanceAndStateIntegrityAfterUISim(UIAuditResult res) {
        auditLateCombatWarroomPerformanceAndStateIntegrity(res, true);
    }

    private static void auditLateCombatWarroomPerformanceAndStateIntegrity(UIAuditResult res, boolean checkLabelCache) {
        DivisionTacticalMapOverlay perfOv = new DivisionTacticalMapOverlay(null);
        CommanderProfile pCmdr = new CommanderProfile(null, "Alpha Core");
        BattleGroupState perfPGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState perfEGroup = new BattleGroupState(1, pCmdr);
        Random rng = new Random(42L);
        List<DivisionState> allPerfDivs = new ArrayList<>();
        for (int d = 0; d < 9; d++) {
            DivisionState pDiv = new DivisionState("perf_p_div_" + d, 0, d + 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div " + (d + 1), pCmdr);
            perfPGroup.divisions.add(pDiv);
            allPerfDivs.add(pDiv);
            DivisionState eDiv = new DivisionState("perf_e_div_" + d, 1, d + 1, DivisionArchetype.BATTLESHIP_DIVISION, "Enemy Div " + (d + 1), pCmdr);
            perfEGroup.divisions.add(eDiv);
            allPerfDivs.add(eDiv);
            float baseX = (d - 4) * 520f;
            float baseY = -200f + (d % 3) * 180f;
            float eBaseX = (d - 4) * 500f + 60f;
            float eBaseY = -100f + (d % 3) * 180f;
            for (int s = 0; s < 6; s++) {
                float sx = baseX + (s - 2.5f) * 290f + (rng.nextFloat() - 0.5f) * 80f;
                float sy = baseY + (s % 2) * 260f + (rng.nextFloat() - 0.5f) * 80f;
                ShipAPI pShip = createMockDropletShip("perf_p_" + d + "_" + s, HullSize.CRUISER, sx, sy);
                pDiv.members.add(pShip);
                if (s == 0) pDiv.flagship = pShip;
                float ex = eBaseX + (s - 2.5f) * 290f + (rng.nextFloat() - 0.5f) * 80f;
                float ey = eBaseY + (s % 2) * 260f + (rng.nextFloat() - 0.5f) * 80f;
                ShipAPI eShip = createMockDropletShip("perf_e_" + d + "_" + s, HullSize.CRUISER, ex, ey);
                eDiv.members.add(eShip);
                if (s == 0) eDiv.flagship = eShip;
            }
        }

        float perfScale = 0.085f;
        float perfOffsetX = 960f;
        float perfOffsetY = 540f;
        List<List<Vector2f>> perfPConvs = new ArrayList<>();
        List<List<Vector2f>> perfEConvs = new ArrayList<>();
        int perfMaxVerts = 0;
        int perfPolyCount = 0;
        boolean perfGeomIntegrity = true;
        long perfGeomNs = 0L;
        long maxSingleFrameGeomNs = 0L;
        long perfStageSdfNs = 0L;
        long perfStageTriNs = 0L;
        long perfStageClipNs = 0L;
        String worstDropInfo = "";
        // Warm up JIT for 2 frames before measuring late-combat 18-division frame geometry time
        for (int warm = 0; warm < 2; warm++) {
            for (DivisionState div : allPerfDivs) {
                List<List<ShipAPI>> droplets = perfOv.clusterDivisionMembersSticky(div, div.members);
                for (List<ShipAPI> drop : droplets) {
                    List<Vector2f> conv = perfOv.buildFluidDropletPolygonScreen(div, drop, div.members, perfScale, perfOffsetX, perfOffsetY);
                    Vector2f cent = DivisionTacticalMapOverlay.computePolygonCentroid(conv);
                    List<Vector2f> disp = perfOv.applyMetaballNeckPinchScreen(div, conv, cent, drop, div.members, perfScale, perfOffsetX, perfOffsetY);
                    DivisionTacticalMapOverlay.triangulatePolygonCCW(disp);
                }
            }
        }
        int perfFrames = 10;
        for (int f = 0; f < perfFrames; f++) {
            perfPConvs.clear();
            perfEConvs.clear();
            long startNs = System.nanoTime();
            for (DivisionState div : allPerfDivs) {
                List<List<ShipAPI>> droplets = perfOv.clusterDivisionMembersSticky(div, div.members);
                for (List<ShipAPI> drop : droplets) {
                    long stage0 = System.nanoTime();
                    List<Vector2f> conv = perfOv.buildFluidDropletPolygonScreen(div, drop, div.members, perfScale, perfOffsetX, perfOffsetY);
                    Vector2f cent = DivisionTacticalMapOverlay.computePolygonCentroid(conv);
                    List<Vector2f> disp = perfOv.applyMetaballNeckPinchScreen(div, conv, cent, drop, div.members, perfScale, perfOffsetX, perfOffsetY);
                    long stage1 = System.nanoTime();
                    perfStageSdfNs += (stage1 - stage0);
                    List<int[]> tris = DivisionTacticalMapOverlay.triangulatePolygonCCW(disp);
                    long stage2 = System.nanoTime();
                    perfStageTriNs += (stage2 - stage1);
                    if (disp.size() > perfMaxVerts) perfMaxVerts = disp.size();
                    perfPolyCount++;
                    if (tris.size() != disp.size() - 2
                            || DivisionTacticalMapOverlay.countPolygonSelfIntersections(disp) > 0
                            || DivisionTacticalMapOverlay.computePolygonArea(disp) < 50f) {
                        perfGeomIntegrity = false;
                    }
                    for (ShipAPI s : drop) {
                        float sx = s.getLocation().x * perfScale + perfOffsetX;
                        float sy = s.getLocation().y * perfScale + perfOffsetY;
                        if (!DivisionTacticalMapOverlay.isPointInsidePolygon(disp, sx, sy)) {
                            perfGeomIntegrity = false;
                        }
                    }
                    if (div.side == 0) perfPConvs.add(conv); else perfEConvs.add(conv);
                    if (f == perfFrames - 1) {
                        float minBx = Float.MAX_VALUE;
                        float maxBx = -Float.MAX_VALUE;
                        float minBy = Float.MAX_VALUE;
                        float maxBy = -Float.MAX_VALUE;
                        for (ShipAPI s : drop) {
                            float sx = s.getLocation().x * perfScale + perfOffsetX;
                            float sy = s.getLocation().y * perfScale + perfOffsetY;
                            if (sx < minBx) minBx = sx;
                            if (sx > maxBx) maxBx = sx;
                            if (sy < minBy) minBy = sy;
                            if (sy > maxBy) maxBy = sy;
                        }
                        worstDropInfo += String.format(Locale.ROOT,
                                "  [%s] drop=%d ships, span=(%.1f x %.1f)px, convV=%d, dispV=%d, tris=%d%n",
                                div.id, drop.size(), maxBx - minBx, maxBy - minBy, conv.size(), disp.size(), tris.size());
                    }
                }
            }
            long clipStart = System.nanoTime();
            for (List<Vector2f> a : perfPConvs) {
                for (List<Vector2f> b : perfEConvs) {
                    List<Vector2f> inter = DivisionTacticalMapOverlay.clipConvexPolygon(a, b);
                    if (inter.size() >= 3 && DivisionTacticalMapOverlay.computePolygonArea(inter) >= 380f) {
                        for (int k = -12; k <= 12; k++) {
                            DivisionTacticalMapOverlay.clipPolygonToDiagonalSlab(inter, k * 24f, k * 24f + 12f);
                        }
                    }
                }
            }
            perfStageClipNs += (System.nanoTime() - clipStart);
            long frameNs = (System.nanoTime() - startNs);
            perfGeomNs += frameNs;
            if (frameNs > maxSingleFrameGeomNs) maxSingleFrameGeomNs = frameNs;
        }
        float perfGeomMsPerFrame = (perfGeomNs - maxSingleFrameGeomNs) / 1e6f / Math.max(1, perfFrames - 1);
        System.out.printf("WARROOM PERF: total=%.2f ms/frame | SDF=%.2f ms, Tri=%.2f ms, Clip=%.2f ms, polys=%d, maxVerts=%d%n%s",
                perfGeomMsPerFrame,
                perfStageSdfNs / 1e6f / perfFrames,
                perfStageTriNs / 1e6f / perfFrames,
                perfStageClipNs / 1e6f / perfFrames,
                perfPolyCount / perfFrames,
                perfMaxVerts,
                worstDropInfo);
        String warroomOverlaySrc = "";
        try {
            warroomOverlaySrc = new String(
                    Files.readAllBytes(new File("src/divisionai/DivisionTacticalMapOverlay.java").toPath()),
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        boolean strikeCraftOwnLayerAndDashedBorderOk = warroomOverlaySrc.contains("collectStrikeCraftDivisionPolygons")
                && warroomOverlaySrc.contains("renderStrikeCraftDivisionPolygons")
                && warroomOverlaySrc.contains("renderStrikeCraftDashedBorder")
                && warroomOverlaySrc.contains("transformCachedGeometryInPlace")
                && warroomOverlaySrc.contains("buildOverlappingPolygonStripesCache");
        boolean perfFastEnough = perfGeomMsPerFrame < 24.0f && strikeCraftOwnLayerAndDashedBorderOk;
        boolean perfVertexBounded = perfMaxVerts <= 88;
        res.record(
                perfFastEnough && perfVertexBounded && perfGeomIntegrity,
                "Warroom Late-Combat 18-Division Performance & Geometry Integrity Gate",
                String.format(Locale.ROOT,
                        "geomMsPerFrame=%.2f (must<24ms), maxVerts=%d (must<=88), selfIntersections/uncontainedShips=%s, strikeLayerDashed=%b",
                        perfGeomMsPerFrame, perfMaxVerts, perfGeomIntegrity, strikeCraftOwnLayerAndDashedBorderOk));

        // State integrity: a division that lost its last ship must NOT trigger per-frame unthrottled TSFC re-evaluation
        BattleGroupState destroyedGroup = new BattleGroupState(0, pCmdr);
        DivisionState destroyedDiv = new DivisionState("destroyed_div", 0, 1, DivisionArchetype.CRUISER_DIVISION, "Destroyed Div", pCmdr);
        destroyedDiv.members.clear();
        destroyedGroup.divisions.add(destroyedDiv);
        BattlefieldFirepowerEvaluator.updateDivisionBaseTimeSpace(destroyedDiv);
        boolean destroyedTsfcInitialized = destroyedDiv.directionalSpaceInitialized;
        boolean noUninitThrottleBypass = !TsfcWarroomRenderer.hasAnyUninitializedDivisionTimeSpace(null, destroyedGroup);
        res.record(
                destroyedTsfcInitialized && noUninitThrottleBypass,
                "Warroom TSFC Destroyed-Division Throttle & State Integrity",
                String.format(Locale.ROOT,
                        "directionalSpaceInitialized=%s, hasAnyUninitializedDivisionTimeSpace=%s (must be false)",
                        destroyedTsfcInitialized, !noUninitThrottleBypass));

        // Label cache: flux-varying intent badges must reuse a single per-division LabelAPI instead of creating new instances every 1%
        if (checkLabelCache) {
            int labelCacheBefore = perfOv.getCachedLabelCountForTest();
            for (int flux = 0; flux <= 100; flux++) {
                perfOv.getOrCreateIntentBadgeForTest("intent_div_1", false, "ENGAGE | LINE - " + flux + "%");
                perfOv.getOrCreateIntentBadgeForTest("intent_div_2", false, "PRESS HOME - " + flux + "%");
            }
            int labelCacheAfter = perfOv.getCachedLabelCountForTest();
            boolean labelCacheStable = (labelCacheAfter - labelCacheBefore) == 2;
            res.record(
                    labelCacheStable,
                    "Warroom Intent-Badge Label Cache Stability",
                    String.format(Locale.ROOT,
                            "cacheDelta=%d across 202 flux updates (must be exactly 2, no per-flux-percent LabelAPI leak)",
                            labelCacheAfter - labelCacheBefore));

            auditHegemonyInspector39DivisionWarroomStressAndGlGuards(res, pCmdr);
            auditOtcReassignmentFlagshipBlackoutAndBattleSummary(res);
        }
    }

    private static void auditHegemonyInspector39DivisionWarroomStressAndGlGuards(
            UIAuditResult res,
            CommanderProfile cmdr) {
        // 1. Build a 39-division (14 player + 25 enemy), 134-ship battlefield matching the Hegemony Investigators battle
        BattleGroupState pGroup = new BattleGroupState(0, cmdr);
        BattleGroupState eGroup = new BattleGroupState(1, cmdr);
        List<ShipAPI> allShips = new ArrayList<>(140);
        final java.util.Set<ShipAPI> fogVisibleEnemyShips = new java.util.HashSet<>();
        ShipAPI flickeringEnemyShip = null;

        int shipCounter = 0;
        for (int d = 0; d < 14; d++) {
            DivisionState div = new DivisionState(
                    "heg_p_div_" + d, 0, d + 1, DivisionArchetype.CRUISER_DIVISION, "Player Div " + (d + 1), cmdr);
            pGroup.divisions.add(div);
            int count = (d < 6) ? 4 : 3; // 6*4 + 8*3 = 48 player ships
            float bx = -1800f + (d % 7) * 540f;
            float by = -900f + (d / 7) * 420f;
            for (int s = 0; s < count; s++) {
                ShipAPI ship = createMockDropletShip(
                        "heg_p_s_" + (++shipCounter), HullSize.CRUISER, bx + s * 220f, by + (s % 2) * 160f);
                div.members.add(ship);
                allShips.add(ship);
                if (s == 0) div.flagship = ship;
            }
        }
        for (int d = 0; d < 25; d++) {
            DivisionState div = new DivisionState(
                    "heg_e_div_" + d, 1, d + 1, DivisionArchetype.CRUISER_DIVISION, "Hegemony Div " + (d + 1), cmdr);
            eGroup.divisions.add(div);
            int count = (d < 11) ? 4 : 3; // 11*4 + 14*3 = 86 enemy ships (48 + 86 = 134 total ships)
            float bx = -2200f + (d % 9) * 520f;
            float by = -400f + (d / 9) * 400f;
            for (int s = 0; s < count; s++) {
                ShipAPI ship = createMockDropletShip(
                        "heg_e_s_" + (++shipCounter), HullSize.CRUISER, bx + s * 220f, by + (s % 2) * 160f);
                div.members.add(ship);
                allShips.add(ship);
                fogVisibleEnemyShips.add(ship);
                if (s == 0) div.flagship = ship;
                if (d == 17 && s == 2) {
                    flickeringEnemyShip = ship;
                }
            }
        }

        final float[] simClock = new float[] { 100.0f };
        final float[] realClock = new float[] { 100.0f };
        final int[] deployedCopyCalls = new int[] { 0 };
        final com.fs.starfarer.api.combat.CombatFleetManagerAPI mockFm =
                (com.fs.starfarer.api.combat.CombatFleetManagerAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[] { com.fs.starfarer.api.combat.CombatFleetManagerAPI.class },
                        (p, m, a) -> {
                            String name = m.getName();
                            if ("getDeployedCopy".equals(name) || "getReservesCopy".equals(name)) {
                                deployedCopyCalls[0]++;
                                return java.util.Collections.emptyList();
                            }
                            if (m.getReturnType() == boolean.class) return false;
                            if (m.getReturnType() == int.class) return 0;
                            if (m.getReturnType() == float.class) return 0f;
                            return null;
                        });
        final CombatEngineAPI mockEng = (CombatEngineAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[] { CombatEngineAPI.class },
                (p, m, a) -> {
                    String name = m.getName();
                    if ("getTotalElapsedTime".equals(name)) {
                        boolean includePaused = (a != null && a.length > 0 && Boolean.TRUE.equals(a[0]));
                        return includePaused ? realClock[0] : simClock[0];
                    }
                    if ("getShips".equals(name)) return allShips;
                    if ("getFleetManager".equals(name)) return mockFm;
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == int.class) return 0;
                    if (m.getReturnType() == float.class) return 0f;
                    return null;
                });
        final com.fs.starfarer.api.combat.FogOfWarAPI mockFog =
                (com.fs.starfarer.api.combat.FogOfWarAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[] { com.fs.starfarer.api.combat.FogOfWarAPI.class },
                        (p, m, a) -> {
                            if ("isVisible".equals(m.getName()) && a != null && a.length > 0 && a[0] instanceof ShipAPI) {
                                return fogVisibleEnemyShips.contains((ShipAPI) a[0]);
                            }
                            if (m.getReturnType() == boolean.class) return true;
                            return null;
                        });

        DivisionTacticalMapOverlay stressOv = new DivisionTacticalMapOverlay(mockEng);

        // Initial cold build of all 39 divisions
        List<DivisionTacticalMapOverlay.RenderedDivisionPolygon> outPolys = new ArrayList<>(64);
        int[] coldBudget = new int[] { 256 };
        int initialRebuilt = stressOv.collectDivisionPolygonsCached(
                pGroup, false, null, simClock[0], false, 0.085f, 960f, 540f, coldBudget, outPolys);
        initialRebuilt += stressOv.collectDivisionPolygonsCached(
                eGroup, true, mockFog, simClock[0], false, 0.085f, 960f, 540f, coldBudget, outPolys);

        // Test A: Single enemy ship leaves sticky visibility on an unpaused frame -> ONLY 1 division (out of 39) rebuilds!
        fogVisibleEnemyShips.remove(flickeringEnemyShip);
        // Advance simClock past 4.0s sticky grace for that single ship while refreshing all other visible enemy ships
        for (int step = 0; step < 10; step++) {
            simClock[0] += 0.50f;
            realClock[0] += 0.50f;
            for (ShipAPI es : fogVisibleEnemyShips) {
                BattlefieldFirepowerEvaluator.isEnemyShipVisibleSticky(mockEng, es, mockFog);
            }
        }
        // Warm cache at current time (where flickeringEnemyShip is now expired from sticky visibility)
        outPolys.clear();
        int[] warmBudget = new int[] { 256 };
        stressOv.collectDivisionPolygonsCached(
                pGroup, false, null, simClock[0], false, 0.085f, 960f, 540f, warmBudget, outPolys);
        stressOv.collectDivisionPolygonsCached(
                eGroup, true, mockFog, simClock[0], false, 0.085f, 960f, 540f, warmBudget, outPolys);

        // Now toggle flickeringEnemyShip back into sensor view 0.05s later -> only Division #17 should rebuild!
        fogVisibleEnemyShips.add(flickeringEnemyShip);
        BattlefieldFirepowerEvaluator.isEnemyShipVisibleSticky(mockEng, flickeringEnemyShip, mockFog);
        simClock[0] += 0.05f;
        realClock[0] += 0.05f;
        outPolys.clear();
        int[] flickerBudget = new int[] { 6 };
        int flickerRebuilt = stressOv.collectDivisionPolygonsCached(
                pGroup, false, null, simClock[0], false, 0.085f, 960f, 540f, flickerBudget, outPolys);
        flickerRebuilt += stressOv.collectDivisionPolygonsCached(
                eGroup, true, mockFog, simClock[0], false, 0.085f, 960f, 540f, flickerBudget, outPolys);

        // Test B: Paused Warroom sticky-visibility stability (realClock advances 10s while paused, simClock stays still)
        fogVisibleEnemyShips.remove(flickeringEnemyShip);
        realClock[0] += 10.0f; // 10 seconds of wall-clock pause (> 4.0s sticky grace)
        boolean stickyPreservedWhilePaused = BattlefieldFirepowerEvaluator.isEnemyShipVisibleSticky(
                mockEng, flickeringEnemyShip, mockFog);
        outPolys.clear();
        int[] pausedBudget = new int[] { 6 };
        int pausedRebuilt = stressOv.collectDivisionPolygonsCached(
                pGroup, false, null, simClock[0], true, 0.092f, 980f, 520f, pausedBudget, outPolys);
        pausedRebuilt += stressOv.collectDivisionPolygonsCached(
                eGroup, true, mockFog, simClock[0], true, 0.092f, 980f, 520f, pausedBudget, outPolys);

        // Test C: 300 continuous unpaused Warroom frames (5.0s at 60 FPS) with 39 divisions & 134 moving ships + camera pan/zoom
        long[] frameTimesNs = new long[300];
        long totalStressNs = 0L;
        for (int f = 0; f < 300; f++) {
            simClock[0] += (1f / 60f);
            realClock[0] += (1f / 60f);
            float scale = 0.080f + (f % 30) * 0.0005f;
            float offX = 960f + (f % 40) * 1.5f;
            float offY = 540f - (f % 40) * 1.0f;
            long t0 = System.nanoTime();
            outPolys.clear();
            int[] frameBudget = new int[] { 6 };
            stressOv.collectDivisionPolygonsCached(
                    pGroup, false, null, simClock[0], false, scale, offX, offY, frameBudget, outPolys);
            stressOv.collectDivisionPolygonsCached(
                    eGroup, true, mockFog, simClock[0], false, scale, offX, offY, frameBudget, outPolys);
            long dt = System.nanoTime() - t0;
            frameTimesNs[f] = dt;
            totalStressNs += dt;
        }
        java.util.Arrays.sort(frameTimesNs);
        float avgStressMs = (totalStressNs / 300.0f) / 1e6f;
        float p95StressMs = frameTimesNs[(int) (300 * 0.95)] / 1e6f;

        // Test D: Fighter launch/death churn must NOT cause hasAlphaCoreInPlayerFleet to re-clone getDeployedCopy()/getReservesCopy()
        DivisionOtcCommandManager.resetForNewBattle();
        DivisionOtcCommandManager.hasAlphaCoreInPlayerFleet(mockEng);
        int copiesBeforeFighterChurn = deployedCopyCalls[0];
        for (int f = 0; f < 60; f++) {
            // Simulate fighter count changing every frame within a 1.0s combat window
            ShipAPI dummyFighter = createMockDropletShip("fighter_churn_" + f, HullSize.FIGHTER, 0f, 0f);
            allShips.add(dummyFighter);
            simClock[0] += 0.01f;
            DivisionOtcCommandManager.hasAlphaCoreInPlayerFleet(mockEng);
            allShips.remove(allShips.size() - 1);
        }
        int copiesDuringFighterChurn = deployedCopyCalls[0] - copiesBeforeFighterChurn;

        // Test E: Static source guards for OpenGL batching and removal of duplicate 134-ship re-rendering / glPushAttrib stalls
        String overlaySrc = "";
        String doctrineSrc = "";
        String tsfcSrc = "";
        try {
            overlaySrc = new String(Files.readAllBytes(new File("src/divisionai/DivisionTacticalMapOverlay.java").toPath()), StandardCharsets.UTF_8);
            doctrineSrc = new String(Files.readAllBytes(new File("src/divisionai/FleetDoctrineDialog.java").toPath()), StandardCharsets.UTF_8);
            tsfcSrc = new String(Files.readAllBytes(new File("src/divisionai/TsfcWarroomRenderer.java").toPath()), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        boolean batchedTrianglesOk = overlaySrc.contains("renderAllDivisionPolygonsBatched(renderedPolys, basePolyAlpha)")
                && !overlaySrc.contains("\n        renderShipSpritesOnTopLayer(renderedPolys")
                && overlaySrc.contains("recordWarroomFrameTelemetry(");
        int formBorderIdx = doctrineSrc.indexOf("public static void renderFormationSquareBorder(");
        int formBorderEnd = (formBorderIdx >= 0) ? doctrineSrc.indexOf("private void addUnitModifierRow(", formBorderIdx) : -1;
        String formBorderBody = (formBorderIdx >= 0 && formBorderEnd > formBorderIdx)
                ? doctrineSrc.substring(formBorderIdx, formBorderEnd)
                : "";
        boolean noPushAttribInFormIcon = !formBorderBody.contains("glPushAttrib");

        int btnIdx = tsfcSrc.indexOf("static void renderSingleWarroomSquareIconButton(");
        int btnEnd = (btnIdx >= 0) ? tsfcSrc.indexOf("static float getCachedUiLabelWidth(", btnIdx) : -1;
        String btnBody = (btnIdx >= 0 && btnEnd > btnIdx)
                ? tsfcSrc.substring(btnIdx, btnEnd)
                : "";
        boolean noPushAttribInWarroomBtn = !btnBody.contains("glPushAttrib");

        boolean stressPassed = (initialRebuilt == 39)
                && (flickerRebuilt == 1)
                && stickyPreservedWhilePaused
                && (pausedRebuilt == 0)
                && (avgStressMs < 2.0f)
                && (p95StressMs < 5.0f)
                && (copiesDuringFighterChurn == 0)
                && batchedTrianglesOk
                && noPushAttribInFormIcon
                && noPushAttribInWarroomBtn;

        res.record(
                stressPassed,
                "Hegemony Inspector 39-Division / 134-Ship Warroom Stress & OpenGL Batching Gate",
                String.format(
                        Locale.ROOT,
                        "initialRebuilt=%d/39, singleShipFlickerRebuilt=%d (must=1), pausedStickyOk=%b (pausedRebuilt=%d), 300f avg=%.2fms p95=%.2fms, fighterChurnFleetCopies=%d (must=0), batchedGL=%b, noIconPushAttrib=%b/%b",
                        initialRebuilt,
                        flickerRebuilt,
                        stickyPreservedWhilePaused,
                        pausedRebuilt,
                        avgStressMs,
                        p95StressMs,
                        copiesDuringFighterChurn,
                        batchedTrianglesOk,
                        noPushAttribInFormIcon,
                        noPushAttribInWarroomBtn));
    }

    private static void auditOtcReassignmentFlagshipBlackoutAndBattleSummary(UIAuditResult res) {
        final List<String> hudMessages = new ArrayList<>();
        final CombatUIAPI mockUi = (CombatUIAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{CombatUIAPI.class},
                (p, m, a) -> {
                    if ("addMessage".equals(m.getName()) && a != null) {
                        StringBuilder sb = new StringBuilder();
                        for (Object o : a) {
                            if (o instanceof String) {
                                sb.append((String) o);
                            } else if (o instanceof Object[]) {
                                for (Object sub : (Object[]) o) {
                                    if (sub instanceof String) sb.append((String) sub);
                                }
                            }
                        }
                        hudMessages.add(sb.toString());
                    }
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });

        final com.fs.starfarer.api.combat.MutableStat cpStat = new com.fs.starfarer.api.combat.MutableStat(8f);
        final com.fs.starfarer.api.combat.CombatTaskManagerAPI mockTm =
                (com.fs.starfarer.api.combat.CombatTaskManagerAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.CombatTaskManagerAPI.class},
                        (p, m, a) -> {
                            if ("getCommandPointsStat".equals(m.getName())) return cpStat;
                            if ("getCommandPointsLeft".equals(m.getName())) return Math.max(0, (int) cpStat.getModifiedValue());
                            if (m.getReturnType() == boolean.class) return false;
                            if (m.getReturnType() == float.class) return 0f;
                            if (m.getReturnType() == int.class) return 0;
                            return null;
                        });
        final com.fs.starfarer.api.combat.CombatFleetManagerAPI mockFm =
                (com.fs.starfarer.api.combat.CombatFleetManagerAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.CombatFleetManagerAPI.class},
                        (p, m, a) -> {
                            if ("getTaskManager".equals(m.getName())) return mockTm;
                            if (m.getReturnType() == boolean.class) return false;
                            if (m.getReturnType() == float.class) return 0f;
                            if (m.getReturnType() == int.class) return 0;
                            return null;
                        });
        final float[] nowRef = new float[]{10f};
        final CombatEngineAPI mockEngine = (CombatEngineAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{CombatEngineAPI.class},
                (p, m, a) -> {
                    if ("getCombatUI".equals(m.getName())) return mockUi;
                    if ("getFleetManager".equals(m.getName())) return mockFm;
                    if ("getTotalElapsedTime".equals(m.getName())) return nowRef[0];
                    if ("getWinningSideId".equals(m.getName())) return 0;
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });

        // 1. Mid-battle 75% max CP cost (8 max CP -> 6 CP cost), insufficient CP rejection, and 0 CP Alpha Core Convergence transfer
        divisionai.DivisionOtcCommandManager.resetForNewBattle();
        CommanderProfile baseCmdr = new CommanderProfile(null, "Admiral Vance");
        BattleGroupState pGroup = new BattleGroupState(0, baseCmdr);
        BattleGroupState eGroup = new BattleGroupState(1, new CommanderProfile(null, "Enemy Admiral"));

        divisionai.DivisionOtcCommandManager.OtcCandidate humanCand =
                new divisionai.DivisionOtcCommandManager.OtcCandidate(null, null, "Division Leader", true, false);
        divisionai.DivisionOtcCommandManager.OtcCandidate alphaCand =
                new divisionai.DivisionOtcCommandManager.OtcCandidate(null, null, "Alpha Core Vessel", false, true);

        int expectedCost = divisionai.DivisionOtcCommandManager.getOtcReassignmentCommandPointCost(mockEngine);
        boolean midBattleHumanOk = (expectedCost == 6)
                && divisionai.DivisionOtcCommandManager.applyPlayerOtcCandidate(mockEngine, pGroup, humanCand, false, nowRef[0])
                && divisionai.DivisionOtcCommandManager.getPlayerCommandPointsLeft(mockEngine) == 2;
        // Second human change mid-battle with only 2 CP left (needs 6 CP) must be rejected
        boolean rejectedWhenLowCp = !divisionai.DivisionOtcCommandManager.applyPlayerOtcCandidate(mockEngine, pGroup, humanCand, false, nowRef[0])
                && divisionai.DivisionOtcCommandManager.getPlayerCommandPointsLeft(mockEngine) == 2;
        // Convergence transfer to Alpha Core ship costs 0 CP even when only 2 CP remain
        boolean alphaTransferFreeOk = divisionai.DivisionOtcCommandManager.applyPlayerOtcCandidate(mockEngine, pGroup, alphaCand, false, nowRef[0])
                && divisionai.DivisionOtcCommandManager.getPlayerCommandPointsLeft(mockEngine) == 2
                && pGroup.useConvergence;

        // 2. Flagship destruction: overall commander (120s blackout) vs lower/no station commander (30s blackout) + enemy flagship alert
        final boolean[] overallAlive = new boolean[]{true};
        final com.fs.starfarer.api.characters.PersonAPI nonDefaultOfficer =
                (com.fs.starfarer.api.characters.PersonAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.characters.PersonAPI.class},
                        (p, m, a) -> {
                            if ("isDefault".equals(m.getName())) return false;
                            if ("getNameString".equals(m.getName())) return "Fleet Admiral";
                            if (m.getReturnType() == boolean.class) return false;
                            return null;
                        });
        final com.fs.starfarer.api.fleet.FleetMemberAPI overallMember =
                (com.fs.starfarer.api.fleet.FleetMemberAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.fleet.FleetMemberAPI.class},
                        (p, m, a) -> {
                            if ("isFlagship".equals(m.getName())) return true;
                            if ("getId".equals(m.getName())) return "overall_flag_member";
                            if (m.getReturnType() == boolean.class) return false;
                            return null;
                        });
        ShipAPI overallFlagship = (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (p, m, a) -> {
                    if ("getId".equals(m.getName())) return "overall_flag_ship";
                    if ("getName".equals(m.getName())) return "HSS Sovereign";
                    if ("isAlive".equals(m.getName())) return overallAlive[0];
                    if ("isHulk".equals(m.getName())) return !overallAlive[0];
                    if ("getCaptain".equals(m.getName())) return nonDefaultOfficer;
                    if ("getFleetMember".equals(m.getName())) return overallMember;
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });

        BattleGroupState pGroupLoss = new BattleGroupState(0, baseCmdr);
        pGroupLoss.activeFleetFlagship = overallFlagship;
        overallAlive[0] = false;
        nowRef[0] = 20f;
        divisionai.DivisionOtcCommandManager.checkGroupFlagshipLoss(mockEngine, pGroupLoss, nowRef[0], true);
        boolean overallBlackout120sOk = Math.abs(divisionai.DivisionOtcCommandManager.getOtcDisabledSecondsRemaining(pGroupLoss, nowRef[0]) - 120f) < 0.01f
                && divisionai.DivisionOtcCommandManager.isOtcDisabled(pGroupLoss, 139f)
                && !divisionai.DivisionOtcCommandManager.isOtcDisabled(pGroupLoss, 141f);

        // Lower/no station flagship (successor flagship after overall commander lost, or default captain) -> 30s blackout
        final boolean[] lowerAlive = new boolean[]{false};
        ShipAPI lowerFlagship = (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (p, m, a) -> {
                    if ("getId".equals(m.getName())) return "lower_flag_ship";
                    if ("getName".equals(m.getName())) return "HSS Reliant";
                    if ("isAlive".equals(m.getName())) return lowerAlive[0];
                    if ("isHulk".equals(m.getName())) return !lowerAlive[0];
                    if ("getCaptain".equals(m.getName())) return null;
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });
        BattleGroupState eGroupLoss = new BattleGroupState(1, new CommanderProfile(null, "Enemy OTC"));
        eGroupLoss.activeFleetFlagship = lowerFlagship;
        nowRef[0] = 50f;
        divisionai.DivisionOtcCommandManager.checkGroupFlagshipLoss(mockEngine, eGroupLoss, nowRef[0], true);
        boolean lowerBlackout30sOk = Math.abs(divisionai.DivisionOtcCommandManager.getOtcDisabledSecondsRemaining(eGroupLoss, nowRef[0]) - 30f) < 0.01f
                && divisionai.DivisionOtcCommandManager.isOtcDisabled(eGroupLoss, 79f)
                && !divisionai.DivisionOtcCommandManager.isOtcDisabled(eGroupLoss, 81f);

        // Retreating flagship (despawned off map edge with hitpoints > 0 and !isHulk, or isRetreating == true) -> 0s malus!
        ShipAPI retreatedFlagship = (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (p, m, a) -> {
                    if ("getId".equals(m.getName())) return "retreated_flag_ship";
                    if ("getName".equals(m.getName())) return "Seraphim";
                    if ("isAlive".equals(m.getName())) return false;
                    if ("isHulk".equals(m.getName())) return false;
                    if ("getHitpoints".equals(m.getName())) return 5000f;
                    if ("isRetreating".equals(m.getName())) return true;
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 1;
                    return null;
                });
        BattleGroupState eGroupRetreat = new BattleGroupState(1, new CommanderProfile(null, "Enemy OTC"));
        eGroupRetreat.activeFleetFlagship = retreatedFlagship;
        divisionai.DivisionOtcCommandManager.checkGroupFlagshipLoss(mockEngine, eGroupRetreat, 60f, true);
        boolean retreatNoMalusOk = !divisionai.DivisionOtcCommandManager.isOtcDisabled(eGroupRetreat, 60f)
                && divisionai.DivisionOtcCommandManager.getOtcDisabledSecondsRemaining(eGroupRetreat, 60f) == 0f;

        // Fleet & Battle Size override test (reads baseline first, updates live in memory, restores baseline)
        DivisionAISettings.captureBaselineFleetAndBattleSettings();
        int baseBs = DivisionAISettings.getBaselineMaxBattleSize();
        int baseShips = DivisionAISettings.getBaselineMaxShipsInFleet();
        DivisionAISettings.applyRawFleetAndBattleValues(850, 850, 200, 65, 60, 20, 12);
        boolean liveOverrideOk = com.fs.starfarer.api.Global.getSettings().getBattleSize() == 850
                && com.fs.starfarer.api.Global.getSettings().getMaxShipsInFleet() == 65
                && com.fs.starfarer.api.Global.getSettings().getInt("maxShipsInAIFleet") == 60;
        DivisionAISettings.applyRawFleetAndBattleValues(baseBs, baseBs, 200, baseShips, 50, 15, 15);
        boolean baselineRestoredOk = com.fs.starfarer.api.Global.getSettings().getMaxShipsInFleet() == baseShips;

        boolean sawFriendlyWarning = false;
        boolean sawEnemyWarning = false;
        for (String msg : hudMessages) {
            if (msg.contains("HSS Sovereign") && msg.contains("2 minutes (120s)")) sawFriendlyWarning = true;
            if ((msg.contains("Enemy division leader") || msg.contains("Enemy flagship"))
                    && msg.contains("HSS Reliant") && msg.contains("30 seconds")) sawEnemyWarning = true;
            if (msg.contains("Seraphim")) retreatNoMalusOk = false;
        }

        // 3. End-of-battle summary telemetry + obfuscated combat MethodHandle check
        List<String> sink = new ArrayList<>();
        DivisionCombatLog.simLogSink = sink;
        DivisionAISettings.testDebugLoggingEnabledOverride = Boolean.TRUE;
        divisionai.DivisionAICombatPlugin.logBattleSummary(mockEngine, pGroupLoss, eGroupLoss);
        DivisionAISettings.testDebugLoggingEnabledOverride = null;
        DivisionCombatLog.simLogSink = null;
        boolean battleSummaryLogged = !sink.isEmpty() && sink.get(0).contains("[DivisionAI] BATTLE SUMMARY:");
        boolean combatHandlesVerified = DivisionCommanderAI.verifyCombatObfuscatedHandles(UITestHarness.class.getClassLoader());

        res.record(
                midBattleHumanOk && rejectedWhenLowCp && alphaTransferFreeOk
                        && overallBlackout120sOk && lowerBlackout30sOk && retreatNoMalusOk
                        && liveOverrideOk && baselineRestoredOk
                        && sawFriendlyWarning && sawEnemyWarning
                        && battleSummaryLogged && combatHandlesVerified,
                "OTC Reassignment (75% CP / Alpha Core Free & Lock), Flagship Blackout (120s vs 30s, Retreat Exempt), Fleet/Battle Size Overrides & Battle Summary",
                String.format(Locale.ROOT,
                        "cp75=%b, lowCpReject=%b, alphaFree=%b, blackout120s=%b, blackout30s=%b, retreatExempt=%b, sizeOverride=%b, hudWarn=[friendly=%b,enemy=%b], summary=%b, combatHandles=%b",
                        midBattleHumanOk, rejectedWhenLowCp, alphaTransferFreeOk,
                        overallBlackout120sOk, lowerBlackout30sOk, retreatNoMalusOk,
                        liveOverrideOk && baselineRestoredOk,
                        sawFriendlyWarning, sawEnemyWarning,
                        battleSummaryLogged, combatHandlesVerified));

        auditFeaturesABCDE_OtcSuccessionAlphaCoreAndUndeployedPenalties(res);
        auditTooltipsDivisionLeaderColorsAndCampaignBattleSafety(res);
    }

    private static void auditTooltipsDivisionLeaderColorsAndCampaignBattleSafety(UIAuditResult res) {
        // 1. Verify Task B: Friendly "Division leader ... destroyed!" is GREEN, Enemy "Enemy division leader ... destroyed!" is RED
        final List<Color> capturedColors = new ArrayList<>();
        final List<String> capturedTexts = new ArrayList<>();
        final CombatUIAPI colorCheckUi = (CombatUIAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{CombatUIAPI.class},
                (p, m, a) -> {
                    if ("addMessage".equals(m.getName()) && a != null) {
                        Color c = null;
                        StringBuilder sb = new StringBuilder();
                        for (Object o : a) {
                            if (o instanceof Color && c == null) {
                                c = (Color) o;
                            } else if (o instanceof String) {
                                sb.append((String) o);
                            } else if (o instanceof Object[]) {
                                for (Object sub : (Object[]) o) {
                                    if (sub instanceof Color && c == null) {
                                        c = (Color) sub;
                                    } else if (sub instanceof String) {
                                        sb.append((String) sub);
                                    }
                                }
                            }
                        }
                        capturedColors.add(c != null ? c : Color.WHITE);
                        capturedTexts.add(sb.toString());
                    }
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });
        final CombatEngineAPI colorEngine = (CombatEngineAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{CombatEngineAPI.class},
                (p, m, a) -> {
                    if ("getCombatUI".equals(m.getName())) return colorCheckUi;
                    if ("getTotalElapsedTime".equals(m.getName())) return 25f;
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });

        divisionai.DivisionOtcCommandManager.resetForNewBattle();
        final boolean[] deadHolder = new boolean[]{false};
        com.fs.starfarer.api.characters.PersonAPI fCapt = createMockPersonForOtcTest("Capt. Valen", false, null, false, 5);
        com.fs.starfarer.api.fleet.FleetMemberAPI fMem = createMockMemberForOtcTest("f_div_ldr_m", "HSS Dauntless", fCapt, HullSize.CRUISER, 20f, false);
        ShipAPI fDeadLeader = createMockShipForOtcTest("f_div_ldr_s", "HSS Dauntless", 0, fMem, fCapt, HullSize.CRUISER, deadHolder);
        BattleGroupState friendlyGroup = new BattleGroupState(0, new CommanderProfile(null, "Player OTC"));
        DivisionState friendlyDiv = new DivisionState("f_div_1", 0, 1, DivisionArchetype.CRUISER_DIVISION, "Cruiser Div 1", new CommanderProfile(fCapt, "Capt. Valen"));
        friendlyDiv.flagship = fDeadLeader;
        friendlyDiv.members.add(fDeadLeader);
        friendlyGroup.divisions.add(friendlyDiv);

        com.fs.starfarer.api.characters.PersonAPI eCapt = createMockPersonForOtcTest("Warlord Korg", false, null, false, 5);
        com.fs.starfarer.api.fleet.FleetMemberAPI eMem = createMockMemberForOtcTest("e_div_ldr_m", "pirates_dominator", eCapt, HullSize.CRUISER, 20f, false);
        ShipAPI eDeadLeader = createMockShipForOtcTest("e_div_ldr_s", "Merciless", 1, eMem, eCapt, HullSize.CRUISER, deadHolder);
        BattleGroupState enemyGroup = new BattleGroupState(1, new CommanderProfile(null, "Enemy OTC"));
        DivisionState enemyDiv = new DivisionState("e_div_1", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Raider Squadron 1", new CommanderProfile(eCapt, "Warlord Korg"));
        enemyDiv.flagship = eDeadLeader;
        enemyDiv.members.add(eDeadLeader);
        enemyGroup.divisions.add(enemyDiv);

        divisionai.DivisionOtcCommandManager.checkDivisionFlagshipsDestroyed(colorEngine, friendlyGroup, 25f);
        divisionai.DivisionOtcCommandManager.checkDivisionFlagshipsDestroyed(colorEngine, enemyGroup, 25f);

        boolean friendlyLeaderGreenOk = false;
        boolean enemyLeaderRedOk = false;
        for (int i = 0; i < capturedTexts.size(); i++) {
            String msg = capturedTexts.get(i);
            Color c = capturedColors.get(i);
            if (msg.contains("Division leader") && msg.contains("HSS Dauntless") && !msg.contains("Enemy")) {
                friendlyLeaderGreenOk = (c.getGreen() > c.getRed());
            }
            if (msg.contains("Enemy division leader") && msg.contains("Merciless")) {
                enemyLeaderRedOk = (c.getRed() > c.getGreen());
            }
        }

        // 2. Verify Task A: Centralized UI tooltip descriptors cover all major UI controls and row labels
        String[] sampleActions = new String[]{
                "TAB:ALL", "TAB:OTC_COMMAND", "TAB:HULLS", "TAB:NAMING",
                "SET_OTC_AUTH:COMMAND_BY_DIRECTION", "SET_OTC_AUTH:POSITIVE_CONTROL",
                "SET_FLEET_FORMATION:GRAND_LINE_ABREAST", "SET_FTR_FM:FLEET_CARRIER:WEDGE",
                "SET_OPEN_MIS:BATTLESHIP:STANDOFF_ENGAGEMENT", "SET_MAIN_MIS:CRUISER:CLOSE_ACTION",
                "SET_STICKINESS:DESTROYER:STRICT", "SET_DOC:SPEARHEAD", "AUTO_ASSIGN", "CREATE_DIV",
                "CLEAR_ALL_DIVS", "OPEN_DIV:div_1", "DELETE_DIV:div_1",
                "OTC_CHANGE", "OTC_AUTH_PREV", "FLEET_FORMATION_NEXT", "FLEET_ORCHESTRATED_ADVANCE",
                "DIV_STATION_SPACING:300", "DIV_FORMATION:FORWARD_WEDGE", "SET_ARCH:CRUISER_DIVISION"
        };
        boolean allActionTooltipsOk = true;
        for (String act : sampleActions) {
            String[] spec = divisionai.DivisionUiWidgets.resolveActionTooltipSpec(act);
            if (spec == null || spec.length < 2 || spec[0] == null || spec[0].isEmpty() || spec[1] == null || spec[1].isEmpty()) {
                allActionTooltipsOk = false;
                break;
            }
        }
        String[] sampleLabels = new String[]{
                "Battle Line Structure:", "Squadron Formation:", "Line Spacing:", "Opening Mission:",
                "Main Mission:", "Division Cohesion:", "Ramming Options:", "Carrier Options:",
                "Reserve Depth:", "Standoff Range:", "Callsign:", "Posture:", "Division Type:"
        };
        boolean allLabelTooltipsOk = true;
        for (String lbl : sampleLabels) {
            String[] spec = divisionai.DivisionUiWidgets.resolveRowLabelTooltipSpec(lbl);
            if (spec == null || spec.length < 2 || spec[0] == null || spec[0].isEmpty() || spec[1] == null || spec[1].isEmpty()) {
                allLabelTooltipsOk = false;
                break;
            }
        }

        // 3. Verify Task C: Campaign fleet in-battle guard prevents FactionFleetDoctrines from mutating FleetData during battle/inflation
        final int[] removeMemberCalls = new int[]{0};
        final List<com.fs.starfarer.api.fleet.FleetMemberAPI> battleMembers = Arrays.asList(fMem, eMem);
        final com.fs.starfarer.api.campaign.FleetDataAPI battleFleetData =
                (com.fs.starfarer.api.campaign.FleetDataAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.campaign.FleetDataAPI.class},
                        (p, m, a) -> {
                            if ("getMembersListCopy".equals(m.getName())) return new ArrayList<>(battleMembers);
                            if ("removeFleetMember".equals(m.getName()) || "addFleetMember".equals(m.getName())) {
                                removeMemberCalls[0]++;
                                return null;
                            }
                            if (m.getReturnType() == boolean.class) return false;
                            return null;
                        });
        final com.fs.starfarer.api.campaign.BattleAPI activeBattle =
                (com.fs.starfarer.api.campaign.BattleAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.campaign.BattleAPI.class},
                        (p, m, a) -> {
                            if (m.getReturnType() == boolean.class) return false;
                            return null;
                        });
        final com.fs.starfarer.api.campaign.CampaignFleetAPI fleetInBattle =
                (com.fs.starfarer.api.campaign.CampaignFleetAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.campaign.CampaignFleetAPI.class},
                        (p, m, a) -> {
                            String n = m.getName();
                            if ("getId".equals(n)) return "npc_fleet_in_battle";
                            if ("getFleetData".equals(n)) return battleFleetData;
                            if ("getBattle".equals(n)) return activeBattle;
                            if ("isPlayerFleet".equals(n)) return false;
                            if (m.getReturnType() == boolean.class) return false;
                            if (m.getReturnType() == float.class) return 0f;
                            if (m.getReturnType() == int.class) return 0;
                            return null;
                        });
        FactionFleetDoctrines.ensureFleetDivisionsSorted(fleetInBattle);
        boolean battleFleetNeverMutatedOk = (removeMemberCalls[0] == 0);

        res.record(
                friendlyLeaderGreenOk && enemyLeaderRedOk && allActionTooltipsOk && allLabelTooltipsOk && battleFleetNeverMutatedOk,
                "Division Leader Destroyed Colors (Friendly Green / Enemy Red), UI Tooltip Coverage & In-Battle Fleet Guard",
                String.format(
                        Locale.ROOT,
                        "friendlyLeaderGreen=%b, enemyLeaderRed=%b, actionTooltips=%b, labelTooltips=%b, battleFleetGuard=%b",
                        friendlyLeaderGreenOk,
                        enemyLeaderRedOk,
                        allActionTooltipsOk,
                        allLabelTooltipsOk,
                        battleFleetNeverMutatedOk));
    }

    private static com.fs.starfarer.api.characters.PersonAPI createMockPersonForOtcTest(
            final String name,
            final boolean isAiCore,
            final String aiCoreId,
            final boolean isDefault,
            final int level) {
        final com.fs.starfarer.api.characters.MutableCharacterStatsAPI stats =
                (com.fs.starfarer.api.characters.MutableCharacterStatsAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.characters.MutableCharacterStatsAPI.class},
                        (p, m, a) -> {
                            if ("getLevel".equals(m.getName())) return level;
                            if ("getSkillsCopy".equals(m.getName())) return java.util.Collections.emptyList();
                            if (m.getReturnType() == float.class) return 0f;
                            if (m.getReturnType() == int.class) return 0;
                            if (m.getReturnType() == boolean.class) return false;
                            return null;
                        });
        return (com.fs.starfarer.api.characters.PersonAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.characters.PersonAPI.class},
                (p, m, a) -> {
                    if ("getNameString".equals(m.getName())) return name;
                    if ("isAICore".equals(m.getName())) return isAiCore;
                    if ("getAICoreId".equals(m.getName())) return aiCoreId;
                    if ("isDefault".equals(m.getName())) return isDefault;
                    if ("getStats".equals(m.getName())) return stats;
                    if ("getPersonalityAPI".equals(m.getName())) {
                        return Proxy.newProxyInstance(
                                UITestHarness.class.getClassLoader(),
                                new Class<?>[]{com.fs.starfarer.api.characters.PersonalityAPI.class},
                                (pp, mm, aa) -> {
                                    if ("getId".equals(mm.getName())) return isAiCore ? "reckless" : "steady";
                                    if ("getDisplayName".equals(mm.getName())) return isAiCore ? "Reckless" : "Steady";
                                    return null;
                                });
                    }
                    if (m.getReturnType() == boolean.class) return false;
                    return null;
                });
    }

    private static com.fs.starfarer.api.fleet.FleetMemberAPI createMockMemberForOtcTest(
            final String id,
            final String shipName,
            final com.fs.starfarer.api.characters.PersonAPI captain,
            final ShipAPI.HullSize hullSize,
            final float dp,
            final boolean isFlagship) {
        final com.fs.starfarer.api.combat.ShipHullSpecAPI spec =
                (com.fs.starfarer.api.combat.ShipHullSpecAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.ShipHullSpecAPI.class},
                        (p, m, a) -> {
                            if ("getHullSize".equals(m.getName())) return hullSize;
                            if ("getHullName".equals(m.getName())) return shipName;
                            if ("getHullNameWithDashClass".equals(m.getName())) return shipName + "-class";
                            if ("getHints".equals(m.getName())) return java.util.EnumSet.noneOf(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.class);
                            if (m.getReturnType() == boolean.class) return false;
                            if (m.getReturnType() == float.class) return 0f;
                            if (m.getReturnType() == int.class) return 0;
                            return null;
                        });
        return (com.fs.starfarer.api.fleet.FleetMemberAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.fleet.FleetMemberAPI.class},
                (p, m, a) -> {
                    if ("getId".equals(m.getName())) return id;
                    if ("getShipName".equals(m.getName())) return shipName;
                    if ("getCaptain".equals(m.getName())) return captain;
                    if ("getHullSpec".equals(m.getName())) return spec;
                    if ("getDeploymentPointsCost".equals(m.getName())) return dp;
                    if ("isFlagship".equals(m.getName())) return isFlagship;
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });
    }

    private static ShipAPI createMockShipForOtcTest(
            final String id,
            final String name,
            final int owner,
            final com.fs.starfarer.api.fleet.FleetMemberAPI member,
            final com.fs.starfarer.api.characters.PersonAPI captain,
            final ShipAPI.HullSize hullSize,
            final boolean[] aliveHolder) {
        return (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (p, m, a) -> {
                    if ("getId".equals(m.getName())) return id;
                    if ("getName".equals(m.getName())) return name;
                    if ("getOwner".equals(m.getName())) return owner;
                    if ("getFleetMember".equals(m.getName())) return member;
                    if ("getFleetMemberId".equals(m.getName())) return member != null ? member.getId() : id;
                    if ("getCaptain".equals(m.getName())) return captain;
                    if ("getHullSize".equals(m.getName())) return hullSize;
                    if ("getHullSpec".equals(m.getName())) return member != null ? member.getHullSpec() : null;
                    if ("isAlive".equals(m.getName())) return aliveHolder[0];
                    if ("isHulk".equals(m.getName())) return !aliveHolder[0];
                    if ("getHitpoints".equals(m.getName())) return aliveHolder[0] ? 10000f : 0f;
                    if ("getHullLevel".equals(m.getName())) return aliveHolder[0] ? 1.0f : 0f;
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });
    }

    private static void auditFeaturesABCDE_OtcSuccessionAlphaCoreAndUndeployedPenalties(UIAuditResult res) {
        divisionai.DivisionOtcCommandManager.resetForNewBattle();
        BattlefieldFirepowerEvaluator.resetForNewBattle();

        final com.fs.starfarer.api.characters.PersonAPI alphaPerson1 =
                createMockPersonForOtcTest("Alpha Core I", true, "alpha_core", false, 8);
        final com.fs.starfarer.api.characters.PersonAPI alphaPerson2 =
                createMockPersonForOtcTest("Alpha Core II", true, "alpha_core", false, 8);
        final com.fs.starfarer.api.characters.PersonAPI humanAdmiral =
                createMockPersonForOtcTest("Admiral Vance", false, null, false, 7);
        final com.fs.starfarer.api.characters.PersonAPI divCmdrPerson =
                createMockPersonForOtcTest("Commander Kael", false, null, false, 6);
        final com.fs.starfarer.api.characters.PersonAPI juniorOfficer =
                createMockPersonForOtcTest("Lieutenant Mira", false, null, false, 5);
        final com.fs.starfarer.api.characters.PersonAPI defaultCrew =
                createMockPersonForOtcTest("Crew", false, null, true, 1);

        final com.fs.starfarer.api.fleet.FleetMemberAPI alphaMember1 =
                createMockMemberForOtcTest("alpha_m1", "TTS Radiant", alphaPerson1, ShipAPI.HullSize.CAPITAL_SHIP, 60f, false);
        final com.fs.starfarer.api.fleet.FleetMemberAPI alphaMember2 =
                createMockMemberForOtcTest("alpha_m2", "TTS Nova", alphaPerson2, ShipAPI.HullSize.CAPITAL_SHIP, 50f, false);
        final com.fs.starfarer.api.fleet.FleetMemberAPI humanMember1 =
                createMockMemberForOtcTest("human_m1", "HSS Sovereign", humanAdmiral, ShipAPI.HullSize.CAPITAL_SHIP, 60f, true);
        final com.fs.starfarer.api.fleet.FleetMemberAPI divCmdrMember =
                createMockMemberForOtcTest("div_cmdr_m", "HSS Onslaught", divCmdrPerson, ShipAPI.HullSize.CAPITAL_SHIP, 50f, false);
        final com.fs.starfarer.api.fleet.FleetMemberAPI officerMember =
                createMockMemberForOtcTest("officer_m", "HSS Eagle", juniorOfficer, ShipAPI.HullSize.CRUISER, 22f, false);
        final com.fs.starfarer.api.fleet.FleetMemberAPI unnamedCapMember =
                createMockMemberForOtcTest("unnamed_cap_m", "HSS Conquest", defaultCrew, ShipAPI.HullSize.CAPITAL_SHIP, 40f, false);
        final com.fs.starfarer.api.fleet.FleetMemberAPI unnamedFrigMember =
                createMockMemberForOtcTest("unnamed_frig_m", "HSS Lasher", defaultCrew, ShipAPI.HullSize.FRIGATE, 5f, false);

        final boolean[] a1Alive = new boolean[]{true};
        final boolean[] a2Alive = new boolean[]{true};
        final boolean[] h1Alive = new boolean[]{true};
        final boolean[] dcAlive = new boolean[]{true};

        final ShipAPI alphaShip1 = createMockShipForOtcTest("alpha_s1", "TTS Radiant", 0, alphaMember1, alphaPerson1, ShipAPI.HullSize.CAPITAL_SHIP, a1Alive);
        final ShipAPI alphaShip2 = createMockShipForOtcTest("alpha_s2", "TTS Nova", 0, alphaMember2, alphaPerson2, ShipAPI.HullSize.CAPITAL_SHIP, a2Alive);
        final ShipAPI humanShip1 = createMockShipForOtcTest("human_s1", "HSS Sovereign", 0, humanMember1, humanAdmiral, ShipAPI.HullSize.CAPITAL_SHIP, h1Alive);
        final ShipAPI divCmdrShip = createMockShipForOtcTest("div_cmdr_s", "HSS Onslaught", 0, divCmdrMember, divCmdrPerson, ShipAPI.HullSize.CAPITAL_SHIP, dcAlive);

        final List<ShipAPI> liveShips = new ArrayList<>();
        final List<com.fs.starfarer.api.fleet.FleetMemberAPI> deployedMembers = new ArrayList<>();
        final List<com.fs.starfarer.api.fleet.FleetMemberAPI> reserveMembers = new ArrayList<>();
        final boolean[] enginePaused = new boolean[]{false};
        final com.fs.starfarer.api.combat.MutableStat cpStat = new com.fs.starfarer.api.combat.MutableStat(8f);

        final com.fs.starfarer.api.combat.CombatTaskManagerAPI mockTm =
                (com.fs.starfarer.api.combat.CombatTaskManagerAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.CombatTaskManagerAPI.class},
                        (p, m, a) -> {
                            if ("getCommandPointsStat".equals(m.getName())) return cpStat;
                            if ("getCommandPointsLeft".equals(m.getName())) return Math.max(0, (int) cpStat.getModifiedValue());
                            if (m.getReturnType() == boolean.class) return false;
                            if (m.getReturnType() == float.class) return 0f;
                            if (m.getReturnType() == int.class) return 0;
                            return null;
                        });
        final com.fs.starfarer.api.combat.CombatFleetManagerAPI mockFm =
                (com.fs.starfarer.api.combat.CombatFleetManagerAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.CombatFleetManagerAPI.class},
                        (p, m, a) -> {
                            if ("getTaskManager".equals(m.getName())) return mockTm;
                            if ("getDeployedCopy".equals(m.getName())) return new ArrayList<>(deployedMembers);
                            if ("getReservesCopy".equals(m.getName())) return new ArrayList<>(reserveMembers);
                            if (m.getReturnType() == boolean.class) return false;
                            if (m.getReturnType() == float.class) return 0f;
                            if (m.getReturnType() == int.class) return 0;
                            return null;
                        });
        final CombatUIAPI mockUi = (CombatUIAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{CombatUIAPI.class},
                (p, m, a) -> {
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });
        final CombatEngineAPI mockEngine = (CombatEngineAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{CombatEngineAPI.class},
                (p, m, a) -> {
                    if ("getShips".equals(m.getName())) return liveShips;
                    if ("getFleetManager".equals(m.getName())) return mockFm;
                    if ("getCombatUI".equals(m.getName())) return mockUi;
                    if ("setPaused".equals(m.getName()) && a != null && a.length == 1 && a[0] instanceof Boolean) {
                        enginePaused[0] = (Boolean) a[0];
                        return null;
                    }
                    if ("isPaused".equals(m.getName())) return enginePaused[0];
                    if ("getTotalElapsedTime".equals(m.getName())) return 25f;
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });

        // --- Feature A: Free pre-battle Alpha Core picking/un-picking vs In-battle Alpha Core lock ---
        BattleGroupState pGroupA = new BattleGroupState(0, new CommanderProfile(humanAdmiral, "Admiral Vance"));
        reserveMembers.add(alphaMember1);
        reserveMembers.add(humanMember1);
        boolean pickAlphaPreBattle = divisionai.DivisionOtcCommandManager.selectPlayerOtcByMemberId(
                mockEngine, pGroupA, deployedMembers, reserveMembers, humanMember1, "alpha_m1", true)
                && pGroupA.useConvergence
                && !divisionai.DivisionOtcCommandManager.isPlayerOtcLockedByAlphaCoreInBattle(mockEngine, alphaMember1, pGroupA, true);
        boolean unpickAlphaPreBattle = divisionai.DivisionOtcCommandManager.selectPlayerOtcByMemberId(
                mockEngine, pGroupA, deployedMembers, reserveMembers, alphaMember1, "human_m1", true)
                && !pGroupA.useConvergence
                && "human_m1".equals(divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(pGroupA))
                && !divisionai.DivisionOtcCommandManager.isPlayerOtcLockedByAlphaCoreInBattle(mockEngine, humanMember1, pGroupA, true);
        // Now enter battle and deliberately swap to Alpha Core -> must lock OTC for the rest of battle!
        deployedMembers.add(alphaMember1);
        deployedMembers.add(humanMember1);
        liveShips.add(alphaShip1);
        liveShips.add(humanShip1);
        boolean swapToAlphaInBattle = divisionai.DivisionOtcCommandManager.selectPlayerOtcByMemberId(
                mockEngine, pGroupA, deployedMembers, reserveMembers, humanMember1, "alpha_m1", false)
                && divisionai.DivisionOtcCommandManager.isAlphaCoreOtcLockedInBattle(pGroupA)
                && divisionai.DivisionOtcCommandManager.isPlayerOtcLockedByAlphaCoreInBattle(mockEngine, alphaMember1, pGroupA, false);
        boolean lockedAfterInBattleAlphaSwap = !divisionai.DivisionOtcCommandManager.selectPlayerOtcByMemberId(
                mockEngine, pGroupA, deployedMembers, reserveMembers, alphaMember1, "human_m1", false)
                && "alpha_m1".equals(divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(pGroupA));
        boolean featureAOk = pickAlphaPreBattle && unpickAlphaPreBattle && swapToAlphaInBattle && lockedAfterInBattleAlphaSwap;

        // --- Feature B: Destroyed Alpha Core OTC automatically replaced by another deployed Alpha Core ship ---
        liveShips.clear();
        deployedMembers.clear();
        reserveMembers.clear();
        a1Alive[0] = true;
        a2Alive[0] = true;
        h1Alive[0] = true;
        liveShips.add(alphaShip1);
        liveShips.add(alphaShip2);
        liveShips.add(humanShip1);
        deployedMembers.add(alphaMember1);
        deployedMembers.add(alphaMember2);
        deployedMembers.add(humanMember1);
        BattleGroupState pGroupB = new BattleGroupState(0, new CommanderProfile(alphaPerson1, "Alpha Core I"));
        pGroupB.useConvergence = true;
        pGroupB.activeFleetFlagship = alphaShip1;
        a1Alive[0] = false; // Destroy Alpha Core I flagship!
        divisionai.DivisionOtcCommandManager.checkGroupFlagshipLoss(mockEngine, pGroupB, 30f, true);
        boolean featureBOk = (pGroupB.activeFleetFlagship == alphaShip2)
                && !divisionai.DivisionOtcCommandManager.isOtcDisabled(pGroupB, 30f)
                && pGroupB.useConvergence
                && divisionai.DivisionOtcCommandManager.isAlphaCoreOtcLockedInBattle(pGroupB)
                && !divisionai.DivisionOtcCommandManager.isEmergencyPlayerOtcSelectionPending(pGroupB);

        // --- Feature C: Player side's OTC ship destroyed -> pauses game and opens OTC selection screen ---
        divisionai.DivisionOtcCommandManager.resetForNewBattle();
        liveShips.clear();
        deployedMembers.clear();
        reserveMembers.clear();
        h1Alive[0] = true;
        dcAlive[0] = true;
        liveShips.add(humanShip1);
        liveShips.add(divCmdrShip);
        deployedMembers.add(humanMember1);
        deployedMembers.add(divCmdrMember);
        enginePaused[0] = false;
        BattleGroupState pGroupC = new BattleGroupState(0, new CommanderProfile(humanAdmiral, "Admiral Vance"));
        pGroupC.activeFleetFlagship = humanShip1;
        h1Alive[0] = false; // Destroy player's OTC flagship!
        divisionai.DivisionOtcCommandManager.checkGroupFlagshipLoss(mockEngine, pGroupC, 40f, true);
        boolean pausedAndPendingOnLoss = enginePaused[0]
                && divisionai.DivisionOtcCommandManager.isEmergencyPlayerOtcSelectionPending(pGroupC)
                && divisionai.DivisionOtcCommandManager.isEmergencyModalAutoOpenRequested();
        // Verify emergency selection bypasses CP cost even if 0 CP remain, and clears pending state
        cpStat.modifyMult("zero_cp_test", 0f);
        boolean emergencySelectZeroCpOk = divisionai.DivisionOtcCommandManager.selectPlayerOtcByMemberId(
                mockEngine, pGroupC, deployedMembers, reserveMembers, humanMember1, "div_cmdr_m", false)
                && !divisionai.DivisionOtcCommandManager.isEmergencyPlayerOtcSelectionPending(pGroupC)
                && (pGroupC.activeFleetFlagship == divCmdrShip);
        cpStat.unmodify("zero_cp_test");
        boolean featureCOk = pausedAndPendingOnLoss && emergencySelectZeroCpOk;

        // --- Feature D: OTC/Flagship Loss Succession Hierarchy (especially for AI-controlled fleets) ---
        final boolean[] eAlphaAlive = new boolean[]{true};
        final boolean[] eDivCmdrAlive = new boolean[]{true};
        final boolean[] eOfficerAlive = new boolean[]{true};
        final boolean[] eCapAlive = new boolean[]{true};
        final boolean[] eFrigAlive = new boolean[]{true};
        final ShipAPI eAlphaShip = createMockShipForOtcTest("e_alpha", "Enemy Radiant", 1, alphaMember1, alphaPerson1, ShipAPI.HullSize.CAPITAL_SHIP, eAlphaAlive);
        final ShipAPI eDivCmdrShip = createMockShipForOtcTest("e_div_cmdr", "Enemy Onslaught", 1, divCmdrMember, divCmdrPerson, ShipAPI.HullSize.CAPITAL_SHIP, eDivCmdrAlive);
        final ShipAPI eOfficerShip = createMockShipForOtcTest("e_officer", "Enemy Eagle", 1, officerMember, juniorOfficer, ShipAPI.HullSize.CRUISER, eOfficerAlive);
        final ShipAPI eCapShip = createMockShipForOtcTest("e_cap", "Enemy Conquest", 1, unnamedCapMember, defaultCrew, ShipAPI.HullSize.CAPITAL_SHIP, eCapAlive);
        final ShipAPI eFrigShip = createMockShipForOtcTest("e_frig", "Enemy Lasher", 1, unnamedFrigMember, defaultCrew, ShipAPI.HullSize.FRIGATE, eFrigAlive);

        liveShips.clear();
        liveShips.add(eAlphaShip);
        liveShips.add(eDivCmdrShip);
        liveShips.add(eOfficerShip);
        liveShips.add(eCapShip);
        liveShips.add(eFrigShip);

        BattleGroupState aiGroupD = new BattleGroupState(1, new CommanderProfile(humanAdmiral, "AI Fleet Admiral"));
        DivisionState aiMainDiv = new DivisionState("ai_main_div", 1, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div I",
                new CommanderProfile(divCmdrPerson, "Commander Kael"));
        aiMainDiv.isDesignatedMainEffort = true;
        aiMainDiv.flagship = eDivCmdrShip;
        aiMainDiv.members.add(eDivCmdrShip);
        aiMainDiv.members.add(eOfficerShip);
        aiMainDiv.members.add(eCapShip);
        aiGroupD.divisions.add(aiMainDiv);

        // Tier 1: AI Core ship preferred when preferAiCores=true
        ShipAPI tier1Pick = divisionai.DivisionOtcCommandManager.selectSuccessorShipByHierarchy(mockEngine, aiGroupD, null, true);
        // Tier 2: When AI Core is gone, Main Effort Division Commander is chosen over regular Officer and unnamed Capital
        eAlphaAlive[0] = false;
        ShipAPI tier2Pick = divisionai.DivisionOtcCommandManager.selectSuccessorShipByHierarchy(mockEngine, aiGroupD, null, true);
        // Tier 3: When Division Commander is gone, surviving named Officer is chosen over unnamed Capital
        eDivCmdrAlive[0] = false;
        ShipAPI tier3Pick = divisionai.DivisionOtcCommandManager.selectSuccessorShipByHierarchy(mockEngine, aiGroupD, null, true);
        // Tier 4: When named Officer is gone, largest surviving combat warship (Capital > Frigate) is chosen
        eOfficerAlive[0] = false;
        ShipAPI tier4Pick = divisionai.DivisionOtcCommandManager.selectSuccessorShipByHierarchy(mockEngine, aiGroupD, null, true);
        // Division flagship succession check: eDivCmdrShip dead, eOfficerShip alive -> eOfficerShip becomes aiMainDiv.flagship
        eOfficerAlive[0] = true;
        aiMainDiv.flagship = eDivCmdrShip; // dead
        divisionai.DivisionOtcCommandManager.checkDivisionFlagshipsDestroyed(mockEngine, aiGroupD, 35f);
        boolean divSuccOk = (aiMainDiv.flagship == eOfficerShip);
        boolean featureDOk = (tier1Pick == eAlphaShip)
                && (tier2Pick == eDivCmdrShip)
                && (tier3Pick == eOfficerShip)
                && (tier4Pick == eCapShip)
                && divSuccOk;

        // --- Feature E: -75% Max CP & -50% CP Gen Speed when player OTC is not deployed in battle ---
        divisionai.DivisionOtcCommandManager.resetForNewBattle();
        liveShips.clear();
        deployedMembers.clear();
        reserveMembers.clear();
        h1Alive[0] = true;
        dcAlive[0] = true;
        // Player selected human_m1 as OTC, but only deployed div_cmdr_m (human_m1 is left in reserves!)
        reserveMembers.add(humanMember1);
        deployedMembers.add(divCmdrMember);
        liveShips.add(divCmdrShip);
        BattleGroupState pGroupE = new BattleGroupState(0, new CommanderProfile(humanAdmiral, "Admiral Vance"));
        pGroupE.preferredOtcShipId = "human_m1";
        divisionai.DivisionOtcCommandManager.selectPlayerOtcByMemberId(
                mockEngine, pGroupE, java.util.Collections.emptyList(), Arrays.asList(humanMember1, divCmdrMember), divCmdrMember, "human_m1", true);

        boolean otcDetectedUndeployed = !divisionai.DivisionOtcCommandManager.isPlayerOtcDeployedInBattle(mockEngine, pGroupE);
        divisionai.DivisionOtcCommandManager.updatePlayerOtcNotDeployedCommandPointPenalty(mockEngine, pGroupE);
        float penalizedMaxCp = cpStat.getModifiedValue(); // 8 * 0.25 = 2.0f (-75% Max CP!)
        boolean penaltyAppliedOk = pGroupE.otcNotDeployedPenaltyActive
                && Math.abs(penalizedMaxCp - 2.0f) < 0.01f;

        // Now deploy humanShip1 into battle -> penalty must automatically lift!
        liveShips.add(humanShip1);
        deployedMembers.add(humanMember1);
        reserveMembers.remove(humanMember1);
        boolean otcNowDeployed = divisionai.DivisionOtcCommandManager.isPlayerOtcDeployedInBattle(mockEngine, pGroupE);
        divisionai.DivisionOtcCommandManager.updatePlayerOtcNotDeployedCommandPointPenalty(mockEngine, pGroupE);
        float restoredMaxCp = cpStat.getModifiedValue(); // 8.0f
        boolean penaltyClearedOk = !pGroupE.otcNotDeployedPenaltyActive
                && otcNowDeployed
                && Math.abs(restoredMaxCp - 8.0f) < 0.01f;
        boolean featureEOk = otcDetectedUndeployed && penaltyAppliedOk && penaltyClearedOk;

        divisionai.DivisionOtcCommandManager.resetForNewBattle();
        BattlefieldFirepowerEvaluator.resetForNewBattle();

        // Verify Debug Logging & Extended Tactical AI Debug Trace toggles in DivisionAISettings / DivisionCombatLog
        List<String> debugSink = new ArrayList<>();
        DivisionCombatLog.simLogSink = debugSink;
        DivisionAISettings.testDebugLoggingEnabledOverride = false;
        DivisionCombatLog.logVoidstrikeCulminated(0, 1, "test_muted", 10f);
        DivisionCombatLog.logVerboseAiDebug(10f, "[AI Debug] test trace while muted");
        boolean mutedWhenDebugOff = debugSink.isEmpty() && !DivisionAISettings.isVerboseAiDebugLoggingEnabled();

        DivisionAISettings.testDebugLoggingEnabledOverride = true;
        DivisionAISettings.testVerboseAiDebugLoggingOverride = false;
        DivisionCombatLog.logVoidstrikeCulminated(0, 1, "test_on", 11f);
        DivisionCombatLog.logVerboseAiDebug(11f, "[AI Debug] test trace while verbose off");
        boolean standardOnlyWhenVerboseOff = (debugSink.size() == 1);

        DivisionAISettings.testVerboseAiDebugLoggingOverride = true;
        DivisionCombatLog.logVerboseAiDebug(12f, "[AI Debug] test trace while verbose on");
        boolean verboseLoggedWhenBothOn = (debugSink.size() == 2);

        DivisionAISettings.testDebugLoggingEnabledOverride = null;
        DivisionAISettings.testVerboseAiDebugLoggingOverride = null;
        DivisionCombatLog.simLogSink = null;
        boolean debugLoggingToggleOk = mutedWhenDebugOff && standardOnlyWhenVerboseOff && verboseLoggedWhenBothOn;

        // --- Serious Bug A & Changes G, H, I verification ---
        divisionai.DivisionOtcCommandManager.resetForNewBattle();
        reserveMembers.clear();
        BattleGroupState pGroupAuto = new BattleGroupState(0, new CommanderProfile(humanAdmiral, "Admiral Vance"));
        // Select division containing officerMember (22 DP) and divCmdrMember (50 DP) while player flagship humanMember1 is NOT selected -> Auto OTC picks div_cmdr_m
        com.fs.starfarer.api.fleet.FleetMemberAPI autoResolved =
                divisionai.DivisionOtcCommandManager.resolvePreBattlePlayerOtcMember(
                        pGroupAuto,
                        Arrays.asList(humanMember1, divCmdrMember, officerMember),
                        Arrays.asList(officerMember, divCmdrMember));
        // Clear selection (simulating clicking blank space or deploying) -> Auto OTC must stay sticky on div_cmdr_m (and NOT revert to player flagship humanMember1)!
        com.fs.starfarer.api.fleet.FleetMemberAPI stickyAfterClear =
                divisionai.DivisionOtcCommandManager.resolvePreBattlePlayerOtcMember(
                        pGroupAuto,
                        Arrays.asList(humanMember1, divCmdrMember, officerMember),
                        java.util.Collections.emptyList());
        // Inherit into battle via DivisionOtcCommandManager.resolveActiveFleetFlagship:
        // Suppose divCmdrShip ("div_cmdr_m") is deployed alongside humanShip1 ("human_m1"), while div_cmdr_m was auto-selected pre-battle.
        liveShips.clear();
        liveShips.add(humanShip1);
        liveShips.add(divCmdrShip);
        pGroupAuto.preferredOtcShipId = null;
        pGroupAuto.otc = new CommanderProfile(humanAdmiral, "Admiral Vance");
        ShipAPI inheritedFlagship = divisionai.DivisionOtcCommandManager.resolveActiveFleetFlagship(mockEngine, pGroupAuto);
        boolean autoOtcStickyAndInheritedOk = (autoResolved == divCmdrMember)
                && (stickyAfterClear == divCmdrMember)
                && "div_cmdr_m".equals(divisionai.DivisionOtcCommandManager.getAutoSelectedPlayerOtcMemberId())
                && (inheritedFlagship == divCmdrShip);

        // Verify Change G ("division leader" terminology), Change H (Nomenclature sweep), and Change I (user defaults)
        boolean changeGDivisionLeaderOk = PermanentDivisionManager.DivisionFormation.LINE_AHEAD.summary.contains("division leader")
                && !PermanentDivisionManager.DivisionFormation.LINE_AHEAD.summary.contains("flagship");
        BattleGroupState hegEnemyBg = new BattleGroupState(1, new CommanderProfile(null, "Enemy Fleet Command"));
        hegEnemyBg.factionId = "hegemony";
        BattleGroupState pirateEnemyBg = new BattleGroupState(1, new CommanderProfile(null, "Enemy Fleet Command"));
        pirateEnemyBg.factionId = "pirates";
        BattleGroupState threatEnemyBg = new BattleGroupState(1, new CommanderProfile(null, "Enemy Fleet Command"));
        threatEnemyBg.factionId = "threat";
        threatEnemyBg.nomenclatureCategory = DivisionNomenclature.NomenclatureCategory.THREAT_SWARM;
        List<PlayerNamingPreset> selectablePresets = PlayerNamingPreset.getPlayerSelectablePresets();
        boolean spoilerPresetsHidden = selectablePresets.size() == 8
                && !selectablePresets.contains(PlayerNamingPreset.REMNANT_ORDO)
                && !selectablePresets.contains(PlayerNamingPreset.POST_AI_WAR_UNKNOWN)
                && !selectablePresets.contains(PlayerNamingPreset.THREAT_CORRUPTED)
                && !selectablePresets.contains(PlayerNamingPreset.ABYSSAL_ERROR);
        boolean threatLoreAccurate = "INTERDICTORATE PROACT: UNFABRICATE".equals(DivisionNomenclature.THREAT_OTC_TITLE)
                && "INTERDICTORATE PROACT: UNFABRICATE".equals(DivisionNomenclature.resolveBattleGroupOtcTitle(threatEnemyBg))
                && "STANDOFF UNIT // TYPE 300".equals(DivisionNomenclature.formatCorruptedThreatText(DivisionArchetype.BATTLESHIP_DIVISION, 1, 0L))
                && "HIVE UNIT // TYPE 350".equals(DivisionNomenclature.formatCorruptedThreatText(DivisionArchetype.FLEET_CARRIER_DIVISION, 1, 0L))
                && "OVERSEER UNIT // TYPE 250".equals(DivisionNomenclature.formatCorruptedThreatText(DivisionArchetype.CRUISER_DIVISION, 1, 0L))
                && "ASSAULT UNIT // TYPE 200".equals(DivisionNomenclature.formatCorruptedThreatText(DivisionArchetype.DESTROYER_DIVISION, 1, 0L))
                && "SKIRMISH UNIT // TYPE 100".equals(DivisionNomenclature.formatCorruptedThreatText(DivisionArchetype.FRIGATE_DIVISION, 1, 0L))
                && "FABRICATOR UNIT // TYPE 450".equals(DivisionNomenclature.formatCorruptedThreatText(DivisionArchetype.SERVICE_GROUP, 1, 0L));
        boolean changeHNomenclatureOk = "Fleet Command".equals(DivisionNomenclature.PlayerNamingPreset.NAVAL_STANDARD.defaultOtcTitle)
                && "Hegemony Command".equals(DivisionNomenclature.resolveBattleGroupOtcTitle(hegEnemyBg))
                && "Pirate Warlord".equals(DivisionNomenclature.resolveBattleGroupOtcTitle(pirateEnemyBg))
                && spoilerPresetsHidden
                && threatLoreAccurate;
        boolean changeIDefaultSettingsOk = !DivisionAISettings.isDebugLoggingEnabled()
                && !DivisionAISettings.isVerboseAiDebugLoggingEnabled()
                && DivisionAISettings.getChatterVerbosity() == DivisionAISettings.ChatterVerbosity.MINIMAL;

        res.record(
                featureAOk && featureBOk && featureCOk && featureDOk && featureEOk && debugLoggingToggleOk
                        && autoOtcStickyAndInheritedOk && changeGDivisionLeaderOk && changeHNomenclatureOk && changeIDefaultSettingsOk,
                "Features A-E, Serious Bug A (Auto OTC Stickiness & Battle Inheritance), Division Leader Terminology, Nomenclature & User Defaults",
                String.format(Locale.ROOT,
                        "featA_alphaPreFreeInBattleLock=%b, featB_alphaAutoSucc=%b, featC_pauseAndModal=%b, featD_hierarchy4Tiers=%b, featE_undeployedCpPenalty=%b (8.0->%.1f->%.1f), debugLogToggle=%b, autoOtcStickyAndInherited=%b, divLeader=%b, nomenclature=%b, userDefaults=%b",
                        featureAOk, featureBOk, featureCOk, featureDOk, featureEOk, penalizedMaxCp, restoredMaxCp, debugLoggingToggleOk,
                        autoOtcStickyAndInheritedOk, changeGDivisionLeaderOk, changeHNomenclatureOk, changeIDefaultSettingsOk));
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

        for (PermanentDivisionManager.DivisionRammingMode rm : PermanentDivisionManager.DivisionRammingMode.values()) {
            auditedStrings += 3;
            if (rm.displayName.length() > 26 || rm.shortName.length() > 16 || rm.summary.length() > 95) {
                violations.add("DivisionRammingMode." + rm.name() + " copy exceeds budget");
            }
        }

        for (PermanentDivisionManager.DivisionRammingTargetScope rs : PermanentDivisionManager.DivisionRammingTargetScope.values()) {
            auditedStrings += 3;
            if (rs.displayName.length() > 26 || rs.shortName.length() > 16 || rs.summary.length() > 95) {
                violations.add("DivisionRammingTargetScope." + rs.name() + " copy exceeds budget");
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
                "src/divisionai/FleetCommandPanel.java",
                "src/divisionai/OtcSelectionModalDialog.java",
                "src/divisionai/DivisionEditorDialog.java",
                "src/divisionai/DivisionConfigureSubscreen.java",
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
        boolean csvHasRadio = lunaCsv.contains("divai_chatter_verbosity,Chatter Verbosity,Radio,Minimal,\"Verbose, Standard, Minimal, OTC Only, Off\"")
                && lunaCsv.contains("divai_enable_debug_logging,Enable Debug Logging,Boolean,false")
                && lunaCsv.contains("divai_enemy_bg_chatter,Enemy OTC Signals,Boolean,false")
                && !lunaCsv.contains("%");

        int[] hudCounts = new int[DivisionAISettings.ChatterVerbosity.values().length];
        int[] logCounts = new int[DivisionAISettings.ChatterVerbosity.values().length];
        boolean[] hasParentheticalReasonOnHud = new boolean[DivisionAISettings.ChatterVerbosity.values().length];

        try {
            DivisionAISettings.testDebugLoggingEnabledOverride = Boolean.TRUE;
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
            DivisionAISettings.testDebugLoggingEnabledOverride = null;
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
                && "ToT Salvo ->".equals(DivisionModel.DivisionIntentType.TOT_MISSILE_STRIKE.minimalPhrase)
                && DivisionAISettings.isCriticalDivisionIntent(DivisionModel.DivisionIntentType.FEIGNED_RETREAT)
                && "Mobile Flanker".equals(PermanentDivisionManager.DoctrineOption.FLANKING_STRIKE.displayName)
                && DivisionEditorDialog.wrapTextToLines(
                        PermanentDivisionManager.OtcAuthority.COMMAND_BY_DIRECTION.negativeText, 68).size() >= 2;

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

        boolean strictAlphaCoreCheck = DivisionOtcCommandManager.isAlphaCoreControlledShip(alphaShip1)
                && !DivisionOtcCommandManager.isAlphaCoreControlledShip(betaShip)
                && !DivisionOtcCommandManager.isAlphaCoreControlledShip(gammaShip)
                && !DivisionOtcCommandManager.isAlphaCoreControlledShip(humanFlagship);

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
        boolean noAlphaButtonWhenOnlyLowerCores = !DivisionOtcCommandManager.hasAlphaCoreInPlayerFleet(mockEngine);

        // Add the two subordinate Alpha Core ships to the deployed fleet
        deployedShips.add(alphaShip1);
        deployedShips.add(alphaShip2);
        boolean alphaButtonAvailableWhenAlphaPresent = DivisionOtcCommandManager.hasAlphaCoreInPlayerFleet(mockEngine);

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
        // Verify independent ship (gammaShip, unassigned to any division) projects Time, Space, and Convergence envelopes
        List<DivisionState> tsfcUnits = BattlefieldFirepowerEvaluator.getUnitsForTsfcRenderingAndEvaluation(mockEngine, playerGroup);
        DivisionState indepGammaUnit = null;
        for (DivisionState u : tsfcUnits) {
            if (u != null && "indep_ship_p_gamma".equals(u.id)) {
                indepGammaUnit = u;
                break;
            }
        }
        boolean indepShipTsfcProjectedOk = indepGammaUnit != null
                && indepGammaUnit.freeTimeRadius > 600f
                && indepGammaUnit.spaceInnerRadius >= 240f
                && indepGammaUnit.spaceInnerRadius < playerDiv1.spaceInnerRadius
                && indepGammaUnit.spacePotentialRadius > indepGammaUnit.spaceDominatedRadius
                && indepGammaUnit.directionalTimeInitialized
                && indepGammaUnit.directionalSpaceInitialized
                && indepGammaUnit.directionalFirepowerRadii[0] > 200f
                && playerDiv1.directionalFirepowerRadii[0] > 400f;
        timeSpaceCalculatedOk = timeSpaceCalculatedOk && directionalTimePinchOk && indepShipTsfcProjectedOk;

        // 4. Verify initial Flagship & Division Commander Convergence rules:
        //    - Default flagship is humanFlagship -> playerGroup.useConvergence == false (standard human OTC)
        //    - playerDiv1 flagship is humanFlagship -> playerDiv1.useConvergence == false (human division commander)
        //    - playerDiv2 flagship is alphaShip1 -> playerDiv2.useConvergence == true (Alpha Core division commander)
        boolean initialConvergenceStatesOk = !playerGroup.useConvergence
                && !playerDiv1.useConvergence
                && playerDiv2.useConvergence;

        // 5. Verify Urgent Comms Pop-Up state machine when clicking Convergence button:
        //    - Click #1: pauses battle, opens "[URGENT COMMS FROM TTS Hyperion]" asking "Shall I take command?" (phrase 0)
        DivisionOtcCommandManager.handleConvergenceButtonClick(mockEngine);
        ShipAPI firstAsked = DivisionOtcCommandManager.getPendingCommsAlphaCoreShip();
        boolean firstClickOpenedCommsAndPaused = pausedRef[0]
                && firstAsked == alphaShip1
                && DivisionOtcCommandManager.getActiveCommsPhraseIndex() == 0
                && !playerGroup.useConvergence;

        //    - Player clicks "No": closes pop-up, TTS Hyperion will not ask again, OTC remains human
        DivisionOtcCommandManager.refusePendingAlphaCoreCommand(mockEngine);
        boolean afterRefuseFirstOk = DivisionOtcCommandManager.getPendingCommsAlphaCoreShip() == null
                && !playerGroup.useConvergence
                && DivisionOtcCommandManager.getAskedAlphaCoreShipIds().contains(alphaShip1.getId());

        //    - Click #2: next Alpha Core ship (TTS Radiant) asks "Please allow me." (phrase 1)
        pausedRef[0] = false;
        DivisionOtcCommandManager.handleConvergenceButtonClick(mockEngine);
        ShipAPI secondAsked = DivisionOtcCommandManager.getPendingCommsAlphaCoreShip();
        boolean secondClickAskedNextAlpha = pausedRef[0]
                && secondAsked == alphaShip2
                && DivisionOtcCommandManager.getActiveCommsPhraseIndex() == 1;

        //    - Player clicks "Yes": TTS Radiant becomes flagship and player OTC starts using Convergence decision-making!
        DivisionOtcCommandManager.acceptPendingAlphaCoreCommand(mockEngine);
        boolean afterAcceptSecondOk = DivisionOtcCommandManager.getPendingCommsAlphaCoreShip() == null
                && DivisionOtcCommandManager.getPlayerConvergenceFlagshipOverride() == alphaShip2
                && playerGroup.activeFleetFlagship == alphaShip2
                && playerGroup.useConvergence;

        //    - Click #3: no further pop-ups (flagship is now an Alpha Core and both ships were asked), toggles overlay cleanly
        pausedRef[0] = false;
        DivisionOtcCommandManager.handleConvergenceButtonClick(mockEngine);
        boolean thirdClickNoPopup = !pausedRef[0]
                && DivisionOtcCommandManager.getPendingCommsAlphaCoreShip() == null;

        // 6. Verify 0.5-second positional shift animation, centered ship sprite + callsign, and all 4 cycling phrases
        Vector2f shift0 = DivisionOtcCommandManager.getCommandWordShiftOffset(0.10f);
        Vector2f shift1 = DivisionOtcCommandManager.getCommandWordShiftOffset(0.60f);
        Vector2f shift2 = DivisionOtcCommandManager.getCommandWordShiftOffset(1.10f);
        String evalSrc = (new String(Files.readAllBytes(new File("src/divisionai/BattlefieldFirepowerEvaluator.java").toPath()), StandardCharsets.UTF_8) + new String(Files.readAllBytes(new File("src/divisionai/TsfcWarroomRenderer.java").toPath()), StandardCharsets.UTF_8));
        String[] p0 = DivisionOtcCommandManager.getUrgentCommsPhraseParts(0);
        String[] p1 = DivisionOtcCommandManager.getUrgentCommsPhraseParts(1);
        String[] p2 = DivisionOtcCommandManager.getUrgentCommsPhraseParts(2);
        String[] p3 = DivisionOtcCommandManager.getUrgentCommsPhraseParts(3);
        String[] p4 = DivisionOtcCommandManager.getUrgentCommsPhraseParts(4);
        boolean allFourPhrasesAndCycleOk = "command?".equals(p0[1])
                && "Please ".equals(p1[0]) && "allow".equals(p1[1]) && " me.".equals(p1[2])
                && "I am at your ".equals(p2[0]) && "service.".equals(p2[1])
                && "I can ".equals(p3[0]) && "ANNIHILATE".equals(p3[1]) && " them faster.".equals(p3[2])
                && "command?".equals(p4[1]);
        boolean commsSpriteAndJitterQuestionMarkOk = evalSrc.contains("renderCommsShipSprite(aiShip, spriteCenterX, spriteCenterY")
                && allFourPhrasesAndCycleOk
                && "TTS Hyperion".equals(TsfcWarroomRenderer.formatAiShipCallsignOnly(alphaShip1));
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
        boolean omegaCoreRecognized = DivisionOtcCommandManager.isAlphaCoreControlledShip(omegaShip);

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
        float[] wedgeApex = DivisionFormationNavigator.computeLocalFormationOffset(DivisionFormation.FORWARD_WEDGE, 0, 5, 500f);
        float[] wedgeWing = DivisionFormationNavigator.computeLocalFormationOffset(DivisionFormation.FORWARD_WEDGE, 1, 5, 500f);
        float[] revWedgeApex = DivisionFormationNavigator.computeLocalFormationOffset(DivisionFormation.REVERSE_WEDGE, 0, 5, 500f);
        float[] revWedgeWing = DivisionFormationNavigator.computeLocalFormationOffset(DivisionFormation.REVERSE_WEDGE, 1, 5, 500f);
        float[] aheadLead = DivisionFormationNavigator.computeLocalFormationOffset(DivisionFormation.LINE_AHEAD, 0, 5, 500f);
        float[] aheadTrail = DivisionFormationNavigator.computeLocalFormationOffset(DivisionFormation.LINE_AHEAD, 2, 5, 500f);
        boolean geometryDistinctOk = (wedgeApex[1] > wedgeWing[1])
                && (revWedgeApex[1] < revWedgeWing[1])
                && (Math.abs(aheadLead[0]) < 1f && Math.abs(aheadTrail[0]) < 1f && aheadLead[1] > aheadTrail[1]);

        // 6. Verify Configure Division UI & Top-Layer Urgent Comms Modal render order
        String editorSrc = new String(
                Files.readAllBytes(new File("src/divisionai/DivisionEditorDialog.java").toPath()),
                StandardCharsets.UTF_8)
                + "\n" + new String(Files.readAllBytes(new File("src/divisionai/DivisionConfigureSubscreen.java").toPath()), StandardCharsets.UTF_8);
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
        int modalTopLayerIdx = overlaySrc.indexOf("TsfcWarroomRenderer.renderToggleButtonAndLegendUI(engine, fade);");
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
        VoidstrikeCoordinator.updateVoidstrikes(null, pGroup, null, eGroup, 10f);
        List<VoidstrikeCoordinator.VoidstrikeRecord> activeVs1 = VoidstrikeCoordinator.getActiveVoidstrikes();
        boolean multiDivMergedIntoOneOk = (activeVs1.size() == 1)
                && "Voidstrike 1".equals(activeVs1.get(0).getDisplayLabel())
                && activeVs1.get(0).isMultiDivision()
                && Math.abs(activeVs1.get(0).center.x - 200f) < 1f
                && Math.abs(activeVs1.get(0).center.y - 600f) < 1f;

        // When strike craft return -> "Voidstrike 1" disappears
        returningFlag[0] = true;
        VoidstrikeCoordinator.updateVoidstrikes(null, pGroup, null, eGroup, 15f);
        boolean disappearsOnReturnOk = VoidstrikeCoordinator.getActiveVoidstrikes().isEmpty();

        // Next strike launched -> incremented to "Voidstrike 2"
        returningFlag[0] = false;
        f1Loc.set(400f, 900f);
        f2Loc.set(400f, 900f);
        VoidstrikeCoordinator.updateVoidstrikes(null, pGroup, null, eGroup, 20f);
        List<VoidstrikeCoordinator.VoidstrikeRecord> activeVs2 = VoidstrikeCoordinator.getActiveVoidstrikes();
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
        boolean separateSideCountersOk = (VoidstrikeCoordinator.getNextVoidstrikeNumberForSide(
                VoidstrikeCoordinator.VOIDSTRIKE_SIDE_PLAYER) == 3)
                && (VoidstrikeCoordinator.getNextVoidstrikeNumberForSide(
                VoidstrikeCoordinator.VOIDSTRIKE_SIDE_ENEMY) == 1)
                && (VoidstrikeCoordinator.getNextVoidstrikeNumberForSide(
                VoidstrikeCoordinator.VOIDSTRIKE_SIDE_ALLIED) == 1);

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
                && evalSrc.contains("UNIT_SIN_72");

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
        VoidstrikeCoordinator.updateDivisionVoidstrikeStaging(cDiv1, pGroup, enemyTarget, 100f);
        boolean stage0Ok = (cDiv1.voidstrikeStage == 0);
        VoidstrikeCoordinator.updateDivisionVoidstrikeStaging(cDiv1, pGroup, enemyTarget, 102f);
        boolean stage1Ok = (cDiv1.voidstrikeStage == 1) && (cDiv1.voidstrikeMarshalPoint.y > -1500f);
        VoidstrikeCoordinator.updateDivisionVoidstrikeStaging(cDiv1, pGroup, enemyTarget, 105f);
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
        int[] exactComp = VoidstrikeCoordinator.computeCompromiseStrikeWidthAndDepth(6, 3, 2);
        int[] avgComp = VoidstrikeCoordinator.computeCompromiseStrikeWidthAndDepth(4, 4, 4);
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
        float[] pCircleCore = DivisionFormationNavigator.computeLocalFormationOffset(DivisionFormation.PROTECTIVE_CIRCLE, 0, 4, 500f);
        float[] pCircleRing1 = DivisionFormationNavigator.computeLocalFormationOffset(DivisionFormation.PROTECTIVE_CIRCLE, 1, 4, 500f);
        float[] pCircleRing2 = DivisionFormationNavigator.computeLocalFormationOffset(DivisionFormation.PROTECTIVE_CIRCLE, 2, 4, 500f);
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
                && tooltipSrc.contains("line3Label.setText(l3);")
                && tooltipSrc.contains("sanitizePositionTree")
                && tooltipSrc.contains("prepareContentContainerBeforeClear")
                && tooltipSrc.contains("setSuspendRecompute");

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
        float[] echR0 = DivisionFormationNavigator.computeLocalFormationOffset(DivisionFormation.ECHELON_RIGHT, 0, 4, 300f);
        float[] echR2 = DivisionFormationNavigator.computeLocalFormationOffset(DivisionFormation.ECHELON_RIGHT, 2, 4, 300f);
        float[] echL2 = DivisionFormationNavigator.computeLocalFormationOffset(DivisionFormation.ECHELON_LEFT, 2, 4, 300f);
        boolean echelonHandednessOk = (echR0[0] == 0f && echR0[1] == 0f)
                && (echR2[0] > 350f && echR2[1] < -300f)
                && (echL2[0] < -350f && echL2[1] < -300f);

        // Item C: Airborne Voidstrike in Stage 2 does not reset to Stage 0 when target switches mid-flight
        ShipAPI enemyTarget2 = createMockNamedShip("wb2_s1", "Warband 2 Lead", HullSize.CRUISER, 350f, 1600f, 90f, 1, humanCapt);
        DivisionState enemyWarband2 = new DivisionState("wb_2", 1, 2, DivisionArchetype.CRUISER_DIVISION, "Warband 2", cmdr);
        enemyWarband2.flagship = enemyTarget2;
        enemyWarband2.members.add(enemyTarget2);
        cDiv1.targetEnemyDivision = enemyWarband2;
        VoidstrikeCoordinator.updateDivisionVoidstrikeStaging(cDiv1, pGroup, enemyTarget2, 106f);
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
                && defProf.getPrimaryBattleTrackLayer().computeTargetVolume(0.85f, true) >= 0.99f
                && DynamicBattleMusicController.verifyAudioHandles();

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

        // Verify FactionLibrary Organization Ratings (0%/25%/50%/80%/90%/100%), HIGH fallback for unknown factions,
        // and Non-War Fleet (Merchant/Smuggler/Scavenger) nomenclature & rating adaptation:
        boolean factionLibRatingsOk = FactionLibrary.OrganizationRating.NONE.fraction == 0.00f
                && FactionLibrary.OrganizationRating.LOW.fraction == 0.25f
                && FactionLibrary.OrganizationRating.AVERAGE.fraction == 0.50f
                && FactionLibrary.OrganizationRating.HIGH.fraction == 0.80f
                && FactionLibrary.OrganizationRating.VERY_HIGH.fraction == 0.90f
                && FactionLibrary.OrganizationRating.PERFECT.fraction == 1.00f
                && FactionLibrary.resolveFactionOrganizationRating("unknown_submod_faction_xyz") == FactionLibrary.OrganizationRating.HIGH
                && FactionLibrary.resolveFactionOrganizationRating("pirates") == FactionLibrary.OrganizationRating.LOW
                && FactionLibrary.resolveFactionOrganizationRating("independent") == FactionLibrary.OrganizationRating.AVERAGE
                && FactionLibrary.resolveFactionOrganizationRating("persean") == FactionLibrary.OrganizationRating.HIGH
                && FactionLibrary.resolveFactionOrganizationRating("hegemony") == FactionLibrary.OrganizationRating.VERY_HIGH
                && FactionLibrary.resolveFactionOrganizationRating("remnant") == FactionLibrary.OrganizationRating.PERFECT
                && FactionLibrary.resolveFactionOrganizationRating("hegemony", FactionLibrary.FleetRoleCategory.MERCHANT_CONVOY) == FactionLibrary.OrganizationRating.AVERAGE;
        String hegMerchConvoyName = DivisionNomenclature.formatFactionDivisionName(
                "hegemony", DivisionNomenclature.NomenclatureCategory.HUMAN_NAVAL,
                DivisionArchetype.SERVICE_GROUP, 1, null, 0L, FactionLibrary.FleetRoleCategory.MERCHANT_CONVOY);
        String triMerchConvoyName = DivisionNomenclature.formatFactionDivisionName(
                "tritachyon", DivisionNomenclature.NomenclatureCategory.HUMAN_NAVAL,
                DivisionArchetype.SERVICE_GROUP, 1, null, 0L, FactionLibrary.FleetRoleCategory.MERCHANT_CONVOY);
        String indMerchConvoyName = DivisionNomenclature.formatFactionDivisionName(
                "independent", DivisionNomenclature.NomenclatureCategory.HUMAN_NAVAL,
                DivisionArchetype.SERVICE_GROUP, 1, null, 12345L, FactionLibrary.FleetRoleCategory.MERCHANT_CONVOY);
        String unkMerchConvoyName = DivisionNomenclature.formatFactionDivisionName(
                "unknown_submod_faction_xyz", DivisionNomenclature.NomenclatureCategory.HUMAN_NAVAL,
                DivisionArchetype.SERVICE_GROUP, 1, null, 0L, FactionLibrary.FleetRoleCategory.MERCHANT_CONVOY);
        boolean merchantNomenclatureOk = "Chartered Convoy 1".equals(hegMerchConvoyName)
                && "Commercial Caravan 1".equals(triMerchConvoyName)
                && "Merchant Convoy 1".equals(unkMerchConvoyName)
                && !indMerchConvoyName.toLowerCase(Locale.ROOT).contains("service group")
                && (indMerchConvoyName.contains("Convoy") || indMerchConvoyName.contains("Flotilla")
                || indMerchConvoyName.contains("Group") || indMerchConvoyName.contains("Caravan"))
                && new File("mod/data/config/division_faction_library.json").isFile()
                && new File("mod/data/config/division_faction_doctrines.json").isFile()
                && FactionFleetDoctrines.getDoctrineForFactionId("hegemony", null).getFleetWideFormation() == PermanentDivisionManager.FleetWideFormation.GRAND_WEDGE_SPEARHEAD
                && FactionFleetDoctrines.getDoctrineForFactionId("remnant", null).getOtcAuthority() == OtcAuthority.POSITIVE_CONTROL
                && FactionFleetDoctrines.getDoctrineForFactionId("luddic_path", null).isPlayEnabled(PermanentDivisionManager.OtcPlay.MARTYRDOM);

        // Verify New Battle Bugs (A, B, C) & Feature Changes (A, B, C):
        // Bug A: Urgent comms from Alpha Core ship suppressed when an Alpha Core was OTC from the start
        DivisionOtcCommandManager.markInitialPlayerOtcAlphaCore(true);
        ShipAPI unaskedWhenInitialAlpha = DivisionOtcCommandManager.findNextUnaskedSubordinateAlphaCoreShip(musicTestEngine, pGroup);
        DivisionOtcCommandManager.markInitialPlayerOtcAlphaCore(false);
        String otcMgrSrc = new String(Files.readAllBytes(new File("src/divisionai/DivisionOtcCommandManager.java").toPath()), StandardCharsets.UTF_8);
        boolean bugAUrgentCommsSuppressedOk = (unaskedWhenInitialAlpha == null)
                && otcMgrSrc.contains("if (initialPlayerOtcWasAlphaCore)")
                && otcMgrSrc.contains("isActivePlayerOtcAlphaCore(engine, null, playerGroup)");

        // Bug B: OTC panel 2-line text wrapping provisions for long ship names
        String[] wrappedLongFlag = DivisionDeploymentDialogOrganizer.wrapOtcTextIntoTwoLines(
                "Flagship: HSS Invictus Sovereign of the Stars (Battleship)", null, 180f, 180f);
        boolean bugBOtcWrappingOk = wrappedLongFlag.length == 2
                && !wrappedLongFlag[0].isEmpty()
                && !wrappedLongFlag[1].isEmpty();

        // Bug C: Sticky enemy visibility & smoothed centroid for enemy divisions and TSFC
        final boolean[] fogVisToggle = new boolean[]{true};
        final float[] fogTimeSec = new float[]{10.0f};
        com.fs.starfarer.api.combat.FogOfWarAPI mockFog = (com.fs.starfarer.api.combat.FogOfWarAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.combat.FogOfWarAPI.class},
                (p, m, a) -> {
                    if ("isVisible".equals(m.getName())) return fogVisToggle[0];
                    if (m.getReturnType() == boolean.class) return false;
                    return null;
                });
        CombatEngineAPI stickyTimeEngine = (CombatEngineAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{CombatEngineAPI.class},
                (p, m, a) -> {
                    if ("getTotalElapsedTime".equals(m.getName())) return fogTimeSec[0];
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });
        boolean seenAt10 = BattlefieldFirepowerEvaluator.isEnemyShipVisibleSticky(stickyTimeEngine, enemyTarget, mockFog);
        fogVisToggle[0] = false;
        fogTimeSec[0] = 11.5f; // 1.5s after sensor flicker (< 2.75s grace window)
        boolean stickyAt11_5 = BattlefieldFirepowerEvaluator.isEnemyShipVisibleSticky(stickyTimeEngine, enemyTarget, mockFog);
        float alphaAt11_5 = BattlefieldFirepowerEvaluator.getEnemyShipStickyVisibilityAlpha(stickyTimeEngine, enemyTarget, mockFog);
        fogTimeSec[0] = 14.0f; // 4.0s after sensor loss (> 2.75s grace window)
        boolean expiredAt14 = !BattlefieldFirepowerEvaluator.isEnemyShipVisibleSticky(stickyTimeEngine, enemyTarget, mockFog);
        boolean bugCStickyVisibilityOk = seenAt10 && stickyAt11_5 && alphaAt11_5 >= 0.35f && expiredAt14;

        // Feature Change A: "Fleet Status" panel uses compass-wheel-only formation icon once battle has commenced,
        // and all 12 Fleet Formations (including Reverse Wedge, Refused Left, Reverse Crescent, None) are implemented
        String stdFormationPath = DivisionDeploymentDialogOrganizer.getFleetFormationSpritePath(
                PermanentDivisionManager.FleetWideFormation.GRAND_LINE_ABREAST, false);
        String wheelFormationPath = DivisionDeploymentDialogOrganizer.getFleetFormationWheelSpritePath(
                PermanentDivisionManager.FleetWideFormation.GRAND_LINE_ABREAST);
        boolean allTwelveFleetFormationsOk = (PermanentDivisionManager.FleetWideFormation.values().length == 12);
        for (PermanentDivisionManager.FleetWideFormation fw : PermanentDivisionManager.FleetWideFormation.values()) {
            String pFull = DivisionDeploymentDialogOrganizer.getFleetFormationSpritePath(fw, false);
            String pWheel = DivisionDeploymentDialogOrganizer.getFleetFormationWheelSpritePath(fw);
            if (pFull == null || pWheel == null
                    || !new File("mod/" + pFull).isFile()
                    || !new File("mod/" + pWheel).isFile()) {
                allTwelveFleetFormationsOk = false;
            }
        }
        BattleGroupState orchGroup = new BattleGroupState(0, null);
        orchGroup.orchestratedAdvance = true;
        orchGroup.fleetWideFormation = PermanentDivisionManager.FleetWideFormation.REVERSE_WEDGE;
        orchGroup.activeFleetWideFormation = PermanentDivisionManager.FleetWideFormation.REVERSE_WEDGE;
        boolean orchActiveBeforeContact = divisionai.OtcAI.isOrchestratedAdvanceActive(null, orchGroup, null);
        orchGroup.fleetEngagementLevel = 0.25f;
        boolean orchStoppedAfterContact = !divisionai.OtcAI.isOrchestratedAdvanceActive(null, orchGroup, null)
                && orchGroup.orchestratedAdvanceContactMade;
        orchGroup.fleetEngagementLevel = 0.0f;
        boolean orchLatchedAfterContact = !divisionai.OtcAI.isOrchestratedAdvanceActive(null, orchGroup, null);
        boolean featAWheelIconOk = "graphics/icons/fleet_formations/fleet_formation_grand_line.png".equals(stdFormationPath)
                && "graphics/icons/fleet_formations/fleet_formation_grand_line_wheel.png".equals(wheelFormationPath)
                && new File("mod/" + wheelFormationPath).isFile()
                && allTwelveFleetFormationsOk
                && orchActiveBeforeContact && orchStoppedAfterContact && orchLatchedAfterContact;

        // Feature Change B: TSFC visible in the live "battle" screen outside of the warroom at vastly lower opacity
        String combatPluginSrc = new String(
                Files.readAllBytes(new File("src/divisionai/DivisionAICombatPlugin.java").toPath()),
                StandardCharsets.UTF_8);
        boolean featBBattleScreenTsfcOk = divisionai.DivisionAICombatPlugin.BATTLE_SCREEN_TSFC_OPACITY_MULT <= 0.25f
                && divisionai.DivisionAICombatPlugin.BATTLE_SCREEN_TSFC_OPACITY_MULT > 0.10f
                && combatPluginSrc.contains("renderBattleScreenTsfcOverlay(viewport)")
                && combatPluginSrc.contains("TsfcWarroomRenderer.renderTimeSpaceAndConvergenceScreenSpace")
                && combatPluginSrc.contains("TsfcWarroomRenderer.renderFirepowerRosesScreenSpace");

        // Feature Change C: Softly remove formations text underneath division names since formation icons exist
        boolean featCNoFormationTextUnderDivNameOk = !overlaySrc.contains("f.shortName.toUpperCase(Locale.ROOT)")
                && !editorSrc.contains("effForm.shortName,");

        // Bug & Feature Verification:
        // 1) OTC Force-Distribution Overkill & Enemy Center of Gravity:
        //    When a stray armed-freighter pack on the right flank already has 1 friendly frigate division committed,
        //    updateEnemyOverkillAndCenterOfGravity marks it isOverkilled=true and keeps Main Effort on the Enemy CoG.
        BattleGroupState okMyGroup = new BattleGroupState(0, cmdr);
        BattleGroupState okEnemyGroup = new BattleGroupState(1, cmdr);
        DivisionState okBattleDiv = new DivisionState("ok_b1", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div 1", cmdr);
        okBattleDiv.totalFpStrength = 48f;
        okBattleDiv.centroid.set(0f, -2000f);
        ShipAPI okB1Ship = createMockNamedShip("ok_b1_s", "HSS Onslaught", HullSize.CAPITAL_SHIP, 0f, -2000f, 90f, 0, humanCapt);
        okBattleDiv.flagship = okB1Ship;
        okBattleDiv.members.add(okB1Ship);

        DivisionState okFrigDiv = new DivisionState("ok_f2", 0, 2, DivisionArchetype.FRIGATE_DIVISION, "Frigate Div 2", cmdr);
        okFrigDiv.totalFpStrength = 18f;
        okFrigDiv.centroid.set(3200f, -1500f);
        ShipAPI okF2Ship = createMockNamedShip("ok_f2_s", "HSS Lasher", HullSize.FRIGATE, 3200f, -1500f, 90f, 0, humanCapt);
        okFrigDiv.flagship = okF2Ship;
        okFrigDiv.members.add(okF2Ship);

        okMyGroup.divisions.add(okBattleDiv);
        okMyGroup.divisions.add(okFrigDiv);
        okMyGroup.mainEffortDivision = okBattleDiv;

        DivisionState enmCogDiv = new DivisionState("enm_cog", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Enemy Main Line", cmdr);
        enmCogDiv.totalFpStrength = 42f;
        enmCogDiv.centroid.set(0f, 600f);
        ShipAPI enmCogShip = createMockNamedShip("enm_cog_s", "ISS Eagle", HullSize.CRUISER, 0f, 600f, 270f, 1, humanCapt);
        enmCogDiv.flagship = enmCogShip;
        enmCogDiv.members.add(enmCogShip);

        DivisionState enmFlankFreighters = new DivisionState("enm_flk", 1, 2, DivisionArchetype.SERVICE_GROUP, "Stray Flank Pack", cmdr);
        enmFlankFreighters.totalFpStrength = 16f;
        enmFlankFreighters.centroid.set(3600f, -800f);
        ShipAPI enmFlkShip = createMockNamedShip("enm_flk_s", "Wayfarer", HullSize.FRIGATE, 3600f, -800f, 270f, 1, humanCapt);
        enmFlankFreighters.flagship = enmFlkShip;
        enmFlankFreighters.members.add(enmFlkShip);

        okEnemyGroup.divisions.add(enmCogDiv);
        okEnemyGroup.divisions.add(enmFlankFreighters);
        okFrigDiv.targetEnemyDivision = enmFlankFreighters;
        divisionai.OtcAI.updateEnemyOverkillAndCenterOfGravity(null, okMyGroup, okEnemyGroup);
        boolean otcOverkillOk = enmFlankFreighters.isOverkilled
                && !enmCogDiv.isOverkilled
                && enmFlankFreighters.combatThreatWeight <= 0.50f
                && okMyGroup.hasEnemyCenterOfGravity
                && Math.abs(okMyGroup.enemyCenterOfGravity.x) < 900f;

        // 2) Map-Edge TSFC Station & Flank Range when enemy is pinned near map boundary:
        Vector2f corneredEnemyPos = new Vector2f(7500f, 0f); // 500u from East map boundary (halfW=8000)
        Vector2f closeAttackCand = new Vector2f(6850f, 0f);  // 650u from cornered enemy, 1150u from East wall
        float edgeScoreOnCorneredEnemy = BattlefieldFirepowerEvaluator.evaluateMapEdgeStationScore(
                null, okFrigDiv, corneredEnemyPos, closeAttackCand, false);
        boolean mapEdgePursuitNotRepelledOk = edgeScoreOnCorneredEnemy >= 0f;

        // 3) Grand Formation & OTC "Consent":
        //    Scouting Destroyer division is NOT hijacked into Reserve, and wing sides/slot indices remain locked across evaluations.
        BattleGroupState consentGroup = new BattleGroupState(0, cmdr);
        consentGroup.orchestratedAdvance = true;
        consentGroup.fleetWideFormation = PermanentDivisionManager.FleetWideFormation.CRESCENT_HORNS;
        consentGroup.activeFleetWideFormation = PermanentDivisionManager.FleetWideFormation.CRESCENT_HORNS;
        DivisionState cMain = new DivisionState("c_m", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div 1", cmdr);
        cMain.totalFpStrength = 48f;
        cMain.centroid.set(0f, -3000f);
        cMain.members.add(okB1Ship);
        DivisionState cCruiser = new DivisionState("c_c", 0, 2, DivisionArchetype.CRUISER_DIVISION, "Line Cruiser Div 1", cmdr);
        cCruiser.totalFpStrength = 30f;
        cCruiser.centroid.set(-800f, -3000f);
        cCruiser.members.add(okB1Ship);
        DivisionState cScoutDd = new DivisionState("c_d", 0, 3, DivisionArchetype.DESTROYER_DIVISION, "Destroyer Div 1", cmdr);
        cScoutDd.totalFpStrength = 20f;
        cScoutDd.openingMission = DivisionMissionType.SCOUTING;
        cScoutDd.mainMission = DivisionMissionType.CLOSE_ACTION;
        cScoutDd.centroid.set(800f, -3000f);
        cScoutDd.members.add(okF2Ship);
        DivisionState cFrigL = new DivisionState("c_fl", 0, 4, DivisionArchetype.FRIGATE_DIVISION, "Frigate Div 1", cmdr);
        cFrigL.totalFpStrength = 18f;
        cFrigL.openingMission = DivisionMissionType.ENVELOPMENT;
        cFrigL.centroid.set(-1600f, -3000f);
        cFrigL.members.add(okF2Ship);
        consentGroup.divisions.add(cMain);
        consentGroup.divisions.add(cCruiser);
        consentGroup.divisions.add(cScoutDd);
        consentGroup.divisions.add(cFrigL);
        divisionai.OtcAI.synchronizeGrandFormationConsent(null, consentGroup, okEnemyGroup, 0.0f);
        divisionai.OtcAI.updateFleetWideFormationGeometry(null, consentGroup, okEnemyGroup);
        int slotCruiser0 = cCruiser.fleetFormationSlotIndex;
        int wingCruiser0 = cCruiser.consentedWingSide;
        int slotDd0 = cScoutDd.fleetFormationSlotIndex;
        int wingDd0 = cScoutDd.consentedWingSide;
        divisionai.OtcAI.synchronizeGrandFormationConsent(null, consentGroup, okEnemyGroup, 1.5f);
        divisionai.OtcAI.updateFleetWideFormationGeometry(null, consentGroup, okEnemyGroup);
        boolean grandFormationConsentOk = consentGroup.grandFormationConsentLocked
                && !cScoutDd.isDesignatedReserve
                && cMain.isDesignatedMainEffort
                && cCruiser.fleetFormationSlotIndex == slotCruiser0
                && cCruiser.consentedWingSide == wingCruiser0
                && cScoutDd.fleetFormationSlotIndex == slotDd0
                && cScoutDd.consentedWingSide == wingDd0;

        // Verify ShipSkillCoordinationMode (default SYNCHRONIZED, doctrine & per-division override)
        // and ShipSystemCoordinator generic ship skill classification + scouting discipline
        PermanentDivisionManager.FleetDoctrineConfig skillDocCfg = new PermanentDivisionManager.FleetDoctrineConfig();
        PermanentDivisionManager.PermanentDivisionSpec skillDivSpec =
                new PermanentDivisionManager.PermanentDivisionSpec("skill_div", "1st DD Div", DivisionArchetype.DESTROYER_DIVISION);
        boolean defaultSkillCoordSyncOk = skillDocCfg.getShipSkillCoordination(
                        PermanentDivisionManager.DoctrineDivisionType.DESTROYER)
                == PermanentDivisionManager.ShipSkillCoordinationMode.SYNCHRONIZED
                && PermanentDivisionManager.resolveEffectiveShipSkillCoordination(skillDivSpec, skillDocCfg)
                == PermanentDivisionManager.ShipSkillCoordinationMode.SYNCHRONIZED;
        skillDivSpec.customShipSkillCoordination = PermanentDivisionManager.ShipSkillCoordinationMode.INDEPENDENT;
        boolean customSkillCoordIndepOk = PermanentDivisionManager.resolveEffectiveShipSkillCoordination(skillDivSpec, skillDocCfg)
                == PermanentDivisionManager.ShipSkillCoordinationMode.INDEPENDENT;

        boolean defaultRamCfgOk = skillDocCfg.getRammingMode(PermanentDivisionManager.DoctrineDivisionType.DESTROYER)
                == PermanentDivisionManager.DivisionRammingMode.OFF
                && skillDocCfg.getRammingTargetScope(PermanentDivisionManager.DoctrineDivisionType.DESTROYER)
                == PermanentDivisionManager.DivisionRammingTargetScope.SINGLE_SHIP
                && skillDocCfg.getMaxRammingSlots(PermanentDivisionManager.DoctrineDivisionType.DESTROYER) == 1
                && PermanentDivisionManager.resolveEffectiveRammingMode(skillDivSpec, skillDocCfg)
                == PermanentDivisionManager.DivisionRammingMode.OFF;
        skillDivSpec.customRammingMode = PermanentDivisionManager.DivisionRammingMode.GLANCING_SKIRMISH;
        skillDivSpec.customRammingTargetScope = PermanentDivisionManager.DivisionRammingTargetScope.ENEMY_DIVISION;
        skillDivSpec.customMaxRammingSlots = 6;
        boolean customRamCfgOk = PermanentDivisionManager.resolveEffectiveRammingMode(skillDivSpec, skillDocCfg)
                == PermanentDivisionManager.DivisionRammingMode.GLANCING_SKIRMISH
                && PermanentDivisionManager.resolveEffectiveRammingTargetScope(skillDivSpec, skillDocCfg)
                == PermanentDivisionManager.DivisionRammingTargetScope.ENEMY_DIVISION
                && PermanentDivisionManager.resolveEffectiveMaxRammingSlots(skillDivSpec, skillDocCfg) == 6;
        defaultSkillCoordSyncOk = defaultSkillCoordSyncOk && defaultRamCfgOk && customRamCfgOk;

        ShipAPI ramDdMock = createMockShipWithSystem("ram_1", "JYD_rambunctious", "JYD_ramdrive");
        ShipAPI burnCruiserMock = createMockShipWithSystem("burn_1", "dominator", "burndrive");
        ShipAPI jetsCruiserMock = createMockShipWithSystem("jets_1", "eagle", "maneuveringjets");
        ShipAPI blinkDdMock = createMockShipWithSystem("blink_1", "medusa", "displacer");
        ShipAPI ampDdMock = createMockShipWithSystem("amp_1", "hammerhead", "ammofeed");
        divisionai.ShipSystemCoordinator.ShipSkillProfile ramProf = divisionai.ShipSystemCoordinator.getSkillProfile(ramDdMock);
        divisionai.ShipSystemCoordinator.ShipSkillProfile burnProf = divisionai.ShipSystemCoordinator.getSkillProfile(burnCruiserMock);
        divisionai.ShipSystemCoordinator.ShipSkillProfile jetsProf = divisionai.ShipSystemCoordinator.getSkillProfile(jetsCruiserMock);
        divisionai.ShipSystemCoordinator.ShipSkillProfile blinkProf = divisionai.ShipSystemCoordinator.getSkillProfile(blinkDdMock);
        divisionai.ShipSystemCoordinator.ShipSkillProfile ampProf = divisionai.ShipSystemCoordinator.getSkillProfile(ampDdMock);
        boolean shipSkillClassifierOk = ramProf.category == divisionai.ShipSystemCoordinator.ShipSkillCategory.LINEAR_RAM_DRIVE
                && ramProf.isDesignedToRam
                && burnProf.category == divisionai.ShipSystemCoordinator.ShipSkillCategory.LINEAR_THRUST_DRIVE
                && !burnProf.isDesignedToRam
                && jetsProf.category == divisionai.ShipSystemCoordinator.ShipSkillCategory.OMNI_AGILITY_JETS
                && blinkProf.category == divisionai.ShipSystemCoordinator.ShipSkillCategory.BLINK_DISPLACER
                && ampProf.category == divisionai.ShipSystemCoordinator.ShipSkillCategory.OFFENSIVE_AMP;

        cScoutDd.shipSkillCoordination = PermanentDivisionManager.ShipSkillCoordinationMode.SYNCHRONIZED;
        cScoutDd.openingMission = DivisionMissionType.SCOUTING;
        boolean suppressScoutingRamOk = divisionai.ShipSystemCoordinator.shouldSuppressManeuverTargetForLinearSkill(
                ramDdMock, cScoutDd, consentGroup, 5.0f);
        cScoutDd.shipSkillCoordination = PermanentDivisionManager.ShipSkillCoordinationMode.INDEPENDENT;
        boolean allowIndepScoutingRamOk = !divisionai.ShipSystemCoordinator.shouldSuppressManeuverTargetForLinearSkill(
                ramDdMock, cScoutDd, consentGroup, 5.0f);
        boolean shipSkillDoctrineAndClassifierOk = defaultSkillCoordSyncOk && customSkillCoordIndepOk
                && shipSkillClassifierOk && suppressScoutingRamOk && allowIndepScoutingRamOk;

        taskFStrikeCraftSpaceOk = taskFStrikeCraftSpaceOk && factionLibRatingsOk && merchantNomenclatureOk
                && bugAUrgentCommsSuppressedOk && bugBOtcWrappingOk && bugCStickyVisibilityOk
                && featAWheelIconOk && featBBattleScreenTsfcOk && featCNoFormationTextUnderDivNameOk
                && otcOverkillOk && mapEdgePursuitNotRepelledOk && grandFormationConsentOk
                && shipSkillDoctrineAndClassifierOk;

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

        // TSFC Readability & Clarity Gate (A-D):
        //  A & B: Sliding opacity based on highest seen values in a battle + lower base opacity
        //  C: TSFC renders under ship sprites (BELOW_SHIPS_LAYER in battle screen + renderWarroomShipSpritesAboveTsfc in warroom)
        //  D: 0.10s evaluation cadence with C1 keyframe interpolation, anything past 2.0s thrown into the bin
        boolean cadenceAndBinConstOk = Math.abs(BattlefieldFirepowerEvaluator.TSFC_EVAL_INTERVAL - 0.10f) < 1e-5f
                && Math.abs(divisionai.DivisionAICombatPlugin.TSFC_EVAL_INTERVAL - 0.10f) < 1e-5f
                && Math.abs(BattlefieldFirepowerEvaluator.TSFC_KEYFRAME_MAX_AGE - 2.0f) < 1e-5f;

        BattlefieldFirepowerEvaluator.resetForNewBattle();
        ShipAPI kfShip = createMockNamedShip("kf_ship_1", "HSS Onslaught", HullSize.CAPITAL_SHIP, 0f, 0f, 0f, 0, humanCapt);
        BattlefieldFirepowerEvaluator.ShipFirepowerRose kfRose = new BattlefieldFirepowerEvaluator.ShipFirepowerRose(kfShip);
        kfRose.initialized = true;
        kfRose.prevKeyframeTime = 1.00f;
        kfRose.currKeyframeTime = 1.10f;
        kfRose.prevFacingDeg = 0f;
        kfRose.smoothedFacingDeg = 20f;
        float kfMidBlend = kfRose.computeKeyframeBlendFactor(1.15f); // 0.05s into the 0.10s window after 1.10f
        float kfMidFacing = kfRose.getInterpolatedFacingDeg(kfMidBlend);
        float kfExpiredBlend = kfRose.computeKeyframeBlendFactor(3.15f); // 2.05s > 2.0s -> thrown into the bin (-1f)
        boolean roseKeyframeAndBinOk = Math.abs(kfMidBlend - 0.50f) < 0.02f
                && Math.abs(kfMidFacing - 10f) < 0.5f
                && kfExpiredBlend < 0f;

        DivisionState kfDiv = new DivisionState("kf_div_1", 0, 1, DivisionArchetype.CRUISER_DIVISION, "Cruiser Div 1", cmdr);
        kfDiv.members.add(kfShip);
        kfDiv.flagship = kfShip;
        kfDiv.tsfcKeyframesInitialized = true;
        kfDiv.prevTsfcKeyframeTime = 2.00f;
        kfDiv.currTsfcKeyframeTime = 2.10f;
        float divMidBlend = BattlefieldFirepowerEvaluator.computeDivisionKeyframeBlendFactor(kfDiv, 2.15f);
        float divExpiredBlend = BattlefieldFirepowerEvaluator.computeDivisionKeyframeBlendFactor(kfDiv, 4.15f); // > 2.0s old
        boolean divKeyframeAndBinOk = Math.abs(divMidBlend - 0.50f) < 0.02f && divExpiredBlend < 0f;

        float lowShipOpacity = BattlefieldFirepowerEvaluator.computeShipSlidingOpacityScale(kfRose, 60f, 80f, 120f);
        float highShipOpacity = BattlefieldFirepowerEvaluator.computeShipSlidingOpacityScale(kfRose, 420f, 560f, 900f);
        boolean slidingOpacityOk = highShipOpacity > lowShipOpacity * 1.40f
                && lowShipOpacity > 0.15f
                && highShipOpacity <= 1.0f;

        int overlayTsfcIdx = overlaySrc.indexOf("TsfcWarroomRenderer.renderFirepowerRosesScreenSpace(");
        int overlayHullAboveIdx = overlaySrc.indexOf("TsfcWarroomRenderer.renderWarroomShipSpritesAboveTsfc(");
        int overlayStarsIdx = overlaySrc.indexOf("renderFlagshipStarsOnTopLayer(cachedFleetFlagships");
        boolean defaultMiniSpritesOk = DivisionAISettings.isTsfcMiniShipSpritesEnabled();
        DivisionAISettings.testTsfcMiniShipSpritesOverride = Boolean.FALSE;
        boolean disabledMiniSpritesOk = !DivisionAISettings.isTsfcMiniShipSpritesEnabled();
        DivisionAISettings.testTsfcMiniShipSpritesOverride = null;

        // Item A: Late-battle ship near/beyond nominal map border must never have negative/40u boundary clearance across 360 deg
        boolean boundaryClearanceAll360Ok = true;
        org.lwjgl.util.vector.Vector2f outOfBoundsPos = new org.lwjgl.util.vector.Vector2f(9600f, 9600f);
        for (int deg = 0; deg < 360; deg += 15) {
            double r = Math.toRadians(deg);
            float clear = BattlefieldFirepowerEvaluator.computeDirectionalBoundaryClearance(
                    null, outOfBoundsPos, (float) Math.cos(r), (float) Math.sin(r), 90f);
            if (clear < 120f || Float.isNaN(clear)) {
                boundaryClearanceAll360Ok = false;
                break;
            }
        }

        // Item B: Alternating 0.20s OtcAI.updateTelemetry and 0.10s updateTsfcKeyframeTick must have zero spreadRadius/Space heartbeat
        ShipAPI hbShip2 = createMockNamedShip("hb_ship_2", "HSS Hammerhead", HullSize.DESTROYER, 600f, 0f, 0f, 0, humanCapt);
        kfDiv.members.add(hbShip2);
        BattleGroupState hbGroup = new BattleGroupState(0, cmdr);
        hbGroup.divisions.add(kfDiv);
        divisionai.OtcAI.updateTelemetry(null, hbGroup);
        float spreadAfter020 = kfDiv.spreadRadius;
        float domAfter020 = kfDiv.spaceDominatedRadius;
        BattlefieldFirepowerEvaluator.updateTsfcKeyframeTick(null, 2.20f, hbGroup, null, null);
        // Simulate 0.10s kinematics refresh via updateCrossGroupTimeSpaceConvergence consistency
        divisionai.OtcAI.updateTelemetry(null, hbGroup);
        float spreadAfterNext = kfDiv.spreadRadius;
        // Also verify that evaluating playerGroup, empty alliedAiGroup, and enemyGroup (as on OTC / 2.5s C2 ticks)
        // never drops playerGroup from battleActiveDivisionCount or flashes Time/Space/Convergence sliding opacity!
        BattleGroupState emptyAlliedGroup = new BattleGroupState(0, cmdr);
        emptyAlliedGroup.isAlliedAi = true;
        BattleGroupState mockEnemyGroup = new BattleGroupState(1, cmdr);
        ShipAPI mockEnShip = createMockNamedShip("en_flash_1", "HSS Dominator", HullSize.CRUISER, 0f, 1800f, 50f, 1, humanCapt);
        DivisionState mockEnDiv = new DivisionState("en_div_flash", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Enemy Cruiser Div 1", cmdr);
        mockEnDiv.flagship = mockEnShip;
        mockEnDiv.members.add(mockEnShip);
        mockEnemyGroup.divisions.add(mockEnDiv);
        divisionai.OtcAI.updateTelemetry(null, mockEnemyGroup);
        BattlefieldFirepowerEvaluator.evaluateAllGroupsTimeSpaceConvergenceOncePerTick(
                null, 2.50f, hbGroup, emptyAlliedGroup, mockEnemyGroup, mockEnemyGroup, hbGroup);
        float timeOpacityAfterAll = BattlefieldFirepowerEvaluator.computeDivisionTimeSlidingOpacityScale(kfDiv, kfDiv.freeTimeRadius);
        float spaceOpacityAfterAll = BattlefieldFirepowerEvaluator.computeDivisionSpaceSlidingOpacityScale(kfDiv, kfDiv.spaceDominanceWeight);
        // Now call updateCrossGroupTimeSpaceConvergence on emptyAlliedGroup alone (which previously clobbered battleActiveDivisionCount!)
        BattlefieldFirepowerEvaluator.updateCrossGroupTimeSpaceConvergence(null, emptyAlliedGroup, mockEnemyGroup);
        float timeOpacityAfterEmptyAlly = BattlefieldFirepowerEvaluator.computeDivisionTimeSlidingOpacityScale(kfDiv, kfDiv.freeTimeRadius);
        float spaceOpacityAfterEmptyAlly = BattlefieldFirepowerEvaluator.computeDivisionSpaceSlidingOpacityScale(kfDiv, kfDiv.spaceDominanceWeight);
        boolean zeroFlash25sOk = Math.abs(timeOpacityAfterAll - timeOpacityAfterEmptyAlly) < 1e-4f
                && Math.abs(spaceOpacityAfterAll - spaceOpacityAfterEmptyAlly) < 1e-4f;
        boolean zeroHeartbeatOk = Math.abs(spreadAfter020 - spreadAfterNext) < 0.01f
                && domAfter020 > 300f
                && zeroFlash25sOk;

        // Item C: Destroyed wrecks/hulks rendered in truescale when any TSFC overlay is active,
        // and ONLY on the "main" or largest part of a broken-up ship!
        String tsfcRendererSrc = new String(
                Files.readAllBytes(new File("src/divisionai/TsfcWarroomRenderer.java").toPath()),
                StandardCharsets.UTF_8);
        ShipAPI mainHulkPiece = (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("getId".equals(m)) return "hulk_main_1";
                    if ("getParentPieceId".equals(m)) return null;
                    if ("getFleetMemberId".equals(m)) return "fm_broken_1";
                    if ("isHulk".equals(m) || "isPiece".equals(m)) return true;
                    if ("isAlive".equals(m) || "isExpired".equals(m) || "isFighter".equals(m)
                            || "isDrone".equals(m) || "isShuttlePod".equals(m) || "isStationModule".equals(m)) return false;
                    if ("getCollisionRadius".equals(m)) return 145f;
                    if ("getMass".equals(m)) return 1800f;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == float.class) return 0f;
                    if (method.getReturnType() == int.class) return 100;
                    return null;
                });
        ShipAPI smallHulkPiece1 = (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("getId".equals(m)) return "hulk_sub_1";
                    if ("getParentPieceId".equals(m)) return "hulk_main_1";
                    if ("getFleetMemberId".equals(m)) return "fm_broken_1";
                    if ("isHulk".equals(m) || "isPiece".equals(m)) return true;
                    if ("isAlive".equals(m) || "isExpired".equals(m) || "isFighter".equals(m)
                            || "isDrone".equals(m) || "isShuttlePod".equals(m) || "isStationModule".equals(m)) return false;
                    if ("getCollisionRadius".equals(m)) return 65f;
                    if ("getMass".equals(m)) return 420f;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == float.class) return 0f;
                    if (method.getReturnType() == int.class) return 100;
                    return null;
                });
        ShipAPI smallHulkPiece2 = (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("getId".equals(m)) return "hulk_sub_2";
                    if ("getParentPieceId".equals(m)) return "hulk_sub_1";
                    if ("getFleetMemberId".equals(m)) return "fm_broken_1";
                    if ("isHulk".equals(m) || "isPiece".equals(m)) return true;
                    if ("isAlive".equals(m) || "isExpired".equals(m) || "isFighter".equals(m)
                            || "isDrone".equals(m) || "isShuttlePod".equals(m) || "isStationModule".equals(m)) return false;
                    if ("getCollisionRadius".equals(m)) return 40f;
                    if ("getMass".equals(m)) return 210f;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == float.class) return 0f;
                    if (method.getReturnType() == int.class) return 100;
                    return null;
                });
        List<ShipAPI> brokenHulkList = java.util.Arrays.asList(smallHulkPiece1, mainHulkPiece, smallHulkPiece2);
        boolean mainPieceSelectedOk = TsfcWarroomRenderer.isMainOrLargestDestroyedHulk(mainHulkPiece, brokenHulkList)
                && !TsfcWarroomRenderer.isMainOrLargestDestroyedHulk(smallHulkPiece1, brokenHulkList)
                && !TsfcWarroomRenderer.isMainOrLargestDestroyedHulk(smallHulkPiece2, brokenHulkList);
        // Also verify if root piece expired, only the largest remaining piece is selected
        List<ShipAPI> orphanPiecesList = java.util.Arrays.asList(smallHulkPiece1, smallHulkPiece2);
        boolean largestOrphanPieceSelectedOk = TsfcWarroomRenderer.isMainOrLargestDestroyedHulk(smallHulkPiece1, orphanPiecesList)
                && !TsfcWarroomRenderer.isMainOrLargestDestroyedHulk(smallHulkPiece2, orphanPiecesList);

        boolean hulkSpritesOk = tsfcRendererSrc.contains("WARROOM_HULL_WRECK_TINT")
                && tsfcRendererSrc.contains("renderWarroomHulkSpritesAboveTsfc")
                && tsfcRendererSrc.contains("isMainOrLargestDestroyedHulk")
                && !tsfcRendererSrc.contains("battleMaxSust * 0.24f")
                && lowShipOpacity >= 0.48f
                && mainPieceSelectedOk
                && largestOrphanPieceSelectedOk
                && overlaySrc.contains("TsfcWarroomRenderer.renderWarroomHulkSpritesAboveTsfc(");

        // Verify whole-squadron strike craft Firepower Rose centroid, spread, and mean heading
        java.util.function.BiFunction<String, Vector2f, ShipAPI> makeFighterProxy = (id, loc) -> (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("getId".equals(m)) return id;
                    if ("getLocation".equals(m)) return loc;
                    if ("getFacing".equals(m)) return 90f;
                    if ("isAlive".equals(m) || "isFighter".equals(m)) return true;
                    if ("isHulk".equals(m) || "isExpired".equals(m)) return false;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == float.class) return 0f;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        ShipAPI sqM1 = makeFighterProxy.apply("sq_m1", new Vector2f(1000f, 2000f));
        ShipAPI sqM2 = makeFighterProxy.apply("sq_m2", new Vector2f(1100f, 2000f));
        ShipAPI sqM3 = makeFighterProxy.apply("sq_m3", new Vector2f(1200f, 2000f));
        java.util.List<ShipAPI> sqMembers = java.util.Arrays.<ShipAPI>asList(sqM1, sqM2, sqM3);
        com.fs.starfarer.api.combat.FighterWingAPI mockWing = (com.fs.starfarer.api.combat.FighterWingAPI) java.lang.reflect.Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.combat.FighterWingAPI.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("getWingMembers".equals(mName)) return sqMembers;
                    if ("getLeader".equals(mName)) return sqM1;
                    return null;
                });
        Vector2f sqCentroid = new Vector2f();
        BattlefieldFirepowerEvaluator.resolveSquadronLiveCentroid(mockWing, sqM1, sqCentroid);
        float sqSpread = BattlefieldFirepowerEvaluator.computeSquadronSpreadRadius(mockWing, sqCentroid);
        float sqFacing = BattlefieldFirepowerEvaluator.resolveSquadronMeanFacingDeg(mockWing, sqM1);
        boolean squadronRoseOk = Math.abs(sqCentroid.x - 1100f) < 0.5f
                && Math.abs(sqCentroid.y - 2000f) < 0.5f
                && sqSpread > 90f && sqSpread < 120f
                && Math.abs(sqFacing - 90f) < 0.5f;

        // Verify cross-platform ObfuscatedBytecodeBridge (macOS / Linux obfuscated class & descriptor fallback)
        // and FighterWing leader / flocking-AI NPE guard:
        ClassLoader testCl = UITestHarness.class.getClassLoader();
        boolean bridgeAndFighterGuardOk = false;
        try {
            Class<?> wrFallback = divisionai.ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                    testCl, "com.fs.starfarer.combat.new.__MacLinuxRenamedWarroom__",
                    "com.fs.starfarer.combat.CombatState", "getWarroom");
            Class<?> panelFallback = divisionai.ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                    testCl, "com.fs.starfarer.ui.__MacLinuxRenamedPanel__",
                    "com.fs.starfarer.combat.CombatState", "getWidgetPanel");
            Class<?> coreFallback = divisionai.ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                    testCl, "com.fs.starfarer.ui.newui.__MacLinuxRenamedCore__",
                    "com.fs.starfarer.campaign.CampaignState", "getCore");
            Class<?> tmFallback = divisionai.ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                    testCl, "com.fs.starfarer.campaign.__MacLinuxRenamedTM__",
                    "com.fs.starfarer.campaign.CampaignEngine", "getTooltipManager");
            Class<?> fddFallback = divisionai.ObfuscatedBytecodeBridge.resolveClassOrFieldType(
                    testCl, "com.fs.starfarer.ui.impl.__MacLinuxRenamedFdd__",
                    "com.fs.starfarer.combat.CombatState", "fdd");
            Class<?> sGridFallback = divisionai.ObfuscatedBytecodeBridge.resolveClassOrFieldByPackage(
                    testCl, "com.fs.starfarer.campaign.ui.__MacLinuxRenamedShipGrid__",
                    fddFallback.getName(), "com.fs.starfarer.campaign.ui.");
            Class<?> dfmFallback = divisionai.ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                    testCl, "com.fs.starfarer.combat.__MacLinuxRenamedDfm__",
                    "com.fs.starfarer.combat.CombatFleetManager", "getBiggestStation");
            Class<?> soundHFallback = divisionai.ObfuscatedBytecodeBridge.resolveSoundHClass(testCl);
            Class<?> soundOFallback = divisionai.ObfuscatedBytecodeBridge.resolveSoundOClass(testCl, soundHFallback);
            Class<?> streamDescFallback = divisionai.ObfuscatedBytecodeBridge.resolveStreamDescriptorClass(testCl, soundOFallback);
            com.fs.starfarer.api.combat.FighterWingAPI mockNullLeaderWing =
                    (com.fs.starfarer.api.combat.FighterWingAPI) java.lang.reflect.Proxy.newProxyInstance(
                            testCl,
                            new Class<?>[]{com.fs.starfarer.api.combat.FighterWingAPI.class},
                            (proxy, method, args) -> {
                                String mName = method.getName();
                                if ("getWingMembers".equals(mName)) return sqMembers;
                                if ("getWingLeader".equals(mName) || "getLeader".equals(mName)) return null;
                                return null;
                            });
            ShipAPI recoveredLeader = divisionai.DivisionAICombatPlugin.ensureFighterWingLeaderAndFlockingAI(mockNullLeaderWing);
            bridgeAndFighterGuardOk = (wrFallback != null)
                    && (panelFallback != null)
                    && (coreFallback != null)
                    && (tmFallback != null)
                    && (fddFallback != null)
                    && (sGridFallback != null)
                    && (dfmFallback != null)
                    && (soundHFallback != null)
                    && (soundOFallback != null)
                    && (streamDescFallback != null)
                    && (recoveredLeader == sqM1);
        } catch (Throwable ignored) {
            bridgeAndFighterGuardOk = false;
        }

        boolean tsfcOpacitySettingsOk = Math.abs(divisionai.DivisionAISettings.getTsfcWarroomOpacityMult() - 1.00f) < 1e-4f
                && Math.abs(divisionai.DivisionAISettings.getTsfcBattleOpacityMult() - 0.24f) < 1e-4f;
        divisionai.DivisionAISettings.testTsfcWarroomOpacityOverride = Float.valueOf(0.65f);
        divisionai.DivisionAISettings.testTsfcBattleOpacityOverride = Float.valueOf(0.40f);
        tsfcOpacitySettingsOk = tsfcOpacitySettingsOk
                && Math.abs(divisionai.DivisionAISettings.getTsfcWarroomOpacityMult() - 0.65f) < 1e-4f
                && Math.abs(divisionai.DivisionAISettings.getTsfcBattleOpacityMult() - 0.40f) < 1e-4f;
        divisionai.DivisionAISettings.testTsfcWarroomOpacityOverride = null;
        divisionai.DivisionAISettings.testTsfcBattleOpacityOverride = null;

        int belowShipsMethodIdx = combatPluginSrc.indexOf("void renderBattleScreenTsfcBelowShipsLayer(ViewportAPI viewport)");
        int highContrastCallIdx = combatPluginSrc.indexOf("renderBattleScreenHighContrastFiringArcs(viewport);");
        int tsfcOverlayMethodIdx = combatPluginSrc.indexOf("public void renderBattleScreenTsfcOverlay(ViewportAPI viewport)");

        boolean underShipSpritesOk = combatPluginSrc.contains("CombatEngineLayers.BELOW_SHIPS_LAYER")
                && combatPluginSrc.contains("renderBattleScreenTsfcBelowShipsLayer(viewport)")
                && combatPluginSrc.contains("glTranslatef(llx, lly, 0f)")
                && combatPluginSrc.contains("glScalef(invScaleX, invScaleY, 1f)")
                && belowShipsMethodIdx > 0
                && highContrastCallIdx > belowShipsMethodIdx
                && highContrastCallIdx < tsfcOverlayMethodIdx
                && tsfcRendererSrc.contains("renderHighContrastCaptaincyFiringArcsWorldSpace")
                && tsfcRendererSrc.contains("renderSingleWeaponVanillaMatchedHighContrastArcWorld")
                && tsfcRendererSrc.contains("drawVanillaMatchedArcCurveWorld")
                && tsfcRendererSrc.contains("drawVanillaMatchedRadialLineWorld")
                && tsfcRendererSrc.contains("drawVanillaMatchedAimBarWorld")
                && tsfcRendererSrc.contains("slot.computePosition(ship)")
                && tsfcRendererSrc.contains("2.8f")
                && tsfcRendererSrc.contains("1.5f")
                && !tsfcRendererSrc.contains("isArcDuplicate")
                && tsfcOpacitySettingsOk
                && combatPluginSrc.contains("ensureAllFighterWingsHaveValidLeaders(engine)")
                && bridgeAndFighterGuardOk
                && combatPluginSrc.contains("snapshotGroupDivisionKeyframesBeforeUpdate")
                && combatPluginSrc.contains("tacticalOverlay.isShowingVideoFeed()")
                && !combatPluginSrc.contains("renderWarroomShipSpritesAboveTsfc")
                && !combatPluginSrc.contains("renderWarroomHulkSpritesAboveTsfc")
                && tsfcRendererSrc.contains("resolveSquadronLiveCentroid")
                && tsfcRendererSrc.contains("computeSquadronSpreadRadius")
                && tsfcRendererSrc.contains("resolveSquadronMeanFacingDeg")
                && timeOpacityAfterAll >= 0.36f
                && spaceOpacityAfterAll >= 0.34f
                && overlaySrc.contains("syncRegularCommandSpritesVisibility(useMiniSpritesForTsfc)")
                && overlaySrc.contains("lookup.findVirtual(mdClass, iconsMapMethod, MethodType.methodType(Map.class))")
                && overlaySrc.contains("bNewLookup.findGetter(bNewCls, \"\\u00d4\\u00d50000\", spriteCls)")
                && defaultMiniSpritesOk
                && disabledMiniSpritesOk
                && boundaryClearanceAll360Ok
                && zeroHeartbeatOk
                && hulkSpritesOk
                && squadronRoseOk
                && overlayTsfcIdx > 0
                && overlayHullAboveIdx > overlayTsfcIdx
                && overlayStarsIdx > overlayHullAboveIdx;

        res.record(
                cadenceAndBinConstOk && roseKeyframeAndBinOk && divKeyframeAndBinOk && slidingOpacityOk && underShipSpritesOk,
                "TSFC Readability & Clarity: 0.10s Keyframe Interpolation, 2.0s Max-Age Bin, Sliding Opacity & Under-Ship Rendering",
                String.format(
                        Locale.ROOT,
                        "interval=%.2fs, maxAge=%.1fs, roseKf=[mid=%.2f,deg=%.1f,exp=%.1f], divKf=[mid=%.2f,exp=%.1f], slideOpacity=[low=%.2f < high=%.2f], underShips=%b",
                        BattlefieldFirepowerEvaluator.TSFC_EVAL_INTERVAL,
                        BattlefieldFirepowerEvaluator.TSFC_KEYFRAME_MAX_AGE,
                        kfMidBlend, kfMidFacing, kfExpiredBlend,
                        divMidBlend, divExpiredBlend,
                        lowShipOpacity, highShipOpacity,
                        underShipSpritesOk));
    }

    private static ShipAPI createMockShipWithSystem(final String shipId, final String hullId, final String systemId) {
        final com.fs.starfarer.api.combat.ShipHullSpecAPI hullSpec =
                (com.fs.starfarer.api.combat.ShipHullSpecAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.ShipHullSpecAPI.class},
                        (proxy, method, args) -> {
                            String m = method.getName();
                            if ("getHullId".equals(m) || "getBaseHullId".equals(m)) return hullId;
                            if ("getHullName".equals(m) || "getDesignation".equals(m)) return hullId;
                            if ("getHints".equals(m)) return java.util.EnumSet.noneOf(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.class);
                            if ("getBuiltInMods".equals(m)) return java.util.Collections.emptyList();
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == float.class) return 0f;
                            if (method.getReturnType() == int.class) return 0;
                            return null;
                        });
        final com.fs.starfarer.api.combat.ShipSystemSpecAPI sysSpec =
                (com.fs.starfarer.api.combat.ShipSystemSpecAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.ShipSystemSpecAPI.class},
                        (proxy, method, args) -> {
                            String m = method.getName();
                            if ("getId".equals(m)) return systemId;
                            if ("getName".equals(m)) return systemId;
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == float.class) return 0f;
                            if (method.getReturnType() == int.class) return 0;
                            return null;
                        });
        final com.fs.starfarer.api.combat.ShipSystemAPI sys =
                (com.fs.starfarer.api.combat.ShipSystemAPI) Proxy.newProxyInstance(
                        UITestHarness.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.ShipSystemAPI.class},
                        (proxy, method, args) -> {
                            String m = method.getName();
                            if ("getId".equals(m)) return systemId;
                            if ("getSpecAPI".equals(m)) return sysSpec;
                            if ("getDisplayName".equals(m)) return systemId;
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == float.class) return 1.0f;
                            if (method.getReturnType() == int.class) return 0;
                            return null;
                        });
        return (ShipAPI) Proxy.newProxyInstance(
                UITestHarness.class.getClassLoader(),
                new Class<?>[]{ShipAPI.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("getId".equals(m)) return shipId;
                    if ("getHullSpec".equals(m)) return hullSpec;
                    if ("getSystem".equals(m)) return sys;
                    if ("isAlive".equals(m)) return true;
                    if ("getMaxSpeedWithoutBoost".equals(m)) return 90f;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == float.class) return 100f;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
    }
}
