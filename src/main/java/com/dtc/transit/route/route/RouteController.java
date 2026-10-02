package com.dtc.transit.route.route;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dtc.transit.common.geo.GeoJsonCodec;
import com.dtc.transit.common.paging.PageResponse;
import com.dtc.transit.route.overlap.OverlapAnalysisService;
import com.dtc.transit.route.overlap.OverlapFinding;
import com.dtc.transit.route.overlap.OverlapSeverity;
import com.dtc.transit.route.overlap.RouteOverlap;
import com.dtc.transit.route.pattern.RoutePattern;
import com.fasterxml.jackson.databind.JsonNode;

/** Route endpoints, including ad-hoc overlap analysis. */
@RestController
@RequestMapping("/api/v1/routes")
public class RouteController {

    private final RouteService routeService;
    private final OverlapAnalysisService overlapAnalysis;
    private final GeoJsonCodec geoJson;

    public RouteController(
            RouteService routeService, OverlapAnalysisService overlapAnalysis, GeoJsonCodec geoJson) {
        this.routeService = routeService;
        this.overlapAnalysis = overlapAnalysis;
        this.geoJson = geoJson;
    }

    @GetMapping
    public PageResponse<RouteResponse> list(
            @RequestParam(required = false) String routeNo,
            @RequestParam(required = false) Long depotId,
            @RequestParam(required = false) RouteStatus status,
            @PageableDefault(sort = "routeNo") Pageable pageable) {
        return PageResponse.of(routeService.search(routeNo, depotId, status, pageable), RouteResponse::from);
    }

    /** A route with its patterns as GeoJSON, which is what a map client needs. */
    @GetMapping("/{id}")
    public RouteDetailResponse get(@PathVariable Long id) {
        Route route = routeService.get(id);
        List<PatternResponse> patterns = routeService.patternsOf(id).stream()
                .map(pattern -> PatternResponse.from(pattern, geoJson))
                .toList();
        return new RouteDetailResponse(RouteResponse.from(route), patterns);
    }

    @PostMapping
    public ResponseEntity<RouteResponse> create(@Valid @RequestBody CreateRouteRequest request) {
        Route route = routeService.create(request.routeNo(), request.name(), request.depotId());
        return ResponseEntity.created(URI.create("/api/v1/routes/" + route.getId()))
                .body(RouteResponse.from(route));
    }

    /**
     * Replaces one direction's geometry and stop list.
     *
     * <p>PUT rather than PATCH: a pattern is replaced wholesale, because a half-updated line and stop
     * sequence would be meaningless.
     */
    @PutMapping("/{id}/patterns/{direction}")
    public PatternResponse replacePattern(
            @PathVariable Long id,
            @PathVariable Direction direction,
            @Valid @RequestBody ReplacePatternRequest request) {
        var result = routeService.replacePattern(
                id,
                direction,
                geoJson.readLineString(request.geometry().toString()),
                request.stopIds() == null ? List.of() : request.stopIds(),
                Boolean.TRUE.equals(request.simplify()));
        return PatternResponse.from(result.pattern(), geoJson, result.warnings());
    }

    /**
     * Replaces the running times for a direction and day type.
     *
     * <p>Running times live on the pattern because they describe the road, not the timetable. Trip generation
     * reads them to turn a departure time into an arrival time.
     */
    @PutMapping("/{id}/patterns/{direction}/running-times")
    public List<RunningTimeResponse> replaceRunningTimes(
            @PathVariable Long id,
            @PathVariable Direction direction,
            @Valid @RequestBody ReplaceRunningTimesRequest request) {
        return routeService
                .replaceRunningTimes(
                        id,
                        direction,
                        request.dayType(),
                        request.bands().stream()
                                .map(band -> new RouteService.RunningTimeSpec(
                                        band.fromSec(), band.toSec(), band.runningSec()))
                                .toList())
                .stream()
                .map(RunningTimeResponse::from)
                .toList();
    }

    @GetMapping("/{id}/patterns/{direction}/running-times")
    public List<RunningTimeResponse> runningTimes(@PathVariable Long id, @PathVariable Direction direction) {
        return routeService.runningTimesOf(id, direction).stream()
                .map(RunningTimeResponse::from)
                .toList();
    }

    /**
     * Overlap analysis for a geometry that need not be stored yet.
     *
     * <p>This is the endpoint a planner uses while drawing. Requiring the route to be saved first would
     * mean creating and withdrawing routes just to ask a question.
     */
    @PostMapping("/overlap-analysis")
    public OverlapAnalysisResponse analyseOverlap(@Valid @RequestBody OverlapAnalysisRequest request) {
        var settings = new OverlapAnalysisService.Settings(
                request.bufferM() == null ? OverlapAnalysisService.DEFAULT_BUFFER_METRES : request.bufferM(),
                request.minSegmentM() == null
                        ? OverlapAnalysisService.DEFAULT_MIN_SEGMENT_METRES
                        : request.minSegmentM());

        List<OverlapFinding> findings = overlapAnalysis.analyse(
                geoJson.readLineString(request.geometry().toString()),
                settings,
                request.stopIds() == null ? List.of() : request.stopIds());

        return new OverlapAnalysisResponse(
                settings.bufferMetres(),
                settings.minSegmentMetres(),
                findings.stream().map(OverlapFindingResponse::from).toList());
    }

    @GetMapping("/{id}/overlaps")
    public PageResponse<StoredOverlapResponse> overlaps(
            @PathVariable Long id, @PageableDefault Pageable pageable) {
        return PageResponse.of(routeService.storedOverlaps(id, pageable), StoredOverlapResponse::from);
    }

