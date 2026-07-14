(ns electronicrepair.governor
  "Electronic Repair Governor — the independent compliance layer that
  earns the ElectronicRepairOps-LLM the right to propose actions. The
  LLM has no notion of client record verification, equipment safety
  compliance, parts-cost validation, whether equipment actually requires
  technician dispatch scheduling, or when an intake becomes a real repair
  commitment, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD.

  `:itonami.blueprint/governor` is `:electronic-repair-governor`, grep-
  verified UNIQUE fleet-wide — no naming-collision precedent question,
  a fresh independent build following the SAME governed-actor architecture
  (langgraph StateGraph + independent Governor + Phase 0->3 rollout)
  established by `cloud-itonami-isic-6511`.

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them. The confidence/actuation gate is SOFT: it asks a
  human to look (low confidence), and the human may approve -- but see
  `electronicrepair.phase`: for `:stake :actuation/complete-repair`
  (a real technician sign-off) NO phase ever allows auto-commit either.
  Two independent layers agree that repair completion is always a human
  (technician) call.

    1. Client verification         -- is the client record actually on file
                                       in the shop system, with current
                                       contact / address information?
    2. Equipment registration      -- is the equipment record on file with
                                       model/serial/repair-history?
    3. Safety checklist (if
       required)                   -- for hazardous equipment types
                                       (high-voltage, radioactive, CRT,
                                       laser), has the mandatory safety
                                       checklist been completed and
                                       signed?
    4. Estimate validity           -- does the proposed repair have a
                                       documented estimate with labor
                                       hours and parts cost?
    5. Parts cost matching         -- for `:order-parts`, does the
                                       claimed total match the
                                       independently recomputed sum
                                       from the parts list? An HONEST
                                       reapplication of the SAME
                                       discipline `agronomyops.registry`'s
                                       / `specialtyrepair.registry`'s
                                       own cost-matching checks establish.
    6. No duplicate dispatch       -- for `:schedule-technician-
                                       dispatch`, has this intake already
                                       been dispatched? Refuse a second
                                       dispatch to prevent double
                                       scheduling and technician resource
                                       conflicts.

  Two more guards, no-double-completion and safety-escalation, are
  enforced but NOT listed as numbered HARD checks because they need
  special handling: `already-completed-violations` refuses to mark an
  intake as completed twice (off a dedicated `:completed?` fact); and
  `:flag-safety-concern` ALWAYS escalates to human review, never
  auto-approves (it is intrinsically an escalation action, not a
  compliance check)."
  (:require [electronicrepair.facts :as facts]
            [electronicrepair.registry :as registry]
            [electronicrepair.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  The only real-world actuation this actor performs is escalation
  of safety concerns (always human review) — direct repair work
  remains the technician's exclusive authority."
  #{:actuation/complete-repair :actuation/escalation})

;; ----------------------------- checks -----------------------------

(defn- client-verification-violations
  "An intake proposal with no verified client on file is a HARD
  violation — never invent a customer record."
  [{:keys [op subject]} proposal st]
  (when (contains? #{:intake-repair-order :schedule-technician-dispatch :order-parts} op)
    (let [client-id (get-in proposal [:value :client-id])
          client (store/client st client-id)]
      (when-not (facts/client-verified? client)
        [{:rule :client-not-verified
          :detail (str "クライアント記録 " client-id " が検証されていない")}]))))

(defn- equipment-registration-violations
  "An intake proposal with no registered equipment record is a HARD
  violation — never proceed with unknown equipment."
  [{:keys [op subject]} proposal st]
  (when (contains? #{:intake-repair-order :schedule-technician-dispatch :order-parts} op)
    (let [equipment-id (get-in proposal [:value :equipment-id])
          equipment (store/equipment st equipment-id)]
      (when-not (facts/equipment-registered? equipment)
        [{:rule :equipment-not-registered
          :detail (str "機器記録 " equipment-id " が登録されていない")}]))))

(defn- safety-checklist-violations
  "For hazardous equipment types, the safety checklist MUST be
  completed before intake proceeds."
  [{:keys [op subject]} proposal st]
  (when (contains? #{:intake-repair-order} op)
    (let [equipment-id (get-in proposal [:value :equipment-id])
          equipment (store/equipment st equipment-id)
          equipment-type (:equipment-type equipment)
          checklist (store/checklist-for-equipment st equipment-id)]
      (when (and (facts/safety-checklist-required? equipment-type)
                 (not (facts/safety-checklist-complete? checklist)))
        [{:rule :safety-checklist-incomplete
          :detail (str equipment-id " は危険な機器タイプだが安全チェックリストが未完了")}]))))

(defn- estimate-validity-violations
  "For dispatch, an intake must have a complete repair estimate. For
  intake-repair-order itself, the estimate is created separately, so
  we don't check it here."
  [{:keys [op subject]} proposal st]
  (when (= op :schedule-technician-dispatch)
    (let [intake-id (get-in proposal [:value :intake-id])
          estimate (store/estimate-for-intake st intake-id)]
      (when-not (facts/estimate-provided? estimate)
        [{:rule :estimate-missing
          :detail (str "修理見積が作成されていない： " intake-id)}]))))

(defn- parts-cost-matching-violations
  "For `:order-parts`, the claimed total must match the independently
  recomputed sum from the parts list."
  [{:keys [op subject]} proposal st]
  (when (= op :order-parts)
    (let [intake-id (get-in proposal [:value :intake-id])
          estimate (store/estimate-for-intake st intake-id)]
      (when-not (registry/parts-cost-matches-claim? estimate)
        [{:rule :parts-cost-mismatch
          :detail (str intake-id " の部品費用合計が再計算値と一致しない")}]))))

(defn- duplicate-dispatch-violations
  "For `:schedule-technician-dispatch`, refuse if already dispatched
  to prevent double-scheduling resource conflicts."
  [{:keys [op subject]} proposal st]
  (when (= op :schedule-technician-dispatch)
    (let [intake-id (get-in proposal [:value :intake-id])]
      (when (store/intake-already-dispatched? st intake-id)
        [{:rule :already-dispatched
          :detail (str intake-id " は既に技術者に割り当てられている")}]))))

(defn- already-completed-violations
  "For `:complete-repair`, refuse if already completed (technician
  sign-off, once done, cannot be re-executed)."
  [{:keys [op subject]} proposal st]
  (when (= op :complete-repair)
    (let [intake-id (get-in proposal [:value :intake-id])]
      (when (store/repair-already-completed? st intake-id)
        [{:rule :already-completed
          :detail (str intake-id " は既に修理完了として記録されている")}]))))

(defn check
  "Censors an ElectronicRepairOps-LLM proposal against the governor
  rules. Returns {:ok? bool :violations [..] :confidence c :escalate?
  bool :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (client-verification-violations request proposal st)
                           (equipment-registration-violations request proposal st)
                           (safety-checklist-violations request proposal st)
                           (estimate-validity-violations request proposal st)
                           (parts-cost-matching-violations request proposal st)
                           (duplicate-dispatch-violations request proposal st)
                           (already-completed-violations request proposal st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
