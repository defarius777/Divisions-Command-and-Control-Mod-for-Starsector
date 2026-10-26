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
            } else {
                // Keep dynamic maneuver anchors tracking moving target divisions between intent evaluations
                if (div.currentIntent == DivisionIntentType.SCREEN_FRIENDLY_DIVISION && div.targetFriendlyDivision != null) {
                    computeScreenAnchor(engine, div, div.targetFriendlyDivision, div.targetEnemyDivision, div.maneuverAnchor, div.side);
                } else if (div.currentIntent == DivisionIntentType.FLANK_ENEMY_DIVISION && div.targetEnemyDivision != null) {
                    computeFlankAnchor(engine, div, div.targetEnemyDivision, div.maneuverAnchor);
                }
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

        DivisionState prevEnemyTarget = div.targetEnemyDivision;
        BattleObjectiveAPI prevObjective = div.targetObjective;

        div.targetEnemyDivision = null;
        div.targetFriendlyDivision = null;
        div.targetObjective = null;
        String targetLabel = "";
        String reason = "";

        DivisionState nearestEnemyDiv = findBestEnemyDivisionFor(engine, div, enemyBg, false, prevEnemyTarget);
        DivisionState torpedoTargetEnemyDiv = findBestEnemyDivisionFor(engine, div, enemyBg, true, prevEnemyTarget);
        DivisionState endangeredFriendlyDiv = findEndangeredFriendlyDivision(div, myBg);
        DivisionState heavyFriendlyToScreen = findHeavyFriendlyDivisionToScreen(div, myBg);
        BattleObjectiveAPI commandAssignedObj = findTaskManagerObjectiveForDivision(engine, div, myBg, prevObjective);
        BattleObjectiveAPI uncapturedObj = (commandAssignedObj != null)
                ? commandAssignedObj
                : findNearestContestObjective(engine, div, myBg, prevObjective);

        float distToVisibleEnemy = (nearestEnemyDiv != null)
                ? distance(div.centroid, BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv))
                : Float.MAX_VALUE;
        float distToTorpedoEnemy = (torpedoTargetEnemyDiv != null)
                ? distance(div.centroid, BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, torpedoTargetEnemyDiv))
                : Float.MAX_VALUE;

        // 1. Check if a torpedo strike division just finished its strike window or expended its torpedoes / built high flux
        //    -> Strike-then-withdraw to reserve! (Never trigger on 0 torpedo ammo for non-torpedo FLANK_ENEMY_DIVISION!)
        boolean inTorpedoStrike = (div.currentIntent == DivisionIntentType.HARASS_TORPEDO_ATTACK
                || div.currentIntent == DivisionIntentType.COORDINATED_TORPEDO_STRIKE);
        boolean inFlankManeuver = (div.currentIntent == DivisionIntentType.FLANK_ENEMY_DIVISION);

        boolean torpedoRunComplete = inTorpedoStrike && ((now - div.strikeCommittedTimestamp) >= STRIKE_WINDOW_DURATION
                || div.avgFluxLevel >= div.commander.rotationFluxThreshold * 0.90f
                || (div.hasTorpedoStrikeCapability && div.usableStrikeAmmoCount <= 0));
        boolean flankWithdrawNeeded = inFlankManeuver
                && (div.avgFluxLevel >= div.commander.rotationFluxThreshold * 0.88f
                || ((now - div.strikeCommittedTimestamp) >= (STRIKE_WINDOW_DURATION * 1.5f) && div.avgFluxLevel >= 0.45f));

        if (torpedoRunComplete || flankWithdrawNeeded) {
            div.currentIntent = DivisionIntentType.STRIKE_AND_WITHDRAW;
            div.targetEnemyDivision = nearestEnemyDiv;
            div.maneuverAnchor.set(myBg.reserveAnchor);
            div.lastStrikeWithdrawTimestamp = now;
            targetLabel = nearestEnemyDiv != null ? nearestEnemyDiv.displayName : "contact zone";
            reason = String.format("avg flux %.0f%%", div.avgFluxLevel * 100f);
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 2. High flux / damaged division -> Rotate to reserve behind a friendly division
        //    Requires actual flux buildup (or critical hull damage under heavy local overmatch) so fresh divisions at 0-15% flux never retreat!
        boolean highFluxPressure = div.avgFluxLevel >= div.commander.rotationFluxThreshold
                || (div.maxMemberFluxLevel >= 0.78f && div.localEnemyStrength > 12f && div.localOvermatchRatio < 0.65f)
                || (div.avgFluxLevel >= 0.45f && div.localEnemyStrength > 14f && div.localOvermatchRatio < 0.50f && div.commander.aggressionBias < 1.35f);
        boolean canRotateOut = (div != myBg.mainEffortDivision || div.avgFluxLevel >= 0.72f || myBg.divisions.size() >= 3);

        if ((highFluxPressure && canRotateOut) || myBg.currentPhase == BattlePhase.ORDERED_WITHDRAWAL) {

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

        // 3. Explicit Command Window Objective Order or Opening Probe Objective Seizure!
        //    If the player (or Admiral task manager) ordered control points captured, or if enemy forces are hidden in Fog of War
        //    / outside close contact during the opening probe, maneuver divisions prioritize seizing unclaimed control points!
        boolean isHeavyLineOrCarrier = (div.archetype == DivisionArchetype.BATTLE_DIVISION
                || div.archetype == DivisionArchetype.CARRIER_AND_ESCORT);
        boolean shouldSeizeObjective = false;
        if (commandAssignedObj != null && !isHeavyLineOrCarrier && div.localEnemyStrength < 18f) {
            shouldSeizeObjective = true;
        } else if (uncapturedObj != null
                && !isHeavyLineOrCarrier
                && div.localEnemyStrength < 12f
                && (nearestEnemyDiv == null
                || myBg.currentPhase == BattlePhase.OPENING_PROBE
                || (div.isFastFlanker && distance(div.centroid, uncapturedObj.getLocation()) + 400f < distToVisibleEnemy))) {
            shouldSeizeObjective = true;
        }

        if (shouldSeizeObjective && uncapturedObj != null) {
            div.currentIntent = DivisionIntentType.SEIZE_OBJECTIVE;
            div.targetObjective = uncapturedObj;
            div.maneuverAnchor.set(uncapturedObj.getLocation());
            targetLabel = uncapturedObj.getDisplayName();
            reason = (commandAssignedObj != null) ? "executing command order" : "contest tactical buoy";
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 4. Battlegroup Reserve designation (held back until committed)
        if (div.isDesignatedReserve && endangeredFriendlyDiv == null) {
            div.currentIntent = DivisionIntentType.HOLD_IN_RESERVE;
            div.maneuverAnchor.set(myBg.reserveAnchor);
            reason = "awaiting BG commitment";
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, "", reason, now);
            return;
        }

        // 5. Relief of an endangered friendly division (cross-division mutual support)
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

        // 6. Monotype Screening: e.g., "Destroyer Div 1" or "Frigate Div 1" screening "Battle Div 1" or "Carrier & Escort 1"
        if (!div.archetype.isComposite
                && div.isScreenCapable
                && !div.hasTorpedoStrikeCapability
                && heavyFriendlyToScreen != null) {

            float distToHeavy = distance(div.centroid, heavyFriendlyToScreen.centroid);
            boolean isCapitalOrCarrier = heavyFriendlyToScreen.archetype == DivisionArchetype.CARRIER_AND_ESCORT
                    || heavyFriendlyToScreen.archetype == DivisionArchetype.BATTLE_DIVISION;
            // Fast frigate flankers only screen Cruiser divisions when the cruisers are outnumbered or before enemy contact;
            // once in contact, fast frigate divisions prefer flanking on the wings unless screening a Capital/Carrier or set to Screen doctrine.
            boolean cruiserNeedsScreen = !div.isFastFlanker
                    || nearestEnemyDiv == null
                    || (heavyFriendlyToScreen.localEnemyStrength > 14f && heavyFriendlyToScreen.localOvermatchRatio < 0.90f);
            boolean heavyNeedsScreen = isCapitalOrCarrier
                    || (cruiserNeedsScreen && (heavyFriendlyToScreen.localEnemyStrength > 10f || distToHeavy < 2400f));

            if (heavyNeedsScreen) {
                div.currentIntent = DivisionIntentType.SCREEN_FRIENDLY_DIVISION;
                div.targetFriendlyDivision = heavyFriendlyToScreen;
                div.targetEnemyDivision = nearestEnemyDiv;
                computeScreenAnchor(engine, div, heavyFriendlyToScreen, nearestEnemyDiv, div.maneuverAnchor, div.side);
                targetLabel = heavyFriendlyToScreen.displayName;
                reason = "forward PD & anti-flank screen";
                div.lastIntentChangeTime = now;
                DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
                return;
            }
        }

        // 7. Torpedo / Strike Division: Harassing torpedo attack or Time-on-Target strike (ONLY against visible enemies within strike range <= 4600u)
        boolean strikeCooldownElapsed = (now - div.lastStrikeWithdrawTimestamp) >= 12.0f;
        if (strikeCooldownElapsed
                && div.hasTorpedoStrikeCapability
                && div.usableStrikeAmmoCount > 0
                && div.avgFluxLevel < 0.55f
                && torpedoTargetEnemyDiv != null
                && distToTorpedoEnemy <= 4600f) {

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

            Vector2f visTargetPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, torpedoTargetEnemyDiv);
            div.targetEnemyDivision = torpedoTargetEnemyDiv;
            div.maneuverAnchor.set(visTargetPos);
            if (!inTorpedoStrike) {
                div.strikeCommittedTimestamp = now;
            }
            targetLabel = torpedoTargetEnemyDiv.displayName;
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            return;
        }

        // 8. Fast flankers swing wide to flank visible enemy division when within operational contact range (<= 4500u)
        if (strikeCooldownElapsed
                && div.isFastFlanker
                && nearestEnemyDiv != null
                && distToVisibleEnemy <= 4500f
                && myBg.mainEffortDivision != null
                && myBg.mainEffortDivision != div
                && div.avgFluxLevel < 0.52f) {

            div.currentIntent = DivisionIntentType.FLANK_ENEMY_DIVISION;
            div.targetEnemyDivision = nearestEnemyDiv;
            computeFlankAnchor(engine, div, nearestEnemyDiv, div.maneuverAnchor);
            if (!inFlankManeuver) {
                div.strikeCommittedTimestamp = now;
            }
            targetLabel = nearestEnemyDiv.displayName;
            reason = "enveloping flank vector";
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

        // 10. Press local overmatch vs standard line advance (strictly respecting Fog of War)
        if (nearestEnemyDiv != null) {
            Vector2f visEnemyPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, nearestEnemyDiv);
            div.targetEnemyDivision = nearestEnemyDiv;
            div.maneuverAnchor.set(visEnemyPos);
            targetLabel = nearestEnemyDiv.displayName;

            if (div.localOvermatchRatio >= Math.max(1.22f, div.commander.overmatchCommitRatio) && distToVisibleEnemy < 3200f) {
                div.currentIntent = DivisionIntentType.PRESS_OVERMATCH;
                reason = String.format("local advantage %.2fx", div.localOvermatchRatio);
            } else {
                div.currentIntent = DivisionIntentType.ADVANCE_ENGAGE_LINE;
                reason = "maintaining firing line";
            }
            div.lastIntentChangeTime = now;
            DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
        } else {
            // No enemy visible in Fog of War yet: advance on reconnaissance / objective posture without map-hacking
            if (uncapturedObj != null && !isHeavyLineOrCarrier) {
                div.currentIntent = DivisionIntentType.SEIZE_OBJECTIVE;
                div.targetObjective = uncapturedObj;
                div.maneuverAnchor.set(uncapturedObj.getLocation());
                targetLabel = uncapturedObj.getDisplayName();
                reason = "advancing in fog of war";
                div.lastIntentChangeTime = now;
                DivisionCombatLog.logDivisionIntent(engine, div, targetLabel, reason, now);
            } else {
                float scoutDirY = (div.side == 0) ? 1400f : -1400f;
                div.currentIntent = DivisionIntentType.ADVANCE_ENGAGE_LINE;
                div.maneuverAnchor.set(div.centroid.x * 0.6f, div.centroid.y + scoutDirY);
                div.lastIntentChangeTime = now;
            }
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

        List<ShipAPI> alive = div.getAliveMembers();
        if (alive.isEmpty()) return;

        ShipAPI focusEnemyShip = pickFocusEnemyShip(engine, div.side, div.targetEnemyDivision, div.centroid);

        for (int shipIdx = 0; shipIdx < alive.size(); shipIdx++) {
            ShipAPI ship = alive.get(shipIdx);
            if (playerManualControl && ship == playerShip) {
                continue;
            }
            CombatTaskManagerAPI tm = (fm != null) ? fm.getTaskManager(ship.isAlly()) : null;
            if (hasExplicitCommandOrder(ship, div, tm)) {
                continue;
            }

            ShipwideAIFlags flags = ship.getAIFlags();
            if (flags == null) continue;

            Vector2f slotPos = computeShipFormationSlot(engine, ship, shipIdx, alive, div, myBg);
            boolean crowdedByPeer = isShipCrowdedByDivisionPeer(ship, alive, div.idealIntraSpacing * 0.72f);

            float distToDivCenter = distance(ship.getLocation(), div.centroid);
            boolean shipStraggling = alive.size() >= 2 && distToDivCenter > (div.desiredCohesionRadius * 1.35f);

            // Bounded pursuit & cohesion leash: prevent lone ships from chasing bait 3000u away
            if (shipStraggling && div.currentIntent != DivisionIntentType.HARASS_TORPEDO_ATTACK
                    && div.currentIntent != DivisionIntentType.COORDINATED_TORPEDO_STRIKE) {
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                continue;
            }

            switch (div.currentIntent) {
                case HARASS_TORPEDO_ATTACK:
                case COORDINATED_TORPEDO_STRIKE:
                    flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_TTL * 1.5f);
                    if (focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        if (distance(ship.getLocation(), focusEnemyShip.getLocation()) > 2100f) {
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        }
                    } else if (div.targetEnemyDivision != null) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case PRESS_OVERMATCH:
                    flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_TTL);
                    if (focusEnemyShip != null) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        if (distance(ship.getLocation(), focusEnemyShip.getLocation()) > 2100f || crowdedByPeer) {
                            flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        }
                    } else if (div.targetEnemyDivision != null) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case STRIKE_AND_WITHDRAW:
                case ROTATE_TO_RESERVE:
                    flags.setFlag(AIFlags.BACK_OFF, FLAG_TTL * 1.4f);
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL * 1.4f);
                    flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL * 1.4f, slotPos);
                    break;

                case SCREEN_FRIENDLY_DIVISION:
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    // Use spaced picket formation slots ahead/flank of the screened division instead of
                    // ESCORT_OTHER_SHIP on the flagship (which caused frigates to ball up on the battle line)
                    if (distance(ship.getLocation(), slotPos) > Math.max(260f, div.idealIntraSpacing * 0.55f) || crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    } else if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 1650f) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    }
                    break;

                case RELIEVE_FRIENDLY_DIVISION:
                    if (div.targetFriendlyDivision != null && div.targetFriendlyDivision.shipNeedingHelp != null
                            && div.targetFriendlyDivision.shipNeedingHelp.isAlive()
                            && shipIdx == 0) {
                        flags.setFlag(AIFlags.ESCORT_OTHER_SHIP, FLAG_TTL, div.targetFriendlyDivision.shipNeedingHelp);
                    } else {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;

                case FLANK_ENEMY_DIVISION:
                case SEIZE_OBJECTIVE:
                case HOLD_IN_RESERVE:
                    if (distance(ship.getLocation(), slotPos) > Math.max(320f, div.idealIntraSpacing * 0.65f) || crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    } else if (focusEnemyShip != null && div.currentIntent == DivisionIntentType.FLANK_ENEMY_DIVISION) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    }
                    break;

                case HOLD_AND_COHERE:
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_TTL);
                    if (distToDivCenter > div.desiredCohesionRadius * 0.85f || crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
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
                    } else if (crowdedByPeer) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                        if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 2600f) {
                            directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                        }
                    } else if (focusEnemyShip != null && distance(ship.getLocation(), focusEnemyShip.getLocation()) < 2600f) {
                        directShipAndFightersOntoTarget(ship, flags, focusEnemyShip);
                    } else if (div.targetEnemyDivision != null && distance(ship.getLocation(), slotPos) > 950f) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, FLAG_TTL, slotPos);
                    }
                    break;
            }
        }
    }

    /**
     * Respects explicit Command Window orders (such as CAPTURE / ASSAULT / DEFEND on a control point,
     * RALLY, ESCORT, RETREAT, etc.) so Division AI never pulls ships away from player-ordered control points.
     * In vanilla Starsector, ships without an explicit task have assignment == null or SEARCH_AND_DESTROY.
     */
    private static boolean hasExplicitCommandOrder(ShipAPI ship, DivisionState div, CombatTaskManagerAPI tm) {
        if (tm == null) return false;
        AssignmentInfo info = tm.getAssignmentFor(ship);
        if (info == null || info.getType() == null) return false;
        CombatAssignmentType t = info.getType();
        if (t == CombatAssignmentType.SEARCH_AND_DESTROY) {
            return false;
        }
        // If the ship is assigned to capture/control/assault an objective and its division is already seizing that
        // exact objective, they are aligned; otherwise, let the ship execute its explicit Command Window assignment!
        if (info.getTarget() instanceof BattleObjectiveAPI) {
            if (div.currentIntent == DivisionIntentType.SEIZE_OBJECTIVE && div.targetObjective == info.getTarget()) {
                return false;
            }
            return true;
        }
        return true;
    }

    private static DivisionState findBestEnemyDivisionFor(
            CombatEngineAPI engine,
            DivisionState myDiv,
            BattlegroupState enemyBg,
            boolean preferTorpedoPrey,
            DivisionState previousTarget) {

        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;

        for (DivisionState eDiv : enemyBg.divisions) {
            if (!eDiv.isCombatEffective()) continue;
            // Strict Fog of War check: cannot target enemy divisions hidden in the Fog of War!
            if (!BattlegroupCommanderAI.isDivisionVisibleToSide(engine, myDiv.side, eDiv)) {
                continue;
            }

            Vector2f visCentroid = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, myDiv.side, eDiv);
            float visFp = BattlegroupCommanderAI.getVisibleFpForSide(engine, myDiv.side, eDiv);
            float dist = distance(myDiv.centroid, visCentroid);
            float score = -dist * 0.35f;

            // Sticky target bonus prevents divisions from flip-flopping between two enemy divisions every few seconds
            if (eDiv == previousTarget) {
                score += 280f;
            }

            // Deprioritize civilian/auxiliary groups when combat divisions exist
            if (eDiv.archetype == DivisionArchetype.AUXILIARY_CONVOY) {
                score -= 450f;
            }

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
                score += visFp * 6f;
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

            // A composite Carrier & Escort division already has organic escorts; only assign an extra
            // screening division if it is under local enemy pressure or lost its escorts.
            if (other.archetype == DivisionArchetype.CARRIER_AND_ESCORT
                    && other.getAliveMembers().size() >= 2
                    && other.localEnemyStrength < 15f) {
                continue;
            }

            // Cap screening to at most 1 screening division per heavy division to prevent fleet-wide screen dogpiling
            boolean alreadyScreenedByPeer = false;
            for (DivisionState peer : myBg.divisions) {
                if (peer != myDiv && peer != other && peer.isCombatEffective()
                        && peer.currentIntent == DivisionIntentType.SCREEN_FRIENDLY_DIVISION
                        && peer.targetFriendlyDivision == other) {
                    alreadyScreenedByPeer = true;
                    break;
                }
            }
            if (alreadyScreenedByPeer) continue;

            // Keep existing screen target sticky to avoid ping-ponging between two heavy divisions
            float stickyBonus = (myDiv.currentIntent == DivisionIntentType.SCREEN_FRIENDLY_DIVISION
                    && myDiv.targetFriendlyDivision == other) ? 350f : 0f;

            float dist = distance(myDiv.centroid, other.centroid);
            float score = other.totalFpStrength * 10f - dist * 0.3f + stickyBonus;
            if (other.archetype == DivisionArchetype.BATTLE_DIVISION) {
                score += 650f;
            } else if (other.archetype == DivisionArchetype.CARRIER_AND_ESCORT) {
                score += 400f;
            }
            if (score > bestScore) {
                bestScore = score;
                best = other;
            }
        }
        return best;
    }

    private static ShipAPI pickFocusEnemyShip(
            CombatEngineAPI engine,
            int observerSide,
            DivisionState enemyDiv,
            Vector2f fromPoint) {
        if (enemyDiv == null) return null;
        List<ShipAPI> enemies = enemyDiv.getAliveMembers();
        ShipAPI best = null;
        float bestScore = -Float.MAX_VALUE;
        for (ShipAPI e : enemies) {
            if (!BattlegroupCommanderAI.isShipVisibleToSide(engine, observerSide, e)) continue;
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

    /**
     * Checks if any ship in this division has been directly assigned to an objective in CombatTaskManager,
     * or if there is an unclaimed player/task-manager objective assignment in tm.getAllAssignments().
     */
    private static BattleObjectiveAPI findTaskManagerObjectiveForDivision(
            CombatEngineAPI engine,
            DivisionState div,
            BattlegroupState myBg,
            BattleObjectiveAPI previousObjective) {
        if (engine == null) return null;
        if (div.archetype == DivisionArchetype.CARRIER_AND_ESCORT
                || div.archetype == DivisionArchetype.BATTLE_DIVISION) {
            return null;
        }
        CombatFleetManagerAPI fm = engine.getFleetManager(div.side);
        boolean divIsAlly = (div.flagship != null && div.flagship.isAlly());
        CombatTaskManagerAPI tm = (fm != null) ? fm.getTaskManager(divIsAlly) : null;
        if (tm == null) return null;

        // 1. Check if any ship in this division is directly assigned to a BattleObjectiveAPI
        for (ShipAPI s : div.getAliveMembers()) {
            AssignmentInfo info = tm.getAssignmentFor(s);
            if (info != null && info.getTarget() instanceof BattleObjectiveAPI) {
                BattleObjectiveAPI obj = (BattleObjectiveAPI) info.getTarget();
                if (!isObjectiveClaimedByOtherDivision(obj, div, myBg)) {
                    return obj;
                }
            }
        }

        // 2. Check active objective assignments in CombatTaskManager for fast maneuver / destroyer divisions
        if (div.archetype != DivisionArchetype.FRIGATE_DIVISION
                && div.archetype != DivisionArchetype.PHASE_DIVISION
                && div.archetype != DivisionArchetype.DESTROYER_DIVISION
                && div.archetype != DivisionArchetype.TORPEDO_DESTROYER_DIVISION) {
            return null;
        }
        List<AssignmentInfo> all = tm.getAllAssignments();
        if (all != null) {
            BattleObjectiveAPI best = null;
            float bestScore = -Float.MAX_VALUE;
            for (AssignmentInfo info : all) {
                if (info == null || !(info.getTarget() instanceof BattleObjectiveAPI)) continue;
                CombatAssignmentType t = info.getType();
                if (t != CombatAssignmentType.CAPTURE
                        && t != CombatAssignmentType.ASSAULT
                        && t != CombatAssignmentType.CONTROL
                        && t != CombatAssignmentType.DEFEND
                        && t != CombatAssignmentType.RECON) {
                    continue;
                }
                BattleObjectiveAPI obj = (BattleObjectiveAPI) info.getTarget();
                if (isObjectiveClaimedByOtherDivision(obj, div, myBg)) continue;
                float d = distance(div.centroid, obj.getLocation());
                float sticky = (obj == previousObjective) ? 450f : 0f;
                float score = -d + sticky;
                if (score > bestScore) {
                    bestScore = score;
                    best = obj;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static BattleObjectiveAPI findNearestContestObjective(
            CombatEngineAPI engine,
            DivisionState div,
            BattlegroupState myBg,
            BattleObjectiveAPI previousObjective) {
        List<BattleObjectiveAPI> objs = engine.getObjectives();
        if (objs == null || objs.isEmpty()) return null;

        // First pass: prefer uncaptured objectives NOT already claimed by another friendly division
        BattleObjectiveAPI bestUnclaimed = null;
        float bestScore = -Float.MAX_VALUE;
        for (BattleObjectiveAPI obj : objs) {
            if (obj == null || obj.getOwner() == div.side) continue;
            if (isObjectiveClaimedByOtherDivision(obj, div, myBg)) continue;
            float d = distance(div.centroid, obj.getLocation());
            float sticky = (obj == previousObjective) ? 450f : 0f;
            float score = -d + sticky;
            if (score > bestScore) {
                bestScore = score;
                bestUnclaimed = obj;
            }
        }
        return bestUnclaimed;
    }

    private static boolean isObjectiveClaimedByOtherDivision(
            BattleObjectiveAPI obj,
            DivisionState myDiv,
            BattlegroupState myBg) {
        if (obj == null || myBg == null) return false;
        for (DivisionState peer : myBg.divisions) {
            if (peer != myDiv && peer.isCombatEffective()
                    && peer.currentIntent == DivisionIntentType.SEIZE_OBJECTIVE
                    && peer.targetObjective == obj) {
                return true;
            }
        }
        return false;
    }

    private static boolean isShipCrowdedByDivisionPeer(ShipAPI ship, List<ShipAPI> alive, float minSpacing) {
        if (ship == null || alive == null || alive.size() <= 1) return false;
        for (ShipAPI peer : alive) {
            if (peer == null || peer == ship || !peer.isAlive()) continue;
            if (distance(ship.getLocation(), peer.getLocation()) < minSpacing) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes a ship's individual formation slot around its division's maneuver anchor using the division's
     * configured idealIntraSpacing (in units) and applies peer-separation + main-battle-line standoff repulsion
     * so frigates and line ships maintain their doctrinal intra-division spacing instead of balling up.
     */
    private static Vector2f computeShipFormationSlot(
            CombatEngineAPI engine,
            ShipAPI ship,
            int shipIdx,
            List<ShipAPI> alive,
            DivisionState div,
            BattlegroupState myBg) {

        float spacing = Math.max(150f, div.idealIntraSpacing);
        Vector2f baseCenter = (div.currentIntent == DivisionIntentType.HOLD_AND_COHERE)
                ? div.centroid
                : div.maneuverAnchor;

        float fwdX = 0f;
        float fwdY = (div.side == 0) ? 1f : -1f;
        if (div.targetEnemyDivision != null) {
            Vector2f visEnemy = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, div.side, div.targetEnemyDivision);
            float dx = visEnemy.x - div.centroid.x;
            float dy = visEnemy.y - div.centroid.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1f) {
                fwdX = dx / len;
                fwdY = dy / len;
            }
        }
        // Lateral axis perpendicular to forward threat vector (line-abreast / echelon spacing)
        float latX = -fwdY;
        float latY = fwdX;

        int rank = (shipIdx + 1) / 2;
        float sideSign = (shipIdx == 0) ? 0f : ((shipIdx % 2 == 1) ? -1f : 1f);
        float latOffset = sideSign * rank * spacing;
        float depthOffset = -rank * (spacing * 0.16f);

        float slotX = baseCenter.x + latX * latOffset + fwdX * depthOffset;
        float slotY = baseCenter.y + latY * latOffset + fwdY * depthOffset;

        // Peer repulsion: if another ship in the same division is closer than idealIntraSpacing * 0.90f, push apart
        for (ShipAPI peer : alive) {
            if (peer == null || peer == ship || !peer.isAlive()) continue;
            float pdx = ship.getLocation().x - peer.getLocation().x;
            float pdy = ship.getLocation().y - peer.getLocation().y;
            float pDist = (float) Math.sqrt(pdx * pdx + pdy * pdy);
            if (pDist > 1f && pDist < spacing * 0.90f) {
                float push = (spacing - pDist) * 0.75f;
                slotX += (pdx / pDist) * push;
                slotY += (pdy / pDist) * push;
            }
        }

        // Anti-balling with main battle line: keep Frigate & Phase divisions spaced clear of heavy main-line centroid
        if ((div.archetype == DivisionArchetype.FRIGATE_DIVISION
                || div.archetype == DivisionArchetype.PHASE_DIVISION)
                && myBg != null
                && myBg.mainEffortDivision != null
                && myBg.mainEffortDivision != div
                && myBg.mainEffortDivision.isCombatEffective()) {
            Vector2f mainPos = myBg.mainEffortDivision.centroid;
            float mdx = ship.getLocation().x - mainPos.x;
            float mdy = ship.getLocation().y - mainPos.y;
            float mDist = (float) Math.sqrt(mdx * mdx + mdy * mdy);
            float minClearance = Math.max(550f, div.lineStandoffDistance * 0.75f);
            if (mDist > 1f && mDist < minClearance) {
                float push = (minClearance - mDist) * 0.85f;
                slotX += (mdx / mDist) * push;
                slotY += (mdy / mDist) * push;
            }
        }

        return new Vector2f(slotX, slotY);
    }

    private static void computeScreenAnchor(
            CombatEngineAPI engine,
            DivisionState screenDiv,
            DivisionState protectedDiv,
            DivisionState enemyDiv,
            Vector2f outAnchor,
            int side) {
        if (protectedDiv == null) return;
        float standoff = (screenDiv != null) ? Math.max(550f, screenDiv.lineStandoffDistance) : 750f;
        float flankSign = (screenDiv != null && screenDiv.divisionNumber % 2 == 0) ? -1f : 1f;
        float lateralOffset = (screenDiv != null) ? Math.max(320f, screenDiv.idealIntraSpacing * 0.85f) * flankSign : 0f;

        if (enemyDiv != null) {
            Vector2f visEnemyPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, side, enemyDiv);
            float dx = visEnemyPos.x - protectedDiv.centroid.x;
            float dy = visEnemyPos.y - protectedDiv.centroid.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1f) {
                float fwdX = dx / len;
                float fwdY = dy / len;
                float latX = -fwdY;
                float latY = fwdX;
                outAnchor.set(
                        protectedDiv.centroid.x + fwdX * (standoff * 0.80f) + latX * lateralOffset,
                        protectedDiv.centroid.y + fwdY * (standoff * 0.80f) + latY * lateralOffset);
                return;
            }
        }
        float forwardY = (side == 0) ? standoff * 0.80f : -standoff * 0.80f;
        outAnchor.set(protectedDiv.centroid.x + lateralOffset, protectedDiv.centroid.y + forwardY);
    }

    private static void computeFlankAnchor(
            CombatEngineAPI engine,
            DivisionState flankerDiv,
            DivisionState enemyDiv,
            Vector2f outAnchor) {
        Vector2f visEnemyPos = BattlegroupCommanderAI.getVisibleCentroidForSide(engine, flankerDiv.side, enemyDiv);
        float dx = visEnemyPos.x - flankerDiv.centroid.x;
        float dy = visEnemyPos.y - flankerDiv.centroid.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= 1f) {
            outAnchor.set(visEnemyPos);
            return;
        }
        // Perpendicular offset to swing wide around the enemy division's flank, scaled by doctrinal standoff & spacing
        float perpX = -dy / len;
        float perpY = dx / len;
        float sideSign = (flankerDiv.divisionNumber % 2 == 1) ? 1f : -1f;
        float flankRadius = Math.max(1050f, flankerDiv.lineStandoffDistance + flankerDiv.idealIntraSpacing * 0.65f);
        outAnchor.set(
                visEnemyPos.x + perpX * flankRadius * sideSign,
                visEnemyPos.y + perpY * flankRadius * sideSign);
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
