package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import divisionai.DivisionModel.DivisionArchetype;
import divisionai.DivisionNomenclature.NomenclatureCategory;
import divisionai.PermanentDivisionManager.PermanentDivisionSpec;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Organizes the Campaign-Layer Fleet Mouseover Tooltip (StandardTooltipV2.createFleetTooltip)
 * into horizontal rows sorted by the fleet's divisions, mirroring the Deployment Window.
 * Any ships that are not in a division are rendered as normal in the last row(s).
 *
 * Uses java.lang.invoke.MethodHandles.privateLookupIn (zero java.lang.reflect references)
 * so Starsector's SecurityManager never blocks access.
 */
public final class CampaignFleetTooltipOrganizer {

    private static final Logger LOG = Global.getLogger(CampaignFleetTooltipOrganizer.class);

    private static final int HEADER_COLS = 2;

    private boolean handlesResolved = false;
    private boolean handleResolutionFailed = false;

    private Class<?> sPanelClass;
    private Class<?> vGridClass;

    private MethodHandle getCampaignEngineInstanceHandle;
    private MethodHandle getTooltipManagerHandle;
    private MethodHandle getHoveredEntityHandle;
    private MethodHandle getActiveTooltipHandle;

    private MethodHandle getTooltipContentsHandle;
    private MethodHandle updateTooltipSizeHandle;
    private MethodHandle getPanelChildrenCopyHandle;

    private MethodHandle gridGetMembersHandle;
    private MethodHandle gridGetButtonForMemberHandle;
    private MethodHandle gridGetIconForMemberHandle;
    private MethodHandle gridGetListHandle;

    private MethodHandle listGetColumnsHandle;
    private MethodHandle listGetItemWidthHandle;
    private MethodHandle listGetItemHeightHandle;
    private MethodHandle listClearHandle;
    private MethodHandle listAddItemHandle;

    private Object lastOrganizedSPanel = null;
    private final List<ThreatHeaderBinding> activeThreatHeaders = new ArrayList<>();
    private long lastThreatTimeBucket = -1L;

    private static final class MemberIconEntry {
        final FleetMemberAPI member;
        final Object button;
        final Object icon;

        MemberIconEntry(FleetMemberAPI member, Object button, Object icon) {
            this.member = member;
            this.button = button;
            this.icon = icon;
        }
    }

    private static final class ThreatHeaderBinding {
        final DivisionHeaderCardPlugin plugin;
        final DivisionArchetype archetype;
        final int divisionNumber;

        ThreatHeaderBinding(DivisionHeaderCardPlugin plugin, DivisionArchetype archetype, int divisionNumber) {
            this.plugin = plugin;
            this.archetype = archetype;
            this.divisionNumber = divisionNumber;
        }
    }

    private static final class DivisionHeaderCardPlugin extends BaseCustomUIPanelPlugin {
        private CustomPanelAPI panel;
        private final Color accentColor;
        private final Color textColor;
        private final float boxWidth;
        private final float boxHeight;
        private final float divisionSpanWidth;
        private LabelAPI line1Label;
        private LabelAPI line2Label;

        DivisionHeaderCardPlugin(
                Color accentColor,
                Color textColor,
                float boxWidth,
                float boxHeight,
                float divisionSpanWidth) {
            this.accentColor = accentColor != null ? accentColor : Misc.getBasePlayerColor();
            this.textColor = textColor != null ? textColor : this.accentColor;
            this.boxWidth = boxWidth;
            this.boxHeight = boxHeight;
            this.divisionSpanWidth = divisionSpanWidth;
        }

        void attachToPanel(CustomPanelAPI card, String initialTitle) {
            this.panel = card;
            this.line1Label = Global.getSettings().createLabel("", Fonts.DEFAULT_SMALL);
            this.line1Label.setColor(textColor);
            this.line1Label.setAlignment(Alignment.MID);

            this.line2Label = Global.getSettings().createLabel("", Fonts.DEFAULT_SMALL);
            this.line2Label.setColor(textColor);
            this.line2Label.setAlignment(Alignment.MID);

            card.addComponent((UIComponentAPI) line1Label).inTL(4f, 0f);
            card.addComponent((UIComponentAPI) line2Label).inTL(4f, 0f);

            updateTitleAndLayout(initialTitle);
        }

