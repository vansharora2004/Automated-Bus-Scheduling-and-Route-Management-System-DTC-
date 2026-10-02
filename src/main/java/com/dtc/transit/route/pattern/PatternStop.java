package com.dtc.transit.route.pattern;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A stop in a pattern's ordered sequence.
 *
 * <p>{@code distFromStartM} is how far along the line the stop sits, measured by the database. It drives
 * the ordering check: distances must increase with the sequence number, or the stop list disagrees with
 * the drawn path (edge case EC-GEO-14).
 */
@Entity
@Table(name = "pattern_stop")
public class PatternStop {

    @EmbeddedId
    private Key key;

    @Column(name = "stop_id", nullable = false)
    private Long stopId;

    @Column(name = "dist_from_start_m")
    private Double distFromStartM;

    protected PatternStop() {
        // for JPA
    }

    public PatternStop(Long patternId, int seq, Long stopId, Double distFromStartM) {
        this.key = new Key(patternId, seq);
        this.stopId = stopId;
        this.distFromStartM = distFromStartM;
    }

    public Long getPatternId() {
        return key.patternId();
    }

    public int getSeq() {
        return key.seq();
    }

    public Long getStopId() {
        return stopId;
    }

    public Double getDistFromStartM() {
        return distFromStartM;
    }

    /** Composite key: one row per pattern and position. */
    @Embeddable
    public record Key(@Column(name = "pattern_id") Long patternId, @Column(name = "seq") Integer seq)
            implements Serializable {}
}
