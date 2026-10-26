package divisionai;

import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.fleet.FleetGoal;
import divisionai.DivisionModel.BattlegroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionModel.MissionContextType;
import divisionai.ShipCapabilityProfile.TacticalTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto-forms tactical divisions from deployed ships based on:
 *  1. Mission context (Objective Contest, Pitched Battle, Fight to the Last, Escape/Rearguard)
 *  2. Ship class (Capital, Cruiser, Destroyer, Frigate)
 *  3. Weapon & wing capabilities (Carrier bays, heavy torpedo/strike racks, PD screens, phase cloak)
 *
 * Produces both:
 *  - Composite divisions (e.g., "Carrier & Escort 1", or "Battle Div 1" with attached PD escorts)
 *  - Monotype/capability divisions (e.g., "Cruiser Div 1", "Destroyer Div 1", "Frigate Div 1")
 *    which can dynamically cross-support each other during battle (e.g., "Destroyer Div 1"
 *    screening "Battle Div 1" or launching harassing torpedo attacks at "Enemy Destroyer Div 2").
 */
public class DivisionAutoFormer {

    private static final int MAX_MONOTYPE_DIV_SIZE = 4;
    private static final int MAX_FRIGATE_DIV_SIZE = 5;

    public static MissionContextType detectMissionContext(CombatEngineAPI engine, int side) {
        if (engine == null) return MissionContextType.FULL_PITCHED_BATTLE;

        BattleCreationContext ctx = engine.getContext();
        List<BattleObjectiveAPI> objectives = engine.getObjectives();
        boolean hasObjectives = objectives != null && !objectives.isEmpty();

        if (ctx != null) {
            if (ctx.fightToTheLast) {
                return MissionContextType.FIGHT_TO_THE_LAST;
            }
            FleetGoal myGoal = (side == 0) ? ctx.getPlayerGoal() : ctx.getOtherGoal();
            FleetGoal enemyGoal = (side == 0) ? ctx.getOtherGoal() : ctx.getPlayerGoal();
            if (myGoal == FleetGoal.ESCAPE) {
                return MissionContextType.FLEET_ESCAPE_REARGUARD;
            }
            if (enemyGoal == FleetGoal.ESCAPE) {
                return MissionContextType.PURSUIT_INTERCEPTION;
            }
        }
        if (hasObjectives) {
            return MissionContextType.OBJECTIVE_CONTEST;
        }
        return MissionContextType.FULL_PITCHED_BATTLE;
    }

    public static CommanderProfile resolveBattlegroupCommander(CombatEngineAPI engine, int side) {
        CombatFleetManagerAPI fm = engine.getFleetManager(side);
        PersonAPI commander = null;
        if (fm != null) {
            commander = (side == 0) ? fm.getFleetCommanderPreferPlayer() : fm.getFleetCommander();
            if (commander == null) {
                commander = fm.getDefaultCommander();
            }
        }
        String fallback = (side == 0) ? "Player BG Cmdr" : "OpFor BG Cmdr";
        return new CommanderProfile(commander, fallback);
    }

    public static CommanderProfile resolveAlliedAiBattlegroupCommander(CombatEngineAPI engine, List<ShipAPI> alliedShips) {
        PersonAPI commander = null;
        if (alliedShips != null) {
            float bestFp = -1f;
            for (ShipAPI s : alliedShips) {
                if (s == null || !s.isAlly()) continue;
                PersonAPI cap = s.getCaptain();
                if (cap != null && !cap.isDefault()) {
                    float fp = ShipCapabilityProfile.resolveShipFpStrength(s);
                    if (fp > bestFp) {
                        bestFp = fp;
                        commander = cap;
                    }
                }
            }
        }
        if (commander == null && engine != null) {
            CombatFleetManagerAPI fm = engine.getFleetManager(0);
            if (fm != null) {
                commander = fm.getDefaultCommander();
            }
        }
        return new CommanderProfile(commander, "Allied BG Cmdr");
    }

