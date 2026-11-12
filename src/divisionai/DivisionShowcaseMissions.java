package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionNomenclature.NomenclatureCategory;
import divisionai.PermanentDivisionManager.DivisionFormation;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.DivisionSetpieceAttack;
import divisionai.PermanentDivisionManager.DoctrineDivisionType;
import divisionai.PermanentDivisionManager.FleetDoctrineConfig;
import divisionai.PermanentDivisionManager.FleetWideFormation;
import divisionai.PermanentDivisionManager.LineStation;
import divisionai.PermanentDivisionManager.MissionScenarioBundle;
import divisionai.PermanentDivisionManager.MultiDivisionSetpiece;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import divisionai.PermanentDivisionManager.SquadronFormation;
import divisionai.PermanentDivisionManager.VoidstrikeAoaCorrection;
import divisionai.PermanentDivisionManager.VoidstrikeAttackGeometry;
import divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode;
import divisionai.PermanentDivisionManager.VoidstrikeGunneryMode;
import divisionai.PermanentDivisionManager.VoidstrikeTargetObservance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Defines the 4x4 matrix (16 single missions) of ~300+ DP fleet engagements featuring:
 *  1. Mostly Low Tech (Hegemony)
 *  2. Mostly Midline (Persean League)
 *  3. Mostly High Tech (Tri-Tachyon)
 *  4. Remnants (Autonomous AI Core Ordo)
 *
 * Every fleet has 6+ cruiser-or-larger carrier hulls organized into two dedicated Carrier Divisions
 * with baked-in PermanentDivisionSpecs, Division Leaders, doctrinal formations, and Advanced Strike Craft
 * Gunnery configurations (L-Crossfire & Pincer Envelopment, 3-Tier Area Saturation, and Strong AoA Correction).
 */
public final class DivisionShowcaseMissions {

    private DivisionShowcaseMissions() {
    }

    public enum ShowcaseFaction {
        LOW_TECH(
                "low",
                "Low Tech",
                "Hegemony",
                "hegemony",
                "HSS",
                "Hegemony Heavy Task Force",
                NomenclatureCategory.HUMAN_NAVAL,
                "graphics/backgrounds/background4.jpg"),
        MIDLINE(
                "mid",
                "Midline",
                "Persean League",
                "persean",
                "PLS",
                "Persean League Strike Armada",
                NomenclatureCategory.HUMAN_NAVAL,
                "graphics/backgrounds/background2.jpg"),
        HIGH_TECH(
                "high",
                "High Tech",
                "Tri-Tachyon",
                "tritachyon",
                "TTS",
                "Tri-Tachyon Special Asset Armada",
                NomenclatureCategory.HUMAN_NAVAL,
                "graphics/backgrounds/background6.jpg"),
        REMNANT(
                "rem",
                "Remnants",
                "Remnant Ordo",
                "remnant",
                "TTDS",
                "Autonomous Remnant Ordo",
                NomenclatureCategory.POST_AI_WAR,
                "graphics/backgrounds/hyperspace_bg_cool.jpg");

        public final String shortCode;
        public final String techLabel;
        public final String factionDisplayName;
        public final String factionId;
        public final String shipPrefix;
        public final String tagline;
        public final NomenclatureCategory nomenclature;
        public final String backgroundPath;

        ShowcaseFaction(
                String shortCode,
                String techLabel,
                String factionDisplayName,
                String factionId,
                String shipPrefix,
                String tagline,
                NomenclatureCategory nomenclature,
                String backgroundPath) {
            this.shortCode = shortCode;
            this.techLabel = techLabel;
            this.factionDisplayName = factionDisplayName;
            this.factionId = factionId;
            this.shipPrefix = shipPrefix;
            this.tagline = tagline;
            this.nomenclature = nomenclature;
            this.backgroundPath = backgroundPath;
        }
    }

    private static final class ShipSpecEntry {
        final String variantId;
        final String shipName;
        final boolean fleetFlagship;
        final boolean divisionLeader;
        final String officerFirstName;
        final String officerLastName;
        final String portraitPath;
        final String personality;
        final int level;
        final String aiCoreId;
        final boolean addConvertedHangar;
        final List<String> customWings;

        ShipSpecEntry(
                String variantId,
                String shipName,
                boolean fleetFlagship,
                boolean divisionLeader,
                String officerFirstName,
                String officerLastName,
                String portraitPath,
                String personality,
                int level,
                String aiCoreId,
                boolean addConvertedHangar,
                String... customWings) {
            this.variantId = variantId;
            this.shipName = shipName;
            this.fleetFlagship = fleetFlagship;
            this.divisionLeader = divisionLeader;
            this.officerFirstName = officerFirstName;
            this.officerLastName = officerLastName;
            this.portraitPath = portraitPath;
            this.personality = personality;
            this.level = level;
            this.aiCoreId = aiCoreId;
            this.addConvertedHangar = addConvertedHangar;
            this.customWings = (customWings != null && customWings.length > 0)
                    ? Arrays.asList(customWings)
                    : Collections.<String>emptyList();
        }
    }

    private static final class BakedDivisionTemplate {
        final String suffixId;
        final String divisionName;
        final DivisionArchetype archetype;
        final DivisionFormation formation;
        final DivisionMissionType openingMission;
        final DivisionMissionType mainMission;
        final LineStation lineStation;
        final VoidstrikeTargetObservance voidstrikeObservance;
        final VoidstrikeCoordinationMode voidstrikeCoordination;
        final VoidstrikeAttackGeometry voidstrikeGeometry;
        final PermanentDivisionManager.VoidstrikeSplitMode voidstrikeSplitMode;
        final VoidstrikeGunneryMode voidstrikeGunnery;
        final VoidstrikeAoaCorrection voidstrikeAoa;
        final List<ShipSpecEntry> ships = new ArrayList<>();

        BakedDivisionTemplate(
                String suffixId,
                String divisionName,
                DivisionArchetype archetype,
                DivisionFormation formation,
                DivisionMissionType openingMission,
                DivisionMissionType mainMission,
                LineStation lineStation,
                VoidstrikeTargetObservance voidstrikeObservance,
                VoidstrikeCoordinationMode voidstrikeCoordination,
                VoidstrikeAttackGeometry voidstrikeGeometry,
                VoidstrikeGunneryMode voidstrikeGunnery,
                VoidstrikeAoaCorrection voidstrikeAoa,
                ShipSpecEntry... entries) {
            this(suffixId, divisionName, archetype, formation, openingMission, mainMission, lineStation,
                    voidstrikeObservance, voidstrikeCoordination, voidstrikeGeometry, null, voidstrikeGunnery, voidstrikeAoa, entries);
        }

