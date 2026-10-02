package com.dtc.transit.timetable.deadhead;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Deadhead lookups, and the list of pairs that are still estimates. */
@RestController
@RequestMapping("/api/v1/deadheads")
public class DeadheadController {

    private final DeadheadMatrix matrix;

    public DeadheadController(DeadheadMatrix matrix) {
        this.matrix = matrix;
    }

    /**
     * Travel time between two stops at a time of day.
     *
     * <p>A lookup may create a stored estimate as a side effect, which is why this is not a pure read. The
     * alternative, recomputing the same estimate on every call, would make two lookups of the same pair
     * disagree once the formula changed.
     */
    @GetMapping
    public DeadheadMatrix.Lookup lookup(
            @RequestParam Long fromStopId,
            @RequestParam Long toStopId,
            @RequestParam(defaultValue = "0") int departureSec) {
        return matrix.travelTime(fromStopId, toStopId, departureSec);
    }

    /** Every pair whose number is derived rather than surveyed, so planners can see what needs measuring. */
    @GetMapping("/estimated")
    public List<EstimatedPairResponse> estimated() {
        return matrix.estimatedPairs().stream().map(EstimatedPairResponse::from).toList();
    }

    public record EstimatedPairResponse(
            Long fromStopId, Long toStopId, int fromSecBand, int travelSec, Double distanceM) {

        static EstimatedPairResponse from(Deadhead deadhead) {
            return new EstimatedPairResponse(
                    deadhead.getFromStopId(),
                    deadhead.getToStopId(),
                    deadhead.getFromSecBand(),
                    deadhead.getTravelSec(),
                    deadhead.getDistanceM());
        }
    }
}
