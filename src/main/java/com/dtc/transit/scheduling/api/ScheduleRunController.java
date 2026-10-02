package com.dtc.transit.scheduling.api;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.dtc.transit.common.paging.PageResponse;
import com.dtc.transit.scheduling.engine.model.SchedulingMode;
import com.dtc.transit.scheduling.run.RunProgressBroadcaster;
import com.dtc.transit.scheduling.run.RunStatus;
import com.dtc.transit.scheduling.run.ScheduleRun;
import com.dtc.transit.scheduling.run.ScheduleRunService;

/**
 * Scheduling runs: queue one, watch it, read the result.
 *
 * <p>Asynchronous by design. A 45-depot build takes minutes, and an endpoint that waited for it would hold an HTTP
 * connection open past every proxy's timeout and give the client nothing to retry safely.
 */
@RestController
@RequestMapping("/api/v1/schedule-runs")
public class ScheduleRunController {

    /** How long a progress stream stays open before the client must reconnect, in milliseconds. */
    public static final long EVENT_STREAM_TIMEOUT_MS = 10 * 60 * 1000L;

    private final ScheduleRunService runService;
    private final RunProgressBroadcaster progress;

    public ScheduleRunController(ScheduleRunService runService, RunProgressBroadcaster progress) {
        this.runService = runService;
        this.progress = progress;
    }

    /**
     * Queues a run.
     *
     * <p>202 with a {@code Location} header when the run is new, 200 when an {@code Idempotency-Key} matched one
     * that already exists. The distinction matters to a client retrying after a lost response: 200 tells it the
     * work was already accepted rather than that it has just created a second job.
     */
    @PostMapping
    public ResponseEntity<RunResponse> queue(
            @Valid @RequestBody QueueRunRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {

        var queued = runService.queue(
                request.depotId(), request.serviceDate(), request.mode(), request.seed(), idempotencyKey);
        RunResponse body = RunResponse.from(queued.run());

        return queued.created()
                ? ResponseEntity.accepted()
                        .location(URI.create("/api/v1/schedule-runs/" + queued.run().getId()))
                        .body(body)
                : ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public RunResponse get(@PathVariable UUID id) {
        return RunResponse.from(runService.require(id));
    }

    @GetMapping
    public PageResponse<RunResponse> list(
            @RequestParam Long depotId, @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(runService.listForDepot(depotId, pageable), RunResponse::from);
    }

    /**
     * Progress as a Server-Sent Events stream.
     *
     * <p>A stream rather than polling, because a client watching a two-minute build should not have to choose
     * between a stale display and hammering the endpoint. The run row still carries {@code progress}, so a client
     * that cannot use SSE loses nothing but immediacy.
     *
     * <p>Requires the run to exist and be visible to the caller first: subscribing is a read, and it is authorised
     * like one.
     */
    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id) {
        runService.require(id);
        return progress.subscribe(id, EVENT_STREAM_TIMEOUT_MS);
    }

    @PostMapping("/{id}/cancel")
    public RunResponse cancel(@PathVariable UUID id) {
        return RunResponse.from(runService.cancel(id));
    }

    /**
     * @param mode defaults to LINKED, where a crew stays with one bus
     * @param seed omit for a seed derived from the depot and date, which keeps repeated runs reproducible
     */
    public record QueueRunRequest(
            @NotNull Long depotId, @NotNull LocalDate serviceDate, SchedulingMode mode, Long seed) {}

    /**
     * @param metrics the run's own numbers as stored, or null while it has not finished
     * @param scheduleId the schedule the run produced, null until it completes
     */
    public record RunResponse(
            UUID id,
            Long depotId,
            LocalDate serviceDate,
            SchedulingMode mode,
            Long ruleSetId,
            long seed,
            RunStatus status,
            int progress,
            String claimedBy,
            Instant heartbeatAt,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Long scheduleId,
            String error,
            com.fasterxml.jackson.databind.JsonNode metrics) {

        static RunResponse from(ScheduleRun run) {
            return new RunResponse(
                    run.getId(),
                    run.getDepotId(),
                    run.getServiceDate(),
                    run.getMode(),
                    run.getRuleSetId(),
                    run.getSeed(),
                    run.getStatus(),
                    run.getProgress(),
                    run.getClaimedBy(),
                    run.getHeartbeatAt(),
                    run.getCreatedAt(),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    run.getScheduleId(),
                    run.getError(),
                    RawJson.parse(run.getMetrics()));
        }
    }
}
