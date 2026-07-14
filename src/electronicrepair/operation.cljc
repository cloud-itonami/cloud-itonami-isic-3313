(ns electronicrepair.operation
  "Electronic repair operations orchestration — LLM-proposed actions
  for intake, dispatch, and parts-ordering coordination.

  This namespace describes the PROPOSAL API: what the LLM may propose,
  what fields are required, what the proposal structure looks like. The
  LLM does NOT execute these actions directly; it proposes them, the
  governor censors them, and if the verdict is `:ok?` or `:escalate?
  (approved by human at escalation), they commit to the audit ledger as
  facts.

  Real repair work (hands-on calibration, component-level fixes,
  reassembly, testing) is the technician's exclusive authority — this
  actor coordinates logistics around it, never substitutes for it.")

(defn intake-repair-order
  "Propose a new repair intake. Required fields:
  - `:client-id` — shop client record ID
  - `:equipment-id` — equipment record ID
  - `:shop-code` — shop location/branch code
  - `:intake-sequence` — shop-assigned sequence number for this intake
  - `:confidence` — LLM confidence in intake assessment (0.0-1.0)

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
  "Propose dispatch of a technician to an intake. Required fields:
  - `:intake-id` — the intake record ID to dispatch
  - `:technician-id` — shop technician/service record ID
  - `:shop-code` — shop location/branch code
  - `:dispatch-sequence` — shop-assigned sequence number
  - `:confidence` — LLM confidence in dispatch scheduling (0.0-1.0)

  Returns a proposal structure the governor will censor."
  [{:keys [intake-id technician-id shop-code dispatch-sequence confidence]}]
  (when-not intake-id
    (throw (ex-info "schedule-technician-dispatch: intake-id required" {})))
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
           :technician-id technician-id
           :shop-code shop-code
           :dispatch-sequence dispatch-sequence}
   :cites []})

(defn order-parts
  "Propose a parts order for an intake. Required fields:
  - `:intake-id` — the intake record ID
  - `:shop-code` — shop location/branch code
  - `:order-sequence` — shop-assigned sequence number
  - `:confidence` — LLM confidence in parts list (0.0-1.0)

  Returns a proposal structure the governor will censor."
  [{:keys [intake-id shop-code order-sequence confidence]}]
  (when-not intake-id
    (throw (ex-info "order-parts: intake-id required" {})))
  (when-not shop-code
    (throw (ex-info "order-parts: shop-code required" {})))
  (when-not (integer? order-sequence)
    (throw (ex-info "order-parts: order-sequence must be integer" {})))
  {:op :order-parts
   :stake :parts-procurement
   :effect :propose
   :confidence (or confidence 0.0)
   :value {:intake-id intake-id
           :shop-code shop-code
           :order-sequence order-sequence}
   :cites []})

(defn flag-safety-concern
  "Escalate a safety concern. This ALWAYS escalates to human review —
  it is never auto-approved. Required fields:
  - `:intake-id` — the intake record ID with the concern
  - `:concern-type` — keyword describing the concern (e.g., :hazmat,
    :electrical, :high-voltage-risk)
  - `:description` — human-readable description
  - `:confidence` — LLM confidence in concern validity (0.0-1.0)

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
   :effect :escalate
   :confidence (or confidence 0.0)
   :value {:intake-id intake-id
           :concern-type concern-type
           :description description}
   :cites []})
