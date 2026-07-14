(ns electronicrepair.facts-test
  (:require [clojure.test :refer :all]
            [electronicrepair.facts :as facts]))

(deftest test-client-verified?
  (testing "client with all required fields is verified"
    (is (facts/client-verified?
          {:id "C001" :name "ABC Electronics" :contact "contact@abc.com"})))
  (testing "client without ID is not verified"
    (is (not (facts/client-verified?
               {:name "ABC Electronics" :contact "contact@abc.com"}))))
  (testing "client with blank ID is not verified"
    (is (not (facts/client-verified?
               {:id "" :name "ABC Electronics" :contact "contact@abc.com"}))))
  (testing "nil client is not verified"
    (is (not (facts/client-verified? nil)))))

(deftest test-equipment-registered?
  (testing "equipment with all required fields is registered"
    (is (facts/equipment-registered?
          {:id "E001" :model "HP-LaserJet-4050" :equipment-type :high-voltage})))
  (testing "equipment without ID is not registered"
    (is (not (facts/equipment-registered?
               {:model "HP-LaserJet-4050" :equipment-type :high-voltage}))))
  (testing "equipment with blank model is not registered"
    (is (not (facts/equipment-registered?
               {:id "E001" :model "" :equipment-type :high-voltage}))))
  (testing "nil equipment is not registered"
    (is (not (facts/equipment-registered? nil)))))

(deftest test-safety-checklist-required?
  (testing "high-voltage equipment requires checklist"
    (is (facts/safety-checklist-required? :high-voltage)))
  (testing "radioactive equipment requires checklist"
    (is (facts/safety-checklist-required? :radioactive)))
  (testing "CRT equipment requires checklist"
    (is (facts/safety-checklist-required? :crt)))
  (testing "laser equipment requires checklist"
    (is (facts/safety-checklist-required? :laser)))
  (testing "standard office equipment does not require checklist"
    (is (not (facts/safety-checklist-required? :office-equipment)))))

(deftest test-safety-checklist-complete?
  (testing "checklist with all required checks is complete"
    (is (facts/safety-checklist-complete?
          {:electrical-safety-check? true
           :hazmat-assessment? true
           :technician-certification-verified? true})))
  (testing "checklist missing electrical-safety-check is incomplete"
    (is (not (facts/safety-checklist-complete?
               {:electrical-safety-check? false
                :hazmat-assessment? true
                :technician-certification-verified? true}))))
  (testing "nil checklist is not complete"
    (is (not (facts/safety-checklist-complete? nil)))))

(deftest test-estimate-provided?
  (testing "estimate with all required fields is provided"
    (is (facts/estimate-provided?
          {:labor-hours 2.5
           :parts-cost 150.0
           :description "Replace motherboard"})))
  (testing "estimate with zero parts-cost is valid"
    (is (facts/estimate-provided?
          {:labor-hours 1.0
           :parts-cost 0
           :description "Software fix"})))
  (testing "estimate with zero labor-hours is invalid"
    (is (not (facts/estimate-provided?
               {:labor-hours 0
                :parts-cost 150.0
                :description "Replace motherboard"}))))
  (testing "nil estimate is not provided"
    (is (not (facts/estimate-provided? nil)))))

(deftest test-required-evidence-satisfied?
  (testing "all evidence present -> satisfied"
    (is (facts/required-evidence-satisfied?
          :office-equipment
          {:id "C001" :name "ABC" :contact "abc@com"}
          {:id "E001" :model "HP1" :equipment-type :office-equipment}
          nil  ; no checklist needed for office-equipment
          {:labor-hours 1.0 :parts-cost 100 :description "Fix"})))

  (testing "high-voltage without checklist -> not satisfied"
    (is (not (facts/required-evidence-satisfied?
               :high-voltage
               {:id "C001" :name "ABC" :contact "abc@com"}
               {:id "E001" :model "HP1" :equipment-type :high-voltage}
               nil  ; missing checklist
               {:labor-hours 1.0 :parts-cost 100 :description "Fix"}))))

  (testing "high-voltage with checklist -> satisfied"
    (is (facts/required-evidence-satisfied?
          :high-voltage
          {:id "C001" :name "ABC" :contact "abc@com"}
          {:id "E001" :model "HP1" :equipment-type :high-voltage}
          {:electrical-safety-check? true
           :hazmat-assessment? true
           :technician-certification-verified? true}
          {:labor-hours 1.0 :parts-cost 100 :description "Fix"})))

  (testing "missing client -> not satisfied"
    (is (not (facts/required-evidence-satisfied?
               :office-equipment
               nil  ; missing client
               {:id "E001" :model "HP1" :equipment-type :office-equipment}
               nil
               {:labor-hours 1.0 :parts-cost 100 :description "Fix"})))))
