package divisionai;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.SoundPlayerAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.DivisionState;
import org.apache.log4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Explicit Fleet Engagement / Violent Contact Evaluator & Submod-Friendly Multi-Layered Dynamic Battle Music System.
 *
 * <p>1. <b>Mass-Weighted Fleet Engagement Telemetry</b>:
 * Measures each division's active violent contact ({@code violentContactIntensity} in {@code [0.0, 1.0]})
 * from weapon discharge, incoming short-DoT/alpha fire, flux/hull pressure, and close combat proximity,
 * and weights it by the division's combat mass relative to the entire fleet ({@code engagedFpMass / totalFriendlyStrength}).
 * Two frigate divisions skirmishing while a 4-ship battleship division remains uncommitted stays below the
 * activation threshold ({@code ~0.16-0.21 < 0.26}), whereas 4 frigate divisions engaging or the main battleship
 * division engaging crosses the threshold ({@code >= 0.26}) and scales volume with engagement intensity.
 *
 * <p>2. <b>Submod-Friendly Multi-Layered Battle Music</b>:
 * Configured via {@code data/config/division_battle_music.json} (merged across all mods via
 * {@code Global.getSettings().getMergedJSONForMod("data/config/division_battle_music.json", "division_ai")})
 * and {@code data/config/sounds.json}, plus a public Java registration API ({@link #registerFactionProfile}).
 * Supports silent encounters (Threat, Abyssal), sequential looping playlists (Luddic, Pirates), randomized
 * encounter variants (Hegemony), traitor/deserter bounty fleet detection, and multi-layered compositions where
 * each layer activates at its own engagement threshold and modulates volume by live engagement level.
 */
public class DynamicBattleMusicController {

    private static final Logger LOG = Global.getLogger(DynamicBattleMusicController.class);

    public static final String CONFIG_PATH = "data/config/division_battle_music.json";
    public static final String MOD_ID = "division_ai";

    public static final float DEFAULT_ACTIVATION_THRESHOLD = 0.26f;
    public static final float DEFAULT_FULL_VOLUME_ENGAGEMENT = 0.72f;
    public static final float DEFAULT_MIN_VOLUME = 0.24f;
    public static final float DEFAULT_MAX_VOLUME = 1.00f;
    public static final float DEFAULT_FADE_IN_SPEED = 0.38f;
    public static final float DEFAULT_FADE_OUT_SPEED = 0.22f;

    /**
     * Specification for a single engagement-driven music layer within a faction's battle music profile.
     */
    public static class MusicLayerSpec {
        public final String id;
        public final List<String> musicSetIds;
        public final List<String> files;
        public final String source;
        public final boolean randomStart;
        public final float activationThreshold;
        public final float fullVolumeEngagement;
        public final float minVolume;
        public final float maxVolume;

        public MusicLayerSpec(
                String id,
                List<String> musicSetIds,
                List<String> files,
                String source,
                boolean randomStart,
                float activationThreshold,
                float fullVolumeEngagement,
                float minVolume,
                float maxVolume) {
            this.id = (id != null && !id.isEmpty()) ? id : "base";
            this.musicSetIds = musicSetIds != null
                    ? Collections.unmodifiableList(new ArrayList<>(musicSetIds))
                    : Collections.<String>emptyList();
            this.files = files != null
                    ? Collections.unmodifiableList(new ArrayList<>(files))
                    : Collections.<String>emptyList();
            this.source = (source != null && !source.isEmpty()) ? source : "sounds/music/music.bin";
            this.randomStart = randomStart;
            this.activationThreshold = clamp01(activationThreshold);
            this.fullVolumeEngagement = Math.max(this.activationThreshold + 0.05f, clamp01(fullVolumeEngagement));
            this.minVolume = clamp01(minVolume);
            this.maxVolume = Math.max(this.minVolume, clamp01(maxVolume));
        }

        /**
         * Computes the target layer volume for a given fleet engagement level once the layer has been triggered.
         */
        public float computeTargetVolume(float engagementLevel, boolean alreadyTriggered) {
            if (!alreadyTriggered && engagementLevel < activationThreshold) {
                return 0f;
            }
            if (engagementLevel >= activationThreshold) {
                float span = Math.max(0.05f, fullVolumeEngagement - activationThreshold);
                float t = clamp01((engagementLevel - activationThreshold) / span);
                return minVolume + t * (maxVolume - minVolume);
            }
            // Once triggered, if engagement temporarily dips below activationThreshold, scale smoothly down toward 0
            float lowT = clamp01(engagementLevel / Math.max(0.05f, activationThreshold));
            return lowT * minVolume;
        }
    }

    /**
     * Complete battle music profile for a faction (either silent or composed of 1..N engagement layers).
     */
    public static class FactionMusicProfile {
        public final String factionKey;
        public final boolean silent;
        public final List<MusicLayerSpec> layers;

        public FactionMusicProfile(String factionKey, boolean silent, List<MusicLayerSpec> layers) {
            this.factionKey = factionKey != null ? factionKey.toLowerCase(Locale.ROOT) : "default";
            this.silent = silent;
            this.layers = layers != null
                    ? Collections.unmodifiableList(new ArrayList<>(layers))
                    : Collections.<MusicLayerSpec>emptyList();
        }
    }

    private static class ActiveLayerRuntime {
        final MusicLayerSpec spec;
        final int layerIndex;
        final String chosenMusicSetId;
        final List<String> chosenFiles;
        boolean triggered = false;
        boolean playbackStarted = false;
        float currentVolume = 0f;
        float targetVolume = 0f;
        Object standaloneStreamPlayer = null; // sound.O instance for layers >= 1 (or direct stream fallback)

        ActiveLayerRuntime(MusicLayerSpec spec, int layerIndex, Random rng) {
            this.spec = spec;
            this.layerIndex = layerIndex;
            if (!spec.musicSetIds.isEmpty()) {
                int idx = (spec.musicSetIds.size() > 1 && rng != null)
                        ? rng.nextInt(spec.musicSetIds.size())
                        : 0;
                this.chosenMusicSetId = spec.musicSetIds.get(idx);
            } else {
                this.chosenMusicSetId = null;
            }
            if (!spec.files.isEmpty()) {
                if (spec.randomStart && spec.files.size() > 1 && spec.musicSetIds.size() > 1) {
                    int idx = (rng != null) ? rng.nextInt(spec.files.size()) : 0;
                    this.chosenFiles = Collections.singletonList(spec.files.get(idx));
                } else {
                    this.chosenFiles = spec.files;
                }
            } else {
                this.chosenFiles = Collections.emptyList();
            }
        }
    }

    // Submod-extensible registry of faction profiles
    private static final Map<String, FactionMusicProfile> FACTION_PROFILES = new LinkedHashMap<>();
    private static FactionMusicProfile defaultProfile = null;
    private static boolean configLoaded = false;
    private static boolean systemEnabled = true;
    private static float fadeInSpeedPerSec = DEFAULT_FADE_IN_SPEED;
    private static float fadeOutSpeedPerSec = DEFAULT_FADE_OUT_SPEED;

    // MethodHandles for Starsector's native sound.H, sound.O, and org.json (zero java.lang.reflect)
    private static boolean audioHandlesInitialized = false;
    private static MethodHandle mhGetMergedJsonForMod;
    private static MethodHandle mhJsonObjFromStringCtor;
    private static MethodHandle mhJsonObjOptBoolean;
    private static MethodHandle mhJsonObjOptDouble;
    private static MethodHandle mhJsonObjOptString;
    private static MethodHandle mhJsonObjOptJsonObject;
    private static MethodHandle mhJsonObjOptJsonArray;
    private static MethodHandle mhJsonObjNames;
    private static MethodHandle mhJsonArrLength;
    private static MethodHandle mhJsonArrOptString;
    private static MethodHandle mhJsonArrOptJsonObject;

    private static MethodHandle mhSoundHGetInstance;
    private static MethodHandle mhSoundHGetMasterMusicVol;
    private static MethodHandle mhSoundHSetDynamicVolMult;
    private static MethodHandle mhSoundHStopCurrent;
    private static MethodHandle mhStreamDescriptorCtor;
    private static MethodHandle mhStreamDescriptorInit;
    private static MethodHandle mhStreamDescriptorCreatePlayer;
    private static MethodHandle mhSoundOSetLoop;
    private static MethodHandle mhSoundOStartPlay;
    private static MethodHandle mhSoundOAdvanceWithVol;
    private static MethodHandle mhSoundOStopAndRelease;

    // Track whether any combat music session suspended default music so campaign transitions can clean up safely
    private static boolean globalCombatMusicSuspended = false;

    // Instance state per battle
    private boolean battleSessionInitialized = false;
    private boolean battleSessionCleanedUp = false;
    private String activeEnemyFactionKey = "default";
    private FactionMusicProfile activeProfile = null;
    private final List<ActiveLayerRuntime> activeLayers = new ArrayList<>();
    private float currentBattleEngagementLevel = 0f;
    private final Random rng = new Random();

    static {
        installBuiltInDefaults();
    }

    /**
     * Public API for submods to register or override a faction's dynamic battle music profile programmatically.
     */
    public static synchronized void registerFactionProfile(String factionId, FactionMusicProfile profile) {
        if (factionId == null || profile == null) return;
        FACTION_PROFILES.put(factionId.trim().toLowerCase(Locale.ROOT), profile);
    }

    /**
     * Public API for submods to override the default fallback battle music profile.
     */
    public static synchronized void setDefaultProfile(FactionMusicProfile profile) {
        if (profile != null) {
            defaultProfile = profile;
        }
    }

    public static synchronized void ensureConfigLoaded() {
        if (configLoaded) return;
        installBuiltInDefaults();
        try {
            ensureJsonHandlesInitialized();
            if (Global.getSettings() != null && mhGetMergedJsonForMod != null) {
                Object rootJson = mhGetMergedJsonForMod.invoke(Global.getSettings(), CONFIG_PATH, MOD_ID);
                if (rootJson != null) {
                    parseMergedConfigJsonObject(rootJson);
                }
            }
            configLoaded = true;
        } catch (Throwable t) {
            // In headless unit tests without full Starsector resource loading, built-in defaults remain active
            configLoaded = true;
        }
    }

    private static void installBuiltInDefaults() {
        FACTION_PROFILES.clear();
        defaultProfile = singleLayerProfile(
                "default",
                Collections.singletonList("divisionai_battle_default"),
                Collections.singletonList("faction_hegemony_market_02_hostile_var01.ogg"),
                false);

        // vs. Hegemony Fleet or Hegemony traitor bounty fleet
        FACTION_PROFILES.put("hegemony", singleLayerProfile(
                "hegemony",
                Arrays.asList("divisionai_battle_hegemony_hostile", "divisionai_battle_hegemony_neutral"),
                Arrays.asList("faction_hegemony_encounter_02_hostile.ogg", "faction_hegemony_encounter_01_neutral.ogg"),
                true));

        // vs. Persean League or Persean League traitor bounty fleet
        FactionMusicProfile perseanProfile = singleLayerProfile(
                "persean",
                Collections.singletonList("divisionai_battle_persean"),
                Collections.singletonList("faction_league_encounter_03_friendly.ogg"),
                false);
        FACTION_PROFILES.put("persean", perseanProfile);
        FACTION_PROFILES.put("persean_league", perseanProfile);

        // vs. Luddic Church or Luddic Path ({var01 -> var02} sequential loop)
        FactionMusicProfile luddicProfile = singleLayerProfile(
                "luddic_church",
                Collections.singletonList("divisionai_battle_luddic"),
                Arrays.asList(
                        "faction_luddite_market_02_hostile_var01.ogg",
                        "faction_luddite_market_02_hostile_var02.ogg"),
                false);
        FACTION_PROFILES.put("luddic_church", luddicProfile);
        FACTION_PROFILES.put("luddic_path", luddicProfile);
        FACTION_PROFILES.put("knights_of_ludd", luddicProfile);
        FACTION_PROFILES.put("kol", luddicProfile);

        // vs. Pirates ({var01 -> var02} sequential loop)
        FactionMusicProfile pirateProfile = singleLayerProfile(
                "pirates",
                Collections.singletonList("divisionai_battle_pirates"),
                Arrays.asList(
                        "faction_pirate_market_02_hostile_var01.ogg",
                        "faction_pirate_market_02_hostile_var02.ogg"),
                false);
        FACTION_PROFILES.put("pirates", pirateProfile);
        FACTION_PROFILES.put("pirate", pirateProfile);

        // vs. Tri-Tachyon
        FactionMusicProfile tritachProfile = singleLayerProfile(
                "tritachyon",
                Collections.singletonList("divisionai_battle_tritach"),
                Collections.singletonList("faction_tritach_market_02_hostile.ogg"),
                false);
        FACTION_PROFILES.put("tritachyon", tritachProfile);
        FACTION_PROFILES.put("tri_tachyon", tritachProfile);

        // vs. Remnant AI (plays faction_tritach_market_02_hostile.ogg)
        FactionMusicProfile remnantProfile = singleLayerProfile(
                "remnant",
                Collections.singletonList("divisionai_battle_remnant"),
                Collections.singletonList("faction_tritach_market_02_hostile.ogg"),
                false);
        FACTION_PROFILES.put("remnant", remnantProfile);
        FACTION_PROFILES.put("remnants", remnantProfile);
        FACTION_PROFILES.put("omega", remnantProfile);

        // vs. Threat -> nothing plays (silent)
        FactionMusicProfile threatSilent = new FactionMusicProfile("threat", true, Collections.<MusicLayerSpec>emptyList());
        FACTION_PROFILES.put("threat", threatSilent);

        // vs. Abyssal -> nothing plays (silent)
        FactionMusicProfile abyssalSilent = new FactionMusicProfile("dweller", true, Collections.<MusicLayerSpec>emptyList());
        FACTION_PROFILES.put("dweller", abyssalSilent);
        FACTION_PROFILES.put("abyssal", abyssalSilent);
        FACTION_PROFILES.put("abyssal_horrors", abyssalSilent);

        // vs. Derelict -> default battle track
        FACTION_PROFILES.put("derelict", singleLayerProfile(
                "derelict",
                Collections.singletonList("divisionai_battle_default"),
                Collections.singletonList("faction_hegemony_market_02_hostile_var01.ogg"),
                false));
    }

    private static FactionMusicProfile singleLayerProfile(
            String key,
            List<String> musicSetIds,
            List<String> files,
            boolean randomStart) {
        MusicLayerSpec baseLayer = new MusicLayerSpec(
                "base",
                musicSetIds,
                files,
                "sounds/music/music.bin",
                randomStart,
                DEFAULT_ACTIVATION_THRESHOLD,
                DEFAULT_FULL_VOLUME_ENGAGEMENT,
                DEFAULT_MIN_VOLUME,
                DEFAULT_MAX_VOLUME);
        return new FactionMusicProfile(key, false, Collections.singletonList(baseLayer));
    }

    private static synchronized void parseMergedConfigJsonObject(Object root) throws Throwable {
        if (root == null) return;
        systemEnabled = (boolean) mhJsonObjOptBoolean.invoke(root, "enabled", true);
        float defThreshold = (float) (double) mhJsonObjOptDouble.invoke(root, "defaultActivationThreshold", (double) DEFAULT_ACTIVATION_THRESHOLD);
        float defFull = (float) (double) mhJsonObjOptDouble.invoke(root, "defaultFullVolumeEngagement", (double) DEFAULT_FULL_VOLUME_ENGAGEMENT);
        float defMinVol = (float) (double) mhJsonObjOptDouble.invoke(root, "defaultMinVolume", (double) DEFAULT_MIN_VOLUME);
        float defMaxVol = (float) (double) mhJsonObjOptDouble.invoke(root, "defaultMaxVolume", (double) DEFAULT_MAX_VOLUME);
        fadeInSpeedPerSec = (float) (double) mhJsonObjOptDouble.invoke(root, "fadeInSpeedPerSec", (double) DEFAULT_FADE_IN_SPEED);
        fadeOutSpeedPerSec = (float) (double) mhJsonObjOptDouble.invoke(root, "fadeOutSpeedPerSec", (double) DEFAULT_FADE_OUT_SPEED);

        Object defObj = mhJsonObjOptJsonObject.invoke(root, "defaultProfile");
        if (defObj != null) {
            FactionMusicProfile parsedDef = parseProfileObject("default", defObj, defThreshold, defFull, defMinVol, defMaxVol);
            if (parsedDef != null) {
                defaultProfile = parsedDef;
            }
        }

        Object factionsObj = mhJsonObjOptJsonObject.invoke(root, "factions");
        if (factionsObj != null) {
            Object namesArr = mhJsonObjNames.invoke(factionsObj);
            if (namesArr != null) {
                int len = (int) mhJsonArrLength.invoke(namesArr);
                for (int i = 0; i < len; i++) {
                    String fKey = (String) mhJsonArrOptString.invoke(namesArr, i, null);
                    if (fKey == null || fKey.startsWith("#")) continue;
                    Object fObj = mhJsonObjOptJsonObject.invoke(factionsObj, fKey);
                    if (fObj == null) continue;
                    FactionMusicProfile parsed = parseProfileObject(fKey, fObj, defThreshold, defFull, defMinVol, defMaxVol);
                    if (parsed != null) {
                        FACTION_PROFILES.put(fKey.trim().toLowerCase(Locale.ROOT), parsed);
                    }
                }
            }
        }
    }

    private static FactionMusicProfile parseProfileObject(
            String key,
            Object obj,
            float defThreshold,
            float defFull,
            float defMinVol,
            float defMaxVol) throws Throwable {
        boolean silent = (boolean) mhJsonObjOptBoolean.invoke(obj, "silent", false);
        if (silent) {
            return new FactionMusicProfile(key, true, Collections.<MusicLayerSpec>emptyList());
        }
        List<MusicLayerSpec> layers = new ArrayList<>();
        Object layersArr = mhJsonObjOptJsonArray.invoke(obj, "layers");
        if (layersArr != null) {
            int nLayers = (int) mhJsonArrLength.invoke(layersArr);
            for (int i = 0; i < nLayers; i++) {
                Object lObj = mhJsonArrOptJsonObject.invoke(layersArr, i);
                if (lObj == null) continue;
                String id = (String) mhJsonObjOptString.invoke(lObj, "id", "layer_" + i);
                List<String> setIds = new ArrayList<>();
                Object setArr = mhJsonObjOptJsonArray.invoke(lObj, "musicSetIds");
                if (setArr != null) {
                    int nSets = (int) mhJsonArrLength.invoke(setArr);
                    for (int j = 0; j < nSets; j++) {
                        String s = (String) mhJsonArrOptString.invoke(setArr, j, null);
                        if (s != null && !s.trim().isEmpty()) setIds.add(s.trim());
                    }
                }
                String singleSet = (String) mhJsonObjOptString.invoke(lObj, "musicSetId", null);
                if (singleSet != null && !singleSet.trim().isEmpty() && !setIds.contains(singleSet.trim())) {
                    setIds.add(singleSet.trim());
                }

                List<String> files = new ArrayList<>();
                Object filesArr = mhJsonObjOptJsonArray.invoke(lObj, "files");
                if (filesArr != null) {
                    int nFiles = (int) mhJsonArrLength.invoke(filesArr);
                    for (int j = 0; j < nFiles; j++) {
                        String f = (String) mhJsonArrOptString.invoke(filesArr, j, null);
                        if (f != null && !f.trim().isEmpty()) files.add(f.trim());
                    }
                }
                String singleFile = (String) mhJsonObjOptString.invoke(lObj, "file", null);
                if (singleFile != null && !singleFile.trim().isEmpty() && !files.contains(singleFile.trim())) {
                    files.add(singleFile.trim());
                }

                String source = (String) mhJsonObjOptString.invoke(lObj, "source", "sounds/music/music.bin");
                boolean randomStart = (boolean) mhJsonObjOptBoolean.invoke(lObj, "randomStart", false);
                float threshold = (float) (double) mhJsonObjOptDouble.invoke(lObj, "activationThreshold", (double) defThreshold);
                float fullEng = (float) (double) mhJsonObjOptDouble.invoke(lObj, "fullVolumeEngagement", (double) defFull);
                float minVol = (float) (double) mhJsonObjOptDouble.invoke(lObj, "minVolume", (double) defMinVol);
                float maxVol = (float) (double) mhJsonObjOptDouble.invoke(lObj, "maxVolume", (double) defMaxVol);

                if (!setIds.isEmpty() || !files.isEmpty()) {
                    layers.add(new MusicLayerSpec(
                            id, setIds, files, source, randomStart, threshold, fullEng, minVol, maxVol));
                }
            }
        }
        if (layers.isEmpty()) {
            return null;
        }
        return new FactionMusicProfile(key, false, layers);
    }

    public static synchronized FactionMusicProfile getResolvedProfileForFaction(String factionKey) {
        ensureConfigLoaded();
        if (factionKey != null) {
            String norm = factionKey.trim().toLowerCase(Locale.ROOT);
            FactionMusicProfile match = FACTION_PROFILES.get(norm);
            if (match != null) return match;
        }
        return defaultProfile;
    }

    // =============================================================================================
    // 1. EXPLICIT DIVISION & FLEET ENGAGEMENT (VIOLENT CONTACT) MEASUREMENT
    // =============================================================================================

    /**
     * Computes the active weapon firing ratio (FP-weighted fraction of able ships in the division
     * currently discharging non-PD combat weapons).
     */
    public static float computeDivisionActiveFiringRatio(DivisionState div) {
        if (div == null) return 0f;
        List<ShipAPI> alive = div.getAliveMembers();
        if (alive.isEmpty()) return 0f;
        float firingWeight = 0f;
        float totalWeight = 0f;
        for (ShipAPI s : alive) {
            if (DivisionState.isShipUnable(s)) continue;
            float w = ShipCapabilityProfile.resolveShipFpStrength(s);
            totalWeight += w;
            boolean firing = false;
            List<WeaponAPI> weapons = s.getAllWeapons();
            if (weapons != null) {
                for (WeaponAPI wp : weapons) {
                    if (wp == null) continue;
                    if (wp.isFiring() && !wp.hasAIHint(WeaponAPI.AIHints.PD_ONLY)) {
                        firing = true;
                        break;
                    }
                }
            }
            if (firing) {
                firingWeight += w;
            }
        }
        return totalWeight > 0f ? clamp01(firingWeight / totalWeight) : 0f;
    }

    /**
     * Explicitly measures a single division's violent contact intensity ({@code 0.0f..1.0f}).
     */
    public static float computeDivisionViolentContactIntensity(DivisionState div, float activeFiringRatio) {
        if (div == null || div.isUnableDivision || !div.isCombatEffective()) {
            return 0f;
        }
        if (div.localEnemyStrength <= 0.5f
                && div.incomingShortDotThreat <= 10f
                && div.incomingAlphaThreat <= 20f
                && activeFiringRatio <= 0.05f) {
            return 0f;
        }

        // 1. Local enemy presence relative to division strength
        float enemyPresence = clamp01(div.localEnemyStrength / Math.max(10f, div.totalFpStrength * 0.60f));
        if (div.incomingShortDotThreat > 35f || activeFiringRatio > 0.25f) {
            enemyPresence = Math.max(enemyPresence, 0.72f);
        }

        // 2. Incoming weapon fire threat (Short-DoT & Alpha) relative to division mass
        float incomingThreatFactor = clamp01(
                (div.incomingShortDotThreat * 1.15f + div.incomingAlphaThreat * 0.45f)
                        / Math.max(85f, div.totalFpStrength * 10.5f));

        // 3. Flux build-up and hull damage strain
        float fluxAndDamageStrain = clamp01(
                div.avgFluxLevel * 1.35f
                        + div.maxMemberFluxLevel * 0.45f
                        + (1f - div.avgHullLevel) * 0.35f);

        // 4. Direct weapon discharge + incoming fire + flux exchange
        float combatViolence = Math.max(
                incomingThreatFactor,
                Math.max(fluxAndDamageStrain, activeFiringRatio * 0.92f));

        return clamp01(enemyPresence * (0.28f + 0.72f * combatViolence));
    }

    /**
     * Updates explicit fleet-wide engagement telemetry on {@code battleGroup} after per-division
     * telemetry has been computed.
     *
     * <p>Weighs each division's violent contact by its FP mass relative to the whole fleet:
     * <ul>
     *   <li>2 frigate divisions (~30 FP) engaging while a 4-ship battleship division (~110 FP) is uncommitted
     *       yields {@code ~0.18-0.21} (below the {@code 0.26} threshold).</li>
     *   <li>4 frigate divisions (~60 FP, 4 divisions in action) OR the 4-ship battleship division engaging
     *       yields {@code >= 0.32-0.85} (crossing the {@code 0.26} threshold).</li>
     * </ul>
     */
    public static void updateBattleGroupEngagementTelemetry(BattleGroupState battleGroup) {
        if (battleGroup == null) return;

        float totalFleetFp = 0f;
        float engagedFp = 0f;
        int engagedDivs = 0;

        for (DivisionState div : battleGroup.divisions) {
            if (div == null || !div.isAbleCombatEffective()) {
                if (div != null) {
                    div.violentContactIntensity = 0f;
                    div.engagedFpMass = 0f;
                }
                continue;
            }
            // Service / non-combatant groups do not inflate the combat mass denominator when unengaged
            boolean isNonCombatant = div.archetype == DivisionModel.DivisionArchetype.SERVICE_GROUP;
            float divMass = isNonCombatant ? (div.totalFpStrength * 0.25f) : div.totalFpStrength;
            totalFleetFp += divMass;

            div.engagedFpMass = divMass * clamp01(div.violentContactIntensity);
            engagedFp += div.engagedFpMass;
            if (div.violentContactIntensity >= 0.30f) {
                engagedDivs++;
            }
        }

        battleGroup.engagedFpMass = engagedFp;
        battleGroup.engagedDivisionCount = engagedDivs;
        battleGroup.engagedMassFraction = (totalFleetFp > 0f) ? clamp01(engagedFp / totalFleetFp) : 0f;

        // Multi-division commitment bonus when >= 4 distinct divisions are simultaneously in violent contact
        // so 4 frigate divisions engaging together cross the 0.26 threshold even if heavy battleships are in rear reserve,
        // while 1-2 frigate divisions engaging alone stay strictly weighted by their small fraction of fleet mass.
        float multiDivCommitmentBonus = 0f;
        if (engagedDivs >= 4) {
            multiDivCommitmentBonus = Math.min(0.14f, 0.06f + (engagedFp / 80f) * 0.08f);
        } else if (engagedDivs == 3 && engagedFp >= 45f) {
            multiDivCommitmentBonus = 0.04f;
        }

        float rawEngagement = clamp01(battleGroup.engagedMassFraction + multiDivCommitmentBonus);
        battleGroup.fleetEngagementLevel = rawEngagement;

        // Smooth engagement level: responsive rise when violence erupts, gradual decay during lulls
        float prev = battleGroup.smoothedEngagementLevel;
        if (rawEngagement >= prev) {
            battleGroup.smoothedEngagementLevel = prev + (rawEngagement - prev) * 0.42f;
        } else {
            battleGroup.smoothedEngagementLevel = prev + (rawEngagement - prev) * 0.18f;
        }
        battleGroup.smoothedEngagementLevel = clamp01(battleGroup.smoothedEngagementLevel);

        battleGroup.mainEffortEngaged = battleGroup.mainEffortDivision != null
                && battleGroup.mainEffortDivision.violentContactIntensity >= 0.30f;
    }

    // =============================================================================================
    // 2. ENEMY FACTION & TRAITOR BOUNTY FLEET RESOLUTION
    // =============================================================================================

    /**
     * Resolves the enemy music faction key for the current battle, handling standard factions as well
     * as Hegemony / Persean League traitor or deserter bounty fleets, Remnant/Omega, Threat, Abyssal, and Derelict.
     */
    public static String resolveEnemyMusicFactionKey(CombatEngineAPI engine, BattleGroupState enemyGroup) {
        String rawFactionId = null;
        String fleetName = "";
        String commanderFactionId = null;

        if (engine != null && engine.getContext() != null) {
            try {
                CampaignFleetAPI otherFleet = engine.getContext().getOtherFleet();
                if (otherFleet != null) {
                    if (otherFleet.getFaction() != null && otherFleet.getFaction().getId() != null) {
                        rawFactionId = otherFleet.getFaction().getId().trim().toLowerCase(Locale.ROOT);
                    }
                    String n1 = otherFleet.getName() != null ? otherFleet.getName() : "";
                    String n2 = otherFleet.getFullName() != null ? otherFleet.getFullName() : "";
                    fleetName = (n1 + " " + n2).toLowerCase(Locale.ROOT);
                    if (otherFleet.getCommander() != null
                            && otherFleet.getCommander().getFaction() != null
                            && otherFleet.getCommander().getFaction().getId() != null) {
                        commanderFactionId = otherFleet.getCommander().getFaction().getId().trim().toLowerCase(Locale.ROOT);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if ((rawFactionId == null || rawFactionId.isEmpty() || "independent".equals(rawFactionId))
                && enemyGroup != null && enemyGroup.factionId != null && !enemyGroup.factionId.isEmpty()) {
            rawFactionId = enemyGroup.factionId.trim().toLowerCase(Locale.ROOT);
        }
        if ((rawFactionId == null || rawFactionId.isEmpty()) && engine != null) {
            rawFactionId = DivisionAutoFormer.resolveFactionIdForCombatShips(engine.getShips(), engine, 1);
            if (rawFactionId != null) {
                rawFactionId = rawFactionId.trim().toLowerCase(Locale.ROOT);
            }
        }
        if (rawFactionId == null) {
            rawFactionId = "default";
        }

        // Check fleet name / commander / hull composition for Hegemony or Persean League Traitor / Deserter Bounty Fleets
        if (fleetName.contains("hegemony") || fleetName.contains("xiv") || "hegemony".equals(commanderFactionId)) {
            return "hegemony";
        }
        if (fleetName.contains("persean") || fleetName.contains("league") || "persean".equals(commanderFactionId)) {
            return "persean";
        }

        boolean looksLikeTraitorOrBounty = fleetName.contains("traitor")
                || fleetName.contains("deserter")
                || fleetName.contains("renegade")
                || fleetName.contains("mutineer")
                || fleetName.contains("bounty")
                || "mercenary".equals(rawFactionId)
                || "independent".equals(rawFactionId);

        if (engine != null) {
            int threatHulls = 0;
            int abyssalHulls = 0;
            int remnantHulls = 0;
            int derelictHulls = 0;
            int hegemonyXivHulls = 0;
            int perseanHulls = 0;
            int totalEnemyHulls = 0;

            for (ShipAPI s : engine.getShips()) {
                if (s == null || s.getOwner() != 1 || s.isFighter()) continue;
                totalEnemyHulls++;
                String hullId = (s.getHullSpec() != null && s.getHullSpec().getHullId() != null)
                        ? s.getHullSpec().getHullId().toLowerCase(Locale.ROOT)
                        : "";
                String mfr = (s.getHullSpec() != null && s.getHullSpec().getManufacturer() != null)
                        ? s.getHullSpec().getManufacturer().toLowerCase(Locale.ROOT)
                        : "";
                if (hullId.contains("threat") || mfr.contains("threat")) {
                    threatHulls++;
                } else if (hullId.contains("dweller") || hullId.contains("abyssal") || mfr.contains("abyssal") || mfr.contains("shroud")) {
                    abyssalHulls++;
                } else if (hullId.contains("remnant") || hullId.contains("omega") || hullId.contains("radiant")
                        || hullId.contains("brilliant") || hullId.contains("nova") || hullId.contains("fulgent")
                        || hullId.contains("scintilla") || hullId.contains("glimmer") || hullId.contains("lumen")
                        || hullId.contains("tesseract") || mfr.contains("remnant")) {
                    remnantHulls++;
                } else if (hullId.contains("derelict") || hullId.contains("guardian") || hullId.contains("rampart")
                        || hullId.contains("bastillon") || hullId.contains("berserker") || hullId.contains("warden")
                        || hullId.contains("sentry") || hullId.contains("picket") || mfr.contains("explorarium")) {
                    derelictHulls++;
                } else if (hullId.endsWith("_xiv") || hullId.contains("_hegemony")) {
                    hegemonyXivHulls++;
                } else if (hullId.equals("pegasus") || hullId.equals("champion") || hullId.equals("gryphon")
                        || hullId.equals("manticore") || hullId.equals("vigilance")) {
                    perseanHulls++;
                }
            }

            if (totalEnemyHulls > 0) {
                if (threatHulls * 2 >= totalEnemyHulls) return "threat";
                if (abyssalHulls * 2 >= totalEnemyHulls) return "dweller";
                if (remnantHulls * 2 >= totalEnemyHulls && ("default".equals(rawFactionId) || "independent".equals(rawFactionId))) {
                    return "remnant";
                }
                if (derelictHulls * 2 >= totalEnemyHulls && ("default".equals(rawFactionId) || "independent".equals(rawFactionId))) {
                    return "derelict";
                }
                if (hegemonyXivHulls >= 2 || (looksLikeTraitorOrBounty && hegemonyXivHulls >= 1)) {
                    return "hegemony";
                }
                if (looksLikeTraitorOrBounty && perseanHulls * 2 >= totalEnemyHulls && perseanHulls >= 2) {
                    return "persean";
                }
            }
        }

        ensureConfigLoaded();
        if (FACTION_PROFILES.containsKey(rawFactionId)) {
            return rawFactionId;
        }
        if (rawFactionId.contains("hegemony")) return "hegemony";
        if (rawFactionId.contains("persean") || rawFactionId.contains("league")) return "persean";
        if (rawFactionId.contains("luddic") || rawFactionId.contains("luddite") || rawFactionId.contains("knights_of_ludd")) {
            return "luddic_church";
        }
        if (rawFactionId.contains("pirate")) return "pirates";
        if (rawFactionId.contains("tritach")) return "tritachyon";
        if (rawFactionId.contains("remnant") || rawFactionId.contains("omega")) return "remnant";
        if (rawFactionId.contains("threat")) return "threat";
        if (rawFactionId.contains("dweller") || rawFactionId.contains("abyssal")) return "dweller";
        if (rawFactionId.contains("derelict")) return "derelict";

        return rawFactionId;
    }

    // =============================================================================================
    // 3. RUNTIME AUDIO CONTROLLER (COMBAT LOOP & MULTI-LAYER STREAMING)
    // =============================================================================================

    /**
     * Advances the dynamic battle music controller every frame during real combat.
     */
    public void advance(
            CombatEngineAPI engine,
            BattleGroupState playerSideView,
            BattleGroupState enemyGroup,
             float amount) {
        ensureConfigLoaded();
        if (!systemEnabled || engine == null) return;

        if (engine.isCombatOver()) {
            if (!battleSessionCleanedUp) {
                cleanupBattleMusicSession();
            }
            return;
        }

        if (!battleSessionInitialized && (enemyGroup != null && !enemyGroup.divisions.isEmpty() || !engine.getShips().isEmpty())) {
            initializeBattleMusicSession(engine, enemyGroup);
        }
        if (!battleSessionInitialized || activeProfile == null) {
            return;
        }

        // Keep default combat ambience suspended while our controller is managing combat audio
        try {
            SoundPlayerAPI sp = Global.getSoundPlayer();
            if (sp != null) {
                sp.setSuspendDefaultMusicPlayback(true);
                globalCombatMusicSuspended = true;
            }
        } catch (Throwable ignored) {
        }

        // If this faction profile is silent (e.g. Threat, Abyssal), ensure nothing plays
        if (activeProfile.silent) {
            stopNativeMusicPlaybackIfVanillaAmbience();
            return;
        }

        // Effective engagement level is driven primarily by the player's fleet engagement level (weighted by player fleet mass)
        float playerEngagement = (playerSideView != null) ? playerSideView.smoothedEngagementLevel : 0f;
        float enemyEngagement = (enemyGroup != null) ? enemyGroup.smoothedEngagementLevel : 0f;
        if (playerSideView != null && !playerSideView.divisions.isEmpty()) {
            currentBattleEngagementLevel = playerEngagement;
        } else {
            currentBattleEngagementLevel = Math.max(playerEngagement, enemyEngagement);
        }

        float dt = Math.max(0f, Math.min(0.25f, amount));
        float masterMusicVol = getMasterMusicVolumeSetting();

        for (int i = 0; i < activeLayers.size(); i++) {
            ActiveLayerRuntime layer = activeLayers.get(i);
            if (!layer.triggered && currentBattleEngagementLevel >= layer.spec.activationThreshold) {
                layer.triggered = true;
                layer.currentVolume = layer.spec.minVolume;
                startLayerPlayback(layer);
            }

            if (!layer.triggered) {
                // Silent until engagement threshold is met
                if (layer.layerIndex == 0) {
                    stopNativeMusicPlaybackIfVanillaAmbience();
                }
                continue;
            }

            layer.targetVolume = layer.spec.computeTargetVolume(currentBattleEngagementLevel, layer.triggered);
            if (layer.currentVolume < layer.targetVolume) {
                layer.currentVolume = Math.min(layer.targetVolume, layer.currentVolume + fadeInSpeedPerSec * dt);
            } else if (layer.currentVolume > layer.targetVolume) {
                layer.currentVolume = Math.max(layer.targetVolume, layer.currentVolume - fadeOutSpeedPerSec * dt);
            }

            applyLayerVolume(layer, masterMusicVol);
        }
    }

    private void initializeBattleMusicSession(CombatEngineAPI engine, BattleGroupState enemyGroup) {
        battleSessionInitialized = true;
        battleSessionCleanedUp = false;
        activeEnemyFactionKey = resolveEnemyMusicFactionKey(engine, enemyGroup);
        activeProfile = getResolvedProfileForFaction(activeEnemyFactionKey);
        activeLayers.clear();

        if (activeProfile != null && !activeProfile.silent) {
            for (int i = 0; i < activeProfile.layers.size(); i++) {
                activeLayers.add(new ActiveLayerRuntime(activeProfile.layers.get(i), i, rng));
            }
        }

        // Immediately suspend vanilla battle_ambience_01.ogg so combat starts silent until engagement threshold is met
        try {
            SoundPlayerAPI sp = Global.getSoundPlayer();
            if (sp != null) {
                sp.setSuspendDefaultMusicPlayback(true);
                globalCombatMusicSuspended = true;
            }
        } catch (Throwable ignored) {
        }
        stopCurrentNativeMusic();
        setNativeDynamicVolumeMultiplier(0f);

        LOG.info(String.format(
                "[DivisionAI] Dynamic Battle Music initialized: enemyFaction=%s, silent=%b, layers=%d",
                activeEnemyFactionKey,
                activeProfile != null && activeProfile.silent,
                activeLayers.size()));
    }

    private void startLayerPlayback(ActiveLayerRuntime layer) {
        if (layer == null || layer.playbackStarted) return;
        layer.playbackStarted = true;

        if (layer.layerIndex == 0) {
            // Primary layer: try SoundPlayerAPI.playCustomMusic first if musicSetId is registered in sounds.json,
            // with direct sound.O stream creation as automatic fallback!
            boolean startedViaSoundPlayer = false;
            setNativeDynamicVolumeMultiplier(layer.currentVolume);
            if (layer.chosenMusicSetId != null) {
                try {
                    SoundPlayerAPI sp = Global.getSoundPlayer();
                    if (sp != null) {
                        sp.playCustomMusic(0, 1, layer.chosenMusicSetId, true);
                        String curId = sp.getCurrentMusicId();
                        if (curId != null && !curId.isEmpty() && !"nothing".equalsIgnoreCase(curId)) {
                            startedViaSoundPlayer = true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (!startedViaSoundPlayer && !layer.chosenFiles.isEmpty()) {
                layer.standaloneStreamPlayer = createStandaloneStreamPlayer(
                        layer.chosenFiles,
                        layer.spec.source,
                        layer.spec.randomStart);
            }
        } else {
            // Additional simultaneous layers (Layer 1..N) use dedicated sound.O OpenAL streaming sources
            if (!layer.chosenFiles.isEmpty()) {
                layer.standaloneStreamPlayer = createStandaloneStreamPlayer(
                        layer.chosenFiles,
                        layer.spec.source,
                        layer.spec.randomStart);
            }
        }

        LOG.info(String.format(
                "[DivisionAI] Dynamic Battle Music layer '%s' triggered at engagement=%.2f (threshold=%.2f, set=%s, files=%s)",
                layer.spec.id,
                currentBattleEngagementLevel,
                layer.spec.activationThreshold,
                layer.chosenMusicSetId,
                layer.chosenFiles));
    }

    private void applyLayerVolume(ActiveLayerRuntime layer, float masterMusicVol) {
        if (layer == null || !layer.playbackStarted) return;
        float clampedVol = clamp01(layer.currentVolume);
        if (layer.standaloneStreamPlayer != null) {
            advanceStandaloneStreamPlayer(layer.standaloneStreamPlayer, masterMusicVol * clampedVol);
        } else if (layer.layerIndex == 0) {
            setNativeDynamicVolumeMultiplier(clampedVol);
        }
    }

    public void cleanupBattleMusicSession() {
        battleSessionCleanedUp = true;
        for (ActiveLayerRuntime layer : activeLayers) {
            if (layer.standaloneStreamPlayer != null) {
                stopStandaloneStreamPlayer(layer.standaloneStreamPlayer);
                layer.standaloneStreamPlayer = null;
            }
        }
        activeLayers.clear();
        setNativeDynamicVolumeMultiplier(1.0f);
        try {
            SoundPlayerAPI sp = Global.getSoundPlayer();
            if (sp != null) {
                sp.setSuspendDefaultMusicPlayback(false);
            }
        } catch (Throwable ignored) {
        }
        globalCombatMusicSuspended = false;
    }

    /**
     * Called from campaign script advance to guarantee that if the player exits combat abruptly,
     * default campaign music playback and full volume multiplier are immediately restored.
     */
    public static void ensureCleanedUpOutsideCombat() {
        if (!globalCombatMusicSuspended) return;
        try {
            if (Global.getCurrentState() != GameState.COMBAT) {
                setNativeDynamicVolumeMultiplier(1.0f);
                SoundPlayerAPI sp = Global.getSoundPlayer();
                if (sp != null) {
                    sp.setSuspendDefaultMusicPlayback(false);
                }
                globalCombatMusicSuspended = false;
            }
        } catch (Throwable ignored) {
        }
    }

    public float getCurrentBattleEngagementLevel() {
        return currentBattleEngagementLevel;
    }

    public String getActiveEnemyFactionKey() {
        return activeEnemyFactionKey;
    }

    public FactionMusicProfile getActiveProfile() {
        return activeProfile;
    }

    public boolean isAnyLayerTriggered() {
        for (ActiveLayerRuntime l : activeLayers) {
            if (l.triggered) return true;
        }
        return false;
    }

    public float getPrimaryLayerVolume() {
        if (activeLayers.isEmpty()) return 0f;
        return activeLayers.get(0).currentVolume;
    }

    // =============================================================================================
    // 4. NATIVE OPENAL STREAMING BRIDGE (MethodHandles, Zero java.lang.reflect)
    // =============================================================================================

    private static synchronized void ensureJsonHandlesInitialized() {
        if (mhGetMergedJsonForMod != null) return;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            ClassLoader cl = Global.class.getClassLoader();
            Class<?> jsonObjClass = Class.forName("org.json.JSONObject", false, cl);
            Class<?> jsonArrClass = Class.forName("org.json.JSONArray", false, cl);

            mhGetMergedJsonForMod = lookup.findVirtual(
                    SettingsAPI.class,
                    "getMergedJSONForMod",
                    MethodType.methodType(jsonObjClass, String.class, String.class));
            mhJsonObjFromStringCtor = lookup.findConstructor(
                    jsonObjClass,
                    MethodType.methodType(void.class, String.class));
            mhJsonObjOptBoolean = lookup.findVirtual(
                    jsonObjClass,
                    "optBoolean",
                    MethodType.methodType(boolean.class, String.class, boolean.class));
            mhJsonObjOptDouble = lookup.findVirtual(
                    jsonObjClass,
                    "optDouble",
                    MethodType.methodType(double.class, String.class, double.class));
            mhJsonObjOptString = lookup.findVirtual(
                    jsonObjClass,
                    "optString",
                    MethodType.methodType(String.class, String.class, String.class));
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
            mhJsonArrOptJsonObject = lookup.findVirtual(
                    jsonArrClass,
                    "optJSONObject",
                    MethodType.methodType(jsonObjClass, int.class));
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void ensureAudioHandlesInitialized() {
        if (audioHandlesInitialized) return;
        audioHandlesInitialized = true;
        ensureJsonHandlesInitialized();
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            ClassLoader cl = Global.class.getClassLoader();
            Class<?> jsonObjClass = Class.forName("org.json.JSONObject", false, cl);
            Class<?> soundHClass = Class.forName("sound.H", false, cl);
            Class<?> soundOClass = Class.forName("sound.O", false, cl);
            Class<?> streamDescClass = Class.forName("com.fs.starfarer.loading.int", false, cl);

            // sound.H.?00000() -> singleton sound.H
            mhSoundHGetInstance = lookup.findStatic(
                    soundHClass, "\u003f00000", MethodType.methodType(soundHClass));
            // sound.H.?00000() -> float masterMusicVolume (0..1)
            mhSoundHGetMasterMusicVol = lookup.findVirtual(
                    soundHClass, "\u003f00000", MethodType.methodType(float.class));
            // sound.H.o00000(float) -> sets sound.H.class dynamic volume multiplier (0..1)
            mhSoundHSetDynamicVolMult = lookup.findVirtual(
                    soundHClass, "o00000", MethodType.methodType(void.class, float.class));
            // sound.H.return() -> stops current music stream cleanly
            mhSoundHStopCurrent = lookup.findVirtual(
                    soundHClass, "return", MethodType.methodType(void.class));

            // com.fs.starfarer.loading.int(JSONObject) -> stream descriptor
            mhStreamDescriptorCtor = lookup.findConstructor(
                    streamDescClass, MethodType.methodType(void.class, jsonObjClass));
            mhStreamDescriptorInit = lookup.findVirtual(
                    streamDescClass, "o00000", MethodType.methodType(void.class));
            mhStreamDescriptorCreatePlayer = lookup.findVirtual(
                    streamDescClass, "return", MethodType.methodType(soundOClass));

            // sound.O methods for standalone multi-layer streaming
            mhSoundOSetLoop = lookup.findVirtual(
                    soundOClass, "super", MethodType.methodType(void.class, boolean.class));
            mhSoundOStartPlay = lookup.findVirtual(
                    soundOClass, "\u003f00000", MethodType.methodType(void.class, boolean.class));
            mhSoundOAdvanceWithVol = lookup.findVirtual(
                    soundOClass, "String", MethodType.methodType(void.class, float.class));
            mhSoundOStopAndRelease = lookup.findVirtual(
                    soundOClass, "if", MethodType.methodType(void.class));
        } catch (Throwable ignored) {
            // Safe no-op in headless test environments where fs.sound_obf.jar is not loaded
        }
    }

    private static float getMasterMusicVolumeSetting() {
        ensureAudioHandlesInitialized();
        if (mhSoundHGetInstance == null || mhSoundHGetMasterMusicVol == null) return 0.8f;
        try {
            Object soundH = mhSoundHGetInstance.invoke();
            if (soundH != null) {
                return (float) mhSoundHGetMasterMusicVol.invoke(soundH);
            }
        } catch (Throwable ignored) {
        }
        return 0.8f;
    }

    private static void setNativeDynamicVolumeMultiplier(float mult) {
        ensureAudioHandlesInitialized();
        if (mhSoundHGetInstance == null || mhSoundHSetDynamicVolMult == null) return;
        try {
            Object soundH = mhSoundHGetInstance.invoke();
            if (soundH != null) {
                mhSoundHSetDynamicVolMult.invoke(soundH, clamp01(mult));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void stopCurrentNativeMusic() {
        ensureAudioHandlesInitialized();
        if (mhSoundHGetInstance == null || mhSoundHStopCurrent == null) return;
        try {
            Object soundH = mhSoundHGetInstance.invoke();
            if (soundH != null) {
                mhSoundHStopCurrent.invoke(soundH);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void stopNativeMusicPlaybackIfVanillaAmbience() {
        try {
            SoundPlayerAPI sp = Global.getSoundPlayer();
            if (sp != null) {
                String cur = sp.getCurrentMusicId();
                if (cur != null && cur.toLowerCase(Locale.ROOT).contains("battle_ambience")) {
                    stopCurrentNativeMusic();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object createStandaloneStreamPlayer(List<String> files, String source, boolean randomStart) {
        ensureAudioHandlesInitialized();
        if (mhJsonObjFromStringCtor == null
                || mhStreamDescriptorCtor == null
                || mhStreamDescriptorInit == null
                || mhStreamDescriptorCreatePlayer == null
                || files == null
                || files.isEmpty()) {
            return null;
        }
        try {
            String src = source != null ? source : "sounds/music/music.bin";
            StringBuilder sb = new StringBuilder();
            sb.append("{\"source\":\"").append(src).append("\",\"volume\":0.85,");
            if (files.size() == 1) {
                sb.append("\"file\":\"").append(files.get(0)).append("\"}");
            } else {
                sb.append("\"files\":[");
                for (int i = 0; i < files.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(files.get(i)).append("\"");
                }
                sb.append("],\"randomStart\":").append(randomStart).append("}");
            }
            Object descJson = mhJsonObjFromStringCtor.invoke(sb.toString());
            Object desc = mhStreamDescriptorCtor.invoke(descJson);
            mhStreamDescriptorInit.invoke(desc);
            Object player = mhStreamDescriptorCreatePlayer.invoke(desc);
            if (player != null) {
                mhSoundOSetLoop.invoke(player, true);
                mhSoundOStartPlay.invoke(player, true);
            }
            return player;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void advanceStandaloneStreamPlayer(Object player, float effectiveGain) {
        if (player == null || mhSoundOAdvanceWithVol == null) return;
        try {
            mhSoundOAdvanceWithVol.invoke(player, clamp01(effectiveGain));
        } catch (Throwable ignored) {
        }
    }

    private static void stopStandaloneStreamPlayer(Object player) {
        if (player == null || mhSoundOStopAndRelease == null) return;
        try {
            mhSoundOStopAndRelease.invoke(player);
        } catch (Throwable ignored) {
        }
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
