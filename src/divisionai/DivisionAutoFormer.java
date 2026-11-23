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
    private static volatile CombatEngineAPI activeCombatEngine = null;

    public static void setActiveCombatEngine(CombatEngineAPI engine) {
        activeCombatEngine = engine;
    }

    public static MissionContextType detectMissionContext(CombatEngineAPI engine, int side) {
        if (engine == null) return MissionContextType.FLEET_ACTION;

        BattleCreationContext ctx = engine.getContext();
        List<BattleObjectiveAPI> objectives = engine.getObjectives();
        boolean hasObjectives = objectives != null && !objectives.isEmpty();

        FleetGoal myGoal = (ctx != null) ? ((side == 0) ? ctx.getPlayerGoal() : ctx.getOtherGoal()) : null;
        FleetGoal enemyGoal = (ctx != null) ? ((side == 0) ? ctx.getOtherGoal() : ctx.getPlayerGoal()) : null;
        if (myGoal == null) {
            CombatFleetManagerAPI myFm = engine.getFleetManager(side);
            if (myFm != null) myGoal = myFm.getGoal();
        }
        if (enemyGoal == null) {
            CombatFleetManagerAPI enemyFm = engine.getFleetManager(1 - side);
            if (enemyFm != null) enemyGoal = enemyFm.getGoal();
        }
        if (myGoal == FleetGoal.ESCAPE) {
            return MissionContextType.REARGUARD_ACTION;
        }
        if (enemyGoal == FleetGoal.ESCAPE) {
            return MissionContextType.INTERCEPTION;
        }
        if (ctx != null && ctx.fightToTheLast) {
            return MissionContextType.FIGHT_TO_THE_LAST;
        }
        if (engine.getCustomData() != null && Boolean.TRUE.equals(engine.getCustomData().get("can_deploy_left_right"))) {
            return (side == 0) ? MissionContextType.INTERCEPTION : MissionContextType.REARGUARD_ACTION;
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
        String fallback = (side == 0)
                ? DivisionNomenclature.resolveOtcCommanderTitle(null, true, false, null)
                : "Hostile Command";
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
        return new CommanderProfile(commander, "Allied Command");
    }

    /**
     * Synchronizes ONLY the player's campaign Permanent Divisions onto deployed player-controlled ships.
     * Never forcibly assigns unassigned player ships to divisions on the battle layer.
     */
    public static List<DivisionState> syncPlayerPermanentDivisionsOnly(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            List<ShipAPI> playerShips) {
        if (BattlefieldFirepowerEvaluator.isSimulationEngine(engine)) {
            return new ArrayList<>();
        }
        if (engine != null) {
            activeCombatEngine = engine;
        }

        List<DivisionState> newlyCreated = new ArrayList<>();
        Set<String> assignedIds = new HashSet<>(playerGroup.shipIdToDivision.keySet());

        List<ShipCapabilityProfile> unassigned = new ArrayList<>();
        for (ShipAPI ship : playerShips) {
            if (ship == null || ship.isAlly() || !isEligibleCombatHull(ship)) continue;
            String sId = ship.getId();
            if (!assignedIds.contains(sId) && !playerGroup.checkedUnassignedShipIds.contains(sId)) {
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

        // Inherit campaign Permanent Divisions ONLY; if none exist at all (e.g. standalone Missions without a
        // campaign fleet), auto-form divisions so the player's fleet still deploys in formation.
        playerGroup.nomenclatureCategory = DivisionNomenclature.getPlayerNomenclatureCategory();
        PermanentDivisionManager.applyPermanentDivisionsInCombat(
                playerGroup,
                unassigned,
                newlyCreated);
        if (playerGroup.divisions.isEmpty() && PermanentDivisionManager.getPermanentDivisions().isEmpty()) {
            return syncDivisionsForSide(engine, playerGroup, playerShips);
        }
        for (int i = 0, n = unassigned.size(); i < n; i++) {
            ShipCapabilityProfile p = unassigned.get(i);
            if (p != null && p.ship != null && p.ship.getId() != null
                    && !playerGroup.shipIdToDivision.containsKey(p.ship.getId())) {
                playerGroup.checkedUnassignedShipIds.add(p.ship.getId());
            }
        }

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
        if (BattlefieldFirepowerEvaluator.isSimulationEngine(engine)) {
            return new ArrayList<>();
        }
        if (engine != null) {
            activeCombatEngine = engine;
        }

        List<DivisionState> newlyCreated = new ArrayList<>();
        Set<String> assignedIds = new HashSet<>(battleGroup.shipIdToDivision.keySet());

        List<ShipCapabilityProfile> unassigned = new ArrayList<>();
        if (eligibleShips != null) {
            for (ShipAPI ship : eligibleShips) {
                if (ship == null || !isEligibleCombatHull(ship)) continue;
                String sId = ship.getId();
                if (!assignedIds.contains(sId) && !battleGroup.checkedUnassignedShipIds.contains(sId)) {
                    unassigned.add(new ShipCapabilityProfile(ship));
                }
            }
        }

        if (eligibleShips != null && !eligibleShips.isEmpty() && (!battleGroup.battleGroupDoctrineSynced || !unassigned.isEmpty())) {
            battleGroup.battleGroupDoctrineSynced = true;
            PermanentDivisionManager.MissionScenarioBundle missionBundle =
                    (battleGroup.side == 1) ? PermanentDivisionManager.resolveActiveMissionScenario() : null;
            if (missionBundle != null) {
                battleGroup.factionId = missionBundle.enemyFactionId;
                battleGroup.nomenclatureCategory = missionBundle.enemyNomenclature;
                PermanentDivisionManager.FleetDoctrineConfig fDoc = missionBundle.enemyDoctrine;
                if (fDoc != null) {
                    battleGroup.authority = fDoc.getOtcAuthority();
                    battleGroup.fleetWideFormation = fDoc.getFleetWideFormation();
                    battleGroup.multiDivisionSetpieceDoctrine = fDoc.getMultiDivisionSetpiece();
                    battleGroup.operationalAnchor = fDoc.getOperationalAnchor();
                    battleGroup.objectiveCapturePolicy = fDoc.getObjectiveCapturePolicy();
                    battleGroup.decisiveArm = fDoc.getDecisiveArm();
                    battleGroup.engagementDiscipline = fDoc.getEngagementDiscipline();
                    battleGroup.deployInFleetFormation = fDoc.isDeployInFleetFormation();
                    battleGroup.orchestratedAdvance = fDoc.isOrchestratedAdvance();
                    battleGroup.plays.clear();
                    battleGroup.plays.addAll(fDoc.getEnabledPlays());
                }
            } else {
                String detectedFactionId = resolveFactionIdForCombatShips(eligibleShips, engine, battleGroup.side);
                battleGroup.factionId = detectedFactionId;
                battleGroup.nomenclatureCategory = DivisionNomenclature.resolveCategoryForCombatShips(
                        eligibleShips,
                        detectedFactionId,
                        false);
                if (!battleGroup.campaignDoctrineInherited) {
                    PermanentDivisionManager.FleetDoctrineConfig fDoc =
                            FactionFleetDoctrines.getDoctrineForFactionId(detectedFactionId, null);
                    battleGroup.authority = fDoc.getOtcAuthority();
                    battleGroup.fleetWideFormation = fDoc.getFleetWideFormation();
                    battleGroup.multiDivisionSetpieceDoctrine = fDoc.getMultiDivisionSetpiece();
                    battleGroup.operationalAnchor = fDoc.getOperationalAnchor();
                    battleGroup.objectiveCapturePolicy = fDoc.getObjectiveCapturePolicy();
                    battleGroup.decisiveArm = fDoc.getDecisiveArm();
                    battleGroup.engagementDiscipline = fDoc.getEngagementDiscipline();
                    battleGroup.deployInFleetFormation = fDoc.isDeployInFleetFormation();
                    battleGroup.orchestratedAdvance = fDoc.isOrchestratedAdvance();
                    battleGroup.plays.clear();
                    battleGroup.plays.addAll(fDoc.getEnabledPlays());
                    instantiatePreCombatConductOfBattle(
                            engine,
                            battleGroup,
                            detectedFactionId,
                            fDoc,
                            eligibleShips);
                }
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

        // 0. If this is a baked-in Mission Scenario, bind Enemy hulls directly to the scenario's pre-baked enemy divisions!
        if (battleGroup.side == 1) {
            unassigned = PermanentDivisionManager.applyMissionEnemyDivisionsInCombat(
                    battleGroup,
                    unassigned,
                    newlyCreated);
            if (unassigned.isEmpty()) {
                return newlyCreated;
            }
        }

        // 1. Inherit campaign-sorted divisions from any instanced NPC CampaignFleetAPI first!
        // Any ship that belongs to an instanced NPC CampaignFleetAPI but was NOT placed in a division by
        // ensureFleetDivisionsSorted (due to the fleet's Organization Rating) is an intentionally independent
        // ship and must remain unassigned in combat!
        List<ShipCapabilityProfile> campaignIndependentOrLeftover = FactionFleetDoctrines.applyCampaignNpcDivisionsInCombat(
                battleGroup,
                unassigned,
                newlyCreated);

        List<ShipCapabilityProfile> nonCampaignUnassigned = new ArrayList<>();
        for (ShipCapabilityProfile prof : campaignIndependentOrLeftover) {
            if (prof == null || prof.ship == null) continue;
            com.fs.starfarer.api.fleet.FleetMemberAPI fm = prof.ship.getFleetMember();
            com.fs.starfarer.api.campaign.CampaignFleetAPI srcFleet =
                    FactionFleetDoctrines.resolveTrueCampaignSourceFleet(fm);
            if (srcFleet != null
                    && !srcFleet.isPlayerFleet()
                    && !srcFleet.isStationMode()
                    && fm != null
                    && FactionFleetDoctrines.isKnownCampaignIndependentMember(srcFleet, fm.getId())) {
                // Intentionally independent ship from a campaign NPC fleet's Organization Rating — record once so future C2 ticks skip in O(1)!
                if (prof.ship.getId() != null) {
                    battleGroup.checkedUnassignedShipIds.add(prof.ship.getId());
                }
                continue;
            }
            nonCampaignUnassigned.add(prof);
        }
        unassigned = nonCampaignUnassigned;

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

    private static PermanentDivisionManager.FleetDoctrineConfig resolveDoctrineForPool(
            BattleGroupState battleGroup,
            List<ShipCapabilityProfile> pool) {
        boolean isAlliedPool = pool != null && !pool.isEmpty() && pool.get(0).ship != null && pool.get(0).ship.isAlly();
        if (battleGroup != null && (battleGroup.side == 1
                || isAlliedPool
                || (battleGroup.factionId != null
                && !battleGroup.factionId.isEmpty()
                && !"player".equalsIgnoreCase(battleGroup.factionId)))) {
            return FactionFleetDoctrines.getDoctrineForFactionId(battleGroup.factionId, null);
        }
        return PermanentDivisionManager.getFleetDoctrine();
    }

    private static PermanentDivisionManager.AutoAssignHull toAutoAssignHull(HullSize size) {
        if (size == null) return PermanentDivisionManager.AutoAssignHull.FRIGATE;
        switch (size) {
            case CAPITAL_SHIP: return PermanentDivisionManager.AutoAssignHull.BATTLESHIP;
            case CRUISER:      return PermanentDivisionManager.AutoAssignHull.CRUISER;
            case DESTROYER:    return PermanentDivisionManager.AutoAssignHull.DESTROYER;
            case FRIGATE:
            default:           return PermanentDivisionManager.AutoAssignHull.FRIGATE;
        }
    }

    public static List<DivisionState> formDivisionsFromPool(
            BattleGroupState battleGroup,
            List<ShipCapabilityProfile> pool) {
        List<DivisionState> newlyCreated = new ArrayList<>();
        PermanentDivisionManager.FleetDoctrineConfig doc = resolveDoctrineForPool(battleGroup, pool);

        List<ShipCapabilityProfile> carriers = new ArrayList<>();
        List<ShipCapabilityProfile> battlecarriers = new ArrayList<>();
        List<ShipCapabilityProfile> battlecruisers = new ArrayList<>();
        List<ShipCapabilityProfile> capitals = new ArrayList<>();
        List<ShipCapabilityProfile> missileCapitals = new ArrayList<>();
        List<ShipCapabilityProfile> strikeCruisers = new ArrayList<>();
        List<ShipCapabilityProfile> missileCruisers = new ArrayList<>();
        List<ShipCapabilityProfile> phaseCruisers = new ArrayList<>();
        List<ShipCapabilityProfile> lineCruisers = new ArrayList<>();
        List<ShipCapabilityProfile> strikeDestroyers = new ArrayList<>();
        List<ShipCapabilityProfile> missileDestroyers = new ArrayList<>();
        List<ShipCapabilityProfile> escortDestroyers = new ArrayList<>();
        List<ShipCapabilityProfile> lineDestroyers = new ArrayList<>();
        List<ShipCapabilityProfile> strikeFrigates = new ArrayList<>();
        List<ShipCapabilityProfile> missileFrigates = new ArrayList<>();
        List<ShipCapabilityProfile> screenFrigates = new ArrayList<>();
        List<ShipCapabilityProfile> phaseShips = new ArrayList<>();
        List<ShipCapabilityProfile> missilePhaseShips = new ArrayList<>();
        List<ShipCapabilityProfile> auxiliaries = new ArrayList<>();

        for (ShipCapabilityProfile p : pool) {
            PermanentDivisionManager.AutoAssignHull aaHull = toAutoAssignHull(p.hullSize);
            if (p.primaryTag == TacticalTag.CIVILIAN_AUX) {
                auxiliaries.add(p);
            } else if (p.primaryTag == TacticalTag.MISSILE_PHASE) {
                PermanentDivisionManager.AutoAssignHull phaseHull =
                        (p.hullSize == HullSize.CAPITAL_SHIP) ? PermanentDivisionManager.AutoAssignHull.CRUISER : aaHull;
                if (doc == null || doc.isSchemeEnabled(PermanentDivisionManager.AutoAssignCapability.MISSILE_PHASE, phaseHull)) {
                    missilePhaseShips.add(p);
                } else if (p.hullSize == HullSize.CRUISER || p.hullSize == HullSize.CAPITAL_SHIP) {
                    phaseCruisers.add(p);
                } else {
                    phaseShips.add(p);
                }
            } else if (p.primaryTag == TacticalTag.PHASE_CRUISER) {
                if (doc == null || doc.isSchemeEnabled(PermanentDivisionManager.AutoAssignCapability.PHASE, PermanentDivisionManager.AutoAssignHull.CRUISER)) {
                    phaseCruisers.add(p);
                } else {
                    phaseShips.add(p);
                }
            } else if (p.primaryTag == TacticalTag.PHASE) {
                phaseShips.add(p);
            } else if (p.primaryTag == TacticalTag.BATTLECARRIER) {
                if (doc == null || doc.isSchemeEnabled(PermanentDivisionManager.AutoAssignCapability.BATTLECARRIER, PermanentDivisionManager.AutoAssignHull.BATTLESHIP)) {
                    battlecarriers.add(p);
                } else {
                    capitals.add(p);
                }
            } else if (p.primaryTag == TacticalTag.CARRIER) {
                if (doc == null || doc.isSchemeEnabled(PermanentDivisionManager.AutoAssignCapability.CARRIER, aaHull)) {
                    carriers.add(p);
                } else if (p.hullSize == HullSize.CAPITAL_SHIP) {
                    capitals.add(p);
                } else if (p.hullSize == HullSize.CRUISER) {
                    lineCruisers.add(p);
                } else {
                    escortDestroyers.add(p);
                }
            } else if (p.primaryTag == TacticalTag.MISSILE_CAPITAL) {
                if (doc == null || doc.isSchemeEnabled(PermanentDivisionManager.AutoAssignCapability.MISSILE, aaHull)) {
                    missileCapitals.add(p);
                } else {
                    capitals.add(p);
                }
            } else if (p.primaryTag == TacticalTag.STRIKE_CAPITAL) {
                if (doc == null || doc.isSchemeEnabled(PermanentDivisionManager.AutoAssignCapability.STRIKE, aaHull)) {
                    battlecruisers.add(p);
                } else {
                    capitals.add(p);
                }
            } else if (p.hullSize == HullSize.CAPITAL_SHIP) {
                capitals.add(p);
            } else if (p.primaryTag == TacticalTag.MISSILE_CRUISER) {
                if (doc == null || doc.isSchemeEnabled(PermanentDivisionManager.AutoAssignCapability.MISSILE, aaHull)) {
                    missileCruisers.add(p);
                } else {
                    lineCruisers.add(p);
                }
            } else if (p.primaryTag == TacticalTag.STRIKE_CRUISER) {
                if (doc == null || doc.isSchemeEnabled(PermanentDivisionManager.AutoAssignCapability.STRIKE, aaHull)) {
                    strikeCruisers.add(p);
                } else {
                    lineCruisers.add(p);
                }
            } else if (p.hullSize == HullSize.CRUISER) {
                lineCruisers.add(p);
            } else if (p.primaryTag == TacticalTag.MISSILE_DESTROYER) {
                if (doc == null || doc.isSchemeEnabled(PermanentDivisionManager.AutoAssignCapability.MISSILE, aaHull)) {
                    missileDestroyers.add(p);
                } else {
                    lineDestroyers.add(p);
                }
            } else if (p.primaryTag == TacticalTag.STRIKE_DESTROYER) {
                if (doc == null || doc.isSchemeEnabled(PermanentDivisionManager.AutoAssignCapability.STRIKE, aaHull)) {
                    strikeDestroyers.add(p);
                } else {
                    lineDestroyers.add(p);
                }
            } else if (p.primaryTag == TacticalTag.ESCORT_DESTROYER) {
                escortDestroyers.add(p);
            } else if (p.hullSize == HullSize.DESTROYER) {
                lineDestroyers.add(p);
            } else if (p.primaryTag == TacticalTag.MISSILE_FRIGATE) {
                if (doc == null || doc.isSchemeEnabled(PermanentDivisionManager.AutoAssignCapability.MISSILE, aaHull)) {
                    missileFrigates.add(p);
                } else {
                    screenFrigates.add(p);
                }
            } else if (p.primaryTag == TacticalTag.STRIKE_FRIGATE) {
                if (doc == null || doc.isSchemeEnabled(PermanentDivisionManager.AutoAssignCapability.STRIKE, aaHull)) {
                    strikeFrigates.add(p);
                } else {
                    screenFrigates.add(p);
                }
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

        // 2. Form Capital Divisions (Battlecarrier, Battlecruiser, Missile Battleship, Battleship).
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.BATTLECARRIER_DIVISION, battlecarriers, 2));
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.BATTLECRUISER_DIVISION, battlecruisers, 2));
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

        // 3. Form Cruiser Divisions (missile cruisers, strike cruisers, phase cruisers, line cruisers).
        if (strikeCruisers.size() == 1 && !lineCruisers.isEmpty()) {
            lineCruisers.add(strikeCruisers.remove(0));
        }
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.MISSILE_CRUISER_DIVISION, missileCruisers, MAX_MONOTYPE_DIV_SIZE));
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.STRIKE_CRUISER_DIVISION, strikeCruisers, MAX_MONOTYPE_DIV_SIZE));
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.PHASE_CRUISER_DIVISION, phaseCruisers, 3));
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.CRUISER_DIVISION, lineCruisers, MAX_MONOTYPE_DIV_SIZE));

        // 4. Form Destroyer Divisions (missile destroyers, strike destroyers, line/escort destroyers).
        List<ShipCapabilityProfile> gunDestroyers = new ArrayList<>();
        gunDestroyers.addAll(escortDestroyers);
        gunDestroyers.addAll(lineDestroyers);
        if (strikeDestroyers.size() == 1 && !gunDestroyers.isEmpty()) {
            gunDestroyers.add(strikeDestroyers.remove(0));
        }

        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.MISSILE_DESTROYER_DIVISION, missileDestroyers, MAX_MONOTYPE_DIV_SIZE));
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.STRIKE_DESTROYER_DIVISION, strikeDestroyers, MAX_MONOTYPE_DIV_SIZE));
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.DESTROYER_DIVISION, gunDestroyers, MAX_MONOTYPE_DIV_SIZE));

        // 5. Form Frigate, Strike Frigate, Missile Frigate, Phase, & Missile Phase Divisions
        if (strikeFrigates.size() == 1 && !screenFrigates.isEmpty()) {
            screenFrigates.add(strikeFrigates.remove(0));
        }
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.MISSILE_FRIGATE_DIVISION, missileFrigates, MAX_FRIGATE_DIV_SIZE));
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.STRIKE_FRIGATE_DIVISION, strikeFrigates, MAX_FRIGATE_DIV_SIZE));
        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.FRIGATE_DIVISION, screenFrigates, MAX_FRIGATE_DIV_SIZE));

        newlyCreated.addAll(chunkIntoDivisions(battleGroup, DivisionArchetype.MISSILE_PHASE_DIVISION, missilePhaseShips, MAX_MONOTYPE_DIV_SIZE));
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
            displayName = DivisionNomenclature.formatFactionDivisionName(
                    battleGroup.factionId,
                    nomCat,
                    archetype,
                    nameSeq,
                    captain,
                    seed);

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
        int primaryMissileShips = 0;
        int pdShips = 0;
        int carrierShips = 0;
        int ramShips = 0;

        for (ShipCapabilityProfile p : div.profiles) {
            fpSum += p.fpStrength;
            speedSum += p.baseMaxSpeed;
            rangeSum += p.avgWeaponRange;
            if (p.hasHeavyMissileLoadout) missileShips++;
            if (p.isPrimaryMissileBoat || p.hasMissileAutoforge) primaryMissileShips++;
            if (p.isPdScreenCapable) pdShips++;
            if (p.isCarrier || p.isBattlecarrier) carrierShips++;
            if (p.ship != null && ShipSystemCoordinator.getSkillProfile(p.ship).isDesignedToRam) {
                ramShips++;
            }
        }
        div.ramCapableMemberCount = ramShips;
        if (ramShips > 0 && div.rammingMode == PermanentDivisionManager.DivisionRammingMode.OFF) {
            div.rammingMode = PermanentDivisionManager.DivisionRammingMode.GLANCING_SKIRMISH;
        }

        int count = Math.max(1, div.profiles.size());
        div.totalFpStrength = fpSum;
        div.avgMaxSpeed = speedSum / count;
        div.avgEngageRange = rangeSum / count;
        if (!div.profiles.isEmpty()) {
            List<Float> mainBatRanges = new ArrayList<>(div.profiles.size());
            for (int i = 0, n = div.profiles.size(); i < n; i++) {
                ShipCapabilityProfile p = div.profiles.get(i);
                if (p == null) continue;
                if (p.ship != null && div.detachedStragglerIds != null
                        && div.detachedStragglerIds.contains(p.ship.getId())) {
                    continue;
                }
                float r = (p.mainBatteryGunRange > 150f) ? p.mainBatteryGunRange : p.avgWeaponRange;
                if (r > 150f) {
                    mainBatRanges.add(r);
                }
            }
            if (!mainBatRanges.isEmpty()) {
                Collections.sort(mainBatRanges);
                int sz = mainBatRanges.size();
                div.medianMainBatteryRange = 0.5f * (mainBatRanges.get((sz - 1) / 2) + mainBatRanges.get(sz / 2));
                div.minMainBatteryRange = mainBatRanges.get(Math.min(sz - 1, (int) ((sz - 1) * 0.20f)));
                if (div.archetype != null
                        && !div.archetype.isCarrierArchetype()
                        && !div.archetype.isMissileArchetype()
                        && div.medianMainBatteryRange > 250f) {
                    div.avgEngageRange = div.medianMainBatteryRange;
                }
            }
        }
        div.primaryMissileLoadoutRatio = (float) primaryMissileShips / (float) count;

        div.hasCarrierCapability = div.archetype.hasStrikeCraftCapability() || (carrierShips > 0);
        div.hasMissileStrikeCapability = !div.archetype.isCarrierArchetype()
                && div.archetype != DivisionArchetype.SERVICE_GROUP
                && (div.archetype.isMissileArchetype()
                || (div.archetype == DivisionArchetype.PHASE_DIVISION)
                || (div.archetype == DivisionArchetype.PHASE_CRUISER_DIVISION)
                || (div.archetype != DivisionArchetype.BATTLESHIP_DIVISION
                && div.archetype != DivisionArchetype.BATTLECRUISER_DIVISION
                && div.archetype != DivisionArchetype.BATTLECARRIER_DIVISION
                && div.archetype != DivisionArchetype.CRUISER_DIVISION
                && div.archetype != DivisionArchetype.STRIKE_CRUISER_DIVISION
                && missileShips > 0 && missileShips * 2 >= count));
        div.isScreenCapable = (div.archetype == DivisionArchetype.DESTROYER_DIVISION)
                || (div.archetype == DivisionArchetype.STRIKE_DESTROYER_DIVISION)
                || (div.archetype == DivisionArchetype.FRIGATE_DIVISION)
                || (div.archetype == DivisionArchetype.STRIKE_FRIGATE_DIVISION)
                || (!div.archetype.isHeavyLineArchetype()
                && !div.archetype.isCarrierArchetype()
                && !div.archetype.isPhaseArchetype()
                && !div.archetype.isMissileArchetype()
                && div.archetype != DivisionArchetype.SERVICE_GROUP
                && pdShips > 0);
        div.isFastFlanker = !div.archetype.isCarrierArchetype()
                && !div.archetype.isHeavyLineArchetype()
                && div.archetype != DivisionArchetype.SERVICE_GROUP
                && (div.avgMaxSpeed >= 105f
                || div.archetype == DivisionArchetype.BATTLECRUISER_DIVISION
                || div.archetype.isStrikeArchetype()
                || div.archetype.isLightSkirmisherArchetype()
                || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION);

        // Customizable Fleet Doctrine division-type modifiers (intra-spacing, inter-spacing, standoff, leash, opening & main missions, preferred space)
        PermanentDivisionManager.DoctrineDivisionType docType =
                PermanentDivisionManager.DoctrineDivisionType.forArchetype(div.archetype);
        boolean isPlayerOwnedDiv = (div.side == 0) && (div.flagship == null || !div.flagship.isAlly());
        if (isPlayerOwnedDiv) {
            PermanentDivisionManager.FleetDoctrineConfig docCfg = PermanentDivisionManager.getFleetDoctrine();
            div.stationSpacing = docCfg.getStationSpacing(docType);
            div.divisionInterval = docCfg.getDivisionInterval(docType);
            div.openingPhaseDurationSeconds = docCfg.getOpeningDurationSeconds();
            div.openingMission = docCfg.getOpeningMission(docType);
            div.mainMission = docCfg.getMainMission(docType);
            int cfgStandoff = docCfg.getStandoffRange(docType);
            div.hasExplicitCustomStandoff = (cfgStandoff != docType.defaultStandoffRangeUnits)
                    && (cfgStandoff != PermanentDivisionManager.getEffectiveDefaultStandoffRangeUnits(docType));
            div.standoffRange = PermanentDivisionManager.adaptDoctrinalStandoffToLiveEnvelope(
                    div, docType, cfgStandoff, div.hasExplicitCustomStandoff);
            div.stationKeepingMult = docCfg.getStationKeepingMult(docType);
            div.doctrinalMovementStickiness = docCfg.getMovementStickiness(docType);
            div.activeMovementStickiness = div.doctrinalMovementStickiness;
            div.speedGovernorEnabled = docCfg.isSpeedGovernorEnabled();
            div.doctrinalEngineTelegraph = docCfg.getEngineTelegraph(docType);
            if (!div.speedGovernorEnabled) {
                div.activeEngineTelegraph = PermanentDivisionManager.DivisionEngineTelegraph.FLANK;
            } else if (div.doctrinalEngineTelegraph != PermanentDivisionManager.DivisionEngineTelegraph.AUTO) {
                div.activeEngineTelegraph = div.doctrinalEngineTelegraph;
            }
            div.strikeCraftOpeningMission = docCfg.getStrikeCraftOpeningMission(docType);
            div.strikeCraftMainMission = docCfg.getStrikeCraftMainMission(docType);
            div.strikeCraftCapReserve = docCfg.getStrikeCraftCapReserve(docType);
            div.lineStation = docCfg.getLineStation(docType);
            div.doctrinalFormation = docCfg.getFormation(docType);
        } else {
            String aiFactionId = resolveAiDivisionFactionId(div);
            PermanentDivisionManager.FleetDoctrineConfig aiDocCfg = (aiFactionId != null && !aiFactionId.isEmpty())
                    ? FactionFleetDoctrines.getDoctrineForFactionId(aiFactionId, null)
                    : null;
            if (aiDocCfg != null) {
                div.stationSpacing = aiDocCfg.getStationSpacing(docType);
                div.divisionInterval = aiDocCfg.getDivisionInterval(docType);
                int cfgStandoff = aiDocCfg.getStandoffRange(docType);
                div.standoffRange = PermanentDivisionManager.adaptDoctrinalStandoffToLiveEnvelope(
                        div, docType, cfgStandoff, false);
                div.stationKeepingMult = aiDocCfg.getStationKeepingMult(docType);
                div.doctrinalMovementStickiness = aiDocCfg.getMovementStickiness(docType);
                div.activeMovementStickiness = div.doctrinalMovementStickiness;
                div.speedGovernorEnabled = aiDocCfg.isSpeedGovernorEnabled();
                div.doctrinalEngineTelegraph = aiDocCfg.getEngineTelegraph(docType);
                if (!div.speedGovernorEnabled) {
                    div.activeEngineTelegraph = PermanentDivisionManager.DivisionEngineTelegraph.FLANK;
                }
                div.openingPhaseDurationSeconds = aiDocCfg.getOpeningDurationSeconds();
                div.openingMission = aiDocCfg.getOpeningMission(docType);
                div.mainMission = aiDocCfg.getMainMission(docType);
                div.strikeCraftOpeningMission = aiDocCfg.getStrikeCraftOpeningMission(docType);
                div.strikeCraftMainMission = aiDocCfg.getStrikeCraftMainMission(docType);
                div.strikeCraftCapReserve = aiDocCfg.getStrikeCraftCapReserve(docType);
                div.lineStation = aiDocCfg.getLineStation(docType);
                div.doctrinalFormation = aiDocCfg.getFormation(docType);
            } else {
                div.stationSpacing = PermanentDivisionManager.getEffectiveDefaultStationSpacingUnits(docType);
                div.divisionInterval = PermanentDivisionManager.getEffectiveDefaultDivisionIntervalUnits(docType);
                div.standoffRange = PermanentDivisionManager.adaptDoctrinalStandoffToLiveEnvelope(
                        div, docType, PermanentDivisionManager.getEffectiveDefaultStandoffRangeUnits(docType), false);
                div.stationKeepingMult = docType.defaultStationKeepingMult;
                div.speedGovernorEnabled = CombatPhysicsProfile.isSpeedGoverningEnvironment();
                div.doctrinalEngineTelegraph = PermanentDivisionManager.DivisionEngineTelegraph.AUTO;
                if (!div.speedGovernorEnabled) {
                    div.activeEngineTelegraph = PermanentDivisionManager.DivisionEngineTelegraph.FLANK;
                }
                div.openingPhaseDurationSeconds = 45f;
                div.openingMission = docType.defaultOpeningMission;
                div.mainMission = docType.defaultMainMission;
                div.strikeCraftOpeningMission =
                        PermanentDivisionManager.FleetDoctrineConfig.defaultStrikeCraftOpeningMissionFor(docType);
                div.strikeCraftMainMission =
                        PermanentDivisionManager.FleetDoctrineConfig.defaultStrikeCraftMainMissionFor(docType);
                div.strikeCraftCapReserve =
                        PermanentDivisionManager.FleetDoctrineConfig.defaultStrikeCraftCapReserveFor(docType);
                div.lineStation = docType.defaultLineStation;
                div.doctrinalFormation = docType.defaultFormation;
            }
        }
        if (div.lastFormationChangeTime < -100f) {
            div.activeFormation = div.doctrinalFormation;
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
                resolvePhysicsAwareStationSpacing(div),
                div.stationKeepingMult,
                div.commander.stationKeepingStrictness);
    }

    private static String resolveAiDivisionFactionId(DivisionState div) {
        if (div == null || div.members == null) return null;
        for (int i = 0, n = div.members.size(); i < n; i++) {
            ShipAPI s = div.members.get(i);
            if (s == null) continue;
            com.fs.starfarer.api.fleet.FleetMemberAPI fm = s.getFleetMember();
            if (fm != null) {
                com.fs.starfarer.api.campaign.CampaignFleetAPI fl = FactionFleetDoctrines.resolveTrueCampaignSourceFleet(fm);
                if (fl != null && fl.getFaction() != null && fl.getFaction().getId() != null) {
                    return fl.getFaction().getId();
                }
            }
        }
        return null;
    }

    /**
     * Returns {@code div.stationSpacing} unchanged in vanilla physics ({@code inflation <= 1.35f}), or expands slot
     * spacing when a combat mod inflates {@link ShipAPI#getCollisionRadius()} (e.g. {@code 3x}), because vanilla
     * {@code BasicShipAI} flocking repulsion refuses to settle two friendly ships closer than {@code ~2.0 * collisionRadius}.
     */
    public static float resolvePhysicsAwareStationSpacing(DivisionState div) {
        if (div == null) return 300f;
        float base = div.stationSpacing;
        List<ShipAPI> members = div.getAliveMembers();
        if (members == null || members.size() < 2) return base;
        float colSum = 0f;
        float maxInf = 1.0f;
        int n = 0;
        for (int i = 0; i < members.size(); i++) {
            ShipAPI s = members.get(i);
            if (s == null || !s.isAlive() || s.isFighter()) continue;
            float inf = CombatPhysicsProfile.resolveCollisionRadiusInflation(s);
            if (inf > maxInf) maxInf = inf;
            colSum += Math.max(40f, s.getCollisionRadius());
            n++;
        }
        if (n < 2 || maxInf <= 1.35f) {
            return base;
        }
        float avgLiveCol = colSum / n;
        return Math.max(base, avgLiveCol * 2.08f);
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
                case BATTLECRUISER_DIVISION:
                case MISSILE_BATTLESHIP_DIVISION:
                case BATTLECARRIER_DIVISION:
                case FLEET_CARRIER_DIVISION:
                case LIGHT_CARRIER_DIVISION:
                case ESCORT_CARRIER_DIVISION:
                case CARRIER_GROUP:
                    baseRadius = 900f;
                    break;
                case CRUISER_DIVISION:
                case MISSILE_CRUISER_DIVISION:
                case STRIKE_CRUISER_DIVISION:
                case PHASE_CRUISER_DIVISION:
                case FORCE_DIVISION:
                    baseRadius = 850f;
                    break;
                case DESTROYER_DIVISION:
                case STRIKE_DESTROYER_DIVISION:
                case MISSILE_DESTROYER_DIVISION:
                    baseRadius = 780f;
                    break;
                case FRIGATE_DIVISION:
                case STRIKE_FRIGATE_DIVISION:
                case MISSILE_FRIGATE_DIVISION:
                case PHASE_DIVISION:
                case MISSILE_PHASE_DIVISION:
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
        float spScale = Math.max(1.0f, CombatPhysicsProfile.getGlobalStationSpacingMult());
        float scaledBaseRadius = baseRadius * spScale;
        float slotRms = stationSpacing * (float) Math.sqrt(rankSqSum / n);
        float spacingBasedRadius = slotRms + Math.max(380f * spScale, stationSpacing * 0.75f);
        return (Math.max(scaledBaseRadius, spacingBasedRadius) * Math.max(0.75f, stationKeepingMult))
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
            int maxCap = (div.archetype != null && (div.archetype.isFrigateArchetype()
                    || div.archetype == DivisionArchetype.SERVICE_GROUP))
                    ? MAX_FRIGATE_DIV_SIZE
                    : MAX_MONOTYPE_DIV_SIZE;
            if (aliveCount >= maxCap) continue;
            // Only merge mid-battle reinforcement ships into an existing division if they spawn within <= 2200u
            // of that division's centroid; otherwise form a new reinforcement division so a ship spawning 4000u
            // away at the map edge doesn't spike the frontline division's spreadRadius and force CLOSE_UP!
            if (prof.ship != null && prof.ship.getLocation() != null && (div.centroid.x != 0f || div.centroid.y != 0f)) {
                float reinfMaxDist = 2200f * Math.max(
                        CombatPhysicsProfile.getMapScale(activeCombatEngine),
                        CombatPhysicsProfile.getWeaponRangeScale(activeCombatEngine));
                float dx = prof.ship.getLocation().x - div.centroid.x;
                float dy = prof.ship.getLocation().y - div.centroid.y;
                if ((dx * dx + dy * dy) > (reinfMaxDist * reinfMaxDist)) continue;
            }

            if (prof.primaryTag == TacticalTag.CIVILIAN_AUX && div.archetype == DivisionArchetype.SERVICE_GROUP) {
                return div;
            }
            if (prof.primaryTag == TacticalTag.MISSILE_PHASE && div.archetype == DivisionArchetype.MISSILE_PHASE_DIVISION) {
                return div;
            }
            if (prof.primaryTag == TacticalTag.PHASE_CRUISER && div.archetype == DivisionArchetype.PHASE_CRUISER_DIVISION) {
                return div;
            }
            if (prof.isPhase && div.archetype == DivisionArchetype.PHASE_DIVISION) {
                return div;
            }
            if (prof.primaryTag == TacticalTag.BATTLECARRIER
                    && (div.archetype == DivisionArchetype.BATTLECARRIER_DIVISION
                    || div.archetype == DivisionArchetype.BATTLESHIP_DIVISION)) {
                return div;
            }
            if (prof.primaryTag == TacticalTag.CARRIER && div.archetype.isCarrierArchetype()) {
                return div;
            }
            if (prof.hullSize == HullSize.CAPITAL_SHIP
                    && (div.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                    || div.archetype == DivisionArchetype.BATTLECRUISER_DIVISION)) {
                return div;
            }
            if (prof.hullSize == HullSize.DESTROYER
                    && div.archetype != null
                    && div.archetype.isDestroyerArchetype()) {
                return div;
            }
            if (prof.hullSize == HullSize.CRUISER
                    && !prof.isPhase
                    && (div.archetype == DivisionArchetype.CRUISER_DIVISION
                    || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION)) {
                return div;
            }
            if (prof.hullSize == HullSize.FRIGATE
                    && !prof.isPhase
                    && (div.archetype == DivisionArchetype.FRIGATE_DIVISION
                    || div.archetype == DivisionArchetype.STRIKE_FRIGATE_DIVISION
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

    public static boolean isAsteroidOrNonCombatProp(ShipAPI ship) {
        if (ship == null) return true;
        int owner = ship.getOwner();
        if (owner != 0 && owner != 1) {
            return true; // Excludes neutral side-100 derelict hulks / map props (e.g. Asteroid Mining objective hulks)
        }
        if (ship.isPiece()) {
            return true;
        }
        Map<String, Object> custom = ship.getCustomData();
        if (custom != null && (custom.containsKey("wfpasm_resource")
                || Boolean.TRUE.equals(custom.get("divisionai_non_combat_prop")))) {
            return true;
        }
        com.fs.starfarer.api.combat.ShipVariantAPI var = ship.getVariant();
        if (var != null && var.hasHullMod("wfpasm_valuable_asteroid")) {
            return true;
        }
        com.fs.starfarer.api.combat.ShipHullSpecAPI hs = ship.getHullSpec();
        if (hs != null && hs.getBuiltInMods() != null && hs.getBuiltInMods().contains("wfpasm_valuable_asteroid")) {
            return true;
        }
        // Null-AI entities on the combat layer (Asteroid Mining wild/tugged rocks, foiler null-AI station parts, props):
        // preserve only the player's own manually-piloted flagship (where Starsector sets shipAI == null when Autopilot is OFF)
        // and bare standalone unit-test ShipAPI stubs outside an active combat engine.
        if (ship.getShipAI() == null) {
            CombatEngineAPI eng = activeCombatEngine;
            if (eng == null) {
                try {
                    eng = com.fs.starfarer.api.Global.getCombatEngine();
                } catch (Throwable ignored) {
                }
            }
            List<ShipAPI> activeShips = eng != null ? eng.getShips() : null;
            if ((activeShips != null && activeShips.contains(ship)) || ship.getMutableStats() != null) {
                ShipAPI playerShip = VoidstrikeCoordinator.safeGetPlayerShip(eng);
                if (ship != playerShip) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isEligibleCombatHull(ShipAPI ship) {
        if (ship == null) return false;
        if (!ship.isAlive() || ship.isHulk() || ship.isExpired()) return false;
        if (ship.isFighter() || ship.isDrone() || ship.isStation() || ship.isStationModule()) return false;
        if (ship.getParentStation() != null) return false;
        if (isAsteroidOrNonCombatProp(ship)) return false;
        HullSize size = ship.getHullSize();
        return size == HullSize.FRIGATE
                || size == HullSize.DESTROYER
                || size == HullSize.CRUISER
                || size == HullSize.CAPITAL_SHIP;
    }

    private static final Set<String> CORE_GUNLINE_FACTIONS = new HashSet<>(java.util.Arrays.asList(
            "hegemony", "xiv", "xiv_battlegroup", "hegemony_xiv",
            "ironshell", "iron shell 1.183aj", "ironsentinel",
            "sindrian_diktat", "sindran_diktat", "diktat_achilles", "lions_guard",
            "interstellarimperium", "imperium", "ii_imperial_guard",
            "vic", "xhanempire", "vantanet",
            "uaf_november", "uaf_automata",
            "knights_of_ludd", "knights_of_selkie", "templars", "zea_dawn"
    ));

    private static final Set<String> NON_CARRIER_DOCTRINE_FACTIONS = new HashSet<>(java.util.Arrays.asList(
            "svc", "symbiotic_void_creatures",
            "vwl", "void_wildlife",
            "bugmaster",
            "mmm", "mind_metamorphosis",
            "luddic_path", "luddenhance",
            "fang",
            "threat", "mess", "mess_remnant", "hmi_nightmare",
            "dweller", "omega", "liminal", "blade_breakers"
    ));

    /**
     * Hybrid Pre-Combat Conduct of Battle Instantiator:
     * Starts from the faction's doctrinal baseline in {@code fDoc} (preserving faction personality on mixed fleets
     * and non-preset Custom Conduct axes on fanatical/biological/AI factions), then inspects the actual deployed
     * + reserve combat hulls of this specific NPC battle group so atypical fleets adapt cleanly:
     *  - Carrier-Centric factions without carriers fall back to Linear Warfare or Light & Strike Warfare.
     *  - Pure frigate/destroyer/phase/strike pickets (0 Capitals and 0 Line Cruisers) switch off static Battle Line
     *    to Open Flotilla + Torpedo/Strike Runs instead of trying to form a battleship gunline.
     *  - Carrier-dominant task forces from Linear/Light factions promote to Carrier-Centric Operations.
     *  - Heavy surface armadas from Light/Strike factions promote to Linear Warfare.
     */
    public static void instantiatePreCombatConductOfBattle(
            CombatEngineAPI engine,
            BattleGroupState battleGroup,
            String factionId,
            PermanentDivisionManager.FleetDoctrineConfig fDoc,
            List<ShipAPI> eligibleShips) {
        if (battleGroup == null || fDoc == null) return;

        String normFid = factionId != null ? factionId.trim().toLowerCase(java.util.Locale.ROOT) : "";
        boolean isAlliedGroup = false;
        if (eligibleShips != null) {
            for (int i = 0, n = eligibleShips.size(); i < n; i++) {
                ShipAPI s = eligibleShips.get(i);
                if (s != null && s.isAlly()) {
                    isAlliedGroup = true;
                    break;
                }
            }
        }

        float totalFp = 0f;
        float carrierFp = 0f;
        float heavyLineFp = 0f;
        float lightStrikeFp = 0f;
        int carrierCount = 0;
        int heavyLineCount = 0;
        Set<String> countedMemberIds = new HashSet<>();

        if (eligibleShips != null) {
            for (int i = 0, n = eligibleShips.size(); i < n; i++) {
                ShipAPI s = eligibleShips.get(i);
                if (s == null || !isEligibleCombatHull(s)) continue;
                if (s.getFleetMemberId() != null) {
                    countedMemberIds.add(s.getFleetMemberId());
                }
                ShipCapabilityProfile prof = new ShipCapabilityProfile(s);
                if (prof.primaryTag == TacticalTag.CIVILIAN_AUX) continue;
                float fp = Math.max(1f, prof.fpStrength);
                totalFp += fp;
                HullSize sz = prof.hullSize;
                if (prof.primaryTag == TacticalTag.BATTLECARRIER) {
                    carrierCount++;
                    heavyLineCount++;
                    carrierFp += fp * 0.60f;
                    heavyLineFp += fp * 0.40f;
                } else if (prof.primaryTag == TacticalTag.CARRIER) {
                    carrierCount++;
                    carrierFp += fp;
                } else if (sz == HullSize.CAPITAL_SHIP) {
                    heavyLineCount++;
                    heavyLineFp += fp;
                } else if (sz == HullSize.CRUISER
                        && prof.primaryTag != TacticalTag.STRIKE_CRUISER
                        && prof.primaryTag != TacticalTag.MISSILE_CRUISER
                        && prof.primaryTag != TacticalTag.PHASE_CRUISER
                        && prof.primaryTag != TacticalTag.PHASE
                        && prof.primaryTag != TacticalTag.MISSILE_PHASE) {
                    heavyLineCount++;
                    heavyLineFp += fp;
                } else {
                    lightStrikeFp += fp;
                }
            }
        }

        if (engine != null) {
            CombatFleetManagerAPI fm = engine.getFleetManager(battleGroup.side);
            List<com.fs.starfarer.api.fleet.FleetMemberAPI> reserves = fm != null ? fm.getReservesCopy() : null;
            if (reserves != null) {
                for (int i = 0, n = reserves.size(); i < n; i++) {
                    com.fs.starfarer.api.fleet.FleetMemberAPI m = reserves.get(i);
                    if (m == null || !PermanentDivisionManager.isEligibleFleetMember(m)) continue;
                    if (battleGroup.side == 0 && isAlliedGroup && !m.isAlly()) continue;
                    if (m.getId() != null && !countedMemberIds.add(m.getId())) continue;
                    float fp = Math.max(1f, m.getFleetPointCost());
                    DivisionArchetype arch = PermanentDivisionManager.classifyFleetMemberArchetype(m);
                    totalFp += fp;
                    switch (arch) {
                        case FLEET_CARRIER_DIVISION:
                        case LIGHT_CARRIER_DIVISION:
                        case ESCORT_CARRIER_DIVISION:
                        case CARRIER_GROUP:
                            carrierCount++;
                            carrierFp += fp;
                            break;
                        case BATTLECARRIER_DIVISION:
                            carrierCount++;
                            heavyLineCount++;
                            carrierFp += fp * 0.60f;
                            heavyLineFp += fp * 0.40f;
                            break;
                        case BATTLESHIP_DIVISION:
                        case MISSILE_BATTLESHIP_DIVISION:
                        case BATTLECRUISER_DIVISION:
                        case CRUISER_DIVISION:
                            heavyLineCount++;
                            heavyLineFp += fp;
                            break;
                        case SERVICE_GROUP:
                            break;
                        default:
                            lightStrikeFp += fp;
                            break;
                    }
                }
            }
        }

        if (totalFp <= 0f) return;

        float carrierShare = carrierFp / totalFp;
        float heavyShare = heavyLineFp / totalFp;
        float lightShare = lightStrikeFp / totalFp;

        PermanentDivisionManager.OperationalTemplate matchedPreset = fDoc.resolveMatchingOperationalTemplate();
        boolean isCustomConduct = (matchedPreset == null);
        boolean prefersCarrier = fDoc.getOperationalAnchor() == PermanentDivisionManager.OperationalAnchor.CARRIER_SCREEN
                || fDoc.getDecisiveArm() == PermanentDivisionManager.DecisiveArm.CARRIER_STRIKE;
        boolean prefersOpenFlotilla = fDoc.getOperationalAnchor() == PermanentDivisionManager.OperationalAnchor.OPEN_FLOTILLA;
        boolean isCoreGunline = CORE_GUNLINE_FACTIONS.contains(normFid);
        boolean blocksCarrierScreen = NON_CARRIER_DOCTRINE_FACTIONS.contains(normFid)
                || normFid.startsWith("rat_abyssals");

        // 1. Carrier-Centric Operations retention / promotion
        if (!blocksCarrierScreen) {
            if (prefersCarrier) {
                if (carrierCount >= 1 && carrierShare >= 0.15f) {
                    battleGroup.operationalAnchor = fDoc.getOperationalAnchor();
                    battleGroup.objectiveCapturePolicy = fDoc.getObjectiveCapturePolicy();
                    battleGroup.decisiveArm = fDoc.getDecisiveArm();
                    battleGroup.engagementDiscipline = fDoc.getEngagementDiscipline();
                    return;
                }
                // Carrier-Centric faction spawned a 0-carrier / low-carrier surface patrol -> fall through!
            } else {
                float carrierPromoteThreshold = isCoreGunline ? 0.42f : (isCustomConduct ? 0.38f : 0.33f);
                int minCarriers = isCoreGunline ? 2 : 1;
                if (carrierCount >= minCarriers && carrierShare >= carrierPromoteThreshold) {
                    PermanentDivisionManager.ObjectiveCapturePolicy savedObj = fDoc.getObjectiveCapturePolicy();
                    battleGroup.applyOperationalTemplateForBattle(PermanentDivisionManager.OperationalTemplate.CARRIER_CENTRIC);
                    if (isCustomConduct) {
                        battleGroup.objectiveCapturePolicy = savedObj;
                    }
                    return;
                }
            }
        }

        // 2. Pure Light / Wolfpack / Phase Picket Guard (0 Capitals and 0 Line Cruisers)
        if (heavyLineCount == 0) {
            if (isCustomConduct) {
                battleGroup.operationalAnchor = PermanentDivisionManager.OperationalAnchor.OPEN_FLOTILLA;
                battleGroup.decisiveArm = (fDoc.getDecisiveArm() == PermanentDivisionManager.DecisiveArm.CARRIER_STRIKE)
                        ? PermanentDivisionManager.DecisiveArm.SURFACE_GUNFIRE
                        : fDoc.getDecisiveArm();
                battleGroup.objectiveCapturePolicy = fDoc.getObjectiveCapturePolicy();
                battleGroup.engagementDiscipline = (fDoc.getEngagementDiscipline() == PermanentDivisionManager.EngagementDiscipline.CLOSE_SCREEN
                        && !"vwl".equals(normFid) && !"void_wildlife".equals(normFid))
                        ? PermanentDivisionManager.EngagementDiscipline.LINE_COHESION
                        : fDoc.getEngagementDiscipline();
            } else {
                battleGroup.applyOperationalTemplateForBattle(PermanentDivisionManager.OperationalTemplate.LIGHT_FORCES_TORPEDO);
            }
            return;
        }

        // 3. Fleets with >= 1 Capital or Line Cruiser
        if (prefersOpenFlotilla) {
            // Standard Light/Strike factions (e.g. Tri-Tachyon Paragon battlefleet or Pirate Atlas Mk.II armada)
            // promote to Linear Warfare when overwhelmingly anchored by heavy surface gunlines.
            if (!isCustomConduct && heavyLineCount >= 3 && heavyShare >= 0.55f) {
                battleGroup.applyOperationalTemplateForBattle(PermanentDivisionManager.OperationalTemplate.LINEAR_WARFARE);
            } else {
                battleGroup.operationalAnchor = fDoc.getOperationalAnchor();
                battleGroup.objectiveCapturePolicy = fDoc.getObjectiveCapturePolicy();
                battleGroup.decisiveArm = fDoc.getDecisiveArm();
                battleGroup.engagementDiscipline = fDoc.getEngagementDiscipline();
            }
            return;
        }

        if (prefersCarrier) {
            // Carrier-Centric faction with < 15% carrier FP: fight Linear if gunline-backed, else Light & Strike
            if (heavyShare >= 0.30f) {
                battleGroup.applyOperationalTemplateForBattle(PermanentDivisionManager.OperationalTemplate.LINEAR_WARFARE);
            } else {
                battleGroup.applyOperationalTemplateForBattle(PermanentDivisionManager.OperationalTemplate.LIGHT_FORCES_TORPEDO);
            }
            return;
        }

        // Linear Warfare / Custom Battle-Line baseline:
        // If a non-core-gunline fleet has only 1 lone cruiser leading a large wolfpack (>= 70% light/strike FP),
        // fight as an Open Flotilla instead of anchoring the whole wolfpack onto 1 cruiser.
        if (!isCoreGunline && !isCustomConduct && heavyLineCount == 1 && heavyShare < 0.22f && lightShare >= 0.70f) {
            battleGroup.applyOperationalTemplateForBattle(PermanentDivisionManager.OperationalTemplate.LIGHT_FORCES_TORPEDO);
            return;
        }

        battleGroup.operationalAnchor = fDoc.getOperationalAnchor();
        battleGroup.objectiveCapturePolicy = fDoc.getObjectiveCapturePolicy();
        battleGroup.decisiveArm = fDoc.getDecisiveArm();
        battleGroup.engagementDiscipline = fDoc.getEngagementDiscipline();
    }
}
