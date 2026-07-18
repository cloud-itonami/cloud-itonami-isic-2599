(ns metalfabmfg.registry
  "Pure-function domain logic for the metal-fabrication shop
  plant-operations coordination actor -- equipment/batch verification,
  shipment-weight recompute, product-category validation, defect-rate
  plausibility validation, and draft maintenance-schedule/shipment-
  coordination record construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/metalfabmfg`-style capability library to
  wrap (verified: no such repo exists). The domain logic therefore
  lives here as pure functions, re-verified INDEPENDENTLY by
  `metalfabmfg.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `foundrymfg.registry/shipment-weight-exceeded?` from
  `cloud-itonami-isic-2431`, the closest architectural sibling): never
  trust a proposal's own self-reported weight/status when the inputs
  needed to recompute it independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant-operations system. It builds the DRAFT record
  a shop coordinator would keep (a scheduled maintenance window, a
  coordinated shipment), not the act of actuating a stamping press or
  pressing/wire-forming line, or dispatching a real freight carrier
  (this actor NEVER does either -- see README `What this actor does
  NOT do`).

  SCOPE NOTE: ISIC 2599 (this actor) is the RESIDUAL 'other fabricated
  metal products n.e.c.' class -- a metal-fabrication shop (stamping
  press -> pressing line -> wire-forming line) that produces stamped/
  pressed sheet-metal parts, wire products (mesh, coil, formed wire
  goods) and metal household goods not elsewhere classified. This is
  distinct from `cloud-itonami-isic-2591` (Forging, pressing, stamping
  and roll-forming of metal -- the primary heavy metal-forming
  process), `cloud-itonami-isic-2592` (Treatment and coating of
  metals), and `cloud-itonami-isic-2593` (Manufacture of cutlery, hand
  tools and general hardware) -- distinct plants with distinct hazard
  profiles from this one. This actor's own hazard profile centers on
  sharp-edge/burr laceration risk from freshly stamped/pressed sheet
  metal, stamping-press pinch-point/crush hazard, wire-forming-machine
  entanglement hazard, and coating/lubricant/degreasing chemical
  exposure -- not molten-metal handling (2431/2410) or heavy roll-
  forming/forging (2591).")

;; ----------------------------- constants -----------------------------

(def valid-product-categories
  "The closed set of product-category values a production-batch (a
  stamped/pressed/wire-formed lot) record may declare -- the standard
  'other fabricated metal products n.e.c.' families this shop
  produces. Anything else is a fabricated/unrecognized product
  category -- the governor HARD-holds rather than let an invented
  category pass through."
  #{:stamped-metal-part :pressed-metal-part :wire-product
    :metal-household-good :metal-hardware-item :formed-sheet-metal-part})

