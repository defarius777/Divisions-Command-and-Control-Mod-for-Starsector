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
        STRIKE_CAPITAL,
        LINE_CRUISER,
        STRIKE_CRUISER,
        TORPEDO_DESTROYER,
        ESCORT_DESTROYER,
        LINE_DESTROYER,
        TORPEDO_FRIGATE,
        SCREEN_FRIGATE,
        PHASE_RAIDER,
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

    public final boolean hasHeavyTorpedoLoadout;
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
            }
        }
        this.bomberWings = bombers;
        this.interceptorSupportWings = interceptors;
        this.isCarrier = bays > 0 || hints.contains(ShipTypeHints.CARRIER);
        this.isDedicatedCarrier = (bays >= 2) || (hints.contains(ShipTypeHints.CARRIER) && bays >= 1);

        float opSum = 0f;
        float strikeSum = 0f;
        float pdSum = 0f;
        float longSum = 0f;
        float closeSum = 0f;
        float weightedRangeSum = 0f;

        List<WeaponAPI> weapons = ship.getAllWeapons();
        if (weapons != null) {
            for (WeaponAPI w : weapons) {
                if (w == null || w.isDecorative()) continue;
                WeaponType type = w.getType();
                if (type == WeaponType.DECORATIVE || type == WeaponType.LAUNCH_BAY || type == WeaponType.SYSTEM || type == WeaponType.STATION_MODULE) {
                    continue;
                }
                float weight = sizeWeight(w.getSize());
                opSum += weight;

                float range = w.getRange();
                weightedRangeSum += range * weight;

                boolean isPd = w.hasAIHint(AIHints.PD) || w.hasAIHint(AIHints.PD_ONLY) || w.hasAIHint(AIHints.PD_ALSO) || w.hasAIHint(AIHints.ANTI_FTR);
                boolean isStrike = w.hasAIHint(AIHints.STRIKE);

                if (isPd) {
                    pdSum += weight;
                }
                if (isStrike) {
                    strikeSum += weight * (type == WeaponType.MISSILE ? 1.35f : 1.0f);
                }
                if (!isPd) {
                    if (range >= 900f) {
                        longSum += weight;
                    } else if (range <= 650f) {
                        closeSum += weight;
                    }
                }
            }
        }

        this.totalWeaponOP = Math.max(1f, opSum);
        this.strikeMissileWeight = strikeSum;
        this.pdWeaponWeight = pdSum;
        this.longRangeWeight = longSum;
        this.closeBrawlWeight = closeSum;
        this.avgWeaponRange = opSum > 0f ? (weightedRangeSum / opSum) : 700f;

        // Torpedo / strike loadout detection: strong strike missiles on non-carrier hulls
        this.hasHeavyTorpedoLoadout = !this.isDedicatedCarrier
                && ((strikeSum >= 4.0f && (strikeSum / this.totalWeaponOP) >= 0.28f)
                || (strikeSum >= 3.0f && (hullSize == HullSize.FRIGATE || hullSize == HullSize.DESTROYER)));

        this.isPdScreenCapable = (pdSum / this.totalWeaponOP) >= 0.32f
                || (interceptorSupportWings >= 1 && pdSum >= 2.0f);

        this.isLongRangeStandoff = this.avgWeaponRange >= 850f || (longSum / this.totalWeaponOP) >= 0.50f;
        this.primaryTag = classifyPrimaryTag();
    }

    private TacticalTag classifyPrimaryTag() {
        if (isCivilian) return TacticalTag.CIVILIAN_AUX;
        if (isPhase) return TacticalTag.PHASE_RAIDER;
        if (isDedicatedCarrier) return TacticalTag.CARRIER;

        switch (hullSize) {
            case CAPITAL_SHIP:
                if (baseMaxSpeed >= 75f || (hasHeavyTorpedoLoadout && !isLongRangeStandoff)) {
                    return TacticalTag.STRIKE_CAPITAL;
                }
                return TacticalTag.BATTLE_LINE_CAPITAL;
            case CRUISER:
                if (hasHeavyTorpedoLoadout || (baseMaxSpeed >= 90f && closeBrawlWeight >= longRangeWeight)) {
                    return TacticalTag.STRIKE_CRUISER;
                }
                return TacticalTag.LINE_CRUISER;
            case DESTROYER:
                if (hasHeavyTorpedoLoadout) {
                    return TacticalTag.TORPEDO_DESTROYER;
                }
                if (isPdScreenCapable) {
                    return TacticalTag.ESCORT_DESTROYER;
                }
                return TacticalTag.LINE_DESTROYER;
            case FRIGATE:
            default:
                if (hasHeavyTorpedoLoadout) {
                    return TacticalTag.TORPEDO_FRIGATE;
                }
                return TacticalTag.SCREEN_FRIGATE;
        }
    }

    private static float sizeWeight(WeaponSize size) {
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
}
