package divisionai;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.DivisionIntentType;
import divisionai.DivisionModel.DivisionState;
import divisionai.PermanentDivisionManager.DivisionRammingMode;
import divisionai.ShipSystemCoordinator.ShipSkillCategory;
import divisionai.ShipSystemCoordinator.ShipSkillProfile;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;
import java.util.WeakHashMap;

/**
 * Ship-Level Tactical C2 & Helmsmanship Governor ("Ship Captain").
 *
 * <p>Completes the 5-tier naval C2 hierarchy:
 * <pre>
 *   Player (Admiral) -> OTC (Fleet Command) -> Division Leader -> Ship Captain (ShipCaptainAI) -> Basic Ship AI
 * </pre>
 *
 * <p>In Starsector's {@code CombatEngine.advance(dt)}, each ship executes {@code ship.getAI().advance(dt)}
 * immediately followed on the next line by {@code ship.advance(dt)} (which drains {@code ship.commands},
 * strips {@code ship.blockedCommands}, and runs {@code engineController.processCommands}). By wrapping the
 * underlying {@link ShipAIPlugin} ({@code BasicShipAI} or a third-party ship AI such as {@code AI Tweaks}),
 * {@code ShipCaptainAI} runs <b>in the exact window between {@code BasicShipAI.advance(dt)} and
 * {@code Ship.advance(dt)}</b>, giving the Ship Captain same-frame veto and helm authority ("taking the conn")
 * over suicidal {@code BasicShipAI} impulses:
 * <ol>
 *   <li><b>Closed-Loop Newtonian Objective Capture & Waypoint Braking</b>: Replaces vanilla {@code ooOo}'s
 *       hardcoded {@code 500u} braking gate with true stopping kinematics ({@code v_in^2 / (2 * decel)})
 *       so fast frigates bleed speed smoothly onto Control Points and settle inside the capture ring on the
 *       first pass instead of overshooting by 800-1,200u in pendulum loops.</li>
 *   <li><b>Mobility System Overshoot Veto & Post-Burn Damping</b>: Vetoes forward burst/burn {@code USE_SYSTEM}
 *       commands that would catapult a hull past an uncaptured objective or through its own battleline into
 *       enemy crossfire, and actively damps residual overspeed ({@code v > maxSpeed}) when overhauls disable
 *       vanilla post-system slowdown.</li>
 *   <li><b>Physics-Scaled Vent & Disengagement Discipline</b>: Vetoes suicidal open-space {@code VENT_FLUX}
 *       inside extended enemy weapon envelopes (where vanilla's hardcoded {@code 2,200u} threat horizon thinks
 *       3,000u is safe) and initiates shielded {@code BACK_OFF} before compressed-speed pursuit traps high-flux
 *       hulls.</li>
 * </ol>
 *
 * <p>All trig and angular checks use {@link CombatPhysicsProfile#fastCosDeg(float)} /
 * {@link CombatPhysicsProfile#fastSinDeg(float)} and vector dot/cross products (zero {@code Math.sin/cos/atan2/pow}
 * {@code CallLeafNoFP} nodes in 60 Hz paths, zero reflection, full {@code 0.98a-RC8} and {@code 0.98.5a} binary
 * compatibility).
 */
public final class ShipCaptainAI implements ShipAIPlugin {

    private static CombatEngineAPI activeEngine = null;
    private static BattleGroupState activePlayerGroup = null;
    private static BattleGroupState activeAlliedGroup = null;
    private static BattleGroupState activeEnemyGroup = null;
    private static final float[] SALVO_SCRATCH = new float[5];

    // Tracks installed ShipCaptainAI per ShipAPI.
    // CRITICAL: In Starsector's `Ship.setShipAI(ShipAIPlugin)`, non-internal ShipAIPlugins are wrapped inside a
    // `Ship$ShipAIWrapper` (which implements internal `AI` + `ShipAIPlugin`), so `ship.getShipAI()` returns that
    // `Ship$ShipAIWrapper` instance rather than `this`! Caching `installedOuterWrapper` (1) prevents re-wrapping
    // on subsequent frames (which otherwise stacks +1 nested wrapper layer per frame!), and (2) lets `advance(dt)`
    // restore `installedOuterWrapper` directly via `Ship.setShipAI(AI)` with zero per-frame object allocations!
    private static final WeakHashMap<ShipAPI, ShipCaptainAI> INSTALLED_CAPTAINS = new WeakHashMap<>();

    // Tracks last elapsed combat timestamp at which Captain's Conn ran on a ship so EveryFrame fallback
    // (for unwrapped ships or headless SimArena steps) never double-applies in the same frame.
    private static final WeakHashMap<ShipAPI, Float> LAST_CONN_FRAME_BY_SHIP = new WeakHashMap<>();

    private final ShipAPI ship;
    private ShipAIPlugin delegate;
    private ShipAIPlugin installedOuterWrapper;
    private boolean inAdvance = false;

    public ShipCaptainAI(ShipAPI ship, ShipAIPlugin delegate) {
        this.ship = ship;
        this.delegate = unwrapDelegate(delegate);
    }

    private static ShipAIPlugin unwrapDelegate(ShipAIPlugin ai) {
        ShipAIPlugin curr = ai;
        while (curr instanceof ShipCaptainAI) {
            curr = ((ShipCaptainAI) curr).delegate;
        }
        return curr;
    }

    public ShipAIPlugin getInnerDelegate() {
        return delegate;
    }

