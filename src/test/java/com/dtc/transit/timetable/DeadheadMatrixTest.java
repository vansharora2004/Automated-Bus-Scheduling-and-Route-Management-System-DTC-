package com.dtc.transit.timetable;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dtc.transit.support.PostgisContainerTest;
import com.dtc.transit.timetable.deadhead.DeadheadMatrix;

/**
 * The deadhead matrix: measured values where they exist, flagged estimates where they do not.
 *
 * <p>At the service layer, because the behaviour under test is which of two sources answers and whether the
 * answer admits to being a guess. Going over HTTP would add a response shape without adding a question.
 */
class DeadheadMatrixTest extends PostgisContainerTest {

    @Autowired
    private DeadheadMatrix matrix;

    @Autowired
    private JdbcTemplate jdbc;

    private Long terminalA;
    private Long terminalB;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM deadhead");
        jdbc.update("DELETE FROM pattern_stop");
        jdbc.update("DELETE FROM stop");

        // Roughly 5.9 km apart along a meridian, so the straight-line distance has a known answer.
        terminalA = insertStop("DH-A", 77.20, 28.60);
        terminalB = insertStop("DH-B", 77.20, 28.653);
    }

    @Test
    @DisplayName("a pair with no measured value gets an estimate, flagged as such")
    void missingPairIsEstimatedAndFlagged() {
        var lookup = matrix.travelTime(terminalA, terminalB, 8 * 3600);

        // Refusing to answer would stop a whole depot from being scheduled over one missing survey. The
        // flag is what keeps the estimate from being mistaken for a measurement.
        assertThat(lookup.estimated()).isTrue();
        assertThat(lookup.travelSeconds()).isPositive();
        // Straight line times the detour factor: roughly 5.9 km becomes about 7.7 km of road.
        assertThat(lookup.distanceMetres()).isBetween(7_000.0, 8_500.0);
    }

    @Test
    @DisplayName("an estimate is stored once and reused rather than recomputed")
    void estimateIsPersisted() {
        var first = matrix.travelTime(terminalA, terminalB, 8 * 3600);
        var second = matrix.travelTime(terminalA, terminalB, 8 * 3600);

        assertThat(second.travelSeconds()).isEqualTo(first.travelSeconds());
        // One row, not two: the second call found the stored estimate instead of making a new one.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deadhead", Integer.class)).isEqualTo(1);
        assertThat(matrix.estimatedPairs()).hasSize(1);
    }

    @Test
    @DisplayName("EC-TT-11: the band is chosen by departure time, so a peak move keeps the peak value")
    void bandIsChosenByDepartureTime() {
        insertDeadhead(terminalA, terminalB, 0, 900);
        insertDeadhead(terminalA, terminalB, 6 * 3600, 1800);
        insertDeadhead(terminalA, terminalB, 10 * 3600, 1200);

        var offPeak = matrix.travelTime(terminalA, terminalB, 5 * 3600);
        var peak = matrix.travelTime(terminalA, terminalB, 9 * 3600);
        var midday = matrix.travelTime(terminalA, terminalB, 11 * 3600);

        // Each lookup takes the latest band starting at or before the departure, which is what "from this
        // time onward" means. A move that starts in the peak sits in peak traffic for its whole journey.
        assertThat(offPeak.travelSeconds()).isEqualTo(900);
        assertThat(peak.travelSeconds()).isEqualTo(1800);
        assertThat(midday.travelSeconds()).isEqualTo(1200);
        assertThat(peak.estimated()).isFalse();
    }

    @Test
    @DisplayName("a move to the same place takes no time and is not an estimate")
    void sameStopIsZeroAndNotEstimated() {
        var lookup = matrix.travelTime(terminalA, terminalA, 8 * 3600);

        // Zero is the right answer here, and flagging it as estimated would raise a soft conflict against
        // every block that lays over at its own terminal, which is most of them.
        assertThat(lookup.travelSeconds()).isZero();
        assertThat(lookup.estimated()).isFalse();
        assertThat(matrix.estimatedPairs()).isEmpty();
    }

    @Test
    @DisplayName("measured values are never reported as estimates")
    void measuredValuesAreNotFlagged() {
        insertDeadhead(terminalA, terminalB, 0, 1500);

        var lookup = matrix.travelTime(terminalA, terminalB, 8 * 3600);

        assertThat(lookup.travelSeconds()).isEqualTo(1500);
        assertThat(lookup.estimated()).isFalse();
        assertThat(matrix.estimatedPairs()).isEmpty();
    }

    private Long insertStop(String code, double lon, double lat) {
        return jdbc.queryForObject(
                """
                INSERT INTO stop (code, name, location, is_terminal)
                VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), TRUE)
                RETURNING id
                """,
                Long.class,
                code,
                code,
                lon,
                lat);
    }

    private void insertDeadhead(Long from, Long to, int band, int travelSec) {
        jdbc.update(
                """
                INSERT INTO deadhead (from_stop_id, to_stop_id, from_sec_band, travel_sec, distance_m, estimated)
                VALUES (?, ?, ?, ?, ?, FALSE)
                """,
                from,
                to,
                band,
                travelSec,
                8000.0);
    }
}
