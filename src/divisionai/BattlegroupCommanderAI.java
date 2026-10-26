package divisionai;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CollisionGridAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FogOfWarAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import divisionai.DivisionModel.BattlePhase;
import divisionai.DivisionModel.BattlegroupState;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionModel.MissionContextType;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.OrchestrationPlay;
import org.lwjgl.util.vector.Vector2f;

import java.util.Iterator;
import java.util.List;

/**
 * Force-level Battlegroup Commander AI:
 *  1. Updates division telemetry (centroid, spread/cohesion, flux/hull pressure, strike ammo,
 *     and local overmatch via CombatEngineAPI's spatial grid iterator).
 *  2. Evaluates battle-wide force ratios, endangered friendly divisions, and high-value enemy
 *     divisions.
 *  3. Chooses the Battlegroup operational phase, designates Main Effort and Reserve divisions,
 *     and issues battle-wide directives logged to the player's combat feed.
 */
public class BattlegroupCommanderAI {

    private static final float LOCAL_EVAL_DIAMETER = 4600f; // 2300u radius around division centroid
    private static final float MIN_PHASE_DWELL = 10f;

    public static void updateTelemetry(CombatEngineAPI engine, BattlegroupState bg) {
        float fleetWeightedX = 0f;
        float fleetWeightedY = 0f;
        float totalFriendlyFp = 0f;

        for (DivisionState div : bg.divisions) {
            List<ShipAPI> alive = div.getAliveMembers();
            if (alive.isEmpty()) {
                div.localFriendlyStrength = 0f;
                div.localEnemyStrength = 0f;
                div.localOvermatchRatio = 1f;
                continue;
            }

            float sumX = 0f;
            float sumY = 0f;
            float sumVx = 0f;
            float sumVy = 0f;
            float weightSum = 0f;
            float fluxSum = 0f;
            float maxFlux = 0f;
            float hullSum = 0f;
            int strikeAmmo = 0;
            boolean needsHelp = false;
            ShipAPI helpShip = null;

            for (ShipAPI s : alive) {
                float w = ShipCapabilityProfile.resolveShipFpStrength(s);
                sumX += s.getLocation().x * w;
                sumY += s.getLocation().y * w;
                sumVx += s.getVelocity().x * w;
                sumVy += s.getVelocity().y * w;
                weightSum += w;

                float fl = s.getFluxLevel();
                fluxSum += fl;
                if (fl > maxFlux) maxFlux = fl;
                hullSum += s.getHullLevel();

                ShipwideAIFlags flags = s.getAIFlags();
                if (flags != null && (flags.hasFlag(AIFlags.NEEDS_HELP) || flags.hasFlag(AIFlags.IN_CRITICAL_DPS_DANGER))) {
                    needsHelp = true;
                    helpShip = s;
                } else if (fl >= 0.84f || s.getHullLevel() <= 0.45f) {
                    needsHelp = true;
                    if (helpShip == null) helpShip = s;
                }

                strikeAmmo += countUsableStrikeAmmo(s);
            }

            div.centroid.set(sumX / weightSum, sumY / weightSum);
            div.velocity.set(sumVx / weightSum, sumVy / weightSum);
            div.avgFluxLevel = fluxSum / alive.size();
            div.maxMemberFluxLevel = maxFlux;
            div.avgHullLevel = hullSum / alive.size();
            div.hasShipNeedingHelp = needsHelp;
            div.shipNeedingHelp = helpShip;
            div.usableStrikeAmmoCount = strikeAmmo;
            div.totalFpStrength = weightSum;

            // Compute RMS spread radius around division centroid
            float distSqSum = 0f;
            for (ShipAPI s : alive) {
                float dx = s.getLocation().x - div.centroid.x;
                float dy = s.getLocation().y - div.centroid.y;
                distSqSum += (dx * dx + dy * dy);
            }
            div.spreadRadius = (float) Math.sqrt(distSqSum / alive.size());
            div.cohesionRatio = div.spreadRadius / Math.max(300f, div.desiredCohesionRadius);

            // Spatial-grid local overmatch scan around division centroid (never O(N^2))
            evaluateLocalOvermatchViaGrid(engine, div);

            fleetWeightedX += div.centroid.x * weightSum;
            fleetWeightedY += div.centroid.y * weightSum;
            totalFriendlyFp += weightSum;
        }

        bg.totalFriendlyStrength = totalFriendlyFp;
        if (totalFriendlyFp > 0f) {
            bg.fleetCentroid.set(fleetWeightedX / totalFriendlyFp, fleetWeightedY / totalFriendlyFp);
        }

        // Reserve anchor sits 1800u behind the fleet centroid toward friendly map edge
        float retreatDirY = (bg.side == 0) ? -1f : 1f;
        bg.reserveAnchor.set(bg.fleetCentroid.x, bg.fleetCentroid.y + retreatDirY * 1800f);
    }