    /**
     * Synchronizes ONLY the player's campaign Permanent Divisions onto deployed player-controlled ships.
     * Never forcibly assigns unassigned player ships to divisions on the battle layer.
     */
    public static List<DivisionState> syncPlayerPermanentDivisionsOnly(
            CombatEngineAPI engine,
            BattlegroupState playerBg,
            List<ShipAPI> playerShips) {

        List<DivisionState> newlyCreated = new ArrayList<>();
        Set<String> assignedIds = new HashSet<>(playerBg.shipIdToDivision.keySet());

        List<ShipCapabilityProfile> unassigned = new ArrayList<>();
        for (ShipAPI ship : playerShips) {
            if (ship == null || ship.isAlly() || !isEligibleCombatHull(ship)) continue;
            if (!assignedIds.contains(ship.getId())) {
                unassigned.add(new ShipCapabilityProfile(ship));
            }
        }

        if (unassigned.isEmpty()) {
            return newlyCreated;
        }

        Collections.sort(unassigned, new Comparator<ShipCapabilityProfile>() {
            @Override
            public int compare(ShipCapabilityProfile a, ShipCapabilityProfile b) {
                int cmp = Float.compare(b.fpStrength, a.fpStrength);
                if (cmp != 0) return cmp;
                return Float.compare(a.ship.getLocation().x, b.ship.getLocation().x);
            }
        });

        // Inherit campaign Permanent Divisions ONLY; any player ships not in a permanent division
        // remain unassigned and run vanilla Starsector combat AI.
        PermanentDivisionManager.applyPermanentDivisionsInCombat(
                playerBg,
                unassigned,
                newlyCreated);

        return newlyCreated;
    }

    /**
     * Reconciles newly deployed AI fleet ships (Allied AI fleet or OpFor fleet) into existing divisions
     * or builds initial divisions when a battle starts or reinforcement waves arrive.
     */
    public static List<DivisionState> syncDivisionsForSide(
            CombatEngineAPI engine,
            BattlegroupState bg,
            List<ShipAPI> eligibleShips) {

        List<DivisionState> newlyCreated = new ArrayList<>();
        Set<String> assignedIds = new HashSet<>(bg.shipIdToDivision.keySet());

        List<ShipCapabilityProfile> unassigned = new ArrayList<>();
        for (ShipAPI ship : eligibleShips) {
            if (ship == null || !isEligibleCombatHull(ship)) continue;
            if (!assignedIds.contains(ship.getId())) {
                unassigned.add(new ShipCapabilityProfile(ship));
            }
        }

        if (unassigned.isEmpty()) {
            return newlyCreated;
        }

        // Sort unassigned hulls deterministically by FP descending, then x-position
        Collections.sort(unassigned, new Comparator<ShipCapabilityProfile>() {
            @Override
            public int compare(ShipCapabilityProfile a, ShipCapabilityProfile b) {
                int cmp = Float.compare(b.fpStrength, a.fpStrength);
                if (cmp != 0) return cmp;
                return Float.compare(a.ship.getLocation().x, b.ship.getLocation().x);
            }
        });

        if (!bg.divisions.isEmpty()) {
            List<ShipCapabilityProfile> remaining = new ArrayList<>();
            for (ShipCapabilityProfile prof : unassigned) {
                DivisionState match = findExistingDivisionForReinforcement(bg, prof);
                if (match != null) {
                    addShipToDivision(bg, match, prof);
                    recomputeDivisionCapabilities(match);
                } else {
                    remaining.add(prof);
                }
            }
            unassigned = remaining;
        }

        if (!unassigned.isEmpty()) {
            newlyCreated.addAll(formDivisionsFromPool(bg, unassigned));
        }
        return newlyCreated;
    }

