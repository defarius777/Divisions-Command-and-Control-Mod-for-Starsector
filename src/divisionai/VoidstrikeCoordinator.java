package divisionai;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.FogOfWarAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.DerivedWeaponStatsAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionNomenclature.NomenclatureCategory;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import divisionai.DivisionModel.BattlePhase;
import divisionai.DivisionModel.OtcOrderType;
import divisionai.DivisionModel.OtcOrder;
import divisionai.DivisionModel.DivisionIntentType;
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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Unified Voidstrike Coordinator & Tracker:
 *  1. 3-Stage Carrier Voidstrike Staging (Stage 0 Orbit Carrier -> Stage 1 Marshal Point -> Stage 2 Attack Run)
 *  2. Compromise Strike Width x Depth (1..10) & 7 Squadron Craft Formations (Fighter & Bomber patrol vs Voidstrike)
 *  3. Target Selection Observance (FREE, MEDIUM, HIGH) & Fighter/Bomber Formation Dressing
 *  4. Multi-Division Voidstrike Clustering, Per-Side Numbering (Player, Enemy, Allies), and Tactical Map Tracking
 */
public class VoidstrikeCoordinator {

    public static final int VOIDSTRIKE_SIDE_PLAYER = 0;
    public static final int VOIDSTRIKE_SIDE_ENEMY = 1;
    public static final int VOIDSTRIKE_SIDE_ALLIED = 2;

    static final List<VoidstrikeRecord> activeVoidstrikes = new ArrayList<>();
    static final List<VoidstrikeRecord> recentVoidstrikeHistory = new ArrayList<>();
    static final int[] nextVoidstrikeNumberBySide = new int[]{1, 1, 1};
    static CombatEngineAPI lastVoidstrikeUpdateEngine = null;
    static float lastVoidstrikeUpdateTime = -100f;

    public static class VoidstrikeRecord {
        public final int side; // 0 = Player, 1 = Enemy, 2 = Allies
        public int voidstrikeNumber;
        public String targetKey;
        public final Set<String> participatingDivisionIds = new java.util.LinkedHashSet<>();
        public final Set<String> participatingWingKeys = new java.util.LinkedHashSet<>();
        public final List<ShipAPI> activeFighters = new ArrayList<>();
        public final Vector2f center = new Vector2f();
        public final Vector2f visibleCenter = new Vector2f();
        public boolean visibleToPlayer = true;
        public float lastActiveTime = -100f;
        public float createdTime = -100f;
        public boolean reachedTarget = false;
        public float reachedTargetTime = -100f;
        public final Set<String> initialBomberWingKeys = new java.util.LinkedHashSet<>();
        public int peakFighterCount = 0;
        public boolean achievedCohesion = false;
        public boolean targetIsolatedAtLaunch = false;
        public String culminationReason = null;

        public VoidstrikeRecord(int side, int voidstrikeNumber, String targetKey) {
            this.side = side;
            this.voidstrikeNumber = voidstrikeNumber;
            this.targetKey = targetKey;
        }

        public String getDisplayLabel() {
            return "Voidstrike " + voidstrikeNumber;
        }

        public boolean isMultiDivision() {
            return participatingDivisionIds.size() > 1;
        }
    }

    public static void resetForNewBattle() {
        activeVoidstrikes.clear();
        recentVoidstrikeHistory.clear();
        nextVoidstrikeNumberBySide[0] = 1;
        nextVoidstrikeNumberBySide[1] = 1;
        nextVoidstrikeNumberBySide[2] = 1;
        lastVoidstrikeUpdateEngine = null;
        lastVoidstrikeUpdateTime = -100f;
        lastVoidstrikePlayerGroup = null;
        lastVoidstrikeAlliedGroup = null;
        lastVoidstrikeEnemyGroup = null;
    }

    // Bounded Voidstrike sortie doctrine: (1) readiness -> (2) ingress -> (3) strike pass -> (4) culmination & egress.
    static final float SORTIE_ARRIVAL_RADIUS = 780f;
    static final float APPROACH_TIMEOUT_SECONDS = 26f;
    static final float STRIKE_PASS_CULMINATION_SECONDS = 16f;
    static final float MAX_SORTIE_DURATION_SECONDS = 48f;
    static final float BOMBER_RELEASE_GRACE_SECONDS = 6f;
    static final float TARGET_ESCAPE_DISTANCE = 2600f;
    static final float TARGET_REJOIN_RADIUS = 950f;
    static final float SORTIE_COHESION_RADIUS = 1400f;
    static final float SORTIE_ATTRITION_FRACTION = 0.45f;
    static final float EGRESS_RECOVERY_RADIUS = 700f;
    static final float EGRESS_MAX_SECONDS = 12f;
    static BattleGroupState lastVoidstrikePlayerGroup;
    static BattleGroupState lastVoidstrikeAlliedGroup;
    static BattleGroupState lastVoidstrikeEnemyGroup;
    static final List<ShipAPI> cachedAliveFighters = new ArrayList<>();

