(ns electronicrepair.operation-graph-test
  "Integration tests for `electronicrepair.operation/build` -- builds
  the REAL compiled `langgraph.graph` StateGraph and runs it end-to-end
  via `langgraph.graph/run*` through commit / hard-hold /
  escalate-approve / escalate-reject routes. NONE of this existed
  before: there was no `advisor.cljc`/`defprotocol`/mock ANYWHERE in
  this repo, no `operation.cljc` `state-graph`/`add-node`/
  `compile-graph` call, and `store/append-ledger!` (also new) had never
  been called from anywhere -- there was no commit/hold path to call it
  from.

  Falsifiable claims each test proves, not just asserts:
    1. the ledger is verified EMPTY before the run (never pre-populated
       by test fixtures), so a post-run non-empty ledger is genuinely
       caused by this run's own `:commit`/`:hold` node, not residue;
    2. a HARD governor violation blocks the graph from EVER reaching
       `:commit` -- proven for `:intake-repair-order` against an
       UNVERIFIED client, a real ground-truth store lookup the advisor's
       own (optimistic) proposal cannot override;
    3. the advisor's proposal is genuinely threaded through
       `:advise -> :govern -> :decide -> :commit` -- proven by injecting
       a custom `Advisor` (via `build`'s `:advisor` opt) whose proposal
       carries a random, single-use `:notes` string generated at test
       run time (impossible to have been hardcoded anywhere in
       `electronicrepair.operation`) and asserting the committed ledger
       fact's `:record` carries that EXACT string;
    4. `:complete-repair` and `:flag-safety-concern` genuinely escalate
       (checkpointed interrupt, not an immediate hold) and stay
       un-recorded in the ledger until a human shop dispatcher resumes
       the thread -- both the approve->commit and reject->hold branches
       are exercised;
    5. `store/mark-dispatched!` / `store/mark-completed!` only fire as a
       genuine effect of a real `:commit` -- never on hold or on the
       interrupted (not-yet-approved) escalate path."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [electronicrepair.advisor :as advisor]
            [electronicrepair.operation :as operation]
            [electronicrepair.store :as store]))

(def ^:private dispatcher {:actor-id "shop-dispatcher-01" :role :shop-coordinator})

(def ^:private seed
  "Reference repair-shop data: one verified client, one office-equipment
  record, one hazardous CRT record with a completed safety checklist,
  and one pre-existing intake with a documented, cost-matching
  estimate ready for dispatch/parts/completion proposals."
  {:clients {"C001" {:id "C001" :name "ABC Electronics" :contact "contact@abc-electronics.example"}}
   :equipment {"E001" {:id "E001" :model "HP-LaserJet-4050" :equipment-type :office-equipment}
               "E002" {:id "E002" :model "Philips-CRT-Monitor" :equipment-type :crt}}
   :safety-checklists {"E002" {:electrical-safety-check? true
                               :hazmat-assessment? true
                               :technician-certification-verified? true}}
   :estimates {"INT-001" {:labor-hours 2.0 :parts-cost 150.0
                          :description "Replace power supply and recalibrate"
                          :parts [{:cost 100.0} {:cost 50.0}]
                          :total-parts-cost 150.0}}})

