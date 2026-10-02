package com.dtc.transit.scheduling.engine.model;

/**
 * An unbroken stretch of one bus's work that one crew could take.
 *
 * <p>Delimited by event sequence rather than by time, because the cut has to fall exactly on a relief
 * opportunity. Storing times alone would let a later edit move a cut to a point where no crew can reach the
 * bus.
 *
 * @param blockNo the block this piece belongs to, which is also the bus the crew stays with in linked mode
 * @param startReliefStopId where the crew takes the bus over, null when that is the depot
 * @param endReliefStopId where the crew hands it on, null when that is the depot
 */
public record PieceOfWorkPlan(
        int blockNo,
        int fromEventSeq,
        int toEventSeq,
        int startSec,
        int endSec,
        Long startReliefStopId,
        Long endReliefStopId) {

    public PieceOfWorkPlan {
        if (toEventSeq < fromEventSeq) {
            throw new IllegalArgumentException("piece of work ends before it starts, in event order");
        }
        if (endSec <= startSec) {
            throw new IllegalArgumentException("piece of work ends at or before it starts");
        }
    }

    public int lengthSec() {
        return endSec - startSec;
    }
}
