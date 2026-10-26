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
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionModel.MissionContextType;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.OtcPlay;
import org.lwjgl.util.vector.Vector2f;

import java.util.Iterator;
import java.util.List;

/**
 * Force-level OTC AI:
 *  1. Updates division telemetry (centroid, spread/cohesion, flux/hull pressure, strike ammo,
 *     and local overmatch via CombatEngineAPI's spatial grid iterator).
 *  2. Evaluates battle-wide force ratios, endangered friendly divisions, and high-value enemy
 *     divisions.
 *  3. Chooses the Battle Group operational phase, designates Main Effort and Reserve divisions,
 *     and issues battle-wide directives logged to the player's combat feed.
 */
public class OtcAI {

    private static final float LOCAL_EVAL_DIAMETER = 4600f; // 2300u radius around division centroid
    private static final float MIN_PHASE_DWELL = 10f;

    public static void updateTelemetry(CombatEngineAPI engine, BattleGroupState battleGroup) {
        float fleetWeightedX = 0f;
        float fleetWeightedY = 0f;
        float totalFriendlyFp = 0f;

        for (DivisionState div : battleGroup.divisions) {
            List<ShipAPI> alive = div.getAliveMembers();
            if (alive.isEmpty()) {
                div.localFriendlyStrength = 0f;
                div.localEnemyStrength = 0f;
                div.localForceRatio = 1f;
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

            // Compute RMS spread radius around division centroid and refresh stationKeepingRadius for current alive count
            div.stationKeepingRadius = DivisionAutoFormer.computeStationKeepingRadius(
                    div.archetype,
                    alive.size(),
                    div.stationSpacing,
                    div.stationKeepingMult,
                    div.commander.stationKeepingStrictness);
            float distSqSum = 0f;
            for (ShipAPI s : alive) {
                float dx = s.getLocation().x - div.centroid.x;
                float dy = s.getLocation().y - div.centroid.y;
                distSqSum += (dx * dx + dy * dy);
            }
            div.spreadRadius = (float) Math.sqrt(distSqSum / alive.size());
            div.stationKeepingRatio = div.spreadRadius / Math.max(300f, div.stationKeepingRadius);

            // Spatial-grid local overmatch scan around division centroid (never O(N^2))
            evaluateLocalForceRatioViaGrid(engine, div);

            fleetWeightedX += div.centroid.x * weightSum;
            fleetWeightedY += div.centroid.y * weightSum;
            totalFriendlyFp += weightSum;
        }

        battleGroup.totalFriendlyStrength = totalFriendlyFp;
        if (totalFriendlyFp > 0f) {
            battleGroup.fleetCentroid.set(fleetWeightedX / totalFriendlyFp, fleetWeightedY / totalFriendlyFp);
        }

        // Reserve station sits 1800u behind the fleet centroid toward friendly map edge
        float retreatDirY = (battleGroup.side == 0) ? -1f : 1f;
        battleGroup.reserveStation.set(battleGroup.fleetCentroid.x, battleGroup.fleetCentroid.y + retreatDirY * 1800f);
    }

    private static void evaluateLocalForceRatioViaGrid(CombatEngineAPI engine, DivisionState div) {
        CollisionGridAPI grid = engine.getAiGridShips();
        if (grid == null) {
            grid = engine.getShipGrid();
        }
        if (grid == null) {
            div.localFriendlyStrength = div.totalFpStrength;
            div.localEnemyStrength = 0f;
            div.localForceRatio = 2.0f;
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
        div.localForceRatio = (enemyFp <= 1f) ? 3.0f : (div.localFriendlyStrength / enemyFp);
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
     * Evaluates Battle Group strategy, phase transitions, Main Effort, and Reserve designations.
     */
    public static void evaluateOtcStrategy(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            float now) {

        float visibleEnemyFp = 0f;
        for (DivisionState eDiv : enemyGroup.divisions) {
            visibleEnemyFp += getVisibleFpForSide(engine, myGroup.side, eDiv);
        }
        myGroup.totalEnemyStrength = visibleEnemyFp;
        // Under Fog of War, do not assume a single spotted 8 FP scout represents the entire enemy fleet!
        // Use the larger of visibleEnemyFp or 75% of friendly strength (capped by actual deployed enemy strength)
        // when estimating battle-wide force ratio so spotting 1 scout doesn't report a 27x advantage.
        float estimatedEnemyFleetFp = Math.max(
                visibleEnemyFp,
                Math.min(enemyGroup.totalFriendlyStrength, myGroup.totalFriendlyStrength * 0.85f));
        myGroup.forceRatio = (estimatedEnemyFleetFp <= 1f)
                ? 1.25f
                : (myGroup.totalFriendlyStrength / estimatedEnemyFleetFp);

        int activeDivCount = 0;
        DivisionState endangeredDiv = null;
        float worstLocalRatio = Float.MAX_VALUE;
        DivisionState strongestLineDiv = null;
        float strongestLineFp = -1f;
        DivisionState bestReserveCandidate = null;
        float bestReserveScore = -1f;

        float significantFp = Math.max(10f, myGroup.totalFriendlyStrength * 0.12f);
        DivisionState minorEndangeredDiv = null;
        float worstMinorLocalRatio = Float.MAX_VALUE;
        for (DivisionState div : myGroup.divisions) {
            if (!div.isCombatEffective()) {
                div.isDesignatedMainEffort = false;
                div.isDesignatedReserve = false;
                continue;
            }
            activeDivCount++;

            // Only divisions that matter to the battle can flip the whole battlegroup into Stabilize & Relieve;
            // a lone frigate or destroyer in trouble is handled by local mutual support instead.
            boolean significant = div.totalFpStrength >= significantFp
                    || div.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                    || div.archetype.isCarrierArchetype();
            if (significant && div.localEnemyStrength > 8f && div.localForceRatio < worstLocalRatio) {
                worstLocalRatio = div.localForceRatio;
                endangeredDiv = div;
            }
            if (div.localEnemyStrength > 8f && div.localForceRatio < 0.60f && div.localForceRatio < worstMinorLocalRatio) {
                worstMinorLocalRatio = div.localForceRatio;
                minorEndangeredDiv = div;
            }

            float mainScore = div.totalFpStrength * (1.2f - 0.5f * div.avgFluxLevel);
            if (div.archetype == DivisionArchetype.BATTLESHIP_DIVISION) {
                mainScore = mainScore * 2.05f + 25f;
            } else if (div.archetype == DivisionArchetype.CRUISER_DIVISION
                    || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION) {
                mainScore *= 1.35f;
            } else if (div.archetype.isCarrierArchetype()) {
                mainScore *= 0.55f;
            }
            // The Main Effort anchors the line, so prefer divisions whose mission keeps them there
            DivisionMissionType mission = DivisionCommanderAI.resolveActiveMission(div, now);
            if (mission == DivisionMissionType.ENVELOPMENT
                    || mission == DivisionMissionType.SCREEN
                    || mission == DivisionMissionType.RESERVE) {
                mainScore *= 0.6f;
            }
            if (div == myGroup.mainEffortDivision) {
                mainScore *= 1.15f;
            }
            if (mainScore > strongestLineFp) {
                strongestLineFp = mainScore;
                strongestLineDiv = div;
            }
        }

        // Second pass: pick a mobile reserve candidate (never pick Main Effort, Battle Divisions, Cruiser lines, or Carrier groups)
        for (DivisionState div : myGroup.divisions) {
            if (!div.isCombatEffective()) continue;
            if (div == strongestLineDiv
                    || div.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                    || div.archetype == DivisionArchetype.CRUISER_DIVISION
                    || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION
                    || div.archetype.isCarrierArchetype()) {
                continue;
            }
            float distToEnemy = distanceToNearestEnemyDivision(engine, div, enemyGroup);
            float distFactor = (distToEnemy < Float.MAX_VALUE * 0.5f) ? (distToEnemy * 0.001f) : 2.0f;
            float reserveScore = distFactor + (div.isFastFlanker ? 1.5f : 0.8f) - div.avgFluxLevel;
            if (DivisionCommanderAI.resolveActiveMission(div, now) == DivisionMissionType.RESERVE) {
                reserveScore += 5f;
            }
            if (div == myGroup.reserveDivision) {
                reserveScore += 1.0f; // sticky: don't swap the reserve between two similar divisions
            }
            if (reserveScore > bestReserveScore) {
                bestReserveScore = reserveScore;
                bestReserveCandidate = div;
            }
        }

        myGroup.mainEffortDivision = strongestLineDiv;
        myGroup.primaryThreatEnemyDivision = pickPrimaryEnemyTargetDivision(engine, myGroup, enemyGroup);

        // Determine operational BattlePhase (strictly respecting Fog of War: if no enemy is spotted yet, stay in APPROACH)
        float minContactDist = minDistanceBetweenFleets(engine, myGroup, enemyGroup);
        List<BattleObjectiveAPI> objectives = engine.getObjectives();
        boolean hasUncapturedObjectives = hasContestObjectives(objectives, myGroup.side);
        boolean anyEnemyVisible = (myGroup.primaryThreatEnemyDivision != null);
        BattlePhase current = myGroup.currentPhase;

        // Hysteresis: entering a phase needs a clearer signal than staying in it, so the battlegroup does not
        // flap between Stabilize and Breakthrough every few seconds.
        boolean crisis = endangeredDiv != null
                && (worstLocalRatio < 0.72f || (endangeredDiv.hasShipNeedingHelp && worstLocalRatio < 0.85f));
        boolean crisisPersists = current == BattlePhase.REINFORCE_THE_LINE
                && endangeredDiv != null && worstLocalRatio < 0.90f;
        boolean generalChase = myGroup.forceRatio >= 1.35f && visibleEnemyFp >= 30f && minContactDist < 3000f;
        boolean generalChasePersists = current == BattlePhase.GENERAL_CHASE
                && myGroup.forceRatio >= 1.20f && visibleEnemyFp >= 15f && minContactDist < 3600f;
        boolean strikeRunning = myGroup.strikePlan != null && myGroup.isPlayEnabled(OtcPlay.TIME_ON_TARGET_STRIKES);

        BattlePhase nextPhase;
        if (myGroup.missionContext == MissionContextType.REARGUARD_ACTION
                || (anyEnemyVisible && myGroup.forceRatio < 0.58f && myGroup.missionContext != MissionContextType.FIGHT_TO_THE_LAST)) {
            nextPhase = BattlePhase.FIGHTING_RETIREMENT;
        } else if (crisis || crisisPersists) {
            nextPhase = BattlePhase.REINFORCE_THE_LINE;
        } else if ((!anyEnemyVisible && now < 90f) || (minContactDist > 3300f && hasUncapturedObjectives && now < 75f)) {
            nextPhase = BattlePhase.APPROACH;
        } else if (generalChase || generalChasePersists) {
            nextPhase = BattlePhase.GENERAL_CHASE;
        } else if (strikeRunning || (hasMissileOrStrikeDivisionReady(myGroup) && minContactDist < 3400f)) {
            nextPhase = BattlePhase.COORDINATED_STRIKE;
        } else {
            nextPhase = BattlePhase.GENERAL_ENGAGEMENT;
        }

        // Minimum dwell: a phase holds for MIN_PHASE_DWELL seconds unless withdrawal is ordered or a severe crisis erupts
        boolean dwellElapsed = (now - myGroup.phaseEnteredTime) >= MIN_PHASE_DWELL;
        boolean severeCrisis = endangeredDiv != null && worstLocalRatio < 0.45f;
        if (nextPhase != current && !dwellElapsed
                && nextPhase != BattlePhase.FIGHTING_RETIREMENT
                && !(nextPhase == BattlePhase.REINFORCE_THE_LINE && severeCrisis)) {
            nextPhase = current;
        }

        myGroup.previousPhase = current;
        myGroup.currentPhase = nextPhase;
        if (nextPhase != current) {
            myGroup.phaseEnteredTime = now;
        }

        // Reserve management. With the Reserve & Exploit play the reserve is held until the OTC commits it
        // (relief, exploitation) or 150s pass; otherwise only during the opening 90s.
        boolean reservePlay = myGroup.isPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE) && myGroup.authority.allowsRetasking();
        boolean holdReserve;
        if (reservePlay) {
            holdReserve = !myGroup.reserveCommitted
                    && (now < 150f)
                    && (activeDivCount >= 3)
                    && nextPhase != BattlePhase.FIGHTING_RETIREMENT
                    && (myGroup.otc.aggressionBias <= 1.35f);
        } else {
            holdReserve = (now < 90f)
                    && (activeDivCount >= 3)
                    && (nextPhase == BattlePhase.APPROACH || nextPhase == BattlePhase.GENERAL_ENGAGEMENT)
                    && (myGroup.otc.aggressionBias <= 1.20f);
        }

        for (DivisionState d : myGroup.divisions) {
            d.isDesignatedMainEffort = (d == myGroup.mainEffortDivision);
            d.isDesignatedReserve = holdReserve && (d == bestReserveCandidate);
        }
        myGroup.reserveDivision = holdReserve ? bestReserveCandidate : null;

        // Orchestrate: turn the battle plan into per-division taskings
        OtcBattlePlanner.planBattle(engine, myGroup, enemyGroup, endangeredDiv, minorEndangeredDiv, now);

        // Log OTC operational directive when phase or primary focus shifts
        String directive = buildOtcSignal(myGroup, endangeredDiv);
        DivisionCombatLog.logOtcSignal(engine, myGroup, directive, now);
    }

    private static String buildOtcSignal(BattleGroupState battleGroup, DivisionState endangeredDiv) {
        String mainName = battleGroup.mainEffortDivision != null ? "\"" + battleGroup.mainEffortDivision.displayName + "\"" : "the main body";
        String targetName = battleGroup.primaryThreatEnemyDivision != null ? "\"" + battleGroup.primaryThreatEnemyDivision.displayName + "\"" : "the enemy line";

        switch (battleGroup.currentPhase) {
            case APPROACH:
                if (battleGroup.reserveDivision != null) {
                    return String.format("Scouts ahead, %s leading; \"%s\" held in reserve.", mainName, battleGroup.reserveDivision.displayName);
                }
                return String.format("Scouts ahead, %s leading, to take objectives and gain contact.", mainName);
            case COORDINATED_STRIKE:
                if (battleGroup.strikePlan != null && battleGroup.strikePlan.target != null) {
                    return String.format("%s fixes; %d strike division%s on time-on-target against \"%s\".",
                            mainName,
                            battleGroup.strikePlan.participants.size(),
                            battleGroup.strikePlan.participants.size() == 1 ? "" : "s",
                            battleGroup.strikePlan.target.displayName);
                }
                return String.format("%s fixes; strike divisions stand by against %s.", mainName, targetName);
            case REINFORCE_THE_LINE:
                if (endangeredDiv != null) {
                    return String.format("Reinforce \"%s\" (local ratio %.2f); close up the screens.",
                            endangeredDiv.displayName, endangeredDiv.localForceRatio);
                }
                return "Reinforce the line and haul hot divisions out to vent.";
            case GENERAL_CHASE:
                return String.format("General chase: press the advantage (%.2fx) on %s.", battleGroup.forceRatio, targetName);
            case FIGHTING_RETIREMENT:
                return "Retire by divisions toward the rendezvous, covering each other.";
            case GENERAL_ENGAGEMENT:
            default:
                return String.format("Line of battle formed on %s, engaging %s.", mainName, targetName);
        }
    }

    private static DivisionState pickPrimaryEnemyTargetDivision(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup) {
        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;
        for (DivisionState eDiv : enemyGroup.divisions) {
            if (!eDiv.isCombatEffective()) continue;
            if (!isDivisionVisibleToSide(engine, myGroup.side, eDiv)) continue;
            Vector2f visCentroid = getVisibleCentroidForSide(engine, myGroup.side, eDiv);
            float visFp = getVisibleFpForSide(engine, myGroup.side, eDiv);
            float dist = MiscDistance(myGroup.fleetCentroid, visCentroid);
            // Prefer enemy divisions that are close, isolated, or high-value
            float isolationBonus = (eDiv.stationKeepingRatio > 1.3f) ? 350f : 0f;
            float auxPenalty = (eDiv.archetype == DivisionArchetype.SERVICE_GROUP) ? -500f : 0f;
            float score = visFp * 12f + isolationBonus + auxPenalty - dist * 0.25f;
            if (score > bestScore) {
                bestScore = score;
                best = eDiv;
            }
        }
        return best;
    }

    private static boolean hasMissileOrStrikeDivisionReady(BattleGroupState battleGroup) {
        for (DivisionState d : battleGroup.divisions) {
            if (d.isCombatEffective() && d.hasMissileStrikeCapability && d.avgFluxLevel < 0.60f && d.usableStrikeAmmoCount > 0) {
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

    private static float minDistanceBetweenFleets(CombatEngineAPI engine, BattleGroupState a, BattleGroupState b) {
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
            BattleGroupState enemyGroup) {
        float minDist = Float.MAX_VALUE;
        for (DivisionState eDiv : enemyGroup.divisions) {
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
