(ns electronicrepair.store
  "State management for the electronic repair coordination actor.
  Encapsulates access to client records, equipment records, intakes,
  dispatches, and repair history.

  Two layers:

    1. PURE VALUE HELPERS (`client` / `equipment` / `intake-record` /
       `dispatch-record` / `estimate-for-intake` /
       `checklist-for-equipment` / `intake-already-dispatched?` /
       `repair-already-completed?` / `parts-already-ordered?` /
       `all-intakes` / `all-dispatches`) -- plain functions over an
       immutable `{:clients ... :equipment ... :estimates ...
       :safety-checklists ... :dispatched? ... :completed? ...
       :parts-ordered? ...}` value. `electronicrepair.governor`'s
       independent checks call these directly against whatever snapshot
       they're handed -- this is this actor's original seam and stays
       unchanged so the Governor never has to care whether it's looking
       at a plain map or a live `Store`.

    2. `Store` PROTOCOL -- the backend seam every other cloud-itonami
       actor in this fleet uses (mirrors `pastaops.store`,
       cloud-itonami-isic-1074): `MemStore` (atom, deterministic default
       for dev/tests/demo). Holds the SAME plain-map shape the pure
       helpers above expect (`snapshot` returns it) plus an append-only
       audit ledger (`ledger`/`append-ledger!`) -- this actor's core
       missing plumbing until now (PRIOR GAP: this store had no
       mutation/ledger capability at all -- there was no
       `operation.cljc` graph, no commit/hold path, nothing to append
       to). `electronicrepair.operation`'s `:commit`/`:hold` graph nodes
       append every committed/held/approval-rejected decision fact
       here, so an intake's full operating history is always a query
       over an immutable log.")

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

;; ----------------------------- Store protocol -----------------------------

(defprotocol Store
  (snapshot [store]
    "The current plain-map store value -- the exact shape the pure
    helpers above and `electronicrepair.governor/check` expect. Pass
    this straight to `governor/check`.")
  (mark-dispatched! [store intake-id]
    "Flip `:dispatched?` true for `intake-id`. Used by the `:commit`
    graph node for `:schedule-technician-dispatch`.")
  (mark-completed! [store intake-id]
    "Flip `:completed?` true for `intake-id`. Used by the `:commit`
    graph node for `:complete-repair`.")
  (mark-parts-ordered! [store intake-id]
    "Flip `:parts-ordered?` true for `intake-id`. Used by the `:commit`
    graph node for `:order-parts`.")
  (ledger [store]
    "The append-only audit ledger: every committed/held/
    approval-rejected decision fact, in append order.")
  (append-ledger! [store fact]
    "Append one immutable decision fact to the ledger. Returns fact.
    THIS is the call that did not exist anywhere in this repo before
    this fix -- there was no `operation.cljc` graph, so nothing ever
    had a real commit/hold path to call it from."))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [state-atom ledger-atom]
  Store
  (snapshot [_store] @state-atom)
  (mark-dispatched! [_store intake-id]
    (swap! state-atom assoc-in [:dispatched? intake-id] true)
    nil)
  (mark-completed! [_store intake-id]
    (swap! state-atom assoc-in [:completed? intake-id] true)
    nil)
  (mark-parts-ordered! [_store intake-id]
    (swap! state-atom assoc-in [:parts-ordered? intake-id] true)
    nil)
  (ledger [_store] @ledger-atom)
  (append-ledger! [_store fact]
    (swap! ledger-atom conj fact)
    fact))

(defn mem-store
  "Create an in-memory store. `initial` is an optional plain-map value
  merged over the empty default shape (pre-commit data: `:clients`,
  `:equipment`, `:intakes`, `:dispatches`, `:estimates`,
  `:safety-checklists`, as staged via test/demo/telemetry onboarding
  BEFORE any proposal is made -- same shape `governor/check`,
  `snapshot`, and the pure helpers above all expect)."
  [& [{:keys [initial] :or {initial {}}}]]
  (MemStore.
   (atom (merge {:clients {} :equipment {} :intakes {} :dispatches {}
                 :estimates {} :safety-checklists {}
                 :dispatched? {} :completed? {} :parts-ordered? {}}
                initial))
   (atom [])))
