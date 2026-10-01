package com.dtc.transit.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Service-day time handling. Covers edge cases EC-TIME-01 and EC-TIME-03: trips past midnight belong
 * to the previous service date, and the hour field may exceed 23.
 */
class ServiceTimeTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Nested
    @DisplayName("formatting")
    class Formatting {

        @ParameterizedTest
        @CsvSource({
            "0, 00:00:00",
            "3600, 01:00:00",
            "86399, 23:59:59",
            "86400, 24:00:00",
            "89400, 24:50:00",
            "91800, 25:30:00"
        })
        @DisplayName("hours may exceed 23 for post-midnight service")
        void formatsBeyondMidnight(int seconds, String expected) {
            assertThat(ServiceTime.format(seconds)).isEqualTo(expected);
        }

        @Test
        void rejectsNegativeSeconds() {
            assertThatThrownBy(() -> ServiceTime.format(-1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @ParameterizedTest
        @CsvSource({"00:00:00, 0", "01:00:00, 3600", "24:50:00, 89400", "25:30:00, 91800"})
        void parsesServiceDayTimes(String text, int expected) {
            assertThat(ServiceTime.parse(text)).isEqualTo(expected);
        }

        @Test
        void roundTripsThroughFormat() {
            int original = 89_400;
            assertThat(ServiceTime.parse(ServiceTime.format(original))).isEqualTo(original);
        }

        @ParameterizedTest
        @CsvSource({"'', ''", "'10:00', ''", "'aa:bb:cc', ''", "'10:60:00', ''", "'10:00:60', ''"})
        void rejectsMalformedInput(String text) {
            assertThatThrownBy(() -> ServiceTime.parse(text)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("absolute instants")
    class Absolute {

        @Test
        @DisplayName("EC-TIME-01: a trip at 24:50 lands on the day after its service date")
        void postMidnightResolvesToNextCalendarDay() {
            LocalDate serviceDate = LocalDate.of(2026, 3, 14);

            Instant actual = ServiceTime.toInstant(serviceDate, ServiceTime.parse("24:50:00"), IST);

            // 00:50 IST on 15 March is 19:20 UTC on 14 March.
            assertThat(actual).isEqualTo(Instant.parse("2026-03-14T19:20:00Z"));
        }

        @Test
        void secondsFromServiceDateIsTheInverse() {
            LocalDate serviceDate = LocalDate.of(2026, 3, 14);
            int seconds = 89_400;

            Instant instant = ServiceTime.toInstant(serviceDate, seconds, IST);

            assertThat(ServiceTime.secondsFromServiceDate(serviceDate, instant, IST)).isEqualTo(seconds);
        }

        @Test
        @DisplayName("results do not depend on the JVM default timezone (EC-TIME-04)")
        void independentOfDefaultZone() {
            LocalDate serviceDate = LocalDate.of(2026, 3, 14);

            Instant viaIst = ServiceTime.toInstant(serviceDate, 36_000, IST);
            Instant viaNewYork = ServiceTime.toInstant(serviceDate, 36_000, ZoneId.of("America/New_York"));

            // Same service-day seconds, different zone, so a different instant. The conversion is
            // explicit about its zone rather than silently using the JVM default.
            assertThat(viaIst).isNotEqualTo(viaNewYork);
        }
    }

    @Test
    void identifiesPostMidnightValues() {
        assertThat(ServiceTime.isAfterMidnight(86_399)).isFalse();
        assertThat(ServiceTime.isAfterMidnight(86_400)).isTrue();
    }
}