    static ShipCaptainAI getInstalledCaptain(ShipAPI ship) {
        return INSTALLED_CAPTAINS.get(ship);
    }

    public static void resetForNewBattle() {
        activeEngine = null;
        activePlayerGroup = null;
        activeAlliedGroup = null;
        activeEnemyGroup = null;
        INSTALLED_CAPTAINS.clear();
        LAST_CONN_FRAME_BY_SHIP.clear();
    }

    /**
     * Updates active battle-group context and ensures all eligible AI-controlled division warships have
     * {@link ShipCaptainAI} installed around their underlying {@link ShipAIPlugin}.
     *
     * <p>Respects mod rule #2: if the player fleet has 0 active divisions, player ships are never wrapped.
     * Never wraps fighters, stations, station modules, hulks, or a manually piloted player flagship.
     */
    public static void syncBattleContextAndInstall(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            BattleGroupState alliedGroup,
            BattleGroupState enemyGroup) {
        activeEngine = engine;
        activePlayerGroup = playerGroup;
        activeAlliedGroup = alliedGroup;
        activeEnemyGroup = enemyGroup;
        if (engine == null || BattlefieldFirepowerEvaluator.isSimulationEngine(engine)) {
            return;
        }
        installForBattleGroup(engine, playerGroup);
        installForBattleGroup(engine, alliedGroup);
        installForBattleGroup(engine, enemyGroup);
    }

    private static void installForBattleGroup(CombatEngineAPI engine, BattleGroupState group) {
        if (group == null || group.divisions == null || group.divisions.isEmpty()) {
            return;
        }
        List<DivisionState> divs = group.divisions;
        for (int dIdx = 0, dLen = divs.size(); dIdx < dLen; dIdx++) {
            DivisionState div = divs.get(dIdx);
            if (div == null || div.members == null) continue;
            for (int mIdx = 0, mLen = div.members.size(); mIdx < mLen; mIdx++) {
                ShipAPI s = div.members.get(mIdx);
                ensureWrappedIfEligible(engine, s);
            }
        }
    }

    static void ensureWrappedIfEligible(CombatEngineAPI engine, ShipAPI s) {
        if (s == null || !s.isAlive() || s.isHulk() || s.isFighter() || s.isStation() || s.isStationModule()) {
            return;
        }
        if (OtcAI.isPlayerManuallyPiloting(engine, s)) {
            return;
        }
        ShipAIPlugin currentAi = s.getShipAI();
        if (currentAi == null || currentAi instanceof ShipCaptainAI) {
            return;
        }
        ShipCaptainAI existing = INSTALLED_CAPTAINS.get(s);
        if (existing != null) {
            if (currentAi == existing.installedOuterWrapper || currentAi == existing.delegate) {
                if (currentAi == existing.delegate) {
                    // Restore existing wrapper without allocating a new ShipCaptainAI or Ship$ShipAIWrapper
                    s.setShipAI(existing.installedOuterWrapper != null ? existing.installedOuterWrapper : existing);
                    existing.installedOuterWrapper = s.getShipAI();
                }
                return;
            }
            // Genuine AI replacement (e.g. autopilot toggled back on or third-party AI swap): reuse single instance
            existing.delegate = unwrapDelegate(currentAi);
            s.setShipAI(existing.installedOuterWrapper != null ? existing.installedOuterWrapper : existing);
            existing.installedOuterWrapper = s.getShipAI();
            return;
        }
        ShipCaptainAI captain = new ShipCaptainAI(s, currentAi);
        s.setShipAI(captain);
        captain.installedOuterWrapper = s.getShipAI();
        INSTALLED_CAPTAINS.put(s, captain);
    }

    // =========================================================================
    // ShipAIPlugin DELEGATION & SAME-FRAME CAPTAIN'S CONN
    // =========================================================================

    @Override
    public void advance(float dt) {
        if (ship == null || ship.isHulk() || !ship.isAlive() || delegate == null) {
            return;
        }
        if (inAdvance) {
            // Hard re-entrancy guard: mathematically prevents nested recursion under any wrapper topology
            delegate.advance(dt);
            return;
        }
        CombatEngineAPI engine = activeEngine;
        if (engine != null && OtcAI.isPlayerManuallyPiloting(engine, ship)) {
            ship.setShipAI(null);
            return;
        }

        inAdvance = true;
        try {
            DivisionState div = resolveShipDivision(ship);
            BattleGroupState enemyGroup = resolveOpposingBattleGroup(ship);

            // 1. Pre-AI Captaincy Posture (ShipAIConfig tuning before BasicShipAI evaluates maneuvers)
            if (div != null) {
                applyPreAdvanceCaptainConfig(ship, delegate.getConfig(), div);
            }

            // 2. Delegate to underlying ShipAIPlugin (BasicShipAI or third-party AI such as AI Tweaks).
            // Temporarily expose `delegate` on `ship.getShipAI()` so BasicShipAI's `this.ship.getAI() == this`
            // invariant and vanilla maneuvers (`ooOo` `instanceof BasicShipAI`) execute with 100% fidelity,
            // then restore `installedOuterWrapper` (`Ship$ShipAIWrapper implements AI`, zero allocation!).
            ship.setShipAI(delegate);
            try {
                delegate.advance(dt);
            } finally {
                ShipAIPlugin afterAi = ship.getShipAI();
                if (afterAi == delegate) {
                    ship.setShipAI(installedOuterWrapper != null ? installedOuterWrapper : this);
                    if (installedOuterWrapper == null) {
                        installedOuterWrapper = ship.getShipAI();
                    }
                } else if (afterAi != null && afterAi != installedOuterWrapper && !(afterAi instanceof ShipCaptainAI)) {
                    this.delegate = unwrapDelegate(afterAi);
                    ship.setShipAI(installedOuterWrapper != null ? installedOuterWrapper : this);
                    this.installedOuterWrapper = ship.getShipAI();
                }
            }

            // 3. Post-AI Captain's Conn (runs immediately BEFORE Ship.advance drains ship.commands!)
            if (engine != null && div != null && dt > 0f) {
                float now = engine.getTotalElapsedTime(false);
                LAST_CONN_FRAME_BY_SHIP.put(ship, Float.valueOf(now));
                applyCaptainConnPostAdvance(engine, ship, div, enemyGroup);
            }
        } finally {
            inAdvance = false;
        }
    }

