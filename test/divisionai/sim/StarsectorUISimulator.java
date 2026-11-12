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
import com.fs.starfarer.campaign.ui.S;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.CombatState;
import com.fs.starfarer.coreui.v;
import com.fs.starfarer.loading.specs.g;
import com.fs.starfarer.settings.StarfarerSettings;
import com.fs.starfarer.ui.OO0O;
import com.fs.starfarer.ui.c;
import com.fs.starfarer.ui.d;
import com.fs.starfarer.ui.impl.o0OO;
import com.fs.starfarer.ui.impl.oO0O;
import com.fs.starfarer.ui.interfacenew;
import com.fs.starfarer.ui.n;
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
        public OO0O setSize(float w, float h) {
            return getPosition().setSize(w, h);
        }

        @Override
        public OO0O sizeToInner(float w, float h) {
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

    public static class SimButton extends n {
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
        public g simHullSpec;
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
                g hullSpec,
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
        @Override public g getHullSpec() { return simHullSpec; }
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

        // Wrap realSettings so getScreenWidth() / getScreenHeight() return 1920x1080 in headless mode
        SettingsAPI wrappedSettings = (SettingsAPI) Proxy.newProxyInstance(
                StarsectorUISimulator.class.getClassLoader(),
                new Class<?>[]{SettingsAPI.class},
                (proxy, method, args) -> {
                    String mn = method.getName();
                    if ("getScreenWidth".equals(mn) || "getScreenWidthPixels".equals(mn)) return 1920f;
                    if ("getScreenHeight".equals(mn) || "getScreenHeightPixels".equals(mn)) return 1080f;
                    if ("getUiScale".equals(mn)) return 1.0f;
                    return method.invoke(realSettings, args);
                });
        Global.setSettings(wrappedSettings);

        // Configure VFS (com.fs.util.C) to resolve files from starsector-core and mod/
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
        Class<?> texCls = Class.forName("com.fs.graphics.Object");
        Constructor<?> texCtor = texCls.getDeclaredConstructor(int.class, int.class, String.class);
        texCtor.setAccessible(true);
        Method setImgW = null;
        Method setImgH = null;
        Method setTexW = null;
        Method setTexH = null;
        for (Method m : texCls.getDeclaredMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class && m.getReturnType() == void.class) {
                m.setAccessible(true);
                String mn = m.getName();
                if ("o00000".equals(mn)) setImgH = m;
                else if ("Object".equals(mn)) setTexW = m;
            }
        }

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
                    public void o00000(String id, String path) {
                        // Headless no-op: lazyMap populates texture objects with real PNG dimensions
                    }
                });
            }
        }

        // Install lazy real .fnt bitmap font loader into com.fs.graphics.A.D
        Class<?> fontMapCls = Class.forName("com.fs.graphics.A.D");
        Method loadFont = fontMapCls.getDeclaredMethod("super", String.class, String.class);
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
                                try {
                                    String fallback = "graphics/fonts/insignia15LTaa.fnt";
                                    loadFont.invoke(null, k, fallback);
                                    v = super.get(k);
                                } catch (Exception ex) {
                                    throw new RuntimeException(ex);
                                }
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

    private static S buildRealShipGrid(oO0O fdd, float width, float height, int cols, List<FleetMember> members) throws Exception {
        S grid = new S(cols, 0f, 58f, D.o.values()[0], fdd);
        grid.setSize(width, height);
        List<FleetMember> gridMembers = grid.getMembers();
        v table = grid.getList();

        Class<?> iconCls = Class.forName("com.fs.starfarer.ui.B.new");
        Object fontObj = null;
        Constructor<?> textRendererCtor = null;
        try {
            Class<?> fontMapCls = Class.forName("com.fs.graphics.A.D");
            for (Method m : fontMapCls.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == String.class
                        && m.getReturnType().getName().equals("com.fs.graphics.A.F")) {
                    m.setAccessible(true);
                    fontObj = m.invoke(null, "graphics/fonts/victor14.fnt");
                    break;
                }
            }
            for (Field f : iconCls.getDeclaredFields()) {
                if ("float.Object".equals(f.getName())) {
                    textRendererCtor = f.getType().getDeclaredConstructor(Class.forName("com.fs.graphics.A.F"));
                    textRendererCtor.setAccessible(true);
                    break;
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

            // 2. Build 16 real SimFleetMembers & Campaign Sector persistent data for 1:1 UI simulation
            g hullSpec = allocateWithoutConstructor(g.class);
            for (Field f : g.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("\u00d800000") && f.getType() == String.class) {
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
                    && warroomBattleSuiteOk;

            res.record(
                    allSimOk,
                    "1:1 Starsector Obfuscated UI Simulation (Deployment formation selector, campaign dialogs & 30 formation sprites)",
                    String.format(
                            Locale.ROOT,
                            "divisionSprites=%d, fleetSprites=%d(%b), deployStd=%b, deployAlpha=%b, deployMidBattle=%b, deployPursuit=%b, doctrineDialog=%b, editorDialog=%b, campaignTooltip1To1=%b, warroomBattleSuite1To1=%b",
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
                            warroomBattleSuiteOk));
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
            g hullSpec,
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

        // Construct real Starsector oO0O (FleetDeploymentDialog)
        Constructor<interfacenew> panelCtor = interfacenew.class.getDeclaredConstructor();
        Constructor<SimFdd> fddCtor = (Constructor<SimFdd>) ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(SimFdd.class, panelCtor);
        SimFdd fdd = fddCtor.newInstance();
        fdd.selectedMembers = new ArrayList<>();
        interfacenew innerPanel = new interfacenew();
        fdd.setSize(624f, 460f);
        innerPanel.setSize(604f, 440f);
        fdd.add(innerPanel).inTL(10f, 10f);

        for (Field f : oO0O.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getName().equals("\u00f6\u00f80000")) f.setInt(fdd, 0);
            else if (f.getName().equals("o\u00f60000")) f.setBoolean(fdd, false);
            else if (f.getName().equals("\u00f8O0000")) f.set(fdd, fdd.selectedMembers);
        }
        for (Field f : o0OO.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getType() == interfacenew.class) f.set(fdd, innerPanel);
            else if (f.getType() == Map.class) f.set(fdd, new HashMap<>());
        }

        S deployedGrid = buildRealShipGrid(fdd, 592f, 174f, 10, deployedMembers);
        S reservesGrid = buildRealShipGrid(fdd, 592f, 174f, 10, reserveMembers);
        interfacenew sep1 = new interfacenew();
        sep1.setSize(592f, 7f);
        interfacenew sep2 = new interfacenew();
        sep2.setSize(542f, 1f);
        LabelAPI crNote = Global.getSettings().createLabel("Combat readiness note", Fonts.DEFAULT_SMALL);
        ((UIComponentAPI) crNote).getPosition().setSize(250f, 22f);

        innerPanel.add(deployedGrid).inTL(10f, 10f);
        innerPanel.add(sep1).belowLeft(deployedGrid, 10f);
        innerPanel.add(reservesGrid).belowLeft(sep1, 10f);
        innerPanel.add(sep2).belowLeft(reservesGrid, 15f);
        innerPanel.add((c) crNote).belowLeft(sep2, 6f);

        interfacenew widgetPanel = new interfacenew();
        widgetPanel.setSize(1920f, 1080f);
        widgetPanel.add(fdd).inMid();

        CombatState cs = allocateWithoutConstructor(CombatState.class);
        for (Field f : CombatState.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getName().equals("widgetPanel")) {
                f.set(cs, widgetPanel);
            } else if (f.getType().getName().equals("com.fs.starfarer.combat.CombatState$o")) {
                Class<?> enumCls = f.getType();
                Field floatField = enumCls.getDeclaredField("float");
                floatField.setAccessible(true);
                f.set(cs, floatField.get(null));
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
        if (preBattle && fcButtons.size() != 6) {
            System.out.println("FAIL command style + fleet formation + OTC change buttons: " + fcButtons.size());
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
        }
        if (expectAlphaCore && (alphaMasterLabel == null || alphaShipLineLabel == null
                || alphaShipLineLabel.getPosition().getY() >= alphaMasterLabel.getPosition().getY() - 10f)) {
            System.out.println("FAIL Alpha Core OTC is not typeset as a separate two-line title");
            return false;
        }

        float fcX = fcPos.getX();
        float fcW = fcPos.getWidth();
        float divX = fcX + Math.round(fcW * 0.46f);
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
        ButtonAPI nextFormationButton = null;
        ButtonAPI prevFormationButton = null;
        ButtonAPI nextAuthButton = null;
        ButtonAPI prevAuthButton = null;
        ButtonAPI orchestratedAdvanceButton = null;
        float cmdMidX = fcPos.getX() + Math.round(fcPos.getWidth() * 0.50f) + 8f + 195f;
        for (ButtonAPI btn : fcButtons) {
            PositionAPI bp = btn.getPosition();
            boolean isAuthCol = bp != null && bp.getX() < cmdMidX;
            if (">".equals(btn.getText())) {
                if (isAuthCol) nextAuthButton = btn;
                else nextFormationButton = btn;
            }
            if ("<".equals(btn.getText())) {
                if (isAuthCol) prevAuthButton = btn;
                else prevFormationButton = btn;
            }
            if (btn.getText() != null && btn.getText().startsWith("Orchestrated Advance:")) {
                orchestratedAdvanceButton = btn;
            }
            if (btn.getText() != null && btn.getText().startsWith("Formation entry:")) {
                System.out.println("FAIL Feature B: 'Formation entry' button should be removed!");
                return false;
            }
        }
        if (nextFormationButton == null || prevFormationButton == null
                || nextAuthButton == null || prevAuthButton == null || orchestratedAdvanceButton == null) {
            System.out.println("FAIL missing command style / fleet formation arrow / Orchestrated Advance buttons");
            return false;
        }
        PermanentDivisionManager.OtcAuthority initialAuth =
                PermanentDivisionManager.getFleetDoctrine().getOtcAuthority();
        nextAuthButton.setChecked(true);
        fcPanel.advance(0.1f);
        PermanentDivisionManager.OtcAuthority[] authValues = PermanentDivisionManager.OtcAuthority.values();
        PermanentDivisionManager.OtcAuthority expectedAuth =
                authValues[(initialAuth.ordinal() + 1) % authValues.length];
        if (PermanentDivisionManager.getFleetDoctrine().getOtcAuthority() != expectedAuth
                || playerGroup.authority != expectedAuth) {
            System.out.println("FAIL pre-battle next command style button did not update doctrine/group authority: got "
                    + PermanentDivisionManager.getFleetDoctrine().getOtcAuthority() + " expected " + expectedAuth);
            return false;
        }
        prevAuthButton.setChecked(true);
        fcPanel.advance(0.1f);
        if (PermanentDivisionManager.getFleetDoctrine().getOtcAuthority() != initialAuth
                || playerGroup.authority != initialAuth) {
            System.out.println("FAIL pre-battle prev command style button did not restore initial authority");
            return false;
        }
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
        if (PermanentDivisionManager.getFleetDoctrine().getFleetWideFormation() != expectedFw
                || playerGroup.fleetWideFormation != expectedFw
                || playerGroup.activeFleetWideFormation != expectedFw
                || !formationLabelUpdated) {
            System.out.println("FAIL next fleet formation button did not update doctrine/group/name on single click: initial=" + initialFw
                    + " expected=" + expectedFw
                    + " doctrine=" + PermanentDivisionManager.getFleetDoctrine().getFleetWideFormation()
                    + " group=" + playerGroup.fleetWideFormation
                    + " active=" + playerGroup.activeFleetWideFormation
                    + " labelUpdated=" + formationLabelUpdated);
            return false;
        }
        prevFormationButton.setChecked(true);
        fcPanel.advance(0.1f);
        if (PermanentDivisionManager.getFleetDoctrine().getFleetWideFormation() != initialFw
                || playerGroup.fleetWideFormation != initialFw) {
            System.out.println("FAIL previous fleet formation button did not restore initial formation");
            return false;
        }
        boolean orchBefore = PermanentDivisionManager.getFleetDoctrine().isOrchestratedAdvance();
        orchestratedAdvanceButton.setChecked(true);
        fcPanel.advance(0.1f);
        if (PermanentDivisionManager.getFleetDoctrine().isOrchestratedAdvance() == orchBefore
                || playerGroup.orchestratedAdvance == orchBefore
                || !PermanentDivisionManager.getFleetDoctrine().isDeployInFleetFormation()) {
            System.out.println("FAIL Orchestrated Advance toggle did not update doctrine/group or disabled formation entry");
            return false;
        }
        // Restore Orchestrated Advance to On
        orchestratedAdvanceButton.setChecked(true);
        fcPanel.advance(0.1f);
        }

        // 3. Audit every division row header card in reservesGrid
        v rList = reservesGrid.getList();
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
    private static boolean simulatePursuitDeploymentWindow1To1(g hullSpec, final PersonAPI flagshipCaptain) throws Throwable {
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

        Constructor<interfacenew> panelCtor = interfacenew.class.getDeclaredConstructor();
        Constructor<SimFdd> fddCtor = (Constructor<SimFdd>) ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(SimFdd.class, panelCtor);
        SimFdd fdd = fddCtor.newInstance();
        fdd.selectedMembers = new ArrayList<>();

        interfacenew innerPanel = new interfacenew();
        fdd.setSize(624f, 460f);
        innerPanel.setSize(604f, 440f);
        fdd.add(innerPanel).inTL(10f, 10f);

        S deployedGrid = buildRealShipGrid(fdd, 592f, 174f, 10, new ArrayList<FleetMember>());
        S reservesGrid = buildRealShipGrid(fdd, 592f, 174f, 10, reserveMembers);
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
            if (f.getName().equals("\u00f6\u00f80000")) f.setInt(fdd, 0);
            else if (f.getName().equals("o\u00f60000")) f.setBoolean(fdd, false);
            else if (f.getName().equals("\u00f8O0000")) f.set(fdd, fdd.selectedMembers);
            else if (f.getType() == CombatEngine.class) f.set(fdd, simEngine);
            else if (f.getType() == com.fs.starfarer.combat.CombatFleetManager.class) f.set(fdd, simPlayerFm);
            else if (f.getType() == com.fs.starfarer.campaign.ui.N.class) f.set(fdd, simBar);
            else if (f.getType() == n.class) f.set(fdd, dummyBtn);
            else if (f.getType() == S.class) {
                if ("void.null$Object".equals(f.getName())) f.set(fdd, deployedGrid);
                else f.set(fdd, reservesGrid);
            }
        }
        for (Field f : o0OO.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getType() == interfacenew.class) f.set(fdd, innerPanel);
            else if (f.getType() == Map.class) f.set(fdd, new HashMap<>());
        }
        interfacenew sep1 = new interfacenew();
        sep1.setSize(592f, 7f);
        interfacenew sep2 = new interfacenew();
        sep2.setSize(542f, 1f);
        LabelAPI crNote = Global.getSettings().createLabel("Combat readiness note", Fonts.DEFAULT_SMALL);
        ((UIComponentAPI) crNote).getPosition().setSize(250f, 22f);

        innerPanel.add(deployedGrid).inTL(10f, 10f);
        innerPanel.add(sep1).belowLeft(deployedGrid, 10f);
        innerPanel.add(reservesGrid).belowLeft(sep1, 10f);
        innerPanel.add(sep2).belowLeft(reservesGrid, 15f);
        innerPanel.add((c) crNote).belowLeft(sep2, 6f);

        interfacenew widgetPanel = new interfacenew();
        widgetPanel.setSize(1920f, 1080f);
        widgetPanel.add(fdd).inMid();

        CombatState cs = allocateWithoutConstructor(CombatState.class);
        for (Field f : CombatState.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getName().equals("widgetPanel")) {
                f.set(cs, widgetPanel);
            } else if (f.getType().getName().equals("com.fs.starfarer.combat.CombatState$o")) {
                Class<?> enumCls = f.getType();
                Field floatField = enumCls.getDeclaredField("float");
                floatField.setAccessible(true);
                f.set(cs, floatField.get(null));
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

        v rList = reservesGrid.getList();
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

        Method iconGetStateM = Class.forName("com.fs.starfarer.ui.B.new").getDeclaredMethod("\u00d5O0000");
        iconGetStateM.setAccessible(true);

        // 1. Div 3 (Frigates): Click [L] -> state 2; Click [R] -> state 3; Click [R] again -> state -1 (clean deselect!)
        p2.buttonPressed("DEPLOY_LEFT_ROW");
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
        interfacenew hostCoreUI = new interfacenew();
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
        interfacenew hostCoreUI = new interfacenew();
        hostCoreUI.setSize(1920f, 1080f);
        DivisionEditorDialog dialog = new DivisionEditorDialog(hostCoreUI);
        dialog.show();

        for (String cmd : new String[]{
                null,
                "SET_OTC_AUTH:POSITIVE_CONTROL",
                "SET_OTC_AUTH:COMMAND_BY_DIRECTION",
                "OPEN_DIV:div_1",
                "TOGGLE_DD:DIV_ARCH_DD",
                "TOGGLE_DD:DIV_OPEN_MIS_DD",
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
                        && lp.getY() < (1080f - Math.max(20f, (1080f - DivisionEditorDialog.DIALOG_HEIGHT) * 0.5f) - 108f)
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
                        && bp.getY() < (1080f - Math.max(20f, (1080f - DivisionEditorDialog.DIALOG_HEIGHT) * 0.5f) - 108f)
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
        if (!(rootPos instanceof OO0O)) return false;
        Field childrenField = OO0O.class.getDeclaredField("\u00d400000");
        childrenField.setAccessible(true);
        Set<OO0O> visited = Collections.newSetFromMap(new IdentityHashMap<OO0O, Boolean>());
        return verifyOO0ONodeRecursive((OO0O) rootPos, childrenField, visited);
    }

    @SuppressWarnings("unchecked")
    private static boolean verifyOO0ONodeRecursive(OO0O node, Field childrenField, Set<OO0O> visited) throws Throwable {
        if (node == null || !visited.add(node)) return true;
        node.setSuspendRecompute(false);
        node.recompute();
        List<OO0O> children = (List<OO0O>) childrenField.get(node);
        if (children == null || children.isEmpty()) return true;
        Set<OO0O> siblingSet = Collections.newSetFromMap(new IdentityHashMap<OO0O, Boolean>());
        for (OO0O ch : children) {
            if (ch != null) siblingSet.add(ch);
        }
        for (OO0O ch : new ArrayList<OO0O>(children)) {
            if (ch == null) continue;
            OO0O base = ch.getBase();
            if (base != null && !siblingSet.contains(base)) {
                System.out.println("FAIL dangling OO0O sibling anchor detected: child=" + ch + " base=" + base);
                return false;
            }
            if (!verifyOO0ONodeRecursive(ch, childrenField, visited)) {
                return false;
            }
        }
        return true;
    }

    private static com.fs.starfarer.ui.f extractAttachedTooltip(Object comp) {
        if (comp instanceof com.fs.starfarer.ui.OOOo) {
            return ((com.fs.starfarer.ui.OOOo) comp).getTooltip();
        }
        if (comp != null) {
            for (Class<?> cl = comp.getClass(); cl != null && cl != Object.class; cl = cl.getSuperclass()) {
                for (Field f : cl.getDeclaredFields()) {
                    if (f.getType() == com.fs.starfarer.ui.f.class) {
                        try {
                            f.setAccessible(true);
                            return (com.fs.starfarer.ui.f) f.get(comp);
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
            com.fs.starfarer.ui.f tt = extractAttachedTooltip(comp);
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

    private static boolean simulateCampaignFleetTooltipOrganizer1To1(g hullSpec, final PersonAPI flagshipCaptain) throws Throwable {
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
        interfacenew contents1 = (interfacenew) tooltip1.getContents();
        S sGrid1 = buildRealShipGrid(null, 410f, 180f, 7, members);
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
        interfacenew contents2 = (interfacenew) tooltip2.getContents();
        S sGrid2 = buildRealShipGrid(null, 410f, 180f, 7, members);
        contents2.add(sGrid2).inTL(5f, 24f);
        v vTable2 = sGrid2.getList();
        c firstShipCell = vTable2.getItems().get(0);
        interfacenew contentContainer2 = vTable2.getScroller().getContentContainer();
        interfacenew foreignOverlay = new interfacenew(18f, 18f);
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
        interfacenew contents3 = (interfacenew) tooltip3.getContents();
        List<FleetMember> dupMembers = new ArrayList<>(members);
        dupMembers.add(members.get(0));
        dupMembers.add(members.get(2));
        S sGrid3 = buildRealShipGrid(null, 410f, 180f, 7, dupMembers);
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
        interfacenew orphanTarget = new interfacenew(10f, 10f);
        interfacenew anchoredChild = new interfacenew(10f, 10f);
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
        Class<?> combatEngineCls = Class.forName("com.fs.starfarer.combat.CombatEngine");
        Class<?> combatStateCls = Class.forName("com.fs.starfarer.combat.CombatState");
        Class<?> wrCls = Class.forName("com.fs.starfarer.combat.new.OoOO");
        Class<?> mdCls = Class.forName("com.fs.starfarer.combat.new.b");
        Class<?> shipIconCls = Class.forName("com.fs.starfarer.combat.new.A");
        Class<?> fighterIconCls = Class.forName("com.fs.starfarer.combat.new.o0Oo");
        Class<?> bNewCls = Class.forName("com.fs.starfarer.ui.B.new");
        Class<?> spriteCls = Class.forName("com.fs.graphics.Sprite");
        Class<?> texObjCls = Class.forName("com.fs.graphics.Object");

        Object realEngine = allocateWithoutConstructor(combatEngineCls);
        Object combatState = allocateWithoutConstructor(combatStateCls);
        Constructor<interfacenew> panelCtor = interfacenew.class.getDeclaredConstructor();
        Constructor<?> wrCtor = ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(wrCls, panelCtor);
        Object warroom = wrCtor.newInstance();
        ((interfacenew) warroom).setSize(1920f, 1080f);
        Object mapDisplay = allocateWithoutConstructor(mdCls);

        // Wire CombatEngine -> CombatState -> OoOO (Warroom) -> b (MapDisplay)
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
        Field mdMapField = mdCls.getDeclaredField("private.new");
        mdMapField.setAccessible(true);
        mdMapField.set(mapDisplay, liveIconMap);
        Field mdSetField = mdCls.getDeclaredField("\u00d2o0000");
        mdSetField.setAccessible(true);
        mdSetField.set(mapDisplay, deadIconSet);

        Field aBNewField = shipIconCls.getDeclaredField("O\u00d30000");
        aBNewField.setAccessible(true);
        Field aArrowField = shipIconCls.getDeclaredField("interface.new");
        aArrowField.setAccessible(true);
        Field fighterSpriteField = fighterIconCls.getDeclaredField("float.super");
        fighterSpriteField.setAccessible(true);
        Field bNewSpriteField = bNewCls.getDeclaredField("\u00d4\u00d50000");
        bNewSpriteField.setAccessible(true);
        Field bNewHoloField = bNewCls.getDeclaredField("\u00d8\u00f40000");
        bNewHoloField.setAccessible(true);
        Field bNewModulesField = bNewCls.getDeclaredField("\u00d4\u00f40000");
        bNewModulesField.setAccessible(true);
        Field bNewStatusField = bNewCls.getDeclaredField("\u00d3\u00d60000");
        bNewStatusField.setAccessible(true);
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
}
