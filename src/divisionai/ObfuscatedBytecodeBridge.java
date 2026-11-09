package divisionai;

import com.fs.starfarer.api.Global;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-platform (Windows / macOS / Linux) zero-reflection bytecode constant-pool and descriptor bridge.
 *
 * Why this exists:
 * Fractal Softworks's Windows, macOS, and Linux builds of Starsector 0.98a-RC8 share identical public API classes
 * and identical unobfuscated engine entry classes ({@code CombatEngine}, {@code CombatState}, {@code CampaignEngine},
 * {@code CampaignState}, {@code StandardTooltipV2}, {@code Ship}), as well as unobfuscated entry method names
 * ({@code getWarroom}, {@code getMapDisplay}, {@code getWidgetPanel}, {@code getScreenPanel}, {@code getCore},
 * {@code getEncounterDialog}, {@code getCurrentTab}, {@code getTooltipManager}, {@code getContents}, {@code getPosition}).
 * However, because Zelix KlassMaster obfuscates the platform builds separately, internal obfuscated class names
 * (such as {@code com.fs.starfarer.combat.new.OoOO}, {@code com.fs.starfarer.ui.interfacenew},
 * {@code com.fs.starfarer.ui.newui.L}, {@code com.fs.starfarer.campaign.OOoO}, {@code sound.H} vs {@code sound.OooO},
 * and {@code sound.O} vs {@code sound.oo0O}) differ on macOS and Linux.
 *
 * IMPORTANT: Starsector's script sandbox classloader ({@code com.fs.starfarer.loading.scripts.B}) only allows a
 * strict whitelist of {@code java.io.*} classes ({@link InputStream} is allowed; {@code DataInputStream} is NOT).
 * This bridge reads raw {@code .class} bytes using only {@link InputStream} and parses the bytecode constant pool
 * directly in memory with zero {@code java.lang.reflect} and zero non-whitelisted {@code java.io} references.
 */
public final class ObfuscatedBytecodeBridge {

    public static final class MemberEntry {
        public final int accessFlags;
        public final String name;
        public final String descriptor;

