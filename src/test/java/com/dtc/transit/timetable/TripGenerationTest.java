package com.dtc.transit.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.route.pattern.RunningTimeBand;
import com.dtc.transit.route.route.DayType;
import com.dtc.transit.route.route.Direction;
import com.dtc.transit.timetable.headway.HeadwayBand;
import com.dtc.transit.timetable.trip.TripGenerator;

/**
 * The trip generator on its own, with no Spring context and no database.
 *
 * <p>Generation is pure arithmetic over bands, so it is tested as such. Driving it through the API instead
 * would make every case cost a transaction and would hide which part produced a wrong number.
 */
class TripGenerationTest {

    private static final Long PATTERN_ID = 42L;

    private final TripGenerator generator = new TripGenerator();

    @ParameterizedTest(name = "{0}s to {1}s every {2}s produces {3} trips")
    @CsvSource({
        // A window that divides exactly: the last departure is at the window's end minus one headway.
        "21600, 36000, 1200, 12",
        // A window that does not divide: the remainder still gets a departure, so the gap never exceeds
        // the advertised headway.
        "21600, 36300, 1200, 13",
        // A single departure, which is what a short special-service window looks like.
        "21600, 22200, 1200, 1",
        // Headway longer than the window: one departure, not zero. A band that produced no service at all
        // would be a silent hole in the timetable.
        "21600, 22200, 3600, 1"
    })
    @DisplayName("the generated trip count is ceil(window / headway)")
    void countMatchesAnalyticalFormula(int fromSec, int toSec, int headwaySec, int expected) {
        List<HeadwayBand> bands = List.of(band(fromSec, toSec, headwaySec));

        var result = generator.generate(request(bands, runningTimes(21600, 90000, 2400)));

        assertThat(TripGenerator.analyticalTripCount(bands)).isEqualTo(expected);
        assertThat(result.trips()).hasSize(expected);
    }

    @Test
    @DisplayName("the count over several bands is the sum of the bands' counts")
    void countAcrossBands() {
        // Morning peak, midday trough, evening peak: 12 + 6 + 12.
        List<HeadwayBand> bands = List.of(
                band(6 * 3600, 10 * 3600, 1200),
                band(10 * 3600, 16 * 3600, 3600),
                band(16 * 3600, 20 * 3600, 1200));

        var result = generator.generate(request(bands, runningTimes(0, 129600, 3000)));

        assertThat(TripGenerator.analyticalTripCount(bands)).isEqualTo(30);
        assertThat(result.trips()).hasSize(30);
        // Departures come out in order and never repeat, which is what the unique constraint on
        // (timetable, pattern, departure) would otherwise have to catch.
        assertThat(result.trips().stream().map(TripGenerator.TripDraft::startSec))
                .isSorted()
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a departure no running-time band covers is an error in strict mode")
    void strictModeRejectsUncoveredDeparture() {
        // Service runs to 20:00 but running times are only known to 10:00.
        List<HeadwayBand> bands = List.of(band(6 * 3600, 20 * 3600, 1200));

        assertThatThrownBy(() -> generator.generate(request(bands, runningTimes(6 * 3600, 10 * 3600, 2400))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("running time");
    }

    @Test
    @DisplayName("outside strict mode the nearest band is used and the substitution is reported")
    void nonStrictModeFallsBackAndWarns() {
        List<HeadwayBand> bands = List.of(band(6 * 3600, 20 * 3600, 1200));

        var request = new TripGenerator.Request(
                PATTERN_ID,
                Direction.UP,
                DayType.WEEKDAY,
                bands,
                runningTimes(6 * 3600, 10 * 3600, 2400),
                12_000.0,
                null,
                false);
        var result = generator.generate(request);

        // Generation succeeds, because refusing would leave a planner with no timetable at all; but the
        // guess is on the record rather than indistinguishable from a known value.
        assertThat(result.trips()).hasSize(TripGenerator.analyticalTripCount(bands));
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    @DisplayName("a trip that runs past midnight keeps seconds beyond 86,400")
    void pastMidnightTripsKeepServiceDaySeconds() {
        // Departs 23:40, takes 40 minutes, so it arrives at 00:20 the next calendar day.
        List<HeadwayBand> bands = List.of(band(85_200, 85_800, 600));

        var result = generator.generate(request(bands, runningTimes(0, 129_600, 2400)));

        // Wrapping to 1,200 would place the arrival before the departure and make the trip look like a
        // data error; the service day is 24 hours long only by coincidence.
        assertThat(result.trips()).hasSize(1);
        assertThat(result.trips().get(0).endSec()).isGreaterThan(86_400);
    }

    @Test
    @DisplayName("an implausible average speed is warned about rather than silently accepted")
    void implausibleSpeedWarns() {
        List<HeadwayBand> bands = List.of(band(6 * 3600, 7 * 3600, 1800));

        // 40 km in 10 minutes is 240 km/h: arithmetically fine, physically not a bus.
        var request = new TripGenerator.Request(
                PATTERN_ID,
                Direction.UP,
                DayType.WEEKDAY,
                bands,
                runningTimes(0, 129_600, 600),
                40_000.0,
                null,
                true);
        var result = generator.generate(request);

        assertThat(result.trips()).isNotEmpty();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("km/h"));
    }

    @Test
    @DisplayName("overlapping headway bands are refused before any trip is generated")
    void overlappingBandsRejected() {
        List<HeadwayBand> bands =
                List.of(band(6 * 3600, 10 * 3600, 1200), band(9 * 3600, 12 * 3600, 1800));

        // The database refuses this too, with an exclusion constraint. Catching it here is what turns a
        // constraint violation into a message a planner can act on.
        assertThatThrownBy(() -> generator.generate(request(bands, runningTimes(0, 129_600, 2400))))
                .isInstanceOf(BusinessRuleException.class);
    }

    private TripGenerator.Request request(List<HeadwayBand> bands, List<RunningTimeBand> runningTimes) {
        return new TripGenerator.Request(
                PATTERN_ID, Direction.UP, DayType.WEEKDAY, bands, runningTimes, 12_000.0, null, true);
    }

    private static HeadwayBand band(int fromSec, int toSec, int headwaySec) {
        return new HeadwayBand(1L, Direction.UP, fromSec, toSec, headwaySec);
    }

    private static List<RunningTimeBand> runningTimes(int fromSec, int toSec, int runningSec) {
        return List.of(new RunningTimeBand(PATTERN_ID, DayType.WEEKDAY, fromSec, toSec, runningSec));
    }
}
