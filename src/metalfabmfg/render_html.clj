(ns metalfabmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`
  (flagship checklist item 2).

  This namespace does NOT hand-write a console. It drives the REAL actor
  stack of this repo -- `metalfabmfg.operation/build` (a langgraph-clj
  StateGraph: :intake -> :advise -> :govern -> :decide -> :commit |
  :request-approval | :hold) over `metalfabmfg.advisor`,
  `metalfabmfg.governor`, `metalfabmfg.phase` and `metalfabmfg.store` --
  and renders whatever that run actually produced.

  Everything on the page is runtime output of this repo's own code:
    - batch / equipment tables      `store/all-batches`, `store/all-equipment`
                                    AFTER the run (so `batch-001`'s
                                    `:shipped-weight-kg` shows the weight the
                                    one committed shipment actually added, and
                                    `press-001`'s
                                    `:last-scheduled-maintenance-date` shows
                                    the date the committed maintenance wrote)
    - HARD-hold table / audit ledger `store/ledger` -- the governor's own
                                    `:rule` keywords and its own Japanese
                                    `:detail` strings, verbatim
    - draft record ids              `store/maintenance-history` /
                                    `store/shipment-history`
                                    (`metalfabmfg.registry` built them:
                                    MNT-nnnnnn / SHP-nnnnnn)
    - safety-concern log            `store/safety-concerns`
    - policy tables                 read off the live vars
                                    `governor/allowed-ops`,
                                    `governor/allowed-proposal-effects`,
                                    `governor/high-stakes`,
                                    `governor/confidence-floor`,
                                    `phase/phases`,
                                    `registry/valid-product-categories`,
                                    `registry/valid-process-types`,
                                    `registry/defect-rate-*-percent`
                                    -- derived, so the page self-corrects
                                    when the policy changes
    - approver attribution          MEASURED at render time by walking the
                                    store and the run state for any
                                    approver-bearing key (see
                                    `approver-attribution`), never asserted.

  The seed ids (`batch-001`/`batch-002`/`batch-003`/`press-001`/
  `wireform-002`) are `metalfabmfg.store/sample-data!`'s own; no entity,
  id or number on this page was invented here.

  DETERMINISTIC: no clock read, no random, no map-iteration order relied
  on (every collection is either an append-ordered log or explicitly
  sorted). Two runs are byte-identical.

  REFUSES TO LIE: `-main` throws and writes nothing when the run put no
  HARD governor hold on the ledger -- a console showing no real hold
  would be indistinguishable from a hand-written mock.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [metalfabmfg.governor :as governor]
            [metalfabmfg.operation :as op]
            [metalfabmfg.phase :as phase]
            [metalfabmfg.registry :as registry]
            [metalfabmfg.store :as store]))

;; ============================ the real run ============================

(def ^:private coordinator
  "Phase-3 shop coordinator -- the same context `metalfabmfg.sim` uses."
  {:actor-id "coord-1" :actor-role :shop-coordinator :phase 3})

(def ^:private phase-1-coordinator
  "The SAME coordinator earlier in the staged rollout. Used once, to show
  that the phase gate (`metalfabmfg.phase/gate`) holds a write the
  governor itself was happy with -- a second, independent layer."
  {:actor-id "coord-1" :actor-role :shop-coordinator :phase 1})

(defn run-demo!
  "Seeds a fresh MemStore with `store/sample-data!` and drives the real
  actor through every disposition it can reach. Returns
  `{:db <store> :runs [<run entry> ...]}` where each run entry keeps the
  request it sent, the graph state it got back, and (for the runs that
  paused at `interrupt-before #{:request-approval}`) the state after a
  human resumed it.

  COMMITTED
    t01 `batch-001` production-batch logging -- governor-clean, low
        stakes, and `:log-production-batch` is the ONE member of phase
        3's `:auto` set, so it auto-commits with no human.
    t02 `mnt-1` maintenance on the verified+registered stamping press
        `press-001` -- governor-clean, but `:schedule-maintenance` is
        absent from EVERY phase's `:auto` set, so the phase gate
        escalates (`:phase-approval`); a human approves.
    t03 `concern-1` safety-concern flag -- ALWAYS escalates
        (`:coordination/safety-concern` ∈ `governor/high-stakes`),
        approved.
    t04 `ship-1`, 3000.0 kg off `batch-001`, carrying a COMPLETE
        optional `:handoff` to the downstream pressure-equipment
        manufacturer cloud-itonami-isic-2813 -- escalates, approved.
        This is the twelfth (handoff) check PASSING.

  ESCALATED AND REFUSED BY THE HUMAN (soft, reached a person)
    t05 `ship-5`, 9000.0 kg off `batch-001` -- governor-clean and within
        the batch's remaining weight, escalated, and the human shipping
        approver REJECTS it (`:approval-rejected`). Not a HARD hold: the
        contrast is the point.

  HARD HELD (never reaches a human -- the `:hold` branch never routes
  through `:request-approval`)
    t06 `:actuate-press-line? true`             -> :press-line-actuate-blocked
    t07 unverified `wireform-002`               -> :equipment-not-verified
    t08 unverified `batch-003`                  -> :batch-not-verified
    t09 1000.0 kg on `batch-002` (5600.0/6000.0 already shipped)
                                                -> :shipment-weight-exceeded (over)
    t10 a shipment stating NO weight at all     -> :shipment-weight-exceeded
        (un-checkable headroom is not headroom -- `registry/
        shipment-weight-exceeded-checkable?`)
    t11 `:handoff` present but missing `:handoff/source-actor`
                                                -> :handoff-incomplete
    t12 request `:effect :direct-write`         -> :not-propose-effect
    t13 op `:actuate-press-line`                -> :unknown-op AND
                                                   :press-line-control-blocked
    t14 `mnt-1` scheduled a second time         -> :already-scheduled
    t15 `:product-category :unobtainium-gadget` -> :invalid-product-category
    t16 `:defect-rate-percent 999.0`            -> :invalid-defect-rate
    t17 the same clean `press-001` maintenance, sent at PHASE 1
                                                -> phase gate `:phase-disabled`

  Order is load-bearing: t04 must commit before the tables are read (it
  is what moves `batch-001`'s own `:shipped-weight-kg`), and t02 must
  commit before t14 can be held as a double-schedule."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        runs (atom [])
        step! (fn [tid label context request]
                (let [r (g/run* actor {:request request :context context}
                                {:thread-id tid})]
                  (swap! runs conj {:tid tid :label label :context context
                                    :request request :state (:state r)})
                  r))
        resume! (fn [tid status by]
                  (let [r (g/run* actor {:approval {:status status :by by}}
                                  {:thread-id tid :resume? true})]
                    (swap! runs
                           (fn [rs]
                             (mapv #(if (= tid (:tid %))
                                      (assoc % :resumed {:status status :by by
                                                         :state (:state r)})
                                      %)
                                   rs)))
                    r))]

    ;; ---------------- committed ----------------
    (step! "t01" "生産バッチ記録 (phase-3 自動コミット)" coordinator
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-category :stamped-metal-part
                    :last-assessed "2026-07-14"}})

    (step! "t02" "プレス金型点検の保守枠 (人間承認)" coordinator
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "press-001" :maintenance-type :die-inspection
                    :scheduled-date "2026-08-01" :actuate-press-line? false}})
    (resume! "t02" :approved "supervisor-1")

    (step! "t03" "安全懸念の報告 (常に人間承認)" coordinator
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "press-001" :severity :moderate
                    :description "プレス周辺で挟まれ点(ピンチポイント)ガードの緩みを確認"}})
    (resume! "t03" :approved "supervisor-1")

    (step! "t04" "出荷調整 3000.0 kg + 完全な handoff (人間承認)" coordinator
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :weight-kg 3000.0
                    :destination "buyer-yard-north"
                    :handoff {:handoff/id "ho-2599-2813-0001"
                              :handoff/source-actor "cloud-itonami-isic-2599"
                              :handoff/batch-id "batch-001"}}})
    (resume! "t04" :approved "shipping-approver-1")

    ;; ---------------- escalated, then refused by the human ----------------
    (step! "t05" "出荷調整 9000.0 kg (人間が却下)" coordinator
           {:op :coordinate-shipment :effect :propose :subject "ship-5"
            :value {:batch-id "batch-001" :weight-kg 9000.0
                    :destination "buyer-yard-far"}})
    (resume! "t05" :rejected "shipping-approver-1")

    ;; ---------------- HARD holds ----------------
    (step! "t06" "プレスラインの直接操作(actuate)提案" coordinator
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "press-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-press-line? true}})

    (step! "t07" "未検証のワイヤー成形機への保守提案" coordinator
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "wireform-002" :maintenance-type :roller-inspection
                    :scheduled-date "2026-08-01" :actuate-press-line? false}})

    (step! "t08" "未検証バッチからの出荷提案" coordinator
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :weight-kg 1000.0
                    :destination "buyer-yard-south"}})

    (step! "t09" "生産量を超過する出荷提案" coordinator
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :weight-kg 1000.0
                    :destination "buyer-yard-east"}})

    (step! "t10" "申請量を書かない出荷提案 (検算不能)" coordinator
           {:op :coordinate-shipment :effect :propose :subject "ship-6"
            :value {:batch-id "batch-001" :destination "buyer-yard-unknown"}})

    (step! "t11" "handoff の必須フィールド欠落" coordinator
           {:op :coordinate-shipment :effect :propose :subject "ship-4"
            :value {:batch-id "batch-001" :weight-kg 500.0
                    :destination "buyer-yard-west"
                    :handoff {:handoff/id "ho-2599-2813-0002"
                              :handoff/batch-id "batch-001"}}})

    (step! "t12" "request :effect が :propose でない呼び出し" coordinator
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:product-category :stamped-metal-part}})

    (step! "t13" "許可リスト外の操作" coordinator
           {:op :actuate-press-line :effect :propose :subject "batch-001"})

    (step! "t14" "同じ保守枠の二重スケジュール" coordinator
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "press-001" :maintenance-type :die-inspection
                    :scheduled-date "2026-08-01" :actuate-press-line? false}})

    (step! "t15" "存在しない product-category" coordinator
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-category :unobtainium-gadget}})

    (step! "t16" "物理的にありえない不良率" coordinator
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:defect-rate-percent 999.0}})

    (step! "t17" "ガバナは通したが phase 1 では書けない操作" phase-1-coordinator
           {:op :schedule-maintenance :effect :propose :subject "mnt-4"
            :value {:equipment-id "press-001" :maintenance-type :die-inspection
                    :scheduled-date "2026-10-01" :actuate-press-line? false}})

    {:db db :runs @runs}))