    private static List<DivisionState> formDivisionsFromPool(
            BattlegroupState bg,
            List<ShipCapabilityProfile> pool) {
        List<DivisionState> newlyCreated = new ArrayList<>();
        List<ShipCapabilityProfile> carriers = new ArrayList<>();
        List<ShipCapabilityProfile> capitals = new ArrayList<>();
        List<ShipCapabilityProfile> strikeCruisers = new ArrayList<>();
        List<ShipCapabilityProfile> lineCruisers = new ArrayList<>();
        List<ShipCapabilityProfile> torpedoDestroyers = new ArrayList<>();
        List<ShipCapabilityProfile> escortDestroyers = new ArrayList<>();
        List<ShipCapabilityProfile> lineDestroyers = new ArrayList<>();
        List<ShipCapabilityProfile> torpedoFrigates = new ArrayList<>();
        List<ShipCapabilityProfile> screenFrigates = new ArrayList<>();
        List<ShipCapabilityProfile> phaseShips = new ArrayList<>();
        List<ShipCapabilityProfile> auxiliaries = new ArrayList<>();

        for (ShipCapabilityProfile p : pool) {
            if (p.primaryTag == TacticalTag.CIVILIAN_AUX) {
                auxiliaries.add(p);
            } else if (p.primaryTag == TacticalTag.PHASE_RAIDER) {
                phaseShips.add(p);
            } else if (p.primaryTag == TacticalTag.CARRIER) {
                carriers.add(p);
            } else if (p.hullSize == HullSize.CAPITAL_SHIP) {
                capitals.add(p);
            } else if (p.primaryTag == TacticalTag.STRIKE_CRUISER) {
                strikeCruisers.add(p);
            } else if (p.hullSize == HullSize.CRUISER) {
                lineCruisers.add(p);
            } else if (p.primaryTag == TacticalTag.TORPEDO_DESTROYER) {
                torpedoDestroyers.add(p);
            } else if (p.primaryTag == TacticalTag.ESCORT_DESTROYER) {
                escortDestroyers.add(p);
            } else if (p.hullSize == HullSize.DESTROYER) {
                lineDestroyers.add(p);
            } else if (p.primaryTag == TacticalTag.TORPEDO_FRIGATE) {
                torpedoFrigates.add(p);
            } else {
                screenFrigates.add(p);
            }
        }

        // 1. Form "Carrier & Escort" composite divisions.
        // In defensive/escape missions or when escort destroyers/PD frigates are available,
        // pair 1-2 dedicated PD escorts directly into the Carrier & Escort division.
        // However, if there are enough destroyers to form a standalone Destroyer Division,
        // leave monotype destroyers intact so they can act as an independent screening/strike division!
        boolean keepStandaloneDestroyerDiv = (escortDestroyers.size() + lineDestroyers.size()) >= 2
                && bg.missionContext != MissionContextType.FLEET_ESCAPE_REARGUARD;

        for (int i = 0; i < carriers.size(); i += 2) {
            List<ShipCapabilityProfile> group = new ArrayList<>();
            ShipCapabilityProfile primaryCarrier = carriers.get(i);
            group.add(primaryCarrier);
            if (i + 1 < carriers.size() && primaryCarrier.hullSize != HullSize.CAPITAL_SHIP) {
                group.add(carriers.get(i + 1));
            }

            int escortsWanted = (primaryCarrier.hullSize == HullSize.CAPITAL_SHIP) ? 2 : 1;
            while (escortsWanted > 0) {
                ShipCapabilityProfile escort = null;
                if (!keepStandaloneDestroyerDiv && !escortDestroyers.isEmpty()) {
                    escort = popNearest(primaryCarrier.ship, escortDestroyers);
                } else if (!screenFrigates.isEmpty() && (screenFrigates.size() > 2 || bg.missionContext == MissionContextType.FLEET_ESCAPE_REARGUARD)) {
                    escort = popNearest(primaryCarrier.ship, screenFrigates);
                }
                if (escort == null) break;
                group.add(escort);
                escortsWanted--;
            }

            DivisionState div = createDivision(bg, DivisionArchetype.CARRIER_AND_ESCORT, group);
            newlyCreated.add(div);
        }

        // 2. Form "Battle Div" from capitals.
        // If there is a lone capital and leftover single cruiser, or in escape/rearguard missions,
        // attach 1 close escort; otherwise keep Cruiser and Destroyer divisions monotype so they can maneuver/screen independently.
        for (int i = 0; i < capitals.size(); i += 2) {
            List<ShipCapabilityProfile> group = new ArrayList<>();
            ShipCapabilityProfile cap = capitals.get(i);
            group.add(cap);
            if (i + 1 < capitals.size()) {
                group.add(capitals.get(i + 1));
            }
            if (group.size() == 1 && lineCruisers.size() == 1 && strikeCruisers.isEmpty()) {
                group.add(lineCruisers.remove(0));
            }
            DivisionState div = createDivision(bg, DivisionArchetype.BATTLE_DIVISION, group);
            newlyCreated.add(div);
        }

        // 3. Form Cruiser Divisions (monotype line cruisers vs strike cruisers).
        // If only 1 strike cruiser and 1+ line cruisers exist, merge them into a Cruiser Division rather than a 1-ship fragment.
        if (strikeCruisers.size() == 1 && !lineCruisers.isEmpty()) {
            lineCruisers.add(strikeCruisers.remove(0));
        }
        newlyCreated.addAll(chunkIntoDivisions(bg, DivisionArchetype.STRIKE_CRUISER_DIVISION, strikeCruisers, MAX_MONOTYPE_DIV_SIZE));
        newlyCreated.addAll(chunkIntoDivisions(bg, DivisionArchetype.CRUISER_DIVISION, lineCruisers, MAX_MONOTYPE_DIV_SIZE));

        // 4. Form Destroyer Divisions (torpedo destroyers vs line/escort destroyers).
        // Both use the clean "Destroyer Div N" callsign so battle log messages read naturally
        // ("Destroyer Div 1 intends to launch harassing torpedo attack at Enemy Destroyer Div 2"),
        // while preserving their distinct torpedo-strike vs screening capabilities.
        List<ShipCapabilityProfile> gunDestroyers = new ArrayList<>();
        gunDestroyers.addAll(escortDestroyers);
        gunDestroyers.addAll(lineDestroyers);

        if (torpedoDestroyers.size() == 1 && gunDestroyers.size() == 1) {
            torpedoDestroyers.addAll(gunDestroyers);
            gunDestroyers.clear();
        } else if (torpedoDestroyers.isEmpty() && gunDestroyers.size() == 1 && torpedoFrigates.size() >= 1) {
            // Pair a lone destroyer with torpedo frigates as a light destroyer flotilla
            gunDestroyers.addAll(torpedoFrigates);
            torpedoFrigates.clear();
        }

        newlyCreated.addAll(chunkIntoDivisions(bg, DivisionArchetype.TORPEDO_DESTROYER_DIVISION, torpedoDestroyers, MAX_MONOTYPE_DIV_SIZE));
        newlyCreated.addAll(chunkIntoDivisions(bg, DivisionArchetype.DESTROYER_DIVISION, gunDestroyers, MAX_MONOTYPE_DIV_SIZE));

        // 5. Form Frigate & Phase Divisions
        List<ShipCapabilityProfile> allFrigates = new ArrayList<>();
        // Keep torpedo frigates grouped first so chunking naturally forms a torpedo wolfpack before screen frigates
        allFrigates.addAll(torpedoFrigates);
        allFrigates.addAll(screenFrigates);
        newlyCreated.addAll(chunkIntoDivisions(bg, DivisionArchetype.FRIGATE_DIVISION, allFrigates, MAX_FRIGATE_DIV_SIZE));

        newlyCreated.addAll(chunkIntoDivisions(bg, DivisionArchetype.PHASE_DIVISION, phaseShips, MAX_MONOTYPE_DIV_SIZE));
        newlyCreated.addAll(chunkIntoDivisions(bg, DivisionArchetype.AUXILIARY_CONVOY, auxiliaries, MAX_FRIGATE_DIV_SIZE));

        return newlyCreated;
    }

