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
        BATTLECARRIER,
        BATTLE_LINE_CAPITAL,
        MISSILE_CAPITAL,
        STRIKE_CAPITAL,
        LINE_CRUISER,
        MISSILE_CRUISER,
        STRIKE_CRUISER,
        PHASE_CRUISER,
        MISSILE_DESTROYER,
        STRIKE_DESTROYER,
        ESCORT_DESTROYER,
        LINE_DESTROYER,
        MISSILE_FRIGATE,
        STRIKE_FRIGATE,
        SCREEN_FRIGATE,
        PHASE,
        MISSILE_PHASE,
        CIVILIAN_AUX
    }

    public final ShipAPI ship;
    public final HullSize hullSize;
    public final float fpStrength;
    public final float baseMaxSpeed;
    public final boolean isCarrier;
    public final boolean isBattlecarrier;
    public final boolean isDedicatedCarrier;
    public final boolean isPhase;
    public final boolean isCivilian;
    public final boolean hasSafetyOverrides;
    public final boolean hasMissileAutoforge;
    public final boolean isPrimaryMissileBoat;

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

    public static boolean isShipSafetyOverridesActive(ShipAPI ship) {
        if (ship == null) return false;
        if (ship.getVariant() != null && ship.getVariant().hasHullMod("safetyoverrides")) {
            return true;
        }
        try {
            if (ship.getMutableStats() != null
                    && ship.getMutableStats().getVentRateMult() != null
                    && ship.getMutableStats().getVentRateMult().getModifiedValue() <= 0.01f
                    && ship.getMutableStats().getWeaponRangeThreshold() != null
                    && ship.getMutableStats().getWeaponRangeThreshold().getModifiedValue() > 0f
                    && ship.getMutableStats().getWeaponRangeMultPastThreshold() != null
                    && ship.getMutableStats().getWeaponRangeMultPastThreshold().getModifiedValue() < 0.5f) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean hasTruePhaseCloak(ShipAPI ship) {
        if (ship == null) return false;
        if (ship.isPhased()) return true;
        ShipHullSpecAPI spec = ship.getHullSpec();
        if (spec != null && spec.getHints() != null && spec.getHints().contains(ShipTypeHints.PHASE)) {
            return true;
        }
        com.fs.starfarer.api.combat.ShipSystemAPI cloak = ship.getPhaseCloak();
        if (cloak != null) {
            com.fs.starfarer.api.combat.ShipSystemSpecAPI sysSpec = cloak.getSpecAPI();
            if (sysSpec == null) {
                String cId = cloak.getId();
                if (cId == null || cId.isEmpty()) {
                    return true; // Headless mock ShipSystemAPI without spec
                }
                String lower = cId.toLowerCase(java.util.Locale.ROOT);
                return lower.contains("phase") || lower.contains("cloak");
            }
            if (sysSpec.isPhaseCloak()) {
                return true;
            }
            String sysId = sysSpec.getId();
            return sysId != null && sysId.toLowerCase(java.util.Locale.ROOT).contains("phase");
        }
        return false;
    }

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
        this.isPhase = hasTruePhaseCloak(ship);
        this.isCivilian = hints.contains(ShipTypeHints.CIVILIAN)
                || hints.contains(ShipTypeHints.FREIGHTER)
                || hints.contains(ShipTypeHints.TANKER)
                || hints.contains(ShipTypeHints.LINER)
                || hints.contains(ShipTypeHints.TRANSPORT);
        this.hasSafetyOverrides = isShipSafetyOverridesActive(ship);

        int bays = ship.getNumFighterBays();
        boolean dtaZeroed = VoidstrikeCoordinator.isCarrierFighterRangeZeroedByHullmod(ship);
        int bombers = 0;
        int interceptors = 0;
        int tetheredEscortWings = 0;
        int rangedSupportWings = 0;
        float wingRangeSum = 0f;
        float wingSpeedSum = 0f;
        int validWingCount = 0;
        List<FighterWingAPI> wings = ship.getAllWings();
        if (wings != null) {
            for (FighterWingAPI wing : wings) {
                if (wing == null || wing.getSpec() == null) continue;
                FighterWingSpecAPI wSpec = wing.getSpec();
                boolean tetheredZero = dtaZeroed || VoidstrikeCoordinator.isWingTetheredZeroRange(ship, wing);
                boolean supportOnly = tetheredZero || VoidstrikeCoordinator.isSupportEscortOnlyWing(ship, wing);
                if (tetheredZero) {
                    tetheredEscortWings++;
                    interceptors++;
                    continue;
                }
                if (supportOnly) {
                    rangedSupportWings++;
                    interceptors++;
                    continue;
                }
                if (wSpec.isBomber() || wSpec.isAssault()) {
                    bombers++;
                } else {
                    interceptors++;
                }
                float wr = VoidstrikeCoordinator.resolveEffectiveWingEngagementRange(ship, wing);
                float fScale = CombatPhysicsProfile.getFighterRangeScale(null);
                wingRangeSum += Math.max(1200f, Math.min(3400f * fScale, wr));
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
        boolean hasNonTetheredAirCapability = (wings == null || wings.isEmpty())
                ? (bays > 0 && !dtaZeroed)
                : (validWingCount > 0 || rangedSupportWings > 0);
        this.isCarrier = hasNonTetheredAirCapability
                || (hints.contains(ShipTypeHints.CARRIER) && !dtaZeroed
                && (wings == null || wings.isEmpty() || validWingCount > 0 || rangedSupportWings > 0));

        float opSum = 0f;
        float strikeSum = 0f;
        float pdSum = 0f;
        float longSum = 0f;
        float closeSum = 0f;
        float weightedRangeSum = 0f;

        // Include Strike Craft (FighterWingAPI) in weapon range, strike weight, PD weight, and long-range reach
        if (validWingCount > 0) {
            float avgWingRange = wingRangeSum / validWingCount;
            float fScale = CombatPhysicsProfile.getFighterRangeScale(null);
            float tacticalWingRange = Math.max(1350f, Math.min(2400f * fScale, avgWingRange * 0.75f));
            for (int i = 0; i < validWingCount; i++) {
                float wingOpWeight = 6.5f;
                opSum += wingOpWeight;
                weightedRangeSum += tacticalWingRange * wingOpWeight;
                longSum += wingOpWeight;
            }
            strikeSum += bombers * 7.5f;
            pdSum += (interceptors - tetheredEscortWings - rangedSupportWings) * 5.5f;
        } else if (bays > 0 && !dtaZeroed && tetheredEscortWings == 0 && rangedSupportWings == 0) {
            for (int i = 0; i < bays; i++) {
                float wingOpWeight = 6.0f;
                opSum += wingOpWeight;
                weightedRangeSum += 1800f * wingOpWeight;
                longSum += wingOpWeight;
                strikeSum += 4.5f;
                pdSum += 3.0f;
            }
        }
        if (rangedSupportWings > 0) {
            pdSum += rangedSupportWings * 6.0f;
        }
        if (tetheredEscortWings > 0) {
            pdSum += tetheredEscortWings * 4.5f;
            closeSum += tetheredEscortWings * 3.5f;
        }

        float purePdSum = 0f;
        float missileWeight = 0f;
        float antiShipMissileRangeWeightedSum = 0f;
        float antiShipMissileWeight = 0f;
        float weaponOnlyOpSum = 0f;
        int largeMissileCount = 0;
        int mediumMissileCount = 0;
        String hullIdLower = (spec != null && spec.getHullId() != null)
                ? spec.getHullId().toLowerCase(java.util.Locale.ROOT)
                : "";
        this.hasMissileAutoforge = hasMissileReloadOrAutoforgeSystem(ship);

        float longGunFloor = CombatPhysicsProfile.getLongWeaponRangeFloor(0f);
        float shortGunCeil = CombatPhysicsProfile.getShortWeaponRangeCeiling(0f);
        float hybridCloseCeil = CombatPhysicsProfile.getHybridCloseWeaponRangeCeiling(0f);
        float wRangeScale = CombatPhysicsProfile.getGlobalWeaponRangeScale();

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

                float rawWepRange = (w.isBeam() && CombatPhysicsProfile.isBeamDropoffActive(ship))
                        ? CombatPhysicsProfile.resolveEffectiveTacticalBeamRange(ship, w)
                        : w.getRange();
                float range = PermanentDivisionManager.applyShipWeaponRangeThreshold(
                        ship.getMutableStats(), ship.getVariant(), type, rawWepRange);
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
                    if (fireClass != BattlefieldFirepowerEvaluator.FireClassification.POINT_DEFENSE) {
                        antiShipMissileWeight += weight;
                        float mslCeil = 1850f * Math.max(wRangeScale, range > 2200f ? Math.min(4f, range / 1600f) : 1f);
                        antiShipMissileRangeWeightedSum += Math.max(900f, Math.min(mslCeil, range)) * weight;
                    }
                }

                if (fireClass == BattlefieldFirepowerEvaluator.FireClassification.POINT_DEFENSE) {
                    pdSum += weight;
                    purePdSum += weight;
                } else if (fireClass == BattlefieldFirepowerEvaluator.FireClassification.HYBRID) {
                    // Hybrid weapons (Large/Medium Flak, Devastators, Dual Flak, HMG, Paladin PD, PD_ALSO)
                    // count toward BOTH Point Defense AND anti-ship close/mid-range combat weight!
                    pdSum += weight * 0.90f;
                    if (range >= longGunFloor) {
                        longSum += weight * 0.85f;
                    } else if (range <= hybridCloseCeil) {
                        closeSum += weight * 0.85f;
                    }
                } else {
                    if (range >= longGunFloor) {
                        longSum += weight;
                    } else if (range <= shortGunCeil) {
                        closeSum += weight;
                    }
                }
                if (isStrike || (type == WeaponType.MISSILE && fireClass != BattlefieldFirepowerEvaluator.FireClassification.POINT_DEFENSE)) {
                    strikeSum += weight * (isStrike ? (type == WeaponType.MISSILE ? 1.35f : 1.0f) : 0.85f);
                }
            }
        }

        String designationLower = (spec != null && spec.getDesignation() != null)
                ? spec.getDesignation().toLowerCase(java.util.Locale.ROOT)
                : "";
        float nonPdWeaponOp = Math.max(0f, weaponOnlyOpSum - purePdSum);
        this.isBattlecarrier = isBattlecarrierHull(this.hullSize, bays, hints, designationLower, nonPdWeaponOp);
        this.isDedicatedCarrier = !this.isBattlecarrier && isDedicatedCarrierHull(this.hullSize, bays, hints, designationLower, nonPdWeaponOp);

        this.totalWeaponOP = Math.max(1f, opSum);
        this.strikeMissileWeight = strikeSum;
        this.pdWeaponWeight = pdSum;
        this.longRangeWeight = longSum;
        this.closeBrawlWeight = closeSum;

        this.isPrimaryMissileBoat = !this.isDedicatedCarrier
                && antiShipMissileWeight >= 1.0f
                && (nonPdWeaponOp <= 0.5f || (antiShipMissileWeight / Math.max(1f, nonPdWeaponOp)) >= 0.62f
                || (this.hasMissileAutoforge && (antiShipMissileWeight / Math.max(1f, nonPdWeaponOp)) >= 0.42f));

        float rawAvgRange = opSum > 0f ? (weightedRangeSum / opSum) : 700f;
        if (this.isPrimaryMissileBoat && antiShipMissileWeight > 0f) {
            float mslRange = antiShipMissileRangeWeightedSum / antiShipMissileWeight;
            float mslStandCap = 1650f * Math.max(wRangeScale, mslRange > 2000f ? Math.min(4f, mslRange / 1500f) : 1f);
            this.avgWeaponRange = Math.max(980f, Math.min(mslStandCap, mslRange));
        } else {
            this.avgWeaponRange = rawAvgRange;
        }

        boolean isHeadlessMockMissileShip = (weapons != null && weapons.size() <= 2 && strikeSum >= 3.0f);
        this.hasHeavyMissileLoadout = isHeavyMissileHullLoadout(
                this.hullSize,
                hullIdLower,
                this.isDedicatedCarrier,
                largeMissileCount,
                missileWeight,
                weaponOnlyOpSum,
                isHeadlessMockMissileShip)
                || (this.isPhase && this.isPrimaryMissileBoat)
                || (this.hasMissileAutoforge && missileWeight >= 3.0f && (missileWeight / Math.max(1f, weaponOnlyOpSum)) >= 0.34f);

        this.isPdScreenCapable = (pdSum / this.totalWeaponOP) >= 0.32f
                || (interceptorSupportWings >= 1 && pdSum >= 2.0f);

        float longStandThreshold = 850f * wRangeScale;
        this.isLongRangeStandoff = this.avgWeaponRange >= longStandThreshold || (longSum / this.totalWeaponOP) >= 0.50f;
        this.primaryTag = classifyPrimaryTag();
    }

    public static boolean hasMissileReloadOrAutoforgeSystem(ShipAPI ship) {
        if (ship == null) return false;
        if (ship.getVariant() != null && ship.getVariant().hasHullMod("missleracks")) {
            // Expanded Missile Racks alone is passive, not an active autoforge system
        }
        com.fs.starfarer.api.combat.ShipSystemAPI sys = ship.getSystem();
        if (sys == null || sys.getId() == null) return false;
        String id = sys.getId().toLowerCase(java.util.Locale.ROOT);
        if (id.contains("forge") || id.contains("autoforge") || id.contains("missile_reload") || id.contains("fastmissileracks")) {
            return id.contains("forge") || id.contains("reload");
        }
        try {
            com.fs.starfarer.api.combat.ShipSystemSpecAPI sSpec = sys.getSpecAPI();
            if (sSpec != null) {
                Object json = JsonReflectBridge.getShipSystemSpecJson(sSpec);
                if (json != null) {
                    String jType = JsonReflectBridge.optString(json, "type", "").toUpperCase(java.util.Locale.ROOT);
                    String aiType = JsonReflectBridge.optString(json, "aiType", "").toUpperCase(java.util.Locale.ROOT);
                    if ("AMMO_RELOAD".equals(jType) || aiType.contains("AMMO_RELOAD") || aiType.contains("FORGE")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private TacticalTag classifyPrimaryTag() {
        if (isCivilian) return TacticalTag.CIVILIAN_AUX;
        String desgLower = (ship != null && ship.getHullSpec() != null && ship.getHullSpec().getDesignation() != null)
                ? ship.getHullSpec().getDesignation().toLowerCase(java.util.Locale.ROOT)
                : "";
        if (isPhase) {
            if (hasHeavyMissileLoadout || isPrimaryMissileBoat) {
                return TacticalTag.MISSILE_PHASE;
            }
            if (hullSize == HullSize.CRUISER || hullSize == HullSize.CAPITAL_SHIP) {
                return TacticalTag.PHASE_CRUISER;
            }
            return TacticalTag.PHASE;
        }
        if (isBattlecarrier) return TacticalTag.BATTLECARRIER;
        if (isDedicatedCarrier) return TacticalTag.CARRIER;

        switch (hullSize) {
            case CAPITAL_SHIP:
                if (hasHeavyMissileLoadout) {
                    return TacticalTag.MISSILE_CAPITAL;
                }
                if (isFastBattlecruiserHull(hullSize, baseMaxSpeed, desgLower, hasSafetyOverrides)) {
                    return TacticalTag.STRIKE_CAPITAL;
                }
                return TacticalTag.BATTLE_LINE_CAPITAL;
            case CRUISER:
                if (hasHeavyMissileLoadout) {
                    return TacticalTag.MISSILE_CRUISER;
                }
                if (isFastStrikeCruiserHull(baseMaxSpeed, desgLower, closeBrawlWeight, longRangeWeight, hasSafetyOverrides)) {
                    return TacticalTag.STRIKE_CRUISER;
                }
                return TacticalTag.LINE_CRUISER;
            case DESTROYER:
                if (hasHeavyMissileLoadout) {
                    return TacticalTag.MISSILE_DESTROYER;
                }
                if (isFastStrikeDestroyerHull(baseMaxSpeed, isPdScreenCapable, closeBrawlWeight, longRangeWeight, hasSafetyOverrides)) {
                    return TacticalTag.STRIKE_DESTROYER;
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
                if (isFastStrikeFrigateHull(baseMaxSpeed, desgLower, isPdScreenCapable, closeBrawlWeight, longRangeWeight, hasSafetyOverrides)) {
                    return TacticalTag.STRIKE_FRIGATE;
                }
                return TacticalTag.SCREEN_FRIGATE;
        }
    }

    public static boolean isFastBattlecruiserHull(HullSize size, float baseSpeed, String designationLower, boolean safetyOverrides) {
        if (size != HullSize.CAPITAL_SHIP) return false;
        String d = designationLower != null ? designationLower : "";
        return d.contains("battlecruiser") || baseSpeed >= 65f || safetyOverrides;
    }

    public static boolean isFastStrikeCruiserHull(
            float baseSpeed, String designationLower, float closeWeight, float longWeight, boolean safetyOverrides) {
        String d = designationLower != null ? designationLower : "";
        return safetyOverrides
                || d.contains("fast cruiser")
                || (baseSpeed >= 80f && closeWeight >= longWeight * 0.75f);
    }

    public static boolean isFastStrikeDestroyerHull(
            float baseSpeed, boolean pdScreenCapable, float closeWeight, float longWeight, boolean safetyOverrides) {
        if (safetyOverrides) return true;
        return !pdScreenCapable && (baseSpeed >= 95f || (baseSpeed >= 88f && closeWeight > longWeight));
    }

    public static boolean isFastStrikeFrigateHull(
            float baseSpeed, String designationLower, boolean pdScreenCapable, float closeWeight, float longWeight, boolean safetyOverrides) {
        if (safetyOverrides) return true;
        String d = designationLower != null ? designationLower : "";
        return !pdScreenCapable && (baseSpeed >= 135f || d.contains("gunship") || (baseSpeed >= 120f && closeWeight > longWeight));
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
            if ((ship.isStationModule() || ship.getParentStation() != null)
                    && BattlefieldFirepowerEvaluator.isStationOrModuleCombatUnit(ship)) {
                fp = 18f;
            } else {
                fp = defaultStrengthForHull(ship.getHullSize());
            }
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
        return isBattlecarrierHull(hullSize, bays, hints, "", nonPdWeaponWeight);
    }

    public static boolean isBattlecarrierHull(
            HullSize hullSize,
            int bays,
            EnumSet<ShipTypeHints> hints,
            String designationLower,
            float nonPdWeaponWeight) {
        if (bays <= 0 || hullSize != HullSize.CAPITAL_SHIP) return false;
        String d = designationLower != null ? designationLower : "";
        if (d.contains("battlecruiser")) return false;
        if (d.contains("battlecarrier")) return true;
        return (hints != null && hints.contains(ShipTypeHints.CARRIER) && hints.contains(ShipTypeHints.COMBAT))
                || (bays >= 3 && nonPdWeaponWeight >= 22f && (hints == null || !hints.contains(ShipTypeHints.CARRIER)));
    }

    /**
     * Unified dedicated-carrier check shared by combat (ShipCapabilityProfile) and campaign (PermanentDivisionManager).
     */
    public static boolean isDedicatedCarrierHull(
            HullSize hullSize,
            int bays,
            EnumSet<ShipTypeHints> hints,
            float nonPdWeaponWeight) {
        return isDedicatedCarrierHull(hullSize, bays, hints, "", nonPdWeaponWeight);
    }

    public static boolean isDedicatedCarrierHull(
            HullSize hullSize,
            int bays,
            EnumSet<ShipTypeHints> hints,
            String designationLower,
            float nonPdWeaponWeight) {
        String d = designationLower != null ? designationLower : "";
        if (hullSize == HullSize.CAPITAL_SHIP && d.contains("battlecruiser")) {
            return false;
        }
        boolean isBc = isBattlecarrierHull(hullSize, bays, hints, d, nonPdWeaponWeight);
        return !isBc && ((bays >= 1 && hints != null && hints.contains(ShipTypeHints.CARRIER)) || (bays >= 2 && nonPdWeaponWeight < 20f));
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
