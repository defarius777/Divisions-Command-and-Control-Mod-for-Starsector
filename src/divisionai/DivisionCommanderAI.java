package divisionai;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import divisionai.DivisionModel.BattlePhase;
import divisionai.DivisionModel.BattlegroupState;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionIntentType;
import divisionai.DivisionModel.DivisionState;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

/**
 * Mid-level Division Commander AI:
 *  - Evaluates each division's capabilities, local overmatch, cohesion, flux pressure,
 *    and Battlegroup Commander phase.
 *  - Selects concrete single-division and cross-division tactics:
 *      * Harassing torpedo attack ("Destroyer Div 1" -> "Enemy Destroyer Div 4")
 *      * Time-on-target coordinated strike
 *      * Strike-then-withdraw to reserve
 *      * Monotype division screening another division ("Destroyer Div 1" screening "Battle Div 2")
 *      * Relief of an endangered friendly division
 *      * Flux/hull rotation to reserve
 *      * Bounded pursuit & cohesion keeping
 *  - Logs intent transitions into the combat HUD log via DivisionCombatLog.
 *  - Executes intents cooperatively via ShipwideAIFlags without overriding player flagship
 *    or explicit player single-ship orders.
 */
public class DivisionCommanderAI {

    private static final float STRIKE_WINDOW_DURATION = 14.0f;
    private static final float FLAG_TTL = 0.85f;

    public static void evaluateAndExecuteDivisions(
            CombatEngineAPI engine,
            BattlegroupState myBg,
            BattlegroupState enemyBg,
            float now) {

        for (DivisionState div : myBg.divisions) {
            if (!div.isCombatEffective()) continue;

            // Re-evaluate division intent on its commander's decision cadence
            if ((now - div.lastIntentChangeTime) >= div.commander.decisionCadenceSeconds) {
                selectDivisionIntent(engine, div, myBg, enemyBg, now);
            }

            // Apply per-tick (0.5s cadence) ShipwideAIFlags nudges to division members
            applyDivisionFlagsToShips(engine, div, myBg, now);
        }
    }

