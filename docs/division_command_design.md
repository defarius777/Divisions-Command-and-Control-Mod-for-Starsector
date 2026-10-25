# Division Command Layer — Design Plan

Status: planning draft  
Project: Division AI (`division_ai`)  
Purpose: add force-level command and control to Starsector tactical combat without replacing the competent vanilla per-ship AI.

## 1. Problem statement

Vanilla tactical ship AI is strong inside a local bubble but weak across the whole battle.

Evidence from the decompiled combat AI analysis:

- `BasicShipAI` is a modular container, not a monolith. It coordinates engine, attack, threat, shield, vent, order-response, behavior, and system modules through `ShipwideAIFlags`.
- Threat evaluation is local: approximately 1500 units.
- Target acquisition is local: approximately 2200 units, or 4400 under search-and-destroy.
- Ships do not appear to share target priority or “who is winning where” across separate firefights.
- The commander/task layer is objective-oriented. It can assign ships to assault, defend, escort, intercept, harass, retreat, and similar tasks, but it does not reason about local overmatch or division cohesion.
- `BattleGroupAI.followOrders()` is empty in the shipped build; order execution remains per-ship.

Result: large battles fragment into isolated firefights. Ships can win or lose locally, but the force does not deliberately redistribute strength, preserve reserves, rotate damaged ships, or time attacks across multiple groups.

## 2. Design goal

Add two command layers above vanilla ship AI:

1. Battlegroup Commander
   - battle-wide intent
   - division allocation
   - reserve management
   - phase/tempo control
   - command-point-aware coordination

2. Division Commander
   - mid-level control of a persistent group of ships
   - spacing, cohesion, tempo, engagement discipline
   - rotation, reinforcement, regrouping, pursuit control

Individual ships remain vanilla. The mod should influence them through the same channels vanilla uses: assignments and `ShipwideAIFlags`.

The desired player-visible result is a fleet that behaves like a force rather than a collection of isolated ship AIs.

## 3. Design principles

- Cooperate with vanilla AI; do not replace `BasicShipAI`.
- Prefer vanilla-style assignments for large movements and flags for short-lived tactical nudges.
- Make command decisions legible through logs and debug rendering before making them pretty.
- Keep computation scalable: cadence-based evaluation, spatial-grid queries, no naive all-pairs scans.
- Connect to existing gameplay: officers, personalities, command points, assignments, fleet composition, objectives, and combat readiness.
- Avoid physics or stat cheats. Commanders should change decisions, not ship capabilities.

## 4. Non-goals for early versions

The first implementation should not attempt:

- replacement ship AI
- pixel-perfect formations
- exact synchronized weapon discharge
- custom campaign UI
- a new RPG skill tree
- universal support for every modded ship/system edge case

Those can come later after the command loop proves that it changes battle outcomes.

## 5. Vanilla API seams

Relevant public API surfaces verified from `starfarer.api.jar`:

### Combat plugin and spatial access

- `CombatEngineAPI.addPlugin(EveryFrameCombatPlugin)`
- `CombatEngineAPI.getAllShips()`
- `CombatEngineAPI.getAiGridShips()`
- `CollisionGridAPI.getCheckIterator(Vector2f, float, float)`
- `CombatEngineAPI.getFleetManager(int)`
- `CombatEngineAPI.getCustomData()`

### Assignments and command-layer influence

- `CombatFleetManagerAPI.getTaskManager(boolean)`
- `CombatTaskManagerAPI.createAssignment(...)`
- `CombatTaskManagerAPI.giveAssignment(...)`
- `CombatTaskManagerAPI.removeAssignment(...)`
- `CombatTaskManagerAPI.setAssignmentWeight(...)`
- `CombatTaskManagerAPI.reassign()`
- `CombatTaskManagerAPI.getCommandPointsStat()`
- `CombatTaskManagerAPI.setFullAssault(...)`
- `CombatTaskManagerAPI.orderSearchAndDestroy(...)`
- `CombatTaskManagerAPI.orderRetreat(...)`

### Admiral-level integration

- `CombatFleetManagerAPI.getAdmiralAI()`
- `CombatFleetManagerAPI.setAdmiralAI(AdmiralAIPlugin)`
- `AdmiralAIPlugin.advance(float)`

This is a possible long-term seam, but replacing the admiral should not be the first implementation path.

