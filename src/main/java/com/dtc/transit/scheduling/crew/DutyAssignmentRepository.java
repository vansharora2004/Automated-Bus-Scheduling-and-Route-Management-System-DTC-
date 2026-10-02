package com.dtc.transit.scheduling.crew;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DutyAssignmentRepository extends JpaRepository<DutyAssignment, Long> {

    @Query(
            """
            select a from DutyAssignment a
            where a.dutyId in (select d.id from Duty d where d.scheduleId = :scheduleId)
            order by a.dutyId, a.crewRole
            """)
    List<DutyAssignment> findBySchedule(@Param("scheduleId") Long scheduleId);

    @Query(
            """
            select a from DutyAssignment a
            where a.dutyId in (select d.id from Duty d where d.scheduleId = :scheduleId)
            order by a.dutyId, a.crewRole
            """)
    Page<DutyAssignment> findBySchedule(@Param("scheduleId") Long scheduleId, Pageable pageable);

    List<DutyAssignment> findByDutyIdOrderByCrewRoleAsc(Long dutyId);

    Page<DutyAssignment> findByCrewMemberIdOrderByStartsAtDesc(Long crewMemberId, Pageable pageable);

    /**
     * What a crew member has worked over a window, for the rolling history.
     *
     * <p>Cancelled rows are excluded: they are not work anybody did, and counting them would make a withdrawn
     * duty keep consuming someone's weekly hours.
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT a.crew_member_id AS crewMemberId, a.starts_at AS startsAt, a.ends_at AS endsAt,
                           d.spread_sec - d.break_sec AS workSec,
                           (d.duty_type = 'NIGHT') AS night
                    FROM duty_assignment a
                    JOIN duty d ON d.id = a.duty_id
                    WHERE a.status <> 'CANCELLED'
                      AND a.service_date >= :from AND a.service_date < :to
                      AND a.crew_member_id = ANY(CAST(:crewIds AS bigint[]))
                    ORDER BY a.crew_member_id, a.starts_at
                    """)
    List<HistoryRow> historyBetween(
            @Param("crewIds") String crewIdArray,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** Flips a schedule's assignments to a new status, in one statement, for the publish transaction. */
    @Modifying(flushAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE duty_assignment SET schedule_status = :status
                    WHERE duty_id IN (SELECT id FROM duty WHERE schedule_id = :scheduleId)
                    """)
    int updateScheduleStatus(@Param("scheduleId") Long scheduleId, @Param("status") String status);

    /** Projection for {@link #historyBetween}. */
    interface HistoryRow {

        long getCrewMemberId();

        java.time.Instant getStartsAt();

        java.time.Instant getEndsAt();

        int getWorkSec();

        boolean getNight();
    }
}