(def valid-process-types
  "The closed set of PRIMARY PROCESS types this shop's own production
  line may take -- stamping, pressing, wire-forming, roll-forming, or
  deep-drawing. A metal-fabrication shop in this residual n.e.c. class
  never ships a raw casting or a forged/rolled primary-mill shape
  (those are a different actor's own upstream scope, not this
  actor's)."
  #{:stamping :pressing :wire-forming :roll-forming :deep-drawing})

(def defect-rate-min-percent
  "Physical floor for a batch's own defect/scrap-rate reading (zero
  defective output is the best possible outcome, never negative)."
  0.0)

(def defect-rate-max-percent
  "Physical ceiling for a batch's own defect/scrap-rate reading -- a
  batch cannot reject more than 100% of its own output. A reading
  above this is implausible sensor/QC data, not a real batch."
  100.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the shop's equipment
  registry)? Scheduling maintenance against equipment that is not on
  file and registered is the exact scope violation this actor's HARD
  invariant ('shop/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its product-category/weight/defect-rate claims have actually
  been QC-inspected, not merely logged from an unverified intake
  patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the shop's
  production ledger? Coordinating a shipment against a batch that is
  not on file and registered is the exact scope violation this actor's
  HARD invariant ('shop/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-weight-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal:
  would `shipped-to-date-kg` + `new-weight-kg` exceed `batch`'s own
  recorded `:weight-kg` (the batch's own logged production weight)?
  Needs no proposal inspection or stored-verdict lookup -- its inputs
  are permanent fields already on the batch's own record, the same
  shape every sibling actor's own cost/total-matching check uses."
  [batch new-weight-kg]
  (let [capacity (:weight-kg batch)
        so-far (:shipped-weight-kg batch 0.0)]
    (and (number? capacity)
         (number? new-weight-kg)
         (> (+ (double so-far) (double new-weight-kg)) (double capacity)))))

(defn product-category-valid?
  "Is `product-category` one of the closed, known product-category
  values (stamped/pressed/wire-product/household-good/hardware/formed-
  sheet-metal family)? nil/blank is treated as invalid (a
  production-batch patch must declare a real product category, not
  omit it silently)."
  [product-category]
  (contains? valid-product-categories product-category))

(defn defect-rate-valid?
  "Is `percent` a physically plausible batch defect/scrap-rate
  reading? Rejects nil, non-numbers, negative values, and values
  beyond `defect-rate-max-percent` -- a fabricated or sensor-error
  reading, never let through as a real batch fact."
  [percent]
  (and (number? percent)
       (>= (double percent) defect-rate-min-percent)
       (<= (double percent) defect-rate-max-percent)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human shop supervisor's/shipping approver's act, not this
  actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  stamping-press/pressing-line/wire-forming-machine maintenance window
  against a verified, registered piece of equipment. Pure function --
  does not actuate the stamping press or pressing/wire-forming line or
  execute any maintenance; it builds the RECORD a shop coordinator
  would keep. `metalfabmfg.governor` independently re-verifies the
  equipment's own verified/registered ground truth, and permanently
  blocks any attempt to directly actuate the stamping/pressing line
  (see README `Actuation`), before this is ever allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound fabricated-metal-product shipment against a verified,
  registered production batch. Pure function -- does not dispatch any
  real freight carrier; it builds the RECORD a shop coordinator would
  keep. `metalfabmfg.governor` independently re-verifies the
  shipment's own claimed weight against `shipment-weight-exceeded?`,
  before this is ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))

;; ----------------------------- :handoff (additive, optional) -----------------------------
;;
;; Part-supplier-linkage traceability (superproject part-supplier-
;; linkage ADR, cloud-itonami-isic-2599<->cloud-itonami-isic-2813):
;; `:coordinate-shipment` may OPTIONALLY carry a `:handoff` (the
;; superproject `:handoff` shared shape, ADR-2607177600, isic-1075<->
;; jsic-4721, REUSED AS-IS -- no new shape) naming which downstream
;; consumer (e.g. cloud-itonami-isic-2813, a pressure-equipment
;; manufacturer sourcing `part:piping`/`part:vibration-isolators`
;; fabricated-metal components) the shipped batch is destined for.
;; Unlike isic-1075's own `:coordinate-shipment` (which made
;; `:handoff` MANDATORY), this actor's `:handoff` stays OPTIONAL --
;; this shop ships fabricated-metal products to any customer, tracked
;; or not, the SAME 'optional field absent -> not checked' discipline
;; cloud-itonami-isic-2710's own `:coordinate-shipment`-`:handoff`
;; extension established (identical op name, independently
;; re-implemented, no shared code). Existing callers that never set
;; `:handoff` are completely unaffected -- the advisor already passes
;; `:value` through verbatim, so no advisor change is needed either
;; (the SAME reuse `cloud-itonami-isic-2710`'s own extension noted).

(defn handoff-fields-present?
  "True when `handoff` carries the three identity/correlation
  `:handoff/*` fields (`:handoff/id`/`:handoff/source-actor`/
  `:handoff/batch-id`) the superproject `:handoff` shared shape
  requires for traceability -- called ONLY when a `:handoff` map is
  actually present on a `:coordinate-shipment` proposal (see
  `metalfabmfg.governor/handoff-incomplete-violations`); a proposal
  with NO `:handoff` at all never reaches this predicate. Domain-
  specific optional fields on the shared shape (`:handoff/product-
  type-id`/`:handoff/quantity-kg`/`:handoff/cold-chain-temp-min-c`/
  `:handoff/cold-chain-temp-max-c`/`:handoff/dispatched-at-iso`) are
  NOT required here -- fabricated metal products are not cold-chain
  goods, the same 'optional field absent -> not checked' discipline
  cloud-itonami-isic-2710's own handoff-compatibility check uses."
  [handoff]
  (every? some? ((juxt :handoff/id :handoff/source-actor :handoff/batch-id) handoff)))
