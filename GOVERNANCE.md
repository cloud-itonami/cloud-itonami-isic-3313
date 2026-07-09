# Governance

`cloud-itonami-3313` is an OSS open-business blueprint for community
electronic and optical equipment repair operations, robotics-premised.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a robot action the governor refuses is never dispatched to hardware.
- the Electronic Repair Governor remains independent of the advisor.
- hard policy violations (a return-to-service release without a
  completed calibration/post-repair inspection, a repair step outside
  verified repair-authority scope) cannot be overridden by human
  approval.
- every repair step, sign-off and release path is auditable.
- sensitive repair and equipment data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, robot-safety, audit and data-flow review.

Certified operators can lose certification for:
- bypassing robot-safety or repair-authority-scope checks
- mishandling repair or equipment data
- misrepresenting certification status
- failing to respond to safety incidents
