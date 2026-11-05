package divisionai;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import divisionai.DivisionModel.BattlePhase;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.OtcOrderType;
import divisionai.DivisionModel.OtcOrder;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionIntentType;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionModel.ShipRankState;
import divisionai.DivisionModel.StrikePlan;
import divisionai.PermanentDivisionManager.DivisionFormation;
import divisionai.PermanentDivisionManager.DivisionMovementStickiness;
import divisionai.PermanentDivisionManager.DivisionSetpieceAttack;
import divisionai.PermanentDivisionManager.FleetWideFormation;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.OtcPlay;
import divisionai.PermanentDivisionManager.LineStation;
import divisionai.PermanentDivisionManager.SquadronFormation;
import divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode;
import divisionai.PermanentDivisionManager.VoidstrikeTargetObservance;
import org.lwjgl.util.vector.Vector2f;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/**
 * Naval Formation Slot Geometry, Tactical Station Calculators (Standoff, Reinforce, Screening, Flank),
 * and Out-of-Contact Wide-Arc Route Planner for {@link DivisionCommanderAI}.
 */
public class DivisionFormationNavigator {

    static Vector2f computeShipFormationSlot(
            CombatEngineAPI engine,
            ShipAPI ship,
            int shipIdx,
            List<ShipAPI> alive,
            DivisionState div,
            BattleGroupState myGroup) {

        boolean closingUp = (div.currentIntent == DivisionIntentType.CLOSE_UP);
        float baseSpacing = Math.max(150f, div.stationSpacing);
        float effectiveSlotSpacing = closingUp ? Math.max(140f, baseSpacing * 0.60f) : baseSpacing;

        Vector2f rawCenter;
        if (closingUp) {
            rawCenter = (div.flagship != null && div.flagship.isAlive() && !div.flagship.isHulk()
                    && !div.detachedStragglerIds.contains(div.flagship.getId()))
                    ? div.flagship.getLocation()
                    : div.centroid;
        } else if (div.hasRouteWaypoint) {
            rawCenter = div.routeWaypoint;
        } else {
            rawCenter = div.stationPoint;
        }

        float centerX = rawCenter.x;
        float centerY = rawCenter.y;

        // Moving Formation Carriage: when a division in a structured formation is marching toward a distant
        // objective or station (e.g. right after deploying as a solid block), project the formation carriage
        // a bounded lead distance ahead of the division's core centroid along the march vector so ships
        // immediately fan out laterally into their Wedge / Semicircle / Line Abreast / Echelon while advancing!
        DivisionFormation formation = div.activeFormation != null
                ? div.activeFormation
                : (div.doctrinalFormation != null ? div.doctrinalFormation : DivisionFormation.LINE_ABREAST);
        float marchDirX = 0f;
        float marchDirY = 0f;
        float marchDist = 0f;
        if (div.centroid != null && (Math.abs(div.centroid.x) > 1f || Math.abs(div.centroid.y) > 1f)) {
            float gdx = centerX - div.centroid.x;
            float gdy = centerY - div.centroid.y;
            marchDist = (float) Math.sqrt(gdx * gdx + gdy * gdy);
            if (marchDist > 1f) {
                marchDirX = gdx / marchDist;
                marchDirY = gdy / marchDist;
            }
        }
        if (!closingUp
                && !div.hasRouteWaypoint
                && formation != DivisionFormation.NONE
                && alive.size() >= 2
                && (div.currentIntent == DivisionIntentType.TAKE_AND_HOLD
                || div.currentIntent == DivisionIntentType.CLOSE_AND_ENGAGE
                || div.currentIntent == DivisionIntentType.FORM_LINE_AND_FIX)
                && marchDist > 1f) {
            // Only use the short 680u cautious step on TAKE_AND_HOLD when advancing into enemy-controlled space
            // (Station assault, Pursuit battle, or Enemy Space on the objective) or under a Cautious commander.
            // During CLOSE_AND_ENGAGE, FORM_LINE_AND_FIX, and standard Meeting Engagement TAKE_AND_HOLD, project the
            // carriage >= 1950u ahead so rear Wedge/Column ranks never fall at/behind div.centroid and ships never
            // stall inside vanilla BasicShipAI's < 450u CAMP_LOCATION braking zone!
            boolean cautiousObjectiveStep = (div.currentIntent == DivisionIntentType.TAKE_AND_HOLD)
                    && DivisionCommanderAI.shouldAdvanceCautiouslyOnObjective(engine, div, myGroup, null);
            boolean enteringEnemyContact = div.targetEnemyDivision != null
                    && div.targetEnemyDivision.isCombatEffective()
                    && (div.localEnemyStrength >= 4f || marchDist <= 1450f);
            float maxCarriageLead = cautiousObjectiveStep
                    ? Math.max(850f, effectiveSlotSpacing * 2.10f)
                    : (enteringEnemyContact
                            ? Math.max(720f, effectiveSlotSpacing * 2.25f)
                            : Math.max(1950f, effectiveSlotSpacing * 4.0f));
            if (marchDist > maxCarriageLead) {
                centerX = div.centroid.x + marchDirX * maxCarriageLead;
                centerY = div.centroid.y + marchDirY * maxCarriageLead;
            }
        }

        // Whole-division inter-division spacing & main-line clearance (applied to the division center as a unit,
        // never pushing individual ships of the same division in opposite directions!
        // CRITICAL: When the division is advancing toward a target/objective (marchDist > 180f), project out any
        // backward component along marchDir so friendly divisions ONLY separate laterally and NEVER push a division's
        // carriage backward onto its own centroid, which previously froze Missile Frigate Div 2 in the middle of battle!)
        if (!closingUp && myGroup != null) {
            float desiredInter = Math.max(400f, div.divisionInterval);
            for (DivisionState peerDiv : myGroup.divisions) {
                if (peerDiv == null || peerDiv == div || !peerDiv.isCombatEffective()) continue;
                // Do not repel from the friendly division we are actively screening, reinforcing, or venting behind
                if (peerDiv == div.targetFriendlyDivision) continue;
                float minInter = Math.max(desiredInter, peerDiv.divisionInterval) * 0.85f;
                float idx = div.centroid.x - peerDiv.centroid.x;
                float idy = div.centroid.y - peerDiv.centroid.y;
                float iDist = (float) Math.sqrt(idx * idx + idy * idy);
                if (iDist > 1f && iDist < minInter) {
                    float push = Math.min(420f, (minInter - iDist) * 0.55f);
                    float px = (idx / iDist) * push;
                    float py = (idy / iDist) * push;
                    if (marchDist > 180f) {
                        float dot = px * marchDirX + py * marchDirY;
                        if (dot < 0f) {
                            px -= dot * marchDirX;
                            py -= dot * marchDirY;
                        }
                    }
                    centerX += px;
                    centerY += py;
                }
            }

            // Anti-balling with main battle line: shift Frigate & Phase division center clear of heavy main-line centroid
            if ((div.archetype == DivisionArchetype.FRIGATE_DIVISION
                    || div.archetype == DivisionArchetype.PHASE_DIVISION)
                    && myGroup.mainEffortDivision != null
                    && myGroup.mainEffortDivision != div
                    && myGroup.mainEffortDivision != div.targetFriendlyDivision
                    && myGroup.mainEffortDivision.isCombatEffective()) {
                Vector2f mainPos = myGroup.mainEffortDivision.centroid;
                float mdx = div.centroid.x - mainPos.x;
                float mdy = div.centroid.y - mainPos.y;
                float mDist = (float) Math.sqrt(mdx * mdx + mdy * mdy);
                float minClearance = Math.max(550f, Math.max(div.divisionInterval * 0.80f, div.standoffRange * 0.75f));
                if (mDist > 1f && mDist < minClearance) {
                    float push = Math.min(500f, (minClearance - mDist) * 0.70f);
                    float px = (mdx / mDist) * push;
                    float py = (mdy / mDist) * push;
                    if (marchDist > 180f) {
                        float dot = px * marchDirX + py * marchDirY;
                        if (dot < 0f) {
                            px -= dot * marchDirX;
                            py -= dot * marchDirY;
                        }
                    }
                    centerX += px;
                    centerY += py;
                }
            }
        }

        boolean divisionInEntryTravelDrive = false;
        for (int i = 0; i < alive.size(); i++) {
            ShipAPI m = alive.get(i);
            if (m != null && m.getFullTimeDeployed() < 8.5f && m.getTravelDrive() != null && m.getTravelDrive().isActive()) {
                divisionInEntryTravelDrive = true;
                break;
            }
        }

        float desiredFwdX = 0f;
        float desiredFwdY = (div.side == 0) ? 1f : -1f;
        if (!divisionInEntryTravelDrive) {
            if (div.targetEnemyDivision != null) {
                Vector2f visEnemy = OtcAI.getVisibleCentroidForSide(engine, div.side, div.targetEnemyDivision);
                float dx = visEnemy.x - div.centroid.x;
                float dy = visEnemy.y - div.centroid.y;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 1f) {
                    desiredFwdX = dx / len;
                    desiredFwdY = dy / len;
                }
            } else if (div.centroid != null && rawCenter != null) {
                // When advancing on an objective or station before enemy contact, orient formation heading along the march vector
                float dx = rawCenter.x - div.centroid.x;
                float dy = rawCenter.y - div.centroid.y;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 220f) {
                    desiredFwdX = dx / len;
                    desiredFwdY = dy / len;
                }
            }
        }

        // Smoothly wheel the division's formation heading so the line wheels as a cohesive naval unit.
        // Item A: Use a faster wheel rate (0.50) when marching out of combat toward an opening objective so
        // Line Abreast and Echelon Right immediately align perpendicular/echeloned to the march vector!
        if (!div.formationHeadingInitialized || divisionInEntryTravelDrive) {
            div.formationHeadingX = desiredFwdX;
            div.formationHeadingY = desiredFwdY;
            div.formationHeadingInitialized = true;
        } else if (shipIdx == 0) {
            float blend = (div.targetEnemyDivision == null) ? 0.50f : 0.28f;
            float bx = div.formationHeadingX * (1f - blend) + desiredFwdX * blend;
            float by = div.formationHeadingY * (1f - blend) + desiredFwdY * blend;
            float bLen = (float) Math.sqrt(bx * bx + by * by);
            if (bLen > 0.001f) {
                div.formationHeadingX = bx / bLen;
                div.formationHeadingY = by / bLen;
            }
        }

        float fwdX = div.formationHeadingX;
        float fwdY = div.formationHeadingY;
        // Lateral axis perpendicular to forward threat vector (+lat = starboard / right of heading, matching computeFormationSlotPositionForAnchor)
        float latX = fwdY;
        float latY = -fwdX;

        // Station in Line depth bias (Van / Center / Rear) relative to the main battle line when marching without a
        // standoff-computed enemy station (never double-apply VAN +220u on top of computeStandoffStation, which previously
        // pushed VAN Destroyer divisions 350u inside heavy cruiser gunlines!).
        float stationDepthBias = 0f;
        LineStation lineStation = (div.lineStation != null) ? div.lineStation : LineStation.CENTER;
        if (!closingUp
                && div.targetEnemyDivision == null
                && div.currentIntent != DivisionIntentType.HOLD_IN_RESERVE
                && div.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT
                && div.currentIntent != DivisionIntentType.BREAK_OFF_AND_RETIRE) {
            if (lineStation == LineStation.VAN) {
                stationDepthBias = 220f;
            } else if (lineStation == LineStation.REAR) {
                stationDepthBias = -320f;
            }
        }

        float anchorX = divisionInEntryTravelDrive && div.centroid != null
                ? div.centroid.x
                : (centerX + fwdX * stationDepthBias);
        float anchorY = divisionInEntryTravelDrive && div.centroid != null
                ? (div.centroid.y + fwdY * 1800f)
                : (centerY + fwdY * stationDepthBias);

        int n = Math.max(1, alive.size());
        float[] slotLx = new float[n];
        float[] slotLy = new float[n];
        float sumSlotLx = 0f;
        float sumSlotLy = 0f;
        for (int k = 0; k < n; k++) {
            float[] local = computeLocalFormationOffset(formation, k, n, effectiveSlotSpacing);
            slotLx[k] = local[0];
            slotLy[k] = local[1];
            sumSlotLx += local[0];
            sumSlotLy += local[1];
        }
        float meanSlotLx = sumSlotLx / n;
        float meanSlotLy = sumSlotLy / n;

        Vector2f[] worldSlots = new Vector2f[n];
        // Also compute zero-centered formation slots anchored on the division's current centroid so greedy
        // wingman-to-slot matching and rank-dressing use the formation's true centroid offsets (essential for
        // REVERSE_SEMICIRCLE and REVERSE_WEDGE where all raw slotLy values are negative!).
        float curCenterX = (div.centroid != null) ? div.centroid.x : anchorX;
        float curCenterY = (div.centroid != null) ? div.centroid.y : anchorY;
        Vector2f[] currentCentroidSlots = new Vector2f[n];
        for (int k = 0; k < n; k++) {
            float lx = slotLx[k];
            float ly = slotLy[k];
            worldSlots[k] = new Vector2f(
                    anchorX + latX * lx + fwdX * ly,
                    anchorY + latY * lx + fwdY * ly);
            currentCentroidSlots[k] = new Vector2f(
                    curCenterX + latX * lx + fwdX * ly,
                    curCenterY + latY * lx + fwdY * ly);
        }

        // Permutation-free slot assignment:
        //  - Slot 0 is the Flagship Command Slot (occupied by div.flagship if alive in `alive`, else alive.get(0)).
        //  - Remaining wingmen are matched to slots 1..n-1 greedily by minimum distance so wingmen never criss-cross.
        int leadIdx = 0;
        if (div.flagship != null) {
            for (int i = 0; i < n; i++) {
                if (alive.get(i) == div.flagship) {
                    leadIdx = i;
                    break;
                }
            }
        }

        int assignedSlotIdx = 0;
        boolean shipInSafeReliefRank = false;
        boolean isCircularFormation = (formation == DivisionFormation.CIRCLE
                || formation == DivisionFormation.PROTECTIVE_CIRCLE);
        // Intra-Division Formation Rank & Depth:
        // Defensive: compress frontage and add a rear relief rank (front ships cover high-flux ships venting).
        // Offensive: spearhead a deep 1-2 wide column through a narrow enemy gap so rear ships keep firing over the front.
        if (!closingUp
                && div.inDoubleLineRelief
                && !isCircularFormation
                && n >= 2
                && div.engagementZoneShipCount >= 1
                && div.safeZoneShipCount >= 1
                && div.engagementZoneShipCount + div.safeZoneShipCount == n) {
            int frontCount = div.engagementZoneShipCount;
            int reliefCount = div.safeZoneShipCount;
            float reliefDepth = Math.max(440f, div.reliefRankDepth);
            for (int fIdx = 0; fIdx < frontCount; fIdx++) {
                float[] local = computeLocalFormationOffset(formation, fIdx, frontCount, effectiveSlotSpacing);
                slotLx[fIdx] = local[0];
                slotLy[fIdx] = local[1] + 35f;
                worldSlots[fIdx] = new Vector2f(
                        anchorX + latX * slotLx[fIdx] + fwdX * slotLy[fIdx],
                        anchorY + latY * slotLx[fIdx] + fwdY * slotLy[fIdx]);
            }
            for (int rIdx = 0; rIdx < reliefCount; rIdx++) {
                int slotK = frontCount + rIdx;
                float rLat = (reliefCount == 1)
                        ? 0f
                        : ((rIdx - (reliefCount - 1) * 0.5f) * (effectiveSlotSpacing * 0.82f));
                float rDepth = -reliefDepth;
                slotLx[slotK] = rLat;
                slotLy[slotK] = rDepth;
                worldSlots[slotK] = new Vector2f(
                        curCenterX + latX * rLat + fwdX * rDepth,
                        curCenterY + latY * rLat + fwdY * rDepth);
            }

            boolean[] slotTaken = new boolean[n];
            for (int i = 0; i < n; i++) {
                ShipAPI s = alive.get(i);
                ShipRankState rs = (s != null && s.getId() != null)
                        ? div.shipRankStateByShipId.get(s.getId())
                        : ShipRankState.FRONT_ENGAGEMENT;
                boolean isRelief = (rs == ShipRankState.RELIEF_SAFE);
                int startK = isRelief ? frontCount : 0;
                int endK = isRelief ? n : frontCount;
                int bestSlot = startK;
                float bestDistSq = Float.MAX_VALUE;
                for (int k = startK; k < endK; k++) {
                    if (slotTaken[k]) continue;
                    float dx = s.getLocation().x - worldSlots[k].x;
                    float dy = s.getLocation().y - worldSlots[k].y;
                    float dSq = dx * dx + dy * dy;
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        bestSlot = k;
                    }
                }
                slotTaken[bestSlot] = true;
                if (i == shipIdx) {
                    assignedSlotIdx = bestSlot;
                    shipInSafeReliefRank = isRelief;
                }
            }
        } else if (!closingUp
                && isCircularFormation
                && div.cantabrianCircleActive
                && n >= 3
                && ship != null) {
            // Cantabrian Circle (offensive / defensive): continuous orbit of the division's circle stations around
            // the core anchor. Each ship advances along the tangent of the ring so the whole division keeps moving,
            // firing outward (offensive) or trading places between the exposed outer arc and the safe inner arc (defensive).
            Vector2f baseSlot = computeProtectiveOrCircularSlot(formation, shipIdx, alive, div, anchorX, anchorY, fwdX, fwdY, latX, latY, effectiveSlotSpacing);
            float relX = baseSlot.x - curCenterX;
            float relY = baseSlot.y - curCenterY;
            float rLen = (float) Math.sqrt(relX * relX + relY * relY);
            float cantX = baseSlot.x;
            float cantY = baseSlot.y;
            if (rLen > 40f) {
                float phase = div.cantabrianPhase;
                float cosP = (float) Math.cos(phase);
                float sinP = (float) Math.sin(phase);
                float rotX = relX * cosP - relY * sinP;
                float rotY = relX * sinP + relY * cosP;
                float tangSign = div.cantabrianOffensive ? 1f : -1f;
                float tangentLead = effectiveSlotSpacing * 0.55f;
                cantX = curCenterX + rotX - (rotY / rLen) * tangSign * tangentLead;
                cantY = curCenterY + rotY + (rotX / rLen) * tangSign * tangentLead;
            }
            assignedSlotIdx = shipIdx;
            worldSlots[assignedSlotIdx].set(cantX, cantY);
        } else if (formation == DivisionFormation.PROTECTIVE_CIRCLE && n > 1) {
            // Protective Circle: assign the largest / carrier / flagship hulls to the inner core slots (0 .. coreCount-1)
            // and match smaller escort hulls (frigates/destroyers) to the outer protective circle slots (coreCount .. n-1).
            int coreCount = computeProtectiveCircleCoreCount(n);
            boolean[] isCoreMember = new boolean[n];
            for (int c = 0; c < coreCount; c++) {
                int bestShipIdx = -1;
                float bestPrio = -Float.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    if (isCoreMember[i]) continue;
                    float prio = computeProtectiveCircleCorePriority(alive.get(i), div);
                    if (prio > bestPrio) {
                        bestPrio = prio;
                        bestShipIdx = i;
                    }
                }
                if (bestShipIdx >= 0) {
                    isCoreMember[bestShipIdx] = true;
                }
            }
            boolean[] slotTaken = new boolean[n];
            if (isCoreMember[shipIdx]) {
                int coreSlotCursor = 0;
                for (int i = 0; i < n; i++) {
                    if (!isCoreMember[i]) continue;
                    int slot = Math.min(coreCount - 1, coreSlotCursor++);
                    slotTaken[slot] = true;
                    if (i == shipIdx) {
                        assignedSlotIdx = slot;
                        break;
                    }
                }
            } else {
                for (int c = 0; c < coreCount; c++) {
                    slotTaken[c] = true;
                }
                for (int i = 0; i < n; i++) {
                    if (isCoreMember[i]) continue;
                    ShipAPI s = alive.get(i);
                    int bestSlot = coreCount;
                    float bestDistSq = Float.MAX_VALUE;
                    for (int k = coreCount; k < n; k++) {
                        if (slotTaken[k]) continue;
                        float dx = s.getLocation().x - worldSlots[k].x;
                        float dy = s.getLocation().y - worldSlots[k].y;
                        float dSq = dx * dx + dy * dy;
                        if (dSq < bestDistSq) {
                            bestDistSq = dSq;
                            bestSlot = k;
                        }
                    }
                    slotTaken[bestSlot] = true;
                    if (i == shipIdx) {
                        assignedSlotIdx = bestSlot;
                        break;
                    }
                }
            }
        } else if (shipIdx != leadIdx && n > 1) {
            // Persistent station-keeping assignment: a formation is a set of stations that ships hold; they only
            // swap stations when the formation itself changes or a swap is clearly worth it (see resolvePersistentSlots).
            int[] slotByShip = resolvePersistentSlots(div, alive, leadIdx, currentCentroidSlots, formation);
            assignedSlotIdx = slotByShip[shipIdx];
        }

                float slotX = worldSlots[assignedSlotIdx].x;
                float slotY = worldSlots[assignedSlotIdx].y;
                boolean outOfCombatMarch = isDivisionInOutOfCombatMarch(engine, div);

                // Item A & Mixed-Division Sustainable Speed Pacing: Longitudinal & Lateral Formation Dressing while Marching!
                // Out of combat, mixed divisions (e.g. Battle Division 1 with ships of different top speeds) must move at
                // the greatest sustainable speed that ALL ships in the division can maintain, anchoring the formation rank
                // to the slowest core member so faster ships never outrun slower ships during the opening advance!
                // Once combat opens up (outOfCombatMarch == false), all bets are off and ships use their full combat speed.
                if (!closingUp
                && !shipInSafeReliefRank
                && !div.inDoubleLineRelief
                && formation != DivisionFormation.NONE
                && n >= 2
                && marchDist > 220f
                && (div.currentIntent == DivisionIntentType.TAKE_AND_HOLD
                || div.currentIntent == DivisionIntentType.CLOSE_AND_ENGAGE
                || div.currentIntent == DivisionIntentType.FORM_LINE_AND_FIX)) {
                ShipAPI refShip = (div.flagship != null && div.flagship.isAlive() && !div.flagship.isHulk()
                && !div.detachedStragglerIds.contains(div.flagship.getId()))
                ? div.flagship : null;
                float refX = (refShip != null)
                ? (refShip.getLocation().x * 0.55f + curCenterX * 0.45f)
                : curCenterX;
                float refY = (refShip != null)
                ? (refShip.getLocation().y * 0.55f + curCenterY * 0.45f)
                : curCenterY;
                float refOffLx = (refShip != null)
                ? (slotLx[leadIdx] * 0.55f + meanSlotLx * 0.45f)
                : meanSlotLx;
                float refOffLy = (refShip != null)
                ? (slotLy[leadIdx] * 0.55f + meanSlotLy * 0.45f)
                : meanSlotLy;

                // When marching out of combat with mixed ship speeds, anchor the longitudinal reference to the slowest
                // core member's rank progress so fast ships dress onto the slowest ship's sustainable pace!
                float minSpd = computeDivisionSustainableSpeed(div, alive);
                float maxSpd = computeDivisionMaxMemberSpeed(div, alive);
                boolean mixedSpeedOutOfCombat = outOfCombatMarch
                        && (maxSpd - minSpd) > 16f
                        && maxSpd > minSpd * 1.18f;
                if (mixedSpeedOutOfCombat) {
                    int[] allSlots = (n > 1)
                            ? resolvePersistentSlots(div, alive, leadIdx, currentCentroidSlots, formation)
                            : new int[]{0};
                    float refFwdProj = refX * fwdX + refY * fwdY - refOffLy;
                    float slowestRankProj = refFwdProj;
                    for (int mIdx = 0; mIdx < n; mIdx++) {
                        ShipAPI m = alive.get(mIdx);
                        if (m == null || !m.isAlive() || m.isHulk() || m.getLocation() == null) continue;
                        if (div.detachedStragglerIds != null && div.detachedStragglerIds.contains(m.getId())) continue;
                        if (DivisionCommanderAI.distance(m.getLocation(), div.centroid) > div.stationKeepingRadius * 1.35f) continue;
                        int mSlot = (mIdx < allSlots.length) ? allSlots[mIdx] : mIdx;
                        float mRankProj = (m.getLocation().x * fwdX + m.getLocation().y * fwdY) - slotLy[mSlot];
                        if (mRankProj < slowestRankProj) {
                            slowestRankProj = mRankProj;
                        }
                    }
                    float rankPullBack = Math.max(0f, Math.min(340f, refFwdProj - slowestRankProj));
                    refX -= fwdX * rankPullBack;
                    refY -= fwdY * rankPullBack;
                }

                float relSlotLx = slotLx[assignedSlotIdx] - refOffLx;
                float relSlotLy = slotLy[assignedSlotIdx] - refOffLy;
                float trueRankX = refX + latX * relSlotLx + fwdX * relSlotLy;
                float trueRankY = refY + latY * relSlotLx + fwdY * relSlotLy;

                float errX = ship.getLocation().x - trueRankX;
                float errY = ship.getLocation().y - trueRankY;
                float alongError = errX * fwdX + errY * fwdY;
                float latError = Math.abs(errX * latX + errY * latY);

                float alongThreshold = mixedSpeedOutOfCombat ? 15f : 45f;
                if (alongError > alongThreshold) {
                // Ship is ahead of its assigned rank: place its target along its true lateral lane.
                // When pacing a mixed-speed division out of combat, pull aheadOfShipLead tight (45..185u) so BasicShipAI
                // enters its proportional arrival-braking zone and matches the slowest ship's sustainable speed!
                float aheadOfShipLead = mixedSpeedOutOfCombat
                        ? Math.min(marchDist, Math.max(45f, 185f - alongError * 0.85f - latError * 0.15f))
                        : Math.min(marchDist, Math.max(260f, 580f - alongError * 0.45f - latError * 0.20f));
                float dressLead = alongError + aheadOfShipLead;
                if (dressLead < marchDist) {
                slotX = trueRankX + fwdX * dressLead;
                slotY = trueRankY + fwdY * dressLead;
                }
                } else if (latError > 95f) {
                // Ship is laterally out of its lane (e.g. wheeling into NW/NE objective): pull carriage closer along fwd
                // so the lateral steering angle toward its lane is sharp (~30-45 deg) instead of diluted over 1950u!
                float lateralDressLead = Math.max(420f, Math.min(950f, 1150f - latError * 0.85f));
                if (marchDist > lateralDressLead) {
                slotX = trueRankX + fwdX * lateralDressLead;
                slotY = trueRankY + fwdY * lateralDressLead;
                }
                }
                }

        // Intra-division & Inter-division Helmsmanship Anti-Collision:
        // Ensure a ship's station and movement vector never cause it to slam into another friendly ship in the division or fleet!
        float repelThreshold = (formation == DivisionFormation.NONE)
                ? (effectiveSlotSpacing * 0.85f)
                : (effectiveSlotSpacing * 0.48f);
        for (ShipAPI peer : alive) {
            if (peer == null || peer == ship || !peer.isAlive()) continue;
            float pdx = ship.getLocation().x - peer.getLocation().x;
            float pdy = ship.getLocation().y - peer.getLocation().y;
            float pDist = (float) Math.sqrt(pdx * pdx + pdy * pdy);
            if (pDist > 1f && pDist < repelThreshold) {
                float push = (repelThreshold - pDist) * 0.55f;
                slotX += (pdx / pDist) * push;
                slotY += (pdy / pDist) * push;
            }
        }

        Vector2f helmSlot = applyFleetHelmsmanshipVectorAvoidance(
                engine, ship, slotX, slotY, fwdX, fwdY, latX, latY, alive, div, myGroup);
        return helmSlot;
    }

    /**
     * Returns true when {@code div} is marching out of combat (no weapons in range, not taking fire, low engagement level,
     * and enemy main body still outside standoff approach horizon), so mixed-speed divisions pace to their slowest member.
     * Once combat opens up, returns false ("all bets are off").
     */
    public static boolean isDivisionInOutOfCombatMarch(CombatEngineAPI engine, DivisionState div) {
        if (div == null) return false;
        if (div.inRangeFirepowerScore > 0f
                || div.incomingInRangeFirepower > 2f
                || div.engagementLevel >= 0.12f
                || div.localEnemyStrength >= 6f) {
            return false;
        }
        if (div.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                || div.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE
                || div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE
                || div.currentIntent == DivisionIntentType.TOT_MISSILE_STRIKE
                || div.currentIntent == DivisionIntentType.PRESS_HOME) {
            return false;
        }
        if (div.targetEnemyDivision != null && div.targetEnemyDivision.isCombatEffective() && div.centroid != null) {
            Vector2f visE = OtcAI.getVisibleCentroidForSide(engine, div.side, div.targetEnemyDivision);
            float distE = DivisionCommanderAI.distance(div.centroid, visE);
            if (distE <= Math.max(1450f, div.standoffRange * 1.30f)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Computes the greatest sustainable speed that ALL non-straggler core members of {@code div} can maintain.
     */
    public static float computeDivisionSustainableSpeed(DivisionState div, List<ShipAPI> alive) {
        if (alive == null || alive.isEmpty()) return 100f;
        float minSpd = Float.MAX_VALUE;
        for (int i = 0; i < alive.size(); i++) {
            ShipAPI s = alive.get(i);
            if (s == null || !s.isAlive() || s.isHulk()) continue;
            if (div != null && div.detachedStragglerIds != null && div.detachedStragglerIds.contains(s.getId())) continue;
            float spd = Math.max(20f, s.getMaxSpeed());
            if (spd < minSpd) {
                minSpd = spd;
            }
        }
        return minSpd < Float.MAX_VALUE ? minSpd : 100f;
    }

    public static float computeDivisionMaxMemberSpeed(DivisionState div, List<ShipAPI> alive) {
        if (alive == null || alive.isEmpty()) return 100f;
        float maxSpd = 20f;
        for (int i = 0; i < alive.size(); i++) {
            ShipAPI s = alive.get(i);
            if (s == null || !s.isAlive() || s.isHulk()) continue;
            if (div != null && div.detachedStragglerIds != null && div.detachedStragglerIds.contains(s.getId())) continue;
            float spd = Math.max(20f, s.getMaxSpeed());
            if (spd > maxSpd) {
                maxSpd = spd;
            }
        }
        return maxSpd;
    }

    /**
     * Fleet-wide Helmsmanship Anti-Collision: inspects both intra-division peers and friendly ships in other divisions
     * across {@code myGroup}. If another friendly ship sits on {@code (slotX, slotY)} or lies directly in {@code ship}'s
     * travel chord / velocity vector toward {@code (slotX, slotY)}, deflects the slot laterally around the friendly hull
     * and prevents rear-ending slower friendly ships!
     */
    private static Vector2f applyFleetHelmsmanshipVectorAvoidance(
            CombatEngineAPI engine,
            ShipAPI ship,
            float slotX,
            float slotY,
            float fwdX,
            float fwdY,
            float latX,
            float latY,
            List<ShipAPI> alive,
            DivisionState div,
            BattleGroupState myGroup) {
        if (ship == null || ship.getLocation() == null) {
            return new Vector2f(slotX, slotY);
        }
        float sx = ship.getLocation().x;
        float sy = ship.getLocation().y;
        float myR = Math.max(40f, ship.getCollisionRadius());
        float toSlotX = slotX - sx;
        float toSlotY = slotY - sy;
        float distToSlot = (float) Math.sqrt(toSlotX * toSlotX + toSlotY * toSlotY);
        float dirX = distToSlot > 1f ? (toSlotX / distToSlot) : fwdX;
        float dirY = distToSlot > 1f ? (toSlotY / distToSlot) : fwdY;
        float perpX = -dirY;
        float perpY = dirX;

        float vx = (ship.getVelocity() != null) ? ship.getVelocity().x : 0f;
        float vy = (ship.getVelocity() != null) ? ship.getVelocity().y : 0f;

        if (myGroup != null && myGroup.divisions != null) {
            for (int dIdx = 0; dIdx < myGroup.divisions.size(); dIdx++) {
                DivisionState otherDiv = myGroup.divisions.get(dIdx);
                if (otherDiv == null) continue;
                List<ShipAPI> members = otherDiv.getAliveMembers();
                for (int i = 0; i < members.size(); i++) {
                    ShipAPI other = members.get(i);
                    if (other == null || other == ship || !other.isAlive() || other.isHulk() || other.isFighter() || other.getLocation() == null) {
                        continue;
                    }
                    boolean sameDiv = (otherDiv == div);
                    float otherR = Math.max(40f, other.getCollisionRadius());
                    float combinedR = myR + otherR;
                    float ox = other.getLocation().x;
                    float oy = other.getLocation().y;
                    float ovx = (other.getVelocity() != null) ? other.getVelocity().x : 0f;
                    float ovy = (other.getVelocity() != null) ? other.getVelocity().y : 0f;

                    // 1. Direct inter-division hull clearance & slot de-confliction
                    if (!sameDiv) {
                        float dsx = sx - ox;
                        float dsy = sy - oy;
                        float curDist = (float) Math.sqrt(dsx * dsx + dsy * dsy);
                        float hullSafeDist = combinedR * 1.22f + 45f;
                        if (curDist > 1f && curDist < hullSafeDist) {
                            float push = (hullSafeDist - curDist) * 0.80f;
                            slotX += (dsx / curDist) * push;
                            slotY += (dsy / curDist) * push;
                        }

                        float slotDx = slotX - ox;
                        float slotDy = slotY - oy;
                        float slotDist = (float) Math.sqrt(slotDx * slotDx + slotDy * slotDy);
                        float minSlotClearance = combinedR * 1.18f + 45f;
                        if (slotDist > 1f && slotDist < minSlotClearance) {
                            float push = (minSlotClearance - slotDist) * 0.80f;
                            slotX += (slotDx / slotDist) * push;
                            slotY += (slotDy / slotDist) * push;
                        }
                    }

                    // 2. True Velocity-Vector Closest-Point-of-Approach (CPA) Helmsmanship Avoidance:
                    //    If our relative velocity vector (V_my - V_other) is closing on `other` and will bring our
                    //    hulls within safe clearance inside the next 2.6 seconds, deflect our slot laterally away from
                    //    the collision trajectory!
                    float rvx = vx - ovx;
                    float rvy = vy - ovy;
                    float relSpdSq = rvx * rvx + rvy * rvy;
                    if (relSpdSq > 144f) { // > 12 su/s relative speed
                        float relOx = ox - sx;
                        float relOy = oy - sy;
                        float tca = (relOx * rvx + relOy * rvy) / relSpdSq;
                        if (tca > 0f && tca < 2.6f) {
                            float cpaX = relOx - rvx * tca;
                            float cpaY = relOy - rvy * tca;
                            float cpaDist = (float) Math.sqrt(cpaX * cpaX + cpaY * cpaY);
                            float safePassDist = combinedR * 1.15f + 38f;
                            if (cpaDist < safePassDist) {
                                float relSpd = (float) Math.sqrt(relSpdSq);
                                float rPerpX = -rvy / relSpd;
                                float rPerpY = rvx / relSpd;
                                float latSigned = relOx * rPerpX + relOy * rPerpY;
                                float avoidSign = (latSigned >= 0f) ? -1f : 1f;
                                float latDeflect = (safePassDist - cpaDist) * 1.25f + 40f;
                                slotX += rPerpX * avoidSign * latDeflect - (rvx / relSpd) * 35f;
                                slotY += rPerpY * avoidSign * latDeflect - (rvy / relSpd) * 35f;
                            }
                        }
                    }
                }
            }
        }
        return new Vector2f(slotX, slotY);
    }

    private static Vector2f computeProtectiveOrCircularSlot(
            DivisionFormation formation,
            int shipIdx,
            List<ShipAPI> alive,
            divisionai.DivisionModel.DivisionState div,
            float anchorX,
            float anchorY,
            float fwdX,
            float fwdY,
            float latX,
            float latY,
            float spacing) {
        float[] local = computeLocalFormationOffset(formation, shipIdx, Math.max(1, alive.size()), spacing);
        return new Vector2f(
                anchorX + latX * local[0] + fwdX * local[1],
                anchorY + latY * local[0] + fwdY * local[1]);
    }

    /** Minimum total-distance gain (su) required before two wingmen swap stations in an established formation. */
    static final float SLOT_SWAP_GAIN_THRESHOLD = 380f;

    /**
     * Returns slot index per ship index in {@code alive} (slot 0 = flagship command slot). Assignments persist in
     * {@link DivisionState#assignedSlotByShipId}. A full greedy re-solve happens only when the formation type,
     * member count, or flagship changes (or a member has no valid stored slot). Otherwise only pairwise swaps that
     * shorten the pair's combined distance to their stations by more than {@link #SLOT_SWAP_GAIN_THRESHOLD} occur.
     */
    static int[] resolvePersistentSlots(
            DivisionState div,
            List<ShipAPI> alive,
            int leadIdx,
            Vector2f[] slotPositions,
            DivisionFormation formation) {
        int n = alive.size();
        int[] result = new int[n];
        String flagId = (leadIdx >= 0 && leadIdx < n && alive.get(leadIdx) != null) ? alive.get(leadIdx).getId() : null;
        boolean structuralChange = div.lastSlotAssignedFormation != formation
                || div.lastSlotAssignedCount != n
                || (flagId != null && !Integer.valueOf(0).equals(div.assignedSlotByShipId.get(flagId)));

        boolean valid = !structuralChange;
        if (valid) {
            boolean[] seen = new boolean[n];
            for (int i = 0; i < n; i++) {
                ShipAPI s = alive.get(i);
                Integer k = (s != null) ? div.assignedSlotByShipId.get(s.getId()) : null;
                if (k == null || k < 0 || k >= n || seen[k] || (i == leadIdx) != (k == 0)) {
                    valid = false;
                    break;
                }
                seen[k] = true;
                result[i] = k;
            }
        }

        if (!valid) {
            // Greedy nearest-station re-solve (flagship holds slot 0)
            boolean[] slotTaken = new boolean[n];
            slotTaken[0] = true;
            result[leadIdx] = 0;
            for (int i = 0; i < n; i++) {
                if (i == leadIdx) continue;
                ShipAPI s = alive.get(i);
                int bestSlot = -1;
                float bestDistSq = Float.MAX_VALUE;
                for (int k = 1; k < n; k++) {
                    if (slotTaken[k]) continue;
                    float dx = s.getLocation().x - slotPositions[k].x;
                    float dy = s.getLocation().y - slotPositions[k].y;
                    float dSq = dx * dx + dy * dy;
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        bestSlot = k;
                    }
                }
                if (bestSlot < 0) bestSlot = 0;
                slotTaken[bestSlot] = true;
                result[i] = bestSlot;
            }
        } else {
            // Established formation: allow at most one clearly beneficial pairwise swap per evaluation
            float bestGain = SLOT_SWAP_GAIN_THRESHOLD;
            int swapA = -1;
            int swapB = -1;
            for (int a = 0; a < n; a++) {
                if (a == leadIdx) continue;
                for (int b = a + 1; b < n; b++) {
                    if (b == leadIdx) continue;
                    Vector2f pa = alive.get(a).getLocation();
                    Vector2f pb = alive.get(b).getLocation();
                    float cur = dist(pa, slotPositions[result[a]]) + dist(pb, slotPositions[result[b]]);
                    float swapped = dist(pa, slotPositions[result[b]]) + dist(pb, slotPositions[result[a]]);
                    float gain = cur - swapped;
                    if (gain > bestGain) {
                        bestGain = gain;
                        swapA = a;
                        swapB = b;
                    }
                }
            }
            if (swapA >= 0) {
                int t = result[swapA];
                result[swapA] = result[swapB];
                result[swapB] = t;
            }
        }

        div.assignedSlotByShipId.clear();
        for (int i = 0; i < n; i++) {
            ShipAPI s = alive.get(i);
            if (s != null && s.getId() != null) div.assignedSlotByShipId.put(s.getId(), result[i]);
        }
        div.lastSlotAssignedFormation = formation;
        div.lastSlotAssignedCount = n;
        return result;
    }

    private static float dist(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Scope Item 4: Computes the world-space formation slot position for {@code ship} (at index {@code shipIdx}
     * in {@code alive}) anchored around {@code (anchorX, anchorY)} with heading {@code (headingX, headingY)}.
     * Used by initial Fleet-Wide Formation deployment marshalling so divisions deploy directly in formation.
     */
    public static Vector2f computeFormationSlotPositionForAnchor(
            DivisionFormation formation,
            int shipIdx,
            List<ShipAPI> alive,
            ShipAPI ship,
            float anchorX,
            float anchorY,
            float headingX,
            float headingY,
            float spacing) {
        int n = (alive != null) ? alive.size() : 1;
        if (n <= 1) {
            return new Vector2f(anchorX, anchorY);
        }
        float rightX = headingY;
        float rightY = -headingX;
        float[] local = computeLocalFormationOffset(formation, shipIdx, n, Math.max(180f, spacing));
        float lat = local[0];
        float depth = local[1];
        return new Vector2f(
                anchorX + rightX * lat + headingX * depth,
                anchorY + rightY * lat + headingY * depth);
    }

    /**
     * Computes the local (lateralOffset, forwardDepthOffset) in combat units for slot {@code k} of {@code n} ships
     * in the given {@link DivisionFormation}.
     * Slot {@code k = 0} is always the Flagship Command Slot.
     * {@code +lateral} = starboard (right of heading), {@code -lateral} = port (left of heading);
     * {@code +depth} = ahead (toward enemy), {@code -depth} = astern.
     */
    public static float[] computeLocalFormationOffset(
            DivisionFormation formation,
            int k,
            int n,
            float spacing) {
        if (n <= 1) {
            return new float[]{0f, 0f};
        }
        DivisionFormation f = (formation != null) ? formation : DivisionFormation.LINE_ABREAST;
        int rank = (k + 1) / 2;
        float sideSign = (k == 0) ? 0f : ((k % 2 == 1) ? -1f : 1f);

        switch (f) {
            case LINE_ABREAST: {
                // Pure lateral battle line perpendicular to the enemy; flagship in the center
                return new float[]{sideSign * rank * spacing, 0f};
            }
            case LINE_AHEAD: {
                // Single-file column: flagship at the van (k=0, depth=0), wingmen astern in its wake
                return new float[]{0f, -k * (spacing * 0.82f)};
            }
            case FORWARD_WEDGE: {
                // V-wedge pointing forward: flagship at the forward apex (k=0, depth=0), wingmen stepped back on both shoulders
                float lat = sideSign * rank * (spacing * 0.84f);
                float depth = -rank * (spacing * 0.68f);
                return new float[]{lat, depth};
            }
            case REVERSE_WEDGE: {
                // Inverted V-pocket opening toward the enemy: flagship at the rear vertex (k=0), wingmen forward on shoulders
                int maxRank = Math.max(1, n / 2);
                float vertexRear = -maxRank * (spacing * 0.68f);
                float lat = sideSign * rank * (spacing * 0.84f);
                float depth = vertexRear + rank * (spacing * 0.68f);
                return new float[]{lat, depth};
            }
            case ECHELON_LEFT: {
                // Stepped diagonal line back and to port (-lateral) from flagship at front-starboard (k=0, depth=0)
                float step = spacing * 0.72f;
                return new float[]{-k * step, -k * (step * 0.82f)};
            }
            case ECHELON_RIGHT: {
                // Stepped diagonal line back and to starboard (+lateral) from flagship at front-port (k=0, depth=0)
                float step = spacing * 0.72f;
                return new float[]{k * step, -k * (step * 0.82f)};
            }
            case CIRCLE: {
                // All-around defensive ring: flagship at the forward crown (k=0, depth=0), wingmen around the ring behind it
                float radius = Math.max(spacing * 0.65f, (float) ((n * spacing * 0.72f) / (2.0 * Math.PI)));
                double angleFromFwd = (2.0 * Math.PI * k) / n;
                float lat = (float) (Math.sin(angleFromFwd) * radius);
                float depth = (float) ((Math.cos(angleFromFwd) - 1.0) * radius);
                return new float[]{lat, depth};
            }
            case PROTECTIVE_CIRCLE: {
                // Protective Circle for mixed large + small ship divisions:
                // Core slots (0 .. coreCount-1) sit inside the protected center; escort slots (coreCount .. n-1)
                // form a 360-degree outer defensive ring around the center at radius R.
                int coreCount = computeProtectiveCircleCoreCount(n);
                if (k < coreCount) {
                    if (coreCount == 1) {
                        return new float[]{0f, 0f};
                    }
                    float coreLat = (k - (coreCount - 1) * 0.5f) * (spacing * 0.62f);
                    return new float[]{coreLat, 0f};
                }
                int ringCount = Math.max(1, n - coreCount);
                int ringIdx = k - coreCount;
                float radius = Math.max(spacing * 0.82f, (float) ((ringCount * spacing * 0.72f) / (2.0 * Math.PI)));
                double angleFromFwd = (2.0 * Math.PI * ringIdx) / ringCount;
                float lat = (float) (Math.sin(angleFromFwd) * radius);
                float depth = (float) (Math.cos(angleFromFwd) * radius);
                return new float[]{lat, depth};
            }
            case FORWARD_SEMICIRCLE: {
                // Convex dome bowing toward the enemy: flagship at the forward crown (k=0, depth=0), wingmen curving back along +-70 deg arc
                int maxRank = Math.max(1, (n + 1) / 2);
                float radius = Math.max(spacing * 0.85f, maxRank * spacing * 0.82f);
                double maxAngle = Math.toRadians(70.0);
                double theta = (k == 0) ? 0.0 : (sideSign * (rank / (double) maxRank) * maxAngle);
                float lat = (float) (Math.sin(theta) * radius);
                float depth = (float) ((Math.cos(theta) - 1.0) * radius);
                return new float[]{lat, depth};
            }
            case REVERSE_SEMICIRCLE: {
                // Concave crescent opening toward the enemy: flagship at the rear-center base (k=0), wingmen curving forward on both wings
                int maxRank = Math.max(1, (n + 1) / 2);
                float radius = Math.max(spacing * 0.85f, maxRank * spacing * 0.82f);
                double maxAngle = Math.toRadians(70.0);
                double theta = (k == 0) ? 0.0 : (sideSign * (rank / (double) maxRank) * maxAngle);
                float lat = (float) (Math.sin(theta) * radius);
                float depth = (float) (-Math.cos(theta) * radius * 0.55f);
                return new float[]{lat, depth};
            }
            case NONE:
            default: {
                // Loose organic scatter for free maneuver
                float lat = sideSign * rank * (spacing * 0.95f);
                float depth = ((k % 3) - 1) * (spacing * 0.28f);
                return new float[]{lat, depth};
            }
        }
    }

    /**
     * Number of center-protected core slots in {@link DivisionFormation#PROTECTIVE_CIRCLE} for {@code n} ships.
     */
    public static int computeProtectiveCircleCoreCount(int n) {
        if (n <= 4) return 1;
        return Math.max(1, Math.min(3, n / 3));
    }

    /**
     * Priority score for placing a ship in the protected inner core of {@link DivisionFormation#PROTECTIVE_CIRCLE}:
     * larger hulls (Capitals, Cruisers), dedicated carriers, and the division flagship belong in the inner core,
     * while smaller escort hulls (Frigates, Destroyers) belong on the 360-degree outer protective circle.
     */
    public static float computeProtectiveCircleCorePriority(ShipAPI s, DivisionState div) {
        if (s == null) return 0f;
        float score = ShipCapabilityProfile.resolveShipFpStrength(s) * 2.0f;
        if (div != null && s == div.flagship) {
            score += 45f;
        }
        int bays = s.getNumFighterBays();
        if (bays > 0) {
            score += 110f * bays;
        }
        ShipAPI.HullSize hs = s.getHullSize();
        if (hs == ShipAPI.HullSize.CAPITAL_SHIP) {
            score += 400f;
        } else if (hs == ShipAPI.HullSize.CRUISER) {
            score += 240f;
        } else if (hs == ShipAPI.HullSize.DESTROYER) {
            score += 90f;
        } else if (hs == ShipAPI.HullSize.FRIGATE) {
            score += 20f;
        }
        return score;
    }

    static void computeStandoffStation(
            CombatEngineAPI engine,
            DivisionState div,
            DivisionState enemyDiv,
            Vector2f outStation) {
        if (enemyDiv == null) {
            outStation.set(div.centroid);
            return;
        }
        Vector2f visEnemyPos = OtcAI.getVisibleCentroidForSide(engine, div.side, enemyDiv);
        float dx = div.centroid.x - visEnemyPos.x;
        float dy = div.centroid.y - visEnemyPos.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float standoff = Math.max(400f, div.standoffRange);
        if (!div.archetype.isCarrierArchetype() && div.standoffRange <= 1050f && div.avgEngageRange > 250f) {
            standoff = Math.min(standoff, Math.max(540f, div.avgEngageRange * 0.88f));
        }
        LineStation lineStation = (div.lineStation != null) ? div.lineStation : LineStation.CENTER;
        boolean isLightOrDdDiv = div.archetype == DivisionArchetype.DESTROYER_DIVISION
                || div.archetype == DivisionArchetype.MISSILE_DESTROYER_DIVISION
                || div.archetype == DivisionArchetype.FRIGATE_DIVISION
                || div.archetype == DivisionArchetype.MISSILE_FRIGATE_DIVISION;
        boolean outmatchedOrFragile = isLightOrDdDiv
                && (div.localForceRatio < 0.95f
                || div.effectiveHpRatio < 0.72f
                || (enemyDiv.totalEffectiveHp > div.totalEffectiveHp * 1.35f && div.totalEffectiveHp > 100f));
        boolean targetCorneredOrAuxiliary = !div.archetype.isCarrierArchetype()
                && !outmatchedOrFragile
                && div.avgFluxLevel < 0.60f
                && (enemyDiv.edgeProximityPinch >= 0.28f
                || enemyDiv.isUnableDivision
                || enemyDiv.retreatingFraction >= 0.35f
                || enemyDiv.combatThreatWeight <= 0.60f);
        if (targetCorneredOrAuxiliary) {
            float refWeaponRange = (div.avgEngageRange > 250f) ? div.avgEngageRange : div.standoffRange;
            standoff = Math.max(480f, Math.min(760f, refWeaponRange * 0.75f));
        } else if (outmatchedOrFragile && div.avgEngageRange > 250f) {
            // Kite near the outer edge of our own gun envelope (never outside our own weapons!)
            standoff = Math.max(standoff, Math.min(1080f, div.avgEngageRange * 0.92f));
        } else if (lineStation == LineStation.REAR) {
            standoff = standoff * 1.12f;
        }
        // Account for the forward hull projection of a multi-ship enemy division's front rank ahead of visEnemyPos
        // (ignoring detached stragglers and compact 1-3 ship groups) so standoff is measured from the front rank
        // of large spread-out enemy formations rather than their deep centroid!
        List<ShipAPI> eMembers = enemyDiv.getAliveMembers();
        if (!targetCorneredOrAuxiliary && len > 1f && eMembers.size() >= 4) {
            float ux = dx / len;
            float uy = dy / len;
            float maxEnemyFrontProj = 0f;
            for (int i = 0; i < eMembers.size(); i++) {
                ShipAPI es = eMembers.get(i);
                if (es == null || !OtcAI.isShipVisibleToSide(engine, div.side, es) || es.getLocation() == null) continue;
                if (enemyDiv.detachedStragglerIds != null && enemyDiv.detachedStragglerIds.contains(es.getId())) continue;
                float rx = es.getLocation().x - visEnemyPos.x;
                float ry = es.getLocation().y - visEnemyPos.y;
                if (rx * rx + ry * ry > 680f * 680f) continue;
                float proj = rx * ux + ry * uy;
                if (proj > maxEnemyFrontProj) {
                    maxEnemyFrontProj = proj;
                }
            }
            if (maxEnemyFrontProj > 180f) {
                float frontBuffer = Math.min(280f, (maxEnemyFrontProj - 180f) * 0.58f);
                standoff += frontBuffer;
            }
        }
        if (len > 1f) {
            outStation.set(
                    visEnemyPos.x + (dx / len) * standoff,
                    visEnemyPos.y + (dy / len) * standoff);
        } else {
            float backY = (div.side == 0) ? -standoff : standoff;
            outStation.set(visEnemyPos.x, visEnemyPos.y + backY);
        }
        // Slide standoff station out of L/U crossfire overlaps only when actually inside a crossfire cordon
        // (calling optimizeStationAlongArc on every tick during normal approach recursively rotates outStation
        // 36 degrees per tick around visEnemyPos and makes divisions and carriers spiral into the enemy!).
        if (!div.archetype.isCarrierArchetype() && div.crossfireCordonIndex >= 0.28f) {
            if (div.useConvergence) {
                BattlefieldFirepowerEvaluator.optimizeConvergenceStation(
                        engine, div, enemyDiv, null, null, visEnemyPos, outStation, 28f, outStation);
            } else {
                BattlefieldFirepowerEvaluator.optimizeStationAlongArc(
                        engine, div, enemyDiv, visEnemyPos, outStation, 24f, 5, outStation);
            }
        }
    }

    /**
     * Places a reinforcing division on the friendly / low-cordon shoulder of the beleaguered division
     * rather than diving straight onto its centroid inside an L- or U-shaped enemy crossfire pocket.
     */
    static void computeReinforceStation(
            CombatEngineAPI engine,
            DivisionState reinforcer,
            DivisionState friend,
            Vector2f outStation,
            BattleGroupState myGroup) {
        if (friend == null) return;
        if (reinforcer == null) {
            outStation.set(friend.centroid);
            return;
        }
        // Default support offset: 480u toward the reinforcer / friendly reserve side of friend.centroid
        float dx = reinforcer.centroid.x - friend.centroid.x;
        float dy = reinforcer.centroid.y - friend.centroid.y;
        if (myGroup != null) {
            dx = dx * 0.65f + (myGroup.reserveStation.x - friend.centroid.x) * 0.35f;
            dy = dy * 0.65f + (myGroup.reserveStation.y - friend.centroid.y) * 0.35f;
        }
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float supportDist = Math.max(420f, Math.min(680f, reinforcer.stationSpacing * 1.15f));
        if (len > 1f) {
            outStation.set(
                    friend.centroid.x + (dx / len) * supportDist,
                    friend.centroid.y + (dy / len) * supportDist);
        } else {
            float backY = (reinforcer.side == 0) ? -supportDist : supportDist;
            outStation.set(friend.centroid.x, friend.centroid.y + backY);
        }
        BattlefieldFirepowerEvaluator.optimizeStationAlongArc(
                engine, reinforcer, friend.targetEnemyDivision, friend.centroid, outStation, 50f, 5, outStation);
        clampToMapBounds(engine, outStation, 650f);
    }

    static void computeScreeningStation(
            CombatEngineAPI engine,
            DivisionState screenDiv,
            DivisionState protectedDiv,
            DivisionState enemyDiv,
            Vector2f outStation,
            int side) {
        if (protectedDiv == null) return;
        float standoff = (screenDiv != null) ? Math.max(550f, screenDiv.standoffRange) : 750f;
        float flankSign = (screenDiv != null && screenDiv.divisionNumber % 2 == 0) ? -1f : 1f;
        float lateralOffset = (screenDiv != null) ? Math.max(320f, screenDiv.stationSpacing * 0.85f) * flankSign : 0f;

        if (enemyDiv != null) {
            Vector2f visEnemyPos = OtcAI.getVisibleCentroidForSide(engine, side, enemyDiv);
            float dx = visEnemyPos.x - protectedDiv.centroid.x;
            float dy = visEnemyPos.y - protectedDiv.centroid.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1f) {
                float fwdX = dx / len;
                float fwdY = dy / len;
                float latX = -fwdY;
                float latY = fwdX;
                float minEnemyClearance = Math.max(780f, (screenDiv != null && screenDiv.avgEngageRange > 250f) ? screenDiv.avgEngageRange * 0.85f : standoff);
                float fwdAdvance = Math.min(standoff * 0.65f, Math.max(120f, len - minEnemyClearance));
                outStation.set(
                        protectedDiv.centroid.x + fwdX * fwdAdvance + latX * lateralOffset,
                        protectedDiv.centroid.y + fwdY * fwdAdvance + latY * lateralOffset);
                return;
            }
        }
        float forwardY = (side == 0) ? standoff * 0.80f : -standoff * 0.80f;
        outStation.set(protectedDiv.centroid.x + lateralOffset, protectedDiv.centroid.y + forwardY);
    }

    static void computeFlankStation(
            CombatEngineAPI engine,
            DivisionState flankerDiv,
            DivisionState enemyDiv,
            Vector2f outStation,
            BattleGroupState myGroup) {
        Vector2f visEnemyPos = OtcAI.getVisibleCentroidForSide(engine, flankerDiv.side, enemyDiv);
        float weaponRange = (flankerDiv.avgEngageRange > 250f) ? flankerDiv.avgEngageRange : flankerDiv.standoffRange;
        boolean targetCorneredOrWeak = enemyDiv.edgeProximityPinch >= 0.25f
                || enemyDiv.isUnableDivision
                || enemyDiv.retreatingFraction >= 0.35f
                || enemyDiv.combatThreatWeight <= 0.62f;
        float flankRadius = targetCorneredOrWeak
                ? Math.max(540f, Math.min(840f, weaponRange * 0.78f))
                : Math.max(1250f, flankerDiv.standoffRange + enemyDiv.spreadRadius * 0.55f + flankerDiv.stationSpacing * 0.65f);
        float mapClampMargin = (enemyDiv.edgeProximityPinch >= 0.22f) ? 360f : 650f;

        // Pincer: an OTC-assigned flank side is measured on the battlegroup's attack axis (fleet centre -> target),
        // so two flankers on opposite sides really do envelop the target from left and right.
        float assignedSide = DivisionCommanderAI.assignedFlankSide(flankerDiv, enemyDiv, myGroup);
        if (assignedSide != 0f) {
            float ax = visEnemyPos.x - myGroup.fleetCentroid.x;
            float ay = visEnemyPos.y - myGroup.fleetCentroid.y;
            float aLen = (float) Math.sqrt(ax * ax + ay * ay);
            if (aLen > 1f) {
                float fwdX = ax / aLen;
                float fwdY = ay / aLen;
                float perpX = -fwdY;
                float perpY = fwdX;
                // Check if the flanker is still approaching from the front hemisphere of the target division;
                // if so, swing wider laterally so it sweeps around the enemy's outer wing out of contact.
                float relX = flankerDiv.centroid.x - visEnemyPos.x;
                float relY = flankerDiv.centroid.y - visEnemyPos.y;
                float relLen = (float) Math.sqrt(relX * relX + relY * relY);
                float forwardDot = (relLen > 1f) ? ((relX * fwdX + relY * fwdY) / relLen) : -1f;
                float lateralMult = (forwardDot < -0.15f && !targetCorneredOrWeak) ? 1.28f : 1.0f;
                float depthMult = (forwardDot < -0.15f) ? 0.15f : 0.32f;
                outStation.set(
                        visEnemyPos.x + perpX * flankRadius * lateralMult * assignedSide + fwdX * flankRadius * depthMult,
                        visEnemyPos.y + perpY * flankRadius * lateralMult * assignedSide + fwdY * flankRadius * depthMult);
                if (flankerDiv.useConvergence) {
                    BattlefieldFirepowerEvaluator.optimizeConvergenceStation(
                            engine, flankerDiv, enemyDiv, myGroup, null, visEnemyPos, outStation, 52f, outStation);
                } else {
                    BattlefieldFirepowerEvaluator.optimizeStationAlongArc(
                            engine, flankerDiv, enemyDiv, visEnemyPos, outStation, 48f, 7, outStation);
                }
                clampToMapBounds(engine, outStation, mapClampMargin);
                return;
            }
        }

        float dx = visEnemyPos.x - flankerDiv.centroid.x;
        float dy = visEnemyPos.y - flankerDiv.centroid.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= 1f) {
            outStation.set(visEnemyPos);
            return;
        }
        // Perpendicular offset to swing around the enemy division's flank, scaled by weapon envelope
        float perpX = -dy / len;
        float perpY = dx / len;
        float sideSign = (flankerDiv.consentedWingSide != 0)
                ? -flankerDiv.consentedWingSide
                : ((flankerDiv.divisionNumber % 2 == 1) ? 1f : -1f);
        // Item D: Check map-edge clearance on both flanks of the enemy division so an autonomous flanker never
        // tries to squeeze into a pinched gap between the enemy and the map wall, and instead swings around the
        // open flank to pin the enemy against the map edge!
        float posClearance = BattlefieldFirepowerEvaluator.computeDirectionalBoundaryClearance(
                engine, visEnemyPos, perpX * sideSign, perpY * sideSign, 200f);
        float negClearance = BattlefieldFirepowerEvaluator.computeDirectionalBoundaryClearance(
                engine, visEnemyPos, -perpX * sideSign, -perpY * sideSign, 200f);
        if (posClearance < 950f && negClearance > posClearance + 400f) {
            sideSign = -sideSign;
        }
        outStation.set(
                visEnemyPos.x + perpX * flankRadius * sideSign,
                visEnemyPos.y + perpY * flankRadius * sideSign);
        if (flankerDiv.useConvergence) {
            BattlefieldFirepowerEvaluator.optimizeConvergenceStation(
                    engine, flankerDiv, enemyDiv, myGroup, null, visEnemyPos, outStation, 60f, outStation);
        } else {
            BattlefieldFirepowerEvaluator.optimizeStationAlongArc(
                    engine, flankerDiv, enemyDiv, visEnemyPos, outStation, 55f, 7, outStation);
        }
        clampToMapBounds(engine, outStation, mapClampMargin);
    }

    /**
     * Updates a division's out-of-contact wide-arc route waypoint (div.routeWaypoint).
     * When a division is executing a flank, staging for a strike, seizing an objective, reinforcing,
     * screening, or retiring to vent, and an enemy division / battle line lies across the straight chord
     * from div.centroid to div.stationPoint, projects an intermediate tangent waypoint around the outer
     * perimeter of the enemy threat zone so the division maneuvers out of contact before assuming station.
     */
    static void updateDivisionOutOfContactRoute(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup) {

        div.routeWaypoint.set(div.stationPoint);
        div.hasRouteWaypoint = false;
        if (enemyGroup == null || enemyGroup.divisions.isEmpty()) return;

        DivisionIntentType intent = div.currentIntent;
        if (intent == DivisionIntentType.CLOSE_UP) {
            return;
        }

        // Direct close-in attack intents do not detour around their own attack target once within 2400u,
        // though they still skirt around other intervening enemy divisions on long transits.
        boolean directAttackIntent = (intent == DivisionIntentType.PRESS_HOME
                || intent == DivisionIntentType.FORM_LINE_AND_FIX
                || intent == DivisionIntentType.TOT_MISSILE_STRIKE
                || intent == DivisionIntentType.TOT_ALPHA_STRIKE
                || intent == DivisionIntentType.HARASSING_MISSILE_ATTACK
                || intent == DivisionIntentType.GIVE_GENERAL_CHASE
                || intent == DivisionIntentType.CLOSE_AND_ENGAGE);

        DivisionState ignoreDirectTarget = directAttackIntent ? div.targetEnemyDivision : null;
        DivisionState flankTarget = (intent == DivisionIntentType.WORK_FLANK) ? div.targetEnemyDivision : null;

        float preferredSide = DivisionCommanderAI.assignedFlankSide(div, div.targetEnemyDivision, myGroup);
        if (preferredSide == 0f) {
            preferredSide = div.routeSkirtSide;
        }

        float[] chosenSide = new float[]{preferredSide};
        Vector2f wp = computeOutOfContactRoutePoint(
                engine,
                div.side,
                div.centroid,
                div.stationPoint,
                ignoreDirectTarget,
                flankTarget,
                preferredSide,
                enemyGroup,
                chosenSide);
        div.routeSkirtSide = chosenSide[0];
        div.routeWaypoint.set(wp);
        div.hasRouteWaypoint = DivisionCommanderAI.distance(div.routeWaypoint, div.stationPoint) > 220f;
    }

    /**
     * Computes an out-of-contact wide-arc waypoint from startPos (A) to goalPos (B) that skirts around
     * any intervening visible enemy division threat zones.
     *
     * If the straight chord A -> B slices through an enemy threat circle (or enemy battle line cluster),
     * returns a tangent/arc waypoint W at ~52 degrees ahead along the outer perimeter of the obstacle
     * on the open flank side. Once the chord A -> B is clear of enemy threat zones, returns goalPos (B).
     */
    static Vector2f computeOutOfContactRoutePoint(
            CombatEngineAPI engine,
            int side,
            Vector2f startPos,
            Vector2f goalPos,
            DivisionState ignoreDirectAttackTarget,
            DivisionState flankTargetDiv,
            float preferredSideHint,
            BattleGroupState enemyGroup,
            float[] outChosenSide) {

        if (startPos == null || goalPos == null) {
            return goalPos != null ? new Vector2f(goalPos) : new Vector2f();
        }
        if (enemyGroup == null || enemyGroup.divisions.isEmpty()) {
            return new Vector2f(goalPos);
        }

        float chordX = goalPos.x - startPos.x;
        float chordY = goalPos.y - startPos.y;
        float chordLen = (float) Math.sqrt(chordX * chordX + chordY * chordY);
        if (chordLen < 360f) {
            return new Vector2f(goalPos);
        }

        float dirX = chordX / chordLen;
        float dirY = chordY / chordLen;

        // Find the primary visible enemy division whose threat zone blocks the straight segment A -> B
        DivisionState blockingDiv = null;
        Vector2f blockingCenter = null;
        float blockingRadius = 0f;
        float earliestBlockProj = Float.MAX_VALUE;

        for (DivisionState eDiv : enemyGroup.divisions) {
            if (eDiv == null || !eDiv.isCombatEffective()) continue;
            if (!OtcAI.isDivisionVisibleToSide(engine, side, eDiv)) continue;
            if (eDiv == ignoreDirectAttackTarget) continue;
            if (ignoreDirectAttackTarget != null && OtcAI.getVisibleFpForSide(engine, side, eDiv) < 24f) continue;

            Vector2f eCenter = OtcAI.getVisibleCentroidForSide(engine, side, eDiv);
            float distGoalToEnemy = DivisionCommanderAI.distance(goalPos, eCenter);
            float distStartToEnemy = DivisionCommanderAI.distance(startPos, eCenter);

            // Threat avoidance radius: enemy engagement range + spread + out-of-contact buffer
            float threatRadius = Math.max(1150f, eDiv.avgEngageRange * 1.10f)
                    + Math.min(700f, eDiv.spreadRadius * 0.70f)
                    + 320f;
            if (eDiv == flankTargetDiv) {
                // For the enemy division being flanked, keep the avoidance disc just inside the flank station radius
                // so the flanker sweeps around its front/beam out of contact rather than cutting across its nose.
                threatRadius = Math.min(threatRadius, Math.max(850f, distGoalToEnemy * 0.88f));
            } else if (distGoalToEnemy < threatRadius + 140f) {
                // If goalPos itself sits close to this enemy division, shrink avoidance radius so goalPos remains reachable
                threatRadius = Math.max(700f, distGoalToEnemy - 140f);
            }

            // Project eCenter onto segment A -> B
            float relX = eCenter.x - startPos.x;
            float relY = eCenter.y - startPos.y;
            float proj = relX * dirX + relY * dirY;
            if (proj <= 60f || proj >= chordLen - 40f) {
                continue;
            }

            float closestX = startPos.x + dirX * proj;
            float closestY = startPos.y + dirY * proj;
            float perpDist = (float) Math.sqrt(
                    (eCenter.x - closestX) * (eCenter.x - closestX)
                            + (eCenter.y - closestY) * (eCenter.y - closestY));

            if (perpDist < threatRadius * 0.96f && proj < earliestBlockProj) {
                // Also skip if startPos is already closer to goalPos than to eCenter and moving away
                if (distStartToEnemy > distGoalToEnemy && proj > chordLen * 0.75f) {
                    continue;
                }
                earliestBlockProj = proj;
                blockingDiv = eDiv;
                blockingCenter = eCenter;
                blockingRadius = threatRadius;
            }
        }

        if (blockingDiv == null || blockingCenter == null) {
            return new Vector2f(goalPos);
        }

        // Expand obstacle center/radius slightly if adjacent enemy divisions form a contiguous battle line with blockingDiv
        float clusterX = blockingCenter.x * blockingDiv.totalFpStrength;
        float clusterY = blockingCenter.y * blockingDiv.totalFpStrength;
        float clusterWeight = Math.max(1f, blockingDiv.totalFpStrength);
        for (DivisionState otherE : enemyGroup.divisions) {
            if (otherE == null || otherE == blockingDiv || !otherE.isCombatEffective()) continue;
            if (!OtcAI.isDivisionVisibleToSide(engine, side, otherE)) continue;
            if (otherE == ignoreDirectAttackTarget) continue;
            Vector2f oPos = OtcAI.getVisibleCentroidForSide(engine, side, otherE);
            float dBetween = DivisionCommanderAI.distance(blockingCenter, oPos);
            if (dBetween < 1850f) {
                // Project onto A -> B as well
                float oProj = (oPos.x - startPos.x) * dirX + (oPos.y - startPos.y) * dirY;
                if (oProj > 0f && oProj < chordLen) {
                    float w = otherE.totalFpStrength * 0.55f;
                    clusterX += oPos.x * w;
                    clusterY += oPos.y * w;
                    clusterWeight += w;
                }
            }
        }
        Vector2f obsCenter = new Vector2f(clusterX / clusterWeight, clusterY / clusterWeight);
        float extraClusterSpan = DivisionCommanderAI.distance(obsCenter, blockingCenter);
        float skirtRadius = Math.min(2850f, blockingRadius + extraClusterSpan * 0.65f + 240f);

        // Two candidate normals perpendicular to A -> B: +1 (Port / Left of chord) and -1 (Starboard / Right of chord)
        float normX = -dirY;
        float normY = dirX;

        float bestSide = (preferredSideHint != 0f) ? Math.signum(preferredSideHint) : 1f;
        float bestSideScore = -Float.MAX_VALUE;

        for (int sIdx = 0; sIdx < 2; sIdx++) {
            float candidateSide = (sIdx == 0) ? 1f : -1f;
            float candX = obsCenter.x + normX * candidateSide * skirtRadius;
            float candY = obsCenter.y + normY * candidateSide * skirtRadius;
            Vector2f candApex = new Vector2f(candX, candY);

            float score = 0f;
            // 1. Prefer swinging around the OUTSIDE of the enemy fleet centroid rather than between enemy divisions
            if (enemyGroup.totalFriendlyStrength > 0f) {
                score += DivisionCommanderAI.distance(candApex, enemyGroup.fleetCentroid) * 0.42f;
            }
            // 2. Penalize candidate apex if other visible enemy divisions sit near that side
            for (DivisionState otherE : enemyGroup.divisions) {
                if (otherE == null || otherE == blockingDiv || !otherE.isCombatEffective()) continue;
                if (!OtcAI.isDivisionVisibleToSide(engine, side, otherE)) continue;
                Vector2f oPos = OtcAI.getVisibleCentroidForSide(engine, side, otherE);
                float dToCand = DivisionCommanderAI.distance(candApex, oPos);
                if (dToCand < 1900f) {
                    score -= (1900f - dToCand) * 0.65f;
                }
            }
            // 2b. Penalize candidate apex by actual overlapping weapon-arc firepower & L/U crossfire cordon
            BattlefieldFirepowerEvaluator.PointFirepowerEval fpEval =
                    BattlefieldFirepowerEvaluator.evaluatePoint(engine, side, candApex, 180f);
            score -= fpEval.effectiveDanger * 0.42f + fpEval.cordonIndex * 380f;
            if (blockingDiv != null) {
                score += BattlefieldFirepowerEvaluator.evaluateBlindSpotAdvantage(engine, side, blockingDiv, candApex) * 0.85f;
            }

            // 3. Shorter tangent geometry from startPos around obsCenter
            float crossStart = (startPos.x - obsCenter.x) * normX * candidateSide
                    + (startPos.y - obsCenter.y) * normY * candidateSide;
            score += crossStart * 0.35f;

            // 4. Hysteresis / OTC flank-side stickiness so units don't flip-flop mid-flank
            if (preferredSideHint != 0f && Math.signum(preferredSideHint) == candidateSide) {
                score += 620f;
            }

            // 5. Penalize proximity to combat map borders
            if (engine != null) {
                float halfW = engine.getMapWidth() * 0.5f - 900f;
                float halfH = engine.getMapHeight() * 0.5f - 900f;
                if (Math.abs(candX) > halfW) {
                    score -= (Math.abs(candX) - halfW) * 1.2f;
                }
                if (Math.abs(candY) > halfH) {
                    score -= (Math.abs(candY) - halfH) * 1.2f;
                }
            }

            if (score > bestSideScore) {
                bestSideScore = score;
                bestSide = candidateSide;
            }
        }

        if (outChosenSide != null && outChosenSide.length > 0) {
            outChosenSide[0] = bestSide;
        }

        // Place waypoint W along the outer perimeter of obsCenter, stepped ~52 degrees ahead along the chosen side arc
        float rx = startPos.x - obsCenter.x;
        float ry = startPos.y - obsCenter.y;
        float rLen = (float) Math.sqrt(rx * rx + ry * ry);
        float ux;
        float uy;
        if (rLen > 1f) {
            ux = rx / rLen;
            uy = ry / rLen;
        } else {
            ux = normX * bestSide;
            uy = normY * bestSide;
        }

        // Tangent vector perpendicular to u that advances toward goalPos around the chosen side
        float t1x = -uy;
        float t1y = ux;
        float t2x = uy;
        float t2y = -ux;
        // Pick the tangent whose projection along (dir + 0.5 * chosenNormal) is positive
        float guideX = dirX + normX * bestSide * 0.65f;
        float guideY = dirY + normY * bestSide * 0.65f;
        float tx;
        float ty;
        if (t1x * guideX + t1y * guideY >= t2x * guideX + t2y * guideY) {
            tx = t1x;
            ty = t1y;
        } else {
            tx = t2x;
            ty = t2y;
        }

        // 52-degree angular step around the perimeter (cos 52° ≈ 0.616, sin 52° ≈ 0.788)
        float stepDirX = 0.616f * ux + 0.788f * tx;
        float stepDirY = 0.616f * uy + 0.788f * ty;
        float stepLen = (float) Math.sqrt(stepDirX * stepDirX + stepDirY * stepDirY);
        if (stepLen > 1e-4f) {
            stepDirX /= stepLen;
            stepDirY /= stepLen;
        }

        Vector2f waypoint = new Vector2f(
                obsCenter.x + stepDirX * skirtRadius,
                obsCenter.y + stepDirY * skirtRadius);
        clampToMapBounds(engine, waypoint, 650f);
        return waypoint;
    }

    static boolean isShipNearVisibleEnemy(
            CombatEngineAPI engine,
            int side,
            Vector2f pos,
            BattleGroupState enemyGroup,
            float radius) {
        if (pos == null || enemyGroup == null) return false;
        float rSq = radius * radius;
        for (DivisionState eDiv : enemyGroup.divisions) {
            if (eDiv == null || !eDiv.isCombatEffective()) continue;
            for (ShipAPI e : eDiv.getAliveMembers()) {
                if (!OtcAI.isShipVisibleToSide(engine, side, e)) continue;
                float dx = pos.x - e.getLocation().x;
                float dy = pos.y - e.getLocation().y;
                if ((dx * dx + dy * dy) <= rSq) {
                    return true;
                }
            }
        }
        return false;
    }

    static void clampToMapBounds(CombatEngineAPI engine, Vector2f pt, float margin) {
        if (engine == null || pt == null) return;
        float maxW = Math.max(2000f, engine.getMapWidth() * 0.5f - margin);
        float maxH = Math.max(2000f, engine.getMapHeight() * 0.5f - margin);
        if (pt.x > maxW) pt.x = maxW;
        if (pt.x < -maxW) pt.x = -maxW;
        if (pt.y > maxH) pt.y = maxH;
        if (pt.y < -maxH) pt.y = -maxH;
    }

}