    private static void evaluateLocalOvermatchViaGrid(CombatEngineAPI engine, DivisionState div) {
        CollisionGridAPI grid = engine.getAiGridShips();
        if (grid == null) {
            grid = engine.getShipGrid();
        }
        if (grid == null) {
            div.localFriendlyStrength = div.totalFpStrength;
            div.localEnemyStrength = 0f;
            div.localOvermatchRatio = 2.0f;
            return;
        }

        float friendlyFp = 0f;
        float enemyFp = 0f;
        float radiusSq = (LOCAL_EVAL_DIAMETER * 0.5f) * (LOCAL_EVAL_DIAMETER * 0.5f);

        Iterator<Object> iter = grid.getCheckIterator(div.centroid, LOCAL_EVAL_DIAMETER, LOCAL_EVAL_DIAMETER);
        while (iter != null && iter.hasNext()) {
            Object obj = iter.next();
            if (!(obj instanceof ShipAPI)) continue;
            ShipAPI s = (ShipAPI) obj;
            if (!DivisionAutoFormer.isEligibleCombatHull(s)) continue;

            float dx = s.getLocation().x - div.centroid.x;
            float dy = s.getLocation().y - div.centroid.y;
            if ((dx * dx + dy * dy) > radiusSq) continue;

            float fp = ShipCapabilityProfile.resolveShipFpStrength(s);
            // Weight combat effectiveness by remaining hull and flux headroom
            float readiness = Math.max(0.35f, s.getHullLevel() * (1.15f - 0.5f * s.getFluxLevel()));
            if (s.getOwner() == div.side) {
                friendlyFp += fp * readiness;
            } else if (isShipVisibleToSide(engine, div.side, s)) {
                enemyFp += fp * readiness;
            }
        }

        div.localFriendlyStrength = Math.max(friendlyFp, div.totalFpStrength * 0.5f);
        div.localEnemyStrength = enemyFp;
        div.localOvermatchRatio = (enemyFp <= 1f) ? 3.0f : (div.localFriendlyStrength / enemyFp);
    }

    public static boolean isShipVisibleToSide(CombatEngineAPI engine, int observerSide, ShipAPI ship) {
        if (ship == null || !ship.isAlive() || ship.isHulk()) return false;
        if (engine == null) return true;
        FogOfWarAPI fog = engine.getFogOfWar(observerSide);
        return fog == null || fog.isVisible(ship);
    }

    public static boolean isDivisionVisibleToSide(CombatEngineAPI engine, int observerSide, DivisionState div) {
        if (div == null) return false;
        if (observerSide == div.side) return div.isCombatEffective();
        for (ShipAPI s : div.getAliveMembers()) {
            if (isShipVisibleToSide(engine, observerSide, s)) {
                return true;
            }
        }
        return false;
    }

    public static Vector2f getVisibleCentroidForSide(CombatEngineAPI engine, int observerSide, DivisionState div) {
        if (div == null) return new Vector2f(0f, 0f);
        if (observerSide == div.side || engine == null) return div.centroid;
        float sumX = 0f;
        float sumY = 0f;
        float weightSum = 0f;
        for (ShipAPI s : div.getAliveMembers()) {
            if (!isShipVisibleToSide(engine, observerSide, s)) continue;
            float w = ShipCapabilityProfile.resolveShipFpStrength(s);
            sumX += s.getLocation().x * w;
            sumY += s.getLocation().y * w;
            weightSum += w;
        }
        if (weightSum <= 0f) {
            return div.centroid;
        }
        return new Vector2f(sumX / weightSum, sumY / weightSum);
    }