    /**
     * Item D: Updates Coordinated vs. Uncoordinated Voidstrike staging for deck strikes and multi-division strikes:
     *  - UNCOORDINATED: voidstrikeStage = 2 immediately (wings attack in successive dispersed waves).
     *  - COORDINATED:
     *      * Stage 0 (Orbit Carrier, ~1.8s): Newly launched squadrons circle around their carrier as the deck cycle launches.
     *      * Stage 1 (Form Up at Marshal Point nearby, ~2.4s or until >= 72% of airborne wings reach the marshal point):
     *        Squadrons converge on a marshalling point ~420u ahead of the carrier division (or shared multi-division
     *        marshalling point for OTC StrikePlan participants) and dress into their Voidstrike formation & depth.
     *      * Stage 2 (Massed Voidstrike Attack Run): All marshalled squadrons unleash together onto the target!
     */
    public static void updateDivisionVoidstrikeStaging(
            DivisionState div,
            BattleGroupState myGroup,
            ShipAPI focusEnemyShip,
            float now) {
        if (div == null) return;

        boolean hasWings = false;
        int airborneWings = 0;
        int wingsClearedCarriers = 0;
        int wingsCommittedDownrange = 0;
        for (ShipAPI m : div.getAliveMembers()) {
            if (m.getAllWings() != null && !m.getAllWings().isEmpty()) {
                hasWings = true;
                for (com.fs.starfarer.api.combat.FighterWingAPI w : m.getAllWings()) {
                    if (w != null && w.getWingMembers() != null && !w.getWingMembers().isEmpty()) {
                        airborneWings++;
                        ShipAPI leader = w.getLeader();
                        if (leader != null && leader.isAlive() && !w.isReturning(leader)) {
                            float dCarrier = DivisionUiWidgets.distance(leader.getLocation(), m.getLocation());
                            if (dCarrier >= 130f) wingsClearedCarriers++;
                            if (dCarrier >= 460f) wingsCommittedDownrange++;
                        }
                    }
                }
            }
        }
        if (!hasWings || focusEnemyShip == null || !focusEnemyShip.isAlive() || focusEnemyShip.isHulk()) {
            div.voidstrikeStage = 0;
            div.voidstrikeLastTargetKey = null;
            return;
        }

        boolean isDeckOrMultiDivStrike = div.currentIntent == DivisionIntentType.LAUNCH_DECKLOAD_STRIKE
                || div.currentIntent == DivisionIntentType.LAUNCH_ALPHA_STRIKE
                || div.currentIntent == DivisionIntentType.MARSHAL_FOR_STRIKE
                || div.currentIntent == DivisionIntentType.FLY_CYCLIC_STRIKES
                || div.currentIntent == DivisionIntentType.FLY_FIGHTER_SWEEP
                || div.currentIntent == DivisionIntentType.FLY_ARMED_RECONNAISSANCE
                || div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE
                || div.currentIntent == DivisionIntentType.PRESS_HOME
                || (myGroup != null && myGroup.strikePlan != null && myGroup.strikePlan.participants.contains(div));

        // Physical egress recovery tracking: after a Voidstrike culminates, div.voidstrikeEgressActive remains true
        // until surviving airborne fighters have physically returned within EGRESS_RECOVERY_RADIUS of their carriers
        // (never merely w.isReturning(leader) while still 2000u downrange!).
        if (div.voidstrikeEgressActive) {
            float elapsedEgress = Math.max(0f, now - div.voidstrikeStageStartTime);
            int recoveredFighters = 0;
            int countedFighters = 0;
            for (ShipAPI m : div.getAliveMembers()) {
                if (m.getAllWings() == null) continue;
                for (com.fs.starfarer.api.combat.FighterWingAPI w : m.getAllWings()) {
                    if (w == null || w.getWingMembers() == null) continue;
                    for (ShipAPI f : w.getWingMembers()) {
                        if (f == null || !f.isAlive() || f.isHulk() || f.isExpired()) continue;
                        countedFighters++;
                        if (DivisionUiWidgets.distance(f.getLocation(), m.getLocation()) <= EGRESS_RECOVERY_RADIUS) {
                            recoveredFighters++;
                        }
                    }
                }
            }
            boolean recoveredEnough = countedFighters == 0
                    || recoveredFighters >= Math.max(1, Math.round(countedFighters * 0.75f));
            if (recoveredEnough || elapsedEgress >= EGRESS_MAX_SECONDS) {
                div.voidstrikeEgressActive = false;
                div.culminatedVoidstrikeWingKeys.clear();
                div.culminatedVoidstrikeTargetKey = null;
                div.voidstrikeStageStartTime = now;
            } else {
                return;
            }
        }

        if (div.voidstrikeCoordination == VoidstrikeCoordinationMode.UNCOORDINATED
                && div.currentIntent != DivisionIntentType.MARSHAL_FOR_STRIKE) {
            div.voidstrikeStage = 2;
            return;
        }
        if (!isDeckOrMultiDivStrike) {
            div.voidstrikeStage = 2;
            return;
        }

        // Compute nearby marshalling point (~360u ahead of carrier division centroid toward focusEnemyShip;
        // if part of a multi-division StrikePlan, blend 40% toward the StrikePlan's shared axis)
        Vector2f anchor = (div.centroid.x != 0f || div.centroid.y != 0f)
                ? div.centroid
                : ((div.flagship != null && div.flagship.isAlive() && !div.flagship.isHulk())
                ? div.flagship.getLocation()
                : div.centroid);
        float dx = focusEnemyShip.getLocation().x - anchor.x;
        float dy = focusEnemyShip.getLocation().y - anchor.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float dirX = dist > 1f ? (dx / dist) : 0f;
        float dirY = dist > 1f ? (dy / dist) : (div.side == 0 ? 1f : -1f);
        float marshalLead = Math.min(380f, Math.max(240f, dist * 0.20f));
        float mx = anchor.x + dirX * marshalLead;
        float my = anchor.y + dirY * marshalLead;

        if (myGroup != null && myGroup.strikePlan != null
                && myGroup.strikePlan.participants.size() >= 2
                && myGroup.strikePlan.participants.contains(div)) {
            float sumX = 0f;
            float sumY = 0f;
            int cnt = 0;
            for (DivisionState pDiv : myGroup.strikePlan.participants) {
                if (pDiv != null && pDiv.isAbleCombatEffective() && pDiv.hasCarrierCapability) {
                    sumX += pDiv.centroid.x;
                    sumY += pDiv.centroid.y;
                    cnt++;
                }
            }
            if (cnt >= 2) {
                float avgX = sumX / cnt + dirX * marshalLead;
                float avgY = sumY / cnt + dirY * marshalLead;
                mx = mx * 0.60f + avgX * 0.40f;
                my = my * 0.60f + avgY * 0.40f;
            }
        }
        div.voidstrikeMarshalPoint.set(mx, my);

        String targetKey = (div.targetEnemyDivision != null ? div.targetEnemyDivision.id : focusEnemyShip.getId());
        boolean hadPreviousTarget = (div.voidstrikeLastTargetKey != null);
        boolean targetChanged = (!hadPreviousTarget || !div.voidstrikeLastTargetKey.equals(targetKey));

        // If enemy is already at point-blank knife-fight range (< 850u), skip staging and strike immediately
        if (dist < 850f && div.currentIntent != DivisionIntentType.MARSHAL_FOR_STRIKE) {
            div.voidstrikeStage = 2;
            div.voidstrikeLastTargetKey = targetKey;
            return;
        }

        // Item C: If a strike wave is already airborne downrange in Stage 2 (hadPreviousTarget && wingsCommittedDownrange > 0)
        // and the target changes mid-flight (e.g. target ship destroyed or retargeted), do NOT reset to Stage 0 and
        // abort the airborne wave! Keep voidstrikeStage = 2 so the airborne squadrons press home on the new target!
        if (targetChanged) {
            div.voidstrikeLastTargetKey = targetKey;
            if (hadPreviousTarget && div.voidstrikeStage == 2 && wingsCommittedDownrange > 0
                    && div.currentIntent != DivisionIntentType.MARSHAL_FOR_STRIKE) {
                return;
            }
            div.voidstrikeStage = 0;
            div.voidstrikeStageStartTime = now;
        }

        // During OTC MARSHAL_FOR_STRIKE countdown, keep wings in Stage 0 -> Stage 1 at the marshal point
        // (instead of pulling them back into carrier bays!) so they are already formed up when the strike commits!
        if (div.currentIntent == DivisionIntentType.MARSHAL_FOR_STRIKE) {
            if (div.voidstrikeStage == 2) {
                div.voidstrikeStage = 1;
                div.voidstrikeStageStartTime = now;
            } else if (div.voidstrikeStage == 0) {
                float elapsed = Math.max(0f, now - div.voidstrikeStageStartTime);
                if ((elapsed >= 1.1f && airborneWings > 0)
                        || wingsClearedCarriers >= Math.max(1, Math.round(airborneWings * 0.65f))) {
                    div.voidstrikeStage = 1;
                    div.voidstrikeStageStartTime = now;
                }
            }
            return;
        }

        float elapsedInStage = Math.max(0f, now - div.voidstrikeStageStartTime);
        if (div.voidstrikeStage == 0) {
            if (div.voidstrikeEgressActive) {
                // Egress: the previous sortie culminated; wait until most wings recover near their carriers
                int recovered = 0;
                int counted = 0;
                for (ShipAPI m : div.getAliveMembers()) {
                    if (m.getAllWings() == null) continue;
                    for (com.fs.starfarer.api.combat.FighterWingAPI w : m.getAllWings()) {
                        if (w == null || w.getLeader() == null || !w.getLeader().isAlive()) continue;
                        counted++;
                        if (DivisionUiWidgets.distance(w.getLeader().getLocation(), m.getLocation()) <= EGRESS_RECOVERY_RADIUS) recovered++;
                    }
                }
                boolean recoveredEnough = counted == 0 || recovered >= Math.max(1, Math.round(counted * 0.7f));
                if (!recoveredEnough && elapsedInStage < EGRESS_MAX_SECONDS) {
                    return;
                }
                div.voidstrikeEgressActive = false;
                div.voidstrikeStageStartTime = now;
                return;
            }
            // Stage 0: Squadrons launch and clear their carrier decks (~1.2s)
            if (elapsedInStage >= 1.2f && airborneWings > 0) {
                div.voidstrikeStage = 1;
                div.voidstrikeStageStartTime = now;
            }
        } else if (div.voidstrikeStage == 1) {
            // Stage 1: Squadrons form up at the marshalling point; wait until >= 78% of airborne wings are within
            // 420u of voidstrikeMarshalPoint (up to 5.8s max timeout so slower bombers & trailing carriers' wings
            // actually reach the marshal point and form up together before departing!)
            int assembledWings = 0;
            for (ShipAPI m : div.getAliveMembers()) {
                if (m.getAllWings() == null) continue;
                for (com.fs.starfarer.api.combat.FighterWingAPI w : m.getAllWings()) {
                    if (w == null || w.getLeader() == null || !w.getLeader().isAlive()) continue;
                    if (DivisionUiWidgets.distance(w.getLeader().getLocation(), div.voidstrikeMarshalPoint) <= 420f) {
                        assembledWings++;
                    }
                }
            }
            boolean majorityAssembled = airborneWings > 0 && assembledWings >= Math.max(1, Math.round(airborneWings * 0.78f));
            float maxStage1Wait = (airborneWings >= 2) ? 4.8f : 2.6f;
            if ((elapsedInStage >= 1.2f && majorityAssembled) || elapsedInStage >= maxStage1Wait) {
                div.voidstrikeStage = 2;
                div.voidstrikeStageStartTime = now;
            }
        } else if (div.voidstrikeStage == 2) {
            // In cyclic / deckload strikes, if all strike craft returned near carrier after a long run (> 14s), re-marshal next wave
            if (elapsedInStage >= 14.5f && airborneWings > 0) {
                int nearCarrier = 0;
                for (ShipAPI m : div.getAliveMembers()) {
                    if (m.getAllWings() == null) continue;
                    for (com.fs.starfarer.api.combat.FighterWingAPI w : m.getAllWings()) {
                        if (w == null || w.getLeader() == null || !w.getLeader().isAlive()) continue;
                        if (DivisionUiWidgets.distance(w.getLeader().getLocation(), m.getLocation()) <= 340f) {
                            nearCarrier++;
                        }
                    }
                }
                if (nearCarrier >= Math.max(1, Math.round(airborneWings * 0.75f))) {
                    div.voidstrikeStage = 0;
                    div.voidstrikeStageStartTime = now;
                }
            }
        }
    }

    /**
     * Computes the effective [strikeWidth, strikeDepth] for {@code totalUnits} squadrons/craft given
     * requested {@code reqWidth} (1..10) and {@code reqDepth} (1..10).
     * If {@code reqWidth} and {@code reqDepth} cannot be achieved together ({@code reqWidth * reqDepth != totalUnits}),
     * averages each requested dimension with the feasible dimension implied by the other:
     *   {@code feasibleWidth = totalUnits / reqDepth}, {@code feasibleDepth = totalUnits / reqWidth},
     *   {@code effWidth = round(0.5 * (reqWidth + feasibleWidth))},
     *   {@code effDepth = round(0.5 * (reqDepth + feasibleDepth))}.
     */
    public static int[] computeCompromiseStrikeWidthAndDepth(int totalUnits, int reqWidth, int reqDepth) {
        int n = Math.max(1, totalUnits);
        int w = Math.max(1, Math.min(10, reqWidth));
        int d = Math.max(1, Math.min(10, reqDepth));
        if (w * d == n) {
            return new int[]{w, d};
        }
        float feasibleW = Math.max(1f, (float) n / d);
        float feasibleD = Math.max(1f, (float) n / w);
        int effW = Math.max(1, Math.min(10, Math.round(0.5f * (w + feasibleW))));
        int effD = Math.max(1, Math.min(10, Math.round(0.5f * (d + feasibleD))));
        return new int[]{effW, effD};
    }

    /**
     * Computes the 2D formation offset (in units) for a strike craft inside its squadron (and rank depth 1..10
     * during Voidstrikes) for Items E, D2, F, G, and H.
     */
    public static Vector2f computeSquadronCraftSlotOffset(
            SquadronFormation formation,
            int craftIdx,
            int craftCount,
            int squadronRankIdx,
            int squadronDepth,
            float craftSpacing,
            float fwdX,
            float fwdY,
            float rightX,
            float rightY) {
        return computeSquadronCraftSlotOffset(
                formation, craftIdx, craftCount, squadronRankIdx, squadronDepth, 0, 1,
                craftSpacing, fwdX, fwdY, rightX, rightY);
    }

