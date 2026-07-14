(ns electronicrepair.facts
  "Ground-truth facts for electronic and optical equipment repair
  coordination. This actor supports a repair shop's intake, dispatch,
  parts logistics, and safety coordination — NOT direct hands-on
  calibration/repair (that remains the technician's exclusive authority).

  Required evidence for a repair intake to proceed:
  1. Client record exists and is current
  2. Equipment record exists with repair history
  3. Safety checklist completed for hazardous equipment types
  4. Estimate provided and agreed"
  (:require [clojure.string :as str]))

(defn client-verified?
  "Has the client record been verified in the shop system?
  Ground truth: client must have an ID, name, and contact on file."
  [client]
  (and client
       (not (str/blank? (:id client)))
       (not (str/blank? (:name client)))
       (not (str/blank? (:contact client)))))

(defn equipment-registered?
  "Has the equipment record been registered with repair history?
  Ground truth: equipment must have an ID, model, and equipment-type."
  [equipment]
  (and equipment
       (not (str/blank? (:id equipment)))
       (not (str/blank? (:model equipment)))
       (keyword? (:equipment-type equipment))))

(defn safety-checklist-required?
  "Does this equipment type require a safety checklist?
  Hazardous types: high-voltage electronics, radioactive calibration
  equipment, CRT/plasma displays, laser-based systems."
  [equipment-type]
  (contains? #{:high-voltage :radioactive :crt :laser} equipment-type))

(defn safety-checklist-complete?
  "Has the safety checklist been completed for hazardous equipment?"
  [checklist]
  (and checklist
       (boolean (:electrical-safety-check? checklist))
       (boolean (:hazmat-assessment? checklist))
       (boolean (:technician-certification-verified? checklist))))

(defn estimate-provided?
  "Has a repair estimate been created and documented?"
  [estimate]
  (and estimate
       (number? (:labor-hours estimate))
       (> (:labor-hours estimate) 0)
       (number? (:parts-cost estimate))
       (>= (:parts-cost estimate) 0)
       (not (str/blank? (:description estimate)))))

(defn required-evidence-satisfied?
  "For intake, all required evidence must be on file:
  client verified, equipment registered, safety checklist (if needed),
  and estimate provided."
  [equipment-type client equipment checklist estimate]
  (and (client-verified? client)
       (equipment-registered? equipment)
       (or (not (safety-checklist-required? equipment-type))
           (safety-checklist-complete? checklist))
       (estimate-provided? estimate)))
