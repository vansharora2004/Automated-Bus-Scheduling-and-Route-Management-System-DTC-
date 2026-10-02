package com.dtc.transit.timetable.trip;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Trip reads. Writes go through the timetable, because a trip only exists as part of a generated set. */
@Service
public class TripService {

    private final TripRepository trips;

    public TripService(TripRepository trips) {
        this.trips = trips;
    }

    /**
     * One page of trips plus a lookahead row.
     *
     * <p>Returns {@code limit + 1} rows so the caller can tell whether another page exists without a second
     * query, and without a count that would defeat the point of keyset paging.
     */
    @Transactional(readOnly = true)
    public List<Trip> page(
            Long after, Long timetableId, Long patternId, Integer fromSec, Integer toSec, int limit) {
        return trips.findPage(after, timetableId, patternId, fromSec, toSec, PageRequest.of(0, limit + 1));
    }

    @Transactional(readOnly = true)
    public long countFor(Long timetableId) {
        return trips.countByTimetableId(timetableId);
    }

    @Transactional(readOnly = true)
    public List<Trip> ofTimetable(Long timetableId) {
        return trips.findByTimetableIdOrderByStartSecAsc(timetableId);
    }
}
