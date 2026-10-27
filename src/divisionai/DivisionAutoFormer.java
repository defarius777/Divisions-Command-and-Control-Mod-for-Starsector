package divisionai;

import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.fleet.FleetGoal;
import divisionai.DivisionModel.BattleGroupState;
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
        if (engine == null) return MissionContextType.FLEET_ACTION;

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
                return MissionContextType.REARGUARD_ACTION;
            }
            if (enemyGoal == FleetGoal.ESCAPE) {
                return MissionContextType.INTERCEPTION;
            }
        }
        if (hasObjectives) {
            return MissionContextType.CONTESTED_OBJECTIVES;
        }
        return MissionContextType.FLEET_ACTION;
    }

    public static CommanderProfile resolveOtc(CombatEngineAPI engine, int side) {
        CombatFleetManagerAPI fm = engine.getFleetManager(side);
        PersonAPI commander = null;
        if (fm != null) {
            commander = (side == 0) ? fm.getFleetCommanderPreferPlayer() : fm.getFleetCommander();
            if (commander == null) {
                commander = fm.getDefaultCommander();
            }
        }
        String fallback = (side == 0) ? "Player OTC" : "OpFor OTC";
        return new CommanderProfile(commander, fallback);
    }

    public static CommanderProfile resolveAlliedAiOtc(CombatEngineAPI engine, List<ShipAPI> alliedShips) {
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
        return new CommanderProfile(commander, "Allied OTC");
    }

    /**
     * Synchronizes ONLY the player's campaign Permanent Divisions onto deployed player-controlled ships.
     * Never forcibly assigns unassigned player ships to divisions on the battle layer.
     */
    public static List<DivisionState> syncPlayerPermanentDivisionsOnly(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            List<ShipAPI> playerShips) {

        List<DivisionState> newlyCreated = new ArrayList<>();
        Set<String> assignedIds = new HashSet<>(playerGroup.shipIdToDivision.keySet());

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
        playerGroup.nomenclatureCategory = DivisionNomenclature.getPlayerNomenclatureCategory();
        PermanentDivisionManager.applyPermanentDivisionsInCombat(
                playerGroup,
                unassigned,
                newlyCreated);

        return newlyCreated;
    }

    public static String resolveFactionIdForCombatShips(List<ShipAPI> ships, CombatEngineAPI engine, int side) {
        if (ships != null) {
            for (ShipAPI s : ships) {
                if (s == null || s.isFighter()) continue;
                if (s.getFleetMember() != null && s.getFleetMember().getFleetData() != null
                        && s.getFleetMember().getFleetData().getFleet() != null
                        && s.getFleetMember().getFleetData().getFleet().getFaction() != null) {
                    return s.getFleetMember().getFleetData().getFleet().getFaction().getId();
                }
            }
        }
        if (engine != null && engine.getContext() != null) {
            BattleCreationContext ctx = engine.getContext();
            if (side == 1 && ctx.getOtherFleet() != null && ctx.getOtherFleet().getFaction() != null) {
                return ctx.getOtherFleet().getFaction().getId();
            }
        }
        return "independent";
    }

    /**
     * Reconciles newly deployed AI fleet ships (Allied AI fleet or OpFor fleet) into existing divisions
     * or builds initial divisions when a battle starts or reinforcement waves arrive.
     */
    public static List<DivisionState> syncDivisionsForSide(
            CombatEngineAPI engine,
            BattleGroupState battleGroup,
            List<ShipAPI> eligibleShips) {

        List<DivisionState> newlyCreated = new ArrayList<>();
        Set<String> assignedIds = new HashSet<>(battleGroup.shipIdToDivision.keySet());

        if (eligibleShips != null && !eligibleShips.isEmpty()) {
            String detectedFactionId = resolveFactionIdForCombatShips(eligibleShips, engine, battleGroup.side);
            battleGroup.factionId = detectedFactionId;
            battleGroup.nomenclatureCategory = DivisionNomenclature.resolveCategoryForCombatShips(
                    eligibleShips,
                    detectedFactionId,
                    false);
            PermanentDivisionManager.FleetDoctrineConfig fDoc =
                    FactionFleetDoctrines.getDoctrineForFactionId(detectedFactionId, null);
            battleGroup.authority = fDoc.getOtcAuthority();
            battleGroup.plays.clear();
            battleGroup.plays.addAll(fDoc.getEnabledPlays());
        }

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

        // 1. Inherit campaign-sorted divisions from any instanced NPC CampaignFleetAPI first!
        unassigned = FactionFleetDoctrines.applyCampaignNpcDivisionsInCombat(
                battleGroup,
                unassigned,
                newlyCreated);

        if (!unassigned.isEmpty() && !battleGroup.divisions.isEmpty()) {
            List<ShipCapabilityProfile> remaining = new ArrayList<>();
            for (ShipCapabilityProfile prof : unassigned) {
                DivisionState match = findExistingDivisionForReinforcement(battleGroup, prof);
                if (match != null) {
                    addShipToDivision(battleGroup, match, prof);
                    recomputeDivisionCapabilities(match);
                    DivisionCombatLog.logReinforcementJoined(match, prof.ship);
                } else {
                    remaining.add(prof);
                }
            }
            unassigned = remaining;
        }

        if (!unassigned.isEmpty()) {
            newlyCreated.addAll(formDivisionsFromPool(battleGroup, unassigned));
        }
        return newlyCreated;
    }

    private static List<DivisionState> formDivisionsFromPool(
            BattleGroupState battleGroup,
            List<ShipCapabilityProfile> pool) {
        List<DivisionState> newlyCreated = new ArrayList<>();
        List<ShipCapabilityProfile> carriers = new ArrayList<>();
        List<ShipCapabilityProfile> capitals = new ArrayList<>();
        List<ShipCapabilityProfile> missileCapitals = new ArrayList<>();
        List<ShipCapabilityProfile> strikeCruisers = new ArrayList<>();
        List<ShipCapabilityProfile> missileCruisers = new ArrayList<>();
        List<ShipCapabilityProfile> lineCruisers = new ArrayList<>();
        List<ShipCapabilityProfile> missileDestroyers = new ArrayList<>();
        List<ShipCapabilityProfile> escortDestroyers = new ArrayList<>();
        List<ShipCapabilityProfile> lineDestroyers = new ArrayList<>();
        List<ShipCapabilityProfile> missileFrigates = new ArrayList<>();
        List<ShipCapabilityProfile> screenFrigates = new ArrayList<>();
        List<ShipCapabilityProfile> phaseShips = new ArrayList<>();
        List<ShipCapabilityProfile> auxiliaries = new ArrayList<>();

        for (ShipCapabilityProfile p : pool) {
            if (p.primaryTag == TacticalTag.CIVILIAN_AUX) {
                auxiliaries.add(p);
            } else if (p.primaryTag == TacticalTag.PHASE) {
                phaseShips.add(p);
            } else if (p.primaryTag == TacticalTag.CARRIER) {
                carriers.add(p);
            } else if (p.primaryTag == TacticalTag.MISSILE_CAPITAL) {
                missileCapitals.add(p);
            } else if (p.hullSize == HullSize.CAPITAL_SHIP) {
                capitals.add(p);
            } else if (p.primaryTag == TacticalTag.MISSILE_CRUISER) {
                missileCruisers.add(p);
            } else if (p.primaryTag == TacticalTag.STRIKE_CRUISER) {
                strikeCruisers.add(p);
            } else if (p.hullSize == HullSize.CRUISER) {
                lineCruisers.add(p);
            } else if (p.primaryTag == TacticalTag.MISSILE_DESTROYER) {
                missileDestroyers.add(p);
            } else if (p.primaryTag == TacticalTag.ESCORT_DESTROYER) {
                escortDestroyers.add(p);
            } else if (p.hullSize == HullSize.DESTROYER) {
                lineDestroyers.add(p);
            } else if (p.primaryTag == TacticalTag.MISSILE_FRIGATE) {
                missileFrigates.add(p);
            } else {
                screenFrigates.add(p);
            }
        }

        // 1. Form Carrier Divisions (Fleet Carrier, Light Carrier, Escort Carrier, or Mixed Carrier Division when paired with escorts).
        boolean keepStandaloneDestroyerDiv = (escortDestroyers.size() + lineDestroyers.size()) >= 2
                && battleGroup.missionContext != MissionContextType.REARGUARD_ACTION;

        List<ShipCapabilityProfile> fleetCarriers = new ArrayList<>();
        List<ShipCapabilityProfile> lightCarriers = new ArrayList<>();
        List<ShipCapabilityProfile> escortCarriers = new ArrayList<>();
        for (ShipCapabilityProfile c : carriers) {
            if (c.hullSize == HullSize.CAPITAL_SHIP) {
                fleetCarriers.add(c);
            } else if (c.hullSize == HullSize.CRUISER) {
                lightCarriers.add(c);
            } else {
                escortCarriers.add(c);
            }
        }

        if (battleGroup.missionContext == MissionContextType.REARGUARD_ACTION) {
            // In rearguard escape, pair carriers with organic escorts as Mixed Carrier Divisions
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
                    } else if (!screenFrigates.isEmpty()) {
                        escort = popNearest(primaryCarrier.ship, screenFrigates);
                    }
                    if (escort == null) break;
                    group.add(escort);
                    escortsWanted--;
                }
                DivisionState div = createDivision(battleGroup, DivisionArchetype.CARRIER_GROUP, group);
                newlyCreated.add(div);
            }
        } else {
            newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.FLEET_CARRIER_DIVISION, fleetCarriers, 2));
            newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.LIGHT_CARRIER_DIVISION, lightCarriers, 3));
            newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.ESCORT_CARRIER_DIVISION, escortCarriers, 4));
        }

        // 2. Form "Battle Div" & "Missile Battleship Div" from capitals.
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.MISSILE_BATTLESHIP_DIVISION, missileCapitals, 2));
        for (int i = 0; i < capitals.size(); i += 2) {
            List<ShipCapabilityProfile> group = new ArrayList<>();
            ShipCapabilityProfile cap = capitals.get(i);
            group.add(cap);
            if (i + 1 < capitals.size()) {
                group.add(capitals.get(i + 1));
            }
            if (group.size() == 1 && lineCruisers.size() == 1 && strikeCruisers.isEmpty() && missileCruisers.isEmpty()) {
                group.add(lineCruisers.remove(0));
            }
            DivisionState div = createDivision(battleGroup, DivisionArchetype.BATTLESHIP_DIVISION, group);
            newlyCreated.add(div);
        }

        // 3. Form Cruiser Divisions (missile cruisers, strike cruisers, line cruisers).
        if (strikeCruisers.size() == 1 && !lineCruisers.isEmpty()) {
            lineCruisers.add(strikeCruisers.remove(0));
        }
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.MISSILE_CRUISER_DIVISION, missileCruisers, MAX_MONOTYPE_DIV_SIZE));
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.STRIKE_CRUISER_DIVISION, strikeCruisers, MAX_MONOTYPE_DIV_SIZE));
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.CRUISER_DIVISION, lineCruisers, MAX_MONOTYPE_DIV_SIZE));

        // 4. Form Destroyer Divisions (missile destroyers vs line/escort destroyers).
        List<ShipCapabilityProfile> gunDestroyers = new ArrayList<>();
        gunDestroyers.addAll(escortDestroyers);
        gunDestroyers.addAll(lineDestroyers);

        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.MISSILE_DESTROYER_DIVISION, missileDestroyers, MAX_MONOTYPE_DIV_SIZE));
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.DESTROYER_DIVISION, gunDestroyers, MAX_MONOTYPE_DIV_SIZE));

        // 5. Form Frigate, Missile Frigate, & Phase Divisions
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.MISSILE_FRIGATE_DIVISION, missileFrigates, MAX_FRIGATE_DIV_SIZE));
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.FRIGATE_DIVISION, screenFrigates, MAX_FRIGATE_DIV_SIZE));

        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.PHASE_DIVISION, phaseShips, MAX_MONOTYPE_DIV_SIZE));
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.SERVICE_GROUP, auxiliaries, MAX_FRIGATE_DIV_SIZE));

        return newlyCreated;
    }

    private static List<DivisionState> chunkIntoDivisions(
            BattleGroupState battleGroup,
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
            out.add(createDivision(battleGroup, archetype, sub));
            idx += take;
        }
        return out;
    }

    private static DivisionState createDivision(
            BattleGroupState battleGroup,
            DivisionArchetype archetype,
            List<ShipCapabilityProfile> members) {

        boolean isAlliedAi = !members.isEmpty() && members.get(0).ship.isAlly();
        String sidePrefix = (battleGroup.side == 0) ? (isAlliedAi ? "Allied " : "") : "Enemy ";
        int number = nextDivisionNumberForBucket(battleGroup, archetype, isAlliedAi);

        ShipAPI leaderShip = pickDivisionFlagship(members, archetype);
        PersonAPI captain = (leaderShip != null) ? leaderShip.getCaptain() : null;

        DivisionNomenclature.NomenclatureCategory nomCat = battleGroup.nomenclatureCategory;
        int nameSeq = number;
        String displayName;
        while (true) {
            long seed = (leaderShip != null && leaderShip.getId() != null)
                    ? leaderShip.getId().hashCode() * 6364136223846793005L + nameSeq * 1442695040888963407L
                    : (nameSeq * 1442695040888963407L);
            String baseName = DivisionNomenclature.formatFactionDivisionName(
                    battleGroup.factionId,
                    nomCat,
                    archetype,
                    nameSeq,
                    captain,
                    seed);
            displayName = (nomCat == DivisionNomenclature.NomenclatureCategory.HUMAN_NAVAL)
                    ? (sidePrefix + baseName)
                    : baseName;

            if (nomCat != DivisionNomenclature.NomenclatureCategory.HUMAN_NAVAL) {
                break;
            }
            boolean duplicate = false;
            for (DivisionState existing : battleGroup.divisions) {
                if (existing != null && displayName.equalsIgnoreCase(existing.displayName)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate || nameSeq > number + 32) {
                break;
            }
            nameSeq++;
        }

        String divId = (battleGroup.side == 0 ? (isAlliedAi ? "ally_" : "player_") : "enemy_")
                + archetype.name().toLowerCase() + "_" + number;

        CommanderProfile cmdr = new CommanderProfile(captain, displayName + " Cmdr");

        DivisionState div = new DivisionState(divId, battleGroup.side, number, archetype, displayName, cmdr);
        div.nomenclatureCategory = nomCat;
        div.flagship = leaderShip;
        for (ShipCapabilityProfile prof : members) {
            addShipToDivision(battleGroup, div, prof);
        }
        recomputeDivisionCapabilities(div);
        if (battleGroup.side == 1 || isAlliedAi) {
            PermanentDivisionManager.FleetDoctrineConfig fDoc =
                    FactionFleetDoctrines.getDoctrineForFactionId(battleGroup.factionId, null);
            FactionFleetDoctrines.applyFactionDoctrineToCombatDivision(div, null, fDoc);
        }
        battleGroup.divisions.add(div);
        return div;
    }

    private static void addShipToDivision(BattleGroupState battleGroup, DivisionState div, ShipCapabilityProfile prof) {
        div.members.add(prof.ship);
        div.profiles.add(prof);
        battleGroup.shipIdToDivision.put(prof.ship.getId(), div);
    }

    public static void recomputeDivisionCapabilities(DivisionState div) {
        float fpSum = 0f;
        float speedSum = 0f;
        float rangeSum = 0f;
        int missileShips = 0;
        int pdShips = 0;
        int carrierShips = 0;

        for (ShipCapabilityProfile p : div.profiles) {
            fpSum += p.fpStrength;
            speedSum += p.baseMaxSpeed;
            rangeSum += p.avgWeaponRange;
            if (p.hasHeavyMissileLoadout) missileShips++;
            if (p.isPdScreenCapable) pdShips++;
            if (p.isCarrier) carrierShips++;
        }

        int count = Math.max(1, div.profiles.size());
        div.totalFpStrength = fpSum;
        div.avgMaxSpeed = speedSum / count;
        div.avgEngageRange = rangeSum / count;

        div.hasCarrierCapability = div.archetype.isCarrierArchetype() || (carrierShips > 0);
        div.hasMissileStrikeCapability = !div.hasCarrierCapability
                && div.archetype != DivisionArchetype.SERVICE_GROUP
                && (div.archetype.isMissileArchetype()
                || (div.archetype == DivisionArchetype.PHASE_DIVISION)
                || (div.archetype != DivisionArchetype.BATTLESHIP_DIVISION
                && div.archetype != DivisionArchetype.CRUISER_DIVISION
                && div.archetype != DivisionArchetype.STRIKE_CRUISER_DIVISION
                && missileShips > 0 && missileShips * 2 >= count));
        div.isScreenCapable = (div.archetype == DivisionArchetype.DESTROYER_DIVISION)
                || (div.archetype == DivisionArchetype.FRIGATE_DIVISION)
                || (div.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION)
                || (pdShips > 0);
        div.isFastFlanker = !div.hasCarrierCapability
                && div.archetype != DivisionArchetype.BATTLESHIP_DIVISION
                && div.archetype != DivisionArchetype.MISSILE_BATTLESHIP_DIVISION
                && div.archetype != DivisionArchetype.CRUISER_DIVISION
                && div.archetype != DivisionArchetype.MISSILE_CRUISER_DIVISION
                && div.archetype != DivisionArchetype.STRIKE_CRUISER_DIVISION
                && div.archetype != DivisionArchetype.SERVICE_GROUP
                && (div.avgMaxSpeed >= 105f
                || div.archetype == DivisionArchetype.FRIGATE_DIVISION
                || div.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION
                || div.archetype == DivisionArchetype.PHASE_DIVISION
                || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION);

        // Customizable Fleet Doctrine division-type modifiers (intra-spacing, inter-spacing, standoff, leash, opening & main missions, preferred space)
        PermanentDivisionManager.DoctrineDivisionType docType =
                PermanentDivisionManager.DoctrineDivisionType.forArchetype(div.archetype);
        boolean isPlayerOwnedDiv = (div.side == 0) && (div.flagship == null || !div.flagship.isAlly());
        if (isPlayerOwnedDiv) {
            PermanentDivisionManager.FleetDoctrineConfig docCfg = PermanentDivisionManager.getFleetDoctrine();
            div.stationSpacing = docCfg.getStationSpacing(docType);
            div.divisionInterval = docCfg.getDivisionInterval(docType);
            div.standoffRange = docCfg.getStandoffRange(docType);
            div.stationKeepingMult = docCfg.getStationKeepingMult(docType);
            div.openingPhaseDurationSeconds = docCfg.getOpeningDurationSeconds();
            div.openingMission = docCfg.getOpeningMission(docType);
            div.mainMission = docCfg.getMainMission(docType);
            div.lineStation = docCfg.getLineStation(docType);
        } else {
            div.stationSpacing = docType.defaultStationSpacingUnits;
            div.divisionInterval = docType.defaultDivisionIntervalUnits;
            div.standoffRange = docType.defaultStandoffRangeUnits;
            div.stationKeepingMult = docType.defaultStationKeepingMult;
            div.openingPhaseDurationSeconds = 45f;
            div.openingMission = docType.defaultOpeningMission;
            div.mainMission = docType.defaultMainMission;
            div.lineStation = docType.defaultLineStation;
        }

        // Desired cohesion radius scales by archetype, ideal intra-division spacing, and commander strictness
        float baseRadius;
        switch (div.archetype) {
            case BATTLESHIP_DIVISION:
            case MISSILE_BATTLESHIP_DIVISION:
            case FLEET_CARRIER_DIVISION:
            case LIGHT_CARRIER_DIVISION:
            case ESCORT_CARRIER_DIVISION:
            case CARRIER_GROUP:
                baseRadius = 900f;
                break;
            case CRUISER_DIVISION:
            case MISSILE_CRUISER_DIVISION:
            case STRIKE_CRUISER_DIVISION:
            case FORCE_DIVISION:
                baseRadius = 850f;
                break;
            case DESTROYER_DIVISION:
            case MISSILE_DESTROYER_DIVISION:
                baseRadius = 750f;
                break;
            case FRIGATE_DIVISION:
            case MISSILE_FRIGATE_DIVISION:
            case PHASE_DIVISION:
            default:
                baseRadius = 680f;
                break;
        }
        div.stationKeepingRadius = computeStationKeepingRadius(
                div.archetype,
                count,
                div.stationSpacing,
                div.stationKeepingMult,
                div.commander.stationKeepingStrictness);
    }

    /**
     * Computes the division's station-keeping radius from the actual RMS geometry of an N-ship
     * line-abreast formation at `stationSpacing` plus combat maneuvering headroom, so holding
     * configured station spacing never triggers false cohesion alarms.
     */
    public static float computeStationKeepingRadius(
            DivisionArchetype archetype,
            int shipCount,
            float stationSpacing,
            float stationKeepingMult,
            float strictness) {
        float baseRadius;
        if (archetype == null) {
            baseRadius = 750f;
        } else {
            switch (archetype) {
                case BATTLESHIP_DIVISION:
                case MISSILE_BATTLESHIP_DIVISION:
                case FLEET_CARRIER_DIVISION:
                case LIGHT_CARRIER_DIVISION:
                case ESCORT_CARRIER_DIVISION:
                case CARRIER_GROUP:
                    baseRadius = 900f;
                    break;
                case CRUISER_DIVISION:
                case MISSILE_CRUISER_DIVISION:
                case STRIKE_CRUISER_DIVISION:
                case FORCE_DIVISION:
                    baseRadius = 850f;
                    break;
                case DESTROYER_DIVISION:
                case MISSILE_DESTROYER_DIVISION:
                    baseRadius = 780f;
                    break;
                case FRIGATE_DIVISION:
                case MISSILE_FRIGATE_DIVISION:
                case PHASE_DIVISION:
                default:
                    baseRadius = 720f;
                    break;
            }
        }
        int n = Math.max(1, Math.min(10, shipCount));
        float rankSqSum = 0f;
        for (int i = 0; i < n; i++) {
            int rank = (i + 1) / 2;
            rankSqSum += (rank * rank) * 1.0256f; // includes 0.16x depth offset squared
        }
        float slotRms = stationSpacing * (float) Math.sqrt(rankSqSum / n);
        float spacingBasedRadius = slotRms + Math.max(380f, stationSpacing * 0.75f);
        return (Math.max(baseRadius, spacingBasedRadius) * Math.max(0.75f, stationKeepingMult))
                / Math.max(0.75f, Math.min(1.18f, strictness));
    }

    private static int nextDivisionNumberForBucket(
            BattleGroupState battleGroup,
            DivisionArchetype archetype,
            boolean isAlliedAi) {
        String bucketKey = DivisionNomenclature.resolvePrefixBucketKey(archetype);
        int count = 0;
        for (DivisionState existing : battleGroup.divisions) {
            boolean existingAlly = existing.flagship != null && existing.flagship.isAlly();
            if (existingAlly == isAlliedAi
                    && bucketKey.equals(DivisionNomenclature.resolvePrefixBucketKey(existing.archetype))) {
                count++;
            }
        }
        return count + 1;
    }

    private static ShipAPI pickDivisionFlagship(List<ShipCapabilityProfile> members, DivisionArchetype archetype) {
        ShipCapabilityProfile best = null;
        float bestScore = -1f;
        boolean preferCarrier = archetype != null && archetype.isCarrierArchetype();
        for (ShipCapabilityProfile p : members) {
            float score = p.fpStrength;
            if (p.ship.getCaptain() != null && !p.ship.getCaptain().isDefault()) {
                // Ships that already have a commander naturally become division flagships
                score += 1000f + p.ship.getCaptain().getStats().getLevel() * 25f;
            }
            if (preferCarrier && (p.isCarrier || p.ship.getNumFighterBays() > 0)) {
                score += 400f;
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
            BattleGroupState battleGroup,
            ShipCapabilityProfile prof) {
        for (DivisionState div : battleGroup.divisions) {
            boolean divIsAlly = (div.flagship != null && div.flagship.isAlly());
            if (divIsAlly != prof.ship.isAlly()) continue;
            int aliveCount = div.getAliveMembers().size();
            int maxCap = (div.archetype == DivisionArchetype.FRIGATE_DIVISION
                    || div.archetype == DivisionArchetype.SERVICE_GROUP)
                    ? MAX_FRIGATE_DIV_SIZE
                    : MAX_MONOTYPE_DIV_SIZE;
            if (aliveCount >= maxCap) continue;
            // Only merge mid-battle reinforcement ships into an existing division if they spawn within <= 2200u
            // of that division's centroid; otherwise form a new reinforcement division so a ship spawning 4000u
            // away at the map edge doesn't spike the frontline division's spreadRadius and force CLOSE_UP!
            if (prof.ship != null && prof.ship.getLocation() != null && (div.centroid.x != 0f || div.centroid.y != 0f)) {
                float dx = prof.ship.getLocation().x - div.centroid.x;
                float dy = prof.ship.getLocation().y - div.centroid.y;
                if ((dx * dx + dy * dy) > (2200f * 2200f)) continue;
            }

            if (prof.primaryTag == TacticalTag.CIVILIAN_AUX && div.archetype == DivisionArchetype.SERVICE_GROUP) {
                return div;
            }
            if (prof.primaryTag == TacticalTag.PHASE && div.archetype == DivisionArchetype.PHASE_DIVISION) {
                return div;
            }
            if (prof.primaryTag == TacticalTag.CARRIER && div.archetype.isCarrierArchetype()) {
                return div;
            }
            if (prof.hullSize == HullSize.CAPITAL_SHIP && div.archetype == DivisionArchetype.BATTLESHIP_DIVISION) {
                return div;
            }
            if (prof.hullSize == HullSize.DESTROYER
                    && (div.archetype == DivisionArchetype.DESTROYER_DIVISION
                    || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION)) {
                return div;
            }
            if (prof.hullSize == HullSize.CRUISER
                    && (div.archetype == DivisionArchetype.CRUISER_DIVISION
                    || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION)) {
                return div;
            }
            if (prof.hullSize == HullSize.FRIGATE
                    && (div.archetype == DivisionArchetype.FRIGATE_DIVISION
                    || (div.archetype == DivisionArchetype.CARRIER_GROUP && aliveCount < 3))) {
                return div;
            }
        }
        return null;
    }

    private static ShipCapabilityProfile popNearest(ShipAPI station, List<ShipCapabilityProfile> candidates) {
        if (candidates.isEmpty()) return null;
        int bestIdx = 0;
        float bestDistSq = Float.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            ShipAPI s = candidates.get(i).ship;
            float dx = s.getLocation().x - station.getLocation().x;
            float dy = s.getLocation().y - station.getLocation().y;
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
        if (!ship.isAlive() || ship.isHulk() || ship.isExpired()) return false;
        if (ship.isFighter() || ship.isDrone() || ship.isStation() || ship.isStationModule()) return false;
        if (ship.getParentStation() != null) return false;
        HullSize size = ship.getHullSize();
        return size == HullSize.FRIGATE
                || size == HullSize.DESTROYER
                || size == HullSize.CRUISER
                || size == HullSize.CAPITAL_SHIP;
    }
}
