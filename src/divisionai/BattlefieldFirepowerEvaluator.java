package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
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
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.DivisionState;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Battlefield Firepower-Space & Weapon-Arc Evaluator:
 *  1. Profiles every ship's weapons into "General" (anti-ship) and "Point Defense" (PD) roles across
 *     three time horizons:
 *       - Alpha Strike Score (0-2s burst/salvo deletion potential)
 *       - Short DoT Score (4-6s tactical pass burst DPS)
 *       - Sustained DoT Score (15-30s flux-clamped continuous DPS)
 *  2. Maintains a temporally and angularly stabilized 24-sector Polar Firepower Rose per ship
 *     (angular-velocity slew padding + soft cosine arc shoulders + asymmetric rise/fall EMA +
 *     instant blind-spot drop when a ship overloads or vents) so numbers never oscillate
 *     schizophrenically while genuine blind spots and disunified arcs are immediately exploitable.
 *  3. Detects L-shaped (~90 deg) and U-shaped (120-180 deg) overlapping crossfire cordons at any
 *     battlefield point so Hammerheads and Frigate Packs avoid diving into multi-vector kill pockets.
 *  4. Renders stabilized Firepower Roses / Planes on the battle screen (live view and Tab map),
 *     toggled via a compact button on the right side of the battle screen (OFF by default).
 */
public class BattlefieldFirepowerEvaluator {

    public static final int NUM_SECTORS = 24; // 15 degrees per sector around ship hull
    public static final float SECTOR_DEG = 360f / NUM_SECTORS;
    private static final int RENDER_CONTOUR_STEPS = 72; // 5-degree smooth contour interpolation

    // Asymmetric temporal stabilization time constants (seconds)
    private static final float TAU_HEADING_SMOOTH = 0.42f;
    private static final float TAU_RISE = 0.35f;
    private static final float TAU_FALL = 1.75f;
    private static final float TAU_VENT_DROP = 0.18f;

    // Right-side battle screen toggle button geometry (relative to scaled screenW x screenH)
    public static final float BTN_WIDTH = 142f;
    public static final float BTN_HEIGHT = 28f;
    public static final float BTN_RIGHT_MARGIN = 14f;
    public static final float BTN_Y_FRACTION = 0.52f;

    private static boolean overlayVisible = false; // OFF by default
    private static float lastMouseX = -1f;
    private static float lastMouseY = -1f;
    private static float lastToggleRealTime = -10f;

    private static final Map<String, ShipFirepowerRose> rosesByShipId = new HashMap<>();
    private static final Map<String, LabelAPI> uiLabelCache = new HashMap<>();

    /**
     * Stabilized 24-sector Polar Firepower Rose for a single ship.
     * Sector 0 is centered on 0 deg (ship bow, relative to smoothedFacingDeg);
     * Sector 6 is +90 deg (port beam); Sector 12 is 180 deg (stern); Sector 18 is 270 deg (starboard beam).
     */
    public static class ShipFirepowerRose {
        public final String shipId;
        public ShipAPI ship;
        public float smoothedFacingDeg;
        public boolean initialized = false;
        public float lastUpdateTime = -100f;

        // Smoothed 3-horizon General scores & PD score per 15-degree sector
        public final float[] alpha = new float[NUM_SECTORS];
        public final float[] shortDot = new float[NUM_SECTORS];
        public final float[] sustainedDot = new float[NUM_SECTORS];
        public final float[] pdScore = new float[NUM_SECTORS];

        // Smoothed weapon reach (world units) per sector
        public final float[] generalRange = new float[NUM_SECTORS];
        public final float[] pdRange = new float[NUM_SECTORS];

        // Summary metrics for fast queries and blind-spot comparison
        public float peakSectorShortDot = 1f;
        public float peakSectorAlpha = 1f;
        public float peakSectorSustained = 1f;
        public float maxGeneralRange = 600f;
        public float maxPdRange = 400f;
        public float liveReadinessMult = 1f;

        public ShipFirepowerRose(ShipAPI ship) {
            this.shipId = ship.getId();
            this.ship = ship;
            this.smoothedFacingDeg = normalizeAngle360(ship.getFacing());
        }

        /** Interpolates general weapon reach at a world bearing (degrees). */
        public float sampleGeneralRangeAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(generalRange, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
        }

        /** Interpolates PD weapon reach at a world bearing (degrees). */
        public float samplePdRangeAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(pdRange, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
        }

        /** Interpolates Short DoT score at a world bearing (degrees). */
        public float sampleShortDotAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(shortDot, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
        }

        /** Interpolates Alpha Strike score at a world bearing (degrees). */
        public float sampleAlphaAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(alpha, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
        }

        /** Interpolates Sustained DoT score at a world bearing (degrees). */
        public float sampleSustainedAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(sustainedDot, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
        }

        /** Interpolates Point Defense score at a world bearing (degrees). */
        public float samplePdAtWorldAngle(float worldAngleDeg) {
            return sampleArrayAtRelAngle(pdScore, normalizeAngle360(worldAngleDeg - smoothedFacingDeg));
        }

        private static float sampleArrayAtRelAngle(float[] arr, float relAngleDeg) {
            float exactIdx = (relAngleDeg / SECTOR_DEG);
            int idx0 = ((int) Math.floor(exactIdx)) % NUM_SECTORS;
            if (idx0 < 0) idx0 += NUM_SECTORS;
            int idx1 = (idx0 + 1) % NUM_SECTORS;
            float frac = exactIdx - (float) Math.floor(exactIdx);
            return arr[idx0] * (1f - frac) + arr[idx1] * frac;
        }
    }

    /**
     * Result of evaluating overlapping weapon arcs at a candidate point P(x, y) on the battlefield.
     */
    public static class PointFirepowerEval {
        public float incomingAlpha;
        public float incomingShortDot;
        public float incomingSustainedDot;
        public float incomingPd;

        public float friendlyShortDot;
        public float friendlySustainedDot;

        // Resultant unit direction pointing from P toward the net center of incoming enemy fire
        public float threatDirX;
        public float threatDirY;

        // 0.0 = unidirectional fire, ~0.35-0.55 = L-shaped 90-deg cordon, >= 0.62 = U-shaped 3-sided pocket
        public float cordonIndex;
        public float angularSpreadDeg;
        public int activeThreatOctants;

        // Combined danger score weighted by crossfire cordon multiplier and ship shield arc vulnerability
        public float effectiveDanger;

        // 8 compass octants of incoming Short DoT around P (for visualizing converging L/U crossfire vectors)
        public final float[] octantThreat = new float[8];
    }

    public static void resetForNewBattle() {
        overlayVisible = false; // Always OFF by default when a battle starts
        rosesByShipId.clear();
        uiLabelCache.clear();
        lastToggleRealTime = -10f;
    }

    public static boolean isOverlayVisible() {
        return overlayVisible;
    }

    public static void setOverlayVisible(boolean visible) {
        overlayVisible = visible;
    }

    public static void toggleOverlayVisible() {
        overlayVisible = !overlayVisible;
    }

    public static ShipFirepowerRose getRoseForShip(ShipAPI ship) {
        if (ship == null) return null;
        return rosesByShipId.get(ship.getId());
    }

