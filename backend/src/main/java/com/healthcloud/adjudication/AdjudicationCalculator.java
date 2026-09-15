package com.healthcloud.adjudication;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The claims-adjudication math (source-of-truth §Phase 5) as a pure, unit-testable policy class — no Spring, no
 * DB, no I/O — the third exemplar of the pure-policy pattern after {@code ClaimTransitions} and
 * {@code ConsentPolicy}. Given a covering plan's parameters and a claim's line charges, it computes,
 * deterministically and explainably, how every amount is split between the plan and the member.
 *
 * <p><b>The model (deterministic, MVP-honest).</b> For each line, in line order:
 * <ol>
 *   <li>{@code allowed = charge} — the allowed amount equals the billed charge (a fee schedule is a later hook).</li>
 *   <li><b>copay</b> = {@code min(plan copay, allowed)} — a fixed member amount per line.</li>
 *   <li><b>deductible</b> — the plan's annual deductible is consumed across the claim's lines in order, up to what
 *       remains of the charge after copay; the member pays the amount applied.</li>
 *   <li><b>coinsurance</b> = {@code round(remainder * coinsuranceRate)} to the member, the rest to the plan.</li>
 * </ol>
 * {@code memberResponsibility = copay + deductibleApplied + coinsurance}; {@code planPaid = allowed - member}.
 * Money is {@link BigDecimal} at scale 2, {@link RoundingMode#HALF_UP}; the coinsurance rate is a 0..1 fraction.
 *
 * <p><b>Honest limitation.</b> The deductible here starts fresh at the plan's full amount for each claim: this
 * slice has no cross-claim <i>annual</i> deductible/out-of-pocket accumulator (that needs a row-locked financial
 * accumulator, §31, and is a later Phase-5 slice). Within a single claim, though, the deductible is consumed
 * across lines deterministically. Exclusions and out-of-pocket-max are also later slices. A denied claim (no
 * coverage) is handled by the service, not here — this calculator only computes the covered case.
 */
public final class AdjudicationCalculator {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private AdjudicationCalculator() {
    }

    /** The covering plan's parameters that drive the math (a subset of the coverage plan). */
    public record PlanParameters(BigDecimal deductibleAmount, BigDecimal coinsuranceRate, BigDecimal copayAmount) {
    }

    /** One line's input to the calculator. */
    public record LineCharge(int lineNumber, BigDecimal chargeAmount) {
    }

    /** How one line was computed — every amount that makes up the split, for the explainable breakdown. */
    public record LineComputation(
            int lineNumber,
            BigDecimal allowedAmount,
            BigDecimal copayAmount,
            BigDecimal deductibleAppliedAmount,
            BigDecimal coinsuranceAmount,
            BigDecimal planPaidAmount,
            BigDecimal memberResponsibility) {
    }

    /** The whole computed outcome: the per-line breakdown plus the claim totals. */
    public record Computation(
            List<LineComputation> lines,
            BigDecimal totalAllowed,
            BigDecimal totalPlanPaid,
            BigDecimal totalMemberResponsibility) {
    }

    /**
     * Compute the covered adjudication as if this were the first claim of the benefit year — the plan's full
     * deductible is available. Kept for callers/tests that do not track cross-claim accumulation; the engine
     * uses {@link #adjudicate(PlanParameters, BigDecimal, List)} with the deductible already met carried in.
     */
    public static Computation adjudicate(PlanParameters plan, List<LineCharge> lines) {
        return adjudicate(plan, plan.deductibleAmount(), lines);
    }

    /**
     * Compute the covered adjudication for {@code lines} under {@code plan}, given how much of the annual
     * deductible is <b>still remaining</b> for this patient/plan/year (the benefit accumulator carries the rest
     * across claims, §31). Deterministic: the same inputs always produce the same amounts. The remaining
     * deductible is consumed across the lines in the order given.
     */
    public static Computation adjudicate(PlanParameters plan, BigDecimal deductibleRemaining,
                                         List<LineCharge> lines) {
        BigDecimal remainingDeductible = money(deductibleRemaining).max(ZERO);
        BigDecimal copayRate = plan.copayAmount() == null ? ZERO : money(plan.copayAmount());
        BigDecimal coinsuranceRate = plan.coinsuranceRate() == null ? BigDecimal.ZERO : plan.coinsuranceRate();

        List<LineComputation> computed = new ArrayList<>();
        BigDecimal totalAllowed = ZERO;
        BigDecimal totalPlanPaid = ZERO;
        BigDecimal totalMember = ZERO;

        for (LineCharge line : lines) {
            BigDecimal allowed = money(line.chargeAmount());

            BigDecimal copay = copayRate.min(allowed);
            BigDecimal afterCopay = allowed.subtract(copay);

            BigDecimal deductibleApplied = remainingDeductible.min(afterCopay);
            remainingDeductible = remainingDeductible.subtract(deductibleApplied);
            BigDecimal afterDeductible = afterCopay.subtract(deductibleApplied);

            BigDecimal coinsurance = money(afterDeductible.multiply(coinsuranceRate));
            BigDecimal member = copay.add(deductibleApplied).add(coinsurance);
            BigDecimal planPaid = allowed.subtract(member);

            computed.add(new LineComputation(
                    line.lineNumber(), allowed, copay, deductibleApplied, coinsurance, planPaid, member));
            totalAllowed = totalAllowed.add(allowed);
            totalPlanPaid = totalPlanPaid.add(planPaid);
            totalMember = totalMember.add(member);
        }

        return new Computation(computed, totalAllowed, totalPlanPaid, totalMember);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
