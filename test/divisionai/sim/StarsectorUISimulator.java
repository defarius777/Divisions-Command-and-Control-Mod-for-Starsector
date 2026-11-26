package divisionai.sim;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomVisualDialogDelegate;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.campaign.fleet.FleetMember;
import com.fs.starfarer.campaign.ui.D;
import com.fs.starfarer.campaign.ui.supersuper;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.CombatState;
import com.fs.starfarer.loading.specs.O00o;
import com.fs.starfarer.settings.StarfarerSettings;
import com.fs.starfarer.ui.M;
import com.fs.starfarer.ui.c;
import com.fs.starfarer.ui.classnew;
import com.fs.starfarer.ui.impl.E;
import com.fs.starfarer.ui.impl.oO0O;
import com.fs.starfarer.ui.z;
import divisionai.DivisionDeploymentDialogOrganizer;
import divisionai.DivisionEditorDialog;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.CommanderProfile;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionModel.DivisionState;
import divisionai.DynamicBattleMusicController;
import divisionai.FleetDoctrineDialog;
import divisionai.PermanentDivisionManager;
import divisionai.PermanentDivisionManager.DivisionFormation;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import divisionai.sim.UITestHarness.UIAuditResult;
import jdk.internal.reflect.ReflectionFactory;
import org.json.JSONObject;

import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 1:1 Headless Starsector UI Simulation Harness.
 *
 * Boots Starsector's real obfuscated UI and rendering classes in headless JVM:
 *  - {@code com.fs.starfarer.settings.StarfarerSettings$1} (real {@link SettingsAPI})
 *  - {@code com.fs.graphics.A.D} / {@code com.fs.graphics.A.F} (real {@code .fnt} bitmap font metrics & word-wrap engine)
 *  - {@code com.fs.graphics.oOoO} / {@code com.fs.graphics.Object} (lazy headless texture loader reading real PNG dimensions)
 *  - {@code com.fs.starfarer.ui.impl.oO0O} (real {@code FleetDeploymentDialog})
 *  - {@code com.fs.starfarer.campaign.ui.S} & {@code com.fs.starfarer.coreui.v} (real ship grids & scrolling tables)
 *  - {@code com.fs.starfarer.ui.newui.int} & {@code StandardTooltipV2Expandable} (real {@link CustomPanelAPI} & {@code TooltipMakerAPI})
 *  - {@code com.fs.starfarer.ui.d} & {@code com.fs.starfarer.ui.n} (real {@link LabelAPI} & {@link ButtonAPI})
 */
public class StarsectorUISimulator {

    public static class SimFdd extends oO0O {
        public List<FleetMemberAPI> selectedMembers = new ArrayList<>();

        public SimFdd() {
            super(null, null);
        }

        @Override
        public M setSize(float w, float h) {
            return getPosition().setSize(w, h);
        }

        @Override
        public M sizeToInner(float w, float h) {
            if (getInnerPanel() != null) {
                getInnerPanel().setSize(w, h);
            }
            return getPosition().setSize(w + 20f, h + 20f);
        }

        @Override
        public List getSelected() {
            return selectedMembers;
        }
    }

    public static class SimCombatFleetManager extends com.fs.starfarer.combat.CombatFleetManager {
        public com.fs.starfarer.api.fleet.FleetGoal simGoal = com.fs.starfarer.api.fleet.FleetGoal.ATTACK;
        public int simMaxStrength = 240;
        public PersonAPI simCommander;
        public int simOwner = 0;

        public SimCombatFleetManager() {
            super(0);
        }

        @Override
        public int getOwner() {
            return simOwner;
        }

        @Override
        public com.fs.starfarer.api.fleet.FleetGoal getGoal() {
            return simGoal;
        }

        @Override
        public int getMaxStrength() {
            return simMaxStrength;
        }

        @Override
        public int getCurrStrength() {
            return 0;
        }

        @Override
        public PersonAPI getFleetCommanderPreferPlayer() {
            return simCommander;
        }
    }

    public static class SimProgressBar extends com.fs.starfarer.campaign.ui.N {
        public SimProgressBar() {
            super((String) null, 100f, 20f);
        }

        @Override
        public void setProgress(float p) {
        }
    }

    public static class SimButton extends classnew {
        private final String lbl;
        public SimButton(float w, float h, String lbl, Object data) {
            super((Oo) null, null);
            this.lbl = lbl;
            setSize(w, h);
            setCustomData(data);
        }
        @Override public void renderImpl(float a) {}
        @Override public String getText() { return lbl; }
    }

    public static class SimFaction extends com.fs.starfarer.campaign.Faction {
        public SimFaction() { super("player"); }
        @Override public String getId() { return "player"; }
        @Override public String getDisplayName() { return "Player"; }
        @Override public Color getBaseUIColor() { return new Color(110, 210, 255, 255); }
        @Override public Color getBrightUIColor() { return new Color(170, 235, 255, 255); }
        @Override public Color getDarkUIColor() { return new Color(35, 75, 105, 255); }
        @Override public Color getGridUIColor() { return new Color(60, 125, 165, 255); }
        @Override public Color getSecondaryUIColor() { return new Color(110, 210, 255, 255); }
    }

    public static class SimCampaignEngine extends com.fs.starfarer.campaign.CampaignEngine {
        public com.fs.starfarer.campaign.Faction simFaction;
        @Override public com.fs.starfarer.campaign.Faction getPlayerFaction() { return simFaction; }
        @Override public com.fs.starfarer.campaign.Faction getFaction(String id) { return simFaction; }
    }

    public static class SimPerson extends com.fs.starfarer.rpg.Person {
        public String nameStr;
        public FullName fullName;
        public String portrait;
        public boolean aiCore;
        public String aiCoreId;

        public static SimPerson create(String first, String last, String portrait, boolean aiCore, String aiCoreId) throws Exception {
            SimPerson p = allocateWithoutConstructor(SimPerson.class);
            p.nameStr = first + " " + last;
            p.fullName = new FullName(first, last, FullName.Gender.ANY);
            p.portrait = portrait;
            p.aiCore = aiCore;
            p.aiCoreId = aiCoreId;
            return p;
        }

        @Override public String getNameString() { return nameStr; }
        @Override public FullName getName() { return fullName; }
        @Override public String getPortraitSprite() { return portrait; }
        @Override public boolean isDefault() { return false; }
        @Override public boolean isAICore() { return aiCore; }
        @Override public String getAICoreId() { return aiCoreId; }
        @Override public com.fs.starfarer.api.characters.PersonalityAPI getPersonalityAPI() {
            return (com.fs.starfarer.api.characters.PersonalityAPI) Proxy.newProxyInstance(
                    StarsectorUISimulator.class.getClassLoader(),
                    new Class<?>[]{com.fs.starfarer.api.characters.PersonalityAPI.class},
                    (p, m, a) -> {
                        if ("getId".equals(m.getName())) return aiCore ? "fearless" : "steady";
                        if ("getDisplayName".equals(m.getName())) return aiCore ? "Fearless" : "Steady";
                        return null;
                    });
        }
    }

    public static class SimFleetMember extends FleetMember {
        public String simId;
        public String simShipName;
        public float simDp;
        public boolean simFlagship;
        public HullSize simSize;
        public O00o simHullSpec;
        public PersonAPI simCaptain;

        public SimFleetMember() {
            super(0, (String) null, null);
        }

        public static SimFleetMember create(
                String id,
                String shipName,
                float dp,
                boolean flagship,
                HullSize size,
                O00o hullSpec,
                PersonAPI captain) throws Exception {
            SimFleetMember m = allocateWithoutConstructor(SimFleetMember.class);
            m.simId = id;
            m.simShipName = shipName;
            m.simDp = dp;
            m.simFlagship = flagship;
            m.simSize = size;
            m.simHullSpec = hullSpec;
            m.simCaptain = captain;
            for (Field f : FleetMember.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (PersonAPI.class.isAssignableFrom(f.getType()) && captain instanceof com.fs.starfarer.rpg.Person) {
                    f.set(m, captain);
                } else if (f.getType() == com.fs.starfarer.api.fleet.FleetMemberType.class) {
                    f.set(m, com.fs.starfarer.api.fleet.FleetMemberType.SHIP);
                }
            }
            return m;
        }

        @Override public com.fs.starfarer.api.fleet.FleetMemberType getType() { return com.fs.starfarer.api.fleet.FleetMemberType.SHIP; }
        @Override public boolean isStation() { return false; }
        @Override public String getId() { return simId; }
        @Override public String getShipName() { return simShipName; }
        @Override public float getDeploymentPointsCost() { return simDp; }
        @Override public float getUnmodifiedDeploymentPointsCost() { return simDp; }
        @Override public boolean isFlagship() { return simFlagship; }
        @Override public void setFlagship(boolean f) {}
        @Override public boolean isFighterWing() { return false; }
        @Override public boolean isCivilian() { return false; }
        @Override public boolean isCarrier() { return false; }
        @Override public boolean isPhaseShip() { return false; }
        @Override public boolean isCapital() { return simSize == HullSize.CAPITAL_SHIP; }
        @Override public boolean isCruiser() { return simSize == HullSize.CRUISER; }
        @Override public boolean isDestroyer() { return simSize == HullSize.DESTROYER; }
        @Override public boolean isFrigate() { return simSize == HullSize.FRIGATE; }
        @Override public boolean isMothballed() { return false; }
        @Override public boolean canBeDeployedForCombat() { return true; }
        @Override public O00o getHullSpec() { return simHullSpec; }
        @Override public String getHullId() { return simHullSpec != null ? simHullSpec.getHullId() : "onslaught"; }
        @Override public com.fs.starfarer.rpg.Person getCaptain() {
            return (simCaptain instanceof com.fs.starfarer.rpg.Person) ? (com.fs.starfarer.rpg.Person) simCaptain : null;
        }
    }

