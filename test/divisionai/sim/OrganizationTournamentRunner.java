package divisionai.sim;

import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.ShipAPI;
import divisionai.sim.SimArena.SimWeaponMount;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import divisionai.BattlefieldFirepowerEvaluator;
import divisionai.DivisionAICombatPlugin;
import divisionai.DivisionAutoFormer;
import divisionai.DivisionCommanderAI;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionModel.MissionContextType;
import divisionai.FactionLibrary.OrganizationRating;
import divisionai.OtcAI;
import divisionai.PermanentDivisionManager.DivisionFormation;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.FleetWideFormation;
import divisionai.PermanentDivisionManager.LineStation;
import divisionai.PermanentDivisionManager.MultiDivisionSetpiece;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.OtcPlay;
import divisionai.ShipCapabilityProfile;
import divisionai.sim.SimArena.SimShipState;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Runs the 120-Battle Large-Fleet Organization Rating & OTC Convergence Tournament.
 */
public class OrganizationTournamentRunner {
    static float[] fireSec = new float[2], aliveSec = new float[2], idleInRangeSec = new float[2],
            campInRangeSec = new float[2], backOffSec = new float[2], dmgDealt = new float[2];
    static Map<String, Float> intentSec = new java.util.TreeMap<>();
    static boolean DIAG = false; static boolean TRACE1 = false; static float CUR_T = 0f;
    static float[] dnboSec = new float[2], ovl = new float[2], hullDmgTaken = new float[2], strikeDmgDealt = new float[2], exposure = new float[2], firstLoss = new float[2], halfLoss = new float[2];
    static int battles = 0;
    static float MAX_BATTLE_SECONDS = 1800f;
    static int invalidCount = 0;
    static boolean[] vanillaOnlySide = new boolean[]{false, false};
    static boolean STALL_DUMP = false;
    static float[] shotNoReply = new float[2];
    static Map<String, float[]> zoneStats = new java.util.TreeMap<>(); // zone -> {sec, fireSec}
    static Map<String, Float> haulWhy = new HashMap<>(), ovlWhy = new HashMap<>();
    static divisionai.DivisionModel.BattleGroupState CUR_GROUP0 = null;
    static float reliefSec = 0f, doubleLineSec = 0f, divShipSec = 0f;
    static Map<String, float[]> intentStats = new java.util.TreeMap<>(); // alive, camp, inRange, firing, backoff, exposure, escort, mtSet, nearest
    static java.util.Set<String> firedThisTick = new java.util.HashSet<>();
    static Map<String, float[]> deathT = new java.util.TreeMap<>();
    static Map<String, float[]> intentDeaths = new java.util.TreeMap<>();

    public static class BattleOutcome {
        public int winnerSide; // 0 = Side 0 (Perfect), 1 = Side 1 (Test Org), -1 = Draw
        public float durationSeconds;
        public int side0SurvivingShips;
        public int side1SurvivingShips;
        public float side0SurvivingFp;
        public float side1SurvivingFp;
        public float side0HullIntegrityPct;
        public float side1HullIntegrityPct;
        public int side0OverloadsInflicted;
        public int side1OverloadsInflicted;
        public float side0FocusFireConcentration;
        public float side1FocusFireConcentration;
    }

    private static class ShipSpecTemplate {
        final String code;
        final String hullName;
        final HullSize size;
        final float fp;
        final float maxSpeed;
        final float engageRange;
        final int strikeAmmo;
        final int fighterBays;
        final DivisionArchetype naturalArchetype;
        final int naturalDivSlot;
        final float spawnX;
        final float spawnYOffset;

        ShipSpecTemplate(
                String code,
                String hullName,
                HullSize size,
                float fp,
                float maxSpeed,
                float engageRange,
                int strikeAmmo,
                int fighterBays,
                DivisionArchetype naturalArchetype,
                int naturalDivSlot,
                float spawnX,
                float spawnYOffset) {
            this.code = code;
            this.hullName = hullName;
            this.size = size;
            this.fp = fp;
            this.maxSpeed = maxSpeed;
            this.engageRange = engageRange;
            this.strikeAmmo = strikeAmmo;
            this.fighterBays = fighterBays;
            this.naturalArchetype = naturalArchetype;
            this.naturalDivSlot = naturalDivSlot;
            this.spawnX = spawnX;
            this.spawnYOffset = spawnYOffset;
        }
    }

    private static List<ShipSpecTemplate> buildIdentical20ShipFleetTemplate() {
        List<ShipSpecTemplate> list = new ArrayList<>(20);
        // Div 0: Battle Div 1 (2 Battleships, 50 FP) - Center Vanguard
        list.add(new ShipSpecTemplate("BB1", "Onslaught", HullSize.CAPITAL_SHIP, 25f, 55f, 1050f, 8, 0,
                DivisionArchetype.BATTLESHIP_DIVISION, 0, -240f, 0f));
        list.add(new ShipSpecTemplate("BB2", "Conquest", HullSize.CAPITAL_SHIP, 25f, 65f, 1150f, 10, 0,
                DivisionArchetype.BATTLESHIP_DIVISION, 0, 240f, 0f));

        // Div 1: Line Cruiser Div 1 (4 Line Cruisers, 48 FP) - Center Battle Line
        list.add(new ShipSpecTemplate("CA1", "Dominator", HullSize.CRUISER, 12f, 68f, 950f, 6, 0,
                DivisionArchetype.CRUISER_DIVISION, 1, -1080f, -60f));
        list.add(new ShipSpecTemplate("CA2", "Eagle", HullSize.CRUISER, 12f, 78f, 1000f, 4, 0,
                DivisionArchetype.CRUISER_DIVISION, 1, -720f, -60f));
        list.add(new ShipSpecTemplate("CA3", "Eagle", HullSize.CRUISER, 12f, 78f, 1000f, 4, 0,
                DivisionArchetype.CRUISER_DIVISION, 1, 720f, -60f));
        list.add(new ShipSpecTemplate("CA4", "Dominator", HullSize.CRUISER, 12f, 68f, 950f, 6, 0,
                DivisionArchetype.CRUISER_DIVISION, 1, 1080f, -60f));

        // Div 2: Carrier Div 1 (2 Cruisers w/ 3 Fighter Bays, 30 FP) - Rear Support
        list.add(new ShipSpecTemplate("CV1", "Heron", HullSize.CRUISER, 15f, 75f, 1450f, 0, 3,
                DivisionArchetype.LIGHT_CARRIER_DIVISION, 2, -380f, -480f));
        list.add(new ShipSpecTemplate("CV2", "Mora", HullSize.CRUISER, 15f, 62f, 1400f, 4, 3,
                DivisionArchetype.LIGHT_CARRIER_DIVISION, 2, 380f, -480f));

        // Div 3: Missile Cruiser Div 1 (4 Missile/Strike Cruisers, 44 FP) - Standoff Support
        list.add(new ShipSpecTemplate("MC1", "Gryphon", HullSize.CRUISER, 11f, 75f, 1180f, 16, 0,
                DivisionArchetype.MISSILE_CRUISER_DIVISION, 3, -1480f, -220f));
        list.add(new ShipSpecTemplate("MC2", "Falcon", HullSize.CRUISER, 11f, 95f, 980f, 10, 0,
                DivisionArchetype.MISSILE_CRUISER_DIVISION, 3, -1160f, -220f));
        list.add(new ShipSpecTemplate("MC3", "Falcon", HullSize.CRUISER, 11f, 95f, 980f, 10, 0,
                DivisionArchetype.MISSILE_CRUISER_DIVISION, 3, 1160f, -220f));
        list.add(new ShipSpecTemplate("MC4", "Gryphon", HullSize.CRUISER, 11f, 75f, 1180f, 16, 0,
                DivisionArchetype.MISSILE_CRUISER_DIVISION, 3, 1480f, -220f));

        // Div 4: Destroyer Div 1 (4 Escort Destroyers, 28 FP) - Forward Screen
        list.add(new ShipSpecTemplate("DD1", "Hammerhead", HullSize.DESTROYER, 7f, 98f, 820f, 6, 0,
                DivisionArchetype.DESTROYER_DIVISION, 4, -620f, 200f));
        list.add(new ShipSpecTemplate("DD2", "Enforcer", HullSize.DESTROYER, 7f, 84f, 800f, 8, 0,
                DivisionArchetype.DESTROYER_DIVISION, 4, -200f, 200f));
        list.add(new ShipSpecTemplate("DD3", "Enforcer", HullSize.DESTROYER, 7f, 84f, 800f, 8, 0,
                DivisionArchetype.DESTROYER_DIVISION, 4, 200f, 200f));
        list.add(new ShipSpecTemplate("DD4", "Hammerhead", HullSize.DESTROYER, 7f, 98f, 820f, 6, 0,
                DivisionArchetype.DESTROYER_DIVISION, 4, 620f, 200f));

        // Div 5: Frigate Div 1 (4 Fast Flankers, 16 FP) - Outer Wings
        list.add(new ShipSpecTemplate("FF1", "Wolf", HullSize.FRIGATE, 4f, 138f, 680f, 4, 0,
                DivisionArchetype.FRIGATE_DIVISION, 5, -1850f, 160f));
        list.add(new ShipSpecTemplate("FF2", "Lasher", HullSize.FRIGATE, 4f, 126f, 700f, 4, 0,
                DivisionArchetype.FRIGATE_DIVISION, 5, -1580f, 160f));
        list.add(new ShipSpecTemplate("FF3", "Lasher", HullSize.FRIGATE, 4f, 126f, 700f, 4, 0,
                DivisionArchetype.FRIGATE_DIVISION, 5, 1580f, 160f));
        list.add(new ShipSpecTemplate("FF4", "Wolf", HullSize.FRIGATE, 4f, 138f, 680f, 4, 0,
                DivisionArchetype.FRIGATE_DIVISION, 5, 1850f, 160f));

        return list;
    }

