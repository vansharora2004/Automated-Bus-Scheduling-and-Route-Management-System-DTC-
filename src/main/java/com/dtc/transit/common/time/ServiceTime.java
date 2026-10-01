package com.dtc.transit.common.time;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Conversions between service-day seconds and absolute instants.
 *
 * <p>Trip and duty times are stored as seconds counted from the start of the service day, GTFS
 * style, so a value may exceed 86,400. {@code 25:30:00} is 91,800 seconds and means 01:30 on the
 * following calendar day, still belonging to the earlier service date.
 *
 * <p>Keeping schedule times in this form is what makes post-midnight service natural to reason
 * about. Absolute instants are derived only when a row needs a real timestamp, for example the
 * {@code tstzrange} that the Phase 9 exclusion constraints compare.
 */
public final class ServiceTime {

    public static final int SECONDS_PER_DAY = 86_400;

    private ServiceTime() {}

    /**
     * Formats service-day seconds as {@code HH:mm:ss}, where the hour field may exceed 24.
     *
     * @throws IllegalArgumentException if seconds is negative
     */
    public static String format(int seconds) {
        requireNonNegative(seconds);
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        return "%02d:%02d:%02d".formatted(hours, minutes, secs);
    }

    /**
     * Parses {@code HH:mm:ss} into service-day seconds, accepting hour values above 23.
     *
     * @throws IllegalArgumentException if the text is not a valid service-day time
     */
    public static int parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("service-day time must not be null");
        }
        String[] parts = text.trim().split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("expected HH:mm:ss but got '" + text + "'");
        }
        try {
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            int seconds = Integer.parseInt(parts[2]);
            if (hours < 0 || minutes < 0 || minutes > 59 || seconds < 0 || seconds > 59) {
                throw new IllegalArgumentException("out-of-range field in '" + text + "'");
            }
            return hours * 3600 + minutes * 60 + seconds;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("non-numeric field in '" + text + "'", e);
        }
    }

    /**
     * Resolves service-day seconds to an absolute instant.
     *
     * <p>The result is anchored at the service date's midnight in the given zone, so a seconds value
     * above 86,400 correctly lands on the next calendar day.
     */
    public static Instant toInstant(LocalDate serviceDate, int seconds, ZoneId zone) {
        requireNonNegative(seconds);
        return serviceDate.atStartOfDay(zone).plusSeconds(seconds).toInstant();
    }

    /** Seconds from the service date's midnight to the given instant. May be negative. */
    public static long secondsFromServiceDate(LocalDate serviceDate, Instant instant, ZoneId zone) {
        return Duration.between(serviceDate.atStartOfDay(zone).toInstant(), instant).toSeconds();
    }

    /** True when the value falls on the calendar day after the service date. */
    public static boolean isAfterMidnight(int seconds) {
        requireNonNegative(seconds);
        return seconds >= SECONDS_PER_DAY;
    }

    private static void requireNonNegative(int seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("service-day seconds must not be negative: " + seconds);
        }
    }
}
