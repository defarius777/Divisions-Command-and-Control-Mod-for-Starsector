package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import divisionai.DivisionModel.BattleGroupState;
import divisionai.DivisionModel.DivisionState;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Enlarges Starsector's pre-battle Deployment Window (com.fs.starfarer.ui.impl.oO0O) from 10x3
 * to 14 columns x 7 visible reserve rows (12 ship slots per division row + 2 header columns),
 * and organizes reserve ships into clean rows by the player's non-empty Permanent Divisions.
 *
 * Uses java.lang.invoke.MethodHandles exclusively (zero java.lang.reflect.* references).
 */
public class DivisionDeploymentDialogOrganizer {

    private static final Logger LOG = Global.getLogger(DivisionDeploymentDialogOrganizer.class);

    public static final int ENLARGED_TOTAL_COLS = 16;
    public static final int LABEL_COLS = 2;
    public static final int SHIPS_PER_ROW = ENLARGED_TOTAL_COLS - LABEL_COLS; // 14 ships per row
    public static final int TARGET_RESERVE_VISIBLE_ROWS = 7;
    public static final float ENLARGED_FLEET_COMMAND_HEIGHT = 204f;
    public static final float ALPHA_CORE_PORTRAIT_STRETCH_ASPECT = 1.72f;
    public static final float MASTER_JITTER_PERIOD_SECONDS = 0.50f;
    private static final String ALPHA_CORE_PORTRAIT_SPRITE = "graphics/portraits/portrait_ai2b.png";

    private static final Color DIV_HEADER_TEXT_COLOR = new Color(130, 255, 165, 255);
    private static final Color DIV_ACCENT_COLOR = new Color(75, 225, 125, 255);
    private static final Color DIV_BTN_BASE_COLOR = new Color(75, 215, 120, 255);
    private static final Color DIV_BTN_DARK_COLOR = new Color(18, 52, 32, 235);
    private static final Color UNASSIGNED_TEXT_COLOR = new Color(195, 205, 215, 235);
    private static final Color UNASSIGNED_ACCENT_COLOR = new Color(135, 150, 165, 255);
    private static final Color UNASSIGNED_BTN_BASE = new Color(135, 150, 165, 255);
    private static final Color UNASSIGNED_BTN_DARK = new Color(32, 38, 46, 235);
    private static final Color GOLDEN_SQUARE_COLOR = new Color(255, 215, 45, 255);
    private static final Color GOLDEN_SQUARE_SPOTLIGHT = new Color(255, 228, 95, 255);
    private static final Color DEFAULT_RESERVE_GRID_COLOR = new Color(148, 255, 178, 255);

    private static MethodHandle fontPathGetterHandle = null;
    private static boolean fontPathHandleFailed = false;