    private static List<DivisionState> chunkIntoDivisions(
            BattlegroupState bg,
            DivisionArchetype archetype,
            List<ShipCapabilityProfile> pool,
            int maxPerDiv) {
        List<DivisionState> out = new ArrayList<>();
        if (pool.isEmpty()) return out;

        int idx = 0;
        while (idx < pool.size()) {
            int remaining = pool.size() - idx;
            int take = Math.min(maxPerDiv, remaining);
            // Avoid leaving a trailing 1-ship division when we can split evenly (e.g. 5 -> 3 + 2)
            if (remaining > maxPerDiv && (remaining - take) == 1 && take > 2) {
                take -= 1;
            }
            List<ShipCapabilityProfile> sub = new ArrayList<>(pool.subList(idx, idx + take));
            out.add(createDivision(bg, archetype, sub));
            idx += take;
        }
        return out;
    }

    private static DivisionState createDivision(
            BattlegroupState bg,
            DivisionArchetype archetype,
            List<ShipCapabilityProfile> members) {

        boolean isAlliedAi = !members.isEmpty() && members.get(0).ship.isAlly();
        String sidePrefix = (bg.side == 0) ? (isAlliedAi ? "Allied " : "") : "Enemy ";
        int number = nextDivisionNumberForPrefix(bg, sidePrefix + archetype.shortPrefix);
        String displayName = sidePrefix + archetype.shortPrefix + " " + number;
        String divId = (bg.side == 0 ? (isAlliedAi ? "ally_" : "player_") : "enemy_")
                + archetype.name().toLowerCase() + "_" + number;

        ShipAPI leaderShip = pickDivisionFlagship(members);
        PersonAPI captain = (leaderShip != null) ? leaderShip.getCaptain() : null;
        CommanderProfile cmdr = new CommanderProfile(captain, displayName + " Cmdr");

        DivisionState div = new DivisionState(divId, bg.side, number, archetype, displayName, cmdr);
        div.flagship = leaderShip;
        for (ShipCapabilityProfile prof : members) {
            addShipToDivision(bg, div, prof);
        }
        recomputeDivisionCapabilities(div);
        bg.divisions.add(div);
        return div;
    }