    /**
     * Overloaded squadron craft slot offset calculator that incorporates both Voidstrike Strike Depth (1..10)
     * and Strike Width (1..10) with automatic averaging when width and depth cannot be achieved together.
     */
    public static Vector2f computeSquadronCraftSlotOffset(
            SquadronFormation formation,
            int craftIdx,
            int craftCount,
            int squadronRankIdx,
            int squadronDepth,
            int squadronColIdx,
            int squadronWidth,
            float craftSpacing,
            float fwdX,
            float fwdY,
            float rightX,
            float rightY) {

        SquadronFormation fm = formation != null ? formation : SquadronFormation.WEDGE;
        int n = Math.max(1, craftCount);
        int idx = Math.max(0, Math.min(n - 1, craftIdx));
        float sp = Math.max(28f, craftSpacing);

        // Lateral index centered around 0
        float centered = idx - (n - 1) * 0.5f;
        int wingSide = (idx % 2 == 1) ? 1 : -1;
        int pairRank = (idx + 1) / 2;

        float localFwd = 0f;
        float localRight = 0f;

        switch (fm) {
            case LINE_ABREAST:
                localRight = centered * sp;
                localFwd = 0f;
                break;
            case LINE_AHEAD:
                localRight = 0f;
                localFwd = -idx * sp * 0.92f;
                break;
            case WEDGE:
                if (idx == 0) {
                    localFwd = sp * 0.45f;
                    localRight = 0f;
                } else {
                    localRight = wingSide * pairRank * sp * 0.85f;
                    localFwd = (sp * 0.45f) - pairRank * sp * 0.72f;
                }
                break;
            case REVERSE_WEDGE:
                if (idx == 0) {
                    localFwd = -sp * 0.45f;
                    localRight = 0f;
                } else {
                    localRight = wingSide * pairRank * sp * 0.85f;
                    localFwd = (-sp * 0.45f) + pairRank * sp * 0.72f;
                }
                break;
            case CIRCLE: {
                double ang = (2.0 * Math.PI * idx) / n;
                float rad = Math.max(sp * 0.65f, (n * sp) / (float) (2.0 * Math.PI));
                localFwd = (float) Math.cos(ang) * rad;
                localRight = (float) Math.sin(ang) * rad;
                break;
            }
            case SEMICIRCLE: {
                double t = (n <= 1) ? 0.0 : (idx / (double) (n - 1)) * Math.PI - (0.5 * Math.PI);
                float rad = Math.max(sp * 0.75f, n * sp * 0.34f);
                localFwd = (float) Math.cos(t) * rad * 0.65f;
                localRight = (float) Math.sin(t) * rad;
                break;
            }
            case REVERSE_SEMICIRCLE: {
                double t = (n <= 1) ? 0.0 : (idx / (double) (n - 1)) * Math.PI - (0.5 * Math.PI);
                float rad = Math.max(sp * 0.75f, n * sp * 0.34f);
                localFwd = -(float) Math.cos(t) * rad * 0.65f;
                localRight = (float) Math.sin(t) * rad;
                break;
            }
        }

        // Apply multi-rank Voidstrike Squadron Depth (1..10) and Strike Width (1..10):
        int clampedDepth = Math.max(1, Math.min(10, squadronDepth));
        if (clampedDepth > 1 && squadronRankIdx > 0) {
            int effRank = squadronRankIdx % clampedDepth;
            localFwd -= effRank * Math.max(85f, sp * 1.95f);
        }
        int clampedWidth = Math.max(1, Math.min(10, squadronWidth));
        if (clampedWidth > 1) {
            int effCol = Math.max(0, squadronColIdx) % clampedWidth;
            float centeredCol = effCol - (clampedWidth - 1) * 0.5f;
            localRight += centeredCol * Math.max(92f, sp * 2.15f);
        }

        return new Vector2f(
                fwdX * localFwd + rightX * localRight,
                fwdY * localFwd + rightY * localRight);
    }

    /**
     * Integrates a ship's Strike Craft (FighterWingAPI squadrons) into the division's formation and Voidstrike doctrine:
     *  - Item C (VoidstrikeTargetObservance): FREE, MEDIUM, HIGH target observance on the designated strike target.
     *  - Item D (VoidstrikeCoordinationMode): COORDINATED (Stage 0 orbit carrier -> Stage 1 marshal point -> Stage 2 strike)
     *    vs UNCOORDINATED (dispersed waves).
     *  - Items E, D2, F, G (SquadronFormation): Fighter & Bomber formations in general patrol vs Voidstrike.
     *  - Item H (voidstrikeSquadronDepth 1..10): Multi-rank depth across squadrons in a Voidstrike.
     */
    static void dressShipStrikeCraftInFormation(
            ShipAPI carrier,
            int carrierIdxInDiv,
            ShipAPI focusEnemyShip,
            DivisionState div,
            BattleGroupState myGroup,
            float now) {
        if (carrier == null || div == null) return;
        List<com.fs.starfarer.api.combat.FighterWingAPI> wings = carrier.getAllWings();
        if (wings == null || wings.isEmpty()) return;

        // Item C: Only pull fighters back inside carrier bays when genuinely out of combat (HOLD_IN_RESERVE,
        // HAUL_OUT_TO_VENT without local enemies, or CLOSE_UP without a strike target).
        // Never setPullBackFighters(true) during MARSHAL_FOR_STRIKE or Stage 0/1 staging once wings are launching,
        // because setPullBackFighters(true) forces airborne squadrons to land back inside the carrier instead of
        // assembling at voidstrikeMarshalPoint!
        boolean holdFightersInFormation = div.voidstrikeEgressActive
                || div.currentIntent == DivisionIntentType.HOLD_IN_RESERVE
                || (div.currentIntent == DivisionIntentType.CLOSE_UP && focusEnemyShip == null)
                || (div.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT && div.localEnemyStrength < 10f);

        boolean coordinatedStagingActive = !holdFightersInFormation
                && focusEnemyShip != null
                && focusEnemyShip.isAlive()
                && (div.currentIntent == DivisionIntentType.MARSHAL_FOR_STRIKE
                || (div.voidstrikeCoordination == VoidstrikeCoordinationMode.COORDINATED
                && (div.voidstrikeStage == 0 || div.voidstrikeStage == 1)));

        if (holdFightersInFormation) {
            if (!carrier.isPullBackFighters()) {
                carrier.setPullBackFighters(true);
                div.fighterPullbackShipIds.add(carrier.getId());
            }
        } else {
            if (carrier.isPullBackFighters()) {
                carrier.setPullBackFighters(false);
            }
            div.fighterPullbackShipIds.remove(carrier.getId());
        }

        ShipAPI escortAnchor = (div.flagship != null && div.flagship.isAlive() && !div.flagship.isHulk())
                ? div.flagship
                : carrier;

        boolean inVoidstrike = !holdFightersInFormation
                && focusEnemyShip != null
                && focusEnemyShip.isAlive()
                && (div.currentIntent == DivisionIntentType.LAUNCH_DECKLOAD_STRIKE
                || div.currentIntent == DivisionIntentType.LAUNCH_ALPHA_STRIKE
                || div.currentIntent == DivisionIntentType.FLY_CYCLIC_STRIKES
                || div.currentIntent == DivisionIntentType.FLY_FIGHTER_SWEEP
                || div.currentIntent == DivisionIntentType.FLY_ARMED_RECONNAISSANCE
                || div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE
                || div.currentIntent == DivisionIntentType.PRESS_HOME
                || DivisionUiWidgets.distance(carrier.getLocation(), focusEnemyShip.getLocation()) > 650f);

        // Compute attack / patrol heading basis vectors
        float fwdX = div.formationHeadingInitialized ? div.formationHeadingX : 0f;
        float fwdY = div.formationHeadingInitialized ? div.formationHeadingY : (div.side == 0 ? 1f : -1f);
        if (focusEnemyShip != null && focusEnemyShip.isAlive()) {
            float dx = focusEnemyShip.getLocation().x - carrier.getLocation().x;
            float dy = focusEnemyShip.getLocation().y - carrier.getLocation().y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1f) {
                fwdX = dx / len;
                fwdY = dy / len;
            }
        }
        float rightX = fwdY;
        float rightY = -fwdX;

        int reqDepth = Math.max(1, Math.min(10, div.voidstrikeSquadronDepth));
        int reqWidth = Math.max(1, Math.min(10, div.voidstrikeSquadronWidth));
        int totalDivWings = 0;
        int priorCarrierWings = 0;
        List<ShipAPI> divAlive = div.getAliveMembers();
        for (int i = 0; i < divAlive.size(); i++) {
            ShipAPI m = divAlive.get(i);
            int mWings = (m.getAllWings() != null) ? m.getAllWings().size() : 0;
            if (i < carrierIdxInDiv) {
                priorCarrierWings += mWings;
            }
            totalDivWings += mWings;
        }
        int[] compromise = computeCompromiseStrikeWidthAndDepth(Math.max(1, totalDivWings), reqWidth, reqDepth);
        int effSqdnWidth = compromise[0];
        int effSqdnDepth = compromise[1];
        VoidstrikeTargetObservance observance = div.voidstrikeObservance != null
                ? div.voidstrikeObservance
                : VoidstrikeTargetObservance.MEDIUM;

