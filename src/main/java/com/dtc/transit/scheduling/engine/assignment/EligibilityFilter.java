package com.dtc.transit.scheduling.engine.assignment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dtc.transit.scheduling.engine.model.CrewHistory;
import com.dtc.transit.scheduling.engine.model.CrewView;
import com.dtc.transit.scheduling.engine.model.DutyPlan;
import com.dtc.transit.scheduling.engine.model.RejectionReason;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.TimeWindow;

/**
 * Decides who may take a duty, and records why everyone else may not.
 *
 * <p>The reasons are the valuable part. A duty that cannot be staffed is not a bug to be hidden; it is a fact a
 * scheduler has to act on, and the action depends entirely on whether the obstacle is leave, rest or licences.
 *
 * <p>Checks run cheapest-first — depot and role before anything that needs history — because this runs once per
 * crew member per duty slot, which on the L dataset is millions of evaluations.
 *
 * <p>Rest between duties and the weekly limits are enforced here rather than in the duty rules, because they
 * depend on who is doing the work and what they did yesterday.
 */
public class EligibilityFilter {

    private final ZoneId zone;

    public EligibilityFilter(ZoneId zone) {
        this.zone = zone;
    }

    /**
     * Splits a pool into those who can take the duty and a histogram of why the rest cannot.
     *
     * @param requiredQualifications codes the duty demands, such as EV for an electric block
     */
    public Result filter(
            List<CrewView> pool,
            DutyPlan duty,
            String crewRole,
            long depotId,
            LocalDate serviceDate,
            Set<String> requiredQualifications,
            CrewHistory history,
            RuleSet rules) {

        Instant startsAt = serviceDate.atStartOfDay(zone).plusSeconds(duty.signOnSec()).toInstant();
        Instant endsAt = serviceDate.atStartOfDay(zone).plusSeconds(duty.signOffSec()).toInstant();
        TimeWindow dutyWindow = new TimeWindow(duty.signOnSec(), duty.signOffSec());
        int dutyWorkSec = duty.spreadSec() - duty.breakSec();

        List<CrewView> eligible = new java.util.ArrayList<>();
        Map<RejectionReason, Integer> rejections = new LinkedHashMap<>();

        for (CrewView member : pool) {
            RejectionReason reason = reject(
                    member,
                    crewRole,
                    depotId,
                    serviceDate,
                    dutyWindow,
                    startsAt,
                    endsAt,
                    dutyWorkSec,
                    requiredQualifications,
                    history,
                    rules);
            if (reason == null) {
                eligible.add(member);
            } else {
                rejections.merge(reason, 1, Integer::sum);
            }
        }

        return new Result(List.copyOf(eligible), Map.copyOf(rejections), pool.size());
    }

    /**
     * The first reason this person cannot take the duty, or null when they can.
     *
     * <p>First rather than all: a person on leave with an expired licence is still just unavailable, and
     * collecting every reason for every rejection would multiply the work for no extra insight.
     */
    private RejectionReason reject(
            CrewView member,
            String crewRole,
            long depotId,
            LocalDate serviceDate,
            TimeWindow dutyWindow,
            Instant startsAt,
            Instant endsAt,
            int dutyWorkSec,
            Set<String> requiredQualifications,
            CrewHistory history,
            RuleSet rules) {

        if (member.depotId() != depotId) {
            return RejectionReason.WRONG_DEPOT;
        }
        if (!crewRole.equals(member.crewRole())) {
            return RejectionReason.WRONG_ROLE;
        }
        if (!member.isActive()) {
            return RejectionReason.INACTIVE;
        }
        if (member.weeklyOffDow() != null && member.weeklyOffDow() == serviceDate.getDayOfWeek().getValue()) {
            return RejectionReason.WEEKLY_OFF;
        }
        if (member.isOnLeaveDuring(dutyWindow)) {
            return RejectionReason.ON_LEAVE;
        }
        if (!member.hasValidLicenceOn(serviceDate)) {
            return RejectionReason.LICENCE_INVALID;
        }
        if (!member.qualifications().containsAll(requiredQualifications)) {
            return RejectionReason.QUALIFICATION_MISSING;
        }

        // History-dependent checks last: they are the expensive ones.
        if (history.isBookedDuring(member.id(), startsAt, endsAt)) {
            return RejectionReason.ALREADY_BOOKED;
        }
        if (history.restBeforeSec(member.id(), startsAt) < rules.minRestBetweenDutiesMin() * 60) {
            return RejectionReason.INSUFFICIENT_REST;
        }
        if (history.weeklyWorkSecBefore(member.id(), endsAt) + dutyWorkSec > rules.maxWeeklyWorkMin() * 60) {
            return RejectionReason.WEEKLY_HOURS_EXCEEDED;
        }
        if (!history.hasRestDayInLast7Days(member.id(), endsAt)) {
            return RejectionReason.WEEKLY_REST_MISSING;
        }
        return null;
    }

    /**
     * @param rejections how many people each reason accounted for, in check order
     * @param considered the size of the pool, so the histogram can be read as a proportion
     */
    public record Result(List<CrewView> eligible, Map<RejectionReason, Integer> rejections, int considered) {

        public boolean isEmpty() {
            return eligible.isEmpty();
        }

        /**
         * The histogram as a sentence, which is what reaches a scheduler.
         *
         * <p>For example "18 considered: 9 INSUFFICIENT_REST, 6 ON_LEAVE, 3 LICENCE_INVALID". That is the form
         * that makes an unstaffable duty actionable rather than merely reported.
         */
        public String describe() {
            if (rejections.isEmpty()) {
                return considered + " considered, none rejected";
            }
            String breakdown = rejections.entrySet().stream()
                    .sorted(Map.Entry.<RejectionReason, Integer>comparingByValue()
                            .reversed()
                            .thenComparing(entry -> entry.getKey().name()))
                    .map(entry -> entry.getValue() + " " + entry.getKey())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            return considered + " considered: " + breakdown;
        }
    }
}
