(ns electronicrepair.registry-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [electronicrepair.registry :as registry]))

(deftest test-compute-total-parts-cost
  (testing "sum of parts with costs"
    (is (= 250.0
           (registry/compute-total-parts-cost
             [{:cost 100.0} {:cost 75.0} {:cost 75.0}]))))
  (testing "empty parts list"
    (is (= 0 (registry/compute-total-parts-cost []))))
  (testing "parts without cost field"
    (is (= 0 (registry/compute-total-parts-cost
               [{:name "Part1"} {:name "Part2"}])))))

(deftest test-parts-cost-matches-claim?
  (testing "claimed cost matches computed"
    (is (registry/parts-cost-matches-claim?
          {:parts [{:cost 100.0} {:cost 50.0}]
           :total-parts-cost 150.0})))
  (testing "claimed cost does not match computed"
    (is (not (registry/parts-cost-matches-claim?
               {:parts [{:cost 100.0} {:cost 50.0}]
                :total-parts-cost 200.0}))))
  (testing "zero cost estimate"
    (is (registry/parts-cost-matches-claim?
          {:parts []
           :total-parts-cost 0}))))

(deftest test-register-intake
  (testing "valid intake registration"
    (let [result (registry/register-intake "C001" "E001" "SHOP1" 0)]
      (is (contains? result "record"))
      (is (contains? result "intake_number"))
      (is (string? (get result "intake_number")))
      (is (str/starts-with? (get result "intake_number") "SHOP1-INT-"))))

  (testing "zero-padded sequence number"
    (let [result (registry/register-intake "C001" "E001" "SHOP1" 42)]
      (is (str/includes? (get result "intake_number") "000042"))))

  (testing "missing client-id throws"
    (is (thrown? clojure.lang.ExceptionInfo
          (registry/register-intake nil "E001" "SHOP1" 0))))

  (testing "missing equipment-id throws"
    (is (thrown? clojure.lang.ExceptionInfo
          (registry/register-intake "C001" nil "SHOP1" 0))))

  (testing "missing shop-code throws"
    (is (thrown? clojure.lang.ExceptionInfo
          (registry/register-intake "C001" "E001" nil 0))))

  (testing "negative sequence throws"
    (is (thrown? clojure.lang.ExceptionInfo
          (registry/register-intake "C001" "E001" "SHOP1" -1)))))

(deftest test-register-dispatch
  (testing "valid dispatch registration"
    (let [result (registry/register-dispatch "INT-000001" "T001" "SHOP1" 0)]
      (is (contains? result "record"))
      (is (contains? result "dispatch_number"))
      (is (string? (get result "dispatch_number")))
      (is (str/starts-with? (get result "dispatch_number") "SHOP1-DSP-"))))

  (testing "missing intake-id throws"
    (is (thrown? clojure.lang.ExceptionInfo
          (registry/register-dispatch nil "T001" "SHOP1" 0))))

  (testing "missing technician-id throws"
    (is (thrown? clojure.lang.ExceptionInfo
          (registry/register-dispatch "INT-001" nil "SHOP1" 0)))))

(deftest test-register-parts-order
  (testing "valid parts order registration"
    (let [result (registry/register-parts-order "INT-000001" "SHOP1" 0)]
      (is (contains? result "record"))
      (is (contains? result "order_number"))
      (is (string? (get result "order_number")))
      (is (str/starts-with? (get result "order_number") "SHOP1-PRT-"))))

  (testing "missing intake-id throws"
    (is (thrown? clojure.lang.ExceptionInfo
          (registry/register-parts-order nil "SHOP1" 0))))

  (testing "negative sequence throws"
    (is (thrown? clojure.lang.ExceptionInfo
          (registry/register-parts-order "INT-001" "SHOP1" -1)))))

(deftest test-append
  (testing "append record to history"
    (let [history [{"record_id" "R001"}]
          result {"record" {"record_id" "R002"}}
          updated (registry/append history result)]
      (is (= 2 (count updated)))
      (is (= {"record_id" "R002"} (get updated 1))))))
