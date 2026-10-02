package com.dtc.transit.scheduling.rules;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Finds the rule set that applies, and binds it to the typed record.
 *
 * <p>Resolution has a fixed precedence: a depot-specific set beats the global one, and among sets of the same
 * scope the latest {@code effective_from} on or before the service date wins. Scheduling a past date therefore
 * uses the rules that were in force then, which is the whole reason rule sets are dated rather than edited.
 *
 * <p>Binding failures are loud. A rule set the code cannot read, or one whose values are contradictory, must
 * stop a run rather than quietly fall back to defaults: a schedule built against assumed rules is a schedule
 * nobody has checked.
 */
@Service
public class RuleSetResolver {

    private static final Logger log = LoggerFactory.getLogger(RuleSetResolver.class);

    private final StoredRuleSetRepository repository;
    private final ObjectMapper objectMapper;

    public RuleSetResolver(StoredRuleSetRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * The rule set in force for a depot-day.
     *
     * @throws BusinessRuleException when no rule set covers the date, which means the system has no idea what
     *     the labour rules are and must not guess
     */
    @Transactional(readOnly = true)
    public Resolved resolve(Long depotId, LocalDate serviceDate) {
        StoredRuleSet stored = repository
                .findInForce(depotId, serviceDate)
                .orElseThrow(() -> new BusinessRuleException(
                        "NO_RULE_SET",
                        "No rule set is effective on %s for depot %d. Create one before scheduling."
                                .formatted(serviceDate, depotId)));

        log.debug(
                "resolved rule set {} ({}) for depot {} on {}",
                stored.getId(),
                stored.isGlobal() ? "global" : "depot-specific",
                depotId,
                serviceDate);
        return new Resolved(stored.getId(), bind(stored));
    }

    /** Binds one stored rule set's JSON to the typed record. */
    @Transactional(readOnly = true)
    public RuleSet bindById(Long ruleSetId) {
        return bind(repository
                .findById(ruleSetId)
                .orElseThrow(() -> new BusinessRuleException(
                        "NO_RULE_SET", "Rule set " + ruleSetId + " does not exist")));
    }

    /**
     * Turns stored JSON into the record, with the record's own invariants doing the checking.
     *
     * <p>A missing field binds to zero, which the record's constructor then rejects, so an incomplete rule set
     * fails here rather than producing a schedule with a zero-minute work limit.
     */
    public RuleSet bind(StoredRuleSet stored) {
        try {
            return objectMapper.readValue(stored.getRules(), RuleSet.class);
        } catch (IllegalArgumentException e) {
            // Thrown by the record's constructor for a contradictory set, and wrapped by Jackson.
            throw new BusinessRuleException(
                    "INVALID_RULE_SET",
                    "Rule set %d ('%s') is not usable: %s".formatted(stored.getId(), stored.getName(), rootCause(e)));
        } catch (Exception e) {
            throw new BusinessRuleException(
                    "INVALID_RULE_SET",
                    "Rule set %d ('%s') could not be read: %s"
                            .formatted(stored.getId(), stored.getName(), rootCause(e)));
        }
    }

    private static String rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    /**
     * @param ruleSetId stored on the run, so a schedule can always be traced to the rules it was built against
     */
    public record Resolved(Long ruleSetId, RuleSet rules) {}
}
