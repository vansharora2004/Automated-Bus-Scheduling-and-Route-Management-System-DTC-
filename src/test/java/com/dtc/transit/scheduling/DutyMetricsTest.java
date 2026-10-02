package com.dtc.transit.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.dtc.transit.scheduling.engine.duty.DutyCandidate;
import com.dtc.transit.scheduling.engine.duty.DutyMetrics;
import com.dtc.transit.scheduling.engine.duty.DutyMetricsCalculator;
import com.dtc.transit.scheduling.engine.model.BlockEvent;
import com.dtc.transit.scheduling.engine.model.BlockEventType;
import com.dtc.transit.scheduling.engine.model.DutyType;
import com.dtc.transit.scheduling.engine.model.RuleSet;

/**
 * Duty metrics against figures worked out by hand.
 *
 * <p>Every expected value below is derived in the comment above it, in minutes, from the rule set's own sign-on
 * and sign-off allowances. That is the point: these numbers decide what a crew is paid and whether a duty is
 * legal, so a test that merely re-ran the calculator and compared it to itself would verify nothing.
 *
 * <p>The default rule set gives 15 minutes of sign-on, 10 of sign-off, a 30-minute minimum break and a 240-minute
 * paid guarantee.
 */
class DutyMetricsTest {

    private static final int EIGHT_AM = 8 * 3600;

    private final RuleSet rules = RuleSet.defaults();

    @Test
    @DisplayName("a single unbroken trip")
    void singleTrip() {
        // Bus taken at 08:00, handed back at 12:00. Four hours of driving.
        //   sign-on   08:00 - 15 min = 07:45
        //   sign-off  12:00 + 10 min = 12:10
        //   platform  240 min
        //   work      15 + 240 + 10 = 265 min
        //   spread    07:45 to 12:10 = 265 min (no breaks, so the same as work)
        //   paid      max(265, 240) = 265 min
        //   stretch   265 min, since nothing interrupts it
        DutyMetrics metrics = compute(trip(EIGHT_AM, 4 * 3600));

        assertThat(metrics.signOnSec()).isEqualTo(EIGHT_AM - 15 * 60);
        assertThat(metrics.signOffSec()).isEqualTo(EIGHT_AM + 4 * 3600 + 10 * 60);
        assertThat(metrics.platformSec()).isEqualTo(240 * 60);
        assertThat(metrics.breakSec()).isZero();
        assertThat(metrics.workSec()).isEqualTo(265 * 60);
        assertThat(metrics.spreadSec()).isEqualTo(265 * 60);
        assertThat(metrics.paidSec()).isEqualTo(265 * 60);
        assertThat(metrics.longestStretchSec()).isEqualTo(265 * 60);
        assertThat(metrics.overtimeSec()).isZero();
    }

    @Test
    @DisplayName("a qualifying break is unpaid and splits the work into two stretches")
    void qualifyingBreak() {
        // Three hours driving, a 45-minute layover, three more hours.
        //   platform  180 + 180 = 360 min; the layover is a break, so it is not platform time
        //   break     45 min
        //   work      15 + 360 + 10 = 385 min
        //   spread    15 + (180 + 45 + 180) + 10 = 430 min
        //   stretch   first is 15 + 180 = 195, second is 180 + 10 = 190, so the longest is 195 min
        DutyMetrics metrics = compute(
                trip(EIGHT_AM, 3 * 3600),
                layover(EIGHT_AM + 3 * 3600, 45 * 60),
                trip(EIGHT_AM + 3 * 3600 + 45 * 60, 3 * 3600));

        assertThat(metrics.platformSec()).isEqualTo(360 * 60);
        assertThat(metrics.breakSec()).isEqualTo(45 * 60);
        assertThat(metrics.workSec()).isEqualTo(385 * 60);
        assertThat(metrics.spreadSec()).isEqualTo(430 * 60);
        // The difference between spread and work is exactly the break, which is the distinction the two rules
        // exist to make: at work for seven hours, working six and a half of them.
        assertThat(metrics.spreadSec() - metrics.workSec()).isEqualTo(metrics.breakSec());
        assertThat(metrics.longestStretchSec()).isEqualTo(195 * 60);
    }

    @Test
    @DisplayName("a layover shorter than the minimum break is paid platform time")
    void shortLayoverIsPlatformTime() {
        // Three hours, a 20-minute layover, three more. The layover is below the 30-minute minimum, so the crew
        // is still with the bus and still being paid.
        //   platform  180 + 20 + 180 = 380 min
        //   break     0
        //   work      15 + 380 + 10 = 405 min
        //   stretch   the whole 405 min, because nothing qualifies as a break
        DutyMetrics metrics = compute(
                trip(EIGHT_AM, 3 * 3600),
                layover(EIGHT_AM + 3 * 3600, 20 * 60),
                trip(EIGHT_AM + 3 * 3600 + 20 * 60, 3 * 3600));

        assertThat(metrics.platformSec()).isEqualTo(380 * 60);
        assertThat(metrics.breakSec()).isZero();
        assertThat(metrics.workSec()).isEqualTo(405 * 60);
        assertThat(metrics.longestStretchSec()).isEqualTo(405 * 60);
    }

