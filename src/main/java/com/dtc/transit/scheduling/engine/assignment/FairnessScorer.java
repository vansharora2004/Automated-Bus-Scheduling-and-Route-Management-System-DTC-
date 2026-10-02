package com.dtc.transit.scheduling.engine.assignment;

import java.time.Instant;

import com.dtc.transit.scheduling.engine.model.CrewHistory;
import com.dtc.transit.scheduling.engine.model.CrewView;
import com.dtc.transit.scheduling.engine.model.DutyPlan;
import com.dtc.transit.scheduling.engine.model.DutyType;
import com.dtc.transit.scheduling.engine.model.RuleSet;

/**
 * Ranks eligible crew for a duty. Lower is better.
 *
 * <p>Fairness here means spreading the unpopular work. Every eligible person can legally take the duty, so the
 * question is who should, and the answer is whoever has had the least of it: fewest hours this week, fewest
 * night duties this month.
 *
 * <p>Without this the assigner would book the first eligible person every time, which is legal and ends with the
 * same few people working every night turn.
 */
public class FairnessScorer {

    /** Weight on how full the person's week already is. The dominant term, and the one people feel most. */
    public static final double WEEKLY_HOURS_WEIGHT = 10.0;

    /** Weight on night duties already worked in the last 28 days. */
    public static final double NIGHT_DUTY_WEIGHT = 1.5;

    /** Weight on how many duties this person has taken in this run, which keeps one day's work even too. */
    public static final double RUN_LOAD_WEIGHT = 0.5;

    /**
     * Scores one candidate for one duty.
     *
     * @param endsAt the proposed sign-off, which is the point the weekly window is measured to
     */
    public double score(CrewView member, DutyPlan duty, CrewHistory history, Instant endsAt, RuleSet rules) {
        double weeklyLoad = (double) history.weeklyWorkSecBefore(member.id(), endsAt)
                / Math.max(1, rules.maxWeeklyWorkMin() * 60);

        double score = WEEKLY_HOURS_WEIGHT * weeklyLoad
                + RUN_LOAD_WEIGHT * history.dutiesWorked(member.id());

        // Night work is only penalised for a night duty. Counting a candidate's night history against them for a
        // day turn would push night workers towards more night work, which is backwards.
        if (duty.dutyType() == DutyType.NIGHT) {
            score += NIGHT_DUTY_WEIGHT * history.nightDutiesLast28Days(member.id(), endsAt);
        }
        return score;
    }
}
