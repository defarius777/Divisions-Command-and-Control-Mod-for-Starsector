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
    public static final float OTC_NOT_DEPLOYED_MAX_CP_MULT = 0.25f;
    public static final float OTC_NOT_DEPLOYED_CP_RATE_MULT = 0.50f;
    public static final String OTC_NOT_DEPLOYED_MAX_CP_MOD_ID = "divisionai_otc_not_deployed_max_cp";
    public static final String OTC_NOT_DEPLOYED_CP_RATE_MOD_ID = "divisionai_otc_not_deployed_cp_rate";
    public static final String OTC_PRE_DEPLOY_WARNING_TEXT =
            "OTC NOT DEPLOYED (-75% Max CP, -50% Regen)";
    public static final String OTC_PANEL_NOT_DEPLOYED_WARNING_TEXT =
            "OTC NOT DEPLOYED (-75% Max CP, -50% Regen)";
    private static final Object OTC_NOT_DEPLOYED_HUD_STATUS_KEY = new Object();

    /** Tracks the player's explicitly chosen OTC FleetMemberAPI id across pre-battle and mid-battle UI refreshes. */
    private static String preferredPlayerOtcMemberId = null;
    /** Tracks the player's auto-selected OTC FleetMemberAPI id so it stays sticky across pre-battle and into battle. */
    private static String autoSelectedPlayerOtcMemberId = null;
    private static boolean explicitPlayerOtcChoiceMade = false;
    private static boolean playerShipStickyOtcActive = false;
    /** True once the player's OTC becomes an Alpha Core during battle (deliberate swap, Convergence, or deployed start). */
    private static boolean playerAlphaCoreLockedInBattle = false;
    /** True when the player's OTC ship was destroyed and the game is paused awaiting replacement OTC selection. */
    private static boolean emergencyPlayerOtcSelectionPending = false;
    /** True when the UI should automatically pop open the OTC Selection Window on the current frame. */
    private static boolean emergencyModalAutoOpenRequested = false;

    /** Snapshot of an eligible OTC candidate ship/member. */
    public static class OtcCandidate {
        public final FleetMemberAPI member;
        public final ShipAPI ship;
        public final String memberId;
        public final String shipId;
        public final String shipName;
        public final String hullName;
        public final String commanderName;
        public final String personalityId;
        public final String personalityDisplay;
        public final String portraitSpritePath;
        public final String assignmentLabel;
        public final String divisionId;
        public final String divisionTitle;
        public final PermanentDivisionManager.DivisionFormation divisionFormation;
        public final boolean inDivision;
        public final boolean isFlagshipDivision;
        public final boolean alphaCore;
        public final boolean divisionLeader;
        public final boolean subordinateInDivision;
        public final boolean independent;

        public OtcCandidate(
                FleetMemberAPI member,
                ShipAPI ship,
                String assignmentLabel,
                boolean divisionLeader,
                boolean alphaCoreOverride) {
            this(member, ship, assignmentLabel, divisionLeader, false, alphaCoreOverride,
                    null, "Unassigned", null, false, false);
        }

        public OtcCandidate(
                FleetMemberAPI member,
                ShipAPI ship,
                String assignmentLabel,
                boolean divisionLeader,
                boolean subordinateInDivision,
                boolean alphaCoreOverride,
                String divisionId,
                String divisionTitle,
                PermanentDivisionManager.DivisionFormation divisionFormation,
                boolean inDivision,
                boolean isFlagshipDivision) {
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
            if (this.alphaCore) {
                this.personalityId = "fearless";
                this.personalityDisplay = "Alpha Core";
            } else {
                String pId = "steady";
                String pDisp = "Steady";
                if (captain != null && captain.getPersonalityAPI() != null) {
                    if (captain.getPersonalityAPI().getId() != null && !captain.getPersonalityAPI().getId().trim().isEmpty()) {
                        pId = captain.getPersonalityAPI().getId().trim().toLowerCase(Locale.ROOT);
                    }
                    if (captain.getPersonalityAPI().getDisplayName() != null
                            && !captain.getPersonalityAPI().getDisplayName().trim().isEmpty()) {
                        pDisp = captain.getPersonalityAPI().getDisplayName().trim();
                    } else if (!pId.isEmpty()) {
                        pDisp = Character.toUpperCase(pId.charAt(0)) + pId.substring(1);
                    }
                }
                this.personalityId = pId;
                this.personalityDisplay = pDisp;
            }
            if (this.alphaCore) {
                this.portraitSpritePath = "graphics/portraits/portrait_ai2b.png";
            } else if (captain != null && captain.getPortraitSprite() != null && !captain.getPortraitSprite().isEmpty()) {
                this.portraitSpritePath = captain.getPortraitSprite();
            } else {
                this.portraitSpritePath = "graphics/portraits/portrait_generic.png";
            }
            this.assignmentLabel = assignmentLabel;
            this.divisionLeader = divisionLeader;
            this.subordinateInDivision = subordinateInDivision;
            this.independent = !inDivision;
            this.divisionId = divisionId;
            this.divisionTitle = (divisionTitle != null && !divisionTitle.isEmpty()) ? divisionTitle : "Unassigned";
            this.divisionFormation = divisionFormation;
            this.inDivision = inDivision;
            this.isFlagshipDivision = isFlagshipDivision;
        }
    }

    public static void resetForNewBattle() {
        preferredPlayerOtcMemberId = null;
        autoSelectedPlayerOtcMemberId = null;
        explicitPlayerOtcChoiceMade = false;
        playerShipStickyOtcActive = false;
        playerAlphaCoreLockedInBattle = false;
        emergencyPlayerOtcSelectionPending = false;
        emergencyModalAutoOpenRequested = false;
    }

    public static String getAutoSelectedPlayerOtcMemberId() {
        return autoSelectedPlayerOtcMemberId;
    }

    public static boolean hasAutoOrExplicitPlayerOtcSelection(BattleGroupState playerGroup) {
        return isExplicitPlayerOtcChoiceMade(playerGroup)
                || playerShipStickyOtcActive
                || (autoSelectedPlayerOtcMemberId != null && !autoSelectedPlayerOtcMemberId.isEmpty());
    }

    public static boolean isExplicitPlayerOtcChoiceMade(BattleGroupState playerGroup) {
        return explicitPlayerOtcChoiceMade || (playerGroup != null && playerGroup.otcManuallyAssigned);
    }

    public static void setExplicitPlayerOtcChoiceMade(BattleGroupState playerGroup, boolean explicit) {
        explicitPlayerOtcChoiceMade = explicit;
        if (playerGroup != null) {
            playerGroup.otcManuallyAssigned = explicit;
        }
    }

    public static boolean isPlayerShipStickyOtcActive() {
        return playerShipStickyOtcActive;
    }

    public static void setPlayerShipStickyOtcActive(boolean active) {
        playerShipStickyOtcActive = active;
    }

    public static void setAutoPreDeployPlayerOtcMemberId(BattleGroupState playerGroup, String id) {
        preferredPlayerOtcMemberId = id;
        if (playerGroup != null) {
            playerGroup.preferredOtcShipId = id;
        }
    }

    public static boolean isAlphaCoreOtcLockedInBattle(BattleGroupState playerGroup) {
        return playerAlphaCoreLockedInBattle || (playerGroup != null && playerGroup.alphaCoreOtcLockedInBattle);
    }

    public static void lockPlayerAlphaCoreOtcInBattle(BattleGroupState playerGroup) {
        playerAlphaCoreLockedInBattle = true;
        if (playerGroup != null) {
            playerGroup.alphaCoreOtcLockedInBattle = true;
        }
    }

    public static void clearPlayerAlphaCoreOtcLockInBattle(BattleGroupState playerGroup) {
        playerAlphaCoreLockedInBattle = false;
        if (playerGroup != null) {
            playerGroup.alphaCoreOtcLockedInBattle = false;
        }
    }

    public static boolean isEmergencyPlayerOtcSelectionPending(BattleGroupState playerGroup) {
        return emergencyPlayerOtcSelectionPending
                || (playerGroup != null && playerGroup.emergencyOtcSelectionPending);
    }

    public static void triggerEmergencyPlayerOtcSelection(CombatEngineAPI engine, BattleGroupState playerGroup) {
        emergencyPlayerOtcSelectionPending = true;
        emergencyModalAutoOpenRequested = true;
        if (playerGroup != null) {
            playerGroup.emergencyOtcSelectionPending = true;
        }
        if (engine != null) {
            try {
                engine.setPaused(true);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void clearEmergencyPlayerOtcSelection(BattleGroupState playerGroup) {
        emergencyPlayerOtcSelectionPending = false;
        emergencyModalAutoOpenRequested = false;
        if (playerGroup != null) {
            playerGroup.emergencyOtcSelectionPending = false;
        }
    }

    public static boolean consumeEmergencyModalAutoOpenRequest() {
        if (!emergencyModalAutoOpenRequested) return false;
        emergencyModalAutoOpenRequested = false;
        return true;
    }

    public static boolean isEmergencyModalAutoOpenRequested() {
        return emergencyModalAutoOpenRequested;
    }

    public static String getPreferredPlayerOtcMemberId(BattleGroupState playerGroup) {
        if (playerGroup != null && playerGroup.preferredOtcShipId != null && !playerGroup.preferredOtcShipId.isEmpty()) {
            return playerGroup.preferredOtcShipId;
        }
        if (preferredPlayerOtcMemberId != null && !preferredPlayerOtcMemberId.isEmpty()) {
            return preferredPlayerOtcMemberId;
        }
        return autoSelectedPlayerOtcMemberId;
    }

    public static void setPreferredPlayerOtcMemberId(BattleGroupState playerGroup, String id) {
        preferredPlayerOtcMemberId = id;
        boolean hasId = (id != null && !id.isEmpty());
        explicitPlayerOtcChoiceMade = hasId;
        if (playerGroup != null) {
            playerGroup.preferredOtcShipId = id;
            playerGroup.otcManuallyAssigned = hasId;
        }
    }

    public static boolean isPlayerCharacterMember(FleetMemberAPI m) {
        if (m == null) return false;
        if (m.isFlagship()) return true;
        PersonAPI cap = m.getCaptain();
        if (cap != null) {
            try {
                if (cap.isPlayer()) return true;
            } catch (Throwable ignored) {
            }
            try {
                if (Global.getSector() != null && Global.getSector().getPlayerPerson() == cap) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static FleetMemberAPI findPlayerCharacterMember(List<FleetMemberAPI> members) {
        if (members == null) return null;
        for (FleetMemberAPI m : members) {
            if (isPlayerCharacterMember(m)) {
                return m;
            }
        }
        return null;
    }

    private static boolean isCivilianMember(FleetMemberAPI m) {
        if (m == null) return false;
        try {
            if (m.isCivilian()) return true;
        } catch (Throwable ignored) {
        }
        try {
            if (m.getHullSpec() != null && m.getHullSpec().getHints() != null
                    && m.getHullSpec().getHints().contains(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.CIVILIAN)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static float scoreMemberForLargestShipOtc(FleetMemberAPI m) {
        if (m == null) return -1e9f;
        float score = 0f;
        ShipAPI.HullSize hs = (m.getHullSpec() != null) ? m.getHullSpec().getHullSize() : null;
        if (hs == ShipAPI.HullSize.CAPITAL_SHIP) score += 40000f;
        else if (hs == ShipAPI.HullSize.CRUISER) score += 30000f;
        else if (hs == ShipAPI.HullSize.DESTROYER) score += 20000f;
        else if (hs == ShipAPI.HullSize.FRIGATE) score += 10000f;

        score += Math.max(0f, m.getDeploymentPointsCost()) * 100f;
        if (isCivilianMember(m)) {
            score -= 50000f;
        }
        PersonAPI cap = m.getCaptain();
        if (cap != null && !cap.isDefault() && cap.getStats() != null) {
            score += Math.max(1, cap.getStats().getLevel()) * 5f;
        }
        return score;
    }

    public static FleetMemberAPI selectLargestShipCommanderMember(List<FleetMemberAPI> selectedMembers) {
        if (selectedMembers == null || selectedMembers.isEmpty()) return null;
        boolean anyNamedCommander = false;
        for (FleetMemberAPI m : selectedMembers) {
            if (m == null || m.isFighterWing()) continue;
            PersonAPI cap = m.getCaptain();
            boolean hasCmdr = (cap != null && !cap.isDefault())
                    || DivisionDeploymentDialogOrganizer.isFlagshipAlphaCoreEquipped(null, m, null)
                    || isPlayerCharacterMember(m);
            if (hasCmdr) {
                anyNamedCommander = true;
                break;
            }
        }
        FleetMemberAPI best = null;
        float bestScore = -1e9f;
        for (FleetMemberAPI m : selectedMembers) {
            if (m == null || m.isFighterWing()) continue;
            if (anyNamedCommander) {
                PersonAPI cap = m.getCaptain();
                boolean hasCmdr = (cap != null && !cap.isDefault())
                        || DivisionDeploymentDialogOrganizer.isFlagshipAlphaCoreEquipped(null, m, null)
                        || isPlayerCharacterMember(m);
                if (!hasCmdr) continue;
            }
            float sc = scoreMemberForLargestShipOtc(m);
            if (sc > bestScore || best == null) {
                bestScore = sc;
                best = m;
            }
        }
        return best;
    }

    /**
     * Resolves the active OTC FleetMemberAPI in the pre-battle deployment screen:
     *  - Defaults to the player ship when no divisions/ships are selected yet.
     *  - As the player clicks divisions/ships to deploy (without the player ship), automatically changes
     *    to the commander of the largest selected ship.
     *  - Once the player ship is chosen to deploy, stickily remains the player character until the player
     *    explicitly chooses another OTC in pre-deployment.
     *  - Once the player explicitly chooses an OTC in pre-deployment, that explicit choice is sticky.
     */
    public static FleetMemberAPI resolvePreBattlePlayerOtcMember(
            BattleGroupState playerGroup,
            List<FleetMemberAPI> allMembers,
            List<FleetMemberAPI> selectedForDeploy) {
        if (allMembers == null || allMembers.isEmpty()) return null;

        FleetMemberAPI playerShip = findPlayerCharacterMember(allMembers);
        if (playerShip != null && selectedForDeploy != null) {
            String pId = playerShip.getId();
            for (FleetMemberAPI sm : selectedForDeploy) {
                if (sm == playerShip || (sm != null && pId != null && pId.equals(sm.getId()))) {
                    playerShipStickyOtcActive = true;
                    break;
                }
            }
        }

        // 1. Explicit manual choice in the OTC Selection Window is sticky above all else
        if (isExplicitPlayerOtcChoiceMade(playerGroup)) {
            String prefId = getPreferredPlayerOtcMemberId(playerGroup);
            if (prefId != null && !prefId.isEmpty()) {
                for (FleetMemberAPI m : allMembers) {
                    if (m != null && prefId.equals(m.getId())) {
                        return m;
                    }
                }
            }
        }

        // 2. Once the player ship is chosen to deploy, stickily remain on the player character
        if (playerShipStickyOtcActive && playerShip != null) {
            autoSelectedPlayerOtcMemberId = playerShip.getId();
            setAutoPreDeployPlayerOtcMemberId(playerGroup, playerShip.getId());
            return playerShip;
        }

        // 3. As the player clicks divisions/ships to deploy, automatically switch to the commander of the largest ship
        if (selectedForDeploy != null && !selectedForDeploy.isEmpty()) {
            FleetMemberAPI largestSelected = selectLargestShipCommanderMember(selectedForDeploy);
            if (largestSelected != null) {
                autoSelectedPlayerOtcMemberId = largestSelected.getId();
                setAutoPreDeployPlayerOtcMemberId(playerGroup, largestSelected.getId());
                return largestSelected;
            }
        }

        // 3b. Keep the auto-selected OTC sticky even when fdd.getSelected() is cleared on clicking "Deploy"
        //     or when ships are deselected in the pre-battle deployment window
        if (autoSelectedPlayerOtcMemberId != null && !autoSelectedPlayerOtcMemberId.isEmpty()) {
            for (FleetMemberAPI m : allMembers) {
                if (m != null && autoSelectedPlayerOtcMemberId.equals(m.getId())) {
                    setAutoPreDeployPlayerOtcMemberId(playerGroup, m.getId());
                    return m;
                }
            }
        }

        // 4. Default before any divisions/ships are clicked to deploy: the player character's ship
        if (playerShip != null) {
            setAutoPreDeployPlayerOtcMemberId(playerGroup, playerShip.getId());
            return playerShip;
        }

        FleetMemberAPI fallback = selectLargestShipCommanderMember(allMembers);
        if (fallback != null) {
            setAutoPreDeployPlayerOtcMemberId(playerGroup, fallback.getId());
        }
        return fallback;
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

    /** True if the player fleet's active OTC is an Alpha Core (for visual title/portrait display). */
    public static boolean isActivePlayerOtcAlphaCore(
            CombatEngineAPI engine,
            FleetMemberAPI currentFlagshipMember,
            BattleGroupState playerGroup) {
        return DivisionDeploymentDialogOrganizer.isFlagshipAlphaCoreEquipped(engine, currentFlagshipMember, playerGroup);
    }

    /**
     * Feature A: Before battle (preBattle == true), the player may freely change OTC, picking and un-picking
     * any Alpha Core as they wish. During battle (preBattle == false), once the OTC is an Alpha Core (by
     * deployed start, deliberate mid-battle swap, or Convergence takeover), OTC changes are locked.
     */
    public static boolean isPlayerOtcLockedByAlphaCoreInBattle(
            CombatEngineAPI engine,
            FleetMemberAPI currentFlagshipMember,
            BattleGroupState playerGroup,
            boolean preBattle) {
        if (preBattle) {
            return false;
        }
        if (isAlphaCoreOtcLockedInBattle(playerGroup)) {
            return true;
        }
        return isActivePlayerOtcAlphaCore(engine, currentFlagshipMember, playerGroup);
    }

    /**
     * Collects all eligible player OTC candidates from the Deployment Screen / battlefield, organized
     * in division-row order (each non-empty Permanent Division followed by Unassigned vessels):
     *  1. Division leader commanders (flagships of each division),
     *  2. Subordinate vessel commanders inside each division (non-flagship ships with a commander or Alpha Core),
     *  3. Independent / unassigned vessel commanders (and fleet flagship / Alpha Core vessels).
     */
    public static List<OtcCandidate> collectPlayerOtcCandidates(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            List<FleetMemberAPI> deployedMembers,
            List<FleetMemberAPI> reserveMembers) {

        List<FleetMemberAPI> allMembers = new ArrayList<>();
        java.util.Map<String, FleetMemberAPI> byId = new java.util.LinkedHashMap<>();
        if (deployedMembers != null) {
            for (FleetMemberAPI m : deployedMembers) {
                if (m != null && m.getId() != null && !byId.containsKey(m.getId())) {
                    byId.put(m.getId(), m);
                    allMembers.add(m);
                }
            }
        }
        if (reserveMembers != null) {
            for (FleetMemberAPI m : reserveMembers) {
                if (m != null && m.getId() != null && !byId.containsKey(m.getId())) {
                    byId.put(m.getId(), m);
                    allMembers.add(m);
                }
            }
        }
        if (allMembers.isEmpty() && engine != null && engine.getShips() != null) {
            for (ShipAPI s : engine.getShips()) {
                if (s == null || s.getOwner() != 0 || s.isAlly() || !DivisionAutoFormer.isEligibleCombatHull(s)) continue;
                FleetMemberAPI m = s.getFleetMember();
                if (m != null && m.getId() != null && !byId.containsKey(m.getId())) {
                    byId.put(m.getId(), m);
                    allMembers.add(m);
                }
            }
        }

        List<PermanentDivisionSpec> specs = PermanentDivisionManager.getNonEmptyPermanentDivisions();
        PermanentDivisionManager.FleetDoctrineConfig docCfg = PermanentDivisionManager.getFleetDoctrine();
        DivisionNomenclature.NomenclatureCategory playerNomCat = DivisionNomenclature.getPlayerNomenclatureCategory();

        Set<String> activeCombatDivisionLeaders = new HashSet<>();
        if (playerGroup != null) {
            for (DivisionState d : playerGroup.divisions) {
                if (d != null && d.flagship != null && d.flagship.getFleetMember() != null
                        && d.flagship.getFleetMember().getId() != null) {
                    activeCombatDivisionLeaders.add(d.flagship.getFleetMember().getId());
                }
            }
        }

        List<OtcCandidate> out = new ArrayList<>();
        Set<String> processedMemberIds = new HashSet<>();
        int divSeq = 1;

        for (PermanentDivisionSpec spec : specs) {
            if (spec == null || spec.memberIds == null || spec.memberIds.isEmpty()) continue;
            String leaderId = (spec.flagshipMemberId != null && spec.memberIds.contains(spec.flagshipMemberId))
                    ? spec.flagshipMemberId
                    : spec.memberIds.get(0);

            List<FleetMemberAPI> divMembersOrdered = new ArrayList<>();
            if (leaderId != null && byId.containsKey(leaderId)) {
                divMembersOrdered.add(byId.get(leaderId));
            }
            for (String mid : spec.memberIds) {
                FleetMemberAPI m = byId.get(mid);
                if (m != null && !divMembersOrdered.contains(m)) {
                    divMembersOrdered.add(m);
                }
            }
            if (divMembersOrdered.isEmpty()) continue;

            boolean isFlagDiv = false;
            for (FleetMemberAPI dm : divMembersOrdered) {
                if (dm != null && dm.isFlagship()) {
                    isFlagDiv = true;
                    break;
                }
            }
            PermanentDivisionManager.DivisionFormation divFm =
                    PermanentDivisionManager.resolveEffectiveFormation(spec, docCfg);
            String rowTitle = DivisionNomenclature.resolveDynamicDivisionDisplayName(
                    playerNomCat, spec.archetype, divSeq++, spec.name);

            for (FleetMemberAPI m : divMembersOrdered) {
                if (m == null || m.isFighterWing() || m.getId() == null) continue;
                processedMemberIds.add(m.getId());
                ShipAPI ship = findLiveShipForMember(engine, m.getId());
                if (ship != null && (!ship.isAlive() || ship.isHulk())) continue;

                boolean isDivLeader = m.getId().equals(leaderId) || activeCombatDivisionLeaders.contains(m.getId());
                boolean isAlpha = DivisionDeploymentDialogOrganizer.isFlagshipAlphaCoreEquipped(null, m, null)
                        || BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(ship);
                PersonAPI cap = m.getCaptain();
                boolean hasOfficerOrPlayer = (cap != null && !cap.isDefault()) || isPlayerCharacterMember(m) || isAlpha;

                // Feature A: Empty division leaders (without an Officer, AI Core, or Player) remain in the
                // succession hierarchy, but are not listed as selectable options in the player's OTC panel.
                if (hasOfficerOrPlayer) {
                    String label = isAlpha
                            ? "Alpha Core Vessel"
                            : (isDivLeader ? "Division Leader" : "Division Subordinate");
                    out.add(new OtcCandidate(
                            m, ship, label, isDivLeader, !isDivLeader, isAlpha,
                            spec.id, rowTitle, divFm, true, isFlagDiv));
                }
            }
        }

        for (FleetMemberAPI m : allMembers) {
            if (m == null || m.isFighterWing() || m.getId() == null) continue;
            if (processedMemberIds.contains(m.getId())) continue;
            ShipAPI ship = findLiveShipForMember(engine, m.getId());
            if (ship != null && (!ship.isAlive() || ship.isHulk())) continue;

            boolean isDivLeader = activeCombatDivisionLeaders.contains(m.getId());
            boolean isAlpha = DivisionDeploymentDialogOrganizer.isFlagshipAlphaCoreEquipped(null, m, null)
                    || BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(ship);
            PersonAPI cap = m.getCaptain();
            boolean hasOfficerOrPlayer = (cap != null && !cap.isDefault()) || isPlayerCharacterMember(m) || isAlpha;

            if (hasOfficerOrPlayer) {
                String label = isAlpha
                        ? "Alpha Core Vessel"
                        : (isDivLeader ? "Division Leader" : "Independent Commander");
                out.add(new OtcCandidate(
                        m, ship, label, isDivLeader, false, isAlpha,
                        "UNASSIGNED", "Unassigned", null, false, m.isFlagship()));
            }
        }

        return out;
    }

    /**
     * Selects a specific OTC candidate by FleetMemberAPI id (invoked when clicking a commander card in the OTC Selection Window).
     */
    public static boolean selectPlayerOtcByMemberId(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            List<FleetMemberAPI> deployedMembers,
            List<FleetMemberAPI> reserveMembers,
            FleetMemberAPI currentFlagshipMember,
            String targetMemberId,
            boolean preBattle) {
        if (targetMemberId == null || targetMemberId.isEmpty()) return false;
        if (isPlayerOtcLockedByAlphaCoreInBattle(engine, currentFlagshipMember, playerGroup, preBattle)) {
            notifyPlayer(engine, "OTC cannot be changed during battle while an Alpha Core holds command.", false);
            return false;
        }
        float now = (engine != null) ? engine.getTotalElapsedTime(false) : 0f;
        List<OtcCandidate> candidates = collectPlayerOtcCandidates(engine, playerGroup, deployedMembers, reserveMembers);
        for (OtcCandidate c : candidates) {
            if (c != null && (targetMemberId.equals(c.memberId) || targetMemberId.equals(c.shipId))) {
                return applyPlayerOtcCandidate(engine, playerGroup, c, preBattle, now);
            }
        }
        return false;
    }

    static ShipAPI findLiveShipForMember(CombatEngineAPI engine, String memberId) {
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

        if (isPlayerOtcLockedByAlphaCoreInBattle(engine, currentFlagshipMember, playerGroup, preBattle)) {
            notifyPlayer(engine, "OTC cannot be changed during battle while an Alpha Core holds command.", false);
            return false;
        }
        float now = (engine != null) ? engine.getTotalElapsedTime(false) : 0f;
        boolean emergency = isEmergencyPlayerOtcSelectionPending(playerGroup);
        if (!preBattle && !emergency && isOtcDisabled(playerGroup, now)) {
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
     * Applies the chosen OTC candidate, charging 75% of max CP if mid-battle and not an Alpha Core Convergence transfer
     * or emergency post-destruction succession.
     */
    public static boolean applyPlayerOtcCandidate(
            CombatEngineAPI engine,
            BattleGroupState playerGroup,
            OtcCandidate candidate,
            boolean preBattle,
            float now) {
        if (candidate == null) return false;
        if (!preBattle && isAlphaCoreOtcLockedInBattle(playerGroup)) {
            notifyPlayer(engine, "OTC cannot be changed during battle once an Alpha Core holds command.", false);
            return false;
        }
        boolean emergency = isEmergencyPlayerOtcSelectionPending(playerGroup);
        if (!preBattle && !emergency && playerGroup != null && isOtcDisabled(playerGroup, now) && !candidate.alphaCore) {
            notifyPlayer(engine, String.format(Locale.ROOT,
                    "OTC disabled for %.0fs following flagship destruction.",
                    getOtcDisabledSecondsRemaining(playerGroup, now)), false);
            return false;
        }

        int cpCost = 0;
        if (!preBattle && !emergency && !candidate.alphaCore) {
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

        String chosenId = candidate.memberId != null ? candidate.memberId : candidate.shipId;
        setPreferredPlayerOtcMemberId(playerGroup, chosenId);
        clearEmergencyPlayerOtcSelection(playerGroup);
        PersonAPI person = resolveCandidatePerson(candidate.member, candidate.ship);
        ShipAPI resolvedShip = candidate.ship != null ? candidate.ship : findLiveShipForMember(engine, chosenId);
        if (playerGroup != null) {
            playerGroup.otc = new CommanderProfile(person, candidate.commanderName);
            playerGroup.lastOtcReassignmentTime = now;
            if (resolvedShip != null) {
                playerGroup.activeFleetFlagship = resolvedShip;
                playerGroup.designatedSuccessorFlagship = resolvedShip;
            }
            if (candidate.alphaCore) {
                playerGroup.useConvergence = true;
                playerGroup.otcDisabledUntilTime = -100f;
                playerGroup.otcDisabledWarningActive = false;
                if (!preBattle) {
                    lockPlayerAlphaCoreOtcInBattle(playerGroup);
                    if (resolvedShip != null) {
                        BattlefieldFirepowerEvaluator.setPlayerConvergenceFlagshipOverride(resolvedShip);
                    }
                }
            } else {
                clearPlayerAlphaCoreOtcLockInBattle(playerGroup);
                BattlefieldFirepowerEvaluator.setPlayerConvergenceFlagshipOverride(null);
                BattlefieldFirepowerEvaluator.markInitialPlayerOtcAlphaCore(false);
                if (playerGroup.forceConvergenceOverride == null) {
                    playerGroup.useConvergence = false;
                }
            }
        } else {
            if (candidate.alphaCore && !preBattle) {
                lockPlayerAlphaCoreOtcInBattle(null);
            } else if (!candidate.alphaCore) {
                clearPlayerAlphaCoreOtcLockInBattle(null);
                BattlefieldFirepowerEvaluator.setPlayerConvergenceFlagshipOverride(null);
                BattlefieldFirepowerEvaluator.markInitialPlayerOtcAlphaCore(false);
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

    public static int getPlayerUnpenalizedMaxCommandPoints(CombatEngineAPI engine) {
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

    public static int computePenalizedMaxCommandPoints(int unpenalizedMaxCp) {
        if (unpenalizedMaxCp <= 0) return 0;
        return Math.max(1, Math.round(unpenalizedMaxCp * OTC_NOT_DEPLOYED_MAX_CP_MULT));
    }

    public static int getPlayerMaxCommandPoints(CombatEngineAPI engine) {
        int unpenalized = getPlayerUnpenalizedMaxCommandPoints(engine);
        if (unpenalized <= 0) return 0;
        CombatTaskManagerAPI tm = getPlayerTaskManager(engine);
        if (tm != null) {
            try {
                MutableStat stat = tm.getCommandPointsStat();
                if (stat != null && stat.getFlatStatMod(OTC_NOT_DEPLOYED_MAX_CP_MOD_ID) != null) {
                    return computePenalizedMaxCommandPoints(unpenalized);
                }
            } catch (Throwable ignored) {
            }
        }
        return unpenalized;
    }

    public static float getPlayerEffectiveCpRateMult(CombatEngineAPI engine) {
        CombatTaskManagerAPI tm = getPlayerTaskManager(engine);
        if (tm == null) return 1.0f;
        try {
            float mult = tm.getCPRateMult();
            if (mult > 0f) return mult;
            MutableStat rateMod = tm.getCPRateModifier();
            if (rateMod != null) {
                return Math.max(0f, 1.0f + rateMod.getModifiedValue());
            }
        } catch (Throwable ignored) {
        }
        return 1.0f;
    }

    /**
     * Feature E: Returns true if the player side's designated OTC ship is currently deployed and alive in battle.
     */
    public static boolean isPlayerOtcDeployedInBattle(CombatEngineAPI engine, BattleGroupState playerGroup) {
        String preferredId = getPreferredPlayerOtcMemberId(playerGroup);
        if (preferredId != null && !preferredId.isEmpty()) {
            ShipAPI livePref = findLiveShipForMember(engine, preferredId);
            if (livePref != null
                    && livePref.isAlive()
                    && !livePref.isHulk()
                    && !isShipRetreatedFromBattle(engine, playerGroup, livePref)) {
                return true;
            }
            if (playerGroup != null && playerGroup.activeFleetFlagship != null) {
                ShipAPI af = playerGroup.activeFleetFlagship;
                boolean matches = preferredId.equals(af.getId())
                        || (af.getFleetMember() != null && preferredId.equals(af.getFleetMember().getId()));
                if (matches && af.isAlive() && !af.isHulk() && !isShipRetreatedFromBattle(engine, playerGroup, af)) {
                    return true;
                }
            }
            return false;
        }

        // If no explicit preferred OTC id is set, check if a campaign flagship is sitting undeployed in reserves
        try {
            if (engine != null && engine.getFleetManager(0) != null) {
                CombatFleetManagerAPI fm = engine.getFleetManager(0);
                List<FleetMemberAPI> reserves = fm.getReservesCopy();
                if (reserves != null) {
                    for (FleetMemberAPI rm : reserves) {
                        if (rm != null && !rm.isAlly() && rm.isFlagship()) {
                            ShipAPI liveFlag = findLiveShipForMember(engine, rm.getId());
                            if (liveFlag == null || !liveFlag.isAlive() || liveFlag.isHulk()) {
                                return false;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (playerGroup != null && playerGroup.activeFleetFlagship != null) {
            ShipAPI af = playerGroup.activeFleetFlagship;
            return af.isAlive() && !af.isHulk() && !isShipRetreatedFromBattle(engine, playerGroup, af);
        }

        if (engine != null && engine.getShips() != null) {
            for (ShipAPI s : engine.getShips()) {
                if (s == null || s.getOwner() != 0 || s.isAlly() || s.isFighter() || s.isStationModule()) continue;
                if (!s.isAlive() || s.isHulk() || isShipRetreatedFromBattle(engine, playerGroup, s)) continue;
                if (s.getFleetMember() != null && s.getFleetMember().isFlagship()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Feature E: Applies -75% max command points and -50% command point generation speed to the player side
     * whenever the player's OTC is not deployed in battle, and restores normal stats once the OTC is deployed.
     */
    public static void updatePlayerOtcNotDeployedCommandPointPenalty(
            CombatEngineAPI engine,
            BattleGroupState playerGroup) {
        if (engine == null) return;
        boolean otcDeployed = isPlayerOtcDeployedInBattle(engine, playerGroup);
        boolean penaltyActive = !otcDeployed;
        if (playerGroup != null) {
            playerGroup.otcNotDeployedPenaltyActive = penaltyActive;
        }

        CombatTaskManagerAPI tm = getPlayerTaskManager(engine);
        if (tm != null) {
            try {
                MutableStat rateStat = tm.getCPRateModifier();
                if (rateStat != null) {
                    rateStat.unmodify(OTC_NOT_DEPLOYED_CP_RATE_MOD_ID);
                    if (penaltyActive) {
                        float baseRate = tm.getCPRateMult();
                        if (baseRate <= 0f) {
                            baseRate = Math.max(0.1f, 1.0f + rateStat.getModifiedValue());
                        }
                        float ratePenalty = -baseRate * (1.0f - OTC_NOT_DEPLOYED_CP_RATE_MULT);
                        rateStat.modifyFlat(OTC_NOT_DEPLOYED_CP_RATE_MOD_ID, ratePenalty);
                    }
                }
            } catch (Throwable ignored) {
            }

            try {
                MutableStat cpStat = tm.getCommandPointsStat();
                if (cpStat != null) {
                    if (penaltyActive) {
                        int unpenalizedMax = getPlayerUnpenalizedMaxCommandPoints(engine);
                        if (unpenalizedMax > 0) {
                            int penalizedMax = computePenalizedMaxCommandPoints(unpenalizedMax);
                            int baseReduction = Math.max(0, unpenalizedMax - penalizedMax);
                            MutableStat.StatMod existing = cpStat.getFlatStatMod(OTC_NOT_DEPLOYED_MAX_CP_MOD_ID);
                            float existingVal = (existing != null) ? existing.value : 0f;
                            int unpenalizedCurrent = Math.round(cpStat.getModifiedValue() - existingVal);
                            int neededReduction = Math.max(baseReduction, unpenalizedCurrent - penalizedMax);
                            cpStat.modifyFlat(OTC_NOT_DEPLOYED_MAX_CP_MOD_ID, -neededReduction);
                        }
                    } else {
                        cpStat.unmodify(OTC_NOT_DEPLOYED_MAX_CP_MOD_ID);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (penaltyActive) {
            try {
                String icon = Global.getSettings().getSpriteName("ui", "icon_tactical_coordinated_maneuvers");
                engine.maintainStatusForPlayerShip(
                        OTC_NOT_DEPLOYED_HUD_STATUS_KEY,
                        icon,
                        "OTC NOT DEPLOYED",
                        "-75% max CP, -50% CP regen speed",
                        true);
            } catch (Throwable ignored) {
            }
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
        checkDivisionFlagshipsDestroyed(engine, playerGroup, now);
        checkDivisionFlagshipsDestroyed(engine, alliedGroup, now);
        checkDivisionFlagshipsDestroyed(engine, enemyGroup, now);
        // Feature E: Enforce -75% Max CP and -50% CP Generation Speed if the player's OTC is not deployed in battle
        boolean anyPlayerSideShipInPlay = false;
        if (engine.getShips() != null) {
            for (ShipAPI s : engine.getShips()) {
                if (s != null && s.getOwner() == 0 && !s.isAlly() && !s.isFighter() && !s.isStationModule() && s.isAlive()) {
                    anyPlayerSideShipInPlay = true;
                    break;
                }
            }
        }
        if (anyPlayerSideShipInPlay || (playerGroup != null && playerGroup.initialDeploymentDone)) {
            updatePlayerOtcNotDeployedCommandPointPenalty(engine, playerGroup);
        }
    }

    public static void checkGroupFlagshipLoss(
            CombatEngineAPI engine,
            BattleGroupState group,
            float now,
            boolean announce) {
        if (group == null || group.isPassiveOpposingView) return;
        ShipAPI flagship = group.activeFleetFlagship;
        if (flagship == null) return;
        String flagshipId = flagship.getId();
        if (flagshipId == null) return;
        if (flagship.isRetreating() || flagship.isDirectRetreat()) {
            group.formationSpawnHijackedShipIds.add("__retreating_" + flagshipId);
        }
        String lossKey = "__otc_lost_" + flagshipId;
        if (group.formationSpawnHijackedShipIds.contains(lossKey)) {
            return;
        }

        boolean destroyed = !flagship.isAlive() || flagship.isHulk();
        if (!destroyed) return;

        boolean retreated = isShipRetreatedFromBattle(engine, group, flagship);
        boolean wasAlphaCoreOtc = BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(flagship)
                || (group.otc != null && BattlefieldFirepowerEvaluator.isAlphaCoreOfficer(group.otc.person))
                || (group.side == 0 && !group.isAlliedAi && isAlphaCoreOtcLockedInBattle(group));

        if (retreated) {
            group.formationSpawnHijackedShipIds.add(lossKey);
            if (wasAlphaCoreOtc) {
                ShipAPI nextAlpha = findBestDeployedAlphaCoreSuccessor(engine, group, flagship);
                if (nextAlpha != null) {
                    promoteShipToGroupOtc(engine, group, nextAlpha, now, true);
                    return;
                }
            }
            if (group.side == 1 || group.isAlliedAi) {
                ShipAPI nextSuccession = selectSuccessorShipByHierarchy(engine, group, flagship, wasAlphaCoreOtc);
                if (nextSuccession != null) {
                    promoteShipToGroupOtc(engine, group, nextSuccession, now, false);
                } else {
                    group.activeFleetFlagship = null;
                }
            } else {
                group.activeFleetFlagship = null;
            }
            return;
        }

        // Feature B: If an Alpha Core OTC is destroyed, any other eligible Alpha Core ship deployed in battle
        // on that side is automatically chosen to be OTC.
        if (wasAlphaCoreOtc) {
            ShipAPI nextAlpha = findBestDeployedAlphaCoreSuccessor(engine, group, flagship);
            if (nextAlpha != null) {
                group.formationSpawnHijackedShipIds.add(lossKey);
                promoteShipToGroupOtc(engine, group, nextAlpha, now, true);
                String oldName = flagship.getName() != null ? flagship.getName() : "Alpha Core Flagship";
                String newName = nextAlpha.getName() != null ? nextAlpha.getName() : "Alpha Core Vessel";
                String sideLabel = group.side == 0 ? (group.isAlliedAi ? "Allied" : "Player") : "Enemy";
                String line = String.format(Locale.ROOT,
                        "[DivisionAI] %s Alpha Core OTC \"%s\" destroyed -> Alpha Core on \"%s\" automatically assumes OTC.",
                        sideLabel, oldName, newName);
                if (DivisionAISettings.isDebugLoggingEnabled()) {
                    LOG.info(line);
                    if (DivisionCombatLog.simLogSink != null) {
                        DivisionCombatLog.simLogSink.add(String.format(Locale.ROOT, "[t=%5.1fs] %s", Math.max(0f, now), line));
                    }
                }
                if (announce) {
                    notifyPlayer(engine, String.format(Locale.ROOT,
                            "Alpha Core OTC \"%s\" destroyed! Surviving Alpha Core on \"%s\" automatically assumes OTC.",
                            oldName, newName), true);
                }
                return;
            }
            if (BattlefieldFirepowerEvaluator.isNonHumanConvergenceGroup(group)
                    || Boolean.TRUE.equals(group.forceConvergenceOverride)) {
                return;
            }
            // No other deployed Alpha Core ship remains on this side — lift Alpha Core lock and fall through
            group.useConvergence = false;
            if (group.side == 0 && !group.isAlliedAi) {
                clearPlayerAlphaCoreOtcLockInBattle(group);
                BattlefieldFirepowerEvaluator.setPlayerConvergenceFlagshipOverride(null);
            }
        } else if (group.useConvergence || Boolean.TRUE.equals(group.forceConvergenceOverride)) {
            return;
        }

        if (isOtcDisabled(group, now)) {
            return;
        }

        boolean overallCommanderAboard = isFleetOverallCommanderAboard(engine, group, flagship);
        float blackout = overallCommanderAboard
                ? FLEET_COMMANDER_FLAGSHIP_LOSS_BLACKOUT_SECONDS
                : LOWER_COMMANDER_FLAGSHIP_LOSS_BLACKOUT_SECONDS;
        group.otcDisabledUntilTime = Math.max(group.otcDisabledUntilTime, now + blackout);
        group.otcDisabledWarningActive = true;
        group.formationSpawnHijackedShipIds.add(lossKey);
        group.formationSpawnHijackedShipIds.add("__overall_cmdr_lost");

        String shipName = flagship.getName() != null ? flagship.getName() : "Flagship";
        String durationStr = overallCommanderAboard ? "2 minutes (120s)" : "30 seconds";
        String stationDesc = overallCommanderAboard ? "overall fleet commander" : "lower/no station commander";
        String logLine = String.format(Locale.ROOT,
                "[DivisionAI] %s flagship \"%s\" (%s) destroyed! OTC disabled for %s.",
                group.side == 0 ? (group.isAlliedAi ? "Allied" : "Player") : "Enemy",
                shipName, stationDesc, durationStr);
        if (DivisionAISettings.isDebugLoggingEnabled()) {
            LOG.warn(logLine);
            if (DivisionCombatLog.simLogSink != null) {
                DivisionCombatLog.simLogSink.add(String.format(Locale.ROOT, "[t=%5.1fs] %s", Math.max(0f, now), logLine));
            }
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

        if (group.side == 0 && !group.isAlliedAi) {
            // Feature C: Player side's OTC ship destroyed -> pause game and open OTC selection screen
            group.preferredOtcShipId = null;
            preferredPlayerOtcMemberId = null;
            autoSelectedPlayerOtcMemberId = null;
            group.activeFleetFlagship = null;
            group.designatedSuccessorFlagship = selectSuccessorShipByHierarchy(engine, group, flagship, false);
            triggerEmergencyPlayerOtcSelection(engine, group);
        } else {
            // Feature D: AI-controlled fleet OTC/Flagship loss succession hierarchy
            ShipAPI successor = selectSuccessorShipByHierarchy(engine, group, flagship, false);
            group.designatedSuccessorFlagship = successor;
            if (successor != null) {
                promoteShipToGroupOtc(engine, group, successor, now, false);
            } else {
                group.activeFleetFlagship = null;
            }
        }
    }

    /**
     * Feature B: Finds the highest-priority alive, combat-ready Alpha Core ship deployed on the given group's side,
     * excluding the destroyed/lost flagship.
     */
    public static ShipAPI findBestDeployedAlphaCoreSuccessor(
            CombatEngineAPI engine,
            BattleGroupState group,
            ShipAPI excludedLostShip) {
        List<ShipAPI> pool = collectDeployedAliveGroupShips(engine, group, excludedLostShip);
        ShipAPI best = null;
        float bestScore = -Float.MAX_VALUE;
        for (ShipAPI s : pool) {
            if (!BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(s)) continue;
            float score = scoreSuccessorCandidate(s, group, true);
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    /**
     * Feature D: General OTC / Flagship Loss Succession Hierarchy (especially for AI-controlled fleets):
     *  - Tier 1: Eligible deployed Alpha Core ships (when previous OTC was an Alpha Core or in AI fleets with Alpha Cores).
     *  - Tier 2: Active Division Commanders (flagships of combat divisions led by named officers or Beta/Gamma AI cores),
     *            ordered by officer level, doctrinal division role (Main Effort / Battleship / Cruiser / Carrier / Destroyer / Frigate),
     *            hull size tier, DP, and combat health.
     *  - Tier 3: Other deployed officer-commanded combat ships in the fleet, ordered by officer level, hull size tier, and DP.
     *  - Tier 4: Senior surviving combat warship (Acting Flag Captain), ordered by hull size tier, division flagship status, DP, and health.
     *  - Tier 5: Any remaining surviving hull.
     */
    public static ShipAPI selectSuccessorShipByHierarchy(
            CombatEngineAPI engine,
            BattleGroupState group,
            ShipAPI excludedLostShip,
            boolean preferAlphaCoreFirst) {
        List<ShipAPI> pool = collectDeployedAliveGroupShips(engine, group, excludedLostShip);
        if (pool.isEmpty()) return null;

        boolean allowAlphaCoreTier1 = preferAlphaCoreFirst
                || group == null
                || group.side == 1
                || group.isAlliedAi
                || group.useConvergence;

        ShipAPI best = null;
        float bestScore = -Float.MAX_VALUE;
        for (ShipAPI s : pool) {
            float score = scoreSuccessorCandidate(s, group, allowAlphaCoreTier1);
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    /**
     * Feature D: Selects the best surviving flagship within a single combat division when its division flagship is lost,
     * updating both div.flagship and div.commander.
     */
    public static ShipAPI resolveBestDivisionFlagshipSuccessor(DivisionState div) {
        if (div == null) return null;
        List<ShipAPI> alive = div.getAliveMembers();
        if (alive == null || alive.isEmpty()) return null;
        if (div.flagship != null
                && alive.contains(div.flagship)
                && div.flagship.isAlive()
                && !div.flagship.isHulk()
                && !div.flagship.isRetreating()
                && !div.flagship.isDirectRetreat()) {
            return div.flagship;
        }
        ShipAPI best = null;
        float bestScore = -Float.MAX_VALUE;
        boolean preferCarrier = div.archetype != null && div.archetype.isCarrierArchetype();
        for (ShipAPI s : alive) {
            if (s == null || !s.isAlive() || s.isHulk()) continue;
            float score = scoreSuccessorCandidate(s, null, true);
            if (preferCarrier && s.getNumFighterBays() > 0) {
                score += 600f;
            }
            if (s.isRetreating() || s.isDirectRetreat()) {
                score -= 25000f;
            }
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        if (best != null && best != div.flagship) {
            div.flagship = best;
            PersonAPI cap = best.getCaptain();
            String fallback = (div.displayName != null ? div.displayName : "Division") + " Cmdr";
            div.commander = new CommanderProfile(cap, fallback);
        }
        return best;
    }

    /**
     * Promotes the given successor ship to be the battle group's active fleet flagship and OTC.
     */
    public static void promoteShipToGroupOtc(
            CombatEngineAPI engine,
            BattleGroupState group,
            ShipAPI successor,
            float now,
            boolean isAlphaCoreAutoTransfer) {
        if (group == null || successor == null) return;
        group.activeFleetFlagship = successor;
        group.designatedSuccessorFlagship = successor;
        group.successionCount++;

        PersonAPI cap = successor.getCaptain();
        String fallbackName = (cap != null && cap.getNameString() != null && !cap.getNameString().trim().isEmpty())
                ? cap.getNameString().trim()
                : (successor.getName() != null ? successor.getName() + " Captain" : "Acting OTC");
        group.otc = new CommanderProfile(cap, fallbackName);

        boolean isAlpha = isAlphaCoreAutoTransfer || BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(successor);
        if (isAlpha) {
            group.useConvergence = true;
            group.otcDisabledUntilTime = -100f;
            group.otcDisabledWarningActive = false;
        }

        if (group.side == 0 && !group.isAlliedAi) {
            String memberOrShipId = (successor.getFleetMember() != null && successor.getFleetMember().getId() != null)
                    ? successor.getFleetMember().getId()
                    : successor.getId();
            setPreferredPlayerOtcMemberId(group, memberOrShipId);
            clearEmergencyPlayerOtcSelection(group);
            if (isAlpha) {
                lockPlayerAlphaCoreOtcInBattle(group);
                BattlefieldFirepowerEvaluator.setPlayerConvergenceFlagshipOverride(successor);
            }
        }
    }

    private static List<ShipAPI> collectDeployedAliveGroupShips(
            CombatEngineAPI engine,
            BattleGroupState group,
            ShipAPI excludedLostShip) {
        List<ShipAPI> out = new ArrayList<>();
        Set<ShipAPI> seen = new HashSet<>();
        int wantSide = (group != null) ? group.side : 0;
        boolean wantAlly = (group != null) && group.isAlliedAi;

        if (engine != null && engine.getShips() != null) {
            for (ShipAPI s : engine.getShips()) {
                if (s == null || s == excludedLostShip || seen.contains(s)) continue;
                if (s.isFighter() || s.isShuttlePod() || s.isStationModule()) continue;
                if (!s.isAlive() || s.isHulk() || s.isExpired()) continue;
                if (s.getOwner() != wantSide || s.isAlly() != wantAlly) continue;
                if (isShipRetreatedFromBattle(engine, group, s)) continue;
                seen.add(s);
                out.add(s);
            }
        }
        if (group != null && group.divisions != null) {
            for (DivisionState d : group.divisions) {
                if (d == null || d.members == null) continue;
                for (ShipAPI s : d.members) {
                    if (s == null || s == excludedLostShip || seen.contains(s)) continue;
                    if (s.isFighter() || s.isShuttlePod() || s.isStationModule()) continue;
                    if (!s.isAlive() || s.isHulk() || s.isExpired()) continue;
                    if (isShipRetreatedFromBattle(engine, group, s)) continue;
                    seen.add(s);
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static float scoreSuccessorCandidate(
            ShipAPI s,
            BattleGroupState group,
            boolean allowAlphaCoreTier1) {
        if (s == null) return -Float.MAX_VALUE;
        boolean isAlpha = BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(s);
        PersonAPI cap = s.getCaptain();
        boolean hasOfficer = (cap != null && !cap.isDefault());
        int officerLevel = getOfficerLevelSafe(cap);
        int aiCoreRank = getAiCoreHierarchyRank(cap);

        DivisionState owningDiv = null;
        boolean isDivLeader = false;
        if (group != null && group.divisions != null) {
            for (DivisionState d : group.divisions) {
                if (d == null) continue;
                if (d.flagship == s) {
                    owningDiv = d;
                    isDivLeader = true;
                    break;
                }
                if (d.members != null && d.members.contains(s)) {
                    owningDiv = d;
                }
            }
        }

        boolean isCivilian = isCivilianHullSafe(s);
        float hullTierScore = getHullSizeHierarchyScore(s);
        float fpScore = Math.min(400f, resolveShipFpSafe(s) * 4.0f);
        float healthScore = Math.max(0f, Math.min(1f, s.getHullLevel())) * 50f;
        try {
            if (s.getFluxTracker() != null && s.getFluxTracker().isOverloadedOrVenting()) {
                healthScore -= 120f;
            }
        } catch (Throwable ignored) {
        }

        float divRoleScore = 0f;
        if (owningDiv != null) {
            if (group != null && owningDiv == group.mainEffortDivision) {
                divRoleScore += 800f;
            }
            if (owningDiv.archetype != null) {
                if (owningDiv.archetype.isCarrierArchetype()) {
                    divRoleScore += 350f;
                } else {
                    switch (owningDiv.archetype) {
                        case BATTLESHIP_DIVISION:
                        case MISSILE_BATTLESHIP_DIVISION:
                            divRoleScore += 600f;
                            break;
                        case CRUISER_DIVISION:
                        case STRIKE_CRUISER_DIVISION:
                        case MISSILE_CRUISER_DIVISION:
                            divRoleScore += 450f;
                            break;
                        case DESTROYER_DIVISION:
                        case MISSILE_DESTROYER_DIVISION:
                            divRoleScore += 250f;
                            break;
                        case FRIGATE_DIVISION:
                        case MISSILE_FRIGATE_DIVISION:
                        case PHASE_DIVISION:
                            divRoleScore += 150f;
                            break;
                        default:
                            divRoleScore += 50f;
                            break;
                    }
                }
            }
        }

        float tierBase;
        if (isAlpha && allowAlphaCoreTier1) {
            // Tier 1: Eligible Alpha Core Ship
            tierBase = 100000f;
        } else if (isDivLeader && (hasOfficer || isAlpha)) {
            // Tier 2: Active Division Commander with Officer / AI Core
            tierBase = 50000f;
        } else if (hasOfficer || isAlpha) {
            // Tier 3: Other Deployed Fleet Officer / AI Core
            tierBase = 25000f;
        } else if (!isCivilian) {
            // Tier 4: Senior Surviving Combat Warship (Acting Flag Captain)
            tierBase = 10000f + (isDivLeader ? 2000f : 0f);
        } else {
            // Tier 5: Surviving Auxiliary / Civilian Hull
            tierBase = 1000f;
        }

        float officerScore = officerLevel * 500f + aiCoreRank * 400f + (isDivLeader ? 750f : 0f);
        float civPenalty = isCivilian ? -1500f : 0f;
        return tierBase + officerScore + divRoleScore + hullTierScore + fpScore + healthScore + civPenalty;
    }

    private static int getOfficerLevelSafe(PersonAPI cap) {
        if (cap == null || cap.isDefault()) return 0;
        try {
            if (cap.getStats() != null) {
                return Math.max(1, cap.getStats().getLevel());
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    private static int getAiCoreHierarchyRank(PersonAPI cap) {
        if (cap == null) return 0;
        try {
            String coreId = cap.getAICoreId();
            if (coreId != null) {
                String low = coreId.trim().toLowerCase(Locale.ROOT);
                if (low.contains("omega")) return 4;
                if (low.contains("alpha")) return 3;
                if (low.contains("beta")) return 2;
                if (low.contains("gamma")) return 1;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static float getHullSizeHierarchyScore(ShipAPI s) {
        if (s == null || s.getHullSize() == null) return 100f;
        switch (s.getHullSize()) {
            case CAPITAL_SHIP:
                return 400f;
            case CRUISER:
                return 300f;
            case DESTROYER:
                return 200f;
            case FRIGATE:
                return 100f;
            default:
                return 50f;
        }
    }

    private static boolean isCivilianHullSafe(ShipAPI s) {
        if (s == null) return false;
        try {
            if (s.getVariant() != null && s.getVariant().isCivilian()) return true;
        } catch (Throwable ignored) {
        }
        try {
            if (s.getHullSpec() != null && s.getHullSpec().isCivilianNonCarrier()) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static float resolveShipFpSafe(ShipAPI s) {
        if (s == null) return 1f;
        try {
            float fp = ShipCapabilityProfile.resolveShipFpStrength(s);
            if (fp > 0f) return fp;
        } catch (Throwable ignored) {
        }
        try {
            if (s.getFleetMember() != null) {
                return Math.max(1f, s.getFleetMember().getFleetPointCost());
            }
        } catch (Throwable ignored) {
        }
        try {
            if (s.getHullSpec() != null) {
                return Math.max(1f, s.getHullSpec().getFleetPoints());
            }
        } catch (Throwable ignored) {
        }
        return 5f;
    }

    public static void checkDivisionFlagshipsDestroyed(
            CombatEngineAPI engine,
            BattleGroupState group,
            float now) {
        if (group == null || group.isPassiveOpposingView) return;
        for (DivisionState div : group.divisions) {
            if (div == null || div.flagship == null) continue;
            ShipAPI f = div.flagship;
            if (f.getId() == null) continue;
            if (f.isRetreating() || f.isDirectRetreat()) {
                group.formationSpawnHijackedShipIds.add("__retreating_" + f.getId());
            }
            String divFlagKey = "__div_flag_lost_" + f.getId();
            if (!f.isAlive() || f.isHulk()) {
                boolean alreadyRecorded = !group.formationSpawnHijackedShipIds.add(divFlagKey);
                boolean alsoOtcLoss = group.formationSpawnHijackedShipIds.contains("__otc_lost_" + f.getId());
                boolean retreated = isShipRetreatedFromBattle(engine, group, f);
                // Feature D: Promote division flagship successor by hierarchy
                ShipAPI succ = resolveBestDivisionFlagshipSuccessor(div);
                div.flagship = succ;
                if (succ != null && succ.getCaptain() != null && !succ.getCaptain().isDefault()) {
                    div.commander = new CommanderProfile(succ.getCaptain(), succ.getCaptain().getNameString());
                }
                if (!alreadyRecorded && !alsoOtcLoss && !retreated) {
                    if (group.side != 0) {
                        String shipName = f.getName() != null ? f.getName() : "Enemy Division Leader";
                        String divName = div.displayName != null ? div.displayName : "Enemy Division";
                        String line = String.format(Locale.ROOT,
                                "[DivisionAI] Enemy division leader \"%s\" (%s) destroyed!",
                                shipName, divName);
                        if (DivisionAISettings.isDebugLoggingEnabled()) {
                            LOG.info(line);
                            if (DivisionCombatLog.simLogSink != null) {
                                DivisionCombatLog.simLogSink.add(String.format(Locale.ROOT, "[t=%5.1fs] %s", Math.max(0f, now), line));
                            }
                        }
                        notifyPlayer(engine, String.format(Locale.ROOT,
                                "Enemy division leader \"%s\" (%s) destroyed!",
                                shipName, divName), true);
                    } else {
                        String sidePrefix = group.isAlliedAi ? "Allied division leader" : "Division leader";
                        String shipName = f.getName() != null ? f.getName() : "Division Leader";
                        String divName = div.displayName != null ? div.displayName : "Division";
                        String line = String.format(Locale.ROOT,
                                "[DivisionAI] %s \"%s\" (%s) destroyed!",
                                sidePrefix, shipName, divName);
                        if (DivisionAISettings.isDebugLoggingEnabled()) {
                            LOG.info(line);
                            if (DivisionCombatLog.simLogSink != null) {
                                DivisionCombatLog.simLogSink.add(String.format(Locale.ROOT, "[t=%5.1fs] %s", Math.max(0f, now), line));
                            }
                        }
                        notifyPlayer(engine, String.format(Locale.ROOT,
                                "%s \"%s\" (%s) destroyed!",
                                sidePrefix, shipName, divName), false);
                    }
                }
            }
        }
    }

    /**
     * Returns true if the ship left play by retreating from the battlefield rather than being destroyed.
     * Retreating flagships do not trigger the OTC command-disruption cooldown malus.
     */
    public static boolean isShipRetreatedFromBattle(
            CombatEngineAPI engine,
            BattleGroupState group,
            ShipAPI ship) {
        if (ship == null) return false;
        if (ship.isHulk()) return false;
        if (ship.isRetreating() || ship.isDirectRetreat()) {
            return true;
        }
        if (group != null && ship.getId() != null
                && group.formationSpawnHijackedShipIds.contains("__retreating_" + ship.getId())) {
            return true;
        }
        // Fast path: a ship that is currently alive on the battlefield and not marked retreating has not despawned
        // into fm.getRetreatedCopy(). Avoid cloning fm.getRetreatedCopy() for every alive ship on every frame.
        if (ship.isAlive()) {
            return false;
        }
        try {
            if (engine != null && engine.getFleetManager(ship.getOwner()) != null) {
                CombatFleetManagerAPI fm = engine.getFleetManager(ship.getOwner());
                List<FleetMemberAPI> retreated = fm.getRetreatedCopy();
                if (retreated != null && ship.getFleetMember() != null) {
                    for (FleetMemberAPI rm : retreated) {
                        if (rm == ship.getFleetMember()
                                || (rm != null && rm.getId() != null && rm.getId().equals(ship.getFleetMember().getId()))) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // In Starsector, a ship despawned off the map edge after retreating has !isAlive(), !isHulk(), and hitpoints > 0
        if (!ship.isAlive() && !ship.isHulk() && ship.getHitpoints() > 0f) {
            return true;
        }
        return false;
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
