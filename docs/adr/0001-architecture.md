# ADR-0001: MetalFabAdvisor ⊣ Metal Fabrication Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2599` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2599` publishes an OSS blueprint for a
metal-fabrication shop's **plant operations coordination**
(production-batch product-category/weight/defect-rate data logging,
stamping-press/pressing-line/wire-forming-machine maintenance
scheduling, safety-concern flagging, and outbound fabricated-metal-
product shipment coordination). Like every actor in this fleet, the
blueprint alone is not an implementation: this ADR records the
governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor + Phase
0->3 rollout pattern established across the cloud-itonami fleet.

The closest architectural analog is `cloud-itonami-isic-2431` (Casting
of iron and steel): both are back-office coordination actors for a
fixed processing PLANT with heavy manufacturing equipment and a real
physical safety dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`) and the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination). The two verticals are, however, distinct
plants with distinct hazard profiles: 2431's central physical hazard
is molten-metal handling (splash/burn risk at the melting furnace and
pour, furnace radiant-heat exposure, mold/core-binder fume exposure),
while 2599's is sharp-edge/burr laceration risk from freshly stamped/
pressed sheet metal, stamping-press pinch-point/crush hazard, wire-
forming-machine entanglement hazard, and coating/lubricant/degreasing
chemical exposure. This build mirrors 2431's architecture closely but
adapts the hazard profile and equipment/product vocabulary to the
metal-fabrication shop: 2599's permanent equipment-actuation block
guards a stamping press/pressing line (`:actuate-press-line?`) rather
than a melting furnace (`:actuate-furnace?`); and 2599's
production-batch record declares a `:product-category` (spanning
stamped/pressed parts, wire products, and metal household goods, per
ISIC 2599's own residual "n.e.c." scope) rather than 2431's
`:alloy-grade`.

`cloud-itonami-isic-2599` is also distinct from three sibling classes
in the same ISIC 259 group, none of which are `:implemented` in this
fleet yet (`kotoba-lang/industry` registry, `:maturity :spec`):
`cloud-itonami-isic-2591` (Forging, pressing, stamping and roll-
forming of metal -- the primary heavy metal-forming process upstream
of this shop's own light fabrication), `cloud-itonami-isic-2592`
(Treatment and coating of metals -- a distinct surface-finishing
process), and `cloud-itonami-isic-2593` (Manufacture of cutlery, hand
tools and general hardware -- a distinct, more specific product
family). ISIC 2599 is deliberately the RESIDUAL "other fabricated
metal products n.e.c." class: a metal-fabrication shop that stamps,
presses and wire-forms sheet metal and wire stock into finished goods
(stamped/pressed sheet-metal parts, wire products such as mesh and
formed-wire goods, and metal household goods) not elsewhere
classified in the 259 group -- a distinct plant, distinct process
shape (light stamping/pressing/wire-forming, not heavy forging/roll-
forming, surface coating, or cutlery/hand-tool manufacture), and this
build follows the 2431-style four-op propose-only pattern specified
for this class.

This vertical has NO pre-existing `kotoba-lang/metalfabmfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic — pure functions in
`metalfabmfg.registry` (equipment/batch verification, shipment-weight
recompute, product-category validation, defect-rate plausibility
validation) are re-verified independently by the governor, the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-2431`'s `foundrymfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:metal-fab-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "metal-fab-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external metal-fabrication-shop capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
"other fabricated metal products n.e.c." vertical has NO pre-existing
capability library to wrap. The equipment/batch-verification /
shipment-weight / product-category / defect-rate validation functions
live as pure functions in `metalfabmfg.registry` and are re-verified
independently by `metalfabmfg.governor` — the same "ground truth, not
self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2431`'s `foundrymfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of metal-
fabrication shop plant operations. It does NOT:
- Control the stamping press or pressing/wire-forming line equipment directly
- Make shop-safety or materials-safety decisions (exclusive to the human shop supervisor)
- Actuate the stamping press or pressing/wire-forming line

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human shop-supervisor
approval. This is not a replacement for the supervisor's authority —
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: metal-fabrication shop manufacturing has
real physical hazards (sharp-edge/burr laceration risk, stamping-press
pinch-point/crush hazard, wire-forming-machine entanglement hazard,
coating/lubricant/degreasing chemical exposure). Safety-concern
flagging NEVER auto-commits. All safety concerns escalate immediately
to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (sharp-edge/burr-laceration risk, stamping-
press pinch-point/crush hazard, wire-forming-machine entanglement
hazard, coating/lubricant/degreasing chemical exposure, equipment-
safety concern) ALWAYS escalates, never auto-commits. This is not a
"low-stakes proposal" — it is a circuit-breaker that must reach human
authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2431`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "shop/batch record must be independently
verified/registered before any action" HARD invariant applied to the
two distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date weight plus the proposal's own
claimed weight would exceed the batch's own recorded production
weight — never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into ten concrete checks in
`metalfabmfg.governor`, mirroring `cloud-itonami-isic-2431`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Shop/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's weight must independently recompute within the batch's own logged production weight
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Any proposal touching stamping/pressing-line-equipment control, or direct press-line actuation, is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Metal-fabrication shop plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human shop-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into ten concrete governor checks) protect against scope creep into
unauthorized equipment operation or press-line actuation. Safety
concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and press-line operation remain
human-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) — this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-2599`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-weight-exceeded, press-line-actuate-
  blocked, already-scheduled, invalid-product-category, invalid-
  defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
