(ns electronicrepair.phase-test
  (:require [clojure.test :refer :all]
            [electronicrepair.phase :as phase]))

(deftest test-phase-allows-commit?
  (testing "phase 0 doesn't allow commit"
    (is (false? (phase/phase-allows-commit? 0 :intake-repair-order))))
  (testing "phase 1 doesn't allow commit"
    (is (false? (phase/phase-allows-commit? 1 :intake-repair-order))))
  (testing "phase 2 allows intake commit"
    (is (true? (phase/phase-allows-commit? 2 :intake-repair-order))))
  (testing "phase 2 allows dispatch commit"
    (is (true? (phase/phase-allows-commit? 2 :schedule-technician-dispatch))))
  (testing "phase 2 allows parts commit"
    (is (true? (phase/phase-allows-commit? 2 :order-parts))))
  (testing "repair-completion never auto-commits even in phase 3"
    (is (false? (phase/phase-allows-commit? 3 :complete-repair))))
  (testing "safety-escalation never auto-commits"
    (is (false? (phase/phase-allows-commit? 3 :flag-safety-concern)))))

(deftest test-phase-for-op
  (testing "intake-repair-order requires phase 1"
    (is (= 1 (phase/phase-for-op :intake-repair-order))))
  (testing "schedule-technician-dispatch requires phase 1"
    (is (= 1 (phase/phase-for-op :schedule-technician-dispatch))))
  (testing "order-parts requires phase 1"
    (is (= 1 (phase/phase-for-op :order-parts))))
  (testing "complete-repair is human-only"
    (is (= :human-only (phase/phase-for-op :complete-repair))))
  (testing "flag-safety-concern is human-only"
    (is (= :human-only (phase/phase-for-op :flag-safety-concern)))))

(deftest test-governance-action
  (testing "hard violations always hold"
    (is (= :hold
           (phase/governance-action
             2 :intake-repair-order
             {:hard? true :escalate? false}))))

  (testing "low confidence escalates"
    (is (= :escalate
           (phase/governance-action
             2 :intake-repair-order
             {:hard? false :escalate? true}))))

  (testing "high-stakes escalates"
    (is (= :escalate
           (phase/governance-action
             2 :complete-repair
             {:hard? false :escalate? true}))))

  (testing "clean proposal in phase 2 commits"
    (is (= :commit
           (phase/governance-action
             2 :intake-repair-order
             {:hard? false :escalate? false}))))

  (testing "clean proposal in phase 1 escalates"
    (is (= :escalate
           (phase/governance-action
             1 :intake-repair-order
             {:hard? false :escalate? false}))))

  (testing "phase 3 same as phase 2 for repair-completion"
    (is (= :escalate
           (phase/governance-action
             3 :complete-repair
             {:hard? false :escalate? false})))))
