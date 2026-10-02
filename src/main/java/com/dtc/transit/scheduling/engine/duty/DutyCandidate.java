package com.dtc.transit.scheduling.engine.duty;

import java.util.List;

import com.dtc.transit.scheduling.engine.model.BlockEvent;

/**
 * A proposed duty, before anything decides whether it is legal.
 *
 * <p>The builder creates thousands of these and keeps a handful. Carrying the events rather than just the times
 * is what lets the break and continuous-work rules be checked at all: those depend on where the idle periods
 * fall inside the duty, not merely on how long it is.
 *
 * @param segments the stretches of bus work this duty covers, in time order. One in linked mode, where the crew
 *     stays with one bus; several once Phase 8 allows a crew to change buses.
 */
public record DutyCandidate(List<WorkSegment> segments, DutyMetrics metrics) {

    public DutyCandidate {
        segments = List.copyOf(segments);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("a duty candidate with no work in it is not a duty");
        }
    }

    /** Every event the duty covers, across all its segments, in time order. */
    public List<BlockEvent> events() {
        return segments.stream().flatMap(segment -> segment.events().stream()).toList();
    }

    public List<Integer> blockNos() {
        return segments.stream().map(WorkSegment::blockNo).distinct().sorted().toList();
    }

    /** How many separate stretches of work the duty has, which the soft piece-count rule limits. */
    public int pieceCount() {
        return segments.size();
    }

    /**
     * One stretch of one bus's work.
     *
     * @param fromEventSeq and toEventSeq delimit the stretch in the block's own event numbering, which is what
     *     pins a cut to an actual relief opportunity rather than to a bare timestamp
     */
    public record WorkSegment(
            int blockNo,
            int fromEventSeq,
            int toEventSeq,
            int startSec,
            int endSec,
            Long startReliefStopId,
            Long endReliefStopId,
            List<BlockEvent> events) {

        public WorkSegment {
            events = List.copyOf(events);
        }

        public int lengthSec() {
            return endSec - startSec;
        }
    }
}