    @Test
    @DisplayName("exactly the minimum break qualifies")
    void breakAtTheBoundary() {
        // A 30-minute layover is exactly the minimum, and a rule written as "at least 30 minutes" has to accept
        // 30. One second less and it is platform time instead.
        DutyMetrics atTheLimit = compute(
                trip(EIGHT_AM, 3600), layover(EIGHT_AM + 3600, 30 * 60), trip(EIGHT_AM + 3600 + 30 * 60, 3600));
        DutyMetrics justUnder = compute(
                trip(EIGHT_AM, 3600),
                layover(EIGHT_AM + 3600, 30 * 60 - 1),
                trip(EIGHT_AM + 3600 + 30 * 60 - 1, 3600));

        assertThat(atTheLimit.breakSec()).isEqualTo(30 * 60);
        assertThat(justUnder.breakSec()).isZero();
    }

    @Test
    @DisplayName("the minimum paid guarantee tops up a short duty without inventing work")
    void shortDutyIsToppedUp() {
        // One hour of driving.
        //   work  15 + 60 + 10 = 85 min
        //   paid  max(85, 240) = 240 min
        DutyMetrics metrics = compute(trip(EIGHT_AM, 3600));

        assertThat(metrics.workSec()).isEqualTo(85 * 60);
        assertThat(metrics.paidSec()).isEqualTo(240 * 60);
        // Paid and worked are kept apart on purpose: the depot needs to know it is paying for 155 minutes
        // nobody worked, and a report that conflated the two would hide it.
        assertThat(metrics.isShortDuty()).isTrue();
    }

    @Test
    @DisplayName("overtime is the work above the normal maximum, and is measured even when not permitted")
    void overtimeIsMeasured() {
        // Nine hours of driving: work is 15 + 540 + 10 = 565 min against a 480-minute maximum, so 85 minutes of
        // overtime. The rule set forbids overtime, which makes this a violation rather than a payment — but the
        // number is still computed, because the constraint needs it to say by how much.
        DutyMetrics metrics = compute(trip(EIGHT_AM, 9 * 3600));

        assertThat(metrics.workSec()).isEqualTo(565 * 60);
        assertThat(metrics.overtimeSec()).isEqualTo(85 * 60);
    }

    @Test
    @DisplayName("sign-on and sign-off are clamped at the start of the service day")
    void signOnCannotPrecedeTheServiceDay() {
        // A duty taking a bus at 00:05 would sign on five minutes before the service day began. Clamping to zero
        // keeps the stored value usable; a negative service-day second would be rejected everywhere downstream.
        DutyMetrics metrics = compute(trip(5 * 60, 3600));

        assertThat(metrics.signOnSec()).isZero();
    }

    @Test
    @DisplayName("a duty signing on before 04:00 is classified as night work")
    void nightDuty() {
        assertThat(compute(trip(3 * 3600, 4 * 3600)).dutyType()).isEqualTo(DutyType.NIGHT);
    }

    @Test
    @DisplayName("a duty signing off after midnight is also night work")
    void pastMidnightIsNight() {
        // Takes the bus at 21:00 and runs four hours, so it signs off at 01:10 the next calendar day.
        assertThat(compute(trip(21 * 3600, 4 * 3600 + 20 * 60)).dutyType()).isEqualTo(DutyType.NIGHT);
    }

    @Test
    @DisplayName("an early turn signs on before 08:00; a late turn signs off after 20:00")
    void earlyAndLateTurns() {
        // Signs on at 05:45, which is an early turn.
        assertThat(compute(trip(6 * 3600, 4 * 3600)).dutyType()).isEqualTo(DutyType.EARLY);
        // Signs on at 13:45 and off at 21:10, which is a late turn.
        assertThat(compute(trip(14 * 3600, 7 * 3600)).dutyType()).isEqualTo(DutyType.LATE);
    }

    @Test
    @DisplayName("a duty inside the day with no long gap is a middle turn")
    void middleTurn() {
        // Signs on at 09:45 and off at 15:10: not early, not late, no night work, no long gap.
        assertThat(compute(trip(10 * 3600, 5 * 3600)).dutyType()).isEqualTo(DutyType.MIDDLE);
    }

    @Test
    @DisplayName("a long unpaid gap makes it a split duty, whatever hour it runs")
    void splitDuty() {
        // Two hours of work, two hours parked at the depot, two more hours. The gap clears the 90-minute
        // threshold, so this is a split duty even though it sits squarely inside the day.
        DutyMetrics metrics = compute(
                trip(10 * 3600, 2 * 3600),
                depotPark(12 * 3600, 2 * 3600),
                trip(14 * 3600, 2 * 3600));

        assertThat(metrics.dutyType()).isEqualTo(DutyType.SPLIT);
        assertThat(metrics.breakSec()).isEqualTo(2 * 3600);
    }

    // --- fixtures -----------------------------------------------------------

    private DutyMetrics compute(BlockEvent... events) {
        List<BlockEvent> list = List.of(events);
        var segment = new DutyCandidate.WorkSegment(
                1,
                list.get(0).seq(),
                list.get(list.size() - 1).seq(),
                list.get(0).startSec(),
                list.get(list.size() - 1).endSec(),
                null,
                null,
                list);
        return DutyMetricsCalculator.compute(List.of(segment), rules);
    }

    private static int nextSeq;

    private static BlockEvent trip(int startSec, int durationSec) {
        return new BlockEvent(
                nextSeq++, BlockEventType.TRIP, 1L, 1L, 2L, startSec, startSec + durationSec, 1000, false);
    }

    private static BlockEvent layover(int startSec, int durationSec) {
        return new BlockEvent(
                nextSeq++, BlockEventType.LAYOVER, null, 2L, 2L, startSec, startSec + durationSec, 0, false);
    }

    private static BlockEvent depotPark(int startSec, int durationSec) {
        return new BlockEvent(
                nextSeq++, BlockEventType.DEPOT_PARK, null, null, null, startSec, startSec + durationSec, 0, true);
    }
}