        void updateTitleAndLayout(String rawTitle) {
            if (line1Label == null || line2Label == null) return;
            String clean = rawTitle != null ? rawTitle.trim() : "";
            float maxTextWidth = Math.max(40f, boxWidth - 14f);

            String[] lines = splitBalancedTwoLines(clean, line1Label, maxTextWidth);
            if (lines[1] == null || lines[1].isEmpty()) {
                line1Label.setText(lines[0]);
                line2Label.setText("");

                PositionAPI p1 = ((UIComponentAPI) line1Label).getPosition();
                p1.setSize(boxWidth - 8f, boxHeight);
                p1.inTL(5f, 0f);

                PositionAPI p2 = ((UIComponentAPI) line2Label).getPosition();
                p2.setSize(0f, 0f);
                p2.inTL(5f, 0f);
            } else {
                line1Label.setText(lines[0]);
                line2Label.setText(lines[1]);

                float lineH = 14f;
                float blockH = lineH * 2f;
                float topY = Math.max(2f, (boxHeight - blockH) * 0.5f);

                PositionAPI p1 = ((UIComponentAPI) line1Label).getPosition();
                p1.setSize(boxWidth - 8f, lineH);
                p1.inTL(5f, topY);

                PositionAPI p2 = ((UIComponentAPI) line2Label).getPosition();
                p2.setSize(boxWidth - 8f, lineH);
                p2.inTL(5f, topY + lineH);
            }
        }

        private static String[] splitBalancedTwoLines(String text, LabelAPI measurer, float maxLineW) {
            if (text.isEmpty() || measurer.computeTextWidth(text) <= maxLineW || !text.contains(" ")) {
                return new String[]{text, ""};
            }

            int bestSpace = -1;
            int mid = text.length() / 2;
            int bestDist = Integer.MAX_VALUE;
            for (int i = 1; i < text.length() - 1; i++) {
                if (text.charAt(i) == ' ') {
                    int dist = Math.abs(i - mid);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestSpace = i;
                    }
                }
            }
            if (bestSpace <= 0) {
                return new String[]{text, ""};
            }
            String first = text.substring(0, bestSpace).trim();
            String second = text.substring(bestSpace + 1).trim();
            return new String[]{first, second};
        }

