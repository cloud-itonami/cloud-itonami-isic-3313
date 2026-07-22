(ns electronicrepair.advisor
  "ElectronicRepairOps-LLM Advisor -- the contained LLM/decision node.
  This actor's intelligence layer proposes repair-shop coordination
  actions (intake, technician dispatch, parts ordering, repair-
  completion logging, safety-concern escalation). The advisor is SEALED
  into the `:advise` step of `electronicrepair.operation/build`'s
  compiled StateGraph; every proposal is routed through the independent
  `electronicrepair.governor` before anything commits.

  PRIOR BUG (fixed here): this namespace DID NOT EXIST AT ALL -- there
  was no `defprotocol`, no `defrecord`, no mock implementation anywhere
  in this repo's `src/`. The four proposal-shape functions this fix
  moves here (`intake-repair-order` / `schedule-technician-dispatch` /
  `order-parts` / `flag-safety-concern`) previously lived in
  `electronicrepair.operation`, which never `require`d a
  `langgraph.graph`, never called it, and was never itself called from
  anything -- `operation.cljc`'s own docstring described a \"PROPOSAL
  API\" that no real graph or governor round-trip ever exercised.

  The op vocabulary below is derived DIRECTLY from what
  `electronicrepair.governor/check` actually validates (read there
  first, not invented independently here):
    - `:intake-repair-order`, `:schedule-technician-dispatch`,
      `:order-parts` -- client-verification / equipment-registration
      checks (`client-verification-violations` /
      `equipment-registration-violations`) apply to all three.
    - `:intake-repair-order` alone additionally gets
      `safety-checklist-violations` (hazardous equipment types).
    - `:schedule-technician-dispatch` alone gets
      `estimate-validity-violations` + `duplicate-dispatch-violations`.
    - `:order-parts` alone gets `parts-cost-matching-violations`.
    - `:complete-repair` gets `already-completed-violations` and carries
      `:stake :actuation/complete-repair` -- one of governor's own
      `high-stakes` values, so it ALWAYS escalates even when clean (a
      real technician sign-off is being LOGGED here, not performed --
      the hands-on repair itself remains exclusively the technician's;
      this actor only proposes recording that it happened).
    - `:flag-safety-concern` carries `:stake :actuation/escalation`,
      governor's other `high-stakes` value -- ALWAYS escalates, never
      auto-approved by confidence alone.

  Proposal shape is UNCHANGED from the pre-fix `operation.cljc`
  functions this moves: `{:op :stake :effect :confidence :value :cites}`,
  exactly what `electronicrepair.governor/check` already reads
  (`(:confidence proposal)`, `(:stake proposal)`,
  `(get-in proposal [:value ...])`) -- reused unmodified.

  ONE GENUINE FIX made while wiring this up (surfaced only once these
  builders were actually run through `governor/check` for the first
  time -- they never had been before, since nothing called them from a
  real flow): `client-verification-violations` /
  `equipment-registration-violations` apply to ALL THREE of
  `:intake-repair-order`, `:schedule-technician-dispatch`, and
  `:order-parts` -- not merely to intake creation. The pre-fix
  `schedule-technician-dispatch` / `order-parts` builders never put
  `:client-id`/`:equipment-id` in their `:value`, so every dispatch/
  parts-order proposal would have hard-held UNCONDITIONALLY on
  `:client-not-verified` + `:equipment-not-registered`, regardless of
  the underlying intake's own legitimacy (confirmed empirically before
  this fix: `(governor/check ... )` on the original proposal shape
  always returned `:hard? true` for these two ops). Fixed by adding
  `:client-id`/`:equipment-id` as required `request` fields on both
  builders below, carried through into `:value`.")

;; ----------------------------- proposal builders -----------------------------

