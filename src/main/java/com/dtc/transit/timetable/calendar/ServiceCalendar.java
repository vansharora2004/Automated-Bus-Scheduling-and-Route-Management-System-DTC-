package com.dtc.transit.timetable.calendar;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.route.route.DayType;

/**
 * Resolves which kind of day a service date is.
 *
 * <p>Two sources, in a fixed order of precedence: a calendar exception if one exists, otherwise the day of
 * the week. Everything downstream depends on this answer, because the day type selects the timetable, which
 * selects the trips, which drive the whole schedule.
 */
@Service
public class ServiceCalendar {

    private final CalendarExceptionRepository exceptions;
    private final org.springframework.context.ApplicationEventPublisher events;

    public ServiceCalendar(
            CalendarExceptionRepository exceptions, org.springframework.context.ApplicationEventPublisher events) {
        this.exceptions = exceptions;
        this.events = events;
    }

    /**
     * The day type in force for a date at a depot.
     *
     * @param depotId may be null to ask for the network-wide answer
     */
    @Transactional(readOnly = true)
    public Resolution resolve(LocalDate serviceDate, Long depotId) {
        Optional<CalendarException> override = depotId == null
                ? exceptions.findNetworkWide(serviceDate)
                : exceptions.findForDateAndDepot(serviceDate, depotId);

        return override.map(exception -> new Resolution(
                        exception.getDayTypeOverride(),
                        true,
                        exception.isNetworkWide() ? "network-wide override" : "depot override",
                        exception.getNote()))
                .orElseGet(() -> new Resolution(
                        DayType.fromDate(serviceDate), false, "day of week", null));
    }

    /** Overrides in a date range, for a planner reviewing the calendar. */
    @Transactional(readOnly = true)
    public java.util.List<CalendarException> exceptionsBetween(LocalDate from, LocalDate to) {
        return exceptions.findByServiceDateBetweenOrderByServiceDateAsc(from, to);
    }

    /** Records an override, such as a holiday or a special event. */
    @PreAuthorize("hasAnyRole('ADMIN','PLANNER')")
    @Transactional
    public CalendarException addException(
            LocalDate serviceDate, Long depotId, DayType dayType, String note) {
        var exception = exceptions.save(new CalendarException(serviceDate, depotId, dayType, note));
        events.publishEvent(new AuditEvent(
                "CALENDAR_EXCEPTION_ADDED",
                "CALENDAR",
                serviceDate.toString(),
                null,
                """
                {"dayType":"%s","depotId":%s}""".formatted(dayType, depotId == null ? "null" : depotId),
                note));
        return exception;
    }

    /**
     * Which day type applied and why.
     *
     * @param overridden true when a calendar exception decided it rather than the calendar
     * @param source a short explanation, so a run's metadata can record what it used and a planner can tell
     *     a holiday timetable from a mistake
     */
    public record Resolution(DayType dayType, boolean overridden, String source, String note) {}
}
