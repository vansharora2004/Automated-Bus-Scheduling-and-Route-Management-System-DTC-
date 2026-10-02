package com.dtc.transit.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.dtc.transit.scheduling.engine.constraint.DutyConstraints;
import com.dtc.transit.scheduling.engine.constraint.ValidationContext;
import com.dtc.transit.scheduling.engine.constraint.Violation;
import com.dtc.transit.scheduling.engine.duty.DutyCandidate;
import com.dtc.transit.scheduling.engine.duty.DutyMetricsCalculator;
import com.dtc.transit.scheduling.engine.model.BlockEvent;
import com.dtc.transit.scheduling.engine.model.BlockEventType;
import com.dtc.transit.scheduling.engine.model.RuleSet;

/**
 * Each duty constraint on its own, at its boundary.
 *
 * <p>Exactly at the limit must pass and one second over must fail. Every one of these rules is written as "at
 * most N", and an off-by-one either refuses a legal duty or permits an illegal one — neither of which is visible
 * in output that otherwise looks reasonable.
 *
 * <p>Candidates are assembled from synthetic events rather than from a built block, so each test states the one
 * quantity it is about and nothing else can drift.
 */
class DutyConstraintBoundaryTest {

    private static final int SIX_AM = 6 * 3600;

    private final RuleSet rules = RuleSet.defaults();

    @Nested
    @DisplayName("maximum work per duty")
    class MaxWork {

        /**
         * The limit is on work, which is sign-on plus platform plus sign-off.
         *
         * <p>With the default 15 minutes of sign-on and 10 of sign-off, 25 minutes are spent before any driving.
         * A 480-minute limit therefore allows 455 minutes of platform time exactly.
         */
        @ParameterizedTest(name = "{0} min of driving")
        @CsvSource({"454, false", "455, false", "456, true"})
        @DisplayName("455 minutes of driving is the most that fits an eight-hour duty")
        void boundary(int platformMinutes, boolean expectViolation) {
            var duty = drivingOnly(platformMinutes * 60);

            var violations = DutyConstraints.maxWork().check(duty, ValidationContext.of(rules));

            assertThat(violations.isEmpty()).isNotEqualTo(expectViolation);
        }

        @Test
        @DisplayName("the violation reports the measured value and the limit")
        void violationCarriesTheNumbers() {
            var duty = drivingOnly(500 * 60);

            Violation violation = DutyConstraints.maxWork()
                    .check(duty, ValidationContext.of(rules))
                    .get(0);

            // "Too much work" sends a planner looking; the numbers tell them how much slack to find.
            assertThat(violation.actual()).isEqualTo(525 * 60);
            assertThat(violation.limit()).isEqualTo(480 * 60);
            assertThat(violation.isBlocking()).isTrue();
        }

        @Test
        @DisplayName("permitted overtime raises the ceiling by exactly the allowance")
        void overtimeRaisesTheCeiling() {
            // 480 + 60 of overtime = 540 minutes of work, so 515 of platform time.
            RuleSet withOvertime =
                    RuleSet.defaults().toBuilder().allowOvertime(true).maxOvertimeMin(60).build();

            assertThat(DutyConstraints.maxWork()
                            .check(drivingOnly(515 * 60), ValidationContext.of(withOvertime)))
                    .isEmpty();
            assertThat(DutyConstraints.maxWork()
                            .check(drivingOnly(516 * 60), ValidationContext.of(withOvertime)))
                    .hasSize(1);
        }

