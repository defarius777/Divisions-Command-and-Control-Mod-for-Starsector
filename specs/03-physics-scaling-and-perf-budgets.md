# Spec 03 — Physics Scaling Matrix, C2 JIT Safety & Performance Budgets

## 1. Physics Scaling Basis (`PHYS-INV-01`)
**1.0x Vanilla Parity Rule**: When `weaponRangeScale == 1.0`, `shipBoundsScale == 1.0`, and `cachedGlobalSpeedOverhaulActive == false`, every scaled helper MUST return exact `1.0x` vanilla constants, and Speed Governing MUST default to **OFF** (`0` stat mods, `0` retro-trim).

| Tactical Quantity | Scaling Basis | Vanilla `1.0x` | Better Combat (`~3.64x` gun, `~3.0x` mis, `600` target spd) |
|---|---|---|---|
| Intra-division `stationSpacing` | `getGlobalStationSpacingMult()` (`bounds` + PD overlap) | `1.00x` (`525u` CA) | `~1.71x` (`~900u` CA, `~1150u` BB) |
| Inter-division `divisionInterval` | `getGlobalDivisionIntervalMult()` | `1.00x` (`1050u` CA) | `~1.95x` (`~2050u` CA) |
| Gun division `standoffRange` | `getWeaponRangeScale(engine)` + P70 main-battery envelope | `1.00x` (`800u` CA) | `~3.64x` (`~2900u` CA) |
| Missile division `standoffRange` | `getGlobalMissileRangeScale()` + `liveMissileCeiling` (`<= max(1.25x def, 0.96x avgEngage)`) | `1.00x` | Bounded (`~4000–5000u`, auto-heals `>1.80x` double-scaled saves) |
| Carrier `VAN` / `CENTER` / `REAR` echelon & strike cap (`PHYS-INV-02`) | Main-Body Frame (`divisionInterval`) + `getFighterRangeScale(engine)` (`<= 0.88x liveStrikeRange`) | `+0.25x depth` / `-0.35x int` / `-0.85x int`, `<= ~3600u` | Scales with `divisionInterval` (`~1.95x`) & live wing range (`~5500–7600u`, heals `10200u` bloat) |
| Hull speed class thresholds (`105`, `130` su/s) | `scaleVanillaSpeedThreshold(engine, v)` | `105` / `130` su/s | `~303` / `~318` su/s |
| Speed Governor (`DivisionEngineTelegraph`) | `isSpeedGoverningEnvironment()` | **OFF** by default (opt-in) | **ON** by default (`AUTO` -> `1/3`, `1/2`, `FULL`, `FLANK`) |

**Bidirectional Save Migration (`PHYS-INV-03`)**: Toggling Better Combat ON or OFF on an existing campaign save MUST migrate both `FleetDoctrineConfig` defaults and non-manual `PermanentDivisionSpec` spacing, interval, and `customStandoffRange` values in both directions (healing `2,750–4,950u` Better Combat cruiser/destroyer/frigate auto-standoffs down to `1.0x` `liveGun` when returning to Vanilla, not only values `> 5,000u`), without overriding `1.0x` Vanilla `FleetDoctrineConfig` sliders or mixed-battery longest-gun standoffs in `adaptDoctrinalStandoffToLiveEnvelope`.

## 2. Shenandoah-IU C2 JIT Safety (`PERF-INV-01`)
- Hot 60 Hz classes (`CombatPhysicsProfile`, `ShipCaptainAI`, `DivisionDeploymentPlanner`) MUST contain **zero** `Math.sin`, `Math.cos`, `Math.atan2`, or `Math.pow` bytecode instructions (`scanJarClassesForC2UnsafeMath`). Always use `fastSinDeg`, `fastCosDeg`, `fastAtan2Deg`, and `fastUnitPow`.

## 3. Combat Frame-Time & Allocation Budgets (`PERF-INV-02`)
1. **10 Hz `updateTsfcKeyframeTick` Idle Gate**: Intermediate `0.10s` TSFC keyframe ticks exist strictly for Warroom TSFC overlay interpolation (`Time`, `Space`, `Firepower`, `Convergence`). When the Warroom (`TAB`) is closed OR all four TSFC overlay toggles are `false`, intermediate 10 Hz ticks MUST NOT run (`0.00 ms/s` idle cost).
2. **2 Hz `runPeriodicC2EvaluationTick` Multi-Frame Stagger**: In live combat (`isRealCombatEngine`), the 2 Hz C2 cycle staggers across **4 consecutive frames** (Stage 0: Sync + Roses + Player/Allied Telemetry; Stage 1: Enemy Telemetry + Opposing Views; Stage 2: Cross-Group TSFC + OTC; Stage 3: Division Commanders + Voidstrikes) targeting `maxC2StageFrame <= 6 ms` on 28-division / 160-ship battles.
3. **60 Hz Zero-Copy Weapon & Carrier Caches**:
   - `CombatPhysicsProfile.applyAutofireBoundsAndBeamAssist` MUST cache `WeaponGroupAPI[]` and `WeaponAPI[]` arrays per `ShipAPI` rather than calling `getWeaponGroupsCopy()` / `getWeaponsCopy()` every frame.
   - `ShipCaptainAI.advance` MUST evaluate `applyCombatBearingDiscipline` once per frame (pre-advance), not twice.
   - `VoidstrikeCoordinator` 60 Hz strike-craft gunnery MUST cache `CarrierBayComposition`, CAP reserve keys, and 5 Hz (`0.20s`) carrier contact/FoW target scans per carrier, and skip per-fighter $O(\text{ships})$ flak-avoidance scans on non-leader wingmen following an alive wing leader.
