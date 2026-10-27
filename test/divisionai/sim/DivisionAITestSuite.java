package divisionai.sim;

import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.FullName.Gender;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
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
        testCulturalNomenclatureAndPrefixBuckets();
        testFluidDivisionPolygonsAndOverlapStriping();
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
        DivisionState lpDiv = new DivisionState("lp_1", 1, 1, DivisionArchetype.CRUISER_DIVISION, "Martyr Host I", lpCmdr);
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
        SimShipState v1 = lpArena.addShip(lpDiv, "lp1", "Blessed Prometheus", "prometheus2", HullSize.CAPITAL_SHIP, 0f, 950f, 90f, 700f, 2, 0);
        SimShipState v2 = lpArena.addShip(lpDiv, "lp2", "Blessed Eradicator", "eradicator", HullSize.CRUISER, 120f, 980f, 110f, 800f, 2, 0);
        lpArena.addShip(hegDiv, "h1", "HSS Onslaught", "Onslaught", HullSize.CAPITAL_SHIP, 0f, 0f, 55f, 1300f, 0, 0);
        v1.fluxLevel = 0.82f;
        v2.fluxLevel = 0.82f;

        boolean pressedHome = false;
        boolean everBackedOffOrHauledOut = false;
        for (int s = 0; s < 10; s++) {
            v1.fluxLevel = 0.82f;
            v2.fluxLevel = 0.82f;
            lpArena.runSimulation(1.0f);
            if (lpDiv.currentIntent == DivisionIntentType.PRESS_HOME) {
                pressedHome = true;
            }
            if (lpDiv.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE
                    || lpDiv.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT) {
                everBackedOffOrHauledOut = true;
            }
        }
        check(pressedHome && !everBackedOffOrHauledOut,
                "Luddic Path Martyrdom division executes point-blank PRESS_HOME charge without backing off or hauling out at 82% flux",
                "pressedHome=" + pressedHome + ", everBackedOffOrHauledOut=" + everBackedOffOrHauledOut + ", finalDist=" + Math.round(v1.location.length()) + "u");
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
     * Scenario 7: UI/UX Testing Harness (Deployment Window 14x7 Enlargement, Succinctness & Button Budgets).
     */
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
     * Scenario 8: Bytecode verification that mod/jars/DivisionAI.jar contains zero java/lang/reflect references.
     */
    private static void testZeroReflectionInCompiledJar() throws Exception {
        System.out.println("\n--- Scenario 8: Bytecode Security Check (Zero java/lang/reflect) ---");
        File jarFile = new File("mod/jars/DivisionAI.jar");
        int classCount = 0;
        List<String> violations = new ArrayList<>();
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
                            violations.add(entry.getName());
                        }
                    }
                }
            }
        }
        check(classCount > 0 && violations.isEmpty(),
                "DivisionAI.jar contains zero java/lang/reflect constant-pool entries",
                "scannedClasses=" + classCount + ", violations=" + violations);
    }

    private static float dist(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