    /**
     * Updates stabilized Polar Firepower Roses for all active ships in the battle.
     * Called on the C2 evaluation cadence (~0.5s) from OtcAI.updateTelemetry / DivisionAICombatPlugin.
     */
    public static void updateBattlefieldRoses(CombatEngineAPI engine, float now) {
        if (engine == null) return;
        List<ShipAPI> ships = engine.getShips();
        if (ships != null && !ships.isEmpty()) {
            for (ShipAPI ship : ships) {
                if (!DivisionAutoFormer.isEligibleCombatHull(ship)) continue;
                ShipFirepowerRose rose = rosesByShipId.get(ship.getId());
                if (rose == null) {
                    rose = new ShipFirepowerRose(ship);
                    rosesByShipId.put(ship.getId(), rose);
                } else {
                    rose.ship = ship;
                }
                updateSingleShipRose(rose, ship, now);
            }
        } else if (engine.getAiGridShips() != null) {
            Iterator<Object> iter = engine.getAiGridShips().getCheckIterator(new Vector2f(0f, 0f), 40000f, 40000f);
            while (iter != null && iter.hasNext()) {
                Object obj = iter.next();
                if (!(obj instanceof ShipAPI)) continue;
                ShipAPI ship = (ShipAPI) obj;
                if (!DivisionAutoFormer.isEligibleCombatHull(ship)) continue;
                ShipFirepowerRose rose = rosesByShipId.get(ship.getId());
                if (rose == null) {
                    rose = new ShipFirepowerRose(ship);
                    rosesByShipId.put(ship.getId(), rose);
                } else {
                    rose.ship = ship;
                }
                updateSingleShipRose(rose, ship, now);
            }
        }

        // Prune expired/destroyed ships
        Iterator<Map.Entry<String, ShipFirepowerRose>> it = rosesByShipId.entrySet().iterator();
        while (it.hasNext()) {
            ShipFirepowerRose r = it.next().getValue();
            if (r.ship == null || !r.ship.isAlive() || r.ship.isHulk() || r.ship.isExpired()) {
                it.remove();
            }
        }
    }

    private static void updateSingleShipRose(ShipFirepowerRose rose, ShipAPI ship, float now) {
        float dt = rose.initialized ? Math.max(0.05f, Math.min(2.0f, now - rose.lastUpdateTime)) : 0.5f;
        rose.lastUpdateTime = now;

        // 1. Stabilize ship heading so StrafeTargetManeuverV2 +/-15 deg nose wobble does not whip arcs
        float rawFacing = normalizeAngle360(ship.getFacing());
        if (!rose.initialized) {
            rose.smoothedFacingDeg = rawFacing;
        } else {
            float diff = shortestAngleDiffDeg(rose.smoothedFacingDeg, rawFacing);
            float headingAlpha = Math.min(1.0f, dt / (TAU_HEADING_SMOOTH + dt));
            rose.smoothedFacingDeg = normalizeAngle360(rose.smoothedFacingDeg + diff * headingAlpha);
        }

        // 2. Compute ship-level live combat readiness (overload / venting / high hard-flux suppression)
        FluxTrackerAPI ft = ship.getFluxTracker();
        boolean overloadedOrVenting = false;
        float ovldVentRemaining = 0f;
        if (ft != null && ft.isOverloadedOrVenting()) {
            overloadedOrVenting = true;
            ovldVentRemaining = Math.max(ft.getOverloadTimeRemaining(), ft.getTimeToVent());
        }

        float fluxLevel = ship.getFluxLevel();
        float readiness;
        if (overloadedOrVenting && ovldVentRemaining > 0.6f) {
            // Instant temporal blind spot while overloaded or venting!
            readiness = 0.04f;
        } else if (fluxLevel > 0.70f) {
            readiness = Math.max(0.28f, 1.0f - (fluxLevel - 0.70f) * 2.2f);
        } else {
            readiness = 1.0f;
        }
        rose.liveReadinessMult = readiness;

        // Estimate ship flux sustainability factor for Sustained DoT
        float shipDissipation = 250f;
        if (ship.getMutableStats() != null && ship.getMutableStats().getFluxDissipation() != null) {
            float d = ship.getMutableStats().getFluxDissipation().getModifiedValue();
            if (d > 1f) shipDissipation = d;
        } else {
            shipDissipation = ShipCapabilityProfile.defaultStrengthForHull(ship.getHullSize()) * 22f;
        }

        List<WeaponAPI> weapons = ship.getAllWeapons();
        float totalGenFluxPerSec = 0f;
        if (weapons != null) {
            for (WeaponAPI w : weapons) {
                if (!isUsableCombatWeapon(w)) continue;
                DerivedWeaponStatsAPI derived = w.getDerivedStats();
                if (derived != null && derived.getSustainedFluxPerSecond() > 0f) {
                    totalGenFluxPerSec += derived.getSustainedFluxPerSecond();
                } else {
                    totalGenFluxPerSec += weaponSizeWeight(w.getSize()) * 45f;
                }
            }
        }
        float shipFluxSustainability = Math.max(0.35f, Math.min(1.0f, shipDissipation / Math.max(1f, totalGenFluxPerSec)));

        float[] rawAlpha = new float[NUM_SECTORS];
        float[] rawShort = new float[NUM_SECTORS];
        float[] rawSustained = new float[NUM_SECTORS];
        float[] rawPd = new float[NUM_SECTORS];
        float[] rawGenRange = new float[NUM_SECTORS];
        float[] rawPdRange = new float[NUM_SECTORS];

        float hullOmega = Math.abs(ship.getAngularVelocity());

        if (weapons != null && !weapons.isEmpty()) {
            for (WeaponAPI w : weapons) {
                if (!isUsableCombatWeapon(w)) continue;
                if (w.isDisabled() || w.isPermanentlyDisabled()) continue;
                if (w.usesAmmo() && w.getAmmo() <= 0) continue;

                float range = w.getRange();
                if (range <= 50f) continue;

                boolean isPdOnly = w.hasAIHint(AIHints.PD_ONLY) || w.hasAIHint(AIHints.ANTI_FTR);
                boolean isPdAlso = w.hasAIHint(AIHints.PD_ALSO);
                boolean isPd = isPdOnly || isPdAlso || w.hasAIHint(AIHints.PD);
                boolean isStrike = w.hasAIHint(AIHints.STRIKE);

                float pdFrac;
                float genFrac;
                if (isPdOnly || (isPd && !isPdAlso && !isStrike)) {
                    pdFrac = 1.0f;
                    genFrac = 0.14f; // light close-in pressure against frigates
                } else if (isPdAlso) {
                    pdFrac = 0.65f;
                    genFrac = 0.70f;
                } else {
                    pdFrac = 0.0f;
                    genFrac = 1.0f;
                }

                float sizeW = weaponSizeWeight(w.getSize());
                DerivedWeaponStatsAPI derived = w.getDerivedStats();
                float burstDmg;
                float dps;
                float sustDps;
                if (derived != null && (derived.getDps() > 0f || derived.getBurstDamage() > 0f || derived.getDamagePerShot() > 0f)) {
                    burstDmg = Math.max(derived.getBurstDamage(), derived.getDamagePerShot());
                    dps = Math.max(derived.getDps(), derived.getSustainedDps());
                    sustDps = Math.max(derived.getSustainedDps(), dps * 0.60f);
                    if (dps <= 1f && burstDmg > 0f) {
                        float cd = Math.max(0.5f, w.getCooldown());
                        dps = burstDmg / Math.min(5f, cd);
                        sustDps = burstDmg / Math.max(1f, cd);
                    }
                } else {
                    // Fallback for headless SimArena mock weapons
                    float hullScale = (ship.getHullSize() == HullSize.CAPITAL_SHIP) ? 1.45f
                            : (ship.getHullSize() == HullSize.CRUISER ? 1.20f : 1.0f);
                    burstDmg = sizeW * 150f * hullScale * (isStrike ? 2.2f : 1.0f);
                    dps = sizeW * 95f * hullScale * (isStrike ? 1.4f : 1.0f);
                    sustDps = sizeW * 75f * hullScale * (isStrike ? 0.55f : 1.0f);
                }

                // Damage-type & Strike modifiers
                DamageType dmgType = w.getDamageType();
                float typeMult = 1.0f;
                if (dmgType == DamageType.FRAGMENTATION && !isStrike) {
                    typeMult = 0.45f; // Fragmentation is much less threatening to shielded/armored ships
                } else if (dmgType == DamageType.KINETIC || dmgType == DamageType.HIGH_EXPLOSIVE) {
                    typeMult = 1.12f;
                }

                float wAlpha = burstDmg * typeMult * (isStrike ? 1.65f : 1.0f);
                // Cooldown gating on immediate alpha strike (0-2s window)
                float cdRem = w.getCooldownRemaining();
                if (cdRem > 2.2f) {
                    wAlpha *= Math.max(0.25f, 1.0f - (cdRem - 2.2f) / 6.0f);
                }

                // Short DoT (4-6s tactical exchange window)
                float wShortDot = (dps * 0.75f + (burstDmg / 4.5f) * 0.25f) * typeMult * (isStrike ? 1.25f : 1.0f);

                // Sustained DoT (15-30s line battle window, clamped by ship flux dissipation & ammo endurance)
                float ammoEndurance = 1.0f;
                if (w.usesAmmo() && w.getAmmoPerSecond() <= 0f) {
                    ammoEndurance = Math.min(0.65f, Math.max(0.22f, w.getAmmo() / 16f));
                }
                float wSustainedDot = sustDps * typeMult * shipFluxSustainability * ammoEndurance;

                // Weapon arc geometry relative to ship facing
                float arcFacingRel = normalizeAngle360(w.getArcFacing());
                float rawArc = w.getArc();
                if (rawArc <= 0f) {
                    // Default forward+beam arc for mock weapons in SimArena
                    rawArc = isStrike ? 90f : 210f;
                }

                WeaponSlotAPI slot = w.getSlot();
                boolean isTurret = (slot == null) || slot.isTurret();
                // Angular-velocity slew padding prevents sharp arc boundary flicker while ships turn/strafe
                float slewPad = Math.min(32f, hullOmega * 1.15f + (isTurret ? 14f : 8f));
                float halfArc = Math.min(180f, rawArc * 0.5f + slewPad * 0.5f);
                float shoulderWidth = 20f; // smooth cosine taper outside halfArc

                for (int s = 0; s < NUM_SECTORS; s++) {
                    float sectorCenterRel = s * SECTOR_DEG;
                    float angDist = Math.abs(shortestAngleDiffDeg(sectorCenterRel, arcFacingRel));
                    if (angDist > halfArc + shoulderWidth) continue;

                    float angularWeight;
                    if (angDist <= halfArc) {
                        angularWeight = 1.0f;
                    } else {
                        float u = (angDist - halfArc) / shoulderWidth;
                        angularWeight = 0.5f * (1.0f + (float) Math.cos(u * Math.PI));
                    }

                    if (genFrac > 0f) {
                        float gw = angularWeight * genFrac * readiness;
                        rawAlpha[s] += wAlpha * gw;
                        rawShort[s] += wShortDot * gw;
                        rawSustained[s] += wSustainedDot * gw;
                        float effRange = range * (0.72f + 0.28f * angularWeight);
                        if (effRange > rawGenRange[s]) {
                            rawGenRange[s] = effRange;
                        }
                    }
                    if (pdFrac > 0f) {
                        float pw = angularWeight * pdFrac * readiness;
                        rawPd[s] += (dps * 0.8f + burstDmg * 0.2f) * pw;
                        float effPdRange = range * (0.75f + 0.25f * angularWeight);
                        if (effPdRange > rawPdRange[s]) {
                            rawPdRange[s] = effPdRange;
                        }
                    }
                }
            }
        }

        // 3. Apply Asymmetric Temporal EMA per sector (fast rise on threat, slow decay on wobble, fast drop on vent/overload)
        float alphaRise = Math.min(1.0f, dt / (TAU_RISE + dt));
        float alphaFall = (overloadedOrVenting && ovldVentRemaining > 0.6f)
                ? Math.min(1.0f, dt / (TAU_VENT_DROP + dt))
                : Math.min(1.0f, dt / (TAU_FALL + dt));

        float peakShort = 1f;
        float peakAlpha = 1f;
        float peakSust = 1f;
        float maxGenR = 300f;
        float maxPdR = 200f;

        for (int s = 0; s < NUM_SECTORS; s++) {
            if (!rose.initialized) {
                rose.alpha[s] = rawAlpha[s];
                rose.shortDot[s] = rawShort[s];
                rose.sustainedDot[s] = rawSustained[s];
                rose.pdScore[s] = rawPd[s];
                rose.generalRange[s] = rawGenRange[s];
                rose.pdRange[s] = rawPdRange[s];
            } else {
                rose.alpha[s] = blendAsymmetric(rose.alpha[s], rawAlpha[s], alphaRise, alphaFall);
                rose.shortDot[s] = blendAsymmetric(rose.shortDot[s], rawShort[s], alphaRise, alphaFall);
                rose.sustainedDot[s] = blendAsymmetric(rose.sustainedDot[s], rawSustained[s], alphaRise, alphaFall);
                rose.pdScore[s] = blendAsymmetric(rose.pdScore[s], rawPd[s], alphaRise, alphaFall);
                rose.generalRange[s] = blendAsymmetric(rose.generalRange[s], rawGenRange[s], alphaRise, alphaFall * 0.7f);
                rose.pdRange[s] = blendAsymmetric(rose.pdRange[s], rawPdRange[s], alphaRise, alphaFall * 0.7f);
            }

            if (rose.shortDot[s] > peakShort) peakShort = rose.shortDot[s];
            if (rose.alpha[s] > peakAlpha) peakAlpha = rose.alpha[s];
            if (rose.sustainedDot[s] > peakSust) peakSust = rose.sustainedDot[s];
            if (rose.generalRange[s] > maxGenR) maxGenR = rose.generalRange[s];
            if (rose.pdRange[s] > maxPdR) maxPdR = rose.pdRange[s];
        }

        rose.peakSectorShortDot = peakShort;
        rose.peakSectorAlpha = peakAlpha;
        rose.peakSectorSustained = peakSust;
        rose.maxGeneralRange = maxGenR;
        rose.maxPdRange = maxPdR;
        rose.initialized = true;
    }

