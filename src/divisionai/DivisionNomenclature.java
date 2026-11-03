package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import divisionai.DivisionModel.DivisionArchetype;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Multi-faction nomenclature system and player division naming customization.
 *
 * Faction Nomenclature Categories:
 *  - HUMAN_NAVAL:
 *      Standard Naval divisions (Battle Division, Carrier Division, Cruiser Division,
 *      Destroyer Division, Frigate Division, Phase Division, Service Group).
 *  - POST_AI_WAR (remnant, derelict):
 *      OTC Commander is "ORDO COGITATOR/MASTER".
 *      Because vanilla lore has no tactical unit echelons below campaign-level Ordo/Sub-Ordo/Fragment,
 *      all divisions display "UNKNOWN".
 *  - THREAT_SWARM (threat):
 *      OTC Commander is "INTERDICTORATE PROACT: UNFABRICATE" (per rules.csv Threat comm-link).
 *      Per descriptions.csv ("no fixed designation, no identity... defined by function alone"),
 *      divisions use canonical Threat functional designations (Standoff Unit Type 300, Hive Unit Type 350,
 *      Overseer Unit Type 250, Assault Unit Type 200, Skirmish Unit Type 100, Fabricator Unit Type 450,
 *      Reclamation Swarm) with archaic Domain comm/infosec telemetry cycling.
 *  - ABYSSAL_ERROR (dweller, omega):
 *      "ERROR" everywhere (OTC Commander, division names, intent readouts, and campaign tooltips).
 */
public final class DivisionNomenclature {

    private static final String PERSISTENT_PLAYER_NAMING_KEY = "divisionai_player_naming_v1";
    public static final String PERSISTENT_PLAYER_FACTION_NAMING_KEY = "divisionai_player_faction_naming_v1";

    private static PlayerNamingConfig inMemoryPlayerNamingFallback = new PlayerNamingConfig();
    private static PlayerNamingConfig inMemoryAppliedPlayerFactionNaming = null;

    public static final String POST_AI_WAR_OTC_TITLE = "ORDO COGITATOR/MASTER";
    public static final String POST_AI_WAR_DIVISION_NAME = "UNKNOWN";
    public static final String THREAT_OTC_TITLE = "INTERDICTORATE PROACT: UNFABRICATE";
    public static final String ABYSSAL_ERROR_TEXT = "ERROR";

    public enum NomenclatureCategory {
        HUMAN_NAVAL("Human Naval Standard"),
        POST_AI_WAR("Post-AI War AI (ORDO COGITATOR/MASTER / UNKNOWN)"),
        THREAT_SWARM("The Threat (INTERDICTORATE PROACT: UNFABRICATE / Functional Units)"),
        ABYSSAL_ERROR("Abyssal / Otherworldly (ERROR)");

        public final String displayName;

        NomenclatureCategory(String displayName) {
            this.displayName = displayName;
        }
    }

    public enum NumberingStyle {
        ARABIC("Arabic (1, 2)", "Battle Div 1, Battle Div 2."),
        ROMAN("Roman (I, II)", "Battle Div I, Battle Div II."),
        ORDINAL_PREFIX("Ordinal (1st, 2nd)", "1st Battle Div, 2nd Battle Div."),
        PHONETIC("Callsign (Alpha)", "Battle Div Alpha, Battle Div Beta."),
        NONE("None", "Base title only.");

        public final String displayName;
        public final String summary;

        NumberingStyle(String displayName, String summary) {
            this.displayName = displayName;
            this.summary = summary;
        }

        public static NumberingStyle fromId(String id, NumberingStyle fallback) {
            if (id == null) return fallback;
            for (NumberingStyle s : values()) {
                if (s.name().equalsIgnoreCase(id.trim())) {
                    return s;
                }
            }
            return fallback;
        }
    }

    public enum PlayerNamingPreset {
        NAVAL_STANDARD(
                "Naval Standard",
                "Battle Div, Carrier Div, Line Cruiser Div, Destroyer Div, Frigate Div.",
                NomenclatureCategory.HUMAN_NAVAL,
                NumberingStyle.ARABIC,
                "Fleet Command",
                true),
        CLASSIC_NAVAL(
                "Detailed Naval",
                "Battleship Div, Fleet/Light/Escort Carrier Div, Strike Cruiser Div.",
                NomenclatureCategory.HUMAN_NAVAL,
                NumberingStyle.ARABIC,
                "Flag Command",
                true),
        DOMAIN_ARMADA(
                "Domain / Hegemony Armada",
                "Battle Line, Carrier Wing, Cruiser Squadron, Destroyer Flotilla, Picket.",
                NomenclatureCategory.HUMAN_NAVAL,
                NumberingStyle.ROMAN,
                "Armada Command",
                true),
        TRITACHYON_CORPORATE(
                "Tri-Tachyon Corporate",
                "Capital Asset Group, Flight Group, Assurance Unit, Special Affairs.",
                NomenclatureCategory.HUMAN_NAVAL,
                NumberingStyle.PHONETIC,
                "Corporate Command",
                true),
        PERSEAN_NAVARCHY(
                "Persean League Navarchy",
                "Battle Phalanx, Carrier Group, Cruiser Mora, Destroyer Lochos, Peltast.",
                NomenclatureCategory.HUMAN_NAVAL,
                NumberingStyle.ROMAN,
                "Navarch Command",
                true),
        SINDRIAN_GUARD(
                "Sindrian Diktat / Lion's Guard",
                "Executor Line, Solar Wing, Cruiser Phalanx, Vanguard Flotilla, Picket Guard.",
                NomenclatureCategory.HUMAN_NAVAL,
                NumberingStyle.ROMAN,
                "Supreme Command",
                true),
        LUDDIC_ARMADA(
                "Luddic Armada",
                "Hallowed Bastion, Ark Host, Righteous Hammer, Sword Flotilla, Watchers.",
                NomenclatureCategory.HUMAN_NAVAL,
                NumberingStyle.ROMAN,
                "Knight-Command",
                true),
        PIRATE_WOLFPACK(
                "Pirate / Free Captain",
                "Dreadnought Pack, Flattop Pack, Raider Pack, Hunter Pack, Wolfpack.",
                NomenclatureCategory.HUMAN_NAVAL,
                NumberingStyle.ORDINAL_PREFIX,
                "Warband Command",
                true),
        REMNANT_ORDO(
                "Remnant AI Echelons",
                "Heavy Sub-Ordo, Carrier Sub-Ordo, Cruiser Sub-Ordo, Fragment.",
                NomenclatureCategory.HUMAN_NAVAL,
                NumberingStyle.ROMAN,
                POST_AI_WAR_OTC_TITLE,
                false),
        POST_AI_WAR_UNKNOWN(
                "Post-AI War ('UNKNOWN')",
                "OTC is 'ORDO COGITATOR/MASTER'; divisions display 'UNKNOWN'.",
                NomenclatureCategory.POST_AI_WAR,
                NumberingStyle.NONE,
                POST_AI_WAR_OTC_TITLE,
                false),
        THREAT_CORRUPTED(
                "The Threat (Functional Units)",
                "OTC is 'INTERDICTORATE PROACT: UNFABRICATE'; Standoff/Hive/Overseer/Assault/Skirmish Units.",
                NomenclatureCategory.THREAT_SWARM,
                NumberingStyle.NONE,
                THREAT_OTC_TITLE,
                false),
        ABYSSAL_ERROR(
                "Abyssal ('ERROR')",
                "OTC and all divisions display 'ERROR'.",
                NomenclatureCategory.ABYSSAL_ERROR,
                NumberingStyle.NONE,
                ABYSSAL_ERROR_TEXT,
                false);

        public final String displayName;
        public final String summary;
        public final NomenclatureCategory category;
        public final NumberingStyle defaultNumbering;
        public final String defaultOtcTitle;
        public final boolean playerSelectable;

        PlayerNamingPreset(
                String displayName,
                String summary,
                NomenclatureCategory category,
                NumberingStyle defaultNumbering,
                String defaultOtcTitle,
                boolean playerSelectable) {
            this.displayName = displayName;
            this.summary = summary;
            this.category = category;
            this.defaultNumbering = defaultNumbering;
            this.defaultOtcTitle = defaultOtcTitle;
            this.playerSelectable = playerSelectable;
        }

        public static List<PlayerNamingPreset> getPlayerSelectablePresets() {
            java.util.ArrayList<PlayerNamingPreset> list = new java.util.ArrayList<>();
            for (PlayerNamingPreset p : values()) {
                if (p.playerSelectable) {
                    list.add(p);
                }
            }
            return list;
        }

        public static PlayerNamingPreset fromId(String id, PlayerNamingPreset fallback) {
            if (id == null) return fallback;
            for (PlayerNamingPreset p : values()) {
                if (p.name().equalsIgnoreCase(id.trim())) {
                    return p;
                }
            }
            return fallback;
        }
    }

    public static final class PlayerNamingConfig implements Serializable {
        private static final long serialVersionUID = 1L;

        private String presetId = PlayerNamingPreset.NAVAL_STANDARD.name();
        private String numberingStyleId = NumberingStyle.ARABIC.name();
        private String customOtcTitle = "";
        private Map<String, String> customPrefixes = new LinkedHashMap<>();
        private transient Map<String, Object> backingMap = null;

