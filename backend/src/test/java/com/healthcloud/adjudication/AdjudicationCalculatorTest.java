package com.healthcloud.adjudication;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.healthcloud.adjudication.AdjudicationCalculator.Computation;
import com.healthcloud.adjudication.AdjudicationCalculator.LineCharge;
import com.healthcloud.adjudication.AdjudicationCalculator.LineComputation;
import com.healthcloud.adjudication.AdjudicationCalculator.PlanParameters;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The adjudication math (Phase 5 slice 1) — a pure policy class, so this needs no Spring/DB. Proves the split
 * for a single line (copay → deductible → coinsurance), the deductible being consumed across lines in order,
 * zero-coinsurance, and HALF_UP rounding. Every amount is asserted, since "how every amount was computed" is the
 * §60 proof.
 */
class AdjudicationCalculatorTest {

    private static PlanParameters plan(String deductible, String coinsurance, String copay) {
        return new PlanParameters(new BigDecimal(deductible), new BigDecimal(coinsurance), new BigDecimal(copay));
    }

    private static BigDecimal money(String v) {
        return new BigDecimal(v).setScale(2);
    }

    @Test
    void a_single_line_under_the_deductible_is_all_member_after_copay() {
        // $150 charge, $25 copay, $1500 deductible not met, 20% coinsurance. After the copay, the remaining $125
        // all falls under the deductible, so coinsurance is 0 and the member owes copay + deductible = $150.
        Computation c = AdjudicationCalculator.adjudicate(
                plan("1500.00", "0.2000", "25.00"),
                List.of(new LineCharge(1, money("150.00"))));

        LineComputation line = c.lines().get(0);
        assertEquals(money("150.00"), line.allowedAmount());
        assertEquals(money("25.00"), line.copayAmount());
        assertEquals(money("125.00"), line.deductibleAppliedAmount());
        assertEquals(money("0.00"), line.coinsuranceAmount());
        assertEquals(money("0.00"), line.planPaidAmount());
        assertEquals(money("150.00"), line.memberResponsibility());
        assertEquals(money("150.00"), c.totalAllowed());
        assertEquals(money("0.00"), c.totalPlanPaid());
        assertEquals(money("150.00"), c.totalMemberResponsibility());
    }

    @Test
    void with_the_deductible_met_the_plan_pays_coinsurance_share() {
        // No deductible, $0 copay, 20% coinsurance: on $200 the member owes 20% = $40 and the plan pays $160.
        Computation c = AdjudicationCalculator.adjudicate(
                plan("0.00", "0.2000", "0.00"),
                List.of(new LineCharge(1, money("200.00"))));

        LineComputation line = c.lines().get(0);
        assertEquals(money("0.00"), line.copayAmount());
        assertEquals(money("0.00"), line.deductibleAppliedAmount());
        assertEquals(money("40.00"), line.coinsuranceAmount());
        assertEquals(money("160.00"), line.planPaidAmount());
        assertEquals(money("40.00"), line.memberResponsibility());
    }

    @Test
    void the_deductible_is_consumed_across_lines_in_order() {
        // $100 deductible, $0 copay, 20% coinsurance, two $200 lines.
        // Line 1: $100 to the deductible, then 20% of the remaining $100 = $20 → member $120, plan $80.
        // Line 2: deductible exhausted → 20% of $200 = $40 → member $40, plan $160.
        Computation c = AdjudicationCalculator.adjudicate(
                plan("100.00", "0.2000", "0.00"),
                List.of(new LineCharge(1, money("200.00")), new LineCharge(2, money("200.00"))));

        LineComputation first = c.lines().get(0);
        assertEquals(money("100.00"), first.deductibleAppliedAmount());
        assertEquals(money("20.00"), first.coinsuranceAmount());
        assertEquals(money("120.00"), first.memberResponsibility());
        assertEquals(money("80.00"), first.planPaidAmount());

        LineComputation second = c.lines().get(1);
        assertEquals(money("0.00"), second.deductibleAppliedAmount());
        assertEquals(money("40.00"), second.coinsuranceAmount());
        assertEquals(money("40.00"), second.memberResponsibility());
        assertEquals(money("160.00"), second.planPaidAmount());

        assertEquals(money("400.00"), c.totalAllowed());
        assertEquals(money("240.00"), c.totalPlanPaid());
        assertEquals(money("160.00"), c.totalMemberResponsibility());
    }

