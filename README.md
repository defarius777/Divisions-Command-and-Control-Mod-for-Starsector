# Starsector Division AI Mod

Force-cohesion layer for the tactical ship AI. Vanilla ship awareness is
hard-capped at a 1500-2200u bubble with zero cross-ship state sharing (see
`.\Starsector OST Rip\combat_ai_analysis.md`); this mod adds
the missing "who is winning where" evaluation and feeds it to ships via the
same ShipwideAIFlags blackboard the vanilla modules use.

## Layout

    mod/                  <- the actual mod (junctioned into the game)
      mod_info.json       -> id "division_ai", gameVersion 0.98a-RC8
      jars/DivisionAI.jar -> built by build.sh
      data/config/        -> mod settings will live here (divisionai_settings.json)
    src/divisionai/       <- Java source, package divisionai
    lib/                  <- pinned compile-time deps (do not edit):
      starfarer.api.jar     game's public API, from starsector-core/
      starfarer.api.zip     API javadoc (unzip to browse)
      log4j-1.2.9.jar       API's Logger type lives here
      LazyLib.jar + javadoc (pinned; runtime dep via mod loading)
    toolchain/jdk17/      <- Temurin JDK 17.0.20.1 (javac/jar for building)
    build/                <- compiler output (gitignored trash)
    build.sh              <- compiles src/ -> mod/jars/DivisionAI.jar

## Build

    bash build.sh

Classpath is only the three pinned jars; the game's Zulu 17 runtime loads
everything else. `mod/` is junctioned at ${STARSECTOR_DIR:-../Starsector}\mods\DivisionAI
so builds land in the game directly - no copy step.

## References

- Design plan: docs/division_command_design.md
- Combat AI analysis (decompiled vanilla AI): .\Starsector OST Rip\
  - combat_ai_analysis.md (main doc), architecture_notes.md (engine overview)
  - combat_ai_sources/ (CFR output, 120 files - BasicShipAI, D.java threat eval,
    AttackAIModule, StrafeTargetManeuverV2, N.java behavior module)
- API docs: unzip lib/starfarer.api.zip, or Community API Docs:
  https://starsectorcommunityapidocs.github.io/CommunityApiDocs/
- LazyLib javadoc: unzip lib/lazylib-javadoc.zip
- API source, jar contents, and modding javadoc ship inside the game:
  ${STARSECTOR_DIR:-../Starsector}\starsector-core\starfarer.api.jar / .zip
- Vanilla AI-relevant configs: ${STARSECTOR_DIR:-../Starsector}\starsector-core\data\config\
- Reference mods (installed, enabled): Console Commands (dev/testing),
  DetailedCombatResults, LunaLib (settings + in-game dev tools)
- Key vanilla files in decompiled sources (obfuscated names, mapped):
  BasicShipAI (container), D.java (threat eval, 1500u bubble),
  OO0O.java (AI toolkit: target finding, personality), N.java (behavior:
  fleet-center pull, omega/monster exclusions), AttackAIModule (flux-war
  gunnery), movement/StrafeTargetManeuverV2 (75KB workhorse), new.java (vent)

## Injection point (design decision)

Deep Battle Formations proved processInputPreCoreControls is a reliable
last-word hook; the ShipwideAIFlags blackboard (MANEUVER_TARGET, DO_NOT_VENT,
...) is how vanilla modules coordinate, so DivisionAI writes flags rather
than overriding AI classes - cooperation, not replacement.

## Testing loop

1. bash build.sh
2. Launch game, enable "Division AI" in the mod list, start a simulation
   battle (or run via LunaLib's dev tools / Console Commands).
3. Check starsector.log (${STARSECTOR_DIR:-../Starsector}\starsector.log) for
   "DivisionAI" lines.
4. Junction means no re-copy; game restart per iteration (Java classloader
   caches jars).

## Status

v0.2.0 implemented & compiled (`mod/jars/DivisionAI.jar`, synced to `${STARSECTOR_DIR:-../Starsector}\mods\DivisionAI` and enabled in `enabled_mods.json`).
Includes:
- `ShipCapabilityProfile`: inspects hull class, weapon mounts, strike/torpedo loadouts, PD weight, speed, and fighter bays.
- `DivisionAutoFormer`: auto-forms composite (`Carrier & Escort N`, `Battle Div N`) and monotype (`Cruiser Div N`, `Strike Cruiser Div N`, `Destroyer Div N`, `Frigate Div N`, `Phase Div N`) divisions by hull class, weapons, capabilities, and mission context.
- `BattlegroupCommanderAI`: 1.5s operational phase loop (`OPENING_PROBE`, `LINE_CONTACT`, `COORDINATED_STRIKE`, `STABILIZE_AND_RELIEVE`, `EXPLOIT_BREAKTHROUGH`, `ORDERED_WITHDRAWAL`), spatial-grid local overmatch evaluation, Main Effort & Reserve designation.
- `DivisionCommanderAI`: 0.5s tactical evaluator & `ShipwideAIFlags` execution (harassing torpedo attacks, time-on-target strikes, strike-then-withdraw to reserve, monotype cross-division screening, relief of endangered divisions, flux rotation, bounded pursuit).
- `DivisionCombatLog`: live HUD combat log announcements (`CombatUIAPI.addMessage`) + `starsector.log` output.
