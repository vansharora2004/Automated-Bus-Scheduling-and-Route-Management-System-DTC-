package com.dtc.transit.route.route;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Calendar category deciding which timetable applies. */
public enum DayType {
    WEEKDAY,
    SATURDAY,
    SUNDAY,
    HOLIDAY;

    /**
     * The day type implied by the calendar alone.
     *
     * <p>Holidays cannot be derived from a date, so they only ever arrive through a calendar exception.
     */
    public static DayType fromDate(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY -> SATURDAY;
            case SUNDAY -> SUNDAY;
            default -> WEEKDAY;
        };
    }

    public static DayType fromDayOfWeek(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case SATURDAY -> SATURDAY;
            case SUNDAY -> SUNDAY;
            default -> WEEKDAY;
        };
    }
}
