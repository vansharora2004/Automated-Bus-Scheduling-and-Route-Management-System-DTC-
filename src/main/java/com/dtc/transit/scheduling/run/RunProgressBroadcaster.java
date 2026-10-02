package com.dtc.transit.scheduling.run;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.dtc.transit.scheduling.engine.model.ScheduleMetrics;

/**
 * Pushes run progress to subscribed clients over Server-Sent Events.
 *
 * <p>In-memory, and deliberately so. Progress is ephemeral: a client that misses it can poll the run, and the
 * authoritative state is the row in the database. Persisting progress events would add writes during the hot part
 * of a run to deliver information that is worthless a second later.
 *
 * <p>This also means progress is only delivered by the instance running the job. With several instances a client
 * may see nothing until completion, which is why the run row carries {@code progress} as well.
 */
@Component
public class RunProgressBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RunProgressBroadcaster.class);

    private final Map<UUID, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /** Registers a client. The emitter is removed on completion, timeout or error, so the map cannot grow. */
    public SseEmitter subscribe(UUID runId, long timeoutMillis) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        subscribers.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable remove = () -> {
            List<SseEmitter> forRun = subscribers.get(runId);
            if (forRun != null) {
                forRun.remove(emitter);
                if (forRun.isEmpty()) {
                    subscribers.remove(runId);
                }
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        return emitter;
    }

    public void progress(UUID runId, int percent) {
        send(runId, "progress", new ProgressEvent(runId, percent), false);
    }

    public void completed(UUID runId, ScheduleMetrics metrics) {
        send(
                runId,
                "completed",
                new CompletedEvent(
                        runId,
                        100,
                        metrics.blocks(),
                        metrics.tripsCovered(),
                        metrics.tripsUncovered(),
                        metrics.hardConflicts(),
                        metrics.elapsedMillis()),
                true);
    }

    public void failed(UUID runId, String error) {
        send(runId, "failed", new FailedEvent(runId, error), true);
    }

    /**
     * Sends one event to every subscriber of a run.
     *
     * <p>The payload is a record, left to the framework's own serialiser rather than built as a string. An error
     * message can contain quotes and newlines, and hand-escaping JSON is the kind of thing that works until the
     * first message that needs it.
     */
    private void send(UUID runId, String event, Object payload, boolean last) {
        List<SseEmitter> forRun = subscribers.get(runId);
        if (forRun == null) {
            return;
        }
        for (SseEmitter emitter : forRun) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event)
                        .data(payload, org.springframework.http.MediaType.APPLICATION_JSON));
                if (last) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                // A client that has gone away must never fail the run that was telling it something.
                log.debug("dropping a progress subscriber for run {}: {}", runId, e.getMessage());
                emitter.completeWithError(e);
            }
        }
    }

    /** How many clients are watching a run. Used by tests; never a decision input. */
    public int subscriberCount(UUID runId) {
        List<SseEmitter> forRun = subscribers.get(runId);
        return forRun == null ? 0 : forRun.size();
    }

    record ProgressEvent(UUID runId, int progress) {}

    record CompletedEvent(
            UUID runId,
            int progress,
            int blocks,
            int tripsCovered,
            int tripsUncovered,
            int hardConflicts,
            long elapsedMillis) {}

    record FailedEvent(UUID runId, String error) {}
}