    private static Set<Integer> getOrganizedShipIndices(OrganizationRating rating) {
        Set<Integer> set = new HashSet<>();
        switch (rating) {
            case NONE:
                break;
            case LOW: // 25% of 20 = 5 ships (BB1, BB2 in Battle Div 1; CA1, CA2, CA3 in Line Cruiser Div 1)
                for (int idx : new int[]{0, 1, 2, 3, 4}) set.add(idx);
                break;
            case AVERAGE: // 50% of 20 = 10 ships (BB[2], CA[4], CV[2], DD[2])
                for (int idx : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 12, 13}) set.add(idx);
                break;
            case HIGH: // 80% of 20 = 16 ships (BB[2], CA[4], CV[2], MC[4], DD[4]; 4 Frigates independent)
                for (int idx = 0; idx < 16; idx++) set.add(idx);
                break;
            case VERY_HIGH:
                for (int idx = 0; idx < 18; idx++) set.add(idx);
                break;
            case PERFECT:
            default:
                for (int idx = 0; idx < 20; idx++) set.add(idx);
                break;
        }
        return set;
    }

    private static void configureGroupDoctrine(BattleGroupState group, boolean convergence) {
        group.useConvergence = convergence;
        group.forceConvergenceOverride = convergence;
        group.authority = convergence ? OtcAuthority.POSITIVE_CONTROL : OtcAuthority.COMMAND_BY_DIRECTION;
        group.fleetWideFormation = convergence ? FleetWideFormation.CRESCENT_HORNS : FleetWideFormation.GRAND_WEDGE_SPEARHEAD;
        group.multiDivisionSetpieceDoctrine = convergence ? MultiDivisionSetpiece.CONVERGENCE_KILL_BOX : MultiDivisionSetpiece.HAMMER_AND_ANVIL;
        group.missionContext = MissionContextType.FLEET_ACTION;
        group.plays.clear();
        group.plays.add(OtcPlay.LINE_AND_FIRE_DISTRIBUTION);
        group.plays.add(OtcPlay.DOUBLING);
        group.plays.add(OtcPlay.TIME_ON_TARGET_STRIKES);
        group.plays.add(OtcPlay.RESERVE_AND_GENERAL_CHASE);
    }

    private static void configureDivisionDoctrine(DivisionState div, boolean convergence) {
        div.useConvergence = convergence;
        div.forceConvergenceOverride = convergence;
        switch (div.archetype) {
            case BATTLESHIP_DIVISION:
                div.openingMission = DivisionMissionType.CLOSE_ACTION;
                div.mainMission = DivisionMissionType.CLOSE_ACTION;
                div.lineStation = LineStation.VAN;
                div.doctrinalFormation = DivisionFormation.FORWARD_WEDGE;
                div.activeFormation = DivisionFormation.FORWARD_WEDGE;
                div.standoffRange = 760f;
                div.stationSpacing = 480f;
                div.divisionInterval = 1000f;
                break;
            case CRUISER_DIVISION:
                div.openingMission = DivisionMissionType.CLOSE_ACTION;
                div.mainMission = DivisionMissionType.CLOSE_ACTION;
                div.lineStation = LineStation.CENTER;
                div.doctrinalFormation = DivisionFormation.LINE_ABREAST;
                div.activeFormation = DivisionFormation.LINE_ABREAST;
                div.standoffRange = 780f;
                div.stationSpacing = 440f;
                div.divisionInterval = 920f;
                break;
            case LIGHT_CARRIER_DIVISION:
                div.openingMission = DivisionMissionType.ARMED_RECONNAISSANCE;
                div.mainMission = DivisionMissionType.ALPHA_STRIKE;
                div.lineStation = LineStation.REAR;
                div.doctrinalFormation = DivisionFormation.LINE_ABREAST;
                div.activeFormation = DivisionFormation.LINE_ABREAST;
                div.standoffRange = 1380f;
                div.stationSpacing = 480f;
                div.divisionInterval = 1100f;
                break;
            case MISSILE_CRUISER_DIVISION:
                div.openingMission = DivisionMissionType.STANDOFF_ENGAGEMENT;
                div.mainMission = DivisionMissionType.TOT_ALPHA_STRIKE;
                div.lineStation = LineStation.CENTER;
                div.doctrinalFormation = DivisionFormation.LINE_ABREAST;
                div.activeFormation = DivisionFormation.LINE_ABREAST;
                div.standoffRange = 1020f;
                div.stationSpacing = 450f;
                div.divisionInterval = 960f;
                break;
            case DESTROYER_DIVISION:
                div.openingMission = DivisionMissionType.SCREEN;
                div.mainMission = DivisionMissionType.SCREEN;
                div.lineStation = LineStation.CENTER;
                div.doctrinalFormation = DivisionFormation.FORWARD_SEMICIRCLE;
                div.activeFormation = DivisionFormation.FORWARD_SEMICIRCLE;
                div.standoffRange = 740f;
                div.stationSpacing = 380f;
                div.divisionInterval = 860f;
                break;
            case FRIGATE_DIVISION:
            default:
                div.openingMission = DivisionMissionType.SCOUTING;
                div.mainMission = DivisionMissionType.ENVELOPMENT;
                div.lineStation = LineStation.VAN;
                div.doctrinalFormation = DivisionFormation.FORWARD_SEMICIRCLE;
                div.activeFormation = DivisionFormation.FORWARD_SEMICIRCLE;
                div.standoffRange = 660f;
                div.stationSpacing = 340f;
                div.divisionInterval = 800f;
                break;
        }
        div.stationKeepingRadius = DivisionAutoFormer.computeStationKeepingRadius(
                div.archetype,
                Math.max(1, div.members.size()),
                div.stationSpacing,
                1.0f,
                div.commander.stationKeepingStrictness);
    }

    /**
     * Mirrors DivisionAutoFormer.syncDivisionsForSide's member-pruning and flagship promotion
     * so divisions continue operating seamlessly when their initial flagship is destroyed.
     */
    private static void pruneDeadDivisionMembers(BattleGroupState group) {
        Iterator<DivisionState> divIt = group.divisions.iterator();
        while (divIt.hasNext()) {
            DivisionState div = divIt.next();
            Iterator<ShipAPI> memIt = div.members.iterator();
            while (memIt.hasNext()) {
                ShipAPI s = memIt.next();
                if (s == null || !s.isAlive() || s.isHulk()) {
                    memIt.remove();
                    if (s != null) {
                        group.shipIdToDivision.remove(s.getId());
                    }
                }
            }
            if (div.members.isEmpty()) {
                divIt.remove();
                continue;
            }
            if (div.flagship == null || !div.flagship.isAlive() || div.flagship.isHulk()) {
                div.flagship = div.members.get(0);
            }
        }
    }

    public static BattleOutcome runSingleBattle(
            OrganizationRating side0Org,
            OrganizationRating side1Org,
            boolean side0Convergence,
            boolean side1Convergence,
            long battleSeed) {

        Random rng = new Random(battleSeed * 6364136223846793005L + 1442695040888963407L);
        BattlefieldFirepowerEvaluator.resetForNewBattle();

        CommanderProfile cmdr0 = new CommanderProfile(null, side0Convergence ? "Alpha Core OTC" : "Admiral Steady");
        CommanderProfile cmdr1 = new CommanderProfile(null, side1Convergence ? "Alpha Core OTC" : "Admiral OpFor");
        if (side0Convergence) {
            cmdr0.stationKeepingStrictness = 1.15f;
            cmdr0.aggressionBias = 1.18f;
        }
        if (side1Convergence) {
            cmdr1.stationKeepingStrictness = 1.15f;
            cmdr1.aggressionBias = 1.18f;
        }

        BattleGroupState group0 = new BattleGroupState(0, cmdr0);
        BattleGroupState group1 = new BattleGroupState(1, cmdr1);
        configureGroupDoctrine(group0, side0Convergence);
        configureGroupDoctrine(group1, side1Convergence);

        SimArena arena = new SimArena("Tournament", "Large Fleet Matchup", group0, group1);
        List<ShipSpecTemplate> template = buildIdentical20ShipFleetTemplate();
        Map<String, Float> fpByShipId = new HashMap<>();

        float[][] jitter = new float[template.size()][4];
        for (float[] j : jitter) {
            j[0] = (rng.nextFloat() - 0.5f) * 140f;
            j[1] = (rng.nextFloat() - 0.5f) * 120f;
            j[2] = (rng.nextFloat() - 0.5f) * 320f;
            j[3] = (rng.nextFloat() - 0.5f) * 260f;
        }
        spawnFleetSide(arena, group0, 0, template, side0Org, side0Convergence, jitter, fpByShipId);
        spawnFleetSide(arena, group1, 1, template, side1Org, side1Convergence, jitter, fpByShipId);

        BattleOutcome outcome = new BattleOutcome();
        float maxDuration = MAX_BATTLE_SECONDS;
        float dt = 0.25f;
        int steps = Math.round(maxDuration / dt);

        List<DivisionState> contactPool0 = new ArrayList<>();
        List<DivisionState> contactPool1 = new ArrayList<>();
        BattleGroupState viewOf0For1 = new BattleGroupState(0, cmdr0);
        BattleGroupState viewOf1For0 = new BattleGroupState(1, cmdr1);

        float focusSamples0 = 0f, focusSum0 = 0f;
        float focusSamples1 = 0f, focusSum1 = 0f;

        for (int step = 0; step < steps; step++) {
            float now = arena.elapsedTime;
            int alive0 = 0, alive1 = 0;
            for (SimShipState s : arena.ships) {
                if (!s.alive) continue;
                if (s.owner == 0) alive0++;
                else alive1++;
            }
            if (DIAG) {
                if (alive0 < 20 && firstLoss[0] < 0) firstLoss[0] = now; if (alive1 < 20 && firstLoss[1] < 0) firstLoss[1] = now;
                if (alive0 <= 10 && halfLoss[0] < 0) halfLoss[0] = now; if (alive1 <= 10 && halfLoss[1] < 0) halfLoss[1] = now;
            }
            if (alive0 == 0 || alive1 == 0) {
                break;
            }

            if (step % 2 == 0) {
                pruneDeadDivisionMembers(group0);
                pruneDeadDivisionMembers(group1);

                // Alternate which side's AI runs first so neither OTC systematically reacts to fresher shared state.
                BattlefieldFirepowerEvaluator.updateBattlefieldRoses(arena.engineProxy, now);
                boolean side0First = ((step / 2) % 2) == 0;
                BattleGroupState[] order = side0First ? new BattleGroupState[]{group0, group1} : new BattleGroupState[]{group1, group0};
                for (BattleGroupState g : order) {
                    OtcAI.updateTelemetry(arena.engineProxy, g);
                }
                rebuildOpposingView(arena, group0, viewOf0For1, contactPool0, 0);
                rebuildOpposingView(arena, group1, viewOf1For0, contactPool1, 1);
                for (BattleGroupState g : order) {
                    if (vanillaOnlySide[g.side]) continue;
                    BattleGroupState enemyView = (g == group0) ? viewOf1For0 : viewOf0For1;
                    if ((now - g.lastOtcEvaluationTime) >= 1.5f || step == 0) {
                        if (!g.divisions.isEmpty() && !enemyView.divisions.isEmpty()) {
                            OtcAI.evaluateOtcStrategy(arena.engineProxy, g, enemyView, now);
                            g.lastOtcEvaluationTime = now;
                        } else if (!g.friendlyIndependentShips.isEmpty() && !enemyView.divisions.isEmpty()) {
                            OtcAI.updateFleetIndependentCommand(arena.engineProxy, g, enemyView, null, now);
                            g.lastOtcEvaluationTime = now;
                        }
                    }
                }
                for (BattleGroupState g : order) {
                    if (vanillaOnlySide[g.side]) continue;
                    BattleGroupState enemyView = (g == group0) ? viewOf1For0 : viewOf0For1;
                    if (!g.divisions.isEmpty() && !enemyView.divisions.isEmpty()) {
                        DivisionCommanderAI.evaluateAndExecuteDivisions(arena.engineProxy, g, enemyView, now);
                    } else if (!g.friendlyIndependentShips.isEmpty() && !enemyView.divisions.isEmpty()) {
                        OtcAI.applyFleetIndependentCommands(arena.engineProxy, g, enemyView, now);
                    }
                }
            }

            CUR_T = now;
            if (TRACE1 && step % 20 == 0 && now <= 70f) {
                System.out.printf(Locale.ROOT, "--- t=%.0f alive %d vs %d%n", now, alive0, alive1);
                for (DivisionState dv : (java.util.List<DivisionState>) (java.util.List<?>) concat(group0.divisions, group1.divisions)) {
                    int oppSide = 1 - dv.side;
                    float minD = Float.MAX_VALUE; int expo = 0;
                    for (ShipAPI m : dv.members) { SimShipState ms = null; for (SimShipState q : arena.ships) if (q.proxy == m) ms = q; if (ms == null || !ms.alive) continue;
                        for (SimShipState o : arena.ships) if (o.alive && o.owner == oppSide) { float d = dist(ms.location, o.location); minD = Math.min(minD, d); if (d <= o.engageRange*1.08f) expo++; } }
                    System.out.printf(Locale.ROOT, "  S%d %-22s n=%d flux=%.2f c=(%.0f,%.0f) intent=%s tgt=%s lfr=%.2f nearestEnemy=%.0f gunsOnDiv=%d stn=(%.0f,%.0f)%n",
                        dv.side, dv.displayName, dv.members.size(), dv.avgFluxLevel, dv.centroid.x, dv.centroid.y, dv.currentIntent,
                        dv.targetEnemyDivision != null ? dv.targetEnemyDivision.displayName + "(" + dv.targetEnemyDivision.members.size() + ")" : "-", dv.localForceRatio, minD, expo, dv.stationPoint.x, dv.stationPoint.y);
                }
                float sx=0, sy=0; int n=0; for (SimShipState q : arena.ships) if (q.alive && q.owner==1) { sx+=q.location.x; sy+=q.location.y; n++; }
                if (n>0) System.out.printf(Locale.ROOT, "  side1 centroid=(%.0f,%.0f) contacts=%d%n", sx/n, sy/n, viewOf1For0.divisions.size());
            }
            float[] focusStats = stepFullCombatPhysics(arena, group0, group1, dt, rng, outcome);
            if (focusStats[0] > 0f) {
                focusSum0 += focusStats[0];
                focusSamples0 += 1f;
            }
            if (focusStats[1] > 0f) {
                focusSum1 += focusStats[1];
                focusSamples1 += 1f;
            }
            arena.elapsedTime += dt;
        }

        if (STALL_DUMP) {
            int a0=0,a1=0; for (SimShipState q : arena.ships) if (q.alive) { if (q.owner==0) a0++; else a1++; }
            if (a0 > 0 && a1 > 0) {
                System.out.println("  STALL dump t=" + arena.elapsedTime);
                for (SimShipState q : arena.ships) { if (!q.alive) continue;
                    float nd = Float.MAX_VALUE; for (SimShipState o : arena.ships) if (o.alive && o.owner != q.owner) nd = Math.min(nd, dist(q.location, o.location));
                    BattleGroupState g = q.owner==0?group0:group1; DivisionState dv = g.shipIdToDivision.get(q.id);
                    System.out.printf(Locale.ROOT, "   %s pos=(%.0f,%.0f) hull=%.2f flux=%.2f rng=%.0f nearestEnemy=%.0f div=%s intent=%s camp=%s bo=%s dnp=%s mt=%s esc=%s cft=%s vsStage=%s vsMode=%s tgtDiv=%s strikeCd=%.1f ammo=%d bays=%d%n",
                        q.id, q.location.x, q.location.y, q.hullLevel, q.fluxLevel, q.engageRange, nd, dv==null?"-":dv.displayName, dv==null?"-":String.valueOf(dv.currentIntent),
                        q.aiFlags.hasFlag(AIFlags.CAMP_LOCATION), q.aiFlags.hasFlag(AIFlags.BACK_OFF), q.aiFlags.hasFlag(AIFlags.DO_NOT_PURSUE),
                        q.aiFlags.getCustom(AIFlags.MANEUVER_TARGET)!=null, q.aiFlags.getCustom(AIFlags.ESCORT_OTHER_SHIP)!=null, q.aiFlags.getCustom(AIFlags.CARRIER_FIGHTER_TARGET)!=null,
                        dv==null?"-":String.valueOf(dv.voidstrikeStage), dv==null?"-":String.valueOf(dv.voidstrikeCoordination), dv==null||dv.targetEnemyDivision==null?"-":dv.targetEnemyDivision.displayName, q.strikeCooldown, q.strikeAmmo, q.fighterBays);
                }
            }
        }
        outcome.durationSeconds = arena.elapsedTime;
        float totalHp0 = 0f, remHp0 = 0f;
        float totalHp1 = 0f, remHp1 = 0f;
        for (SimShipState s : arena.ships) {
            float fp = fpByShipId.containsKey(s.id) ? fpByShipId.get(s.id) : 5f;
            if (s.owner == 0) {
                totalHp0 += s.maxHullHp;
                if (s.alive) {
                    outcome.side0SurvivingShips++;
                    outcome.side0SurvivingFp += fp;
                    remHp0 += s.maxHullHp * Math.max(0f, s.hullLevel);
                }
            } else {
                totalHp1 += s.maxHullHp;
                if (s.alive) {
                    outcome.side1SurvivingShips++;
                    outcome.side1SurvivingFp += fp;
                    remHp1 += s.maxHullHp * Math.max(0f, s.hullLevel);
                }
            }
        }
        outcome.side0HullIntegrityPct = (totalHp0 > 0f) ? (remHp0 * 100f / totalHp0) : 0f;
        outcome.side1HullIntegrityPct = (totalHp1 > 0f) ? (remHp1 * 100f / totalHp1) : 0f;
        outcome.side0FocusFireConcentration = (focusSamples0 > 0f) ? (focusSum0 / focusSamples0) : 0f;
        outcome.side1FocusFireConcentration = (focusSamples1 > 0f) ? (focusSum1 / focusSamples1) : 0f;

        if (outcome.side0SurvivingShips > 0 && outcome.side1SurvivingShips == 0) {
            outcome.winnerSide = 0;
        } else if (outcome.side1SurvivingShips > 0 && outcome.side0SurvivingShips == 0) {
            outcome.winnerSide = 1;
        } else {
            outcome.winnerSide = -1; // INVALID: no decisive annihilation within the time cap
            invalidCount++;
        }
        return outcome;
    }

    private static void spawnFleetSide(
            SimArena arena,
            BattleGroupState group,
            int side,
            List<ShipSpecTemplate> template,
            OrganizationRating orgRating,
            boolean convergence,
            float[][] jitter,
            Map<String, Float> fpByShipId) {

        Set<Integer> orgIndices = getOrganizedShipIndices(orgRating);
        Map<Integer, DivisionState> divBySlot = new HashMap<>();
        String[] divNames = {
                "Battle Div. I",
                "Line Cruiser Div 1",
                "Carrier Div 1",
                "Missile Cruiser Div 1",
                "Destroyer Div 1",
                "Frigate Div 1"
        };

        float baseY = (side == 0) ? -1450f : 1450f;
        float dirY = (side == 0) ? 1f : -1f;

        for (int i = 0; i < template.size(); i++) {
            ShipSpecTemplate t = template.get(i);
            String id = (side == 0 ? "s0_" : "s1_") + t.code;
            String name = (side == 0 ? "HSS " : "OPS ") + t.hullName + " " + (i + 1);
            fpByShipId.put(id, t.fp);

            float jx = jitter[i][0];
            float jy = jitter[i][1];
            // Side 1 deploys as a 180-degree rotation of side 0 (its left wing is at +x when facing -y), matching
            // how OtcAI's deployment hijack places each division into its facing-relative fleet slot in the real game.
            // Exact point symmetry: side 1's deployment (including jitter) is side 0's rotated 180 degrees.
            float sgn = (side == 0) ? 1f : -1f;
            float x = sgn * (t.spawnX + jx);
            float y = baseY + dirY * (t.spawnYOffset + jy);

            if (orgIndices.contains(i)) {
                DivisionState div = divBySlot.get(t.naturalDivSlot);
                if (div == null) {
                    CommanderProfile dCmdr = new CommanderProfile(null, divNames[t.naturalDivSlot] + " Cmdr");
                    if (convergence) {
                        dCmdr.stationKeepingStrictness = 1.15f;
                        dCmdr.aggressionBias = 1.15f;
                    }
                    div = new DivisionState(
                            "div_" + side + "_" + t.naturalDivSlot,
                            side,
                            t.naturalDivSlot + 1,
                            t.naturalArchetype,
                            divNames[t.naturalDivSlot],
                            dCmdr);
                    divBySlot.put(t.naturalDivSlot, div);
                    group.divisions.add(div);
                }
                SimShipState s = arena.addShip(
                        div, id, name, t.hullName, t.size, x, y, t.maxSpeed, t.engageRange, t.strikeAmmo, t.fighterBays);
                s.enableDetailedCombatModel();
                group.shipIdToDivision.put(s.id, div);
            } else {
                // Independent ship: not in any division; starts with slightly greater deployment scatter
                float indepScatterX = sgn * jitter[i][2];
                float indepScatterY = dirY * jitter[i][3];
                SimShipState s = new SimShipState(
                        id, name, t.hullName, side, t.size, x + indepScatterX, y + indepScatterY,
                        t.maxSpeed, t.engageRange, t.strikeAmmo, t.fighterBays);
                s.enableDetailedCombatModel();
                arena.ships.add(s);
            }
        }

        arena.enableFogOfWar();
        for (DivisionState div : group.divisions) {
            DivisionAutoFormer.recomputeDivisionCapabilities(div);
            configureDivisionDoctrine(div, convergence);
        }
    }

    private static void rebuildOpposingView(
            SimArena arena,
            BattleGroupState realGroup,
            BattleGroupState outView,
            List<DivisionState> contactPool,
            int side) {
        outView.otc = realGroup.otc;
        outView.useConvergence = realGroup.useConvergence;
        outView.forceConvergenceOverride = realGroup.forceConvergenceOverride;
        outView.missionContext = realGroup.missionContext;
        outView.divisions.clear();
        outView.shipIdToDivision.clear();
        outView.divisions.addAll(realGroup.divisions);
        outView.shipIdToDivision.putAll(realGroup.shipIdToDivision);

        int observerSide = 1 - side;
        List<ShipAPI> unassigned = new ArrayList<>();
        for (SimShipState s : arena.ships) {
            if (s.alive && s.owner == side
                    && !realGroup.shipIdToDivision.containsKey(s.id)
                    && OtcAI.isShipVisibleToSide(arena.engineProxy, observerSide, s.proxy)) {
                unassigned.add(s.proxy);
            }
        }
        if (!unassigned.isEmpty()) {
            DivisionAICombatPlugin.populateSpatialContactClusters(
                    unassigned,
                    contactPool,
                    outView,
                    side,
                    "indep_contacts_" + side + "_",
                    "Independent Contact ");
        }
        if (!outView.divisions.isEmpty()) {
            OtcAI.updateTelemetry(arena.engineProxy, outView);
        }
    }

    private static float[] stepFullCombatPhysics(
            SimArena arena,
            BattleGroupState group0,
            BattleGroupState group1,
            float dt,
            Random rng,
            BattleOutcome outcome) {

        Map<ShipAPI, SimShipState> stateByProxy = new HashMap<>(arena.ships.size() * 2);
        for (SimShipState s : arena.ships) {
            stateByProxy.put(s.proxy, s);
            // Carrier fighter wings project sensor visibility ahead of their carrier (matching vanilla Ship.isWingLeader() in FogOfWar)
            if (s.alive && s.fighterBays > 0) {
                float fwdY = (s.owner == 0) ? 1f : -1f;
                float patrolX = s.location.x * 0.90f;
                float patrolY = s.location.y + fwdY * 1350f;
                arena.engineProxy.getFogOfWar(s.owner).revealAroundPoint("wing_" + s.id, patrolX, patrolY, 2800f);
            }
        }

        // 1. Movement & AI steering (decisions use a common position snapshot; integration is deferred)
        Map<SimShipState, Vector2f> pendingMove = new HashMap<>(arena.ships.size() * 2);
        for (SimShipState s : arena.ships) {
            if (!s.alive) continue;
            ShipwideAIFlags flags = s.aiFlags;
            BattleGroupState myGroup = (s.owner == 0) ? group0 : group1;
            boolean isInDivision = myGroup.shipIdToDivision.containsKey(s.id);
            if (DIAG) CUR_GROUP0 = group0;

            if (DIAG) {
                aliveSec[s.owner] += dt;
                float nearest = Float.MAX_VALUE;
                for (SimShipState o : arena.ships) if (o.alive && o.owner != s.owner) nearest = Math.min(nearest, dist(s.location, o.location));
                boolean inRange = nearest <= s.engageRange * 1.08f;
                boolean nearRange = nearest <= s.engageRange * 1.6f;
                if (nearRange && flags.hasFlag(AIFlags.CAMP_LOCATION)) campInRangeSec[s.owner] += dt;
                if (flags.hasFlag(AIFlags.BACK_OFF)) backOffSec[s.owner] += dt;
                if (flags.hasFlag(AIFlags.DO_NOT_BACK_OFF)) dnboSec[s.owner] += dt;
                int exp = 0; for (SimShipState o : arena.ships) if (o.alive && o.owner != s.owner && dist(s.location, o.location) <= o.engageRange * 1.08f) exp++;
                exposure[s.owner] += exp * dt;
                if (exp > 0 && !inRange) shotNoReply[s.owner] += dt;
                if (!inRange) idleInRangeSec[s.owner] += dt; // time out of gun range
                if (isInDivision && s.owner == 1) {
                    DivisionState dv1 = myGroup.shipIdToDivision.get(s.id);
                    if (dv1 != null) intentSec.merge("S1_" + dv1.archetype + ":" + dv1.currentIntent + "(phase=" + myGroup.currentPhase + ")", dt, Float::sum);
                }
                if (isInDivision && s.owner == 0) {
                    DivisionState dv = myGroup.shipIdToDivision.get(s.id);
                    if (dv != null) {
                        divShipSec += dt;
                        Object zt = dv.shipZoneTypeByShipId.get(s.id);
                        float[] zs = zoneStats.computeIfAbsent(String.valueOf(zt), k -> new float[2]);
                        zs[0] += dt;
                        if (firedThisTick.contains(s.id)) zs[1] += dt;
                        if (dv.shipRankStateByShipId.get(s.id) == divisionai.DivisionModel.ShipRankState.RELIEF_SAFE && dv.inDoubleLineRelief) reliefSec += dt;
                        if (dv.inDoubleLineRelief) doubleLineSec += dt;
                        if (dv.currentIntent == divisionai.DivisionModel.DivisionIntentType.HAUL_OUT_TO_VENT) {
                            String k = "front" + dv.engagementZoneShipCount + "/relief" + dv.safeZoneShipCount + (dv.crossfireCordonIndex >= 0.36f ? "/cordon" : "") + "/avgF" + (int)(dv.avgFluxLevel*10) + "/maxF" + (int)(dv.maxMemberFluxLevel*10);
                            haulWhy.merge(k, dt, Float::sum);
                        }
                        intentSec.merge(dv.archetype + ":" + dv.currentIntent, dt, Float::sum);
                        float[] st = intentStats.computeIfAbsent(String.valueOf(dv.currentIntent), k -> new float[9]);
                        st[0] += dt;
                        if (flags.hasFlag(AIFlags.CAMP_LOCATION)) st[1] += dt;
                        if (inRange) st[2] += dt;
                        if (firedThisTick.contains(s.id)) st[3] += dt;
                        if (flags.hasFlag(AIFlags.BACK_OFF)) st[4] += dt;
                        int ex = 0; for (SimShipState o : arena.ships) if (o.alive && o.owner != s.owner && dist(s.location, o.location) <= o.engageRange * 1.08f) ex++;
                        st[5] += ex * dt;
                        if (flags.getCustom(AIFlags.ESCORT_OTHER_SHIP) != null) st[6] += dt;
                        if (flags.getCustom(AIFlags.MANEUVER_TARGET) != null) st[7] += dt;
                        st[8] += Math.min(nearest, 5000f) * dt;
                    }
                }
            }
            if (s.overloadTimer > 0f) {
                s.overloadTimer = Math.max(0f, s.overloadTimer - dt);
                s.velocity.x *= (1f - 1.5f * dt);
                s.velocity.y *= (1f - 1.5f * dt);
                pendingMove.put(s, new Vector2f(s.velocity.x * dt, s.velocity.y * dt));
                flags.advance(dt);
                continue;
            }

            Vector2f desiredVel = new Vector2f(0f, 0f);
            boolean hasCamp = flags.hasFlag(AIFlags.CAMP_LOCATION);
            Object campObj = flags.getCustom(AIFlags.CAMP_LOCATION);
            Object escortObj = flags.getCustom(AIFlags.ESCORT_OTHER_SHIP);
            Object targetObj = flags.getCustom(AIFlags.MANEUVER_TARGET);
            boolean backOff = flags.hasFlag(AIFlags.BACK_OFF);
            boolean doNotBackOff = flags.hasFlag(AIFlags.DO_NOT_BACK_OFF);
            boolean doNotPursue = flags.hasFlag(AIFlags.DO_NOT_PURSUE);

            // Simple Fleet-Wide Command reception: independent ships under a coarse OTC general signal
            // respond with a 20% command-lag straggle rate (no division-level focus-fire or rank-depth relief).
            if (!isInDivision && myGroup.activeIndependentCommand != divisionai.DivisionModel.FleetIndependentCommand.NONE) {
                int pHashC = neutralHash(s.id);
                if ((pHashC % 10) < 2) {
                    hasCamp = false;
                    doNotPursue = false;
                }
            }

            if (!isInDivision) {
                // Independent ship running vanilla BasicShipAI:
                // Each independent captain has their own personality threshold based on ship ID hash
                int pHash = neutralHash(s.id);
                float captainBackOffFlux = 0.72f + (pHash % 15) * 0.01f; // 0.72..0.86
                float captainRangeMult = 0.78f + ((pHash / 17) % 16) * 0.01f; // 0.78..0.93
                backOff = s.fluxLevel >= captainBackOffFlux || (s.hullLevel < 0.38f && s.fluxLevel >= 0.52f);

                // Sticky individual target selection (strictly respecting Fog of War: cannot see or lock unspotted enemies!)
                SimShipState curTarget = (s.shipTarget != null) ? stateByProxy.get(s.shipTarget) : null;
                if (curTarget != null && (!curTarget.alive || !arena.isEntityVisibleToSide(s.owner, curTarget.proxy))) {
                    curTarget = null;
                }
                if (curTarget == null || dist(s.location, curTarget.location) > s.engageRange * 1.45f) {
                    SimShipState bestIndepTarget = null;
                    float bestIndepScore = -Float.MAX_VALUE;
                    for (SimShipState other : arena.ships) {
                        if (!other.alive || other.owner == s.owner) continue;
                        if (!arena.isEntityVisibleToSide(s.owner, other.proxy)) continue;
                        float d = dist(s.location, other.location);
                        // Individual captain picks local visible opponent in front of them (with per-ship tiebreaker, no fleet hive-mind)
                        float personalBias = ((pHash ^ neutralHash(other.id)) & 127) * 1.8f;
                        float score = -d * 0.95f + personalBias;
                        if (score > bestIndepScore) {
                            bestIndepScore = score;
                            bestIndepTarget = other;
                        }
                    }
                    curTarget = bestIndepTarget;
                    s.shipTarget = (curTarget != null) ? curTarget.proxy : null;
                }

                if (flags.hasFlag(AIFlags.BACK_OFF)) {
                    backOff = true;
                }
                Vector2f indepCampGoal = (hasCamp && campObj instanceof Vector2f) ? (Vector2f) campObj : null;
                if (indepCampGoal != null) {
                    float dx = indepCampGoal.x - s.location.x;
                    float dy = indepCampGoal.y - s.location.y;
                    float distCamp = (float) Math.sqrt(dx * dx + dy * dy);
                    if (distCamp > 25f) {
                        float speedFactor = Math.min(1f, distCamp / 200f);
                        if (backOff && curTarget != null && curTarget.alive) {
                            float toEnemyX = curTarget.location.x - s.location.x;
                            float toEnemyY = curTarget.location.y - s.location.y;
                            if (dx * toEnemyX + dy * toEnemyY > 0f) {
                                speedFactor *= 0.25f;
                            }
                        }
                        desiredVel.x += (dx / distCamp) * s.maxSpeed * speedFactor;
                        desiredVel.y += (dy / distCamp) * s.maxSpeed * speedFactor;
                    }
                    if (!backOff && curTarget != null && curTarget.alive && distCamp <= 360f
                            && dist(s.location, curTarget.location) <= s.engageRange * 1.15f) {
                        Vector2f combatVel = new Vector2f(0f, 0f);
                        steerShipAtCombatTarget(s, curTarget.location, s.engageRange * captainRangeMult, false, combatVel);
                        desiredVel.x = desiredVel.x * 0.60f + combatVel.x * 0.40f;
                        desiredVel.y = desiredVel.y * 0.60f + combatVel.y * 0.40f;
                    }
                } else if (curTarget != null && curTarget.alive) {
                    steerShipAtCombatTarget(s, curTarget.location, s.engageRange * captainRangeMult, backOff, desiredVel);
                } else if (!backOff) {
                    // No enemy visible in Fog of War yet: independent captain advances along search lane toward enemy side
                    float advDirY = (s.owner == 0) ? 1f : -1f;
                    float laneBiasX = (((pHash >> 3) & 31) - 15.5f) * 0.010f - (s.location.x / 18000f) * 0.22f;
                    float len = (float) Math.sqrt(laneBiasX * laneBiasX + advDirY * advDirY);
                    desiredVel.x += (laneBiasX / len) * s.maxSpeed * 0.88f;
                    desiredVel.y += (advDirY / len) * s.maxSpeed * 0.88f;
                }
            } else {
                // Division-controlled ship:
                // 1) If CAMP_LOCATION or ESCORT_OTHER_SHIP is active, dress formation / move to tactical station
                // 2) Otherwise, engage MANEUVER_TARGET / shipTarget at weapon standoff range (never ramming to 0u!)
                Vector2f stationGoal = null;
                if (hasCamp && campObj instanceof Vector2f) {
                    stationGoal = (Vector2f) campObj;
                } else if (escortObj instanceof ShipAPI) {
                    stationGoal = ((ShipAPI) escortObj).getLocation();
                }

                SimShipState combatTarget = null;
                if (targetObj instanceof ShipAPI) {
                    combatTarget = stateByProxy.get((ShipAPI) targetObj);
                }
                if ((combatTarget == null || !combatTarget.alive) && s.shipTarget != null) {
                    combatTarget = stateByProxy.get(s.shipTarget);
                }

                if (!doNotBackOff && (s.fluxLevel >= 0.74f || (s.hullLevel < 0.38f && s.fluxLevel >= 0.52f))) {
                    backOff = true;
                }

                if (stationGoal != null) {
                    float dx = stationGoal.x - s.location.x;
                    float dy = stationGoal.y - s.location.y;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dist > 25f) {
                        float speedFactor = Math.min(1f, dist / 200f);
                        // If backing off at high flux and stationGoal is not behind us, yield to threat repulsion
                        if (backOff && combatTarget != null && combatTarget.alive) {
                            float toEnemyX = combatTarget.location.x - s.location.x;
                            float toEnemyY = combatTarget.location.y - s.location.y;
                            if (dx * toEnemyX + dy * toEnemyY > 0f) {
                                speedFactor *= 0.25f;
                            }
                        }
                        desiredVel.x += (dx / dist) * s.maxSpeed * speedFactor;
                        desiredVel.y += (dy / dist) * s.maxSpeed * speedFactor;
                    }
                    if (!backOff && combatTarget != null && combatTarget.alive && dist <= 360f
                            && dist(s.location, combatTarget.location) <= s.engageRange * 1.15f) {
                        Vector2f combatVel = new Vector2f(0f, 0f);
                        steerShipAtCombatTarget(s, combatTarget.location, s.engageRange * 0.88f, false, combatVel);
                        desiredVel.x = desiredVel.x * 0.60f + combatVel.x * 0.40f;
                        desiredVel.y = desiredVel.y * 0.60f + combatVel.y * 0.40f;
                    }
                } else if (combatTarget != null && combatTarget.alive
                        && (!doNotPursue || dist(s.location, combatTarget.location) <= s.engageRange * 1.35f)) {
                    float rangeMult = flags.hasFlag(AIFlags.HARASS_MOVE_IN) ? 0.78f : 0.86f;
                    steerShipAtCombatTarget(s, combatTarget.location, s.engageRange * rangeMult, backOff, desiredVel);
                }
            }

            // Local enemy threat repulsion when not ordered DO_NOT_BACK_OFF
            if (!doNotBackOff) {
                for (SimShipState other : arena.ships) {
                    if (!other.alive || other.owner == s.owner) continue;
                    float dx = s.location.x - other.location.x;
                    float dy = s.location.y - other.location.y;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    float dangerRadius = backOff ? (other.engageRange * 1.05f) : (other.engageRange * 0.65f);
                    if (dist > 1f && dist < dangerRadius) {
                        float repelStrength = ((dangerRadius - dist) / dangerRadius) * s.maxSpeed * (backOff ? 1.50f : 0.70f);
                        desiredVel.x += (dx / dist) * repelStrength;
                        desiredVel.y += (dy / dist) * repelStrength;
                    }
                }
            }

            // Friendly ship collision-avoidance separation
            for (SimShipState ally : arena.ships) {
                if (!ally.alive || ally == s || ally.owner != s.owner) continue;
                float dx = s.location.x - ally.location.x;
                float dy = s.location.y - ally.location.y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > 1f && dist < 165f) {
                    float push = ((165f - dist) / 165f) * s.maxSpeed * 0.65f;
                    desiredVel.x += (dx / dist) * push;
                    desiredVel.y += (dy / dist) * push;
                }
            }

            float dSpeed = (float) Math.sqrt(desiredVel.x * desiredVel.x + desiredVel.y * desiredVel.y);
            if (dSpeed > s.maxSpeed) {
                desiredVel.x = (desiredVel.x / dSpeed) * s.maxSpeed;
                desiredVel.y = (desiredVel.y / dSpeed) * s.maxSpeed;
            }

            float blend = Math.min(1f, dt * 2.8f);
            s.velocity.x += (desiredVel.x - s.velocity.x) * blend;
            s.velocity.y += (desiredVel.y - s.velocity.y) * blend;
            pendingMove.put(s, new Vector2f(s.velocity.x * dt, s.velocity.y * dt));

            // Orient hull toward active combat target when in/near range so weapon arcs bear on the enemy;
            // if faceTarget is out of gun range or unset (e.g. while station-keeping or kiting), face the nearest
            // enemy within weapon range (matching Starsector's strafe-and-fire ship orientation), otherwise face velocity.
            SimShipState faceTarget = null;
            if (targetObj instanceof ShipAPI) faceTarget = stateByProxy.get((ShipAPI) targetObj);
            if ((faceTarget == null || !faceTarget.alive) && s.shipTarget != null) faceTarget = stateByProxy.get(s.shipTarget);
            if (faceTarget == null || !faceTarget.alive || dist(s.location, faceTarget.location) > s.engageRange * 1.12f) {
                SimShipState nearestInRange = null;
                float bestD = s.engageRange * 1.25f;
                for (SimShipState other : arena.ships) {
                    if (!other.alive || other.owner == s.owner) continue;
                    float d = dist(s.location, other.location);
                    if (d < bestD) {
                        bestD = d;
                        nearestInRange = other;
                    }
                }
                if (nearestInRange != null) {
                    faceTarget = nearestInRange;
                }
            }
            float desiredFacing = s.facing;
            if (faceTarget != null && faceTarget.alive && dist(s.location, faceTarget.location) <= s.engageRange * 1.45f) {
                desiredFacing = (float) Math.toDegrees(Math.atan2(
                        faceTarget.location.y - s.location.y,
                        faceTarget.location.x - s.location.x));
            } else if (dSpeed > 5f) {
                desiredFacing = (float) Math.toDegrees(Math.atan2(s.velocity.y, s.velocity.x));
            }
            float angDiff = shortestAngleDiff(s.facing, desiredFacing);
            float maxTurnRate = (s.hullSize == HullSize.CAPITAL_SHIP) ? 32f
                    : (s.hullSize == HullSize.CRUISER ? 52f : (s.hullSize == HullSize.DESTROYER ? 78f : 115f));
            float turnStep = Math.max(-maxTurnRate * dt, Math.min(maxTurnRate * dt, angDiff));
            s.angularVelocity = turnStep / dt;
            s.facing = normalizeAngle(s.facing + turnStep);

            if (s.strikeCooldown > 0f) {
                s.strikeCooldown = Math.max(0f, s.strikeCooldown - dt);
            }
            for (SimWeaponMount m : s.weaponMounts) {
                if (m.cooldownRemaining > 0f) m.cooldownRemaining = Math.max(0f, m.cooldownRemaining - dt);
                if (m.disabledTimer > 0f) m.disabledTimer = Math.max(0f, m.disabledTimer - dt);
            }
        }

        for (Map.Entry<SimShipState, Vector2f> e : pendingMove.entrySet()) {
            e.getKey().location.x += e.getValue().x;
            e.getKey().location.y += e.getValue().y;
        }

        if (DIAG) firedThisTick.clear();
        // 2. Weapon Firing, Focus-Fire Target Selection, Mutual PD vs Strikes, and Damage/Flux Application
        Map<SimShipState, Integer> attackersOnTarget = new HashMap<>();
        List<Object[]> pendingHits = new ArrayList<>();
        int totalShooters0 = 0, maxAttackersOnSingleTarget0 = 0;
        int totalShooters1 = 0, maxAttackersOnSingleTarget1 = 0;

        for (SimShipState attacker : arena.ships) {
            if (!attacker.alive || attacker.overloadTimer > 0f) continue;
            ShipwideAIFlags flags = attacker.aiFlags;
            BattleGroupState myGroup = (attacker.owner == 0) ? group0 : group1;
            boolean isInDivision = myGroup.shipIdToDivision.containsKey(attacker.id);

            if (attacker.fluxLevel >= 0.88f && !flags.hasFlag(AIFlags.DO_NOT_BACK_OFF)) {
                continue;
            }

            SimShipState assignedTarget = null;
            Object mt = flags.getCustom(AIFlags.MANEUVER_TARGET);
            if (mt instanceof ShipAPI) {
                assignedTarget = stateByProxy.get((ShipAPI) mt);
            }
            if ((assignedTarget == null || !assignedTarget.alive) && attacker.shipTarget != null) {
                assignedTarget = stateByProxy.get(attacker.shipTarget);
            }

            SimShipState chosenGunTarget = null;
            float effRange = attacker.engageRange * 1.08f;
            if (assignedTarget != null && assignedTarget.alive && arena.isEntityVisibleToSide(attacker.owner, assignedTarget.proxy)) {
                float d = dist(attacker.location, assignedTarget.location);
                if (d <= effRange) {
                    chosenGunTarget = assignedTarget;
                }
            }
            if (chosenGunTarget == null) {
                float bestScore = -Float.MAX_VALUE;
                for (SimShipState enemy : arena.ships) {
                    if (!enemy.alive || enemy.owner == attacker.owner) continue;
                    if (!arena.isEntityVisibleToSide(attacker.owner, enemy.proxy)) continue;
                    float d = dist(attacker.location, enemy.location);
                    if (d > effRange) continue;
                    // Division ships share tactical data-link fire prioritization; independent ships shoot their nearest local threat
                    float score = isInDivision
                            ? (-d * 0.35f + (enemy.overloadTimer > 0f ? 620f : 0f) + enemy.fluxLevel * 380f)
                            : (-d * 0.90f + (enemy.overloadTimer > 0f ? 220f : 0f));
                    if (score > bestScore) {
                        bestScore = score;
                        chosenGunTarget = enemy;
                    }
                }
            }

            if (chosenGunTarget != null) {
                float dTarget = dist(attacker.location, chosenGunTarget.location);
                float bearingToTarget = (float) Math.toDegrees(Math.atan2(
                        chosenGunTarget.location.y - attacker.location.y,
                        chosenGunTarget.location.x - attacker.location.x));
                boolean anyMountInRangeAndArc = false;
                float tickGunDmg = 0f;
                float tickFluxGen = 0f;

                for (SimWeaponMount m : attacker.weaponMounts) {
                    if (m.isStrike) continue;
                    if (m.isPd && !m.isHybridFlak) continue;
                    if (m.disabled || m.disabledTimer > 0f) continue;
                    if (m.usesAmmo && m.ammo <= 0) continue;
                    if (dTarget > m.range * 1.08f) continue;

                    float mountWorldFacing = normalizeAngle(attacker.facing + m.arcFacingRelDeg);
                    float offAxis = Math.abs(shortestAngleDiff(mountWorldFacing, bearingToTarget));
                    if (offAxis > m.arcDeg * 0.5f + 12f) continue;

                    anyMountInRangeAndArc = true;
                    if (m.cooldownRemaining <= 0f) {
                        m.cooldownRemaining = m.cooldownDuration;
                        if (m.usesAmmo) m.ammo = Math.max(0, m.ammo - 1);
                        tickGunDmg += m.burstDamage;
                        tickFluxGen += m.fluxPerShot;
                        pendingHits.add(new Object[]{chosenGunTarget, m.burstDamage, Boolean.TRUE, m.damageType});
                    }
                }

                if (anyMountInRangeAndArc) {
                    int c = attackersOnTarget.containsKey(chosenGunTarget) ? attackersOnTarget.get(chosenGunTarget) + 1 : 1;
                    attackersOnTarget.put(chosenGunTarget, c);
                    if (attacker.owner == 0) {
                        totalShooters0++;
                        if (c > maxAttackersOnSingleTarget0) maxAttackersOnSingleTarget0 = c;
                    } else {
                        totalShooters1++;
                        if (c > maxAttackersOnSingleTarget1) maxAttackersOnSingleTarget1 = c;
                    }
                    if (DIAG) {
                        fireSec[attacker.owner] += dt;
                        dmgDealt[attacker.owner] += tickGunDmg;
                        firedThisTick.add(attacker.id);
                    }
                    if (tickFluxGen > 0f) {
                        attacker.fluxLevel = Math.min(0.95f, attacker.fluxLevel + tickFluxGen / attacker.maxFluxCap);
                    }
                }
            }

            // Strike Missiles & Carrier Bomber Strikes
            SimShipState strikeTarget = assignedTarget;
            Object cft = flags.getCustom(AIFlags.CARRIER_FIGHTER_TARGET);
            if (cft instanceof ShipAPI) {
                SimShipState cftState = stateByProxy.get((ShipAPI) cft);
                if (cftState != null && cftState.alive) {
                    strikeTarget = cftState;
                }
            }
            if (strikeTarget == null || !strikeTarget.alive) {
                strikeTarget = chosenGunTarget;
            }
            if ((strikeTarget == null || !strikeTarget.alive) && attacker.fighterBays > 0) {
                float bestD = 3400f;
                for (SimShipState enemy : arena.ships) {
                    if (!enemy.alive || enemy.owner == attacker.owner) continue;
                    if (!arena.isEntityVisibleToSide(attacker.owner, enemy.proxy)) continue;
                    float d = dist(attacker.location, enemy.location);
                    if (d < bestD) { bestD = d; strikeTarget = enemy; }
                }
            }

            if (strikeTarget != null && strikeTarget.alive && arena.isEntityVisibleToSide(attacker.owner, strikeTarget.proxy)) {
                float dStrike = dist(attacker.location, strikeTarget.location);
                // Defending Mutual PD screen around strikeTarget (friendly ships within 680u using live non-disabled PD mounts)
                float mutualPd = 0f;
                for (SimShipState defenderAlly : arena.ships) {
                    if (!defenderAlly.alive || defenderAlly.owner != strikeTarget.owner || defenderAlly.overloadTimer > 0f) {
                        continue;
                    }
                    float dAlly = dist(defenderAlly.location, strikeTarget.location);
                    if (dAlly <= 680f) {
                        float falloff = 1.0f - (dAlly / 850f) * 0.55f;
                        float livePd = 0f;
                        for (SimWeaponMount pm : defenderAlly.weaponMounts) {
                            if (pm.isPd && !pm.disabled && pm.disabledTimer <= 0f) {
                                livePd += pm.sustainedDps;
                            }
                        }
                        mutualPd += livePd * falloff;
                    }
                }

                for (SimWeaponMount sm : attacker.weaponMounts) {
                    if (!sm.isStrike || sm.disabled || sm.disabledTimer > 0f) continue;
                    if (sm.usesAmmo && sm.ammo <= 0) continue;
                    if (sm.cooldownRemaining > 0f || dStrike > sm.range) continue;

                    float bearingToStrike = (float) Math.toDegrees(Math.atan2(
                            strikeTarget.location.y - attacker.location.y,
                            strikeTarget.location.x - attacker.location.x));
                    float mountWorldFacing = normalizeAngle(attacker.facing + sm.arcFacingRelDeg);
                    if (Math.abs(shortestAngleDiff(mountWorldFacing, bearingToStrike)) > sm.arcDeg * 0.5f + 25f) {
                        continue;
                    }

                    boolean totWindow = flags.hasFlag(AIFlags.DO_NOT_BACK_OFF)
                            || strikeTarget.fluxLevel >= 0.62f
                            || strikeTarget.overloadTimer > 0f
                            || rng.nextFloat() < 0.25f;
                    if (totWindow) {
                        if (sm.usesAmmo) {
                            sm.ammo = Math.max(0, sm.ammo - 1);
                            attacker.strikeAmmo = sm.ammo;
                        }
                        sm.cooldownRemaining = sm.cooldownDuration;
                        float pdMitigation = Math.min(0.78f, mutualPd / (mutualPd + 260f));
                        float netMissileDmg = sm.burstDamage * (1.0f - pdMitigation);
                        pendingHits.add(new Object[]{strikeTarget, netMissileDmg, Boolean.FALSE, sm.damageType});
                    }
                }

                if (attacker.fighterBays > 0 && attacker.strikeCooldown <= 0f && dStrike <= 3400f) {
                    attacker.strikeCooldown = 5.5f;
                    float rawBomberDmg = attacker.fighterBays * 460f;
                    float pdMitigation = Math.min(0.82f, mutualPd / (mutualPd + 310f));
                    float netBomberDmg = rawBomberDmg * (1.0f - pdMitigation);
                    pendingHits.add(new Object[]{strikeTarget, netBomberDmg, Boolean.FALSE, DamageType.HIGH_EXPLOSIVE});
                }
            }
        }

        // Resolve all hits simultaneously: a ship destroyed this tick still got its own shots off.
        for (Object[] h : pendingHits) {
            applyIncomingDamage((SimShipState) h[0], (Float) h[1], (Boolean) h[2], (DamageType) h[3], outcome);
        }

        // 3. Flux Dissipation & Venting
        for (SimShipState s : arena.ships) {
            if (!s.alive) continue;
            boolean underFire = attackersOnTarget.containsKey(s);
            boolean backingOff = s.aiFlags.hasFlag(AIFlags.BACK_OFF) || (!underFire && s.fluxLevel > 0.50f);
            float dissRate = s.fluxDissipation * (backingOff && !underFire ? 1.85f : (underFire ? 0.72f : 1.15f));
            s.fluxLevel = Math.max(0.02f, s.fluxLevel - (dissRate * dt) / s.maxFluxCap);
            if (!underFire) {
                s.hardFluxFraction = Math.max(0.15f, s.hardFluxFraction - 0.12f * dt);
            }
            s.aiFlags.advance(dt);
        }

        float conc0 = (totalShooters0 >= 2) ? ((float) maxAttackersOnSingleTarget0 / totalShooters0) : 0f;
        float conc1 = (totalShooters1 >= 2) ? ((float) maxAttackersOnSingleTarget1 / totalShooters1) : 0f;
        return new float[]{conc0, conc1};
    }

    private static void steerShipAtCombatTarget(
            SimShipState s,
            Vector2f targetLoc,
            float desiredRange,
            boolean backOff,
            Vector2f outVel) {
        float dx = targetLoc.x - s.location.x;
        float dy = targetLoc.y - s.location.y;
        float dist = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
        if (!backOff) {
            if (dist > desiredRange + 65f) {
                outVel.x += (dx / dist) * s.maxSpeed;
                outVel.y += (dy / dist) * s.maxSpeed;
            } else if (dist < desiredRange - 120f) {
                outVel.x -= (dx / dist) * s.maxSpeed * 0.55f;
                outVel.y -= (dy / dist) * s.maxSpeed * 0.55f;
            } else {
                float sign = ((neutralHash(s.id) & 1) == 0) ? 1f : -1f;
                outVel.x += (-dy / dist) * s.maxSpeed * 0.42f * sign;
                outVel.y += (dx / dist) * s.maxSpeed * 0.42f * sign;
            }
        }
    }

    private static float normalizeAngle(float deg) {
        float r = deg % 360f;
        if (r < 0f) r += 360f;
        return r;
    }

    private static float shortestAngleDiff(float fromDeg, float toDeg) {
        float diff = (toDeg - fromDeg) % 360f;
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;
        return diff;
    }

    private static void applyIncomingDamage(
            SimShipState target,
            float dmg,
            boolean isGunHit,
            DamageType dmgType,
            BattleOutcome outcome) {
        if (!target.alive || dmg <= 0f) return;
        float hullBefore = target.hullLevel;
        if (DIAG && !isGunHit) strikeDmgDealt[1 - target.owner] += dmg;

        float shieldMult = 1.0f;
        float armorHullMult = 1.0f;
        if (dmgType == DamageType.KINETIC) {
            shieldMult = 1.25f;
            armorHullMult = 0.75f;
        } else if (dmgType == DamageType.HIGH_EXPLOSIVE) {
            shieldMult = 0.78f;
            armorHullMult = 1.28f;
        } else if (dmgType == DamageType.FRAGMENTATION) {
            shieldMult = 0.45f;
            armorHullMult = (target.armorIntegrity < 0.45f) ? 1.15f : 0.50f;
        }

        if (target.overloadTimer > 0f) {
            float effDmg = dmg * armorHullMult * (1.12f - 0.32f * target.armorIntegrity);
            target.armorIntegrity = Math.max(0.08f, target.armorIntegrity - (effDmg * 0.45f) / Math.max(800f, target.armorRating * 8f));
            target.hullLevel -= effDmg / target.maxHullHp;
            // Heavy unshielded hits can temporarily knock out a weapon mount
            if (effDmg >= 340f && !target.weaponMounts.isEmpty()) {
                int mIdx = Math.abs((int) (target.hullLevel * 997f)) % target.weaponMounts.size();
                SimWeaponMount m = target.weaponMounts.get(mIdx);
                if (m.disabledTimer <= 0f) {
                    m.disabledTimer = 4.2f;
                }
            }
        } else {
            float shieldDmg = dmg * 0.86f * shieldMult * target.shieldFluxPerDam;
            float hullBleed = dmg * 0.14f * armorHullMult * (1.08f - 0.35f * target.armorIntegrity);
            target.fluxLevel += shieldDmg / target.maxFluxCap;
            target.hardFluxFraction = Math.min(0.92f, target.hardFluxFraction + (isGunHit ? 0.015f : 0.045f));
            target.armorIntegrity = Math.max(0.12f, target.armorIntegrity - (hullBleed * 0.35f) / Math.max(800f, target.armorRating * 8f));
            target.hullLevel -= hullBleed / target.maxHullHp;

            if (target.fluxLevel >= 0.96f) {
                target.fluxLevel = 0.65f;
                target.overloadTimer = 5.2f;
                if (DIAG) ovl[target.owner] += 1;
                if (DIAG && target.owner == 0) {
                    {
                        String k;
                        divisionai.DivisionModel.DivisionState dvv = (CUR_GROUP0 != null) ? CUR_GROUP0.shipIdToDivision.get(target.id) : null;
                        if (dvv == null) k = "independent";
                        else k = dvv.currentIntent + "/" + dvv.shipZoneTypeByShipId.get(target.id) + "/" + dvv.shipRankStateByShipId.get(target.id)
                                + (target.aiFlags.hasFlag(AIFlags.DO_NOT_BACK_OFF) ? "/DNBO" : "") + (target.aiFlags.hasFlag(AIFlags.BACK_OFF) ? "/BO" : "");
                        ovlWhy.merge(k, 1f, Float::sum);
                    }
                }
                if (target.owner == 0) {
                    outcome.side1OverloadsInflicted++;
                } else {
                    outcome.side0OverloadsInflicted++;
                }
            }
        }
        if (target.hullLevel <= 0f) {
            target.hullLevel = 0f;
            target.alive = false;
        }
        if (DIAG) hullDmgTaken[target.owner] += (hullBefore - target.hullLevel) * target.maxHullHp;
        if (DIAG && !target.alive && hullBefore > 0f) {
            String k = target.id.substring(0, 2) + target.id.substring(3, 5);
            float[] v = deathT.computeIfAbsent(k, kk -> new float[2]); v[0] += CUR_T; v[1] += 1;
        }
    }

    private static List<DivisionState> concat(List<DivisionState> a, List<DivisionState> b) { List<DivisionState> r = new ArrayList<>(a); r.addAll(b); return r; }

    /** Side-neutral ship key ("s0_BB1" and "s1_BB1" -> "BB1") so captain personalities are identical on both sides. */
    private static int neutralHash(String id) {
        String k = (id.length() > 3 && id.charAt(0) == 's' && id.charAt(2) == '_') ? id.substring(3) : id;
        return Math.abs(k.hashCode());
    }

    private static float dist(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    static float maxTimeSeen = 0f;

    private static BattleOutcome swapSides(BattleOutcome o) {
        BattleOutcome r = new BattleOutcome();
        r.winnerSide = (o.winnerSide == 0) ? 1 : (o.winnerSide == 1 ? 0 : -1);
        r.durationSeconds = o.durationSeconds;
        r.side0SurvivingShips = o.side1SurvivingShips; r.side1SurvivingShips = o.side0SurvivingShips;
        r.side0SurvivingFp = o.side1SurvivingFp; r.side1SurvivingFp = o.side0SurvivingFp;
        r.side0HullIntegrityPct = o.side1HullIntegrityPct; r.side1HullIntegrityPct = o.side0HullIntegrityPct;
        r.side0OverloadsInflicted = o.side1OverloadsInflicted; r.side1OverloadsInflicted = o.side0OverloadsInflicted;
        r.side0FocusFireConcentration = o.side1FocusFireConcentration; r.side1FocusFireConcentration = o.side0FocusFireConcentration;
        return r;
    }

    private static void runBracket(
            String bracketTitle,
            boolean side0Conv,
            boolean side1Conv) {
        System.out.println("======================================================================================================");
        System.out.println("  " + bracketTitle);
        System.out.println("  Side 0 (PERFECT 100% Org, OTC=" + (side0Conv ? "Convergence" : "Human") + ") vs Side 1 (Test Org, OTC=" + (side1Conv ? "Convergence" : "Human") + ")");
        System.out.println("======================================================================================================");
        System.out.printf(Locale.ROOT,
                "%-22s | %-8s | %-13s | %-13s | %-10s | %-10s | %-11s | %-7s%n",
                "Matchup (10 Battles)", "PERF W-L-I", "S0 Surv FP", "S1 Surv FP", "S0 Hull %", "S1 Hull %", "Focus Fire", "AvgTime");
        System.out.println("------------------------------------------------------------------------------------------------------");

        OrganizationRating[] oppRatings = {
                OrganizationRating.NONE,
                OrganizationRating.LOW,
                OrganizationRating.AVERAGE,
                OrganizationRating.HIGH
        };

        int totalWins0 = 0, totalWins1 = 0, totalDraws = 0;
        for (OrganizationRating opp : oppRatings) {
            int w0 = 0, w1 = 0, d = 0;
            float sumFp0 = 0f, sumFp1 = 0f;
            float sumHull0 = 0f, sumHull1 = 0f;
            float sumFocus0 = 0f, sumFocus1 = 0f;
            float sumTime = 0f;

            for (int b = 1; b <= 10; b++) {
                long seed = (opp.ordinal() + 1) * 1000L + b * 97L + (side0Conv ? 10000L : 0L) + (side1Conv ? 20000L : 0L);
                // Alternate deployment side every battle so any residual side effect cancels within each 10-battle cell.
                boolean perfectOnSide1 = (b % 2 == 0);
                BattleOutcome raw = perfectOnSide1
                        ? runSingleBattle(opp, OrganizationRating.PERFECT, side1Conv, side0Conv, seed)
                        : runSingleBattle(OrganizationRating.PERFECT, opp, side0Conv, side1Conv, seed);
                BattleOutcome out = perfectOnSide1 ? swapSides(raw) : raw;
                if (out.winnerSide == 0) w0++;
                else if (out.winnerSide == 1) w1++;
                else d++;
                sumFp0 += out.side0SurvivingFp;
                sumFp1 += out.side1SurvivingFp;
                sumHull0 += out.side0HullIntegrityPct;
                sumHull1 += out.side1HullIntegrityPct;
                sumFocus0 += out.side0FocusFireConcentration * 100f;
                sumFocus1 += out.side1FocusFireConcentration * 100f;
                sumTime += out.durationSeconds;
                maxTimeSeen = Math.max(maxTimeSeen, out.durationSeconds);
            }
            totalWins0 += w0;
            totalWins1 += w1;
            totalDraws += d;

            String label = String.format(Locale.ROOT, "PERFECT vs %-7s(%3.0f%%)", opp.name(), opp.fraction * 100f);
            System.out.printf(Locale.ROOT,
                    "%-22s | %2d-%2d-%2d | %6.1f / 216 | %6.1f / 216 | %7.1f%%   | %7.1f%%   | %4.0f%%:%4.0f%% | %5.1fs%n",
                    label, w0, w1, d, sumFp0 / 10f, sumFp1 / 10f, sumHull0 / 10f, sumHull1 / 10f,
                    sumFocus0 / 10f, sumFocus1 / 10f, sumTime / 10f);
        }
        System.out.println("------------------------------------------------------------------------------------------------------");
        System.out.printf(Locale.ROOT, "  BRACKET TOTAL (40 Battles): Side 0 (PERFECT) %dW - %dL - %dD (%.1f%% Win Rate)%n%n",
                totalWins0, totalWins1, totalDraws, (totalWins0 * 100.0f) / 40.0f);
    }

    private static void runVsVanilla40(String title, boolean ourConv) {
        System.out.println("======================================================================================================");
        System.out.println("  " + title);
        System.out.println("  Our Fleet (20 Ships / 216 FP, OTC=" + (ourConv ? "Convergence" : "Human") + ") vs Pure Vanilla AI (20 Ships / 216 FP, No Divisions, No OTC)");
        System.out.println("======================================================================================================");
        System.out.printf(Locale.ROOT,
                "%-26s | %-8s | %-13s | %-13s | %-10s | %-10s | %-11s | %-7s%n",
                "Batch (10 Battles)", "OUR W-L-I", "Our Surv FP", "Van Surv FP", "Our Hull %", "Van Hull %", "Focus Fire", "AvgTime");
        System.out.println("------------------------------------------------------------------------------------------------------");

        // Part A: 40 battles of PERFECT (100% Org) vs Pure Vanilla AI in 4 x 10-battle batches (alternating sides)
        int totalW = 0, totalL = 0, totalD = 0;
        float grandFp0 = 0f, grandFp1 = 0f, grandHull0 = 0f, grandHull1 = 0f, grandFoc0 = 0f, grandFoc1 = 0f, grandTime = 0f;
        for (int batch = 1; batch <= 4; batch++) {
            int w0 = 0, w1 = 0, d = 0;
            float sumFp0 = 0f, sumFp1 = 0f, sumHull0 = 0f, sumHull1 = 0f, sumFocus0 = 0f, sumFocus1 = 0f, sumTime = 0f;
            for (int b = 1; b <= 10; b++) {
                long seed = batch * 1000L + b * 97L + (ourConv ? 10000L : 0L);
                boolean ourOnSide1 = (b % 2 == 0);
                vanillaOnlySide[0] = ourOnSide1;
                vanillaOnlySide[1] = !ourOnSide1;
                BattleOutcome raw = ourOnSide1
                        ? runSingleBattle(OrganizationRating.NONE, OrganizationRating.PERFECT, false, ourConv, seed)
                        : runSingleBattle(OrganizationRating.PERFECT, OrganizationRating.NONE, ourConv, false, seed);
                vanillaOnlySide[0] = false;
                vanillaOnlySide[1] = false;
                BattleOutcome out = ourOnSide1 ? swapSides(raw) : raw;
                if (out.winnerSide == 0) w0++;
                else if (out.winnerSide == 1) w1++;
                else d++;
                sumFp0 += out.side0SurvivingFp;
                sumFp1 += out.side1SurvivingFp;
                sumHull0 += out.side0HullIntegrityPct;
                sumHull1 += out.side1HullIntegrityPct;
                sumFocus0 += out.side0FocusFireConcentration * 100f;
                sumFocus1 += out.side1FocusFireConcentration * 100f;
                sumTime += out.durationSeconds;
            }
            totalW += w0; totalL += w1; totalD += d;
            grandFp0 += sumFp0; grandFp1 += sumFp1; grandHull0 += sumHull0; grandHull1 += sumHull1;
            grandFoc0 += sumFocus0; grandFoc1 += sumFocus1; grandTime += sumTime;
            String label = String.format(Locale.ROOT, "Batch %d (Battles %2d-%2d)", batch, (batch - 1) * 10 + 1, batch * 10);
            System.out.printf(Locale.ROOT,
                    "%-26s | %2d-%2d-%2d | %6.1f / 216 | %6.1f / 216 | %7.1f%%   | %7.1f%%   | %4.0f%%:%4.0f%% | %5.1fs%n",
                    label, w0, w1, d, sumFp0 / 10f, sumFp1 / 10f, sumHull0 / 10f, sumHull1 / 10f,
                    sumFocus0 / 10f, sumFocus1 / 10f, sumTime / 10f);
        }
        System.out.println("------------------------------------------------------------------------------------------------------");
        System.out.printf(Locale.ROOT,
                "  40-BATTLE TOTAL: Our Fleet %dW - %dL - %dD (%.1f%% Win Rate) | Avg Surv FP: %.1f vs %.1f | Avg Hull: %.1f%% vs %.1f%% | Focus: %.0f%%:%.0f%% | AvgTime: %.1fs%n%n",
                totalW, totalL, totalD, (totalW * 100.0f) / 40.0f,
                grandFp0 / 40f, grandFp1 / 40f, grandHull0 / 40f, grandHull1 / 40f,
                grandFoc0 / 40f, grandFoc1 / 40f, grandTime / 40f);

    }

    public static void main(String[] args) {
        org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.WARN);
        if (args.length > 0 && args[0].equals("vanilla")) {
            runVsVanilla40("RUN 1 (40 BATTLES): OUR FLEET AI (HUMAN OTC) vs PURE VANILLA AI", false);
            runVsVanilla40("RUN 2 (40 BATTLES): OUR FLEET AI (CONVERGENCE OTC) vs PURE VANILLA AI", true);
            System.out.printf(Locale.ROOT, "VALIDITY: invalid (non-annihilation) battles = %d | longest battle = %.1fs (cap %.0fs)%n", invalidCount, maxTimeSeen, MAX_BATTLE_SECONDS);
            return;
        }
        if (args.length > 0 && args[0].equals("vanmatch")) {
            int n = args.length > 1 ? Integer.parseInt(args[1]) : 20;
            int hWins = 0, hLoss = 0, cWins = 0, cLoss = 0;
            float hFp = 0f, hHull = 0f, cFp = 0f, cHull = 0f;
            System.out.println("MATCHED-SEED 216 FP PERFECT DIVISION AI vs PURE VANILLA AI (Human vs Convergence, same seeds)...");
            for (int b = 1; b <= n; b++) {
                long seed = b * 97L + 1000L;
                boolean ourOnSide1 = (b % 2 == 0);
                // Human
                vanillaOnlySide[0] = ourOnSide1;
                vanillaOnlySide[1] = !ourOnSide1;
                BattleOutcome hRaw = ourOnSide1
                        ? runSingleBattle(OrganizationRating.NONE, OrganizationRating.PERFECT, false, false, seed)
                        : runSingleBattle(OrganizationRating.PERFECT, OrganizationRating.NONE, false, false, seed);
                BattleOutcome hOut = ourOnSide1 ? swapSides(hRaw) : hRaw;
                // Convergence
                BattleOutcome cRaw = ourOnSide1
                        ? runSingleBattle(OrganizationRating.NONE, OrganizationRating.PERFECT, false, true, seed)
                        : runSingleBattle(OrganizationRating.PERFECT, OrganizationRating.NONE, true, false, seed);
                BattleOutcome cOut = ourOnSide1 ? swapSides(cRaw) : cRaw;
                vanillaOnlySide[0] = false;
                vanillaOnlySide[1] = false;
                if (hOut.winnerSide == 0) hWins++; else if (hOut.winnerSide == 1) hLoss++;
                if (cOut.winnerSide == 0) cWins++; else if (cOut.winnerSide == 1) cLoss++;
                hFp += hOut.side0SurvivingFp; hHull += hOut.side0HullIntegrityPct;
                cFp += cOut.side0SurvivingFp; cHull += cOut.side0HullIntegrityPct;
                System.out.printf(Locale.ROOT,
                        "  seed %6d (%s on Side 1): Human %s (%.0f FP, %.0f%% hull) | Conv %s (%.0f FP, %.0f%% hull)%n",
                        seed, ourOnSide1 ? "ours" : "van",
                        hOut.winnerSide == 0 ? "WIN " : "LOSS", hOut.side0SurvivingFp, hOut.side0HullIntegrityPct,
                        cOut.winnerSide == 0 ? "WIN " : "LOSS", cOut.side0SurvivingFp, cOut.side0HullIntegrityPct);
            }
            System.out.printf(Locale.ROOT,
                    "  MATCHED %d BATTLES: Human OTC %dW-%dL (Avg Surv FP %.1f, Hull %.1f%%) | Convergence OTC %dW-%dL (Avg Surv FP %.1f, Hull %.1f%%)%n",
                    n, hWins, hLoss, hFp / n, hHull / n, cWins, cLoss, cFp / n, cHull / n);
            return;
        }
        if (args.length > 0 && args[0].equals("mirror")) {
            String[][] cfgs = {{"PERFECT","PERFECT","false","false"},{"NONE","NONE","false","false"},{"NONE","PERFECT","false","false"},{"PERFECT","PERFECT","true","true"},{"PERFECT","PERFECT","true","false"},{"LOW","LOW","false","false"},{"LOW","LOW","false","true"}};
            for (String[] c : cfgs) {
                int w0=0,w1=0,d=0; float f0=0,f1=0;
                for (int b=1;b<=20;b++){ BattleOutcome o = runSingleBattle(OrganizationRating.valueOf(c[0]), OrganizationRating.valueOf(c[1]), c[2].equals("true"), c[3].equals("true"), 5000L+b*131L);
                    if(o.winnerSide==0)w0++; else if(o.winnerSide==1)w1++; else d++; f0+=o.side0SurvivingFp; f1+=o.side1SurvivingFp; }
                System.out.printf(Locale.ROOT, "%-8s(c=%s) vs %-8s(c=%s): %2d-%2d-%2d  survFP %.0f vs %.0f%n", c[0], c[2], c[1], c[3], w0, w1, d, f0/20, f1/20);
                System.out.flush();
            }
            return;
        }
        if (args.length > 0 && args[0].equals("stall")) {
            STALL_DUMP = true;
            String[] c = {args[1], args[2], args[3], args[4]};
            for (int b=1;b<=20;b++){ BattleOutcome o = runSingleBattle(OrganizationRating.valueOf(c[0]), OrganizationRating.valueOf(c[1]), c[2].equals("true"), c[3].equals("true"), 5000L+b*131L);
                System.out.printf(Locale.ROOT, "b%d winner=%d t=%.0f ships %d vs %d%n", b, o.winnerSide, o.durationSeconds, o.side0SurvivingShips, o.side1SurvivingShips); }
            return;
        }
        if (args.length > 0 && args[0].equals("bias")) {
            String[][] cfgs = {{"NONE","false"},{"PERFECT","false"},{"PERFECT","true"},{"LOW","true"}};
            int n = Integer.parseInt(args[1]);
            for (String[] c : cfgs) { int w0=0,w1=0,d=0; float mt=0;
                for (int b=1;b<=n;b++){ BattleOutcome o = runSingleBattle(OrganizationRating.valueOf(c[0]), OrganizationRating.valueOf(c[0]), c[1].equals("true"), c[1].equals("true"), 77000L+b*131L);
                    if(o.winnerSide==0)w0++; else if(o.winnerSide==1)w1++; else d++; mt=Math.max(mt,o.durationSeconds);} 
                System.out.printf(Locale.ROOT, "%-8s conv=%s mirror x%d: side0 %d - side1 %d - invalid %d (max t=%.0fs)%n", c[0], c[1], n, w0, w1, d, mt); System.out.flush(); }
            return;
        }
        if (args.length > 0 && args[0].equals("trace")) {
            TRACE1 = true;
            OrganizationRating r0 = args.length > 2 ? OrganizationRating.valueOf(args[2]) : OrganizationRating.PERFECT;
            boolean c0 = args.length > 3 && args[3].equals("true");
            vanillaOnlySide[1] = args.length > 4 && args[4].equals("true");
            long seed = args.length > 5 ? Long.parseLong(args[5]) : (3000L + 97L);
            BattleOutcome o = runSingleBattle(r0, OrganizationRating.valueOf(args[1]), c0, false, seed);
            System.out.println("winner=" + o.winnerSide + " fp " + o.side0SurvivingFp + " vs " + o.side1SurvivingFp);
            return;
        }
        if (args.length > 0 && args[0].equals("diag")) {
            DIAG = true; divisionai.BattlefieldFirepowerEvaluator.ZONE_DEBUG = true;
            OrganizationRating opp = OrganizationRating.valueOf(args[1]);
            boolean c0 = args.length > 2 && args[2].equals("true");
            boolean c1 = args.length > 3 && args[3].equals("true");
            float[] fl = new float[2], hl = new float[2];
            for (int b = 1; b <= 10; b++) {
                firstLoss[0] = firstLoss[1] = halfLoss[0] = halfLoss[1] = -1f;
                BattleOutcome o = runSingleBattle(OrganizationRating.PERFECT, opp, c0, c1, (opp.ordinal()+1)*1000L + b*97L);
                for (int s = 0; s < 2; s++) { fl[s] += firstLoss[s]; hl[s] += (halfLoss[s] < 0 ? 280f : halfLoss[s]); }
                System.out.printf(Locale.ROOT, "  b%d winner=%d fp %.0f vs %.0f t=%.0f%n", b, o.winnerSide, o.side0SurvivingFp, o.side1SurvivingFp, o.durationSeconds);
            }
            System.out.println("  intent                       share  camp  inRng  fire  backoff exposure escort mtSet nearest");
            float tt = 0; for (float[] v : intentStats.values()) tt += v[0];
            for (Map.Entry<String, float[]> e : intentStats.entrySet()) { float[] v = e.getValue(); if (v[0]/tt < 0.01f) continue;
                System.out.printf(Locale.ROOT, "  %-26s %5.1f%% %5.0f%% %5.0f%% %5.0f%% %5.0f%% %6.2f %5.0f%% %5.0f%% %6.0f%n", e.getKey(), 100*v[0]/tt, 100*v[1]/v[0], 100*v[2]/v[0], 100*v[3]/v[0], 100*v[4]/v[0], v[5]/v[0], 100*v[6]/v[0], 100*v[7]/v[0], v[8]/v[0]); }
            for (int s = 0; s < 2; s++) System.out.printf(Locale.ROOT, "side%d shotWithoutReply=%.1f%%%n", s, 100*shotNoReply[s]/aliveSec[s]);
            for (Map.Entry<String, float[]> e : deathT.entrySet()) System.out.printf(Locale.ROOT, "  death %s n=%.0f avgT=%.0f%n", e.getKey(), e.getValue()[1], e.getValue()[0]/e.getValue()[1]);
            for (int s = 0; s < 2; s++) System.out.printf(Locale.ROOT, "side%d: firstLoss=%.0fs halfLoss=%.0fs overloadsSuffered=%.0f hullDmgTaken=%.0f strikeDmgDealt=%.0f DNBO=%.1f%% avgExposure(enemy guns on me)=%.2f%n",
                s, fl[s]/10, hl[s]/10, ovl[s], hullDmgTaken[s], strikeDmgDealt[s], 100*dnboSec[s]/aliveSec[s], exposure[s]/aliveSec[s]);
            for (int s = 0; s < 2; s++) System.out.printf(Locale.ROOT,
                "side%d: aliveShipSec=%.0f fireUptime=%.1f%% outOfGunRange=%.1f%% campNearEnemy=%.1f%% backOff=%.1f%% gunDmg=%.0f%n",
                s, aliveSec[s], 100*fireSec[s]/aliveSec[s], 100*idleInRangeSec[s]/aliveSec[s], 100*campInRangeSec[s]/aliveSec[s], 100*backOffSec[s]/aliveSec[s], dmgDealt[s]);
            System.out.printf(Locale.ROOT, "zoneDbg n=%.0f avgIn=%.1f avgOut=%.1f cordonFrac=%.2f%n", divisionai.BattlefieldFirepowerEvaluator.zoneDbgN, divisionai.BattlefieldFirepowerEvaluator.zoneDbgIn/Math.max(1f,divisionai.BattlefieldFirepowerEvaluator.zoneDbgN), divisionai.BattlefieldFirepowerEvaluator.zoneDbgOut/Math.max(1f,divisionai.BattlefieldFirepowerEvaluator.zoneDbgN), divisionai.BattlefieldFirepowerEvaluator.zoneDbgCordon/Math.max(1f,divisionai.BattlefieldFirepowerEvaluator.zoneDbgN));
            ovlWhy.entrySet().stream().sorted((a,b)->Float.compare(b.getValue(),a.getValue())).limit(16).forEach(e -> System.out.printf(Locale.ROOT, "  ovl %-60s %4.0f%n", e.getKey(), e.getValue()));
            haulWhy.entrySet().stream().sorted((a,b)->Float.compare(b.getValue(),a.getValue())).limit(14).forEach(e -> System.out.printf(Locale.ROOT, "  haul %-40s %6.0fs%n", e.getKey(), e.getValue()));
            System.out.printf(Locale.ROOT, "side0 divShips: inDoubleLine=%.1f%% inReliefRank=%.1f%%%n", 100*doubleLineSec/Math.max(1f,divShipSec), 100*reliefSec/Math.max(1f,divShipSec));
            for (Map.Entry<String, float[]> e : zoneStats.entrySet()) System.out.printf(Locale.ROOT, "  zone %-16s share=%5.1f%% fire=%5.1f%%%n", e.getKey(), 100*e.getValue()[0]/Math.max(1f,divShipSec), 100*e.getValue()[1]/Math.max(0.01f,e.getValue()[0]));
            float tot = 0; for (float v : intentSec.values()) tot += v;
            for (Map.Entry<String, Float> e : intentSec.entrySet()) if (e.getValue()/tot > 0.01f) System.out.printf(Locale.ROOT, "  %-55s %5.1f%%%n", e.getKey(), 100*e.getValue()/tot);
            return;
        }
        long t0 = System.currentTimeMillis();
        System.out.println("RUNNING LARGE FLEET ORGANIZATION & OTC CONVERGENCE TOURNAMENT (20 Ships / 216 FP per side)...\n");

        // 1st 40 battles: Both sides have Human OTC
        runBracket("1ST 40 BATTLES: BOTH SIDES HUMAN OTC", false, false);

        // 2nd 40 battles: 1 side has Convergence OTC, other side has Human OTC
        runBracket("2ND 40 BATTLES (A): PERFECT HAS CONVERGENCE OTC vs ORG HAS HUMAN OTC", true, false);
        runBracket("2ND 40 BATTLES (B): PERFECT HAS HUMAN OTC vs ORG HAS CONVERGENCE OTC", false, true);

        // 3rd 40 battles: Both sides have Convergence OTC
        runBracket("3RD 40 BATTLES: BOTH SIDES CONVERGENCE OTC", true, true);

        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("Completed all tournament brackets in " + elapsed + " ms.");
        System.out.printf(Locale.ROOT, "VALIDITY: invalid (non-annihilation) battles = %d | longest battle = %.1fs (cap %.0fs)%n", invalidCount, maxTimeSeen, MAX_BATTLE_SECONDS);
    }
}
