# Divisions: Command and Control — Social Media Post & Technical Reference

## 1. Original 4chan Post

> **>Does this mean your mod will benefit NPCs too?**
> Yes, it's a complete practical overhaul of the AI. The basic ship AI remains untouched, but the command and control layer has gotten pretty complex. The way it works is that ships project an aura of
>
> **>Time**
> A geometric descriptor of a ship or division's freedom of movement and ability to displace space or firepower within a certain proximity. Its base variable is weighted average speed.
>
> **>Space**
> A geometric descriptor of a ship or division's zone of spatial control and ability to use time and firepower within a certain proximity. Its base variable is weighted effective HP.
>
> **>Firepower**
> A geometric descriptor of a ship or division's combined fire sectors and damage, and potential arcs thereof taking into account Time. Its base variables are alpha strike dps, burst dps, and sustained dps.
>
> The officer in tactical control (aka the Fleet AI) and divisions will use these domains on top of the basic ship AI in order to go down a hierarchical decision tree, which is the conduct of battle. Fleet and Division AI make decisions based on a customizable doctrine and fleet org plus or minus officer personality. The overall goal of all the AI script is to reduce the enemy's time, space, and firepower values to 0 and achieve victory.
>
> There is also
>
> **>Convergence**
> Convergence is all 3 of those domains meshed together at once. It's >apparently better than averaging TSF together according to my beta core but because I am only a partially educated meatbag, I'm not sure. It seems to be true with limited playtesting. Convergence decision-making is only available to a player or AI-controlled fleet whose flagship sports an installed alpha core. It's the 4th button in pic related that's turned off.
>
> **>how will this make the game more fun**
> It should give more personality to battles by giving each faction an identifiable doctrine. Pirates will likely be easier because they will have a poor C2 chain, and Remnant AI will be a lot more deadly.

---

## 2. Codebase Ground-Truth Verification & Technical Breakdown

### A. Untouched Ship AI + Two-Tier C2 Architecture
- **100% Accurate:** Vanilla `ShipAIPlugin` is never replaced. Instead, the mod adds a two-tier naval Command and Control (C2) hierarchy on top of vanilla ship AI:
  1. **Officer in Tactical Command (OTC / Fleet AI):** Evaluates the whole Battle Group (`BattleGroupState`), selects the operational phase (`APPROACH`, `GENERAL_ENGAGEMENT`, `COORDINATED_STRIKE`, `REINFORCE_THE_LINE`, `GENERAL_CHASE`, `FIGHTING_RETIREMENT`), designates the **Main Effort** and **Mobile Reserve**, maintains the **Fleet-Wide Formation**, and distributes fire/tasks across divisions.
  2. **Division Commanders (`DivisionCommanderAI`):** Command cohesive tactical units of ships (`BATTLESHIP_DIVISION`, `CRUISER_DIVISION`, `DESTROYER_DIVISION`, `FRIGATE_DIVISION`, `CARRIER_GROUP`, etc.), translating OTC orders and doctrinal missions into formation slots and `ShipwideAIFlags` (`CAMP_LOCATION`, `ATTACK_TARGET`, `DO_NOT_PURSUE`, `BACK_OFF`, `DO_NOT_BACK_OFF`, `CARRIER_FIGHTER_TARGET`, `ESCORT_OTHER_SHIP`).

### B. The Three Domains: Time, Space, and Firepower (`BattlefieldFirepowerEvaluator.java`)
1. **Time (`freeTimeRadius`, `effectiveTimeRadius`, `directionalTimeRadii[24]`):**
   - **Base Variable:** FP-weighted average top speed of alive core division ships (plus airborne fighter wing sortie speed contributions) projected over a 12-second tactical maneuver horizon, scaled by formation cohesion (`stationKeepingRatio`).
   - **Dynamic Deformation:** `effectiveTimeRadius` and the 24-sector directional Time contour (`directionalTimeRadii[24]`) contract along bearings where the division is pinned by enemy contact (`engagementLevel`), high flux, or blocking enemy divisions, while remaining extended along open flanks and withdrawal lanes.