        for (int wIdx = 0; wIdx < wings.size(); wIdx++) {
            com.fs.starfarer.api.combat.FighterWingAPI wing = wings.get(wIdx);
            if (wing == null) continue;
            List<ShipAPI> wingMembers = wing.getWingMembers();
            if (wingMembers == null || wingMembers.isEmpty()) continue;
            ShipAPI wingLeader = wing.getLeader();
            if (wingLeader == null || !wingLeader.isAlive()) {
                wingLeader = wingMembers.get(0);
            }

            boolean isBomberWing = (wing.getSpec() != null && wing.getSpec().isBomber())
                    || (wing.getRole() == com.fs.starfarer.api.loading.WingRole.BOMBER);
            SquadronFormation sqdnForm = isBomberWing
                    ? (inVoidstrike ? div.bomberVoidstrikeFormation : div.bomberFormation)
                    : (inVoidstrike ? div.fighterVoidstrikeFormation : div.fighterFormation);
            int globalSqdnIdx = priorCarrierWings + wIdx;
            int colIdx = inVoidstrike ? (globalSqdnIdx % effSqdnWidth) : 0;
            int rankIdx = inVoidstrike ? ((globalSqdnIdx / effSqdnWidth) % effSqdnDepth) : 0;
            int activeColsInRank = Math.max(1, Math.min(effSqdnWidth, totalDivWings));

            for (int cIdx = 0; cIdx < wingMembers.size(); cIdx++) {
                ShipAPI fighter = wingMembers.get(cIdx);
                if (fighter == null || !fighter.isAlive() || fighter.isHulk()) continue;
                // Respect player-piloted strikecraft (e.g. Arma Armatura neural transfer) and WingCom officer-piloted craft
                if (fighter.getShipAI() == null
                        || (Global.getCombatEngine() != null && fighter == Global.getCombatEngine().getPlayerShip())
                        || (fighter.getCaptain() != null && !fighter.getCaptain().isDefault())) {
                    continue;
                }
                ShipwideAIFlags fFlags = fighter.getAIFlags();
                if (fFlags == null) continue;

                Vector2f slotOffset = computeSquadronCraftSlotOffset(
                        sqdnForm,
                        cIdx,
                        wingMembers.size(),
                        rankIdx,
                        inVoidstrike ? effSqdnDepth : 1,
                        colIdx,
                        inVoidstrike ? activeColsInRank : 1,
                        44f,
                        fwdX,
                        fwdY,
                        rightX,
                        rightY);

                if (holdFightersInFormation) {
                    fFlags.setFlag(AIFlags.DO_NOT_PURSUE, DivisionCommanderAI.FLAG_TTL);
                    fFlags.setFlag(AIFlags.ESCORT_OTHER_SHIP, DivisionCommanderAI.FLAG_TTL, escortAnchor);
                    Vector2f patrolSlot = new Vector2f(
                            escortAnchor.getLocation().x + slotOffset.x,
                            escortAnchor.getLocation().y + slotOffset.y);
                    fFlags.setFlag(AIFlags.MOVEMENT_DEST, DivisionCommanderAI.FLAG_TTL, patrolSlot);
                } else if (coordinatedStagingActive && div.voidstrikeStage == 0) {
                    // Coordinated Stage 0: Squadrons launch and circle/orbit around their carrier
                    double orbitAng = (now * 1.45) + (wIdx * 1.57) + (cIdx * 0.55);
                    float orbitRad = Math.max(135f, carrier.getCollisionRadius() + 75f);
                    Vector2f orbitPt = new Vector2f(
                            carrier.getLocation().x + (float) Math.cos(orbitAng) * orbitRad,
                            carrier.getLocation().y + (float) Math.sin(orbitAng) * orbitRad);
                    fighter.setShipTarget(null);
                    fFlags.unsetFlag(AIFlags.MANEUVER_TARGET);
                    fFlags.unsetFlag(AIFlags.CARRIER_FIGHTER_TARGET);
                    fFlags.setFlag(AIFlags.DO_NOT_PURSUE, DivisionCommanderAI.FLAG_TTL);
                    fFlags.setFlag(AIFlags.ESCORT_OTHER_SHIP, DivisionCommanderAI.FLAG_TTL, carrier);
                    fFlags.setFlag(AIFlags.MOVEMENT_DEST, DivisionCommanderAI.FLAG_TTL, orbitPt);
                    fFlags.setFlag(AIFlags.CAMP_LOCATION, DivisionCommanderAI.FLAG_TTL, orbitPt);
                } else if (coordinatedStagingActive && div.voidstrikeStage == 1) {
                    // Coordinated Stage 1: Squadrons move to nearby marshalling point and dress into Voidstrike formation, width & depth.
                    // Clear fighter shipTarget and gently pace fast interceptors/fighters at marshalSlot so they wait for slower bombers!
                    Vector2f marshalSlot = new Vector2f(
                            div.voidstrikeMarshalPoint.x + slotOffset.x,
                            div.voidstrikeMarshalPoint.y + slotOffset.y);
                    fighter.setShipTarget(null);
                    fFlags.unsetFlag(AIFlags.MANEUVER_TARGET);
                    fFlags.unsetFlag(AIFlags.CARRIER_FIGHTER_TARGET);
                    fFlags.setFlag(AIFlags.DO_NOT_PURSUE, DivisionCommanderAI.FLAG_TTL);
                    fFlags.setFlag(AIFlags.MOVEMENT_DEST, DivisionCommanderAI.FLAG_TTL, marshalSlot);
                    fFlags.setFlag(AIFlags.CAMP_LOCATION, DivisionCommanderAI.FLAG_TTL, marshalSlot);
                    float dMarshal = DivisionUiWidgets.distance(fighter.getLocation(), marshalSlot);
                    if (dMarshal <= 260f && fighter.getVelocity() != null) {
                        // Gentle velocity damping/steering at the marshal point so early-arriving wings hold formation
                        float toSlotX = marshalSlot.x - fighter.getLocation().x;
                        float toSlotY = marshalSlot.y - fighter.getLocation().y;
                        float desiredVx = toSlotX * 0.85f + carrier.getVelocity().x * 0.5f;
                        float desiredVy = toSlotY * 0.85f + carrier.getVelocity().y * 0.5f;
                        fighter.getVelocity().set(
                                fighter.getVelocity().x * 0.82f + desiredVx * 0.18f,
                                fighter.getVelocity().y * 0.82f + desiredVy * 0.18f);
                    }
                } else if (focusEnemyShip != null && focusEnemyShip.isAlive()) {
                    float dEnemy = DivisionUiWidgets.distance(fighter.getLocation(), focusEnemyShip.getLocation());

                    // Item C: Target Selection Observance (FREE, MEDIUM, HIGH)
                    if (observance == VoidstrikeTargetObservance.HIGH) {
                        fFlags.setFlag(AIFlags.MANEUVER_TARGET, DivisionCommanderAI.FLAG_TTL * 1.5f, focusEnemyShip);
                        fFlags.setFlag(AIFlags.CARRIER_FIGHTER_TARGET, DivisionCommanderAI.FLAG_TTL * 1.5f, focusEnemyShip);
                        fFlags.setFlag(AIFlags.DO_NOT_BACK_OFF, DivisionCommanderAI.FLAG_TTL);
                        fighter.setShipTarget(focusEnemyShip);
                    } else if (observance == VoidstrikeTargetObservance.MEDIUM) {
                        fFlags.setFlag(AIFlags.MANEUVER_TARGET, DivisionCommanderAI.FLAG_TTL, focusEnemyShip);
                        ShipAPI currTgt = fighter.getShipTarget();
                        boolean hasCloseInterceptor = currTgt != null
                                && currTgt.isAlive()
                                && !currTgt.isHulk()
                                && currTgt != focusEnemyShip
                                && DivisionUiWidgets.distance(fighter.getLocation(), currTgt.getLocation()) <= 680f;
                        if (!hasCloseInterceptor) {
                            fighter.setShipTarget(focusEnemyShip);
                        }
                    } else {
                        // FREE observance: allow engaging targets of opportunity along flight path
                        ShipAPI currTgt = fighter.getShipTarget();
                        boolean hasOpportunityTarget = currTgt != null
                                && currTgt.isAlive()
                                && !currTgt.isHulk()
                                && DivisionUiWidgets.distance(fighter.getLocation(), currTgt.getLocation()) <= 1250f;
                        if (!hasOpportunityTarget) {
                            fFlags.setFlag(AIFlags.MANEUVER_TARGET, DivisionCommanderAI.FLAG_TTL, focusEnemyShip);
                        }
                    }

                    // Dress squadron formation & Voidstrike depth (1..10) on the approach leg (dEnemy > 520f)
                    if (wingLeader != null && wingLeader.isAlive() && dEnemy > 520f) {
                        Vector2f leaderLoc = wingLeader.getLocation();
                        Vector2f desiredCraftPos = new Vector2f(
                                leaderLoc.x + slotOffset.x,
                                leaderLoc.y + slotOffset.y);
                        if (fighter != wingLeader) {
                            float dSlot = DivisionUiWidgets.distance(fighter.getLocation(), desiredCraftPos);
                            if (dSlot > 75f) {
                                fFlags.setFlag(AIFlags.MOVEMENT_DEST, DivisionCommanderAI.FLAG_TTL, desiredCraftPos);
                            }
                            if (dSlot > 380f && dEnemy > 850f) {
                                fFlags.setFlag(AIFlags.ESCORT_OTHER_SHIP, DivisionCommanderAI.FLAG_TTL, wingLeader);
                            }
                        } else if ((rankIdx > 0 || activeColsInRank > 1) && dEnemy > 750f) {
                            // Echelon squadron columns (width 1..10) and deeper squadron ranks (depth 1..10)
                            float centeredCol = colIdx - (activeColsInRank - 1) * 0.5f;
                            Vector2f rankLeadTarget = new Vector2f(
                                    focusEnemyShip.getLocation().x - fwdX * (rankIdx * 110f) + rightX * (centeredCol * 95f),
                                    focusEnemyShip.getLocation().y - fwdY * (rankIdx * 110f) + rightY * (centeredCol * 95f));
                            fFlags.setFlag(AIFlags.MOVEMENT_DEST, DivisionCommanderAI.FLAG_TTL, rankLeadTarget);
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Voidstrike Instantiation, Multi-Division Merging & Lifecycle Tracking
    // =========================================================================

    static class WingStrikeCandidate {
        final int side;
        final String wingKey;
        final String divisionId;
        final DivisionState division;
        final String targetKey;
        final DivisionState targetDivision;
        final boolean isBomberWing;
        final Vector2f targetPos;
        final List<ShipAPI> activeFighters;
        final Vector2f center;

        WingStrikeCandidate(
                int side,
                String wingKey,
                String divisionId,
                DivisionState division,
                String targetKey,
                DivisionState targetDivision,
                boolean isBomberWing,
                Vector2f targetPos,
                List<ShipAPI> activeFighters,
                Vector2f center) {
            this.side = side;
            this.wingKey = wingKey;
            this.divisionId = divisionId;
            this.division = division;
            this.targetKey = targetKey;
            this.targetDivision = targetDivision;
            this.isBomberWing = isBomberWing;
            this.targetPos = targetPos;
            this.activeFighters = activeFighters;
            this.center = center;
        }
    }

    public static List<VoidstrikeRecord> getActiveVoidstrikes() {
        return Collections.unmodifiableList(activeVoidstrikes);
    }

    public static int getNextVoidstrikeNumberForSide(int side) {
        int idx = (side == VOIDSTRIKE_SIDE_ALLIED) ? 2 : ((side == VOIDSTRIKE_SIDE_ENEMY) ? 1 : 0);
        return nextVoidstrikeNumberBySide[idx];
    }

    /**
     * Evaluates all airborne strike craft across Player (side=0), Enemy (side=1), and Allied (side=2) battle groups:
     *  - Individual sides (Player, Enemy, Allies) maintain separate Voidstrike counters.
     *  - Instantiates and registers Voidstrikes for strike craft groups assigned an enemy target.
     *  - Merges multi-division strike craft attacking the same target (or flying within 1450u) into ONE Voidstrike.
     *  - Updates the live centroid ("Voidstrike #" position) of each active Voidstrike.
     *  - Removes a Voidstrike as soon as its strike craft begin returning to their carrier(s), causing
     *    "Voidstrike #" to disappear and the next instantiated Voidstrike to use the incremented counter.
     *  - Preserves existing Voidstrike identity across temporary Fog-of-War occlusion or brief target switches
     *    so the Voidstrike counter never runs away to 120+.
     */
    /**
     * Lightweight 60+ FPS render-loop helper: updates live fighter positions for active Voidstrikes in O(K)
     * every frame so "Voidstrike #" labels glide smoothly, while throttling full wing-clustering scans to ~7 Hz.
     */
    public static void refreshVoidstrikesForRender(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup,
            float now) {
        if (engine != lastVoidstrikeUpdateEngine || Math.abs(now - lastVoidstrikeUpdateTime) >= 0.45f) {
            updateVoidstrikes(engine, playerGroup, alliedAiGroup, enemyGroup, now);
            return;
        }
        if (activeVoidstrikes.isEmpty()) return;
        FogOfWarAPI playerFog = engine != null ? engine.getFogOfWar(0) : null;
        for (VoidstrikeRecord rec : activeVoidstrikes) {
            if (rec == null || rec.activeFighters.isEmpty()) continue;
            float sx = 0f, sy = 0f;
            int cnt = 0;
            float vsx = 0f, vsy = 0f;
            int vcnt = 0;
            for (ShipAPI fm : rec.activeFighters) {
                if (fm == null || !fm.isAlive() || fm.isHulk() || fm.isExpired()) continue;
                sx += fm.getLocation().x;
                sy += fm.getLocation().y;
                cnt++;
                if (rec.side != VOIDSTRIKE_SIDE_ENEMY || playerFog == null || playerFog.isVisible(fm)) {
                    vsx += fm.getLocation().x;
                    vsy += fm.getLocation().y;
                    vcnt++;
                }
            }
            if (cnt > 0) {
                rec.center.set(sx / cnt, sy / cnt);
            }
            if (rec.side != VOIDSTRIKE_SIDE_ENEMY) {
                rec.visibleToPlayer = cnt > 0;
                rec.visibleCenter.set(rec.center);
            } else {
                rec.visibleToPlayer = vcnt > 0;
                if (vcnt > 0) {
                    rec.visibleCenter.set(vsx / vcnt, vsy / vcnt);
                }
            }
        }
    }

    public static void updateVoidstrikes(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup,
            float now) {

        lastVoidstrikeUpdateEngine = engine;
        lastVoidstrikeUpdateTime = now;
        lastVoidstrikePlayerGroup = playerGroup;
        lastVoidstrikeAlliedGroup = alliedAiGroup;
        lastVoidstrikeEnemyGroup = enemyGroup;

        List<WingStrikeCandidate> candidatesPlayer = new ArrayList<>();
        List<WingStrikeCandidate> candidatesEnemy = new ArrayList<>();
        List<WingStrikeCandidate> candidatesAllied = new ArrayList<>();
        Set<String> visitedWingKeys = new HashSet<>();
        Set<String> returningWingKeys = new HashSet<>();

        Set<String> activeWingKeysSnapshot = new HashSet<>();
        for (VoidstrikeRecord rec : activeVoidstrikes) {
            if (rec != null) {
                activeWingKeysSnapshot.addAll(rec.participatingWingKeys);
            }
        }
        for (VoidstrikeRecord rec : recentVoidstrikeHistory) {
            if (rec != null && (now - rec.lastActiveTime) <= 1.25f) {
                activeWingKeysSnapshot.addAll(rec.participatingWingKeys);
            }
        }

        collectWingStrikeCandidatesFromGroup(
                playerGroup, VOIDSTRIKE_SIDE_PLAYER, enemyGroup, visitedWingKeys, activeWingKeysSnapshot, returningWingKeys, candidatesPlayer);
        collectWingStrikeCandidatesFromGroup(
                alliedAiGroup, VOIDSTRIKE_SIDE_ALLIED, enemyGroup, visitedWingKeys, activeWingKeysSnapshot, returningWingKeys, candidatesAllied);
        collectWingStrikeCandidatesFromGroup(
                enemyGroup, VOIDSTRIKE_SIDE_ENEMY, playerGroup, visitedWingKeys, activeWingKeysSnapshot, returningWingKeys, candidatesEnemy);

        // Also scan any unassigned carrier hulls in engine.getShips() so standalone carriers still register Voidstrikes,
        // and cache alive fighters once for O(F) local air-superiority checks.
        cachedAliveFighters.clear();
        if (engine != null && engine.getShips() != null) {
            for (ShipAPI ship : engine.getShips()) {
                if (ship == null || !ship.isAlive() || ship.isHulk()) continue;
                if (ship.isFighter()) {
                    cachedAliveFighters.add(ship);
                    continue;
                }
                if (ship.getAllWings() == null || ship.getAllWings().isEmpty()) continue;
                int side;
                DivisionState div = null;
                if (ship.getOwner() == 0) {
                    if (playerGroup != null) div = playerGroup.shipIdToDivision.get(ship.getId());
                    if (div != null) {
                        side = VOIDSTRIKE_SIDE_PLAYER;
                    } else if (alliedAiGroup != null && alliedAiGroup.shipIdToDivision.containsKey(ship.getId())) {
                        div = alliedAiGroup.shipIdToDivision.get(ship.getId());
                        side = VOIDSTRIKE_SIDE_ALLIED;
                    } else {
                        side = ship.isAlly() ? VOIDSTRIKE_SIDE_ALLIED : VOIDSTRIKE_SIDE_PLAYER;
                    }
                } else {
                    side = VOIDSTRIKE_SIDE_ENEMY;
                    if (enemyGroup != null) div = enemyGroup.shipIdToDivision.get(ship.getId());
                }
                BattleGroupState opp = (side == VOIDSTRIKE_SIDE_ENEMY) ? playerGroup : enemyGroup;
                List<WingStrikeCandidate> targetList = (side == VOIDSTRIKE_SIDE_PLAYER)
                        ? candidatesPlayer
                        : (side == VOIDSTRIKE_SIDE_ALLIED ? candidatesAllied : candidatesEnemy);
                collectWingStrikeCandidatesFromCarrier(
                        ship,
                        div,
                        side,
                        opp,
                        visitedWingKeys,
                        activeWingKeysSnapshot,
                        returningWingKeys,
                        targetList);
            }
        }

        FogOfWarAPI playerFog = (engine != null) ? engine.getFogOfWar(0) : null;
        reconcileVoidstrikesForSide(VOIDSTRIKE_SIDE_PLAYER, candidatesPlayer, returningWingKeys, playerFog, now);
        reconcileVoidstrikesForSide(VOIDSTRIKE_SIDE_ALLIED, candidatesAllied, returningWingKeys, playerFog, now);
        reconcileVoidstrikesForSide(VOIDSTRIKE_SIDE_ENEMY, candidatesEnemy, returningWingKeys, playerFog, now);
    }

    static void collectWingStrikeCandidatesFromGroup(
            BattleGroupState group,
            int side,
            BattleGroupState opposingGroup,
            Set<String> visitedWingKeys,
            Set<String> activeWingKeysSnapshot,
            Set<String> returningWingKeys,
            List<WingStrikeCandidate> outCandidates) {
        if (group == null || group.divisions == null) return;
        for (DivisionState div : group.divisions) {
            if (div == null) continue;
            for (ShipAPI carrier : div.getAliveMembers()) {
                collectWingStrikeCandidatesFromCarrier(
                        carrier,
                        div,
                        side,
                        opposingGroup,
                        visitedWingKeys,
                        activeWingKeysSnapshot,
                        returningWingKeys,
                        outCandidates);
            }
        }
    }

    static void collectWingStrikeCandidatesFromCarrier(
            ShipAPI carrier,
            DivisionState div,
            int side,
            BattleGroupState opposingGroup,
            Set<String> visitedWingKeys,
            Set<String> activeWingKeysSnapshot,
            Set<String> returningWingKeys,
            List<WingStrikeCandidate> outCandidates) {
        if (carrier == null || !carrier.isAlive() || carrier.isHulk() || carrier.isExpired()) return;
        List<com.fs.starfarer.api.combat.FighterWingAPI> wings = carrier.getAllWings();
        if (wings == null || wings.isEmpty()) return;

        // When carrier is pulling back fighters (Hold in Reserve / Retreat / Venting), its strike craft are returning or held on deck.
        // Note: During MARSHAL_FOR_STRIKE or Coordinated Stage 1/2, airborne wings assembling at the marshal point are part
        // of an active/staging Voidstrike and must NOT be discarded as returning!
        boolean stagingOrStriking = div != null
                && div.targetEnemyDivision != null
                && (div.currentIntent == DivisionModel.DivisionIntentType.MARSHAL_FOR_STRIKE
                || div.voidstrikeStage == 1
                || div.voidstrikeStage == 2);
        boolean carrierPullingBack = (div != null && div.voidstrikeEgressActive)
                || (carrier.isPullBackFighters() && !stagingOrStriking)
                || (div != null && (div.currentIntent == DivisionModel.DivisionIntentType.HOLD_IN_RESERVE
                || div.currentIntent == DivisionModel.DivisionIntentType.BREAK_OFF_AND_RETIRE
                || div.currentIntent == DivisionModel.DivisionIntentType.HAUL_OUT_TO_VENT));
        if (carrierPullingBack) {
            for (int wIdx = 0; wIdx < wings.size(); wIdx++) {
                returningWingKeys.add(carrier.getId() + "#wing_" + wIdx);
            }
            return;
        }

        for (int wIdx = 0; wIdx < wings.size(); wIdx++) {
            com.fs.starfarer.api.combat.FighterWingAPI wing = wings.get(wIdx);
            if (wing == null) continue;
            String wingKey = carrier.getId() + "#wing_" + wIdx;
            if (!visitedWingKeys.add(wingKey)) continue;

            List<ShipAPI> members = wing.getWingMembers();
            if (members == null || members.isEmpty()) continue;

            List<ShipAPI> nonReturningFighters = new ArrayList<>(members.size());
            float sumX = 0f;
            float sumY = 0f;
            ShipAPI fighterAssignedEnemy = null;

            for (ShipAPI f : members) {
                if (f == null || !f.isAlive() || f.isHulk() || f.isExpired()) continue;
                // When strike craft are returning to refit/land, they are no longer on an active Voidstrike
                if (wing.isReturning(f)) continue;
                nonReturningFighters.add(f);
                sumX += f.getLocation().x;
                sumY += f.getLocation().y;

                if (fighterAssignedEnemy == null) {
                    ShipAPI st = f.getShipTarget();
                    if (st != null && st.isAlive() && !st.isHulk() && st.getOwner() != carrier.getOwner()) {
                        fighterAssignedEnemy = st;
                    } else if (f.getAIFlags() != null) {
                        Object mt = f.getAIFlags().getCustom(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.MANEUVER_TARGET);
                        if (mt instanceof ShipAPI) {
                            ShipAPI mst = (ShipAPI) mt;
                            if (mst.isAlive() && !mst.isHulk() && mst.getOwner() != carrier.getOwner()) {
                                fighterAssignedEnemy = mst;
                            }
                        }
                    }
                }
            }

            // If all fighters in the wing are returning (or destroyed), this wing is not on a Voidstrike
            if (nonReturningFighters.isEmpty()) {
                returningWingKeys.add(wingKey);
                if (div != null) {
                    div.culminatedVoidstrikeWingKeys.remove(wingKey);
                }
                continue;
            }

            Vector2f wingCenter = new Vector2f(
                    sumX / nonReturningFighters.size(),
                    sumY / nonReturningFighters.size());

            // Resolve assigned enemy target (from fighter target, carrier fighter target, carrier target, or division target)
            ShipAPI targetEnemyShip = fighterAssignedEnemy;
            if (targetEnemyShip == null && carrier.getAIFlags() != null) {
                Object cft = carrier.getAIFlags().getCustom(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.CARRIER_FIGHTER_TARGET);
                if (cft instanceof ShipAPI) {
                    ShipAPI s = (ShipAPI) cft;
                    if (s.isAlive() && !s.isHulk() && s.getOwner() != carrier.getOwner()) {
                        targetEnemyShip = s;
                    }
                }
                if (targetEnemyShip == null) {
                    Object mt = carrier.getAIFlags().getCustom(com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags.MANEUVER_TARGET);
                    if (mt instanceof ShipAPI) {
                        ShipAPI s = (ShipAPI) mt;
                        if (s.isAlive() && !s.isHulk() && s.getOwner() != carrier.getOwner()) {
                            targetEnemyShip = s;
                        }
                    }
                }
            }
            if (targetEnemyShip == null) {
                ShipAPI ct = carrier.getShipTarget();
                if (ct != null && ct.isAlive() && !ct.isHulk() && ct.getOwner() != carrier.getOwner()) {
                    targetEnemyShip = ct;
                }
            }

            // Map any fighter target (or enemy ship target) up to its parent enemy division so brief fighter-to-fighter
            // target switches during a dogfight never fragment or re-instantiate a new Voidstrike!
            DivisionState targetEnemyDiv = (div != null && div.targetEnemyDivision != null && div.targetEnemyDivision.isCombatEffective())
                    ? div.targetEnemyDivision
                    : null;
            if (targetEnemyDiv == null && targetEnemyShip != null && opposingGroup != null && opposingGroup.shipIdToDivision != null) {
                ShipAPI lookupShip = targetEnemyShip;
                if (lookupShip.isFighter() && lookupShip.getWing() != null && lookupShip.getWing().getSourceShip() != null) {
                    lookupShip = lookupShip.getWing().getSourceShip();
                }
                targetEnemyDiv = opposingGroup.shipIdToDivision.get(lookupShip.getId());
            }

            // A Voidstrike requires an assigned enemy target (targetEnemyDiv or targetEnemyShip)
            if (targetEnemyDiv == null && targetEnemyShip == null) continue;

            // Top-down instantiation gate (Division -> Carrier): a Voidstrike is a strike SORTIE the division commander
            // ordered, not "any fighter that happens to have a target". Escort/CSP/sweep/recon wings dogfighting near
            // their own line, and point-defence interceptors, are ordinary air activity and never open a Voidstrike.
            if (!isStrikeSortieOrdered(div, wing)) continue;

            // Prevent defensive carrier-escort wings or Stage 0 deck-orbiting wings right on top of their own carrier
            // from registering as an active downrange Voidstrike before they marshal/depart:
            boolean alreadyActive = activeWingKeysSnapshot != null && activeWingKeysSnapshot.contains(wingKey);
            float distFromCarrier = DivisionUiWidgets.distance(wingCenter, carrier.getLocation());
            if (div != null) {
                if (div.currentIntent == DivisionModel.DivisionIntentType.FLY_COMBAT_SPACE_PATROL) {
                    if (targetEnemyShip == null || (!alreadyActive && distFromCarrier < 260f)) {
                        continue;
                    }
                }
                if (div.voidstrikeCoordination == VoidstrikeCoordinationMode.COORDINATED
                        && div.voidstrikeStage == 0
                        && !alreadyActive
                        && distFromCarrier < 260f) {
                    continue;
                }
            }

            // Normalize targetKey to the enemy Division ID whenever known (or to the parent carrier of an enemy fighter)
            // so target key remains rock-solid throughout the entire strike sortie!
            String targetKey;
            if (targetEnemyDiv != null) {
                targetKey = "DIV:" + targetEnemyDiv.id;
            } else if (targetEnemyShip != null
                    && targetEnemyShip.isFighter()
                    && targetEnemyShip.getWing() != null
                    && targetEnemyShip.getWing().getSourceShip() != null) {
                targetKey = "SHIP:" + targetEnemyShip.getWing().getSourceShip().getId();
            } else {
                targetKey = "SHIP:" + targetEnemyShip.getId();
            }

            // Clear per-wing culmination lock if the division switched targets, or once the division has physically
            // completed egress recovery (!div.voidstrikeEgressActive) and the wing is back inside the carrier's
            // recovery/marshalling envelope (or all craft returned/refitted on deck at line 978).
            if (div != null) {
                if (!div.voidstrikeEgressActive
                        && div.culminatedVoidstrikeTargetKey != null && !div.culminatedVoidstrikeTargetKey.equals(targetKey)) {
                    div.culminatedVoidstrikeWingKeys.clear();
                    div.culminatedVoidstrikeTargetKey = null;
                }
                if (!div.voidstrikeEgressActive && (div.voidstrikeStage == 1 || distFromCarrier <= 320f)) {
                    div.culminatedVoidstrikeWingKeys.remove(wingKey);
                }
                if (div.culminatedVoidstrikeWingKeys.contains(wingKey)) {
                    continue;
                }
            }

            boolean isBomberWing = (wing.getSpec() != null && wing.getSpec().isBomber())
                    || (wing.getRole() == com.fs.starfarer.api.loading.WingRole.BOMBER);
            Vector2f resolvedTargetPos = null;
            if (targetEnemyShip != null && targetEnemyShip.getLocation() != null) {
                resolvedTargetPos = new Vector2f(targetEnemyShip.getLocation());
            } else if (targetEnemyDiv != null) {
                if (targetEnemyDiv.centroid.x != 0f || targetEnemyDiv.centroid.y != 0f) {
                    resolvedTargetPos = new Vector2f(targetEnemyDiv.centroid);
                } else if (targetEnemyDiv.flagship != null && targetEnemyDiv.flagship.getLocation() != null) {
                    resolvedTargetPos = new Vector2f(targetEnemyDiv.flagship.getLocation());
                }
            }
            String divId = (div != null && div.id != null) ? div.id : ("SHIP_DIV:" + carrier.getId());

            outCandidates.add(new WingStrikeCandidate(
                    side,
                    wingKey,
                    divId,
                    div,
                    targetKey,
                    targetEnemyDiv,
                    isBomberWing,
                    resolvedTargetPos,
                    nonReturningFighters,
                    wingCenter));
        }
    }

    static void reconcileVoidstrikesForSide(
            int side,
            List<WingStrikeCandidate> candidates,
            Set<String> returningWingKeys,
            FogOfWarAPI playerFog,
            float now) {

        // 1. Cluster active WingStrikeCandidates so multi-division voidstrikes against the same target
        // or flying within 1500u of each other toward the same target are merged into ONE single Voidstrike!
        int n = candidates.size();
        boolean[] visited = new boolean[n];
        List<List<WingStrikeCandidate>> mergedClusters = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (visited[i]) continue;
            List<WingStrikeCandidate> cluster = new ArrayList<>();
            cluster.add(candidates.get(i));
            visited[i] = true;

            boolean addedAny = true;
            while (addedAny) {
                addedAny = false;
                for (int j = 0; j < n; j++) {
                    if (visited[j]) continue;
                    WingStrikeCandidate candJ = candidates.get(j);
                    boolean shouldMerge = false;
                    float distSeed = DivisionUiWidgets.distance(cluster.get(0).center, candJ.center);
                    for (WingStrikeCandidate member : cluster) {
                        float distCenters = DivisionUiWidgets.distance(member.center, candJ.center);
                        // One Voidstrike per Division and one merged Voidstrike per shared target on the same side:
                        // trailing replacement wings from the same division or converging multi-division strikes on
                        // the same target always merge into the existing strike rather than burning new numbers!
                        boolean sameDiv = member.divisionId.equals(candJ.divisionId);
                        boolean sameTarget = member.targetKey.equals(candJ.targetKey);
                        if (sameDiv || sameTarget || (distCenters <= 1150f && distSeed <= 1350f)) {
                            shouldMerge = true;
                            break;
                        }
                    }
                    if (shouldMerge) {
                        cluster.add(candJ);
                        visited[j] = true;
                        addedAny = true;
                    }
                }
            }
            mergedClusters.add(cluster);
        }

        // 2. Collect existing VoidstrikeRecords for this side, plus any recently-ended records within a 1.25s
        // hysteresis window (so a 1-frame target switch or Fog-of-War flicker never increments the counter to 120+!)
        List<VoidstrikeRecord> existingForSide = new ArrayList<>();
        Iterator<VoidstrikeRecord> it = activeVoidstrikes.iterator();
        while (it.hasNext()) {
            VoidstrikeRecord rec = it.next();
            if (rec.side == side) {
                existingForSide.add(rec);
                it.remove();
            }
        }
        Iterator<VoidstrikeRecord> histIt = recentVoidstrikeHistory.iterator();
        while (histIt.hasNext()) {
            VoidstrikeRecord hRec = histIt.next();
            if ((now - hRec.lastActiveTime) > 1.25f) {
                histIt.remove();
            } else if (hRec.side == side) {
                existingForSide.add(hRec);
                histIt.remove();
            }
        }

        boolean[] existingUsed = new boolean[existingForSide.size()];
        List<VoidstrikeRecord> updatedForSide = new ArrayList<>(mergedClusters.size());
        int sideIdx = (side == VOIDSTRIKE_SIDE_ALLIED) ? 2 : ((side == VOIDSTRIKE_SIDE_ENEMY) ? 1 : 0);

        for (List<WingStrikeCandidate> cluster : mergedClusters) {
            Set<String> clusterWingKeys = new java.util.LinkedHashSet<>();
            Set<String> clusterBomberWingKeys = new java.util.LinkedHashSet<>();
            Set<String> clusterDivIds = new java.util.LinkedHashSet<>();
            Set<DivisionState> clusterDivs = new java.util.LinkedHashSet<>();
            List<ShipAPI> clusterFighters = new ArrayList<>();
            String primaryTargetKey = cluster.get(0).targetKey;
            Vector2f primaryTargetPos = cluster.get(0).targetPos;

            float sumX = 0f;
            float sumY = 0f;
            float visSumX = 0f;
            float visSumY = 0f;
            int visCount = 0;

            for (WingStrikeCandidate wc : cluster) {
                clusterWingKeys.add(wc.wingKey);
                if (wc.isBomberWing) {
                    clusterBomberWingKeys.add(wc.wingKey);
                }
                clusterDivIds.add(wc.divisionId);
                if (wc.division != null) {
                    clusterDivs.add(wc.division);
                }
                if (primaryTargetPos == null && wc.targetPos != null) {
                    primaryTargetPos = wc.targetPos;
                }
                for (ShipAPI f : wc.activeFighters) {
                    clusterFighters.add(f);
                    sumX += f.getLocation().x;
                    sumY += f.getLocation().y;
                    if (side != VOIDSTRIKE_SIDE_ENEMY || playerFog == null || playerFog.isVisible(f)) {
                        visSumX += f.getLocation().x;
                        visSumY += f.getLocation().y;
                        visCount++;
                    }
                }
            }

            if (clusterFighters.isEmpty()) continue;

            boolean isVisibleToPlayer = (side != VOIDSTRIKE_SIDE_ENEMY) || (visCount > 0);
            Vector2f clusterCenter = new Vector2f(
                    sumX / clusterFighters.size(),
                    sumY / clusterFighters.size());
            Vector2f clusterVisCenter = (visCount > 0)
                    ? new Vector2f(visSumX / visCount, visSumY / visCount)
                    : new Vector2f(clusterCenter);

            float spread = 0f;
            for (ShipAPI f : clusterFighters) {
                spread += DivisionUiWidgets.distance(f.getLocation(), clusterCenter);
            }
            spread = clusterFighters.isEmpty() ? 0f : spread / clusterFighters.size();

            // Match to an existing VoidstrikeRecord:
            //  - Do NOT glue a cluster onto an old VoidstrikeRecord that already reached its old target (ex.reachedTarget)
            //    if the target has changed (!sameTarget) — when a target is destroyed after engagement, that Voidstrike is over!
            //  - Do NOT match by sharesDiv alone if sharesWing is false and !sameTarget.
            VoidstrikeRecord matched = null;
            int bestMatchIdx = -1;
            int bestMatchPriority = -1;
            for (int eIdx = 0; eIdx < existingForSide.size(); eIdx++) {
                if (existingUsed[eIdx]) continue;
                VoidstrikeRecord ex = existingForSide.get(eIdx);
                boolean sharesWing = false;
                for (String wk : clusterWingKeys) {
                    if (ex.participatingWingKeys.contains(wk)) {
                        sharesWing = true;
                        break;
                    }
                }
                boolean sharesDiv = false;
                for (String dId : clusterDivIds) {
                    if (ex.participatingDivisionIds.contains(dId)) {
                        sharesDiv = true;
                        break;
                    }
                }
                boolean sameTarget = ex.targetKey != null && ex.targetKey.equals(primaryTargetKey);
                boolean closeCenter = DivisionUiWidgets.distance(ex.center, clusterCenter) <= 1650f;
                boolean canRedirectMidFlight = !ex.reachedTarget && (ex.createdTime < 0f || (now - ex.createdTime) <= 18.0f);
                boolean validMatch = sameTarget
                        || (sharesDiv && (!ex.reachedTarget || sameTarget))
                        || (sharesWing && (!ex.reachedTarget || sameTarget || canRedirectMidFlight))
                        || (closeCenter && (sharesDiv || sharesWing));
                if (validMatch) {
                    int prio = (sharesWing ? 4 : 0) + (sharesDiv ? 2 : 0) + (sameTarget ? 1 : 0);
                    if (matched == null
                            || prio > bestMatchPriority
                            || (prio == bestMatchPriority && ex.voidstrikeNumber > 0
                            && (matched.voidstrikeNumber <= 0 || ex.voidstrikeNumber < matched.voidstrikeNumber))) {
                        matched = ex;
                        bestMatchIdx = eIdx;
                        bestMatchPriority = prio;
                    }
                }
            }
            if (bestMatchIdx >= 0) {
                existingUsed[bestMatchIdx] = true;
            }

            if (matched == null) {
                // Do not birth a stillborn Voidstrike if the candidate fighters have not yet formed spatial cohesion!
                if (clusterFighters.size() >= 3 && spread > SORTIE_COHESION_RADIUS) {
                    continue;
                }
                // For enemy side, keep tracking the cluster with voidstrikeNumber = 0 while hidden in Fog of War,
                // and only allocate a Voidstrike number once it becomes visible to the player!
                int assignedNumber = (side == VOIDSTRIKE_SIDE_ENEMY && !isVisibleToPlayer)
                        ? 0
                        : claimSortieNumber(clusterDivs, updatedForSide, sideIdx);
                matched = new VoidstrikeRecord(side, assignedNumber, primaryTargetKey);
                matched.createdTime = now;
            } else if (matched.voidstrikeNumber <= 0 && (side != VOIDSTRIKE_SIDE_ENEMY || isVisibleToPlayer)) {
                if (clusterFighters.size() >= 3 && spread > SORTIE_COHESION_RADIUS) {
                    continue;
                }
                matched.voidstrikeNumber = claimSortieNumber(clusterDivs, updatedForSide, sideIdx);
                if (matched.createdTime < 0f) {
                    matched.createdTime = now;
                }
            }

            if (matched.createdTime < 0f) {
                matched.createdTime = now;
            }
            if (spread <= SORTIE_COHESION_RADIUS) {
                matched.achievedCohesion = true;
            }
            matched.initialBomberWingKeys.addAll(clusterBomberWingKeys);
            if (primaryTargetPos != null && DivisionUiWidgets.distance(clusterCenter, primaryTargetPos) <= 780f) {
                if (!matched.reachedTarget) {
                    matched.reachedTarget = true;
                    matched.reachedTargetTime = now;
                }
            }

            // Bounded sortie culmination. A Voidstrike is a discrete strike sortie, not a standing state:
            //  1. Ordnance expended: every bomber wing has released and turned for home.
            //  2. Strike pass complete: STRIKE_PASS_CULMINATION_SECONDS after arrival over the target.
            //  3. Approach timeout (never arrived) or MAX_SORTIE_DURATION_SECONDS total.
            //  4. Target escaped (opened beyond TARGET_ESCAPE_DISTANCE after arrival) or rejoined its main line.
            //  5. Attackers lost cohesion (spread > SORTIE_COHESION_RADIUS) or local advantage (attrition / enemy CAP).
            if (clusterFighters.size() > matched.peakFighterCount) {
                matched.peakFighterCount = clusterFighters.size();
            }
            float sortieAge = now - matched.createdTime;
            boolean bombersDeliveredAndReturning = !matched.initialBomberWingKeys.isEmpty()
                    && (matched.reachedTarget || sortieAge >= STRIKE_PASS_CULMINATION_SECONDS)
                    && !containsAny(clusterBomberWingKeys, matched.initialBomberWingKeys)
                    && returningWingKeys != null
                    && returningWingKeys.containsAll(matched.initialBomberWingKeys);
            boolean strikePassComplete = matched.reachedTarget
                    && matched.reachedTargetTime >= 0f
                    && (now - matched.reachedTargetTime) > STRIKE_PASS_CULMINATION_SECONDS;
            boolean approachTimedOut = !matched.reachedTarget && sortieAge > APPROACH_TIMEOUT_SECONDS;
            boolean maxSortieDurationExpired = sortieAge > MAX_SORTIE_DURATION_SECONDS;
            boolean targetEscaped = false;
            boolean targetRejoinedLine = false;
            if (primaryTargetPos != null) {
                float dTarget = DivisionUiWidgets.distance(clusterCenter, primaryTargetPos);
                targetEscaped = matched.reachedTarget && dTarget > TARGET_ESCAPE_DISTANCE;
            }
            DivisionState primaryTargetDiv = null;
            for (WingStrikeCandidate wc : cluster) {
                if (wc.targetDivision != null) {
                    primaryTargetDiv = wc.targetDivision;
                    break;
                }
            }
            if (primaryTargetDiv != null && sortieAge > 4f) {
                boolean isolatedAtLaunch = matched.targetIsolatedAtLaunch;
                boolean supportedNow = isTargetSupportedByMainLine(primaryTargetDiv);
                if (sortieAge <= 4.5f) {
                    matched.targetIsolatedAtLaunch = !supportedNow;
                } else if (isolatedAtLaunch && supportedNow) {
                    targetRejoinedLine = true;
                }
            }
            boolean canCulminateThisFrame = (now - matched.createdTime) >= 3.0f;
            boolean lostCohesion = canCulminateThisFrame
                    && matched.achievedCohesion
                    && clusterDivs.size() <= 1
                    && clusterWingKeys.size() <= 1
                    && clusterFighters.size() >= 3
                    && spread > SORTIE_COHESION_RADIUS * 1.25f;
            boolean lostAdvantage = canCulminateThisFrame
                    && matched.peakFighterCount >= 4
                    && clusterFighters.size() < Math.ceil(matched.peakFighterCount * SORTIE_ATTRITION_FRACTION);
            if (!lostAdvantage
                    && canCulminateThisFrame
                    && primaryTargetDiv != null
                    && matched.reachedTarget
                    && matched.reachedTargetTime >= 0f
                    && (now - matched.reachedTargetTime) >= 4.0f
                    && clusterFighters.size() < matched.peakFighterCount) {
                int hostileInterceptors = countHostileFightersNear(primaryTargetDiv, clusterCenter, 800f);
                lostAdvantage = hostileInterceptors >= Math.max(6, Math.round(clusterFighters.size() * 2.0f));
            }
            String culminationReason = null;
            if (bombersDeliveredAndReturning) culminationReason = "ordnance expended";
            else if (strikePassComplete) culminationReason = "strike pass complete";
            else if (approachTimedOut) culminationReason = "approach timed out";
            else if (maxSortieDurationExpired) culminationReason = "sortie time limit";
            else if (targetEscaped) culminationReason = "target escaped";
            else if (targetRejoinedLine) culminationReason = "target rejoined main line";
            else if (lostCohesion) culminationReason = "lost cohesion";
            else if (lostAdvantage) culminationReason = "lost local advantage";
            if (culminationReason != null) {
                matched.culminationReason = culminationReason;
                for (DivisionState d : clusterDivs) {
                    if (d != null) {
                        d.culminatedVoidstrikeTargetKey = primaryTargetKey;
                        d.culminatedVoidstrikeWingKeys.addAll(clusterWingKeys);
                        beginVoidstrikeEgress(d, now);
                    }
                }
                releaseSortieNumber(matched);
                if (matched.voidstrikeNumber > 0) {
                    DivisionCombatLog.logVoidstrikeCulminated(side, matched.voidstrikeNumber, culminationReason, now);
                }
                continue;
            }

            if (matched.voidstrikeNumber > 0) {
                for (DivisionState d : clusterDivs) {
                    if (d != null && !d.voidstrikeEgressActive) {
                        d.activeVoidstrikeSortieNumber = matched.voidstrikeNumber;
                    }
                }
            }
            matched.targetKey = primaryTargetKey;
            matched.participatingDivisionIds.clear();
            matched.participatingDivisionIds.addAll(clusterDivIds);
            matched.participatingWingKeys.clear();
            matched.participatingWingKeys.addAll(clusterWingKeys);
            matched.activeFighters.clear();
            matched.activeFighters.addAll(clusterFighters);
            matched.center.set(clusterCenter);
            matched.visibleCenter.set(clusterVisCenter);
            matched.visibleToPlayer = isVisibleToPlayer && (matched.voidstrikeNumber > 0);
            if (isVisibleToPlayer || matched.voidstrikeNumber > 0) {
                matched.lastActiveTime = now;
            }
            updatedForSide.add(matched);
        }

        // Preserve any unmatched VoidstrikeRecord whose wings did NOT actually start returning (e.g. a 1-frame
        // target switch) for up to 1.25s in recentVoidstrikeHistory so flicker never burns a new Voidstrike number,
        // while any Voidstrike whose strike craft returned to carrier is retired immediately so the next launch
        // increments the counter!
        for (int eIdx = 0; eIdx < existingForSide.size(); eIdx++) {
            if (!existingUsed[eIdx]) {
                VoidstrikeRecord leftover = existingForSide.get(eIdx);
                boolean allWingsReturned = !leftover.participatingWingKeys.isEmpty()
                        && returningWingKeys != null
                        && returningWingKeys.containsAll(leftover.participatingWingKeys);
                if (!allWingsReturned && leftover.voidstrikeNumber > 0 && (now - leftover.lastActiveTime) <= 1.25f) {
                    recentVoidstrikeHistory.add(leftover);
                } else if (leftover.voidstrikeNumber > 0) {
                    releaseSortieNumber(leftover);
                }
            }
        }

        activeVoidstrikes.addAll(updatedForSide);
    }

    /**
     * Division-level sortie order check. Strike intents (deckload / alpha / cyclic strikes, marshalling) or an
     * active coordinated stage open Voidstrikes; air-superiority / patrol / recon intents do not. A carrier with no
     * division (unassigned hull) only counts with a bomber wing actually committed to a ship target.
     */
    static boolean isStrikeSortieOrdered(DivisionState div, com.fs.starfarer.api.combat.FighterWingAPI wing) {
        boolean isBomber = wing != null && ((wing.getSpec() != null && wing.getSpec().isBomber())
                || wing.getRole() == com.fs.starfarer.api.loading.WingRole.BOMBER);
        if (div == null) {
            return isBomber;
        }
        if (div.voidstrikeEgressActive) {
            return false;
        }
        DivisionModel.DivisionIntentType in = div.currentIntent;
        if (in == DivisionModel.DivisionIntentType.LAUNCH_DECKLOAD_STRIKE
                || in == DivisionModel.DivisionIntentType.LAUNCH_ALPHA_STRIKE
                || in == DivisionModel.DivisionIntentType.FLY_CYCLIC_STRIKES
                || in == DivisionModel.DivisionIntentType.MARSHAL_FOR_STRIKE) {
            return true;
        }
        if ((in == DivisionModel.DivisionIntentType.TOT_ALPHA_STRIKE
                || in == DivisionModel.DivisionIntentType.PRESS_HOME) && isBomber) {
            return true;
        }
        return false;
    }

    /**
     * Returns the Voidstrike number for a newly-seen strike cluster: the open sortie number of a participating
     * division when one exists and is not already displayed by another live record this tick, otherwise the next
     * counter value for the side.
     */
    static int claimSortieNumber(Set<DivisionState> clusterDivs, List<VoidstrikeRecord> liveThisTick, int sideIdx) {
        if (clusterDivs != null) {
            for (DivisionState d : clusterDivs) {
                if (d == null || d.activeVoidstrikeSortieNumber <= 0 || d.voidstrikeEgressActive) continue;
                boolean taken = false;
                for (VoidstrikeRecord r : liveThisTick) {
                    if (r != null && r.voidstrikeNumber == d.activeVoidstrikeSortieNumber) { taken = true; break; }
                }
                for (VoidstrikeRecord r : activeVoidstrikes) {
                    if (r != null && r.voidstrikeNumber == d.activeVoidstrikeSortieNumber
                            && r.side == (sideIdx == 2 ? VOIDSTRIKE_SIDE_ALLIED : (sideIdx == 1 ? VOIDSTRIKE_SIDE_ENEMY : VOIDSTRIKE_SIDE_PLAYER))) {
                        taken = true; break;
                    }
                }
                if (!taken) return d.activeVoidstrikeSortieNumber;
            }
        }
        return nextVoidstrikeNumberBySide[sideIdx]++;
    }

    /** Sortie over without a culmination event (all craft recovered / record expired): free the division's number. */
    static void releaseSortieNumber(VoidstrikeRecord rec) {
        if (rec == null || rec.voidstrikeNumber <= 0) return;
        for (BattleGroupState g : new BattleGroupState[] { lastVoidstrikePlayerGroup, lastVoidstrikeAlliedGroup, lastVoidstrikeEnemyGroup }) {
            if (g == null || g.divisions == null) continue;
            for (DivisionState d : g.divisions) {
                if (d != null && (d.activeVoidstrikeSortieNumber == rec.voidstrikeNumber
                        || (d.id != null && rec.participatingDivisionIds.contains(d.id)))) {
                    d.activeVoidstrikeSortieNumber = 0;
                }
            }
        }
    }

    static boolean containsAny(Set<String> a, Set<String> b) {
        if (a == null || b == null) return false;
        for (String k : b) {
            if (a.contains(k)) return true;
        }
        return false;
    }

    /** True when another able division of the target's side is within TARGET_REJOIN_RADIUS (the target is back in line). */
    static boolean isTargetSupportedByMainLine(DivisionState target) {
        if (target == null) return false;
        BattleGroupState g = null;
        for (BattleGroupState cand : new BattleGroupState[] { lastVoidstrikePlayerGroup, lastVoidstrikeAlliedGroup, lastVoidstrikeEnemyGroup }) {
            if (cand != null && cand.divisions != null && cand.divisions.contains(target)) {
                g = cand;
                break;
            }
        }
        if (g == null || g.divisions == null) return false;
        for (DivisionState other : g.divisions) {
            if (other == null || other == target || !other.isAbleCombatEffective()) continue;
            if (DivisionUiWidgets.distance(other.centroid, target.centroid)
                    <= TARGET_REJOIN_RADIUS + other.stationKeepingRadius + target.stationKeepingRadius) {
                return true;
            }
        }
        return false;
    }

    /** Counts enemy (defending) fighters near the strike package, used as the local air-superiority check. */
    static int countHostileFightersNear(DivisionState targetDiv, Vector2f center, float radius) {
        if (targetDiv == null) return 0;
        if (!cachedAliveFighters.isEmpty()) {
            int n = 0;
            for (int i = 0, sz = cachedAliveFighters.size(); i < sz; i++) {
                ShipAPI s = cachedAliveFighters.get(i);
                if (s == null || s.getOwner() != targetDiv.side || !s.isAlive() || s.isHulk()) continue;
                if (DivisionUiWidgets.distance(s.getLocation(), center) <= radius) n++;
            }
            return n;
        }
        CombatEngineAPI engine = lastVoidstrikeUpdateEngine;
        if (engine == null || engine.getShips() == null) return 0;
        int n = 0;
        for (ShipAPI s : engine.getShips()) {
            if (s == null || !s.isFighter() || !s.isAlive() || s.isHulk()) continue;
            if (s.getOwner() != targetDiv.side) continue;
            if (DivisionUiWidgets.distance(s.getLocation(), center) <= radius) n++;
        }
        return n;
    }

    /** Culmination -> egress: packages recover toward their carriers (or refit on deck) before starting a new sortie. */
    static void beginVoidstrikeEgress(DivisionState d, float now) {
        if (d == null) return;
        d.voidstrikeEgressActive = true;
        d.activeVoidstrikeSortieNumber = 0;
        d.voidstrikeStageStartTime = now;
        if (d.voidstrikeCoordination == VoidstrikeCoordinationMode.COORDINATED
                && (d.voidstrikeStage == 1 || d.voidstrikeStage == 2)) {
            d.voidstrikeStage = 0;
        }
    }

}
