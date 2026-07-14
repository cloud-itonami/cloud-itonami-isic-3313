(ns electronicrepair.phase
  "Phase-gating for electronic repair operations: which actions the
  LLM may auto-commit at each rollout phase.

  Phase 0 (Proposal Research): LLM proposes nothing yet; governance
  chain reads proposals from humans.

  Phase 1 (Propose Intake/Dispatch/Parts): LLM proposes intake,
  dispatch, and parts orders (all `:effect :propose` — no commitment).
  Governor enforces: client verified, equipment registered, safety
  checks complete, estimate valid, no duplicates. If all pass and
  confidence >= threshold and not high-stakes, `:ok? true`. Else
  escalate to human review.

  Phase 2 (Auto-Commit Logistics): LLM auto-commits
  `:intake-repair-order`, `:schedule-technician-dispatch`, and
  `:order-parts` proposals. **Never auto-commits repair completion
  (`:complete-repair`) — that ALWAYS requires technician sign-off**.

  Phase 3 (Full Authority): Same as Phase 2 (repair completion never
  auto-commits).

  Invariant: `:complete-repair` (tech sign-off) and
  `:flag-safety-concern` (escalation) ALWAYS escalate to human review,
  never auto-commit, across all phases."
  (:require [electronicrepair.governor :as governor]))

(defn phase-allows-commit?
  "Given the operation type and current phase, is auto-commit permitted?
  Returns true only if the operation is NOT repair completion or safety
  escalation, and the phase is >= 2."
  [phase op-type]
  (let [never-auto-commit? (contains?
                             #{:complete-repair :flag-safety-concern}
                             op-type)]
    (and (>= phase 2)
         (not never-auto-commit?))))

(defn phase-for-op
  "Minimum phase required to perform a given operation type:
  - `:intake-repair-order` -> phase 1
  - `:schedule-technician-dispatch` -> phase 1
  - `:order-parts` -> phase 1
  - `:complete-repair` -> never auto-commits (always human)
  - `:flag-safety-concern` -> never auto-commits (always escalation)"
  [op-type]
  (case op-type
    (:intake-repair-order
     :schedule-technician-dispatch
     :order-parts) 1
    (:complete-repair :flag-safety-concern) :human-only))

(defn governance-action
  "Given governor verdict, phase, and operation, return the governance
  action: `:commit`, `:escalate`, or `:hold`.

  - `:hold` if any hard violations exist
  - `:escalate` if (a) high-stakes/repair-completion, (b) low
    confidence, or (c) operation type never auto-commits
  - `:commit` if phase >= 2 and all checks pass and not high-stakes"
  [phase op-type verdict]
  (cond
    (:hard? verdict) :hold
    (:escalate? verdict) :escalate
    (phase-allows-commit? phase op-type) :commit
    :else :escalate))
