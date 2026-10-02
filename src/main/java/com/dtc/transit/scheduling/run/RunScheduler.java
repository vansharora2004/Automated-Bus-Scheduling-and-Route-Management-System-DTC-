package com.dtc.transit.scheduling.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timers: poll the queue, and reap runs whose worker died.
 *
 * <p>Separated from the worker so that a test can drive {@link RunWorker#pollOnce()} directly and deterministically
 * instead of waiting for a timer to fire. Scheduling in the same class as the logic makes every test of that logic
 * a race.
 *
 * <p>Both timers can be turned off with {@code app.scheduling.worker.enabled=false}, which is what the integration
 * tests do: a background poller claiming runs out from under a test would make assertions about queue state
 * meaningless.
 */
@Component
public class RunScheduler {

    private static final Logger log = LoggerFactory.getLogger(RunScheduler.class);

    private final RunWorker worker;
    private final RunBookkeeper bookkeeper;
    private final boolean enabled;

    public RunScheduler(
            RunWorker worker,
            RunBookkeeper bookkeeper,
            @Value("${app.scheduling.worker.enabled:true}") boolean enabled) {
        this.worker = worker;
        this.bookkeeper = bookkeeper;
        this.enabled = enabled;
        log.info("run worker polling is {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Polls every two seconds.
     *
     * <p>Fixed delay rather than a fixed rate, so a run taking a minute does not queue up thirty overdue polls
     * behind it.
     */
    @Scheduled(fixedDelayString = "${app.scheduling.worker.poll-interval-ms:2000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            worker.pollOnce();
        } catch (Exception e) {
            // A failure here must not kill the timer: Spring stops rescheduling a task that throws.
            log.error("the run poller failed; it will try again", e);
        }
    }

    @Scheduled(fixedDelayString = "${app.scheduling.worker.reap-interval-ms:30000}")
    public void reap() {
        if (!enabled) {
            return;
        }
        try {
            bookkeeper.reapStaleRuns();
        } catch (Exception e) {
            log.error("the reaper failed; it will try again", e);
        }
    }
}
