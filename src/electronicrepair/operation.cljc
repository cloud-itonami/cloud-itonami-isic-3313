(ns electronicrepair.operation
  "OperationActor for the community electronic and optical equipment
  repair operations coordinator.

  `build` compiles a REAL langgraph-clj StateGraph
  (`langgraph.graph/state-graph` + `compile-graph`) that seals
  `electronicrepair.advisor`'s `Advisor` into a single node (`:advise`),
  ALWAYS routes its proposal through the independent Electronic Repair
  Governor (`:govern`) before anything commits, and gives high-stakes
  ops (`:complete-repair` real technician sign-off logging,
  `:flag-safety-concern` escalation) a real human-in-the-loop approval
  gate (`:request-approval`, `interrupt-before` + checkpoint-based
  resume). Mirrors `pastaops.operation` (cloud-itonami-isic-1074) /
  `transportops.operation` (cloud-itonami-isic-869) node/edge structure,
  wired to this repo's own advisor/governor/store.

  PRIOR BUGS (fixed here):
    1. `electronicrepair.operation` used to be four proposal-shape
       builder functions (`intake-repair-order` /
       `schedule-technician-dispatch` / `order-parts` /
       `flag-safety-concern`) plus prose describing a \"PROPOSAL API\" --
       it never `require`d `langgraph.graph`, never called
       `state-graph`/`add-node`/`compile-graph`, and was never itself
       required by anything else in `src/`. There was no
       `advisor.cljc`/`defprotocol`/mock ANYWHERE in this repo (only
       prose mentions of \"advise\" elsewhere). Fixed: the four builders
       moved to the new `electronicrepair.advisor` (now a real
       `Advisor` protocol + `MockAdvisor`, deriving their op vocabulary
       directly from what `electronicrepair.governor/check` validates,
       plus a fifth op -- `:complete-repair` -- that governor already
       independently checked for via `already-completed-violations` /
       its `:actuation/complete-repair` high-stakes entry, but no
       proposal builder for it existed anywhere), and THIS namespace is
       now the compiled StateGraph that genuinely calls it.
    2. `electronicrepair.store` had no mutation/ledger capability at
       all -- there was no commit/hold path anywhere to call one from.
       Fixed: `store/append-ledger!` (new `Store` protocol, `MemStore`)
       is genuinely called from this namespace's compiled graph's
       `:commit`/`:hold` node handlers below.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (`electronicrepair.store/MemStore`, or any `Store` impl)
    - the Advisor  (mock today; real LLM is the next seam --
                     `electronicrepair.advisor/Advisor` is already the
                     injection point)

  One graph run = one repair-shop coordination request. No unbounded
  inner loop -- each run is auditable and checkpointed. An intake's
  operating history is advanced by MANY runs (intake-repair-order /
  schedule-technician-dispatch / order-parts / complete-repair /
  flag-safety-concern), each its own independent graph run, and every
  commit/hold/approval-rejected decision fact lands in
  `electronicrepair.store`'s append-only ledger (`store/append-ledger!`)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [electronicrepair.advisor :as advisor]
            [electronicrepair.governor :as governor]
            [electronicrepair.store :as store]))

;; ----------------------------- audit fact shapes -----------------------------

(defn- commit-record
  "The store-level payload a commit represents. Intake creation has no
  separate stateful commit-record! entity of its own beyond the ledger;
  dispatch/parts-order/repair-completion additionally flip a dedicated
  store flag via `apply-commit-mutation!` below."
  [request _context proposal]
  {:effect  (:effect proposal)
   :path    [(:subject request)]
   :value   (:value proposal)
   :payload (:value proposal)})

(defn- commit-fact
  "The audit fact written when a proposal commits."
  [request context proposal]
  {:t           :committed
   :op          (:op request)
   :actor       (:actor-id context)
   :subject     (:subject request)
   :disposition :commit
   :basis       (:cites proposal)
   :stake       (:stake proposal)
   :record      (:value proposal)})

(defn- apply-commit-mutation!
  "The store-level side effect a commit represents, if any:
  `:schedule-technician-dispatch` flips the intake's `:dispatched?`
  flag; `:order-parts` flips `:parts-ordered?`; `:complete-repair` flips
  `:completed?`. `:intake-repair-order` and `:flag-safety-concern` have
  no dedicated mutable flag of their own -- the ledger fact IS the
  durable record, same discipline as `pastaops.operation`'s ops without
  a stateful commit-record! entity."
  [store request]
  (case (:op request)
    :schedule-technician-dispatch (store/mark-dispatched! store (:intake-id request))
    :order-parts                  (store/mark-parts-ordered! store (:intake-id request))
    :complete-repair               (store/mark-completed! store (:intake-id request))
    nil))

;; ----------------------------- StateGraph -----------------------------

(defn build
  "Compiles an OperationActor graph bound to `store`. opts:
    :advisor      -- an `electronicrepair.advisor/Advisor` (default:
                     `(advisor/mock-advisor)`)
    :checkpointer -- a `langgraph.checkpoint/Checkpointer`
                     (default: in-memory `cp/mem-checkpointer`)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal (store/snapshot store))}))

      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [disposition (cond
                               (:hard? verdict)     :hold
                               (:escalate? verdict)  :escalate
                               :else                 :commit)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(governor/hold-fact request context verdict)]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :subject (:subject request)
                        :reason (if (:high-stakes? verdict) :high-stakes-actuation :low-confidence)
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)}))))

      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :payload (assoc (:value proposal)
                                            :approved-by (:by approval)))
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:subject request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (governor/hold-fact request context
                                                (update verdict :violations
                                                        (fnil conj [])
                                                        {:rule :approver-rejected}))
                            {:t :approval-rejected})]})))

      (g/add-node :commit
        (fn [{:keys [request context proposal]}]
          (let [f (commit-fact request context proposal)]
            (apply-commit-mutation! store request)
            (store/append-ledger! store f)
            {:audit [f]})))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