        @Test
        @DisplayName("changing the limit in the rule set changes the verdict, with no code change")
        void limitComesFromTheRuleSet() {
            var duty = drivingOnly(300 * 60);
            // Both limits move together: the rule set refuses a continuous limit above the daily one, because
            // such a pair can never bind and almost always means the two were entered the wrong way round.
            RuleSet tighter = RuleSet.defaults().toBuilder()
                    .maxWorkPerDutyMin(240)
                    .maxContinuousWorkMin(240)
                    .build();

            assertThat(DutyConstraints.maxWork().check(duty, ValidationContext.of(rules)))
                    .isEmpty();
            assertThat(DutyConstraints.maxWork().check(duty, ValidationContext.of(tighter)))
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("maximum continuous work")
    class ContinuousWork {

        /**
         * The limit is on the longest run of work with no qualifying break.
         *
         * <p>Sign-on starts the stretch and sign-off continues it, because neither is separated from the driving
         * by a break. So 25 minutes of the five-hour limit are spent before and after the bus moves, and 275
         * minutes of unbroken driving is the most that fits.
         */
        @ParameterizedTest(name = "{0} min of unbroken driving")
        @CsvSource({"274, false", "275, false", "276, true"})
        @DisplayName("275 minutes of unbroken driving is the most that fits a five-hour limit")
        void boundary(int platformMinutes, boolean expectViolation) {
            var duty = drivingOnly(platformMinutes * 60);

            var violations = DutyConstraints.maxContinuousWork().check(duty, ValidationContext.of(rules));

            assertThat(violations.isEmpty()).isNotEqualTo(expectViolation);
        }

        @Test
        @DisplayName("a qualifying break resets the clock, so a longer duty becomes legal")
        void aBreakResetsTheClock() {
            // Four hours of driving, a 30-minute break, then four more. Eight hours of driving in total, which
            // would far exceed the continuous limit without the break in the middle.
            var duty = candidate(
                    trip(SIX_AM, 4 * 3600),
                    layover(SIX_AM + 4 * 3600, 30 * 60),
                    trip(SIX_AM + 4 * 3600 + 30 * 60, 4 * 3600));

            assertThat(DutyConstraints.maxContinuousWork().check(duty, ValidationContext.of(rules)))
                    .isEmpty();
        }

        @ParameterizedTest(name = "a {0}-minute layover")
        @CsvSource({"29, true", "30, false"})
        @DisplayName("only a layover at least as long as the minimum break counts as one")
        void shortLayoverIsNotABreak(int layoverMinutes, boolean expectViolation) {
            // Two stretches of three hours. Six hours of driving breaks the five-hour limit unless the layover
            // between them qualifies as a break.
            var duty = candidate(
                    trip(SIX_AM, 3 * 3600),
                    layover(SIX_AM + 3 * 3600, layoverMinutes * 60),
                    trip(SIX_AM + 3 * 3600 + layoverMinutes * 60, 3 * 3600));

            var violations = DutyConstraints.maxContinuousWork().check(duty, ValidationContext.of(rules));

            // A four-minute layover is standing at a terminal, not a rest. Counting it would let a crew work
            // twelve hours in half-hour stretches and never take a break.
            assertThat(violations.isEmpty()).isNotEqualTo(expectViolation);
        }

        @Test
        @DisplayName("changing the limit in the rule set changes the verdict, with no code change")
        void limitComesFromTheRuleSet() {
            var duty = drivingOnly(200 * 60);
            RuleSet tighter = RuleSet.defaults().toBuilder().maxContinuousWorkMin(180).build();

            assertThat(DutyConstraints.maxContinuousWork().check(duty, ValidationContext.of(rules)))
                    .isEmpty();
            assertThat(DutyConstraints.maxContinuousWork().check(duty, ValidationContext.of(tighter)))
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("spread-over")
    class SpreadOver {

        /**
         * Spread-over is sign-on to sign-off, breaks included.
         *
         * <p>So 720 minutes allows 695 minutes between taking the bus and handing it back, once 25 minutes of
         * sign-on and sign-off are accounted for.
         */
        @ParameterizedTest(name = "{0} min with the bus")
        @CsvSource({"694, false", "695, false", "696, true"})
        @DisplayName("695 minutes with the bus is the most that fits a twelve-hour spread-over")
        void boundary(int spanMinutes, boolean expectViolation) {
            // A long break in the middle keeps the work limit from binding first, so the spread-over is the only
            // rule this case tests.
            int half = (spanMinutes * 60 - 5 * 3600) / 2;
            var duty = candidate(
                    trip(SIX_AM, half),
                    layover(SIX_AM + half, 5 * 3600),
                    trip(SIX_AM + half + 5 * 3600, spanMinutes * 60 - half - 5 * 3600));

            var violations = DutyConstraints.spreadOver().check(duty, ValidationContext.of(rules));

            assertThat(violations.isEmpty()).isNotEqualTo(expectViolation);
        }

        @Test
        @DisplayName("the violation names the hours the duty spans")
        void violationNamesTheHours() {
            var duty = candidate(
                    trip(SIX_AM, 3 * 3600),
                    layover(SIX_AM + 3 * 3600, 9 * 3600),
                    trip(SIX_AM + 12 * 3600, 3 * 3600));

            Violation violation = DutyConstraints.spreadOver()
                    .check(duty, ValidationContext.of(rules))
                    .get(0);

            assertThat(violation.message()).contains("05:45").contains("spread-over");
        }
    }

    @Nested
    @DisplayName("minimum paid duty")
    class MinimumPaid {

        @ParameterizedTest(name = "{0} min of driving")
        @CsvSource({"214, true", "215, false", "216, false"})
        @DisplayName("215 minutes of driving reaches the four-hour guarantee")
        void boundary(int platformMinutes, boolean expectViolation) {
            var duty = drivingOnly(platformMinutes * 60);

            var violations = DutyConstraints.minimumPaidDuty().check(duty, ValidationContext.of(rules));

            assertThat(violations.isEmpty()).isNotEqualTo(expectViolation);
        }

        @Test
        @DisplayName("a short duty is a warning, not an obstacle")
        void shortDutyIsSoft() {
            var duty = drivingOnly(60 * 60);

            Violation violation = DutyConstraints.minimumPaidDuty()
                    .check(duty, ValidationContext.of(rules))
                    .get(0);

            // The last block of the evening leaves a tail that has to go somewhere. It is expensive, not illegal.
            assertThat(violation.isBlocking()).isFalse();
            assertThat(violation.message()).contains("paid and not worked");
        }
    }

    @Test
    @DisplayName("every constraint in the catalogue declares a code, a severity and the duty scope")
    void catalogueIsWellFormed() {
        var catalogue = DutyConstraints.all();

        assertThat(catalogue).isNotEmpty();
        assertThat(catalogue).allSatisfy(constraint -> {
            assertThat(constraint.code()).isNotBlank();
            assertThat(constraint.severity()).isNotNull();
            assertThat(constraint.scope()).isEqualTo(com.dtc.transit.scheduling.engine.constraint.Scope.DUTY);
        });
        // Codes must be distinct, or two different problems would be reported under one name and the second
        // would be deduplicated away.
        assertThat(catalogue.stream().map(c -> c.code()).distinct()).hasSameSizeAs(catalogue);
    }

    // --- fixtures -----------------------------------------------------------

    /** A duty that is one unbroken trip of the given length. */
    private DutyCandidate drivingOnly(int platformSec) {
        return candidate(trip(SIX_AM, platformSec));
    }

    private DutyCandidate candidate(BlockEvent... events) {
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
        return new DutyCandidate(List.of(segment), DutyMetricsCalculator.compute(List.of(segment), rules));
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
}