    public static float getVisibleFpForSide(CombatEngineAPI engine, int observerSide, DivisionState div) {
        if (div == null) return 0f;
        if (observerSide == div.side || engine == null) return div.totalFpStrength;
        float fpSum = 0f;
        for (ShipAPI s : div.getAliveMembers()) {
            if (isShipVisibleToSide(engine, observerSide, s)) {
                fpSum += ShipCapabilityProfile.resolveShipFpStrength(s);
            }
        }
        return fpSum;
    }

    /**
     * Evaluates Battlegroup strategy, phase transitions, Main Effort, and Reserve designations.
     */
    public static void evaluateBattlegroupStrategy(
            CombatEngineAPI engine,
            BattlegroupState myBg,
            BattlegroupState enemyBg,
            float now) {

        float visibleEnemyFp = 0f;
        for (DivisionState eDiv : enemyBg.divisions) {
            visibleEnemyFp += getVisibleFpForSide(engine, myBg.side, eDiv);
        }
        myBg.totalEnemyStrength = visibleEnemyFp;
        // Under Fog of War, do not assume a single spotted 8 FP scout represents the entire enemy fleet!
        // Use the larger of visibleEnemyFp or 75% of friendly strength (capped by actual deployed enemy strength)
        // when estimating battle-wide force ratio so spotting 1 scout doesn't report a 27x breakthrough.
        float estimatedEnemyFleetFp = Math.max(
                visibleEnemyFp,
                Math.min(enemyBg.totalFriendlyStrength, myBg.totalFriendlyStrength * 0.85f));
        myBg.forceRatio = (estimatedEnemyFleetFp <= 1f)
                ? 1.25f
                : (myBg.totalFriendlyStrength / estimatedEnemyFleetFp);

        int activeDivCount = 0;
        DivisionState endangeredDiv = null;
        float worstOvermatch = Float.MAX_VALUE;
        DivisionState strongestLineDiv = null;
        float strongestLineFp = -1f;
        DivisionState bestReserveCandidate = null;
        float bestReserveScore = -1f;

        float significantFp = Math.max(10f, myBg.totalFriendlyStrength * 0.12f);
        DivisionState minorEndangeredDiv = null;
        float worstMinorOvermatch = Float.MAX_VALUE;
        for (DivisionState div : myBg.divisions) {
            if (!div.isCombatEffective()) {
                div.isDesignatedMainEffort = false;
                div.isDesignatedReserve = false;
                continue;
            }
            activeDivCount++;

            // Only divisions that matter to the battle can flip the whole battlegroup into Stabilize & Relieve;
            // a lone frigate or destroyer in trouble is handled by local mutual support instead.
            boolean significant = div.totalFpStrength >= significantFp
                    || div.archetype == DivisionArchetype.BATTLE_DIVISION
                    || div.archetype.isCarrierArchetype();
            if (significant && div.localEnemyStrength > 8f && div.localOvermatchRatio < worstOvermatch) {
                worstOvermatch = div.localOvermatchRatio;
                endangeredDiv = div;
            }
            if (div.localEnemyStrength > 8f && div.localOvermatchRatio < 0.60f && div.localOvermatchRatio < worstMinorOvermatch) {
                worstMinorOvermatch = div.localOvermatchRatio;
                minorEndangeredDiv = div;
            }

            float mainScore = div.totalFpStrength * (1.2f - 0.5f * div.avgFluxLevel);
            if (div.archetype == DivisionArchetype.BATTLE_DIVISION) {
                mainScore = mainScore * 2.05f + 25f;
            } else if (div.archetype == DivisionArchetype.CRUISER_DIVISION
                    || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION) {
                mainScore *= 1.35f;
            } else if (div.archetype.isCarrierArchetype()) {
                mainScore *= 0.55f;
            }
            // The Main Effort anchors the line, so prefer divisions whose mission keeps them there
            DivisionMissionType mission = DivisionCommanderAI.resolveActiveMission(div, now);
            if (mission == DivisionMissionType.OUTFLANK_AND_DESTROY
                    || mission == DivisionMissionType.SCREEN
                    || mission == DivisionMissionType.BATTLEGROUP_RESERVE) {
                mainScore *= 0.6f;
            }
            if (div == myBg.mainEffortDivision) {
                mainScore *= 1.15f;
            }
            if (mainScore > strongestLineFp) {
                strongestLineFp = mainScore;
                strongestLineDiv = div;
            }
        }

        // Second pass: pick a mobile reserve candidate (never pick Main Effort, Battle Divisions, Cruiser lines, or Carrier groups)
        for (DivisionState div : myBg.divisions) {
            if (!div.isCombatEffective()) continue;
            if (div == strongestLineDiv
                    || div.archetype == DivisionArchetype.BATTLE_DIVISION
                    || div.archetype == DivisionArchetype.CRUISER_DIVISION
                    || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION
                    || div.archetype.isCarrierArchetype()) {
                continue;
            }
            float distToEnemy = distanceToNearestEnemyDivision(engine, div, enemyBg);
            float distFactor = (distToEnemy < Float.MAX_VALUE * 0.5f) ? (distToEnemy * 0.001f) : 2.0f;
            float reserveScore = distFactor + (div.isFastFlanker ? 1.5f : 0.8f) - div.avgFluxLevel;
            if (DivisionCommanderAI.resolveActiveMission(div, now) == DivisionMissionType.BATTLEGROUP_RESERVE) {
                reserveScore += 5f;
            }
            if (div == myBg.reserveDivision) {
                reserveScore += 1.0f; // sticky: don't swap the reserve between two similar divisions
            }
            if (reserveScore > bestReserveScore) {
                bestReserveScore = reserveScore;
                bestReserveCandidate = div;
            }
        }

        myBg.mainEffortDivision = strongestLineDiv;
        myBg.primaryThreatEnemyDivision = pickPrimaryEnemyTargetDivision(engine, myBg, enemyBg);

        // Determine operational BattlePhase (strictly respecting Fog of War: if no enemy is spotted yet, stay in OPENING_PROBE)
        float minContactDist = minDistanceBetweenFleets(engine, myBg, enemyBg);
        List<BattleObjectiveAPI> objectives = engine.getObjectives();
        boolean hasUncapturedObjectives = hasContestObjectives(objectives, myBg.side);
        boolean anyEnemyVisible = (myBg.primaryThreatEnemyDivision != null);
        BattlePhase current = myBg.currentPhase;

        // Hysteresis: entering a phase needs a clearer signal than staying in it, so the battlegroup does not
        // flap between Stabilize and Breakthrough every few seconds.
        boolean crisis = endangeredDiv != null
                && (worstOvermatch < 0.72f || (endangeredDiv.hasShipNeedingHelp && worstOvermatch < 0.85f));
        boolean crisisPersists = current == BattlePhase.STABILIZE_AND_RELIEVE
                && endangeredDiv != null && worstOvermatch < 0.90f;
        boolean breakthrough = myBg.forceRatio >= 1.35f && visibleEnemyFp >= 30f && minContactDist < 3000f;
        boolean breakthroughPersists = current == BattlePhase.EXPLOIT_BREAKTHROUGH
                && myBg.forceRatio >= 1.20f && visibleEnemyFp >= 15f && minContactDist < 3600f;
        boolean strikeRunning = myBg.strikePlan != null && myBg.isPlayEnabled(OrchestrationPlay.TIMED_STRIKES);

        BattlePhase nextPhase;
        if (myBg.missionContext == MissionContextType.FLEET_ESCAPE_REARGUARD
                || (anyEnemyVisible && myBg.forceRatio < 0.58f && myBg.missionContext != MissionContextType.FIGHT_TO_THE_LAST)) {
            nextPhase = BattlePhase.ORDERED_WITHDRAWAL;
        } else if (crisis || crisisPersists) {
            nextPhase = BattlePhase.STABILIZE_AND_RELIEVE;
        } else if ((!anyEnemyVisible && now < 90f) || (minContactDist > 3300f && hasUncapturedObjectives && now < 75f)) {
            nextPhase = BattlePhase.OPENING_PROBE;
        } else if (breakthrough || breakthroughPersists) {
            nextPhase = BattlePhase.EXPLOIT_BREAKTHROUGH;
        } else if (strikeRunning || (hasTorpedoOrStrikeDivisionReady(myBg) && minContactDist < 3400f)) {
            nextPhase = BattlePhase.COORDINATED_STRIKE;
        } else {
            nextPhase = BattlePhase.LINE_CONTACT;
        }

        // Minimum dwell: a phase holds for MIN_PHASE_DWELL seconds unless withdrawal is ordered or a severe crisis erupts
        boolean dwellElapsed = (now - myBg.phaseEnteredTime) >= MIN_PHASE_DWELL;
        boolean severeCrisis = endangeredDiv != null && worstOvermatch < 0.45f;
        if (nextPhase != current && !dwellElapsed
                && nextPhase != BattlePhase.ORDERED_WITHDRAWAL
                && !(nextPhase == BattlePhase.STABILIZE_AND_RELIEVE && severeCrisis)) {
            nextPhase = current;
        }

        myBg.previousPhase = current;
        myBg.currentPhase = nextPhase;
        if (nextPhase != current) {
            myBg.phaseEnteredTime = now;
        }

        // Reserve management. With the Reserve & Exploit play the reserve is held until the BG commits it
        // (relief, exploitation) or 150s pass; otherwise only during the opening 90s.
        boolean reservePlay = myBg.isPlayEnabled(OrchestrationPlay.RESERVE_AND_EXPLOIT) && myBg.authority.allowsRetasking();
        boolean holdReserve;
        if (reservePlay) {
            holdReserve = !myBg.reserveCommitted
                    && (now < 150f)
                    && (activeDivCount >= 3)
                    && nextPhase != BattlePhase.ORDERED_WITHDRAWAL
                    && (myBg.bgCommander.aggressionBias <= 1.35f);
        } else {
            holdReserve = (now < 90f)
                    && (activeDivCount >= 3)
                    && (nextPhase == BattlePhase.OPENING_PROBE || nextPhase == BattlePhase.LINE_CONTACT)
                    && (myBg.bgCommander.aggressionBias <= 1.20f);
        }

        for (DivisionState d : myBg.divisions) {
            d.isDesignatedMainEffort = (d == myBg.mainEffortDivision);
            d.isDesignatedReserve = holdReserve && (d == bestReserveCandidate);
        }
        myBg.reserveDivision = holdReserve ? bestReserveCandidate : null;

        // Orchestrate: turn the battle plan into per-division taskings
        BattlegroupOrchestrator.orchestrate(engine, myBg, enemyBg, endangeredDiv, minorEndangeredDiv, now);

        // Log Battlegroup Commander operational directive when phase or primary focus shifts
        String directive = buildBattlegroupDirectiveSummary(myBg, endangeredDiv);
        DivisionCombatLog.logBattlegroupDirective(engine, myBg, directive, now);
    }