        public PlayerNamingPreset getPreset() {
            PlayerNamingPreset p = PlayerNamingPreset.fromId(presetId, PlayerNamingPreset.NAVAL_STANDARD);
            return (p != null && p.playerSelectable) ? p : PlayerNamingPreset.NAVAL_STANDARD;
        }

        public void setPreset(PlayerNamingPreset preset) {
            if (preset == null || !preset.playerSelectable) preset = PlayerNamingPreset.NAVAL_STANDARD;
            this.presetId = preset.name();
            this.numberingStyleId = preset.defaultNumbering.name();
            syncToBackingMap();
        }

        public NumberingStyle getNumberingStyle() {
            return NumberingStyle.fromId(numberingStyleId, getPreset().defaultNumbering);
        }

        public void setNumberingStyle(NumberingStyle style) {
            if (style != null) {
                this.numberingStyleId = style.name();
                syncToBackingMap();
            }
        }

        public String getCustomOtcTitle() {
            return customOtcTitle != null ? customOtcTitle.trim() : "";
        }

        public void setCustomOtcTitle(String title) {
            this.customOtcTitle = title != null ? title.trim() : "";
            syncToBackingMap();
        }

        public String getPrefixForArchetype(DivisionArchetype archetype) {
            if (archetype == null) archetype = DivisionArchetype.CRUISER_DIVISION;
            if (customPrefixes != null) {
                String custom = customPrefixes.get(archetype.name());
                if (custom != null && !custom.trim().isEmpty()) {
                    return custom.trim();
                }
            }
            return getDefaultPrefixForPreset(getPreset(), archetype);
        }


        public void setCustomPrefixForArchetype(DivisionArchetype archetype, String prefix) {
            if (archetype == null) return;
            if (customPrefixes == null) {
                customPrefixes = new LinkedHashMap<>();
            }
            if (prefix == null || prefix.trim().isEmpty()) {
                customPrefixes.remove(archetype.name());
            } else {
                customPrefixes.put(archetype.name(), prefix.trim());
            }
            syncToBackingMap();
        }

        public void clearCustomPrefixes() {
            if (customPrefixes != null) {
                customPrefixes.clear();
            }
            customOtcTitle = "";
            syncToBackingMap();
        }

        public PlayerNamingConfig copy() {
            PlayerNamingConfig cp = new PlayerNamingConfig();
            cp.presetId = this.presetId;
            cp.numberingStyleId = this.numberingStyleId;
            cp.customOtcTitle = this.customOtcTitle;
            if (this.customPrefixes != null) {
                cp.customPrefixes.putAll(this.customPrefixes);
            }
            return cp;
        }

        public Map<String, Object> toPlainMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            writeFieldsToMap(map);
            return map;
        }

        private void writeFieldsToMap(Map<String, Object> map) {
            if (map == null) return;
            map.put("presetId", presetId != null ? presetId : PlayerNamingPreset.NAVAL_STANDARD.name());
            map.put("numberingStyleId", numberingStyleId != null ? numberingStyleId : NumberingStyle.ARABIC.name());
            map.put("customOtcTitle", customOtcTitle != null ? customOtcTitle : "");
            Map<String, String> prefs = new LinkedHashMap<>();
            if (customPrefixes != null) {
                prefs.putAll(customPrefixes);
            }
            map.put("customPrefixes", prefs);
        }

        private void syncToBackingMap() {
            if (backingMap != null) {
                writeFieldsToMap(backingMap);
            }
        }

