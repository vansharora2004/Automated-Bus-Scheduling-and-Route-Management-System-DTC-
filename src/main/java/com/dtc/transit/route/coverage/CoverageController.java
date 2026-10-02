package com.dtc.transit.route.coverage;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Coverage endpoints. */
@RestController
@RequestMapping("/api/v1/coverage")
public class CoverageController {

    private final CoverageService coverageService;

    public CoverageController(CoverageService coverageService) {
        this.coverageService = coverageService;
    }

    /**
     * Coverage per zone, worst first.
     *
     * <p>Not paginated. The result is one row per ward, ordered by how badly served it is, and the whole
     * list is the answer: paging it would hide the comparison the report exists to make.
     *
     * @param maxRatio return only zones at or below this coverage, for a gap report
     */
    @GetMapping("/zones")
    public List<CoverageService.ZoneCoverage> zones(
            @RequestParam(required = false) String zoneType,
            @RequestParam(required = false) @DecimalMin("1") @DecimalMax("5000") Double catchmentM,
            @RequestParam(required = false) @DecimalMin("0") @DecimalMax("1") Double maxRatio) {
        double catchment = catchmentM == null ? CoverageService.DEFAULT_CATCHMENT_METRES : catchmentM;
        return coverageService.zoneCoverage(zoneType, catchment, maxRatio);
    }

    /** What a candidate's stops would newly bring within walking distance. */
    @PostMapping("/gain")
    public CoverageService.CoverageGain gain(@Valid @RequestBody CoverageGainRequest request) {
        double catchment =
                request.catchmentM() == null ? CoverageService.DEFAULT_CATCHMENT_METRES : request.catchmentM();
        return coverageService.coverageGain(request.stopIds(), catchment);
    }

    /** Rebuilds the grid. Administrator only, since it discards and recreates every cell. */
    @PostMapping("/grid")
    public GridResponse generateGrid(
            @RequestParam(defaultValue = "250") @DecimalMin("10") @DecimalMax("10000") double cellSizeM) {
        int cells = coverageService.generateGrid(cellSizeM);
        coverageService.refreshCellCoverage();
        return new GridResponse(cells, cellSizeM);
    }

    /** Recomputes per-cell distances after stops change. */
    @PostMapping("/refresh")
    public void refresh() {
        coverageService.refreshCellCoverage();
    }

    public record CoverageGainRequest(
            @NotEmpty List<Long> stopIds, @DecimalMin("1") @DecimalMax("5000") Double catchmentM) {}

    public record GridResponse(int cells, double cellSizeMetres) {}
}
