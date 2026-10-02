package com.dtc.transit.masterdata.stop;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dtc.transit.common.paging.PageResponse;

/** Stop endpoints, including the spatial filters. */
@RestController
@RequestMapping("/api/v1/stops")
public class StopController {

    private final StopService stopService;

    public StopController(StopService stopService) {
        this.stopService = stopService;
    }

    /**
     * Lists stops.
     *
     * <p>The filter record is bound straight from the query string, so {@code terminal=maybe} is a 400
     * rather than being quietly dropped.
     */
    @GetMapping
    public PageResponse<StopResponse> list(StopFilter filter, @PageableDefault(sort = "code") Pageable pageable) {
        return PageResponse.of(stopService.search(filter, pageable), StopResponse::from);
    }

    @GetMapping("/{id}")
    public StopResponse get(@PathVariable Long id) {
        return StopResponse.from(stopService.get(id));
    }

    @PostMapping
    public ResponseEntity<StopResponse> create(@Valid @RequestBody CreateStopRequest request) {
        Stop stop = stopService.create(new StopService.NewStop(
                request.code(),
                request.name(),
                request.longitude(),
                request.latitude(),
                Boolean.TRUE.equals(request.terminal()),
                Boolean.TRUE.equals(request.reliefPoint()),
                Boolean.TRUE.equals(request.crewFacilities())));
        return ResponseEntity.created(URI.create("/api/v1/stops/" + stop.getId()))
                .body(StopResponse.from(stop));
    }

    public record CreateStopRequest(
            @NotBlank @Size(max = 20) String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            Boolean terminal,
            Boolean reliefPoint,
            Boolean crewFacilities) {}

    public record StopResponse(
            Long id,
            String code,
            String name,
            double longitude,
            double latitude,
            boolean terminal,
            boolean reliefPoint,
            boolean crewFacilities,
            boolean active,
            long version) {

        static StopResponse from(Stop stop) {
            return new StopResponse(
                    stop.getId(),
                    stop.getCode(),
                    stop.getName(),
                    stop.getLocation().getX(),
                    stop.getLocation().getY(),
                    stop.isTerminal(),
                    stop.isReliefPoint(),
                    stop.hasCrewFacilities(),
                    stop.isActive(),
                    stop.getVersion());
        }
    }
}
