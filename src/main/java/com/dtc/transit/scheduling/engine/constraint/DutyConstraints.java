package com.dtc.transit.scheduling.engine.constraint;

import java.util.List;

import com.dtc.transit.common.time.ServiceTime;
import com.dtc.transit.scheduling.engine.duty.DutyCandidate;
import com.dtc.transit.scheduling.engine.model.Severity;
import com.dtc.transit.scheduling.engine.vehicle.ConflictTypes;

/**
 * The rules that apply inside one duty.
 *
 * <p>One class holding several small constraints rather than a file each. They share nothing but the interface,
 * and each is a handful of lines; splitting them across five files would make the catalogue harder to read
 * without making any one rule easier to change.
 *
 * <p>Rest <em>between</em> consecutive duties and weekly rest are not here. They depend on who is doing the duty
 * and what they did yesterday, so they belong to crew assignment in Phase 9.
 *
 * <p>Every limit is read from the rule set, never from a constant. That is what makes changing the maximum
 * continuous work a configuration change rather than a release.
 */
public final class DutyConstraints {

    private DutyConstraints() {
        // factory of constraints
    }

    /** The catalogue, in the order a reader would want to see failures reported. */
    public static List<Constraint<DutyCandidate>> all() {
        return List.of(
                maxWork(), maxContinuousWork(), spreadOver(), minimumPaidDuty(), maximumPieces());
    }

    /** Only the hard ones, which is what the builder needs to decide whether a candidate is legal at all. */
    public static List<Constraint<DutyCandidate>> hardOnly() {
        return all().stream().filter(Constraint::isHard).toList();
    }

    /**
     * Total work in a duty.
     *
     * <p>Work, not spread-over: an unpaid break in the middle does not count. Overtime is allowed to raise the
     * ceiling only when the rule set permits it, and then only by {@code maxOvertimeMin}.
     */
    public static Constraint<DutyCandidate> maxWork() {
        return new Constraint<>() {
            @Override
            public String code() {
                return ConflictTypes.MAX_WORK_EXCEEDED;
            }

            @Override
            public Severity severity() {
                return Severity.HARD;
            }

            @Override
            public Scope scope() {
                return Scope.DUTY;
            }

            @Override
            public List<Violation> check(DutyCandidate duty, ValidationContext context) {
                var rules = context.rules();
                int ceiling = rules.maxWorkPerDutySec()
                        + (rules.allowOvertime() ? rules.maxOvertimeMin() * 60 : 0);
                int actual = duty.metrics().workSec();

                if (actual <= ceiling) {
                    return List.of();
                }
                return List.of(Violation.hard(
                        code(),
                        "Duty works %d min against a limit of %d min%s"
                                .formatted(
                                        actual / 60,
                                        ceiling / 60,
                                        rules.allowOvertime() ? " including permitted overtime" : ""),
                        actual,
                        ceiling));
            }
        };
    }

    /**
     * How long a crew may work without a qualifying break.
     *
     * <p>The rule that makes duty building interesting, and the reason the builder cannot stop at the first
     * infeasible cut point. A longer segment can be legal where a shorter one was not, because the break that
     * satisfies this rule may lie beyond the shorter segment's end.
     */
    public static Constraint<DutyCandidate> maxContinuousWork() {
        return new Constraint<>() {
            @Override
            public String code() {
                return ConflictTypes.CONTINUOUS_WORK_EXCEEDED;
            }

            @Override
            public Severity severity() {
                return Severity.HARD;
            }

            @Override
            public Scope scope() {
                return Scope.DUTY;
            }

            @Override
            public List<Violation> check(DutyCandidate duty, ValidationContext context) {
                int limit = context.rules().maxContinuousWorkSec();
                int actual = duty.metrics().longestStretchSec();

                if (actual <= limit) {
                    return List.of();
                }
                return List.of(Violation.hard(
                        code(),
                        "Duty works %d min continuously against a limit of %d min; it needs a break of at "
                                        .formatted(actual / 60, limit / 60)
                                + "least %d min before then".formatted(context.rules().minBreakMin()),
                        actual,
                        limit));
            }
        };
    }

    /**
     * Sign-on to sign-off, breaks included.
     *
     * <p>Separate from the work limit because they protect different things. The work limit stops a crew driving
     * too long; the spread-over stops a duty being stretched across fourteen hours of someone's day with an
     * unpaid hole in the middle.
     */
    public static Constraint<DutyCandidate> spreadOver() {
        return new Constraint<>() {
            @Override
            public String code() {
                return ConflictTypes.SPREAD_OVER_EXCEEDED;
            }

            @Override
            public Severity severity() {
                return Severity.HARD;
            }

            @Override
            public Scope scope() {
                return Scope.DUTY;
            }

            @Override
            public List<Violation> check(DutyCandidate duty, ValidationContext context) {
                int limit = context.rules().maxSpreadOverSec();
                int actual = duty.metrics().spreadSec();

                if (actual <= limit) {
                    return List.of();
                }
                return List.of(Violation.hard(
                        code(),
                        "Duty spans %s to %s, which is %d min against a spread-over limit of %d min"
                                .formatted(
                                        ServiceTime.format(duty.metrics().signOnSec()),
                                        ServiceTime.format(duty.metrics().signOffSec()),
                                        actual / 60,
                                        limit / 60),
                        actual,
                        limit));
            }
        };
    }

    /**
     * The minimum paid guarantee.
     *
     * <p>Soft, because a short duty is legal and sometimes unavoidable — the last block of the evening leaves a
     * tail that has to go somewhere. It is still worth flagging: the depot pays a full guarantee for it, so a
     * schedule full of short duties is expensive rather than illegal.
     */
    public static Constraint<DutyCandidate> minimumPaidDuty() {
        return new Constraint<>() {
            @Override
            public String code() {
                return ConflictTypes.SHORT_DUTY;
            }

            @Override
            public Severity severity() {
                return Severity.SOFT;
            }

            @Override
            public Scope scope() {
                return Scope.DUTY;
            }

            @Override
            public List<Violation> check(DutyCandidate duty, ValidationContext context) {
                int guarantee = context.rules().minPaidDutySec();
                int worked = duty.metrics().workSec();

                if (worked >= guarantee) {
                    return List.of();
                }
                return List.of(Violation.soft(
                        code(),
                        "Duty works %d min but is paid the %d min minimum, so %d min are paid and not worked"
                                .formatted(worked / 60, guarantee / 60, (guarantee - worked) / 60),
                        worked,
                        guarantee));
            }
        };
    }

    /**
     * How fragmented a duty may be.
     *
     * <p>Soft, and unreachable in linked mode, where a duty is one stretch of one bus by construction. It is here
     * because the catalogue is shared with Phase 8, where a duty can be assembled from pieces of several buses
     * and this becomes the rule that stops it being assembled from six of them.
     */
    public static Constraint<DutyCandidate> maximumPieces() {
        return new Constraint<>() {
            @Override
            public String code() {
                return ConflictTypes.TOO_MANY_CHANGEOVERS;
            }

            @Override
            public Severity severity() {
                return Severity.SOFT;
            }

            @Override
            public Scope scope() {
                return Scope.DUTY;
            }

            @Override
            public List<Violation> check(DutyCandidate duty, ValidationContext context) {
                int limit = context.rules().maxPiecesPerDuty();
                int actual = duty.pieceCount();

                if (actual <= limit) {
                    return List.of();
                }
                return List.of(Violation.soft(
                        code(),
                        "Duty is made of %d pieces of work against a preferred maximum of %d"
                                .formatted(actual, limit),
                        actual,
                        limit));
            }
        };
    }
}
