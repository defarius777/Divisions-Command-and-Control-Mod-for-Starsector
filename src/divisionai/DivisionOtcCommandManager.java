package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionState;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import org.apache.log4j.Logger;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fleet OTC (Overall Tactical Commander) reassignment & command-disruption state machine.
 */
public class DivisionOtcCommandManager {

    private static final Logger LOG = Global.getLogger(DivisionOtcCommandManager.class);

    public static final float OTC_REASSIGN_COMMAND_POINT_COST_FRACTION = 0.75f;
    public static final float FLEET_COMMANDER_FLAGSHIP_LOSS_BLACKOUT_SECONDS = 120.0f;
    public static final float LOWER_COMMANDER_FLAGSHIP_LOSS_BLACKOUT_SECONDS = 30.0f;

    /** Tracks the player's explicitly chosen OTC FleetMemberAPI id across pre-battle and mid-battle UI refreshes. */
    private static String preferredPlayerOtcMemberId = null;

    /** Snapshot of an eligible OTC candidate ship/member. */
    public static class OtcCandidate {
        public final FleetMemberAPI member;
        public final ShipAPI ship;
        public final String memberId;
        public final String shipId;
        public final String shipName;
        public final String hullName;
        public final String commanderName;
        public final String assignmentLabel;
        public final boolean alphaCore;
        public final boolean divisionLeader;
        public final boolean independent;

        public OtcCandidate(
                FleetMemberAPI member,
                ShipAPI ship,
                String assignmentLabel,
                boolean divisionLeader,
                boolean alphaCoreOverride) {
            this.member = member;
            this.ship = ship;
            this.memberId = member != null ? member.getId() : (ship != null && ship.getFleetMember() != null ? ship.getFleetMember().getId() : null);
            this.shipId = ship != null ? ship.getId() : null;
            this.shipName = (member != null && member.getShipName() != null && !member.getShipName().trim().isEmpty())
                    ? member.getShipName().trim()
                    : (ship != null && ship.getName() != null ? ship.getName().trim() : "Flagship");
            this.hullName = (member != null && member.getHullSpec() != null && member.getHullSpec().getHullNameWithDashClass() != null)
                    ? member.getHullSpec().getHullNameWithDashClass()
                    : (ship != null && ship.getHullSpec() != null ? ship.getHullSpec().getHullNameWithDashClass() : "Command Hull");
            PersonAPI captain = resolveCandidatePerson(member, ship);
            this.commanderName = (captain != null && captain.getNameString() != null && !captain.getNameString().trim().isEmpty())
                    ? captain.getNameString().trim()
                    : "Fleet Commander";
            this.alphaCore = alphaCoreOverride
                    || DivisionDeploymentDialogOrganizer.isFlagshipAlphaCoreEquipped(null, member, null)
                    || BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(ship);
            this.assignmentLabel = assignmentLabel;
            this.divisionLeader = divisionLeader;
            this.independent = !divisionLeader;
        }
    }

    public static void resetForNewBattle() {
        preferredPlayerOtcMemberId = null;
    }

    public static String getPreferredPlayerOtcMemberId(BattleGroupState playerGroup) {
        if (playerGroup != null && playerGroup.preferredOtcShipId != null && !playerGroup.preferredOtcShipId.isEmpty()) {
            return playerGroup.preferredOtcShipId;
        }
        return preferredPlayerOtcMemberId;
    }

    public static void setPreferredPlayerOtcMemberId(BattleGroupState playerGroup, String id) {
        preferredPlayerOtcMemberId = id;
        if (playerGroup != null) {
            playerGroup.preferredOtcShipId = id;
            playerGroup.otcManuallyAssigned = (id != null && !id.isEmpty());
        }
    }

    public static PersonAPI resolveCandidatePerson(FleetMemberAPI member, ShipAPI ship) {
        if (member != null && member.getCaptain() != null && !member.getCaptain().isDefault()) {
            return member.getCaptain();
        }
        if (ship != null && ship.getCaptain() != null && !ship.getCaptain().isDefault()) {
            return ship.getCaptain();
        }
        if (member != null && member.isFlagship()) {
            try {
                if (Global.getSector() != null && Global.getSector().getPlayerPerson() != null) {
                    return Global.getSector().getPlayerPerson();
                }
            } catch (Throwable ignored) {
            }
        }
        return member != null ? member.getCaptain() : (ship != null ? ship.getCaptain() : null);
    }

