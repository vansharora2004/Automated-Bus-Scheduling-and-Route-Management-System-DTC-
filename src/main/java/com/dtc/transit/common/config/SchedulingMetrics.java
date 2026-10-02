package com.dtc.transit.common.config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The application's own metrics.
 *
 * <p>Micrometer gives JVM, pool and HTTP metrics for free. These are the ones only this system can report: how
 * long a scheduling run took, how often runs fail, what kinds of conflict are being produced, and how large a
 * page clients are actually asking for.
 *
 * <p>Tag cardinality is kept deliberately low. A tag per depot would be 45 series per metric, a tag per run id
 * would be unbounded, and an unbounded tag is how a monitoring system is brought down by the thing it monitors.
 * Mode and conflict type are both small closed sets.
 */
@Component
public class SchedulingMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger queuedRuns = new AtomicInteger();
    private final AtomicInteger runningRuns = new AtomicInteger();

    public SchedulingMetrics(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder("scheduling.runs.queued", queuedRuns, AtomicInteger::get)
                .description("Runs waiting for a worker")
                .register(registry);
        Gauge.builder("scheduling.runs.running", runningRuns, AtomicInteger::get)
                .description("Runs currently being executed")
                .register(registry);
    }

    /**
     * How long a completed run took, tagged by mode.
     *
     * <p>Linked and unlinked have genuinely different cost profiles — unlinked spends a search budget — so one
     * timer covering both would have a bimodal distribution and a meaningless p95.
     */
    public void recordRunDuration(String mode, Duration duration) {
        Timer.builder("scheduling.run.duration")
                .description("Wall-clock time for one depot-day scheduling run")
                .tag("mode", mode == null ? "unknown" : mode)
                .register(registry)
                .record(duration);
    }

    /** Counted rather than timed: a failure has no meaningful duration, and the rate is what wakes somebody. */
    public void recordRunFailure(String reason) {
        Counter.builder("scheduling.run.failures")
                .description("Scheduling runs that ended in failure")
                .tag("reason", shortReason(reason))
                .register(registry)
                .increment();
    }

    /**
     * Conflicts produced, by type.
     *
     * <p>The shape of this over time is the most useful operational signal in the system: a sudden rise in
     * {@code UNASSIGNED_DUTY} means a staffing problem, and a rise in {@code EV_RANGE_EXCEEDED} means a fleet
     * one.
     */
    public void recordConflict(String type, int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("scheduling.conflicts")
                .description("Conflicts produced by scheduling runs")
                .tag("type", type == null ? "unknown" : type)
                .register(registry)
                .increment(count);
    }

    /** Overlap analysis duration, which is the most expensive spatial query in the system. */
    public Timer overlapTimer() {
        return Timer.builder("route.overlap.duration")
                .description("Time to run an overlap analysis")
                .register(registry);
    }

    /**
     * What page size clients actually request.
     *
     * <p>Recorded because the clamping is invisible otherwise: a client asking for 5,000 and silently getting 100
     * will believe it has everything, and this is the metric that shows somebody is paging wrong.
     */
    public void recordPageSize(int requested) {
        registry.summary("api.page.size").record(requested);
    }

    public void setQueuedRuns(int value) {
        queuedRuns.set(value);
    }

    public void setRunningRuns(int value) {
        runningRuns.set(value);
    }

    /**
     * Collapses a failure message into a bounded tag.
     *
     * <p>Failure messages contain ids and dates. Using one as a tag value would create a new time series per
     * failure, which is the classic way to run a monitoring system out of memory.
     */
    private static String shortReason(String reason) {
        if (reason == null) {
            return "unknown";
        }
        if (reason.startsWith("WORKER_LOST")) {
            return "worker_lost";
        }
        if (reason.startsWith("CANCELLED")) {
            return "cancelled";
        }
        if (reason.contains("NO_RULE_SET") || reason.contains("rule set")) {
            return "rule_set";
        }
        if (reason.contains("DEPOT_HAS_NO_FLEET") || reason.contains("no usable buses")) {
            return "no_fleet";
        }
        return "other";
    }
}