        public MemberEntry(int accessFlags, String name, String descriptor) {
            this.accessFlags = accessFlags;
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    public static final class ClassBytecodeInfo {
        public final String internalName;
        public final String superInternalName;
        public final List<MemberEntry> fields;
        public final List<MemberEntry> methods;
        public final List<String> referencedInternalClasses;

        public ClassBytecodeInfo(
                String internalName,
                String superInternalName,
                List<MemberEntry> fields,
                List<MemberEntry> methods,
                List<String> referencedInternalClasses) {
            this.internalName = internalName;
            this.superInternalName = superInternalName;
            this.fields = fields;
            this.methods = methods;
            this.referencedInternalClasses = referencedInternalClasses;
        }
    }

    private static final Map<String, ClassBytecodeInfo> CACHE = new ConcurrentHashMap<>();

    private ObfuscatedBytecodeBridge() {
    }

    /**
     * Inspects {@code ownerClassName}'s bytecode (walking superclasses if needed) for an unobfuscated method
     * {@code methodName} and loads its return type class; falls back to {@code Class.forName(preferredClassName, false, cl)}
     * only if bytecode inspection cannot resolve it.
     *
     * <p>CRITICAL: Never try {@code Class.forName(preferredClassName)} first! Zelix KlassMaster reuses short obfuscated
     * class names (like {@code com.fs.starfarer.ui.B.new}, {@code com.fs.starfarer.coreui.v}, {@code sound.O}) for
     * completely different classes on macOS and Linux.
     */
    public static Class<?> resolveClassOrMethodReturn(
            ClassLoader cl,
            String preferredClassName,
            String ownerClassName,
            String methodName) throws ClassNotFoundException {
        Class<?> resolved = resolveMethodReturnClass(cl, ownerClassName, methodName, null);
        if (resolved != null) {
            return resolved;
        }
        return Class.forName(preferredClassName, false, cl);
    }

    /**
     * Inspects {@code ownerClassName}'s field table (walking superclasses if needed) for an unobfuscated field
     * {@code fieldName} and loads its type class; falls back to {@code Class.forName(preferredClassName, false, cl)}.
     */
    public static Class<?> resolveClassOrFieldType(
            ClassLoader cl,
            String preferredClassName,
            String ownerClassName,
            String fieldName) throws ClassNotFoundException {
        String curClass = ownerClassName;
        for (int depth = 0; depth < 6 && curClass != null && !curClass.startsWith("java."); depth++) {
            ClassBytecodeInfo info = readClassInfo(cl, curClass);
            if (info == null) break;
            if (fieldName != null) {
                for (int i = 0, n = info.fields.size(); i < n; i++) {
                    MemberEntry f = info.fields.get(i);
                    if (fieldName.equals(f.name) && f.descriptor.startsWith("L") && f.descriptor.endsWith(";")) {
                        String clsName = f.descriptor.substring(1, f.descriptor.length() - 1).replace('/', '.');
                        return Class.forName(clsName, false, cl);
                    }
                }
            }
            curClass = (info.superInternalName != null) ? info.superInternalName.replace('/', '.') : null;
        }
        return Class.forName(preferredClassName, false, cl);
    }

    /**
     * Inspects {@code ownerClassName}'s field table (walking superclasses if needed) for a field whose object type
     * starts with {@code packagePrefix} (e.g. {@code "com.fs.starfarer.campaign.ui."} or
     * {@code "com.fs.starfarer.renderers."}); falls back to {@code Class.forName(preferredClassName, false, cl)}.
     */
    public static Class<?> resolveClassOrFieldByPackage(
            ClassLoader cl,
            String preferredClassName,
            String ownerClassName,
            String packagePrefix) throws ClassNotFoundException {
        if (packagePrefix != null) {
            String internalPrefix = "L" + packagePrefix.replace('.', '/');
            String curClass = ownerClassName;
            for (int depth = 0; depth < 6 && curClass != null && !curClass.startsWith("java."); depth++) {
                ClassBytecodeInfo info = readClassInfo(cl, curClass);
                if (info == null) break;
                for (int i = 0, n = info.fields.size(); i < n; i++) {
                    MemberEntry f = info.fields.get(i);
                    if (f.descriptor.startsWith(internalPrefix) && f.descriptor.endsWith(";")) {
                        String clsName = f.descriptor.substring(1, f.descriptor.length() - 1).replace('/', '.');
                        return Class.forName(clsName, false, cl);
                    }
                }
                curClass = (info.superInternalName != null) ? info.superInternalName.replace('/', '.') : null;
            }
        }
        return Class.forName(preferredClassName, false, cl);
    }

    /**
     * Inspects {@code ownerClassName}'s method {@code methodName} (walking superclasses if needed) and loads its
     * {@code paramIndex}-th parameter object class; falls back to {@code Class.forName(preferredClassName, false, cl)}.
     */
    public static Class<?> resolveClassOrMethodParam(
            ClassLoader cl,
            String preferredClassName,
            String ownerClassName,
            String methodName,
            int paramIndex) throws ClassNotFoundException {
        if (methodName != null) {
            String curClass = ownerClassName;
            for (int depth = 0; depth < 6 && curClass != null && !curClass.startsWith("java."); depth++) {
                ClassBytecodeInfo info = readClassInfo(cl, curClass);
                if (info == null) break;
                for (int i = 0, n = info.methods.size(); i < n; i++) {
                    MemberEntry m = info.methods.get(i);
                    if (!methodName.equals(m.name)) continue;
                    List<String> paramClasses = extractParameterObjectClassNames(m.descriptor);
                    if (paramIndex >= 0 && paramIndex < paramClasses.size()) {
                        String clsName = paramClasses.get(paramIndex);
                        if (clsName != null) {
                            return Class.forName(clsName, false, cl);
                        }
                    }
                }
                curClass = (info.superInternalName != null) ? info.superInternalName.replace('/', '.') : null;
            }
        }
        return Class.forName(preferredClassName, false, cl);
    }

    private static List<String> extractParameterObjectClassNames(String methodDescriptor) {
        List<String> out = new ArrayList<>();
        if (methodDescriptor == null || !methodDescriptor.startsWith("(")) return out;
        int end = methodDescriptor.indexOf(')');
        if (end < 0) return out;
        int idx = 1;
        while (idx < end) {
            char c = methodDescriptor.charAt(idx);
            if (c == 'L') {
                int semi = methodDescriptor.indexOf(';', idx);
                if (semi < 0 || semi > end) break;
                out.add(methodDescriptor.substring(idx + 1, semi).replace('/', '.'));
                idx = semi + 1;
            } else if (c == '[') {
                while (idx < end && methodDescriptor.charAt(idx) == '[') idx++;
                if (idx < end && methodDescriptor.charAt(idx) == 'L') {
                    int semi = methodDescriptor.indexOf(';', idx);
                    if (semi < 0 || semi > end) break;
                    idx = semi + 1;
                } else {
                    idx++;
                }
                out.add(null);
            } else {
                out.add(null);
                idx++;
            }
        }
        return out;
    }

    /**
     * Tries each candidate class name in order, throwing the first {@link ClassNotFoundException} only if all fail.
     */
    public static Class<?> resolveFirstAvailableClass(ClassLoader cl, String... candidateClassNames)
            throws ClassNotFoundException {
        ClassNotFoundException firstEx = null;
        for (String name : candidateClassNames) {
            if (name == null || name.isEmpty()) continue;
            try {
                return Class.forName(name, false, cl);
            } catch (ClassNotFoundException ex) {
                if (firstEx == null) {
                    firstEx = ex;
                }
            }
        }
        throw (firstEx != null) ? firstEx : new ClassNotFoundException("No candidate class names provided");
    }

    /**
     * Resolves the music controller singleton class ({@code sound.H} on Windows, {@code sound.OooO} on macOS,
     * {@code sound.OooOOOO...} on Linux) by inspecting {@code com.fs.starfarer.combat.CombatMain}'s constant pool
     * for a {@code sound/*} class containing a {@link Thread} field.
     */
    public static Class<?> resolveSoundHClass(ClassLoader cl) throws ClassNotFoundException {
        ClassBytecodeInfo cmInfo = readClassInfo(cl, "com.fs.starfarer.combat.CombatMain");
        if (cmInfo != null) {
            for (int i = 0, n = cmInfo.referencedInternalClasses.size(); i < n; i++) {
                String ref = cmInfo.referencedInternalClasses.get(i);
                if (ref != null && ref.startsWith("sound/") && ref.indexOf('$') < 0) {
                    String dotName = ref.replace('/', '.');
                    ClassBytecodeInfo sInfo = readClassInfo(cl, dotName);
                    if (sInfo != null) {
                        for (int f = 0, fn = sInfo.fields.size(); f < fn; f++) {
                            if ("Ljava/lang/Thread;".equals(sInfo.fields.get(f).descriptor)) {
                                return Class.forName(dotName, false, cl);
                            }
                        }
                    }
                }
            }
        }
        return resolveFirstAvailableClass(cl, "sound.H", "sound.OooO");
    }

    /**
     * Resolves the audio stream player class ({@code sound.O} on Windows/Linux, {@code sound.oo0O} on macOS)
     * by inspecting {@code soundHClass}'s fields for a {@code sound/*} field type.
     */
    public static Class<?> resolveSoundOClass(ClassLoader cl, Class<?> soundHClass) throws ClassNotFoundException {
        if (soundHClass != null) {
            ClassBytecodeInfo info = readClassInfo(cl, soundHClass.getName());
            if (info != null) {
                String selfDesc = toDescriptor(soundHClass);
                for (int i = 0, n = info.fields.size(); i < n; i++) {
                    MemberEntry f = info.fields.get(i);
                    if (f.descriptor.startsWith("Lsound/") && f.descriptor.endsWith(";") && !selfDesc.equals(f.descriptor)) {
                        String clsName = f.descriptor.substring(1, f.descriptor.length() - 1).replace('/', '.');
                        return Class.forName(clsName, false, cl);
                    }
                }
            }
        }
        return resolveFirstAvailableClass(cl, "sound.O", "sound.oo0O");
    }

    /**
     * Resolves the music stream descriptor class ({@code com.fs.starfarer.loading.int} on Windows)
     * by scanning {@code com.fs.starfarer.loading.SpecStore}'s constant pool for a {@code com/fs/starfarer/loading/*}
     * class with a {@code (Lorg/json/JSONObject;)V} constructor and a method returning {@code soundOClass}.
     */
    public static Class<?> resolveStreamDescriptorClass(ClassLoader cl, Class<?> soundOClass)
            throws ClassNotFoundException {
        if (soundOClass != null) {
            String soundORetSuffix = ")" + toDescriptor(soundOClass);
            ClassBytecodeInfo ssInfo = readClassInfo(cl, "com.fs.starfarer.loading.SpecStore");
            if (ssInfo != null) {
                for (int i = 0, n = ssInfo.referencedInternalClasses.size(); i < n; i++) {
                    String ref = ssInfo.referencedInternalClasses.get(i);
                    if (ref != null && ref.startsWith("com/fs/starfarer/loading/") && ref.indexOf('$') < 0) {
                        String dotName = ref.replace('/', '.');
                        ClassBytecodeInfo cand = readClassInfo(cl, dotName);
                        if (cand == null) continue;
                        boolean hasJsonCtor = false;
                        boolean returnsSoundO = false;
                        for (int m = 0, mn = cand.methods.size(); m < mn; m++) {
                            MemberEntry me = cand.methods.get(m);
                            if ("<init>".equals(me.name) && "(Lorg/json/JSONObject;)V".equals(me.descriptor)) {
                                hasJsonCtor = true;
                            } else if (me.descriptor.endsWith(soundORetSuffix)) {
                                returnsSoundO = true;
                            }
                        }
                        if (hasJsonCtor && returnsSoundO) {
                            return Class.forName(dotName, false, cl);
                        }
                    }
                }
            }
        }
        return Class.forName("com.fs.starfarer.loading.int", false, cl);
    }

    private static String getInternalPackagePrefix(Class<?> cls, String defaultInternalPrefix) {
        if (cls == null) return defaultInternalPrefix;
        String name = cls.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot <= 0) return defaultInternalPrefix;
        return name.substring(0, lastDot + 1).replace('.', '/');
    }

