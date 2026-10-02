package com.dtc.transit.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dtc.transit.scheduling.engine.SchedulingEngine;
import com.dtc.transit.scheduling.engine.model.RuleSet;
import com.dtc.transit.scheduling.engine.model.ScheduleResult;
import com.dtc.transit.scheduling.rules.RuleSetResolver;
import com.dtc.transit.scheduling.schedule.ScheduleSnapshotLoader;
import com.dtc.transit.seed.DatasetGenerator;
import com.dtc.transit.seed.DatasetSize;
import com.dtc.transit.support.PostgisContainerTest;

/**
 * The Phase 6 performance target, measured rather than assumed.
 *
 * <p>The target is that a depot-day of the S dataset schedules in under five seconds, with every trip either
 * covered or carrying a reason. Measured against the engine and the snapshot loader together, because a fast
 * algorithm fed by a slow loader is not a fast system.
 *
 * <p>Generous bounds on purpose. This runs on developer laptops and in CI containers of unknown speed, and a
 * threshold tight enough to be interesting on one machine is a flaky test on another. The number worth watching
 * is the one it prints; the assertion exists to catch an order-of-magnitude regression, such as a per-lookup
 * database round trip creeping back into the engine's hot loop.
 */
class SchedulingPerformanceTest extends PostgisContainerTest {

    /** The plan's figure for a depot-day. */
    private static final long S_BUDGET_MILLIS = 5_000;

    /**
     * Budget for the largest depot-day in M.
     *
     * <p>M spreads the same per-depot volume over ten depots, so a depot-day is comparable to S and this is not a
     * ten-times-larger problem. The looser bound covers the snapshot loader having more rows to filter past.
     */
    private static final long M_BUDGET_MILLIS = 10_000;

    @Autowired
    private DatasetGenerator generator;

    @Autowired
    private ScheduleSnapshotLoader loader;

    @Autowired
    private RuleSetResolver ruleSets;

    @Autowired
    private JdbcTemplate jdbc;

    private final SchedulingEngine engine = new SchedulingEngine();

    @org.junit.jupiter.api.AfterEach
    void clearDataset() {
        generator.purge();
    }

    @Test
    @DisplayName("an S depot-day schedules fully within the time budget")
    void sDatasetMeetsTheBudget() {
        generator.generate(DatasetSize.S, true);
        Measurement measurement = scheduleFirstDepot();

        System.out.printf(
                "S: %d trips, %d blocks (optimum %s), %d ms load, %d ms engine%n",
                measurement.result().metrics().tripsTotal(),
                measurement.result().metrics().blocks(),
                measurement.result().metrics().minFleetLowerBound(),
                measurement.loadMillis(),
                measurement.result().metrics().elapsedMillis());

        assertThat(measurement.result().metrics().tripsTotal()).isPositive();
        // Full coverage, which is the stronger of the two forms the target allows.
        assertThat(measurement.result().metrics().tripsUncovered()).isZero();
        assertThat(measurement.totalMillis()).isLessThan(S_BUDGET_MILLIS);
    }

    @Test
    @DisplayName("every trip is covered or carries a reason, with no silent losses")
    void everyTripIsAccountedFor() {
        generator.generate(DatasetSize.S, true);
        Measurement measurement = scheduleFirstDepot();
        var schedule = measurement.result().vehicleSchedule();

        int accountedFor = schedule.coveredTripCount() + schedule.uncovered().size();

        // VehicleSchedule.of refuses to be constructed when a trip is lost, so reaching here already proves it;
        // the assertion states the fact rather than relying on a constructor nobody reads.
        assertThat(accountedFor).isEqualTo(measurement.result().metrics().tripsTotal());
        assertThat(schedule.uncovered()).allSatisfy(uncovered -> assertThat(uncovered.reason())
                .as("every uncovered trip must say why")
                .isNotBlank());
    }

    @Test
    @DisplayName("the greedy builder stays within a reasonable margin of the optimal fleet")
    void greedyIsCloseToOptimal() {
        generator.generate(DatasetSize.S, true);
        var metrics = scheduleFirstDepot().result().metrics();

        assertThat(metrics.minFleetLowerBound()).isNotNull();
        // Greedy can never beat the bound. The margin is what the matching builder exists to make visible: a
        // regression that doubled the fleet would still produce a valid schedule and would show up here.
        assertThat(metrics.blocks()).isGreaterThanOrEqualTo(metrics.minFleetLowerBound());
        assertThat(metrics.blocks())
                .as("greedy used %d buses against an optimum of %d", metrics.blocks(), metrics.minFleetLowerBound())
                .isLessThanOrEqualTo((int) Math.ceil(metrics.minFleetLowerBound() * 1.6) + 2);
    }

    @Test
    @DisplayName("an M depot-day schedules within its budget")
    void mDatasetMeetsTheBudget() {
        generator.generate(DatasetSize.M, true);
        Measurement measurement = scheduleFirstDepot();

        System.out.printf(
                "M: %d trips, %d blocks, %d ms load, %d ms engine%n",
                measurement.result().metrics().tripsTotal(),
                measurement.result().metrics().blocks(),
                measurement.loadMillis(),
                measurement.result().metrics().elapsedMillis());

        assertThat(measurement.result().metrics().tripsUncovered()).isZero();
        assertThat(measurement.totalMillis()).isLessThan(M_BUDGET_MILLIS);
    }

    @Test
    @DisplayName("dead running stays a small share of the total")
    void deadRunningIsNotExcessive() {
        generator.generate(DatasetSize.S, true);
        var metrics = scheduleFirstDepot().result().metrics();

        System.out.printf(
                "S: %.1f service km, %.1f dead km (%.1f%%)%n",
                metrics.serviceKm(), metrics.deadKm(), metrics.deadKmRatio() * 100);

        // The headline efficiency figure. A schedule where half the running is empty would be valid and awful,
        // and nothing else in the suite would notice.
        // Around 19% on this dataset. The bound is loose enough to survive a dataset tweak and tight enough to
        // catch the mistake this assertion was written for: ranking candidate blocks by raw slack, which quietly
        // prefers chains that need dead running because the empty movement eats the gap.
        assertThat(metrics.deadKmRatio()).isLessThan(0.30);
    }

    /** Loads and schedules the first depot's day, timing both halves separately. */
    private Measurement scheduleFirstDepot() {
        Long depotId = jdbc.queryForObject("SELECT id FROM depot ORDER BY id LIMIT 1", Long.class);
        LocalDate serviceDate = LocalDate.of(2026, 6, 1);
        RuleSet rules = ruleSets.resolve(depotId, serviceDate).rules();

        long startedLoad = System.nanoTime();
        var snapshot = loader.load(depotId, serviceDate);
        long loadMillis = (System.nanoTime() - startedLoad) / 1_000_000;

        ScheduleResult result = engine.run(snapshot.trips(), snapshot.context(), rules);
        return new Measurement(result, loadMillis);
    }

    private record Measurement(ScheduleResult result, long loadMillis) {

        long totalMillis() {
            return loadMillis + result.metrics().elapsedMillis();
        }
    }
}
