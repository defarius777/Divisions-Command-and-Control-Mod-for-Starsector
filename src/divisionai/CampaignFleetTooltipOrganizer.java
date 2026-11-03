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

    public static String[] splitBalancedLines(String text, LabelAPI measurer, float maxLineW) {
        return DivisionHeaderCardPlugin.splitBalancedLines(text, measurer, maxLineW);
    }

    public static String fitSingleLineToWidth(String text, LabelAPI measurer, float maxLineW) {
        return DivisionHeaderCardPlugin.fitSingleLineToWidth(text, measurer, maxLineW);
    }

    public static float measureLineWidth(String text, LabelAPI measurer) {
        return DivisionHeaderCardPlugin.measureLineWidth(text, measurer);
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
        private LabelAPI line3Label;

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

            this.line3Label = Global.getSettings().createLabel("", Fonts.DEFAULT_SMALL);
            this.line3Label.setColor(textColor);
            this.line3Label.setAlignment(Alignment.MID);

            PositionAPI p1 = card.addComponent((UIComponentAPI) line1Label);
            PositionAPI p2 = card.addComponent((UIComponentAPI) line2Label);
            PositionAPI p3 = card.addComponent((UIComponentAPI) line3Label);
            // Pre-initialize non-zero widths before any setText() call so com.fs.starfarer.ui.d
            // never word-wraps spaces into newlines against a 0px width.
            float initW = Math.max(120f, boxWidth * 2f);
            p1.setSize(initW, 14f).inTL(0f, 0f);
            p2.setSize(initW, 14f).inTL(0f, 0f);
            p3.setSize(initW, 14f).inTL(0f, 0f);

            updateTitleAndLayout(initialTitle);
        }

        void updateTitleAndLayout(String rawTitle) {
            if (line1Label == null || line2Label == null || line3Label == null) return;
            String clean = rawTitle != null ? rawTitle.trim().replaceAll("\\s+", " ") : "";
            // Usable interior width between the 3px left accent bar (x+1..x+4) and right card edge (x+boxWidth-3)
            float maxTextWidth = Math.max(40f, boxWidth - 14f);
            float usableCenterX = 4f + (boxWidth - 7f) * 0.5f;

            String[] lines = splitBalancedLines(clean, line1Label, maxTextWidth);
            PositionAPI p1 = ((UIComponentAPI) line1Label).getPosition();
            PositionAPI p2 = ((UIComponentAPI) line2Label).getPosition();
            PositionAPI p3 = ((UIComponentAPI) line3Label).getPosition();

            if (lines.length <= 1 || lines[1] == null || lines[1].isEmpty()) {
                String l1 = lines.length > 0 ? fitSingleLineToWidth(lines[0], line1Label, maxTextWidth) : "";
                float safeW = Math.max(boxWidth - 6f, measureLineWidth(l1, line1Label) + 24f);
                p1.setSize(safeW, boxHeight);
                p1.inTL(usableCenterX - safeW * 0.5f, 0f);
                line1Label.setText(l1);

                p2.setSize(safeW, 0f);
                p2.inTL(usableCenterX - safeW * 0.5f, 0f);
                line2Label.setText("");

                p3.setSize(safeW, 0f);
                p3.inTL(usableCenterX - safeW * 0.5f, 0f);
                line3Label.setText("");
            } else if (lines.length == 2 || lines[2] == null || lines[2].isEmpty()) {
                String l1 = fitSingleLineToWidth(lines[0], line1Label, maxTextWidth);
                String l2 = fitSingleLineToWidth(lines[1], line2Label, maxTextWidth);
                float lineH = 14f;
                float blockH = lineH * 2f;
                float topY = Math.max(2f, (boxHeight - blockH) * 0.5f);

                float safeW1 = Math.max(boxWidth - 6f, measureLineWidth(l1, line1Label) + 24f);
                p1.setSize(safeW1, lineH);
                p1.inTL(usableCenterX - safeW1 * 0.5f, topY);
                line1Label.setText(l1);

                float safeW2 = Math.max(boxWidth - 6f, measureLineWidth(l2, line2Label) + 24f);
                p2.setSize(safeW2, lineH);
                p2.inTL(usableCenterX - safeW2 * 0.5f, topY + lineH);
                line2Label.setText(l2);

                p3.setSize(safeW2, 0f);
                p3.inTL(usableCenterX - safeW2 * 0.5f, 0f);
                line3Label.setText("");
            } else {
                String l1 = fitSingleLineToWidth(lines[0], line1Label, maxTextWidth);
                String l2 = fitSingleLineToWidth(lines[1], line2Label, maxTextWidth);
                String l3 = fitSingleLineToWidth(lines[2], line3Label, maxTextWidth);
                float lineH = 12f;
                float blockH = lineH * 3f;
                float topY = Math.max(1f, (boxHeight - blockH) * 0.5f);

                float safeW1 = Math.max(boxWidth - 6f, measureLineWidth(l1, line1Label) + 24f);
                p1.setSize(safeW1, lineH);
                p1.inTL(usableCenterX - safeW1 * 0.5f, topY);
                line1Label.setText(l1);

                float safeW2 = Math.max(boxWidth - 6f, measureLineWidth(l2, line2Label) + 24f);
                p2.setSize(safeW2, lineH);
                p2.inTL(usableCenterX - safeW2 * 0.5f, topY + lineH);
                line2Label.setText(l2);

                float safeW3 = Math.max(boxWidth - 6f, measureLineWidth(l3, line3Label) + 24f);
                p3.setSize(safeW3, lineH);
                p3.inTL(usableCenterX - safeW3 * 0.5f, topY + lineH * 2f);
                line3Label.setText(l3);
            }
        }

        public static float measureLineWidth(String text, LabelAPI measurer) {
            if (text == null || text.isEmpty()) return 0f;
            if (measurer != null) {
                try {
                    float w = measurer.computeTextWidth(text);
                    if (w > 0f) return w;
                } catch (Throwable ignored) {
                }
            }
            // Exact glyph-advance fallback matching Starsector's insignia15LTaa.fnt (Fonts.DEFAULT_SMALL)
            float sum = 0f;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case ' ':
                    case '.':
                    case ',':
                    case ':':
                    case ';':
                    case '!':
                    case '\'':
                    case '|':
                    case 'i':
                    case 'l':
                        sum += 3.5f;
                        break;
                    case 'I':
                    case 'j':
                    case 't':
                    case 'f':
                    case 'r':
                    case '-':
                    case '1':
                        sum += 5.0f;
                        break;
                    case 'm':
                    case 'w':
                    case 'M':
                    case 'W':
                        sum += 10.0f;
                        break;
                    default:
                        sum += (Character.isUpperCase(c) ? 8.0f : 6.5f);
                        break;
                }
            }
            return sum;
        }

        public static String fitSingleLineToWidth(String text, LabelAPI measurer, float maxLineW) {
            if (text == null || text.isEmpty()) return "";
            if (measureLineWidth(text, measurer) <= maxLineW + 1f) {
                return text;
            }
            String candidate = text;
            while (candidate.length() > 3 && measureLineWidth(candidate + ".", measurer) > maxLineW + 1f) {
                candidate = candidate.substring(0, candidate.length() - 1).trim();
            }
            return candidate + ".";
        }

        public static String[] splitBalancedLines(String text, LabelAPI measurer, float maxLineW) {
            if (text == null) return new String[]{""};
            String norm = text.trim().replaceAll("\\s+", " ");
            if (norm.isEmpty()) return new String[]{""};

            // Pre-split any hyphenated token wider than maxLineW
            String[] rawTokens = norm.split(" ");
            List<String> words = new ArrayList<>(rawTokens.length + 2);
            for (String tok : rawTokens) {
                if (measureLineWidth(tok, measurer) > maxLineW && tok.indexOf('-') > 0 && tok.indexOf('-') < tok.length() - 1) {
                    int dash = tok.indexOf('-');
                    words.add(tok.substring(0, dash + 1));
                    words.add(tok.substring(dash + 1));
                } else {
                    words.add(tok);
                }
            }

            float fullW = measureLineWidth(norm, measurer);
            float singleLineThreshold = Math.min(62f, maxLineW);
            if (words.size() == 1 || fullW <= singleLineThreshold) {
                return new String[]{fitSingleLineToWidth(norm, measurer, maxLineW)};
            }

            // Evaluate all 2-line split points using actual pixel widths
            String best2L1 = norm;
            String best2L2 = "";
            float best2Score = Float.MAX_VALUE;
            float best2MaxW = Float.MAX_VALUE;

            for (int i = 1; i < words.size(); i++) {
                String l1 = joinWords(words, 0, i);
                String l2 = joinWords(words, i, words.size());
                float w1 = measureLineWidth(l1, measurer);
                float w2 = measureLineWidth(l2, measurer);
                float maxW = Math.max(w1, w2);
                float overflowPenalty = Math.max(0f, maxW - maxLineW) * 100f;
                float score = overflowPenalty + maxW + 0.15f * Math.abs(w1 - w2);
                if (score < best2Score) {
                    best2Score = score;
                    best2MaxW = maxW;
                    best2L1 = l1;
                    best2L2 = l2;
                }
            }

            if (best2MaxW <= maxLineW || words.size() < 3) {
                return new String[]{best2L1, best2L2};
            }

            // Evaluate 3-line splits when 2 lines would exceed maxLineW
            String best3L1 = best2L1;
            String best3L2 = best2L2;
            String best3L3 = "";
            float best3Score = Float.MAX_VALUE;
            float best3MaxW = Float.MAX_VALUE;

            for (int i = 1; i < words.size() - 1; i++) {
                for (int j = i + 1; j < words.size(); j++) {
                    String l1 = joinWords(words, 0, i);
                    String l2 = joinWords(words, i, j);
                    String l3 = joinWords(words, j, words.size());
                    float w1 = measureLineWidth(l1, measurer);
                    float w2 = measureLineWidth(l2, measurer);
                    float w3 = measureLineWidth(l3, measurer);
                    float maxW = Math.max(w1, Math.max(w2, w3));
                    float overflowPenalty = Math.max(0f, maxW - maxLineW) * 100f;
                    float score = overflowPenalty + maxW + 0.10f * (Math.abs(w1 - w2) + Math.abs(w2 - w3));
                    if (score < best3Score) {
                        best3Score = score;
                        best3MaxW = maxW;
                        best3L1 = l1;
                        best3L2 = l2;
                        best3L3 = l3;
                    }
                }
            }

            if (best3MaxW < best2MaxW) {
                return new String[]{best3L1, best3L2, best3L3};
            }
            return new String[]{best2L1, best2L2};
        }

        private static String joinWords(List<String> words, int from, int to) {
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (i > from) sb.append(' ');
                sb.append(words.get(i));
            }
            return sb.toString();
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
        Color factionColor = (faction != null && faction.getBaseUIColor() != null)
                ? faction.getBaseUIColor()
                : Misc.getBasePlayerColor();
        Color factionBrightColor = (faction != null && faction.getBrightUIColor() != null)
                ? faction.getBrightUIColor()
                : factionColor;

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
            Color divTextColor = resolveDivisionTextColor(nomCat, factionBrightColor, divColor);

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
                            divTextColor,
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

        // If the fleet has more members/wings than displayed (compact tooltip "+N" badge in bottom-right cell)
        // and the last row occupied the rightmost column, reserve an extra row so "+N" never overlaps a ship.
        int totalFleetMembers = fleet.getMembersWithFightersCopy() != null
                ? fleet.getMembersWithFightersCopy().size()
                : fleet.getFleetData().getMembersListCopy().size();
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
        // Follow the campaign UI color of the fleet's respective faction across all divisions (not multicolored by archetype)
        return factionColor != null ? factionColor : Misc.getBasePlayerColor();
    }

    private Color resolveDivisionTextColor(
            NomenclatureCategory nomCat,
            Color factionBrightColor,
            Color fallbackColor) {
        if (factionBrightColor != null) {
            return factionBrightColor;
        }
        return fallbackColor != null ? fallbackColor : Misc.getBasePlayerColor();
    }

    private void addExpandedDivisionHeaderCard(
            Object scrollingList,
            PermanentDivisionSpec spec,
            int divisionNumber,
            NomenclatureCategory nomCat,
            Color accentColor,
            Color brightTextColor,
            float boxWidth,
            float boxHeight,
            float divisionSpanWidth) throws Throwable {

        String title = DivisionNomenclature.resolveDynamicDivisionDisplayName(
                nomCat,
                spec.archetype,
                divisionNumber,
                spec.name);

        Color textColor = brightTextColor != null
                ? brightTextColor
                : (accentColor != null ? accentColor : Misc.getBasePlayerColor());

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
