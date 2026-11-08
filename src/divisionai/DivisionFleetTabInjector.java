package divisionai;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/**
 * Campaign EveryFrameScript that watches the Fleet screen (CoreUITabId.FLEET) and injects
 * "Fleet Doctrine" (on top) and "Fleet Organization" (below) on the left side of the Fleet screen.
 * Uses java.lang.invoke.MethodHandles (never java.lang.reflect.*) so Starsector's script
 * classloader never blocks reflection.
 */
public class DivisionFleetTabInjector implements EveryFrameScript {

    private static final Logger LOG = Global.getLogger(DivisionFleetTabInjector.class);

    private transient boolean handlesResolved = false;
    private transient boolean handleResolutionFailed = false;
    private transient MethodHandle getCoreHandle;
    private transient MethodHandle getEncounterDialogHandle;
    private transient MethodHandle getDialogCoreUIHandle;
    private transient MethodHandle getCurrentTabHandle;
    private transient MethodHandle getChildrenCopyHandle;

    private transient CustomPanelAPI injectedButtonPanel;
    private transient float lastInjectedTopY = 0f;
    private transient ButtonAPI divisionEditorButton;
    private transient ButtonAPI fleetDoctrineButton;
    private transient DivisionEditorDialog activeDialog;
    private transient FleetDoctrineDialog activeDoctrineDialog;
    private transient CampaignFleetTooltipOrganizer fleetTooltipOrganizer;

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return true;
    }

    @Override
    public void advance(float amount) {
        SectorAPI sector = Global.getSector();
        if (sector == null) return;
        CampaignUIAPI campaignUI = sector.getCampaignUI();
        if (campaignUI == null) return;

        if (fleetTooltipOrganizer == null) {
            fleetTooltipOrganizer = new CampaignFleetTooltipOrganizer();
        }
        fleetTooltipOrganizer.advance();
        DivisionAISettings.advanceLunaSettingsReinitButton();
        DynamicBattleMusicController.ensureCleanedUpOutsideCombat();

        if (!CoreUITabId.FLEET.equals(campaignUI.getCurrentCoreTab())) {
            if (activeDialog != null && !activeDialog.isClosed()) {
                activeDialog.close();
            }
            activeDialog = null;
            if (activeDoctrineDialog != null && !activeDoctrineDialog.isClosed()) {
                activeDoctrineDialog.close();
            }
            activeDoctrineDialog = null;
            return;
        }

        if (!resolveMethodHandlesIfNeeded(campaignUI)) {
            return;
        }

        try {
            UIPanelAPI coreUI = getCoreUIPanel(campaignUI);
            if (coreUI == null) return;

            UIPanelAPI currentTab = (UIPanelAPI) getCurrentTabHandle.invoke(coreUI);
            if (currentTab == null) return;

            ensureButtonInjectedIntoFleetTab(coreUI, currentTab);

            boolean anyModalOpen = (activeDialog != null && !activeDialog.isClosed())
                    || (activeDoctrineDialog != null && !activeDoctrineDialog.isClosed());

            if (divisionEditorButton != null && divisionEditorButton.isChecked()) {
                divisionEditorButton.setChecked(false);
                if (!anyModalOpen) {
                    activeDialog = new DivisionEditorDialog(coreUI);
                    activeDialog.show();
                }
            }
            if (fleetDoctrineButton != null && fleetDoctrineButton.isChecked()) {
                fleetDoctrineButton.setChecked(false);
                if (!anyModalOpen) {
                    activeDoctrineDialog = new FleetDoctrineDialog(coreUI);
                    activeDoctrineDialog.show();
                }
            }
        } catch (Throwable t) {
            if (!handleResolutionFailed) {
                handleResolutionFailed = true;
                LOG.warn("[DivisionAI] Error updating Fleet tab Fleet Organization button", t);
            }
        }
    }

    private boolean resolveMethodHandlesIfNeeded(CampaignUIAPI campaignUI) {
        if (handlesResolved) return true;
        if (handleResolutionFailed) return false;

        try {
            ClassLoader cl = campaignUI.getClass().getClassLoader();
            Class<?> csClass = Class.forName("com.fs.starfarer.campaign.CampaignState", false, cl);
            Class<?> lClass = ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                    cl, "com.fs.starfarer.ui.newui.L", csClass.getName(), "getCore");
            Class<?> dialogClass = ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                    cl, "com.fs.starfarer.ui.newui.o0Oo", csClass.getName(), "getEncounterDialog");
            Class<?> ifNewClass = ObfuscatedBytecodeBridge.resolveClassOrMethodReturn(
                    cl, "com.fs.starfarer.ui.interfacenew", csClass.getName(), "getScreenPanel");

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            getCoreHandle = lookup.findVirtual(csClass, "getCore", MethodType.methodType(lClass));
            getEncounterDialogHandle = lookup.findVirtual(csClass, "getEncounterDialog", MethodType.methodType(dialogClass));
            getDialogCoreUIHandle = lookup.findVirtual(dialogClass, "getCoreUI", MethodType.methodType(lClass));
            getCurrentTabHandle = lookup.findVirtual(lClass, "getCurrentTab", MethodType.methodType(ifNewClass));
            getChildrenCopyHandle = lookup.findVirtual(ifNewClass, "getChildrenCopy", MethodType.methodType(List.class));

            handlesResolved = true;
            return true;
        } catch (Throwable t) {
            handleResolutionFailed = true;
            LOG.warn("[DivisionAI] Failed to resolve CampaignUI MethodHandles for Fleet Organization button", t);
            return false;
        }
    }

    private UIPanelAPI getCoreUIPanel(CampaignUIAPI campaignUI) throws Throwable {
        Object dialog = getEncounterDialogHandle.invoke(campaignUI);
        if (dialog != null) {
            Object dialogCore = getDialogCoreUIHandle.invoke(dialog);
            if (dialogCore instanceof UIPanelAPI) {
                return (UIPanelAPI) dialogCore;
            }
        }
        Object core = getCoreHandle.invoke(campaignUI);
        if (core instanceof UIPanelAPI) {
            return (UIPanelAPI) core;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void ensureButtonInjectedIntoFleetTab(UIPanelAPI coreUI, UIPanelAPI fleetTab) throws Throwable {
        List<UIComponentAPI> tabChildren = (List<UIComponentAPI>) getChildrenCopyHandle.invoke(fleetTab);
        if (tabChildren == null) return;

        UIPanelAPI leftControlPanel = null;
        ButtonAPI autoAssignButton = null;

        for (UIComponentAPI child : tabChildren) {
            if (!(child instanceof UIPanelAPI)) continue;
            UIPanelAPI candidatePanel = (UIPanelAPI) child;
            List<UIComponentAPI> subChildren = (List<UIComponentAPI>) getChildrenCopyHandle.invoke(candidatePanel);
            if (subChildren == null) continue;

            for (UIComponentAPI sub : subChildren) {
                if (sub instanceof ButtonAPI) {
                    ButtonAPI btn = (ButtonAPI) sub;
                    String text = btn.getText();
                    if (text != null && text.contains("Auto-assign idle officers")) {
                        leftControlPanel = candidatePanel;
                        autoAssignButton = btn;
                        break;
                    }
                }
            }
            if (leftControlPanel != null) break;
        }

        if (leftControlPanel == null || autoAssignButton == null) {
            return;
        }

        List<UIComponentAPI> leftChildren = (List<UIComponentAPI>) getChildrenCopyHandle.invoke(leftControlPanel);
        if (injectedButtonPanel != null && leftChildren != null && leftChildren.contains(injectedButtonPanel)) {
            float[] topYHolder = new float[]{lastInjectedTopY};
            if (alignPanelBelowSidebarSiblings(
                    leftControlPanel, autoAssignButton, injectedButtonPanel, leftChildren,
                    getChildrenCopyHandle, topYHolder, false)) {
                lastInjectedTopY = topYHolder[0];
            }
            return;
        }

        float btnW = autoAssignButton.getPosition().getWidth();
        float btnH = autoAssignButton.getPosition().getHeight();
        float rowGap = 6f;
        float panelH = btnH * 2f + rowGap + 2f;
        float iconW = 38f;
        float iconH = btnH;
        float iconGap = 6f;
        float actionBtnW = Math.max(140f, btnW - iconW - iconGap);

        String starIconPath = Global.getSettings().getSpriteName("division_ai", "icon_gold-star");
        String wingIconPath = Global.getSettings().getSpriteName("division_ai", "icon_gold-wing");

        injectedButtonPanel = Global.getSettings().createCustom(btnW, panelH, new BaseCustomUIPanelPlugin());
        TooltipMakerAPI tt = injectedButtonPanel.createUIElement(btnW, panelH, false);

        tt.addImage(starIconPath, iconW, iconH, 0f);
        UIComponentAPI starIconComp = tt.getPrev();
        if (starIconComp != null) {
            starIconComp.getPosition().inTL(5f, 0f);
        }

        fleetDoctrineButton = tt.addButton(
                "Fleet Doctrine",
                "OPEN_FLEET_DOCTRINE",
                Misc.getBasePlayerColor(),
                Misc.getDarkPlayerColor(),
                Alignment.MID,
                CutStyle.TL_BR,
                actionBtnW,
                btnH,
                0f);
        fleetDoctrineButton.getPosition().inTL(5f + iconW + iconGap, 0f);
        DivisionUiWidgets.addTooltip(
                tt,
                fleetDoctrineButton,
                "Fleet Doctrine & Battle Group Command",
                "Configure Officer in Tactical Command (OTC) authority, fleet-wide formations, doctrinal division modifiers, auto-assign schemes, hull limits, and division nomenclature.");

        float row2Y = btnH + rowGap;
        tt.addImage(wingIconPath, iconW, iconH, 0f);
        UIComponentAPI wingIconComp = tt.getPrev();
        if (wingIconComp != null) {
            wingIconComp.getPosition().inTL(5f, row2Y);
        }

        divisionEditorButton = tt.addButton(
                "Fleet Organization",
                "OPEN_DIVISION_EDITOR",
                Misc.getBasePlayerColor(),
                Misc.getDarkPlayerColor(),
                Alignment.MID,
                CutStyle.TL_BR,
                actionBtnW,
                btnH,
                0f);
        divisionEditorButton.getPosition().inTL(5f + iconW + iconGap, row2Y);
        DivisionUiWidgets.addTooltip(
                tt,
                divisionEditorButton,
                "Fleet Organization (Divisions)",
                "Organize your fleet into permanent tactical divisions, designate Division Leaders, and customize individual division missions, formations, spacing, and standoff ranges.");

        injectedButtonPanel.addUIElement(tt).inTL(-5f, 0f);

        // Initial baseline placement below "Auto-assign idle officers", followed immediately by dynamic
        // bounding-box alignment below all existing sidebar elements (Fleet composition data, disengage text,
        // RAT artifact slot, AoTD officer button, FleetBuilder search/filter bars, etc.).
        boolean aotdVokPresent = Global.getSettings().getModManager().isModEnabled("aotd_vok");
        float extraOffset = aotdVokPresent ? 132f : 100f;
        lastInjectedTopY = Global.getSettings().getScreenHeight() - autoAssignButton.getPosition().getY() + extraOffset;

        leftControlPanel.addComponent(injectedButtonPanel).inTL(0f, lastInjectedTopY);
        float[] topYHolder = new float[]{lastInjectedTopY};
        if (alignPanelBelowSidebarSiblings(
                leftControlPanel, autoAssignButton, injectedButtonPanel, leftChildren,
                getChildrenCopyHandle, topYHolder, true)) {
            lastInjectedTopY = topYHolder[0];
        }
        LOG.info("[DivisionAI] Injected 'Fleet Doctrine' and 'Fleet Organization' buttons onto Fleet tab.");
    }

    /**
     * Dynamically aligns {@code injectedPanel} below all sidebar UI elements in {@code leftControlPanel}
     * (including vanilla Fleet composition data & disengage status label, Random Assortment of Things artifact box,
     * Second-in-Command: Auxiliaries expanded composition rows, AoTD officer button, and FleetBuilder / Ship Filter
     * search and sort controls).
     *
     * On {@code initialPlacement == true}, places {@code injectedPanel} 10px below the lowest sidebar element at or
     * below {@code autoAssignButton}. On subsequent frames ({@code initialPlacement == false}), if a mod script
     * (such as RAT's ArtifactUIScript, which waits for sector pause) injects or shifts elements downward so that any
     * sibling overlaps {@code injectedPanel}, automatically shifts {@code injectedPanel} down below the entire
     * contiguous sidebar stack while ignoring widgets placed cleanly below {@code injectedPanel} (preventing leapfrogging).
     */
    @SuppressWarnings("unchecked")
    public static boolean alignPanelBelowSidebarSiblings(
            UIPanelAPI leftControlPanel,
            ButtonAPI autoAssignButton,
            UIComponentAPI injectedPanel,
            List<UIComponentAPI> leftChildren,
            MethodHandle childrenHandle,
            float[] inTlTopYRef,
            boolean initialPlacement) {
        if (leftControlPanel == null || autoAssignButton == null || injectedPanel == null || leftChildren == null) {
            return false;
        }
        com.fs.starfarer.api.ui.PositionAPI myPos = injectedPanel.getPosition();
        com.fs.starfarer.api.ui.PositionAPI autoPos = autoAssignButton.getPosition();
        if (myPos == null || autoPos == null) return false;

        float autoW = Math.max(220f, autoPos.getWidth());
        float colMinX = autoPos.getX() - 35f;
        float colMaxX = autoPos.getX() + autoW + 60f;
        float maxCompW = Math.max(380f, autoW * 1.70f);
        float autoTopY = autoPos.getY() + autoPos.getHeight();

        // Collect [bottomY, topY] intervals of all valid sidebar components at or below autoAssignButton
        java.util.List<float[]> intervals = new java.util.ArrayList<>();
        intervals.add(new float[]{autoPos.getY(), autoTopY});

        for (int i = 0; i < leftChildren.size(); i++) {
            UIComponentAPI c = leftChildren.get(i);
            if (c == null || c == injectedPanel || c == autoAssignButton) continue;
            collectSidebarComponentBounds(c, injectedPanel, colMinX, colMaxX, maxCompW, autoTopY, childrenHandle, intervals, 0);
        }

        float myBottomY = myPos.getY();
        float myTopY = myBottomY + myPos.getHeight();
        float lowestBottomY = autoPos.getY();

        if (initialPlacement) {
            for (int i = 0; i < intervals.size(); i++) {
                float[] iv = intervals.get(i);
                if (iv[0] < lowestBottomY) {
                    lowestBottomY = iv[0];
                }
            }
        } else {
            // Check if any sibling component overlaps (or crowds within 8px of) injectedPanel
            boolean anyOverlap = false;
            float sweepBottomY = myBottomY;
            for (int i = 0; i < intervals.size(); i++) {
                float[] iv = intervals.get(i);
                float cBottom = iv[0];
                float cTop = iv[1];
                if (cBottom < myTopY + 8f && cTop > myBottomY - 8f) {
                    anyOverlap = true;
                    if (cBottom < sweepBottomY) {
                        sweepBottomY = cBottom;
                    }
                }
            }
            if (!anyOverlap) {
                return false;
            }
            // Expand downward through any contiguous sidebar elements below the overlapping component(s)
            boolean expanded = true;
            while (expanded) {
                expanded = false;
                for (int i = 0; i < intervals.size(); i++) {
                    float[] iv = intervals.get(i);
                    float cBottom = iv[0];
                    float cTop = iv[1];
                    if (cTop >= sweepBottomY - 36f && cBottom < sweepBottomY) {
                        sweepBottomY = cBottom;
                        expanded = true;
                    }
                }
            }
            lowestBottomY = sweepBottomY;
        }

        float gap = 10f;
        float desiredTopScreenY = lowestBottomY - gap;
        if (desiredTopScreenY - myPos.getHeight() < 10f) {
            desiredTopScreenY = 10f + myPos.getHeight();
        }
        float deltaDown = myTopY - desiredTopScreenY;
        if (deltaDown > 1.5f) {
            float baseInTlY = (inTlTopYRef != null && inTlTopYRef.length > 0)
                    ? inTlTopYRef[0]
                    : ((leftControlPanel.getPosition() != null && leftControlPanel.getPosition().getHeight() > 100f)
                            ? (leftControlPanel.getPosition().getY() + leftControlPanel.getPosition().getHeight() - myTopY)
                            : 100f);
            float newTopY = Math.max(0f, baseInTlY + deltaDown);
            if (inTlTopYRef != null && inTlTopYRef.length > 0) {
                inTlTopYRef[0] = newTopY;
            }
            myPos.inTL(0f, newTopY);
            return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void collectSidebarComponentBounds(
            UIComponentAPI comp,
            UIComponentAPI injectedPanel,
            float colMinX,
            float colMaxX,
            float maxCompW,
            float autoTopY,
            MethodHandle childrenHandle,
            java.util.List<float[]> outIntervals,
            int depth) {
        if (comp == null || comp == injectedPanel) return;
        com.fs.starfarer.api.ui.PositionAPI p = comp.getPosition();
        if (p != null) {
            float w = p.getWidth();
            float h = p.getHeight();
            float x = p.getX();
            float y = p.getY();
            if (w > 2f && w <= maxCompW && h > 2f && h <= 480f
                    && (x + w) >= colMinX && x <= colMaxX
                    && y <= autoTopY + 14f && y >= 10f) {
                outIntervals.add(new float[]{y, y + h});
            }
        }
        if (depth < 2 && childrenHandle != null && comp instanceof UIPanelAPI) {
            try {
                List<UIComponentAPI> sub = (List<UIComponentAPI>) childrenHandle.invoke(comp);
                if (sub != null) {
                    for (int i = 0; i < sub.size(); i++) {
                        collectSidebarComponentBounds(
                                sub.get(i), injectedPanel, colMinX, colMaxX, maxCompW,
                                autoTopY, childrenHandle, outIntervals, depth + 1);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
