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
 * the "Division Editor" button on the left side of the Fleet screen.
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
                LOG.warn("[DivisionAI] Error updating Fleet tab Division Editor button", t);
            }
        }
    }

    private boolean resolveMethodHandlesIfNeeded(CampaignUIAPI campaignUI) {
        if (handlesResolved) return true;
        if (handleResolutionFailed) return false;

        try {
            ClassLoader cl = campaignUI.getClass().getClassLoader();
            Class<?> csClass = Class.forName("com.fs.starfarer.campaign.CampaignState", false, cl);
            Class<?> lClass = Class.forName("com.fs.starfarer.ui.newui.L", false, cl);
            Class<?> dialogClass = Class.forName("com.fs.starfarer.ui.newui.o0Oo", false, cl);
            Class<?> ifNewClass = Class.forName("com.fs.starfarer.ui.interfacenew", false, cl);

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
            LOG.warn("[DivisionAI] Failed to resolve CampaignUI MethodHandles for Division Editor button", t);
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
            return;
        }

        float btnW = autoAssignButton.getPosition().getWidth();
        float btnH = autoAssignButton.getPosition().getHeight();
        float panelH = btnH * 2f + 8f;

        injectedButtonPanel = Global.getSettings().createCustom(btnW, panelH, new BaseCustomUIPanelPlugin());
        TooltipMakerAPI tt = injectedButtonPanel.createUIElement(btnW, panelH, false);
        divisionEditorButton = tt.addButton(
                "Division Editor",
                "OPEN_DIVISION_EDITOR",
                Misc.getBasePlayerColor(),
                Misc.getDarkPlayerColor(),
                Alignment.MID,
                CutStyle.TL_BR,
                btnW,
                btnH,
                0f);
        fleetDoctrineButton = tt.addButton(
                "Fleet Doctrine",
                "OPEN_FLEET_DOCTRINE",
                Misc.getBasePlayerColor(),
                Misc.getDarkPlayerColor(),
                Alignment.MID,
                CutStyle.TL_BR,
                btnW,
                btnH,
                6f);
        injectedButtonPanel.addUIElement(tt).inTL(-5f, 0f);

        // Compute top-left Y offset below "Auto-assign idle officers" (and below AoTD's "Talk to your officers" if present)
        boolean aotdVokPresent = Global.getSettings().getModManager().isModEnabled("aotd_vok");
        float extraOffset = aotdVokPresent ? 132f : 100f;
        float topY = Global.getSettings().getScreenHeight() - autoAssignButton.getPosition().getY() + extraOffset;

        leftControlPanel.addComponent(injectedButtonPanel).inTL(0f, topY);
        LOG.info("[DivisionAI] Injected 'Division Editor' and 'Fleet Doctrine' buttons onto Fleet tab.");
    }
}
