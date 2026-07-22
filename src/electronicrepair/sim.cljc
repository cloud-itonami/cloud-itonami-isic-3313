(ns electronicrepair.sim
  "Simulation harness for the Community Electronic and Optical Equipment
  Repair Operations coordinator actor. Run with: clojure -M:dev:run

  PRIOR GAP (fixed here): `deps.edn`'s `:run` alias already pointed at
  `-m electronicrepair.sim`, but this namespace did not exist anywhere
  in the repo -- `clojure -M:dev:run` would have failed outright. This
  drives the real compiled graph (`operation/build`) through a clean
  low-stakes commit (`:intake-repair-order`), a clean dispatch/parts
  commit, two always-escalate high-stakes paths
  (`:complete-repair` approved, `:flag-safety-concern` rejected), and a
  hard-hold path (`:intake-repair-order` against an unverified client),
  then prints the resulting append-only audit ledger. Mirrors
  `pastaops.sim` (cloud-itonami-isic-1074) / `transportops.sim`
  (cloud-itonami-isic-869)."
  (:require [langgraph.graph :as g]
            [electronicrepair.operation :as operation]
            [electronicrepair.store :as store]))

(def shop-dispatcher {:actor-id "shop-dispatcher-01" :role :shop-coordinator})

(def ^:private seed
  "Reference repair-shop data: one verified client, one office-equipment
  record (no safety checklist required), one hazardous CRT record (with
  a completed safety checklist), and one pre-existing intake with a
  documented, cost-matching estimate ready for dispatch/parts proposals."
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

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "shop-dispatcher-01"}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "shop-dispatcher-01"}}
          {:thread-id tid :resume? true}))

(defn demo
  "Run the compiled StateGraph through a commit path
  (:intake-repair-order, governor-clean), a second commit path
  (:schedule-technician-dispatch against the seeded INT-001 estimate),
  two always-escalate high-stakes paths (:complete-repair approved,
  :flag-safety-concern rejected), and a hard-hold path
  (:intake-repair-order against an UNVERIFIED client); print each
  result and the final audit ledger."
  []
  (let [s (store/mem-store {:initial seed})
        actor (operation/build s)]

    (println "=== Electronic Repair Operations Coordinator Demo ===")

    (println "\n== intake-repair-order C001/E001 (governor-clean, low-stakes -> commit) ==")
    (println (exec-op actor "t1"
                      {:op :intake-repair-order :subject "E001"
                       :client-id "C001" :equipment-id "E001"
                       :shop-code "SHOP1" :intake-sequence 1 :confidence 0.9}
                      shop-dispatcher))

    (println "\n== schedule-technician-dispatch INT-001 (governor-clean -> commit) ==")
    (println (exec-op actor "t2"
                      {:op :schedule-technician-dispatch :subject "INT-001"
                       :intake-id "INT-001" :client-id "C001" :equipment-id "E001"
                       :technician-id "T001" :shop-code "SHOP1"
                       :dispatch-sequence 1 :confidence 0.9}
                      shop-dispatcher))

    (println "\n== complete-repair INT-001 (ALWAYS escalates -- technician sign-off -- dispatcher approves) ==")
    (let [r (exec-op actor "t3"
                     {:op :complete-repair :subject "INT-001"
                      :intake-id "INT-001" :technician-id "T001"
                      :notes "Power supply replaced, unit tested and calibrated"
                      :confidence 0.9}
                     shop-dispatcher)]
      (println r)
      (println "-- shop dispatcher approves (technician sign-off confirmed) --")
      (println (approve! actor "t3")))

    (println "\n== flag-safety-concern INT-001 (ALWAYS escalates -- dispatcher rejects) ==")
    (let [r (exec-op actor "t4"
                     {:op :flag-safety-concern :subject "INT-001"
                      :intake-id "INT-001" :concern-type :electrical
                      :description "Possible loose grounding wire near power supply"
                      :confidence 0.8}
                     shop-dispatcher)]
      (println r)
      (println "-- shop dispatcher rejects (inspected on-site, no grounding issue found) --")
      (println (reject! actor "t4")))

    (println "\n== intake-repair-order for UNVERIFIED client (no client record on file) -> HARD hold ==")
    (println (exec-op actor "t5"
                      {:op :intake-repair-order :subject "E002"
                       :client-id "C999" :equipment-id "E002"
                       :shop-code "SHOP1" :intake-sequence 2 :confidence 0.9}
                      shop-dispatcher))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger s)] (println f))

    {:ledger (store/ledger s)}))

(defn -main
  "clojure -M:run entrypoint."
  [& _args]
  (demo))

(comment
  ;; In a real REPL:
  (demo))