(defn intake-repair-order
  "Propose a new repair intake. Required fields in `request`:
  - `:client-id` -- shop client record ID
  - `:equipment-id` -- equipment record ID
  - `:shop-code` -- shop location/branch code
  - `:intake-sequence` -- shop-assigned sequence number for this intake
  - `:confidence` -- LLM confidence in intake assessment (0.0-1.0)

  Returns a proposal structure the governor will censor."
  [{:keys [client-id equipment-id shop-code intake-sequence confidence]}]
  (when-not client-id
    (throw (ex-info "intake-repair-order: client-id required" {})))
  (when-not equipment-id
    (throw (ex-info "intake-repair-order: equipment-id required" {})))
  (when-not shop-code
    (throw (ex-info "intake-repair-order: shop-code required" {})))
  (when-not (integer? intake-sequence)
    (throw (ex-info "intake-repair-order: intake-sequence must be integer" {})))
  {:op :intake-repair-order
   :stake :intake-coordination
   :effect :propose
   :confidence (or confidence 0.0)
   :value {:client-id client-id
           :equipment-id equipment-id
           :shop-code shop-code
           :intake-sequence intake-sequence}
   :cites []})

(defn schedule-technician-dispatch
  "Propose dispatch of a technician to an intake. Required fields in
  `request`:
  - `:intake-id` -- the intake record ID to dispatch
  - `:client-id` -- the intake's client record ID
  - `:equipment-id` -- the intake's equipment record ID
  - `:technician-id` -- shop technician/service record ID
  - `:shop-code` -- shop location/branch code
  - `:dispatch-sequence` -- shop-assigned sequence number
  - `:confidence` -- LLM confidence in dispatch scheduling (0.0-1.0)

  `:client-id`/`:equipment-id` are carried in `:value` (not just
  `:intake-id`) because `electronicrepair.governor/check`'s
  `client-verification-violations` / `equipment-registration-violations`
  independently re-verify BOTH for every op in
  `#{:intake-repair-order :schedule-technician-dispatch :order-parts}` --
  not merely intake creation. A dispatch proposal that omitted them would
  read as an unverified client/unregistered equipment and hard-hold
  unconditionally, regardless of the intake itself being legitimate.

  Returns a proposal structure the governor will censor."
  [{:keys [intake-id client-id equipment-id technician-id shop-code
           dispatch-sequence confidence]}]
  (when-not intake-id
    (throw (ex-info "schedule-technician-dispatch: intake-id required" {})))
  (when-not client-id
    (throw (ex-info "schedule-technician-dispatch: client-id required" {})))
  (when-not equipment-id
    (throw (ex-info "schedule-technician-dispatch: equipment-id required" {})))
  (when-not technician-id
    (throw (ex-info "schedule-technician-dispatch: technician-id required" {})))
  (when-not shop-code
    (throw (ex-info "schedule-technician-dispatch: shop-code required" {})))
  (when-not (integer? dispatch-sequence)
    (throw (ex-info "schedule-technician-dispatch: dispatch-sequence must be integer" {})))
  {:op :schedule-technician-dispatch
   :stake :dispatch-coordination
   :effect :propose
   :confidence (or confidence 0.0)
   :value {:intake-id intake-id
           :client-id client-id
           :equipment-id equipment-id
           :technician-id technician-id
           :shop-code shop-code
           :dispatch-sequence dispatch-sequence}
   :cites []})

(defn order-parts
  "Propose a parts order for an intake. Required fields in `request`:
  - `:intake-id` -- the intake record ID
  - `:client-id` -- the intake's client record ID
  - `:equipment-id` -- the intake's equipment record ID
  - `:shop-code` -- shop location/branch code
  - `:order-sequence` -- shop-assigned sequence number
  - `:confidence` -- LLM confidence in parts list (0.0-1.0)

  `:client-id`/`:equipment-id` are carried for the SAME reason as
  `schedule-technician-dispatch` above -- governor's client/equipment
  verification checks apply to `:order-parts` too, not only intake
  creation.

  Returns a proposal structure the governor will censor."
  [{:keys [intake-id client-id equipment-id shop-code order-sequence confidence]}]
  (when-not intake-id
    (throw (ex-info "order-parts: intake-id required" {})))
  (when-not client-id
    (throw (ex-info "order-parts: client-id required" {})))
  (when-not equipment-id
    (throw (ex-info "order-parts: equipment-id required" {})))
  (when-not shop-code
    (throw (ex-info "order-parts: shop-code required" {})))
  (when-not (integer? order-sequence)
    (throw (ex-info "order-parts: order-sequence must be integer" {})))
  {:op :order-parts
   :stake :parts-procurement
   :effect :propose
   :confidence (or confidence 0.0)
   :value {:intake-id intake-id
           :client-id client-id
           :equipment-id equipment-id
           :shop-code shop-code
           :order-sequence order-sequence}
   :cites []})

