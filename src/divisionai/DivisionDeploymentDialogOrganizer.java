package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
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
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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

    private static final Color DIV_HEADER_TEXT_COLOR = new Color(130, 255, 165, 255);
    private static final Color DIV_ACCENT_COLOR = new Color(75, 225, 125, 255);
    private static final Color DIV_BTN_BASE_COLOR = new Color(75, 215, 120, 255);
    private static final Color DIV_BTN_DARK_COLOR = new Color(18, 52, 32, 235);
    private static final Color UNASSIGNED_TEXT_COLOR = new Color(195, 205, 215, 235);
    private static final Color UNASSIGNED_ACCENT_COLOR = new Color(135, 150, 165, 255);
    private static final Color UNASSIGNED_BTN_BASE = new Color(135, 150, 165, 255);
    private static final Color UNASSIGNED_BTN_DARK = new Color(32, 38, 46, 235);

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
    private MethodHandle scrollerPackHandle;
    private MethodHandle scrollerClampOffsetHandle;
    private MethodHandle posRecomputeHandle;

    private Object lastFddInstance = null;
    private String lastReservesSignature = "";

    private void ensureHandles(Object combatUi) {
        if (handlesResolved || handlesFailed || combatUi == null) return;
        try {
            ClassLoader cl = combatUi.getClass().getClassLoader();
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
                    combatUi.getClass(),
                    "isShowingDeploymentDialog",
                    MethodType.methodType(boolean.class));
            this.getWidgetPanelHandle = lookup.findVirtual(
                    combatUi.getClass(),
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

            UIPanelAPI innerPanel = (UIPanelAPI) fddGetInnerPanelHandle.invoke(activeFdd);
            if (innerPanel == null) return;

            Object[] grids = findDeployedAndReservesGrids(innerPanel);
            Object deployedGrid = grids[0];
            Object reservesGrid = grids[1];
            if (reservesGrid == null) return;

            // 1. Enlarge the Deployment Window (wider 14-column layout + taller 7-row reserve viewport)
            int targetCols = enlargeDeploymentDialogIfNeeded(activeFdd, innerPanel, deployedGrid, reservesGrid);

            @SuppressWarnings("unchecked")
            List<FleetMemberAPI> members = (List<FleetMemberAPI>) gridGetMembersHandle.invoke(reservesGrid);
            if (members == null || members.isEmpty()) {
                return;
            }

            List<PermanentDivisionSpec> nonEmptyDivisions = PermanentDivisionManager.getNonEmptyPermanentDivisions();
            String sig = buildMembersSignature(members, nonEmptyDivisions, targetCols);
            if (activeFdd == lastFddInstance && sig.equals(lastReservesSignature)) {
                return;
            }

            if (!nonEmptyDivisions.isEmpty()) {
                organizeReservesByDivisionRows(activeFdd, reservesGrid, members, nonEmptyDivisions, targetCols);
            } else {
                reflowGridItemsToColumns(reservesGrid, targetCols);
            }

            lastFddInstance = activeFdd;
            lastReservesSignature = buildMembersSignature(members, nonEmptyDivisions, targetCols);
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
                }
                reflowGridItemsToColumns(deployedGrid, maxColsForScreen);
            }

            resPos.setSize(targetGridW, targetResH);

            List<UIComponentAPI> children = (List<UIComponentAPI>) panelGetChildrenCopyHandle.invoke(innerPanel);
            for (UIComponentAPI child : children) {
                if (child == null || child == deployedGrid || child == reservesGrid) continue;
                PositionAPI cp = child.getPosition();
                if (cp == null) continue;
                if (Math.abs(cp.getWidth() - oldInnerW) <= 6f) {
                    cp.setSize(targetInnerW, cp.getHeight());
                } else if (Math.abs(cp.getWidth() - (10f * itemW + 4f)) <= 8f) {
                    cp.setSize(maxColsForScreen * itemW + 4f, cp.getHeight());
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

    private String buildMembersSignature(
            List<FleetMemberAPI> members,
            List<PermanentDivisionSpec> nonEmptyDivs,
            int totalCols) {
        StringBuilder sb = new StringBuilder(members.size() * 12 + 16);
        for (FleetMemberAPI m : members) {
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

        MemberIconEntry(FleetMemberAPI member, Object button, Object icon) {
            this.member = member;
            this.button = button;
            this.icon = icon;
        }
    }

    private void organizeReservesByDivisionRows(
            Object fdd,
            Object reservesGrid,
            List<FleetMemberAPI> membersList,
            List<PermanentDivisionSpec> nonEmptyDivisions,
            int totalCols) throws Throwable {

        // 1. Capture existing ship buttons and icons from reservesGrid before clearing ScrollingList
        Map<String, MemberIconEntry> byMemberId = new LinkedHashMap<>();
        for (FleetMemberAPI m : new ArrayList<>(membersList)) {
            if (m == null) continue;
            Object btn = gridGetButtonForMemberHandle.invoke(reservesGrid, m);
            Object icon = gridGetIconForMemberHandle.invoke(reservesGrid, m);
            if (btn != null && icon != null) {
                byMemberId.put(m.getId(), new MemberIconEntry(m, btn, icon));
            }
        }

        if (byMemberId.isEmpty()) return;

        Object scrollingList = gridGetListHandle.invoke(reservesGrid);
        if (scrollingList == null) return;

        if (totalCols < 6) totalCols = ENLARGED_TOTAL_COLS;
        float itemW = (float) listGetItemWidthHandle.invoke(scrollingList);
        float itemH = (float) listGetItemHeightHandle.invoke(scrollingList);

        // 2. Partition reserve ships into rows following the player's list of non-empty Permanent Divisions
        Set<String> placedMemberIds = new HashSet<>();
        List<FleetMemberAPI> orderedMembers = new ArrayList<>(byMemberId.size());

        // Clear the ScrollingList UI items (while keeping scroll position)
        listClearHandle.invoke(scrollingList, false);

        int currentSlotIndex = 0;
        int labelCols = LABEL_COLS; // Columns 0..1 (116px wide) hold the Division Row Header card
        int shipsPerRow = Math.max(1, totalCols - labelCols);
        DivisionNomenclature.NomenclatureCategory playerNomCat = DivisionNomenclature.getPlayerNomenclatureCategory();
        int divSeq = 1;

        for (PermanentDivisionSpec divSpec : nonEmptyDivisions) {
            List<MemberIconEntry> divEntries = new ArrayList<>();

            // Put the division's designated Flagship first in the row if present in reserves
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
                orderedMembers.add(e.member);
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
                    currentSlotIndex,
                    totalCols,
                    labelCols,
                    shipsPerRow,
                    itemW,
                    itemH);
        }

        // 3. Place any unassigned reserve ships in their own row(s) below the player's divisions
        List<MemberIconEntry> unassignedEntries = new ArrayList<>();
        for (Map.Entry<String, MemberIconEntry> e : byMemberId.entrySet()) {
            if (!placedMemberIds.contains(e.getKey())) {
                unassignedEntries.add(e.getValue());
                orderedMembers.add(e.getValue().member);
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
            scrollerSetContentSizeHandle.invoke(scroller, totalCols * itemW, totalRows * itemH);
            scrollerPackHandle.invoke(scroller);
            scrollerClampOffsetHandle.invoke(scroller);
        }

        // 5. Sync membersList order to match the division row order
        membersList.clear();
        membersList.addAll(orderedMembers);
    }

    private int appendRowGroupToScrollingList(
            Object fdd,
            Object scrollingList,
            String groupTitle,
            boolean isDivisionRow,
            PermanentDivisionManager.DivisionFormation divFm,
            boolean isFlagshipDiv,
            List<MemberIconEntry> entries,
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

        LabelAPI line1Label = Global.getSettings().createLabel("", Fonts.DEFAULT_SMALL);
        line1Label.setColor(titleColor);
        line1Label.setAlignment(Alignment.LMID);

        LabelAPI line2Label = Global.getSettings().createLabel("", Fonts.DEFAULT_SMALL);
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
                    com.fs.starfarer.api.graphics.SpriteAPI sprite = FleetDoctrineDialog.loadFormationSpriteSafe(
                            divFm.getSettingsIconKey(isFlagshipDiv),
                            divFm.getSpritePath(isFlagshipDiv));
                    if (sprite != null) {
                        sprite.setSize(iconSize, iconSize);
                        sprite.setColor(Color.WHITE);
                        sprite.setAlphaMult(0.95f * alphaMult);
                        sprite.renderAtCenter(iconCx, iconCy);
                    }
                    FleetDoctrineDialog.renderFormationSquareBorder(
                            iconCx, iconCy, iconSize, isFlagshipDiv, 0.95f * alphaMult);
                }
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
        float initLabelW = Math.max(160f, cardW * 2f);
        p1.setSize(initLabelW, 13f).inTL(0f, 0f);
        p2.setSize(initLabelW, 13f).inTL(0f, 0f);

        if (!isTwoLineTitle) {
            String l1 = lines.length > 0 ? lines[0] : "";
            float safeW1 = Math.max(maxLine1W, CampaignFleetTooltipOrganizer.measureLineWidth(l1, line1Label) + 20f);
            p1.setSize(safeW1, 14f).inTL(line1LeftX, 9f);
            line1Label.setText(l1);

            p2.setSize(safeW1, 0f).inTL(line2LeftX, 0f);
            line2Label.setText("");
        } else {
            String l1 = lines[0];
            String l2 = lines[1];
            float safeW1 = Math.max(maxLine1W, CampaignFleetTooltipOrganizer.measureLineWidth(l1, line1Label) + 20f);
            p1.setSize(safeW1, 13f).inTL(line1LeftX, 3f);
            line1Label.setText(l1);

            float safeW2 = Math.max(maxLine2W, CampaignFleetTooltipOrganizer.measureLineWidth(l2, line2Label) + 20f);
            p2.setSize(safeW2, 13f).inTL(line2LeftX, 16f);
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
                if (e.member.canBeDeployedForCombat() && !selectedIds.contains(e.member.getId())) {
                    anyDeployableUnselected = true;
                    break;
                }
            }

            if (anyDeployableUnselected) {
                // Select all unselected deployable ships in this division row
                for (MemberIconEntry e : entries) {
                    if (e.member.canBeDeployedForCombat() && !selectedIds.contains(e.member.getId())) {
                        fddFleetMemberClickedHandle.invoke(fdd, e.member, e.icon, null);
                    }
                }
            } else {
                // All deployable ships in this division row are already selected -> deselect them
                for (MemberIconEntry e : entries) {
                    if (!e.member.canBeDeployedForCombat()) continue;
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
}