    @Test
    void zero_coinsurance_hdhp_style_plan_pays_everything_after_the_deductible() {
        // HDHP-ish: $50 deductible, $0 copay, 0% coinsurance on a $200 line → member owes only the $50 deductible.
        Computation c = AdjudicationCalculator.adjudicate(
                plan("50.00", "0.0000", "0.00"),
                List.of(new LineCharge(1, money("200.00"))));

        LineComputation line = c.lines().get(0);
        assertEquals(money("50.00"), line.deductibleAppliedAmount());
        assertEquals(money("0.00"), line.coinsuranceAmount());
        assertEquals(money("50.00"), line.memberResponsibility());
        assertEquals(money("150.00"), line.planPaidAmount());
    }

    @Test
    void a_partial_remaining_deductible_is_consumed_then_coinsurance() {
        // Only $50 of the deductible remains (the rest met on earlier claims), $0 copay, 20% coinsurance on $200:
        // $50 to the deductible, then 20% of the remaining $150 = $30 → member $80, plan $120.
        Computation c = AdjudicationCalculator.adjudicate(
                plan("1500.00", "0.2000", "0.00"), money("50.00"),
                List.of(new LineCharge(1, money("200.00"))));

        LineComputation line = c.lines().get(0);
        assertEquals(money("50.00"), line.deductibleAppliedAmount());
        assertEquals(money("30.00"), line.coinsuranceAmount());
        assertEquals(money("80.00"), line.memberResponsibility());
        assertEquals(money("120.00"), line.planPaidAmount());
    }

    @Test
    void a_met_deductible_means_the_plan_pays_from_the_first_dollar() {
        // Deductible fully met (remaining $0), $0 copay, 20% coinsurance on $200 → member $40, plan $160.
        Computation c = AdjudicationCalculator.adjudicate(
                plan("1500.00", "0.2000", "0.00"), money("0.00"),
                List.of(new LineCharge(1, money("200.00"))));

        LineComputation line = c.lines().get(0);
        assertEquals(money("0.00"), line.deductibleAppliedAmount());
        assertEquals(money("40.00"), line.coinsuranceAmount());
        assertEquals(money("160.00"), line.planPaidAmount());
    }

    @Test
    void the_out_of_pocket_max_caps_member_cost() {
        // Deductible met, $0 copay, 20% coinsurance on $1,000 = $200 gross member — but only $50 of OOP remains,
        // so the member pays $50, $150 shifts to the plan, and the plan pays $950.
        Computation c = AdjudicationCalculator.adjudicate(
                plan("1500.00", "0.2000", "0.00"), money("0.00"), money("50.00"),
                List.of(new LineCharge(1, money("1000.00"))));

        LineComputation line = c.lines().get(0);
        assertEquals(money("200.00"), line.coinsuranceAmount());
        assertEquals(money("150.00"), line.oopMaxAppliedAmount());
        assertEquals(money("50.00"), line.memberResponsibility());
        assertEquals(money("950.00"), line.planPaidAmount());
        assertEquals(money("50.00"), c.totalMemberResponsibility());
    }

    @Test
    void a_null_remaining_oop_never_caps() {
        // Same line, but no OOP cap (null) → the member pays the full $200 coinsurance, nothing shifts.
        Computation c = AdjudicationCalculator.adjudicate(
                plan("1500.00", "0.2000", "0.00"), money("0.00"), null,
                List.of(new LineCharge(1, money("1000.00"))));

        LineComputation line = c.lines().get(0);
        assertEquals(money("0.00"), line.oopMaxAppliedAmount());
        assertEquals(money("200.00"), line.memberResponsibility());
        assertEquals(money("800.00"), line.planPaidAmount());
    }

