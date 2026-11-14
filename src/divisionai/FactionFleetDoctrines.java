package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FactionDoctrineAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PersonImportance;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import divisionai.DivisionNomenclature.NomenclatureCategory;
import divisionai.PermanentDivisionManager.DivisionFormation;
import divisionai.PermanentDivisionManager.DivisionMissionType;
import divisionai.PermanentDivisionManager.DivisionSetpieceAttack;
import divisionai.PermanentDivisionManager.DoctrineDivisionType;
import divisionai.PermanentDivisionManager.DoctrineHullCategory;
import divisionai.PermanentDivisionManager.FleetDoctrineConfig;
import divisionai.PermanentDivisionManager.FleetWideFormation;
import divisionai.PermanentDivisionManager.LineStation;
import divisionai.PermanentDivisionManager.MultiDivisionSetpiece;
import divisionai.PermanentDivisionManager.SquadronFormation;
import divisionai.PermanentDivisionManager.VoidstrikeCoordinationMode;
import divisionai.PermanentDivisionManager.VoidstrikeTargetObservance;
import divisionai.PermanentDivisionManager.OtcAuthority;
import divisionai.PermanentDivisionManager.OtcPlay;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ascribes lore-accurate Fleet Doctrines to all vanilla Starsector factions, sorts NPC fleets
 * into divisions upon fleet initialization, maintains per-faction division unit record tables
 * (prioritizing Story/Special fleets first and reactivating destroyed divisions smaller-numbers-first),
 * keeps AI fleet divisions sticky across reinforcement/dispersal events, and executes the sector-wide
 * divisions pass.
 *
 * Player fleets are never forcibly assigned to divisions.
 */
public final class FactionFleetDoctrines {

    private static final Logger LOG = Global.getLogger(FactionFleetDoctrines.class);

    public static final String PERSISTENT_INITIAL_PASS_KEY = "divisionai_initial_divisions_pass_v1";
    public static final String PERSISTENT_FACTION_TABLES_KEY = "divisionai_faction_division_tables_v1";
    public static final String PERSISTENT_PLAYER_FACTION_DOCTRINE_KEY = "divisionai_player_faction_doctrine_v1";
    public static final String MEM_NPC_DIVISIONS_KEY = "$divisionai_npc_divisions_v1";
    public static final String MEM_NPC_SIGNATURE_KEY = "$divisionai_npc_sig_v1";
    public static final String MEM_NPC_INDEPENDENT_IDS_KEY = "$divisionai_npc_indep_ids_v1";

    private static final Map<String, FleetDoctrineConfig> DOCTRINE_CACHE = new HashMap<>();
    private static FleetDoctrineConfig inMemoryAppliedPlayerFactionDoctrine = null;

    private FactionFleetDoctrines() {
    }

    /**
     * Represents a single recorded division unit in a faction's Sector-wide Order of Battle table.
     * Destroyed/deactivated records (active == false) are reactivated smaller-numbers-first when
     * new fleets or reinforcements form divisions for that faction.
     */
    public static final class FactionDivisionRecord {
        public String recordId;
        public String factionId;
        public String bucketKey;
        public DivisionArchetype archetype;
        public int unitNumber;
        public long unitSeed;
        public String divisionName;
        public String assignedFleetId;
        public boolean heldByStoryFleet;
        public boolean active;
        public int timesActivated;
        public String flagshipMemberId;
        public final List<String> memberIds = new ArrayList<>();
    }

    /**
     * Transient campaign listener that sorts newly spawned NPC fleets into divisions upon initialization
     * and marks division records inactive when fleets despawn/are destroyed.
     */
    public static final class FleetInitializationDivisionListener extends BaseCampaignEventListener {
        public FleetInitializationDivisionListener() {
            super(false);
        }

        @Override
        public void reportFleetSpawned(CampaignFleetAPI fleet) {
            if (fleet == null || fleet.isPlayerFleet() || fleet.isStationMode()) {
                return;
            }
            try {
                registerLiveFleet(fleet);
                consolidateWholeDivisionTransfersWithNearbyFleets(fleet);
                ensureFleetDivisionsSorted(fleet);
            } catch (Throwable t) {
                LOG.warn("[DivisionAI] Failed to sort newly spawned fleet into divisions: " + fleet.getName(), t);
            }
        }

        @Override
        public void reportFleetDespawned(CampaignFleetAPI fleet, FleetDespawnReason reason, Object param) {
            if (fleet == null || fleet.isPlayerFleet()) {
                return;
            }
            try {
                deactivateFleetDivisionsOnDespawn(fleet);
            } catch (Throwable t) {
                LOG.warn("[DivisionAI] Failed to deactivate divisions for despawned fleet: " + fleet.getName(), t);
            }
        }
    }

    /**
     * Registers the transient fleet initialization listener (deduplicating prior instances) and runs
     * the one-time "divisions pass" across all already-instanced non-player fleets if needed.
     */
    public static void onGameLoad(SectorAPI sector) {
        if (sector == null) return;
        ensureSectorTablesAndIndexesLoaded(sector);
        sanitizeAllNpcFleetMemories(sector);
        if (sector.getListenerManager() != null) {
            sector.getListenerManager().removeListenerOfClass(FleetInitializationDivisionListener.class);
        }
        sector.addTransientListener(new FleetInitializationDivisionListener());
        runInitialSectorDivisionsPassIfNeeded(sector);
    }

    /**
     * Flushes dirty runtime faction division tables into plain JDK collections in SectorAPI.getPersistentData()
     * and fast-verifies that no NPC fleet MemoryAPI contains non-plain objects before Starsector writes campaign.xml.
     */
    public static void onBeforeGameSave(SectorAPI sector) {
        if (sector == null) return;
        flushDirtyFactionDivisionTablesToPersistentData(sector);
        sanitizeAllNpcFleetMemories(sector);
    }

    private static List<PermanentDivisionSpec> readNpcDivisionsFromMemory(MemoryAPI mem) {
        if (mem == null) return new ArrayList<>();
        Object raw = mem.get(MEM_NPC_DIVISIONS_KEY);
        if (!(raw instanceof List)) return new ArrayList<>();
        List<PermanentDivisionSpec> specs = PermanentDivisionManager.deserializeSpecList(raw);
        for (Object item : (List<?>) raw) {
            if (item instanceof PermanentDivisionSpec) {
                writeNpcDivisionsToMemory(mem, specs);
                break;
            }
        }
        return specs;
    }

    private static void writeNpcDivisionsToMemory(MemoryAPI mem, List<PermanentDivisionSpec> specs) {
        if (mem == null) return;
        mem.set(MEM_NPC_DIVISIONS_KEY, PermanentDivisionManager.serializeSpecList(specs));
    }

    /**
     * Fast-checks every instanced fleet's MemoryAPI without deserializing valid plain-Map entries:
     * only rewrites $divisionai_npc_divisions_v1 if a legacy non-Map item is present, and normalizes
     * $divisionai_npc_indep_ids_v1 to a plain java.util.ArrayList if needed.
     */
    public static void sanitizeAllNpcFleetMemories(SectorAPI sector) {
        if (sector == null) return;
        if (sector.getHyperspace() != null) {
            sanitizeLocationFleetMemories(sector.getHyperspace());
        }
        if (sector.getStarSystems() != null) {
            for (StarSystemAPI sys : sector.getStarSystems()) {
                if (sys != null) sanitizeLocationFleetMemories(sys);
            }
        }
    }

    private static void sanitizeLocationFleetMemories(LocationAPI loc) {
        if (loc == null || loc.getFleets() == null) return;
        for (CampaignFleetAPI fleet : loc.getFleets()) {
            if (fleet == null) continue;
            MemoryAPI mem = fleet.getMemoryWithoutUpdate();
            if (mem == null) continue;
            Object raw = mem.get(MEM_NPC_DIVISIONS_KEY);
            if (raw instanceof List) {
                List<?> list = (List<?>) raw;
                boolean needsRewrite = (raw.getClass() != ArrayList.class);
                if (!needsRewrite) {
                    for (int i = 0, n = list.size(); i < n; i++) {
                        Object item = list.get(i);
                        if (item == null || item.getClass() != HashMap.class) {
                            needsRewrite = true;
                            break;
                        }
                    }
                }
                if (needsRewrite) {
                    List<PermanentDivisionSpec> specs = PermanentDivisionManager.deserializeSpecList(raw);
                    writeNpcDivisionsToMemory(mem, specs);
                }
            }
            Object rawIndep = mem.get(MEM_NPC_INDEPENDENT_IDS_KEY);
            if (rawIndep instanceof Collection && rawIndep.getClass() != ArrayList.class) {
                ArrayList<String> cleanIndep = new ArrayList<>();
                for (Object o : (Collection<?>) rawIndep) {
                    if (o instanceof String) cleanIndep.add((String) o);
                }
                mem.set(MEM_NPC_INDEPENDENT_IDS_KEY, cleanIndep);
            }
        }
    }

    /**
     * Recursively verifies that every DivisionAI persistent key in SectorAPI.getPersistentData() and
     * every $divisionai_* key in instanced fleet MemoryAPIs uses strictly plain JDK types
     * (HashMap, ArrayList, String, Boolean, Integer, Long, Float, Double) with zero divisionai.* classes,
     * guaranteeing the mod is 100% safe to remove from a save at any time.
     *
     * @return null if 100% save-removal safe, or a diagnostic description of the first offending object/path.
     */
    public static String verifySectorSaveRemovalSafety(SectorAPI sector) {
        if (sector == null) return null;
        Map<String, Object> pdata = sector.getPersistentData();
        if (pdata != null) {
            for (Map.Entry<String, Object> e : pdata.entrySet()) {
                String k = e.getKey();
                if (k != null && k.startsWith("divisionai_")) {
                    String bad = findNonPlainSaveValue("persistentData[" + k + "]", e.getValue(), 0);
                    if (bad != null) return bad;
                }
            }
        }
        List<LocationAPI> locs = new ArrayList<>();
        if (sector.getHyperspace() != null) locs.add(sector.getHyperspace());
        if (sector.getStarSystems() != null) {
            for (StarSystemAPI sys : sector.getStarSystems()) {
                if (sys != null) locs.add(sys);
            }
        }
        String[] memKeys = new String[]{MEM_NPC_DIVISIONS_KEY, MEM_NPC_SIGNATURE_KEY, MEM_NPC_INDEPENDENT_IDS_KEY};
        for (LocationAPI loc : locs) {
            if (loc.getFleets() == null) continue;
            for (CampaignFleetAPI fleet : loc.getFleets()) {
                if (fleet == null || fleet.getMemoryWithoutUpdate() == null) continue;
                MemoryAPI mem = fleet.getMemoryWithoutUpdate();
                for (String mk : memKeys) {
                    if (mem.contains(mk)) {
                        String bad = findNonPlainSaveValue("fleet(" + fleet.getId() + ").mem[" + mk + "]", mem.get(mk), 0);
                        if (bad != null) return bad;
                    }
                }
            }
        }
        return null;
    }