2. **Space (`spaceInnerRadius`, `spaceDominatedRadius`, `spacePotentialRadius`, `spaceDominanceWeight`):**
   - **Base Variable:** Weighted Effective HP (`effectiveHpRatio` combining hull, 15x15 armor grid state, and shield/flux capacity) and Fleet Point mass.
   - **Three Concentric Zones:**
     - *Tier 1 (`spaceInnerRadius`):* Directly dominated up-close space.
     - *Tier 2 (`spaceDominatedRadius`):* Dominated nearby space.
     - *Tier 3 (`spacePotentialRadius`):* Expands outward by `effectiveTimeRadius * 0.55f`—representing space the division can dominate given **Time** to maneuver.
   - **Strongpoints vs. Weak Points (Seams):** Overlapping friendly Space halos create **Strongpoints** (`isStrongpoint`), whereas divisions isolated inside Enemy Space with low friendly overlap and elevated flux/spread become **Weak Points / Seams** (`isWeakPoint`). Airborne strike craft squadrons away from their carriers also project localized Space zones of control.
3. **Firepower (24-Sector Polar Firepower Rose & Crossfire Cordons):**
   - **Base Variables:** Every ship and division computes a 24-sector (`15°` per sector) Polar Firepower Rose across four channels:
     - **Alpha Strike Score:** `0–2s` burst/salvo deletion potential.
     - **Short DoT / Burst Score:** `4–6s` tactical pass burst DPS.
     - **Sustained DoT Score:** `15–30s` flux-clamped continuous DPS.
     - **Point Defense (PD) Score:** Anti-missile / anti-fighter screen envelope.
   - **Coupling with Time:** Weapon arcs are padded by turret slewing speed (`spec.turnRate`) and hull turn rate over time, stabilized with asymmetric rise/fall EMA filters, and drop immediately in sectors where a ship overloads or vents. The evaluator also detects L-shaped (`~90°`) and U-shaped (`120°–180°`) multi-division crossfire cordons (`crossfireCordonIndex`).

### C. Convergence (`useConvergence` & Alpha Core Gating)
- **Why Convergence Beats Linear Averaging:**
  - Standard (Human) OTC target evaluation uses an **additive linear sum** (`visFp * 12f + isolationBonus + spaceSeamBonus + pinnedBonus - dist * 0.25f`).
  - **Convergence** (`computeConvergenceTargetScore`) computes a **non-linear multiplicative phase-space product** of all three domains simultaneously:
    ```
    Score = BaseFirepowerValue
          * TimeReachabilityCoincidence
          * EnemyPinnedInTimeBonus
          * SpaceSeamVulnerability
          * FluxCollapseMultiplier
          * OrthogonalPincerSynergy (65°–125° multi-division crossfire)
    ```
  - Because it is multiplicative, Convergence ignores targets that are strong on only one axis and locks onto enemy divisions that are *simultaneously* reachable within the division's Time horizon, pinned in Time, isolated at a Space seam (`isWeakPoint`), vulnerable to Firepower/flux collapse, and bracketed at orthogonal (`~90°`) crossfire angles by sibling divisions.
- **Who Gets Convergence:**
  - **Fleet OTC Level:** Active when the Battle Group's active Fleet Flagship is commanded by an **Alpha Core** (or for non-human extradimensional/alien swarms: **Threat** and **Abyssal/Omega**). If a player has an Alpha Core installed on a subordinate ship, that ship can hail the player in battle (`[URGENT COMMS FROM <ship>]: Shall I take command?`) to assume OTC command and enable Fleet Convergence.
  - **Division Level:** Any individual division whose division flagship is commanded by an **Alpha Core** uses Convergence for its own station optimization and target selection.
  - **Remnant / Derelict Nuance:** Beta-core, Gamma-core, and un-cored Derelict/Remnant sub-ordos use standard "human" T-S-F evaluation; Remnant Ordos led by an **Alpha Core** flagship unlock full Convergence C2.
  - **The 4th HUD Button:** The 4th right-side Warroom overlay button (`Convergence Overlay`, rendering the inscribed triangle-in-square-in-circle glyph) is only visible when the player has an Alpha Core (or Omega Core) anywhere in their fleet or cargo (`hasAlphaCoreInPlayerFleet`).