        @SuppressWarnings("unchecked")
        public static PlayerNamingConfig fromPlainMap(Map<String, Object> map) {
            PlayerNamingConfig cfg = new PlayerNamingConfig();
            if (map == null) return cfg;
            Object pid = map.get("presetId");
            if (pid instanceof String) cfg.presetId = (String) pid;
            Object nid = map.get("numberingStyleId");
            if (nid instanceof String) cfg.numberingStyleId = (String) nid;
            Object otc = map.get("customOtcTitle");
            if (otc instanceof String) cfg.customOtcTitle = (String) otc;
            Object prefs = map.get("customPrefixes");
            if (prefs instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) prefs).entrySet()) {
                    if (e.getKey() instanceof String && e.getValue() instanceof String) {
                        cfg.customPrefixes.put((String) e.getKey(), (String) e.getValue());
                    }
                }
            }
            cfg.backingMap = map;
            return cfg;
        }

        /**
         * XStream legacy-save migration hook: if an older save serialized a PlayerNamingConfig
         * instance directly into SectorAPI.getPersistentData(), immediately resolve it to a plain
         * JDK Map<String, Object> during XML unmarshalling.
         */
        private Object readResolve() {
            return toPlainMap();
        }
    }

    private DivisionNomenclature() {
    }

    private static Object cachedPlayerNamingRawRef = null;
    private static PlayerNamingConfig cachedPlayerNamingConfig = null;

    @SuppressWarnings("unchecked")
    public static PlayerNamingConfig getPlayerNamingConfig() {
        SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPersistentData() == null) {
            return inMemoryPlayerNamingFallback;
        }
        Map<String, Object> data = sector.getPersistentData();
        Object raw = data.get(PERSISTENT_PLAYER_NAMING_KEY);
        if (raw instanceof Map) {
            if (raw == cachedPlayerNamingRawRef && cachedPlayerNamingConfig != null) {
                return cachedPlayerNamingConfig;
            }
            PlayerNamingConfig cfg = PlayerNamingConfig.fromPlainMap((Map<String, Object>) raw);
            cachedPlayerNamingRawRef = raw;
            cachedPlayerNamingConfig = cfg;
            return cfg;
        }
        if (raw instanceof PlayerNamingConfig) {
            PlayerNamingConfig legacy = (PlayerNamingConfig) raw;
            Map<String, Object> plain = legacy.toPlainMap();
            data.put(PERSISTENT_PLAYER_NAMING_KEY, plain);
            legacy.backingMap = plain;
            cachedPlayerNamingRawRef = plain;
            cachedPlayerNamingConfig = legacy;
            return legacy;
        }
        PlayerNamingConfig fresh = new PlayerNamingConfig();
        Map<String, Object> plain = fresh.toPlainMap();
        fresh.backingMap = plain;
        data.put(PERSISTENT_PLAYER_NAMING_KEY, plain);
        cachedPlayerNamingRawRef = plain;
        cachedPlayerNamingConfig = fresh;
        return fresh;
    }

    /**
     * Copies the player's current Fleet Nomenclature (preset, numbering style, and custom prefixes)
     * to the player's personal faction ("player") for autonomous colony fleets.
     */
    public static PlayerNamingConfig applyPlayerNamingConfigToPersonalFaction() {
        PlayerNamingConfig snapshot = getPlayerNamingConfig().copy();
        inMemoryAppliedPlayerFactionNaming = snapshot;
        SectorAPI sector = Global.getSector();
        if (sector != null && sector.getPersistentData() != null) {
            Map<String, Object> plain = snapshot.toPlainMap();
            snapshot.backingMap = plain;
            sector.getPersistentData().put(PERSISTENT_PLAYER_FACTION_NAMING_KEY, plain);
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    public static PlayerNamingConfig getAppliedPlayerFactionNamingConfig() {
        SectorAPI sector = Global.getSector();
        if (sector != null && sector.getPersistentData() != null) {
            Map<String, Object> data = sector.getPersistentData();
            Object raw = data.get(PERSISTENT_PLAYER_FACTION_NAMING_KEY);
            if (raw instanceof Map) {
                return PlayerNamingConfig.fromPlainMap((Map<String, Object>) raw);
            }
            if (raw instanceof PlayerNamingConfig) {
                PlayerNamingConfig legacy = (PlayerNamingConfig) raw;
                Map<String, Object> plain = legacy.toPlainMap();
                data.put(PERSISTENT_PLAYER_FACTION_NAMING_KEY, plain);
                legacy.backingMap = plain;
                return legacy;
            }
        }
        return inMemoryAppliedPlayerFactionNaming;
    }

    public static void sanitizePersistentNamingData(SectorAPI sector) {
        if (sector == null || sector.getPersistentData() == null) return;
        Map<String, Object> data = sector.getPersistentData();
        Object raw1 = data.get(PERSISTENT_PLAYER_NAMING_KEY);
        if (raw1 instanceof PlayerNamingConfig) {
            data.put(PERSISTENT_PLAYER_NAMING_KEY, ((PlayerNamingConfig) raw1).toPlainMap());
        }
        Object raw2 = data.get(PERSISTENT_PLAYER_FACTION_NAMING_KEY);
        if (raw2 instanceof PlayerNamingConfig) {
            data.put(PERSISTENT_PLAYER_FACTION_NAMING_KEY, ((PlayerNamingConfig) raw2).toPlainMap());
        }
    }


    public static void clearAppliedPlayerFactionNamingConfigForTests() {
        inMemoryAppliedPlayerFactionNaming = null;
        SectorAPI sector = Global.getSector();
        if (sector != null && sector.getPersistentData() != null) {
            sector.getPersistentData().remove(PERSISTENT_PLAYER_FACTION_NAMING_KEY);
        }
    }

    public static NomenclatureCategory getPlayerNomenclatureCategory() {
        return getPlayerNamingConfig().getPreset().category;
    }

    /**
     * Resolves the nomenclature category for a faction ID string.
     */
    public static NomenclatureCategory resolveCategoryForFactionId(String factionId) {
        if (factionId == null) return NomenclatureCategory.HUMAN_NAVAL;
        FactionLibrary.FactionAttributes attrs = FactionLibrary.getAttributesForFaction(factionId);
        if (attrs != null && attrs.nomenclatureCategory != null) {
            return attrs.nomenclatureCategory;
        }
        return NomenclatureCategory.HUMAN_NAVAL;
    }

    /**
     * Resolves the nomenclature category for a campaign fleet (inspecting both faction ID
     * and member hull tags so special encounter fleets using neutral faction are properly classified).
     */
    public static NomenclatureCategory resolveCategoryForCampaignFleet(CampaignFleetAPI fleet) {
        if (fleet == null) return NomenclatureCategory.HUMAN_NAVAL;
        if (fleet.isPlayerFleet()) {
            return getPlayerNomenclatureCategory();
        }
        FactionAPI faction = fleet.getFaction();
        String fId = (faction != null && faction.getId() != null) ? faction.getId().trim() : null;
        if ("player".equalsIgnoreCase(fId)) {
            PlayerNamingConfig applied = getAppliedPlayerFactionNamingConfig();
            return applied != null ? applied.getPreset().category : getPlayerNomenclatureCategory();
        }
        if (fId != null) {
            NomenclatureCategory byFaction = resolveCategoryForFactionId(fId);
            if (byFaction != NomenclatureCategory.HUMAN_NAVAL) {
                return byFaction;
            }
        }
        if (fleet.getFleetData() != null && isNeutralOrUnknownFactionId(fId)) {
            List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
            return resolveCategoryForFleetMembers(members, fId);
        }
        return NomenclatureCategory.HUMAN_NAVAL;
    }

    private static boolean isNeutralOrUnknownFactionId(String factionId) {
        if (factionId == null) return true;
        String id = factionId.trim().toLowerCase(Locale.ROOT);
        return id.isEmpty() || "neutral".equals(id) || "unknown".equals(id);
    }

    public static NomenclatureCategory resolveCategoryForFleetMembers(
            Collection<FleetMemberAPI> members,
            String factionIdHint) {
        if ("player".equalsIgnoreCase(factionIdHint)) {
            PlayerNamingConfig applied = getAppliedPlayerFactionNamingConfig();
            return applied != null ? applied.getPreset().category : getPlayerNomenclatureCategory();
        }
        NomenclatureCategory byFaction = resolveCategoryForFactionId(factionIdHint);
        if (byFaction != NomenclatureCategory.HUMAN_NAVAL) {
            return byFaction;
        }
        if (!isNeutralOrUnknownFactionId(factionIdHint)) {
            return NomenclatureCategory.HUMAN_NAVAL;
        }
        if (members == null || members.isEmpty()) {
            return NomenclatureCategory.HUMAN_NAVAL;
        }
        List<ShipHullSpecAPI> specs = new ArrayList<>();
        for (FleetMemberAPI m : members) {
            if (m != null && !m.isFighterWing()) specs.add(m.getHullSpec());
        }
        return resolveCategoryFromHullSpecs(specs);
    }

    public static NomenclatureCategory resolveCategoryForCombatShips(
            Collection<ShipAPI> ships,
            String factionIdHint,
            boolean isPlayerBattleGroup) {
        if (isPlayerBattleGroup) {
            return getPlayerNomenclatureCategory();
        }
        if ("player".equalsIgnoreCase(factionIdHint)) {
            PlayerNamingConfig applied = getAppliedPlayerFactionNamingConfig();
            return applied != null ? applied.getPreset().category : getPlayerNomenclatureCategory();
        }
        NomenclatureCategory byFaction = resolveCategoryForFactionId(factionIdHint);
        if (byFaction != NomenclatureCategory.HUMAN_NAVAL) {
            return byFaction;
        }
        if (!isNeutralOrUnknownFactionId(factionIdHint)) {
            return NomenclatureCategory.HUMAN_NAVAL;
        }
        if (ships == null || ships.isEmpty()) {
            return NomenclatureCategory.HUMAN_NAVAL;
        }
        List<ShipHullSpecAPI> specs = new ArrayList<>();
        for (ShipAPI s : ships) {
            if (s == null || s.isFighter()) continue;
            if (s.getFleetMember() != null && s.getFleetMember().getFleetData() != null
                    && s.getFleetMember().getFleetData().getFleet() != null) {
                NomenclatureCategory fleetCat = resolveCategoryForCampaignFleet(
                        s.getFleetMember().getFleetData().getFleet());
                if (fleetCat != NomenclatureCategory.HUMAN_NAVAL) {
                    return fleetCat;
                }
            }
            specs.add(s.getHullSpec());
        }
        return resolveCategoryFromHullSpecs(specs);
    }

    private static NomenclatureCategory resolveCategoryFromHullSpecs(List<ShipHullSpecAPI> specs) {
        if (specs == null || specs.isEmpty()) return NomenclatureCategory.HUMAN_NAVAL;
        int dwellerOrOmega = 0;
        int threat = 0;
        int postAiWar = 0;
        int total = specs.size();
        for (ShipHullSpecAPI spec : specs) {
            if (spec == null) continue;
            NomenclatureCategory hullCat = classifyHullSpec(spec);
            if (hullCat == NomenclatureCategory.ABYSSAL_ERROR) dwellerOrOmega++;
            else if (hullCat == NomenclatureCategory.THREAT_SWARM) threat++;
            else if (hullCat == NomenclatureCategory.POST_AI_WAR) postAiWar++;
        }
        if (dwellerOrOmega * 2 >= total) return NomenclatureCategory.ABYSSAL_ERROR;
        if (threat * 2 >= total) return NomenclatureCategory.THREAT_SWARM;
        if (postAiWar * 2 >= total) return NomenclatureCategory.POST_AI_WAR;
        return NomenclatureCategory.HUMAN_NAVAL;
    }

    private static NomenclatureCategory classifyHullSpec(ShipHullSpecAPI spec) {
        if (spec == null) return NomenclatureCategory.HUMAN_NAVAL;
        String hullId = spec.getHullId() != null ? spec.getHullId().toLowerCase(Locale.ROOT) : "";
        Collection<String> tags = spec.getTags();

        if (tags != null) {
            for (String t : tags) {
                if (t == null) continue;
                String tl = t.toLowerCase(Locale.ROOT);
                if ("dweller".equals(tl) || "omega".equals(tl) || "shrouded".equals(tl)) {
                    return NomenclatureCategory.ABYSSAL_ERROR;
                }
                if ("threat".equals(tl)) {
                    return NomenclatureCategory.THREAT_SWARM;
                }
                if ("remnant".equals(tl) || "derelict".equals(tl)) {
                    return NomenclatureCategory.POST_AI_WAR;
                }
            }
        }
        if (hullId.startsWith("shrouded_") || "tesseract".equals(hullId) || "facet".equals(hullId) || hullId.startsWith("shard_")) {
            return NomenclatureCategory.ABYSSAL_ERROR;
        }
        if (hullId.endsWith("_unit") || "attack_swarm".equals(hullId)) {
            return NomenclatureCategory.THREAT_SWARM;
        }
        if ("radiant".equals(hullId) || "nova".equals(hullId) || "apex".equals(hullId)
                || "brilliant".equals(hullId) || "scintilla".equals(hullId) || "fulgent".equals(hullId)
                || "glimmer".equals(hullId) || "lumen".equals(hullId) || "guardian".equals(hullId)
                || "rampart".equals(hullId) || "berserker".equals(hullId) || "bastillon".equals(hullId)
                || "warden".equals(hullId) || "sentry".equals(hullId) || "picket".equals(hullId)
                || "defender".equals(hullId)) {
            return NomenclatureCategory.POST_AI_WAR;
        }
        return NomenclatureCategory.HUMAN_NAVAL;
    }

    /**
     * Resolves the OTC Commander title for a battle group or faction.
     */
    public static String resolveOtcCommanderTitle(
            NomenclatureCategory category,
            boolean isPlayerGroup,
            boolean isAlliedAiGroup,
            String fallbackTitle) {
        if (category == NomenclatureCategory.ABYSSAL_ERROR) {
            return ABYSSAL_ERROR_TEXT;
        }
        if (category == NomenclatureCategory.THREAT_SWARM) {
            return THREAT_OTC_TITLE;
        }
        if (category == NomenclatureCategory.POST_AI_WAR) {
            return POST_AI_WAR_OTC_TITLE;
        }
        if (isPlayerGroup) {
            String custom = getPlayerNamingConfig().getCustomOtcTitle();
            if (!custom.isEmpty()) {
                return custom;
            }
            PlayerNamingPreset preset = getPlayerNamingConfig().getPreset();
            if (preset != null && preset.defaultOtcTitle != null && !preset.defaultOtcTitle.isEmpty()) {
                return preset.defaultOtcTitle;
            }
        }
        if (fallbackTitle != null && !fallbackTitle.trim().isEmpty()) {
            String trimmed = fallbackTitle.trim();
            if (!"Player OTC".equalsIgnoreCase(trimmed)
                    && !"Enemy OTC".equalsIgnoreCase(trimmed)
                    && !"OpFor OTC".equalsIgnoreCase(trimmed)
                    && !"Allied OTC".equalsIgnoreCase(trimmed)) {
                return trimmed;
            }
        }
        if (isPlayerGroup) return "Fleet Command";
        if (isAlliedAiGroup) return "Allied Command";
        return "Hostile Command";
    }

    /**
     * Resolves the faction/culturally appropriate OTC title for any BattleGroupState in combat.
     */
    public static String resolveBattleGroupOtcTitle(DivisionModel.BattleGroupState group) {
        if (group == null) return "Fleet Command";
        if (group.nomenclatureCategory == NomenclatureCategory.ABYSSAL_ERROR) {
            return ABYSSAL_ERROR_TEXT;
        }
        if (group.nomenclatureCategory == NomenclatureCategory.THREAT_SWARM) {
            return THREAT_OTC_TITLE;
        }
        if (group.nomenclatureCategory == NomenclatureCategory.POST_AI_WAR) {
            return POST_AI_WAR_OTC_TITLE;
        }
        boolean isPlayer = (group.side == 0 && !group.isAlliedAi);
        if (isPlayer) {
            String custom = getPlayerNamingConfig().getCustomOtcTitle();
            if (!custom.isEmpty()) {
                return custom;
            }
            if (group.useConvergence) {
                return POST_AI_WAR_OTC_TITLE;
            }
            PlayerNamingPreset preset = getPlayerNamingConfig().getPreset();
            if (preset != null && preset.defaultOtcTitle != null && !preset.defaultOtcTitle.isEmpty()) {
                return preset.defaultOtcTitle;
            }
            return "Fleet Command";
        }
        String fId = (group.factionId != null) ? group.factionId.trim().toLowerCase(Locale.ROOT) : "";
        String cmdrName = (group.otc != null && group.otc.commanderName != null) ? group.otc.commanderName.trim() : "";
        boolean hasNamedCmdr = !cmdrName.isEmpty()
                && !"steady".equalsIgnoreCase(cmdrName)
                && !"aggressive".equalsIgnoreCase(cmdrName)
                && !"cautious".equalsIgnoreCase(cmdrName)
                && !"reckless".equalsIgnoreCase(cmdrName)
                && !"timid".equalsIgnoreCase(cmdrName)
                && !"fearless".equalsIgnoreCase(cmdrName)
                && !"Enemy OTC".equalsIgnoreCase(cmdrName)
                && !"Enemy Fleet Command".equalsIgnoreCase(cmdrName)
                && !"OpFor OTC".equalsIgnoreCase(cmdrName)
                && !"Allied OTC".equalsIgnoreCase(cmdrName)
                && !"Allied Fleet Command".equalsIgnoreCase(cmdrName)
                && !"Allied Command".equalsIgnoreCase(cmdrName)
                && !"Hostile Command".equalsIgnoreCase(cmdrName);
        if ("remnant".equals(fId) || "derelict".equals(fId)) {
            return POST_AI_WAR_OTC_TITLE;
        }
        if ("threat".equals(fId)) {
            return THREAT_OTC_TITLE;
        }
        if ("dweller".equals(fId) || "omega".equals(fId)) {
            return ABYSSAL_ERROR_TEXT;
        }
        if ("hegemony".equals(fId) || "ironshell".equals(fId)) {
            return "Hegemony Command";
        }
        if ("tritachyon".equals(fId)) {
            return "Tri-Tachyon Command";
        }
        if ("persean".equals(fId) || "persean_league".equals(fId) || "mayasura".equals(fId)) {
            return "League Navarchy";
        }
        if ("sindrian_diktat".equals(fId) || "lions_guard".equals(fId)) {
            return "Diktat Command";
        }
        if ("luddic_church".equals(fId) || "knights_of_ludd".equals(fId)) {
            return "Luddic Command";
        }
        if ("luddic_path".equals(fId)) {
            return "Pather Warleader";
        }
        if ("pirates".equals(fId) || "pirate_privateers".equals(fId)) {
            return hasNamedCmdr ? ("Warlord " + cmdrName) : "Pirate Warlord";
        }
        if ("independent".equals(fId) || "mercenary".equals(fId) || "scavengers".equals(fId)) {
            return hasNamedCmdr ? cmdrName : (group.isAlliedAi ? "Allied Command" : "Independent Command");
        }
        FactionLibrary.FactionAttributes attrs = FactionLibrary.getAttributesForFaction(fId);
        if (attrs != null && attrs.basePreset != null && attrs.basePreset.defaultOtcTitle != null) {
            return attrs.basePreset.defaultOtcTitle;
        }
        if (hasNamedCmdr) {
            return cmdrName;
        }
        return group.isAlliedAi ? "Allied Command" : "Hostile Command";
    }

    /**
     * Formats a division name for the player fleet according to the player's customized naming scheme.
     */
    public static String formatPlayerDivisionName(DivisionArchetype archetype, int sequenceNumber) {
        return formatDivisionNameWithConfig(getPlayerNamingConfig(), archetype, sequenceNumber);
    }

    public static String formatDivisionNameWithConfig(
            PlayerNamingConfig cfg,
            DivisionArchetype archetype,
            int sequenceNumber) {
        PlayerNamingConfig effectiveCfg = cfg != null ? cfg : getPlayerNamingConfig();
        PlayerNamingPreset preset = effectiveCfg.getPreset();
        if (preset.category == NomenclatureCategory.ABYSSAL_ERROR) {
            return ABYSSAL_ERROR_TEXT;
        }
        if (preset.category == NomenclatureCategory.POST_AI_WAR) {
            return POST_AI_WAR_DIVISION_NAME;
        }
        if (preset.category == NomenclatureCategory.THREAT_SWARM) {
            return formatCorruptedThreatText(archetype, sequenceNumber, System.currentTimeMillis() / 260L);
        }
        String prefix = effectiveCfg.getPrefixForArchetype(archetype);
        return applyNumbering(prefix, sequenceNumber, effectiveCfg.getNumberingStyle());
    }

    /**
     * Returns a canonical numbering bucket key for a DivisionArchetype so sub-archetypes
     * that share a display family (such as FLEET_CARRIER_DIVISION, LIGHT_CARRIER_DIVISION,
     * ESCORT_CARRIER_DIVISION, and CARRIER_GROUP) share a single numbering sequence and
     * never produce duplicate "Carrier Div 1" names in the same fleet or faction table.
     */
    public static String resolvePrefixBucketKey(DivisionArchetype archetype) {
        if (archetype == null) return "FORCE";
        switch (archetype) {
            case BATTLESHIP_DIVISION:
                return "BATTLE";
            case MISSILE_BATTLESHIP_DIVISION:
                return "MISSILE_BATTLESHIP";
            case FLEET_CARRIER_DIVISION:
            case LIGHT_CARRIER_DIVISION:
            case ESCORT_CARRIER_DIVISION:
                return "CARRIER";
            case CARRIER_GROUP:
                return "CARRIER_GROUP";
            case MISSILE_CRUISER_DIVISION:
                return "MISSILE_CRUISER";
            case STRIKE_CRUISER_DIVISION:
                return "STRIKE_CRUISER";
            case CRUISER_DIVISION:
                return "CRUISER";
            case MISSILE_DESTROYER_DIVISION:
                return "MISSILE_DESTROYER";
            case DESTROYER_DIVISION:
                return "DESTROYER";
            case MISSILE_FRIGATE_DIVISION:
                return "MISSILE_FRIGATE";
            case FRIGATE_DIVISION:
                return "FRIGATE";
            case PHASE_DIVISION:
                return "PHASE";
            case SERVICE_GROUP:
                return "SERVICE";
            case FORCE_DIVISION:
                return "FORCE";
            default:
                return archetype.name();
        }
    }

    /**
     * Resolves the numbering bucket key for a player division under the player's active {@link PlayerNamingConfig}.
     * Two archetypes share a numbering sequence if and only if they share the exact same prefix under {@code cfg}
     * (e.g. under Naval Standard, Fleet/Light/Escort Carrier share "Carrier Div", whereas under Detailed Naval
     * "Fleet Carrier Div", "Light Carrier Div", and "Escort Carrier Div" each have their own sequence starting at 1).
     */
    public static String resolvePlayerPrefixBucketKey(PlayerNamingConfig cfg, DivisionArchetype archetype) {
        PlayerNamingConfig effectiveCfg = cfg != null ? cfg : getPlayerNamingConfig();
        DivisionArchetype arch = archetype != null ? archetype : DivisionArchetype.FORCE_DIVISION;
        String prefix = effectiveCfg.getPrefixForArchetype(arch);
        if (prefix == null || prefix.trim().isEmpty()) {
            return resolvePrefixBucketKey(arch);
        }
        return prefix.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Returns {@code true} if {@code currentName} is an unmodified default division name as per {@code cfg}
     * (character-for-character and case-sensitive). If the player has customized the division's name by even
     * a single character, returns {@code false}.
     */
    public static boolean isDefaultDivisionNameForConfig(
            String currentName,
            DivisionArchetype currentArchetype,
            PlayerNamingConfig cfg) {
        if (currentName == null || currentName.isEmpty()) {
            return true;
        }
        PlayerNamingConfig effectiveCfg = cfg != null ? cfg : getPlayerNamingConfig();
        NomenclatureCategory cat = effectiveCfg.getPreset().category;
        if (cat == NomenclatureCategory.ABYSSAL_ERROR) {
            return ABYSSAL_ERROR_TEXT.equals(currentName);
        }
        if (cat == NomenclatureCategory.POST_AI_WAR) {
            return POST_AI_WAR_DIVISION_NAME.equals(currentName);
        }
        if (cat == NomenclatureCategory.THREAT_SWARM) {
            return currentName.contains("UNIT") || currentName.contains("SWARM")
                    || currentName.contains("PROACT") || currentName.contains("ALICIT");
        }
        if (currentArchetype != null) {
            for (int seq = 1; seq <= 99; seq++) {
                if (currentName.equals(formatDivisionNameWithConfig(effectiveCfg, currentArchetype, seq))) {
                    return true;
                }
            }
        }
        for (DivisionArchetype arch : DivisionArchetype.values()) {
            if (arch == currentArchetype) continue;
            for (int seq = 1; seq <= 99; seq++) {
                if (currentName.equals(formatDivisionNameWithConfig(effectiveCfg, arch, seq))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Formats a division name for any NPC faction fleet.
     */
    public static String formatFactionDivisionName(
            String factionId,
            NomenclatureCategory category,
            DivisionArchetype archetype,
            int sequenceNumber) {
        return formatFactionDivisionName(factionId, category, archetype, sequenceNumber, null, 0L);
    }

    /**
     * Formats a division name for an NPC faction fleet, incorporating cultural diversity for
     * Pirates/Independents ("Flotilla", "Fleet", "Warband", "Pack", "Group", "Unit", "Gang", "Division",
     * varied Roman/Arabic/Ordinal numbering, and commander possessive names like
     * "James Vega's 17th Warband" or "Vega's Warband") and Persean League polities.
     */
    public static String formatFactionDivisionName(
            String factionId,
            NomenclatureCategory category,
            DivisionArchetype archetype,
            int sequenceNumber,
            com.fs.starfarer.api.characters.PersonAPI commander,
            long unitSeed) {
        return formatFactionDivisionName(
                factionId,
                category,
                archetype,
                sequenceNumber,
                commander,
                unitSeed,
                FactionLibrary.FleetRoleCategory.WAR_FLEET);
    }

    public static String formatFactionDivisionName(
            String factionId,
            NomenclatureCategory category,
            DivisionArchetype archetype,
            int sequenceNumber,
            com.fs.starfarer.api.characters.PersonAPI commander,
            long unitSeed,
            FactionLibrary.FleetRoleCategory fleetRole) {
        if (category == NomenclatureCategory.ABYSSAL_ERROR) {
            return ABYSSAL_ERROR_TEXT;
        }
        if (category == NomenclatureCategory.POST_AI_WAR) {
            return POST_AI_WAR_DIVISION_NAME;
        }
        if (category == NomenclatureCategory.THREAT_SWARM) {
            return formatCorruptedThreatText(archetype, sequenceNumber, System.currentTimeMillis() / 260L);
        }

        String fId = factionId != null ? factionId.toLowerCase(Locale.ROOT) : "";
        int num = Math.max(1, sequenceNumber);
        FactionLibrary.FleetRoleCategory effRole = fleetRole != null
                ? fleetRole
                : FactionLibrary.FleetRoleCategory.WAR_FLEET;

        if ("player".equals(fId) && !effRole.isNonWarFleet()) {
            PlayerNamingConfig appliedCfg = getAppliedPlayerFactionNamingConfig();
            if (appliedCfg != null) {
                return formatDivisionNameWithConfig(appliedCfg, archetype, num);
            }
        }
        long rawSeed = unitSeed != 0L
                ? unitSeed
                : (fId.hashCode() * 6364136223846793005L
                        + num * 1442695040888963407L
                        + (archetype != null ? archetype.ordinal() * 2654435761L : 0L));
        long seed = mixSeed64(rawSeed
                ^ (num * 0x9E3779B97F4A7C15L)
                ^ (archetype != null ? (archetype.ordinal() + 1L) * 0xBF58476D1CE4E5B9L : 0L));

        FactionLibrary.FactionAttributes attrs = FactionLibrary.getAttributesForFaction(fId);
        if (attrs != null) {
            if (attrs.namingStyle == FactionLibrary.FactionNamingStyle.POST_AI_WAR_UNKNOWN) {
                return POST_AI_WAR_DIVISION_NAME;
            }
            if (attrs.namingStyle == FactionLibrary.FactionNamingStyle.THREAT_CORRUPTED) {
                return formatCorruptedThreatText(archetype, num, System.currentTimeMillis() / 260L);
            }
            if (attrs.namingStyle == FactionLibrary.FactionNamingStyle.ABYSSAL_ERROR) {
                return ABYSSAL_ERROR_TEXT;
            }
            if (attrs.namingStyle == FactionLibrary.FactionNamingStyle.CULTURAL_PIRATE_INDIE) {
                return formatPirateOrIndependentDivisionName(fId, archetype, num, commander, seed, effRole, attrs);
            }
            if (attrs.namingStyle == FactionLibrary.FactionNamingStyle.CULTURAL_PERSEAN) {
                return formatPerseanDivisionName(archetype, num, commander, seed, effRole, attrs);
            }

            String prefix = attrs.resolvePrefixFor(archetype, effRole);
            if (!effRole.isNonWarFleet()
                    && archetype == DivisionArchetype.BATTLESHIP_DIVISION
                    && attrs.battleshipDotRoman
                    && ("Battle Div".equals(prefix) || "Battle Div.".equals(prefix))) {
                return "Battle Div. " + toRoman(num);
            }
            NumberingStyle style = (archetype == DivisionArchetype.BATTLESHIP_DIVISION && !effRole.isNonWarFleet() && attrs.battleshipNumbering != null)
                    ? attrs.battleshipNumbering
                    : attrs.defaultNumbering;
            return applyNumbering(prefix, num, style);
        }

        if (isPirateOrIndependentFaction(fId)) {
            return formatPirateOrIndependentDivisionName(fId, archetype, num, commander, seed, effRole, null);
        }
        if ("persean".equals(fId) || "persean_league".equals(fId)
                || "mayasura".equals(fId) || "mayasuran_guard".equals(fId)) {
            return formatPerseanDivisionName(archetype, num, commander, seed, effRole, null);
        }

        // Standard Human Naval nomenclature: use Roman numerals for Battle Divisions ("Battle Div. I")
        // or clean Naval Standard elsewhere.
        String prefix = getDefaultPrefixForPreset(PlayerNamingPreset.NAVAL_STANDARD, archetype);
        if (archetype == DivisionArchetype.BATTLESHIP_DIVISION) {
            return "Battle Div. " + toRoman(num);
        }
        return applyNumbering(prefix, num, NumberingStyle.ARABIC);
    }

    private static long mixSeed64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static boolean isPirateOrIndependentFaction(String fId) {
        if (fId == null) return false;
        return "pirates".equals(fId)
                || "pirate_privateers".equals(fId)
                || "jaydeepiracy".equals(fId)
                || "jdp_flowerfish".equals(fId)
                || "hmi_sv".equals(fId)
                || "fang".equals(fId)
                || "independent".equals(fId)
                || "mercenary".equals(fId)
                || "scavengers".equals(fId)
                || "cft".equals(fId)
                || "jyd".equals(fId)
                || "brighton".equals(fId)
                || "hmi_brighton".equals(fId)
                || "luddic_path".equals(fId)
                || "luddenhance".equals(fId);
    }

    private static final String[] PIRATE_INDIE_UNIT_NOUNS = {
            "Flotilla", "Fleet", "Warband", "Pack", "Group", "Unit", "Gang", "Division"
    };

    private static final String[] PERSEAN_UNIT_NOUNS = {
            "Division", "Flotilla", "Squadron", "Group", "Unit", "Fleet", "Phalanx", "Task Group"
    };

    private static String formatPirateOrIndependentDivisionName(
            String factionId,
            DivisionArchetype archetype,
            int num,
            com.fs.starfarer.api.characters.PersonAPI commander,
            long seed,
            FactionLibrary.FleetRoleCategory fleetRole,
            FactionLibrary.FactionAttributes attrs) {

        FactionLibrary.FleetRoleCategory effRole = fleetRole != null
                ? fleetRole
                : FactionLibrary.FleetRoleCategory.WAR_FLEET;
        boolean isCivGroup = (archetype == DivisionArchetype.SERVICE_GROUP);

        List<String> roleNouns = resolveNonWarRoleNouns(attrs, effRole, isCivGroup);

        String noun;
        if (roleNouns != null && !roleNouns.isEmpty()) {
            int rIdx = Math.floorMod((int) (seed ^ (seed >>> 13)), roleNouns.size());
            noun = roleNouns.get(rIdx);
        } else if (effRole.isNonWarFleet() && attrs != null) {
            noun = attrs.resolvePrefixFor(archetype, effRole);
        } else {
            int nounIdx = Math.floorMod((int) (seed ^ (seed >>> 13)), PIRATE_INDIE_UNIT_NOUNS.length);
            if (!"pirates".equals(factionId) && "Gang".equals(PIRATE_INDIE_UNIT_NOUNS[nounIdx])) {
                if (Math.floorMod((int) (seed >>> 29), 3) != 0) {
                    nounIdx = Math.floorMod(nounIdx + 1, PIRATE_INDIE_UNIT_NOUNS.length);
                }
            }
            noun = PIRATE_INDIE_UNIT_NOUNS[nounIdx];
        }

        String cmdFirst = null;
        String cmdLast = null;
        if (commander != null && !commander.isDefault() && commander.getName() != null) {
            cmdFirst = commander.getName().getFirst() != null ? commander.getName().getFirst().trim() : "";
            cmdLast = commander.getName().getLast() != null ? commander.getName().getLast().trim() : "";
        }
        boolean hasCommanderName = (cmdFirst != null && !cmdFirst.isEmpty()) || (cmdLast != null && !cmdLast.isEmpty());

        int narcissismRoll = Math.floorMod((int) (seed >>> 19), 100);
        int narcissismThreshold = (attrs != null && attrs.commanderPossessiveChance > 0)
                ? attrs.commanderPossessiveChance
                : ("pirates".equals(factionId) ? 55 : 40);
        if (hasCommanderName && narcissismRoll < narcissismThreshold) {
            String possessive = buildCommanderPossessive(cmdFirst, cmdLast, seed);
            int numStyleRoll = Math.floorMod((int) (seed >>> 27), 100);
            if (numStyleRoll < 42) {
                return possessive + " " + noun;
            } else if (numStyleRoll < 82) {
                return possessive + " " + toOrdinal(num) + " " + noun;
            } else {
                return possessive + " " + toRoman(num) + " " + noun;
            }
        }

        int pattern = Math.floorMod((int) (seed >>> 35), 4);
        switch (pattern) {
            case 0:
                return toRoman(num) + " " + noun;
            case 1:
                return noun + " " + num;
            case 2:
                return toOrdinal(num) + " " + noun;
            case 3:
            default:
                return noun + " " + toRoman(num);
        }
    }

    private static String formatPerseanDivisionName(
            DivisionArchetype archetype,
            int num,
            com.fs.starfarer.api.characters.PersonAPI commander,
            long seed,
            FactionLibrary.FleetRoleCategory fleetRole,
            FactionLibrary.FactionAttributes attrs) {

        FactionLibrary.FleetRoleCategory effRole = fleetRole != null
                ? fleetRole
                : FactionLibrary.FleetRoleCategory.WAR_FLEET;
        boolean isCivGroup = (archetype == DivisionArchetype.SERVICE_GROUP);

        String cmdFirst = null;
        String cmdLast = null;
        if (commander != null && !commander.isDefault() && commander.getName() != null) {
            cmdFirst = commander.getName().getFirst() != null ? commander.getName().getFirst().trim() : "";
            cmdLast = commander.getName().getLast() != null ? commander.getName().getLast().trim() : "";
        }
        boolean hasCommanderName = (cmdLast != null && !cmdLast.isEmpty()) || (cmdFirst != null && !cmdFirst.isEmpty());

        if (effRole.isNonWarFleet()) {
            List<String> roleNouns = resolveNonWarRoleNouns(attrs, effRole, isCivGroup);
            String coreNoun;
            if (roleNouns != null && !roleNouns.isEmpty()) {
                int rIdx = Math.floorMod((int) (seed ^ (seed >>> 11)), roleNouns.size());
                coreNoun = roleNouns.get(rIdx);
            } else if (attrs != null) {
                coreNoun = attrs.resolvePrefixFor(archetype, effRole);
            } else {
                coreNoun = isCivGroup ? "Merchant Flotilla" : "Convoy Escort";
            }

            int narcissismRoll = Math.floorMod((int) (seed >>> 21), 100);
            if (hasCommanderName && narcissismRoll < 20) {
                String owner = (cmdLast != null && !cmdLast.isEmpty()) ? (cmdLast + "'s") : (cmdFirst + "'s");
                return owner + " " + coreNoun;
            }
            int numPattern = Math.floorMod((int) (seed >>> 37), 4);
            switch (numPattern) {
                case 0:
                    return toRoman(num) + " " + coreNoun;
                case 1:
                    return coreNoun + " " + num;
                case 2:
                    return toOrdinal(num) + " " + coreNoun;
                case 3:
                default:
                    return coreNoun + " " + toRoman(num);
            }
        }

        int nounIdx = Math.floorMod((int) (seed ^ (seed >>> 11)), PERSEAN_UNIT_NOUNS.length);
        String noun = PERSEAN_UNIT_NOUNS[nounIdx];

        int narcissismRoll = Math.floorMod((int) (seed >>> 21), 100);
        if (hasCommanderName && narcissismRoll < 15) {
            String owner = (cmdLast != null && !cmdLast.isEmpty()) ? (cmdLast + "'s") : (cmdFirst + "'s");
            if (Math.floorMod((int) (seed >>> 31), 3) == 0) {
                return owner + " " + noun;
            }
            return owner + " " + toOrdinal(num) + " " + noun;
        }

        boolean includeRole = Math.floorMod((int) (seed >>> 17), 100) < 55;
        String roleWord = getShortRoleWord(archetype);
        String coreNoun = (includeRole && !noun.startsWith(roleWord)) ? (roleWord + " " + noun) : noun;
        if ("Battle Division".equals(coreNoun) && Math.floorMod((int) (seed >>> 7), 2) == 0) {
            coreNoun = "Battle Div.";
        }

        int numPattern = Math.floorMod((int) (seed >>> 37), 4);
        switch (numPattern) {
            case 0:
                return toRoman(num) + " " + coreNoun;
            case 1:
                return coreNoun + " " + num;
            case 2:
                return toOrdinal(num) + " " + coreNoun;
            case 3:
            default:
                return coreNoun + " " + toRoman(num);
        }
    }

    private static String getShortRoleWord(DivisionArchetype archetype) {
        if (archetype == null) return "Force";
        switch (archetype) {
            case BATTLESHIP_DIVISION:
                return "Battle";
            case MISSILE_BATTLESHIP_DIVISION:
                return "Bombard";
            case FLEET_CARRIER_DIVISION:
            case LIGHT_CARRIER_DIVISION:
            case ESCORT_CARRIER_DIVISION:
            case CARRIER_GROUP:
                return "Carrier";
            case MISSILE_CRUISER_DIVISION:
                return "Missile";
            case STRIKE_CRUISER_DIVISION:
                return "Strike";
            case CRUISER_DIVISION:
                return "Cruiser";
            case DESTROYER_DIVISION:
                return "Destroyer";
            case MISSILE_DESTROYER_DIVISION:
                return "Torpedo";
            case FRIGATE_DIVISION:
                return "Frigate";
            case MISSILE_FRIGATE_DIVISION:
                return "Missile";
            case PHASE_DIVISION:
                return "Phase";
            case SERVICE_GROUP:
                return "Supply";
            case FORCE_DIVISION:
            default:
                return "Force";
        }
    }

    private static String buildCommanderPossessive(String first, String last, long seed) {
        boolean hasFirst = first != null && !first.isEmpty();
        boolean hasLast = last != null && !last.isEmpty();
        if (hasFirst && hasLast) {
            int form = Math.floorMod((int) (seed >>> 23), 3);
            if (form == 0) {
                return first + " " + last + "'s";
            } else if (form == 1) {
                return last + "'s";
            } else {
                return first + "'s";
            }
        }
        if (hasLast) return last + "'s";
        return first + "'s";
    }

    /**
     * Resolves the live display name for a division on the Command Window (Tab) or campaign tooltip,
     * applying shifting corrupted text for The Threat fleets, "UNKNOWN" for Post-AI War AI fleets,
     * and "ERROR" for Abyssal/Otherworldly fleets.
     */
    public static String resolveDynamicDivisionDisplayName(
            NomenclatureCategory category,
            DivisionArchetype archetype,
            int divisionNumber,
            String baseDisplayName) {
        if (category == NomenclatureCategory.ABYSSAL_ERROR) {
            return ABYSSAL_ERROR_TEXT;
        }
        if (category == NomenclatureCategory.POST_AI_WAR) {
            return POST_AI_WAR_DIVISION_NAME;
        }
        if (category == NomenclatureCategory.THREAT_SWARM) {
            long timeBucket = System.currentTimeMillis() / 240L;
            return formatCorruptedThreatText(archetype, divisionNumber, timeBucket);
        }
        return baseDisplayName != null ? baseDisplayName : "Division " + divisionNumber;
    }

    /**
     * Produces canonical Threat functional unit designations and archaic Domain comm/infosec telemetry
     * faithful to Starsector 0.98a lore (rules.csv lines 30414 & 39741-39765, descriptions.csv lines 337-377,
     * data/hulls/*_unit.ship, and data/variants/*_unit_Type*.variant):
     *  - "Between intercept telemetry and salvage we are forced to conclude that there is no fixed designation,
     *     no identity, to individual swarm elements. They are defined by function alone."
     *  - Ships are designated by function and Domain Type: Standoff Unit (Type 300/301/302), Hive Unit (Type 350),
     *    Overseer Unit (Type 250), Assault Unit (Type 200/201), Skirmish Unit (Type 100/101), Fabricator Unit (Type 450),
     *    and Reclamation / Attack Swarm.
     *  - Comm-link & TriPad telemetry signals: "???? DELUSE", "*** ALICIT REIFICATE ***",
     *    "INTERDICTORATE PROACT: UNFABRICATE", "???? DENYANCE", "INFOSEC ERR".
     */
    public static String formatCorruptedThreatText(
            DivisionArchetype archetype,
            int divisionNumber,
            long timeBucket) {
        String functionalUnit = getThreatBaseWord(archetype);
        String typeTag = getThreatTypeDesignation(archetype);
        if (timeBucket == 0L) {
            return functionalUnit + " // " + typeTag;
        }
        long seed = timeBucket * 6364136223846793005L
                + (divisionNumber * 1442695040888963407L)
                + (archetype != null ? archetype.ordinal() * 2654435761L : 0L);
        int frame = Math.floorMod((int) (seed ^ (seed >>> 17)), 6);
        switch (frame) {
            case 0:
                return functionalUnit + " // " + typeTag;
            case 1:
                return "???? " + functionalUnit + " ????";
            case 2:
                return "*** ALICIT: " + functionalUnit + " ***";
            case 3:
                return functionalUnit + " // INFOSEC ERR";
            case 4:
                return "PROACT: " + functionalUnit;
            case 5:
            default:
                return "???? DELUSE // " + functionalUnit;
        }
    }

    private static String getThreatBaseWord(DivisionArchetype archetype) {
        if (archetype == null) return "RECLAMATION SWARM";
        switch (archetype) {
            case BATTLESHIP_DIVISION:
            case MISSILE_BATTLESHIP_DIVISION:
                return "STANDOFF UNIT";
            case FLEET_CARRIER_DIVISION:
            case LIGHT_CARRIER_DIVISION:
            case ESCORT_CARRIER_DIVISION:
            case CARRIER_GROUP:
                return "HIVE UNIT";
            case CRUISER_DIVISION:
            case MISSILE_CRUISER_DIVISION:
                return "OVERSEER UNIT";
            case STRIKE_CRUISER_DIVISION:
            case DESTROYER_DIVISION:
            case MISSILE_DESTROYER_DIVISION:
                return "ASSAULT UNIT";
            case FRIGATE_DIVISION:
            case MISSILE_FRIGATE_DIVISION:
            case PHASE_DIVISION:
                return "SKIRMISH UNIT";
            case SERVICE_GROUP:
                return "FABRICATOR UNIT";
            case FORCE_DIVISION:
            default:
                return "RECLAMATION SWARM";
        }
    }

    private static String getThreatTypeDesignation(DivisionArchetype archetype) {
        if (archetype == null) return "UNFABRICATE";
        switch (archetype) {
            case BATTLESHIP_DIVISION:
                return "TYPE 300";
            case MISSILE_BATTLESHIP_DIVISION:
                return "TYPE 302";
            case FLEET_CARRIER_DIVISION:
            case LIGHT_CARRIER_DIVISION:
            case ESCORT_CARRIER_DIVISION:
            case CARRIER_GROUP:
                return "TYPE 350";
            case CRUISER_DIVISION:
            case MISSILE_CRUISER_DIVISION:
                return "TYPE 250";
            case STRIKE_CRUISER_DIVISION:
            case MISSILE_DESTROYER_DIVISION:
                return "TYPE 201";
            case DESTROYER_DIVISION:
                return "TYPE 200";
            case FRIGATE_DIVISION:
                return "TYPE 100";
            case MISSILE_FRIGATE_DIVISION:
            case PHASE_DIVISION:
                return "TYPE 101";
            case SERVICE_GROUP:
                return "TYPE 450";
            case FORCE_DIVISION:
            default:
                return "UNFABRICATE";
        }
    }

    public static String getDefaultPrefixForPreset(PlayerNamingPreset preset, DivisionArchetype archetype) {
        if (archetype == null) archetype = DivisionArchetype.FORCE_DIVISION;
        if (preset == null) preset = PlayerNamingPreset.NAVAL_STANDARD;

        switch (preset) {
            case NAVAL_STANDARD:
                switch (archetype) {
                    case BATTLESHIP_DIVISION:
                        return "Battle Div";
                    case MISSILE_BATTLESHIP_DIVISION:
                        return "Missile Battleship Div";
                    case FLEET_CARRIER_DIVISION:
                    case LIGHT_CARRIER_DIVISION:
                    case ESCORT_CARRIER_DIVISION:
                        return "Carrier Div";
                    case CARRIER_GROUP:
                        return "Carrier Group";
                    case CRUISER_DIVISION:
                        return "Line Cruiser Div";
                    case MISSILE_CRUISER_DIVISION:
                        return "Missile Cruiser Div";
                    case STRIKE_CRUISER_DIVISION:
                        return "Strike Cruiser Div";
                    case DESTROYER_DIVISION:
                        return "Destroyer Div";
                    case MISSILE_DESTROYER_DIVISION:
                        return "Missile Destroyer Div";
                    case FRIGATE_DIVISION:
                        return "Frigate Div";
                    case MISSILE_FRIGATE_DIVISION:
                        return "Missile Frigate Div";
                    case PHASE_DIVISION:
                        return "Phase Div";
                    case SERVICE_GROUP:
                        return "Service Group";
                    case FORCE_DIVISION:
                        return "Force";
                    default:
                        return archetype.shortPrefix;
                }

            case CLASSIC_NAVAL:
                return archetype.shortPrefix;

            case DOMAIN_ARMADA:
                switch (archetype) {
                    case BATTLESHIP_DIVISION:
                        return "Battle Line";
                    case MISSILE_BATTLESHIP_DIVISION:
                        return "Bombard Battle Line";
                    case FLEET_CARRIER_DIVISION:
                        return "Fleet Carrier Wing";
                    case LIGHT_CARRIER_DIVISION:
                        return "Light Carrier Wing";
                    case ESCORT_CARRIER_DIVISION:
                        return "Escort Carrier Wing";
                    case CARRIER_GROUP:
                        return "Carrier Task Group";
                    case CRUISER_DIVISION:
                        return "Cruiser Squadron";
                    case MISSILE_CRUISER_DIVISION:
                        return "Missile Cruiser Squadron";
                    case STRIKE_CRUISER_DIVISION:
                        return "Strike Squadron";
                    case DESTROYER_DIVISION:
                        return "Destroyer Flotilla";
                    case MISSILE_DESTROYER_DIVISION:
                        return "Torpedo Flotilla";
                    case FRIGATE_DIVISION:
                        return "Frigate Picket";
                    case MISSILE_FRIGATE_DIVISION:
                        return "Missile Frigate Picket";
                    case PHASE_DIVISION:
                        return "Phase Detachment";
                    case SERVICE_GROUP:
                        return "Auxiliary Train";
                    case FORCE_DIVISION:
                        return "Task Force";
                    default:
                        return archetype.shortPrefix;
                }

            case TRITACHYON_CORPORATE:
                switch (archetype) {
                    case BATTLESHIP_DIVISION:
                        return "Capital Asset Group";
                    case MISSILE_BATTLESHIP_DIVISION:
                        return "Stand-Off Capital Group";
                    case FLEET_CARRIER_DIVISION:
                        return "Astral Flight Group";
                    case LIGHT_CARRIER_DIVISION:
                        return "Support Flight Group";
                    case ESCORT_CARRIER_DIVISION:
                        return "Tactical Flight Group";
                    case CARRIER_GROUP:
                        return "Integrated Flight Group";
                    case CRUISER_DIVISION:
                        return "Cruiser Assurance Unit";
                    case MISSILE_CRUISER_DIVISION:
                        return "Ordnance Cruiser Unit";
                    case STRIKE_CRUISER_DIVISION:
                        return "Fast Response Unit";
                    case DESTROYER_DIVISION:
                        return "Destroyer Security Unit";
                    case MISSILE_DESTROYER_DIVISION:
                        return "Torpedo Strike Unit";
                    case FRIGATE_DIVISION:
                        return "Frigate Picket Unit";
                    case MISSILE_FRIGATE_DIVISION:
                        return "Missile Picket Unit";
                    case PHASE_DIVISION:
                        return "Phase Special Affairs";
                    case SERVICE_GROUP:
                        return "Logistics Asset Group";
                    case FORCE_DIVISION:
                        return "Task Force Asset";
                    default:
                        return archetype.shortPrefix;
                }

            case PERSEAN_NAVARCHY:
                switch (archetype) {
                    case BATTLESHIP_DIVISION:
                        return "Battle Phalanx";
                    case MISSILE_BATTLESHIP_DIVISION:
                        return "Cataphract Phalanx";
                    case FLEET_CARRIER_DIVISION:
                        return "Navarch Carrier Group";
                    case LIGHT_CARRIER_DIVISION:
                        return "Light Carrier Lochos";
                    case ESCORT_CARRIER_DIVISION:
                        return "Escort Carrier Lochos";
                    case CARRIER_GROUP:
                        return "Composite Carrier Group";
                    case CRUISER_DIVISION:
                        return "Cruiser Mora";
                    case MISSILE_CRUISER_DIVISION:
                        return "Ballista Mora";
                    case STRIKE_CRUISER_DIVISION:
                        return "Strike Mora";
                    case DESTROYER_DIVISION:
                        return "Destroyer Lochos";
                    case MISSILE_DESTROYER_DIVISION:
                        return "Javelin Lochos";
                    case FRIGATE_DIVISION:
                        return "Frigate Peltast";
                    case MISSILE_FRIGATE_DIVISION:
                        return "Toxotes Missile Peltast";
                    case PHASE_DIVISION:
                        return "Phase Krypteia";
                    case SERVICE_GROUP:
                        return "Supply Hipparchy";
                    case FORCE_DIVISION:
                        return "League Force";
                    default:
                        return archetype.shortPrefix;
                }

            case SINDRIAN_GUARD:
                switch (archetype) {
                    case BATTLESHIP_DIVISION:
                        return "Executor Line";
                    case MISSILE_BATTLESHIP_DIVISION:
                        return "Lion's Wrath Line";
                    case FLEET_CARRIER_DIVISION:
                    case LIGHT_CARRIER_DIVISION:
                    case ESCORT_CARRIER_DIVISION:
                    case CARRIER_GROUP:
                        return "Solar Flight Wing";
                    case CRUISER_DIVISION:
                        return "Cruiser Phalanx";
                    case MISSILE_CRUISER_DIVISION:
                        return "Thunderbolt Phalanx";
                    case STRIKE_CRUISER_DIVISION:
                        return "Solar Strike Squadron";
                    case DESTROYER_DIVISION:
                        return "Vanguard Flotilla";
                    case MISSILE_DESTROYER_DIVISION:
                        return "Breaker Torpedo Flotilla";
                    case FRIGATE_DIVISION:
                        return "Picket Guard";
                    case MISSILE_FRIGATE_DIVISION:
                        return "Javelin Picket Guard";
                    case PHASE_DIVISION:
                        return "Special Guard";
                    case SERVICE_GROUP:
                        return "Askonia Supply Train";
                    case FORCE_DIVISION:
                        return "Sovereign Force";
                    default:
                        return archetype.shortPrefix;
                }

            case LUDDIC_ARMADA:
                switch (archetype) {
                    case BATTLESHIP_DIVISION:
                        return "Hallowed Bastion";
                    case MISSILE_BATTLESHIP_DIVISION:
                        return "Brimstone Bastion";
                    case FLEET_CARRIER_DIVISION:
                    case LIGHT_CARRIER_DIVISION:
                    case ESCORT_CARRIER_DIVISION:
                    case CARRIER_GROUP:
                        return "Ark Carrier Host";
                    case CRUISER_DIVISION:
                        return "Righteous Hammer";
                    case MISSILE_CRUISER_DIVISION:
                        return "Pillar of Judgment";
                    case STRIKE_CRUISER_DIVISION:
                        return "Zealot Strike Host";
                    case DESTROYER_DIVISION:
                        return "Sword Flotilla";
                    case MISSILE_DESTROYER_DIVISION:
                        return "Slinger Flotilla";
                    case FRIGATE_DIVISION:
                        return "Watchers Picket";
                    case MISSILE_FRIGATE_DIVISION:
                        return "Censer Missile Picket";
                    case PHASE_DIVISION:
                        return "Penitent Veil";
                    case SERVICE_GROUP:
                        return "Pilgrim Convoy";
                    case FORCE_DIVISION:
                        return "Faithful Force";
                    default:
                        return archetype.shortPrefix;
                }

            case PIRATE_WOLFPACK:
                switch (archetype) {
                    case BATTLESHIP_DIVISION:
                        return "Dreadnought Pack";
                    case MISSILE_BATTLESHIP_DIVISION:
                        return "Siegebreaker Pack";
                    case FLEET_CARRIER_DIVISION:
                    case LIGHT_CARRIER_DIVISION:
                    case ESCORT_CARRIER_DIVISION:
                    case CARRIER_GROUP:
                        return "Flattop Pack";
                    case CRUISER_DIVISION:
                        return "Heavy Raider Pack";
                    case MISSILE_CRUISER_DIVISION:
                        return "Salvo Raider Pack";
                    case STRIKE_CRUISER_DIVISION:
                        return "Outrider Pack";
                    case DESTROYER_DIVISION:
                        return "Hunter Pack";
                    case MISSILE_DESTROYER_DIVISION:
                        return "Boom-Runner Pack";
                    case FRIGATE_DIVISION:
                        return "Wolfpack";
                    case MISSILE_FRIGATE_DIVISION:
                        return "Bottle-Rocket Pack";
                    case PHASE_DIVISION:
                        return "Ghost Pack";
                    case SERVICE_GROUP:
                        return "Loot Train";
                    case FORCE_DIVISION:
                        return "Raider Force";
                    default:
                        return archetype.shortPrefix;
                }

            case REMNANT_ORDO:
                switch (archetype) {
                    case BATTLESHIP_DIVISION:
                        return "Heavy Sub-Ordo";
                    case MISSILE_BATTLESHIP_DIVISION:
                        return "Barrage Sub-Ordo";
                    case FLEET_CARRIER_DIVISION:
                    case LIGHT_CARRIER_DIVISION:
                    case ESCORT_CARRIER_DIVISION:
                    case CARRIER_GROUP:
                        return "Carrier Sub-Ordo";
                    case CRUISER_DIVISION:
                        return "Cruiser Sub-Ordo";
                    case MISSILE_CRUISER_DIVISION:
                        return "Ordnance Sub-Ordo";
                    case STRIKE_CRUISER_DIVISION:
                        return "Strike Sub-Ordo";
                    case DESTROYER_DIVISION:
                        return "Destroyer Fragment";
                    case MISSILE_DESTROYER_DIVISION:
                        return "Torpedo Fragment";
                    case FRIGATE_DIVISION:
                        return "Frigate Fragment";
                    case MISSILE_FRIGATE_DIVISION:
                        return "Missile Fragment";
                    case PHASE_DIVISION:
                        return "Phase Fragment";
                    case SERVICE_GROUP:
                        return "Utility Fragment";
                    case FORCE_DIVISION:
                        return "Force Sub-Ordo";
                    default:
                        return archetype.shortPrefix;
                }

            case POST_AI_WAR_UNKNOWN:
                return POST_AI_WAR_DIVISION_NAME;

            case THREAT_CORRUPTED:
                return formatCorruptedThreatText(archetype, 1, 0L);

            case ABYSSAL_ERROR:
                return ABYSSAL_ERROR_TEXT;

            default:
                return archetype.shortPrefix;
        }
    }

    public static String applyNumbering(String basePrefix, int number, NumberingStyle style) {
        if (basePrefix == null || basePrefix.trim().isEmpty()) {
            basePrefix = "Division";
        }
        String trimmed = basePrefix.trim();
        if (POST_AI_WAR_DIVISION_NAME.equals(trimmed) || ABYSSAL_ERROR_TEXT.equals(trimmed)) {
            return trimmed;
        }
        if (style == null) style = NumberingStyle.ARABIC;
        int n = Math.max(1, number);
        switch (style) {
            case NONE:
                return trimmed;
            case ROMAN:
                return trimmed + " " + toRoman(n);
            case ORDINAL_PREFIX:
                return toOrdinal(n) + " " + trimmed;
            case PHONETIC:
                return trimmed + " " + toPhonetic(n);
            case ARABIC:
            default:
                return trimmed + " " + n;
        }
    }

    public static String toRoman(int number) {
        int n = Math.max(1, Math.min(99, number));
        int[] values = {90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            while (n >= values[i]) {
                sb.append(numerals[i]);
                n -= values[i];
            }
        }
        return sb.toString();
    }

    public static String toOrdinal(int number) {
        int n = Math.max(1, number);
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) {
            return n + "th";
        }
        switch (n % 10) {
            case 1:
                return n + "st";
            case 2:
                return n + "nd";
            case 3:
                return n + "rd";
            default:
                return n + "th";
        }
    }

    public static String toPhonetic(int number) {
        String[] callsigns = {
                "Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta",
                "Eta", "Theta", "Iota", "Kappa", "Lambda", "Sigma", "Omega"
        };
        int idx = Math.max(0, number - 1);
        if (idx < callsigns.length) {
            return callsigns[idx];
        }
        return callsigns[idx % callsigns.length] + "-" + ((idx / callsigns.length) + 1);
    }
    private static List<String> resolveNonWarRoleNouns(
            FactionLibrary.FactionAttributes attrs,
            FactionLibrary.FleetRoleCategory effRole,
            boolean isCivGroup) {
        if (attrs == null || effRole == null) return null;
        if (effRole == FactionLibrary.FleetRoleCategory.MERCHANT_CONVOY) {
            return isCivGroup ? attrs.merchantConvoyNouns : attrs.merchantEscortNouns;
        }
        if (effRole == FactionLibrary.FleetRoleCategory.SMUGGLER_RAIDER) {
            return isCivGroup ? attrs.smugglerConvoyNouns : attrs.smugglerEscortNouns;
        }
        if (effRole == FactionLibrary.FleetRoleCategory.SCAVENGER_EXPEDITION) {
            return isCivGroup ? attrs.scavengerConvoyNouns : attrs.scavengerEscortNouns;
        }
        return null;
    }
}
