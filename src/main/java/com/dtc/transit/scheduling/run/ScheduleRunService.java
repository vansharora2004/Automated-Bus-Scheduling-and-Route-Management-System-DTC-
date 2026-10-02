package com.dtc.transit.scheduling.run;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.common.error.ConflictException;
import com.dtc.transit.common.error.NotFoundException;
import com.dtc.transit.masterdata.depot.DepotRepository;
import com.dtc.transit.scheduling.engine.model.SchedulingMode;
import com.dtc.transit.scheduling.rules.RuleSetResolver;
import com.dtc.transit.security.DepotAccessEvaluator;

/**
 * Queuing and inspecting scheduling runs.
 *
 * <p>Queuing is cheap and returns immediately; the work happens in {@link RunWorker}. A synchronous endpoint
 * would hold an HTTP connection open for the length of a 45-depot build and time out behind any proxy.
 */
@Service
public class ScheduleRunService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRunService.class);

    private final ScheduleRunRepository runs;
    private final DepotRepository depots;
    private final RuleSetResolver ruleSets;
    private final DepotAccessEvaluator depotAccess;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ScheduleRunService(
            ScheduleRunRepository runs,
            DepotRepository depots,
            RuleSetResolver ruleSets,
            DepotAccessEvaluator depotAccess,
            ApplicationEventPublisher events,
            Clock clock) {
        this.runs = runs;
        this.depots = depots;
        this.ruleSets = ruleSets;
        this.depotAccess = depotAccess;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Queues a run, or returns the one an earlier identical request already queued.
     *
     * <p>Two different protections, for two different mistakes. An idempotency key makes a client's retry safe:
     * the same key returns the same run rather than a second one. Without a key, a second request for a depot-day
     * that is already queued or running is a conflict, because running the same work twice wastes minutes of CPU
     * to produce an answer one of the two will discard.
     *
     * @param idempotencyKey may be null, in which case only the conflict check applies
     * @return the run, and whether it was created by this call
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional
    public Queued queue(
            Long depotId, LocalDate serviceDate, SchedulingMode mode, Long seed, String idempotencyKey) {

        if (!depots.existsById(depotId)) {
            throw NotFoundException.of("Depot", depotId);
        }
        depotAccess.requireAccess(depotId, "Depot", depotId);

        if (idempotencyKey != null) {
            Optional<ScheduleRun> existing = runs.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                // Returned rather than refused. A client that lost the response to its first attempt is doing
                // exactly the right thing by retrying, and should get the original job handle back.
                log.debug("idempotency key {} already maps to run {}", idempotencyKey, existing.get().getId());
                return new Queued(existing.get(), false);
            }
        }

        runs.findActiveFor(depotId, serviceDate).ifPresent(active -> {
            throw new ConflictException(
                    "RUN_ALREADY_ACTIVE",
                    "A %s run for depot %d on %s already exists (%s). Wait for it or cancel it first."
                            .formatted(active.getStatus(), depotId, serviceDate, active.getId()));
        });

        // Resolved now, not when the worker picks the run up, so the run records the rules that were in force
        // when it was requested and a later rule change cannot retroactively alter what was asked for.
        var resolved = ruleSets.resolve(depotId, serviceDate);

        ScheduleRun run = new ScheduleRun(
                UUID.randomUUID(),
                depotId,
                serviceDate,
                mode == null ? SchedulingMode.LINKED : mode,
                resolved.ruleSetId(),
                seed == null ? serviceDate.toEpochDay() * 1000 + depotId : seed,
                idempotencyKey,
                actor());

        try {
            runs.saveAndFlush(run);
        } catch (DataIntegrityViolationException e) {
            // The partial unique index is the real guarantee. Two requests arriving in the same instant both
            // pass the check above, and one of them loses here.
            throw new ConflictException(
                    "RUN_ALREADY_ACTIVE",
                    "A run for depot %d on %s was queued concurrently".formatted(depotId, serviceDate));
        }

        events.publishEvent(AuditEvent.created(
                "SCHEDULE_RUN",
                run.getId(),
                """
                {"depotId":%d,"serviceDate":"%s","mode":"%s","ruleSetId":%d,"seed":%d}"""
                        .formatted(depotId, serviceDate, run.getMode(), run.getRuleSetId(), run.getSeed())));
        log.info("queued run {} for depot {} on {}", run.getId(), depotId, serviceDate);
        return new Queued(run, true);
    }

    @Transactional(readOnly = true)
    public ScheduleRun require(UUID runId) {
        ScheduleRun run = runs.findById(runId).orElseThrow(() -> NotFoundException.of("ScheduleRun", runId));
        depotAccess.requireAccess(run.getDepotId(), "ScheduleRun", runId);
        return run;
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public Page<ScheduleRun> listForDepot(Long depotId, Pageable pageable) {
        depotAccess.requireAccess(depotId, "Depot", depotId);
        return runs.findByDepotIdOrderByCreatedAtDesc(depotId, pageable);
    }

    /**
     * Cancels a run that has not finished.
     *
     * <p>A queued run is cancelled outright. A running one is also marked cancelled: the worker's own transaction
     * will fail to commit its completion, so nothing half-done survives.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional
    public ScheduleRun cancel(UUID runId) {
        ScheduleRun run = require(runId);
        if (run.getStatus().isFinished()) {
            throw new ConflictException(
                    "RUN_ALREADY_FINISHED", "Run %s is already %s".formatted(runId, run.getStatus()));
        }
        runs.markFailed(runId, "CANCELLED_BY_" + actor());
        events.publishEvent(AuditEvent.of("SCHEDULE_RUN_CANCELLED", "SCHEDULE_RUN", runId));
        runs.flush();
        return runs.findById(runId).orElseThrow();
    }

    private static String actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : authentication.getName();
    }

    /** @param created false when an idempotency key matched an existing run, which makes the response a 200 */
    public record Queued(ScheduleRun run, boolean created) {}
}