    public static String getLabelFontPath(LabelAPI lbl) {
        if (lbl == null || fontPathHandleFailed) return null;
        if (fontPathGetterHandle == null) {
            try {
                fontPathGetterHandle = MethodHandles.lookup().findVirtual(
                        lbl.getClass(), "getFont", MethodType.methodType(String.class));
            } catch (Throwable t) {
                fontPathHandleFailed = true;
                return null;
            }
        }
        try {
            Object val = fontPathGetterHandle.invoke(lbl);
            return val != null ? val.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void assertCoolTacticalFonts(LabelAPI lbl) {
        String path = getLabelFontPath(lbl);
        if (path == null) return;
        if (path.contains("insignia") || path.contains("default")) {
            throw new IllegalStateException("Deployment Screen uses comic-style font: " + path);
        }
    }

    private boolean handlesResolved = false;
    private boolean handlesFailed = false;

    private Class<?> fddClass;
    private Class<?> shipGridClass;

    private MethodHandle isShowingDeploymentDialogHandle;
    private MethodHandle getWidgetPanelHandle;
    private MethodHandle panelGetChildrenCopyHandle;
    private MethodHandle fddGetInnerPanelHandle;
    private MethodHandle fddSizeToInnerHandle;
    private MethodHandle fddGetOwnerIdHandle;
    private MethodHandle fddIsMultiDeployHandle;
    private MethodHandle fddGetSelectedHandle;
    private MethodHandle fddFleetMemberClickedHandle;

    private MethodHandle gridGetMembersHandle;
    private MethodHandle gridGetButtonForMemberHandle;
    private MethodHandle gridGetIconForMemberHandle;
    private MethodHandle gridGetListHandle;

    private MethodHandle listGetColumnsHandle;
    private MethodHandle listGetItemWidthHandle;
    private MethodHandle listGetItemHeightHandle;
    private MethodHandle listGetItemsHandle;
    private MethodHandle listClearHandle;
    private MethodHandle listAddItemHandle;
    private MethodHandle listGetScrollerHandle;

    private MethodHandle scrollerSetContentSizeHandle;
    private MethodHandle scrollerSetShowGridHandle;
    private MethodHandle scrollerPackHandle;
    private MethodHandle scrollerClampOffsetHandle;
    private MethodHandle posRecomputeHandle;
    private MethodHandle compSetOpacityHandle;
    private MethodHandle panelSetSizeHandle;
    private MethodHandle listSetColsFieldHandle;
    private MethodHandle gridSetColsFieldHandle;

    private MethodHandle iconGetO0OOHandle;
    private MethodHandle o0ooSetColorsHandle;
    private MethodHandle iconSetDeployStateHandle;
    private MethodHandle iconSetDeployTextHandle;

    public static final class IndexedMemberList extends ArrayList<FleetMemberAPI> {
        private final Map<Integer, FleetMemberAPI> slotToMember = new LinkedHashMap<>();
        private final Map<String, Integer> memberIdToSlot = new LinkedHashMap<>();

        public IndexedMemberList() {
            super();
        }

        public IndexedMemberList(java.util.Collection<? extends FleetMemberAPI> c) {
            super(c);
        }

        public void clearSlotMapping() {
            slotToMember.clear();
            memberIdToSlot.clear();
        }

        public void recordSlotMapping(int slotIndex, FleetMemberAPI member) {
            if (member != null) {
                slotToMember.put(slotIndex, member);
                if (member.getId() != null) {
                    memberIdToSlot.put(member.getId(), slotIndex);
                }
            }
        }

        public FleetMemberAPI getMemberAtSlot(int slotIndex) {
            return slotToMember.get(slotIndex);
        }

        public int getSlotForMember(FleetMemberAPI member) {
            if (member == null || member.getId() == null) return -1;
            Integer slot = memberIdToSlot.get(member.getId());
            return slot != null ? slot : -1;
        }

        public Map<Integer, FleetMemberAPI> getSlotToMemberMap() {
            return java.util.Collections.unmodifiableMap(slotToMember);
        }
    }

    private Object lastFddInstance = null;
    private Object cachedDeployedGridForActiveFdd = null;
    private Object cachedReservesGridForActiveFdd = null;
    private String lastReservesSignature = "";
    private final Map<String, MemberIconEntry> cachedEntriesForActiveFdd = new LinkedHashMap<>();
    private CustomPanelAPI activeFleetCommandPanel = null;
    private CustomPanelAPI activeOtcSelectorPanel = null;
    private UIPanelAPI standaloneEmergencyModalParent = null;
    private boolean otcSelectorWindowOpen = false;
    private Runnable activeOtcSelectorPollHook = null;
    private IndexedMemberList lastIndexedReserveMembers = new IndexedMemberList();

    public IndexedMemberList getLastIndexedReserveMembers() {
        return lastIndexedReserveMembers;
    }

    public CustomPanelAPI getActiveOtcSelectorPanel() {
        return activeOtcSelectorPanel;
    }

    public boolean isOtcSelectorWindowOpen() {
        return otcSelectorWindowOpen;
    }

    private BattleGroupState playerGroup;
    private BattleGroupState enemyGroup;
    private DynamicBattleMusicController musicController;

    public void setBattleContext(
            BattleGroupState playerGroup,
            BattleGroupState enemyGroup,
            DynamicBattleMusicController musicController) {
        this.playerGroup = playerGroup;
        this.enemyGroup = enemyGroup;
        this.musicController = musicController;
    }

    public static boolean verifyObfuscatedHandles(ClassLoader cl) {
        try {
            Class<?> combatStateClass = Class.forName("com.fs.starfarer.combat.CombatState", false, cl);
            DivisionDeploymentDialogOrganizer org = new DivisionDeploymentDialogOrganizer();
            org.ensureHandlesForClass(cl, combatStateClass);
            return org.handlesResolved && !org.handlesFailed
                    && org.iconGetO0OOHandle != null
                    && org.o0ooSetColorsHandle != null
                    && org.iconSetDeployStateHandle != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void ensureHandles(Object combatUi) {
        if (handlesResolved || handlesFailed || combatUi == null) return;
        ensureHandlesForClass(combatUi.getClass().getClassLoader(), combatUi.getClass());
    }

    private void ensureHandlesForClass(ClassLoader cl, Class<?> combatUiClass) {
        if (handlesResolved || handlesFailed || cl == null || combatUiClass == null) return;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            Class<?> panelClass = Class.forName("com.fs.starfarer.ui.interfacenew", false, cl);
            Class<?> uiCompBaseClass = Class.forName("com.fs.starfarer.ui.c", false, cl);
            Class<?> posClass = Class.forName("com.fs.starfarer.ui.OO0O", false, cl);
            Class<?> buttonImplClass = Class.forName("com.fs.starfarer.ui.n", false, cl);
            Class<?> fleetMemberImplClass = Class.forName("com.fs.starfarer.campaign.fleet.FleetMember", false, cl);
            Class<?> iconClass = Class.forName("com.fs.starfarer.ui.B.new", false, cl);
            Class<?> inputEventImplClass = Class.forName("com.fs.starfarer.util.A.C", false, cl);
            Class<?> scrollingListClass = Class.forName("com.fs.starfarer.coreui.v", false, cl);
            Class<?> scrollerClass = Class.forName("com.fs.starfarer.ui.g", false, cl);

            this.fddClass = Class.forName("com.fs.starfarer.ui.impl.oO0O", false, cl);
            this.shipGridClass = Class.forName("com.fs.starfarer.campaign.ui.S", false, cl);

            this.isShowingDeploymentDialogHandle = lookup.findVirtual(
                    combatUiClass,
                    "isShowingDeploymentDialog",
                    MethodType.methodType(boolean.class));
            this.getWidgetPanelHandle = lookup.findVirtual(
                    combatUiClass,
                    "getWidgetPanel",
                    MethodType.methodType(panelClass));
            this.panelGetChildrenCopyHandle = lookup.findVirtual(
                    panelClass,
                    "getChildrenCopy",
                    MethodType.methodType(List.class));

            this.fddGetInnerPanelHandle = lookup.findVirtual(
                    fddClass,
                    "getInnerPanel",
                    MethodType.methodType(panelClass));
            this.fddSizeToInnerHandle = lookup.findVirtual(
                    fddClass,
                    "sizeToInner",
                    MethodType.methodType(posClass, float.class, float.class));
            this.fddGetOwnerIdHandle = lookup.findVirtual(
                    fddClass,
                    "getOwnerId",
                    MethodType.methodType(int.class));
            this.fddIsMultiDeployHandle = lookup.findVirtual(
                    fddClass,
                    "isMultiDeploy",
                    MethodType.methodType(boolean.class));
            this.fddGetSelectedHandle = lookup.findVirtual(
                    fddClass,
                    "getSelected",
                    MethodType.methodType(List.class));
            this.fddFleetMemberClickedHandle = lookup.findVirtual(
                    fddClass,
                    "fleetMemberClicked",
                    MethodType.methodType(void.class, fleetMemberImplClass, iconClass, inputEventImplClass));

            this.gridGetMembersHandle = lookup.findVirtual(
                    shipGridClass,
                    "getMembers",
                    MethodType.methodType(List.class));
            this.gridGetButtonForMemberHandle = lookup.findVirtual(
                    shipGridClass,
                    "getButtonForMember",
                    MethodType.methodType(buttonImplClass, fleetMemberImplClass));
            this.gridGetIconForMemberHandle = lookup.findVirtual(
                    shipGridClass,
                    "getIconForMember",
                    MethodType.methodType(iconClass, fleetMemberImplClass));
            this.gridGetListHandle = lookup.findVirtual(
                    shipGridClass,
                    "getList",
                    MethodType.methodType(scrollingListClass));

            this.listGetColumnsHandle = lookup.findVirtual(
                    scrollingListClass,
                    "getColumns",
                    MethodType.methodType(int.class));
            this.listGetItemWidthHandle = lookup.findVirtual(
                    scrollingListClass,
                    "getItemWidth",
                    MethodType.methodType(float.class));
            this.listGetItemHeightHandle = lookup.findVirtual(
                    scrollingListClass,
                    "getItemHeight",
                    MethodType.methodType(float.class));
            this.listGetItemsHandle = lookup.findVirtual(
                    scrollingListClass,
                    "getItems",
                    MethodType.methodType(List.class));
            this.listClearHandle = lookup.findVirtual(
                    scrollingListClass,
                    "clear",
                    MethodType.methodType(void.class, boolean.class));
            this.listAddItemHandle = lookup.findVirtual(
                    scrollingListClass,
                    "addItem",
                    MethodType.methodType(int.class, uiCompBaseClass, Object.class));
            this.listGetScrollerHandle = lookup.findVirtual(
                    scrollingListClass,
                    "getScroller",
                    MethodType.methodType(scrollerClass));

            this.scrollerSetContentSizeHandle = lookup.findVirtual(
                    scrollerClass,
                    "setContentSize",
                    MethodType.methodType(void.class, float.class, float.class));
            try {
                this.scrollerSetShowGridHandle = lookup.findVirtual(
                        scrollerClass,
                        "setShowGrid",
                        MethodType.methodType(void.class, float.class, float.class, boolean.class));
            } catch (Throwable ignored) {
            }
            try {
                this.compSetOpacityHandle = lookup.findVirtual(
                        uiCompBaseClass,
                        "setOpacity",
                        MethodType.methodType(void.class, float.class));
            } catch (Throwable ignored) {
            }
            try {
                this.panelSetSizeHandle = lookup.findVirtual(
                        panelClass,
                        "setSize",
                        MethodType.methodType(posClass, float.class, float.class));
            } catch (Throwable ignored) {
            }
            try {
                MethodHandles.Lookup vLookup = MethodHandles.privateLookupIn(scrollingListClass, lookup);
                this.listSetColsFieldHandle = vLookup.findSetter(scrollingListClass, "do.do$return", int.class);
            } catch (Throwable ignored) {
            }
            try {
                MethodHandles.Lookup sLookup = MethodHandles.privateLookupIn(shipGridClass, lookup);
                this.gridSetColsFieldHandle = sLookup.findSetter(shipGridClass, "O\u00f60o00", int.class);
            } catch (Throwable ignored) {
            }
            this.scrollerPackHandle = lookup.findVirtual(
                    scrollerClass,
                    "pack",
                    MethodType.methodType(void.class));
            this.scrollerClampOffsetHandle = lookup.findVirtual(
                    scrollerClass,
                    "clampOffset",
                    MethodType.methodType(void.class));
            this.posRecomputeHandle = lookup.findVirtual(
                    posClass,
                    "recompute",
                    MethodType.methodType(void.class));

            try {
                Class<?> o0ooClass = Class.forName("com.fs.starfarer.renderers.O0OO", false, cl);
                this.iconGetO0OOHandle = lookup.findVirtual(
                        iconClass,
                        "\u00d2O0000",
                        MethodType.methodType(o0ooClass));
                this.o0ooSetColorsHandle = lookup.findVirtual(
                        o0ooClass,
                        "o00000",
                        MethodType.methodType(void.class, Color.class, Color.class));
                this.iconSetDeployStateHandle = lookup.findVirtual(
                        iconClass,
                        "Object",
                        MethodType.methodType(void.class, int.class));
                this.iconSetDeployTextHandle = lookup.findVirtual(
                        iconClass,
                        "Object",
                        MethodType.methodType(void.class, String.class));
            } catch (Throwable ignored) {
            }

            this.handlesResolved = true;
        } catch (Throwable t) {
            this.handlesFailed = true;
            LOG.warn("[DivisionAI] Could not resolve Deployment Dialog MethodHandles", t);
        }
    }

    public void advance(CombatEngineAPI engine) {
        if (engine == null) return;
        CombatUIAPI ui = engine.getCombatUI();
        if (ui == null) return;
        // Fast path during combat / Warroom when the deployment dialog is closed and no emergency OTC modal is active
        if (!ui.isShowingDeploymentDialog()
                && standaloneEmergencyModalParent == null
                && !DivisionOtcCommandManager.isEmergencyModalAutoOpenRequested()
                && lastFddInstance == null) {
            return;
        }
        if (!"com.fs.starfarer.combat.CombatState".equals(ui.getClass().getName())) {
            return;
        }

        ensureHandles(ui);
        if (!handlesResolved) return;

        try {
            boolean showingDeploy = (boolean) isShowingDeploymentDialogHandle.invoke(ui);
            UIPanelAPI widgetPanel = (UIPanelAPI) getWidgetPanelHandle.invoke(ui);
            if (!showingDeploy) {
                lastFddInstance = null;
                cachedDeployedGridForActiveFdd = null;
                cachedReservesGridForActiveFdd = null;
                lastReservesSignature = "";
                cachedEntriesForActiveFdd.clear();
                activeFleetCommandPanel = null;

                // Feature C: If the player side's OTC ship was destroyed in battle, pause and show the OTC selection screen
                if (standaloneEmergencyModalParent != null && otcSelectorWindowOpen && activeOtcSelectorPanel != null) {
                    engine.setPaused(true);
                    if (activeOtcSelectorPollHook != null) {
                        activeOtcSelectorPollHook.run();
                    }
                    return;
                }
                if (widgetPanel != null && DivisionOtcCommandManager.consumeEmergencyModalAutoOpenRequest()) {
                    openStandaloneEmergencyOtcSelectorWindow(engine, widgetPanel);
                    return;
                }

                activeOtcSelectorPanel = null;
                standaloneEmergencyModalParent = null;
                otcSelectorWindowOpen = false;
                activeOtcSelectorPollHook = null;
                return;
            }

            if (standaloneEmergencyModalParent != null) {
                closeOtcSelectorWindow(standaloneEmergencyModalParent, null);
                standaloneEmergencyModalParent = null;
            }
            if (widgetPanel == null) return;

            Object activeFdd = null;
            @SuppressWarnings("unchecked")
            List<UIComponentAPI> widgetChildren = (List<UIComponentAPI>) panelGetChildrenCopyHandle.invoke(widgetPanel);
            for (UIComponentAPI child : widgetChildren) {
                if (child != null && fddClass.isInstance(child)) {
                    activeFdd = child;
                }
            }
            if (activeFdd == null) return;

            int ownerId = (int) fddGetOwnerIdHandle.invoke(activeFdd);
            boolean multiDeploy = (boolean) fddIsMultiDeployHandle.invoke(activeFdd);
            if (ownerId != 0 || multiDeploy) {
                return;
            }

            if (activeFdd != lastFddInstance) {
                cachedEntriesForActiveFdd.clear();
                cachedDeployedGridForActiveFdd = null;
                cachedReservesGridForActiveFdd = null;
                activeFleetCommandPanel = null;
                activeOtcSelectorPanel = null;
                otcSelectorWindowOpen = false;
                activeOtcSelectorPollHook = null;
                lastReservesSignature = "";
            }

            if (otcSelectorWindowOpen) {
                return;
            }

            UIPanelAPI innerPanel = (UIPanelAPI) fddGetInnerPanelHandle.invoke(activeFdd);
            if (innerPanel == null) return;

            Object deployedGrid = cachedDeployedGridForActiveFdd;
            Object reservesGrid = cachedReservesGridForActiveFdd;
            if (reservesGrid == null) {
                Object[] grids = findDeployedAndReservesGrids(innerPanel);
                deployedGrid = grids[0];
                reservesGrid = grids[1];
                cachedDeployedGridForActiveFdd = deployedGrid;
                cachedReservesGridForActiveFdd = reservesGrid;
            }
            if (reservesGrid == null) return;

            // 1. Enlarge the Deployment Window (wider 14-column layout + taller 7-row reserve viewport)
            int targetCols = enlargeDeploymentDialogIfNeeded(activeFdd, innerPanel, deployedGrid, reservesGrid);

            // Requirement A: Hide any "No ships deployed" / "Deployed ships" labels in the top section
            hideVanillaDeployedSectionLabels(innerPanel);

            @SuppressWarnings("unchecked")
            List<FleetMemberAPI> deployedMembers = (deployedGrid != null)
                    ? (List<FleetMemberAPI>) gridGetMembersHandle.invoke(deployedGrid)
                    : new ArrayList<FleetMemberAPI>();
            if (deployedMembers == null) deployedMembers = new ArrayList<>();

            @SuppressWarnings("unchecked")
            List<FleetMemberAPI> reserveMembers = (List<FleetMemberAPI>) gridGetMembersHandle.invoke(reservesGrid);
            if (reserveMembers == null) reserveMembers = new ArrayList<>();

            if (deployedMembers.isEmpty() && reserveMembers.isEmpty()) {
                return;
            }

            List<PermanentDivisionSpec> nonEmptyDivisions = PermanentDivisionManager.getNonEmptyPermanentDivisions();
            String sig = buildMembersSignature(deployedMembers, reserveMembers, nonEmptyDivisions, targetCols);
            if (activeFdd == lastFddInstance && sig.equals(lastReservesSignature)) {
                return;
            }

            organizeReservesByDivisionRows(
                    activeFdd,
                    deployedGrid,
                    reservesGrid,
                    deployedMembers,
                    reserveMembers,
                    nonEmptyDivisions,
                    targetCols);

            // Requirements C, D, E: Replace the former 'deployed ships' section with the Fleet Command section
            mountFleetCommandSection(
                    engine,
                    activeFdd,
                    innerPanel,
                    deployedGrid,
                    reservesGrid,
                    deployedMembers,
                    reserveMembers);

            lastFddInstance = activeFdd;
            lastReservesSignature = buildMembersSignature(deployedMembers, reserveMembers, nonEmptyDivisions, targetCols);
        } catch (Throwable t) {
            LOG.warn("[DivisionAI] Error organizing Deployment Window division rows", t);
        }
    }

    /**
     * Enlarges the pre-battle Deployment Window from 10 cols x 3 reserve rows to up to 14 cols x 7 reserve rows,
     * dynamically clamped to fit the user's screen resolution with comfortable margins.
     */
    @SuppressWarnings("unchecked")
    private int enlargeDeploymentDialogIfNeeded(
            Object fdd,
            UIPanelAPI innerPanel,
            Object deployedGrid,
            Object reservesGrid) throws Throwable {

        Object scrollingList = gridGetListHandle.invoke(reservesGrid);
        if (scrollingList == null) return 10;

        float itemW = (float) listGetItemWidthHandle.invoke(scrollingList);
        float itemH = (float) listGetItemHeightHandle.invoke(scrollingList);
        if (itemW <= 0f) itemW = 58f;
        if (itemH <= 0f) itemH = 58f;

        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();

        int maxColsForScreen = Math.max(10, Math.min(ENLARGED_TOTAL_COLS, (int) ((screenW - 140f) / itemW)));
        float targetInnerW = maxColsForScreen * itemW + 34f;
        float targetGridW = targetInnerW - 22f;

        PositionAPI innerPos = innerPanel.getPosition();
        PositionAPI resPos = ((UIComponentAPI) reservesGrid).getPosition();
        PositionAPI depPos = (deployedGrid instanceof UIComponentAPI)
                ? ((UIComponentAPI) deployedGrid).getPosition()
                : null;
        if (innerPos == null || resPos == null) return maxColsForScreen;

        float oldInnerW = innerPos.getWidth();
        float oldInnerH = innerPos.getHeight();
        float oldResH = resPos.getHeight();
        float oldDepH = (depPos != null && depPos.getHeight() > 60f) ? depPos.getHeight() : 174f;
        float targetDepH = Math.max(oldDepH, ENLARGED_FLEET_COMMAND_HEIGHT);

        float overheadH = Math.max(260f, (oldInnerH - oldResH) + (targetDepH - oldDepH));
        int maxRowsForScreen = Math.max(4, Math.min(TARGET_RESERVE_VISIBLE_ROWS, (int) ((screenH - overheadH - 80f) / itemH)));
        float targetResH = maxRowsForScreen * itemH;

        if (Math.abs(oldInnerW - targetInnerW) > 4f
                || Math.abs(oldResH - targetResH) > 4f
                || Math.abs(oldDepH - targetDepH) > 4f) {
            float targetInnerH = oldInnerH + (targetResH - oldResH) + (targetDepH - oldDepH);

            fddSizeToInnerHandle.invoke(fdd, targetInnerW, targetInnerH);
            if (fdd instanceof UIComponentAPI && ((UIComponentAPI) fdd).getPosition() != null) {
                ((UIComponentAPI) fdd).getPosition().inMid();
            }

            if (deployedGrid instanceof UIComponentAPI) {
                if (depPos != null) {
                    depPos.setSize(targetGridW, targetDepH);
                    if (panelSetSizeHandle != null) {
                        try {
                            panelSetSizeHandle.invoke(deployedGrid, targetGridW, targetDepH);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (gridSetColsFieldHandle != null) {
                    try {
                        gridSetColsFieldHandle.invoke(deployedGrid, maxColsForScreen);
                    } catch (Throwable ignored) {
                    }
                }
                reflowGridItemsToColumns(deployedGrid, maxColsForScreen);
            }

            resPos.setSize(targetGridW, targetResH);
            if (panelSetSizeHandle != null) {
                try {
                    panelSetSizeHandle.invoke(reservesGrid, targetGridW, targetResH);
                } catch (Throwable ignored) {
                }
            }
            if (gridSetColsFieldHandle != null) {
                try {
                    gridSetColsFieldHandle.invoke(reservesGrid, maxColsForScreen);
                } catch (Throwable ignored) {
                }
            }
            if (listSetColsFieldHandle != null) {
                try {
                    listSetColsFieldHandle.invoke(scrollingList, maxColsForScreen);
                } catch (Throwable ignored) {
                }
            }

            List<UIComponentAPI> children = (List<UIComponentAPI>) panelGetChildrenCopyHandle.invoke(innerPanel);
            for (UIComponentAPI child : children) {
                if (child == null || child == deployedGrid || child == reservesGrid) continue;
                PositionAPI cp = child.getPosition();
                if (cp == null) continue;
                if (Math.abs(cp.getWidth() - oldInnerW) <= 6f) {
                    cp.setSize(targetInnerW, cp.getHeight());
                } else if (Math.abs(cp.getWidth() - (10f * itemW + 4f)) <= 16f) {
                    cp.setSize(targetGridW, cp.getHeight());
                }
            }

            if (posRecomputeHandle != null) {
                posRecomputeHandle.invoke(innerPos);
            }
        }

        return maxColsForScreen;
    }

    @SuppressWarnings("unchecked")
    private void reflowGridItemsToColumns(Object shipGrid, int cols) throws Throwable {
        if (shipGrid == null || cols <= 0) return;
        Object scrollingList = gridGetListHandle.invoke(shipGrid);
        if (scrollingList == null) return;
        float itemW = (float) listGetItemWidthHandle.invoke(scrollingList);
        float itemH = (float) listGetItemHeightHandle.invoke(scrollingList);
        List<UIComponentAPI> items = (List<UIComponentAPI>) listGetItemsHandle.invoke(scrollingList);
        if (items == null || items.isEmpty()) return;

        for (int i = 0; i < items.size(); i++) {
            UIComponentAPI comp = items.get(i);
            if (comp != null && comp.getPosition() != null) {
                int r = i / cols;
                int c = i % cols;
                comp.getPosition().setSize(itemW, itemH).inTL(c * itemW, r * itemH);
            }
        }
        int totalRows = (items.size() + cols - 1) / cols;
        Object scroller = listGetScrollerHandle.invoke(scrollingList);
        if (scroller != null) {
            scrollerSetContentSizeHandle.invoke(scroller, cols * itemW, totalRows * itemH);
            scrollerPackHandle.invoke(scroller);
            scrollerClampOffsetHandle.invoke(scroller);
        }
    }

    private Object[] findDeployedAndReservesGrids(UIPanelAPI innerPanel) throws Throwable {
        Object highestGrid = null;
        Object lowestGrid = null;
        float highestY = -Float.MAX_VALUE;
        float lowestY = Float.MAX_VALUE;
        @SuppressWarnings("unchecked")
        List<UIComponentAPI> children = (List<UIComponentAPI>) panelGetChildrenCopyHandle.invoke(innerPanel);
        for (UIComponentAPI child : children) {
            if (child != null && shipGridClass.isInstance(child) && child.getPosition() != null) {
                float y = child.getPosition().getY();
                if (y > highestY) {
                    highestY = y;
                    highestGrid = child;
                }
                if (y < lowestY) {
                    lowestY = y;
                    lowestGrid = child;
                }
            }
        }
        if (highestGrid == lowestGrid) {
            highestGrid = null;
        }
        return new Object[]{highestGrid, lowestGrid};
    }

    @SuppressWarnings("unchecked")
    private void hideVanillaDeployedSectionLabels(UIPanelAPI innerPanel) throws Throwable {
        if (innerPanel == null) return;
        List<UIComponentAPI> children = (List<UIComponentAPI>) panelGetChildrenCopyHandle.invoke(innerPanel);
        if (children == null) return;
        for (UIComponentAPI child : children) {
            if (child instanceof LabelAPI) {
                LabelAPI lbl = (LabelAPI) child;
                String txt = lbl.getText();
                if (txt != null) {
                    String lower = txt.trim().toLowerCase(Locale.ROOT);
                    if (lower.contains("no ships deployed")
                            || lower.contains("no allied ships deployed")
                            || lower.equals("deployed ships")) {
                        lbl.setText("");
                        lbl.setOpacity(0f);
                    }
                }
            }
        }
    }

    private String buildMembersSignature(
            List<FleetMemberAPI> deployedMembers,
            List<FleetMemberAPI> reserveMembers,
            List<PermanentDivisionSpec> nonEmptyDivs,
            int totalCols) {
        StringBuilder sb = new StringBuilder((deployedMembers.size() + reserveMembers.size()) * 14 + 24);
        sb.append("D:");
        for (FleetMemberAPI m : deployedMembers) {
            if (m != null) {
                sb.append(m.getId()).append(',');
            }
        }
        sb.append("|R:");
        for (FleetMemberAPI m : reserveMembers) {
            if (m != null) {
                sb.append(m.getId()).append(',');
            }
        }
        sb.append('|').append(nonEmptyDivs.size()).append('|').append(totalCols);
        return sb.toString();
    }

    private static class MemberIconEntry {
        final FleetMemberAPI member;
        final Object button;
        final Object icon;
        boolean isAlreadyDeployed;

        MemberIconEntry(FleetMemberAPI member, Object button, Object icon, boolean isAlreadyDeployed) {
            this.member = member;
            this.button = button;
            this.icon = icon;
            this.isAlreadyDeployed = isAlreadyDeployed;
        }
    }

    private void organizeReservesByDivisionRows(
            Object fdd,
            Object deployedGrid,
            Object reservesGrid,
            List<FleetMemberAPI> deployedMembersList,
            List<FleetMemberAPI> reserveMembersList,
            List<PermanentDivisionSpec> nonEmptyDivisions,
            int totalCols) throws Throwable {

        // 1. Capture existing ship buttons and icons from BOTH deployedGrid and reservesGrid
        //    before clearing their ScrollingLists (using cachedEntriesForActiveFdd across re-layouts)
        Map<String, MemberIconEntry> byMemberId = new LinkedHashMap<>();

        if (deployedGrid != null && deployedMembersList != null) {
            for (FleetMemberAPI m : new ArrayList<>(deployedMembersList)) {
                if (m == null || m.getId() == null) continue;
                Object btn = gridGetButtonForMemberHandle.invoke(deployedGrid, m);
                Object icon = gridGetIconForMemberHandle.invoke(deployedGrid, m);
                MemberIconEntry entry;
                if (btn != null && icon != null) {
                    entry = new MemberIconEntry(m, btn, icon, true);
                    cachedEntriesForActiveFdd.put(m.getId(), entry);
                } else {
                    entry = cachedEntriesForActiveFdd.get(m.getId());
                    if (entry != null) {
                        entry.isAlreadyDeployed = true;
                    }
                }
                if (entry != null) {
                    // Clear any stale "DEPLOY" text on already-deployed ship icons
                    if (iconSetDeployStateHandle != null) {
                        try {
                            iconSetDeployStateHandle.invoke(entry.icon, -1);
                        } catch (Throwable ignored) {
                        }
                    }
                    applyIconSquareTint(entry.icon, true);
                    byMemberId.put(m.getId(), entry);
                }
            }
            // Requirement A: Clear the visual ScrollingList of deployedGrid so no ships render in the top section
            try {
                compSetOpacityHandle.invoke(deployedGrid, 0f);
            } catch (Throwable ignored) {
            }
            Object deployedScrollingList = gridGetListHandle.invoke(deployedGrid);
            if (deployedScrollingList != null) {
                listClearHandle.invoke(deployedScrollingList, false);
                Object depScroller = listGetScrollerHandle.invoke(deployedScrollingList);
                if (depScroller != null) {
                    try {
                        scrollerSetShowGridHandle.invoke(depScroller, 0f, 0f, false);
                    } catch (Throwable ignored) {
                    }
                    scrollerSetContentSizeHandle.invoke(depScroller, 0f, 0f);
                    scrollerPackHandle.invoke(depScroller);
                }
            }
        }

        if (reserveMembersList != null) {
            for (FleetMemberAPI m : new ArrayList<>(reserveMembersList)) {
                if (m == null || m.getId() == null) continue;
                Object btn = gridGetButtonForMemberHandle.invoke(reservesGrid, m);
                Object icon = gridGetIconForMemberHandle.invoke(reservesGrid, m);
                MemberIconEntry entry;
                if (btn != null && icon != null) {
                    entry = new MemberIconEntry(m, btn, icon, false);
                    cachedEntriesForActiveFdd.put(m.getId(), entry);
                } else {
                    entry = cachedEntriesForActiveFdd.get(m.getId());
                    if (entry != null) {
                        entry.isAlreadyDeployed = false;
                    }
                }
                if (entry != null && !byMemberId.containsKey(m.getId())) {
                    byMemberId.put(m.getId(), entry);
                }
            }
        }

        if (byMemberId.isEmpty()) return;

        Object scrollingList = gridGetListHandle.invoke(reservesGrid);
        if (scrollingList == null) return;

        if (totalCols < 6) totalCols = ENLARGED_TOTAL_COLS;
        float itemW = (float) listGetItemWidthHandle.invoke(scrollingList);
        float itemH = (float) listGetItemHeightHandle.invoke(scrollingList);

        // 2. Partition all fleet ships (both already-deployed and reserve) into rows following the player's
        //    list of non-empty Permanent Divisions
        Set<String> placedMemberIds = new HashSet<>();
        List<FleetMemberAPI> orderedReserveMembers = new ArrayList<>(reserveMembersList.size());
        IndexedMemberList indexedReserveMembers = new IndexedMemberList();

        // Clear the ScrollingList UI items (while keeping scroll position)
        listClearHandle.invoke(scrollingList, false);

        int currentSlotIndex = 0;
        int labelCols = LABEL_COLS; // Columns 0..1 (116px wide) hold the Division Row Header card
        int shipsPerRow = Math.max(1, totalCols - labelCols);
        DivisionNomenclature.NomenclatureCategory playerNomCat = DivisionNomenclature.getPlayerNomenclatureCategory();
        int divSeq = 1;

        for (PermanentDivisionSpec divSpec : nonEmptyDivisions) {
            List<MemberIconEntry> divEntries = new ArrayList<>();

            // Put the division's designated Flagship first in the row if present
            if (divSpec.flagshipMemberId != null) {
                MemberIconEntry flagEntry = byMemberId.get(divSpec.flagshipMemberId);
                if (flagEntry != null && divSpec.memberIds.contains(divSpec.flagshipMemberId)
                        && !placedMemberIds.contains(divSpec.flagshipMemberId)) {
                    divEntries.add(flagEntry);
                    placedMemberIds.add(divSpec.flagshipMemberId);
                }
            }

            // Followed by the remaining members in division order
            for (String memberId : divSpec.memberIds) {
                if (placedMemberIds.contains(memberId)) continue;
                MemberIconEntry entry = byMemberId.get(memberId);
                if (entry != null) {
                    divEntries.add(entry);
                    placedMemberIds.add(memberId);
                }
            }

            if (divEntries.isEmpty()) {
                continue;
            }

            boolean isFlagshipDiv = false;
            for (MemberIconEntry e : divEntries) {
                if (!e.isAlreadyDeployed) {
                    orderedReserveMembers.add(e.member);
                }
                if (e.member != null && e.member.isFlagship()) {
                    isFlagshipDiv = true;
                }
            }

            PermanentDivisionManager.FleetDoctrineConfig docCfg = PermanentDivisionManager.getFleetDoctrine();
            PermanentDivisionManager.DivisionFormation divFm =
                    PermanentDivisionManager.resolveEffectiveFormation(divSpec, docCfg);
            String rowTitle = DivisionNomenclature.resolveDynamicDivisionDisplayName(
                    playerNomCat,
                    divSpec.archetype,
                    divSeq++,
                    divSpec.name);

            currentSlotIndex = appendRowGroupToScrollingList(
                    fdd,
                    scrollingList,
                    rowTitle,
                    true,
                    divSpec,
                    divFm,
                    isFlagshipDiv,
                    divEntries,
                    indexedReserveMembers,
                    currentSlotIndex,
                    totalCols,
                    labelCols,
                    shipsPerRow,
                    itemW,
                    itemH);
        }

        // 3. Place any unassigned ships in their own row(s) below the player's divisions
        List<MemberIconEntry> unassignedEntries = new ArrayList<>();
        for (Map.Entry<String, MemberIconEntry> e : byMemberId.entrySet()) {
            if (!placedMemberIds.contains(e.getKey())) {
                MemberIconEntry entry = e.getValue();
                unassignedEntries.add(entry);
                if (!entry.isAlreadyDeployed) {
                    orderedReserveMembers.add(entry.member);
                }
            }
        }

        if (!unassignedEntries.isEmpty()) {
            currentSlotIndex = appendRowGroupToScrollingList(
                    fdd,
                    scrollingList,
                    "Unassigned",
                    false,
                    null,
                    null,
                    false,
                    unassignedEntries,
                    indexedReserveMembers,
                    currentSlotIndex,
                    totalCols,
                    labelCols,
                    shipsPerRow,
                    itemW,
                    itemH);
        }

        // 4. Update scroller content size for the enlarged column count
        int totalRows = (currentSlotIndex + totalCols - 1) / totalCols;
        Object scroller = listGetScrollerHandle.invoke(scrollingList);
        if (scroller != null) {
            try {
                scrollerSetShowGridHandle.invoke(scroller, itemW, itemH, false);
            } catch (Throwable ignored) {
            }
            scrollerSetContentSizeHandle.invoke(scroller, totalCols * itemW, totalRows * itemH);
            scrollerPackHandle.invoke(scroller);
            scrollerClampOffsetHandle.invoke(scroller);
        }

        // 5. Sync reserveMembersList order to match the division row order (only undeployed reserve ships!)
        indexedReserveMembers.addAll(orderedReserveMembers);
        this.lastIndexedReserveMembers = indexedReserveMembers;
        reserveMembersList.clear();
        reserveMembersList.addAll(orderedReserveMembers);
    }

    private int appendRowGroupToScrollingList(
            Object fdd,
            Object scrollingList,
            String groupTitle,
            boolean isDivisionRow,
            PermanentDivisionSpec divSpec,
            PermanentDivisionManager.DivisionFormation divFm,
            boolean isFlagshipDiv,
            List<MemberIconEntry> entries,
            IndexedMemberList indexedReserveMembers,
            int currentSlotIndex,
            int totalCols,
            int labelCols,
            int shipsPerRow,
            float itemW,
            float itemH) throws Throwable {

        float totalDp = 0f;
        for (MemberIconEntry e : entries) {
            totalDp += e.member.getDeploymentPointsCost();
        }

        int rowsForGroup = Math.max(1, (entries.size() + shipsPerRow - 1) / shipsPerRow);
        float groupCardHeight = itemH * rowsForGroup;

        int idx = 0;
        boolean firstRowOfGroup = true;
        while (idx < entries.size()) {
            while ((currentSlotIndex % totalCols) != 0) {
                addEmptySpacerCell(scrollingList, currentSlotIndex, totalCols, itemW, itemH);
                currentSlotIndex++;
            }

            int rowIdx = currentSlotIndex / totalCols;

            if (firstRowOfGroup) {
                CustomPanelAPI headerCard = createRowHeaderCard(
                        fdd,
                        groupTitle,
                        totalDp,
                        isDivisionRow,
                        divSpec,
                        divFm,
                        isFlagshipDiv,
                        entries,
                        itemW * labelCols,
                        groupCardHeight,
                        totalCols * itemW);
                listAddItemHandle.invoke(scrollingList, headerCard, null);
                headerCard.getPosition()
                        .setSize(itemW * labelCols - 3f, groupCardHeight - 2f)
                        .inTL(0f, rowIdx * itemH);
                currentSlotIndex++;

                for (int c = 1; c < labelCols; c++) {
                    addEmptySpacerCell(scrollingList, currentSlotIndex, totalCols, itemW, itemH);
                    currentSlotIndex++;
                }
                firstRowOfGroup = false;
            } else {
                for (int c = 0; c < labelCols; c++) {
                    addEmptySpacerCell(scrollingList, currentSlotIndex, totalCols, itemW, itemH);
                    currentSlotIndex++;
                }
            }

            int countInRow = 0;
            while (idx < entries.size() && countInRow < shipsPerRow) {
                MemberIconEntry entry = entries.get(idx++);
                int slotCol = currentSlotIndex % totalCols;
                int slotRow = currentSlotIndex / totalCols;
                listAddItemHandle.invoke(scrollingList, entry.button, entry.icon);
                if (indexedReserveMembers != null && !entry.isAlreadyDeployed) {
                    indexedReserveMembers.recordSlotMapping(currentSlotIndex, entry.member);
                }
                if (entry.button instanceof UIComponentAPI) {
                    PositionAPI bp = ((UIComponentAPI) entry.button).getPosition();
                    if (bp != null) {
                        bp.setSize(itemW, itemH).inTL(slotCol * itemW, slotRow * itemH);
                    }
                }
                currentSlotIndex++;
                countInRow++;
            }

            while ((currentSlotIndex % totalCols) != 0) {
                addEmptySpacerCell(scrollingList, currentSlotIndex, totalCols, itemW, itemH);
                currentSlotIndex++;
            }
        }

        return currentSlotIndex;
    }

    private void addEmptySpacerCell(
            Object scrollingList,
            int slotIndex,
            int totalCols,
            float itemW,
            float itemH) throws Throwable {
        int col = slotIndex % totalCols;
        int row = slotIndex / totalCols;
        CustomPanelAPI spacer = Global.getSettings().createCustom(0f, 0f, null);
        listAddItemHandle.invoke(scrollingList, spacer, null);
        spacer.getPosition().setSize(0f, 0f).inTL(col * itemW, row * itemH);
    }

    private CustomPanelAPI createRowHeaderCard(
            final Object fdd,
            final String title,
            final float totalDp,
            final boolean isDivisionRow,
            final PermanentDivisionSpec divSpec,
            final PermanentDivisionManager.DivisionFormation divFm,
            final boolean isFlagshipDiv,
            final List<MemberIconEntry> groupEntries,
            final float width,
            final float height,
            final float rowSpanWidth) {

        final ButtonAPI[] toggleBtnHolder = new ButtonAPI[1];
        final CustomPanelAPI[] cardRef = new CustomPanelAPI[1];
        final Color accentColor = isDivisionRow ? DIV_ACCENT_COLOR : UNASSIGNED_ACCENT_COLOR;
        final float cardW = width - 3f;
        final float cardH = height - 2f;

        Color titleColor = isDivisionRow ? DIV_HEADER_TEXT_COLOR : UNASSIGNED_TEXT_COLOR;
        Color btnBase = isDivisionRow ? DIV_BTN_BASE_COLOR : UNASSIGNED_BTN_BASE;
        Color btnDark = isDivisionRow ? DIV_BTN_DARK_COLOR : UNASSIGNED_BTN_DARK;

        LabelAPI line1Label = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        line1Label.setColor(titleColor);
        line1Label.setAlignment(Alignment.LMID);

        LabelAPI line2Label = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        line2Label.setColor(titleColor);
        line2Label.setAlignment(Alignment.LMID);

        boolean hasFormationBadge = isDivisionRow && divFm != null;
        float line1LeftX = hasFormationBadge ? 20f : 6f;
        float line2LeftX = 6f;
        float maxLine1W = Math.max(42f, cardW - line1LeftX - 4f);
        float maxLine2W = Math.max(54f, cardW - line2LeftX - 4f);

        String[] lines = splitDeploymentCardTitleLines(title, line1Label, maxLine1W, maxLine2W);
        final boolean isTwoLineTitle = (lines.length >= 2 && lines[1] != null && !lines[1].isEmpty());
        // Vertical center offset from top of card for the 12x12 formation icon badge so it aligns with Line 1
        final float iconCenterTopY = isTwoLineTitle ? 9.5f : 15.5f;

        BaseCustomUIPanelPlugin cardPlugin = new BaseCustomUIPanelPlugin() {
            @Override
            public void renderBelow(float alphaMult) {
                CustomPanelAPI self = cardRef[0];
                if (self == null || self.getPosition() == null) return;
                PositionAPI pos = self.getPosition();
                float x = pos.getX() + 1f;
                float y = pos.getY() + 2f;
                float w = width - 4f;
                float h = height - 4f;

                float r = accentColor.getRed() / 255f;
                float g = accentColor.getGreen() / 255f;
                float b = accentColor.getBlue() / 255f;

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                // Dark card backdrop
                GL11.glColor4f(0.03f, 0.06f, 0.10f, 0.92f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                // Left vertical accent stripe
                GL11.glColor4f(r, g, b, 0.92f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + 3f, y);
                GL11.glVertex2f(x + 3f, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                // Subtle row frame enclosing the division's ships
                if (rowSpanWidth > w + 4f) {
                    GL11.glLineWidth(1.0f);
                    GL11.glColor4f(r, g, b, 0.25f * alphaMult);
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2f(x, y);
                    GL11.glVertex2f(pos.getX() + rowSpanWidth - 2f, y);
                    GL11.glVertex2f(pos.getX() + rowSpanWidth - 2f, y + h);
                    GL11.glVertex2f(x, y + h);
                    GL11.glEnd();
                }

                GL11.glPopAttrib();

                // Render 12x12 Formation Icon Box aligned with the division name
                PermanentDivisionManager.DivisionFormation activeDivFm = (divSpec != null)
                        ? PermanentDivisionManager.resolveEffectiveFormation(divSpec, PermanentDivisionManager.getFleetDoctrine())
                        : divFm;
                if (isDivisionRow && activeDivFm != null) {
                    float iconSize = 12f;
                    float iconCx = pos.getX() + 11.5f;
                    float iconCy = pos.getY() + pos.getHeight() - iconCenterTopY;
                    FleetDoctrineDialog.renderFormationIcon(
                            activeDivFm,
                            iconCx,
                            iconCy,
                            iconSize,
                            Color.WHITE,
                            0.95f * alphaMult,
                            isFlagshipDiv);
                }
            }

            @Override
            public void render(float alphaMult) {
                renderGoldenSquaresForGroupEntries(fdd, groupEntries, alphaMult);
            }

            @Override
            public void buttonPressed(Object buttonId) {
                if ("TOGGLE_ROW".equals(buttonId)) {
                    ButtonAPI btn = toggleBtnHolder[0];
                    if (btn != null) btn.setChecked(false);
                    toggleGroupSelection(fdd, groupEntries);
                }
            }

            @Override
            public void advance(float amount) {
                ButtonAPI btn = toggleBtnHolder[0];
                if (btn != null && btn.isChecked()) {
                    btn.setChecked(false);
                    toggleGroupSelection(fdd, groupEntries);
                }
            }
        };

        CustomPanelAPI card = Global.getSettings().createCustom(cardW, cardH, cardPlugin);
        cardRef[0] = card;

        // Pre-initialize non-zero label widths before setText() so Starsector's label never word-wraps spaces into newlines
        PositionAPI p1 = card.addComponent((UIComponentAPI) line1Label);
        PositionAPI p2 = card.addComponent((UIComponentAPI) line2Label);
        float boxW1 = Math.max(20f, cardW - line1LeftX - 1f);
        float boxW2 = Math.max(20f, cardW - line2LeftX - 1f);
        p1.setSize(boxW1, 13f).inTL(line1LeftX, 3f);
        p2.setSize(boxW2, 13f).inTL(line2LeftX, 16f);

        if (!isTwoLineTitle) {
            String l1 = lines.length > 0 ? lines[0] : "";
            l1 = CampaignFleetTooltipOrganizer.fitSingleLineToWidth(l1, line1Label, boxW1 - 3f);
            p1.setSize(boxW1, 14f).inTL(line1LeftX, 9f);
            line1Label.setText(l1);

            p2.setSize(boxW2, 0f).inTL(line2LeftX, 0f);
            line2Label.setText("");
        } else {
            String l1 = CampaignFleetTooltipOrganizer.fitSingleLineToWidth(lines[0], line1Label, boxW1 - 3f);
            String l2 = CampaignFleetTooltipOrganizer.fitSingleLineToWidth(lines[1], line2Label, boxW2 - 3f);
            p1.setSize(boxW1, 13f).inTL(line1LeftX, 3f);
            line1Label.setText(l1);

            p2.setSize(boxW2, 13f).inTL(line2LeftX, 16f);
            line2Label.setText(l2);
        }

        // Bottom Select button sits at y_top = 35f..51f (well below Line 2 at y_top = 16f..29f, so it never eats the division name)
        TooltipMakerAPI tt = card.createUIElement(cardW, cardH, false);
        String btnLabel = String.format(Locale.ROOT, "Select (%.0f DP)", totalDp);
        ButtonAPI btn = tt.addButton(
                btnLabel,
                "TOGGLE_ROW",
                btnBase,
                btnDark,
                Alignment.MID,
                CutStyle.ALL,
                cardW - 10f,
                16f,
                0f);
        btn.getPosition().inTL(5f, 35f);
        toggleBtnHolder[0] = btn;

        tt.setHeightSoFar(cardH);
        card.addUIElement(tt).inTL(0f, 0f);
        return card;
    }

    /**
     * Splits a deployment screen division title into at most 2 non-overflowing lines (Line 1 beside the
     * 12x12 formation icon badge within {@code maxLine1W}, and Line 2 across {@code maxLine2W}), so the
     * title always stays in the top zone ({@code y_top = 3..29px}) and never collides with the Select button
     * at {@code y_top = 35px}.
     */
    public static String[] splitDeploymentCardTitleLines(
            String rawTitle,
            LabelAPI measurer,
            float maxLine1W,
            float maxLine2W) {
        if (rawTitle == null) return new String[]{""};
        String norm = rawTitle.trim().replaceAll("\\s+", " ");
        if (norm.isEmpty()) return new String[]{""};

        if (CampaignFleetTooltipOrganizer.measureLineWidth(norm, measurer) <= maxLine1W + 1f) {
            return new String[]{norm};
        }

        String[] rawWords = norm.split(" ");
        List<String> wordList = new ArrayList<>(rawWords.length + 2);
        for (String tok : rawWords) {
            if (CampaignFleetTooltipOrganizer.measureLineWidth(tok, measurer) > maxLine1W) {
                int splitPos = Math.max(tok.indexOf('-'), tok.indexOf('/'));
                if (splitPos > 0 && splitPos < tok.length() - 1) {
                    wordList.add(tok.substring(0, splitPos + 1));
                    wordList.add(tok.substring(splitPos + 1));
                    continue;
                }
            }
            wordList.add(tok);
        }
        String[] words = wordList.toArray(new String[0]);
        if (words.length <= 1) {
            return new String[]{CampaignFleetTooltipOrganizer.fitSingleLineToWidth(norm, measurer, maxLine1W)};
        }

        String bestL1 = words[0];
        String bestL2 = "";
        float bestScore = Float.MAX_VALUE;
        for (int split = 1; split < words.length; split++) {
            StringBuilder sb1 = new StringBuilder();
            for (int i = 0; i < split; i++) {
                if (i > 0) sb1.append(' ');
                sb1.append(words[i]);
            }
            StringBuilder sb2 = new StringBuilder();
            for (int i = split; i < words.length; i++) {
                if (i > split) sb2.append(' ');
                sb2.append(words[i]);
            }
            String cand1 = sb1.toString();
            String cand2 = sb2.toString();
            float w1 = CampaignFleetTooltipOrganizer.measureLineWidth(cand1, measurer);
            float w2 = CampaignFleetTooltipOrganizer.measureLineWidth(cand2, measurer);
            float over1 = Math.max(0f, w1 - maxLine1W);
            float over2 = Math.max(0f, w2 - maxLine2W);
            float score = (over1 + over2) * 100f + Math.max(w1 / Math.max(1f, maxLine1W), w2 / Math.max(1f, maxLine2W)) * 20f
                    + Math.abs(w1 - w2) * 0.1f;
            if (score < bestScore) {
                bestScore = score;
                bestL1 = cand1;
                bestL2 = cand2;
            }
        }
        return new String[]{
                CampaignFleetTooltipOrganizer.fitSingleLineToWidth(bestL1, measurer, maxLine1W),
                CampaignFleetTooltipOrganizer.fitSingleLineToWidth(bestL2, measurer, maxLine2W)
        };
    }

    @SuppressWarnings("unchecked")
    private void toggleGroupSelection(Object fdd, List<MemberIconEntry> entries) {
        try {
            List<FleetMemberAPI> currentlySelected = (List<FleetMemberAPI>) fddGetSelectedHandle.invoke(fdd);
            Set<String> selectedIds = new HashSet<>();
            if (currentlySelected != null) {
                for (FleetMemberAPI m : currentlySelected) {
                    if (m != null) selectedIds.add(m.getId());
                }
            }

            boolean anyDeployableUnselected = false;
            for (MemberIconEntry e : entries) {
                if (!e.isAlreadyDeployed && e.member.canBeDeployedForCombat() && !selectedIds.contains(e.member.getId())) {
                    anyDeployableUnselected = true;
                    break;
                }
            }

            if (anyDeployableUnselected) {
                // Select all unselected deployable ships in this division row
                for (MemberIconEntry e : entries) {
                    if (!e.isAlreadyDeployed && e.member.canBeDeployedForCombat() && !selectedIds.contains(e.member.getId())) {
                        fddFleetMemberClickedHandle.invoke(fdd, e.member, e.icon, null);
                    }
                }
            } else {
                // All deployable ships in this division row are already selected -> deselect them
                for (MemberIconEntry e : entries) {
                    if (e.isAlreadyDeployed || !e.member.canBeDeployedForCombat()) continue;
                    for (int attempt = 0; attempt < 4; attempt++) {
                        List<FleetMemberAPI> selNow = (List<FleetMemberAPI>) fddGetSelectedHandle.invoke(fdd);
                        boolean stillSelected = false;
                        if (selNow != null) {
                            for (FleetMemberAPI m : selNow) {
                                if (m != null && m.getId().equals(e.member.getId())) {
                                    stillSelected = true;
                                    break;
                                }
                            }
                        }
                        if (!stillSelected) break;
                        fddFleetMemberClickedHandle.invoke(fdd, e.member, e.icon, null);
                    }
                }
            }
        } catch (Throwable t) {
            LOG.warn("[DivisionAI] Failed to toggle division row in Deployment Window", t);
        }
    }

    private void applyIconSquareTint(Object icon, boolean gold) {
        if (icon == null || iconGetO0OOHandle == null || o0ooSetColorsHandle == null) return;
        try {
            Object o0oo = iconGetO0OOHandle.invoke(icon);
            if (o0oo != null) {
                if (gold) {
                    o0ooSetColorsHandle.invoke(o0oo, GOLDEN_SQUARE_COLOR, GOLDEN_SQUARE_SPOTLIGHT);
                } else {
                    o0ooSetColorsHandle.invoke(o0oo, DEFAULT_RESERVE_GRID_COLOR, DEFAULT_RESERVE_GRID_COLOR);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Requirement B: Marks out both already-deployed ships and ships selected to deploy with golden squares
     * on their icons inside their division rows.
     */
    @SuppressWarnings("unchecked")
    private void renderGoldenSquaresForGroupEntries(
            Object fdd,
            List<MemberIconEntry> groupEntries,
            float alphaMult) {
        if (groupEntries == null || groupEntries.isEmpty()) return;
        Set<String> selectedIds = new HashSet<>();
        try {
            if (fdd != null && fddGetSelectedHandle != null) {
                List<FleetMemberAPI> sel = (List<FleetMemberAPI>) fddGetSelectedHandle.invoke(fdd);
                if (sel != null) {
                    for (FleetMemberAPI m : sel) {
                        if (m != null && m.getId() != null) selectedIds.add(m.getId());
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        for (MemberIconEntry e : groupEntries) {
            if (e == null || e.member == null) continue;
            boolean isSelected = !e.isAlreadyDeployed && selectedIds.contains(e.member.getId());
            boolean showGold = e.isAlreadyDeployed || isSelected;
            applyIconSquareTint(e.icon, showGold);
            if (!showGold || !(e.button instanceof UIComponentAPI)) continue;

            PositionAPI bp = ((UIComponentAPI) e.button).getPosition();
            if (bp == null) continue;
            float bx = bp.getX() + 3f;
            float by = bp.getY() + 3f;
            float bw = bp.getWidth() - 6f;
            float bh = bp.getHeight() - 6f;
            if (bw <= 4f || bh <= 4f) continue;

            // Subtle inner golden wash
            GL11.glColor4f(1.0f, 0.84f, 0.16f, 0.11f * alphaMult);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(bx, by);
            GL11.glVertex2f(bx + bw, by);
            GL11.glVertex2f(bx + bw, by + bh);
            GL11.glVertex2f(bx, by + bh);
            GL11.glEnd();

            // Primary golden square border
            GL11.glLineWidth(2.0f);
            GL11.glColor4f(1.0f, 0.85f, 0.18f, 0.96f * alphaMult);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(bx, by);
            GL11.glVertex2f(bx + bw, by);
            GL11.glVertex2f(bx + bw, by + bh);
            GL11.glVertex2f(bx, by + bh);
            GL11.glEnd();

            // Crisp inner golden accent line
            GL11.glLineWidth(1.0f);
            GL11.glColor4f(1.0f, 0.94f, 0.55f, 0.55f * alphaMult);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(bx + 2f, by + 2f);
            GL11.glVertex2f(bx + bw - 2f, by + 2f);
            GL11.glVertex2f(bx + bw - 2f, by + bh - 2f);
            GL11.glVertex2f(bx + 2f, by + bh - 2f);
            GL11.glEnd();
        }

        GL11.glPopAttrib();
    }

    /**
     * Formats the primary OTC commander title in the Fleet Command section (Requirements D & E):
     *  - Standard OTC: commander name only (the surrounding panel already establishes command).
     *  - Alpha Core-equipped Flagship: "ORDO COGITATOR/MASTER: <SHIP NAME IN ALL CAPS>"
     */
    public static String formatFleetCommandOtcTitle(
            String commanderName,
            String flagshipShipName,
            boolean isAlphaCoreFlagship) {
        if (isAlphaCoreFlagship) {
            String shipUpper = (flagshipShipName != null && !flagshipShipName.trim().isEmpty())
                    ? flagshipShipName.trim().toUpperCase(Locale.ROOT)
                    : "OMEGA-NEXUS";
            return DivisionNomenclature.POST_AI_WAR_OTC_TITLE + ": " + shipUpper;
        }
        String name = (commanderName != null && !commanderName.trim().isEmpty())
                ? commanderName.trim()
                : "Fleet Commander";
        return name;
    }

    /**
     * Requirement E: Computes the 0.5-second jitter offset [dx, dy] for the word "MASTER" in
     * "ORDO COGITATOR/MASTER: <SHIP NAME>". Every 0.50 seconds, "MASTER" snaps to a new jitter offset
     * with a high-frequency glitch burst on each 0.5s beat.
     */
    public static float[] computeMasterJitterOffset(double elapsedRealSeconds) {
        if (elapsedRealSeconds < 0.0) elapsedRealSeconds = 0.0;
        long halfSecBucket = (long) Math.floor(elapsedRealSeconds / MASTER_JITTER_PERIOD_SECONDS);
        double phaseInBucket = (elapsedRealSeconds - halfSecBucket * MASTER_JITTER_PERIOD_SECONDS)
                / MASTER_JITTER_PERIOD_SECONDS;

        // Deterministic hash per 0.5s bucket so every 0.5s boundary produces a distinct jump
        long h = (halfSecBucket * 0x9E3779B97F4A7C15L) ^ 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        int hx = (int) (h & 0xFF);
        int hy = (int) ((h >>> 8) & 0xFF);

        // Non-zero step offset that changes every 0.5 seconds
        float stepDx = ((hx % 2 == 0) ? 1f : -1f) * (0.55f + (hx / 255f) * 1.05f);
        float stepDy = ((hy % 2 == 0) ? 1f : -1f) * (0.30f + (hy / 255f) * 0.75f);

        // Sharp high-frequency burst during the first 30% (150ms) of each 0.5s period
        if (phaseInBucket < 0.30) {
            long subTick = (long) Math.floor(elapsedRealSeconds * 60.0);
            float oscX = ((subTick % 2L == 0L) ? 1f : -1f) * 1.20f;
            float oscY = (((subTick / 2L) % 2L == 0L) ? 1f : -1f) * 0.80f;
            return new float[]{stepDx + oscX, stepDy + oscY};
        }
        return new float[]{stepDx, stepDy};
    }

    /**
     * Requirement E: Returns true if the player's flagship (or OTC flagship) is an Alpha Core-equipped ship.
     */
    public static boolean isFlagshipAlphaCoreEquipped(
            CombatEngineAPI engine,
            FleetMemberAPI flagshipMember,
            BattleGroupState playerGroup) {
        if (flagshipMember != null) {
            if (BattlefieldFirepowerEvaluator.isAlphaCoreControlledMember(flagshipMember)) {
                return true;
            }
            PersonAPI cap = flagshipMember.getCaptain();
            if (cap != null && cap.getPortraitSprite() != null
                    && cap.getPortraitSprite().toLowerCase(Locale.ROOT).contains("portrait_ai2")) {
                return true;
            }
            try {
                if (flagshipMember.getVariant() != null && flagshipMember.getVariant().getHullMods() != null) {
                    for (String hm : flagshipMember.getVariant().getHullMods()) {
                        if (hm == null) continue;
                        String low = hm.toLowerCase(Locale.ROOT);
                        if (low.contains("alpha_core") || low.contains("aicore_alpha")) {
                            return true;
                        }
                    }
                    if (flagshipMember.getVariant().hasHullMod("automated")
                            && BattlefieldFirepowerEvaluator.hasAlphaCoreInPlayerFleet(engine)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
            return DivisionOtcCommandManager.isAlphaCoreOtcLockedInBattle(playerGroup);
        }
        if (engine != null && engine.getPlayerShip() != null
                && BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(engine.getPlayerShip())) {
            return true;
        }
        if (playerGroup != null && playerGroup.activeFleetFlagship != null
                && BattlefieldFirepowerEvaluator.isAlphaCoreControlledShip(playerGroup.activeFleetFlagship)) {
            return true;
        }
        if (playerGroup != null && playerGroup.otc != null) {
            if (BattlefieldFirepowerEvaluator.isAlphaCoreOfficer(playerGroup.otc.person)) {
                return true;
            }
            String pName = playerGroup.otc.commanderName != null
                    ? playerGroup.otc.commanderName.toLowerCase(Locale.ROOT)
                    : "";
            if (pName.contains("alpha") || pName.contains("cogitator") || pName.contains("omega")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPreBattleDeployment(CombatEngineAPI engine, List<FleetMemberAPI> deployedMembers) {
        if (deployedMembers != null && !deployedMembers.isEmpty()) {
            return false;
        }
        if (engine != null && engine.getShips() != null) {
            for (ShipAPI s : engine.getShips()) {
                if (s != null && s.getOwner() == 0 && !s.isAlly() && !s.isFighter() && !s.isStationModule() && s.isAlive() && !s.isHulk()) {
                    return false;
                }
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public boolean isOtcMemberSelectedOrDeployed(
            CombatEngineAPI engine,
            Object fdd,
            FleetMemberAPI otcMember,
            List<FleetMemberAPI> deployedMembers) {
        if (otcMember == null) return false;
        String targetId = otcMember.getId();
        if (deployedMembers != null) {
            for (FleetMemberAPI dm : deployedMembers) {
                if (dm == otcMember || (dm != null && targetId != null && targetId.equals(dm.getId()))) {
                    return true;
                }
            }
        }
        if (engine != null && targetId != null) {
            ShipAPI live = DivisionOtcCommandManager.findLiveShipForMember(engine, targetId);
            if (live != null
                    && live.isAlive()
                    && !live.isHulk()
                    && !DivisionOtcCommandManager.isShipRetreatedFromBattle(engine, playerGroup, live)) {
                return true;
            }
        }
        if (fdd != null && fddGetSelectedHandle != null) {
            try {
                List<FleetMemberAPI> selected = (List<FleetMemberAPI>) fddGetSelectedHandle.invoke(fdd);
                if (selected != null) {
                    for (FleetMemberAPI sm : selected) {
                        if (sm == otcMember || (sm != null && targetId != null && targetId.equals(sm.getId()))) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private FleetMemberAPI resolvePlayerFlagshipMember(
            Object fdd,
            boolean preBattle,
            List<FleetMemberAPI> deployedMembers,
            List<FleetMemberAPI> reserveMembers) {
        List<FleetMemberAPI> all = new ArrayList<>();
        if (deployedMembers != null) all.addAll(deployedMembers);
        if (reserveMembers != null) all.addAll(reserveMembers);

        if (preBattle) {
            List<FleetMemberAPI> selectedForDeploy = null;
            Object activeFdd = (fdd != null) ? fdd : lastFddInstance;
            if (activeFdd != null && fddGetSelectedHandle != null) {
                try {
                    selectedForDeploy = (List<FleetMemberAPI>) fddGetSelectedHandle.invoke(activeFdd);
                } catch (Throwable ignored) {
                }
            }
            if ((selectedForDeploy == null || selectedForDeploy.isEmpty())
                    && cachedDeployedGridForActiveFdd != null
                    && gridGetMembersHandle != null) {
                try {
                    List<FleetMemberAPI> liveDeployed =
                            (List<FleetMemberAPI>) gridGetMembersHandle.invoke(cachedDeployedGridForActiveFdd);
                    if (liveDeployed != null && !liveDeployed.isEmpty()) {
                        selectedForDeploy = liveDeployed;
                    }
                } catch (Throwable ignored) {
                }
            }
            FleetMemberAPI preOtc = DivisionOtcCommandManager.resolvePreBattlePlayerOtcMember(
                    playerGroup, all, selectedForDeploy);
            if (preOtc != null) {
                return preOtc;
            }
        }

        String preferredId = DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup);
        if (preferredId != null && !preferredId.isEmpty()) {
            for (FleetMemberAPI m : all) {
                if (m != null && preferredId.equals(m.getId())) return m;
            }
        }
        for (FleetMemberAPI m : all) {
            if (m != null && m.isFlagship()) return m;
        }
        List<PermanentDivisionSpec> divs = PermanentDivisionManager.getNonEmptyPermanentDivisions();
        if (!divs.isEmpty() && divs.get(0).flagshipMemberId != null) {
            String fid = divs.get(0).flagshipMemberId;
            for (FleetMemberAPI m : all) {
                if (m != null && fid.equals(m.getId())) return m;
            }
        }
        FleetMemberAPI best = null;
        float bestDp = -1f;
        for (FleetMemberAPI m : all) {
            if (m != null && m.getDeploymentPointsCost() > bestDp) {
                bestDp = m.getDeploymentPointsCost();
                best = m;
            }
        }
        return best;
    }

    private void mountFleetCommandSection(
            CombatEngineAPI engine,
            Object fdd,
            UIPanelAPI innerPanel,
            Object deployedGrid,
            Object reservesGrid,
            List<FleetMemberAPI> deployedMembers,
            List<FleetMemberAPI> reserveMembers) throws Throwable {
        if (innerPanel == null) return;

        if (activeFleetCommandPanel != null) {
            try {
                innerPanel.removeComponent(activeFleetCommandPanel);
            } catch (Throwable ignored) {
            }
            activeFleetCommandPanel = null;
        }
        if (activeOtcSelectorPanel != null) {
            try {
                innerPanel.removeComponent(activeOtcSelectorPanel);
            } catch (Throwable ignored) {
            }
            activeOtcSelectorPanel = null;
            otcSelectorWindowOpen = false;
            activeOtcSelectorPollHook = null;
        }

        PositionAPI resPos = ((UIComponentAPI) reservesGrid).getPosition();
        PositionAPI depPos = (deployedGrid instanceof UIComponentAPI)
                ? ((UIComponentAPI) deployedGrid).getPosition()
                : null;

        PositionAPI innerPos = innerPanel.getPosition();
        float panelW = (resPos != null && resPos.getWidth() > 100f)
                ? resPos.getWidth()
                : (innerPos != null ? innerPos.getWidth() - 22f : 824f);
        float panelH = (depPos != null && depPos.getHeight() > 60f)
                ? depPos.getHeight()
                : 174f;
        float leftOff = (resPos != null && innerPos != null)
                ? Math.max(0f, resPos.getX() - innerPos.getX())
                : 10f;
        float topOff = (depPos != null && innerPos != null)
                ? Math.max(0f, (innerPos.getY() + innerPos.getHeight()) - (depPos.getY() + depPos.getHeight()))
                : 10f;

        CustomPanelAPI fcPanel = createFleetCommandPanel(
                engine,
                fdd,
                innerPanel,
                reservesGrid,
                panelW,
                panelH,
                deployedMembers,
                reserveMembers);
        activeFleetCommandPanel = fcPanel;
        PositionAPI fcPos = innerPanel.addComponent(fcPanel);
        if (fcPos != null) {
            fcPos.setSize(panelW, panelH).inTL(leftOff, topOff);
        }
    }

    private CustomPanelAPI createFleetCommandPanel(
            final CombatEngineAPI engine,
            final Object fdd,
            final UIPanelAPI innerPanel,
            final Object reservesGrid,
            final float width,
            final float height,
            final List<FleetMemberAPI> deployedMembers,
            final List<FleetMemberAPI> reserveMembers) {

        final boolean preBattle = deployedMembers == null || deployedMembers.isEmpty();
        final FleetMemberAPI[] currentFlagshipRef = new FleetMemberAPI[]{
                resolvePlayerFlagshipMember(fdd, preBattle, deployedMembers, reserveMembers)
        };
        final boolean[] isAlphaCoreRef = new boolean[]{
                isFlagshipAlphaCoreEquipped(engine, currentFlagshipRef[0], playerGroup)
        };
        final String[] portraitSpritePathRef = new String[]{ALPHA_CORE_PORTRAIT_SPRITE};

        final float topBarH = 20f;
        final float portraitTopY = topBarH + 6f;
        final float portraitH = Math.max(82f, Math.min(92f, height - topBarH - 42f));
        final float[] portraitWRef = new float[]{
                isAlphaCoreRef[0]
                        ? Math.min(148f, portraitH * ALPHA_CORE_PORTRAIT_STRETCH_ASPECT)
                        : portraitH
        };
        final float portraitX = 12f;
        final float leftSectionW = Math.round(width * 0.46f);
        final float rightSectionX = leftSectionW + 8f;
        final float rightSectionW = Math.max(280f, width - rightSectionX - 6f);
        final float cmdColW = Math.round((rightSectionW - 6f) * 0.48f);
        final float cmdColX = rightSectionX;
        final float formColX = rightSectionX + cmdColW + 6f;
        final float formColW = Math.max(140f, rightSectionW - cmdColW - 6f);
        final float rightStatusMaxW = Math.max(180f, rightSectionW - 204f);
        final float titleTopY = topBarH + 10f;
        final float[] masterBasePos = new float[]{portraitX + portraitWRef[0] + 12f, titleTopY};
        final PositionAPI[] masterPosRef = new PositionAPI[1];
        final PositionAPI[] masterGhostPosRef = new PositionAPI[1];
        final PositionAPI[] prePosRef = new PositionAPI[1];
        final PositionAPI[] sufPosRef = new PositionAPI[1];
        final PositionAPI[] sufLine2PosRef = new PositionAPI[1];
        final PositionAPI[] stdTitlePosRef = new PositionAPI[1];
        final PositionAPI[] stdTitleLine2PosRef = new PositionAPI[1];
        final PositionAPI[] flagshipPosRef = new PositionAPI[1];
        final PositionAPI[] flagshipLine2PosRef = new PositionAPI[1];
        final PositionAPI[] profilePosRef = new PositionAPI[1];
        final PositionAPI[] profileLine2PosRef = new PositionAPI[1];
        final CustomPanelAPI[] panelRef = new CustomPanelAPI[1];
        final ButtonAPI[] prevButtonRef = new ButtonAPI[1];
        final ButtonAPI[] nextButtonRef = new ButtonAPI[1];
        final ButtonAPI[] entryButtonRef = new ButtonAPI[1];
        final ButtonAPI[] authPrevButtonRef = new ButtonAPI[1];
        final ButtonAPI[] authNextButtonRef = new ButtonAPI[1];
        final ButtonAPI[] changeOtcButtonRef = new ButtonAPI[1];
        final float[] liveEngagementLevel = new float[]{0f};
        final float[] liveCommitmentRatio = new float[]{0f};
        final float[] liveCohesionRatio = new float[]{1f};

        final LabelAPI headerBannerLabel = Global.getSettings().createLabel("FLEET COMMAND", Fonts.ORBITRON_12);
        headerBannerLabel.setColor(isAlphaCoreRef[0] ? new Color(110, 235, 255, 255) : new Color(255, 215, 80, 255));
        final LabelAPI otcStandardTitleLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_20AA);
        otcStandardTitleLabel.setColor(new Color(255, 222, 90, 255));
        final LabelAPI otcStandardTitleLine2Label = Global.getSettings().createLabel("", Fonts.ORBITRON_20AA);
        otcStandardTitleLine2Label.setColor(new Color(255, 222, 90, 255));
        final LabelAPI cogPrefixLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        cogPrefixLabel.setColor(new Color(120, 235, 255, 255));
        final LabelAPI cogMasterGhostLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        cogMasterGhostLabel.setColor(new Color(255, 65, 95, 170));
        final LabelAPI cogMasterLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        cogMasterLabel.setColor(new Color(235, 252, 255, 255));
        final LabelAPI cogSuffixLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_20AA);
        cogSuffixLabel.setColor(new Color(150, 238, 255, 255));
        final LabelAPI cogSuffixLine2Label = Global.getSettings().createLabel("", Fonts.ORBITRON_20AA);
        cogSuffixLine2Label.setColor(new Color(150, 238, 255, 255));
        final LabelAPI flagshipLabel = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        flagshipLabel.setColor(new Color(215, 230, 242, 245));
        final LabelAPI flagshipLine2Label = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        flagshipLine2Label.setColor(new Color(215, 230, 242, 245));
        final LabelAPI profileLabel = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        profileLabel.setColor(new Color(175, 215, 235, 240));
        final LabelAPI profileLine2Label = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        profileLine2Label.setColor(new Color(175, 215, 235, 240));
        final LabelAPI cmdStyleHeaderLabel = Global.getSettings().createLabel(
                preBattle ? "COMMAND STYLE" : "", Fonts.ORBITRON_12);
        cmdStyleHeaderLabel.setColor(new Color(255, 215, 80, 255));
        cmdStyleHeaderLabel.setAlignment(Alignment.MID);
        final LabelAPI cmdStyleNameLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        cmdStyleNameLabel.setColor(new Color(255, 232, 130, 255));
        cmdStyleNameLabel.setAlignment(Alignment.MID);
        final LabelAPI selectorHeaderLabel = Global.getSettings().createLabel(
                preBattle ? "FLEET FORMATION" : "FLEET STATUS", Fonts.ORBITRON_12);
        selectorHeaderLabel.setColor(new Color(255, 215, 80, 255));
        selectorHeaderLabel.setAlignment(preBattle ? Alignment.MID : Alignment.LMID);
        final LabelAPI formationNameLabel = Global.getSettings().createLabel("", preBattle ? Fonts.ORBITRON_12 : Fonts.ORBITRON_20AA);
        formationNameLabel.setColor(new Color(235, 245, 255, 255));
        formationNameLabel.setAlignment(preBattle ? Alignment.MID : Alignment.LMID);
        final LabelAPI engagementLabel = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        engagementLabel.setColor(new Color(235, 245, 255, 255));
        final LabelAPI commitmentLabel = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        commitmentLabel.setColor(new Color(225, 238, 248, 250));
        final LabelAPI cohesionLabel = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        cohesionLabel.setColor(new Color(215, 240, 248, 245));
        final LabelAPI phaseLabel = Global.getSettings().createLabel("", "graphics/fonts/victor14.fnt");
        phaseLabel.setColor(new Color(145, 225, 245, 235));

        assertCoolTacticalFonts(headerBannerLabel);
        assertCoolTacticalFonts(otcStandardTitleLabel);
        assertCoolTacticalFonts(otcStandardTitleLine2Label);
        assertCoolTacticalFonts(cogPrefixLabel);
        assertCoolTacticalFonts(cogMasterGhostLabel);
        assertCoolTacticalFonts(cogMasterLabel);
        assertCoolTacticalFonts(cogSuffixLabel);
        assertCoolTacticalFonts(cogSuffixLine2Label);
        assertCoolTacticalFonts(flagshipLabel);
        assertCoolTacticalFonts(flagshipLine2Label);
        assertCoolTacticalFonts(profileLabel);
        assertCoolTacticalFonts(profileLine2Label);
        assertCoolTacticalFonts(cmdStyleHeaderLabel);
        assertCoolTacticalFonts(cmdStyleNameLabel);
        assertCoolTacticalFonts(selectorHeaderLabel);
        assertCoolTacticalFonts(formationNameLabel);
        assertCoolTacticalFonts(engagementLabel);
        assertCoolTacticalFonts(commitmentLabel);
        assertCoolTacticalFonts(cohesionLabel);
        assertCoolTacticalFonts(phaseLabel);

        BaseCustomUIPanelPlugin fcPlugin = new BaseCustomUIPanelPlugin() {
            private void refreshOtcDisplay() {
                FleetMemberAPI fm = resolvePlayerFlagshipMember(fdd, preBattle, deployedMembers, reserveMembers);
                currentFlagshipRef[0] = fm;
                boolean alpha = isFlagshipAlphaCoreEquipped(engine, fm, playerGroup);
                isAlphaCoreRef[0] = alpha;
                if (playerGroup != null && fm != null && fm.getId() != null) {
                    if (playerGroup.preferredOtcShipId == null || playerGroup.preferredOtcShipId.isEmpty()) {
                        playerGroup.preferredOtcShipId = fm.getId();
                    }
                    if (preBattle && !DivisionOtcCommandManager.isExplicitPlayerOtcChoiceMade(playerGroup)) {
                        PersonAPI autoCap = DivisionOtcCommandManager.resolveCandidatePerson(fm, null);
                        String autoName = (autoCap != null && autoCap.getNameString() != null && !autoCap.getNameString().trim().isEmpty())
                                ? autoCap.getNameString().trim()
                                : "Fleet Commander";
                        playerGroup.otc = new DivisionModel.CommanderProfile(autoCap, autoName);
                    }
                    if (alpha) {
                        playerGroup.useConvergence = true;
                        BattlefieldFirepowerEvaluator.markInitialPlayerOtcAlphaCore(true);
                    } else if (preBattle) {
                        playerGroup.useConvergence = false;
                        BattlefieldFirepowerEvaluator.markInitialPlayerOtcAlphaCore(false);
                        BattlefieldFirepowerEvaluator.setPlayerConvergenceFlagshipOverride(null);
                        DivisionOtcCommandManager.clearPlayerAlphaCoreOtcLockInBattle(playerGroup);
                    }
                }
                portraitWRef[0] = alpha
                        ? Math.min(148f, portraitH * ALPHA_CORE_PORTRAIT_STRETCH_ASPECT)
                        : portraitH;

                PersonAPI resolvedPerson = (fm != null && fm.getCaptain() != null && !fm.getCaptain().isDefault())
                        ? fm.getCaptain()
                        : null;
                if (resolvedPerson == null) {
                    try {
                        if (Global.getSector() != null && Global.getSector().getPlayerPerson() != null) {
                            resolvedPerson = Global.getSector().getPlayerPerson();
                        }
                    } catch (Throwable ignored) {
                    }
                }
                String commanderName = (resolvedPerson != null && resolvedPerson.getNameString() != null
                        && !resolvedPerson.getNameString().trim().isEmpty())
                        ? resolvedPerson.getNameString().trim()
                        : (playerGroup != null && playerGroup.otc != null && playerGroup.otc.commanderName != null
                        ? playerGroup.otc.commanderName
                        : "Fleet Commander");
                String flagshipName = (fm != null && fm.getShipName() != null && !fm.getShipName().trim().isEmpty())
                        ? fm.getShipName().trim()
                        : "FLAGSHIP";
                String hullClassName = (fm != null && fm.getHullSpec() != null
                        && fm.getHullSpec().getHullNameWithDashClass() != null)
                        ? fm.getHullSpec().getHullNameWithDashClass()
                        : "Command Hull";
                String candidatePortrait = (resolvedPerson != null && resolvedPerson.getPortraitSprite() != null
                        && !resolvedPerson.getPortraitSprite().trim().isEmpty())
                        ? resolvedPerson.getPortraitSprite().trim()
                        : ALPHA_CORE_PORTRAIT_SPRITE;
                if (alpha) candidatePortrait = ALPHA_CORE_PORTRAIT_SPRITE;
                portraitSpritePathRef[0] = candidatePortrait;

                boolean otcDeployedOrSelected = isOtcMemberSelectedOrDeployed(engine, fdd, fm, deployedMembers);
                if (!otcDeployedOrSelected) {
                    headerBannerLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                            "FLEET COMMAND - OTC NOT DEPLOYED", headerBannerLabel, Math.max(180f, leftSectionW - 20f)));
                    headerBannerLabel.setColor(new Color(255, 95, 75, 255));
                } else {
                    headerBannerLabel.setText("FLEET COMMAND");
                    headerBannerLabel.setColor(alpha ? new Color(110, 235, 255, 255) : new Color(255, 215, 80, 255));
                }
                float textLeftX = portraitX + portraitWRef[0] + 12f;
                float maxLeftTextW = Math.max(160f, leftSectionW - textLeftX - 6f);
                float maxWrapW = Math.max(110f, maxLeftTextW - 14f);
                float safeLabelBoxW = maxLeftTextW;
                float flagshipTop;
                float profileTop;

                if (alpha) {
                    otcStandardTitleLabel.setText("");
                    otcStandardTitleLine2Label.setText("");
                    if (stdTitlePosRef[0] != null) stdTitlePosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);
                    if (stdTitleLine2PosRef[0] != null) stdTitleLine2PosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);

                    String prefix = "ORDO COGITATOR/";
                    String masterWord = "MASTER";
                    String shipLine = flagshipName.toUpperCase(Locale.ROOT);
                    float effTitleTopY = topBarH + 6f;
                    if (prePosRef[0] != null) prePosRef[0].setSize(safeLabelBoxW, 14f).inTL(textLeftX, effTitleTopY);
                    cogPrefixLabel.setText(prefix);
                    float preW = CampaignFleetTooltipOrganizer.measureLineWidth(prefix, cogPrefixLabel);
                    float masterW = CampaignFleetTooltipOrganizer.measureLineWidth(masterWord, cogMasterLabel);
                    boolean wrapMasterLine = (preW + masterW + 6f > maxWrapW);
                    float masterX = wrapMasterLine ? textLeftX : (textLeftX + preW);
                    float masterY = wrapMasterLine ? (effTitleTopY + 14f) : effTitleTopY;
                    float remAfterPre = wrapMasterLine ? safeLabelBoxW : Math.max(60f, safeLabelBoxW - preW);
                    if (masterGhostPosRef[0] != null) masterGhostPosRef[0].setSize(remAfterPre, 14f).inTL(masterX, masterY);
                    if (masterPosRef[0] != null) masterPosRef[0].setSize(remAfterPre, 14f).inTL(masterX, masterY);
                    cogMasterGhostLabel.setText(masterWord);
                    cogMasterLabel.setText(masterWord);

                    float shipBaseTopY = wrapMasterLine ? (effTitleTopY + 30f) : (effTitleTopY + 16f);
                    // ORBITRON_20AA glyphs are wider than standard 15px fonts; use a tighter wrap budget so long AI ship names always wrap cleanly
                    float shipWrapBudget = Math.max(110f, maxWrapW - 12f);
                    String[] shipLines = wrapOtcTextIntoTwoLines(shipLine, cogSuffixLabel, shipWrapBudget, shipWrapBudget);
                    if (shipLines.length >= 2 && !shipLines[1].isEmpty()) {
                        if (sufPosRef[0] != null) sufPosRef[0].setSize(safeLabelBoxW, 18f).inTL(textLeftX, shipBaseTopY);
                        if (sufLine2PosRef[0] != null) sufLine2PosRef[0].setSize(safeLabelBoxW, 18f).inTL(textLeftX, shipBaseTopY + 18f);
                        cogSuffixLabel.setText(shipLines[0]);
                        cogSuffixLine2Label.setText(shipLines[1]);
                        flagshipTop = shipBaseTopY + 39f;
                    } else {
                        if (sufPosRef[0] != null) sufPosRef[0].setSize(safeLabelBoxW, 19f).inTL(textLeftX, shipBaseTopY + 2f);
                        if (sufLine2PosRef[0] != null) sufLine2PosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);
                        cogSuffixLabel.setText(shipLines[0]);
                        cogSuffixLine2Label.setText("");
                        flagshipTop = shipBaseTopY + 25f;
                    }
                    masterBasePos[0] = masterX;
                    masterBasePos[1] = masterY;
                } else {
                    cogPrefixLabel.setText("");
                    cogMasterGhostLabel.setText("");
                    cogMasterLabel.setText("");
                    cogSuffixLabel.setText("");
                    cogSuffixLine2Label.setText("");
                    if (sufPosRef[0] != null) sufPosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);
                    if (sufLine2PosRef[0] != null) sufLine2PosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);
                    String stdTitleRaw = formatFleetCommandOtcTitle(commanderName, flagshipName, false);
                    float stdWrapBudget = Math.max(120f, maxWrapW - 8f);
                    String[] stdLines = wrapOtcTextIntoTwoLines(stdTitleRaw, otcStandardTitleLabel, stdWrapBudget, stdWrapBudget);
                    if (stdLines.length >= 2 && !stdLines[1].isEmpty()) {
                        if (stdTitlePosRef[0] != null) stdTitlePosRef[0].setSize(safeLabelBoxW, 18f).inTL(textLeftX, titleTopY - 2f);
                        if (stdTitleLine2PosRef[0] != null) stdTitleLine2PosRef[0].setSize(safeLabelBoxW, 18f).inTL(textLeftX, titleTopY + 16f);
                        otcStandardTitleLabel.setText(stdLines[0]);
                        otcStandardTitleLine2Label.setText(stdLines[1]);
                        flagshipTop = titleTopY + 37f;
                    } else {
                        if (stdTitlePosRef[0] != null) stdTitlePosRef[0].setSize(safeLabelBoxW, 20f).inTL(textLeftX, titleTopY);
                        if (stdTitleLine2PosRef[0] != null) stdTitleLine2PosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);
                        otcStandardTitleLabel.setText(stdLines[0]);
                        otcStandardTitleLine2Label.setText("");
                        flagshipTop = titleTopY + 26f;
                    }
                }

                PermanentDivisionManager.FleetDoctrineConfig doc = PermanentDivisionManager.getFleetDoctrine();
                String personality;
                if (alpha) {
                    personality = "Alpha Core";
                } else if (resolvedPerson != null && resolvedPerson.getPersonalityAPI() != null
                        && resolvedPerson.getPersonalityAPI().getDisplayName() != null
                        && !resolvedPerson.getPersonalityAPI().getDisplayName().trim().isEmpty()) {
                    personality = resolvedPerson.getPersonalityAPI().getDisplayName().trim();
                } else if (resolvedPerson != null && resolvedPerson.getPersonalityAPI() != null
                        && resolvedPerson.getPersonalityAPI().getId() != null
                        && !resolvedPerson.getPersonalityAPI().getId().trim().isEmpty()) {
                    String rawP = resolvedPerson.getPersonalityAPI().getId().trim().toLowerCase(Locale.ROOT);
                    personality = Character.toUpperCase(rawP.charAt(0)) + rawP.substring(1);
                } else if (playerGroup != null && playerGroup.otc != null && playerGroup.otc.personalityId != null
                        && !playerGroup.otc.personalityId.isEmpty()) {
                    String rawP = playerGroup.otc.personalityId.trim().toLowerCase(Locale.ROOT);
                    personality = Character.toUpperCase(rawP.charAt(0)) + rawP.substring(1);
                } else {
                    personality = "Steady";
                }
                String flagshipLine = alpha
                        ? hullClassName
                        : String.format(Locale.ROOT, "%s (%s)", flagshipName, hullClassName);
                float now = (engine != null) ? engine.getTotalElapsedTime(false) : 0f;
                String profileLine;
                if (!otcDeployedOrSelected) {
                    profileLine = "Not Deployed: -75% Max CP, -50% Regen";
                    profileLabel.setColor(new Color(255, 120, 95, 250));
                    profileLine2Label.setColor(new Color(255, 120, 95, 250));
                } else if (playerGroup != null && DivisionOtcCommandManager.isOtcDisabled(playerGroup, now)) {
                    profileLine = String.format(Locale.ROOT, "Disrupted: %.0fs | %s",
                            DivisionOtcCommandManager.getOtcDisabledSecondsRemaining(playerGroup, now),
                            doc.getOtcAuthority().shortName);
                    profileLabel.setColor(new Color(255, 165, 90, 245));
                    profileLine2Label.setColor(new Color(255, 165, 90, 245));
                } else {
                    profileLine = String.format(Locale.ROOT, "%s | %s", personality, doc.getOtcAuthority().shortName);
                    profileLabel.setColor(new Color(175, 215, 235, 240));
                    profileLine2Label.setColor(new Color(175, 215, 235, 240));
                }

                String[] flagLines = wrapOtcTextIntoTwoLines(flagshipLine, flagshipLabel, maxWrapW, maxWrapW);
                if (flagLines.length >= 2 && !flagLines[1].isEmpty()) {
                    if (flagshipPosRef[0] != null) flagshipPosRef[0].setSize(safeLabelBoxW, 14f).inTL(textLeftX, flagshipTop);
                    flagshipLabel.setText(flagLines[0]);
                    if (flagshipLine2PosRef[0] != null) flagshipLine2PosRef[0].setSize(safeLabelBoxW, 14f).inTL(textLeftX, flagshipTop + 15f);
                    flagshipLine2Label.setText(flagLines[1]);
                    profileTop = flagshipTop + 32f;
                } else {
                    if (flagshipPosRef[0] != null) flagshipPosRef[0].setSize(safeLabelBoxW, 15f).inTL(textLeftX, flagshipTop);
                    flagshipLabel.setText(flagLines[0]);
                    if (flagshipLine2PosRef[0] != null) flagshipLine2PosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);
                    flagshipLine2Label.setText("");
                    profileTop = flagshipTop + 19f;
                }

                String[] profLines = wrapOtcTextIntoTwoLines(profileLine, profileLabel, maxWrapW, maxWrapW);
                if (profLines.length >= 2 && !profLines[1].isEmpty()) {
                    if (profilePosRef[0] != null) profilePosRef[0].setSize(safeLabelBoxW, 14f).inTL(textLeftX, profileTop);
                    profileLabel.setText(profLines[0]);
                    if (profileLine2PosRef[0] != null) profileLine2PosRef[0].setSize(safeLabelBoxW, 14f).inTL(textLeftX, profileTop + 14f);
                    profileLine2Label.setText(profLines[1]);
                } else {
                    if (profilePosRef[0] != null) profilePosRef[0].setSize(safeLabelBoxW, 15f).inTL(textLeftX, profileTop);
                    profileLabel.setText(profLines[0]);
                    if (profileLine2PosRef[0] != null) profileLine2PosRef[0].setSize(safeLabelBoxW, 0f).inTL(textLeftX, 0f);
                    profileLine2Label.setText("");
                }

                ButtonAPI changeBtn = changeOtcButtonRef[0];
                if (changeBtn != null) {
                    changeBtn.getPosition().setSize(portraitWRef[0], 18f).inTL(portraitX, portraitTopY + portraitH + 5f);
                    boolean disabledByAlpha = DivisionOtcCommandManager.isPlayerOtcLockedByAlphaCoreInBattle(
                            engine, fm, playerGroup, preBattle);
                    boolean emergency = DivisionOtcCommandManager.isEmergencyPlayerOtcSelectionPending(playerGroup);
                    boolean disabledByDisruption = !preBattle && !emergency
                            && playerGroup != null && DivisionOtcCommandManager.isOtcDisabled(playerGroup, now);
                    changeBtn.setEnabled(!disabledByAlpha && !disabledByDisruption);
                }
            }

            private void updateSelectorText() {
                PermanentDivisionManager.FleetDoctrineConfig doc = PermanentDivisionManager.getFleetDoctrine();
                PermanentDivisionManager.FleetWideFormation fw;
                if (preBattle) {
                    fw = doc.getFleetWideFormation();
                    PermanentDivisionManager.OtcAuthority auth = doc.getOtcAuthority();
                    if (playerGroup != null) {
                        playerGroup.authority = auth;
                    }
                    cmdStyleNameLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                            auth.dropdownLabel, cmdStyleNameLabel, cmdColW - 64f));
                } else {
                    fw = (playerGroup != null
                            && playerGroup.activeFleetWideFormation != null
                            && playerGroup.activeFleetWideFormation != PermanentDivisionManager.FleetWideFormation.DOCTRINAL_AUTO)
                            ? playerGroup.activeFleetWideFormation
                            : doc.getFleetWideFormation();
                }
                formationNameLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                        fw.displayName, formationNameLabel,
                        preBattle ? formColW - 64f : rightStatusMaxW));
                ButtonAPI entry = entryButtonRef[0];
                if (preBattle && entry != null) {
                    boolean orch = doc.isOrchestratedAdvance();
                    entry.setText("Orchestrated Advance: " + (orch ? "On" : "Off"));
                    if (orch) entry.highlight();
                    else entry.unhighlight();
                }
            }

            private void updateLiveStatusText() {
                float eng = 0f;
                if (musicController != null) eng = Math.max(eng, musicController.getCurrentBattleEngagementLevel());
                if (playerGroup != null) {
                    eng = Math.max(eng, Math.max(playerGroup.smoothedEngagementLevel, playerGroup.fleetEngagementLevel));
                }
                if (enemyGroup != null) {
                    eng = Math.max(eng, Math.max(enemyGroup.smoothedEngagementLevel, enemyGroup.fleetEngagementLevel));
                }
                liveEngagementLevel[0] = Math.max(0f, Math.min(1f, eng));

                float depDp = 0f;
                int depCount = 0;
                if (deployedMembers != null) {
                    for (FleetMemberAPI m : deployedMembers) {
                        if (m != null) {
                            depCount++;
                            depDp += m.getDeploymentPointsCost();
                        }
                    }
                }
                float resDp = 0f;
                int resCount = 0;
                if (reserveMembers != null) {
                    for (FleetMemberAPI m : reserveMembers) {
                        if (m != null) {
                            resCount++;
                            resDp += m.getDeploymentPointsCost();
                        }
                    }
                }
                liveCommitmentRatio[0] = Math.max(0f, Math.min(1f, depDp / Math.max(1f, depDp + resDp)));

                float coh = 1.0f;
                int activeDivCount = (playerGroup != null) ? playerGroup.divisions.size() : 0;
                int totalDivSpecs = PermanentDivisionManager.getNonEmptyPermanentDivisions().size();
                if (playerGroup != null && !playerGroup.divisions.isEmpty()) {
                    float sumReady = 0f;
                    int cnt = 0;
                    for (DivisionState d : playerGroup.divisions) {
                        if (d != null && d.isCombatEffective()) {
                            float dressScore = Math.max(0.25f, Math.min(1.0f,
                                    1.25f - Math.max(0f, d.stationKeepingRatio - 0.65f) * 0.55f));
                            float fluxReady = 1.0f - d.avgFluxLevel * 0.65f;
                            sumReady += 0.5f * (dressScore + fluxReady);
                            cnt++;
                        }
                    }
                    if (cnt > 0) coh = sumReady / cnt;
                }
                liveCohesionRatio[0] = Math.max(0f, Math.min(1f, coh));

                String phaseName = (playerGroup != null && playerGroup.currentPhase != null)
                        ? playerGroup.currentPhase.displayName
                        : "Deployment";
                String engState = (eng >= 0.70f) ? "Decisive brawl"
                        : (eng >= 0.38f ? "General engagement"
                        : (eng >= 0.12f ? "Skirmish contact" : "Maneuver"));
                engagementLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                        String.format(Locale.ROOT, "ENGAGEMENT %.0f%% - %s", eng * 100f, engState),
                        engagementLabel, rightStatusMaxW));
                commitmentLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                        String.format(Locale.ROOT, "COMMITTED %.0f%% (%.0f DP)",
                                liveCommitmentRatio[0] * 100f, depDp),
                        commitmentLabel, rightStatusMaxW));
                cohesionLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                        String.format(Locale.ROOT, "COHESION %.0f%% | Divisions %d/%d",
                                coh * 100f, activeDivCount, Math.max(activeDivCount, totalDivSpecs)),
                        cohesionLabel, rightStatusMaxW));
                phaseLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                        "PHASE: " + phaseName, phaseLabel, rightStatusMaxW));
            }

            private void pollButtons() {
                if (activeOtcSelectorPollHook != null) {
                    activeOtcSelectorPollHook.run();
                }
                ButtonAPI changeBtn = changeOtcButtonRef[0];
                if (changeBtn != null && changeBtn.isChecked()) {
                    changeBtn.setChecked(false);
                    if (!DivisionOtcCommandManager.isPlayerOtcLockedByAlphaCoreInBattle(
                            engine, currentFlagshipRef[0], playerGroup, preBattle)) {
                        playDeploymentButtonSound();
                        if (otcSelectorWindowOpen) {
                            closeOtcSelectorWindow(innerPanel, reservesGrid);
                        } else {
                            openOtcSelectorWindow(
                                    engine,
                                    innerPanel,
                                    reservesGrid,
                                    width,
                                    deployedMembers,
                                    reserveMembers,
                                    currentFlagshipRef,
                                    preBattle,
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            refreshOtcDisplay();
                                        }
                                    });
                        }
                    }
                }
                if (!preBattle) return;
                ButtonAPI authPrev = authPrevButtonRef[0];
                ButtonAPI authNext = authNextButtonRef[0];
                if (authPrev != null && authPrev.isChecked()) {
                    authPrev.setChecked(false);
                    selectOtcAuthority(playerGroup, -1);
                }
                if (authNext != null && authNext.isChecked()) {
                    authNext.setChecked(false);
                    selectOtcAuthority(playerGroup, 1);
                }
                ButtonAPI prev = prevButtonRef[0];
                ButtonAPI next = nextButtonRef[0];
                ButtonAPI entry = entryButtonRef[0];
                if (prev != null && prev.isChecked()) {
                    prev.setChecked(false);
                    selectFleetFormation(playerGroup, -1);
                }
                if (next != null && next.isChecked()) {
                    next.setChecked(false);
                    selectFleetFormation(playerGroup, 1);
                }
                if (entry != null && entry.isChecked()) {
                    entry.setChecked(false);
                    PermanentDivisionManager.FleetDoctrineConfig doc = PermanentDivisionManager.getFleetDoctrine();
                    boolean enabled = !doc.isOrchestratedAdvance();
                    PermanentDivisionManager.updateOrchestratedAdvance(enabled);
                    PermanentDivisionManager.updateDeployInFleetFormation(true);
                    if (playerGroup != null) {
                        playerGroup.orchestratedAdvance = enabled;
                        playerGroup.deployInFleetFormation =
                                doc.getFleetWideFormation() != PermanentDivisionManager.FleetWideFormation.NONE;
                    }
                    playDeploymentButtonSound();
                }
                updateSelectorText();
            }

            @Override
            public void advance(float amount) {
                refreshOtcDisplay();
                if (isAlphaCoreRef[0] && masterPosRef[0] != null) {
                    float[] jitter = computeMasterJitterOffset(System.nanoTime() * 1.0e-9);
                    masterPosRef[0].inTL(masterBasePos[0] + jitter[0], masterBasePos[1] + jitter[1]);
                    if (masterGhostPosRef[0] != null) {
                        masterGhostPosRef[0].inTL(
                                masterBasePos[0] - jitter[0] * 0.75f,
                                masterBasePos[1] - jitter[1] * 0.75f);
                    }
                }
                pollButtons();
                updateSelectorText();
                if (!preBattle) updateLiveStatusText();
            }

            private boolean prevPennantLmbDown = false;

            @Override
            public void processInput(List<com.fs.starfarer.api.input.InputEventAPI> events) {
                if (!preBattle || events == null || otcSelectorWindowOpen) return;
                CustomPanelAPI self = panelRef[0];
                if (self == null || self.getPosition() == null) return;
                PositionAPI pos = self.getPosition();
                float px = pos.getX() + cmdColX;
                float py = pos.getY();
                float ph = pos.getHeight();
                float pCardW = 122f;
                float pCardH = 122f;
                float pStartX = px + Math.round((cmdColW - pCardW) * 0.5f);
                float pBottomY = py + ph - 68f - pCardH;
                for (com.fs.starfarer.api.input.InputEventAPI ev : events) {
                    if (ev == null || ev.isConsumed()) continue;
                    if (ev.isLMBDownEvent()) {
                        float ex = ev.getX();
                        float ey = ev.getY();
                        if (ex >= pStartX && ex <= pStartX + pCardW && ey >= pBottomY && ey <= pBottomY + pCardH) {
                            ev.consume();
                            selectOtcAuthority(playerGroup, 1);
                            updateSelectorText();
                            refreshOtcDisplay();
                            return;
                        }
                    }
                }
            }

            @Override
            public void renderBelow(float alphaMult) {
                CustomPanelAPI self = panelRef[0];
                if (self == null || self.getPosition() == null) return;
                PositionAPI pos = self.getPosition();
                float x = pos.getX();
                float y = pos.getY();
                float w = pos.getWidth();
                float h = pos.getHeight();
                boolean alpha = isAlphaCoreRef[0];
                float portraitW = portraitWRef[0];
                Color themeAccent = alpha ? new Color(75, 220, 255) : new Color(245, 200, 65);
                float ar = themeAccent.getRed() / 255f;
                float ag = themeAccent.getGreen() / 255f;
                float ab = themeAccent.getBlue() / 255f;

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glColor4f(0.02f, 0.05f, 0.09f, 0.94f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();
                GL11.glColor4f(ar * 0.22f, ag * 0.24f, ab * 0.28f, 0.90f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x + 1f, y + h - topBarH);
                GL11.glVertex2f(x + w - 1f, y + h - topBarH);
                GL11.glVertex2f(x + w - 1f, y + h - 1f);
                GL11.glVertex2f(x + 1f, y + h - 1f);
                GL11.glEnd();
                GL11.glLineWidth(1.5f);
                GL11.glColor4f(ar, ag, ab, 0.72f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(x + 1f, y + 1f);
                GL11.glVertex2f(x + w - 1f, y + 1f);
                GL11.glVertex2f(x + w - 1f, y + h - 1f);
                GL11.glVertex2f(x + 1f, y + h - 1f);
                GL11.glEnd();
                float divX = x + leftSectionW;
                GL11.glLineWidth(1.0f);
                GL11.glColor4f(ar, ag, ab, 0.40f * alphaMult);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex2f(divX, y + 6f);
                GL11.glVertex2f(divX, y + h - topBarH - 4f);
                GL11.glEnd();
                float rx = x + rightSectionX;
                float ry = y + 6f;
                float rw = rightSectionW;
                float rh = h - topBarH - 10f;
                if (preBattle) {
                    float cX = x + cmdColX;
                    float fX = x + formColX;
                    GL11.glColor4f(0.01f, 0.03f, 0.06f, 0.85f * alphaMult);
                    GL11.glBegin(GL11.GL_QUADS);
                    GL11.glVertex2f(cX, ry);
                    GL11.glVertex2f(cX + cmdColW, ry);
                    GL11.glVertex2f(cX + cmdColW, ry + rh);
                    GL11.glVertex2f(cX, ry + rh);
                    GL11.glVertex2f(fX, ry);
                    GL11.glVertex2f(fX + formColW, ry);
                    GL11.glVertex2f(fX + formColW, ry + rh);
                    GL11.glVertex2f(fX, ry + rh);
                    GL11.glEnd();
                    GL11.glColor4f(ar, ag, ab, 0.32f * alphaMult);
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2f(cX, ry);
                    GL11.glVertex2f(cX + cmdColW, ry);
                    GL11.glVertex2f(cX + cmdColW, ry + rh);
                    GL11.glVertex2f(cX, ry + rh);
                    GL11.glEnd();
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2f(fX, ry);
                    GL11.glVertex2f(fX + formColW, ry);
                    GL11.glVertex2f(fX + formColW, ry + rh);
                    GL11.glVertex2f(fX, ry + rh);
                    GL11.glEnd();
                } else {
                    GL11.glColor4f(0.01f, 0.03f, 0.06f, 0.85f * alphaMult);
                    GL11.glBegin(GL11.GL_QUADS);
                    GL11.glVertex2f(rx, ry);
                    GL11.glVertex2f(rx + rw, ry);
                    GL11.glVertex2f(rx + rw, ry + rh);
                    GL11.glVertex2f(rx, ry + rh);
                    GL11.glEnd();
                    GL11.glColor4f(ar, ag, ab, 0.32f * alphaMult);
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2f(rx, ry);
                    GL11.glVertex2f(rx + rw, ry);
                    GL11.glVertex2f(rx + rw, ry + rh);
                    GL11.glVertex2f(rx, ry + rh);
                    GL11.glEnd();
                    float barX = rx + 10f;
                    float barW = rightStatusMaxW;
                    renderStatusMeterBar(barX, y + h - 88f, barW, 5f,
                            liveEngagementLevel[0], new Color(90, 215, 255), alphaMult);
                    renderStatusMeterBar(barX, y + h - 114f, barW, 5f,
                            liveCommitmentRatio[0], new Color(255, 205, 65), alphaMult);
                    renderStatusMeterBar(barX, y + h - 140f, barW, 5f,
                            liveCohesionRatio[0], new Color(90, 235, 150), alphaMult);
                }
                GL11.glPopAttrib();

                float pCenterX = x + portraitX + portraitW * 0.5f;
                float pCenterY = y + h - portraitTopY - portraitH * 0.5f;
                try {
                    SpriteAPI portraitSprite = Global.getSettings().getSprite(portraitSpritePathRef[0]);
                    if (portraitSprite != null) {
                        float prevW = portraitSprite.getWidth();
                        float prevH = portraitSprite.getHeight();
                        float prevCx = portraitSprite.getCenterX();
                        float prevCy = portraitSprite.getCenterY();
                        Color prevColor = portraitSprite.getColor();
                        float prevAlpha = portraitSprite.getAlphaMult();
                        portraitSprite.setSize(portraitW, portraitH);
                        portraitSprite.setCenter(portraitW * 0.5f, portraitH * 0.5f);
                        portraitSprite.setColor(Color.WHITE);
                        portraitSprite.setAlphaMult(0.96f * alphaMult);
                        portraitSprite.renderAtCenter(pCenterX, pCenterY);
                        portraitSprite.setSize(prevW, prevH);
                        portraitSprite.setCenter(prevCx, prevCy);
                        if (prevColor != null) portraitSprite.setColor(prevColor);
                        portraitSprite.setAlphaMult(prevAlpha);
                    }
                } catch (Throwable ignored) {
                }
                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                float px0 = pCenterX - portraitW * 0.5f;
                float py0 = pCenterY - portraitH * 0.5f;
                if (alpha) {
                    GL11.glLineWidth(1.0f);
                    GL11.glColor4f(0.30f, 0.90f, 1.0f, 0.14f * alphaMult);
                    GL11.glBegin(GL11.GL_LINES);
                    for (float sy = py0 + 4f; sy < py0 + portraitH - 2f; sy += 5f) {
                        GL11.glVertex2f(px0 + 1f, sy);
                        GL11.glVertex2f(px0 + portraitW - 1f, sy);
                    }
                    GL11.glEnd();
                }
                GL11.glLineWidth(2.0f);
                GL11.glColor4f(ar, ag, ab, 0.92f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(px0, py0);
                GL11.glVertex2f(px0 + portraitW, py0);
                GL11.glVertex2f(px0 + portraitW, py0 + portraitH);
                GL11.glVertex2f(px0, py0 + portraitH);
                GL11.glEnd();
                GL11.glPopAttrib();

                PermanentDivisionManager.FleetDoctrineConfig doc = PermanentDivisionManager.getFleetDoctrine();
                PermanentDivisionManager.FleetWideFormation fw;
                if (preBattle) {
                    fw = doc.getFleetWideFormation();
                } else {
                    fw = (playerGroup != null
                            && playerGroup.activeFleetWideFormation != null
                            && playerGroup.activeFleetWideFormation != PermanentDivisionManager.FleetWideFormation.DOCTRINAL_AUTO)
                            ? playerGroup.activeFleetWideFormation
                            : doc.getFleetWideFormation();
                }
                if (preBattle) {
                    PermanentDivisionManager.OtcAuthority activeAuth = (doc != null && doc.getOtcAuthority() != null)
                            ? doc.getOtcAuthority()
                            : PermanentDivisionManager.OtcAuthority.COMMAND_BY_DIRECTION;
                    float pCardW = 122f;
                    float pCardH = 122f;
                    float pStartX = x + cmdColX + Math.round((cmdColW - pCardW) * 0.5f);
                    float pBottomY = y + h - 68f - pCardH;
                    FleetDoctrineDialog.renderOtcAuthorityPennantBox(
                            activeAuth, pStartX, pBottomY, pCardW, pCardH, true, false, alphaMult);
                    renderFleetFormationIcon(fw, x + formColX + formColW * 0.5f, y + h - 122f, 108f, alphaMult, false);
                } else {
                    PermanentDivisionManager.OtcAuthority activeAuth = (playerGroup != null && playerGroup.authority != null)
                            ? playerGroup.authority
                            : doc.getOtcAuthority();
                    float midIconSize = 86f;
                    float pennantX = rx + rw - midIconSize * 2f - 18f;
                    float pennantY = y + Math.round((h - topBarH - midIconSize) * 0.5f) - 2f;
                    float wheelCenterX = rx + rw - midIconSize * 0.5f - 8f;
                    float wheelCenterY = pennantY + midIconSize * 0.5f;
                    FleetDoctrineDialog.renderOtcAuthorityPennantBox(
                            activeAuth, pennantX, pennantY, midIconSize, midIconSize, true, false, alphaMult);
                    renderFleetFormationIcon(fw, wheelCenterX, wheelCenterY, midIconSize, alphaMult, true);
                }
            }
        };

        CustomPanelAPI panel = Global.getSettings().createCustom(width, height, fcPlugin);
        panelRef[0] = panel;
        panel.addComponent((UIComponentAPI) headerBannerLabel).setSize(width - 20f, 16f).inTL(10f, 2f);

        float initTextLeftX = portraitX + portraitWRef[0] + 12f;
        float initMaxLeftW = Math.max(160f, leftSectionW - initTextLeftX - 6f);
        prePosRef[0] = panel.addComponent((UIComponentAPI) cogPrefixLabel);
        prePosRef[0].setSize(initMaxLeftW, 15f).inTL(initTextLeftX, titleTopY);
        masterGhostPosRef[0] = panel.addComponent((UIComponentAPI) cogMasterGhostLabel);
        masterGhostPosRef[0].setSize(initMaxLeftW, 15f).inTL(initTextLeftX, titleTopY);
        masterPosRef[0] = panel.addComponent((UIComponentAPI) cogMasterLabel);
        masterPosRef[0].setSize(initMaxLeftW, 15f).inTL(initTextLeftX, titleTopY);
        sufPosRef[0] = panel.addComponent((UIComponentAPI) cogSuffixLabel);
        sufPosRef[0].setSize(initMaxLeftW, 20f).inTL(initTextLeftX, titleTopY + 24f);
        sufLine2PosRef[0] = panel.addComponent((UIComponentAPI) cogSuffixLine2Label);
        sufLine2PosRef[0].setSize(initMaxLeftW, 0f).inTL(initTextLeftX, 0f);
        stdTitlePosRef[0] = panel.addComponent((UIComponentAPI) otcStandardTitleLabel);
        stdTitlePosRef[0].setSize(initMaxLeftW, 20f).inTL(initTextLeftX, titleTopY);
        stdTitleLine2PosRef[0] = panel.addComponent((UIComponentAPI) otcStandardTitleLine2Label);
        stdTitleLine2PosRef[0].setSize(initMaxLeftW, 0f).inTL(initTextLeftX, 0f);
        flagshipPosRef[0] = panel.addComponent((UIComponentAPI) flagshipLabel);
        flagshipPosRef[0].setSize(initMaxLeftW, 16f).inTL(initTextLeftX, titleTopY + 31f);
        flagshipLine2PosRef[0] = panel.addComponent((UIComponentAPI) flagshipLine2Label);
        flagshipLine2PosRef[0].setSize(initMaxLeftW, 0f).inTL(initTextLeftX, 0f);
        profilePosRef[0] = panel.addComponent((UIComponentAPI) profileLabel);
        profilePosRef[0].setSize(initMaxLeftW, 16f).inTL(initTextLeftX, titleTopY + 55f);
        profileLine2PosRef[0] = panel.addComponent((UIComponentAPI) profileLine2Label);
        profileLine2PosRef[0].setSize(initMaxLeftW, 0f).inTL(initTextLeftX, 0f);

        float rightLabelX = rightSectionX + 8f;
        float rightMaxW = rightStatusMaxW;
        if (preBattle) {
            panel.addComponent((UIComponentAPI) cmdStyleHeaderLabel)
                    .setSize(cmdColW - 8f, 15f).inTL(cmdColX + 4f, topBarH + 4f);
            panel.addComponent((UIComponentAPI) cmdStyleNameLabel)
                    .setSize(cmdColW - 60f, 16f).inTL(cmdColX + 30f, topBarH + 24f);
            panel.addComponent((UIComponentAPI) selectorHeaderLabel)
                    .setSize(formColW - 8f, 15f).inTL(formColX + 4f, topBarH + 4f);
            panel.addComponent((UIComponentAPI) formationNameLabel)
                    .setSize(formColW - 60f, 16f).inTL(formColX + 30f, topBarH + 24f);
        } else {
            panel.addComponent((UIComponentAPI) selectorHeaderLabel)
                    .setSize(rightMaxW, 15f).inTL(rightLabelX, topBarH + 4f);
            panel.addComponent((UIComponentAPI) formationNameLabel)
                    .setSize(rightMaxW, 20f).inTL(rightLabelX, topBarH + 23f);
        }

        TooltipMakerAPI leftButtons = panel.createUIElement(leftSectionW, height, false);
        Color base = new Color(245, 200, 65);
        Color dark = new Color(24, 34, 44);
        changeOtcButtonRef[0] = leftButtons.addButton("Change", "OTC_CHANGE", base, dark,
                Alignment.MID, CutStyle.ALL, portraitWRef[0], 18f, 0f);
        changeOtcButtonRef[0].getPosition().inTL(portraitX, portraitTopY + portraitH + 5f);
        changeOtcButtonRef[0].setEnabled(!DivisionOtcCommandManager.isPlayerOtcLockedByAlphaCoreInBattle(
                engine, currentFlagshipRef[0], playerGroup, preBattle));
        leftButtons.setHeightSoFar(height);
        panel.addUIElement(leftButtons).inTL(0f, 0f);

        if (!preBattle) {
            panel.addComponent((UIComponentAPI) engagementLabel)
                    .setSize(rightMaxW, 15f).inTL(rightLabelX, 66f);
            panel.addComponent((UIComponentAPI) commitmentLabel)
                    .setSize(rightMaxW, 15f).inTL(rightLabelX, 92f);
            panel.addComponent((UIComponentAPI) cohesionLabel)
                    .setSize(rightMaxW, 15f).inTL(rightLabelX, 118f);
            panel.addComponent((UIComponentAPI) phaseLabel)
                    .setSize(rightMaxW, 14f).inTL(rightLabelX, 146f);
        } else {
            TooltipMakerAPI buttons = panel.createUIElement(width, height, false);
            authPrevButtonRef[0] = buttons.addButton("<", "OTC_AUTH_PREV", base, dark,
                    Alignment.MID, CutStyle.ALL, 24f, 20f, 0f);
            authNextButtonRef[0] = buttons.addButton(">", "OTC_AUTH_NEXT", base, dark,
                    Alignment.MID, CutStyle.ALL, 24f, 20f, 0f);
            prevButtonRef[0] = buttons.addButton("<", "FLEET_FORMATION_PREV", base, dark,
                    Alignment.MID, CutStyle.ALL, 24f, 20f, 0f);
            nextButtonRef[0] = buttons.addButton(">", "FLEET_FORMATION_NEXT", base, dark,
                    Alignment.MID, CutStyle.ALL, 24f, 20f, 0f);
            entryButtonRef[0] = buttons.addButton("Orchestrated Advance: On", "FLEET_ORCHESTRATED_ADVANCE", base, dark,
                    Alignment.MID, CutStyle.ALL, formColW - 12f, 18f, 0f);
            authPrevButtonRef[0].getPosition().inTL(cmdColX + 4f, topBarH + 22f);
            authNextButtonRef[0].getPosition().inTL(cmdColX + cmdColW - 28f, topBarH + 22f);
            prevButtonRef[0].getPosition().inTL(formColX + 4f, topBarH + 22f);
            nextButtonRef[0].getPosition().inTL(formColX + formColW - 28f, topBarH + 22f);
            entryButtonRef[0].getPosition().inTL(formColX + 6f, height - 24f);
            buttons.setHeightSoFar(height);
            panel.addUIElement(buttons).inTL(0f, 0f);
        }
        fcPlugin.advance(0f);
        return panel;
    }

    private float savedReservesLeftOff = 10f;
    private float savedReservesTopOff = 204f;

    public void closeOtcSelectorWindow(UIPanelAPI innerPanel, Object reservesGrid) {
        if (activeOtcSelectorPanel != null && innerPanel != null) {
            try {
                innerPanel.removeComponent(activeOtcSelectorPanel);
            } catch (Throwable ignored) {
            }
        }
        activeOtcSelectorPanel = null;
        standaloneEmergencyModalParent = null;
        otcSelectorWindowOpen = false;
        activeOtcSelectorPollHook = null;
        if (reservesGrid instanceof UIComponentAPI) {
            PositionAPI resPos = ((UIComponentAPI) reservesGrid).getPosition();
            if (resPos != null) {
                resPos.inTL(savedReservesLeftOff, savedReservesTopOff);
            }
            if (compSetOpacityHandle != null) {
                try {
                    compSetOpacityHandle.invoke(reservesGrid, 1f);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Feature C: Opens the OTC Selection Screen directly onto the given combat UI panel (e.g. widgetPanel)
     * and pauses the game when the player side's OTC ship is destroyed during battle.
     */
    public boolean openStandaloneEmergencyOtcSelectorWindow(
            final CombatEngineAPI engine,
            final UIPanelAPI parentPanel) {
        if (parentPanel == null) return false;
        if (engine != null) {
            try {
                engine.setPaused(true);
            } catch (Throwable ignored) {
            }
        }
        List<FleetMemberAPI> deployedMembers = new ArrayList<>();
        List<FleetMemberAPI> reserveMembers = new ArrayList<>();
        try {
            if (engine != null && engine.getFleetManager(0) != null) {
                if (engine.getFleetManager(0).getDeployedCopy() != null) {
                    for (FleetMemberAPI m : engine.getFleetManager(0).getDeployedCopy()) {
                        if (m != null && !m.isAlly()) deployedMembers.add(m);
                    }
                }
                if (engine.getFleetManager(0).getReservesCopy() != null) {
                    for (FleetMemberAPI m : engine.getFleetManager(0).getReservesCopy()) {
                        if (m != null && !m.isAlly()) reserveMembers.add(m);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        List<DivisionOtcCommandManager.OtcCandidate> candidates =
                DivisionOtcCommandManager.collectPlayerOtcCandidates(engine, playerGroup, deployedMembers, reserveMembers);
        if (candidates.isEmpty()) {
            return false;
        }
        closeOtcSelectorWindow(parentPanel, null);
        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        float winW = Math.max(640f, Math.min(920f, screenW - 140f));
        float winH = Math.max(360f, Math.min(500f, screenH - 140f));
        float leftOff = Math.max(20f, (screenW - winW) * 0.5f);
        float topOff = Math.max(20f, (screenH - winH) * 0.5f);

        final FleetMemberAPI[] currentFlagshipRef = new FleetMemberAPI[]{
                resolvePlayerFlagshipMember(null, false, deployedMembers, reserveMembers)
        };
        CustomPanelAPI winPanel = createOtcSelectorWindowPanel(
                engine,
                parentPanel,
                null,
                winW,
                winH,
                deployedMembers,
                reserveMembers,
                currentFlagshipRef,
                false,
                null);
        activeOtcSelectorPanel = winPanel;
        standaloneEmergencyModalParent = parentPanel;
        otcSelectorWindowOpen = true;
        PositionAPI wp = parentPanel.addComponent(winPanel);
        if (wp != null) {
            wp.setSize(winW, winH).inTL(leftOff, topOff);
        }
        return true;
    }

    private void openOtcSelectorWindow(
            final CombatEngineAPI engine,
            final UIPanelAPI innerPanel,
            final Object reservesGrid,
            final float fallbackWidth,
            final List<FleetMemberAPI> deployedMembers,
            final List<FleetMemberAPI> reserveMembers,
            final FleetMemberAPI[] currentFlagshipRef,
            final boolean preBattle,
            final Runnable onOtcChangedCallback) {
        if (innerPanel == null) return;
        closeOtcSelectorWindow(innerPanel, reservesGrid);

        PositionAPI innerPos = innerPanel.getPosition();
        PositionAPI resPos = (reservesGrid instanceof UIComponentAPI)
                ? ((UIComponentAPI) reservesGrid).getPosition()
                : null;
        float winW = (resPos != null && resPos.getWidth() > 100f) ? resPos.getWidth() : fallbackWidth;
        float winH = (resPos != null && resPos.getHeight() > 100f) ? resPos.getHeight() : 406f;
        float leftOff = (resPos != null && innerPos != null && resPos.getX() > -1000f)
                ? Math.max(0f, resPos.getX() - innerPos.getX())
                : 10f;
        float topOff = (resPos != null && innerPos != null && resPos.getY() > -1000f)
                ? Math.max(0f, (innerPos.getY() + innerPos.getHeight()) - (resPos.getY() + resPos.getHeight()))
                : 204f;
        savedReservesLeftOff = leftOff;
        savedReservesTopOff = topOff;

        if (resPos != null) {
            resPos.inTL(10000f, 10000f);
        }
        if (reservesGrid != null && compSetOpacityHandle != null) {
            try {
                compSetOpacityHandle.invoke(reservesGrid, 0f);
            } catch (Throwable ignored) {
            }
        }

        CustomPanelAPI winPanel = createOtcSelectorWindowPanel(
                engine,
                innerPanel,
                reservesGrid,
                winW,
                winH,
                deployedMembers,
                reserveMembers,
                currentFlagshipRef,
                preBattle,
                onOtcChangedCallback);
        activeOtcSelectorPanel = winPanel;
        otcSelectorWindowOpen = true;
        PositionAPI wp = innerPanel.addComponent(winPanel);
        if (wp != null) {
            wp.setSize(winW, winH).inTL(leftOff, topOff);
        }
    }

    private CustomPanelAPI createOtcSelectorWindowPanel(
            final CombatEngineAPI engine,
            final UIPanelAPI innerPanel,
            final Object reservesGrid,
            final float width,
            final float height,
            final List<FleetMemberAPI> deployedMembers,
            final List<FleetMemberAPI> reserveMembers,
            final FleetMemberAPI[] currentFlagshipRef,
            final boolean preBattle,
            final Runnable onOtcChangedCallback) {

        final CustomPanelAPI[] winRef = new CustomPanelAPI[1];
        final ButtonAPI[] closeBtnRef = new ButtonAPI[1];
        final List<ButtonAPI> candidateButtons = new ArrayList<>();
        final List<String> candidateMemberIds = new ArrayList<>();
        final float topBarH = 26f;

        final Runnable pollWindowButtons = new Runnable() {
            @Override
            public void run() {
                if (!otcSelectorWindowOpen) return;
                ButtonAPI closeBtn = closeBtnRef[0];
                if (closeBtn != null && closeBtn.isChecked()) {
                    closeBtn.setChecked(false);
                    playDeploymentButtonSound();
                    closeOtcSelectorWindow(innerPanel, reservesGrid);
                    return;
                }
                for (int i = 0; i < candidateButtons.size(); i++) {
                    ButtonAPI b = candidateButtons.get(i);
                    if (b != null && b.isChecked()) {
                        b.setChecked(false);
                        String targetId = candidateMemberIds.get(i);
                        boolean changed = DivisionOtcCommandManager.selectPlayerOtcByMemberId(
                                engine,
                                playerGroup,
                                deployedMembers,
                                reserveMembers,
                                currentFlagshipRef[0],
                                targetId,
                                preBattle);
                        if (changed) {
                            playDeploymentButtonSound();
                            if (onOtcChangedCallback != null) {
                                onOtcChangedCallback.run();
                            }
                        }
                        closeOtcSelectorWindow(innerPanel, reservesGrid);
                        return;
                    }
                }
            }
        };
        activeOtcSelectorPollHook = pollWindowButtons;

        BaseCustomUIPanelPlugin winPlugin = new BaseCustomUIPanelPlugin() {
            @Override
            public void advance(float amount) {
                pollWindowButtons.run();
            }

            @Override
            public void renderBelow(float alphaMult) {
                CustomPanelAPI self = winRef[0];
                if (self == null || self.getPosition() == null) return;
                PositionAPI pos = self.getPosition();
                float x = pos.getX();
                float y = pos.getY();
                float w = pos.getWidth();
                float h = pos.getHeight();

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                // Window background
                GL11.glColor4f(0.02f, 0.05f, 0.09f, 0.96f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                // Header bar fill
                GL11.glColor4f(0.20f, 0.17f, 0.06f, 0.90f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x + 1f, y + h - topBarH);
                GL11.glVertex2f(x + w - 1f, y + h - topBarH);
                GL11.glVertex2f(x + w - 1f, y + h - 1f);
                GL11.glVertex2f(x + 1f, y + h - 1f);
                GL11.glEnd();

                // Window border
                GL11.glLineWidth(1.5f);
                GL11.glColor4f(0.96f, 0.78f, 0.25f, 0.80f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(x + 1f, y + 1f);
                GL11.glVertex2f(x + w - 1f, y + 1f);
                GL11.glVertex2f(x + w - 1f, y + h - 1f);
                GL11.glVertex2f(x + 1f, y + h - 1f);
                GL11.glEnd();

                GL11.glPopAttrib();
            }
        };

        CustomPanelAPI winPanel = Global.getSettings().createCustom(width, height, winPlugin);
        winRef[0] = winPanel;

        boolean emergencyPending = DivisionOtcCommandManager.isEmergencyPlayerOtcSelectionPending(playerGroup);
        String headerTitleText = emergencyPending
                ? "OTC DESTROYED - SELECT NEW OTC"
                : "SELECT TACTICAL COMMANDER";
        LabelAPI titleLbl = Global.getSettings().createLabel(headerTitleText, Fonts.ORBITRON_12);
        titleLbl.setColor(emergencyPending ? new Color(255, 105, 85, 255) : new Color(255, 215, 80, 255));
        assertCoolTacticalFonts(titleLbl);
        winPanel.addComponent((UIComponentAPI) titleLbl).setSize(265f, 18f).inTL(10f, 4f);

        int cpCost = (preBattle || emergencyPending) ? 0 : DivisionOtcCommandManager.getOtcReassignmentCommandPointCost(engine);
        String costInfo = emergencyPending
                ? "0 CP | Undeployed OTC: -75% Max CP, -50% Regen"
                : (preBattle
                ? "0 CP | Undeployed OTC: -75% Max CP, -50% Regen"
                : String.format(Locale.ROOT, "%d CP | Undeployed OTC: -75%% Max CP, -50%% Regen", cpCost));
        LabelAPI costLbl = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        costLbl.setColor(preBattle || emergencyPending ? new Color(255, 195, 95, 245) : new Color(175, 215, 235, 240));
        assertCoolTacticalFonts(costLbl);
        float costMaxW = Math.max(180f, width - 355f);
        winPanel.addComponent((UIComponentAPI) costLbl).setSize(costMaxW, 16f).inTL(278f, 5f);
        costLbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(costInfo, costLbl, costMaxW - 4f));

        TooltipMakerAPI topBtnBar = winPanel.createUIElement(width, topBarH, false);
        Color base = new Color(245, 200, 65);
        Color dark = new Color(24, 34, 44);
        closeBtnRef[0] = topBtnBar.addButton("Close", "OTC_WINDOW_CLOSE", base, dark,
                Alignment.MID, CutStyle.ALL, 68f, 18f, 0f);
        closeBtnRef[0].getPosition().inTL(width - 76f, 4f);
        topBtnBar.setHeightSoFar(topBarH);
        winPanel.addUIElement(topBtnBar).inTL(0f, 0f);

        float bodyH = Math.max(120f, height - topBarH - 4f);
        TooltipMakerAPI scroller = winPanel.createUIElement(width - 4f, bodyH, true);

        List<DivisionOtcCommandManager.OtcCandidate> allCandidates =
                DivisionOtcCommandManager.collectPlayerOtcCandidates(engine, playerGroup, deployedMembers, reserveMembers);
        String currentOtcId = (currentFlagshipRef[0] != null && currentFlagshipRef[0].getId() != null)
                ? currentFlagshipRef[0].getId()
                : DivisionOtcCommandManager.getPreferredPlayerOtcMemberId(playerGroup);

        // Group candidates by division row (preserving division order, with Unassigned at the end)
        Map<String, List<DivisionOtcCommandManager.OtcCandidate>> rowsByGroupKey = new LinkedHashMap<>();
        for (DivisionOtcCommandManager.OtcCandidate c : allCandidates) {
            if (c == null) continue;
            String key = (c.divisionId != null && !c.divisionId.isEmpty()) ? c.divisionId : "UNASSIGNED";
            List<DivisionOtcCommandManager.OtcCandidate> list = rowsByGroupKey.get(key);
            if (list == null) {
                list = new ArrayList<>();
                rowsByGroupKey.put(key, list);
            }
            list.add(c);
        }

        float rowFullW = width - 16f;
        float headerW = 122f;
        float cardW = 128f;
        float cardH = 126f;
        float gapX = 6f;
        float gapY = 4f;
        float cardsStartX = headerW + 6f;
        int cardsPerRow = Math.max(1, (int) ((rowFullW - cardsStartX + gapX) / (cardW + gapX)));
        float curY = 4f;

        for (Map.Entry<String, List<DivisionOtcCommandManager.OtcCandidate>> entry : rowsByGroupKey.entrySet()) {
            List<DivisionOtcCommandManager.OtcCandidate> groupList = entry.getValue();
            if (groupList == null || groupList.isEmpty()) continue;

            DivisionOtcCommandManager.OtcCandidate first = groupList.get(0);
            int subRows = Math.max(1, (groupList.size() + cardsPerRow - 1) / cardsPerRow);
            float rowBlockH = subRows * cardH + (subRows - 1) * gapY;

            CustomPanelAPI rowPanel = createOtcDivisionRowPanel(
                    first.divisionTitle,
                    first.inDivision,
                    first.divisionFormation,
                    first.isFlagshipDivision,
                    groupList,
                    currentOtcId,
                    rowFullW,
                    rowBlockH,
                    headerW,
                    cardsStartX,
                    cardW,
                    cardH,
                    gapX,
                    gapY,
                    cardsPerRow,
                    candidateButtons,
                    candidateMemberIds);
            scroller.addCustom(rowPanel, 0f).getPosition().inTL(4f, curY);
            curY += rowBlockH + 6f;
        }

        scroller.setHeightSoFar(curY + 4f);
        winPanel.addUIElement(scroller).inTL(2f, topBarH + 2f);
        return winPanel;
    }

    private CustomPanelAPI createOtcDivisionRowPanel(
            final String groupTitle,
            final boolean isDivisionRow,
            final PermanentDivisionManager.DivisionFormation divFm,
            final boolean isFlagshipDiv,
            final List<DivisionOtcCommandManager.OtcCandidate> groupCandidates,
            final String currentOtcId,
            final float rowWidth,
            final float rowHeight,
            final float headerWidth,
            final float cardsStartX,
            final float cardW,
            final float cardH,
            final float gapX,
            final float gapY,
            final int cardsPerRow,
            final List<ButtonAPI> outButtons,
            final List<String> outMemberIds) {

        final CustomPanelAPI[] rowRef = new CustomPanelAPI[1];
        final Color accentColor = isDivisionRow ? DIV_ACCENT_COLOR : UNASSIGNED_ACCENT_COLOR;
        Color titleColor = isDivisionRow ? DIV_HEADER_TEXT_COLOR : UNASSIGNED_TEXT_COLOR;

        LabelAPI line1Label = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        line1Label.setColor(titleColor);
        line1Label.setAlignment(Alignment.LMID);
        assertCoolTacticalFonts(line1Label);

        LabelAPI line2Label = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        line2Label.setColor(titleColor);
        line2Label.setAlignment(Alignment.LMID);
        assertCoolTacticalFonts(line2Label);

        LabelAPI countLabel = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        countLabel.setColor(new Color(175, 215, 235, 235));
        countLabel.setAlignment(Alignment.LMID);
        assertCoolTacticalFonts(countLabel);

        boolean hasFormationBadge = isDivisionRow && divFm != null;
        float line1LeftX = hasFormationBadge ? 20f : 6f;
        float line2LeftX = 6f;
        float maxLine1W = Math.max(42f, headerWidth - line1LeftX - 4f);
        float maxLine2W = Math.max(54f, headerWidth - line2LeftX - 4f);
        String[] lines = splitDeploymentCardTitleLines(groupTitle, line1Label, maxLine1W, maxLine2W);
        final boolean isTwoLineTitle = (lines.length >= 2 && lines[1] != null && !lines[1].isEmpty());
        final float iconCenterTopY = isTwoLineTitle ? 12.5f : 18.5f;

        BaseCustomUIPanelPlugin rowPlugin = new BaseCustomUIPanelPlugin() {
            @Override
            public void renderBelow(float alphaMult) {
                CustomPanelAPI self = rowRef[0];
                if (self == null || self.getPosition() == null) return;
                PositionAPI pos = self.getPosition();
                float x = pos.getX();
                float y = pos.getY();
                float h = pos.getHeight();
                float hw = headerWidth - 2f;

                float r = accentColor.getRed() / 255f;
                float g = accentColor.getGreen() / 255f;
                float b = accentColor.getBlue() / 255f;

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                // Left division header card backdrop
                GL11.glColor4f(0.03f, 0.06f, 0.10f, 0.94f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + hw, y);
                GL11.glVertex2f(x + hw, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                // Left vertical accent stripe
                GL11.glColor4f(r, g, b, 0.92f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + 3f, y);
                GL11.glVertex2f(x + 3f, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                // Enclosing division row frame
                GL11.glLineWidth(1.0f);
                GL11.glColor4f(r, g, b, 0.28f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + rowWidth - 2f, y);
                GL11.glVertex2f(x + rowWidth - 2f, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                GL11.glPopAttrib();

                if (isDivisionRow && divFm != null) {
                    float iconCx = x + 11.5f;
                    float iconCy = y + h - iconCenterTopY;
                    FleetDoctrineDialog.renderFormationIcon(
                            divFm,
                            iconCx,
                            iconCy,
                            12f,
                            Color.WHITE,
                            0.95f * alphaMult,
                            isFlagshipDiv);
                }
            }
        };

        CustomPanelAPI rowPanel = Global.getSettings().createCustom(rowWidth, rowHeight, rowPlugin);
        rowRef[0] = rowPanel;

        PositionAPI p1 = rowPanel.addComponent((UIComponentAPI) line1Label);
        PositionAPI p2 = rowPanel.addComponent((UIComponentAPI) line2Label);
        PositionAPI pc = rowPanel.addComponent((UIComponentAPI) countLabel);
        float boxW1 = Math.max(20f, headerWidth - line1LeftX - 3f);
        float boxW2 = Math.max(20f, headerWidth - line2LeftX - 3f);
        if (!isTwoLineTitle) {
            String l1 = lines.length > 0 ? lines[0] : "";
            p1.setSize(boxW1, 14f).inTL(line1LeftX, 11f);
            line1Label.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(l1, line1Label, boxW1 - 2f));
            p2.setSize(boxW2, 0f).inTL(line2LeftX, 0f);
            line2Label.setText("");
        } else {
            p1.setSize(boxW1, 13f).inTL(line1LeftX, 5f);
            line1Label.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(lines[0], line1Label, boxW1 - 2f));
            p2.setSize(boxW2, 13f).inTL(line2LeftX, 19f);
            line2Label.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(lines[1], line2Label, boxW2 - 2f));
        }
        String countTxt = groupCandidates.size() == 1 ? "1 Commander" : (groupCandidates.size() + " Commanders");
        pc.setSize(boxW2, 14f).inTL(6f, 38f);
        countLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(countTxt, countLabel, boxW2 - 2f));

        for (int i = 0; i < groupCandidates.size(); i++) {
            DivisionOtcCommandManager.OtcCandidate cand = groupCandidates.get(i);
            int col = i % cardsPerRow;
            int subRow = i / cardsPerRow;
            float cx = cardsStartX + col * (cardW + gapX);
            float cy = subRow * (cardH + gapY);
            boolean isCurrent = (currentOtcId != null && currentOtcId.equals(cand.memberId));
            CustomPanelAPI card = createOtcCommanderPortraitCard(cand, isCurrent, cardW, cardH, outButtons, outMemberIds);
            rowPanel.addComponent(card).setSize(cardW, cardH).inTL(cx, cy);
        }

        return rowPanel;
    }

    private CustomPanelAPI createOtcCommanderPortraitCard(
            final DivisionOtcCommandManager.OtcCandidate cand,
            final boolean isCurrentOtc,
            final float cardW,
            final float cardH,
            final List<ButtonAPI> outButtons,
            final List<String> outMemberIds) {

        final CustomPanelAPI[] cardRef = new CustomPanelAPI[1];
        final float portraitH = 58f;
        final float portraitW = cand.alphaCore
                ? Math.min(104f, portraitH * ALPHA_CORE_PORTRAIT_STRETCH_ASPECT)
                : portraitH;
        final float portraitTopY = 4f;

        BaseCustomUIPanelPlugin cardPlugin = new BaseCustomUIPanelPlugin() {
            @Override
            public void renderBelow(float alphaMult) {
                CustomPanelAPI self = cardRef[0];
                if (self == null || self.getPosition() == null) return;
                PositionAPI pos = self.getPosition();
                float x = pos.getX() + 1f;
                float y = pos.getY() + 1f;
                float w = pos.getWidth() - 2f;
                float h = pos.getHeight() - 2f;

                Color borderCol = isCurrentOtc
                        ? new Color(255, 215, 55)
                        : (cand.alphaCore ? new Color(80, 225, 255)
                        : (cand.divisionLeader ? new Color(85, 220, 135) : new Color(135, 165, 195)));
                float br = borderCol.getRed() / 255f;
                float bg = borderCol.getGreen() / 255f;
                float bb = borderCol.getBlue() / 255f;

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                // Card background
                GL11.glColor4f(isCurrentOtc ? 0.08f : 0.02f, isCurrentOtc ? 0.08f : 0.04f, 0.08f, 0.94f * alphaMult);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();

                // Card border
                GL11.glLineWidth(isCurrentOtc ? 2.0f : 1.2f);
                GL11.glColor4f(br, bg, bb, (isCurrentOtc ? 0.95f : 0.65f) * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h);
                GL11.glVertex2f(x, y + h);
                GL11.glEnd();
                GL11.glPopAttrib();

                // Render Medium/Large Commander Portrait
                float pCx = pos.getX() + pos.getWidth() * 0.5f;
                float pCy = pos.getY() + pos.getHeight() - portraitTopY - portraitH * 0.5f;
                try {
                    SpriteAPI sp = Global.getSettings().getSprite(cand.portraitSpritePath);
                    if (sp != null) {
                        float prevW = sp.getWidth();
                        float prevH = sp.getHeight();
                        float prevCx = sp.getCenterX();
                        float prevCy = sp.getCenterY();
                        Color prevColor = sp.getColor();
                        float prevAlpha = sp.getAlphaMult();
                        sp.setSize(portraitW, portraitH);
                        sp.setCenter(portraitW * 0.5f, portraitH * 0.5f);
                        sp.setColor(Color.WHITE);
                        sp.setAlphaMult(0.96f * alphaMult);
                        sp.renderAtCenter(pCx, pCy);
                        sp.setSize(prevW, prevH);
                        sp.setCenter(prevCx, prevCy);
                        if (prevColor != null) sp.setColor(prevColor);
                        sp.setAlphaMult(prevAlpha);
                    }
                } catch (Throwable ignored) {
                }

                // Portrait frame
                float px0 = pCx - portraitW * 0.5f;
                float py0 = pCy - portraitH * 0.5f;
                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glLineWidth(1.5f);
                GL11.glColor4f(br, bg, bb, 0.90f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(px0, py0);
                GL11.glVertex2f(px0 + portraitW, py0);
                GL11.glVertex2f(px0 + portraitW, py0 + portraitH);
                GL11.glVertex2f(px0, py0 + portraitH);
                GL11.glEnd();
                GL11.glPopAttrib();

                // Render Gold/Silver Star badge on upper-left of portrait if Fleet Flagship or Division Leader
                boolean isFleetFlag = (cand.member != null && cand.member.isFlagship()) || isCurrentOtc;
                if (isFleetFlag || cand.divisionLeader) {
                    try {
                        String starKey = isFleetFlag ? "star_gold" : "star_silver";
                        SpriteAPI starSp = Global.getSettings().getSprite("division_ai", starKey);
                        if (starSp != null) {
                            float sw = starSp.getWidth();
                            float sh = starSp.getHeight();
                            starSp.setSize(14f, 14f);
                            starSp.setCenter(7f, 7f);
                            starSp.setAlphaMult(0.95f * alphaMult);
                            starSp.renderAtCenter(px0 + 8f, py0 + portraitH - 8f);
                            starSp.setSize(sw, sh);
                            starSp.setCenter(sw * 0.5f, sh * 0.5f);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        };

        CustomPanelAPI card = Global.getSettings().createCustom(cardW, cardH, cardPlugin);
        cardRef[0] = card;

        LabelAPI cmdrLbl = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        cmdrLbl.setColor(cand.alphaCore ? new Color(130, 238, 255, 255) : new Color(235, 245, 255, 255));
        cmdrLbl.setAlignment(Alignment.MID);
        assertCoolTacticalFonts(cmdrLbl);

        LabelAPI persLbl = Global.getSettings().createLabel("", "graphics/fonts/victor14.fnt");
        Color persColor;
        if (cand.alphaCore) {
            persColor = new Color(110, 235, 255, 255);
        } else if ("reckless".equalsIgnoreCase(cand.personalityId)) {
            persColor = new Color(255, 145, 110, 255);
        } else if ("aggressive".equalsIgnoreCase(cand.personalityId)) {
            persColor = new Color(255, 205, 95, 255);
        } else if ("cautious".equalsIgnoreCase(cand.personalityId) || "timid".equalsIgnoreCase(cand.personalityId)) {
            persColor = new Color(150, 230, 185, 255);
        } else {
            persColor = new Color(175, 215, 245, 255);
        }
        persLbl.setColor(persColor);
        persLbl.setAlignment(Alignment.MID);
        assertCoolTacticalFonts(persLbl);

        LabelAPI shipLbl = Global.getSettings().createLabel("", "graphics/fonts/victor14.fnt");
        shipLbl.setColor(new Color(180, 210, 230, 240));
        shipLbl.setAlignment(Alignment.MID);
        assertCoolTacticalFonts(shipLbl);

        float textBoxW = cardW - 6f;
        card.addComponent((UIComponentAPI) cmdrLbl).setSize(textBoxW, 13f).inTL(3f, 64f);
        cmdrLbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                cand.alphaCore ? "ALPHA CORE" : cand.commanderName, cmdrLbl, textBoxW - 6f));

        String persText = (cand.personalityDisplay != null && !cand.personalityDisplay.isEmpty())
                ? cand.personalityDisplay
                : "Steady";
        card.addComponent((UIComponentAPI) persLbl).setSize(textBoxW, 12f).inTL(3f, 77f);
        persLbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                persText, persLbl, textBoxW - 6f));

        card.addComponent((UIComponentAPI) shipLbl).setSize(textBoxW, 12f).inTL(3f, 90f);
        shipLbl.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                cand.shipName, shipLbl, textBoxW - 6f));

        TooltipMakerAPI btnHolder = card.createUIElement(cardW, cardH, false);
        Color btnBase = isCurrentOtc
                ? new Color(255, 215, 70)
                : (cand.alphaCore ? new Color(90, 225, 255) : new Color(110, 225, 145));
        Color btnDark = new Color(20, 34, 44);
        String btnText = isCurrentOtc ? "Active OTC" : "Select";
        ButtonAPI selectBtn = btnHolder.addButton(
                btnText,
                "OTC_SELECT:" + cand.memberId,
                btnBase,
                btnDark,
                Alignment.MID,
                CutStyle.ALL,
                cardW - 10f,
                16f,
                0f);
        selectBtn.getPosition().inTL(5f, 105f);
        selectBtn.setEnabled(!isCurrentOtc);
        if (isCurrentOtc) {
            selectBtn.highlight();
        }
        btnHolder.setHeightSoFar(cardH);
        card.addUIElement(btnHolder).inTL(0f, 0f);

        if (outButtons != null && outMemberIds != null) {
            outButtons.add(selectBtn);
            outMemberIds.add(cand.memberId);
        }

        return card;
    }

    private static void renderStatusMeterBar(
            float x,
            float y,
            float w,
            float h,
            float fillRatio,
            Color barColor,
            float alphaMult) {
        float r = barColor.getRed() / 255f;
        float g = barColor.getGreen() / 255f;
        float b = barColor.getBlue() / 255f;
        GL11.glColor4f(0.06f, 0.10f, 0.15f, 0.90f * alphaMult);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
        float fillW = Math.max(0f, Math.min(w, w * fillRatio));
        if (fillW > 0.5f) {
            GL11.glColor4f(r, g, b, 0.88f * alphaMult);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x + 1f, y + 1f);
            GL11.glVertex2f(x + fillW, y + 1f);
            GL11.glVertex2f(x + fillW, y + h - 1f);
            GL11.glVertex2f(x + 1f, y + h - 1f);
            GL11.glEnd();
        }
        GL11.glLineWidth(1.0f);
        GL11.glColor4f(r, g, b, 0.48f * alphaMult);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    public static void selectFleetFormation(BattleGroupState playerGroup, int direction) {
        PermanentDivisionManager.FleetDoctrineConfig doc = PermanentDivisionManager.getFleetDoctrine();
        PermanentDivisionManager.FleetWideFormation[] values = PermanentDivisionManager.FleetWideFormation.values();
        PermanentDivisionManager.FleetWideFormation currentFm = (doc != null && doc.getFleetWideFormation() != null)
                ? doc.getFleetWideFormation()
                : (playerGroup != null && playerGroup.fleetWideFormation != null
                ? playerGroup.fleetWideFormation
                : PermanentDivisionManager.FleetWideFormation.DOCTRINAL_AUTO);
        int current = currentFm.ordinal();
        int step = direction < 0 ? -1 : 1;
        PermanentDivisionManager.FleetWideFormation next = values[(current + step + values.length) % values.length];
        PermanentDivisionManager.updateFleetWideFormation(next);
        PermanentDivisionManager.updateDeployInFleetFormation(true);
        if (playerGroup != null) {
            playerGroup.fleetWideFormation = next;
            playerGroup.activeFleetWideFormation = next;
            playerGroup.deployInFleetFormation = (next != PermanentDivisionManager.FleetWideFormation.NONE);
        }
        autoHarmonizePermanentDivisionsToFleetFormation(next);
        playDeploymentButtonSound();
    }

    public static void selectOtcAuthority(BattleGroupState playerGroup, int direction) {
        PermanentDivisionManager.FleetDoctrineConfig doc = PermanentDivisionManager.getFleetDoctrine();
        PermanentDivisionManager.OtcAuthority[] values = PermanentDivisionManager.OtcAuthority.values();
        PermanentDivisionManager.OtcAuthority currentAuth = (doc != null && doc.getOtcAuthority() != null)
                ? doc.getOtcAuthority()
                : (playerGroup != null && playerGroup.authority != null
                ? playerGroup.authority
                : PermanentDivisionManager.OtcAuthority.COMMAND_BY_DIRECTION);
        int current = currentAuth.ordinal();
        int step = direction < 0 ? -1 : 1;
        PermanentDivisionManager.OtcAuthority next = values[(current + step + values.length) % values.length];
        setOtcAuthority(playerGroup, next);
    }

    public static void setOtcAuthority(BattleGroupState playerGroup, PermanentDivisionManager.OtcAuthority next) {
        if (next == null) return;
        PermanentDivisionManager.updateOtcAuthority(next);
        if (playerGroup != null) {
            playerGroup.authority = next;
        }
        playDeploymentButtonSound();
    }

    private static void playDeploymentButtonSound() {
        try {
            Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Wraps a long OTC ship name or "Flagship: <ShipName> (<HullClass>)" line into up to 2 lines within
     * {@code maxLine1W} and {@code maxLine2W}, preferring to split before the hull class {@code " ("} or at
     * the cleanest word boundary before falling back to single-line truncation.
     */
    public static String[] wrapOtcTextIntoTwoLines(
            String rawText,
            LabelAPI measurer,
            float maxLine1W,
            float maxLine2W) {
        if (rawText == null) return new String[]{""};
        String norm = rawText.trim().replaceAll("\\s+", " ");
        if (norm.isEmpty()) return new String[]{""};
        if (CampaignFleetTooltipOrganizer.measureLineWidth(norm, measurer) <= maxLine1W) {
            return new String[]{norm};
        }
        int pipeIdx = norm.indexOf(" | ");
        if (pipeIdx > 0) {
            String beforePipe = norm.substring(0, pipeIdx).trim();
            String afterPipe = norm.substring(pipeIdx + 3).trim();
            if (CampaignFleetTooltipOrganizer.measureLineWidth(beforePipe, measurer) <= maxLine1W) {
                return new String[]{
                        beforePipe,
                        CampaignFleetTooltipOrganizer.fitSingleLineToWidth(afterPipe, measurer, maxLine2W)
                };
            }
        }
        int parenIdx = norm.lastIndexOf(" (");
        if (parenIdx > 0) {
            String beforeParen = norm.substring(0, parenIdx).trim();
            String parenPart = norm.substring(parenIdx + 1).trim();
            if (CampaignFleetTooltipOrganizer.measureLineWidth(beforeParen, measurer) <= maxLine1W) {
                return new String[]{
                        beforeParen,
                        CampaignFleetTooltipOrganizer.fitSingleLineToWidth(parenPart, measurer, maxLine2W)
                };
            }
        }
        return splitDeploymentCardTitleLines(norm, measurer, maxLine1W, maxLine2W);
    }

    public static String getFleetFormationSpritePath(PermanentDivisionManager.FleetWideFormation formation) {
        return getFleetFormationSpritePath(formation, false);
    }

    public static String getFleetFormationWheelSpritePath(PermanentDivisionManager.FleetWideFormation formation) {
        return getFleetFormationSpritePath(formation, true);
    }

    public static String getFleetFormationSpritePath(
            PermanentDivisionManager.FleetWideFormation formation,
            boolean wheelOnly) {
        if (formation == null) return null;
        String icon;
        switch (formation) {
            case GRAND_LINE_ABREAST:
                icon = "grand_line";
                break;
            case GRAND_COLUMN_IN_DEPTH:
                icon = "grand_column";
                break;
            case GRAND_WEDGE_SPEARHEAD:
                icon = "grand_wedge";
                break;
            case REVERSE_WEDGE:
                icon = "reverse_wedge";
                break;
            case REFUSED_FLANK_OBLIQUE:
                icon = "refused_right";
                break;
            case REFUSED_FLANK_LEFT:
                icon = "refused_left";
                break;
            case CRESCENT_HORNS:
                icon = "crescent";
                break;
            case REVERSE_CRESCENT:
                icon = "reverse_crescent";
                break;
            case CHECKERBOARD_QUINCUNX:
                icon = "quincunx";
                break;
            case FLEET_PROTECTIVE_BASTION:
                icon = "bastion";
                break;
            case NONE:
                icon = "none";
                break;
            case DOCTRINAL_AUTO:
            default:
                icon = "auto";
                break;
        }
        String suffix = wheelOnly ? "_wheel.png" : ".png";
        return "graphics/icons/fleet_formations/fleet_formation_" + icon + suffix;
    }

    public static void renderFleetFormationIcon(
            PermanentDivisionManager.FleetWideFormation formation,
            float centerX,
            float centerY,
            float size,
            float alphaMult) {
        renderFleetFormationIcon(formation, centerX, centerY, size, alphaMult, false);
    }

    public static void renderFleetFormationIcon(
            PermanentDivisionManager.FleetWideFormation formation,
            float centerX,
            float centerY,
            float size,
            float alphaMult,
            boolean wheelOnly) {
        String path = getFleetFormationSpritePath(formation, wheelOnly);
        if (path == null) return;
        SpriteAPI sprite = FleetDoctrineDialog.loadFormationSpriteSafe(null, path);
        if (sprite == null && wheelOnly) {
            sprite = FleetDoctrineDialog.loadFormationSpriteSafe(null, getFleetFormationSpritePath(formation, false));
        }
        if (sprite == null) return;
        float prevW = sprite.getWidth();
        float prevH = sprite.getHeight();
        float prevCx = sprite.getCenterX();
        float prevCy = sprite.getCenterY();
        float prevAlpha = sprite.getAlphaMult();
        Color prevColor = sprite.getColor();
        sprite.setSize(size, size);
        sprite.setCenter(size * 0.5f, size * 0.5f);
        sprite.setColor(Color.WHITE);
        sprite.setAlphaMult(alphaMult);
        sprite.renderAtCenter(Math.round(centerX), Math.round(centerY));
        sprite.setSize(prevW, prevH);
        sprite.setCenter(prevCx, prevCy);
        sprite.setAlphaMult(prevAlpha);
        if (prevColor != null) sprite.setColor(prevColor);
    }

    /**
     * Automatically harmonizes the player's PermanentDivisionSpecs (and any active combat divisions) with the
     * selected Fleet-Wide Formation when the player cycles Fleet Formation in the Deployment Screen.
     */
    public static void autoHarmonizePermanentDivisionsToFleetFormation(
            PermanentDivisionManager.FleetWideFormation fleetFm) {
        if (fleetFm == null) return;
        List<PermanentDivisionManager.PermanentDivisionSpec> specs =
                PermanentDivisionManager.getNonEmptyPermanentDivisions();
        int n = specs.size();
        for (int i = 0; i < n; i++) {
            PermanentDivisionManager.PermanentDivisionSpec spec = specs.get(i);
            if (spec == null) continue;
            int wingSide = (i == 0) ? 0 : ((i % 2 == 1) ? -1 : 1);
            int rankIdx = (i == 0) ? 0 : ((i + 1) / 2);
            switch (fleetFm) {
                case GRAND_WEDGE_SPEARHEAD:
                case REFUSED_FLANK_OBLIQUE:
                    spec.customFormation = (i == 0)
                            ? PermanentDivisionManager.DivisionFormation.FORWARD_WEDGE
                            : (wingSide < 0
                            ? PermanentDivisionManager.DivisionFormation.ECHELON_LEFT
                            : PermanentDivisionManager.DivisionFormation.ECHELON_RIGHT);
                    break;
                case REFUSED_FLANK_LEFT:
                    spec.customFormation = (i == 0)
                            ? PermanentDivisionManager.DivisionFormation.FORWARD_WEDGE
                            : (wingSide > 0
                            ? PermanentDivisionManager.DivisionFormation.ECHELON_RIGHT
                            : PermanentDivisionManager.DivisionFormation.ECHELON_LEFT);
                    break;
                case REVERSE_WEDGE:
                    spec.customFormation = (i == 0)
                            ? PermanentDivisionManager.DivisionFormation.REVERSE_WEDGE
                            : (wingSide < 0
                            ? PermanentDivisionManager.DivisionFormation.ECHELON_RIGHT
                            : PermanentDivisionManager.DivisionFormation.ECHELON_LEFT);
                    break;
                case CRESCENT_HORNS:
                    spec.customFormation = (i == 0)
                            ? PermanentDivisionManager.DivisionFormation.REVERSE_SEMICIRCLE
                            : (wingSide < 0
                            ? PermanentDivisionManager.DivisionFormation.ECHELON_RIGHT
                            : PermanentDivisionManager.DivisionFormation.ECHELON_LEFT);
                    break;
                case REVERSE_CRESCENT:
                    spec.customFormation = (i == 0)
                            ? PermanentDivisionManager.DivisionFormation.FORWARD_SEMICIRCLE
                            : (wingSide < 0
                            ? PermanentDivisionManager.DivisionFormation.ECHELON_LEFT
                            : PermanentDivisionManager.DivisionFormation.ECHELON_RIGHT);
                    break;
                case CHECKERBOARD_QUINCUNX:
                    spec.customFormation = (rankIdx % 2 == 0)
                            ? PermanentDivisionManager.DivisionFormation.FORWARD_WEDGE
                            : PermanentDivisionManager.DivisionFormation.LINE_ABREAST;
                    break;
                case FLEET_PROTECTIVE_BASTION:
                    spec.customFormation = (i == 0)
                            ? PermanentDivisionManager.DivisionFormation.FORWARD_SEMICIRCLE
                            : (wingSide < 0
                            ? PermanentDivisionManager.DivisionFormation.ECHELON_LEFT
                            : PermanentDivisionManager.DivisionFormation.ECHELON_RIGHT);
                    break;
                case GRAND_COLUMN_IN_DEPTH:
                    spec.customFormation = (i == 0)
                            ? PermanentDivisionManager.DivisionFormation.FORWARD_WEDGE
                            : PermanentDivisionManager.DivisionFormation.LINE_AHEAD;
                    break;
                case NONE:
                    spec.customFormation = PermanentDivisionManager.DivisionFormation.NONE;
                    break;
                case GRAND_LINE_ABREAST:
                case DOCTRINAL_AUTO:
                default:
                    spec.customFormation = null;
                    break;
            }
        }
    }
}