### Ship-level influence

`ShipwideAIFlags` already exposes useful flags, including:

- `MOVEMENT_DEST`
- `MANEUVER_TARGET`
- `BACK_OFF`
- `DO_NOT_PURSUE`
- `DO_NOT_BACK_OFF`
- `NEEDS_HELP`
- `TARGET_FOR_SHIP_SYSTEM`
- `CUSTOM1` through `CUSTOM5`

The decompiled vanilla AI reads and writes these flags, so they are the preferred low-level cooperation channel.

### Commander and RPG metadata

- `FleetMemberAPI.getCaptain()`
- `FleetMemberAPI.getFleetCommander()`
- `PersonAPI.getPersonalityAPI()`
- `PersonAPI.getStats()`
- `MutableCharacterStatsAPI`

Early versions should use vanilla personalities. Later versions can derive custom commander profiles from officer stats and skills.

## 6. Runtime architecture

### 6.1 Combat plugin

A mod-owned `EveryFrameCombatPlugin` acts as the runtime root.

Responsibilities:

- detect battle start/end
- build initial division state
- run cadence timers
- invoke evaluators and commanders
- log decisions
- render debug overlays when enabled

The exact registration path still needs a small implementation spike. Likely options are a campaign-side bootstrap that notices combat state, or a combat listener/deployment hook. This should be proven before feature work begins.

### 6.2 Battle context scanner

Collects lightweight state every evaluation tick:

- ships by side
- current assignments
- ship positions and velocities
- hull size / role / fleet point cost
- flux state
- damage state
- retreat/hulk/expired status
- objectives
- command points
- deployed and reserve fleet members

Use engine spatial grids where possible. Avoid scanning all ship pairs.

### 6.3 Division manager

Owns division membership and identity.

Division sources, in order of implementation:

1. Automatic groupings for proof-of-concept
2. Assignment-derived groupings
3. Commander/officer-derived groupings
4. Player-defined divisions

A division should persist across the battle even as members die, retreat, or are reinforced.

### 6.4 Division evaluator

Computes the state of each division:

- centroid
- average spacing
- cohesion radius
- local friendly strength
- local enemy strength
- local overmatch ratio
- flux pressure
- damage/casualty state
- objective proximity
- isolation from other friendly divisions
- reserve/readiness state

Possible division states:

- `UNENGAGED`
- `SCREENING`
- `ENGAGED_WINNING`
- `ENGAGED_CONTESTED`
- `ENGAGED_LOSING`
- `ISOLATED`
- `OVEREXTENDED`
- `REGROUPING`
- `WITHDRAWING`
- `RESERVE`
- `BROKEN`

### 6.5 Division commander

Turns division state into stance.

Possible stances:

- `HOLD`
- `SCREEN`
- `PRESS`
- `REINFORCE`
- `REGROUP`
- `ROTATE_OUT`
- `WITHDRAW`
- `FLANK`
- `PURSUE`
- `DELAY`
- `RESERVE`

Division commanders do not directly pilot ships. They choose intent and ask the order arbiter to express that intent through assignments and flags.

### 6.6 Battlegroup commander

Owns battle-wide decisions:

- current battle phase
- which division is main effort
- which division is reserve
- whether to reinforce a losing fight
- whether to exploit a winning fight
- when to rotate divisions
- when to commit reserves
- when to shift from objectives to annihilation or withdrawal

Possible battle phases:

- `DEPLOYMENT`
- `PROBE`
- `CONTACT`
- `MAIN_EFFORT`
- `EXPLOITATION`
- `STABILIZATION`
- `WITHDRAWAL`
- `PURSUIT`

### 6.7 Order arbiter

Prevents the new layers from fighting the player or vanilla orders.

Priority model:

1. Explicit player orders
2. Battlegroup commander intent
3. Division commander stance
4. Vanilla ship self-preservation

The arbiter should:

- avoid overriding fresh player assignments
- avoid issuing redundant assignments every tick
- respect command-point availability where appropriate
- prefer existing assignments when they already match intent
- use short-duration flags for local nudges
- log every accepted, rejected, and suppressed decision

## 7. Data model

### 7.1 DivisionState

Core fields:

- id
- side
- members
- commander profile
- role
- centroid
- radius
- cohesion score
- local friendly strength
- local enemy strength
- overmatch ratio
- flux pressure
- damage state
- objective
- stance
- readiness
- isolation score
- last decision time
- last order time