    /** Returns true if the given side's OTC is currently disabled by a flagship loss. */
    public static boolean isOtcDisabled(BattleGroupState group, float now) {
        if (group == null) return false;
        if (group.side == 0 && !group.isAlliedAi && group.useConvergence
                && BattlefieldFirepowerEvaluator.getPlayerConvergenceFlagshipOverride() != null
                && BattlefieldFirepowerEvaluator.getPlayerConvergenceFlagshipOverride().isAlive()) {
            return false;
        }
        return now < group.otcDisabledUntilTime;
    }

    public static float getOtcDisabledSecondsRemaining(BattleGroupState group, float now) {
        if (group == null) return 0f;
        return Math.max(0f, group.otcDisabledUntilTime - now);
    }

    /** True if the player fleet's active OTC is an Alpha Core (cannot be manually changed). */
    public static boolean isActivePlayerOtcAlphaCore(
            CombatEngineAPI engine,
            FleetMemberAPI currentFlagshipMember,
            BattleGroupState playerGroup) {
        return DivisionDeploymentDialogOrganizer.isFlagshipAlphaCoreEquipped(engine, currentFlagshipMember, playerGroup);
    }

    /**
     * Collects all eligible player OTC candidates from the Deployment Screen / battlefield:
     *  1. Division leader commanders (flagships of each non-empty division),
     *  2. Commanders of independent ships (ships not in any division that have a non-default captain or are the fleet flagship),
     *  3. Any Alpha Core-equipped ship whether in or out of a division.
     */
    public static List<OtcCandidate> collectPlayerOtcCandidates(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            List<FleetMemberAPI> deployedMembers,
            List<FleetMemberAPI> reserveMembers) {

        List<FleetMemberAPI> allMembers = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        if (deployedMembers != null) {
            for (FleetMemberAPI m : deployedMembers) {
                if (m != null && m.getId() != null && seenIds.add(m.getId())) {
                    allMembers.add(m);
                }
            }
        }
        if (reserveMembers != null) {
            for (FleetMemberAPI m : reserveMembers) {
                if (m != null && m.getId() != null && seenIds.add(m.getId())) {
                    allMembers.add(m);
                }
            }
        }
        if (allMembers.isEmpty() && engine != null && engine.getShips() != null) {
            for (ShipAPI s : engine.getShips()) {
                if (s == null || s.getOwner() != 0 || s.isAlly() || !DivisionAutoFormer.isEligibleCombatHull(s)) continue;
                FleetMemberAPI m = s.getFleetMember();
                if (m != null && m.getId() != null && seenIds.add(m.getId())) {
                    allMembers.add(m);
                }
            }
        }

        List<PermanentDivisionSpec> specs = PermanentDivisionManager.getNonEmptyPermanentDivisions();
        Set<String> divisionMemberIds = new HashSet<>();
        Set<String> divisionLeaderIds = new HashSet<>();
        for (PermanentDivisionSpec spec : specs) {
            if (spec == null || spec.memberIds == null || spec.memberIds.isEmpty()) continue;
            divisionMemberIds.addAll(spec.memberIds);
            String leaderId = (spec.flagshipMemberId != null && spec.memberIds.contains(spec.flagshipMemberId))
                    ? spec.flagshipMemberId
                    : spec.memberIds.get(0);
            if (leaderId != null) {
                divisionLeaderIds.add(leaderId);
            }
        }
        if (playerGroup != null) {
            for (DivisionState d : playerGroup.divisions) {
                if (d == null) continue;
                for (ShipAPI s : d.members) {
                    if (s != null && s.getFleetMember() != null && s.getFleetMember().getId() != null) {
                        divisionMemberIds.add(s.getFleetMember().getId());
                    }
                }
                if (d.flagship != null && d.flagship.getFleetMember() != null && d.flagship.getFleetMember().getId() != null) {
                    divisionLeaderIds.add(d.flagship.getFleetMember().getId());
                }
            }
        }

        List<OtcCandidate> out = new ArrayList<>();
        Set<String> addedCandidateIds = new HashSet<>();

        for (FleetMemberAPI m : allMembers) {
            if (m == null || m.isFighterWing() || m.getId() == null) continue;
            ShipAPI ship = findLiveShipForMember(engine, m.getId());
            if (ship != null && (!ship.isAlive() || ship.isHulk())) continue;

            boolean isDivLeader = divisionLeaderIds.contains(m.getId());
            boolean inDiv = divisionMemberIds.contains(m.getId());
            boolean isAlpha = DivisionDeploymentDialogOrganizer.isFlagshipAlphaCoreEquipped(null, m, null)
                    || BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(ship);
            PersonAPI cap = m.getCaptain();
            boolean hasCommander = (cap != null && !cap.isDefault()) || m.isFlagship();

            if (isDivLeader || (!inDiv && hasCommander) || isAlpha) {
                if (addedCandidateIds.add(m.getId())) {
                    String label = isAlpha
                            ? "Alpha Core Vessel"
                            : (isDivLeader ? "Division Leader" : "Independent Commander");
                    out.add(new OtcCandidate(m, ship, label, isDivLeader, isAlpha));
                }
            }
        }

        return out;
    }

