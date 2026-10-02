package com.dtc.transit.scheduling.engine.model;

/**
 * One leg of a block.
 *
 * @param tripId set only on {@link BlockEventType#TRIP} events
 * @param reliefOpportunity whether a crew could change over at the end of this leg; computed during
 *     scheduling because it depends on the rule set in force for the run
 */
public record BlockEvent(
        int seq,
        BlockEventType type,
        Long tripId,
        Long fromStopId,
        Long toStopId,
        int startSec,
        int endSec,
        double distanceM,
        boolean reliefOpportunity) {

    public BlockEvent {
        if (endSec < startSec) {
            throw new IllegalArgumentException("event " + seq + " of type " + type + " ends before it starts");
        }
        if ((type == BlockEventType.TRIP) != (tripId != null)) {
            // Keeping these in step means a trip-coverage query can simply count TRIP events, rather than
            // defending against a trip id on a layover.
            throw new IllegalArgumentException("a trip id belongs on a TRIP event and nowhere else: " + type);
        }
    }

    public int durationSec() {
        return endSec - startSec;
    }

    /** A copy marked as a relief opportunity, used once the relief finder has run. */
    public BlockEvent asReliefOpportunity() {
        return new BlockEvent(seq, type, tripId, fromStopId, toStopId, startSec, endSec, distanceM, true);
    }

    public BlockEvent withSeq(int newSeq) {
        return new BlockEvent(
                newSeq, type, tripId, fromStopId, toStopId, startSec, endSec, distanceM, reliefOpportunity);
    }
}
