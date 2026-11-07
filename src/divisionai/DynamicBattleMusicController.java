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
    public static final String AMBIENCE_LAYER_ID = "ambience_base";
    public static final String AMBIENCE_MUSIC_SET_ID = "divisionai_battle_ambience";
    public static final String AMBIENCE_FILE = "battle_ambience_01.ogg";

    public static final float DEFAULT_ACTIVATION_THRESHOLD = 0.26f;
    public static final float DEFAULT_FULL_VOLUME_ENGAGEMENT = 0.72f;
    public static final float DEFAULT_MIN_VOLUME = 0.24f;
    public static final float DEFAULT_MAX_VOLUME = 1.00f;
    public static final float DEFAULT_FADE_IN_SPEED = 0.38f;
    public static final float DEFAULT_FADE_OUT_SPEED = 0.22f;
    public static final float DEFAULT_PAUSE_FADE_OUT_SEC = 0.35f;
    public static final float DEFAULT_PAUSE_FADE_IN_SEC = 0.40f;

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
        public final boolean alwaysOn;
        public final boolean fadeOnPause;
        public final float pauseFadeOutSec;
        public final float pauseFadeInSec;

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
            this(
                    id,
                    musicSetIds,
                    files,
                    source,
                    randomStart,
                    activationThreshold,
                    fullVolumeEngagement,
                    minVolume,
                    maxVolume,
                    isAmbienceLayerIdOrFiles(id, files),
                    !isAmbienceLayerIdOrFiles(id, files),
                    DEFAULT_PAUSE_FADE_OUT_SEC,
                    DEFAULT_PAUSE_FADE_IN_SEC);
        }

        public MusicLayerSpec(
                String id,
                List<String> musicSetIds,
                List<String> files,
                String source,
                boolean randomStart,
                float activationThreshold,
                float fullVolumeEngagement,
                float minVolume,
                float maxVolume,
                boolean alwaysOn,
                boolean fadeOnPause,
                float pauseFadeOutSec,
                float pauseFadeInSec) {
            this.id = (id != null && !id.isEmpty()) ? id : "base";
            this.musicSetIds = musicSetIds != null
                    ? Collections.unmodifiableList(new ArrayList<>(musicSetIds))
                    : Collections.<String>emptyList();
            this.files = files != null
                    ? Collections.unmodifiableList(new ArrayList<>(files))
                    : Collections.<String>emptyList();
            this.source = (source != null && !source.isEmpty()) ? source : "sounds/music/music.bin";
            this.randomStart = randomStart;
            this.alwaysOn = alwaysOn;
            this.fadeOnPause = fadeOnPause;
            this.pauseFadeOutSec = Math.max(0.05f, pauseFadeOutSec > 0f ? pauseFadeOutSec : DEFAULT_PAUSE_FADE_OUT_SEC);
            this.pauseFadeInSec = Math.max(0.05f, pauseFadeInSec > 0f ? pauseFadeInSec : DEFAULT_PAUSE_FADE_IN_SEC);
            this.activationThreshold = alwaysOn ? 0.0f : clamp01(activationThreshold);
            this.fullVolumeEngagement = Math.max(this.activationThreshold + 0.05f, clamp01(fullVolumeEngagement));
            this.minVolume = clamp01(minVolume);
            this.maxVolume = Math.max(this.minVolume, clamp01(maxVolume));
        }

        private static boolean isAmbienceLayerIdOrFiles(String id, List<String> files) {
            if (id != null && (AMBIENCE_LAYER_ID.equalsIgnoreCase(id.trim()) || id.toLowerCase(Locale.ROOT).contains("ambience"))) {
                return true;
            }
            if (files != null) {
                for (String f : files) {
                    if (f != null && f.toLowerCase(Locale.ROOT).contains("battle_ambience")) {
                        return true;
                    }
                }
            }
            return false;
        }

        public boolean isAmbienceBottomLayer() {
            return alwaysOn || isAmbienceLayerIdOrFiles(id, files);
        }

        /**
         * Computes the target layer volume for a given fleet engagement level once the layer has been triggered.
         */
        public float computeTargetVolume(float engagementLevel, boolean alreadyTriggered) {
            if (alwaysOn) {
                float span = Math.max(0.05f, fullVolumeEngagement - activationThreshold);
                float t = clamp01((engagementLevel - activationThreshold) / span);
                return minVolume + t * (maxVolume - minVolume);
            }
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

    public static MusicLayerSpec createAmbienceBottomLayerSpec() {
        return new MusicLayerSpec(
                AMBIENCE_LAYER_ID,
                Collections.singletonList(AMBIENCE_MUSIC_SET_ID),
                Collections.singletonList(AMBIENCE_FILE),
                "sounds/music/music.bin",
                false,
                0.0f,
                1.0f,
                0.85f,
                1.00f,
                true,
                false,
                DEFAULT_PAUSE_FADE_OUT_SEC,
                DEFAULT_PAUSE_FADE_IN_SEC);
    }

    /**
     * Complete battle music profile for a faction.
     * Per Task C, {@code battle_ambience_01.ogg} is always present at Layer 0 as the first and bottom layer.
     */
    public static class FactionMusicProfile {
        public final String factionKey;
        public final boolean silent;
        public final List<MusicLayerSpec> layers;

        public FactionMusicProfile(String factionKey, boolean silent, List<MusicLayerSpec> layers) {
            this.factionKey = factionKey != null ? factionKey.toLowerCase(Locale.ROOT) : "default";
            this.silent = silent;
            List<MusicLayerSpec> merged = new ArrayList<>();
            boolean hasAmbienceFirst = (layers != null && !layers.isEmpty() && layers.get(0) != null && layers.get(0).isAmbienceBottomLayer());
            if (!hasAmbienceFirst) {
                merged.add(createAmbienceBottomLayerSpec());
            }
            if (layers != null) {
                for (MusicLayerSpec l : layers) {
                    if (l == null) continue;
                    if (silent && !l.isAmbienceBottomLayer()) continue;
                    merged.add(l);
                }
            }
            this.layers = Collections.unmodifiableList(merged);
        }

        public MusicLayerSpec getAmbienceBottomLayer() {
            return layers.isEmpty() ? null : layers.get(0);
        }

        public MusicLayerSpec getPrimaryBattleTrackLayer() {
            for (MusicLayerSpec l : layers) {
                if (l != null && !l.isAmbienceBottomLayer()) {
                    return l;
                }
            }
            return null;
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
        float pauseFadeFactor = 1.0f;
        boolean pausedInPlace = false;
        Object standaloneStreamPlayer = null; // sound.O instance for independent OpenAL streaming

        float getEffectiveVolume() {
            return clamp01(currentVolume * pauseFadeFactor);
        }

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
    private static float defaultPauseFadeOutSec = DEFAULT_PAUSE_FADE_OUT_SEC;
    private static float defaultPauseFadeInSec = DEFAULT_PAUSE_FADE_IN_SEC;

    // MethodHandles for Starsector's native sound.H, sound.O, AL10, and org.json (zero java.lang.reflect)
    private static boolean audioHandlesInitialized = false;

    private static MethodHandle mhSoundHGetInstance;
    private static MethodHandle mhSoundHGetMasterMusicVol;
    private static MethodHandle mhSoundHSetDynamicVolMult;
    private static MethodHandle mhSoundHStopCurrent;
    private static MethodHandle mhStreamDescriptorCtor;
    private static MethodHandle mhStreamDescriptorInit;
    private static MethodHandle mhStreamDescriptorCreatePlayer;
    private static MethodHandle mhSoundOSetLoop;
    private static MethodHandle mhSoundOFadeIn;
    private static MethodHandle mhSoundOStartPlay;
    private static MethodHandle mhSoundOAdvanceWithVol;
    private static MethodHandle mhSoundOStopAndRelease;
    private static MethodHandle mhSoundOGetSourceId;
    private static MethodHandle mhAlSourcef;
    private static MethodHandle mhAlSourcePause;
    private static MethodHandle mhAlSourcePlay;
    private static MethodHandle mhAlGetError;

    // Track whether any combat music session suspended default music so campaign/title transitions can clean up safely
    private static volatile boolean globalCombatMusicSuspended = false;
    private static volatile DynamicBattleMusicController activeSessionController = null;
    private static volatile CombatEngineAPI activeSessionEngine = null;
    private static volatile Thread stateWatchdogThread = null;

    // Instance state per battle
    private boolean battleSessionInitialized = false;
    private boolean battleSessionCleanedUp = false;
    private boolean yieldedToVanillaDefault = false;
    private long lastAdvanceNanoTime = 0L;
    private String activeEnemyFactionKey = "default";
    private FactionMusicProfile activeProfile = null;
    private final List<ActiveLayerRuntime> activeLayers = new ArrayList<>();
    private float currentBattleEngagementLevel = 0f;
    private final Random rng = new Random();

    static {
        installBuiltInDefaults();
    }



    public static synchronized void ensureConfigLoaded() {
        if (configLoaded) return;
        installBuiltInDefaults();
        try {
            JsonReflectBridge.ensureInitialized();
            if (Global.getSettings() != null) {
                Object rootJson = JsonReflectBridge.loadMergedModJson(CONFIG_PATH, MOD_ID);
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
        MusicLayerSpec ambienceLayer = createAmbienceBottomLayerSpec();
        MusicLayerSpec baseLayer = new MusicLayerSpec(
                "base",
                musicSetIds,
                files,
                "sounds/music/music.bin",
                randomStart,
                DEFAULT_ACTIVATION_THRESHOLD,
                DEFAULT_FULL_VOLUME_ENGAGEMENT,
                DEFAULT_MIN_VOLUME,
                DEFAULT_MAX_VOLUME,
                false,
                true,
                DEFAULT_PAUSE_FADE_OUT_SEC,
                DEFAULT_PAUSE_FADE_IN_SEC);
        return new FactionMusicProfile(key, false, Arrays.asList(ambienceLayer, baseLayer));
    }

    private static synchronized void parseMergedConfigJsonObject(Object root) throws Throwable {
        if (root == null) return;
        systemEnabled = JsonReflectBridge.optBoolean(root, "enabled", true);
        float defThreshold = JsonReflectBridge.optFloat(root, "defaultActivationThreshold", DEFAULT_ACTIVATION_THRESHOLD);
        float defFull = JsonReflectBridge.optFloat(root, "defaultFullVolumeEngagement", DEFAULT_FULL_VOLUME_ENGAGEMENT);
        float defMinVol = JsonReflectBridge.optFloat(root, "defaultMinVolume", DEFAULT_MIN_VOLUME);
        float defMaxVol = JsonReflectBridge.optFloat(root, "defaultMaxVolume", DEFAULT_MAX_VOLUME);
        fadeInSpeedPerSec = JsonReflectBridge.optFloat(root, "fadeInSpeedPerSec", DEFAULT_FADE_IN_SPEED);
        fadeOutSpeedPerSec = JsonReflectBridge.optFloat(root, "fadeOutSpeedPerSec", DEFAULT_FADE_OUT_SPEED);
        defaultPauseFadeOutSec = JsonReflectBridge.optFloat(root, "defaultPauseFadeOutSec", DEFAULT_PAUSE_FADE_OUT_SEC);
        defaultPauseFadeInSec = JsonReflectBridge.optFloat(root, "defaultPauseFadeInSec", DEFAULT_PAUSE_FADE_IN_SEC);

        Object defObj = JsonReflectBridge.optJSONObject(root, "defaultProfile");
        if (defObj != null) {
            FactionMusicProfile parsedDef = parseProfileObject("default", defObj, defThreshold, defFull, defMinVol, defMaxVol);
            if (parsedDef != null) {
                defaultProfile = parsedDef;
            }
        }

        Object factionsObj = JsonReflectBridge.optJSONObject(root, "factions");
        if (factionsObj != null) {
            Object namesArr = JsonReflectBridge.names(factionsObj);
            if (namesArr != null) {
                int len = JsonReflectBridge.arrayLength(namesArr);
                for (int i = 0; i < len; i++) {
                    String fKey = JsonReflectBridge.arrayOptString(namesArr, i, null);
                    if (fKey == null || fKey.startsWith("#")) continue;
                    Object fObj = JsonReflectBridge.optJSONObject(factionsObj, fKey);
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
        boolean silent = JsonReflectBridge.optBoolean(obj, "silent", false);
        List<MusicLayerSpec> layers = new ArrayList<>();
        Object layersArr = JsonReflectBridge.optJSONArray(obj, "layers");
        if (layersArr != null) {
            int nLayers = JsonReflectBridge.arrayLength(layersArr);
            for (int i = 0; i < nLayers; i++) {
                Object lObj = JsonReflectBridge.arrayOptJSONObject(layersArr, i);
                if (lObj == null) continue;
                String id = JsonReflectBridge.optString(lObj, "id", "layer_" + i);
                List<String> setIds = new ArrayList<>();
                Object setArr = JsonReflectBridge.optJSONArray(lObj, "musicSetIds");
                if (setArr != null) {
                    int nSets = JsonReflectBridge.arrayLength(setArr);
                    for (int j = 0; j < nSets; j++) {
                        String s = JsonReflectBridge.arrayOptString(setArr, j, null);
                        if (s != null && !s.trim().isEmpty()) setIds.add(s.trim());
                    }
                }
                String singleSet = JsonReflectBridge.optString(lObj, "musicSetId", null);
                if (singleSet != null && !singleSet.trim().isEmpty() && !setIds.contains(singleSet.trim())) {
                    setIds.add(singleSet.trim());
                }

                List<String> files = new ArrayList<>();
                Object filesArr = JsonReflectBridge.optJSONArray(lObj, "files");
                if (filesArr != null) {
                    int nFiles = JsonReflectBridge.arrayLength(filesArr);
                    for (int j = 0; j < nFiles; j++) {
                        String f = JsonReflectBridge.arrayOptString(filesArr, j, null);
                        if (f != null && !f.trim().isEmpty()) files.add(f.trim());
                    }
                }
                String singleFile = JsonReflectBridge.optString(lObj, "file", null);
                if (singleFile != null && !singleFile.trim().isEmpty() && !files.contains(singleFile.trim())) {
                    files.add(singleFile.trim());
                }

                String source = JsonReflectBridge.optString(lObj, "source", "sounds/music/music.bin");
                boolean randomStart = JsonReflectBridge.optBoolean(lObj, "randomStart", false);
                boolean isAmb = MusicLayerSpec.isAmbienceLayerIdOrFiles(id, files);
                boolean alwaysOn = JsonReflectBridge.optBoolean(lObj, "alwaysOn", isAmb);
                boolean fadeOnPause = JsonReflectBridge.optBoolean(lObj, "fadeOnPause", !alwaysOn);
                float pauseOutSec = JsonReflectBridge.optFloat(lObj, "pauseFadeOutSec", defaultPauseFadeOutSec);
                float pauseInSec = JsonReflectBridge.optFloat(lObj, "pauseFadeInSec", defaultPauseFadeInSec);
                float threshold = JsonReflectBridge.optFloat(lObj, "activationThreshold", alwaysOn ? 0.0f : defThreshold);
                float fullEng = JsonReflectBridge.optFloat(lObj, "fullVolumeEngagement", alwaysOn ? 1.0f : defFull);
                float minVol = JsonReflectBridge.optFloat(lObj, "minVolume", alwaysOn ? 0.85f : defMinVol);
                float maxVol = JsonReflectBridge.optFloat(lObj, "maxVolume", defMaxVol);

                if (!setIds.isEmpty() || !files.isEmpty()) {
                    layers.add(new MusicLayerSpec(
                            id, setIds, files, source, randomStart, threshold, fullEng, minVol, maxVol,
                            alwaysOn, fadeOnPause, pauseOutSec, pauseInSec));
                }
            }
        }
        if (silent) {
            return new FactionMusicProfile(key, true, layers);
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
                    if (wp == null || wp.isDecorative()) continue;
                    if (wp.hasAIHint(WeaponAPI.AIHints.PD_ONLY)) continue;
                    // isFiring() is only true for the discharge frames; missiles, long-cycle cannons, and burst
                    // beams spend most of their engagement cycling, so a weapon on cooldown also counts as in action.
                    if (wp.isFiring()
                            || (wp.getCooldownRemaining() > 0.05f && wp.getCooldown() > 0f)
                            || (wp.getType() == WeaponAPI.WeaponType.MISSILE && wp.getCooldownRemaining() > 0f)) {
                        firing = true;
                        break;
                    }
                }
            }
            if (!firing) {
                firing = hasWingsInAction(s);
            }
            if (firing) {
                firingWeight += w;
            }
        }
        return totalWeight > 0f ? clamp01(firingWeight / totalWeight) : 0f;
    }

    static boolean isCombatOrder(DivisionModel.DivisionIntentType intent) {
        if (intent == null) return false;
        switch (intent) {
            case CLOSE_AND_ENGAGE:
            case PRESS_HOME:
            case FORM_LINE_AND_FIX:
            case HARASSING_MISSILE_ATTACK:
            case TOT_MISSILE_STRIKE:
            case TOT_ALPHA_STRIKE:
            case WORK_FLANK:
            case FLY_FIGHTER_SWEEP:
            case FLY_CYCLIC_STRIKES:
            case LAUNCH_DECKLOAD_STRIKE:
            case LAUNCH_ALPHA_STRIKE:
            case GIVE_GENERAL_CHASE:
                return true;
            default:
                return false;
        }
    }

    /** True when the carrier has launched fighters that are actively discharging weapons or attacking a target. */
    static boolean hasWingsInAction(ShipAPI carrier) {
        try {
            if (carrier == null || carrier.getAllWings() == null) return false;
            for (com.fs.starfarer.api.combat.FighterWingAPI wing : carrier.getAllWings()) {
                if (wing == null) continue;
                List<ShipAPI> members = wing.getWingMembers();
                if (members == null) continue;
                for (ShipAPI f : members) {
                    if (f == null || !f.isAlive()) continue;
                    if (f.getWing() != null && f.getWing().isReturning(f)) continue;
                    List<WeaponAPI> fw = f.getAllWeapons();
                    if (fw == null) continue;
                    for (WeaponAPI w : fw) {
                        if (w != null && !w.isDecorative() && (w.isFiring() || w.getCooldownRemaining() > 0.05f)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
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
        // Long-range gunlines, missile boats, and carriers fight from outside the local evaluation radius: an active
        // combat order with weapons/wings in action is contact even when no enemy hull is inside the local grid.
        if (activeFiringRatio > 0.10f && isCombatOrder(div.currentIntent)) {
            enemyPresence = Math.max(enemyPresence, 0.60f + 0.25f * activeFiringRatio);
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
     * Returns true when both the JSON configuration and the player's Mod Settings / settings.json
     * enable Division AI's dynamic battle music engine. When false, combat music control is yielded
     * to vanilla Starsector default.
     */
    public static boolean isMusicEngineActive() {
        ensureConfigLoaded();
        return systemEnabled && DivisionAISettings.isDynamicBattleMusicEnabled();
    }

    public boolean isYieldedToVanillaDefault() {
        return yieldedToVanillaDefault || !isMusicEngineActive();
    }

    /**
     * Immediately stops all Division AI battle music layers and switches combat music control back
     * to vanilla Starsector default.
     */
    public synchronized void switchControlToVanillaDefault() {
        for (int i = 0; i < activeLayers.size(); i++) {
            ActiveLayerRuntime layer = activeLayers.get(i);
            stopSingleLayerStream(layer, 0);
        }
        activeLayers.clear();
        try {
            SoundPlayerAPI sp = Global.getSoundPlayer();
            if (sp != null) {
                sp.pauseCustomMusic();
                sp.setSuspendDefaultMusicPlayback(false);
            }
        } catch (Throwable ignored) {
        }
        setNativeDynamicVolumeMultiplier(1.0f);
        globalCombatMusicSuspended = false;
        battleSessionInitialized = false;
        battleSessionCleanedUp = false;
        yieldedToVanillaDefault = true;
        if (activeSessionController == this) {
            activeSessionController = null;
            activeSessionEngine = null;
        }
    }

    private float resolveStepDeltaSeconds(float amount) {
        long nowNano = System.nanoTime();
        float wallDt = 0.033f;
        if (lastAdvanceNanoTime > 0L) {
            float elapsed = (nowNano - lastAdvanceNanoTime) * 1e-9f;
            if (elapsed >= 0.004f && elapsed <= 0.25f) {
                wallDt = elapsed;
            }
        }
        lastAdvanceNanoTime = nowNano;
        if (amount > 0f) {
            return Math.max(0.016f, Math.min(0.25f, amount));
        }
        return Math.max(0.016f, Math.min(0.10f, wallDt));
    }

    /**
     * Advances the dynamic battle music controller every frame during real combat (including while paused).
     */
    public void advance(
            CombatEngineAPI engine,
            BattleGroupState playerSideView,
            BattleGroupState enemyGroup,
            float amount) {
        ensureConfigLoaded();
        if (engine == null) return;
        if (!isMusicEngineActive()) {
            if (battleSessionInitialized || globalCombatMusicSuspended) {
                switchControlToVanillaDefault();
            } else {
                yieldedToVanillaDefault = true;
            }
            return;
        }
        yieldedToVanillaDefault = false;

        float dt = resolveStepDeltaSeconds(amount);
        float masterMusicVol = getMasterMusicVolumeSetting();

        if (engine.isCombatOver()) {
            if (!battleSessionCleanedUp) {
                if (!battleSessionInitialized || !isAnyLayerTriggered()) {
                    cleanupBattleMusicSession(1);
                } else {
                    currentBattleEngagementLevel = 0f;
                    float combatEndFadeSpeed = Math.max(0.45f, fadeOutSpeedPerSec * 2.0f);
                    boolean anyStillAudible = false;
                    for (int i = 0; i < activeLayers.size(); i++) {
                        ActiveLayerRuntime layer = activeLayers.get(i);
                        if (!layer.triggered || !layer.playbackStarted) continue;
                        if (layer.pausedInPlace) {
                            resumeLayerStreamInPlace(layer);
                            layer.pausedInPlace = false;
                        }
                        layer.targetVolume = 0f;
                        layer.currentVolume = Math.max(0f, layer.currentVolume - combatEndFadeSpeed * dt);
                        applyLayerVolume(layer, masterMusicVol);
                        if (layer.getEffectiveVolume() > 0.01f) {
                            anyStillAudible = true;
                        }
                    }
                    if (!anyStillAudible) {
                        cleanupBattleMusicSession(0);
                    }
                }
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

        boolean battlePaused = engine.isPaused();

        // Effective engagement level is driven primarily by the player's fleet engagement level (weighted by player fleet mass)
        if (!battlePaused) {
            float playerEngagement = (playerSideView != null) ? playerSideView.smoothedEngagementLevel : 0f;
            float enemyEngagement = (enemyGroup != null) ? enemyGroup.smoothedEngagementLevel : 0f;
            if (playerSideView != null && !playerSideView.divisions.isEmpty()) {
                currentBattleEngagementLevel = playerEngagement;
            } else {
                currentBattleEngagementLevel = Math.max(playerEngagement, enemyEngagement);
            }
        }

        for (int i = 0; i < activeLayers.size(); i++) {
            ActiveLayerRuntime layer = activeLayers.get(i);
            if (!layer.triggered && (layer.spec.alwaysOn || currentBattleEngagementLevel >= layer.spec.activationThreshold)) {
                layer.triggered = true;
                layer.currentVolume = layer.spec.alwaysOn
                        ? layer.spec.minVolume
                        : Math.min(layer.spec.minVolume, 0.04f);
                layer.pauseFadeFactor = (battlePaused && layer.spec.fadeOnPause) ? 0f : 1f;
                startLayerPlayback(layer);
                if (battlePaused && layer.spec.fadeOnPause) {
                    pauseLayerStreamInPlace(layer);
                    layer.pausedInPlace = true;
                }
            }

            if (!layer.triggered) {
                continue;
            }

            if (battlePaused) {
                if (layer.spec.fadeOnPause) {
                    float fadeOutRate = 1.0f / Math.max(0.05f, layer.spec.pauseFadeOutSec);
                    layer.pauseFadeFactor = Math.max(0f, layer.pauseFadeFactor - fadeOutRate * dt);
                    if (layer.pauseFadeFactor <= 0.001f) {
                        layer.pauseFadeFactor = 0f;
                        if (!layer.pausedInPlace) {
                            pauseLayerStreamInPlace(layer);
                            layer.pausedInPlace = true;
                        }
                    } else {
                        applyLayerVolume(layer, masterMusicVol);
                    }
                    continue;
                } else {
                    // Bottom ambience layer (fadeOnPause == false) stays playing at all times, even while paused
                    layer.pauseFadeFactor = 1.0f;
                }
            } else {
                if (layer.pausedInPlace) {
                    resumeLayerStreamInPlace(layer);
                    layer.pausedInPlace = false;
                }
                if (layer.pauseFadeFactor < 1.0f) {
                    float fadeInRate = 1.0f / Math.max(0.05f, layer.spec.pauseFadeInSec);
                    layer.pauseFadeFactor = Math.min(1.0f, layer.pauseFadeFactor + fadeInRate * dt);
                }
            }

            layer.targetVolume = layer.spec.computeTargetVolume(currentBattleEngagementLevel, layer.triggered);
            if (layer.currentVolume < layer.targetVolume) {
                layer.currentVolume = Math.min(layer.targetVolume, layer.currentVolume + fadeInSpeedPerSec * dt);
            } else if (layer.currentVolume > layer.targetVolume) {
                layer.currentVolume = Math.max(layer.targetVolume, layer.currentVolume - fadeOutSpeedPerSec * dt);
            }

            applyLayerVolume(layer, masterMusicVol);

            // For non-alwaysOn battle tracks, if engagement has dropped well below threshold and the layer has
            // faded completely out to 0, stop its stream cleanly so it can re-trigger from the beginning later.
            // Layer 0 (alwaysOn battle_ambience_01.ogg) never deactivates during combat.
            if (!battlePaused
                    && !layer.spec.alwaysOn
                    && currentBattleEngagementLevel < layer.spec.activationThreshold * 0.25f
                    && layer.currentVolume <= 0.006f) {
                stopSingleLayerStream(layer, 0);
                layer.triggered = false;
                layer.playbackStarted = false;
                layer.currentVolume = 0f;
                layer.pauseFadeFactor = 1.0f;
                layer.pausedInPlace = false;
            }
        }
    }

    private void initializeBattleMusicSession(CombatEngineAPI engine, BattleGroupState enemyGroup) {
        if (activeSessionController != null && activeSessionController != this && !activeSessionController.battleSessionCleanedUp) {
            activeSessionController.cleanupBattleMusicSession(0);
        }
        battleSessionInitialized = true;
        battleSessionCleanedUp = false;
        yieldedToVanillaDefault = false;
        activeSessionController = this;
        activeSessionEngine = engine;
        activeEnemyFactionKey = resolveEnemyMusicFactionKey(engine, enemyGroup);
        activeProfile = getResolvedProfileForFaction(activeEnemyFactionKey);
        activeLayers.clear();

        if (activeProfile != null) {
            for (int i = 0; i < activeProfile.layers.size(); i++) {
                MusicLayerSpec spec = activeProfile.layers.get(i);
                if (activeProfile.silent && !spec.isAmbienceBottomLayer()) {
                    continue;
                }
                ActiveLayerRuntime rt = new ActiveLayerRuntime(spec, i, rng);
                activeLayers.add(rt);
            }
        }

        // Suspend vanilla's single music channel so our multi-layered OpenAL engine controls Layer 0 (battle_ambience_01.ogg)
        // and Layer 1..N (faction battle tracks) without channel conflicts
        try {
            SoundPlayerAPI sp = Global.getSoundPlayer();
            if (sp != null) {
                sp.setSuspendDefaultMusicPlayback(true);
                globalCombatMusicSuspended = true;
            }
        } catch (Throwable ignored) {
        }
        stopCurrentNativeMusic(0);
        setNativeDynamicVolumeMultiplier(1.0f);
        ensureStateWatchdogRunning();

        // Immediately start any alwaysOn bottom layer (Layer 0: battle_ambience_01.ogg)
        float masterMusicVol = getMasterMusicVolumeSetting();
        for (int i = 0; i < activeLayers.size(); i++) {
            ActiveLayerRuntime layer = activeLayers.get(i);
            if (layer.spec.alwaysOn) {
                layer.triggered = true;
                layer.currentVolume = layer.spec.minVolume;
                layer.targetVolume = layer.spec.minVolume;
                layer.pauseFadeFactor = 1.0f;
                startLayerPlayback(layer);
                applyLayerVolume(layer, masterMusicVol);
            }
        }

        LOG.info(String.format(
                "[DivisionAI] Dynamic Battle Music initialized: enemyFaction=%s, silent=%b, layers=%d",
                activeEnemyFactionKey,
                activeProfile != null && activeProfile.silent,
                activeLayers.size()));
    }

    private void startLayerPlayback(ActiveLayerRuntime layer) {
        if (layer == null || layer.playbackStarted) return;
        layer.playbackStarted = true;

        // Every layer (Layer 0 ambience_base and Layer 1..N combat tracks) uses its own dedicated sound.O OpenAL
        // streaming source so Layer 0 and Layer 1..N play simultaneously with independent volume and pause/resume!
        if (!layer.chosenFiles.isEmpty()) {
            layer.standaloneStreamPlayer = createStandaloneStreamPlayer(
                    layer.chosenFiles,
                    layer.spec.source,
                    layer.spec.randomStart);
        }

        // Fallback to SoundPlayerAPI.playCustomMusic if standalone stream creation was unavailable
        if (layer.standaloneStreamPlayer == null && layer.chosenMusicSetId != null) {
            setNativeDynamicVolumeMultiplier(layer.getEffectiveVolume());
            try {
                SoundPlayerAPI sp = Global.getSoundPlayer();
                if (sp != null) {
                    sp.playCustomMusic(layer.layerIndex == 0 ? 0 : 1, 1, layer.chosenMusicSetId, true);
                }
            } catch (Throwable ignored) {
            }
        }

        LOG.info(String.format(
                "[DivisionAI] Dynamic Battle Music layer '%s' triggered at engagement=%.2f (threshold=%.2f, alwaysOn=%b, fadeOnPause=%b, streamActive=%b, files=%s)",
                layer.spec.id,
                currentBattleEngagementLevel,
                layer.spec.activationThreshold,
                layer.spec.alwaysOn,
                layer.spec.fadeOnPause,
                layer.standaloneStreamPlayer != null,
                layer.chosenFiles));
    }

    private void applyLayerVolume(ActiveLayerRuntime layer, float masterMusicVol) {
        if (layer == null || !layer.playbackStarted || layer.pausedInPlace) return;
        float clampedVol = layer.getEffectiveVolume();
        if (layer.standaloneStreamPlayer != null) {
            advanceStandaloneStreamPlayer(layer.standaloneStreamPlayer, masterMusicVol * clampedVol);
        } else if (layer.layerIndex == 0) {
            setNativeDynamicVolumeMultiplier(clampedVol);
        }
    }

    private void pauseLayerStreamInPlace(ActiveLayerRuntime layer) {
        if (layer == null || !layer.playbackStarted) return;
        if (layer.standaloneStreamPlayer != null) {
            pauseStandaloneStreamPlayer(layer.standaloneStreamPlayer);
        } else if (layer.layerIndex == 0) {
            setNativeDynamicVolumeMultiplier(0f);
            try {
                SoundPlayerAPI sp = Global.getSoundPlayer();
                if (sp != null) {
                    sp.pauseCustomMusic();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void resumeLayerStreamInPlace(ActiveLayerRuntime layer) {
        if (layer == null || !layer.playbackStarted) return;
        if (layer.standaloneStreamPlayer != null) {
            resumeStandaloneStreamPlayer(layer.standaloneStreamPlayer);
        }
    }

    private void stopSingleLayerStream(ActiveLayerRuntime layer, int fadeOutSec) {
        if (layer == null) return;
        if (layer.standaloneStreamPlayer != null) {
            stopStandaloneStreamPlayer(layer.standaloneStreamPlayer);
            layer.standaloneStreamPlayer = null;
        }
        if (layer.layerIndex == 0 && layer.playbackStarted) {
            try {
                SoundPlayerAPI sp = Global.getSoundPlayer();
                if (sp != null) {
                    if (fadeOutSec > 0) {
                        sp.playCustomMusic(fadeOutSec, 0, null);
                    }
                    sp.pauseCustomMusic();
                }
            } catch (Throwable ignored) {
            }
            stopCurrentNativeMusic(fadeOutSec);
        }
    }

    public void cleanupBattleMusicSession() {
        cleanupBattleMusicSession(1);
    }

    public synchronized void cleanupBattleMusicSession(int fadeOutSec) {
        battleSessionCleanedUp = true;
        for (int i = 0; i < activeLayers.size(); i++) {
            ActiveLayerRuntime layer = activeLayers.get(i);
            stopSingleLayerStream(layer, fadeOutSec);
        }
        activeLayers.clear();
        try {
            SoundPlayerAPI sp = Global.getSoundPlayer();
            if (sp != null) {
                if (fadeOutSec > 0) {
                    sp.playCustomMusic(fadeOutSec, 0, null);
                }
                sp.pauseCustomMusic();
                sp.setSuspendDefaultMusicPlayback(false);
            }
        } catch (Throwable ignored) {
        }
        stopCurrentNativeMusic(fadeOutSec);
        setNativeDynamicVolumeMultiplier(1.0f);
        globalCombatMusicSuspended = false;
        if (activeSessionController == this) {
            activeSessionController = null;
            activeSessionEngine = null;
        }
    }

    /**
     * Called from campaign script advance, Main Menu combat advance, or background watchdog to guarantee
     * that whenever the player exits real combat (to Campaign or Main Menu), any battle music fades out
     * and default music playback + full volume multiplier are immediately restored.
     */
    public static void ensureCleanedUpOutsideCombat() {
        if (!globalCombatMusicSuspended && activeSessionController == null) return;
        try {
            CombatEngineAPI curEngine = Global.getCombatEngine();
            boolean inRealCombat = (Global.getCurrentState() == GameState.COMBAT)
                    && curEngine != null
                    && BattlefieldFirepowerEvaluator.isRealCombatEngine(curEngine)
                    && (activeSessionEngine == null || curEngine == activeSessionEngine);
            if (!inRealCombat) {
                DynamicBattleMusicController ctrl = activeSessionController;
                if (ctrl != null) {
                    ctrl.fadeOutAndCleanupOnStateExit();
                } else {
                    try {
                        SoundPlayerAPI sp = Global.getSoundPlayer();
                        if (sp != null) {
                            sp.playCustomMusic(1, 0, null);
                            sp.pauseCustomMusic();
                            sp.setSuspendDefaultMusicPlayback(false);
                        }
                    } catch (Throwable ignored) {
                    }
                    stopCurrentNativeMusic(1);
                    setNativeDynamicVolumeMultiplier(1.0f);
                    globalCombatMusicSuspended = false;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void fadeOutAndCleanupOnStateExit() {
        if (battleSessionCleanedUp) return;
        float masterMusicVol = getMasterMusicVolumeSetting();
        // Smooth 0.9s volume ramp down if called from the background state watchdog thread
        if (Thread.currentThread() == stateWatchdogThread && isAnyLayerTriggered()) {
            for (int step = 5; step >= 0; step--) {
                float factor = step / 6.0f;
                for (int i = 0; i < activeLayers.size(); i++) {
                    ActiveLayerRuntime layer = activeLayers.get(i);
                    if (layer.triggered && layer.playbackStarted) {
                        layer.currentVolume *= factor;
                        applyLayerVolume(layer, masterMusicVol);
                    }
                }
                try {
                    Thread.sleep(140L);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        cleanupBattleMusicSession(1);
    }

    private static synchronized void ensureStateWatchdogRunning() {
        if (stateWatchdogThread != null && stateWatchdogThread.isAlive()) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (globalCombatMusicSuspended || activeSessionController != null) {
                    try {
                        Thread.sleep(150L);
                        ensureCleanedUpOutsideCombat();
                    } catch (InterruptedException e) {
                        break;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "DivisionAI-BattleMusic-Watchdog");
        t.setDaemon(true);
        stateWatchdogThread = t;
        t.start();
    }

    public float getCurrentBattleEngagementLevel() {
        return currentBattleEngagementLevel;
    }



    public boolean isAnyLayerTriggered() {
        for (ActiveLayerRuntime l : activeLayers) {
            if (l.triggered) return true;
        }
        return false;
    }

    public float getPrimaryLayerVolume() {
        if (activeLayers.isEmpty()) return 0f;
        for (ActiveLayerRuntime l : activeLayers) {
            if (!l.spec.isAmbienceBottomLayer() && l.triggered) {
                return l.getEffectiveVolume();
            }
        }
        return activeLayers.get(0).getEffectiveVolume();
    }

    public float getAmbienceLayerVolume() {
        for (ActiveLayerRuntime l : activeLayers) {
            if (l.spec.isAmbienceBottomLayer()) {
                return l.getEffectiveVolume();
            }
        }
        return 0f;
    }

    public float getBattleTrackLayerVolume() {
        for (ActiveLayerRuntime l : activeLayers) {
            if (!l.spec.isAmbienceBottomLayer()) {
                return l.getEffectiveVolume();
            }
        }
        return 0f;
    }

    public boolean isBattleTrackPausedInPlace() {
        for (ActiveLayerRuntime l : activeLayers) {
            if (!l.spec.isAmbienceBottomLayer()) {
                return l.pausedInPlace;
            }
        }
        return false;
    }

    // =============================================================================================
    // 4. NATIVE OPENAL STREAMING BRIDGE (MethodHandles, Zero java.lang.reflect)
    // =============================================================================================


    private static synchronized void ensureAudioHandlesInitialized() {
        if (audioHandlesInitialized) return;
        audioHandlesInitialized = true;
        JsonReflectBridge.ensureInitialized();
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            ClassLoader cl = Global.class.getClassLoader();
            Class<?> jsonObjClass = Class.forName("org.json.JSONObject", false, cl);
            Class<?> soundHClass = ObfuscatedBytecodeBridge.resolveFirstAvailableClass(cl, "sound.H", "sound.OooO");
            Class<?> soundOClass = ObfuscatedBytecodeBridge.resolveFirstAvailableClass(cl, "sound.O", "sound.oo0O");
            Class<?> streamDescClass = Class.forName("com.fs.starfarer.loading.int", false, cl);

            // sound.H.\u00d200000() -> singleton sound.H
            mhSoundHGetInstance = lookup.findStatic(
                    soundHClass, "\u00d200000", MethodType.methodType(soundHClass));
            // sound.H.\u00d800000() -> float masterMusicVolume (0..1)
            mhSoundHGetMasterMusicVol = lookup.findVirtual(
                    soundHClass, "\u00d800000", MethodType.methodType(float.class));
            // sound.H.o00000(float) -> sets sound.H.class dynamic volume multiplier (0..1)
            mhSoundHSetDynamicVolMult = lookup.findVirtual(
                    soundHClass, "o00000", MethodType.methodType(void.class, float.class));
            // sound.H.Object(int fadeOutSec) -> fades out current music stream over fadeOutSec seconds and clears queue
            mhSoundHStopCurrent = lookup.findVirtual(
                    soundHClass, "Object", MethodType.methodType(void.class, int.class));

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
            // sound.O.\u00d500000(int fadeInSec) -> initializes fade state o.o00000 so internal gain multiplier reaches 1.0f
            mhSoundOFadeIn = lookup.findVirtual(
                    soundOClass, "\u00d500000", MethodType.methodType(void.class, int.class));
            mhSoundOStartPlay = lookup.findVirtual(
                    soundOClass, "\u00d200000", MethodType.methodType(void.class, boolean.class));
            mhSoundOAdvanceWithVol = lookup.findVirtual(
                    soundOClass, "String", MethodType.methodType(void.class, float.class));
            mhSoundOStopAndRelease = lookup.findVirtual(
                    soundOClass, "if", MethodType.methodType(void.class));
            mhSoundOGetSourceId = lookup.findVirtual(
                    soundOClass, "super", MethodType.methodType(int.class));

            Class<?> al10Class = Class.forName("org.lwjgl.openal.AL10", false, cl);
            mhAlSourcef = lookup.findStatic(
                    al10Class, "alSourcef", MethodType.methodType(void.class, int.class, int.class, float.class));
            mhAlSourcePause = lookup.findStatic(
                    al10Class, "alSourcePause", MethodType.methodType(void.class, int.class));
            mhAlSourcePlay = lookup.findStatic(
                    al10Class, "alSourcePlay", MethodType.methodType(void.class, int.class));
            mhAlGetError = lookup.findStatic(
                    al10Class, "alGetError", MethodType.methodType(int.class));
        } catch (Throwable t) {
            LOG.warn("[DivisionAI] Dynamic Battle Music audio handle initialization warning: " + t);
        }
    }

    /** Verifies all obfuscated audio MethodHandles against the loaded Starsector runtime JARs. */
    public static boolean verifyAudioHandles() {
        ensureAudioHandlesInitialized();
        return mhSoundHGetInstance != null
                && mhSoundHGetMasterMusicVol != null
                && mhSoundHSetDynamicVolMult != null
                && mhSoundHStopCurrent != null
                && mhStreamDescriptorCtor != null
                && mhStreamDescriptorInit != null
                && mhStreamDescriptorCreatePlayer != null
                && mhSoundOSetLoop != null
                && mhSoundOFadeIn != null
                && mhSoundOStartPlay != null
                && mhSoundOAdvanceWithVol != null
                && mhSoundOStopAndRelease != null
                && mhSoundOGetSourceId != null
                && mhAlSourcef != null
                && mhAlSourcePause != null
                && mhAlSourcePlay != null
                && mhAlGetError != null;
    }

    private static boolean isOpenAlContextActive() {
        try {
            return org.lwjgl.openal.AL.isCreated();
        } catch (Throwable t) {
            return false;
        }
    }

    private static float getMasterMusicVolumeSetting() {
        if (!isOpenAlContextActive()) return 0.8f;
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
        if (!isOpenAlContextActive()) return;
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

    private static void stopCurrentNativeMusic(int fadeOutSec) {
        if (!isOpenAlContextActive()) return;
        ensureAudioHandlesInitialized();
        if (mhSoundHGetInstance == null || mhSoundHStopCurrent == null) return;
        try {
            Object soundH = mhSoundHGetInstance.invoke();
            if (soundH != null) {
                mhSoundHStopCurrent.invoke(soundH, Math.max(0, fadeOutSec));
            }
        } catch (Throwable ignored) {
        }
    }


    private static Object createStandaloneStreamPlayer(List<String> files, String source, boolean randomStart) {
        if (!isOpenAlContextActive()) return null;
        ensureAudioHandlesInitialized();
        if (!JsonReflectBridge.isAvailable()
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
            String json = sb.toString();
            // sound.O.<init> samples alGetError() BEFORE alGenSources, so any stale error left on the OpenAL context
            // by another subsystem (typically 40964 AL_INVALID_OPERATION) makes stream creation fail spuriously.
            // Drain the sticky error state first, and retry once after re-draining.
            Throwable lastFailure = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                int drained = drainOpenAlErrors();
                if (drained != 0 && attempt == 0) {
                    LOG.info("[DivisionAI] Dynamic Battle Music cleared stale OpenAL error " + drained + " before stream creation");
                }
                try {
                    Object descJson = JsonReflectBridge.newJsonObject(json);
                    Object desc = mhStreamDescriptorCtor.invoke(descJson);
                    Object player = mhStreamDescriptorCreatePlayer.invoke(desc);
                    if (player != null) {
                        int srcId = -1;
                        try {
                            if (mhSoundOGetSourceId != null) srcId = (int) mhSoundOGetSourceId.invoke(player);
                        } catch (Throwable ignored) {
                        }
                        if (srcId < 0 && attempt == 0) {
                            stopStandalonePlayerQuietly(player);
                            continue;
                        }
                        mhSoundOSetLoop.invoke(player, true);
                        if (mhSoundOFadeIn != null) {
                            mhSoundOFadeIn.invoke(player, 0);
                        }
                        mhSoundOStartPlay.invoke(player, true);
                        return player;
                    }
                } catch (Throwable t) {
                    lastFailure = t;
                }
            }
            if (lastFailure != null) {
                LOG.warn("[DivisionAI] Dynamic Battle Music stream creation failed for " + files + ": " + lastFailure);
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Drains all pending OpenAL errors (bounded). Returns the first stale error code seen, or 0. */
    private static int drainOpenAlErrors() {
        if (mhAlGetError == null) return 0;
        int first = 0;
        try {
            for (int i = 0; i < 8; i++) {
                int err = (int) mhAlGetError.invokeExact();
                if (err == 0) break;
                if (first == 0) first = err;
            }
        } catch (Throwable ignored) {
        }
        return first;
    }

    private static void stopStandalonePlayerQuietly(Object player) {
        if (player == null || mhSoundOStopAndRelease == null) return;
        try {
            mhSoundOStopAndRelease.invoke(player);
        } catch (Throwable ignored) {
        }
        drainOpenAlErrors();
    }

    private static void advanceStandaloneStreamPlayer(Object player, float effectiveGain) {
        if (player == null || mhSoundOAdvanceWithVol == null) return;
        try {
            mhSoundOAdvanceWithVol.invoke(player, clamp01(effectiveGain));
        } catch (Throwable ignored) {
        }
    }

    private static void pauseStandaloneStreamPlayer(Object player) {
        if (player == null || mhSoundOGetSourceId == null) return;
        try {
            int sourceId = (int) mhSoundOGetSourceId.invoke(player);
            if (sourceId >= 0) {
                if (mhAlSourcef != null) {
                    mhAlSourcef.invokeExact(sourceId, 4106, 0.0f);
                }
                if (mhAlSourcePause != null) {
                    mhAlSourcePause.invokeExact(sourceId);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void resumeStandaloneStreamPlayer(Object player) {
        if (player == null || mhSoundOGetSourceId == null) return;
        try {
            int sourceId = (int) mhSoundOGetSourceId.invoke(player);
            if (sourceId >= 0 && mhAlSourcePlay != null) {
                mhAlSourcePlay.invokeExact(sourceId);
            }
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
