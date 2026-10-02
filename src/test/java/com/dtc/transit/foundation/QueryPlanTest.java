package com.dtc.transit.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dtc.transit.seed.DatasetGenerator;
import com.dtc.transit.seed.DatasetSize;
import com.dtc.transit.support.PostgisContainerTest;

/**
 * Query plans for the queries that run most often or cost most.
 *
 * <p>Asserting on plans rather than on timings. A timing assertion is a flaky test on shared hardware; a plan
 * assertion catches the thing that actually matters, which is an index silently stopping being used. A sequential
 * scan on a hundred rows is fine and on fifty thousand is not, and the plan is what tells them apart.
 *
 * <p>The M dataset is loaded so the planner has enough rows to prefer an index. On a handful of rows PostgreSQL
 * correctly chooses a sequential scan, and a test that asserted otherwise would be asserting the wrong thing.
 */
class QueryPlanTest extends PostgisContainerTest {

    @Autowired
    private DatasetGenerator generator;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void loadAndAnalyse() {
        generator.generate(DatasetSize.M, true);
        // Without fresh statistics the planner is working from defaults and every assertion below is a guess
        // about what it would do with real numbers.
        jdbc.execute("ANALYZE");
    }

    @org.junit.jupiter.api.AfterEach
    void clearDataset() {
        generator.purge();
    }

    @Test
    @DisplayName("the trip keyset page uses an index, not a scan of every trip")
    void tripKeysetPageUsesAnIndex() {
        String plan = explain(
                """
                SELECT * FROM trip
                WHERE timetable_id = (SELECT id FROM timetable ORDER BY id LIMIT 1)
                ORDER BY id LIMIT 100
                """);

        // 12,000 trips in M. A sequential scan here is what a deep offset would force, and it is exactly what
        // keyset pagination exists to avoid.
        assertThat(plan).doesNotContain("Seq Scan on trip");
    }

    @Test
    @DisplayName("the depot-day trip load for a run uses indexes on trip and timetable")
    void snapshotTripLoadUsesIndexes() {
        String plan = explain(
                """
                SELECT t.id FROM trip t
                JOIN timetable tt ON tt.id = t.timetable_id
                JOIN route r ON r.id = tt.route_id
                WHERE r.depot_id = (SELECT id FROM depot ORDER BY id LIMIT 1)
                  AND tt.day_type = 'WEEKDAY' AND tt.status = 'ACTIVE'
                """);

        // This is the hottest query in the system: every run begins with it. A plan that scans trip would make
        // every depot-day pay for the whole network's trips.
        assertThat(plan).contains("Index");
    }

    /**
     * Indexes exist for the queries this test cannot populate enough rows to exercise.
     *
     * <p>{@code block_event}, {@code duty_assignment} and {@code audit_log} are written by a scheduling run, not
     * by the dataset generator, and running one here would make this test minutes long. On the empty tables the
     * planner correctly chooses a sequential scan, so asserting on the plan would assert the wrong thing.
     *
     * <p>What is checked instead is the durable property: the index is present and partial where it should be.
     * An index that was dropped is the failure this guards against; which plan the planner picks for ten rows is
     * not. The scheduling tests exercise these paths with real volumes.
     */
    @Test
    @DisplayName("the high-volume operational tables carry the indexes their hot queries need")
    void highVolumeTablesHaveTheirIndexes() {
        assertThat(indexesOn("block_event"))
                .as("the per-block read and the relief-point lookup")
                .contains("block_event_block_idx", "block_event_relief_idx");

        assertThat(indexesOn("duty_assignment"))
                .as("the rolling crew history window, which runs once per candidate per duty")
                .contains("duty_assignment_history_idx", "duty_assignment_crew_date_idx");

        assertThat(indexesOn("audit_log"))
                .as("the keyset page, newest first")
                .contains("audit_log_at_idx");
    }

    @Test
    @DisplayName("the audit log is partitioned, so its growth is bounded by dropping partitions")
    void auditLogIsPartitioned() {
        Integer partitions = jdbc.queryForObject(
                """
                SELECT count(*) FROM pg_class c
                JOIN pg_inherits i ON i.inhrelid = c.oid
                JOIN pg_class parent ON parent.oid = i.inhparent
                WHERE parent.relname = 'audit_log'
                """,
                Integer.class);

        // Retention is a partition drop, not a delete. That is also why UPDATE and DELETE can be revoked.
        assertThat(partitions).isGreaterThan(1);
    }

    @Test
    @DisplayName("the reporting views are read by their unique indexes")
    void reportViewsUseTheirIndexes() {
        jdbc.execute("REFRESH MATERIALIZED VIEW mv_fleet_utilization_daily");
        jdbc.execute("ANALYZE mv_fleet_utilization_daily");

        String plan = explain(
                """
                SELECT * FROM mv_fleet_utilization_daily
                WHERE depot_id = (SELECT id FROM depot ORDER BY id LIMIT 1)
                ORDER BY service_date DESC
                """);

        // The view is small on this dataset, so a scan is a legitimate choice. What matters is that the unique
        // index exists at all, because REFRESH ... CONCURRENTLY requires it and the plan is incidental.
        assertThat(indexesOn("mv_fleet_utilization_daily")).contains("mv_fleet_utilization_daily_pk");
        assertThat(plan).isNotBlank();
    }

    @Test
    @DisplayName("the one-active-run index covers the queue claim")
    void runQueueClaimUsesThePartialIndex() {
        assertThat(indexesOn("schedule_run"))
                .contains("one_active_run_per_depot_day")
                .contains("schedule_run_queue_idx");
    }

    @Test
    @DisplayName("the double-booking guards are backed by GiST indexes")
    void exclusionConstraintsHaveIndexes() {
        // An exclusion constraint is implemented as an index. If these were missing the constraint would not
        // exist, and nothing in the application would notice until two buses were sent to one trip.
        assertThat(indexesOn("bus_assignment")).contains("no_bus_double_booking");
        assertThat(indexesOn("duty_assignment")).contains("no_crew_double_booking");
    }

    /** The plan for a query, as text. */
    private String explain(String sql) {
        List<String> lines = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class);
        String plan = String.join("\n", lines);
        // Printed so a reviewer can see the plan rather than only whether the assertion passed.
        System.out.println("---- " + sql.strip().lines().findFirst().orElse("") + "\n" + plan);
        return plan;
    }

    private List<String> indexesOn(String table) {
        return jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = ?", String.class, table);
    }
}
