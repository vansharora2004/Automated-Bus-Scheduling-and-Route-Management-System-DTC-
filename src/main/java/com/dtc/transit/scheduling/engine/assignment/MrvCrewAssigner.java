package com.dtc.transit.scheduling.engine.assignment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dtc.transit.scheduling.engine.model.CrewHistory;
import com.dtc.transit.scheduling.engine.model.CrewPool;
import com.dtc.transit.scheduling.engine.model.CrewView;
import com.dtc.transit.scheduling.engine.model.DutyPlan;
import com.dtc.transit.scheduling.engine.model.DutyType;
import com.dtc.transit.scheduling.engine.model.EngineConflict;
import com.dtc.transit.scheduling.engine.model.EntityRef;
import com.dtc.transit.scheduling.engine.model.RejectionReason;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.vehicle.ConflictTypes;

/**
 * Puts named crew on duties, hardest duty first.
 *
 * <p>Minimum remaining values: the duty with the fewest eligible people is filled before the duty with many.
 * Assigning in time order instead would spend the flexible people early and leave the one duty that only three
 * drivers can legally take with none of them free — a failure that looks like a staffing shortage and is really
 * an ordering mistake.
 *
 * <p>Candidate counts are recomputed as bookings are made, because every booking changes who is still available.
 * Recomputing after each one is the honest version; doing it in batches is the optimisation, and the batch size
 * is the one knob here.
 *
 * <p>Deterministic. Slots are ordered by candidate count then sign-on then duty number, and candidates by score
 * then id, so the same input always produces the same roster.
 */
public class MrvCrewAssigner {

    /**
     * How many bookings before candidate counts are recomputed.
     *
     * <p>Every booking changes availability, but recomputing the whole table each time is O(duties × crew) per
     * booking. Twenty-five keeps the ordering fresh enough to matter without dominating the runtime.
     */
    public static final int RECOUNT_EVERY = 25;

    private final EligibilityFilter eligibilityFilter;
    private final FairnessScorer scorer;
    private final ZoneId zone;

    public MrvCrewAssigner(ZoneId zone) {
        this(new EligibilityFilter(zone), new FairnessScorer(), zone);
    }

    public MrvCrewAssigner(EligibilityFilter eligibilityFilter, FairnessScorer scorer, ZoneId zone) {
        this.eligibilityFilter = eligibilityFilter;
        this.scorer = scorer;
        this.zone = zone;
    }