    @Override
    public void setDoNotFireDelay(float amount) {
        if (delegate != null) delegate.setDoNotFireDelay(amount);
    }

    @Override
    public void forceCircumstanceEvaluation() {
        if (delegate != null) delegate.forceCircumstanceEvaluation();
    }

    @Override
    public boolean needsRefit() {
        return delegate != null && delegate.needsRefit();
    }

    @Override
    public ShipwideAIFlags getAIFlags() {
        return delegate != null ? delegate.getAIFlags() : ship.getAIFlags();
    }

    @Override
    public void cancelCurrentManeuver() {
        if (delegate != null) delegate.cancelCurrentManeuver();
    }

    @Override
    public ShipAIConfig getConfig() {
        return delegate != null ? delegate.getConfig() : null;
    }

    @Override
    public void setTargetOverride(ShipAPI target) {
        if (delegate != null) delegate.setTargetOverride(target);
    }

    // =========================================================================
    // CAPTAIN'S CONN DISCIPLINES (Live Wrapper + Direct Unit-Test Entry Point)
    // =========================================================================

    static boolean didCaptainConnRunThisFrame(ShipAPI ship, float now) {
        Float stamp = LAST_CONN_FRAME_BY_SHIP.get(ship);
        return stamp != null && now > 0f && Math.abs(stamp.floatValue() - now) < 1e-4f;
    }

    private static void applyPreAdvanceCaptainConfig(ShipAPI ship, ShipAIConfig cfg, DivisionState div) {
        if (cfg == null || div == null) {
            return;
        }
        boolean allOutCharge = div.currentIntent == DivisionIntentType.PRESS_HOME
                || div.currentIntent == DivisionIntentType.RUN_THE_GAUNTLET;
        // Prevent vanilla BurnDriveAI from blindly burning into enemy gunlines unless ordered to charge/breakout
        cfg.burnDriveIgnoreEnemies = allOutCharge;
        if (!allOutCharge && (CombatPhysicsProfile.getWeaponRangeScale(activeEngine) > 1.30f
                || CombatPhysicsProfile.hasExtremeInertia(ship))) {
            cfg.backingOffWhileNotVentingAllowed = true;
        }
    }

    /**
     * Executes all post-{@code BasicShipAI} Ship Captain disciplines on {@code ship}.
     * Package-private so headless tests can invoke and verify exact command vetoes and braking directly.
     */
    public static void applyCaptainConnPostAdvance(
            CombatEngineAPI engine,
            ShipAPI ship,
            DivisionState div,
            BattleGroupState enemyGroup) {
        if (engine == null || ship == null || !ship.isAlive() || ship.isHulk() || div == null) {
            return;
        }
        if (ship.getTravelDrive() != null && ship.getTravelDrive().isActive()) {
            return;
        }
        ShipwideAIFlags flags = ship.getAIFlags();
        ShipSkillProfile skillProf = ShipSystemCoordinator.getSkillProfile(ship);
        DivisionRammingMode ramMode = (div.rammingMode != null) ? div.rammingMode : DivisionRammingMode.OFF;

        // 1. Objective Capture & Waypoint Newtonian Braking Discipline
        boolean handledByObjectiveHelm = tryApplyObjectiveCaptureConn(engine, ship, div, flags);
        if (!handledByObjectiveHelm) {
            applyStationSlotNewtonianBraking(ship, div, flags, skillProf, ramMode);
        }

        // 2. Mobility System Overshoot Veto & Post-Burn Overspeed Damping
        applyMobilitySystemDiscipline(engine, ship, div, enemyGroup, flags, skillProf, ramMode, handledByObjectiveHelm);

        // 3. Range-Scaled Anti-Suicide Vent Veto & Early Shielded Disengagement
        applySurvivalAndVentDiscipline(engine, ship, div, enemyGroup, flags, skillProf, ramMode);

        // 4. Active Incoming-Salvo Defense (4x-Speed Torpedo Vent Lockout, Closing-Speed Brake & Bulwark Reflex)
        applyIncomingOrdnanceDefense(engine, ship, div, flags, skillProf, ramMode);

        // 5. 60 Hz Per-Weapon Autofire Inflated-Sphere Rescue & Beam Flux Discipline
        // During controlled HAUL_OUT_TO_VENT in formation, keep low-flux (< 50%) wingmen returning covering fire!
        boolean doNotUseFlux = (flags != null && flags.hasFlag(AIFlags.DO_NOT_USE_FLUX))
                || (div.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT && ship.getFluxLevel() >= 0.50f);
        CombatPhysicsProfile.applyAutofireBoundsAndBeamAssist(engine, ship, doNotUseFlux);
    }

