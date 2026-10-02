package com.dtc.transit.timetable.headway;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.dtc.transit.route.route.Direction;

/**
 * How often a route runs during one window of the service day.
 *
 * <p>Bands are how a planner actually thinks: "every 6 minutes in the morning peak, every 15 off-peak".
 * Trips are generated from them rather than typed out, which is the point of the whole phase.
 *
 * <p>Times are service-day seconds, so a band may legitimately run past 86,400 for a late-night service.
 */
@Entity
@Table(name = "headway_band")
public class HeadwayBand {

    @EmbeddedId
    private Key key;

    @Column(name = "to_sec", nullable = false)
    private int toSec;

    @Column(name = "headway_sec", nullable = false)
    private int headwaySec;

    protected HeadwayBand() {
        // for JPA
    }

    public HeadwayBand(Long timetableId, Direction direction, int fromSec, int toSec, int headwaySec) {
        this.key = new Key(timetableId, direction, fromSec);
        this.toSec = toSec;
        this.headwaySec = headwaySec;
    }

    /** Length of the window in seconds. */
    public int windowSeconds() {
        return toSec - key.fromSec();
    }

    /**
     * How many departures this band produces.
     *
     * <p>Ceiling, not floor: a band is inclusive of its start, so a 60 minute window at a 25 minute
     * headway departs at 0, 25 and 50, which is three trips rather than two.
     */
    public int expectedTripCount() {
        return (int) Math.ceil((double) windowSeconds() / headwaySec);
    }

    public boolean overlaps(HeadwayBand other) {
        return key.direction() == other.key.direction()
                && key.fromSec() < other.toSec
                && toSec > other.key.fromSec();
    }

    public Long getTimetableId() {
        return key.timetableId();
    }

    public Direction getDirection() {
        return key.direction();
    }

    public int getFromSec() {
        return key.fromSec();
    }

    public int getToSec() {
        return toSec;
    }

    public int getHeadwaySec() {
        return headwaySec;
    }

    /** Composite key: one band per timetable, direction and start time. */
    @Embeddable
    public record Key(
            @Column(name = "timetable_id") Long timetableId,
            @Enumerated(EnumType.STRING) @Column(name = "direction") Direction direction,
            @Column(name = "from_sec") Integer fromSec)
            implements Serializable {}
}
