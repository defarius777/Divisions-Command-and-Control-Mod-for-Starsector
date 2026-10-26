# Naval Terminology Glossary (v0.5.1)

Player-facing terms follow real naval usage, mostly US Navy doctrine and line-of-battle tradition.
Starsector's own terms (flux, strike craft, DP, phase, Command Window) are kept.

Only display text changed. Enum names, save keys and code identifiers keep their old names
(`BattlegroupAuthority.HYBRID`, `PreferredSpace.FORWARD`, ...), so saves and doctrine settings carry over.

## Command & organization

| Old | New | Notes |
|---|---|---|
| Battlegroup Commander / BG Cmdr | Battle Group Commander, **OTC** (Officer in Tactical Command) | Log tags `[Player OTC]`, `[Enemy OTC]`, `[Allied OTC]` |
| Battlegroup | Battle Group | USN spelling |
| Battle Div | **Battleship Div** | BatDiv; capitals stand in for battleships |
| Mixed Carrier Div | **Carrier Group** | Carrier plus its own escorts |
| Aux Group / Auxiliary Convoy | **Service Group** | Auxiliaries (ServRon / service force) |
| Disband (division) | **Disestablish** | USN term for dissolving a unit |
| Create division | **Establish** | |

Frigate, Destroyer, Cruiser, Strike Cruiser, Fleet/Light/Escort Carrier and Phase divisions are unchanged.
The first seven are real ship-type divisions; phase ships have no real analog (closest: submarines).

## OTC authority (Fleet Doctrine → Battle Group Command)

| Old | New | Meaning |
|---|---|---|
| A. Hybrid | **A. Command by Direction** | OTC directs targets, timing, stations and flank sides within each mission |
| B. Central Planning | **B. Positive Control** | OTC orders replace missions |
| C. Mission-Type Tactics | **C. Command by Negation** | Divisions act on their own initiative unless the OTC overrides them (USN Composite Warfare doctrine) |
| D. Target Coordination Only | **D. Fire Distribution Only** | OTC only distributes targets and times strikes |

## OTC plays

| Old | New |
|---|---|
| Anchor & Fix | **Line & Fire Distribution** |
| Pincer Flanks | **Doubling** (doubling the enemy line: attacking it from both sides) |
| Timed Strikes | **Time-on-Target Strikes** (divisions go to marshal points, then strike together) |
| Reserve & Exploit | **Reserve & General Chase** |

Flank sides are now **port** / **starboard** of the battle group's attack axis. This also fixes a v0.5.0 bug
where the log called the left side "right".

## Battle phases

| Old | New |
|---|---|
| Opening Probe & Objective Seizure | **Approach & Scouting** |
| Main Line Engagement | **General Engagement** |
| Coordinated Multi-Division Strike | **Coordinated Strike** |
| Line Stabilization & Relief | **Reinforce the Line** |
| Breakthrough Exploitation | **General Chase** (historic signal to pursue a broken enemy) |
| Ordered Phased Withdrawal | **Fighting Retirement** |

Mission contexts: Objective Contest → **Contested Objectives**, Pitched Battle → **Fleet Action**,
Rearguard Escape → **Rearguard Action**, Pursuit Interception → **Interception**.

## Missions

| Old | New |
|---|---|
| a. Reconnaissance | **a. Scouting** |
| b. Dominate Space into Brawl | **b. Close Action** ("engage the enemy more closely") |
| c. Standoff at Range | **c. Standoff Engagement** |
| d. Outflank and Destroy | **d. Envelopment** |
| e. Screen | e. Screen |
| Carrier a. Reconnaissance by Strike Craft | **a. Armed Reconnaissance** |
| Carrier b. Void Superiority | **b. Space Superiority** |
| Carrier c. Protect Fleet | **c. Combat Space Patrol** (CAP → CSP) |
| Carrier d. Successive Strikes / Fire at Will | **d. Cyclic Strikes** |
| Carrier e. Division Coordinated Strike | **e. Deckload Strike** |
| Carrier f. Multi-Division Coordinated Strike | **f. Alpha Strike** |
| g. Battlegroup Reserve | **g. Reserve** |

## Stations & spacing

| Old | New |
|---|---|
| Preferred Space: Forward / Middle / Back | **Station in Line: Van / Center / Rear** |
| Intra-division spacing | **Station spacing** (ship to ship) |
| Inter-division spacing | **Division interval** (division to division) |
| Line Standoff distance | **Standoff range** (auto value now uses gun range only) |
| Inherit BG | **Inherit** (from Fleet Doctrine) |

## Division intents (combat log)

| Old | New |
|---|---|
| form up and maintain division cohesion | **close up and re-form** |
| advance and engage | **close and engage** |
| launch harassing missile attack at | **make a harassing missile attack on** |
| execute coordinated missile strike against | **deliver a time-on-target missile strike on** |
| press local overmatch against | **press home the attack on** |
| screen and escort | **take screening station on** |
| move to relieve | **move to reinforce** |
| swing wide to flank | **work around the flank of** |
| complete strike window and withdraw to reserve from | **break off and retire from** |
| rotate high-flux hulls back to reserve behind | **haul out of the line to vent behind** |
| hold in battlegroup reserve awaiting commitment | **hold in reserve, awaiting orders** |
| secure tactical objective | **take and hold** |
| anchor the battle line and fix | **form the line and fix** |
| stage for synchronized strike against | **proceed to the marshal point to strike** |
| pursue and destroy broken | **give general chase to** |
| (carriers) strike craft reconnaissance / void superiority / fleet air defense / successive attacks / division strike / multi-division strike | **fly armed reconnaissance / fly fighter sweeps for space superiority / fly combat space patrol / fly cyclic strikes / launch a deckload strike / launch an alpha strike** |

## Mod Settings (LunaLib)

"C2 Chatter" → **Command Signals**: OTC Signals, Division Signals, Multi-Division Signals.

## Save migration

On first load in v0.5.1, auto-named divisions are renamed: `Battle Div N` → `Battleship Div N`,
`Mixed Carrier Div N` → `Carrier Group N`, `Aux Group N` → `Service Group N`.
Names you typed yourself are untouched.
