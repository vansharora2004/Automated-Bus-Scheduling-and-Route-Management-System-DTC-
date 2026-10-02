package com.dtc.transit.scheduling.run;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Takes runs off the queue and hands them to the executor.
 *
 * <p>The queue is a database table rather than a broker, because the work is already transactional with the data
 * it reads and writes. A broker would add a second system that can disagree with the database about whether a job
 * ran, and reconciling the two is harder than the queue itself.
 *
 * <p>This class holds no transaction of its own. The claim, the execution and the failure record are three
 * separate transactional boundaries on three separate beans, which is the only way the annotations actually apply.
 */
@Service
public class RunWorker {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);

    private final ScheduleRunRepository runs;
    private final RunBookkeeper bookkeeper;
    private final RunExecutor executor;
    private final RunProgressBroadcaster progress;
    private final String workerName;

    public RunWorker(
            ScheduleRunRepository runs,
            RunBookkeeper bookkeeper,
            RunExecutor executor,
            RunProgressBroadcaster progress) {
        this.runs = runs;
        this.bookkeeper = bookkeeper;
        this.executor = executor;
        this.progress = progress;
        this.workerName = defaultWorkerName();
    }

    /**
     * Claims one run and executes it.
     *
     * <p>The claim commits before the work starts. Holding the claiming transaction open for the whole run would
     * keep a row lock for minutes, which is the situation {@code SKIP LOCKED} exists to avoid.
     *
     * @return the run that was executed, or empty when the queue was empty
     */
    public Optional<UUID> pollOnce() {
        Optional<UUID> claimed = bookkeeper.claimNext(workerName);
        claimed.ifPresent(this::execute);
        return claimed;
    }

    /**
     * Runs one claimed job to completion or failure.
     *
     * <p>Catches everything. An escaping exception would leave the row saying {@code RUNNING} until the reaper
     * noticed, which means two minutes of a depot-day slot held for a run that is already dead.
     */
    public void execute(UUID runId) {
        ScheduleRun run = runs.findById(runId).orElse(null);
        if (run == null) {
            log.warn("run {} vanished between claim and execution", runId);
            return;
        }

        try {
            var result = executor.execute(run, workerName);
            progress.completed(runId, result.metrics());
        } catch (Exception e) {
            log.error("run {} failed", runId, e);
            // The message reaches the client, not the stack trace: a scheduler needs to know the depot has no
            // fleet, and a Java trace would only expose internals (edge case EC-SEC-12).
            String reason = describe(e);
            bookkeeper.fail(runId, reason);
            progress.failed(runId, reason);
        }
    }

    private static String describe(Exception cause) {
        String message = cause.getMessage();
        String text = message == null ? cause.getClass().getSimpleName() : message;
        return text.length() > 1000 ? text.substring(0, 1000) : text;
    }

    /**
     * A name that identifies this process.
     *
     * <p>Host and process id, so a reaped run's log says which machine lost it. A random id would be correct and
     * useless at the moment anyone actually needs it.
     */
    private static String defaultWorkerName() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        return host + '/' + ProcessHandle.current().pid();
    }

    public String workerName() {
        return workerName;
    }
}
