package com.dtc.transit.route.pattern;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.dtc.transit.route.route.DayType;

/**
 * How long a pattern takes to run during one window of the day.
 *
 * <p>Separate from the headway band because the two answer different questions: headway is how often a bus
 * departs, running time is how long it takes to get there. Both vary by time of day, and a single average
 * running time would make peak trips arrive late and off-peak buses wait at terminals.
 */
@Entity
@Table(name = "running_time_band")
public class RunningTimeBand {

    @EmbeddedId
    private Key key;

    @Column(name = "to_sec", nullable = false)
    private int toSec;

    @Column(name = "running_sec", nullable = false)
    private int runningSec;

    protected RunningTimeBand() {
        // for JPA
    }

    public RunningTimeBand(Long patternId, DayType dayType, int fromSec, int toSec, int runningSec) {
        this.key = new Key(patternId, dayType, fromSec);
        this.toSec = toSec;
        this.runningSec = runningSec;
    }

    /** Whether a departure at this time falls in this band. Half-open, so bands can abut exactly. */
    public boolean covers(int departureSec) {
        return departureSec >= key.fromSec() && departureSec < toSec;
    }

    public Long getPatternId() {
        return key.patternId();
    }

    public DayType getDayType() {
        return key.dayType();
    }

    public int getFromSec() {
        return key.fromSec();
    }

    public int getToSec() {
        return toSec;
    }

    public int getRunningSec() {
        return runningSec;
    }

    /** Composite key: one band per pattern, day type and start time. */
    @Embeddable
    public record Key(
            @Column(name = "pattern_id") Long patternId,
            @Enumerated(EnumType.STRING) @Column(name = "day_type") DayType dayType,
            @Column(name = "from_sec") Integer fromSec)
            implements Serializable {}
}