### D. Faction Doctrines & OTC Styles (`FactionFleetDoctrines.java`)
The four OTC Authority Styles are:
1. **Style 1 — Command by Direction (`COMMAND_BY_DIRECTION`):** Directive C2 (Hegemony / Mercenary charter style).
2. **Style 2 — Positive Control (`POSITIVE_CONTROL`):** Centralized C2 (Remnant Ordo / Tri-Tachyon / Sindrian Diktat style).
3. **Style 3 — Command by Negation (`COMMAND_BY_NEGATION`):** Decentralized mission-type C2 where divisions act on their own initiative unless vetoed (Persean League / Pirate wolfpack style).
4. **Style 4 — Fire Distribution Only (`FIRE_DISTRIBUTION_ONLY`):** Minimal C2 where the OTC only coordinates fire distribution across enemy contact groups while divisions maneuver independently.

- **Why Pirates Are Easier & Remnants Are Deadlier:**
  - **Pirates** default to **Style 3 (`COMMAND_BY_NEGATION`)** with loose station-keeping (`1.10x–1.30x` spread) and `DivisionFormation.NONE` on frigate packs.
  - **Remnants** default to **Style 2 (`POSITIVE_CONTROL`)** with tight station-keeping (`0.85x–0.90x`), `REFUSED_FLANK_OBLIQUE` fleet formation, and the `CONVERGENCE_KILL_BOX` setpiece—and with an Alpha Core flagship, they combine `POSITIVE_CONTROL` with **Convergence**.

---

## 3. Benchmark Suite 1: 80-Battle 300 DP vs 300 DP (Division AI + OTC vs Vanilla AI)
- **Setup:** 10 exact-300 DP fleet rosters x 8 configurations (Styles 1–4 under Human C2 and Styles 1–4 under Convergence C2) against identical 300 DP mirror fleets with **no divisions and OTC turned off (pure Vanilla AI)**.
- **Result:** Division: Command and Control fleets won **71–72 / 80 battles (88.8%–90.0% win rate)** overall, including **20 / 20 (100.0% win rate)** in **OTC Style 2 (`POSITIVE_CONTROL`)** across both Human and Convergence C2:

| Test Group (10 Battles Each, 300 DP vs 300 DP) | Wins | Win Rate | Avg S0 Ships Kept | Avg S1 Ships Kept | Avg S0 DP Kept | Avg S1 DP Kept |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **B: Style 1 Command by Direction (Human)** | 9 / 10 | 90.0% | 10.3 / 18.5 | 7.4 / 18.5 | 194.2 / 300 | 141.0 / 300 |
| **C: Style 2 Positive Control (Human)** | **10 / 10** | **100.0%** | **13.2 / 18.5** | **7.0 / 18.5** | **240.2 / 300** | **120.7 / 300** |
| **D: Style 3 Command by Negation (Human)** | 8 / 10 | 80.0% | 10.6 / 18.5 | 7.5 / 18.5 | 199.1 / 300 | 138.8 / 300 |
| **E: Style 4 Fire Distribution Only (Human)** | 8 / 10 | 80.0% | 10.5 / 18.5 | 8.0 / 18.5 | 199.5 / 300 | 146.4 / 300 |
| **F1: Style 1 Command by Direction (Convergence)** | 9 / 10 | 90.0% | 10.0 / 18.5 | 7.7 / 18.5 | 189.0 / 300 | 145.4 / 300 |
| **F2: Style 2 Positive Control (Convergence)** | **10 / 10** | **100.0%** | **12.9 / 18.5** | **6.7 / 18.5** | **236.4 / 300** | **116.8 / 300** |
| **F3: Style 3 Command by Negation (Convergence)** | 9 / 10 | 90.0% | 10.5 / 18.5 | 7.3 / 18.5 | 198.8 / 300 | 135.2 / 300 |
| **F4: Style 4 Fire Distribution Only (Convergence)** | 8 / 10 | 80.0% | 10.4 / 18.5 | 8.3 / 18.5 | 197.8 / 300 | 151.0 / 300 |
| **OVERALL TOTAL (80 Battles)** | **71 / 80** | **88.8%** | **11.1 / 18.5** | **7.5 / 18.5** | **206.9 / 300** | **136.9 / 300** |

