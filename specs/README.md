# Spec-Driven Development (SDD) — Divisions: Command and Control

Every behavioral rule, cross-layer contract, physics scaling law, and frame-time budget in **Divisions: Command and Control** is governed by an explicit specification in `specs/` before implementation or bug-fixing.

## Workflow
1. **Spec First (`specs/*.md`)**: Before adding or modifying C2, strike-craft, helmsmanship, UI, or audio behavior, update the relevant contract table or state machine in `specs/`.
2. **Single Shared Predicate in Code**: Never duplicate eligibility conditions across caller and callee (e.g., OTC `OtcBattlePlanner` and subordinate `DivisionCommanderAI` must call the same `canAccept...` contract method with explicit entry/exit hysteresis).
3. **Property + Scenario Verification (`bash test.sh`)**: Every spec invariant is verified both by deterministic scenarios and by seeded property/fuzz checks in `DivisionAITestSuite` + `UITestHarness`.
4. **Live Runtime Audit (`BATTLE SUMMARY`)**: Every real combat session emits compact spec-compliance and frame-budget telemetry in `[DivisionAI] BATTLE SUMMARY` inside `starsector.log` (`spec=[...]` / `perf=[...]` / `music=[...]`).

## Specification Index
- [`01-c2-authority-and-contracts.md`](01-c2-authority-and-contracts.md) — OTC $\leftrightarrow$ Division Authority Matrix, Hysteresis Thresholds, Setpiece Marshaling, Line-Passing Armor Gate, and `HAUL_OUT_TO_VENT` Contract.
- [`02-strike-craft-state-machine.md`](02-strike-craft-state-machine.md) — Carrier & Strike-Craft State Machine across Fog of War, Phase Transitions, CAP Reserves, Support LPCs, and Multi-Carrier Voidstrikes.
- [`03-physics-scaling-and-perf-budgets.md`](03-physics-scaling-and-perf-budgets.md) — `CombatPhysicsProfile` Scaling Basis Matrix (`1.0x` Vanilla Parity vs. Overhauls), C2 JIT Safety Rules, and 60 Hz / 10 Hz / 2 Hz CPU & Allocation Budgets.
- [`04-dynamic-battle-music-spec.md`](04-dynamic-battle-music-spec.md) — Fleet Violent-Contact Telemetry Source Contract (`playerGroup` + `alliedAiGroup`, never passive `side0OpposingView`) & Multi-Layer Audio State Machine.
