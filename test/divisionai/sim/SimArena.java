package divisionai.sim;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CollisionGridAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.FogOfWarAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.DerivedWeaponStatsAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import divisionai.DivisionCommanderAI;
import divisionai.DivisionCombatLog;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.DivisionState;
import divisionai.OtcAI;
import divisionai.ShipCapabilityProfile;
import org.lwjgl.util.vector.Vector2f;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Headless 2D Tactical Simulation Arena for Starsector Division AI.
 *
 * Runs the unmodified production C2 stack (OtcAI, OtcBattlePlanner, DivisionCommanderAI)
 * against a deterministic 2D ship physics & vanilla-AI flag interpreter at >500x real time,
 * and exports interactive HTML5 Canvas battle replays for visual inspection.
 */
public class SimArena {

    public static class SimWeaponMount {
        public final String id;
        public final WeaponType type;
        public final WeaponSize size;
        public final DamageType damageType;
        public final float range;
        public final float arcDeg;
        public final float arcFacingRelDeg;
        public final float burstDamage;
        public final float sustainedDps;
        public final float fluxPerShot;
        public final float cooldownDuration;
        public float cooldownRemaining = 0f;
        public final boolean usesAmmo;
        public int maxAmmo;
        public int ammo;
        public final boolean isPd;
        public final boolean isStrike;
        public final boolean isHybridFlak;
        public boolean disabled = false;
        public float disabledTimer = 0f;
        public WeaponAPI proxy;

        public SimWeaponMount(
                String id,
                WeaponType type,
                WeaponSize size,
                DamageType damageType,
                float range,
                float arcDeg,
                float arcFacingRelDeg,
                float burstDamage,
                float sustainedDps,
                float fluxPerShot,
                float cooldownDuration,
                boolean usesAmmo,
                int maxAmmo,
                boolean isPd,
                boolean isStrike,
                boolean isHybridFlak) {
            this.id = id;
            this.type = type;
            this.size = size;
            this.damageType = damageType;
            this.range = range;
            this.arcDeg = arcDeg;
            this.arcFacingRelDeg = arcFacingRelDeg;
            this.burstDamage = burstDamage;
            this.sustainedDps = sustainedDps;
            this.fluxPerShot = fluxPerShot;
            this.cooldownDuration = cooldownDuration;
            this.usesAmmo = usesAmmo;
            this.maxAmmo = maxAmmo;
            this.ammo = maxAmmo;
            this.isPd = isPd;
            this.isStrike = isStrike;
            this.isHybridFlak = isHybridFlak;
            this.proxy = createWeaponProxy();
        }

