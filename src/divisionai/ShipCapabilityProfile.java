package divisionai;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;

import java.util.EnumSet;
import java.util.List;

/**
 * Inspects a deployed ShipAPI's hull class, weapon mounts, AI hints, fighter wings,
 * and mobility to determine its tactical capability profile for division auto-formation
 * and commander decision-making.
 */
public class ShipCapabilityProfile {

    public enum TacticalTag {
        CARRIER,
        BATTLE_LINE_CAPITAL,
        MISSILE_CAPITAL,
        STRIKE_CAPITAL,
        LINE_CRUISER,
        MISSILE_CRUISER,
        STRIKE_CRUISER,
        MISSILE_DESTROYER,
        ESCORT_DESTROYER,
        LINE_DESTROYER,
        MISSILE_FRIGATE,
        SCREEN_FRIGATE,
        PHASE,
        CIVILIAN_AUX
    }

    public final ShipAPI ship;
    public final HullSize hullSize;
    public final float fpStrength;
    public final float baseMaxSpeed;
    public final boolean isCarrier;
    public final boolean isDedicatedCarrier;
    public final boolean isPhase;
    public final boolean isCivilian;

    public final float totalWeaponOP;
    public final float strikeMissileWeight;
    public final float pdWeaponWeight;
    public final float longRangeWeight;
    public final float closeBrawlWeight;
    public final float avgWeaponRange;
    public final int bomberWings;
    public final int interceptorSupportWings;

    public final boolean hasHeavyMissileLoadout;
    public final boolean isPdScreenCapable;
    public final boolean isLongRangeStandoff;
    public final TacticalTag primaryTag;