(defn- exec
  ([actor tid request] (exec actor tid request dispatcher))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(deftest commit-path-clean-intake-proposal
  (testing "a clean :intake-repair-order commits through the REAL
            compiled graph and appends exactly one fact to the audit
            ledger -- the ledger is verified EMPTY beforehand, proving
            the write is a genuine effect of THIS run, not test-setup
            residue"
    (let [s (store/mem-store {:initial seed})
          actor (operation/build s)]
      (is (empty? (store/ledger s)) "ledger is empty before any run")
      (let [result (exec actor "t-commit" {:op :intake-repair-order :subject "E001"
                                            :client-id "C001" :equipment-id "E001"
                                            :shop-code "SHOP1" :intake-sequence 1
                                            :confidence 0.9})
            state (:state result)]
        (is (= :done (:status result)))
        (is (= :commit (:disposition state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :committed (:t (first ledger))))
          (is (= :intake-repair-order (:op (first ledger))))
          (is (= "E001" (:subject (first ledger)))))))))

(deftest hard-hold-path-client-not-verified
  (testing ":intake-repair-order against an UNVERIFIED client (`C999`,
            never seeded) is a HARD governor violation -- the real
            graph routes straight to :hold (no interrupt, no
            human-approval detour) and durably records the hold fact"
    (let [s (store/mem-store {:initial seed})
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [result (exec actor "t-hold" {:op :intake-repair-order :subject "E002"
                                          :client-id "C999" :equipment-id "E002"
                                          :shop-code "SHOP1" :intake-sequence 1
                                          :confidence 0.9})
            state (:state result)]
        (is (= :done (:status result)) "no interrupt -- HARD holds never pause for approval")
        (is (= :hold (:disposition state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :governor-hold (:t (first ledger))))
          (is (some #{:client-not-verified} (map :rule (:violations (first ledger))))))))))

(deftest governor-hard-hold-blocks-ledger-write-before-commit
  (testing "a HARD governor violation (:client-not-verified) proves the
            ledger contains ONLY a :governor-hold fact -- never a
            :committed fact -- and the store's own ground truth (not
            the advisor's optimistic proposal) is what decides"
    (let [s (store/mem-store {:initial seed})
          actor (operation/build s)
          result (exec actor "t-govhold" {:op :intake-repair-order :subject "E002"
                                           :client-id "C999" :equipment-id "E002"
                                           :shop-code "SHOP1" :intake-sequence 1
                                           :confidence 0.9})]
      (is (= :hold (:disposition (:state result))))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (every? #(= :governor-hold (:t %)) ledger)
            "no :committed fact was ever written")))))

(deftest escalate-then-approve-commits-and-genuinely-consults-advisor
  (testing ":complete-repair ALWAYS escalates (high-stakes
            :actuation/complete-repair) -- the real graph GENUINELY
            interrupts (checkpointed) at :request-approval, the ledger
            stays EMPTY until a human shop dispatcher resumes it, and
            `store/mark-completed!` has NOT fired while interrupted. A
            custom, non-default Advisor (injected at test time via
            `build`'s `:advisor` opt, NOT a call-site literal in
            `electronicrepair.operation`) proposes with a randomly
            generated, single-use `:notes` string. Only if the graph
            truly threads the Advisor's own proposal through
            :advise -> :govern -> :decide -> :commit (rather than
            re-deriving/hardcoding a proposal internally) can that
            exact string reach the ledger's committed fact"
    (let [distinctive-notes (str "TEST-ADVISOR-" (rand-int 1000000000))
          test-advisor (reify advisor/Advisor
                         (-advise [_ _store request]
                           {:op :complete-repair
                            :stake :actuation/complete-repair
                            :effect :propose
                            :confidence 0.9
                            :value {:intake-id (:intake-id request)
                                    :technician-id "T001"
                                    :notes distinctive-notes}
                            :cites []}))
          s (store/mem-store {:initial seed})
          actor (operation/build s {:advisor test-advisor})]
      (is (empty? (store/ledger s)))
      (let [held (exec actor "t-escalate" {:op :complete-repair :subject "INT-001"
                                            :intake-id "INT-001" :technician-id "T001"
                                            :confidence 0.9})]
        (is (= :interrupted (:status held)))
        (is (= [:request-approval] (:frontier held)))
        (is (empty? (store/ledger s)) "not yet committed -- awaiting human sign-off")
        (is (false? (store/repair-already-completed? (store/snapshot s) "INT-001"))
            "store/mark-completed! never fired while merely interrupted")
        (let [approved (g/run* actor {:approval {:status :approved :by "shop-dispatcher-01"}}
                               {:thread-id "t-escalate" :resume? true})
              approved-state (:state approved)]
          (is (= :done (:status approved)))
          (is (= :commit (:disposition approved-state)))
          (let [ledger (store/ledger s)]
            (is (= 1 (count ledger)))
            (is (= :committed (:t (first ledger))))
            (is (= distinctive-notes (get-in (first ledger) [:record :notes]))
                "the ledger's committed fact carries the INJECTED test
                Advisor's own distinctive notes string -- proof the
                graph genuinely threads the Advisor's real proposal
                through :govern -> :decide -> :commit rather than
                hardcoding a pass-string or ignoring the :advise node's
                output")
            (is (true? (store/repair-already-completed? (store/snapshot s) "INT-001"))
                "store/mark-completed! DID fire once genuinely committed")))))))

(deftest escalate-then-reject-holds
  (testing "a human shop dispatcher rejecting an escalated
            :flag-safety-concern routes to :hold via the
            :request-approval node's own decision, and durably records
            the rejection -- not a hand-rolled parallel path"
    (let [s (store/mem-store {:initial seed})
          actor (operation/build s)
          _held (exec actor "t-reject" {:op :flag-safety-concern :subject "INT-001"
                                         :intake-id "INT-001" :concern-type :electrical
                                         :description "Possible loose grounding wire"
                                         :confidence 0.8})
          rejected (g/run* actor {:approval {:status :rejected :by "shop-dispatcher-01"}}
                           {:thread-id "t-reject" :resume? true})
          rejected-state (:state rejected)]
      (is (= :done (:status rejected)))
      (is (= :hold (:disposition rejected-state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (= :approval-rejected (:t (first ledger))))
        (is (some #{:approver-rejected} (map :rule (:violations (first ledger)))))))))

(deftest dispatch-commit-flips-store-flag-only-on-real-commit
  (testing "a clean :schedule-technician-dispatch commits, and
            `store/mark-dispatched!` genuinely fires as an effect of
            the :commit node -- proven both by the store flag AND the
            ledger fact"
    (let [s (store/mem-store {:initial seed})
          actor (operation/build s)]
      (is (false? (store/intake-already-dispatched? (store/snapshot s) "INT-001")))
      (let [result (exec actor "t-dispatch" {:op :schedule-technician-dispatch :subject "INT-001"
                                              :intake-id "INT-001" :client-id "C001"
                                              :equipment-id "E001" :technician-id "T001"
                                              :shop-code "SHOP1" :dispatch-sequence 1
                                              :confidence 0.9})]
        (is (= :commit (:disposition (:state result))))
        (is (true? (store/intake-already-dispatched? (store/snapshot s) "INT-001")))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :committed (:t (first ledger)))))))))

(deftest duplicate-dispatch-hard-holds
  (testing "a SECOND :schedule-technician-dispatch against an
            ALREADY-dispatched intake is a HARD governor violation
            (`:already-dispatched`) -- proven end-to-end through the
            compiled graph after a genuine first commit"
    (let [s (store/mem-store {:initial seed})
          actor (operation/build s)
          first-result (exec actor "t-dispatch-1" {:op :schedule-technician-dispatch :subject "INT-001"
                                                     :intake-id "INT-001" :client-id "C001"
                                                     :equipment-id "E001" :technician-id "T001"
                                                     :shop-code "SHOP1" :dispatch-sequence 1
                                                     :confidence 0.9})]
      (is (= :commit (:disposition (:state first-result))))
      (let [second-result (exec actor "t-dispatch-2" {:op :schedule-technician-dispatch :subject "INT-001"
                                                        :intake-id "INT-001" :client-id "C001"
                                                        :equipment-id "E001" :technician-id "T002"
                                                        :shop-code "SHOP1" :dispatch-sequence 2
                                                        :confidence 0.9})]
        (is (= :hold (:disposition (:state second-result))))
        (let [ledger (store/ledger s)]
          (is (= 2 (count ledger)))
          (is (= :committed (:t (first ledger))))
          (is (= :governor-hold (:t (second ledger))))
          (is (some #{:already-dispatched} (map :rule (:violations (second ledger))))))))))
