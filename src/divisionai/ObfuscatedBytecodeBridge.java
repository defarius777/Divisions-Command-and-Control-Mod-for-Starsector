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

        public ClassBytecodeInfo(
                String internalName,
                String superInternalName,
                List<MemberEntry> fields,
                List<MemberEntry> methods) {
            this.internalName = internalName;
            this.superInternalName = superInternalName;
            this.fields = fields;
            this.methods = methods;
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
     * Parses {@code ownerClassName}'s bytecode and loads the return type {@link Class} of {@code methodName}.
     */
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

            ClassBytecodeInfo info = new ClassBytecodeInfo(thisInternal, superInternal, fields, methods);
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
