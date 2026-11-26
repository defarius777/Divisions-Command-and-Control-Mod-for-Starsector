package divisionai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.rulecmd.SetCodexEntryId;
import com.fs.starfarer.api.impl.codex.CodexDataV2;
import com.fs.starfarer.api.impl.codex.CodexDialogAPI;
import com.fs.starfarer.api.impl.codex.CodexEntryPlugin;
import com.fs.starfarer.api.impl.codex.CodexEntryV2;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Registers "Divisions: Command & Control" entries in Starsector 0.98a's Codex ({@link CodexDataV2}),
 * provides contextual {@code F2} tooltip-to-Codex entry resolution, and opens the Codex directly
 * to any Division AI topic.
 */
public final class DivisionCodexSetup {

    private static final Logger LOG = Global.getLogger(DivisionCodexSetup.class);

    public static final String LUNALIB_MODS_CAT_ID = "lunalib_mods";
    public static final String CAT_DIVISION_AI = "divisionai_cat_root";

    public static final String ENTRY_OVERVIEW = "divisionai_overview";
    public static final String ENTRY_OTC = "divisionai_otc";
    public static final String ENTRY_ORGANIZATION = "divisionai_organization";
    public static final String ENTRY_MISSIONS = "divisionai_missions";
    public static final String ENTRY_FORMATIONS = "divisionai_formations";
    public static final String ENTRY_WARROOM = "divisionai_warroom";

    private static MethodHandle showCodexByIdHandle = null;
    private static boolean showCodexHandleResolved = false;

    /** Tracks the last entry requested via {@link #openCodexEntry(String)} in headless tests. */
    public static String lastOpenedCodexEntryForTests = null;

    private DivisionCodexSetup() {
    }

    /**
     * Called from {@link DivisionAIModPlugin#onAboutToStartGeneratingCodex()}.
     * Ensures vanilla's Spacer's Manual category is generated as well.
     */
    public static void onAboutToStartGeneratingCodex() {
        CodexDataV2.WITH_GAME_MECHANICS_CAT = true;
    }

    /**
     * Called from {@link DivisionAIModPlugin#onCodexDataGenerated()}.
     * Builds the Divisions: Command & Control Codex tree, indexes every entry in {@link CodexDataV2#ENTRIES},
     * and cross-links related topics.
     */
    public static void onCodexDataGenerated() {
        try {
            if (CodexDataV2.ROOT == null) return;

            String modLogoIcon = safeSprite("division_ai", "icon_regular", "graphics/icons/icon_regular.png");

            CodexEntryPlugin parentCat = CodexDataV2.getEntry(LUNALIB_MODS_CAT_ID);
            if (parentCat == null) {
                parentCat = CodexDataV2.getEntry(CodexDataV2.CAT_GAME_MECHANICS);
            }
            if (parentCat == null) {
                parentCat = CodexDataV2.ROOT;
            }

            CodexEntryV2 modCat = new CodexEntryV2(CAT_DIVISION_AI, "Divisions: Command & Control", modLogoIcon);
            registerEntryUnderParent(modCat, parentCat);

            DivisionManualEntry overview = buildOverviewEntry(modLogoIcon);
            DivisionManualEntry otc = buildOtcEntry(modLogoIcon);
            DivisionManualEntry org = buildOrganizationEntry(modLogoIcon);
            DivisionManualEntry missions = buildMissionsEntry(modLogoIcon);
            DivisionManualEntry formations = buildFormationsEntry(modLogoIcon);
            DivisionManualEntry warroom = buildWarroomEntry(modLogoIcon);

            registerEntryUnderParent(overview, modCat);
            registerEntryUnderParent(otc, modCat);
            registerEntryUnderParent(org, modCat);
            registerEntryUnderParent(missions, modCat);
            registerEntryUnderParent(formations, modCat);
            registerEntryUnderParent(warroom, modCat);

            CodexDataV2.makeRelated(overview, otc, org, missions, formations, warroom);
            CodexDataV2.makeRelated(otc, overview, org, missions, warroom);
            CodexDataV2.makeRelated(org, overview, otc, missions, formations);
            CodexDataV2.makeRelated(missions, overview, org, formations, warroom);
            CodexDataV2.makeRelated(formations, overview, org, missions, warroom);
            CodexDataV2.makeRelated(warroom, overview, otc, missions, formations);

            LOG.info("[DivisionAI] Registered 6 Codex manual entries under '" + parentCat.getTitle() + "'.");
        } catch (Throwable t) {
            LOG.warn("[DivisionAI] Failed to register Codex entries", t);
        }
    }