    public ShipCapabilityProfile(ShipAPI ship) {
        this.ship = ship;
        this.hullSize = ship.getHullSize();
        float deployCost = ship.getDeployCost();
        if (deployCost <= 0f && ship.getFleetMember() != null) {
            deployCost = ship.getFleetMember().getDeploymentPointsCost();
        }
        if (deployCost <= 0f) {
            deployCost = defaultStrengthForHull(this.hullSize);
        }
        this.fpStrength = deployCost;
        this.baseMaxSpeed = ship.getMaxSpeedWithoutBoost();

        ShipHullSpecAPI spec = ship.getHullSpec();
        EnumSet<ShipTypeHints> hints = spec != null ? spec.getHints() : EnumSet.noneOf(ShipTypeHints.class);
        this.isPhase = (ship.getPhaseCloak() != null) || hints.contains(ShipTypeHints.PHASE);
        this.isCivilian = hints.contains(ShipTypeHints.CIVILIAN)
                || hints.contains(ShipTypeHints.FREIGHTER)
                || hints.contains(ShipTypeHints.TANKER)
                || hints.contains(ShipTypeHints.LINER)
                || hints.contains(ShipTypeHints.TRANSPORT);

        int bays = ship.getNumFighterBays();
        int bombers = 0;
        int interceptors = 0;
        float wingRangeSum = 0f;
        float wingSpeedSum = 0f;
        int validWingCount = 0;
        List<FighterWingAPI> wings = ship.getAllWings();
        if (wings != null) {
            for (FighterWingAPI wing : wings) {
                if (wing == null || wing.getSpec() == null) continue;
                FighterWingSpecAPI wSpec = wing.getSpec();
                if (wSpec.isBomber() || wSpec.isAssault()) {
                    bombers++;
                } else {
                    interceptors++;
                }
                float wr = wing.getRange() > 0f ? wing.getRange() : 2200f;
                wingRangeSum += Math.max(1200f, Math.min(3400f, wr));
                float fSpd = 235f;
                if (wing.getWingMembers() != null && !wing.getWingMembers().isEmpty()) {
                    ShipAPI f0 = wing.getWingMembers().get(0);
                    if (f0 != null && f0.getMaxSpeed() > 20f) {
                        fSpd = f0.getMaxSpeed();
                    }
                }
                wingSpeedSum += fSpd;
                validWingCount++;
            }
        }
        this.bomberWings = bombers;
        this.interceptorSupportWings = interceptors;
        this.isCarrier = bays > 0 || hints.contains(ShipTypeHints.CARRIER);

        float opSum = 0f;
        float strikeSum = 0f;
        float pdSum = 0f;
        float longSum = 0f;
        float closeSum = 0f;
        float weightedRangeSum = 0f;

        // Include Strike Craft (FighterWingAPI) in weapon range, strike weight, PD weight, and long-range reach
        if (validWingCount > 0) {
            float avgWingRange = wingRangeSum / validWingCount;
            float tacticalWingRange = Math.max(1350f, Math.min(2400f, avgWingRange * 0.75f));
            for (int i = 0; i < validWingCount; i++) {
                float wingOpWeight = 6.5f;
                opSum += wingOpWeight;
                weightedRangeSum += tacticalWingRange * wingOpWeight;
                longSum += wingOpWeight;
            }
            strikeSum += bombers * 7.5f;
            pdSum += interceptors * 5.5f;
        } else if (bays > 0) {
            for (int i = 0; i < bays; i++) {
                float wingOpWeight = 6.0f;
                opSum += wingOpWeight;
                weightedRangeSum += 1800f * wingOpWeight;
                longSum += wingOpWeight;
                strikeSum += 4.5f;
                pdSum += 3.0f;
            }
        }

        float purePdSum = 0f;
        float missileWeight = 0f;
        float weaponOnlyOpSum = 0f;
        int largeMissileCount = 0;
        int mediumMissileCount = 0;
        String hullIdLower = (spec != null && spec.getHullId() != null)
                ? spec.getHullId().toLowerCase(java.util.Locale.ROOT)
                : "";

        List<WeaponAPI> weapons = ship.getAllWeapons();
        if (weapons != null) {
            for (WeaponAPI w : weapons) {
                if (w == null || w.isDecorative()) continue;
                WeaponType type = w.getType();
                if (type == WeaponType.DECORATIVE || type == WeaponType.LAUNCH_BAY || type == WeaponType.SYSTEM || type == WeaponType.STATION_MODULE) {
                    continue;
                }
                WeaponSize effSize = BattlefieldFirepowerEvaluator.resolveEffectiveWeaponSize(w);
                float weight = (w.getSize() == null && (w.getSlot() == null || w.getSlot().getSlotSize() == null))
                        ? 3.0f
                        : sizeWeight(effSize);
                opSum += weight;
                weaponOnlyOpSum += weight;

                float range = w.getRange();
                weightedRangeSum += range * weight;

                boolean isStrike = w.hasAIHint(AIHints.STRIKE);
                BattlefieldFirepowerEvaluator.FireClassification fireClass = BattlefieldFirepowerEvaluator.classifyWeaponFire(w);

                if (type == WeaponType.MISSILE) {
                    missileWeight += weight;
                    if (effSize == WeaponSize.LARGE) {
                        largeMissileCount++;
                    } else if (effSize == WeaponSize.MEDIUM) {
                        mediumMissileCount++;
                    }
                }

                if (fireClass == BattlefieldFirepowerEvaluator.FireClassification.POINT_DEFENSE) {
                    pdSum += weight;
                    purePdSum += weight;
                } else if (fireClass == BattlefieldFirepowerEvaluator.FireClassification.HYBRID) {
                    // Hybrid weapons (Large/Medium Flak, Devastators, Dual Flak, HMG, Paladin PD, PD_ALSO)
                    // count toward BOTH Point Defense AND anti-ship close/mid-range combat weight!
                    pdSum += weight * 0.90f;
                    if (range >= 900f) {
                        longSum += weight * 0.85f;
                    } else if (range <= 700f) {
                        closeSum += weight * 0.85f;
                    }
                } else {
                    if (range >= 900f) {
                        longSum += weight;
                    } else if (range <= 650f) {
                        closeSum += weight;
                    }
                }
                if (isStrike) {
                    strikeSum += weight * (type == WeaponType.MISSILE ? 1.35f : 1.0f);
                }
            }
        }

        this.isDedicatedCarrier = isDedicatedCarrierHull(this.hullSize, bays, hints, opSum - purePdSum);

        this.totalWeaponOP = Math.max(1f, opSum);
        this.strikeMissileWeight = strikeSum;
        this.pdWeaponWeight = pdSum;
        this.longRangeWeight = longSum;
        this.closeBrawlWeight = closeSum;
        this.avgWeaponRange = opSum > 0f ? (weightedRangeSum / opSum) : 700f;

        boolean isHeadlessMockMissileShip = (weapons != null && weapons.size() <= 2 && strikeSum >= 3.0f);
        this.hasHeavyMissileLoadout = isHeavyMissileHullLoadout(
                this.hullSize,
                hullIdLower,
                this.isDedicatedCarrier,
                largeMissileCount,
                missileWeight,
                weaponOnlyOpSum,
                isHeadlessMockMissileShip);

        this.isPdScreenCapable = (pdSum / this.totalWeaponOP) >= 0.32f
                || (interceptorSupportWings >= 1 && pdSum >= 2.0f);

        this.isLongRangeStandoff = this.avgWeaponRange >= 850f || (longSum / this.totalWeaponOP) >= 0.50f;
        this.primaryTag = classifyPrimaryTag();
    }

