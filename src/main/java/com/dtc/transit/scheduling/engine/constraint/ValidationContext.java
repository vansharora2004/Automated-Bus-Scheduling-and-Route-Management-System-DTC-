package com.dtc.transit.scheduling.engine.constraint;

import com.dtc.transit.scheduling.engine.model.DepotContext;
import com.dtc.transit.scheduling.engine.model.RuleSet;

/**
 * What a constraint is allowed to look at besides its subject.
 *
 * <p>Just the rules and the depot snapshot. Passing a repository or a service here would let a constraint ask a
 * question whose answer could change between the construction pass and the validation pass, and the two passes
 * agreeing is the entire point of running the catalogue twice.
 */
public record ValidationContext(RuleSet rules, DepotContext depot) {

    /** For unit tests and for block-level checks that need no depot. */
    public static ValidationContext of(RuleSet rules) {
        return new ValidationContext(rules, null);
    }
}
