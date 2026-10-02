package com.dtc.transit.masterdata.depot;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dtc.transit.common.paging.PageResponse;

/** Depot endpoints. */
@RestController
@RequestMapping("/api/v1/depots")
public class DepotController {

    private final DepotService depotService;

    public DepotController(DepotService depotService) {
        this.depotService = depotService;
    }

    @GetMapping
    public PageResponse<DepotResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(sort = "code") Pageable pageable) {
        return PageResponse.of(depotService.search(q, active, pageable), DepotResponse::from);
    }

    @GetMapping("/{id}")
    public DepotResponse get(@PathVariable Long id) {
        return DepotResponse.from(depotService.get(id));
    }

    @PostMapping
    public ResponseEntity<DepotResponse> create(@Valid @RequestBody CreateDepotRequest request) {
        Depot depot = depotService.create(
                request.code(),
                request.name(),
                request.longitude(),
                request.latitude(),
                request.parkingCapacity(),
                request.chargingBays() == null ? 0 : request.chargingBays());
        return ResponseEntity.created(URI.create("/api/v1/depots/" + depot.getId()))
                .body(DepotResponse.from(depot));
    }

    /**
     * @param longitude first, matching GeoJSON and PostGIS order rather than the "lat, long" people say
     */
    public record CreateDepotRequest(
            @NotBlank @Size(max = 20) String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @Min(1) Integer parkingCapacity,
            @Min(0) Integer chargingBays) {}

    public record DepotResponse(
            Long id,
            String code,
            String name,
            double longitude,
            double latitude,
            Integer parkingCapacity,
            int chargingBays,
            boolean active,
            long version) {

        static DepotResponse from(Depot depot) {
            return new DepotResponse(
                    depot.getId(),
                    depot.getCode(),
                    depot.getName(),
                    depot.getLocation().getX(),
                    depot.getLocation().getY(),
                    depot.getParkingCapacity(),
                    depot.getChargingBays(),
                    depot.isActive(),
                    depot.getVersion());
        }
    }
}
