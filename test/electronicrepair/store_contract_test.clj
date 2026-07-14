(ns electronicrepair.store-contract-test
  (:require [clojure.test :refer :all]
            [electronicrepair.store :as store]))

(def sample-store
  {:clients {"C001" {:id "C001" :name "ABC Electronics" :contact "abc@com"}
             "C002" {:id "C002" :name "XYZ Corp" :contact "xyz@com"}}
   :equipment {"E001" {:id "E001" :model "HP-4050" :equipment-type :office-equipment}
               "E002" {:id "E002" :model "Philips-X-Ray" :equipment-type :radioactive}}
   :intakes {"INT-001" {:record_id "INT-001"}
             "INT-002" {:record_id "INT-002"}}
   :dispatches {"DSP-001" {:record_id "DSP-001" :intake_id "INT-001"}
                "DSP-002" {:record_id "DSP-002" :intake_id "INT-002"}}
   :estimates {"INT-001" {:labor-hours 2.0 :parts-cost 150.0}
               "INT-002" {:labor-hours 1.5 :parts-cost 75.0}}
   :safety-checklists {"E002" {:electrical-safety-check? true
                               :hazmat-assessment? true
                               :technician-certification-verified? true}}
   :dispatched? {"INT-001" true}
   :completed? {}
   :parts-ordered? {"INT-001" true}})

(deftest test-client-retrieval
  (testing "retrieve existing client"
    (is (= {:id "C001" :name "ABC Electronics" :contact "abc@com"}
           (store/client sample-store "C001"))))
  (testing "retrieve non-existent client returns nil"
    (is (nil? (store/client sample-store "C999")))))

(deftest test-equipment-retrieval
  (testing "retrieve existing equipment"
    (is (= {:id "E001" :model "HP-4050" :equipment-type :office-equipment}
           (store/equipment sample-store "E001"))))
  (testing "retrieve non-existent equipment returns nil"
    (is (nil? (store/equipment sample-store "E999")))))

(deftest test-intake-record-retrieval
  (testing "retrieve existing intake"
    (is (= {:record_id "INT-001"}
           (store/intake-record sample-store "INT-001"))))
  (testing "retrieve non-existent intake returns nil"
    (is (nil? (store/intake-record sample-store "INT-999")))))

(deftest test-dispatch-record-retrieval
  (testing "retrieve existing dispatch"
    (is (= {:record_id "DSP-001" :intake_id "INT-001"}
           (store/dispatch-record sample-store "DSP-001"))))
  (testing "retrieve non-existent dispatch returns nil"
    (is (nil? (store/dispatch-record sample-store "DSP-999")))))

(deftest test-estimate-retrieval
  (testing "retrieve estimate for intake"
    (is (= {:labor-hours 2.0 :parts-cost 150.0}
           (store/estimate-for-intake sample-store "INT-001"))))
  (testing "retrieve non-existent estimate returns nil"
    (is (nil? (store/estimate-for-intake sample-store "INT-999")))))

(deftest test-checklist-retrieval
  (testing "retrieve checklist for equipment"
    (is (= {:electrical-safety-check? true
            :hazmat-assessment? true
            :technician-certification-verified? true}
           (store/checklist-for-equipment sample-store "E002"))))
  (testing "retrieve checklist for equipment without one returns nil"
    (is (nil? (store/checklist-for-equipment sample-store "E001")))))

(deftest test-dispatch-status
  (testing "intake-already-dispatched? returns true"
    (is (true? (store/intake-already-dispatched? sample-store "INT-001"))))
  (testing "intake-already-dispatched? returns false"
    (is (false? (store/intake-already-dispatched? sample-store "INT-002")))))

(deftest test-completion-status
  (testing "repair-already-completed? returns false when not completed"
    (is (false? (store/repair-already-completed? sample-store "INT-001"))))
  (testing "repair-already-completed? checks :completed? map"
    (let [st (assoc sample-store :completed? {"INT-001" true})]
      (is (true? (store/repair-already-completed? st "INT-001"))))))

(deftest test-parts-order-status
  (testing "parts-already-ordered? returns true"
    (is (true? (store/parts-already-ordered? sample-store "INT-001"))))
  (testing "parts-already-ordered? returns false"
    (is (false? (store/parts-already-ordered? sample-store "INT-002")))))

(deftest test-all-intakes
  (testing "retrieve all intake IDs"
    (is (= #{"INT-001" "INT-002"}
           (set (store/all-intakes sample-store))))))

(deftest test-all-dispatches
  (testing "retrieve all dispatch records"
    (let [dispatches (store/all-dispatches sample-store)]
      (is (= 2 (count dispatches)))
      (is (some #(= (:record_id %) "DSP-001") dispatches))
      (is (some #(= (:record_id %) "DSP-002") dispatches)))))
