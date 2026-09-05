(ns electronicrepair.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2: this repo previously had NO demo
  page and no generator at all. This namespace drives the REAL actor
  stack (`electronicrepair.operation` -> `electronicrepair.governor` ->
  `electronicrepair.store`) and renders the resulting store + append-only
  ledger. Nothing on the page is typed by hand except the `action-gate-rows`
  block, which is a static description of this actor's own fixed op
  contract (documentation-of-code, marked as such below).

  INPUT PROVENANCE. Every subject id below already exists in this repo's
  own reference seed, `electronicrepair.sim/seed` (this repo keeps its
  seed in `sim.cljc`, not in `store.cljc` -- there is no
  `store/seed-db`/`store/demo-data` here; `store/mem-store` takes an
  `:initial` map). That var was `^:private` and is made public by this
  change so the console renders the SAME reference data the repo's own
  `clojure -M:dev:run` demo does, rather than a second copy that could
  drift. Seeded ids used here, verbatim from `sim/seed`:

    \"C001\"    -- :clients            (verified: id + name + contact)
    \"E001\"    -- :equipment          (:office-equipment, no checklist required)
    \"E002\"    -- :equipment          (:crt -- hazardous; :safety-checklists has
                                        a complete checklist for it)
    \"INT-001\" -- :estimates          (labor 2.0h, parts 150.0, BOM 100.0+50.0
                                        = 150.0, so `registry/parts-cost-matches-claim?`
                                        is genuinely true)

  `\"C999\"` and `\"T002\"` appear as a client-id / technician-id INSIDE a
  proposal, never as a subject: `C999` is deliberately a client that is
  NOT on file, which is exactly what the governor's
  `client-verification-violations` check exists to catch (same device the
  repo's own sim uses for its hard-hold path).

  WHICH SCENARIO EXERCISES WHAT (each is one real `langgraph.graph/run*`):

    A. INT-001 / E001 -- ONE FULL CLEAN LIFECYCLE, four ops:
       a1 `:intake-repair-order`          governor-clean, low stakes  -> commit
       a2 `:schedule-technician-dispatch` estimate on file, first     -> commit
                                          dispatch (flips :dispatched?)
       a3 `:order-parts`                  claimed 150.0 == recomputed -> commit
                                          BOM 150.0 (flips :parts-ordered?)
       a4 `:complete-repair`              ALWAYS escalates (stake
                                          :actuation/complete-repair is in
                                          `governor/high-stakes`; `phase/
                                          phase-for-op` says :human-only at
                                          EVERY phase) -> human approves
                                          -> commit (flips :completed?)

    B. THREE HARD HOLDS, three DISTINCT governor rules, none reaching a human:
       b1 `:schedule-technician-dispatch` INT-001 a second time
                                          -> :already-dispatched
       b2 `:complete-repair`              INT-001 a second time
                                          -> :already-completed  (note: this
                                          beats the always-escalate rule --
                                          `:decide` tests `:hard?` first, so
                                          no approver is ever asked)
       b3 `:intake-repair-order`          E002 (CRT, checklist complete) but
                                          for client C999, not on file
                                          -> :client-not-verified

    C. HUMAN GATE, NEGATIVE PATH:
       c1 `:flag-safety-concern`          INT-001, stake :actuation/escalation
                                          -> always escalates -> human REJECTS
                                          -> :approval-rejected

  LEDGER FACT TYPES ACTUALLY APPENDED. `store/append-ledger!` is called
  only from `operation`'s `:commit` node (`:committed`) and `:hold` node
  (which re-appends whichever of `:governor-hold` / `:approval-rejected`
  is last in the run's audit channel). `:approval-granted` and
  `:approval-requested` go ONLY to the in-memory `:audit` channel and are
  never persisted -- so this renderer branches on exactly
  `#{:committed :governor-hold :approval-rejected}` and nothing else.

  DETERMINISM. No timestamps, no random ids, no wall-clock. Store maps
  are iterated in sorted-key order; the ledger is rendered in append
  order. Two consecutive runs are byte-identical (verify by diffing).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [electronicrepair.facts :as facts]
            [electronicrepair.operation :as operation]
            [electronicrepair.registry :as registry]
            [electronicrepair.sim :as sim]
            [electronicrepair.store :as store]))

