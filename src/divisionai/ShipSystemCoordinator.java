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
import divisionai.PermanentDivisionManager.DivisionRammingMode;
import divisionai.PermanentDivisionManager.DivisionRammingTargetScope;
import divisionai.PermanentDivisionManager.DivisionSetpieceAttack;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.ShipSkillCoordinationMode;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
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

    /** Records each ship's unboosted base hull mass so systems like JYD_ramdrive that forget to reset setMass in unapply() don't leave ships stuck at 5x mass. */
    private static final Map<ShipAPI, Float> BASE_MASS_CACHE = new WeakHashMap<>();

    /** Locks each active rammer's assigned enemy target for the duration of its ram burn so C2 ticks never swap lanes mid-charge. */
    private static final Map<ShipAPI, ShipAPI> LOCKED_RAM_TARGETS = new WeakHashMap<>();

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
     * Campaign-layer detector used by the Auto-Assigner to check whether a {@link com.fs.starfarer.api.fleet.FleetMemberAPI}
     * is equipped with a ramming ship system (e.g. Rambunctious JYD_ramdrive, Iron Shell rampage drive, drill attack,
     * or any zero-flameout no-shield/no-weapons linear drive).
     */
    public static boolean isFleetMemberDesignedToRam(com.fs.starfarer.api.fleet.FleetMemberAPI member) {
        if (member == null || member.isFighterWing()) return false;
        ShipHullSpecAPI hullSpec = member.getHullSpec();
        if (hullSpec == null) return false;
        String hullIdLower = hullSpec.getHullId() != null ? hullSpec.getHullId().toLowerCase(Locale.ROOT) : "";
        String hullNameLower = hullSpec.getHullName() != null ? hullSpec.getHullName().toLowerCase(Locale.ROOT) : "";
        if (hullIdLower.contains("rambunctious") || hullNameLower.contains("rambunctious")) {
            return true;
        }
        String sysId = hullSpec.getShipSystemId();
        if (sysId == null || sysId.trim().isEmpty()) return false;
        String sysIdLower = sysId.trim().toLowerCase(Locale.ROOT);
        if (sysIdLower.contains("traveldrive")) return false;
        if (sysIdLower.contains("ramdrive")
                || sysIdLower.contains("rampage")
                || sysIdLower.contains("drill_attack")
                || sysIdLower.contains("battering")
                || sysIdLower.contains("silverlight_dash")) {
            return true;
        }
        try {
            if (com.fs.starfarer.api.Global.getSettings() != null) {
                ShipSystemSpecAPI spec = com.fs.starfarer.api.Global.getSettings().getShipSystemSpec(sysId);
                if (spec != null) {
                    String dispLower = spec.getName() != null ? spec.getName().toLowerCase(Locale.ROOT) : "";
                    String statsLower = spec.getStatsScriptClassName() != null
                            ? spec.getStatsScriptClassName().toLowerCase(Locale.ROOT)
                            : "";
                    String aiLower = spec.getAIScriptClassName() != null
                            ? spec.getAIScriptClassName().toLowerCase(Locale.ROOT)
                            : "";
                    if (dispLower.contains("ram drive")
                            || dispLower.contains("ramming")
                            || dispLower.contains("rampage")
                            || statsLower.contains("ramdrive")
                            || statsLower.contains("rampage")
                            || statsLower.contains("drillattack")
                            || aiLower.contains("ramdrive")
                            || aiLower.contains("ironrampage")
                            || aiLower.contains("drillattack")) {
                        return true;
                    }
                    boolean alwaysAcc = spec.isAlwaysAccelerate();
                    boolean shieldAllowed = spec.isShieldAllowed();
                    boolean firingAllowed = spec.isFiringAllowed();
                    float flameoutChance = spec.getFlameoutOnImpactChance();
                    boolean noShieldHull = hullSpec.getDefenseType() == null
                            || hullSpec.getDefenseType() == ShieldAPI.ShieldType.NONE;
                    if (alwaysAcc && flameoutChance == 0f && (!shieldAllowed || noShieldHull) && !firingAllowed) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
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
        // divisions, do not launch a premature solo suicide charge ahead of the friendly main line!
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
                    if (dOther <= dSelf + 420f || dOther <= 1650f || other.inRangeFirepowerScore > 0f) {
                        heavyAnvilEngaged = true;
                        break;
                    }
                }
            }
            if (hasHeavyAnvil && !heavyAnvilEngaged && now < 85f && dSelf > 1050f) {
                anvilSupportReady = false;
            }
        }

        boolean explicitLanceRamOrder = div.rammingMode == DivisionRammingMode.SYNCHRONIZED_LANCE
                || div.activeSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE
                || div.doctrinalSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE
                || (div.otcOrder != null && div.otcOrder.isActive(now)
                && div.otcOrder.orderedSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE);
        DivisionMissionType activeMission = DivisionCommanderAI.resolveActiveMission(div, now);
        boolean activelyAttackingIntent = div.currentIntent == DivisionIntentType.CLOSE_AND_ENGAGE
                || div.currentIntent == DivisionIntentType.PRESS_HOME
                || div.currentIntent == DivisionIntentType.WORK_FLANK
                || div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE
                || div.currentIntent == DivisionIntentType.GIVE_GENERAL_CHASE
                || div.currentIntent == DivisionIntentType.REINFORCE_DIVISION;
        boolean postureForbidsOffensiveCharge = !otcStrikeOrder && !explicitLanceRamOrder
                && (activeMission == DivisionMissionType.SCOUTING
                || activeMission == DivisionMissionType.STANDOFF_ENGAGEMENT
                || (activeMission == DivisionMissionType.SCREEN && !activelyAttackingIntent)
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

        // Whole-division synchronized ram charges are triggered when the division is configured/ordered for Lance Charge
        // (SYNCHRONIZED_LANCE or LANCE_CHARGE setpiece, or explicit OTC strike order) and the localized space is conducive.
        boolean wantRamCharge = hasRamPack
                && div.rammingMode != DivisionRammingMode.OFF
                && anvilSupportReady
                && div.avgFluxLevel < 0.62f
                && distToTarget >= 280f
                && distToTarget <= 2150f
                && (div.readySkillMemberRatio >= 0.50f || div.hasActiveLinearSkillBurn)
                && (explicitLanceRamOrder || (otcStrikeOrder && alive.size() == 1));

        float desiredStopDist = Math.max(580f, div.standoffRange * 0.92f);
        boolean wantLinearGapClose = (hasLinearThrustPack || (hasRamPack && !wantRamCharge))
                && div.avgFluxLevel < 0.58f
                && distToTarget >= desiredStopDist + 420f
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
                // Ram charges marshal in Stage 1 so all ships dress and align their bows onto their assigned targets before igniting together!
                int alignedInit = wantRamCharge
                        ? countRammersAlignedToAssignedTargets(engine, div, alive, focusEnemyShip, myGroup, enemyGroup, 15f)
                        : countMembersAlignedToPoint(alive, focusEnemyShip.getLocation(), 22f);
                boolean alreadyDressedAndAligned = alive.size() == 1
                        || (div.stationKeepingRatio <= 0.82f
                        && distToTarget <= 1250f
                        && alignedInit >= alive.size());
                boolean mustMarshalFirst = wantRamCharge
                        ? !alreadyDressedAndAligned
                        : (alive.size() >= 2 && div.stationKeepingRatio > 0.85f);
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
            // Stage 1: Marshal into formation & align bows toward each rammer's assigned target before igniting Stage 2 together
            float reqAngle = wantRamCharge ? 15f : (underFire ? 22f : 26f);
            float reqSkRatio = underFire ? 0.98f : 1.15f;
            int alignedCount = wantRamCharge
                    ? countRammersAlignedToAssignedTargets(engine, div, alive, focusEnemyShip, myGroup, enemyGroup, reqAngle)
                    : countMembersAlignedToPoint(alive, focusEnemyShip.getLocation(), reqAngle);
            int reqAlignedCount = wantRamCharge
                    ? Math.max(1, (int) Math.ceil(alive.size() * 0.85f))
                    : Math.max(1, (alive.size() * 2 + 1) / 3);
            boolean wellDressed = div.stationKeepingRatio <= reqSkRatio;
            boolean bowsAligned = alignedCount >= reqAlignedCount;
            boolean inIgniteRange = !wantRamCharge || distToTarget <= (underFire ? 1520f : 1680f);
            if (inIgniteRange && ((wellDressed && bowsAligned)
                    || (bowsAligned && distToTarget <= 1350f)
                    || (!wantRamCharge && stageElapsed >= (underFire ? 1.8f : 1.4f) && distToTarget <= 1420f)
                    || (wantRamCharge && stageElapsed >= 2.8f && alignedCount >= Math.max(1, (alive.size() * 2) / 3)))) {
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

            List<ShipAPI> alive = div.getAliveMembers();
            if (alive.isEmpty()) continue;
            boolean syncSkills = (div.shipSkillCoordination == ShipSkillCoordinationMode.SYNCHRONIZED);
            boolean outOfCombatMarch = DivisionFormationNavigator.isDivisionInOutOfCombatMarch(engine, div);

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
            boolean activelyAttackingIntent = div.currentIntent == DivisionIntentType.CLOSE_AND_ENGAGE
                    || div.currentIntent == DivisionIntentType.PRESS_HOME
                    || div.currentIntent == DivisionIntentType.WORK_FLANK
                    || div.currentIntent == DivisionIntentType.TOT_ALPHA_STRIKE
                    || div.currentIntent == DivisionIntentType.GIVE_GENERAL_CHASE
                    || div.currentIntent == DivisionIntentType.REINFORCE_DIVISION;
            boolean scoutingOrStandoffPosture = activeMission == DivisionMissionType.SCOUTING
                    || activeMission == DivisionMissionType.STANDOFF_ENGAGEMENT
                    || (activeMission == DivisionMissionType.SCREEN && !activelyAttackingIntent)
                    || activeMission == DivisionMissionType.RESERVE
                    || div.currentIntent == DivisionIntentType.TAKE_SCREENING_STATION
                    || div.currentIntent == DivisionIntentType.HOLD_IN_RESERVE;

            boolean anyActiveLinear = false;
            DivisionRammingMode ramMode = div.rammingMode != null ? div.rammingMode : DivisionRammingMode.OFF;
            DivisionRammingTargetScope ramScope = div.rammingTargetScope != null
                    ? div.rammingTargetScope
                    : DivisionRammingTargetScope.SINGLE_SHIP;
            int maxRamSlots = Math.max(1, Math.min(10, div.maxRammingAttackSlots));
            Map<ShipAPI, ShipAPI> assignedRamTargets = (ramMode != DivisionRammingMode.OFF && div.ramCapableMemberCount > 0)
                    ? computeDivisionRamTargetAssignments(
                            engine, div, alive, focusEnemy, myGroup, enemyGroup, ramMode, ramScope, maxRamSlots)
                    : java.util.Collections.emptyMap();

            // Frame-accurate Stage 1 -> Stage 2 Lance Charge promotion:
            // As soon as the assigned rammers have dressed and aligned their bows (within 18 deg) onto their assigned
            // targets in the conducive localized space, promote Stage 1 -> Stage 2 immediately on this frame so the
            // entire division ignites its ram drives together instead of piecemeal!
            if (syncSkills
                    && div.synchronizedSkillManeuverActive
                    && div.synchronizedSkillStage == 1
                    && !assignedRamTargets.isEmpty()) {
                int assignedReady = 0;
                int alignedAndInRange = 0;
                for (Map.Entry<ShipAPI, ShipAPI> entry : assignedRamTargets.entrySet()) {
                    ShipAPI rShip = entry.getKey();
                    ShipAPI rTgt = entry.getValue();
                    if (rShip == null || rTgt == null) continue;
                    assignedReady++;
                    float d = DivisionCommanderAI.distance(rShip.getLocation(), rTgt.getLocation());
                    Vector2f aim = computeRamInterceptPoint(rShip, rTgt, 0, div.targetEnemyDivision, ramMode);
                    if (d <= 1650f && isShipAlignedToPoint(rShip, aim, 18f)) {
                        alignedAndInRange++;
                    }
                }
                float stageElapsedNow = Math.max(0f, now - div.synchronizedSkillStageStartTime);
                if (assignedReady > 0
                        && ((alignedAndInRange >= assignedReady && (div.stationKeepingRatio <= 1.15f || stageElapsedNow >= 0.45f))
                        || (stageElapsedNow >= 1.75f && alignedAndInRange >= Math.max(1, assignedReady - 1)))) {
                    div.synchronizedSkillStage = 2;
                    div.synchronizedSkillStageStartTime = now;
                    div.activeSkillManeuverLabel = "Sync Ram Charge";
                }
            }

            boolean stage2SyncCharge = syncSkills && div.synchronizedSkillManeuverActive && div.synchronizedSkillStage == 2;
            boolean stage1Marshaling = syncSkills && ((div.synchronizedSkillManeuverActive && div.synchronizedSkillStage == 1)
                    || (!div.synchronizedSkillManeuverActive && div.activeSetpieceAttack != DivisionSetpieceAttack.NONE && div.setpieceStage == 1));
            boolean directObjectiveMarch = div.currentIntent == DivisionIntentType.TAKE_AND_HOLD
                    && div.targetObjective != null
                    && div.targetObjective.getLocation() != null
                    && div.targetObjective.getOwner() != div.side
                    && (div.underDirectObjectiveOrder
                    || DivisionCommanderAI.distance(div.centroid, div.targetObjective.getLocation()) <= 1350f);
            if (directObjectiveMarch
                    && div.underDirectObjectiveOrder
                    && DivisionCommanderAI.distance(div.centroid, div.targetObjective.getLocation()) > 850f) {
                outOfCombatMarch = true;
            }

            for (int sIdx = 0; sIdx < alive.size(); sIdx++) {
                ShipAPI ship = alive.get(sIdx);
                if (ship == null || OtcAI.isPlayerManuallyPiloting(engine, ship)) continue;

                ShipwideAIFlags flags = ship.getAIFlags();
                if (directObjectiveMarch
                        && ship.getFluxLevel() < 0.80f
                        && (ship.getFluxTracker() == null || !ship.getFluxTracker().isOverloadedOrVenting())) {
                    ShipAPI blockingFriendlyObjMarch = findImminentFriendlyInForwardPath(engine, ship, alive, myGroup, 380f);
                    if (blockingFriendlyObjMarch == null) {
                        Vector2f objLoc = div.targetObjective.getLocation();
                        Vector2f campGoal = (flags != null && flags.getCustom(AIFlags.CAMP_LOCATION) instanceof Vector2f)
                                ? (Vector2f) flags.getCustom(AIFlags.CAMP_LOCATION)
                                : null;
                        Vector2f helmGoal;
                        float distShipToObj = DivisionCommanderAI.distance(ship.getLocation(), objLoc);
                        if (sIdx == 0 || ship == div.flagship || campGoal == null) {
                            helmGoal = (div.hasRouteWaypoint && distShipToObj > 1250f && div.routeWaypoint != null)
                                    ? div.routeWaypoint
                                    : objLoc;
                        } else {
                            helmGoal = campGoal;
                        }
                        float distShipToGoal = DivisionCommanderAI.distance(ship.getLocation(), helmGoal);
                        if (flags != null) {
                            flags.unsetFlag(AIFlags.BACK_OFF);
                            flags.setFlag(AIFlags.DO_NOT_BACK_OFF, 0.50f);
                            flags.setFlag(AIFlags.DO_NOT_PURSUE, 0.50f);
                            flags.setFlag(AIFlags.CAMP_LOCATION, 0.50f, helmGoal);
                            flags.setFlag(AIFlags.MOVEMENT_DEST, 0.50f, helmGoal);
                            if (distShipToObj > 420f) {
                                flags.unsetFlag(AIFlags.MANEUVER_TARGET);
                                flags.unsetFlag(AIFlags.HARASS_MOVE_IN);
                                flags.unsetFlag(AIFlags.PURSUING);
                                DivisionCommanderAI.clearBasicShipAiTargetOverride(ship);
                            }
                        }
                        steerShipHelmOntoObjectivePoint(ship, helmGoal, distShipToGoal, distShipToObj);
                    }
                }

                ShipSkillProfile prof = getSkillProfile(ship);
                if (prof.category == ShipSkillCategory.NONE) continue;

                ShipSystemAPI sys = ship.getSystem();
                if (sys == null) continue;

                // Record unboosted base mass before a ram drive multiplies it, and restore it once the drive is fully off
                // (fixes JYD_RamDriveStats leaving ships permanently stuck at 5x mass with sluggish thrusters after one ram).
                if (prof.isDesignedToRam) {
                    float curMass = ship.getMass();
                    if (curMass > 1f) {
                        Float baseMass = BASE_MASS_CACHE.get(ship);
                        if (baseMass == null || (curMass < baseMass && !sys.isActive() && !sys.isChargeup())) {
                            BASE_MASS_CACHE.put(ship, curMass);
                        } else if (!sys.isActive() && !sys.isChargeup() && !sys.isChargedown() && curMass > baseMass * 1.45f) {
                            ship.setMass(baseMass);
                        }
                    }
                }

                Vector2f campLoc = (flags != null && flags.getCustom(AIFlags.CAMP_LOCATION) instanceof Vector2f)
                        ? (Vector2f) flags.getCustom(AIFlags.CAMP_LOCATION)
                        : null;
                Vector2f movDest = (flags != null && flags.getCustom(AIFlags.MOVEMENT_DEST) instanceof Vector2f)
                        ? (Vector2f) flags.getCustom(AIFlags.MOVEMENT_DEST)
                        : null;
                boolean hasDoNotPursue = flags != null && flags.hasFlag(AIFlags.DO_NOT_PURSUE);
                boolean hasBackOff = flags != null && flags.hasFlag(AIFlags.BACK_OFF);
                boolean hasRunQuickly = flags != null && flags.hasFlag(AIFlags.RUN_QUICKLY);
                boolean activeOrChargingRam = prof.isDesignedToRam
                        && ramMode != DivisionRammingMode.OFF
                        && (sys.isActive() || sys.isChargeup() || stage1Marshaling || stage2SyncCharge)
                        && ship.getFluxLevel() < 0.74f
                        && ship.getHullLevel() > 0.38f;
                boolean isShipExtracting = div.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                        || div.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE
                        || (!directObjectiveMarch && !activeOrChargingRam && (hasRunQuickly
                        || (hasBackOff && (ship.getFluxLevel() >= 0.45f || ship.getHullLevel() < 0.72f || div.localForceRatio < 0.82f))));

                ShipAPI assignedSlotTarget = assignedRamTargets.get(ship);
                ShipAPI effTarget = (assignedSlotTarget != null)
                        ? assignedSlotTarget
                        : resolveEffectiveRamOrSkillTarget(ship, flags, focusEnemy);

                // ------------------------------------------------------------------
                // A. ACTIVE LINEAR DRIVE MANAGEMENT: Helmsmanship Anti-Friendly Cutoff,
                //    Glancing Side-Body Ram, Escape Burn & Standoff Cutoff
                // ------------------------------------------------------------------
                if (prof.category.isLinearVector() && (sys.isActive() || sys.isChargeup())) {
                    anyActiveLinear = true;
                    float currSpd = ship.getVelocity() != null
                            ? (float) Math.sqrt(ship.getVelocity().x * ship.getVelocity().x + ship.getVelocity().y * ship.getVelocity().y)
                            : ship.getMaxSpeed();
                    float decel = Math.max(25f, ship.getDeceleration());
                    float brakeDist = (currSpd * currSpd) / (2f * decel);

                    // HELMSMANSHIP RULE #1: Never allow an active linear/ram drive to slam into ANY friendly ship in the fleet!
                    float friendlyCheckDist = Math.max(420f, Math.min(820f, currSpd * 1.45f + 140f));
                    ShipAPI blockingFriendly = findImminentFriendlyInForwardPath(engine, ship, alive, myGroup, friendlyCheckDist);
                    if (blockingFriendly != null) {
                        // Steer laterally around the friendly hull immediately
                        Vector2f avoidPt = computeLateralAvoidancePoint(ship, blockingFriendly);
                        if (flags != null && avoidPt != null) {
                            flags.setFlag(AIFlags.MOVEMENT_DEST, 0.25f, avoidPt);
                        }
                        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                        ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                        if (prof.isToggle && sys.isActive()) {
                            sys.deactivate();
                            ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                            Float baseMass = BASE_MASS_CACHE.get(ship);
                            if (baseMass != null && baseMass > 1f) {
                                ship.setMass(baseMass);
                            }
                            continue;
                        }
                    }

                    if (prof.isToggle && sys.isActive()) {
                        boolean shouldCutToggle = false;

                        if (isShipExtracting) {
                            // ESCAPE BURN MANAGEMENT: Cut toggle drive once safely clear of enemy gun range or arriving at extraction point,
                            // or if an enemy ship blocks our escape path!
                            Vector2f escGoal = movDest != null ? movDest : (campLoc != null ? campLoc : div.stationPoint);
                            if (!isForwardCorridorClearOfEnemies(engine, ship, enemyGroup, 420f)) {
                                shouldCutToggle = true;
                            } else if (effTarget != null) {
                                float distE = DivisionCommanderAI.distance(ship.getLocation(), effTarget.getLocation());
                                if (distE >= Math.max(1380f, div.standoffRange * 1.45f)) {
                                    shouldCutToggle = true;
                                }
                            }
                            if (escGoal != null) {
                                float distGoal = DivisionCommanderAI.distance(ship.getLocation(), escGoal);
                                if (distGoal <= Math.max(220f, Math.min(400f, brakeDist * 0.65f))) {
                                    shouldCutToggle = true;
                                }
                            }
                        } else if (prof.isDesignedToRam && effTarget != null) {
                            if (ramMode == DivisionRammingMode.OFF || isStationOrStationModule(effTarget)) {
                                // Ramming is OFF for this division (or target is an immovable station):
                                // Cut the drive at gun standoff range so the ship never rams!
                                float distE = DivisionCommanderAI.distance(ship.getLocation(), effTarget.getLocation());
                                float stopBand = isStationOrStationModule(effTarget)
                                        ? 900f
                                        : (Math.max(540f, div.standoffRange * 0.88f) + Math.min(340f, brakeDist * 0.55f));
                                if (distE <= stopBand) {
                                    shouldCutToggle = true;
                                }
                            } else {
                                // RAMMING DRIVE (e.g. Rambunctious JYD_ramdrive):
                                // Keep the drive burning all the way through physical hull impact, then cut right after
                                // passing the target's beam or after collision momentum transfer so shields/guns come back up!
                                float toEx = effTarget.getLocation().x - ship.getLocation().x;
                                float toEy = effTarget.getLocation().y - ship.getLocation().y;
                                float distE = (float) Math.sqrt(toEx * toEx + toEy * toEy);
                                float sRad = Math.max(35f, ship.getCollisionRadius());
                                float tRad = Math.max(45f, effTarget.getCollisionRadius());
                                float deepHullContactDist = (sRad + tRad) * 0.58f;
                                double rad = Math.toRadians(ship.getFacing());
                                float hdgX = (float) Math.cos(rad);
                                float hdgY = (float) Math.sin(rad);
                                float dot = (distE > 1f) ? ((toEx / distE) * hdgX + (toEy / distE) * hdgY) : 1f;
                                if ((distE > 110f && dot < -0.15f)
                                        || (distE <= deepHullContactDist && (currSpd < 135f || dot < -0.05f))
                                        || ship.getFluxLevel() > 0.86f) {
                                    shouldCutToggle = true;
                                }
                            }
                        } else if (!prof.isDesignedToRam && effTarget != null) {
                            // NON-RAMMING LINEAR DRIVE (e.g. Eagle/Dominator/Mora Burn Drive):
                            // Caveat enforcement: NEVER slam into the enemy! Cut off at standoff range + braking margin.
                            float distE = DivisionCommanderAI.distance(ship.getLocation(), effTarget.getLocation());
                            float stopBand = Math.max(540f, div.standoffRange * 0.88f) + Math.min(340f, brakeDist * 0.55f);
                            if (distE <= stopBand) {
                                shouldCutToggle = true;
                            }
                        }

                        // If burning to catch up to a formation slot (campLoc) out of combat, cut off upon arrival
                        if (!stage2SyncCharge && !prof.isDesignedToRam && campLoc != null) {
                            float distSlot = DivisionCommanderAI.distance(ship.getLocation(), campLoc);
                            if (distSlot <= Math.max(180f, Math.min(360f, brakeDist * 0.65f))) {
                                shouldCutToggle = true;
                            }
                        } else if (!stage2SyncCharge && prof.isDesignedToRam && campLoc != null
                                && (effTarget == null || DivisionCommanderAI.distance(ship.getLocation(), effTarget.getLocation()) > 1650f)) {
                            float distSlot = DivisionCommanderAI.distance(ship.getLocation(), campLoc);
                            if (distSlot <= Math.max(220f, Math.min(400f, brakeDist * 0.70f))) {
                                shouldCutToggle = true;
                            }
                        }

                        if (shouldCutToggle) {
                            sys.deactivate();
                            ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                            Float baseMass = BASE_MASS_CACHE.get(ship);
                            if (baseMass != null && baseMass > 1f) {
                                ship.setMass(baseMass);
                            }
                            continue;
                        }
                    }

                    if (isShipExtracting && flags != null) {
                        Vector2f escGoal = movDest != null ? movDest : (campLoc != null ? campLoc : div.stationPoint);
                        if (escGoal != null) {
                            flags.unsetFlag(AIFlags.MANEUVER_TARGET);
                            flags.setFlag(AIFlags.DO_NOT_PURSUE, 0.25f);
                            flags.setFlag(AIFlags.RUN_QUICKLY, 0.25f);
                            flags.setFlag(AIFlags.MOVEMENT_DEST, 0.25f, escGoal);
                        }
                        continue;
                    }

                    // Whenever ANY dedicated ramming ship is actively executing an offensive ram (when ramMode != OFF),
                    // steer its movement vector AND direct helm commands onto the intercept point on its assigned target
                    // so vanilla BasicShipAI collision-avoidance cannot dodge around the enemy hull or brake before impact!
                    if (prof.isDesignedToRam && ramMode != DivisionRammingMode.OFF
                            && effTarget != null && !isStationOrStationModule(effTarget)
                            && flags != null && blockingFriendly == null) {
                        float distE = DivisionCommanderAI.distance(ship.getLocation(), effTarget.getLocation());
                        if (distE <= 2150f) {
                            Vector2f aimPt = computeRamInterceptPoint(ship, effTarget, sIdx, div.targetEnemyDivision, ramMode);
                            flags.unsetFlag(AIFlags.CAMP_LOCATION);
                            flags.unsetFlag(AIFlags.BACK_OFF);
                            flags.unsetFlag(AIFlags.DO_NOT_PURSUE);
                            flags.unsetFlag(AIFlags.RUN_QUICKLY);
                            flags.setFlag(AIFlags.DO_NOT_BACK_OFF, 0.25f);
                            flags.setFlag(AIFlags.HARASS_MOVE_IN, 0.25f);
                            flags.setFlag(AIFlags.PURSUING, 0.25f);
                            flags.setFlag(AIFlags.MANEUVER_TARGET, 0.25f, effTarget);
                            flags.setFlag(AIFlags.MOVEMENT_DEST, 0.25f, aimPt);
                            ship.setShipTarget(effTarget);
                            steerShipHelmOntoRamTarget(ship, aimPt, true);
                        }
                    }
                    continue;
                }

                // If Ramming is OFF for this division, block any offensive ram-drive activation near enemies
                // (after extraction and out-of-combat straggler catch-up are handled).
                if (prof.isDesignedToRam && ramMode == DivisionRammingMode.OFF && !isShipExtracting) {
                    if (effTarget != null && DivisionCommanderAI.distance(ship.getLocation(), effTarget.getLocation()) <= 1650f) {
                        ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                        continue;
                    }
                }

                // HELMSMANSHIP RULE #2: Even in INDEPENDENT skill mode, NEVER allow a ship to ignite a forward linear/ram
                // drive when another friendly ship anywhere in the fleet is in its forward trajectory!
                if (prof.category.isLinearVector()
                        && !isForwardCorridorClearOfFleetFriendlies(engine, ship, alive, myGroup, prof.isDesignedToRam ? 760f : 560f)) {
                    ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                    continue;
                }

                // Out-of-Combat Mixed-Division Speed Pacing: while marching out of combat, forbid ships that are already
                // at or ahead of their division's formation rank from triggering speed-boost systems that break formation!
                if (outOfCombatMarch && alive.size() >= 2 && (prof.category.isLinearVector() || prof.estimatedSpeedBonus > 10f)) {
                    float distFromCentroid = div.centroid != null
                            ? DivisionCommanderAI.distance(ship.getLocation(), div.centroid)
                            : 0f;
                    float alongLead = (div.centroid != null)
                            ? ((ship.getLocation().x - div.centroid.x) * div.formationHeadingX
                            + (ship.getLocation().y - div.centroid.y) * div.formationHeadingY)
                            : 0f;
                    boolean genuineStragglerBehind = distFromCentroid >= Math.max(820f, div.stationKeepingRadius * 1.65f)
                            && alongLead < -280f;
                    if (!genuineStragglerBehind) {
                        ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                        continue;
                    }
                }

                // Extraction Escape Drive Ignition (applies in both SYNCHRONIZED and INDEPENDENT modes):
                // When a ship or division is extracting from danger and has turned its bow toward the escape corridor
                // (away from the enemy, with a clear path free of friendlies and enemies), ignite its movement/ram drive
                // to sprint out of enemy gun range!
                if (isShipExtracting && isShipSystemReady(ship, prof)) {
                    Vector2f escGoal = movDest != null ? movDest : (campLoc != null ? campLoc : div.stationPoint);
                    if (prof.category.isLinearVector() && escGoal != null && effTarget != null) {
                        float distE = DivisionCommanderAI.distance(ship.getLocation(), effTarget.getLocation());
                        float toEx = effTarget.getLocation().x - ship.getLocation().x;
                        float toEy = effTarget.getLocation().y - ship.getLocation().y;
                        double rad = Math.toRadians(ship.getFacing());
                        float hdgX = (float) Math.cos(rad);
                        float hdgY = (float) Math.sin(rad);
                        float dotEnemy = (distE > 1f) ? ((toEx / distE) * hdgX + (toEy / distE) * hdgY) : 0f;
                        if (distE <= Math.max(1250f, div.standoffRange * 1.35f)
                                && dotEnemy < -0.25f
                                && isShipAlignedToPoint(ship, escGoal, 26f)
                                && DivisionCommanderAI.distance(ship.getLocation(), escGoal) >= 420f
                                && isForwardCorridorClearOfFleetFriendlies(engine, ship, alive, myGroup, 780f)
                                && isForwardCorridorClearOfEnemies(engine, ship, enemyGroup, 650f)) {
                            if (flags != null) {
                                flags.unsetFlag(AIFlags.MANEUVER_TARGET);
                                flags.setFlag(AIFlags.DO_NOT_PURSUE, 0.40f);
                                flags.setFlag(AIFlags.RUN_QUICKLY, 0.40f);
                                flags.setFlag(AIFlags.MOVEMENT_DEST, 0.40f, escGoal);
                            }
                            ship.useSystem();
                            anyActiveLinear = true;
                            continue;
                        }
                    } else if ((prof.category == ShipSkillCategory.BLINK_DISPLACER
                            || prof.category == ShipSkillCategory.OMNI_AGILITY_JETS)
                            && effTarget != null
                            && DivisionCommanderAI.distance(ship.getLocation(), effTarget.getLocation()) <= 1150f) {
                        ship.useSystem();
                        continue;
                    }
                }

                if (!syncSkills) continue;

                // ------------------------------------------------------------------
                // B. SYNCHRONIZED LINEAR SKILL DISCIPLINE & TRIGGERING
                // ------------------------------------------------------------------
                if (prof.category.isLinearVector()) {
                    // 1. Out-of-Fire Free Marshaling (Straggler Catch-Up to Division Carriage):
                    // When NOT under enemy fire and a ship has fallen >= 780u behind its own division's centroid
                    // and formation slot (after initial deployment), allow/trigger linear thrust to rapidly rejoin!
                    float distFromCentroid = div.centroid != null
                            ? DivisionCommanderAI.distance(ship.getLocation(), div.centroid)
                            : 0f;
                    if (!underFire && !stage2SyncCharge && now > 6.0f && alive.size() >= 2 && campLoc != null
                            && distFromCentroid >= Math.max(780f, div.stationKeepingRadius * 1.55f)) {
                        float distToSlot = DivisionCommanderAI.distance(ship.getLocation(), campLoc);
                        boolean alignedToSlot = isShipAlignedToPoint(ship, campLoc, 16f);
                        if (distToSlot >= 720f && alignedToSlot
                                && isShipSystemReady(ship, prof)
                                && isForwardCorridorClearOfFleetFriendlies(engine, ship, alive, myGroup, 680f)) {
                            ship.useSystem();
                            anyActiveLinear = true;
                            continue;
                        }
                    }

                    // 2. Per-Division Ramming Mode & Attack Slot Enforcement (1..10 slots against Single Ship or Enemy Division):
                    if (!stage2SyncCharge && prof.isDesignedToRam) {
                        if (ramMode == DivisionRammingMode.OFF) {
                            ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                            continue;
                        }
                        if (ramMode == DivisionRammingMode.SYNCHRONIZED_LANCE || stage1Marshaling) {
                            // During SYNCHRONIZED_LANCE / Stage 1 Marshaling: hold solo ram-drive ignition so the division
                            // never attacks piecemeal, while actively aligning every assigned rammer's bow onto its target
                            // in the conducive localized space so all assigned rammers ignite together in Stage 2!
                            if (assignedSlotTarget != null && !isShipExtracting
                                    && DivisionCommanderAI.distance(ship.getLocation(), assignedSlotTarget.getLocation()) <= 2150f) {
                                Vector2f prepAim = computeRamInterceptPoint(
                                        ship, assignedSlotTarget, sIdx, div.targetEnemyDivision, ramMode);
                                if (flags != null) {
                                    flags.unsetFlag(AIFlags.CAMP_LOCATION);
                                    flags.unsetFlag(AIFlags.BACK_OFF);
                                    flags.unsetFlag(AIFlags.DO_NOT_PURSUE);
                                    flags.setFlag(AIFlags.DO_NOT_BACK_OFF, 0.25f);
                                    flags.setFlag(AIFlags.MANEUVER_TARGET, 0.25f, assignedSlotTarget);
                                    flags.setFlag(AIFlags.MOVEMENT_DEST, 0.25f, prepAim);
                                }
                                ship.setShipTarget(assignedSlotTarget);
                                steerShipHelmOntoRamTarget(ship, prepAim, isShipAlignedToPoint(ship, prepAim, 18f));
                            }
                            ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                            continue;
                        }
                        ShipAPI slotTarget = assignedSlotTarget;
                        boolean allowOpportunisticRam = slotTarget != null
                                && !scoutingOrStandoffPosture
                                && !hasBackOff
                                && !isShipExtracting
                                && div.currentIntent != DivisionIntentType.CLOSE_UP
                                && div.currentIntent != DivisionIntentType.MARSHAL_FOR_STRIKE
                                && div.currentIntent != DivisionIntentType.HAUL_OUT_TO_VENT
                                && div.currentIntent != DivisionIntentType.BREAK_OFF_AND_RETIRE
                                && !isStationOrStationModule(slotTarget)
                                && (ramMode == DivisionRammingMode.DIRECT_RAM
                                        ? (ship.getFluxLevel() < 0.72f && ship.getHullLevel() > 0.35f)
                                        : (div.crossfireCordonIndex < 0.42f
                                                && div.localForceRatio >= 0.80f
                                                && ship.getFluxLevel() < 0.48f
                                                && ship.getHullLevel() > 0.58f));
                        if (allowOpportunisticRam) {
                            float distE = DivisionCommanderAI.distance(ship.getLocation(), slotTarget.getLocation());
                            Vector2f ramAim = computeRamInterceptPoint(ship, slotTarget, sIdx, div.targetEnemyDivision, ramMode);
                            float alignTol = (ramMode == DivisionRammingMode.DIRECT_RAM) ? 34f : 26f;
                            if (distE <= 1450f) {
                                if (flags != null) {
                                    flags.unsetFlag(AIFlags.CAMP_LOCATION);
                                    flags.unsetFlag(AIFlags.BACK_OFF);
                                    flags.unsetFlag(AIFlags.DO_NOT_PURSUE);
                                    flags.setFlag(AIFlags.DO_NOT_BACK_OFF, 0.35f);
                                    flags.setFlag(AIFlags.HARASS_MOVE_IN, 0.35f);
                                    flags.setFlag(AIFlags.PURSUING, 0.35f);
                                    flags.setFlag(AIFlags.MANEUVER_TARGET, 0.35f, slotTarget);
                                    flags.setFlag(AIFlags.MOVEMENT_DEST, 0.35f, ramAim);
                                }
                                ship.setShipTarget(slotTarget);
                                steerShipHelmOntoRamTarget(ship, ramAim, distE <= 1250f);
                            }
                            if (distE >= 260f && distE <= 1350f
                                    && isShipAlignedToPoint(ship, ramAim, alignTol)
                                    && isForwardCorridorClearOfFleetFriendlies(engine, ship, alive, myGroup, 760f)
                                    && isShipSystemReady(ship, prof)) {
                                LOCKED_RAM_TARGETS.put(ship, slotTarget);
                                ship.useSystem();
                                anyActiveLinear = true;
                                continue;
                            }
                        }
                        // Otherwise hold ram drive activation until aligned onto an open slot target in a conducive space
                        ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                        continue;
                    }

                    // 3. Under-Fire / Scouting / Standoff / Stage-1 Marshaling Discipline Gate for Non-Ramming Linear Skills:
                    if (!stage2SyncCharge) {
                        boolean blockSoloBurst = scoutingOrStandoffPosture
                                || stage1Marshaling
                                || hasDoNotPursue
                                || hasBackOff
                                || div.currentIntent == DivisionIntentType.CLOSE_UP
                                || div.currentIntent == DivisionIntentType.MARSHAL_FOR_STRIKE
                                || (underFire && prof.dropsShield && div.getAliveMembers().size() >= 2);
                        if (blockSoloBurst) {
                            ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                            continue;
                        }
                    }

                    // 4. Stage 2 Synchronized Division / OTC Charge Ignition:
                    if (stage2SyncCharge && effTarget != null && isShipSystemReady(ship, prof)) {
                        float distE = DivisionCommanderAI.distance(ship.getLocation(), effTarget.getLocation());
                        boolean explicitLanceRam = ramMode != DivisionRammingMode.OFF
                                && (ramMode == DivisionRammingMode.SYNCHRONIZED_LANCE
                                        || ramMode == DivisionRammingMode.DIRECT_RAM
                                        || ramMode == DivisionRammingMode.GLANCING_SKIRMISH
                                        || div.activeSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE
                                        || div.doctrinalSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE
                                        || (div.otcOrder != null && div.otcOrder.isActive(now)
                                        && div.otcOrder.orderedSetpieceAttack == DivisionSetpieceAttack.LANCE_CHARGE));
                        if (prof.isDesignedToRam && explicitLanceRam && !isStationOrStationModule(effTarget)) {
                            ShipAPI slotTarget = assignedSlotTarget;
                            if (slotTarget != null) {
                                float slotDist = DivisionCommanderAI.distance(ship.getLocation(), slotTarget.getLocation());
                                Vector2f ramAim = computeRamInterceptPoint(
                                        ship, slotTarget, sIdx, div.targetEnemyDivision, ramMode);
                                if (slotDist <= 1850f) {
                                    // Always steer assigned Stage 2 rammers directly onto their assigned target so any wingman
                                    // slightly off-angle snaps onto bore and ignites immediately alongside its division mates!
                                    if (flags != null) {
                                        flags.unsetFlag(AIFlags.CAMP_LOCATION);
                                        flags.unsetFlag(AIFlags.BACK_OFF);
                                        flags.unsetFlag(AIFlags.DO_NOT_PURSUE);
                                        flags.setFlag(AIFlags.DO_NOT_BACK_OFF, 0.5f);
                                        flags.setFlag(AIFlags.HARASS_MOVE_IN, 0.5f);
                                        flags.setFlag(AIFlags.PURSUING, 0.5f);
                                        flags.setFlag(AIFlags.MANEUVER_TARGET, 0.5f, slotTarget);
                                        flags.setFlag(AIFlags.MOVEMENT_DEST, 0.5f, ramAim);
                                    }
                                    ship.setShipTarget(slotTarget);
                                    steerShipHelmOntoRamTarget(ship, ramAim, true);
                                }
                                if (slotDist >= 220f && slotDist <= 1750f
                                        && isShipAlignedToPoint(ship, ramAim, 18f)
                                        && isForwardCorridorClearOfFleetFriendlies(engine, ship, alive, myGroup, 760f)) {
                                    LOCKED_RAM_TARGETS.put(ship, slotTarget);
                                    ship.useSystem();
                                    anyActiveLinear = true;
                                } else {
                                    ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                                }
                            } else {
                                ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
                            }
                        } else {
                            // Synchronized Non-Ramming Linear Approach (or Ram-Drive Standoff Gap-Close without ramming!):
                            float stopBand = Math.max(580f, div.standoffRange * 0.92f);
                            Vector2f goalPt = campLoc != null ? campLoc : div.stationPoint;
                            if (distE >= stopBand + 380f && distE <= 2550f
                                    && isShipAlignedToPoint(ship, goalPt != null ? goalPt : effTarget.getLocation(), 22f)
                                    && isForwardCorridorClearOfFleetFriendlies(engine, ship, alive, myGroup, 620f)) {
                                ship.useSystem();
                                anyActiveLinear = true;
                            } else if (distE < stopBand + 300f) {
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

    private static ShipAPI resolveEffectiveRamOrSkillTarget(
            ShipAPI ship,
            ShipwideAIFlags flags,
            ShipAPI focusEnemy) {
        if (ship == null) return focusEnemy;
        if (ship.getShipTarget() != null
                && ship.getShipTarget().isAlive()
                && !ship.getShipTarget().isHulk()
                && ship.getShipTarget().getOwner() != ship.getOwner()) {
            float d = DivisionCommanderAI.distance(ship.getLocation(), ship.getShipTarget().getLocation());
            if (d <= 1850f) {
                return ship.getShipTarget();
            }
        }
        if (flags != null && flags.getCustom(AIFlags.MANEUVER_TARGET) instanceof ShipAPI) {
            ShipAPI mt = (ShipAPI) flags.getCustom(AIFlags.MANEUVER_TARGET);
            if (mt.isAlive() && !mt.isHulk() && mt.getOwner() != ship.getOwner()) {
                float d = DivisionCommanderAI.distance(ship.getLocation(), mt.getLocation());
                if (d <= 1850f) {
                    return mt;
                }
            }
        }
        return focusEnemy;
    }

    /**
     * Evaluates the target enemy division's tactical geometry to find a localized space (radius ~950u)
     * that the division commander is confident is conducive to ramming attacks, and assigns up to
     * {@code maxRamSlots} (1..10) ram-capable ships in {@code div} to targets:
     * <ul>
     *   <li>{@link DivisionRammingTargetScope#SINGLE_SHIP}: focuses up to {@code maxRamSlots} rammers onto a single
     *       priority enemy target ship inside the conducive space.</li>
     *   <li>{@link DivisionRammingTargetScope#ENEMY_DIVISION}: selects <b>multiple distinct enemy targets</b> inside
     *       the conducive localized space and distributes up to {@code maxRamSlots} rammers across them in
     *       non-crossing port-to-starboard lanes (1 rammer per target first before doubling up).</li>
     * </ul>
     */
    public static Map<ShipAPI, ShipAPI> computeDivisionRamTargetAssignments(
            CombatEngineAPI engine,
            DivisionState div,
            List<ShipAPI> aliveMembers,
            ShipAPI focusEnemy,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            DivisionRammingMode ramMode,
            DivisionRammingTargetScope ramScope,
            int maxRamSlots) {
        Map<ShipAPI, ShipAPI> assignments = new java.util.LinkedHashMap<>();
        if (div == null || aliveMembers == null || aliveMembers.isEmpty() || ramMode == DivisionRammingMode.OFF) {
            return assignments;
        }
        int cap = Math.max(1, Math.min(10, maxRamSlots));

        // 1. Collect eligible rammers in our division (already active/charging rammers first, then ready rammers)
        List<ShipAPI> eligibleRammers = new ArrayList<>();
        for (int i = 0; i < aliveMembers.size(); i++) {
            ShipAPI m = aliveMembers.get(i);
            if (m == null || !m.isAlive() || m.isHulk() || OtcAI.isPlayerManuallyPiloting(engine, m)) continue;
            ShipSkillProfile mp = getSkillProfile(m);
            if (!mp.isDesignedToRam) continue;
            ShipSystemAPI ms = m.getSystem();
            if (ms == null) continue;
            ShipwideAIFlags mf = m.getAIFlags();
            boolean extracting = (mf != null && mf.hasFlag(AIFlags.RUN_QUICKLY))
                    || div.currentIntent == DivisionIntentType.HAUL_OUT_TO_VENT
                    || div.currentIntent == DivisionIntentType.BREAK_OFF_AND_RETIRE;
            if (extracting) continue;
            boolean activeNow = ms.isActive() || ms.isChargeup();
            if (!activeNow) {
                LOCKED_RAM_TARGETS.remove(m);
            }
            if (activeNow || isShipSystemReady(m, mp)) {
                eligibleRammers.add(m);
            }
        }
        if (eligibleRammers.isEmpty()) {
            return assignments;
        }

        // 2. Gather candidate enemy ships from targetEnemyDivision (or nearby visible enemy ships)
        List<ShipAPI> rawCandidates = new ArrayList<>();
        DivisionState eDiv = div.targetEnemyDivision;
        if (eDiv != null && eDiv.members != null) {
            for (int i = 0; i < eDiv.members.size(); i++) {
                ShipAPI c = eDiv.members.get(i);
                if (isValidEnemyRamCandidate(engine, div.side, c)) {
                    rawCandidates.add(c);
                }
            }
        }
        if (focusEnemy != null && isValidEnemyRamCandidate(engine, div.side, focusEnemy) && !rawCandidates.contains(focusEnemy)) {
            rawCandidates.add(focusEnemy);
        }
        if (rawCandidates.isEmpty() && enemyGroup != null && enemyGroup.divisions != null) {
            for (int d = 0; d < enemyGroup.divisions.size(); d++) {
                DivisionState od = enemyGroup.divisions.get(d);
                if (od == null || od.members == null) continue;
                for (int i = 0; i < od.members.size(); i++) {
                    ShipAPI c = od.members.get(i);
                    if (isValidEnemyRamCandidate(engine, div.side, c)
                            && div.centroid != null
                            && DivisionCommanderAI.distance(div.centroid, c.getLocation()) <= 1850f
                            && !rawCandidates.contains(c)) {
                        rawCandidates.add(c);
                    }
                }
            }
        }
        if (rawCandidates.isEmpty()) {
            return assignments;
        }

        // 3. Evaluate which candidate enemy ships sit in a space conducive to ramming attacks:
        //    - Not buried behind an intervening front-rank enemy hull
        //    - Not inside a lethal enemy crossfire pocket (unless DIRECT_RAM or target is overloaded/collapsed)
        Vector2f divOrigin = div.centroid != null ? div.centroid : eligibleRammers.get(0).getLocation();
        float maxCordonAllowed = (ramMode == DivisionRammingMode.DIRECT_RAM) ? 0.78f : 0.56f;
        List<ShipAPI> conduciveCandidates = new ArrayList<>();
        for (int i = 0; i < rawCandidates.size(); i++) {
            ShipAPI cand = rawCandidates.get(i);
            float distFromDiv = DivisionCommanderAI.distance(divOrigin, cand.getLocation());
            if (distFromDiv > 2150f) continue;
            if (isEnemyScreenedByOtherEnemyHull(divOrigin, cand, rawCandidates)) {
                continue;
            }
            boolean targetVulnerable = (cand.getFluxTracker() != null && cand.getFluxTracker().isOverloadedOrVenting())
                    || cand.getFluxLevel() >= 0.55f
                    || cand.getHullLevel() < 0.55f;
            float localCordon = div.crossfireCordonIndex;
            if (engine != null && enemyGroup != null && distFromDiv > 1f) {
                // Evaluate crossfire cordon at the approach point 220u in front of `cand` toward `divOrigin`
                // (rather than at `cand`'s own center where its port/starboard wingmen sit 180 deg apart!)
                float stepBack = Math.min(220f, distFromDiv * 0.45f);
                float ux = (divOrigin.x - cand.getLocation().x) / distFromDiv;
                float uy = (divOrigin.y - cand.getLocation().y) / distFromDiv;
                Vector2f approachSamplePt = new Vector2f(
                        cand.getLocation().x + ux * stepBack,
                        cand.getLocation().y + uy * stepBack);
                BattlefieldFirepowerEvaluator.PointFirepowerEval eval =
                        BattlefieldFirepowerEvaluator.evaluatePoint(engine, div.side, approachSamplePt, 85f);
                if (eval != null) {
                    localCordon = Math.max(localCordon, eval.cordonIndex);
                }
            }
            boolean spaceConducive = targetVulnerable
                    || ramMode == DivisionRammingMode.DIRECT_RAM
                    || (localCordon <= maxCordonAllowed && (div.localForceRatio >= 0.72f || rawCandidates.size() <= eligibleRammers.size() + 1));
            if (spaceConducive) {
                conduciveCandidates.add(cand);
            }
        }
        if (conduciveCandidates.isEmpty()) {
            return assignments;
        }

        // 4. Find the best localized attack space (radius 950u) with maximum conducive target density & approach geometry
        final float localizedRadius = 950f;
        ShipAPI bestAnchor = conduciveCandidates.get(0);
        float bestAnchorScore = -1e9f;
        for (int i = 0; i < conduciveCandidates.size(); i++) {
            ShipAPI anchor = conduciveCandidates.get(i);
            int clusterCount = 0;
            for (int j = 0; j < conduciveCandidates.size(); j++) {
                ShipAPI other = conduciveCandidates.get(j);
                if (DivisionCommanderAI.distance(anchor.getLocation(), other.getLocation()) <= localizedRadius) {
                    clusterCount++;
                }
            }
            float d = DivisionCommanderAI.distance(divOrigin, anchor.getLocation());
            float score = (ramScope == DivisionRammingTargetScope.ENEMY_DIVISION ? clusterCount * 240f : 0f)
                    - d * 0.45f
                    + anchor.getFluxLevel() * 220f
                    + (anchor == focusEnemy ? 160f : 0f);
            if (score > bestAnchorScore) {
                bestAnchorScore = score;
                bestAnchor = anchor;
            }
        }

        List<ShipAPI> localizedTargets = new ArrayList<>();
        if (ramScope == DivisionRammingTargetScope.SINGLE_SHIP) {
            localizedTargets.add(bestAnchor);
        } else {
            for (int i = 0; i < conduciveCandidates.size(); i++) {
                ShipAPI cand = conduciveCandidates.get(i);
                if (DivisionCommanderAI.distance(bestAnchor.getLocation(), cand.getLocation()) <= localizedRadius) {
                    localizedTargets.add(cand);
                }
            }
            if (localizedTargets.isEmpty()) {
                localizedTargets.add(bestAnchor);
            }
        }

        // 5. Select up to `cap` (1..10) rammers from eligibleRammers (active rammers first, then closest to bestAnchor)
        final Vector2f anchorLoc = bestAnchor.getLocation();
        eligibleRammers.sort((a, b) -> {
            boolean aAct = a.getSystem() != null && (a.getSystem().isActive() || a.getSystem().isChargeup());
            boolean bAct = b.getSystem() != null && (b.getSystem().isActive() || b.getSystem().isChargeup());
            if (aAct != bAct) return aAct ? -1 : 1;
            float da = DivisionCommanderAI.distance(a.getLocation(), anchorLoc);
            float db = DivisionCommanderAI.distance(b.getLocation(), anchorLoc);
            return Float.compare(da, db);
        });
        List<ShipAPI> selectedRammers = new ArrayList<>(Math.min(cap, eligibleRammers.size()));
        for (int i = 0; i < eligibleRammers.size() && i < cap; i++) {
            selectedRammers.add(eligibleRammers.get(i));
        }

        if (ramScope == DivisionRammingTargetScope.SINGLE_SHIP || localizedTargets.size() == 1) {
            ShipAPI singleTgt = localizedTargets.get(0);
            for (int i = 0; i < selectedRammers.size(); i++) {
                assignments.put(selectedRammers.get(i), singleTgt);
            }
            return assignments;
        }

        // 6. ENEMY_DIVISION Multi-Target Assignment:
        //    - First, any rammer whose ram drive is ALREADY active/charging keeps its locked shipTarget if valid,
        //      so active rammers never swap lanes mid-charge!
        //    - Remaining rammers and unassigned targets are sorted port-to-starboard across the axis from divOrigin
        //      to the localized target group's centroid so each rammer gets a non-crossing lane!
        float lcx = 0f, lcy = 0f;
        for (int i = 0; i < localizedTargets.size(); i++) {
            Vector2f tl = localizedTargets.get(i).getLocation();
            lcx += tl.x;
            lcy += tl.y;
        }
        lcx /= localizedTargets.size();
        lcy /= localizedTargets.size();
        float ax = lcx - divOrigin.x;
        float ay = lcy - divOrigin.y;
        float alen = (float) Math.sqrt(ax * ax + ay * ay);
        final float perpX = (alen > 1f) ? (-ay / alen) : -1f;
        final float perpY = (alen > 1f) ? (ax / alen) : 0f;

        selectedRammers.sort((a, b) -> {
            float la = (a.getLocation().x - divOrigin.x) * perpX + (a.getLocation().y - divOrigin.y) * perpY;
            float lb = (b.getLocation().x - divOrigin.x) * perpX + (b.getLocation().y - divOrigin.y) * perpY;
            return Float.compare(la, lb);
        });
        localizedTargets.sort((a, b) -> {
            float la = (a.getLocation().x - divOrigin.x) * perpX + (a.getLocation().y - divOrigin.y) * perpY;
            float lb = (b.getLocation().x - divOrigin.x) * perpX + (b.getLocation().y - divOrigin.y) * perpY;
            return Float.compare(la, lb);
        });

        int numRammers = selectedRammers.size();
        int numTargets = localizedTargets.size();
        int maxPerTarget = Math.max(1, (numRammers + numTargets - 1) / numTargets);
        Map<ShipAPI, Integer> assignedCountPerTarget = new java.util.HashMap<>();

        // Pass 1: Honor locked targets for rammers whose ram drive is already active/charging (up to maxPerTarget)
        for (int i = 0; i < numRammers; i++) {
            ShipAPI rammer = selectedRammers.get(i);
            ShipSystemAPI rs = rammer.getSystem();
            ShipAPI lockedTgt = LOCKED_RAM_TARGETS.get(rammer);
            if (rs != null && (rs.isActive() || rs.isChargeup())
                    && lockedTgt != null && localizedTargets.contains(lockedTgt)) {
                int used = assignedCountPerTarget.getOrDefault(lockedTgt, 0);
                if (used < maxPerTarget) {
                    assignments.put(rammer, lockedTgt);
                    assignedCountPerTarget.put(lockedTgt, used + 1);
                }
            }
        }

        // Pass 2: Assign remaining rammers in port-to-starboard order to targets with the fewest assigned rammers
        for (int i = 0; i < numRammers; i++) {
            ShipAPI rammer = selectedRammers.get(i);
            if (assignments.containsKey(rammer)) continue;
            int preferredIdx = (numTargets >= numRammers)
                    ? ((i * numTargets) / numRammers)
                    : (i % numTargets);
            ShipAPI chosen = localizedTargets.get(preferredIdx);
            if (assignedCountPerTarget.getOrDefault(chosen, 0) > 0 && numTargets > 1) {
                // Find an unassigned target in localizedTargets first so every target in the localized space gets hit!
                for (int offset = 0; offset < numTargets; offset++) {
                    ShipAPI cand = localizedTargets.get((preferredIdx + offset) % numTargets);
                    if (assignedCountPerTarget.getOrDefault(cand, 0) == 0) {
                        chosen = cand;
                        break;
                    }
                }
            }
            assignments.put(rammer, chosen);
            assignedCountPerTarget.put(chosen, assignedCountPerTarget.getOrDefault(chosen, 0) + 1);
            ShipSystemAPI rs = rammer.getSystem();
            if (rs != null && (rs.isActive() || rs.isChargeup())) {
                LOCKED_RAM_TARGETS.put(rammer, chosen);
            }
        }
        return assignments;
    }

    private static boolean isValidEnemyRamCandidate(CombatEngineAPI engine, int mySide, ShipAPI cand) {
        if (cand == null || !cand.isAlive() || cand.isHulk() || cand.isFighter() || cand.isDrone()) {
            return false;
        }
        if (cand.getOwner() == mySide || isStationOrStationModule(cand) || cand.getLocation() == null) {
            return false;
        }
        return OtcAI.isShipVisibleToSide(engine, mySide, cand);
    }

    private static boolean isEnemyScreenedByOtherEnemyHull(
            Vector2f fromOrigin,
            ShipAPI target,
            List<ShipAPI> allEnemyCandidates) {
        if (fromOrigin == null || target == null || target.getLocation() == null || allEnemyCandidates == null) {
            return false;
        }
        Vector2f tLoc = target.getLocation();
        float dx = tLoc.x - fromOrigin.x;
        float dy = tLoc.y - fromOrigin.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 180f) return false;
        float ux = dx / dist;
        float uy = dy / dist;
        for (int i = 0; i < allEnemyCandidates.size(); i++) {
            ShipAPI other = allEnemyCandidates.get(i);
            if (other == null || other == target || other.getLocation() == null) continue;
            float ox = other.getLocation().x - fromOrigin.x;
            float oy = other.getLocation().y - fromOrigin.y;
            float proj = ox * ux + oy * uy;
            if (proj <= 110f || proj >= dist - 120f) continue;
            // Only consider `other` as screening `target` if `other` is genuinely closer in depth along the approach
            // (not merely a side-by-side ship in the same enemy rank!)
            if (dist - proj < 135f) continue;
            float perp = Math.abs(ox * (-uy) + oy * ux);
            float blockRadius = Math.max(55f, other.getCollisionRadius() * 0.72f);
            if (perp < blockRadius) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drives the ship's helm directly onto {@code aimPt} during a committed ram run or Stage 1/2 lance alignment,
     * overriding vanilla {@code BasicShipAI}'s tendency to brake or swerve around enemy collision circles.
     */
    private static void steerShipHelmOntoRamTarget(ShipAPI ship, Vector2f aimPt, boolean fullForwardThrust) {
        if (ship == null || aimPt == null || ship.getLocation() == null) return;
        float dx = aimPt.x - ship.getLocation().x;
        float dy = aimPt.y - ship.getLocation().y;
        if (dx * dx + dy * dy < 16f) return;
        float desiredAng = (float) Math.toDegrees(Math.atan2(dy, dx));
        float diff = shortestAngleDiff(ship.getFacing(), desiredAng);
        if (diff > 2.2f) {
            ship.blockCommandForOneFrame(ShipCommand.TURN_RIGHT);
            ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
        } else if (diff < -2.2f) {
            ship.blockCommandForOneFrame(ShipCommand.TURN_LEFT);
            ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
        }
        if (fullForwardThrust && Math.abs(diff) <= 42f) {
            ship.blockCommandForOneFrame(ShipCommand.DECELERATE);
            ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
            ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        }
    }

    /**
     * Drives a ship's helm directly onto an uncaptured Control Point (or its tight capture slot inside the point's
     * 500u capture ring), overriding vanilla {@code BasicShipAI}'s {@code ooOo} maneuver which otherwise turns to face
     * and orbit any enemy within 2,200u when {@code dist >= 1,000u} and brakes at {@code 500u}.
     */
    public static void steerShipHelmOntoObjectivePoint(
            ShipAPI ship,
            Vector2f helmGoal,
            float distToGoal,
            float distToObj) {
        if (ship == null || helmGoal == null || ship.getLocation() == null) return;
        if (ship.getFluxTracker() != null && ship.getFluxTracker().isOverloadedOrVenting()) return;
        Vector2f sLoc = ship.getLocation();
        Vector2f vel = ship.getVelocity();
        float vx = (vel != null) ? vel.x : 0f;
        float vy = (vel != null) ? vel.y : 0f;
        float spd = (float) Math.sqrt(vx * vx + vy * vy);

        // Once inside the Control Point capture circle (<= 340u from objective center and <= 200u from slot),
        // brake if carrying excess speed so the ship settles inside the capture ring instead of coasting through.
        if (distToObj <= 340f && distToGoal <= 200f) {
            if (spd > 32f) {
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            }
            return;
        }

        // Between 340u and 420u of the objective center, allow weapons to bear on contesting enemies unless the ship
        // is drifting outward or still > 240u from its capture slot.
        if (distToObj <= 420f && distToGoal <= 240f) {
            float toGoalX = helmGoal.x - sLoc.x;
            float toGoalY = helmGoal.y - sLoc.y;
            float inVel = (distToGoal > 1f) ? ((toGoalX * vx + toGoalY * vy) / distToGoal) : 0f;
            if (inVel >= -8f && spd <= 45f) {
                return;
            }
        }

        float dx = helmGoal.x - sLoc.x;
        float dy = helmGoal.y - sLoc.y;
        if (dx * dx + dy * dy < 25f) return;
        float desiredAng = (float) Math.toDegrees(Math.atan2(dy, dx));
        float diff = shortestAngleDiff(ship.getFacing(), desiredAng);
        if (diff > 2.2f) {
            ship.blockCommandForOneFrame(ShipCommand.TURN_RIGHT);
            ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
        } else if (diff < -2.2f) {
            ship.blockCommandForOneFrame(ShipCommand.TURN_LEFT);
            ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
        }

        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
        if (Math.abs(diff) <= 65f) {
            if (distToGoal <= 240f && spd > 55f) {
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            } else {
                ship.blockCommandForOneFrame(ShipCommand.DECELERATE);
                ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
                ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
                ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
            }
        } else {
            // While swinging the bow around from an enemy toward the Control Point, strafe toward the Control Point!
            ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
            if (diff > 0f) {
                ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
                ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
            } else {
                ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
                ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
            }
        }
    }

    /**
     * Computes the lead-compensated hull intercept aim point for {@code ship} ramming {@code target}.
     * <ul>
     *   <li>{@link DivisionRammingMode#DIRECT_RAM} and {@link DivisionRammingMode#SYNCHRONIZED_LANCE}: aims directly
     *       into the target's lead-compensated hull with a slight off-center torque bias (0.14x target radius) so the
     *       rammer's bow physically impacts the hull solidly.</li>
     *   <li>{@link DivisionRammingMode#GLANCING_SKIRMISH}: aims into the target's port/starboard hull shoulder
     *       (0.24x target radius, well inside the physical collision bounds so the blow connects!) and flares outward
     *       after impact to peel away cleanly.</li>
     * </ul>
     */
    public static Vector2f computeRamInterceptPoint(
            ShipAPI ship,
            ShipAPI target,
            int shipIdx,
            DivisionState enemyDiv,
            DivisionRammingMode ramMode) {
        if (ramMode == DivisionRammingMode.DIRECT_RAM || ramMode == DivisionRammingMode.SYNCHRONIZED_LANCE) {
            if (ship == null || target == null || ship.getLocation() == null || target.getLocation() == null) {
                return target != null && target.getLocation() != null ? new Vector2f(target.getLocation()) : new Vector2f();
            }
            Vector2f sLoc = ship.getLocation();
            Vector2f tLoc = target.getLocation();
            Vector2f tVel = target.getVelocity();
            Vector2f sVel = ship.getVelocity();
            float rawDist = DivisionCommanderAI.distance(sLoc, tLoc);
            float currSpd = (sVel != null)
                    ? (float) Math.sqrt(sVel.x * sVel.x + sVel.y * sVel.y)
                    : ship.getMaxSpeed();
            float closingSpeed = Math.max(260f, currSpd);
            float leadTime = Math.min(0.65f, rawDist / closingSpeed);
            float cx = tLoc.x + (tVel != null ? tVel.x * leadTime : 0f);
            float cy = tLoc.y + (tVel != null ? tVel.y * leadTime : 0f);
            float dx = cx - sLoc.x;
            float dy = cy - sLoc.y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < 1f) return new Vector2f(cx, cy);
            float appX = dx / dist;
            float appY = dy / dist;
            // Push the aim point 90u through the target's hull along the approach chord so BasicShipAI never brakes short!
            return new Vector2f(cx + appX * 90f, cy + appY * 90f);
        }
        return computeGlancingSideRamPoint(ship, target, shipIdx, enemyDiv);
    }

    /**
     * Computes a glancing hull-shoulder aim point along the port or starboard side of {@code target}'s hull.
     * Aims inside the target's physical hull shoulder (~0.24x target collision radius off-center) with velocity lead
     * until physical contact occurs, imparting rotational spin on impact and then flaring outward to deflect past.
     */
    public static Vector2f computeGlancingSideRamPoint(
            ShipAPI ship,
            ShipAPI target,
            int shipIdx,
            DivisionState enemyDiv) {
        if (ship == null || target == null || ship.getLocation() == null || target.getLocation() == null) {
            return target != null && target.getLocation() != null ? new Vector2f(target.getLocation()) : new Vector2f();
        }
        Vector2f sLoc = ship.getLocation();
        Vector2f tLoc = target.getLocation();
        Vector2f tVel = target.getVelocity();
        float rawDist = DivisionCommanderAI.distance(sLoc, tLoc);
        float leadTime = Math.min(0.55f, rawDist / 380f);
        float cx = tLoc.x + (tVel != null ? tVel.x * leadTime : 0f);
        float cy = tLoc.y + (tVel != null ? tVel.y * leadTime : 0f);

        float dx = cx - sLoc.x;
        float dy = cy - sLoc.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) {
            return new Vector2f(cx, cy);
        }
        float appX = dx / dist;
        float appY = dy / dist;
        // Perpendicular lateral normal to the approach chord (+1 = port/left of approach, -1 = starboard/right)
        float perpX = -appY;
        float perpY = appX;

        // 1. Favor the side the ramming ship's bow is already slightly angled toward (so it never crosses the target's bow)
        double sRadAng = Math.toRadians(ship.getFacing());
        float sHdgX = (float) Math.cos(sRadAng);
        float sHdgY = (float) Math.sin(sRadAng);
        float headingBias = (sHdgX * perpX + sHdgY * perpY) * 1.6f;

        // 2. Favor the side away from the enemy division's centroid (so after glancing off the target's side,
        //    the rammer exits toward open space rather than into the middle of adjacent enemy ships)
        float openSpaceBias = 0f;
        if (enemyDiv != null && enemyDiv.centroid != null) {
            float ecx = enemyDiv.centroid.x - tLoc.x;
            float ecy = enemyDiv.centroid.y - tLoc.y;
            float ecLen = (float) Math.sqrt(ecx * ecx + ecy * ecy);
            if (ecLen > 40f) {
                float towardClusterDot = (ecx / ecLen) * perpX + (ecy / ecLen) * perpY;
                openSpaceBias = -towardClusterDot * 1.25f;
            }
        }

        float tieBreaker = (shipIdx % 2 == 0) ? 0.18f : -0.18f;
        float sideSign = (headingBias + openSpaceBias + tieBreaker >= 0f) ? 1f : -1f;

        float tRad = Math.max(45f, target.getCollisionRadius());
        float sRad = Math.max(35f, ship.getCollisionRadius());
        float combinedRad = tRad + sRad;
        // Aim inside the target's physical hull shoulder (0.24x target collision radius) until physical hull contact
        // occurs (dist <= 0.58x combinedRad), then flare outward so the ship deflects past after striking!
        boolean inPhysicalContact = dist <= (combinedRad * 0.58f);
        float shoulderOffset = Math.min(tRad * 0.24f, combinedRad * 0.16f);
        float lateralOffset = inPhysicalContact ? (combinedRad * 0.68f) : shoulderOffset;
        float tangentPassThrough = inPhysicalContact ? 175f : 95f;

        return new Vector2f(
                cx + perpX * sideSign * lateralOffset + appX * tangentPassThrough,
                cy + perpY * sideSign * lateralOffset + appY * tangentPassThrough);
    }

    private static int countRammersAlignedToAssignedTargets(
            CombatEngineAPI engine,
            DivisionState div,
            List<ShipAPI> alive,
            ShipAPI focusEnemy,
            BattleGroupState myGroup,
            BattleGroupState enemyGroup,
            float maxAngleDeg) {
        if (alive == null || alive.isEmpty()) return 0;
        Map<ShipAPI, ShipAPI> map = computeDivisionRamTargetAssignments(
                engine, div, alive, focusEnemy, myGroup, enemyGroup,
                div.rammingMode, div.rammingTargetScope, div.maxRammingAttackSlots);
        int count = 0;
        for (int i = 0; i < alive.size(); i++) {
            ShipAPI s = alive.get(i);
            ShipAPI tgt = map.get(s);
            if (tgt == null) tgt = focusEnemy;
            if (s != null && tgt != null && tgt.getLocation() != null) {
                Vector2f aim = computeRamInterceptPoint(s, tgt, i, div.targetEnemyDivision, div.rammingMode);
                if (isShipAlignedToPoint(s, aim, maxAngleDeg)) {
                    count++;
                }
            }
        }
        return count;
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

    public static boolean isForwardCorridorClearOfFleetFriendlies(
            CombatEngineAPI engine,
            ShipAPI ship,
            List<ShipAPI> divPeers,
            BattleGroupState myGroup,
            float checkDist) {
        return findImminentFriendlyInForwardPath(engine, ship, divPeers, myGroup, checkDist) == null;
    }

    /**
     * Scans all friendly ships across the division and entire fleet (intra-division AND inter-division)
     * to check if any friendly hull lies in {@code ship}'s forward heading cone OR on a predictive velocity
     * collision course (TCPA/DCPA) within {@code checkDist}.
     */
    public static ShipAPI findImminentFriendlyInForwardPath(
            CombatEngineAPI engine,
            ShipAPI ship,
            List<ShipAPI> divPeers,
            BattleGroupState myGroup,
            float checkDist) {
        if (ship == null || ship.getLocation() == null) return null;
        double rad = Math.toRadians(ship.getFacing());
        float fx = (float) Math.cos(rad);
        float fy = (float) Math.sin(rad);
        float myR = Math.max(40f, ship.getCollisionRadius());
        float myVx = (ship.getVelocity() != null) ? ship.getVelocity().x : (fx * ship.getMaxSpeed());
        float myVy = (ship.getVelocity() != null) ? ship.getVelocity().y : (fy * ship.getMaxSpeed());
        // Include forward boost projection along bow
        float projVx = myVx * 0.5f + fx * Math.max(180f, ship.getMaxSpeed() * 1.6f) * 0.5f;
        float projVy = myVy * 0.5f + fy * Math.max(180f, ship.getMaxSpeed() * 1.6f) * 0.5f;

        if (myGroup != null && myGroup.divisions != null) {
            for (int dIdx = 0; dIdx < myGroup.divisions.size(); dIdx++) {
                DivisionState d = myGroup.divisions.get(dIdx);
                if (d == null) continue;
                List<ShipAPI> members = d.getAliveMembers();
                for (int i = 0; i < members.size(); i++) {
                    ShipAPI other = members.get(i);
                    boolean sameDiv = (divPeers != null && divPeers.contains(other));
                    if (isFriendlyBlockingForwardRayOrTcpa(ship, other, sameDiv, fx, fy, myR, projVx, projVy, checkDist)) {
                        return other;
                    }
                }
            }
        } else if (divPeers != null) {
            for (int i = 0; i < divPeers.size(); i++) {
                ShipAPI other = divPeers.get(i);
                if (isFriendlyBlockingForwardRayOrTcpa(ship, other, true, fx, fy, myR, projVx, projVy, checkDist)) {
                    return other;
                }
            }
        }
        if (engine != null && engine.getShips() != null) {
            List<ShipAPI> allShips = engine.getShips();
            for (int i = 0; i < allShips.size(); i++) {
                ShipAPI other = allShips.get(i);
                if (other == null || other.getOwner() != ship.getOwner() || other.isFighter()) continue;
                boolean sameDiv = (divPeers != null && divPeers.contains(other));
                if (isFriendlyBlockingForwardRayOrTcpa(ship, other, sameDiv, fx, fy, myR, projVx, projVy, checkDist)) {
                    return other;
                }
            }
        }
        return null;
    }

    private static boolean isFriendlyBlockingForwardRayOrTcpa(
            ShipAPI ship,
            ShipAPI other,
            boolean sameDivisionPeer,
            float fx,
            float fy,
            float myR,
            float projVx,
            float projVy,
            float checkDist) {
        if (other == null || other == ship || !other.isAlive() || other.isHulk() || other.isFighter() || other.getLocation() == null) {
            return false;
        }
        float dx = other.getLocation().x - ship.getLocation().x;
        float dy = other.getLocation().y - ship.getLocation().y;
        float distSq = dx * dx + dy * dy;
        float maxCheck = checkDist + 160f;
        if (distSq > maxCheck * maxCheck) return false;
        float dist = (float) Math.sqrt(distSq);

        float otherR = Math.max(40f, other.getCollisionRadius());
        // Same-division wingmen dress in parallel lanes (160u-240u apart), so only flag a same-division peer if it lies
        // genuinely within our physical hull beam (0.48x combined radius) inside a narrow forward cone (fwdProj > 0.84*dist).
        // Cross-division ships use a wider 0.74x combined radius (+15u) corridor so divisions thread cleanly around each other.
        float clearance = sameDivisionPeer
                ? ((myR + otherR) * 0.48f)
                : ((myR + otherR) * 0.74f + 15f);

        // 1. Direct geometric corridor strictly AHEAD of current bow heading (never side-by-side)
        float fwdProj = dx * fx + dy * fy;
        float minFwdProj = sameDivisionPeer ? Math.max((myR + otherR) * 0.52f, dist * 0.84f) : ((myR + otherR) * 0.32f);
        if (fwdProj > minFwdProj && fwdProj <= checkDist) {
            float latDist = Math.abs(dx * (-fy) + dy * fx);
            float coneAllowance = clearance + (sameDivisionPeer ? 0f : Math.max(0f, fwdProj) * 0.04f);
            if (latDist < coneAllowance) {
                return true;
            }
        }

        // 2. Predictive velocity TCPA/DCPA check over next 2.2s (only when closing on a friendly ahead of our beam)
        if (fwdProj > (sameDivisionPeer ? dist * 0.75f : -myR * 0.15f)) {
            float oVx = (other.getVelocity() != null) ? other.getVelocity().x : 0f;
            float oVy = (other.getVelocity() != null) ? other.getVelocity().y : 0f;
            float rvx = projVx - oVx;
            float rvy = projVy - oVy;
            float rvSq = rvx * rvx + rvy * rvy;
            if (rvSq > 25f) {
                float tcpa = (dx * rvx + dy * rvy) / rvSq;
                if (tcpa > 0.08f && tcpa <= (sameDivisionPeer ? 1.4f : 2.2f)) {
                    float cpaX = dx - rvx * tcpa;
                    float cpaY = dy - rvy * tcpa;
                    if (cpaX * cpaX + cpaY * cpaY < clearance * clearance) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Vector2f computeLateralAvoidancePoint(ShipAPI ship, ShipAPI obstacle) {
        if (ship == null || obstacle == null || ship.getLocation() == null || obstacle.getLocation() == null) return null;
        float dx = obstacle.getLocation().x - ship.getLocation().x;
        float dy = obstacle.getLocation().y - ship.getLocation().y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return null;
        float nx = dx / len;
        float ny = dy / len;
        // Perpendicular tangent away from obstacle's center
        float perpX = -ny;
        float perpY = nx;
        double rad = Math.toRadians(ship.getFacing());
        float hdgX = (float) Math.cos(rad);
        float hdgY = (float) Math.sin(rad);
        float sideSign = (hdgX * perpX + hdgY * perpY >= 0f) ? 1f : -1f;
        float offset = (ship.getCollisionRadius() + obstacle.getCollisionRadius()) * 1.35f + 120f;
        return new Vector2f(
                ship.getLocation().x + perpX * sideSign * offset - nx * 80f,
                ship.getLocation().y + perpY * sideSign * offset - ny * 80f);
    }

    private static boolean isStationOrStationModule(ShipAPI target) {
        return target != null && (target.isStation() || target.isStationModule() || target.getParentStation() != null);
    }

    private static boolean isForwardCorridorClearOfEnemies(
            CombatEngineAPI engine,
            ShipAPI ship,
            BattleGroupState enemyGroup,
            float checkDist) {
        if (ship == null || ship.getLocation() == null || enemyGroup == null) return true;
        List<DivisionState> effEnemyDivs = OtcAI.getEffectiveEnemyDivisions(engine, enemyGroup);
        if (effEnemyDivs.isEmpty()) return true;
        double rad = Math.toRadians(ship.getFacing());
        float fx = (float) Math.cos(rad);
        float fy = (float) Math.sin(rad);
        float myR = Math.max(40f, ship.getCollisionRadius());
        for (int dIdx = 0; dIdx < effEnemyDivs.size(); dIdx++) {
            DivisionState eDiv = effEnemyDivs.get(dIdx);
            if (eDiv == null) continue;
            List<ShipAPI> eMembers = eDiv.getAliveMembers();
            for (int i = 0; i < eMembers.size(); i++) {
                ShipAPI es = eMembers.get(i);
                if (es == null || !es.isAlive() || es.isHulk() || es.getLocation() == null) continue;
                if (!OtcAI.isShipVisibleToSide(engine, ship.getOwner(), es)) continue;
                float dx = es.getLocation().x - ship.getLocation().x;
                float dy = es.getLocation().y - ship.getLocation().y;
                float fwdProj = dx * fx + dy * fy;
                if (fwdProj <= 0f || fwdProj > checkDist) continue;
                float latDist = Math.abs(dx * (-fy) + dy * fx);
                float clearance = (myR + Math.max(45f, es.getCollisionRadius())) * 1.15f + 50f;
                if (latDist < clearance) {
                    return false;
                }
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
