package divisionai.sim;

import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.FullName.Gender;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import divisionai.BattlefieldFirepowerEvaluator;
import divisionai.TsfcWarroomRenderer;
import divisionai.BattlefieldFirepowerEvaluator.PointFirepowerEval;
import divisionai.BattlefieldFirepowerEvaluator.ShipFirepowerRose;
import divisionai.DivisionCommanderAI;
import divisionai.DivisionFormationNavigator;
import divisionai.ShipSystemCoordinator;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionIntentType;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionNomenclature;
import divisionai.DivisionNomenclature.NomenclatureCategory;
import divisionai.DivisionTacticalMapOverlay;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.sim.SimArena.ScenarioRecording;
import divisionai.sim.SimArena.SimShipState;
import org.lwjgl.util.vector.Vector2f;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Automated Tactical C2 & Campaign Test Suite for Starsector Division AI.
 *
 * Executes multi-ship 2D tactical scenarios at >500x real-time speed and verifies:
 *  1. Core-Cluster Cohesion & Straggler Wide-Arc Rejoin (no CLOSE_UP stranding on flanks)
 *  2. Division-Level Out-of-Contact Wide Flank Sweep & Double Envelopment (Pincer / Doubling)
 *  3. High-Flux Line Rotation (HAUL_OUT_TO_VENT) & Crisis Relief
 *  4. Time-on-Target Coordinated Strike Staging & Execution
 *  5. Cultural & Polity Nomenclature Diversity + Shared Prefix Bucket Numbering
 *  6. Zero java/lang/reflect References in DivisionAI.jar
 */
public class DivisionAITestSuite {

    private static int passed = 0;
    private static int failed = 0;
    private static final List<ScenarioRecording> recordedScenarios = new ArrayList<>();