(def ^:private coordinator
  "Same operator identity the repo's own `sim` demo runs under."
  {:actor-id "shop-dispatcher-01" :role :shop-coordinator})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "shop-dispatcher-01"}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "shop-dispatcher-01"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a freshly seeded store (`sim/seed`) through the scenario
  documented in this namespace's docstring: one full clean INT-001
  lifecycle (intake -> dispatch -> parts order -> approved repair
  completion), three HARD holds on three distinct governor rules
  (`:already-dispatched`, `:already-completed`, `:client-not-verified`),
  and one always-escalating safety flag that the human REJECTS. Returns
  the resulting `Store` -- every value `render` reads below is real
  governor/store output."
  []
  (let [s (store/mem-store {:initial sim/seed})
        actor (operation/build s)]

    ;; --- A. full clean lifecycle on the seeded INT-001 estimate ---------
    (exec! actor "a1-intake"
           {:op :intake-repair-order :subject "E001"
            :client-id "C001" :equipment-id "E001"
            :shop-code "SHOP1" :intake-sequence 1 :confidence 0.9})

    (exec! actor "a2-dispatch"
           {:op :schedule-technician-dispatch :subject "INT-001"
            :intake-id "INT-001" :client-id "C001" :equipment-id "E001"
            :technician-id "T001" :shop-code "SHOP1"
            :dispatch-sequence 1 :confidence 0.9})

    (exec! actor "a3-parts"
           {:op :order-parts :subject "INT-001"
            :intake-id "INT-001" :client-id "C001" :equipment-id "E001"
            :shop-code "SHOP1" :order-sequence 1 :confidence 0.9})

    (exec! actor "a4-complete"
           {:op :complete-repair :subject "INT-001"
            :intake-id "INT-001" :technician-id "T001"
            :notes "Power supply replaced, unit tested and recalibrated"
            :confidence 0.9})
    (approve! actor "a4-complete")

    ;; --- B. three HARD holds, three distinct rules ----------------------
    (exec! actor "b1-redispatch"
           {:op :schedule-technician-dispatch :subject "INT-001"
            :intake-id "INT-001" :client-id "C001" :equipment-id "E001"
            :technician-id "T002" :shop-code "SHOP1"
            :dispatch-sequence 2 :confidence 0.9})

    (exec! actor "b2-recomplete"
           {:op :complete-repair :subject "INT-001"
            :intake-id "INT-001" :technician-id "T002"
            :notes "Duplicate sign-off attempt"
            :confidence 0.9})

    (exec! actor "b3-intake-unverified"
           {:op :intake-repair-order :subject "E002"
            :client-id "C999" :equipment-id "E002"
            :shop-code "SHOP1" :intake-sequence 2 :confidence 0.9})

    ;; --- C. always-escalate op, human rejects ---------------------------
    (exec! actor "c1-safety"
           {:op :flag-safety-concern :subject "INT-001"
            :intake-id "INT-001" :concern-type :electrical
            :description "Possible loose grounding wire near power supply"
            :confidence 0.8})
    (reject! actor "c1-safety")

    s))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw
  "Bare name of a keyword, escaped."
  [v]
  (esc (if (keyword? v) (name v) v)))

(defn- qkw
  "FULL keyword including its namespace -- `:actuation/complete-repair`
  must not render as `complete-repair`, which would be indistinguishable
  from the op of the same name."
  [v]
  (esc (if (keyword? v) (subs (str v) 1) v)))

(defn- plural [n one many]
  (str n " " (if (= 1 n) one many)))

