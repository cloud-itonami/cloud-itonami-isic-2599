# cloud-itonami-isic-2599: Manufacture of other fabricated metal products n.e.c.

Open Business Blueprint for **ISIC Rev.5 2599**: manufacture of other fabricated metal products n.e.c. — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **metal-fabrication shop plant operations**: production-batch data logging (product-category/weight/defect-rate), stamping-press/pressing-line/wire-forming-machine maintenance scheduling, safety-concern flagging, and outbound fabricated-metal-product shipment coordination.

This repository designs a forkable OSS business for metal-fabrication
shop plant operations: run by a qualified operator so a shop keeps its
own operating records instead of renting a closed SaaS.

## Scope: the residual "other fabricated metal products" shop, not forging, coating, or hand tools

ISIC 2599 covers the metal-fabrication shop that stamps, presses and
wire-forms sheet metal and wire stock into finished miscellaneous
metal goods — stamped/pressed sheet-metal parts, wire products (mesh,
coil, formed-wire goods), and metal household goods — not elsewhere
classified in the ISIC 259 group. This is distinct from
`cloud-itonami-isic-2591` (Forging, pressing, stamping and roll-
forming of metal), the primary heavy metal-forming process upstream of
this shop's own light fabrication; from `cloud-itonami-isic-2592`
(Treatment and coating of metals), a distinct surface-finishing
process; and from `cloud-itonami-isic-2593` (Manufacture of cutlery,
hand tools and general hardware), a distinct, more specific product
family. This actor's own hazard profile centers on sharp-edge/burr
laceration risk from freshly stamped/pressed sheet metal, stamping-
press pinch-point/crush hazard, wire-forming-machine entanglement
hazard, and coating/lubricant/degreasing chemical exposure.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — product-category/weight/defect-rate data logging (administrative, not an operational decision)
- `:schedule-maintenance` — stamping-press/pressing-line/wire-forming-machine maintenance scheduling proposal
- `:flag-safety-concern` — surface a sharp-edge/burr-laceration/press-pinch-point/wire-entanglement/coating-chemical-exposure/equipment-safety concern (always escalates)
- `:coordinate-shipment` — outbound fabricated-metal-product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-relevant domain**
(stamping press pinch-point/crush hazard, sharp-edge/burr laceration
risk, wire-forming-machine entanglement hazard, coating/lubricant
chemical exposure):

- Does NOT control the stamping press or pressing/wire-forming line equipment directly
- Does NOT make shop-safety or materials-safety decisions (that's the shop supervisor's exclusive human authority)
- Does NOT actuate the stamping press or pressing/wire-forming line (human shop supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`metalfabmfg.operation/build`, a langgraph-clj StateGraph):
1. **`metalfabmfg.advisor`** (sealed intelligence node, `MetalFabAdvisor`): proposes decisions only, never commits
2. **`metalfabmfg.governor`** (independent, `Metal Fabrication Plant Operations Governor`): validates against domain rules, re-derived from `metalfabmfg.registry`'s pure functions and `metalfabmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Shop/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct stamping/pressing-line-equipment control)
     - Any proposal touching stamping/pressing-line-equipment control is a hard, permanent block (`:actuate-press-line? true` on a `:schedule-maintenance` proposal)
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-category` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`metalfabmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`metalfabmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
