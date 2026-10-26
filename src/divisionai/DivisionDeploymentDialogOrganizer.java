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
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import org.apache.log4j.Logger;

import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reorganizes Starsector's combat Deployment Window (com.fs.starfarer.ui.impl.oO0O) into rows
 * following the player's list of non-empty Permanent Divisions:
 *  - Only activates when the player has at least 1 non-empty Permanent Division.
 *  - Each non-empty division with ships in reserve starts on its own row:
 *      - Columns 0..1 (116x58px): Division Row Header Card displaying the division name,
 *        ship count, DP total, and a one-click "Select Div" / "Deselect" toggle button.
 *      - Columns 2..9 (8 ship slots per row): That division's reserve ships (Flagship first,
 *        followed by constituent members), padded with empty cells at the end of the row so
 *        the next non-empty division begins on a fresh row.
 *  - Any unassigned player ships in reserve are placed in their own row(s) after the player's
 *    non-empty divisions.
 *  - Uses java.lang.invoke.MethodHandles exclusively (zero java.lang.reflect.* references).
 */
public class DivisionDeploymentDialogOrganizer {

    private static final Logger LOG = Global.getLogger(DivisionDeploymentDialogOrganizer.class);

    private static final Color DIV_HEADER_TEXT_COLOR = new Color(130, 255, 165, 255);
    private static final Color DIV_BTN_BASE_COLOR = new Color(75, 215, 120, 255);
    private static final Color DIV_BTN_DARK_COLOR = new Color(18, 52, 32, 235);
    private static final Color UNASSIGNED_TEXT_COLOR = new Color(195, 205, 215, 235);
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
    private MethodHandle listClearHandle;
    private MethodHandle listAddItemHandle;

    private Object lastFddInstance = null;
    private String lastReservesSignature = "";

    private void ensureHandles(Object combatUi) {
        if (handlesResolved || handlesFailed || combatUi == null) return;
        try {
            ClassLoader cl = combatUi.getClass().getClassLoader();
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            Class<?> panelClass = Class.forName("com.fs.starfarer.ui.interfacenew", false, cl);
            Class<?> uiCompBaseClass = Class.forName("com.fs.starfarer.ui.c", false, cl);
            Class<?> buttonImplClass = Class.forName("com.fs.starfarer.ui.n", false, cl);
            Class<?> fleetMemberImplClass = Class.forName("com.fs.starfarer.campaign.fleet.FleetMember", false, cl);
            Class<?> iconClass = Class.forName("com.fs.starfarer.ui.B.new", false, cl);
            Class<?> inputEventImplClass = Class.forName("com.fs.starfarer.util.A.C", false, cl);
            Class<?> scrollingListClass = Class.forName("com.fs.starfarer.coreui.v", false, cl);

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
            this.listClearHandle = lookup.findVirtual(
                    scrollingListClass,
                    "clear",
                    MethodType.methodType(void.class, boolean.class));
            this.listAddItemHandle = lookup.findVirtual(
                    scrollingListClass,
                    "addItem",
                    MethodType.methodType(int.class, uiCompBaseClass, Object.class));

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
        // Skip TitleScreenState background battles on the Main Menu (only hook real CombatState battles)
        if (!"com.fs.starfarer.combat.CombatState".equals(ui.getClass().getName())) {
            return;
        }

        // Only organize the Deployment Window when the player has at least 1 non-empty Permanent Division
        List<PermanentDivisionSpec> nonEmptyDivisions = PermanentDivisionManager.getNonEmptyPermanentDivisions();
        if (nonEmptyDivisions.isEmpty()) {
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

            Object reservesGrid = findReservesShipGrid(innerPanel);
            if (reservesGrid == null) return;

            @SuppressWarnings("unchecked")
            List<FleetMemberAPI> members = (List<FleetMemberAPI>) gridGetMembersHandle.invoke(reservesGrid);
            if (members == null || members.isEmpty()) {
                return;
            }

            String sig = buildMembersSignature(members, nonEmptyDivisions);
            if (activeFdd == lastFddInstance && sig.equals(lastReservesSignature)) {
                return;
            }

            organizeReservesByDivisionRows(activeFdd, reservesGrid, members, nonEmptyDivisions);

            // Recompute signature after reordering members list so we don't re-run until reserves change
            lastFddInstance = activeFdd;
            lastReservesSignature = buildMembersSignature(members, nonEmptyDivisions);
        } catch (Throwable t) {
            LOG.warn("[DivisionAI] Error organizing Deployment Window division rows", t);
        }
    }

    private Object findReservesShipGrid(UIPanelAPI innerPanel) throws Throwable {
        Object lowestGrid = null;
        float lowestY = Float.MAX_VALUE;
        @SuppressWarnings("unchecked")
        List<UIComponentAPI> children = (List<UIComponentAPI>) panelGetChildrenCopyHandle.invoke(innerPanel);
        for (UIComponentAPI child : children) {
            if (child != null && shipGridClass.isInstance(child) && child.getPosition() != null) {
                float y = child.getPosition().getY();
                if (y < lowestY) {
                    lowestY = y;
                    lowestGrid = child;
                }
            }
        }
        return lowestGrid;
    }

    private String buildMembersSignature(List<FleetMemberAPI> members, List<PermanentDivisionSpec> nonEmptyDivs) {
        StringBuilder sb = new StringBuilder(members.size() * 12);
        for (FleetMemberAPI m : members) {
            if (m != null) {
                sb.append(m.getId()).append(',');
            }
        }
        sb.append('|').append(nonEmptyDivs.size());
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
            List<PermanentDivisionSpec> nonEmptyDivisions) throws Throwable {

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

        int totalCols = (int) listGetColumnsHandle.invoke(scrollingList);
        if (totalCols < 4) totalCols = 10;
        float itemW = (float) listGetItemWidthHandle.invoke(scrollingList);
        float itemH = (float) listGetItemHeightHandle.invoke(scrollingList);

        // 2. Partition reserve ships into rows following the player's list of non-empty Permanent Divisions
        Set<String> placedMemberIds = new HashSet<>();
        List<FleetMemberAPI> orderedMembers = new ArrayList<>(byMemberId.size());

        // Clear the ScrollingList UI items (while keeping scroll position)
        listClearHandle.invoke(scrollingList, false);

        int currentSlotIndex = 0;
        int labelCols = 2; // Columns 0..1 (116px wide) hold the Division Row Header card
        int shipsPerRow = Math.max(1, totalCols - labelCols);

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

            for (MemberIconEntry e : divEntries) {
                orderedMembers.add(e.member);
            }

            currentSlotIndex = appendRowGroupToScrollingList(
                    fdd,
                    scrollingList,
                    divSpec.name,
                    divSpec.archetype.shortPrefix,
                    true,
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
                    "Vanilla AI",
                    false,
                    unassignedEntries,
                    currentSlotIndex,
                    totalCols,
                    labelCols,
                    shipsPerRow,
                    itemW,
                    itemH);
        }

        // 4. Sync membersList order to match the division row order
        membersList.clear();
        membersList.addAll(orderedMembers);
    }