;; ============================ derivations ============================

(defn hard-holds
  "The HARD governor holds on `ledger` -- the facts the `:hold` branch
  wrote, which by construction never passed through `:request-approval`.
  A human rejection is `:approval-rejected`, deliberately NOT counted
  here: it DID reach a person.

  Public on purpose: `-main`'s refuse-to-write gate is proven by
  redefining this."
  [ledger]
  (filterv #(= :governor-hold (:t %)) ledger))

(defn- commits [ledger] (filterv #(= :committed (:t %)) ledger))
(defn- rejections [ledger] (filterv #(= :approval-rejected (:t %)) ledger))

(defn- hold-reasons
  "Every distinct reason a hold fired, sorted. A hold carries either
  governor `:basis` rule keywords, or (when the governor was clean and
  the PHASE gate held it) a `:phase-reason`."
  [holds]
  (->> holds
       (mapcat (fn [h] (concat (:basis h)
                               (when-let [pr (:phase-reason h)] [pr]))))
       distinct
       (map name)
       sort
       vec))

(defn- final-disposition [{:keys [state resumed]}]
  (or (get-in resumed [:state :disposition]) (:disposition state)))

(defn- run-refs
  "Ids this run's request referenced (subject + the batch/equipment it
  named), so a batch/equipment row can report what happened to it."
  [{:keys [request]}]
  (into #{} (remove nil?)
        [(:subject request)
         (get-in request [:value :batch-id])
         (get-in request [:value :equipment-id])]))

(defn- disposition-tally
  "commit/hold/escalate counts for the runs that referenced `id`."
  [runs id]
  (let [ds (->> runs (filter #(contains? (run-refs %) id)) (map final-disposition))]
    {:commit (count (filter #(= :commit %) ds))
     :hold (count (filter #(= :hold %) ds))
     :escalate (count (filter #(= :escalate %) ds))}))

(defn- committed-shipments
  "The shipment records the store actually kept, found through the
  append-only draft history (the Store protocol exposes `shipment` by id
  but no `all-shipments`)."
  [db]
  (->> (store/shipment-history db)
       (keep #(get % "shipment_id"))
       sort
       (keep #(store/shipment db %))
       vec))

(def ^:private approver-keys
  "Keys that could plausibly carry a human approver's identity. Checked
  generically so the measurement below does not presuppose the answer."
  [:approved-by :approved_by :approver :by :signed-by])

(defn- approver-hits
  "Every [key value] pair under an approver-ish key anywhere inside `x`.
  A generic walk -- this is a MEASUREMENT, not an assertion about where
  the approver is supposed to land."
  [x]
  (let [acc (atom #{})]
    (walk/postwalk
     (fn [v]
       (when (map? v)
         (doseq [k approver-keys]
           (when-some [hit (get v k)]
             (swap! acc conj [(name k) (str hit)]))))
       v)
     x)
    (vec (sort @acc))))

(defn approver-attribution
  "Measures where (if anywhere) a human approver's id survives, by
  walking what actually exists after the run:

    :approvals   -- the approvals a human really gave in this run
    :in-graph    -- approver-bearing pairs in the resumed graph state
                    (the actor's own in-run record/audit)
    :in-store    -- approver-bearing pairs in every entity the SSoT kept
    :in-ledger   -- approver-bearing pairs on the append-only ledger

  Derived, never hard-coded: if `operation/commit-record` and
  `store/commit-record!` are later changed to retain the approver, this
  page reports the new truth on the next build without being edited."
  [{:keys [db runs]}]
  (let [approved (filterv #(= :approved (get-in % [:resumed :status])) runs)
        store-entities (concat (store/all-batches db)
                               (store/all-equipment db)
                               (store/all-maintenance db)
                               (committed-shipments db)
                               (store/safety-concerns db)
                               (vals (into (sorted-map) (store/get-records db))))]
    {:approvals (mapv (fn [r] {:tid (:tid r)
                               :op (get-in r [:request :op])
                               :subject (get-in r [:request :subject])
                               :by (get-in r [:resumed :by])})
                      approved)
     :in-graph (approver-hits (mapv #(get-in % [:resumed :state]) approved))
     :in-store (approver-hits (vec store-entities))
     :in-ledger (approver-hits (vec (store/ledger db)))}))

;; ============================ html helpers ============================

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (str ":" (name v)) (str v)))
(defn- code [v] (str "<code>" (esc (kw v)) "</code>"))
(defn- num [v] (str "<span class=\"num\">" (esc v) "</span>"))
(defn- yes-no [b]
  (if b "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>"))
(defn- muted [v] (str "<span class=\"muted\">" (esc v) "</span>"))

(defn- row [& cells] (str "        <tr><td>" (str/join "</td><td>" cells) "</td></tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- card [title lead & body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (when lead (str "    <p class=\"muted\">" lead "</p>\n"))
       (str/join body)
       "  </section>\n"))

(defn- sorted-kws [s] (->> s (map name) sort (map #(code (keyword %))) (str/join " ")))

;; ============================ sections ============================

(defn- summary-section [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        holds (hard-holds ledger)]
    (card
     "この run が実際に出した結果"
     (str "下の数値はすべて 1 回の実行の出力。ハンドコードされた行は 1 つも無い"
          "（起点は <code>metalfabmfg.store/sample-data!</code>、駆動は "
          "<code>metalfabmfg.operation/build</code> の StateGraph）。")
     (table ["指標" "件数" "出所"]
            [(row "調整リクエスト" (num (count runs)) (muted "run-demo! が actor に送った要求"))
             (row "コミット (SSoT 更新)" (num (count (commits ledger)))
                  (code :committed))
             (row "<strong>HARD hold（人間に届かない）</strong>"
                  (str "<span class=\"critical\">" (num (count holds)) "</span>")
                  (code :governor-hold))
             (row "人間が却下した提案" (num (count (rejections ledger)))
                  (code :approval-rejected))
             (row "人間承認を得たコミット"
                  (num (count (filter #(= :approved (get-in % [:resumed :status])) runs)))
                  (muted "interrupt-before #{:request-approval} を人間が再開"))
             (row "監査台帳の事実" (num (count ledger)) (code "store/ledger"))
             (row "保守枠ドラフト" (num (count (store/maintenance-history db)))
                  (code "store/maintenance-history"))
             (row "出荷ドラフト" (num (count (store/shipment-history db)))
                  (code "store/shipment-history"))
             (row "安全懸念ログ" (num (count (store/safety-concerns db)))
                  (code "store/safety-concerns"))
             (row "発火した hold の種別"
                  (num (count (hold-reasons holds)))
                  (muted (str/join " / " (hold-reasons holds))))]))))

(defn- batches-section [{:keys [db runs]}]
  (card
   "生産バッチ (run 後の SSoT)"
   (str "<code>store/all-batches</code>。<code>batch-001</code> の出荷済み重量と "
        "last-assessed は、この run が実際にコミットした出荷/記録更新の結果。")
   (table ["batch" "product-category" "process" "材料" "生産量 kg" "出荷済 kg"
           "残 kg" "不良率 %" "verified?" "registered?" "last-assessed" "この run の判定"]
          (for [{:keys [id product-category process-type material weight-kg
                        shipped-weight-kg defect-rate-percent last-assessed] :as b}
                (store/all-batches db)
                :let [t (disposition-tally runs id)]]
            (row (code id) (code product-category) (code process-type) (esc material)
                 (num weight-kg) (num shipped-weight-kg)
                 (num (- (double weight-kg) (double (or shipped-weight-kg 0.0))))
                 (num defect-rate-percent)
                 (yes-no (registry/batch-verified? b))
                 (yes-no (registry/batch-registered? b))
                 (esc last-assessed)
                 (str "commit " (num (:commit t)) " / hold " (num (:hold t))))))))

(defn- equipment-section [{:keys [db runs]}]
  (card
   "設備 (run 後の SSoT)"
   (str "<code>store/all-equipment</code>。<code>last-scheduled-maintenance-date</code> は "
        "この run で承認・コミットされた保守枠が書いた値。")
   (table ["equipment" "kind" "verified?" "registered?" "最終保守日"
           "予定された保守日" "この run の判定"]
          (for [{:keys [id kind last-maintenance-date last-scheduled-maintenance-date] :as e}
                (store/all-equipment db)
                :let [t (disposition-tally runs id)]]
            (row (code id) (code kind)
                 (yes-no (registry/equipment-verified? e))
                 (yes-no (registry/equipment-registered? e))
                 (if last-maintenance-date (esc last-maintenance-date) (muted "なし"))
                 (if last-scheduled-maintenance-date
                   (esc last-scheduled-maintenance-date) (muted "なし"))
                 (str "commit " (num (:commit t)) " / hold " (num (:hold t))))))))

(defn- holds-section [{:keys [db]}]
  (let [holds (hard-holds (vec (store/ledger db)))]
    (card
     (str "HARD hold — 人間に届かない拒否 (" (count holds) " 件)")
     (str "governor が HARD 違反を出した提案は <code>:hold</code> 枝へ行き、"
          "<code>:request-approval</code> を通らない。つまり誰も承認できない。"
          "rule と detail は <code>metalfabmfg.governor</code> が出した文字列そのもの。")
     (table ["op" "subject" "rule" "governor の detail" "confidence" "phase gate"]
            (for [{:keys [op subject basis violations confidence phase-reason phase]} holds]
              (row (code op) (code subject)
                   (if (seq basis)
                     (str/join " " (map #(str "<span class=\"critical\">" (esc (kw %)) "</span>") basis))
                     (muted "(governor は通した)"))
                   (if (seq violations)
                     (str/join "<br>" (map #(esc (:detail %)) violations))
                     (muted "—"))
                   (num confidence)
                   (if phase-reason
                     (str "<span class=\"critical\">" (esc (kw phase-reason)) "</span>"
                          " @ phase " (num phase))
                     (muted "—"))))))))

(defn- approval-section [{:keys [db runs]}]
  (let [paused (filter :resumed runs)]
    (card
     "人間承認キュー (interrupt-before)"
     (str "governor が HARD 違反を出さなかった提案のうち、phase gate または "
          "high-stakes 判定で人間に渡ったもの。actor はここで実際に停止し、"
          "人間の再開入力で先へ進む。却下は SSoT を一切変更しない。")
     (table ["run" "op" "subject" "停止時の判定" "人間の応答" "応答者" "最終判定"]
            (for [{:keys [tid label request state resumed]} paused]
              (row (str (code tid) " " (muted label))
                   (code (:op request)) (code (:subject request))
                   (code (:disposition state))
                   (if (= :approved (:status resumed))
                     (str "<span class=\"ok\">" (esc (kw (:status resumed))) "</span>")
                     (str "<span class=\"critical\">" (esc (kw (:status resumed))) "</span>"))
                   (code (:by resumed))
                   (code (get-in resumed [:state :disposition])))))
     (str "    <p class=\"muted\">この表の「応答者」は run の入力値。"
          "SSoT 側に残ったかどうかは下の「承認者はどこに残るか」で実測している。</p>\n")
     (table ["台帳に載った却下"]
            (for [{:keys [op subject basis]} (rejections (vec (store/ledger db)))]
              (row (str (code op) " " (code subject) " → "
                        (str/join " " (map #(str "<span class=\"critical\">" (esc (kw %)) "</span>")
                                           basis)))))))))

(defn- attribution-section [result]
  (let [{:keys [approvals in-graph in-store in-ledger]} (approver-attribution result)
        pairs->html (fn [ps]
                      (if (seq ps)
                        (str/join " " (map (fn [[k v]] (str "<code>:" (esc k) " = " (esc v) "</code>")) ps))
                        "<span class=\"critical\">見つからない</span>"))]
    (card
     "承認者はどこに残るか（実測）"
     (str "この repo の scaffold には、人間の承認者 id が SSoT へ落ちない系統の欠陥がある"
          "ことが fleet で観測されている。ここでは「ある/ない」を決め打ちせず、"
          "run 後の graph state・store の全エンティティ・台帳を "
          "<code>" (esc (str/join "/" (map name approver-keys))) "</code> "
          "の各キーで実際に走査した結果を出している。")
     (table ["承認された run" "op" "subject" "承認者として渡された id"]
            (for [{:keys [tid op subject by]} approvals]
              (row (code tid) (code op) (code subject) (code by))))
     (table ["走査した面" "見つかった承認者キー"]
            [(row "graph state (actor の run 内 record/audit)" (pairs->html in-graph))
             (row "store のエンティティ (batches/equipment/maintenance/shipments/concerns/records)"
                  (pairs->html in-store))
             (row "監査台帳 (store/ledger)" (pairs->html in-ledger))])
     (str "    <p>"
          (if (seq in-store)
            (str "<span class=\"ok\">承認者 id は SSoT のレコードに残っている。</span>")
            (str "<span class=\"critical\">承認者 id は SSoT のレコードに残っていない。</span> "
                 "<code>metalfabmfg.operation/commit-record</code> は承認者を "
                 "<code>:payload</code> に付けるが、<code>metalfabmfg.store/commit-record!</code> は "
                 "<code>:value</code> しか読まない。"
                 (if (seq in-ledger)
                   "台帳側には残っている。"
                   "台帳の <code>:committed</code> 事実にも承認者フィールドは無い。")
                 " したがって、コミット済みレコードだけを見た読み手には"
                 "「誰も承認していない」と「承認者が保存されなかった」の区別がつかない —— "
                 "この欠陥はこのページで隠さずに出す。修正は actor の SSoT 意味論の変更なので、"
                 "デモ生成のこのコミットでは行わない。"))
          "</p>\n"))))

(defn- drafts-section [{:keys [db]}]
  (card
   "ドラフト記録 / 安全懸念ログ"
   (str "<code>metalfabmfg.registry</code> が組んだ記録。番号は連番から実際に採番された値で、"
        "証明書は常に <code>draft-unsigned</code>（署名は人間の行為であって actor の行為ではない）。")
   (table ["record_id" "kind" "対象" "immutable"]
          (concat
           (for [r (store/maintenance-history db)]
             (row (code (get r "record_id")) (esc (get r "kind"))
                  (str (code (get r "maintenance_id")) " on " (code (get r "equipment_id")))
                  (yes-no (get r "immutable"))))
           (for [r (store/shipment-history db)]
             (row (code (get r "record_id")) (esc (get r "kind"))
                  (code (get r "shipment_id"))
                  (yes-no (get r "immutable"))))))
   (table ["安全懸念" "設備" "severity" "内容"]
          (for [{:keys [id equipment-id severity description]} (store/safety-concerns db)]
            (row (code id) (code equipment-id) (code severity) (esc description))))))

(defn- policy-section []
  (card
   "ガバナ方針と段階ロールアウト（コードから導出）"
   (str "この表はこの run の計測ではなく、<code>metalfabmfg.governor</code> と "
        "<code>metalfabmfg.phase</code> と <code>metalfabmfg.registry</code> の"
        "生きた var を読んで組み立てた固定契約。方針が変われば次のビルドでこの表も変わる。")
   (table ["方針" "値" "var"]
          [(row "許可された op" (sorted-kws governor/allowed-ops) (code "governor/allowed-ops"))
           (row "許可された proposal :effect" (sorted-kws governor/allowed-proposal-effects)
                (code "governor/allowed-proposal-effects"))
           (row "常に人間を要する stake" (sorted-kws governor/high-stakes)
                (code "governor/high-stakes"))
           (row "confidence 下限" (num governor/confidence-floor)
                (code "governor/confidence-floor"))
           (row "既知 product-category" (sorted-kws registry/valid-product-categories)
                (code "registry/valid-product-categories"))
           (row "既知 process-type" (sorted-kws registry/valid-process-types)
                (code "registry/valid-process-types"))
           (row "不良率の物理レンジ %"
                (str (num registry/defect-rate-min-percent) " – "
                     (num registry/defect-rate-max-percent))
                (code "registry/defect-rate-*-percent"))])
   (table ["phase" "label" "書き込み可能な op" "自動コミット可能な op"]
          (for [[p {:keys [label writes auto]}] (sort-by key phase/phases)]
            (row (num p) (esc label)
                 (if (seq writes) (sorted-kws writes) (muted "なし"))
                 (if (seq auto)
                   (str "<span class=\"ok\">" (sorted-kws auto) "</span>")
                   (muted "なし")))))
   (str "    <p class=\"muted\"><code>:schedule-maintenance</code> は phase 3 でも "
        "<code>:auto</code> に入っていない —— これはロールアウトの途中経過ではなく恒久的な構造。"
        "保守枠の確定は必ず人間の判断を通る。</p>\n")))

(defn- ledger-section [{:keys [db]}]
  (let [ledger (vec (store/ledger db))]
    (card
     (str "監査台帳（この run の全 " (count ledger) " 事実、追記順）")
     (str "<code>store/ledger</code> の全行。commit も hold も却下も同じ 1 本の"
          "追記専用ログに載る —— 「誰が何を止めたか」を後から問える形。")
     (table ["#" "fact" "op" "subject" "actor" "basis / summary"]
            (map-indexed
             (fn [i {:keys [t op subject actor basis summary]}]
               (row (num (inc i))
                    (case t
                      :committed (str "<span class=\"ok\">" (esc (kw t)) "</span>")
                      :governor-hold (str "<span class=\"critical\">" (esc (kw t)) "</span>")
                      :approval-rejected (str "<span class=\"warn\">" (esc (kw t)) "</span>")
                      (esc (kw t)))
                    (code op) (code subject) (code actor)
                    (if (seq basis)
                      (str/join " " (map #(code %) basis))
                      (esc (or summary "")))))
             ledger)))))

;; ============================ document ============================

(defn render
  "Pure: `{:db .. :runs ..}` (as returned by `run-demo!`) -> the full
  operator-console HTML string. No clock, no randomness, no reliance on
  map iteration order."
  [{:keys [db] :as result}]
  (let [ledger (vec (store/ledger db))
        holds (hard-holds ledger)]
    (str
     "<!doctype html>\n<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-2599 · 金属加工工場 運用コンソール</title>\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style>\n"
     "</head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>その他の金属製品製造業 n.e.c. (ISIC 2599) — 運用コンソール</h1>\n"
     "  <span class=\"badge\">read-only サンプル</span>\n"
     "  <span class=\"badge\">governor-gated</span>\n"
     "  <span class=\"badge\">HARD hold " (count holds) " 件</span>\n"
     "</header>\n"
     "<main>\n"
     "  <div class=\"banner\">\n"
     "    <p>このページは実行時に生成される。<code>clojure -M:dev:render-html</code> が "
     "<code>metalfabmfg.render-html/run-demo!</code> で実際の actor "
     "(<code>metalfabmfg.operation</code> の langgraph StateGraph → "
     "<code>metalfabmfg.governor</code> → <code>metalfabmfg.store</code>) を駆動し、"
     "その結果だけを描画する。HARD hold が 1 件も出なかった run では"
     "<strong>ファイルを書かずに例外を投げる</strong> —— 実際の拒否を示せないコンソールは"
     "手書きのモックと区別がつかないため。</p>\n"
     "  </div>\n"
     (summary-section result)
     (batches-section result)
     (equipment-section result)
     (holds-section result)
     (approval-section result)
     (attribution-section result)
     (drafts-section result)
     (policy-section)
     (ledger-section result)
     "</main>\n"
     "<footer>\n"
     "  <p>生成: <code>clojure -M:dev:render-html</code> "
     "(<code>metalfabmfg.render-html</code>)。"
     "シード: <code>metalfabmfg.store/sample-data!</code>。"
     "スタイル: <code>jp-go-dds.skin/dds+skin</code>（デジタル庁デザインシステム）。"
     "決定的 —— 同じシードなら 2 回の実行はバイト単位で一致する"
     "（タイムスタンプも乱数もページに載せていない）。</p>\n"
     "  <p>この actor は提案しか行わない。スタンピングプレス・成形ラインの直接操作、"
     "実際の保守作業の実施、実運送業者への配車は一切行わない。</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db] :as result} (run-demo!)
        hs (hard-holds (vec (store/ledger db)))]
    (when (empty? hs)
      (throw (ex-info "no governor hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (let [html (render result)]
      (io/make-parents (io/file out))
      (spit out html)
      (println "wrote" out
               (str "(" (count (store/ledger db)) " ledger facts, ")
               (str (count (filter #(= :committed (:t %)) (store/ledger db))) " commits, ")
               (str (count hs) " HARD holds, ")
               (str (count (hold-reasons hs)) " distinct hold reasons: "
                    (str/join "/" (hold-reasons hs)) ")")))))
