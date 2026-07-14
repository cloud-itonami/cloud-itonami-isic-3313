(ns electronicrepair.governor-contract-test
  (:require [clojure.test :refer :all]
            [electronicrepair.governor :as governor]))

(def verified-client
  {:id "C001" :name "ABC Electronics" :contact "abc@com"})

(def verified-equipment
  {:id "E001" :model "HP-4050" :equipment-type :office-equipment})

(def complete-checklist
  {:electrical-safety-check? true
   :hazmat-assessment? true
   :technician-certification-verified? true})

(def complete-estimate
  {:labor-hours 2.0
   :parts-cost 150.0
   :description "Repair display"
   :parts [{:cost 100.0} {:cost 50.0}]
   :total-parts-cost 150.0})

(def minimal-store
  {:clients {"C001" verified-client}
   :equipment {"E001" verified-equipment}
   :safety-checklists {"E001" complete-checklist}
   :estimates {"INT-001" complete-estimate}
   :dispatched? {}
   :completed? {}
   :parts-ordered? {}})

(deftest test-governor-check-intake-success
  (let [request {:op :intake-repair-order
                 :subject "INT-001"}
        proposal {:value {:client-id "C001" :equipment-id "E001"}
                  :confidence 0.8}
        verdict (governor/check request {} proposal minimal-store)]
    (testing "all checks pass for valid intake"
      (is (true? (:ok? verdict)))
      (is (empty? (:violations verdict)))
      (is (false? (:hard? verdict))))))

(deftest test-governor-check-missing-client
  (let [store (assoc minimal-store :clients {})
        request {:op :intake-repair-order :subject "INT-001"}
        proposal {:value {:client-id "C001" :equipment-id "E001"}
                  :confidence 0.8}
        verdict (governor/check request {} proposal store)]
    (testing "missing client triggers hard violation"
      (is (false? (:ok? verdict)))
      (is (seq (:violations verdict)))
      (is (true? (:hard? verdict))))))

(deftest test-governor-check-missing-equipment
  (let [store (assoc minimal-store :equipment {})
        request {:op :intake-repair-order :subject "INT-001"}
        proposal {:value {:client-id "C001" :equipment-id "E001"}
                  :confidence 0.8}
        verdict (governor/check request {} proposal store)]
    (testing "missing equipment triggers hard violation"
      (is (false? (:ok? verdict)))
      (is (seq (:violations verdict)))
      (is (true? (:hard? verdict))))))

(deftest test-governor-check-low-confidence
  (let [request {:op :intake-repair-order :subject "INT-001"}
        proposal {:value {:client-id "C001" :equipment-id "E001"}
                  :confidence 0.4}  ; below floor of 0.6
        verdict (governor/check request {} proposal minimal-store)]
    (testing "low confidence triggers escalation"
      (is (false? (:ok? verdict)))
      (is (true? (:escalate? verdict)))
      (is (false? (:hard? verdict))))))

(deftest test-governor-check-high-stakes-escalate
  (let [request {:op :complete-repair :subject "INT-001"}
        proposal {:value {:intake-id "INT-001"}
                  :confidence 0.8
                  :stake :actuation/complete-repair}  ; high-stakes
        verdict (governor/check request {} proposal minimal-store)]
    (testing "high-stakes operation escalates even when clean"
      (is (false? (:ok? verdict)))
      (is (true? (:escalate? verdict)))
      (is (true? (:high-stakes? verdict))))))

(deftest test-governor-check-safety-escalation
  (let [request {:op :flag-safety-concern :subject "INT-001"}
        proposal {:value {:intake-id "INT-001"
                         :concern-type :hazmat
                         :description "Hazmat issue detected"}
                  :confidence 0.9
                  :stake :actuation/escalation}
        verdict (governor/check request {} proposal minimal-store)]
    (testing "safety concern always escalates"
      (is (false? (:ok? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest test-governor-hold-fact
  (let [request {:op :intake-repair-order :subject "INT-001"}
        context {:actor-id "repair-actor-001"}
        violations [{:rule :client-not-verified
                    :detail "Client C001 not verified"}]
        verdict {:violations violations :hard? true :confidence 0.5}
        fact (governor/hold-fact request context verdict)]
    (testing "hold fact records violation details"
      (is (= :governor-hold (:t fact)))
      (is (= :intake-repair-order (:op fact)))
      (is (= :hold (:disposition fact)))
      (is (= [:client-not-verified] (:basis fact))))))