    /**
     * Assigns every duty it can and explains the rest.
     *
     * @param requiredQualificationsByDuty duty number to the codes that duty demands; absent means none
     */
    public Roster assign(
            List<DutyPlan> duties,
            CrewPool pool,
            CrewHistory history,
            long depotId,
            LocalDate serviceDate,
            Map<Integer, Set<String>> requiredQualificationsByDuty,
            RuleSet rules) {

        List<Slot> slots = new ArrayList<>();
        for (DutyPlan duty : duties) {
            // One slot per required role. Conductors are modelled but not demanded by the current trip data, so
            // only driver slots are created; the loop is what makes adding conductors a data change.
            slots.add(new Slot(duty, "DRIVER"));
        }

        List<Booking> bookings = new ArrayList<>();
        List<Unassigned> unassigned = new ArrayList<>();
        List<EngineConflict> conflicts = new ArrayList<>();

        Map<Integer, Integer> candidateCounts = countCandidates(
                slots, pool, history, depotId, serviceDate, requiredQualificationsByDuty, rules);
        List<Slot> remaining = new ArrayList<>(slots);
        int bookedSinceRecount = 0;

        while (!remaining.isEmpty()) {
            // Captured for the comparator, which cannot close over a variable that is reassigned on recount.
            final Map<Integer, Integer> counts = candidateCounts;
            // Hardest first: fewest candidates, then earliest sign-on, then duty number for a stable order.
            remaining.sort(Comparator.<Slot>comparingInt(
                            slot -> counts.getOrDefault(slot.duty().dutyNo(), 0))
                    .thenComparingInt(slot -> slot.duty().signOnSec())
                    .thenComparingInt(slot -> slot.duty().dutyNo()));

            Slot slot = remaining.remove(0);
            var filtered = eligibilityFilter.filter(
                    pool.ofRole(slot.crewRole()),
                    slot.duty(),
                    slot.crewRole(),
                    depotId,
                    serviceDate,
                    requiredQualificationsByDuty.getOrDefault(slot.duty().dutyNo(), Set.of()),
                    history,
                    rules);

            if (filtered.isEmpty()) {
                unassigned.add(new Unassigned(slot.duty().dutyNo(), slot.crewRole(), filtered.rejections(),
                        filtered.describe()));
                conflicts.add(EngineConflict.hard(
                        ConflictTypes.UNASSIGNED_DUTY,
                        "Duty %d has no eligible %s. %s"
                                .formatted(slot.duty().dutyNo(), slot.crewRole(), filtered.describe()),
                        List.of()));
                continue;
            }

            Instant startsAt = instantAt(serviceDate, slot.duty().signOnSec());
            Instant endsAt = instantAt(serviceDate, slot.duty().signOffSec());

            CrewView chosen = filtered.eligible().stream()
                    .min(Comparator.comparingDouble(
                                    (CrewView member) -> scorer.score(member, slot.duty(), history, endsAt, rules))
                            .thenComparingLong(CrewView::id))
                    .orElseThrow();

            bookings.add(new Booking(slot.duty().dutyNo(), slot.crewRole(), chosen.id(), startsAt, endsAt));
            // The in-memory history is updated immediately, so the next decision sees this booking. Without this
            // the assigner would happily book the same person twice.
            history.add(
                    chosen.id(),
                    startsAt,
                    endsAt,
                    slot.duty().spreadSec() - slot.duty().breakSec(),
                    slot.duty().dutyType() == DutyType.NIGHT);

            if (++bookedSinceRecount >= RECOUNT_EVERY) {
                bookedSinceRecount = 0;
                candidateCounts = countCandidates(
                        remaining, pool, history, depotId, serviceDate, requiredQualificationsByDuty, rules);
            }
        }

        return new Roster(
                bookings.stream()
                        .sorted(Comparator.comparingInt(Booking::dutyNo))
                        .toList(),
                unassigned.stream().sorted(Comparator.comparingInt(Unassigned::dutyNo)).toList(),
                conflicts);
    }

    /** How many people could take each slot, which is what makes one slot harder than another. */
    private Map<Integer, Integer> countCandidates(
            List<Slot> slots,
            CrewPool pool,
            CrewHistory history,
            long depotId,
            LocalDate serviceDate,
            Map<Integer, Set<String>> requiredQualificationsByDuty,
            RuleSet rules) {

        Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (Slot slot : slots) {
            var filtered = eligibilityFilter.filter(
                    pool.ofRole(slot.crewRole()),
                    slot.duty(),
                    slot.crewRole(),
                    depotId,
                    serviceDate,
                    requiredQualificationsByDuty.getOrDefault(slot.duty().dutyNo(), Set.of()),
                    history,
                    rules);
            counts.put(slot.duty().dutyNo(), filtered.eligible().size());
        }
        return counts;
    }

    private Instant instantAt(LocalDate serviceDate, int sec) {
        return serviceDate.atStartOfDay(zone).plusSeconds(sec).toInstant();
    }

    private record Slot(DutyPlan duty, String crewRole) {}

    public record Booking(int dutyNo, String crewRole, long crewMemberId, Instant startsAt, Instant endsAt) {}

    /** @param histogram counts per reason, which is what makes an unstaffable duty actionable */
    public record Unassigned(
            int dutyNo, String crewRole, Map<RejectionReason, Integer> histogram, String description) {}

    public record Roster(
            List<Booking> bookings, List<Unassigned> unassigned, List<EngineConflict> conflicts) {

        public Roster {
            bookings = List.copyOf(bookings);
            unassigned = List.copyOf(unassigned);
            conflicts = List.copyOf(conflicts);
        }
    }
}
