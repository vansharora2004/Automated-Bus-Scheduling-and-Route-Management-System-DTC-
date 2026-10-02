package com.dtc.transit.scheduling.schedule;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flags published schedules that master data has moved underneath.
 *
 * <p>A published schedule is immutable, so this never changes one. It sets {@code needs_revalidation} and records
 * the new conflicts, which is the honest response: the roster crews were given is still the roster, and somebody
 * has to decide whether to publish a correction.
 *
 * <p>Two causes are checked, both of which actually happen between publication and the service date: a bus going
 * into the workshop under a block it was assigned to, and a licence expiring under a driver who is rostered.
 *
 * <p>Runs on a timer rather than reacting to events. A bus status change is one statement in another module, and
 * making every such write publish an event that this consumed would couple the two for a job that can afford to
 * be a few minutes late.
 */
@Component
public class RevalidationJob {

    private static final Logger log = LoggerFactory.getLogger(RevalidationJob.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean enabled;

    public RevalidationJob(
            JdbcTemplate jdbc, Clock clock, @Value("${app.scheduling.revalidation.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.scheduling.revalidation.interval-ms:300000}")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            revalidate(LocalDate.now(clock));
        } catch (Exception e) {
            // Must not kill the timer: Spring stops rescheduling a task that throws.
            log.error("revalidation failed; it will try again", e);
        }
    }

    /**
     * Checks every published schedule from today onwards.
     *
     * @return how many schedules were newly flagged
     */
    @Transactional
    public int revalidate(LocalDate from) {
        List<Long> affected = jdbc.queryForList(
                """
                SELECT DISTINCT s.id
                FROM schedule s
                WHERE s.status = 'PUBLISHED' AND s.service_date >= ?
                  AND (
                    -- A bus assigned to one of this schedule's blocks is now unavailable during it.
                    EXISTS (
                      SELECT 1 FROM bus_assignment a
                      JOIN vehicle_block b ON b.id = a.block_id AND b.schedule_id = s.id
                      JOIN bus_unavailability u ON u.bus_id = a.bus_id AND u.period && a.period)
                    OR EXISTS (
                      SELECT 1 FROM bus_assignment a
                      JOIN vehicle_block b ON b.id = a.block_id AND b.schedule_id = s.id
                      JOIN bus bu ON bu.id = a.bus_id
                      WHERE bu.status NOT IN ('ACTIVE', 'UNDER_MAINTENANCE'))
                    -- Or a rostered driver's licence has expired by the service date.
                    OR EXISTS (
                      SELECT 1 FROM duty_assignment da
                      JOIN duty d ON d.id = da.duty_id AND d.schedule_id = s.id
                      JOIN crew_member c ON c.id = da.crew_member_id
                      WHERE da.status <> 'CANCELLED'
                        AND (c.status <> 'ACTIVE'
                             OR (c.licence_expiry IS NOT NULL AND c.licence_expiry < s.service_date)))
                  )
                """,
                Long.class,
                from);

        if (affected.isEmpty()) {
            return 0;
        }

        for (Long scheduleId : affected) {
            // Only once per cause: re-flagging an already-flagged schedule every five minutes would bury the
            // conflict list in duplicates.
            int inserted = jdbc.update(
                    """
                    INSERT INTO conflict (schedule_id, type, severity, message)
                    SELECT ?, 'NEEDS_REVALIDATION', 'HARD',
                           'Master data changed after publication: a bus or a rostered crew member is no longer '
                           || 'available. Review and publish a new version.'
                    WHERE NOT EXISTS (
                      SELECT 1 FROM conflict
                      WHERE schedule_id = ? AND type = 'NEEDS_REVALIDATION' AND NOT resolved)
                    """,
                    scheduleId,
                    scheduleId);
            jdbc.update("UPDATE schedule SET needs_revalidation = TRUE WHERE id = ?", scheduleId);
            if (inserted > 0) {
                log.warn("schedule {} needs revalidation: master data changed after publication", scheduleId);
            }
        }
        return affected.size();
    }
}