    private static String buildBattlegroupDirectiveSummary(BattlegroupState bg, DivisionState endangeredDiv) {
        String mainName = bg.mainEffortDivision != null ? "\"" + bg.mainEffortDivision.displayName + "\"" : "the main body";
        String targetName = bg.primaryThreatEnemyDivision != null ? "\"" + bg.primaryThreatEnemyDivision.displayName + "\"" : "the enemy line";

        switch (bg.currentPhase) {
            case OPENING_PROBE:
                if (bg.reserveDivision != null) {
                    return String.format("Scouts ahead, %s leading; \"%s\" held in reserve.", mainName, bg.reserveDivision.displayName);
                }
                return String.format("Scouts ahead, %s leading, to take objectives and gain contact.", mainName);
            case COORDINATED_STRIKE:
                if (bg.strikePlan != null && bg.strikePlan.target != null) {
                    return String.format("%s fixes; %d strike division%s on time-on-target against \"%s\".",
                            mainName,
                            bg.strikePlan.participants.size(),
                            bg.strikePlan.participants.size() == 1 ? "" : "s",
                            bg.strikePlan.target.displayName);
                }
                return String.format("%s fixes; strike divisions stand by against %s.", mainName, targetName);
            case STABILIZE_AND_RELIEVE:
                if (endangeredDiv != null) {
                    return String.format("Reinforce \"%s\" (local ratio %.2f); close up the screens.",
                            endangeredDiv.displayName, endangeredDiv.localOvermatchRatio);
                }
                return "Reinforce the line and haul hot divisions out to vent.";
            case EXPLOIT_BREAKTHROUGH:
                return String.format("General chase: press the advantage (%.2fx) on %s.", bg.forceRatio, targetName);
            case ORDERED_WITHDRAWAL:
                return "Retire by divisions toward the rendezvous, covering each other.";
            case LINE_CONTACT:
            default:
                return String.format("Line of battle formed on %s, engaging %s.", mainName, targetName);
        }
    }

