# ADR-007 — Bounded Synthetic Claims Engine
- Status: Accepted
- Date: 2026-09-24 (recorded; in force since Phase 5)

## Context
Real insurance adjudication is vast (thousands of edits, coordination of benefits, complex networks, appeals law,
fraud models). Reproducing that breadth is neither feasible for a solo portfolio project nor the point. But a claims
engine that is a black box — "the system decided $X" with no explanation — has no engineering value either. The
goal is a **believable, explainable** engine, honestly bounded.

## Decision
Implement a **deterministic, explainable, bounded** synthetic adjudication engine rather than claiming full insurer
breadth. It applies, in order: eligibility on the service date → coverage/exclusions → allowed amount (fee
schedule) → cost-sharing (deductible → copay → coinsurance) → out-of-pocket max → prior-authorization → provider
network, computing per line and in total: allowed / copay / deductible-applied / coinsurance / plan-paid /
member-responsibility. Decisions are **immutable and versioned** (ADR-014). For any decision, the platform can show
*which plan applied and how every amount was computed* (the §60 proof). Where a heuristic is synthetic (e.g. the
anomaly detector's `HIGH_TOTAL_CHARGE` threshold), it is labelled synthetic, not presented as a measured model.

## Consequences
- ✅ Explainable and testable end-to-end; the money math lives in a pure `AdjudicationCalculator` with exact-amount
  tests.
- ✅ Honest scope — clearly a *synthetic* engine, never claimed to match a production payer.
- ⚠️ MVP limitations, documented rather than hidden: re-adjudication reverses/recomputes *this claim only* (not
  sibling claims in the same benefit year); plan-year = calendar year; no coordination-of-benefits; heuristics are
  demonstrative, not statistically validated.
