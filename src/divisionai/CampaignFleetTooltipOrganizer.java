package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
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
import java.util.Locale;
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
        final LabelAPI titleLabel;
        final DivisionArchetype archetype;
        final int divisionNumber;

        ThreatHeaderBinding(LabelAPI titleLabel, DivisionArchetype archetype, int divisionNumber) {
            this.titleLabel = titleLabel;
            this.archetype = archetype;
            this.divisionNumber = divisionNumber;
        }
    }

    private static final class DivisionHeaderCardPlugin extends BaseCustomUIPanelPlugin {
        private final CustomPanelAPI panel;
        private final Color accentColor;
        private final boolean isContinuation;

        DivisionHeaderCardPlugin(CustomPanelAPI panel, Color accentColor, boolean isContinuation) {
            this.panel = panel;
            this.accentColor = accentColor != null ? accentColor : Misc.getBasePlayerColor();
            this.isContinuation = isContinuation;
        }

        @Override
        public void renderBelow(float alphaMult) {
            if (panel == null) return;
            PositionAPI pos = panel.getPosition();
            if (pos == null) return;

            float x = pos.getX();
            float y = pos.getY();
            float w = pos.getWidth();
            float h = pos.getHeight();

            float r = accentColor.getRed() / 255f;
            float g = accentColor.getGreen() / 255f;
            float b = accentColor.getBlue() / 255f;

            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            // Subtle dark tactical card backdrop
            float bgAlpha = (isContinuation ? 0.35f : 0.68f) * alphaMult;
            GL11.glColor4f(0.03f, 0.06f, 0.10f, bgAlpha);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x, y + 2f);
            GL11.glVertex2f(x + w - 2f, y + 2f);
            GL11.glVertex2f(x + w - 2f, y + h - 2f);
            GL11.glVertex2f(x, y + h - 2f);
            GL11.glEnd();

            // Left vertical accent bar
            GL11.glColor4f(r, g, b, (isContinuation ? 0.50f : 0.92f) * alphaMult);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x, y + 2f);
            GL11.glVertex2f(x + 3.5f, y + 2f);
            GL11.glVertex2f(x + 3.5f, y + h - 2f);
            GL11.glVertex2f(x, y + h - 2f);
            GL11.glEnd();

            if (!isContinuation) {
                // Top and bottom bracket lines
                GL11.glLineWidth(1.0f);
                GL11.glColor4f(r, g, b, 0.48f * alphaMult);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex2f(x, y + h - 2f);
                GL11.glVertex2f(x + w - 2f, y + h - 2f);
                GL11.glVertex2f(x, y + 2f);
                GL11.glVertex2f(x + w - 2f, y + 2f);
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
                            if (b.titleLabel != null) {
                                b.titleLabel.setText(DivisionNomenclature.formatCorruptedThreatText(
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
        Color accentColor = (faction != null) ? faction.getBaseUIColor() : Misc.getBasePlayerColor();
        Map<String, FleetMemberAPI> fleetMembersById = new HashMap<>();
        for (FleetMemberAPI fm : fleet.getFleetData().getMembersListCopy()) {
            if (fm != null && fm.getId() != null) {
                fleetMembersById.put(fm.getId(), fm);
            }
        }

        listClearHandle.invoke(scrollingList, false);
        List<FleetMemberAPI> orderedMembers = new ArrayList<>(allEntries.size());
        int currentSlotIndex = 0;
        long timeBucket = System.currentTimeMillis() / 240L;
        lastThreatTimeBucket = timeBucket;

        for (int dIdx = 0; dIdx < nonEmptySpecs.size(); dIdx++) {
            PermanentDivisionSpec spec = nonEmptySpecs.get(dIdx);
            List<MemberIconEntry> divEntries = entriesPerDivision.get(dIdx);

            int totalDivShips = 0;
            int totalDivDp = 0;
            for (String mId : spec.memberIds) {
                FleetMemberAPI fm = fleetMembersById.get(mId);
                if (fm != null) {
                    totalDivShips++;
                    totalDivDp += Math.round(fm.getDeploymentPointsCost());
                }
            }
            if (totalDivShips == 0) {
                totalDivShips = divEntries.size();
            }

            int idx = 0;
            boolean firstRowOfDiv = true;
            while (idx < divEntries.size()) {
                while ((currentSlotIndex % totalCols) != 0) {
                    addEmptySpacerCell(scrollingList, itemW, itemH);
                    currentSlotIndex++;
                }

                CustomPanelAPI headerCard = createDivisionRowHeaderCard(
                        spec,
                        dIdx + 1,
                        nomCat,
                        accentColor,
                        totalDivShips,
                        totalDivDp,
                        itemW * HEADER_COLS,
                        itemH,
                        !firstRowOfDiv,
                        timeBucket);
                listAddItemHandle.invoke(scrollingList, headerCard, null);
                headerCard.getPosition().setSize(itemW * HEADER_COLS - 3f, itemH - 2f);
                currentSlotIndex++;

                for (int hc = 1; hc < HEADER_COLS; hc++) {
                    CustomPanelAPI zeroSpacer = Global.getSettings().createCustom(0f, 0f, null);
                    listAddItemHandle.invoke(scrollingList, zeroSpacer, null);
                    zeroSpacer.getPosition().setSize(0f, 0f);
                    currentSlotIndex++;
                }
                firstRowOfDiv = false;

                int countInRow = 0;
                while (idx < divEntries.size() && countInRow < shipsPerDivRow) {
                    MemberIconEntry entry = divEntries.get(idx++);
                    listAddItemHandle.invoke(scrollingList, entry.button, entry.icon);
                    orderedMembers.add(entry.member);
                    currentSlotIndex++;
                    countInRow++;
                }

                while ((currentSlotIndex % totalCols) != 0) {
                    addEmptySpacerCell(scrollingList, itemW, itemH);
                    currentSlotIndex++;
                }
            }
        }

        // Render unassigned ships (and fighter wings) as normal in the last row(s) starting at column 0!
        if (!unassignedEntries.isEmpty()) {
            while ((currentSlotIndex % totalCols) != 0) {
                addEmptySpacerCell(scrollingList, itemW, itemH);
                currentSlotIndex++;
            }
            for (MemberIconEntry entry : unassignedEntries) {
                listAddItemHandle.invoke(scrollingList, entry.button, entry.icon);
                orderedMembers.add(entry.member);
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

    private void addEmptySpacerCell(Object scrollingList, float itemW, float itemH) throws Throwable {
        CustomPanelAPI spacer = Global.getSettings().createCustom(itemW, itemH, null);
        listAddItemHandle.invoke(scrollingList, spacer, null);
        spacer.getPosition().setSize(itemW, itemH);
    }

    private CustomPanelAPI createDivisionRowHeaderCard(
            PermanentDivisionSpec spec,
            int divisionNumber,
            NomenclatureCategory nomCat,
            Color accentColor,
            int shipCount,
            int totalDp,
            float width,
            float height,
            boolean isContinuation,
            long timeBucket) {

        CustomPanelAPI cardHolder = Global.getSettings().createCustom(width - 3f, height - 2f, null);
        CustomPanelAPI card = Global.getSettings().createCustom(
                width - 3f,
                height - 2f,
                new DivisionHeaderCardPlugin(cardHolder, accentColor, isContinuation));
        card.addComponent(cardHolder).inTL(0f, 0f);
        TooltipMakerAPI tm = card.createUIElement(width - 3f, height - 2f, false);

        if (isContinuation) {
            tm.setParaSmallInsignia();
            String contText = (nomCat == NomenclatureCategory.ABYSSAL_ERROR)
                    ? "ERROR"
                    : "  ↳ CONT.";
            LabelAPI lbl = tm.addPara(contText, 0f, Misc.getGrayColor());
            lbl.getPosition().inTL(8f, Math.max(4f, (height - 18f) * 0.5f));
        } else {
            tm.setParaSmallInsignia();
            String title = DivisionNomenclature.resolveDynamicDivisionDisplayName(
                    nomCat,
                    spec.archetype,
                    divisionNumber,
                    spec.name);
            if (title.length() > 17 && nomCat == NomenclatureCategory.HUMAN_NAVAL) {
                title = title.substring(0, 16) + ".";
            }

            Color titleColor = accentColor != null ? accentColor : Misc.getBasePlayerColor();
            if (nomCat == NomenclatureCategory.ABYSSAL_ERROR) {
                titleColor = Misc.getNegativeHighlightColor();
            } else if (nomCat == NomenclatureCategory.THREAT_SWARM) {
                titleColor = new Color(210, 235, 195, 255);
            }

            LabelAPI titleLbl = tm.addPara(title, 0f, titleColor);
            titleLbl.getPosition().inTL(6f, 6f);

            if (nomCat == NomenclatureCategory.THREAT_SWARM) {
                activeThreatHeaders.add(new ThreatHeaderBinding(titleLbl, spec.archetype, divisionNumber));
            }

            String subText;
            Color subColor;
            if (nomCat == NomenclatureCategory.ABYSSAL_ERROR) {
                subText = "ERROR";
                subColor = Misc.getNegativeHighlightColor();
            } else {
                subText = String.format(
                        Locale.ROOT,
                        "%d %s • %d DP",
                        shipCount,
                        shipCount == 1 ? "SHIP" : "SHIPS",
                        totalDp);
                subColor = Misc.getHighlightColor();
            }

            LabelAPI subLbl = tm.addPara(subText, 0f, subColor);
            subLbl.getPosition().inTL(6f, 24f);
        }

        tm.setHeightSoFar(height - 2f);
        card.addUIElement(tm).inTL(0f, 0f);
        return card;
    }
}
