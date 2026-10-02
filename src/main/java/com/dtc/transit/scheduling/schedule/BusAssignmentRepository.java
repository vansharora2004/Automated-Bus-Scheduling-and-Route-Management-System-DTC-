package com.dtc.transit.scheduling.schedule;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BusAssignmentRepository extends JpaRepository<BusAssignment, Long> {

    @Query(
            """
            select a from BusAssignment a
            where a.blockId in (select b.id from VehicleBlock b where b.scheduleId = :scheduleId)
            order by a.blockId
            """)
    List<BusAssignment> findBySchedule(@Param("scheduleId") Long scheduleId);

    /**
     * Flips a schedule's assignments to a new status.
     *
     * <p>A bulk update rather than loading and saving each row. Publishing must flip every assignment in one
     * statement, because the exclusion constraint is checked per statement and a row-by-row flip would make the
     * schedule transiently overlap itself.
     */
    // flushAutomatically so a pending status change on the schedule itself reaches the database before this
    // statement runs. Not clearAutomatically: the caller still holds managed entities it means to flush.
    @Modifying(flushAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE bus_assignment SET schedule_status = :status
                    WHERE block_id IN (SELECT id FROM vehicle_block WHERE schedule_id = :scheduleId)
                    """)
    int updateScheduleStatus(@Param("scheduleId") Long scheduleId, @Param("status") String status);
}