    private static ShipAPI findLiveShipForMember(CombatEngineAPI engine, String memberId) {
        if (engine == null || memberId == null || engine.getShips() == null) return null;
        for (ShipAPI s : engine.getShips()) {
            if (s == null) continue;
            if (memberId.equals(s.getId())) return s;
            if (s.getFleetMember() != null && memberId.equals(s.getFleetMember().getId())) return s;
        }
        return null;
    }

    /**
     * Cycles the player's OTC to the next eligible candidate from the Deployment Screen "Change" button.
     * Pre-battle is free; mid-battle costs 75% of max CP unless transferring to an Alpha Core ship.
     */
    public static boolean cycleToNextEligiblePlayerOtc(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            List<FleetMemberAPI> deployedMembers,
            List<FleetMemberAPI> reserveMembers,
            FleetMemberAPI currentFlagshipMember,
            boolean preBattle) {

        if (isActivePlayerOtcAlphaCore(engine, currentFlagshipMember, playerGroup)) {
            notifyPlayer(engine, "OTC cannot be changed while an Alpha Core holds command.", false);
            return false;
        }
        float now = (engine != null) ? engine.getTotalElapsedTime(false) : 0f;
        if (isOtcDisabled(playerGroup, now)) {
            notifyPlayer(engine, String.format(Locale.ROOT,
                    "OTC disabled for %.0fs following flagship destruction.",
                    getOtcDisabledSecondsRemaining(playerGroup, now)), false);
            return false;
        }

        List<OtcCandidate> candidates = collectPlayerOtcCandidates(engine, playerGroup, deployedMembers, reserveMembers);
        if (candidates.size() <= 1) {
            return false;
        }

        String curId = (currentFlagshipMember != null) ? currentFlagshipMember.getId() : getPreferredPlayerOtcMemberId(playerGroup);
        int curIdx = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (curId != null && curId.equals(candidates.get(i).memberId)) {
                curIdx = i;
                break;
            }
        }