    /**
     * Resolves the Warroom order-arrow manager class ({@code com.fs.starfarer.combat.new.O0OO} on Windows,
     * {@code com.fs.starfarer.combat.OoOO.E} on macOS, {@code com.fs.starfarer.combat.B.class} on Linux)
     * by checking {@code mdClass}'s fields for a Warroom-package type that declares both a {@link Map} field
     * and a {@link List} field.
     */
    public static Class<?> resolveWarroomArrowMgrClass(ClassLoader cl, Class<?> mdClass)
            throws ClassNotFoundException {
        if (mdClass != null) {
            String pkgPrefix = "L" + getInternalPackagePrefix(mdClass, "com/fs/starfarer/combat/new/");
            ClassBytecodeInfo mdInfo = readClassInfo(cl, mdClass.getName());
            if (mdInfo != null) {
                for (int i = 0, n = mdInfo.fields.size(); i < n; i++) {
                    MemberEntry f = mdInfo.fields.get(i);
                    if (f.descriptor.startsWith(pkgPrefix) && f.descriptor.endsWith(";")) {
                        String candName = f.descriptor.substring(1, f.descriptor.length() - 1).replace('/', '.');
                        ClassBytecodeInfo cand = readClassInfo(cl, candName);
                        if (cand == null) continue;
                        boolean hasMap = false;
                        boolean hasList = false;
                        for (int j = 0, fn = cand.fields.size(); j < fn; j++) {
                            String fd = cand.fields.get(j).descriptor;
                            if ("Ljava/util/Map;".equals(fd)) hasMap = true;
                            else if ("Ljava/util/List;".equals(fd)) hasList = true;
                        }
                        if (hasMap && hasList) {
                            return Class.forName(candName, false, cl);
                        }
                    }
                }
            }
        }
        return Class.forName("com.fs.starfarer.combat.new.O0OO", false, cl);
    }

