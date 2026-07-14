(ns electronicrepair.store
  "State management for the electronic repair coordination actor.
  Encapsulates access to client records, equipment records, intakes,
  dispatches, and repair history.")

(defn client
  "Retrieve a client record by ID from the immutable state store."
  [store client-id]
  (get-in store [:clients client-id]))

(defn equipment
  "Retrieve an equipment record by ID from the immutable state store."
  [store equipment-id]
  (get-in store [:equipment equipment-id]))

(defn intake-record
  "Retrieve an intake record by ID from the immutable state store."
  [store intake-id]
  (get-in store [:intakes intake-id]))

(defn dispatch-record
  "Retrieve a dispatch record by ID from the immutable state store."
  [store dispatch-id]
  (get-in store [:dispatches dispatch-id]))

(defn estimate-for-intake
  "Retrieve the repair estimate associated with an intake."
  [store intake-id]
  (get-in store [:estimates intake-id]))

(defn checklist-for-equipment
  "Retrieve the safety checklist for an equipment record."
  [store equipment-id]
  (get-in store [:safety-checklists equipment-id]))

(defn intake-already-dispatched?
  "Has this intake already been dispatched to a technician?"
  [store intake-id]
  (boolean (get-in store [:dispatched? intake-id])))

(defn repair-already-completed?
  "Has this intake's repair already been marked complete and signed off
  by the technician? (Tech sign-off is the ONLY authority.)"
  [store intake-id]
  (boolean (get-in store [:completed? intake-id])))

(defn parts-already-ordered?
  "Has a parts order already been placed for this intake?"
  [store intake-id]
  (boolean (get-in store [:parts-ordered? intake-id])))

(defn all-intakes
  "Retrieve all intake IDs (for searching/listing)."
  [store]
  (keys (:intakes store)))

(defn all-dispatches
  "Retrieve all dispatch records (for technician scheduling)."
  [store]
  (vals (:dispatches store)))