        for (int step = 1; step < candidates.size(); step++) {
            OtcCandidate next = candidates.get((Math.max(0, curIdx) + step) % candidates.size());
            if (curId != null && curId.equals(next.memberId)) continue;
            if (applyPlayerOtcCandidate(engine, playerGroup, next, preBattle, now)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies the chosen OTC candidate, charging 75% of max CP if mid-battle and not an Alpha Core Convergence transfer.
     */
    public static boolean applyPlayerOtcCandidate(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            OtcCandidate candidate,
            boolean preBattle,
            float now) {
        if (candidate == null) return false;
        if (playerGroup != null && isOtcDisabled(playerGroup, now) && !candidate.alphaCore) {
            notifyPlayer(engine, String.format(Locale.ROOT,
                    "OTC disabled for %.0fs following flagship destruction.",
                    getOtcDisabledSecondsRemaining(playerGroup, now)), false);
            return false;
        }

        int cpCost = 0;
        if (!preBattle && !candidate.alphaCore) {
            cpCost = getOtcReassignmentCommandPointCost(engine);
            int available = getPlayerCommandPointsLeft(engine);
            if (cpCost > 0 && available < cpCost) {
                notifyPlayer(engine, String.format(Locale.ROOT,
                        "Insufficient Command Points to change OTC (requires %d CP [75%% of max], available %d CP).",
                        cpCost, available), false);
                return false;
            }
        }

        if (cpCost > 0) {
            spendCommandPoints(engine, cpCost);
        }

        setPreferredPlayerOtcMemberId(playerGroup, candidate.memberId != null ? candidate.memberId : candidate.shipId);
        PersonAPI person = resolveCandidatePerson(candidate.member, candidate.ship);
        if (playerGroup != null) {
            playerGroup.otc = new CommanderProfile(person, candidate.commanderName);
            playerGroup.lastOtcReassignmentTime = now;
            if (candidate.ship != null) {
                playerGroup.activeFleetFlagship = candidate.ship;
            }
            if (candidate.alphaCore) {
                playerGroup.useConvergence = true;
                playerGroup.otcDisabledUntilTime = -100f;
                playerGroup.otcDisabledWarningActive = false;
            } else if (playerGroup.forceConvergenceOverride == null) {
                playerGroup.useConvergence = false;
            }
        }

        String msg = candidate.alphaCore
                ? String.format(Locale.ROOT, "Convergence transfer: Alpha Core on %s assumes OTC (0 CP).", candidate.shipName)
                : (cpCost > 0
                ? String.format(Locale.ROOT, "OTC transferred to %s on %s (-%d CP).", candidate.commanderName, candidate.shipName, cpCost)
                : String.format(Locale.ROOT, "OTC assigned to %s on %s.", candidate.commanderName, candidate.shipName));
        LOG.info("[DivisionAI] " + msg);
        if (!preBattle) {
            notifyPlayer(engine, msg, true);
        }
        return true;
    }

    public static int getPlayerCommandPointsLeft(CombatEngineAPI engine) {
        CombatTaskManagerAPI tm = getPlayerTaskManager(engine);
        if (tm == null) return 0;
        try {
            return Math.max(0, tm.getCommandPointsLeft());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static int getPlayerMaxCommandPoints(CombatEngineAPI engine) {
        CombatTaskManagerAPI tm = getPlayerTaskManager(engine);
        if (tm == null) return 0;
        try {
            MutableStat stat = tm.getCommandPointsStat();
            if (stat == null) return 0;
            float max = stat.base;
            if (stat.getPercentMods() != null) {
                for (MutableStat.StatMod mod : stat.getPercentMods().values()) {
                    if (mod != null) max += stat.base * mod.value / 100f;
                }
            }
            if (stat.getFlatMods() != null) {
                for (MutableStat.StatMod mod : stat.getFlatMods().values()) {
                    if (mod != null && mod.value > 0f) max += mod.value;
                }
            }
            return Math.max(0, Math.round(max));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static int getOtcReassignmentCommandPointCost(CombatEngineAPI engine) {
        int maxCp = getPlayerMaxCommandPoints(engine);
        if (maxCp <= 0) return 0;
        return Math.max(1, (int) Math.ceil(maxCp * OTC_REASSIGN_COMMAND_POINT_COST_FRACTION));
    }

    private static CombatTaskManagerAPI getPlayerTaskManager(CombatEngineAPI engine) {
        if (engine == null) return null;
        try {
            CombatFleetManagerAPI fm = engine.getFleetManager(0);
            if (fm == null) return null;
            return fm.getTaskManager(false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void spendCommandPoints(CombatEngineAPI engine, int cost) {
        if (engine == null || cost <= 0) return;
        CombatTaskManagerAPI tm = getPlayerTaskManager(engine);
        if (tm == null) return;
        try {
            MutableStat stat = tm.getCommandPointsStat();
            if (stat == null) return;
            float prev = 0f;
            MutableStat.StatMod existing = stat.getFlatStatMod("divisionai_otc_reassignment");
            if (existing != null) {
                prev = existing.value;
            }
            stat.modifyFlat("divisionai_otc_reassignment", prev - cost);
        } catch (Throwable t) {
            LOG.warn("[DivisionAI] Failed to deduct OTC reassignment command points: " + t);
        }
    }

    /**
     * Checks whether a side's active fleet flagship (or an enemy division flagship) was destroyed;
     * if so, disables that side's OTC for 120s (overall commander) or 30s (lower/no station) and emits
     * top-left HUD warnings.
     */
    public static void updateFlagshipLossCommandDisruption(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            BattleGroupState alliedGroup,
            BattleGroupState enemyGroup,
            float now) {
        if (engine == null) return;
        checkGroupFlagshipLoss(engine, playerGroup, now, true);
        checkGroupFlagshipLoss(engine, alliedGroup, now, false);
        checkGroupFlagshipLoss(engine, enemyGroup, now, true);
        checkEnemyDivisionFlagshipsDestroyed(engine, enemyGroup, now);
    }

    public static void checkGroupFlagshipLoss(
            CombatEngineAPI engine,
            BattleGroupState group,
            float now,
            boolean announce) {
        if (group == null) return;
        ShipAPI flagship = group.activeFleetFlagship;
        if (flagship == null) return;
        String flagshipId = flagship.getId();
        if (flagshipId == null) return;
        String lossKey = "__otc_lost_" + flagshipId;
        if (group.formationSpawnHijackedShipIds.contains(lossKey)) {
            return;
        }
        boolean destroyed = !flagship.isAlive() || flagship.isHulk();
        if (!destroyed) return;

        boolean overallCommanderAboard = isFleetOverallCommanderAboard(engine, group, flagship);
        float blackout = overallCommanderAboard
                ? FLEET_COMMANDER_FLAGSHIP_LOSS_BLACKOUT_SECONDS
                : LOWER_COMMANDER_FLAGSHIP_LOSS_BLACKOUT_SECONDS;
        group.otcDisabledUntilTime = Math.max(group.otcDisabledUntilTime, now + blackout);
        group.otcDisabledWarningActive = true;
        group.formationSpawnHijackedShipIds.add(lossKey);
        group.formationSpawnHijackedShipIds.add("__overall_cmdr_lost");
        if (group.side == 0 && !group.isAlliedAi) {
            group.preferredOtcShipId = null;
            preferredPlayerOtcMemberId = null;
        }

        String shipName = flagship.getName() != null ? flagship.getName() : "Flagship";
        String durationStr = overallCommanderAboard ? "2 minutes (120s)" : "30 seconds";
        String stationDesc = overallCommanderAboard ? "overall fleet commander" : "lower/no station commander";
        String logLine = String.format(Locale.ROOT,
                "[DivisionAI] %s flagship \"%s\" (%s) destroyed! OTC disabled for %s.",
                group.side == 0 ? (group.isAlliedAi ? "Allied" : "Player") : "Enemy",
                shipName, stationDesc, durationStr);
        LOG.warn(logLine);
        if (DivisionCombatLog.simLogSink != null) {
            DivisionCombatLog.simLogSink.add(String.format(Locale.ROOT, "[t=%5.1fs] %s", Math.max(0f, now), logLine));
        }

        if (announce) {
            if (group.side == 1) {
                notifyPlayer(engine, String.format(Locale.ROOT,
                        "WARNING: Enemy flagship \"%s\" destroyed! Enemy OTC disabled for %s.",
                        shipName, durationStr), true);
            } else {
                notifyPlayer(engine, String.format(Locale.ROOT,
                        "WARNING: Flagship \"%s\" destroyed (%s)! Friendly OTC disabled for %s.",
                        shipName, stationDesc, durationStr), false);
            }
        }
    }

    private static void checkEnemyDivisionFlagshipsDestroyed(
            CombatEngineAPI engine,
            BattleGroupState enemyGroup,
            float now) {
        if (enemyGroup == null) return;
        for (DivisionState div : enemyGroup.divisions) {
            if (div == null || div.flagship == null) continue;
            ShipAPI f = div.flagship;
            if (f.getId() == null) continue;
            String divFlagKey = "__div_flag_lost_" + f.getId();
            if (enemyGroup.formationSpawnHijackedShipIds.contains(divFlagKey)) continue;
            if (enemyGroup.formationSpawnHijackedShipIds.contains("__otc_lost_" + f.getId())) {
                enemyGroup.formationSpawnHijackedShipIds.add(divFlagKey);
                continue;
            }
            if (!f.isAlive() || f.isHulk()) {
                enemyGroup.formationSpawnHijackedShipIds.add(divFlagKey);
                String shipName = f.getName() != null ? f.getName() : "Enemy Flagship";
                String divName = div.displayName != null ? div.displayName : "Enemy Division";
                String line = String.format(Locale.ROOT,
                        "[DivisionAI] Enemy division flagship \"%s\" (%s) destroyed!",
                        shipName, divName);
                LOG.info(line);
                if (DivisionCombatLog.simLogSink != null) {
                    DivisionCombatLog.simLogSink.add(String.format(Locale.ROOT, "[t=%5.1fs] %s", Math.max(0f, now), line));
                }
                notifyPlayer(engine, String.format(Locale.ROOT,
                        "Enemy flagship \"%s\" (%s) destroyed!",
                        shipName, divName), true);
            }
        }
    }

    public static void onOtcRestored(CombatEngineAPI engine, BattleGroupState group, float now) {
        if (group == null) return;
        String sideName = group.side == 0 ? (group.isAlliedAi ? "Allied" : "Friendly") : "Enemy";
        String line = String.format(Locale.ROOT, "[DivisionAI] %s OTC command channel restored.", sideName);
        LOG.info(line);
        if (DivisionCombatLog.simLogSink != null) {
            DivisionCombatLog.simLogSink.add(String.format(Locale.ROOT, "[t=%5.1fs] %s", Math.max(0f, now), line));
        }
        if (group.side == 0 && !group.isAlliedAi) {
            notifyPlayer(engine, "Friendly OTC command channel restored.", true);
        }
    }

    /**
     * Returns true if the destroyed flagship carried the fleet's overall commander (120s blackout)
     * rather than a commander of lower or no station (30s blackout).
     */
    public static boolean isFleetOverallCommanderAboard(
            CombatEngineAPI engine,
            BattleGroupState group,
            ShipAPI flagship) {
        if (flagship == null) return false;
        if (group != null && group.formationSpawnHijackedShipIds.contains("__overall_cmdr_lost")) {
            return false;
        }
        if (group != null && group.otcManuallyAssigned) {
            boolean isOriginalFlagship = flagship.getFleetMember() != null && flagship.getFleetMember().isFlagship();
            if (!isOriginalFlagship) {
                return false;
            }
        }
        PersonAPI cap = flagship.getCaptain();
        boolean hasNonDefaultCaptain = (cap != null && !cap.isDefault());
        boolean isPlayerPiloted = (engine != null && group != null && group.side == 0 && !group.isAlliedAi
                && flagship == engine.getPlayerShip());
        if (!hasNonDefaultCaptain && !isPlayerPiloted) {
            return false;
        }
        try {
            FleetMemberAPI member = flagship.getFleetMember();
            if (member != null && member.isFlagship()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        if (isPlayerPiloted) {
            return true;
        }
        try {
            if (engine != null && group != null && engine.getFleetManager(group.side) != null) {
                CombatFleetManagerAPI fm = engine.getFleetManager(group.side);
                PersonAPI fleetCmdr = (group.side == 0) ? fm.getFleetCommanderPreferPlayer() : fm.getFleetCommander();
                if (fleetCmdr != null && !fleetCmdr.isDefault() && cap == fleetCmdr) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return hasNonDefaultCaptain;
    }

    private static void notifyPlayer(CombatEngineAPI engine, String text, boolean positive) {
        try {
            CombatEngineAPI eng = (engine != null) ? engine : Global.getCombatEngine();
            if (eng == null) return;
            CombatUIAPI ui = eng.getCombatUI();
            if (ui == null) return;
            Color color = positive
                    ? new Color(110, 230, 145)
                    : new Color(255, 100, 100);
            try {
                if (Global.getSettings() != null) {
                    color = positive ? Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor();
                }
            } catch (Throwable ignored) {
            }
            ui.addMessage(1, color, text);
        } catch (Throwable ignored) {
        }
    }
}
