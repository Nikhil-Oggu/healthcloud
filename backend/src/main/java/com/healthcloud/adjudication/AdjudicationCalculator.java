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
 *   <li><b>out-of-pocket max</b> — the member's cost-sharing (copay + deductible + coinsurance) is capped so the
 *       cumulative out-of-pocket for the year does not exceed the plan's maximum; any excess shifts to the plan
 *       ({@code oopMaxAppliedAmount}). A null remaining OOP means no cap (unlimited).</li>
 * </ol>
 * {@code memberResponsibility = copay + deductibleApplied + coinsurance - oopMaxApplied}; {@code planPaid =
 * allowed - member}. Money is {@link BigDecimal} at scale 2, {@link RoundingMode#HALF_UP}; the coinsurance rate
 * is a 0..1 fraction. The remaining deductible and remaining OOP are carried across claims by the benefit
 * accumulator (§31); this class is pure and just applies what it is given.
 *
 * <p><b>Honest limitation.</b> Exclusions (non-covered procedures) and fee-schedule allowed amounts
 * ({@code allowed = charge} here) are later Phase-5 slices. A denied claim (no coverage) is handled by the
 * service, not here — this calculator only computes the covered case.
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
            BigDecimal oopMaxAppliedAmount,
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
     * deductible is available and the out-of-pocket max is uncapped. Kept for callers/tests that do not track
     * cross-claim accumulation; the engine uses the four-argument form with the accumulated amounts carried in.
     */
    public static Computation adjudicate(PlanParameters plan, List<LineCharge> lines) {
        return adjudicate(plan, plan.deductibleAmount(), null, lines);
    }

    /**
     * Compute the covered adjudication given the <b>remaining deductible</b> (the accumulator carries the rest
     * across claims, §31), with the out-of-pocket max uncapped. Retained for callers/tests that do not enforce
     * the OOP max; the engine uses {@link #adjudicate(PlanParameters, BigDecimal, BigDecimal, List)}.
     */
    public static Computation adjudicate(PlanParameters plan, BigDecimal deductibleRemaining,
                                         List<LineCharge> lines) {
        return adjudicate(plan, deductibleRemaining, null, lines);
    }

    /**
     * Compute the covered adjudication for {@code lines} under {@code plan}, given how much of the annual
     * deductible and the annual out-of-pocket max are <b>still remaining</b> for this patient/plan/year (the
     * benefit accumulator carries the rest across claims, §31). A null {@code oopRemaining} means no OOP cap.
     * Deterministic: the same inputs always produce the same amounts. Both the remaining deductible and the
     * remaining OOP are consumed across the lines in the order given.
     */
    public static Computation adjudicate(PlanParameters plan, BigDecimal deductibleRemaining,
                                         BigDecimal oopRemaining, List<LineCharge> lines) {
        BigDecimal remainingDeductible = money(deductibleRemaining).max(ZERO);
        BigDecimal remainingOop = oopRemaining == null ? null : money(oopRemaining).max(ZERO);
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
            BigDecimal grossMember = copay.add(deductibleApplied).add(coinsurance);

            // Out-of-pocket max: cap the member's cost-sharing; the excess shifts to the plan.
            BigDecimal oopMaxApplied = ZERO;
            BigDecimal member = grossMember;
            if (remainingOop != null) {
                member = grossMember.min(remainingOop);
                oopMaxApplied = grossMember.subtract(member);
                remainingOop = remainingOop.subtract(member);
            }
            BigDecimal planPaid = allowed.subtract(member);

            computed.add(new LineComputation(line.lineNumber(), allowed, copay, deductibleApplied, coinsurance,
                    oopMaxApplied, planPaid, member));
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