(defn complete-repair
  "Propose LOGGING a repair as complete -- the hands-on repair work
  itself is ALWAYS the technician's own act, done outside this actor;
  this proposal only records that a technician signed off on it.
  Required fields in `request`:
  - `:intake-id` -- the intake record ID being completed
  - `:technician-id` -- the technician who performed and signed off
  - `:confidence` -- LLM confidence the sign-off record is accurate

  `:stake :actuation/complete-repair` is one of
  `electronicrepair.governor/high-stakes` -- this ALWAYS escalates to a
  human, even when governor-clean; see the governor namespace's own
  docstring: \"Two independent layers agree that repair completion is
  always a human (technician) call.\""
  [{:keys [intake-id technician-id notes confidence]}]
  (when-not intake-id
    (throw (ex-info "complete-repair: intake-id required" {})))
  (when-not technician-id
    (throw (ex-info "complete-repair: technician-id required" {})))
  {:op :complete-repair
   :stake :actuation/complete-repair
   :effect :propose
   :confidence (or confidence 0.0)
   :value {:intake-id intake-id
           :technician-id technician-id
           :notes (or notes "")}
   :cites []})

(defn flag-safety-concern
  "Escalate a safety concern. This ALWAYS escalates to human review --
  it is never auto-approved. Required fields in `request`:
  - `:intake-id` -- the intake record ID with the concern
  - `:concern-type` -- keyword describing the concern (e.g., :hazmat,
    :electrical, :high-voltage-risk)
  - `:description` -- human-readable description
  - `:confidence` -- LLM confidence in concern validity (0.0-1.0)

  Returns a proposal structure that will always escalate (never auto-commit)."
  [{:keys [intake-id concern-type description confidence]}]
  (when-not intake-id
    (throw (ex-info "flag-safety-concern: intake-id required" {})))
  (when-not concern-type
    (throw (ex-info "flag-safety-concern: concern-type required" {})))
  (when-not description
    (throw (ex-info "flag-safety-concern: description required" {})))
  {:op :flag-safety-concern
   :stake :actuation/escalation
   :effect :propose
   :confidence (or confidence 0.0)
   :value {:intake-id intake-id
           :concern-type concern-type
           :description description}
   :cites []})

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request]
    "Given store and request ({:op ... plus the op's own required
    fields, see the proposal builders above}), return a proposal map
    with :op, :stake, :effect, :confidence, :value, :cites --
    exactly the shape `electronicrepair.governor/check` reads."))

(defrecord MockAdvisor []
  Advisor
  (-advise [_advisor _store request]
    (case (:op request)
      :intake-repair-order          (intake-repair-order request)
      :schedule-technician-dispatch (schedule-technician-dispatch request)
      :order-parts                  (order-parts request)
      :complete-repair               (complete-repair request)
      :flag-safety-concern           (flag-safety-concern request)
      ;; fallback -- unrecognized op. Governor's checks censor this
      ;; regardless of what the advisor says; no known op means no
      ;; evidence, zero confidence, no stake.
      {:op (:op request)
       :stake :unknown
       :effect :propose
       :confidence 0.0
       :value {}
       :cites []})))

(defn mock-advisor []
  (MockAdvisor.))

(defn trace
  "Audit trail entry for an advisor proposal. Recorded whenever a
  proposal is generated, regardless of whether it's approved."
  [request proposal]
  {:t :advisor-proposal
   :op (:op request)
   :subject (:subject request)
   :stake (:stake proposal)
   :confidence (:confidence proposal)})
