package com.dtc.transit.reporting;

import java.io.IOException;
import java.io.Writer;
import java.time.LocalDate;
import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * KPI reports over published schedules.
 *
 * <p>Every figure here comes from a materialized view that filters on published status. A superseded version
 * carries the same blocks and duties, so including one would double count a republished day — which is the kind
 * of error that makes a whole reporting suite quietly untrustworthy.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/fleet-utilization")
    public ReportService.Page<ReportService.FleetUtilizationRow> fleetUtilization(
            @RequestParam(required = false) Long depotId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return reportService.fleetUtilization(depotId, from, to, Math.max(0, page), size);
    }

    @GetMapping("/crew-hours")
    public ReportService.Page<ReportService.CrewHoursRow> crewHours(
            @RequestParam(required = false) Long depotId,
            @RequestParam(required = false) Integer isoYear,
            @RequestParam(required = false) Integer isoWeek,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return reportService.crewHours(depotId, isoYear, isoWeek, Math.max(0, page), size);
    }

    @GetMapping("/schedule-kpis")
    public ReportService.Page<ReportService.ScheduleKpiRow> scheduleKpis(
            @RequestParam(required = false) Long depotId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return reportService.scheduleKpis(depotId, from, to, Math.max(0, page), size);
    }

    /**
     * Fleet utilization as CSV, streamed.
     *
     * <p>Written to the response as rows arrive rather than assembled first. A year across 45 depots is sixteen
     * thousand rows; building that in memory to write it out is the kind of endpoint that works in testing and
     * falls over the first time somebody exports two years.
     *
     * <p>RFC 4180 quoting, because a depot name could contain a comma and a CSV that breaks on one is worse than
     * no export at all.
     */
    @GetMapping(path = "/fleet-utilization.csv", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> fleetUtilizationCsv(
            @RequestParam(required = false) Long depotId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        StreamingResponseBody body = outputStream -> {
            try (Writer writer = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8))) {
                writer.write("depotId,serviceDate,blocks,busesUsed,peakVehicles,serviceKm,deadKm,"
                        + "deadKmRatio,inServiceRatio\n");
                reportService.streamFleetUtilization(depotId, from, to, row -> {
                    try {
                        writer.write(csvRow(row));
                    } catch (IOException e) {
                        // The client hung up mid-export. Unchecked so it escapes the consumer, where the
                        // container turns it into a dropped response rather than a logged stack trace per row.
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            }
        };

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"fleet-utilization.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    /**
     * Rebuilds the report views.
     *
     * <p>Manual as well as scheduled, because after publishing a correction a manager wants the numbers now
     * rather than at the next refresh.
     */
    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh() {
        reportService.refreshAll();
        return ResponseEntity.noContent().build();
    }

    /** One CSV line. Numbers are formatted with a fixed locale, or a comma could become a decimal separator. */
    private static String csvRow(ReportService.FleetUtilizationRow row) {
        return String.format(
                Locale.ROOT,
                "%d,%s,%d,%d,%d,%.2f,%.2f,%s,%s%n",
                row.depotId(),
                row.serviceDate(),
                row.blocks(),
                row.busesUsed(),
                row.peakVehicles(),
                row.serviceKm(),
                row.deadKm(),
                ratio(row.deadKmRatio()),
                ratio(row.inServiceRatio()));
    }

    /** An empty field for a null ratio, which is how CSV expresses "no value" without lying with a zero. */
    private static String ratio(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.4f", value);
    }
}
