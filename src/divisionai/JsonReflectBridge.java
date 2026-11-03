package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared MethodHandles bridge for {@code org.json.JSONObject}, {@code org.json.JSONArray},
 * and {@link SettingsAPI#getMergedJSONForMod(String, String)}.
 *
 * Compiles without {@code json.jar} on the compile-time classpath and uses zero {@code java.lang.reflect}.
 */
final class JsonReflectBridge {

    private static boolean initialized = false;
    private static MethodHandle mhGetMergedJsonForMod;
    private static MethodHandle mhJsonObjFromStringCtor;
    private static MethodHandle mhJsonObjHas;
    private static MethodHandle mhJsonObjOptBoolean;
    private static MethodHandle mhJsonObjOptInt;
    private static MethodHandle mhJsonObjOptDouble;
    private static MethodHandle mhJsonObjOptString;
    private static MethodHandle mhJsonObjOptJsonObject;
    private static MethodHandle mhJsonObjOptJsonArray;
    private static MethodHandle mhJsonObjNames;
    private static MethodHandle mhJsonArrLength;
    private static MethodHandle mhJsonArrOptString;
    private static MethodHandle mhJsonArrOptJsonObject;

    private JsonReflectBridge() {
    }

    static synchronized void ensureInitialized() {
        if (initialized) return;
        initialized = true;
        try {
            ClassLoader cl = JsonReflectBridge.class.getClassLoader();
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
            mhJsonObjHas = lookup.findVirtual(
                    jsonObjClass,
                    "has",
                    MethodType.methodType(boolean.class, String.class));
            mhJsonObjOptBoolean = lookup.findVirtual(
                    jsonObjClass,
                    "optBoolean",
                    MethodType.methodType(boolean.class, String.class, boolean.class));
            mhJsonObjOptInt = lookup.findVirtual(
                    jsonObjClass,
                    "optInt",
                    MethodType.methodType(int.class, String.class, int.class));
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

    static boolean isAvailable() {
        ensureInitialized();
        return mhJsonObjFromStringCtor != null;
    }

    static Object newJsonObject(String rawJson) throws Throwable {
        ensureInitialized();
        if (mhJsonObjFromStringCtor == null || rawJson == null) return null;
        return mhJsonObjFromStringCtor.invoke(rawJson);
    }

    static Object loadMergedModJson(String configPath, String modId) throws Throwable {
        ensureInitialized();
        Object rootJson = null;
        if (Global.getSettings() != null && mhGetMergedJsonForMod != null) {
            try {
                rootJson = mhGetMergedJsonForMod.invoke(Global.getSettings(), configPath, modId);
            } catch (Throwable ignored) {
            }
        }
        if ((rootJson == null || arrayLength(names(rootJson)) == 0) && mhJsonObjFromStringCtor != null) {
            File localFile = new File("mod/" + configPath);
            if (!localFile.isFile()) {
                localFile = new File(configPath);
            }
            if (localFile.isFile()) {
                String raw = new String(Files.readAllBytes(localFile.toPath()), StandardCharsets.UTF_8);
                rootJson = mhJsonObjFromStringCtor.invoke(raw);
            }
        }
        return rootJson;
    }

    static boolean has(Object jsonObj, String key) throws Throwable {
        return jsonObj != null && mhJsonObjHas != null && (boolean) mhJsonObjHas.invoke(jsonObj, key);
    }

    static boolean optBoolean(Object jsonObj, String key, boolean def) throws Throwable {
        return jsonObj != null && mhJsonObjOptBoolean != null
                ? (boolean) mhJsonObjOptBoolean.invoke(jsonObj, key, def)
                : def;
    }

    static int optInt(Object jsonObj, String key, int def) throws Throwable {
        return jsonObj != null && mhJsonObjOptInt != null
                ? (int) mhJsonObjOptInt.invoke(jsonObj, key, def)
                : def;
    }

    static float optFloat(Object jsonObj, String key, float def) throws Throwable {
        return jsonObj != null && mhJsonObjOptDouble != null
                ? (float) (double) mhJsonObjOptDouble.invoke(jsonObj, key, (double) def)
                : def;
    }

    static String optString(Object jsonObj, String key, String def) throws Throwable {
        return jsonObj != null && mhJsonObjOptString != null
                ? (String) mhJsonObjOptString.invoke(jsonObj, key, def)
                : def;
    }

    static Object optJSONObject(Object jsonObj, String key) throws Throwable {
        return jsonObj != null && mhJsonObjOptJsonObject != null
                ? mhJsonObjOptJsonObject.invoke(jsonObj, key)
                : null;
    }

    static Object optJSONArray(Object jsonObj, String key) throws Throwable {
        return jsonObj != null && mhJsonObjOptJsonArray != null
                ? mhJsonObjOptJsonArray.invoke(jsonObj, key)
                : null;
    }

    static Object names(Object jsonObj) throws Throwable {
        return jsonObj != null && mhJsonObjNames != null
                ? mhJsonObjNames.invoke(jsonObj)
                : null;
    }

    static List<String> keys(Object jsonObj) throws Throwable {
        List<String> out = new ArrayList<>();
        Object namesArr = names(jsonObj);
        if (namesArr == null) return out;
        int len = arrayLength(namesArr);
        for (int i = 0; i < len; i++) {
            String k = arrayOptString(namesArr, i, "");
            if (k != null && !k.isEmpty()) out.add(k);
        }
        return out;
    }

    static List<String> optStringList(Object jsonObj, String key) throws Throwable {
        List<String> out = new ArrayList<>();
        Object arr = optJSONArray(jsonObj, key);
        if (arr == null) return out;
        int len = arrayLength(arr);
        for (int i = 0; i < len; i++) {
            String s = arrayOptString(arr, i, "");
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    static int arrayLength(Object jsonArr) throws Throwable {
        return jsonArr != null && mhJsonArrLength != null
                ? (int) mhJsonArrLength.invoke(jsonArr)
                : 0;
    }

    static String arrayOptString(Object jsonArr, int index, String def) throws Throwable {
        return jsonArr != null && mhJsonArrOptString != null
                ? (String) mhJsonArrOptString.invoke(jsonArr, index, def)
                : def;
    }

    static Object arrayOptJSONObject(Object jsonArr, int index) throws Throwable {
        return jsonArr != null && mhJsonArrOptJsonObject != null
                ? mhJsonArrOptJsonObject.invoke(jsonArr, index)
                : null;
    }
}