    /**
     * Resolves the Warroom order-arrow entry inner class ({@code com.fs.starfarer.combat.new.O0OO$o} on Windows)
     * by scanning {@code arrowMgrClass}'s referenced classes for an inner class declaring a
     * {@code com.fs.graphics.util.Fader} field.
     */
    public static Class<?> resolveWarroomArrowEntryClass(ClassLoader cl, Class<?> arrowMgrClass)
            throws ClassNotFoundException {
        if (arrowMgrClass != null) {
            String prefix = arrowMgrClass.getName().replace('.', '/') + "$";
            ClassBytecodeInfo info = readClassInfo(cl, arrowMgrClass.getName());
            if (info != null) {
                for (int i = 0, n = info.referencedInternalClasses.size(); i < n; i++) {
                    String ref = info.referencedInternalClasses.get(i);
                    if (ref != null && ref.startsWith(prefix)) {
                        String dotName = ref.replace('/', '.');
                        ClassBytecodeInfo cand = readClassInfo(cl, dotName);
                        if (cand == null) continue;
                        for (int j = 0, fn = cand.fields.size(); j < fn; j++) {
                            if ("Lcom/fs/graphics/util/Fader;".equals(cand.fields.get(j).descriptor)) {
                                return Class.forName(dotName, false, cl);
                            }
                        }
                    }
                }
            }
        }
        return Class.forName("com.fs.starfarer.combat.new.O0OO$o", false, cl);
    }

