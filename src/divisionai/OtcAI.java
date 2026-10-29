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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        if (engine != null) {
            BattlefieldFirepowerEvaluator.updateBattlefieldRoses(engine, engine.getTotalElapsedTime(false));
        }
        float fleetWeightedX = 0f;
        float fleetWeightedY = 0f;
        float totalFriendlyFp = 0f;

        for (DivisionState div : battleGroup.divisions) {
            List<ShipAPI> alive = div.getAliveMembers();
            div.detachedStragglerIds.clear();
            if (alive.isEmpty()) {
                div.coreMemberCount = 0;
                div.ableMemberCount = 0;
                div.retreatingMemberCount = 0;
                div.retreatingFraction = 0f;
                div.isUnableDivision = true;
                div.coreFpRatio = 1f;
                div.localFriendlyStrength = 0f;
                div.localEnemyStrength = 0f;
                div.localForceRatio = 1f;
                div.incomingAlphaThreat = 0f;
                div.incomingShortDotThreat = 0f;
                div.incomingSustainedDotThreat = 0f;
                div.incomingPdThreat = 0f;
                div.crossfireCordonIndex = 0f;
                div.smoothedDangerScore = 0f;
                continue;
            }

            int ableCount = 0;
            int retreatingCount = 0;
            float ableWeightSum = 0f;
            boolean[] shipUnable = new boolean[alive.size()];
            for (int i = 0; i < alive.size(); i++) {
                ShipAPI s = alive.get(i);
                boolean unable = DivisionState.isShipUnable(s);
                shipUnable[i] = unable;
                if (unable) {
                    retreatingCount++;
                } else {
                    ableCount++;
                    ableWeightSum += ShipCapabilityProfile.resolveShipFpStrength(s);
                }
            }
            div.ableMemberCount = ableCount;
            div.retreatingMemberCount = retreatingCount;
            div.retreatingFraction = (float) retreatingCount / Math.max(1, alive.size());
            div.isUnableDivision = (ableCount == 0 || div.retreatingFraction >= 0.65f);

            div.stationKeepingRadius = DivisionAutoFormer.computeStationKeepingRadius(
                    div.archetype,
                    Math.max(1, ableCount > 0 ? ableCount : alive.size()),
                    div.stationSpacing,
                    div.stationKeepingMult,
                    div.commander.stationKeepingStrictness);

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

            float[] weights = new float[alive.size()];
            for (int i = 0; i < alive.size(); i++) {
                ShipAPI s = alive.get(i);
                float baseW = ShipCapabilityProfile.resolveShipFpStrength(s);
                // Discount retreating ("unable") ships when able ships remain so fleeing ships do not drag the division centroid
                float w = (shipUnable[i] && ableCount > 0) ? (baseW * 0.12f) : baseW;
                weights[i] = w;
                if (shipUnable[i] && ableCount > 0) {
                    div.detachedStragglerIds.add(s.getId());
                }
                sumX += s.getLocation().x * w;
                sumY += s.getLocation().y * w;
                sumVx += s.getVelocity().x * w;
                sumVy += s.getVelocity().y * w;
                weightSum += w;

                float fl = s.getFluxLevel();
                fluxSum += fl;
                if (!shipUnable[i] && fl > maxFlux) maxFlux = fl;
                hullSum += s.getHullLevel();

                if (!shipUnable[i]) {
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
            }

            float rawCenterX = sumX / Math.max(1f, weightSum);
            float rawCenterY = sumY / Math.max(1f, weightSum);
            float rawDistSqSum = 0f;
            for (ShipAPI s : alive) {
                float dx = s.getLocation().x - rawCenterX;
                float dy = s.getLocation().y - rawCenterY;
                rawDistSqSum += (dx * dx + dy * dy);
            }
            div.rawAllMemberSpreadRadius = (float) Math.sqrt(rawDistSqSum / alive.size());

            // Identify Core Cluster vs. Detached Stragglers so 1-2 cut-off ships on the other side of the
            // enemy battle line never drag div.centroid into the enemy line or freeze a formed core in CLOSE_UP.
            float coreSumX = sumX;
            float coreSumY = sumY;
            float coreSumVx = sumVx;
            float coreSumVy = sumVy;
            float coreWeightSum = weightSum;
            int coreCount = alive.size();

            if (alive.size() >= 2) {
                float neighborRadius = Math.max(820f, div.stationKeepingRadius * 1.45f);
                float neighborRadiusSq = neighborRadius * neighborRadius;

                int anchorIdx = 0;
                float bestClusterScore = -1f;
                for (int i = 0; i < alive.size(); i++) {
                    ShipAPI si = alive.get(i);
                    float score = weights[i] * (si == div.flagship ? 1.30f : 1.0f);
                    for (int j = 0; j < alive.size(); j++) {
                        if (i == j) continue;
                        ShipAPI sj = alive.get(j);
                        float dx = si.getLocation().x - sj.getLocation().x;
                        float dy = si.getLocation().y - sj.getLocation().y;
                        if ((dx * dx + dy * dy) <= neighborRadiusSq) {
                            score += weights[j];
                        }
                    }
                    if (score > bestClusterScore) {
                        bestClusterScore = score;
                        anchorIdx = i;
                    }
                }

                // Single-linkage expansion from anchorIdx within neighborRadius (captures cohesive line-abreast formations)
                boolean[] inCore = new boolean[alive.size()];
                inCore[anchorIdx] = true;
                boolean addedAny = true;
                while (addedAny) {
                    addedAny = false;
                    for (int i = 0; i < alive.size(); i++) {
                        if (inCore[i]) continue;
                        ShipAPI si = alive.get(i);
                        for (int j = 0; j < alive.size(); j++) {
                            if (!inCore[j]) continue;
                            ShipAPI sj = alive.get(j);
                            float dx = si.getLocation().x - sj.getLocation().x;
                            float dy = si.getLocation().y - sj.getLocation().y;
                            if ((dx * dx + dy * dy) <= neighborRadiusSq) {
                                inCore[i] = true;
                                addedAny = true;
                                break;
                            }
                        }
                    }
                }

                // Also include any ship within outlierCutoff of the preliminary core centroid
                float prelimX = 0f;
                float prelimY = 0f;
                float prelimW = 0f;
                for (int i = 0; i < alive.size(); i++) {
                    if (inCore[i]) {
                        prelimX += alive.get(i).getLocation().x * weights[i];
                        prelimY += alive.get(i).getLocation().y * weights[i];
                        prelimW += weights[i];
                    }
                }
                if (prelimW > 0f) {
                    prelimX /= prelimW;
                    prelimY /= prelimW;
                    float outlierCutoff = Math.max(1050f, div.stationKeepingRadius * 1.68f);
                    float outlierCutoffSq = outlierCutoff * outlierCutoff;
                    for (int i = 0; i < alive.size(); i++) {
                        if (!inCore[i]) {
                            float dx = alive.get(i).getLocation().x - prelimX;
                            float dy = alive.get(i).getLocation().y - prelimY;
                            if ((dx * dx + dy * dy) <= outlierCutoffSq) {
                                inCore[i] = true;
                            }
                        }
                    }
                }

                int candidateCoreCount = 0;
                float candidateCoreWeight = 0f;
                for (int i = 0; i < alive.size(); i++) {
                    if (inCore[i]) {
                        candidateCoreCount++;
                        candidateCoreWeight += weights[i];
                    }
                }

                boolean validMultiShipCore = alive.size() >= 3
                        && candidateCoreCount >= 2
                        && candidateCoreCount < alive.size()
                        && (candidateCoreWeight >= weightSum * 0.50f || candidateCoreCount * 2 >= alive.size());
                boolean validSingleShipCore = alive.size() >= 2
                        && candidateCoreCount == 1
                        && div.rawAllMemberSpreadRadius > Math.max(950f, div.stationKeepingRadius * 1.35f);

                if (validMultiShipCore || validSingleShipCore) {
                    coreSumX = 0f;
                    coreSumY = 0f;
                    coreSumVx = 0f;
                    coreSumVy = 0f;
                    coreWeightSum = 0f;
                    coreCount = 0;
                    for (int i = 0; i < alive.size(); i++) {
                        ShipAPI s = alive.get(i);
                        if (inCore[i]) {
                            float w = weights[i];
                            coreSumX += s.getLocation().x * w;
                            coreSumY += s.getLocation().y * w;
                            coreSumVx += s.getVelocity().x * w;
                            coreSumVy += s.getVelocity().y * w;
                            coreWeightSum += w;
                            coreCount++;
                        } else {
                            div.detachedStragglerIds.add(s.getId());
                        }
                    }
                }
            }

            div.coreMemberCount = coreCount;
            div.coreFpRatio = coreWeightSum / Math.max(1f, weightSum);
            div.centroid.set(coreSumX / Math.max(1f, coreWeightSum), coreSumY / Math.max(1f, coreWeightSum));
            div.velocity.set(coreSumVx / Math.max(1f, coreWeightSum), coreSumVy / Math.max(1f, coreWeightSum));
            div.avgFluxLevel = fluxSum / alive.size();
            div.maxMemberFluxLevel = maxFlux;
            div.avgHullLevel = hullSum / alive.size();
            div.hasShipNeedingHelp = needsHelp;
            div.shipNeedingHelp = helpShip;
            div.usableStrikeAmmoCount = strikeAmmo;
            div.totalFpStrength = (ableCount > 0) ? Math.max(1f, ableWeightSum) : Math.max(1f, weightSum * 0.25f);

            // Compute RMS spread radius around the Core Cluster (excluding detached stragglers)
            float coreDistSqSum = 0f;
            int spreadSamples = 0;
            for (ShipAPI s : alive) {
                if (div.detachedStragglerIds.contains(s.getId())) continue;
                float dx = s.getLocation().x - div.centroid.x;
                float dy = s.getLocation().y - div.centroid.y;
                coreDistSqSum += (dx * dx + dy * dy);
                spreadSamples++;
            }
            div.spreadRadius = (spreadSamples > 0)
                    ? (float) Math.sqrt(coreDistSqSum / spreadSamples)
                    : div.rawAllMemberSpreadRadius;
            div.stationKeepingRatio = div.spreadRadius / Math.max(300f, div.stationKeepingRadius);

            // Spatial-grid local overmatch scan around division centroid (never O(N^2))
            evaluateLocalForceRatioViaGrid(engine, div);

            // Weapon-arc firepower-space & L/U crossfire cordon evaluation at division centroid
            BattlefieldFirepowerEvaluator.updateDivisionFirepowerTelemetry(engine, div);
            BattlefieldFirepowerEvaluator.updateDivisionBaseTimeSpace(div);
            if (div.crossfireCordonIndex >= 0.32f && div.localEnemyStrength > 1f) {
                // Weight effective local enemy pressure by L/U crossfire cordon amplification
                float cordonMult = 1.0f + (div.crossfireCordonIndex - 0.20f) * 0.55f;
                div.localEnemyStrength *= cordonMult;
                div.localForceRatio = div.localFriendlyStrength / Math.max(1f, div.localEnemyStrength);
            }

            if (!div.isUnableDivision) {
                fleetWeightedX += div.centroid.x * div.totalFpStrength;
                fleetWeightedY += div.centroid.y * div.totalFpStrength;
                totalFriendlyFp += div.totalFpStrength;
            }
        }

        float frontlineWeightedX = 0f;
        float frontlineWeightedY = 0f;
        float frontlineFp = 0f;
        for (DivisionState div : battleGroup.divisions) {
            if (!div.isAbleCombatEffective()) continue;
            if (div.currentIntent != DivisionModel.DivisionIntentType.HOLD_IN_RESERVE
                    && div.currentIntent != DivisionModel.DivisionIntentType.HAUL_OUT_TO_VENT
                    && div.currentIntent != DivisionModel.DivisionIntentType.BREAK_OFF_AND_RETIRE) {
                frontlineWeightedX += div.centroid.x * div.totalFpStrength;
                frontlineWeightedY += div.centroid.y * div.totalFpStrength;
                frontlineFp += div.totalFpStrength;
            }
        }

        battleGroup.totalFriendlyStrength = totalFriendlyFp;
        if (totalFriendlyFp > 0f) {
            battleGroup.fleetCentroid.set(fleetWeightedX / totalFriendlyFp, fleetWeightedY / totalFriendlyFp);
        }

        // Anchor reserveStation 680u behind the engaged frontline centroid (close tactical support distance).
        // Item D: When outnumbered / fighting retirement / in Fleet Protective Bastion, anchor the reserve station
        // closer to the friendly map edge so a balled-up fleet uses the map edge as a safe rear-blocking zone!
        float retreatDirY = (battleGroup.side == 0) ? -1f : 1f;
        float halfH = (engine != null && engine.getMapHeight() > 2000f) ? (engine.getMapHeight() * 0.5f) : 9000f;
        float maxEdgeY = halfH * 0.78f;
        boolean edgeBastionPosture = battleGroup.forceRatio < 0.82f
                || battleGroup.currentPhase == DivisionModel.BattlePhase.FIGHTING_RETIREMENT
                || battleGroup.activeFleetWideFormation == PermanentDivisionManager.FleetWideFormation.FLEET_PROTECTIVE_BASTION;
        if (frontlineFp > 0f) {
            float fx = frontlineWeightedX / frontlineFp;
            float fy = frontlineWeightedY / frontlineFp;
            float pullBackDist = edgeBastionPosture ? 920f : 680f;
            float targetResY = Math.max(-maxEdgeY, Math.min(maxEdgeY, fy + retreatDirY * pullBackDist));
            if (edgeBastionPosture) {
                float friendlyEdgeZoneY = retreatDirY * (halfH - 1450f);
                targetResY = targetResY * 0.65f + friendlyEdgeZoneY * 0.35f;
            }
            battleGroup.reserveStation.set(fx, targetResY);
        } else if (battleGroup.reserveStation.x == 0f && battleGroup.reserveStation.y == 0f) {
            battleGroup.reserveStation.set(battleGroup.fleetCentroid.x, battleGroup.fleetCentroid.y + retreatDirY * 680f);
        }
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
            if (DivisionState.isShipUnable(s)) continue;

            float dx = s.getLocation().x - div.centroid.x;
            float dy = s.getLocation().y - div.centroid.y;
            if ((dx * dx + dy * dy) > radiusSq) continue;

            float fp = ShipCapabilityProfile.resolveShipFpStrength(s);
            // Weight combat effectiveness by both FP readiness AND Effective HP (Shield HP + Armor HP + Hull HP)
            // so glass-cannon destroyers (e.g. Hammerheads) do not overestimate their local force ratio against heavy armor/shields!
            float readiness = Math.max(0.35f, s.getHullLevel() * (1.15f - 0.5f * s.getFluxLevel()));
            float ehpStrength = BattlefieldFirepowerEvaluator.computeShipEffectiveHp(s) / 1600f;
            float effectiveStrength = 0.42f * (fp * readiness) + 0.58f * ehpStrength;
            if (s.getOwner() == div.side) {
                friendlyFp += effectiveStrength;
            } else if (isShipVisibleToSide(engine, div.side, s)) {
                enemyFp += effectiveStrength;
            }
        }

        float selfEhpStrength = 0f;
        for (ShipAPI m : div.getAliveMembers()) {
            selfEhpStrength += BattlefieldFirepowerEvaluator.computeShipEffectiveHp(m) / 1600f;
        }
        float minSelfFloor = (0.42f * div.totalFpStrength + 0.58f * selfEhpStrength) * 0.5f;
        div.localFriendlyStrength = Math.max(friendlyFp, minSelfFloor);
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
            float w = ShipCapabilityProfile.resolveShipFpStrength(s) * (DivisionState.isShipUnable(s) ? 0.15f : 1.0f);
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
                float w = ShipCapabilityProfile.resolveShipFpStrength(s);
                fpSum += DivisionState.isShipUnable(s) ? (w * 0.20f) : w;
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

        BattlefieldFirepowerEvaluator.updateCrossGroupTimeSpaceConvergence(engine, myGroup, enemyGroup);
        if (enemyGroup != null) {
            BattlefieldFirepowerEvaluator.updateCrossGroupTimeSpaceConvergence(engine, enemyGroup, myGroup);
        }

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
            if (!div.isAbleCombatEffective()) {
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
            } else if (div.archetype == DivisionArchetype.CARRIER_GROUP) {
                mainScore *= 0.35f;
            } else if (div.archetype.isCarrierArchetype()) {
                mainScore *= 0.12f;
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

        // Second pass: pick a mobile reserve candidate (never pick Main Effort, Battle Divisions, Cruiser lines,
        // Carrier groups, or divisions explicitly tasked to ENVELOPMENT / SCREEN unless their mission is RESERVE)
        for (DivisionState div : myGroup.divisions) {
            if (!div.isAbleCombatEffective()) continue;
            if (div == strongestLineDiv
                    || div.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                    || div.archetype == DivisionArchetype.CRUISER_DIVISION
                    || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION
                    || div.archetype.isCarrierArchetype()) {
                continue;
            }
            DivisionMissionType divMission = DivisionCommanderAI.resolveActiveMission(div, now);
            if (divMission != DivisionMissionType.RESERVE) {
                // Do not hijack divisions explicitly assigned to Envelopment or Screen, or in small (<4 division) groups
                if (activeDivCount < 4
                        || divMission == DivisionMissionType.ENVELOPMENT
                        || divMission == DivisionMissionType.SCREEN) {
                    continue;
                }
            }
            float distToEnemy = distanceToNearestEnemyDivision(engine, div, enemyGroup);
            float distFactor = (distToEnemy < Float.MAX_VALUE * 0.5f) ? (distToEnemy * 0.001f) : 2.0f;
            // Time freedom bonus: unengaged divisions with high effectiveTimeRadius make the best mobile reserves
            float timeFreedomBonus = div.timeFreedomFactor * 0.85f + (div.effectiveTimeRadius / 2400f) * 0.45f;
            float reserveScore = distFactor + (div.isFastFlanker ? 1.5f : 0.8f) + timeFreedomBonus - div.avgFluxLevel;
            if (divMission == DivisionMissionType.RESERVE) {
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

        boolean pressuredForRetirement = endangeredDiv != null && worstLocalRatio < 0.55f && endangeredDiv.avgFluxLevel >= 0.35f;
        BattlePhase nextPhase;
        if (myGroup.missionContext == MissionContextType.REARGUARD_ACTION
                || (anyEnemyVisible && now > 40f && myGroup.forceRatio < 0.45f && pressuredForRetirement
                && myGroup.missionContext != MissionContextType.FIGHT_TO_THE_LAST)) {
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

        // Reserve management: hold a mobile reserve during approach or while enjoying a clear force advantage,
        // but commit the reserve immediately once the main battle line is in close contact against an equal/superior force
        // so we never fight 4v5 outnumbered while 20% of the fleet watches from the rear.
        boolean reservePlay = myGroup.isPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE) && myGroup.authority.allowsRetasking();
        boolean frontlineInCloseFight = minContactDist < 1850f && (myGroup.forceRatio <= 1.18f || worstLocalRatio < 0.98f);
        if (frontlineInCloseFight && myGroup.reserveDivision != null) {
            myGroup.reserveCommitted = true;
        }
        boolean holdReserve;
        if (reservePlay) {
            holdReserve = !myGroup.reserveCommitted
                    && !frontlineInCloseFight
                    && (now < 90f)
                    && (activeDivCount >= 4)
                    && nextPhase != BattlePhase.FIGHTING_RETIREMENT
                    && (myGroup.otc.aggressionBias <= 1.35f);
        } else {
            holdReserve = !frontlineInCloseFight
                    && (now < 45f)
                    && (activeDivCount >= 4)
                    && (nextPhase == BattlePhase.APPROACH)
                    && (myGroup.otc.aggressionBias <= 1.20f);
        }

        for (DivisionState d : myGroup.divisions) {
            d.isDesignatedMainEffort = (d == myGroup.mainEffortDivision);
            d.isDesignatedReserve = holdReserve && (d == bestReserveCandidate);
        }
        myGroup.reserveDivision = holdReserve ? bestReserveCandidate : null;

        // Orchestrate: compute multi-division / fleet-wide formation geometry, then turn the battle plan into per-division taskings
        updateFleetWideFormationGeometry(engine, myGroup, enemyGroup);
        OtcBattlePlanner.planBattle(engine, myGroup, enemyGroup, endangeredDiv, minorEndangeredDiv, now);

        // Log OTC operational directive when phase or primary focus shifts
        String directive = buildOtcSignal(myGroup, endangeredDiv);
        DivisionCombatLog.logOtcSignal(engine, myGroup, directive, now);
    }

    private static String buildOtcSignal(BattleGroupState battleGroup, DivisionState endangeredDiv) {
        String mainName = battleGroup.mainEffortDivision != null ? "\"" + battleGroup.mainEffortDivision.displayName + "\"" : "the main body";
        String targetName = battleGroup.primaryThreatEnemyDivision != null ? "\"" + battleGroup.primaryThreatEnemyDivision.displayName + "\"" : "the enemy line";
        String fwTag = (battleGroup.activeFleetWideFormation != null)
                ? " [" + battleGroup.activeFleetWideFormation.shortName + "]"
                : "";
        String spTag = (battleGroup.activeMultiDivisionSetpiece != null
                && battleGroup.activeMultiDivisionSetpiece != PermanentDivisionManager.MultiDivisionSetpiece.NONE)
                ? " {" + battleGroup.activeMultiDivisionSetpiece.shortName + "}"
                : "";

        switch (battleGroup.currentPhase) {
            case APPROACH:
                if (battleGroup.reserveDivision != null) {
                    return String.format("Scouts ahead%s, %s leading; \"%s\" held in reserve.", fwTag, mainName, battleGroup.reserveDivision.displayName);
                }
                return String.format("Scouts ahead%s, %s leading, to take objectives and gain contact.", fwTag, mainName);
            case COORDINATED_STRIKE:
                if (battleGroup.strikePlan != null && battleGroup.strikePlan.target != null) {
                    return String.format("%s fixes%s%s; %d strike division%s on time-on-target against \"%s\".",
                            mainName,
                            fwTag,
                            spTag,
                            battleGroup.strikePlan.participants.size(),
                            battleGroup.strikePlan.participants.size() == 1 ? "" : "s",
                            battleGroup.strikePlan.target.displayName);
                }
                return String.format("%s fixes%s%s; strike divisions stand by against %s.", mainName, fwTag, spTag, targetName);
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
                return String.format("Line of battle%s%s formed on %s, engaging %s.", fwTag, spTag, mainName, targetName);
        }
    }

    /**
     * Scope Items 3 & 4: Computes the Fleet-Wide (Multi-Division) Formation offsets for all active divisions
     * in the BattleGroup relative to the fleet advance axis, and supports initial deployment marshalling.
     */
    public static void updateFleetWideFormationGeometry(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup) {
        if (myGroup == null) return;
        List<DivisionState> active = new ArrayList<>();
        for (DivisionState d : myGroup.divisions) {
            if (d != null && d.isCombatEffective()) {
                active.add(d);
            }
        }
        if (active.isEmpty()) return;

        PermanentDivisionManager.FleetWideFormation configured = myGroup.fleetWideFormation != null
                ? myGroup.fleetWideFormation
                : PermanentDivisionManager.FleetWideFormation.DOCTRINAL_AUTO;
        PermanentDivisionManager.FleetWideFormation resolved = configured;
        if (configured == PermanentDivisionManager.FleetWideFormation.DOCTRINAL_AUTO) {
            boolean hasCarrier = false;
            int fastFlankers = 0;
            for (DivisionState d : active) {
                if (d.archetype != null && d.archetype.isCarrierArchetype()) hasCarrier = true;
                if (d.isFastFlanker) fastFlankers++;
            }
            if (hasCarrier && myGroup.forceRatio < 0.92f) {
                resolved = PermanentDivisionManager.FleetWideFormation.FLEET_PROTECTIVE_BASTION;
            } else if (myGroup.useConvergence && fastFlankers >= 2) {
                resolved = PermanentDivisionManager.FleetWideFormation.CRESCENT_HORNS;
            } else if (myGroup.forceRatio >= 1.28f && active.size() >= 3) {
                resolved = PermanentDivisionManager.FleetWideFormation.GRAND_WEDGE_SPEARHEAD;
            } else if (myGroup.forceRatio < 0.80f && active.size() >= 3) {
                resolved = PermanentDivisionManager.FleetWideFormation.REFUSED_FLANK_OBLIQUE;
            } else if (hasCarrier && active.size() >= 3) {
                resolved = PermanentDivisionManager.FleetWideFormation.CHECKERBOARD_QUINCUNX;
            } else {
                resolved = PermanentDivisionManager.FleetWideFormation.GRAND_LINE_ABREAST;
            }
        }
        myGroup.activeFleetWideFormation = resolved;

        // Determine fleet advance axis (toward primary visible enemy or default +Y/-Y battle axis)
        float axisX = 0f;
        float axisY = (myGroup.side == 0) ? 1f : -1f;
        if (myGroup.primaryThreatEnemyDivision != null
                && isDivisionVisibleToSide(engine, myGroup.side, myGroup.primaryThreatEnemyDivision)) {
            Vector2f ePos = getVisibleCentroidForSide(engine, myGroup.side, myGroup.primaryThreatEnemyDivision);
            float dx = ePos.x - myGroup.fleetCentroid.x;
            float dy = ePos.y - myGroup.fleetCentroid.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 50f) {
                axisX = dx / len;
                axisY = dy / len;
            }
        }
        float perpX = axisY;
        float perpY = -axisX;

        List<DivisionState> combatDivs = new ArrayList<>();
        List<DivisionState> supportDivs = new ArrayList<>();
        for (DivisionState d : active) {
            if (d.archetype != null && (d.archetype.isCarrierArchetype()
                    || d.archetype == DivisionArchetype.SERVICE_GROUP
                    || d.isDesignatedReserve)) {
                supportDivs.add(d);
            } else {
                combatDivs.add(d);
            }
        }
        // Put Main Effort first in combatDivs for spearhead/center indexing
        if (myGroup.mainEffortDivision != null && combatDivs.remove(myGroup.mainEffortDivision)) {
            combatDivs.add(0, myGroup.mainEffortDivision);
        }

        int nCombat = combatDivs.size();
        for (int i = 0; i < nCombat; i++) {
            DivisionState d = combatDivs.get(i);
            float rawInterval = Math.max(650f, d.divisionInterval);
            float interval = (nCombat >= 4)
                    ? Math.min(rawInterval * 0.72f, 2100f / nCombat)
                    : rawInterval;
            float fwd = 0f;
            float lat = 0f;
            switch (resolved) {
                case GRAND_COLUMN_IN_DEPTH: {
                    fwd = ((nCombat - 1) * 0.5f - i) * 650f;
                    lat = (i == 0) ? 0f : ((i % 2 == 1) ? -160f : 160f);
                    break;
                }
                case GRAND_WEDGE_SPEARHEAD: {
                    if (i == 0) {
                        fwd = 380f;
                        lat = 0f;
                    } else {
                        int wingRank = (i + 1) / 2;
                        float side = (i % 2 == 1) ? -1f : 1f;
                        fwd = 380f - wingRank * 440f;
                        lat = side * wingRank * interval * 0.88f;
                    }
                    break;
                }
                case REFUSED_FLANK_OBLIQUE: {
                    float centered = i - (nCombat - 1) * 0.5f;
                    lat = centered * interval * 0.85f;
                    fwd = 340f - i * 310f;
                    break;
                }
                case CRESCENT_HORNS: {
                    if (i == 0) {
                        fwd = -80f;
                        lat = 0f;
                    } else {
                        int wingRank = (i + 1) / 2;
                        float side = (i % 2 == 1) ? -1f : 1f;
                        fwd = -60f + Math.min(260f, wingRank * 110f);
                        lat = side * wingRank * interval * 0.82f;
                    }
                    break;
                }
                case CHECKERBOARD_QUINCUNX: {
                    int rank = i % 2;
                    float centered = i - (nCombat - 1) * 0.5f;
                    fwd = (rank == 0) ? 240f : -380f;
                    lat = centered * interval * 0.78f;
                    break;
                }
                case FLEET_PROTECTIVE_BASTION: {
                    double angle = (nCombat == 1)
                            ? 0.0
                            : (-Math.PI * 0.42 + (Math.PI * 0.84 * i) / (nCombat - 1));
                    float radius = Math.max(780f, interval * 0.82f);
                    fwd = (float) Math.cos(angle) * radius;
                    lat = (float) Math.sin(angle) * radius;
                    break;
                }
                case GRAND_LINE_ABREAST:
                case DOCTRINAL_AUTO:
                default: {
                    float centered;
                    if (i == 0) {
                        centered = 0f;
                    } else {
                        int wingRank = (i + 1) / 2;
                        float side = (i % 2 == 1) ? -1f : 1f;
                        centered = side * wingRank;
                    }
                    fwd = (i == 0) ? 90f : 0f;
                    lat = centered * interval * 0.90f;
                    break;
                }
            }
            d.fleetFormationOffset.set(axisX * fwd + perpX * lat, axisY * fwd + perpY * lat);
            d.hasFleetFormationOffset = true;
        }

        int nSupport = supportDivs.size();
        for (int i = 0; i < nSupport; i++) {
            DivisionState d = supportDivs.get(i);
            float interval = Math.max(850f, d.divisionInterval);
            float centered = i - (nSupport - 1) * 0.5f;
            float fwd = (resolved == PermanentDivisionManager.FleetWideFormation.FLEET_PROTECTIVE_BASTION)
                    ? -120f
                    : -880f;
            float lat = centered * interval * 0.75f;
            d.fleetFormationOffset.set(axisX * fwd + perpX * lat, axisY * fwd + perpY * lat);
            d.hasFleetFormationOffset = true;
        }
    }

    /**
     * Scope Item 4: Deploys/marshals divisions directly into their Fleet-Wide Formation and intra-division
     * formation slots at battle start (when safe in the deployment zone before close combat begins).
     */
    public static void applyInitialFleetFormationDeployment(
            CombatEngineAPI engine,
            BattleGroupState group,
             BattleGroupState enemyGroup,
            float now) {
        if (engine == null || group == null || !group.deployInFleetFormation) return;
        if (now > 3.5f && group.initialDeploymentDone) return;
        if (group.divisions.isEmpty()) return;

        updateFleetWideFormationGeometry(engine, group, enemyGroup);

        float mapW = Math.max(8000f, engine.getMapWidth());
        float mapH = Math.max(8000f, engine.getMapHeight());
        float maxAbsX = mapW * 0.42f;
        float maxAbsY = mapH * 0.42f;
        float fwdDirY = (group.side == 0) ? 1f : -1f;

        Set<ShipAPI> unplacedGroupShips = new HashSet<>();
        if (now <= 1.6f) {
            for (DivisionState d : group.divisions) {
                if (d == null || !d.isCombatEffective() || d.initialDeploymentMarshalled) continue;
                for (ShipAPI s : d.getAliveMembers()) {
                    if (s != null && !(s == engine.getPlayerShip() && !engine.isUIAutopilotOn())) {
                        unplacedGroupShips.add(s);
                    }
                }
            }
        }

        for (DivisionState div : group.divisions) {
            if (div == null || !div.isCombatEffective() || div.initialDeploymentMarshalled) continue;
            float nearestEnemy = (enemyGroup != null) ? distanceToNearestEnemyDivision(engine, div, enemyGroup) : 99999f;
            if (nearestEnemy < 2000f) {
                div.initialDeploymentMarshalled = true;
                continue;
            }
            float targetCenterX = group.fleetCentroid.x + div.fleetFormationOffset.x;
            float targetCenterY = group.fleetCentroid.y + div.fleetFormationOffset.y;
            targetCenterX = Math.max(-maxAbsX, Math.min(maxAbsX, targetCenterX));
            targetCenterY = Math.max(-maxAbsY, Math.min(maxAbsY, targetCenterY));

            div.stationPoint.set(targetCenterX, targetCenterY);
            // Item A: Orient initial deployment heading toward the division's opening objective (e.g. Comm Relay Beta NW
            // or Sensor Jammer Delta NE) when the division is a scouting/fast/non-heavy unit, so Line Abreast and
            // Echelon Right deploy perpendicular/echeloned to their actual opening march vector instead of facing 90 deg North!
            float initHx = 0f;
            float initHy = fwdDirY;
            boolean isHeavyOrCarrier = div.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                    || (div.archetype != null && div.archetype.isCarrierArchetype());
            DivisionMissionType openMission = DivisionCommanderAI.resolveActiveMission(div, now);
            if (!isHeavyOrCarrier
                    && openMission != DivisionMissionType.RESERVE
                    && engine.getObjectives() != null
                    && !engine.getObjectives().isEmpty()) {
                BattleObjectiveAPI nearestObj = null;
                float bestObjDist = Float.MAX_VALUE;
                for (BattleObjectiveAPI obj : engine.getObjectives()) {
                    if (obj == null || obj.getOwner() == group.side) continue;
                    float odx = obj.getLocation().x - targetCenterX;
                    float ody = obj.getLocation().y - targetCenterY;
                    float d = (float) Math.sqrt(odx * odx + ody * ody);
                    if (d < bestObjDist) {
                        bestObjDist = d;
                        nearestObj = obj;
                    }
                }
                if (nearestObj != null && bestObjDist > 250f) {
                    float odx = nearestObj.getLocation().x - targetCenterX;
                    float ody = nearestObj.getLocation().y - targetCenterY;
                    float oLen = (float) Math.sqrt(odx * odx + ody * ody);
                    if (oLen > 1f) {
                        initHx = odx / oLen;
                        initHy = ody / oLen;
                    }
                }
            }
            div.formationHeadingX = initHx;
            div.formationHeadingY = initHy;
            div.formationHeadingInitialized = true;
            float initFacingDeg = (float) Math.toDegrees(Math.atan2(initHy, initHx));
            if (initFacingDeg < 0f) initFacingDeg += 360f;

            if (now <= 1.6f) {
                List<ShipAPI> alive = div.getAliveMembers();
                PermanentDivisionManager.DivisionFormation fm = div.activeFormation != null
                        ? div.activeFormation
                        : (div.doctrinalFormation != null ? div.doctrinalFormation : PermanentDivisionManager.DivisionFormation.LINE_ABREAST);
                float spacing = Math.max(220f, div.stationSpacing);
                for (int i = 0; i < alive.size(); i++) {
                    ShipAPI s = alive.get(i);
                    if (s == null || s == engine.getPlayerShip() && !engine.isUIAutopilotOn()) continue;
                    Vector2f slot = DivisionCommanderAI.computeFormationSlotPositionForAnchor(
                            fm, i, alive, s, targetCenterX, targetCenterY, initHx, initHy, spacing);
                    float sx = Math.max(-maxAbsX, Math.min(maxAbsX, slot.x));
                    float sy = Math.max(-maxAbsY, Math.min(maxAbsY, slot.y));
                    unplacedGroupShips.remove(s);
                    if (isDeploymentSlotClear(engine, s, sx, sy, unplacedGroupShips)) {
                        s.getLocation().set(sx, sy);
                        s.setFacing(initFacingDeg);
                    }
                }
                div.centroid.set(targetCenterX, targetCenterY);
            }
            div.initialDeploymentMarshalled = true;
        }
        group.initialDeploymentDone = true;
    }

    private static boolean isDeploymentSlotClear(
            CombatEngineAPI engine,
            ShipAPI movingShip,
            float x,
            float y,
            Set<ShipAPI> unplacedGroupShips) {
        if (engine == null || engine.getShips() == null) return true;
        float myRad = Math.max(40f, movingShip.getCollisionRadius());
        for (ShipAPI other : engine.getShips()) {
            if (other == null || other == movingShip || !other.isAlive() || other.isFighter()) continue;
            if (unplacedGroupShips != null && unplacedGroupShips.contains(other)) continue;
            float minSep = (myRad + Math.max(40f, other.getCollisionRadius())) * 0.92f;
            float dx = other.getLocation().x - x;
            float dy = other.getLocation().y - y;
            if ((dx * dx + dy * dy) < minSep * minSep) {
                return false;
            }
        }
        return true;
    }

    private static DivisionState pickPrimaryEnemyTargetDivision(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup) {
        DivisionState best = null;
        float bestScore = -Float.MAX_VALUE;
        DivisionState anchorDiv = (myGroup.mainEffortDivision != null && myGroup.mainEffortDivision.isCombatEffective())
                ? myGroup.mainEffortDivision
                : (!myGroup.divisions.isEmpty() ? myGroup.divisions.get(0) : null);

        boolean anyAbleVisibleEnemy = false;
        for (DivisionState eDiv : enemyGroup.divisions) {
            if (eDiv.isAbleCombatEffective() && isDivisionVisibleToSide(engine, myGroup.side, eDiv)) {
                anyAbleVisibleEnemy = true;
                break;
            }
        }

        for (DivisionState eDiv : enemyGroup.divisions) {
            if (!eDiv.isCombatEffective()) continue;
            if (!isDivisionVisibleToSide(engine, myGroup.side, eDiv)) continue;
            Vector2f visCentroid = getVisibleCentroidForSide(engine, myGroup.side, eDiv);
            float visFp = getVisibleFpForSide(engine, myGroup.side, eDiv);
            float dist = distance(myGroup.fleetCentroid, visCentroid);
            float auxPenalty = (eDiv.archetype == DivisionArchetype.SERVICE_GROUP) ? -500f : 0f;
            // Item F: Heavily deprioritize retreating ("unable") enemy divisions as long as able enemy divisions remain
            float unablePenalty = (anyAbleVisibleEnemy && (eDiv.isUnableDivision || eDiv.retreatingFraction >= 0.5f)) ? -1400f : 0f;
            // Item D: Bonus for enemy divisions pinched against a map edge or corner (restricted maneuver options)
            float edgePinchBonus = BattlefieldFirepowerEvaluator.computeEdgeProximityPinch(engine, visCentroid, eDiv.freeTimeRadius) * 240f;

            float score;
            if (myGroup.useConvergence && anchorDiv != null) {
                // Convergence OTC decision-making (AI / Threat / Abyssal or Alpha Core flagship):
                // Synthesizes Capital/Cruiser mass value with Time, Space, and Firepower vulnerability
                float convSum = 0f;
                int convCount = 0;
                for (DivisionState d : myGroup.divisions) {
                    if (!d.isAbleCombatEffective()) continue;
                    convSum += BattlefieldFirepowerEvaluator.computeConvergenceTargetScore(engine, d, eDiv, myGroup);
                    convCount++;
                }
                float avgConv = (convCount > 0) ? (convSum / convCount) : 0f;
                float collapseBonus = (visFp >= 20f)
                        ? (eDiv.avgFluxLevel * 360f + (1.0f - eDiv.avgHullLevel) * 320f)
                        : 0f;
                score = visFp * 11.5f + avgConv * 0.55f + collapseBonus + auxPenalty + unablePenalty + edgePinchBonus - dist * 0.20f;
            } else {
                // Human OTC decision-making: weights Firepower value, Space Strongpoint/Weak Point seams, Time pinning, and Map-Edge pinching
                float isolationBonus = (eDiv.stationKeepingRatio > 1.3f) ? 350f : 0f;
                float spaceSeamBonus = eDiv.isWeakPoint ? 240f : (eDiv.isStrongpoint ? -120f : 0f);
                float pinnedBonus = eDiv.engagementLevel * 180f;
                score = visFp * 12f + isolationBonus + spaceSeamBonus + pinnedBonus + edgePinchBonus + auxPenalty + unablePenalty - dist * 0.25f;
            }
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
            float d = distance(myDiv.centroid, visCentroid);
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

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