    private static void registerEntryUnderParent(CodexEntryV2 entry, CodexEntryPlugin parent) {
        if (entry == null || parent == null) return;
        // Remove stale duplicate if Codex was regenerated (e.g. reloadCodexData)
        if (parent.getChildren() != null) {
            List<CodexEntryPlugin> toRemove = new ArrayList<>();
            for (CodexEntryPlugin existing : parent.getChildren()) {
                if (existing != null && entry.getId().equals(existing.getId())) {
                    toRemove.add(existing);
                }
            }
            parent.getChildren().removeAll(toRemove);
        }
        entry.setParent(parent);
        parent.addChild(entry);
        CodexDataV2.ENTRIES.put(entry.getId(), entry);
    }

    private static String safeSprite(String category, String key, String fallback) {
        try {
            if (Global.getSettings() != null) {
                String path = Global.getSettings().getSpriteName(category, key);
                if (path != null && !path.isEmpty()) {
                    return path;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /**
     * Opens the Starsector 0.98a Codex directly to {@code entryId} (defaulting to {@link #ENTRY_OVERVIEW}).
     */
    public static boolean openCodexEntry(String entryId) {
        String targetId = (entryId != null && !entryId.trim().isEmpty()) ? entryId.trim() : ENTRY_OVERVIEW;
        lastOpenedCodexEntryForTests = targetId;
        try {
            MethodHandle mh = resolveShowCodexHandle();
            if (mh != null) {
                mh.invoke(targetId);
                return true;
            }
        } catch (Throwable t) {
            LOG.warn("[DivisionAI] Direct CodexDialog.show(entryId) failed; trying campaignUI fallback", t);
        }
        try {
            SectorAPI sector = Global.getSector();
            if (sector != null) {
                if (sector.getMemoryWithoutUpdate() != null) {
                    sector.getMemoryWithoutUpdate().set(SetCodexEntryId.GLOBAL_CODEX_ENTRY_TO_OPEN, targetId, 0f);
                }
                CampaignUIAPI ui = sector.getCampaignUI();
                if (ui != null) {
                    ui.cmdCodex();
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static MethodHandle resolveShowCodexHandle() {
        if (showCodexHandleResolved) {
            return showCodexByIdHandle;
        }
        showCodexHandleResolved = true;
        try {
            ClassLoader cl = DivisionCodexSetup.class.getClassLoader();
            Class<?> codexDlgClass = Class.forName("com.fs.starfarer.codex2.CodexDialog", false, cl);
            showCodexByIdHandle = MethodHandles.lookup().findStatic(
                    codexDlgClass, "show", MethodType.methodType(void.class, String.class));
        } catch (Throwable ignored) {
            showCodexByIdHandle = null;
        }
        return showCodexByIdHandle;
    }

    /**
     * Maps any Division AI UI button {@code actionId} to its matching 0.98a Codex entry ID for {@code F2} lookup.
     */
    public static String resolveCodexEntryForAction(String actionId) {
        if (actionId == null || actionId.isEmpty()) return ENTRY_OVERVIEW;
        if (actionId.startsWith("OPEN_CODEX:")) {
            String tok = actionId.substring("OPEN_CODEX:".length()).trim();
            return tok.isEmpty() ? ENTRY_OVERVIEW : tok;
        }
        if ("OPEN_FLEET_DOCTRINE".equals(actionId)
                || "TAB:OTC_COMMAND".equals(actionId)
                || "TAB:ALL".equals(actionId)
                || actionId.startsWith("SET_OTC_AUTH:")
                || actionId.startsWith("OTC_SELECT:")
                || actionId.startsWith("SET_OP_TEMPLATE:")
                || actionId.startsWith("SET_OP_ANCHOR:")
                || actionId.startsWith("SET_OBJ_POLICY:")
                || actionId.startsWith("SET_DECISIVE_ARM:")
                || actionId.startsWith("SET_ENGAGE_DISC:")
                || actionId.startsWith("SET_MULTI_SETPIECE:")
                || actionId.startsWith("TOGGLE_PLAY:")
                || actionId.startsWith("FIGHTING_STYLE_")
                || actionId.startsWith("BATTLE_SETPIECE_")) {
            return ENTRY_OTC;
        }
        if ("OPEN_DIVISION_EDITOR".equals(actionId)
                || "TAB:AUTO_ASSIGN".equals(actionId)
                || "TAB:HULLS".equals(actionId)
                || "TAB:NAMING".equals(actionId)
                || "CREATE_DIV_OVERVIEW".equals(actionId)
                || "CREATE_DIV".equals(actionId)
                || "AUTO_ASSIGN".equals(actionId)
                || "RESET_AUTO".equals(actionId)
                || "CLEAR_ALL_DIVS".equals(actionId)
                || actionId.startsWith("SELECT_DIV:")
                || actionId.startsWith("OPEN_DIV:")
                || actionId.startsWith("DELETE_DIV:")
                || actionId.startsWith("NEW_DIV_WITH:")
                || actionId.startsWith("SET_MIN:")
                || actionId.startsWith("SET_MAX:")
                || actionId.startsWith("SET_NAMING_")
                || actionId.startsWith("SET_NUMBERING_")
                || actionId.startsWith("SET_ARCH:")
                || actionId.startsWith("SET_COMBAT_FREIGHTERS_WARSHIPS:")
                || actionId.contains("DIV_ARCH_DD")) {
            return ENTRY_ORGANIZATION;
        }
        if (actionId.startsWith("SET_OPEN_MIS:")
                || actionId.startsWith("DIV_OPEN_MIS:")
                || actionId.startsWith("SET_MAIN_MIS:")
                || actionId.startsWith("DIV_MAIN_MIS:")
                || actionId.contains("OPEN_DD")
                || actionId.contains("MAIN_DD")
                || actionId.contains("CARRIER_OPTIONS")
                || actionId.contains("CARRIER_OPTS")
                || actionId.startsWith("SET_SC_")
                || actionId.startsWith("DIV_SC_")
                || actionId.startsWith("SET_VS_")
                || actionId.startsWith("DIV_VS_")
                || actionId.startsWith("SET_FTR_")
                || actionId.startsWith("DIV_FTR_")
                || actionId.startsWith("SET_BMB_")
                || actionId.startsWith("DIV_BMB_")
                || actionId.contains("RAMMING")
                || actionId.startsWith("SET_RAM_")
                || actionId.startsWith("DIV_RAM_")) {
            return ENTRY_MISSIONS;
        }
        if (actionId.contains("FORMATION")
                || actionId.contains("SPACING")
                || actionId.contains("INTERVAL")
                || actionId.contains("STANDOFF")
                || actionId.contains("LINE_STATION")
                || actionId.contains("STICKINESS")
                || actionId.contains("SPEED_GOVERNOR")
                || actionId.contains("TELEGRAPH")
                || actionId.contains("SETPIECE")
                || actionId.contains("SKILL_COORD")
                || actionId.contains("TUNING")) {
            return ENTRY_FORMATIONS;
        }
        if (actionId.contains("WARROOM")
                || actionId.contains("TSFC")
                || actionId.contains("ROSE")) {
            return ENTRY_WARROOM;
        }
        return ENTRY_OVERVIEW;
    }

    /**
     * Maps row labels in Fleet Doctrine / Configure Division to their matching 0.98a Codex entry ID.
     */
    public static String resolveCodexEntryForLabel(String labelText) {
        if (labelText == null || labelText.isEmpty()) return ENTRY_OVERVIEW;
        String lower = labelText.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("command style") || lower.contains("authority") || lower.contains("conduct")
                || lower.contains("anchor") || lower.contains("objective capture") || lower.contains("striking arm")
                || lower.contains("discipline") || lower.contains("fleet formation") || lower.contains("orchestrated")) {
            return ENTRY_OTC;
        }
        if (lower.contains("callsign") || lower.contains("naming") || lower.contains("numbering")
                || lower.contains("hull") || lower.contains("auto-assign")) {
            return ENTRY_ORGANIZATION;
        }
        if (lower.contains("division type") || lower.contains("mission") || lower.contains("opening")
                || lower.contains("strike craft") || lower.contains("voidstrike") || lower.contains("carrier")
                || lower.contains("cap reserve") || lower.contains("target bias") || lower.contains("ram")) {
            return ENTRY_MISSIONS;
        }
        if (lower.contains("spacing") || lower.contains("interval") || lower.contains("standoff")
                || lower.contains("line station") || lower.contains("formation") || lower.contains("cohesion")
                || lower.contains("speed governor") || lower.contains("telegraph") || lower.contains("setpiece")
                || lower.contains("skill") || lower.contains("tuning")) {
            return ENTRY_FORMATIONS;
        }
        return ENTRY_OVERVIEW;
    }

    // ========================================================================================
    // CODEX MANUAL ENTRIES
    // ========================================================================================

    private static final class SectionBlock {
        final String heading;
        final List<ParaItem> items = new ArrayList<>();

        SectionBlock(String heading) {
            this.heading = heading;
        }

        SectionBlock p(String text, String... highlights) {
            items.add(new ParaItem(false, null, text, highlights));
            return this;
        }

        SectionBlock b(String text, String... highlights) {
            items.add(new ParaItem(true, null, text, highlights));
            return this;
        }

        SectionBlock icon(String iconSprite, String text, String... highlights) {
            items.add(new ParaItem(false, iconSprite, text, highlights));
            return this;
        }
    }

    private static final class ParaItem {
        final boolean bullet;
        final String iconSprite;
        final String text;
        final String[] highlights;

        ParaItem(boolean bullet, String iconSprite, String text, String[] highlights) {
            this.bullet = bullet;
            this.iconSprite = iconSprite;
            this.text = text;
            this.highlights = highlights;
        }
    }

    private static final class DivisionManualEntry extends CodexEntryV2 {
        private final String subtitle;
        private final List<SectionBlock> sections;

        DivisionManualEntry(String id, String title, String icon, String subtitle, List<SectionBlock> sections) {
            super(id, title, icon);
            this.subtitle = subtitle;
            this.sections = sections;
            setParam(id);
        }

        @Override
        public boolean hasCustomDetailPanel() {
            return true;
        }

        @Override
        public void createCustomDetail(CustomPanelAPI panel, UIPanelAPI relatedEntries, CodexDialogAPI codex) {
            Color base = Misc.getBasePlayerColor();
            Color dark = Misc.getDarkPlayerColor();
            Color hl = Misc.getHighlightColor();
            Color gray = Misc.getGrayColor();

            float opad = 10f;
            float horzBoxPad = 30f;
            float width = panel.getPosition().getWidth();
            float tw = relatedEntries != null
                    ? (width - 290f - opad - horzBoxPad + 10f)
                    : (width - horzBoxPad);
            tw = Math.max(320f, tw);

            TooltipMakerAPI text = panel.createUIElement(tw, 0f, false);
            text.setParaOrbitronLarge();
            text.addPara(getTitle(), base, 0f);
            text.setParaSmallInsignia();
            if (subtitle != null && !subtitle.isEmpty()) {
                text.addPara(subtitle, gray, 4f);
            }

            for (SectionBlock sec : sections) {
                if (sec.heading != null && !sec.heading.isEmpty()) {
                    text.addSectionHeading(sec.heading, base, dark, com.fs.starfarer.api.ui.Alignment.LMID, opad);
                }
                boolean prevBullet = false;
                for (int i = 0; i < sec.items.size(); i++) {
                    ParaItem item = sec.items.get(i);
                    float pad = (i == 0) ? 6f : (item.bullet && prevBullet ? 3f : 8f);
                    if (item.iconSprite != null && !item.iconSprite.isEmpty()) {
                        text.setBulletedListMode(null);
                        TooltipMakerAPI imgText = text.beginImageWithText(item.iconSprite, 28f);
                        if (item.highlights != null && item.highlights.length > 0) {
                            imgText.addPara(item.text, 0f, hl, item.highlights);
                        } else {
                            imgText.addPara(item.text, 0f);
                        }
                        text.addImageWithText(pad);
                        prevBullet = false;
                    } else {
                        text.setBulletedListMode(item.bullet ? BaseIntelPlugin.BULLET : null);
                        if (item.highlights != null && item.highlights.length > 0) {
                            text.addPara(item.text, pad, hl, item.highlights);
                        } else {
                            text.addPara(item.text, pad);
                        }
                        prevBullet = item.bullet;
                    }
                }
                text.setBulletedListMode(null);
            }

            panel.updateUIElementSizeAndMakeItProcessInput(text);
            UIPanelAPI box = panel.wrapTooltipWithBox(text);
            panel.addComponent(box).inTL(0f, 0f);
            if (relatedEntries != null) {
                panel.addComponent(relatedEntries).inTR(0f, 0f);
            }

            float height = box.getPosition().getHeight();
            if (relatedEntries != null) {
                height = Math.max(height, relatedEntries.getPosition().getHeight());
            }
            panel.getPosition().setSize(width, height);
        }
    }

    private static DivisionManualEntry buildOverviewEntry(String icon) {
        List<SectionBlock> secs = new ArrayList<>();
        secs.add(new SectionBlock("Quick Start - Works Automatically")
                .p("Divisions: Command & Control organizes both your fleet and AI fleets into permanent tactical divisions of up to %s. You can fight right out of the box without changing any settings.",
                        "10 ships")
                .p("When you want finer control, open the %s tab (%s) and use the two buttons on the left sidebar: %s (fleet-wide rules) and %s (your ship divisions).",
                        "Fleet", "F", "Fleet Doctrine", "Fleet Organization")
                .p("Tip: Hover any Division AI button, dropdown, or label and press %s to jump straight to its Codex page.",
                        "F2"));

        secs.add(new SectionBlock("The Command & Control (C2) Stack")
                .b("%s - Sets fleet doctrine, organizes divisions, pilots the flagship, and issues tactical map orders that take top priority.",
                        "1. Player")
                .b("%s - Commands the entire battle group from your designated command ship. Chooses the overall battle phase, designates the Main Effort, and coordinates multi-division plays.",
                        "2. The Officer in Tactical Command")
                .b("%s - Commands up to 10 ships inside a single division. Executes your assigned Opening and Main Missions, keeps formation, and rotates damaged or high-flux ships behind the line.",
                        "3. Division Leader")
                .b("%s - Governs an individual ship's helmsmanship, station-keeping, standoff range, braking, and weapon or vent discipline within its division.",
                        "4. Ship Captain")
                .b("%s - Handles moment-to-moment weapon firing, shield facing, point defense, and local collision or flux reactions underneath the Ship Captain.",
                        "5. Basic Ship AI"));

        return new DivisionManualEntry(
                ENTRY_OVERVIEW,
                "Overview & Quick Start",
                icon,
                "How Divisions: Command & Control structures fleet combat without micromanagement.",
                secs);
    }

    private static DivisionManualEntry buildOtcEntry(String icon) {
        List<SectionBlock> secs = new ArrayList<>();
        secs.add(new SectionBlock("Command Style (How Tight Is the Leash?)")
                .p("Your %s sets how much freedom Division Leaders have versus following central fleet orders:",
                        "Command Style")
                .b("%s - Tight central control. The OTC assigns targets, stations, and formations directly to every division.",
                        "Positive Control")
                .b("%s - Balanced default. The OTC directs the Main Effort and key plays while Division Leaders adapt locally.",
                        "Command by Direction")
                .b("%s - Mission command. Divisions execute their own standing missions unless the OTC steps in to prevent a crisis.",
                        "Command by Negation")
                .b("%s - Hands-off maneuver. Divisions fight independently while the OTC only coordinates target distribution.",
                        "Fire Distribution Only"));

        secs.add(new SectionBlock("Conduct of Battle & Pre-Battle Overrides")
                .p("In %s (or on the pre-battle Deployment Screen via %s), choose an operational template - %s, %s, or %s - to set how your battle line anchors, captures objectives, and commits reserves.",
                        "Fleet Doctrine", "Conduct of Battle", "Linear Warfare", "Light & Strike Warfare", "Carrier-Centric Ops")
                .p("Changes made on the Deployment Screen apply to %s without overwriting your saved campaign doctrine.",
                        "that battle only"));

        secs.add(new SectionBlock("Command Ship Loss & Succession")
                .p("Designate your OTC on the %s screen. Failing to deploy your designated OTC ship incurs %s and %s. If the OTC flagship is destroyed in combat, fleet-wide C2 is disrupted for %s before command passes to the next senior Division Leader.",
                        "Fleet Organization", "-75% Max Command Points", "-50% CP regeneration", "120 seconds"));

        return new DivisionManualEntry(
                ENTRY_OTC,
                "Officer in Tactical Command (OTC)",
                icon,
                "Fleet-level command authority, Conduct of Battle templates, and command succession.",
                secs);
    }

    private static DivisionManualEntry buildOrganizationEntry(String icon) {
        List<SectionBlock> secs = new ArrayList<>();
        secs.add(new SectionBlock("Building & Editing Divisions")
                .b("%s or %s any ship icon in %s to move ships between divisions or the Unassigned Pool (up to %s per division).",
                        "Drag-and-drop", "Right-click", "Fleet Organization", "10 ships")
                .b("%s a ship inside a division card to make that ship the %s (marked with a gold star).",
                        "Left-click", "Division Leader")
                .b("Click %s on any division card to open its dedicated settings screen, or use %s to sort unassigned hulls automatically.",
                        "Configure ->", "Auto-Assign"));

        secs.add(new SectionBlock("Why Division Leader Personalities Matter")
                .p("A division's aggressiveness and willingness to press attacks come naturally from its %s's Starsector officer personality - no separate posture micro-toggles needed:",
                        "Division Leader")
                .b("%s officers press attacks at lower force odds and stay in the line to higher flux before rotating out.",
                        "Aggressive & Reckless")
                .b("%s officers fight methodically at balanced odds and rotate venting ships cleanly.",
                        "Steady")
                .b("%s officers keep safer standoff buffers, avoid risky brawls, and rotate out early when pressured.",
                        "Cautious & Timid"));

        return new DivisionManualEntry(
                ENTRY_ORGANIZATION,
                "Fleet Organization & Leaders",
                icon,
                "Assigning ships, picking Division Leaders, and how officer personalities drive combat behavior.",
                secs);
    }

    private static DivisionManualEntry buildMissionsEntry(String icon) {
        List<SectionBlock> secs = new ArrayList<>();
        secs.add(new SectionBlock("Division Type & Division Creation")
                .p("Each division's %s (such as %s, %s, %s, %s, %s, or %s) determines which defaults it inherits from %s -> %s. Meanwhile, %s -> %s controls which specialist division types are instantiated from your ships (including whether %s count as warships or join Service Groups) and which roles fold into standard gun-line divisions when turned OFF.",
                        "Division Type",
                        "Line Cruiser Div",
                        "Strike Destroyer Div",
                        "Missile Cruiser Div",
                        "Fleet Carrier Div",
                        "Battlecarrier Div",
                        "Aviation Cruiser / Destroyer Div",
                        "Fleet Doctrine",
                        "Division Defaults",
                        "Fleet Doctrine",
                        "Division Creation",
                        "Combat Freighters"));

        secs.add(new SectionBlock("Main Mission & Opening Mission")
                .p("Every division has a %s (its core job for the battle) and an %s (what it does during the first %s while fleets close into contact):",
                        "Main Mission", "Opening Mission", "30-60 seconds")
                .b("%s - Advances into effective gun range to fix and break the enemy battle line.",
                        "Close Action")
                .b("%s - Trades fire near maximum gun range, gives ground when pressed, and rotates out earlier on flux.",
                        "Standoff Engagement")
                .b("%s - Captures early objectives and holds contact at range without overcommitting.",
                        "Scouting")
                .b("%s - Sweeps wide around enemy flanks to strike exposed carriers, artillery, and rear arcs.",
                        "Envelopment")
                .b("%s - Marshals outside gun range, surges in for a synchronized missile/torpedo salvo, then breaks off.",
                        "ToT Alpha Strike")
                .b("%s - Stations ahead of friendly capitals and carriers to intercept flankers, bombers, and missiles.",
                        "Screen")
                .b("%s - Holds astern behind the battle line until the OTC commits it to reinforce or exploit a breakthrough.",
                        "Reserve"));

        secs.add(new SectionBlock("Carrier Missions & Strike Craft Options")
                .p("Carrier archetypes unlock dedicated carrier missions (%s, %s, %s, and %s/%s/%s Voidstrikes). Any division containing fighter bays or %s can also expand %s to customize wing targeting and coordinated Voidstrike waves.",
                        "Carrier Standoff", "Armed Recon", "Combat Space Patrol", "Cyclic", "Deckload", "Alpha", "Converted Hangar", "Strike Craft Options"));

        return new DivisionManualEntry(
                ENTRY_MISSIONS,
                "Division Types & Missions",
                icon,
                "Choosing a division's role, Opening vs. Main Missions, and carrier Voidstrike doctrine.",
                secs);
    }

    private static DivisionManualEntry buildFormationsEntry(String icon) {
        List<SectionBlock> secs = new ArrayList<>();
        secs.add(new SectionBlock("Line Station & Standoff Range")
                .b("%s (%s, %s, %s) sets whether the division deploys ahead of, inside, or astern of the main battle line.",
                        "Line Station", "Van", "Center", "Rear")
                .b("%s sets preferred engagement distance. Click %s to sync standoff automatically to the division's longest non-PD gun battery, or %s to follow Fleet Doctrine.",
                        "Standoff Range", "Match Guns", "Inherit"));

        secs.add(new SectionBlock("Formations & Cohesion")
                .b("%s selects the geometric arrangement of ships (%s, %s, %s, %s, %s, etc.).",
                        "Formation", "Line Abreast", "Forward Wedge", "Echelon", "Line Ahead", "Protective Circle")
                .b("%s (%s, %s, %s, %s) controls how tightly captains hold formation slots once shooting starts - from tight gunline dress (%s) to wide individual skirmishing (%s).",
                        "Cohesion", "Strict", "Balanced", "Loose", "Free", "Strict", "Free"));

        secs.add(new SectionBlock("Speed Governor & Engine Telegraph (Better Combat)")
                .p("When %s is active, %s is enabled by default so heavy hulls do not overshoot formation stops under high inertia. %s (%s, %s, %s, %s, %s) caps top speed to safe braking envelopes. In %s Starsector, Speed Governor is %s by default because ships already brake cleanly.",
                        "Better Combat", "Speed Governor", "Engine Telegraph", "Auto", "1/3", "1/2", "Full", "Flank", "vanilla", "Off"));

        return new DivisionManualEntry(
                ENTRY_FORMATIONS,
                "Formations, Cohesion & Speed Governor",
                icon,
                "Line Station, Standoff Range, formation geometry, cohesion, and Better Combat inertia governing.",
                secs);
    }

    private static DivisionManualEntry buildWarroomEntry(String icon) {
        String timeIcon = safeSprite("division_ai", "icon_overlay_time", "graphics/icons/warroom/icon_overlay_time.png");
        String spaceIcon = safeSprite("division_ai", "icon_overlay_space", "graphics/icons/warroom/icon_overlay_space.png");
        String fpIcon = safeSprite("division_ai", "icon_overlay_firepower", "graphics/icons/warroom/icon_overlay_firepower.png");
        String convIcon = safeSprite("division_ai", "icon_overlay_convergence", "graphics/icons/warroom/icon_overlay_convergence.png");

        List<SectionBlock> secs = new ArrayList<>();
        secs.add(new SectionBlock("Commanding Divisions on the Tactical Map (Tab)")
                .p("Press %s in combat to open the Tactical Map. Division polygons, formation headings, and intent vectors show what each division is doing at a glance.",
                        "Tab")
                .b("Select a division or assign map objectives (Capture, Defend, Engage, Rally) - Division AI translates your map orders into coordinated division-wide maneuvers that outrank routine OTC tasking.")
                .b("Use the top-left Warroom controls to adjust %s or trigger fleet-wide orders on the fly.",
                        "Command Style"));

        secs.add(new SectionBlock("Time, Space & Firepower Overlays")
                .p("Three square icon buttons on the right side of the Tactical Map toggle live tactical overlays. %s",
                        "You may use these as a source of information, but they are chaotic to the human eye and are mainly for the benefit of your C2 chain.")
                .icon(timeIcon,
                        "%s - Visualizes division mobility reach and how quickly each formation can close, flank, or disengage before opposing forces can react.",
                        "Time")
                .icon(spaceIcon,
                        "%s - Visualizes controlled and contested battle space around divisions, stations, and maneuver corridors.",
                        "Space")
                .icon(fpIcon,
                        "%s - Visualizes directional weapon-arc threat roses, crossfire pockets, and blind spots across short, medium, and strike range bands.",
                        "Firepower"));

        secs.add(new SectionBlock("Convergence")
                .icon(convIcon,
                        "%s - Although the calculus of space warfare is decently understood by trained crews and educated officers, it is rumored that forbidden technologies unlock a higher understanding of the rhythms of combat.",
                        "Convergence"));

        secs.add(new SectionBlock("Combat Chatter & Overlay Settings")
                .p("You can tune on-screen C2 radio chatter verbosity (%s, %s, %s, %s, %s) and polygon opacity anytime in %s (%s).",
                        "Verbose", "Standard", "Minimal", "OTC Only", "Off", "Mod Settings", "LunaLib"));

        return new DivisionManualEntry(
                ENTRY_WARROOM,
                "Tactical Map (Tab) & Combat C2",
                icon,
                "Giving division orders in combat, reading intent vectors, and T-S-F-C tactical overlays.",
                secs);
    }
}
