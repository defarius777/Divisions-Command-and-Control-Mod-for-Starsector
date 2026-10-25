package divisionai;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CollisionGridAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
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
            } else {
                enemyFp += fp * readiness;
            }
        }

        div.localFriendlyStrength = Math.max(friendlyFp, div.totalFpStrength * 0.5f);
        div.localEnemyStrength = enemyFp;
        div.localOvermatchRatio = (enemyFp <= 1f) ? 3.0f : (div.localFriendlyStrength / enemyFp);
    }

    /**
     * Evaluates Battlegroup strategy, phase transitions, Main Effort, and Reserve designations.
     */
    public static void evaluateBattlegroupStrategy(
            CombatEngineAPI engine,
            BattlegroupState myBg,
            BattlegroupState enemyBg,
            float now) {

        myBg.totalEnemyStrength = enemyBg.totalFriendlyStrength;
        myBg.forceRatio = (enemyBg.totalFriendlyStrength <= 1f)
                ? 2.5f
                : (myBg.totalFriendlyStrength / enemyBg.totalFriendlyStrength);

        int activeDivCount = 0;
        DivisionState endangeredDiv = null;
        float worstOvermatch = Float.MAX_VALUE;
        DivisionState strongestLineDiv = null;
        float strongestLineFp = -1f;
        DivisionState bestReserveCandidate = null;
        float bestReserveScore = -1f;

        for (DivisionState div : myBg.divisions) {
            if (!div.isCombatEffective()) {
                div.isDesignatedMainEffort = false;
                div.isDesignatedReserve = false;
                continue;
            }
            activeDivCount++;

            if (div.localEnemyStrength > 8f && div.localOvermatchRatio < worstOvermatch) {
                worstOvermatch = div.localOvermatchRatio;
                endangeredDiv = div;
            }

            float mainScore = div.totalFpStrength * (1.2f - 0.5f * div.avgFluxLevel);
            if (div.archetype == DivisionArchetype.BATTLE_DIVISION) {
                mainScore *= 1.65f;
            } else if (div.archetype == DivisionArchetype.CRUISER_DIVISION
                    || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION) {
                mainScore *= 1.35f;
            }
            if (mainScore > strongestLineFp) {
                strongestLineFp = mainScore;
                strongestLineDiv = div;
            }
        }

        // Second pass: pick a mobile reserve candidate (never pick Main Effort, Battle Divisions, or Carrier groups)
        for (DivisionState div : myBg.divisions) {
            if (!div.isCombatEffective()) continue;
            if (div == strongestLineDiv
                    || div.archetype == DivisionArchetype.BATTLE_DIVISION
                    || div.archetype == DivisionArchetype.CARRIER_AND_ESCORT) {
                continue;
            }
            float distToEnemy = distanceToNearestEnemyDivision(div, enemyBg);
            float reserveScore = (distToEnemy * 0.001f) + (div.isFastFlanker ? 1.5f : 0.8f) - div.avgFluxLevel;
            if (reserveScore > bestReserveScore) {
                bestReserveScore = reserveScore;
                bestReserveCandidate = div;
            }
        }

        myBg.mainEffortDivision = strongestLineDiv;
        myBg.primaryThreatEnemyDivision = pickPrimaryEnemyTargetDivision(myBg, enemyBg);

        // Determine operational BattlePhase
        BattlePhase nextPhase;
        float minContactDist = minDistanceBetweenFleets(myBg, enemyBg);
        List<BattleObjectiveAPI> objectives = engine.getObjectives();
        boolean hasUncapturedObjectives = hasContestObjectives(objectives, myBg.side);

        if (myBg.missionContext == MissionContextType.FLEET_ESCAPE_REARGUARD
                || (myBg.forceRatio < 0.58f && myBg.missionContext != MissionContextType.FIGHT_TO_THE_LAST)) {
            nextPhase = BattlePhase.ORDERED_WITHDRAWAL;
        } else if (endangeredDiv != null && (worstOvermatch < 0.72f || (endangeredDiv.hasShipNeedingHelp && worstOvermatch < 0.85f))) {
            nextPhase = BattlePhase.STABILIZE_AND_RELIEVE;
        } else if (minContactDist > 3300f && hasUncapturedObjectives && now < 55f) {
            nextPhase = BattlePhase.OPENING_PROBE;
        } else if (myBg.forceRatio >= 1.35f && minContactDist < 3000f) {
            nextPhase = BattlePhase.EXPLOIT_BREAKTHROUGH;
        } else if (hasTorpedoOrStrikeDivisionReady(myBg) && minContactDist < 3400f) {
            nextPhase = BattlePhase.COORDINATED_STRIKE;
        } else {
            nextPhase = BattlePhase.LINE_CONTACT;
        }

        myBg.previousPhase = myBg.currentPhase;
        myBg.currentPhase = nextPhase;

        // Reserve management: keep 1 uncommitted reserve division during Opening Probe / Line Contact
        // if we have >= 3 divisions and cautious/steady commander, then commit it on Coordinated Strike / Stabilize!
        boolean holdReserve = (activeDivCount >= 3)
                && (nextPhase == BattlePhase.OPENING_PROBE || nextPhase == BattlePhase.LINE_CONTACT)
                && (myBg.bgCommander.aggressionBias <= 1.20f);

        for (DivisionState d : myBg.divisions) {
            d.isDesignatedMainEffort = (d == myBg.mainEffortDivision);
            d.isDesignatedReserve = holdReserve && (d == bestReserveCandidate);
        }
        myBg.reserveDivision = holdReserve ? bestReserveCandidate : null;

        // Log Battlegroup Commander operational directive when phase or primary focus shifts
        String directive = buildBattlegroupDirectiveSummary(myBg, endangeredDiv);
        DivisionCombatLog.logBattlegroupDirective(engine, myBg, directive, now);
    }

    private static String buildBattlegroupDirectiveSummary(BattlegroupState bg, DivisionState endangeredDiv) {
        String mainName = bg.mainEffortDivision != null ? "\"" + bg.mainEffortDivision.displayName + "\"" : "main line";
        String targetName = bg.primaryThreatEnemyDivision != null ? "\"" + bg.primaryThreatEnemyDivision.displayName + "\"" : "enemy line";

        switch (bg.currentPhase) {
            case OPENING_PROBE:
                if (bg.reserveDivision != null) {
                    return String.format("Advance %s while holding \"%s\" in reserve.", mainName, bg.reserveDivision.displayName);
                }
                return String.format("Advance %s to contest objectives and establish contact.", mainName);
            case COORDINATED_STRIKE:
                return String.format("Fix with %s and cue strike divisions against %s.", mainName, targetName);
            case STABILIZE_AND_RELIEVE:
                if (endangeredDiv != null) {
                    return String.format("Relieve \"%s\" (local ratio %.2f) and tighten division screens.",
                            endangeredDiv.displayName, endangeredDiv.localOvermatchRatio);
                }
                return "Stabilize local overmatch and rotate high-flux divisions.";
            case EXPLOIT_BREAKTHROUGH:
                return String.format("Press force advantage (%.2fx) against %s.", bg.forceRatio, targetName);
            case ORDERED_WITHDRAWAL:
                return "Execute phased leapfrog withdrawal toward reserve anchor.";
            case LINE_CONTACT:
            default:
                return String.format("Anchor battle line on %s against %s.", mainName, targetName);
        }
    }

    private static DivisionState pickPrimaryEnemyTargetDivision(BattlegroupState myBg, BattlegroupState enemyBg) {
        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;
        for (DivisionState eDiv : enemyBg.divisions) {
            if (!eDiv.isCombatEffective()) continue;
            float dist = MiscDistance(myBg.fleetCentroid, eDiv.centroid);
            // Prefer enemy divisions that are close, isolated, or high-value
            float isolationBonus = (eDiv.cohesionRatio > 1.3f) ? 350f : 0f;
            float score = eDiv.totalFpStrength * 12f + isolationBonus - dist * 0.25f;
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

    private static float minDistanceBetweenFleets(BattlegroupState a, BattlegroupState b) {
        float minDist = Float.MAX_VALUE;
        for (DivisionState da : a.divisions) {
            if (!da.isCombatEffective()) continue;
            float d = distanceToNearestEnemyDivision(da, b);
            if (d < minDist) minDist = d;
        }
        return minDist;
    }

    public static float distanceToNearestEnemyDivision(DivisionState myDiv, BattlegroupState enemyBg) {
        float minDist = Float.MAX_VALUE;
        for (DivisionState eDiv : enemyBg.divisions) {
            if (!eDiv.isCombatEffective()) continue;
            float d = MiscDistance(myDiv.centroid, eDiv.centroid);
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
            boolean isStrikeMissile = (w.getType() == WeaponType.MISSILE)
                    && (w.hasAIHint(AIHints.STRIKE) || w.hasAIHint(AIHints.DANGEROUS) || w.usesAmmo());
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
