package com.dtc.transit.scheduling.engine.constraint;

import java.util.ArrayList;
import java.util.List;

import com.dtc.transit.scheduling.engine.duty.DutyCandidate;

/**
 * Runs the duty catalogue over a candidate.
 *
 * <p>Two methods with deliberately different costs. {@link #isLegal} stops at the first hard failure, which is
 * what the builder wants while searching: it tries a candidate per relief point and only needs a yes or no.
 * {@link #evaluate} runs everything and collects every finding, which is what the final validation and the API
 * want, because a planner needs the whole list rather than the first thing that went wrong.
 *
 * <p>Keeping both behind one evaluator is what stops them drifting. A fast path that disagreed with the full
 * check would produce schedules that pass construction and fail validation, which is the worst of both.
 */
public final class DutyEvaluator {

    private final List<Constraint<DutyCandidate>> constraints;
    private final List<Constraint<DutyCandidate>> hardConstraints;

    public DutyEvaluator() {
        this(DutyConstraints.all());
    }

    public DutyEvaluator(List<Constraint<DutyCandidate>> constraints) {
        this.constraints = List.copyOf(constraints);
        this.hardConstraints = this.constraints.stream().filter(Constraint::isHard).toList();
    }

    /**
     * Whether a candidate breaks no hard rule.
     *
     * <p>Short-circuits. Called once per candidate end point per cursor position, so on a long block this runs
     * in the thousands and the cost of building violation messages for candidates that are discarded anyway is
     * worth avoiding.
     */
    public boolean isLegal(DutyCandidate candidate, ValidationContext context) {
        for (Constraint<DutyCandidate> constraint : hardConstraints) {
            if (!constraint.check(candidate, context).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Every violation, hard and soft, in catalogue order. */
    public List<Violation> evaluate(DutyCandidate candidate, ValidationContext context) {
        List<Violation> violations = new ArrayList<>();
        for (Constraint<DutyCandidate> constraint : constraints) {
            violations.addAll(constraint.check(candidate, context));
        }
        return violations;
    }

    /** The hard violations only, for explaining why a candidate was rejected. */
    public List<Violation> hardViolations(DutyCandidate candidate, ValidationContext context) {
        List<Violation> violations = new ArrayList<>();
        for (Constraint<DutyCandidate> constraint : hardConstraints) {
            violations.addAll(constraint.check(candidate, context));
        }
        return violations;
    }

    public List<Constraint<DutyCandidate>> constraints() {
        return constraints;
    }
}
