package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import org.apache.log4j.Logger;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain-language interactive guided tour across the Fleet screen, Fleet Doctrine, and Fleet Organization
 * (saved per-campaign via {@link DivisionAISharedData}).
 *
 * <p>Tour walkthrough:
 * <ol>
 *   <li><b>Step 0 (Fleet Sidebar)</b>: Invites the player to open <b>Fleet Doctrine</b>.</li>
 *   <li><b>Step 1 (Fleet Doctrine - 1a)</b>: Explains that the fleet is already using Sector-standard
 *       organization and tactics inherited from the Domain and nothing needs to be changed.</li>
 *   <li><b>Step 2 (Fleet Doctrine - 1b)</b>: Explains that Fleet Doctrine settings are inherited by
 *       active divisions in the <b>Fleet Organization</b> screen, then guides the player there.</li>
 *   <li><b>Step 3 (Fleet Organization - 2)</b>: Asks the player to hit <b>Auto-Assign</b> (without forcing
 *       manual ship rearrangement) and explains that divisions inherit parameters from Doctrine by default
 *       or can be tuned individually.</li>
 * </ol>
 */
public class DivisionOnboardingOverlay extends BaseCustomUIPanelPlugin {

    private static final Logger LOG = Global.getLogger(DivisionOnboardingOverlay.class);
    public static final float CARD_WIDTH = 464f;
    public static final float SETTLE_DELAY_SECONDS = 0.28f;

    public static final int STEP_FLEET_INTRO = 0;
    public static final int STEP_DOCTRINE_COMMAND = 1;
    public static final int STEP_DOCTRINE_DEFAULTS = 2;
    public static final int STEP_DOCTRINE_CREATION = 3;
    public static final int STEP_DOCTRINE_HULLS = 4;
    public static final int STEP_DOCTRINE_NAMING = 5;
    public static final int STEP_ORG_OVERVIEW = 6;
    public static final int STEP_ORG_CONFIGURE = 7;
    public static final int TOTAL_STEPS = 8;

    // Legacy aliases preserved for external callers/tests
    public static final int STEP_DOCTRINE_1A = STEP_DOCTRINE_COMMAND;
    public static final int STEP_DOCTRINE_1B = STEP_DOCTRINE_DEFAULTS;
    public static final int STEP_ORGANIZATION_2 = STEP_ORG_OVERVIEW;

    public interface TourNavigator {
        void openFleetDoctrine();
        void closeFleetDoctrine();
        FleetDoctrineDialog getActiveDoctrineDialog();
        void openFleetOrganization();
        void closeFleetOrganization();
        DivisionEditorDialog getActiveOrganizationDialog();
    }

    private static DivisionOnboardingOverlay activeInstance = null;

    public static DivisionOnboardingOverlay getActiveInstance() {
        return (activeInstance != null && !activeInstance.closed) ? activeInstance : null;
    }

    public static boolean isTourActiveOnAutoAssign() {
        DivisionOnboardingOverlay inst = getActiveInstance();
        return inst != null && inst.stepIndex == STEP_ORG_OVERVIEW;
    }

    public static void notifyTourAutoAssignPressed() {
        DivisionOnboardingOverlay inst = getActiveInstance();
        if (inst != null) {
            inst.onAutoAssignExecuted();
        }
    }

    private final UIPanelAPI hostCoreUI;
    private UIComponentAPI injectedPanelRef;
    private ButtonAPI doctrineButtonRef;
    private ButtonAPI organizationButtonRef;
    private TourNavigator navigator;

    private CustomPanelAPI rootCardPanel;
    private CustomPanelAPI innerContentPanel;
    private final Map<ButtonAPI, String> activeButtons = new LinkedHashMap<>();
    private String pendingAction = null;

    private int stepIndex = STEP_FLEET_INTRO;
    private boolean autoAssignPressed = false;
    private boolean lastDialogOpenSnapshot = false;
    private boolean lastOrgConfigureSnapshot = false;
    private int lastUnassignedCountSnapshot = -1;
    private float cardHeight = 168f;
    private float pulsePhase = 0f;
    private boolean closed = false;

    public DivisionOnboardingOverlay(
            UIPanelAPI hostCoreUI,
            UIComponentAPI injectedPanel,
            ButtonAPI doctrineButton,
            ButtonAPI organizationButton) {
        this(hostCoreUI, injectedPanel, doctrineButton, organizationButton, null);
    }

    public DivisionOnboardingOverlay(
            UIPanelAPI hostCoreUI,
            UIComponentAPI injectedPanel,
            ButtonAPI doctrineButton,
            ButtonAPI organizationButton,
            TourNavigator navigator) {
        this.hostCoreUI = hostCoreUI;
        this.injectedPanelRef = injectedPanel;
        this.doctrineButtonRef = doctrineButton;
        this.organizationButtonRef = organizationButton;
        this.navigator = navigator;
    }

    public void setNavigator(TourNavigator navigator) {
        this.navigator = navigator;
    }