        @Override
        public void renderBelow(float alphaMult) {
            if (panel == null) return;
            PositionAPI pos = panel.getPosition();
            if (pos == null) return;

            float x = pos.getX();
            float y = pos.getY();
            float w = boxWidth;
            float h = boxHeight;

            float r = accentColor.getRed() / 255f;
            float g = accentColor.getGreen() / 255f;
            float b = accentColor.getBlue() / 255f;

            float bx = x + 1f;
            float by = y + 2f;
            float bw = w - 4f;
            float bh = h - 4f;

            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            // Solid dark card backdrop covering the 2-column x N-row header area cleanly
            GL11.glColor4f(0.03f, 0.06f, 0.10f, 0.96f * alphaMult);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(bx, by);
            GL11.glVertex2f(bx + bw, by);
            GL11.glVertex2f(bx + bw, by + bh);
            GL11.glVertex2f(bx, by + bh);
            GL11.glEnd();

            // Subtle accent wash inside the header box
            GL11.glColor4f(r, g, b, 0.10f * alphaMult);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(bx, by);
            GL11.glVertex2f(bx + bw, by);
            GL11.glVertex2f(bx + bw, by + bh);
            GL11.glVertex2f(bx, by + bh);
            GL11.glEnd();

            // Left vertical accent bar spanning full division height
            GL11.glColor4f(r, g, b, 0.95f * alphaMult);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(bx, by);
            GL11.glVertex2f(bx + 3.0f, by);
            GL11.glVertex2f(bx + 3.0f, by + bh);
            GL11.glVertex2f(bx, by + bh);
            GL11.glEnd();

            // Clean box border around the header card
            GL11.glLineWidth(1.0f);
            GL11.glColor4f(r, g, b, 0.62f * alphaMult);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(bx, by);
            GL11.glVertex2f(bx + bw, by);
            GL11.glVertex2f(bx + bw, by + bh);
            GL11.glVertex2f(bx, by + bh);
            GL11.glEnd();

            // Subtle outer frame enclosing the division's ships across all rows of this division
            if (divisionSpanWidth > w + 4f) {
                float spanW = divisionSpanWidth - 2f;
                GL11.glColor4f(r, g, b, 0.35f * alphaMult);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(bx, by);
                GL11.glVertex2f(x + spanW, by);
                GL11.glVertex2f(x + spanW, by + bh);
                GL11.glVertex2f(bx, by + bh);
                GL11.glEnd();
            }

            GL11.glPopAttrib();
        }
    }

    public void advance() {
        if (!resolveHandlesIfNeeded()) {
            return;
        }

        try {
            Object campaignEngine = getCampaignEngineInstanceHandle.invoke();
            if (campaignEngine == null) return;

            Object tooltipMgr = getTooltipManagerHandle.invoke(campaignEngine);
            if (tooltipMgr == null) return;

            Object hoveredEntity = getHoveredEntityHandle.invoke(tooltipMgr);
            Object activeTooltip = getActiveTooltipHandle.invoke(tooltipMgr);

            if (!(hoveredEntity instanceof CampaignFleetAPI) || activeTooltip == null) {
                lastOrganizedSPanel = null;
                activeThreatHeaders.clear();
                return;
            }

            CampaignFleetAPI fleet = (CampaignFleetAPI) hoveredEntity;
            if (fleet.isStationMode() || fleet.getFleetData() == null) {
                return;
            }

            Object contentsObj = getTooltipContentsHandle.invoke(activeTooltip);
            if (!(contentsObj instanceof UIPanelAPI)) {
                return;
            }

            List<?> children = (List<?>) getPanelChildrenCopyHandle.invoke(contentsObj);
            if (children == null || children.isEmpty()) {
                return;
            }

            Object sPanel = null;
            for (Object child : children) {
                if (child != null && sPanelClass.isInstance(child)) {
                    sPanel = child;
                    break;
                }
            }
            if (sPanel == null) {
                return;
            }

            if (sPanel == lastOrganizedSPanel) {
                // If this fleet uses Threat shifting corrupted text, animate the header labels in real time!
                if (!activeThreatHeaders.isEmpty()) {
                    long bucket = System.currentTimeMillis() / 240L;
                    if (bucket != lastThreatTimeBucket) {
                        lastThreatTimeBucket = bucket;
                        for (ThreatHeaderBinding b : activeThreatHeaders) {
                            if (b.plugin != null) {
                                b.plugin.updateTitleAndLayout(DivisionNomenclature.formatCorruptedThreatText(
                                        b.archetype,
                                        b.divisionNumber,
                                        bucket));
                            }
                        }
                    }
                }
                return;
            }

            organizeFleetTooltip(fleet, activeTooltip, (UIPanelAPI) contentsObj, sPanel);
            lastOrganizedSPanel = sPanel;
        } catch (Throwable t) {
            if (!handleResolutionFailed) {
                handleResolutionFailed = true;
                LOG.warn("[DivisionAI] Error organizing campaign fleet mouseover tooltip", t);
            }
        }
    }

    private boolean resolveHandlesIfNeeded() {
        if (handlesResolved) return true;
        if (handleResolutionFailed) return false;

        try {
            ClassLoader cl = Global.class.getClassLoader();
            Class<?> ceClass = Class.forName("com.fs.starfarer.campaign.CampaignEngine", false, cl);
            Class<?> tmClass = Class.forName("com.fs.starfarer.campaign.OOoO", false, cl);
            Class<?> entityClass = Class.forName("com.fs.starfarer.campaign.CampaignEntity", false, cl);
            Class<?> stClass = Class.forName("com.fs.starfarer.ui.impl.StandardTooltipV2", false, cl);
            Class<?> uiCompClass = Class.forName("com.fs.starfarer.ui.c", false, cl);
            Class<?> ifNewClass = Class.forName("com.fs.starfarer.ui.interfacenew", false, cl);
            Class<?> buttonImplClass = Class.forName("com.fs.starfarer.ui.n", false, cl);
            Class<?> fleetMemberImplClass = Class.forName("com.fs.starfarer.campaign.fleet.FleetMember", false, cl);
            Class<?> iconClass = Class.forName("com.fs.starfarer.ui.B.new", false, cl);

            sPanelClass = Class.forName("com.fs.starfarer.campaign.ui.S", false, cl);
            vGridClass = Class.forName("com.fs.starfarer.coreui.v", false, cl);

            MethodHandles.Lookup pubLookup = MethodHandles.lookup();
            MethodHandles.Lookup tmLookup = MethodHandles.privateLookupIn(tmClass, pubLookup);

            getCampaignEngineInstanceHandle = pubLookup.findStatic(
                    ceClass,
                    "getInstance",
                    MethodType.methodType(ceClass));
            getTooltipManagerHandle = pubLookup.findVirtual(
                    ceClass,
                    "getTooltipManager",
                    MethodType.methodType(tmClass));
            getHoveredEntityHandle = tmLookup.findGetter(
                    tmClass,
                    "o00000",
                    entityClass);

            MethodHandle tooltipGetter = null;
            String[] candidateTooltipFields = {"\u00d800000", "\u00d400000"};
            for (String fName : candidateTooltipFields) {
                try {
                    tooltipGetter = tmLookup.findGetter(tmClass, fName, stClass);
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (tooltipGetter == null) {
                throw new NoSuchFieldException("StandardTooltipV2 field not found on OOoO");
            }
            getActiveTooltipHandle = tooltipGetter;

            getTooltipContentsHandle = pubLookup.findVirtual(
                    stClass,
                    "getContents",
                    MethodType.methodType(uiCompClass));
            updateTooltipSizeHandle = pubLookup.findVirtual(
                    stClass,
                    "updateSize",
                    MethodType.methodType(void.class));
            getPanelChildrenCopyHandle = pubLookup.findVirtual(
                    ifNewClass,
                    "getChildrenCopy",
                    MethodType.methodType(List.class));

            gridGetMembersHandle = pubLookup.findVirtual(
                    sPanelClass,
                    "getMembers",
                    MethodType.methodType(List.class));
            gridGetButtonForMemberHandle = pubLookup.findVirtual(
                    sPanelClass,
                    "getButtonForMember",
                    MethodType.methodType(buttonImplClass, fleetMemberImplClass));
            gridGetIconForMemberHandle = pubLookup.findVirtual(
                    sPanelClass,
                    "getIconForMember",
                    MethodType.methodType(iconClass, fleetMemberImplClass));
            gridGetListHandle = pubLookup.findVirtual(
                    sPanelClass,
                    "getList",
                    MethodType.methodType(vGridClass));

            listGetColumnsHandle = pubLookup.findVirtual(
                    vGridClass,
                    "getColumns",
                    MethodType.methodType(int.class));
            listGetItemWidthHandle = pubLookup.findVirtual(
                    vGridClass,
                    "getItemWidth",
                    MethodType.methodType(float.class));
            listGetItemHeightHandle = pubLookup.findVirtual(
                    vGridClass,
                    "getItemHeight",
                    MethodType.methodType(float.class));
            listClearHandle = pubLookup.findVirtual(
                    vGridClass,
                    "clear",
                    MethodType.methodType(void.class, boolean.class));
            listAddItemHandle = pubLookup.findVirtual(
                    vGridClass,
                    "addItem",
                    MethodType.methodType(int.class, uiCompClass, Object.class));

            handlesResolved = true;
            return true;
        } catch (Throwable t) {
            handleResolutionFailed = true;
            LOG.warn("[DivisionAI] Failed to resolve MethodHandles for CampaignFleetTooltipOrganizer", t);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void organizeFleetTooltip(
            CampaignFleetAPI fleet,
            Object standardTooltipV2,
            UIPanelAPI contentsPanel,
            Object sPanelObj) throws Throwable {

        activeThreatHeaders.clear();
        List<PermanentDivisionSpec> divisions = FactionFleetDoctrines.ensureFleetDivisionsSorted(fleet);
        if (divisions == null || divisions.isEmpty()) {
            // Player fleet with 0 divisions or empty fleet: leave vanilla layout untouched
            return;
        }

        List<FleetMemberAPI> membersList = (List<FleetMemberAPI>) gridGetMembersHandle.invoke(sPanelObj);
        if (membersList == null || membersList.isEmpty()) {
            return;
        }

        Map<String, List<MemberIconEntry>> entriesByMemberId = new HashMap<>();
        List<MemberIconEntry> allEntries = new ArrayList<>(membersList.size());
        for (FleetMemberAPI m : new ArrayList<>(membersList)) {
            if (m == null) continue;
            Object btn = gridGetButtonForMemberHandle.invoke(sPanelObj, m);
            Object icon = gridGetIconForMemberHandle.invoke(sPanelObj, m);
            if (btn != null && icon != null) {
                MemberIconEntry entry = new MemberIconEntry(m, btn, icon);
                allEntries.add(entry);
                if (m.getId() != null) {
                    List<MemberIconEntry> bucket = entriesByMemberId.get(m.getId());
                    if (bucket == null) {
                        bucket = new ArrayList<>(1);
                        entriesByMemberId.put(m.getId(), bucket);
                    }
                    bucket.add(entry);
                }
            }
        }
        if (allEntries.isEmpty()) {
            return;
        }

        Object scrollingList = gridGetListHandle.invoke(sPanelObj);
        if (scrollingList == null) return;

        int totalCols = (int) listGetColumnsHandle.invoke(scrollingList);
        if (totalCols < 4) totalCols = 8;
        float itemW = (float) listGetItemWidthHandle.invoke(scrollingList);
        float itemH = (float) listGetItemHeightHandle.invoke(scrollingList);
        if (itemW <= 0f || itemH <= 0f) return;

        int shipsPerDivRow = Math.max(2, totalCols - HEADER_COLS);
        Set<MemberIconEntry> placedEntries = new HashSet<>();
        List<PermanentDivisionSpec> nonEmptySpecs = new ArrayList<>();
        List<List<MemberIconEntry>> entriesPerDivision = new ArrayList<>();

        for (PermanentDivisionSpec spec : divisions) {
            if (spec == null || spec.memberIds == null || spec.memberIds.isEmpty()) continue;
            List<MemberIconEntry> orderedDivEntries = new ArrayList<>();

            // 1. Flagship first
            if (spec.flagshipMemberId != null) {
                List<MemberIconEntry> flagBucket = entriesByMemberId.get(spec.flagshipMemberId);
                if (flagBucket != null) {
                    for (MemberIconEntry entry : flagBucket) {
                        if (placedEntries.add(entry)) {
                            orderedDivEntries.add(entry);
                            break;
                        }
                    }
                }
            }

            // 2. Remaining division members
            for (String mId : spec.memberIds) {
                List<MemberIconEntry> bucket = entriesByMemberId.get(mId);
                if (bucket == null) continue;
                for (MemberIconEntry entry : bucket) {
                    if (placedEntries.add(entry)) {
                        orderedDivEntries.add(entry);
                    }
                }
            }

            if (!orderedDivEntries.isEmpty()) {
                nonEmptySpecs.add(spec);
                entriesPerDivision.add(orderedDivEntries);
            }
        }

        if (nonEmptySpecs.isEmpty()) {
            return;
        }

        // Unassigned ships (and fighter wings) are rendered as normal in the last row(s)!
        List<MemberIconEntry> unassignedEntries = new ArrayList<>();
        for (MemberIconEntry entry : allEntries) {
            if (!placedEntries.contains(entry)) {
                unassignedEntries.add(entry);
            }
        }

        NomenclatureCategory nomCat = DivisionNomenclature.resolveCategoryForCampaignFleet(fleet);
        FactionAPI faction = fleet.getFaction();
        Color factionColor = (faction != null) ? faction.getBaseUIColor() : Misc.getBasePlayerColor();

        listClearHandle.invoke(scrollingList, false);
        List<FleetMemberAPI> orderedMembers = new ArrayList<>(allEntries.size());
        int currentSlotIndex = 0;
        long timeBucket = System.currentTimeMillis() / 240L;
        lastThreatTimeBucket = timeBucket;
        int lastPlacedColInRow = 0;

        for (int dIdx = 0; dIdx < nonEmptySpecs.size(); dIdx++) {
            PermanentDivisionSpec spec = nonEmptySpecs.get(dIdx);
            List<MemberIconEntry> divEntries = entriesPerDivision.get(dIdx);

            int rowsForDiv = Math.max(1, (divEntries.size() + shipsPerDivRow - 1) / shipsPerDivRow);
            int maxShipsInDivRow = Math.min(shipsPerDivRow, divEntries.size());
            float boxWidth = itemW * HEADER_COLS;
            float boxHeight = itemH * rowsForDiv;
            float divisionSpanWidth = itemW * (HEADER_COLS + maxShipsInDivRow);
            Color divColor = resolveDivisionCardColor(spec.archetype, nomCat, factionColor);

            int idx = 0;
            boolean firstRowOfDiv = true;
            while (idx < divEntries.size()) {
                while ((currentSlotIndex % totalCols) != 0) {
                    addEmptySpacerCell(scrollingList);
                    currentSlotIndex++;
                }

                if (firstRowOfDiv) {
                    addExpandedDivisionHeaderCard(
                            scrollingList,
                            spec,
                            dIdx + 1,
                            nomCat,
                            divColor,
                            boxWidth,
                            boxHeight,
                            divisionSpanWidth);
                    currentSlotIndex++;

                    for (int hc = 1; hc < HEADER_COLS; hc++) {
                        addEmptySpacerCell(scrollingList);
                        currentSlotIndex++;
                    }
                    firstRowOfDiv = false;
                } else {
                    // Multi-row division: the single expanded box from row 0 spans across these columns
                    for (int hc = 0; hc < HEADER_COLS; hc++) {
                        addEmptySpacerCell(scrollingList);
                        currentSlotIndex++;
                    }
                }

                int countInRow = 0;
                while (idx < divEntries.size() && countInRow < shipsPerDivRow) {
                    MemberIconEntry entry = divEntries.get(idx++);
                    listAddItemHandle.invoke(scrollingList, entry.button, entry.icon);
                    orderedMembers.add(entry.member);
                    lastPlacedColInRow = currentSlotIndex % totalCols;
                    currentSlotIndex++;
                    countInRow++;
                }

                while ((currentSlotIndex % totalCols) != 0) {
                    addEmptySpacerCell(scrollingList);
                    currentSlotIndex++;
                }
            }
        }

        // Render unassigned ships (and fighter wings) as normal in the last row(s) starting at column 0!
        if (!unassignedEntries.isEmpty()) {
            while ((currentSlotIndex % totalCols) != 0) {
                addEmptySpacerCell(scrollingList);
                currentSlotIndex++;
            }
            for (MemberIconEntry entry : unassignedEntries) {
                listAddItemHandle.invoke(scrollingList, entry.button, entry.icon);
                orderedMembers.add(entry.member);
                lastPlacedColInRow = currentSlotIndex % totalCols;
                currentSlotIndex++;
            }
            while ((currentSlotIndex % totalCols) != 0) {
                addEmptySpacerCell(scrollingList);
                currentSlotIndex++;
            }
        }

        // If the fleet has more members than displayed (compact tooltip "+N" badge in bottom-right cell)
        // and the last row occupied the rightmost column, reserve an extra row so "+N" never overlaps a ship.
        int totalFleetMembers = fleet.getFleetData().getMembersListCopy().size();
        if (totalFleetMembers > allEntries.size() && lastPlacedColInRow == totalCols - 1) {
            for (int c = 0; c < totalCols; c++) {
                addEmptySpacerCell(scrollingList);
                currentSlotIndex++;
            }
        }

        membersList.clear();
        membersList.addAll(orderedMembers);

        int totalRows = Math.max(1, (currentSlotIndex + totalCols - 1) / totalCols);
        UIComponentAPI sComp = (UIComponentAPI) sPanelObj;
        UIComponentAPI vComp = (UIComponentAPI) scrollingList;
        float oldSHeight = sComp.getPosition().getHeight();
        float newSHeight = totalRows * itemH;
        float gridWidth = totalCols * itemW;

        vComp.getPosition().setSize(gridWidth, newSHeight);
        sComp.getPosition().setSize(gridWidth, newSHeight);

        float deltaH = newSHeight - oldSHeight;
        if (Math.abs(deltaH) > 0.5f) {
            PositionAPI cPos = contentsPanel.getPosition();
            cPos.setSize(cPos.getWidth(), cPos.getHeight() + deltaH);
            updateTooltipSizeHandle.invoke(standardTooltipV2);

            UIComponentAPI tooltipComp = (UIComponentAPI) standardTooltipV2;
            PositionAPI tPos = tooltipComp.getPosition();
            float screenH = Global.getSettings().getScreenHeight();
            if (tPos.getY() < 6f) {
                tPos.setLocation(tPos.getX(), 6f);
            } else if (tPos.getY() + tPos.getHeight() > screenH - 6f) {
                tPos.setLocation(tPos.getX(), Math.max(6f, screenH - 6f - tPos.getHeight()));
            }
        }
    }

    private void addEmptySpacerCell(Object scrollingList) throws Throwable {
        CustomPanelAPI spacer = Global.getSettings().createCustom(0f, 0f, null);
        listAddItemHandle.invoke(scrollingList, spacer, null);
        spacer.getPosition().setSize(0f, 0f);
    }

    private Color resolveDivisionCardColor(
            DivisionArchetype archetype,
            NomenclatureCategory nomCat,
            Color factionColor) {
        if (nomCat == NomenclatureCategory.ABYSSAL_ERROR) {
            return new Color(235, 55, 75, 255);
        }
        if (nomCat == NomenclatureCategory.THREAT_SWARM) {
            return new Color(175, 210, 155, 255);
        }
        if (nomCat == NomenclatureCategory.POST_AI_WAR) {
            return new Color(110, 225, 255, 255);
        }
        if (archetype == null) {
            return factionColor != null ? factionColor : Misc.getBasePlayerColor();
        }
        switch (archetype) {
            case BATTLESHIP_DIVISION:
                return new Color(255, 195, 75, 255);
            case FLEET_CARRIER_DIVISION:
            case LIGHT_CARRIER_DIVISION:
            case ESCORT_CARRIER_DIVISION:
            case CARRIER_GROUP:
                return new Color(135, 215, 255, 255);
            case CRUISER_DIVISION:
            case STRIKE_CRUISER_DIVISION:
                return new Color(110, 235, 190, 255);
            case DESTROYER_DIVISION:
            case MISSILE_DESTROYER_DIVISION:
                return new Color(155, 225, 120, 255);
            case FRIGATE_DIVISION:
                return new Color(120, 200, 255, 255);
            case PHASE_DIVISION:
                return new Color(205, 140, 255, 255);
            case SERVICE_GROUP:
            default:
                return new Color(185, 195, 205, 255);
        }
    }

    private void addExpandedDivisionHeaderCard(
            Object scrollingList,
            PermanentDivisionSpec spec,
            int divisionNumber,
            NomenclatureCategory nomCat,
            Color accentColor,
            float boxWidth,
            float boxHeight,
            float divisionSpanWidth) throws Throwable {

        String title = DivisionNomenclature.resolveDynamicDivisionDisplayName(
                nomCat,
                spec.archetype,
                divisionNumber,
                spec.name);

        Color textColor = accentColor != null ? accentColor : Misc.getBasePlayerColor();
        if (nomCat == NomenclatureCategory.ABYSSAL_ERROR) {
            textColor = Misc.getNegativeHighlightColor();
        } else if (nomCat == NomenclatureCategory.THREAT_SWARM) {
            textColor = new Color(210, 235, 195, 255);
        }

        DivisionHeaderCardPlugin plugin = new DivisionHeaderCardPlugin(
                accentColor,
                textColor,
                boxWidth,
                boxHeight,
                divisionSpanWidth);
        CustomPanelAPI card = Global.getSettings().createCustom(boxWidth, boxHeight, plugin);
        listAddItemHandle.invoke(scrollingList, card, null);
        card.getPosition().setSize(boxWidth, boxHeight);
        plugin.attachToPanel(card, title);

        if (nomCat == NomenclatureCategory.THREAT_SWARM) {
            activeThreatHeaders.add(new ThreatHeaderBinding(plugin, spec.archetype, divisionNumber));
        }
    }
}
