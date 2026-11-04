package divisionai;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CollisionGridAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
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
            updateSingleDivisionTelemetry(engine, div);
            if (!div.isUnableDivision && !div.getAliveMembers().isEmpty()) {
                fleetWeightedX += div.centroid.x * div.totalFpStrength;
                fleetWeightedY += div.centroid.y * div.totalFpStrength;
                totalFriendlyFp += div.totalFpStrength;
            }
        }

        float frontlineWeightedX = 0f;
        float frontlineWeightedY = 0f;
        float frontlineFp = 0f;
        float sumFleetLiveFp = 0f;
        float sumFleetInRangeFp = 0f;
        for (DivisionState div : battleGroup.divisions) {
            if (!div.isAbleCombatEffective()) continue;
            sumFleetLiveFp += div.liveFirepowerScore;
            sumFleetInRangeFp += div.inRangeFirepowerScore;
            if (div.currentIntent != DivisionModel.DivisionIntentType.HOLD_IN_RESERVE
                    && div.currentIntent != DivisionModel.DivisionIntentType.HAUL_OUT_TO_VENT
                    && div.currentIntent != DivisionModel.DivisionIntentType.BREAK_OFF_AND_RETIRE) {
                frontlineWeightedX += div.centroid.x * div.totalFpStrength;
                frontlineWeightedY += div.centroid.y * div.totalFpStrength;
                frontlineFp += div.totalFpStrength;
            }
        }
        battleGroup.fleetLiveFirepower = sumFleetLiveFp;
        battleGroup.fleetInRangeFirepower = sumFleetInRangeFp;
        battleGroup.fleetFirepowerUptime = (sumFleetLiveFp > 1f)
                ? Math.min(1f, sumFleetInRangeFp / sumFleetLiveFp)
                : 0f;

        updateIndependentShipsAndFleetCentroid(engine, battleGroup, fleetWeightedX, fleetWeightedY, totalFriendlyFp);
        DynamicBattleMusicController.updateBattleGroupEngagementTelemetry(battleGroup);
        updateReserveAndEngagementCorridors(engine, battleGroup, frontlineWeightedX, frontlineWeightedY, frontlineFp);
    }

    private static void updateSingleDivisionTelemetry(CombatEngineAPI engine, DivisionState div) {
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
            div.activeWeaponFiringRatio = 0f;
            div.violentContactIntensity = 0f;
            div.engagedFpMass = 0f;
            return;
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

        computeCoreClusterAndStragglers(div, alive, weights, sumX, sumY, sumVx, sumVy, weightSum);

        div.avgFluxLevel = fluxSum / alive.size();
        div.maxMemberFluxLevel = maxFlux;
        div.avgHullLevel = hullSum / alive.size();
        div.hasShipNeedingHelp = needsHelp;
        div.shipNeedingHelp = helpShip;
        div.usableStrikeAmmoCount = strikeAmmo;
        div.totalFpStrength = (ableCount > 0) ? Math.max(1f, ableWeightSum) : Math.max(1f, weightSum * 0.25f);

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

        evaluateLocalForceRatioViaGrid(engine, div);
        BattlefieldFirepowerEvaluator.updateDivisionFirepowerTelemetry(engine, div);
        BattlefieldFirepowerEvaluator.updateDivisionBaseTimeSpace(div);
        ShipSystemCoordinator.updateDivisionSkillTelemetry(div);
        if (div.crossfireCordonIndex >= 0.32f && div.localEnemyStrength > 1f) {
            float cordonMult = 1.0f + (div.crossfireCordonIndex - 0.20f) * 0.55f;
            div.localEnemyStrength *= cordonMult;
            div.localForceRatio = div.localFriendlyStrength / Math.max(1f, div.localEnemyStrength);
        }

        div.activeWeaponFiringRatio = DynamicBattleMusicController.computeDivisionActiveFiringRatio(div);
        div.violentContactIntensity = DynamicBattleMusicController.computeDivisionViolentContactIntensity(
                div, div.activeWeaponFiringRatio);
        div.engagedFpMass = div.totalFpStrength * div.violentContactIntensity;
    }

    private static void computeCoreClusterAndStragglers(
            DivisionState div,
            List<ShipAPI> alive,
            float[] weights,
            float sumX,
            float sumY,
            float sumVx,
            float sumVy,
            float weightSum) {
        float coreSumX = sumX;
        float coreSumY = sumY;
        float coreSumVx = sumVx;
        float coreSumVy = sumVy;
        float coreWeightSum = weightSum;
        int coreCount = alive.size();

        if (alive.size() >= 2) {
            boolean anyLinearBurn = div.hasActiveLinearSkillBurn || div.synchronizedSkillManeuverActive;
            float burnSpreadMult = anyLinearBurn ? 2.25f : 1.0f;
            float neighborRadius = Math.max(820f, div.stationKeepingRadius * 1.45f) * burnSpreadMult;
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
                float outlierCutoff = Math.max(1050f, div.stationKeepingRadius * 1.68f) * burnSpreadMult;
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
    }

    private static void updateIndependentShipsAndFleetCentroid(
            CombatEngineAPI engine,
            BattleGroupState battleGroup,
            float fleetWeightedX,
            float fleetWeightedY,
            float totalFriendlyFp) {
        battleGroup.friendlyIndependentShips.clear();
        float indepFp = 0f;
        float indepLiveFp = 0f;
        float indepEhp = 0f;
        float indepWx = 0f;
        float indepWy = 0f;
        float indepVx = 0f;
        float indepVy = 0f;
        if (engine != null && engine.getShips() != null) {
            for (ShipAPI s : engine.getShips()) {
                if (s == null || s.getOwner() != battleGroup.side) continue;
                if (!DivisionAutoFormer.isEligibleCombatHull(s) || DivisionState.isShipUnable(s)) continue;
                if (battleGroup.side == 0 && s.isAlly() != battleGroup.isAlliedAi) continue;
                if (battleGroup.shipIdToDivision.containsKey(s.getId())) continue;
                battleGroup.friendlyIndependentShips.add(s);
                float fp = ShipCapabilityProfile.resolveShipFpStrength(s);
                indepFp += fp;
                indepLiveFp += BattlefieldFirepowerEvaluator.computeShipLiveFirepowerScore(s, false);
                indepEhp += BattlefieldFirepowerEvaluator.computeShipEffectiveHp(s);
                indepWx += s.getLocation().x * fp;
                indepWy += s.getLocation().y * fp;
                if (s.getVelocity() != null) {
                    indepVx += s.getVelocity().x * fp;
                    indepVy += s.getVelocity().y * fp;
                }
            }
        }
        battleGroup.friendlyIndependentFp = indepFp;
        battleGroup.friendlyIndependentLiveFirepower = indepLiveFp;
        battleGroup.friendlyIndependentEhp = indepEhp;
        float totalCombinedFp = totalFriendlyFp + indepFp;
        battleGroup.organizationRatio = (totalCombinedFp > 1f)
                ? (totalFriendlyFp / totalCombinedFp)
                : 1.0f;
        if (indepFp > 0f) {
            battleGroup.independentCentroid.set(indepWx / indepFp, indepWy / indepFp);
            battleGroup.independentVelocity.set(indepVx / indepFp, indepVy / indepFp);
            battleGroup.independentProjectedCentroid.set(
                    battleGroup.independentCentroid.x + battleGroup.independentVelocity.x * 4.0f,
                    battleGroup.independentCentroid.y + battleGroup.independentVelocity.y * 4.0f);
        } else if (totalFriendlyFp > 0f) {
            battleGroup.independentCentroid.set(fleetWeightedX / totalFriendlyFp, fleetWeightedY / totalFriendlyFp);
            battleGroup.independentVelocity.set(0f, 0f);
            battleGroup.independentProjectedCentroid.set(battleGroup.independentCentroid);
        }

        battleGroup.totalFriendlyStrength = totalCombinedFp;
        if (totalFriendlyFp > 0f) {
            float orgCenterX = fleetWeightedX / totalFriendlyFp;
            float orgCenterY = fleetWeightedY / totalFriendlyFp;
            if (indepFp > 10f && battleGroup.organizationRatio < 0.65f) {
                float indepBlend = Math.min(0.50f, (0.68f - battleGroup.organizationRatio) * 0.75f);
                orgCenterX = orgCenterX * (1f - indepBlend) + battleGroup.independentCentroid.x * indepBlend;
                orgCenterY = orgCenterY * (1f - indepBlend) + battleGroup.independentCentroid.y * indepBlend;
            }
            battleGroup.fleetCentroid.set(orgCenterX, orgCenterY);
        } else if (indepFp > 0f) {
            battleGroup.fleetCentroid.set(battleGroup.independentCentroid);
        }
    }

    private static void updateReserveAndEngagementCorridors(
            CombatEngineAPI engine,
            BattleGroupState battleGroup,
            float frontlineWeightedX,
            float frontlineWeightedY,
            float frontlineFp) {
        float retreatDirY = (battleGroup.side == 0) ? -1f : 1f;
        float halfH = (engine != null && engine.getMapHeight() > 2000f) ? (engine.getMapHeight() * 0.5f) : 9000f;
        float maxEdgeY = halfH * 0.78f;
        boolean edgeBastionPosture = battleGroup.forceRatio < 0.82f
                || battleGroup.currentPhase == DivisionModel.BattlePhase.FIGHTING_RETIREMENT
                || battleGroup.activeFleetWideFormation == PermanentDivisionManager.FleetWideFormation.FLEET_PROTECTIVE_BASTION;
        if (frontlineFp > 0f) {
            float fx = frontlineWeightedX / frontlineFp;
            float fy = frontlineWeightedY / frontlineFp;
            float pullBackDist = edgeBastionPosture ? 740f : 620f;
            float targetResY = Math.max(-maxEdgeY, Math.min(maxEdgeY, fy + retreatDirY * pullBackDist));
            battleGroup.reserveStation.set(fx, targetResY);
            battleGroup.fleetEngagementZoneCenter.set(fx, fy);
            battleGroup.fleetSafeCorridorCenter.set(fx, Math.max(-maxEdgeY, Math.min(maxEdgeY, fy + retreatDirY * 560f)));
        } else if (battleGroup.reserveStation.x == 0f && battleGroup.reserveStation.y == 0f) {
            battleGroup.reserveStation.set(battleGroup.fleetCentroid.x, battleGroup.fleetCentroid.y + retreatDirY * 620f);
            battleGroup.fleetEngagementZoneCenter.set(battleGroup.fleetCentroid.x, battleGroup.fleetCentroid.y);
            battleGroup.fleetSafeCorridorCenter.set(battleGroup.fleetCentroid.x, battleGroup.fleetCentroid.y + retreatDirY * 560f);
        }
        if (isOrchestratedAdvanceActive(engine, battleGroup, null)
                && battleGroup.reserveDivision != null
                && battleGroup.reserveDivision.hasFleetFormationOffset
                && battleGroup.mainEffortDivision != null) {
            float rx = battleGroup.mainEffortDivision.centroid.x + battleGroup.reserveDivision.fleetFormationOffset.x;
            float ry = Math.max(-maxEdgeY, Math.min(maxEdgeY,
                    battleGroup.mainEffortDivision.centroid.y + battleGroup.reserveDivision.fleetFormationOffset.y));
            battleGroup.reserveStation.set(rx, ry);
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

            // Items A & B: Weight local combat effectiveness by live weapon firepower (discounting reloading,
            // disabled, and out-of-ammo mounts, with phase ships exempt) AND Effective HP (Shield + Armor + Hull HP).
            float effectiveStrength = BattlefieldFirepowerEvaluator.computeEffectiveShipCombatPower(s);
            if (s.getOwner() == div.side) {
                friendlyFp += effectiveStrength;
            } else if (isShipVisibleToSide(engine, div.side, s)) {
                enemyFp += effectiveStrength;
            }
        }

        float selfCombatStrength = 0f;
        for (ShipAPI m : div.getAliveMembers()) {
            selfCombatStrength += BattlefieldFirepowerEvaluator.computeEffectiveShipCombatPower(m);
        }
        float minSelfFloor = selfCombatStrength * 0.5f;
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
        if (observerSide == div.side || engine == null) return div.isCombatEffective();
        FogOfWarAPI fog = engine.getFogOfWar(observerSide);
        List<ShipAPI> members = div.members;
        for (int i = 0, n = members.size(); i < n; i++) {
            ShipAPI s = members.get(i);
            if (s != null && s.isAlive() && !s.isHulk() && !s.isExpired()) {
                if (fog == null || fog.isVisible(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Vector2f getVisibleCentroidForSide(CombatEngineAPI engine, int observerSide, DivisionState div) {
        if (div == null) return new Vector2f(0f, 0f);
        if (observerSide == div.side || engine == null) return div.centroid;
        FogOfWarAPI fog = engine.getFogOfWar(observerSide);
        if (fog == null) return div.centroid;
        float sumX = 0f;
        float sumY = 0f;
        float weightSum = 0f;
        List<ShipAPI> members = div.members;
        for (int i = 0, n = members.size(); i < n; i++) {
            ShipAPI s = members.get(i);
            if (s == null || !s.isAlive() || s.isHulk() || s.isExpired()) continue;
            if (!fog.isVisible(s)) continue;
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
        FogOfWarAPI fog = engine.getFogOfWar(observerSide);
        if (fog == null) return div.totalFpStrength;
        float fpSum = 0f;
        List<ShipAPI> members = div.members;
        for (int i = 0, n = members.size(); i < n; i++) {
            ShipAPI s = members.get(i);
            if (s == null || !s.isAlive() || s.isHulk() || s.isExpired()) continue;
            if (fog.isVisible(s)) {
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

        // Each side's OTC evaluates only its own divisions against the enemy; the enemy OTC does the reverse on its own
        // update. Running enemy->my here too mutated directional radii mid-frame and made results depend on update order.
        BattlefieldFirepowerEvaluator.updateCrossGroupTimeSpaceConvergence(engine, myGroup, enemyGroup);
        BattlefieldFirepowerEvaluator.updateEnemyFeignedRetreatSuspicion(engine, myGroup, enemyGroup);
        updateEnemyOverkillAndCenterOfGravity(engine, myGroup, enemyGroup);

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

        // If this side's OTC is disabled due to flagship destruction (120s for overall commander, 30s for lower/no station),
        // suspend OTC phase changes, battle-plan retasking, and independent fleet signals until command is restored.
        if (DivisionOtcCommandManager.isOtcDisabled(myGroup, now)) {
            return;
        }
        if (myGroup.otcDisabledWarningActive) {
            myGroup.otcDisabledWarningActive = false;
            DivisionOtcCommandManager.onOtcRestored(engine, myGroup, now);
        }

        // Synchronize Grand Formation & OTC division role/wing distribution ("Consent") before evaluating phases
        synchronizeGrandFormationConsent(engine, myGroup, enemyGroup, now);

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

            float mainScore = computeMainEffortCandidateScore(myGroup, div, now);
            if (mainScore > strongestLineFp) {
                strongestLineFp = mainScore;
                strongestLineDiv = div;
            }
        }

        // Second pass: pick a mobile reserve candidate (never pick Main Effort, Battle Divisions, Cruiser lines,
        // Carrier groups, or divisions explicitly tasked to ENVELOPMENT / SCREEN / SCOUTING unless their mission is RESERVE)
        bestReserveCandidate = selectConsentedReserveCandidate(engine, myGroup, enemyGroup, strongestLineDiv, activeDivCount, now);

        // Consent lock: While Orchestrated Advance is active and existing Main Effort remains combat-effective,
        // keep the consented Main Effort so the Grand Formation spearhead never swaps mid-advance.
        if (isOrchestratedAdvanceActive(engine, myGroup, enemyGroup)
                && myGroup.mainEffortDivision != null
                && myGroup.mainEffortDivision.isAbleCombatEffective()
                && !myGroup.mainEffortDivision.underDirectObjectiveOrder) {
            strongestLineDiv = myGroup.mainEffortDivision;
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
        boolean enemyBaitTrapSuspected = myGroup.primaryThreatEnemyDivision != null
                && myGroup.primaryThreatEnemyDivision.enemyFeignedRetreatSuspicion >= 0.60f;
        boolean generalChase = !enemyBaitTrapSuspected
                && myGroup.forceRatio >= 1.35f && visibleEnemyFp >= 30f && minContactDist < 3000f;
        boolean generalChasePersists = !enemyBaitTrapSuspected
                && current == BattlePhase.GENERAL_CHASE
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
        } else if ((!anyEnemyVisible && now < 90f)
                || (minContactDist > 3300f && hasUncapturedObjectives && now < 75f && myGroup.fleetEngagementLevel < 0.22f)) {
            nextPhase = BattlePhase.APPROACH;
        } else if (generalChase || generalChasePersists) {
            nextPhase = BattlePhase.GENERAL_CHASE;
        } else if (strikeRunning || (hasMissileOrStrikeDivisionReady(myGroup) && minContactDist < 3400f)) {
            nextPhase = BattlePhase.COORDINATED_STRIKE;
        } else {
            nextPhase = BattlePhase.GENERAL_ENGAGEMENT;
        }

        // Minimum dwell: a phase holds for MIN_PHASE_DWELL seconds unless withdrawal is ordered, a severe crisis erupts,
        // or the fleet was in APPROACH without enemy contact and has now spotted the enemy within engagement distance!
        boolean dwellElapsed = (now - myGroup.phaseEnteredTime) >= MIN_PHASE_DWELL;
        boolean severeCrisis = endangeredDiv != null && worstLocalRatio < 0.45f;
        boolean approachContactEstablished = (current == BattlePhase.APPROACH)
                && anyEnemyVisible
                && minContactDist < 3200f;
        if (nextPhase != current && !dwellElapsed
                && !approachContactEstablished
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
        // but commit the reserve immediately once the main battle line is in violent contact against an equal/superior force
        // so we never fight 4v5 outnumbered while 20% of the fleet watches from the rear.
        boolean reservePlay = myGroup.isPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE) && myGroup.authority.allowsRetasking();
        boolean frontlineInCloseFight = (minContactDist < 1850f || myGroup.fleetEngagementLevel >= 0.26f)
                && (myGroup.forceRatio <= 1.18f || worstLocalRatio < 0.98f || myGroup.fleetEngagementLevel >= 0.38f);
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
        if (!myGroup.divisions.isEmpty()) {
            updateFleetWideFormationGeometry(engine, myGroup, enemyGroup);
            OtcBattlePlanner.planBattle(engine, myGroup, enemyGroup, endangeredDiv, minorEndangeredDiv, now);
        }
        // Issue simple fleet-wide general signals to any friendly independent ships in the battle group
        updateFleetIndependentCommand(engine, myGroup, enemyGroup, endangeredDiv, now);
        applyFleetIndependentCommands(engine, myGroup, enemyGroup, now);

        // Log OTC operational directive when phase or primary focus shifts
        if (!myGroup.divisions.isEmpty()) {
            String directive = buildOtcSignal(myGroup, endangeredDiv);
            DivisionCombatLog.logOtcSignal(engine, myGroup, directive, now);
        }
    }

    /**
     * Selects a single coarse fleet-wide general signal ({@link DivisionModel.FleetIndependentCommand})
     * and objective point for all friendly independent ships in {@code myGroup}.
     */
    public static void updateFleetIndependentCommand(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            DivisionState endangeredDiv,
            float now) {
        if (myGroup == null || myGroup.friendlyIndependentShips.isEmpty()) {
            if (myGroup != null) {
                myGroup.activeIndependentCommand = DivisionModel.FleetIndependentCommand.NONE;
            }
            return;
        }
        float cmdCadence = myGroup.useConvergence ? 2.0f : 3.0f;
        if (myGroup.activeIndependentCommand != DivisionModel.FleetIndependentCommand.NONE
                && (now - myGroup.lastIndependentCommandUpdateTime) < cmdCadence) {
            return;
        }
        myGroup.lastIndependentCommandUpdateTime = now;

        float fwdDirY = (myGroup.side == 0) ? 1f : -1f;
        myGroup.fleetIndependentCarrierPoint.set(
                myGroup.fleetCentroid.x,
                myGroup.fleetCentroid.y - fwdDirY * 540f);

        float sumFlux = 0f;
        float maxFlux = 0f;
        int highFluxCount = 0;
        int nAlive = 0;
        for (ShipAPI s : myGroup.friendlyIndependentShips) {
            if (s != null && s.isAlive()) {
                float fl = s.getFluxLevel();
                sumFlux += fl;
                if (fl > maxFlux) maxFlux = fl;
                if (fl >= 0.58f) highFluxCount++;
                nAlive++;
            }
        }
        float avgIndepFlux = (nAlive > 0) ? (sumFlux / nAlive) : 0f;

        DivisionState primary = myGroup.primaryThreatEnemyDivision;
        boolean primaryVisible = primary != null
                && primary.isCombatEffective()
                && isDivisionVisibleToSide(engine, myGroup.side, primary);

        if (isFullAssaultActive(engine, myGroup) || myGroup.currentPhase == BattlePhase.GENERAL_CHASE) {
            myGroup.activeIndependentCommand = DivisionModel.FleetIndependentCommand.FULL_ASSAULT_CHARGE;
            if (primaryVisible) {
                myGroup.fleetIndependentCommandPoint.set(getVisibleCentroidForSide(engine, myGroup.side, primary));
            }
        } else if (myGroup.currentPhase == BattlePhase.FIGHTING_RETIREMENT
                || myGroup.fleetTradeSpaceActive) {
            myGroup.activeIndependentCommand = DivisionModel.FleetIndependentCommand.GENERAL_RETREAT_RALLY;
            myGroup.fleetIndependentCommandPoint.set(myGroup.reserveStation);
        } else if (myGroup.currentPhase == BattlePhase.REINFORCE_THE_LINE
                || (endangeredDiv != null && endangeredDiv.isCombatEffective())) {
            myGroup.activeIndependentCommand = DivisionModel.FleetIndependentCommand.REINFORCE_THREATENED_SECTOR;
            Vector2f anchor = (endangeredDiv != null && endangeredDiv.isCombatEffective())
                    ? endangeredDiv.centroid
                    : myGroup.fleetCentroid;
            myGroup.fleetIndependentCommandPoint.set(anchor.x, anchor.y + fwdDirY * 140f);
        } else if (myGroup.currentPhase == BattlePhase.APPROACH && !primaryVisible) {
            myGroup.activeIndependentCommand = DivisionModel.FleetIndependentCommand.RALLY_ON_FLEET_CENTER;
            myGroup.fleetIndependentCommandPoint.set(
                    myGroup.fleetCentroid.x,
                    myGroup.fleetCentroid.y + fwdDirY * 220f);
        } else {
            myGroup.activeIndependentCommand = (myGroup.currentPhase == BattlePhase.COORDINATED_STRIKE)
                    ? DivisionModel.FleetIndependentCommand.PRESS_COORDINATED_STRIKE
                    : DivisionModel.FleetIndependentCommand.ENGAGE_PRIMARY_SECTOR;
            if (primaryVisible) {
                Vector2f ePos = getVisibleCentroidForSide(engine, myGroup.side, primary);
                myGroup.fleetIndependentCommandPoint.set(ePos);
            } else {
                myGroup.fleetIndependentCommandPoint.set(myGroup.fleetCentroid.x, myGroup.fleetCentroid.y + fwdDirY * 600f);
            }
        }
    }

    /**
     * Applies the OTC's active {@link DivisionModel.FleetIndependentCommand} to all friendly independent ships
     * via coarse {@link ShipwideAIFlags}. Unlike organized divisions (which have Division Commanders to manage
     * per-slot formation geometry, fine double-line rank relief, and division focus-fire target links),
     * independent ships receive only blanket fleet-wide posture signals along their own lanes.
     */
    /**
     * Returns true when {@code ship} is the player's currently piloted ship under manual helm control
     * (Autopilot OFF).
     *
     * Note on Starsector engine quirk: in {@code com.fs.starfarer.combat.CombatEngine.isUIAutopilotOn()},
     * vanilla Starsector returns {@code !combatUI.isAutopilotOn()} (inverted: true when manual control is active,
     * false when autopilot is active), whereas {@code CombatUI.setAutopilot(boolean)} sets
     * {@code playerShip.setShipAI(null)} during manual control and attaches a non-null {@code ShipAIPlugin}
     * when autopilot is enabled. Checking {@code ship.getShipAI() == null} reliably detects manual player control.
     */
    public static boolean isPlayerManuallyPiloting(CombatEngineAPI engine, ShipAPI ship) {
        if (engine == null || ship == null || ship != engine.getPlayerShip()) {
            return false;
        }
        return ship.getShipAI() == null;
    }

    public static void applyFleetIndependentCommands(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            float now) {
        if (myGroup == null
                || myGroup.friendlyIndependentShips.isEmpty()
                || myGroup.activeIndependentCommand == DivisionModel.FleetIndependentCommand.NONE) {
            return;
        }
        Set<ShipAPI> divMembers = new HashSet<>();
        for (DivisionState d : myGroup.divisions) {
            if (d != null && d.members != null) divMembers.addAll(d.members);
        }
        float fwdDirY = (myGroup.side == 0) ? 1f : -1f;
        for (ShipAPI s : myGroup.friendlyIndependentShips) {
            if (s == null || !s.isAlive() || divMembers.contains(s)) continue;
            if (isPlayerManuallyPiloting(engine, s)) continue;
            ShipwideAIFlags flags = s.getAIFlags();
            if (flags == null) continue;

            // Lane-preserving fleet-wide step-back target (never clumps independent ships into a single pinpoint deathball)
            Vector2f laneRetreatGoal = new Vector2f(
                    s.getLocation().x,
                    s.getLocation().y - fwdDirY * 360f);

            switch (myGroup.activeIndependentCommand) {
                case RALLY_ON_FLEET_CENTER: {
                    Vector2f laneRallyGoal = new Vector2f(
                            s.getLocation().x,
                            myGroup.fleetIndependentCommandPoint.y);
                    if (DivisionUiWidgets.distance(s.getLocation(), laneRallyGoal) > 1650f) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, 0.85f, laneRallyGoal);
                    }
                    break;
                }
                case GENERAL_RETREAT_RALLY:
                    // Blanket fleet-wide recoil: without a Division Commander to isolate only the >=62% flux ships
                    // into a second rank, the OTC's general recall pulls back all moderately fluxed independent ships!
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, 0.85f);
                    if (s.getFluxLevel() >= 0.28f) {
                        flags.setFlag(AIFlags.BACK_OFF, 0.85f);
                        flags.setFlag(AIFlags.CAMP_LOCATION, 0.85f, laneRetreatGoal);
                    }
                    break;
                case REINFORCE_THREATENED_SECTOR: {
                    Vector2f sectorGoal = new Vector2f(
                            s.getLocation().x * 0.65f + myGroup.fleetIndependentCommandPoint.x * 0.35f,
                            myGroup.fleetIndependentCommandPoint.y);
                    if (DivisionUiWidgets.distance(s.getLocation(), myGroup.fleetIndependentCommandPoint) > 1950f) {
                        flags.setFlag(AIFlags.CAMP_LOCATION, 0.85f, sectorGoal);
                    }
                    break;
                }
                case ENGAGE_PRIMARY_SECTOR:
                case PRESS_COORDINATED_STRIKE: {
                    if (DivisionUiWidgets.distance(s.getLocation(), myGroup.fleetIndependentCommandPoint) > 2350f) {
                        Vector2f laneAdvanceGoal = new Vector2f(
                                s.getLocation().x * 0.70f + myGroup.fleetIndependentCommandPoint.x * 0.30f,
                                myGroup.fleetIndependentCommandPoint.y);
                        flags.setFlag(AIFlags.CAMP_LOCATION, 0.85f, laneAdvanceGoal);
                    }
                    break;
                }
                case FULL_ASSAULT_CHARGE:
                    flags.unsetFlag(AIFlags.CAMP_LOCATION);
                    flags.unsetFlag(AIFlags.DO_NOT_PURSUE);
                    if (s.getFluxLevel() < 0.55f) {
                        flags.setFlag(AIFlags.HARASS_MOVE_IN, 0.85f);
                        flags.setFlag(AIFlags.PURSUING, 0.85f);
                    }
                    break;
                default:
                    break;
            }
        }
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

    /** Returns true when Starsector's Full Assault command is active for the given BattleGroup's side/task manager. */
    public static boolean isFullAssaultActive(CombatEngineAPI engine, BattleGroupState group) {
        if (engine == null || group == null) return false;
        try {
            CombatFleetManagerAPI fm = engine.getFleetManager(group.side);
            if (fm == null) return false;
            CombatTaskManagerAPI tm = fm.getTaskManager(group.side == 0 && group.isAlliedAi);
            if (tm != null && tm.isFullAssault()) return true;
            if (group.side == 0 && group.isAlliedAi) {
                CombatTaskManagerAPI playerTm = fm.getTaskManager(false);
                if (playerTm != null && playerTm.isFullAssault()) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
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
        if (isFullAssaultActive(engine, myGroup)) {
            for (DivisionState d : myGroup.divisions) {
                if (d != null) {
                    d.hasFleetFormationOffset = false;
                    d.fleetFormationOffset.set(0f, 0f);
                }
            }
            return;
        }
        List<DivisionState> active = new ArrayList<>();
        for (DivisionState d : myGroup.divisions) {
            if (d != null && d.isCombatEffective()) {
                active.add(d);
            }
        }
        if (active.isEmpty()) return;

        if (myGroup.mainEffortDivision == null) {
            DivisionState bestMain = null;
            float bestFp = -1f;
            for (DivisionState d : active) {
                if (d.archetype != null && (d.archetype.isCarrierArchetype() || d.archetype == DivisionArchetype.SERVICE_GROUP)) {
                    continue;
                }
                if (d.totalFpStrength > bestFp) {
                    bestFp = d.totalFpStrength;
                    bestMain = d;
                }
            }
            if (bestMain == null && !active.isEmpty()) {
                bestMain = active.get(0);
            }
            myGroup.mainEffortDivision = bestMain;
        }

        PermanentDivisionManager.FleetWideFormation configured = myGroup.fleetWideFormation != null
                ? myGroup.fleetWideFormation
                : PermanentDivisionManager.FleetWideFormation.DOCTRINAL_AUTO;
        PermanentDivisionManager.FleetWideFormation resolved = configured;
        if (resolved == PermanentDivisionManager.FleetWideFormation.NONE) {
            myGroup.activeFleetWideFormation = PermanentDivisionManager.FleetWideFormation.NONE;
            for (DivisionState d : active) {
                d.fleetFormationRank = 0;
                d.fleetFormationWingSide = 0;
                d.fleetFormationOffset.set(0f, 0f);
                d.hasFleetFormationOffset = false;
            }
            return;
        }
        if (resolved == null || resolved == PermanentDivisionManager.FleetWideFormation.DOCTRINAL_AUTO) {
            resolved = resolveDoctrinalAutoFleetFormation(myGroup, active);
        }
        myGroup.activeFleetWideFormation = resolved;

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
        float now = (engine != null) ? engine.getTotalElapsedTime(false) : 0f;

        if (myGroup.mainEffortDivision == null || !myGroup.mainEffortDivision.isAbleCombatEffective()) {
            synchronizeGrandFormationConsent(engine, myGroup, enemyGroup, now);
        }

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
        if (myGroup.mainEffortDivision != null && combatDivs.remove(myGroup.mainEffortDivision)) {
            combatDivs.add(0, myGroup.mainEffortDivision);
        }

        orderCombatDivisionsByConsent(myGroup, combatDivs, perpX, perpY, now);
        assignCombatDivisionsFleetFormationOffsets(engine, combatDivs, resolved, axisX, axisY, perpX, perpY);
        assignSupportDivisionsFleetFormationOffsets(supportDivs, resolved, axisX, axisY, perpX, perpY);
    }

    private static PermanentDivisionManager.FleetWideFormation resolveDoctrinalAutoFleetFormation(
            BattleGroupState myGroup,
            List<DivisionState> active) {
        boolean hasCarrier = false;
        int fastFlankers = 0;
        int heavyLineDivs = 0;
        for (DivisionState d : active) {
            if (d.archetype != null && d.archetype.isCarrierArchetype()) hasCarrier = true;
            if (d.isFastFlanker) fastFlankers++;
            if (d.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                    || d.archetype == DivisionArchetype.CRUISER_DIVISION
                    || d.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION) {
                heavyLineDivs++;
            }
        }
        if (hasCarrier && myGroup.forceRatio < 0.92f) {
            return PermanentDivisionManager.FleetWideFormation.FLEET_PROTECTIVE_BASTION;
        }
        if (myGroup.organizationRatio < 0.55f) {
            return (heavyLineDivs >= 1 && active.size() >= 2)
                    ? PermanentDivisionManager.FleetWideFormation.GRAND_WEDGE_SPEARHEAD
                    : PermanentDivisionManager.FleetWideFormation.GRAND_LINE_ABREAST;
        }
        if (myGroup.useConvergence && fastFlankers >= 2 && active.size() >= 3) {
            return PermanentDivisionManager.FleetWideFormation.CRESCENT_HORNS;
        }
        if ((myGroup.forceRatio >= 1.22f && active.size() >= 3)
                || (heavyLineDivs >= 2 && myGroup.forceRatio >= 1.05f && active.size() >= 3)) {
            return PermanentDivisionManager.FleetWideFormation.GRAND_WEDGE_SPEARHEAD;
        }
        if (myGroup.forceRatio < 0.80f && active.size() >= 3) {
            return PermanentDivisionManager.FleetWideFormation.REFUSED_FLANK_OBLIQUE;
        }
        if (hasCarrier && active.size() >= 3) {
            return PermanentDivisionManager.FleetWideFormation.CHECKERBOARD_QUINCUNX;
        }
        return PermanentDivisionManager.FleetWideFormation.GRAND_LINE_ABREAST;
    }

    private static void assignCombatDivisionsFleetFormationOffsets(
            CombatEngineAPI engine,
            List<DivisionState> combatDivs,
            PermanentDivisionManager.FleetWideFormation resolved,
            float axisX,
            float axisY,
            float perpX,
            float perpY) {
        int nCombat = combatDivs.size();
        float maxSpanHalf = (engine != null && engine.getMapWidth() > 0f)
                ? (engine.getMapWidth() * 0.36f)
                : 4200f;
        float maxRanks = Math.max(1f, (nCombat + 1) * 0.5f);
        for (int i = 0; i < nCombat; i++) {
            DivisionState d = combatDivs.get(i);
            int shipCount = (d.members != null && !d.members.isEmpty()) ? d.members.size() : 3;
            float divFootprint = Math.min(5, Math.max(2, shipCount)) * Math.max(200f, d.stationSpacing * 0.85f) + 160f;
            float rawInterval = Math.max(680f, Math.min(920f, Math.max(d.divisionInterval * 0.82f, divFootprint)));
            float interval = (nCombat >= 4)
                    ? Math.max(640f, Math.min(rawInterval, Math.min(820f, maxSpanHalf / maxRanks)))
                    : rawInterval;
            float fwd = 0f;
            float lat = 0f;
            int rankIdx = 0;
            int wingSide = 0;
            switch (resolved) {
                case GRAND_COLUMN_IN_DEPTH: {
                    fwd = ((nCombat - 1) * 0.5f - i) * 650f;
                    lat = (i == 0) ? 0f : ((i % 2 == 1) ? -160f : 160f);
                    rankIdx = i;
                    wingSide = (i == 0) ? 0 : ((i % 2 == 1) ? -1 : 1);
                    break;
                }
                case GRAND_WEDGE_SPEARHEAD: {
                    if (i == 0) {
                        fwd = 420f;
                        lat = 0f;
                        rankIdx = 0;
                        wingSide = 0;
                    } else {
                        int wingRank = (i + 1) / 2;
                        int side = (i % 2 == 1) ? -1 : 1;
                        fwd = 420f - wingRank * 480f;
                        lat = side * wingRank * interval * 0.88f;
                        rankIdx = wingRank;
                        wingSide = side;
                    }
                    break;
                }
                case REVERSE_WEDGE: {
                    if (i == 0) {
                        fwd = -280f;
                        lat = 0f;
                        rankIdx = 0;
                        wingSide = 0;
                    } else {
                        int wingRank = (i + 1) / 2;
                        int side = (i % 2 == 1) ? -1 : 1;
                        fwd = -280f + wingRank * 440f;
                        lat = side * wingRank * interval * 0.86f;
                        rankIdx = wingRank;
                        wingSide = side;
                    }
                    break;
                }
                case REFUSED_FLANK_OBLIQUE: {
                    float centered = i - (nCombat - 1) * 0.5f;
                    lat = centered * interval * 0.85f;
                    fwd = 360f - i * 340f;
                    rankIdx = i;
                    wingSide = (i == 0) ? 0 : 1;
                    break;
                }
                case REFUSED_FLANK_LEFT: {
                    float centered = (nCombat - 1) * 0.5f - i;
                    lat = centered * interval * 0.85f;
                    fwd = 360f - i * 340f;
                    rankIdx = i;
                    wingSide = (i == 0) ? 0 : -1;
                    break;
                }
                case CRESCENT_HORNS: {
                    if (i == 0) {
                        fwd = -80f;
                        lat = 0f;
                        rankIdx = 0;
                        wingSide = 0;
                    } else {
                        int wingRank = (i + 1) / 2;
                        int side = (i % 2 == 1) ? -1 : 1;
                        fwd = -60f + Math.min(260f, wingRank * 110f);
                        lat = side * wingRank * interval * 0.82f;
                        rankIdx = wingRank;
                        wingSide = side;
                    }
                    break;
                }
                case REVERSE_CRESCENT: {
                    if (i == 0) {
                        fwd = 260f;
                        lat = 0f;
                        rankIdx = 0;
                        wingSide = 0;
                    } else {
                        int wingRank = (i + 1) / 2;
                        int side = (i % 2 == 1) ? -1 : 1;
                        fwd = 260f - Math.min(480f, wingRank * 210f);
                        lat = side * wingRank * interval * 0.84f;
                        rankIdx = wingRank;
                        wingSide = side;
                    }
                    break;
                }
                case CHECKERBOARD_QUINCUNX: {
                    int rank = i % 2;
                    float centered = i - (nCombat - 1) * 0.5f;
                    fwd = (rank == 0) ? 240f : -380f;
                    lat = centered * interval * 0.78f;
                    rankIdx = rank;
                    wingSide = (centered < -0.05f) ? -1 : ((centered > 0.05f) ? 1 : 0);
                    break;
                }
                case FLEET_PROTECTIVE_BASTION: {
                    double angle = (nCombat == 1)
                            ? 0.0
                            : (-Math.PI * 0.42 + (Math.PI * 0.84 * i) / (nCombat - 1));
                    float radius = Math.max(780f, interval * 0.82f);
                    fwd = (float) Math.cos(angle) * radius;
                    lat = (float) Math.sin(angle) * radius;
                    rankIdx = 0;
                    wingSide = (lat < -50f) ? -1 : ((lat > 50f) ? 1 : 0);
                    break;
                }
                case GRAND_LINE_ABREAST:
                case DOCTRINAL_AUTO:
                default: {
                    float centered;
                    if (i == 0) {
                        centered = 0f;
                        rankIdx = 0;
                        wingSide = 0;
                    } else {
                        int wingRank = (i + 1) / 2;
                        int side = (i % 2 == 1) ? -1 : 1;
                        centered = side * wingRank;
                        rankIdx = wingRank;
                        wingSide = side;
                    }
                    fwd = (i == 0) ? 90f : 0f;
                    lat = centered * interval * 0.90f;
                    break;
                }
            }
            d.fleetFormationRank = rankIdx;
            d.fleetFormationWingSide = wingSide;
            d.fleetFormationSlotIndex = i;
            if (d.consentedWingSide == 0 && wingSide != 0) {
                d.consentedWingSide = wingSide;
            } else if (i == 0) {
                d.consentedWingSide = 0;
            }
            d.fleetFormationOffset.set(axisX * fwd + perpX * lat, axisY * fwd + perpY * lat);
            d.hasFleetFormationOffset = true;
        }
    }

    private static void assignSupportDivisionsFleetFormationOffsets(
            List<DivisionState> supportDivs,
            PermanentDivisionManager.FleetWideFormation resolved,
            float axisX,
            float axisY,
            float perpX,
            float perpY) {
        int nSupport = supportDivs.size();
        for (int i = 0; i < nSupport; i++) {
            DivisionState d = supportDivs.get(i);
            float interval = Math.max(850f, d.divisionInterval);
            float centered = i - (nSupport - 1) * 0.5f;
            float fwd = (resolved == PermanentDivisionManager.FleetWideFormation.FLEET_PROTECTIVE_BASTION)
                    ? -120f
                    : -880f;
            float lat = centered * interval * 0.75f;
            d.fleetFormationRank = 2;
            d.fleetFormationWingSide = (centered < -0.05f) ? -1 : ((centered > 0.05f) ? 1 : 0);
            d.fleetFormationSlotIndex = 100 + i;
            d.consentedWingSide = 0;
            d.fleetFormationOffset.set(axisX * fwd + perpX * lat, axisY * fwd + perpY * lat);
            d.hasFleetFormationOffset = true;
        }
    }

    /**
     * Synchronizes Grand Formations and what the OTC considers an appropriate distribution of divisions
     * across the wider fleet ("Consent"). Runs at deployment time (t = 0.0s) before wave placement and
     * before OTC tasking so Main Effort, Reserve, and Port/Center/Starboard wing assignments agree from frame 0.
     */
    public static void synchronizeGrandFormationConsent(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            float now) {
        if (myGroup == null || myGroup.divisions.isEmpty()) return;

        int activeDivCount = 0;
        DivisionState strongestLineDiv = null;
        float strongestLineFp = -1f;
        for (DivisionState div : myGroup.divisions) {
            if (div == null || !div.isAbleCombatEffective()) continue;
            activeDivCount++;
            float score = computeMainEffortCandidateScore(myGroup, div, now);
            if (score > strongestLineFp) {
                strongestLineFp = score;
                strongestLineDiv = div;
            }
        }
        if (activeDivCount == 0) return;

        boolean oaActive = isOrchestratedAdvanceActive(engine, myGroup, enemyGroup);
        if (oaActive
                && myGroup.mainEffortDivision != null
                && myGroup.mainEffortDivision.isAbleCombatEffective()
                && !myGroup.mainEffortDivision.underDirectObjectiveOrder) {
            strongestLineDiv = myGroup.mainEffortDivision;
        }
        myGroup.mainEffortDivision = strongestLineDiv;

        DivisionState bestReserve = selectConsentedReserveCandidate(
                engine, myGroup, enemyGroup, strongestLineDiv, activeDivCount, now);
        boolean reservePlay = myGroup.isPlayEnabled(OtcPlay.RESERVE_AND_GENERAL_CHASE) && myGroup.authority.allowsRetasking();
        float minContactDist = (enemyGroup != null) ? minDistanceBetweenFleets(engine, myGroup, enemyGroup) : Float.MAX_VALUE;
        boolean frontlineInCloseFight = (minContactDist < 1850f || myGroup.fleetEngagementLevel >= 0.26f)
                && (myGroup.forceRatio <= 1.18f || myGroup.fleetEngagementLevel >= 0.38f);
        boolean holdReserve = reservePlay
                ? (!myGroup.reserveCommitted && !frontlineInCloseFight && now < 90f && activeDivCount >= 4
                        && myGroup.currentPhase != BattlePhase.FIGHTING_RETIREMENT && myGroup.otc.aggressionBias <= 1.35f)
                : (!frontlineInCloseFight && now < 45f && activeDivCount >= 4
                        && myGroup.currentPhase == BattlePhase.APPROACH && myGroup.otc.aggressionBias <= 1.20f);

        for (DivisionState d : myGroup.divisions) {
            if (d == null) continue;
            d.isDesignatedMainEffort = (d == myGroup.mainEffortDivision);
            d.isDesignatedReserve = holdReserve && (d == bestReserve);
        }
        myGroup.reserveDivision = holdReserve ? bestReserve : null;
    }

    private static float computeMainEffortCandidateScore(BattleGroupState myGroup, DivisionState div, float now) {
        float mainScore = div.totalFpStrength * (1.2f - 0.5f * div.avgFluxLevel);
        if (div.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                || div.archetype == DivisionArchetype.MISSILE_BATTLESHIP_DIVISION) {
            mainScore = mainScore * 2.05f + 25f;
        } else if (div.archetype == DivisionArchetype.CRUISER_DIVISION
                || div.archetype == DivisionArchetype.MISSILE_CRUISER_DIVISION
                || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION) {
            mainScore *= 1.35f;
        } else if (div.archetype == DivisionArchetype.CARRIER_GROUP) {
            mainScore *= 0.35f;
        } else if (div.archetype != null && div.archetype.isCarrierArchetype()) {
            mainScore *= 0.12f;
        }
        DivisionMissionType mission = DivisionCommanderAI.resolveActiveMission(div, now);
        if (mission == DivisionMissionType.ENVELOPMENT
                || mission == DivisionMissionType.SCREEN
                || mission == DivisionMissionType.RESERVE
                || mission == DivisionMissionType.SCOUTING) {
            mainScore *= 0.6f;
        }
        if (div == myGroup.mainEffortDivision) {
            mainScore *= 1.15f;
        }
        if (div.underDirectObjectiveOrder) {
            mainScore *= 0.15f;
        }
        return mainScore;
    }

    private static DivisionState selectConsentedReserveCandidate(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            DivisionState strongestLineDiv,
            int activeDivCount,
            float now) {
        if (myGroup.reserveDivision != null
                && myGroup.reserveDivision.isAbleCombatEffective()
                && myGroup.reserveDivision != strongestLineDiv
                && isOrchestratedAdvanceActive(engine, myGroup, enemyGroup)) {
            return myGroup.reserveDivision;
        }
        DivisionState bestReserveCandidate = null;
        float bestReserveScore = -1f;
        for (DivisionState div : myGroup.divisions) {
            if (div == null || !div.isAbleCombatEffective()) continue;
            if (div == strongestLineDiv
                    || div.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                    || div.archetype == DivisionArchetype.MISSILE_BATTLESHIP_DIVISION
                    || div.archetype == DivisionArchetype.CRUISER_DIVISION
                    || div.archetype == DivisionArchetype.MISSILE_CRUISER_DIVISION
                    || div.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION
                    || (div.archetype != null && div.archetype.isCarrierArchetype())) {
                continue;
            }
            DivisionMissionType divMission = DivisionCommanderAI.resolveActiveMission(div, now);
            if (divMission != DivisionMissionType.RESERVE) {
                // Consent: never hijack divisions explicitly assigned to Envelopment, Screen, or Scouting into
                // Reserve unless their mission is RESERVE (prevents yanking a Scouting Destroyer division backward at t=1.5s!)
                if (activeDivCount < 4
                        || divMission == DivisionMissionType.ENVELOPMENT
                        || divMission == DivisionMissionType.SCREEN
                        || divMission == DivisionMissionType.SCOUTING) {
                    continue;
                }
            }
            float distToEnemy = (enemyGroup != null) ? distanceToNearestEnemyDivision(engine, div, enemyGroup) : 4000f;
            float distFactor = (distToEnemy < Float.MAX_VALUE * 0.5f) ? (distToEnemy * 0.001f) : 2.0f;
            float timeFreedomBonus = div.timeFreedomFactor * 0.85f + (div.effectiveTimeRadius / 2400f) * 0.45f;
            float reserveScore = distFactor + (div.isFastFlanker ? 1.5f : 0.8f) + timeFreedomBonus - div.avgFluxLevel;
            if (divMission == DivisionMissionType.RESERVE) {
                reserveScore += 5f;
            }
            if (div == myGroup.reserveDivision) {
                reserveScore += 1.5f;
            }
            if (reserveScore > bestReserveScore) {
                bestReserveScore = reserveScore;
                bestReserveCandidate = div;
            }
        }
        return bestReserveCandidate;
    }

    /**
     * Orders {@code combatDivs} (with {@code combatDivs.get(0)} as Main Effort) by doctrinal Grand Formation
     * role tier (Inner Line: Heavy/Screen -> Mid Wing: Destroyers -> Outer Horns: Fast Flankers / Envelopment / Scouts)
     * and locks Port (-1) vs Starboard (+1) wing pairing so divisions never swap wings after deployment.
     */
    private static void orderCombatDivisionsByConsent(
            BattleGroupState myGroup,
            List<DivisionState> combatDivs,
            float perpX,
            float perpY,
            float now) {
        if (combatDivs == null || combatDivs.size() <= 1) return;

        boolean canReuseLockedSlots = myGroup.grandFormationConsentLocked
                && myGroup.consentedDivisionCount == combatDivs.size();
        if (canReuseLockedSlots) {
            for (int i = 1; i < combatDivs.size(); i++) {
                DivisionState d = combatDivs.get(i);
                if (d.fleetFormationSlotIndex < 1 || d.fleetFormationSlotIndex >= combatDivs.size()) {
                    canReuseLockedSlots = false;
                    break;
                }
            }
        }
        if (canReuseLockedSlots) {
            List<DivisionState> wings = new ArrayList<>(combatDivs.subList(1, combatDivs.size()));
            wings.sort((a, b) -> Integer.compare(a.fleetFormationSlotIndex, b.fleetFormationSlotIndex));
            for (int i = 0; i < wings.size(); i++) {
                combatDivs.set(i + 1, wings.get(i));
            }
            return;
        }

        List<DivisionState> wings = new ArrayList<>(combatDivs.subList(1, combatDivs.size()));
        wings.sort((a, b) -> {
            int tierA = computeGrandFormationRoleTier(a, now);
            int tierB = computeGrandFormationRoleTier(b, now);
            if (tierA != tierB) return Integer.compare(tierA, tierB);
            int fpCmp = Float.compare(b.totalFpStrength, a.totalFpStrength);
            if (fpCmp != 0) return fpCmp;
            return Integer.compare(a.divisionNumber, b.divisionNumber);
        });

        // Within each symmetric wing pair (slot 2k-1 = Port -1, slot 2k = Starboard +1), match each division
        // to its consented wing side or current lateral offset along (perpX, perpY) so divisions never cross the fleet!
        for (int idx = 0; idx + 1 < wings.size(); idx += 2) {
            DivisionState dPort = wings.get(idx);
            DivisionState dStar = wings.get(idx + 1);
            float latA = (dPort.consentedWingSide != 0)
                    ? (dPort.consentedWingSide * 2000f)
                    : ((dPort.centroid.x - myGroup.fleetCentroid.x) * perpX
                            + (dPort.centroid.y - myGroup.fleetCentroid.y) * perpY);
            float latB = (dStar.consentedWingSide != 0)
                    ? (dStar.consentedWingSide * 2000f)
                    : ((dStar.centroid.x - myGroup.fleetCentroid.x) * perpX
                            + (dStar.centroid.y - myGroup.fleetCentroid.y) * perpY);
            if (latA > latB + 15f) {
                wings.set(idx, dStar);
                wings.set(idx + 1, dPort);
            }
            wings.get(idx).consentedWingSide = -1;
            wings.get(idx + 1).consentedWingSide = 1;
        }
        if (wings.size() % 2 == 1) {
            DivisionState last = wings.get(wings.size() - 1);
            if (last.consentedWingSide == 0) {
                last.consentedWingSide = -1;
            }
        }

        combatDivs.get(0).fleetFormationSlotIndex = 0;
        combatDivs.get(0).consentedWingSide = 0;
        for (int i = 0; i < wings.size(); i++) {
            DivisionState w = wings.get(i);
            w.fleetFormationSlotIndex = i + 1;
            combatDivs.set(i + 1, w);
        }
        myGroup.grandFormationConsentLocked = true;
        myGroup.consentedDivisionCount = combatDivs.size();
    }

    private static int computeGrandFormationRoleTier(DivisionState d, float now) {
        if (d == null) return 3;
        DivisionMissionType mission = DivisionCommanderAI.resolveActiveMission(d, now);
        if (d.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                || d.archetype == DivisionArchetype.MISSILE_BATTLESHIP_DIVISION
                || d.archetype == DivisionArchetype.CRUISER_DIVISION
                || d.archetype == DivisionArchetype.MISSILE_CRUISER_DIVISION
                || d.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION
                || mission == DivisionMissionType.SCREEN) {
            return 1; // Inner Battleline adjacent to Main Effort
        }
        if ((d.archetype == DivisionArchetype.DESTROYER_DIVISION
                || d.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION)
                && mission != DivisionMissionType.ENVELOPMENT
                && mission != DivisionMissionType.SCOUTING) {
            return 2; // Mid-Wing Support Line
        }
        return 3; // Outer Flanks / Horns (Frigates, Fast Flankers, Envelopment, Scouting)
    }

    /**
     * Formation-Aware Combat Deployment Hijack (built on top of vanilla CombatFleetManager.deploy as a failsafe):
     * Rearranges newly spawned deployment waves into their Fleet-Wide and Intra-Division formations at the map
     * entry zone and synchronizes their travel-drive burn durations so entire fleets and divisions zoom into
     * the battlefield already in formation (modulated by Fleet Doctrine, OTC personality, Division personality,
     * and Division settings). If any error occurs, restores the exact vanilla deployment state.
     */
    /**
     * Returns true if "Orchestrated Advance" is currently active for {@code group}:
     * the fleet advances cohesively in its given Fleet-Wide Formation toward the enemy until significant contact is made.
     */
    public static boolean isOrchestratedAdvanceActive(
            CombatEngineAPI engine,
            BattleGroupState group,
            BattleGroupState enemyGroup) {
        if (group == null || !group.orchestratedAdvance) return false;
        if (group.fleetWideFormation == PermanentDivisionManager.FleetWideFormation.NONE
                || group.activeFleetWideFormation == PermanentDivisionManager.FleetWideFormation.NONE) {
            return false;
        }
        if (isFullAssaultActive(engine, group)) {
            group.orchestratedAdvanceContactMade = true;
            return false;
        }
        if (group.orchestratedAdvanceContactMade) {
            return false;
        }
        if (group.fleetEngagementLevel >= 0.18f
                || group.smoothedEngagementLevel >= 0.18f
                || group.mainEffortEngaged
                || group.engagedDivisionCount >= 2) {
            group.orchestratedAdvanceContactMade = true;
            return false;
        }
        for (DivisionState d : group.divisions) {
            if (d == null || !d.isCombatEffective()) continue;
            if (d.inRangeFirepowerScore >= 180f
                    || d.incomingInRangeFirepower >= 180f
                    || d.violentContactIntensity >= 0.35f) {
                group.orchestratedAdvanceContactMade = true;
                return false;
            }
        }
        if (group.mainEffortDivision != null && enemyGroup != null) {
            float dist = distanceToNearestEnemyDivision(engine, group.mainEffortDivision, enemyGroup);
            float contactThreshold = Math.max(1250f, group.mainEffortDivision.standoffRange * 1.15f);
            if (dist <= contactThreshold) {
                group.orchestratedAdvanceContactMade = true;
                return false;
            }
        }
        return true;
    }

    public static void hijackNewlyDeployedWaveIntoFormation(
            CombatEngineAPI engine,
            BattleGroupState group,
            BattleGroupState enemyGroup,
            List<ShipAPI> newlyDeployedShips,
            float now) {
        if (engine == null || group == null || newlyDeployedShips == null || newlyDeployedShips.isEmpty()) {
            return;
        }
        if (!group.deployInFleetFormation
                || group.fleetWideFormation == PermanentDivisionManager.FleetWideFormation.NONE) {
            for (ShipAPI s : newlyDeployedShips) {
                if (s != null && s.getId() != null) group.formationSpawnHijackedShipIds.add(s.getId());
            }
            return;
        }
        if (group.divisions.isEmpty()) {
            return;
        }
        for (ShipAPI s : newlyDeployedShips) {
            if (s != null && s.getId() != null) group.formationSpawnHijackedShipIds.add(s.getId());
        }

        final int count = newlyDeployedShips.size();
        final float[] backupLocX = new float[count];
        final float[] backupLocY = new float[count];
        final float[] backupVelX = new float[count];
        final float[] backupVelY = new float[count];
        final float[] backupFacing = new float[count];
        for (int i = 0; i < count; i++) {
            ShipAPI s = newlyDeployedShips.get(i);
            if (s != null) {
                backupLocX[i] = s.getLocation().x;
                backupLocY[i] = s.getLocation().y;
                backupVelX[i] = s.getVelocity().x;
                backupVelY[i] = s.getVelocity().y;
                backupFacing[i] = s.getFacing();
            }
        }

        try {
            updateFleetWideFormationGeometry(engine, group, enemyGroup);

            List<DivisionState> deployingDivs = new ArrayList<>();
            java.util.Map<DivisionState, List<ShipAPI>> waveShipsByDiv = new java.util.LinkedHashMap<>();
            float waveSumX = 0f;
            float waveSumY = 0f;
            float waveWeight = 0f;
            float frontmostEntryY = (group.side == 0) ? -Float.MAX_VALUE : Float.MAX_VALUE;
            boolean anyTravelDriveActive = false;

            for (ShipAPI s : newlyDeployedShips) {
                if (s == null || !s.isAlive() || s.isFighter()) continue;
                DivisionState d = group.shipIdToDivision.get(s.getId());
                if (d == null) continue;
                List<ShipAPI> list = waveShipsByDiv.get(d);
                if (list == null) {
                    list = new ArrayList<>();
                    waveShipsByDiv.put(d, list);
                    deployingDivs.add(d);
                }
                list.add(s);
                float w = Math.max(1f, ShipCapabilityProfile.resolveShipFpStrength(s));
                waveSumX += s.getLocation().x * w;
                waveSumY += s.getLocation().y * w;
                waveWeight += w;
                if (group.side == 0) {
                    if (s.getLocation().y > frontmostEntryY) frontmostEntryY = s.getLocation().y;
                } else {
                    if (s.getLocation().y < frontmostEntryY) frontmostEntryY = s.getLocation().y;
                }
                if (s.getTravelDrive() != null && s.getTravelDrive().isActive()) {
                    anyTravelDriveActive = true;
                }
            }

            if (deployingDivs.isEmpty() || waveWeight <= 0f) {
                return;
            }

            float waveCenterX = waveSumX / waveWeight;
            float waveAverageY = waveSumY / waveWeight;
            float mapW = Math.max(8000f, engine.getMapWidth());
            float mapH = Math.max(8000f, engine.getMapHeight());
            float maxAbsX = mapW * 0.42f;
            float maxAbsY = mapH * 0.42f;
            float fwdDirY = (group.side == 0) ? 1f : -1f;

            float avgOffsetX = 0f;
            float avgOffsetY = 0f;
            for (DivisionState d : deployingDivs) {
                avgOffsetX += d.fleetFormationOffset.x;
                avgOffsetY += d.fleetFormationOffset.y;
            }
            avgOffsetX /= deployingDivs.size();
            avgOffsetY /= deployingDivs.size();

            float otcStrictness = (group.otc != null) ? group.otc.stationKeepingStrictness : 1.0f;
            float otcAggression = (group.otc != null) ? group.otc.aggressionBias : 1.0f;
            float authorityScale = (group.authority == PermanentDivisionManager.OtcAuthority.POSITIVE_CONTROL)
                    ? 0.92f
                    : (group.authority == PermanentDivisionManager.OtcAuthority.COMMAND_BY_NEGATION ? 1.12f : 1.0f);
            float otcCohesionScale = authorityScale * Math.max(0.82f, Math.min(1.22f, 2.0f - otcStrictness));

            float entryBaseY = anyTravelDriveActive
                    ? (frontmostEntryY - fwdDirY * 160f)
                    : waveAverageY;
            float vanillaStopLineY = (group.side == 0)
                    ? (-mapH * 0.5f + 2150f)
                    : (mapH * 0.5f - 2150f);
            float travelDistToStopLine = (group.side == 0)
                    ? (vanillaStopLineY - entryBaseY)
                    : (entryBaseY - vanillaStopLineY);
            float baseWaveBurnDur = Math.max(1.30f, Math.min(4.20f, travelDistToStopLine / 600f));
            float otcBurnBonus = (otcAggression - 1.0f) * 0.35f;
            float syncFleetBurnDur = Math.max(1.20f, Math.min(4.50f, baseWaveBurnDur + otcBurnBonus));

            Set<ShipAPI> unplacedWaveShips = new HashSet<>();
            for (List<ShipAPI> divShips : waveShipsByDiv.values()) {
                for (ShipAPI s : divShips) {
                    if (s != null && !(s == engine.getPlayerShip() && !engine.isUIAutopilotOn())) {
                        unplacedWaveShips.add(s);
                    }
                }
            }

            for (DivisionState div : deployingDivs) {
                List<ShipAPI> divWaveShips = waveShipsByDiv.get(div);
                if (divWaveShips == null || divWaveShips.isEmpty()) continue;
                placeDivisionWaveShipsInFormation(
                        engine, group, div, divWaveShips, deployingDivs.size(),
                        waveCenterX, waveAverageY, entryBaseY, avgOffsetX, avgOffsetY,
                        otcStrictness, otcCohesionScale, syncFleetBurnDur,
                        maxAbsX, maxAbsY, fwdDirY, anyTravelDriveActive, unplacedWaveShips, now);
            }
            group.initialDeploymentDone = true;
        } catch (Throwable t) {
            for (int i = 0; i < count; i++) {
                ShipAPI s = newlyDeployedShips.get(i);
                if (s != null && s.getId() != null) group.formationSpawnHijackedShipIds.add(s.getId());
                if (s != null && s.isAlive()) {
                    s.getLocation().set(backupLocX[i], backupLocY[i]);
                    s.getVelocity().set(backupVelX[i], backupVelY[i]);
                    s.setFacing(backupFacing[i]);
                }
            }
        }
    }

    private static void placeDivisionWaveShipsInFormation(
            CombatEngineAPI engine,
            BattleGroupState group,
            DivisionState div,
            List<ShipAPI> divWaveShips,
            int totalDeployingDivs,
            float waveCenterX,
            float waveAverageY,
            float entryBaseY,
            float avgOffsetX,
            float avgOffsetY,
            float otcStrictness,
            float otcCohesionScale,
            float syncFleetBurnDur,
            float maxAbsX,
            float maxAbsY,
            float fwdDirY,
            boolean anyTravelDriveActive,
            Set<ShipAPI> unplacedWaveShips,
            float now) {
        if (div.flagship != null && divWaveShips.remove(div.flagship)) {
            divWaveShips.add(0, div.flagship);
        }

        DivisionCommanderAI.evaluateAndUpdateDivisionFormation(engine, div, group, now);

        float relFleetX = (totalDeployingDivs >= 2)
                ? (div.fleetFormationOffset.x - avgOffsetX) * otcCohesionScale
                : 0f;
        float relFleetY = (totalDeployingDivs >= 2)
                ? (div.fleetFormationOffset.y - avgOffsetY) * otcCohesionScale
                : 0f;

        float lineStationBiasY = 0f;
        if (div.lineStation == PermanentDivisionManager.LineStation.VAN) {
            lineStationBiasY = fwdDirY * 150f;
        } else if (div.lineStation == PermanentDivisionManager.LineStation.REAR
                || (div.archetype != null && div.archetype.isCarrierArchetype())) {
            lineStationBiasY = -fwdDirY * 220f;
        }

        float divAnchorX = Math.max(-maxAbsX, Math.min(maxAbsX, waveCenterX + relFleetX));
        float divAnchorY = anyTravelDriveActive
                ? (entryBaseY + relFleetY + lineStationBiasY)
                : Math.max(-maxAbsY, Math.min(maxAbsY, waveAverageY + relFleetY + lineStationBiasY));

        float initHx = 0f;
        float initHy = fwdDirY;
        boolean isHeavyOrCarrier = div.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                || (div.archetype != null && div.archetype.isCarrierArchetype());
        DivisionMissionType openMission = DivisionCommanderAI.resolveActiveMission(div, now);
        if (!anyTravelDriveActive
                && !isHeavyOrCarrier
                && openMission != DivisionMissionType.RESERVE
                && engine.getObjectives() != null
                && !engine.getObjectives().isEmpty()) {
            BattleObjectiveAPI nearestObj = null;
            float bestObjDist = Float.MAX_VALUE;
            for (BattleObjectiveAPI obj : engine.getObjectives()) {
                if (obj == null || obj.getOwner() == group.side) continue;
                float odx = obj.getLocation().x - divAnchorX;
                float ody = obj.getLocation().y - divAnchorY;
                float d = (float) Math.sqrt(odx * odx + ody * ody);
                if (d < bestObjDist) {
                    bestObjDist = d;
                    nearestObj = obj;
                }
            }
            if (nearestObj != null && bestObjDist > 250f) {
                float odx = nearestObj.getLocation().x - divAnchorX;
                float ody = nearestObj.getLocation().y - divAnchorY;
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

        PermanentDivisionManager.DivisionFormation fm = div.activeFormation != null
                ? div.activeFormation
                : (div.doctrinalFormation != null ? div.doctrinalFormation : PermanentDivisionManager.DivisionFormation.LINE_ABREAST);

        float divStrictness = (div.commander != null) ? div.commander.stationKeepingStrictness : 1.0f;
        float combinedStrictness = 0.5f * (otcStrictness + divStrictness);
        float looseness = Math.max(0f, 1.05f - combinedStrictness) * 0.25f;
        float effectiveSpacing = Math.max(210f, div.stationSpacing * Math.max(0.78f, Math.min(1.30f, div.stationKeepingMult * (1.85f - 0.85f * divStrictness))));

        div.assignedSlotByShipId.clear();
        div.lastSlotAssignedFormation = fm;
        div.lastSlotAssignedCount = divWaveShips.size();

        for (int i = 0; i < divWaveShips.size(); i++) {
            ShipAPI s = divWaveShips.get(i);
            if (s == null) continue;
            if (s.getId() != null) {
                div.assignedSlotByShipId.put(s.getId(), i);
            }
            if (s == engine.getPlayerShip() && !engine.isUIAutopilotOn()) {
                continue;
            }
            Vector2f slot = DivisionFormationNavigator.computeFormationSlotPositionForAnchor(
                    fm, i, divWaveShips, s, divAnchorX, divAnchorY, initHx, initHy, effectiveSpacing);

            int hash = (s.getId() != null) ? s.getId().hashCode() : (i * 7919);
            float jx = (((hash & 0xFF) / 127.5f) - 1.0f) * 45f * looseness;
            float jy = ((((hash >> 8) & 0xFF) / 127.5f) - 1.0f) * 35f * looseness;

            float targetX = Math.max(-maxAbsX, Math.min(maxAbsX, slot.x + jx));
            float targetY = anyTravelDriveActive
                    ? (slot.y + jy)
                    : Math.max(-maxAbsY, Math.min(maxAbsY, slot.y + jy));

            unplacedWaveShips.remove(s);
            Vector2f safePos = resolveSafeDeploymentPosition(engine, s, targetX, targetY, maxAbsX, unplacedWaveShips);
            if (!Float.isNaN(safePos.x) && !Float.isNaN(safePos.y)) {
                float deltaX = safePos.x - s.getLocation().x;
                float deltaY = safePos.y - s.getLocation().y;
                s.getLocation().set(safePos.x, safePos.y);
                s.setFacing(initFacingDeg);
                if (anyTravelDriveActive && s.getTravelDrive() != null && s.getTravelDrive().isActive()) {
                    float origSpeed = (float) Math.sqrt(
                            s.getVelocity().x * s.getVelocity().x + s.getVelocity().y * s.getVelocity().y);
                    float spd = (origSpeed >= 250f && origSpeed <= 750f) ? origSpeed : 600f;
                    s.getVelocity().set(initHx * spd, initHy * spd);
                    s.turnOnTravelDrive(syncFleetBurnDur);
                }
                translateSpawnedChildModulesAndWings(s, deltaX, deltaY, initFacingDeg);
            }
        }

        float postBurnX = Math.max(-maxAbsX, Math.min(maxAbsX, divAnchorX + (anyTravelDriveActive ? initHx * 600f * syncFleetBurnDur : 0f)));
        float postBurnY = Math.max(-maxAbsY, Math.min(maxAbsY, divAnchorY + (anyTravelDriveActive ? initHy * 600f * syncFleetBurnDur : 0f)));
        div.stationPoint.set(postBurnX, postBurnY);
        div.centroid.set(divAnchorX, divAnchorY);
        div.initialDeploymentMarshalled = true;
    }

    private static void translateSpawnedChildModulesAndWings(
            ShipAPI s,
            float deltaX,
            float deltaY,
            float initFacingDeg) {
        try {
            if (s.getChildModulesCopy() != null) {
                for (ShipAPI mod : s.getChildModulesCopy()) {
                    if (mod != null && mod.getLocation() != null) {
                        mod.getLocation().set(mod.getLocation().x + deltaX, mod.getLocation().y + deltaY);
                        mod.setFacing(initFacingDeg);
                    }
                }
            }
            if (s.getAllWings() != null) {
                for (com.fs.starfarer.api.combat.FighterWingAPI w : s.getAllWings()) {
                    if (w == null || w.getWingMembers() == null) continue;
                    for (ShipAPI fmShip : w.getWingMembers()) {
                        if (fmShip != null && fmShip.getLocation() != null) {
                            fmShip.getLocation().set(
                                    fmShip.getLocation().x + deltaX,
                                    fmShip.getLocation().y + deltaY);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static Vector2f resolveSafeDeploymentPosition(
            CombatEngineAPI engine,
            ShipAPI movingShip,
            float desiredX,
            float desiredY,
            float maxAbsX,
            Set<ShipAPI> unplacedGroupShips) {
        if (isDeploymentSlotClear(engine, movingShip, desiredX, desiredY, unplacedGroupShips)) {
            return new Vector2f(desiredX, desiredY);
        }
        float step = Math.max(95f, movingShip.getCollisionRadius() * 1.15f);
        for (int ring = 1; ring <= 6; ring++) {
            for (int k = 0; k < 8; k++) {
                double ang = (Math.PI * 0.25) * k;
                float cx = Math.max(-maxAbsX, Math.min(maxAbsX, desiredX + (float) Math.cos(ang) * step * ring));
                float cy = desiredY + (float) Math.sin(ang) * step * ring;
                if (isDeploymentSlotClear(engine, movingShip, cx, cy, unplacedGroupShips)) {
                    return new Vector2f(cx, cy);
                }
            }
        }
        return new Vector2f(desiredX, desiredY);
    }

    /**
     * Scope Item 4: Deploys/marshals divisions directly into their Fleet-Wide Formation and intra-division
     * formation slots at battle start (when safe in the deployment zone before close combat begins).
     */
    public static boolean applyInitialFleetFormationDeployment(
            CombatEngineAPI engine,
            BattleGroupState battleGroup,
            BattleGroupState enemyGroup,
            float now) {
        if (engine == null || battleGroup == null || !battleGroup.deployInFleetFormation
                || battleGroup.fleetWideFormation == PermanentDivisionManager.FleetWideFormation.NONE) return false;
        if (now > 3.5f && battleGroup.initialDeploymentDone) return false;
        if (battleGroup.divisions.isEmpty()) return false;

        updateFleetWideFormationGeometry(engine, battleGroup, enemyGroup);

        float mapW = Math.max(8000f, engine.getMapWidth());
        float mapH = Math.max(8000f, engine.getMapHeight());
        float maxAbsX = mapW * 0.42f;
        float maxAbsY = mapH * 0.42f;
        float fwdDirY = (battleGroup.side == 0) ? 1f : -1f;

        Set<ShipAPI> unplacedGroupShips = new HashSet<>();
        if (now <= 1.6f) {
            for (DivisionState d : battleGroup.divisions) {
                if (d == null || !d.isCombatEffective() || d.initialDeploymentMarshalled) continue;
                for (ShipAPI s : d.getAliveMembers()) {
                    if (s != null && !(s == engine.getPlayerShip() && !engine.isUIAutopilotOn())) {
                        unplacedGroupShips.add(s);
                    }
                }
            }
        }

        float maxFwdOffset = 0f;
        for (DivisionState d : battleGroup.divisions) {
            if (d == null || !d.isCombatEffective()) continue;
            float fwdOff = d.fleetFormationOffset.y * fwdDirY;
            if (fwdOff > maxFwdOffset) {
                maxFwdOffset = fwdOff;
            }
        }

        for (DivisionState div : battleGroup.divisions) {
            if (div == null || !div.isCombatEffective() || div.initialDeploymentMarshalled) continue;
            float nearestEnemy = (enemyGroup != null) ? distanceToNearestEnemyDivision(engine, div, enemyGroup) : 99999f;
            if (nearestEnemy < 2000f) {
                div.initialDeploymentMarshalled = true;
                continue;
            }
            float targetCenterX = battleGroup.fleetCentroid.x + div.fleetFormationOffset.x;
            float targetCenterY = battleGroup.fleetCentroid.y + div.fleetFormationOffset.y - maxFwdOffset * fwdDirY;
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
                    if (obj == null || obj.getOwner() == battleGroup.side) continue;
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
                    Vector2f slot = DivisionFormationNavigator.computeFormationSlotPositionForAnchor(
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
        battleGroup.initialDeploymentDone = true;
        return true;
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

    /**
     * Computes each enemy division's war-fighting threat weight (discounting civilian/freighter/converted-freighter/light
     * auxiliary hulls), locates the Enemy Center of Gravity, and evaluates per-target OTC Overkill thresholds so the
     * fleet does not send 4 divisions to chase stray armed freighters on a flank while the Enemy Center of Gravity
     * engages our battleline.
     */
    public static void updateEnemyOverkillAndCenterOfGravity(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup) {
        if (myGroup == null || enemyGroup == null) return;
        myGroup.hasEnemyCenterOfGravity = false;
        if (enemyGroup.divisions.isEmpty()) return;

        float cogSumX = 0f;
        float cogSumY = 0f;
        float cogWeightSum = 0f;
        int visibleAbleEnemyCount = 0;

        for (DivisionState eDiv : enemyGroup.divisions) {
            if (eDiv == null) continue;
            eDiv.committedFriendlyFpAgainst = 0f;
            eDiv.isOverkilled = false;
            if (!eDiv.isCombatEffective() || !isDivisionVisibleToSide(engine, myGroup.side, eDiv)) {
                eDiv.combatThreatWeight = 1.0f;
                eDiv.overkillCapFp = 999f;
                continue;
            }
            eDiv.combatThreatWeight = computeDivisionCombatThreatWeight(engine, myGroup.side, eDiv);
            if (!eDiv.isUnableDivision && eDiv.retreatingFraction < 0.50f) {
                visibleAbleEnemyCount++;
                Vector2f visPos = getVisibleCentroidForSide(engine, myGroup.side, eDiv);
                float visFp = getVisibleFpForSide(engine, myGroup.side, eDiv);
                float lineMult = (eDiv.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                        || eDiv.archetype == DivisionArchetype.MISSILE_BATTLESHIP_DIVISION
                        || eDiv.archetype == DivisionArchetype.CRUISER_DIVISION
                        || eDiv.archetype == DivisionArchetype.MISSILE_CRUISER_DIVISION
                        || eDiv.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION) ? 1.35f : 1.0f;
                float w = Math.max(1f, visFp * eDiv.combatThreatWeight * lineMult);
                cogSumX += visPos.x * w;
                cogSumY += visPos.y * w;
                cogWeightSum += w;
            }
        }

        if (cogWeightSum > 0f) {
            myGroup.enemyCenterOfGravity.set(cogSumX / cogWeightSum, cogSumY / cogWeightSum);
            myGroup.hasEnemyCenterOfGravity = true;
        }

        // Compute per-enemy-division Overkill FP cap and committed friendly FP
        for (DivisionState eDiv : enemyGroup.divisions) {
            if (eDiv == null || !eDiv.isCombatEffective() || !isDivisionVisibleToSide(engine, myGroup.side, eDiv)) {
                continue;
            }
            Vector2f visPos = getVisibleCentroidForSide(engine, myGroup.side, eDiv);
            float visFp = getVisibleFpForSide(engine, myGroup.side, eDiv);
            float effectiveThreatFp = Math.max(5f, visFp * eDiv.combatThreatWeight);
            float distToCog = myGroup.hasEnemyCenterOfGravity ? DivisionUiWidgets.distance(visPos, myGroup.enemyCenterOfGravity) : 0f;
            boolean isPeripheralFlankPack = (distToCog > 2000f && effectiveThreatFp < 26f)
                    || eDiv.combatThreatWeight <= 0.58f
                    || eDiv.archetype == DivisionArchetype.SERVICE_GROUP;
            if (visibleAbleEnemyCount <= 1) {
                eDiv.overkillCapFp = 999f;
            } else if (isPeripheralFlankPack) {
                // Stray flank pack or armed freighters: 1 light/medium division (~15-22 FP) is already 100% sufficient!
                eDiv.overkillCapFp = Math.max(14f, effectiveThreatFp * 1.35f);
            } else {
                float mult = (eDiv == myGroup.primaryThreatEnemyDivision) ? 2.15f : 1.55f;
                eDiv.overkillCapFp = Math.max(22f, effectiveThreatFp * mult);
            }
        }

        for (DivisionState myDiv : myGroup.divisions) {
            if (myDiv == null || !myDiv.isAbleCombatEffective()) continue;
            DivisionState assigned = (myDiv.otcOrder != null && myDiv.otcOrder.targetEnemy != null
                    && myDiv.otcOrder.targetEnemy.isCombatEffective())
                    ? myDiv.otcOrder.targetEnemy
                    : myDiv.targetEnemyDivision;
            if (assigned != null && assigned.isCombatEffective()) {
                assigned.committedFriendlyFpAgainst += myDiv.totalFpStrength;
            }
        }

        if (visibleAbleEnemyCount >= 2) {
            boolean anyUnderAllocatedAbleEnemy = false;
            for (DivisionState eDiv : enemyGroup.divisions) {
                if (eDiv == null || !eDiv.isAbleCombatEffective() || !isDivisionVisibleToSide(engine, myGroup.side, eDiv)) {
                    continue;
                }
                if (eDiv.committedFriendlyFpAgainst < eDiv.overkillCapFp * 0.92f) {
                    anyUnderAllocatedAbleEnemy = true;
                    break;
                }
            }
            if (anyUnderAllocatedAbleEnemy) {
                for (DivisionState eDiv : enemyGroup.divisions) {
                    if (eDiv == null || !eDiv.isCombatEffective()) continue;
                    eDiv.isOverkilled = eDiv.committedFriendlyFpAgainst >= eDiv.overkillCapFp;
                }
            }
        }
    }

    public static float computeDivisionCombatThreatWeight(
            CombatEngineAPI engine,
            int observerSide,
            DivisionState div) {
        if (div == null) return 1.0f;
        if (div.archetype == DivisionArchetype.SERVICE_GROUP) return 0.35f;
        float weightedSum = 0f;
        float fpSum = 0f;
        for (ShipAPI s : div.getAliveMembers()) {
            if (s == null) continue;
            if (engine != null && observerSide != div.side && !isShipVisibleToSide(engine, observerSide, s)) {
                continue;
            }
            float fp = Math.max(1f, ShipCapabilityProfile.resolveShipFpStrength(s));
            float shipThreat = classifyShipCombatThreatWeight(s);
            weightedSum += fp * shipThreat;
            fpSum += fp;
        }
        if (fpSum <= 0f) return 1.0f;
        return Math.max(0.25f, Math.min(1.0f, weightedSum / fpSum));
    }

    private static float classifyShipCombatThreatWeight(ShipAPI s) {
        if (s == null) return 1.0f;
        boolean civ = (s.getVariant() != null && s.getVariant().isCivilian())
                || (s.getHullSpec() != null && s.getHullSpec().isCivilianNonCarrier());
        String hullId = "";
        if (s.getHullSpec() != null && s.getHullSpec().getHullId() != null) {
            hullId = s.getHullSpec().getHullId().toLowerCase();
        } else if (s.getName() != null) {
            hullId = s.getName().toLowerCase();
        }
        boolean isConvertedOrArmedFreighter = hullId.contains("wayfarer")
                || hullId.contains("mule")
                || hullId.contains("colossus")
                || hullId.contains("buffalo")
                || hullId.contains("hound")
                || hullId.contains("cerberus")
                || hullId.contains("shepherd")
                || hullId.contains("kite")
                || hullId.contains("tarsus")
                || hullId.contains("gemini")
                || hullId.contains("valkyrie")
                || hullId.contains("phaeton")
                || hullId.contains("dram")
                || hullId.contains("starliner")
                || hullId.contains("nebula")
                || hullId.contains("mudskipper")
                || hullId.contains("hermes")
                || hullId.contains("mercury")
                || hullId.contains("salvage")
                || hullId.contains("crig");
        if (civ) return 0.28f;
        if (isConvertedOrArmedFreighter) return 0.42f;
        if (s.isCapital() || s.isCruiser()) return 1.0f;
        if (s.isDestroyer()) return 0.92f;
        return 0.78f;
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
        int ableVisibleEnemyCount = 0;
        for (DivisionState eDiv : enemyGroup.divisions) {
            if (eDiv.isAbleCombatEffective() && isDivisionVisibleToSide(engine, myGroup.side, eDiv)) {
                anyAbleVisibleEnemy = true;
                ableVisibleEnemyCount++;
            }
        }

        for (DivisionState eDiv : enemyGroup.divisions) {
            if (!eDiv.isCombatEffective()) continue;
            if (!isDivisionVisibleToSide(engine, myGroup.side, eDiv)) continue;
            Vector2f visCentroid = getVisibleCentroidForSide(engine, myGroup.side, eDiv);
            float rawVisFp = getVisibleFpForSide(engine, myGroup.side, eDiv);
            float threatWeight = Math.max(0.28f, eDiv.combatThreatWeight);
            float visFp = rawVisFp * threatWeight;
            float dist = (anchorDiv != null)
                    ? DivisionUiWidgets.distance(anchorDiv.centroid, visCentroid)
                    : DivisionUiWidgets.distance(myGroup.fleetCentroid, visCentroid);
            float auxPenalty = (eDiv.archetype == DivisionArchetype.SERVICE_GROUP || threatWeight <= 0.52f) ? -520f : 0f;
            // Item F: Heavily deprioritize retreating ("unable") enemy divisions as long as able enemy divisions remain
            float unablePenalty = (anyAbleVisibleEnemy && (eDiv.isUnableDivision || eDiv.retreatingFraction >= 0.5f)) ? -1400f : 0f;
            // Item D: Bonus for enemy divisions pinched against a map edge or corner (restricted maneuver options)
            float edgePinchBonus = BattlefieldFirepowerEvaluator.computeEdgeProximityPinch(engine, visCentroid, eDiv.freeTimeRadius)
                    * 220f * threatWeight;

            // Enemy Center of Gravity Alignment: Main Effort fixes the Enemy Center of Gravity, never a stray flank pack!
            float cogBonus = 0f;
            if (myGroup.hasEnemyCenterOfGravity && ableVisibleEnemyCount >= 2) {
                float distToCog = DivisionUiWidgets.distance(visCentroid, myGroup.enemyCenterOfGravity);
                cogBonus = Math.max(-460f, 260f - distToCog * 0.18f);
            }

            // OTC Overkill Veto for Primary Threat:
            // If non-Main-Effort friendly divisions already commit >= eDiv.overkillCapFp against this division
            // while another visible able enemy division is NOT overkilled, Main Effort must NOT pile on!
            float nonMainCommittedFp = 0f;
            for (DivisionState myDiv : myGroup.divisions) {
                if (myDiv == null || !myDiv.isAbleCombatEffective() || myDiv == anchorDiv) continue;
                DivisionState tgt = (myDiv.otcOrder != null && myDiv.otcOrder.targetEnemy != null)
                        ? myDiv.otcOrder.targetEnemy
                        : myDiv.targetEnemyDivision;
                if (tgt == eDiv) {
                    nonMainCommittedFp += myDiv.totalFpStrength;
                }
            }
            float overkillPrimaryPenalty = (ableVisibleEnemyCount >= 2 && nonMainCommittedFp >= eDiv.overkillCapFp)
                    ? -1600f
                    : 0f;

            // Item D (Independent Ship Disposition Alignment):
            // When our fleet has a substantial independent ship component (organizationRatio < 0.75),
            // strongly favor the enemy division/cluster that our friendly independent ships are already engaging or
            // closing upon so our organized divisions concentrate fire with our independents instead of fighting a separate war!
            float indepAlignmentBonus = 0f;
            if (myGroup.friendlyIndependentFp >= 12f && myGroup.organizationRatio < 0.78f) {
                float dIndep = DivisionUiWidgets.distance(myGroup.independentProjectedCentroid, visCentroid);
                float indepWeight = Math.min(1.0f, (0.85f - myGroup.organizationRatio) * 1.25f);
                if (dIndep <= 1850f) {
                    indepAlignmentBonus = (1850f - dIndep) * 0.34f * indepWeight;
                } else {
                    indepAlignmentBonus = -(dIndep - 1850f) * 0.22f * indepWeight;
                }
            }

            // Frontline proximity & active-fire bonus: measure from Main Effort and heavy battleline divisions
            // (Battleship/Cruiser/Strike Cruiser) rather than from a lone scouting frigate 3,500u away on a flank!
            float minDivDist = dist;
            for (DivisionState myDiv : myGroup.divisions) {
                if (!myDiv.isAbleCombatEffective()) continue;
                boolean isHeavyBattleline = (myDiv == anchorDiv)
                        || myDiv.archetype == DivisionArchetype.BATTLESHIP_DIVISION
                        || myDiv.archetype == DivisionArchetype.MISSILE_BATTLESHIP_DIVISION
                        || myDiv.archetype == DivisionArchetype.CRUISER_DIVISION
                        || myDiv.archetype == DivisionArchetype.MISSILE_CRUISER_DIVISION
                        || myDiv.archetype == DivisionArchetype.STRIKE_CRUISER_DIVISION;
                if (!isHeavyBattleline && anchorDiv != null) continue;
                float dd = DivisionUiWidgets.distance(myDiv.centroid, visCentroid);
                if (dd < minDivDist) minDivDist = dd;
            }
            float frontlineContactBonus = (minDivDist <= 1450f ? (1450f - minDivDist) * 0.32f : 0f)
                    + (eDiv.inRangeFirepowerScore > 25f ? 180f * threatWeight : 0f);
            // Item F Defensive Counter: if an enemy division is suspected of feigning retreat, discount its lure priority
            float feignPenalty = (eDiv.enemyFeignedRetreatSuspicion >= 0.60f) ? -320f : 0f;

            float score;
            if (myGroup.useConvergence) {
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
                score = visFp * 9.5f + avgConv * 0.55f + collapseBonus + auxPenalty + unablePenalty
                        + edgePinchBonus + cogBonus + overkillPrimaryPenalty
                        + indepAlignmentBonus + frontlineContactBonus + feignPenalty - minDivDist * 0.52f;
            } else {
                // Human OTC decision-making: weights Firepower value, Space Strongpoint/Weak Point seams, Time pinning, and Map-Edge pinching.
                // Multi-division target synthesis scales smoothly with the square of organizationRatio (the fraction of the fleet in divisions).
                float orgC2Scale = Math.max(0f, Math.min(1f, (myGroup.organizationRatio - 0.93f) / 0.07f));
                float convSum = 0f;
                int convCount = 0;
                for (DivisionState d : myGroup.divisions) {
                    if (!d.isAbleCombatEffective()) continue;
                    convSum += BattlefieldFirepowerEvaluator.computeConvergenceTargetScore(engine, d, eDiv, myGroup);
                    convCount++;
                }
                float avgConv = (convCount > 0) ? (convSum / convCount) * orgC2Scale : 0f;
                boolean isIndepCluster = eDiv.divisionNumber >= 90;
                float isolationBonus = (!isIndepCluster && eDiv.stationKeepingRatio > 1.45f) ? 380f * threatWeight : 0f;
                float spaceSeamBonus = eDiv.isWeakPoint ? 240f : (eDiv.isStrongpoint ? -120f : 0f);
                float pinnedBonus = eDiv.engagementLevel * 180f * threatWeight;
                float collapseBonus = (visFp >= 18f)
                        ? (eDiv.avgFluxLevel * 360f + (1.0f - eDiv.avgHullLevel) * 320f)
                        : 0f;
                score = visFp * 9.5f + avgConv * 0.65f + isolationBonus + spaceSeamBonus + pinnedBonus + collapseBonus + edgePinchBonus
                        + cogBonus + overkillPrimaryPenalty
                        + indepAlignmentBonus + frontlineContactBonus + feignPenalty + auxPenalty + unablePenalty
                        - minDivDist * 0.52f;
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
        if (!a.friendlyIndependentShips.isEmpty()) {
            for (DivisionState eDiv : b.divisions) {
                if (!eDiv.isCombatEffective() || !isDivisionVisibleToSide(engine, a.side, eDiv)) continue;
                Vector2f visCentroid = getVisibleCentroidForSide(engine, a.side, eDiv);
                float d = DivisionUiWidgets.distance(a.independentCentroid, visCentroid);
                if (d < minDist) minDist = d;
            }
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
            float d = DivisionUiWidgets.distance(myDiv.centroid, visCentroid);
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

}