### 7.2 BattlegroupState

Core fields:

- side
- divisions
- reserve divisions
- current phase
- main effort division
- command points
- battle objectives
- threat map
- player-order override state
- last reassessment time

### 7.3 CommanderProfile

Derived from a `PersonAPI` where possible.

Fields:

- person
- personality
- risk bias
- tempo bias
- cohesion bias
- pursuit discipline
- rotation threshold
- reserve commitment threshold
- command cadence
- maximum effective division size

Early profiles should be derived from vanilla personality strings. Later profiles can incorporate officer level, skills, doctrine, or custom traits.

## 8. Order expression

### 8.1 Assignment-level actions

Use vanilla task manager assignments for large-scale intent:

- `ENGAGE`
- `DEFEND`
- `ASSAULT`
- `INTERCEPT`
- `HARASS`
- `AVOID`
- `RALLY_TASK_FORCE`
- `RALLY_CARRIER`
- `RALLY_STRIKE_FORCE`
- `SEARCH_AND_DESTROY`
- `RETREAT`

Assignments are visible to vanilla AI and feel connected to normal command gameplay.

### 8.2 Flag-level actions

Use `ShipwideAIFlags` for tactical nudges:

- `MOVEMENT_DEST` — move toward a regroup or support point
- `MANEUVER_TARGET` — bias target/maneuver focus
- `BACK_OFF` — pull an overextended ship back
- `DO_NOT_PURSUE` — stop baited chases
- `NEEDS_HELP` — mark a ship requiring relief
- `CUSTOM1-5` — mod-owned division state if needed

Each flag needs a runtime test because the exact vanilla response is module-specific.

## 9. Tactics enabled by the design

### 9.1 Single-division tactics

- cohesion keeping
- role-based spacing
- controlled aggression
- bounded pursuit
- flux rotation
- leapfrog withdrawal
- rescue response
- carrier screening
- objective anchoring
- strike-then-recover

### 9.2 Multi-division tactics

- hammer and anvil
- pincer attack
- time-on-target movement
- feint and punish
- division rotation
- layered defense
- economy of force
- crossfire positioning
- relief of a losing fight
- reserve counterattack
- pursuit after rupture
- retreat interception

### 9.3 Battlegroup-level tactics

- phased battle plans
- reserve management
- axis shifting
- anti-piecemeal discipline
- tempo control
- command-point rhythm
- strategic target priority
- ordered withdrawal
- endgame judgment
- commander-personality-driven doctrines

## 10. RPG and commander layer

The RPG system should modify decision parameters, not ship stats.

Early version:

- use vanilla personalities only
- cautious commanders prefer screens, rotation, and limited pursuit
- aggressive commanders prefer pressure, flanking, and concentrated attacks
- reckless commanders commit reserves early and accept overextension
- timid commanders delay, avoid decisive fights, and withdraw earlier

Later version:

- derive commander quality from officer stats and skills
- add command traits
- let commanders improve or gain traits
- give divisions persistent identity and battle history

Possible commander effects:

- faster or slower reassessment cadence
- larger or smaller effective divisions
- better cohesion radius
- stricter pursuit discipline
- earlier or later rotation threshold
- more or less willingness to commit reserve
- command-point efficiency
- delay between decision and execution

## 11. Player-facing integration

### Phase 1: automatic divisions

No player UI. Ships are grouped automatically by role, hull size, assignment, and proximity. This proves the command loop.

### Phase 2: semi-defined divisions

Use existing gameplay structure:

- flagship/commander
- officered ships as division leaders
- task-force-like groupings
- assignment-derived groupings

### Phase 3: player-defined divisions

Possible interfaces:

- combat command screen extensions
- pre-battle deployment UI
- campaign fleet UI
- LunaLib configuration
- debug overlay and hotkeys

This phase needs its own UI investigation before implementation.

## 12. Implementation phases

### Phase 0 — Observer spike

Goal: prove combat registration and data collection.

Deliverables:

- combat plugin registration
- division auto-grouping prototype
- local strength evaluator
- assignment/state logging
- debug overlay optional

Acceptance:

- mod loads
- no behavior changes
- logs show division state over time

### Phase 1 — Single-division discipline

Goal: stop ships from isolating themselves.

Features:

- cohesion score
- bounded pursuit
- regroup stance
- backoff nudges
- needs-help response inside a division

Acceptance:

- fewer isolated ship deaths in test scenarios
- visible regrouping in logs or overlay

### Phase 2 — Rotation and reserve

Goal: create strike-then-withdraw behavior.

Features:

- readiness score
- `ROTATE_OUT` stance
- reserve division state
- rally/regroup assignments

Acceptance:

- damaged/high-flux divisions disengage
- fresh divisions replace them

### Phase 3 — Relief and reinforcement

Goal: divisions react to losing fights elsewhere.

Features:

- isolation score
- cross-division threat map
- `REINFORCE` stance
- battlegroup allocation rules

Acceptance:

- losing division receives help before collapse in scripted scenarios

### Phase 4 — Battlegroup commander

Goal: battle-wide intent and phase control.

Features:

- battle phases
- main effort selection
- reserve commitment
- axis shifting
- tempo policy

Acceptance:

- multi-division coordinated behavior appears in simulation battles

### Phase 5 — Commander profiles

Goal: make behavior depend on leaders.

Features:

- personality-derived profiles
- division leader selection
- battlegroup commander selection
- command cadence and doctrine parameters

Acceptance:

- different commanders produce measurably different decisions

### Phase 6 — Player-defined divisions and RPG depth

Goal: connect the system to normal fleet gameplay.

Features:

- player division assignment
- commander traits
- persistence
- UI and settings

Acceptance:

- player can create divisions and observe commander-specific behavior

## 13. Test scenarios

### Scenario A — isolated fight

Two friendly groups fight separated enemies. One starts losing.

Expected: battlegroup identifies isolation and reinforces or withdraws the losing division.

### Scenario B — baited pursuit

A fast enemy frigate retreats away from the main fight.

Expected: friendly ships stop pursuit at division support boundary.

### Scenario C — strike and recover

A strike division attacks, takes flux/damage, then rotates out.

Expected: fresh division or reserve replaces it.

### Scenario D — reserve counterattack

Enemy breaks through a screen division.

Expected: reserve division commits only after penetration is confirmed.

### Scenario E — objective defense

Enemy probes a defended objective while another division threatens elsewhere.

Expected: objective division anchors instead of chasing.

### Scenario F — large battle performance

Large fleet battle with many ships.

Expected: evaluator stays within cadence and spatial-grid budget; no all-pairs explosion.

## 14. Logging and observability

Every command decision should log:

- timestamp
- side
- division id
- division state
- commander profile
- chosen stance
- rejected alternatives
- order primitive used
- assignment/flag target
- suppression reason if not executed

Debug overlay should eventually show:

- division centroid
- division radius
- cohesion status
- local overmatch ratio
- current stance
- main effort/reserve markers

## 15. Performance budget

- heavy evaluation every 0.5–1.0 seconds
- battlegroup reassessment every 1–2 seconds
- per-frame work limited to timers and cheap bookkeeping
- spatial-grid neighborhood queries where possible
- no persistent all-pairs ship comparisons
- decisions cached until stale

## 16. Main risks

### Registration risk

The mod must reliably install its combat plugin at battle start. This needs a small spike before feature work.

### Flag-semantics risk

Flags do not all mean what their names imply in every module. Each flag used by the mod needs runtime verification.

### Order-conflict risk

Player orders, vanilla admiral assignments, and DivisionAI orders can conflict. The order arbiter must be conservative.

### UI risk

Player-defined divisions are likely harder than the AI evaluator. UI should come after behavior proof.

### Compatibility risk

Modded ships, systems, stations, fighters, and allies may violate assumptions. The evaluator should degrade gracefully.

### Balance risk

A better coordinated AI can feel oppressive. Difficulty knobs and commander personality variance are important.

## 17. First proof target

The best first proof is not time-on-target or complex flanking.

Recommended first proof:

- automatic divisions
- cohesion score
- bounded pursuit
- regroup stance
- division rotation into reserve

That slice should prove the core value: ships stop fighting alone, and divisions act as persistent tactical objects.

## 18. Immediate next steps

1. Initialize git for the project.
2. Add `.gitignore` for `build/` and local trash.
3. Implement the Phase 0 observer spike.
4. Verify combat plugin registration.
5. Log division state in a simulation battle.
6. Only then implement behavior-changing stances.