    private int appendRowGroupToScrollingList(
            Object fdd,
            Object scrollingList,
            String groupTitle,
            String groupSub,
            boolean isDivisionRow,
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
            // Ensure we are at column 0 of a fresh row
            while ((currentSlotIndex % totalCols) != 0) {
                addEmptySpacerCell(scrollingList, itemW, itemH);
                currentSlotIndex++;
            }

            if (firstRowOfGroup) {
                CustomPanelAPI headerCard = createRowHeaderCard(
                        fdd,
                        groupTitle,
                        groupSub,
                        entries.size(),
                        totalDp,
                        isDivisionRow,
                        entries,
                        itemW * labelCols,
                        groupCardHeight);
                listAddItemHandle.invoke(scrollingList, headerCard, null);
                headerCard.getPosition().setSize(itemW * labelCols - 3f, groupCardHeight - 2f);
                currentSlotIndex++;

                for (int c = 1; c < labelCols; c++) {
                    CustomPanelAPI zeroSpacer = Global.getSettings().createCustom(0f, 0f, null);
                    listAddItemHandle.invoke(scrollingList, zeroSpacer, null);
                    zeroSpacer.getPosition().setSize(0f, 0f);
                    currentSlotIndex++;
                }
                firstRowOfGroup = false;
            } else {
                // Continuation row for divisions larger than 8 ships: indent Columns 0..1
                for (int c = 0; c < labelCols; c++) {
                    addEmptySpacerCell(scrollingList, itemW, itemH);
                    currentSlotIndex++;
                }
            }

            int countInRow = 0;
            while (idx < entries.size() && countInRow < shipsPerRow) {
                MemberIconEntry entry = entries.get(idx++);
                listAddItemHandle.invoke(scrollingList, entry.button, entry.icon);
                currentSlotIndex++;
                countInRow++;
            }

            // Pad the remainder of this row so the next division starts on a fresh row
            while ((currentSlotIndex % totalCols) != 0) {
                addEmptySpacerCell(scrollingList, itemW, itemH);
                currentSlotIndex++;
            }
        }

        return currentSlotIndex;
    }

    private void addEmptySpacerCell(Object scrollingList, float itemW, float itemH) throws Throwable {
        CustomPanelAPI spacer = Global.getSettings().createCustom(0f, 0f, null);
        listAddItemHandle.invoke(scrollingList, spacer, null);
        spacer.getPosition().setSize(0f, 0f);
    }

    private CustomPanelAPI createRowHeaderCard(
            final Object fdd,
            final String title,
            final String subtitle,
            final int shipCount,
            final float totalDp,
            final boolean isDivisionRow,
            final List<MemberIconEntry> groupEntries,
            final float width,
            final float height) {

        final ButtonAPI[] toggleBtnHolder = new ButtonAPI[1];

        BaseCustomUIPanelPlugin cardPlugin = new BaseCustomUIPanelPlugin() {
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

        CustomPanelAPI card = Global.getSettings().createCustom(width - 3f, height - 2f, cardPlugin);
        TooltipMakerAPI tt = card.createUIElement(width - 3f, height - 2f, false);

        Color titleColor = isDivisionRow ? DIV_HEADER_TEXT_COLOR : UNASSIGNED_TEXT_COLOR;
        Color btnBase = isDivisionRow ? DIV_BTN_BASE_COLOR : UNASSIGNED_BTN_BASE;
        Color btnDark = isDivisionRow ? DIV_BTN_DARK_COLOR : UNASSIGNED_BTN_DARK;

        tt.setParaFont(Fonts.VICTOR_10);
        String clippedTitle = title.length() > 18 ? title.substring(0, 17) + "." : title;
        tt.addPara("%s", 1f, titleColor, clippedTitle);
        tt.addPara(
                "%s sh • %s DP",
                1f,
                Misc.getHighlightColor(),
                String.valueOf(shipCount),
                String.format("%.0f", totalDp));

        ButtonAPI btn = tt.addButton(
                isDivisionRow ? "Select Div" : "Select Row",
                "TOGGLE_ROW",
                btnBase,
                btnDark,
                Alignment.MID,
                CutStyle.ALL,
                width - 8f,
                16f,
                2f);
        toggleBtnHolder[0] = btn;

        card.addUIElement(tt).inTL(0f, 0f);
        return card;
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