    private static void addShipToDivision(BattlegroupState bg, DivisionState div, ShipCapabilityProfile prof) {
        div.members.add(prof.ship);
        div.profiles.add(prof);
        bg.shipIdToDivision.put(prof.ship.getId(), div);
    }

    public static void recomputeDivisionCapabilities(DivisionState div) {
        float fpSum = 0f;
        float speedSum = 0f;
        float rangeSum = 0f;
        int torpedoShips = 0;
        int pdShips = 0;
        int carrierShips = 0;

        for (ShipCapabilityProfile p : div.profiles) {
            fpSum += p.fpStrength;
            speedSum += p.baseMaxSpeed;
            rangeSum += p.avgWeaponRange;
            if (p.hasHeavyTorpedoLoadout) torpedoShips++;
            if (p.isPdScreenCapable) pdShips++;
            if (p.isCarrier) carrierShips++;
        }

        int count = Math.max(1, div.profiles.size());
        div.totalFpStrength = fpSum;
        div.avgMaxSpeed = speedSum / count;
        div.avgEngageRange = rangeSum / count;

        div.hasCarrierCapability = (div.archetype == DivisionArchetype.CARRIER_AND_ESCORT) || (carrierShips > 0);
        div.hasTorpedoStrikeCapability = !div.hasCarrierCapability
                && div.archetype != DivisionArchetype.BATTLE_DIVISION
                && div.archetype != DivisionArchetype.AUXILIARY_CONVOY
                && ((div.archetype == DivisionArchetype.TORPEDO_DESTROYER_DIVISION)
                || (div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION)
                || (div.archetype == DivisionArchetype.PHASE_DIVISION)
                || (torpedoShips > 0 && torpedoShips * 2 >= count));
        div.isScreenCapable = (div.archetype == DivisionArchetype.DESTROYER_DIVISION)
                || (div.archetype == DivisionArchetype.FRIGATE_DIVISION)
                || (pdShips > 0);
        div.isFastFlanker = !div.hasCarrierCapability
                && div.archetype != DivisionArchetype.BATTLE_DIVISION
                && div.archetype != DivisionArchetype.CRUISER_DIVISION
                && div.archetype != DivisionArchetype.STRIKE_CRUISER_DIVISION
                && div.archetype != DivisionArchetype.AUXILIARY_CONVOY
                && (div.avgMaxSpeed >= 105f
                || div.archetype == DivisionArchetype.FRIGATE_DIVISION
                || div.archetype == DivisionArchetype.PHASE_DIVISION
                || div.archetype == DivisionArchetype.TORPEDO_DESTROYER_DIVISION);

        // Desired cohesion radius scales by archetype and commander strictness
        float baseRadius;
        switch (div.archetype) {
            case BATTLE_DIVISION:
            case CARRIER_AND_ESCORT:
                baseRadius = 900f;
                break;
            case CRUISER_DIVISION:
            case STRIKE_CRUISER_DIVISION:
                baseRadius = 850f;
                break;
            case DESTROYER_DIVISION:
            case TORPEDO_DESTROYER_DIVISION:
                baseRadius = 750f;
                break;
            case FRIGATE_DIVISION:
            case PHASE_DIVISION:
            default:
                baseRadius = 680f;
                break;
        }
        div.desiredCohesionRadius = baseRadius / Math.max(0.75f, div.commander.cohesionStrictness);
    }