    /**
     * Evaluates overlapping weapon arcs and directional crossfire (L-shaped / U-shaped cordons)
     * at a candidate world point P(x, y) from the perspective of observerSide (0 = player, 1 = enemy).
     */
    public static PointFirepowerEval evaluatePoint(
            CombatEngineAPI engine,
            int observerSide,
            Vector2f point,
            float friendlyShieldArcDeg) {

        PointFirepowerEval eval = new PointFirepowerEval();
        if (engine == null || point == null) return eval;

        float dirSumX = 0f;
        float dirSumY = 0f;
        float totalDirWeight = 0f;

        for (ShipFirepowerRose rose : rosesByShipId.values()) {
            ShipAPI s = rose.ship;
            if (s == null || !s.isAlive() || s.isHulk() || s.isExpired()) continue;

            boolean isFriendly = (s.getOwner() == observerSide);
            if (!isFriendly && !OtcAI.isShipVisibleToSide(engine, observerSide, s)) {
                continue;
            }

            Vector2f sLoc = s.getLocation();
            float dx = point.x - sLoc.x;
            float dy = point.y - sLoc.y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float maxCheckRange = rose.maxGeneralRange + 320f;
            if (dist > maxCheckRange) continue;

            float worldAngleShipToPoint = (dist > 1f)
                    ? normalizeAngle360((float) Math.toDegrees(Math.atan2(dy, dx)))
                    : rose.smoothedFacingDeg;
            float relAngle = normalizeAngle360(worldAngleShipToPoint - rose.smoothedFacingDeg);

            float sectorReach = ShipFirepowerRose.sampleArrayAtRelAngle(rose.generalRange, relAngle);
            float pdReach = ShipFirepowerRose.sampleArrayAtRelAngle(rose.pdRange, relAngle);

            // Smooth range envelope with a 240u tactical approach buffer outside max weapon range
            float rangeFactor = 0f;
            if (sectorReach > 50f && dist <= sectorReach + 240f) {
                if (dist <= sectorReach * 0.85f) {
                    rangeFactor = 1.0f;
                } else {
                    float u = (dist - sectorReach * 0.85f) / Math.max(120f, sectorReach * 0.15f + 240f);
                    u = Math.max(0f, Math.min(1f, u));
                    rangeFactor = 0.5f * (1.0f + (float) Math.cos(u * Math.PI));
                }
            }

            if (isFriendly) {
                if (rangeFactor > 0f) {
                    eval.friendlyShortDot += ShipFirepowerRose.sampleArrayAtRelAngle(rose.shortDot, relAngle) * rangeFactor;
                    eval.friendlySustainedDot += ShipFirepowerRose.sampleArrayAtRelAngle(rose.sustainedDot, relAngle) * rangeFactor;
                }
            } else {
                if (rangeFactor > 0f) {
                    float a = ShipFirepowerRose.sampleArrayAtRelAngle(rose.alpha, relAngle) * rangeFactor;
                    float sh = ShipFirepowerRose.sampleArrayAtRelAngle(rose.shortDot, relAngle) * rangeFactor;
                    float su = ShipFirepowerRose.sampleArrayAtRelAngle(rose.sustainedDot, relAngle) * rangeFactor;

                    eval.incomingAlpha += a;
                    eval.incomingShortDot += sh;
                    eval.incomingSustainedDot += su;

                    // Direction from point P toward the threatening enemy ship (-dx, -dy)
                    float w = sh + a * 0.35f;
                    if (w > 1f && dist > 1f) {
                        float ux = -dx / dist;
                        float uy = -dy / dist;
                        dirSumX += ux * w;
                        dirSumY += uy * w;
                        totalDirWeight += w;

                        float threatBearing = normalizeAngle360((float) Math.toDegrees(Math.atan2(uy, ux)));
                        int oct = ((int) Math.floor(((threatBearing + 22.5f) % 360f) / 45f)) & 7;
                        eval.octantThreat[oct] += w;
                    }
                }
                if (pdReach > 50f && dist <= pdReach + 120f) {
                    float pdFactor = (dist <= pdReach) ? 1.0f : Math.max(0f, 1.0f - (dist - pdReach) / 120f);
                    eval.incomingPd += ShipFirepowerRose.sampleArrayAtRelAngle(rose.pdScore, relAngle) * pdFactor;
                }
            }
        }

        // Compute L-Shaped and U-Shaped Cordon Index from directional threat distribution
        if (totalDirWeight > 10f) {
            float netLen = (float) Math.sqrt(dirSumX * dirSumX + dirSumY * dirSumY);
            if (netLen > 1e-3f) {
                eval.threatDirX = dirSumX / netLen;
                eval.threatDirY = dirSumY / netLen;
            }
            float coherence = Math.max(0f, Math.min(1f, netLen / totalDirWeight));
            float rawDispersion = 1.0f - coherence;

            // Measure angular span across significant threat octants (>= 14% of total incoming fire and >= 45 threat)
            float sigThreshold = Math.max(45f, totalDirWeight * 0.14f);
            int sigCount = 0;
            float maxPairAngle = 0f;
            for (int i = 0; i < 8; i++) {
                if (eval.octantThreat[i] >= sigThreshold) {
                    sigCount++;
                    for (int j = i + 1; j < 8; j++) {
                        if (eval.octantThreat[j] >= sigThreshold) {
                            float ang = Math.abs(shortestAngleDiffDeg(i * 45f, j * 45f));
                            if (ang > maxPairAngle) {
                                maxPairAngle = ang;
                            }
                        }
                    }
                }
            }
            eval.activeThreatOctants = sigCount;
            eval.angularSpreadDeg = maxPairAngle;

            // Combine vector incoherence with angular spread:
            //  - 0 deg (single front): cordonIndex ~ 0.0
            //  - 90 deg (L-shaped crossfire): maxPairAngle = 90, rawDispersion ~ 0.29 -> cordonIndex ~ 0.45
            //  - 135-180 deg (U-shaped pocket): maxPairAngle >= 135, rawDispersion >= 0.55 -> cordonIndex ~ 0.75-1.0
            float spreadFactor = Math.max(0f, Math.min(1f, (maxPairAngle - 40f) / 115f));
            eval.cordonIndex = Math.max(0f, Math.min(1f, rawDispersion * 0.85f + spreadFactor * 0.55f));
        }

        // Narrow front shields (like Hammerhead's 90-deg front shield) suffer much higher crossfire vulnerability
        // when angularSpreadDeg exceeds their shield arc!
        float clampedShieldArc = Math.max(60f, Math.min(360f, friendlyShieldArcDeg));
        float shieldVuln = 1.0f + Math.max(0f, (240f - clampedShieldArc) / 150f);
        float baseThreat = eval.incomingShortDot * 0.68f + eval.incomingAlpha * 0.32f;
        eval.effectiveDanger = baseThreat * (1.0f + 1.80f * eval.cordonIndex * shieldVuln);

        return eval;
    }