    private static String stripJsonComments(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (String line : raw.split("\r?\n")) {
            boolean inStr = false;
            int cut = -1;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                    inStr = !inStr;
                }
                if (!inStr && ch == '#') {
                    cut = i;
                    break;
                }
            }
            sb.append(cut >= 0 ? line.substring(0, cut) : line).append('\n');
        }
        return sb.toString();
    }

    private static int[] readPngDimensions(String relPath, Path starsectorCoreDir) {
        if (relPath == null || relPath.isEmpty()) return new int[]{64, 64};
        List<Path> candidates = Arrays.asList(
                Paths.get("mod", relPath),
                starsectorCoreDir.resolve(relPath));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                try (FileInputStream in = new FileInputStream(p.toFile())) {
                    byte[] hdr = new byte[24];
                    if (in.read(hdr) == 24
                            && (hdr[0] & 0xFF) == 0x89
                            && hdr[1] == 0x50
                            && hdr[2] == 0x4E
                            && hdr[3] == 0x47) {
                        int w = ((hdr[16] & 0xFF) << 24) | ((hdr[17] & 0xFF) << 16) | ((hdr[18] & 0xFF) << 8) | (hdr[19] & 0xFF);
                        int h = ((hdr[20] & 0xFF) << 24) | ((hdr[21] & 0xFF) << 16) | ((hdr[22] & 0xFF) << 8) | (hdr[23] & 0xFF);
                        if (w > 0 && h > 0) {
                            return new int[]{w, h};
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return new int[]{64, 64};
    }

    private static volatile float simulatedScreenWidth = 1920f;
    private static volatile float simulatedScreenHeight = 1080f;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SettingsAPI installRealStarsectorHeadlessUI(Path starsectorCoreDir) throws Exception {
        String coreRaw = new String(Files.readAllBytes(starsectorCoreDir.resolve("data/config/settings.json")), StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(stripJsonComments(coreRaw));
        JSONObject gfx = json.getJSONObject("graphics");

        String modRaw = new String(Files.readAllBytes(Paths.get("mod/data/config/settings.json")), StandardCharsets.UTF_8);
        JSONObject modJson = new JSONObject(stripJsonComments(modRaw));
        if (modJson.has("graphics")) {
            JSONObject modGfx = modJson.getJSONObject("graphics");
            Iterator<?> mgit = modGfx.keys();
            while (mgit.hasNext()) {
                String k = (String) mgit.next();
                gfx.put(k, modGfx.get(k));
            }
        }
        Iterator<?> git = gfx.keys();
        while (git.hasNext()) {
            String k = (String) git.next();
            if (!json.has(k)) {
                json.put(k, gfx.get(k));
            }
        }
        Iterator<?> mit = modJson.keys();
        while (mit.hasNext()) {
            String k = (String) mit.next();
            if (!"graphics".equals(k)) {
                json.put(k, modJson.get(k));
            }
        }

        for (Field f : StarfarerSettings.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getType() == JSONObject.class) {
                f.set(null, json);
            } else if (f.getType() == String.class && !Modifier.isFinal(f.getModifiers())) {
                f.set(null, "graphics/fonts/insignia15LTaa.fnt");
            } else if (f.getType() == float.class && !Modifier.isFinal(f.getModifiers())) {
                // Set screen resolution to 1920x1080 and UI scale to 1.0f
                String fn = f.getName();
                if (f.getFloat(null) == 0f) {
                    f.setFloat(null, 1.0f);
                }
            }
        }

        // Set StarfarerSettings screen width=1920, height=1080 via DisplayMode / resolution fields
        Class<?> settingsImplCls = Class.forName("com.fs.starfarer.settings.StarfarerSettings$1");
        Constructor<?> sc = settingsImplCls.getDeclaredConstructor();
        sc.setAccessible(true);
        final SettingsAPI realSettings = (SettingsAPI) sc.newInstance();

        // Wrap realSettings so getScreenWidth() / getScreenHeight() return simulatedScreenWidth x simulatedScreenHeight in headless mode
        SettingsAPI wrappedSettings = (SettingsAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{SettingsAPI.class},
                (proxy, method, args) -> {
                    String mn = method.getName();
                    if ("getScreenWidth".equals(mn) || "getScreenWidthPixels".equals(mn)) return simulatedScreenWidth;
                    if ("getScreenHeight".equals(mn) || "getScreenHeightPixels".equals(mn)) return simulatedScreenHeight;
                    if ("getUiScale".equals(mn)) return 1.0f;
                    return method.invoke(realSettings, args);
                });
        Global.setSettings(wrappedSettings);

        // Configure VFS (com.fs.util.C in 0.98.5a-RC9) to resolve files from starsector-core and mod/
        Class<?> vfsCls = Class.forName("com.fs.util.C");
        Object vfs = null;
        for (Method m : vfsCls.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == vfsCls && Modifier.isStatic(m.getModifiers())) {
                m.setAccessible(true);
                vfs = m.invoke(null);
                break;
            }
        }
        for (Method m : vfsCls.getDeclaredMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class && m.getReturnType() == void.class) {
                m.setAccessible(true);
                try {
                    m.invoke(vfs, starsectorCoreDir.toAbsolutePath().toString().replace('\\', '/'));
                    m.invoke(vfs, Paths.get("mod").toAbsolutePath().toString().replace('\\', '/'));
                } catch (Throwable ignored) {
                }
            }
        }

        Fonts.DEFAULT_SMALL = "graphics/fonts/insignia15LTaa.fnt";

        // Install lazy headless texture loader (reads real PNG dimensions from disk!)
        Class<?> texCls = Class.forName("com.fs.graphics.Texture");
        Constructor<?> texCtor = texCls.getDeclaredConstructor(int.class, int.class, String.class);
        texCtor.setAccessible(true);

        Class<?> loaderCls = Class.forName("com.fs.graphics.oOoO");
        for (Field f : loaderCls.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Map<String, Object> lazyMap = new HashMap<String, Object>() {
                    @Override
                    public boolean containsKey(Object key) {
                        return key != null;
                    }

                    @Override
                    public Object get(Object key) {
                        if (key == null) return null;
                        String k = String.valueOf(key);
                        Object v = super.get(k);
                        if (v == null) {
                            try {
                                int[] dims = readPngDimensions(k, starsectorCoreDir);
                                v = texCtor.newInstance(3553, 1, k);
                                for (Field tf : texCls.getDeclaredFields()) {
                                    tf.setAccessible(true);
                                    if (tf.getType() == int.class && !Modifier.isStatic(tf.getModifiers())) {
                                        int cur = tf.getInt(v);
                                        if (cur == 0) {
                                            tf.setInt(v, dims[0]);
                                        }
                                    } else if (tf.getType() == float.class && !Modifier.isStatic(tf.getModifiers())) {
                                        tf.setFloat(v, 1.0f);
                                    }
                                }
                                super.put(k, v);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                        return v;
                    }
                };
                f.set(null, lazyMap);
            } else if (f.getType() == com.fs.graphics.TextureLoader.class) {
                f.setAccessible(true);
                f.set(null, new com.fs.graphics.TextureLoader() {
                    @Override
                    public void \u00d200000(String id, String path) {
                        // Headless no-op for font page & lazy texture loads
                    }
                });
            }
        }

        // Install lazy real .fnt bitmap font loader into the font registry class in fs.common_obf.jar
        final String fontMapClsName = resolveFontRegistryClassName();
        Class<?> fontMapCls = Class.forName(fontMapClsName);
        Method loadFont = fontMapCls.getDeclaredMethod("o00000", String.class, String.class);
        loadFont.setAccessible(true);
        for (Field f : fontMapCls.getDeclaredFields()) {
            if (HashMap.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                HashMap<String, Object> lazyFonts = new HashMap<String, Object>() {
                    @Override
                    public Object get(Object key) {
                        String k = (key == null || String.valueOf(key).isEmpty())
                                ? "graphics/fonts/insignia15LTaa.fnt"
                                : String.valueOf(key);
                        Object v = super.get(k);
                        if (v == null) {
                            try {
                                loadFont.invoke(null, k, k);
                                v = super.get(k);
                            } catch (Exception e) {
                                throw new RuntimeException("Invalid or missing .fnt font path requested by UI: '" + k + "'", e);
                            }
                        }
                        return v;
                    }
                };
                f.set(null, lazyFonts);
            }
        }

        return wrappedSettings;
    }

    private static <T> T allocateWithoutConstructor(Class<T> cls) throws Exception {
        ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
        Constructor<?> objCtor = Object.class.getDeclaredConstructor();
        Constructor<?> sc = rf.newConstructorForSerialization(cls, objCtor);
        sc.setAccessible(true);
        return cls.cast(sc.newInstance());
    }

    private static String resolveFontRegistryClassName() throws Exception {
        Class<?> iconCls = divisionai.ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                StarsectorUISimulator.class.getClassLoader(),
                "com.fs.starfarer.ui.OoOO.OoOO",
                "com.fs.starfarer.campaign.ui.supersuper",
                "getIconForMember");
        String pkgPrefix = null;
        for (Field f : iconCls.getDeclaredFields()) {
            String tn = f.getType().getName();
            if (tn.startsWith("com.fs.graphics.") && tn.lastIndexOf('.') > "com.fs.graphics".length()) {
                pkgPrefix = tn.substring(0, tn.lastIndexOf('.'));
                break;
            }
        }
        if (pkgPrefix != null) {
            for (String cand : new String[]{pkgPrefix + ".String", pkgPrefix + ".O0OO", pkgPrefix + ".D"}) {
                try {
                    Class<?> c = Class.forName(cand);
                    c.getDeclaredMethod("o00000", String.class, String.class);
                    return cand;
                } catch (Throwable ignored) {
                }
            }
        }
        throw new ClassNotFoundException("Could not locate bitmap font registry class in " + pkgPrefix);
    }

    private static supersuper buildRealShipGrid(
            oO0O fdd,
            float width,
            float height,
            int cols,
            List<FleetMember> members) throws Exception {
        supersuper grid = new supersuper(cols, 0f, 58f, D.o.values()[0], fdd);
        grid.setSize(width, height);
        List<FleetMember> gridMembers = grid.getMembers();
        com.fs.starfarer.coreui.w table = grid.getList();

        Class<?> iconCls = divisionai.ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                StarsectorUISimulator.class.getClassLoader(),
                "com.fs.starfarer.ui.OoOO.OoOO",
                "com.fs.starfarer.campaign.ui.supersuper",
                "getIconForMember");
        Object fontObj = null;
        Constructor<?> textRendererCtor = null;
        try {
            Class<?> fontMapCls = Class.forName(resolveFontRegistryClassName());
            Class<?> fontCls = null;
            for (Method m : fontMapCls.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == String.class
                        && m.getReturnType().getName().endsWith(".return")) {
                    m.setAccessible(true);
                    fontCls = m.getReturnType();
                    fontObj = m.invoke(null, "graphics/fonts/victor14.fnt");
                    break;
                }
            }
            if (fontCls != null) {
                for (Field f : iconCls.getDeclaredFields()) {
                    if ("float.Object".equals(f.getName())) {
                        textRendererCtor = f.getType().getDeclaredConstructor(fontCls);
                        textRendererCtor.setAccessible(true);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        for (FleetMember m : members) {
            gridMembers.add(m);
            Object icon = allocateWithoutConstructor(iconCls);
            for (Field f : iconCls.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == FleetMember.class) {
                    f.set(icon, m);
                } else if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) {
                    f.setInt(icon, -1);
                } else if (f.getType() == String.class && !Modifier.isStatic(f.getModifiers())) {
                    f.set(icon, "DEPLOY");
                } else if (textRendererCtor != null && fontObj != null && f.getType() == textRendererCtor.getDeclaringClass()) {
                    f.set(icon, textRendererCtor.newInstance(fontObj));
                }
            }
            SimButton cell = new SimButton(58f, 58f, m.getId(), m);
            table.addItem(cell, icon);
        }
        return grid;
    }

    private static boolean isPurePrintableAscii(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch > 0x7F) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static void collectLabelsAndButtonsRecursive(
            Object comp,
            List<LabelAPI> outLabels,
            List<ButtonAPI> outButtons) {
        collectLabelsAndButtonsRecursive(
                comp,
                outLabels,
                outButtons,
                Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>()));
    }

    @SuppressWarnings("unchecked")
    private static void collectLabelsAndButtonsRecursive(
            Object comp,
            List<LabelAPI> outLabels,
            List<ButtonAPI> outButtons,
            java.util.Set<Object> visited) {
        if (comp == null || !visited.add(comp)) return;
        if (comp instanceof LabelAPI) {
            outLabels.add((LabelAPI) comp);
        }
        if (comp instanceof ButtonAPI) {
            outButtons.add((ButtonAPI) comp);
        }
        try {
            Method getChildren = comp.getClass().getMethod("getChildrenCopy");
            Object res = getChildren.invoke(comp);
            if (res instanceof List) {
                for (Object child : (List<?>) res) {
                    collectLabelsAndButtonsRecursive(child, outLabels, outButtons, visited);
                }
            }
        } catch (Throwable ignored) {
        }
        // Also inspect StandardTooltipV2Expandable.getPanel() or Scroller (com.fs.starfarer.ui.g) content,
        // without walking up parent pointers on general UI components
        String clsName = comp.getClass().getName();
        if (clsName.contains("StandardTooltip") || clsName.equals("com.fs.starfarer.ui.g")) {
            for (Class<?> cl = comp.getClass(); cl != null && cl != Object.class; cl = cl.getSuperclass()) {
                if (cl == c.class) continue;
                for (Field f : cl.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (UIComponentAPI.class.isAssignableFrom(f.getType())) {
                        try {
                            f.setAccessible(true);
                            Object sub = f.get(comp);
                            if (sub != null && sub != comp) {
                                collectLabelsAndButtonsRecursive(sub, outLabels, outButtons, visited);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
    }

    public static void runOneToOneStarsectorUISimulation(UIAuditResult res) {
        Path starsectorCoreDir = null;
        String envCore = System.getenv("STARSECTOR_CORE");
        if (envCore != null && !envCore.trim().isEmpty()) {
            starsectorCoreDir = Paths.get(envCore.trim());
        }
        if (starsectorCoreDir == null || !Files.isDirectory(starsectorCoreDir)) {
            String envDir = System.getenv("STARSECTOR_DIR");
            if (envDir != null && !envDir.trim().isEmpty()) {
                starsectorCoreDir = Paths.get(envDir.trim(), "starsector-core");
            }
        }
        if (starsectorCoreDir == null || !Files.isDirectory(starsectorCoreDir)) {
            Path cfgFile = Paths.get("lib/starsector_core_path.txt");
            if (Files.isRegularFile(cfgFile)) {
                try {
                    String line = new String(Files.readAllBytes(cfgFile), StandardCharsets.UTF_8).trim();
                    if (!line.isEmpty()) {
                        starsectorCoreDir = Paths.get(line);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (starsectorCoreDir == null || !Files.isDirectory(starsectorCoreDir)) {
            res.record(true, "1:1 Starsector Obfuscated UI Simulation", "Skipped (starsector-core directory not configured)");
            return;
        }
        try {
            Class.forName("com.fs.starfarer.ui.impl.oO0O");
        } catch (ClassNotFoundException e) {
            res.record(true, "1:1 Starsector Obfuscated UI Simulation", "Skipped (starfarer_obf.jar not on classpath)");
            return;
        }

        SettingsAPI prevSettings = null;
        SectorAPI prevSector = null;
        try {
            prevSettings = Global.getSettings();
        } catch (Throwable ignored) {
        }
        try {
            prevSector = Global.getSector();
        } catch (Throwable ignored) {
        }

        try {
            installRealStarsectorHeadlessUI(starsectorCoreDir);

            // 1. Verify all 22 Formation Sprites load 64x64 textures and center properly on resize
            boolean allFormationSpritesCenteredOk = true;
            int verifiedSprites = 0;
            for (DivisionFormation fm : DivisionFormation.values()) {
                for (boolean gold : new boolean[]{false, true}) {
                    SpriteAPI sp = FleetDoctrineDialog.loadFormationSpriteSafe(
                            fm.getSettingsIconKey(gold),
                            fm.getSpritePath(gold));
                    if (sp == null || sp.getTextureId() <= 0 || sp.getWidth() != 64f || sp.getHeight() != 64f) {
                        allFormationSpritesCenteredOk = false;
                    } else {
                        sp.setSize(14f, 14f);
                        sp.setCenter(7f, 7f);
                        if (Math.abs(sp.getCenterX() - 7f) > 0.01f || Math.abs(sp.getCenterY() - 7f) > 0.01f) {
                            allFormationSpritesCenteredOk = false;
                        }
                        sp.setSize(64f, 64f);
                        sp.setCenter(32f, 32f);
                        verifiedSprites++;
                    }
                }
            }

            // Fleet formation compass sprites (128px gold/navy full set + 8 compass-wheel-only mid-battle sprites)
            boolean allFleetFormationSpritesOk = true;
            int verifiedFleetSprites = 0;
            for (PermanentDivisionManager.FleetWideFormation fw : PermanentDivisionManager.FleetWideFormation.values()) {
                String path = DivisionDeploymentDialogOrganizer.getFleetFormationSpritePath(fw, false);
                String wheelPath = DivisionDeploymentDialogOrganizer.getFleetFormationWheelSpritePath(fw);
                SpriteAPI sp = FleetDoctrineDialog.loadFormationSpriteSafe(null, path);
                SpriteAPI wheelSp = FleetDoctrineDialog.loadFormationSpriteSafe(null, wheelPath);
                if (path == null || sp == null || sp.getTextureId() <= 0
                        || sp.getWidth() != 128f || sp.getHeight() != 128f
                        || wheelPath == null || !wheelPath.endsWith("_wheel.png")
                        || wheelSp == null || wheelSp.getTextureId() <= 0
                        || wheelSp.getWidth() != 128f || wheelSp.getHeight() != 128f) {
                    allFleetFormationSpritesOk = false;
                } else {
                    sp.setSize(96f, 96f);
                    sp.setCenter(48f, 48f);
                    wheelSp.setSize(38f, 38f);
                    wheelSp.setCenter(19f, 19f);
                    if (Math.abs(sp.getCenterX() - 48f) > 0.01f || Math.abs(sp.getCenterY() - 48f) > 0.01f
                            || Math.abs(wheelSp.getCenterX() - 19f) > 0.01f || Math.abs(wheelSp.getCenterY() - 19f) > 0.01f) {
                        allFleetFormationSpritesOk = false;
                    }
                    verifiedFleetSprites++;
                }
            }

            // OTC Command Style Naval Pennant sprites (4 dark-navy 256x256 pennants)
            boolean allOtcPennantSpritesOk = true;
            int verifiedOtcPennantSprites = 0;
            for (PermanentDivisionManager.OtcAuthority auth : PermanentDivisionManager.OtcAuthority.values()) {
                SpriteAPI pSp = FleetDoctrineDialog.loadFormationSpriteSafe(
                        auth.getSettingsIconKey(), auth.getSpritePath());
                if (pSp == null || pSp.getTextureId() <= 0 || pSp.getWidth() != 256f || pSp.getHeight() != 256f) {
                    allOtcPennantSpritesOk = false;
                } else {
                    pSp.setSize(96f, 96f);
                    pSp.setCenter(48f, 48f);
                    if (Math.abs(pSp.getCenterX() - 48f) > 0.01f || Math.abs(pSp.getCenterY() - 48f) > 0.01f) {
                        allOtcPennantSpritesOk = false;
                    }
                    pSp.setSize(256f, 256f);
                    pSp.setCenter(128f, 128f);
                    verifiedOtcPennantSprites++;
                }
            }
            for (PermanentDivisionManager.OperationalTemplate tpl : PermanentDivisionManager.OperationalTemplate.values()) {
                SpriteAPI cSp = FleetDoctrineDialog.loadFormationSpriteSafe(
                        tpl.getSettingsIconKey(), tpl.getSpritePath());
                if (cSp == null || cSp.getTextureId() <= 0 || cSp.getWidth() != 256f || cSp.getHeight() != 256f) {
                    allOtcPennantSpritesOk = false;
                }
            }
            SpriteAPI custSp = FleetDoctrineDialog.loadFormationSpriteSafe(
                    PermanentDivisionManager.OperationalTemplate.CUSTOM_CONDUCT_ICON_KEY,
                    PermanentDivisionManager.OperationalTemplate.CUSTOM_CONDUCT_SPRITE_PATH);
            if (custSp == null || custSp.getTextureId() <= 0 || custSp.getWidth() != 256f || custSp.getHeight() != 256f) {
                allOtcPennantSpritesOk = false;
            }

            // 2. Build 16 real SimFleetMembers & Campaign Sector persistent data for 1:1 UI simulation
            O00o hullSpec = allocateWithoutConstructor(O00o.class);
            for (Field f : O00o.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("Object.new") && f.getType() == String.class) {
                    f.set(hullSpec, "onslaught");
                } else if (f.getType() == HullSize.class) {
                    f.set(hullSpec, HullSize.CAPITAL_SHIP);
                } else if (EnumSet.class.isAssignableFrom(f.getType())) {
                    f.set(hullSpec, EnumSet.noneOf(ShipHullSpecAPI.ShipTypeHints.class));
                }
            }

            PersonAPI humanCmdr = SimPerson.create("Admiral", "Vance", "graphics/portraits/portrait_hegemony01.png", false, null);
            PersonAPI alphaCoreCmdr = SimPerson.create("Alpha", "Core", "graphics/portraits/portrait_ai2b.png", true, "alpha_core");

            boolean stdDeploymentOk = simulateDeploymentWindow1To1(hullSpec, humanCmdr, false, true);
            boolean alphaDeploymentOk = simulateDeploymentWindow1To1(hullSpec, alphaCoreCmdr, true, true);
            boolean midBattleDeploymentOk = simulateDeploymentWindow1To1(hullSpec, humanCmdr, false, false);

            // 3. Simulate FleetDoctrineDialog 1:1 across all 3 tabs
            boolean fleetDoctrineDialogOk = simulateFleetDoctrineDialog1To1();

            // 4. Simulate DivisionEditorDialog 1:1
            boolean divisionEditorDialogOk = simulateDivisionEditorDialog1To1();

            // 5. Simulate CampaignFleetTooltipOrganizer 1:1 (normal, foreign mod overlay sibling anchor, duplicates, station fleet)
            boolean campaignTooltipSimOk = simulateCampaignFleetTooltipOrganizer1To1(hullSpec, humanCmdr);

            // 6. Simulate 1:1 Warroom Battle Suite (OoOO, b, A, o0Oo, B.new command sprite suppression, mid-battle spawns & Mod Settings toggle)
            boolean warroomBattleSuiteOk = simulateWarroomBattleCommandSpriteSuite1To1();

            // 7. Simulate 1:1 Pursuit Deployment Window ([L]/[C]/[R] flank selection, mixed division toggling, and clean deselection)
            boolean pursuitDeploymentOk = simulatePursuitDeploymentWindow1To1(hullSpec, humanCmdr);

            // 8. Simulate 1:1 720p (1280x720) & 768p Responsive UI Viewport Suite (Doctrine, Editor, Configure, Deployment Modals & Crowded Sidebar)
            boolean responsive720pOk = simulate720pAnd768pViewportSuite1To1(hullSpec, humanCmdr);
            boolean onboardingCodexClarityOk = simulateOnboardingCodexAndConfigureClarity1To1();

            boolean allSimOk = allFormationSpritesCenteredOk
                    && verifiedSprites == 22
                    && allFleetFormationSpritesOk
                    && verifiedFleetSprites == 12
                    && allOtcPennantSpritesOk
                    && verifiedOtcPennantSprites == 4
                    && stdDeploymentOk
                    && alphaDeploymentOk
                    && midBattleDeploymentOk
                    && pursuitDeploymentOk
                    && fleetDoctrineDialogOk
                    && divisionEditorDialogOk
                    && campaignTooltipSimOk
                    && warroomBattleSuiteOk
                    && responsive720pOk
                    && onboardingCodexClarityOk;

            res.record(
                    allSimOk,
                    "1:1 Starsector Obfuscated UI Simulation (Deployment formation selector, campaign dialogs & 30 formation sprites)",
                    String.format(
                            Locale.ROOT,
                            "divisionSprites=%d, fleetSprites=%d(%b), deployStd=%b, deployAlpha=%b, deployMidBattle=%b, deployPursuit=%b, doctrineDialog=%b, editorDialog=%b, campaignTooltip1To1=%b, warroomBattleSuite1To1=%b, responsive720p1To1=%b, onboardingCodex=%b",
                            verifiedSprites,
                            verifiedFleetSprites,
                            allFleetFormationSpritesOk,
                            stdDeploymentOk,
                            alphaDeploymentOk,
                            midBattleDeploymentOk,
                            pursuitDeploymentOk,
                            fleetDoctrineDialogOk,
                            divisionEditorDialogOk,
                            campaignTooltipSimOk,
                            warroomBattleSuiteOk,
                            responsive720pOk,
                            onboardingCodexClarityOk));
            res.record(
                    onboardingCodexClarityOk,
                    "1:1 Soft Fleet Intro Tour (Galatia/Modal Conflict Guards), 0.98a Codex (6 Entries + F2 Links) & Easier-to-Understand Configure UI",
                    "Verified 4-step non-blocking Fleet coachmark (1080p & 720p), Galatia/modal guards, cross-save persistence, Spacer's Manual & LunaLib Codex generation, F2 links, and Role & Standing Orders hierarchy");
            res.record(
                    responsive720pOk,
                    "1:1 Starsector 720p (1280x720) Responsive UI Viewport & Modal Expansion Verification",
                    "Verified 1240x688 FleetDoctrine/DivisionEditor/Configure clamping, 5-pennant scaling, upward 720p Deployment modal expansion + FleetCommandPanel restore, and crowded-sidebar fallback");
            UITestHarness.auditLateCombatWarroomPerformanceAndStateIntegrityAfterUISim(res);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            res.record(false, "1:1 Starsector Obfuscated UI Simulation", "Exception: " + t);
        } finally {
            if (prevSettings != null) {
                try { Global.setSettings(prevSettings); } catch (Throwable ignored) {}
            }
            if (prevSector != null) {
                try { Global.setSector(prevSector); } catch (Throwable ignored) {}
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean simulateDeploymentWindow1To1(
            O00o hullSpec,
            final PersonAPI flagshipCaptain,
            boolean expectAlphaCore,
            boolean preBattle) throws Throwable {

        divisionai.DivisionOtcCommandManager.resetForNewBattle();
        final PersonAPI subDiv1Cmdr = SimPerson.create("Lieutenant", "Mira", "graphics/portraits/portrait_hegemony04.png", false, null);
        final PersonAPI div2Cmdr = SimPerson.create("Captain", "Kael", "graphics/portraits/portrait_hegemony02.png", false, null);
        final PersonAPI indepCmdr = SimPerson.create("Commander", "Sera", "graphics/portraits/portrait_hegemony03.png", false, null);
        final List<FleetMember> allMembers = new ArrayList<>();
        final List<FleetMember> deployedMembers = new ArrayList<>();
        final List<FleetMember> reserveMembers = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            PersonAPI cap = (i == 0) ? flagshipCaptain : (i == 2 ? subDiv1Cmdr : (i == 6 ? div2Cmdr : (i == 11 ? indepCmdr : null)));
            SimFleetMember m = SimFleetMember.create(
                    "sim_ship_" + i,
                    i == 0 ? "HSS Invictus Sovereign of the Stars" : ("HSS Vanguard " + i),
                    i < 6 ? 40f : 20f,
                    i == 0,
                    i < 6 ? HullSize.CAPITAL_SHIP : HullSize.CRUISER,
                    hullSpec,
                    cap);
            allMembers.add(m);
            if (!preBattle && i < 2) deployedMembers.add(m);
            else reserveMembers.add(m);
        }

        final Map<String, Object> pdata = new HashMap<>();
        FleetDataAPI fleetData = (FleetDataAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{FleetDataAPI.class},
                (p, m, a) -> "getMembersListCopy".equals(m.getName()) ? new ArrayList<>(allMembers) : null);
        final FactionAPI playerFaction = (FactionAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{FactionAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getId".equals(n)) return "player";
                    if ("getDisplayName".equals(n)) return "Player";
                    if ("getBaseUIColor".equals(n)) return new Color(110, 210, 255, 255);
                    if ("getBrightUIColor".equals(n)) return new Color(170, 235, 255, 255);
                    if ("getDarkUIColor".equals(n)) return new Color(35, 75, 105, 255);
                    if ("getGridUIColor".equals(n)) return new Color(60, 125, 165, 255);
                    if ("getSecondaryUIColor".equals(n)) return new Color(110, 210, 255, 255);
                    if (m.getReturnType() == Color.class) return new Color(110, 210, 255, 255);
                    if (m.getReturnType() == boolean.class) return false;
                    return null;
                });
        CampaignFleetAPI playerFleet = (CampaignFleetAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (p, m, a) -> {
                    if ("getFleetData".equals(m.getName())) return fleetData;
                    if ("getFlagship".equals(m.getName())) return allMembers.get(0);
                    if ("getCommander".equals(m.getName())) return flagshipCaptain;
                    if ("getFaction".equals(m.getName())) return playerFaction;
                    if (m.getReturnType() == boolean.class) return false;
                    return null;
                });
        SectorAPI sector = (SectorAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class},
                (p, m, a) -> {
                    if ("getPersistentData".equals(m.getName())) return pdata;
                    if ("getPlayerFleet".equals(m.getName())) return playerFleet;
                    if ("getPlayerFaction".equals(m.getName())) return playerFaction;
                    if ("getPlayerPerson".equals(m.getName())) return flagshipCaptain;
                    if ("getFaction".equals(m.getName())) return playerFaction;
                    if (m.getReturnType() == boolean.class) return false;
                    return null;
                });
        try {
            com.fs.starfarer.campaign.CampaignEngine.getInstance();
        } catch (Throwable ignored) {
        }
        Global.setSector(sector);

        PermanentDivisionSpec div1 = new PermanentDivisionSpec("div_1", "Battle Div 1", DivisionArchetype.BATTLESHIP_DIVISION);
        div1.customFormation = DivisionFormation.LINE_ABREAST;
        div1.flagshipMemberId = "sim_ship_0";
        for (int i = 0; i < 6; i++) div1.memberIds.add("sim_ship_" + i);

        PermanentDivisionSpec div2 = new PermanentDivisionSpec("div_2", "Missile Battleship Division 1", DivisionArchetype.MISSILE_BATTLESHIP_DIVISION);
        div2.customFormation = DivisionFormation.LINE_AHEAD;
        // Feature A test: div2's division flagship (sim_ship_7) has no Officer (cap == null), while sim_ship_6 has Captain Kael.
        // Officerless division flagship sim_ship_7 must NOT appear in the player's OTC Selection Window options (winButtons stays 5, not 6).
        div2.flagshipMemberId = "sim_ship_7";
        for (int i = 6; i < 11; i++) div2.memberIds.add("sim_ship_" + i);

        Method saveSpecsM = PermanentDivisionManager.class.getDeclaredMethod("saveSpecsToPersistentData", Map.class, List.class);
        saveSpecsM.setAccessible(true);
        saveSpecsM.invoke(null, pdata, Arrays.asList(div1, div2));

        // Construct real Starsector FleetDeploymentDialog
        Constructor<z> panelCtor = z.class.getDeclaredConstructor();
        Constructor<SimFdd> fddCtor = (Constructor<SimFdd>) ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(SimFdd.class, panelCtor);
        SimFdd fdd = fddCtor.newInstance();
        fdd.selectedMembers = new ArrayList<>();
        z innerPanel = new z();
        fdd.setSize(624f, 460f);
        innerPanel.setSize(604f, 440f);
        fdd.add(innerPanel).inTL(10f, 10f);

        for (Field f : oO0O.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getName().equals("interface.null$Object")) f.setInt(fdd, 0);
            else if (f.getName().equals("while.null$Object")) f.setBoolean(fdd, false);
        }
        for (Field f : E.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getType() == z.class) f.set(fdd, innerPanel);
            else if (f.getType() == Map.class) f.set(fdd, new HashMap<>());
        }

        supersuper deployedGrid = buildRealShipGrid(fdd, 592f, 174f, 10, deployedMembers);
        supersuper reservesGrid = buildRealShipGrid(fdd, 592f, 174f, 10, reserveMembers);
        z sep1 = new z();
        sep1.setSize(592f, 7f);
        z sep2 = new z();
        sep2.setSize(542f, 1f);
        LabelAPI crNote = Global.getSettings().createLabel("Combat readiness note", Fonts.DEFAULT_SMALL);
        ((UIComponentAPI) crNote).getPosition().setSize(250f, 22f);

        innerPanel.add(deployedGrid).inTL(10f, 10f);
        innerPanel.add(sep1).belowLeft(deployedGrid, 10f);
        innerPanel.add(reservesGrid).belowLeft(sep1, 10f);
        innerPanel.add(sep2).belowLeft(reservesGrid, 15f);
        innerPanel.add((c) crNote).belowLeft(sep2, 6f);

        z widgetPanel = new z();
        widgetPanel.setSize(1920f, 1080f);
        widgetPanel.add(fdd).inMid();

        CombatState cs = allocateWithoutConstructor(CombatState.class);
        for (Field f : CombatState.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getName().equals("widgetPanel")) {
                f.set(cs, widgetPanel);
            } else if (f.getType().getName().equals("com.fs.starfarer.combat.CombatState$o")) {
                f.set(cs, Enum.valueOf((Class) f.getType(), "DEPLOY"));
            }
        }

        CombatEngine mockEngine = allocateWithoutConstructor(CombatEngine.class);
        CombatEngineAPI proxyEngine = (CombatEngineAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{CombatEngineAPI.class},
                (p, m, a) -> {
                    if ("getCombatUI".equals(m.getName())) return cs;
                    if ("getShips".equals(m.getName())) return Collections.emptyList();
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });

        CommanderProfile pCmdr = new CommanderProfile(flagshipCaptain, expectAlphaCore ? "aggressive" : "steady");
        BattleGroupState playerGroup = new BattleGroupState(0, pCmdr);
        BattleGroupState enemyGroup = new BattleGroupState(1, new CommanderProfile(null, "steady"));
        DivisionState dState1 = new DivisionState("div_1", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Div 1", pCmdr);
        playerGroup.divisions.add(dState1);
        playerGroup.mainEffortDivision = dState1;

        DivisionDeploymentDialogOrganizer organizer = new DivisionDeploymentDialogOrganizer();
        organizer.setBattleContext(playerGroup, enemyGroup, new DynamicBattleMusicController());
        Global.setSector(sector);

        // Verify Refit Simulator (isSimulation == true) leaves vanilla FleetDeploymentDialog (Opponents/Allies tabs) 100% untouched
        float vanillaResW = reservesGrid.getPosition().getWidth();
        float vanillaResH = reservesGrid.getPosition().getHeight();
        int vanillaInnerChildCount = innerPanel.getChildrenCopy().size();
        CombatEngineAPI simProxyEngine = (CombatEngineAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{CombatEngineAPI.class},
                (p, m, a) -> {
                    if ("getCombatUI".equals(m.getName())) return cs;
                    if ("isSimulation".equals(m.getName())) return true;
                    if ("getShips".equals(m.getName())) return Collections.emptyList();
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });
        organizer.advance(simProxyEngine);
        if (Math.abs(reservesGrid.getPosition().getWidth() - vanillaResW) > 0.01f
                || Math.abs(reservesGrid.getPosition().getHeight() - vanillaResH) > 0.01f
                || innerPanel.getChildrenCopy().size() != vanillaInnerChildCount) {
            System.out.println("FAIL Simulator UI modified by organizer!");
            return false;
        }

        organizer.advance(proxyEngine);
        innerPanel.advance(0.1f);

        // Verify enlarged dimensions and zero overlap between fleetCommandPanel, sep1, and reservesGrid
        PositionAPI resPos = reservesGrid.getPosition();
        PositionAPI sep1Pos = sep1.getPosition();
        if (Math.abs(resPos.getWidth() - 940f) > 3f || Math.abs(resPos.getHeight() - 406f) > 3f) {
            System.out.println("FAIL resPos size: " + resPos.getWidth() + "x" + resPos.getHeight());
            return false;
        }

        CustomPanelAPI fcPanel = null;
        for (c child : innerPanel.getChildrenCopy()) {
            if (child instanceof CustomPanelAPI && child != deployedGrid && child != reservesGrid) {
                fcPanel = (CustomPanelAPI) child;
            }
        }
        if (fcPanel == null) {
            System.out.println("FAIL fcPanel null");
            return false;
        }

        PositionAPI fcPos = fcPanel.getPosition();
        if (Math.abs(fcPos.getWidth() - 940f) > 3f || Math.abs(fcPos.getHeight() - 204f) > 3f) {
            System.out.println("FAIL fcPos size: " + fcPos.getWidth() + "x" + fcPos.getHeight());
            return false;
        }
        // 1. fleetCommandPanel must align horizontally with reservesGrid and sit strictly above sep1 and reservesGrid
        boolean fcAlignedAndAboveSeparator = Math.abs(fcPos.getX() - resPos.getX()) <= 2f
                && Math.abs(fcPos.getWidth() - resPos.getWidth()) <= 2f
                && fcPos.getY() >= (sep1Pos.getY() + sep1Pos.getHeight() - 1f)
                && sep1Pos.getY() >= (resPos.getY() + resPos.getHeight() - 1f);
        if (!fcAlignedAndAboveSeparator) {
            System.out.println("FAIL fcAligned: fc=(" + fcPos.getX() + "," + fcPos.getY() + "," + fcPos.getWidth() + "," + fcPos.getHeight()
                    + ") res=(" + resPos.getX() + "," + resPos.getY() + "," + resPos.getWidth() + "," + resPos.getHeight()
                    + ") sep1=(" + sep1Pos.getX() + "," + sep1Pos.getY() + "," + sep1Pos.getWidth() + "," + sep1Pos.getHeight() + ")");
            return false;
        }

        // 2. Audit every label inside fleetCommandPanel
        List<LabelAPI> fcLabels = new ArrayList<>();
        List<ButtonAPI> fcButtons = new ArrayList<>();
        collectLabelsAndButtonsRecursive(fcPanel, fcLabels, fcButtons);
        if (fcLabels.size() < 6) {
            System.out.println("FAIL fcLabels size: " + fcLabels.size());
            return false;
        }

        String[] bannedDeploymentReadout = {
                "ENGAGEMENT:", "COHESION:", "DIRECTIVE:", "FORCE & DP",
                "Protocol:", "Setpiece:", "TACTICAL DOMAINS", "TELEMETRY"
        };
        boolean sawEngagement = false;
        boolean sawCohesion = false;
        LabelAPI alphaMasterLabel = null;
        LabelAPI alphaShipLineLabel = null;
        for (LabelAPI lbl : fcLabels) {
            String txt = lbl.getText();
            if (txt == null) continue;
            if (txt.startsWith("ENGAGEMENT")) sawEngagement = true;
            if (txt.startsWith("COHESION")) sawCohesion = true;
            if ("MASTER".equals(txt)) alphaMasterLabel = lbl;
            if (txt.startsWith("HSS INVICTUS")) alphaShipLineLabel = lbl;
            if (preBattle) {
                for (String banned : bannedDeploymentReadout) {
                    if (txt.contains(banned)) {
                        System.out.println("FAIL word-salad deployment label: " + txt);
                        return false;
                    }
                }
            }
        }
        if (preBattle && fcButtons.size() != 9) {
            System.out.println("FAIL command style + fighting style + fleet formation + OTC change buttons: " + fcButtons.size());
            return false;
        }
        // Verify groupSizes=[4, 15, 5] in UITestHarness still matches, and in StarsectorUISimulator div3 is rendered in reservesGrid
        // while div3's officerless leader (sim_ship_12) is excluded from OTC Selection Window (still 1 Close + 4 officer cards = 5 buttons!)
        if (!preBattle && (fcButtons.size() != 1 || !sawEngagement || !sawCohesion)) {
            System.out.println("FAIL post-deployment live status: buttons=" + fcButtons.size()
                    + ", engagement=" + sawEngagement + ", cohesion=" + sawCohesion);
            return false;
        }
        ButtonAPI changeOtcButton = null;
        for (ButtonAPI btn : fcButtons) {
            if ("Change".equals(btn.getText())) {
                changeOtcButton = btn;
            }
        }
        if (changeOtcButton == null) {
            System.out.println("FAIL missing 'Change' button under OTC portrait");
            return false;
        }
        if (expectAlphaCore && !preBattle && changeOtcButton.isEnabled()) {
            System.out.println("FAIL 'Change' button must be disabled mid-battle when OTC is an Alpha Core");
            return false;
        }
        if ((preBattle || !expectAlphaCore) && !changeOtcButton.isEnabled()) {
            System.out.println("FAIL 'Change' button must be enabled pre-battle (even for Alpha Core) and for human OTC");
            return false;
        }
        PositionAPI chPos = changeOtcButton.getPosition();
        float portraitBottomY = fcPos.getY() + fcPos.getHeight() - 26f - 92f;
        if (chPos == null
                || Math.abs(chPos.getX() - (fcPos.getX() + 12f)) > 2f
                || (chPos.getY() + chPos.getHeight()) > portraitBottomY + 1f
                || chPos.getY() < fcPos.getY() + 2f) {
            System.out.println("FAIL 'Change' button not positioned directly under OTC portrait: chPos="
                    + (chPos == null ? "null" : (chPos.getX() + "," + chPos.getY() + "," + chPos.getWidth() + "," + chPos.getHeight()))
                    + " portraitBottomY=" + portraitBottomY);
            return false;
        }
        if (preBattle) {
            // Verify pre-battle default player OTC -> largest selected ship commander (sticky when selection clears!) -> sticky player ship -> sticky explicit choice
            if (!expectAlphaCore) {
                if (!"sim_ship_0".equals(divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup))) {
                    System.out.println("FAIL Pre-battle OTC must default to player ship sim_ship_0 when nothing is selected");
                    return false;
                }
                // Click Cruiser sim_ship_11 (20 DP, Commander Sera) to deploy -> OTC auto-switches to sim_ship_11
                fdd.selectedMembers.add(allMembers.get(11));
                fcPanel.advance(0.1f);
                if (!"sim_ship_11".equals(divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup))) {
                    System.out.println("FAIL Pre-battle OTC did not auto-switch to Cruiser commander sim_ship_11: got "
                            + divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup));
                    return false;
                }
                // Serious Bug A regression check: clearing fdd.selectedMembers (e.g. clicking blank space or deploying) MUST keep sim_ship_11 sticky!
                fdd.selectedMembers.clear();
                fcPanel.advance(0.1f);
                if (!"sim_ship_11".equals(divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup))
                        || !"sim_ship_11".equals(divisionai.DivisionOtcCommandManager.getAutoSelectedPlayerOtcMemberId())) {
                    System.out.println("FAIL Serious Bug A: Auto-selected OTC sim_ship_11 was NOT sticky when selection cleared! got "
                            + divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup));
                    return false;
                }
                // Click Capital sim_ship_2 (40 DP, Lieutenant Mira) + Cruiser sim_ship_11 -> OTC auto-switches to largest ship sim_ship_2
                fdd.selectedMembers.add(allMembers.get(11));
                fdd.selectedMembers.add(allMembers.get(2));
                fcPanel.advance(0.1f);
                if (!"sim_ship_2".equals(divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup))) {
                    System.out.println("FAIL Pre-battle OTC did not auto-switch to largest selected ship commander sim_ship_2: got "
                            + divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup));
                    return false;
                }
                // Clear selection again -> sim_ship_2 remains sticky!
                fdd.selectedMembers.clear();
                fcPanel.advance(0.1f);
                if (!"sim_ship_2".equals(divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup))) {
                    System.out.println("FAIL Serious Bug A: Auto-selected largest OTC sim_ship_2 was NOT sticky when selection cleared!");
                    return false;
                }
                // Click player ship sim_ship_0 to deploy -> OTC becomes sticky on player character sim_ship_0!
                fdd.selectedMembers.add(allMembers.get(0));
                fcPanel.advance(0.1f);
                if (!"sim_ship_0".equals(divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup))
                        || !divisionai.DivisionOtcCommandManager.isPlayerShipStickyOtcActive()) {
                    System.out.println("FAIL Pre-battle OTC did not switch to player ship sim_ship_0 when chosen to deploy");
                    return false;
                }
                // Even if player ship is unselected from fdd.selectedMembers while other ships remain selected, player OTC stays sticky!
                fdd.selectedMembers.remove(allMembers.get(0));
                fcPanel.advance(0.1f);
                if (!"sim_ship_0".equals(divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup))) {
                    System.out.println("FAIL Pre-battle player OTC was not sticky after player ship was chosen to deploy");
                    return false;
                }
                fdd.selectedMembers.clear();
                fcPanel.advance(0.1f);
            }
            changeOtcButton.setChecked(true);
            fcPanel.advance(0.1f);
            // Simulate CombatState calling organizer.advance(engine) on subsequent frames while OTC window is open
            // (regression test for Bug B: must never swap deployedGrid/reservesGrid or clear reservesGrid icons!)
            organizer.advance(proxyEngine);
            for (FleetMember m : reserveMembers) {
                if (reservesGrid.getIconForMember(m) == null) {
                    System.out.println("FAIL Bug B regression: reservesGrid.getIconForMember(" + m.getId() + ") became null while OTC window open!");
                    return false;
                }
            }
            CustomPanelAPI otcWin = organizer.getActiveOtcSelectorPanel();
            if (!organizer.isOtcSelectorWindowOpen() || otcWin == null) {
                System.out.println("FAIL clicking 'Change' did not open OTC Commander Selection Window");
                return false;
            }
            List<LabelAPI> winLabels = new ArrayList<>();
            List<ButtonAPI> winButtons = new ArrayList<>();
            collectLabelsAndButtonsRecursive(otcWin, winLabels, winButtons);
            // Expect 1 'Close' button + 4 commander card buttons (sim_ship_0 flagship, sim_ship_2 subordinate in div_1,
            // sim_ship_6 div_2 leader, sim_ship_11 unassigned commander)
            if (winButtons.size() != 5) {
                System.out.println("FAIL OTC Selection Window button count: got " + winButtons.size() + " expected 5");
                return false;
            }
            boolean sawPersonalityLabel = false;
            boolean sawUndeployedWarningHeader = false;
            for (LabelAPI wl : winLabels) {
                String wTxt = wl.getText();
                if (wTxt == null || wTxt.isEmpty()) continue;
                if (wTxt.contains("Personality:")) {
                    System.out.println("FAIL Feature B: redundant 'Personality:' prefix must be removed from commander cards: " + wTxt);
                    return false;
                }
                if ("Steady".equals(wTxt) || "Alpha Core".equals(wTxt) || "Aggressive".equals(wTxt) || "Reckless".equals(wTxt)) {
                    sawPersonalityLabel = true;
                }
                if (wTxt.contains("-75% Max CP")) {
                    sawUndeployedWarningHeader = true;
                }
                if (!isPurePrintableAscii(wTxt)) {
                    System.out.println("FAIL non-ascii label in OTC Selection Window: " + wTxt);
                    return false;
                }
                PositionAPI wlp = wl.getPosition();
                if (wlp != null && wl.computeTextWidth(wTxt) > wlp.getWidth() + 2f) {
                    System.out.println("FAIL OTC Selection Window label overflow: '" + wTxt + "' textW=" + wl.computeTextWidth(wTxt) + " w=" + wlp.getWidth());
                    return false;
                }
            }
            if (!sawPersonalityLabel) {
                System.out.println("FAIL Change D: OTC Selection Window cards missing commander personality description");
                return false;
            }
            if (!sawUndeployedWarningHeader) {
                System.out.println("FAIL Feature E: OTC Selection Window missing undeployed OTC -75% Max CP warning");
                return false;
            }
            // Select the non-flagship subordinate commander in Division 1 (sim_ship_2, Lieutenant Mira)
            // Works even when expectAlphaCore == true (Feature A: player may freely un-pick Alpha Core before battle!)
            ButtonAPI subSelectBtn = winButtons.get(2); // index 0 = Close, 1 = sim_ship_0 (Active OTC), 2 = sim_ship_2 (Select)
            if (!"Select".equals(subSelectBtn.getText()) || !subSelectBtn.isEnabled()) {
                System.out.println("FAIL subordinate commander Select button invalid: " + subSelectBtn.getText());
                return false;
            }
            subSelectBtn.setChecked(true);
            fcPanel.advance(0.1f);
            String newOtcId = divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup);
            if (!"sim_ship_2".equals(newOtcId) || organizer.isOtcSelectorWindowOpen()) {
                System.out.println("FAIL selecting subordinate commander sim_ship_2 in OTC Window: got " + newOtcId
                        + " windowOpen=" + organizer.isOtcSelectorWindowOpen());
                return false;
            }
            if (!expectAlphaCore) {
                // Verify explicit player choice sim_ship_2 is now sticky even when player ship sim_ship_0 is clicked to deploy!
                fdd.selectedMembers.add(allMembers.get(0));
                fdd.selectedMembers.add(allMembers.get(11));
                fcPanel.advance(0.1f);
                if (!"sim_ship_2".equals(divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup))) {
                    System.out.println("FAIL explicit player OTC choice sim_ship_2 was not sticky when clicking divisions/player ship to deploy");
                    return false;
                }
                fdd.selectedMembers.clear();
                fcPanel.advance(0.1f);
            }
            if (expectAlphaCore && playerGroup.useConvergence) {
                System.out.println("FAIL Feature A: un-picking Alpha Core before battle did not clear playerGroup.useConvergence");
                return false;
            }
            // Re-open the OTC Selection Window and restore sim_ship_0 (re-picking Alpha Core if expectAlphaCore == true)
            changeOtcButton.setChecked(true);
            fcPanel.advance(0.1f);
            CustomPanelAPI otcWin2 = organizer.getActiveOtcSelectorPanel();
            List<LabelAPI> winLabels2 = new ArrayList<>();
            List<ButtonAPI> winButtons2 = new ArrayList<>();
            collectLabelsAndButtonsRecursive(otcWin2, winLabels2, winButtons2);
            winButtons2.get(1).setChecked(true); // sim_ship_0
            fcPanel.advance(0.1f);
            organizer.advance(proxyEngine);
            for (FleetMember m : reserveMembers) {
                if (reservesGrid.getIconForMember(m) == null) {
                    System.out.println("FAIL Bug B regression: reservesGrid.getIconForMember(" + m.getId() + ") null after closing OTC window!");
                    return false;
                }
            }
            String backToFirstId = divisionai.DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup);
            if (!"sim_ship_0".equals(backToFirstId) || organizer.isOtcSelectorWindowOpen()) {
                System.out.println("FAIL selecting sim_ship_0 in OTC Window did not restore initial OTC: got " + backToFirstId);
                return false;
            }
            if (expectAlphaCore && !playerGroup.useConvergence) {
                System.out.println("FAIL Feature A: re-picking Alpha Core before battle did not restore playerGroup.useConvergence");
                return false;
            }

            // Verify Fighting Style strip + Customize Modal + strict Campaign non-mutation invariant!
            PermanentDivisionManager.FleetDoctrineConfig campaignDoc = PermanentDivisionManager.getFleetDoctrine();
            PermanentDivisionManager.OtcAuthority origAuth = campaignDoc.getOtcAuthority();
            PermanentDivisionManager.FleetWideFormation origFw = campaignDoc.getFleetWideFormation();
            PermanentDivisionManager.OperationalAnchor origAnchor = campaignDoc.getOperationalAnchor();
            PermanentDivisionManager.ObjectiveCapturePolicy origPol = campaignDoc.getObjectiveCapturePolicy();
            PermanentDivisionManager.DecisiveArm origArm = campaignDoc.getDecisiveArm();
            PermanentDivisionManager.EngagementDiscipline origDisc = campaignDoc.getEngagementDiscipline();
            boolean origOrch = campaignDoc.isOrchestratedAdvance();

            DivisionDeploymentDialogOrganizer.selectOtcAuthority(playerGroup, 1);
            DivisionDeploymentDialogOrganizer.selectFleetFormation(playerGroup, 1);
            DivisionDeploymentDialogOrganizer.selectFightingStyle(playerGroup, 1);
            fcPanel.advance(0.1f);

            if (campaignDoc.getOtcAuthority() != origAuth
                    || campaignDoc.getFleetWideFormation() != origFw
                    || campaignDoc.getOperationalAnchor() != origAnchor
                    || campaignDoc.getObjectiveCapturePolicy() != origPol
                    || campaignDoc.getDecisiveArm() != origArm
                    || campaignDoc.getEngagementDiscipline() != origDisc
                    || campaignDoc.isOrchestratedAdvance() != origOrch) {
                System.out.println("FAIL Deployment Screen overrides mutated Campaign FleetDoctrineConfig!");
                return false;
            }
            if (playerGroup.authority == origAuth || playerGroup.fleetWideFormation == origFw) {
                System.out.println("FAIL Deployment Screen cyclers did not update battle-local playerGroup!");
                return false;
            }

            ButtonAPI customizeBtn = null;
            for (ButtonAPI btn : fcButtons) {
                if ("Customize".equals(btn.getText())) {
                    customizeBtn = btn;
                    break;
                }
            }
            if (customizeBtn == null) {
                System.out.println("FAIL missing 'Customize' button under FIGHTING STYLE");
                return false;
            }
            customizeBtn.setChecked(true);
            fcPanel.advance(0.1f);
            organizer.advance(proxyEngine);
            CustomPanelAPI styleWin = organizer.getActiveFightingStylePanel();
            if (!organizer.isFightingStyleWindowOpen() || styleWin == null) {
                System.out.println("FAIL clicking 'Customize' did not open FightingStyleModalDialog");
                return false;
            }
            for (FleetMember m : reserveMembers) {
                if (reservesGrid.getIconForMember(m) == null) {
                    System.out.println("FAIL reservesGrid icon became null while FightingStyleModalDialog open!");
                    return false;
                }
            }
            List<LabelAPI> fsLabels = new ArrayList<>();
            List<ButtonAPI> fsButtons = new ArrayList<>();
            collectLabelsAndButtonsRecursive(styleWin, fsLabels, fsButtons);
            for (LabelAPI fsl : fsLabels) {
                String fsTxt = fsl.getText();
                if (fsTxt == null || fsTxt.isEmpty()) continue;
                if (!isPurePrintableAscii(fsTxt)) {
                    System.out.println("FAIL non-ascii label in FightingStyleModalDialog: " + fsTxt);
                    return false;
                }
                PositionAPI fsp = fsl.getPosition();
                if (fsp != null && fsl.computeTextWidth(fsTxt) > fsp.getWidth() + 2f) {
                    System.out.println("FAIL FightingStyleModalDialog label overflow: '" + fsTxt
                            + "' textW=" + fsl.computeTextWidth(fsTxt) + " w=" + fsp.getWidth());
                    return false;
                }
            }
            // 1) Click "Light & Strike Warfare" inside Customize modal via CustomUIPanelPlugin.buttonPressed
            //    and verify both playerGroup and FleetCommandPanel update to "Light & Strike Warfare" without touching Campaign!
            ButtonAPI lightStrikeBtn = null;
            for (ButtonAPI b : fsButtons) {
                if ("Light & Strike Warfare".equals(b.getText())) {
                    lightStrikeBtn = b;
                    break;
                }
            }
            if (lightStrikeBtn == null) {
                System.out.println("FAIL missing 'Light & Strike Warfare' button in FightingStyleModalDialog");
                return false;
            }
            if (styleWin.getPlugin() != null) {
                styleWin.getPlugin().buttonPressed("TPL:LIGHT_FORCES_TORPEDO");
            } else {
                lightStrikeBtn.setChecked(true);
            }
            fcPanel.advance(0.1f);
            if (playerGroup.resolveMatchingOperationalTemplate()
                    != PermanentDivisionManager.OperationalTemplate.LIGHT_FORCES_TORPEDO
                    || campaignDoc.getOperationalAnchor() != origAnchor
                    || campaignDoc.getDecisiveArm() != origArm) {
                System.out.println("FAIL selecting 'Light & Strike Warfare' in Customize modal did not apply or leaked into campaign!");
                return false;
            }
            List<LabelAPI> checkFcLabels = new ArrayList<>();
            List<ButtonAPI> checkFcButtons = new ArrayList<>();
            collectLabelsAndButtonsRecursive(fcPanel, checkFcLabels, checkFcButtons);
            boolean sawLightStrikeLabelOnFc = false;
            for (LabelAPI l : checkFcLabels) {
                if (l.getText() != null && l.getText().contains("Light & Strike Warfare")) {
                    sawLightStrikeLabelOnFc = true;
                    break;
                }
            }
            if (!sawLightStrikeLabelOnFc) {
                System.out.println("FAIL FleetCommandPanel did not update Conduct of Battle label to 'Light & Strike Warfare'");
                return false;
            }

            fsLabels.clear();
            fsButtons.clear();
            collectLabelsAndButtonsRecursive(styleWin, fsLabels, fsButtons);

            // 2) Click "Direct Orders Only" pill to verify granular custom battle style, then "Campaign Default", then "Close"
            ButtonAPI directOrdersBtn = null;
            for (ButtonAPI b : fsButtons) {
                if ("Direct Orders Only".equals(b.getText())) {
                    directOrdersBtn = b;
                    break;
                }
            }
            if (directOrdersBtn == null) {
                System.out.println("FAIL missing 'Direct Orders Only' pill button in FightingStyleModalDialog");
                return false;
            }
            directOrdersBtn.setChecked(true);
            fcPanel.advance(0.1f);
            if (playerGroup.objectiveCapturePolicy != PermanentDivisionManager.ObjectiveCapturePolicy.DIRECT_ORDERS_ONLY
                    || playerGroup.resolveMatchingOperationalTemplate() != null
                    || campaignDoc.getObjectiveCapturePolicy() != origPol) {
                System.out.println("FAIL granular axis edit in FightingStyleModalDialog failed or leaked into campaign!");
                return false;
            }

            fsLabels.clear();
            fsButtons.clear();
            collectLabelsAndButtonsRecursive(styleWin, fsLabels, fsButtons);
            ButtonAPI resetCampBtn = null;
            ButtonAPI closeFsBtn = null;
            for (ButtonAPI b : fsButtons) {
                if ("Campaign Default".equals(b.getText())) resetCampBtn = b;
                if ("Close".equals(b.getText())) closeFsBtn = b;
            }
            if (resetCampBtn == null || closeFsBtn == null) {
                System.out.println("FAIL missing 'Campaign Default' or 'Close' button in FightingStyleModalDialog");
                return false;
            }
            resetCampBtn.setChecked(true);
            fcPanel.advance(0.1f);
            if (playerGroup.authority != origAuth
                    || playerGroup.fleetWideFormation != origFw
                    || playerGroup.objectiveCapturePolicy != origPol) {
                System.out.println("FAIL 'Campaign Default' did not restore inherited campaign settings on playerGroup!");
                return false;
            }

            fsLabels.clear();
            fsButtons.clear();
            collectLabelsAndButtonsRecursive(styleWin, fsLabels, fsButtons);
            for (ButtonAPI b : fsButtons) {
                if ("Close".equals(b.getText())) closeFsBtn = b;
            }
            closeFsBtn.setChecked(true);
            fcPanel.advance(0.1f);
            organizer.advance(proxyEngine);
            if (organizer.isFightingStyleWindowOpen()) {
                System.out.println("FAIL 'Close' did not close FightingStyleModalDialog");
                return false;
            }
        } else {
            // Verify Conduct of Battle is strictly pre-battle only (read-only mid-battle)
            for (ButtonAPI btn : fcButtons) {
                if ("Customize".equals(btn.getText())) {
                    System.out.println("FAIL 'Customize' button must not appear mid-battle");
                    return false;
                }
            }
            if (organizer.isFightingStyleWindowOpen()) {
                System.out.println("FAIL Conduct of Battle modal must not be open mid-battle");
                return false;
            }
        }
        if (expectAlphaCore && (alphaMasterLabel == null || alphaShipLineLabel == null
                || alphaShipLineLabel.getPosition().getY() >= alphaMasterLabel.getPosition().getY() - 10f)) {
            System.out.println("FAIL Alpha Core OTC is not typeset as a separate two-line title");
            return false;
        }

        float fcX = fcPos.getX();
        float fcW = fcPos.getWidth();
        float divX = fcX + Math.round(fcW * 0.385f);
        for (LabelAPI lbl : fcLabels) {
            String txt = lbl.getText();
            if (txt == null || txt.isEmpty()) continue;
            if (!isPurePrintableAscii(txt)) {
                System.out.println("FAIL non-ascii fcLabel: " + txt);
                return false;
            }
            String fontPath = DivisionDeploymentDialogOrganizer.getLabelFontPath(lbl);
            if (fontPath != null && (fontPath.contains("insignia") || fontPath.contains("default"))) {
                System.out.println("FAIL fcLabel uses comic-style font: '" + txt + "' font=" + fontPath);
                return false;
            }
            PositionAPI lp = lbl.getPosition();
            if (lp == null) continue;
            // Must fit within fleetCommandPanel bounds
            if (lp.getX() < fcX - 2f || (lp.getX() + lp.getWidth()) > (fcX + fcW + 2f)) {
                System.out.println("FAIL fcLabel out of fc bounds: '" + txt + "' x=" + lp.getX() + " w=" + lp.getWidth() + " fcX=" + fcX + " fcW=" + fcW);
                return false;
            }
            // Left section labels (excluding top banner) must not cross divX into right section
            boolean isTopBanner = txt.startsWith("FLEET COMMAND");
            if (!isTopBanner && lp.getX() < divX && (lp.getX() + lp.getWidth()) > divX + 2f) {
                System.out.println("FAIL fcLabel crosses divX: '" + txt + "' x=" + lp.getX() + " w=" + lp.getWidth() + " divX=" + divX);
                return false;
            }
            // Rendered .fnt width must fit inside the label's bounding width without wrapping
            float renderedW = lbl.computeTextWidth(txt);
            if (renderedW > lp.getWidth() + 2f) {
                System.out.println("FAIL fcLabel renderedW > width: '" + txt + "' renderedW=" + renderedW + " w=" + lp.getWidth());
                return false;
            }
            // Must not vertically collide with any other distinct label in fcPanel
            for (LabelAPI other : fcLabels) {
                if (other == lbl) continue;
                String oTxt = other.getText();
                if (oTxt == null || oTxt.isEmpty()) continue;
                if ("MASTER".equals(txt) && "MASTER".equals(oTxt)) continue; // intentional ghost chromatic shadow
                PositionAPI op = other.getPosition();
                if (op == null) continue;
                if (Math.abs(lp.getX() - op.getX()) < 4f && lp.getY() < op.getY() + op.getHeight() && lp.getY() + lp.getHeight() > op.getY()) {
                    System.out.println("FAIL fcLabel overlap: '" + txt + "' overlaps '" + oTxt + "'");
                    return false;
                }
            }
        }

        // Exercise the real arrow buttons and Orchestrated Advance toggle through CustomPanelPlugin.advance
        if (preBattle) {
        PermanentDivisionManager.FleetWideFormation initialFw =
                PermanentDivisionManager.getFleetDoctrine().getFleetWideFormation();
        if (initialFw != PermanentDivisionManager.FleetWideFormation.DOCTRINAL_AUTO) {
            System.out.println("FAIL Bug A regression: initial fleet formation should be DOCTRINAL_AUTO, got " + initialFw);
            return false;
        }
        boolean autoShownFirst = false;
        for (LabelAPI lbl : fcLabels) {
            if (PermanentDivisionManager.FleetWideFormation.DOCTRINAL_AUTO.displayName.equals(lbl.getText())) {
                autoShownFirst = true;
            }
        }
        if (!autoShownFirst) {
            System.out.println("FAIL Bug A regression: 'Auto' not shown first when entering battle!");
            return false;
        }
        List<ButtonAPI> prevBtns = new ArrayList<>();
        List<ButtonAPI> nextBtns = new ArrayList<>();
        ButtonAPI orchestratedAdvanceButton = null;
        for (ButtonAPI btn : fcButtons) {
            if ("<".equals(btn.getText())) prevBtns.add(btn);
            if (">".equals(btn.getText())) nextBtns.add(btn);
            if (btn.getText() != null && btn.getText().startsWith("Orchestrated Advance:")) {
                orchestratedAdvanceButton = btn;
            }
            if (btn.getText() != null && btn.getText().startsWith("Formation entry:")) {
                System.out.println("FAIL Feature B: 'Formation entry' button should be removed!");
                return false;
            }
        }
        prevBtns.sort((a, b) -> Float.compare(a.getPosition().getX(), b.getPosition().getX()));
        nextBtns.sort((a, b) -> Float.compare(a.getPosition().getX(), b.getPosition().getX()));
        if (prevBtns.size() != 3 || nextBtns.size() != 3 || orchestratedAdvanceButton == null) {
            System.out.println("FAIL missing 3-column command/fighting/formation arrow or Orchestrated Advance buttons");
            return false;
        }
        ButtonAPI prevAuthButton = prevBtns.get(0);
        ButtonAPI nextAuthButton = nextBtns.get(0);
        ButtonAPI prevStyleButton = prevBtns.get(1);
        ButtonAPI nextStyleButton = nextBtns.get(1);
        ButtonAPI prevFormationButton = prevBtns.get(2);
        ButtonAPI nextFormationButton = nextBtns.get(2);

        PermanentDivisionManager.OtcAuthority campaignAuth =
                PermanentDivisionManager.getFleetDoctrine().getOtcAuthority();
        PermanentDivisionManager.OtcAuthority initialAuth = playerGroup.authority;
        nextAuthButton.setChecked(true);
        fcPanel.advance(0.1f);
        PermanentDivisionManager.OtcAuthority[] authValues = PermanentDivisionManager.OtcAuthority.values();
        PermanentDivisionManager.OtcAuthority expectedAuth =
                authValues[(initialAuth.ordinal() + 1) % authValues.length];
        if (playerGroup.authority != expectedAuth
                || PermanentDivisionManager.getFleetDoctrine().getOtcAuthority() != campaignAuth) {
            System.out.println("FAIL pre-battle next command style button did not update battle group or mutated campaign: group="
                    + playerGroup.authority + " expected=" + expectedAuth
                    + " campaign=" + PermanentDivisionManager.getFleetDoctrine().getOtcAuthority());
            return false;
        }
        prevAuthButton.setChecked(true);
        fcPanel.advance(0.1f);
        if (playerGroup.authority != initialAuth
                || PermanentDivisionManager.getFleetDoctrine().getOtcAuthority() != campaignAuth) {
            System.out.println("FAIL pre-battle prev command style button did not restore initial authority");
            return false;
        }

        PermanentDivisionManager.OperationalTemplate initialStyle = playerGroup.resolveMatchingOperationalTemplate();
        PermanentDivisionManager.OperationalAnchor campaignAnchor =
                PermanentDivisionManager.getFleetDoctrine().getOperationalAnchor();
        nextStyleButton.setChecked(true);
        fcPanel.advance(0.1f);
        PermanentDivisionManager.OperationalTemplate[] styleValues =
                PermanentDivisionManager.OperationalTemplate.values();
        PermanentDivisionManager.OperationalTemplate expectedStyle =
                styleValues[((initialStyle != null ? initialStyle.ordinal() : 0) + 1) % styleValues.length];
        if (playerGroup.resolveMatchingOperationalTemplate() != expectedStyle
                || PermanentDivisionManager.getFleetDoctrine().getOperationalAnchor() != campaignAnchor) {
            System.out.println("FAIL pre-battle next fighting style button did not update battle group or mutated campaign");
            return false;
        }
        prevStyleButton.setChecked(true);
        fcPanel.advance(0.1f);
        if (playerGroup.resolveMatchingOperationalTemplate() != initialStyle) {
            System.out.println("FAIL pre-battle prev fighting style button did not restore initial style");
            return false;
        }

        PermanentDivisionManager.FleetWideFormation campaignFw =
                PermanentDivisionManager.getFleetDoctrine().getFleetWideFormation();
        nextFormationButton.setChecked(true);
        fcPanel.advance(0.1f);
        PermanentDivisionManager.FleetWideFormation[] fwValues =
                PermanentDivisionManager.FleetWideFormation.values();
        if (fwValues.length != 12) {
            System.out.println("FAIL Feature A: expected 12 FleetWideFormations, got " + fwValues.length);
            return false;
        }
        PermanentDivisionManager.FleetWideFormation expectedFw =
                fwValues[(initialFw.ordinal() + 1) % fwValues.length];
        boolean formationLabelUpdated = false;
        for (LabelAPI lbl : fcLabels) {
            if (expectedFw.displayName.equals(lbl.getText())) formationLabelUpdated = true;
        }
        if (PermanentDivisionManager.getFleetDoctrine().getFleetWideFormation() != campaignFw
                || playerGroup.fleetWideFormation != expectedFw
                || playerGroup.activeFleetWideFormation != expectedFw
                || !formationLabelUpdated) {
            System.out.println("FAIL next fleet formation button failed battle update or mutated campaign: initial=" + initialFw
                    + " expected=" + expectedFw
                    + " campaign=" + PermanentDivisionManager.getFleetDoctrine().getFleetWideFormation()
                    + " group=" + playerGroup.fleetWideFormation
                    + " active=" + playerGroup.activeFleetWideFormation
                    + " labelUpdated=" + formationLabelUpdated);
            return false;
        }
        prevFormationButton.setChecked(true);
        fcPanel.advance(0.1f);
        if (PermanentDivisionManager.getFleetDoctrine().getFleetWideFormation() != campaignFw
                || playerGroup.fleetWideFormation != initialFw) {
            System.out.println("FAIL previous fleet formation button did not restore initial formation");
            return false;
        }
        boolean campaignOrch = PermanentDivisionManager.getFleetDoctrine().isOrchestratedAdvance();
        boolean orchBefore = playerGroup.orchestratedAdvance;
        orchestratedAdvanceButton.setChecked(true);
        fcPanel.advance(0.1f);
        if (PermanentDivisionManager.getFleetDoctrine().isOrchestratedAdvance() != campaignOrch
                || playerGroup.orchestratedAdvance == orchBefore) {
            System.out.println("FAIL Orchestrated Advance toggle failed battle update or mutated campaign");
            return false;
        }
        // Restore Orchestrated Advance to On
        orchestratedAdvanceButton.setChecked(true);
        fcPanel.advance(0.1f);
        }

        // 3. Audit every division row header card in reservesGrid
        com.fs.starfarer.coreui.w rList = reservesGrid.getList();
        List<c> items = rList.getItems();
        if (items.size() != 48) {
            System.out.println("FAIL items size: " + items.size()
                    + " preBattle=" + preBattle
                    + " expectAlpha=" + expectAlphaCore
                    + " nonEmptyDivs=" + PermanentDivisionManager.getNonEmptyPermanentDivisions().size()
                    + " reserveMembers=" + reserveMembers.size()
                    + " gridMembers=" + reservesGrid.getMembers().size());
            return false;
        }

        int[] headerSlots = new int[]{0, 16, 32};
        float prevCardBottomY = Float.MAX_VALUE;
        for (int slotIdx : headerSlots) {
            c item = items.get(slotIdx);
            if (!(item instanceof CustomPanelAPI)) {
                System.out.println("FAIL slot " + slotIdx + " not CustomPanelAPI: " + item);
                return false;
            }
            CustomPanelAPI card = (CustomPanelAPI) item;
            PositionAPI cp = card.getPosition();
            if (Math.abs(cp.getWidth() - 113f) > 1f || Math.abs(cp.getHeight() - 56f) > 1f) {
                System.out.println("FAIL card size: " + cp.getWidth() + "x" + cp.getHeight());
                return false;
            }
            // Must not overlap previous row header card vertically
            if (cp.getY() + cp.getHeight() > prevCardBottomY + 0.5f) {
                System.out.println("FAIL card vertical overlap: y=" + cp.getY() + " h=" + cp.getHeight() + " prevY=" + prevCardBottomY);
                return false;
            }
            prevCardBottomY = cp.getY();

            // First ship cell in this row is at slotIdx + 2
            c firstShipCell = items.get(slotIdx + 2);
            float firstShipX = firstShipCell.getPosition().getX();
            if (cp.getX() + cp.getWidth() > firstShipX + 0.1f) {
                System.out.println("FAIL card overlaps firstShipX: cardRight=" + (cp.getX() + cp.getWidth()) + " firstShipX=" + firstShipX);
                return false;
            }

            List<LabelAPI> cardLabels = new ArrayList<>();
            List<ButtonAPI> cardButtons = new ArrayList<>();
            collectLabelsAndButtonsRecursive(card, cardLabels, cardButtons);
            for (LabelAPI cl : cardLabels) {
                String txt = cl.getText();
                if (txt == null || txt.isEmpty()) continue;
                if (!isPurePrintableAscii(txt)) {
                    System.out.println("FAIL card non-ascii: " + txt);
                    return false;
                }
                PositionAPI clp = cl.getPosition();
                // Must stay strictly inside card and left of firstShipX
                if ((clp.getX() + clp.getWidth()) > (cp.getX() + cp.getWidth() + 0.5f)) {
                    System.out.println("FAIL cardLabel right overflow: '" + txt + "' clRight=" + (clp.getX() + clp.getWidth()) + " cardRight=" + (cp.getX() + cp.getWidth()));
                    return false;
                }
                if (cl.computeTextWidth(txt) > clp.getWidth() + 2f) {
                    System.out.println("FAIL cardLabel textWidth > width: '" + txt + "' textW=" + cl.computeTextWidth(txt) + " w=" + clp.getWidth());
                    return false;
                }
            }
            if (cardButtons.size() != 1) {
                System.out.println("FAIL cardButtons size: " + cardButtons.size());
                return false;
            }
            ButtonAPI selBtn = cardButtons.get(0);
            PositionAPI bp = selBtn.getPosition();
            if ((bp.getX() + bp.getWidth()) > (cp.getX() + cp.getWidth() + 0.5f)) {
                System.out.println("FAIL selBtn right overflow");
                return false;
            }
        }

        // 4. Verify S.getButtonForMember, S.getIconForMember, 16-column grid sync, and IndexedMemberList click index mapping
        if (rList.getColumns() != 16) {
            System.out.println("FAIL rList.getColumns(): " + rList.getColumns());
            return false;
        }
        DivisionDeploymentDialogOrganizer.IndexedMemberList indexedList = organizer.getLastIndexedReserveMembers();
        if (indexedList == null || indexedList.size() != reserveMembers.size()) {
            System.out.println("FAIL indexedList: " + (indexedList == null ? "null" : indexedList.size()) + " vs " + reserveMembers.size());
            return false;
        }
        for (FleetMember m : reserveMembers) {
            if (reservesGrid.getButtonForMember(m) == null || reservesGrid.getIconForMember(m) == null) {
                System.out.println("FAIL getButtonForMember/getIconForMember for " + m.getId());
                return false;
            }
            int slot = indexedList.getSlotForMember(m);
            if (slot < 0 || indexedList.getMemberAtSlot(slot) != m) {
                System.out.println("FAIL slot mapping for " + m.getId() + " slot=" + slot);
                return false;
            }
        }

        if (!assertPositionTreeInvariantsAndRecompute(fdd.getPosition())) {
            System.out.println("FAIL fdd position tree sibling-anchor invariant or recompute");
            return false;
        }
        if (!verifyAttachedTooltipsInTree(fdd, 5)) {
            System.out.println("FAIL fdd attached tooltips verification");
            return false;
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean simulatePursuitDeploymentWindow1To1(O00o hullSpec, final PersonAPI flagshipCaptain) throws Throwable {
        divisionai.DivisionOtcCommandManager.resetForNewBattle();
        final List<FleetMember> reserveMembers = new ArrayList<>();
        // Div 1: Mixed Light Cruiser Division (1 Cruiser Heron + 1 Destroyer Shrike + 3 Frigates Omen/Wolf/Wolf)
        SimFleetMember mHeron = SimFleetMember.create("p_heron", "ISS Heron", 20f, true, HullSize.CRUISER, hullSpec, flagshipCaptain);
        SimFleetMember mShrike = SimFleetMember.create("p_shrike", "ISS Shrike", 8f, false, HullSize.DESTROYER, hullSpec, null);
        SimFleetMember mOmen = SimFleetMember.create("p_omen", "ISS Omen", 6f, false, HullSize.FRIGATE, hullSpec, null);
        SimFleetMember mWolf1 = SimFleetMember.create("p_wolf1", "ISS Wolf 1", 5f, false, HullSize.FRIGATE, hullSpec, null);
        SimFleetMember mWolf2 = SimFleetMember.create("p_wolf2", "ISS Wolf 2", 5f, false, HullSize.FRIGATE, hullSpec, null);
        // Div 2: Pure Destroyer Division (4 Destroyers — no Frigates)
        SimFleetMember mMedusa = SimFleetMember.create("p_medusa", "ISS Medusa", 12f, false, HullSize.DESTROYER, hullSpec, null);
        SimFleetMember mEnforcer = SimFleetMember.create("p_enforcer", "ISS Enforcer", 9f, false, HullSize.DESTROYER, hullSpec, null);
        SimFleetMember mManticore = SimFleetMember.create("p_manticore", "ISS Manticore", 11f, false, HullSize.DESTROYER, hullSpec, null);
        SimFleetMember mSunder = SimFleetMember.create("p_sunder", "ISS Sunder", 11f, false, HullSize.DESTROYER, hullSpec, null);
        // Div 3: Pure Frigate Division (3 Frigates)
        SimFleetMember mLasher1 = SimFleetMember.create("p_lasher1", "ISS Lasher 1", 4f, false, HullSize.FRIGATE, hullSpec, null);
        SimFleetMember mLasher2 = SimFleetMember.create("p_lasher2", "ISS Lasher 2", 4f, false, HullSize.FRIGATE, hullSpec, null);
        SimFleetMember mCenturion = SimFleetMember.create("p_centurion", "ISS Centurion", 7f, false, HullSize.FRIGATE, hullSpec, null);

        reserveMembers.addAll(Arrays.asList(
                mHeron, mShrike, mOmen, mWolf1, mWolf2,
                mMedusa, mEnforcer, mManticore, mSunder,
                mLasher1, mLasher2, mCenturion));

        final Map<String, Object> pdata = new HashMap<>();
        FleetDataAPI fleetData = (FleetDataAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{FleetDataAPI.class},
                (p, m, a) -> "getMembersListCopy".equals(m.getName()) ? new ArrayList<>(reserveMembers) : null);
        final FactionAPI playerFaction = (FactionAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{FactionAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getId".equals(n)) return "player";
                    if ("getDisplayName".equals(n)) return "Player";
                    if (m.getReturnType() == Color.class) return new Color(110, 210, 255, 255);
                    if (m.getReturnType() == boolean.class) return false;
                    return null;
                });
        CampaignFleetAPI playerFleet = (CampaignFleetAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (p, m, a) -> {
                    if ("getFleetData".equals(m.getName())) return fleetData;
                    if ("getFlagship".equals(m.getName())) return mHeron;
                    if ("getCommander".equals(m.getName())) return flagshipCaptain;
                    if ("getFaction".equals(m.getName())) return playerFaction;
                    if (m.getReturnType() == boolean.class) return false;
                    return null;
                });
        SectorAPI sector = (SectorAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class},
                (p, m, a) -> {
                    if ("getPersistentData".equals(m.getName())) return pdata;
                    if ("getPlayerFleet".equals(m.getName())) return playerFleet;
                    if ("getPlayerFaction".equals(m.getName()) || "getFaction".equals(m.getName())) return playerFaction;
                    if ("getPlayerPerson".equals(m.getName())) return flagshipCaptain;
                    if (m.getReturnType() == boolean.class) return false;
                    return null;
                });
        Global.setSector(sector);

        PermanentDivisionSpec div1 = new PermanentDivisionSpec("p_div_1", "1st Light Cruiser", DivisionArchetype.CRUISER_DIVISION);
        div1.flagshipMemberId = "p_heron";
        div1.memberIds.addAll(Arrays.asList("p_heron", "p_shrike", "p_omen", "p_wolf1", "p_wolf2"));

        PermanentDivisionSpec div2 = new PermanentDivisionSpec("p_div_2", "2nd Destroyer Screen", DivisionArchetype.DESTROYER_DIVISION);
        div2.flagshipMemberId = "p_medusa";
        div2.memberIds.addAll(Arrays.asList("p_medusa", "p_enforcer", "p_manticore", "p_sunder"));

        PermanentDivisionSpec div3 = new PermanentDivisionSpec("p_div_3", "3rd Frigate Flotilla", DivisionArchetype.FRIGATE_DIVISION);
        div3.flagshipMemberId = "p_lasher1";
        div3.memberIds.addAll(Arrays.asList("p_lasher1", "p_lasher2", "p_centurion"));

        Method saveSpecsM = PermanentDivisionManager.class.getDeclaredMethod("saveSpecsToPersistentData", Map.class, List.class);
        saveSpecsM.setAccessible(true);
        saveSpecsM.invoke(null, pdata, Arrays.asList(div1, div2, div3));

        Constructor<z> panelCtor = z.class.getDeclaredConstructor();
        Constructor<SimFdd> fddCtor = (Constructor<SimFdd>) ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(SimFdd.class, panelCtor);
        SimFdd fdd = fddCtor.newInstance();
        fdd.selectedMembers = new ArrayList<>();

        z innerPanel = new z();
        fdd.setSize(624f, 460f);
        innerPanel.setSize(604f, 440f);
        fdd.add(innerPanel).inTL(10f, 10f);

        supersuper deployedGrid = buildRealShipGrid(fdd, 592f, 174f, 10, new ArrayList<FleetMember>());
        supersuper reservesGrid = buildRealShipGrid(fdd, 592f, 174f, 10, reserveMembers);
        SimCombatFleetManager simPlayerFm = allocateWithoutConstructor(SimCombatFleetManager.class);
        simPlayerFm.simOwner = 0;
        simPlayerFm.simGoal = com.fs.starfarer.api.fleet.FleetGoal.ATTACK;
        simPlayerFm.simMaxStrength = 240;
        simPlayerFm.simCommander = flagshipCaptain;
        SimCombatFleetManager simEnemyFm = allocateWithoutConstructor(SimCombatFleetManager.class);
        simEnemyFm.simOwner = 1;
        simEnemyFm.simGoal = com.fs.starfarer.api.fleet.FleetGoal.ESCAPE;
        simEnemyFm.simMaxStrength = 240;
        CombatEngine simEngine = allocateWithoutConstructor(CombatEngine.class);
        for (Field ef : CombatEngine.class.getDeclaredFields()) {
            if ("interface.new".equals(ef.getName())) {
                ef.setAccessible(true);
                ef.set(simEngine, Arrays.asList(simPlayerFm, simEnemyFm));
            }
        }
        SimProgressBar simBar = allocateWithoutConstructor(SimProgressBar.class);
        SimButton dummyBtn = new SimButton(100f, 24f, "Deploy", null);

        for (Field f : oO0O.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getName().equals("interface.null$Object")) f.setInt(fdd, 0);
            else if (f.getName().equals("while.null$Object")) f.setBoolean(fdd, false);
            else if (f.getType() == CombatEngine.class) f.set(fdd, simEngine);
            else if (f.getType() == com.fs.starfarer.combat.CombatFleetManager.class) f.set(fdd, simPlayerFm);
            else if (f.getType() == com.fs.starfarer.campaign.ui.N.class) f.set(fdd, simBar);
            else if (f.getType() == classnew.class) f.set(fdd, dummyBtn);
            else if (f.getType() == supersuper.class) {
                if ("interface.int$Object".equals(f.getName())) f.set(fdd, deployedGrid);
                else f.set(fdd, reservesGrid);
            }
        }
        for (Field f : E.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getType() == z.class) f.set(fdd, innerPanel);
            else if (f.getType() == Map.class) f.set(fdd, new HashMap<>());
        }
        z sep1 = new z();
        sep1.setSize(592f, 7f);
        z sep2 = new z();
        sep2.setSize(542f, 1f);
        LabelAPI crNote = Global.getSettings().createLabel("Combat readiness note", Fonts.DEFAULT_SMALL);
        ((UIComponentAPI) crNote).getPosition().setSize(250f, 22f);

        innerPanel.add(deployedGrid).inTL(10f, 10f);
        innerPanel.add(sep1).belowLeft(deployedGrid, 10f);
        innerPanel.add(reservesGrid).belowLeft(sep1, 10f);
        innerPanel.add(sep2).belowLeft(reservesGrid, 15f);
        innerPanel.add((c) crNote).belowLeft(sep2, 6f);

        z widgetPanel = new z();
        widgetPanel.setSize(1920f, 1080f);
        widgetPanel.add(fdd).inMid();

        CombatState cs = allocateWithoutConstructor(CombatState.class);
        for (Field f : CombatState.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getName().equals("widgetPanel")) {
                f.set(cs, widgetPanel);
            } else if (f.getType().getName().equals("com.fs.starfarer.combat.CombatState$o")) {
                f.set(cs, Enum.valueOf((Class) f.getType(), "DEPLOY"));
            }
        }

        final Map<String, Object> customData = new HashMap<>();
        CombatEngineAPI proxyEngine = (CombatEngineAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{CombatEngineAPI.class},
                (p, m, a) -> {
                    if ("getCombatUI".equals(m.getName())) return cs;
                    if ("getShips".equals(m.getName())) return Collections.emptyList();
                    if ("getCustomData".equals(m.getName())) return customData;
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == float.class) return 0f;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });

        CommanderProfile pCmdr = new CommanderProfile(flagshipCaptain, "steady");
        BattleGroupState playerGroup = new BattleGroupState(0, pCmdr);
        playerGroup.missionContext = divisionai.DivisionModel.MissionContextType.INTERCEPTION;
        BattleGroupState enemyGroup = new BattleGroupState(1, new CommanderProfile(null, "steady"));
        enemyGroup.missionContext = divisionai.DivisionModel.MissionContextType.REARGUARD_ACTION;

        DivisionDeploymentDialogOrganizer organizer = new DivisionDeploymentDialogOrganizer();
        organizer.setBattleContext(playerGroup, enemyGroup, new DynamicBattleMusicController());
        organizer.advance(proxyEngine);
        innerPanel.advance(0.1f);

        com.fs.starfarer.coreui.w rList = reservesGrid.getList();
        List<c> rItems = rList.getItems();
        List<CustomPanelAPI> headerCards = new ArrayList<>();
        for (c item : rItems) {
            if (item instanceof CustomPanelAPI && item.getPosition() != null
                    && Math.abs(item.getPosition().getWidth() - 113f) <= 2f) {
                headerCards.add((CustomPanelAPI) item);
            }
        }
        if (headerCards.size() != 3) {
            System.out.println("FAIL pursuit headerCards size: " + headerCards.size());
            return false;
        }

        // Card 0 (Div 1: Cruiser + Destroyer + 3 Frigates) -> has Frigates -> 3 buttons ([L], [C], [R])
        // Card 1 (Div 2: 4 Destroyers, 0 Frigates) -> no Frigates (no ForceConcentration) -> 1 button (Select)
        // Card 2 (Div 3: 3 Frigates) -> has Frigates -> 3 buttons ([L], [C], [R])
        List<LabelAPI> c0Labels = new ArrayList<>(), c1Labels = new ArrayList<>(), c2Labels = new ArrayList<>();
        List<ButtonAPI> c0Btns = new ArrayList<>(), c1Btns = new ArrayList<>(), c2Btns = new ArrayList<>();
        collectLabelsAndButtonsRecursive(headerCards.get(0), c0Labels, c0Btns);
        collectLabelsAndButtonsRecursive(headerCards.get(1), c1Labels, c1Btns);
        collectLabelsAndButtonsRecursive(headerCards.get(2), c2Labels, c2Btns);
        if (c0Btns.size() != 3 || c1Btns.size() != 1 || c2Btns.size() != 3) {
            System.out.println("FAIL pursuit card button counts: c0=" + c0Btns.size() + " c1=" + c1Btns.size() + " c2=" + c2Btns.size());
            return false;
        }

        Field pluginField = null;
        for (Field f : headerCards.get(0).getClass().getDeclaredFields()) {
            if (CustomUIPanelPlugin.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                pluginField = f;
                break;
            }
        }
        if (pluginField == null) return false;
        CustomUIPanelPlugin p0 = (CustomUIPanelPlugin) pluginField.get(headerCards.get(0));
        CustomUIPanelPlugin p1 = (CustomUIPanelPlugin) pluginField.get(headerCards.get(1));
        CustomUIPanelPlugin p2 = (CustomUIPanelPlugin) pluginField.get(headerCards.get(2));

        Method iconGetStateM = null;
        Object probeIcon = reservesGrid.getIconForMember(mLasher1);
        List<Method> initMinusOneMethods = new ArrayList<>();
        for (Method candM : probeIcon.getClass().getDeclaredMethods()) {
            if (candM.getParameterCount() == 0 && candM.getReturnType() == int.class) {
                candM.setAccessible(true);
                if ((int) candM.invoke(probeIcon) == -1) initMinusOneMethods.add(candM);
            }
        }

        // 1. Div 3 (Frigates): Click [L] -> state 2; Click [R] -> state 3; Click [R] again -> state -1 (clean deselect!)
        p2.buttonPressed("DEPLOY_LEFT_ROW");
        for (Method candM : initMinusOneMethods) {
            if ((int) candM.invoke(probeIcon) == 2) { iconGetStateM = candM; break; }
        }
        if (iconGetStateM == null && !initMinusOneMethods.isEmpty()) iconGetStateM = initMinusOneMethods.get(0);
        if ((int) iconGetStateM.invoke(reservesGrid.getIconForMember(mLasher1)) != 2
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mLasher2)) != 2
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mCenturion)) != 2) {
            System.out.println("FAIL Div 3 DEPLOY_LEFT_ROW state");
            return false;
        }
        p2.buttonPressed("DEPLOY_RIGHT_ROW");
        if ((int) iconGetStateM.invoke(reservesGrid.getIconForMember(mLasher1)) != 3
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mCenturion)) != 3) {
            System.out.println("FAIL Div 3 DEPLOY_RIGHT_ROW state");
            return false;
        }
        p2.buttonPressed("DEPLOY_RIGHT_ROW");
        if ((int) iconGetStateM.invoke(reservesGrid.getIconForMember(mLasher1)) != -1
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mLasher2)) != -1
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mCenturion)) != -1) {
            System.out.println("FAIL Div 3 deselect from DEPLOY_RIGHT_ROW");
            return false;
        }

        // 2. Div 2 (Destroyers): Click Select -> state 1; Click Select again -> state -1 (never stuck at state 4!)
        p1.buttonPressed("TOGGLE_ROW");
        if ((int) iconGetStateM.invoke(reservesGrid.getIconForMember(mMedusa)) != 1
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mSunder)) != 1) {
            System.out.println("FAIL Div 2 select state");
            return false;
        }
        p1.buttonPressed("TOGGLE_ROW");
        if ((int) iconGetStateM.invoke(reservesGrid.getIconForMember(mMedusa)) != -1
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mEnforcer)) != -1
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mManticore)) != -1
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mSunder)) != -1) {
            System.out.println("FAIL Div 2 Destroyer deselect stuck at state=" + iconGetStateM.invoke(reservesGrid.getIconForMember(mMedusa)));
            return false;
        }

        // 3. Div 1 (Mixed Cruiser + Destroyer + 3 Frigates):
        //    Click [L] -> Frigates become 2 (DEPLOY LEFT), Heron/Shrike stay -1.
        //    Click [C] -> Heron/Shrike become 1 (DEPLOY), Frigates stay 2 (DEPLOY LEFT)!
        //    Click [C] again -> All 5 become 1 (DEPLOY).
        //    Click [C] third time -> All 5 cleanly deselect to -1!
        p0.buttonPressed("DEPLOY_LEFT_ROW");
        if ((int) iconGetStateM.invoke(reservesGrid.getIconForMember(mOmen)) != 2
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mWolf1)) != 2
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mHeron)) != -1
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mShrike)) != -1) {
            System.out.println("FAIL Mixed Div 1 step 1 ([L])");
            return false;
        }
        p0.buttonPressed("TOGGLE_ROW");
        if ((int) iconGetStateM.invoke(reservesGrid.getIconForMember(mHeron)) != 1
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mShrike)) != 1
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mOmen)) != 2
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mWolf1)) != 2) {
            System.out.println("FAIL Mixed Div 1 step 2 ([C] fills unselected Center while preserving Left Frigates)");
            return false;
        }
        p0.buttonPressed("TOGGLE_ROW");
        if ((int) iconGetStateM.invoke(reservesGrid.getIconForMember(mHeron)) != 1
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mOmen)) != 1) {
            System.out.println("FAIL Mixed Div 1 step 3 ([C] unifies to Center)");
            return false;
        }
        p0.buttonPressed("TOGGLE_ROW");
        if ((int) iconGetStateM.invoke(reservesGrid.getIconForMember(mHeron)) != -1
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mShrike)) != -1
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mOmen)) != -1
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mWolf1)) != -1
                || (int) iconGetStateM.invoke(reservesGrid.getIconForMember(mWolf2)) != -1) {
            System.out.println("FAIL Mixed Div 1 step 4 ([C] deselects all)");
            return false;
        }

        return true;
    }

    private static boolean simulateFleetDoctrineDialog1To1() throws Throwable {
        z hostCoreUI = new z();
        hostCoreUI.setSize(1920f, 1080f);
        FleetDoctrineDialog dialog = new FleetDoctrineDialog(hostCoreUI);
        dialog.show();

        // Verify initial open lands on lightweight Command Scheme (< 45 buttons vs ~1,210 previously)
        List<LabelAPI> initLabels = new ArrayList<>();
        List<ButtonAPI> initButtons = new ArrayList<>();
        collectLabelsAndButtonsRecursive(hostCoreUI, initLabels, initButtons);
        if (initButtons.isEmpty() || initButtons.size() > 45) {
            System.out.println("FAIL FleetDoctrineDialog initial button count too high: " + initButtons.size());
            return false;
        }

        // Verify Division Defaults starts cleanly collapsed into 6 Hull-Type groups (< 35 buttons)
        dialog.buttonPressed("TAB:MODIFIERS");
        dialog.advance(0.1f);
        List<LabelAPI> modCollapsedLabels = new ArrayList<>();
        List<ButtonAPI> modCollapsedButtons = new ArrayList<>();
        collectLabelsAndButtonsRecursive(hostCoreUI, modCollapsedLabels, modCollapsedButtons);
        if (modCollapsedButtons.size() < 15 || modCollapsedButtons.size() > 35) {
            System.out.println("FAIL Division Defaults collapsed hull-group button count unexpected: " + modCollapsedButtons.size());
            return false;
        }

        final float dialogLeft = Math.round((1920f - FleetDoctrineDialog.DIALOG_WIDTH) * 0.5f);
        final float dialogRight = dialogLeft + FleetDoctrineDialog.DIALOG_WIDTH;

        for (String tabCmd : new String[]{
                "TOGGLE_HULL_GROUP:BATTLESHIPS",
                "TOGGLE_RAMMING_OPTS:BATTLESHIP",
                "TOGGLE_DT_CARD:BATTLESHIP",
                "TOGGLE_DT_CARD:BATTLESHIP",
                "TOGGLE_HULL_GROUP:CARRIERS",
                "TOGGLE_CARRIER_OPTS:FLEET_CARRIER",
                "TOGGLE_DD:SC_OPEN_DD:FLEET_CARRIER",
                "SET_SC_OPEN_MIS:FLEET_CARRIER:COMBAT_SPACE_PATROL",
                "SET_SC_OPEN_MIS:FLEET_CARRIER:ARMED_RECONNAISSANCE",
                "TOGGLE_DD:SC_MAIN_DD:FLEET_CARRIER",
                "SET_SC_MAIN_MIS:FLEET_CARRIER:ALPHA_STRIKE",
                "SET_VS_BIAS:FLEET_CARRIER:CAPITALS_FIRST",
                "SET_VS_CV_FOCUS:FLEET_CARRIER:TOGGLE",
                "SET_VS_BIAS:FLEET_CARRIER:FREE_SELECTION",
                "SET_VS_BIAS:FLEET_CARRIER:CRUISERS_AND_CAPITALS",
                "SET_VS_CV_FOCUS:FLEET_CARRIER:TOGGLE",
                "SET_VS_GEOM:FLEET_CARRIER:PINCER_ENVELOPMENT",
                "SET_VS_GUN:FLEET_CARRIER:PRECISION",
                "SET_VS_AOA:FLEET_CARRIER:LOW",
                "SET_VS_GEOM:FLEET_CARRIER:ANVIL_CROSSFIRE",
                "SET_VS_GUN:FLEET_CARRIER:AREA_SATURATION",
                "SET_VS_AOA:FLEET_CARRIER:STRONG",
                "EXPAND_ALL_HULL_GROUPS",
                "COLLAPSE_ALL_HULL_GROUPS",
                "TAB:OTC_COMMAND",
                "TOGGLE_DD:FLEET_WIDE_FORMATION_DD",
                "TOGGLE_DD:MULTI_DIV_SETPIECE_DD",
                "TAB:AUTO_ASSIGN",
                "TAB:HULLS",
                "TAB:NAMING",
                "TOGGLE_DD:NAMING_PRESET",
                "TAB:ALL"}) {
            dialog.buttonPressed(tabCmd);
            dialog.advance(0.1f);
            List<LabelAPI> labels = new ArrayList<>();
            List<ButtonAPI> buttons = new ArrayList<>();
            collectLabelsAndButtonsRecursive(hostCoreUI, labels, buttons);
            if (labels.isEmpty() || buttons.isEmpty()) {
                return false;
            }
            if (!assertPositionTreeInvariantsAndRecompute(hostCoreUI.getPosition())) {
                return false;
            }
            for (LabelAPI lbl : labels) {
                if (!isPurePrintableAscii(lbl.getText())) {
                    return false;
                }
                PositionAPI lp = lbl.getPosition();
                if (lp != null && lp.getWidth() > 0f && (lp.getX() + lp.getWidth() > dialogRight + 4f)) {
                    System.out.println("FAIL FleetDoctrineDialog label overflow on " + tabCmd
                            + ": right=" + (lp.getX() + lp.getWidth()) + " > dialogRight=" + dialogRight
                            + " text=\"" + lbl.getText() + "\"");
                    return false;
                }
            }
            for (ButtonAPI btn : buttons) {
                if (!isPurePrintableAscii(btn.getText())) {
                    return false;
                }
                PositionAPI bp = btn.getPosition();
                if (bp != null && bp.getWidth() > 0f && (bp.getX() + bp.getWidth() > dialogRight + 4f)) {
                    System.out.println("FAIL FleetDoctrineDialog button overflow on " + tabCmd
                            + ": right=" + (bp.getX() + bp.getWidth()) + " > dialogRight=" + dialogRight
                            + " text=\"" + btn.getText() + "\"");
                    return false;
                }
            }
            if (!verifyAttachedTooltipsInTree(hostCoreUI, 8)) {
                return false;
            }
        }
        dialog.close();
        return true;
    }

    private static boolean simulateDivisionEditorDialog1To1() throws Throwable {
        z hostCoreUI = new z();
        hostCoreUI.setSize(1920f, 1080f);
        DivisionEditorDialog dialog = new DivisionEditorDialog(hostCoreUI);
        dialog.show();

        for (String cmd : new String[]{
                null,
                "SET_OTC_AUTH:POSITIVE_CONTROL",
                "SET_OTC_AUTH:COMMAND_BY_DIRECTION",
                "OPEN_DIV:div_1",
                "TOGGLE_DD:DIV_ARCH_DD",
                "TOGGLE_DD:DIV_OPEN_DD",
                "TOGGLE_DD:DIV_MAIN_DD",
                "TOGGLE_DIV_TUNING_OPTIONS",
                "TOGGLE_DIV_TUNING_OPTIONS",
                "TOGGLE_DIV_RAMMING_OPTIONS",
                "TOGGLE_DIV_CARRIER_OPTIONS",
                "TOGGLE_DD:DIV_SC_OPEN_DD",
                "DIV_SC_OPEN_MIS:COMBAT_SPACE_PATROL",
                "DIV_SC_OPEN_MIS:INHERIT",
                "TOGGLE_DD:DIV_SC_MAIN_DD",
                "DIV_SC_MAIN_MIS:ALPHA_STRIKE",
                "DIV_SC_MAIN_MIS:INHERIT",
                "DIV_VS_BIAS:CAPITALS_FIRST",
                "DIV_VS_CV_FOCUS:TOGGLE",
                "DIV_VS_BIAS:FREE_SELECTION",
                "DIV_VS_BIAS:INHERIT",
                "DIV_VS_CV_FOCUS:INHERIT",
                "DIV_VS_GEOM:ANVIL_CROSSFIRE",
                "DIV_VS_GUN:AREA_SATURATION",
                "DIV_VS_AOA:STRONG",
                "DIV_VS_GEOM:INHERIT",
                "DIV_VS_GUN:INHERIT",
                "DIV_VS_AOA:INHERIT",
                "BACK_TO_LIST"}) {
            if (cmd != null) {
                dialog.buttonPressed(cmd);
                dialog.advance(0.1f);
            }
            if ("SET_OTC_AUTH:POSITIVE_CONTROL".equals(cmd)
                    && PermanentDivisionManager.getFleetDoctrine().getOtcAuthority() != PermanentDivisionManager.OtcAuthority.POSITIVE_CONTROL) {
                return false;
            }
            if ("SET_OTC_AUTH:COMMAND_BY_DIRECTION".equals(cmd)
                    && PermanentDivisionManager.getFleetDoctrine().getOtcAuthority() != PermanentDivisionManager.OtcAuthority.COMMAND_BY_DIRECTION) {
                return false;
            }
            List<LabelAPI> labels = new ArrayList<>();
            List<ButtonAPI> buttons = new ArrayList<>();
            collectLabelsAndButtonsRecursive(hostCoreUI, labels, buttons);
            if (labels.isEmpty() || buttons.isEmpty()) {
                return false;
            }
            if (!assertPositionTreeInvariantsAndRecompute(hostCoreUI.getPosition())) {
                return false;
            }
            float edDialogLeft = Math.max(20f, (1920f - DivisionEditorDialog.DIALOG_WIDTH) * 0.5f);
            float edDialogRight = edDialogLeft + DivisionEditorDialog.DIALOG_WIDTH;
            float edLeftColRight = edDialogLeft + 12f + (DivisionEditorDialog.DIALOG_WIDTH - 36f) * 0.5f;
            boolean inSingleDivView = cmd != null && !"BACK_TO_LIST".equals(cmd) && !cmd.startsWith("SET_OTC_AUTH:");
            for (LabelAPI lbl : labels) {
                if (!isPurePrintableAscii(lbl.getText())) {
                    return false;
                }
                PositionAPI lp = lbl.getPosition();
                if (lp != null && lp.getWidth() > 0f && (lp.getX() + lp.getWidth() > edDialogRight + 4f)) {
                    System.out.println("FAIL DivisionEditorDialog label overflow on " + cmd
                            + ": right=" + (lp.getX() + lp.getWidth()) + " > edDialogRight=" + edDialogRight
                            + " text=\"" + lbl.getText() + "\"");
                    return false;
                }
                // Ensure labels in Configure Division's left settings column never bleed across into the right ship pool
                if (inSingleDivView && lp != null && lp.getWidth() > 0f
                        && lp.getY() < (1080f - Math.max(20f, (1080f - DivisionEditorDialog.DIALOG_HEIGHT) * 0.5f) - 76f)
                        && lp.getX() < edLeftColRight - 10f
                        && (lp.getX() + lp.getWidth() > edLeftColRight + 4f)) {
                    System.out.println("FAIL Configure Division left-column label bleeds into right column on " + cmd
                            + ": right=" + (lp.getX() + lp.getWidth()) + " > leftColRight=" + edLeftColRight
                            + " text=\"" + lbl.getText() + "\"");
                    return false;
                }
            }
            for (ButtonAPI btn : buttons) {
                if (!isPurePrintableAscii(btn.getText())) {
                    return false;
                }
                PositionAPI bp = btn.getPosition();
                if (bp != null && bp.getWidth() > 0f && (bp.getX() + bp.getWidth() > edDialogRight + 4f)) {
                    System.out.println("FAIL DivisionEditorDialog button overflow on " + cmd
                            + ": right=" + (bp.getX() + bp.getWidth()) + " > edDialogRight=" + edDialogRight
                            + " text=\"" + btn.getText() + "\"");
                    return false;
                }
                if (inSingleDivView && bp != null && bp.getWidth() > 0f
                        && bp.getY() < (1080f - Math.max(20f, (1080f - DivisionEditorDialog.DIALOG_HEIGHT) * 0.5f) - 76f)
                        && bp.getX() < edLeftColRight - 10f
                        && (bp.getX() + bp.getWidth() > edLeftColRight + 4f)) {
                    System.out.println("FAIL Configure Division left-column button bleeds into right column on " + cmd
                            + ": right=" + (bp.getX() + bp.getWidth()) + " > leftColRight=" + edLeftColRight
                            + " text=\"" + btn.getText() + "\"");
                    return false;
                }
            }
            if (!verifyAttachedTooltipsInTree(hostCoreUI, 6)) {
                return false;
            }
        }
        dialog.close();
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean assertPositionTreeInvariantsAndRecompute(PositionAPI rootPos) throws Throwable {
        if (!(rootPos instanceof M)) return false;
        Field childrenField = M.class.getDeclaredField("\u00d400000");
        childrenField.setAccessible(true);
        Set<M> visited = Collections.newSetFromMap(new IdentityHashMap<M, Boolean>());
        return verifyPositionNodeRecursive((M) rootPos, childrenField, visited);
    }

    @SuppressWarnings("unchecked")
    private static boolean verifyPositionNodeRecursive(M node, Field childrenField, Set<M> visited) throws Throwable {
        if (node == null || !visited.add(node)) return true;
        node.setSuspendRecompute(false);
        node.recompute();
        List<M> children = (List<M>) childrenField.get(node);
        if (children == null || children.isEmpty()) return true;
        Set<M> siblingSet = Collections.newSetFromMap(new IdentityHashMap<M, Boolean>());
        for (M ch : children) {
            if (ch != null) siblingSet.add(ch);
        }
        for (M ch : new ArrayList<M>(children)) {
            if (ch == null) continue;
            M base = ch.getBase();
            if (base != null && !siblingSet.contains(base)) {
                System.out.println("FAIL dangling L sibling anchor detected: child=" + ch + " base=" + base);
                return false;
            }
            if (!verifyPositionNodeRecursive(ch, childrenField, visited)) {
                return false;
            }
        }
        return true;
    }

    private static com.fs.starfarer.ui.whilesuper extractAttachedTooltip(Object comp) {
        if (comp instanceof com.fs.starfarer.ui.R) {
            return ((com.fs.starfarer.ui.R) comp).getTooltip();
        }
        if (comp != null) {
            for (Class<?> cl = comp.getClass(); cl != null && cl != Object.class; cl = cl.getSuperclass()) {
                for (Field f : cl.getDeclaredFields()) {
                    if (f.getType() == com.fs.starfarer.ui.whilesuper.class) {
                        try {
                            f.setAccessible(true);
                            return (com.fs.starfarer.ui.whilesuper) f.get(comp);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean verifyAttachedTooltipsInTree(Object rootComp, int minExpectedTooltips) throws Throwable {
        List<LabelAPI> labels = new ArrayList<>();
        List<ButtonAPI> buttons = new ArrayList<>();
        collectLabelsAndButtonsRecursive(rootComp, labels, buttons);
        int tooltipCount = 0;
        List<Object> allCheckComps = new ArrayList<>();
        allCheckComps.addAll(buttons);
        allCheckComps.addAll(labels);
        for (Object comp : allCheckComps) {
            com.fs.starfarer.ui.whilesuper tt = extractAttachedTooltip(comp);
            if (tt instanceof com.fs.starfarer.ui.impl.StandardTooltipV2Expandable) {
                tooltipCount++;
                com.fs.starfarer.ui.impl.StandardTooltipV2Expandable exp =
                        (com.fs.starfarer.ui.impl.StandardTooltipV2Expandable) tt;
                exp.beforeShown();
                List<LabelAPI> ttLabels = new ArrayList<>();
                List<ButtonAPI> ttButtons = new ArrayList<>();
                collectLabelsAndButtonsRecursive(exp.getPanel(), ttLabels, ttButtons);
                if (ttLabels.isEmpty()) {
                    System.out.println("FAIL attached tooltip built 0 labels for component: " + comp);
                    return false;
                }
                for (LabelAPI tl : ttLabels) {
                    if (!isPurePrintableAscii(tl.getText())) {
                        System.out.println("FAIL non-ascii text in attached tooltip: " + tl.getText());
                        return false;
                    }
                }
                if (exp.getPosition() != null && !assertPositionTreeInvariantsAndRecompute(exp.getPosition())) {
                    return false;
                }
            }
        }
        if (tooltipCount < minExpectedTooltips) {
            System.out.println("FAIL expected at least " + minExpectedTooltips + " attached tooltips, found " + tooltipCount);
            return false;
        }
        return true;
    }

    private static boolean simulateCampaignFleetTooltipOrganizer1To1(O00o hullSpec, final PersonAPI flagshipCaptain) throws Throwable {
        final List<FleetMember> members = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            SimFleetMember m = SimFleetMember.create(
                    "sim_ship_" + i,
                    i == 0 ? "HSS Sovereign" : ("HSS Escort " + i),
                    i < 4 ? 40f : 15f,
                    i == 0,
                    i < 4 ? HullSize.CAPITAL_SHIP : HullSize.CRUISER,
                    hullSpec,
                    i == 0 ? flagshipCaptain : null);
            members.add(m);
        }
        final FleetDataAPI fleetData = (FleetDataAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{FleetDataAPI.class},
                (p, m, a) -> "getMembersListCopy".equals(m.getName()) ? new ArrayList<>(members) : null);
        final FactionAPI simFaction = (FactionAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{FactionAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getId".equals(n)) return "player";
                    if ("getDisplayName".equals(n)) return "Player";
                    if ("getBaseUIColor".equals(n)) return new Color(110, 210, 255, 255);
                    if ("getBrightUIColor".equals(n)) return new Color(170, 235, 255, 255);
                    if ("getDarkUIColor".equals(n)) return new Color(35, 75, 105, 255);
                    if ("getGridUIColor".equals(n)) return new Color(60, 125, 165, 255);
                    if ("getSecondaryUIColor".equals(n)) return new Color(110, 210, 255, 255);
                    if (m.getReturnType() == Color.class) return new Color(110, 210, 255, 255);
                    if (m.getReturnType() == boolean.class) return false;
                    return null;
                });
        final boolean[] stationModeRef = new boolean[]{false};
        final CampaignFleetAPI fleet = (CampaignFleetAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getId".equals(n)) return "sim_campaign_fleet";
                    if ("getFleetData".equals(n)) return fleetData;
                    if ("getFlagship".equals(n)) return members.get(0);
                    if ("getCommander".equals(n)) return flagshipCaptain;
                    if ("getFaction".equals(n)) return simFaction;
                    if ("isPlayerFleet".equals(n)) return true;
                    if ("isStationMode".equals(n)) return stationModeRef[0];
                    if (m.getReturnType() == boolean.class) return false;
                    return null;
                });

        divisionai.CampaignFleetTooltipOrganizer organizer = new divisionai.CampaignFleetTooltipOrganizer();

        // Scenario 1: Normal multi-division campaign fleet tooltip + 6x CampaignEngine.renderInScreenCoords recompute loop
        com.fs.starfarer.ui.impl.StandardTooltipV2 tooltip1 =
                com.fs.starfarer.ui.impl.StandardTooltipV2Expandable.createTextTooltip(420f, "Fleet Tooltip");
        tooltip1.beforeShown();
        z contents1 = (z) tooltip1.getContents();
        supersuper sGrid1 = buildRealShipGrid(null, 410f, 180f, 7, members);
        contents1.add(sGrid1).inTL(5f, 24f);
        if (!organizer.organizeTooltipForTesting(fleet, tooltip1, contents1, sGrid1)) {
            System.out.println("FAIL CampaignFleetTooltipOrganizer scenario 1 returned false");
            return false;
        }
        for (int pass = 0; pass < 6; pass++) {
            tooltip1.getPosition().setLocation(100f + pass, 120f + pass);
            if (!assertPositionTreeInvariantsAndRecompute(tooltip1.getPosition())) {
                System.out.println("FAIL CampaignFleetTooltipOrganizer scenario 1 recompute pass " + pass);
                return false;
            }
        }

        // Scenario 2: Foreign mod overlay component injected directly into g$Oo and anchored via belowLeft to a ship cell
        // (Reproduces exact 'RuntimeException: May only anchor on siblings' crash condition!)
        com.fs.starfarer.ui.impl.StandardTooltipV2 tooltip2 =
                com.fs.starfarer.ui.impl.StandardTooltipV2Expandable.createTextTooltip(420f, "Fleet Tooltip With Foreign Overlay");
        tooltip2.beforeShown();
        z contents2 = (z) tooltip2.getContents();
        supersuper sGrid2 = buildRealShipGrid(null, 410f, 180f, 7, members);
        contents2.add(sGrid2).inTL(5f, 24f);
        com.fs.starfarer.coreui.w vTable2 = sGrid2.getList();
        c firstShipCell = vTable2.getItems().get(0);
        z contentContainer2 = vTable2.getScroller().getContentContainer();
        z foreignOverlay = new z(18f, 18f);
        contentContainer2.add(foreignOverlay).belowLeft(firstShipCell, 2f);
        if (!organizer.organizeTooltipForTesting(fleet, tooltip2, contents2, sGrid2)) {
            System.out.println("FAIL CampaignFleetTooltipOrganizer scenario 2 (foreign sibling anchor) returned false");
            return false;
        }
        if (!assertPositionTreeInvariantsAndRecompute(tooltip2.getPosition())) {
            System.out.println("FAIL CampaignFleetTooltipOrganizer scenario 2 recompute after foreign sibling anchor");
            return false;
        }

        // Scenario 3: Duplicate FleetMember entries in S.getMembers() (joint/combined fleet tooltip edge case)
        com.fs.starfarer.ui.impl.StandardTooltipV2 tooltip3 =
                com.fs.starfarer.ui.impl.StandardTooltipV2Expandable.createTextTooltip(420f, "Fleet Tooltip With Duplicates");
        tooltip3.beforeShown();
        z contents3 = (z) tooltip3.getContents();
        List<FleetMember> dupMembers = new ArrayList<>(members);
        dupMembers.add(members.get(0));
        dupMembers.add(members.get(2));
        supersuper sGrid3 = buildRealShipGrid(null, 410f, 180f, 7, dupMembers);
        contents3.add(sGrid3).inTL(5f, 24f);
        if (!organizer.organizeTooltipForTesting(fleet, tooltip3, contents3, sGrid3)) {
            System.out.println("FAIL CampaignFleetTooltipOrganizer scenario 3 (duplicate members) returned false");
            return false;
        }
        if (!assertPositionTreeInvariantsAndRecompute(tooltip3.getPosition())) {
            System.out.println("FAIL CampaignFleetTooltipOrganizer scenario 3 recompute after duplicate members");
            return false;
        }

        // Scenario 4: Station fleet skip & deliberate dangling-anchor sanitization
        stationModeRef[0] = true;
        int itemsBeforeStationSkip = sGrid3.getList().getItems().size();
        if (!organizer.organizeTooltipForTesting(fleet, tooltip3, contents3, sGrid3)
                || sGrid3.getList().getItems().size() != itemsBeforeStationSkip) {
            System.out.println("FAIL CampaignFleetTooltipOrganizer scenario 4 station fleet skip");
            return false;
        }
        stationModeRef[0] = false;

        // Deliberately corrupt a child's OO0O base anchor to point to a removed node, then run sanitizeTooltipForTesting
        z orphanTarget = new z(10f, 10f);
        z anchoredChild = new z(10f, 10f);
        contents3.add(orphanTarget).inTL(0f, 0f);
        contents3.add(anchoredChild).rightOfTop(orphanTarget, 4f);
        contents3.remove(orphanTarget);
        organizer.sanitizeTooltipForTesting(tooltip3.getPosition());
        if (!assertPositionTreeInvariantsAndRecompute(tooltip3.getPosition())) {
            System.out.println("FAIL CampaignFleetTooltipOrganizer scenario 4 sanitizeTooltipForTesting did not repair dangling anchor");
            return false;
        }

        return true;
    }

    private static Field resolveBooleanSetterField(
            ClassLoader cl,
            Class<?> targetCls,
            String fallbackMethodName,
            int descriptorOrdinal) throws Exception {
        String methodName = divisionai.ObfuscatedBytecodeBridge.resolveMethodNameByDescriptor(
                cl, targetCls, fallbackMethodName, "(Z)V", descriptorOrdinal);
        Method setter = targetCls.getDeclaredMethod(methodName, boolean.class);
        setter.setAccessible(true);
        Object probeA = allocateWithoutConstructor(targetCls);
        Object probeB = allocateWithoutConstructor(targetCls);
        setter.invoke(probeA, false);
        setter.invoke(probeB, true);
        for (Field f : targetCls.getDeclaredFields()) {
            if (f.getType() == boolean.class && !Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                if (!f.getBoolean(probeA) && f.getBoolean(probeB)) {
                    return f;
                }
            }
        }
        throw new NoSuchFieldException("Could not resolve boolean field mutated by " + targetCls.getName() + "." + methodName);
    }

    /**
     * 1:1 Starsector Obfuscated Warroom Battle Suite:
     * Instantiates real {@code com.fs.starfarer.combat.CombatState}, {@code com.fs.starfarer.combat.new.OoOO} (Warroom),
     * {@code com.fs.starfarer.combat.new.b} (MapDisplay), {@code com.fs.starfarer.combat.new.A} (Ship command icon),
     * {@code com.fs.starfarer.combat.new.o0Oo} (Fighter command icon), {@code com.fs.starfarer.ui.B.new} (Ship widget),
     * and {@code com.fs.graphics.Sprite} / {@code com.fs.graphics.Object} textures, attaches {@link DivisionTacticalMapOverlay}
     * via {@link DivisionTacticalMapOverlay#ensureAttachedToWarroomUI()}, and verifies:
     *   1. Live icons in {@code b.class()} ({@code "private.new"} Map) and fading-out icons in {@code b.\u00d2o0000} Set
     *      have their hull sprites, facing arrows, fighter sprites, holo scanlines, modules, and status overlays suppressed
     *      when TSFC is active and {@code isTsfcMiniShipSpritesEnabled()} is true.
     *   2. Mid-battle reinforcement ships and fighters added to {@code b.class()} while TSFC is already active are
     *      suppressed immediately on the next frame.
     *   3. Toggling {@code isTsfcMiniShipSpritesEnabled()} to false (or turning TSFC off) losslessly restores all
     *      original {@code Sprite.texture} instances and {@code B.new} flags.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean simulateWarroomBattleCommandSpriteSuite1To1() throws Throwable {
        ClassLoader cl = StarsectorUISimulator.class.getClassLoader();
        Class<?> combatEngineCls = Class.forName("com.fs.starfarer.combat.CombatEngine");
        Class<?> combatStateCls = Class.forName("com.fs.starfarer.combat.CombatState");
        Class<?> wrCls = divisionai.ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                cl, "com.fs.starfarer.combat.B.A", combatStateCls.getName(), "getWarroom");
        Class<?> mdCls = divisionai.ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                cl, "com.fs.starfarer.combat.B.floatsuper", wrCls.getName(), "getMapDisplay");
        Class<?> bNewCls = divisionai.ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                cl, "com.fs.starfarer.ui.oOOO.OoOO", "com.fs.starfarer.campaign.ui.supersuper", "getIconForMember");
        Class<?> shipIconCls = divisionai.ObfuscatedBytecodeBridge.resolveWarroomShipIconClass(cl, mdCls, bNewCls);
        Class<?> fighterIconCls = divisionai.ObfuscatedBytecodeBridge.resolveWarroomFighterIconClass(cl, mdCls, shipIconCls);
        Class<?> spriteCls = Class.forName("com.fs.graphics.Sprite");
        Class<?> texObjCls = divisionai.ObfuscatedBytecodeBridge.resolveClassOrFieldType(
                cl, "com.fs.graphics.C", spriteCls.getName(), "texture");

        Object realEngine = allocateWithoutConstructor(combatEngineCls);
        Object combatState = allocateWithoutConstructor(combatStateCls);
        Constructor<z> panelCtor = z.class.getDeclaredConstructor();
        Constructor<?> wrCtor = ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(wrCls, panelCtor);
        Object warroom = wrCtor.newInstance();
        ((z) warroom).setSize(1920f, 1080f);
        Object mapDisplay = allocateWithoutConstructor(mdCls);

        // Wire CombatEngine -> CombatState -> Warroom -> MapDisplay
        Field uiField = null;
        for (Field f : combatEngineCls.getDeclaredFields()) {
            if (com.fs.starfarer.api.combat.CombatUIAPI.class.isAssignableFrom(f.getType())) {
                uiField = f;
                break;
            }
        }
        if (uiField != null) {
            uiField.setAccessible(true);
            uiField.set(realEngine, combatState);
        }
        for (Field f : combatStateCls.getDeclaredFields()) {
            if (f.getType() == wrCls) {
                f.setAccessible(true);
                f.set(combatState, warroom);
                break;
            }
        }
        for (Field f : wrCls.getDeclaredFields()) {
            if (f.getType() == mdCls) {
                f.setAccessible(true);
                f.set(warroom, mapDisplay);
                break;
            }
        }

        Map<Object, Object> liveIconMap = new HashMap<>();
        Set<Object> deadIconSet = new java.util.HashSet<>();
        String mdMapFieldName = divisionai.ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                cl, mdCls, "Ljava/util/Map;", "while.new");
        Field mdMapField = mdCls.getDeclaredField(mdMapFieldName);
        mdMapField.setAccessible(true);
        mdMapField.set(mapDisplay, liveIconMap);
        String mdSetFieldName = divisionai.ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                cl, mdCls, "Ljava/util/Set;", "null.super");
        Field mdSetField = mdCls.getDeclaredField(mdSetFieldName);
        mdSetField.setAccessible(true);
        mdSetField.set(mapDisplay, deadIconSet);

        String aBNewFieldName = divisionai.ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                cl, shipIconCls, divisionai.ObfuscatedBytecodeBridge.toDescriptor(bNewCls), "O\u00d30000");
        Field aBNewField = shipIconCls.getDeclaredField(aBNewFieldName);
        aBNewField.setAccessible(true);
        String aArrowFieldName = divisionai.ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                cl, shipIconCls, divisionai.ObfuscatedBytecodeBridge.toDescriptor(spriteCls), "interface.new");
        Field aArrowField = shipIconCls.getDeclaredField(aArrowFieldName);
        aArrowField.setAccessible(true);
        String fighterSpriteFieldName = divisionai.ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                cl, fighterIconCls, divisionai.ObfuscatedBytecodeBridge.toDescriptor(spriteCls), "float.super");
        Field fighterSpriteField = fighterIconCls.getDeclaredField(fighterSpriteFieldName);
        fighterSpriteField.setAccessible(true);
        String bNewSpriteFieldName = divisionai.ObfuscatedBytecodeBridge.resolveFieldNameByDescriptor(
                cl, bNewCls, divisionai.ObfuscatedBytecodeBridge.toDescriptor(spriteCls), "\u00d4\u00d50000");
        Field bNewSpriteField = bNewCls.getDeclaredField(bNewSpriteFieldName);
        bNewSpriteField.setAccessible(true);
        Field bNewHoloField = resolveBooleanSetterField(cl, bNewCls, "\u00d4O0000", 1);
        Field bNewModulesField = resolveBooleanSetterField(cl, bNewCls, "\u00f400000", 4);
        Field bNewStatusField = resolveBooleanSetterField(cl, bNewCls, "\u00d500000", 10);
        Field spriteTexField = spriteCls.getDeclaredField("texture");
        spriteTexField.setAccessible(true);

        // Create initial live ship icon A1 (in liveIconMap), dead ship icon A2 (in deadIconSet), and live fighter icon F1
        Object texHull1 = allocateWithoutConstructor(texObjCls);
        Object texArrow1 = allocateWithoutConstructor(texObjCls);
        Object spHull1 = allocateWithoutConstructor(spriteCls);
        Object spArrow1 = allocateWithoutConstructor(spriteCls);
        spriteTexField.set(spHull1, texHull1);
        spriteTexField.set(spArrow1, texArrow1);
        Object bNew1 = allocateWithoutConstructor(bNewCls);
        bNewSpriteField.set(bNew1, spHull1);
        bNewHoloField.setBoolean(bNew1, true);
        bNewModulesField.setBoolean(bNew1, true);
        bNewStatusField.setBoolean(bNew1, true);
        Object shipIcon1 = allocateWithoutConstructor(shipIconCls);
        aBNewField.set(shipIcon1, bNew1);
        aArrowField.set(shipIcon1, spArrow1);
        liveIconMap.put("ship_1", shipIcon1);

        Object texFighter1 = allocateWithoutConstructor(texObjCls);
        Object spFighter1 = allocateWithoutConstructor(spriteCls);
        spriteTexField.set(spFighter1, texFighter1);
        Object fighterIcon1 = allocateWithoutConstructor(fighterIconCls);
        fighterSpriteField.set(fighterIcon1, spFighter1);
        liveIconMap.put("fighter_1", fighterIcon1);

        Object texDeadHull = allocateWithoutConstructor(texObjCls);
        Object spDeadHull = allocateWithoutConstructor(spriteCls);
        spriteTexField.set(spDeadHull, texDeadHull);
        Object bNewDead = allocateWithoutConstructor(bNewCls);
        bNewSpriteField.set(bNewDead, spDeadHull);
        bNewHoloField.setBoolean(bNewDead, true);
        bNewModulesField.setBoolean(bNewDead, true);
        bNewStatusField.setBoolean(bNewDead, true);
        Object deadShipIcon = allocateWithoutConstructor(shipIconCls);
        aBNewField.set(deadShipIcon, bNewDead);
        deadIconSet.add(deadShipIcon);

        divisionai.DivisionTacticalMapOverlay overlay =
                new divisionai.DivisionTacticalMapOverlay((com.fs.starfarer.api.combat.CombatEngineAPI) realEngine);
        overlay.ensureAttachedToWarroomUI();

        boolean prevFirepower = divisionai.TsfcWarroomRenderer.isOverlayVisible();
        Boolean prevMiniOverride = divisionai.DivisionAISettings.testTsfcMiniShipSpritesOverride;
        try {
            // 1. Activate TSFC Firepower overlay with Mini Ship Sprites ON (default)
            divisionai.TsfcWarroomRenderer.resetForNewBattle();
            divisionai.DivisionAISettings.testTsfcMiniShipSpritesOverride = Boolean.TRUE;
            divisionai.TsfcWarroomRenderer.toggleOverlayVisible();
            overlay.advance(0.10f);

            boolean initialSuppressedOk = spriteTexField.get(spHull1) == null
                    && spriteTexField.get(spArrow1) == null
                    && spriteTexField.get(spFighter1) == null
                    && spriteTexField.get(spDeadHull) == null
                    && !bNewHoloField.getBoolean(bNew1)
                    && !bNewModulesField.getBoolean(bNew1)
                    && !bNewStatusField.getBoolean(bNew1);

            // 2. Spawn mid-battle reinforcement ship & fighter into liveIconMap while TSFC is already active
            Object texHull2 = allocateWithoutConstructor(texObjCls);
            Object spHull2 = allocateWithoutConstructor(spriteCls);
            spriteTexField.set(spHull2, texHull2);
            Object bNew2 = allocateWithoutConstructor(bNewCls);
            bNewSpriteField.set(bNew2, spHull2);
            bNewHoloField.setBoolean(bNew2, true);
            bNewModulesField.setBoolean(bNew2, true);
            bNewStatusField.setBoolean(bNew2, true);
            Object shipIcon2 = allocateWithoutConstructor(shipIconCls);
            aBNewField.set(shipIcon2, bNew2);
            liveIconMap.put("ship_reinforcement_2", shipIcon2);

            overlay.advance(0.10f);
            boolean midBattleSpawnSuppressedOk = spriteTexField.get(spHull2) == null
                    && !bNewHoloField.getBoolean(bNew2)
                    && !bNewModulesField.getBoolean(bNew2)
                    && !bNewStatusField.getBoolean(bNew2);

            // 3. Toggle Mod Settings 'T-S-F-C Mini Ship Sprites' OFF while TSFC stays active -> regular icons must restore!
            divisionai.DivisionAISettings.testTsfcMiniShipSpritesOverride = Boolean.FALSE;
            overlay.advance(0.10f);
            boolean settingOffRestoredOk = spriteTexField.get(spHull1) == texHull1
                    && spriteTexField.get(spArrow1) == texArrow1
                    && spriteTexField.get(spFighter1) == texFighter1
                    && spriteTexField.get(spDeadHull) == texDeadHull
                    && spriteTexField.get(spHull2) == texHull2
                    && bNewHoloField.getBoolean(bNew1)
                    && bNewModulesField.getBoolean(bNew1)
                    && bNewStatusField.getBoolean(bNew1)
                    && bNewHoloField.getBoolean(bNew2);

            // 4. Re-enable setting -> suppressed again, then toggle TSFC overlay OFF -> restored again!
            divisionai.DivisionAISettings.testTsfcMiniShipSpritesOverride = Boolean.TRUE;
            overlay.advance(0.10f);
            boolean reSuppressedOk = spriteTexField.get(spHull1) == null && spriteTexField.get(spHull2) == null;
            divisionai.TsfcWarroomRenderer.resetForNewBattle();
            overlay.advance(0.10f);
            boolean tsfcOffRestoredOk = spriteTexField.get(spHull1) == texHull1
                    && spriteTexField.get(spHull2) == texHull2
                    && spriteTexField.get(spFighter1) == texFighter1;

            if (!initialSuppressedOk || !midBattleSpawnSuppressedOk || !settingOffRestoredOk || !reSuppressedOk || !tsfcOffRestoredOk) {
                System.out.println("FAIL simulateWarroomBattleCommandSpriteSuite1To1: init=" + initialSuppressedOk
                        + " [hull1=" + (spriteTexField.get(spHull1) == null)
                        + ", arrow1=" + (spriteTexField.get(spArrow1) == null)
                        + ", fighter1=" + (spriteTexField.get(spFighter1) == null)
                        + ", dead=" + (spriteTexField.get(spDeadHull) == null)
                        + ", holo=" + (!bNewHoloField.getBoolean(bNew1))
                        + ", mod=" + (!bNewModulesField.getBoolean(bNew1))
                        + ", stat=" + (!bNewStatusField.getBoolean(bNew1)) + "]"
                        + ", midSpawn=" + midBattleSpawnSuppressedOk
                        + ", settingOff=" + settingOffRestoredOk
                        + ", reSup=" + reSuppressedOk
                        + ", tsfcOff=" + tsfcOffRestoredOk);
                return false;
            }
            return true;
        } finally {
            divisionai.TsfcWarroomRenderer.resetForNewBattle();
            if (prevFirepower) {
                divisionai.TsfcWarroomRenderer.toggleOverlayVisible();
            }
            divisionai.DivisionAISettings.testTsfcMiniShipSpritesOverride = prevMiniOverride;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean simulate720pAnd768pViewportSuite1To1(
            O00o hullSpec,
            final PersonAPI flagshipCaptain) throws Throwable {
        // Verify 1080p+ exact preservation first
        if (FleetDoctrineDialog.resolveDialogWidth(1920f) != 1320f
                || FleetDoctrineDialog.resolveDialogHeight(1080f) != 800f
                || DivisionEditorDialog.resolveDialogWidth(1920f) != 1320f
                || DivisionEditorDialog.resolveDialogHeight(1080f) != 800f
                || FleetDoctrineDialog.resolveDialogWidth(1366f) != 1320f
                || FleetDoctrineDialog.resolveDialogHeight(768f) != 736f
                || FleetDoctrineDialog.resolveDialogWidth(1280f) != 1240f
                || FleetDoctrineDialog.resolveDialogHeight(720f) != 688f) {
            System.out.println("FAIL 720p/768p/1080p resolveDialogWidth/Height clamping mismatch");
            return false;
        }

        final List<FleetMember> allMembers = new ArrayList<>();
        final List<FleetMember> reserveMembers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            SimFleetMember m = SimFleetMember.create(
                    "sim720_ship_" + i,
                    i == 0 ? "HSS Invictus" : ("HSS Escort " + i),
                    i < 4 ? 40f : 15f,
                    i == 0,
                    i < 4 ? HullSize.CAPITAL_SHIP : HullSize.CRUISER,
                    hullSpec,
                    i == 0 ? flagshipCaptain : null);
            allMembers.add(m);
            reserveMembers.add(m);
        }

        final Map<String, Object> pdata = new HashMap<>();
        FleetDataAPI fleetData = (FleetDataAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{FleetDataAPI.class},
                (p, m, a) -> "getMembersListCopy".equals(m.getName()) ? new ArrayList<>(allMembers) : null);
        final FactionAPI playerFaction = (FactionAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{FactionAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getId".equals(n)) return "player";
                    if ("getDisplayName".equals(n)) return "Player";
                    if ("getBaseUIColor".equals(n)) return new Color(110, 210, 255, 255);
                    if ("getBrightUIColor".equals(n)) return new Color(170, 235, 255, 255);
                    if ("getDarkUIColor".equals(n)) return new Color(35, 75, 105, 255);
                    if ("getGridUIColor".equals(n)) return new Color(60, 125, 165, 255);
                    if ("getSecondaryUIColor".equals(n)) return new Color(110, 210, 255, 255);
                    if (m.getReturnType() == Color.class) return new Color(110, 210, 255, 255);
                    if (m.getReturnType() == boolean.class) return false;
                    return null;
                });
        CampaignFleetAPI playerFleet = (CampaignFleetAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{CampaignFleetAPI.class},
                (p, m, a) -> {
                    if ("getFleetData".equals(m.getName())) return fleetData;
                    if ("getFlagship".equals(m.getName())) return allMembers.get(0);
                    if ("getCommander".equals(m.getName())) return flagshipCaptain;
                    if ("getFaction".equals(m.getName())) return playerFaction;
                    if (m.getReturnType() == boolean.class) return false;
                    return null;
                });
        SectorAPI sector = (SectorAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{SectorAPI.class},
                (p, m, a) -> {
                    if ("getPlayerFleet".equals(m.getName())) return playerFleet;
                    if ("getPersistentData".equals(m.getName())) return pdata;
                    if ("getPlayerFaction".equals(m.getName())) return playerFaction;
                    if (m.getReturnType() == boolean.class) return false;
                    return null;
                });

        PermanentDivisionSpec div1 = new PermanentDivisionSpec("div720_1", "Battle Division 1", DivisionArchetype.BATTLESHIP_DIVISION);
        for (int i = 0; i < 6; i++) div1.memberIds.add("sim720_ship_" + i);
        div1.flagshipMemberId = "sim720_ship_0";
        Method saveSpecsM = PermanentDivisionManager.class.getDeclaredMethod("saveSpecsToPersistentData", Map.class, List.class);
        saveSpecsM.setAccessible(true);
        saveSpecsM.invoke(null, pdata, Collections.singletonList(div1));
        Global.setSector(sector);

        try {
            simulatedScreenWidth = 1280f;
            simulatedScreenHeight = 720f;

            // A. FleetDoctrineDialog at 1280x720 (must clamp to 1240x688, [20..1260] x [16..704], zero button/label overflow, 5 pennants non-overlapping)
            z hostDoctrine = new z();
            hostDoctrine.setSize(1280f, 720f);
            FleetDoctrineDialog docDlg = new FleetDoctrineDialog(hostDoctrine);
            docDlg.show();
            if (docDlg.getActiveDialogWidth() != 1240f || docDlg.getActiveDialogHeight() != 688f) {
                System.out.println("FAIL 720p FleetDoctrineDialog dimensions: "
                        + docDlg.getActiveDialogWidth() + "x" + docDlg.getActiveDialogHeight());
                return false;
            }
            for (String cmd : new String[]{
                    "TAB:OTC_COMMAND",
                    "TAB:AUTO_ASSIGN",
                    "TAB:MODIFIERS",
                    "TOGGLE_HULL_GROUP:BATTLESHIPS",
                    "TAB:HULLS",
                    "TAB:NAMING",
                    "TAB:ALL",
                    "RESET_DEFAULTS",
                    "CANCEL_RESET"}) {
                docDlg.buttonPressed(cmd);
                docDlg.advance(0.1f);
                if (!assertPositionTreeInvariantsAndRecompute(hostDoctrine.getPosition())) {
                    return false;
                }
                List<LabelAPI> labels = new ArrayList<>();
                List<ButtonAPI> buttons = new ArrayList<>();
                collectLabelsAndButtonsRecursive(hostDoctrine, labels, buttons);
                for (ButtonAPI btn : buttons) {
                    PositionAPI bp = btn.getPosition();
                    if (bp != null && bp.getWidth() > 0f) {
                        if (bp.getX() < 18f || (bp.getX() + bp.getWidth()) > 1262f) {
                            System.out.println("FAIL 720p FleetDoctrineDialog button out of horizontal bounds on " + cmd
                                    + ": [" + bp.getX() + ".." + (bp.getX() + bp.getWidth()) + "] text=\"" + btn.getText() + "\"");
                            return false;
                        }
                    }
                }
                for (LabelAPI lbl : labels) {
                    PositionAPI lp = lbl.getPosition();
                    if (lp != null && lp.getWidth() > 0f) {
                        if (lp.getX() < 18f || (lp.getX() + lp.getWidth()) > 1264f) {
                            System.out.println("FAIL 720p FleetDoctrineDialog label out of horizontal bounds on " + cmd
                                    + ": [" + lp.getX() + ".." + (lp.getX() + lp.getWidth()) + "] text=\"" + lbl.getText() + "\"");
                            return false;
                        }
                    }
                }
            }
            docDlg.close();

            // B. DivisionEditorDialog + DivisionConfigureSubscreen at 1280x720
            z hostEditor = new z();
            hostEditor.setSize(1280f, 720f);
            DivisionEditorDialog edDlg = new DivisionEditorDialog(hostEditor);
            edDlg.show();
            if (edDlg.getActiveDialogWidth() != 1240f || edDlg.getActiveDialogHeight() != 688f) {
                System.out.println("FAIL 720p DivisionEditorDialog dimensions: "
                        + edDlg.getActiveDialogWidth() + "x" + edDlg.getActiveDialogHeight());
                return false;
            }
            for (String cmd : new String[]{
                    "SET_OTC_AUTH:FULL_FREEDOM",
                    "SET_OTC_AUTH:COMMAND_BY_DIRECTION",
                    "OPEN_DIV:div720_1",
                    "TOGGLE_DD:DIV_ARCH_DD",
                    "TOGGLE_DIV_RAMMING_OPTIONS",
                    "TOGGLE_DIV_CARRIER_OPTIONS",
                    "SET_DOC:AGGRESSIVE",
                    "DELETE_DIV:div720_1",
                    "CANCEL_DELETE",
                    "BACK_TO_LIST"}) {
                edDlg.buttonPressed(cmd);
                edDlg.advance(0.1f);
                if (!assertPositionTreeInvariantsAndRecompute(hostEditor.getPosition())) {
                    return false;
                }
                List<LabelAPI> labels = new ArrayList<>();
                List<ButtonAPI> buttons = new ArrayList<>();
                collectLabelsAndButtonsRecursive(hostEditor, labels, buttons);
                for (ButtonAPI btn : buttons) {
                    PositionAPI bp = btn.getPosition();
                    if (bp != null && bp.getWidth() > 0f) {
                        if (bp.getX() < 18f || (bp.getX() + bp.getWidth()) > 1262f) {
                            System.out.println("FAIL 720p DivisionEditorDialog button out of horizontal bounds on " + cmd
                                    + ": [" + bp.getX() + ".." + (bp.getX() + bp.getWidth()) + "] text=\"" + btn.getText() + "\"");
                            return false;
                        }
                    }
                }
                // Verify redundant Row B posture buttons are absent in Configure view
                if ("SET_DOC:AGGRESSIVE".equals(cmd)) {
                    List<PositionAPI> postureBtns = new ArrayList<>();
                    for (ButtonAPI btn : buttons) {
                        Object cd = btn.getCustomData();
                        if (cd instanceof String && ((String) cd).startsWith("SET_DOC:")) {
                            postureBtns.add(btn.getPosition());
                        }
                    }
                    if (!postureBtns.isEmpty()) {
                        System.out.println("FAIL 720p unexpected posture buttons present: " + postureBtns.size());
                        return false;
                    }
                }
            }
            edDlg.close();

            // C. Pre-Battle FleetDeploymentDialog + FightingStyleModalDialog + OtcSelectionModalDialog at 1280x720
            Constructor<z> panelCtor = z.class.getDeclaredConstructor();
            Constructor<SimFdd> fddCtor = (Constructor<SimFdd>) ReflectionFactory.getReflectionFactory()
                    .newConstructorForSerialization(SimFdd.class, panelCtor);
            SimFdd fdd = fddCtor.newInstance();
            fdd.selectedMembers = new ArrayList<>();
            z innerPanel = new z();
            fdd.setSize(624f, 460f);
            innerPanel.setSize(604f, 440f);
            fdd.add(innerPanel).inTL(10f, 10f);
            for (Field f : oO0O.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("interface.null$Object")) f.setInt(fdd, 0);
                else if (f.getName().equals("while.null$Object")) f.setBoolean(fdd, false);
            }
            for (Field f : E.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == z.class) f.set(fdd, innerPanel);
                else if (f.getType() == Map.class) f.set(fdd, new HashMap<>());
            }
            supersuper deployedGrid = buildRealShipGrid(fdd, 592f, 174f, 10, Collections.<FleetMember>emptyList());
            supersuper reservesGrid = buildRealShipGrid(fdd, 592f, 174f, 10, reserveMembers);
            z sep1 = new z();
            sep1.setSize(592f, 7f);
            z sep2 = new z();
            sep2.setSize(542f, 1f);
            LabelAPI crNote = Global.getSettings().createLabel("Combat readiness note", Fonts.DEFAULT_SMALL);
            ((UIComponentAPI) crNote).getPosition().setSize(250f, 22f);
            innerPanel.add(deployedGrid).inTL(10f, 10f);
            innerPanel.add(sep1).belowLeft(deployedGrid, 10f);
            innerPanel.add(reservesGrid).belowLeft(sep1, 10f);
            innerPanel.add(sep2).belowLeft(reservesGrid, 15f);
            innerPanel.add((c) crNote).belowLeft(sep2, 6f);

            z widgetPanel = new z();
            widgetPanel.setSize(1280f, 720f);
            widgetPanel.add(fdd).inMid();

            CombatState cs = allocateWithoutConstructor(CombatState.class);
            for (Field f : CombatState.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("widgetPanel")) {
                    f.set(cs, widgetPanel);
                } else if (f.getType().getName().equals("com.fs.starfarer.combat.CombatState$o")) {
                    f.set(cs, Enum.valueOf((Class) f.getType(), "DEPLOY"));
                }
            }
            CombatEngineAPI proxyEngine = (CombatEngineAPI) Proxy.newProxyInstance(
                    StarsectorUISimulator.class.getClassLoader(),
                    new Class<?>[]{CombatEngineAPI.class},
                    (p, m, a) -> {
                        if ("getCombatUI".equals(m.getName())) return cs;
                        if ("getShips".equals(m.getName())) return Collections.emptyList();
                        if (m.getReturnType() == boolean.class) return false;
                        if (m.getReturnType() == float.class) return 0f;
                        if (m.getReturnType() == int.class) return 0;
                        return null;
                    });
            CommanderProfile pCmdr = new CommanderProfile(flagshipCaptain, "steady");
            BattleGroupState playerGroup = new BattleGroupState(0, pCmdr);
            BattleGroupState enemyGroup = new BattleGroupState(1, new CommanderProfile(null, "steady"));
            DivisionState dState1 = new DivisionState("div720_1", 0, 1, DivisionArchetype.BATTLESHIP_DIVISION, "Battle Division 1", pCmdr);
            playerGroup.divisions.add(dState1);
            playerGroup.mainEffortDivision = dState1;

            DivisionDeploymentDialogOrganizer organizer = new DivisionDeploymentDialogOrganizer();
            organizer.setBattleContext(playerGroup, enemyGroup, new DynamicBattleMusicController());
            organizer.advance(proxyEngine);
            innerPanel.advance(0.1f);
            assertPositionTreeInvariantsAndRecompute(widgetPanel.getPosition());

            PositionAPI fddPos = fdd.getPosition();
            PositionAPI resPos = reservesGrid.getPosition();
            if (fddPos.getX() < 0f || (fddPos.getX() + fddPos.getWidth()) > 1280f
                    || fddPos.getY() < 0f || (fddPos.getY() + fddPos.getHeight()) > 720f) {
                System.out.println("FAIL 720p DeploymentDialog out of 1280x720 viewport: "
                        + fddPos.getX() + "," + fddPos.getY() + " " + fddPos.getWidth() + "x" + fddPos.getHeight());
                return false;
            }
            // At 1280x720, maxRows = 5 -> reservesGrid height = 290f (< 360f)
            if (Math.abs(resPos.getHeight() - 290f) > 3f) {
                System.out.println("FAIL 720p reservesGrid expected 290f height, got " + resPos.getHeight());
                return false;
            }
            CustomPanelAPI fcPanel = null;
            for (c child : innerPanel.getChildrenCopy()) {
                if (child instanceof CustomPanelAPI && child != deployedGrid && child != reservesGrid) {
                    fcPanel = (CustomPanelAPI) child;
                }
            }
            if (fcPanel == null) {
                System.out.println("FAIL 720p fcPanel null");
                return false;
            }
            float origFcX = fcPanel.getPosition().getX();
            float origFcY = fcPanel.getPosition().getY();
            float origResX = resPos.getX();
            float origResY = resPos.getY();

            // Open FightingStyleModalDialog at 720p -> must expand upward over FleetCommandPanel (>= 440px tall) and hide fcPanel
            Method openStyleM = DivisionDeploymentDialogOrganizer.class.getDeclaredMethod(
                    "openFightingStyleWindow",
                    UIPanelAPI.class,
                    Object.class,
                    float.class,
                    boolean.class,
                    Runnable.class);
            openStyleM.setAccessible(true);
            openStyleM.invoke(organizer, innerPanel, reservesGrid, 940f, true, null);
            assertPositionTreeInvariantsAndRecompute(widgetPanel.getPosition());
            CustomPanelAPI styleModal = null;
            for (c child : innerPanel.getChildrenCopy()) {
                if (child instanceof CustomPanelAPI && child != fcPanel && child != deployedGrid && child != reservesGrid) {
                    styleModal = (CustomPanelAPI) child;
                }
            }
            if (styleModal == null || styleModal.getPosition().getHeight() < 440f) {
                System.out.println("FAIL 720p FightingStyleModalDialog did not expand upward: "
                        + (styleModal == null ? "null" : styleModal.getPosition().getHeight()));
                return false;
            }
            if (Math.abs(fcPanel.getPosition().getX() - origFcX) < 1000f) {
                System.out.println("FAIL 720p fcPanel was not hidden while expanded modal was open");
                return false;
            }
            // Close FightingStyleModalDialog -> both fcPanel and reservesGrid must return to exact original positions!
            organizer.closeFightingStyleWindow(innerPanel, reservesGrid);
            assertPositionTreeInvariantsAndRecompute(widgetPanel.getPosition());
            if (Math.abs(fcPanel.getPosition().getX() - origFcX) > 1.5f
                    || Math.abs(fcPanel.getPosition().getY() - origFcY) > 1.5f
                    || Math.abs(resPos.getX() - origResX) > 1.5f
                    || Math.abs(resPos.getY() - origResY) > 1.5f) {
                System.out.println("FAIL 720p fcPanel/reservesGrid failed to restore exact coords after FightingStyle close");
                return false;
            }

            // Open OtcSelectionModalDialog at 720p -> must also expand upward and cleanly restore on close
            Method openOtcM = DivisionDeploymentDialogOrganizer.class.getDeclaredMethod(
                    "openOtcSelectorWindow",
                    CombatEngineAPI.class,
                    UIPanelAPI.class,
                    Object.class,
                    float.class,
                    List.class,
                    List.class,
                    com.fs.starfarer.api.fleet.FleetMemberAPI[].class,
                    boolean.class,
                    Runnable.class);
            openOtcM.setAccessible(true);
            openOtcM.invoke(
                    organizer,
                    proxyEngine,
                    innerPanel,
                    reservesGrid,
                    940f,
                    Collections.<com.fs.starfarer.api.fleet.FleetMemberAPI>emptyList(),
                    new ArrayList<com.fs.starfarer.api.fleet.FleetMemberAPI>(reserveMembers),
                    new com.fs.starfarer.api.fleet.FleetMemberAPI[]{allMembers.get(0)},
                    true,
                    null);
            assertPositionTreeInvariantsAndRecompute(widgetPanel.getPosition());
            CustomPanelAPI otcModal = null;
            for (c child : innerPanel.getChildrenCopy()) {
                if (child instanceof CustomPanelAPI && child != fcPanel && child != deployedGrid && child != reservesGrid) {
                    otcModal = (CustomPanelAPI) child;
                }
            }
            if (otcModal == null || otcModal.getPosition().getHeight() < 440f) {
                System.out.println("FAIL 720p OtcSelectionModalDialog did not expand upward: "
                        + (otcModal == null ? "null" : otcModal.getPosition().getHeight()));
                return false;
            }
            organizer.closeOtcSelectorWindow(innerPanel, reservesGrid);
            assertPositionTreeInvariantsAndRecompute(widgetPanel.getPosition());
            if (Math.abs(fcPanel.getPosition().getX() - origFcX) > 1.5f
                    || Math.abs(fcPanel.getPosition().getY() - origFcY) > 1.5f) {
                System.out.println("FAIL 720p fcPanel failed to restore exact coords after OtcSelection close");
                return false;
            }

            // D. DivisionFleetTabInjector crowded 720p modded sidebar fallback
            z leftSidebar = new z();
            leftSidebar.setSize(220f, 720f);
            leftSidebar.getPosition().inTL(20f, 0f);
            SimButton autoAssignBtn = new SimButton(190f, 24f, "Auto-assign", "AUTO_ASSIGN");
            leftSidebar.add(autoAssignBtn).inTL(0f, 18f);
            // Populate contiguous modded sidebar widgets from below autoAssignBtn down to bottomY = 34f (< 68f needed below!)
            List<z> modSidebarWidgets = new ArrayList<>();
            float curTopOff = 50f;
            while (720f - curTopOff - 56f >= 24f) {
                z w = new z();
                w.setSize(190f, 56f);
                leftSidebar.add(w).inTL(0f, curTopOff);
                modSidebarWidgets.add(w);
                curTopOff += 62f;
            }
            CustomPanelAPI btnBlock = Global.getSettings().createCustom(190f, 58f, null);
            float[] inTlRef = new float[]{180f};
            leftSidebar.add((c) btnBlock).inTL(0f, inTlRef[0]);
            assertPositionTreeInvariantsAndRecompute(leftSidebar.getPosition());

            List<UIComponentAPI> sidebarChildren = new ArrayList<UIComponentAPI>(leftSidebar.getChildrenCopy());
            divisionai.DivisionFleetTabInjector.alignPanelBelowSidebarSiblings(
                    leftSidebar,
                    autoAssignBtn,
                    btnBlock,
                    sidebarChildren,
                    null,
                    inTlRef,
                    true);
            assertPositionTreeInvariantsAndRecompute(leftSidebar.getPosition());

            PositionAPI blockPos = btnBlock.getPosition();
            if (blockPos.getY() < 8f || (blockPos.getX() + blockPos.getWidth()) > 1280f) {
                System.out.println("FAIL 720p crowded sidebar button block out of bounds: "
                        + blockPos.getX() + "," + blockPos.getY());
                return false;
            }
            for (z mw : modSidebarWidgets) {
                PositionAPI mp = mw.getPosition();
                boolean overlapX = blockPos.getX() < (mp.getX() + mp.getWidth() - 1f)
                        && (blockPos.getX() + blockPos.getWidth() - 1f) > mp.getX();
                boolean overlapY = blockPos.getY() < (mp.getY() + mp.getHeight() - 1f)
                        && (blockPos.getY() + blockPos.getHeight() - 1f) > mp.getY();
                if (overlapX && overlapY) {
                    System.out.println("FAIL 720p crowded sidebar button block overlapped modded sidebar widget at y=" + mp.getY());
                    return false;
                }
            }

            return true;
        } finally {
            simulatedScreenWidth = 1920f;
            simulatedScreenHeight = 1080f;
        }
    }

    private static boolean simulateOnboardingCodexAndConfigureClarity1To1() throws Throwable {
        Boolean prevIntroOverride = divisionai.DivisionAISharedData.testFleetIntroCompletedOverride;
        com.fs.starfarer.api.impl.codex.CodexEntryPlugin prevRoot = com.fs.starfarer.api.impl.codex.CodexDataV2.ROOT;
        Map<String, com.fs.starfarer.api.impl.codex.CodexEntryPlugin> prevEntries =
                new LinkedHashMap<>(com.fs.starfarer.api.impl.codex.CodexDataV2.ENTRIES);
        boolean prevWithMechanics = com.fs.starfarer.api.impl.codex.CodexDataV2.WITH_GAME_MECHANICS_CAT;
        try {
            // 1. Easier-to-Understand Mission Filtering & Archetype-Prioritized Ordering
            List<PermanentDivisionManager.DivisionMissionType> nonCarrierOpts =
                    PermanentDivisionManager.DivisionMissionType.getOptionsForDivisionType(false);
            List<PermanentDivisionManager.DivisionMissionType> carrierOpts =
                    PermanentDivisionManager.DivisionMissionType.getOptionsForDivisionType(true);
            if (nonCarrierOpts.contains(PermanentDivisionManager.DivisionMissionType.CARRIER_STANDOFF)
                    || !carrierOpts.contains(PermanentDivisionManager.DivisionMissionType.CARRIER_STANDOFF)) {
                System.out.println("FAIL CARRIER_STANDOFF leaked into non-carrier hull mission list");
                return false;
            }
            if (PermanentDivisionManager.DivisionMissionType.missionsForArchetype(DivisionArchetype.BATTLESHIP_DIVISION)[0]
                            != PermanentDivisionManager.DivisionMissionType.CLOSE_ACTION
                    || PermanentDivisionManager.DivisionMissionType.missionsForArchetype(DivisionArchetype.MISSILE_CRUISER_DIVISION)[0]
                            != PermanentDivisionManager.DivisionMissionType.STANDOFF_ENGAGEMENT
                    || PermanentDivisionManager.DivisionMissionType.missionsForArchetype(DivisionArchetype.STRIKE_CRUISER_DIVISION)[0]
                            != PermanentDivisionManager.DivisionMissionType.TOT_ALPHA_STRIKE
                    || PermanentDivisionManager.DivisionMissionType.missionsForArchetype(DivisionArchetype.FRIGATE_DIVISION)[0]
                            != PermanentDivisionManager.DivisionMissionType.SCREEN) {
                System.out.println("FAIL missionsForArchetype did not prioritize natural role missions first");
                return false;
            }

            // 2. Codex Generation (Vanilla Spacer's Manual path + LunaLib Mods path) & Custom Detail Rendering
            com.fs.starfarer.api.impl.codex.CodexDataV2.ENTRIES.clear();
            com.fs.starfarer.api.impl.codex.CodexEntryV2 root =
                    new com.fs.starfarer.api.impl.codex.CodexEntryV2("codex_root", "Codex", null);
            com.fs.starfarer.api.impl.codex.CodexEntryV2 mechCat =
                    new com.fs.starfarer.api.impl.codex.CodexEntryV2(
                            com.fs.starfarer.api.impl.codex.CodexDataV2.CAT_GAME_MECHANICS, "Spacer's Manual", null);
            root.addChild(mechCat);
            com.fs.starfarer.api.impl.codex.CodexDataV2.ENTRIES.put(mechCat.getId(), mechCat);
            com.fs.starfarer.api.impl.codex.CodexDataV2.ROOT = root;
            com.fs.starfarer.api.impl.codex.CodexDataV2.WITH_GAME_MECHANICS_CAT = false;

            divisionai.DivisionCodexSetup.onAboutToStartGeneratingCodex();
            if (!com.fs.starfarer.api.impl.codex.CodexDataV2.WITH_GAME_MECHANICS_CAT) {
                System.out.println("FAIL onAboutToStartGeneratingCodex did not enable WITH_GAME_MECHANICS_CAT");
                return false;
            }
            divisionai.DivisionCodexSetup.onCodexDataGenerated();

            com.fs.starfarer.api.impl.codex.CodexEntryPlugin divCat =
                    com.fs.starfarer.api.impl.codex.CodexDataV2.getEntry(divisionai.DivisionCodexSetup.CAT_DIVISION_AI);
            if (divCat == null || divCat.getParent() != mechCat
                    || !"graphics/icons/icon_regular.png".equals(divCat.getIcon())) {
                System.out.println("FAIL CAT_DIVISION_AI not attached under CAT_GAME_MECHANICS with icon_regular");
                return false;
            }

            String[] expectedEntries = new String[]{
                    divisionai.DivisionCodexSetup.ENTRY_OVERVIEW,
                    divisionai.DivisionCodexSetup.ENTRY_OTC,
                    divisionai.DivisionCodexSetup.ENTRY_ORGANIZATION,
                    divisionai.DivisionCodexSetup.ENTRY_MISSIONS,
                    divisionai.DivisionCodexSetup.ENTRY_FORMATIONS,
                    divisionai.DivisionCodexSetup.ENTRY_WARROOM
            };
            for (String entryId : expectedEntries) {
                com.fs.starfarer.api.impl.codex.CodexEntryPlugin entry =
                        com.fs.starfarer.api.impl.codex.CodexDataV2.getEntry(entryId);
                if (entry == null || entry.getParent() != divCat || !entry.hasDetail()
                        || !"graphics/icons/icon_regular.png".equals(entry.getIcon())) {
                    System.out.println("FAIL Codex entry missing, invalid, or wrong icon: " + entryId);
                    return false;
                }
                CustomPanelAPI detailHost = Global.getSettings().createCustom(720f, 680f, null);
                entry.createCustomDetail(detailHost, null, null);
                assertPositionTreeInvariantsAndRecompute(detailHost.getPosition());
                List<LabelAPI> cLabels = new ArrayList<>();
                List<ButtonAPI> cButtons = new ArrayList<>();
                collectLabelsAndButtonsRecursive(detailHost, cLabels, cButtons);
                if (cLabels.size() < 4) {
                    System.out.println("FAIL Codex entry rendered too few labels: " + entryId + " (" + cLabels.size() + ")");
                    return false;
                }
                StringBuilder joined = new StringBuilder();
                for (LabelAPI lbl : cLabels) {
                    if (!isPurePrintableAscii(lbl.getText())) {
                        System.out.println("FAIL Non-ASCII text in Codex entry " + entryId + ": " + lbl.getText());
                        return false;
                    }
                    joined.append(lbl.getText()).append('\n');
                }
                if (divisionai.DivisionCodexSetup.ENTRY_OVERVIEW.equals(entryId)) {
                    String ov = joined.toString();
                    for (String tier : new String[]{
                            "1. Player",
                            "2. The Officer in Tactical Command",
                            "3. Division Leader",
                            "4. Ship Captain",
                            "5. Basic Ship AI"}) {
                        if (!ov.contains(tier)) {
                            System.out.println("FAIL Overview Codex entry missing C2 stack tier: " + tier);
                            return false;
                        }
                    }
                }
            }

            // Test LunaLib "lunalib_mods" category attachment & idempotence
            com.fs.starfarer.api.impl.codex.CodexDataV2.ENTRIES.clear();
            com.fs.starfarer.api.impl.codex.CodexEntryV2 root2 =
                    new com.fs.starfarer.api.impl.codex.CodexEntryV2("codex_root", "Codex", null);
            com.fs.starfarer.api.impl.codex.CodexEntryV2 lunaModsCat =
                    new com.fs.starfarer.api.impl.codex.CodexEntryV2(
                            divisionai.DivisionCodexSetup.LUNALIB_MODS_CAT_ID, "Mods", null);
            root2.addChild(lunaModsCat);
            com.fs.starfarer.api.impl.codex.CodexDataV2.ENTRIES.put(lunaModsCat.getId(), lunaModsCat);
            com.fs.starfarer.api.impl.codex.CodexDataV2.ROOT = root2;
            divisionai.DivisionCodexSetup.onCodexDataGenerated();
            divisionai.DivisionCodexSetup.onCodexDataGenerated(); // Idempotent second call
            com.fs.starfarer.api.impl.codex.CodexEntryPlugin divCatLuna =
                    com.fs.starfarer.api.impl.codex.CodexDataV2.getEntry(divisionai.DivisionCodexSetup.CAT_DIVISION_AI);
            if (divCatLuna == null || divCatLuna.getParent() != lunaModsCat || lunaModsCat.getChildren().size() != 1) {
                System.out.println("FAIL LunaLib Mods category attachment or idempotence failed");
                return false;
            }

            // Verify F2 contextual action/label -> Codex entry resolution & OPEN_CODEX button wiring
            if (!divisionai.DivisionCodexSetup.ENTRY_OTC.equals(
                            divisionai.DivisionCodexSetup.resolveCodexEntryForAction("SET_OTC_AUTH:COMMAND_BY_DIRECTION"))
                    || !divisionai.DivisionCodexSetup.ENTRY_ORGANIZATION.equals(
                            divisionai.DivisionCodexSetup.resolveCodexEntryForAction("SET_ARCH:CRUISER_DIVISION"))
                    || !divisionai.DivisionCodexSetup.ENTRY_MISSIONS.equals(
                            divisionai.DivisionCodexSetup.resolveCodexEntryForAction("DIV_MAIN_MIS:CLOSE_ACTION"))
                    || !divisionai.DivisionCodexSetup.ENTRY_FORMATIONS.equals(
                            divisionai.DivisionCodexSetup.resolveCodexEntryForAction("DIV_FORMATION:FORWARD_WEDGE"))
                    || !divisionai.DivisionCodexSetup.ENTRY_WARROOM.equals(
                            divisionai.DivisionCodexSetup.resolveCodexEntryForAction("WARROOM_TOGGLE_TIME"))
                    || !divisionai.DivisionCodexSetup.ENTRY_FORMATIONS.equals(
                            divisionai.DivisionCodexSetup.resolveCodexEntryForLabel("Spacing & C2 Tuning:"))) {
                System.out.println("FAIL F2 Codex resolver returned unexpected entry IDs");
                return false;
            }

            // Verify OPEN_CODEX buttons in FleetDoctrineDialog & DivisionEditorDialog
            z codexHost = new z();
            codexHost.setSize(1920f, 1080f);
            FleetDoctrineDialog docDlg = new FleetDoctrineDialog(codexHost);
            docDlg.show();
            divisionai.DivisionCodexSetup.lastOpenedCodexEntryForTests = null;
            docDlg.buttonPressed("OPEN_CODEX:" + divisionai.DivisionCodexSetup.ENTRY_OTC);
            docDlg.advance(0.1f);
            if (!divisionai.DivisionCodexSetup.ENTRY_OTC.equals(divisionai.DivisionCodexSetup.lastOpenedCodexEntryForTests)) {
                System.out.println("FAIL FleetDoctrineDialog OPEN_CODEX button did not trigger ENTRY_OTC");
                return false;
            }
            docDlg.close();

            DivisionEditorDialog edDlg = new DivisionEditorDialog(codexHost);
            edDlg.show();
            divisionai.DivisionCodexSetup.lastOpenedCodexEntryForTests = null;
            edDlg.buttonPressed("OPEN_CODEX:" + divisionai.DivisionCodexSetup.ENTRY_ORGANIZATION);
            edDlg.advance(0.1f);
            if (!divisionai.DivisionCodexSetup.ENTRY_ORGANIZATION.equals(divisionai.DivisionCodexSetup.lastOpenedCodexEntryForTests)) {
                System.out.println("FAIL DivisionEditorDialog OPEN_CODEX button did not trigger ENTRY_ORGANIZATION");
                return false;
            }
            edDlg.buttonPressed("OPEN_DIV:div_1");
            edDlg.advance(0.1f);
            edDlg.buttonPressed("OPEN_CODEX:" + divisionai.DivisionCodexSetup.ENTRY_MISSIONS);
            edDlg.advance(0.1f);
            if (!divisionai.DivisionCodexSetup.ENTRY_MISSIONS.equals(divisionai.DivisionCodexSetup.lastOpenedCodexEntryForTests)) {
                System.out.println("FAIL Configure Division OPEN_CODEX button did not trigger ENTRY_MISSIONS");
                return false;
            }
            edDlg.close();

            // 3. Soft Fleet Intro Tour (DivisionOnboardingOverlay) & Real-Game Conflict Guards
            // Note: In real Starsector CampaignState, isShowingDialog() is TRUE whenever CoreUITabId.FLEET is open!
            SectorAPI sector = Global.getSector();
            com.fs.starfarer.api.campaign.CampaignUIAPI mockCampUi =
                    (com.fs.starfarer.api.campaign.CampaignUIAPI) Proxy.newProxyInstance(
                            com.fs.starfarer.api.campaign.CampaignUIAPI.class.getClassLoader(),
                            new Class<?>[]{com.fs.starfarer.api.campaign.CampaignUIAPI.class},
                            (p, m, a) -> {
                                String n = m.getName();
                                if ("getCurrentCoreTab".equals(n)) return com.fs.starfarer.api.campaign.CoreUITabId.FLEET;
                                if ("isShowingDialog".equals(n)) return Boolean.TRUE;
                                if ("isShowingMenu".equals(n)) return Boolean.FALSE;
                                return null;
                            });
            java.lang.invoke.MethodHandle childrenHandle = java.lang.invoke.MethodHandles.lookup().findVirtual(
                    z.class, "getChildrenCopy", java.lang.invoke.MethodType.methodType(List.class));

            z coreUI = new z();
            coreUI.setSize(1920f, 1080f);
            // Simulate native full-screen TriPad frame sibling (com.fs.starfarer.ui.newui.X, non-CustomPanelAPI)
            z nativeTripadFrame = new z();
            nativeTripadFrame.setSize(1920f, 1080f);
            coreUI.add(nativeTripadFrame).inTL(0f, 0f);

            z fleetTab = new z();
            fleetTab.setSize(1920f, 1080f);
            coreUI.add(fleetTab).inTL(0f, 0f);

            CustomPanelAPI btnBlock = Global.getSettings().createCustom(205f, 52f, null);
            com.fs.starfarer.api.ui.TooltipMakerAPI btnTt = btnBlock.createUIElement(205f, 52f, false);
            ButtonAPI docBtn = btnTt.addButton("Fleet Doctrine", "OPEN_FLEET_DOCTRINE", 174f, 24f, 0f);
            docBtn.getPosition().inTL(27f, 0f);
            ButtonAPI orgBtn = btnTt.addButton("Fleet Organization", "OPEN_DIVISION_EDITOR", 174f, 24f, 0f);
            orgBtn.getPosition().inTL(27f, 28f);
            btnBlock.addUIElement(btnTt).inTL(0f, 0f);
            fleetTab.add((c) btnBlock).inTL(18f, 220f);
            assertPositionTreeInvariantsAndRecompute(coreUI.getPosition());

            // Test per-save sector memory directly (null test override)
            divisionai.DivisionAISharedData.testFleetIntroCompletedOverride = null;
            divisionai.DivisionAISharedData.resetFleetIntro();
            if (divisionai.DivisionAISharedData.isFleetIntroCompleted()) {
                System.out.println("FAIL Per-save intro flag should start false on fresh/existing save");
                return false;
            }

            // Baseline: clean Fleet tab with ships + isShowingDialog()==true + native TriPad sibling -> auto-start allowed
            if (!divisionai.DivisionOnboardingOverlay.canAutoStartOnFleetTab(
                    sector, mockCampUi, coreUI, fleetTab, btnBlock, childrenHandle)) {
                System.out.println("FAIL canAutoStartOnFleetTab should return true on real Fleet screen");
                return false;
            }

            // Guard A: Already completed in this save's memory -> suppressed
            divisionai.DivisionAISharedData.markFleetIntroCompleted();
            if (!divisionai.DivisionAISharedData.isFleetIntroCompleted()
                    || divisionai.DivisionOnboardingOverlay.canAutoStartOnFleetTab(
                            sector, mockCampUi, coreUI, fleetTab, btnBlock, childrenHandle)) {
                System.out.println("FAIL canAutoStartOnFleetTab did not suppress when intro completed in save memory");
                return false;
            }
            divisionai.DivisionAISharedData.resetFleetIntro();

            // Guard B: Base-game Galatia tutorial start ($tutorialInProgress / $tutorialRespawn) -> MUST STILL TRIGGER
            // (CampaignTutorialScript only touches CHARACTER and INTEL/MAP tabs, never CoreUITabId.FLEET!)
            if (sector != null && sector.getMemoryWithoutUpdate() != null) {
                sector.getMemoryWithoutUpdate().set("$tutorialInProgress", Boolean.TRUE);
                sector.getMemoryWithoutUpdate().set("$tutorialRespawn", Boolean.TRUE);
                boolean allowedInGalatiaStart = divisionai.DivisionOnboardingOverlay.canAutoStartOnFleetTab(
                        sector, mockCampUi, coreUI, fleetTab, btnBlock, childrenHandle);
                sector.getMemoryWithoutUpdate().unset("$tutorialInProgress");
                sector.getMemoryWithoutUpdate().unset("$tutorialRespawn");
                if (!allowedInGalatiaStart) {
                    System.out.println("FAIL Fleet intro tour was wrongly suppressed on a Galatia tutorial start");
                    return false;
                }
            }

            // Guard C: Another mod CustomPanelAPI modal mounted over coreUI -> suppressed
            CustomPanelAPI otherModal = Global.getSettings().createCustom(900f, 600f, null);
            coreUI.add((c) otherModal).inTL(510f, 240f);
            assertPositionTreeInvariantsAndRecompute(coreUI.getPosition());
            boolean blockedByModal = !divisionai.DivisionOnboardingOverlay.canAutoStartOnFleetTab(
                    sector, mockCampUi, coreUI, fleetTab, btnBlock, childrenHandle);
            coreUI.removeComponent((c) otherModal);
            if (!blockedByModal) {
                System.out.println("FAIL Modal conflict guard did not suppress Fleet intro tour");
                return false;
            }

            // Walk through all 4 steps on 1080p AND crowded 720p viewports
            for (int vpIdx = 0; vpIdx < 2; vpIdx++) {
                float sw = (vpIdx == 0) ? 1920f : 1280f;
                float sh = (vpIdx == 0) ? 1080f : 720f;
                simulatedScreenWidth = sw;
                simulatedScreenHeight = sh;
                coreUI.setSize(sw, sh);
                fleetTab.setSize(sw, sh);
                // On 720p test crowded bottom-left sidebar position (near bottom edge)
                btnBlock.getPosition().inTL(18f, vpIdx == 0 ? 220f : (sh - 70f));
                assertPositionTreeInvariantsAndRecompute(coreUI.getPosition());

                divisionai.DivisionAISharedData.testFleetIntroCompletedOverride = Boolean.FALSE;
                divisionai.DivisionOnboardingOverlay tour =
                        new divisionai.DivisionOnboardingOverlay(coreUI, btnBlock, docBtn, orgBtn);
                tour.show();

                String[] actions = new String[]{
                        null,
                        "TOUR_NEXT",
                        "TOUR_NEXT",
                        "TOUR_PREV",
                        "TOUR_NEXT",
                        "TOUR_NEXT",
                        "TOUR_NEXT",
                        "TOUR_NEXT",
                        "TOUR_NEXT",
                        "TOUR_NEXT"
                };
                int[] expectedStep = new int[]{0, 1, 2, 1, 2, 3, 4, 5, 6, 7};
                for (int i = 0; i < actions.length; i++) {
                    if (actions[i] != null) {
                        tour.buttonPressed(actions[i]);
                        tour.advance(0.1f);
                    }
                    if (tour.getStepIndex() != expectedStep[i] || tour.isClosed()) {
                        System.out.println("FAIL Tour step mismatch at i=" + i + ": got " + tour.getStepIndex());
                        return false;
                    }
                    assertPositionTreeInvariantsAndRecompute(coreUI.getPosition());
                    CustomPanelAPI card = tour.getRootCardPanel();
                    PositionAPI cp = card.getPosition();
                    PositionAPI bp = btnBlock.getPosition();
                    if (cp.getX() < 0f || cp.getY() < 0f
                            || (cp.getX() + cp.getWidth()) > sw + 1f
                            || (cp.getY() + cp.getHeight()) > sh + 1f) {
                        System.out.println("FAIL Tour card out of viewport bounds on " + sw + "x" + sh);
                        return false;
                    }
                    boolean overlapX = cp.getX() < (bp.getX() + bp.getWidth() - 1f)
                            && (cp.getX() + cp.getWidth() - 1f) > bp.getX();
                    boolean overlapY = cp.getY() < (bp.getY() + bp.getHeight() - 1f)
                            && (cp.getY() + cp.getHeight() - 1f) > bp.getY();
                    if (overlapX && overlapY) {
                        System.out.println("FAIL Tour card overlapped Fleet sidebar buttons on step " + expectedStep[i]);
                        return false;
                    }
                    List<LabelAPI> tLabels = new ArrayList<>();
                    List<ButtonAPI> tBtns = new ArrayList<>();
                    collectLabelsAndButtonsRecursive(card, tLabels, tBtns);
                    for (LabelAPI tl : tLabels) {
                        if (!isPurePrintableAscii(tl.getText())) {
                            System.out.println("FAIL Non-ASCII text in Tour card: " + tl.getText());
                            return false;
                        }
                    }
                }

                // Finish on step 4 -> closes cleanly & persists completion
                tour.buttonPressed("TOUR_NEXT");
                tour.advance(0.1f);
                if (!tour.isClosed() || !divisionai.DivisionAISharedData.isFleetIntroCompleted()) {
                    System.out.println("FAIL Finishing tour did not close overlay and mark intro completed");
                    return false;
                }
            }

            // Test full cross-screen guided tutorial with live TourNavigator (Fleet Doctrine 1a/1b -> Fleet Organization 2 + Auto-Assign)
            divisionai.DivisionAISharedData.resetFleetIntro();
            divisionai.PermanentDivisionManager.clearAllDivisions();
            final divisionai.FleetDoctrineDialog[] liveDocHolder = new divisionai.FleetDoctrineDialog[1];
            final divisionai.DivisionEditorDialog[] liveOrgHolder = new divisionai.DivisionEditorDialog[1];
            divisionai.DivisionOnboardingOverlay.TourNavigator simNav = new divisionai.DivisionOnboardingOverlay.TourNavigator() {
                @Override
                public void openFleetDoctrine() {
                    closeFleetOrganization();
                    if (liveDocHolder[0] == null || liveDocHolder[0].isClosed()) {
                        liveDocHolder[0] = new divisionai.FleetDoctrineDialog(coreUI);
                        liveDocHolder[0].show();
                    }
                }

                @Override
                public void closeFleetDoctrine() {
                    if (liveDocHolder[0] != null && !liveDocHolder[0].isClosed()) {
                        liveDocHolder[0].close();
                    }
                    liveDocHolder[0] = null;
                }

                @Override
                public divisionai.FleetDoctrineDialog getActiveDoctrineDialog() {
                    return liveDocHolder[0];
                }

                @Override
                public void openFleetOrganization() {
                    closeFleetDoctrine();
                    if (liveOrgHolder[0] == null || liveOrgHolder[0].isClosed()) {
                        liveOrgHolder[0] = new divisionai.DivisionEditorDialog(coreUI);
                        liveOrgHolder[0].show();
                    }
                }

                @Override
                public void closeFleetOrganization() {
                    if (liveOrgHolder[0] != null && !liveOrgHolder[0].isClosed()) {
                        liveOrgHolder[0].close();
                    }
                    liveOrgHolder[0] = null;
                }

                @Override
                public divisionai.DivisionEditorDialog getActiveOrganizationDialog() {
                    return liveOrgHolder[0];
                }
            };

            divisionai.DivisionOnboardingOverlay guidedTour =
                    new divisionai.DivisionOnboardingOverlay(coreUI, btnBlock, docBtn, orgBtn, simNav);
            guidedTour.show();
            List<LabelAPI> step0Labels = new ArrayList<>();
            collectLabelsAndButtonsRecursive(guidedTour.getRootCardPanel(), step0Labels, new ArrayList<>());
            boolean sawWelcomeModTitle = false;
            for (LabelAPI l : step0Labels) {
                if (l.getText() != null
                        && l.getText().contains("Welcome to Divisions: Command and Control, Commander!")
                        && !l.getText().contains("plain language")) {
                    sawWelcomeModTitle = true;
                }
            }
            if (!sawWelcomeModTitle) {
                System.out.println("FAIL Step 0 copy missing 'Welcome to Divisions: Command and Control!'");
                return false;
            }

            // Step 0 -> Open Doctrine -> Step 1 (Command Scheme: broad level + Domain Sector-standard + no change needed)
            guidedTour.buttonPressed("TOUR_NEXT");
            guidedTour.advance(0.1f);
            if (guidedTour.getStepIndex() != divisionai.DivisionOnboardingOverlay.STEP_DOCTRINE_COMMAND
                    || liveDocHolder[0] == null || liveDocHolder[0].isClosed()
                    || !"TAB:OTC_COMMAND".equals(liveDocHolder[0].getActiveTabCommand())) {
                System.out.println("FAIL Guided tour did not open FleetDoctrineDialog on Command Scheme");
                return false;
            }
            List<LabelAPI> step1aLabels = new ArrayList<>();
            collectLabelsAndButtonsRecursive(guidedTour.getRootCardPanel(), step1aLabels, new ArrayList<>());
            boolean sawDomainStandard = false;
            for (LabelAPI l : step1aLabels) {
                if (l.getText() != null
                        && l.getText().contains("how your fleet acts in battle at a broad level")
                        && l.getText().contains("Domain")
                        && l.getText().contains("do not need to change anything")) {
                    sawDomainStandard = true;
                }
            }
            if (!sawDomainStandard) {
                System.out.println("FAIL Step 1 copy missing broad-level / Domain / no-change reassurance");
                return false;
            }

            // Step 1 -> Division Defaults (TAB:MODIFIERS)
            guidedTour.buttonPressed("TOUR_NEXT");
            guidedTour.advance(0.1f);
            if (guidedTour.getStepIndex() != divisionai.DivisionOnboardingOverlay.STEP_DOCTRINE_DEFAULTS
                    || !"TAB:MODIFIERS".equals(liveDocHolder[0].getActiveTabCommand())) {
                System.out.println("FAIL Guided tour did not switch FleetDoctrineDialog to Division Defaults");
                return false;
            }
            List<LabelAPI> step1bLabels = new ArrayList<>();
            collectLabelsAndButtonsRecursive(guidedTour.getRootCardPanel(), step1bLabels, new ArrayList<>());
            boolean sawInherited = false;
            for (LabelAPI l : step1bLabels) {
                if (l.getText() != null && l.getText().contains("Division Defaults")
                        && l.getText().contains("inherited by your active divisions")
                        && l.getText().contains("Fleet Organization")) {
                    sawInherited = true;
                }
            }
            if (!sawInherited) {
                System.out.println("FAIL Division Defaults step missing active-division inheritance explanation");
                return false;
            }

            // Step 2 -> Division Creation (TAB:AUTO_ASSIGN) -> Hull Limits (TAB:HULLS) -> Nomenclature (TAB:NAMING)
            String[] expectedTabs = new String[]{"TAB:AUTO_ASSIGN", "TAB:HULLS", "TAB:NAMING"};
            int[] expectedDocSteps = new int[]{
                    divisionai.DivisionOnboardingOverlay.STEP_DOCTRINE_CREATION,
                    divisionai.DivisionOnboardingOverlay.STEP_DOCTRINE_HULLS,
                    divisionai.DivisionOnboardingOverlay.STEP_DOCTRINE_NAMING
            };
            for (int ti = 0; ti < expectedTabs.length; ti++) {
                guidedTour.buttonPressed("TOUR_NEXT");
                guidedTour.advance(0.1f);
                if (guidedTour.getStepIndex() != expectedDocSteps[ti]
                        || !expectedTabs[ti].equals(liveDocHolder[0].getActiveTabCommand())) {
                    System.out.println("FAIL Guided tour did not advance to Doctrine tab " + expectedTabs[ti]);
                    return false;
                }
            }

            // Step 5 -> Fleet Organization -> closes Doctrine, opens DivisionEditorDialog at Step 6 (Overview)
            guidedTour.buttonPressed("TOUR_NEXT");
            guidedTour.advance(0.1f);
            if (guidedTour.getStepIndex() != divisionai.DivisionOnboardingOverlay.STEP_ORG_OVERVIEW
                    || liveDocHolder[0] != null
                    || liveOrgHolder[0] == null || liveOrgHolder[0].isClosed()) {
                System.out.println("FAIL Guided tour did not transition from FleetDoctrineDialog to DivisionEditorDialog");
                return false;
            }
            List<LabelAPI> step2Labels = new ArrayList<>();
            collectLabelsAndButtonsRecursive(guidedTour.getRootCardPanel(), step2Labels, new ArrayList<>());
            boolean sawAutoAssignAndTune = false;
            for (LabelAPI l : step2Labels) {
                if (l.getText() != null && l.getText().contains("Auto-Assign")
                        && l.getText().contains("mainly inherit their values from Division Defaults")
                        && l.getText().contains("tune them yourself")) {
                    sawAutoAssignAndTune = true;
                }
            }
            if (!sawAutoAssignAndTune) {
                System.out.println("FAIL Fleet Organization step missing Auto-Assign / Division Defaults inheritance guidance");
                return false;
            }

            // Simulate clicking the real Auto-Assign button on DivisionEditorDialog while tour is active
            liveOrgHolder[0].buttonPressed("AUTO_ASSIGN");
            liveOrgHolder[0].advance(0.1f);
            guidedTour.advance(0.1f);
            if (!guidedTour.isAutoAssignPressed()
                    || !divisionai.PermanentDivisionManager.getUnassignedCombatMembers(
                            sector.getPlayerFleet(),
                            divisionai.PermanentDivisionManager.getPermanentDivisions()).isEmpty()) {
                System.out.println("FAIL Clicking AUTO_ASSIGN during guided tour did not organize ships immediately");
                return false;
            }

            // Step 6 -> Configure Division -> opens Configure Division subscreen at Step 7
            guidedTour.buttonPressed("TOUR_NEXT");
            guidedTour.advance(0.1f);
            if (guidedTour.getStepIndex() != divisionai.DivisionOnboardingOverlay.STEP_ORG_CONFIGURE
                    || !liveOrgHolder[0].isShowingConfigureDivision()
                    || liveOrgHolder[0].getDivisionTypeDropdownButton() == null) {
                System.out.println("FAIL Guided tour did not open Configure Division subscreen on Step 7");
                return false;
            }
            List<LabelAPI> stepCfgLabels = new ArrayList<>();
            collectLabelsAndButtonsRecursive(guidedTour.getRootCardPanel(), stepCfgLabels, new ArrayList<>());
            boolean sawCfgInherit = false;
            for (LabelAPI l : stepCfgLabels) {
                if (l.getText() != null
                        && l.getText().contains("mainly inherit their values from Division Defaults")
                        && l.getText().contains("Default (Doctrine)")) {
                    sawCfgInherit = true;
                }
            }
            if (!sawCfgInherit) {
                System.out.println("FAIL Configure Division step missing Division Defaults inheritance explanation");
                return false;
            }

            // Finish tour -> marks per-save intro completed
            guidedTour.buttonPressed("TOUR_NEXT");
            guidedTour.advance(0.1f);
            simNav.closeFleetOrganization();
            if (!guidedTour.isClosed() || !divisionai.DivisionAISharedData.isFleetIntroCompleted()) {
                System.out.println("FAIL Completing guided tour did not mark per-save completion");
                return false;
            }

            // Verify Combat Freighters as Warships switch (ON vs OFF) in FleetDoctrineDialog & PermanentDivisionManager
            com.fs.starfarer.api.fleet.FleetMemberAPI muleCf = createCustomMockMember(
                    "sim_cf_mule", "Mule", com.fs.starfarer.api.combat.ShipAPI.HullSize.DESTROYER, false,
                    "Combat Freighter", java.util.EnumSet.of(
                            com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.FREIGHTER,
                            com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.COMBAT));
            com.fs.starfarer.api.fleet.FleetMemberAPI cerberusCf = createCustomMockMember(
                    "sim_cf_cerberus", "Cerberus", com.fs.starfarer.api.combat.ShipAPI.HullSize.FRIGATE, true,
                    "Combat Freighter", java.util.EnumSet.of(
                            com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.CIVILIAN,
                            com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.FREIGHTER,
                            com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.COMBAT));
            com.fs.starfarer.api.fleet.FleetMemberAPI tarsusPureFreighter = createCustomMockMember(
                    "sim_pure_tarsus", "Tarsus", com.fs.starfarer.api.combat.ShipAPI.HullSize.DESTROYER, true,
                    "Freighter", java.util.EnumSet.of(
                            com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.CIVILIAN,
                            com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.FREIGHTER));
            if (!divisionai.ShipCapabilityProfile.isCombatFreighterHull(muleCf.getHullSpec())
                    || !divisionai.ShipCapabilityProfile.isCombatFreighterHull(cerberusCf.getHullSpec())
                    || divisionai.ShipCapabilityProfile.isCombatFreighterHull(tarsusPureFreighter.getHullSpec())) {
                System.out.println("FAIL isCombatFreighterHull misclassified Mule / Cerberus / Tarsus");
                return false;
            }
            divisionai.PermanentDivisionManager.updateCombatFreightersAsWarships(true);
            divisionai.PermanentDivisionManager.FleetDoctrineConfig docOn = divisionai.PermanentDivisionManager.getFleetDoctrine();
            if (divisionai.PermanentDivisionManager.classifyFleetMemberArchetype(muleCf, docOn) != divisionai.DivisionModel.DivisionArchetype.DESTROYER_DIVISION
                    || divisionai.PermanentDivisionManager.classifyFleetMemberArchetype(cerberusCf, docOn) != divisionai.DivisionModel.DivisionArchetype.FRIGATE_DIVISION
                    || divisionai.PermanentDivisionManager.classifyFleetMemberArchetype(tarsusPureFreighter, docOn) != divisionai.DivisionModel.DivisionArchetype.SERVICE_GROUP) {
                System.out.println("FAIL Combat Freighters as Warships ON did not route Mule/Cerberus to warship divisions while keeping Tarsus in Service Group");
                return false;
            }
            divisionai.FleetDoctrineDialog cfDocDlg = new divisionai.FleetDoctrineDialog(coreUI);
            cfDocDlg.show();
            cfDocDlg.buttonPressed("TAB:AUTO_ASSIGN");
            cfDocDlg.advance(0.1f);
            cfDocDlg.buttonPressed("SET_COMBAT_FREIGHTERS_WARSHIPS:OFF");
            cfDocDlg.advance(0.1f);
            divisionai.PermanentDivisionManager.FleetDoctrineConfig docOff = divisionai.PermanentDivisionManager.getFleetDoctrine();
            if (docOff.isCombatFreightersAsWarships()
                    || divisionai.PermanentDivisionManager.classifyFleetMemberArchetype(muleCf, docOff) != divisionai.DivisionModel.DivisionArchetype.SERVICE_GROUP
                    || divisionai.PermanentDivisionManager.classifyFleetMemberArchetype(cerberusCf, docOff) != divisionai.DivisionModel.DivisionArchetype.SERVICE_GROUP) {
                cfDocDlg.close();
                System.out.println("FAIL Combat Freighters as Warships OFF did not route Mule/Cerberus to Service Group");
                return false;
            }
            cfDocDlg.buttonPressed("SET_COMBAT_FREIGHTERS_WARSHIPS:ON");
            cfDocDlg.advance(0.1f);
            cfDocDlg.close();
            if (!divisionai.PermanentDivisionManager.getFleetDoctrine().isCombatFreightersAsWarships()) {
                System.out.println("FAIL Toggling Combat Freighters as Warships back ON failed");
                return false;
            }

            // Test same-hull-first Auto-Assign & distributePoolWithCommandersFirst (prefers grouping identical/same-family hulls up to hull limit)
            {
                List<com.fs.starfarer.api.fleet.FleetMemberAPI> interleavedFrigates = new ArrayList<>();
                // Interleave 4 Centurions (1 officer) and 4 Lashers (1 officer) -> must form [4 Centurions] and [4 Lashers], never mixed!
                for (int i = 0; i < 4; i++) {
                    interleavedFrigates.add(createCustomMockMemberWithHullId(
                            "aa_cent_" + i, "centurion", "centurion", "Centurion",
                            com.fs.starfarer.api.combat.ShipAPI.HullSize.FRIGATE, 6f, i == 0));
                    interleavedFrigates.add(createCustomMockMemberWithHullId(
                            "aa_lash_" + i, "lasher", i == 1 ? "lasher_xiv" : "lasher", "Lasher",
                            com.fs.starfarer.api.combat.ShipAPI.HullSize.FRIGATE, 5f, i == 0));
                }
                List<List<com.fs.starfarer.api.fleet.FleetMemberAPI>> frigChunks =
                        divisionai.PermanentDivisionManager.distributePoolWithCommandersFirst(interleavedFrigates, 4);
                if (frigChunks.size() != 2 || frigChunks.get(0).size() != 4 || frigChunks.get(1).size() != 4) {
                    System.out.println("FAIL Same-hull distributePoolWithCommandersFirst did not produce two 4-ship divisions");
                    return false;
                }
                for (List<com.fs.starfarer.api.fleet.FleetMemberAPI> chunk : frigChunks) {
                    String firstBase = chunk.get(0).getHullSpec().getBaseHullId();
                    for (com.fs.starfarer.api.fleet.FleetMemberAPI fm : chunk) {
                        if (!firstBase.equals(fm.getHullSpec().getBaseHullId())) {
                            System.out.println("FAIL Same-hull distributePoolWithCommandersFirst mixed hull families: "
                                    + firstBase + " vs " + fm.getHullSpec().getBaseHullId());
                            return false;
                        }
                    }
                }

                // Also verify live PermanentDivisionManager.autoAssignUnassignedMembers() (both Pass 3b creation and Pass 2a top-up!)
                com.fs.starfarer.api.campaign.SectorAPI prevSec = com.fs.starfarer.api.Global.getSector();
                final List<com.fs.starfarer.api.fleet.FleetMemberAPI> liveMembers = new ArrayList<>();
                final Map<String, Object> livePdata = new HashMap<>();
                final com.fs.starfarer.api.campaign.FleetDataAPI liveFleetData =
                        (com.fs.starfarer.api.campaign.FleetDataAPI) Proxy.newProxyInstance(
                                StarsectorUISimulator.class.getClassLoader(),
                                new Class<?>[]{com.fs.starfarer.api.campaign.FleetDataAPI.class},
                                (p, m, a) -> "getMembersListCopy".equals(m.getName()) ? new ArrayList<>(liveMembers) : null);
                final com.fs.starfarer.api.campaign.CampaignFleetAPI livePlayerFleet =
                        (com.fs.starfarer.api.campaign.CampaignFleetAPI) Proxy.newProxyInstance(
                                StarsectorUISimulator.class.getClassLoader(),
                                new Class<?>[]{com.fs.starfarer.api.campaign.CampaignFleetAPI.class},
                                (p, m, a) -> {
                                    if ("getFleetData".equals(m.getName())) return liveFleetData;
                                    if ("isPlayerFleet".equals(m.getName())) return Boolean.TRUE;
                                    if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                                    return null;
                                });
                final com.fs.starfarer.api.campaign.SectorAPI liveSector =
                        (com.fs.starfarer.api.campaign.SectorAPI) Proxy.newProxyInstance(
                                StarsectorUISimulator.class.getClassLoader(),
                                new Class<?>[]{com.fs.starfarer.api.campaign.SectorAPI.class},
                                (p, m, a) -> {
                                    if ("getPlayerFleet".equals(m.getName())) return livePlayerFleet;
                                    if ("getPersistentData".equals(m.getName())) return livePdata;
                                    if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                                    return null;
                                });
                try {
                    com.fs.starfarer.api.Global.setSector(liveSector);
                    divisionai.PermanentDivisionManager.FleetDoctrineConfig liveDoc =
                            divisionai.PermanentDivisionManager.getFleetDoctrine();
                    liveDoc.setMax(divisionai.PermanentDivisionManager.DoctrineHullCategory.CRUISER, 4);
                    divisionai.PermanentDivisionManager.saveFleetDoctrine(liveDoc);

                    // Add interleaved 3 Eagles and 3 Dominators -> should form 1 pure Eagle div (3) and 1 pure Dominator div (3)
                    for (int i = 0; i < 3; i++) {
                        liveMembers.add(createCustomMockMemberWithHullId(
                                "live_eagle_" + i, "eagle", "eagle", "Eagle",
                                com.fs.starfarer.api.combat.ShipAPI.HullSize.CRUISER, 20f, i == 0));
                        liveMembers.add(createCustomMockMemberWithHullId(
                                "live_dom_" + i, "dominator", "dominator", "Dominator",
                                com.fs.starfarer.api.combat.ShipAPI.HullSize.CRUISER, 25f, i == 0));
                    }
                    divisionai.PermanentDivisionManager.autoAssignUnassignedMembers();
                    List<divisionai.PermanentDivisionManager.PermanentDivisionSpec> firstPassDivs =
                            divisionai.PermanentDivisionManager.getNonEmptyPermanentDivisions();
                    if (firstPassDivs.size() != 2) {
                        System.out.println("FAIL Live autoAssignUnassignedMembers expected 2 pure cruiser divisions, got " + firstPassDivs.size());
                        return false;
                    }
                    for (divisionai.PermanentDivisionManager.PermanentDivisionSpec sp : firstPassDivs) {
                        boolean allEagle = true;
                        boolean allDom = true;
                        for (String mid : sp.memberIds) {
                            if (!mid.startsWith("live_eagle_")) allEagle = false;
                            if (!mid.startsWith("live_dom_")) allDom = false;
                        }
                        if (sp.memberIds.size() != 3 || (!allEagle && !allDom)) {
                            System.out.println("FAIL Live autoAssignUnassignedMembers mixed cruiser hulls on initial creation: " + sp.memberIds);
                            return false;
                        }
                    }

                    // Now add 1 more Dominator and 1 more Eagle and hit Auto-Assign again (Pass 2a same-hull affinity up to limit=4!)
                    liveMembers.add(createCustomMockMemberWithHullId(
                            "live_dom_3", "dominator", "dominator_xiv", "Dominator (XIV)",
                            com.fs.starfarer.api.combat.ShipAPI.HullSize.CRUISER, 25f, false));
                    liveMembers.add(createCustomMockMemberWithHullId(
                            "live_eagle_3", "eagle", "eagle_xiv", "Eagle (XIV)",
                            com.fs.starfarer.api.combat.ShipAPI.HullSize.CRUISER, 22f, false));
                    divisionai.PermanentDivisionManager.autoAssignUnassignedMembers();
                    List<divisionai.PermanentDivisionManager.PermanentDivisionSpec> secondPassDivs =
                            divisionai.PermanentDivisionManager.getNonEmptyPermanentDivisions();
                    if (secondPassDivs.size() != 2) {
                        System.out.println("FAIL Live autoAssignUnassignedMembers Pass 2a created extra division instead of topping up to 4: " + secondPassDivs.size());
                        return false;
                    }
                    for (divisionai.PermanentDivisionManager.PermanentDivisionSpec sp : secondPassDivs) {
                        boolean allEagle = true;
                        boolean allDom = true;
                        for (String mid : sp.memberIds) {
                            if (!mid.startsWith("live_eagle_")) allEagle = false;
                            if (!mid.startsWith("live_dom_")) allDom = false;
                        }
                        if (sp.memberIds.size() != 4 || (!allEagle && !allDom)) {
                            System.out.println("FAIL Live autoAssignUnassignedMembers Pass 2a did not match new Eagle/Dominator to its same-hull division: " + sp.memberIds);
                            return false;
                        }
                    }

                    // Comprehensive end-to-end check of "Combat Freighters as Warships" switch:
                    // 1) Vanilla civgrade warship conversions (Buffalo Mk.II, Venture, Prometheus Mk.II, Atlas Mk.II)
                    //    vs pure civilians (Buffalo, Shepherd, Kite)
                    com.fs.starfarer.api.fleet.FleetMemberAPI buffaloMk2 = createCustomMockMemberWithCivgrade(
                            "cf_buf2", "Buffalo Mk.II", com.fs.starfarer.api.combat.ShipAPI.HullSize.DESTROYER,
                            true, "Destroyer",
                            java.util.EnumSet.noneOf(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.class),
                            java.util.Collections.singletonList("civgrade"));
                    com.fs.starfarer.api.fleet.FleetMemberAPI ventureCr = createCustomMockMemberWithCivgrade(
                            "cf_venture", "Venture", com.fs.starfarer.api.combat.ShipAPI.HullSize.CRUISER,
                            true, "Cruiser",
                            java.util.EnumSet.noneOf(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.class),
                            java.util.Arrays.asList("civgrade", "surveying_equipment"));
                    com.fs.starfarer.api.fleet.FleetMemberAPI promMk2 = createCustomMockMemberWithCivgrade(
                            "cf_prom2", "Prometheus Mk.II", com.fs.starfarer.api.combat.ShipAPI.HullSize.CAPITAL_SHIP,
                            true, "Modified Tanker",
                            java.util.EnumSet.of(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.ALWAYS_PANIC),
                            java.util.Collections.singletonList("civgrade"));
                    com.fs.starfarer.api.fleet.FleetMemberAPI atlasMk2 = createCustomMockMemberWithCivgrade(
                            "cf_atlas2", "Atlas Mk.II", com.fs.starfarer.api.combat.ShipAPI.HullSize.CAPITAL_SHIP,
                            true, "Modified Superfreighter",
                            java.util.EnumSet.noneOf(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.class),
                            java.util.Collections.singletonList("civgrade"));
                    com.fs.starfarer.api.fleet.FleetMemberAPI pureBuffalo = createCustomMockMemberWithCivgrade(
                            "civ_buffalo", "Buffalo", com.fs.starfarer.api.combat.ShipAPI.HullSize.DESTROYER,
                            true, "Freighter",
                            java.util.EnumSet.of(
                                    com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.CIVILIAN,
                                    com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.FREIGHTER),
                            java.util.Collections.singletonList("civgrade"));
                    com.fs.starfarer.api.fleet.FleetMemberAPI pureShepherd = createCustomMockMemberWithCivgrade(
                            "civ_shepherd", "Shepherd", com.fs.starfarer.api.combat.ShipAPI.HullSize.FRIGATE,
                            true, "Drone Tender",
                            java.util.EnumSet.of(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.CARRIER),
                            java.util.Arrays.asList("civgrade", "surveying_equipment", "repair_gantry"));
                    com.fs.starfarer.api.fleet.FleetMemberAPI pureKite = createCustomMockMemberWithCivgrade(
                            "civ_kite", "Kite", com.fs.starfarer.api.combat.ShipAPI.HullSize.FRIGATE,
                            true, "Shuttle",
                            java.util.EnumSet.noneOf(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.class),
                            java.util.Collections.singletonList("civgrade"));

                    if (!divisionai.ShipCapabilityProfile.isCombatFreighterHull(buffaloMk2.getHullSpec())
                            || !divisionai.ShipCapabilityProfile.isCombatFreighterHull(ventureCr.getHullSpec())
                            || !divisionai.ShipCapabilityProfile.isCombatFreighterHull(promMk2.getHullSpec())
                            || !divisionai.ShipCapabilityProfile.isCombatFreighterHull(atlasMk2.getHullSpec())
                            || divisionai.ShipCapabilityProfile.isCombatFreighterHull(pureBuffalo.getHullSpec())
                            || divisionai.ShipCapabilityProfile.isCombatFreighterHull(pureShepherd.getHullSpec())
                            || divisionai.ShipCapabilityProfile.isCombatFreighterHull(pureKite.getHullSpec())) {
                        System.out.println("FAIL isCombatFreighterHull misclassified civgrade conversion or pure civilian hull");
                        return false;
                    }

                    // 2) Live campaign fleet round-trip: ON -> Reset & Auto-Form -> leave 1 Lasher unassigned ->
                    //    toggle OFF (moves Atlas Mk.II, Mule x2, Buffalo Mk.II into Service Group, keeps Eagles & unassigned Lasher untouched) ->
                    //    toggle ON (returns Atlas Mk.II, Mule x2, Buffalo Mk.II to warship divisions; pure Buffalo stays in Service Group; Atlas Mk.II sorts above Cruiser!).
                    liveMembers.clear();
                    livePdata.clear();
                    com.fs.starfarer.api.fleet.FleetMemberAPI cfMule0 = createCustomMockMemberWithCivgrade(
                            "cf_mule_0", "Mule", com.fs.starfarer.api.combat.ShipAPI.HullSize.DESTROYER,
                            false, "Combat Freighter",
                            java.util.EnumSet.noneOf(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.class),
                            java.util.Collections.emptyList());
                    com.fs.starfarer.api.fleet.FleetMemberAPI cfMule1 = createCustomMockMemberWithCivgrade(
                            "cf_mule_1", "Mule", com.fs.starfarer.api.combat.ShipAPI.HullSize.DESTROYER,
                            false, "Combat Freighter",
                            java.util.EnumSet.noneOf(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.class),
                            java.util.Collections.emptyList());
                    liveMembers.add(atlasMk2);
                    liveMembers.add(createCustomMockMemberWithHullId(
                            "cf_eagle_0", "eagle", "eagle", "Eagle",
                            com.fs.starfarer.api.combat.ShipAPI.HullSize.CRUISER, 22f, true));
                    liveMembers.add(createCustomMockMemberWithHullId(
                            "cf_eagle_1", "eagle", "eagle", "Eagle",
                            com.fs.starfarer.api.combat.ShipAPI.HullSize.CRUISER, 22f, false));
                    liveMembers.add(cfMule0);
                    liveMembers.add(cfMule1);
                    liveMembers.add(buffaloMk2);
                    liveMembers.add(pureBuffalo);
                    divisionai.PermanentDivisionManager.resetAndAutoFormFromPlayerFleet();

                    // Add an intentionally unassigned Lasher after initial auto-form
                    com.fs.starfarer.api.fleet.FleetMemberAPI unassignedLasher = createCustomMockMemberWithHullId(
                            "cf_unassigned_lasher", "lasher", "lasher", "Lasher",
                            com.fs.starfarer.api.combat.ShipAPI.HullSize.FRIGATE, 5f, false);
                    liveMembers.add(unassignedLasher);

                    List<divisionai.PermanentDivisionManager.PermanentDivisionSpec> divsOnInit =
                            divisionai.PermanentDivisionManager.getNonEmptyPermanentDivisions();
                    if (divsOnInit.isEmpty() || divsOnInit.get(0).archetype != divisionai.DivisionModel.DivisionArchetype.BATTLESHIP_DIVISION) {
                        System.out.println("FAIL Atlas Mk.II Battleship Division did not sort to top rank when As Warships is ON: "
                                + (divsOnInit.isEmpty() ? "empty" : divsOnInit.get(0).archetype));
                        return false;
                    }

                    // Toggle OFF live
                    divisionai.PermanentDivisionManager.updateCombatFreightersAsWarships(false);
                    List<divisionai.PermanentDivisionManager.PermanentDivisionSpec> divsAfterOff =
                            divisionai.PermanentDivisionManager.getNonEmptyPermanentDivisions();
                    int svcCountOff = 0;
                    boolean foundCombatFreighterInWarshipDiv = false;
                    boolean eagleDivIntact = false;
                    boolean lasherStillUnassigned = true;
                    for (divisionai.PermanentDivisionManager.PermanentDivisionSpec sp : divsAfterOff) {
                        if (sp.memberIds.contains("cf_unassigned_lasher")) lasherStillUnassigned = false;
                        if (sp.archetype == divisionai.DivisionModel.DivisionArchetype.SERVICE_GROUP) {
                            svcCountOff += sp.memberIds.size();
                        } else {
                            if (sp.memberIds.contains("cf_eagle_0") && sp.memberIds.contains("cf_eagle_1")) {
                                eagleDivIntact = true;
                            }
                            if (sp.memberIds.contains("cf_atlas2")
                                    || sp.memberIds.contains("cf_mule_0")
                                    || sp.memberIds.contains("cf_mule_1")
                                    || sp.memberIds.contains("cf_buf2")) {
                                foundCombatFreighterInWarshipDiv = true;
                            }
                        }
                    }
                    if (foundCombatFreighterInWarshipDiv || svcCountOff != 5 || !eagleDivIntact || !lasherStillUnassigned) {
                        System.out.println("FAIL Live toggle OFF reconciliation failed: cfInWarship="
                                + foundCombatFreighterInWarshipDiv + " svcCount=" + svcCountOff
                                + " eagleIntact=" + eagleDivIntact + " lasherUnassigned=" + lasherStillUnassigned);
                        return false;
                    }

                    // Toggle ON live -> combat freighters leave Service Group back into warship divisions; pure Buffalo stays in Service Group; Lasher stays unassigned!
                    divisionai.PermanentDivisionManager.updateCombatFreightersAsWarships(true);
                    List<divisionai.PermanentDivisionManager.PermanentDivisionSpec> divsAfterOn =
                            divisionai.PermanentDivisionManager.getNonEmptyPermanentDivisions();
                    int svcCountOn = 0;
                    int cfInWarshipOn = 0;
                    for (divisionai.PermanentDivisionManager.PermanentDivisionSpec sp : divsAfterOn) {
                        if (sp.memberIds.contains("cf_unassigned_lasher")) lasherStillUnassigned = false;
                        if (sp.archetype == divisionai.DivisionModel.DivisionArchetype.SERVICE_GROUP) {
                            svcCountOn += sp.memberIds.size();
                        } else {
                            for (String mid : sp.memberIds) {
                                if (mid.equals("cf_atlas2") || mid.equals("cf_mule_0")
                                        || mid.equals("cf_mule_1") || mid.equals("cf_buf2")) {
                                    cfInWarshipOn++;
                                }
                            }
                        }
                    }
                    if (svcCountOn != 1 || cfInWarshipOn != 4 || !lasherStillUnassigned
                            || divsAfterOn.get(0).archetype != divisionai.DivisionModel.DivisionArchetype.BATTLESHIP_DIVISION) {
                        System.out.println("FAIL Live toggle ON reconciliation failed: svcCountOn=" + svcCountOn
                                + " cfInWarshipOn=" + cfInWarshipOn + " lasherUnassigned=" + lasherStillUnassigned
                                + " topArch=" + (divsAfterOn.isEmpty() ? "empty" : divsAfterOn.get(0).archetype));
                        return false;
                    }
                } finally {
                    com.fs.starfarer.api.Global.setSector(prevSec);
                }
            }

            // Test resetFleetIntro + TOUR_OPEN_CODEX action
            divisionai.DivisionAISharedData.resetFleetIntro();
            if (divisionai.DivisionAISharedData.isFleetIntroCompleted()) {
                System.out.println("FAIL resetFleetIntro did not clear completion flag");
                return false;
            }
            divisionai.DivisionOnboardingOverlay codexTour =
                    new divisionai.DivisionOnboardingOverlay(coreUI, btnBlock, docBtn, orgBtn);
            codexTour.show();
            divisionai.DivisionCodexSetup.lastOpenedCodexEntryForTests = null;
            codexTour.buttonPressed("TOUR_OPEN_CODEX");
            codexTour.advance(0.1f);
            if (!codexTour.isClosed()
                    || !divisionai.DivisionAISharedData.isFleetIntroCompleted()
                    || !divisionai.DivisionCodexSetup.ENTRY_OVERVIEW.equals(
                            divisionai.DivisionCodexSetup.lastOpenedCodexEntryForTests)) {
                System.out.println("FAIL TOUR_OPEN_CODEX did not close tour, persist completion, and open ENTRY_OVERVIEW");
                return false;
            }

            return true;
        } finally {
            simulatedScreenWidth = 1920f;
            simulatedScreenHeight = 1080f;
            divisionai.DivisionAISharedData.testFleetIntroCompletedOverride = prevIntroOverride;
            com.fs.starfarer.api.impl.codex.CodexDataV2.ROOT = prevRoot;
            com.fs.starfarer.api.impl.codex.CodexDataV2.ENTRIES.clear();
            com.fs.starfarer.api.impl.codex.CodexDataV2.ENTRIES.putAll(prevEntries);
            com.fs.starfarer.api.impl.codex.CodexDataV2.WITH_GAME_MECHANICS_CAT = prevWithMechanics;
        }
    }

    private static com.fs.starfarer.api.fleet.FleetMemberAPI createCustomMockMemberWithHullId(
            final String memberId,
            final String baseHullId,
            final String exactHullId,
            final String hullName,
            final com.fs.starfarer.api.combat.ShipAPI.HullSize hullSize,
            final float dp,
            final boolean hasOfficer) {
        final java.util.EnumSet<com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints> hints =
                java.util.EnumSet.noneOf(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.class);
        final com.fs.starfarer.api.combat.ShipHullSpecAPI spec =
                (com.fs.starfarer.api.combat.ShipHullSpecAPI) Proxy.newProxyInstance(
                        com.fs.starfarer.api.combat.ShipHullSpecAPI.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.ShipHullSpecAPI.class},
                        (p, m, a) -> {
                            String n = m.getName();
                            if ("getHullSize".equals(n)) return hullSize;
                            if ("getHullName".equals(n) || "getHullNameWithDashClass".equals(n)) return hullName;
                            if ("getBaseHullId".equals(n)) return baseHullId;
                            if ("getHullId".equals(n)) return exactHullId;
                            if ("getDesignation".equals(n)) return "Cruiser";
                            if ("getHints".equals(n)) return hints;
                            if ("getFighterBays".equals(n)) return 0;
                            if ("isPhase".equals(n)) return Boolean.FALSE;
                            if ("getAllWeaponSlotsCopy".equals(n)) return java.util.Collections.emptyList();
                            if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                            if (m.getReturnType() == int.class) return 0;
                            if (m.getReturnType() == float.class) return 0f;
                            return null;
                        });
        final com.fs.starfarer.api.characters.PersonAPI officer;
        if (hasOfficer) {
            final com.fs.starfarer.api.characters.MutableCharacterStatsAPI stats =
                    (com.fs.starfarer.api.characters.MutableCharacterStatsAPI) Proxy.newProxyInstance(
                            com.fs.starfarer.api.characters.MutableCharacterStatsAPI.class.getClassLoader(),
                            new Class<?>[]{com.fs.starfarer.api.characters.MutableCharacterStatsAPI.class},
                            (p, m, a) -> {
                                if ("getLevel".equals(m.getName())) return 5;
                                if ("getSkillsCopy".equals(m.getName())) return java.util.Collections.emptyList();
                                if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                                if (m.getReturnType() == int.class) return 0;
                                if (m.getReturnType() == float.class) return 0f;
                                return null;
                            });
            officer = (com.fs.starfarer.api.characters.PersonAPI) Proxy.newProxyInstance(
                    com.fs.starfarer.api.characters.PersonAPI.class.getClassLoader(),
                    new Class<?>[]{com.fs.starfarer.api.characters.PersonAPI.class},
                    (p, m, a) -> {
                        String n = m.getName();
                        if ("getId".equals(n)) return "off_" + memberId;
                        if ("isDefault".equals(n)) return Boolean.FALSE;
                        if ("isAICore".equals(n)) return Boolean.FALSE;
                        if ("getStats".equals(n)) return stats;
                        if ("getNameString".equals(n)) return "Capt. " + memberId;
                        if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                        if (m.getReturnType() == int.class) return 0;
                        if (m.getReturnType() == float.class) return 0f;
                        return null;
                    });
        } else {
            officer = null;
        }
        return (com.fs.starfarer.api.fleet.FleetMemberAPI) Proxy.newProxyInstance(
                com.fs.starfarer.api.fleet.FleetMemberAPI.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.fleet.FleetMemberAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getId".equals(n)) return memberId;
                    if ("getShipName".equals(n)) return "ISS " + hullName + " " + memberId;
                    if ("getHullId".equals(n)) return exactHullId;
                    if ("getHullSpec".equals(n)) return spec;
                    if ("getCaptain".equals(n)) return officer;
                    if ("isCivilian".equals(n)) return Boolean.FALSE;
                    if ("getDeploymentPointsCost".equals(n) || "getUnmodifiedDeploymentPointsCost".equals(n)) return dp;
                    if ("getFleetPointCost".equals(n)) return Math.max(4, Math.round(dp));
                    if ("getNumFlightDecks".equals(n)) return 0;
                    if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                    if (m.getReturnType() == int.class) return 0;
                    if (m.getReturnType() == float.class) return 0f;
                    return null;
                });
    }

    private static com.fs.starfarer.api.fleet.FleetMemberAPI createCustomMockMember(
            final String id,
            final String hullName,
            final com.fs.starfarer.api.combat.ShipAPI.HullSize hullSize,
            final boolean isCivilian,
            final String designation,
            final java.util.EnumSet<com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints> hints) {
        return createCustomMockMemberWithCivgrade(
                id, hullName, hullSize, isCivilian, designation, hints, java.util.Collections.emptyList());
    }

    private static com.fs.starfarer.api.fleet.FleetMemberAPI createCustomMockMemberWithCivgrade(
            final String id,
            final String hullName,
            final com.fs.starfarer.api.combat.ShipAPI.HullSize hullSize,
            final boolean isCivilian,
            final String designation,
            final java.util.EnumSet<com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints> hints,
            final java.util.List<String> builtInMods) {
        final String baseId = id.replaceAll("_[0-9]+$", "");
        final com.fs.starfarer.api.combat.ShipHullSpecAPI spec =
                (com.fs.starfarer.api.combat.ShipHullSpecAPI) Proxy.newProxyInstance(
                        com.fs.starfarer.api.combat.ShipHullSpecAPI.class.getClassLoader(),
                        new Class<?>[]{com.fs.starfarer.api.combat.ShipHullSpecAPI.class},
                        (p, m, a) -> {
                            String n = m.getName();
                            if ("getHullSize".equals(n)) return hullSize;
                            if ("getHullName".equals(n) || "getHullNameWithDashClass".equals(n)) return hullName;
                            if ("getBaseHullId".equals(n) || "getHullId".equals(n)) return baseId;
                            if ("getDesignation".equals(n)) return designation;
                            if ("getHints".equals(n)) return hints;
                            if ("getBuiltInMods".equals(n)) return builtInMods;
                            if ("getFighterBays".equals(n)) return 0;
                            if ("isPhase".equals(n)) return Boolean.FALSE;
                            if ("getAllWeaponSlotsCopy".equals(n)) return java.util.Collections.emptyList();
                            if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                            if (m.getReturnType() == int.class) return 0;
                            if (m.getReturnType() == float.class) return 0f;
                            return null;
                        });
        return (com.fs.starfarer.api.fleet.FleetMemberAPI) Proxy.newProxyInstance(
                com.fs.starfarer.api.fleet.FleetMemberAPI.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.fleet.FleetMemberAPI.class},
                (p, m, a) -> {
                    String n = m.getName();
                    if ("getId".equals(n)) return id;
                    if ("getShipName".equals(n)) return "ISS " + hullName;
                    if ("getHullId".equals(n)) return id;
                    if ("getHullSpec".equals(n)) return spec;
                    if ("isCivilian".equals(n)) return Boolean.valueOf(isCivilian);
                    if ("isFighterWing".equals(n) || "isStation".equals(n) || "isMothballed".equals(n)) return Boolean.FALSE;
                    if ("isPhaseShip".equals(n) || "isCarrier".equals(n)) return Boolean.FALSE;
                    if ("getNumFlightDecks".equals(n)) return 0;
                    if ("getDeploymentPointsCost".equals(n)) return 8f;
                    if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                    if (m.getReturnType() == int.class) return 0;
                    if (m.getReturnType() == float.class) return 0f;
                    return null;
                });
    }
}
