package com.dtc.transit.scheduling.duty;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dtc.transit.scheduling.engine.model.DutyType;

public interface DutyRepository extends JpaRepository<Duty, Long> {

    Page<Duty> findByScheduleIdOrderByDutyNoAsc(Long scheduleId, Pageable pageable);

    List<Duty> findByScheduleIdOrderByDutyNoAsc(Long scheduleId);

    Page<Duty> findByScheduleIdAndDutyTypeOrderByDutyNoAsc(
            Long scheduleId, DutyType dutyType, Pageable pageable);

    long countByScheduleId(Long scheduleId);

    /**
     * The pieces of work a duty covers, in order.
     *
     * <p>Native, through the join table. A JPA association would make every duty read drag its pieces along, and
     * the duty list is the common case while the pieces are the detail view.
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT p.* FROM piece_of_work p
                    JOIN duty_piece dp ON dp.piece_id = p.id
                    WHERE dp.duty_id = :dutyId
                    ORDER BY dp.seq
                    """)
    List<PieceOfWork> piecesOf(@Param("dutyId") Long dutyId);

    /**
     * Totals per duty type, for the fairness picture a planner wants before publishing.
     *
     * <p>Aggregated in SQL rather than in Java: the alternative is loading every duty of a 45-depot day to add up
     * five numbers.
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT duty_type AS dutyType, count(*) AS dutyCount,
                           sum(paid_sec) AS paidSec, sum(platform_sec) AS platformSec
                    FROM duty WHERE schedule_id = :scheduleId
                    GROUP BY duty_type ORDER BY duty_type
                    """)
    List<DutyTypeSummary> summaryByType(@Param("scheduleId") Long scheduleId);

    /** Projection for {@link #summaryByType}. */
    interface DutyTypeSummary {

        String getDutyType();

        long getDutyCount();

        long getPaidSec();

        long getPlatformSec();
    }
}