    private static int nextDivisionNumberForPrefix(BattlegroupState bg, String fullPrefix) {
        int count = 0;
        for (DivisionState existing : bg.divisions) {
            if (existing.displayName.startsWith(fullPrefix + " ")) {
                count++;
            }
        }
        return count + 1;
    }

    private static ShipAPI pickDivisionFlagship(List<ShipCapabilityProfile> members) {
        ShipCapabilityProfile best = null;
        float bestScore = -1f;
        for (ShipCapabilityProfile p : members) {
            float score = p.fpStrength;
            if (p.ship.getCaptain() != null && !p.ship.getCaptain().isDefault()) {
                score += 25f + p.ship.getCaptain().getStats().getLevel() * 5f;
            }
            if (p.ship.isAlly()) {
                score -= 5f;
            }
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best != null ? best.ship : null;
    }

    private static DivisionState findExistingDivisionForReinforcement(
            BattlegroupState bg,
            ShipCapabilityProfile prof) {
        for (DivisionState div : bg.divisions) {
            boolean divIsAlly = (div.flagship != null && div.flagship.isAlly());
            if (divIsAlly != prof.ship.isAlly()) continue;
            int aliveCount = div.getAliveMembers().size();
            int maxCap = (div.archetype == DivisionArchetype.FRIGATE_DIVISION
                    || div.archetype == DivisionArchetype.AUXILIARY_CONVOY)
                    ? MAX_FRIGATE_DIV_SIZE
                    : MAX_MONOTYPE_DIV_SIZE;
            if (aliveCount >= maxCap) continue;

            if (prof.primaryTag == TacticalTag.CIVILIAN_AUX && div.archetype == DivisionArchetype.AUXILIARY_CONVOY) {
                return div;
            }
            if (prof.primaryTag == TacticalTag.PHASE_RAIDER && div.archetype == DivisionArchetype.PHASE_DIVISION) {
                return div;
            }
            if (prof.primaryTag == TacticalTag.CARRIER && div.archetype == DivisionArchetype.CARRIER_AND_ESCORT) {
                return div;
            }
            if (prof.hullSize == HullSize.CAPITAL_SHIP && div.archetype == DivisionArchetype.BATTLE_DIVISION) {
                return div;
            }
            if (prof.hullSize == HullSize.DESTROYER
                    && (div.archetype == DivisionArchetype.DESTROYER_DIVISION
                    || div.archetype == DivisionArchetype.TORPEDO_DESTROYER_DIVISION)) {
                return div;
            }
            if (prof.hullSize == HullSize.CRUISER
                    && (div.archetype == DivisionArchetype.CRUISER_DIVISION
                    || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION)) {
                return div;
            }
            if (prof.hullSize == HullSize.FRIGATE
                    && (div.archetype == DivisionArchetype.FRIGATE_DIVISION
                    || (div.archetype == DivisionArchetype.CARRIER_AND_ESCORT && aliveCount < 3))) {
                return div;
            }
        }
        return null;
    }

    private static ShipCapabilityProfile popNearest(ShipAPI anchor, List<ShipCapabilityProfile> candidates) {
        if (candidates.isEmpty()) return null;
        int bestIdx = 0;
        float bestDistSq = Float.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            ShipAPI s = candidates.get(i).ship;
            float dx = s.getLocation().x - anchor.getLocation().x;
            float dy = s.getLocation().y - anchor.getLocation().y;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestDistSq) {
                bestDistSq = d2;
                bestIdx = i;
            }
        }
        return candidates.remove(bestIdx);
    }

    public static boolean isEligibleCombatHull(ShipAPI ship) {
        if (ship == null) return false;
        if (!ship.isAlive() || ship.isHulk() || ship.isExpired() || ship.isRetreating()) return false;
        if (ship.isFighter() || ship.isDrone() || ship.isStation() || ship.isStationModule()) return false;
        if (ship.getParentStation() != null) return false;
        HullSize size = ship.getHullSize();
        return size == HullSize.FRIGATE
                || size == HullSize.DESTROYER
                || size == HullSize.CRUISER
                || size == HullSize.CAPITAL_SHIP;
    }
}