    /**
     * Resolves the Warroom ship map icon class ({@code com.fs.starfarer.combat.new.A} on Windows)
     * by scanning {@code mdClass}'s constant pool for a Warroom-package class that declares a field or method
     * returning {@code bNewCls}.
     */
    public static Class<?> resolveWarroomShipIconClass(ClassLoader cl, Class<?> mdClass, Class<?> bNewCls)
            throws ClassNotFoundException {
        if (mdClass != null && bNewCls != null) {
            String pkgPrefix = getInternalPackagePrefix(mdClass, "com/fs/starfarer/combat/new/");
            String bNewDesc = toDescriptor(bNewCls);
            String bNewRet = "()" + bNewDesc;
            ClassBytecodeInfo mdInfo = readClassInfo(cl, mdClass.getName());
            if (mdInfo != null) {
                for (int i = 0, n = mdInfo.referencedInternalClasses.size(); i < n; i++) {
                    String ref = mdInfo.referencedInternalClasses.get(i);
                    if (ref != null && ref.startsWith(pkgPrefix) && ref.indexOf('$') < 0) {
                        String dotName = ref.replace('/', '.');
                        if (dotName.equals(mdClass.getName())) continue;
                        ClassBytecodeInfo cand = readClassInfo(cl, dotName);
                        if (cand == null) continue;
                        for (int m = 0, mn = cand.methods.size(); m < mn; m++) {
                            if (bNewRet.equals(cand.methods.get(m).descriptor)) {
                                return Class.forName(dotName, false, cl);
                            }
                        }
                        for (int f = 0, fn = cand.fields.size(); f < fn; f++) {
                            if (bNewDesc.equals(cand.fields.get(f).descriptor)) {
                                return Class.forName(dotName, false, cl);
                            }
                        }
                    }
                }
            }
        }
        return Class.forName("com.fs.starfarer.combat.new.A", false, cl);
    }

    /**
     * Resolves the Warroom fighter/missile map icon class ({@code com.fs.starfarer.combat.new.o0Oo} on Windows)
     * by scanning {@code mdClass}'s constant pool for a Warroom-package class (other than {@code shipIconCls})
     * that has constructor {@code (Lcom/fs/starfarer/combat/entities/Ship;)V} and a {@code com.fs.graphics.Sprite} field.
     */
    public static Class<?> resolveWarroomFighterIconClass(ClassLoader cl, Class<?> mdClass, Class<?> shipIconCls)
            throws ClassNotFoundException {
        if (mdClass != null) {
            String pkgPrefix = getInternalPackagePrefix(mdClass, "com/fs/starfarer/combat/new/");
            String skipName = (shipIconCls != null) ? shipIconCls.getName() : null;
            ClassBytecodeInfo mdInfo = readClassInfo(cl, mdClass.getName());
            if (mdInfo != null) {
                for (int i = 0, n = mdInfo.referencedInternalClasses.size(); i < n; i++) {
                    String ref = mdInfo.referencedInternalClasses.get(i);
                    if (ref != null && ref.startsWith(pkgPrefix) && ref.indexOf('$') < 0) {
                        String dotName = ref.replace('/', '.');
                        if (dotName.equals(mdClass.getName()) || dotName.equals(skipName)) continue;
                        ClassBytecodeInfo cand = readClassInfo(cl, dotName);
                        if (cand == null) continue;
                        boolean hasShipCtor = false;
                        boolean hasSpriteField = false;
                        for (int m = 0, mn = cand.methods.size(); m < mn; m++) {
                            MemberEntry me = cand.methods.get(m);
                            if ("<init>".equals(me.name)
                                    && "(Lcom/fs/starfarer/combat/entities/Ship;)V".equals(me.descriptor)) {
                                hasShipCtor = true;
                                break;
                            }
                        }
                        for (int f = 0, fn = cand.fields.size(); f < fn; f++) {
                            if ("Lcom/fs/graphics/Sprite;".equals(cand.fields.get(f).descriptor)) {
                                hasSpriteField = true;
                                break;
                            }
                        }
                        if (hasShipCtor && hasSpriteField) {
                            return Class.forName(dotName, false, cl);
                        }
                    }
                }
            }
        }
        return Class.forName("com.fs.starfarer.combat.new.o0Oo", false, cl);
    }