        BakedDivisionTemplate(
                String suffixId,
                String divisionName,
                DivisionArchetype archetype,
                DivisionFormation formation,
                DivisionMissionType openingMission,
                DivisionMissionType mainMission,
                LineStation lineStation,
                VoidstrikeTargetObservance voidstrikeObservance,
                VoidstrikeCoordinationMode voidstrikeCoordination,
                VoidstrikeAttackGeometry voidstrikeGeometry,
                PermanentDivisionManager.VoidstrikeSplitMode voidstrikeSplitMode,
                VoidstrikeGunneryMode voidstrikeGunnery,
                VoidstrikeAoaCorrection voidstrikeAoa,
                ShipSpecEntry... entries) {
            this.suffixId = suffixId;
            this.divisionName = divisionName;
            this.archetype = archetype;
            this.formation = formation;
            this.openingMission = openingMission;
            this.mainMission = mainMission;
            this.lineStation = lineStation;
            this.voidstrikeObservance = voidstrikeObservance;
            this.voidstrikeCoordination = voidstrikeCoordination;
            this.voidstrikeGeometry = voidstrikeGeometry;
            this.voidstrikeSplitMode = voidstrikeSplitMode;
            this.voidstrikeGunnery = voidstrikeGunnery;
            this.voidstrikeAoa = voidstrikeAoa;
            if (entries != null) {
                Collections.addAll(this.ships, entries);
            }
        }
    }

    public static String getMissionId(int rowOneBased, int colOneBased) {
        ShowcaseFaction[] vals = ShowcaseFaction.values();
        ShowcaseFaction p = vals[Math.max(0, Math.min(vals.length - 1, rowOneBased - 1))];
        ShowcaseFaction e = vals[Math.max(0, Math.min(vals.length - 1, colOneBased - 1))];
        return "divai_" + rowOneBased + colOneBased + "_" + p.shortCode + "_vs_" + e.shortCode;
    }