    /**
     * Updates a division's live firepower & crossfire cordon telemetry at its current centroid.
     */
    public static void updateDivisionFirepowerTelemetry(CombatEngineAPI engine, DivisionState div) {
        if (div == null || !div.isCombatEffective()) return;
        float avgShieldArc = computeDivisionAvgShieldArc(div);
        PointFirepowerEval eval = evaluatePoint(engine, div.side, div.centroid, avgShieldArc);

        div.incomingAlphaThreat = eval.incomingAlpha;
        div.incomingShortDotThreat = eval.incomingShortDot;
        div.incomingSustainedDotThreat = eval.incomingSustainedDot;
        div.incomingPdThreat = eval.incomingPd;
        div.crossfireCordonIndex = eval.cordonIndex;

        // Asymmetric smoothing on division danger score
        if (div.smoothedDangerScore <= 0f) {
            div.smoothedDangerScore = eval.effectiveDanger;
        } else if (eval.effectiveDanger >= div.smoothedDangerScore) {
            div.smoothedDangerScore = div.smoothedDangerScore * 0.55f + eval.effectiveDanger * 0.45f;
        } else {
            div.smoothedDangerScore = div.smoothedDangerScore * 0.80f + eval.effectiveDanger * 0.20f;
        }
    }

    /**
     * Scores how well a candidate station point exploits blind spots or disunified weapon arcs
     * of the target enemy division.
     * Returns a positive bonus when candidatePos lies in the target division's weak/stern/venting sector
     * or engages only the outer ship of a disunified enemy formation.
     */
    public static float evaluateBlindSpotAdvantage(
            CombatEngineAPI engine,
            int observerSide,
            DivisionState enemyDiv,
            Vector2f candidatePos) {

        if (enemyDiv == null || candidatePos == null) return 0f;
        float totalAdvantage = 0f;

        for (ShipAPI e : enemyDiv.getAliveMembers()) {
            if (!OtcAI.isShipVisibleToSide(engine, observerSide, e)) continue;
            ShipFirepowerRose rose = rosesByShipId.get(e.getId());
            if (rose == null || rose.peakSectorShortDot <= 5f) continue;

            float dx = candidatePos.x - e.getLocation().x;
            float dy = candidatePos.y - e.getLocation().y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > rose.maxGeneralRange + 900f) continue;

            float worldBearing = (dist > 1f)
                    ? normalizeAngle360((float) Math.toDegrees(Math.atan2(dy, dx)))
                    : rose.smoothedFacingDeg;
            float bearingShortDot = rose.sampleShortDotAtWorldAngle(worldBearing);
            float sectorRatio = bearingShortDot / Math.max(1f, rose.peakSectorShortDot);

            // If this bearing has < 55% of the ship's peak arc firepower (e.g. stern/quarter blind spot or disunified facing)
            float fp = ShipCapabilityProfile.resolveShipFpStrength(e);
            if (sectorRatio < 0.55f) {
                totalAdvantage += (0.55f - sectorRatio) * fp * 14f;
            }
            // Extra temporal blind-spot bonus if the enemy ship is overloaded, venting, or above 80% flux
            if (rose.liveReadinessMult < 0.25f) {
                totalAdvantage += fp * 12f;
            } else if (e.getFluxLevel() >= 0.78f) {
                totalAdvantage += fp * 6f;
            }
        }
        return totalAdvantage;
    }

    /**
     * Evaluates candidate stations along an arc around centerPos at radius, choosing a position that:
     *  - Avoids L-shaped and U-shaped crossfire cordons (low effectiveDanger & low cordonIndex)
     *  - Exploits enemy blind spots / disunified weapon arcs (high evaluateBlindSpotAdvantage)
     *  - Maintains strong hysteresis stickiness toward preferredPos and the division's current stationPoint
     *    so stations glide smoothly and never jump back and forth.
     */
    public static void optimizeStationAlongArc(
            CombatEngineAPI engine,
            DivisionState myDiv,
            DivisionState targetEnemyDiv,
            Vector2f arcCenter,
            Vector2f preferredPos,
            float maxArcSweepDeg,
            int numSamples,
            Vector2f outStation) {

        if (myDiv == null || arcCenter == null || preferredPos == null) {
            if (outStation != null && preferredPos != null) outStation.set(preferredPos);
            return;
        }
        if (rosesByShipId.isEmpty() || numSamples <= 1) {
            outStation.set(preferredPos);
            return;
        }

        float dx = preferredPos.x - arcCenter.x;
        float dy = preferredPos.y - arcCenter.y;
        float radius = (float) Math.sqrt(dx * dx + dy * dy);
        if (radius < 150f) {
            outStation.set(preferredPos);
            return;
        }

        float baseAngleDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
        float shieldArc = computeDivisionAvgShieldArc(myDiv);

        // Evaluate the default preferredPos first so we only shift if another angle is meaningfully better
        PointFirepowerEval prefEval = evaluatePoint(engine, myDiv.side, preferredPos, shieldArc);
        // Fast path: if preferredPos has zero crossfire cordon and low danger, keep exact preferredPos
        if (prefEval.cordonIndex < 0.18f && prefEval.effectiveDanger < 120f) {
            outStation.set(preferredPos);
            return;
        }

        float bestScore = -Float.MAX_VALUE;
        float bestX = preferredPos.x;
        float bestY = preferredPos.y;

        boolean hasPrevStation = (myDiv.stationPoint.x != 0f || myDiv.stationPoint.y != 0f);
        float halfSamples = (numSamples - 1) * 0.5f;

        for (int i = 0; i < numSamples; i++) {
            float offsetFraction = (halfSamples > 0f) ? ((i - halfSamples) / halfSamples) : 0f;
            float angleDeg = baseAngleDeg + offsetFraction * maxArcSweepDeg;
            float rad = (float) Math.toRadians(angleDeg);
            float candX = arcCenter.x + (float) Math.cos(rad) * radius;
            float candY = arcCenter.y + (float) Math.sin(rad) * radius;
            Vector2f cand = new Vector2f(candX, candY);

            PointFirepowerEval eval = (i == (int) halfSamples)
                    ? prefEval
                    : evaluatePoint(engine, myDiv.side, cand, shieldArc);
            float blindSpotBonus = (targetEnemyDiv != null)
                    ? evaluateBlindSpotAdvantage(engine, myDiv.side, targetEnemyDiv, cand)
                    : 0f;

            // Score candidate: reward blind spots and friendly fire support; heavily penalize L/U crossfire cordons
            float score = blindSpotBonus * 1.15f
                    + eval.friendlyShortDot * 0.12f
                    - eval.effectiveDanger * 0.55f
                    - eval.cordonIndex * 520f;

            // Penalize high-alpha delete zones when our division already has elevated flux
            if (myDiv.avgFluxLevel >= 0.40f || myDiv.maxMemberFluxLevel >= 0.60f) {
                score -= eval.incomingAlpha * 0.45f;
            }

            // Deviation penalty from doctrinal preferred angle (keeps doctrinal geometry unless crossfire forces a shift)
            score -= Math.abs(offsetFraction) * 165f;

            // Temporal hysteresis stickiness toward current stationPoint so station never flickers
            if (hasPrevStation) {
                float distToPrev = distance(cand, myDiv.stationPoint);
                score -= distToPrev * 0.18f;
            }

            // Travel distance cost from current division centroid
            float distFromDiv = distance(cand, myDiv.centroid);
            score -= distFromDiv * 0.08f;

            if (score > bestScore) {
                bestScore = score;
                bestX = candX;
                bestY = candY;
            }
        }

        outStation.set(bestX, bestY);
    }

    public static float computeDivisionAvgShieldArc(DivisionState div) {
        if (div == null) return 180f;
        List<ShipAPI> alive = div.getAliveMembers();
        if (alive.isEmpty()) return 180f;
        float sum = 0f;
        for (ShipAPI s : alive) {
            ShieldAPI sh = s.getShield();
            if (sh != null && sh.getType() != ShieldAPI.ShieldType.NONE && sh.getArc() > 0f) {
                sum += sh.getArc();
            } else if (s.getPhaseCloak() != null) {
                sum += 270f;
            } else {
                sum += 90f; // Unshielded hulls are vulnerable to multi-angle crossfire
            }
        }
        return sum / alive.size();
    }

    // =========================================================================
    // Battle Screen / Command View Window State Guards & Input/Rendering
    // =========================================================================

    /**
     * Returns true ONLY when the engine belongs to an active combat battle (CombatState),
     * and false on the Main Menu background battle (TitleScreenState / GameState.TITLE) or Campaign.
     */
    public static boolean isRealCombatEngine(CombatEngineAPI engine) {
        if (engine == null) return false;
        try {
            com.fs.starfarer.api.GameState state = Global.getCurrentState();
            if (state != null && state != com.fs.starfarer.api.GameState.COMBAT) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        com.fs.starfarer.api.combat.CombatUIAPI ui = engine.getCombatUI();
        if (ui != null) {
            String clsName = ui.getClass().getName();
            if (clsName.contains("TitleScreenState")) {
                return false;
            }
            if (!"com.fs.starfarer.combat.CombatState".equals(clsName) && !clsName.contains("$Proxy")) {
                return false;
            }
            return true;
        }
        // In the live game (Global.getSettings() != null), a real battle always has a CombatState UI;
        // in headless SimArena unit tests, Global.getSettings() is null and ui is null.
        return Global.getSettings() == null;
    }

    /**
     * Returns true ONLY when the Command View Window (Tab tactical map) is actively open
     * inside a real battle (never on the Main Menu TitleScreenState, pre-battle deployment
     * dialog, or live arcade piloting HUD).
     */
    public static boolean isCommandViewWindowActive(CombatEngineAPI engine) {
        if (!isRealCombatEngine(engine)) return false;
        com.fs.starfarer.api.combat.CombatUIAPI ui = engine.getCombatUI();
        if (ui == null) return false;
        if (!ui.isShowingCommandUI() || ui.getCommandUIOpacity() <= 0.05f) {
            return false;
        }
        return true;
    }

    /**
     * Processes mouse events for the right-side "FIREPOWER: OFF/ON" toggle button on the Command View Window.
     * Strictly ignores input on the Main Menu (TitleScreenState) or when the Command View Window is closed.
     */
    public static void processInputEvents(List<InputEventAPI> events) {
        CombatEngineAPI engine = null;
        try {
            engine = Global.getCombatEngine();
        } catch (Throwable ignored) {
        }
        processInputEvents(engine, events);
    }

    public static void processInputEvents(CombatEngineAPI engine, List<InputEventAPI> events) {
        if (events == null || Global.getSettings() == null) return;
        if (!isCommandViewWindowActive(engine)) return;
        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        float btnX = screenW - BTN_WIDTH - BTN_RIGHT_MARGIN;
        float btnY = screenH * BTN_Y_FRACTION;

        for (InputEventAPI event : events) {
            if (event == null || event.isConsumed()) continue;
            if (event.isMouseEvent()) {
                lastMouseX = event.getX();
                lastMouseY = event.getY();
            }
            if (event.isLMBDownEvent()) {
                float mx = event.getX();
                float my = event.getY();
                if (mx >= btnX && mx <= btnX + BTN_WIDTH && my >= btnY && my <= btnY + BTN_HEIGHT) {
                    event.consume();
                    // Guard against double-firing in the same frame if both hooks see the event
                    float nowReal = (engine != null)
                            ? engine.getTotalElapsedTime(true)
                            : -1f;
                    if (nowReal < 0f || Math.abs(nowReal - lastToggleRealTime) > 0.05f) {
                        lastToggleRealTime = nowReal;
                        toggleOverlayVisible();
                        try {
                            Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f);
                        } catch (Throwable ignored) {
                        }
                    }
                    return;
                }
            }
        }
    }

    /**
     * Legacy live-screen hook retained as a no-op guard so the Firepower Overlay button and roses
     * render exclusively inside the Command View Window (Tab tactical map via DivisionTacticalMapOverlay)
     * and never appear on the Main Menu (TitleScreenState) or over non-Command-View dialogs.
     */
    public static void renderOnLiveBattleScreen(
            CombatEngineAPI engine,
            ViewportAPI viewport,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup) {
        // Rendered strictly inside the Command View Window (DivisionTacticalMapOverlay.render)
        // when isCommandViewWindowActive(engine) is true.
    }

    /**
     * Renders stabilized Polar Firepower Roses / Planes for all visible combat ships in screen space
     * (worldToScreen: sx = offsetX + wx * scale, sy = offsetY + wy * scale).
     */
    public static void renderFirepowerRosesScreenSpace(
            CombatEngineAPI engine,
            float scale,
            float offsetX,
            float offsetY,
            float fade,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup) {

        if (!overlayVisible || engine == null || scale <= 1e-5f || fade <= 0.04f) return;

        // Ensure roses exist even if toggled on while paused before the first 0.5s tick
        if (rosesByShipId.isEmpty()) {
            updateBattlefieldRoses(engine, engine.getTotalElapsedTime(false));
        }

        FogOfWarAPI playerFog = engine.getFogOfWar(0);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        // 1. Render per-ship Polar Firepower Roses (Sustained DoT outer plane, Short DoT mid plane, Alpha core, PD ring)
        for (ShipFirepowerRose rose : rosesByShipId.values()) {
            ShipAPI s = rose.ship;
            if (s == null || !s.isAlive() || s.isHulk() || s.isExpired()) continue;
            if (rose.maxGeneralRange <= 80f && rose.maxPdRange <= 80f) continue;

            boolean isEnemy = (s.getOwner() == 1);
            boolean isAlly = !isEnemy && s.isAlly();
            if (isEnemy && playerFog != null && !playerFog.isVisible(s)) {
                continue;
            }

            renderSingleShipFirepowerRose(rose, s, isEnemy, isAlly, scale, offsetX, offsetY, fade);
        }

        // 2. Render L-Cordon / U-Cordon crossfire pocket indicators for divisions experiencing crossfire
        renderDivisionCordonIndicators(engine, playerGroup, false, scale, offsetX, offsetY, fade);
        renderDivisionCordonIndicators(engine, alliedAiGroup, false, scale, offsetX, offsetY, fade);
        renderDivisionCordonIndicators(engine, enemyGroup, true, scale, offsetX, offsetY, fade);

        GL11.glPopMatrix();
        GL11.glPopAttrib();

        // 3. Render compact L-CORDON / U-CORDON warning labels on divisions in crossfire
        renderDivisionCordonLabels(engine, playerGroup, false, scale, offsetX, offsetY, fade);
    }

    private static void renderSingleShipFirepowerRose(
            ShipFirepowerRose rose,
            ShipAPI ship,
            boolean isEnemy,
            boolean isAlly,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        float cx = offsetX + ship.getLocation().x * scale;
        float cy = offsetY + ship.getLocation().y * scale;
        float baseShipRadPx = Math.max(4f, ship.getCollisionRadius() * scale * 0.55f);

        // Color palette by side & horizon:
        // Friendly: Cool Tactical Cyan/Teal (Sustained), Bright Sky-Cyan (Short DoT), Electric Gold-White (Alpha)
        // Enemy: Warm Crimson-Red (Sustained), Amber-Orange (Short DoT), Hot Magenta-Gold (Alpha)
        float sustR, sustG, sustB;
        float shortR, shortG, shortB;
        float alphaR, alphaG, alphaB;

        if (isEnemy) {
            sustR = 1.00f; sustG = 0.24f; sustB = 0.18f;
            shortR = 1.00f; shortG = 0.52f; shortB = 0.14f;
            alphaR = 1.00f; alphaG = 0.82f; alphaB = 0.25f;
        } else if (isAlly) {
            sustR = 0.65f; sustG = 0.88f; sustB = 0.28f;
            shortR = 0.85f; shortG = 0.96f; shortB = 0.42f;
            alphaR = 1.00f; alphaG = 0.92f; alphaB = 0.55f;
        } else {
            sustR = 0.12f; sustG = 0.72f; sustB = 0.96f;
            shortR = 0.28f; shortG = 0.92f; shortB = 1.00f;
            alphaR = 0.75f; alphaG = 0.98f; alphaB = 1.00f;
        }

        float readinessFade = Math.max(0.22f, rose.liveReadinessMult) * fade;
        float normSust = Math.max(80f, rose.peakSectorSustained);
        float normShort = Math.max(100f, rose.peakSectorShortDot);
        float normAlpha = Math.max(180f, rose.peakSectorAlpha);

        float[] sustX = new float[RENDER_CONTOUR_STEPS];
        float[] sustY = new float[RENDER_CONTOUR_STEPS];
        float[] shortX = new float[RENDER_CONTOUR_STEPS];
        float[] shortY = new float[RENDER_CONTOUR_STEPS];
        float[] alphaX = new float[RENDER_CONTOUR_STEPS];
        float[] alphaY = new float[RENDER_CONTOUR_STEPS];
        float[] pdX = new float[RENDER_CONTOUR_STEPS];
        float[] pdY = new float[RENDER_CONTOUR_STEPS];
        boolean hasPd = false;

        for (int i = 0; i < RENDER_CONTOUR_STEPS; i++) {
            float relDeg = i * (360f / RENDER_CONTOUR_STEPS);
            float worldDeg = rose.smoothedFacingDeg + relDeg;
            float rad = (float) Math.toRadians(worldDeg);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);

            float genReachPx = ShipFirepowerRose.sampleArrayAtRelAngle(rose.generalRange, relDeg) * scale;
            float sustVal = ShipFirepowerRose.sampleArrayAtRelAngle(rose.sustainedDot, relDeg);
            float shortVal = ShipFirepowerRose.sampleArrayAtRelAngle(rose.shortDot, relDeg);
            float alphaVal = ShipFirepowerRose.sampleArrayAtRelAngle(rose.alpha, relDeg);

            float sustIntensity = Math.min(1f, sustVal / normSust);
            float shortIntensity = Math.min(1f, shortVal / normShort);
            float alphaIntensity = Math.min(1f, alphaVal / normAlpha);

            // Outer Plane: Sustained DoT reach & concentration
            float rSust = (genReachPx > 2f && sustIntensity > 0.03f)
                    ? Math.max(baseShipRadPx, genReachPx * (0.42f + 0.58f * (float) Math.sqrt(sustIntensity)))
                    : baseShipRadPx * 0.45f;
            // Mid Plane: Short DoT (5s burst exchange envelope)
            float rShort = (genReachPx > 2f && shortIntensity > 0.04f)
                    ? Math.max(baseShipRadPx * 0.8f, rSust * (0.48f + 0.44f * shortIntensity))
                    : baseShipRadPx * 0.35f;
            // Inner Core Plane: Alpha Strike (0-2s burst/torpedo salvo cone)
            float rAlpha = (genReachPx > 2f && alphaIntensity > 0.06f)
                    ? Math.max(baseShipRadPx * 0.6f, rShort * (0.40f + 0.50f * alphaIntensity))
                    : baseShipRadPx * 0.25f;

            sustX[i] = cx + cos * rSust;
            sustY[i] = cy + sin * rSust;
            shortX[i] = cx + cos * rShort;
            shortY[i] = cy + sin * rShort;
            alphaX[i] = cx + cos * rAlpha;
            alphaY[i] = cy + sin * rAlpha;

            float pdReachPx = ShipFirepowerRose.sampleArrayAtRelAngle(rose.pdRange, relDeg) * scale;
            float pdVal = ShipFirepowerRose.sampleArrayAtRelAngle(rose.pdScore, relDeg);
            if (pdReachPx > 3f && pdVal > 5f) {
                hasPd = true;
                pdX[i] = cx + cos * Math.max(baseShipRadPx * 1.05f, pdReachPx);
                pdY[i] = cy + sin * Math.max(baseShipRadPx * 1.05f, pdReachPx);
            } else {
                pdX[i] = cx + cos * baseShipRadPx * 0.5f;
                pdY[i] = cy + sin * baseShipRadPx * 0.5f;
            }
        }

        // Plane 1: Sustained DoT Outer Polar Plane
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(sustR, sustG, sustB, 0.14f * readinessFade);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(sustR, sustG, sustB, 0.07f * readinessFade);
        for (int i = 0; i <= RENDER_CONTOUR_STEPS; i++) {
            int idx = i % RENDER_CONTOUR_STEPS;
            GL11.glVertex2f(sustX[idx], sustY[idx]);
        }
        GL11.glEnd();

        // Outer Sustained DoT contour line
        GL11.glLineWidth(1.25f);
        GL11.glColor4f(sustR, sustG, sustB, 0.34f * readinessFade);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < RENDER_CONTOUR_STEPS; i++) {
            GL11.glVertex2f(sustX[i], sustY[i]);
        }
        GL11.glEnd();

        // Plane 2: Short DoT (4-6s Burst) Mid Polar Plane
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(shortR, shortG, shortB, 0.16f * readinessFade);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(shortR, shortG, shortB, 0.09f * readinessFade);
        for (int i = 0; i <= RENDER_CONTOUR_STEPS; i++) {
            int idx = i % RENDER_CONTOUR_STEPS;
            GL11.glVertex2f(shortX[idx], shortY[idx]);
        }
        GL11.glEnd();

        // Plane 3: Alpha Strike (0-2s Salvo) Hot Inner Core
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(alphaR, alphaG, alphaB, 0.20f * readinessFade);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(alphaR, alphaG, alphaB, 0.10f * readinessFade);
        for (int i = 0; i <= RENDER_CONTOUR_STEPS; i++) {
            int idx = i % RENDER_CONTOUR_STEPS;
            GL11.glVertex2f(alphaX[idx], alphaY[idx]);
        }
        GL11.glEnd();

        // Plane 4: Point Defense (PD) Dashed Inner Ring (Violet-Gold)
        if (hasPd) {
            GL11.glLineWidth(1.3f);
            GL11.glColor4f(0.92f, 0.78f, 1.00f, 0.42f * readinessFade);
            GL11.glBegin(GL11.GL_LINES);
            for (int i = 0; i < RENDER_CONTOUR_STEPS; i += 2) {
                int next = (i + 1) % RENDER_CONTOUR_STEPS;
                GL11.glVertex2f(pdX[i], pdY[i]);
                GL11.glVertex2f(pdX[next], pdY[next]);
            }
            GL11.glEnd();
        }
    }

    private static void renderDivisionCordonIndicators(
            CombatEngineAPI engine,
            BattleGroupState group,
            boolean isEnemy,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        if (group == null || group.divisions.isEmpty()) return;
        FogOfWarAPI playerFog = engine.getFogOfWar(0);

        for (DivisionState div : group.divisions) {
            if (!div.isCombatEffective()) continue;
            if (div.crossfireCordonIndex < 0.35f) continue;
            if (isEnemy && !OtcAI.isDivisionVisibleToSide(engine, 0, div)) continue;

            Vector2f cWorld = isEnemy ? OtcAI.getVisibleCentroidForSide(engine, 0, div) : div.centroid;
            if (isEnemy && playerFog != null && !playerFog.isVisible(cWorld)) continue;

            float cx = offsetX + cWorld.x * scale;
            float cy = offsetY + cWorld.y * scale;
            float ringRadPx = Math.max(22f, Math.min(72f, div.spreadRadius * scale * 0.45f));

            boolean isUCordon = div.crossfireCordonIndex >= 0.60f;
            float r = 1.0f;
            float g = isUCordon ? 0.22f : 0.62f;
            float b = 0.15f;
            float a = Math.min(0.85f, (0.38f + div.crossfireCordonIndex * 0.45f) * fade);

            // Dashed warning ring around division centroid caught in L/U crossfire
            GL11.glLineWidth(2.2f);
            GL11.glColor4f(r, g, b, a);
            GL11.glBegin(GL11.GL_LINES);
            int segs = 24;
            for (int i = 0; i < segs; i += 2) {
                float a0 = (float) (i * 2.0 * Math.PI / segs);
                float a1 = (float) ((i + 1) * 2.0 * Math.PI / segs);
                GL11.glVertex2f(cx + (float) Math.cos(a0) * ringRadPx, cy + (float) Math.sin(a0) * ringRadPx);
                GL11.glVertex2f(cx + (float) Math.cos(a1) * ringRadPx, cy + (float) Math.sin(a1) * ringRadPx);
            }
            GL11.glEnd();
        }
    }

    private static void renderDivisionCordonLabels(
            CombatEngineAPI engine,
            BattleGroupState group,
            boolean isEnemy,
            float scale,
            float offsetX,
            float offsetY,
            float fade) {

        if (group == null || group.divisions.isEmpty() || Global.getSettings() == null) return;
        for (DivisionState div : group.divisions) {
            if (!div.isCombatEffective() || div.crossfireCordonIndex < 0.35f) continue;
            if (isEnemy && !OtcAI.isDivisionVisibleToSide(engine, 0, div)) continue;

            Vector2f cWorld = isEnemy ? OtcAI.getVisibleCentroidForSide(engine, 0, div) : div.centroid;
            float cx = offsetX + cWorld.x * scale;
            float cy = offsetY + cWorld.y * scale;

            String tag = (div.crossfireCordonIndex >= 0.60f)
                    ? String.format(Locale.ROOT, "U-CORDON CROSSFIRE (%.0f%%)", div.crossfireCordonIndex * 100f)
                    : String.format(Locale.ROOT, "L-CORDON CROSSFIRE (%.0f%%)", div.crossfireCordonIndex * 100f);
            Color col = (div.crossfireCordonIndex >= 0.60f)
                    ? new Color(255, 90, 75, 235)
                    : new Color(255, 185, 70, 230);

            LabelAPI lbl = getCachedLabel("cordon_" + div.id, tag, Fonts.DEFAULT_SMALL, col);
            if (lbl != null) {
                float tw = lbl.computeTextWidth(tag);
                drawLabel(lbl, tag, cx - tw * 0.5f, cy - 28f, fade * 0.92f);
            }
        }
    }

    /**
     * Renders the compact right-side toggle button ("FIREPOWER: OFF" / "FIREPOWER: ON")
     * and, when enabled, a compact 3-line key underneath it.
     */
    public static void renderToggleButtonAndLegendUI(float fade) {
        if (Global.getSettings() == null || fade <= 0.05f) return;

        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        float btnX = screenW - BTN_WIDTH - BTN_RIGHT_MARGIN;
        float btnY = screenH * BTN_Y_FRACTION;

        boolean hovered = (lastMouseX >= btnX && lastMouseX <= btnX + BTN_WIDTH
                && lastMouseY >= btnY && lastMouseY <= btnY + BTN_HEIGHT);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // Button background fill
        if (overlayVisible) {
            GL11.glColor4f(0.08f, 0.22f, 0.30f, (hovered ? 0.94f : 0.86f) * fade);
        } else {
            GL11.glColor4f(0.05f, 0.09f, 0.14f, (hovered ? 0.88f : 0.76f) * fade);
        }
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(btnX, btnY);
        GL11.glVertex2f(btnX + BTN_WIDTH, btnY);
        GL11.glVertex2f(btnX + BTN_WIDTH, btnY + BTN_HEIGHT);
        GL11.glVertex2f(btnX, btnY + BTN_HEIGHT);
        GL11.glEnd();

        // Left status indicator bar
        if (overlayVisible) {
            GL11.glColor4f(0.25f, 0.92f, 1.00f, 0.95f * fade);
        } else {
            GL11.glColor4f(0.42f, 0.50f, 0.58f, 0.75f * fade);
        }
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(btnX + 3f, btnY + 4f);
        GL11.glVertex2f(btnX + 8f, btnY + 4f);
        GL11.glVertex2f(btnX + 8f, btnY + BTN_HEIGHT - 4f);
        GL11.glVertex2f(btnX + 3f, btnY + BTN_HEIGHT - 4f);
        GL11.glEnd();

        // Button border
        GL11.glLineWidth(hovered ? 1.8f : 1.2f);
        if (overlayVisible) {
            GL11.glColor4f(0.32f, 0.88f, 1.00f, (hovered ? 1.0f : 0.85f) * fade);
        } else {
            GL11.glColor4f(0.45f, 0.58f, 0.68f, (hovered ? 0.90f : 0.65f) * fade);
        }
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(btnX, btnY);
        GL11.glVertex2f(btnX + BTN_WIDTH, btnY);
        GL11.glVertex2f(btnX + BTN_WIDTH, btnY + BTN_HEIGHT);
        GL11.glVertex2f(btnX, btnY + BTN_HEIGHT);
        GL11.glEnd();

        // Compact legend box below button when ON
        float legH = 54f;
        float legY = btnY - legH - 4f;
        if (overlayVisible) {
            GL11.glColor4f(0.04f, 0.08f, 0.12f, 0.80f * fade);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(btnX, legY);
            GL11.glVertex2f(btnX + BTN_WIDTH, legY);
            GL11.glVertex2f(btnX + BTN_WIDTH, legY + legH);
            GL11.glVertex2f(btnX, legY + legH);
            GL11.glEnd();

            GL11.glLineWidth(1.0f);
            GL11.glColor4f(0.25f, 0.65f, 0.82f, 0.55f * fade);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(btnX, legY);
            GL11.glVertex2f(btnX + BTN_WIDTH, legY);
            GL11.glVertex2f(btnX + BTN_WIDTH, legY + legH);
            GL11.glVertex2f(btnX, legY + legH);
            GL11.glEnd();
        }

        GL11.glPopMatrix();
        GL11.glPopAttrib();

        // Render button label text
        String btnText = overlayVisible ? "FIREPOWER: ON" : "FIREPOWER: OFF";
        Color textCol = overlayVisible
                ? new Color(165, 245, 255, 245)
                : new Color(190, 205, 220, 225);
        LabelAPI btnLabel = getCachedLabel("fp_btn_lbl", btnText, Fonts.DEFAULT_SMALL, textCol);
        if (btnLabel != null) {
            float tw = btnLabel.computeTextWidth(btnText);
            float th = btnLabel.computeTextHeight(btnText);
            float tx = btnX + 10f + (BTN_WIDTH - 10f - tw) * 0.5f;
            float ty = btnY + (BTN_HEIGHT - th) * 0.5f + 2f;
            drawLabel(btnLabel, btnText, tx, ty, fade);
        }

        if (overlayVisible) {
            String s1 = "Outer: Sustained DoT";
            String s2 = "Mid/Core: 5s DoT / Alpha";
            String s3 = "Dashed: Point Defense";
            LabelAPI l1 = getCachedLabel("fp_leg_1", s1, Fonts.DEFAULT_SMALL, new Color(130, 215, 250, 230));
            LabelAPI l2 = getCachedLabel("fp_leg_2", s2, Fonts.DEFAULT_SMALL, new Color(255, 205, 115, 230));
            LabelAPI l3 = getCachedLabel("fp_leg_3", s3, Fonts.DEFAULT_SMALL, new Color(230, 195, 255, 230));
            if (l1 != null) drawLabel(l1, s1, btnX + 6f, legY + legH - 18f, fade * 0.92f);
            if (l2 != null) drawLabel(l2, s2, btnX + 6f, legY + legH - 34f, fade * 0.92f);
            if (l3 != null) drawLabel(l3, s3, btnX + 6f, legY + legH - 50f, fade * 0.92f);
        }
    }

    private static void drawLabel(LabelAPI lbl, String text, float x, float y, float alpha) {
        if (lbl == null) return;
        float tw = lbl.computeTextWidth(text);
        float th = lbl.computeTextHeight(text);
        lbl.getPosition().setSize(tw + 10f, th + 4f);
        lbl.getPosition().setLocation(x, y);
        lbl.render(alpha);
    }

    private static LabelAPI getCachedLabel(String key, String text, String font, Color color) {
        try {
            LabelAPI lbl = uiLabelCache.get(key);
            if (lbl == null) {
                lbl = Global.getSettings().createLabel(text, font);
                uiLabelCache.put(key, lbl);
            } else {
                lbl.setText(text);
            }
            lbl.setColor(color);
            return lbl;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // =========================================================================
    // Math & Weapon Helpers
    // =========================================================================

    private static boolean isUsableCombatWeapon(WeaponAPI w) {
        if (w == null || w.isDecorative()) return false;
        WeaponType type = w.getType();
        return type != WeaponType.DECORATIVE
                && type != WeaponType.LAUNCH_BAY
                && type != WeaponType.SYSTEM
                && type != WeaponType.STATION_MODULE;
    }

    private static float weaponSizeWeight(WeaponSize size) {
        if (size == null) return 1f;
        switch (size) {
            case LARGE:
                return 7f;
            case MEDIUM:
                return 3f;
            case SMALL:
            default:
                return 1f;
        }
    }

    private static float blendAsymmetric(float current, float target, float alphaRise, float alphaFall) {
        float a = (target >= current) ? alphaRise : alphaFall;
        return current + (target - current) * a;
    }

    public static float normalizeAngle360(float deg) {
        float a = deg % 360f;
        if (a < 0f) a += 360f;
        return a;
    }

    public static float shortestAngleDiffDeg(float fromDeg, float toDeg) {
        float diff = (toDeg - fromDeg) % 360f;
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;
        return diff;
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