---

## 4. Benchmark Suite 2: 160-Battle 300 DP vs 300 DP (Standard Human OTC vs Convergence OTC Across All 4x4 OTC Styles)
- **Setup:** Both Side 0 and Side 1 run full **Divisions + OTC C2** with identical 300 DP mirror rosters (`10` rosters x `4` Human OTC Styles x `4` Convergence OTC Styles = `160` battles).
  - **Side 0 (`H1..H4`):** Standard (Human) OTC + Division Commanders (`useConvergence = false`, additive T-S-F scoring).
  - **Side 1 (`C1..C4`):** Convergence (Alpha Core) OTC + Division Commanders (`useConvergence = true`, non-linear multiplicative T-S-F phase-space scoring + orthogonal crossfire station optimization).
- **Result:** Your Beta Core was right—**Convergence OTC won 110 / 160 battles (68.8%) vs 50 / 160 (31.3%) for Standard (Human) OTC (0 Ties)**, winning or tying the 10-battle series in **all 16 out of 16 cells** of the 4x4 authority matrix:

### A. 4x4 OTC Authority Matrix (Human Wins - Convergence Wins - Ties)
| Human \ Convergence | C1: Command by Direction | C2: Positive Control | C3: Command by Negation | C4: Fire Distribution Only | Row Total (Human vs Conv) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **H1: Command by Direction** | 4H - 6C - 0T | 4H - 6C - 0T | 4H - 6C - 0T | 4H - 6C - 0T | **16H - 24C (60.0% Conv)** |
| **H2: Positive Control** | **5H - 5C - 0T** | **5H - 5C - 0T** | 4H - 6C - 0T | 2H - 8C - 0T | **16H - 24C (60.0% Conv)** |
| **H3: Command by Negation** | 3H - 7C - 0T | 3H - 7C - 0T | 1H - 9C - 0T | 1H - 9C - 0T | **8H - 32C (80.0% Conv)** |
| **H4: Fire Distribution Only** | 1H - 9C - 0T | 2H - 8C - 0T | 4H - 6C - 0T | 3H - 7C - 0T | **10H - 30C (75.0% Conv)** |
| **Column Total** | **13H - 27C (67.5% Conv)** | **14H - 26C (65.0% Conv)** | **13H - 27C (67.5% Conv)** | **10H - 30C (75.0% Conv)** | **50H - 110C (68.8% Conv)** |