    public static void defineMission(
            MissionDefinitionAPI api,
            final String missionId,
            ShowcaseFaction playerFaction,
            ShowcaseFaction enemyFaction) {
        if (api == null || missionId == null || playerFaction == null || enemyFaction == null) {
            return;
        }

        api.initFleet(FleetSide.PLAYER, playerFaction.shipPrefix, FleetGoal.ATTACK, false, 15, 500);
        api.initFleet(FleetSide.ENEMY, enemyFaction.shipPrefix, FleetGoal.ATTACK, true, 15, 500);

        api.setFleetTagline(FleetSide.PLAYER, playerFaction.tagline);
        api.setFleetTagline(FleetSide.ENEMY, enemyFaction.tagline);

        api.addBriefingItem("This is a dynamic wargame scenario taught at the academy based on historical military doctrine.");

        List<PermanentDivisionSpec> playerDivs = populateSideFleetAndDivisions(
                api, FleetSide.PLAYER, missionId, playerFaction);
        List<PermanentDivisionSpec> enemyDivs = populateSideFleetAndDivisions(
                api, FleetSide.ENEMY, missionId, enemyFaction);

        FleetDoctrineConfig playerDoctrine = buildFactionShowcaseDoctrine(playerFaction);
        FleetDoctrineConfig enemyDoctrine = buildFactionShowcaseDoctrine(enemyFaction);

        MissionScenarioBundle bundle = new MissionScenarioBundle(
                missionId,
                playerFaction.factionId,
                enemyFaction.factionId,
                playerFaction.nomenclature,
                enemyFaction.nomenclature,
                playerDoctrine,
                enemyDoctrine,
                playerDivs,
                enemyDivs);
        PermanentDivisionManager.registerMissionScenario(bundle);

        float width = 24000f;
        float height = 18000f;
        api.initMap(-width / 2f, width / 2f, -height / 2f, height / 2f);
        api.setBackgroundSpriteName(playerFaction.backgroundPath);

        for (int i = 0; i < 10; i++) {
            float x = (float) (((i * 3719) % 16000) - 8000);
            float y = (float) (((i * 5179) % 10000) - 5000);
            float r = 250f + (i % 4) * 180f;
            api.addNebula(x, y, r);
        }

        api.addObjective(-4200f, 0f, "nav_buoy", BattleObjectiveAPI.Importance.NORMAL);
        api.addObjective(4200f, 0f, "nav_buoy", BattleObjectiveAPI.Importance.NORMAL);
        api.addObjective(-2400f, 2200f, "sensor_array", BattleObjectiveAPI.Importance.NORMAL);
        api.addObjective(2400f, -2200f, "sensor_array", BattleObjectiveAPI.Importance.NORMAL);
        api.addObjective(0f, 0f, "comm_relay", BattleObjectiveAPI.Importance.NORMAL);

        api.addPlugin(new BaseEveryFrameCombatPlugin() {
            private boolean activated = false;

            @Override
            public void init(CombatEngineAPI engine) {
                activated = true;
                PermanentDivisionManager.activateMissionScenarioForEngine(engine, missionId);
            }

            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                CombatEngineAPI engine = Global.getCombatEngine();
                if (engine == null) return;
                if (!activated) {
                    activated = true;
                    PermanentDivisionManager.activateMissionScenarioForEngine(engine, missionId);
                }
                PermanentDivisionManager.ensureMissionDeploymentPointCap(engine);
            }
        });
    }

    private static FleetDoctrineConfig buildFactionShowcaseDoctrine(ShowcaseFaction fac) {
        FleetDoctrineConfig base = FactionFleetDoctrines.getDoctrineForFactionId(fac.factionId, null);
        FleetDoctrineConfig cfg = PermanentDivisionManager.copyDoctrineConfig(base);
        cfg.setDeployInFleetFormation(true);
        cfg.setOrchestratedAdvance(true);
        for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
            if (dt == DoctrineDivisionType.FLEET_CARRIER
                    || dt == DoctrineDivisionType.LIGHT_CARRIER
                    || dt == DoctrineDivisionType.ESCORT_CARRIER
                    || dt == DoctrineDivisionType.CARRIER_GROUP) {
                cfg.setVoidstrikeObservance(dt, VoidstrikeTargetObservance.HIGH);
                cfg.setVoidstrikeCoordination(dt, VoidstrikeCoordinationMode.COORDINATED);
                cfg.setVoidstrikeAoaCorrection(dt, VoidstrikeAoaCorrection.STRONG);
            }
        }
        switch (fac) {
            case LOW_TECH:
                cfg.setOtcAuthority(OtcAuthority.POSITIVE_CONTROL);
                cfg.setFleetWideFormation(FleetWideFormation.GRAND_WEDGE_SPEARHEAD);
                cfg.setMultiDivisionSetpiece(MultiDivisionSetpiece.HAMMER_AND_ANVIL);
                break;
            case MIDLINE:
                cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_DIRECTION);
                cfg.setFleetWideFormation(FleetWideFormation.REFUSED_FLANK_OBLIQUE);
                cfg.setMultiDivisionSetpiece(MultiDivisionSetpiece.CANNAAE_DOUBLE_ENVELOPMENT);
                break;
            case HIGH_TECH:
                cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_DIRECTION);
                cfg.setFleetWideFormation(FleetWideFormation.GRAND_WEDGE_SPEARHEAD);
                cfg.setMultiDivisionSetpiece(MultiDivisionSetpiece.CONVERGENCE_KILL_BOX);
                break;
            case REMNANT:
                cfg.setOtcAuthority(OtcAuthority.POSITIVE_CONTROL);
                cfg.setFleetWideFormation(FleetWideFormation.CHECKERBOARD_QUINCUNX);
                cfg.setMultiDivisionSetpiece(MultiDivisionSetpiece.CONVERGENCE_KILL_BOX);
                break;
        }
        return cfg;
    }

    private static List<PermanentDivisionSpec> populateSideFleetAndDivisions(
            MissionDefinitionAPI api,
            FleetSide side,
            String missionId,
            ShowcaseFaction fac) {

        List<BakedDivisionTemplate> templates = buildRosterTemplates(fac);
        List<PermanentDivisionSpec> outSpecs = new ArrayList<>(templates.size());
        String sideTag = (side == FleetSide.PLAYER) ? "P" : "E";

        PersonAPI fleetCommander = null;
        if (fac == ShowcaseFaction.REMNANT) {
            fleetCommander = createOfficer(
                    "Alpha", "Core", "graphics/portraits/portrait_ai2b.png",
                    "aggressive", 7, "alpha_core", fac.factionId);
            try {
                PersonAPI defCmdr = api.getDefaultCommander(side);
                if (defCmdr != null) {
                    defCmdr.setAICoreId("alpha_core");
                    defCmdr.setPersonality("aggressive");
                }
            } catch (Throwable ignored) {
            }
        }

        int divSeq = 1;
        for (BakedDivisionTemplate tmpl : templates) {
            String specId = missionId + "_" + sideTag + "_div_" + divSeq + "_" + tmpl.suffixId;
            PermanentDivisionSpec spec = new PermanentDivisionSpec(specId, tmpl.divisionName, tmpl.archetype);
            spec.customFormation = tmpl.formation;
            spec.customOpeningMission = tmpl.openingMission;
            spec.customMainMission = tmpl.mainMission;
            spec.customLineStation = tmpl.lineStation;
            if (tmpl.archetype != null && tmpl.archetype.isCarrierArchetype()) {
                spec.customVoidstrikeTargetingBias = PermanentDivisionManager.VoidstrikeTargetingBias.CRUISERS_AND_CAPITALS;
                spec.customVoidstrikeObservance = tmpl.voidstrikeObservance;
                spec.customVoidstrikeCoordination = tmpl.voidstrikeCoordination;
                spec.customVoidstrikeAttackGeometry = tmpl.voidstrikeGeometry != null
                        ? tmpl.voidstrikeGeometry
                        : VoidstrikeAttackGeometry.DIRECT_ASSAULT;
                spec.customVoidstrikeSplitMode = tmpl.voidstrikeSplitMode != null
                        ? tmpl.voidstrikeSplitMode
                        : PermanentDivisionManager.VoidstrikeSplitMode.UNIFIED;
                spec.customVoidstrikeGunneryMode = tmpl.voidstrikeGunnery;
                spec.customVoidstrikeAoaCorrection = tmpl.voidstrikeAoa;
                spec.customFighterFormation = SquadronFormation.LINE_ABREAST;
                spec.customFighterVoidstrikeFormation = SquadronFormation.WEDGE;
                spec.customBomberFormation = SquadronFormation.LINE_ABREAST;
                spec.customBomberVoidstrikeFormation = SquadronFormation.LINE_ABREAST;
                spec.customSetpieceAttack = DivisionSetpieceAttack.NONE;
            }

            int shipSeq = 1;
            for (ShipSpecEntry entry : tmpl.ships) {
                String fullShipName = fac.shipPrefix + " " + entry.shipName;
                FleetMemberAPI member = api.addToFleet(
                        side, entry.variantId, FleetMemberType.SHIP, fullShipName, entry.fleetFlagship);
                if (member == null) continue;

                String detMemberId = missionId + "_" + sideTag + "_" + divSeq + "_" + shipSeq;
                try {
                    member.setId(detMemberId);
                } catch (Throwable ignored) {
                }
                String actualId = member.getId() != null ? member.getId() : detMemberId;

                customizeMemberVariantAndReadiness(member, entry, fac == ShowcaseFaction.REMNANT);

                PersonAPI captain = createOfficer(
                        entry.officerFirstName,
                        entry.officerLastName,
                        entry.portraitPath,
                        entry.personality,
                        entry.level,
                        entry.aiCoreId,
                        fac.factionId);
                if (captain != null) {
                    member.setCaptain(captain);
                    if (entry.fleetFlagship && fleetCommander == null) {
                        fleetCommander = captain;
                    }
                }
                if (fleetCommander != null) {
                    try {
                        member.setFleetCommanderForStats(fleetCommander, null);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    if (member.getRepairTracker() != null) {
                        member.getRepairTracker().setCR(0.85f);
                    }
                    member.updateStats();
                } catch (Throwable ignored) {
                }

                spec.memberIds.add(actualId);
                if (entry.divisionLeader || spec.flagshipMemberId == null) {
                    spec.flagshipMemberId = actualId;
                }
                shipSeq++;
            }

            if (!spec.memberIds.isEmpty()) {
                outSpecs.add(spec);
            }
            divSeq++;
        }

        return outSpecs;
    }

    private static void customizeMemberVariantAndReadiness(
            FleetMemberAPI member,
            ShipSpecEntry entry,
            boolean isRemnantSide) {
        if (member == null || member.getVariant() == null) return;
        try {
            boolean needsClone = isRemnantSide
                    || entry.addConvertedHangar
                    || !entry.customWings.isEmpty()
                    || member.getVariant().hasHullMod("automated");
            if (!needsClone) return;

            ShipVariantAPI copy = member.getVariant().clone();
            copy.setSource(VariantSource.REFIT);
            if (isRemnantSide || copy.hasHullMod("automated")) {
                copy.addTag("no_auto_penalty");
            }
            if (entry.addConvertedHangar && !copy.hasHullMod("converted_hangar")) {
                copy.addMod("converted_hangar");
            }
            if (!entry.customWings.isEmpty()) {
                for (int i = 0; i < entry.customWings.size(); i++) {
                    String wid = entry.customWings.get(i);
                    if (wid != null && !wid.isEmpty()) {
                        copy.setWingId(i, wid);
                    }
                }
            }
            member.setVariant(copy, false, false);
        } catch (Throwable ignored) {
        }
    }

    private static PersonAPI createOfficer(
            String firstName,
            String lastName,
            String portraitPath,
            String personality,
            int level,
            String aiCoreId,
            String factionId) {
        if (firstName == null || firstName.isEmpty()) return null;
        try {
            PersonAPI p = Global.getFactory().createPerson();
            p.setName(new FullName(firstName, lastName != null ? lastName : "", FullName.Gender.ANY));
            if (portraitPath != null && !portraitPath.isEmpty()) {
                p.setPortraitSprite(portraitPath);
            }
            if (personality != null && !personality.isEmpty()) {
                p.setPersonality(personality);
            }
            if (factionId != null && !factionId.isEmpty()) {
                try {
                    p.setFaction(factionId);
                } catch (Throwable ignored) {
                }
            }
            if (aiCoreId != null && !aiCoreId.isEmpty()) {
                p.setAICoreId(aiCoreId);
                try {
                    if (p.getMemoryWithoutUpdate() != null) {
                        p.getMemoryWithoutUpdate().set("$aiCoreId", aiCoreId);
                    }
                } catch (Throwable ignored) {
                }
            }
            int lvl = Math.max(1, level);
            p.getStats().setLevel(lvl);
            safeSetSkill(p, "helmsmanship", 2f);
            safeSetSkill(p, "combat_endurance", 2f);
            safeSetSkill(p, "target_analysis", 2f);
            if (lvl >= 5) {
                safeSetSkill(p, "field_modulation", 2f);
                safeSetSkill(p, "ordnance_expert", 2f);
            }
            if (lvl >= 6) {
                safeSetSkill(p, "systems_expertise", 2f);
                safeSetSkill(p, "point_defense", 2f);
            }
            if (aiCoreId != null && !aiCoreId.isEmpty()) {
                safeSetSkill(p, "automated_ships", 1f);
            }
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void safeSetSkill(PersonAPI p, String skillId, float level) {
        if (p == null || p.getStats() == null || skillId == null) return;
        try {
            p.getStats().setSkillLevel(skillId, level);
        } catch (Throwable ignored) {
        }
    }

    private static List<BakedDivisionTemplate> buildRosterTemplates(ShowcaseFaction fac) {
        List<BakedDivisionTemplate> list = new ArrayList<>(5);
        switch (fac) {
            case LOW_TECH:
                // 305 DP total | 7 Cruiser+ Carrier Hulls (1x Legion + 6x Mora) across 2 Carrier Divisions
                list.add(new BakedDivisionTemplate(
                        "battle_1",
                        "Battle Div. I",
                        DivisionArchetype.BATTLESHIP_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.STANDOFF_ENGAGEMENT,
                        DivisionMissionType.CLOSE_ACTION,
                        LineStation.VAN,
                        null, null, null, null, null,
                        new ShipSpecEntry("onslaught_xiv_Elite", "Sovereign", true, true,
                                "Kael", "Vance", "graphics/portraits/portrait_hegemony01.png", "steady", 6, null, false),
                        new ShipSpecEntry("onslaught_Standard", "Ironclad", false, false,
                                "Marcus", "Holt", "graphics/portraits/portrait_hegemony03.png", "steady", 5, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "carrier_1",
                        "Fleet Carrier Div. I",
                        DivisionArchetype.FLEET_CARRIER_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.ALPHA_STRIKE,
                        DivisionMissionType.CYCLIC_STRIKES,
                        LineStation.REAR,
                        VoidstrikeTargetObservance.HIGH,
                        VoidstrikeCoordinationMode.COORDINATED,
                        VoidstrikeAttackGeometry.DIRECT_ASSAULT,
                        PermanentDivisionManager.VoidstrikeSplitMode.UNIFIED,
                        VoidstrikeGunneryMode.AREA_SATURATION,
                        VoidstrikeAoaCorrection.STRONG,
                        new ShipSpecEntry("legion_Strike", "Forge of Orion", false, true,
                                "Mira", "Thorne", "graphics/portraits/portrait_hegemony04.png", "steady", 5, null, false,
                                "perdition_wing", "perdition_wing", "piranha_wing", "broadsword_wing"),
                        new ShipSpecEntry("mora_Torpedo", "Anvil Chorus", false, false,
                                "Darius", "Kael", "graphics/portraits/portrait_hegemony06.png", "steady", 4, null, false,
                                "perdition_wing", "perdition_wing", "broadsword_wing"),
                        new ShipSpecEntry("mora_Strike", "Bastion Wing", false, false,
                                null, null, null, null, 0, null, false,
                                "hoplon_wing", "hoplon_wing", "broadsword_wing")
                ));
                list.add(new BakedDivisionTemplate(
                        "carrier_2",
                        "Light Carrier Div. II",
                        DivisionArchetype.LIGHT_CARRIER_DIVISION,
                        DivisionFormation.ECHELON_RIGHT,
                        DivisionMissionType.ALPHA_STRIKE,
                        DivisionMissionType.CYCLIC_STRIKES,
                        LineStation.REAR,
                        VoidstrikeTargetObservance.HIGH,
                        VoidstrikeCoordinationMode.COORDINATED,
                        VoidstrikeAttackGeometry.DIRECT_ASSAULT,
                        PermanentDivisionManager.VoidstrikeSplitMode.UNIFIED,
                        VoidstrikeGunneryMode.AREA_SATURATION,
                        VoidstrikeAoaCorrection.STRONG,
                        new ShipSpecEntry("mora_Torpedo", "Redoubtable", false, true,
                                "Elena", "Vane", "graphics/portraits/portrait_hegemony07.png", "aggressive", 5, null, false,
                                "perdition_wing", "perdition_wing", "broadsword_wing"),
                        new ShipSpecEntry("mora_Assault", "Cinder Gate", false, false,
                                null, null, null, null, 0, null, false,
                                "warthog_wing", "warthog_wing", "broadsword_wing"),
                        new ShipSpecEntry("mora_Strike", "Iron Talon", false, false,
                                null, null, null, null, 0, null, false,
                                "piranha_wing", "piranha_wing", "talon_wing"),
                        new ShipSpecEntry("mora_Support", "Aegis Deck", false, false,
                                null, null, null, null, 0, null, false,
                                "perdition_wing", "sarissa_wing", "broadsword_wing")
                ));
                list.add(new BakedDivisionTemplate(
                        "cruiser_1",
                        "Cruiser Div. I",
                        DivisionArchetype.CRUISER_DIVISION,
                        DivisionFormation.FORWARD_WEDGE,
                        DivisionMissionType.STANDOFF_ENGAGEMENT,
                        DivisionMissionType.CLOSE_ACTION,
                        LineStation.CENTER,
                        null, null, null, null, null,
                        new ShipSpecEntry("dominator_Assault", "Judicator", false, true,
                                "Titus", "Rorke", "graphics/portraits/portrait_hegemony09.png", "aggressive", 5, null, false),
                        new ShipSpecEntry("eradicator_Assault", "Punisher", false, false,
                                null, null, null, null, 0, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "destroyer_1",
                        "Destroyer Div. I",
                        DivisionArchetype.DESTROYER_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.SCREEN,
                        DivisionMissionType.SCREEN,
                        LineStation.CENTER,
                        null, null, null, null, null,
                        new ShipSpecEntry("enforcer_Elite", "Bulwark", false, true,
                                "Viktor", "Drax", "graphics/portraits/portrait_hegemony12.png", "steady", 4, null, false),
                        new ShipSpecEntry("enforcer_Assault", "Warder", false, false,
                                null, null, null, null, 0, null, false)
                ));
                // 20 Frigates split into 4 Hegemony Doctrinal Divisions (5 hulls per division)
                list.add(new BakedDivisionTemplate(
                        "frigate_1",
                        "Frigate Div. I",
                        DivisionArchetype.FRIGATE_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.SCREEN,
                        DivisionMissionType.SCREEN,
                        LineStation.VAN,
                        null, null, null, null, null,
                        new ShipSpecEntry("lasher_CS", "Vanguard Picket", false, true,
                                "Garrick", "Thorne", "graphics/portraits/portrait_hegemony02.png", "steady", 4, null, false),
                        new ShipSpecEntry("lasher_Assault", "Iron Watch", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("lasher_Standard", "Outrider", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("centurion_Assault", "Shield Bearer", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("hound_hegemony_Standard", "Trailblazer", false, false,
                                null, null, null, null, 0, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "frigate_2",
                        "Frigate Div. II",
                        DivisionArchetype.FRIGATE_DIVISION,
                        DivisionFormation.FORWARD_WEDGE,
                        DivisionMissionType.SCREEN,
                        DivisionMissionType.CLOSE_ACTION,
                        LineStation.VAN,
                        null, null, null, null, null,
                        new ShipSpecEntry("vanguard_Attack", "Battering Ram", false, true,
                                "Bram", "Kovacs", "graphics/portraits/portrait_hegemony05.png", "aggressive", 4, null, false),
                        new ShipSpecEntry("vanguard_Strike", "Anvil Spur", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("lasher_Assault", "Cinder Hound", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("centurion_Assault", "Sentinel", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("hound_hegemony_Standard", "Iron Hound", false, false,
                                null, null, null, null, 0, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "frigate_3",
                        "Frigate Div. III",
                        DivisionArchetype.FRIGATE_DIVISION,
                        DivisionFormation.ECHELON_LEFT,
                        DivisionMissionType.SCREEN,
                        DivisionMissionType.SCREEN,
                        LineStation.CENTER,
                        null, null, null, null, null,
                        new ShipSpecEntry("wolf_hegemony_Assault", "Grey Wolf", false, true,
                                "Nora", "Vex", "graphics/portraits/portrait_hegemony08.png", "steady", 4, null, false),
                        new ShipSpecEntry("wolf_hegemony_CS", "Timber Wolf", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("wolf_hegemony_PD", "Watchdog", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("lasher_PD", "Flak Screen", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("centurion_Assault", "Warden", false, false,
                                null, null, null, null, 0, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "missile_frigate_1",
                        "Missile Frigate Div. I",
                        DivisionArchetype.MISSILE_FRIGATE_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.STANDOFF_ENGAGEMENT,
                        DivisionMissionType.TOT_ALPHA_STRIKE,
                        LineStation.CENTER,
                        null, null, null, null, null,
                        new ShipSpecEntry("vanguard_Strike", "Red Salvo", false, true,
                                "Corvus", "Hale", "graphics/portraits/portrait_hegemony11.png", "steady", 4, null, false),
                        new ShipSpecEntry("lasher_Strike", "Firebrand", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("brawler_Assault", "Slugger", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("vigilance_Strike", "Ballista", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("vigilance_FS", "Crossbow", false, false,
                                null, null, null, null, 0, null, false)
                ));
                break;

            case MIDLINE:
                // 6 Cruiser+ Carrier Hulls (6x Heron) across 2 Carrier Divisions + 20 Frigates across 4 Divisions
                list.add(new BakedDivisionTemplate(
                        "battle_1",
                        "Battle Div. I",
                        DivisionArchetype.BATTLESHIP_DIVISION,
                        DivisionFormation.LINE_AHEAD,
                        DivisionMissionType.STANDOFF_ENGAGEMENT,
                        DivisionMissionType.STANDOFF_ENGAGEMENT,
                        LineStation.VAN,
                        null, null, null, null, null,
                        new ShipSpecEntry("pegasus_Strike", "Polemarch", true, true,
                                "Cassian", "Rey", "graphics/portraits/portrait_league00.png", "steady", 6, null, false),
                        new ShipSpecEntry("conquest_Standard", "Sky Marshal", false, false,
                                "Gaius", "Valerius", "graphics/portraits/portrait_league03.png", "steady", 5, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "carrier_1",
                        "Light Carrier Div. I",
                        DivisionArchetype.LIGHT_CARRIER_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.ALPHA_STRIKE,
                        DivisionMissionType.CYCLIC_STRIKES,
                        LineStation.REAR,
                        VoidstrikeTargetObservance.HIGH,
                        VoidstrikeCoordinationMode.COORDINATED,
                        VoidstrikeAttackGeometry.ANVIL_CROSSFIRE,
                        PermanentDivisionManager.VoidstrikeSplitMode.SPLIT_AXES,
                        VoidstrikeGunneryMode.AREA_SATURATION,
                        VoidstrikeAoaCorrection.STRONG,
                        new ShipSpecEntry("heron_Strike", "Kazeron Wing", false, true,
                                "Lyra", "Solis", "graphics/portraits/portrait_league06.png", "steady", 5, null, false,
                                "dagger_wing", "dagger_wing", "broadsword_wing"),
                        new ShipSpecEntry("heron_Strike", "Mazalot Falcon", false, false,
                                "Corin", "Vance", "graphics/portraits/portrait_league09.png", "steady", 4, null, false,
                                "perdition_wing", "perdition_wing", "gladius_wing"),
                        new ShipSpecEntry("heron_Attack", "Thylea Lance", false, false,
                                null, null, null, null, 0, null, false,
                                "cobra_wing", "cobra_wing", "thunder_wing")
                ));
                list.add(new BakedDivisionTemplate(
                        "carrier_2",
                        "Light Carrier Div. II",
                        DivisionArchetype.LIGHT_CARRIER_DIVISION,
                        DivisionFormation.ECHELON_LEFT,
                        DivisionMissionType.ALPHA_STRIKE,
                        DivisionMissionType.CYCLIC_STRIKES,
                        LineStation.REAR,
                        VoidstrikeTargetObservance.HIGH,
                        VoidstrikeCoordinationMode.COORDINATED,
                        VoidstrikeAttackGeometry.PINCER_ENVELOPMENT,
                        PermanentDivisionManager.VoidstrikeSplitMode.SPLIT_AXES,
                        VoidstrikeGunneryMode.AREA_SATURATION,
                        VoidstrikeAoaCorrection.STRONG,
                        new ShipSpecEntry("heron_Strike", "Hesiod Flight", false, true,
                                "Orin", "Kael", "graphics/portraits/portrait_league11.png", "steady", 5, null, false,
                                "dagger_wing", "dagger_wing", "gladius_wing"),
                        new ShipSpecEntry("heron_Attack1", "Filken Condor", false, false,
                                null, null, null, null, 0, null, false,
                                "longbow_wing", "perdition_wing", "thunder_wing"),
                        new ShipSpecEntry("heron_Attack2", "Navarch Talon", false, false,
                                null, null, null, null, 0, null, false,
                                "perdition_wing", "perdition_wing", "claw_wing")
                ));
                list.add(new BakedDivisionTemplate(
                        "cruiser_1",
                        "Cruiser Div. I",
                        DivisionArchetype.CRUISER_DIVISION,
                        DivisionFormation.FORWARD_WEDGE,
                        DivisionMissionType.STANDOFF_ENGAGEMENT,
                        DivisionMissionType.CLOSE_ACTION,
                        LineStation.CENTER,
                        null, null, null, null, null,
                        new ShipSpecEntry("champion_Elite", "Corinthian", false, true,
                                "Helena", "Sterling", "graphics/portraits/portrait_league12.png", "aggressive", 5, null, false),
                        new ShipSpecEntry("gryphon_Standard", "Firestorm", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("eagle_Assault", "Aquila", false, false,
                                null, null, null, null, 0, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "destroyer_1",
                        "Destroyer Div. I",
                        DivisionArchetype.DESTROYER_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.SCREEN,
                        DivisionMissionType.ENVELOPMENT,
                        LineStation.CENTER,
                        null, null, null, null, null,
                        new ShipSpecEntry("hammerhead_Elite", "Halberd", false, true,
                                "Jarek", "Vex", "graphics/portraits/portrait_league14.png", "steady", 4, null, false),
                        new ShipSpecEntry("sunder_CS", "Sunlance", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("hammerhead_Balanced", "Partisan", false, false,
                                null, null, null, null, 0, null, false)
                ));
                // 20 Frigates split into 4 Persean League Doctrinal Divisions (5 hulls per division)
                list.add(new BakedDivisionTemplate(
                        "frigate_1",
                        "Frigate Div. I",
                        DivisionArchetype.FRIGATE_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.SCREEN,
                        DivisionMissionType.SCREEN,
                        LineStation.VAN,
                        null, null, null, null, null,
                        new ShipSpecEntry("monitor_Escort", "Aegis of Kazeron", false, true,
                                "Talia", "Vance", "graphics/portraits/portrait_league01.png", "steady", 4, null, false),
                        new ShipSpecEntry("centurion_Assault", "Hoplite", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("brawler_Elite", "Peltast", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("brawler_Assault", "Myrmidon", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("vigilance_Standard", "Skirmisher", false, false,
                                null, null, null, null, 0, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "frigate_2",
                        "Frigate Div. II",
                        DivisionArchetype.FRIGATE_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.SCREEN,
                        DivisionMissionType.SCREEN,
                        LineStation.VAN,
                        null, null, null, null, null,
                        new ShipSpecEntry("monitor_Escort", "Shield of Thylea", false, true,
                                "Damon", "Kael", "graphics/portraits/portrait_league04.png", "steady", 4, null, false),
                        new ShipSpecEntry("centurion_Assault", "Phalanx", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("brawler_Elite", "Sarissa", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("brawler_Support", "Dory", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("vigilance_AP", "Javelin", false, false,
                                null, null, null, null, 0, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "missile_frigate_1",
                        "Missile Frigate Div. I",
                        DivisionArchetype.MISSILE_FRIGATE_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.STANDOFF_ENGAGEMENT,
                        DivisionMissionType.TOT_ALPHA_STRIKE,
                        LineStation.CENTER,
                        null, null, null, null, null,
                        new ShipSpecEntry("vigilance_FS", "Cataphract", false, true,
                                "Sera", "Lin", "graphics/portraits/portrait_league07.png", "steady", 4, null, false),
                        new ShipSpecEntry("vigilance_Strike", "Scorpion", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("vigilance_Support1", "Onager", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("vigilance_Support2", "Mangonel", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("brawler_Support", "Arbalest", false, false,
                                null, null, null, null, 0, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "missile_frigate_2",
                        "Missile Frigate Div. II",
                        DivisionArchetype.MISSILE_FRIGATE_DIVISION,
                        DivisionFormation.ECHELON_RIGHT,
                        DivisionMissionType.STANDOFF_ENGAGEMENT,
                        DivisionMissionType.TOT_ALPHA_STRIKE,
                        LineStation.CENTER,
                        null, null, null, null, null,
                        new ShipSpecEntry("vigilance_FS", "Parthenon", false, true,
                                "Lucian", "Reyes", "graphics/portraits/portrait_league10.png", "steady", 4, null, false),
                        new ShipSpecEntry("vigilance_Strike", "Trebuchet", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("vigilance_Support1", "Culverin", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("vigilance_Standard", "Falconet", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("centurion_Assault", "Buckler", false, false,
                                null, null, null, null, 0, null, false)
                ));
                break;

            case HIGH_TECH:
                // 6 Cruiser+ Carrier Hulls (1x Astral, 1x Odyssey, 2x Heron, 2x Anubis) across 2 Carrier Divisions + 20 Frigates across 4 Divisions
                list.add(new BakedDivisionTemplate(
                        "battle_1",
                        "Asset Protection Div. 1",
                        DivisionArchetype.BATTLESHIP_DIVISION,
                        DivisionFormation.FORWARD_WEDGE,
                        DivisionMissionType.STANDOFF_ENGAGEMENT,
                        DivisionMissionType.CLOSE_ACTION,
                        LineStation.VAN,
                        null, null, null, null, null,
                        new ShipSpecEntry("paragon_Elite", "Quarterly Dividend", true, true,
                                "Evelyn", "Vane", "graphics/portraits/portrait_corporate01.png", "steady", 6, null, false),
                        new ShipSpecEntry("aurora_Balanced", "Hostile Takeover", false, false,
                                "Julian", "Krell", "graphics/portraits/portrait_corporate02.png", "aggressive", 5, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "carrier_1",
                        "Fleet Carrier Div. 1",
                        DivisionArchetype.FLEET_CARRIER_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.ALPHA_STRIKE,
                        DivisionMissionType.CYCLIC_STRIKES,
                        LineStation.REAR,
                        VoidstrikeTargetObservance.HIGH,
                        VoidstrikeCoordinationMode.COORDINATED,
                        VoidstrikeAttackGeometry.PINCER_ENVELOPMENT,
                        PermanentDivisionManager.VoidstrikeSplitMode.SPLIT_AXES,
                        VoidstrikeGunneryMode.AREA_SATURATION,
                        VoidstrikeAoaCorrection.STRONG,
                        new ShipSpecEntry("astral_Strike", "Cloud Matrix", false, true,
                                "Solara", "Chen", "graphics/portraits/portrait_corporate03.png", "steady", 5, null, false,
                                "dagger_wing", "dagger_wing", "trident_wing", "trident_wing", "longbow_wing", "broadsword_wing"),
                        new ShipSpecEntry("odyssey_Balanced", "Venture Vector", false, false,
                                "Soren", "Voss", "graphics/portraits/portrait_corporate04.png", "aggressive", 5, null, false,
                                "trident_wing", "xyphos_wing"),
                        new ShipSpecEntry("heron_Strike", "Paradigm Shift", false, false,
                                null, null, null, null, 0, null, false,
                                "trident_wing", "trident_wing", "wasp_wing")
                ));
                list.add(new BakedDivisionTemplate(
                        "carrier_2",
                        "Light Carrier Div. 2",
                        DivisionArchetype.LIGHT_CARRIER_DIVISION,
                        DivisionFormation.ECHELON_RIGHT,
                        DivisionMissionType.ALPHA_STRIKE,
                        DivisionMissionType.CYCLIC_STRIKES,
                        LineStation.REAR,
                        VoidstrikeTargetObservance.HIGH,
                        VoidstrikeCoordinationMode.COORDINATED,
                        VoidstrikeAttackGeometry.PINCER_ENVELOPMENT,
                        PermanentDivisionManager.VoidstrikeSplitMode.SPLIT_TARGETS,
                        VoidstrikeGunneryMode.AREA_SATURATION,
                        VoidstrikeAoaCorrection.STRONG,
                        new ShipSpecEntry("heron_Strike", "Market Correction", false, true,
                                "Aris", "Thorne", "graphics/portraits/portrait_corporate05.png", "steady", 5, null, false,
                                "dagger_wing", "dagger_wing", "longbow_wing"),
                        new ShipSpecEntry("anubis_Standard", "Synergistic Alpha", false, false,
                                null, null, null, null, 0, null, true,
                                "dagger_wing", "xyphos_wing"),
                        new ShipSpecEntry("anubis_Standard", "Golden Parachute", false, false,
                                null, null, null, null, 0, null, true,
                                "cobra_wing", "wasp_wing")
                ));
                list.add(new BakedDivisionTemplate(
                        "cruiser_1",
                        "Strike Cruiser Div. 1",
                        DivisionArchetype.STRIKE_CRUISER_DIVISION,
                        DivisionFormation.FORWARD_WEDGE,
                        DivisionMissionType.ENVELOPMENT,
                        DivisionMissionType.CLOSE_ACTION,
                        LineStation.CENTER,
                        null, null, null, null, null,
                        new ShipSpecEntry("fury_Attack", "Fiduciary Duty", false, true,
                                "Celine", "Vane", "graphics/portraits/portrait_corporate06.png", "aggressive", 5, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "destroyer_1",
                        "Destroyer Div. 1",
                        DivisionArchetype.DESTROYER_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.SCREEN,
                        DivisionMissionType.ENVELOPMENT,
                        LineStation.CENTER,
                        null, null, null, null, null,
                        new ShipSpecEntry("medusa_Attack", "Margin Call", false, true,
                                "Kira", "Jax", "graphics/portraits/portrait_corporate07.png", "aggressive", 4, null, false),
                        new ShipSpecEntry("medusa_CS", "Severance", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("shrike_Attack", "Liquidation", false, false,
                                null, null, null, null, 0, null, false)
                ));
                // 20 Frigates split into 4 Tri-Tachyon Doctrinal Divisions (5 hulls per division)
                list.add(new BakedDivisionTemplate(
                        "frigate_1",
                        "Frigate Div. 1",
                        DivisionArchetype.FRIGATE_DIVISION,
                        DivisionFormation.REVERSE_SEMICIRCLE,
                        DivisionMissionType.ARMED_RECONNAISSANCE,
                        DivisionMissionType.ENVELOPMENT,
                        LineStation.VAN,
                        null, null, null, null, null,
                        new ShipSpecEntry("scarab_Experimental", "High Frequency", false, true,
                                "Vesper", "Cole", "graphics/portraits/portrait_corporate08.png", "aggressive", 4, null, false),
                        new ShipSpecEntry("tempest_Attack", "Flash Crash", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("wolf_Assault", "Short Squeeze", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("wolf_Strike", "Call Option", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("omen_PD", "Firewall", false, false,
                                null, null, null, null, 0, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "frigate_2",
                        "Frigate Div. 2",
                        DivisionArchetype.FRIGATE_DIVISION,
                        DivisionFormation.REVERSE_SEMICIRCLE,
                        DivisionMissionType.ARMED_RECONNAISSANCE,
                        DivisionMissionType.ENVELOPMENT,
                        LineStation.VAN,
                        null, null, null, null, null,
                        new ShipSpecEntry("hyperion_Attack", "Apex Predator", false, true,
                                "Zane", "Mercer", "graphics/portraits/portrait_corporate09.png", "aggressive", 4, null, false),
                        new ShipSpecEntry("tempest_Attack", "Alpha Seeker", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("wolf_Overdriven", "Leverage", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("wolf_CS", "Arbitrage", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("omen_PD", "Encryption", false, false,
                                null, null, null, null, 0, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "missile_frigate_1",
                        "Missile Frigate Div. 1",
                        DivisionArchetype.MISSILE_FRIGATE_DIVISION,
                        DivisionFormation.REVERSE_SEMICIRCLE,
                        DivisionMissionType.STANDOFF_ENGAGEMENT,
                        DivisionMissionType.TOT_ALPHA_STRIKE,
                        LineStation.CENTER,
                        null, null, null, null, null,
                        new ShipSpecEntry("hyperion_Strike", "Black Swan", false, true,
                                "Iris", "Vance", "graphics/portraits/portrait_corporate10.png", "steady", 4, null, false),
                        new ShipSpecEntry("tempest_Attack", "Volatiles", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("wolf_Strike", "Stop Loss", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("wolf_Strike", "Put Option", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("brawler_tritachyon_Standard", "Liquidator", false, false,
                                null, null, null, null, 0, null, false)
                ));
                list.add(new BakedDivisionTemplate(
                        "phase_frigate_1",
                        "Phase Div. 1",
                        DivisionArchetype.PHASE_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.ARMED_RECONNAISSANCE,
                        DivisionMissionType.ENVELOPMENT,
                        LineStation.VAN,
                        null, null, null, null, null,
                        new ShipSpecEntry("afflictor_Strike", "Dark Pool", false, true,
                                "Nyx", "Sato", "graphics/portraits/portrait_corporate01.png", "aggressive", 4, null, false),
                        new ShipSpecEntry("afflictor_Strike", "Shadow Account", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("shade_Assault", "Insider Trade", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("shade_Assault", "Offshore", false, false,
                                null, null, null, null, 0, null, false),
                        new ShipSpecEntry("afflictor_Strike", "Shell Company", false, false,
                                null, null, null, null, 0, null, false)
                ));
                break;

            case REMNANT:
            default:
                // 6 Cruiser+ Carrier Hulls (2x Automated Legion XIV + 4x Apex) + 2x Scintilla Carriers + 20 Frigates across 4 Fragments
                list.add(new BakedDivisionTemplate(
                        "ordo_battle_1",
                        "Heavy Sub-Ordo 1",
                        DivisionArchetype.BATTLESHIP_DIVISION,
                        DivisionFormation.FORWARD_WEDGE,
                        DivisionMissionType.STANDOFF_ENGAGEMENT,
                        DivisionMissionType.CLOSE_ACTION,
                        LineStation.VAN,
                        null, null, null, null, null,
                        new ShipSpecEntry("radiant_Standard", "Zero-Sum Recursion", true, true,
                                "Alpha", "Core", "graphics/portraits/portrait_ai2b.png", "aggressive", 7, "alpha_core", false),
                        new ShipSpecEntry("nova_Standard", "Terminal Asymptote", false, false,
                                "Alpha", "Core", "graphics/portraits/portrait_ai2b.png", "aggressive", 7, "alpha_core", false)
                ));
                list.add(new BakedDivisionTemplate(
                        "ordo_carrier_1",
                        "Carrier Sub-Ordo 1",
                        DivisionArchetype.FLEET_CARRIER_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.ALPHA_STRIKE,
                        DivisionMissionType.CYCLIC_STRIKES,
                        LineStation.REAR,
                        VoidstrikeTargetObservance.HIGH,
                        VoidstrikeCoordinationMode.COORDINATED,
                        VoidstrikeAttackGeometry.PINCER_ENVELOPMENT,
                        PermanentDivisionManager.VoidstrikeSplitMode.SPLIT_TARGETS,
                        VoidstrikeGunneryMode.AREA_SATURATION,
                        VoidstrikeAoaCorrection.STRONG,
                        new ShipSpecEntry("legion_xiv_Automated", "Infinite Manifold", false, true,
                                "Alpha", "Core", "graphics/portraits/portrait_ai2b.png", "steady", 7, "alpha_core", false,
                                "flash_wing", "flash_wing", "flash_wing", "spark_wing"),
                        new ShipSpecEntry("apex_Standard", "Eigenvalue Prime", false, false,
                                "Beta", "Core", "graphics/portraits/portrait_ai3b.png", "steady", 6, "beta_core", true,
                                "flash_wing"),
                        new ShipSpecEntry("apex_Assault", "Null Hypothesis", false, false,
                                "Beta", "Core", "graphics/portraits/portrait_ai3b.png", "steady", 6, "beta_core", true,
                                "flash_wing"),
                        new ShipSpecEntry("scintilla_Strike", "Swarm Node Alpha", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false,
                                "flash_wing", "lux_wing")
                ));
                list.add(new BakedDivisionTemplate(
                        "ordo_carrier_2",
                        "Carrier Sub-Ordo 2",
                        DivisionArchetype.LIGHT_CARRIER_DIVISION,
                        DivisionFormation.ECHELON_LEFT,
                        DivisionMissionType.ALPHA_STRIKE,
                        DivisionMissionType.CYCLIC_STRIKES,
                        LineStation.REAR,
                        VoidstrikeTargetObservance.HIGH,
                        VoidstrikeCoordinationMode.COORDINATED,
                        VoidstrikeAttackGeometry.PINCER_ENVELOPMENT,
                        PermanentDivisionManager.VoidstrikeSplitMode.SPLIT_AXES,
                        VoidstrikeGunneryMode.AREA_SATURATION,
                        VoidstrikeAoaCorrection.STRONG,
                        new ShipSpecEntry("legion_xiv_Automated", "Singular Horizon", false, true,
                                "Alpha", "Core", "graphics/portraits/portrait_ai2b.png", "steady", 7, "alpha_core", false,
                                "flash_wing", "flash_wing", "flash_wing", "lux_wing"),
                        new ShipSpecEntry("apex_Standard", "Silent Parallax", false, false,
                                "Beta", "Core", "graphics/portraits/portrait_ai3b.png", "steady", 6, "beta_core", true,
                                "flash_wing"),
                        new ShipSpecEntry("apex_Assault", "Radiant Vector", false, false,
                                "Beta", "Core", "graphics/portraits/portrait_ai3b.png", "steady", 6, "beta_core", true,
                                "flash_wing"),
                        new ShipSpecEntry("scintilla_Support", "Swarm Node Beta", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false,
                                "flash_wing", "spark_wing")
                ));
                list.add(new BakedDivisionTemplate(
                        "ordo_destroyer_1",
                        "Destroyer Fragment 1",
                        DivisionArchetype.DESTROYER_DIVISION,
                        DivisionFormation.LINE_ABREAST,
                        DivisionMissionType.SCREEN,
                        DivisionMissionType.SCREEN,
                        LineStation.CENTER,
                        null, null, null, null, null,
                        new ShipSpecEntry("fulgent_Assault", "Phase Lattice", false, true,
                                "Beta", "Core", "graphics/portraits/portrait_ai3b.png", "aggressive", 6, "beta_core", false),
                        new ShipSpecEntry("fulgent_Support", "Flux Conduit", false, false,
                                "Beta", "Core", "graphics/portraits/portrait_ai3b.png", "steady", 6, "beta_core", false)
                ));
                // 20 Remnant Frigates split into 4 Doctrinal Frigate Fragments (5 hulls per Fragment in Protective Circle)
                list.add(new BakedDivisionTemplate(
                        "ordo_frigate_1",
                        "Frigate Fragment 1",
                        DivisionArchetype.FRIGATE_DIVISION,
                        DivisionFormation.PROTECTIVE_CIRCLE,
                        DivisionMissionType.SCREEN,
                        DivisionMissionType.SCREEN,
                        LineStation.VAN,
                        null, null, null, null, null,
                        new ShipSpecEntry("glimmer_Assault", "Photon Shard 1", false, true,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "aggressive", 5, "gamma_core", false),
                        new ShipSpecEntry("glimmer_Assault", "Photon Shard 2", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "aggressive", 5, "gamma_core", false),
                        new ShipSpecEntry("glimmer_Support", "Photon Shard 3", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false),
                        new ShipSpecEntry("glimmer_Support", "Photon Shard 4", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false),
                        new ShipSpecEntry("lumen_Standard", "Quanta 1", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false)
                ));
                list.add(new BakedDivisionTemplate(
                        "ordo_frigate_2",
                        "Frigate Fragment 2",
                        DivisionArchetype.FRIGATE_DIVISION,
                        DivisionFormation.PROTECTIVE_CIRCLE,
                        DivisionMissionType.SCREEN,
                        DivisionMissionType.SCREEN,
                        LineStation.VAN,
                        null, null, null, null, null,
                        new ShipSpecEntry("glimmer_Assault", "Photon Shard 5", false, true,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "aggressive", 5, "gamma_core", false),
                        new ShipSpecEntry("glimmer_Assault", "Photon Shard 6", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "aggressive", 5, "gamma_core", false),
                        new ShipSpecEntry("glimmer_Support", "Photon Shard 7", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false),
                        new ShipSpecEntry("glimmer_Support", "Photon Shard 8", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false),
                        new ShipSpecEntry("lumen_Standard", "Quanta 2", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false)
                ));
                list.add(new BakedDivisionTemplate(
                        "ordo_frigate_3",
                        "Frigate Fragment 3",
                        DivisionArchetype.FRIGATE_DIVISION,
                        DivisionFormation.PROTECTIVE_CIRCLE,
                        DivisionMissionType.SCREEN,
                        DivisionMissionType.CLOSE_ACTION,
                        LineStation.CENTER,
                        null, null, null, null, null,
                        new ShipSpecEntry("glimmer_Assault", "Wavefront 1", false, true,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "aggressive", 5, "gamma_core", false),
                        new ShipSpecEntry("glimmer_Support", "Wavefront 2", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false),
                        new ShipSpecEntry("lumen_Standard", "Quanta 3", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false),
                        new ShipSpecEntry("lumen_Standard", "Quanta 4", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false),
                        new ShipSpecEntry("lumen_Standard", "Quanta 5", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false)
                ));
                list.add(new BakedDivisionTemplate(
                        "ordo_frigate_4",
                        "Frigate Fragment 4",
                        DivisionArchetype.FRIGATE_DIVISION,
                        DivisionFormation.PROTECTIVE_CIRCLE,
                        DivisionMissionType.SCREEN,
                        DivisionMissionType.CLOSE_ACTION,
                        LineStation.CENTER,
                        null, null, null, null, null,
                        new ShipSpecEntry("glimmer_Assault", "Wavefront 3", false, true,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "aggressive", 5, "gamma_core", false),
                        new ShipSpecEntry("glimmer_Support", "Wavefront 4", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false),
                        new ShipSpecEntry("lumen_Standard", "Quanta 6", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false),
                        new ShipSpecEntry("lumen_Standard", "Quanta 7", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false),
                        new ShipSpecEntry("lumen_Standard", "Quanta 8", false, false,
                                "Gamma", "Core", "graphics/portraits/portrait_ai1b.png", "steady", 5, "gamma_core", false)
                ));
                break;
        }
        return list;
    }
}