    @PostMapping("/{id}/submit")
    public RouteResponse submit(@PathVariable Long id) {
        return RouteResponse.from(routeService.submitForReview(id));
    }

    @PostMapping("/{id}/decision")
    public RouteResponse decide(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return RouteResponse.from(
                routeService.decide(id, Boolean.TRUE.equals(request.approve()), request.note()));
    }

    @PostMapping("/{id}/activate")
    public RouteResponse activate(@PathVariable Long id, @Valid @RequestBody ActivateRequest request) {
        return RouteResponse.from(routeService.activate(id, request.effectiveFrom()));
    }

    @PostMapping("/{id}/retire")
    public RouteResponse retire(@PathVariable Long id) {
        return RouteResponse.from(routeService.retire(id));
    }

    public record CreateRouteRequest(
            @NotBlank @Size(max = 20) String routeNo,
            @NotBlank @Size(max = 200) String name,
            @NotNull Long depotId) {}

    /**
     * @param geometry an embedded GeoJSON LineString object in WGS84, longitude first. A nested object
     *     rather than a quoted string, because that is what a mapping client produces and what a reader
     *     of the API expects.
     * @param simplify whether to thin a noisy traced line before the vertex cap is checked
     */
    public record ReplacePatternRequest(
            @NotNull JsonNode geometry, List<Long> stopIds, Boolean simplify) {}

    public record ReplaceRunningTimesRequest(
            @NotNull DayType dayType, @NotEmpty @Valid List<RunningTimeBandRequest> bands) {}

    /**
     * @param fromSec start of the window in service-day seconds, inclusive
     * @param toSec end of the window, exclusive
     * @param runningSec how long the whole pattern takes to drive in that window
     */
    public record RunningTimeBandRequest(
            @NotNull @Min(0) Integer fromSec,
            @NotNull @Min(1) Integer toSec,
            @NotNull @Min(60) Integer runningSec) {}

    public record RunningTimeResponse(DayType dayType, int fromSec, int toSec, int runningSec) {

        static RunningTimeResponse from(com.dtc.transit.route.pattern.RunningTimeBand band) {
            return new RunningTimeResponse(
                    band.getDayType(), band.getFromSec(), band.getToSec(), band.getRunningSec());
        }
    }

    public record OverlapAnalysisRequest(
            @NotNull JsonNode geometry, List<Long> stopIds, Double bufferM, Double minSegmentM) {}

    /** @param approve true to approve, false to reject; a rejection must carry a note */
    public record DecisionRequest(@NotNull Boolean approve, @Size(max = 1000) String note) {}

    public record ActivateRequest(@NotNull LocalDate effectiveFrom) {}

    public record RouteResponse(
            Long id,
            String routeNo,
            String name,
            Long depotId,
            RouteStatus status,
            LocalDate effectiveFrom,
            String submittedBy,
            String decidedBy,
            String decisionNote,
            long version) {

        static RouteResponse from(Route route) {
            return new RouteResponse(
                    route.getId(),
                    route.getRouteNo(),
                    route.getName(),
                    route.getDepot().getId(),
                    route.getStatus(),
                    route.getEffectiveFrom(),
                    route.getSubmittedBy(),
                    route.getDecidedBy(),
                    route.getDecisionNote(),
                    route.getVersion());
        }
    }

    public record RouteDetailResponse(RouteResponse route, List<PatternResponse> patterns) {}

    /** @param warnings populated on write; empty on read */
    public record PatternResponse(
            Long id, Direction direction, Double lengthM, String geometry, long version, List<String> warnings) {

        static PatternResponse from(RoutePattern pattern, GeoJsonCodec codec) {
            return from(pattern, codec, List.of());
        }

        static PatternResponse from(RoutePattern pattern, GeoJsonCodec codec, List<String> warnings) {
            return new PatternResponse(
                    pattern.getId(),
                    pattern.getDirection(),
                    pattern.getLengthM(),
                    codec.write(pattern.getGeom()),
                    pattern.getVersion(),
                    warnings);
        }
    }

    /** @param looksLikeDuplication all three signals agreeing, not geometry alone */
    public record OverlapFindingResponse(
            Long patternId,
            Long routeId,
            String routeNo,
            String routeName,
            Direction direction,
            double overlapMetres,
            double overlapRatio,
            int sharedStops,
            boolean sameDirection,
            OverlapSeverity severity,
            boolean looksLikeDuplication) {

        static OverlapFindingResponse from(OverlapFinding finding) {
            return new OverlapFindingResponse(
                    finding.patternId(),
                    finding.routeId(),
                    finding.routeNo(),
                    finding.routeName(),
                    finding.direction(),
                    finding.overlapMetres(),
                    finding.overlapRatio(),
                    finding.sharedStops(),
                    finding.sameDirection(),
                    finding.severity(),
                    finding.looksLikeDuplication());
        }
    }

    public record OverlapAnalysisResponse(
            double bufferMetres, double minSegmentMetres, List<OverlapFindingResponse> findings) {}

    public record StoredOverlapResponse(
            Long proposedPatternId,
            Long existingPatternId,
            double overlapMetres,
            double overlapRatio,
            int sharedStops,
            boolean sameDirection,
            OverlapSeverity severity,
            Instant computedAt) {

        static StoredOverlapResponse from(RouteOverlap overlap) {
            return new StoredOverlapResponse(
                    overlap.getProposedPatternId(),
                    overlap.getExistingPatternId(),
                    overlap.getOverlapMetres(),
                    overlap.getOverlapRatio(),
                    overlap.getSharedStops(),
                    overlap.isSameDirection(),
                    overlap.getSeverity(),
                    overlap.getComputedAt());
        }
    }
}