    private static String findNonPlainSaveValue(String path, Object val, int depth) {
        if (val == null || depth > 16) return null;
        Class<?> cls = val.getClass();
        if (cls == String.class
                || cls == Boolean.class
                || cls == Integer.class
                || cls == Long.class
                || cls == Float.class
                || cls == Double.class) {
            return null;
        }
        if (cls == ArrayList.class) {
            List<?> list = (List<?>) val;
            for (int i = 0, n = list.size(); i < n; i++) {
                String bad = findNonPlainSaveValue(path + "[" + i + "]", list.get(i), depth + 1);
                if (bad != null) return bad;
            }
            return null;
        }
        if (cls == HashMap.class || cls == LinkedHashMap.class) {
            Map<?, ?> map = (Map<?, ?>) val;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String badK = findNonPlainSaveValue(path + ".key", e.getKey(), depth + 1);
                if (badK != null) return badK;
                String badV = findNonPlainSaveValue(path + "[" + e.getKey() + "]", e.getValue(), depth + 1);
                if (badV != null) return badV;
            }
            return null;
        }
        return path + " has non-plain class " + cls.getName();
    }

    /**
     * Clears all NPC faction division record tables and cached NPC fleet division lists, then
     * re-runs the sector-wide divisions pass (with Story/Special fleets prioritized first).
     * Never modifies the player's fleet.
     *
     * @return number of non-player fleets reinitialized across the sector.
     */
    public static int reinitializeSectorWideDivisionLists(SectorAPI sector) {
        if (sector == null) return 0;
        Map<String, Object> pdata = sector.getPersistentData();
        pdata.remove(PERSISTENT_INITIAL_PASS_KEY);
        pdata.remove(PERSISTENT_FACTION_TABLES_KEY);
        clearAllRuntimeTablesAndIndexes();
        ensureSectorTablesAndIndexesLoaded(sector);

        List<CampaignFleetAPI> allNpcFleets = collectAllInstancedNpcFleets(sector);
        for (CampaignFleetAPI fleet : allNpcFleets) {
            MemoryAPI mem = fleet.getMemoryWithoutUpdate();
            if (mem != null) {
                mem.unset(MEM_NPC_DIVISIONS_KEY);
                mem.unset(MEM_NPC_SIGNATURE_KEY);
                mem.unset(MEM_NPC_INDEPENDENT_IDS_KEY);
            }
        }

        return executeSectorWideDivisionsPass(sector);
    }

    /**
     * Checks whether a campaign fleet belongs strictly to the player's personal faction ("player")
     * — such as patrols, defense armadas, and trade fleets spawned from player-owned colonies —
     * and NOT the player's own flagship fleet, nor any fleet spawned by a preset faction's market
     * over which a commissioned player merely holds a governorship/mandate.
     */
    public static boolean isPlayerPersonalFactionFleet(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.isPlayerFleet() || fleet.isStationMode()) {
            return false;
        }
        if (fleet.getFaction() == null || !Factions.PLAYER.equals(fleet.getFaction().getId())) {
            return false;
        }
        try {
            MarketAPI sourceMarket = Misc.getSourceMarket(fleet);
            if (sourceMarket != null
                    && sourceMarket.getFactionId() != null
                    && !Factions.PLAYER.equals(sourceMarket.getFactionId())) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        MemoryAPI mem = fleet.getMemoryWithoutUpdate();
        if (mem != null) {
            Object rawSrc = mem.get(MemFlags.MEMORY_KEY_SOURCE_MARKET);
            if (rawSrc instanceof MarketAPI) {
                MarketAPI mkt = (MarketAPI) rawSrc;
                if (mkt.getFactionId() != null && !Factions.PLAYER.equals(mkt.getFactionId())) {
                    return false;
                }
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static FleetDoctrineConfig getAppliedPlayerFactionDoctrine() {
        SectorAPI sector = Global.getSector();
        if (sector != null && sector.getPersistentData() != null) {
            Object raw = sector.getPersistentData().get(PERSISTENT_PLAYER_FACTION_DOCTRINE_KEY);
            if (raw instanceof Map) {
                return PermanentDivisionManager.deserializeDoctrineConfig((Map<String, Object>) raw);
            }
        }
        return inMemoryAppliedPlayerFactionDoctrine;
    }


    /**
     * Copies the player's current Fleet Doctrine to the player's personal faction ("player")
     * and reinitializes division assignments and division names across the player's personal
     * faction division record table and all instanced personal faction fleets.
     * Never affects commissioned preset faction worlds or fleets.
     */
    public static int applyPlayerDoctrineToPersonalFactionFleets(SectorAPI sector) {
        FleetDoctrineConfig snapshot = PermanentDivisionManager.getFleetDoctrine().copy();
        inMemoryAppliedPlayerFactionDoctrine = snapshot;
        synchronized (DOCTRINE_CACHE) {
            DOCTRINE_CACHE.put(Factions.PLAYER, snapshot);
        }
        if (sector != null && sector.getPersistentData() != null) {
            sector.getPersistentData().put(
                    PERSISTENT_PLAYER_FACTION_DOCTRINE_KEY,
                    PermanentDivisionManager.serializeDoctrineConfig(snapshot));
        }

        clearFactionDivisionTable(Factions.PLAYER);

        if (sector == null) {
            return 0;
        }

        List<CampaignFleetAPI> personalFleets = new ArrayList<>();
        for (CampaignFleetAPI fleet : collectAllInstancedNpcFleets(sector)) {
            if (isPlayerPersonalFactionFleet(fleet)) {
                personalFleets.add(fleet);
                MemoryAPI mem = fleet.getMemoryWithoutUpdate();
                if (mem != null) {
                    mem.unset(MEM_NPC_DIVISIONS_KEY);
                    mem.unset(MEM_NPC_SIGNATURE_KEY);
                }
            }
        }

        sortFleetsByDescendingPriority(personalFleets);

        int reinitCount = 0;
        for (CampaignFleetAPI fleet : personalFleets) {
            List<PermanentDivisionSpec> divs = ensureFleetDivisionsSorted(fleet);
            if (!divs.isEmpty()) {
                reinitCount++;
            }
        }
        saveFactionDivisionTablesToPersistentData();
        LOG.info("[DivisionAI] Applied player Fleet Doctrine to personal faction ('player') and reinitialized "
                + reinitCount + " personal faction fleet(s).");
        return reinitCount;
    }

    /**
     * Applies the player's current Fleet Nomenclature (preset, numbering style, and custom prefixes)
     * to the player's personal faction ("player") and reinitializes division names across the player's
     * personal faction division record table and all instanced personal faction fleets.
     * Never affects commissioned preset faction worlds or fleets.
     */
    @SuppressWarnings("unchecked")
    public static int applyPlayerNomenclatureToPersonalFactionFleets(SectorAPI sector) {
        DivisionNomenclature.applyPlayerNamingConfigToPersonalFaction();
        NomenclatureCategory nomCat = DivisionNomenclature.getPlayerNomenclatureCategory();

        List<FactionDivisionRecord> playerFactionTable = getOrCreateFactionDivisionTable(Factions.PLAYER);
        Map<String, FactionDivisionRecord> recordById = new HashMap<>();
        for (FactionDivisionRecord rec : playerFactionTable) {
            if (rec == null) continue;
            rec.divisionName = DivisionNomenclature.formatFactionDivisionName(
                    Factions.PLAYER,
                    nomCat,
                    rec.archetype,
                    rec.unitNumber,
                    null,
                    rec.unitSeed);
            if (rec.recordId != null) {
                recordById.put(rec.recordId, rec);
            }
        }

        if (sector == null) {
            return 0;
        }

        int updatedFleets = 0;
        Map<String, Integer> fallbackSeqByBucket = new LinkedHashMap<>();
        for (CampaignFleetAPI fleet : collectAllInstancedNpcFleets(sector)) {
            if (!isPlayerPersonalFactionFleet(fleet)) continue;
            MemoryAPI mem = fleet.getMemoryWithoutUpdate();
            List<PermanentDivisionSpec> specs = readNpcDivisionsFromMemory(mem);
            if (!specs.isEmpty()) {
                for (PermanentDivisionSpec spec : specs) {
                    if (spec == null) continue;
                    FactionDivisionRecord rec = recordById.get(spec.id);
                    if (rec != null && rec.divisionName != null) {
                        spec.name = rec.divisionName;
                    } else {
                        String bucket = DivisionNomenclature.resolvePrefixBucketKey(spec.archetype);
                        int seq = (fallbackSeqByBucket.containsKey(bucket) ? fallbackSeqByBucket.get(bucket) : 0) + 1;
                        fallbackSeqByBucket.put(bucket, seq);
                        spec.name = DivisionNomenclature.formatFactionDivisionName(
                                Factions.PLAYER,
                                nomCat,
                                spec.archetype,
                                seq,
                                null,
                                0L);
                    }
                }
                writeNpcDivisionsToMemory(mem, specs);
                updatedFleets++;
            } else {
                List<PermanentDivisionSpec> divs = ensureFleetDivisionsSorted(fleet);
                if (!divs.isEmpty()) {
                    updatedFleets++;
                }
            }
        }
        saveFactionDivisionTablesToPersistentData();
        LOG.info("[DivisionAI] Applied player Fleet Nomenclature to personal faction ('player') and updated division names on "
                + updatedFleets + " personal faction fleet(s).");
        return updatedFleets;
    }

    /**
     * Runs the one-time "divisions pass" through all already-instanced NPC fleets in hyperspace
     * and all star systems, assigning their ships to divisions based on their faction's Fleet Doctrine
     * (Story/Special fleets prioritized first). Never modifies the player's fleet.
     */
    public static void runInitialSectorDivisionsPassIfNeeded(SectorAPI sector) {
        if (sector == null) return;
        Map<String, Object> pdata = sector.getPersistentData();
        if (Boolean.TRUE.equals(pdata.get(PERSISTENT_INITIAL_PASS_KEY))) {
            return;
        }
        executeSectorWideDivisionsPass(sector);
    }

    private static final class ScoredFleetEntry implements Comparable<ScoredFleetEntry> {
        final CampaignFleetAPI fleet;
        final float score;

        ScoredFleetEntry(CampaignFleetAPI fleet, float score) {
            this.fleet = fleet;
            this.score = score;
        }

        @Override
        public int compareTo(ScoredFleetEntry other) {
            return Float.compare(other.score, this.score);
        }
    }

    private static void sortFleetsByDescendingPriority(List<CampaignFleetAPI> fleets) {
        if (fleets == null || fleets.size() <= 1) return;
        int n = fleets.size();
        List<ScoredFleetEntry> scored = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            CampaignFleetAPI f = fleets.get(i);
            scored.add(new ScoredFleetEntry(f, computeFleetPriorityScore(f)));
        }
        Collections.sort(scored);
        for (int i = 0; i < n; i++) {
            fleets.set(i, scored.get(i).fleet);
        }
    }

    private static int executeSectorWideDivisionsPass(SectorAPI sector) {
        if (sector == null) return 0;
        long t0 = System.nanoTime();
        ensureSectorTablesAndIndexesLoaded(sector);
        Map<String, Object> pdata = sector.getPersistentData();

        List<CampaignFleetAPI> allNpcFleets = collectAllInstancedNpcFleets(sector);
        // Sort fleets so Story Fleets / Special Fleets / Major Named Armadas get first priority
        // for permanent divisions (#1, #2, etc.) in their respective faction!
        // Precompute priority once per fleet in O(F) rather than inside Comparator.compare!
        sortFleetsByDescendingPriority(allNpcFleets);

        int fleetsProcessed = 0;
        int divisionsCreated = 0;
        int shipsAssigned = 0;

        for (CampaignFleetAPI fleet : allNpcFleets) {
            List<PermanentDivisionSpec> divs = ensureFleetDivisionsSorted(fleet);
            if (!divs.isEmpty()) {
                fleetsProcessed++;
                divisionsCreated += divs.size();
                for (PermanentDivisionSpec d : divs) {
                    shipsAssigned += d.memberIds.size();
                }
            }
        }

        flushDirtyFactionDivisionTablesToPersistentData(sector);
        pdata.put(PERSISTENT_INITIAL_PASS_KEY, Boolean.TRUE);
        double elapsedMs = (System.nanoTime() - t0) / 1_000_000.0;
        LOG.info(String.format(
                Locale.ROOT,
                "[DivisionAI] Completed sector-wide campaign divisions pass in %.1f ms: sorted %d/%d instanced NPC fleets into %d divisions (%d ships).",
                elapsedMs,
                fleetsProcessed,
                allNpcFleets.size(),
                divisionsCreated,
                shipsAssigned));
        return fleetsProcessed;
    }

    private static List<CampaignFleetAPI> collectAllInstancedNpcFleets(SectorAPI sector) {
        List<CampaignFleetAPI> out = new ArrayList<>();
        if (sector == null) return out;
        if (sector.getHyperspace() != null && sector.getHyperspace().getFleets() != null) {
            for (CampaignFleetAPI fleet : sector.getHyperspace().getFleets()) {
                if (fleet != null && !fleet.isPlayerFleet() && !fleet.isStationMode()) {
                    out.add(fleet);
                }
            }
        }
        if (sector.getStarSystems() != null) {
            for (StarSystemAPI sys : sector.getStarSystems()) {
                if (sys == null || sys.getFleets() == null) continue;
                for (CampaignFleetAPI fleet : sys.getFleets()) {
                    if (fleet != null && !fleet.isPlayerFleet() && !fleet.isStationMode()) {
                        out.add(fleet);
                    }
                }
            }
        }
        return out;
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        if (s == null || prefix == null) return false;
        int pLen = prefix.length();
        return s.length() >= pLen && s.regionMatches(true, 0, prefix, 0, pLen);
    }

    private static boolean containsIgnoreCase(String s, String needle) {
        if (s == null || needle == null) return false;
        int nLen = needle.length();
        if (nLen == 0) return true;
        int max = s.length() - nLen;
        if (max < 0) return false;
        char firstLower = Character.toLowerCase(needle.charAt(0));
        char firstUpper = Character.toUpperCase(needle.charAt(0));
        for (int i = 0; i <= max; i++) {
            char c = s.charAt(i);
            if (c != firstLower && c != firstUpper) continue;
            if (s.regionMatches(true, i, needle, 0, nLen)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines whether an NPC fleet is a Story Fleet or Special Fleet that should receive
     * first priority for prestigious low-numbered divisions (e.g., Battle Div. I) in its faction.
     * Zero-allocation fast path: avoids String.toLowerCase() heap allocations across thousands of fleets.
     */
    public static boolean isStoryOrSpecialFleet(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.isPlayerFleet()) return false;
        MemoryAPI mem = fleet.getMemoryWithoutUpdate();
        if (mem != null) {
            if (mem.getBoolean(MemFlags.ENTITY_MISSION_IMPORTANT)
                    || mem.getBoolean("$storyFleet")
                    || mem.getBoolean("$specialFleet")
                    || mem.getBoolean("$uniqueFleet")
                    || mem.getBoolean("$bossFleet")) {
                return true;
            }
        }
        PersonAPI cmdr = fleet.getCommander();
        if (cmdr != null && !cmdr.isDefault()) {
            PersonImportance imp = cmdr.getImportance();
            if (imp == PersonImportance.VERY_HIGH || imp == PersonImportance.HIGH) {
                return true;
            }
            if (cmdr.getStats() != null && cmdr.getStats().getLevel() >= 7 && fleet.getFleetPoints() >= 150) {
                return true;
            }
        }
        if (fleet.getFleetPoints() >= 160) {
            String fName = fleet.getName();
            if (fName != null && !fName.isEmpty()
                    && (containsIgnoreCase(fName, "armada")
                    || containsIgnoreCase(fName, "grand")
                    || containsIgnoreCase(fName, "first fleet")
                    || containsIgnoreCase(fName, "invasion")
                    || containsIgnoreCase(fName, "task force"))) {
                return true;
            }
        }
        if (mem != null) {
            Collection<String> keys = mem.getKeys();
            if (keys != null) {
                for (String k : keys) {
                    if (k == null || k.length() < 4) continue;
                    if (startsWithIgnoreCase(k, "$divisionai_") || startsWithIgnoreCase(k, "$cfai_")) continue;
                    if (startsWithIgnoreCase(k, "$ga_")
                            || startsWithIgnoreCase(k, "$pk_")
                            || startsWithIgnoreCase(k, "$ziggurat")
                            || containsIgnoreCase(k, "grandarmada")
                            || containsIgnoreCase(k, "armada_leader")
                            || containsIgnoreCase(k, "story")
                            || containsIgnoreCase(k, "crisis")
                            || containsIgnoreCase(k, "inspection_fleet")
                            || containsIgnoreCase(k, "blockade_boss")
                            || containsIgnoreCase(k, "sentinel_fleet")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Computes a priority score for sorting NPC fleets during division allocation so Story/Special
     * fleets and major capital-led armadas get #1 divisions first.
     */
    public static float computeFleetPriorityScore(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.isPlayerFleet()) return -1f;
        float score = fleet.getFleetPoints();
        if (isStoryOrSpecialFleet(fleet)) {
            score += 100000f;
        }
        PersonAPI cmdr = fleet.getCommander();
        if (cmdr != null && !cmdr.isDefault()) {
            int lvl = cmdr.getStats() != null ? cmdr.getStats().getLevel() : 1;
            score += 500f + lvl * 200f;
            PersonImportance imp = cmdr.getImportance();
            if (imp == PersonImportance.VERY_HIGH) score += 10000f;
            else if (imp == PersonImportance.HIGH) score += 5000f;
            else if (imp == PersonImportance.MEDIUM) score += 1500f;
        }
        if (fleet.getFleetData() != null) {
            for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
                if (m != null && m.getHullSpec() != null && m.getHullSpec().getHullSize() == HullSize.CAPITAL_SHIP) {
                    score += 350f;
                }
            }
        }
        return score;
    }

    /**
     * Returns the FleetDoctrineConfig for a campaign fleet (player's configured FleetDoctrineConfig
     * for the player fleet, or the faction's FleetDoctrineConfig for NPC fleets).
     */
    public static FleetDoctrineConfig getDoctrineForFleet(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.isPlayerFleet()) {
            return PermanentDivisionManager.getFleetDoctrine();
        }
        NomenclatureCategory nomCat = DivisionNomenclature.resolveCategoryForCampaignFleet(fleet);
        if (nomCat == NomenclatureCategory.ABYSSAL_ERROR) {
            return getDoctrineForFactionId("dweller", fleet.getFaction());
        }
        if (nomCat == NomenclatureCategory.THREAT_SWARM) {
            return getDoctrineForFactionId("threat", fleet.getFaction());
        }
        if (nomCat == NomenclatureCategory.POST_AI_WAR) {
            String fId = fleet.getFaction() != null ? fleet.getFaction().getId() : "remnant";
            if (!"derelict".equalsIgnoreCase(fId)) {
                fId = "remnant";
            }
            return getDoctrineForFactionId(fId, fleet.getFaction());
        }
        String factionId = fleet.getFaction() != null ? fleet.getFaction().getId() : "independent";
        return getDoctrineForFactionId(factionId, fleet.getFaction());
    }

    /**
     * Returns the lore-accurate FleetDoctrineConfig ascribed to a faction ID.
     */
    public static FleetDoctrineConfig getDoctrineForFactionId(String factionId, FactionAPI optionalFaction) {
        String key = (factionId != null && !factionId.trim().isEmpty())
                ? factionId.trim().toLowerCase(Locale.ROOT)
                : "independent";
        if ("player".equals(key)) {
            FleetDoctrineConfig applied = getAppliedPlayerFactionDoctrine();
            if (applied != null) {
                return applied;
            }
        }
        synchronized (DOCTRINE_CACHE) {
            ensureJsonDoctrinesLoaded();
            FleetDoctrineConfig cached = DOCTRINE_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            FleetDoctrineConfig built = buildFactionDoctrine(key, optionalFaction);
            DOCTRINE_CACHE.put(key, built);
            return built;
        }
    }

    public static final String FACTION_DOCTRINES_JSON_PATH = "data/config/division_faction_doctrines.json";
    private static boolean jsonDoctrinesLoaded = false;

    private static synchronized void ensureJsonDoctrinesLoaded() {
        if (jsonDoctrinesLoaded) return;
        jsonDoctrinesLoaded = true;
        try {
            Object rootJson = JsonReflectBridge.loadMergedModJson(FACTION_DOCTRINES_JSON_PATH, FactionLibrary.MOD_ID);
            if (rootJson == null) return;
            Object factionsJson = JsonReflectBridge.optJSONObject(rootJson, "factions");
            if (factionsJson == null) factionsJson = rootJson;
            for (String key : JsonReflectBridge.keys(factionsJson)) {
                if (key == null || key.startsWith("_")) continue;
                Object entryJson = JsonReflectBridge.optJSONObject(factionsJson, key);
                if (entryJson == null) continue;
                FleetDoctrineConfig cfg = parseDoctrineFromJson(entryJson);
                String normKey = key.trim().toLowerCase(Locale.ROOT);
                DOCTRINE_CACHE.put(normKey, cfg);
                for (String alias : JsonReflectBridge.optStringList(entryJson, "aliases")) {
                    String normAlias = alias.trim().toLowerCase(Locale.ROOT);
                    if (!normAlias.isEmpty()) {
                        DOCTRINE_CACHE.put(normAlias, cfg.copy());
                    }
                }
            }
        } catch (Throwable t) {
            LOG.warn("[DivisionAI] Failed to load faction doctrines from " + FACTION_DOCTRINES_JSON_PATH, t);
        }
    }

    private static FleetDoctrineConfig parseDoctrineFromJson(Object entryJson) throws Throwable {
        FleetDoctrineConfig cfg = new FleetDoctrineConfig();
        String authStr = JsonReflectBridge.optString(entryJson, "otcAuthority", null);
        if (authStr != null && !authStr.isEmpty()) {
            cfg.setOtcAuthority(OtcAuthority.fromId(authStr, cfg.getOtcAuthority()));
        }
        int opDur = JsonReflectBridge.optInt(entryJson, "openingDurationSeconds", -1);
        if (opDur > 0) {
            cfg.setOpeningDurationSeconds(opDur);
        }
        String fwStr = JsonReflectBridge.optString(entryJson, "fleetWideFormation", null);
        if (fwStr != null && !fwStr.isEmpty()) {
            cfg.setFleetWideFormation(FleetWideFormation.fromId(fwStr, cfg.getFleetWideFormation()));
        }
        String mdsStr = JsonReflectBridge.optString(entryJson, "multiDivisionSetpiece", null);
        if (mdsStr != null && !mdsStr.isEmpty()) {
            cfg.setMultiDivisionSetpiece(MultiDivisionSetpiece.fromId(mdsStr, cfg.getMultiDivisionSetpiece()));
        }

        Object playsJson = JsonReflectBridge.optJSONObject(entryJson, "plays");
        if (playsJson != null) {
            for (OtcPlay play : OtcPlay.values()) {
                if (JsonReflectBridge.has(playsJson, play.name())) {
                    cfg.setPlayEnabled(play, JsonReflectBridge.optBoolean(playsJson, play.name(), cfg.isPlayEnabled(play)));
                }
            }
        }

        Object hullMaxJson = JsonReflectBridge.optJSONObject(entryJson, "hullMax");
        if (hullMaxJson != null) {
            for (DoctrineHullCategory hc : DoctrineHullCategory.values()) {
                if (JsonReflectBridge.has(hullMaxJson, hc.name())) {
                    cfg.setMax(hc, JsonReflectBridge.optInt(hullMaxJson, hc.name(), cfg.getMax(hc)));
                }
            }
        }

        Object divTypesJson = JsonReflectBridge.optJSONObject(entryJson, "divisionTypes");
        if (divTypesJson != null) {
            for (DoctrineDivisionType dt : DoctrineDivisionType.values()) {
                Object dtJson = JsonReflectBridge.optJSONObject(divTypesJson, dt.name());
                if (dtJson == null) continue;
                if (JsonReflectBridge.has(dtJson, "stationSpacing")) {
                    cfg.setStationSpacing(dt, JsonReflectBridge.optInt(dtJson, "stationSpacing", cfg.getStationSpacing(dt)));
                }
                if (JsonReflectBridge.has(dtJson, "divisionInterval")) {
                    cfg.setDivisionInterval(dt, JsonReflectBridge.optInt(dtJson, "divisionInterval", cfg.getDivisionInterval(dt)));
                }
                if (JsonReflectBridge.has(dtJson, "standoffRange")) {
                    cfg.setStandoffRange(dt, JsonReflectBridge.optInt(dtJson, "standoffRange", cfg.getStandoffRange(dt)));
                }
                if (JsonReflectBridge.has(dtJson, "stationKeepingMult")) {
                    cfg.setStationKeepingMult(dt, JsonReflectBridge.optFloat(dtJson, "stationKeepingMult", cfg.getStationKeepingMult(dt)));
                }
                String opMis = JsonReflectBridge.optString(dtJson, "openingMission", null);
                if (opMis != null && !opMis.isEmpty()) {
                    cfg.setOpeningMission(dt, DivisionMissionType.fromId(opMis, cfg.getOpeningMission(dt)));
                }
                String mnMis = JsonReflectBridge.optString(dtJson, "mainMission", null);
                if (mnMis != null && !mnMis.isEmpty()) {
                    cfg.setMainMission(dt, DivisionMissionType.fromId(mnMis, cfg.getMainMission(dt)));
                }
                String ls = JsonReflectBridge.optString(dtJson, "lineStation", null);
                if (ls != null && !ls.isEmpty()) {
                    cfg.setLineStation(dt, LineStation.fromId(ls, cfg.getLineStation(dt)));
                }
                String fm = JsonReflectBridge.optString(dtJson, "formation", null);
                if (fm != null && !fm.isEmpty()) {
                    cfg.setFormation(dt, DivisionFormation.fromId(fm, cfg.getFormation(dt)));
                }
                String spAtk = JsonReflectBridge.optString(dtJson, "setpieceAttack", null);
                if (spAtk != null && !spAtk.isEmpty()) {
                    cfg.setSetpieceAttack(dt, DivisionSetpieceAttack.fromId(spAtk, cfg.getSetpieceAttack(dt)));
                }
                String skCoord = JsonReflectBridge.optString(dtJson, "shipSkillCoordination", null);
                if (skCoord != null) {
                    cfg.setShipSkillCoordination(dt, PermanentDivisionManager.ShipSkillCoordinationMode.fromId(
                            skCoord, cfg.getShipSkillCoordination(dt)));
                }
                String ramMode = JsonReflectBridge.optString(dtJson, "rammingMode", null);
                if (ramMode != null) {
                    cfg.setRammingMode(dt, PermanentDivisionManager.DivisionRammingMode.fromId(
                            ramMode, cfg.getRammingMode(dt)));
                }
                String ramScope = JsonReflectBridge.optString(dtJson, "rammingTargetScope", null);
                if (ramScope != null) {
                    cfg.setRammingTargetScope(dt, PermanentDivisionManager.DivisionRammingTargetScope.fromId(
                            ramScope, cfg.getRammingTargetScope(dt)));
                }
                if (JsonReflectBridge.has(dtJson, "maxRammingSlots")) {
                    cfg.setMaxRammingSlots(dt, JsonReflectBridge.optInt(
                            dtJson, "maxRammingSlots", cfg.getMaxRammingSlots(dt)));
                }
                String vsBias = JsonReflectBridge.optString(dtJson, "voidstrikeTargetingBias", null);
                if (vsBias != null && !vsBias.isEmpty()) {
                    cfg.setVoidstrikeTargetingBias(dt, PermanentDivisionManager.VoidstrikeTargetingBias.fromId(
                            vsBias, cfg.getVoidstrikeTargetingBias(dt)));
                }
                if (JsonReflectBridge.has(dtJson, "voidstrikeFocusCarriers")) {
                    cfg.setVoidstrikeFocusCarriers(dt, JsonReflectBridge.optBoolean(
                            dtJson, "voidstrikeFocusCarriers", cfg.isVoidstrikeFocusCarriers(dt)));
                }
                String vsObs = JsonReflectBridge.optString(dtJson, "voidstrikeObservance", null);
                if (vsObs != null && !vsObs.isEmpty()) {
                    cfg.setVoidstrikeObservance(dt, VoidstrikeTargetObservance.fromId(vsObs, cfg.getVoidstrikeObservance(dt)));
                }
                String vsCoord = JsonReflectBridge.optString(dtJson, "voidstrikeCoordination", null);
                if (vsCoord != null && !vsCoord.isEmpty()) {
                    cfg.setVoidstrikeCoordination(dt, VoidstrikeCoordinationMode.fromId(vsCoord, cfg.getVoidstrikeCoordination(dt)));
                }
                String vsGeom = JsonReflectBridge.optString(dtJson, "voidstrikeAttackGeometry", null);
                if (vsGeom != null && !vsGeom.isEmpty()) {
                    cfg.setVoidstrikeAttackGeometry(dt, PermanentDivisionManager.VoidstrikeAttackGeometry.fromId(
                            vsGeom, cfg.getVoidstrikeAttackGeometry(dt)));
                }
                String vsSplit = JsonReflectBridge.optString(dtJson, "voidstrikeSplitMode", null);
                if (vsSplit != null && !vsSplit.isEmpty()) {
                    cfg.setVoidstrikeSplitMode(dt, PermanentDivisionManager.VoidstrikeSplitMode.fromId(
                            vsSplit, cfg.getVoidstrikeSplitMode(dt)));
                }
                String vsGun = JsonReflectBridge.optString(dtJson, "voidstrikeGunneryMode", null);
                if (vsGun != null && !vsGun.isEmpty()) {
                    cfg.setVoidstrikeGunneryMode(dt, PermanentDivisionManager.VoidstrikeGunneryMode.fromId(
                            vsGun, cfg.getVoidstrikeGunneryMode(dt)));
                }
                String vsAoa = JsonReflectBridge.optString(dtJson, "voidstrikeAoaCorrection", null);
                if (vsAoa != null && !vsAoa.isEmpty()) {
                    cfg.setVoidstrikeAoaCorrection(dt, PermanentDivisionManager.VoidstrikeAoaCorrection.fromId(
                            vsAoa, cfg.getVoidstrikeAoaCorrection(dt)));
                }
                String ftrFm = JsonReflectBridge.optString(dtJson, "fighterFormation", null);
                if (ftrFm != null && !ftrFm.isEmpty()) {
                    cfg.setFighterFormation(dt, SquadronFormation.fromId(ftrFm, cfg.getFighterFormation(dt)));
                }
                String ftrVsFm = JsonReflectBridge.optString(dtJson, "fighterVoidstrikeFormation", null);
                if (ftrVsFm != null && !ftrVsFm.isEmpty()) {
                    cfg.setFighterVoidstrikeFormation(dt, SquadronFormation.fromId(ftrVsFm, cfg.getFighterVoidstrikeFormation(dt)));
                }
                String bmbFm = JsonReflectBridge.optString(dtJson, "bomberFormation", null);
                if (bmbFm != null && !bmbFm.isEmpty()) {
                    cfg.setBomberFormation(dt, SquadronFormation.fromId(bmbFm, cfg.getBomberFormation(dt)));
                }
                String bmbVsFm = JsonReflectBridge.optString(dtJson, "bomberVoidstrikeFormation", null);
                if (bmbVsFm != null && !bmbVsFm.isEmpty()) {
                    cfg.setBomberVoidstrikeFormation(dt, SquadronFormation.fromId(bmbVsFm, cfg.getBomberVoidstrikeFormation(dt)));
                }
                if (JsonReflectBridge.has(dtJson, "voidstrikeSquadronWidth")) {
                    cfg.setVoidstrikeSquadronWidth(dt, JsonReflectBridge.optInt(dtJson, "voidstrikeSquadronWidth", cfg.getVoidstrikeSquadronWidth(dt)));
                }
                if (JsonReflectBridge.has(dtJson, "voidstrikeSquadronDepth")) {
                    cfg.setVoidstrikeSquadronDepth(dt, JsonReflectBridge.optInt(dtJson, "voidstrikeSquadronDepth", cfg.getVoidstrikeSquadronDepth(dt)));
                }
            }
        }
        return cfg;
    }

    private static FleetDoctrineConfig buildFactionDoctrine(String id, FactionAPI optionalFaction) {
        FleetDoctrineConfig cfg = new FleetDoctrineConfig();
        // Dynamic fallback from FactionDoctrineAPI if present for unknown modded factions
        if (optionalFaction != null && optionalFaction.getDoctrine() != null) {
            FactionDoctrineAPI fd = optionalFaction.getDoctrine();
            int aggr = fd.getAggression();
            if (fd.isStrictComposition()) {
                cfg.setOtcAuthority(OtcAuthority.POSITIVE_CONTROL);
            } else if (aggr >= 5) {
                cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_NEGATION);
            } else {
                cfg.setOtcAuthority(OtcAuthority.COMMAND_BY_DIRECTION);
            }
            if (aggr >= 4) {
                cfg.setOpeningDurationSeconds(35);
                cfg.setOpeningMission(DoctrineDivisionType.BATTLESHIP, DivisionMissionType.CLOSE_ACTION);
                cfg.setMainMission(DoctrineDivisionType.BATTLESHIP, DivisionMissionType.CLOSE_ACTION);
                cfg.setOpeningMission(DoctrineDivisionType.CRUISER, DivisionMissionType.CLOSE_ACTION);
                cfg.setMainMission(DoctrineDivisionType.CRUISER, DivisionMissionType.CLOSE_ACTION);
            }
            // Dynamic carrier doctrine synthesis for unknown modded factions
            int cvWeight = fd.getCarriers();
            int phaseWeight = fd.getPhaseShips();
            int shipQuality = fd.getShipQuality();
            int shipSize = fd.getShipSize();
            boolean disorderlyRaider = !fd.isStrictComposition() && shipQuality <= 2 && aggr >= 4;
            if (disorderlyRaider) {
                for (DoctrineDivisionType cdt : new DoctrineDivisionType[]{
                        DoctrineDivisionType.FLEET_CARRIER,
                        DoctrineDivisionType.LIGHT_CARRIER,
                        DoctrineDivisionType.ESCORT_CARRIER,
                        DoctrineDivisionType.CARRIER_GROUP}) {
                    cfg.setVoidstrikeTargetingBias(cdt, PermanentDivisionManager.VoidstrikeTargetingBias.FREE_SELECTION);
                    cfg.setVoidstrikeFocusCarriers(cdt, false);
                    cfg.setVoidstrikeObservance(cdt, VoidstrikeTargetObservance.FREE);
                    cfg.setVoidstrikeCoordination(cdt, VoidstrikeCoordinationMode.UNCOORDINATED);
                    cfg.setVoidstrikeAttackGeometry(cdt, PermanentDivisionManager.VoidstrikeAttackGeometry.DIRECT_ASSAULT);
                    cfg.setVoidstrikeSplitMode(cdt, PermanentDivisionManager.VoidstrikeSplitMode.SPLIT_TARGETS);
                }
            } else {
                PermanentDivisionManager.VoidstrikeAttackGeometry dynGeom = (phaseWeight >= 2 || shipQuality >= 4)
                        ? PermanentDivisionManager.VoidstrikeAttackGeometry.PINCER_ENVELOPMENT
                        : ((cvWeight >= 3)
                        ? PermanentDivisionManager.VoidstrikeAttackGeometry.ANVIL_CROSSFIRE
                        : PermanentDivisionManager.VoidstrikeAttackGeometry.DIRECT_ASSAULT);
                PermanentDivisionManager.VoidstrikeSplitMode dynSplit = (dynGeom == PermanentDivisionManager.VoidstrikeAttackGeometry.DIRECT_ASSAULT)
                        ? PermanentDivisionManager.VoidstrikeSplitMode.UNIFIED
                        : PermanentDivisionManager.VoidstrikeSplitMode.SPLIT_AXES;
                boolean hunterCv = (cvWeight >= 3 || shipQuality >= 4);
                cfg.setVoidstrikeTargetingBias(DoctrineDivisionType.FLEET_CARRIER,
                        shipSize >= 4
                                ? PermanentDivisionManager.VoidstrikeTargetingBias.CAPITALS_FIRST
                                : PermanentDivisionManager.VoidstrikeTargetingBias.CRUISERS_AND_CAPITALS);
                cfg.setVoidstrikeFocusCarriers(DoctrineDivisionType.FLEET_CARRIER, shipQuality >= 5);
                cfg.setVoidstrikeAttackGeometry(DoctrineDivisionType.FLEET_CARRIER, dynGeom);
                cfg.setVoidstrikeSplitMode(DoctrineDivisionType.FLEET_CARRIER, dynSplit);

                cfg.setVoidstrikeTargetingBias(DoctrineDivisionType.LIGHT_CARRIER,
                        PermanentDivisionManager.VoidstrikeTargetingBias.CRUISERS_AND_CAPITALS);
                cfg.setVoidstrikeFocusCarriers(DoctrineDivisionType.LIGHT_CARRIER, hunterCv);
                cfg.setVoidstrikeAttackGeometry(DoctrineDivisionType.LIGHT_CARRIER, dynGeom);
                cfg.setVoidstrikeSplitMode(DoctrineDivisionType.LIGHT_CARRIER, dynSplit);

                cfg.setVoidstrikeTargetingBias(DoctrineDivisionType.CARRIER_GROUP,
                        PermanentDivisionManager.VoidstrikeTargetingBias.CRUISERS_AND_CAPITALS);
                cfg.setVoidstrikeFocusCarriers(DoctrineDivisionType.CARRIER_GROUP, hunterCv);
                cfg.setVoidstrikeAttackGeometry(DoctrineDivisionType.CARRIER_GROUP, dynGeom);
                cfg.setVoidstrikeSplitMode(DoctrineDivisionType.CARRIER_GROUP, dynSplit);
            }
        }
        return cfg;
    }

    /**
     * Ensures that a campaign fleet's divisions are sorted and up to date:
     *  - For the player fleet: never forcibly assigns ships; returns PermanentDivisionManager.getNonEmptyPermanentDivisions().
     *  - For NPC fleets: sorts eligible ships into divisions based on the faction's Fleet Doctrine
     *    and stores the sorted PermanentDivisionSpec list in the fleet's MemoryAPI.
     */
    @SuppressWarnings("unchecked")
    public static List<PermanentDivisionSpec> ensureFleetDivisionsSorted(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.getFleetData() == null) {
            return new ArrayList<>();
        }
        if (fleet.isPlayerFleet()) {
            return PermanentDivisionManager.getNonEmptyPermanentDivisions();
        }
        if (fleet.isStationMode()) {
            return new ArrayList<>();
        }
        registerLiveFleet(fleet);

        List<FleetMemberAPI> eligible = new ArrayList<>();
        StringBuilder sigBuilder = new StringBuilder();
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            if (PermanentDivisionManager.isEligibleFleetMember(m)) {
                eligible.add(m);
                sigBuilder.append(m.getId()).append('|');
            }
        }
        if (eligible.isEmpty()) {
            return new ArrayList<>();
        }

        String currentSig = sigBuilder.toString();
        MemoryAPI mem = fleet.getMemoryWithoutUpdate();
        List<PermanentDivisionSpec> existingDivs = readNpcDivisionsFromMemory(mem);
        if (fleet.getBattle() != null && (!existingDivs.isEmpty() || (mem != null && mem.contains(MEM_NPC_SIGNATURE_KEY)))) {
            return existingDivs;
        }
        if (mem != null) {
            Object cachedSig = mem.get(MEM_NPC_SIGNATURE_KEY);
            if (cachedSig != null && currentSig.equals(cachedSig)) {
                return existingDivs;
            }
        }

        FleetDoctrineConfig doctrine = getDoctrineForFleet(fleet);
        NomenclatureCategory nomCat = DivisionNomenclature.resolveCategoryForCampaignFleet(fleet);
        String factionId = fleet.getFaction() != null ? fleet.getFaction().getId() : "independent";

        List<PermanentDivisionSpec> sorted;
        Set<String> knownIndependentIds = new HashSet<>();
        if (mem != null) {
            Object rawIndep = mem.get(MEM_NPC_INDEPENDENT_IDS_KEY);
            if (rawIndep instanceof Collection) {
                for (Object o : (Collection<?>) rawIndep) {
                    if (o instanceof String) knownIndependentIds.add((String) o);
                }
            }
        }

        if (!existingDivs.isEmpty()) {
            // Sticky AI fleet divisions: preserve existing divisions across casualties, recombine depleted
            // remnants after attrition, keep known independent captains independent, and apply the fleet's
            // Organization Rating quota to genuinely new reinforcement ships.
            sorted = updateStickyNpcFleetDivisions(
                    fleet,
                    existingDivs,
                    eligible,
                    knownIndependentIds,
                    doctrine,
                    factionId,
                    nomCat);
        } else {
            // Initial fleet organization: select the organized subset per Organization Rating; record the rest as independent
            List<FleetMemberAPI> organizedSubset = FactionLibrary.selectOrganizedFleetMembers(fleet, eligible, doctrine);
            Set<String> orgIds = new HashSet<>();
            for (FleetMemberAPI m : organizedSubset) {
                if (m != null && m.getId() != null) orgIds.add(m.getId());
            }
            knownIndependentIds.clear();
            for (FleetMemberAPI m : eligible) {
                if (m != null && m.getId() != null && !orgIds.contains(m.getId())) {
                    knownIndependentIds.add(m.getId());
                }
            }
            sorted = sortMembersByDoctrine(fleet, organizedSubset, doctrine, factionId, nomCat);
        }

        if (mem != null) {
            mem.set(MEM_NPC_SIGNATURE_KEY, currentSig);
            writeNpcDivisionsToMemory(mem, sorted);
            mem.set(MEM_NPC_INDEPENDENT_IDS_KEY, new ArrayList<>(knownIndependentIds));
        }
        return sorted;
    }

    /**
     * When a newly spawned/split AI fleet appears near a parent fleet of the same faction that just
     * transferred part of its divisions into this fleet, swaps same-hull-size ships between the two
     * fleets so whole divisions are transferred intact whenever possible.
     */
    public static void consolidateWholeDivisionTransfersWithNearbyFleets(CampaignFleetAPI childFleet) {
        if (childFleet == null || childFleet.isPlayerFleet() || childFleet.isStationMode()
                || childFleet.getBattle() != null || childFleet.getFleetData() == null) {
            return;
        }
        LocationAPI loc = childFleet.getContainingLocation();
        if (loc == null || loc.getFleets() == null) return;
        String factionId = childFleet.getFaction() != null ? childFleet.getFaction().getId() : null;
        if (factionId == null) return;

        List<FleetMemberAPI> childMembers = childFleet.getFleetData().getMembersListCopy();
        if (childMembers == null || childMembers.isEmpty()) return;

        // O(ships) fast path: if our runtime division index is populated and zero hulls in childFleet belong
        // to any existing NPC division record, this is a freshly generated fleet rather than a split/transfer!
        if (!RECORD_BY_ID.isEmpty()) {
            boolean anyKnownMember = false;
            for (int i = 0, n = childMembers.size(); i < n; i++) {
                FleetMemberAPI m = childMembers.get(i);
                if (m != null && m.getId() != null && RECORD_BY_MEMBER_ID.containsKey(m.getId())) {
                    anyKnownMember = true;
                    break;
                }
            }
            if (!anyKnownMember) {
                return;
            }
        }

        Vector2f childLoc = childFleet.getLocation();
        Map<String, FleetMemberAPI> childById = null;
        boolean checkedChildStory = false;

        for (CampaignFleetAPI other : loc.getFleets()) {
            if (other == null || other == childFleet || other.isPlayerFleet() || other.isStationMode()) continue;
            if (other.getBattle() != null) continue;
            FactionAPI otherFaction = other.getFaction();
            if (otherFaction == null || !factionId.equals(otherFaction.getId())) continue;
            if (other.getFleetData() == null) continue;
            if (childLoc != null && other.getLocation() != null) {
                float dx = childLoc.x - other.getLocation().x;
                float dy = childLoc.y - other.getLocation().y;
                if (dx * dx + dy * dy > 950f * 950f) continue;
            }
            MemoryAPI otherMem = other.getMemoryWithoutUpdate();
            if (otherMem == null || !otherMem.contains(MEM_NPC_DIVISIONS_KEY)) continue;
            if (Misc.getDistance(childFleet, other) > 600f) continue;
            if (isStoryOrSpecialFleet(other)) continue;

            if (!checkedChildStory) {
                checkedChildStory = true;
                if (isStoryOrSpecialFleet(childFleet)) {
                    return;
                }
                childById = new HashMap<>();
                for (FleetMemberAPI m : childFleet.getFleetData().getMembersListCopy()) {
                    if (m != null && m.getId() != null) {
                        childById.put(m.getId(), m);
                    }
                }
                if (childById.isEmpty()) return;
            }

            List<PermanentDivisionSpec> parentDivs = readNpcDivisionsFromMemory(otherMem);
            if (parentDivs.isEmpty()) continue;

            Map<String, FleetMemberAPI> parentById = new HashMap<>();
            for (FleetMemberAPI m : other.getFleetData().getMembersListCopy()) {
                if (m != null && m.getId() != null) {
                    parentById.put(m.getId(), m);
                }
            }

            // Find if any parent divisions were split across parentFleet and childFleet
            List<PermanentDivisionSpec> majorityInChild = new ArrayList<>();
            List<PermanentDivisionSpec> minorityInChild = new ArrayList<>();
            for (PermanentDivisionSpec d : parentDivs) {
                if (d == null || d.memberIds.isEmpty()) continue;
                int inChild = 0;
                int inParent = 0;
                for (String mId : d.memberIds) {
                    if (childById.containsKey(mId)) inChild++;
                    else if (parentById.containsKey(mId)) inParent++;
                }
                if (inChild > 0 && inParent > 0) {
                    if (inChild >= inParent) {
                        majorityInChild.add(d);
                    } else {
                        minorityInChild.add(d);
                    }
                }
            }

            if (majorityInChild.isEmpty() || minorityInChild.isEmpty()) continue;

            // Swap compatible-hull-size ships between majorityInChild (in parent) and minorityInChild (in child)
            // so whole divisions stay together!
            for (PermanentDivisionSpec majDiv : majorityInChild) {
                for (String mId : new ArrayList<>(majDiv.memberIds)) {
                    FleetMemberAPI stayInParent = parentById.get(mId);
                    if (stayInParent == null || stayInParent.isFlagship()) continue;
                    if (stayInParent.getCaptain() != null && !stayInParent.getCaptain().isDefault()) continue;
                    HullSize targetSize = stayInParent.getHullSpec() != null
                            ? stayInParent.getHullSpec().getHullSize()
                            : HullSize.FRIGATE;

                    FleetMemberAPI swapFromChild = null;
                    for (PermanentDivisionSpec minDiv : minorityInChild) {
                        for (String cId : minDiv.memberIds) {
                            FleetMemberAPI cand = childById.get(cId);
                            if (cand != null && !cand.isFlagship()
                                    && (cand.getCaptain() == null || cand.getCaptain().isDefault())
                                    && cand.getHullSpec() != null
                                    && cand.getHullSpec().getHullSize() == targetSize) {
                                swapFromChild = cand;
                                break;
                            }
                        }
                        if (swapFromChild != null) break;
                    }

                    if (swapFromChild != null) {
                        other.getFleetData().removeFleetMember(stayInParent);
                        childFleet.getFleetData().removeFleetMember(swapFromChild);
                        childFleet.getFleetData().addFleetMember(stayInParent);
                        other.getFleetData().addFleetMember(swapFromChild);

                        parentById.remove(stayInParent.getId());
                        childById.remove(swapFromChild.getId());
                        childById.put(stayInParent.getId(), stayInParent);
                        parentById.put(swapFromChild.getId(), swapFromChild);
                    }
                }
            }
        }
    }

    private static void recalculateNpcDivisionStandoffFast(
            PermanentDivisionSpec spec,
            Map<String, FleetMemberAPI> eligibleById) {
        if (spec == null || eligibleById == null || spec.memberIds.isEmpty()) return;
        List<FleetMemberAPI> resolved = new ArrayList<>(spec.memberIds.size());
        for (int i = 0, n = spec.memberIds.size(); i < n; i++) {
            FleetMemberAPI m = eligibleById.get(spec.memberIds.get(i));
            if (m != null) resolved.add(m);
        }
        PermanentDivisionManager.recalculateDivisionStandoffRangeFromMembers(spec, resolved);
    }

    private static List<PermanentDivisionSpec> updateStickyNpcFleetDivisions(
            CampaignFleetAPI fleet,
            List<PermanentDivisionSpec> existingDivs,
            List<FleetMemberAPI> eligible,
            Set<String> knownIndependentIds,
            FleetDoctrineConfig doctrine,
            String factionId,
            NomenclatureCategory nomCat) {

        Map<String, FleetMemberAPI> eligibleById = new LinkedHashMap<>();
        for (FleetMemberAPI m : eligible) {
            if (m != null && m.getId() != null) {
                eligibleById.put(m.getId(), m);
            }
        }

        // Ensure runtime tables & O(1) indexes are initialized for this sector
        getOrCreateFactionDivisionTable(factionId);
        boolean fleetIsStory = isStoryOrSpecialFleet(fleet);
        String fleetId = fleet != null ? fleet.getId() : null;

        List<PermanentDivisionSpec> retained = new ArrayList<>();
        Set<String> assignedMemberIds = new HashSet<>();

        // 1. Keep existing divisions in this fleet sticky
        for (PermanentDivisionSpec spec : existingDivs) {
            if (spec == null) continue;
            for (int i = spec.memberIds.size() - 1; i >= 0; i--) {
                String mId = spec.memberIds.get(i);
                if (!eligibleById.containsKey(mId) || assignedMemberIds.contains(mId)) {
                    spec.memberIds.remove(i);
                } else {
                    assignedMemberIds.add(mId);
                }
            }

            if (spec.memberIds.isEmpty()) {
                FactionDivisionRecord rec = RECORD_BY_ID.get(spec.id);
                if (rec != null && (fleetId == null || fleetId.equals(rec.assignedFleetId))) {
                    indexDeactivateRecord(rec);
                }
                continue;
            }

            refreshNpcDivisionFlagship(spec, eligibleById);
            // Auto-migrate existing campaign NPC missile divisions if their ships are actually gun hulls (e.g. Onslaughts)
            if (spec.archetype != null && spec.archetype.isMissileArchetype()) {
                FleetMemberAPI flagM = eligibleById.get(spec.flagshipMemberId);
                if (flagM != null) {
                    DivisionArchetype recalcArch = classifyNpcFleetMemberArchetype(flagM, factionId, nomCat, doctrine);
                    if (recalcArch != null && !recalcArch.isMissileArchetype()) {
                        spec.archetype = recalcArch;
                        FactionDivisionRecord oldRec = RECORD_BY_ID.get(spec.id);
                        int unitNum = (oldRec != null && oldRec.unitNumber > 0) ? oldRec.unitNumber : 1;
                        PersonAPI flagCmdr = flagM.getCaptain();
                        if ((flagCmdr == null || flagCmdr.isDefault()) && fleet != null && flagM.isFlagship()) {
                            flagCmdr = fleet.getCommander();
                        }
                        long uSeed = (oldRec != null && oldRec.unitSeed != 0L) ? oldRec.unitSeed : unitNum * 2654435761L;
                        spec.name = DivisionNomenclature.formatFactionDivisionName(
                                factionId, nomCat, recalcArch, unitNum, flagCmdr, uSeed,
                                FactionLibrary.resolveFleetRoleCategory(fleet));
                        if (oldRec != null) {
                            oldRec.archetype = recalcArch;
                            oldRec.divisionName = spec.name;
                            indexRebucketRecord(oldRec, DivisionNomenclature.resolvePrefixBucketKey(recalcArch));
                        }
                    }
                }
            }
            recalculateNpcDivisionStandoffFast(spec, eligibleById);
            retained.add(spec);

            FactionDivisionRecord rec = RECORD_BY_ID.get(spec.id);
            if (rec != null) {
                indexActivateRecord(rec, fleetId, spec.memberIds, spec.flagshipMemberId, fleetIsStory);
            }
        }

        // 2. Check if any newly arrived ships in this fleet belong to a division transferred from another fleet
        Map<String, List<FleetMemberAPI>> transferredByRecordId = new LinkedHashMap<>();
        for (FleetMemberAPI m : eligible) {
            if (assignedMemberIds.contains(m.getId())) continue;
            FactionDivisionRecord prevRec = RECORD_BY_MEMBER_ID.get(m.getId());
            if (prevRec != null && prevRec.recordId != null && !prevRec.recordId.isEmpty()) {
                List<FleetMemberAPI> bucket = transferredByRecordId.get(prevRec.recordId);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    transferredByRecordId.put(prevRec.recordId, bucket);
                }
                bucket.add(m);
            }
        }

        for (Map.Entry<String, List<FleetMemberAPI>> entry : transferredByRecordId.entrySet()) {
            FactionDivisionRecord rec = RECORD_BY_ID.get(entry.getKey());
            List<FleetMemberAPI> transferredMembers = entry.getValue();
            if (rec == null || transferredMembers.isEmpty()) continue;

            // Adopt the transferred division intact if it isn't already in retained
            PermanentDivisionSpec targetSpec = null;
            for (PermanentDivisionSpec s : retained) {
                if (rec.recordId.equals(s.id)) {
                    targetSpec = s;
                    break;
                }
            }
            if (targetSpec == null) {
                targetSpec = new PermanentDivisionSpec(rec.recordId, rec.divisionName, rec.archetype);
                retained.add(targetSpec);
            }
            for (FleetMemberAPI tm : transferredMembers) {
                if (!targetSpec.memberIds.contains(tm.getId())) {
                    targetSpec.memberIds.add(tm.getId());
                    assignedMemberIds.add(tm.getId());
                }
            }
            refreshNpcDivisionFlagship(targetSpec, eligibleById);
            recalculateNpcDivisionStandoffFast(targetSpec, eligibleById);
            indexActivateRecord(rec, fleetId, targetSpec.memberIds, targetSpec.flagshipMemberId, fleetIsStory);
        }

        // Prune destroyed ships from knownIndependentIds
        if (knownIndependentIds != null) {
            for (Iterator<String> it = knownIndependentIds.iterator(); it.hasNext(); ) {
                String id = it.next();
                if (!eligibleById.containsKey(id) || assignedMemberIds.contains(id)) {
                    it.remove();
                }
            }
        }

        // 3. Any genuinely new reinforcement ships (neither already in a division nor previously marked independent):
        // apply the fleet's Organization Rating so only that fraction of new arrivals join divisions while the
        // rest become independent ships!
        List<FleetMemberAPI> genuinelyNewArrivals = new ArrayList<>();
        for (FleetMemberAPI m : eligible) {
            if (assignedMemberIds.contains(m.getId())) continue;
            if (knownIndependentIds != null && knownIndependentIds.contains(m.getId())) continue;
            genuinelyNewArrivals.add(m);
        }

        List<FleetMemberAPI> remainingNew = new ArrayList<>();
        if (!genuinelyNewArrivals.isEmpty()) {
            FactionLibrary.OrganizationRating rating = FactionLibrary.resolveEffectiveOrganizationRating(fleet);
            int maxTotalOrganized = Math.round(eligible.size() * rating.fraction);
            int allowedFromNew = Math.max(0, Math.min(
                    genuinelyNewArrivals.size(),
                    Math.max(Math.round(genuinelyNewArrivals.size() * rating.fraction), maxTotalOrganized - assignedMemberIds.size())));
            if (rating == FactionLibrary.OrganizationRating.PERFECT) {
                allowedFromNew = genuinelyNewArrivals.size();
            } else if (rating == FactionLibrary.OrganizationRating.NONE) {
                allowedFromNew = 0;
            }
            for (int idx = 0; idx < genuinelyNewArrivals.size(); idx++) {
                FleetMemberAPI m = genuinelyNewArrivals.get(idx);
                if (idx < allowedFromNew) {
                    remainingNew.add(m);
                } else if (knownIndependentIds != null && m.getId() != null) {
                    knownIndependentIds.add(m.getId());
                }
            }
        }

        if (!remainingNew.isEmpty()) {
            for (int i = remainingNew.size() - 1; i >= 0; i--) {
                FleetMemberAPI m = remainingNew.get(i);
                DivisionArchetype arch = classifyNpcFleetMemberArchetype(m, factionId, nomCat, doctrine);
                int maxCap = getMaxForArchetype(doctrine, arch);
                PermanentDivisionSpec bestUnderStrength = null;
                for (PermanentDivisionSpec s : retained) {
                    if (s.archetype == arch && s.memberIds.size() < maxCap) {
                        bestUnderStrength = s;
                        break;
                    }
                }
                if (bestUnderStrength != null) {
                    bestUnderStrength.memberIds.add(m.getId());
                    assignedMemberIds.add(m.getId());
                    remainingNew.remove(i);
                    refreshNpcDivisionFlagship(bestUnderStrength, eligibleById);
                    recalculateNpcDivisionStandoffFast(bestUnderStrength, eligibleById);
                    FactionDivisionRecord rec = RECORD_BY_ID.get(bestUnderStrength.id);
                    if (rec != null) {
                        indexActivateRecord(rec, fleetId, bestUnderStrength.memberIds, bestUnderStrength.flagshipMemberId, fleetIsStory);
                    }
                }
            }

            if (!remainingNew.isEmpty()) {
                List<PermanentDivisionSpec> newlyFormed = sortMembersByDoctrine(
                        fleet,
                        remainingNew,
                        doctrine,
                        factionId,
                        nomCat);
                retained.addAll(newlyFormed);
            }
        }

        // 4. Post-Attrition Division Recombination:
        //  (a) Same-archetype / same-hull-family consolidation: if two divisions of the same archetype (or compatible
        //      hull class) have been depleted by attrition so that their combined ships fit within maxCap, the senior
        //      (lower-numbered / earlier) division absorbs the junior division's survivors and deactivates the junior record.
        for (int i = 0; i < retained.size(); i++) {
            PermanentDivisionSpec senior = retained.get(i);
            if (senior == null || senior.memberIds.isEmpty()) continue;
            int maxCap = getMaxForArchetype(doctrine, senior.archetype);
            for (int j = retained.size() - 1; j > i; j--) {
                PermanentDivisionSpec junior = retained.get(j);
                if (junior == null || junior.memberIds.isEmpty()) continue;
                boolean sameOrCompatible = (senior.archetype == junior.archetype)
                        || (senior.archetype != null && junior.archetype != null
                        && senior.archetype.isCarrierArchetype() && junior.archetype.isCarrierArchetype()
                        && senior.memberIds.size() + junior.memberIds.size() <= 3)
                        || ((senior.memberIds.size() == 1 || junior.memberIds.size() == 1)
                        && areArchetypesSameHullWeight(senior.archetype, junior.archetype));
                if (sameOrCompatible && (senior.memberIds.size() + junior.memberIds.size()) <= maxCap) {
                    for (String mId : junior.memberIds) {
                        if (!senior.memberIds.contains(mId)) {
                            senior.memberIds.add(mId);
                        }
                    }
                    FactionDivisionRecord jRec = RECORD_BY_ID.get(junior.id);
                    if (jRec != null) {
                        indexDeactivateRecord(jRec);
                    }
                    retained.remove(j);
                    refreshNpcDivisionFlagship(senior, eligibleById);
                    recalculateNpcDivisionStandoffFast(senior, eligibleById);
                    FactionDivisionRecord sRec = RECORD_BY_ID.get(senior.id);
                    if (sRec != null) {
                        indexActivateRecord(sRec, fleetId, senior.memberIds, senior.flagshipMemberId, fleetIsStory);
                    }
                }
            }
        }

        //  (b) Cross-class 1-ship remnant recombination: if attrition leaves >= 2 lone 1-ship remnant divisions across
        //      different hull classes, recombine them into the senior survivor's division (or a composite Force / Carrier Group)
        //      so organized survivors never sit in awkward 1-ship "divisions". If only a single lone 1-ship remnant exists
        //      and the fleet has < 100% Organization Rating, dissolve that lone 1-ship division into independent ships.
        List<PermanentDivisionSpec> singletons = new ArrayList<>();
        for (PermanentDivisionSpec s : retained) {
            if (s != null && s.memberIds.size() == 1) {
                singletons.add(s);
            }
        }
        if (singletons.size() >= 2) {
            PermanentDivisionSpec anchor = singletons.get(0);
            int maxMerge = Math.max(3, getMaxForArchetype(doctrine, anchor.archetype));
            for (int k = 1; k < singletons.size(); k++) {
                PermanentDivisionSpec rem = singletons.get(k);
                if (anchor.memberIds.size() + rem.memberIds.size() > maxMerge) break;
                for (String mId : rem.memberIds) {
                    if (!anchor.memberIds.contains(mId)) {
                        anchor.memberIds.add(mId);
                    }
                }
                FactionDivisionRecord rRec = RECORD_BY_ID.get(rem.id);
                if (rRec != null) {
                    indexDeactivateRecord(rRec);
                }
                retained.remove(rem);
            }
            refreshNpcDivisionFlagship(anchor, eligibleById);
            recalculateNpcDivisionStandoffFast(anchor, eligibleById);
            FactionDivisionRecord aRec = RECORD_BY_ID.get(anchor.id);
            if (aRec != null) {
                indexActivateRecord(aRec, fleetId, anchor.memberIds, anchor.flagshipMemberId, fleetIsStory);
            }
        } else if (singletons.size() == 1) {
            PermanentDivisionSpec lone = singletons.get(0);
            // Try merging the single lone survivor into any existing under-strength combat division in the fleet
            PermanentDivisionSpec host = null;
            for (PermanentDivisionSpec s : retained) {
                if (s == null || s == lone) continue;
                int cap = Math.max(3, getMaxForArchetype(doctrine, s.archetype));
                if (s.memberIds.size() < cap) {
                    host = s;
                    break;
                }
            }
            if (host != null) {
                for (String mId : lone.memberIds) {
                    if (!host.memberIds.contains(mId)) host.memberIds.add(mId);
                }
                FactionDivisionRecord lRec = RECORD_BY_ID.get(lone.id);
                if (lRec != null) {
                    indexDeactivateRecord(lRec);
                }
                retained.remove(lone);
                refreshNpcDivisionFlagship(host, eligibleById);
                recalculateNpcDivisionStandoffFast(host, eligibleById);
                FactionDivisionRecord hRec = RECORD_BY_ID.get(host.id);
                if (hRec != null) {
                    indexActivateRecord(hRec, fleetId, host.memberIds, host.flagshipMemberId, fleetIsStory);
                }
            }
        }

        factionTablesDirty = true;
        return retained;
    }

    private static boolean areArchetypesSameHullWeight(DivisionArchetype a, DivisionArchetype b) {
        if (a == null || b == null) return false;
        if (a == b) return true;
        boolean aCap = a == DivisionArchetype.BATTLESHIP_DIVISION || a == DivisionArchetype.MISSILE_BATTLESHIP_DIVISION;
        boolean bCap = b == DivisionArchetype.BATTLESHIP_DIVISION || b == DivisionArchetype.MISSILE_BATTLESHIP_DIVISION;
        if (aCap && bCap) return true;
        boolean aCru = a == DivisionArchetype.CRUISER_DIVISION
                || a == DivisionArchetype.MISSILE_CRUISER_DIVISION
                || a == DivisionArchetype.STRIKE_CRUISER_DIVISION;
        boolean bCru = b == DivisionArchetype.CRUISER_DIVISION
                || b == DivisionArchetype.MISSILE_CRUISER_DIVISION
                || b == DivisionArchetype.STRIKE_CRUISER_DIVISION;
        if (aCru && bCru) return true;
        boolean aDes = a == DivisionArchetype.DESTROYER_DIVISION || a == DivisionArchetype.MISSILE_DESTROYER_DIVISION;
        boolean bDes = b == DivisionArchetype.DESTROYER_DIVISION || b == DivisionArchetype.MISSILE_DESTROYER_DIVISION;
        if (aDes && bDes) return true;
        boolean aFri = a == DivisionArchetype.FRIGATE_DIVISION || a == DivisionArchetype.MISSILE_FRIGATE_DIVISION;
        boolean bFri = b == DivisionArchetype.FRIGATE_DIVISION || b == DivisionArchetype.MISSILE_FRIGATE_DIVISION;
        return aFri && bFri;
    }

    private static int getMaxForArchetype(FleetDoctrineConfig doctrine, DivisionArchetype arch) {
        if (doctrine == null || arch == null) return 4;
        switch (arch) {
            case BATTLESHIP_DIVISION:
            case MISSILE_BATTLESHIP_DIVISION:
            case FLEET_CARRIER_DIVISION:
                return Math.max(1, doctrine.getMax(DoctrineHullCategory.CAPITAL));
            case LIGHT_CARRIER_DIVISION:
                return Math.min(3, Math.max(1, doctrine.getMax(DoctrineHullCategory.CRUISER)));
            case ESCORT_CARRIER_DIVISION:
                return Math.min(4, Math.max(1, doctrine.getMax(DoctrineHullCategory.DESTROYER)));
            case CARRIER_GROUP:
                return 3;
            case CRUISER_DIVISION:
            case MISSILE_CRUISER_DIVISION:
            case STRIKE_CRUISER_DIVISION:
                return Math.max(1, doctrine.getMax(DoctrineHullCategory.CRUISER));
            case DESTROYER_DIVISION:
            case MISSILE_DESTROYER_DIVISION:
                return Math.max(1, doctrine.getMax(DoctrineHullCategory.DESTROYER));
            case PHASE_DIVISION:
                return Math.min(4, Math.max(1, doctrine.getMax(DoctrineHullCategory.FRIGATE)));
            case SERVICE_GROUP:
                return Math.max(4, doctrine.getMax(DoctrineHullCategory.FREIGHTER));
            case FORCE_DIVISION:
                return Math.max(1, doctrine.getMax(DoctrineHullCategory.CRUISER));
            case FRIGATE_DIVISION:
            case MISSILE_FRIGATE_DIVISION:
            default:
                return Math.max(1, doctrine.getMax(DoctrineHullCategory.FRIGATE));
        }
    }

    private static void refreshNpcDivisionFlagship(
            PermanentDivisionSpec spec,
            Map<String, FleetMemberAPI> eligibleById) {
        if (spec == null || spec.memberIds.isEmpty()) return;
        boolean preferCarrier = spec.archetype != null && spec.archetype.isCarrierArchetype();
        FleetMemberAPI best = null;
        float bestScore = -1f;
        for (String mId : spec.memberIds) {
            FleetMemberAPI m = eligibleById.get(mId);
            if (m == null) continue;
            float score = PermanentDivisionManager.computeFlagshipCandidateScore(m, preferCarrier);
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        spec.flagshipMemberId = (best != null) ? best.getId() : spec.memberIds.get(0);
    }

    private static List<PermanentDivisionSpec> sortMembersByDoctrine(
            CampaignFleetAPI fleet,
            List<FleetMemberAPI> eligible,
            FleetDoctrineConfig doctrine,
            String factionId,
            NomenclatureCategory nomCat) {

        List<FleetMemberAPI> sortedEligible = new ArrayList<>(eligible);
        Collections.sort(sortedEligible, new Comparator<FleetMemberAPI>() {
            @Override
            public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                return Float.compare(b.getDeploymentPointsCost(), a.getDeploymentPointsCost());
            }
        });

        Map<DivisionArchetype, List<FleetMemberAPI>> buckets = PermanentDivisionManager.newArchetypeBuckets();
        for (FleetMemberAPI m : sortedEligible) {
            DivisionArchetype arch = classifyNpcFleetMemberArchetype(m, factionId, nomCat, doctrine);
            if (arch == null) arch = DivisionArchetype.FORCE_DIVISION;
            if (arch == DivisionArchetype.CARRIER_GROUP) {
                arch = DivisionArchetype.ESCORT_CARRIER_DIVISION;
            }
            buckets.get(arch).add(m);
        }

        List<FleetMemberAPI> fleetCarriers = buckets.get(DivisionArchetype.FLEET_CARRIER_DIVISION);
        List<FleetMemberAPI> lightCarriers = buckets.get(DivisionArchetype.LIGHT_CARRIER_DIVISION);
        List<FleetMemberAPI> escortCarriers = buckets.get(DivisionArchetype.ESCORT_CARRIER_DIVISION);
        List<FleetMemberAPI> capitals = buckets.get(DivisionArchetype.BATTLESHIP_DIVISION);
        List<FleetMemberAPI> missileCapitals = buckets.get(DivisionArchetype.MISSILE_BATTLESHIP_DIVISION);
        List<FleetMemberAPI> strikeCruisers = buckets.get(DivisionArchetype.STRIKE_CRUISER_DIVISION);
        List<FleetMemberAPI> missileCruisers = buckets.get(DivisionArchetype.MISSILE_CRUISER_DIVISION);
        List<FleetMemberAPI> lineCruisers = buckets.get(DivisionArchetype.CRUISER_DIVISION);
        List<FleetMemberAPI> missileDestroyers = buckets.get(DivisionArchetype.MISSILE_DESTROYER_DIVISION);
        List<FleetMemberAPI> destroyers = buckets.get(DivisionArchetype.DESTROYER_DIVISION);
        List<FleetMemberAPI> missileFrigates = buckets.get(DivisionArchetype.MISSILE_FRIGATE_DIVISION);
        List<FleetMemberAPI> frigates = buckets.get(DivisionArchetype.FRIGATE_DIVISION);
        List<FleetMemberAPI> phaseShips = buckets.get(DivisionArchetype.PHASE_DIVISION);
        List<FleetMemberAPI> auxiliaries = buckets.get(DivisionArchetype.SERVICE_GROUP);
        List<FleetMemberAPI> forceShips = buckets.get(DivisionArchetype.FORCE_DIVISION);

        int capMax = Math.max(1, doctrine.getMax(DoctrineHullCategory.CAPITAL));
        int cruMax = Math.max(1, doctrine.getMax(DoctrineHullCategory.CRUISER));
        int desMax = Math.max(1, doctrine.getMax(DoctrineHullCategory.DESTROYER));
        int friMax = Math.max(1, doctrine.getMax(DoctrineHullCategory.FRIGATE));
        int auxMax = Math.max(4, doctrine.getMax(DoctrineHullCategory.FREIGHTER));

        List<PermanentDivisionSpec> specs = new ArrayList<>();
        Map<DivisionArchetype, Integer> seqByArch = new LinkedHashMap<>();

        // 1. Battle & Missile Battleship Divisions
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.MISSILE_BATTLESHIP_DIVISION, missileCapitals, capMax, factionId, nomCat);
        if (!capitals.isEmpty()) {
            if (capitals.size() == 1 && lineCruisers.size() == 1 && strikeCruisers.isEmpty() && missileCruisers.isEmpty()) {
                capitals.add(lineCruisers.remove(0));
            }
            chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.BATTLESHIP_DIVISION, capitals, capMax, factionId, nomCat);
        }

        // 2. Carrier Divisions (merge lone light/escort carriers into a Carrier Group if small)
        if (fleetCarriers.size() + lightCarriers.size() + escortCarriers.size() > 0) {
            if (fleetCarriers.size() + lightCarriers.size() + escortCarriers.size() <= 3
                    && ((fleetCarriers.isEmpty() ? 0 : 1) + (lightCarriers.isEmpty() ? 0 : 1) + (escortCarriers.isEmpty() ? 0 : 1)) >= 2) {
                List<FleetMemberAPI> combinedCarriers = new ArrayList<>();
                combinedCarriers.addAll(fleetCarriers);
                combinedCarriers.addAll(lightCarriers);
                combinedCarriers.addAll(escortCarriers);
                chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.CARRIER_GROUP, combinedCarriers, 3, factionId, nomCat);
            } else {
                chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.FLEET_CARRIER_DIVISION, fleetCarriers, capMax, factionId, nomCat);
                chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.LIGHT_CARRIER_DIVISION, lightCarriers, Math.min(3, cruMax), factionId, nomCat);
                chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.ESCORT_CARRIER_DIVISION, escortCarriers, Math.min(4, desMax), factionId, nomCat);
            }
        }

        // 3. Cruiser Divisions
        if (strikeCruisers.size() == 1 && !lineCruisers.isEmpty()) {
            lineCruisers.add(strikeCruisers.remove(0));
        }
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.CRUISER_DIVISION, lineCruisers, cruMax, factionId, nomCat);
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.MISSILE_CRUISER_DIVISION, missileCruisers, cruMax, factionId, nomCat);
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.STRIKE_CRUISER_DIVISION, strikeCruisers, cruMax, factionId, nomCat);

        // 4. Destroyer Divisions
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.MISSILE_DESTROYER_DIVISION, missileDestroyers, desMax, factionId, nomCat);
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.DESTROYER_DIVISION, destroyers, desMax, factionId, nomCat);

        // 5. Frigate & Phase Divisions
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.MISSILE_FRIGATE_DIVISION, missileFrigates, friMax, factionId, nomCat);
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.FRIGATE_DIVISION, frigates, friMax, factionId, nomCat);
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.PHASE_DIVISION, phaseShips, Math.min(4, friMax), factionId, nomCat);

        // 6. Auxiliary / Service Groups & Generic Force Divisions
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.SERVICE_GROUP, auxiliaries, auxMax, factionId, nomCat);
        chunkIntoNpcDivisions(specs, seqByArch, fleet, DivisionArchetype.FORCE_DIVISION, forceShips, cruMax, factionId, nomCat);

        factionTablesDirty = true;
        return specs;
    }

    private static DivisionArchetype classifyNpcFleetMemberArchetype(
            FleetMemberAPI m,
            String factionId,
            NomenclatureCategory nomCat,
            FleetDoctrineConfig doctrine) {
        if (m == null) return DivisionArchetype.FORCE_DIVISION;
        if (nomCat == NomenclatureCategory.THREAT_SWARM && m.getHullSpec() != null) {
            String hullId = m.getHullSpec().getHullId();
            if ("fabricator_unit".equalsIgnoreCase(hullId)) {
                return DivisionArchetype.SERVICE_GROUP;
            }
            if ("hive_unit".equalsIgnoreCase(hullId)) {
                return DivisionArchetype.LIGHT_CARRIER_DIVISION;
            }
            if ("standoff_unit".equalsIgnoreCase(hullId)) {
                return DivisionArchetype.BATTLESHIP_DIVISION;
            }
            if ("overseer_unit".equalsIgnoreCase(hullId)) {
                return DivisionArchetype.CRUISER_DIVISION;
            }
            if ("assault_unit".equalsIgnoreCase(hullId)) {
                return DivisionArchetype.DESTROYER_DIVISION;
            }
            if ("skirmish_unit".equalsIgnoreCase(hullId) || "attack_swarm".equalsIgnoreCase(hullId)) {
                return DivisionArchetype.FRIGATE_DIVISION;
            }
        }
        DivisionArchetype classified = PermanentDivisionManager.classifyFleetMemberArchetype(m, doctrine);
        return classified != null ? classified : DivisionArchetype.FORCE_DIVISION;
    }

    private static void chunkIntoNpcDivisions(
            List<PermanentDivisionSpec> out,
            Map<DivisionArchetype, Integer> seqByArch,
            CampaignFleetAPI fleet,
            DivisionArchetype archetype,
            List<FleetMemberAPI> pool,
            int maxPerDiv,
            String factionId,
            NomenclatureCategory nomCat) {
        if (pool == null || pool.isEmpty()) return;

        List<List<FleetMemberAPI>> chunks = PermanentDivisionManager.distributePoolWithCommandersFirst(pool, maxPerDiv);
        boolean preferCarrier = archetype != null && archetype.isCarrierArchetype();

        for (List<FleetMemberAPI> sub : chunks) {
            if (sub.isEmpty()) continue;

            FleetMemberAPI bestFlagship = null;
            float bestScore = -1f;
            List<String> memberIdList = new ArrayList<>(sub.size());
            for (FleetMemberAPI m : sub) {
                memberIdList.add(m.getId());
                float score = PermanentDivisionManager.computeFlagshipCandidateScore(m, preferCarrier);
                if (score > bestScore) {
                    bestScore = score;
                    bestFlagship = m;
                }
            }
            if (bestFlagship == null) {
                bestFlagship = sub.get(0);
            }
            PersonAPI flagCommander = bestFlagship.getCaptain();
            if ((flagCommander == null || flagCommander.isDefault()) && fleet != null && bestFlagship.isFlagship()) {
                flagCommander = fleet.getCommander();
            }

            FactionDivisionRecord record = allocateOrReactivateFactionDivisionRecord(
                    factionId,
                    nomCat,
                    archetype,
                    fleet,
                    flagCommander,
                    memberIdList,
                    bestFlagship.getId());

            PermanentDivisionSpec spec = new PermanentDivisionSpec(record.recordId, record.divisionName, archetype);
            spec.memberIds.addAll(memberIdList);
            spec.flagshipMemberId = bestFlagship.getId();
            PermanentDivisionManager.recalculateDivisionStandoffRangeFromMembers(spec, sub);
            out.add(spec);
        }
    }

    private static final Map<String, List<FactionDivisionRecord>> RUNTIME_FACTION_TABLES = new HashMap<>();
    private static final Map<String, List<FactionDivisionRecord>> BUCKET_RECORDS_INDEX = new HashMap<>();
    private static final Map<String, FactionDivisionRecord> RECORD_BY_ID = new HashMap<>();
    private static final Map<String, FactionDivisionRecord> RECORD_BY_MEMBER_ID = new HashMap<>();
    private static final Map<String, List<FactionDivisionRecord>> RECORDS_BY_FLEET_ID = new HashMap<>();
    private static final Map<String, WeakReference<CampaignFleetAPI>> LIVE_FLEET_BY_ID = new HashMap<>();
    private static SectorAPI lastLoadedTablesSector = null;
    private static boolean factionTablesDirty = false;

    private static void clearAllRuntimeTablesAndIndexes() {
        RUNTIME_FACTION_TABLES.clear();
        BUCKET_RECORDS_INDEX.clear();
        RECORD_BY_ID.clear();
        RECORD_BY_MEMBER_ID.clear();
        RECORDS_BY_FLEET_ID.clear();
        LIVE_FLEET_BY_ID.clear();
        lastLoadedTablesSector = null;
        factionTablesDirty = false;
    }

    private static void ensureSectorTablesAndIndexesLoaded(SectorAPI sector) {
        if (sector == null || sector == lastLoadedTablesSector) return;
        clearAllRuntimeTablesAndIndexes();
        lastLoadedTablesSector = sector;
        rebuildLiveFleetIndex(sector);
        loadFactionDivisionTablesFromPersistentData(sector);
        reconcileAllLoadedTablesOnce(sector);
    }

    private static void rebuildLiveFleetIndex(SectorAPI sector) {
        LIVE_FLEET_BY_ID.clear();
        if (sector == null) return;
        if (sector.getHyperspace() != null && sector.getHyperspace().getFleets() != null) {
            for (CampaignFleetAPI f : sector.getHyperspace().getFleets()) {
                registerLiveFleet(f);
            }
        }
        if (sector.getStarSystems() != null) {
            for (StarSystemAPI sys : sector.getStarSystems()) {
                if (sys == null || sys.getFleets() == null) continue;
                for (CampaignFleetAPI f : sys.getFleets()) {
                    registerLiveFleet(f);
                }
            }
        }
    }

    private static void registerLiveFleet(CampaignFleetAPI fleet) {
        if (fleet == null) return;
        String id = fleet.getId();
        if (id == null || id.isEmpty()) return;
        SectorAPI sector = Global.getSector();
        if (sector != null && sector != lastLoadedTablesSector) {
            ensureSectorTablesAndIndexesLoaded(sector);
        }
        LIVE_FLEET_BY_ID.put(id, new WeakReference<>(fleet));
    }

    private static String makeBucketIndexKey(String normFactionId, String bucketKey) {
        return normFactionId + "|" + (bucketKey != null ? bucketKey : "");
    }

    private static void insertIntoSortedBucketList(List<FactionDivisionRecord> bucketList, FactionDivisionRecord rec) {
        int n = bucketList.size();
        if (n == 0 || bucketList.get(n - 1).unitNumber <= rec.unitNumber) {
            bucketList.add(rec);
            return;
        }
        int lo = 0;
        int hi = n - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int midNum = bucketList.get(mid).unitNumber;
            if (midNum <= rec.unitNumber) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        bucketList.add(lo, rec);
    }

    private static void unbindRecordAssignments(FactionDivisionRecord rec) {
        if (rec == null) return;
        if (rec.assignedFleetId != null) {
            List<FactionDivisionRecord> byFleet = RECORDS_BY_FLEET_ID.get(rec.assignedFleetId);
            if (byFleet != null) {
                byFleet.remove(rec);
                if (byFleet.isEmpty()) {
                    RECORDS_BY_FLEET_ID.remove(rec.assignedFleetId);
                }
            }
        }
        if (!rec.memberIds.isEmpty()) {
            for (int i = 0, n = rec.memberIds.size(); i < n; i++) {
                String mId = rec.memberIds.get(i);
                if (mId != null && RECORD_BY_MEMBER_ID.get(mId) == rec) {
                    RECORD_BY_MEMBER_ID.remove(mId);
                }
            }
        }
    }

    private static void bindRecordAssignments(FactionDivisionRecord rec) {
        if (rec == null || !rec.active) return;
        if (rec.assignedFleetId != null && !rec.assignedFleetId.isEmpty()) {
            List<FactionDivisionRecord> byFleet = RECORDS_BY_FLEET_ID.get(rec.assignedFleetId);
            if (byFleet == null) {
                byFleet = new ArrayList<>(4);
                RECORDS_BY_FLEET_ID.put(rec.assignedFleetId, byFleet);
            }
            if (!byFleet.contains(rec)) {
                byFleet.add(rec);
            }
        }
        for (int i = 0, n = rec.memberIds.size(); i < n; i++) {
            String mId = rec.memberIds.get(i);
            if (mId != null && !mId.isEmpty()) {
                RECORD_BY_MEMBER_ID.put(mId, rec);
            }
        }
    }

    private static void indexInsertRecord(FactionDivisionRecord rec) {
        if (rec == null || rec.factionId == null || rec.recordId == null) return;
        RECORD_BY_ID.put(rec.recordId, rec);
        String bKey = makeBucketIndexKey(rec.factionId, rec.bucketKey);
        List<FactionDivisionRecord> bucketList = BUCKET_RECORDS_INDEX.get(bKey);
        if (bucketList == null) {
            bucketList = new ArrayList<>();
            BUCKET_RECORDS_INDEX.put(bKey, bucketList);
        }
        insertIntoSortedBucketList(bucketList, rec);
        if (rec.active) {
            bindRecordAssignments(rec);
        }
    }

    private static void indexActivateRecord(
            FactionDivisionRecord rec,
            String fleetId,
            Collection<String> newMemberIds,
            String flagshipId,
            boolean heldByStory) {
        if (rec == null) return;
        List<String> snapshotMembers = (newMemberIds == rec.memberIds)
                ? new ArrayList<>(newMemberIds)
                : null;
        unbindRecordAssignments(rec);
        rec.active = true;
        rec.assignedFleetId = fleetId;
        rec.heldByStoryFleet = heldByStory;
        rec.flagshipMemberId = flagshipId;
        rec.memberIds.clear();
        if (snapshotMembers != null) {
            rec.memberIds.addAll(snapshotMembers);
        } else if (newMemberIds != null) {
            rec.memberIds.addAll(newMemberIds);
        }
        bindRecordAssignments(rec);
        factionTablesDirty = true;
    }

    private static void indexDeactivateRecord(FactionDivisionRecord rec) {
        if (rec == null) return;
        unbindRecordAssignments(rec);
        rec.active = false;
        rec.assignedFleetId = null;
        rec.heldByStoryFleet = false;
        rec.flagshipMemberId = null;
        rec.memberIds.clear();
        factionTablesDirty = true;
    }

    private static void indexRebucketRecord(FactionDivisionRecord rec, String newBucketKey) {
        if (rec == null || newBucketKey == null || newBucketKey.equals(rec.bucketKey)) return;
        String oldKey = makeBucketIndexKey(rec.factionId, rec.bucketKey);
        List<FactionDivisionRecord> oldList = BUCKET_RECORDS_INDEX.get(oldKey);
        if (oldList != null) {
            oldList.remove(rec);
        }
        rec.bucketKey = newBucketKey;
        String nextKey = makeBucketIndexKey(rec.factionId, newBucketKey);
        List<FactionDivisionRecord> nextList = BUCKET_RECORDS_INDEX.get(nextKey);
        if (nextList == null) {
            nextList = new ArrayList<>();
            BUCKET_RECORDS_INDEX.put(nextKey, nextList);
        }
        insertIntoSortedBucketList(nextList, rec);
        factionTablesDirty = true;
    }

    private static void clearFactionDivisionTable(String factionId) {
        String fId = (factionId != null && !factionId.isEmpty()) ? factionId.toLowerCase(Locale.ROOT) : "independent";
        List<FactionDivisionRecord> table = getOrCreateFactionDivisionTable(fId);
        for (FactionDivisionRecord rec : table) {
            unbindRecordAssignments(rec);
            if (rec.recordId != null) {
                RECORD_BY_ID.remove(rec.recordId);
            }
        }
        table.clear();
        String prefix = fId + "|";
        for (Iterator<Map.Entry<String, List<FactionDivisionRecord>>> it = BUCKET_RECORDS_INDEX.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, List<FactionDivisionRecord>> e = it.next();
            if (e.getKey() != null && e.getKey().startsWith(prefix)) {
                it.remove();
            }
        }
        factionTablesDirty = true;
    }

    @SuppressWarnings("unchecked")
    public static List<FactionDivisionRecord> getOrCreateFactionDivisionTable(String factionId) {
        String fId = (factionId != null && !factionId.isEmpty()) ? factionId.toLowerCase(Locale.ROOT) : "independent";
        SectorAPI sector = Global.getSector();
        if (sector != null && sector != lastLoadedTablesSector) {
            ensureSectorTablesAndIndexesLoaded(sector);
        }
        List<FactionDivisionRecord> table = RUNTIME_FACTION_TABLES.get(fId);
        if (table == null) {
            table = new ArrayList<>();
            RUNTIME_FACTION_TABLES.put(fId, table);
        }
        return table;
    }

    /**
     * Marks all division records assigned to a despawned/destroyed fleet as inactive (destroyed)
     * in O(divisions_in_fleet) via RECORDS_BY_FLEET_ID without scanning sector fleets or serializing tables.
     */
    public static void deactivateFleetDivisionsOnDespawn(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.isPlayerFleet()) return;
        SectorAPI sector = Global.getSector();
        if (sector != null && sector != lastLoadedTablesSector) {
            ensureSectorTablesAndIndexesLoaded(sector);
        }
        String fId = fleet.getId();
        if (fId == null) return;
        LIVE_FLEET_BY_ID.remove(fId);
        List<FactionDivisionRecord> assigned = RECORDS_BY_FLEET_ID.get(fId);
        if (assigned == null || assigned.isEmpty()) return;
        List<FactionDivisionRecord> copy = new ArrayList<>(assigned);
        for (int i = 0, n = copy.size(); i < n; i++) {
            indexDeactivateRecord(copy.get(i));
        }
    }

    private static boolean isRecordAssignedFleetLive(SectorAPI sector, FactionDivisionRecord rec) {
        if (rec == null || !rec.active) return false;
        if (rec.assignedFleetId == null || rec.assignedFleetId.isEmpty()) {
            indexDeactivateRecord(rec);
            return false;
        }
        if (sector == null) {
            return true;
        }
        CampaignFleetAPI liveFleet = findLiveFleetById(sector, rec.assignedFleetId);
        if (liveFleet == null || !liveFleet.isAlive() || liveFleet.isDespawning()) {
            indexDeactivateRecord(rec);
            return false;
        }
        return true;
    }

    private static FactionDivisionRecord allocateOrReactivateFactionDivisionRecord(
            String factionId,
            NomenclatureCategory nomCat,
            DivisionArchetype archetype,
            CampaignFleetAPI fleet,
            PersonAPI flagCommander,
            List<String> memberIds,
            String flagshipMemberId) {

        String fId = (factionId != null && !factionId.isEmpty()) ? factionId.toLowerCase(Locale.ROOT) : "independent";
        FactionLibrary.FleetRoleCategory fleetRole = FactionLibrary.resolveFleetRoleCategory(fleet);
        String baseBucket = DivisionNomenclature.resolvePrefixBucketKey(archetype);
        String bucketKey = fleetRole.isNonWarFleet()
                ? (fleetRole.id.toUpperCase(Locale.ROOT) + ":" + baseBucket)
                : baseBucket;
        List<FactionDivisionRecord> table = getOrCreateFactionDivisionTable(fId);
        SectorAPI sector = Global.getSector();
        registerLiveFleet(fleet);

        boolean isStory = isStoryOrSpecialFleet(fleet);
        String fleetId = fleet != null ? fleet.getId() : null;

        String bIndexKey = makeBucketIndexKey(fId, bucketKey);
        List<FactionDivisionRecord> bucketRecords = BUCKET_RECORDS_INDEX.get(bIndexKey);
        if (bucketRecords == null) {
            bucketRecords = new ArrayList<>();
            BUCKET_RECORDS_INDEX.put(bIndexKey, bucketRecords);
        }
        int maxUnitNum = bucketRecords.isEmpty() ? 0 : bucketRecords.get(bucketRecords.size() - 1).unitNumber;

        // 1. Look for the lowest-numbered inactive (destroyed) record to reactivate!
        //    bucketRecords is strictly sorted by unitNumber ascending; lazily verify active records in O(1).
        FactionDivisionRecord chosen = null;
        for (int i = 0, n = bucketRecords.size(); i < n; i++) {
            FactionDivisionRecord rec = bucketRecords.get(i);
            if (!rec.active || !isRecordAssignedFleetLive(sector, rec)) {
                chosen = rec;
                break;
            }
        }

        // 2. If this is a Story/Special fleet and all lower-numbered records are active, check if Unit #1 (or a lower number)
        // is currently held by a non-story fleet; if so, preempt it so the Story Fleet gets Unit #1 (e.g. Battle Div. I)!
        if (isStory) {
            for (int i = 0, n = bucketRecords.size(); i < n; i++) {
                FactionDivisionRecord rec = bucketRecords.get(i);
                if (chosen != null && rec.unitNumber >= chosen.unitNumber) {
                    break;
                }
                if (rec.active && isRecordAssignedFleetLive(sector, rec) && !rec.heldByStoryFleet) {
                    bumpPreemptedRecordToNewNumber(fId, nomCat, table, rec, maxUnitNum + 1);
                    chosen = rec;
                    break;
                } else if (!rec.active) {
                    chosen = rec;
                    break;
                }
            }
        }

        if (chosen == null) {
            int nextNum = maxUnitNum + 1;
            chosen = new FactionDivisionRecord();
            chosen.factionId = fId;
            chosen.bucketKey = bucketKey;
            chosen.unitNumber = nextNum;
            chosen.recordId = fId + "_" + bucketKey + "_" + nextNum;
            chosen.unitSeed = fId.hashCode() * 6364136223846793005L
                    + bucketKey.hashCode() * 1442695040888963407L
                    + nextNum * 2654435761L;
            table.add(chosen);
            indexInsertRecord(chosen);
        }

        chosen.archetype = archetype;
        chosen.timesActivated++;
        indexActivateRecord(chosen, fleetId, memberIds, flagshipMemberId, isStory);
        chosen.divisionName = DivisionNomenclature.formatFactionDivisionName(
                fId,
                nomCat,
                archetype,
                chosen.unitNumber,
                flagCommander,
                chosen.unitSeed,
                fleetRole);
        return chosen;
    }

    @SuppressWarnings("unchecked")
    private static void bumpPreemptedRecordToNewNumber(
            String factionId,
            NomenclatureCategory nomCat,
            List<FactionDivisionRecord> table,
            FactionDivisionRecord preempted,
            int newUnitNumber) {

        String oldFleetId = preempted.assignedFleetId;
        List<String> oldMembers = new ArrayList<>(preempted.memberIds);
        String oldFlagshipId = preempted.flagshipMemberId;
        indexDeactivateRecord(preempted);

        FactionDivisionRecord replacement = new FactionDivisionRecord();
        replacement.factionId = factionId;
        replacement.bucketKey = preempted.bucketKey;
        replacement.archetype = preempted.archetype;
        replacement.unitNumber = newUnitNumber;
        replacement.recordId = factionId + "_" + preempted.bucketKey + "_" + newUnitNumber;
        replacement.unitSeed = factionId.hashCode() * 6364136223846793005L
                + preempted.bucketKey.hashCode() * 1442695040888963407L
                + newUnitNumber * 2654435761L;
        replacement.active = true;
        replacement.timesActivated = 1;
        replacement.assignedFleetId = oldFleetId;
        replacement.heldByStoryFleet = false;
        replacement.memberIds.addAll(oldMembers);
        replacement.flagshipMemberId = oldFlagshipId;
        replacement.divisionName = DivisionNomenclature.formatFactionDivisionName(
                factionId,
                nomCat,
                replacement.archetype,
                newUnitNumber,
                null,
                replacement.unitSeed);
        table.add(replacement);
        indexInsertRecord(replacement);
        factionTablesDirty = true;

        // Update the bumped fleet's cached division list if that fleet is currently instanced
        SectorAPI sector = Global.getSector();
        if (sector != null && oldFleetId != null) {
            CampaignFleetAPI oldFleet = findLiveFleetById(sector, oldFleetId);
            if (oldFleet != null && oldFleet.getMemoryWithoutUpdate() != null) {
                MemoryAPI oldMem = oldFleet.getMemoryWithoutUpdate();
                List<PermanentDivisionSpec> specs = readNpcDivisionsFromMemory(oldMem);
                if (!specs.isEmpty()) {
                    for (int i = 0; i < specs.size(); i++) {
                        PermanentDivisionSpec s = specs.get(i);
                        if (s != null && preempted.recordId.equals(s.id)) {
                            PermanentDivisionSpec updated = new PermanentDivisionSpec(
                                    replacement.recordId,
                                    replacement.divisionName,
                                    replacement.archetype);
                            updated.memberIds.addAll(s.memberIds);
                            updated.flagshipMemberId = s.flagshipMemberId;
                            copyCustomSpecFields(updated, s);
                            specs.set(i, updated);
                            writeNpcDivisionsToMemory(oldMem, specs);
                            break;
                        }
                    }
                }
            }
        }
    }

    private static void reconcileAllLoadedTablesOnce(SectorAPI sector) {
        if (sector == null || RUNTIME_FACTION_TABLES.isEmpty()) return;
        for (List<FactionDivisionRecord> table : RUNTIME_FACTION_TABLES.values()) {
            if (table == null) continue;
            for (int i = 0, n = table.size(); i < n; i++) {
                FactionDivisionRecord rec = table.get(i);
                if (rec != null && rec.active) {
                    isRecordAssignedFleetLive(sector, rec);
                }
            }
        }
    }

    private static CampaignFleetAPI findLiveFleetById(SectorAPI sector, String fleetId) {
        if (sector == null || fleetId == null || fleetId.isEmpty()) return null;
        if (sector != lastLoadedTablesSector) {
            ensureSectorTablesAndIndexesLoaded(sector);
        }
        WeakReference<CampaignFleetAPI> ref = LIVE_FLEET_BY_ID.get(fleetId);
        if (ref == null) return null;
        CampaignFleetAPI f = ref.get();
        if (f == null || !f.isAlive() || f.isDespawning()) {
            LIVE_FLEET_BY_ID.remove(fleetId);
            return null;
        }
        return f;
    }

    private static FactionDivisionRecord parseCompactRecordV2(String fId, String encoded) {
        if (encoded == null || !encoded.startsWith("v2\t")) return null;
        // Format: v2 \t recordId \t bucketKey \t archetype \t unitNum \t unitSeed \t active \t story \t timesAct \t fleetId \t flagId \t csvMembers \t divName
        String[] parts = new String[13];
        int start = 0;
        for (int p = 0; p < 12; p++) {
            int tab = encoded.indexOf('\t', start);
            if (tab < 0) return null;
            parts[p] = encoded.substring(start, tab);
            start = tab + 1;
        }
        parts[12] = encoded.substring(start);

        FactionDivisionRecord rec = new FactionDivisionRecord();
        rec.recordId = parts[1].isEmpty() ? null : parts[1];
        rec.factionId = fId;
        rec.bucketKey = parts[2].isEmpty() ? null : parts[2];
        if (rec.recordId == null || rec.bucketKey == null) return null;
        try {
            rec.archetype = parts[3].isEmpty()
                    ? DivisionArchetype.CRUISER_DIVISION
                    : DivisionArchetype.valueOf(parts[3]);
        } catch (IllegalArgumentException ignored) {
            rec.archetype = DivisionArchetype.CRUISER_DIVISION;
        }
        try {
            rec.unitNumber = Integer.parseInt(parts[4]);
        } catch (NumberFormatException ignored) {
            rec.unitNumber = 1;
        }
        try {
            rec.unitSeed = Long.parseLong(parts[5]);
        } catch (NumberFormatException ignored) {
            rec.unitSeed = 0L;
        }
        rec.active = "1".equals(parts[6]);
        rec.heldByStoryFleet = "1".equals(parts[7]);
        try {
            rec.timesActivated = Integer.parseInt(parts[8]);
        } catch (NumberFormatException ignored) {
            rec.timesActivated = 1;
        }
        rec.assignedFleetId = parts[9].isEmpty() ? null : parts[9];
        rec.flagshipMemberId = parts[10].isEmpty() ? null : parts[10];
        String csv = parts[11];
        if (!csv.isEmpty()) {
            int mStart = 0;
            while (mStart <= csv.length()) {
                int comma = csv.indexOf(',', mStart);
                String tok = (comma < 0) ? csv.substring(mStart) : csv.substring(mStart, comma);
                if (!tok.isEmpty()) {
                    rec.memberIds.add(tok);
                }
                if (comma < 0) break;
                mStart = comma + 1;
            }
        }
        rec.divisionName = DivisionNomenclature.migrateLegacyDivisionName(
                parts[12].isEmpty() ? null : parts[12],
                rec.archetype);
        return rec;
    }

    private static String formatCompactRecordV2(FactionDivisionRecord rec) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("v2\t");
        if (rec.recordId != null) sb.append(rec.recordId);
        sb.append('\t');
        if (rec.bucketKey != null) sb.append(rec.bucketKey);
        sb.append('\t');
        sb.append(rec.archetype != null ? rec.archetype.name() : DivisionArchetype.CRUISER_DIVISION.name());
        sb.append('\t').append(rec.unitNumber);
        sb.append('\t').append(rec.unitSeed);
        sb.append('\t').append(rec.active ? '1' : '0');
        sb.append('\t').append(rec.heldByStoryFleet ? '1' : '0');
        sb.append('\t').append(rec.timesActivated);
        sb.append('\t');
        if (rec.active && rec.assignedFleetId != null) sb.append(rec.assignedFleetId);
        sb.append('\t');
        if (rec.active && rec.flagshipMemberId != null) sb.append(rec.flagshipMemberId);
        sb.append('\t');
        if (rec.active && !rec.memberIds.isEmpty()) {
            for (int i = 0, n = rec.memberIds.size(); i < n; i++) {
                if (i > 0) sb.append(',');
                String mId = rec.memberIds.get(i);
                if (mId != null) sb.append(mId);
            }
        }
        sb.append('\t');
        if (rec.divisionName != null) {
            for (int i = 0, n = rec.divisionName.length(); i < n; i++) {
                char c = rec.divisionName.charAt(i);
                sb.append(c < ' ' ? ' ' : c);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void loadFactionDivisionTablesFromPersistentData(SectorAPI sector) {
        if (sector == null || sector.getPersistentData() == null) return;
        Object raw = sector.getPersistentData().get(PERSISTENT_FACTION_TABLES_KEY);
        if (!(raw instanceof Map)) return;
        Map<String, Object> byFaction = (Map<String, Object>) raw;
        for (Map.Entry<String, Object> entry : byFaction.entrySet()) {
            String rawFactionKey = entry.getKey();
            if (rawFactionKey == null || !(entry.getValue() instanceof List)) continue;
            String fId = rawFactionKey.toLowerCase(Locale.ROOT);
            List<?> rawRecords = (List<?>) entry.getValue();
            List<FactionDivisionRecord> table = new ArrayList<>(rawRecords.size());
            for (Object item : rawRecords) {
                FactionDivisionRecord rec = null;
                if (item instanceof String) {
                    rec = parseCompactRecordV2(fId, (String) item);
                } else if (item instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) item;
                    rec = new FactionDivisionRecord();
                    rec.recordId = (String) m.get("recordId");
                    rec.factionId = fId;
                    rec.bucketKey = (String) m.get("bucketKey");
                    String archStr = (String) m.get("archetype");
                    if (archStr != null) {
                        try {
                            rec.archetype = DivisionArchetype.valueOf(archStr);
                        } catch (IllegalArgumentException ignored) {
                            rec.archetype = DivisionArchetype.CRUISER_DIVISION;
                        }
                    }
                    Object uNum = m.get("unitNumber");
                    rec.unitNumber = (uNum instanceof Number) ? ((Number) uNum).intValue() : 1;
                    Object uSeed = m.get("unitSeed");
                    rec.unitSeed = (uSeed instanceof Number) ? ((Number) uSeed).longValue() : 0L;
                    rec.divisionName = DivisionNomenclature.migrateLegacyDivisionName(
                            (String) m.get("divisionName"),
                            rec.archetype);
                    rec.assignedFleetId = (String) m.get("assignedFleetId");
                    rec.heldByStoryFleet = Boolean.TRUE.equals(m.get("heldByStoryFleet"));
                    rec.active = Boolean.TRUE.equals(m.get("active"));
                    Object tAct = m.get("timesActivated");
                    rec.timesActivated = (tAct instanceof Number) ? ((Number) tAct).intValue() : 1;
                    rec.flagshipMemberId = (String) m.get("flagshipMemberId");
                    Object mIds = m.get("memberIds");
                    if (mIds instanceof List) {
                        for (Object idObj : (List<?>) mIds) {
                            if (idObj instanceof String) {
                                rec.memberIds.add((String) idObj);
                            }
                        }
                    }
                    if (rec.recordId == null || rec.bucketKey == null) {
                        rec = null;
                    }
                }
                if (rec != null) {
                    table.add(rec);
                    indexInsertRecord(rec);
                }
            }
            RUNTIME_FACTION_TABLES.put(fId, table);
        }
        factionTablesDirty = false;
    }

    private static void flushDirtyFactionDivisionTablesToPersistentData(SectorAPI sector) {
        if (!factionTablesDirty && sector != null && sector.getPersistentData() != null
                && sector.getPersistentData().containsKey(PERSISTENT_FACTION_TABLES_KEY)) {
            return;
        }
        saveFactionDivisionTablesToPersistentData(sector);
    }

    private static void saveFactionDivisionTablesToPersistentData() {
        saveFactionDivisionTablesToPersistentData(Global.getSector());
    }

    private static void saveFactionDivisionTablesToPersistentData(SectorAPI sector) {
        if (sector == null || sector.getPersistentData() == null) return;
        // Uses strictly plain JDK HashMap<String, ArrayList<String>> so XStream writes 1 compact <string> tag
        // per division record (instead of 38 nested <map>/<entry> tags) and remains 100% safe to remove at any time.
        Map<String, Object> serializedByFaction = new HashMap<>();
        for (Map.Entry<String, List<FactionDivisionRecord>> entry : RUNTIME_FACTION_TABLES.entrySet()) {
            List<FactionDivisionRecord> table = entry.getValue();
            if (table == null || table.isEmpty()) continue;
            ArrayList<String> list = new ArrayList<>(table.size());
            for (int i = 0, n = table.size(); i < n; i++) {
                FactionDivisionRecord rec = table.get(i);
                if (rec != null) {
                    list.add(formatCompactRecordV2(rec));
                }
            }
            serializedByFaction.put(entry.getKey(), list);
        }
        sector.getPersistentData().put(PERSISTENT_FACTION_TABLES_KEY, serializedByFaction);
        factionTablesDirty = false;
    }

    /**
     * In the combat layer, matches deployed NPC ships (Allied AI or Enemy) against their source
     * CampaignFleetAPI's sorted divisions so that the exact divisions seen on the campaign map
     * are preserved in battle, with their faction's FleetDoctrineConfig applied.
     */
    public static CampaignFleetAPI resolveTrueCampaignSourceFleet(FleetMemberAPI fm) {
        if (fm == null) return null;
        CampaignFleetAPI currFleet = (fm.getFleetData() != null) ? fm.getFleetData().getFleet() : null;
        try {
            SectorAPI sector = Global.getSector();
            CampaignFleetAPI pFleet = (sector != null) ? sector.getPlayerFleet() : null;
            com.fs.starfarer.api.campaign.BattleAPI battle = (pFleet != null) ? pFleet.getBattle() : null;
            if (battle == null && currFleet != null) {
                battle = currFleet.getBattle();
            }
            if (battle != null) {
                CampaignFleetAPI battleSrc = battle.getSourceFleet(fm);
                if (battleSrc != null) {
                    return battleSrc;
                }
            }
        } catch (Throwable ignored) {
        }
        return currFleet;
    }

    public static boolean isKnownCampaignIndependentMember(CampaignFleetAPI fleet, String memberId) {
        if (fleet == null || memberId == null) return false;
        MemoryAPI mem = fleet.getMemoryWithoutUpdate();
        if (mem == null) return false;
        Object rawIndep = mem.get(MEM_NPC_INDEPENDENT_IDS_KEY);
        if (rawIndep instanceof Collection) {
            return ((Collection<?>) rawIndep).contains(memberId);
        }
        return false;
    }

    public static List<ShipCapabilityProfile> applyCampaignNpcDivisionsInCombat(
            BattleGroupState battleGroup,
            List<ShipCapabilityProfile> unassignedNpcHulls,
            List<DivisionState> newlyCreatedOut) {

        if (battleGroup == null || unassignedNpcHulls == null || unassignedNpcHulls.isEmpty()) {
            return unassignedNpcHulls;
        }

        // Collect all source NPC CampaignFleetAPIs represented by these hulls (deduplicated per fleet!)
        Map<String, PermanentDivisionSpec> memberToSpec = new HashMap<>();
        Map<String, CampaignFleetAPI> specIdToFleet = new HashMap<>();
        Map<CampaignFleetAPI, List<PermanentDivisionSpec>> specsBySourceFleet =
                new java.util.IdentityHashMap<CampaignFleetAPI, List<PermanentDivisionSpec>>();

        for (ShipCapabilityProfile prof : unassignedNpcHulls) {
            if (prof == null || prof.ship == null || prof.ship.getFleetMember() == null) continue;
            FleetMemberAPI fm = prof.ship.getFleetMember();
            CampaignFleetAPI sourceFleet = resolveTrueCampaignSourceFleet(fm);
            if (sourceFleet == null || sourceFleet.isPlayerFleet() || sourceFleet.isStationMode()) continue;

            List<PermanentDivisionSpec> fleetSpecs = specsBySourceFleet.get(sourceFleet);
            if (fleetSpecs == null) {
                fleetSpecs = ensureFleetDivisionsSorted(sourceFleet);
                specsBySourceFleet.put(sourceFleet, fleetSpecs);
                String fleetPrefix = "f" + System.identityHashCode(sourceFleet) + "_";
                for (PermanentDivisionSpec spec : fleetSpecs) {
                    String uniqueSpecId = fleetPrefix + spec.id;
                    specIdToFleet.put(uniqueSpecId, sourceFleet);
                    for (String mId : spec.memberIds) {
                        if (!memberToSpec.containsKey(mId)) {
                            PermanentDivisionSpec copy = new PermanentDivisionSpec(uniqueSpecId, spec.name, spec.archetype);
                            copy.flagshipMemberId = spec.flagshipMemberId;
                            copy.memberIds.addAll(spec.memberIds);
                            copy.doctrine = spec.doctrine;
                            copyCustomSpecFields(copy, spec);
                            memberToSpec.put(mId, copy);
                        }
                    }
                }
            }
        }

        if (memberToSpec.isEmpty()) {
            return unassignedNpcHulls;
        }

        List<ShipCapabilityProfile> leftover = new ArrayList<>();
        Map<String, List<ShipCapabilityProfile>> groupedBySpecId = new LinkedHashMap<>();
        Map<String, PermanentDivisionSpec> specById = new HashMap<>();
        Map<String, Integer> zoneByGroupKey = new HashMap<>();

        com.fs.starfarer.api.combat.CombatEngineAPI engine = Global.getCombatEngine();
        boolean pursuitBattle = OtcAI.isPursuitBattle(engine, battleGroup);
        Map<String, Set<Integer>> zonesPerSpecId = new HashMap<>();
        if (pursuitBattle) {
            for (ShipCapabilityProfile prof : unassignedNpcHulls) {
                String fmId = (prof.ship.getFleetMember() != null) ? prof.ship.getFleetMember().getId() : null;
                PermanentDivisionSpec spec = (fmId != null) ? memberToSpec.get(fmId) : null;
                if (spec == null) continue;
                int z = OtcAI.resolveShipPursuitSpawnZone(engine, prof.ship);
                Set<Integer> zSet = zonesPerSpecId.get(spec.id);
                if (zSet == null) {
                    zSet = new HashSet<>();
                    zonesPerSpecId.put(spec.id, zSet);
                }
                zSet.add(z);
            }
        }

        for (ShipCapabilityProfile prof : unassignedNpcHulls) {
            String fmId = (prof.ship.getFleetMember() != null) ? prof.ship.getFleetMember().getId() : null;
            PermanentDivisionSpec spec = (fmId != null) ? memberToSpec.get(fmId) : null;
            if (spec == null) {
                leftover.add(prof);
                continue;
            }

            int shipZone = pursuitBattle ? OtcAI.resolveShipPursuitSpawnZone(engine, prof.ship) : 0;
            Set<Integer> specZones = zonesPerSpecId.get(spec.id);
            boolean splitAcrossZones = pursuitBattle && specZones != null && specZones.size() > 1;
            String groupKey = (splitAcrossZones && shipZone != 0)
                    ? (spec.id + (shipZone < 0 ? "#L" : "#R"))
                    : spec.id;

            DivisionState existingCombatDiv = null;
            for (DivisionState d : battleGroup.divisions) {
                if (groupKey.equals(d.id) && (!pursuitBattle || d.pursuitSpawnZone == shipZone)) {
                    existingCombatDiv = d;
                    break;
                }
            }

            if (existingCombatDiv != null && existingCombatDiv.members.size() < PermanentDivisionManager.MAX_DIVISION_SIZE) {
                existingCombatDiv.members.add(prof.ship);
                existingCombatDiv.profiles.add(prof);
                battleGroup.shipIdToDivision.put(prof.ship.getId(), existingCombatDiv);
                if (fmId.equals(spec.flagshipMemberId) || existingCombatDiv.flagship == null || !existingCombatDiv.flagship.isAlive()) {
                    existingCombatDiv.flagship = prof.ship;
                }
                DivisionAutoFormer.recomputeDivisionCapabilities(existingCombatDiv);
                CampaignFleetAPI sourceFleet = specIdToFleet.get(spec.id);
                applyFactionDoctrineToCombatDivision(existingCombatDiv, spec, getDoctrineForFleet(sourceFleet));
                DivisionCombatLog.logReinforcementJoined(existingCombatDiv, prof.ship);
            } else {
                specById.put(groupKey, spec);
                zoneByGroupKey.put(groupKey, shipZone);
                List<ShipCapabilityProfile> list = groupedBySpecId.get(groupKey);
                if (list == null) {
                    list = new ArrayList<>();
                    groupedBySpecId.put(groupKey, list);
                }
                list.add(prof);
            }
        }

        int fallbackNumber = battleGroup.divisions.size() + 1;
        for (Map.Entry<String, List<ShipCapabilityProfile>> entry : groupedBySpecId.entrySet()) {
            String groupKey = entry.getKey();
            PermanentDivisionSpec spec = specById.get(groupKey);
            List<ShipCapabilityProfile> members = entry.getValue();
            if (spec == null || members.isEmpty()) continue;

            int zone = zoneByGroupKey.containsKey(groupKey) ? zoneByGroupKey.get(groupKey) : 0;
            PermanentDivisionSpec effectiveSpec = spec;
            if (!groupKey.equals(spec.id)) {
                String suffix = (zone < 0) ? " (Left Flank)" : " (Right Flank)";
                effectiveSpec = new PermanentDivisionSpec(groupKey, spec.name + suffix, spec.archetype);
                effectiveSpec.flagshipMemberId = spec.flagshipMemberId;
                effectiveSpec.memberIds.addAll(spec.memberIds);
                effectiveSpec.doctrine = spec.doctrine;
                copyCustomSpecFields(effectiveSpec, spec);
            }

            DivisionState combatDiv = PermanentDivisionManager.instantiateCombatDivisionFromSpec(
                    battleGroup, effectiveSpec, members, fallbackNumber++);
            combatDiv.pursuitSpawnZone = zone;
            combatDiv.fleetFormationWingSide = zone;
            if (zone != 0) {
                combatDiv.consentedWingSide = zone;
            }
            CampaignFleetAPI sourceFleet = specIdToFleet.get(spec.id);
            applyFactionDoctrineToCombatDivision(combatDiv, effectiveSpec, getDoctrineForFleet(sourceFleet));
            battleGroup.divisions.add(combatDiv);
            newlyCreatedOut.add(combatDiv);
        }

        return leftover;
    }

    private static void copyCustomSpecFields(PermanentDivisionSpec dst, PermanentDivisionSpec src) {
        if (dst == null || src == null) return;
        dst.customStationSpacing = src.customStationSpacing;
        dst.customDivisionInterval = src.customDivisionInterval;
        dst.customStandoffRange = src.customStandoffRange;
        dst.customOpeningMission = src.customOpeningMission;
        dst.customMainMission = src.customMainMission;
        dst.customStrikeCraftOpeningMission = src.customStrikeCraftOpeningMission;
        dst.customStrikeCraftMainMission = src.customStrikeCraftMainMission;
        dst.customLineStation = src.customLineStation;
        dst.customFormation = src.customFormation;
        dst.customMovementStickiness = src.customMovementStickiness;
        dst.customVoidstrikeTargetingBias = src.customVoidstrikeTargetingBias;
        dst.customVoidstrikeFocusCarriers = src.customVoidstrikeFocusCarriers;
        dst.customVoidstrikeObservance = src.customVoidstrikeObservance;
        dst.customVoidstrikeCoordination = src.customVoidstrikeCoordination;
        dst.customVoidstrikeAttackGeometry = src.customVoidstrikeAttackGeometry;
        dst.customVoidstrikeSplitMode = src.customVoidstrikeSplitMode;
        dst.customVoidstrikeGunneryMode = src.customVoidstrikeGunneryMode;
        dst.customVoidstrikeAoaCorrection = src.customVoidstrikeAoaCorrection;
        dst.customFighterFormation = src.customFighterFormation;
        dst.customFighterVoidstrikeFormation = src.customFighterVoidstrikeFormation;
        dst.customBomberFormation = src.customBomberFormation;
        dst.customBomberVoidstrikeFormation = src.customBomberVoidstrikeFormation;
        dst.customVoidstrikeSquadronDepth = src.customVoidstrikeSquadronDepth;
        dst.customVoidstrikeSquadronWidth = src.customVoidstrikeSquadronWidth;
        dst.customSetpieceAttack = src.customSetpieceAttack;
        dst.customShipSkillCoordination = src.customShipSkillCoordination;
        dst.customRammingMode = src.customRammingMode;
        dst.customRammingTargetScope = src.customRammingTargetScope;
        dst.customMaxRammingSlots = src.customMaxRammingSlots;
    }

    public static void applyFactionDoctrineToCombatDivision(
            DivisionState div,
            PermanentDivisionSpec optionalSpec,
            FleetDoctrineConfig cfg) {
        PermanentDivisionManager.applyDoctrineModifiersToCombatDivision(div, optionalSpec, cfg);
    }
}