    // -------------------------------------------------------------------------
    // Discipline 1: Newtonian Objective Capture & Waypoint Glide-Slope Braking
    // -------------------------------------------------------------------------

    private static boolean tryApplyObjectiveCaptureConn(
            CombatEngineAPI engine,
            ShipAPI ship,
            DivisionState div,
            ShipwideAIFlags flags) {
        if (div.currentIntent != DivisionIntentType.TAKE_AND_HOLD) {
            return false;
        }
        BattleObjectiveAPI obj = div.targetObjective;
        if (obj == null || obj.getLocation() == null || obj.getOwner() == div.side) {
            return false;
        }
        if (ship.getFluxLevel() >= 0.84f) {
            return false;
        }
        if (ship.getFluxTracker() != null && ship.getFluxTracker().isOverloadedOrVenting()) {
            return false;
        }
        Vector2f objLoc = obj.getLocation();
        float distToObj = DivisionCommanderAI.distance(ship.getLocation(), objLoc);
        float stopDist = CombatPhysicsProfile.computeShipMaxStoppingDistance(ship);
        float engageCaptureRadius = Math.max(1450f, stopDist * 1.45f + 420f);
        if (!div.underDirectObjectiveOrder && distToObj > engageCaptureRadius) {
            return false;
        }

        Vector2f campGoal = (flags != null && flags.getCustom(AIFlags.CAMP_LOCATION) instanceof Vector2f)
                ? (Vector2f) flags.getCustom(AIFlags.CAMP_LOCATION)
                : null;
        Vector2f helmGoal = resolveObjectiveHelmGoal(ship, div, objLoc, campGoal, distToObj);
        float distToGoal = DivisionCommanderAI.distance(ship.getLocation(), helmGoal);

        if (flags != null) {
            flags.unsetFlag(AIFlags.BACK_OFF);
            flags.setFlag(AIFlags.DO_NOT_BACK_OFF, 0.50f);
            flags.setFlag(AIFlags.DO_NOT_PURSUE, 0.50f);
            flags.setFlag(AIFlags.CAMP_LOCATION, 0.50f, helmGoal);
            flags.setFlag(AIFlags.MOVEMENT_DEST, 0.50f, helmGoal);
        }
        steerShipHelmOntoObjectivePoint(ship, helmGoal, distToGoal, distToObj);
        return true;
    }

    private static Vector2f resolveObjectiveHelmGoal(
            ShipAPI ship,
            DivisionState div,
            Vector2f objLoc,
            Vector2f campGoal,
            float distShipToObj) {
        if (ship == div.flagship || campGoal == null) {
            return (div.hasRouteWaypoint && distShipToObj > 1250f && div.routeWaypoint != null)
                    ? div.routeWaypoint
                    : objLoc;
        }
        if (!div.hasRouteWaypoint && distShipToObj <= 1500f) {
            float sdx = campGoal.x - objLoc.x;
            float sdy = campGoal.y - objLoc.y;
            float sDist = (float) Math.sqrt(sdx * sdx + sdy * sdy);
            if (sDist > 220f) {
                return new Vector2f(objLoc.x + (sdx / sDist) * 220f, objLoc.y + (sdy / sDist) * 220f);
            }
        }
        return campGoal;
    }

    /**
     * Closed-loop Newtonian helm controller for objective capture pads ({@code TAKE_AND_HOLD}).
     *
     * <p>Replaces hardcoded {@code 240u}/{@code 500u} braking gates with true {@code v_close^2 / (2 * decel)}
     * glide-slope braking, lateral-drift damping, and forward-burst system lockout inside the braking envelope
     * so high-speed frigates settle cleanly inside the capture circle on their first pass without overshooting.
     */
    public static void steerShipHelmOntoObjectivePoint(
            ShipAPI ship,
            Vector2f helmGoal,
            float distToGoal,
            float distToObj) {
        if (ship == null || helmGoal == null || ship.getLocation() == null) {
            return;
        }
        if (ship.getFluxTracker() != null && ship.getFluxTracker().isOverloadedOrVenting()) {
            return;
        }
        Vector2f sLoc = ship.getLocation();
        Vector2f vel = ship.getVelocity();
        float vx = (vel != null) ? vel.x : 0f;
        float vy = (vel != null) ? vel.y : 0f;
        float spd = (float) Math.sqrt(vx * vx + vy * vy);
        float decel = Math.max(20f, ship.getDeceleration());

        // Phase A: Parked inside the Control Point capture pad (<= 340u of objective center and <= 220u of slot).
        // Kill residual velocity so the ship holds station on the pad while leaving facing free for turrets/shields!
        if (distToObj <= 340f && distToGoal <= 220f) {
            ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
            float dx = helmGoal.x - sLoc.x;
            float dy = helmGoal.y - sLoc.y;
            float outVel = (distToGoal > 1f) ? (-(dx * vx + dy * vy) / distToGoal) : 0f;
            if (spd > 24f || outVel > 5f) {
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
                ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
                ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
                ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            }
            return;
        }

        float dx = helmGoal.x - sLoc.x;
        float dy = helmGoal.y - sLoc.y;
        float exactDist = (float) Math.sqrt(dx * dx + dy * dy);
        if (exactDist < 5f) {
            if (spd > 15f) {
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            }
            return;
        }
        float invD = 1.0f / exactDist;
        float ux = dx * invD;
        float uy = dy * invD;

        // Decompose velocity into closing speed toward helmGoal and sideways tangential drift
        float closingSpd = vx * ux + vy * uy;
        float latSpd = vx * (-uy) + vy * ux; // > 0 means drifting to port (+90 deg) of goal vector

        // Compute true Newtonian stopping distance along approach vector (with 0.78 safety margin + frame reaction pad)
        float posClose = Math.max(0f, closingSpd);
        float brakeDist = (posClose * posClose) / (2.0f * decel * 0.78f) + posClose * 0.22f;

        // Veto forward mobility bursts whenever within 1.4x braking distance + 380u of the capture pad!
        if (exactDist <= brakeDist * 1.40f + 380f || distToObj <= 650f) {
            if (hasForwardBurstMobilitySystem(ship)) {
                ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
            }
        }

        // Phase B: Inside Newtonian Braking Glide-Slope — bleed speed smoothly to settle <= 28 su/s on arrival!
        float targetGlideSpd = (float) Math.sqrt(2.0f * decel * 0.72f * Math.max(0f, exactDist - 85f));
        boolean mustBrake = (exactDist <= brakeDist + 95f && posClose > Math.max(28f, targetGlideSpd))
                || (distToObj <= 420f && exactDist <= 260f && spd > 38f);

        if (mustBrake) {
            ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
            ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
            ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
            ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
            ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            // Keep bow pointed toward pad while outside 360u so arrival settles inside the ring
            if (distToObj > 360f) {
                steerBowTowardUnitVector(ship, ux, uy);
            }
            return;
        }

        // Between 340u and 420u at low controlled speed, let BasicShipAI track nearby contesting enemies
        if (distToObj <= 420f && exactDist <= 240f && closingSpd >= -6f && spd <= 42f) {
            return;
        }

        // Phase C: Approach Cruise — turn bow onto lead-damped goal vector and thrust cleanly
        float facing = ship.getFacing();
        float fx = CombatPhysicsProfile.fastCosDeg(facing);
        float fy = CombatPhysicsProfile.fastSinDeg(facing);
        float cosErr = fx * ux + fy * uy;
        float sinErr = fx * uy - fy * ux; // > 0 => goal is CCW (TURN_LEFT) from current facing

        steerBowFromCrossDot(ship, cosErr, sinErr);
        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);

