package com.dtc.transit.scheduling.engine.constraint;

import java.util.List;

import com.dtc.transit.scheduling.engine.model.Severity;

/**
 * One rule, checked against one kind of subject.
 *
 * <p>Generic in the subject so that duty rules, block rules and assignment rules share an interface and a
 * catalogue. The alternative — a method per rule on a large validator — makes adding a rule an edit to shared
 * code, and makes it impossible to test one rule without the others.
 *
 * <p>The same catalogue is used twice, deliberately. During construction the duty builder runs these as fast
 * feasibility checks on candidate segments; after construction they run again as a full re-check. A rule that
 * only ran during construction would be a rule nobody could verify was applied.
 *
 * <p>Implementations must be stateless and side-effect free. The builder calls them thousands of times on
 * candidates it then discards, and a constraint that remembered anything would make the result depend on how
 * many candidates happened to be tried.
 */
public interface Constraint<T> {

    /** The conflict code this constraint emits, for example {@code MAX_CONTINUOUS_WORK}. */
    String code();

    Severity severity();

    Scope scope();

    /** Empty when the subject is acceptable. Never throws: an unacceptable subject is a finding, not an error. */
    List<Violation> check(T subject, ValidationContext context);

    /** Whether a failure of this constraint makes a candidate illegal rather than merely worse. */
    default boolean isHard() {
        return severity() == Severity.HARD;
    }
}
