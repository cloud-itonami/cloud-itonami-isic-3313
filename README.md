# cloud-itonami-3313

Open Business Blueprint for **ISIC Rev.5 3313**: repair of electronic
and optical equipment (diagnostic, calibration and certified repair
of electronic instruments, test/measurement equipment, and optical
devices).

This repository designs a forkable OSS business for community
electronic/optical repair: repair-authority and calibration-
certification-scope management, robotics-assisted diagnostics,
component-level rework and post-repair calibration, and repair/
certification records — run by a qualified operator so a repair shop
keeps its own calibration and repair history instead of renting a
closed repair-operations platform.

## Scope note: electronic/optical repair, not machinery repair or manufacturing

`cloud-itonami-isic-3312` ("Community Industrial Machinery Repair
Operations") is scoped to mechanical industrial equipment (pumps,
compressors, turbines). This repository is deliberately scoped to the
SEPARATE class of electronic and optical equipment: test/measurement
instruments, optical devices, and other electronic apparatus. Also
distinct from `cloud-itonami-isic-2660` (medical device
manufacturing) and other manufacturing verticals in this fleet, which
build NEW equipment -- this repository repairs and recertifies
EXISTING equipment. Electronic/optical repair carries its own
distinct compliance concerns: calibration services frequently require
ISO/IEC 17025 laboratory accreditation; component-level rework
follows IPC/WHMA-A-620 and IPC-7711/7721 industry rework/repair
standards; right-to-repair legislation (various US state acts, the
EU's Right to Repair Directive 2024/1799) increasingly shapes access
to parts, diagnostics and service documentation for this class of
equipment specifically.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (diagnostic test-rig
operation, component-level rework assist, post-repair calibration
verification) operate under an actor that proposes actions and an
independent **Electronic Repair Governor** that gates them. The
governor never releases a repaired unit back into service itself;
`:high`/`:safety-critical` actions (a repair step outside verified
repair-authority scope, a return-to-service release without a
completed calibration/inspection pass, a certification record without
verified evidence) require human sign-off.

## Core Contract

```text
intake + identity + repair-authority/calibration scope + work order
        |
        v
Electronic Repair Advisor -> Electronic Repair Governor -> repair record, calibration record, release, or human approval
        |
        v
robot actions (gated) + repair record + certification record + audit ledger
```

No automated advice can release a unit back into service the governor
refuses, advance a repair step outside its verified repair-authority
scope, or publish a certification record without governor approval
and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `3313`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/eda`](https://github.com/kotoba-lang/eda) — repair-authority artifact management
- [`kotoba-lang/cae`](https://github.com/kotoba-lang/cae) — calibration/simulation evidence

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
