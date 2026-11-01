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

    public static final int ENLARGED_TOTAL_COLS = 14;
    public static final int LABEL_COLS = 2;
    public static final int SHIPS_PER_ROW = ENLARGED_TOTAL_COLS - LABEL_COLS; // 12 ships per row
    public static final int TARGET_RESERVE_VISIBLE_ROWS = 7;
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
    private String lastReservesSignature = "";
    private final Map<String, MemberIconEntry> cachedEntriesForActiveFdd = new LinkedHashMap<>();
    private CustomPanelAPI activeFleetCommandPanel = null;
    private IndexedMemberList lastIndexedReserveMembers = new IndexedMemberList();

    public IndexedMemberList getLastIndexedReserveMembers() {
        return lastIndexedReserveMembers;
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
        if (!"com.fs.starfarer.combat.CombatState".equals(ui.getClass().getName())) {
            return;
        }

        ensureHandles(ui);
        if (!handlesResolved) return;

        try {
            boolean showingDeploy = (boolean) isShowingDeploymentDialogHandle.invoke(ui);
            if (!showingDeploy) {
                lastFddInstance = null;
                lastReservesSignature = "";
                cachedEntriesForActiveFdd.clear();
                activeFleetCommandPanel = null;
                return;
            }

            UIPanelAPI widgetPanel = (UIPanelAPI) getWidgetPanelHandle.invoke(ui);
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
                activeFleetCommandPanel = null;
                lastReservesSignature = "";
            }

            UIPanelAPI innerPanel = (UIPanelAPI) fddGetInnerPanelHandle.invoke(activeFdd);
            if (innerPanel == null) return;

            Object[] grids = findDeployedAndReservesGrids(innerPanel);
            Object deployedGrid = grids[0];
            Object reservesGrid = grids[1];
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
        if (innerPos == null || resPos == null) return maxColsForScreen;

        float oldInnerW = innerPos.getWidth();
        float oldInnerH = innerPos.getHeight();
        float oldResH = resPos.getHeight();

        float overheadH = Math.max(260f, oldInnerH - oldResH);
        int maxRowsForScreen = Math.max(4, Math.min(TARGET_RESERVE_VISIBLE_ROWS, (int) ((screenH - overheadH - 80f) / itemH)));
        float targetResH = maxRowsForScreen * itemH;

        if (Math.abs(oldInnerW - targetInnerW) > 4f || Math.abs(oldResH - targetResH) > 4f) {
            float targetInnerH = oldInnerH + (targetResH - oldResH);

            fddSizeToInnerHandle.invoke(fdd, targetInnerW, targetInnerH);
            if (fdd instanceof UIComponentAPI && ((UIComponentAPI) fdd).getPosition() != null) {
                ((UIComponentAPI) fdd).getPosition().inMid();
            }

            if (deployedGrid instanceof UIComponentAPI) {
                PositionAPI depPos = ((UIComponentAPI) deployedGrid).getPosition();
                if (depPos != null) {
                    depPos.setSize(targetGridW, depPos.getHeight());
                    if (panelSetSizeHandle != null) {
                        try {
                            panelSetSizeHandle.invoke(deployedGrid, targetGridW, depPos.getHeight());
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
                if (isDivisionRow && divFm != null) {
                    float iconSize = 12f;
                    float iconCx = pos.getX() + 11.5f;
                    float iconCy = pos.getY() + pos.getHeight() - iconCenterTopY;
                    FleetDoctrineDialog.renderFormationIcon(
                            divFm,
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

        String[] words = norm.split(" ");
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

    private FleetMemberAPI resolvePlayerFlagshipMember(
            List<FleetMemberAPI> deployedMembers,
            List<FleetMemberAPI> reserveMembers) {
        List<FleetMemberAPI> all = new ArrayList<>();
        if (deployedMembers != null) all.addAll(deployedMembers);
        if (reserveMembers != null) all.addAll(reserveMembers);
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
            final float width,
            final float height,
            final List<FleetMemberAPI> deployedMembers,
            final List<FleetMemberAPI> reserveMembers) {

        final boolean preBattle = deployedMembers == null || deployedMembers.isEmpty();
        final FleetMemberAPI flagshipMember = resolvePlayerFlagshipMember(deployedMembers, reserveMembers);
        final boolean isAlphaCore = isFlagshipAlphaCoreEquipped(engine, flagshipMember, playerGroup);

        PersonAPI resolvedPerson = (flagshipMember != null && flagshipMember.getCaptain() != null
                && !flagshipMember.getCaptain().isDefault())
                ? flagshipMember.getCaptain()
                : null;
        if (resolvedPerson == null) {
            try {
                if (Global.getSector() != null && Global.getSector().getPlayerPerson() != null) {
                    resolvedPerson = Global.getSector().getPlayerPerson();
                }
            } catch (Throwable ignored) {
            }
        }

        String rawCmdrName = (resolvedPerson != null && resolvedPerson.getNameString() != null
                && !resolvedPerson.getNameString().trim().isEmpty())
                ? resolvedPerson.getNameString().trim()
                : (playerGroup != null && playerGroup.otc != null && playerGroup.otc.commanderName != null
                ? playerGroup.otc.commanderName
                : "Fleet Commander");
        final String commanderName = rawCmdrName;
        final String flagshipName = (flagshipMember != null && flagshipMember.getShipName() != null
                && !flagshipMember.getShipName().trim().isEmpty())
                ? flagshipMember.getShipName().trim()
                : "FLAGSHIP";
        final String hullClassName = (flagshipMember != null && flagshipMember.getHullSpec() != null
                && flagshipMember.getHullSpec().getHullNameWithDashClass() != null)
                ? flagshipMember.getHullSpec().getHullNameWithDashClass()
                : "Command Hull";

        String candidatePortrait = (resolvedPerson != null && resolvedPerson.getPortraitSprite() != null
                && !resolvedPerson.getPortraitSprite().trim().isEmpty())
                ? resolvedPerson.getPortraitSprite().trim()
                : ALPHA_CORE_PORTRAIT_SPRITE;
        if (isAlphaCore) candidatePortrait = ALPHA_CORE_PORTRAIT_SPRITE;
        final String portraitSpritePath = candidatePortrait;

        final float topBarH = 20f;
        final float portraitH = Math.max(82f, Math.min(96f, height - topBarH - 22f));
        final float portraitW = isAlphaCore
                ? Math.min(156f, portraitH * ALPHA_CORE_PORTRAIT_STRETCH_ASPECT)
                : portraitH;
        final float portraitX = 12f;
        final float leftSectionW = Math.round(width * 0.50f);
        final float rightSectionX = leftSectionW + 8f;
        final float rightSectionW = Math.max(260f, width - rightSectionX - 6f);
        final float textLeftX = portraitX + portraitW + 12f;
        final float titleTopY = topBarH + 10f;
        final float maxLeftTextW = Math.max(160f, leftSectionW - textLeftX - 6f);
        final float[] masterBasePos = new float[]{textLeftX, titleTopY};
        final PositionAPI[] masterPosRef = new PositionAPI[1];
        final PositionAPI[] masterGhostPosRef = new PositionAPI[1];
        final CustomPanelAPI[] panelRef = new CustomPanelAPI[1];
        final ButtonAPI[] prevButtonRef = new ButtonAPI[1];
        final ButtonAPI[] nextButtonRef = new ButtonAPI[1];
        final ButtonAPI[] entryButtonRef = new ButtonAPI[1];
        final float[] liveEngagementLevel = new float[]{0f};
        final float[] liveCommitmentRatio = new float[]{0f};
        final float[] liveCohesionRatio = new float[]{1f};

        final LabelAPI headerBannerLabel = Global.getSettings().createLabel("FLEET COMMAND", Fonts.ORBITRON_12);
        headerBannerLabel.setColor(isAlphaCore ? new Color(110, 235, 255, 255) : new Color(255, 215, 80, 255));
        final LabelAPI otcStandardTitleLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_20AA);
        otcStandardTitleLabel.setColor(new Color(255, 222, 90, 255));
        final LabelAPI cogPrefixLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        cogPrefixLabel.setColor(new Color(120, 235, 255, 255));
        final LabelAPI cogMasterGhostLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        cogMasterGhostLabel.setColor(new Color(255, 65, 95, 170));
        final LabelAPI cogMasterLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_12);
        cogMasterLabel.setColor(new Color(235, 252, 255, 255));
        final LabelAPI cogSuffixLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_20AA);
        cogSuffixLabel.setColor(new Color(150, 238, 255, 255));
        final LabelAPI flagshipLabel = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        flagshipLabel.setColor(new Color(215, 230, 242, 245));
        final LabelAPI profileLabel = Global.getSettings().createLabel("", Fonts.GROUP_NUM_FONT);
        profileLabel.setColor(new Color(175, 215, 235, 240));
        final LabelAPI selectorHeaderLabel = Global.getSettings().createLabel(
                preBattle ? "FLEET FORMATION" : "FLEET STATUS", Fonts.ORBITRON_12);
        selectorHeaderLabel.setColor(new Color(255, 215, 80, 255));
        selectorHeaderLabel.setAlignment(Alignment.MID);
        final LabelAPI formationNameLabel = Global.getSettings().createLabel("", Fonts.ORBITRON_20AA);
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
        assertCoolTacticalFonts(cogPrefixLabel);
        assertCoolTacticalFonts(cogMasterGhostLabel);
        assertCoolTacticalFonts(cogMasterLabel);
        assertCoolTacticalFonts(cogSuffixLabel);
        assertCoolTacticalFonts(flagshipLabel);
        assertCoolTacticalFonts(profileLabel);
        assertCoolTacticalFonts(selectorHeaderLabel);
        assertCoolTacticalFonts(formationNameLabel);
        assertCoolTacticalFonts(engagementLabel);
        assertCoolTacticalFonts(commitmentLabel);
        assertCoolTacticalFonts(cohesionLabel);
        assertCoolTacticalFonts(phaseLabel);

        BaseCustomUIPanelPlugin fcPlugin = new BaseCustomUIPanelPlugin() {
            private void updateSelectorText() {
                PermanentDivisionManager.FleetDoctrineConfig doc = PermanentDivisionManager.getFleetDoctrine();
                PermanentDivisionManager.FleetWideFormation fw = (playerGroup != null
                        && playerGroup.activeFleetWideFormation != null)
                        ? playerGroup.activeFleetWideFormation
                        : doc.getFleetWideFormation();
                formationNameLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                        fw.displayName, formationNameLabel,
                        preBattle ? rightSectionW - 92f : rightSectionW - 78f));
                ButtonAPI entry = entryButtonRef[0];
                if (preBattle && entry != null) {
                    entry.setText("Formation entry: " + (doc.isDeployInFleetFormation() ? "On" : "Off"));
                    if (doc.isDeployInFleetFormation()) entry.highlight();
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
                        engagementLabel, rightSectionW - 20f));
                commitmentLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                        String.format(Locale.ROOT, "COMMITTED %.0f%% (%d / %.0f DP)",
                                liveCommitmentRatio[0] * 100f, depCount, depDp),
                        commitmentLabel, rightSectionW - 20f));
                cohesionLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                        String.format(Locale.ROOT, "COHESION %.0f%% | Divisions %d/%d",
                                coh * 100f, activeDivCount, Math.max(activeDivCount, totalDivSpecs)),
                        cohesionLabel, rightSectionW - 20f));
                phaseLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                        "PHASE: " + phaseName, phaseLabel, rightSectionW - 20f));
            }

            private void pollButtons() {
                if (!preBattle) return;
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
                    boolean enabled = !doc.isDeployInFleetFormation();
                    PermanentDivisionManager.updateDeployInFleetFormation(enabled);
                    if (playerGroup != null) playerGroup.deployInFleetFormation = enabled;
                    playDeploymentButtonSound();
                }
                updateSelectorText();
            }

            @Override
            public void advance(float amount) {
                if (isAlphaCore && masterPosRef[0] != null) {
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

            @Override
            public void renderBelow(float alphaMult) {
                CustomPanelAPI self = panelRef[0];
                if (self == null || self.getPosition() == null) return;
                PositionAPI pos = self.getPosition();
                float x = pos.getX();
                float y = pos.getY();
                float w = pos.getWidth();
                float h = pos.getHeight();
                Color themeAccent = isAlphaCore ? new Color(75, 220, 255) : new Color(245, 200, 65);
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
                if (!preBattle) {
                    float barX = rx + 10f;
                    float barW = rw - 20f;
                    renderStatusMeterBar(barX, y + h - topBarH - 62f, barW, 7f,
                            liveEngagementLevel[0], new Color(90, 215, 255), alphaMult);
                    renderStatusMeterBar(barX, y + h - topBarH - 90f, barW, 7f,
                            liveCommitmentRatio[0], new Color(255, 205, 65), alphaMult);
                    renderStatusMeterBar(barX, y + h - topBarH - 118f, barW, 7f,
                            liveCohesionRatio[0], new Color(90, 235, 150), alphaMult);
                }
                GL11.glPopAttrib();

                float pCenterX = x + portraitX + portraitW * 0.5f;
                float pCenterY = y + (h - topBarH) * 0.5f;
                try {
                    SpriteAPI portraitSprite = Global.getSettings().getSprite(portraitSpritePath);
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
                if (isAlphaCore) {
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

                PermanentDivisionManager.FleetWideFormation fw = (playerGroup != null
                        && playerGroup.activeFleetWideFormation != null)
                        ? playerGroup.activeFleetWideFormation
                        : PermanentDivisionManager.getFleetDoctrine().getFleetWideFormation();
                if (preBattle) {
                    renderFleetFormationIcon(fw, rx + rw * 0.5f, y + h - 110f, 86f, alphaMult);
                } else {
                    renderFleetFormationIcon(fw, rx + rw - 30f, y + h - topBarH - 29f, 38f, alphaMult);
                }
            }
        };

        CustomPanelAPI panel = Global.getSettings().createCustom(width, height, fcPlugin);
        panelRef[0] = panel;
        panel.addComponent((UIComponentAPI) headerBannerLabel).setSize(width - 20f, 16f).inTL(10f, 2f);

        float flagshipTop = isAlphaCore ? titleTopY + 54f : titleTopY + 31f;
        float profileTop = isAlphaCore ? titleTopY + 77f : titleTopY + 55f;
        if (isAlphaCore) {
            String prefix = "ORDO COGITATOR/";
            String masterWord = "MASTER";
            String shipLine = flagshipName.toUpperCase(Locale.ROOT);
            PositionAPI pPre = panel.addComponent((UIComponentAPI) cogPrefixLabel);
            PositionAPI pGhost = panel.addComponent((UIComponentAPI) cogMasterGhostLabel);
            PositionAPI pMas = panel.addComponent((UIComponentAPI) cogMasterLabel);
            PositionAPI pSuf = panel.addComponent((UIComponentAPI) cogSuffixLabel);
            pPre.setSize(maxLeftTextW, 15f).inTL(textLeftX, titleTopY);
            cogPrefixLabel.setText(prefix);
            float preW = CampaignFleetTooltipOrganizer.measureLineWidth(prefix, cogPrefixLabel);
            float remAfterPre = Math.max(40f, maxLeftTextW - preW);
            pGhost.setSize(remAfterPre, 15f).inTL(textLeftX + preW, titleTopY);
            cogMasterGhostLabel.setText(masterWord);
            pMas.setSize(remAfterPre, 15f).inTL(textLeftX + preW, titleTopY);
            cogMasterLabel.setText(masterWord);
            pSuf.setSize(maxLeftTextW, 20f).inTL(textLeftX, titleTopY + 24f);
            cogSuffixLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                    shipLine, cogSuffixLabel, maxLeftTextW - 4f));
            masterBasePos[0] = textLeftX + preW;
            masterBasePos[1] = titleTopY;
            masterPosRef[0] = pMas;
            masterGhostPosRef[0] = pGhost;
        } else {
            PositionAPI pTitle = panel.addComponent((UIComponentAPI) otcStandardTitleLabel);
            pTitle.setSize(maxLeftTextW, 20f).inTL(textLeftX, titleTopY);
            otcStandardTitleLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                    formatFleetCommandOtcTitle(commanderName, flagshipName, false),
                    otcStandardTitleLabel, maxLeftTextW - 4f));
        }

        PermanentDivisionManager.FleetDoctrineConfig doc = PermanentDivisionManager.getFleetDoctrine();
        String personality = isAlphaCore
                ? "Alpha Core"
                : (playerGroup != null && playerGroup.otc != null
                ? playerGroup.otc.personalityId.toUpperCase(Locale.ROOT)
                : "STEADY");
        String flagshipLine = String.format(Locale.ROOT, "Flagship: %s (%s)", flagshipName, hullClassName);
        String profileLine = String.format(Locale.ROOT, "OTC: %s | %s", personality, doc.getOtcAuthority().shortName);
        panel.addComponent((UIComponentAPI) flagshipLabel).setSize(maxLeftTextW, 16f).inTL(textLeftX, flagshipTop);
        flagshipLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                flagshipLine, flagshipLabel, maxLeftTextW - 4f));
        panel.addComponent((UIComponentAPI) profileLabel).setSize(maxLeftTextW, 16f).inTL(textLeftX, profileTop);
        profileLabel.setText(CampaignFleetTooltipOrganizer.fitSingleLineToWidth(
                profileLine, profileLabel, maxLeftTextW - 4f));

        float rightLabelX = rightSectionX + 8f;
        float rightMaxW = Math.max(180f, rightSectionW - 16f);
        panel.addComponent((UIComponentAPI) selectorHeaderLabel).setSize(rightMaxW, 15f).inTL(rightLabelX, topBarH + 4f);
        panel.addComponent((UIComponentAPI) formationNameLabel)
                .setSize(preBattle ? rightSectionW - 84f : rightSectionW - 58f, 20f)
                .inTL(preBattle ? rightSectionX + 42f : rightLabelX, topBarH + 25f);

        if (!preBattle) {
            panel.addComponent((UIComponentAPI) engagementLabel)
                    .setSize(rightMaxW, 16f).inTL(rightLabelX, topBarH + 55f);
            panel.addComponent((UIComponentAPI) commitmentLabel)
                    .setSize(rightMaxW, 16f).inTL(rightLabelX, topBarH + 83f);
            panel.addComponent((UIComponentAPI) cohesionLabel)
                    .setSize(rightMaxW, 16f).inTL(rightLabelX, topBarH + 111f);
            panel.addComponent((UIComponentAPI) phaseLabel)
                    .setSize(rightMaxW, 13f).inTL(rightLabelX, topBarH + 133f);
        } else {
            TooltipMakerAPI buttons = panel.createUIElement(width, height, false);
            Color base = new Color(245, 200, 65);
            Color dark = new Color(24, 34, 44);
            prevButtonRef[0] = buttons.addButton("<", "FLEET_FORMATION_PREV", base, dark,
                    Alignment.MID, CutStyle.ALL, 28f, 22f, 0f);
            nextButtonRef[0] = buttons.addButton(">", "FLEET_FORMATION_NEXT", base, dark,
                    Alignment.MID, CutStyle.ALL, 28f, 22f, 0f);
            entryButtonRef[0] = buttons.addButton("Formation entry: On", "FLEET_FORMATION_ENTRY", base, dark,
                    Alignment.MID, CutStyle.ALL, rightSectionW - 76f, 18f, 0f);
            prevButtonRef[0].getPosition().inTL(rightSectionX + 8f, topBarH + 24f);
            nextButtonRef[0].getPosition().inTL(rightSectionX + rightSectionW - 36f, topBarH + 24f);
            entryButtonRef[0].getPosition().inTL(rightSectionX + 38f, height - 24f);
            buttons.setHeightSoFar(height);
            panel.addUIElement(buttons).inTL(0f, 0f);
        }
        fcPlugin.advance(0f);
        return panel;
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
        int current = doc.getFleetWideFormation().ordinal();
        int step = direction < 0 ? -1 : 1;
        PermanentDivisionManager.FleetWideFormation next = values[(current + step + values.length) % values.length];
        PermanentDivisionManager.updateFleetWideFormation(next);
        PermanentDivisionManager.updateDeployInFleetFormation(true);
        if (playerGroup != null) {
            playerGroup.fleetWideFormation = next;
            playerGroup.activeFleetWideFormation = next;
            playerGroup.deployInFleetFormation = true;
        }
        autoHarmonizePermanentDivisionsToFleetFormation(next);
        playDeploymentButtonSound();
    }

    private static void playDeploymentButtonSound() {
        try {
            Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f);
        } catch (Throwable ignored) {
        }
    }

    public static String getFleetFormationSpritePath(PermanentDivisionManager.FleetWideFormation formation) {
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
            case REFUSED_FLANK_OBLIQUE:
                icon = "refused_right";
                break;
            case CRESCENT_HORNS:
                icon = "crescent";
                break;
            case CHECKERBOARD_QUINCUNX:
                icon = "quincunx";
                break;
            case FLEET_PROTECTIVE_BASTION:
                icon = "bastion";
                break;
            case DOCTRINAL_AUTO:
            default:
                icon = "auto";
                break;
        }
        return "graphics/icons/fleet_formations/fleet_formation_" + icon + ".png";
    }

    public static void renderFleetFormationIcon(
            PermanentDivisionManager.FleetWideFormation formation,
            float centerX,
            float centerY,
            float size,
            float alphaMult) {
        String path = getFleetFormationSpritePath(formation);
        if (path == null) return;
        SpriteAPI sprite = FleetDoctrineDialog.loadFormationSpriteSafe(null, path);
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
                case CRESCENT_HORNS:
                    spec.customFormation = (i == 0)
                            ? PermanentDivisionManager.DivisionFormation.REVERSE_SEMICIRCLE
                            : (wingSide < 0
                            ? PermanentDivisionManager.DivisionFormation.ECHELON_RIGHT
                            : PermanentDivisionManager.DivisionFormation.ECHELON_LEFT);
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
                case GRAND_LINE_ABREAST:
                case DOCTRINAL_AUTO:
                default:
                    spec.customFormation = null;
                    break;
            }
        }
    }
}
