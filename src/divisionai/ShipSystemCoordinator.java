package divisionai;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.DivisionIntentType;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionModel.OtcOrderType;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.DivisionSetpieceAttack;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.ShipSkillCoordinationMode;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Generic Ship System Classifier & Division / OTC Positive-Control Skill Coordinator.
 *
 * Classifies any vanilla or modded ShipSystemAPI / ShipSystemSpecAPI into generic tactical categories
 * based on mechanical behavior (linear vector, kinetic ramming, omnidirectional agility, blink/displacer,
 * offensive weapon amplifier, defensive bulwark, tactical deployable) without hardcoding mod lists.
 *
 * When {@link ShipSkillCoordinationMode#SYNCHRONIZED} is enabled on a division (the default):
 *  1. Prevents premature solo linear bursts during Opening Scouting / Standoff / Screening / Reserve / Close-Up.
 *  2. Allows ships to marshal freely into formation using movement skills when out of enemy fire (with automatic
 *     toggle cutoff at braking distance so they never overshoot their formation slot), while enforcing cautious
 *     shield-ready station-keeping when under enemy fire.
 *  3. Orchestrates synchronized Division Commander and OTC Positive-Control maneuvers:
 *     - LINEAR_RAM_DRIVE divisions (e.g. Rambunctious-class destroyers with JYD_ramdrive, HMI drill attack,
 *       Iron Shell rampage drive) marshal at an Initial Point, align bows, ignite ram drives in unison, and
 *       plow through the target enemy ship at multiplied mass (cutting toggle drives after pass-through so they
 *       wheel around instead of flying 3,000u off-map).
 *     - LINEAR_THRUST_DRIVE divisions (e.g. Eagle, Falcon, Dominator, Mora with Burn Drive / Micro Burn / Orion)
 *       ignite linear drives in unison to close gaps or shift flanks, then automatically cut off at weapon
 *       standoff range + braking margin so they drop into a dressed firing line without slamming into the enemy.
 *     - OFFENSIVE_AMP, BLINK_DISPLACER, and DEFENSIVE_BULWARK systems synchronize on ToT Alpha Strikes,
 *       Wall Salvo Passes, pounces, and focus-fire tanking.
 */
public final class ShipSystemCoordinator {

    public enum ShipSkillCategory {
        NONE("None"),
        LINEAR_RAM_DRIVE("Linear Ramming Drive"),
        LINEAR_THRUST_DRIVE("Linear Thrust Vector"),
        OMNI_AGILITY_JETS("Omnidirectional Agility Jets"),
        BLINK_DISPLACER("Blink / Phase Displacer"),
        OFFENSIVE_AMP("Offensive Weapon Amplifier"),
        DEFENSIVE_BULWARK("Defensive Bulwark / Screen"),
        TACTICAL_DEPLOYABLE("Tactical Deployable / EMP");

        public final String displayName;

        ShipSkillCategory(String displayName) {
            this.displayName = displayName;
        }

        public boolean isLinearVector() {
            return this == LINEAR_RAM_DRIVE || this == LINEAR_THRUST_DRIVE;
        }

        public boolean isMovementSkill() {
            return this == LINEAR_RAM_DRIVE
                    || this == LINEAR_THRUST_DRIVE
                    || this == OMNI_AGILITY_JETS
                    || this == BLINK_DISPLACER;
        }
    }

    public static final class ShipSkillProfile {
        public final String systemId;
        public final String displayName;
        public final ShipSkillCategory category;
        public final boolean isDesignedToRam;
        public final boolean isToggle;
        public final boolean alwaysAccelerate;
        public final boolean dropsShield;
        public final boolean suppressesWeapons;
        public final float flameoutOnImpactChance;
        public final float chargeUpDuration;
        public final float activeDuration;
        public final float chargeDownDuration;
        public final float estimatedSpeedBonus;
        public final float estimatedBurstDistance;

        public ShipSkillProfile(
                String systemId,
                String displayName,
                ShipSkillCategory category,
                boolean isDesignedToRam,
                boolean isToggle,
                boolean alwaysAccelerate,
                boolean dropsShield,
                boolean suppressesWeapons,
                float flameoutOnImpactChance,
                float chargeUpDuration,
                float activeDuration,
                float chargeDownDuration,
                float estimatedSpeedBonus,
                float estimatedBurstDistance) {
            this.systemId = systemId != null ? systemId : "";
            this.displayName = displayName != null ? displayName : "";
            this.category = category != null ? category : ShipSkillCategory.NONE;
            this.isDesignedToRam = isDesignedToRam;
            this.isToggle = isToggle;
            this.alwaysAccelerate = alwaysAccelerate;
            this.dropsShield = dropsShield;
            this.suppressesWeapons = suppressesWeapons;
            this.flameoutOnImpactChance = flameoutOnImpactChance;
            this.chargeUpDuration = chargeUpDuration;
            this.activeDuration = activeDuration;
            this.chargeDownDuration = chargeDownDuration;
            this.estimatedSpeedBonus = estimatedSpeedBonus;
            this.estimatedBurstDistance = estimatedBurstDistance;
        }
    }

    private static final ShipSkillProfile NONE_PROFILE = new ShipSkillProfile(
            "", "None", ShipSkillCategory.NONE, false, false, false, false, false, 0f, 0f, 0f, 0f, 0f, 0f);

    private static final Map<ShipAPI, ShipSkillProfile> PROFILE_CACHE = new WeakHashMap<>();

    private ShipSystemCoordinator() {
    }

    /**
     * Classifies a ship's primary ShipSystemAPI generically from its ShipSystemSpecAPI, JSON aiHints,
     * mechanical flags, and hull characteristics. Cached per ShipAPI instance.
     */
    public static synchronized ShipSkillProfile getSkillProfile(ShipAPI ship) {
        if (ship == null) return NONE_PROFILE;
        ShipSkillProfile cached = PROFILE_CACHE.get(ship);
        if (cached != null) return cached;

        ShipSkillProfile computed = classifyShipSystem(ship);
        PROFILE_CACHE.put(ship, computed);
        return computed;
    }

    public static ShipSkillProfile classifyShipSystem(ShipAPI ship) {
        if (ship == null) return NONE_PROFILE;
        ShipSystemAPI sys = ship.getSystem();
        if (sys == null) return NONE_PROFILE;

        String sysId = sys.getId() != null ? sys.getId().trim() : "";
        if (sysId.isEmpty()) return NONE_PROFILE;
        String sysIdLower = sysId.toLowerCase(Locale.ROOT);
        if (sysIdLower.contains("traveldrive")) {
            return NONE_PROFILE;
        }

        String dispName = sys.getDisplayName() != null ? sys.getDisplayName().trim() : sysId;
        String dispLower = dispName.toLowerCase(Locale.ROOT);

        ShipSystemSpecAPI spec = null;
        try {
            spec = sys.getSpecAPI();
        } catch (Throwable ignored) {
        }

        boolean alwaysAcc = false;
        boolean clampTurn = false;
        boolean turningAllowed = true;
        boolean shieldAllowed = true;
        boolean firingAllowed = true;
        boolean isToggle = false;
        boolean isPhaseCloak = false;
        float flameoutChance = -1f;
        float inDur = Math.max(0f, sys.getChargeUpDur());
        float actDur = Math.max(0f, sys.getChargeActiveDur());
        float outDur = Math.max(0f, sys.getChargeDownDur());
        String statsScriptLower = "";
        String aiScriptLower = "";
        String jsonType = "";
        String jsonAiType = "";
        float activeSpeedInc = 0f;
        float burstPotential = 0f;
        float avgSpeedInc = 0f;

        if (spec != null) {
            try {
                alwaysAcc = spec.isAlwaysAccelerate();
                clampTurn = spec.isClampTurnRateAfter();
                turningAllowed = spec.isTurningAllowed();
                shieldAllowed = spec.isShieldAllowed();
                firingAllowed = spec.isFiringAllowed();
                isToggle = spec.isToggle();
                isPhaseCloak = spec.isPhaseCloak();
                flameoutChance = spec.getFlameoutOnImpactChance();
                if (inDur <= 0f) inDur = Math.max(0f, spec.getIn());
                if (actDur <= 0f) actDur = Math.max(0f, spec.getActive());
                if (outDur <= 0f) outDur = Math.max(0f, spec.getOut());
                if (spec.getStatsScriptClassName() != null) {
                    statsScriptLower = spec.getStatsScriptClassName().toLowerCase(Locale.ROOT);
                }
                if (spec.getAIScriptClassName() != null) {
                    aiScriptLower = spec.getAIScriptClassName().toLowerCase(Locale.ROOT);
                }
                Object specJson = JsonReflectBridge.getShipSystemSpecJson(spec);
                if (specJson != null) {
                    jsonType = JsonReflectBridge.optString(specJson, "type", "").toUpperCase(Locale.ROOT);
                    jsonAiType = JsonReflectBridge.optString(specJson, "aiType", "").toUpperCase(Locale.ROOT);
                    Object hintsObj = JsonReflectBridge.optJSONObject(specJson, "aiHints");
                    if (hintsObj != null) {
                        activeSpeedInc = JsonReflectBridge.optFloat(hintsObj, "activeSpeedIncrease", 0f);
                        burstPotential = JsonReflectBridge.optFloat(hintsObj, "burstMovementPotential", 0f);
                        avgSpeedInc = JsonReflectBridge.optFloat(hintsObj, "averageSpeedIncrease", 0f);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // Fallback heuristics when running in headless mocks without full .system JSON loaded
        if (jsonType.isEmpty() && jsonAiType.isEmpty()) {
            if (sysIdLower.contains("ramdrive") || sysIdLower.contains("rampagedrive") || sysIdLower.contains("drill_attack")) {
                jsonType = "ENGINE_MOD";
                jsonAiType = "BURN_DRIVE_TOGGLE";
                alwaysAcc = true;
                clampTurn = true;
                turningAllowed = false;
                shieldAllowed = false;
                firingAllowed = false;
                isToggle = sysIdLower.contains("ramdrive");
                flameoutChance = 0f;
                if (actDur <= 0f) actDur = sysIdLower.contains("ramdrive") ? 9.0f : 3.5f;
                activeSpeedInc = sysIdLower.contains("ramdrive") ? 400f : 200f;
                burstPotential = 600f;
            } else if (sysIdLower.contains("burndrive") || sysIdLower.contains("combat_burn")
                    || sysIdLower.contains("microburn") || sysIdLower.contains("orion_device")
                    || sysIdLower.contains("torchdrive") || sysIdLower.contains("turboburn")
                    || sysIdLower.contains("plasmaburn") || sysIdLower.contains("infernium")) {
                jsonType = "ENGINE_MOD";
                jsonAiType = sysIdLower.contains("burndrive") || sysIdLower.contains("combat_burn") ? "BURN_DRIVE_TOGGLE" : "BURN_DRIVE";
                alwaysAcc = !sysIdLower.contains("orion");
                clampTurn = true;
                turningAllowed = false;
                shieldAllowed = sysIdLower.contains("orion") || sysIdLower.contains("combat_burn");
                firingAllowed = true;
                isToggle = jsonAiType.contains("TOGGLE");
                flameoutChance = 0.5f;
                if (actDur <= 0f) actDur = sysIdLower.contains("microburn") ? 1.5f : 3.5f;
                activeSpeedInc = 200f;
                burstPotential = 400f;
            } else if (sysIdLower.contains("maneuvering") || sysIdLower.contains("plasmajets")
                    || sysIdLower.contains("temporalshell") || sysIdLower.contains("drift")) {
                jsonType = "ENGINE_MOD";
                jsonAiType = "MANEUVERING_JETS";
                alwaysAcc = false;
                turningAllowed = true;
                if (actDur <= 0f) actDur = 3.0f;
                activeSpeedInc = 100f;
            } else if (sysIdLower.contains("displacer") || sysIdLower.contains("teleporter")
                    || sysIdLower.contains("phaseskimmer") || sysIdLower.contains("nova_burst")
                    || sysIdLower.contains("flicker")) {
                jsonType = "DISPLACER";
                jsonAiType = "PHASE_DISPLACER";
                if (actDur <= 0f) actDur = 0.5f;
                burstPotential = 350f;
            } else if (sysIdLower.contains("ammofeed") || sysIdLower.contains("high_energy_focus")
                    || sysIdLower.contains("lidar") || sysIdLower.contains("fastmissileracks")
                    || sysIdLower.contains("entropy") || sysIdLower.contains("targeting")
                    || sysIdLower.contains("overdrive")) {
                jsonType = "STAT_MOD";
                jsonAiType = "WEAPON_BOOST";
                if (actDur <= 0f) actDur = 5.0f;
            } else if (sysIdLower.contains("damper") || sysIdLower.contains("fortress")
                    || sysIdLower.contains("flare") || sysIdLower.contains("citadel")) {
                jsonType = "SHIELD_MOD";
                jsonAiType = "DAMPER_FIELD";
                if (actDur <= 0f) actDur = 5.0f;
            }
        }

        String hullIdLower = "";
        String hullNameLower = "";
        ShipHullSpecAPI hullSpec = ship.getHullSpec();
        if (hullSpec != null) {
            if (hullSpec.getHullId() != null) hullIdLower = hullSpec.getHullId().toLowerCase(Locale.ROOT);
            if (hullSpec.getHullName() != null) hullNameLower = hullSpec.getHullName().toLowerCase(Locale.ROOT);
        }

        // 1. Check Blink / Displacer / Teleporter
        boolean isBlink = "DISPLACER".equals(jsonType)
                || "TELEPORTER".equals(jsonType)
                || jsonAiType.contains("DISPLACER")
                || jsonAiType.contains("TELEPORTER")
                || sysIdLower.contains("displacer")
                || sysIdLower.contains("teleporter")
                || sysIdLower.contains("phaseskimmer")
                || sysIdLower.contains("nova_burst")
                || sysIdLower.contains("flicker")
                || sysIdLower.contains("minijump");

        // 2. Check Linear Vector (Burn Drive / Ram Drive / Surge / Lunge / Orion)
        boolean hasLinearAiOrFlags = alwaysAcc
                || jsonAiType.contains("BURN_DRIVE")
                || (clampTurn && ("ENGINE_MOD".equals(jsonType) || activeSpeedInc >= 100f || burstPotential >= 250f))
                || (!turningAllowed && ("ENGINE_MOD".equals(jsonType) || activeSpeedInc >= 80f || burstPotential >= 200f))
                || sysIdLower.contains("ramdrive")
                || sysIdLower.contains("rampagedrive")
                || sysIdLower.contains("drill_attack")
                || sysIdLower.contains("burndrive")
                || sysIdLower.contains("combat_burn")
                || sysIdLower.contains("microburn")
                || sysIdLower.contains("orion")
                || sysIdLower.contains("torchdrive")
                || sysIdLower.contains("turboburn")
                || sysIdLower.contains("lunge")
                || sysIdLower.contains("silverlight_dash");

        // 3. Distinguish Dedicated Ramming Linear Drive from Non-Ramming Linear Thrust Drive
        boolean hasRamNaming = sysIdLower.contains("ramdrive")
                || sysIdLower.contains("rampage")
                || sysIdLower.contains("drill_attack")
                || sysIdLower.contains("battering")
                || sysIdLower.contains("silverlight_dash")
                || dispLower.contains("ram drive")
                || dispLower.contains("ramming")
                || dispLower.contains("drill")
                || dispLower.contains("rampage")
                || statsScriptLower.contains("ramdrive")
                || statsScriptLower.contains("rampage")
                || statsScriptLower.contains("drillattack")
                || aiScriptLower.contains("ramdrive")
                || aiScriptLower.contains("ironrampage")
                || aiScriptLower.contains("drillattack")
                || hullIdLower.contains("rambunctious")
                || hullNameLower.contains("rambunctious");

        boolean hasZeroFlameoutWreckerProfile = hasLinearAiOrFlags
                && flameoutChance == 0f
                && alwaysAcc
                && (!shieldAllowed || ship.getShield() == null || ship.getShield().getType() == ShieldAPI.ShieldType.NONE)
                && !firingAllowed;

        boolean isDesignedToRam = !isBlink && hasLinearAiOrFlags && (hasRamNaming || hasZeroFlameoutWreckerProfile);

        ShipSkillCategory category;
        if (isBlink) {
            category = ShipSkillCategory.BLINK_DISPLACER;
        } else if (isDesignedToRam) {
            category = ShipSkillCategory.LINEAR_RAM_DRIVE;
        } else if (hasLinearAiOrFlags) {
            category = ShipSkillCategory.LINEAR_THRUST_DRIVE;
        } else if ("ENGINE_MOD".equals(jsonType)
                || jsonAiType.contains("MANEUVERING_JETS")
                || jsonAiType.contains("TEMPORAL_SHELL")
                || activeSpeedInc >= 40f
                || avgSpeedInc >= 40f
                || sysIdLower.contains("maneuvering")
                || sysIdLower.contains("plasmajets")
                || sysIdLower.contains("temporalshell")) {
            category = ShipSkillCategory.OMNI_AGILITY_JETS;
        } else if ("SHIELD_MOD".equals(jsonType)
                || "PHASE_CLOAK".equals(jsonType)
                || isPhaseCloak
                || jsonAiType.contains("DAMPER")
                || jsonAiType.contains("FORTRESS")
                || jsonAiType.contains("FLARE")
                || jsonAiType.contains("CANISTER_FLAK")
                || sysIdLower.contains("damper")
                || sysIdLower.contains("fortress")
                || sysIdLower.contains("flare")) {
            category = ShipSkillCategory.DEFENSIVE_BULWARK;
        } else if ("DRONE_LAUNCHER".equals(jsonType)
                || "EMP".equals(jsonType)
                || jsonAiType.contains("DRONE")
                || jsonAiType.contains("MINE_STRIKE")
                || jsonAiType.contains("RESERVE_DEPLOYMENT")
                || jsonAiType.contains("RECALL_DEVICE")
                || jsonAiType.contains("MOTE_ATTRACTOR")
                || jsonAiType.contains("INTERDICTOR")
                || jsonAiType.contains("EMP")) {
            category = ShipSkillCategory.TACTICAL_DEPLOYABLE;
        } else if ("STAT_MOD".equals(jsonType)
                || "WEAPON".equals(jsonType)
                || "AMMO_RELOAD".equals(jsonType)
                || "FAST_RELOAD".equals(jsonType)
                || jsonAiType.contains("WEAPON_BOOST")
                || jsonAiType.contains("LIDAR_ARRAY")
                || jsonAiType.contains("TARGETING_FEED")
                || jsonAiType.contains("AMMO_RELOAD")
                || jsonAiType.contains("FAST_RELOAD")
                || jsonAiType.contains("ENTROPY")
                || jsonAiType.contains("ACAUSAL")
                || sysIdLower.contains("ammofeed")
                || sysIdLower.contains("focus")
                || sysIdLower.contains("lidar")
                || sysIdLower.contains("racks")
                || sysIdLower.contains("forge")) {
            category = ShipSkillCategory.OFFENSIVE_AMP;
        } else {
            category = ShipSkillCategory.NONE;
        }

        float effSpeedBonus = activeSpeedInc > 0f
                ? Math.min(450f, activeSpeedInc)
                : (category.isLinearVector() ? 220f : (category == ShipSkillCategory.OMNI_AGILITY_JETS ? 85f : 0f));
        float effDur = Math.max(0.8f, actDur > 0f ? actDur : (category.isLinearVector() ? 3.0f : 1.5f));
        float estBurstDist = burstPotential > 0f && !category.isLinearVector()
                ? burstPotential
                : Math.max(burstPotential, (ship.getMaxSpeedWithoutBoost() + effSpeedBonus) * Math.min(effDur, 4.5f));

        return new ShipSkillProfile(
                sysId,
                dispName,
                category,
                isDesignedToRam,
                isToggle || jsonAiType.contains("TOGGLE"),
                alwaysAcc,
                !shieldAllowed,
                !firingAllowed,
                flameoutChance >= 0f ? flameoutChance : (isDesignedToRam ? 0f : 0.5f),
                inDur,
                actDur,
                outDur,
                effSpeedBonus,
                estBurstDist);
    }

    /**
     * Checks whether a ship's system can be activated right now (off cooldown, has ammo, not overloaded/venting,
     * and has sufficient flux headroom).
     */
    public static boolean isShipSystemReady(ShipAPI ship, ShipSkillProfile profile) {
        if (ship == null || !ship.isAlive() || ship.isHulk() || profile == null || profile.category == ShipSkillCategory.NONE) {
            return false;
        }
        if (ship.getFluxTracker() != null && ship.getFluxTracker().isOverloadedOrVenting()) {
            return false;
        }
        if (ship.getTravelDrive() != null && ship.getTravelDrive().isActive()) {
            return false;
        }
        ShipSystemAPI sys = ship.getSystem();
        if (sys == null || sys.isActive() || sys.isChargeup() || sys.isChargedown() || sys.isCoolingDown() || sys.isOutOfAmmo()) {
            return false;
        }
        float maxFlux = Math.max(1f, ship.getMaxFlux());
        float currFlux = ship.getCurrFlux();
        float estCost = sys.getFluxPerUse() + sys.getFluxPerSecond() * Math.min(2.5f, Math.max(0.5f, profile.activeDuration));
        return (currFlux + estCost) < maxFlux * 0.86f;
    }

    /**
     * Updates division-level skill capability counts and active linear-burn flags on every 0.5s telemetry pass.
     */
    public static void updateDivisionSkillTelemetry(DivisionState div) {
        if (div == null) return;
        List<ShipAPI> alive = div.getAliveMembers();
        int ramCnt = 0;
        int thrustCnt = 0;
        int offCnt = 0;
        int blinkCnt = 0;
        int skillCapable = 0;
        int skillReady = 0;
        boolean anyActiveLinear = false;

        for (int i = 0; i < alive.size(); i++) {
            ShipAPI s = alive.get(i);
            if (s == null) continue;
            ShipSkillProfile prof = getSkillProfile(s);
            if (prof.category == ShipSkillCategory.NONE) continue;
            skillCapable++;
            switch (prof.category) {
                case LINEAR_RAM_DRIVE:
                    ramCnt++;
                    break;
                case LINEAR_THRUST_DRIVE:
                    thrustCnt++;
                    break;
                case OFFENSIVE_AMP:
                    offCnt++;
                    break;
                case BLINK_DISPLACER:
                    blinkCnt++;
                    break;
                default:
                    break;
            }
            ShipSystemAPI sys = s.getSystem();
            if (sys != null && (sys.isActive() || sys.isChargeup() || sys.isChargedown())) {
                if (prof.category.isLinearVector()) {
                    anyActiveLinear = true;
                }
                skillReady++;
            } else if (isShipSystemReady(s, prof)) {
                skillReady++;
            }
        }

        div.ramCapableMemberCount = ramCnt;
        div.linearThrustMemberCount = thrustCnt;
        div.offensiveAmpMemberCount = offCnt;
        div.blinkMemberCount = blinkCnt;
        div.readySkillMemberRatio = skillCapable > 0 ? ((float) skillReady / skillCapable) : 0f;
        div.hasActiveLinearSkillBurn = anyActiveLinear;
    }

    /**
     * Returns true when a ship's linear movement skill (such as JYD_RamDriveAI or vanilla BurnDriveAI)
     * triggers on {@link AIFlags#MANEUVER_TARGET} and the division is currently in a posture where solo
     * forward charges must be suppressed (Scouting, Standoff, Screening, Reserve, Marshal, Close-Up,
     * or Stage 1 Setpiece dressing before synchronized ignition).
     */
    public static boolean shouldSuppressManeuverTargetForLinearSkill(
            ShipAPI ship,
            DivisionState div,
            BattleGroupState myGroup,
            float now) {
        if (ship == null || div == null) return false;
        if (div.shipSkillCoordination != ShipSkillCoordinationMode.SYNCHRONIZED) {
            return false;
        }
        ShipSkillProfile prof = getSkillProfile(ship);
        if (!prof.category.isLinearVector()) {
            return false;
        }
        // If the division is actively executing a Stage 2 synchronized charge/burn, allow MANEUVER_TARGET
        if (div.synchronizedSkillManeuverActive && div.synchronizedSkillStage == 2) {
            return false;
        }
        if (div.activeSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE && div.setpieceStage == 2) {
            return false;
        }
        DivisionMissionType activeMission = DivisionCommanderAI.resolveActiveMission(div, now);
        if (activeMission == DivisionMissionType.SCOUTING
                || activeMission == DivisionMissionType.STANDOFF_ENGAGEMENT
                || activeMission == DivisionMissionType.SCREEN
                || activeMission == DivisionMissionType.RESERVE) {
            return true;
        }
        DivisionIntentType intent = div.currentIntent;
        if (intent == DivisionIntentType.TAKE_SCREENING_STATION
                || intent == DivisionIntentType.HOLD_IN_RESERVE
                || intent == DivisionIntentType.MARSHAL_FOR_STRIKE
                || intent == DivisionIntentType.CLOSE_UP
                || intent == DivisionIntentType.HAUL_OUT_TO_VENT
                || intent == DivisionIntentType.BREAK_OFF_AND_RETIRE
                || intent == DivisionIntentType.TRADE_SPACE_FOR_TIME
                || intent == DivisionIntentType.FEIGNED_RETREAT) {
            return true;
        }
        // While dressing in Stage 1 of a Setpiece or Synchronized Skill Maneuver, hold solo linear bursts
        // so one ship doesn't zoom ahead before its wingmen have formed up!
        if ((div.activeSetpieceAttack != DivisionSetpieceAttack.NONE && div.setpieceStage == 1)
                || (div.synchronizedSkillManeuverActive && div.synchronizedSkillStage == 1)) {
            return true;
        }
        return false;
    }

    /**
     * Evaluates and updates Division-level and OTC Positive-Control synchronized skill maneuvers at the 0.5s C2 tick.
     */
    public static void updateDivisionSkillManeuverState(
            CombatEngineAPI engine,
            DivisionState div,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            ShipAPI focusEnemyShip,
            float now) {
        if (div == null) return;
        updateDivisionSkillTelemetry(div);

        if (div.shipSkillCoordination != ShipSkillCoordinationMode.SYNCHRONIZED) {
            div.synchronizedSkillManeuverActive = false;
            div.synchronizedSkillStage = 0;
            div.activeSkillManeuverLabel = "";
            return;
        }

        List<ShipAPI> alive = div.getAliveMembers();
        if (alive.isEmpty()) {
            div.synchronizedSkillManeuverActive = false;
            div.synchronizedSkillStage = 0;
            div.activeSkillManeuverLabel = "";
            return;
        }

        boolean positiveControlOtc = myGroup != null && myGroup.authority == OtcAuthority.POSITIVE_CONTROL;
        boolean otcStrikeOrder = div.otcOrder != null && div.otcOrder.isActive(now)
                && (div.otcOrder.type == OtcOrderType.EXECUTE_STRIKE
                || div.otcOrder.type == OtcOrderType.GENERAL_CHASE
                || (positiveControlOtc && div.otcOrder.type == OtcOrderType.ENGAGE_ASSIGNED));

        boolean hasRamPack = div.ramCapableMemberCount >= 1
                && (div.ramCapableMemberCount * 2 >= alive.size() || alive.size() == 1);
        boolean hasLinearThrustPack = !hasRamPack
                && div.linearThrustMemberCount >= 1
                && (div.linearThrustMemberCount * 2 >= alive.size() || alive.size() == 1);
        boolean hasOffensiveAmpPack = !hasRamPack && !hasLinearThrustPack
                && div.offensiveAmpMemberCount >= 2;

        // If this is a light/destroyer ramming pack operating in a multi-division battle group with heavier anvil
        // divisions, do not launch a premature solo suicide charge 2,000u ahead of the friendly main line!
        boolean anvilSupportReady = true;
        boolean hasHeavyAnvil = false;
        boolean heavyAnvilEngaged = false;
        if (hasRamPack && myGroup != null && myGroup.divisions.size() >= 2 && focusEnemyShip != null) {
            float dSelf = DivisionCommanderAI.distance(div.centroid, focusEnemyShip.getLocation());
            for (int i = 0; i < myGroup.divisions.size(); i++) {
                DivisionState other = myGroup.divisions.get(i);
                if (other == null || other == div || !other.isCombatEffective() || other.centroid == null) continue;
                if (other.ramCapableMemberCount * 2 < other.getAliveMembers().size()) {
                    hasHeavyAnvil = true;
                    float dOther = DivisionCommanderAI.distance(other.centroid, focusEnemyShip.getLocation());
                    if (dOther <= dSelf + 480f || dOther <= 1750f) {
                        heavyAnvilEngaged = true;
                        break;
                    }
                }
            }
            if (hasHeavyAnvil && !heavyAnvilEngaged && now < 24f && dSelf > 1150f) {
                anvilSupportReady = false;
            }
        }

        boolean positiveControlHammerReady = positiveControlOtc && hasRamPack && heavyAnvilEngaged && now >= 12f;
        DivisionMissionType activeMission = DivisionCommanderAI.resolveActiveMission(div, now);
        boolean postureForbidsOffensiveCharge = !otcStrikeOrder && !positiveControlHammerReady
                && (activeMission == DivisionMissionType.SCOUTING
                || activeMission == DivisionMissionType.STANDOFF_ENGAGEMENT
                || activeMission == DivisionMissionType.SCREEN
                || activeMission == DivisionMissionType.RESERVE
                || div.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                || div.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE
                || div.currentIntent == DivisionIntentType.HOLD_IN_RESERVE
                || div.currentIntent == DivisionIntentType.TAKE_SCREENING_STATION);

        if (postureForbidsOffensiveCharge || focusEnemyShip == null || !focusEnemyShip.isAlive() || focusEnemyShip.isHulk()) {
            div.synchronizedSkillManeuverActive = false;
            div.synchronizedSkillStage = 0;
            div.activeSkillManeuverLabel = "";
            return;
        }

        float distToTarget = DivisionCommanderAI.distance(div.centroid, focusEnemyShip.getLocation());

        // Determine if Division Commander or Positive-Control OTC wants a synchronized linear or amp maneuver
        boolean wantRamCharge = hasRamPack
                && anvilSupportReady
                && div.avgFluxLevel < 0.58f
                && distToTarget >= 380f
                && distToTarget <= 2250f
                && (div.readySkillMemberRatio >= 0.50f || div.hasActiveLinearSkillBurn)
                && (positiveControlOtc
                || otcStrikeOrder
                || div.currentIntent == DivisionIntentType.PRESS_HOME
                || div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE
                || div.currentIntent == DivisionIntentType.CLOSE_AND_ENGAGE
                || div.activeSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE);

        float desiredStopDist = Math.max(580f, div.standoffRange * 0.92f);
        boolean wantLinearGapClose = hasLinearThrustPack
                && div.avgFluxLevel < 0.58f
                && distToTarget >= desiredStopDist + 380f
                && distToTarget <= 2650f
                && (div.readySkillMemberRatio >= 0.50f || div.hasActiveLinearSkillBurn)
                && (positiveControlOtc
                || otcStrikeOrder
                || div.currentIntent == DivisionIntentType.CLOSE_AND_ENGAGE
                || div.currentIntent == DivisionIntentType.PRESS_HOME
                || div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE
                || div.currentIntent == DivisionIntentType.WORK_FLANK
                || div.currentIntent == DivisionIntentType.REINFORCE_DIVISION);

        float effGunRange = Math.min(1250f, Math.max(700f, div.avgEngageRange * 0.92f));
        boolean wantOffensiveAmpVolley = hasOffensiveAmpPack
                && div.avgFluxLevel < 0.55f
                && distToTarget <= effGunRange
                && div.readySkillMemberRatio >= 0.50f;

        if (!wantRamCharge && !wantLinearGapClose && !wantOffensiveAmpVolley) {
            if (div.synchronizedSkillManeuverActive && !div.hasActiveLinearSkillBurn) {
                div.lastSynchronizedSkillEndTime = now;
                div.synchronizedSkillManeuverActive = false;
                div.synchronizedSkillStage = 0;
                div.activeSkillManeuverLabel = "";
            }
            return;
        }

        boolean underFire = div.incomingInRangeFirepower > 2.5f
                || div.localEnemyStrength >= 6f
                || distToTarget <= 1650f;

        if (!div.synchronizedSkillManeuverActive) {
            if ((now - div.lastSynchronizedSkillEndTime) < 6.0f && !otcStrikeOrder) {
                return;
            }
            div.synchronizedSkillManeuverActive = true;
            if (wantOffensiveAmpVolley) {
                div.synchronizedSkillStage = 2;
                div.activeSkillManeuverLabel = "Sync Firepower Amp";
            } else {
                // Ram charges marshal in Stage 1 while closing from 2,250u down to 1,780u
                boolean mustMarshalFirst = (alive.size() >= 2 && div.stationKeepingRatio > 0.85f)
                        || (wantRamCharge && distToTarget > 1780f);
                div.synchronizedSkillStage = mustMarshalFirst ? 1 : 2;
                div.activeSkillManeuverLabel = wantRamCharge
                        ? (div.synchronizedSkillStage == 1
                                ? (underFire ? "Cautious Marshal (Ram)" : "Free Marshal (Ram)")
                                : "Sync Ram Charge")
                        : "Sync Burn Approach";
            }
            div.synchronizedSkillStageStartTime = now;
        }

        float stageElapsed = Math.max(0f, now - div.synchronizedSkillStageStartTime);
        if (div.synchronizedSkillStage == 1) {
            // Stage 1: Marshal into formation (freely out of fire, cautiously under fire) & align bows toward target
            float reqAngle = underFire ? 20f : 26f;
            float reqSkRatio = underFire ? 0.95f : 1.15f;
            int alignedCount = countMembersAlignedToPoint(alive, focusEnemyShip.getLocation(), reqAngle);
            boolean wellDressed = div.stationKeepingRatio <= reqSkRatio;
            boolean bowsAligned = alignedCount >= Math.max(1, (alive.size() * 2 + 1) / 3);
            boolean inIgniteRange = !wantRamCharge || distToTarget <= (underFire ? 1580f : 1720f);
            if (inIgniteRange && ((wellDressed && bowsAligned) || stageElapsed >= (underFire ? 2.0f : 1.5f) || distToTarget <= 1150f)) {
                div.synchronizedSkillStage = 2;
                div.synchronizedSkillStageStartTime = now;
                div.activeSkillManeuverLabel = wantRamCharge ? "Sync Ram Charge" : "Sync Burn Approach";
                // If another friendly ramming division is marshaling nearby on the same target, synchronize its Stage 2 ignition!
                if (wantRamCharge && myGroup != null) {
                    for (int i = 0; i < myGroup.divisions.size(); i++) {
                        DivisionState peer = myGroup.divisions.get(i);
                        if (peer == null || peer == div || !peer.isCombatEffective()) continue;
                        if (peer.shipSkillCoordination == ShipSkillCoordinationMode.SYNCHRONIZED
                                && peer.ramCapableMemberCount >= 1
                                && peer.centroid != null
                                && DivisionCommanderAI.distance(peer.centroid, focusEnemyShip.getLocation()) <= 1850f) {
                            peer.synchronizedSkillManeuverActive = true;
                            peer.synchronizedSkillStage = 2;
                            peer.synchronizedSkillStageStartTime = now;
                            peer.activeSkillManeuverLabel = "Sync Ram Charge";
                        }
                    }
                }
            }
        } else if (div.synchronizedSkillStage == 2) {
            float maxStage2Dur = wantRamCharge ? 5.5f : (wantOffensiveAmpVolley ? 4.0f : 7.5f);
            if (stageElapsed >= maxStage2Dur || (!div.hasActiveLinearSkillBurn && stageElapsed >= 2.2f && div.readySkillMemberRatio < 0.25f)) {
                div.lastSynchronizedSkillEndTime = now;
                div.synchronizedSkillManeuverActive = false;
                div.synchronizedSkillStage = 0;
                div.activeSkillManeuverLabel = "";
            }
        }
    }

    /**
     * Runs every frame (60 Hz) in both {@link DivisionAICombatPlugin#advance} and {@code DistilledBattleArena.step}
     * to enforce frame-accurate ship system discipline, out-of-fire marshaling burns, standoff/overshoot toggle
     * cutoffs, and synchronized division/OTC skill activation.
     */
    public static void advanceEveryFrame(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            BattleGroupState alliedAiGroup,
            BattleGroupState enemyGroup,
            float now) {
        if (engine == null || engine.isPaused()) return;
        if (playerGroup != null && !playerGroup.divisions.isEmpty()) {
            advanceGroupEveryFrame(engine, playerGroup, enemyGroup, now);
        }
        if (alliedAiGroup != null && !alliedAiGroup.divisions.isEmpty()) {
            advanceGroupEveryFrame(engine, alliedAiGroup, enemyGroup, now);
        }
        if (enemyGroup != null && !enemyGroup.isPassiveOpposingView && !enemyGroup.divisions.isEmpty()) {
            advanceGroupEveryFrame(engine, enemyGroup, playerGroup, now);
        }
    }

    private static void advanceGroupEveryFrame(
            CombatEngineAPI engine,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            float now) {
        for (int dIdx = 0; dIdx < myGroup.divisions.size(); dIdx++) {
            DivisionState div = myGroup.divisions.get(dIdx);
            if (div == null || !div.isCombatEffective()) continue;
            if (div.shipSkillCoordination != ShipSkillCoordinationMode.SYNCHRONIZED) continue;

            List<ShipAPI> alive = div.getAliveMembers();
            if (alive.isEmpty()) continue;

            ShipAPI focusEnemy = DivisionCommanderAI.pickFocusEnemyShip(
                    engine,
                    div.side,
                    div.targetEnemyDivision,
                    div.centroid,
                    div.useConvergence || myGroup.useConvergence);
            if (myGroup.strikePlan != null && myGroup.strikePlan.committed
                    && myGroup.strikePlan.focusShip != null
                    && myGroup.strikePlan.focusShip.isAlive()
                    && !myGroup.strikePlan.focusShip.isHulk()
                    && (myGroup.strikePlan.participants.contains(div) || div == myGroup.mainEffortDivision)) {
                focusEnemy = myGroup.strikePlan.focusShip;
            }

            boolean underFire = div.incomingInRangeFirepower > 2.5f
                    || div.localEnemyStrength >= 6f
                    || (focusEnemy != null && DivisionCommanderAI.distance(div.centroid, focusEnemy.getLocation()) <= 1950f);

            DivisionMissionType activeMission = DivisionCommanderAI.resolveActiveMission(div, now);
            boolean scoutingOrStandoffPosture = activeMission == DivisionMissionType.SCOUTING
                    || activeMission == DivisionMissionType.STANDOFF_ENGAGEMENT
                    || activeMission == DivisionMissionType.SCREEN
                    || activeMission == DivisionMissionType.RESERVE
                    || div.currentIntent == DivisionIntentType.TAKE_SCREENING_STATION
                    || div.currentIntent == DivisionIntentType.HOLD_IN_RESERVE;

            boolean stage2SyncCharge = div.synchronizedSkillManeuverActive && div.synchronizedSkillStage == 2;
            boolean stage1Marshaling = (div.synchronizedSkillManeuverActive && div.synchronizedSkillStage == 1)
                    || (!div.synchronizedSkillManeuverActive && div.activeSetpieceAttack != DivisionSetpieceAttack.NONE && div.setpieceStage == 1);

            boolean anyActiveLinear = false;

            for (int sIdx = 0; sIdx < alive.size(); sIdx++) {
                ShipAPI ship = alive.get(sIdx);
                if (ship == null || OtcAI.isPlayerManuallyPiloting(engine, ship)) continue;

                ShipSkillProfile prof = getSkillProfile(ship);
                if (prof.category == ShipSkillCategory.NONE) continue;

                ShipSystemAPI sys = ship.getSystem();
                if (sys == null) continue;

                ShipwideAIFlags flags = ship.getAIFlags();
                Vector2f campLoc = (flags != null && flags.getCustom(AIFlags.CAMP_LOCATION) instanceof Vector2f)
                        ? (Vector2f) flags.getCustom(AIFlags.CAMP_LOCATION)
                        : null;
                boolean hasDoNotPursue = flags != null && flags.hasFlag(AIFlags.DO_NOT_PURSUE);
                boolean hasBackOff = flags != null && flags.hasFlag(AIFlags.BACK_OFF);

                // ------------------------------------------------------------------
                // A. ACTIVE LINEAR DRIVE MANAGEMENT: Standoff Braking & Overshoot Cutoff
                // ------------------------------------------------------------------
                if (prof.category.isLinearVector() && (sys.isActive() || sys.isChargeup())) {
                    anyActiveLinear = true;
                    if (prof.isToggle && sys.isActive()) {
                        boolean shouldCutToggle = false;
                        float currSpd = ship.getVelocity() != null
                                ? (float) Math.sqrt(ship.getVelocity().x * ship.getVelocity().x + ship.getVelocity().y * ship.getVelocity().y)
                                : ship.getMaxSpeed();
                        float decel = Math.max(25f, ship.getDeceleration());
                        float brakeDist = (currSpd * currSpd) / (2f * decel);

                        if (prof.isDesignedToRam && focusEnemy != null) {
                            // RAMMING DRIVE (e.g. Rambunctious JYD_ramdrive):
                            // JYD_ramdrive suppresses shields and weapons while active!
                            // Keep burning through physical impact with focusEnemy, then IMMEDIATELY cut the toggle
                            // drive right after impact (when in point-blank contact and speed has arrested, or after
                            // passing behind the target) so the destroyer restores its shields and guns at point-blank!
                            float toEx = focusEnemy.getLocation().x - ship.getLocation().x;
                            float toEy = focusEnemy.getLocation().y - ship.getLocation().y;
                            float distE = (float) Math.sqrt(toEx * toEx + toEy * toEy);
                            float contactDist = ship.getCollisionRadius() + focusEnemy.getCollisionRadius() + 55f;
                            double rad = Math.toRadians(ship.getFacing());
                            float hdgX = (float) Math.cos(rad);
                            float hdgY = (float) Math.sin(rad);
                            float dot = (distE > 1f) ? ((toEx / distE) * hdgX + (toEy / distE) * hdgY) : 1f;
                            if ((distE > 160f && dot < -0.10f)
                                    || (distE <= contactDist && currSpd < 340f)
                                    || ship.getFluxLevel() > 0.78f) {
                                shouldCutToggle = true;
                            }
                        } else if (!prof.isDesignedToRam && focusEnemy != null) {
                            // NON-RAMMING LINEAR DRIVE (e.g. Eagle/Dominator/Mora Burn Drive):
                            // Caveat enforcement: NEVER slam into the enemy! Cut off at standoff range + braking margin.
                            float distE = DivisionCommanderAI.distance(ship.getLocation(), focusEnemy.getLocation());
                            float stopBand = Math.max(540f, div.standoffRange * 0.88f) + Math.min(340f, brakeDist * 0.55f);
                            if (distE <= stopBand) {
                                shouldCutToggle = true;
                            }
                        }

                        // If burning to catch up to a formation slot (campLoc) out of combat, cut off upon arrival
                        if (!stage2SyncCharge && campLoc != null) {
                            float distSlot = DivisionCommanderAI.distance(ship.getLocation(), campLoc);
                            if (distSlot <= Math.max(180f, Math.min(360f, brakeDist * 0.65f))) {
                                shouldCutToggle = true;
                            }
                        }

                        if (shouldCutToggle) {
                            sys.deactivate();
                            ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                            continue;
                        }
                    }

                    // While a dedicated ramming ship is mid-charge in Stage 2, steer its movement point through the enemy
                    if (prof.isDesignedToRam && stage2SyncCharge && focusEnemy != null && flags != null) {
                        double rad = Math.toRadians(ship.getFacing());
                        Vector2f eLoc = focusEnemy.getLocation();
                        Vector2f eVel = focusEnemy.getVelocity();
                        Vector2f ramThroughPt = new Vector2f(
                                eLoc.x + (eVel != null ? eVel.x * 0.45f : 0f) + (float) Math.cos(rad) * 180f,
                                eLoc.y + (eVel != null ? eVel.y * 0.45f : 0f) + (float) Math.sin(rad) * 180f);
                        flags.unsetFlag(AIFlags.CAMP_LOCATION);
                        flags.unsetFlag(AIFlags.BACK_OFF);
                        flags.unsetFlag(AIFlags.DO_NOT_PURSUE);
                        flags.setFlag(AIFlags.DO_NOT_BACK_OFF, 0.25f);
                        flags.setFlag(AIFlags.HARASS_MOVE_IN, 0.25f);
                        flags.setFlag(AIFlags.PURSUING, 0.25f);
                        flags.setFlag(AIFlags.MOVEMENT_DEST, 0.25f, ramThroughPt);
                    }
                    continue;
                }

                // ------------------------------------------------------------------
                // B. SYNCHRONIZED LINEAR SKILL DISCIPLINE & TRIGGERING
                // ------------------------------------------------------------------
                if (prof.category.isLinearVector()) {
                    // 1. Out-of-Fire Free Marshaling (Straggler Catch-Up to Division Carriage):
                    // When NOT under enemy fire and a ship has fallen >= 680u behind its own division's centroid
                    // and formation slot (after initial deployment), allow/trigger linear thrust to rapidly rejoin!
                    float distFromCentroid = div.centroid != null
                            ? DivisionCommanderAI.distance(ship.getLocation(), div.centroid)
                            : 0f;
                    if (!underFire && !stage2SyncCharge && now > 6.0f && alive.size() >= 2 && campLoc != null
                            && distFromCentroid >= Math.max(680f, div.stationKeepingRadius * 1.45f)) {
                        float distToSlot = DivisionCommanderAI.distance(ship.getLocation(), campLoc);
                        boolean alignedToSlot = isShipAlignedToPoint(ship, campLoc, 16f);
                        if (distToSlot >= 680f && alignedToSlot
                                && isShipSystemReady(ship, prof)
                                && isForwardRayClearOfFriendlies(ship, alive, 480f)) {
                            ship.useSystem();
                            anyActiveLinear = true;
                            continue;
                        }
                    }

                    // 2. Under-Fire / Scouting / Standoff / Stage-1 Marshaling Discipline Gate:
                    // Block solo linear activations when scouting, holding standoff, dressing in Stage 1, or outside
                    // Stage 2 synchronized execution!
                    if (!stage2SyncCharge) {
                        boolean blockSoloBurst = scoutingOrStandoffPosture
                                || stage1Marshaling
                                || hasDoNotPursue
                                || hasBackOff
                                || prof.isDesignedToRam
                                || div.currentIntent == DivisionIntentType.CLOSE_UP
                                || div.currentIntent == DivisionIntentType.MARSHAL_FOR_STRIKE
                                || (underFire && prof.dropsShield && div.getAliveMembers().size() >= 2);
                        if (blockSoloBurst) {
                            ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                            continue;
                        }
                    }

                    // 3. Stage 2 Synchronized Division / OTC Charge Ignition:
                    if (stage2SyncCharge && focusEnemy != null && isShipSystemReady(ship, prof)) {
                        ShipAPI effTarget = focusEnemy;
                        if (ship.getShipTarget() != null && ship.getShipTarget().isAlive()
                                && !ship.getShipTarget().isHulk() && ship.getShipTarget().getOwner() != ship.getOwner()) {
                            float dSt = DivisionCommanderAI.distance(ship.getLocation(), ship.getShipTarget().getLocation());
                            if (dSt <= 1750f) {
                                effTarget = ship.getShipTarget();
                            }
                        }
                        float distE = DivisionCommanderAI.distance(ship.getLocation(), effTarget.getLocation());
                        if (prof.isDesignedToRam) {
                            // Synchronized Ramming Charge (e.g. Rambunctious division):
                            // Align bow to target enemy ship and ignite JYD_ramdrive in unison inside 1,850u!
                            if (distE >= 280f && distE <= 1850f
                                    && isShipAlignedToPoint(ship, effTarget.getLocation(), 38f)) {
                                if (flags != null) {
                                    flags.unsetFlag(AIFlags.CAMP_LOCATION);
                                    flags.unsetFlag(AIFlags.DO_NOT_PURSUE);
                                    flags.setFlag(AIFlags.DO_NOT_BACK_OFF, 0.5f);
                                    flags.setFlag(AIFlags.HARASS_MOVE_IN, 0.5f);
                                    flags.setFlag(AIFlags.PURSUING, 0.5f);
                                    flags.setFlag(AIFlags.MOVEMENT_DEST, 0.5f, effTarget.getLocation());
                                }
                                ship.setShipTarget(effTarget);
                                ship.useSystem();
                                anyActiveLinear = true;
                            } else {
                                // Hold until bow finishes slewing onto the target vector and within 1,850u
                                ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                            }
                        } else {
                            // Synchronized Non-Ramming Linear Approach (e.g. Burn Drive gap-close to standoff):
                            float stopBand = Math.max(580f, div.standoffRange * 0.92f);
                            Vector2f goalPt = campLoc != null ? campLoc : div.stationPoint;
                            if (distE >= stopBand + 340f && distE <= 2550f
                                    && isShipAlignedToPoint(ship, goalPt != null ? goalPt : focusEnemy.getLocation(), 22f)
                                    && isForwardRayClearOfFriendlies(ship, alive, 420f)) {
                                ship.useSystem();
                                anyActiveLinear = true;
                            } else if (distE < stopBand + 260f) {
                                // Too close to weapon standoff band — forbid forward burn so ship never rams!
                                ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                            }
                        }
                    }
                    continue;
                }

                // ------------------------------------------------------------------
                // C. SYNCHRONIZED OFFENSIVE AMPLIFIER (Ammo Feed / High Energy Focus / Lidar)
                // ------------------------------------------------------------------
                if (prof.category == ShipSkillCategory.OFFENSIVE_AMP && focusEnemy != null) {
                    float distE = DivisionCommanderAI.distance(ship.getLocation(), focusEnemy.getLocation());
                    if (ship.getShipTarget() != null && ship.getShipTarget().isAlive()
                            && !ship.getShipTarget().isHulk() && ship.getShipTarget().getOwner() != ship.getOwner()) {
                        distE = Math.min(distE, DivisionCommanderAI.distance(ship.getLocation(), ship.getShipTarget().getLocation()));
                    }
                    float maxGunRange = Math.max(1350f, div.avgEngageRange * 1.08f);
                    if (distE > maxGunRange || (stage1Marshaling && distE > 1100f)) {
                        // Hold offensive amplifier when out of primary gun range so cooldown isn't wasted at 3,000u!
                        ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                    } else if (isShipSystemReady(ship, prof) && ship.getFluxLevel() < 0.72f) {
                        ship.useSystem();
                    }
                }
            }

            div.hasActiveLinearSkillBurn = anyActiveLinear;
        }
    }

    private static int countMembersAlignedToPoint(List<ShipAPI> members, Vector2f targetPt, float maxAngleDeg) {
        if (members == null || targetPt == null) return 0;
        int count = 0;
        for (int i = 0; i < members.size(); i++) {
            if (isShipAlignedToPoint(members.get(i), targetPt, maxAngleDeg)) {
                count++;
            }
        }
        return count;
    }

    public static boolean isShipAlignedToPoint(ShipAPI ship, Vector2f targetPt, float maxAngleDeg) {
        if (ship == null || targetPt == null || ship.getLocation() == null) return false;
        float dx = targetPt.x - ship.getLocation().x;
        float dy = targetPt.y - ship.getLocation().y;
        if (dx * dx + dy * dy < 25f) return true;
        float desiredAng = (float) Math.toDegrees(Math.atan2(dy, dx));
        float diff = Math.abs(shortestAngleDiff(ship.getFacing(), desiredAng));
        return diff <= maxAngleDeg;
    }

    private static boolean isForwardRayClearOfFriendlies(ShipAPI ship, List<ShipAPI> peers, float checkDist) {
        if (ship == null || peers == null || peers.size() <= 1) return true;
        double rad = Math.toRadians(ship.getFacing());
        float fx = (float) Math.cos(rad);
        float fy = (float) Math.sin(rad);
        float myR = Math.max(40f, ship.getCollisionRadius());

        for (int i = 0; i < peers.size(); i++) {
            ShipAPI other = peers.get(i);
            if (other == null || other == ship || !other.isAlive()) continue;
            float dx = other.getLocation().x - ship.getLocation().x;
            float dy = other.getLocation().y - ship.getLocation().y;
            float fwdProj = dx * fx + dy * fy;
            if (fwdProj <= 0f || fwdProj > checkDist) continue;
            float latDist = Math.abs(dx * (-fy) + dy * fx);
            float clearance = (myR + Math.max(40f, other.getCollisionRadius())) * 0.85f;
            if (latDist < clearance) {
                return false;
            }
        }
        return true;
    }

    private static float shortestAngleDiff(float fromDeg, float toDeg) {
        float d = (toDeg - fromDeg) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
