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
import divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection;
import divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry;
import divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode;
import divisionai.PermanentDivisionManager.VoidstrikeGunneryMode;
import divisionai.PermanentDivisionManager.VoidstrikeSplitMode;
import divisionai.PermanentDivisionManager.VoidstrikeTargetObservance;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.loading.MissileSpecAPI;
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

    public static class MassedStrikePackageProfile {
        public float maxEffectivePackageMass = 0f;
        public float currentArmedPackageMass = 0f;
        public int initialArmedBomberCount = 0;
        public int currentArmedBomberCount = 0;
        public int committedSquadronCount = 0;
        public int committedCraftCount = 0;
        public float effectivePackageSpeed = 180f;
        public float slowestBomberSpeed = 160f;
        public float packageAvgReleaseRange = 520f;
        public float executionDistanceToTarget = 0f;
        public float packageRouteDistance = 0f;
        public float packageApproachTimeoutSec = 48f;
        public float packageStrikePassTimeoutSec = 24f;
    }

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
        public boolean splitWingActive = false;
        public int subWingCount = 1;
        public int distinctTargetCount = 1;
        public final List<Vector2f> subWingCenters = new ArrayList<>();
        public float maxEffectivePackageMass = 0f;
        public float currentArmedPackageMass = 0f;
        public int initialArmedBomberCount = 0;
        public int currentArmedBomberCount = 0;
        public float effectivePackageSpeed = 180f;
        public float slowestBomberSpeed = 160f;
        public float packageAvgReleaseRange = 520f;
        public float executionDistanceToTarget = 0f;
        public float packageRouteDistance = 0f;
        public float packageApproachTimeoutSec = 48f;
        public float packageStrikePassTimeoutSec = 24f;
        public float minDistToTargetSeen = Float.MAX_VALUE;
        public float lastClosingProgressTime = -100f;
        public boolean emergencyRecallTriggered = false;
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
        flankingGateClearedTargetByFighter.clear();
        nextVoidstrikeNumberBySide[0] = 1;
        nextVoidstrikeNumberBySide[1] = 1;
        nextVoidstrikeNumberBySide[2] = 1;
        lastVoidstrikeUpdateEngine = null;
        lastVoidstrikeUpdateTime = -100f;
        lastVoidstrikePlayerGroup = null;
        lastVoidstrikeAlliedGroup = null;
        lastVoidstrikeEnemyGroup = null;
    }

    static final Map<String, String> flankingGateClearedTargetByFighter = new HashMap<>();

    // Bounded Voidstrike sortie doctrine driven by the Maximum Average Effective Massed Strike Package upon execution.
    public static final String VOIDSTRIKE_MAX_RANGE_STAT_ID = "divai_voidstrike_max_range";
    static final float SORTIE_ARRIVAL_RADIUS = 920f;
    static final float APPROACH_TIMEOUT_SECONDS = 48f;
    static final float STRIKE_PASS_CULMINATION_SECONDS = 24f;
    static final float MAX_SORTIE_DURATION_SECONDS = 240f;
    static final float BOMBER_RELEASE_GRACE_SECONDS = 6f;
    static final float TARGET_ESCAPE_DISTANCE = 4200f;
    static final float TARGET_REJOIN_RADIUS = 950f;
    static final float SORTIE_COHESION_RADIUS = 1400f;
    static final float SORTIE_ATTRITION_FRACTION = 0.25f;
    static final float EGRESS_RECOVERY_RADIUS = 700f;
    static final float EGRESS_MAX_SECONDS = 12f;
    static BattleGroupState lastVoidstrikePlayerGroup;
    static BattleGroupState lastVoidstrikeAlliedGroup;
    static BattleGroupState lastVoidstrikeEnemyGroup;
    static final List<ShipAPI> cachedAliveFighters = new ArrayList<>();
    private static MethodHandle fighterAiReturnDataSetter = null;
    private static boolean fighterAiReturnDataSetterResolved = false;

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
            if (hadPreviousTarget) {
                if (div.voidstrikeStage == 2 && (wingsCommittedDownrange > 0 || wingsClearedCarriers > 0)) {
                    return;
                }
                if (div.currentIntent != DivisionIntentType.MARSHAL_FOR_STRIKE
                        && div.voidstrikeStage == 1 && wingsClearedCarriers > 0) {
                    return;
                }
            }
            div.voidstrikeStage = 0;
            div.voidstrikeStageStartTime = now;
        }

        // During OTC MARSHAL_FOR_STRIKE countdown, keep wings in Stage 0 -> Stage 1 at the marshal point
        // (instead of pulling them back into carrier bays!) so they are already formed up when the strike commits!
        // However, if armed strike craft are already committed downrange in Stage 2, never yank them back mid-run!
        if (div.currentIntent == DivisionIntentType.MARSHAL_FOR_STRIKE) {
            if (div.voidstrikeStage == 2) {
                if (wingsCommittedDownrange == 0 || !hasArmedStrikeCraftCommittedDownrange(div)) {
                    div.voidstrikeStage = 1;
                    div.voidstrikeStageStartTime = now;
                }
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
            // Stage 1: Squadrons form up at the marshalling point; wait until >= 70% of airborne wings are within
            // 420u of voidstrikeMarshalPoint, then execute Stage 2 and snapshot the Maximum Average Effective Massed Strike Package!
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
            boolean majorityAssembled = airborneWings > 0 && assembledWings >= Math.max(1, Math.round(airborneWings * 0.70f));
            float maxStage1Wait = (airborneWings >= 2) ? 2.8f : 2.2f;
            if ((elapsedInStage >= 1.2f && majorityAssembled) || elapsedInStage >= maxStage1Wait) {
                div.voidstrikeStage = 2;
                div.voidstrikeStageStartTime = now;
                MassedStrikePackageProfile pkg = computeMassedStrikePackageProfile(div, focusEnemyShip);
                div.lastVoidstrikePackageMass = pkg.maxEffectivePackageMass;
                div.lastVoidstrikeEffectiveSpeed = pkg.effectivePackageSpeed;
                div.lastVoidstrikeApproachTimeoutSec = pkg.packageApproachTimeoutSec;
                div.lastVoidstrikePassTimeoutSec = pkg.packageStrikePassTimeoutSec;
            }
        } else if (div.voidstrikeStage == 2) {
            MassedStrikePackageProfile pkg = computeMassedStrikePackageProfile(div, focusEnemyShip);
            if (pkg.maxEffectivePackageMass > 0f) {
                div.lastVoidstrikePackageMass = Math.max(div.lastVoidstrikePackageMass, pkg.maxEffectivePackageMass);
                div.lastVoidstrikeEffectiveSpeed = pkg.effectivePackageSpeed;
                div.lastVoidstrikeApproachTimeoutSec = Math.max(div.lastVoidstrikeApproachTimeoutSec, pkg.packageApproachTimeoutSec);
                div.lastVoidstrikePassTimeoutSec = Math.max(div.lastVoidstrikePassTimeoutSec, pkg.packageStrikePassTimeoutSec);
            }
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

        // Emergency-Only Pullback Doctrine:
        //  - Carrier / division leader / OTC may pull back an active Voidstrike ONLY in genuine emergencies
        //    (carrier critically overloaded/damaged under close enemy fire, carrier division being overrun in CQB,
        //    emergency HAUL_OUT_TO_VENT under heavy fire, or no live target anywhere on the battlefield).
        //  - While armed bombers are committed downrange with unexpended ordnance and a live target exists,
        //    routine non-emergency intents or premature timers must NEVER recall them before ordnance expenditure!
        boolean emergencyPullback = isVoidstrikeEmergencyPullbackRequired(carrier, div, myGroup, focusEnemyShip);
        div.voidstrikeEmergencyRecallActive = emergencyPullback;
        boolean armedCommittedDownrange = hasArmedStrikeCraftCommittedDownrange(div);

        boolean holdFightersInFormation = emergencyPullback
                || (!armedCommittedDownrange && (div.voidstrikeEgressActive
                || (div.currentIntent == DivisionIntentType.HOLD_IN_RESERVE && focusEnemyShip == null)
                || (div.currentIntent == DivisionIntentType.CLOSE_UP && focusEnemyShip == null)
                || (div.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT && div.localEnemyStrength < 10f && focusEnemyShip == null)));

        boolean isCapMission = div.currentIntent == DivisionIntentType.FLY_COMBAT_SPACE_PATROL;
        boolean coordinatedStagingActive = !holdFightersInFormation
                && !isCapMission
                && div.voidstrikeStage != 2
                && focusEnemyShip != null
                && focusEnemyShip.isAlive()
                && (div.currentIntent == DivisionIntentType.MARSHAL_FOR_STRIKE
                || (div.voidstrikeCoordination == VoidstrikeCoordinationMode.COORDINATED
                && (div.voidstrikeStage == 0 || div.voidstrikeStage == 1)));

        if (holdFightersInFormation) {
            for (int wIdx = 0; wIdx < wings.size(); wIdx++) {
                DivisionAICombatPlugin.ensureFighterWingLeaderAndFlockingAI(wings.get(wIdx));
            }
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

        ShipAPI escortAnchor = (isCapMission
                && div.targetFriendlyDivision != null
                && div.targetFriendlyDivision.flagship != null
                && div.targetFriendlyDivision.flagship.isAlive()
                && !div.targetFriendlyDivision.flagship.isHulk())
                ? div.targetFriendlyDivision.flagship
                : ((div.flagship != null && div.flagship.isAlive() && !div.flagship.isHulk())
                ? div.flagship
                : carrier);

        ShipAPI capInterceptTarget = isCapMission
                ? findCapInterceptTarget(escortAnchor, carrier, focusEnemyShip)
                : null;
        boolean holdCapPatrolOverFriendly = isCapMission && !holdFightersInFormation && capInterceptTarget == null;

        boolean inVoidstrike = !holdFightersInFormation
                && !isCapMission
                && focusEnemyShip != null
                && focusEnemyShip.isAlive()
                && (div.currentIntent == DivisionIntentType.LAUNCH_DECKLOAD_STRIKE
                || div.currentIntent == DivisionIntentType.LAUNCH_ALPHA_STRIKE
                || div.currentIntent == DivisionIntentType.FLY_CYCLIC_STRIKES
                || div.currentIntent == DivisionIntentType.FLY_FIGHTER_SWEEP
                || div.currentIntent == DivisionIntentType.FLY_ARMED_RECONNAISSANCE
                || div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE
                || div.currentIntent == DivisionIntentType.PRESS_HOME
                || (myGroup != null && myGroup.strikePlan != null && myGroup.strikePlan.participants.contains(div))
                || DivisionUiWidgets.distance(carrier.getLocation(), focusEnemyShip.getLocation()) > 650f);

        // Maximum-Range Strike Support: dynamically extend carrier's FighterWingRange stat during active Voidstrikes
        // so vanilla FighterAI.isTooFar(carrier, target) never forces strike craft to abort and pull back at long range!
        updateCarrierMaxRangeStrikeStat(carrier, focusEnemyShip, div, inVoidstrike && !coordinatedStagingActive, emergencyPullback);

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
        VoidstrikeAttackGeometry attackGeometry = div.voidstrikeAttackGeometry != null
                ? div.voidstrikeAttackGeometry
                : VoidstrikeAttackGeometry.DIRECT_ASSAULT;
        VoidstrikeSplitMode splitMode = div.voidstrikeSplitMode != null
                ? div.voidstrikeSplitMode
                : VoidstrikeSplitMode.UNIFIED;
        VoidstrikeGunneryMode gunneryMode = div.voidstrikeGunneryMode != null
                ? div.voidstrikeGunneryMode
                : VoidstrikeGunneryMode.PRECISION;
        VoidstrikeAoaCorrection aoaCorrection = div.voidstrikeAoaCorrection != null
                ? div.voidstrikeAoaCorrection
                : VoidstrikeAoaCorrection.STRONG;

        if (carrierIdxInDiv == 0) {
            div.voidstrikeTerminalAlignedCraftCount = 0;
            div.voidstrikeFireGatedCraftCount = 0;
        }

        // Resolve multi-division Voidstrike participation index & count for Level-1 Voidstrike sector partitioning
        int numParticipatingDivs = 1;
        int divIdxInStrike = 0;
        if (div.id != null && !activeVoidstrikes.isEmpty()) {
            for (VoidstrikeRecord rec : activeVoidstrikes) {
                if (rec != null && rec.participatingDivisionIds.contains(div.id)) {
                    List<String> sortedDivs = new ArrayList<>(rec.participatingDivisionIds);
                    Collections.sort(sortedDivs);
                    numParticipatingDivs = Math.max(1, sortedDivs.size());
                    divIdxInStrike = Math.max(0, sortedDivs.indexOf(div.id));
                    break;
                }
            }
        }

        // Multi-Angle Time-on-Target (ToT) Jaw Synchronization:
        // When executing a multi-angle / split-wing strike (L-Crossfire, Pincer Envelopment, or Split-Axes),
        // compute the slowest armed sub-wing's remaining path-time (ETA) to terminal weapon release so that
        // shorter/faster legs (e.g. the 0-deg Anvil leg or the nearer Pincer jaw) pace themselves outside PD range
        // and all attack angles converge on the target simultaneously to overwhelm and split enemy PD!
        boolean multiAngleStrikeActive = inVoidstrike
                && (!coordinatedStagingActive || div.voidstrikeStage == 2)
                && focusEnemyShip != null
                && focusEnemyShip.isAlive()
                && (attackGeometry != VoidstrikeAttackGeometry.DIRECT_ASSAULT
                || splitMode == VoidstrikeSplitMode.SPLIT_AXES);
        float maxArmedSubWingEtaSec = 0f;
        if (multiAngleStrikeActive) {
            int scanGlobalIdx = 0;
            int numAxesEst = (attackGeometry == VoidstrikeAttackGeometry.DIRECT_ASSAULT && totalDivWings >= 3) ? 3 : 2;
            for (ShipAPI mCarrier : divAlive) {
                if (mCarrier == null || !mCarrier.isAlive() || mCarrier.getAllWings() == null) continue;
                for (com.fs.starfarer.api.combat.FighterWingAPI mWing : mCarrier.getAllWings()) {
                    if (mWing != null && mWing.getWingMembers() != null && !mWing.getWingMembers().isEmpty()) {
                        ShipAPI mLeader = mWing.getLeader();
                        if (mLeader == null || !mLeader.isAlive()) {
                            mLeader = mWing.getWingMembers().get(0);
                        }
                        boolean mIsBomber = (mWing.getSpec() != null && mWing.getSpec().isBomber())
                                || (mWing.getRole() == com.fs.starfarer.api.loading.WingRole.BOMBER);
                        if (mLeader != null && mLeader.isAlive() && !mWing.isReturning(mLeader)
                                && hasStrikeOrdnanceRemaining(mLeader, mIsBomber)) {
                            float mLegDeg = computeSquadronAttackAngleOffsetDeg(
                                    attackGeometry, splitMode, scanGlobalIdx, Math.max(1, totalDivWings),
                                    mIsBomber, focusEnemyShip, fwdX, fwdY);
                            float dL = DivisionUiWidgets.distance(mLeader.getLocation(), focusEnemyShip.getLocation());
                            if (dL <= 3600f) {
                                int subRank = (scanGlobalIdx / numAxesEst) / Math.max(1, effSqdnWidth);
                                float gDist = 620f + subRank * 65f;
                                String lId = mLeader.getId() != null ? mLeader.getId() : ("L_" + scanGlobalIdx);
                                String fTgtId = focusEnemyShip.getId() != null ? focusEnemyShip.getId() : "TGT";
                                boolean gateDone = Math.abs(mLegDeg) <= 5f
                                        || fTgtId.equals(flankingGateClearedTargetByFighter.get(lId));
                                float remPath;
                                if (gateDone) {
                                    remPath = Math.max(0f, dL - 520f);
                                } else {
                                    double mRad = Math.toRadians(mLegDeg);
                                    float mLegFwdX = fwdX * (float) Math.cos(mRad) - fwdY * (float) Math.sin(mRad);
                                    float mLegFwdY = fwdX * (float) Math.sin(mRad) + fwdY * (float) Math.cos(mRad);
                                    Vector2f gPt = new Vector2f(
                                            focusEnemyShip.getLocation().x - mLegFwdX * gDist,
                                            focusEnemyShip.getLocation().y - mLegFwdY * gDist);
                                    remPath = DivisionUiWidgets.distance(mLeader.getLocation(), gPt) + Math.max(0f, gDist - 520f);
                                }
                                float spd = Math.max(110f, mLeader.getMaxSpeed());
                                float eta = remPath / spd;
                                if (eta > maxArmedSubWingEtaSec) {
                                    maxArmedSubWingEtaSec = eta;
                                }
                            }
                        }
                    }
                    scanGlobalIdx++;
                }
            }
            maxArmedSubWingEtaSec = Math.min(maxArmedSubWingEtaSec, 18.0f);
        }

        for (int wIdx = 0; wIdx < wings.size(); wIdx++) {
            com.fs.starfarer.api.combat.FighterWingAPI wing = wings.get(wIdx);
            if (wing == null) continue;
            List<ShipAPI> wingMembers = wing.getWingMembers();
            if (wingMembers == null || wingMembers.isEmpty()) continue;
            ShipAPI wingLeader = DivisionAICombatPlugin.ensureFighterWingLeaderAndFlockingAI(wing);
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

            // Resolve per-squadron target ship (supports SPLIT_TARGETS within the same Voidstrike entity
            // plus dynamic closer-target switching when an intervening enemy ship screens the assigned target or fires AA)
            ShipAPI rawWingTargetShip = isCapMission
                    ? capInterceptTarget
                    : ((inVoidstrike && focusEnemyShip != null && focusEnemyShip.isAlive())
                    ? resolveWingVoidstrikeTargetShip(div, focusEnemyShip, globalSqdnIdx, Math.max(1, totalDivWings))
                    : focusEnemyShip);
            ShipAPI wingTargetShip = isCapMission
                    ? capInterceptTarget
                    : resolveSurvivableSquadronTargetShip(
                            carrier, div, wingLeader, wingMembers, rawWingTargetShip, observance, isBomberWing);
            if (wingTargetShip == null || !wingTargetShip.isAlive() || wingTargetShip.isHulk()) {
                wingTargetShip = rawWingTargetShip;
            }
            float wingFwdX = fwdX;
            float wingFwdY = fwdY;
            if (wingTargetShip != null && wingTargetShip.isAlive()) {
                Vector2f refOrigin = (div.centroid != null && (div.centroid.x != 0f || div.centroid.y != 0f))
                        ? div.centroid
                        : carrier.getLocation();
                float wdx = wingTargetShip.getLocation().x - refOrigin.x;
                float wdy = wingTargetShip.getLocation().y - refOrigin.y;
                float wlen = (float) Math.sqrt(wdx * wdx + wdy * wdy);
                if (wlen > 1f) {
                    wingFwdX = wdx / wlen;
                    wingFwdY = wdy / wlen;
                }
            }

            // Multi-axis attack geometry & Split-Wing sub-elements (Direct Assault, L-Crossfire, Pincer Envelopment)
            float legAngleOffsetDeg = (inVoidstrike && wingTargetShip != null && wingTargetShip.isAlive())
                    ? computeSquadronAttackAngleOffsetDeg(
                            attackGeometry,
                            splitMode,
                            globalSqdnIdx,
                            Math.max(1, totalDivWings),
                            isBomberWing,
                            wingTargetShip,
                            wingFwdX,
                            wingFwdY)
                    : 0f;
            double legRad = Math.toRadians(legAngleOffsetDeg);
            float cosLeg = (float) Math.cos(legRad);
            float sinLeg = (float) Math.sin(legRad);
            float legFwdX = wingFwdX * cosLeg - wingFwdY * sinLeg;
            float legFwdY = wingFwdX * sinLeg + wingFwdY * cosLeg;
            float legRightX = legFwdY;
            float legRightY = -legFwdX;

            for (int cIdx = 0; cIdx < wingMembers.size(); cIdx++) {
                ShipAPI fighter = wingMembers.get(cIdx);
                if (fighter == null || !fighter.isAlive() || fighter.isHulk()) continue;
                // Respect player-piloted strikecraft (e.g. Arma Armatura neural transfer) and WingCom officer-piloted craft
                CombatEngineAPI activeEngine = Global.getCombatEngine();
                if ((activeEngine != null && (fighter == activeEngine.getPlayerShip() || fighter.getShipAI() == null))
                        || (fighter.getCaptain() != null && !fighter.getCaptain().isDefault())) {
                    continue;
                }
                ShipwideAIFlags fFlags = fighter.getAIFlags();
                if (fFlags == null) continue;

                boolean useLegBasis = inVoidstrike
                        && (!coordinatedStagingActive || div.voidstrikeStage == 2)
                        && focusEnemyShip != null
                        && focusEnemyShip.isAlive();
                Vector2f slotOffset = computeSquadronCraftSlotOffset(
                        sqdnForm,
                        cIdx,
                        wingMembers.size(),
                        rankIdx,
                        inVoidstrike ? effSqdnDepth : 1,
                        colIdx,
                        inVoidstrike ? activeColsInRank : 1,
                        44f,
                        useLegBasis ? legFwdX : fwdX,
                        useLegBasis ? legFwdY : fwdY,
                        useLegBasis ? legRightX : rightX,
                        useLegBasis ? legRightY : rightY);
                Vector2f intraWingOffset = computeSquadronCraftSlotOffset(
                        sqdnForm,
                        cIdx,
                        wingMembers.size(),
                        0,
                        1,
                        0,
                        1,
                        36f,
                        useLegBasis ? legFwdX : fwdX,
                        useLegBasis ? legFwdY : fwdY,
                        useLegBasis ? legRightX : rightX,
                        useLegBasis ? legRightY : rightY);

                if (holdFightersInFormation || holdCapPatrolOverFriendly) {
                    if (fighter.getId() != null) {
                        flankingGateClearedTargetByFighter.remove(fighter.getId());
                    }
                    fighter.setShipTarget(null);
                    fFlags.unsetFlag(AIFlags.MANEUVER_TARGET);
                    fFlags.unsetFlag(AIFlags.CARRIER_FIGHTER_TARGET);
                    fFlags.setFlag(AIFlags.DO_NOT_PURSUE, DivisionCommanderAI.FLAG_TTL);
                    fFlags.setFlag(AIFlags.ESCORT_OTHER_SHIP, DivisionCommanderAI.FLAG_TTL, escortAnchor);
                    float capArcOffsetX = 0f;
                    float capArcOffsetY = 0f;
                    if (holdCapPatrolOverFriendly && totalDivWings > 1) {
                        double capAng = ((globalSqdnIdx / (double) Math.max(1, totalDivWings - 1)) - 0.5) * (Math.PI * 0.75);
                        float capRad = Math.max(220f, escortAnchor.getCollisionRadius() + 160f);
                        float localCapFwd = (float) Math.cos(capAng) * capRad;
                        float localCapRight = (float) Math.sin(capAng) * capRad;
                        capArcOffsetX = fwdX * localCapFwd + rightX * localCapRight;
                        capArcOffsetY = fwdY * localCapFwd + rightY * localCapRight;
                    }
                    Vector2f patrolSlot = new Vector2f(
                            escortAnchor.getLocation().x + capArcOffsetX + slotOffset.x,
                            escortAnchor.getLocation().y + capArcOffsetY + slotOffset.y);
                    fFlags.setFlag(AIFlags.MOVEMENT_DEST, DivisionCommanderAI.FLAG_TTL, patrolSlot);
                    steerFighterTowardPoint60Hz(fighter, patrolSlot, !holdCapPatrolOverFriendly);
                } else if (coordinatedStagingActive && div.voidstrikeStage == 0
                        && (DivisionUiWidgets.distance(fighter.getLocation(), carrier.getLocation()) <= 950f
                        || DivisionUiWidgets.distance(fighter.getLocation(), div.voidstrikeMarshalPoint) <= 950f)) {
                    if (fighter.getId() != null) {
                        flankingGateClearedTargetByFighter.remove(fighter.getId());
                    }
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
                    steerFighterTowardPoint60Hz(fighter, orbitPt, false);
                } else if (coordinatedStagingActive && div.voidstrikeStage == 1
                        && (DivisionUiWidgets.distance(fighter.getLocation(), carrier.getLocation()) <= 950f
                        || DivisionUiWidgets.distance(fighter.getLocation(), div.voidstrikeMarshalPoint) <= 950f)) {
                    if (fighter.getId() != null) {
                        flankingGateClearedTargetByFighter.remove(fighter.getId());
                    }
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
                    if (dMarshal > 90f) {
                        steerFighterTowardPoint60Hz(fighter, marshalSlot, false);
                    } else if (fighter.getVelocity() != null) {
                        // Gentle velocity damping/steering at the marshal point so early-arriving wings hold formation
                        float toSlotX = marshalSlot.x - fighter.getLocation().x;
                        float toSlotY = marshalSlot.y - fighter.getLocation().y;
                        float desiredVx = toSlotX * 0.85f + carrier.getVelocity().x * 0.5f;
                        float desiredVy = toSlotY * 0.85f + carrier.getVelocity().y * 0.5f;
                        fighter.getVelocity().set(
                                fighter.getVelocity().x * 0.82f + desiredVx * 0.18f,
                                fighter.getVelocity().y * 0.82f + desiredVy * 0.18f);
                    }
                } else if (wingTargetShip != null && wingTargetShip.isAlive()) {
                    fFlags.unsetFlag(AIFlags.CAMP_LOCATION);
                    fFlags.unsetFlag(AIFlags.ESCORT_OTHER_SHIP);
                    fFlags.unsetFlag(AIFlags.DO_NOT_PURSUE);
                    boolean hasUnexpendedOrdnance = hasUnexpendedStrikeOrdnance(fighter, isBomberWing);
                    boolean isFiniteAmmoCraft = isBomberWing || hasFiniteAmmoStrikeWeapons(fighter);
                    float maxWepRange = computeMaxStrikeWeaponRange(fighter);
                    float dToAssignedNow = DivisionUiWidgets.distance(fighter.getLocation(), wingTargetShip.getLocation());
                    boolean inTerminalDropRange = dToAssignedNow <= 820f;
                    float minContinueHull = isFiniteAmmoCraft
                            ? (inTerminalDropRange ? 0.38f : 0.56f)
                            : 0.54f;
                    boolean criticalSelfPreservationRetreat = (!inTerminalDropRange && fighter.getHullLevel() < 0.50f)
                            || (!isFiniteAmmoCraft && fighter.getHullLevel() < 0.48f)
                            || (dToAssignedNow > 420f && fighter.getHullLevel() < 0.36f);
                    if (!holdFightersInFormation && !emergencyPullback && inVoidstrike && wing.isReturning(fighter) && !criticalSelfPreservationRetreat) {
                        boolean shouldKeepPressing = isFiniteAmmoCraft
                                ? (hasUnexpendedOrdnance && fighter.getHullLevel() >= minContinueHull)
                                : (fighter.getHullLevel() >= minContinueHull);
                        boolean refitTimerOk = fighter.getFighterTimeBeforeRefit() <= 0f || fighter.getFighterTimeBeforeRefit() > 2.5f;
                        if (shouldKeepPressing && !fighter.isLanding() && refitTimerOk) {
                            cancelPrematureFighterReturn(wing, fighter);
                        }
                    }
                    boolean hasOrdnance = hasStrikeOrdnanceRemaining(fighter, isBomberWing);
                    if (wing.isReturning(fighter) || (isBomberWing && !hasOrdnance) || criticalSelfPreservationRetreat) {
                        if (fighter.getId() != null) {
                            flankingGateClearedTargetByFighter.remove(fighter.getId());
                        }
                        if (!wing.isReturning(fighter) && ((isBomberWing && !hasUnexpendedOrdnance) || criticalSelfPreservationRetreat)) {
                            try {
                                wing.orderReturn(fighter);
                            } catch (Throwable ignored) {
                            }
                        }
                        fighter.setShipTarget(null);
                        fFlags.unsetFlag(AIFlags.MANEUVER_TARGET);
                        fFlags.unsetFlag(AIFlags.CARRIER_FIGHTER_TARGET);
                        fFlags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
                        fFlags.setFlag(AIFlags.BACK_OFF, DivisionCommanderAI.FLAG_TTL);
                        Vector2f safeReturnPt = computeAntiAaEvasionWaypoint(
                                fighter, null, carrier.getLocation(), wIdx, cIdx, now);
                        fFlags.setFlag(AIFlags.MOVEMENT_DEST, DivisionCommanderAI.FLAG_TTL, safeReturnPt);
                        steerFighterTowardPoint60Hz(fighter, safeReturnPt, true);
                        continue;
                    }
                    // Item C: Target Selection Observance (FREE, MEDIUM, HIGH) with Split-Wing target support
                    // Only assert DO_NOT_BACK_OFF on finite-ammo bombers while closing into their weapon release window
                    // (never inside point-blank ramming distance and never on gun/beam fighters that need standoff space!).
                    boolean holdTerminalCommit = isFiniteAmmoCraft
                            && hasUnexpendedOrdnance
                            && dToAssignedNow <= Math.max(760f, maxWepRange + 160f)
                            && dToAssignedNow > Math.max(280f, maxWepRange * 0.48f);
                    if (observance == VoidstrikeTargetObservance.HIGH) {
                        fFlags.setFlag(AIFlags.MANEUVER_TARGET, DivisionCommanderAI.FLAG_TTL * 1.5f, wingTargetShip);
                        fFlags.setFlag(AIFlags.CARRIER_FIGHTER_TARGET, DivisionCommanderAI.FLAG_TTL * 1.5f, wingTargetShip);
                        if (holdTerminalCommit) {
                            fFlags.setFlag(AIFlags.DO_NOT_BACK_OFF, DivisionCommanderAI.FLAG_TTL);
                        } else {
                            fFlags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
                        }
                        fighter.setShipTarget(wingTargetShip);
                    } else if (observance == VoidstrikeTargetObservance.MEDIUM) {
                        fFlags.setFlag(AIFlags.MANEUVER_TARGET, DivisionCommanderAI.FLAG_TTL, wingTargetShip);
                        if (!holdTerminalCommit) {
                            fFlags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
                        }
                        ShipAPI currTgt = fighter.getShipTarget();
                        boolean hasCloseInterceptor = currTgt != null
                                && currTgt.isAlive()
                                && !currTgt.isHulk()
                                && currTgt != wingTargetShip
                                && DivisionUiWidgets.distance(fighter.getLocation(), currTgt.getLocation()) <= 680f;
                        if (!hasCloseInterceptor) {
                            fighter.setShipTarget(wingTargetShip);
                        }
                    } else {
                        // FREE observance: allow engaging targets of opportunity along flight path
                        if (!holdTerminalCommit) {
                            fFlags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
                        }
                        ShipAPI currTgt = fighter.getShipTarget();
                        boolean hasOpportunityTarget = currTgt != null
                                && currTgt.isAlive()
                                && !currTgt.isHulk()
                                && DivisionUiWidgets.distance(fighter.getLocation(), currTgt.getLocation()) <= 1250f;
                        if (!hasOpportunityTarget) {
                            fFlags.setFlag(AIFlags.MANEUVER_TARGET, DivisionCommanderAI.FLAG_TTL, wingTargetShip);
                        }
                    }

                    ShipAPI attackTarget = wingTargetShip;
                    ShipAPI activeShipTgt = fighter.getShipTarget();
                    if (activeShipTgt != null
                            && activeShipTgt.isAlive()
                            && !activeShipTgt.isHulk()
                            && !activeShipTgt.isFighter()
                            && activeShipTgt.getOwner() != carrier.getOwner()) {
                        float dActive = DivisionUiWidgets.distance(fighter.getLocation(), activeShipTgt.getLocation());
                        if (dActive <= 950f && (splitMode != VoidstrikeSplitMode.SPLIT_TARGETS || dActive + 320f < dToAssignedNow)) {
                            attackTarget = activeShipTgt;
                        }
                    }
                    float dEnemy = DivisionUiWidgets.distance(fighter.getLocation(), attackTarget.getLocation());

                    boolean unguidedCraft = isBomberWing || hasUnguidedStrikeMunitions(fighter);
                    boolean onFlankingDogleg = false;
                    float terminalStartRange = 880f;

                    // Dress squadron formation, multi-axis dogleg (L-Crossfire / Pincer / Split-Wing), & Voidstrike depth on approach
                    boolean isStandoffGunship = isStandoffGunshipOrCorvette(wing, fighter);
                    float standoffHoldDist = isStandoffGunship
                            ? Math.max(520f, Math.min(850f, maxWepRange * 0.85f))
                            : 420f;
                    if (wingLeader != null && wingLeader.isAlive() && dEnemy > standoffHoldDist) {
                        String fighterIdKey = fighter.getId() != null ? fighter.getId() : ("F_" + globalSqdnIdx + "_" + cIdx);
                        String leaderIdKey = wingLeader.getId() != null ? wingLeader.getId() : ("L_" + globalSqdnIdx);
                        String targetIdKey = attackTarget.getId() != null ? attackTarget.getId() : "TGT";

                        if (Math.abs(legAngleOffsetDeg) > 5f && hasOrdnance && dEnemy <= 2600f) {
                            // Multi-axis Flanking Gate (L-Shaped Crossfire, Pincer Envelopment, or Split-Wing):
                            // Index rank and column within this sub-wing/jaw (rather than across all 18+ squadrons of the division)
                            // so each pincer jaw / crossfire leg forms a tight, compact strike package at standoff release range!
                            int numAxes = (attackGeometry == VoidstrikeAttackGeometry.DIRECT_ASSAULT && totalDivWings >= 3) ? 3 : 2;
                            int subWingSqdnIdx = globalSqdnIdx / numAxes;
                            int subWingTotalSqdns = Math.max(1, (totalDivWings + numAxes - 1) / numAxes);
                            int subWingCols = Math.max(1, Math.min(effSqdnWidth, subWingTotalSqdns));
                            int subColIdx = subWingSqdnIdx % subWingCols;
                            int subRankIdx = (subWingSqdnIdx / subWingCols) % Math.max(1, effSqdnDepth);

                            float gateDist = 620f + subRankIdx * 65f;
                            float centeredCol = subColIdx - (subWingCols - 1) * 0.5f;
                            Vector2f flankGate = new Vector2f(
                                    attackTarget.getLocation().x - legFwdX * gateDist + legRightX * (centeredCol * 60f),
                                    attackTarget.getLocation().y - legFwdY * gateDist + legRightY * (centeredCol * 60f));
                            Vector2f activeGate = flankGate;
                            if (Math.abs(legAngleOffsetDeg) >= 70f) {
                                float dAlongPrimary = (attackTarget.getLocation().x - fighter.getLocation().x) * wingFwdX
                                        + (attackTarget.getLocation().y - fighter.getLocation().y) * wingFwdY;
                                if (dAlongPrimary > 500f) {
                                    Vector2f outerElbow = new Vector2f(
                                            attackTarget.getLocation().x - wingFwdX * 480f - legFwdX * gateDist + legRightX * (centeredCol * 60f),
                                            attackTarget.getLocation().y - wingFwdY * 480f - legFwdY * gateDist + legRightY * (centeredCol * 60f));
                                    if (DivisionUiWidgets.distance(fighter.getLocation(), outerElbow) > 220f) {
                                        activeGate = outerElbow;
                                    }
                                }
                            }
                            Vector2f craftGate = new Vector2f(
                                    activeGate.x + intraWingOffset.x,
                                    activeGate.y + intraWingOffset.y);
                            Vector2f gateWaypoint = computeAntiAaEvasionWaypoint(fighter, attackTarget, craftGate, wIdx, cIdx, now);

                            boolean fighterUnderAaFire = fighter.getHullLevel() < 0.92f
                                    || (fighter.getFluxTracker() != null && fighter.getFluxTracker().getFluxLevel() >= 0.08f);
                            float toTgtX = attackTarget.getLocation().x - fighter.getLocation().x;
                            float toTgtY = attackTarget.getLocation().y - fighter.getLocation().y;
                            float curToTgtX = toTgtX / Math.max(1f, dEnemy);
                            float curToTgtY = toTgtY / Math.max(1f, dEnemy);
                            float cosLegAlign = curToTgtX * legFwdX + curToTgtY * legFwdY;
                            float projAlongLeg = toTgtX * legFwdX + toTgtY * legFwdY;

                            // Latched One-Way Flanking Gate: once a fighter or its squadron leader reaches its turning gate
                            // or crosses the gate plane inward along legFwd, latch clearedGate = true for this target so it
                            // NEVER oscillates or turns 180 deg backward when moving > 165u past gateWaypoint toward the enemy!
                            boolean alreadyClearedGate = targetIdKey.equals(flankingGateClearedTargetByFighter.get(fighterIdKey))
                                    || (targetIdKey.equals(flankingGateClearedTargetByFighter.get(leaderIdKey)) && dEnemy <= gateDist + 240f);
                            boolean alignedWithTerminalLeg = alreadyClearedGate
                                    || (cosLegAlign >= 0.90f)
                                    || (dEnemy <= 680f && cosLegAlign >= 0.72f)
                                    || (projAlongLeg <= gateDist + 45f && cosLegAlign >= 0.65f)
                                    || (fighterUnderAaFire && dEnemy <= 850f)
                                    || (DivisionUiWidgets.distance(fighter.getLocation(), flankGate) <= 185f)
                                    || (DivisionUiWidgets.distance(fighter.getLocation(), craftGate) <= 185f)
                                    || (DivisionUiWidgets.distance(fighter.getLocation(), gateWaypoint) <= 175f);
                            if (alignedWithTerminalLeg) {
                                flankingGateClearedTargetByFighter.put(fighterIdKey, targetIdKey);
                                if (fighter == wingLeader) {
                                    flankingGateClearedTargetByFighter.put(leaderIdKey, targetIdKey);
                                }
                            }
                            if (!alignedWithTerminalLeg) {
                                onFlankingDogleg = true;
                                fFlags.setFlag(AIFlags.MOVEMENT_DEST, DivisionCommanderAI.FLAG_TTL, gateWaypoint);
                                // Time-on-Target (ToT) Jaw Pacing: if this jaw is significantly ahead of the opposite jaw's ETA
                                // while still outside enemy PD range, pace approach speed so both jaws turn in simultaneously!
                                boolean paceForToT = false;
                                if (multiAngleStrikeActive && maxArmedSubWingEtaSec > 1.2f && dEnemy > 820f && !fighterUnderAaFire) {
                                    float myRemPath = DivisionUiWidgets.distance(fighter.getLocation(), activeGate) + Math.max(0f, gateDist - 520f);
                                    float myEta = myRemPath / Math.max(110f, fighter.getMaxSpeed());
                                    if (myEta + 0.55f < maxArmedSubWingEtaSec) {
                                        paceForToT = true;
                                    }
                                }
                                steerFighterTowardPoint60Hz(fighter, gateWaypoint, !paceForToT);
                                if (unguidedCraft) {
                                    fighter.blockCommandForOneFrame(ShipCommand.FIRE);
                                }
                            } else {
                                Vector2f craftGoal = new Vector2f(
                                        attackTarget.getLocation().x + intraWingOffset.x,
                                        attackTarget.getLocation().y + intraWingOffset.y);
                                fFlags.setFlag(AIFlags.MOVEMENT_DEST, DivisionCommanderAI.FLAG_TTL, craftGoal);
                                if (attackTarget != focusEnemyShip && dEnemy > terminalStartRange) {
                                    steerFighterTowardPoint60Hz(fighter, craftGoal, true);
                                }
                            }
                        } else {
                            // Direct approach (or Anvil 0-deg leg of L-Crossfire): project a clean forward approach target.
                            // When part of a multi-angle strike (e.g. 0-deg Anvil leg paired with an 85-deg Hammer dogleg),
                            // gently pace velocity outside enemy PD range (> 820u) so the Anvil leg arrives simultaneously with the Hammer leg!
                            Vector2f craftGoal = new Vector2f(
                                    attackTarget.getLocation().x + intraWingOffset.x,
                                    attackTarget.getLocation().y + intraWingOffset.y);
                            if (dEnemy > terminalStartRange) {
                                craftGoal = computeAntiAaEvasionWaypoint(fighter, attackTarget, craftGoal, wIdx, cIdx, now);
                            }
                            fFlags.setFlag(AIFlags.MOVEMENT_DEST, DivisionCommanderAI.FLAG_TTL, craftGoal);
                            if (attackTarget != focusEnemyShip && dEnemy > terminalStartRange) {
                                steerFighterTowardPoint60Hz(fighter, craftGoal, true);
                            }
                            if (multiAngleStrikeActive && maxArmedSubWingEtaSec > 1.2f && dEnemy > 820f && fighter.getVelocity() != null) {
                                boolean fighterUnderAaFire = fighter.getHullLevel() < 0.92f
                                        || (fighter.getFluxTracker() != null && fighter.getFluxTracker().getFluxLevel() >= 0.08f);
                                if (!fighterUnderAaFire) {
                                    float myRemPath = Math.max(0f, dEnemy - 520f);
                                    float mySpd = Math.max(110f, fighter.getMaxSpeed());
                                    float myEta = myRemPath / mySpd;
                                    if (myEta + 0.45f < maxArmedSubWingEtaSec) {
                                        float totScale = Math.max(0.58f, Math.min(0.95f, (myEta + 0.25f) / maxArmedSubWingEtaSec));
                                        Vector2f vel = fighter.getVelocity();
                                        float curSpd = (float) Math.sqrt(vel.x * vel.x + vel.y * vel.y);
                                        float capSpd = mySpd * totScale;
                                        if (curSpd > capSpd && curSpd > 1f) {
                                            vel.set(vel.x * (capSpd / curSpd), vel.y * (capSpd / curSpd));
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Advanced Strike Craft Gunnery on Terminal Attack Run (once dogleg gate is cleared):
                    //  - Hierarchical Firing Sectors (Voidstrike -> Squadron -> Craft) for PRECISION vs AREA_SATURATION
                    //  - Angle-of-Attack Correction (LOW vs STRONG) with ballistic lead, slip damping, and standoff release
                    // Standoff Gunships / Corvettes (e.g. UAF Neoscar/Puterakamu/Puterisaya/Pengawas, Mayasuran/Kyeltziv Corvettes)
                    // orbit/strafe at standoff range using turret/multi-pod weapons; never lock their 60 Hz velocity vector into the hull center!
                    if (!onFlankingDogleg && !isStandoffGunship && hasOrdnance && (unguidedCraft || isFiniteAmmoCraft) && dEnemy <= terminalStartRange) {
                        FiringSectorAssignment sector = computeHierarchicalFiringSector(
                                gunneryMode,
                                aoaCorrection,
                                attackTarget,
                                fighter,
                                divIdxInStrike,
                                numParticipatingDivs,
                                globalSqdnIdx,
                                Math.max(1, totalDivWings),
                                cIdx,
                                Math.max(1, wingMembers.size()),
                                legFwdX,
                                legFwdY,
                                legRightX,
                                legRightY);
                        div.lastVoidstrikeSpanWidth = sector.voidstrikeSpanWidth;
                        boolean fireGated = applyTerminalStrikeCraftGunnery(
                                fighter,
                                fFlags,
                                attackTarget,
                                sector,
                                aoaCorrection,
                                unguidedCraft,
                                dEnemy);
                        div.voidstrikeTerminalAlignedCraftCount++;
                        if (fireGated) {
                            div.voidstrikeFireGatedCraftCount++;
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Advanced Strike Craft Gunnery (Attack Geometry, Firing Sectors & AoA)
    // =========================================================================

    /**
     * Three-tier hierarchical firing sector assignment for an individual ordnance-bearing strike craft:
     *  1. Voidstrike Level: Total saturation footprint width across the target ship's silhouette & evasion path,
     *     partitioned across participating divisions (`divisionSectorCenter`, `divisionSectorWidth`).
     *  2. Squadron Level: Sub-sector assigned to the craft's squadron (`squadronSectorCenter`, `squadronSectorWidth`).
     *  3. Craft Level: Individual firing lane offset (`craftLateralOffset`, `craftDepthOffset`) and ballistic lead point.
     */
    public static class FiringSectorAssignment {
        public float voidstrikeSpanWidth;
        public float divisionSectorCenter;
        public float divisionSectorWidth;
        public float squadronSectorCenter;
        public float squadronSectorWidth;
        public float craftLateralOffset;
        public float craftDepthOffset;
        public final Vector2f worldSectorPoint = new Vector2f();
        public final Vector2f worldAimPoint = new Vector2f();
    }

    /**
     * Returns true if {@code fighter} carries unguided or low-turn-rate strike munitions (torpedoes,
     * rockets, bombs, or non-beam ballistic/energy strike weapons) that require nose-on AoA alignment.
     */
    private static ShipAPI findCapInterceptTarget(
            ShipAPI escortAnchor,
            ShipAPI carrier,
            ShipAPI focusEnemyShip) {
        if (escortAnchor == null || carrier == null) return null;
        int myOwner = carrier.getOwner();
        float capRadiusAroundProtected = 1250f;
        float capRadiusAroundCarrier = 1100f;

        // 1. Prioritize enemy bombers/fighters inside the CAP umbrella around the protected division or carrier
        ShipAPI bestFighterThreat = null;
        float bestFighterScore = -1f;
        for (ShipAPI f : cachedAliveFighters) {
            if (f == null || !f.isAlive() || f.isHulk() || f.getOwner() == myOwner) continue;
            float dProt = DivisionUiWidgets.distance(f.getLocation(), escortAnchor.getLocation());
            float dCar = DivisionUiWidgets.distance(f.getLocation(), carrier.getLocation());
            if (dProt > capRadiusAroundProtected && dCar > capRadiusAroundCarrier) continue;
            boolean isBomber = f.getWing() != null
                    && ((f.getWing().getSpec() != null && f.getWing().getSpec().isBomber())
                    || f.getWing().getRole() == com.fs.starfarer.api.loading.WingRole.BOMBER);
            float score = (isBomber ? 2000f : 1000f) - Math.min(dProt, dCar);
            if (score > bestFighterScore) {
                bestFighterScore = score;
                bestFighterThreat = f;
            }
        }
        if (bestFighterThreat != null) {
            return bestFighterThreat;
        }

        // 2. Otherwise intercept focusEnemyShip only when it enters the CAP defense perimeter
        if (focusEnemyShip != null && focusEnemyShip.isAlive() && !focusEnemyShip.isHulk()) {
            float dProt = DivisionUiWidgets.distance(focusEnemyShip.getLocation(), escortAnchor.getLocation());
            float dCar = DivisionUiWidgets.distance(focusEnemyShip.getLocation(), carrier.getLocation());
            if (dProt <= capRadiusAroundProtected || dCar <= capRadiusAroundCarrier) {
                return focusEnemyShip;
            }
        }
        return null;
    }

    private static boolean isStandoffGunshipOrCorvette(com.fs.starfarer.api.combat.FighterWingAPI wing, ShipAPI fighter) {
        if (wing != null && wing.getSpec() != null) {
            com.fs.starfarer.api.loading.FighterWingSpecAPI spec = wing.getSpec();
            if (spec.getTags() != null && spec.getTags().contains("attack_at_an_angle")) {
                return true;
            }
            String roleDesc = spec.getRoleDesc();
            if (roleDesc != null) {
                String lower = roleDesc.toLowerCase(Locale.ROOT);
                if (lower.contains("gunship") || lower.contains("corvette")
                        || lower.contains("artillery") || lower.contains("platform")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean hasUnguidedStrikeMunitions(ShipAPI fighter) {
        if (fighter == null) return false;
        List<WeaponAPI> weapons = fighter.getAllWeapons();
        if (weapons != null && !weapons.isEmpty()) {
            for (WeaponAPI w : weapons) {
                if (w == null || w.isDecorative() || w.isPermanentlyDisabled()) continue;
                WeaponType wt = w.getType();
                if (wt == WeaponType.LAUNCH_BAY || wt == WeaponType.SYSTEM || wt == WeaponType.STATION_MODULE) {
                    continue;
                }
                if (wt == WeaponType.MISSILE) {
                    if (w.getSpec() != null && w.getSpec().getProjectileSpec() instanceof MissileSpecAPI) {
                        MissileSpecAPI ms = (MissileSpecAPI) w.getSpec().getProjectileSpec();
                        if (ms.getHullSpec() != null && ms.getHullSpec().getEngineSpec() != null) {
                            float maxTurn = ms.getHullSpec().getEngineSpec().getMaxTurnRate();
                            if (maxTurn <= 55f) {
                                return true;
                            }
                        } else {
                            return true;
                        }
                    } else {
                        return true;
                    }
                } else if ((wt == WeaponType.BALLISTIC || wt == WeaponType.ENERGY) && !w.isBeam()) {
                    return true;
                }
            }
        }
        if (fighter.getWing() != null) {
            if (fighter.getWing().getRole() == com.fs.starfarer.api.loading.WingRole.BOMBER) return true;
            if (fighter.getWing().getSpec() != null && fighter.getWing().getSpec().isBomber()) return true;
        }
        return false;
    }

    /**
     * Returns true if {@code fighter} carries any finite-ammo strike weapon (torpedo, bomb, rocket, or ammo-based weapon).
     */
    public static boolean hasFiniteAmmoStrikeWeapons(ShipAPI fighter) {
        if (fighter == null || fighter.getAllWeapons() == null) return false;
        for (WeaponAPI w : fighter.getAllWeapons()) {
            if (w == null || w.isDecorative() || w.isPermanentlyDisabled()) continue;
            if (w.usesAmmo() || (w.getMaxAmmo() > 0 && w.getMaxAmmo() < Integer.MAX_VALUE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes the fraction [0..1] of primary strike ammo remaining on {@code fighter}, ignoring {@code wing.isReturning}.
     * Non-PD finite-ammo weapons take priority so a secondary PD flare/chaff launcher never masks an empty torpedo bay.
     */
    public static float computeStrikeOrdnanceFraction(ShipAPI fighter) {
        if (fighter == null || !fighter.isAlive() || fighter.isHulk()) return 0f;
        List<WeaponAPI> weapons = fighter.getAllWeapons();
        if (weapons == null || weapons.isEmpty()) return 1f;
        int missileStrikeCurr = 0;
        int missileStrikeMax = 0;
        int primaryAmmoCurr = 0;
        int primaryAmmoMax = 0;
        int anyAmmoCurr = 0;
        int anyAmmoMax = 0;
        for (WeaponAPI w : weapons) {
            if (w == null || w.isDecorative() || w.isPermanentlyDisabled()) continue;
            if (w.usesAmmo() || (w.getMaxAmmo() > 0 && w.getMaxAmmo() < Integer.MAX_VALUE)) {
                int maxA = Math.max(1, (w.getMaxAmmo() > 0 && w.getMaxAmmo() < Integer.MAX_VALUE) ? w.getMaxAmmo() : Math.max(1, w.getAmmo()));
                int curA = Math.max(0, Math.min(maxA, w.getAmmo()));
                anyAmmoCurr += curA;
                anyAmmoMax += maxA;
                boolean isPdOnly = w.getSpec() != null && w.getSpec().getAIHints() != null
                        && (w.getSpec().getAIHints().contains(AIHints.PD_ONLY)
                        || (w.getSpec().getAIHints().contains(AIHints.PD) && w.getType() != WeaponType.MISSILE));
                boolean isMissileOrStrike = w.getType() == WeaponType.MISSILE
                        || (w.getSpec() != null && w.getSpec().getAIHints() != null && w.getSpec().getAIHints().contains(AIHints.STRIKE));
                if (!isPdOnly && isMissileOrStrike) {
                    missileStrikeCurr += curA;
                    missileStrikeMax += maxA;
                }
                if (!isPdOnly) {
                    primaryAmmoCurr += curA;
                    primaryAmmoMax += maxA;
                }
            }
        }
        if (missileStrikeMax > 0) {
            return (float) missileStrikeCurr / (float) missileStrikeMax;
        }
        if (primaryAmmoMax > 0) {
            return (float) primaryAmmoCurr / (float) primaryAmmoMax;
        }
        if (anyAmmoMax > 0) {
            return (float) anyAmmoCurr / (float) anyAmmoMax;
        }
        return 1f;
    }

    /**
     * Returns true if {@code fighter} still has unexpended strike ammo on its racks (checked directly on weapons,
     * independent of whether vanilla {@code wing.isReturning(fighter)} was triggered prematurely).
     */
    public static boolean hasUnexpendedStrikeOrdnance(ShipAPI fighter, boolean isBomberWing) {
        if (fighter == null || !fighter.isAlive() || fighter.isHulk()) return false;
        return computeStrikeOrdnanceFraction(fighter) > 0f;
    }

    /**
     * Returns true while {@code fighter} is not returning to its carrier and still has strike ammo remaining
     * in at least one ammo-using strike weapon (or carries unlimited-ammo strike weapons).
     */
    public static boolean hasStrikeOrdnanceRemaining(ShipAPI fighter, boolean isBomberWing) {
        if (fighter == null || !fighter.isAlive() || fighter.isHulk()) return false;
        if (fighter.getWing() != null && fighter.getWing().isReturning(fighter)) return false;
        return hasUnexpendedStrikeOrdnance(fighter, isBomberWing);
    }

    /**
     * Cancels a premature vanilla return order on an armed strike craft (e.g. triggered by vanilla's
     * {@code FighterWing.fighterNeedsRefit} when a bomber takes 1% hull scratch while its carrier is out of gun range,
     * or by a prior pullback flag), clearing both {@code wing.stopReturning(fighter)} and {@code FighterAI.returnData}.
     */
    public static void cancelPrematureFighterReturn(com.fs.starfarer.api.combat.FighterWingAPI wing, ShipAPI fighter) {
        if (wing == null || fighter == null) return;
        try {
            wing.stopReturning(fighter);
        } catch (Throwable ignored) {
        }
        ShipAIPlugin ai = fighter.getShipAI();
        if (ai == null) return;
        if (!fighterAiReturnDataSetterResolved) {
            fighterAiReturnDataSetterResolved = true;
            try {
                Class<?> aiClass = ai.getClass();
                Class<?> retType = com.fs.starfarer.api.combat.FighterWingAPI.ReturningFighter.class;
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(aiClass, MethodHandles.lookup());
                fighterAiReturnDataSetter = lookup.findSetter(aiClass, "returnData", retType);
            } catch (Throwable ignored) {
                fighterAiReturnDataSetter = null;
            }
        }
        if (fighterAiReturnDataSetter != null) {
            try {
                fighterAiReturnDataSetter.invoke(ai, (Object) null);
                ai.cancelCurrentManeuver();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Dynamically boosts {@code carrier}'s {@code FighterWingRange} stat during active Voidstrikes so strike craft
     * can execute Maximum-Range Strikes across the entire map without vanilla {@code FighterAI.isTooFar} forcing pullback.
     */
    public static void updateCarrierMaxRangeStrikeStat(
            ShipAPI carrier,
            ShipAPI focusEnemyShip,
            DivisionState div,
            boolean activeVoidstrike,
            boolean emergencyPullback) {
        if (carrier == null || carrier.getMutableStats() == null || carrier.getMutableStats().getFighterWingRange() == null) {
            return;
        }
        if (!activeVoidstrike || emergencyPullback || focusEnemyShip == null || !focusEnemyShip.isAlive()) {
            carrier.getMutableStats().getFighterWingRange().unmodify(VOIDSTRIKE_MAX_RANGE_STAT_ID);
            return;
        }
        float maxDist = DivisionUiWidgets.distance(carrier.getLocation(), focusEnemyShip.getLocation());
        if (div != null && div.targetEnemyDivision != null && div.targetEnemyDivision.members != null) {
            for (ShipAPI em : div.targetEnemyDivision.members) {
                if (em != null && em.isAlive() && !em.isHulk() && em.getLocation() != null) {
                    float d = DivisionUiWidgets.distance(carrier.getLocation(), em.getLocation());
                    if (d > maxDist) maxDist = d;
                }
            }
        }
        float requiredBonus = Math.max(2500f, maxDist + 3200f);
        carrier.getMutableStats().getFighterWingRange().modifyFlat(VOIDSTRIKE_MAX_RANGE_STAT_ID, requiredBonus);
    }

    /**
     * Returns true ONLY when the carrier, division leader, or OTC faces a genuine emergency requiring immediate
     * Voidstrike pullback before bombers expend their ordnance:
     *  1. Carrier is overloaded, or at critical flux (>= 82%) / critical hull (< 32%) under close enemy threat.
     *  2. Carrier division is being overrun in close quarters (localEnemyStrength >= 18, localForceRatio < 0.50,
     *     and avgFlux >= 58% or avgHull < 52%).
     *  3. Division is executing an emergency HAUL_OUT_TO_VENT extraction under heavy incoming fire (> 35 firepower,
     *     avgFlux >= 72%), or fleet-wide FIGHTING_RETIREMENT under local enemy fire.
     *  4. All enemy targets are destroyed and no fallback enemy ship exists.
     */
    public static boolean isVoidstrikeEmergencyPullbackRequired(
            ShipAPI carrier,
            DivisionState div,
            BattleGroupState myGroup,
            ShipAPI focusEnemyShip) {
        if (carrier != null && carrier.isAlive() && !carrier.isHulk()) {
            boolean carrierOverloaded = carrier.getFluxTracker() != null && carrier.getFluxTracker().isOverloaded();
            boolean enemyCloseToCarrier = (div != null && div.localEnemyStrength >= 6f)
                    || (focusEnemyShip != null && DivisionUiWidgets.distance(carrier.getLocation(), focusEnemyShip.getLocation()) <= 1150f);
            if (carrierOverloaded && enemyCloseToCarrier) {
                return true;
            }
            if (enemyCloseToCarrier && (carrier.getFluxLevel() >= 0.82f || carrier.getHullLevel() < 0.32f)) {
                return true;
            }
        }
        if (div != null) {
            boolean divisionOverrunInCqb = div.localEnemyStrength >= 18f
                    && div.localForceRatio < 0.50f
                    && (div.avgFluxLevel >= 0.58f || div.avgHullLevel < 0.52f);
            boolean emergencyVentUnderFire = div.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                    && div.incomingInRangeFirepower > 35f
                    && div.avgFluxLevel >= 0.72f;
            boolean emergencyFightingRetirement = myGroup != null
                    && myGroup.currentPhase == BattlePhase.FIGHTING_RETIREMENT
                    && div.localEnemyStrength >= 12f
                    && div.avgFluxLevel >= 0.60f;
            if (divisionOverrunInCqb || emergencyVentUnderFire || emergencyFightingRetirement) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if {@code div} currently has any airborne strike craft carrying unexpended strike ordnance.
     */
    public static boolean hasArmedStrikeCraftAirborne(DivisionState div) {
        if (div == null) return false;
        for (ShipAPI m : div.getAliveMembers()) {
            if (m == null || m.getAllWings() == null) continue;
            for (com.fs.starfarer.api.combat.FighterWingAPI w : m.getAllWings()) {
                if (w == null || w.getWingMembers() == null) continue;
                boolean isBomber = (w.getSpec() != null && w.getSpec().isBomber())
                        || w.getRole() == com.fs.starfarer.api.loading.WingRole.BOMBER;
                for (ShipAPI f : w.getWingMembers()) {
                    if (f == null || !f.isAlive() || f.isHulk() || f.isLanding()) continue;
                    if (hasUnexpendedStrikeOrdnance(f, isBomber)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns true if {@code div} has armed strike craft (with unexpended ordnance) already committed > 460u downrange
     * from their parent carrier.
     */
    public static boolean hasArmedStrikeCraftCommittedDownrange(DivisionState div) {
        if (div == null) return false;
        for (ShipAPI m : div.getAliveMembers()) {
            if (m == null || m.getAllWings() == null) continue;
            for (com.fs.starfarer.api.combat.FighterWingAPI w : m.getAllWings()) {
                if (w == null || w.getWingMembers() == null) continue;
                boolean isBomber = (w.getSpec() != null && w.getSpec().isBomber())
                        || w.getRole() == com.fs.starfarer.api.loading.WingRole.BOMBER;
                for (ShipAPI f : w.getWingMembers()) {
                    if (f == null || !f.isAlive() || f.isHulk() || f.isLanding()) continue;
                    if ((isBomber || hasFiniteAmmoStrikeWeapons(f))
                            && hasUnexpendedStrikeOrdnance(f, isBomber)
                            && DivisionUiWidgets.distance(f.getLocation(), m.getLocation()) >= 460f) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Estimates the primary strike weapon release range of {@code fighter} (clamped to [220, 1400] u).
     */
    public static float computePrimaryStrikeWeaponRange(ShipAPI fighter) {
        if (fighter == null || fighter.getAllWeapons() == null || fighter.getAllWeapons().isEmpty()) {
            return 500f;
        }
        float missileRangeSum = 0f;
        int missileCount = 0;
        float gunRangeSum = 0f;
        int gunCount = 0;
        for (WeaponAPI w : fighter.getAllWeapons()) {
            if (w == null || w.isDecorative() || w.isPermanentlyDisabled()) continue;
            float rng = w.getRange();
            if (rng <= 10f) continue;
            if (w.getType() == WeaponType.MISSILE || w.usesAmmo()) {
                missileRangeSum += rng;
                missileCount++;
            } else if (w.getType() == WeaponType.BALLISTIC || w.getType() == WeaponType.ENERGY) {
                gunRangeSum += rng;
                gunCount++;
            }
        }
        float avg = (missileCount > 0)
                ? (missileRangeSum / missileCount)
                : ((gunCount > 0) ? (gunRangeSum / gunCount) : 500f);
        return Math.max(220f, Math.min(1400f, avg));
    }

    /**
     * Computes the Maximum Average Effective Massed Strike Package profile for {@code div} (or a multi-division
     * strike cluster) upon execution against {@code focusEnemyShip}, and derives the package-driven approach and
     * strike-pass timeouts from the package's mass-weighted effective speed, bomber speed, weapon release range,
     * transit distance, and doctrinal attack geometry.
     */
    public static MassedStrikePackageProfile computeMassedStrikePackageProfile(
            DivisionState div,
            ShipAPI focusEnemyShip) {
        MassedStrikePackageProfile profile = new MassedStrikePackageProfile();
        if (div == null) return profile;
        List<ShipAPI> carriers = div.getAliveMembers();
        if (carriers == null || carriers.isEmpty()) return profile;

        float totalMass = 0f;
        float weightedSpeedSum = 0f;
        float weightedRangeSum = 0f;
        float bomberMassSum = 0f;
        float bomberWeightedSpeedSum = 0f;
        float slowestBomberSpd = Float.MAX_VALUE;
        int armedBomberCount = 0;
        int sqdnCount = 0;
        int craftCount = 0;

        for (ShipAPI carrier : carriers) {
            if (carrier == null || carrier.getAllWings() == null) continue;
            for (com.fs.starfarer.api.combat.FighterWingAPI w : carrier.getAllWings()) {
                if (w == null || w.getWingMembers() == null || w.getWingMembers().isEmpty()) continue;
                if (!isStrikeSortieOrdered(div, w)) continue;
                boolean isBomber = (w.getSpec() != null && w.getSpec().isBomber())
                        || w.getRole() == com.fs.starfarer.api.loading.WingRole.BOMBER;
                sqdnCount++;
                for (ShipAPI f : w.getWingMembers()) {
                    if (f == null || !f.isAlive() || f.isHulk()) continue;
                    craftCount++;
                    boolean isAmmoCraft = isBomber || hasFiniteAmmoStrikeWeapons(f);
                    float ordFrac = computeStrikeOrdnanceFraction(f);
                    float hullFactor = 0.5f + 0.5f * Math.max(0.2f, f.getHullLevel());
                    float craftMass;
                    if (isAmmoCraft) {
                        if (ordFrac <= 0f) continue;
                        craftMass = 2.5f * ordFrac * hullFactor;
                        armedBomberCount++;
                        float bSpd = Math.max(85f, f.getMaxSpeed());
                        bomberMassSum += craftMass;
                        bomberWeightedSpeedSum += craftMass * bSpd;
                        if (bSpd < slowestBomberSpd) slowestBomberSpd = bSpd;
                    } else {
                        craftMass = 1.0f * hullFactor;
                    }
                    float fSpd = Math.max(85f, f.getMaxSpeed());
                    float fRng = computePrimaryStrikeWeaponRange(f);
                    totalMass += craftMass;
                    weightedSpeedSum += craftMass * fSpd;
                    weightedRangeSum += craftMass * fRng;
                }
            }
        }

        profile.maxEffectivePackageMass = totalMass;
        profile.currentArmedPackageMass = totalMass;
        profile.initialArmedBomberCount = armedBomberCount;
        profile.currentArmedBomberCount = armedBomberCount;
        profile.committedSquadronCount = sqdnCount;
        profile.committedCraftCount = craftCount;

        float pkgAvgSpeed = (totalMass > 0f) ? (weightedSpeedSum / totalMass) : 180f;
        float bomberAvgSpeed = (bomberMassSum > 0f) ? (bomberWeightedSpeedSum / bomberMassSum) : pkgAvgSpeed;
        float effSpeed = (bomberMassSum > 0f)
                ? (0.70f * bomberAvgSpeed + 0.30f * pkgAvgSpeed)
                : pkgAvgSpeed;
        profile.effectivePackageSpeed = Math.max(85f, Math.min(320f, effSpeed));
        profile.slowestBomberSpeed = (slowestBomberSpd < Float.MAX_VALUE)
                ? Math.max(80f, slowestBomberSpd)
                : profile.effectivePackageSpeed;
        profile.packageAvgReleaseRange = (totalMass > 0f) ? (weightedRangeSum / totalMass) : 520f;

        Vector2f origin = (div.centroid != null && (div.centroid.x != 0f || div.centroid.y != 0f))
                ? div.centroid
                : (carriers.get(0).getLocation() != null ? carriers.get(0).getLocation() : new Vector2f());
        Vector2f targetLoc = (focusEnemyShip != null && focusEnemyShip.getLocation() != null)
                ? focusEnemyShip.getLocation()
                : ((div.targetEnemyDivision != null) ? div.targetEnemyDivision.centroid : origin);
        float execDist = DivisionUiWidgets.distance(origin, targetLoc);
        profile.executionDistanceToTarget = execDist;

        VoidstrikeAttackGeometry geom = div.voidstrikeAttackGeometry != null
                ? div.voidstrikeAttackGeometry
                : VoidstrikeAttackGeometry.DIRECT_ASSAULT;
        float geomExtra = (geom == VoidstrikeAttackGeometry.ANVIL_CROSSFIRE)
                ? 1250f
                : ((geom == VoidstrikeAttackGeometry.PINCER_ENVELOPMENT) ? 750f : 220f);
        int[] comp = computeCompromiseStrikeWidthAndDepth(
                Math.max(1, sqdnCount),
                Math.max(1, div.voidstrikeSquadronWidth),
                Math.max(1, div.voidstrikeSquadronDepth));
        float rankDepthSpan = Math.max(0, comp[1] - 1) * 120f;
        float splitExtra = (div.voidstrikeSplitMode == VoidstrikeSplitMode.SPLIT_TARGETS) ? 420f
                : ((div.voidstrikeSplitMode == VoidstrikeSplitMode.SPLIT_AXES) ? 240f : 0f);
        profile.packageRouteDistance = Math.max(0f, execDist - profile.packageAvgReleaseRange * 0.5f)
                + geomExtra + rankDepthSpan + splitExtra;

        float targetAwaySpeed = 0f;
        if (focusEnemyShip != null && focusEnemyShip.getVelocity() != null && execDist > 1f) {
            float dx = (targetLoc.x - origin.x) / execDist;
            float dy = (targetLoc.y - origin.y) / execDist;
            float vAlong = focusEnemyShip.getVelocity().x * dx + focusEnemyShip.getVelocity().y * dy;
            if (vAlong > 0f) targetAwaySpeed = vAlong;
        }
        float netClosingSpeed = Math.max(70f, profile.effectivePackageSpeed - targetAwaySpeed * 0.55f);
        float rawApproachSec = (profile.packageRouteDistance / netClosingSpeed) * 1.50f + 16.0f;
        profile.packageApproachTimeoutSec = Math.max(48.0f, Math.min(220.0f, rawApproachSec));

        float rawPassSec = 22.0f + ((geomExtra * 0.65f + rankDepthSpan + splitExtra) / Math.max(85f, profile.effectivePackageSpeed))
                + (armedBomberCount > 0 ? 8.0f : 0f);
        profile.packageStrikePassTimeoutSec = Math.max(24.0f, Math.min(85.0f, rawPassSec));
        return profile;
    }

    /**
     * Estimates the average projectile speed of {@code fighter}'s primary strike weapons (clamped to [260, 1200] u/s).
     */
    public static float computeStrikeProjectileSpeed(ShipAPI fighter) {
        if (fighter == null || fighter.getAllWeapons() == null || fighter.getAllWeapons().isEmpty()) {
            return 460f;
        }
        float missileSpeedSum = 0f;
        int missileCount = 0;
        float gunSpeedSum = 0f;
        int gunCount = 0;
        for (WeaponAPI w : fighter.getAllWeapons()) {
            if (w == null || w.isDecorative() || w.isPermanentlyDisabled() || w.isBeam()) continue;
            float spd = w.getProjectileSpeed();
            if (spd <= 1f) continue;
            if (w.getType() == WeaponType.MISSILE || w.usesAmmo()) {
                missileSpeedSum += spd;
                missileCount++;
            } else if (w.getType() == WeaponType.BALLISTIC || w.getType() == WeaponType.ENERGY) {
                gunSpeedSum += spd;
                gunCount++;
            }
        }
        float avg = (missileCount > 0)
                ? (missileSpeedSum / missileCount)
                : ((gunCount > 0) ? (gunSpeedSum / gunCount) : 460f);
        return Math.max(260f, Math.min(1200f, avg));
    }

    /**
     * Computes the angular offset (in degrees) of squadron {@code sqdnIdx}'s attack leg relative to the
     * primary Voidstrike axis {@code (primaryFwdX, primaryFwdY)} for the active {@link VoidstrikeAttackGeometry}:
     *  - DIRECT_ASSAULT: 0 deg (all squadrons attack along primary axis).
     *  - ANVIL_CROSSFIRE: L-shaped 90-degree hammer-and-anvil crossfire (even squadrons pin at 0 deg Anvil leg;
     *    odd squadrons / solo bomber wings flank at +-85 deg Hammer leg on the target's unshielded/weaker side).
     *  - PINCER_ENVELOPMENT: Converging +-55 deg pincer jaws bracketing both target beams.
     */
    /**
     * Resolves the enemy ship assigned to squadron {@code sqdnIdx} within {@code div}'s Voidstrike:
     *  - For UNIFIED or SPLIT_AXES: returns {@code focusEnemyShip}.
     *  - For SPLIT_TARGETS: distributes squadrons across alive combat ships of {@code div.targetEnemyDivision}
     *    (ordered with {@code focusEnemyShip} first, then by hull size descending and ship ID) within 1800u of
     *    {@code focusEnemyShip}, while keeping all squadrons unified inside the same Voidstrike entity.
     */
    public static ShipAPI resolveWingVoidstrikeTargetShip(
            DivisionState div,
            ShipAPI focusEnemyShip,
            int sqdnIdx,
            int totalSquadrons) {
        if (div == null || focusEnemyShip == null) return focusEnemyShip;
        VoidstrikeSplitMode splitMode = div.voidstrikeSplitMode != null
                ? div.voidstrikeSplitMode
                : VoidstrikeSplitMode.UNIFIED;
        if (splitMode != VoidstrikeSplitMode.SPLIT_TARGETS
                || div.targetEnemyDivision == null
                || totalSquadrons <= 1) {
            return focusEnemyShip;
        }
        List<ShipAPI> enemyAlive = div.targetEnemyDivision.getAliveMembers();
        if (enemyAlive == null || enemyAlive.size() <= 1) {
            return focusEnemyShip;
        }
        List<ShipAPI> eligible = new ArrayList<>(enemyAlive.size());
        if (focusEnemyShip.isAlive() && !focusEnemyShip.isHulk() && !focusEnemyShip.isFighter()) {
            eligible.add(focusEnemyShip);
        }
        List<ShipAPI> others = new ArrayList<>(enemyAlive.size());
        for (ShipAPI em : enemyAlive) {
            if (em == null || em == focusEnemyShip || !em.isAlive() || em.isHulk() || em.isFighter()) continue;
            if (em.getLocation() != null && focusEnemyShip.getLocation() != null
                    && DivisionUiWidgets.distance(em.getLocation(), focusEnemyShip.getLocation()) <= 1800f) {
                others.add(em);
            }
        }
        Collections.sort(others, new Comparator<ShipAPI>() {
            @Override
            public int compare(ShipAPI a, ShipAPI b) {
                int oa = a.getHullSize() != null ? a.getHullSize().ordinal() : 0;
                int ob = b.getHullSize() != null ? b.getHullSize().ordinal() : 0;
                if (oa != ob) return Integer.compare(ob, oa);
                String ida = a.getId() != null ? a.getId() : "";
                String idb = b.getId() != null ? b.getId() : "";
                return ida.compareTo(idb);
            }
        });
        eligible.addAll(others);
        if (eligible.isEmpty()) return focusEnemyShip;
        return eligible.get(Math.abs(sqdnIdx) % eligible.size());
    }

    /**
     * Returns true if {@code div} currently has any alive non-returning strike craft within {@code radius}
     * of any alive member of {@code targetDiv} (or {@code targetDiv.centroid}).
     */
    public static boolean hasStrikeCraftInTerminalEnvelope(
            DivisionState div,
            DivisionState targetDiv,
            float radius) {
        if (div == null || targetDiv == null) return false;
        for (ShipAPI carrier : div.getAliveMembers()) {
            if (carrier == null || carrier.getAllWings() == null) continue;
            for (com.fs.starfarer.api.combat.FighterWingAPI w : carrier.getAllWings()) {
                if (w == null || w.getWingMembers() == null) continue;
                for (ShipAPI f : w.getWingMembers()) {
                    if (f == null || !f.isAlive() || f.isHulk() || w.isReturning(f) || f.isLanding()) continue;
                    float d = computeMinDistToTargetEntity(f.getLocation(), targetDiv.centroid, targetDiv, null);
                    if (d <= radius) return true;
                }
            }
        }
        return false;
    }

    /**
     * Collects all live non-fighter enemy combat ships opposing {@code mySideOwner}.
     */
    static List<ShipAPI> collectEnemyCombatShipsForOwner(int mySideOwner) {
        List<ShipAPI> out = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        BattleGroupState oppGroup = (mySideOwner == 0) ? lastVoidstrikeEnemyGroup : lastVoidstrikePlayerGroup;
        if (oppGroup != null && oppGroup.divisions != null) {
            for (DivisionState eDiv : oppGroup.divisions) {
                if (eDiv == null || eDiv.members == null) continue;
                for (ShipAPI s : eDiv.members) {
                    if (s == null || !s.isAlive() || s.isHulk() || s.isFighter() || s.getOwner() == mySideOwner) continue;
                    if (s.getLocation() == null) continue;
                    if (s.getId() == null || seenIds.add(s.getId())) {
                        out.add(s);
                    }
                }
            }
        }
        CombatEngineAPI engine = (lastVoidstrikeUpdateEngine != null)
                ? lastVoidstrikeUpdateEngine
                : Global.getCombatEngine();
        if (engine != null && engine.getShips() != null) {
            for (ShipAPI s : engine.getShips()) {
                if (s == null || !s.isAlive() || s.isHulk() || s.isFighter() || s.getOwner() == mySideOwner) continue;
                if (s.getLocation() == null) continue;
                if (s.getId() == null || seenIds.add(s.getId())) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /**
     * Dynamically resolves a survivable target ship for a strike squadron (or carrier):
     *  - If the squadron is already within terminal approach range (<= 880u) of {@code assignedTarget}, keeps {@code assignedTarget}.
     *  - When {@code assignedTarget} is farther away (> 880u) and a closer enemy combat ship {@code cand} either:
     *      (a) physically blocks the squadron's approach corridor (forcing the squadron to fly through {@code cand}'s AA),
     *      (b) is within 1020u of the squadron while {@code assignedTarget} is >= 380u farther away,
     *      (c) is within 1400u while the squadron is actively taking AA/combat damage,
     *      (d) is closer than a fast-kiting frigate/phase {@code assignedTarget} (>= 165 speed at > 1350u), or
     *      (e) qualifies under MEDIUM (<= 1200u) or FREE (<= 1600u) target observance,
     *    switches the squadron onto the closer enemy combat ship {@code cand} instead of committing suicide into AA!
     */
    public static ShipAPI resolveSurvivableSquadronTargetShip(
            ShipAPI carrier,
            DivisionState div,
            ShipAPI wingLeader,
            List<ShipAPI> wingMembers,
            ShipAPI assignedTarget,
            VoidstrikeTargetObservance observance,
            boolean isBomberWing) {
        if (carrier == null || assignedTarget == null || !assignedTarget.isAlive() || assignedTarget.isHulk()) {
            return assignedTarget;
        }
        Vector2f refLoc = (wingLeader != null && wingLeader.getLocation() != null)
                ? wingLeader.getLocation()
                : carrier.getLocation();
        if (refLoc == null || assignedTarget.getLocation() == null) {
            return assignedTarget;
        }
        float dAssigned = DivisionUiWidgets.distance(refLoc, assignedTarget.getLocation());
        if (dAssigned <= 880f) {
            return assignedTarget;
        }

        boolean squadronTakingFire = false;
        if (wingMembers != null) {
            for (ShipAPI m : wingMembers) {
                if (m == null || !m.isAlive() || m.isHulk()) continue;
                if (m.getHullLevel() < 0.94f
                        || (m.getFluxTracker() != null && m.getFluxTracker().getFluxLevel() >= 0.08f)) {
                    squadronTakingFire = true;
                    break;
                }
            }
        }
        boolean assignedIsFastKiter = assignedTarget.getMaxSpeed() >= 165f && dAssigned >= 1350f;

        float segDx = assignedTarget.getLocation().x - refLoc.x;
        float segDy = assignedTarget.getLocation().y - refLoc.y;
        float segLen = Math.max(1f, dAssigned);
        float uX = segDx / segLen;
        float uY = segDy / segLen;

        List<ShipAPI> candidates = collectEnemyCombatShipsForOwner(carrier.getOwner());
        ShipAPI bestAlt = null;
        float bestAltScore = -Float.MAX_VALUE;
        for (ShipAPI cand : candidates) {
            if (cand == null || cand == assignedTarget || !cand.isAlive() || cand.isHulk() || cand.isFighter()) {
                continue;
            }
            float dCand = DivisionUiWidgets.distance(refLoc, cand.getLocation());
            if (dCand + 220f >= dAssigned) {
                continue;
            }
            float relX = cand.getLocation().x - refLoc.x;
            float relY = cand.getLocation().y - refLoc.y;
            float proj = relX * uX + relY * uY;
            float perp = Math.abs(relX * (-uY) + relY * uX);
            float aaCorridorRadius = 780f + Math.max(45f, cand.getCollisionRadius());
            boolean blocksFlightCorridor = proj > -60f && proj < (dAssigned - 160f) && perp <= aaCorridorRadius;

            boolean switchForAaCorridor = blocksFlightCorridor && (dCand + 320f < dAssigned);
            boolean switchUnderFire = squadronTakingFire && blocksFlightCorridor && dCand <= 1450f && (dCand + 260f < dAssigned);
            boolean switchFromFastKiter = assignedIsFastKiter && dCand <= 1900f && (dCand + 360f < dAssigned);
            boolean switchForFreeObs = observance == VoidstrikeTargetObservance.FREE
                    && dCand <= 1550f && (dCand + 280f < dAssigned);

            if (switchForAaCorridor
                    || switchUnderFire
                    || switchFromFastKiter
                    || switchForFreeObs) {
                float candScore = -dCand
                        + (blocksFlightCorridor ? 420f : 0f)
                        + (cand.getHullSize() != null ? cand.getHullSize().ordinal() * 95f : 0f);
                if (candScore > bestAltScore) {
                    bestAltScore = candScore;
                    bestAlt = cand;
                }
            }
        }
        return (bestAlt != null) ? bestAlt : assignedTarget;
    }

    /**
     * Computes an anti-AA evasive waypoint for a strike craft in transit or egress:
     *  1. Smooth lateral + radial threat-skirting around any non-target enemy combat ship whose PD/AA envelope
     *     (580u + collisionRadius) intersects the flight segment {@code fighter -> rawWaypoint}, so squadrons
     *     curve around intervening hostile decks without zig-zagging in open space.
     *  2. Modest evasive weaving ONLY when the fighter is physically inside a non-target ship's PD envelope (<= 560u)
     *     and actively taking fire, never outside enemy PD range.
     */
    public static Vector2f computeAntiAaEvasionWaypoint(
            ShipAPI fighter,
            ShipAPI attackTarget,
            Vector2f rawWaypoint,
            int wingIdx,
            int craftIdx,
            float now) {
        if (fighter == null || fighter.getLocation() == null || rawWaypoint == null) {
            return rawWaypoint;
        }
        Vector2f fLoc = fighter.getLocation();
        float dx = rawWaypoint.x - fLoc.x;
        float dy = rawWaypoint.y - fLoc.y;
        float segDist = (float) Math.sqrt(dx * dx + dy * dy);
        if (segDist < 1f) {
            return new Vector2f(rawWaypoint);
        }
        float dirX = dx / segDist;
        float dirY = dy / segDist;
        float rightX = dirY;
        float rightY = -dirX;

        float dTarget = (attackTarget != null && attackTarget.getLocation() != null)
                ? DivisionUiWidgets.distance(fLoc, attackTarget.getLocation())
                : Float.MAX_VALUE;

        float avoidOffsetX = 0f;
        float avoidOffsetY = 0f;
        float minDistToNonTargetEnemy = Float.MAX_VALUE;

        List<ShipAPI> enemies = collectEnemyCombatShipsForOwner(fighter.getOwner());
        for (ShipAPI eShip : enemies) {
            if (eShip == null || !eShip.isAlive() || eShip.isHulk() || eShip.isFighter() || eShip.getLocation() == null) {
                continue;
            }
            if (eShip == attackTarget) {
                continue;
            }
            float dShip = DivisionUiWidgets.distance(fLoc, eShip.getLocation());
            if (dShip < minDistToNonTargetEnemy) {
                minDistToNonTargetEnemy = dShip;
            }
            float keepOutRadius = 580f + Math.max(45f, eShip.getCollisionRadius());
            float relX = eShip.getLocation().x - fLoc.x;
            float relY = eShip.getLocation().y - fLoc.y;
            float proj = relX * dirX + relY * dirY;
            float signedLat = relX * rightX + relY * rightY;
            float absLat = Math.abs(signedLat);

            if (dShip <= keepOutRadius || (proj > -40f && proj < Math.min(segDist + 120f, 1350f) && absLat <= keepOutRadius)) {
                float pushSide = (signedLat > 0f) ? -1f : 1f;
                float penetration = Math.max(0f, keepOutRadius - Math.min(dShip, absLat));
                float lateralPush = Math.min(280f, 120f + penetration * 0.55f);
                avoidOffsetX += rightX * (pushSide * lateralPush);
                avoidOffsetY += rightY * (pushSide * lateralPush);
                if (dShip < keepOutRadius && dShip > 1f) {
                    float awayX = (fLoc.x - eShip.getLocation().x) / dShip;
                    float awayY = (fLoc.y - eShip.getLocation().y) / dShip;
                    float radialPush = Math.min(220f, (keepOutRadius - dShip) * 0.55f);
                    avoidOffsetX += awayX * radialPush;
                    avoidOffsetY += awayY * radialPush;
                }
            }
        }

        float totalAvoidMag = (float) Math.sqrt(avoidOffsetX * avoidOffsetX + avoidOffsetY * avoidOffsetY);
        if (totalAvoidMag > 320f) {
            avoidOffsetX = (avoidOffsetX / totalAvoidMag) * 320f;
            avoidOffsetY = (avoidOffsetY / totalAvoidMag) * 320f;
        }

        boolean takingFire = fighter.getHullLevel() < 0.90f
                || (fighter.getFluxTracker() != null && fighter.getFluxTracker().getFluxLevel() >= 0.12f);
        if (takingFire && minDistToNonTargetEnemy <= 540f && (attackTarget == null || dTarget > 900f)) {
            float jinkPhase = now * 2.8f + wingIdx * 1.45f + craftIdx * 2.05f;
            float jinkOffset = (float) Math.sin(jinkPhase) * 55f;
            avoidOffsetX += rightX * jinkOffset;
            avoidOffsetY += rightY * jinkOffset;
        }

        return new Vector2f(rawWaypoint.x + avoidOffsetX, rawWaypoint.y + avoidOffsetY);
    }

    public static float computeSquadronAttackAngleOffsetDeg(
            VoidstrikeAttackGeometry geometry,
            int sqdnIdx,
            int totalSquadrons,
            boolean isBomberWing,
            ShipAPI focusEnemyShip,
            float primaryFwdX,
            float primaryFwdY) {
        return computeSquadronAttackAngleOffsetDeg(
                geometry,
                VoidstrikeSplitMode.UNIFIED,
                sqdnIdx,
                totalSquadrons,
                isBomberWing,
                focusEnemyShip,
                primaryFwdX,
                primaryFwdY);
    }

    public static float computeSquadronAttackAngleOffsetDeg(
            VoidstrikeAttackGeometry geometry,
            VoidstrikeSplitMode splitMode,
            int sqdnIdx,
            int totalSquadrons,
            boolean isBomberWing,
            ShipAPI focusEnemyShip,
            float primaryFwdX,
            float primaryFwdY) {
        VoidstrikeAttackGeometry effGeom = geometry != null ? geometry : VoidstrikeAttackGeometry.DIRECT_ASSAULT;
        VoidstrikeSplitMode effSplit = splitMode != null ? splitMode : VoidstrikeSplitMode.UNIFIED;
        float preferredFlankSign = resolveExposedFlankSign(focusEnemyShip, primaryFwdX, primaryFwdY);

        if (effGeom == VoidstrikeAttackGeometry.DIRECT_ASSAULT) {
            if ((effSplit == VoidstrikeSplitMode.SPLIT_AXES || effSplit == VoidstrikeSplitMode.SPLIT_TARGETS)
                    && totalSquadrons >= 2) {
                if (totalSquadrons == 2) {
                    return ((sqdnIdx % 2) == 0) ? -35f : 35f;
                }
                int mod3 = Math.abs(sqdnIdx) % 3;
                return (mod3 == 0) ? 0f : ((mod3 == 1) ? -38f : 38f);
            }
            return 0f;
        }
        if (effGeom == VoidstrikeAttackGeometry.ANVIL_CROSSFIRE) {
            boolean isHammerLeg = (totalSquadrons <= 1) ? isBomberWing : ((sqdnIdx % 2) == 1);
            if (!isHammerLeg) {
                return 0f;
            }
            if ((effSplit == VoidstrikeSplitMode.SPLIT_AXES || effSplit == VoidstrikeSplitMode.SPLIT_TARGETS)
                    && totalSquadrons >= 4) {
                return (((sqdnIdx / 2) % 2) == 0 ? 1f : -1f) * preferredFlankSign * 85f;
            }
            return preferredFlankSign * 85f;
        }
        if (effGeom == VoidstrikeAttackGeometry.PINCER_ENVELOPMENT) {
            if (totalSquadrons <= 1) {
                return preferredFlankSign * 55f;
            }
            return ((sqdnIdx % 2) == 0) ? -55f : 55f;
        }
        return 0f;
    }

    private static float resolveExposedFlankSign(ShipAPI target, float primaryFwdX, float primaryFwdY) {
        if (target == null) return 1f;
        float shieldFacing = target.getFacing();
        ShieldAPI shield = target.getShield();
        if (shield != null && shield.isOn()) {
            shieldFacing = shield.getFacing();
        }
        float incomingBearing = (float) Math.toDegrees(Math.atan2(-primaryFwdY, -primaryFwdX));
        float plusBearing = incomingBearing + 85f;
        float minusBearing = incomingBearing - 85f;
        float sepPlus = Math.abs(shortestAngleDiffDeg(shieldFacing, plusBearing));
        float sepMinus = Math.abs(shortestAngleDiffDeg(shieldFacing, minusBearing));

        // Also evaluate adjacent non-target enemy AA ship density on the + vs - flank so the flanking leg
        // naturally avoids flying through a neighboring ship's PD bubble!
        float aaPenaltyPlus = 0f;
        float aaPenaltyMinus = 0f;
        if (target.getLocation() != null) {
            double plusRad = Math.toRadians(plusBearing);
            double minusRad = Math.toRadians(minusBearing);
            Vector2f plusProbe = new Vector2f(
                    target.getLocation().x + (float) Math.cos(plusRad) * 580f,
                    target.getLocation().y + (float) Math.sin(plusRad) * 580f);
            Vector2f minusProbe = new Vector2f(
                    target.getLocation().x + (float) Math.cos(minusRad) * 580f,
                    target.getLocation().y + (float) Math.sin(minusRad) * 580f);
            List<ShipAPI> sameSideShips = collectEnemyCombatShipsForOwner(target.getOwner() == 0 ? 1 : 0);
            for (ShipAPI escort : sameSideShips) {
                if (escort == null || escort == target || !escort.isAlive() || escort.isHulk() || escort.isFighter()) continue;
                if (escort.getLocation() == null) continue;
                float dPlus = DivisionUiWidgets.distance(escort.getLocation(), plusProbe);
                float dMinus = DivisionUiWidgets.distance(escort.getLocation(), minusProbe);
                if (dPlus < 720f) {
                    aaPenaltyPlus += (720f - dPlus) * 0.12f;
                }
                if (dMinus < 720f) {
                    aaPenaltyMinus += (720f - dMinus) * 0.12f;
                }
            }
        }
        float scorePlus = sepPlus - aaPenaltyPlus;
        float scoreMinus = sepMinus - aaPenaltyMinus;
        return (scorePlus >= scoreMinus) ? 1f : -1f;
    }

    private static float shortestAngleDiffDeg(float fromDeg, float toDeg) {
        float diff = (toDeg - fromDeg) % 360f;
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;
        return diff;
    }

    /**
     * Computes the 3-level hierarchical firing sector (Voidstrike -> Squadron -> Craft) and ballistic aim point
     * for an individual strike craft on its attack run.
     */
    public static FiringSectorAssignment computeHierarchicalFiringSector(
            VoidstrikeGunneryMode gunneryMode,
            VoidstrikeAoaCorrection aoaCorrection,
            ShipAPI target,
            ShipAPI fighter,
            int divIdxInStrike,
            int numParticipatingDivs,
            int sqdnIdx,
            int totalSquadrons,
            int craftIdx,
            int squadronSize,
            float legFwdX,
            float legFwdY,
            float legRightX,
            float legRightY) {
        FiringSectorAssignment out = new FiringSectorAssignment();
        if (target == null || target.getLocation() == null) {
            return out;
        }
        float rawRadius = target.getCollisionRadius();
        if (rawRadius <= 1f && target.getHullSize() != null) {
            switch (target.getHullSize()) {
                case CAPITAL_SHIP:
                    rawRadius = 190f;
                    break;
                case CRUISER:
                    rawRadius = 125f;
                    break;
                case DESTROYER:
                    rawRadius = 85f;
                    break;
                default:
                    rawRadius = 55f;
                    break;
            }
        }
        float targetRadius = Math.max(45f, rawRadius);
        float projSpeed = computeStrikeProjectileSpeed(fighter);
        float distToTarget = (fighter != null && fighter.getLocation() != null)
                ? DivisionUiWidgets.distance(fighter.getLocation(), target.getLocation())
                : 450f;
        float tFlight = Math.max(0.12f, Math.min(1.80f, Math.max(0f, distToTarget - 105f) / projSpeed));

        float targetVx = (target.getVelocity() != null) ? target.getVelocity().x : 0f;
        float targetVy = (target.getVelocity() != null) ? target.getVelocity().y : 0f;
        float lateralTargetSpeed = Math.abs(targetVx * legRightX + targetVy * legRightY);

        // Level 1: Whole Voidstrike Saturation Span
        if (gunneryMode == VoidstrikeGunneryMode.AREA_SATURATION) {
            float rawSpan = 2.30f * targetRadius + 0.85f * lateralTargetSpeed * tFlight;
            out.voidstrikeSpanWidth = Math.max(120f, Math.min(560f, rawSpan));
        } else {
            // PRECISION: tight core grouping within +-18u of target center
            out.voidstrikeSpanWidth = Math.min(36f, 0.28f * targetRadius);
        }

        int safeDivCount = Math.max(1, numParticipatingDivs);
        int safeDivIdx = Math.max(0, Math.min(safeDivCount - 1, divIdxInStrike));
        out.divisionSectorWidth = out.voidstrikeSpanWidth / safeDivCount;
        out.divisionSectorCenter = (((safeDivIdx + 0.5f) / safeDivCount) - 0.5f) * out.voidstrikeSpanWidth;

        // Level 2: Squadron Sub-Sector within the Division's Voidstrike slice
        int safeSqdnCount = Math.max(1, totalSquadrons);
        int safeSqdnIdx = Math.max(0, Math.min(safeSqdnCount - 1, sqdnIdx));
        out.squadronSectorWidth = out.divisionSectorWidth / safeSqdnCount;
        out.squadronSectorCenter = out.divisionSectorCenter
                + (((safeSqdnIdx + 0.5f) / safeSqdnCount) - 0.5f) * out.divisionSectorWidth;

        // Level 3: Individual Ordnance-Bearing Strike Craft Firing Lane within its Squadron Sub-Sector
        int safeCraftCount = Math.max(1, squadronSize);
        int safeCraftIdx = Math.max(0, Math.min(safeCraftCount - 1, craftIdx));
        out.craftLateralOffset = out.squadronSectorCenter
                + (((safeCraftIdx + 0.5f) / safeCraftCount) - 0.5f) * out.squadronSectorWidth;
        out.craftDepthOffset = (gunneryMode == VoidstrikeGunneryMode.AREA_SATURATION)
                ? (((safeCraftIdx % 2) == 0 ? -0.22f : 0.22f) * targetRadius)
                : 0f;

        Vector2f tLoc = target.getLocation();
        // Clamp the physical aim point lateral offset to +-65% of the target's collision radius so Area Saturation
        // salvos bracket the entire physical hull silhouette without aiming unguided torpedoes into empty space outside the hull!
        float maxHullAimOffset = Math.max(28f, targetRadius * 0.65f);
        float hullLateralAim = Math.max(-maxHullAimOffset, Math.min(maxHullAimOffset, out.craftLateralOffset));
        float hullDepthAim = Math.max(-maxHullAimOffset * 0.5f, Math.min(maxHullAimOffset * 0.5f, out.craftDepthOffset));
        out.worldSectorPoint.set(
                tLoc.x + legRightX * hullLateralAim + legFwdX * hullDepthAim,
                tLoc.y + legRightY * hullLateralAim + legFwdY * hullDepthAim);

        float leadFactor = (aoaCorrection == VoidstrikeAoaCorrection.LOW) ? 0.55f : 1.00f;
        out.worldAimPoint.set(
                out.worldSectorPoint.x + targetVx * tFlight * leadFactor,
                out.worldSectorPoint.y + targetVy * tFlight * leadFactor);
        return out;
    }

    /**
     * Estimates the maximum effective release range of {@code fighter}'s primary strike weapons (clamped to [280, 900] u).
     */
    public static float computeMaxStrikeWeaponRange(ShipAPI fighter) {
        if (fighter == null || fighter.getAllWeapons() == null || fighter.getAllWeapons().isEmpty()) {
            return 520f;
        }
        float maxMissileRange = 0f;
        float maxGunRange = 0f;
        for (WeaponAPI w : fighter.getAllWeapons()) {
            if (w == null || w.isDecorative() || w.isPermanentlyDisabled()) continue;
            WeaponType wt = w.getType();
            if (wt == WeaponType.LAUNCH_BAY || wt == WeaponType.SYSTEM || wt == WeaponType.STATION_MODULE) continue;
            float rng = w.getRange();
            if (rng <= 1f) continue;
            if (wt == WeaponType.MISSILE || w.usesAmmo()) {
                if (rng > maxMissileRange) maxMissileRange = rng;
            } else if (wt == WeaponType.BALLISTIC || wt == WeaponType.ENERGY) {
                if (rng > maxGunRange) maxGunRange = rng;
            }
        }
        float chosen = (maxMissileRange > 0f) ? maxMissileRange : ((maxGunRange > 0f) ? maxGunRange : 520f);
        return Math.max(300f, Math.min(950f, chosen));
    }

    /**
     * 60 Hz helm driver for strike craft (`FighterAI`) during Stage 0/1 marshalling and multi-axis flanking doglegs
     * (`L-Crossfire` and `Pincer Envelopment`). Because Starsector's `FighterAI` does not read `AIFlags.MOVEMENT_DEST`
     * and runs after `EveryFrameCombatPlugin.advance` inside `CombatEngine.advanceInner`, this helper issues explicit
     * turn/throttle commands and blocks opposing commands for one frame via `blockCommandForOneFrame`.
     */
    private static float currentFrameDt = 0.016f;

    public static void steerFighterTowardPoint60Hz(ShipAPI fighter, Vector2f dest, boolean fullSpeed) {
        if (fighter == null || fighter.getLocation() == null || dest == null) return;
        float dx = dest.x - fighter.getLocation().x;
        float dy = dest.y - fighter.getLocation().y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;

        float dirX = dx / dist;
        float dirY = dy / dist;
        float desiredFacing = (float) Math.toDegrees(Math.atan2(dirY, dirX));
        if (desiredFacing < 0f) desiredFacing += 360f;

        float dt = (Global.getCombatEngine() != null && Global.getCombatEngine().getElapsedInLastFrame() > 0f)
                ? Math.min(0.05f, Global.getCombatEngine().getElapsedInLastFrame())
                : currentFrameDt;
        boolean underHeavyFire = fighter.getHullLevel() < 0.80f
                || (fighter.getFluxTracker() != null && fighter.getFluxTracker().getFluxLevel() >= 0.22f);
        float maxTurnRate = Math.max(120f, fighter.getMaxTurnRate() > 0f ? fighter.getMaxTurnRate() : 180f);
        float angleDiff = shortestAngleDiffDeg(fighter.getFacing(), desiredFacing);
        if (!underHeavyFire || Math.abs(angleDiff) > 38f) {
            // Apply 2.2x maxTurnRate * dt so EveryFrameCombatPlugin cleanly overcomes any intra-frame counter-turn
            // applied earlier in the same frame by vanilla FighterAI / AttackRunManeuver toward CARRIER_FIGHTER_TARGET!
            float maxStep = maxTurnRate * 2.2f * dt;
            float step = Math.max(-maxStep, Math.min(maxStep, angleDiff));
            float newFacing = (fighter.getFacing() + step) % 360f;
            if (newFacing < 0f) newFacing += 360f;
            fighter.setFacing(newFacing);
            angleDiff = shortestAngleDiffDeg(newFacing, desiredFacing);
        }

        fighter.giveCommand(ShipCommand.ACCELERATE, null, 0);
        fighter.blockCommandForOneFrame(ShipCommand.DECELERATE);
        fighter.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
        if (angleDiff > 2.0f) {
            fighter.giveCommand(ShipCommand.TURN_LEFT, null, 0);
            if (!underHeavyFire) {
                fighter.blockCommandForOneFrame(ShipCommand.TURN_RIGHT);
            }
        } else if (angleDiff < -2.0f) {
            fighter.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
            if (!underHeavyFire) {
                fighter.blockCommandForOneFrame(ShipCommand.TURN_LEFT);
            }
        } else if (!underHeavyFire) {
            fighter.blockCommandForOneFrame(ShipCommand.TURN_LEFT);
            fighter.blockCommandForOneFrame(ShipCommand.TURN_RIGHT);
        }

        if (fighter.getVelocity() != null) {
            Vector2f vel = fighter.getVelocity();
            float maxSpd = fighter.getMaxSpeed() > 0f ? fighter.getMaxSpeed() : 220f;
            float targetSpd = fullSpeed ? (maxSpd * 0.94f) : (maxSpd * Math.min(0.85f, Math.max(0.35f, dist / 260f)));
            float blend = Math.min(0.85f, dt * 28.0f);
            vel.set(
                    vel.x * (1f - blend) + dirX * targetSpd * blend,
                    vel.y * (1f - blend) + dirY * targetSpd * blend);
        }
    }

    /**
     * Applies terminal Angle-of-Attack (AoA) run-in corridor steering, lateral slip damping, nose alignment,
     * helm command locking against `FighterAI` override, and off-boresight fire-gating (`ShipCommand.FIRE` blocked
     * when $|\Delta\theta| > 6.5^\circ$ under `STRONG` AoA, and triggered along `sector.worldAimPoint` when aligned).
     * Returns true if off-boresight fire-gating blocked premature ordnance release on this frame.
     */
    public static boolean applyTerminalStrikeCraftGunnery(
            ShipAPI fighter,
            ShipwideAIFlags fFlags,
            ShipAPI target,
            FiringSectorAssignment sector,
            VoidstrikeAoaCorrection aoaCorrection,
            boolean hasUnguidedOrdnance,
            float dEnemy) {
        if (fighter == null || fighter.getLocation() == null || sector == null) return false;
        float dx = sector.worldAimPoint.x - fighter.getLocation().x;
        float dy = sector.worldAimPoint.y - fighter.getLocation().y;
        float distAim = (float) Math.sqrt(dx * dx + dy * dy);
        if (distAim < 1f) return false;

        float aimDirX = dx / distAim;
        float aimDirY = dy / distAim;
        float desiredFacing = (float) Math.toDegrees(Math.atan2(aimDirY, aimDirX));
        if (desiredFacing < 0f) desiredFacing += 360f;

        float targetRad = (target != null && target.getCollisionRadius() > 0f) ? target.getCollisionRadius() : 90f;
        float maxWepRange = computeMaxStrikeWeaponRange(fighter);
        float minStandoffFloor = Math.max(targetRad + 180f, 340f);
        float minInnerDiveFloor = Math.max(targetRad + 45f, 210f);

        // Aim movement toward the target point while outside standoff floor (never projecting +240u past the enemy hull).
        // Once inside minInnerDiveFloor (just outside the enemy collision radius), release MOVEMENT_DEST and DO_NOT_BACK_OFF
        // so no strike craft or corvette ever tries to fly onto and mate with the center of the enemy hull!
        if (fFlags != null) {
            if (dEnemy <= minInnerDiveFloor) {
                fFlags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
                fFlags.unsetFlag(AIFlags.MOVEMENT_DEST);
                fFlags.setFlag(AIFlags.BACK_OFF, DivisionCommanderAI.FLAG_TTL);
            } else if (dEnemy > minStandoffFloor) {
                fFlags.setFlag(AIFlags.MOVEMENT_DEST, DivisionCommanderAI.FLAG_TTL, sector.worldAimPoint);
                fFlags.setFlag(AIFlags.DO_NOT_BACK_OFF, DivisionCommanderAI.FLAG_TTL);
            } else {
                Vector2f forwardPassGoal = new Vector2f(
                        fighter.getLocation().x + aimDirX * 140f,
                        fighter.getLocation().y + aimDirY * 140f);
                fFlags.setFlag(AIFlags.MOVEMENT_DEST, DivisionCommanderAI.FLAG_TTL, forwardPassGoal);
                fFlags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
            }
        }

        boolean isStrong = (aoaCorrection != VoidstrikeAoaCorrection.LOW);
        float maxDampDist = isStrong ? 880f : 650f;
        float dt = (Global.getCombatEngine() != null && Global.getCombatEngine().getElapsedInLastFrame() > 0f)
                ? Math.min(0.05f, Global.getCombatEngine().getElapsedInLastFrame())
                : currentFrameDt;
        float frameScale = Math.max(0.25f, Math.min(4.0f, dt / 0.016f));

        if (dEnemy <= maxDampDist && dEnemy > minInnerDiveFloor && fighter.getVelocity() != null) {
            Vector2f vel = fighter.getVelocity();
            float vFwd = vel.x * aimDirX + vel.y * aimDirY;
            float vPerpX = vel.x - vFwd * aimDirX;
            float vPerpY = vel.y - vFwd * aimDirY;
            float baseDampKeep = isStrong ? 0.76f : 0.92f;
            float dampKeep = (float) Math.pow(baseDampKeep, frameScale);
            float minFwd = isStrong ? Math.max(vFwd, fighter.getMaxSpeed() * 0.85f) : Math.max(0f, vFwd);
            vel.set(
                    aimDirX * minFwd + vPerpX * dampKeep,
                    aimDirY * minFwd + vPerpY * dampKeep);
        }

        float angleDiff = shortestAngleDiffDeg(fighter.getFacing(), desiredFacing);
        if (dEnemy <= maxDampDist && dEnemy > minInnerDiveFloor) {
            float maxSlewDegPerSec = isStrong ? 220f : 95f;
            float maxStep = maxSlewDegPerSec * dt;
            float step = Math.max(-maxStep, Math.min(maxStep, angleDiff));
            float newFacing = (fighter.getFacing() + step) % 360f;
            if (newFacing < 0f) newFacing += 360f;
            fighter.setFacing(newFacing);
            angleDiff = shortestAngleDiffDeg(newFacing, desiredFacing);

            // Only lock forward acceleration while still outside standoff release floor so bombers never kamikaze into the hull
            if (dEnemy > minStandoffFloor) {
                fighter.giveCommand(ShipCommand.ACCELERATE, null, 0);
                fighter.blockCommandForOneFrame(ShipCommand.DECELERATE);
                fighter.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
                if (isStrong) {
                    fighter.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
                    fighter.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
                }
            }
            if (angleDiff > 1.5f) {
                fighter.giveCommand(ShipCommand.TURN_LEFT, null, 0);
                fighter.blockCommandForOneFrame(ShipCommand.TURN_RIGHT);
            } else if (angleDiff < -1.5f) {
                fighter.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
                fighter.blockCommandForOneFrame(ShipCommand.TURN_LEFT);
            } else {
                fighter.blockCommandForOneFrame(ShipCommand.TURN_LEFT);
                fighter.blockCommandForOneFrame(ShipCommand.TURN_RIGHT);
            }
        }

        // Fire-Gate Discipline under STRONG AoA: hold unguided torpedo/rocket release when off-boresight (> 9.5 deg)
        // while outside standoff release floor (dEnemy > 380u), releasing immediately once aligned!
        if (isStrong && hasUnguidedOrdnance && Math.abs(angleDiff) > 9.5f && dEnemy > 380f) {
            fighter.blockCommandForOneFrame(ShipCommand.FIRE);
            return true;
        }

        // Standoff On-Boresight Trigger Release: launch ordnance immediately upon entering maximum weapon range
        // so strike craft utilize their full weapon range envelope for survivability rather than diving to point-blank range!
        float alignGateDeg = isStrong ? 12.0f : 18.0f;
        float releaseEnvelope = maxWepRange + targetRad * 0.90f;
        if (Math.abs(angleDiff) <= alignGateDeg && dEnemy <= releaseEnvelope) {
            fighter.giveCommand(ShipCommand.FIRE, sector.worldAimPoint, 0);
            fighter.giveCommand(ShipCommand.FIRE, sector.worldAimPoint, 1);
            fighter.giveCommand(ShipCommand.FIRE, sector.worldAimPoint, 2);
        }
        return false;
    }

    /**
     * 60 Hz per-frame strike craft gunnery & multi-axis dogleg updater.
     * Ensures off-boresight fire-gating (`blockCommandForOneFrame(ShipCommand.FIRE)`), lateral slip velocity
     * damping, and nose slewing execute on every combat frame rather than only on 2 Hz C2 ticks.
     */
    public static void advanceEveryFrameStrikeCraftGunnery(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup,
            float now) {
        if (engine != null && engine.getElapsedInLastFrame() > 0f) {
            currentFrameDt = Math.min(0.05f, engine.getElapsedInLastFrame());
        } else if (Global.getCombatEngine() != null && Global.getCombatEngine().getElapsedInLastFrame() > 0f) {
            currentFrameDt = Math.min(0.05f, Global.getCombatEngine().getElapsedInLastFrame());
        } else {
            currentFrameDt = 0.033f;
        }
        advanceGroupStrikeCraftEveryFrame(playerGroup, now);
        advanceGroupStrikeCraftEveryFrame(alliedAiGroup, now);
        advanceGroupStrikeCraftEveryFrame(enemyGroup, now);
    }

    private static void advanceGroupStrikeCraftEveryFrame(BattleGroupState group, float now) {
        if (group == null || group.divisions == null || group.isPassiveOpposingView) return;
        for (DivisionState div : group.divisions) {
            if (div == null) continue;
            if (!div.voidstrikeEgressActive && (div.targetEnemyDivision == null || !div.targetEnemyDivision.isCombatEffective())) {
                continue;
            }
            ShipAPI focusEnemyShip = resolveVoidstrikeFocusShip(div);
            if (!div.voidstrikeEgressActive && (focusEnemyShip == null || !focusEnemyShip.isAlive() || focusEnemyShip.isHulk())) {
                continue;
            }

            List<ShipAPI> alive = div.getAliveMembers();
            if (alive == null || alive.isEmpty()) continue;
            for (int i = 0; i < alive.size(); i++) {
                ShipAPI carrier = alive.get(i);
                if (carrier == null || carrier.getAllWings() == null || carrier.getAllWings().isEmpty()) continue;
                dressShipStrikeCraftInFormation(carrier, i, focusEnemyShip, div, group, now);
            }
        }
    }

    public static ShipAPI resolveVoidstrikeFocusShip(DivisionState div) {
        if (div == null || div.targetEnemyDivision == null) return null;
        if (div.targetEnemyDivision.flagship != null
                && div.targetEnemyDivision.flagship.isAlive()
                && !div.targetEnemyDivision.flagship.isHulk()) {
            return div.targetEnemyDivision.flagship;
        }
        List<ShipAPI> enemyAlive = div.targetEnemyDivision.getAliveMembers();
        return (enemyAlive != null && !enemyAlive.isEmpty()) ? enemyAlive.get(0) : null;
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

        // Emergency-Only Pullback: only discard a carrier's strike wings as returning when the carrier/division is
        // in a genuine emergency pullback or post-culmination egress with no armed craft still pressing downrange!
        boolean stagingOrStriking = div != null
                && div.targetEnemyDivision != null
                && (div.currentIntent == DivisionModel.DivisionIntentType.MARSHAL_FOR_STRIKE
                || div.voidstrikeStage == 1
                || div.voidstrikeStage == 2);
        boolean armedDownrange = hasArmedStrikeCraftCommittedDownrange(div);
        boolean emergencyRecall = div != null && div.voidstrikeEmergencyRecallActive;
        boolean carrierPullingBack = emergencyRecall
                || (!armedDownrange && ((div != null && div.voidstrikeEgressActive)
                || (carrier.isPullBackFighters() && !stagingOrStriking)
                || (div != null && div.targetEnemyDivision == null && (div.currentIntent == DivisionModel.DivisionIntentType.HOLD_IN_RESERVE
                || div.currentIntent == DivisionModel.DivisionIntentType.BREAK_OFF_AND_RETIRE
                || div.currentIntent == DivisionModel.DivisionIntentType.HAUL_OUT_TO_VENT))));
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

            boolean isBomberWing = (wing.getSpec() != null && wing.getSpec().isBomber())
                    || (wing.getRole() == com.fs.starfarer.api.loading.WingRole.BOMBER);
            List<ShipAPI> nonReturningFighters = new ArrayList<>(members.size());
            float sumX = 0f;
            float sumY = 0f;
            ShipAPI fighterAssignedEnemy = null;

            for (ShipAPI f : members) {
                if (f == null || !f.isAlive() || f.isHulk() || f.isExpired()) continue;
                boolean hasUnexpended = hasUnexpendedStrikeOrdnance(f, isBomberWing);
                // If an armed bomber was prematurely marked returning by vanilla while not in emergency recall, cancel return!
                if (wing.isReturning(f) && !emergencyRecall && stagingOrStriking) {
                    boolean finiteAmmoCraft = isBomberWing || hasFiniteAmmoStrikeWeapons(f);
                    boolean refitTimerOk = f.getFighterTimeBeforeRefit() <= 0f || f.getFighterTimeBeforeRefit() > 2.5f;
                    if (finiteAmmoCraft && hasUnexpended && f.getHullLevel() >= 0.20f && !f.isLanding() && refitTimerOk) {
                        cancelPrematureFighterReturn(wing, f);
                    }
                }
                // When strike craft are returning to refit/land, or when a bomber has expended all of its strike ordnance,
                // they are no longer part of the active inbound strike package!
                if (wing.isReturning(f) || (isBomberWing && !hasUnexpended)) continue;
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
                        && (div.voidstrikeStage == 0
                        || div.voidstrikeStage == 1
                        || div.currentIntent == DivisionModel.DivisionIntentType.MARSHAL_FOR_STRIKE)
                        && !alreadyActive) {
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
                        // trailing replacement wings from the same division, converging multi-division strikes on
                        // the same target, or sub-wings already belonging to the same active VoidstrikeRecord (even when
                        // Pincer jaws spread > 1200u apart!) always merge into the existing strike rather than burning new numbers!
                        boolean sameDiv = member.divisionId.equals(candJ.divisionId);
                        boolean sameTarget = member.targetKey.equals(candJ.targetKey);
                        boolean sharedActiveVoidstrike = false;
                        for (VoidstrikeRecord activeRec : activeVoidstrikes) {
                            if (activeRec != null && activeRec.side == side
                                    && activeRec.participatingWingKeys.contains(member.wingKey)
                                    && activeRec.participatingWingKeys.contains(candJ.wingKey)) {
                                sharedActiveVoidstrike = true;
                                break;
                            }
                        }
                        if (sameDiv || sameTarget || sharedActiveVoidstrike || (distCenters <= 1150f && distSeed <= 1350f)) {
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
                if (hRec.voidstrikeNumber > 0 && (hRec.reachedTarget || hRec.emergencyRecallTriggered)) {
                    if (hRec.culminationReason == null) {
                        String reason = hRec.emergencyRecallTriggered
                                ? "emergency recall"
                                : "lost local advantage";
                        hRec.culminationReason = reason;
                        DivisionCombatLog.logVoidstrikeCulminated(hRec.side, hRec.voidstrikeNumber, reason, now);
                    }
                    releaseSortieNumber(hRec);
                }
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

            boolean hasSplitWingsOrMultiAxis = clusterDivs.size() > 1;
            for (DivisionState cd : clusterDivs) {
                if (cd != null && (cd.voidstrikeSplitMode != VoidstrikeSplitMode.UNIFIED
                        || cd.voidstrikeAttackGeometry != VoidstrikeAttackGeometry.DIRECT_ASSAULT)) {
                    hasSplitWingsOrMultiAxis = true;
                    break;
                }
            }
            float effectiveCohesionRadius = hasSplitWingsOrMultiAxis
                    ? (SORTIE_COHESION_RADIUS * 1.85f + Math.max(0, clusterDivs.size() - 1) * 520f)
                    : SORTIE_COHESION_RADIUS;

            if (matched == null) {
                // Do not birth a stillborn Voidstrike if the candidate fighters have not yet formed spatial cohesion,
                // or if it is a lone 1-craft splinter without multi-squadron/multi-craft strike mass!
                if (clusterWingKeys.size() < 2 && clusterFighters.size() < 2) {
                    continue;
                }
                if (clusterFighters.size() >= 3 && spread > effectiveCohesionRadius) {
                    continue;
                }
                // For enemy side, keep tracking the cluster with voidstrikeNumber = 0 while hidden in Fog of War,
                // and only allocate a Voidstrike number once it becomes visible to the player!
                int assignedNumber = (side == VOIDSTRIKE_SIDE_ENEMY && !isVisibleToPlayer)
                        ? 0
                        : claimSortieNumber(clusterDivs, updatedForSide, existingForSide, sideIdx);
                matched = new VoidstrikeRecord(side, assignedNumber, primaryTargetKey);
                matched.createdTime = now;
            } else if (matched.voidstrikeNumber <= 0 && (side != VOIDSTRIKE_SIDE_ENEMY || isVisibleToPlayer)) {
                if (clusterWingKeys.size() < 2 && clusterFighters.size() < 2) {
                    continue;
                }
                if (clusterFighters.size() >= 3 && spread > effectiveCohesionRadius) {
                    continue;
                }
                matched.voidstrikeNumber = claimSortieNumber(clusterDivs, updatedForSide, existingForSide, sideIdx);
                if (matched.createdTime < 0f) {
                    matched.createdTime = now;
                }
            }

            if (matched.createdTime < 0f) {
                matched.createdTime = now;
            }
            if (spread <= effectiveCohesionRadius) {
                matched.achievedCohesion = true;
            }

            // Track Split-Wing sub-elements and distinct target ships within this single Voidstrike entity
            Set<String> distinctTargetShipIds = new HashSet<>();
            matched.subWingCenters.clear();
            for (WingStrikeCandidate wc : cluster) {
                if (wc.center != null) {
                    boolean mergedSubCenter = false;
                    for (Vector2f sc : matched.subWingCenters) {
                        if (DivisionUiWidgets.distance(sc, wc.center) <= 680f) {
                            sc.set((sc.x + wc.center.x) * 0.5f, (sc.y + wc.center.y) * 0.5f);
                            mergedSubCenter = true;
                            break;
                        }
                    }
                    if (!mergedSubCenter) {
                        matched.subWingCenters.add(new Vector2f(wc.center));
                    }
                }
                for (ShipAPI cf : wc.activeFighters) {
                    if (cf != null && cf.getShipTarget() != null && cf.getShipTarget().getId() != null) {
                        distinctTargetShipIds.add(cf.getShipTarget().getId());
                    }
                }
            }
            matched.distinctTargetCount = Math.max(1, distinctTargetShipIds.size());
            int maxDoctrinalSubWings = 1;
            for (DivisionState cd : clusterDivs) {
                if (cd == null) continue;
                int divAxes = 1;
                if (cd.voidstrikeAttackGeometry == VoidstrikeAttackGeometry.ANVIL_CROSSFIRE
                        || cd.voidstrikeAttackGeometry == VoidstrikeAttackGeometry.PINCER_ENVELOPMENT) {
                    divAxes = 2;
                }
                if (cd.voidstrikeSplitMode == VoidstrikeSplitMode.SPLIT_AXES) {
                    divAxes = Math.max(divAxes, cd.voidstrikeAttackGeometry == VoidstrikeAttackGeometry.DIRECT_ASSAULT ? 3 : 2);
                } else if (cd.voidstrikeSplitMode == VoidstrikeSplitMode.SPLIT_TARGETS) {
                    divAxes = Math.max(divAxes, matched.distinctTargetCount);
                }
                if (divAxes > maxDoctrinalSubWings) {
                    maxDoctrinalSubWings = divAxes;
                }
            }
            if (clusterDivs.size() > 1) {
                maxDoctrinalSubWings = Math.max(maxDoctrinalSubWings, Math.min(4, clusterDivs.size() * 2));
            }
            int rawSubWings = Math.min(maxDoctrinalSubWings, Math.max(1, matched.subWingCenters.size()));
            if (hasSplitWingsOrMultiAxis && cluster.size() >= 2 && maxDoctrinalSubWings >= 2) {
                rawSubWings = Math.max(2, rawSubWings);
            }
            matched.subWingCount = Math.min(cluster.size(), rawSubWings);
            matched.splitWingActive = hasSplitWingsOrMultiAxis && (cluster.size() >= 2);
            DivisionState primaryTargetDiv = null;
            for (WingStrikeCandidate wc : cluster) {
                if (wc.targetDivision != null) {
                    primaryTargetDiv = wc.targetDivision;
                    break;
                }
            }

            boolean anyWingInStagingHold = false;
            boolean anyEmergencyRecall = false;
            for (DivisionState d : clusterDivs) {
                if (d == null) continue;
                if (d.voidstrikeEmergencyRecallActive) {
                    anyEmergencyRecall = true;
                }
                if (d.voidstrikeCoordination == VoidstrikeCoordinationMode.COORDINATED
                        && (d.voidstrikeStage == 0 || d.voidstrikeStage == 1
                        || d.currentIntent == DivisionModel.DivisionIntentType.MARSHAL_FOR_STRIKE)) {
                    anyWingInStagingHold = true;
                }
            }
            if (anyWingInStagingHold && !matched.reachedTarget) {
                matched.createdTime = now;
            }
            if (anyEmergencyRecall) {
                matched.emergencyRecallTriggered = true;
            }

            matched.initialBomberWingKeys.addAll(clusterBomberWingKeys);

            // Evaluate the Maximum Average Effective Massed Strike Package of this Voidstrike upon execution
            // and update live ordnance fraction, effective speed, bomber spread, and package-driven timeouts!
            updateVoidstrikeRecordPackageMetrics(
                    matched,
                    cluster,
                    clusterDivs,
                    clusterFighters,
                    clusterCenter,
                    primaryTargetPos,
                    primaryTargetDiv,
                    now);

            float effectiveArrivalRadius = SORTIE_ARRIVAL_RADIUS
                    + Math.min(280f, Math.max(0f, (matched.packageAvgReleaseRange - 450f) * 0.45f));
            boolean arrivedAtTarget = false;
            if (matched.currentArmedBomberCount > 0) {
                // When the strike package contains armed bombers, arrival over target is driven by the bomber package
                // (not a lone fast interceptor arriving 15s ahead of the bombers!): require at least one armed bomber
                // inside effectiveArrivalRadius AND either >= 35% of armed bombers within 1650u or clusterCenter within 1450u.
                int armedBombersInArrival = 0;
                int armedBombersNear = 0;
                for (WingStrikeCandidate wc : cluster) {
                    if (wc == null || wc.activeFighters == null) continue;
                    for (ShipAPI cf : wc.activeFighters) {
                        if (cf == null || cf.getLocation() == null) continue;
                        boolean isB = wc.isBomberWing
                                || (cf.getWing() != null && ((cf.getWing().getSpec() != null && cf.getWing().getSpec().isBomber())
                                || cf.getWing().getRole() == com.fs.starfarer.api.loading.WingRole.BOMBER))
                                || hasFiniteAmmoStrikeWeapons(cf);
                        if (!isB || !hasUnexpendedStrikeOrdnance(cf, true)) continue;
                        float dTgt = computeMinDistToTargetEntity(cf.getLocation(), primaryTargetPos, primaryTargetDiv, cf.getShipTarget());
                        if (dTgt <= effectiveArrivalRadius) {
                            armedBombersInArrival++;
                        }
                        if (dTgt <= 1650f) {
                            armedBombersNear++;
                        }
                    }
                }
                float centerDist = computeMinDistToTargetEntity(clusterCenter, primaryTargetPos, primaryTargetDiv, null);
                arrivedAtTarget = armedBombersInArrival >= 1
                        && (armedBombersNear >= Math.max(1, Math.round(matched.currentArmedBomberCount * 0.35f))
                        || centerDist <= 1450f);
            } else {
                float centerDist = computeMinDistToTargetEntity(clusterCenter, primaryTargetPos, primaryTargetDiv, null);
                arrivedAtTarget = centerDist <= effectiveArrivalRadius;
                if (!arrivedAtTarget && !clusterFighters.isEmpty()) {
                    int nearCount = 0;
                    for (ShipAPI cf : clusterFighters) {
                        if (cf == null || cf.getLocation() == null) continue;
                        float dTgt = computeMinDistToTargetEntity(cf.getLocation(), primaryTargetPos, primaryTargetDiv, cf.getShipTarget());
                        if (dTgt <= effectiveArrivalRadius) {
                            nearCount++;
                        }
                    }
                    arrivedAtTarget = nearCount >= Math.max(1, Math.round(clusterFighters.size() * 0.35f));
                }
            }
            if (arrivedAtTarget) {
                if (!matched.reachedTarget) {
                    matched.reachedTarget = true;
                    matched.reachedTargetTime = now;
                    DivisionState leadDiv = !clusterDivs.isEmpty() ? clusterDivs.iterator().next() : null;
                    if (leadDiv != null && matched.voidstrikeNumber > 0) {
                        StringBuilder geomSb = new StringBuilder();
                        for (DivisionState cd : clusterDivs) {
                            if (cd == null || cd.voidstrikeAttackGeometry == null) continue;
                            String gName = cd.voidstrikeAttackGeometry.displayName;
                            if (geomSb.indexOf(gName) < 0) {
                                if (geomSb.length() > 0) geomSb.append("+");
                                geomSb.append(gName);
                            }
                        }
                        String geomStr = geomSb.length() > 0 ? geomSb.toString() : "Direct";
                        VoidstrikeSplitMode leadSplit = leadDiv.voidstrikeSplitMode != null
                                ? leadDiv.voidstrikeSplitMode
                                : VoidstrikeSplitMode.UNIFIED;
                        Global.getLogger(VoidstrikeCoordinator.class).info(String.format(
                                Locale.ROOT,
                                "[DivisionAI] [Voidstrike Gunnery] %s Voidstrike %d terminal attack run on %s: Geometry=%s, Split=%s (subWings=%d, targets=%d), Gunnery=%s (span=%.0fu), AoA=%s, squadrons=%d, craft=%d, pkgMass=%.1f, effSpd=%.0f, passBudget=%.0fs",
                                side == VOIDSTRIKE_SIDE_ENEMY ? "Enemy" : (side == VOIDSTRIKE_SIDE_ALLIED ? "Allied" : "Player"),
                                matched.voidstrikeNumber,
                                primaryTargetDiv != null ? ("\"" + primaryTargetDiv.displayName + "\"") : primaryTargetKey,
                                geomStr,
                                leadSplit.displayName,
                                matched.subWingCount,
                                matched.distinctTargetCount,
                                leadDiv.voidstrikeGunneryMode != null ? leadDiv.voidstrikeGunneryMode.displayName : "Precision",
                                leadDiv.lastVoidstrikeSpanWidth > 0f ? leadDiv.lastVoidstrikeSpanWidth : 180f,
                                leadDiv.voidstrikeAoaCorrection != null ? leadDiv.voidstrikeAoaCorrection.displayName : "Strong",
                                clusterWingKeys.size(),
                                clusterFighters.size(),
                                matched.maxEffectivePackageMass,
                                matched.effectivePackageSpeed,
                                matched.packageStrikePassTimeoutSec));
                    }
                }
            }

            // Package-Driven Voidstrike Culmination:
            //  1. Emergency recall: carrier/division leader/OTC ordered an emergency pullback.
            //  2. Ordnance expended: all armed bombers in the executed strike package have delivered their ordnance
            //     (or >= 92% of the package's bomber ordnance mass has been expended).
            //  3. Strike pass complete: package-driven pass window (packageStrikePassTimeoutSec) elapsed after arrival,
            //     extended automatically if armed bombers are still inside 1850u actively lining up their drop!
            //  4. Approach timeout: package-driven transit budget (packageApproachTimeoutSec) elapsed without arrival
            //     AND the strike package is no longer closing on the target.
            //  5. Target escaped: target opened beyond max(TARGET_ESCAPE_DISTANCE, 1.35 * executionDistanceToTarget)
            //     and is outrunning the strike package.
            if (clusterFighters.size() > matched.peakFighterCount) {
                matched.peakFighterCount = clusterFighters.size();
            }
            float sortieAge = now - matched.createdTime;
            boolean hadBombersInPackage = !matched.initialBomberWingKeys.isEmpty() || matched.initialArmedBomberCount > 0;
            boolean bombersDeliveredAndReturning = hadBombersInPackage
                    && (matched.currentArmedBomberCount == 0
                    || (!matched.initialBomberWingKeys.isEmpty()
                    && !containsAny(clusterBomberWingKeys, matched.initialBomberWingKeys)
                    && returningWingKeys != null
                    && returningWingKeys.containsAll(matched.initialBomberWingKeys))
                    || (matched.reachedTarget
                    && (now - matched.reachedTargetTime) >= 3.0f
                    && matched.maxEffectivePackageMass > 0f
                    && matched.currentArmedPackageMass <= matched.maxEffectivePackageMass * 0.08f));

            boolean armedBombersPressingInTerminal = false;
            if (matched.currentArmedBomberCount > 0) {
                for (WingStrikeCandidate wc : cluster) {
                    if (wc == null || wc.activeFighters == null) continue;
                    for (ShipAPI cf : wc.activeFighters) {
                        if (cf == null || cf.getLocation() == null) continue;
                        boolean isB = wc.isBomberWing
                                || (cf.getWing() != null && ((cf.getWing().getSpec() != null && cf.getWing().getSpec().isBomber())
                                || cf.getWing().getRole() == com.fs.starfarer.api.loading.WingRole.BOMBER))
                                || hasFiniteAmmoStrikeWeapons(cf);
                        if (isB && hasUnexpendedStrikeOrdnance(cf, true)) {
                            float dTgt = computeMinDistToTargetEntity(cf.getLocation(), primaryTargetPos, primaryTargetDiv, cf.getShipTarget());
                            if (dTgt <= 1850f) {
                                armedBombersPressingInTerminal = true;
                                break;
                            }
                        }
                    }
                    if (armedBombersPressingInTerminal) break;
                }
            }

            float effectivePassLimitSec = armedBombersPressingInTerminal
                    ? Math.min(58.0f, matched.packageStrikePassTimeoutSec * 1.25f)
                    : matched.packageStrikePassTimeoutSec;
            boolean strikePassComplete = matched.reachedTarget
                    && matched.reachedTargetTime >= 0f
                    && (now - matched.reachedTargetTime) > effectivePassLimitSec;

            boolean activelyClosingOnTarget = (now - matched.lastClosingProgressTime) <= 8.0f
                    || armedBombersPressingInTerminal;
            boolean approachTimedOut = !matched.reachedTarget
                    && sortieAge > matched.packageApproachTimeoutSec
                    && !activelyClosingOnTarget;
            boolean maxSortieDurationExpired = sortieAge > Math.max(MAX_SORTIE_DURATION_SECONDS,
                    matched.packageApproachTimeoutSec + matched.packageStrikePassTimeoutSec * 1.6f);

            boolean targetEscaped = false;
            if (primaryTargetPos != null
                    && matched.reachedTarget
                    && matched.reachedTargetTime >= 0f
                    && (now - matched.reachedTargetTime) >= 8.0f
                    && !activelyClosingOnTarget) {
                float minDistToTarget = computeMinDistToTargetEntity(clusterCenter, primaryTargetPos, primaryTargetDiv, null);
                float escapeThreshold = Math.max(TARGET_ESCAPE_DISTANCE, matched.executionDistanceToTarget * 1.35f);
                targetEscaped = minDistToTarget > escapeThreshold;
            }

            boolean canCulminateThisFrame = (now - matched.createdTime) >= 3.0f;
            boolean lostCohesion = canCulminateThisFrame
                    && matched.achievedCohesion
                    && matched.currentArmedBomberCount == 0
                    && clusterDivs.size() <= 1
                    && clusterWingKeys.size() <= 1
                    && clusterFighters.size() >= 3
                    && spread > effectiveCohesionRadius * 1.35f;
            // Only count actual combat attrition when armed bombers are NOT still delivering their payload in terminal range,
            // OR when an inbound wave (!matched.reachedTarget) has lost >= 55% of its peak strike craft to AA fire!
            boolean heavyPreTerminalAaAttrition = !matched.reachedTarget
                    && !armedBombersPressingInTerminal
                    && matched.peakFighterCount >= 6
                    && clusterFighters.size() <= Math.floor(matched.peakFighterCount * 0.45f);
            boolean lostAdvantage = canCulminateThisFrame
                    && (heavyPreTerminalAaAttrition
                    || (matched.currentArmedBomberCount == 0
                    && matched.peakFighterCount >= 4
                    && clusterFighters.size() < Math.ceil(matched.peakFighterCount * SORTIE_ATTRITION_FRACTION)));
            String culminationReason = null;
            if (bombersDeliveredAndReturning) culminationReason = "ordnance expended";
            else if (anyEmergencyRecall) culminationReason = "emergency recall";
            else if (strikePassComplete) culminationReason = "strike pass complete";
            else if (approachTimedOut) culminationReason = "approach timed out";
            else if (maxSortieDurationExpired) culminationReason = "sortie time limit";
            else if (targetEscaped) culminationReason = "target escaped";
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
        // while any Voidstrike whose strike craft all returned to carrier after expending their ordnance logs
        // its "ordnance expended" (or "emergency recall") culmination cleanly and frees its number!
        for (int eIdx = 0; eIdx < existingForSide.size(); eIdx++) {
            if (!existingUsed[eIdx]) {
                VoidstrikeRecord leftover = existingForSide.get(eIdx);
                boolean allWingsReturned = !leftover.participatingWingKeys.isEmpty()
                        && returningWingKeys != null
                        && returningWingKeys.containsAll(leftover.participatingWingKeys);
                if (!allWingsReturned && leftover.voidstrikeNumber > 0 && (now - leftover.lastActiveTime) <= 1.25f) {
                    recentVoidstrikeHistory.add(leftover);
                } else if (leftover.voidstrikeNumber > 0 && (allWingsReturned || leftover.reachedTarget || leftover.emergencyRecallTriggered)) {
                    if (leftover.culminationReason == null) {
                        String reason;
                        if (leftover.emergencyRecallTriggered) {
                            reason = "emergency recall";
                        } else if (allWingsReturned) {
                            reason = (!leftover.initialBomberWingKeys.isEmpty() || leftover.initialArmedBomberCount > 0 || leftover.reachedTarget)
                                    ? "ordnance expended"
                                    : "strike pass complete";
                        } else {
                            reason = "lost local advantage";
                        }
                        leftover.culminationReason = reason;
                        DivisionCombatLog.logVoidstrikeCulminated(side, leftover.voidstrikeNumber, reason, now);
                    }
                    releaseSortieNumber(leftover);
                }
            }
        }

        activeVoidstrikes.addAll(updatedForSide);
    }

    static float computeMinDistToTargetEntity(
            Vector2f fromLoc,
            Vector2f primaryTargetPos,
            DivisionState primaryTargetDiv,
            ShipAPI specificTargetShip) {
        if (fromLoc == null) return Float.MAX_VALUE;
        float minDist = (primaryTargetPos != null)
                ? DivisionUiWidgets.distance(fromLoc, primaryTargetPos)
                : Float.MAX_VALUE;
        if (specificTargetShip != null && specificTargetShip.isAlive() && !specificTargetShip.isHulk()
                && !specificTargetShip.isFighter() && specificTargetShip.getLocation() != null) {
            float d = DivisionUiWidgets.distance(fromLoc, specificTargetShip.getLocation());
            if (d < minDist) minDist = d;
        }
        if (primaryTargetDiv != null && primaryTargetDiv.members != null) {
            for (ShipAPI em : primaryTargetDiv.members) {
                if (em != null && em.isAlive() && !em.isHulk() && em.getLocation() != null) {
                    float d = DivisionUiWidgets.distance(fromLoc, em.getLocation());
                    if (d < minDist) minDist = d;
                }
            }
        }
        return minDist;
    }

    static void updateVoidstrikeRecordPackageMetrics(
            VoidstrikeRecord rec,
            List<WingStrikeCandidate> cluster,
            Set<DivisionState> clusterDivs,
            List<ShipAPI> clusterFighters,
            Vector2f clusterCenter,
            Vector2f primaryTargetPos,
            DivisionState primaryTargetDiv,
            float now) {
        if (rec == null) return;
        float totalMass = 0f;
        float weightedSpeedSum = 0f;
        float weightedRangeSum = 0f;
        float bomberMassSum = 0f;
        float bomberWeightedSpeedSum = 0f;
        float slowestBomberSpd = Float.MAX_VALUE;
        int armedBomberCount = 0;
        float minBomberDistToTarget = Float.MAX_VALUE;
        float maxBomberDistToTarget = 0f;

        for (WingStrikeCandidate wc : cluster) {
            if (wc == null || wc.activeFighters == null) continue;
            for (ShipAPI f : wc.activeFighters) {
                if (f == null || !f.isAlive() || f.isHulk()) continue;
                boolean isBomber = wc.isBomberWing
                        || (f.getWing() != null && ((f.getWing().getSpec() != null && f.getWing().getSpec().isBomber())
                        || f.getWing().getRole() == com.fs.starfarer.api.loading.WingRole.BOMBER))
                        || hasFiniteAmmoStrikeWeapons(f);
                float ordFrac = computeStrikeOrdnanceFraction(f);
                float hullFactor = 0.5f + 0.5f * Math.max(0.2f, f.getHullLevel());
                float craftMass;
                if (isBomber) {
                    if (ordFrac <= 0f) continue;
                    craftMass = 2.5f * ordFrac * hullFactor;
                    armedBomberCount++;
                    float bSpd = Math.max(85f, f.getMaxSpeed());
                    bomberMassSum += craftMass;
                    bomberWeightedSpeedSum += craftMass * bSpd;
                    if (bSpd < slowestBomberSpd) slowestBomberSpd = bSpd;
                    float dTgt = computeMinDistToTargetEntity(f.getLocation(), primaryTargetPos, primaryTargetDiv, f.getShipTarget());
                    if (dTgt < minBomberDistToTarget) minBomberDistToTarget = dTgt;
                    if (dTgt < 25000f && dTgt > maxBomberDistToTarget) maxBomberDistToTarget = dTgt;
                } else {
                    craftMass = (rec.initialArmedBomberCount > 0 ? 0.45f : 1.0f) * hullFactor;
                }
                float fSpd = Math.max(85f, f.getMaxSpeed());
                float fRng = computePrimaryStrikeWeaponRange(f);
                totalMass += craftMass;
                weightedSpeedSum += craftMass * fSpd;
                weightedRangeSum += craftMass * fRng;
            }
        }

        rec.currentArmedPackageMass = totalMass;
        rec.currentArmedBomberCount = armedBomberCount;
        if (totalMass > rec.maxEffectivePackageMass) {
            rec.maxEffectivePackageMass = totalMass;
        }
        if (armedBomberCount > rec.initialArmedBomberCount) {
            rec.initialArmedBomberCount = armedBomberCount;
        }

        if (totalMass > 0f) {
            float pkgAvgSpeed = weightedSpeedSum / totalMass;
            float bomberAvgSpeed = (bomberMassSum > 0f) ? (bomberWeightedSpeedSum / bomberMassSum) : pkgAvgSpeed;
            float effSpeed = (bomberMassSum > 0f)
                    ? (0.70f * bomberAvgSpeed + 0.30f * pkgAvgSpeed)
                    : pkgAvgSpeed;
            rec.effectivePackageSpeed = Math.max(85f, Math.min(320f, effSpeed));
            rec.slowestBomberSpeed = (slowestBomberSpd < Float.MAX_VALUE)
                    ? Math.max(80f, slowestBomberSpd)
                    : rec.effectivePackageSpeed;
            rec.packageAvgReleaseRange = weightedRangeSum / totalMass;
        }

        float currentDistToTarget = (minBomberDistToTarget < Float.MAX_VALUE)
                ? minBomberDistToTarget
                : computeMinDistToTargetEntity(clusterCenter, primaryTargetPos, primaryTargetDiv, null);
        if (currentDistToTarget < Float.MAX_VALUE) {
            if (rec.executionDistanceToTarget <= 1f || (!rec.reachedTarget && (now - rec.createdTime) <= 4.0f)) {
                float maxCarrierDist = currentDistToTarget;
                for (DivisionState d : clusterDivs) {
                    if (d != null && primaryTargetPos != null) {
                        float dDiv = DivisionUiWidgets.distance(d.centroid, primaryTargetPos);
                        if (dDiv > maxCarrierDist) maxCarrierDist = dDiv;
                    }
                }
                rec.executionDistanceToTarget = Math.max(rec.executionDistanceToTarget, maxCarrierDist);
            }
            if (currentDistToTarget < rec.minDistToTargetSeen - 20f
                    || (armedBomberCount > 0 && currentDistToTarget <= 2000f)) {
                rec.minDistToTargetSeen = Math.min(rec.minDistToTargetSeen, currentDistToTarget);
                rec.lastClosingProgressTime = now;
            } else if (rec.lastClosingProgressTime < 0f) {
                rec.minDistToTargetSeen = currentDistToTarget;
                rec.lastClosingProgressTime = now;
            }
        }

        float geomExtra = 220f;
        int maxDepth = 1;
        for (DivisionState d : clusterDivs) {
            if (d == null) continue;
            if (d.voidstrikeAttackGeometry == VoidstrikeAttackGeometry.ANVIL_CROSSFIRE) {
                geomExtra = Math.max(geomExtra, 1250f);
            } else if (d.voidstrikeAttackGeometry == VoidstrikeAttackGeometry.PINCER_ENVELOPMENT) {
                geomExtra = Math.max(geomExtra, 750f);
            }
            int[] comp = computeCompromiseStrikeWidthAndDepth(
                    Math.max(1, cluster.size()),
                    Math.max(1, d.voidstrikeSquadronWidth),
                    Math.max(1, d.voidstrikeSquadronDepth));
            if (comp[1] > maxDepth) maxDepth = comp[1];
        }
        float rankDepthSpan = Math.max(0, maxDepth - 1) * 120f;
        float splitSpread = Math.max(0, rec.subWingCount - 1) * 200f
                + Math.max(0, rec.distinctTargetCount - 1) * 320f;
        float baseExecDist = Math.max(rec.executionDistanceToTarget,
                currentDistToTarget < Float.MAX_VALUE ? currentDistToTarget : 2000f);
        rec.packageRouteDistance = Math.max(0f, baseExecDist - rec.packageAvgReleaseRange * 0.5f)
                + geomExtra + rankDepthSpan + splitSpread;

        float netClosingSpeed = Math.max(70f, rec.effectivePackageSpeed);
        float rawApproachSec = (rec.packageRouteDistance / netClosingSpeed) * 1.50f + 16.0f;
        rec.packageApproachTimeoutSec = Math.max(APPROACH_TIMEOUT_SECONDS, Math.min(220.0f, rawApproachSec));

        float bomberSpreadSpan = (armedBomberCount >= 2 && maxBomberDistToTarget > minBomberDistToTarget)
                ? Math.min(2600f, maxBomberDistToTarget - minBomberDistToTarget)
                : 0f;
        float rawPassSec = 22.0f
                + ((bomberSpreadSpan + geomExtra * 0.65f + rankDepthSpan + splitSpread) / Math.max(85f, rec.effectivePackageSpeed))
                + 3.5f * rec.subWingCount;
        rec.packageStrikePassTimeoutSec = Math.max(STRIKE_PASS_CULMINATION_SECONDS, Math.min(48.0f, rawPassSec));

        for (DivisionState d : clusterDivs) {
            if (d != null) {
                d.lastVoidstrikePackageMass = Math.max(d.lastVoidstrikePackageMass, rec.maxEffectivePackageMass);
                d.lastVoidstrikeEffectiveSpeed = rec.effectivePackageSpeed;
                d.lastVoidstrikeApproachTimeoutSec = rec.packageApproachTimeoutSec;
                d.lastVoidstrikePassTimeoutSec = rec.packageStrikePassTimeoutSec;
            }
        }
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
        return claimSortieNumber(clusterDivs, liveThisTick, null, sideIdx);
    }

    static int claimSortieNumber(
            Set<DivisionState> clusterDivs,
            List<VoidstrikeRecord> liveThisTick,
            List<VoidstrikeRecord> existingForSide,
            int sideIdx) {
        int targetSide = sideIdx == 2 ? VOIDSTRIKE_SIDE_ALLIED : (sideIdx == 1 ? VOIDSTRIKE_SIDE_ENEMY : VOIDSTRIKE_SIDE_PLAYER);
        if (clusterDivs != null) {
            for (DivisionState d : clusterDivs) {
                if (d == null || d.activeVoidstrikeSortieNumber <= 0 || d.voidstrikeEgressActive) continue;
                int cand = d.activeVoidstrikeSortieNumber;
                boolean taken = false;
                if (liveThisTick != null) {
                    for (VoidstrikeRecord r : liveThisTick) {
                        if (r != null && r.voidstrikeNumber == cand) { taken = true; break; }
                    }
                }
                if (!taken && existingForSide != null) {
                    for (VoidstrikeRecord r : existingForSide) {
                        if (r != null && r.voidstrikeNumber == cand) { taken = true; break; }
                    }
                }
                if (!taken) {
                    for (VoidstrikeRecord r : activeVoidstrikes) {
                        if (r != null && r.voidstrikeNumber == cand && r.side == targetSide) {
                            taken = true;
                            break;
                        }
                    }
                }
                if (!taken) {
                    for (VoidstrikeRecord r : recentVoidstrikeHistory) {
                        if (r != null && r.voidstrikeNumber == cand && r.side == targetSide) {
                            taken = true;
                            break;
                        }
                    }
                }
                if (!taken) return cand;
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
                if (d != null && d.activeVoidstrikeSortieNumber == rec.voidstrikeNumber) {
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
