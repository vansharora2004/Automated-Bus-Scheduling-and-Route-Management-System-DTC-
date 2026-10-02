package com.dtc.transit.scheduling.run;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleRunRepository extends JpaRepository<ScheduleRun, UUID> {

    Optional<ScheduleRun> findByIdempotencyKey(String idempotencyKey);

    @Query(
            """
            select r from ScheduleRun r
            where r.depotId = :depotId and r.serviceDate = :serviceDate
              and r.status in (com.dtc.transit.scheduling.run.RunStatus.QUEUED,
                               com.dtc.transit.scheduling.run.RunStatus.RUNNING)
            """)
    Optional<ScheduleRun> findActiveFor(
            @Param("depotId") Long depotId, @Param("serviceDate") LocalDate serviceDate);

    Page<ScheduleRun> findByDepotIdOrderByCreatedAtDesc(Long depotId, Pageable pageable);

    /**
     * Says the worker is still alive.
     *
     * <p>Guarded on the worker's own name: a worker the reaper has already given up on must not be able to
     * resurrect a run that has been failed and possibly re-queued.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE schedule_run SET heartbeat_at = now(), progress = :progress
                    WHERE id = :runId AND status = 'RUNNING' AND claimed_by = :worker
                    """)
    int heartbeat(
            @Param("runId") UUID runId, @Param("worker") String worker, @Param("progress") int progress);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE schedule_run
                    SET status = 'COMPLETED', progress = 100, finished_at = now(),
                        schedule_id = :scheduleId, metrics = CAST(:metrics AS jsonb)
                    WHERE id = :runId
                    """)
    int markCompleted(
            @Param("runId") UUID runId,
            @Param("scheduleId") Long scheduleId,
            @Param("metrics") String metrics);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE schedule_run SET status = 'FAILED', finished_at = now(), error = :error
                    WHERE id = :runId AND status IN ('QUEUED', 'RUNNING')
                    """)
    int markFailed(@Param("runId") UUID runId, @Param("error") String error);

    /**
     * Fails every run whose worker has gone quiet.
     *
     * <p>The reaper's whole job. A worker that was killed mid-run leaves its row saying {@code RUNNING} forever,
     * which would hold the one-active-run-per-depot-day slot and block every retry. The schedule rows are not
     * cleaned up here because there are none: the run's writes are one transaction that never committed.
     *
     * @return how many runs were reaped
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE schedule_run
                    SET status = 'FAILED', finished_at = now(), error = 'WORKER_LOST'
                    WHERE status = 'RUNNING' AND (heartbeat_at IS NULL OR heartbeat_at < :deadline)
                    """)
    int reapStaleRuns(@Param("deadline") Instant deadline);

    List<ScheduleRun> findByStatus(RunStatus status);
}