    private static void check(boolean condition, String description, String details) {
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + description + " -> " + details);
        } else {
            failed++;
            System.out.println("  [FAIL] " + description + " -> " + details);
        }
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        System.out.println("==========================================================================");
        System.out.println("  STARSECTOR DIVISION AI — HEADLESS TACTICAL C2 & CAMPAIGN TEST SUITE");
        System.out.println("==========================================================================\n");

        testStrandedFrigateFlankAndStragglerRejoin();
        testDivisionWideFlankAndDoubleEnvelopment();
        testHighFluxLineRotationAndRelief();
        testTimeOnTargetCoordinatedStrike();
        testFleetWideAdvanceFormationAndAuthority();
        testCulturalNomenclatureAndPrefixBuckets();
        testFluidDivisionPolygonsAndOverlapStriping();
        testWeaponArcFirepowerSpaceAndCordonAvoidance();
        testHelmsmanshipMixedSpeedPacingExtractionAndPlayerC2();
        testBabylonStationBastionTargetingAndModuleFocusFire();
        testFeignedRetreatForceDisparityFriendlyProtectionAndMinimalSignals();
        testPursuitDeploymentAndRearguardC2();
        testAdvancedStrikeCraftGunneryAndHierarchicalSectors();
        testPirateHordeC2AndVoidstrikeLifecycle();
        testBattle2StrikecraftAndMultiCarrierFixes();
        testDecoupledHullAndStrikeCraftMissions();
        testCampaignLogPolishAndC2Hygiene();
        testMilitaryFirstNomenclatureSweep();
        testUIHarnessAndUXSuccinctness();
        testZeroReflectionInCompiledJar();

        File replayOut = new File("build/sim_replay.html");
        SimArena.exportHtmlReplay(recordedScenarios, replayOut);

        long elapsedMs = System.currentTimeMillis() - t0;
        System.out.println("\n==========================================================================");
        System.out.println(String.format(Locale.ROOT,
                "  SUMMARY: %d passed, %d failed (completed in %d ms)",
                passed, failed, elapsedMs));
        System.out.println("  Interactive HTML5 Replay exported to: " + replayOut.getAbsolutePath());
        System.out.println("==========================================================================");

        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * Scenario 1: 3 frigates of "Frigate Div 1" have achieved a flanking position at (-1500, 1050)
     * on "Enemy Cruiser Div 1" (0, 0), while a 4th stray frigate ("s4_stray") is cut off behind
     * the enemy battle line at (120, -2150).
     *
     * Verifies:
     *  - The 3-ship Core Cluster on the flank NEVER freezes in CLOSE_UP waiting for the cut-off straggler.
     *  - The cut-off straggler skirts wide around the outside of the enemy Cruiser Line (never cutting
     *    through the center within < 1150u) and successfully rejoins the Core Cluster on the flank!
     */
    private static void testStrandedFrigateFlankAndStragglerRejoin() {
        System.out.println("--- Scenario 1: Stranded Frigate Flank & Cut-Off Straggler Wide-Arc Rejoin ---");
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "steady");

        BattleGroupState playerGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState enemyGroup = new BattleGroupState(1, eCmdr);

        DivisionState mainLine = new DivisionState("p_main", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div. I", pCmdr);
        mainLine.openingMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
        mainLine.mainMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
        mainLine.standoffRange = 1350f;

        DivisionState frigDiv = new DivisionState("p_frig1", 0, 1, DivisionArchetype.FRIGATE_DIVISION, "Frigate Div 1", pCmdr);
        frigDiv.openingMission = DivisionMissionType.ENVELOPMENT;
        frigDiv.mainMission = DivisionMissionType.ENVELOPMENT;
        frigDiv.standoffRange = 850f;
        frigDiv.stationSpacing = 320f;

        playerGroup.divisions.add(mainLine);
        playerGroup.divisions.add(frigDiv);

        DivisionState enemyCruisers = new DivisionState("e_cru1", 1, 1, DivisionArchetype.CRUISER_DIVISION, "1st Group", eCmdr);
        enemyCruisers.openingMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
        enemyCruisers.mainMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
        enemyCruisers.standoffRange = 1200f;
        enemyGroup.divisions.add(enemyCruisers);

        SimArena arena = new SimArena(
                "1. Stranded Frigate Flank & Straggler Wide-Arc Rejoin",
                "3 frigates hold the port flank while 1 cut-off straggler behind the enemy cruiser line routes wide out of contact to rejoin.",
                playerGroup,
                enemyGroup);

        // Friendly battleship division fixing from (-200, -1450)
        arena.addShip(mainLine, "b1", "HSS Onslaught", "Onslaught", HullSize.CAPITAL_SHIP, -200f, -1450f, 55f, 1350f, 0, 0);
        arena.addShip(mainLine, "b2", "HSS Dominator", "Dominator", HullSize.CRUISER, 120f, -1500f, 65f, 1250f, 0, 0);

        // 3 frigates already on the port flank (-1500, 950) + 1 stray frigate cut off at (150, -2200)
        SimShipState f1 = arena.addShip(frigDiv, "f1_flag", "RDS Lasher", "Lasher", HullSize.FRIGATE, -1500f, 950f, 165f, 800f, 0, 0);
        arena.addShip(frigDiv, "f2_wing", "RDS Wolf", "Wolf", HullSize.FRIGATE, -1350f, 1080f, 175f, 800f, 0, 0);
        arena.addShip(frigDiv, "f3_wing", "RDS Brawler", "Brawler", HullSize.FRIGATE, -1620f, 820f, 165f, 800f, 0, 0);
        SimShipState f4Stray = arena.addShip(frigDiv, "f4_stray", "RDS Hound", "Hound", HullSize.FRIGATE, 150f, -2200f, 180f, 750f, 0, 0);

        // Enemy Cruiser Line holding (0, 0)
        arena.addShip(enemyCruisers, "e1", "Eagle", "Eagle", HullSize.CRUISER, -120f, 0f, 45f, 1250f, 0, 0);
        arena.addShip(enemyCruisers, "e2", "Falcon", "Falcon", HullSize.CRUISER, 180f, 60f, 50f, 1200f, 0, 0);

        int closeUpFrames = 0;
        boolean initiallyDetached = false;
        float minStrayDistToEnemyCenter = Float.MAX_VALUE;

        // Step in 1s increments to inspect telemetry across 55 seconds
        for (int s = 0; s < 55; s++) {
            arena.runSimulation(1.0f);
            if (s == 0) {
                initiallyDetached = frigDiv.detachedStragglerIds.contains("f4_stray");
            }
            if (frigDiv.currentIntent == DivisionIntentType.CLOSE_UP) {
                closeUpFrames++;
            }
            float dToEnemy = dist(f4Stray.location, enemyCruisers.centroid);
            if (dToEnemy < minStrayDistToEnemyCenter) {
                minStrayDistToEnemyCenter = dToEnemy;
            }
        }
        recordedScenarios.add(arena.recording);

        float finalDistToFlagship = dist(f4Stray.location, f1.location);
        check(initiallyDetached,
                "Cut-off straggler initially isolated in detachedStragglerIds",
                "detached=" + initiallyDetached + ", initial rawSpread=" + String.format(Locale.ROOT, "%.0fu", frigDiv.rawAllMemberSpreadRadius));
        check(closeUpFrames == 0,
                "Flanking Frigate Division Core Cluster never freezes in CLOSE_UP",
                "closeUpFrames=" + closeUpFrames + ", finalIntent=" + frigDiv.currentIntent);
        check(minStrayDistToEnemyCenter > 1100f,
                "Straggler skirts wide out of contact around Enemy Cruiser Line",
                String.format(Locale.ROOT, "minClearanceFromEnemyCenter=%.0fu", minStrayDistToEnemyCenter));
        check(finalDistToFlagship < 850f && !frigDiv.detachedStragglerIds.contains("f4_stray"),
                "Straggler successfully rejoins its division on the flank",
                String.format(Locale.ROOT, "finalDistToFlagship=%.0fu, detachedAfterRejoin=%b",
                        finalDistToFlagship, frigDiv.detachedStragglerIds.contains("f4_stray")));
    }

    /**
     * Scenario 2: A Frigate Division starts behind the right side of the friendly line (600, -1900)
     * and is ordered by the OTC (POSITIVE_CONTROL) to take the Port Flank (-X) of an Enemy Cruiser Line (0, 0),
     * while a second Frigate Division takes the Starboard Flank (+X).
     *
     * Verifies that the cross-line flanker uses a wide out-of-contact routeWaypoint (hasRouteWaypoint == true)
     * and both flankers achieve opposite flanks of the enemy line.
     */
    private static void testDivisionWideFlankAndDoubleEnvelopment() {
        System.out.println("\n--- Scenario 2: Division Wide Flank Out-of-Contact Sweep & Double Envelopment ---");
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "steady");

        BattleGroupState playerGroup = new BattleGroupState(0, pCmdr);
        playerGroup.authority = OtcAuthority.POSITIVE_CONTROL;
        BattleGroupState enemyGroup = new BattleGroupState(1, eCmdr);

        DivisionState mainLine = new DivisionState("p_main", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div. I", pCmdr);
        mainLine.openingMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
        mainLine.mainMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
        mainLine.standoffRange = 1350f;

        DivisionState portFlankDiv = new DivisionState("p_f1", 0, 1, DivisionArchetype.FRIGATE_DIVISION, "Frigate Div 1", pCmdr);
        portFlankDiv.openingMission = DivisionMissionType.ENVELOPMENT;
        portFlankDiv.mainMission = DivisionMissionType.ENVELOPMENT;
        portFlankDiv.standoffRange = 950f;

        DivisionState stbdFlankDiv = new DivisionState("p_f2", 0, 2, DivisionArchetype.FRIGATE_DIVISION, "Frigate Div 2", pCmdr);
        stbdFlankDiv.openingMission = DivisionMissionType.ENVELOPMENT;
        stbdFlankDiv.mainMission = DivisionMissionType.ENVELOPMENT;
        stbdFlankDiv.standoffRange = 950f;

        playerGroup.divisions.add(mainLine);
        playerGroup.divisions.add(portFlankDiv);
        playerGroup.divisions.add(stbdFlankDiv);

        DivisionState enemyMain = new DivisionState("e_main", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Warband I", eCmdr);
        enemyMain.openingMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
        enemyMain.mainMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
        enemyMain.standoffRange = 1200f;
        enemyGroup.divisions.add(enemyMain);

        SimArena arena = new SimArena(
                "2. Wide Out-of-Contact Flank & Double Envelopment",
                "Main Effort fixes Warband I while Frigate Div 1 and Frigate Div 2 sweep wide onto opposite Port and Starboard flanks.",
                playerGroup,
                enemyGroup);

        arena.addShip(mainLine, "m1", "Paragon", "Paragon", HullSize.CAPITAL_SHIP, 0f, -1450f, 50f, 1400f, 0, 0);
        arena.addShip(portFlankDiv, "pf1", "Tempest 1", "Tempest", HullSize.FRIGATE, -450f, -1750f, 175f, 850f, 0, 0);
        arena.addShip(portFlankDiv, "pf2", "Tempest 2", "Tempest", HullSize.FRIGATE, -580f, -1820f, 175f, 850f, 0, 0);
        arena.addShip(stbdFlankDiv, "sf1", "Omen 1", "Omen", HullSize.FRIGATE, 450f, -1750f, 175f, 850f, 0, 0);
        arena.addShip(stbdFlankDiv, "sf2", "Omen 2", "Omen", HullSize.FRIGATE, 580f, -1820f, 175f, 850f, 0, 0);

        arena.addShip(enemyMain, "em1", "Dominator", "Dominator", HullSize.CRUISER, -140f, 0f, 40f, 1200f, 0, 0);
        arena.addShip(enemyMain, "em2", "Eradicator", "Eradicator", HullSize.CRUISER, 140f, 0f, 40f, 1200f, 0, 0);

        boolean usedWideRouteWaypoint = false;
        boolean oppositeFlanksAchieved = false;
        float maxFlankSpan = 0f;
        for (int s = 0; s < 40; s++) {
            arena.runSimulation(1.0f);
            if (portFlankDiv.hasRouteWaypoint || stbdFlankDiv.hasRouteWaypoint) {
                usedWideRouteWaypoint = true;
            }
            float span = stbdFlankDiv.centroid.x - portFlankDiv.centroid.x;
            if (portFlankDiv.centroid.x < -750f && stbdFlankDiv.centroid.x > 750f && span > maxFlankSpan) {
                maxFlankSpan = span;
                if (span > 1600f) {
                    oppositeFlanksAchieved = true;
                }
            }
        }
        recordedScenarios.add(arena.recording);

        check(usedWideRouteWaypoint,
                "Flanking divisions project out-of-contact wide-arc routeWaypoints around enemy gun ring",
                "usedWideRouteWaypoint=" + usedWideRouteWaypoint);
        check(oppositeFlanksAchieved,
                "Port and Starboard flankers envelop enemy division from opposite sides",
                String.format(Locale.ROOT, "maxOppositeFlankSpan=%.0fu", maxFlankSpan));
    }

    /**
     * Scenario 3: High-flux division rotation (HAUL_OUT_TO_VENT) behind the Main Effort, followed by re-engaging once vented.
     */
    private static void testHighFluxLineRotationAndRelief() {
        System.out.println("\n--- Scenario 3: High-Flux Line Rotation (HAUL_OUT_TO_VENT) ---");
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        BattleGroupState playerGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState enemyGroup = new BattleGroupState(1, pCmdr);

        DivisionState anchorDiv = new DivisionState("p_anc", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div. I", pCmdr);
        DivisionState pressuredDiv = new DivisionState("p_cru", 0, 1, DivisionArchetype.CRUISER_DIVISION, "Cruiser Div 1", pCmdr);
        playerGroup.divisions.add(anchorDiv);
        playerGroup.divisions.add(pressuredDiv);

        DivisionState enemyDiv = new DivisionState("e_1", 1, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Enemy Battle Div. I", pCmdr);
        enemyGroup.divisions.add(enemyDiv);

        SimArena arena = new SimArena(
                "3. High-Flux Line Rotation (Haul Out to Vent)",
                "Cruiser Div 1 at 78% flux rotates behind Battle Div. I to vent before returning to the line.",
                playerGroup,
                enemyGroup);

        arena.addShip(anchorDiv, "a1", "Onslaught", "Onslaught", HullSize.CAPITAL_SHIP, 0f, -900f, 55f, 1300f, 0, 0);
        SimShipState c1 = arena.addShip(pressuredDiv, "c1", "Eagle 1", "Eagle", HullSize.CRUISER, 0f, -400f, 110f, 1100f, 0, 0);
        SimShipState c2 = arena.addShip(pressuredDiv, "c2", "Eagle 2", "Eagle", HullSize.CRUISER, 220f, -420f, 110f, 1100f, 0, 0);
        c1.fluxLevel = 0.78f;
        c2.fluxLevel = 0.76f;

        arena.addShip(enemyDiv, "e1", "Enemy Onslaught", "Onslaught", HullSize.CAPITAL_SHIP, 0f, 650f, 40f, 1200f, 0, 0);

        boolean enteredHaulOut = false;
        boolean pulledBehindAnchorWhileVenting = false;
        boolean reEngagedAfterVenting = false;
        float minVentedFlux = 1.0f;
        for (int s = 0; s < 25; s++) {
            arena.runSimulation(1.0f);
            if (pressuredDiv.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT) {
                enteredHaulOut = true;
                if (pressuredDiv.centroid.y < anchorDiv.centroid.y) {
                    pulledBehindAnchorWhileVenting = true;
                }
            }
            if (pressuredDiv.avgFluxLevel < minVentedFlux) {
                minVentedFlux = pressuredDiv.avgFluxLevel;
            }
            if (enteredHaulOut && pressuredDiv.avgFluxLevel < 0.36f
                    && pressuredDiv.currentIntent == DivisionIntentType.CLOSE_AND_ENGAGE) {
                reEngagedAfterVenting = true;
            }
        }
        recordedScenarios.add(arena.recording);

        check(enteredHaulOut,
                "High-flux Cruiser Division transitions to HAUL_OUT_TO_VENT",
                "enteredHaulOut=" + enteredHaulOut);
        check(pulledBehindAnchorWhileVenting && reEngagedAfterVenting,
                "Cruiser Division pulls behind Battle Div. I, vents flux, and re-engages",
                String.format(Locale.ROOT, "pulledBehindAnchor=%b, minFlux=%.0f%%, reEngaged=%b",
                        pulledBehindAnchorWhileVenting, minVentedFlux * 100f, reEngagedAfterVenting));
    }

    /**
     * Scenario 4: Time-on-Target Coordinated Strike staging & execution.
     */
    private static void testTimeOnTargetCoordinatedStrike() {
        System.out.println("\n--- Scenario 4: Time-on-Target Coordinated Strike ---");
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        BattleGroupState playerGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState enemyGroup = new BattleGroupState(1, pCmdr);

        DivisionState mainDiv = new DivisionState("p_main", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div. I", pCmdr);
        DivisionState strikeDiv = new DivisionState("p_strk", 0, 1, DivisionArchetype.MISSILE_DESTROYER_DIVISION, "Destroyer Div 1", pCmdr);
        strikeDiv.openingMission = DivisionMissionType.ENVELOPMENT;
        strikeDiv.mainMission = DivisionMissionType.ENVELOPMENT;
        playerGroup.divisions.add(mainDiv);
        playerGroup.divisions.add(strikeDiv);

        DivisionState enemyDiv = new DivisionState("e_1", 1, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Enemy Battle Div. I", pCmdr);
        enemyGroup.divisions.add(enemyDiv);

        SimArena arena = new SimArena(
                "4. Time-on-Target Coordinated Strike",
                "Battle Div. I fixes Enemy Battle Div. I while Destroyer Div 1 marshals and executes a synchronized torpedo strike.",
                playerGroup,
                enemyGroup);

        arena.addShip(mainDiv, "m1", "Onslaught", "Onslaught", HullSize.CAPITAL_SHIP, 0f, -1100f, 55f, 1300f, 0, 0);
        arena.addShip(strikeDiv, "d1", "Hammerhead 1", "Hammerhead", HullSize.DESTROYER, -700f, -1800f, 130f, 1000f, 6, 0);
        arena.addShip(strikeDiv, "d2", "Hammerhead 2", "Hammerhead", HullSize.DESTROYER, -900f, -1850f, 130f, 1000f, 6, 0);
        arena.addShip(enemyDiv, "e1", "Enemy Paragon", "Paragon", HullSize.CAPITAL_SHIP, 0f, 200f, 35f, 1300f, 0, 0);

        boolean marshaled = false;
        boolean executedStrike = false;
        for (int s = 0; s < 30; s++) {
            arena.runSimulation(1.0f);
            if (strikeDiv.currentIntent == DivisionIntentType.MARSHAL_FOR_STRIKE) marshaled = true;
            if (strikeDiv.currentIntent == DivisionIntentType.TOT_MISSILE_STRIKE) executedStrike = true;
        }
        recordedScenarios.add(arena.recording);

        check(marshaled && executedStrike,
                "Strike division marshals and executes synchronized TOT_MISSILE_STRIKE",
                "marshaled=" + marshaled + ", executedStrike=" + executedStrike);
    }

    /**
     * Fleet-wide advance: Positive Control preserves both wedge depth and lateral spacing in contact;
     * Command by Direction uses a softer pull; Command by Negation leaves contact to division initiative;
     * Fire Distribution Only never repositions divisions after deployment.
     */
    private static void testFleetWideAdvanceFormationAndAuthority() {
        System.out.println("\n--- Fleet-Wide Advance in Formation: Two-Axis Station Keeping & Authority ---");
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "steady");
        BattleGroupState group = new BattleGroupState(0, pCmdr);
        BattleGroupState enemyGroup = new BattleGroupState(1, eCmdr);
        DivisionState main = new DivisionState("fw_main", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div 1", pCmdr);
        DivisionState wing = new DivisionState("fw_wing", 0, 2, DivisionArchetype.CRUISER_DIVISION, "Cruiser Div 1", pCmdr);
        DivisionState enemy = new DivisionState("fw_enemy", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Enemy Div 1", eCmdr);
        group.divisions.add(main);
        group.divisions.add(wing);
        enemyGroup.divisions.add(enemy);
        SimArena arena = new SimArena("Fleet Advance Authority", "Test", group, enemyGroup);
        arena.addShip(main, "fw_m1", "Onslaught", "Onslaught", HullSize.CAPITAL_SHIP, 0f, 0f, 55f, 1200f, 0, 0);
        arena.addShip(wing, "fw_w1", "Eagle", "Eagle", HullSize.CRUISER, -700f, -500f, 70f, 1100f, 0, 0);
        arena.addShip(enemy, "fw_e1", "Eagle", "Eagle", HullSize.CRUISER, 0f, 2000f, 70f, 1100f, 1, 0);
        group.mainEffortDivision = main;
        group.primaryThreatEnemyDivision = enemy;
        group.fleetCentroid.set(0f, 0f);
        main.centroid.set(0f, 0f);
        main.fleetFormationOffset.set(0f, 420f);
        main.hasFleetFormationOffset = true;
        wing.centroid.set(-700f, -500f);
        wing.fleetFormationOffset.set(-800f, -60f);
        wing.hasFleetFormationOffset = true;
        wing.currentIntent = DivisionIntentType.CLOSE_AND_ENGAGE;
        wing.openingMission = DivisionMissionType.CLOSE_ACTION;
        wing.mainMission = DivisionMissionType.CLOSE_ACTION;
        wing.localEnemyStrength = 10f;
        enemy.centroid.set(0f, 2000f);

        group.authority = OtcAuthority.POSITIVE_CONTROL;
        wing.stationPoint.set(0f, 1000f);
        DivisionCommanderAI.applyFleetLateralStationKeeping(null, wing, group, 10f);
        boolean positiveTwoAxis = wing.stationPoint.x < -300f && wing.stationPoint.y < 500f;
        check(positiveTwoAxis,
                "Positive Control preserves wedge lateral AND depth station during enemy contact",
                String.format(Locale.ROOT, "station=(%.0f, %.0f), expected near (-800, -480)", wing.stationPoint.x, wing.stationPoint.y));

        group.authority = OtcAuthority.COMMAND_BY_DIRECTION;
        wing.stationPoint.set(0f, 1000f);
        DivisionCommanderAI.applyFleetLateralStationKeeping(null, wing, group, 10f);
        boolean directionSofter = wing.stationPoint.x < -150f && wing.stationPoint.x > -440f
                && wing.stationPoint.y <= 705f && wing.stationPoint.y >= 650f;
        check(directionSofter,
                "Command by Direction applies a softer two-axis formation pull in contact",
                String.format(Locale.ROOT, "station=(%.0f, %.0f)", wing.stationPoint.x, wing.stationPoint.y));

        group.authority = OtcAuthority.COMMAND_BY_NEGATION;
        wing.stationPoint.set(0f, 1000f);
        DivisionCommanderAI.applyFleetLateralStationKeeping(null, wing, group, 10f);
        boolean negationLocal = Math.abs(wing.stationPoint.x) < 1f && Math.abs(wing.stationPoint.y - 1000f) < 1f;
        group.authority = OtcAuthority.FIRE_DISTRIBUTION_ONLY;
        DivisionCommanderAI.applyFleetLateralStationKeeping(null, wing, group, 10f);
        boolean fireOnlyLocal = Math.abs(wing.stationPoint.x) < 1f && Math.abs(wing.stationPoint.y - 1000f) < 1f;
        check(negationLocal && fireOnlyLocal,
                "Command by Negation and Fire Distribution Only leave contact maneuver to division authority",
                String.format(Locale.ROOT, "negation=%b, fireOnly=%b, station=(%.0f, %.0f)",
                        negationLocal, fireOnlyLocal, wing.stationPoint.x, wing.stationPoint.y));
    }

    /**
     * Scenario 5: Cultural & Polity Nomenclature Diversity and Shared Prefix Bucket Numbering.
     */
    private static void testCulturalNomenclatureAndPrefixBuckets() {
        System.out.println("\n--- Scenario 5: Cultural Nomenclature & Shared Prefix Bucket Numbering ---");
        final FullName vegaName = new FullName("James", "Vega", Gender.MALE);
        PersonAPI vega = (PersonAPI) Proxy.newProxyInstance(
                PersonAPI.class.getClassLoader(),
                new Class<?>[]{PersonAPI.class},
                (p, m, a) -> {
                    if ("getName".equals(m.getName())) return vegaName;
                    if ("isDefault".equals(m.getName())) return Boolean.FALSE;
                    return null;
                });

        boolean sawCommanderPossessive = false;
        boolean sawCulturalNoun = false;
        for (int i = 1; i <= 16; i++) {
            String name = DivisionNomenclature.formatFactionDivisionName(
                    "pirates", NomenclatureCategory.HUMAN_NAVAL, DivisionArchetype.CRUISER_DIVISION, i, vega, 1000L + i * 7919L);
            if (name.contains("Vega") || name.contains("James")) sawCommanderPossessive = true;
            if (name.contains("Warband") || name.contains("Flotilla") || name.contains("Pack") || name.contains("Gang")) {
                sawCulturalNoun = true;
            }
        }
        String hegBattle1 = DivisionNomenclature.formatFactionDivisionName(
                "hegemony", NomenclatureCategory.HUMAN_NAVAL, DivisionArchetype.BATTLESHIP_DIVISION, 1, null, 0L);

        boolean sharedCarrierBucket = DivisionNomenclature.resolvePrefixBucketKey(DivisionArchetype.LIGHT_CARRIER_DIVISION)
                .equals(DivisionNomenclature.resolvePrefixBucketKey(DivisionArchetype.ESCORT_CARRIER_DIVISION));

        check(sawCommanderPossessive && sawCulturalNoun,
                "Pirate/Independent cultural nomenclature produces commander possessives and diverse nouns",
                "sawCommanderPossessive=" + sawCommanderPossessive + ", sawCulturalNoun=" + sawCulturalNoun);
        check("Battle Div. I".equals(hegBattle1) && sharedCarrierBucket,
                "Standard Battleship Division formats as 'Battle Div. I' and Carrier sub-archetypes share numbering bucket",
                "hegBattle1='" + hegBattle1 + "', sharedCarrierBucket=" + sharedCarrierBucket);

        // Verify Luddic Path Martyrdom combat execution (charges point-blank without backing off or hauling out to vent)
        CommanderProfile lpCmdr = new CommanderProfile(null, "reckless");
        CommanderProfile hegCmdr = new CommanderProfile(null, "steady");
        BattleGroupState lpGroup = new BattleGroupState(1, lpCmdr);
        BattleGroupState hegGroup = new BattleGroupState(0, hegCmdr);
        DivisionState lpDiv = new DivisionState("lp_1", 1, 1, DivisionArchetype.CRUISER_DIVISION, "1st Assault Cell", lpCmdr);
        lpDiv.openingMission = DivisionMissionType.MARTYRDOM;
        lpDiv.mainMission = DivisionMissionType.MARTYRDOM;
        lpDiv.standoffRange = 400f;
        lpGroup.divisions.add(lpDiv);

        DivisionState hegDiv = new DivisionState("heg_1", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div. I", hegCmdr);
        hegGroup.divisions.add(hegDiv);

        SimArena lpArena = new SimArena(
                "5b. Luddic Path Martyrdom Charge",
                "Luddic Path Martyrdom division charges point-blank into Enemy Battle Div. I without backing off or hauling out to vent at 82% flux.",
                hegGroup,
                lpGroup);
        SimShipState v1 = lpArena.addShip(lpDiv, "lp1", "LPS Prometheus", "prometheus2", HullSize.CAPITAL_SHIP, 0f, 950f, 90f, 700f, 2, 0);
        SimShipState v2 = lpArena.addShip(lpDiv, "lp2", "Blessed Eradicator", "eradicator", HullSize.CRUISER, 120f, 980f, 110f, 800f, 2, 0);
        lpArena.addShip(hegDiv, "h1", "HSS Onslaught", "Onslaught", HullSize.CAPITAL_SHIP, 0f, 0f, 55f, 1300f, 0, 0);
        v1.fluxLevel = 0.82f;
        v2.fluxLevel = 0.82f;

        boolean pressedHome = false;
        boolean everBackedOffOrHauledOut = false;
        boolean sawMartyrCampClose = false;
        boolean hegLineKeptStrafeUnimpeded = false;
        for (int s = 0; s < 10; s++) {
            v1.fluxLevel = 0.82f;
            v2.fluxLevel = 0.82f;
            lpArena.runSimulation(1.0f);

            if (lpDiv.currentIntent == DivisionIntentType.PRESS_HOME) {
                pressedHome = true;
                if (v1.location.length() > 450f && v1.aiFlags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.CAMP_LOCATION)) {
                    sawMartyrCampClose = true;
                }
            }
            if (hegDiv.currentIntent == DivisionIntentType.FORM_LINE_AND_FIX
                    || hegDiv.currentIntent == DivisionIntentType.CLOSE_AND_ENGAGE) {
                if (v1.location.length() <= 1150f && v1.location.length() >= 750f) {
                    hegLineKeptStrafeUnimpeded = true;
                }
            }
            if (lpDiv.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE
                    || lpDiv.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT) {
                everBackedOffOrHauledOut = true;
            }
        }
        check(pressedHome && !everBackedOffOrHauledOut && sawMartyrCampClose && hegLineKeptStrafeUnimpeded,
                "Luddic Path Martyrdom division executes point-blank PRESS_HOME charge without backing off or hauling out at 82% flux",
                "pressedHome=" + pressedHome + ", sawMartyrCampClose=" + sawMartyrCampClose
                        + ", everBackedOffOrHauledOut=" + everBackedOffOrHauledOut + ", finalDist=" + Math.round(v1.location.length()) + "u");
    }

    /**
     * Scenario 6: Fluid Division Polygon, Chaikin Smoothing, and Sutherland-Hodgman Overlap Striping.
     */
    private static void testFluidDivisionPolygonsAndOverlapStriping() {
        System.out.println("\n--- Scenario 6: Fluid Division Polygons & Overlap Striping Geometry ---");
        List<Vector2f> divACloud = new ArrayList<>();
        List<Vector2f> divBCloud = new ArrayList<>();
        float[][] shipsA = {{100f, 100f}, {160f, 105f}, {130f, 145f}};
        float[][] shipsB = {{140f, 110f}, {200f, 115f}, {170f, 155f}};
        for (float[] s : shipsA) {
            for (int i = 0; i < 16; i++) {
                double ang = (2.0 * Math.PI * i) / 16;
                divACloud.add(new Vector2f(s[0] + (float) Math.cos(ang) * 28f, s[1] + (float) Math.sin(ang) * 28f));
            }
        }
        for (float[] s : shipsB) {
            for (int i = 0; i < 16; i++) {
                double ang = (2.0 * Math.PI * i) / 16;
                divBCloud.add(new Vector2f(s[0] + (float) Math.cos(ang) * 28f, s[1] + (float) Math.sin(ang) * 28f));
            }
        }

        List<Vector2f> polyA = DivisionTacticalMapOverlay.chaikinSmoothClosed(
                DivisionTacticalMapOverlay.chaikinSmoothClosed(
                        DivisionTacticalMapOverlay.computeConvexHullCCW(divACloud)));
        List<Vector2f> polyB = DivisionTacticalMapOverlay.chaikinSmoothClosed(
                DivisionTacticalMapOverlay.chaikinSmoothClosed(
                        DivisionTacticalMapOverlay.computeConvexHullCCW(divBCloud)));

        float areaA = DivisionTacticalMapOverlay.computePolygonArea(polyA);
        float areaB = DivisionTacticalMapOverlay.computePolygonArea(polyB);
        List<Vector2f> overlap = DivisionTacticalMapOverlay.clipConvexPolygon(polyA, polyB);
        float overlapArea = DivisionTacticalMapOverlay.computePolygonArea(overlap);

        int stripeSlices = 0;
        for (int k = -20; k <= 40; k++) {
            List<Vector2f> slab = DivisionTacticalMapOverlay.clipPolygonToDiagonalSlab(overlap, k * 19f, k * 19f + 9.5f);
            if (slab.size() >= 3 && DivisionTacticalMapOverlay.computePolygonArea(slab) > 1f) {
                stripeSlices++;
            }
        }

        check(polyA.size() >= 24 && areaA > 2000f && overlapArea > 500f && overlapArea < Math.min(areaA, areaB),
                "Fluid division polygons compute valid CCW Chaikin hulls and non-empty Sutherland-Hodgman overlap",
                String.format(Locale.ROOT, "vertsA=%d, areaA=%.0f, overlapArea=%.0f", polyA.size(), areaA, overlapArea));
        check(stripeSlices >= 2,
                "Overlapping division intersection slices cleanly into thick diagonal stripe bands",
                "stripeSlices=" + stripeSlices);
    }

    /**
     * Scenario 6b: Weapon-Arc Firepower Space (3-Horizon Alpha / Short DoT / Sustained DoT, General vs PD),
     * L/U-Cordon Crossfire Detection, Blind-Spot Exploitation, Hammerhead Asymmetric-Flux Vent Retention,
     * and Right-Side Battle Screen Firepower Toggle Button (Off by Default).
     */
    private static void testWeaponArcFirepowerSpaceAndCordonAvoidance() {
        System.out.println("\n--- Scenario 6b: Weapon-Arc Firepower Space, L/U-Cordon Avoidance & Hammerhead Vent Retention ---");
        CommanderProfile pCmdr = new CommanderProfile(null, "aggressive");
        CommanderProfile eCmdr = new CommanderProfile(null, "reckless");

        BattleGroupState playerGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState enemyGroup = new BattleGroupState(1, eCmdr);

        DivisionState anchorDiv = new DivisionState("p_anc", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battleship Div 1", pCmdr);
        DivisionState hhDiv = new DivisionState("p_hh2", 0, 2, DivisionArchetype.DESTROYER_DIVISION, "Destroyer Div 2", pCmdr);
        playerGroup.divisions.add(anchorDiv);
        playerGroup.divisions.add(hhDiv);

        DivisionState eFront = new DivisionState("e_front", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Fleet II", eCmdr);
        DivisionState ePort = new DivisionState("e_port", 1, 2, DivisionArchetype.CRUISER_DIVISION, "Rania's I Division", eCmdr);
        DivisionState eStbd = new DivisionState("e_stbd", 1, 3, DivisionArchetype.CRUISER_DIVISION, "Gal's Gang", eCmdr);
        enemyGroup.divisions.add(eFront);
        enemyGroup.divisions.add(ePort);
        enemyGroup.divisions.add(eStbd);

        SimArena arena = new SimArena(
                "6b. Weapon-Arc Firepower Space & L/U Crossfire Cordon Avoidance",
                "Destroyer Div 2 (2x Hammerhead) in a 3-sided U-shaped enemy pocket detects high Cordon Index, holds HAUL_OUT_TO_VENT until both ships vent, and slides into blind spots.",
                playerGroup,
                enemyGroup);

        // Verify right-side toggle button is OFF by default on battle init
        boolean defaultOff = !TsfcWarroomRenderer.isOverlayVisible();
        TsfcWarroomRenderer.toggleOverlayVisible();
        boolean toggledOn = TsfcWarroomRenderer.isOverlayVisible();
        BattlefieldFirepowerEvaluator.resetForNewBattle();
        boolean resetBackToOff = !TsfcWarroomRenderer.isOverlayVisible();

        arena.addShip(anchorDiv, "b_anc", "RDS Onslaught", "Onslaught", HullSize.CAPITAL_SHIP, 0f, -1500f, 55f, 1400f, 0, 0);
        SimShipState hh1 = arena.addShip(hhDiv, "hh1", "RDS Solstice", "Hammerhead", HullSize.DESTROYER, 0f, 0f, 130f, 950f, 0, 0);
        SimShipState hh2 = arena.addShip(hhDiv, "hh2", "RDS Comet", "Hammerhead", HullSize.DESTROYER, 140f, -40f, 130f, 950f, 0, 0);

        // Asymmetric flux: hh1 is at 76% flux, hh2 is at 18% flux (avg = 47%, which previously exited HAUL_OUT_TO_VENT immediately!)
        hh1.fluxLevel = 0.76f;
        hh2.fluxLevel = 0.18f;

        // 3 enemy divisions forming a U-shaped crossfire cordon around (0, 0):
        //  - eFront at (0, 850) facing 270 deg (south, narrow 90-deg front guns + 360 PD)
        //  - ePort at (-850, 50) facing 0 deg (east, into the pocket)
        //  - eStbd at (850, 50) facing 180 deg (west, into the pocket)
        SimShipState ef = arena.addShip(eFront, "ef1", "Shinoria", "Eradicator", HullSize.CRUISER, 0f, 850f, 60f, 1300f, 0, 0);
        ef.facing = 270f;
        ef.weapons.clear();
        ef.addDirectionalWeapon(1350f, 90f, 0f, false, true);  // Heavy forward strike/general arc
        ef.addDirectionalWeapon(550f, 360f, 0f, true, false);  // All-around PD arc

        SimShipState ep = arena.addShip(ePort, "ep1", "Mulligan", "Falcon", HullSize.CRUISER, -850f, 50f, 75f, 1250f, 0, 0);
        ep.facing = 0f;
        ep.weapons.clear();
        ep.addDirectionalWeapon(1250f, 110f, 0f, false, false);

        SimShipState es = arena.addShip(eStbd, "es1", "Olkzan", "Enforcer", HullSize.CRUISER, 850f, 50f, 65f, 1250f, 0, 0);
        es.facing = 180f;
        es.weapons.clear();
        es.addDirectionalWeapon(1250f, 110f, 0f, false, false);

        BattlefieldFirepowerEvaluator.updateBattlefieldRoses(arena.engineProxy, 0.5f);
        ShipFirepowerRose efRose = BattlefieldFirepowerEvaluator.getRoseForShip(ef.proxy);

        // Check efRose directional firepower (bow at 270 deg world vs stern blind spot at 90 deg world)
        float bowShortDot = efRose != null ? efRose.sampleShortDotAtWorldAngle(270f) : 0f;
        float bowAlpha = efRose != null ? efRose.sampleAlphaAtWorldAngle(270f) : 0f;
        float sternShortDot = efRose != null ? efRose.sampleShortDotAtWorldAngle(90f) : 999f;
        float sternPd = efRose != null ? efRose.samplePdAtWorldAngle(90f) : 0f;

        // Evaluate U-cordon pocket center (0, 0) vs unidirectional point (0, 1550) in ef's stern blind spot
        PointFirepowerEval pocketEval = BattlefieldFirepowerEvaluator.evaluatePoint(
                arena.engineProxy, 0, new Vector2f(0f, 0f), 90f);
        PointFirepowerEval blindSpotEval = BattlefieldFirepowerEvaluator.evaluatePoint(
                arena.engineProxy, 0, new Vector2f(0f, 1550f), 90f);
        float blindSpotBonus = BattlefieldFirepowerEvaluator.evaluateBlindSpotAdvantage(
                arena.engineProxy, 0, eFront, new Vector2f(0f, 1550f));

        // Step simulation and verify Destroyer Div 2 enters HAUL_OUT_TO_VENT and holds it while hh1 is still > 45% flux
        arena.runSimulation(1.0f);
        boolean enteredHaulOutInPocket = (hhDiv.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT);

        // Even if hh2 is at 10% flux and hh1 is at 56% flux (avgFlux = 33%, below 0.55 * 0.72 = 39.6%),
        // Destroyer Div 2 must STAY in HAUL_OUT_TO_VENT because hh1 (maxMemberFluxLevel = 56% >= 45%) is not yet vented!
        hh1.fluxLevel = 0.56f;
        hh2.fluxLevel = 0.10f;
        arena.runSimulation(3.0f);
        boolean heldHaulOutWhileWingmanHighFlux = (hhDiv.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT);

        // Verify Hybrid Fire Classification (GENERAL, POINT_DEFENSE, HYBRID) & Onslaught vs Pegasus missile capability
        SimShipState flakCap = arena.addShip(eFront, "ef_flak", "HSS Onslaught", "onslaught", HullSize.CAPITAL_SHIP, 0f, 950f, 50f, 1400f, 0, 0);
        flakCap.weapons.clear();
        // 4 Medium Missiles (weight 12) + 3 Large Ballistics (weight 21) + 7 Medium Ballistics (weight 21) + 2 Large Devastator Flak (weight 14) = 17.6% missile share
        for (int i = 0; i < 4; i++) flakCap.addDirectionalWeapon(1500f, 45f, 0f, false, true);
        for (int i = 0; i < 10; i++) flakCap.addDirectionalWeapon(1100f, 180f, 0f, false, false);
        flakCap.weapons.add((com.fs.starfarer.api.combat.WeaponAPI) java.lang.reflect.Proxy.newProxyInstance(
                com.fs.starfarer.api.combat.WeaponAPI.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.combat.WeaponAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getId".equals(n)) return "devastator";
                    if ("getDisplayName".equals(n)) return "Devastator Cannon";
                    if ("getSize".equals(n)) return com.fs.starfarer.api.combat.WeaponAPI.WeaponSize.LARGE;
                    if ("getType".equals(n)) return com.fs.starfarer.api.combat.WeaponAPI.WeaponType.BALLISTIC;
                    if ("getRange".equals(n)) return 750f;
                    if ("getArc".equals(n)) return 240f;
                    if ("getArcFacing".equals(n)) return 0f;
                    if ("hasAIHint".equals(n)) return a != null && a.length > 0
                            && (a[0] == com.fs.starfarer.api.combat.WeaponAPI.AIHints.PD
                            || a[0] == com.fs.starfarer.api.combat.WeaponAPI.AIHints.PD_ALSO);
                    if ("isDecorative".equals(n) || "isDisabled".equals(n) || "isPermanentlyDisabled".equals(n) || "usesAmmo".equals(n)) return Boolean.FALSE;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                }));
        BattlefieldFirepowerEvaluator.FireClassification devClass = BattlefieldFirepowerEvaluator.classifyWeaponFire(
                flakCap.weapons.get(flakCap.weapons.size() - 1));
        divisionai.ShipCapabilityProfile onslaughtProf = new divisionai.ShipCapabilityProfile(flakCap.proxy);
        boolean hybridAndOnslaughtClassificationOk = (devClass == BattlefieldFirepowerEvaluator.FireClassification.HYBRID)
                && !onslaughtProf.hasHeavyMissileLoadout
                && (onslaughtProf.primaryTag != divisionai.ShipCapabilityProfile.TacticalTag.MISSILE_CAPITAL);

        check(defaultOff && toggledOn && resetBackToOff && hybridAndOnslaughtClassificationOk,
                "Battle screen right-side Firepower Rose toggle button defaults to OFF and Hybrid/Onslaught classification verified",
                String.format(Locale.ROOT, "defaultOff=%b, toggledOn=%b, resetBackToOff=%b, devClass=%s, onslaughtMissileCap=%b",
                        defaultOff, toggledOn, resetBackToOff, devClass, onslaughtProf.hasHeavyMissileLoadout));
        check(bowShortDot > 150f && bowAlpha > 250f && sternShortDot < bowShortDot * 0.25f && sternPd > 20f,
                "ShipFirepowerRose distinguishes 3-horizon General arcs from PD arcs and stern blind spots",
                String.format(Locale.ROOT, "bowShort=%.0f, bowAlpha=%.0f, sternShort=%.0f (blind spot), sternPd=%.0f",
                        bowShortDot, bowAlpha, sternShortDot, sternPd));
        check(pocketEval.cordonIndex >= 0.55f && pocketEval.activeThreatOctants >= 3 && blindSpotBonus > 50f
                        && pocketEval.effectiveDanger > blindSpotEval.effectiveDanger * 3.0f,
                "PointFirepowerEval detects 3-sided U-cordon crossfire pocket vs stern blind-spot advantage",
                String.format(Locale.ROOT, "pocketCordon=%.2f (octants=%d, danger=%.0f) vs blindSpotDanger=%.0f, blindSpotBonus=%.0f",
                        pocketEval.cordonIndex, pocketEval.activeThreatOctants, pocketEval.effectiveDanger,
                        blindSpotEval.effectiveDanger, blindSpotBonus));
        check(enteredHaulOutInPocket && heldHaulOutWhileWingmanHighFlux,
                "Hammerhead division in U-cordon enters HAUL_OUT_TO_VENT and waits until both ships vent below 45% flux",
                String.format(Locale.ROOT, "enteredHaulOut=%b, heldAtWingman56Pct=%b (intent=%s)",
                        enteredHaulOutInPocket, heldHaulOutWhileWingmanHighFlux, hhDiv.currentIntent));
    }

    /**
     * Scenario 6c: Fleet-Wide Helmsmanship (A), Mixed-Division Out-of-Combat Speed Pacing (B),
     * Covered Extraction & Escape Vector (C), and Absolute Player C2 Objective Capture Authority (D).
     */
    private static void testHelmsmanshipMixedSpeedPacingExtractionAndPlayerC2() {
        System.out.println("\n--- Scenario 6c: Helmsmanship (A), Mixed-Speed Pacing (B), Covered Extraction (C) & Player Objective C2 (D) ---");
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "steady");

        // --- Problem A: Cross-Division Friendly Collision Prevention ---
        BattleGroupState pGroupA = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroupA = new BattleGroupState(1, eCmdr);
        DivisionState ddDiv = new DivisionState("p_dd3", 0, 1, DivisionArchetype.DESTROYER_DIVISION, "Destroyer Div 3", pCmdr);
        DivisionState cruDiv = new DivisionState("p_cru1", 0, 2, DivisionArchetype.CRUISER_DIVISION, "Cruiser Div 1", pCmdr);
        pGroupA.divisions.add(ddDiv);
        pGroupA.divisions.add(cruDiv);
        SimArena arenaA = new SimArena("6c-A. Helmsmanship", "Cross-division friendly collision check", pGroupA, eGroupA);
        SimShipState rambunctious = arenaA.addShip(ddDiv, "dd_ram", "RDS Vanguard", "JYD_rambunctious", HullSize.DESTROYER, 0f, 0f, 110f, 700f, 0, 0);
        rambunctious.facing = 90f;
        rambunctious.velocity.set(0f, 95f);
        // Place a friendly cruiser from Cruiser Div 1 directly in front of RDS Vanguard at (0, 220)
        SimShipState friendlyCruiser = arenaA.addShip(cruDiv, "cru_block", "RDS Patient", "JYD_patient", HullSize.CRUISER, 0f, 220f, 55f, 1000f, 0, 0);
        friendlyCruiser.facing = 90f;
        friendlyCruiser.velocity.set(0f, 10f);
        boolean crossDivCorridorBlocked = !ShipSystemCoordinator.isForwardCorridorClearOfFleetFriendlies(
                arenaA.engineProxy, rambunctious.proxy, ddDiv.getAliveMembers(), pGroupA, 500f);
        com.fs.starfarer.api.combat.ShipAPI detectedObstacle = ShipSystemCoordinator.findImminentFriendlyInForwardPath(
                arenaA.engineProxy, rambunctious.proxy, ddDiv.getAliveMembers(), pGroupA, 450f);
        check(crossDivCorridorBlocked && detectedObstacle == friendlyCruiser.proxy,
                "Problem A: Fleet-wide helmsmanship detects cross-division friendly hull in forward velocity vector",
                "corridorBlocked=" + crossDivCorridorBlocked + ", obstacle=" + (detectedObstacle != null ? detectedObstacle.getId() : "none"));

        // --- Problem B: Mixed-Division Out-of-Combat Sustainable Speed Pacing ---
        BattleGroupState pGroupB = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroupB = new BattleGroupState(1, eCmdr);
        DivisionState battleDiv1 = new DivisionState("p_bat1", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Division 1", pCmdr);
        battleDiv1.doctrinalFormation = divisionai.PermanentDivisionManager.DivisionFormation.LINE_ABREAST;
        battleDiv1.activeFormation = divisionai.PermanentDivisionManager.DivisionFormation.LINE_ABREAST;
        battleDiv1.openingMission = DivisionMissionType.SCOUTING;
        battleDiv1.mainMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
        battleDiv1.stationSpacing = 340f;
        pGroupB.divisions.add(battleDiv1);
        DivisionState farEnemy = new DivisionState("e_far", 1, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Hegemony Line", eCmdr);
        eGroupB.divisions.add(farEnemy);
        SimArena arenaB = new SimArena("6c-B. Mixed-Division Pacing", "Battle Division 1 out-of-combat pacing", pGroupB, eGroupB);
        SimShipState chinax = arenaB.addShip(battleDiv1, "b_chinax", "HSS Chinax", "SD_chinax", HullSize.CAPITAL_SHIP, -360f, -2000f, 45f, 1300f, 0, 0);
        arenaB.addShip(battleDiv1, "b_dom1", "RDS Annihilator", "dominator", HullSize.CRUISER, -120f, -2000f, 60f, 1100f, 0, 0);
        SimShipState erad = arenaB.addShip(battleDiv1, "b_erad", "RDS Abhorrent Violence", "eradicator", HullSize.CRUISER, 120f, -2000f, 95f, 1100f, 0, 0);
        arenaB.addShip(battleDiv1, "b_dom2", "RDS The Culture", "dominator", HullSize.CRUISER, 360f, -2000f, 60f, 1100f, 0, 0);
        arenaB.addShip(farEnemy, "e_onslaught", "HSS Onslaught", "onslaught", HullSize.CAPITAL_SHIP, 0f, 2600f, 45f, 1350f, 0, 0);
        for (int s = 0; s < 14; s++) {
            arenaB.runSimulation(1.0f);
        }
        boolean outOfCombatDetected = DivisionFormationNavigator.isDivisionInOutOfCombatMarch(arenaB.engineProxy, battleDiv1);
        float sustainableSpd = DivisionFormationNavigator.computeDivisionSustainableSpeed(battleDiv1, battleDiv1.getAliveMembers());
        float alongLeadEradOverChinax = erad.location.y - chinax.location.y;
        // Now simulate combat opening up (enemy in range & firing)
        battleDiv1.inRangeFirepowerScore = 120f;
        battleDiv1.engagementLevel = 0.55f;
        boolean combatReleasesSpeedGovernor = !DivisionFormationNavigator.isDivisionInOutOfCombatMarch(arenaB.engineProxy, battleDiv1);
        check(outOfCombatDetected && Math.abs(sustainableSpd - 45f) < 1f && alongLeadEradOverChinax < 260f && combatReleasesSpeedGovernor,
                "Problem B: Mixed Battle Division 1 paces to slowest ship (45 su/s) out of combat and releases governor in combat",
                String.format(Locale.ROOT, "outOfCombat=%b, sustainableSpd=%.0f, eradLeadOverChinax=%.0fu, releasedInCombat=%b",
                        outOfCombatDetected, sustainableSpd, alongLeadEradOverChinax, combatReleasesSpeedGovernor));

        // --- Problem C: Single/Multi-Ship Covered Extraction & High-Speed Escape Vector ---
        BattleGroupState pGroupC = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroupC = new BattleGroupState(1, eCmdr);
        DivisionState ramDiv = new DivisionState("p_dd4", 0, 1, DivisionArchetype.DESTROYER_DIVISION, "Destroyer Div 4", pCmdr);
        ramDiv.ramCapableMemberCount = 2;
        pGroupC.divisions.add(ramDiv);
        DivisionState hegCruisers = new DivisionState("e_heg", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Hegemony Cruisers", eCmdr);
        eGroupC.divisions.add(hegCruisers);
        SimArena arenaC = new SimArena("6c-C. Covered Extraction", "Rambunctious division extraction & covering screen", pGroupC, eGroupC);
        SimShipState dd1 = arenaC.addShip(ramDiv, "dd_1", "RDS Pioneer", "JYD_rambunctious", HullSize.DESTROYER, -80f, 0f, 110f, 700f, 0, 0);
        SimShipState dd2 = arenaC.addShip(ramDiv, "dd_2", "RDS Trailblazer", "JYD_rambunctious", HullSize.DESTROYER, 80f, 0f, 110f, 700f, 0, 0);
        arenaC.addShip(hegCruisers, "e_heg1", "HSS Eagle", "eagle", HullSize.CRUISER, 0f, 520f, 55f, 1200f, 0, 0);
        arenaC.addShip(hegCruisers, "e_heg2", "HSS Dominator", "dominator", HullSize.CRUISER, 140f, 560f, 50f, 1200f, 0, 0);
        // Put both destroyers into high-flux / post-pass danger with no friendly battleship division nearby
        dd1.fluxLevel = 0.72f;
        dd1.hullLevel = 0.62f;
        dd2.fluxLevel = 0.68f;
        dd2.hullLevel = 0.64f;
        arenaC.runSimulation(0.25f);
        boolean enteredExtraction = (ramDiv.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT);
        boolean escapeVectorAwayFromEnemy = (ramDiv.stationPoint.y < -600f);
        boolean runQuicklySet = dd1.aiFlags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.RUN_QUICKLY);
        check(enteredExtraction && escapeVectorAwayFromEnemy && runQuicklySet,
                "Problem C: Endangered Destroyer division enters HAUL_OUT_TO_VENT along a 1,350u escape vector with RUN_QUICKLY",
                String.format(Locale.ROOT, "intent=%s, stationY=%.0f, runQuickly=%b",
                        ramDiv.currentIntent, ramDiv.stationPoint.y, runQuicklySet));

        // --- Problem D: Absolute Player C2 Authority on Mid-Battle Control Point Capture ---
        BattleGroupState pGroupD = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroupD = new BattleGroupState(1, eCmdr);
        // Only a Cruiser Division is alive when the player orders a contested Control Point captured mid-battle!
        DivisionState cruCapDiv = new DivisionState("p_cru_cap", 0, 1, DivisionArchetype.CRUISER_DIVISION, "Cruiser Div 1", pCmdr);
        pGroupD.divisions.add(cruCapDiv);
        DivisionState enemyDefenders = new DivisionState("e_def", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Hegemony Defenders", eCmdr);
        eGroupD.divisions.add(enemyDefenders);
        SimArena arenaD = new SimArena("6c-D. Player Objective C2", "Mid-battle contested Control Point capture", pGroupD, eGroupD);
        SimShipState c1 = arenaD.addShip(cruCapDiv, "c_lead", "RDS Patient 1", "JYD_patient", HullSize.CRUISER, 0f, -600f, 65f, 1000f, 0, 0);
        arenaD.addShip(cruCapDiv, "c_wing", "RDS Patient 2", "JYD_patient", HullSize.CRUISER, 160f, -640f, 65f, 1000f, 0, 0);
        arenaD.addShip(enemyDefenders, "e_d1", "HSS Enforcer 1", "enforcer", HullSize.DESTROYER, 0f, 250f, 60f, 900f, 0, 0);
        arenaD.addShip(enemyDefenders, "e_d2", "HSS Enforcer 2", "enforcer", HullSize.DESTROYER, 150f, 280f, 60f, 900f, 0, 0);
        final Vector2f objCenter = new Vector2f(0f, 0f);
        final com.fs.starfarer.api.combat.BattleObjectiveAPI contestedObj =
                (com.fs.starfarer.api.combat.BattleObjectiveAPI) Proxy.newProxyInstance(
                        com.fs.starfarer.api.combat.BattleObjectiveAPI.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.BattleObjectiveAPI.class},
                        (p, m, a) -> {
                            String n = m.getName();
                            if ("getLocation".equals(n)) return objCenter;
                            if ("getOwner".equals(n)) return 1;
                            if ("getDisplayName".equals(n)) return "Comm Relay Center";
                            if ("getType".equals(n)) return "comm_relay";
                            Class<?> rt = m.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == float.class) return 0f;
                            return null;
                        });
        arenaD.objectives.add(contestedObj);
        final com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo capAssignment =
                (com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo) Proxy.newProxyInstance(
                        com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo.class},
                        (p, m, a) -> {
                            String n = m.getName();
                            if ("getType".equals(n)) return com.fs.starfarer.api.combat.CombatAssignmentType.CAPTURE;
                            if ("getTarget".equals(n)) return contestedObj;
                            return null;
                        });
        final com.fs.starfarer.api.combat.CombatTaskManagerAPI tmProxy =
                (com.fs.starfarer.api.combat.CombatTaskManagerAPI) Proxy.newProxyInstance(
                        com.fs.starfarer.api.combat.CombatTaskManagerAPI.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.CombatTaskManagerAPI.class},
                        (p, m, a) -> {
                            if ("getAllAssignments".equals(m.getName())) {
                                return java.util.Collections.singletonList(capAssignment);
                            }
                            return null;
                        });
        final com.fs.starfarer.api.combat.CombatFleetManagerAPI fmProxy =
                (com.fs.starfarer.api.combat.CombatFleetManagerAPI) Proxy.newProxyInstance(
                        com.fs.starfarer.api.combat.CombatFleetManagerAPI.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.CombatFleetManagerAPI.class},
                        (p, m, a) -> {
                            if ("getTaskManager".equals(m.getName())) return tmProxy;
                            return null;
                        });
        com.fs.starfarer.api.combat.CombatEngineAPI engineWithTm =
                (com.fs.starfarer.api.combat.CombatEngineAPI) Proxy.newProxyInstance(
                        com.fs.starfarer.api.combat.CombatEngineAPI.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.CombatEngineAPI.class},
                        (p, m, a) -> {
                            if ("getFleetManager".equals(m.getName())) {
                                int side = (a != null && a.length > 0 && a[0] instanceof Integer) ? (Integer) a[0] : 0;
                                return (side == 0) ? fmProxy : null;
                            }
                            return m.invoke(arenaD.engineProxy, a);
                        });
        arenaD.runSimulation(0.25f);
        // Force heavy localEnemyStrength (> 24f) AND active commitment lock (intentCommitUntil=25s, lastIntentChangeTime=4.8s)
        // to verify player order immediately preempts commitment timers without waiting and is NEVER rejected due to enemy presence!
        cruCapDiv.localEnemyStrength = 32f;
        cruCapDiv.currentIntent = DivisionIntentType.CLOSE_AND_ENGAGE;
        cruCapDiv.lastIntentChangeTime = 4.8f;
        cruCapDiv.intentCommitUntil = 25.0f;
        DivisionCommanderAI.evaluateAndExecuteDivisions(engineWithTm, pGroupD, eGroupD, 5.0f);
        boolean adoptedPlayerOrder = (cruCapDiv.currentIntent == DivisionIntentType.TAKE_AND_HOLD
                && cruCapDiv.targetObjective == contestedObj
                && cruCapDiv.underDirectObjectiveOrder);
        Vector2f leadCamp = (Vector2f) c1.aiFlags.getCustom(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.CAMP_LOCATION);
        boolean leadPinnedToObjectiveCenter = (leadCamp != null && dist(leadCamp, contestedObj.getLocation()) < 5f)
                && c1.aiFlags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.DO_NOT_PURSUE)
                && c1.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.ACCELERATE)
                && !c1.aiFlags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.MANEUVER_TARGET);

        // Multi-Objective C2 Authority, OTC Preemption, Score-Stealing Immunity & Physical Capture-Ring Arrival:
        // Player orders 3 Control Points captured simultaneously while 4 combat divisions (Frigate Div 1, Destroyer Div 1,
        // Line Cruiser Div 2 at 55% flux, and Battle Div 1) are actively engaged under OTC POSITIVE_CONTROL.
        BattleGroupState pGroupMulti = new BattleGroupState(0, pCmdr);
        pGroupMulti.authority = OtcAuthority.POSITIVE_CONTROL;
        BattleGroupState eGroupMulti = new BattleGroupState(1, eCmdr);
        DivisionState frig1 = new DivisionState("p_f1", 0, 1, DivisionArchetype.FRIGATE_DIVISION, "Frigate Div 1", pCmdr);
        DivisionState ddCap1 = new DivisionState("p_dd1", 0, 2, DivisionArchetype.DESTROYER_DIVISION, "Destroyer Div 1", pCmdr);
        DivisionState cru2 = new DivisionState("p_cru2", 0, 3, DivisionArchetype.CRUISER_DIVISION, "Line Cruiser Div 2", pCmdr);
        DivisionState bat1 = new DivisionState("p_bat1", 0, 4, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div 1", pCmdr);
        pGroupMulti.divisions.add(frig1);
        pGroupMulti.divisions.add(ddCap1);
        pGroupMulti.divisions.add(cru2);
        pGroupMulti.divisions.add(bat1);
        DivisionState eMain = new DivisionState("e_main", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Enemy Main Line", eCmdr);
        eGroupMulti.divisions.add(eMain);

        SimArena arenaMulti = new SimArena("6c-D2. Multi-Objective C2", "3 simultaneous player CAPTURE orders", pGroupMulti, eGroupMulti);
        SimShipState f1s = arenaMulti.addShip(frig1, "f1_1", "HSS Lasher 1", "lasher", HullSize.FRIGATE, -2100f, -400f, 150f, 800f, 0, 0);
        arenaMulti.addShip(frig1, "f1_2", "HSS Lasher 2", "lasher", HullSize.FRIGATE, -1980f, -440f, 150f, 800f, 0, 0);
        SimShipState d1s = arenaMulti.addShip(ddCap1, "dd1_1", "HSS Hammerhead 1", "hammerhead", HullSize.DESTROYER, -100f, -500f, 110f, 950f, 0, 0);
        arenaMulti.addShip(ddCap1, "dd1_2", "HSS Hammerhead 2", "hammerhead", HullSize.DESTROYER, 60f, -540f, 110f, 950f, 0, 0);
        SimShipState c2s = arenaMulti.addShip(cru2, "cru2_1", "HSS Eagle 1", "eagle", HullSize.CRUISER, 2000f, -450f, 90f, 1100f, 0, 0);
        SimShipState c2w = arenaMulti.addShip(cru2, "cru2_2", "HSS Eagle 2", "eagle", HullSize.CRUISER, 2140f, -490f, 90f, 1100f, 0, 0);
        c2s.fluxLevel = 0.54f;
        c2w.fluxLevel = 0.54f;
        arenaMulti.addShip(bat1, "bat1_1", "HSS Onslaught", "onslaught", HullSize.CAPITAL_SHIP, 0f, -1200f, 55f, 1250f, 0, 0);
        arenaMulti.addShip(eMain, "em_1", "Enemy Cruiser 1", "eagle", HullSize.CRUISER, -900f, 200f, 70f, 1000f, 0, 0);
        arenaMulti.addShip(eMain, "em_2", "Enemy Cruiser 2", "eagle", HullSize.CRUISER, 900f, 200f, 70f, 1000f, 0, 0);

        final Vector2f locAlpha = new Vector2f(-2200f, 900f);
        final Vector2f locGamma = new Vector2f(0f, 1050f);
        final Vector2f locDelta = new Vector2f(2200f, 900f);
        com.fs.starfarer.api.combat.BattleObjectiveAPI objAlpha = createMockObjective("Nav Buoy Alpha", "nav_buoy", locAlpha, 1);
        com.fs.starfarer.api.combat.BattleObjectiveAPI objGamma = createMockObjective("Sensor Jammer Gamma", "sensor_array", locGamma, 1);
        com.fs.starfarer.api.combat.BattleObjectiveAPI objDelta = createMockObjective("Comm Relay Delta", "comm_relay", locDelta, 1);
        arenaMulti.objectives.add(objAlpha);
        arenaMulti.objectives.add(objGamma);
        arenaMulti.objectives.add(objDelta);

        // Step 1: Run 1.5s without player objective orders so all divisions commit to combat / OTC orders
        arenaMulti.runSimulation(1.5f);
        // Step 2: Player orders all 3 Control Points captured mid-battle!
        arenaMulti.playerAssignments.add(createMockAssignment(com.fs.starfarer.api.combat.CombatAssignmentType.CAPTURE, objAlpha));
        arenaMulti.playerAssignments.add(createMockAssignment(com.fs.starfarer.api.combat.CombatAssignmentType.CAPTURE, objGamma));
        arenaMulti.playerAssignments.add(createMockAssignment(com.fs.starfarer.api.combat.CombatAssignmentType.CAPTURE, objDelta));

        // Run a single 0.5s tick — all 3 non-battleship divisions must immediately preempt combat/OTC and claim the 3 objectives 1-to-1!
        arenaMulti.runSimulation(0.5f);
        boolean all3ImmediatelyClaimed = (frig1.currentIntent == DivisionIntentType.TAKE_AND_HOLD && frig1.underDirectObjectiveOrder && frig1.targetObjective == objAlpha)
                && (ddCap1.currentIntent == DivisionIntentType.TAKE_AND_HOLD && ddCap1.underDirectObjectiveOrder && ddCap1.targetObjective == objGamma)
                && (cru2.currentIntent == DivisionIntentType.TAKE_AND_HOLD && cru2.underDirectObjectiveOrder && cru2.targetObjective == objDelta)
                && (frig1.otcOrder.type == divisionai.DivisionModel.OtcOrderType.NONE)
                && (ddCap1.otcOrder.type == divisionai.DivisionModel.OtcOrderType.NONE)
                && (cru2.otcOrder.type == divisionai.DivisionModel.OtcOrderType.NONE);

        // Step 3: Run 20 seconds of simulation (with OTC re-evaluating every 1.5s) and verify all 3 divisions stay locked
        // on their ordered Control Points and physically drive their flagships inside the < 220u capture circles!
        boolean neverDroppedOrder = true;
        for (int step = 0; step < 40; step++) {
            arenaMulti.runSimulation(0.5f);
            divisionai.ShipSystemCoordinator.advanceEveryFrame(arenaMulti.engineProxy, pGroupMulti, null, eGroupMulti, arenaMulti.elapsedTime);
            if (!frig1.underDirectObjectiveOrder || frig1.currentIntent != DivisionIntentType.TAKE_AND_HOLD
                    || !ddCap1.underDirectObjectiveOrder || ddCap1.currentIntent != DivisionIntentType.TAKE_AND_HOLD
                    || !cru2.underDirectObjectiveOrder || cru2.currentIntent != DivisionIntentType.TAKE_AND_HOLD) {
                neverDroppedOrder = false;
            }
        }
        float dAlpha = dist(f1s.location, locAlpha);
        float dGamma = dist(d1s.location, locGamma);
        float dDelta = dist(c2s.location, locDelta);
        float dDeltaWing = dist(c2w.location, locDelta);
        boolean all3PhysicallyCaptured = dAlpha <= 220f && dGamma <= 220f && dDelta <= 220f && dDeltaWing <= 320f;

        check(adoptedPlayerOrder && leadPinnedToObjectiveCenter && all3ImmediatelyClaimed && neverDroppedOrder && all3PhysicallyCaptured,
                "Problem D: Contested mid-battle player CAPTURE order obeyed unconditionally (TAKE_AND_HOLD + lead ship pinned to point)",
                String.format(Locale.ROOT,
                        "intent=%s, underDirectOrder=%b, leadPinnedToCenter=%b, 3objClaimed=%b, neverDropped=%b, finalDists=[%.0f, %.0f, %.0f (wing=%.0f)]",
                        cruCapDiv.currentIntent, cruCapDiv.underDirectObjectiveOrder, leadPinnedToObjectiveCenter,
                        all3ImmediatelyClaimed, neverDroppedOrder, dAlpha, dGamma, dDelta, dDeltaWing));
    }

    /**
     * Scenario 6d: Babylon Space Station Bastion Siege (OtcAI + OtcBattlePlanner + DivisionCommanderAI + Module Focus).
     * Verifies that when all mobile enemy garrison divisions are destroyed and only a multi-module orbital station
     * ("Babylon") remains:
     *  1. OtcAI.getEffectiveEnemyDivisions & primaryThreatEnemyDivision select "Babylon" even when enemyGroup.divisions is empty.
     *  2. Carrier divisions target "Babylon" (not "the contact zone" at reserveStation) and focus armed station modules.
     *  3. Line Cruiser divisions with high custom standoffRange (1650u) clamp their station into gun range (< 1200u) of the station
     *     and pick an armed combat module rather than the unarmed central hub or structural struts.
     */
    private static void testBabylonStationBastionTargetingAndModuleFocusFire() throws Exception {
        System.out.println("\n--- Scenario 6d: Babylon Multi-Module Station Bastion Siege & Module Focus-Fire ---");
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "steady");
        BattleGroupState pGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroup = new BattleGroupState(1, eCmdr);
        pGroup.authority = OtcAuthority.POSITIVE_CONTROL;

        // Player divisions: Carrier Div 1 (standoff 2400) & Line Cruiser Div 1 (standoff 1650, gun range 1000)
        DivisionState carrierDiv = new DivisionState("p_cv1", 0, 1,
                DivisionArchetype.LIGHT_CARRIER_DIVISION, "Carrier Div 1", pCmdr);
        carrierDiv.standoffRange = 2400f;
        carrierDiv.openingMission = DivisionMissionType.DECKLOAD_STRIKE;
        carrierDiv.mainMission = DivisionMissionType.DECKLOAD_STRIKE;
        pGroup.divisions.add(carrierDiv);

        DivisionState cruiserDiv = new DivisionState("p_cru1", 0, 2,
                DivisionArchetype.CRUISER_DIVISION, "Line Cruiser Div 1", pCmdr);
        cruiserDiv.standoffRange = 1650f;
        cruiserDiv.openingMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
        cruiserDiv.mainMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
        pGroup.divisions.add(cruiserDiv);

        SimArena arena = new SimArena("6d. Babylon Station Siege",
                "Multi-module station bastion targeting & module focus-fire", pGroup, eGroup);
        arena.addShip(carrierDiv, "cv_1", "RDS Manikin", "heron",
                HullSize.CRUISER, -400f, -1800f, 90f, 900f, 0, 3);
        arena.addShip(carrierDiv, "cv_2", "RDS Star Witch", "heron",
                HullSize.CRUISER, 400f, -1800f, 90f, 900f, 0, 3);
        arena.addShip(cruiserDiv, "cr_1", "RDS Terror", "champion",
                HullSize.CRUISER, -220f, -1100f, 80f, 1000f, 2, 0);
        arena.addShip(cruiserDiv, "cr_2", "RDS Warden", "eagle",
                HullSize.CRUISER, 220f, -1100f, 80f, 1000f, 0, 0);

        // Enemy side: enemyGroup.divisions is EMPTY (all mobile garrison ships destroyed!), only "Babylon" station + modules exist in engine.getShips()
        SimShipState stationCore = new SimShipState(
                "st_babylon", "Babylon", "ii_station4", 1, HullSize.CAPITAL_SHIP,
                0f, 500f, 0f, 0f, 0, 0);
        stationCore.isStation = true;
        stationCore.weapons.clear();
        stationCore.weaponMounts.clear();

        SimShipState passiveArmor = new SimShipState(
                "st_mod_armor", "Station Armour Module", "ii_module_armor_straight", 1, HullSize.FRIGATE,
                0f, 260f, 0f, 0f, 0, 0);
        passiveArmor.isStationModule = true;
        passiveArmor.parentStation = stationCore.proxy;
        passiveArmor.weapons.clear();
        passiveArmor.weaponMounts.clear();

        SimShipState armedBastionMod = new SimShipState(
                "st_mod_superbastion", "Module, Magnum Support Bastion", "ii_module_superbastion", 1, HullSize.FRIGATE,
                0f, 280f, 0f, 1200f, 4, 2);
        armedBastionMod.isStationModule = true;
        armedBastionMod.parentStation = stationCore.proxy;

        stationCore.childModules.add(passiveArmor.proxy);
        stationCore.childModules.add(armedBastionMod.proxy);
        arena.ships.add(stationCore);
        arena.ships.add(passiveArmor);
        arena.ships.add(armedBastionMod);

        arena.runSimulation(2.0f);

        boolean primaryIsBabylon = pGroup.primaryThreatEnemyDivision != null
                && "Babylon".equals(pGroup.primaryThreatEnemyDivision.displayName);
        boolean carrierTargetsBabylon = carrierDiv.targetEnemyDivision != null
                && "Babylon".equals(carrierDiv.targetEnemyDivision.displayName);
        boolean cruiserTargetsBabylon = cruiserDiv.targetEnemyDivision != null
                && "Babylon".equals(cruiserDiv.targetEnemyDivision.displayName)
                && cruiserDiv.localEnemyStrength > 10f;
        com.fs.starfarer.api.combat.ShipAPI focusShip = DivisionCommanderAI.pickFocusEnemyShip(
                arena.engineProxy, 0, cruiserDiv.targetEnemyDivision, cruiserDiv.centroid, false);
        boolean focusIsArmedModule = (focusShip == armedBastionMod.proxy);
        float cruiserStandoffDist = dist(cruiserDiv.stationPoint, stationCore.location);

        check(primaryIsBabylon && carrierTargetsBabylon && cruiserTargetsBabylon && focusIsArmedModule && cruiserStandoffDist <= 1350f,
                "Scenario 6d: Station Bastion ('Babylon') targeted by OTC, Carriers & Cruisers when mobile garrison is dead, focusing armed module",
                String.format(Locale.ROOT,
                        "primary=%s, carrierTgt=%s, cruiserTgt=%s, localEnemyFp=%.1f, focusShip=%s, cruiserStandoff=%.0fu",
                        pGroup.primaryThreatEnemyDivision != null ? pGroup.primaryThreatEnemyDivision.displayName : "null",
                        carrierDiv.targetEnemyDivision != null ? carrierDiv.targetEnemyDivision.displayName : "null",
                        cruiserDiv.targetEnemyDivision != null ? cruiserDiv.targetEnemyDivision.displayName : "null",
                        cruiserDiv.localEnemyStrength,
                        focusShip != null ? focusShip.getName() : "null",
                        cruiserStandoffDist));

        // Verify Player Full Assault Authority (overrides high-flux trade-space/venting & formation leashes into PRESS_HOME with aggressive flags)
        for (com.fs.starfarer.api.combat.ShipAPI m : cruiserDiv.getAliveMembers()) {
            for (SimShipState s : arena.ships) {
                if (s.proxy == m) {
                    s.fluxLevel = 0.68f;
                }
            }
        }
        arena.fullAssaultSide0 = true;
        arena.runSimulation(0.6f);
        com.fs.starfarer.api.combat.ShipwideAIFlags cr1Flags = cruiserDiv.getAliveMembers().get(0).getAIFlags();
        boolean fullAssaultIntentOk = cruiserDiv.currentIntent == DivisionIntentType.PRESS_HOME
                && cruiserDiv.activeFormation == divisionai.PermanentDivisionManager.DivisionFormation.NONE
                && pGroup.currentPhase == divisionai.DivisionModel.BattlePhase.GENERAL_CHASE;
        boolean fullAssaultFlagsOk = cr1Flags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF)
                && cr1Flags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.HARASS_MOVE_IN)
                && cr1Flags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.PURSUING)
                && !cr1Flags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.BACK_OFF)
                && !cr1Flags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.DO_NOT_PURSUE);
        check(fullAssaultIntentOk && fullAssaultFlagsOk,
                "Full Assault Authority: immediately breaks formation leash, suppresses 68%-flux retreat, and drives ships in PRESS_HOME with DO_NOT_BACK_OFF + HARASS_MOVE_IN + PURSUING",
                String.format(Locale.ROOT,
                        "intent=%s, form=%s, phase=%s, flagsOk=%s",
                        cruiserDiv.currentIntent, cruiserDiv.activeFormation, pGroup.currentPhase, fullAssaultFlagsOk));
    }

    /**
     * Scenario 6e: Feigned Retreat Force-Disparity Checks, Friendly-Division Protection & Minimal Verbosity Signaling.
     * Rehearses:
     *  1. Latest-battle failure mode blocked: Line Cruiser Div 1 refuses to feign retreat when Destroyer Div 1 is
     *     hard-pressed/withdrawing ahead of it or when no hammer division provides >= 1.28x combined trap superiority.
     *  2. Overmatching bait blocked: Bait division with > 1.05x target strength does not feign retreat (enemy won't chase).
     *  3. Valid multi-division trap (bait <= 1.05x enemy, combined bait+hammer >= 1.28x enemy, no friendly left behind)
     *     executes Stage 1 Lure -> Stage 2 Spring Trap and signals the player on the HUD even at MINIMAL verbosity.
     */
    private static void testFeignedRetreatForceDisparityFriendlyProtectionAndMinimalSignals() {
        System.out.println("\n--- Scenario 6e: Feigned Retreat Force Disparity, Friendly Protection & Minimal Verbosity Signals ---");
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "aggressive");

        // --- Subcase 1: Friendly Destroyer Div 1 exposed/hard-pressed ahead -> Feigned Retreat MUST be blocked ---
        BattleGroupState pGroup1 = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroup1 = new BattleGroupState(1, eCmdr);
        pGroup1.authority = OtcAuthority.POSITIVE_CONTROL;
        pGroup1.organizationRatio = 1.0f;

        DivisionState cruiserBait1 = new DivisionState("p_cru1", 0, 1,
                DivisionArchetype.CRUISER_DIVISION, "Line Cruiser Div 1", pCmdr);
        DivisionState exposedDd1 = new DivisionState("p_dd1", 0, 2,
                DivisionArchetype.DESTROYER_DIVISION, "Destroyer Div 1", pCmdr);
        pGroup1.divisions.add(cruiserBait1);
        pGroup1.divisions.add(exposedDd1);

        DivisionState heavyEnemy1 = new DivisionState("e_hcru2", 1, 1,
                DivisionArchetype.CRUISER_DIVISION, "Heavy Cruiser Div 2", eCmdr);
        eGroup1.divisions.add(heavyEnemy1);

        SimArena arena1 = new SimArena("6e-1. Feigned Retreat Blocked (Friendly Left Behind)",
                "Line Cruiser Div 1 refuses to feign retreat while Destroyer Div 1 is hard-pressed ahead",
                pGroup1, eGroup1);
        arena1.addShip(cruiserBait1, "c1", "RDS Terror", "champion", HullSize.CRUISER, -120f, -1100f, 65f, 1000f, 2, 0);
        arena1.addShip(cruiserBait1, "c2", "RDS Warden", "eagle", HullSize.CRUISER, 120f, -1100f, 65f, 1000f, 0, 0);
        SimShipState dd1 = arena1.addShip(exposedDd1, "d1", "RDS Comet", "hammerhead", HullSize.DESTROYER, -80f, -820f, 90f, 900f, 0, 0);
        SimShipState dd2 = arena1.addShip(exposedDd1, "d2", "RDS Vector", "sunder", HullSize.DESTROYER, 80f, -820f, 90f, 900f, 0, 0);
        dd1.fluxLevel = 0.64f;
        dd2.fluxLevel = 0.58f;

        SimShipState e1 = arena1.addShip(heavyEnemy1, "e1", "HSS Dominator", "dominator", HullSize.CRUISER, -140f, 100f, 55f, 1100f, 2, 0);
        SimShipState e2 = arena1.addShip(heavyEnemy1, "e2", "HSS Eagle", "eagle", HullSize.CRUISER, 0f, 100f, 65f, 1100f, 0, 0);
        SimShipState e3 = arena1.addShip(heavyEnemy1, "e3", "HSS Mora", "mora", HullSize.CRUISER, 140f, 100f, 50f, 1100f, 0, 3);
        e1.velocity.set(0f, -35f);
        e2.velocity.set(0f, -35f);
        e3.velocity.set(0f, -35f);

        arena1.runSimulation(1.0f);
        boolean friendlyExposedDetected = divisionai.OtcBattlePlanner.hasFriendlyDivisionLeftBehindOrExposed(
                arena1.engineProxy, pGroup1, cruiserBait1, heavyEnemy1);
        DivisionState invalidHammerWhenExposed = divisionai.OtcBattlePlanner.findValidFeignedRetreatHammer(
                arena1.engineProxy, pGroup1, cruiserBait1, heavyEnemy1);
        boolean feignBlockedWhenFriendlyExposed = friendlyExposedDetected
                && invalidHammerWhenExposed == null
                && !pGroup1.fleetFeignedRetreatActive
                && cruiserBait1.currentIntent != DivisionIntentType.FEIGNED_RETREAT;

        // --- Subcase 2: Bait division alone already outmatches target (> 1.05x) -> Feigned Retreat blocked ---
        DivisionState weakEnemy = new DivisionState("e_weak", 1, 2,
                DivisionArchetype.FRIGATE_DIVISION, "Frigate Screen", eCmdr);
        weakEnemy.totalFpStrength = 14f;
        weakEnemy.centroid.set(0f, 100f);
        cruiserBait1.totalFpStrength = 36f;
        DivisionState overmatchHammer = divisionai.OtcBattlePlanner.findValidFeignedRetreatHammer(
                arena1.engineProxy, pGroup1, cruiserBait1, weakEnemy);
        boolean overmatchingBaitBlocked = (overmatchHammer == null);

        // --- Subcase 3: Valid Force-Disparity Trap (Bait <= 1.05x Enemy, Bait+Hammer >= 1.28x Enemy, No Friendly Exposed)
        //     + MINIMAL Verbosity HUD Signaling ---
        BattleGroupState pGroup3 = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroup3 = new BattleGroupState(1, eCmdr);
        pGroup3.authority = OtcAuthority.POSITIVE_CONTROL;
        pGroup3.organizationRatio = 1.0f;

        DivisionState baitCruisers = new DivisionState("p_bait", 0, 1,
                DivisionArchetype.CRUISER_DIVISION, "Line Cruiser Div 1", pCmdr);
        DivisionState hammerCruisers = new DivisionState("p_hammer", 0, 2,
                DivisionArchetype.STRIKE_CRUISER_DIVISION, "Strike Cruiser Div 2", pCmdr);
        hammerCruisers.isFastFlanker = true;
        pGroup3.divisions.add(baitCruisers);
        pGroup3.divisions.add(hammerCruisers);

        DivisionState pursuingEnemy = new DivisionState("e_pursue", 1, 1,
                DivisionArchetype.CRUISER_DIVISION, "Hegemony Vanguard", eCmdr);
        eGroup3.divisions.add(pursuingEnemy);

        SimArena arena3 = new SimArena("6e-3. Valid Feigned Retreat Trap & Minimal Signals",
                "Bait lures pursuing enemy into Hammer division's alpha strike with Minimal HUD signals",
                pGroup3, eGroup3);
        arena3.addShip(baitCruisers, "bc1", "RDS Sentinel", "eagle", HullSize.CRUISER, -110f, -1100f, 70f, 1050f, 0, 0);
        arena3.addShip(baitCruisers, "bc2", "RDS Bastion", "falcon", HullSize.CRUISER, 110f, -1100f, 75f, 1050f, 0, 0);
        // Hammer division positioned behind/flank of bait at (-950, -1350) so it is NOT exposed ahead of bait
        arena3.addShip(hammerCruisers, "hc1", "RDS Rapier", "fury", HullSize.CRUISER, -950f, -1350f, 85f, 1000f, 0, 0);
        arena3.addShip(hammerCruisers, "hc2", "RDS Sabre", "aurora", HullSize.CRUISER, -780f, -1350f, 85f, 1000f, 0, 0);

        SimShipState pe1 = arena3.addShip(pursuingEnemy, "pe1", "HSS Champion", "champion", HullSize.CRUISER, -100f, 120f, 65f, 1050f, 0, 0);
        SimShipState pe2 = arena3.addShip(pursuingEnemy, "pe2", "HSS Eagle", "eagle", HullSize.CRUISER, 100f, 120f, 65f, 1050f, 0, 0);
        SimShipState pe3 = arena3.addShip(pursuingEnemy, "pe3", "HSS Enforcer", "enforcer", HullSize.DESTROYER, 0f, 150f, 65f, 950f, 0, 0);
        pe1.facing = 270f;
        pe2.facing = 270f;
        pe3.facing = 270f;
        pe1.velocity.set(0f, -45f);
        pe2.velocity.set(0f, -45f);
        pe3.velocity.set(0f, -45f);

        final List<String> minimalHudMessages = new ArrayList<>();
        final com.fs.starfarer.api.combat.CombatUIAPI mockUi =
                (com.fs.starfarer.api.combat.CombatUIAPI) Proxy.newProxyInstance(
                        com.fs.starfarer.api.combat.CombatUIAPI.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.CombatUIAPI.class},
                        (p, m, a) -> {
                            if ("addMessage".equals(m.getName()) && a != null) {
                                StringBuilder sb = new StringBuilder();
                                for (Object arg : a) {
                                    if (arg instanceof String) sb.append((String) arg);
                                    else if (arg instanceof Object[]) {
                                        for (Object sub : (Object[]) arg) {
                                            if (sub instanceof String) sb.append((String) sub);
                                        }
                                    }
                                }
                                minimalHudMessages.add(sb.toString());
                            }
                            Class<?> rt = m.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == float.class) return 0f;
                            return null;
                        });
        com.fs.starfarer.api.combat.CombatEngineAPI engineWithUi =
                (com.fs.starfarer.api.combat.CombatEngineAPI) Proxy.newProxyInstance(
                        com.fs.starfarer.api.combat.CombatEngineAPI.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.CombatEngineAPI.class},
                        (p, m, a) -> {
                            if ("getCombatUI".equals(m.getName())) return mockUi;
                            return m.invoke(arena3.engineProxy, a);
                        });

        DivisionState resolvedHammer = null;
        boolean validTrapInitiated = false;
        boolean minimalSignaledFeignAndLargeTactics = false;
        try {
            divisionai.DivisionAISettings.testVerbosityOverride = divisionai.DivisionAISettings.ChatterVerbosity.MINIMAL;
            arena3.runSimulation(0.5f);
            pGroup3.organizationRatio = 1.0f;
            pGroup3.forceRatio = 1.6f;
            pursuingEnemy.closingSpeedToEnemy = 40f;
            baitCruisers.avgFluxLevel = 0.18f;
            baitCruisers.weaponReadinessRatio = 0.85f;
            hammerCruisers.avgFluxLevel = 0.14f;
            pGroup3.primaryThreatEnemyDivision = pursuingEnemy;
            pGroup3.mainEffortDivision = baitCruisers;
            pGroup3.strikePlan = null;
            pGroup3.fleetFeignedRetreatActive = false;
            pGroup3.fleetFeignedRetreatStage = 0;
            pGroup3.lastFleetFeignedRetreatEndTime = -30f;
            baitCruisers.otcOrder.clear();
            hammerCruisers.otcOrder.clear();
            baitCruisers.lastIntentChangeTime = -10f;
            baitCruisers.intentCommitUntil = 0f;

            resolvedHammer = divisionai.OtcBattlePlanner.findValidFeignedRetreatHammer(
                    engineWithUi, pGroup3, baitCruisers, pursuingEnemy);
            divisionai.OtcBattlePlanner.planBattle(engineWithUi, pGroup3, eGroup3, null, null, 30.0f);
            DivisionCommanderAI.evaluateAndExecuteDivisions(engineWithUi, pGroup3, eGroup3, 30.0f);

            validTrapInitiated = (resolvedHammer == hammerCruisers)
                    && pGroup3.fleetFeignedRetreatActive
                    && pGroup3.fleetFeignedRetreatStage == 1
                    && baitCruisers.currentIntent == DivisionIntentType.FEIGNED_RETREAT;

            // Advance 5.0s so the pursuing enemy closes into the kill zone and triggers Stage 2 (Spring Trap)
            pursuingEnemy.closingSpeedToEnemy = 40f;
            divisionai.OtcBattlePlanner.planBattle(engineWithUi, pGroup3, eGroup3, null, null, 35.0f);
            boolean trapSprungStage2 = pGroup3.fleetFeignedRetreatActive && pGroup3.fleetFeignedRetreatStage == 2;
            validTrapInitiated = validTrapInitiated && trapSprungStage2;

            // Also emit a Setpiece and Trade Space order at MINIMAL verbosity to verify large-scale tactic signals
            divisionai.DivisionCombatLog.logOtcOrder(engineWithUi, pGroup3,
                    "Setpiece [Hammer and Anvil]: 2 divisions executing against \"Hegemony Vanguard\" (strike in 4.0s).");
            divisionai.DivisionCombatLog.logOtcOrder(engineWithUi, pGroup3,
                    "Tactical Retreat [Trade Space for Time]: 1 divisions offsetting centroid by (0, -600) against \"Hegemony Vanguard\".");

            boolean sawFeignLureOnMinimalHud = false;
            boolean sawFeignSpringOnMinimalHud = false;
            boolean sawSetpieceOnMinimalHud = false;
            boolean sawTradeSpaceOnMinimalHud = false;
            for (String msg : minimalHudMessages) {
                if (msg.contains("Feigned Retreat (Lure)") || msg.contains("Lure <-")) sawFeignLureOnMinimalHud = true;
                if (msg.contains("Feigned Retreat: Spring Trap")) sawFeignSpringOnMinimalHud = true;
                if (msg.contains("Setpiece [Hammer and Anvil]")) sawSetpieceOnMinimalHud = true;
                if (msg.contains("Trade Space")) sawTradeSpaceOnMinimalHud = true;
            }
            minimalSignaledFeignAndLargeTactics = sawFeignLureOnMinimalHud
                    && sawFeignSpringOnMinimalHud
                    && sawSetpieceOnMinimalHud
                    && sawTradeSpaceOnMinimalHud;
        } finally {
            divisionai.DivisionAISettings.testVerbosityOverride = null;
        }
        recordedScenarios.add(arena1.recording);
        recordedScenarios.add(arena3.recording);

        check(feignBlockedWhenFriendlyExposed && overmatchingBaitBlocked && validTrapInitiated && minimalSignaledFeignAndLargeTactics,
                "Scenario 6e: Feigned Retreat enforces force disparity & no-friendly-left-behind and signals large-scale tactics at MINIMAL verbosity",
                String.format(Locale.ROOT,
                        "blockedWhenFriendlyExposed=%b, overmatchBlocked=%b, validTrapInitiated=%b (resHammer=%s, exp=%b, baitFp=%.1f, hamFp=%.1f, enemFp=%.1f, locEnem=%.1f, fleetFeign=%b, stage=%d, intent=%s, dist=%.0f), minimalSignals=%b (hud=%s)",
                        feignBlockedWhenFriendlyExposed, overmatchingBaitBlocked, validTrapInitiated,
                        resolvedHammer != null ? resolvedHammer.displayName : "null",
                        divisionai.OtcBattlePlanner.hasFriendlyDivisionLeftBehindOrExposed(engineWithUi, pGroup3, baitCruisers, pursuingEnemy),
                        baitCruisers.totalFpStrength, hammerCruisers.totalFpStrength, pursuingEnemy.totalFpStrength, baitCruisers.localEnemyStrength,
                        pGroup3.fleetFeignedRetreatActive, pGroup3.fleetFeignedRetreatStage, baitCruisers.currentIntent,
                        dist(baitCruisers.centroid, pursuingEnemy.centroid),
                        minimalSignaledFeignAndLargeTactics, minimalHudMessages));

        // ------------------------------------------------------------------
        // Sub-test 6f: Per-Division Ramming Options (Off by Default, Auto-Enabled on Ram Ships, 1..10 Attack Slots)
        // ------------------------------------------------------------------
        CommanderProfile pRamCmdr = new CommanderProfile(null, "aggressive");
        CommanderProfile eRamCmdr = new CommanderProfile(null, "steady");
        BattleGroupState pGroupRam = new BattleGroupState(0, pRamCmdr);
        BattleGroupState eGroupRam = new BattleGroupState(1, eRamCmdr);

        DivisionState stdDiv = new DivisionState(
                "p_std_dd_div", 0, 1, DivisionArchetype.DESTROYER_DIVISION, "Standard Destroyer Div", pRamCmdr);
        DivisionState ramDiv = new DivisionState(
                "p_ram_dd_div", 0, 2, DivisionArchetype.DESTROYER_DIVISION, "Ram Destroyer Div", pRamCmdr);
        DivisionState enemTgtDiv = new DivisionState(
                "e_tgt_div", 1, 1, DivisionArchetype.DESTROYER_DIVISION, "Enemy Escort Div", eRamCmdr);
        pGroupRam.divisions.add(stdDiv);
        pGroupRam.divisions.add(ramDiv);
        eGroupRam.divisions.add(enemTgtDiv);

        SimArena arenaRam = new SimArena("6f. Per-Division Ramming Options", "Ramming modes & 1..10 attack slots", pGroupRam, eGroupRam);
        arenaRam.addShip(stdDiv, "std_dd", "HSS Hammerhead", "hammerhead", HullSize.DESTROYER, -800f, -600f, 95f, 700f, 0, 0);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(stdDiv);
        boolean defaultOffWithoutRamShips = (stdDiv.rammingMode == divisionai.PermanentDivisionManager.DivisionRammingMode.OFF)
                && (stdDiv.ramCapableMemberCount == 0);

        SimShipState ramDd1 = arenaRam.addShip(ramDiv, "jyd_rambunctious_1", "JYD Rambunctious 1", "JYD_rambunctious", HullSize.DESTROYER, -160f, -380f, 110f, 650f, 0, 0);
        SimShipState ramDd2 = arenaRam.addShip(ramDiv, "jyd_rambunctious_2", "JYD Rambunctious 2", "JYD_rambunctious", HullSize.DESTROYER, 0f, -380f, 110f, 650f, 0, 0);
        SimShipState ramDd3 = arenaRam.addShip(ramDiv, "jyd_rambunctious_3", "JYD Rambunctious 3", "JYD_rambunctious", HullSize.DESTROYER, 160f, -380f, 110f, 650f, 0, 0);
        ramDd1.configureShipSystem("JYD_ramdrive", "Ram Drive", true);
        ramDd2.configureShipSystem("JYD_ramdrive", "Ram Drive", true);
        ramDd3.configureShipSystem("JYD_ramdrive", "Ram Drive", true);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(ramDiv);
        boolean autoEnabledWithRamShips = (ramDiv.rammingMode == divisionai.PermanentDivisionManager.DivisionRammingMode.GLANCING_SKIRMISH)
                && (ramDiv.ramCapableMemberCount == 3);

        SimShipState enemTgt1 = arenaRam.addShip(enemTgtDiv, "e_tgt_1", "HSS Enforcer 1", "enforcer", HullSize.DESTROYER, -160f, 180f, 65f, 700f, 0, 0);
        SimShipState enemTgt2 = arenaRam.addShip(enemTgtDiv, "e_tgt_2", "HSS Enforcer 2", "enforcer", HullSize.DESTROYER, 0f, 180f, 65f, 700f, 0, 0);
        SimShipState enemTgt3 = arenaRam.addShip(enemTgtDiv, "e_tgt_3", "HSS Enforcer 3", "enforcer", HullSize.DESTROYER, 160f, 180f, 65f, 700f, 0, 0);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(enemTgtDiv);

        ramDiv.currentIntent = DivisionIntentType.CLOSE_AND_ENGAGE;
        ramDiv.targetEnemyDivision = enemTgtDiv;
        ramDiv.inRangeFirepowerScore = 80f;
        ramDiv.engagementLevel = 0.5f;
        ramDiv.localForceRatio = 1.2f;
        ramDiv.crossfireCordonIndex = 0.0f;

        // 1. Verify Ramming: OFF blocks offensive ramming completely
        ramDiv.rammingMode = divisionai.PermanentDivisionManager.DivisionRammingMode.OFF;
        divisionai.ShipSystemCoordinator.advanceEveryFrame(arenaRam.engineProxy, pGroupRam, null, eGroupRam, 20.0f);
        boolean offModeBlockedAll = !ramDd1.systemActive && !ramDd2.systemActive && !ramDd3.systemActive;

        // 2. Verify SINGLE_SHIP focuses attack slots onto ONE single target ship (slots=2 -> 2 rammers on same target, 3rd held)
        ramDiv.rammingMode = divisionai.PermanentDivisionManager.DivisionRammingMode.DIRECT_RAM;
        ramDiv.rammingTargetScope = divisionai.PermanentDivisionManager.DivisionRammingTargetScope.SINGLE_SHIP;
        ramDiv.maxRammingAttackSlots = 2;
        divisionai.ShipSystemCoordinator.advanceEveryFrame(arenaRam.engineProxy, pGroupRam, null, eGroupRam, 21.0f);
        int activeSingle2 = (ramDd1.systemActive ? 1 : 0) + (ramDd2.systemActive ? 1 : 0) + (ramDd3.systemActive ? 1 : 0);
        boolean singleShipFocusedSameTarget = (activeSingle2 == 2)
                && (ramDd1.shipTarget != null && ramDd1.shipTarget == ramDd2.shipTarget);

        // 3. Reset drives and verify ENEMY_DIVISION selects MULTIPLE distinct targets in the localized space (1..10 slots)
        ramDd1.systemActive = false; ramDd1.systemCooldownRemaining = 0f;
        ramDd2.systemActive = false; ramDd2.systemCooldownRemaining = 0f;
        ramDd3.systemActive = false; ramDd3.systemCooldownRemaining = 0f;
        ramDiv.rammingTargetScope = divisionai.PermanentDivisionManager.DivisionRammingTargetScope.ENEMY_DIVISION;
        ramDiv.maxRammingAttackSlots = 1;
        divisionai.ShipSystemCoordinator.advanceEveryFrame(arenaRam.engineProxy, pGroupRam, null, eGroupRam, 22.0f);
        int activeDivSlot1 = (ramDd1.systemActive ? 1 : 0) + (ramDd2.systemActive ? 1 : 0) + (ramDd3.systemActive ? 1 : 0);

        // 4. Increase ENEMY_DIVISION maxRammingAttackSlots to 3 -> all 3 rammers attack 3 DISTINCT targets in the localized space!
        ramDd1.systemActive = false; ramDd1.systemCooldownRemaining = 0f;
        ramDd2.systemActive = false; ramDd2.systemCooldownRemaining = 0f;
        ramDd3.systemActive = false; ramDd3.systemCooldownRemaining = 0f;
        ramDiv.maxRammingAttackSlots = 3;
        divisionai.ShipSystemCoordinator.advanceEveryFrame(arenaRam.engineProxy, pGroupRam, null, eGroupRam, 22.5f);
        int activeDivSlot3 = (ramDd1.systemActive ? 1 : 0) + (ramDd2.systemActive ? 1 : 0) + (ramDd3.systemActive ? 1 : 0);
        java.util.Set<com.fs.starfarer.api.combat.ShipAPI> distinctDivTargets = new java.util.HashSet<>();
        if (ramDd1.shipTarget != null) distinctDivTargets.add(ramDd1.shipTarget);
        if (ramDd2.shipTarget != null) distinctDivTargets.add(ramDd2.shipTarget);
        if (ramDd3.shipTarget != null) distinctDivTargets.add(ramDd3.shipTarget);
        boolean multiTargetInLocalizedSpace = (activeDivSlot3 == 3) && (distinctDivTargets.size() == 3);

        // 5. Full Closed-Loop Rehearsal of SYNCHRONIZED_LANCE (Lance Charge) against an Enemy Division:
        //    Start 3 Rambunctious destroyers at y=-820 (slightly misaligned headings so Stage 1 must dress & align them first),
        //    verify zero piecemeal ignition during Stage 1, simultaneous 3-ship ignition in Stage 2, and physical hull impacts
        //    on all 3 enemy ships!
        ramDd1.systemActive = false; ramDd1.systemCooldownRemaining = 0f;
        ramDd2.systemActive = false; ramDd2.systemCooldownRemaining = 0f;
        ramDd3.systemActive = false; ramDd3.systemCooldownRemaining = 0f;
        ramDd1.location.set(-180f, -820f); ramDd1.facing = 55f;  ramDd1.velocity.set(0f, 60f);
        ramDd2.location.set(0f, -840f);    ramDd2.facing = 125f; ramDd2.velocity.set(0f, 60f);
        ramDd3.location.set(180f, -820f);  ramDd3.facing = 50f;  ramDd3.velocity.set(0f, 60f);
        enemTgt1.location.set(-180f, 180f); enemTgt1.velocity.set(0f, 0f);
        enemTgt2.location.set(0f, 180f);    enemTgt2.velocity.set(0f, 0f);
        enemTgt3.location.set(180f, 180f);  enemTgt3.velocity.set(0f, 0f);

        ramDiv.rammingMode = divisionai.PermanentDivisionManager.DivisionRammingMode.SYNCHRONIZED_LANCE;
        ramDiv.rammingTargetScope = divisionai.PermanentDivisionManager.DivisionRammingTargetScope.ENEMY_DIVISION;
        ramDiv.maxRammingAttackSlots = 3;
        ramDiv.shipSkillCoordination = divisionai.PermanentDivisionManager.ShipSkillCoordinationMode.SYNCHRONIZED;
        ramDiv.synchronizedSkillManeuverActive = false;
        ramDiv.synchronizedSkillStage = 0;
        ramDiv.lastSynchronizedSkillEndTime = -20f;

        boolean sawStage1HoldNoPiecemeal = false;
        boolean sawSimultaneous3ShipLance = false;
        boolean hitTgt1 = false, hitTgt2 = false, hitTgt3 = false;
        float minD1 = 9999f, minD2 = 9999f, minD3 = 9999f;
        for (int step = 0; step < 28; step++) {
            float t = 30.0f + step * 0.25f;
            divisionai.OtcAI.updateTelemetry(arenaRam.engineProxy, pGroupRam);
            divisionai.OtcAI.updateTelemetry(arenaRam.engineProxy, eGroupRam);
            ramDiv.currentIntent = DivisionIntentType.PRESS_HOME;
            ramDiv.targetEnemyDivision = enemTgtDiv;
            ramDiv.localForceRatio = 1.25f;
            ramDiv.crossfireCordonIndex = 0.10f;
            DivisionCommanderAI.evaluateAndExecuteDivisions(arenaRam.engineProxy, pGroupRam, eGroupRam, t);
            divisionai.ShipSystemCoordinator.advanceEveryFrame(arenaRam.engineProxy, pGroupRam, null, eGroupRam, t);

            int actNow = (ramDd1.systemActive ? 1 : 0) + (ramDd2.systemActive ? 1 : 0) + (ramDd3.systemActive ? 1 : 0);
            if (ramDiv.synchronizedSkillStage == 1 && actNow == 0) {
                sawStage1HoldNoPiecemeal = true;
            }
            if (actNow == 3) {
                sawSimultaneous3ShipLance = true;
            }
            // Step kinematics toward each ship's movement destination / target
            SimShipState[] rammers = new SimShipState[]{ramDd1, ramDd2, ramDd3};
            for (SimShipState rs : rammers) {
                Vector2f md = (Vector2f) rs.aiFlags.getCustom(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.MOVEMENT_DEST);
                if (md != null) {
                    float dx = md.x - rs.location.x;
                    float dy = md.y - rs.location.y;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    float desAng = (float) Math.toDegrees(Math.atan2(dy, dx));
                    float diff = desAng - rs.facing;
                    while (diff > 180f) diff -= 360f;
                    while (diff < -180f) diff += 360f;
                    float maxTurn = 45f * 0.25f;
                    rs.facing += Math.max(-maxTurn, Math.min(maxTurn, diff));
                    float spd = rs.systemActive ? (rs.maxSpeed + 260f) : rs.maxSpeed;
                    if (d > 5f) {
                        double rad = Math.toRadians(rs.facing);
                        rs.velocity.set((float) Math.cos(rad) * spd, (float) Math.sin(rad) * spd);
                        rs.location.x += rs.velocity.x * 0.25f;
                        rs.location.y += rs.velocity.y * 0.25f;
                    }
                }
                minD1 = Math.min(minD1, dist(rs.location, enemTgt1.location));
                minD2 = Math.min(minD2, dist(rs.location, enemTgt2.location));
                minD3 = Math.min(minD3, dist(rs.location, enemTgt3.location));
                if (dist(rs.location, enemTgt1.location) <= 145f) hitTgt1 = true;
                if (dist(rs.location, enemTgt2.location) <= 145f) hitTgt2 = true;
                if (dist(rs.location, enemTgt3.location) <= 145f) hitTgt3 = true;
            }
        }
        boolean lanceRehearsalPassed = sawStage1HoldNoPiecemeal && sawSimultaneous3ShipLance && hitTgt1 && hitTgt2 && hitTgt3;

        check(defaultOffWithoutRamShips && autoEnabledWithRamShips && offModeBlockedAll
                        && singleShipFocusedSameTarget && activeDivSlot1 == 1 && multiTargetInLocalizedSpace
                        && lanceRehearsalPassed,
                "Scenario 6f: Per-Division Ramming Options default to OFF, auto-enable when ram ships detected, select multiple targets in localized space for Enemy Division, and execute cohesive Lance Charge impacts",
                String.format(Locale.ROOT,
                        "defaultOff=%b, autoEnabled=%b, offBlocked=%b, singleShipSameTgt=%b (act=%d), enemyDiv1Slot=%d, enemyDiv3MultiTgt=%b (distinct=%d), lanceRehearsal=%b (stage1Hold=%b, sync3=%b, hits=%b/%b/%b, minD=%.0f/%.0f/%.0f)",
                        defaultOffWithoutRamShips, autoEnabledWithRamShips, offModeBlockedAll,
                        singleShipFocusedSameTarget, activeSingle2, activeDivSlot1, multiTargetInLocalizedSpace, distinctDivTargets.size(),
                        lanceRehearsalPassed, sawStage1HoldNoPiecemeal, sawSimultaneous3ShipLance, hitTgt1, hitTgt2, hitTgt3,
                        minD1, minD2, minD3));
    }

    /**
     * Scenario 10: Advanced Strike Craft Gunnery (L-Crossfire & Pincer Attack Geometries,
     * 3-Tier Hierarchical Firing Sectors: Voidstrike -> Squadron -> Craft, and Low vs Strong
     * Angle-of-Attack Correction with Off-Boresight Fire-Gating).
     */
    private static void testAdvancedStrikeCraftGunneryAndHierarchicalSectors() {
        System.out.println("\n--- Scenario 10: Advanced Strike Craft Gunnery (L-Crossfire, Hierarchical Sectors & AoA Correction) ---");

        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "steady");
        BattleGroupState pGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroup = new BattleGroupState(1, eCmdr);
        DivisionState carrierDiv = new DivisionState(
                "p_cv_div", 0, 1, DivisionArchetype.FLEET_CARRIER_DIVISION, "Fleet Carrier Div 1", pCmdr);
        DivisionState enemyDiv = new DivisionState(
                "e_bb_div", 1, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Enemy Battle Div 1", eCmdr);
        pGroup.divisions.add(carrierDiv);
        eGroup.divisions.add(enemyDiv);

        SimArena arena = new SimArena("10. Advanced Strike Craft Gunnery", "L-Crossfire, Hierarchical Sectors & AoA", pGroup, eGroup);
        SimShipState targetState = arena.addShip(
                enemyDiv, "tgt_cap", "HSS Onslaught", "onslaught", HullSize.CAPITAL_SHIP,
                0f, 1800f, 50f, 1200f, 3, 0);
        targetState.facing = 240f;
        targetState.velocity.set(45f, 0f); // Strafing east at 45 u/s
        com.fs.starfarer.api.combat.ShipAPI targetShip = targetState.proxy;

        // 1. Verify Multi-Axis Attack Geometry (Direct, L-Crossfire, Pincer)
        float directSq0 = divisionai.VoidstrikeCoordinator.computeSquadronAttackAngleOffsetDeg(
                divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.DIRECT_ASSAULT, 0, 2, true, targetShip, 0f, 1f);
        float directSq1 = divisionai.VoidstrikeCoordinator.computeSquadronAttackAngleOffsetDeg(
                divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.DIRECT_ASSAULT, 1, 2, true, targetShip, 0f, 1f);
        float anvilSq0 = divisionai.VoidstrikeCoordinator.computeSquadronAttackAngleOffsetDeg(
                divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.ANVIL_CROSSFIRE, 0, 2, true, targetShip, 0f, 1f);
        float anvilSq1 = divisionai.VoidstrikeCoordinator.computeSquadronAttackAngleOffsetDeg(
                divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.ANVIL_CROSSFIRE, 1, 2, true, targetShip, 0f, 1f);
        float pincerSq0 = divisionai.VoidstrikeCoordinator.computeSquadronAttackAngleOffsetDeg(
                divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.PINCER_ENVELOPMENT, 0, 2, true, targetShip, 0f, 1f);
        float pincerSq1 = divisionai.VoidstrikeCoordinator.computeSquadronAttackAngleOffsetDeg(
                divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.PINCER_ENVELOPMENT, 1, 2, true, targetShip, 0f, 1f);

        boolean geometryOk = Math.abs(directSq0) < 0.1f && Math.abs(directSq1) < 0.1f
                && Math.abs(anvilSq0) < 0.1f && Math.abs(Math.abs(anvilSq1) - 85f) < 0.1f
                && Math.abs(pincerSq0 - (-55f)) < 0.1f && Math.abs(pincerSq1 - 55f) < 0.1f;

        // 2. Verify 3-Tier Hierarchical Firing Sectors (Voidstrike -> Squadron -> Craft) for PRECISION vs AREA_SATURATION
        SimShipState bomberSampleState = arena.addShip(
                carrierDiv, "bmr_0", "Dagger 1", "dagger", HullSize.FIGHTER,
                0f, 1300f, 180f, 500f, 1, 0);
        com.fs.starfarer.api.combat.ShipAPI bomberSample = bomberSampleState.proxy;

        divisionai.VoidstrikeCoordinator.FiringSectorAssignment precC0 =
                divisionai.VoidstrikeCoordinator.computeHierarchicalFiringSector(
                        divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.PRECISION,
                        divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG,
                        targetShip, bomberSample, 0, 2, 0, 2, 0, 4, 0f, 1f, 1f, 0f);
        divisionai.VoidstrikeCoordinator.FiringSectorAssignment precC3 =
                divisionai.VoidstrikeCoordinator.computeHierarchicalFiringSector(
                        divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.PRECISION,
                        divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG,
                        targetShip, bomberSample, 1, 2, 1, 2, 3, 4, 0f, 1f, 1f, 0f);

        // In AREA_SATURATION across 2 participating divisions x 2 squadrons/div x 4 craft/squadron (16 craft total),
        // every division, squadron, and craft must receive a strictly increasing, non-overlapping lateral sector offset
        // spanning the target ship's silhouette + evasion envelope (> 380u total span for a 165u-radius capital)!
        List<Float> areaLateralOffsets = new ArrayList<>();
        float areaSpanWidth = 0f;
        for (int dIdx = 0; dIdx < 2; dIdx++) {
            for (int sIdx = 0; sIdx < 2; sIdx++) {
                for (int cIdx = 0; cIdx < 4; cIdx++) {
                    divisionai.VoidstrikeCoordinator.FiringSectorAssignment sec =
                            divisionai.VoidstrikeCoordinator.computeHierarchicalFiringSector(
                                    divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.AREA_SATURATION,
                                    divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG,
                                    targetShip, bomberSample, dIdx, 2, sIdx, 2, cIdx, 4, 0f, 1f, 1f, 0f);
                    areaSpanWidth = sec.voidstrikeSpanWidth;
                    areaLateralOffsets.add(sec.craftLateralOffset);
                }
            }
        }
        boolean strictlyMonotonicSectors = (areaLateralOffsets.size() == 16);
        for (int i = 1; i < areaLateralOffsets.size(); i++) {
            if (areaLateralOffsets.get(i) <= areaLateralOffsets.get(i - 1) + 15f) {
                strictlyMonotonicSectors = false;
                break;
            }
        }
        float totalCraftSpread = areaLateralOffsets.get(areaLateralOffsets.size() - 1) - areaLateralOffsets.get(0);
        boolean sectorsOk = (precC0.voidstrikeSpanWidth <= 36.1f)
                && (Math.abs(precC3.craftLateralOffset - precC0.craftLateralOffset) <= 36.1f)
                && (areaSpanWidth >= 380f)
                && strictlyMonotonicSectors
                && (totalCraftSpread >= 330f);

        // 3. Verify LOW vs STRONG Angle-of-Attack Correction (Ballistic Lead, Lateral Slip Damping & Off-Boresight Fire-Gating)
        divisionai.VoidstrikeCoordinator.FiringSectorAssignment lowSec =
                divisionai.VoidstrikeCoordinator.computeHierarchicalFiringSector(
                        divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.PRECISION,
                        divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.LOW,
                        targetShip, bomberSample, 0, 1, 0, 1, 0, 1, 0f, 1f, 1f, 0f);
        divisionai.VoidstrikeCoordinator.FiringSectorAssignment strongSec =
                divisionai.VoidstrikeCoordinator.computeHierarchicalFiringSector(
                        divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.PRECISION,
                        divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG,
                        targetShip, bomberSample, 0, 1, 0, 1, 0, 1, 0f, 1f, 1f, 0f);
        boolean leadScaleOk = (strongSec.worldAimPoint.x > lowSec.worldAimPoint.x + 12f);

        // Off-boresight bomber (facing 45 deg when target is at ~90 deg north, with heavy 120 u/s sideways drift):
        final SimShipState bomberStrongState = arena.addShip(
                carrierDiv, "bmr_strong", "Cobra 1", "cobra", HullSize.FIGHTER,
                0f, 1320f, 180f, 500f, 1, 0);
        bomberStrongState.facing = 45f; // 40+ deg off-boresight
        bomberStrongState.velocity.set(120f, 60f); // Heavy lateral slip

        final SimShipState bomberLowState = arena.addShip(
                carrierDiv, "bmr_low", "Cobra 2", "cobra", HullSize.FIGHTER,
                0f, 1320f, 180f, 500f, 1, 0);
        bomberLowState.facing = 45f;
        bomberLowState.velocity.set(120f, 60f);

        // 53 lines below verify off-boresight gating + multi-frame Newtonian AoA slip cancellation (zero instant vel.set jump!):
        boolean gatedStrongOffBoresight = divisionai.VoidstrikeCoordinator.applyTerminalStrikeCraftGunnery(
                bomberStrongState.proxy, bomberStrongState.aiFlags, targetShip, strongSec,
                divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG, true, 480f);
        boolean gatedLowOffBoresight = divisionai.VoidstrikeCoordinator.applyTerminalStrikeCraftGunnery(
                bomberLowState.proxy, bomberLowState.aiFlags, targetShip, lowSec,
                divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.LOW, true, 480f);
        // Verify zero instantaneous velocity mutation inside applyTerminalStrikeCraftGunnery itself:
        boolean zeroInstantVelTeleport = Math.abs(bomberStrongState.velocity.x - 120f) < 0.01f
                && Math.abs(bomberStrongState.velocity.y - 60f) < 0.01f;

        // Step 24 frames at 60 Hz (0.40s) through Newtonian thruster kinematics: STRONG AoA actively cancels lateral slip
        // via RCS STRAFE_LEFT/RIGHT + tight PD nose turn, achieving higher forward velocity and lower lateral slip than LOW!
        for (int af = 0; af < 24; af++) {
            stepRehearsalFighterKinematics60Hz(bomberStrongState, 1f / 60f);
            stepRehearsalFighterKinematics60Hz(bomberLowState, 1f / 60f);
            divisionai.VoidstrikeCoordinator.applyTerminalStrikeCraftGunnery(
                    bomberStrongState.proxy, bomberStrongState.aiFlags, targetShip, strongSec,
                    divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG, true, 480f);
            divisionai.VoidstrikeCoordinator.applyTerminalStrikeCraftGunnery(
                    bomberLowState.proxy, bomberLowState.aiFlags, targetShip, lowSec,
                    divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.LOW, true, 480f);
        }
        boolean strongSlipBetter = Math.abs(bomberStrongState.velocity.x) + 12f < Math.abs(bomberLowState.velocity.x);

        // Once bomberStrong aligns within 6.5 deg of worldAimPoint, fire-gate opens so torpedoes release cleanly!
        float alignedAng = (float) Math.toDegrees(Math.atan2(
                strongSec.worldAimPoint.y - bomberStrongState.location.y,
                strongSec.worldAimPoint.x - bomberStrongState.location.x));
        if (alignedAng < 0f) alignedAng += 360f;
        bomberStrongState.facing = alignedAng;
        bomberStrongState.angularVelocity = 0f;
        boolean gatedWhenAligned = divisionai.VoidstrikeCoordinator.applyTerminalStrikeCraftGunnery(
                bomberStrongState.proxy, bomberStrongState.aiFlags, targetShip, strongSec,
                divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG, true, 480f);

        boolean aoaOk = leadScaleOk
                && gatedStrongOffBoresight
                && !gatedLowOffBoresight
                && !gatedWhenAligned
                && zeroInstantVelTeleport
                && strongSlipBetter;

        // 3b. Closed-Loop 60 Hz Voidstrike Maneuver Rehearsal Suite:
        //   (A) Coordinated Stage 1 Marshalling -> Stage 2 L-Crossfire (Anvil 0 deg + Hammer +-85 deg orthogonal dogleg)
        //   (B) Pincer Envelopment (+-55 deg Port & Starboard jaws brackets)
        //   (C) Split-Wing Voidstrikes (SPLIT_AXES & SPLIT_TARGETS hitting 3 enemy division ships inside ONE Voidstrike entity)
        //   (D) Culmination & 60 Hz Egress Recovery back to carrier
        //   (E) Battle 9 Regression: Zero Fighters Mating With Ship Centers & Zero Non-Physical Squadron Snap-Turns / Jinking
        rehearsalSnapTurnViolations = 0;
        rehearsalVelTeleportViolations = 0;
        rehearsalWingmanSteerViolations = 0;
        boolean lCrossfireRehearsalOk = rehearseClosedLoopLCrossfireVoidstrike();
        boolean pincerRehearsalOk = rehearseClosedLoopPincerVoidstrike();
        boolean splitTargetsRehearsalOk = rehearseClosedLoopSplitTargetsVoidstrike();
        boolean maxRangePkgEmergencyOk = rehearseMaxRangePackageDrivenAndEmergencyVoidstrike();
        boolean physicsAndAntiMatingOk = rehearseFighterPhysicsAndAntiMatingInvariants();
        boolean zeroKinematicViolations = (rehearsalSnapTurnViolations == 0
                && rehearsalVelTeleportViolations == 0
                && rehearsalWingmanSteerViolations == 0);

        check(geometryOk && sectorsOk && aoaOk
                        && lCrossfireRehearsalOk && pincerRehearsalOk && splitTargetsRehearsalOk
                        && maxRangePkgEmergencyOk && physicsAndAntiMatingOk && zeroKinematicViolations,
                "Scenario 10: Advanced Strike Craft Gunnery, Newtonian Flight Physics & Closed-Loop Voidstrike Rehearsal (Zero Ship-Center Mating & Zero Snap-Turns)",
                String.format(Locale.ROOT,
                        "geometry=[direct=%.0f/%.0f, anvil=%.0f/%.0f, pincer=%.0f/%.0f], sectors=[precSpan=%.1fu, areaSpan=%.1fu, 16craftSpread=%.1fu, monotonic=%b], aoa=[leadX=%.1f>%.1f, strongGateOffBore=%b, lowGate=%b, alignedGate=%b, zeroTeleport=%b], rehearsals=[LCrossfire=%b, Pincer=%b, SplitTargets1Entity=%b, MaxRangePkgEmergency=%b, AntiMatingPhysics=%b, snapViol=%d, velViol=%d, wingmanViol=%d]",
                        directSq0, directSq1, anvilSq0, anvilSq1, pincerSq0, pincerSq1,
                        precC0.voidstrikeSpanWidth, areaSpanWidth, totalCraftSpread, strictlyMonotonicSectors,
                        strongSec.worldAimPoint.x, lowSec.worldAimPoint.x,
                        gatedStrongOffBoresight, gatedLowOffBoresight, gatedWhenAligned, zeroInstantVelTeleport,
                        lCrossfireRehearsalOk, pincerRehearsalOk, splitTargetsRehearsalOk, maxRangePkgEmergencyOk,
                        physicsAndAntiMatingOk, rehearsalSnapTurnViolations, rehearsalVelTeleportViolations, rehearsalWingmanSteerViolations));

        // 4. Verify the 4x4 Showcase Mission Matrix (16 missions, ~300+ DP per side, 6+ Cruiser+ carriers per side, baked-in PermanentDivisionSpecs)
        int missionsVerified = 0;
        boolean allDivsBaked = true;
        for (int r = 1; r <= 4; r++) {
            for (int c = 1; c <= 4; c++) {
                String mid = divisionai.DivisionShowcaseMissions.getMissionId(r, c);
                com.fs.starfarer.api.mission.MissionDefinitionPlugin plugin;
                try {
                    Class<?> mCls = Class.forName("data.missions." + mid + ".MissionDefinition");
                    plugin = (com.fs.starfarer.api.mission.MissionDefinitionPlugin) mCls.getDeclaredConstructor().newInstance();
                } catch (Exception ex) {
                    throw new RuntimeException("Failed to load MissionDefinition for " + mid, ex);
                }
                final int[] memberSeq = new int[]{0};
                final List<String> briefings = new ArrayList<>();
                com.fs.starfarer.api.mission.MissionDefinitionAPI mockApi =
                        (com.fs.starfarer.api.mission.MissionDefinitionAPI) Proxy.newProxyInstance(
                                com.fs.starfarer.api.mission.MissionDefinitionAPI.class.getClassLoader(),
                                new Class<?>[]{com.fs.starfarer.api.mission.MissionDefinitionAPI.class},
                                (p, m, a) -> {
                                    String n = m.getName();
                                    if ("addBriefingItem".equals(n) && a != null && a.length > 0 && a[0] instanceof String) {
                                        briefings.add((String) a[0]);
                                        return null;
                                    }
                                    if ("addToFleet".equals(n)) {
                                        final String[] idHolder = new String[]{"m_" + (++memberSeq[0])};
                                        final String vId = (a != null && a.length > 1 && a[1] instanceof String) ? (String) a[1] : "heron_Strike";
                                        return (com.fs.starfarer.api.fleet.FleetMemberAPI) Proxy.newProxyInstance(
                                                com.fs.starfarer.api.fleet.FleetMemberAPI.class.getClassLoader(),
                                                new Class<?>[]{com.fs.starfarer.api.fleet.FleetMemberAPI.class},
                                                (mp, mm, ma) -> {
                                                    String mn = mm.getName();
                                                    if ("getId".equals(mn)) return idHolder[0];
                                                    if ("setId".equals(mn) && ma != null && ma.length > 0 && ma[0] instanceof String) {
                                                        idHolder[0] = (String) ma[0];
                                                        return null;
                                                    }
                                                    if ("getSpecId".equals(mn)) return vId;
                                                    Class<?> rt = mm.getReturnType();
                                                    if (rt == boolean.class) return false;
                                                    if (rt == int.class) return 0;
                                                    if (rt == float.class) return 0f;
                                                    return null;
                                                });
                                    }
                                    Class<?> rt = m.getReturnType();
                                    if (rt == boolean.class) return false;
                                    if (rt == int.class) return 0;
                                    if (rt == float.class) return 0f;
                                    return null;
                                });
                plugin.defineMission(mockApi);
                divisionai.PermanentDivisionManager.MissionScenarioBundle bundle =
                        divisionai.PermanentDivisionManager.getRegisteredMissionScenarioTemplate(mid);
                int expectedP = (r == 4) ? 8 : 9;
                int expectedE = (c == 4) ? 8 : 9;
                boolean briefingOk = briefings.size() == 1
                        && briefings.get(0).contains("This is a dynamic wargame scenario taught at the academy based on historical military doctrine.");
                if (bundle == null
                        || !briefingOk
                        || bundle.playerDivisions.size() != expectedP
                        || bundle.enemyDivisions.size() != expectedE) {
                    allDivsBaked = false;
                } else {
                    int pCarrierShips = 0;
                    int eCarrierShips = 0;
                    int pFrigateShips = 0;
                    int eFrigateShips = 0;
                    int pFrigDivs = 0;
                    int eFrigDivs = 0;
                    for (divisionai.PermanentDivisionManager.PermanentDivisionSpec s : bundle.playerDivisions) {
                        if (s.archetype != null && s.archetype.isCarrierArchetype()) {
                            pCarrierShips += s.memberIds.size();
                            if (s.customVoidstrikeGunneryMode != divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.AREA_SATURATION
                                    || s.customVoidstrikeAoaCorrection != divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG) {
                                allDivsBaked = false;
                            }
                        }
                        if (s.archetype == DivisionArchetype.FRIGATE_DIVISION
                                || s.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION
                                || s.archetype == DivisionArchetype.PHASE_DIVISION) {
                            pFrigDivs++;
                            pFrigateShips += s.memberIds.size();
                        }
                    }
                    for (divisionai.PermanentDivisionManager.PermanentDivisionSpec s : bundle.enemyDivisions) {
                        if (s.archetype != null && s.archetype.isCarrierArchetype()) {
                            eCarrierShips += s.memberIds.size();
                        }
                        if (s.archetype == DivisionArchetype.FRIGATE_DIVISION
                                || s.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION
                                || s.archetype == DivisionArchetype.PHASE_DIVISION) {
                            eFrigDivs++;
                            eFrigateShips += s.memberIds.size();
                        }
                    }
                    if (pCarrierShips < 6 || eCarrierShips < 6
                            || pFrigateShips != 20 || eFrigateShips != 20
                            || pFrigDivs != 4 || eFrigDivs != 4) {
                        allDivsBaked = false;
                    }
                    missionsVerified++;
                }
            }
        }
        divisionai.PermanentDivisionManager.clearActiveMissionScenario();
        check(missionsVerified == 16 && allDivsBaked,
                "4x4 Showcase Mission Matrix (16 missions, academy wargame flavor text, 6+ Cruiser+ Carriers & 20 Frigates across 4 doctrinal divisions per fleet)",
                String.format(Locale.ROOT, "missionsVerified=%d/16, allDivsBaked=%b", missionsVerified, allDivsBaked));
    }

    private static com.fs.starfarer.api.combat.FighterWingAPI createRehearsalWing(
            final SimShipState carrierState,
            final List<SimShipState> craftStates,
            final boolean isBomber,
            final boolean[] returningOverride) {
        final List<com.fs.starfarer.api.combat.ShipAPI> memberProxies = new ArrayList<>();
        for (SimShipState cs : craftStates) {
            memberProxies.add(cs.proxy);
        }
        final com.fs.starfarer.api.loading.FighterWingSpecAPI specProxy =
                (com.fs.starfarer.api.loading.FighterWingSpecAPI) Proxy.newProxyInstance(
                        com.fs.starfarer.api.loading.FighterWingSpecAPI.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.loading.FighterWingSpecAPI.class},
                        (p, m, a) -> {
                            String n = m.getName();
                            if ("isBomber".equals(n)) return isBomber;
                            if ("getNumFighters".equals(n)) return craftStates.size();
                            Class<?> rt = m.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == float.class) return 0f;
                            return null;
                        });
        com.fs.starfarer.api.combat.FighterWingAPI wingProxy =
                (com.fs.starfarer.api.combat.FighterWingAPI) Proxy.newProxyInstance(
                        com.fs.starfarer.api.combat.FighterWingAPI.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.FighterWingAPI.class},
                        (p, m, a) -> {
                            String n = m.getName();
                            if ("equals".equals(n)) return p == a[0];
                            if ("hashCode".equals(n)) return System.identityHashCode(p);
                            if ("getSpec".equals(n)) return specProxy;
                            if ("getRole".equals(n)) {
                                return isBomber
                                        ? com.fs.starfarer.api.loading.WingRole.BOMBER
                                        : com.fs.starfarer.api.loading.WingRole.FIGHTER;
                            }
                            if ("getSourceShip".equals(n)) return carrierState.proxy;
                            if ("getWingMembers".equals(n)) return memberProxies;
                            if ("getLeader".equals(n)) return memberProxies.isEmpty() ? null : memberProxies.get(0);
                            if ("isReturning".equals(n)) {
                                return (returningOverride != null && returningOverride[0]) || carrierState.pullBackFighters;
                            }
                            if ("orderReturn".equals(n)) {
                                if (returningOverride != null) returningOverride[0] = true;
                                return null;
                            }
                            if ("stopReturning".equals(n)) {
                                if (returningOverride != null) returningOverride[0] = false;
                                return null;
                            }
                            Class<?> rt = m.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == float.class) return 0f;
                            return null;
                        });
        for (SimShipState cs : craftStates) {
            cs.wing = wingProxy;
        }
        carrierState.wings.add(wingProxy);
        return wingProxy;
    }

    private static int rehearsalSnapTurnViolations = 0;
    private static int rehearsalVelTeleportViolations = 0;
    private static int rehearsalWingmanSteerViolations = 0;

    private static float shortestDeg(float fromDeg, float toDeg) {
        float diff = (toDeg - fromDeg) % 360f;
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;
        return diff;
    }

    private static void stepRehearsalFighterKinematics60Hz(SimShipState f, float dt) {
        if (!f.alive) return;
        // 1. Integrate position from velocity (matching vanilla CombatEngine.advance Step 1)
        f.location.x += f.velocity.x * dt;
        f.location.y += f.velocity.y * dt;

        com.fs.starfarer.api.combat.ShipAPI leader = (f.wing != null) ? f.wing.getLeader() : f.proxy;
        boolean isWingLeader = (leader == null || leader == f.proxy);
        boolean isReturning = (f.wing != null && f.wing.isReturning(f.proxy));

        // 2. Non-leader, non-returning wingmen follow their Wing Leader smoothly via vanilla maneuvers.N:
        //    Verify VoidstrikeCoordinator NEVER injects competing helm turn overrides onto wingmen!
        if (!isWingLeader && !isReturning && leader != null && leader.isAlive()) {
            if (f.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.TURN_LEFT)
                    || f.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.TURN_RIGHT)) {
                rehearsalWingmanSteerViolations++;
            }
            List<com.fs.starfarer.api.combat.ShipAPI> members = f.wing.getWingMembers();
            int slotIdx = Math.max(1, members.indexOf(f.proxy));
            int rank = (slotIdx + 1) / 2;
            float sideSign = (slotIdx % 2 == 1) ? -1f : 1f;
            float back = rank * 36f;
            float lat = sideSign * rank * 36f;
            double lRad = Math.toRadians(leader.getFacing());
            float fwdX = (float) Math.cos(lRad);
            float fwdY = (float) Math.sin(lRad);
            float rightX = fwdY;
            float rightY = -fwdX;
            float slotX = leader.getLocation().x - fwdX * back + rightX * lat;
            float slotY = leader.getLocation().y - fwdY * back + rightY * lat;
            float dx = slotX - f.location.x;
            float dy = slotY - f.location.y;
            float dSlot = (float) Math.sqrt(dx * dx + dy * dy);
            float tVx = leader.getVelocity().x;
            float tVy = leader.getVelocity().y;
            if (dSlot > 2f) {
                float catchSpd = Math.min(f.maxSpeed, dSlot * 3.0f);
                tVx += (dx / dSlot) * catchSpd;
                tVy += (dy / dSlot) * catchSpd;
                float tSpd = (float) Math.sqrt(tVx * tVx + tVy * tVy);
                if (tSpd > f.maxSpeed) {
                    tVx *= (f.maxSpeed / tSpd);
                    tVy *= (f.maxSpeed / tSpd);
                }
            }
            float maxDv = 420f * dt;
            float dvx = tVx - f.velocity.x;
            float dvy = tVy - f.velocity.y;
            float dvMag = (float) Math.sqrt(dvx * dvx + dvy * dvy);
            if (dvMag > maxDv && dvMag > 1e-4f) {
                dvx *= (maxDv / dvMag);
                dvy *= (maxDv / dvMag);
            }
            f.velocity.set(f.velocity.x + dvx, f.velocity.y + dvy);
            float maxTurn = 135f * dt;
            float angDiff = shortestDeg(f.facing, leader.getFacing());
            f.facing = (f.facing + Math.max(-maxTurn, Math.min(maxTurn, angDiff)) + 360f) % 360f;
            f.givenCommands.clear();
            f.blockedCommands.clear();
            return;
        }

        // 3. Vanilla FighterAI fallback commands (appended AFTER EveryFrameCombatPlugin commands, respecting blockedCommands
        //    and first-turn-command precedence exactly as in decompiled Ship.advance!):
        Object campObj = f.aiFlags.getCustom(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.CAMP_LOCATION);
        Object escObj = f.aiFlags.getCustom(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.ESCORT_OTHER_SHIP);
        boolean doNotPursue = f.aiFlags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.DO_NOT_PURSUE);
        Object mtObj = f.aiFlags.getCustom(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.MANEUVER_TARGET);
        Vector2f goal = null;
        boolean landingApproach = false;
        if (isReturning && f.wing != null && f.wing.getSourceShip() != null) {
            Vector2f cLoc = f.wing.getSourceShip().getLocation();
            // Model offset launch bays (-85, -45) / (+85, +45) away from carrier center so returning craft land at their bay!
            float baySide = ((f.id.hashCode() & 1) == 0) ? -85f : 85f;
            goal = new Vector2f(cLoc.x + baySide, cLoc.y - 45f);
            landingApproach = true;
        } else if (campObj instanceof Vector2f) {
            goal = (Vector2f) campObj;
        } else if (doNotPursue && escObj instanceof com.fs.starfarer.api.combat.ShipAPI) {
            // Vanilla EscortTargetManeuverV3 orbits outside collisionRadius rather than sitting on (0,0) center!
            com.fs.starfarer.api.combat.ShipAPI esc = (com.fs.starfarer.api.combat.ShipAPI) escObj;
            float escR = Math.max(180f, esc.getCollisionRadius() + 95f);
            double orbA = (f.id.hashCode() & 7) * (Math.PI * 0.25);
            goal = new Vector2f(
                    esc.getLocation().x + (float) Math.cos(orbA) * escR,
                    esc.getLocation().y + (float) Math.sin(orbA) * escR);
        } else if (f.shipTarget != null && f.shipTarget.isAlive()) {
            goal = f.shipTarget.getLocation();
        } else if (mtObj instanceof com.fs.starfarer.api.combat.ShipAPI
                && ((com.fs.starfarer.api.combat.ShipAPI) mtObj).isAlive()) {
            goal = ((com.fs.starfarer.api.combat.ShipAPI) mtObj).getLocation();
        }

        boolean pluginGaveTurn = f.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.TURN_LEFT)
                || f.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.TURN_RIGHT)
                || f.blockedCommands.contains(com.fs.starfarer.api.combat.ShipCommand.TURN_LEFT)
                || f.blockedCommands.contains(com.fs.starfarer.api.combat.ShipCommand.TURN_RIGHT);
        boolean pluginGaveThrust = f.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.ACCELERATE)
                || f.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.DECELERATE)
                || f.blockedCommands.contains(com.fs.starfarer.api.combat.ShipCommand.ACCELERATE)
                || f.blockedCommands.contains(com.fs.starfarer.api.combat.ShipCommand.DECELERATE);

        if (goal != null) {
            float dx = goal.x - f.location.x;
            float dy = goal.y - f.location.y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > 6f) {
                float desAng = (float) Math.toDegrees(Math.atan2(dy, dx));
                if (desAng < 0f) desAng += 360f;
                float diff = shortestDeg(f.facing, desAng);
                float stopDeg = (f.angularVelocity * Math.abs(f.angularVelocity)) / (2.0f * 380f);
                float lead = diff - stopDeg;
                if (!pluginGaveTurn) {
                    if (lead > 1.8f) {
                        f.proxy.giveCommand(com.fs.starfarer.api.combat.ShipCommand.TURN_LEFT, null, 0);
                    } else if (lead < -1.8f) {
                        f.proxy.giveCommand(com.fs.starfarer.api.combat.ShipCommand.TURN_RIGHT, null, 0);
                    }
                }
                if (!pluginGaveThrust) {
                    if (landingApproach && d < 120f && Math.hypot(f.velocity.x, f.velocity.y) > 90f) {
                        f.proxy.giveCommand(com.fs.starfarer.api.combat.ShipCommand.DECELERATE, null, 0);
                    } else if (Math.abs(diff) > 100f && Math.hypot(f.velocity.x, f.velocity.y) > f.maxSpeed * 0.45f) {
                        f.proxy.giveCommand(com.fs.starfarer.api.combat.ShipCommand.DECELERATE, null, 0);
                    } else {
                        f.proxy.giveCommand(com.fs.starfarer.api.combat.ShipCommand.ACCELERATE, null, 0);
                    }
                }
            }
        }

        // 4. Newtonian Ship.engineController.processCommands integration (strict maxTurnRate, turnAccel, and linAccel):
        float maxTurnRate = 135f;
        float turnAccel = 380f;
        float linAccel = Math.max(260f, f.accel);

        boolean turnL = f.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.TURN_LEFT);
        boolean turnR = f.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.TURN_RIGHT);
        if (turnL && !turnR) {
            f.angularVelocity = Math.min(maxTurnRate, f.angularVelocity + turnAccel * dt);
        } else if (turnR && !turnL) {
            f.angularVelocity = Math.max(-maxTurnRate, f.angularVelocity - turnAccel * dt);
        } else {
            float damp = turnAccel * dt;
            if (Math.abs(f.angularVelocity) <= damp) {
                f.angularVelocity = 0f;
            } else {
                f.angularVelocity -= Math.signum(f.angularVelocity) * damp;
            }
        }

        float prevFacing = f.facing;
        f.facing = (f.facing + f.angularVelocity * dt) % 360f;
        if (f.facing < 0f) f.facing += 360f;
        if (Math.abs(shortestDeg(prevFacing, f.facing)) > maxTurnRate * dt + 0.5f) {
            rehearsalSnapTurnViolations++;
        }

        double rad = Math.toRadians(f.facing);
        float noseX = (float) Math.cos(rad);
        float noseY = (float) Math.sin(rad);
        float leftX = -noseY;
        float leftY = noseX;

        float prevVx = f.velocity.x;
        float prevVy = f.velocity.y;
        float vFwd = prevVx * noseX + prevVy * noseY;
        float vLeft = prevVx * leftX + prevVy * leftY;

        boolean decel = f.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.DECELERATE);
        boolean accelCmd = f.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.ACCELERATE);
        boolean strafeL = f.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.STRAFE_LEFT);
        boolean strafeR = f.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.STRAFE_RIGHT);

        if (decel) {
            float dStep = linAccel * dt;
            vFwd = (Math.abs(vFwd) <= dStep) ? 0f : (vFwd - Math.signum(vFwd) * dStep);
            float lStep = linAccel * 0.80f * dt;
            vLeft = (Math.abs(vLeft) <= lStep) ? 0f : (vLeft - Math.signum(vLeft) * lStep);
        } else if (accelCmd) {
            vFwd = Math.min(f.maxSpeed, vFwd + linAccel * dt);
            float lStep = linAccel * 0.55f * dt;
            vLeft = (Math.abs(vLeft) <= lStep) ? 0f : (vLeft - Math.signum(vLeft) * lStep);
        }

        if (strafeL && !strafeR) {
            vLeft += linAccel * 0.65f * dt;
        } else if (strafeR && !strafeL) {
            vLeft -= linAccel * 0.65f * dt;
        }

        float newVx = noseX * vFwd + leftX * vLeft;
        float newVy = noseY * vFwd + leftY * vLeft;
        float newSpd = (float) Math.sqrt(newVx * newVx + newVy * newVy);
        if (newSpd > f.maxSpeed && newSpd > 1e-4f) {
            newVx *= (f.maxSpeed / newSpd);
            newVy *= (f.maxSpeed / newSpd);
        }
        float dv = (float) Math.hypot(newVx - prevVx, newVy - prevVy);
        float maxAllowedDv = linAccel * 1.35f * dt;
        if (dv > maxAllowedDv && dv > 1e-4f) {
            newVx = prevVx + (newVx - prevVx) * (maxAllowedDv / dv);
            newVy = prevVy + (newVy - prevVy) * (maxAllowedDv / dv);
            dv = maxAllowedDv;
        }
        if (dv > linAccel * 1.40f * dt + 1.0f) {
            rehearsalVelTeleportViolations++;
        }
        f.velocity.set(newVx, newVy);

        f.givenCommands.clear();
        f.blockedCommands.clear();
    }

    /**
     * Rehearsal A: Closed-Loop 60 Hz Coordinated L-Crossfire Voidstrike (Stage 0 -> Stage 1 Marshal -> Stage 2 L-Dogleg -> Terminal Run -> Egress).
     */
    private static boolean rehearseClosedLoopLCrossfireVoidstrike() {
        divisionai.VoidstrikeCoordinator.resetForNewBattle();
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "steady");
        BattleGroupState pGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroup = new BattleGroupState(1, eCmdr);

        DivisionState carrierDiv = new DivisionState(
                "reh_l_cdiv", 0, 1, DivisionArchetype.FLEET_CARRIER_DIVISION, "Carrier Div. I", pCmdr);
        carrierDiv.currentIntent = DivisionIntentType.LAUNCH_ALPHA_STRIKE;
        carrierDiv.voidstrikeObservance = divisionai.PermanentDivisionManager.VoidstrikeTargetObservance.HIGH;
        carrierDiv.voidstrikeCoordination = divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode.COORDINATED;
        carrierDiv.voidstrikeAttackGeometry = divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.ANVIL_CROSSFIRE;
        carrierDiv.voidstrikeSplitMode = divisionai.PermanentDivisionManager.VoidstrikeSplitMode.SPLIT_AXES;
        carrierDiv.voidstrikeGunneryMode = divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.AREA_SATURATION;
        carrierDiv.voidstrikeAoaCorrection = divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG;
        carrierDiv.voidstrikeStage = 0;
        carrierDiv.voidstrikeStageStartTime = -999f;

        DivisionState enemyDiv = new DivisionState(
                "reh_l_ediv", 1, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Enemy Battle Div. I", eCmdr);
        carrierDiv.targetEnemyDivision = enemyDiv;
        pGroup.divisions.add(carrierDiv);
        eGroup.divisions.add(enemyDiv);

        SimArena arena = new SimArena("Rehearsal A: L-Crossfire", "Coordinated Stage 1 Marshal & 90-Deg L-Dogleg", pGroup, eGroup);
        SimShipState carrier = arena.addShip(carrierDiv, "reh_c1", "HSS Legion", "legion", HullSize.CAPITAL_SHIP, 0f, 0f, 50f, 1200f, 2, 4);
        carrierDiv.centroid.set(0f, 0f);
        pGroup.shipIdToDivision.put(carrier.id, carrierDiv);

        SimShipState enemyCap = arena.addShip(enemyDiv, "reh_e1", "PLS Conquest", "conquest", HullSize.CAPITAL_SHIP, 0f, 1500f, 45f, 1200f, 2, 0);
        enemyCap.facing = 270f; // Shield facing South toward carrier (0, 0)
        enemyDiv.centroid.set(0f, 1500f);
        eGroup.shipIdToDivision.put(enemyCap.id, enemyDiv);

        boolean[] ret0 = new boolean[]{false};
        boolean[] ret1 = new boolean[]{false};
        List<SimShipState> anvilWing = new ArrayList<>();
        List<SimShipState> hammerWing = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            SimShipState fa = arena.addShip(carrierDiv, "anvil_f" + i, "Dagger A" + i, "dagger", HullSize.FIGHTER, -30f + i * 30f, 20f, 220f, 500f, 1, 0);
            anvilWing.add(fa);
            SimShipState fh = arena.addShip(carrierDiv, "hammer_f" + i, "Dagger H" + i, "dagger", HullSize.FIGHTER, -30f + i * 30f, -20f, 220f, 500f, 1, 0);
            hammerWing.add(fh);
        }
        createRehearsalWing(carrier, anvilWing, true, ret0);
        createRehearsalWing(carrier, hammerWing, true, ret1);

        boolean sawStage1HoldWithoutPrematureRush = false;
        float maxHammerLateralX = 0f;
        float maxAnvilLateralX = 0f;
        boolean hammerHeldFireOnDogleg = false;
        boolean anvilReachedTarget = false;
        boolean hammerReachedTargetFromFlank = false;
        boolean singleVoidstrikeEntityMaintained = true;
        boolean sawActiveVoidstrike = false;

        float dt = 0.05f;
        for (int step = 0; step <= 320; step++) {
            float now = step * dt;
            if (step % 5 == 0) {
                divisionai.VoidstrikeCoordinator.updateDivisionVoidstrikeStaging(carrierDiv, pGroup, enemyCap.proxy, now);
                divisionai.VoidstrikeCoordinator.updateVoidstrikes(arena.engineProxy, pGroup, null, eGroup, now);
            }
            divisionai.VoidstrikeCoordinator.advanceEveryFrameStrikeCraftGunnery(arena.engineProxy, pGroup, null, eGroup, now);

            if (carrierDiv.voidstrikeStage == 1 && now >= 1.5f) {
                float maxYInStage1 = -9999f;
                for (SimShipState f : anvilWing) maxYInStage1 = Math.max(maxYInStage1, f.location.y);
                for (SimShipState f : hammerWing) maxYInStage1 = Math.max(maxYInStage1, f.location.y);
                if (maxYInStage1 < 650f && maxYInStage1 > 120f) {
                    sawStage1HoldWithoutPrematureRush = true;
                }
            }

            List<divisionai.VoidstrikeCoordinator.VoidstrikeRecord> activeVs = divisionai.VoidstrikeCoordinator.getActiveVoidstrikes();
            if (!activeVs.isEmpty()) {
                sawActiveVoidstrike = true;
                if (activeVs.size() > 1) {
                    singleVoidstrikeEntityMaintained = false;
                }
            }

            for (SimShipState f : hammerWing) {
                float absX = Math.abs(f.location.x);
                if (absX > maxHammerLateralX) maxHammerLateralX = absX;
                if (absX > 300f && f.blockedCommands.contains(com.fs.starfarer.api.combat.ShipCommand.FIRE)) {
                    hammerHeldFireOnDogleg = true;
                }
                if (dist(f.location, enemyCap.location) <= 420f && Math.abs(f.location.x) >= 180f) {
                    hammerReachedTargetFromFlank = true;
                }
            }
            for (SimShipState f : anvilWing) {
                if (f.location.y < 1200f) {
                    maxAnvilLateralX = Math.max(maxAnvilLateralX, Math.abs(f.location.x));
                }
                if (dist(f.location, enemyCap.location) <= 420f) {
                    anvilReachedTarget = true;
                }
            }

            for (SimShipState f : anvilWing) stepRehearsalFighterKinematics60Hz(f, dt);
            for (SimShipState f : hammerWing) stepRehearsalFighterKinematics60Hz(f, dt);
        }

        return sawStage1HoldWithoutPrematureRush
                && maxHammerLateralX >= 450f
                && maxAnvilLateralX <= 220f
                && hammerHeldFireOnDogleg
                && anvilReachedTarget
                && hammerReachedTargetFromFlank
                && sawActiveVoidstrike
                && singleVoidstrikeEntityMaintained;
    }

    /**
     * Rehearsal B: Closed-Loop 60 Hz Pincer Envelopment Voidstrike (Port -55 deg Jaw & Starboard +55 deg Jaw in 1 Voidstrike Entity).
     */
    private static boolean rehearseClosedLoopPincerVoidstrike() {
        divisionai.VoidstrikeCoordinator.resetForNewBattle();
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "steady");
        BattleGroupState pGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroup = new BattleGroupState(1, eCmdr);

        DivisionState carrierDiv = new DivisionState(
                "reh_p_cdiv", 0, 1, DivisionArchetype.LIGHT_CARRIER_DIVISION, "Carrier Div. II", pCmdr);
        carrierDiv.currentIntent = DivisionIntentType.LAUNCH_ALPHA_STRIKE;
        carrierDiv.voidstrikeObservance = divisionai.PermanentDivisionManager.VoidstrikeTargetObservance.HIGH;
        carrierDiv.voidstrikeCoordination = divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode.UNCOORDINATED;
        carrierDiv.voidstrikeAttackGeometry = divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.PINCER_ENVELOPMENT;
        carrierDiv.voidstrikeSplitMode = divisionai.PermanentDivisionManager.VoidstrikeSplitMode.SPLIT_AXES;
        carrierDiv.voidstrikeGunneryMode = divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.AREA_SATURATION;
        carrierDiv.voidstrikeAoaCorrection = divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG;
        carrierDiv.voidstrikeStage = 2;

        DivisionState enemyDiv = new DivisionState(
                "reh_p_ediv", 1, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Enemy Battle Div. I", eCmdr);
        carrierDiv.targetEnemyDivision = enemyDiv;
        pGroup.divisions.add(carrierDiv);
        eGroup.divisions.add(enemyDiv);

        SimArena arena = new SimArena("Rehearsal B: Pincer", "Two-Jaw +-55 Deg Envelopment", pGroup, eGroup);
        SimShipState carrier = arena.addShip(carrierDiv, "reh_pc1", "HSS Mora", "mora", HullSize.CRUISER, 0f, 0f, 55f, 1100f, 2, 2);
        carrierDiv.centroid.set(0f, 0f);
        pGroup.shipIdToDivision.put(carrier.id, carrierDiv);

        SimShipState enemyCap = arena.addShip(enemyDiv, "reh_pe1", "PLS Onslaught", "onslaught", HullSize.CAPITAL_SHIP, 0f, 1400f, 45f, 1200f, 2, 0);
        enemyCap.facing = 270f;
        enemyDiv.centroid.set(0f, 1400f);
        eGroup.shipIdToDivision.put(enemyCap.id, enemyDiv);

        List<SimShipState> jawPort = new ArrayList<>();
        List<SimShipState> jawStbd = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            jawPort.add(arena.addShip(carrierDiv, "jawP_f" + i, "Perdition P" + i, "perdition", HullSize.FIGHTER, -20f + i * 20f, 0f, 220f, 500f, 1, 0));
            jawStbd.add(arena.addShip(carrierDiv, "jawS_f" + i, "Perdition S" + i, "perdition", HullSize.FIGHTER, -20f + i * 20f, 0f, 220f, 500f, 1, 0));
        }
        createRehearsalWing(carrier, jawPort, true, new boolean[]{false});
        createRehearsalWing(carrier, jawStbd, true, new boolean[]{false});

        float minXReached = 0f;
        float maxXReached = 0f;
        boolean portArrived = false;
        boolean stbdArrived = false;
        boolean singleVoidstrikeMaintained = true;

        float dt = 0.05f;
        for (int step = 0; step <= 260; step++) {
            float now = step * dt;
            if (step % 5 == 0) {
                divisionai.VoidstrikeCoordinator.updateVoidstrikes(arena.engineProxy, pGroup, null, eGroup, now);
            }
            divisionai.VoidstrikeCoordinator.advanceEveryFrameStrikeCraftGunnery(arena.engineProxy, pGroup, null, eGroup, now);

            List<divisionai.VoidstrikeCoordinator.VoidstrikeRecord> activeVs = divisionai.VoidstrikeCoordinator.getActiveVoidstrikes();
            if (activeVs.size() > 1) {
                singleVoidstrikeMaintained = false;
            }
            for (SimShipState f : jawPort) {
                minXReached = Math.min(minXReached, f.location.x);
                maxXReached = Math.max(maxXReached, f.location.x);
                if (dist(f.location, enemyCap.location) <= 420f) portArrived = true;
            }
            for (SimShipState f : jawStbd) {
                minXReached = Math.min(minXReached, f.location.x);
                maxXReached = Math.max(maxXReached, f.location.x);
                if (dist(f.location, enemyCap.location) <= 420f) stbdArrived = true;
            }
            for (SimShipState f : jawPort) stepRehearsalFighterKinematics60Hz(f, dt);
            for (SimShipState f : jawStbd) stepRehearsalFighterKinematics60Hz(f, dt);
        }

        return minXReached <= -360f
                && maxXReached >= 360f
                && portArrived
                && stbdArrived
                && singleVoidstrikeMaintained;
    }

    /**
     * Rehearsal C: Closed-Loop 60 Hz Split-Wing Voidstrike (`SPLIT_TARGETS`) distributing 3 squadrons across
     * 3 ships of an enemy Cruiser Division while remaining unified inside ONE Voidstrike entity, followed by
     * sortie culmination and 60 Hz egress recovery back to the carrier.
     */
    private static boolean rehearseClosedLoopSplitTargetsVoidstrike() {
        divisionai.VoidstrikeCoordinator.resetForNewBattle();
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "steady");
        BattleGroupState pGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroup = new BattleGroupState(1, eCmdr);

        DivisionState carrierDiv = new DivisionState(
                "reh_s_cdiv", 0, 1, DivisionArchetype.FLEET_CARRIER_DIVISION, "Split Carrier Div. I", pCmdr);
        carrierDiv.currentIntent = DivisionIntentType.LAUNCH_ALPHA_STRIKE;
        carrierDiv.voidstrikeObservance = divisionai.PermanentDivisionManager.VoidstrikeTargetObservance.HIGH;
        carrierDiv.voidstrikeCoordination = divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode.UNCOORDINATED;
        carrierDiv.voidstrikeAttackGeometry = divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.DIRECT_ASSAULT;
        carrierDiv.voidstrikeSplitMode = divisionai.PermanentDivisionManager.VoidstrikeSplitMode.SPLIT_TARGETS;
        carrierDiv.voidstrikeGunneryMode = divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.AREA_SATURATION;
        carrierDiv.voidstrikeAoaCorrection = divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG;
        carrierDiv.voidstrikeStage = 2;

        DivisionState enemyDiv = new DivisionState(
                "reh_s_ediv", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Enemy Cruiser Div. I", eCmdr);
        carrierDiv.targetEnemyDivision = enemyDiv;
        pGroup.divisions.add(carrierDiv);
        eGroup.divisions.add(enemyDiv);

        SimArena arena = new SimArena("Rehearsal C: Split-Wing Targets", "3 Squadrons -> 3 Enemy Ships in 1 Voidstrike Entity", pGroup, eGroup);
        SimShipState carrier = arena.addShip(carrierDiv, "reh_sc1", "TTS Astral", "astral", HullSize.CAPITAL_SHIP, 0f, 0f, 50f, 1200f, 2, 3);
        carrierDiv.centroid.set(0f, 0f);
        pGroup.shipIdToDivision.put(carrier.id, carrierDiv);

        SimShipState eShip0 = arena.addShip(enemyDiv, "reh_se0", "PLS Eagle 1", "eagle", HullSize.CRUISER, 0f, 1350f, 50f, 1000f, 1, 0);
        SimShipState eShip1 = arena.addShip(enemyDiv, "reh_se1", "PLS Eagle 2", "eagle", HullSize.CRUISER, -420f, 1300f, 50f, 1000f, 1, 0);
        SimShipState eShip2 = arena.addShip(enemyDiv, "reh_se2", "PLS Falcon 3", "falcon", HullSize.CRUISER, 420f, 1300f, 55f, 1000f, 1, 0);
        enemyDiv.flagship = eShip0.proxy;
        enemyDiv.centroid.set(0f, 1320f);
        eGroup.shipIdToDivision.put(eShip0.id, enemyDiv);
        eGroup.shipIdToDivision.put(eShip1.id, enemyDiv);
        eGroup.shipIdToDivision.put(eShip2.id, enemyDiv);

        boolean[] ret0 = new boolean[]{false};
        boolean[] ret1 = new boolean[]{false};
        boolean[] ret2 = new boolean[]{false};
        List<SimShipState> w0 = new ArrayList<>();
        List<SimShipState> w1 = new ArrayList<>();
        List<SimShipState> w2 = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            w0.add(arena.addShip(carrierDiv, "sw0_f" + i, "Trident A" + i, "trident", HullSize.FIGHTER, -20f + i * 20f, 0f, 225f, 500f, 1, 0));
            w1.add(arena.addShip(carrierDiv, "sw1_f" + i, "Trident B" + i, "trident", HullSize.FIGHTER, -20f + i * 20f, 0f, 225f, 500f, 1, 0));
            w2.add(arena.addShip(carrierDiv, "sw2_f" + i, "Trident C" + i, "trident", HullSize.FIGHTER, -20f + i * 20f, 0f, 225f, 500f, 1, 0));
        }
        createRehearsalWing(carrier, w0, true, ret0);
        createRehearsalWing(carrier, w1, true, ret1);
        createRehearsalWing(carrier, w2, true, ret2);

        boolean hitE0 = false;
        boolean hitE1 = false;
        boolean hitE2 = false;
        boolean sawSingleSplitEntityWith3Targets = false;
        boolean neverFragmentedIntoMultipleVoidstrikes = true;
        boolean egressRecoveredToCarrier = false;

        float dt = 0.05f;
        for (int step = 0; step <= 320; step++) {
            float now = step * dt;
            if (hitE0 && hitE1 && hitE2 && !ret0[0]) {
                // Simulate bombers expending ordnance once all 3 enemy division ships have been struck
                for (SimShipState f : w0) f.strikeAmmo = 0;
                for (SimShipState f : w1) f.strikeAmmo = 0;
                for (SimShipState f : w2) f.strikeAmmo = 0;
                ret0[0] = true;
                ret1[0] = true;
                ret2[0] = true;
            }
            if (step % 5 == 0) {
                divisionai.VoidstrikeCoordinator.updateDivisionVoidstrikeStaging(carrierDiv, pGroup, eShip0.proxy, now);
                divisionai.VoidstrikeCoordinator.updateVoidstrikes(arena.engineProxy, pGroup, null, eGroup, now);
            }
            divisionai.VoidstrikeCoordinator.advanceEveryFrameStrikeCraftGunnery(arena.engineProxy, pGroup, null, eGroup, now);

            List<divisionai.VoidstrikeCoordinator.VoidstrikeRecord> activeVs = divisionai.VoidstrikeCoordinator.getActiveVoidstrikes();
            if (activeVs.size() > 1) {
                neverFragmentedIntoMultipleVoidstrikes = false;
            }
            if (activeVs.size() == 1) {
                divisionai.VoidstrikeCoordinator.VoidstrikeRecord rec = activeVs.get(0);
                if (rec.splitWingActive && rec.distinctTargetCount == 3 && rec.voidstrikeNumber == 1) {
                    sawSingleSplitEntityWith3Targets = true;
                }
            }

            for (SimShipState f : w0) {
                if (dist(f.location, eShip0.location) <= 260f) hitE0 = true;
                stepRehearsalFighterKinematics60Hz(f, dt);
            }
            for (SimShipState f : w1) {
                if (dist(f.location, eShip1.location) <= 260f) hitE1 = true;
                stepRehearsalFighterKinematics60Hz(f, dt);
            }
            for (SimShipState f : w2) {
                if (dist(f.location, eShip2.location) <= 260f) hitE2 = true;
                stepRehearsalFighterKinematics60Hz(f, dt);
            }

            if (ret0[0] && activeVs.isEmpty() && dist(w0.get(0).location, carrier.location) <= 680f) {
                egressRecoveredToCarrier = true;
            }
        }

        return hitE0 && hitE1 && hitE2
                && sawSingleSplitEntityWith3Targets
                && neverFragmentedIntoMultipleVoidstrikes
                && egressRecoveredToCarrier;
    }

    /**
     * Rehearsal D: Closed-Loop 60 Hz Maximum-Range Voidstrike (7,200u cross-map), Package-Driven Timeouts
     * (`MassedStrikePackageProfile`), Premature Return Cancellation on Armed Bombers, and Emergency-Only Pullback.
     */
    private static boolean rehearseMaxRangePackageDrivenAndEmergencyVoidstrike() {
        divisionai.VoidstrikeCoordinator.resetForNewBattle();
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "steady");
        BattleGroupState pGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroup = new BattleGroupState(1, eCmdr);

        DivisionState carrierDiv = new DivisionState(
                "reh_mr_cdiv", 0, 1, DivisionArchetype.FLEET_CARRIER_DIVISION, "MaxRange Carrier Div. I", pCmdr);
        carrierDiv.currentIntent = DivisionIntentType.LAUNCH_ALPHA_STRIKE;
        carrierDiv.voidstrikeObservance = divisionai.PermanentDivisionManager.VoidstrikeTargetObservance.HIGH;
        carrierDiv.voidstrikeCoordination = divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode.UNCOORDINATED;
        carrierDiv.voidstrikeAttackGeometry = divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.ANVIL_CROSSFIRE;
        carrierDiv.voidstrikeSplitMode = divisionai.PermanentDivisionManager.VoidstrikeSplitMode.SPLIT_AXES;
        carrierDiv.voidstrikeGunneryMode = divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.AREA_SATURATION;
        carrierDiv.voidstrikeAoaCorrection = divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG;
        carrierDiv.voidstrikeStage = 2;

        DivisionState enemyDiv = new DivisionState(
                "reh_mr_ediv", 1, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Far Enemy Battle Div. I", eCmdr);
        carrierDiv.targetEnemyDivision = enemyDiv;
        pGroup.divisions.add(carrierDiv);
        eGroup.divisions.add(enemyDiv);

        SimArena arena = new SimArena("Rehearsal D: Max-Range Package Strike", "7,200u Cross-Map Strike & Emergency-Only Recall", pGroup, eGroup);
        SimShipState carrier = arena.addShip(carrierDiv, "reh_mrc1", "HSS Mora", "mora", HullSize.CRUISER, 0f, -3200f, 45f, 1000f, 1, 3);
        carrierDiv.centroid.set(0f, -3200f);
        pGroup.shipIdToDivision.put(carrier.id, carrierDiv);

        // Enemy capital is 7,200u away at (0, +4000) — far beyond vanilla's 4,000u fighter leash and old 38s timeout!
        SimShipState enemyCap = arena.addShip(enemyDiv, "reh_mre1", "ISS Onslaught", "onslaught", HullSize.CAPITAL_SHIP, 0f, 4000f, 40f, 1200f, 2, 0);
        enemyCap.facing = 270f;
        enemyDiv.flagship = enemyCap.proxy;
        enemyDiv.centroid.set(0f, 4000f);
        eGroup.shipIdToDivision.put(enemyCap.id, enemyDiv);

        boolean[] ret0 = new boolean[]{false};
        boolean[] ret1 = new boolean[]{false};
        List<SimShipState> slowBomberWing = new ArrayList<>();
        List<SimShipState> escortFighterWing = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            // Slow heavy torpedo bombers (145 u/s) with strikeAmmo = 2
            SimShipState b = arena.addShip(carrierDiv, "mr_bmr_" + i, "Trident " + i, "trident", HullSize.FIGHTER, -20f + i * 20f, -3150f, 145f, 520f, 2, 0);
            slowBomberWing.add(b);
            // Fast gun-only strike fighters (250 u/s) with strikeAmmo = 0
            SimShipState f = arena.addShip(carrierDiv, "mr_ftr_" + i, "Broadsword " + i, "broadsword", HullSize.FIGHTER, -20f + i * 20f, -3180f, 250f, 420f, 0, 0);
            escortFighterWing.add(f);
        }
        createRehearsalWing(carrier, slowBomberWing, true, ret0);
        createRehearsalWing(carrier, escortFighterWing, false, ret1);

        // 1. Verify MassedStrikePackageProfile upon execution weights speed toward the 145 u/s bombers and grants > 85s approach timeout!
        divisionai.VoidstrikeCoordinator.MassedStrikePackageProfile pkg =
                divisionai.VoidstrikeCoordinator.computeMassedStrikePackageProfile(carrierDiv, enemyCap.proxy);
        boolean packageProfileOk = pkg.maxEffectivePackageMass >= 10f
                && pkg.initialArmedBomberCount == 3
                && pkg.effectivePackageSpeed >= 140f && pkg.effectivePackageSpeed <= 185f
                && pkg.executionDistanceToTarget >= 7000f
                && pkg.packageApproachTimeoutSec >= 85f
                && pkg.packageStrikePassTimeoutSec >= 26f;

        boolean prematureReturnCancelledOk = false;
        boolean survivedPastOld38sTimeout = false;
        boolean nonEmergencyDidNotPullBack = true;
        boolean bombersExpendedOrdnanceAtTarget = false;
        String culminationReasonSeen = null;

        float dt = 0.10f;
        for (int step = 0; step <= 700; step++) {
            float now = step * dt;

            // At t = 15s (bombers ~1,000u downrange with full ammo), simulate:
            //  (a) vanilla M.fighterNeedsRefit prematurely ordering return on 95%-hull bombers (ret0[0] = true), and
            //  (b) a non-emergency division intent (HOLD_IN_RESERVE) while bombers are committed downrange!
            if (step == 150) {
                for (SimShipState b : slowBomberWing) {
                    b.hullLevel = 0.95f;
                }
                ret0[0] = true;
                carrierDiv.currentIntent = DivisionIntentType.HOLD_IN_RESERVE;
            }
            if (step == 160) {
                carrierDiv.currentIntent = DivisionIntentType.LAUNCH_ALPHA_STRIKE;
            }

            // Once the slow bombers actually reach weapon release range (<= 380u) of the enemy capital at ~52s,
            // they drop their torpedoes (strikeAmmo = 0)!
            boolean allBombersInReleaseRange = true;
            for (SimShipState b : slowBomberWing) {
                if (dist(b.location, enemyCap.location) > 380f) {
                    allBombersInReleaseRange = false;
                    break;
                }
            }
            if (allBombersInReleaseRange && slowBomberWing.get(0).strikeAmmo > 0) {
                for (SimShipState b : slowBomberWing) {
                    b.strikeAmmo = 0;
                }
                bombersExpendedOrdnanceAtTarget = true;
            }

            if (step % 3 == 0) {
                divisionai.VoidstrikeCoordinator.updateDivisionVoidstrikeStaging(carrierDiv, pGroup, enemyCap.proxy, now);
                divisionai.VoidstrikeCoordinator.updateVoidstrikes(arena.engineProxy, pGroup, null, eGroup, now);
            }
            divisionai.VoidstrikeCoordinator.advanceEveryFrameStrikeCraftGunnery(arena.engineProxy, pGroup, null, eGroup, now);

            if (step == 152) {
                // Verify premature return on 95%-hull armed bombers was immediately cancelled and non-emergency HOLD_IN_RESERVE did not pull back!
                prematureReturnCancelledOk = !ret0[0] && !carrier.pullBackFighters;
                if (carrier.pullBackFighters) {
                    nonEmergencyDidNotPullBack = false;
                }
            }
            if (now >= 42.0f && !bombersExpendedOrdnanceAtTarget) {
                List<divisionai.VoidstrikeCoordinator.VoidstrikeRecord> activeAt42 = divisionai.VoidstrikeCoordinator.getActiveVoidstrikes();
                if (activeAt42.size() == 1 && activeAt42.get(0).voidstrikeNumber == 1) {
                    survivedPastOld38sTimeout = true;
                }
            }

            for (SimShipState b : slowBomberWing) stepRehearsalFighterKinematics60Hz(b, dt);
            for (SimShipState f : escortFighterWing) stepRehearsalFighterKinematics60Hz(f, dt);

            if (bombersExpendedOrdnanceAtTarget && divisionai.VoidstrikeCoordinator.getActiveVoidstrikes().isEmpty()) {
                culminationReasonSeen = "ordnance expended";
                break;
            }
        }

        // Finally, verify that a GENUINE emergency (carrier at 88% flux under close enemy attack) DOES trigger emergency pullback!
        for (SimShipState b : slowBomberWing) b.strikeAmmo = 2;
        ret0[0] = false;
        carrier.fluxLevel = 0.88f;
        carrierDiv.localEnemyStrength = 20f;
        boolean emergencyTriggeredOk = divisionai.VoidstrikeCoordinator.isVoidstrikeEmergencyPullbackRequired(
                carrier.proxy, carrierDiv, pGroup, enemyCap.proxy);
        divisionai.VoidstrikeCoordinator.advanceEveryFrameStrikeCraftGunnery(arena.engineProxy, pGroup, null, eGroup, 90f);
        boolean emergencyPulledBackOk = emergencyTriggeredOk && carrier.pullBackFighters && carrierDiv.voidstrikeEmergencyRecallActive;

        // Also verify:
        //  (1) Closer-target switching (resolveSurvivableSquadronTargetShip) when an intervening enemy ship blocks the flight corridor,
        //  (2) Anti-AA threat-skirting & dynamic jinking (computeAntiAaEvasionWaypoint) around non-target AA ships, and
        //  (3) Self-preservation break-off when a strike craft is heavily damaged (< 48% hull) outside terminal range.
        carrier.fluxLevel = 0f;
        carrierDiv.localEnemyStrength = 0f;
        carrierDiv.voidstrikeEmergencyRecallActive = false;
        carrierDiv.voidstrikeEgressActive = false;
        SimShipState interveningAaCruiser = arena.addShip(enemyDiv, "reh_mre_aa", "TTS Anubis", "anubis", HullSize.CRUISER, 0f, -1800f, 90f, 800f, 1, 0);
        eGroup.shipIdToDivision.put(interveningAaCruiser.id, enemyDiv);
        slowBomberWing.get(0).location.set(0f, -2900f);
        slowBomberWing.get(0).hullLevel = 0.85f;
        com.fs.starfarer.api.combat.ShipAPI switchedTarget = divisionai.VoidstrikeCoordinator.resolveSurvivableSquadronTargetShip(
                carrier.proxy,
                carrierDiv,
                slowBomberWing.get(0).proxy,
                java.util.Collections.singletonList(slowBomberWing.get(0).proxy),
                enemyCap.proxy,
                divisionai.PermanentDivisionManager.VoidstrikeTargetObservance.HIGH,
                true);
        boolean switchedToCloserBlockerOk = (switchedTarget == interveningAaCruiser.proxy);

        Vector2f rawGoal = new Vector2f(0f, 4000f);
        Vector2f evasiveGoal = divisionai.VoidstrikeCoordinator.computeAntiAaEvasionWaypoint(
                slowBomberWing.get(0).proxy,
                enemyCap.proxy,
                rawGoal,
                0,
                0,
                12.5f);
        boolean aaEvasionDeflectedOk = Math.abs(evasiveGoal.x - rawGoal.x) >= 80f;

        for (SimShipState b : slowBomberWing) {
            b.location.set(0f, -2900f);
            b.hullLevel = 0.40f;
        }
        ret0[0] = false;
        divisionai.VoidstrikeCoordinator.advanceEveryFrameStrikeCraftGunnery(arena.engineProxy, pGroup, null, eGroup, 95f);
        boolean damagedCraftBrokeOffOk = ret0[0];

        return packageProfileOk
                && prematureReturnCancelledOk
                && nonEmergencyDidNotPullBack
                && survivedPastOld38sTimeout
                && bombersExpendedOrdnanceAtTarget
                && "ordnance expended".equals(culminationReasonSeen)
                && emergencyPulledBackOk
                && switchedToCloserBlockerOk
                && aaEvasionDeflectedOk
                && damagedCraftBrokeOffOk;
    }

    /**
     * Rehearsal E (Battle 9 Regression): Verifies that:
     *  (1) Returning strike craft release steering outside landingHandOffDist so they fly to offset launch bays
     *      instead of getting pinned to carrier.getLocation() ("fighters mating with ships").
     *  (2) Post-Voidstrike Egress (`voidstrikeEgressActive`) and 1-Squadron CAP never place waypoints on
     *      `(escortAnchor.x, escortAnchor.y)` center.
     *  (3) Outbound bombers that have flown over/past an enemy ship (and infinite-ammo fixed-gun interceptors)
     *      are NEVER pulled 180 deg back into `target.getLocation()`.
     *  (4) Sudden 180-deg Voidstrike Egress orders and enemy omni-shield wobbles during `ANVIL_CROSSFIRE` produce
     *      smooth Newtonian banking curves (`|dFacing| <= maxTurnRate*dt`, `|dv| <= accel*dt`, zero flank-sign flips).
     */
    private static boolean rehearseFighterPhysicsAndAntiMatingInvariants() {
        divisionai.VoidstrikeCoordinator.resetForNewBattle();
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "steady");
        BattleGroupState pGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroup = new BattleGroupState(1, eCmdr);

        DivisionState carrierDiv = new DivisionState(
                "reh_b9_cdiv", 0, 1, DivisionArchetype.FLEET_CARRIER_DIVISION, "Carrier Div. B9", pCmdr);
        carrierDiv.currentIntent = DivisionIntentType.LAUNCH_ALPHA_STRIKE;
        carrierDiv.voidstrikeObservance = divisionai.PermanentDivisionManager.VoidstrikeTargetObservance.HIGH;
        carrierDiv.voidstrikeCoordination = divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode.UNCOORDINATED;
        carrierDiv.voidstrikeAttackGeometry = divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.ANVIL_CROSSFIRE;
        carrierDiv.voidstrikeSplitMode = divisionai.PermanentDivisionManager.VoidstrikeSplitMode.UNIFIED;
        carrierDiv.voidstrikeGunneryMode = divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.AREA_SATURATION;
        carrierDiv.voidstrikeAoaCorrection = divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG;
        carrierDiv.voidstrikeStage = 2;

        DivisionState enemyDiv = new DivisionState(
                "reh_b9_ediv", 1, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Enemy Battle Div. B9", eCmdr);
        carrierDiv.targetEnemyDivision = enemyDiv;
        pGroup.divisions.add(carrierDiv);
        eGroup.divisions.add(enemyDiv);

        SimArena arena = new SimArena("Rehearsal E: Physics & Anti-Mating", "Battle 9 Regression Suite", pGroup, eGroup);
        SimShipState carrier = arena.addShip(carrierDiv, "b9_cv", "ISS Shoebill", "shoebill", HullSize.CRUISER, 0f, 0f, 55f, 1100f, 1, 2);
        carrierDiv.flagship = carrier.proxy;
        carrierDiv.centroid.set(0f, 0f);
        pGroup.shipIdToDivision.put(carrier.id, carrierDiv);

        SimShipState enemyCap = arena.addShip(enemyDiv, "b9_ecap", "Pirate Atlas Mk.II", "atlas2", HullSize.CAPITAL_SHIP, 0f, 1500f, 45f, 1200f, 2, 0);
        enemyCap.detailedCombatModel = true;
        enemyCap.facing = 269f;
        enemyDiv.flagship = enemyCap.proxy;
        enemyDiv.centroid.set(0f, 1500f);
        eGroup.shipIdToDivision.put(enemyCap.id, enemyDiv);

        boolean[] retBombers = new boolean[]{false};
        boolean[] retGunFighters = new boolean[]{false};
        List<SimShipState> bomberWing = new ArrayList<>();
        List<SimShipState> gunWing = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            SimShipState b = arena.addShip(carrierDiv, "b9_bmr_" + i, "Dagger " + i, "dagger", HullSize.FIGHTER, -25f + i * 25f, 360f, 210f, 520f, 1, 0);
            b.facing = 90f;
            b.velocity.set(0f, 200f);
            bomberWing.add(b);

            // Infinite-ammo fixed-gun fighter (strikeAmmo = 0, fixed narrow-arc ballistic autocannon)
            SimShipState g = arena.addShip(carrierDiv, "b9_gun_" + i, "Broadsword " + i, "broadsword", HullSize.FIGHTER, -25f + i * 25f, 1220f, 240f, 450f, 0, 0);
            g.weaponMounts.clear();
            g.weapons.clear();
            SimArena.SimWeaponMount fixedGun = new SimArena.SimWeaponMount(
                    "b9_ac_" + i,
                    com.fs.starfarer.api.combat.WeaponAPI.WeaponType.BALLISTIC,
                    com.fs.starfarer.api.combat.WeaponAPI.WeaponSize.SMALL,
                    com.fs.starfarer.api.combat.DamageType.KINETIC,
                    500f, 10f, 0f, 40f, 80f, 20f, 0.5f, false, 0, false, false, false);
            g.weaponMounts.add(fixedGun);
            g.weapons.add(fixedGun.proxy);
            g.facing = 90f;
            g.velocity.set(0f, 220f);
            gunWing.add(g);
        }
        createRehearsalWing(carrier, bomberWing, true, retBombers);
        createRehearsalWing(carrier, gunWing, false, retGunFighters);

        // (1) Verify flank-sign hysteresis latch prevents 170-deg (+85 <-> -85) flip-flop when enemy omni-shield wobbles:
        float signAt269 = divisionai.VoidstrikeCoordinator.computeSquadronAttackAngleOffsetDeg(
                divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.ANVIL_CROSSFIRE,
                divisionai.PermanentDivisionManager.VoidstrikeSplitMode.UNIFIED,
                1, 2, true, enemyCap.proxy, 0f, 1f);
        enemyCap.facing = 271f; // Wobble shield 2 deg across 270-deg centerline!
        float signAt271 = divisionai.VoidstrikeCoordinator.computeSquadronAttackAngleOffsetDeg(
                divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.ANVIL_CROSSFIRE,
                divisionai.PermanentDivisionManager.VoidstrikeSplitMode.UNIFIED,
                1, 2, true, enemyCap.proxy, 0f, 1f);
        boolean flankLatchSteady = Math.abs(signAt269) > 80f && Math.signum(signAt269) == Math.signum(signAt271);

        // (2) Verify outbound bomber past enemy hull & infinite-ammo gun fighter are NEVER pulled back into target center:
        SimShipState leadBomber = bomberWing.get(0);
        leadBomber.location.set(0f, 1790f); // 290u past enemyCap at (0, 1500), flying North away from enemyCap!
        leadBomber.facing = 90f;
        leadBomber.velocity.set(0f, 205f);
        leadBomber.givenCommands.clear();
        divisionai.VoidstrikeCoordinator.FiringSectorAssignment secOut =
                divisionai.VoidstrikeCoordinator.computeHierarchicalFiringSector(
                        divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.PRECISION,
                        divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG,
                        enemyCap.proxy, leadBomber.proxy, 0, 1, 0, 2, 0, 3, 0f, 1f, 1f, 0f);
        boolean outboundDidNotEngage = !divisionai.VoidstrikeCoordinator.applyTerminalStrikeCraftGunnery(
                leadBomber.proxy, leadBomber.aiFlags, enemyCap.proxy, secOut,
                divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG, true,
                dist(leadBomber.location, enemyCap.location))
                && !leadBomber.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.TURN_LEFT)
                && !leadBomber.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.TURN_RIGHT);

        // Verify infinite-ammo fixed-gun fighter at dEnemy = 280u on DIRECT_ASSAULT is never locked into terminal dive steering:
        carrierDiv.voidstrikeAttackGeometry = divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.DIRECT_ASSAULT;
        gunWing.get(0).givenCommands.clear();
        divisionai.VoidstrikeCoordinator.advanceEveryFrameStrikeCraftGunnery(arena.engineProxy, pGroup, null, eGroup, 2.0f);
        boolean gunFighterNotDiveLocked = !gunWing.get(0).givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.TURN_LEFT)
                && !gunWing.get(0).givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.TURN_RIGHT)
                && !gunWing.get(0).aiFlags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF);

        // (3) Trigger sudden 180-deg Voidstrike Egress while sprinting North at +205 u/s:
        //     Verify smooth Newtonian banking turn (cannot flip South in 1-3 frames!) and clean handoff outside carrier center!
        for (int i = 0; i < 3; i++) {
            bomberWing.get(i).location.set(-20f + i * 20f, 620f);
            bomberWing.get(i).facing = 90f;
            bomberWing.get(i).velocity.set(0f, 205f);
            bomberWing.get(i).strikeAmmo = 0;
        }
        retBombers[0] = true;
        carrierDiv.voidstrikeEgressActive = true;
        carrier.pullBackFighters = true;

        float dt60 = 1f / 60f;
        boolean stillMovingNorthAfter3Frames = false;
        boolean handedOffInsideLandingEnvelope = false;
        boolean landedAtOffsetBayWithoutCenterMating = false;
        float minDistToCarrierCenter = 9999f;

        for (int fStep = 0; fStep < 420; fStep++) {
            float tNow = 10f + fStep * dt60;
            divisionai.VoidstrikeCoordinator.advanceEveryFrameStrikeCraftGunnery(arena.engineProxy, pGroup, null, eGroup, tNow);
            if (fStep == 3) {
                // After 3 frames (0.05s), a real fighter at +205 u/s North CANNOT have snapped its velocity South!
                stillMovingNorthAfter3Frames = (leadBomber.velocity.y > 140f);
            }
            float dCenter = dist(leadBomber.location, carrier.location);
            minDistToCarrierCenter = Math.min(minDistToCarrierCenter, dCenter);
            if (dCenter <= 420f
                    && leadBomber.aiFlags.getCustom(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.MOVEMENT_DEST) == null
                    && leadBomber.givenCommands.isEmpty()) {
                handedOffInsideLandingEnvelope = true;
            }
            for (SimShipState b : bomberWing) stepRehearsalFighterKinematics60Hz(b, dt60);
            for (SimShipState g : gunWing) stepRehearsalFighterKinematics60Hz(g, dt60);

            float baySide = ((leadBomber.id.hashCode() & 1) == 0) ? -85f : 85f;
            Vector2f expectedBay = new Vector2f(carrier.location.x + baySide, carrier.location.y - 45f);
            if (dist(leadBomber.location, expectedBay) <= 35f && dCenter >= 55f) {
                landedAtOffsetBayWithoutCenterMating = true;
                break;
            }
        }

        // (4) Verify 1-Squadron CAP (`totalDivWings == 1`) places patrolSlot well forward of escortAnchor, never at (0,0) center:
        carrier.wings.remove(1); // Leave exactly 1 squadron on carrier
        retBombers[0] = false;
        for (SimShipState b : bomberWing) b.strikeAmmo = 1;
        carrierDiv.voidstrikeEgressActive = false;
        carrier.pullBackFighters = false;
        carrierDiv.currentIntent = DivisionIntentType.FLY_COMBAT_SPACE_PATROL;
        enemyCap.location.set(0f, 4800f); // Live enemy far outside CAP intercept radius so holdCapPatrolOverFriendly = true!
        divisionai.VoidstrikeCoordinator.advanceEveryFrameStrikeCraftGunnery(arena.engineProxy, pGroup, null, eGroup, 20f);
        Object capDestObj = leadBomber.aiFlags.getCustom(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.MOVEMENT_DEST);
        boolean singleSquadronCapOffCenter = (capDestObj instanceof Vector2f)
                && dist((Vector2f) capDestObj, carrier.location) >= 220f;

        return flankLatchSteady
                && outboundDidNotEngage
                && gunFighterNotDiveLocked
                && stillMovingNorthAfter3Frames
                && handedOffInsideLandingEnvelope
                && landedAtOffsetBayWithoutCenterMating
                && minDistToCarrierCenter >= 45f
                && singleSquadronCapOffCenter;
    }
    private static void testUIHarnessAndUXSuccinctness() throws Exception {
        System.out.println("\n--- Scenario 7: UI/UX Testing Harness (Deployment Window 14x7 & Succinctness Audit) ---");
        File uiPreviewOut = new File("build/ui_preview.html");
        UITestHarness.UIAuditResult uiRes = UITestHarness.runAllAuditsAndExportPreview(uiPreviewOut);
        for (String line : uiRes.details) {
            System.out.println(line);
        }
        check(uiRes.allPassed && uiPreviewOut.exists(),
                "UI/UX Testing Harness passes all layout, succinctness, and button-width audits",
                "checksPassed=" + uiRes.checksPassed + ", checksFailed=" + uiRes.checksFailed
                        + ", preview=" + uiPreviewOut.getPath());
    }

    /**
     * Scenario 8: Bytecode verification that mod/jars/DivisionAI.jar contains zero java/lang/reflect references,
     * zero non-whitelisted java/io/* or java/nio/file/* references blocked by com.fs.starfarer.loading.scripts.B,
     * and passes macOS obfuscated class/method/field fallback inside Starsector's com.fs.starfarer.loading.scripts.B classloader.
     */
    private static void testZeroReflectionInCompiledJar() throws Exception {
        System.out.println("\n--- Scenario 8: Bytecode Security Check (Zero java/lang/reflect & ScriptStore.B Sandbox) ---");
        File jarFile = new File("mod/jars/DivisionAI.jar");
        int classCount = 0;
        List<String> violations = new ArrayList<>();
        java.util.Set<String> allowedJavaIo = new java.util.HashSet<>(java.util.Arrays.asList(
                "java/io/BufferedInputStream",
                "java/io/BufferedReader",
                "java/io/FilterInputStream",
                "java/io/InputStreamReader",
                "java/io/Reader",
                "java/io/Serializable",
                "java/io/InvalidClassException",
                "java/io/ObjectStreamException",
                "java/io/InputStream",
                "java/io/IOException",
                "java/io/PrintStream",
                "java/io/PrintWriter",
                "java/io/ByteArrayInputStream",
                "java/io/FilterOutputStream",
                "java/io/OutputStream",
                "java/io/Closeable",
                "java/io/Flushable",
                "java/io/StringReader",
                "java/io/FileReader"
        ));
        java.util.regex.Pattern ioPattern = java.util.regex.Pattern.compile("java/(?:io|nio)/[A-Za-z0-9_$/]+");
        try (ZipFile zf = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    classCount++;
                    try (InputStream in = zf.getInputStream(entry)) {
                        byte[] bytes = in.readAllBytes();
                        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
                        if (raw.contains("java/lang/reflect")) {
                            violations.add(entry.getName() + ":java/lang/reflect");
                        }
                        java.util.regex.Matcher m = ioPattern.matcher(raw);
                        while (m.find()) {
                            String ref = m.group(0);
                            if (ref.startsWith("java/io/") && !allowedJavaIo.contains(ref)) {
                                violations.add(entry.getName() + ":" + ref);
                            } else if (ref.startsWith("java/nio/file/")) {
                                violations.add(entry.getName() + ":" + ref);
                            }
                        }
                    }
                }
            }
        }
        check(classCount > 0 && violations.isEmpty(),
                "DivisionAI.jar contains zero java/lang/reflect and zero non-whitelisted java/io or java/nio/file entries",
                "scannedClasses=" + classCount + ", violations=" + violations);

        // Verify ObfuscatedBytecodeBridge & all handle verifiers inside Starsector's com.fs.starfarer.loading.scripts.B
        // while forcing preferred Windows obfuscated class names to miss on first lookup (simulating macOS).
        ClassLoader parent = com.fs.starfarer.combat.CombatState.class.getClassLoader();
        Class<?> ssCls = Class.forName("com.fs.starfarer.settings.StarfarerSettings", true, parent);
        org.json.JSONObject dummyCfg = new org.json.JSONObject();
        dummyCfg.put("devMode", false);
        dummyCfg.put("logClassLoading", false);
        for (java.lang.reflect.Field f : ssCls.getDeclaredFields()) {
            if (f.getType() == org.json.JSONObject.class) {
                f.setAccessible(true);
                if (f.get(null) == null) {
                    f.set(null, dummyCfg);
                }
            }
        }
        Class<?> bCls = resolveScriptClassLoaderClass(parent, "com.fs.starfarer.loading.scripts.ScriptClassLoader");
        ClassLoader bLoader = (ClassLoader) bCls.getConstructor(ClassLoader.class).newInstance(parent);
        java.net.URLClassLoader modLoader = new java.net.URLClassLoader(
                new java.net.URL[]{jarFile.toURI().toURL()}, bLoader) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return null;
            }
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("divisionai.")) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) c = findClass(name);
                    if (resolve) resolveClass(c);
                    return c;
                }
                return super.loadClass(name, resolve);
            }
        };
        Class<?> bridge = Class.forName("divisionai.ObfuscatedBytecodeBridge", true, modLoader);
        Object csInfo = bridge.getMethod("readClassInfo", ClassLoader.class, String.class)
                .invoke(null, modLoader, "com.fs.starfarer.combat.CombatState");
        Class<?> dddo = Class.forName("divisionai.DivisionDeploymentDialogOrganizer", true, modLoader);
        boolean deployOk = (Boolean) dddo.getMethod("verifyObfuscatedHandles", ClassLoader.class).invoke(null, modLoader);
        Class<?> dcai = Class.forName("divisionai.DivisionCommanderAI", true, modLoader);
        boolean combatOk = (Boolean) dcai.getMethod("verifyCombatObfuscatedHandles", ClassLoader.class).invoke(null, modLoader);
        Class<?> das = Class.forName("divisionai.DivisionAISettings", true, modLoader);
        boolean settingsOk = (Boolean) das.getMethod("verifySettingsObfuscatedHandles", ClassLoader.class).invoke(null, modLoader);
        check(csInfo != null && deployOk && combatOk && settingsOk,
                "Sandboxed ScriptClassLoader + simulated macOS obfuscated bytecode fallback succeeds",
                String.format(Locale.ROOT, "readClassInfo=%b, deployHandles=%b, combatHandles=%b, settingsHandles=%b",
                        csInfo != null, deployOk, combatOk, settingsOk));

        // Verify all 7 obfuscated handle bridges against REAL cross-platform / cross-version Starsector JARs
        // (macOS 0.98.5a-RC5, Linux 0.98.5a-RC5, and Windows 0.98a-RC8 if configured via lib/starsector_rc8_core_path.txt)
        List<String[]> platformEntries = new ArrayList<>();
        platformEntries.add(new String[]{"macOS 0.98.5a-RC5", "[!] Starsector for Mac/Starsector.app/Contents/Resources/Java", "com.fs.starfarer.loading.scripts.OoOO"});
        platformEntries.add(new String[]{"Linux 0.98.5a-RC5", "[!] Starsector for Linux/starsector", "com.fs.starfarer.loading.scripts.new"});
        File rc8Cfg = new File("lib/starsector_rc8_core_path.txt");
        if (rc8Cfg.isFile()) {
            String rc8Path = java.nio.file.Files.readString(rc8Cfg.toPath(), StandardCharsets.UTF_8).trim();
            if (!rc8Path.isEmpty()) {
                platformEntries.add(new String[]{"Windows 0.98a-RC8", rc8Path, "com.fs.starfarer.loading.scripts.B"});
            }
        }
        for (String[] platEntry : platformEntries) {
            String platName = platEntry[0];
            File platDir = new File(platEntry[1]);
            String legacyScriptClName = platEntry[2];
            if (!platDir.isDirectory()) continue;
            File[] platJars = platDir.listFiles((d, name) -> name.endsWith(".jar"));
            if (platJars == null || platJars.length == 0) continue;
            List<java.net.URL> urls = new ArrayList<>();
            for (File pj : platJars) {
                urls.add(pj.toURI().toURL());
            }
            urls.add(new File("lib/LunaLib.jar").toURI().toURL());
            try (java.net.URLClassLoader platEngineLoader = new java.net.URLClassLoader(
                    urls.toArray(new java.net.URL[0]), ClassLoader.getPlatformClassLoader())) {
                Class<?> platSsCls = Class.forName("com.fs.starfarer.settings.StarfarerSettings", true, platEngineLoader);
                Class<?> platJsonCls = Class.forName("org.json.JSONObject", true, platEngineLoader);
                Object platDummyCfg = platJsonCls.getConstructor().newInstance();
                platJsonCls.getMethod("put", String.class, boolean.class).invoke(platDummyCfg, "devMode", false);
                platJsonCls.getMethod("put", String.class, boolean.class).invoke(platDummyCfg, "logClassLoading", false);
                for (java.lang.reflect.Field f : platSsCls.getDeclaredFields()) {
                    if (f.getType() == platJsonCls) {
                        f.setAccessible(true);
                        if (f.get(null) == null) {
                            f.set(null, platDummyCfg);
                        }
                    }
                }
                Class<?> platBCls = resolveScriptClassLoaderClass(platEngineLoader, legacyScriptClName);
                ClassLoader platBLoader = (ClassLoader) platBCls.getConstructor(ClassLoader.class).newInstance(platEngineLoader);
                try (java.net.URLClassLoader platModLoader = new java.net.URLClassLoader(
                        new java.net.URL[]{jarFile.toURI().toURL()}, platBLoader) {
                    @Override
                    public InputStream getResourceAsStream(String name) {
                        return null; // Match ScriptStore sandbox returning null on getResourceAsStream
                    }
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                        if (name.startsWith("divisionai.")) {
                            Class<?> c = findLoadedClass(name);
                            if (c == null) c = findClass(name);
                            if (resolve) resolveClass(c);
                            return c;
                        }
                        return super.loadClass(name, resolve);
                    }
                }) {
                    boolean pDeploy = (Boolean) Class.forName("divisionai.DivisionDeploymentDialogOrganizer", true, platModLoader)
                            .getMethod("verifyObfuscatedHandles", ClassLoader.class).invoke(null, platModLoader);
                    boolean pCombat = (Boolean) Class.forName("divisionai.DivisionCommanderAI", true, platModLoader)
                            .getMethod("verifyCombatObfuscatedHandles", ClassLoader.class).invoke(null, platModLoader);
                    boolean pWarroom = (Boolean) Class.forName("divisionai.DivisionTacticalMapOverlay", true, platModLoader)
                            .getMethod("verifyWarroomObfuscatedHandles", ClassLoader.class).invoke(null, platModLoader);
                    boolean pCampaign = (Boolean) Class.forName("divisionai.CampaignFleetTooltipOrganizer", true, platModLoader)
                            .getMethod("verifyCampaignObfuscatedHandles", ClassLoader.class).invoke(null, platModLoader);
                    boolean pFleetTab = (Boolean) Class.forName("divisionai.DivisionFleetTabInjector", true, platModLoader)
                            .getMethod("verifyFleetTabHandles", ClassLoader.class).invoke(null, platModLoader);
                    boolean pAudio = (Boolean) Class.forName("divisionai.DynamicBattleMusicController", true, platModLoader)
                            .getMethod("verifyAudioHandlesForClassLoader", ClassLoader.class).invoke(null, platModLoader);
                    boolean pSettings = (Boolean) Class.forName("divisionai.DivisionAISettings", true, platModLoader)
                            .getMethod("verifySettingsObfuscatedHandles", ClassLoader.class).invoke(null, platModLoader);
                    check(pDeploy && pCombat && pWarroom && pCampaign && pFleetTab && pAudio && pSettings,
                            "Real " + platName + " JARs + ScriptClassLoader sandbox resolve all 7 obfuscated MethodHandle bridges",
                            String.format(Locale.ROOT, "deploy=%b, combat=%b, warroom=%b, campaignTooltip=%b, fleetTab=%b, audio=%b, settings=%b",
                                    pDeploy, pCombat, pWarroom, pCampaign, pFleetTab, pAudio, pSettings));
                }
            }
        }
    }

    /**
     * Scenario 9: Pursuit Battles (INTERCEPTION vs REARGUARD_ACTION):
     *  - Fleet-wide formations and wave-teleportation are disabled in pursuit battles so Left/Center/Right spawns stay in their zones.
     *  - OTC in INTERCEPTION targets escaping enemy divisions (isUnableDivision / retreating) and tasks flank divisions to GIVE_GENERAL_CHASE / WORK_FLANK.
     *  - Rearguard divisions in REARGUARD_ACTION keep their vanilla non-direct RETREAT assignment while maintaining Division Commander C2 and withdrawing North (+Y).
     */
    private static void testPursuitDeploymentAndRearguardC2() {
        System.out.println("\n--- Scenario 9: Pursuit Battles (No Fleet-Wide Formation, L/C/R Zones & Rearguard C2) ---");
        CommanderProfile pCmdr = new CommanderProfile(null, "aggressive");
        CommanderProfile eCmdr = new CommanderProfile(null, "steady");

        BattleGroupState pGroup = new BattleGroupState(0, pCmdr);
        pGroup.missionContext = divisionai.DivisionModel.MissionContextType.INTERCEPTION;
        pGroup.fleetWideFormation = divisionai.PermanentDivisionManager.FleetWideFormation.GRAND_LINE_ABREAST;
        BattleGroupState eGroup = new BattleGroupState(1, eCmdr);
        eGroup.missionContext = divisionai.DivisionModel.MissionContextType.REARGUARD_ACTION;
        eGroup.fleetWideFormation = divisionai.PermanentDivisionManager.FleetWideFormation.GRAND_LINE_ABREAST;

        DivisionState pCenter = new DivisionState("p_c", 0, 1, DivisionArchetype.CRUISER_DIVISION, "Center Line", pCmdr);
        pCenter.pursuitSpawnZone = 0;
        DivisionState pLeft = new DivisionState("p_l", 0, 2, DivisionArchetype.FRIGATE_DIVISION, "Left Flank", pCmdr);
        pLeft.pursuitSpawnZone = -1;
        DivisionState pRight = new DivisionState("p_r", 0, 3, DivisionArchetype.DESTROYER_DIVISION, "Right Flank", pCmdr);
        pRight.pursuitSpawnZone = +1;
        pGroup.divisions.add(pCenter);
        pGroup.divisions.add(pLeft);
        pGroup.divisions.add(pRight);

        DivisionState eEscape = new DivisionState("e_esc", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Escaping Convoy", eCmdr);
        eEscape.isUnableDivision = true;
        eEscape.retreatingFraction = 1.0f;
        eGroup.divisions.add(eEscape);

        SimArena arena = new SimArena("9. Pursuit & Rearguard", "Interception L/C/R vs Rearguard Escape", pGroup, eGroup);
        SimShipState pc1 = arena.addShip(pCenter, "pc1", "HSS Dominator", "dominator", HullSize.CRUISER, -120f, -6800f, 60f, 1100f, 0, 0);
        SimShipState pc2 = arena.addShip(pCenter, "pc2", "HSS Eagle", "eagle", HullSize.CRUISER, 120f, -7400f, 80f, 1100f, 0, 0);
        SimShipState pl1 = arena.addShip(pLeft, "pl1", "HSS Lasher", "lasher", HullSize.FRIGATE, -4600f, 400f, 135f, 700f, 0, 0);
        SimShipState pl2 = arena.addShip(pLeft, "pl2", "HSS Wolf", "wolf", HullSize.FRIGATE, -4900f, 100f, 150f, 700f, 0, 0);
        SimShipState pr1 = arena.addShip(pRight, "pr1", "HSS Hammerhead", "hammerhead", HullSize.DESTROYER, 4600f, 400f, 105f, 900f, 0, 0);
        SimShipState pr2 = arena.addShip(pRight, "pr2", "HSS Sunder", "sunder", HullSize.DESTROYER, 4900f, 100f, 100f, 900f, 0, 0);
        SimShipState ee1 = arena.addShip(eEscape, "ee1", "ISS Falcon", "falcon", HullSize.CRUISER, 0f, 600f, 85f, 1000f, 0, 0);

        pl1.facing = 0f;
        pl2.facing = 0f;
        pr1.facing = 180f;
        pr2.facing = 180f;
        pc1.facing = 90f;
        pc2.facing = 90f;

        pGroup.shipIdToDivision.put(pc1.id, pCenter);
        pGroup.shipIdToDivision.put(pc2.id, pCenter);
        pGroup.shipIdToDivision.put(pl1.id, pLeft);
        pGroup.shipIdToDivision.put(pl2.id, pLeft);
        pGroup.shipIdToDivision.put(pr1.id, pRight);
        pGroup.shipIdToDivision.put(pr2.id, pRight);

        List<com.fs.starfarer.api.combat.ShipAPI> wave = new ArrayList<>();
        wave.add(pc1.proxy);
        wave.add(pc2.proxy);
        wave.add(pl1.proxy);
        wave.add(pl2.proxy);
        wave.add(pr1.proxy);
        wave.add(pr2.proxy);

        boolean pursuitDetected = divisionai.OtcAI.isPursuitBattle(arena.engineProxy, pGroup)
                && !divisionai.OtcAI.isOrchestratedAdvanceActive(arena.engineProxy, pGroup, eGroup);
        divisionai.OtcAI.hijackNewlyDeployedWaveIntoFormation(arena.engineProxy, pGroup, eGroup, wave, 0f);
        boolean flankZonesPreserved = pLeft.pursuitSpawnZone == -1
                && pRight.pursuitSpawnZone == 1
                && pCenter.pursuitSpawnZone == 0
                && pl1.location.x < -2000f && pl2.location.x < -2000f
                && pr1.location.x > 2000f && pr2.location.x > 2000f
                && Math.abs(pl1.facing - 0f) < 5f
                && Math.abs(pr1.facing - 180f) < 5f
                && Math.abs(pc1.facing - 90f) < 5f;

        arena.runSimulation(2.0f);
        boolean otcTargetedEscapingEnemy = (pGroup.primaryThreatEnemyDivision == eEscape);
        boolean rearguardDirNorth = (divisionai.OtcAI.getBattleGroupRetreatDirY(eGroup) > 0.5f)
                && (eEscape.currentIntent != null);

        check(pursuitDetected && flankZonesPreserved && otcTargetedEscapingEnemy && rearguardDirNorth,
                "Pursuit disables fleet-wide formation teleportation, preserves L/C/R flank spawns, targets escaping enemies, and keeps Rearguard C2 North (+Y)",
                String.format(Locale.ROOT, "pursuit=%b, flanksPreserved=%b, primaryTarget=%s, rearguardNorthC2=%b",
                        pursuitDetected, flankZonesPreserved,
                        pGroup.primaryThreatEnemyDivision != null ? pGroup.primaryThreatEnemyDivision.displayName : "null",
                        rearguardDirNorth));

        // 9b. Campaign Pursuit Regression Test (reproduces exact conditions from starsector.log pursuit battle):
        //     - BattleCreationContext has FleetGoal.ESCAPE + ctx.setFightToTheLast(true)
        //     - 2 uncaptured escape objectives at (-3800, -6500) and (+3800, -6500)
        //     - Escaping enemy starts hidden in Fog of War at y = +4500
        //     - 2 player divisions with openingMission = SCOUTING and deployInFleetFormation = false (default)
        //       spawned in CENTER at y = -6268.2f interleaved by hull size (Cruisers -> Destroyers -> Frigates)
        BattleGroupState campPGroup = new BattleGroupState(0, pCmdr);
        campPGroup.deployInFleetFormation = false;
        BattleGroupState campEGroup = new BattleGroupState(1, eCmdr);
        DivisionState divCruiser = new DivisionState("c_div1", 0, 1, DivisionArchetype.CRUISER_DIVISION, "1st Light Cruiser", pCmdr);
        divCruiser.openingMission = DivisionMissionType.SCOUTING;
        DivisionState divDestroyer = new DivisionState("c_div2", 0, 2, DivisionArchetype.DESTROYER_DIVISION, "3rd Destroyer Screen", pCmdr);
        divDestroyer.openingMission = DivisionMissionType.SCOUTING;
        campPGroup.divisions.add(divCruiser);
        campPGroup.divisions.add(divDestroyer);

        DivisionState divFleeing = new DivisionState("c_esc", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Fleeing Remnant", eCmdr);
        divFleeing.retreatingFraction = 1.0f;
        campEGroup.divisions.add(divFleeing);

        SimArena campArena = new SimArena("9b. Campaign Pursuit", "FightToTheLast + Escape Objectives + Interleaved Center Spawn", campPGroup, campEGroup);
        campArena.fogOfWarEnabled = true;
        campArena.baseShipSightRadius = 4000f;
        campArena.mapHeight = 26000f;
        campArena.objectives.add(createMockObjective("Sensor Array", "sensor_array", new Vector2f(-3800f, -6500f), 100));
        campArena.objectives.add(createMockObjective("Nav Buoy", "nav_buoy", new Vector2f(3800f, -6500f), 100));

        // Spawn interleaved in CENTER at y = -6268.2f (matching CombatFleetManager.deploy hull-size sorting)
        SimShipState sHeron = campArena.addShip(divCruiser, "c_heron", "ISS Heron", "heron", HullSize.CRUISER, -1350f, -6268.2f, 80f, 1000f, 0, 2);
        SimShipState sMedusa = campArena.addShip(divDestroyer, "c_medusa", "ISS Medusa", "medusa", HullSize.DESTROYER, -1050f, -6268.2f, 110f, 900f, 0, 0);
        SimShipState sEnforcer = campArena.addShip(divDestroyer, "c_enforcer", "ISS Enforcer", "enforcer", HullSize.DESTROYER, -750f, -6268.2f, 90f, 900f, 0, 0);
        SimShipState sManticore = campArena.addShip(divDestroyer, "c_manticore", "ISS Manticore", "manticore", HullSize.DESTROYER, -450f, -6268.2f, 90f, 950f, 0, 0);
        SimShipState sSunder = campArena.addShip(divDestroyer, "c_sunder", "ISS Sunder", "sunder", HullSize.DESTROYER, -150f, -6268.2f, 100f, 950f, 0, 0);
        SimShipState sShrike = campArena.addShip(divCruiser, "c_shrike", "ISS Shrike", "shrike", HullSize.DESTROYER, 150f, -6268.2f, 115f, 850f, 0, 0);
        SimShipState sOmen = campArena.addShip(divCruiser, "c_omen", "ISS Omen", "omen", HullSize.FRIGATE, 450f, -6268.2f, 130f, 700f, 0, 0);
        SimShipState sWolf1 = campArena.addShip(divCruiser, "c_wolf1", "ISS Wolf 1", "wolf", HullSize.FRIGATE, 750f, -6268.2f, 150f, 700f, 0, 0);
        SimShipState sWolf2 = campArena.addShip(divCruiser, "c_wolf2", "ISS Wolf 2", "wolf", HullSize.FRIGATE, 1050f, -6268.2f, 150f, 700f, 0, 0);
        SimShipState sCent = campArena.addShip(divDestroyer, "c_cent", "ISS Centurion", "centurion", HullSize.FRIGATE, 1350f, -6268.2f, 125f, 700f, 0, 0);
        SimShipState sFlee = campArena.addShip(divFleeing, "c_flee", "Enemy Cruiser", "falcon", HullSize.CRUISER, 0f, 4500f, 90f, 1000f, 0, 0);
        sFlee.facing = 90f;

        List<com.fs.starfarer.api.combat.ShipAPI> campWave = new ArrayList<>();
        for (SimShipState ss : new SimShipState[]{sHeron, sMedusa, sEnforcer, sManticore, sSunder, sShrike, sOmen, sWolf1, sWolf2, sCent}) {
            ss.facing = 90f;
            ss.velocity.set(0f, 550f);
            campWave.add(ss.proxy);
        }
        for (SimShipState ss : new SimShipState[]{sHeron, sShrike, sOmen, sWolf1, sWolf2}) {
            campPGroup.shipIdToDivision.put(ss.id, divCruiser);
        }
        for (SimShipState ss : new SimShipState[]{sMedusa, sEnforcer, sManticore, sSunder, sCent}) {
            campPGroup.shipIdToDivision.put(ss.id, divDestroyer);
        }

        // Verify detectMissionContext prioritizes FleetGoal.ESCAPE over ctx.fightToTheLast
        com.fs.starfarer.api.SettingsAPI prevSettings = null;
        try {
            prevSettings = com.fs.starfarer.api.Global.getSettings();
        } catch (Throwable ignored) {
        }
        if (prevSettings == null) {
            com.fs.starfarer.api.Global.setSettings((com.fs.starfarer.api.SettingsAPI) Proxy.newProxyInstance(
                    com.fs.starfarer.api.SettingsAPI.class.getClassLoader(),
                    new Class<?>[]{com.fs.starfarer.api.SettingsAPI.class},
                    (p, m, a) -> {
                        if (m.getReturnType() == float.class) return 3000f;
                        if (m.getReturnType() == boolean.class) return false;
                        if (m.getReturnType() == int.class) return 0;
                        return null;
                    }));
        }
        com.fs.starfarer.api.combat.BattleCreationContext pursuitCtx;
        try {
            pursuitCtx = new com.fs.starfarer.api.combat.BattleCreationContext(null, com.fs.starfarer.api.fleet.FleetGoal.ATTACK, null, com.fs.starfarer.api.fleet.FleetGoal.ESCAPE);
        } finally {
            com.fs.starfarer.api.Global.setSettings(prevSettings);
        }
        pursuitCtx.fightToTheLast = true;
        final com.fs.starfarer.api.combat.CombatEngineAPI baseEngine = campArena.engineProxy;
        com.fs.starfarer.api.combat.CombatEngineAPI pursuitCtxEngine = (com.fs.starfarer.api.combat.CombatEngineAPI) Proxy.newProxyInstance(
                com.fs.starfarer.api.combat.CombatEngineAPI.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.combat.CombatEngineAPI.class},
                (p, m, a) -> {
                    if ("getContext".equals(m.getName())) return pursuitCtx;
                    if ("getMapHeight".equals(m.getName())) return 26000f;
                    return m.invoke(baseEngine, a);
                });
        campPGroup.missionContext = divisionai.DivisionAutoFormer.detectMissionContext(pursuitCtxEngine, 0);
        campEGroup.missionContext = divisionai.DivisionAutoFormer.detectMissionContext(pursuitCtxEngine, 1);
        boolean missionCtxCorrect = campPGroup.missionContext == divisionai.DivisionModel.MissionContextType.INTERCEPTION
                && campEGroup.missionContext == divisionai.DivisionModel.MissionContextType.REARGUARD_ACTION;

        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(divCruiser);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(divDestroyer);
        divisionai.OtcAI.hijackNewlyDeployedWaveIntoFormation(pursuitCtxEngine, campPGroup, campEGroup, campWave, 0f);

        float div1MinX = Float.MAX_VALUE, div1MaxX = -Float.MAX_VALUE;
        for (SimShipState ss : new SimShipState[]{sHeron, sShrike, sOmen, sWolf1, sWolf2}) {
            div1MinX = Math.min(div1MinX, ss.location.x);
            div1MaxX = Math.max(div1MaxX, ss.location.x);
        }
        float div2MinX = Float.MAX_VALUE, div2MaxX = -Float.MAX_VALUE;
        for (SimShipState ss : new SimShipState[]{sMedusa, sEnforcer, sManticore, sSunder, sCent}) {
            div2MinX = Math.min(div2MinX, ss.location.x);
            div2MaxX = Math.max(div2MaxX, ss.location.x);
        }
        boolean unInterleaved = (div1MaxX < div2MinX) || (div2MaxX < div1MinX);
        boolean spawnYPreserved = Math.abs(divCruiser.centroid.y - (-6268.2f)) < 450f
                && Math.abs(divDestroyer.centroid.y - (-6268.2f)) < 450f;

        campArena.runSimulation(1.0f);

        boolean noScoutingOrRearObjectiveChase = campPGroup.currentPhase == divisionai.DivisionModel.BattlePhase.APPROACH
                && divCruiser.currentIntent != DivisionIntentType.TAKE_AND_HOLD
                && divDestroyer.currentIntent != DivisionIntentType.TAKE_AND_HOLD
                && divCruiser.stationPoint.y > -5000f
                && divDestroyer.stationPoint.y > -5000f;

        check(missionCtxCorrect && unInterleaved && spawnYPreserved && noScoutingOrRearObjectiveChase,
                "Campaign Pursuit (fightToTheLast + escape objectives) classifies INTERCEPTION, un-interleaves Center divisions, and advances North (+Y)",
                String.format(Locale.ROOT, "missionCtx=%s/%s, unInterleaved=%b (div1=[%.0f..%.0f], div2=[%.0f..%.0f]), spawnY=(%.0f, %.0f), phase=%s, stationsY=(%.0f, %.0f)",
                        campPGroup.missionContext, campEGroup.missionContext,
                        unInterleaved, div1MinX, div1MaxX, div2MinX, div2MaxX,
                        divCruiser.centroid.y, divDestroyer.centroid.y,
                        campPGroup.currentPhase, divCruiser.stationPoint.y, divDestroyer.stationPoint.y));
    }

    /**
     * Regression test for Pirate Horde live battle telemetry (Issues A, B, and C):
     *  A. Multi-ship unorganized "Independent Contact" spatial clusters do not hijack Main Effort from organized
     *     enemy divisions outside point-blank range, and incumbent primaryThreatEnemyDivision holds hysteresis.
     *  B. Committed carrier Kill-Box StrikePlans release immediately when the Voidstrike enters egress/culminates
     *     rather than keeping carriers locked on a stale target for a second sortie.
     *  C. Wiped-out enemy Voidstrikes that reached target culminate cleanly after the 1.25s hysteresis window and
     *     release their sortie number so the next deckload increments to Voidstrike 2 instead of re-logging Voidstrike 1.
     */
    private static void testPirateHordeC2AndVoidstrikeLifecycle() {
        System.out.println("\n--- Scenario 10b: Pirate Horde C2 Stability, Carrier StrikePlan Release & Wiped-Out Voidstrike Numbering ---");
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "aggressive");

        // --- Issue A: Organized division beats closer 3-ship Independent Contact cluster + incumbent hysteresis ---
        BattleGroupState pGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroup = new BattleGroupState(1, eCmdr);
        DivisionState battleDiv = new DivisionState("p_bat1", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div 1", pCmdr);
        pGroup.divisions.add(battleDiv);
        pGroup.mainEffortDivision = battleDiv;

        DivisionState warband1 = new DivisionState("e_wb1", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Warband I", eCmdr);
        DivisionState pack1 = new DivisionState("e_pk1", 1, 2, DivisionArchetype.CRUISER_DIVISION, "1st Pack", eCmdr);
        eGroup.divisions.add(warband1);
        eGroup.divisions.add(pack1);

        SimArena arena = new SimArena("Scenario 10b: Pirate Horde C2", "Main Effort Stability, Kill-Box Release & Enemy Voidstrike Numbering", pGroup, eGroup);
        SimShipState pCap = arena.addShip(battleDiv, "ph_pc1", "HSS Conquest", "conquest", HullSize.CAPITAL_SHIP, 0f, 0f, 55f, 1200f, 2, 0);
        battleDiv.flagship = pCap.proxy;
        pGroup.shipIdToDivision.put(pCap.id, battleDiv);

        // Warband I at y=1850u (26 FP total), 1st Pack at y=1920u (26 FP total)
        SimShipState wbCru = arena.addShip(warband1, "ph_wb1", "Vetala", "eradicator_pirates", HullSize.CRUISER, -250f, 1850f, 70f, 1000f, 2, 0);
        SimShipState wbDes = arena.addShip(warband1, "ph_wb2", "Cormac", "enforcer", HullSize.DESTROYER, -150f, 1850f, 75f, 900f, 1, 0);
        warband1.flagship = wbCru.proxy;
        eGroup.shipIdToDivision.put(wbCru.id, warband1);
        eGroup.shipIdToDivision.put(wbDes.id, warband1);

        SimShipState pkCru = arena.addShip(pack1, "ph_pk1", "Thatcher", "eradicator_pirates", HullSize.CRUISER, 250f, 1920f, 70f, 1000f, 2, 0);
        SimShipState pkDes = arena.addShip(pack1, "ph_pk2", "Tyrfing", "enforcer", HullSize.DESTROYER, 350f, 1920f, 75f, 900f, 1, 0);
        pack1.flagship = pkCru.proxy;
        eGroup.shipIdToDivision.put(pkCru.id, pack1);
        eGroup.shipIdToDivision.put(pkDes.id, pack1);

        // 3 ungrouped Pirate frigates/destroyers closer at y=1350u (~22 FP) -> forms "Independent Contact 1" in arena.runSimulation
        SimShipState ind1 = new SimShipState("ph_ind1", "Stray Shrike", "shrike", 1, HullSize.DESTROYER, 0f, 1350f, 95f, 800f, 1, 0);
        SimShipState ind2 = new SimShipState("ph_ind2", "Stray Hound 1", "hound", 1, HullSize.FRIGATE, 80f, 1360f, 120f, 600f, 1, 0);
        SimShipState ind3 = new SimShipState("ph_ind3", "Stray Hound 2", "hound", 1, HullSize.FRIGATE, -80f, 1360f, 120f, 600f, 1, 0);
        arena.ships.add(ind1);
        arena.ships.add(ind2);
        arena.ships.add(ind3);

        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(battleDiv);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(warband1);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(pack1);
        arena.runSimulation(1.0f);
        boolean organizedBeatsIndepClusterOk = (pGroup.primaryThreatEnemyDivision == warband1);

        // Move 1st Pack slightly closer than Warband I (y=1760 vs y=1850) -> incumbent hysteresis keeps Warband I!
        pkCru.location.set(250f, 1760f);
        pkDes.location.set(350f, 1760f);
        arena.runSimulation(1.6f);
        boolean incumbentHysteresisHeldOk = (pGroup.primaryThreatEnemyDivision == warband1);

        // --- Issue B: Committed carrier StrikePlan releases immediately on Voidstrike egress ---
        DivisionState cDiv1 = new DivisionState("p_car1", 0, 2, DivisionArchetype.LIGHT_CARRIER_DIVISION, "Carrier Div 1", pCmdr);
        cDiv1.hasCarrierCapability = true;
        cDiv1.openingMission = DivisionMissionType.ALPHA_STRIKE;
        cDiv1.mainMission = DivisionMissionType.ALPHA_STRIKE;
        cDiv1.lastVoidstrikeApproachTimeoutSec = 45f;
        cDiv1.lastVoidstrikePassTimeoutSec = 48f;
        cDiv1.voidstrikeStage = 2;
        cDiv1.activeVoidstrikeSortieNumber = 2;
        SimShipState cShip = arena.addShip(cDiv1, "ph_car1", "HSS Heron", "heron", HullSize.CRUISER, 0f, -600f, 75f, 1000f, 0, 3);
        cDiv1.flagship = cShip.proxy;
        pGroup.divisions.add(cDiv1);
        pGroup.shipIdToDivision.put(cShip.id, cDiv1);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(cDiv1);
        cDiv1.hasCarrierCapability = true;

        divisionai.DivisionModel.StrikePlan sp = new divisionai.DivisionModel.StrikePlan();
        sp.target = warband1;
        sp.setpieceType = divisionai.PermanentDivisionManager.MultiDivisionSetpiece.CONVERGENCE_KILL_BOX;
        sp.participants.add(cDiv1);
        sp.createdTime = 50f;
        sp.lastTargetVisibleTime = 60f;
        sp.strikeTime = 58f;
        sp.committed = true;
        sp.carrierSortieCommitted = true;
        sp.commitTime = 58f;
        pGroup.strikePlan = sp;

        // While airborne (t=62s, only 4s into 93s package window), StrikePlan stays active
        divisionai.OtcAI.updateTelemetry(arena.engineProxy, pGroup);
        divisionai.OtcBattlePlanner.planBattle(arena.engineProxy, pGroup, eGroup, null, null, 62f);
        boolean activeDuringPassOk = (pGroup.strikePlan == sp)
                && (cDiv1.otcOrder.type == divisionai.DivisionModel.OtcOrderType.EXECUTE_STRIKE);

        // Once Voidstrike culminates and enters egress at t=70s (still well inside 93s window), StrikePlan terminates!
        cDiv1.voidstrikeEgressActive = true;
        cDiv1.activeVoidstrikeSortieNumber = 0;
        divisionai.OtcBattlePlanner.planBattle(arena.engineProxy, pGroup, eGroup, null, null, 70f);
        boolean releasedOnEgressOk = (pGroup.strikePlan == null)
                && (cDiv1.otcOrder.type != divisionai.DivisionModel.OtcOrderType.EXECUTE_STRIKE);

        // --- Issue C: Wiped-out enemy Voidstrike that reachedTarget releases number & increments next deckload ---
        divisionai.VoidstrikeCoordinator.resetForNewBattle();
        DivisionState enemyCarrierDiv = new DivisionState("e_cpack", 1, 3, DivisionArchetype.LIGHT_CARRIER_DIVISION, "1st Pack", eCmdr);
        enemyCarrierDiv.currentIntent = DivisionIntentType.LAUNCH_DECKLOAD_STRIKE;
        enemyCarrierDiv.targetEnemyDivision = battleDiv;
        BattleGroupState cleanEnemyGroup = new BattleGroupState(1, eCmdr);
        cleanEnemyGroup.divisions.add(enemyCarrierDiv);
        SimShipState eCarrier = arena.addShip(enemyCarrierDiv, "ph_ec1", "Colossus Mk.III", "colossus3", HullSize.CRUISER, 0f, 1600f, 50f, 900f, 0, 2);
        enemyCarrierDiv.flagship = eCarrier.proxy;
        cleanEnemyGroup.shipIdToDivision.put(eCarrier.id, enemyCarrierDiv);

        boolean[] eRet = new boolean[]{false};
        List<SimShipState> eBombers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            SimShipState eb = arena.addShip(enemyCarrierDiv, "ph_eb_" + i, "Piranha " + i, "piranha", HullSize.FIGHTER, -15f + i * 30f, 200f, 150f, 450f, 2, 0);
            eBombers.add(eb);
        }
        createRehearsalWing(eCarrier, eBombers, true, eRet);

        // Wave 1 arrives at Battle Div 1 (y=200 -> dist=200u) at t=10s -> Enemy Voidstrike 1, reachedTarget = true
        divisionai.VoidstrikeCoordinator.updateVoidstrikes(arena.engineProxy, pGroup, null, cleanEnemyGroup, 10f);
        List<divisionai.VoidstrikeCoordinator.VoidstrikeRecord> eVsWave1 = divisionai.VoidstrikeCoordinator.getActiveVoidstrikes();
        boolean wave1IsOneAndReachedOk = (eVsWave1.size() == 1)
                && eVsWave1.get(0).voidstrikeNumber == 1
                && eVsWave1.get(0).reachedTarget;

        // Wave 1 is completely shot down by AA (alive=false, NOT returning!) at t=12s, expires > 1.25s later at t=14s
        for (SimShipState eb : eBombers) eb.alive = false;
        divisionai.VoidstrikeCoordinator.updateVoidstrikes(arena.engineProxy, pGroup, null, cleanEnemyGroup, 12f);
        divisionai.VoidstrikeCoordinator.updateVoidstrikes(arena.engineProxy, pGroup, null, cleanEnemyGroup, 14f);
        boolean sortieNumReleasedAfterWipeoutOk = (enemyCarrierDiv.activeVoidstrikeSortieNumber == 0);

        // Replacement deckload launches against Cruiser division at t=25s -> must claim Enemy Voidstrike 2 (never reuse 1!)
        DivisionState pCruDiv = new DivisionState("p_cru1", 0, 3, DivisionArchetype.CRUISER_DIVISION, "Line Cruiser Div 1", pCmdr);
        SimShipState pCru = arena.addShip(pCruDiv, "ph_pcru1", "HSS Champion", "champion", HullSize.CRUISER, -900f, -200f, 65f, 1000f, 2, 0);
        pCruDiv.flagship = pCru.proxy;
        pGroup.divisions.add(pCruDiv);
        pGroup.shipIdToDivision.put(pCru.id, pCruDiv);
        enemyCarrierDiv.targetEnemyDivision = pCruDiv;
        for (int i = 0; i < 2; i++) {
            eBombers.get(i).alive = true;
            eBombers.get(i).location.set(-900f + i * 25f, 300f);
            eBombers.get(i).shipTarget = pCru.proxy;
        }
        divisionai.VoidstrikeCoordinator.updateVoidstrikes(arena.engineProxy, pGroup, null, cleanEnemyGroup, 25f);
        List<divisionai.VoidstrikeCoordinator.VoidstrikeRecord> eVsWave2 = divisionai.VoidstrikeCoordinator.getActiveVoidstrikes();
        boolean wave2IncrementedToTwoOk = (eVsWave2.size() == 1) && eVsWave2.get(0).voidstrikeNumber == 2;

        check(organizedBeatsIndepClusterOk
                        && incumbentHysteresisHeldOk
                        && activeDuringPassOk
                        && releasedOnEgressOk
                        && wave1IsOneAndReachedOk
                        && sortieNumReleasedAfterWipeoutOk
                        && wave2IncrementedToTwoOk,
                "Pirate Horde C2 Fixes: Organized division beats Independent Contact cluster, incumbent hysteresis holds, Kill-Box StrikePlan releases on egress, and wiped-out Voidstrike increments sortie number",
                String.format(Locale.ROOT, "orgBeatsIndep=%b, hysteresis=%b, activePass=%b, releasedOnEgress=%b, wave1Reached=%b, wipedReleased=%b, wave2IsTwo=%b",
                        organizedBeatsIndepClusterOk, incumbentHysteresisHeldOk, activeDuringPassOk,
                        releasedOnEgressOk, wave1IsOneAndReachedOk, sortieNumReleasedAfterWipeoutOk, wave2IncrementedToTwoOk));
    }

    /**
     * Scenario 10c: Strike-craft & Multi-Carrier Voidstrike C2 Fixes:
     *  1. Regenerating-ammo / PD escort mechs & interceptors (ammoPerSec > 0 or PD/ANTI_FTR hints) are NOT classified
     *     as finite-ammo bombers, allowing Voidstrikes to culminate immediately on "ordnance expended" once real bombers
     *     drop their torpedoes instead of lingering in flak for 60s until "strike pass complete".
     *  2. Long-range standoff torpedo/MIRV/DEM bombers (attackRunRange >= 1100u, weaponRange = 1200u) scale
     *     computeMaxStrikeWeaponRange to 1200u and Pincer/Crossfire flanking gate distance to 960u (vs 620u for short-range).
     *  3. Carrier divisions with armed strike craft committed downrange (>= 460u from carriers) hold target lock on
     *     their active multi-ship target division across Main Effort shifts so Pincer dogleg gates never reset mid-run.
     *  4. OtcBattlePlanner does not split a 2-division ALPHA_STRIKE carrier air wing in half by drafting a lone
     *     carrier division into MARSHAL while its sister ALPHA_STRIKE carrier division is recovering or airborne.
     */
    private static void testBattle2StrikecraftAndMultiCarrierFixes() {
        System.out.println("\n--- Scenario 10c: Regenerating Escort Ammo Exclusion, Standoff Bomber Gate Scaling, Target Lock & Unified Multi-Carrier Alpha ---");
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "aggressive");

        BattleGroupState pGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroup = new BattleGroupState(1, eCmdr);

        DivisionState battleDiv = new DivisionState("b2_bat1", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div 1", pCmdr);
        DivisionState carDiv1 = new DivisionState("b2_car1", 0, 2, DivisionArchetype.LIGHT_CARRIER_DIVISION, "Carrier Div 1", pCmdr);
        DivisionState carDiv2 = new DivisionState("b2_car2", 0, 3, DivisionArchetype.LIGHT_CARRIER_DIVISION, "Carrier Div 2", pCmdr);
        carDiv1.hasCarrierCapability = true;
        carDiv2.hasCarrierCapability = true;
        carDiv1.openingMission = DivisionMissionType.ALPHA_STRIKE;
        carDiv1.mainMission = DivisionMissionType.ALPHA_STRIKE;
        carDiv2.openingMission = DivisionMissionType.ALPHA_STRIKE;
        carDiv2.mainMission = DivisionMissionType.ALPHA_STRIKE;
        carDiv1.voidstrikeAttackGeometry = divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.PINCER_ENVELOPMENT;
        carDiv2.voidstrikeAttackGeometry = divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry.PINCER_ENVELOPMENT;
        pGroup.divisions.add(battleDiv);
        pGroup.divisions.add(carDiv1);
        pGroup.divisions.add(carDiv2);
        pGroup.mainEffortDivision = battleDiv;

        DivisionState enemyDivA = new DivisionState("b2_ea", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Independent Contact 1", eCmdr);
        DivisionState enemyDivB = new DivisionState("b2_eb", 1, 2, DivisionArchetype.CRUISER_DIVISION, "Warband I", eCmdr);
        eGroup.divisions.add(enemyDivA);
        eGroup.divisions.add(enemyDivB);

        SimArena arena = new SimArena("Scenario 10c: Strike-Craft & Multi-Carrier C2", "Regen Ammo Filter, 1200u Bomber Gate, Target Lock & Unified Alpha", pGroup, eGroup);
        SimShipState pCap = arena.addShip(battleDiv, "b2_pcap", "HSS Onslaught", "onslaught", HullSize.CAPITAL_SHIP, 0f, 0f, 50f, 1200f, 2, 0);
        SimShipState c1 = arena.addShip(carDiv1, "b2_c1", "HSS Heron", "heron", HullSize.CRUISER, -250f, -900f, 75f, 1000f, 0, 2);
        SimShipState c2 = arena.addShip(carDiv2, "b2_c2", "HSS Chester", "condor", HullSize.DESTROYER, 250f, -450f, 70f, 1000f, 0, 2);
        battleDiv.flagship = pCap.proxy;
        carDiv1.flagship = c1.proxy;
        carDiv2.flagship = c2.proxy;
        pGroup.shipIdToDivision.put(pCap.id, battleDiv);
        pGroup.shipIdToDivision.put(c1.id, carDiv1);
        pGroup.shipIdToDivision.put(c2.id, carDiv2);

        SimShipState ea1 = arena.addShip(enemyDivA, "b2_ea1", "Raider Cruiser", "eradicator_pirates", HullSize.CRUISER, -200f, 1250f, 70f, 1000f, 2, 0);
        SimShipState ea2 = arena.addShip(enemyDivA, "b2_ea2", "Raider Escort", "enforcer", HullSize.DESTROYER, -100f, 1280f, 75f, 900f, 1, 0);
        enemyDivA.flagship = ea1.proxy;
        eGroup.shipIdToDivision.put(ea1.id, enemyDivA);
        eGroup.shipIdToDivision.put(ea2.id, enemyDivA);

        SimShipState eb1 = arena.addShip(enemyDivB, "b2_eb1", "Warband Cruiser", "dominator", HullSize.CRUISER, 350f, 1050f, 55f, 1000f, 2, 0);
        SimShipState eb2 = arena.addShip(enemyDivB, "b2_eb2", "Warband Escort", "manticore", HullSize.DESTROYER, 450f, 1080f, 70f, 1000f, 1, 0);
        enemyDivB.flagship = eb1.proxy;
        eGroup.shipIdToDivision.put(eb1.id, enemyDivB);
        eGroup.shipIdToDivision.put(eb2.id, enemyDivB);

        // 1. Build a regenerating-ammo + PD gun mech wing (like MSS_Aznable: 7-ammo @ 2.3/s beam + 100-ammo PD chaingun)
        //    and a long-range 1200u standoff torpedo bomber wing (attackRunRange = 1500u, ammo = 1 @ 0/s).
        final int[] bomberTorpAmmo = new int[]{1};
        com.fs.starfarer.api.combat.WeaponAPI regenBeam = (com.fs.starfarer.api.combat.WeaponAPI) java.lang.reflect.Proxy.newProxyInstance(
                SimArena.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.combat.WeaponAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("usesAmmo".equals(n)) return true;
                    if ("getAmmo".equals(n)) return 7;
                    if ("getMaxAmmo".equals(n)) return 7;
                    if ("getAmmoPerSecond".equals(n)) return 2.3f;
                    if ("getRange".equals(n)) return 600f;
                    if ("getType".equals(n)) return com.fs.starfarer.api.combat.WeaponAPI.WeaponType.ENERGY;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
        final com.fs.starfarer.api.loading.WeaponSpecAPI pdSpec = (com.fs.starfarer.api.loading.WeaponSpecAPI) java.lang.reflect.Proxy.newProxyInstance(
                SimArena.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.loading.WeaponSpecAPI.class},
                (p, m, a) -> {
                    if ("getAIHints".equals(m.getName())) {
                        return java.util.EnumSet.of(com.fs.starfarer.api.combat.WeaponAPI.AIHints.PD);
                    }
                    if ("getAmmoPerSecond".equals(m.getName())) return 0f;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
        com.fs.starfarer.api.combat.WeaponAPI pdChaingun = (com.fs.starfarer.api.combat.WeaponAPI) java.lang.reflect.Proxy.newProxyInstance(
                SimArena.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.combat.WeaponAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("usesAmmo".equals(n)) return true;
                    if ("getAmmo".equals(n)) return 100;
                    if ("getMaxAmmo".equals(n)) return 100;
                    if ("getAmmoPerSecond".equals(n)) return 0f;
                    if ("getRange".equals(n)) return 450f;
                    if ("getType".equals(n)) return com.fs.starfarer.api.combat.WeaponAPI.WeaponType.BALLISTIC;
                    if ("getSpec".equals(n)) return pdSpec;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
        com.fs.starfarer.api.combat.WeaponAPI standoffTorp = (com.fs.starfarer.api.combat.WeaponAPI) java.lang.reflect.Proxy.newProxyInstance(
                SimArena.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.combat.WeaponAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("usesAmmo".equals(n)) return true;
                    if ("getAmmo".equals(n)) return bomberTorpAmmo[0];
                    if ("getMaxAmmo".equals(n)) return 1;
                    if ("getAmmoPerSecond".equals(n)) return 0f;
                    if ("getRange".equals(n)) return 1200f;
                    if ("getType".equals(n)) return com.fs.starfarer.api.combat.WeaponAPI.WeaponType.MISSILE;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });

        SimShipState escortMech = arena.addShip(carDiv1, "b2_mech", "Mech Escort", "broadsword", HullSize.FIGHTER, -180f, 820f, 200f, 600f, 0, 0);
        escortMech.weapons.clear();
        escortMech.weapons.add(regenBeam);
        escortMech.weapons.add(pdChaingun);

        SimShipState lrBomber = arena.addShip(carDiv2, "b2_bomber", "EIS Dagger", "dagger", HullSize.FIGHTER, -160f, 840f, 160f, 1200f, 0, 0);
        lrBomber.weapons.clear();
        lrBomber.weapons.add(standoffTorp);

        // Attach wings: escortMech has WingRole.FIGHTER, lrBomber has WingRole.BOMBER + attackRunRange = 1500f
        boolean[] mechRet = new boolean[]{false};
        boolean[] bomberRet = new boolean[]{false};
        createRehearsalWing(c1, java.util.Collections.singletonList(escortMech), false, mechRet);
        final com.fs.starfarer.api.loading.FighterWingSpecAPI lrSpec = (com.fs.starfarer.api.loading.FighterWingSpecAPI) java.lang.reflect.Proxy.newProxyInstance(
                SimArena.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.loading.FighterWingSpecAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("isBomber".equals(n)) return true;
                    if ("getRole".equals(n)) return com.fs.starfarer.api.loading.WingRole.BOMBER;
                    if ("getAttackRunRange".equals(n)) return 600f; // Matches wing_data.csv for Dagger / Trident / EIS Dagger / JDP Trident!
                    if ("getNumFighters".equals(n)) return 1;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
        com.fs.starfarer.api.combat.FighterWingAPI lrWing = (com.fs.starfarer.api.combat.FighterWingAPI) java.lang.reflect.Proxy.newProxyInstance(
                SimArena.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.combat.FighterWingAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("equals".equals(n)) return p == a[0];
                    if ("hashCode".equals(n)) return System.identityHashCode(p);
                    if ("getWingMembers".equals(n)) return java.util.Collections.singletonList(lrBomber.proxy);
                    if ("getLeader".equals(n)) return lrBomber.proxy;
                    if ("getSourceShip".equals(n)) return c2.proxy;
                    if ("getRole".equals(n)) return com.fs.starfarer.api.loading.WingRole.BOMBER;
                    if ("getSpec".equals(n)) return lrSpec;
                    if ("isReturning".equals(n)) return bomberRet[0];
                    if ("orderReturn".equals(n)) {
                        bomberRet[0] = true;
                        return null;
                    }
                    if ("stopReturning".equals(n)) {
                        bomberRet[0] = false;
                        return null;
                    }
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
        lrBomber.wing = lrWing;
        c2.wings.add(lrWing);

        boolean regenMechExcludedFromFiniteBombersOk = !divisionai.VoidstrikeCoordinator.hasFiniteAmmoStrikeWeapons(escortMech.proxy)
                && divisionai.VoidstrikeCoordinator.hasFiniteAmmoStrikeWeapons(lrBomber.proxy);
        float lrMaxRange = divisionai.VoidstrikeCoordinator.computeMaxStrikeWeaponRange(lrBomber.proxy);
        float lrGateDist = divisionai.VoidstrikeCoordinator.computeDoctrinalFlankingGateBaseDist(lrWing, lrMaxRange);
        float stdGateDist = divisionai.VoidstrikeCoordinator.computeDoctrinalFlankingGateBaseDist(escortMech.wing, 600f);
        boolean standoffGateScaledOk = Math.abs(lrMaxRange - 1200f) < 1f
                && Math.abs(lrGateDist - 960f) < 1f
                && Math.abs(stdGateDist - 620f) < 1f;

        // 2. Verify Voidstrike culminates on "ordnance expended" once lrBomber drops its torpedo even while escortMech
        //    still holds 7/7 regenerating beam charges & 100/100 PD ammo!
        divisionai.VoidstrikeCoordinator.resetForNewBattle();
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(battleDiv);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(carDiv1);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(carDiv2);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(enemyDivA);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(enemyDivB);
        carDiv1.hasCarrierCapability = true;
        carDiv2.hasCarrierCapability = true;
        carDiv1.openingMission = DivisionMissionType.ALPHA_STRIKE;
        carDiv1.mainMission = DivisionMissionType.ALPHA_STRIKE;
        carDiv2.openingMission = DivisionMissionType.ALPHA_STRIKE;
        carDiv2.mainMission = DivisionMissionType.ALPHA_STRIKE;
        carDiv1.currentIntent = DivisionIntentType.LAUNCH_DECKLOAD_STRIKE;
        carDiv2.currentIntent = DivisionIntentType.LAUNCH_DECKLOAD_STRIKE;
        carDiv1.targetEnemyDivision = enemyDivA;
        carDiv2.targetEnemyDivision = enemyDivA;
        pGroup.primaryThreatEnemyDivision = enemyDivA;

        divisionai.OtcAI.updateTelemetry(arena.engineProxy, pGroup);
        divisionai.OtcAI.updateTelemetry(arena.engineProxy, eGroup);
        divisionai.VoidstrikeCoordinator.updateVoidstrikes(arena.engineProxy, pGroup, null, eGroup, 10.0f);
        List<divisionai.VoidstrikeCoordinator.VoidstrikeRecord> activeVs = divisionai.VoidstrikeCoordinator.getActiveVoidstrikes();
        boolean mergedTwoDivWaveOk = (activeVs.size() == 1) && activeVs.get(0).participatingDivisionIds.size() == 2;

        // Shift Main Effort to closer enemyDivB while lrBomber is committed downrange (1290u from c2!) -> carDiv2 must HOLD target lock on enemyDivA!
        pGroup.primaryThreatEnemyDivision = enemyDivB;
        divisionai.DivisionCommanderAI.evaluateAndExecuteDivisions(arena.engineProxy, pGroup, eGroup, 11.0f);
        boolean downrangeTargetLockHeldOk = (carDiv2.targetEnemyDivision == enemyDivA);

        // Expend bomber torpedo at t=14.5s (>3.0s sortie age): escortMech still has 7/7 regen beam & 100 PD rounds -> must culminate "ordnance expended"!
        bomberTorpAmmo[0] = 0;
        bomberRet[0] = true;
        divisionai.VoidstrikeCoordinator.updateVoidstrikes(arena.engineProxy, pGroup, null, eGroup, 14.5f);
        boolean culminatedOrdnanceExpendedOk = divisionai.VoidstrikeCoordinator.getActiveVoidstrikes().isEmpty()
                && carDiv1.voidstrikeEgressActive
                && carDiv2.voidstrikeEgressActive;

        // 3. Multi-carrier Kill-Box unity: if carDiv2 finishes egress first while carDiv1 is still in voidstrikeEgressActive,
        //    OtcBattlePlanner must NOT split the 2-division Alpha Strike air wing by locking carDiv2 into a solo Kill-Box!
        carDiv2.voidstrikeEgressActive = false;
        carDiv2.activeVoidstrikeSortieNumber = 0;
        carDiv2.voidstrikeStage = 0;
        carDiv1.voidstrikeEgressActive = true;
        pGroup.strikePlan = null;
        divisionai.OtcBattlePlanner.planBattle(arena.engineProxy, pGroup, eGroup, null, null, 22.0f);
        boolean noSplitSoloCarrierKillBoxOk = (pGroup.strikePlan == null)
                || !pGroup.strikePlan.participants.contains(carDiv2);

        // 4. Verify Precision gunnery stays pinpoint (36u) for < 8 squadrons and widens anti-flak dispersion (> 90u) for >= 16 merged squadrons
        divisionai.VoidstrikeCoordinator.FiringSectorAssignment smallPrec =
                divisionai.VoidstrikeCoordinator.computeHierarchicalFiringSector(
                        divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.PRECISION,
                        divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG,
                        enemyDivA.flagship, lrBomber.proxy, 0, 1, 0, 4, 0, 3, 0f, 1f, 1f, 0f);
        divisionai.VoidstrikeCoordinator.FiringSectorAssignment megaPrec =
                divisionai.VoidstrikeCoordinator.computeHierarchicalFiringSector(
                        divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.PRECISION,
                        divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG,
                        enemyDivA.flagship, lrBomber.proxy, 0, 2, 0, 10, 0, 3, 0f, 1f, 1f, 0f);
        boolean adaptivePrecisionSpanOk = smallPrec.voidstrikeSpanWidth >= 30f
                && smallPrec.voidstrikeSpanWidth <= 36.1f
                && megaPrec.voidstrikeSpanWidth >= 100f
                && megaPrec.voidstrikeSpanWidth <= 165f;

        // 5. Battle 3 Regression Checks:
        //    (a) Post-egress ALPHA_STRIKE carrier convergence: when carDiv2 is between sorties (!airborneWaveActive,
        //        fighters recovered near deck in Stage 0), it must re-align from enemyDivA onto
        //        pGroup.primaryThreatEnemyDivision (enemyDivB) instead of staying stuck on enemyDivA!
        escortMech.location.set(c1.location.x, c1.location.y + 120f);
        lrBomber.location.set(c2.location.x, c2.location.y + 120f);
        carDiv1.voidstrikeEgressActive = false;
        carDiv2.voidstrikeEgressActive = false;
        carDiv1.activeVoidstrikeSortieNumber = 0;
        carDiv2.activeVoidstrikeSortieNumber = 0;
        carDiv1.voidstrikeStage = 0;
        carDiv2.voidstrikeStage = 0;
        carDiv2.targetEnemyDivision = enemyDivA;
        pGroup.primaryThreatEnemyDivision = enemyDivB;
        divisionai.OtcBattlePlanner.planBattle(arena.engineProxy, pGroup, eGroup, null, null, 65.0f);
        divisionai.DivisionCommanderAI.evaluateAndExecuteDivisions(arena.engineProxy, pGroup, eGroup, 65.0f);
        boolean postEgressAlphaCarrierAlignedOk = (carDiv1.targetEnemyDivision == enemyDivB)
                && (carDiv2.targetEnemyDivision == enemyDivB);

        //    (b) OTC "Doubling" (OtcOrderType.FLANK) on primaryThreatEnemyDivision must NOT trip overkillPrimaryPenalty
        //        and bounce Main Effort off its own target!
        DivisionState flankFrigDiv = new DivisionState("p_flank_frig", 0, 4, DivisionArchetype.FRIGATE_DIVISION, "Frigate Div 1", pCmdr);
        SimShipState ff1 = arena.addShip(flankFrigDiv, "ff1", "RDS Centurion 1", "centurion", HullSize.FRIGATE, -400f, -200f, 150f, 600f, 18, 0);
        SimShipState ff2 = arena.addShip(flankFrigDiv, "ff2", "RDS Centurion 2", "centurion", HullSize.FRIGATE, -350f, -200f, 150f, 600f, 18, 0);
        SimShipState ff3 = arena.addShip(flankFrigDiv, "ff3", "RDS Centurion 3", "centurion", HullSize.FRIGATE, -300f, -200f, 150f, 600f, 18, 0);
        flankFrigDiv.flagship = ff1.proxy;
        pGroup.shipIdToDivision.put(ff1.id, flankFrigDiv);
        pGroup.shipIdToDivision.put(ff2.id, flankFrigDiv);
        pGroup.shipIdToDivision.put(ff3.id, flankFrigDiv);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(flankFrigDiv);
        pGroup.divisions.add(flankFrigDiv);
        pGroup.mainEffortDivision = battleDiv;
        pGroup.primaryThreatEnemyDivision = enemyDivB;
        flankFrigDiv.otcOrder.set(divisionai.DivisionModel.OtcOrderType.FLANK, enemyDivB, null, 1f, 999f, "Doubling");
        flankFrigDiv.targetEnemyDivision = enemyDivB;
        arena.runSimulation(0.5f);
        boolean doublingDoesNotBounceMainEffortOk = (pGroup.primaryThreatEnemyDivision == enemyDivB);

        //    (c) Staggered replacement bomber conveyor check: even if a fresh replacement bomber launches on the carrier
        //        deck >5s after target arrival while original Wave-1 bombers expend ordnance, initialWaveBomberCraftIds
        //        culminates Wave 1 cleanly on "ordnance expended" after the 12s escort window!
        divisionai.VoidstrikeCoordinator.resetForNewBattle();
        final int[] origTorpAmmo = new int[]{1};
        final int[] replTorpAmmo = new int[]{1};
        com.fs.starfarer.api.combat.WeaponAPI origTorp = (com.fs.starfarer.api.combat.WeaponAPI) java.lang.reflect.Proxy.newProxyInstance(
                SimArena.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.combat.WeaponAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("usesAmmo".equals(n)) return true;
                    if ("getAmmo".equals(n)) return origTorpAmmo[0];
                    if ("getMaxAmmo".equals(n)) return 1;
                    if ("getAmmoPerSecond".equals(n)) return 0f;
                    if ("getRange".equals(n)) return 1200f;
                    if ("getType".equals(n)) return com.fs.starfarer.api.combat.WeaponAPI.WeaponType.MISSILE;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
        com.fs.starfarer.api.combat.WeaponAPI replTorp = (com.fs.starfarer.api.combat.WeaponAPI) java.lang.reflect.Proxy.newProxyInstance(
                SimArena.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.combat.WeaponAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("usesAmmo".equals(n)) return true;
                    if ("getAmmo".equals(n)) return replTorpAmmo[0];
                    if ("getMaxAmmo".equals(n)) return 1;
                    if ("getAmmoPerSecond".equals(n)) return 0f;
                    if ("getRange".equals(n)) return 1200f;
                    if ("getType".equals(n)) return com.fs.starfarer.api.combat.WeaponAPI.WeaponType.MISSILE;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
        lrBomber.weapons.clear();
        lrBomber.weapons.add(origTorp);
        bomberRet[0] = false;
        final List<com.fs.starfarer.api.combat.ShipAPI> dynamicWingMembers = new java.util.ArrayList<>();
        dynamicWingMembers.add(lrBomber.proxy);
        com.fs.starfarer.api.combat.FighterWingAPI conveyorWing = (com.fs.starfarer.api.combat.FighterWingAPI) java.lang.reflect.Proxy.newProxyInstance(
                SimArena.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.combat.FighterWingAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("equals".equals(n)) return p == a[0];
                    if ("hashCode".equals(n)) return System.identityHashCode(p);
                    if ("getWingMembers".equals(n)) return dynamicWingMembers;
                    if ("getLeader".equals(n)) return dynamicWingMembers.get(0);
                    if ("getSourceShip".equals(n)) return c2.proxy;
                    if ("getRole".equals(n)) return com.fs.starfarer.api.loading.WingRole.BOMBER;
                    if ("getSpec".equals(n)) return lrSpec;
                    if ("isReturning".equals(n)) return a[0] == lrBomber.proxy && bomberRet[0];
                    if ("orderReturn".equals(n)) {
                        if (a[0] == lrBomber.proxy) bomberRet[0] = true;
                        return null;
                    }
                    if ("stopReturning".equals(n)) {
                        if (a[0] == lrBomber.proxy) bomberRet[0] = false;
                        return null;
                    }
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
        lrBomber.wing = conveyorWing;
        c2.wings.clear();
        c2.wings.add(conveyorWing);
        carDiv1.culminatedVoidstrikeWingKeys.clear();
        carDiv2.culminatedVoidstrikeWingKeys.clear();
        carDiv1.voidstrikeEgressActive = false;
        carDiv2.voidstrikeEgressActive = false;
        carDiv1.voidstrikeStage = 2;
        carDiv2.voidstrikeStage = 2;
        carDiv1.currentIntent = DivisionIntentType.LAUNCH_ALPHA_STRIKE;
        carDiv2.currentIntent = DivisionIntentType.LAUNCH_ALPHA_STRIKE;
        carDiv1.targetEnemyDivision = enemyDivA;
        carDiv2.targetEnemyDivision = enemyDivA;
        lrBomber.location.set(enemyDivA.flagship.getLocation().x, enemyDivA.flagship.getLocation().y - 500f);
        escortMech.location.set(enemyDivA.flagship.getLocation().x + 40f, enemyDivA.flagship.getLocation().y - 480f);
        divisionai.VoidstrikeCoordinator.updateVoidstrikes(arena.engineProxy, pGroup, null, eGroup, 40.0f);
        // At t=46.0s (> 4.0s after arrival, so Wave-1 craft set is frozen!), original bomber expends its torpedo while
        // a staggered replacement bomber launches back near the carrier deck (c2) with full ammo (replTorpAmmo=1)!
        SimShipState replBomber = arena.addShip(carDiv2, "b3_repl_bomber", "Replacement Dagger", "dagger", HullSize.FIGHTER, c2.location.x, c2.location.y + 400f, 160f, 1200f, 0, 0);
        replBomber.weapons.clear();
        replBomber.weapons.add(replTorp);
        replBomber.wing = conveyorWing;
        origTorpAmmo[0] = 0;
        bomberRet[0] = true;
        dynamicWingMembers.add(replBomber.proxy);
        divisionai.VoidstrikeCoordinator.updateVoidstrikes(arena.engineProxy, pGroup, null, eGroup, 46.0f);
        divisionai.VoidstrikeCoordinator.updateVoidstrikes(arena.engineProxy, pGroup, null, eGroup, 53.0f);
        boolean conveyorWaveCulminatedOk = divisionai.VoidstrikeCoordinator.getActiveVoidstrikes().isEmpty()
                && carDiv2.voidstrikeEgressActive;

        //    (d) Friendly-Fire Prevention & Clear-Lane Sidestep (Battle 5 regression):
        //        1) Stationary friendly capital (pCap Onslaught) directly between bomber and enemyDivA.flagship:
        //           applyTerminalStrikeCraftGunnery MUST block ShipCommand.FIRE, withhold giveCommand(FIRE), and steer
        //           the bomber laterally around pCap's shoulder!
        //        2) Fast-moving friendly frigate (ff1) crossing into the torpedo's 1.0s flight path from outside the
        //           instant t=0 bore ray: findFriendlyBlockingStrikeLineOfFire MUST predict the intercept and block FIRE!
        //        3) Once friendly hulls clear the firing corridor, ShipCommand.FIRE releases cleanly onto enemyDivA!
        origTorpAmmo[0] = 1;
        ea1.location.set(0f, 1400f);
        ea1.velocity.set(0f, 0f);
        lrBomber.location.set(0f, 550f);
        lrBomber.facing = 90f;
        lrBomber.velocity.set(0f, 160f);
        pCap.location.set(20f, 980f);
        pCap.velocity.set(0f, 0f);
        ff1.location.set(-900f, -900f);
        ff2.location.set(-900f, -950f);
        ff3.location.set(-900f, -1000f);
        divisionai.VoidstrikeCoordinator.FiringSectorAssignment ffSec =
                divisionai.VoidstrikeCoordinator.computeHierarchicalFiringSector(
                        divisionai.PermanentDivisionManager.VoidstrikeGunneryMode.PRECISION,
                        divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG,
                        ea1.proxy, lrBomber.proxy, 0, 1, 0, 1, 0, 1, 0f, 1f, 1f, 0f);
        lrBomber.givenCommands.clear();
        lrBomber.blockedCommands.clear();
        boolean blockedByFriendlyCap = divisionai.VoidstrikeCoordinator.applyTerminalStrikeCraftGunnery(
                lrBomber.proxy, lrBomber.aiFlags, ea1.proxy, ffSec,
                divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG, true, 850f);
        boolean fireHeldAndTurnCommanded = blockedByFriendlyCap
                && lrBomber.blockedCommands.contains(com.fs.starfarer.api.combat.ShipCommand.FIRE)
                && !lrBomber.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.FIRE)
                && (lrBomber.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.TURN_LEFT)
                || lrBomber.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.STRAFE_LEFT));
        // Step 18 Newtonian frames (0.30s at 60 Hz) to verify smooth physical sidestep around pCap's port shoulder:
        for (int sf = 0; sf < 18; sf++) {
            stepRehearsalFighterKinematics60Hz(lrBomber, 1f / 60f);
            divisionai.VoidstrikeCoordinator.applyTerminalStrikeCraftGunnery(
                    lrBomber.proxy, lrBomber.aiFlags, ea1.proxy, ffSec,
                    divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG, true, 850f);
        }
        boolean capFireHeldAndSkirted = fireHeldAndTurnCommanded && lrBomber.velocity.x < -15f;

        // Move pCap clear, place fast friendly frigate ff1 at (-220, 950) sprinting +220 u/s across x=0 torpedo lane!
        pCap.location.set(-900f, 0f);
        ff1.location.set(-220f, 950f);
        ff1.velocity.set(220f, 0f);
        lrBomber.location.set(0f, 550f);
        lrBomber.facing = 90f;
        lrBomber.velocity.set(0f, 160f);
        lrBomber.givenCommands.clear();
        lrBomber.blockedCommands.clear();
        boolean blockedByCrossingFrigate = divisionai.VoidstrikeCoordinator.applyTerminalStrikeCraftGunnery(
                lrBomber.proxy, lrBomber.aiFlags, ea1.proxy, ffSec,
                divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG, true, 850f);
        boolean crossingFrigateHeld = blockedByCrossingFrigate
                && lrBomber.blockedCommands.contains(com.fs.starfarer.api.combat.ShipCommand.FIRE)
                && !lrBomber.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.FIRE);

        // Clear ff1 out of the lane -> bomber fires cleanly!
        ff1.location.set(-900f, -900f);
        ff1.velocity.set(0f, 0f);
        lrBomber.location.set(0f, 550f);
        lrBomber.facing = 90f;
        lrBomber.velocity.set(0f, 160f);
        lrBomber.givenCommands.clear();
        lrBomber.blockedCommands.clear();
        boolean gatedWhenLaneClear = divisionai.VoidstrikeCoordinator.applyTerminalStrikeCraftGunnery(
                lrBomber.proxy, lrBomber.aiFlags, ea1.proxy, ffSec,
                divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection.STRONG, true, 850f);
        boolean firesCleanlyWhenClear = !gatedWhenLaneClear
                && lrBomber.givenCommands.contains(com.fs.starfarer.api.combat.ShipCommand.FIRE);
        boolean friendlyFirePreventionOk = capFireHeldAndSkirted && crossingFrigateHeld && firesCleanlyWhenClear;

        // 7. Battle 6 Regression Checks: VoidstrikeTargetingBias (FREE_SELECTION, CRUISERS_AND_CAPITALS, CAPITALS_FIRST)
        //    + Focus on Carriers modifier across Division Selection, OTC Fire Distribution, Ship Focus & Corridor Gating!
        eGroup.divisions.remove(enemyDivA);
        eGroup.divisions.remove(enemyDivB);
        DivisionState eFrigDiv = new DivisionState("e_vs_frig", 1, 11, DivisionArchetype.FRIGATE_DIVISION, "Frigate Screen", eCmdr);
        SimShipState efScreen = arena.addShip(eFrigDiv, "ef_scr_1", "PLS Brawler", "brawler", HullSize.FRIGATE, 0f, -800f, 270f, 450f, 1, 0);
        DivisionState eCruiserDiv = new DivisionState("e_vs_ca", 1, 12, DivisionArchetype.CRUISER_DIVISION, "Cruiser Line", eCmdr);
        SimShipState eCa = arena.addShip(eCruiserDiv, "e_ca_1", "PLS Eagle", "eagle", HullSize.CRUISER, -900f, 1100f, 270f, 700f, 1, 0);
        DivisionState eCvDiv = new DivisionState("e_vs_cv", 1, 13, DivisionArchetype.FLEET_CARRIER_DIVISION, "Carrier Wing", eCmdr);
        SimShipState eHeron = arena.addShip(eCvDiv, "e_cv_1", "PLS Heron", "heron", HullSize.CRUISER, 900f, 1650f, 270f, 600f, 1, 3);
        DivisionState eCapDiv = new DivisionState("e_vs_bb", 1, 14, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Line", eCmdr);
        SimShipState eConquest = arena.addShip(eCapDiv, "e_bb_1", "PLS Conquest", "conquest", HullSize.CAPITAL_SHIP, 0f, 2100f, 270f, 900f, 1, 0);
        // Mixed division test: Destroyer flagship + Capital member + Cruiser Carrier member
        SimShipState eMixedDd = arena.addShip(eCapDiv, "e_bb_dd", "PLS Hammerhead", "hammerhead", HullSize.DESTROYER, 120f, 2000f, 270f, 600f, 1, 0);
        eCapDiv.flagship = eMixedDd.proxy;
        eGroup.divisions.add(eFrigDiv);
        eGroup.divisions.add(eCruiserDiv);
        eGroup.divisions.add(eCvDiv);
        eGroup.divisions.add(eCapDiv);
        eGroup.shipIdToDivision.put(efScreen.id, eFrigDiv);
        eGroup.shipIdToDivision.put(eCa.id, eCruiserDiv);
        eGroup.shipIdToDivision.put(eHeron.id, eCvDiv);
        eGroup.shipIdToDivision.put(eConquest.id, eCapDiv);
        eGroup.shipIdToDivision.put(eMixedDd.id, eCapDiv);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(eFrigDiv);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(eCruiserDiv);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(eCvDiv);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(eCapDiv);
        divisionai.OtcAI.updateTelemetry(arena.engineProxy, eGroup);

        c1.location.set(0f, -2000f);
        carDiv1.centroid.set(0f, -2000f);
        carDiv1.voidstrikeEgressActive = false;
        carDiv1.voidstrikeStage = 0;
        carDiv1.activeVoidstrikeSortieNumber = 0;
        carDiv1.targetEnemyDivision = null;

        // (a) FREE_SELECTION + Focus on Carriers OFF -> picks closest Frigate Screen at 1200u
        carDiv1.voidstrikeTargetingBias = divisionai.PermanentDivisionManager.VoidstrikeTargetingBias.FREE_SELECTION;
        carDiv1.voidstrikeFocusCarriers = false;
        DivisionState pickFree = divisionai.DivisionCommanderAI.findBestEnemyDivisionFor(
                arena.engineProxy, carDiv1, pGroup, eGroup, false, null);

        // (b) FREE_SELECTION + Focus on Carriers ON -> skips Frigate/Cruiser/Capital to hunt PLS Heron Carrier Wing!
        carDiv1.voidstrikeFocusCarriers = true;
        DivisionState pickFreeCv = divisionai.DivisionCommanderAI.findBestEnemyDivisionFor(
                arena.engineProxy, carDiv1, pGroup, eGroup, false, null);

        // (c) CRUISERS_AND_CAPITALS + Focus on Carriers OFF -> skips close Frigate Screen (-800y) to strike Cruiser/Capital!
        carDiv1.voidstrikeTargetingBias = divisionai.PermanentDivisionManager.VoidstrikeTargetingBias.CRUISERS_AND_CAPITALS;
        carDiv1.voidstrikeFocusCarriers = false;
        DivisionState pickCruiserCap = divisionai.DivisionCommanderAI.findBestEnemyDivisionFor(
                arena.engineProxy, carDiv1, pGroup, eGroup, false, null);

        // (d) CRUISERS_AND_CAPITALS + Focus on Carriers ON -> hunts PLS Heron Carrier Wing over non-carrier Cruiser & Capital!
        carDiv1.voidstrikeFocusCarriers = true;
        DivisionState pickCruiserCapCv = divisionai.DivisionCommanderAI.findBestEnemyDivisionFor(
                arena.engineProxy, carDiv1, pGroup, eGroup, false, null);

        // (e) CAPITALS_FIRST + Focus on Carriers OFF -> skips Frigate (1200u) & Cruiser (3100u) & Heron (3650u) to strike PLS Conquest (4100u)!
        carDiv1.voidstrikeTargetingBias = divisionai.PermanentDivisionManager.VoidstrikeTargetingBias.CAPITALS_FIRST;
        carDiv1.voidstrikeFocusCarriers = false;
        DivisionState pickCapFirst = divisionai.DivisionCommanderAI.findBestEnemyDivisionFor(
                arena.engineProxy, carDiv1, pGroup, eGroup, false, null);

        // (f) Ship-level focus inside mixed eCapDiv (where flagship is Hammerhead Destroyer, member is Conquest Capital):
        //     resolveVoidstrikeFocusShip picks PLS Conquest over Hammerhead flagship!
        carDiv1.targetEnemyDivision = eCapDiv;
        com.fs.starfarer.api.combat.ShipAPI focusShipInMixed =
                divisionai.VoidstrikeCoordinator.resolveVoidstrikeFocusShip(carDiv1);

        // (g) Flight-corridor screening frigate immunity: efScreen (Brawler Frigate at 0,-800) sits directly in the corridor
        //     between c1 (0,-2000) and eConquest (0,+2100); under CAPITALS_FIRST + HIGH observance, bombers must NOT divert onto efScreen!
        lrBomber.location.set(0f, -1900f);
        com.fs.starfarer.api.combat.ShipAPI corridorCheckedTarget =
                divisionai.VoidstrikeCoordinator.resolveSurvivableSquadronTargetShip(
                        c1.proxy,
                        carDiv1,
                        lrBomber.proxy,
                        java.util.Collections.singletonList(lrBomber.proxy),
                        eConquest.proxy,
                        divisionai.PermanentDivisionManager.VoidstrikeTargetObservance.HIGH,
                        true);

        boolean targetingBiasOk = (pickFree == eFrigDiv)
                && (pickFreeCv == eCvDiv)
                && (pickCruiserCap != eFrigDiv && (pickCruiserCap == eCruiserDiv || pickCruiserCap == eCapDiv || pickCruiserCap == eCvDiv))
                && (pickCruiserCapCv == eCvDiv)
                && (pickCapFirst == eCapDiv)
                && (focusShipInMixed == eConquest.proxy)
                && (corridorCheckedTarget == eConquest.proxy);

        check(regenMechExcludedFromFiniteBombersOk
                        && standoffGateScaledOk
                        && mergedTwoDivWaveOk
                        && downrangeTargetLockHeldOk
                        && culminatedOrdnanceExpendedOk
                        && noSplitSoloCarrierKillBoxOk
                        && adaptivePrecisionSpanOk
                        && postEgressAlphaCarrierAlignedOk
                        && doublingDoesNotBounceMainEffortOk
                        && conveyorWaveCulminatedOk
                        && friendlyFirePreventionOk
                        && targetingBiasOk,
                "Strike-Craft & Multi-Carrier Fixes: Regenerating/PD escort ammo excluded, 1200u bomber gate scaled to 960u (at csv attackRunRange=600), downrange target lock holds, ordnance-expended culmination fires cleanly (incl. staggered replacements), Alpha carriers stay unified & re-align post-egress, Doubling holds Main Effort, mega-Precision span adapts, friendly-fire gate + sidestep protects allies, and VoidstrikeTargetingBias + Focus on Carriers works end-to-end",
                String.format(Locale.ROOT, "regenExcluded=%b, gateScaled=%b (wep=%.0f, lrGate=%.0f, stdGate=%.0f), merged2Div=%b, targetLock=%b, ordExpended=%b, unifiedAlpha=%b, precSpan=[4sq=%.0f,20sq=%.0f], postEgressAlign=%b, doublingHold=%b, conveyorCulm=%b, noFriendlyFire=%b, vsTargetBias=%b",
                        regenMechExcludedFromFiniteBombersOk, standoffGateScaledOk, lrMaxRange, lrGateDist, stdGateDist,
                        mergedTwoDivWaveOk, downrangeTargetLockHeldOk, culminatedOrdnanceExpendedOk, noSplitSoloCarrierKillBoxOk,
                        smallPrec.voidstrikeSpanWidth, megaPrec.voidstrikeSpanWidth,
                        postEgressAlphaCarrierAlignedOk, doublingDoesNotBounceMainEffortOk, conveyorWaveCulminatedOk,
                        friendlyFirePreventionOk, targetingBiasOk));
    }

    /**
     * Scenario 11: Decoupled Division Hull Missions vs. Strike Craft Missions.
     * Verifies:
     *  1. A Destroyer Division with Converted Hangars set to Hull = CLOSE_ACTION + Strike Craft = COMBAT_SPACE_PATROL
     *     charges enemy warships with its destroyer hulls (CLOSE_AND_ENGAGE, MANEUVER_TARGET on enemy cruiser) while
     *     its fighter squadrons fly FLY_COMBAT_SPACE_PATROL over the friendly Battleship Main Effort without getting
     *     force-targeted onto the distant enemy line!
     *  2. When that Converted Hangar Destroyer Division switches Strike Craft Mission to ALPHA_STRIKE alongside a
     *     Fleet Carrier Division (Hull = CARRIER_STANDOFF, Strike Craft = ALPHA_STRIKE), the destroyers still brawl up
     *     front on CLOSE_ACTION while the carrier holds astern, AND both divisions' bombers merge into a single unified
     *     2-division Time-on-Target Alpha Voidstrike!
     *  3. A Battlecarrier Division (FLEET_CARRIER_DIVISION) explicitly given Hull = CLOSE_ACTION + Strike Craft =
     *     COMBAT_SPACE_PATROL advances into gun range (CLOSE_AND_ENGAGE, not clamped astern) while its air wing flies CAP.
     */
    private static void testDecoupledHullAndStrikeCraftMissions() {
        System.out.println("--- Scenario 11: Decoupled Hull Missions vs. Strike Craft Missions (Converted Hangars & Battlecarriers) ---");
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "steady");
        BattleGroupState pGroup = new BattleGroupState(0, pCmdr);
        pGroup.authority = OtcAuthority.FIRE_DISTRIBUTION_ONLY;
        BattleGroupState eGroup = new BattleGroupState(1, eCmdr);

        DivisionState battleDiv = new DivisionState("sc_bat", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div 1", pCmdr);
        battleDiv.openingMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
        battleDiv.mainMission = DivisionMissionType.STANDOFF_ENGAGEMENT;

        DivisionState ddHangarDiv = new DivisionState("sc_dd_ch", 0, 2, DivisionArchetype.DESTROYER_DIVISION, "Destroyer Div 1", pCmdr);
        ddHangarDiv.openingMission = DivisionMissionType.CLOSE_ACTION;
        ddHangarDiv.mainMission = DivisionMissionType.CLOSE_ACTION;
        ddHangarDiv.strikeCraftOpeningMission = divisionai.PermanentDivisionManager.StrikeCraftMissionType.COMBAT_SPACE_PATROL;
        ddHangarDiv.strikeCraftMainMission = divisionai.PermanentDivisionManager.StrikeCraftMissionType.COMBAT_SPACE_PATROL;
        ddHangarDiv.voidstrikeCoordination = divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode.UNCOORDINATED;

        DivisionState fleetCvDiv = new DivisionState("sc_cv", 0, 3, DivisionArchetype.FLEET_CARRIER_DIVISION, "Carrier Div 1", pCmdr);
        fleetCvDiv.hasCarrierCapability = true;
        fleetCvDiv.openingMission = DivisionMissionType.CARRIER_STANDOFF;
        fleetCvDiv.mainMission = DivisionMissionType.CARRIER_STANDOFF;
        fleetCvDiv.strikeCraftOpeningMission = divisionai.PermanentDivisionManager.StrikeCraftMissionType.ALPHA_STRIKE;
        fleetCvDiv.strikeCraftMainMission = divisionai.PermanentDivisionManager.StrikeCraftMissionType.ALPHA_STRIKE;
        fleetCvDiv.voidstrikeCoordination = divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode.UNCOORDINATED;

        pGroup.divisions.add(battleDiv);
        pGroup.divisions.add(ddHangarDiv);
        pGroup.divisions.add(fleetCvDiv);
        pGroup.mainEffortDivision = battleDiv;

        DivisionState enemyLine = new DivisionState("sc_enemy", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Enemy Cruiser Line", eCmdr);
        eGroup.divisions.add(enemyLine);
        pGroup.primaryThreatEnemyDivision = enemyLine;

        SimArena arena = new SimArena("Scenario 11: Decoupled Hull & Strike Craft Missions", "Converted Hangar DD Brawl + CAP / Alpha Strike", pGroup, eGroup);
        SimShipState pBat = arena.addShip(battleDiv, "sc_pbat", "HSS Onslaught", "onslaught", HullSize.CAPITAL_SHIP, 0f, -600f, 50f, 1200f, 2, 0);
        SimShipState dd1 = arena.addShip(ddHangarDiv, "sc_dd1", "HSS Enforcer (CH)", "enforcer", HullSize.DESTROYER, -220f, -150f, 90f, 800f, 0, 1);
        SimShipState dd2 = arena.addShip(ddHangarDiv, "sc_dd2", "HSS Hammerhead (CH)", "hammerhead", HullSize.DESTROYER, 220f, -150f, 95f, 800f, 0, 1);
        SimShipState cv1 = arena.addShip(fleetCvDiv, "sc_cv1", "HSS Astral", "astral", HullSize.CAPITAL_SHIP, 0f, -1500f, 45f, 1000f, 0, 4);
        battleDiv.flagship = pBat.proxy;
        ddHangarDiv.flagship = dd1.proxy;
        fleetCvDiv.flagship = cv1.proxy;
        pGroup.shipIdToDivision.put(pBat.id, battleDiv);
        pGroup.shipIdToDivision.put(dd1.id, ddHangarDiv);
        pGroup.shipIdToDivision.put(dd2.id, ddHangarDiv);
        pGroup.shipIdToDivision.put(cv1.id, fleetCvDiv);

        SimShipState ec1 = arena.addShip(enemyLine, "sc_ec1", "PLS Eagle", "eagle", HullSize.CRUISER, 0f, 1550f, 80f, 1000f, 2, 0);
        SimShipState ec2 = arena.addShip(enemyLine, "sc_ec2", "PLS Falcon", "falcon", HullSize.CRUISER, 260f, 1600f, 95f, 1000f, 2, 0);
        enemyLine.flagship = ec1.proxy;
        eGroup.shipIdToDivision.put(ec1.id, enemyLine);
        eGroup.shipIdToDivision.put(ec2.id, enemyLine);

        // Attach 1 fighter/bomber wing to dd1 (Converted Hangar) and 1 bomber wing to cv1
        final com.fs.starfarer.api.combat.WeaponAPI torpWep = (com.fs.starfarer.api.combat.WeaponAPI) Proxy.newProxyInstance(
                SimArena.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.combat.WeaponAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("usesAmmo".equals(n)) return true;
                    if ("getAmmo".equals(n) || "getMaxAmmo".equals(n)) return 2;
                    if ("getAmmoPerSecond".equals(n)) return 0f;
                    if ("getRange".equals(n)) return 900f;
                    if ("getType".equals(n)) return com.fs.starfarer.api.combat.WeaponAPI.WeaponType.MISSILE;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });

        SimShipState ddFighter = arena.addShip(ddHangarDiv, "sc_ddf1", "Talon 1", "talon_wing", HullSize.FIGHTER, -220f, -450f, 220f, 400f, 0, 0);
        ddHangarDiv.members.remove(ddFighter.proxy);
        ddHangarDiv.profiles.removeIf(p -> p.ship == ddFighter.proxy);
        ddFighter.weapons.clear();
        ddFighter.weapons.add(torpWep);

        SimShipState cvBomber = arena.addShip(fleetCvDiv, "sc_cvb1", "Dagger 1", "dagger_wing", HullSize.FIGHTER, 0f, -900f, 180f, 900f, 1, 0);
        fleetCvDiv.members.remove(cvBomber.proxy);
        fleetCvDiv.profiles.removeIf(p -> p.ship == cvBomber.proxy);
        cvBomber.weapons.clear();
        cvBomber.weapons.add(torpWep);

        boolean[] ddRet = new boolean[]{false};
        boolean[] cvRet = new boolean[]{false};
        createRehearsalWing(dd1, java.util.Collections.singletonList(ddFighter), true, ddRet);
        createRehearsalWing(cv1, java.util.Collections.singletonList(cvBomber), true, cvRet);

        divisionai.VoidstrikeCoordinator.resetForNewBattle();
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(battleDiv);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(ddHangarDiv);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(fleetCvDiv);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(enemyLine);

        battleDiv.openingMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
        battleDiv.mainMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
        ddHangarDiv.openingMission = DivisionMissionType.CLOSE_ACTION;
        ddHangarDiv.mainMission = DivisionMissionType.CLOSE_ACTION;
        ddHangarDiv.strikeCraftOpeningMission = divisionai.PermanentDivisionManager.StrikeCraftMissionType.COMBAT_SPACE_PATROL;
        ddHangarDiv.strikeCraftMainMission = divisionai.PermanentDivisionManager.StrikeCraftMissionType.COMBAT_SPACE_PATROL;
        ddHangarDiv.voidstrikeCoordination = divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode.UNCOORDINATED;
        fleetCvDiv.openingMission = DivisionMissionType.CARRIER_STANDOFF;
        fleetCvDiv.mainMission = DivisionMissionType.CARRIER_STANDOFF;
        fleetCvDiv.strikeCraftOpeningMission = divisionai.PermanentDivisionManager.StrikeCraftMissionType.ALPHA_STRIKE;
        fleetCvDiv.strikeCraftMainMission = divisionai.PermanentDivisionManager.StrikeCraftMissionType.ALPHA_STRIKE;
        fleetCvDiv.voidstrikeCoordination = divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode.UNCOORDINATED;
        divisionai.OtcAI.updateTelemetry(arena.engineProxy, pGroup);
        divisionai.OtcAI.updateTelemetry(arena.engineProxy, eGroup);
        divisionai.OtcBattlePlanner.planBattle(arena.engineProxy, pGroup, eGroup, null, null, 20.0f);
        divisionai.DivisionCommanderAI.evaluateAndExecuteDivisions(arena.engineProxy, pGroup, eGroup, 20.0f);

        // 1. Verify DD hulls execute CLOSE_ACTION while their Converted Hangar wings execute COMBAT_SPACE_PATROL!
        DivisionIntentType step1HullIntent = ddHangarDiv.currentIntent;
        DivisionIntentType step1ScIntent = ddHangarDiv.currentStrikeCraftIntent;
        DivisionState step1ScFriendly = ddHangarDiv.getEffectiveStrikeCraftTargetFriendlyDivision();
        boolean step1HasCft = dd1.aiFlags.hasFlag(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.CARRIER_FIGHTER_TARGET);
        boolean ddHullBrawlsAndSquadronCapsOk = (step1HullIntent == DivisionIntentType.CLOSE_AND_ENGAGE
                || step1HullIntent == DivisionIntentType.PRESS_HOME
                || step1HullIntent == DivisionIntentType.WORK_FLANK)
                && ddHangarDiv.targetEnemyDivision == enemyLine
                && step1ScIntent == DivisionIntentType.FLY_COMBAT_SPACE_PATROL
                && step1ScFriendly == battleDiv
                && !step1HasCft;

        // 2. Switch DD Strike Craft Mission to ALPHA_STRIKE alongside Carrier Div 1 (CARRIER_STANDOFF + ALPHA_STRIKE):
        //    DD hulls STILL brawl on CLOSE_ACTION, Carrier hull holds CARRIER_STANDOFF, and both wings merge into 1 Voidstrike!
        ddHangarDiv.strikeCraftOpeningMission = divisionai.PermanentDivisionManager.StrikeCraftMissionType.ALPHA_STRIKE;
        ddHangarDiv.strikeCraftMainMission = divisionai.PermanentDivisionManager.StrikeCraftMissionType.ALPHA_STRIKE;
        ddFighter.location.set(-150f, 350f);
        cvBomber.location.set(50f, -600f);
        divisionai.DivisionCommanderAI.evaluateAndExecuteDivisions(arena.engineProxy, pGroup, eGroup, 25.0f);
        divisionai.VoidstrikeCoordinator.updateVoidstrikes(arena.engineProxy, pGroup, null, eGroup, 25.0f);
        List<divisionai.VoidstrikeCoordinator.VoidstrikeRecord> mergedAlphaVs =
                divisionai.VoidstrikeCoordinator.getActiveVoidstrikes();
        DivisionIntentType step2DdHullIntent = ddHangarDiv.currentIntent;
        DivisionIntentType step2DdScIntent = ddHangarDiv.currentStrikeCraftIntent;
        DivisionIntentType step2CvIntent = fleetCvDiv.currentIntent;
        java.util.Set<String> step2DivIds = mergedAlphaVs.isEmpty() ? java.util.Collections.emptySet() : mergedAlphaVs.get(0).participatingDivisionIds;
        boolean ddAndCvMergedAlphaStrikeOk = (step2DdHullIntent == DivisionIntentType.CLOSE_AND_ENGAGE
                || step2DdHullIntent == DivisionIntentType.PRESS_HOME
                || step2DdHullIntent == DivisionIntentType.WORK_FLANK)
                && step2DdScIntent == DivisionIntentType.LAUNCH_ALPHA_STRIKE
                && step2CvIntent == DivisionIntentType.LAUNCH_ALPHA_STRIKE
                && mergedAlphaVs.size() == 1
                && step2DivIds.contains(ddHangarDiv.id)
                && step2DivIds.contains(fleetCvDiv.id);

        // 3. Battlecarrier test: give Carrier Div 1 explicit Hull Mission = CLOSE_ACTION + Strike Craft = COMBAT_SPACE_PATROL
        //    -> Battlecarrier hull advances on CLOSE_AND_ENGAGE (stationPoint ahead of -600y, NOT clamped -450u behind Main Effort!),
        //       while its air wing flies FLY_COMBAT_SPACE_PATROL!
        fleetCvDiv.openingMission = DivisionMissionType.CLOSE_ACTION;
        fleetCvDiv.mainMission = DivisionMissionType.CLOSE_ACTION;
        fleetCvDiv.strikeCraftOpeningMission = divisionai.PermanentDivisionManager.StrikeCraftMissionType.COMBAT_SPACE_PATROL;
        fleetCvDiv.strikeCraftMainMission = divisionai.PermanentDivisionManager.StrikeCraftMissionType.COMBAT_SPACE_PATROL;
        fleetCvDiv.intentCommitUntil = 0f;
        fleetCvDiv.lastIntentChangeTime = -99f;
        divisionai.DivisionCommanderAI.evaluateAndExecuteDivisions(arena.engineProxy, pGroup, eGroup, 35.0f);
        boolean battlecarrierDualMissionOk = (fleetCvDiv.currentIntent == DivisionIntentType.CLOSE_AND_ENGAGE
                || fleetCvDiv.currentIntent == DivisionIntentType.PRESS_HOME)
                && fleetCvDiv.currentStrikeCraftIntent == DivisionIntentType.FLY_COMBAT_SPACE_PATROL
                && fleetCvDiv.stationPoint.y > -600f;

        check(ddHullBrawlsAndSquadronCapsOk && ddAndCvMergedAlphaStrikeOk && battlecarrierDualMissionOk,
                "Decoupled Hull & Strike Craft Missions: Converted Hangar DD brawls up front while flying CAP or merging into fleet-wide Alpha Voidstrike, and Battlecarrier on CLOSE_ACTION advances with CAP umbrella",
                String.format(Locale.ROOT, "ddBrawlCap=%b (hull=%s, sc=%s, fr=%s, cft=%b), ddCvMergedAlpha=%b (hull=%s, sc=%s, cv=%s, vsCount=%d, divs=%s), battlecarrierDual=%b (hull=%s, sc=%s, stnY=%.0f)",
                        ddHullBrawlsAndSquadronCapsOk, step1HullIntent, step1ScIntent, step1ScFriendly != null ? step1ScFriendly.id : "null", step1HasCft,
                        ddAndCvMergedAlphaStrikeOk, step2DdHullIntent, step2DdScIntent, step2CvIntent, mergedAlphaVs.size(), step2DivIds,
                        battlecarrierDualMissionOk, fleetCvDiv.currentIntent, fleetCvDiv.currentStrikeCraftIntent, fleetCvDiv.stationPoint.y));
    }

    private static void testCampaignLogPolishAndC2Hygiene() {
        System.out.println("\n--- Scenario 12: Convoy General Chase, No 1-Frigate Setpiece Spam, Steady Anvil, Deduplicated Chatter & Sortie Numbering ---");
        CommanderProfile pCmdr = new CommanderProfile(null, "steady");
        CommanderProfile eCmdr = new CommanderProfile(null, "steady");
        BattleGroupState pGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState eGroup = new BattleGroupState(1, eCmdr);
        pGroup.authority = divisionai.PermanentDivisionManager.OtcAuthority.COMMAND_BY_DIRECTION;

        DivisionState mainDiv = new DivisionState("c12_main", 0, 1, DivisionArchetype.CRUISER_DIVISION, "Line Cruiser Div 1", pCmdr);
        DivisionState loneFrig = new DivisionState("c12_frig", 0, 1, DivisionArchetype.MISSILE_FRIGATE_DIVISION, "Frigate Div 4", pCmdr);
        pGroup.divisions.add(mainDiv);
        pGroup.divisions.add(loneFrig);

        DivisionState enemyService = new DivisionState("c12_svc", 1, 1, DivisionArchetype.SERVICE_GROUP, "Service Group 11", eCmdr);
        eGroup.divisions.add(enemyService);

        SimArena arena = new SimArena("Scenario 12: Campaign C2 Hygiene", "Convoy chase + Setpiece mass + Anvil steady + Chatter dedup", pGroup, eGroup);
        arena.addShip(mainDiv, "c12_mc1", "HSS Eagle", "eagle", HullSize.CRUISER, 0f, -800f, 85f, 1100f, 0, 0);
        arena.addShip(mainDiv, "c12_mc2", "HSS Champion", "champion", HullSize.CRUISER, 220f, -820f, 75f, 1100f, 0, 0);
        arena.addShip(loneFrig, "c12_f1", "HSS Vigilance 1", "vigilance", HullSize.FRIGATE, -320f, -600f, 135f, 800f, 4, 0);
        arena.addShip(loneFrig, "c12_f2", "HSS Vigilance 2", "vigilance", HullSize.FRIGATE, -420f, -620f, 135f, 800f, 4, 0);
        arena.addShip(enemyService, "c12_s1", "HSS Phaeton", "phaeton", HullSize.DESTROYER, 0f, 1100f, 80f, 400f, 0, 0);
        arena.addShip(enemyService, "c12_s2", "HSS Colossus", "colossus", HullSize.CRUISER, 220f, 1150f, 60f, 400f, 0, 0);

        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(mainDiv);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(loneFrig);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(enemyService);
        pGroup.phaseEnteredTime = -100f;

        // 1. Against civilian Service Group 11: fleet enters GENERAL_CHASE and NEVER creates a marshaling Setpiece!
        divisionai.OtcAI.updateTelemetry(arena.engineProxy, pGroup);
        divisionai.OtcAI.updateTelemetry(arena.engineProxy, eGroup);
        divisionai.OtcAI.evaluateOtcStrategy(arena.engineProxy, pGroup, eGroup, 30.0f);
        boolean convoyChaseNoSetpieceOk = (pGroup.currentPhase == divisionai.DivisionModel.BattlePhase.GENERAL_CHASE)
                && (pGroup.strikePlan == null);

        // 2. Replace enemy with an intact combat Cruiser division: lone 2-frigate Missile Frigate pack (< 20 FP)
        //    must NOT trigger a 1-division Fleet Setpiece!
        eGroup.divisions.clear();
        DivisionState enemyCombat = new DivisionState("c12_ec", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Enemy Cruiser Div 1", eCmdr);
        eGroup.divisions.add(enemyCombat);
        arena.addShip(enemyCombat, "c12_ec1", "PLS Eagle 1", "eagle", HullSize.CRUISER, 0f, 950f, 80f, 1100f, 0, 0);
        arena.addShip(enemyCombat, "c12_ec2", "PLS Eagle 2", "eagle", HullSize.CRUISER, 240f, 980f, 80f, 1100f, 0, 0);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(enemyCombat);
        pGroup.lastStrikePlanEndTime = -100f;
        divisionai.OtcAI.updateTelemetry(arena.engineProxy, pGroup);
        divisionai.OtcAI.updateTelemetry(arena.engineProxy, eGroup);
        divisionai.OtcAI.evaluateOtcStrategy(arena.engineProxy, pGroup, eGroup, 75.0f);
        boolean noLoneFrigateSetpieceSpamOk = (pGroup.strikePlan == null);

        // 3. Add a Missile Destroyer division (2 divisions striking together -> true Hammer & Anvil Setpiece!):
        //    Verify Main Effort holds steady on FORM_LINE against intact enemy, and presses home on EXECUTE_STRIKE once enemy breaks!
        DivisionState ddStrike = new DivisionState("c12_dd", 0, 1, DivisionArchetype.MISSILE_DESTROYER_DIVISION, "Destroyer Div 1", pCmdr);
        pGroup.divisions.add(ddStrike);
        arena.addShip(ddStrike, "c12_dd1", "HSS Hammerhead 1", "hammerhead", HullSize.DESTROYER, -500f, -750f, 95f, 900f, 6, 0);
        arena.addShip(ddStrike, "c12_dd2", "HSS Hammerhead 2", "hammerhead", HullSize.DESTROYER, -650f, -780f, 95f, 900f, 6, 0);
        divisionai.DivisionAutoFormer.recomputeDivisionCapabilities(ddStrike);
        pGroup.lastStrikePlanEndTime = -100f;
        divisionai.OtcAI.updateTelemetry(arena.engineProxy, pGroup);
        divisionai.OtcAI.updateTelemetry(arena.engineProxy, eGroup);
        divisionai.OtcAI.evaluateOtcStrategy(arena.engineProxy, pGroup, eGroup, 120.0f);
        if (pGroup.strikePlan != null && !pGroup.strikePlan.committed) {
            pGroup.strikePlan.committed = true;
            pGroup.strikePlan.commitTime = 121.0f;
            divisionai.OtcBattlePlanner.planBattle(arena.engineProxy, pGroup, eGroup, null, null, 121.5f);
        }
        boolean anvilSteadyWhenEnemyIntactOk = (pGroup.strikePlan != null)
                && (mainDiv.otcOrder.type == divisionai.DivisionModel.OtcOrderType.FORM_LINE);
        enemyCombat.avgFluxLevel = 0.72f; // Enemy line breaking under high flux!
        divisionai.OtcBattlePlanner.planBattle(arena.engineProxy, pGroup, eGroup, null, null, 123.0f);
        boolean anvilSurgesWhenEnemyBreaksOk = (mainDiv.otcOrder.type == divisionai.DivisionModel.OtcOrderType.EXECUTE_STRIKE);

        // 4. Verify unchanged (intent, target) does NOT re-emit duplicate log lines at +30s, while target change DOES log!
        Boolean prevDbg = divisionai.DivisionAISettings.testDebugLoggingEnabledOverride;
        divisionai.DivisionAISettings.testDebugLoggingEnabledOverride = Boolean.TRUE;
        List<String> sink = new ArrayList<>();
        divisionai.DivisionCombatLog.simLogSink = sink;
        mainDiv.currentIntent = DivisionIntentType.FORM_LINE_AND_FIX;
        mainDiv.lastLoggedSignature = "";
        mainDiv.lastLogAnnouncementTime = -100f;
        divisionai.DivisionCombatLog.logDivisionIntent(arena.engineProxy, mainDiv, "Enemy Cruiser Div 1", "OTC: hold the line", 10.0f);
        divisionai.DivisionCombatLog.logDivisionIntent(arena.engineProxy, mainDiv, "Enemy Cruiser Div 1", "OTC: hold the line", 42.0f);
        int countAfterDup = sink.size();
        divisionai.DivisionCombatLog.logDivisionIntent(arena.engineProxy, mainDiv, "Enemy Cruiser Div 2", "OTC: hold the line", 48.0f);
        int countAfterNewTarget = sink.size();
        divisionai.DivisionCombatLog.simLogSink = null;
        divisionai.DivisionAISettings.testDebugLoggingEnabledOverride = prevDbg;
        boolean chatterDedupOk = (countAfterDup == 1 && countAfterNewTarget == 2);

        // 5. Verify uncommitted transient VoidstrikeRecord reclaims its sortie number (no 1 -> 3 skip)!
        divisionai.VoidstrikeCoordinator.resetForNewBattle();
        int claimed1 = divisionai.VoidstrikeCoordinator.claimSortieNumber(null, null, 0);
        divisionai.VoidstrikeCoordinator.VoidstrikeRecord transientRec =
                new divisionai.VoidstrikeCoordinator.VoidstrikeRecord(0, claimed1, "DIV:c12_ec");
        divisionai.VoidstrikeCoordinator.reclaimUncommittedSortieNumber(transientRec, null, null);
        int nextAfterReclaim = divisionai.VoidstrikeCoordinator.claimSortieNumber(null, null, 0);
        boolean sortieReclaimNoSkipOk = (claimed1 == 1 && nextAfterReclaim == 1);

        check(convoyChaseNoSetpieceOk
                        && noLoneFrigateSetpieceSpamOk
                        && anvilSteadyWhenEnemyIntactOk
                        && anvilSurgesWhenEnemyBreaksOk
                        && chatterDedupOk
                        && sortieReclaimNoSkipOk,
                "Campaign Log Polish & C2 Hygiene: Civilian convoy General Chase, no 1-frigate Setpiece spam, steady Anvil line, deduplicated chatter, and gap-free Voidstrike numbering",
                String.format(Locale.ROOT, "convoyChase=%b, no1FrigSpam=%b, anvilSteady=%b, anvilSurge=%b, dedup=%b (%d->%d), noSkip=%b (%d->%d)",
                        convoyChaseNoSetpieceOk, noLoneFrigateSetpieceSpamOk,
                        anvilSteadyWhenEnemyIntactOk, anvilSurgesWhenEnemyBreaksOk,
                        chatterDedupOk, countAfterDup, countAfterNewTarget,
                        sortieReclaimNoSkipOk, claimed1, nextAfterReclaim));
    }

    private static void testMilitaryFirstNomenclatureSweep() {
        System.out.println("\n--- Scenario 13: Military-First Nomenclature Sweep & Legacy Save Migration ---");

        String[] bannedSubstrings = {
                "hallowed", "brimstone", "righteous hammer", "pillar of judgment",
                "zealot", "censer", "penitent veil", "duskborne", "reliquary",
                "crusader", "ark carrier host", "ark convoy host", "sword flotilla",
                "slinger flotilla", "cataphract phalanx", "ballista mora", "toxotes",
                "krypteia", "hipparchy", "lion's wrath", "thunderbolt phalanx",
                "boom-runner", "bottle-rocket", "loot train", "bakery", "grain & trade"
        };

        List<String> violations = new ArrayList<>();

        // 1. Check all player-selectable presets across all 16 archetypes
        for (DivisionNomenclature.PlayerNamingPreset p : DivisionNomenclature.PlayerNamingPreset.getPlayerSelectablePresets()) {
            String summaryLower = p.summary.toLowerCase(Locale.ROOT);
            for (String banned : bannedSubstrings) {
                if (summaryLower.contains(banned)) {
                    violations.add("presetSummary:" + p.name() + ":" + p.summary);
                }
            }
            for (DivisionArchetype arch : DivisionArchetype.values()) {
                String pref = DivisionNomenclature.getDefaultPrefixForPreset(p, arch);
                String prefLower = pref.toLowerCase(Locale.ROOT);
                for (String banned : bannedSubstrings) {
                    if (prefLower.contains(banned)) {
                        violations.add("preset:" + p.name() + ":" + arch.name() + "->" + pref);
                    }
                }
            }
        }

        // 2. Check all major vanilla & modded factions across war, merchant, smuggler, and scavenger roles
        String[] factionsToAudit = {
                "hegemony", "ironshell", "tritachyon", "persean", "sindrian_diktat", "lions_guard", "diktat_achilles",
                "luddic_church", "knights_of_ludd", "luddic_path", "pirates", "independent",
                "mercenary", "uaf", "interstellarimperium", "ii_imperial_guard",
                "tahlan_legioinfernalis", "armaarmatura", "vic"
        };
        divisionai.FactionLibrary.FleetRoleCategory[] rolesToAudit = {
                divisionai.FactionLibrary.FleetRoleCategory.WAR_FLEET,
                divisionai.FactionLibrary.FleetRoleCategory.MERCHANT_CONVOY,
                divisionai.FactionLibrary.FleetRoleCategory.SMUGGLER_RAIDER,
                divisionai.FactionLibrary.FleetRoleCategory.SCAVENGER_EXPEDITION
        };
        for (String fId : factionsToAudit) {
            for (divisionai.FactionLibrary.FleetRoleCategory role : rolesToAudit) {
                for (DivisionArchetype arch : DivisionArchetype.values()) {
                    String formatted = DivisionNomenclature.formatFactionDivisionName(
                            fId, NomenclatureCategory.HUMAN_NAVAL, arch, 1, null, 7919L, role);
                    String lower = formatted.toLowerCase(Locale.ROOT);
                    for (String banned : bannedSubstrings) {
                        if (lower.contains(banned)) {
                            violations.add("faction:" + fId + ":" + role.name() + ":" + arch.name() + "->" + formatted);
                        }
                    }
                }
            }
        }

        // 3. Verify explicit grounded Luddic, Knights, Tri-Tachyon, Diktat, and UAF outputs + save migration
        String cgrBattle = DivisionNomenclature.formatFactionDivisionName(
                "luddic_church", NomenclatureCategory.HUMAN_NAVAL, DivisionArchetype.BATTLESHIP_DIVISION, 1, null, 0L);
        String cgrCruiser = DivisionNomenclature.formatFactionDivisionName(
                "luddic_church", NomenclatureCategory.HUMAN_NAVAL, DivisionArchetype.CRUISER_DIVISION, 1, null, 0L);
        String kolBattle = DivisionNomenclature.formatFactionDivisionName(
                "knights_of_ludd", NomenclatureCategory.HUMAN_NAVAL, DivisionArchetype.BATTLESHIP_DIVISION, 1, null, 0L);
        String ttCarrier = DivisionNomenclature.formatFactionDivisionName(
                "tritachyon", NomenclatureCategory.HUMAN_NAVAL, DivisionArchetype.FLEET_CARRIER_DIVISION, 1, null, 0L);
        String diktatCruiser = DivisionNomenclature.formatFactionDivisionName(
                "sindrian_diktat", NomenclatureCategory.HUMAN_NAVAL, DivisionArchetype.CRUISER_DIVISION, 1, null, 0L);
        String reformerBattle = DivisionNomenclature.formatFactionDivisionName(
                "diktat_achilles", NomenclatureCategory.HUMAN_NAVAL, DivisionArchetype.BATTLESHIP_DIVISION, 1, null, 0L);
        String uafConvoy = DivisionNomenclature.formatFactionDivisionName(
                "uaf", NomenclatureCategory.HUMAN_NAVAL, DivisionArchetype.SERVICE_GROUP, 1, null, 0L,
                divisionai.FactionLibrary.FleetRoleCategory.MERCHANT_CONVOY);

        boolean factionOutputsOk = "Armada Battle Line I".equals(cgrBattle)
                && "Warden Cruiser Squadron I".equals(cgrCruiser)
                && "Knight Battle Line I".equals(kolBattle)
                && "Heavy Flight Group Alpha".equals(ttCarrier)
                && "State Cruiser Squadron 1".equals(diktatCruiser)
                && "Reformer Battle Line I".equals(reformerBattle)
                && "Auroran Trade Convoy 1".equals(uafConvoy);

        boolean migrationOk = "Armada Battle Line I".equals(
                DivisionNomenclature.migrateLegacyDivisionName("Hallowed Bastion I", DivisionArchetype.BATTLESHIP_DIVISION))
                && "Warden Cruiser Squadron II".equals(
                DivisionNomenclature.migrateLegacyDivisionName("Righteous Hammer II", DivisionArchetype.CRUISER_DIVISION))
                && "Auxiliary Phase Picket I".equals(
                DivisionNomenclature.migrateLegacyDivisionName("Penitent Veil I", DivisionArchetype.PHASE_DIVISION))
                && "1st Missile Frigate Pack".equals(
                DivisionNomenclature.migrateLegacyDivisionName("1st Bottle-Rocket Pack", DivisionArchetype.MISSILE_FRIGATE_DIVISION))
                && "Task Force Chimera".equals(
                DivisionNomenclature.migrateLegacyDivisionName("Task Force Chimera", DivisionArchetype.CRUISER_DIVISION));

        check(violations.isEmpty() && factionOutputsOk && migrationOk,
                "Scenario 13: All player presets & faction libraries enforce military-first nomenclature and migrate legacy saves cleanly",
                String.format(Locale.ROOT, "violations=%s, cgr='%s'/'%s', kol='%s', tt='%s', diktat='%s', uaf='%s', migration=%s",
                        violations, cgrBattle, cgrCruiser, kolBattle, ttCarrier, diktatCruiser, uafConvoy, migrationOk));
    }

    private static Class<?> resolveScriptClassLoaderClass(ClassLoader loader, String fallbackName)
            throws ClassNotFoundException {
        for (String candidate : new String[]{
                "com.fs.starfarer.loading.scripts.ScriptClassLoader",
                fallbackName,
                "com.fs.starfarer.loading.scripts.B"}) {
            if (candidate == null) continue;
            try {
                return Class.forName(candidate, true, loader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException("ScriptClassLoader not found on " + loader);
    }

    private static float dist(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static com.fs.starfarer.api.combat.BattleObjectiveAPI createMockObjective(
            final String displayName,
            final String type,
            final Vector2f loc,
            final int owner) {
        return (com.fs.starfarer.api.combat.BattleObjectiveAPI) Proxy.newProxyInstance(
                com.fs.starfarer.api.combat.BattleObjectiveAPI.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.combat.BattleObjectiveAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getLocation".equals(n)) return loc;
                    if ("getOwner".equals(n)) return owner;
                    if ("getDisplayName".equals(n)) return displayName;
                    if ("getType".equals(n)) return type;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
    }

    private static com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo createMockAssignment(
            final com.fs.starfarer.api.combat.CombatAssignmentType type,
            final com.fs.starfarer.api.combat.AssignmentTargetAPI target) {
        return (com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo) Proxy.newProxyInstance(
                com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getType".equals(n)) return type;
                    if ("getTarget".equals(n)) return target;
                    return null;
                });
    }
}
