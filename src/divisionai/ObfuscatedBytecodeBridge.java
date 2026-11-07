package divisionai;

import java.io.DataInputStream;
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
 * This bridge reads the raw {@code .class} stream via {@link ClassLoader#getResourceAsStream(String)} using only
 * {@link DataInputStream} (zero {@code java.lang.reflect} constant-pool references) to discover the exact runtime
 * obfuscated class, method, and field names on any OS when the Windows obfuscated name is not present.
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
     * Tries {@code Class.forName(preferredClassName, false, cl)} first (zero-overhead fast path on Windows);
     * if not found (e.g. on macOS/Linux), inspects {@code ownerClassName}'s bytecode for an unobfuscated method
     * {@code methodName} and loads its return type class.
     */
    public static Class<?> resolveClassOrMethodReturn(
            ClassLoader cl,
            String preferredClassName,
            String ownerClassName,
            String methodName) throws ClassNotFoundException {
        try {
            return Class.forName(preferredClassName, false, cl);
        } catch (ClassNotFoundException firstEx) {
            Class<?> resolved = resolveMethodReturnClass(cl, ownerClassName, methodName, null);
            if (resolved != null) {
                return resolved;
            }
            throw firstEx;
        }
    }

    /**
     * Tries {@code Class.forName(preferredClassName, false, cl)} first; if not found, inspects
     * {@code ownerClassName}'s field table for an unobfuscated field {@code fieldName} and loads its type class.
     */
    public static Class<?> resolveClassOrFieldType(
            ClassLoader cl,
            String preferredClassName,
            String ownerClassName,
            String fieldName) throws ClassNotFoundException {
        try {
            return Class.forName(preferredClassName, false, cl);
        } catch (ClassNotFoundException firstEx) {
            ClassBytecodeInfo info = readClassInfo(cl, ownerClassName);
            if (info != null && fieldName != null) {
                for (int i = 0, n = info.fields.size(); i < n; i++) {
                    MemberEntry f = info.fields.get(i);
                    if (fieldName.equals(f.name) && f.descriptor.startsWith("L") && f.descriptor.endsWith(";")) {
                        String clsName = f.descriptor.substring(1, f.descriptor.length() - 1).replace('/', '.');
                        return Class.forName(clsName, false, cl);
                    }
                }
            }
            throw firstEx;
        }
    }

    /**
     * Tries {@code Class.forName(preferredClassName, false, cl)} first; if not found, inspects
     * {@code ownerClassName}'s field table for a field whose object type starts with {@code packagePrefix}
     * (e.g. {@code "com.fs.starfarer.campaign.ui."} or {@code "com.fs.starfarer.renderers."}).
     */
    public static Class<?> resolveClassOrFieldByPackage(
            ClassLoader cl,
            String preferredClassName,
            String ownerClassName,
            String packagePrefix) throws ClassNotFoundException {
        try {
            return Class.forName(preferredClassName, false, cl);
        } catch (ClassNotFoundException firstEx) {
            ClassBytecodeInfo info = readClassInfo(cl, ownerClassName);
            if (info != null && packagePrefix != null) {
                String internalPrefix = "L" + packagePrefix.replace('.', '/');
                for (int i = 0, n = info.fields.size(); i < n; i++) {
                    MemberEntry f = info.fields.get(i);
                    if (f.descriptor.startsWith(internalPrefix) && f.descriptor.endsWith(";")) {
                        String clsName = f.descriptor.substring(1, f.descriptor.length() - 1).replace('/', '.');
                        return Class.forName(clsName, false, cl);
                    }
                }
            }
            throw firstEx;
        }
    }

    /**
     * Tries {@code Class.forName(preferredClassName, false, cl)} first; if not found, inspects
     * {@code ownerClassName}'s method {@code methodName} and loads its {@code paramIndex}-th parameter object class.
     */
    public static Class<?> resolveClassOrMethodParam(
            ClassLoader cl,
            String preferredClassName,
            String ownerClassName,
            String methodName,
            int paramIndex) throws ClassNotFoundException {
        try {
            return Class.forName(preferredClassName, false, cl);
        } catch (ClassNotFoundException firstEx) {
            ClassBytecodeInfo info = readClassInfo(cl, ownerClassName);
            if (info != null && methodName != null) {
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
            }
            throw firstEx;
        }
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
     * Resolves the music controller singleton class ({@code sound.H} on Windows, {@code sound.OooO} on macOS)
     * by checking candidate names first and falling back to inspecting {@code com.fs.starfarer.combat.CombatMain}'s
     * constant pool for a {@code sound/*} class containing a {@link Thread} field.
     */
    public static Class<?> resolveSoundHClass(ClassLoader cl) throws ClassNotFoundException {
        try {
            return resolveFirstAvailableClass(cl, "sound.H", "sound.OooO");
        } catch (ClassNotFoundException firstEx) {
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
            throw firstEx;
        }
    }

    /**
     * Resolves the audio stream player class ({@code sound.O} on Windows, {@code sound.oo0O} on macOS)
     * by checking candidate names first and falling back to inspecting {@code soundHClass}'s fields for
     * a {@code sound/*} field type.
     */
    public static Class<?> resolveSoundOClass(ClassLoader cl, Class<?> soundHClass) throws ClassNotFoundException {
        try {
            return resolveFirstAvailableClass(cl, "sound.O", "sound.oo0O");
        } catch (ClassNotFoundException firstEx) {
            if (soundHClass != null) {
                Class<?> fromField = resolveClassOrFieldByPackage(cl, "sound.O", soundHClass.getName(), "sound.");
                if (fromField != null && fromField != soundHClass) {
                    return fromField;
                }
            }
            throw firstEx;
        }
    }

    /**
     * Resolves the music stream descriptor class ({@code com.fs.starfarer.loading.int} on Windows)
     * by trying {@code Class.forName} first and falling back to scanning {@code com.fs.starfarer.loading.SpecStore}'s
     * constant pool for a {@code com/fs/starfarer/loading/*} class with a {@code (Lorg/json/JSONObject;)V} constructor
     * and a method returning {@code soundOClass}.
     */
    public static Class<?> resolveStreamDescriptorClass(ClassLoader cl, Class<?> soundOClass)
            throws ClassNotFoundException {
        try {
            return Class.forName("com.fs.starfarer.loading.int", false, cl);
        } catch (ClassNotFoundException firstEx) {
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
            throw firstEx;
        }
    }
    public static Class<?> resolveMethodReturnClass(
            ClassLoader cl,
            String ownerClassName,
            String methodName,
            String requiredParamDescriptorPrefix) {
        if (cl == null || ownerClassName == null || methodName == null) return null;
        ClassBytecodeInfo info = readClassInfo(cl, ownerClassName);
        if (info == null) return null;
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
        if (cl == null || ownerClass == null || exactDescriptor == null) return preferredName;
        ClassBytecodeInfo info = readClassInfo(cl, ownerClass.getName());
        if (info == null) return preferredName;
        String fallbackMatch = null;
        for (int i = 0, n = info.methods.size(); i < n; i++) {
            MemberEntry m = info.methods.get(i);
            if (exactDescriptor.equals(m.descriptor)) {
                if (preferredName != null && preferredName.equals(m.name)) {
                    return preferredName;
                }
                if (fallbackMatch == null && !"<init>".equals(m.name) && !"<clinit>".equals(m.name)) {
                    fallbackMatch = m.name;
                }
            }
        }
        return (fallbackMatch != null) ? fallbackMatch : preferredName;
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

    public static ClassBytecodeInfo readClassInfo(ClassLoader cl, String className) {
        if (cl == null || className == null || className.isEmpty()) return null;
        ClassBytecodeInfo cached = CACHE.get(className);
        if (cached != null) return cached;

        String resourcePath = className.replace('.', '/') + ".class";
        try (InputStream rawIn = cl.getResourceAsStream(resourcePath)) {
            if (rawIn == null) return null;
            DataInputStream in = new DataInputStream(rawIn);
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) return null;
            in.readUnsignedShort(); // minor_version
            in.readUnsignedShort(); // major_version

            int cpCount = in.readUnsignedShort();
            String[] utf8Pool = new String[cpCount];
            int[] classNameIndexPool = new int[cpCount];

            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1: // CONSTANT_Utf8
                        utf8Pool[i] = in.readUTF();
                        break;
                    case 3: // CONSTANT_Integer
                    case 4: // CONSTANT_Float
                        in.readInt();
                        break;
                    case 5: // CONSTANT_Long
                    case 6: // CONSTANT_Double
                        in.readLong();
                        i++; // 8-byte constants take two constant pool slots
                        break;
                    case 7: // CONSTANT_Class
                        classNameIndexPool[i] = in.readUnsignedShort();
                        break;
                    case 8: // CONSTANT_String
                    case 16: // CONSTANT_MethodType
                    case 19: // CONSTANT_Module
                    case 20: // CONSTANT_Package
                        in.readUnsignedShort();
                        break;
                    case 9: // CONSTANT_Fieldref
                    case 10: // CONSTANT_Methodref
                    case 11: // CONSTANT_InterfaceMethodref
                    case 12: // CONSTANT_NameAndType
                    case 17: // CONSTANT_Dynamic
                    case 18: // CONSTANT_InvokeDynamic
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                        break;
                    case 15: // CONSTANT_MethodHandle
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        break;
                    default:
                        return null;
                }
            }

            in.readUnsignedShort(); // access_flags
            int thisClassIdx = in.readUnsignedShort();
            int superClassIdx = in.readUnsignedShort();

            String thisInternal = (thisClassIdx > 0 && thisClassIdx < cpCount)
                    ? utf8Pool[classNameIndexPool[thisClassIdx]]
                    : className.replace('.', '/');
            String superInternal = (superClassIdx > 0 && superClassIdx < cpCount)
                    ? utf8Pool[classNameIndexPool[superClassIdx]]
                    : null;

            int interfacesCount = in.readUnsignedShort();
            for (int i = 0; i < interfacesCount; i++) {
                in.readUnsignedShort();
            }

            int fieldsCount = in.readUnsignedShort();
            List<MemberEntry> fields = new ArrayList<>(fieldsCount);
            for (int i = 0; i < fieldsCount; i++) {
                int fAccess = in.readUnsignedShort();
                int fNameIdx = in.readUnsignedShort();
                int fDescIdx = in.readUnsignedShort();
                int attrCount = in.readUnsignedShort();
                for (int a = 0; a < attrCount; a++) {
                    in.readUnsignedShort();
                    int len = in.readInt();
                    skipFully(in, len);
                }
                String fName = (fNameIdx > 0 && fNameIdx < cpCount) ? utf8Pool[fNameIdx] : "";
                String fDesc = (fDescIdx > 0 && fDescIdx < cpCount) ? utf8Pool[fDescIdx] : "";
                fields.add(new MemberEntry(fAccess, fName, fDesc));
            }

            int methodsCount = in.readUnsignedShort();
            List<MemberEntry> methods = new ArrayList<>(methodsCount);
            for (int i = 0; i < methodsCount; i++) {
                int mAccess = in.readUnsignedShort();
                int mNameIdx = in.readUnsignedShort();
                int mDescIdx = in.readUnsignedShort();
                int attrCount = in.readUnsignedShort();
                for (int a = 0; a < attrCount; a++) {
                    in.readUnsignedShort();
                    int len = in.readInt();
                    skipFully(in, len);
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
            CACHE.put(className, info);
            return info;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void skipFully(DataInputStream in, int bytes) throws java.io.IOException {
        int remaining = bytes;
        while (remaining > 0) {
            int skipped = in.skipBytes(remaining);
            if (skipped <= 0) {
                in.readByte();
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }
}
