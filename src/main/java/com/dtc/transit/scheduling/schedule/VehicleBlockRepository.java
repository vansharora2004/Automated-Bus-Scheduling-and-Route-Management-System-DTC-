package com.dtc.transit.scheduling.schedule;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleBlockRepository extends JpaRepository<VehicleBlock, Long> {

    Page<VehicleBlock> findByScheduleIdOrderByBlockNoAsc(Long scheduleId, Pageable pageable);

    List<VehicleBlock> findByScheduleIdOrderByBlockNoAsc(Long scheduleId);

    long countByScheduleId(Long scheduleId);

    /**
     * Trips covered by a schedule's blocks.
     *
     * <p>Native, over the partitioned event table. Mapping {@code block_event} as an entity would need the
     * service date in its identity, which is an awkward key to carry into every read for a table that is only
     * ever written in bulk and read a block at a time.
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT e.trip_id
                    FROM block_event e
                    JOIN vehicle_block b ON b.id = e.block_id
                    WHERE b.schedule_id = :scheduleId AND e.trip_id IS NOT NULL
                    ORDER BY e.trip_id
                    """)
    List<Long> coveredTripIds(@Param("scheduleId") Long scheduleId);
}
