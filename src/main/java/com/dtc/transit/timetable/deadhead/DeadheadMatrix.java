package com.dtc.transit.timetable.deadhead;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Travel times between points for non-revenue movement.
 *
 * <p>Measured values are used where they exist. Where they do not, a straight-line estimate is produced
 * rather than refusing to answer, because a missing pair would otherwise stop a whole depot from being
 * scheduled. Every estimate is flagged, so nobody mistakes it for a survey.
 */
@Service
public class DeadheadMatrix {

    private static final Logger log = LoggerFactory.getLogger(DeadheadMatrix.class);

    /**
     * Road distance as a multiple of straight-line distance.
     *
     * <p>Roads do not run in straight lines. 1.3 is a conventional urban figure; too low and the scheduler
     * builds blocks that cannot be driven, which is the more dangerous direction of error.
     */
    public static final double DETOUR_FACTOR = 1.3;

    /** Assumed average speed for an estimated move, in km/h. Deliberately pessimistic for city traffic. */
    public static final double ESTIMATED_SPEED_KMH = 20;

    /** Floor for an estimate, so two adjacent stops do not come out as a zero-second move. */
    public static final int MINIMUM_ESTIMATE_SECONDS = 180;

    private final DeadheadRepository deadheads;

    public DeadheadMatrix(DeadheadRepository deadheads) {
        this.deadheads = deadheads;
    }

    /**
     * Travel time between two points at a given time of day.
     *
     * <p>The band is chosen by departure time. A move starting in the morning peak keeps the peak time even
     * if it finishes after it, because that is the traffic it is actually sitting in (edge case EC-TT-11).
     *
     * <p>Not read-only, although it usually only reads. A missing pair is estimated and the estimate is
     * stored, and a read-only transaction would make the database refuse that insert. Self-invocation also
     * means the annotation on {@code estimate} does not apply when it is called from here, so this boundary
     * is the only one that counts.
     *
     * @return the measured or estimated time, never empty for two known stops
     */
    @Transactional
    public Lookup travelTime(Long fromStopId, Long toStopId, int departureSec) {
        if (fromStopId.equals(toStopId)) {
            // Same place: no movement, and importantly not an estimate either.
            return new Lookup(0, 0.0, false);
        }

        Optional<Deadhead> measured = deadheads.findForBand(fromStopId, toStopId, departureSec);
        if (measured.isPresent()) {
            Deadhead value = measured.get();
            return new Lookup(value.getTravelSec(), value.getDistanceM(), value.isEstimated());
        }

        return estimate(fromStopId, toStopId);
    }

    /**
     * Builds an estimate from the straight-line distance between two stops.
     *
     * <p>Persisted as well as returned, so the same estimate is reused rather than recomputed on every
     * lookup, and so a planner can list every estimated pair and replace them with surveyed values.
     */
    @Transactional
    public Lookup estimate(Long fromStopId, Long toStopId) {
        Double straightLineMetres = deadheads.straightLineDistance(fromStopId, toStopId).orElse(null);
        if (straightLineMetres == null) {
            throw new com.dtc.transit.common.error.NotFoundException(
                    "Cannot estimate a deadhead between stops " + fromStopId + " and " + toStopId
                            + ": one of them has no location");
        }

        double roadMetres = straightLineMetres * DETOUR_FACTOR;
        int seconds = (int) Math.max(
                MINIMUM_ESTIMATE_SECONDS, Math.round((roadMetres / 1000.0) / ESTIMATED_SPEED_KMH * 3600));

        log.debug("estimated deadhead {} -> {} as {} s over {} m", fromStopId, toStopId, seconds, roadMetres);

        // Band 0 so the estimate applies all day. A time-banded estimate would imply knowledge of traffic
        // patterns that an estimate by definition does not have.
        deadheads.save(new Deadhead(fromStopId, toStopId, 0, seconds, roadMetres, true));
        return new Lookup(seconds, roadMetres, true);
    }

    /** Every estimated pair, so planners can see which numbers still need surveying. */
    @Transactional(readOnly = true)
    public List<Deadhead> estimatedPairs() {
        return deadheads.findByEstimatedTrue();
    }

    /**
     * @param travelSeconds time to make the move
     * @param estimated true when derived rather than measured, which the scheduler surfaces as a soft
     *     conflict
     */
    public record Lookup(int travelSeconds, Double distanceMetres, boolean estimated) {}
}
