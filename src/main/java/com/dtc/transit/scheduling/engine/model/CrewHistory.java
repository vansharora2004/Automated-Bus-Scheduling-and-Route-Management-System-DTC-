package com.dtc.transit.scheduling.engine.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What each crew member has already worked.
 *
 * <p>Two windows, for two different purposes. Seven days drives the hard limits: weekly hours and the weekly
 * rest day. Twenty-eight days drives fairness, because an even spread of night duties over a month is what
 * people actually notice.
 *
 * <p>Mutable, and deliberately so. The assigner books a slot and then has to see the effect on the next
 * decision; recomputing from the database after every booking would make assignment quadratic in queries.
 */
public class CrewHistory {

    private final Map<Long, List<Worked>> worked = new HashMap<>();

    /** Records something already on the books, loaded from previous dates before assignment starts. */
    public void add(long crewMemberId, Instant startsAt, Instant endsAt, int workSec, boolean night) {
        worked.computeIfAbsent(crewMemberId, key -> new ArrayList<>())
                .add(new Worked(startsAt, endsAt, workSec, night));
    }

    /** Minutes worked in the seven days ending at an instant, which is what the weekly limit counts. */
    public int weeklyWorkSecBefore(long crewMemberId, Instant at) {
        Instant from = at.minusSeconds(7 * 24 * 3600L);
        return entries(crewMemberId).stream()
                .filter(entry -> entry.endsAt().isAfter(from) && entry.startsAt().isBefore(at))
                .mapToInt(Worked::workSec)
                .sum();
    }

    /** Night duties in the last 28 days, which the fairness score spreads out. */
    public int nightDutiesLast28Days(long crewMemberId, Instant at) {
        Instant from = at.minusSeconds(28 * 24 * 3600L);
        return (int) entries(crewMemberId).stream()
                .filter(entry -> entry.night() && entry.startsAt().isAfter(from))
                .count();
    }

    /**
     * Seconds of rest between the last sign-off and a proposed sign-on.
     *
     * <p>{@link Integer#MAX_VALUE} when the person has worked nothing recently, so a crew member with no history
     * is never rejected for insufficient rest.
     */
    public int restBeforeSec(long crewMemberId, Instant proposedStart) {
        return entries(crewMemberId).stream()
                .filter(entry -> !entry.endsAt().isAfter(proposedStart))
                .mapToInt(entry -> (int) Math.min(
                        Integer.MAX_VALUE, proposedStart.getEpochSecond() - entry.endsAt().getEpochSecond()))
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    /** Whether any already-booked period overlaps a proposed one. */
    public boolean isBookedDuring(long crewMemberId, Instant startsAt, Instant endsAt) {
        return entries(crewMemberId).stream()
                .anyMatch(entry -> entry.startsAt().isBefore(endsAt) && startsAt.isBefore(entry.endsAt()));
    }

    /**
     * Whether the person has had a whole rest day in the rolling seven.
     *
     * <p>A calendar day with nothing booked. Checked against the days they did work rather than against a
     * roster, because a day off is the absence of work however it arose.
     */
    public boolean hasRestDayInLast7Days(long crewMemberId, Instant at) {
        Instant from = at.minusSeconds(7 * 24 * 3600L);
        long daysWorked = entries(crewMemberId).stream()
                .filter(entry -> entry.startsAt().isAfter(from))
                .map(entry -> entry.startsAt().getEpochSecond() / 86_400)
                .distinct()
                .count();
        return daysWorked < 7;
    }

    public int dutiesWorked(long crewMemberId) {
        return entries(crewMemberId).size();
    }

    private List<Worked> entries(long crewMemberId) {
        return worked.getOrDefault(crewMemberId, List.of());
    }

    /** @param workSec paid work, not the spread-over, because that is what the weekly limit counts */
    private record Worked(Instant startsAt, Instant endsAt, int workSec, boolean night) {}
}
