package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionNomenclature.NomenclatureCategory;
import divisionai.DivisionNomenclature.NumberingStyle;
import divisionai.DivisionNomenclature.PlayerNamingPreset;
import org.apache.log4j.Logger;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Open, submodder-accessible Faction & Fleet-Role Library for Divisions: Command and Control.
 *
 * Attributes to each faction:
 *  1. Organization Rating (NONE 0%, LOW 25%, AVERAGE 50%, HIGH 80%, VERY_HIGH 90%, PERFECT 100%)
 *     controlling what fraction of ships in an NPC fleet are organized into divisions vs left as
 *     independent ships. Factions not present in the library default to HIGH (80%) as a fallback.
 *  2. Fleet-Role Adaptation (WAR_FLEET, PATROL_PICKET, MERCHANT_CONVOY, SMUGGLER_RAIDER,
 *     SCAVENGER_EXPEDITION) so non-war fleets adapt both their Organization Rating and their
 *     division nomenclature (e.g., a merchant fleet calls its freighter divisions "Merchant Convoy" /
 *     "Chartered Convoy" / "Commercial Caravan" / "Trade Flotilla" rather than "Service Group",
 *     and calls its warship divisions "Convoy Escort" / "Caravan Guard").
 *  3. Faction Nomenclature (category, naming style, numbering style, commander-possessive chance,
 *     cultural unit nouns, and per-role archetype prefixes).
 *
 * Submodders can extend or override any faction or default entry in two ways:
 *  - Declaratively via {@code data/config/division_faction_library.json} (automatically merged
 *    across all enabled mods via {@code Global.getSettings().getMergedJSONForMod(...)}), or
 *  - Programmatically via {@link #registerFaction(String, FactionAttributes)} /
 *    {@link #getOrCreateFaction(String)}.
 */
public final class FactionLibrary {

    private static final Logger LOG = Global.getLogger(FactionLibrary.class);

    public static final String MOD_ID = "division_ai";
    public static final String CONFIG_PATH = "data/config/division_faction_library.json";

    /**
     * Faction / Fleet Organization Rating: controls the percentage of eligible ships in an NPC fleet
     * that are organized into divisions (the remainder operate as independent ships).
     * Factions not in the library default to {@link #HIGH} (80%).
     */
    public enum OrganizationRating {
        NONE("none", "None (0%)", 0.00f),
        LOW("low", "Low (25%)", 0.25f),
        AVERAGE("average", "Average (50%)", 0.50f),
        HIGH("high", "High (80%)", 0.80f),
        VERY_HIGH("very_high", "Very High (90%)", 0.90f),
        PERFECT("perfect", "Perfect (100%)", 1.00f);

        public final String id;
        public final String displayName;
        public final float fraction;

        OrganizationRating(String id, String displayName, float fraction) {
            this.id = id;
            this.displayName = displayName;
            this.fraction = fraction;
        }

        public OrganizationRating stepDown(int steps) {
            OrganizationRating[] vals = values();
            int idx = Math.max(0, Math.min(vals.length - 1, this.ordinal() - steps));
            return vals[idx];
        }

        public OrganizationRating stepUp(int steps) {
            OrganizationRating[] vals = values();
            int idx = Math.max(0, Math.min(vals.length - 1, this.ordinal() + steps));
            return vals[idx];
        }

        public static OrganizationRating fromId(String raw, OrganizationRating fallback) {
            if (raw == null || raw.trim().isEmpty()) {
                return fallback;
            }
            String norm = raw.trim().toLowerCase(Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');
            for (OrganizationRating r : values()) {
                if (r.name().equalsIgnoreCase(norm) || r.id.equalsIgnoreCase(norm)) {
                    return r;
                }
            }
            if ("0".equals(norm) || "0%".equals(norm)) return NONE;
            if ("25".equals(norm) || "25%".equals(norm)) return LOW;
            if ("50".equals(norm) || "50%".equals(norm)) return AVERAGE;
            if ("80".equals(norm) || "80%".equals(norm)) return HIGH;
            if ("90".equals(norm) || "90%".equals(norm) || "veryhigh".equals(norm)) return VERY_HIGH;
            if ("100".equals(norm) || "100%".equals(norm)) return PERFECT;
            return fallback;
        }
    }

    /**
     * Functional role of a campaign fleet in the sector.
     * Non-war fleets adapt both their Organization Rating and their division nomenclature.
     */
    public enum FleetRoleCategory {
        WAR_FLEET("war_fleet", "War Fleet"),
        PATROL_PICKET("patrol_picket", "Patrol / Picket"),
        MERCHANT_CONVOY("merchant_convoy", "Merchant / Trade Convoy"),
        SMUGGLER_RAIDER("smuggler_raider", "Smuggler / Raider"),
        SCAVENGER_EXPEDITION("scavenger_expedition", "Scavenger / Expedition");

        public final String id;
        public final String displayName;

        FleetRoleCategory(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public boolean isNonWarFleet() {
            return this == MERCHANT_CONVOY || this == SMUGGLER_RAIDER || this == SCAVENGER_EXPEDITION;
        }

        public static FleetRoleCategory fromId(String raw, FleetRoleCategory fallback) {
            if (raw == null || raw.trim().isEmpty()) return fallback;
            String norm = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (FleetRoleCategory c : values()) {
                if (c.name().equalsIgnoreCase(norm) || c.id.equalsIgnoreCase(norm)) {
                    return c;
                }
            }
            if ("merchant".equals(norm) || "trade".equals(norm) || "convoy".equals(norm)) return MERCHANT_CONVOY;
            if ("smuggler".equals(norm) || "raider".equals(norm) || "privateer".equals(norm)) return SMUGGLER_RAIDER;
            if ("scavenger".equals(norm) || "salvage".equals(norm) || "mining".equals(norm) || "expedition".equals(norm)) {
                return SCAVENGER_EXPEDITION;
            }
            if ("patrol".equals(norm) || "picket".equals(norm)) return PATROL_PICKET;
            if ("war".equals(norm) || "military".equals(norm) || "armada".equals(norm)) return WAR_FLEET;
            return fallback;
        }
    }

    /**
     * Cultural naming pattern used for a faction's divisions.
     */
    public enum FactionNamingStyle {
        STANDARD_PREFIX,
        CULTURAL_PIRATE_INDIE,
        CULTURAL_PERSEAN,
        POST_AI_WAR_UNKNOWN,
        THREAT_CORRUPTED,
        ABYSSAL_ERROR;

        public static FactionNamingStyle fromId(String raw, FactionNamingStyle fallback) {
            if (raw == null || raw.trim().isEmpty()) return fallback;
            String norm = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (FactionNamingStyle s : values()) {
                if (s.name().equalsIgnoreCase(norm)) return s;
            }
            return fallback;
        }
    }

    /**
     * Complete set of attributes ascribed to a faction in the library.
     * Submodders can inspect or mutate fields directly or register custom instances.
     */
    public static final class FactionAttributes {
        public String factionId;
        public final List<String> aliases = new ArrayList<>();
        public NomenclatureCategory nomenclatureCategory = NomenclatureCategory.HUMAN_NAVAL;
        public FactionNamingStyle namingStyle = FactionNamingStyle.STANDARD_PREFIX;
        public PlayerNamingPreset basePreset = PlayerNamingPreset.NAVAL_STANDARD;
        public NumberingStyle defaultNumbering = NumberingStyle.ARABIC;
        public NumberingStyle battleshipNumbering = NumberingStyle.ROMAN;
        public boolean battleshipDotRoman = true;
        public int commanderPossessiveChance = 0;

        public OrganizationRating warOrganizationRating = OrganizationRating.HIGH;
        public final Map<FleetRoleCategory, OrganizationRating> roleOrganizationOverrides =
                new EnumMap<>(FleetRoleCategory.class);

        public final List<String> warUnitNouns = new ArrayList<>();
        public final List<String> merchantConvoyNouns = new ArrayList<>();
        public final List<String> merchantEscortNouns = new ArrayList<>();
        public final List<String> smugglerConvoyNouns = new ArrayList<>();
        public final List<String> smugglerEscortNouns = new ArrayList<>();
        public final List<String> scavengerConvoyNouns = new ArrayList<>();
        public final List<String> scavengerEscortNouns = new ArrayList<>();

        public final Map<DivisionArchetype, String> warFleetPrefixes =
                new EnumMap<>(DivisionArchetype.class);
        public final Map<DivisionArchetype, String> merchantFleetPrefixes =
                new EnumMap<>(DivisionArchetype.class);
        public final Map<DivisionArchetype, String> smugglerFleetPrefixes =
                new EnumMap<>(DivisionArchetype.class);
        public final Map<DivisionArchetype, String> scavengerFleetPrefixes =
                new EnumMap<>(DivisionArchetype.class);

        public FactionAttributes(String factionId) {
            this.factionId = factionId != null ? factionId.trim().toLowerCase(Locale.ROOT) : "default";
        }

        public OrganizationRating getOrganizationRatingForRole(FleetRoleCategory role, boolean isStoryOrArmada) {
            FleetRoleCategory effRole = role != null ? role : FleetRoleCategory.WAR_FLEET;
            OrganizationRating explicit = roleOrganizationOverrides.get(effRole);
            OrganizationRating base = (explicit != null) ? explicit : computeDefaultRoleRating(warOrganizationRating, effRole);
            if (isStoryOrArmada && effRole == FleetRoleCategory.WAR_FLEET && base != OrganizationRating.NONE) {
                // Major named armadas / story fleets are at least one step better organized than ordinary fleets
                return base.stepUp(1);
            }
            return base;
        }

        private static OrganizationRating computeDefaultRoleRating(OrganizationRating warRating, FleetRoleCategory role) {
            OrganizationRating base = warRating != null ? warRating : DEFAULT_FALLBACK_RATING;
            if (base == OrganizationRating.NONE || base == OrganizationRating.PERFECT) {
                return base;
            }
            switch (role) {
                case PATROL_PICKET:
                    return base;
                case MERCHANT_CONVOY:
                    return base == OrganizationRating.LOW ? OrganizationRating.LOW : base.stepDown(1);
                case SCAVENGER_EXPEDITION:
                    return base == OrganizationRating.LOW ? OrganizationRating.LOW : base.stepDown(1);
                case SMUGGLER_RAIDER:
                    return base == OrganizationRating.LOW ? OrganizationRating.LOW : base.stepDown(2).stepUp(base == OrganizationRating.AVERAGE ? 1 : 0);
                case WAR_FLEET:
                default:
                    return base;
            }
        }

        public String resolvePrefixFor(DivisionArchetype archetype, FleetRoleCategory role) {
            DivisionArchetype arch = archetype != null ? archetype : DivisionArchetype.FORCE_DIVISION;
            FleetRoleCategory effRole = role != null ? role : FleetRoleCategory.WAR_FLEET;

            if (effRole == FleetRoleCategory.MERCHANT_CONVOY) {
                String p = merchantFleetPrefixes.get(arch);
                if (p != null && !p.isEmpty()) return p;
                String defMerch = DEFAULT_MERCHANT_PREFIXES.get(arch);
                if (defMerch != null && !defMerch.isEmpty()) return defMerch;
            } else if (effRole == FleetRoleCategory.SMUGGLER_RAIDER) {
                String p = smugglerFleetPrefixes.get(arch);
                if (p != null && !p.isEmpty()) return p;
                String defSmug = DEFAULT_SMUGGLER_PREFIXES.get(arch);
                if (defSmug != null && !defSmug.isEmpty()) return defSmug;
            } else if (effRole == FleetRoleCategory.SCAVENGER_EXPEDITION) {
                String p = scavengerFleetPrefixes.get(arch);
                if (p != null && !p.isEmpty()) return p;
                String defScav = DEFAULT_SCAVENGER_PREFIXES.get(arch);
                if (defScav != null && !defScav.isEmpty()) return defScav;
            }

            String war = warFleetPrefixes.get(arch);
            if (war != null && !war.isEmpty()) {
                return war;
            }
            return DivisionNomenclature.getDefaultPrefixForPreset(
                    basePreset != null ? basePreset : PlayerNamingPreset.NAVAL_STANDARD,
                    arch);
        }
    }

    private static OrganizationRating DEFAULT_FALLBACK_RATING = OrganizationRating.HIGH;
    private static final Map<String, FactionAttributes> FACTION_REGISTRY = new LinkedHashMap<>();
    private static final Map<DivisionArchetype, String> DEFAULT_MERCHANT_PREFIXES = new EnumMap<>(DivisionArchetype.class);
    private static final Map<DivisionArchetype, String> DEFAULT_SMUGGLER_PREFIXES = new EnumMap<>(DivisionArchetype.class);
    private static final Map<DivisionArchetype, String> DEFAULT_SCAVENGER_PREFIXES = new EnumMap<>(DivisionArchetype.class);

    private static boolean configLoaded = false;
    private static boolean jsonHandlesResolved = false;
    private static MethodHandle mhGetMergedJsonForMod;
    private static MethodHandle mhJsonObjFromStringCtor;
    private static MethodHandle mhJsonObjOptString;
    private static MethodHandle mhJsonObjOptInt;
    private static MethodHandle mhJsonObjOptBoolean;
    private static MethodHandle mhJsonObjOptJsonObject;
    private static MethodHandle mhJsonObjOptJsonArray;
    private static MethodHandle mhJsonObjNames;
    private static MethodHandle mhJsonArrLength;
    private static MethodHandle mhJsonArrOptString;

    static {
        installBuiltInLibrary();
    }

    private FactionLibrary() {
    }

    // =========================================================================
    // PUBLIC SUBMODDER API
    // =========================================================================

    /**
     * Returns the default fallback Organization Rating for any faction not explicitly in the library
     * (defaults to {@link OrganizationRating#HIGH} = 80%).
     */
    public static OrganizationRating getDefaultFallbackOrganizationRating() {
        ensureLoaded();
        return DEFAULT_FALLBACK_RATING;
    }

    /**
     * Allows a submod to change the global fallback Organization Rating for unknown factions.
     */
    public static synchronized void setDefaultFallbackOrganizationRating(OrganizationRating rating) {
        if (rating != null) {
            DEFAULT_FALLBACK_RATING = rating;
        }
    }

    /**
     * Registers or overrides a faction's attributes (and any aliases) in the Faction Library.
     */
    public static synchronized void registerFaction(String factionId, FactionAttributes attributes) {
        if (factionId == null || attributes == null) return;
        String key = factionId.trim().toLowerCase(Locale.ROOT);
        FACTION_REGISTRY.put(key, attributes);
        if (attributes.aliases != null) {
            for (String alias : attributes.aliases) {
                if (alias != null && !alias.trim().isEmpty()) {
                    FACTION_REGISTRY.put(alias.trim().toLowerCase(Locale.ROOT), attributes);
                }
            }
        }
    }

    /**
     * Returns the mutable {@link FactionAttributes} entry for {@code factionId}, creating a new
     * entry initialized with {@link OrganizationRating#HIGH} (80%) if not yet registered.
     */
    public static synchronized FactionAttributes getOrCreateFaction(String factionId) {
        ensureLoaded();
        String key = (factionId != null && !factionId.trim().isEmpty())
                ? factionId.trim().toLowerCase(Locale.ROOT)
                : "independent";
        FactionAttributes existing = FACTION_REGISTRY.get(key);
        if (existing != null) return existing;
        FactionAttributes created = createFallbackAttributes(key);
        FACTION_REGISTRY.put(key, created);
        return created;
    }

    /**
     * Returns true if {@code factionId} has an explicit entry in the Faction Library.
     */
    public static boolean isFactionInLibrary(String factionId) {
        ensureLoaded();
        if (factionId == null) return false;
        return FACTION_REGISTRY.containsKey(factionId.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves the {@link FactionAttributes} for a faction ID, returning a default entry with
     * {@link OrganizationRating#HIGH} (80%) if the faction is not in the library.
     */
    public static FactionAttributes getAttributesForFaction(String factionId) {
        ensureLoaded();
        if (factionId != null && !factionId.trim().isEmpty()) {
            String key = factionId.trim().toLowerCase(Locale.ROOT);
            FactionAttributes found = FACTION_REGISTRY.get(key);
            if (found != null) {
                return found;
            }
            // Check heuristic AI / Threat / Abyssal substrings for dynamically generated submod IDs
            if (key.contains("abyssal") || key.contains("dweller")) {
                FactionAttributes dw = FACTION_REGISTRY.get("dweller");
                if (dw != null) return dw;
            }
            if (key.contains("threat")) {
                FactionAttributes th = FACTION_REGISTRY.get("threat");
                if (th != null) return th;
            }
        }
        return createFallbackAttributes(factionId);
    }

    /**
     * Returns an unmodifiable view of all registered faction IDs -> FactionAttributes.
     */
    public static Map<String, FactionAttributes> getAllRegisteredFactions() {
        ensureLoaded();
        return Collections.unmodifiableMap(FACTION_REGISTRY);
    }

    /**
     * Reloads built-in defaults and re-merges {@code data/config/division_faction_library.json}.
     */
    public static synchronized void reloadLibrary() {
        configLoaded = false;
        ensureLoaded();
    }

    // =========================================================================
    // FLEET ROLE DETECTION & ORGANIZATION RATING RESOLUTION
    // =========================================================================

    /**
     * Detects the functional {@link FleetRoleCategory} of a campaign fleet from its memory flags,
     * fleet type ID, fleet name, and civilian/freighter hull composition.
     */
    public static FleetRoleCategory resolveFleetRoleCategory(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.isPlayerFleet()) {
            return FleetRoleCategory.WAR_FLEET;
        }

        MemoryAPI mem = fleet.getMemoryWithoutUpdate();
        String fleetType = null;
        if (mem != null) {
            if (mem.getBoolean(MemFlags.MEMORY_KEY_SMUGGLER)) {
                return FleetRoleCategory.SMUGGLER_RAIDER;
            }
            if (mem.getBoolean(MemFlags.MEMORY_KEY_TRADE_FLEET)) {
                return FleetRoleCategory.MERCHANT_CONVOY;
            }
            if (mem.getBoolean(MemFlags.MEMORY_KEY_SCAVENGER)
                    || mem.getBoolean(MemFlags.SHRINE_PILGRIM_FLEET)
                    || mem.getBoolean(MemFlags.ACADEMY_FLEET)) {
                return FleetRoleCategory.SCAVENGER_EXPEDITION;
            }
            Object rawType = mem.get(MemFlags.MEMORY_KEY_FLEET_TYPE);
            if (rawType instanceof String) {
                fleetType = ((String) rawType).trim();
            }
        }

        if (fleetType != null && !fleetType.isEmpty()) {
            String ft = fleetType.toLowerCase(Locale.ROOT);
            if (FleetTypes.TRADE_SMUGGLER.equalsIgnoreCase(fleetType)
                    || FleetTypes.COMMERCE_RAIDERS.equalsIgnoreCase(fleetType)
                    || FleetTypes.RAIDER.equalsIgnoreCase(fleetType)
                    || FleetTypes.MERC_PRIVATEER.equalsIgnoreCase(fleetType)
                    || ft.contains("smuggl")
                    || ft.contains("raider")
                    || ft.contains("privateer")) {
                return FleetRoleCategory.SMUGGLER_RAIDER;
            }
            if (FleetTypes.TRADE.equalsIgnoreCase(fleetType)
                    || FleetTypes.TRADE_SMALL.equalsIgnoreCase(fleetType)
                    || FleetTypes.TRADE_LINER.equalsIgnoreCase(fleetType)
                    || FleetTypes.SUPPLY_FLEET.equalsIgnoreCase(fleetType)
                    || FleetTypes.FOOD_RELIEF_FLEET.equalsIgnoreCase(fleetType)
                    || ft.contains("trade")
                    || ft.contains("merchant")
                    || ft.contains("convoy")
                    || ft.contains("supply")
                    || ft.contains("relief")
                    || ft.contains("liner")
                    || ft.contains("courier")) {
                return FleetRoleCategory.MERCHANT_CONVOY;
            }
            if (FleetTypes.SCAVENGER_SMALL.equalsIgnoreCase(fleetType)
                    || FleetTypes.SCAVENGER_MEDIUM.equalsIgnoreCase(fleetType)
                    || FleetTypes.SCAVENGER_LARGE.equalsIgnoreCase(fleetType)
                    || FleetTypes.SHRINE_PILGRIMS.equalsIgnoreCase(fleetType)
                    || FleetTypes.ACADEMY_FLEET.equalsIgnoreCase(fleetType)
                    || ft.contains("scaveng")
                    || ft.contains("salvage")
                    || ft.contains("mining")
                    || ft.contains("miner")
                    || ft.contains("pilgrim")
                    || ft.contains("academy")
                    || ft.contains("survey")
                    || ft.contains("prospect")) {
                return FleetRoleCategory.SCAVENGER_EXPEDITION;
            }
            if (FleetTypes.PATROL_SMALL.equalsIgnoreCase(fleetType)
                    || FleetTypes.PATROL_MEDIUM.equalsIgnoreCase(fleetType)
                    || FleetTypes.MERC_SCOUT.equalsIgnoreCase(fleetType)
                    || FleetTypes.MERC_PATROL.equalsIgnoreCase(fleetType)
                    || FleetTypes.MERC_BOUNTY_HUNTER.equalsIgnoreCase(fleetType)
                    || FleetTypes.PERSON_BOUNTY_FLEET.equalsIgnoreCase(fleetType)
                    || FleetTypes.INVESTIGATORS.equalsIgnoreCase(fleetType)
                    || FleetTypes.LEAGUE_ENFORCER.equalsIgnoreCase(fleetType)) {
                return FleetRoleCategory.PATROL_PICKET;
            }
            if (FleetTypes.PATROL_LARGE.equalsIgnoreCase(fleetType)
                    || FleetTypes.TASK_FORCE.equalsIgnoreCase(fleetType)
                    || FleetTypes.INSPECTION_FLEET.equalsIgnoreCase(fleetType)
                    || FleetTypes.LEAGUE_ARMADA.equalsIgnoreCase(fleetType)
                    || FleetTypes.MERC_ARMADA.equalsIgnoreCase(fleetType)
                    || FleetTypes.SACRED_PROTECTORS.equalsIgnoreCase(fleetType)) {
                return FleetRoleCategory.WAR_FLEET;
            }
        }

        if (mem != null) {
            if (mem.getBoolean(MemFlags.MEMORY_KEY_WAR_FLEET)) {
                return FleetRoleCategory.WAR_FLEET;
            }
            if (mem.getBoolean(MemFlags.MEMORY_KEY_PATROL_FLEET)) {
                return fleet.getFleetPoints() >= 110
                        ? FleetRoleCategory.WAR_FLEET
                        : FleetRoleCategory.PATROL_PICKET;
            }
        }

        String name = fleet.getName() != null ? fleet.getName().toLowerCase(Locale.ROOT) : "";
        if (name.contains("smuggl") || name.contains("black market") || name.contains("runner")) {
            return FleetRoleCategory.SMUGGLER_RAIDER;
        }
        if (name.contains("scaveng") || name.contains("salvage") || name.contains("mining")
                || name.contains("prospector") || name.contains("pilgrim") || name.contains("survey")
                || name.contains("expedition")) {
            return FleetRoleCategory.SCAVENGER_EXPEDITION;
        }
        if (name.contains("merchant") || name.contains("trade") || name.contains("convoy")
                || name.contains("caravan") || name.contains("supply") || name.contains("relief")
                || name.contains("starliner") || name.contains("freight") || name.contains("courier")) {
            return FleetRoleCategory.MERCHANT_CONVOY;
        }
        if (name.contains("picket") || name.contains("scout") || name.contains("fast patrol")
                || name.contains("patrol")) {
            return fleet.getFleetPoints() >= 110
                    ? FleetRoleCategory.WAR_FLEET
                    : FleetRoleCategory.PATROL_PICKET;
        }

        // Composition fallback: if >= 35% of eligible hulls are civilian freighters/tankers/liners,
        // treat as a Merchant Convoy (or Scavenger if faction is scavengers).
        if (fleet.getFleetData() != null) {
            int total = 0;
            int civCount = 0;
            for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
                if (!PermanentDivisionManager.isEligibleFleetMember(m)) continue;
                total++;
                if (isCivilianLogisticsMember(m)) {
                    civCount++;
                }
            }
            if (total >= 2 && civCount * 100 >= total * 35) {
                String fId = fleet.getFaction() != null ? fleet.getFaction().getId() : "";
                if ("scavengers".equalsIgnoreCase(fId)) {
                    return FleetRoleCategory.SCAVENGER_EXPEDITION;
                }
                return FleetRoleCategory.MERCHANT_CONVOY;
            }
        }

        return FleetRoleCategory.WAR_FLEET;
    }

    /**
     * Resolves the effective {@link OrganizationRating} for a campaign fleet, taking into account
     * both its faction's library entry (defaulting to HIGH 80% if unknown) and its {@link FleetRoleCategory}.
     */
    public static OrganizationRating resolveEffectiveOrganizationRating(CampaignFleetAPI fleet) {
        if (fleet == null) return DEFAULT_FALLBACK_RATING;
        if (fleet.isPlayerFleet()) return OrganizationRating.PERFECT;

        NomenclatureCategory nomCat = DivisionNomenclature.resolveCategoryForCampaignFleet(fleet);
        if (nomCat == NomenclatureCategory.ABYSSAL_ERROR
                || nomCat == NomenclatureCategory.THREAT_SWARM
                || nomCat == NomenclatureCategory.POST_AI_WAR) {
            return OrganizationRating.PERFECT;
        }

        String factionId = (fleet.getFaction() != null && fleet.getFaction().getId() != null)
                ? fleet.getFaction().getId()
                : "independent";
        FactionAttributes attrs = getAttributesForFaction(factionId);
        FleetRoleCategory role = resolveFleetRoleCategory(fleet);
        boolean isStoryOrArmada = FactionFleetDoctrines.isStoryOrSpecialFleet(fleet);
        return attrs.getOrganizationRatingForRole(role, isStoryOrArmada);
    }

    /**
     * Resolves the base War-Fleet {@link OrganizationRating} for a faction ID (defaulting to HIGH 80%
     * if the faction is not in the library).
     */
    public static OrganizationRating resolveFactionOrganizationRating(String factionId) {
        return getAttributesForFaction(factionId).warOrganizationRating;
    }

    /**
     * Resolves the {@link OrganizationRating} for a faction ID and specific {@link FleetRoleCategory}.
     */
    public static OrganizationRating resolveFactionOrganizationRating(String factionId, FleetRoleCategory role) {
        return getAttributesForFaction(factionId).getOrganizationRatingForRole(role, false);
    }

    /**
     * Deterministically filters a fleet's eligible members according to its effective {@link OrganizationRating}
     * and {@link FleetRoleCategory}.
     *
     * Ships not selected by this method remain independent (unassigned) ships in the fleet — they appear
     * in the unassigned rows at the bottom of the campaign fleet tooltip and fight independently in battle.
     *
     * Guarantees that any selected subset can form at least one multi-ship division (>= 2 ships); if the
     * target count rounds below 2, returns an empty list so we never create a 1-ship "division" when
     * ships are supposed to be independent.
     */
    public static List<FleetMemberAPI> selectOrganizedFleetMembers(
            CampaignFleetAPI fleet,
            List<FleetMemberAPI> eligibleMembers,
            PermanentDivisionManager.FleetDoctrineConfig doctrine) {
        if (eligibleMembers == null || eligibleMembers.isEmpty()) {
            return Collections.emptyList();
        }
        OrganizationRating rating = resolveEffectiveOrganizationRating(fleet);
        FleetRoleCategory role = resolveFleetRoleCategory(fleet);
        String factionId = (fleet != null && fleet.getFaction() != null && fleet.getFaction().getId() != null)
                ? fleet.getFaction().getId()
                : "independent";
        long fleetSeed = (fleet != null && fleet.getId() != null)
                ? fleet.getId().hashCode() * 6364136223846793005L
                : 0x9E3779B97F4A7C15L;
        return selectOrganizedFleetMembersForRating(
                eligibleMembers,
                rating,
                role,
                factionId,
                doctrine,
                fleetSeed);
    }

    /**
     * Pure deterministic selection of which ships in {@code eligibleMembers} join divisions vs
     * remain independent under {@code rating} and {@code role}.
     */
    public static List<FleetMemberAPI> selectOrganizedFleetMembersForRating(
            List<FleetMemberAPI> eligibleMembers,
            OrganizationRating rating,
            FleetRoleCategory role,
            String factionId,
            PermanentDivisionManager.FleetDoctrineConfig doctrine,
            long fleetSeed) {
        if (eligibleMembers == null || eligibleMembers.isEmpty()) {
            return Collections.emptyList();
        }
        OrganizationRating effRating = rating != null ? rating : DEFAULT_FALLBACK_RATING;
        if (effRating == OrganizationRating.NONE || effRating.fraction <= 0f) {
            return Collections.emptyList();
        }
        int total = eligibleMembers.size();
        if (effRating == OrganizationRating.PERFECT || effRating.fraction >= 1.0f) {
            return new ArrayList<>(eligibleMembers);
        }
        if (total < 2) {
            return Collections.emptyList();
        }

        int targetCount = Math.round(total * effRating.fraction);
        if (targetCount < 2) {
            // Fewer than 2 ships cannot form a multi-ship division; leave all ships independent.
            return Collections.emptyList();
        }
        if (targetCount >= total) {
            return new ArrayList<>(eligibleMembers);
        }

        final FleetRoleCategory effRole = role != null ? role : FleetRoleCategory.WAR_FLEET;
        final Map<FleetMemberAPI, DivisionArchetype> archByMember = new LinkedHashMap<>();
        final Map<DivisionArchetype, Integer> archCounts = new EnumMap<>(DivisionArchetype.class);
        for (FleetMemberAPI m : eligibleMembers) {
            DivisionArchetype arch = PermanentDivisionManager.classifyFleetMemberArchetype(m, doctrine);
            if (arch == null) arch = DivisionArchetype.FORCE_DIVISION;
            if (arch == DivisionArchetype.CARRIER_GROUP) arch = DivisionArchetype.ESCORT_CARRIER_DIVISION;
            archByMember.put(m, arch);
            Integer c = archCounts.get(arch);
            archCounts.put(arch, (c == null ? 0 : c) + 1);
        }

        // Score each ship for organization affinity:
        //  - Ships belonging to an archetype bucket with >= 2 hulls get a strong cohesion bonus so selected ships
        //    actually group into 2+ ship divisions rather than lone singletons.
        //  - In Merchant/Scavenger convoys, civilian logistics hulls (freighters/tankers/liners/rigs) have high
        //    affinity to form the convoy body, followed by steady escort warships.
        //  - In War/Patrol fleets, flagships, officer-led ships, capitals, cruisers, and carriers have highest
        //    affinity, while reckless/un-officered frigates, converted hulls, and lone auxiliaries stay independent.
        final Map<FleetMemberAPI, Float> scoreByMember = new LinkedHashMap<>();
        for (FleetMemberAPI m : eligibleMembers) {
            DivisionArchetype arch = archByMember.get(m);
            int bucketSize = archCounts.containsKey(arch) ? archCounts.get(arch) : 1;
            float score = computeOrganizationAffinityScore(m, arch, bucketSize, effRole, factionId, fleetSeed);
            scoreByMember.put(m, score);
        }

        List<FleetMemberAPI> ranked = new ArrayList<>(eligibleMembers);
        Collections.sort(ranked, new Comparator<FleetMemberAPI>() {
            @Override
            public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                float sa = scoreByMember.get(a);
                float sb = scoreByMember.get(b);
                int cmp = Float.compare(sb, sa);
                if (cmp != 0) return cmp;
                String idA = a.getId() != null ? a.getId() : "";
                String idB = b.getId() != null ? b.getId() : "";
                return idA.compareTo(idB);
            }
        });

        List<FleetMemberAPI> selected = new ArrayList<>(ranked.subList(0, targetCount));

        // Ensure we don't accidentally pick a lone singleton of an archetype when another ship in `ranked`
        // could complete a 2-ship division within the exact same `targetCount` budget:
        Map<DivisionArchetype, List<FleetMemberAPI>> selectedByArch = new EnumMap<>(DivisionArchetype.class);
        for (FleetMemberAPI m : selected) {
            DivisionArchetype a = archByMember.get(m);
            List<FleetMemberAPI> list = selectedByArch.get(a);
            if (list == null) {
                list = new ArrayList<>();
                selectedByArch.put(a, list);
            }
            list.add(m);
        }

        Set<FleetMemberAPI> selectedSet = new HashSet<>(selected);
        for (int i = selected.size() - 1; i >= 0; i--) {
            FleetMemberAPI candidateToSwapOut = selected.get(i);
            DivisionArchetype outArch = archByMember.get(candidateToSwapOut);
            List<FleetMemberAPI> outBucket = selectedByArch.get(outArch);
            if (outBucket == null || outBucket.size() != 1) continue;

            // Can we swap this singleton out for an unselected ship that joins an existing selected archetype?
            FleetMemberAPI bestReplacement = null;
            for (int j = targetCount; j < ranked.size(); j++) {
                FleetMemberAPI unselected = ranked.get(j);
                if (selectedSet.contains(unselected)) continue;
                DivisionArchetype inArch = archByMember.get(unselected);
                List<FleetMemberAPI> inBucket = selectedByArch.get(inArch);
                if (inBucket != null && !inBucket.isEmpty()) {
                    bestReplacement = unselected;
                    break;
                }
            }
            if (bestReplacement != null) {
                selectedSet.remove(candidateToSwapOut);
                outBucket.remove(candidateToSwapOut);
                selected.set(i, bestReplacement);
                selectedSet.add(bestReplacement);
                selectedByArch.get(archByMember.get(bestReplacement)).add(bestReplacement);
            }
        }

        return selected;
    }

    private static float computeOrganizationAffinityScore(
            FleetMemberAPI m,
            DivisionArchetype arch,
            int bucketSize,
            FleetRoleCategory role,
            String factionId,
            long fleetSeed) {
        if (m == null) return -1000f;
        float score = 0f;

        // 1. Multi-ship archetype availability (strongly prefer ships that can form >= 2 ship divisions)
        if (bucketSize >= 2) {
            score += 400f;
        }

        // 2. Flagship & Officer discipline
        if (m.isFlagship()) {
            score += 220f;
        }
        PersonAPI cap = m.getCaptain();
        if (cap != null && !cap.isDefault()) {
            int lvl = cap.getStats() != null ? cap.getStats().getLevel() : 1;
            score += 45f + lvl * 12f;
            String pers = cap.getPersonalityAPI() != null ? cap.getPersonalityAPI().getId() : null;
            if (Personalities.STEADY.equals(pers) || Personalities.CAUTIOUS.equals(pers)) {
                score += 55f;
            } else if (Personalities.AGGRESSIVE.equals(pers)) {
                score += 25f;
            } else if (Personalities.RECKLESS.equals(pers)) {
                // Reckless captains chafe at rigid formation station-keeping and are likelier to operate solo
                score -= 65f;
            }
        }

        // 3. Fleet Role & Hull Class affinity
        boolean isCiv = isCivilianLogisticsMember(m) || arch == DivisionArchetype.SERVICE_GROUP;
        HullSize size = (m.getHullSpec() != null && m.getHullSpec().getHullSize() != null)
                ? m.getHullSpec().getHullSize()
                : HullSize.FRIGATE;

        if (role == FleetRoleCategory.MERCHANT_CONVOY || role == FleetRoleCategory.SCAVENGER_EXPEDITION) {
            if (isCiv) {
                // In a merchant convoy or salvage expedition, the freighters/liners/rigs form the main convoy
                score += 260f;
            } else {
                // Escort warships: destroyers and cruisers form the close escort, lone frigates rove on the flanks
                switch (size) {
                    case CRUISER:
                    case DESTROYER:
                        score += 120f;
                        break;
                    case CAPITAL_SHIP:
                        score += 140f;
                        break;
                    case FRIGATE:
                    default:
                        score += 40f;
                        break;
                }
            }
        } else {
            if (isCiv) {
                score -= 140f;
            } else {
                switch (size) {
                    case CAPITAL_SHIP:
                        score += 180f;
                        break;
                    case CRUISER:
                        score += 135f;
                        break;
                    case DESTROYER:
                        score += 90f;
                        break;
                    case FRIGATE:
                    default:
                        score += 35f;
                        break;
                }
            }
            if (arch == DivisionArchetype.PHASE_DIVISION) {
                // Phase ships often operate as independent wolfpacks/stalkers unless in a dedicated phase group
                score -= 30f;
            }
        }

        // 4. Deterministic per-hull tie-breaker hash (0..99) so fleets have organic variety
        String mId = m.getId() != null ? m.getId() : "";
        long h = mix64(fleetSeed ^ (mId.hashCode() * 0x9E3779B97F4A7C15L));
        float jitter = (float) (Math.floorMod(h, 1000L) / 10.0); // 0.0 .. 99.9
        score += jitter;
        return score;
    }

    public static boolean isCivilianLogisticsMember(FleetMemberAPI m) {
        if (m == null) return false;
        if (m.isCivilian()) return true;
        ShipHullSpecAPI spec = m.getHullSpec();
        if (spec == null || spec.getHints() == null) return false;
        EnumSet<ShipTypeHints> hints = spec.getHints();
        return hints.contains(ShipTypeHints.CIVILIAN)
                || hints.contains(ShipTypeHints.FREIGHTER)
                || hints.contains(ShipTypeHints.TANKER)
                || hints.contains(ShipTypeHints.LINER);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // =========================================================================
    // BUILT-IN FACTION LIBRARY & JSON MERGE LOADER
    // =========================================================================

    private static FactionAttributes createFallbackAttributes(String factionId) {
        FactionAttributes attr = new FactionAttributes(factionId != null ? factionId : "default");
        attr.nomenclatureCategory = NomenclatureCategory.HUMAN_NAVAL;
        attr.namingStyle = FactionNamingStyle.STANDARD_PREFIX;
        attr.basePreset = PlayerNamingPreset.NAVAL_STANDARD;
        attr.defaultNumbering = NumberingStyle.ARABIC;
        attr.battleshipNumbering = NumberingStyle.ROMAN;
        attr.battleshipDotRoman = true;
        attr.warOrganizationRating = DEFAULT_FALLBACK_RATING; // HIGH (80%)
        populateDefaultPresetPrefixes(attr, PlayerNamingPreset.NAVAL_STANDARD);
        return attr;
    }

    private static void populateDefaultPresetPrefixes(FactionAttributes attr, PlayerNamingPreset preset) {
        for (DivisionArchetype arch : DivisionArchetype.values()) {
            attr.warFleetPrefixes.put(arch, DivisionNomenclature.getDefaultPrefixForPreset(preset, arch));
        }
        if (preset == PlayerNamingPreset.NAVAL_STANDARD) {
            attr.warFleetPrefixes.put(DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div.");
        }
    }

    private static void populateMerchantPrefixes(
            FactionAttributes attr,
            String convoyName,
            String battleEscort,
            String carrierEscort,
            String cruiserEscort,
            String destroyerEscort,
            String frigateEscort,
            String phaseEscort) {
        attr.merchantFleetPrefixes.put(DivisionArchetype.SERVICE_GROUP, convoyName);
        attr.merchantFleetPrefixes.put(DivisionArchetype.BATTLESHIP_DIVISION, battleEscort);
        attr.merchantFleetPrefixes.put(DivisionArchetype.MISSILE_BATTLESHIP_DIVISION, battleEscort);
        attr.merchantFleetPrefixes.put(DivisionArchetype.FLEET_CARRIER_DIVISION, carrierEscort);
        attr.merchantFleetPrefixes.put(DivisionArchetype.LIGHT_CARRIER_DIVISION, carrierEscort);
        attr.merchantFleetPrefixes.put(DivisionArchetype.ESCORT_CARRIER_DIVISION, carrierEscort);
        attr.merchantFleetPrefixes.put(DivisionArchetype.CARRIER_GROUP, carrierEscort);
        attr.merchantFleetPrefixes.put(DivisionArchetype.CRUISER_DIVISION, cruiserEscort);
        attr.merchantFleetPrefixes.put(DivisionArchetype.MISSILE_CRUISER_DIVISION, cruiserEscort);
        attr.merchantFleetPrefixes.put(DivisionArchetype.STRIKE_CRUISER_DIVISION, cruiserEscort);
        attr.merchantFleetPrefixes.put(DivisionArchetype.DESTROYER_DIVISION, destroyerEscort);
        attr.merchantFleetPrefixes.put(DivisionArchetype.MISSILE_DESTROYER_DIVISION, destroyerEscort);
        attr.merchantFleetPrefixes.put(DivisionArchetype.FRIGATE_DIVISION, frigateEscort);
        attr.merchantFleetPrefixes.put(DivisionArchetype.MISSILE_FRIGATE_DIVISION, frigateEscort);
        attr.merchantFleetPrefixes.put(DivisionArchetype.PHASE_DIVISION, phaseEscort);
        attr.merchantFleetPrefixes.put(DivisionArchetype.FORCE_DIVISION, destroyerEscort);
    }

    private static void installBuiltInLibrary() {
        FACTION_REGISTRY.clear();
        DEFAULT_FALLBACK_RATING = OrganizationRating.HIGH;

        // Global role-based prefix defaults (used when a faction doesn't specify custom role prefixes)
        DEFAULT_MERCHANT_PREFIXES.clear();
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.SERVICE_GROUP, "Merchant Convoy");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.BATTLESHIP_DIVISION, "Heavy Convoy Escort");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.MISSILE_BATTLESHIP_DIVISION, "Heavy Convoy Escort");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.FLEET_CARRIER_DIVISION, "Convoy Carrier Escort");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.LIGHT_CARRIER_DIVISION, "Convoy Carrier Escort");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.ESCORT_CARRIER_DIVISION, "Convoy Escort Wing");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.CARRIER_GROUP, "Convoy Carrier Escort");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.CRUISER_DIVISION, "Convoy Cruiser Escort");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.MISSILE_CRUISER_DIVISION, "Convoy Cruiser Escort");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.STRIKE_CRUISER_DIVISION, "Convoy Outrider Escort");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.DESTROYER_DIVISION, "Convoy Escort");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.MISSILE_DESTROYER_DIVISION, "Convoy Escort");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.FRIGATE_DIVISION, "Convoy Picket");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.MISSILE_FRIGATE_DIVISION, "Convoy Picket");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.PHASE_DIVISION, "Convoy Scout Screen");
        DEFAULT_MERCHANT_PREFIXES.put(DivisionArchetype.FORCE_DIVISION, "Convoy Guard");

        DEFAULT_SMUGGLER_PREFIXES.clear();
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.SERVICE_GROUP, "Blockade Runner Group");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.BATTLESHIP_DIVISION, "Heavy Raider Group");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.MISSILE_BATTLESHIP_DIVISION, "Heavy Raider Group");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.FLEET_CARRIER_DIVISION, "Raider Carrier Group");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.LIGHT_CARRIER_DIVISION, "Raider Carrier Group");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.ESCORT_CARRIER_DIVISION, "Runner Carrier Group");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.CARRIER_GROUP, "Raider Carrier Group");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.CRUISER_DIVISION, "Raider Cruiser Group");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.MISSILE_CRUISER_DIVISION, "Raider Cruiser Group");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.STRIKE_CRUISER_DIVISION, "Fast Runner Group");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.DESTROYER_DIVISION, "Runner Escort");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.MISSILE_DESTROYER_DIVISION, "Runner Escort");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.FRIGATE_DIVISION, "Runner Outriders");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.MISSILE_FRIGATE_DIVISION, "Runner Outriders");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.PHASE_DIVISION, "Shadow Runner Group");
        DEFAULT_SMUGGLER_PREFIXES.put(DivisionArchetype.FORCE_DIVISION, "Smuggler Group");

        DEFAULT_SCAVENGER_PREFIXES.clear();
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.SERVICE_GROUP, "Salvage Convoy");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.BATTLESHIP_DIVISION, "Heavy Salvage Guard");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.MISSILE_BATTLESHIP_DIVISION, "Heavy Salvage Guard");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.FLEET_CARRIER_DIVISION, "Salvage Carrier Group");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.LIGHT_CARRIER_DIVISION, "Salvage Carrier Group");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.ESCORT_CARRIER_DIVISION, "Survey Carrier Group");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.CARRIER_GROUP, "Salvage Carrier Group");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.CRUISER_DIVISION, "Salvage Cruiser Guard");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.MISSILE_CRUISER_DIVISION, "Salvage Cruiser Guard");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.STRIKE_CRUISER_DIVISION, "Expedition Outriders");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.DESTROYER_DIVISION, "Salvage Guard");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.MISSILE_DESTROYER_DIVISION, "Salvage Guard");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.FRIGATE_DIVISION, "Claim Watch");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.MISSILE_FRIGATE_DIVISION, "Claim Watch");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.PHASE_DIVISION, "Survey Recon Group");
        DEFAULT_SCAVENGER_PREFIXES.put(DivisionArchetype.FORCE_DIVISION, "Expedition Group");

        // 1. HEGEMONY (War: VERY_HIGH 90%, Patrol: HIGH 80%, Merchant: AVERAGE 50%, Smuggler: LOW 25%)
        FactionAttributes hegemony = createFallbackAttributes("hegemony");
        hegemony.warOrganizationRating = OrganizationRating.VERY_HIGH;
        hegemony.roleOrganizationOverrides.put(FleetRoleCategory.PATROL_PICKET, OrganizationRating.HIGH);
        hegemony.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.AVERAGE);
        hegemony.roleOrganizationOverrides.put(FleetRoleCategory.SMUGGLER_RAIDER, OrganizationRating.LOW);
        hegemony.roleOrganizationOverrides.put(FleetRoleCategory.SCAVENGER_EXPEDITION, OrganizationRating.AVERAGE);
        populateMerchantPrefixes(hegemony,
                "Chartered Convoy", "Convoy Battle Escort", "Convoy Carrier Wing",
                "Convoy Cruiser Escort", "Convoy Destroyer Escort", "Convoy Picket", "Convoy Phase Screen");
        registerFaction("hegemony", hegemony);

        // 2. IRON SHELL / XIV SENTINELS (War: VERY_HIGH 90% / PERFECT 100%)
        FactionAttributes ironShell = createFallbackAttributes("ironshell");
        ironShell.aliases.addAll(Arrays.asList("iron shell 1.183aj"));
        ironShell.warOrganizationRating = OrganizationRating.VERY_HIGH;
        ironShell.roleOrganizationOverrides.put(FleetRoleCategory.PATROL_PICKET, OrganizationRating.VERY_HIGH);
        ironShell.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.HIGH);
        populateMerchantPrefixes(ironShell,
                "Ministry Tax Convoy", "Chitagupta Heavy Escort", "Convoy Flight Wing",
                "Commerce Cruiser Escort", "Revenue Escort Flotilla", "Customs Picket", "Revenue Phase Screen");
        registerFaction("ironshell", ironShell);

        FactionAttributes ironSentinel = createFallbackAttributes("ironsentinel");
        ironSentinel.warOrganizationRating = OrganizationRating.PERFECT;
        registerFaction("ironsentinel", ironSentinel);

        // 3. TRI-TACHYON CORPORATION (War: VERY_HIGH 90%, Patrol: HIGH 80%, Merchant: HIGH 80%, Smuggler: AVERAGE 50%)
        FactionAttributes tritachyon = createFallbackAttributes("tritachyon");
        tritachyon.aliases.addAll(Arrays.asList("hte", "tte"));
        tritachyon.warOrganizationRating = OrganizationRating.VERY_HIGH;
        tritachyon.roleOrganizationOverrides.put(FleetRoleCategory.PATROL_PICKET, OrganizationRating.HIGH);
        tritachyon.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.HIGH);
        tritachyon.roleOrganizationOverrides.put(FleetRoleCategory.SMUGGLER_RAIDER, OrganizationRating.AVERAGE);
        tritachyon.roleOrganizationOverrides.put(FleetRoleCategory.SCAVENGER_EXPEDITION, OrganizationRating.HIGH);
        populateMerchantPrefixes(tritachyon,
                "Commercial Caravan", "Capital Protection Unit", "Caravan Flight Group",
                "Asset Security Unit", "Convoy Assurance Unit", "Caravan Picket Unit", "Special Commerce Escort");
        registerFaction("tritachyon", tritachyon);

        // 4. PERSEAN LEAGUE & MAYASURA (War: HIGH 80%, Patrol: HIGH 80%, Merchant: AVERAGE 50%, Smuggler: LOW 25%)
        FactionAttributes persean = createFallbackAttributes("persean");
        persean.aliases.addAll(Arrays.asList("persean_league", "mayasura", "mayasuran navy"));
        persean.namingStyle = FactionNamingStyle.CULTURAL_PERSEAN;
        persean.commanderPossessiveChance = 15;
        persean.warUnitNouns.addAll(Arrays.asList(
                "Division", "Flotilla", "Squadron", "Group", "Unit", "Fleet", "Phalanx", "Task Group"));
        persean.merchantConvoyNouns.addAll(Arrays.asList(
                "Merchant Flotilla", "Trade Convoy", "Free Trade Convoy", "Merchant Convoy", "Cargo Flotilla", "Trade Squadron"));
        persean.merchantEscortNouns.addAll(Arrays.asList(
                "Convoy Escort", "Caravan Guard", "Escort Lochos", "Trade Picket", "Convoy Phalanx", "Close Escort"));
        persean.warOrganizationRating = OrganizationRating.HIGH;
        persean.roleOrganizationOverrides.put(FleetRoleCategory.PATROL_PICKET, OrganizationRating.HIGH);
        persean.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.AVERAGE);
        persean.roleOrganizationOverrides.put(FleetRoleCategory.SMUGGLER_RAIDER, OrganizationRating.LOW);
        persean.roleOrganizationOverrides.put(FleetRoleCategory.SCAVENGER_EXPEDITION, OrganizationRating.AVERAGE);
        populateMerchantPrefixes(persean,
                "Merchant Flotilla", "Convoy Phalanx", "Convoy Carrier Group",
                "Convoy Mora", "Convoy Lochos", "Convoy Peltast", "Convoy Krypteia");
        registerFaction("persean", persean);

        FactionAttributes mayasuranGuard = createFallbackAttributes("mayasuran_guard");
        mayasuranGuard.namingStyle = FactionNamingStyle.CULTURAL_PERSEAN;
        mayasuranGuard.warOrganizationRating = OrganizationRating.PERFECT;
        registerFaction("mayasuran_guard", mayasuranGuard);

        // 5. SINDRIAN DIKTAT & LION'S GUARD (Diktat War: VERY_HIGH 90%, Lion's Guard: PERFECT 100%)
        FactionAttributes diktat = createFallbackAttributes("sindrian_diktat");
        diktat.aliases.add("sindran_diktat");
        diktat.warOrganizationRating = OrganizationRating.VERY_HIGH;
        diktat.roleOrganizationOverrides.put(FleetRoleCategory.PATROL_PICKET, OrganizationRating.HIGH);
        diktat.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.HIGH);
        diktat.roleOrganizationOverrides.put(FleetRoleCategory.SMUGGLER_RAIDER, OrganizationRating.LOW);
        populateMerchantPrefixes(diktat,
                "State Fuel Convoy", "Fuel Convoy Heavy Guard", "Solar Convoy Wing",
                "Fuel Convoy Cruiser Guard", "Convoy Vanguard Flotilla", "Convoy Picket Guard", "Convoy Special Guard");
        registerFaction("sindrian_diktat", diktat);

        FactionAttributes lionsGuard = createFallbackAttributes("lions_guard");
        lionsGuard.warOrganizationRating = OrganizationRating.PERFECT;
        populateMerchantPrefixes(lionsGuard,
                "State Fuel Convoy", "Executor Convoy Guard", "Solar Convoy Wing",
                "Lion's Convoy Phalanx", "Lion's Convoy Flotilla", "Lion's Convoy Picket", "Lion's Special Guard");
        registerFaction("lions_guard", lionsGuard);

        // 6. LUDDIC CHURCH & KNIGHTS OF LUDD (War: HIGH 80%, Knights Elite: VERY_HIGH 90%, Merchant/Pilgrim: AVERAGE 50%)
        FactionAttributes church = createFallbackAttributes("luddic_church");
        church.warOrganizationRating = OrganizationRating.HIGH;
        church.roleOrganizationOverrides.put(FleetRoleCategory.PATROL_PICKET, OrganizationRating.HIGH);
        church.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.AVERAGE);
        church.roleOrganizationOverrides.put(FleetRoleCategory.SCAVENGER_EXPEDITION, OrganizationRating.AVERAGE);
        populateMerchantPrefixes(church,
                "Pilgrim Trade Convoy", "Bastion Convoy Escort", "Ark Convoy Host",
                "Convoy Warden Group", "Convoy Sword Flotilla", "Pilgrim Watchers", "Penitent Escort");
        church.scavengerFleetPrefixes.put(DivisionArchetype.SERVICE_GROUP, "Shrine Pilgrim Convoy");
        church.scavengerFleetPrefixes.put(DivisionArchetype.FRIGATE_DIVISION, "Shrine Watchers");
        church.scavengerFleetPrefixes.put(DivisionArchetype.DESTROYER_DIVISION, "Pilgrim Escort Flotilla");
        registerFaction("luddic_church", church);

        FactionAttributes kol = createFallbackAttributes("knights_of_ludd");
        kol.aliases.addAll(Arrays.asList("knights_of_selkie", "zea_dawn", "zea_dusk", "zea_elysians", "templars"));
        kol.warOrganizationRating = OrganizationRating.VERY_HIGH;
        kol.roleOrganizationOverrides.put(FleetRoleCategory.PATROL_PICKET, OrganizationRating.HIGH);
        kol.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.HIGH);
        populateMerchantPrefixes(kol,
                "Sanctuary Relief Convoy", "Templar Heavy Escort", "Sanctuary Carrier Host",
                "Crusader Convoy Guard", "Reliquary Escort Flotilla", "Vigil Picket", "Duskborne Veil Escort");
        registerFaction("knights_of_ludd", kol);

        // 7. LUDDIC PATH (Fanatic cells — War: AVERAGE 50%, Patrol/Raider: LOW 25%, Smuggler/Merchant: LOW 25%)
        FactionAttributes path = createFallbackAttributes("luddic_path");
        path.aliases.add("luddenhance");
        path.namingStyle = FactionNamingStyle.CULTURAL_PIRATE_INDIE;
        path.commanderPossessiveChance = 40;
        path.warOrganizationRating = OrganizationRating.AVERAGE;
        path.roleOrganizationOverrides.put(FleetRoleCategory.PATROL_PICKET, OrganizationRating.LOW);
        path.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.LOW);
        path.roleOrganizationOverrides.put(FleetRoleCategory.SMUGGLER_RAIDER, OrganizationRating.LOW);
        path.roleOrganizationOverrides.put(FleetRoleCategory.SCAVENGER_EXPEDITION, OrganizationRating.LOW);
        path.warUnitNouns.addAll(Arrays.asList(
                "Flotilla", "Fleet", "Warband", "Pack", "Group", "Unit", "Division"));
        path.merchantConvoyNouns.addAll(Arrays.asList(
                "Tithe Convoy", "Pilgrim Caravan", "Supply Caravan", "Faithful Convoy"));
        path.merchantEscortNouns.addAll(Arrays.asList(
                "Caravan Watch", "Zealot Escort", "Faithful Guard", "Convoy Pack"));
        path.smugglerConvoyNouns.addAll(Arrays.asList(
                "Smuggler Cell", "Gunrunner Pack", "Hidden Caravan", "Supply Cell"));
        path.smugglerEscortNouns.addAll(Arrays.asList(
                "Raider Cell", "Ambush Pack", "Martyr Pack", "Outrider Cell"));
        registerFaction("luddic_path", path);

        // 8. PIRATES & OUTLAW FACTIONS (War: LOW 25%, Patrol/Raider/Smuggler/Merchant: LOW 25%)
        FactionAttributes pirates = createFallbackAttributes("pirates");
        pirates.aliases.addAll(Arrays.asList(
                "pirate_privateers", "jaydeepiracy", "jdp_flowerfish", "hmi_sv", "fang"));
        pirates.namingStyle = FactionNamingStyle.CULTURAL_PIRATE_INDIE;
        pirates.commanderPossessiveChance = 55;
        pirates.warOrganizationRating = OrganizationRating.LOW;
        pirates.roleOrganizationOverrides.put(FleetRoleCategory.PATROL_PICKET, OrganizationRating.LOW);
        pirates.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.LOW);
        pirates.roleOrganizationOverrides.put(FleetRoleCategory.SMUGGLER_RAIDER, OrganizationRating.LOW);
        pirates.roleOrganizationOverrides.put(FleetRoleCategory.SCAVENGER_EXPEDITION, OrganizationRating.LOW);
        pirates.warUnitNouns.addAll(Arrays.asList(
                "Flotilla", "Fleet", "Warband", "Pack", "Group", "Unit", "Gang", "Division"));
        pirates.merchantConvoyNouns.addAll(Arrays.asList(
                "Prize Convoy", "Booty Caravan", "Fence Convoy", "Smuggler Convoy", "Trader Pack"));
        pirates.merchantEscortNouns.addAll(Arrays.asList(
                "Convoy Guard", "Prize Escort", "Outrider Pack", "Cutthroat Escort"));
        pirates.smugglerConvoyNouns.addAll(Arrays.asList(
                "Contraband Run", "Blockade Runners", "Smuggler Pack", "Prize Crew", "Black-Market Convoy"));
        pirates.smugglerEscortNouns.addAll(Arrays.asList(
                "Runner Escort", "Raider Pack", "Cutthroat Pack", "Privateer Pack", "Outrider Gang"));
        pirates.scavengerConvoyNouns.addAll(Arrays.asList(
                "Scrapper Crew", "Wreck-Picker Pack", "Stripper Convoy", "Salvage Gang"));
        pirates.scavengerEscortNouns.addAll(Arrays.asList(
                "Scrapper Guard", "Claim-Jumper Pack", "Vulture Pack"));
        registerFaction("pirates", pirates);

        // 9. INDEPENDENTS (War: AVERAGE 50%, Patrol: AVERAGE 50%, Merchant: AVERAGE 50%, Scavenger/Smuggler: LOW 25%)
        FactionAttributes independent = createFallbackAttributes("independent");
        independent.aliases.addAll(Arrays.asList("brighton", "hmi_brighton", "pack"));
        independent.namingStyle = FactionNamingStyle.CULTURAL_PIRATE_INDIE;
        independent.commanderPossessiveChance = 40;
        independent.warOrganizationRating = OrganizationRating.AVERAGE;
        independent.roleOrganizationOverrides.put(FleetRoleCategory.PATROL_PICKET, OrganizationRating.AVERAGE);
        independent.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.AVERAGE);
        independent.roleOrganizationOverrides.put(FleetRoleCategory.SMUGGLER_RAIDER, OrganizationRating.LOW);
        independent.roleOrganizationOverrides.put(FleetRoleCategory.SCAVENGER_EXPEDITION, OrganizationRating.LOW);
        independent.warUnitNouns.addAll(Arrays.asList(
                "Flotilla", "Fleet", "Warband", "Pack", "Group", "Unit", "Division"));
        independent.merchantConvoyNouns.addAll(Arrays.asList(
                "Merchant Convoy", "Trade Convoy", "Freighter Convoy", "Trade Flotilla", "Cargo Flotilla", "Merchant Group", "Caravan"));
        independent.merchantEscortNouns.addAll(Arrays.asList(
                "Convoy Escort", "Caravan Guard", "Escort Flotilla", "Outrider Escort", "Trade Guard", "Close Escort"));
        independent.smugglerConvoyNouns.addAll(Arrays.asList(
                "Free-Trader Run", "Blockade Runners", "Smuggler Convoy", "Runner Group"));
        independent.smugglerEscortNouns.addAll(Arrays.asList(
                "Runner Escort", "Privateer Group", "Outrider Pack", "Escort Unit"));
        independent.scavengerConvoyNouns.addAll(Arrays.asList(
                "Salvage Convoy", "Recovery Flotilla", "Prospecting Group", "Mining Convoy", "Survey Convoy", "Salvage Rig Group"));
        independent.scavengerEscortNouns.addAll(Arrays.asList(
                "Salvage Guard", "Claim Watch", "Expedition Escort", "Rig Escort"));
        registerFaction("independent", independent);

        // 10. CARTER'S FREETRADERS & INDEVO COURIERS (Organized Merchant Houses — Merchant: HIGH 80%, War: AVERAGE 50%)
        FactionAttributes cft = createFallbackAttributes("cft");
        cft.aliases.add("indevo_couriers");
        cft.namingStyle = FactionNamingStyle.CULTURAL_PIRATE_INDIE;
        cft.commanderPossessiveChance = 35;
        cft.warOrganizationRating = OrganizationRating.AVERAGE;
        cft.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.HIGH);
        cft.roleOrganizationOverrides.put(FleetRoleCategory.PATROL_PICKET, OrganizationRating.AVERAGE);
        cft.warUnitNouns.addAll(Arrays.asList("Flotilla", "Fleet", "Group", "Unit", "Division", "Caravan"));
        cft.merchantConvoyNouns.addAll(Arrays.asList(
                "Freetrader Convoy", "Trade Caravan", "Merchant Flotilla", "Chartered Caravan", "Express Convoy"));
        cft.merchantEscortNouns.addAll(Arrays.asList(
                "Caravan Guard", "Convoy Escort", "Freetrader Escort", "Outrider Guard"));
        registerFaction("cft", cft);

        // 11. SCAVENGERS & INDUSTRIAL / MINING GUILDS (Scavengers: LOW 25%; HMI / JYD: AVERAGE 50%)
        FactionAttributes scavs = createFallbackAttributes("scavengers");
        scavs.aliases.add("astroidships");
        scavs.namingStyle = FactionNamingStyle.CULTURAL_PIRATE_INDIE;
        scavs.commanderPossessiveChance = 40;
        scavs.warOrganizationRating = OrganizationRating.LOW;
        scavs.roleOrganizationOverrides.put(FleetRoleCategory.SCAVENGER_EXPEDITION, OrganizationRating.LOW);
        scavs.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.LOW);
        scavs.warUnitNouns.addAll(Arrays.asList("Flotilla", "Pack", "Group", "Unit", "Crew"));
        scavs.scavengerConvoyNouns.addAll(Arrays.asList(
                "Salvage Convoy", "Recovery Crew", "Wrecking Flotilla", "Prospecting Crew", "Rig Group"));
        scavs.scavengerEscortNouns.addAll(Arrays.asList(
                "Salvage Guard", "Claim Watch", "Rig Escort", "Outrider Watch"));
        registerFaction("scavengers", scavs);

        FactionAttributes hmi = createFallbackAttributes("hmi");
        hmi.aliases.addAll(Arrays.asList("hmi_exec", "jyd"));
        hmi.namingStyle = FactionNamingStyle.CULTURAL_PIRATE_INDIE;
        hmi.commanderPossessiveChance = 35;
        hmi.warOrganizationRating = OrganizationRating.AVERAGE;
        hmi.roleOrganizationOverrides.put(FleetRoleCategory.PATROL_PICKET, OrganizationRating.AVERAGE);
        hmi.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.AVERAGE);
        hmi.roleOrganizationOverrides.put(FleetRoleCategory.SCAVENGER_EXPEDITION, OrganizationRating.AVERAGE);
        hmi.warUnitNouns.addAll(Arrays.asList("Flotilla", "Group", "Unit", "Division", "Crew", "Pack"));
        hmi.merchantConvoyNouns.addAll(Arrays.asList(
                "Ore Convoy", "Industrial Haul", "Freighter Convoy", "Company Convoy", "Bulk Flotilla"));
        hmi.merchantEscortNouns.addAll(Arrays.asList(
                "Company Guard", "Haul Escort", "Hazard Escort", "Security Unit"));
        hmi.scavengerConvoyNouns.addAll(Arrays.asList(
                "Mining Crew", "Deep-Core Rig Group", "Recovery Convoy", "Strip-Mining Flotilla"));
        hmi.scavengerEscortNouns.addAll(Arrays.asList(
                "Claim Security", "Rig Guard", "Hazard Watch"));
        registerFaction("hmi", hmi);

        // 12. MERCENARIES (Chartered Companies — War/Patrol/Escort: HIGH 80%)
        FactionAttributes merc = createFallbackAttributes("mercenary");
        merc.aliases.addAll(Arrays.asList("famous_bounty", "ml_bounty"));
        merc.namingStyle = FactionNamingStyle.CULTURAL_PIRATE_INDIE;
        merc.commanderPossessiveChance = 45;
        merc.warOrganizationRating = OrganizationRating.HIGH;
        merc.roleOrganizationOverrides.put(FleetRoleCategory.PATROL_PICKET, OrganizationRating.HIGH);
        merc.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.HIGH);
        merc.warUnitNouns.addAll(Arrays.asList("Company", "Squadron", "Flotilla", "Group", "Unit", "Division", "Lance"));
        merc.merchantConvoyNouns.addAll(Arrays.asList("Contract Convoy", "Chartered Convoy", "Bonded Caravan"));
        merc.merchantEscortNouns.addAll(Arrays.asList("Contract Escort", "Charter Guard", "Security Lance"));
        registerFaction("mercenary", merc);

        // 13. MAJOR MODDED MILITARY / CORPORATE FACTIONS
        FactionAttributes uaf = createFallbackAttributes("uaf");
        uaf.warOrganizationRating = OrganizationRating.VERY_HIGH;
        uaf.roleOrganizationOverrides.put(FleetRoleCategory.PATROL_PICKET, OrganizationRating.HIGH);
        uaf.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.HIGH);
        populateMerchantPrefixes(uaf,
                "Auroran Bakery & Trade Convoy", "Royal Convoy Battle Escort", "Auroran Escort Carrier Wing",
                "Royal Convoy Cruiser Escort", "Royal Convoy Destroyer Escort", "Auroran Convoy Picket", "Convoy Phase Screen");
        registerFaction("uaf", uaf);

        FactionAttributes uafElite = createFallbackAttributes("uaf_november");
        uafElite.aliases.add("uaf_automata");
        uafElite.warOrganizationRating = OrganizationRating.PERFECT;
        registerFaction("uaf_november", uafElite);

        FactionAttributes imperium = createFallbackAttributes("interstellarimperium");
        imperium.aliases.add("imperium");
        imperium.warOrganizationRating = OrganizationRating.VERY_HIGH;
        imperium.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.HIGH);
        populateMerchantPrefixes(imperium,
                "Imperial Grain & Trade Convoy", "Legionary Convoy Escort", "Imperial Convoy Carrier Wing",
                "Cohort Cruiser Escort", "Manipular Escort", "Velites Convoy Picket", "Speculatores Screen");
        registerFaction("interstellarimperium", imperium);

        FactionAttributes impGuard = createFallbackAttributes("ii_imperial_guard");
        impGuard.warOrganizationRating = OrganizationRating.PERFECT;
        registerFaction("ii_imperial_guard", impGuard);

        FactionAttributes diable = createFallbackAttributes("diableavionics");
        diable.warOrganizationRating = OrganizationRating.VERY_HIGH;
        diable.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.HIGH);
        registerFaction("diableavionics", diable);

        FactionAttributes xhan = createFallbackAttributes("xhanempire");
        xhan.aliases.add("vantanet");
        xhan.warOrganizationRating = OrganizationRating.VERY_HIGH;
        xhan.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.HIGH);
        registerFaction("xhanempire", xhan);

        FactionAttributes scy = createFallbackAttributes("scy");
        scy.aliases.add("scynation");
        scy.warOrganizationRating = OrganizationRating.HIGH;
        scy.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.AVERAGE);
        registerFaction("scy", scy);

        FactionAttributes corporateTechno = createFallbackAttributes("koc");
        corporateTechno.aliases.addAll(Arrays.asList(
                "everybody loves koc", "eridani", "bhilai", "kyeltziv", "kyeltziv technocracy",
                "unitedpamed", "ora", "shadow_industry", "neutrinocorp", "metelson",
                "syndicate_asp", "tahlan_greathouses", "blackrock_driveyards", "dassault_mikoyan", "sms_curator"));
        corporateTechno.warOrganizationRating = OrganizationRating.HIGH;
        corporateTechno.roleOrganizationOverrides.put(FleetRoleCategory.MERCHANT_CONVOY, OrganizationRating.HIGH);
        registerFaction("koc", corporateTechno);

        FactionAttributes legio = createFallbackAttributes("tahlan_legioinfernalis");
        legio.warOrganizationRating = OrganizationRating.VERY_HIGH;
        registerFaction("tahlan_legioinfernalis", legio);

        FactionAttributes stealthRaiders = createFallbackAttributes("maybegoogle");
        stealthRaiders.aliases.addAll(Arrays.asList("rat_exotech", "draco", "jdp_deadcomm", "jdp_laplacians"));
        stealthRaiders.warOrganizationRating = OrganizationRating.AVERAGE;
        registerFaction("maybegoogle", stealthRaiders);

        // 14. POST-AI WAR / THE THREAT / ABYSSAL (PERFECT 100%)
        FactionAttributes remnant = createFallbackAttributes("remnant");
        remnant.aliases.addAll(Arrays.asList(
                "remnants", "derelict", "nex_derelict", "domres", "indevo_derelict", "indevo_converters"));
        remnant.nomenclatureCategory = NomenclatureCategory.POST_AI_WAR;
        remnant.namingStyle = FactionNamingStyle.POST_AI_WAR_UNKNOWN;
        remnant.warOrganizationRating = OrganizationRating.PERFECT;
        registerFaction("remnant", remnant);

        FactionAttributes threat = createFallbackAttributes("threat");
        threat.aliases.addAll(Arrays.asList("mess", "mess_remnant", "hmi_nightmare"));
        threat.nomenclatureCategory = NomenclatureCategory.THREAT_SWARM;
        threat.namingStyle = FactionNamingStyle.THREAT_CORRUPTED;
        threat.warOrganizationRating = OrganizationRating.PERFECT;
        registerFaction("threat", threat);

        FactionAttributes dweller = createFallbackAttributes("dweller");
        dweller.aliases.addAll(Arrays.asList(
                "omega", "liminal", "rat_abyssals", "rat_abyssals_deep", "rat_abyssals_deep_seraph",
                "rat_abyssals_primordial", "rat_abyssals_primordials", "rat_abyssals_sim",
                "rat_abyssals_harmony", "rat_abyssals_serenity", "rat_abyssals_solitude", "rat_abyssals_wastes"));
        dweller.nomenclatureCategory = NomenclatureCategory.ABYSSAL_ERROR;
        dweller.namingStyle = FactionNamingStyle.ABYSSAL_ERROR;
        dweller.warOrganizationRating = OrganizationRating.PERFECT;
        registerFaction("dweller", dweller);
    }

    public static synchronized void ensureLoaded() {
        if (configLoaded) return;
        installBuiltInLibrary();
        try {
            ensureJsonHandlesInitialized();
            Object rootJson = null;
            if (Global.getSettings() != null && mhGetMergedJsonForMod != null) {
                try {
                    rootJson = mhGetMergedJsonForMod.invoke(Global.getSettings(), CONFIG_PATH, MOD_ID);
                } catch (Throwable ignored) {
                }
            }
            if (rootJson == null && Global.getSettings() == null && mhJsonObjFromStringCtor != null) {
                // Offline headless unit-test fallback only (never invoked inside the Starsector runtime)
                File localFile = new File("mod/" + CONFIG_PATH);
                if (!localFile.isFile()) {
                    localFile = new File(CONFIG_PATH);
                }
                if (localFile.isFile()) {
                    String raw = new String(Files.readAllBytes(localFile.toPath()), StandardCharsets.UTF_8);
                    rootJson = mhJsonObjFromStringCtor.invoke(raw);
                }
            }
            if (rootJson != null) {
                parseMergedLibraryJson(rootJson);
            }
        } catch (Throwable t) {
            LOG.warn("[DivisionAI] Failed to parse division_faction_library.json; built-in library active.", t);
        } finally {
            configLoaded = true;
        }
    }

    private static synchronized void ensureJsonHandlesInitialized() {
        if (jsonHandlesResolved) return;
        jsonHandlesResolved = true;
        try {
            ClassLoader cl = FactionLibrary.class.getClassLoader();
            Class<?> settingsClass = Class.forName("com.fs.starfarer.api.SettingsAPI", false, cl);
            Class<?> jsonObjClass = Class.forName("org.json.JSONObject", false, cl);
            Class<?> jsonArrClass = Class.forName("org.json.JSONArray", false, cl);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            mhGetMergedJsonForMod = lookup.findVirtual(
                    settingsClass,
                    "getMergedJSONForMod",
                    MethodType.methodType(jsonObjClass, String.class, String.class));
            mhJsonObjFromStringCtor = lookup.findConstructor(
                    jsonObjClass,
                    MethodType.methodType(void.class, String.class));
            mhJsonObjOptString = lookup.findVirtual(
                    jsonObjClass,
                    "optString",
                    MethodType.methodType(String.class, String.class, String.class));
            mhJsonObjOptInt = lookup.findVirtual(
                    jsonObjClass,
                    "optInt",
                    MethodType.methodType(int.class, String.class, int.class));
            mhJsonObjOptBoolean = lookup.findVirtual(
                    jsonObjClass,
                    "optBoolean",
                    MethodType.methodType(boolean.class, String.class, boolean.class));
            mhJsonObjOptJsonObject = lookup.findVirtual(
                    jsonObjClass,
                    "optJSONObject",
                    MethodType.methodType(jsonObjClass, String.class));
            mhJsonObjOptJsonArray = lookup.findVirtual(
                    jsonObjClass,
                    "optJSONArray",
                    MethodType.methodType(jsonArrClass, String.class));
            mhJsonObjNames = lookup.findVirtual(
                    jsonObjClass,
                    "names",
                    MethodType.methodType(jsonArrClass));
            mhJsonArrLength = lookup.findVirtual(
                    jsonArrClass,
                    "length",
                    MethodType.methodType(int.class));
            mhJsonArrOptString = lookup.findVirtual(
                    jsonArrClass,
                    "optString",
                    MethodType.methodType(String.class, int.class, String.class));
        } catch (Throwable ignored) {
        }
    }

    private static void parseMergedLibraryJson(Object rootJson) throws Throwable {
        String defRatingStr = (String) mhJsonObjOptString.invoke(rootJson, "defaultOrganizationRating", "high");
        DEFAULT_FALLBACK_RATING = OrganizationRating.fromId(defRatingStr, OrganizationRating.HIGH);

        Object defMerchObj = mhJsonObjOptJsonObject.invoke(rootJson, "defaultMerchantPrefixes");
        if (defMerchObj != null) {
            parseArchetypePrefixMap(defMerchObj, DEFAULT_MERCHANT_PREFIXES);
        }
        Object defSmugObj = mhJsonObjOptJsonObject.invoke(rootJson, "defaultSmugglerPrefixes");
        if (defSmugObj != null) {
            parseArchetypePrefixMap(defSmugObj, DEFAULT_SMUGGLER_PREFIXES);
        }
        Object defScavObj = mhJsonObjOptJsonObject.invoke(rootJson, "defaultScavengerPrefixes");
        if (defScavObj != null) {
            parseArchetypePrefixMap(defScavObj, DEFAULT_SCAVENGER_PREFIXES);
        }

        Object factionsObj = mhJsonObjOptJsonObject.invoke(rootJson, "factions");
        if (factionsObj == null) return;
        Object namesArr = mhJsonObjNames.invoke(factionsObj);
        if (namesArr == null) return;
        int len = (int) mhJsonArrLength.invoke(namesArr);
        for (int i = 0; i < len; i++) {
            String factionKey = (String) mhJsonArrOptString.invoke(namesArr, i, "");
            if (factionKey == null || factionKey.isEmpty() || factionKey.startsWith("#")) continue;
            Object fObj = mhJsonObjOptJsonObject.invoke(factionsObj, factionKey);
            if (fObj == null) continue;

            String normKey = factionKey.trim().toLowerCase(Locale.ROOT);
            FactionAttributes attr = FACTION_REGISTRY.get(normKey);
            if (attr == null) {
                attr = createFallbackAttributes(normKey);
            }

            String catStr = (String) mhJsonObjOptString.invoke(fObj, "nomenclatureCategory", "");
            if (!catStr.isEmpty()) {
                for (NomenclatureCategory c : NomenclatureCategory.values()) {
                    if (c.name().equalsIgnoreCase(catStr.trim())) {
                        attr.nomenclatureCategory = c;
                        break;
                    }
                }
            }

            String styleStr = (String) mhJsonObjOptString.invoke(fObj, "namingStyle", "");
            if (!styleStr.isEmpty()) {
                attr.namingStyle = FactionNamingStyle.fromId(styleStr, attr.namingStyle);
            }

            String presetStr = (String) mhJsonObjOptString.invoke(fObj, "basePreset", "");
            if (!presetStr.isEmpty()) {
                PlayerNamingPreset p = PlayerNamingPreset.fromId(presetStr, attr.basePreset);
                attr.basePreset = p;
                populateDefaultPresetPrefixes(attr, p);
            }

            String numStr = (String) mhJsonObjOptString.invoke(fObj, "defaultNumbering", "");
            if (!numStr.isEmpty()) {
                attr.defaultNumbering = NumberingStyle.fromId(numStr, attr.defaultNumbering);
            }

            String bNumStr = (String) mhJsonObjOptString.invoke(fObj, "battleshipNumbering", "");
            if (!bNumStr.isEmpty()) {
                attr.battleshipNumbering = NumberingStyle.fromId(bNumStr, attr.battleshipNumbering);
            }

            attr.battleshipDotRoman = (boolean) mhJsonObjOptBoolean.invoke(fObj, "battleshipDotRoman", attr.battleshipDotRoman);
            attr.commanderPossessiveChance = (int) mhJsonObjOptInt.invoke(
                    fObj, "commanderPossessiveChance", attr.commanderPossessiveChance);

            String orgStr = (String) mhJsonObjOptString.invoke(fObj, "organizationRating", "");
            if (!orgStr.isEmpty()) {
                attr.warOrganizationRating = OrganizationRating.fromId(orgStr, attr.warOrganizationRating);
            }

            String patrolOrgStr = (String) mhJsonObjOptString.invoke(fObj, "patrolOrganizationRating", "");
            if (!patrolOrgStr.isEmpty()) {
                attr.roleOrganizationOverrides.put(
                        FleetRoleCategory.PATROL_PICKET,
                        OrganizationRating.fromId(patrolOrgStr, attr.warOrganizationRating));
            }
            String merchOrgStr = (String) mhJsonObjOptString.invoke(fObj, "merchantOrganizationRating", "");
            if (!merchOrgStr.isEmpty()) {
                attr.roleOrganizationOverrides.put(
                        FleetRoleCategory.MERCHANT_CONVOY,
                        OrganizationRating.fromId(merchOrgStr, attr.warOrganizationRating));
            }
            String smugOrgStr = (String) mhJsonObjOptString.invoke(fObj, "smugglerOrganizationRating", "");
            if (!smugOrgStr.isEmpty()) {
                attr.roleOrganizationOverrides.put(
                        FleetRoleCategory.SMUGGLER_RAIDER,
                        OrganizationRating.fromId(smugOrgStr, attr.warOrganizationRating));
            }
            String scavOrgStr = (String) mhJsonObjOptString.invoke(fObj, "scavengerOrganizationRating", "");
            if (!scavOrgStr.isEmpty()) {
                attr.roleOrganizationOverrides.put(
                        FleetRoleCategory.SCAVENGER_EXPEDITION,
                        OrganizationRating.fromId(scavOrgStr, attr.warOrganizationRating));
            }

            Object aliasesArr = mhJsonObjOptJsonArray.invoke(fObj, "aliases");
            if (aliasesArr != null) {
                parseStringList(aliasesArr, attr.aliases, false);
            }
            Object warNounsArr = mhJsonObjOptJsonArray.invoke(fObj, "warUnitNouns");
            if (warNounsArr != null) {
                parseStringList(warNounsArr, attr.warUnitNouns, true);
            }
            Object merchConvoyArr = mhJsonObjOptJsonArray.invoke(fObj, "merchantConvoyNouns");
            if (merchConvoyArr != null) {
                parseStringList(merchConvoyArr, attr.merchantConvoyNouns, true);
            }
            Object merchEscortArr = mhJsonObjOptJsonArray.invoke(fObj, "merchantEscortNouns");
            if (merchEscortArr != null) {
                parseStringList(merchEscortArr, attr.merchantEscortNouns, true);
            }
            Object smugConvoyArr = mhJsonObjOptJsonArray.invoke(fObj, "smugglerConvoyNouns");
            if (smugConvoyArr != null) {
                parseStringList(smugConvoyArr, attr.smugglerConvoyNouns, true);
            }
            Object smugEscortArr = mhJsonObjOptJsonArray.invoke(fObj, "smugglerEscortNouns");
            if (smugEscortArr != null) {
                parseStringList(smugEscortArr, attr.smugglerEscortNouns, true);
            }
            Object scavConvoyArr = mhJsonObjOptJsonArray.invoke(fObj, "scavengerConvoyNouns");
            if (scavConvoyArr != null) {
                parseStringList(scavConvoyArr, attr.scavengerConvoyNouns, true);
            }
            Object scavEscortArr = mhJsonObjOptJsonArray.invoke(fObj, "scavengerEscortNouns");
            if (scavEscortArr != null) {
                parseStringList(scavEscortArr, attr.scavengerEscortNouns, true);
            }

            Object warPrefObj = mhJsonObjOptJsonObject.invoke(fObj, "warFleetPrefixes");
            if (warPrefObj != null) {
                parseArchetypePrefixMap(warPrefObj, attr.warFleetPrefixes);
            }
            Object merchPrefObj = mhJsonObjOptJsonObject.invoke(fObj, "merchantFleetPrefixes");
            if (merchPrefObj != null) {
                parseArchetypePrefixMap(merchPrefObj, attr.merchantFleetPrefixes);
            }
            Object smugPrefObj = mhJsonObjOptJsonObject.invoke(fObj, "smugglerFleetPrefixes");
            if (smugPrefObj != null) {
                parseArchetypePrefixMap(smugPrefObj, attr.smugglerFleetPrefixes);
            }
            Object scavPrefObj = mhJsonObjOptJsonObject.invoke(fObj, "scavengerFleetPrefixes");
            if (scavPrefObj != null) {
                parseArchetypePrefixMap(scavPrefObj, attr.scavengerFleetPrefixes);
            }

            registerFaction(normKey, attr);
        }
    }

    private static void parseStringList(Object jsonArr, List<String> target, boolean clearFirst) throws Throwable {
        if (clearFirst) target.clear();
        int len = (int) mhJsonArrLength.invoke(jsonArr);
        for (int i = 0; i < len; i++) {
            String s = (String) mhJsonArrOptString.invoke(jsonArr, i, "");
            if (s != null && !s.trim().isEmpty() && !target.contains(s.trim())) {
                target.add(s.trim());
            }
        }
    }

    private static void parseArchetypePrefixMap(Object jsonObj, Map<DivisionArchetype, String> target) throws Throwable {
        Object namesArr = mhJsonObjNames.invoke(jsonObj);
        if (namesArr == null) return;
        int len = (int) mhJsonArrLength.invoke(namesArr);
        for (int i = 0; i < len; i++) {
            String key = (String) mhJsonArrOptString.invoke(namesArr, i, "");
            if (key == null || key.isEmpty() || key.startsWith("#")) continue;
            String val = (String) mhJsonObjOptString.invoke(jsonObj, key, "");
            if (val == null || val.trim().isEmpty()) continue;
            try {
                DivisionArchetype arch = DivisionArchetype.valueOf(DivisionArchetype.resolveLegacyName(key.trim().toUpperCase(Locale.ROOT)));
                target.put(arch, val.trim());
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
