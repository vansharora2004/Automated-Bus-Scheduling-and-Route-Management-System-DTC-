package com.dtc.transit.scheduling.duty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * An unbroken stretch of one bus's day that one crew takes.
 *
 * <p>Delimited by event sequence as well as by time, because a cut has to fall exactly on a relief opportunity.
 * Times alone would let a later edit move a cut to a point no crew can reach.
 */
@Entity
@Table(name = "piece_of_work")
public class PieceOfWork {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "piece_of_work_seq")
    @SequenceGenerator(name = "piece_of_work_seq", sequenceName = "piece_of_work_seq", allocationSize = 50)
    private Long id;

    @Column(name = "block_id", nullable = false)
    private Long blockId;

    @Column(name = "from_event_seq", nullable = false)
    private int fromEventSeq;

    @Column(name = "to_event_seq", nullable = false)
    private int toEventSeq;

    @Column(name = "start_sec", nullable = false)
    private int startSec;

    @Column(name = "end_sec", nullable = false)
    private int endSec;

    /** Null means the depot, which is where most pieces start and end. */
    @Column(name = "start_relief_stop_id")
    private Long startReliefStopId;

    @Column(name = "end_relief_stop_id")
    private Long endReliefStopId;

    protected PieceOfWork() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public Long getBlockId() {
        return blockId;
    }

    public int getFromEventSeq() {
        return fromEventSeq;
    }

    public int getToEventSeq() {
        return toEventSeq;
    }

    public int getStartSec() {
        return startSec;
    }

    public int getEndSec() {
        return endSec;
    }

    public Long getStartReliefStopId() {
        return startReliefStopId;
    }

    public Long getEndReliefStopId() {
        return endReliefStopId;
    }
}
