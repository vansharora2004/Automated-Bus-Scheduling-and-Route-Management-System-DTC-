package com.dtc.transit.timetable.calendar;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dtc.transit.route.route.DayType;

/** Calendar exceptions, and the resolved day type for a date. */
@RestController
@RequestMapping("/api/v1/calendar")
public class CalendarController {

    private final ServiceCalendar calendar;

    public CalendarController(ServiceCalendar calendar) {
        this.calendar = calendar;
    }

    /**
     * What kind of day a date is, and why.
     *
     * <p>The reason is part of the answer. A planner seeing Sunday service on a Wednesday needs to be able to
     * tell a holiday override from a bug.
     */
    @GetMapping("/day-type")
    public ServiceCalendar.Resolution dayType(
            @RequestParam LocalDate serviceDate, @RequestParam(required = false) Long depotId) {
        return calendar.resolve(serviceDate, depotId);
    }

    @GetMapping("/exceptions")
    public List<ExceptionResponse> exceptions(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return calendar.exceptionsBetween(from, to).stream()
                .map(ExceptionResponse::from)
                .toList();
    }

    @PostMapping("/exceptions")
    public ExceptionResponse addException(@Valid @RequestBody AddExceptionRequest request) {
        return ExceptionResponse.from(calendar.addException(
                request.serviceDate(), request.depotId(), request.dayTypeOverride(), request.note()));
    }

    /** @param depotId null for a network-wide override, such as a public holiday */
    public record AddExceptionRequest(
            @NotNull LocalDate serviceDate,
            Long depotId,
            @NotNull DayType dayTypeOverride,
            @Size(max = 500) String note) {}

    public record ExceptionResponse(
            Long id, LocalDate serviceDate, Long depotId, DayType dayTypeOverride, boolean networkWide,
            String note) {

        static ExceptionResponse from(CalendarException exception) {
            return new ExceptionResponse(
                    exception.getId(),
                    exception.getServiceDate(),
                    exception.getDepotId(),
                    exception.getDayTypeOverride(),
                    exception.isNetworkWide(),
                    exception.getNote());
        }
    }
}