    public static Class<?> resolveMethodReturnClass(
            ClassLoader cl,
            String ownerClassName,
            String methodName,
            String requiredParamDescriptorPrefix) {
        if (cl == null || ownerClassName == null || methodName == null) return null;
        String curClass = ownerClassName;
        for (int depth = 0; depth < 6 && curClass != null && !curClass.startsWith("java."); depth++) {
            ClassBytecodeInfo info = readClassInfo(cl, curClass);
            if (info == null) break;
            for (int i = 0, n = info.methods.size(); i < n; i++) {
                MemberEntry m = info.methods.get(i);
                if (!methodName.equals(m.name)) continue;
                if (requiredParamDescriptorPrefix != null && !m.descriptor.startsWith(requiredParamDescriptorPrefix)) {
                    continue;
                }
                String retClassName = extractReturnObjectClassName(m.descriptor);
                if (retClassName != null) {
                    try {
                        return Class.forName(retClassName, false, cl);
                    } catch (Throwable ignored) {
                    }
                }
            }
            curClass = (info.superInternalName != null) ? info.superInternalName.replace('/', '.') : null;
        }
        return null;
    }

    /**
     * Resolves a method name on {@code ownerClass} that has the exact bytecode descriptor {@code exactDescriptor}.
     * Prefers {@code preferredName} if present in {@code ownerClass}'s method table; otherwise returns the first
     * method in {@code ownerClass} matching {@code exactDescriptor}.
     */
    public static String resolveMethodNameByDescriptor(
            ClassLoader cl,
            Class<?> ownerClass,
            String preferredName,
            String exactDescriptor) {
        return resolveMethodNameByDescriptor(cl, ownerClass, preferredName, exactDescriptor, 0);
    }

    /**
     * Resolves a method name on {@code ownerClass} that has the exact bytecode descriptor {@code exactDescriptor}.
     * Prefers {@code preferredName} if present in {@code ownerClass}'s method table; otherwise returns the
     * {@code fallbackOccurrenceIndex}-th non-constructor method in {@code ownerClass} matching {@code exactDescriptor}.
     */
    public static String resolveMethodNameByDescriptor(
            ClassLoader cl,
            Class<?> ownerClass,
            String preferredName,
            String exactDescriptor,
            int fallbackOccurrenceIndex) {
        if (cl == null || ownerClass == null || exactDescriptor == null) return preferredName;
        ClassBytecodeInfo info = readClassInfo(cl, ownerClass.getName());
        if (info == null) return preferredName;
        String firstMatch = null;
        String indexedMatch = null;
        int matchIdx = 0;
        for (int i = 0, n = info.methods.size(); i < n; i++) {
            MemberEntry m = info.methods.get(i);
            if (exactDescriptor.equals(m.descriptor)) {
                if (preferredName != null && preferredName.equals(m.name)) {
                    return preferredName;
                }
                if (!"<init>".equals(m.name) && !"<clinit>".equals(m.name)) {
                    if (firstMatch == null) {
                        firstMatch = m.name;
                    }
                    if (matchIdx == fallbackOccurrenceIndex) {
                        indexedMatch = m.name;
                    }
                    matchIdx++;
                }
            }
        }
        if (indexedMatch != null) return indexedMatch;
        return (firstMatch != null) ? firstMatch : preferredName;
    }

    /**
     * Resolves a field name on {@code ownerClass} that has the exact bytecode descriptor {@code exactDescriptor}.
     * Prefers any of {@code preferredNames} if present; otherwise returns the first field matching {@code exactDescriptor}.
     */
    public static String resolveFieldNameByDescriptor(
            ClassLoader cl,
            Class<?> ownerClass,
            String exactDescriptor,
            String... preferredNames) {
        String defaultName = (preferredNames != null && preferredNames.length > 0) ? preferredNames[0] : null;
        if (cl == null || ownerClass == null || exactDescriptor == null) return defaultName;
        ClassBytecodeInfo info = readClassInfo(cl, ownerClass.getName());
        if (info == null) return defaultName;
        if (preferredNames != null) {
            for (String pref : preferredNames) {
                if (pref == null) continue;
                for (int i = 0, n = info.fields.size(); i < n; i++) {
                    MemberEntry f = info.fields.get(i);
                    if (pref.equals(f.name) && exactDescriptor.equals(f.descriptor)) {
                        return pref;
                    }
                }
            }
        }
        for (int i = 0, n = info.fields.size(); i < n; i++) {
            MemberEntry f = info.fields.get(i);
            if (exactDescriptor.equals(f.descriptor)) {
                return f.name;
            }
        }
        return defaultName;
    }