    private static DivisionState pickPrimaryEnemyTargetDivision(
            CombatEngineAPI engine,
            BattlegroupState myBg,
            BattlegroupState enemyBg) {
        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;
        for (DivisionState eDiv : enemyBg.divisions) {
            if (!eDiv.isCombatEffective()) continue;
            if (!isDivisionVisibleToSide(engine, myBg.side, eDiv)) continue;
            Vector2f visCentroid = getVisibleCentroidForSide(engine, myBg.side, eDiv);
            float visFp = getVisibleFpForSide(engine, myBg.side, eDiv);
            float dist = MiscDistance(myBg.fleetCentroid, visCentroid);
            // Prefer enemy divisions that are close, isolated, or high-value
            float isolationBonus = (eDiv.cohesionRatio > 1.3f) ? 350f : 0f;
            float auxPenalty = (eDiv.archetype == DivisionArchetype.AUXILIARY_CONVOY) ? -500f : 0f;
            float score = visFp * 12f + isolationBonus + auxPenalty - dist * 0.25f;
            if (score > bestScore) {
                bestScore = score;
                best = eDiv;
            }
        }
        return best;
    }

    private static boolean hasTorpedoOrStrikeDivisionReady(BattlegroupState bg) {
        for (DivisionState d : bg.divisions) {
            if (d.isCombatEffective() && d.hasTorpedoStrikeCapability && d.avgFluxLevel < 0.60f && d.usableStrikeAmmoCount > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasContestObjectives(List<BattleObjectiveAPI> objectives, int side) {
        if (objectives == null || objectives.isEmpty()) return false;
        for (BattleObjectiveAPI obj : objectives) {
            if (obj != null && obj.getOwner() != side) {
                return true;
            }
        }
        return false;
    }

    private static float minDistanceBetweenFleets(CombatEngineAPI engine, BattlegroupState a, BattlegroupState b) {
        float minDist = Float.MAX_VALUE;
        for (DivisionState da : a.divisions) {
            if (!da.isCombatEffective()) continue;
            float d = distanceToNearestEnemyDivision(engine, da, b);
            if (d < minDist) minDist = d;
        }
        return minDist;
    }

    public static float distanceToNearestEnemyDivision(
            CombatEngineAPI engine,
            DivisionState myDiv,
            BattlegroupState enemyBg) {
        float minDist = Float.MAX_VALUE;
        for (DivisionState eDiv : enemyBg.divisions) {
            if (!eDiv.isCombatEffective()) continue;
            if (!isDivisionVisibleToSide(engine, myDiv.side, eDiv)) continue;
            Vector2f visCentroid = getVisibleCentroidForSide(engine, myDiv.side, eDiv);
            float d = MiscDistance(myDiv.centroid, visCentroid);
            if (d < minDist) minDist = d;
        }
        return minDist;
    }

    private static int countUsableStrikeAmmo(ShipAPI ship) {
        int total = 0;
        List<WeaponAPI> weapons = ship.getAllWeapons();
        if (weapons == null) return 0;
        for (WeaponAPI w : weapons) {
            if (w == null || w.isDisabled() || w.isPermanentlyDisabled()) continue;
            boolean isStrikeMissile = (w.getType() == WeaponType.MISSILE) && w.hasAIHint(AIHints.STRIKE);
            if (isStrikeMissile) {
                total += w.usesAmmo() ? w.getAmmo() : 4;
            }
        }
        return total;
    }

    private static float MiscDistance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
