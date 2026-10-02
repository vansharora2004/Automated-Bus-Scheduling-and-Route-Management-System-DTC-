package com.dtc.transit.masterdata.bus;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.dtc.transit.common.csv.CsvImportResult;
import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.common.paging.PageResponse;

/** Bus endpoints. */
@RestController
@RequestMapping("/api/v1/buses")
public class BusController {

    private final BusService busService;
    private final BusCsvImporter csvImporter;

    public BusController(BusService busService, BusCsvImporter csvImporter) {
        this.busService = busService;
        this.csvImporter = csvImporter;
    }

    @GetMapping
    public PageResponse<BusResponse> list(BusFilter filter, @PageableDefault(sort = "fleetNo") Pageable pageable) {
        return PageResponse.of(busService.search(filter, pageable), BusResponse::from);
    }

    @GetMapping("/{id}")
    public BusResponse get(@PathVariable Long id) {
        return BusResponse.from(busService.get(id));
    }

    @PostMapping
    public ResponseEntity<BusResponse> create(@Valid @RequestBody CreateBusRequest request) {
        Bus bus = busService.create(new BusService.NewBus(
                request.registrationNo(),
                request.fleetNo(),
                request.depotId(),
                request.busType(),
                request.fuelType(),
                Boolean.TRUE.equals(request.airConditioned()),
                request.capacity(),
                request.evRangeKm()));
        return ResponseEntity.created(URI.create("/api/v1/buses/" + bus.getId()))
                .body(BusResponse.from(bus));
    }

    @PatchMapping("/{id}/status")
    public BusResponse changeStatus(@PathVariable Long id, @Valid @RequestBody ChangeStatusRequest request) {
        return BusResponse.from(busService.changeStatus(
                id, request.status(), request.unavailableFrom(), request.unavailableTo(), request.note()));
    }

    @GetMapping("/{id}/unavailability")
    public List<UnavailabilityResponse> unavailability(@PathVariable Long id) {
        return busService.unavailabilityFor(id).stream()
                .map(UnavailabilityResponse::from)
                .toList();
    }

    /**
     * Bulk import.
     *
     * <p>{@code dryRun} defaults to true. Making the safe choice the default means a mistyped request
     * validates a file rather than writing several thousand rows nobody has reviewed.
     */
    // Authorization for this path is declared in SecurityConfig at the URL level, not here.
    // An annotation on a controller method makes Spring proxy the controller, which breaks OpenAPI
    // generation for the nested response records and still lets multipart resolution run first.
    @PostMapping("/import")
    public CsvImportResult importCsv(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
        if (file.isEmpty()) {
            throw new BusinessRuleException("CSV_EMPTY", "The uploaded file is empty");
        }
        try (var stream = file.getInputStream()) {
            return csvImporter.importBuses(stream, dryRun);
        } catch (IOException e) {
            throw new BusinessRuleException("CSV_UNREADABLE", "The upload could not be read: " + e.getMessage());
        }
    }

    public record CreateBusRequest(
            @NotBlank @Size(max = 32) String registrationNo,
            @NotBlank @Size(max = 32) String fleetNo,
            @NotNull Long depotId,
            @NotNull BusType busType,
            @NotNull FuelType fuelType,
            Boolean airConditioned,
            @NotNull @Min(1) Integer capacity,
            @Min(1) Integer evRangeKm) {}

    /**
     * @param unavailableFrom optional; supplying a window records why the bus is out and for how long
     */
    public record ChangeStatusRequest(
            @NotNull BusStatus status, Instant unavailableFrom, Instant unavailableTo, @Size(max = 500) String note) {}

    public record BusResponse(
            Long id,
            String registrationNo,
            String registrationNoAsEntered,
            String fleetNo,
            Long depotId,
            BusType busType,
            FuelType fuelType,
            boolean airConditioned,
            int capacity,
            Integer evRangeKm,
            BusStatus status,
            long version) {

        static BusResponse from(Bus bus) {
            return new BusResponse(
                    bus.getId(),
                    bus.getRegistrationNo(),
                    bus.getRegistrationNoRaw(),
                    bus.getFleetNo(),
                    bus.getDepot().getId(),
                    bus.getBusType(),
                    bus.getFuelType(),
                    bus.isAirConditioned(),
                    bus.getCapacity(),
                    bus.getEvRangeKm(),
                    bus.getStatus(),
                    bus.getVersion());
        }
    }

    public record UnavailabilityResponse(
            Long id, Instant startsAt, Instant endsAt, UnavailabilityReason reason, String note) {

        static UnavailabilityResponse from(BusUnavailability unavailability) {
            return new UnavailabilityResponse(
                    unavailability.getId(),
                    unavailability.getStartsAt(),
                    unavailability.getEndsAt(),
                    unavailability.getReason(),
                    unavailability.getNote());
        }
    }
}