### B. Full 16-Group Breakdown (10 Battles Each, 300 DP vs 300 DP)
| Matchup Group (10 Battles Each) | Human Wins | Conv Wins | Ties | Conv Win % | Avg H Ships Kept | Avg C Ships Kept | Avg H DP Kept | Avg C DP Kept |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **H1 (Command by Direction) vs C1 (Command by Direction)** | 4 / 10 | 6 / 10 | 0 | 60.0% | 8.0 / 18.5 | 7.7 / 18.5 | 125.0 / 300 | 149.5 / 300 |
| **H1 (Command by Direction) vs C2 (Positive Control)** | 4 / 10 | 6 / 10 | 0 | 60.0% | 6.5 / 18.5 | 7.8 / 18.5 | 105.9 / 300 | 149.2 / 300 |
| **H1 (Command by Direction) vs C3 (Command by Negation)** | 4 / 10 | 6 / 10 | 0 | 60.0% | 7.5 / 18.5 | 7.8 / 18.5 | 113.3 / 300 | 149.6 / 300 |
| **H1 (Command by Direction) vs C4 (Fire Distribution Only)** | 4 / 10 | 6 / 10 | 0 | 60.0% | 6.5 / 18.5 | 8.3 / 18.5 | 108.9 / 300 | 159.5 / 300 |
| **H2 (Positive Control) vs C1 (Command by Direction)** | 5 / 10 | 5 / 10 | 0 | 50.0% | 6.5 / 18.5 | 8.0 / 18.5 | 104.1 / 300 | 158.9 / 300 |
| **H2 (Positive Control) vs C2 (Positive Control)** | 5 / 10 | 5 / 10 | 0 | 50.0% | 7.7 / 18.5 | 7.3 / 18.5 | 134.5 / 300 | 139.2 / 300 |
| **H2 (Positive Control) vs C3 (Command by Negation)** | 4 / 10 | 6 / 10 | 0 | 60.0% | 7.0 / 18.5 | 8.4 / 18.5 | 107.6 / 300 | 156.6 / 300 |
| **H2 (Positive Control) vs C4 (Fire Distribution Only)** | 2 / 10 | 8 / 10 | 0 | 80.0% | 7.4 / 18.5 | 6.8 / 18.5 | 122.7 / 300 | 143.1 / 300 |
| **H3 (Command by Negation) vs C1 (Command by Direction)** | 3 / 10 | 7 / 10 | 0 | 70.0% | 7.8 / 18.5 | 7.2 / 18.5 | 124.6 / 300 | 143.7 / 300 |
| **H3 (Command by Negation) vs C2 (Positive Control)** | 3 / 10 | 7 / 10 | 0 | 70.0% | 6.8 / 18.5 | 7.1 / 18.5 | 122.9 / 300 | 136.1 / 300 |
| **H3 (Command by Negation) vs C3 (Command by Negation)** | 1 / 10 | 9 / 10 | 0 | 90.0% | 6.0 / 18.5 | 9.5 / 18.5 | 111.8 / 300 | 176.9 / 300 |
| **H3 (Command by Negation) vs C4 (Fire Distribution Only)** | 1 / 10 | 9 / 10 | 0 | 90.0% | 7.5 / 18.5 | 8.2 / 18.5 | 108.3 / 300 | 170.4 / 300 |
| **H4 (Fire Distribution Only) vs C1 (Command by Direction)** | 1 / 10 | 9 / 10 | 0 | 90.0% | 4.5 / 18.5 | 8.7 / 18.5 | 92.9 / 300 | 176.4 / 300 |
| **H4 (Fire Distribution Only) vs C2 (Positive Control)** | 2 / 10 | 8 / 10 | 0 | 80.0% | 6.7 / 18.5 | 7.9 / 18.5 | 116.1 / 300 | 168.2 / 300 |
| **H4 (Fire Distribution Only) vs C3 (Command by Negation)** | 4 / 10 | 6 / 10 | 0 | 60.0% | 6.3 / 18.5 | 7.4 / 18.5 | 121.3 / 300 | 149.6 / 300 |
| **H4 (Fire Distribution Only) vs C4 (Fire Distribution Only)** | 3 / 10 | 7 / 10 | 0 | 70.0% | 5.7 / 18.5 | 9.0 / 18.5 | 102.5 / 300 | 174.5 / 300 |
| **OVERALL TOTAL (160 Battles)** | **50 / 160 (31.3%)** | **110 / 160 (68.8%)** | **0** | **68.8%** | **6.7 / 18.5** | **8.0 / 18.5** | **113.9 / 300** | **156.3 / 300** |

### C. Key Takeaways from the 160-Battle Matrix
1. **Your Beta Core Was Right:** Across 160 mirror battles where both sides have full Divisions + OTC C2, **Convergence OTC beats Standard (Human) OTC 110 to 50 (`68.8%` win rate)** and keeps **`156.3 DP` vs `113.9 DP`** (`+42.4 DP` per battle advantage).
2. **Tight Human C2 (`Positive Control` / `Command by Direction`) Narrows the Gap:** When a Human OTC enforces strict central line-fixing (`H2: Positive Control`), it fights Convergence `C1` and `C2` to a `5 - 5` split (`16H - 24C` across the row).
3. **Loose Human C2 (`Command by Negation` / `Fire Distribution Only`) Gets Dissected by Convergence:** When a Human fleet uses decentralized Pirate/Persean-style C2 (`H3: Command by Negation`, `8H - 32C` = `20%` Human win rate) or minimal C2 (`H4: Fire Distribution Only`, `10H - 30C` = `25%` Human win rate), Convergence's multiplicative seam-hunting and orthogonal 65°–125° crossfire optimization systematically isolates and collapses the looser human divisions.