    public static String toDescriptor(Class<?> cls) {
        if (cls == null) return "Ljava/lang/Object;";
        if (cls == void.class) return "V";
        if (cls == boolean.class) return "Z";
        if (cls == byte.class) return "B";
        if (cls == char.class) return "C";
        if (cls == short.class) return "S";
        if (cls == int.class) return "I";
        if (cls == long.class) return "J";
        if (cls == float.class) return "F";
        if (cls == double.class) return "D";
        if (cls.isArray()) return cls.getName().replace('.', '/');
        return "L" + cls.getName().replace('.', '/') + ";";
    }

    private static String extractReturnObjectClassName(String methodDescriptor) {
        if (methodDescriptor == null) return null;
        int closeParen = methodDescriptor.indexOf(')');
        if (closeParen < 0 || closeParen + 2 >= methodDescriptor.length()) return null;
        String ret = methodDescriptor.substring(closeParen + 1);
        if (ret.startsWith("L") && ret.endsWith(";")) {
            return ret.substring(1, ret.length() - 1).replace('/', '.');
        }
        return null;
    }

    private static InputStream openClassResourceStream(ClassLoader cl, String resourcePath) {
        InputStream in = null;
        for (ClassLoader cur = cl; cur != null && in == null; cur = cur.getParent()) {
            try {
                in = cur.getResourceAsStream(resourcePath);
            } catch (Throwable ignored) {
            }
        }
        if (in == null) {
            try {
                ClassLoader globalCl = Global.class.getClassLoader();
                if (globalCl != null && globalCl != cl) {
                    in = globalCl.getResourceAsStream(resourcePath);
                }
            } catch (Throwable ignored) {
            }
        }
        if (in == null) {
            try {
                in = ClassLoader.getSystemResourceAsStream(resourcePath);
            } catch (Throwable ignored) {
            }
        }
        return in;
    }

    private static byte[] readStreamBytes(InputStream in) throws java.io.IOException {
        byte[] buf = new byte[16384];
        int total = 0;
        while (true) {
            int r = in.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
            if (total == buf.length) {
                byte[] next = new byte[buf.length * 2];
                System.arraycopy(buf, 0, next, 0, total);
                buf = next;
            }
        }
        byte[] exact = new byte[total];
        System.arraycopy(buf, 0, exact, 0, total);
        return exact;
    }

    private static int readU1(byte[] b, int pos) {
        return b[pos] & 0xFF;
    }

    private static int readU2(byte[] b, int pos) {
        return ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
    }

    private static int readS4(byte[] b, int pos) {
        return ((b[pos] & 0xFF) << 24)
                | ((b[pos + 1] & 0xFF) << 16)
                | ((b[pos + 2] & 0xFF) << 8)
                | (b[pos + 3] & 0xFF);
    }

    private static String decodeModifiedUtf8(byte[] b, int offset, int utflen) {
        char[] chars = new char[utflen];
        int count = 0;
        int chararrCount = 0;
        while (count < utflen) {
            int c = b[offset + count] & 0xFF;
            if (c > 127) break;
            count++;
            chars[chararrCount++] = (char) c;
        }
        while (count < utflen) {
            int c = b[offset + count] & 0xFF;
            switch (c >> 4) {
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                    count++;
                    chars[chararrCount++] = (char) c;
                    break;
                case 12:
                case 13: {
                    count += 2;
                    int char2 = b[offset + count - 1];
                    chars[chararrCount++] = (char) (((c & 0x1F) << 6) | (char2 & 0x3F));
                    break;
                }
                case 14: {
                    count += 3;
                    int char2 = b[offset + count - 2];
                    int char3 = b[offset + count - 1];
                    chars[chararrCount++] = (char) (((c & 0x0F) << 12) | ((char2 & 0x3F) << 6) | (char3 & 0x3F));
                    break;
                }
                default:
                    count++;
                    chars[chararrCount++] = (char) c;
                    break;
            }
        }
        return new String(chars, 0, chararrCount);
    }