        if (cosErr >= 0.42f) { // within ~65 deg of goal
            ship.blockCommandForOneFrame(ShipCommand.DECELERATE);
            // Damp excessive sideways tangential drift so fast ships don't miss the pad into an orbit
            if (Math.abs(latSpd) > 38f && cosErr >= 0.82f) {
                if (latSpd > 0f) {
                    ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
                    ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
                } else {
                    ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
                    ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
                }
            } else {
                ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
                ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
            }
            ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        } else {
            // Swinging bow around toward goal: block main forward thrust and strafe toward the goal
            ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
            if (sinErr > 0f) {
                ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
                ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
            } else {
                ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
                ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
            }
        }
    }

    private static void steerBowTowardUnitVector(ShipAPI ship, float ux, float uy) {
        float facing = ship.getFacing();
        float fx = CombatPhysicsProfile.fastCosDeg(facing);
        float fy = CombatPhysicsProfile.fastSinDeg(facing);
        steerBowFromCrossDot(ship, fx * ux + fy * uy, fx * uy - fy * ux);
    }

    private static void steerBowFromCrossDot(ShipAPI ship, float cosErr, float sinErr) {
        // 2.2 deg threshold: sin(2.2 deg) ~= 0.0384
        if (cosErr < 0.9992f || Math.abs(sinErr) > 0.0384f) {
            if (sinErr > 0f) {
                ship.blockCommandForOneFrame(ShipCommand.TURN_RIGHT);
                ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
            } else {
                ship.blockCommandForOneFrame(ShipCommand.TURN_LEFT);
                ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
            }
        }
    }

    private static void applyStationSlotNewtonianBraking(
            ShipAPI ship,
            DivisionState div,
            ShipwideAIFlags flags,
            ShipSkillProfile skillProf,
            DivisionRammingMode ramMode) {
        if (flags == null || !CombatPhysicsProfile.hasExtremeInertia(ship)) {
            return;
        }
        if (div.currentIntent == DivisionIntentType.PRESS_HOME
                || div.currentIntent == DivisionIntentType.RUN_THE_GAUNTLET
                || (skillProf != null && skillProf.isDesignedToRam && ramMode != DivisionRammingMode.OFF)) {
            return;
        }
        Vector2f vel = ship.getVelocity();
        Vector2f loc = ship.getLocation();
        if (vel == null || loc == null) return;
        float spd = (float) Math.sqrt(vel.x * vel.x + vel.y * vel.y);
        if (spd < 45f) return;

        float decel = Math.max(10f, ship.getDeceleration());
        Object campObj = flags.getCustom(AIFlags.CAMP_LOCATION);
        if (campObj instanceof Vector2f && !flags.hasFlag(AIFlags.RUN_QUICKLY)) {
            Vector2f camp = (Vector2f) campObj;
            float dx = camp.x - loc.x;
            float dy = camp.y - loc.y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);

            // Subtract moving reference-frame velocity (`v_anchor`) when station-keeping on a moving friendly anchor
            // (screening/reinforcing a friendly division, or wingman forming up on a moving flagship) so screeners
            // and wingmen match the anchor's velocity smoothly instead of overshooting OR braking below march speed!
            float anchorVx = 0f;
            float anchorVy = 0f;
            ShipAPI refAnchor = null;
            if ((div.currentIntent == DivisionIntentType.TAKE_SCREENING_STATION
                    || div.currentIntent == DivisionIntentType.REINFORCE_DIVISION)
                    && div.targetFriendlyDivision != null
                    && div.targetFriendlyDivision.flagship != null
                    && div.targetFriendlyDivision.flagship.isAlive()) {
                refAnchor = div.targetFriendlyDivision.flagship;
            } else if (div.flagship != null && div.flagship != ship && div.flagship.isAlive()
                    && div.currentIntent != DivisionIntentType.TAKE_AND_HOLD
                    && div.currentIntent != DivisionIntentType.HOLD_IN_RESERVE) {
                refAnchor = div.flagship;
            }
            if (refAnchor != null && refAnchor.getVelocity() != null) {
                anchorVx = refAnchor.getVelocity().x;
                anchorVy = refAnchor.getVelocity().y;
            }
            float relVx = vel.x - anchorVx;
            float relVy = vel.y - anchorVy;
            float relSpd = (float) Math.sqrt(relVx * relVx + relVy * relVy);

            if (dist <= 135f) {
                float outVel = (dist > 1f) ? (-(dx * relVx + dy * relVy) / dist) : 0f;
                if (relSpd > 12f && outVel > 3f) {
                    ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                    ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                    return;
                }
            } else if (dist < 4200f) {
                float closingSpd = (relVx * dx + relVy * dy) / dist;
                if (closingSpd > 16f) {
                    // Continuous Newtonian arrival glide-slope (no mid-brake cutoff cliff -> zero figure-8 slot orbiting!)
                    float brakeDist = (closingSpd * closingSpd) / (2f * decel * 0.68f) + closingSpd * 0.22f;
                    float targetGlideSpd = (float) Math.sqrt(2.0f * decel * 0.62f * Math.max(0f, dist - 45f));
                    if (dist <= brakeDist + 95f && closingSpd > Math.max(16f, targetGlideSpd)) {
                        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                        ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                        return;
                    }
                }
            }
        }

        // Prevent high-inertia battleline hulls with DO_NOT_PURSUE from sliding inside enemy point-blank range
        ShipAPI focusEnemy = (div.targetEnemyDivision != null) ? div.targetEnemyDivision.flagship : ship.getShipTarget();
        if (focusEnemy != null && focusEnemy.isAlive() && focusEnemy.getLocation() != null
                && flags.hasFlag(AIFlags.DO_NOT_PURSUE)) {
            float edx = focusEnemy.getLocation().x - loc.x;
            float edy = focusEnemy.getLocation().y - loc.y;
            float eDist = (float) Math.sqrt(edx * edx + edy * edy);
            if (eDist > 40f) {
                float closingEnemy = (vel.x * edx + vel.y * edy) / eDist;
                if (closingEnemy > 42f) {
                    float stopTowardEnemy = (closingEnemy * closingEnemy) / (2f * decel);
                    float holdFloor = Math.max(450f, div.standoffRange * 0.78f);
                    if (eDist - stopTowardEnemy < holdFloor) {
                        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                        ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Discipline 2: Mobility System Overshoot Veto & Post-Burn Overspeed Damping
    // -------------------------------------------------------------------------

    private static void applyMobilitySystemDiscipline(
            CombatEngineAPI engine,
            ShipAPI ship,
            DivisionState div,
            BattleGroupState enemyGroup,
            ShipwideAIFlags flags,
            ShipSkillProfile skillProf,
            DivisionRammingMode ramMode,
            boolean onObjectiveApproach) {
        ShipSystemAPI sys = ship.getSystem();
        if (sys == null || skillProf == null) {
            return;
        }
        boolean isForwardBurst = skillProf.category != null && skillProf.category.isLinearVector();
        if (!isForwardBurst) {
            return;
        }
        boolean aggressiveChargeAllowed = div.currentIntent == DivisionIntentType.PRESS_HOME
                || div.currentIntent == DivisionIntentType.RUN_THE_GAUNTLET
                || (skillProf.isDesignedToRam && ramMode != DivisionRammingMode.OFF);
        if (aggressiveChargeAllowed) {
            return;
        }

        Vector2f loc = ship.getLocation();
        Vector2f vel = ship.getVelocity();
        if (loc == null || vel == null) return;
        float spd = (float) Math.sqrt(vel.x * vel.x + vel.y * vel.y);
        float maxSpd = Math.max(40f, ship.getMaxSpeed());
        float decel = Math.max(15f, ship.getDeceleration());

        // A. Post-Burn Overspeed Damping ("Rocket Sled" recovery when post-system engine slowdown is absent):
        // If the mobility system has finished firing (`!isActive && !isChargeup`) yet the hull is still hurtling
        // above 1.15x normal maxSpeed toward enemies inside its stopping + standoff envelope, brake immediately!
        if (!sys.isActive() && !sys.isChargeup() && spd > maxSpd * 1.15f) {
            ShipAPI nearestEnemy = CombatPhysicsProfile.findNearestEnemyWarship(engine, ship, 5500f);
            if (nearestEnemy != null && nearestEnemy.getLocation() != null) {
                float edx = nearestEnemy.getLocation().x - loc.x;
                float edy = nearestEnemy.getLocation().y - loc.y;
                float eDist = (float) Math.sqrt(edx * edx + edy * edy);
                if (eDist > 20f) {
                    float vToward = (vel.x * edx + vel.y * edy) / eDist;
                    float stopD = (spd * spd) / (2f * decel);
                    float safeFloor = Math.max(500f, div.standoffRange * 0.85f);
                    if (vToward > 35f && eDist <= safeFloor + stopD * 0.95f) {
                        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                        ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                    }
                }
            }
        }

        // B. Pre-Activation Forward Burst Veto:
        // If the system is ready (not yet active) and the ship's bow is pointed into an enemy formation where
        // post-burst momentum (`max(spd, maxSpd * 1.75)`) cannot stop before breaching safe standoff, veto USE_SYSTEM!
        if (!sys.isActive() && !sys.isChargeup() && !onObjectiveApproach) {
            boolean extracting = div.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                    || div.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE
                    || (flags != null && flags.hasFlag(AIFlags.BACK_OFF));
            ShipAPI nearestEnemy = CombatPhysicsProfile.findNearestEnemyWarship(engine, ship, 5500f);
            if (nearestEnemy != null && nearestEnemy.getLocation() != null) {
                float edx = nearestEnemy.getLocation().x - loc.x;
                float edy = nearestEnemy.getLocation().y - loc.y;
                float eDist = (float) Math.sqrt(edx * edx + edy * edy);
                if (eDist > 20f) {
                    float facing = ship.getFacing();
                    float fx = CombatPhysicsProfile.fastCosDeg(facing);
                    float fy = CombatPhysicsProfile.fastSinDeg(facing);
                    float bowDotEnemy = (fx * edx + fy * edy) / eDist;
                    if (extracting) {
                        // While retreating/venting, never fire a forward burst TOWARD the enemy!
                        if (bowDotEnemy > 0.25f) {
                            ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                        }
                    } else if (bowDotEnemy > 0.45f) {
                        float projectedBurstSpd = Math.max(spd + 160f, maxSpd * 1.70f);
                        float projectedStop = (projectedBurstSpd * projectedBurstSpd) / (2f * decel);
                        float standoffFloor = Math.max(520f, div.standoffRange * 0.82f);
                        if (eDist <= standoffFloor + projectedStop * 0.90f) {
                            ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                        }
                    }
                }
            }
        }
    }

    private static boolean hasForwardBurstMobilitySystem(ShipAPI ship) {
        ShipSkillProfile p = ShipSystemCoordinator.getSkillProfile(ship);
        return p != null && p.category != null && p.category.isLinearVector();
    }

    // -------------------------------------------------------------------------
    // Discipline 3: Extended-Range Suicidal Vent Veto & Early Shielded Backoff
    // -------------------------------------------------------------------------

    private static void applySurvivalAndVentDiscipline(
            CombatEngineAPI engine,
            ShipAPI ship,
            DivisionState div,
            BattleGroupState enemyGroup,
            ShipwideAIFlags flags,
            ShipSkillProfile skillProf,
            DivisionRammingMode ramMode) {
        if (ship.getFluxTracker() != null && ship.getFluxTracker().isOverloadedOrVenting()) {
            return;
        }
        float wScale = CombatPhysicsProfile.getWeaponRangeScale(engine);
        if (wScale <= 1.25f && !CombatPhysicsProfile.hasExtremeInertia(ship)) {
            return; // Preserve 100% vanilla vent/backoff thresholds at 1.0x physics
        }

        boolean hasActiveDefense = (ship.getShield() != null && ship.getShield().getType() != ShieldAPI.ShieldType.NONE)
                || ShipCapabilityProfile.hasTruePhaseCloak(ship);
        if (!hasActiveDefense) {
            return;
        }

        float flux = ship.getFluxLevel();
        boolean allOutCharge = div.currentIntent == DivisionIntentType.PRESS_HOME
                || div.currentIntent == DivisionIntentType.RUN_THE_GAUNTLET
                || (flags != null && flags.hasFlag(AIFlags.DO_NOT_BACK_OFF))
                || (skillProf != null && skillProf.isDesignedToRam && ramMode != DivisionRammingMode.OFF);

        // Check if any alive enemy warship currently holds this ship inside its live non-PD weapon envelope
        boolean insideLiveEnemyFire = CombatPhysicsProfile.isInsideLiveEnemyGunEnvelope(engine, ship);
        if (!insideLiveEnemyFire) {
            return;
        }

        // A. Suicidal Open-Space Vent Veto:
        // Vanilla VentModule thinks >2,200u is safe to drop shields and vent; under 3,000-4,800u weapon scales,
        // venting in open enemy gun arcs below 88% flux gets cruisers/destroyers deleted instantly.
        if (flux < 0.88f && div.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT) {
            ship.blockCommandForOneFrame(ShipCommand.VENT_FLUX);
        }

        // B. Early Shielded Disengagement under Compressed Hull Speeds:
        // Begin backing out under shields at 65% flux (while 35% shield buffer remains) rather than waiting
        // until 80%+ flux when compressed cruiser/frigate speeds make disengagement impossible.
        if (!allOutCharge && flux >= 0.65f && flags != null) {
            flags.setFlag(AIFlags.BACK_OFF, 0.35f);
            flags.setFlag(AIFlags.DO_NOT_PURSUE, 0.35f);
            flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
        }
    }

    // -------------------------------------------------------------------------
    // Discipline 4: Active Incoming-Salvo Defense (Fast Missiles & Standoff Bombers)
    // -------------------------------------------------------------------------

    private static void applyIncomingOrdnanceDefense(
            CombatEngineAPI engine,
            ShipAPI ship,
            DivisionState div,
            ShipwideAIFlags flags,
            ShipSkillProfile skillProf,
            DivisionRammingMode ramMode) {
        if (ship.getFluxTracker() != null && ship.getFluxTracker().isOverloadedOrVenting()) {
            return;
        }
        if (!CombatPhysicsProfile.evaluateInboundMissileSalvo(engine, ship, 2.75f, SALVO_SCRATCH)) {
            return;
        }
        float inboundDmg = SALVO_SCRATCH[0];
        float minTti = SALVO_SCRATCH[1];
        float tx = SALVO_SCRATCH[2];
        float ty = SALVO_SCRATCH[3];
        float evadeSign = SALVO_SCRATCH[4];

        // 1. Hard Vent Lockout: never drop shields to vent while >= 450 damage of missiles/torpedoes are < 2.75s out
        boolean hasActiveDefense = (ship.getShield() != null && ship.getShield().getType() != ShieldAPI.ShieldType.NONE)
                || ShipCapabilityProfile.hasTruePhaseCloak(ship);
        if (hasActiveDefense && inboundDmg >= 450f && ship.getFluxLevel() < 0.94f) {
            ship.blockCommandForOneFrame(ShipCommand.VENT_FLUX);
        }

        boolean allOutCharge = div.currentIntent == DivisionIntentType.PRESS_HOME
                || div.currentIntent == DivisionIntentType.RUN_THE_GAUNTLET
                || (skillProf != null && skillProf.isDesignedToRam && ramMode != DivisionRammingMode.OFF);

        // 2. Kill Head-On Closing Velocity & Side-Slip Heavy Salvos:
        // Charging at 400+ su/s straight into a 1,200 su/s torpedo cuts PD engagement time by ~35%.
        Vector2f vel = ship.getVelocity();
        if (!allOutCharge && inboundDmg >= 650f && minTti <= 2.10f && vel != null) {
            float vIntoSalvo = vel.x * tx + vel.y * ty;
            if (vIntoSalvo > 55f) {
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                if (skillProf != null && skillProf.category != null && skillProf.category.isLinearVector()) {
                    ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                }
                ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            }
            // Lateral side-slip on light/medium hulls to widen miss distance and reduce closing rate
            if (!ship.isCapital() && minTti <= 1.65f) {
                float facing = ship.getFacing();
                float fx = CombatPhysicsProfile.fastCosDeg(facing);
                float fy = CombatPhysicsProfile.fastSinDeg(facing);
                // Ship's port (+90 deg) unit vector is (-fy, fx); desired evade world dir is (-ty * evadeSign, tx * evadeSign)
                float ex = (-ty) * evadeSign;
                float ey = tx * evadeSign;
                float portDot = (-fy) * ex + fx * ey;
                if (portDot >= 0.25f) {
                    ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
                    ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
                } else if (portDot <= -0.25f) {
                    ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
                    ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
                }
            }
        }

        // 3. Imminent Heavy Salvo Defensive Bulwark Reflex (Damper Field / Shield Fortification <= 0.85s TTI)
        if (inboundDmg >= 900f && minTti <= 0.85f && skillProf != null
                && skillProf.category == ShipSkillCategory.DEFENSIVE_BULWARK
                && !skillProf.isToggle) {
            ShipSystemAPI sys = ship.getSystem();
            if (sys != null && !sys.isActive() && !sys.isChargeup() && !sys.isCoolingDown() && !sys.isOutOfAmmo()) {
                ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0);
            }
        }
    }

    // =========================================================================
    // DIVISION / BATTLEGROUP LOOKUP HELPERS
    // =========================================================================

    private static DivisionState resolveShipDivision(ShipAPI s) {
        if (s == null || s.getId() == null) return null;
        String id = s.getId();
        if (s.getOwner() == 0) {
            if (s.isAlly()) {
                return (activeAlliedGroup != null && activeAlliedGroup.shipIdToDivision != null)
                        ? activeAlliedGroup.shipIdToDivision.get(id)
                        : null;
            }
            return (activePlayerGroup != null && activePlayerGroup.shipIdToDivision != null)
                    ? activePlayerGroup.shipIdToDivision.get(id)
                    : null;
        }
        if (s.getOwner() == 1) {
            return (activeEnemyGroup != null && activeEnemyGroup.shipIdToDivision != null)
                    ? activeEnemyGroup.shipIdToDivision.get(id)
                    : null;
        }
        return null;
    }

    private static BattleGroupState resolveOpposingBattleGroup(ShipAPI s) {
        if (s == null) return null;
        return (s.getOwner() == 0) ? activeEnemyGroup : activePlayerGroup;
    }

    static boolean hasSiblingScreenOnSameAnchor(DivisionState screenDiv, DivisionState protectedDiv, int side) {
        if (screenDiv == null || protectedDiv == null) return false;
        if (side == 1) {
            return groupHasOtherScreener(activeEnemyGroup, screenDiv, protectedDiv);
        }
        return groupHasOtherScreener(activePlayerGroup, screenDiv, protectedDiv)
                || groupHasOtherScreener(activeAlliedGroup, screenDiv, protectedDiv);
    }

    private static boolean groupHasOtherScreener(
            BattleGroupState grp,
            DivisionState screenDiv,
            DivisionState protectedDiv) {
        if (grp == null || grp.divisions == null) return false;
        for (int i = 0, n = grp.divisions.size(); i < n; i++) {
            DivisionState peer = grp.divisions.get(i);
            if (peer != null && peer != screenDiv && peer.isCombatEffective()
                    && peer.currentIntent == DivisionIntentType.TAKE_SCREENING_STATION
                    && peer.targetFriendlyDivision == protectedDiv) {
                return true;
            }
        }
        return false;
    }
}