    /**
     * Checks whether all conflict-free conditions are met to auto-start the soft Fleet-tab tour.
     */
    @SuppressWarnings("unchecked")
    public static boolean canAutoStartOnFleetTab(
            SectorAPI sector,
            CampaignUIAPI campaignUI,
            UIPanelAPI coreUI,
            UIPanelAPI currentTab,
            UIComponentAPI injectedPanel,
            MethodHandle getChildrenCopyHandle) {
        if (DivisionAISharedData.isFleetIntroCompleted()) {
            return false;
        }
        if (sector == null || campaignUI == null || coreUI == null || injectedPanel == null) {
            return false;
        }
        if (!CoreUITabId.FLEET.equals(campaignUI.getCurrentCoreTab())) {
            return false;
        }
        // Note: campaignUI.isShowingDialog() is ALWAYS true when CoreUITabId.FLEET is open (dialogType == CORE_UI),
        // so only suppress when the Escape menu is open.
        if (campaignUI.isShowingMenu()) {
            return false;
        }
        PositionAPI p = injectedPanel.getPosition();
        if (p == null || p.getWidth() <= 10f || p.getHeight() <= 10f) {
            return false;
        }
        try {
            CampaignFleetAPI pf = sector.getPlayerFleet();
            if (pf != null && pf.getFleetData() != null && pf.getFleetData().getMembersListCopy().isEmpty()) {
                return false;
            }
        } catch (Throwable ignored) {
        }

        // Ensure no mod modal dialog/sub-panel (CustomPanelAPI >= 420x280px) is currently mounted on coreUI.
        // Native CoreUI panels (e.g. TriPad frame com.fs.starfarer.ui.newui.X) do not implement CustomPanelAPI.
        if (getChildrenCopyHandle != null) {
            try {
                List<UIComponentAPI> coreChildren = (List<UIComponentAPI>) getChildrenCopyHandle.invoke(coreUI);
                if (coreChildren != null) {
                    for (UIComponentAPI c : coreChildren) {
                        if (c == null || c == currentTab || !(c instanceof CustomPanelAPI)) continue;
                        PositionAPI cp = c.getPosition();
                        if (cp != null && cp.getWidth() >= 420f && cp.getHeight() >= 280f) {
                            return false;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        return true;
    }

    public void updateTargetRefs(
            UIComponentAPI injectedPanel,
            ButtonAPI doctrineButton,
            ButtonAPI organizationButton) {
        this.injectedPanelRef = injectedPanel;
        this.doctrineButtonRef = doctrineButton;
        this.organizationButtonRef = organizationButton;
    }

    public boolean isClosed() {
        return closed;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public boolean isAutoAssignPressed() {
        return autoAssignPressed;
    }

    public CustomPanelAPI getRootCardPanel() {
        return rootCardPanel;
    }

    public void bringToTop() {
        if (!closed && hostCoreUI != null && rootCardPanel != null) {
            try {
                hostCoreUI.bringComponentToTop(rootCardPanel);
            } catch (Throwable ignored) {
            }
        }
    }

    public void onPlayerOpenedDoctrine() {
        if (closed) return;
        if (stepIndex == STEP_FLEET_INTRO || stepIndex >= STEP_ORG_OVERVIEW) {
            stepIndex = STEP_DOCTRINE_COMMAND;
        }
        syncDoctrineTabForCurrentStep();
        lastDialogOpenSnapshot = isExpectedDialogOpen();
        rebuildCardContent();
        updateLiveAnchorPosition();
        bringToTop();
    }

    public void onPlayerOpenedOrganization() {
        if (closed) return;
        if (stepIndex < STEP_ORG_OVERVIEW) {
            stepIndex = STEP_ORG_OVERVIEW;
        }
        DivisionEditorDialog org = getOpenOrganizationDialog();
        lastOrgConfigureSnapshot = org != null && org.isShowingConfigureDivision();
        lastDialogOpenSnapshot = isExpectedDialogOpen();
        rebuildCardContent();
        updateLiveAnchorPosition();
        bringToTop();
    }

    public void onAutoAssignExecuted() {
        if (closed) return;
        autoAssignPressed = true;
        rebuildCardContent();
        updateLiveAnchorPosition();
        bringToTop();
    }

    public void show() {
        if (hostCoreUI == null) return;
        closed = false;
        activeInstance = this;
        rootCardPanel = Global.getSettings().createCustom(CARD_WIDTH, cardHeight, this);
        hostCoreUI.addComponent(rootCardPanel).inTL(320f, 320f);
        hostCoreUI.bringComponentToTop(rootCardPanel);
        lastDialogOpenSnapshot = isExpectedDialogOpen();
        lastUnassignedCountSnapshot = countUnassignedShipsSafe();
        rebuildCardContent();
        updateLiveAnchorPosition();
        LOG.info("[DivisionAI] Started Fleet Intro Tour on Fleet tab.");
    }

    public void close(boolean markCompleted) {
        if (closed) return;
        closed = true;
        if (activeInstance == this) {
            activeInstance = null;
        }
        if (markCompleted) {
            DivisionAISharedData.markFleetIntroCompleted();
        }
        activeButtons.clear();
        if (hostCoreUI != null && rootCardPanel != null) {
            try {
                hostCoreUI.removeComponent(rootCardPanel);
            } catch (Throwable ignored) {
            }
        }
        rootCardPanel = null;
        innerContentPanel = null;
    }

    private final BaseCustomUIPanelPlugin subPanelPlugin = new BaseCustomUIPanelPlugin() {
        @Override
        public void buttonPressed(Object buttonId) {
            DivisionOnboardingOverlay.this.buttonPressed(buttonId);
        }
    };

    private ButtonAPI registerButton(TooltipMakerAPI host, ButtonAPI btn, String actionId) {
        if (btn != null && actionId != null) {
            activeButtons.put(btn, actionId);
            DivisionUiWidgets.attachActionTooltip(host, btn, actionId);
        }
        return btn;
    }

    @Override
    public void buttonPressed(Object buttonId) {
        if (buttonId instanceof String) {
            pendingAction = (String) buttonId;
        }
    }

    private boolean isAnyDivisionModalOpen() {
        return getOpenDoctrineDialog() != null || getOpenOrganizationDialog() != null;
    }

    @Override
    public void processInput(List<InputEventAPI> events) {
        if (closed || events == null || rootCardPanel == null || rootCardPanel.getPosition() == null) return;
        PositionAPI cp = rootCardPanel.getPosition();
        float cx = cp.getX();
        float cy = cp.getY();
        float cw = cp.getWidth();
        float ch = cp.getHeight();

        for (InputEventAPI event : events) {
            if (event.isConsumed()) continue;
            if (event.isKeyDownEvent() && event.getEventValue() == Keyboard.KEY_ESCAPE && !isAnyDivisionModalOpen()) {
                event.consume();
                close(true);
                return;
            }
            if (event.isMouseDownEvent() || event.isMouseUpEvent()) {
                float mx = event.getX();
                float my = event.getY();
                if (mx >= cx && mx <= cx + cw && my >= cy && my <= cy + ch) {
                    event.consume();
                }
            }
        }
    }

    @Override
    public void advance(float amount) {
        if (closed) return;
        pulsePhase += amount * 3.4f;

        boolean expectedOpenNow = isExpectedDialogOpen();
        int unassignedNow = countUnassignedShipsSafe();
        FleetDoctrineDialog docNow = getOpenDoctrineDialog();
        DivisionEditorDialog orgNow = getOpenOrganizationDialog();
        boolean inConfigureNow = orgNow != null && orgNow.isShowingConfigureDivision();

        // If the player manually switches tabs inside Fleet Doctrine during Steps 1..5, follow their tab!
        if (stepIndex >= STEP_DOCTRINE_COMMAND && stepIndex <= STEP_DOCTRINE_NAMING && docNow != null) {
            int docTabStep = stepForDoctrineTabCommand(docNow.getActiveTabCommand());
            if (docTabStep != stepIndex) {
                stepIndex = docTabStep;
                rebuildCardContent();
            }
        }

        // If the player manually clicks "Configure ->" on a division while on Step 6, advance into Step 7 automatically!
        if (stepIndex == STEP_ORG_OVERVIEW && inConfigureNow && !lastOrgConfigureSnapshot) {
            stepIndex = STEP_ORG_CONFIGURE;
            lastOrgConfigureSnapshot = true;
            lastDialogOpenSnapshot = isExpectedDialogOpen();
            rebuildCardContent();
        } else if (stepIndex == STEP_ORG_CONFIGURE && orgNow != null && orgNow.isShowingOverview() && lastOrgConfigureSnapshot) {
            stepIndex = STEP_ORG_OVERVIEW;
            lastOrgConfigureSnapshot = false;
            lastDialogOpenSnapshot = isExpectedDialogOpen();
            rebuildCardContent();
        } else if (expectedOpenNow != lastDialogOpenSnapshot
                || (stepIndex == STEP_ORG_OVERVIEW && unassignedNow != lastUnassignedCountSnapshot)) {
            lastDialogOpenSnapshot = expectedOpenNow;
            lastUnassignedCountSnapshot = unassignedNow;
            lastOrgConfigureSnapshot = inConfigureNow;
            rebuildCardContent();
        } else {
            lastOrgConfigureSnapshot = inConfigureNow;
        }

        updateLiveAnchorPosition();

        if (pendingAction == null) {
            for (Map.Entry<ButtonAPI, String> e : activeButtons.entrySet()) {
                ButtonAPI b = e.getKey();
                if (b != null && b.isChecked()) {
                    b.setChecked(false);
                    pendingAction = e.getValue();
                    break;
                }
            }
        }

        if (pendingAction != null) {
            String cmd = pendingAction;
            pendingAction = null;
            handleAction(cmd);
        }
    }

    private void handleAction(String cmd) {
        if ("TOUR_SKIP".equals(cmd)) {
            close(true);
            return;
        }
        if ("TOUR_OPEN_CODEX".equals(cmd)) {
            close(true);
            DivisionCodexSetup.openCodexEntry(DivisionCodexSetup.ENTRY_OVERVIEW);
            return;
        }
        if ("TOUR_AUTO_ASSIGN".equals(cmd)) {
            DivisionEditorDialog org = getOpenOrganizationDialog();
            if (org != null) {
                org.executeAutoAssignDirect();
            } else {
                PermanentDivisionManager.autoAssignUnassignedMembers();
            }
            onAutoAssignExecuted();
            return;
        }
        if ("TOUR_PREV".equals(cmd)) {
            if (navigator == null) {
                if (stepIndex > 0) stepIndex--;
            } else if (stepIndex == STEP_DOCTRINE_COMMAND) {
                navigator.closeFleetDoctrine();
                stepIndex = STEP_FLEET_INTRO;
            } else if (stepIndex > STEP_DOCTRINE_COMMAND && stepIndex <= STEP_DOCTRINE_NAMING) {
                if (getOpenDoctrineDialog() == null) {
                    navigator.openFleetDoctrine();
                }
                stepIndex--;
                syncDoctrineTabForCurrentStep();
            } else if (stepIndex == STEP_ORG_OVERVIEW) {
                navigator.closeFleetOrganization();
                navigator.openFleetDoctrine();
                stepIndex = STEP_DOCTRINE_NAMING;
                syncDoctrineTabForCurrentStep();
            } else if (stepIndex == STEP_ORG_CONFIGURE) {
                DivisionEditorDialog org = getOpenOrganizationDialog();
                if (org != null) {
                    org.returnToOverviewForTour();
                } else {
                    navigator.openFleetOrganization();
                }
                stepIndex = STEP_ORG_OVERVIEW;
            }
            DivisionEditorDialog orgRef = getOpenOrganizationDialog();
            lastOrgConfigureSnapshot = orgRef != null && orgRef.isShowingConfigureDivision();
            lastDialogOpenSnapshot = isExpectedDialogOpen();
            rebuildCardContent();
            updateLiveAnchorPosition();
            bringToTop();
            return;
        }
        if ("TOUR_NEXT".equals(cmd)) {
            if (navigator == null) {
                if (stepIndex + 1 < TOTAL_STEPS) {
                    stepIndex++;
                } else {
                    close(true);
                    return;
                }
            } else if (stepIndex == STEP_FLEET_INTRO) {
                stepIndex = STEP_DOCTRINE_COMMAND;
                navigator.openFleetDoctrine();
                syncDoctrineTabForCurrentStep();
            } else if (stepIndex >= STEP_DOCTRINE_COMMAND && stepIndex < STEP_DOCTRINE_NAMING) {
                if (getOpenDoctrineDialog() == null) {
                    navigator.openFleetDoctrine();
                    syncDoctrineTabForCurrentStep();
                } else {
                    stepIndex++;
                    syncDoctrineTabForCurrentStep();
                }
            } else if (stepIndex == STEP_DOCTRINE_NAMING) {
                if (getOpenDoctrineDialog() == null) {
                    navigator.openFleetDoctrine();
                    syncDoctrineTabForCurrentStep();
                } else {
                    stepIndex = STEP_ORG_OVERVIEW;
                    navigator.closeFleetDoctrine();
                    navigator.openFleetOrganization();
                }
            } else if (stepIndex == STEP_ORG_OVERVIEW) {
                DivisionEditorDialog org = getOpenOrganizationDialog();
                if (org == null) {
                    navigator.openFleetOrganization();
                } else {
                    boolean openedCfg = org.openFirstOrSelectedDivisionForTour();
                    if (openedCfg) {
                        autoAssignPressed = true;
                        stepIndex = STEP_ORG_CONFIGURE;
                    } else {
                        close(true);
                        return;
                    }
                }
            } else {
                DivisionEditorDialog org = getOpenOrganizationDialog();
                if (org == null) {
                    navigator.openFleetOrganization();
                    DivisionEditorDialog reopened = getOpenOrganizationDialog();
                    if (reopened != null) {
                        reopened.openFirstOrSelectedDivisionForTour();
                    }
                } else {
                    close(true);
                    return;
                }
            }
            DivisionEditorDialog orgRef = getOpenOrganizationDialog();
            lastOrgConfigureSnapshot = orgRef != null && orgRef.isShowingConfigureDivision();
            lastDialogOpenSnapshot = isExpectedDialogOpen();
            rebuildCardContent();
            updateLiveAnchorPosition();
            bringToTop();
        }
    }

    private static int stepForDoctrineTabCommand(String tabCmd) {
        if ("TAB:MODIFIERS".equals(tabCmd)) return STEP_DOCTRINE_DEFAULTS;
        if ("TAB:AUTO_ASSIGN".equals(tabCmd)) return STEP_DOCTRINE_CREATION;
        if ("TAB:HULLS".equals(tabCmd)) return STEP_DOCTRINE_HULLS;
        if ("TAB:NAMING".equals(tabCmd)) return STEP_DOCTRINE_NAMING;
        return STEP_DOCTRINE_COMMAND;
    }

    private void syncDoctrineTabForCurrentStep() {
        FleetDoctrineDialog doc = getOpenDoctrineDialog();
        if (doc == null) return;
        switch (stepIndex) {
            case STEP_DOCTRINE_COMMAND:
                doc.selectTabForTour("TAB:OTC_COMMAND");
                break;
            case STEP_DOCTRINE_DEFAULTS:
                doc.selectTabForTour("TAB:MODIFIERS");
                break;
            case STEP_DOCTRINE_CREATION:
                doc.selectTabForTour("TAB:AUTO_ASSIGN");
                break;
            case STEP_DOCTRINE_HULLS:
                doc.selectTabForTour("TAB:HULLS");
                break;
            case STEP_DOCTRINE_NAMING:
                doc.selectTabForTour("TAB:NAMING");
                break;
            default:
                break;
        }
    }

    private FleetDoctrineDialog getOpenDoctrineDialog() {
        if (navigator == null) return null;
        FleetDoctrineDialog d = navigator.getActiveDoctrineDialog();
        return (d != null && !d.isClosed()) ? d : null;
    }

    private DivisionEditorDialog getOpenOrganizationDialog() {
        if (navigator == null) return null;
        DivisionEditorDialog d = navigator.getActiveOrganizationDialog();
        return (d != null && !d.isClosed()) ? d : null;
    }

    private boolean isExpectedDialogOpen() {
        if (navigator == null) return true;
        if (stepIndex >= STEP_DOCTRINE_COMMAND && stepIndex <= STEP_DOCTRINE_NAMING) {
            return getOpenDoctrineDialog() != null;
        }
        if (stepIndex == STEP_ORG_OVERVIEW) {
            return getOpenOrganizationDialog() != null;
        }
        if (stepIndex == STEP_ORG_CONFIGURE) {
            DivisionEditorDialog org = getOpenOrganizationDialog();
            return org != null && org.isShowingConfigureDivision();
        }
        return true;
    }

    private static int countUnassignedShipsSafe() {
        try {
            SectorAPI sector = Global.getSector();
            CampaignFleetAPI fleet = sector != null ? sector.getPlayerFleet() : null;
            if (fleet == null) return 0;
            List<PermanentDivisionManager.PermanentDivisionSpec> divs = PermanentDivisionManager.getPermanentDivisions();
            List<?> unassigned = PermanentDivisionManager.getUnassignedCombatMembers(fleet, divs);
            return unassigned != null ? unassigned.size() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private UIComponentAPI resolveActiveSpotlightTarget() {
        FleetDoctrineDialog doc = getOpenDoctrineDialog();
        if (stepIndex == STEP_DOCTRINE_COMMAND) {
            if (doc != null && doc.getCommandSchemeTabButton() != null) return doc.getCommandSchemeTabButton();
            return doctrineButtonRef;
        }
        if (stepIndex == STEP_DOCTRINE_DEFAULTS) {
            if (doc != null && doc.getDivisionDefaultsTabButton() != null) return doc.getDivisionDefaultsTabButton();
            return doctrineButtonRef;
        }
        if (stepIndex == STEP_DOCTRINE_CREATION) {
            if (doc != null && doc.getDivisionCreationTabButton() != null) return doc.getDivisionCreationTabButton();
            return doctrineButtonRef;
        }
        if (stepIndex == STEP_DOCTRINE_HULLS) {
            if (doc != null && doc.getHullLimitsTabButton() != null) return doc.getHullLimitsTabButton();
            return doctrineButtonRef;
        }
        if (stepIndex == STEP_DOCTRINE_NAMING) {
            if (doc != null && doc.getNomenclatureTabButton() != null) return doc.getNomenclatureTabButton();
            return doctrineButtonRef;
        }
        DivisionEditorDialog org = getOpenOrganizationDialog();
        if (stepIndex == STEP_ORG_OVERVIEW) {
            if (org != null) {
                boolean readyForCfg = autoAssignPressed || countUnassignedShipsSafe() == 0;
                if (readyForCfg && org.getConfigureSelectedButton() != null) {
                    return org.getConfigureSelectedButton();
                }
                if (org.getAutoAssignButton() != null) {
                    return org.getAutoAssignButton();
                }
            }
            return organizationButtonRef;
        }
        if (stepIndex == STEP_ORG_CONFIGURE) {
            if (org != null && org.isShowingConfigureDivision() && org.getDivisionTypeDropdownButton() != null) {
                return org.getDivisionTypeDropdownButton();
            }
            return organizationButtonRef;
        }
        return doctrineButtonRef != null ? doctrineButtonRef : injectedPanelRef;
    }

    private void updateLiveAnchorPosition() {
        if (rootCardPanel == null || rootCardPanel.getPosition() == null) return;
        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();
        if (screenW <= 0f) screenW = 1920f;
        if (screenH <= 0f) screenH = 1080f;

        float desiredX = 310f;
        float desiredTopInTL = Math.max(24f, screenH * 0.45f);

        FleetDoctrineDialog doc = getOpenDoctrineDialog();
        DivisionEditorDialog org = getOpenOrganizationDialog();

        if (stepIndex >= STEP_DOCTRINE_COMMAND && stepIndex <= STEP_DOCTRINE_NAMING
                && doc != null && doc.getContentContainer() != null && doc.getContentContainer().getPosition() != null) {
            PositionAPI dp = doc.getContentContainer().getPosition();
            float dialogRight = dp.getX() + dp.getWidth();
            float dialogTopInTL = screenH - (dp.getY() + dp.getHeight());
            float dialogBotInTL = screenH - dp.getY();
            desiredX = dialogRight - CARD_WIDTH - 22f;
            if (stepIndex == STEP_DOCTRINE_COMMAND) {
                desiredTopInTL = dialogBotInTL - cardHeight - 22f;
            } else {
                desiredTopInTL = dialogTopInTL + 74f;
            }
        } else if (stepIndex == STEP_ORG_OVERVIEW
                && org != null && org.getContentContainer() != null && org.getContentContainer().getPosition() != null) {
            PositionAPI op = org.getContentContainer().getPosition();
            float dialogTopInTL = screenH - (op.getY() + op.getHeight());
            UIComponentAPI spot = resolveActiveSpotlightTarget();
            if (spot != null && spot.getPosition() != null && spot.getPosition().getWidth() > 4f) {
                PositionAPI ap = spot.getPosition();
                desiredX = ap.getX();
                float btnBottomInTL = screenH - ap.getY();
                desiredTopInTL = btnBottomInTL + 18f;
            } else {
                desiredX = op.getX() + 188f;
                desiredTopInTL = dialogTopInTL + 84f;
            }
        } else if (stepIndex == STEP_ORG_CONFIGURE
                && org != null && org.isShowingConfigureDivision()
                && org.getContentContainer() != null && org.getContentContainer().getPosition() != null) {
            PositionAPI op = org.getContentContainer().getPosition();
            float dialogRight = op.getX() + op.getWidth();
            float dialogTopInTL = screenH - (op.getY() + op.getHeight());
            desiredX = dialogRight - CARD_WIDTH - 26f;
            desiredTopInTL = dialogTopInTL + 96f;
        } else {
            UIComponentAPI target = resolveActiveSpotlightTarget();
            if (target != null && target.getPosition() != null && target.getPosition().getWidth() > 4f) {
                PositionAPI tp = target.getPosition();
                float anchorRight = tp.getX() + tp.getWidth();
                if (injectedPanelRef != null && injectedPanelRef.getPosition() != null) {
                    anchorRight = Math.max(anchorRight,
                            injectedPanelRef.getPosition().getX() + injectedPanelRef.getPosition().getWidth());
                }
                desiredX = anchorRight + 22f;
                float targetMidScreenY = tp.getY() + tp.getHeight() * 0.5f;
                float cardTopScreenY = targetMidScreenY + cardHeight * 0.5f;
                desiredTopInTL = screenH - cardTopScreenY;
            }
        }

        float clampedX = Math.max(16f, Math.min(screenW - CARD_WIDTH - 16f, Math.round(desiredX)));
        float clampedTopInTL = Math.max(16f, Math.min(screenH - cardHeight - 16f, Math.round(desiredTopInTL)));
        rootCardPanel.getPosition().setSize(CARD_WIDTH, cardHeight);
        rootCardPanel.getPosition().inTL(clampedX, clampedTopInTL);
    }

    private void rebuildCardContent() {
        if (rootCardPanel == null) return;
        activeButtons.clear();
        if (innerContentPanel != null) {
            rootCardPanel.removeComponent(innerContentPanel);
            innerContentPanel = null;
        }

        Color base = Misc.getBasePlayerColor();
        Color dark = Misc.getDarkPlayerColor();
        Color hl = Misc.getHighlightColor();
        Color positive = Misc.getPositiveHighlightColor();
        Color gray = Misc.getGrayColor();

        int idx = Math.max(0, Math.min(TOTAL_STEPS - 1, stepIndex));
        boolean dialogReady = isExpectedDialogOpen();
        int unassignedCount = countUnassignedShipsSafe();
        boolean readyToFinishOrg = autoAssignPressed || unassignedCount == 0;

        String title;
        String body;
        String[] highlights;
        String nextBtnLabel;
        boolean positiveNext = false;
        boolean showAutoAssignShortcut = false;

        if (idx == STEP_FLEET_INTRO) {
            title = "Quick Tour (1/8) - Welcome";
            body = "Welcome to Divisions: Command and Control, Commander! Glad to have you aboard - let's take a quick look at your new fleet command screens. Click Fleet Doctrine on the left (or Open Doctrine ->) whenever you're ready.";
            highlights = new String[]{"Divisions: Command and Control", "Fleet Doctrine", "Open Doctrine ->"};
            nextBtnLabel = "Open Doctrine ->";
        } else if (idx == STEP_DOCTRINE_COMMAND) {
            title = "Fleet Doctrine (2/8) - Command Scheme";
            if (!dialogReady) {
                body = "Click Fleet Doctrine on the left (or Open Doctrine ->) to continue the tour inside Fleet Doctrine.";
                highlights = new String[]{"Fleet Doctrine", "Open Doctrine ->"};
                nextBtnLabel = "Open Doctrine ->";
            } else {
                body = "The Fleet Doctrine screen contains parameters for how your fleet acts in battle at a broad level. You are currently using Sector-standard organization and tactics inherited from the Domain. Everything works out of the box - you do not need to change anything.";
                highlights = new String[]{"how your fleet acts in battle at a broad level", "Sector-standard organization and tactics", "Domain", "do not need to change anything"};
                nextBtnLabel = "Division Defaults ->";
            }
        } else if (idx == STEP_DOCTRINE_DEFAULTS) {
            title = "Fleet Doctrine (3/8) - Division Defaults";
            if (!dialogReady) {
                body = "Click Fleet Doctrine on the left (or Open Doctrine ->) to view Division Defaults.";
                highlights = new String[]{"Fleet Doctrine", "Open Doctrine ->"};
                nextBtnLabel = "Open Doctrine ->";
            } else {
                body = "Division Defaults sets the standing missions, formations, and spacing for each Division Type. These settings are inherited by your active divisions in the Fleet Organization screen.";
                highlights = new String[]{"Division Defaults", "inherited by your active divisions", "Fleet Organization"};
                nextBtnLabel = "Division Creation ->";
            }
        } else if (idx == STEP_DOCTRINE_CREATION) {
            title = "Fleet Doctrine (4/8) - Division Creation";
            if (!dialogReady) {
                body = "Click Fleet Doctrine on the left (or Open Doctrine ->) to view Division Creation.";
                highlights = new String[]{"Fleet Doctrine", "Open Doctrine ->"};
                nextBtnLabel = "Open Doctrine ->";
            } else {
                body = "Division Creation chooses which specialist division types get instantiated from your ships (and whether Combat Freighters count as warships). Roles turned OFF fold cleanly into standard gun-line divisions.";
                highlights = new String[]{"Division Creation", "Combat Freighters", "standard gun-line divisions"};
                nextBtnLabel = "Hull Limits ->";
            }
        } else if (idx == STEP_DOCTRINE_HULLS) {
            title = "Fleet Doctrine (5/8) - Hull Limits";
            if (!dialogReady) {
                body = "Click Fleet Doctrine on the left (or Open Doctrine ->) to view Hull Limits.";
                highlights = new String[]{"Fleet Doctrine", "Open Doctrine ->"};
                nextBtnLabel = "Open Doctrine ->";
            } else {
                body = "Hull Limits sets the minimum and maximum number of ships placed into each division when Auto-Assign organizes your fleet.";
                highlights = new String[]{"Hull Limits", "minimum and maximum", "Auto-Assign"};
                nextBtnLabel = "Nomenclature ->";
            }
        } else if (idx == STEP_DOCTRINE_NAMING) {
            title = "Fleet Doctrine (6/8) - Nomenclature";
            if (!dialogReady) {
                body = "Click Fleet Doctrine on the left (or Open Doctrine ->) to view Nomenclature.";
                highlights = new String[]{"Fleet Doctrine", "Open Doctrine ->"};
                nextBtnLabel = "Open Doctrine ->";
            } else {
                body = "Nomenclature lets you pick a cultural naming style and numbering format for your divisions, or customize individual prefixes. Next, let's open Fleet Organization.";
                highlights = new String[]{"Nomenclature", "Fleet Organization"};
                nextBtnLabel = "Fleet Organization ->";
            }
        } else if (idx == STEP_ORG_OVERVIEW) {
            title = "Fleet Organization (7/8) - Auto-Assign";
            if (!dialogReady) {
                body = "Click Fleet Organization on the left (or Open Organization ->) to continue.";
                highlights = new String[]{"Fleet Organization", "Open Organization ->"};
                nextBtnLabel = "Open Organization ->";
            } else if (!readyToFinishOrg) {
                body = "Hit the Auto-Assign button above to group your ships without rearranging them by hand. Divisions mainly inherit their values from Division Defaults, or you can tune them yourself.";
                highlights = new String[]{"Auto-Assign", "mainly inherit their values from Division Defaults", "tune them yourself"};
                nextBtnLabel = "Configure Division ->";
                showAutoAssignShortcut = true;
            } else {
                body = "Your ships are grouped into divisions! Divisions mainly inherit their values from Division Defaults. Click Configure Division -> (or Configure -> on any division) for a quick look inside.";
                highlights = new String[]{"mainly inherit their values from Division Defaults", "Configure Division ->", "Configure ->"};
                nextBtnLabel = "Configure Division ->";
            }
        } else {
            title = "Configure Division (8/8) - Inherit or Tune";
            if (!dialogReady) {
                body = "Open any division's Configure screen (or click Configure Division ->) to finish the tour.";
                highlights = new String[]{"Configure", "Configure Division ->"};
                nextBtnLabel = "Configure Division ->";
            } else {
                body = "Divisions mainly inherit their values from Division Defaults based on their Division Type on the left. Leave settings on Default (Doctrine) to inherit automatically, or change any setting here to tune this division yourself.";
                highlights = new String[]{"mainly inherit their values from Division Defaults", "Division Type", "Default (Doctrine)", "tune this division yourself"};
                nextBtnLabel = "Done";
                positiveNext = true;
            }
        }

        float innerW = CARD_WIDTH - 20f;
        CustomPanelAPI content = Global.getSettings().createCustom(CARD_WIDTH, 230f, subPanelPlugin);
        TooltipMakerAPI tm = content.createUIElement(innerW, 230f, false);

        LabelAPI titleLbl = tm.addPara(title, 0f, hl, title);
        float titleBot = DivisionUiWidgets.wrapLabelInPlace(titleLbl, innerW - 4f, 0f, 2f);

        LabelAPI bodyLbl = tm.addPara(body, 0f, Misc.getTextColor(), hl, highlights);
        float bodyBot = DivisionUiWidgets.wrapLabelInPlace(bodyLbl, innerW - 4f, 0f, titleBot + 6f);

        float btnY = bodyBot + 10f;
        float btnH = 22f;

        ButtonAPI bSkip = registerButton(
                tm,
                tm.addButton("Skip", "TOUR_SKIP", gray, dark, Alignment.MID, CutStyle.ALL, 60f, btnH, 0f),
                "TOUR_SKIP");
        bSkip.getPosition().inTL(0f, btnY);

        float nextW = nextBtnLabel.length() > 18 ? 174f : (nextBtnLabel.length() > 14 ? 158f : (nextBtnLabel.length() > 8 ? 132f : 84f));
        ButtonAPI bNext = registerButton(
                tm,
                tm.addButton(
                        nextBtnLabel,
                        "TOUR_NEXT",
                        positiveNext ? positive : hl,
                        dark,
                        Alignment.MID,
                        CutStyle.TL_BR,
                        nextW,
                        btnH,
                        0f),
                "TOUR_NEXT");
        if (!showAutoAssignShortcut) {
            bNext.highlight();
        }
        bNext.getPosition().inTL(innerW - nextW, btnY);

        float rightCursor = innerW - nextW - 6f;
        if (showAutoAssignShortcut) {
            float aaW = 104f;
            ButtonAPI bAuto = registerButton(
                    tm,
                    tm.addButton(
                            "Auto-Assign",
                            "TOUR_AUTO_ASSIGN",
                            hl,
                            dark,
                            Alignment.MID,
                            CutStyle.TL_BR,
                            aaW,
                            btnH,
                            0f),
                    "TOUR_AUTO_ASSIGN");
            bAuto.highlight();
            bAuto.getPosition().inTL(rightCursor - aaW, btnY);
            rightCursor -= (aaW + 6f);
        }

        if (idx > 0) {
            float prevW = 74f;
            ButtonAPI bPrev = registerButton(
                    tm,
                    tm.addButton("<- Back", "TOUR_PREV", base, dark, Alignment.MID, CutStyle.ALL, prevW, btnH, 0f),
                    "TOUR_PREV");
            bPrev.getPosition().inTL(Math.max(66f, rightCursor - prevW), btnY);
        }

        float computedH = Math.max(128f, Math.round(btnY + btnH + 20f));
        cardHeight = computedH;
        tm.setHeightSoFar(computedH - 16f);
        content.getPosition().setSize(CARD_WIDTH, computedH);
        content.addUIElement(tm).inTL(10f, 8f);

        innerContentPanel = content;
        rootCardPanel.getPosition().setSize(CARD_WIDTH, computedH);
        rootCardPanel.addComponent(content).inTL(0f, 0f);
    }

    @Override
    public void renderBelow(float alphaMult) {
        if (closed || rootCardPanel == null || rootCardPanel.getPosition() == null) return;
        PositionAPI cp = rootCardPanel.getPosition();
        float cx = cp.getX();
        float cy = cp.getY();
        float cw = cp.getWidth();
        float ch = cp.getHeight();

        Color hl = Misc.getHighlightColor();
        Color base = Misc.getBasePlayerColor();
        float pulse = 0.55f + 0.45f * (float) Math.sin(pulsePhase);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        UIComponentAPI target = resolveActiveSpotlightTarget();
        if (target != null && target.getPosition() != null && target.getPosition().getWidth() > 4f) {
            PositionAPI tp = target.getPosition();
            float pad = 4f;
            float sx = tp.getX() - pad;
            float sy = tp.getY() - pad;
            float sw = tp.getWidth() + pad * 2f;
            float sh = tp.getHeight() + pad * 2f;
            if ((target == doctrineButtonRef || target == organizationButtonRef)
                    && injectedPanelRef != null && injectedPanelRef.getPosition() != null) {
                float panelLeft = injectedPanelRef.getPosition().getX() - pad;
                sw += (sx - panelLeft);
                sx = panelLeft;
            }

            drawQuad(sx, sy, sw, sh, hl, 0.12f * pulse * alphaMult);
            drawRectOutline(sx, sy, sw, sh, 2.0f, hl, (0.68f + 0.32f * pulse) * alphaMult);

            float startX = 0f;
            float startY = 0f;
            float endX = 0f;
            float endY = 0f;
            boolean drawLeader = false;

            if (cx > sx + sw + 4f) {
                startX = sx + sw + 1f;
                startY = sy + sh * 0.5f;
                endX = cx - 2f;
                endY = Math.max(cy + 14f, Math.min(cy + ch - 14f, startY));
                drawLeader = true;
            } else if (sy > cy + ch + 4f) {
                startX = sx + sw * 0.5f;
                startY = sy - 1f;
                endX = Math.max(cx + 18f, Math.min(cx + cw - 18f, startX));
                endY = cy + ch + 2f;
                drawLeader = true;
            }

            if (drawLeader) {
                GL11.glColor4f(
                        hl.getRed() / 255f,
                        hl.getGreen() / 255f,
                        hl.getBlue() / 255f,
                        0.85f * alphaMult);
                GL11.glLineWidth(2.0f);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex2f(startX, startY);
                GL11.glVertex2f(endX, endY);
                GL11.glEnd();
                drawQuad(startX - 3f, startY - 3f, 6f, 6f, hl, 0.95f * alphaMult);
            }
        }

        drawQuad(cx + 4f, cy - 4f, cw, ch, Color.BLACK, 0.58f * alphaMult);
        drawQuad(cx, cy, cw, ch, new Color(10, 16, 24), 0.96f * alphaMult);
        drawQuad(cx, cy + ch - 3f, cw, 3f, hl, 0.90f * alphaMult);
        drawRectOutline(cx, cy, cw, ch, 1.5f, base, 0.90f * alphaMult);

        GL11.glPopAttrib();
    }

    private static void drawQuad(float x, float y, float w, float h, Color c, float alpha) {
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, Math.max(0f, Math.min(1f, alpha)));
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    private static void drawRectOutline(float x, float y, float w, float h, float lineW, Color c, float alpha) {
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, Math.max(0f, Math.min(1f, alpha)));
        GL11.glLineWidth(lineW);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }
}