    private TacticalTag classifyPrimaryTag() {
        if (isCivilian) return TacticalTag.CIVILIAN_AUX;
        if (isPhase) return TacticalTag.PHASE;
        if (isDedicatedCarrier) return TacticalTag.CARRIER;

        switch (hullSize) {
            case CAPITAL_SHIP:
                if (hasHeavyMissileLoadout) {
                    return TacticalTag.MISSILE_CAPITAL;
                }
                if (baseMaxSpeed >= 75f) {
                    return TacticalTag.STRIKE_CAPITAL;
                }
                return TacticalTag.BATTLE_LINE_CAPITAL;
            case CRUISER:
                if (hasHeavyMissileLoadout) {
                    return TacticalTag.MISSILE_CRUISER;
                }
                if (baseMaxSpeed >= 90f && closeBrawlWeight >= longRangeWeight) {
                    return TacticalTag.STRIKE_CRUISER;
                }
                return TacticalTag.LINE_CRUISER;
            case DESTROYER:
                if (hasHeavyMissileLoadout) {
                    return TacticalTag.MISSILE_DESTROYER;
                }
                if (isPdScreenCapable) {
                    return TacticalTag.ESCORT_DESTROYER;
                }
                return TacticalTag.LINE_DESTROYER;
            case FRIGATE:
            default:
                if (hasHeavyMissileLoadout) {
                    return TacticalTag.MISSILE_FRIGATE;
                }
                return TacticalTag.SCREEN_FRIGATE;
        }
    }

    public static float sizeWeight(WeaponSize size) {
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

    public static float resolveShipFpStrength(ShipAPI ship) {
        if (ship == null) return 5f;
        float fp = 0f;
        if (ship.getFleetMember() != null) {
            fp = ship.getFleetMember().getDeploymentPointsCost();
        }
        if (fp <= 0f) {
            fp = defaultStrengthForHull(ship.getHullSize());
        }
        return Math.max(4f, fp);
    }

    public static float defaultStrengthForHull(HullSize size) {
        if (size == null) return 5f;
        switch (size) {
            case CAPITAL_SHIP:
                return 40f;
            case CRUISER:
                return 20f;
            case DESTROYER:
                return 10f;
            case FRIGATE:
                return 5f;
            default:
                return 2f;
        }
    }

    /**
     * Unified battlecarrier check shared by combat (ShipCapabilityProfile) and campaign (PermanentDivisionManager).
     */
    public static boolean isBattlecarrierHull(
            HullSize hullSize,
            int bays,
            EnumSet<ShipTypeHints> hints,
            float nonPdWeaponWeight) {
        if (bays <= 0 || hullSize != HullSize.CAPITAL_SHIP) return false;
        return (hints != null && hints.contains(ShipTypeHints.COMBAT))
                || (bays <= 2 && nonPdWeaponWeight >= 20f);
    }

    /**
     * Unified dedicated-carrier check shared by combat (ShipCapabilityProfile) and campaign (PermanentDivisionManager).
     */
    public static boolean isDedicatedCarrierHull(
            HullSize hullSize,
            int bays,
            EnumSet<ShipTypeHints> hints,
            float nonPdWeaponWeight) {
        boolean isBc = isBattlecarrierHull(hullSize, bays, hints, nonPdWeaponWeight);
        return !isBc && ((bays >= 1 && hints != null && hints.contains(ShipTypeHints.CARRIER)) || bays >= 2);
    }

    /**
     * Unified heavy-missile loadout check shared by combat (ShipCapabilityProfile) and campaign (PermanentDivisionManager).
     */
    public static boolean isHeavyMissileHullLoadout(
            HullSize hullSize,
            String hullIdLower,
            boolean isDedicatedCarrier,
            int largeMissileCount,
            float missileWeight,
            float totalWeaponWeight,
            boolean isHeadlessMockMissileShip) {
        if (isDedicatedCarrier) return false;
        String h = hullIdLower != null ? hullIdLower : "";
        float missileShare = totalWeaponWeight > 0f ? (missileWeight / totalWeaponWeight) : 0f;
        if (hullSize == HullSize.CAPITAL_SHIP) {
            return h.contains("pegasus")
                    || (largeMissileCount >= 2 && missileShare >= 0.38f)
                    || (missileShare >= 0.45f && missileWeight >= 16.0f)
                    || (isHeadlessMockMissileShip && h.contains("missile"));
        } else if (hullSize == HullSize.CRUISER) {
            return h.contains("gryphon")
                    || (largeMissileCount >= 1 && missileShare >= 0.38f)
                    || (missileShare >= 0.45f && missileWeight >= 9.0f)
                    || (isHeadlessMockMissileShip && h.contains("gryphon"));
        } else if (hullSize == HullSize.DESTROYER) {
            return (largeMissileCount >= 1 && missileShare >= 0.38f)
                    || (missileShare >= 0.42f && missileWeight >= 6.0f)
                    || isHeadlessMockMissileShip;
        } else {
            return (missileShare >= 0.45f && missileWeight >= 3.0f)
                    || isHeadlessMockMissileShip;
        }
    }
}