        private WeaponAPI createWeaponProxy() {
            final EnumSet<AIHints> hints = EnumSet.noneOf(AIHints.class);
            if (isPd) {
                hints.add(AIHints.PD);
                if (!isHybridFlak) {
                    hints.add(AIHints.PD_ONLY);
                } else {
                    hints.add(AIHints.PD_ALSO);
                }
            }
            if (isStrike) {
                hints.add(AIHints.STRIKE);
            }
            final DerivedWeaponStatsAPI derivedProxy = (DerivedWeaponStatsAPI) Proxy.newProxyInstance(
                    DerivedWeaponStatsAPI.class.getClassLoader(),
                    new Class<?>[]{DerivedWeaponStatsAPI.class},
                    (dp, dm, da) -> {
                        String dn = dm.getName();
                        if ("getBurstDamage".equals(dn) || "getDamagePerShot".equals(dn)) return burstDamage;
                        if ("getDps".equals(dn)) return Math.max(sustainedDps, burstDamage / Math.max(0.5f, cooldownDuration));
                        if ("getSustainedDps".equals(dn)) return sustainedDps;
                        if ("getFluxPerDam".equals(dn)) return fluxPerShot / Math.max(1f, burstDamage);
                        if ("getSustainedFluxPerSecond".equals(dn)) return (fluxPerShot / Math.max(0.5f, cooldownDuration));
                        Class<?> drt = dm.getReturnType();
                        if (drt == boolean.class) return false;
                        if (drt == int.class) return 0;
                        if (drt == float.class) return 0f;
                        return null;
                    });

            return (WeaponAPI) Proxy.newProxyInstance(
                    WeaponAPI.class.getClassLoader(),
                    new Class<?>[]{WeaponAPI.class},
                    (p, m, a) -> {
                        String n = m.getName();
                        if ("getId".equals(n) || "getDisplayName".equals(n)) return id;
                        if ("getRange".equals(n)) return range;
                        if ("getArc".equals(n)) return arcDeg;
                        if ("getArcFacing".equals(n)) return arcFacingRelDeg;
                        if ("getType".equals(n)) return type;
                        if ("getSize".equals(n)) return size;
                        if ("getDamageType".equals(n)) return damageType;
                        if ("getDerivedStats".equals(n)) return derivedProxy;
                        if ("getCooldown".equals(n)) return cooldownDuration;
                        if ("getCooldownRemaining".equals(n)) return cooldownRemaining;
                        if ("getAIHints".equals(n)) return hints;
                        if ("hasAIHint".equals(n)) return a != null && a.length > 0 && hints.contains(a[0]);
                        if ("isDecorative".equals(n) || "isPermanentlyDisabled".equals(n)) return Boolean.FALSE;
                        if ("isDisabled".equals(n)) return disabled || disabledTimer > 0f;
                        if ("usesAmmo".equals(n)) return usesAmmo;
                        if ("getAmmo".equals(n)) return usesAmmo ? ammo : Integer.MAX_VALUE;
                        if ("getMaxAmmo".equals(n)) return usesAmmo ? maxAmmo : Integer.MAX_VALUE;
                        if ("isFiring".equals(n)) return cooldownRemaining > Math.max(0.15f, cooldownDuration * 0.65f);
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == int.class) return 0;
                        if (rt == float.class) return 0f;
                        return null;
                    });
        }
    }

    public static class SimShipState {
        public final String id;
        public final String name;
        public final String hullName;
        public final int owner;
        public final HullSize hullSize;
        public final Vector2f location = new Vector2f();
        public final Vector2f velocity = new Vector2f();
        public float facing = 90f;
        public float angularVelocity = 0f;
        public float maxSpeed;
        public float accel;
        public float fluxLevel = 0.08f;
        public float hardFluxFraction = 0.35f;
        public float hullLevel = 1.0f;
        public float armorIntegrity = 1.0f;
        public float armorRating = 800f;
        public float shieldArc = 210f;
        public float shieldFluxPerDam = 0.85f;
        public boolean detailedCombatModel = false;
        public boolean isPhaseShip = false;
        public boolean phased = false;
        public boolean isStation = false;
        public boolean isStationModule = false;
        public ShipAPI parentStation = null;
        public final List<ShipAPI> childModules = new ArrayList<>();
        public float phaseCloakCooldown = 0f;
        public float maxHullHp = 6000f;
        public float maxFluxCap = 5000f;
        public float gunDps = 220f;
        public float fluxDissipation = 260f;
        public float pdStrength = 40f;
        public float overloadTimer = 0f;
        public float strikeCooldown = 0f;
        public float engageRange;
        public int strikeAmmo;
        public int fighterBays;
        public boolean alive = true;
        public ShipAPI shipTarget = null;
        public final ShipwideAIFlags aiFlags = new ShipwideAIFlags();
        public final List<WeaponAPI> weapons = new ArrayList<>();
        public final List<SimWeaponMount> weaponMounts = new ArrayList<>();
        public ShipAPI proxy;

        public SimShipState(
                String id,
                String name,
                String hullName,
                int owner,
                HullSize hullSize,
                float x,
                float y,
                float maxSpeed,
                float engageRange,
                int strikeAmmo,
                int fighterBays) {
            this.id = id;
            this.name = name;
            this.hullName = hullName;
            this.owner = owner;
            this.hullSize = hullSize;
            this.location.set(x, y);
            this.facing = (owner == 0) ? 90f : 270f;
            this.maxSpeed = maxSpeed;
            this.accel = maxSpeed * 1.35f;
            this.engageRange = engageRange;
            this.strikeAmmo = strikeAmmo;
            this.fighterBays = fighterBays;
            String hLower = hullName != null ? hullName.toLowerCase(Locale.ROOT) : "";
            this.isPhaseShip = hLower.contains("shade") || hLower.contains("afflictor")
                    || hLower.contains("harbinger") || hLower.contains("doom") || hLower.contains("gremlin");
            switch (hullSize != null ? hullSize : HullSize.CRUISER) {
                case CAPITAL_SHIP:
                    this.maxHullHp = 22000f;
                    this.maxFluxCap = 18500f;
                    this.armorRating = 1550f;
                    this.shieldArc = 220f;
                    this.shieldFluxPerDam = 0.78f;
                    this.gunDps = (fighterBays >= 2) ? 360f : 690f;
                    this.fluxDissipation = 720f;
                    this.pdStrength = 95f;
                    break;
                case CRUISER:
                    this.maxHullHp = (fighterBays >= 2) ? 11000f : 12000f;
                    this.maxFluxCap = (fighterBays >= 2) ? 8500f : 10200f;
                    this.armorRating = (fighterBays >= 2) ? 880f : 1050f;
                    this.shieldArc = 210f;
                    this.shieldFluxPerDam = 0.84f;
                    this.gunDps = (fighterBays >= 2) ? 210f : 420f;
                    this.fluxDissipation = 430f;
                    this.pdStrength = 60f;
                    break;
                case DESTROYER:
                    this.maxHullHp = 6400f;
                    this.maxFluxCap = 5400f;
                    this.armorRating = 600f;
                    this.shieldArc = 190f;
                    this.shieldFluxPerDam = 0.90f;
                    this.gunDps = (fighterBays >= 1) ? 140f : 250f;
                    this.fluxDissipation = 250f;
                    this.pdStrength = 48f;
                    break;
                case FRIGATE:
                default:
                    this.maxHullHp = 3300f;
                    this.maxFluxCap = 2900f;
                    this.armorRating = 320f;
                    this.shieldArc = 180f;
                    this.shieldFluxPerDam = 0.95f;
                    this.gunDps = 150f;
                    this.fluxDissipation = 155f;
                    this.pdStrength = 28f;
                    break;
            }
            this.weapons.add(createBaselineGun(engageRange));
            if (strikeAmmo > 0) {
                this.weapons.add(createBaselineStrikeMissile(this));
            }
            this.proxy = createProxy();
        }

        public void enableDetailedCombatModel() {
            this.detailedCombatModel = true;
            this.weapons.clear();
            this.weaponMounts.clear();
            populateDefaultWeaponGroups();
        }

        private static WeaponAPI createBaselineGun(final float range) {
            final EnumSet<AIHints> hints = EnumSet.noneOf(AIHints.class);
            return (WeaponAPI) Proxy.newProxyInstance(
                    WeaponAPI.class.getClassLoader(),
                    new Class<?>[]{WeaponAPI.class},
                    (p, m, a) -> {
                        String n = m.getName();
                        if ("getRange".equals(n)) return range;
                        if ("getType".equals(n)) return WeaponType.BALLISTIC;
                        if ("getAIHints".equals(n)) return hints;
                        if ("isDecorative".equals(n) || "usesAmmo".equals(n)) return Boolean.FALSE;
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == int.class) return 0;
                        if (rt == float.class) return 0f;
                        return null;
                    });
        }

        private static WeaponAPI createBaselineStrikeMissile(final SimShipState shipState) {
            final EnumSet<AIHints> hints = EnumSet.of(AIHints.STRIKE);
            return (WeaponAPI) Proxy.newProxyInstance(
                    WeaponAPI.class.getClassLoader(),
                    new Class<?>[]{WeaponAPI.class},
                    (p, m, a) -> {
                        String n = m.getName();
                        if ("getRange".equals(n)) return 2200f;
                        if ("getType".equals(n)) return WeaponType.MISSILE;
                        if ("getAIHints".equals(n)) return hints;
                        if ("hasAIHint".equals(n)) return a != null && a.length > 0 && hints.contains(a[0]);
                        if ("isDecorative".equals(n) || "isDisabled".equals(n) || "isPermanentlyDisabled".equals(n)) {
                            return Boolean.FALSE;
                        }
                        if ("usesAmmo".equals(n)) return Boolean.TRUE;
                        if ("getAmmo".equals(n)) return shipState.strikeAmmo;
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == int.class) return 0;
                        if (rt == float.class) return 0f;
                        return null;
                    });
        }

        private void populateDefaultWeaponGroups() {
            WeaponSize mainSize = (hullSize == HullSize.CAPITAL_SHIP) ? WeaponSize.LARGE
                    : (hullSize == HullSize.CRUISER ? WeaponSize.MEDIUM
                    : (hullSize == HullSize.DESTROYER ? WeaponSize.MEDIUM : WeaponSize.SMALL));
            // 1. Primary Kinetic Battery (anti-shield, fast-cycling turret, 210-deg forward arc)
            float kinDps = gunDps * 0.52f;
            float kinCd = 1.4f;
            float kinBurst = kinDps * kinCd;
            addWeaponMount(new SimWeaponMount(
                    id + "_kin",
                    WeaponType.BALLISTIC,
                    mainSize,
                    DamageType.KINETIC,
                    engageRange,
                    220f,
                    0f,
                    kinBurst,
                    kinDps,
                    kinBurst * 0.46f,
                    kinCd,
                    false,
                    0,
                    false,
                    false,
                    false));

            // 2. High-Explosive / Energy Heavy Battery (anti-armor, longer reload cycle so reload gating matters)
            float heDps = gunDps * 0.48f;
            float heCd = (hullSize == HullSize.CAPITAL_SHIP) ? 3.8f : (hullSize == HullSize.CRUISER ? 3.2f : 2.5f);
            float heBurst = heDps * heCd * 1.12f;
            addWeaponMount(new SimWeaponMount(
                    id + "_he",
                    WeaponType.BALLISTIC,
                    mainSize,
                    DamageType.HIGH_EXPLOSIVE,
                    engageRange * 0.92f,
                    170f,
                    0f,
                    heBurst,
                    heDps,
                    heBurst * 0.50f,
                    heCd,
                    false,
                    0,
                    false,
                    false,
                    false));

            // 3. Strike Missile / Torpedo Racks (finite ammo, high alpha burst, 5.0s reload cooldown)
            if (strikeAmmo > 0) {
                float strikeBurst = 920f;
                float strikeCd = 4.5f;
                addWeaponMount(new SimWeaponMount(
                        id + "_strike",
                        WeaponType.MISSILE,
                        WeaponSize.MEDIUM,
                        DamageType.HIGH_EXPLOSIVE,
                        1950f,
                        110f,
                        0f,
                        strikeBurst,
                        strikeBurst / strikeCd,
                        0f,
                        strikeCd,
                        true,
                        strikeAmmo,
                        false,
                        true,
                        false));
            }

            // 4. Point-Defense / Flak Battery (360-deg coverage; capitals & cruisers mount hybrid flak)
            boolean hybridFlak = (hullSize == HullSize.CAPITAL_SHIP || hullSize == HullSize.CRUISER);
            addWeaponMount(new SimWeaponMount(
                    id + "_pd",
                    WeaponType.BALLISTIC,
                    hybridFlak ? WeaponSize.MEDIUM : WeaponSize.SMALL,
                    DamageType.FRAGMENTATION,
                    hybridFlak ? 650f : 500f,
                    360f,
                    0f,
                    pdStrength * 1.4f,
                    pdStrength,
                    pdStrength * 0.25f,
                    1.0f,
                    false,
                    0,
                    true,
                    false,
                    hybridFlak));
        }

        public void addWeaponMount(SimWeaponMount mount) {
            this.weaponMounts.add(mount);
            this.weapons.add(mount.proxy);
        }

        public void addDirectionalWeapon(
                final float range,
                final float arcDeg,
                final float arcFacingRelDeg,
                final boolean isPd,
                final boolean isStrike) {
            WeaponSize sz = isStrike ? WeaponSize.MEDIUM : (isPd ? WeaponSize.SMALL : WeaponSize.MEDIUM);
            DamageType dt = isStrike ? DamageType.HIGH_EXPLOSIVE : (isPd ? DamageType.FRAGMENTATION : DamageType.KINETIC);
            float dps = isPd ? 45f : (isStrike ? 180f : 140f);
            float cd = isStrike ? 4.0f : 1.5f;
            addWeaponMount(new SimWeaponMount(
                    id + "_dir_" + weaponMounts.size(),
                    isStrike ? WeaponType.MISSILE : WeaponType.BALLISTIC,
                    sz,
                    dt,
                    range,
                    arcDeg,
                    arcFacingRelDeg,
                    dps * cd,
                    dps,
                    dps * 0.4f,
                    cd,
                    false,
                    0,
                    isPd,
                    isStrike,
                    false));
        }

        private ShipAPI createProxy() {
            final ShipHullSpecAPI hullSpec = (ShipHullSpecAPI) Proxy.newProxyInstance(
                    ShipHullSpecAPI.class.getClassLoader(),
                    new Class<?>[]{ShipHullSpecAPI.class},
                    (p, m, a) -> {
                        if ("getHullName".equals(m.getName()) || "getHullId".equals(m.getName())) return hullName;
                        if ("getHullSize".equals(m.getName())) return hullSize;
                        if (detailedCombatModel && "getArmorRating".equals(m.getName())) return armorRating;
                        if (detailedCombatModel && "getHitpoints".equals(m.getName())) return maxHullHp;
                        if ("getTags".equals(m.getName())) return Collections.emptyList();
                        if ("getHints".equals(m.getName())) {
                            return isPhaseShip
                                    ? EnumSet.of(ShipHullSpecAPI.ShipTypeHints.PHASE)
                                    : EnumSet.noneOf(ShipHullSpecAPI.ShipTypeHints.class);
                        }
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == int.class) return 0;
                        if (rt == float.class) return 0f;
                        return null;
                    });

            final ShieldAPI shieldProxy = (ShieldAPI) Proxy.newProxyInstance(
                    ShieldAPI.class.getClassLoader(),
                    new Class<?>[]{ShieldAPI.class},
                    (sp, sm, sa) -> {
                        String sn = sm.getName();
                        if ("getType".equals(sn)) return isPhaseShip ? ShieldType.PHASE : ShieldType.OMNI;
                        if ("isOn".equals(sn)) return !isPhaseShip && overloadTimer <= 0f;
                        if ("isOff".equals(sn)) return isPhaseShip || overloadTimer > 0f;
                        if ("getArc".equals(sn) || "getActiveArc".equals(sn)) return shieldArc;
                        if ("getFacing".equals(sn)) return facing;
                        if ("getFluxPerPointOfDamage".equals(sn)) return shieldFluxPerDam;
                        Class<?> srt = sm.getReturnType();
                        if (srt == boolean.class) return false;
                        if (srt == int.class) return 0;
                        if (srt == float.class) return 0f;
                        return null;
                    });

            final ShipSystemAPI phaseProxy = isPhaseShip
                    ? (ShipSystemAPI) Proxy.newProxyInstance(
                    ShipSystemAPI.class.getClassLoader(),
                    new Class<?>[]{ShipSystemAPI.class},
                    (pp, pm, pa) -> {
                        String pn = pm.getName();
                        if ("getId".equals(pn)) return "phasecloak";
                        if ("isActive".equals(pn) || "isOn".equals(pn)) return phased;
                        Class<?> prt = pm.getReturnType();
                        if (prt == boolean.class) return false;
                        if (prt == int.class) return 0;
                        if (prt == float.class) return 0f;
                        return null;
                    })
                    : null;

            return (ShipAPI) Proxy.newProxyInstance(
                    ShipAPI.class.getClassLoader(),
                    new Class<?>[]{ShipAPI.class},
                    (p, m, a) -> {
                        String n = m.getName();
                        if ("getId".equals(n)) return id;
                        if ("getName".equals(n)) return name;
                        if ("getHullSpec".equals(n)) return hullSpec;
                        if ("getOwner".equals(n)) return owner;
                        if ("isAlive".equals(n)) return alive;
                        if ("isHulk".equals(n)) return !alive;
                        if ("isPhased".equals(n)) return detailedCombatModel && phased;
                        if ("getShield".equals(n)) return detailedCombatModel ? shieldProxy : null;
                        if ("getPhaseCloak".equals(n)) return detailedCombatModel ? phaseProxy : null;
                        if (detailedCombatModel && "isFrigate".equals(n)) return hullSize == HullSize.FRIGATE;
                        if (detailedCombatModel && "isDestroyer".equals(n)) return hullSize == HullSize.DESTROYER;
                        if (detailedCombatModel && "isCruiser".equals(n)) return hullSize == HullSize.CRUISER;
                        if (detailedCombatModel && "isCapital".equals(n)) return hullSize == HullSize.CAPITAL_SHIP;
                        if ("isStation".equals(n)) return isStation;
                        if ("isStationModule".equals(n)) return isStationModule;
                        if ("getParentStation".equals(n)) return parentStation;
                        if ("getChildModulesCopy".equals(n)) return new ArrayList<ShipAPI>(childModules);
                        if ("isExpired".equals(n) || "isRetreating".equals(n) || "isDirectRetreat".equals(n)
                                || "isFighter".equals(n) || "isDrone".equals(n)
                                || "isAlly".equals(n) || "isShuttlePod".equals(n)) {
                            return Boolean.FALSE;
                        }
                        if ("getLocation".equals(n)) return location;
                        if ("getVelocity".equals(n)) return velocity;
                        if ("getFacing".equals(n)) return facing;
                        if (detailedCombatModel && "getAngularVelocity".equals(n)) return angularVelocity;
                        if ("getFluxLevel".equals(n)) return fluxLevel;
                        if ("getHullLevel".equals(n)) return hullLevel;
                        if (detailedCombatModel && "getHitpoints".equals(n)) return Math.max(0f, hullLevel * maxHullHp);
                        if (detailedCombatModel && "getMaxHitpoints".equals(n)) return maxHullHp;
                        if (detailedCombatModel && "getShipTarget".equals(n)) return shipTarget;
                        if (detailedCombatModel && "setShipTarget".equals(n)) {
                            shipTarget = (a != null && a.length > 0 && a[0] instanceof ShipAPI) ? (ShipAPI) a[0] : null;
                            return null;
                        }
                        if (detailedCombatModel && "getFluxTracker".equals(n)) {
                            return Proxy.newProxyInstance(
                                    com.fs.starfarer.api.combat.FluxTrackerAPI.class.getClassLoader(),
                                    new Class<?>[]{com.fs.starfarer.api.combat.FluxTrackerAPI.class},
                                    (fp, fm, fa) -> {
                                        String fn = fm.getName();
                                        if ("isOverloadedOrVenting".equals(fn) || "isOverloaded".equals(fn)) return overloadTimer > 0f;
                                        if ("getOverloadTimeRemaining".equals(fn)) return overloadTimer;
                                        if ("getFluxLevel".equals(fn)) return fluxLevel;
                                        if ("getMaxFlux".equals(fn)) return maxFluxCap;
                                        if ("getCurrFlux".equals(fn)) return fluxLevel * maxFluxCap;
                                        if ("getHardFlux".equals(fn)) return fluxLevel * maxFluxCap * hardFluxFraction;
                                        Class<?> frt = fm.getReturnType();
                                        if (frt == boolean.class) return false;
                                        if (frt == float.class) return 0f;
                                        if (frt == int.class) return 0;
                                        return null;
                                    });
                        }
                        if ("getHullSize".equals(n)) return hullSize;
                        if ("getMaxSpeed".equals(n)) return maxSpeed;
                        if ("getAIFlags".equals(n)) return aiFlags;
                        if ("getNumFighterBays".equals(n)) return fighterBays;
                        if ("getAllWeapons".equals(n)) return weapons;
                        if ("equals".equals(n)) return p == a[0];
                        if ("hashCode".equals(n)) return System.identityHashCode(p);
                        if ("toString".equals(n)) return name + "(" + id + ")";
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == int.class) return 0;
                        if (rt == float.class) return 0f;
                        return null;
                    });
        }
    }

    public static class ReplayFrame {
        public final float time;
        public final String playerPhase;
        public final String enemyPhase;
        public final List<String> frameShipsJson = new ArrayList<>();
        public final List<String> frameDivsJson = new ArrayList<>();
        public final List<String> recentLogs = new ArrayList<>();

        public ReplayFrame(float time, String playerPhase, String enemyPhase) {
            this.time = time;
            this.playerPhase = playerPhase;
            this.enemyPhase = enemyPhase;
        }
    }

    public static class ScenarioRecording {
        public final String title;
        public final String description;
        public final List<ReplayFrame> frames = new ArrayList<>();

        public ScenarioRecording(String title, String description) {
            this.title = title;
            this.description = description;
        }
    }

    public final String title;
    public final String description;
    public float elapsedTime = 0f;
    public float mapWidth = 18000f;
    public float mapHeight = 18000f;
    public final List<SimShipState> ships = new ArrayList<>();
    public final List<BattleObjectiveAPI> objectives = new ArrayList<>();
    public final List<String> combatLogs = new ArrayList<>();
    public BattleGroupState playerGroup;
    public BattleGroupState enemyGroup;
    public final CombatEngineAPI engineProxy;
    public final ScenarioRecording recording;
    public boolean fogOfWarEnabled = false;
    public float baseShipSightRadius = 3000f;
    private final java.util.Map<Object, float[]> customFogRevealsSide0 = new java.util.LinkedHashMap<>();
    private final java.util.Map<Object, float[]> customFogRevealsSide1 = new java.util.LinkedHashMap<>();

    public void enableFogOfWar() {
        this.fogOfWarEnabled = true;
    }

    public void clearCustomFogReveals() {
        customFogRevealsSide0.clear();
        customFogRevealsSide1.clear();
    }

    public boolean isPointVisibleToSide(int observerSide, float x, float y) {
        if (!fogOfWarEnabled) return true;
        if (observerSide != 0 && observerSide != 1) return true;
        float sightSq = baseShipSightRadius * baseShipSightRadius;
        for (int i = 0, n = ships.size(); i < n; i++) {
            SimShipState s = ships.get(i);
            if (!s.alive || s.owner != observerSide) continue;
            float dx = x - s.location.x;
            float dy = y - s.location.y;
            if (dx * dx + dy * dy <= sightSq) {
                return true;
            }
        }
        for (int i = 0, n = objectives.size(); i < n; i++) {
            BattleObjectiveAPI obj = objectives.get(i);
            if (obj == null || obj.getOwner() != observerSide || obj.getLocation() == null) continue;
            float dx = x - obj.getLocation().x;
            float dy = y - obj.getLocation().y;
            if (dx * dx + dy * dy <= 2000f * 2000f) {
                return true;
            }
        }
        java.util.Map<Object, float[]> reveals = (observerSide == 1) ? customFogRevealsSide1 : customFogRevealsSide0;
        if (!reveals.isEmpty()) {
            for (float[] r : reveals.values()) {
                float dx = x - r[0];
                float dy = y - r[1];
                if (dx * dx + dy * dy <= r[2] * r[2]) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isEntityVisibleToSide(int observerSide, CombatEntityAPI entity) {
        if (!fogOfWarEnabled) return true;
        if (entity == null) return false;
        if (entity.getOwner() == observerSide) return true;
        Vector2f loc = entity.getLocation();
        if (loc == null) return false;
        return isPointVisibleToSide(observerSide, loc.x, loc.y);
    }

    public SimArena(String title, String description, BattleGroupState playerGroup, BattleGroupState enemyGroup) {
        this.title = title;
        this.description = description;
        this.playerGroup = playerGroup;
        this.enemyGroup = enemyGroup;
        this.recording = new ScenarioRecording(title, description);
        this.engineProxy = createEngineProxy();
    }

    public SimShipState addShip(
            DivisionState div,
            String id,
            String name,
            String hullName,
            HullSize size,
            float x,
            float y,
            float maxSpeed,
            float engageRange,
            int strikeAmmo,
            int fighterBays) {
        SimShipState s = new SimShipState(id, name, hullName, div.side, size, x, y, maxSpeed, engageRange, strikeAmmo, fighterBays);
        ships.add(s);
        div.members.add(s.proxy);
        if (div.flagship == null) {
            div.flagship = s.proxy;
        }
        ShipCapabilityProfile prof = new ShipCapabilityProfile(s.proxy);
        div.profiles.add(prof);
        if (strikeAmmo > 0) div.hasMissileStrikeCapability = true;
        if (fighterBays > 0) div.hasCarrierCapability = true;
        if (size == HullSize.FRIGATE || size == HullSize.DESTROYER) div.isScreenCapable = true;
        if (maxSpeed >= 120f) div.isFastFlanker = true;
        div.avgMaxSpeed = maxSpeed;
        div.avgEngageRange = engageRange;
        return s;
    }

    private CombatEngineAPI createEngineProxy() {
        final CollisionGridAPI gridProxy = (CollisionGridAPI) Proxy.newProxyInstance(
                CollisionGridAPI.class.getClassLoader(),
                new Class<?>[]{CollisionGridAPI.class},
                (p, m, a) -> {
                    if ("getCheckIterator".equals(m.getName())) {
                        List<Object> shipProxies = new ArrayList<>(ships.size());
                        for (SimShipState s : ships) {
                            if (s.alive) shipProxies.add(s.proxy);
                        }
                        return shipProxies.iterator();
                    }
                    return null;
                });

        final FogOfWarAPI fogProxy0 = createFogProxyForSide(0);
        final FogOfWarAPI fogProxy1 = createFogProxyForSide(1);

        return (CombatEngineAPI) Proxy.newProxyInstance(
                CombatEngineAPI.class.getClassLoader(),
                new Class<?>[]{CombatEngineAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getTotalElapsedTime".equals(n)) return elapsedTime;
                    if ("getMapWidth".equals(n)) return mapWidth;
                    if ("getMapHeight".equals(n)) return mapHeight;
                    if ("getShips".equals(n)) {
                        List<ShipAPI> list = new ArrayList<>(ships.size());
                        for (SimShipState s : ships) {
                            if (s.alive) list.add(s.proxy);
                        }
                        return list;
                    }
                    if ("getAiGridShips".equals(n) || "getShipGrid".equals(n)) return gridProxy;
                    if ("getFogOfWar".equals(n)) {
                        int side = (a != null && a.length > 0 && a[0] instanceof Integer) ? (Integer) a[0] : 0;
                        return (side == 1) ? fogProxy1 : fogProxy0;
                    }
                    if ("getObjectives".equals(n)) return objectives;
                    if ("isUIAutopilotOn".equals(n)) return Boolean.TRUE;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == float.class) return 0f;
                    return null;
                });
    }

    private FogOfWarAPI createFogProxyForSide(final int side) {
        return (FogOfWarAPI) Proxy.newProxyInstance(
                FogOfWarAPI.class.getClassLoader(),
                new Class<?>[]{FogOfWarAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getPlayerId".equals(n)) return side;
                    if ("revealAroundPoint".equals(n) && a != null && a.length >= 4) {
                        Object key = a[0] != null ? a[0] : p;
                        float rx = ((Number) a[1]).floatValue();
                        float ry = ((Number) a[2]).floatValue();
                        float rad = ((Number) a[3]).floatValue();
                        java.util.Map<Object, float[]> map = (side == 1) ? customFogRevealsSide1 : customFogRevealsSide0;
                        map.put(key, new float[]{rx, ry, rad});
                        return null;
                    }
                    if ("isVisible".equals(n) && a != null && a.length > 0) {
                        if (!fogOfWarEnabled) return Boolean.TRUE;
                        if (a.length == 2 && a[0] instanceof Number && a[1] instanceof Number) {
                            return isPointVisibleToSide(side, ((Number) a[0]).floatValue(), ((Number) a[1]).floatValue());
                        }
                        if (a[0] instanceof Vector2f) {
                            Vector2f v = (Vector2f) a[0];
                            return v != null && isPointVisibleToSide(side, v.x, v.y);
                        }
                        if (a[0] instanceof CombatEntityAPI) {
                            return isEntityVisibleToSide(side, (CombatEntityAPI) a[0]);
                        }
                        return Boolean.TRUE;
                    }
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return true;
                    return null;
                });
    }

    /**
     * Advances the tactical arena by totalSeconds using 0.25s physics ticks and 0.50s C2 ticks.
     */
    public void runSimulation(float durationSeconds) {
        DivisionCombatLog.simLogSink = combatLogs;
        try {
            int steps = Math.max(1, Math.round(durationSeconds / 0.25f));
            for (int step = 0; step < steps; step++) {
                int tickIndex = Math.round(elapsedTime / 0.25f);
                if (tickIndex % 2 == 0) {
                    // 0.5s C2 tick
                    OtcAI.updateTelemetry(engineProxy, playerGroup);
                    OtcAI.updateTelemetry(engineProxy, enemyGroup);

                    if ((elapsedTime - playerGroup.lastOtcEvaluationTime) >= 1.5f) {
                        OtcAI.evaluateOtcStrategy(engineProxy, playerGroup, enemyGroup, elapsedTime);
                        playerGroup.lastOtcEvaluationTime = elapsedTime;
                    }
                    if ((elapsedTime - enemyGroup.lastOtcEvaluationTime) >= 1.5f) {
                        OtcAI.evaluateOtcStrategy(engineProxy, enemyGroup, playerGroup, elapsedTime);
                        enemyGroup.lastOtcEvaluationTime = elapsedTime;
                    }

                    DivisionCommanderAI.evaluateAndExecuteDivisions(engineProxy, playerGroup, enemyGroup, elapsedTime);
                    DivisionCommanderAI.evaluateAndExecuteDivisions(engineProxy, enemyGroup, playerGroup, elapsedTime);
                }

                if (tickIndex % 4 == 0) {
                    recordReplaySnapshot();
                }

                stepPhysics(0.25f);
                elapsedTime += 0.25f;
            }
        } finally {
            DivisionCombatLog.simLogSink = null;
        }
    }

    /**
     * Simulates 2D ship kinematics and vanilla Starsector AI response to ShipwideAIFlags:
     * - Steers toward CAMP_LOCATION / ESCORT_OTHER_SHIP / MANEUVER_TARGET
     * - Models vanilla local enemy threat repulsion when DO_NOT_BACK_OFF is absent (which traps straight-line
     *   chords through an enemy battle line, proving that wide-arc waypoints clear the obstacle)
     * - Simulates weapon flux buildup and passive dissipation.
     */
    private void stepPhysics(float dt) {
        for (SimShipState s : ships) {
            if (!s.alive) continue;
            ShipwideAIFlags flags = s.aiFlags;

            Vector2f desiredVel = new Vector2f(0f, 0f);
            boolean hasCamp = flags.hasFlag(AIFlags.CAMP_LOCATION);
            Object campObj = flags.getCustom(AIFlags.CAMP_LOCATION);
            Object escortObj = flags.getCustom(AIFlags.ESCORT_OTHER_SHIP);
            Object targetObj = flags.getCustom(AIFlags.MANEUVER_TARGET);
            boolean backOff = flags.hasFlag(AIFlags.BACK_OFF);
            boolean doNotBackOff = flags.hasFlag(AIFlags.DO_NOT_BACK_OFF);
            boolean doNotPursue = flags.hasFlag(AIFlags.DO_NOT_PURSUE);

            Vector2f navGoal = null;
            if (hasCamp && campObj instanceof Vector2f) {
                navGoal = (Vector2f) campObj;
            } else if (escortObj instanceof ShipAPI) {
                navGoal = ((ShipAPI) escortObj).getLocation();
            } else if (!doNotPursue && targetObj instanceof ShipAPI) {
                navGoal = ((ShipAPI) targetObj).getLocation();
            }

            if (navGoal != null) {
                float dx = navGoal.x - s.location.x;
                float dy = navGoal.y - s.location.y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > 25f) {
                    float speedFactor = Math.min(1f, dist / 240f);
                    desiredVel.x += (dx / dist) * s.maxSpeed * speedFactor;
                    desiredVel.y += (dy / dist) * s.maxSpeed * speedFactor;
                }
            }

            // Vanilla local threat repulsion: lighter ships or ships with BACK_OFF refuse to fly straight through
            // an enemy battle line unless DO_NOT_BACK_OFF is set.
            if (!doNotBackOff) {
                for (SimShipState other : ships) {
                    if (!other.alive || other.owner == s.owner) continue;
                    float dx = s.location.x - other.location.x;
                    float dy = s.location.y - other.location.y;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    float dangerRadius = backOff ? (other.engageRange * 1.05f) : (other.engageRange * 0.82f);
                    if (dist > 1f && dist < dangerRadius) {
                        float repelStrength = ((dangerRadius - dist) / dangerRadius) * s.maxSpeed * (backOff ? 1.55f : 1.15f);
                        desiredVel.x += (dx / dist) * repelStrength;
                        desiredVel.y += (dy / dist) * repelStrength;
                    }
                }
            }

            // Clamp desired speed to maxSpeed
            float dSpeed = (float) Math.sqrt(desiredVel.x * desiredVel.x + desiredVel.y * desiredVel.y);
            if (dSpeed > s.maxSpeed) {
                desiredVel.x = (desiredVel.x / dSpeed) * s.maxSpeed;
                desiredVel.y = (desiredVel.y / dSpeed) * s.maxSpeed;
            }

            // Smooth velocity integration
            float blend = Math.min(1f, dt * 2.8f);
            s.velocity.x += (desiredVel.x - s.velocity.x) * blend;
            s.velocity.y += (desiredVel.y - s.velocity.y) * blend;

            s.location.x += s.velocity.x * dt;
            s.location.y += s.velocity.y * dt;

            if (dSpeed > 5f) {
                s.facing = (float) Math.toDegrees(Math.atan2(s.velocity.y, s.velocity.x));
            }

            // Flux exchange and passive vent dissipation
            boolean inEnemyFire = false;
            for (SimShipState other : ships) {
                if (!other.alive || other.owner == s.owner) continue;
                float dx = s.location.x - other.location.x;
                float dy = s.location.y - other.location.y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist <= other.engageRange) {
                    inEnemyFire = true;
                    s.fluxLevel = Math.min(0.95f, s.fluxLevel + 0.028f * dt);
                }
            }
            if (!inEnemyFire) {
                s.fluxLevel = Math.max(0.04f, s.fluxLevel - (backOff ? 0.085f : 0.040f) * dt);
            }

            flags.advance(dt);
        }
    }

    private void recordReplaySnapshot() {
        ReplayFrame frame = new ReplayFrame(
                elapsedTime,
                playerGroup.currentPhase != null ? playerGroup.currentPhase.displayName : "",
                enemyGroup.currentPhase != null ? enemyGroup.currentPhase.displayName : "");

        for (SimShipState s : ships) {
            if (!s.alive) continue;
            boolean isStraggler = false;
            String divName = "";
            BattleGroupState bg = (s.owner == 0) ? playerGroup : enemyGroup;
            for (DivisionState d : bg.divisions) {
                if (d.members.contains(s.proxy)) {
                    divName = d.displayName;
                    isStraggler = d.detachedStragglerIds.contains(s.id);
                    break;
                }
            }
            frame.frameShipsJson.add(String.format(
                    Locale.ROOT,
                    "{\"id\":\"%s\",\"name\":\"%s\",\"side\":%d,\"x\":%.1f,\"y\":%.1f,\"facing\":%.1f,\"flux\":%.2f,\"stray\":%b,\"div\":\"%s\"}",
                    escapeJson(s.id),
                    escapeJson(s.name),
                    s.owner,
                    s.location.x,
                    s.location.y,
                    s.facing,
                    s.fluxLevel,
                    isStraggler,
                    escapeJson(divName)));
        }

        appendGroupDivisionsJson(playerGroup, frame);
        appendGroupDivisionsJson(enemyGroup, frame);

        int logStart = Math.max(0, combatLogs.size() - 10);
        for (int i = logStart; i < combatLogs.size(); i++) {
            frame.recentLogs.add(combatLogs.get(i));
        }
        recording.frames.add(frame);
    }

    private void appendGroupDivisionsJson(BattleGroupState bg, ReplayFrame frame) {
        for (DivisionState d : bg.divisions) {
            if (!d.isCombatEffective()) continue;
            frame.frameDivsJson.add(String.format(
                    Locale.ROOT,
                    "{\"name\":\"%s\",\"side\":%d,\"cx\":%.1f,\"cy\":%.1f,\"spread\":%.1f,\"rawSpread\":%.1f,\"limit\":%.1f,\"intent\":\"%s\",\"sx\":%.1f,\"sy\":%.1f,\"wx\":%.1f,\"wy\":%.1f,\"hasWp\":%b,\"strays\":%d}",
                    escapeJson(d.displayName),
                    d.side,
                    d.centroid.x,
                    d.centroid.y,
                    d.spreadRadius,
                    d.rawAllMemberSpreadRadius,
                    d.stationKeepingRadius,
                    d.currentIntent != null ? d.currentIntent.name() : "NONE",
                    d.stationPoint.x,
                    d.stationPoint.y,
                    d.routeWaypoint.x,
                    d.routeWaypoint.y,
                    d.hasRouteWaypoint,
                    d.detachedStragglerIds.size()));
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Exports a list of ScenarioRecordings into an interactive HTML5 Canvas tactical replay viewer.
     */
    public static void exportHtmlReplay(List<ScenarioRecording> scenarios, File outFile) throws Exception {
        StringBuilder sb = new StringBuilder(65536);
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>\n");
        sb.append("<title>Division AI — Tactical C2 Simulation Replay</title>\n");
        sb.append("<style>\n");
        sb.append("body{margin:0;background:#070b12;color:#d6e6f2;font-family:Consolas,monospace;display:flex;height:100vh;overflow:hidden;}\n");
        sb.append("#sidebar{width:410px;background:#0c131f;border-right:1px solid #1e324a;display:flex;flex-direction:column;padding:12px;box-sizing:border-box;}\n");
        sb.append("#main{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;position:relative;}\n");
        sb.append("h2{margin:0 0 8px 0;font-size:16px;color:#5ce6a0;letter-spacing:0.5px;}\n");
        sb.append("select,button,input[type=range]{background:#132033;color:#d6e6f2;border:1px solid #2b4c6f;padding:6px 10px;font-family:inherit;font-size:13px;border-radius:3px;}\n");
        sb.append("button:hover{background:#1c314d;cursor:pointer;}\n");
        sb.append("#controls{display:flex;gap:8px;align-items:center;margin:8px 0;}\n");
        sb.append("#desc{font-size:12px;color:#9bb6cc;margin-bottom:8px;line-height:1.4;}\n");
        sb.append("#logbox{flex:1;background:#060a10;border:1px solid #1a2b40;padding:8px;overflow-y:auto;font-size:11.5px;line-height:1.45;}\n");
        sb.append(".logline{margin-bottom:4px;border-bottom:1px solid #0e1824;padding-bottom:2px;}\n");
        sb.append("canvas{background:#05080e;border:1px solid #1e324a;box-shadow:0 0 24px rgba(0,0,0,0.8);}\n");
        sb.append(".legend{font-size:11px;color:#8aa4bc;margin-top:6px;}\n");
        sb.append("</style></head><body>\n");
        sb.append("<div id='sidebar'>\n");
        sb.append("<h2>DIVISION AI — SIM HARNESS</h2>\n");
        sb.append("<select id='scenSelect' onchange='selectScenario(this.value)'></select>\n");
        sb.append("<div id='controls'>\n");
        sb.append("  <button id='playBtn' onclick='togglePlay()'>Pause</button>\n");
        sb.append("  <input id='scrubber' type='range' min='0' max='100' value='0' style='flex:1' oninput='scrub(this.value)'>\n");
        sb.append("  <span id='timeLbl'>0.0s</span>\n");
        sb.append("</div>\n");
        sb.append("<div id='desc'></div>\n");
        sb.append("<div id='logbox'></div>\n");
        sb.append("<div class='legend'>Green = Player Core | Amber = Detached Straggler | Red = Enemy<br>Dashed Diamond Dogleg = Out-of-Contact Wide-Arc Route</div>\n");
        sb.append("</div>\n");
        sb.append("<div id='main'><canvas id='map' width='860' height='860'></canvas></div>\n");
        sb.append("<script>\nconst SCENARIOS = [\n");

        for (int s = 0; s < scenarios.size(); s++) {
            ScenarioRecording rec = scenarios.get(s);
            if (s > 0) sb.append(",\n");
            sb.append("{\"title\":\"").append(escapeJson(rec.title))
                    .append("\",\"desc\":\"").append(escapeJson(rec.description))
                    .append("\",\"frames\":[\n");
            for (int f = 0; f < rec.frames.size(); f++) {
                ReplayFrame fr = rec.frames.get(f);
                if (f > 0) sb.append(",\n");
                sb.append(String.format(Locale.ROOT, "{\"t\":%.1f,\"pPhase\":\"%s\",\"ePhase\":\"%s\",\"ships\":[",
                        fr.time, escapeJson(fr.playerPhase), escapeJson(fr.enemyPhase)));
                for (int i = 0; i < fr.frameShipsJson.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(fr.frameShipsJson.get(i));
                }
                sb.append("],\"divs\":[");
                for (int i = 0; i < fr.frameDivsJson.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(fr.frameDivsJson.get(i));
                }
                sb.append("],\"logs\":[");
                for (int i = 0; i < fr.recentLogs.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escapeJson(fr.recentLogs.get(i))).append("\"");
                }
                sb.append("]}");
            }
            sb.append("\n]}");
        }

        sb.append("\n];\n");
        sb.append("let curScen=0, curFrame=0, playing=true;\n");
        sb.append("const sel=document.getElementById('scenSelect');\n");
        sb.append("SCENARIOS.forEach((sc,i)=>{const o=document.createElement('option');o.value=i;o.textContent=sc.title;sel.appendChild(o);});\n");
        sb.append("function selectScenario(i){curScen=parseInt(i);curFrame=0;const sc=SCENARIOS[curScen];document.getElementById('scrubber').max=Math.max(0,sc.frames.length-1);document.getElementById('desc').textContent=sc.desc;render();}\n");
        sb.append("function togglePlay(){playing=!playing;document.getElementById('playBtn').textContent=playing?'Pause':'Play';}\n");
        sb.append("function scrub(v){curFrame=parseInt(v);render();}\n");
        sb.append("function w2s(x,y){const scale=860/7600;return [430+x*scale, 430-y*scale, scale];}\n");
        sb.append("function hullCCW(pts){if(pts.length<=3)return pts;const s=[...pts].sort((a,b)=>a[0]-b[0]||a[1]-b[1]);const cr=(o,a,b)=>(a[0]-o[0])*(b[1]-o[1])-(a[1]-o[1])*(b[0]-o[0]);const lo=[],up=[];for(const p of s){while(lo.length>=2&&cr(lo[lo.length-2],lo[lo.length-1],p)<=0)lo.pop();lo.push(p);}for(let i=s.length-1;i>=0;i--){const p=s[i];while(up.length>=2&&cr(up[up.length-2],up[up.length-1],p)<=0)up.pop();up.push(p);}lo.pop();up.pop();return lo.concat(up);}\n");
        sb.append("function chaikin(poly){if(poly.length<3)return poly;const out=[];for(let i=0;i<poly.length;i++){const a=poly[i],b=poly[(i+1)%poly.length];out.push([0.75*a[0]+0.25*b[0],0.75*a[1]+0.25*b[1]],[0.25*a[0]+0.75*b[0],0.25*a[1]+0.75*b[1]]);}return out;}\n");
        sb.append("function render(){\n");
        sb.append("  const sc=SCENARIOS[curScen]; if(!sc||!sc.frames.length)return;\n");
        sb.append("  const fr=sc.frames[curFrame];\n");
        sb.append("  document.getElementById('scrubber').value=curFrame;\n");
        sb.append("  document.getElementById('timeLbl').textContent=fr.t.toFixed(1)+'s';\n");
        sb.append("  const lb=document.getElementById('logbox');lb.innerHTML=fr.logs.map(l=>`<div class='logline'>${l}</div>`).join('');\n");
        sb.append("  const cv=document.getElementById('map'), ctx=cv.getContext('2d');\n");
        sb.append("  ctx.clearRect(0,0,860,860);\n");
        sb.append("  ctx.strokeStyle='#111c2b';ctx.lineWidth=1;\n");
        sb.append("  for(let g=-3000;g<=3000;g+=1000){const [gx,gy]=w2s(g,g);ctx.beginPath();ctx.moveTo(gx,0);ctx.lineTo(gx,860);ctx.stroke();ctx.beginPath();ctx.moveTo(0,gy);ctx.lineTo(860,gy);ctx.stroke();}\n");
        sb.append("  const divPolys=[];\n");
        sb.append("  fr.divs.forEach(d=>{\n");
        sb.append("    const members=fr.ships.filter(s=>s.div===d.name);\n");
        sb.append("    const pts=[];\n");
        sb.append("    members.forEach(m=>{const [mx,my,sc]=w2s(m.x,m.y);const r=Math.max(18,440*sc);for(let k=0;k<12;k++){const a=2*Math.PI*k/12;pts.push([mx+Math.cos(a)*r,my+Math.sin(a)*r]);}});\n");
        sb.append("    if(pts.length>=3){\n");
        sb.append("      const poly=chaikin(chaikin(hullCCW(pts)));\n");
        sb.append("      const ratio=Math.max(d.spread,d.rawSpread||d.spread)/Math.max(300,d.limit);\n");
        sb.append("      const coh=Math.max(0.06,1-Math.max(0,Math.min(1,(ratio-0.85)/1.25))*0.94);\n");
        sb.append("      divPolys.push({d,poly,coh});\n");
        sb.append("      ctx.save();ctx.beginPath();poly.forEach((p,i)=>i?ctx.lineTo(p[0],p[1]):ctx.moveTo(p[0],p[1]));ctx.closePath();\n");
        sb.append("      ctx.fillStyle=d.side===0?`rgba(50,235,120,${(0.14*coh).toFixed(3)})`:`rgba(245,65,65,${(0.14*coh).toFixed(3)})`;ctx.fill();\n");
        sb.append("      ctx.strokeStyle=d.side===0?`rgba(95,255,165,${(0.55*coh).toFixed(3)})`:`rgba(255,110,110,${(0.55*coh).toFixed(3)})`;ctx.lineWidth=1.6;ctx.stroke();ctx.restore();\n");
        sb.append("    }\n");
        sb.append("    const [cx,cy]=w2s(d.cx,d.cy);\n");
        sb.append("    if(d.side===0){\n");
        sb.append("      const [sx,sy]=w2s(d.sx,d.sy);\n");
        sb.append("      ctx.save();ctx.setLineDash([5,4]);ctx.strokeStyle='#5ce6a0';ctx.lineWidth=1.9;ctx.beginPath();ctx.moveTo(cx,cy);\n");
        sb.append("      if(d.hasWp){const [wx,wy]=w2s(d.wx,d.wy);ctx.lineTo(wx,wy);ctx.lineTo(sx,sy);ctx.stroke();ctx.setLineDash([]);ctx.strokeStyle='#ffd166';ctx.strokeRect(wx-4,wy-4,8,8);}else{ctx.lineTo(sx,sy);ctx.stroke();}\n");
        sb.append("      ctx.restore();\n");
        sb.append("    }\n");
        sb.append("    ctx.fillStyle=d.side===0?'#8ef5be':'#ff9999';ctx.font='11px Consolas';\n");
        sb.append("    ctx.fillText(`${d.name} [${d.intent}]`,cx-36,cy-6);\n");
        sb.append("  });\n");
        sb.append("  fr.ships.forEach(s=>{\n");
        sb.append("    const [x,y]=w2s(s.x,s.y);\n");
        sb.append("    ctx.save();ctx.translate(x,y);ctx.rotate(-s.facing*Math.PI/180);\n");
        sb.append("    ctx.fillStyle=s.side===1?'#f24b4b':(s.stray?'#ffad33':'#2ee675');\n");
        sb.append("    ctx.beginPath();ctx.moveTo(9,0);ctx.lineTo(-6,-5);ctx.lineTo(-6,5);ctx.closePath();ctx.fill();\n");
        sb.append("    ctx.restore();\n");
        sb.append("  });\n");
        sb.append("}\n");
        sb.append("selectScenario(0);\n");
        sb.append("setInterval(()=>{if(playing){const sc=SCENARIOS[curScen];curFrame=(curFrame+1)%sc.frames.length;render();}},180);\n");
        sb.append("</script></body></html>\n");

        outFile.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
    }
}
