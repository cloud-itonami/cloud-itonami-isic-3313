(ns electronicrepair.registry
  "Pure-function repair intake, dispatch, and parts-order record
  construction — an append-only repair-shop book-of-record draft.

  Like every sibling actor's registry, there is no single international
  reference-number standard for a repair intake or parts order — every
  repair shop assigns its own reference format. This namespace does NOT
  invent one; it builds a shop-scoped sequence number and validates the
  record's required fields, the same honest, non-fabricating discipline
  `electronicrepair.facts` uses.

  `parts-cost-matches-claim?` is an HONEST reapplication of the SAME
  ground-truth-recompute DISCIPLINE `quarryops.registry`'s own
  `royalty-matches-claim?`, `agronomyops.registry`'s own
  `dose-matches-claim?`, and `specialtyrepair.registry`'s own
  `parts-cost-matches-claim?` establish (verify a claimed quantity
  against the entity's own recorded fields), reapplied to an
  electronics-repair parts line — not claimed as new code, though no
  literal code is shared (different domain).

  This namespace is pure data + pure functions — no I/O, no network
  call to any real repair-shop system. It builds the RECORD a shop
  operator would keep, not the act of repairing itself (that is
  `electronicrepair.operation`'s hand-on work — always technician-gated)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED — signature is
  the technician's act, not this actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn compute-total-parts-cost
  "Sum of all parts in the repair estimate (BOM).
  Ground-truth validation: individual part costs must sum correctly."
  [parts]
  (reduce + (map :cost (filter :cost parts))))

(defn parts-cost-matches-claim?
  "Does the claimed total parts cost equal the independently recomputed
  sum from the parts list? A pure ground-truth check against the
  estimate's own permanent fields — see ns docstring for why this is an
  honest reapplication of the SAME discipline every sibling actor's
  own cost/total-matching check establishes, not a new concept."
  [estimate]
  (let [parts (:parts estimate)
        claimed (:total-parts-cost estimate)
        computed (compute-total-parts-cost parts)]
    (== (double claimed) (double computed))))

(defn register-intake
  "Validate + construct the REPAIR-INTAKE registration DRAFT — the
  shop's own formal intake of equipment for repair. Pure function — does
  not touch any real repair system; it builds the RECORD the operator
  would keep. `electronicrepair.governor` independently re-verifies
  the client/equipment ground truth and evidence completeness before
  this is ever allowed to commit."
  [client-id equipment-id shop-code sequence]
  (when-not (and client-id (not= client-id ""))
    (throw (ex-info "intake: client_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "intake: equipment_id required" {})))
  (when-not (and shop-code (not= shop-code ""))
    (throw (ex-info "intake: shop_code required" {})))
  (when (< sequence 0)
    (throw (ex-info "intake: sequence must be >= 0" {})))
  (let [intake-number (str (str/upper-case shop-code) "-INT-" (zero-pad sequence 6))
        record {"record_id" intake-number
                "kind" "repair-intake-draft"
                "client_id" client-id
                "equipment_id" equipment-id
                "shop_code" shop-code
                "immutable" true}]
    {"record" record "intake_number" intake-number
     "certificate" (unsigned-certificate "RepairIntake" intake-number intake-number)}))

(defn register-dispatch
  "Validate + construct the TECHNICIAN-DISPATCH registration DRAFT — the
  assignment of a technician to execute a repair. Pure function — does
  not execute the repair itself (that is the technician's hand-on work)."
  [intake-id technician-id shop-code sequence]
  (when-not (and intake-id (not= intake-id ""))
    (throw (ex-info "dispatch: intake_id required" {})))
  (when-not (and technician-id (not= technician-id ""))
    (throw (ex-info "dispatch: technician_id required" {})))
  (when-not (and shop-code (not= shop-code ""))
    (throw (ex-info "dispatch: shop_code required" {})))
  (when (< sequence 0)
    (throw (ex-info "dispatch: sequence must be >= 0" {})))
  (let [dispatch-number (str (str/upper-case shop-code) "-DSP-" (zero-pad sequence 6))
        record {"record_id" dispatch-number
                "kind" "technician-dispatch-draft"
                "intake_id" intake-id
                "technician_id" technician-id
                "shop_code" shop-code
                "immutable" true}]
    {"record" record "dispatch_number" dispatch-number
     "certificate" (unsigned-certificate "TechnicianDispatch" dispatch-number dispatch-number)}))

(defn register-parts-order
  "Validate + construct the PARTS-ORDER registration DRAFT — a purchase
  order for replacement parts. Pure function — does not trigger purchase
  or procurement; it records what the shop proposes to order."
  [intake-id shop-code sequence]
  (when-not (and intake-id (not= intake-id ""))
    (throw (ex-info "parts-order: intake_id required" {})))
  (when-not (and shop-code (not= shop-code ""))
    (throw (ex-info "parts-order: shop_code required" {})))
  (when (< sequence 0)
    (throw (ex-info "parts-order: sequence must be >= 0" {})))
  (let [order-number (str (str/upper-case shop-code) "-PRT-" (zero-pad sequence 6))
        record {"record_id" order-number
                "kind" "parts-order-draft"
                "intake_id" intake-id
                "shop_code" shop-code
                "immutable" true}]
    {"record" record "order_number" order-number
     "certificate" (unsigned-certificate "PartsOrder" order-number order-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
