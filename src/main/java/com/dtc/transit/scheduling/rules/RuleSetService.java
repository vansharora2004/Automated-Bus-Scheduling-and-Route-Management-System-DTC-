package com.dtc.transit.scheduling.rules;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.common.error.ConflictException;
import com.dtc.transit.common.error.NotFoundException;
import com.dtc.transit.masterdata.depot.DepotRepository;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reading and versioning rule sets.
 *
 * <p>Updating a rule set creates a new version rather than editing one. A schedule already built against the old
 * values has to stay explicable: if the rules it was judged by could be rewritten underneath it, nobody could
 * answer why a duty that is now illegal was approved last week.
 *
 * <p>Every write binds the submitted rules to the typed record first. That is where validation happens — the
 * record refuses a contradictory set, such as a continuous-work limit above the daily one — so an unusable rule
 * set is rejected at the endpoint instead of failing a run an hour later.
 */
@Service
public class RuleSetService {

    private static final Logger log = LoggerFactory.getLogger(RuleSetService.class);

    private final StoredRuleSetRepository repository;
    private final RuleSetResolver resolver;
    private final DepotRepository depots;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public RuleSetService(
            StoredRuleSetRepository repository,
            RuleSetResolver resolver,
            DepotRepository depots,
            ObjectMapper objectMapper,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.resolver = resolver;
        this.depots = depots;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Page<StoredRuleSet> list(Pageable pageable) {
        return repository.findAllByOrderByEffectiveFromDescIdDesc(pageable);
    }

    @Transactional(readOnly = true)
    public StoredRuleSet require(Long id) {
        return repository.findById(id).orElseThrow(() -> NotFoundException.of("RuleSet", id));
    }

    /** The typed view of a stored rule set, which is what a client needs to edit one. */
    @Transactional(readOnly = true)
    public RuleSet rulesOf(Long id) {
        return resolver.bind(require(id));
    }

    /** Which rule set a depot-day would actually be scheduled against, and the values it carries. */
    @Transactional(readOnly = true)
    public RuleSetResolver.Resolved resolve(Long depotId, LocalDate serviceDate) {
        return resolver.resolve(depotId, serviceDate);
    }

    @Transactional(readOnly = true)
    public List<StoredRuleSet> forDepot(Long depotId) {
        return repository.findByDepotIdOrderByEffectiveFromDesc(depotId);
    }

    /**
     * Creates a new version of the rules.
     *
     * @param depotId null for the global set, which is the fallback for every depot without one of its own
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public StoredRuleSet create(String name, Long depotId, LocalDate effectiveFrom, RuleSet rules) {
        if (depotId != null && !depots.existsById(depotId)) {
            throw NotFoundException.of("Depot", depotId);
        }
        validate(rules);

        StoredRuleSet stored = new StoredRuleSet(name, depotId, effectiveFrom, asJson(rules));
        try {
            repository.saveAndFlush(stored);
        } catch (DataIntegrityViolationException e) {
            // A partial unique index per scope. Two rule sets with the same scope and date would make resolution
            // ambiguous, and the engine would silently pick one of them.
            throw new ConflictException(
                    "RULE_SET_ALREADY_EFFECTIVE",
                    "A %s rule set already takes effect on %s. Choose another date or edit that one."
                            .formatted(depotId == null ? "global" : "depot " + depotId, effectiveFrom));
        }

        events.publishEvent(AuditEvent.created(
                "RULE_SET",
                stored.getId(),
                """
                {"name":"%s","depotId":%s,"effectiveFrom":"%s"}"""
                        .formatted(name, depotId == null ? "null" : depotId, effectiveFrom)));
        log.info(
                "created rule set {} ({}) effective {}",
                stored.getId(),
                depotId == null ? "global" : "depot " + depotId,
                effectiveFrom);
        return stored;
    }

    /**
     * Supersedes a rule set with a new version.
     *
     * <p>A create in disguise, and deliberately so: the endpoint is a PUT because that is how a client thinks
     * about it, but nothing is overwritten. The new version carries its own effective date, and the old one stays
     * readable for the schedules that were built under it.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public StoredRuleSet replace(Long id, LocalDate effectiveFrom, RuleSet rules) {
        StoredRuleSet existing = require(id);
        if (!effectiveFrom.isAfter(existing.getEffectiveFrom())) {
            throw new BusinessRuleException(
                    "RULE_SET_NOT_LATER",
                    "A new version must take effect after %s, the date of the version it replaces"
                            .formatted(existing.getEffectiveFrom()));
        }
        return create(existing.getName(), existing.getDepotId(), effectiveFrom, rules);
    }

    /**
     * Checks a rule set without storing it.
     *
     * <p>Exists because the interesting failures are the contradictions, and an operator editing twenty-three
     * numbers deserves to find out before a run does.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','PLANNER','SCHEDULER')")
    public void validate(RuleSet rules) {
        // The record's own constructor does the checking, so reaching here means it passed. The call is kept
        // explicit so the intent survives a future refactor that moves validation elsewhere.
        if (rules == null) {
            throw new BusinessRuleException("INVALID_RULE_SET", "No rules were supplied");
        }
    }

    private String asJson(RuleSet rules) {
        try {
            return objectMapper.writeValueAsString(rules);
        } catch (Exception e) {
            throw new IllegalStateException("a rule set that binds must also serialise", e);
        }
    }
}