(defn- facts-for
  "All ledger facts whose `:subject` is `id`, in append order."
  [ledger id]
  (filter #(= id (:subject %)) ledger))

(defn- committed-cell
  "Ops that actually committed for this subject, read off the ledger."
  [ledger id]
  (let [ops (map :op (filter #(= :committed (:t %)) (facts-for ledger id)))]
    (if (seq ops)
      (str "<span class=\"ok\">"
           (str/join ", " (map #(str "<code>" (kw %) "</code>") ops))
           "</span>")
      "<span class=\"muted\">none</span>")))

(defn- held-cell
  "Governor HARD holds (and human rejections) for this subject, read off
  the ledger. `:governor-hold` is the governor refusing outright -- it
  never reaches a human; `:approval-rejected` is a human declining an
  escalation. They are different things, so they are labelled
  differently."
  [ledger id]
  (let [fs      (facts-for ledger id)
        holds   (filter #(= :governor-hold (:t %)) fs)
        rejects (filter #(= :approval-rejected (:t %)) fs)
        rules   (fn [coll] (str/join ", " (map kw (distinct (mapcat :basis coll)))))]
    (cond
      (and (seq holds) (seq rejects))
      (str "<span class=\"critical\">HARD hold &middot; " (rules holds) "</span>"
           " <span class=\"warn\">/ approval rejected</span>")

      (seq holds)
      (str "<span class=\"critical\">HARD hold &middot; " (rules holds) "</span>")

      (seq rejects)
      (str "<span class=\"warn\">approval rejected &middot; " (rules rejects) "</span>")

      :else "<span class=\"ok\">none</span>")))

(defn- lifecycle-cell
  "Repair lifecycle state, read from the store's own flags -- the very
  flags `operation`'s `:commit` node flips via `store/mark-dispatched!` /
  `mark-parts-ordered!` / `mark-completed!`."
  [snap intake-id]
  (let [d? (store/intake-already-dispatched? snap intake-id)
        p? (store/parts-already-ordered? snap intake-id)
        c? (store/repair-already-completed? snap intake-id)
        mark (fn [on? label]
               (if on?
                 (str "<span class=\"ok\">" label "</span>")
                 (str "<span class=\"muted\">" label "</span>")))]
    (str (mark d? "dispatched") " &rarr; " (mark p? "parts ordered")
         " &rarr; " (mark c? "completed"))))

(defn- estimate-row [snap ledger [intake-id est]]
  (let [claimed  (:total-parts-cost est)
        computed (registry/compute-total-parts-cost (:parts est))
        match?   (registry/parts-cost-matches-claim? est)]
    (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
                 "<td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>")
            (esc intake-id)
            (esc (:description est))
            (str (esc (:labor-hours est)) " h / "
                 (esc (:parts-cost est))
                 (if (facts/estimate-provided? est)
                   " <span class=\"ok\">documented</span>"
                   " <span class=\"critical\">incomplete</span>"))
            (str (esc claimed) " vs " (esc computed) " "
                 (if match?
                   "<span class=\"ok\">match</span>"
                   "<span class=\"critical\">mismatch</span>"))
            (lifecycle-cell snap intake-id)
            (committed-cell ledger intake-id)
            (held-cell ledger intake-id))))

(defn- equipment-row [snap ledger [equipment-id eq]]
  (let [etype     (:equipment-type eq)
        required? (facts/safety-checklist-required? etype)
        checklist (store/checklist-for-equipment snap equipment-id)
        complete? (facts/safety-checklist-complete? checklist)]
    (format (str "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td>"
                 "<td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>")
            (esc equipment-id)
            (esc (:model eq))
            (kw etype)
            (if (facts/equipment-registered? eq)
              "<span class=\"ok\">registered</span>"
              "<span class=\"critical\">not registered</span>")
            (cond
              (not required?) "<span class=\"muted\">not required</span>"
              complete?       "<span class=\"ok\">complete</span>"
              :else           "<span class=\"critical\">incomplete</span>")
            (committed-cell ledger equipment-id)
            (held-cell ledger equipment-id))))

(defn- client-ops-cell
  "Ops that committed *on behalf of* this client. A client is never
  itself the `:subject` of an op (subjects are equipment and intake
  ids), so this reads the committed fact's own `:record` -- which is
  the proposal `:value` the governor cleared, carrying `:client-id`."
  [ledger client-id]
  (let [ops (->> ledger
                 (filter #(and (= :committed (:t %))
                               (= client-id (get-in % [:record :client-id]))))
                 (map :op))]
    (if (seq ops)
      (str "<span class=\"ok\">"
           (str/join ", " (map #(str "<code>" (kw %) "</code>") ops))
           "</span>")
      "<span class=\"muted\">none</span>")))

(defn- client-row [ledger [client-id c]]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc client-id)
          (esc (:name c))
          (esc (:contact c))
          (if (facts/client-verified? c)
            "<span class=\"ok\">verified</span>"
            "<span class=\"critical\">not verified</span>")
          (client-ops-cell ledger client-id)))

(defn- hold-detail-rows
  "One row per governor violation actually recorded this run -- the
  `:detail` string is written by `electronicrepair.governor` itself."
  [ledger]
  (for [f ledger
        :when (= :governor-hold (:t f))
        v (:violations f)]
    (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td>"
                 "<td><span class=\"critical\">HARD hold &middot; %s</span></td><td>%s</td></tr>")
            (kw (:op f)) (esc (:subject f)) (kw (:rule v)) (esc (:detail v)))))

(defn- ledger-row [i {:keys [t op subject actor basis stake]}]
  (format (str "        <tr><td class=\"num\">%s</td><td>%s</td><td><code>%s</code></td>"
               "<td><code>%s</code></td><td>%s</td><td>%s</td></tr>")
          (inc i)
          (case t
            :committed         "<span class=\"ok\">committed</span>"
            :governor-hold     "<span class=\"critical\">governor-hold</span>"
            :approval-rejected "<span class=\"warn\">approval-rejected</span>"
            ;; `store/append-ledger!` is only ever called with the three
            ;; above; anything else would be a real change in
            ;; `operation.cljc` and should be visible, not hidden.
            (str "<span class=\"muted\">" (kw t) "</span>"))
          (kw op) (esc subject) (esc actor)
          (if (seq basis)
            (str/join ", " (map #(str "<code>" (kw %) "</code>") basis))
            (if stake (str "<code>" (qkw stake) "</code>") "&mdash;"))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract, read off
  ;; `electronicrepair.advisor` (stakes), `electronicrepair.governor`
  ;; (which check applies to which op, `high-stakes`) and
  ;; `electronicrepair.phase` (`phase-for-op` / `phase-allows-commit?`).
  ;; This is documentation of fixed code, NOT runtime telemetry -- it is
  ;; the only hand-written content on this page.
  ["        <tr><td><code>:intake-repair-order</code></td><td><span class=\"ok\">phase &ge; 2 auto-commit when clean</span></td><td>client verified &middot; equipment registered &middot; safety checklist complete for hazardous types</td></tr>"
   "        <tr><td><code>:schedule-technician-dispatch</code></td><td><span class=\"ok\">phase &ge; 2 auto-commit when clean</span></td><td>client verified &middot; equipment registered &middot; estimate documented &middot; not already dispatched</td></tr>"
   "        <tr><td><code>:order-parts</code></td><td><span class=\"ok\">phase &ge; 2 auto-commit when clean</span></td><td>client verified &middot; equipment registered &middot; claimed parts total re-summed from the BOM</td></tr>"
   "        <tr><td><code>:complete-repair</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase</span></td><td>stake <code>:actuation/complete-repair</code> &middot; refuses a second sign-off outright</td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase</span></td><td>stake <code>:actuation/escalation</code> &middot; intrinsically an escalation, not a check</td></tr>"])

(defn render
  "Renders the full operator-console.html document from a `Store` that
  has already been driven by `run-demo!` (or any other real scenario)."
  [s]
  (let [snap    (store/snapshot s)
        ledger  (vec (store/ledger s))
        n-of    (fn [t] (count (filter #(= t (:t %)) ledger)))
        est-rows (str/join "\n" (map (partial estimate-row snap ledger)
                                     (sort-by key (:estimates snap))))
        eq-rows  (str/join "\n" (map (partial equipment-row snap ledger)
                                     (sort-by key (:equipment snap))))
        cl-rows  (str/join "\n" (map (partial client-row ledger)
                                     (sort-by key (:clients snap))))
        hold-rows (str/join "\n" (hold-detail-rows ledger))
        led-rows (str/join "\n" (map-indexed ledger-row ledger))]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<title>cloud-itonami-isic-3313 &middot; electronic &amp; optical equipment repair &mdash; Operator Console</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Electronic &amp; optical equipment repair (ISIC 3313) &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; repair completion and safety escalation are always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>This run</h2>\n"
     "    <p class=\"muted\">Build-time-generated from <code>electronicrepair.store</code> by <code>electronicrepair.render-html</code> (<code>clojure -M:dev:render-html</code>), driving the real <code>electronicrepair.operation</code> StateGraph through <code>electronicrepair.governor</code>. Seed data is this repo's own <code>electronicrepair.sim/seed</code> &mdash; the same reference shop <code>clojure -M:dev:run</code> uses.</p>\n"
     "    <p>" (plural (count ledger) "audit fact" "audit facts") " &middot; "
     "<span class=\"ok\">" (n-of :committed) " committed</span> &middot; "
     "<span class=\"critical\">" (plural (n-of :governor-hold) "governor HARD hold" "governor HARD holds") "</span> &middot; "
     "<span class=\"warn\">" (plural (n-of :approval-rejected) "human rejection" "human rejections") "</span></p>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Repair work items (intakes with a documented estimate)</h2>\n"
     "    <p class=\"muted\">Claimed parts totals are never trusted: the governor re-sums the estimate's own bill of materials (<code>registry/compute-total-parts-cost</code>) and blocks <code>:order-parts</code> on any mismatch.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Intake</th><th>Repair</th><th>Estimate (labor / parts)</th><th>Parts total: claimed vs re-summed</th><th>Lifecycle</th><th>Committed ops</th><th>Held</th></tr></thead>\n"
     "      <tbody>\n" est-rows "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Equipment on file</h2>\n"
     "    <p class=\"muted\">Hazardous equipment types (<code>:high-voltage</code>, <code>:radioactive</code>, <code>:crt</code>, <code>:laser</code>) cannot be taken in for repair until the mandatory safety checklist is complete and signed.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Equipment</th><th>Model</th><th>Type</th><th>Registration</th><th>Safety checklist</th><th>Committed ops</th><th>Held</th></tr></thead>\n"
     "      <tbody>\n" eq-rows "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Clients on file</h2>\n"
     "    <p class=\"muted\">A client record counts as verified only with an id, a name and a contact on file. Proposals naming a client that is not on file are refused outright &mdash; no customer record is ever invented.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Client</th><th>Name</th><th>Contact</th><th>Verification</th><th>Committed ops naming this client</th></tr></thead>\n"
     "      <tbody>\n" cl-rows "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Electronic Repair Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by an approver &mdash; the graph tests them before it ever asks a human, so a duplicate repair sign-off never reaches an approval queue at all.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th><th>Independent checks</th></tr></thead>\n"
     "      <tbody>\n" (str/join "\n" action-gate-rows) "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Governor HARD holds (this run)</h2>\n"
     "    <p class=\"muted\">Every row below was produced by <code>electronicrepair.governor/check</code> during this run; the reason text is the governor's own.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Subject</th><th>Rule</th><th>Governor's reason</th></tr></thead>\n"
     "      <tbody>\n" hold-rows "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision log, in append order. Only committed proposals, governor holds and human rejections are persisted &mdash; an approval grant is not itself a fact, the commit it unblocks is.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>#</th><th>Fact</th><th>Op</th><th>Subject</th><th>Actor</th><th>Basis / stake</th></tr></thead>\n"
     "      <tbody>\n" led-rows "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer class=\"footer\"><p class=\"muted\">Generated from a real actor run &mdash; no fabricated ids, statuses or ledger rows. Regenerate with <code>clojure -M:dev:render-html</code>.</p></footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        s (run-demo!)
        ledger (store/ledger s)
        html (render s)]
    (spit out html)
    (println "wrote" out "("
             (count ledger) "ledger facts,"
             (count (filter #(= :committed (:t %)) ledger)) "committed,"
             (count (filter #(= :governor-hold (:t %)) ledger)) "governor holds,"
             (count (filter #(= :approval-rejected (:t %)) ledger)) "human rejections )")))
