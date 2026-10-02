package com.dtc.transit.reporting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The live operations view for the current service day.
 *
 * <p>Cached for thirty seconds by the service. An operations screen polls every few seconds and the queries scan
 * today's duties and assignments, so without the cache fifty open screens would cost fifty scans.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * Today's snapshot.
     *
     * @param depotId omit for every depot the caller may see; a depot-bound caller always gets their own
     */
    @GetMapping("/today")
    public DashboardService.Snapshot today(@RequestParam(required = false) Long depotId) {
        return dashboardService.today(depotId);
    }
}