    @Test
    void the_oop_cap_is_consumed_across_lines() {
        // $30 OOP remaining, two $1,000 lines at 20% (no deductible/copay). Line 1: $200 gross → member $30,
        // $170 to plan, OOP exhausted. Line 2: $200 gross → member $0, all $200 to plan.
        Computation c = AdjudicationCalculator.adjudicate(
                plan("1500.00", "0.2000", "0.00"), money("0.00"), money("30.00"),
                List.of(new LineCharge(1, money("1000.00")), new LineCharge(2, money("1000.00"))));

        assertEquals(money("30.00"), c.lines().get(0).memberResponsibility());
        assertEquals(money("170.00"), c.lines().get(0).oopMaxAppliedAmount());
        assertEquals(money("0.00"), c.lines().get(1).memberResponsibility());
        assertEquals(money("200.00"), c.lines().get(1).oopMaxAppliedAmount());
        assertEquals(money("2000.00"), c.totalAllowed());
        assertEquals(money("1970.00"), c.totalPlanPaid());
        assertEquals(money("30.00"), c.totalMemberResponsibility());
    }

    @Test
    void a_fee_schedule_allowed_below_the_charge_drives_the_split() {
        // $200 charge but the plan allows only $120 (fee schedule). No deductible, $0 copay, 20% coinsurance:
        // 20% of the $120 allowed = $24 to the member, $96 to the plan. The $80 (charge - allowed) is written off.
        Computation c = AdjudicationCalculator.adjudicate(
                plan("0.00", "0.2000", "0.00"),
                List.of(new LineCharge(1, money("200.00"), money("120.00"))));

        LineComputation line = c.lines().get(0);
        assertEquals(money("120.00"), line.allowedAmount());
        assertEquals(money("24.00"), line.coinsuranceAmount());
        assertEquals(money("24.00"), line.memberResponsibility());
        assertEquals(money("96.00"), line.planPaidAmount());
        assertEquals(money("120.00"), c.totalAllowed());
    }

    @Test
    void the_allowed_amount_is_capped_at_the_billed_charge() {
        // A fee-schedule amount above the charge never inflates the allowed — a plan allows at most what was billed.
        Computation c = AdjudicationCalculator.adjudicate(
                plan("0.00", "0.2000", "0.00"),
                List.of(new LineCharge(1, money("80.00"), money("120.00"))));

        LineComputation line = c.lines().get(0);
        assertEquals(money("80.00"), line.allowedAmount());
        assertEquals(money("16.00"), line.memberResponsibility());
        assertEquals(money("64.00"), line.planPaidAmount());
    }

    @Test
    void no_fee_schedule_entry_falls_back_to_allowed_equals_charge() {
        // The two-argument LineCharge (no fee schedule) keeps the previous behavior: allowed = charge.
        Computation c = AdjudicationCalculator.adjudicate(
                plan("0.00", "0.2000", "0.00"),
                List.of(new LineCharge(1, money("200.00"))));

        assertEquals(money("200.00"), c.lines().get(0).allowedAmount());
    }

    @Test
    void coinsurance_is_rounded_half_up_to_cents() {
        // No deductible, $0 copay, 15% of $155.55 = $23.3325 → rounds to $23.33; plan pays the rest.
        Computation c = AdjudicationCalculator.adjudicate(
                plan("0.00", "0.1500", "0.00"),
                List.of(new LineCharge(1, money("155.55"))));

        LineComputation line = c.lines().get(0);
        assertEquals(money("23.33"), line.coinsuranceAmount());
        assertEquals(money("132.22"), line.planPaidAmount());
        assertEquals(money("23.33"), line.memberResponsibility());
    }
}