    public static ClassBytecodeInfo readClassInfo(ClassLoader cl, String className) {
        if (className == null || className.isEmpty()) return null;
        String cacheKey = ((cl != null) ? System.identityHashCode(cl) : 0) + ":" + className;
        ClassBytecodeInfo cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        String resourcePath = className.replace('.', '/') + ".class";
        try (InputStream rawIn = openClassResourceStream(cl, resourcePath)) {
            if (rawIn == null) return null;
            byte[] b = readStreamBytes(rawIn);
            if (b.length < 10) return null;
            int magic = readS4(b, 0);
            if (magic != 0xCAFEBABE) return null;
            int pos = 8; // skip magic (4), minor (2), major (2)

            int cpCount = readU2(b, pos);
            pos += 2;
            String[] utf8Pool = new String[cpCount];
            int[] classNameIndexPool = new int[cpCount];

            for (int i = 1; i < cpCount; i++) {
                int tag = readU1(b, pos);
                pos += 1;
                switch (tag) {
                    case 1: { // CONSTANT_Utf8
                        int utfLen = readU2(b, pos);
                        pos += 2;
                        utf8Pool[i] = decodeModifiedUtf8(b, pos, utfLen);
                        pos += utfLen;
                        break;
                    }
                    case 3: // CONSTANT_Integer
                    case 4: // CONSTANT_Float
                        pos += 4;
                        break;
                    case 5: // CONSTANT_Long
                    case 6: // CONSTANT_Double
                        pos += 8;
                        i++; // 8-byte constants take two constant pool slots
                        break;
                    case 7: // CONSTANT_Class
                        classNameIndexPool[i] = readU2(b, pos);
                        pos += 2;
                        break;
                    case 8: // CONSTANT_String
                    case 16: // CONSTANT_MethodType
                    case 19: // CONSTANT_Module
                    case 20: // CONSTANT_Package
                        pos += 2;
                        break;
                    case 9: // CONSTANT_Fieldref
                    case 10: // CONSTANT_Methodref
                    case 11: // CONSTANT_InterfaceMethodref
                    case 12: // CONSTANT_NameAndType
                    case 17: // CONSTANT_Dynamic
                    case 18: // CONSTANT_InvokeDynamic
                        pos += 4;
                        break;
                    case 15: // CONSTANT_MethodHandle
                        pos += 3;
                        break;
                    default:
                        return null;
                }
            }

            pos += 2; // access_flags
            int thisClassIdx = readU2(b, pos);
            int superClassIdx = readU2(b, pos + 2);
            pos += 4;

            String thisInternal = (thisClassIdx > 0 && thisClassIdx < cpCount)
                    ? utf8Pool[classNameIndexPool[thisClassIdx]]
                    : className.replace('.', '/');
            String superInternal = (superClassIdx > 0 && superClassIdx < cpCount)
                    ? utf8Pool[classNameIndexPool[superClassIdx]]
                    : null;

            int interfacesCount = readU2(b, pos);
            pos += 2 + interfacesCount * 2;

            int fieldsCount = readU2(b, pos);
            pos += 2;
            List<MemberEntry> fields = new ArrayList<>(fieldsCount);
            for (int i = 0; i < fieldsCount; i++) {
                int fAccess = readU2(b, pos);
                int fNameIdx = readU2(b, pos + 2);
                int fDescIdx = readU2(b, pos + 4);
                int attrCount = readU2(b, pos + 6);
                pos += 8;
                for (int a = 0; a < attrCount; a++) {
                    int len = readS4(b, pos + 2);
                    pos += 6 + len;
                }
                String fName = (fNameIdx > 0 && fNameIdx < cpCount) ? utf8Pool[fNameIdx] : "";
                String fDesc = (fDescIdx > 0 && fDescIdx < cpCount) ? utf8Pool[fDescIdx] : "";
                fields.add(new MemberEntry(fAccess, fName, fDesc));
            }

            int methodsCount = readU2(b, pos);
            pos += 2;
            List<MemberEntry> methods = new ArrayList<>(methodsCount);
            for (int i = 0; i < methodsCount; i++) {
                int mAccess = readU2(b, pos);
                int mNameIdx = readU2(b, pos + 2);
                int mDescIdx = readU2(b, pos + 4);
                int attrCount = readU2(b, pos + 6);
                pos += 8;
                for (int a = 0; a < attrCount; a++) {
                    int len = readS4(b, pos + 2);
                    pos += 6 + len;
                }
                String mName = (mNameIdx > 0 && mNameIdx < cpCount) ? utf8Pool[mNameIdx] : "";
                String mDesc = (mDescIdx > 0 && mDescIdx < cpCount) ? utf8Pool[mDescIdx] : "";
                methods.add(new MemberEntry(mAccess, mName, mDesc));
            }

            List<String> referencedInternalClasses = new ArrayList<>();
            for (int i = 1; i < cpCount; i++) {
                int nameIdx = classNameIndexPool[i];
                if (nameIdx > 0 && nameIdx < cpCount && utf8Pool[nameIdx] != null) {
                    referencedInternalClasses.add(utf8Pool[nameIdx]);
                }
            }

            ClassBytecodeInfo info = new ClassBytecodeInfo(
                    thisInternal, superInternal, fields, methods, referencedInternalClasses);
            CACHE.put(cacheKey, info);
            return info;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
