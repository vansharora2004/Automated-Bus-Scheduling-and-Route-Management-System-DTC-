package com.dtc.transit.scheduling.engine.model;

/**
 * A closed-open window of service-day seconds.
 *
 * <p>Half-open so that back-to-back windows do not count as overlapping: a bus released at 10:00 and
 * booked again from 10:00 is available, not double-booked.
 */
public record TimeWindow(int fromSec, int toSec) {

    public TimeWindow {
        if (toSec <= fromSec) {
            throw new IllegalArgumentException("window ends at or before it starts: " + fromSec + ".." + toSec);
        }
    }

    public boolean overlaps(TimeWindow other) {
        return fromSec < other.toSec && other.fromSec < toSec;
    }

    public boolean contains(int sec) {
        return sec >= fromSec && sec < toSec;
    }

    public int lengthSec() {
        return toSec - fromSec;
    }
}