    private static void selectDivisionIntent(
            CombatEngineAPI engine,
            DivisionState div,
            BattlegroupState myBg,
            BattlegroupState enemyBg,
            float now) {

        div.targetEnemyDivision = null;
        div.targetFriendlyDivision = null;
        div.targetObjective = null;
        String targetLabel = "";
        String reason = "";

        DivisionState nearestEnemyDiv = findBestEnemyDivisionFor(div, enemyBg, false);
        DivisionState torpedoTargetEnemyDiv = findBestEnemyDivisionFor(div, enemyBg, true);
        DivisionState endangeredFriendlyDiv = findEndangeredFriendlyDivision(div, myBg);
        DivisionState heavyFriendlyToScreen = findHeavyFriendlyDivisionToScreen(div, myBg);

        // 1. Check if a strike division just finished its strike window or is high-flux -> Strike-then-withdraw to reserve!
        boolean inActiveStrike = (div.currentIntent == DivisionIntentType.HARASS_TORPEDO_ATTACK
                || div.currentIntent == DivisionIntentType.COORDINATED_TORPEDO_STRIKE
                || div.currentIntent == DivisionIntentType.FLANK_ENEMY_DIVISION);

        if (inActiveStrike && ((now - div.strikeCommittedTimestamp) >= STRIKE_WINDOW_DURATION
                || div.avgFluxLevel >= div.commander.rotationFluxThreshold * 0.90f
                || (div.hasTorpedoStrikeCapability && div.usableStrikeAmmoCount <= 0))) {

            div.currentIntent = DivisionIntentType.STRIKE_AND_WITHDRAW;
            div.targetEnemyDivision = nearestEnemyDiv;
            div.maneuverAnchor.set(myBg.reserveAnchor);
            targetLabel = nearestEnemyDiv != null ? nearestEnemyDiv.displayName : "contact zone";
            reason = String.format("avg flux %.0f%%", div.avgFluxLevel * 100f);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 2. High flux / damaged line division or outgunned locally -> Rotate to reserve behind a friendly division
        if ((div.avgFluxLevel >= div.commander.rotationFluxThreshold
                || (div.localEnemyStrength > 12f && div.localOvermatchRatio < 0.58f && div.commander.aggressionBias < 1.35f)
                || myBg.currentPhase == BattlePhase.ORDERED_WITHDRAWAL)) {

            DivisionState anchorFriend = (myBg.mainEffortDivision != null && myBg.mainEffortDivision != div)
                    ? myBg.mainEffortDivision
                    : heavyFriendlyToScreen;

            div.currentIntent = DivisionIntentType.ROTATE_TO_RESERVE;
            div.targetFriendlyDivision = anchorFriend;
            if (anchorFriend != null) {
                float retreatDirY = (div.side == 0) ? -1f : 1f;
                div.maneuverAnchor.set(anchorFriend.centroid.x, anchorFriend.centroid.y + retreatDirY * 1100f);
                targetLabel = anchorFriend.displayName;
            } else {
                div.maneuverAnchor.set(myBg.reserveAnchor);
                targetLabel = "Battlegroup Reserve";
            }
            reason = String.format("flux %.0f%%, local ratio %.2f", div.avgFluxLevel * 100f, div.localOvermatchRatio);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 3. Battlegroup Reserve designation (held back until committed)
        if (div.isDesignatedReserve && endangeredFriendlyDiv == null) {
            div.currentIntent = DivisionIntentType.HOLD_IN_RESERVE;
            div.maneuverAnchor.set(myBg.reserveAnchor);
            reason = "awaiting BG commitment";
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, "", reason, now);
            return;
        }

        // 4. Relief of an endangered friendly division (cross-division mutual support)
        if (endangeredFriendlyDiv != null
                && div.avgFluxLevel < 0.58f
                && (div.isFastFlanker || div.localEnemyStrength < 15f || myBg.currentPhase == BattlePhase.STABILIZE_AND_RELIEVE)) {

            div.currentIntent = DivisionIntentType.RELIEVE_FRIENDLY_DIVISION;
            div.targetFriendlyDivision = endangeredFriendlyDiv;
            div.maneuverAnchor.set(endangeredFriendlyDiv.centroid);
            targetLabel = endangeredFriendlyDiv.displayName;
            reason = String.format("under heavy pressure, ratio %.2f", endangeredFriendlyDiv.localOvermatchRatio);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 5. Monotype Screening: e.g., "Destroyer Div 1" or "Frigate Div 1" screening "Battle Div 1" or "Carrier & Escort 1"
        if (!div.archetype.isComposite
                && div.isScreenCapable
                && !div.hasTorpedoStrikeCapability
                && heavyFriendlyToScreen != null) {

            float distToHeavy = distance(div.centroid, heavyFriendlyToScreen.centroid);
            boolean heavyNeedsScreen = heavyFriendlyToScreen.archetype == DivisionArchetype.CARRIER_AND_ESCORT
                    || heavyFriendlyToScreen.archetype == DivisionArchetype.BATTLE_DIVISION
                    || heavyFriendlyToScreen.localEnemyStrength > 10f
                    || distToHeavy < 2400f;

            if (heavyNeedsScreen) {
                div.currentIntent = DivisionIntentType.SCREEN_FRIENDLY_DIVISION;
                div.targetFriendlyDivision = heavyFriendlyToScreen;
                computeScreenAnchor(heavyFriendlyToScreen, nearestEnemyDiv, div.maneuverAnchor, div.side);
                targetLabel = heavyFriendlyToScreen.displayName;
                reason = "forward PD & anti-flank screen";
                div.lastIntentChangeTime = now;
                DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                return;
            }
        }

        // 6. Torpedo / Strike Division: Harassing torpedo attack or Time-on-Target strike!
        //    e.g., "Destroyer Div 1" intends to launch harassing torpedo attack at "Enemy Destroyer Div 4"
        if (div.hasTorpedoStrikeCapability
                && div.usableStrikeAmmoCount > 0
                && div.avgFluxLevel < 0.55f
                && torpedoTargetEnemyDiv != null) {

            boolean timeOnTargetCue = (myBg.currentPhase == BattlePhase.COORDINATED_STRIKE)
                    && (myBg.mainEffortDivision != null && myBg.mainEffortDivision != div)
                    && (torpedoTargetEnemyDiv == myBg.primaryThreatEnemyDivision);

            if (timeOnTargetCue) {
                div.currentIntent = DivisionIntentType.COORDINATED_TORPEDO_STRIKE;
                reason = "synced with \"" + myBg.mainEffortDivision.displayName + "\"";
            } else {
                div.currentIntent = DivisionIntentType.HARASS_TORPEDO_ATTACK;
                reason = String.format("%d strike salvos ready", div.usableStrikeAmmoCount);
            }

            div.targetEnemyDivision = torpedoTargetEnemyDiv;
            div.maneuverAnchor.set(torpedoTargetEnemyDiv.centroid);
            div.strikeCommittedTimestamp = now;
            targetLabel = torpedoTargetEnemyDiv.displayName;
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 7. Fast flankers swing wide to flank enemy division when main line is engaged
        if (div.isFastFlanker
                && nearestEnemyDiv != null
                && myBg.mainEffortDivision != null
                && myBg.mainEffortDivision != div
                && div.avgFluxLevel < 0.52f) {

            div.currentIntent = DivisionIntentType.FLANK_ENEMY_DIVISION;
            div.targetEnemyDivision = nearestEnemyDiv;
            computeFlankAnchor(div, nearestEnemyDiv, div.maneuverAnchor);
            div.strikeCommittedTimestamp = now;
            targetLabel = nearestEnemyDiv.displayName;
            reason = "enveloping flank vector";
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 8. Objective seizure during opening probe or by fast divisions
        BattleObjectiveAPI uncapturedObj = findNearestContestObjective(engine, div);
        if (uncapturedObj != null
                && (myBg.currentPhase == BattlePhase.OPENING_PROBE || div.isFastFlanker)
                && div.localEnemyStrength < 15f) {

            div.currentIntent = DivisionIntentType.SEIZE_OBJECTIVE;
            div.targetObjective = uncapturedObj;
            div.maneuverAnchor.set(uncapturedObj.getLocation());
            targetLabel = uncapturedObj.getDisplayName();
            reason = "contest tactical buoy";
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 9. Cohesion check: if division ships have scattered too far apart, pause and reform
        if (div.cohesionRatio > 1.48f && div.getAliveMembers().size() >= 2) {
            div.currentIntent = DivisionIntentType.HOLD_AND_COHERE;
            div.maneuverAnchor.set(div.centroid);
            reason = String.format("spread %.0fu > %.0fu leash", div.spreadRadius, div.desiredCohesionRadius);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, "", reason, now);
            return;
        }

        // 10. Press local overmatch vs standard line advance
        if (nearestEnemyDiv != null) {
            div.targetEnemyDivision = nearestEnemyDiv;
            div.maneuverAnchor.set(nearestEnemyDiv.centroid);
            targetLabel = nearestEnemyDiv.displayName;

            if (div.localOvermatchRatio >= Math.max(1.22f, div.commander.overmatchCommitRatio)) {
                div.currentIntent = DivisionIntentType.PRESS_OVERMATCH;
                reason = String.format("local advantage %.2fx", div.localOvermatchRatio);
            } else {
                div.currentIntent = DivisionIntentType.ADVANCE_ENGAGE_LINE;
                reason = "maintaining firing line";
            }
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
        }
    }

    /**
     * Cooperatively nudges ships in the division via ShipwideAIFlags.
     * Never overrides the player's manually piloted flagship or explicit player orders.
     */
    private static void applyDivisionFlagsToShips(
            CombatEngineAPI engine,
            DivisionState div,
            BattlegroupState myBg,
            float now) {

        ShipAPI playerShip = engine.getPlayerShip();
        boolean playerManualControl = (playerShip != null && !engine.isUIAutopilotOn());

        CombatFleetManagerAPI fm = engine.getFleetManager(div.side);
        CombatTaskManagerAPI tm = (fm != null) ? fm.getTaskManager(false) : null;

        List<ShipAPI> alive = div.getAliveMembers();
        if (alive.isEmpty()) return;

        ShipAPI focusEnemyShip = pickFocusEnemyShip(div.targetEnemyDivision, div.centroid);

        for (ShipAPI ship : alive) {
            if (playerManualControl && ship == playerShip) {
                continue;
            }
            if (hasExplicitPlayerDirectOrder(ship, tm)) {
                continue;
            }

            ShipwideAIFlags flags = ship.getAIFlags();
            if (flags == null) continue;

            float distToDivCenter = distance(ship.getLocation(), div.centroid);
            boolean shipStraggling = alive.size() >= 2 && distToDivCenter > (div.desiredCohesionRadius * 1.35f);

            // Bounded pursuit & cohesion leash: prevent lone ships from chasing bait 3000u away
            if (shipStraggling && div.currentIntent != DivisionIntentType.HARASS_TORPEDO_ATTACK
                    && div.currentIntent != DivisionIntentType.COORDINATED_TORPEDO_STRIKE) {
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, new Vector2f(div.centroid));
                continue;
            }

            switch (div.currentIntent) {
                case HARASS_TORPEDO_ATTACK:
                case COORDINATED_TORPEDO_STRIKE:
                    flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL * 1.5f);
                    if (focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        if (distance(ship.getLocation(), focusEnemyShip.getLocation()) > 2100f) {
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, new Vector2f(focusEnemyShip.getLocation()));
                        }
                    } else {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, new Vector2f(div.maneuverAnchor));
                    }
                    break;

                case PRESS_OVERMATCH:
                    flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
                    if (focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        if (distance(ship.getLocation(), focusEnemyShip.getLocation()) > 2100f) {
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, new Vector2f(focusEnemyShip.getLocation()));
                        }
                    } else {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, new Vector2f(div.maneuverAnchor));
                    }
                    break;

                case STRIKE_AND_WITHDRAW:
                case ROTATE_TO_RESERVE:
                    flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL * 1.4f);
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL * 1.4f);
                    flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL * 1.4f, new Vector2f(div.maneuverAnchor));
                    break;

                case SCREEN_FRIENDLY_DIVISION:
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    if (div.targetFriendlyDivision != null && div.targetFriendlyDivision.flagship != null
                            && div.targetFriendlyDivision.flagship.isAlive()) {
                        flags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, div.targetFriendlyDivision.flagship);
                    } else if (distance(ship.getLocation(), div.maneuverAnchor) > 650f) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, new Vector2f(div.maneuverAnchor));
                    }
                    break;

                case RELIEVE_FRIENDLY_DIVISION:
                    if (div.targetFriendlyDivision != null && div.targetFriendlyDivision.shipNeedingHelp != null
                            && div.targetFriendlyDivision.shipNeedingHelp.isAlive()) {
                        flags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, div.targetFriendlyDivision.shipNeedingHelp);
                    } else {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, new Vector2f(div.maneuverAnchor));
                    }
                    break;

                case FLANK_ENEMY_DIVISION:
                case SEIZE_OBJECTIVE:
                case HOLD_IN_RESERVE:
                    if (distance(ship.getLocation(), div.maneuverAnchor) > 550f) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, new Vector2f(div.maneuverAnchor));
                    } else if (focusEnemyShip != null && div.currentIntent == DivisionIntentType.FLANK_ENEMY_DIVISION) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    }
                    break;

                case HOLD_AND_COHERE:
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    if (distToDivCenter > div.desiredCohesionRadius * 0.85f) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, new Vector2f(div.centroid));
                    }
                    break;

                case ADVANCE_ENGAGE_LINE:
                default:
                    // Composite Carrier & Escort inner discipline: non-carrier escorts screen the carrier flagship
                    if (div.archetype == DivisionArchetype.CARRIER_AND_ESCORT
                            && div.flagship != null
                            && ship != div.flagship
                            && ship.getNumFighterBays() == 0) {
                        flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                        flags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, div.flagship);
                    } else if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 2600f) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    } else if (distance(ship.getLocation(), div.maneuverAnchor) > 1200f) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, new Vector2f(div.maneuverAnchor));
                    }
                    break;
            }
        }
    }

    /**
     * Respects explicit player orders on the player side so the player always has top C2 priority.
     */
    private static boolean hasExplicitPlayerDirectOrder(ShipAPI ship, CombatTaskManagerAPI tm) {
        if (tm == null || ship.getOwner() != 0) return false;
        AssignmentInfo info = tm.getAssignmentFor(ship);
        if (info == null || info.getType() == null) return false;
        CombatAssignmentType t = info.getType();
        return t == CombatAssignmentType.RETREAT
                || t == CombatAssignmentType.REPAIR_AND_REFIT
                || t == CombatAssignmentType.AVOID
                || t == CombatAssignmentType.IGNORE;
    }

    private static DivisionState findBestEnemyDivisionFor(
            DivisionState myDiv,
            BattlegroupState enemyBg,
            boolean preferTorpedoPrey) {

        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;

        for (DivisionState eDiv : enemyBg.divisions) {
            if (!eDiv.isCombatEffective()) continue;
            float dist = distance(myDiv.centroid, eDiv.centroid);
            float score = -dist * 0.35f;

            if (preferTorpedoPrey) {
                // Torpedo destroyers/cruisers love enemy Destroyers, Cruisers, Carriers, or high-flux targets
                if (eDiv.archetype == DivisionArchetype.DESTROYER_DIVISION
                        || eDiv.archetype == DivisionArchetype.TORPEDO_DESTROYER_DIVISION
                        || eDiv.archetype == DivisionArchetype.CRUISER_DIVISION
                        || eDiv.archetype == DivisionArchetype.CARRIER_AND_ESCORT
                        || eDiv.archetype == DivisionArchetype.BATTLE_DIVISION) {
                    score += 420f;
                }
                score += eDiv.avgFluxLevel * 600f;
            } else {
                score += eDiv.totalFpStrength * 6f;
            }

            if (score > bestScore) {
                bestScore = score;
                best = eDiv;
            }
        }
        return best;
    }

    private static void directShipAndFightersOntoTarget(ShipAPI ship, ShipwideAIFlags flags, ShipAPI focusEnemyShip) {
        if (focusEnemyShip == null || !focusEnemyShip.isAlive() || focusEnemyShip.isHulk()) return;
        flags.setFlag(AIFlags.MANEUVER_TARGET, FLAG_TTL, focusEnemyShip);
        if (ship.getNumFighterBays() > 0) {
            ship.setForceCarrierTarget(focusEnemyShip);
            ship.setForceCarrierTargetTime(1.5f);
            flags.setFlag(AIFlags.CARRIER_FIGHTER_TARGET, 1.5f, focusEnemyShip);
        }
        ShipAPI currTarget = ship.getShipTarget();
        if (currTarget == null || !currTarget.isAlive() || currTarget.isHulk()
                || distance(ship.getLocation(), focusEnemyShip.getLocation()) + 250f < distance(ship.getLocation(), currTarget.getLocation())) {
            ship.setShipTarget(focusEnemyShip);
        }
    }

    private static DivisionState findEndangeredFriendlyDivision(DivisionState myDiv, BattlegroupState myBg) {
        DivisionState best = null;
        float bestUrgency = 0f;
        for (DivisionState other : myBg.divisions) {
            if (other == myDiv || !other.isCombatEffective()) continue;
            if (other.localEnemyStrength < 12f) continue;
            // Do not relieve a division that already has local overmatch advantage (>= 0.85)
            if (other.localOvermatchRatio >= 0.85f) continue;

            // Prevent relief dogpiling: if another friendly division is already relieving 'other', skip it
            boolean alreadyRelieved = false;
            for (DivisionState peer : myBg.divisions) {
                if (peer != myDiv && peer != other && peer.isCombatEffective()
                        && peer.currentIntent == DivisionIntentType.RELIEVE_FRIENDLY_DIVISION
                        && peer.targetFriendlyDivision == other) {
                    alreadyRelieved = true;
                    break;
                }
            }
            if (alreadyRelieved) continue;

            float urgency = (1.0f - other.localOvermatchRatio) * 1000f;
            if (other.hasShipNeedingHelp) {
                urgency += 300f;
            }
            if (other.archetype == DivisionArchetype.CARRIER_AND_ESCORT
                    || other.archetype == DivisionArchetype.BATTLE_DIVISION) {
                urgency += 200f;
            }

            float dist = distance(myDiv.centroid, other.centroid);
            if (urgency > 350f && dist < 4200f) {
                float net = urgency - dist * 0.12f;
                if (net > bestUrgency) {
                    bestUrgency = net;
                    best = other;
                }
            }
        }
        return best;
    }

    private static DivisionState findHeavyFriendlyDivisionToScreen(DivisionState myDiv, BattlegroupState myBg) {
        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;
        for (DivisionState other : myBg.divisions) {
            if (other == myDiv || !other.isCombatEffective()) continue;
            boolean isHeavy = other.archetype == DivisionArchetype.BATTLE_DIVISION
                    || other.archetype == DivisionArchetype.CARRIER_AND_ESCORT
                    || other.archetype == DivisionArchetype.CRUISER_DIVISION;
            if (!isHeavy) continue;

            float dist = distance(myDiv.centroid, other.centroid);
            float score = other.totalFpStrength * 10f - dist * 0.3f;
            if (other.archetype == DivisionArchetype.CARRIER_AND_ESCORT
                    || other.archetype == DivisionArchetype.BATTLE_DIVISION) {
                score += 500f;
            }
            if (score > bestScore) {
                bestScore = score;
                best = other;
            }
        }
        return best;
    }

    private static ShipAPI pickFocusEnemyShip(DivisionState enemyDiv, Vector2f fromPoint) {
        if (enemyDiv == null) return null;
        List<ShipAPI> enemies = enemyDiv.getAliveMembers();
        ShipAPI best = null;
        float bestScore = -Float.MAX_VALUE;
        for (ShipAPI e : enemies) {
            float d = distance(fromPoint, e.getLocation());
            // Prefer closer targets and high-flux/exposed targets inside the target division
            float score = -d * 0.45f + e.getFluxLevel() * 550f + (1f - e.getHullLevel()) * 400f;
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private static BattleObjectiveAPI findNearestContestObjective(CombatEngineAPI engine, DivisionState div) {
        List<BattleObjectiveAPI> objs = engine.getObjectives();
        if (objs == null || objs.isEmpty()) return null;
        BattleObjectiveAPI best = null;
        float bestDist = Float.MAX_VALUE;
        for (BattleObjectiveAPI obj : objs) {
            if (obj == null || obj.getOwner() == div.side) continue;
            float d = distance(div.centroid, obj.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = obj;
            }
        }
        return best;
    }

    private static void computeScreenAnchor(
            DivisionState protectedDiv,
            DivisionState enemyDiv,
            Vector2f outAnchor,
            int side) {
        if (enemyDiv != null) {
            float dx = enemyDiv.centroid.x - protectedDiv.centroid.x;
            float dy = enemyDiv.centroid.y - protectedDiv.centroid.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1f) {
                outAnchor.set(
                        protectedDiv.centroid.x + (dx / len) * 550f,
                        protectedDiv.centroid.y + (dy / len) * 550f);
                return;
            }
        }
        float forwardY = (side == 0) ? 500f : -500f;
        outAnchor.set(protectedDiv.centroid.x, protectedDiv.centroid.y + forwardY);
    }

    private static void computeFlankAnchor(
            DivisionState flankerDiv,
            DivisionState enemyDiv,
            Vector2f outAnchor) {
        float dx = enemyDiv.centroid.x - flankerDiv.centroid.x;
        float dy = enemyDiv.centroid.y - flankerDiv.centroid.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= 1f) {
            outAnchor.set(enemyDiv.centroid);
            return;
        }
        // Perpendicular offset to swing wide around the enemy division's flank
        float perpX = -dy / len;
        float perpY = dx / len;
        float sideSign = (flankerDiv.divisionNumber % 2 == 1) ? 1f : -1f;
        outAnchor.set(
                enemyDiv.centroid.x + perpX * 1150f * sideSign,
                enemyDiv.centroid.y + perpY * 1150f * sideSign);
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
