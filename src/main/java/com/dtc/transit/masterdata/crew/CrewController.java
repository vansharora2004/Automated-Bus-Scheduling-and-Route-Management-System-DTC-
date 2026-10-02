package com.dtc.transit.masterdata.crew;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.dtc.transit.common.csv.CsvImportResult;
import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.common.paging.PageResponse;

/** Crew endpoints. */
@RestController
@RequestMapping("/api/v1/crew")
public class CrewController {

    private final CrewService crewService;
    private final CrewCsvImporter csvImporter;

    public CrewController(CrewService crewService, CrewCsvImporter csvImporter) {
        this.crewService = crewService;
        this.csvImporter = csvImporter;
    }

    @GetMapping
    public PageResponse<CrewResponse> list(
            CrewFilter filter, @PageableDefault(sort = "employeeCode") Pageable pageable) {
        return PageResponse.of(crewService.search(filter, pageable), CrewResponse::from);
    }

    @GetMapping("/{id}")
    public CrewResponse get(@PathVariable Long id) {
        return CrewResponse.from(crewService.get(id));
    }

    @PostMapping
    public ResponseEntity<CrewResponse> create(@Valid @RequestBody CreateCrewRequest request) {
        CrewMember member = crewService.create(new CrewService.NewCrewMember(
                request.employeeCode(),
                request.name(),
                request.crewRole(),
                request.depotId(),
                request.licenceNo(),
                request.licenceClass(),
                request.licenceExpiry(),
                request.weeklyOffDayOfWeek(),
                request.joinedOn()));
        return ResponseEntity.created(URI.create("/api/v1/crew/" + member.getId()))
                .body(CrewResponse.from(member));
    }

    @PostMapping("/{id}/leaves")
    public ResponseEntity<LeaveResponse> recordLeave(
            @PathVariable Long id, @Valid @RequestBody RecordLeaveRequest request) {
        CrewLeave leave = crewService.recordLeave(
                id, request.from(), request.to(), request.leaveType(), request.note());
        return ResponseEntity.created(URI.create("/api/v1/crew/" + id + "/leaves"))
                .body(LeaveResponse.from(leave));
    }

    @GetMapping("/{id}/leaves")
    public List<LeaveResponse> leaves(@PathVariable Long id) {
        return crewService.leaveFor(id).stream().map(LeaveResponse::from).toList();
    }

    @PostMapping("/{id}/qualifications")
    public ResponseEntity<QualificationResponse> addQualification(
            @PathVariable Long id, @Valid @RequestBody AddQualificationRequest request) {
        CrewQualification qualification =
                crewService.addQualification(id, request.code(), request.validUntil());
        return ResponseEntity.created(URI.create("/api/v1/crew/" + id + "/qualifications"))
                .body(QualificationResponse.from(qualification));
    }

    @GetMapping("/{id}/qualifications")
    public List<QualificationResponse> qualifications(@PathVariable Long id) {
        return crewService.qualificationsFor(id).stream()
                .map(QualificationResponse::from)
                .toList();
    }

    @GetMapping("/{id}/depot-history")
    public List<DepotHistoryResponse> depotHistory(@PathVariable Long id) {
        return crewService.depotHistoryFor(id).stream()
                .map(DepotHistoryResponse::from)
                .toList();
    }

    @PostMapping("/{id}/transfer")
    public CrewResponse transfer(@PathVariable Long id, @Valid @RequestBody TransferRequest request) {
        return CrewResponse.from(crewService.transfer(id, request.depotId(), request.effectiveFrom()));
    }

    /** Bulk import. {@code dryRun} defaults to true, so the safe path is the default. */
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
            return csvImporter.importCrew(stream, dryRun);
        } catch (IOException e) {
            throw new BusinessRuleException("CSV_UNREADABLE", "The upload could not be read: " + e.getMessage());
        }
    }

    public record CreateCrewRequest(
            @NotBlank @Size(max = 32) String employeeCode,
            @NotBlank @Size(max = 200) String name,
            @NotNull CrewRole crewRole,
            @NotNull Long depotId,
            @Size(max = 32) String licenceNo,
            LicenceClass licenceClass,
            LocalDate licenceExpiry,
            @Min(1) @Max(7) Integer weeklyOffDayOfWeek,
            LocalDate joinedOn) {}

    public record RecordLeaveRequest(
            @NotNull Instant from, @NotNull Instant to, @NotNull LeaveType leaveType, @Size(max = 500) String note) {}

    public record AddQualificationRequest(@NotBlank @Size(max = 32) String code, LocalDate validUntil) {}

    public record TransferRequest(@NotNull Long depotId, @NotNull LocalDate effectiveFrom) {}

    /** Response view. Licence number is included only in part, since it is personal data. */
    public record CrewResponse(
            Long id,
            String employeeCode,
            String name,
            CrewRole crewRole,
            Long depotId,
            LicenceClass licenceClass,
            LocalDate licenceExpiry,
            CrewStatus status,
            Integer weeklyOffDayOfWeek,
            long version) {

        static CrewResponse from(CrewMember member) {
            return new CrewResponse(
                    member.getId(),
                    member.getEmployeeCode(),
                    member.getName(),
                    member.getCrewRole(),
                    member.getDepot().getId(),
                    member.getLicenceClass(),
                    member.getLicenceExpiry(),
                    member.getStatus(),
                    member.getWeeklyOffDayOfWeek(),
                    member.getVersion());
        }
    }

    public record LeaveResponse(
            Long id, Instant startsAt, Instant endsAt, LeaveType leaveType, String note) {

        static LeaveResponse from(CrewLeave leave) {
            return new LeaveResponse(
                    leave.getId(), leave.getStartsAt(), leave.getEndsAt(), leave.getLeaveType(), leave.getNote());
        }
    }

    public record QualificationResponse(String code, LocalDate validUntil) {

        static QualificationResponse from(CrewQualification qualification) {
            return new QualificationResponse(qualification.getCode(), qualification.getValidUntil());
        }
    }

    public record DepotHistoryResponse(Long depotId, LocalDate effectiveFrom, LocalDate effectiveTo) {

        static DepotHistoryResponse from(CrewDepotHistory history) {
            return new DepotHistoryResponse(
                    history.getDepotId(), history.getEffectiveFrom(), history.getEffectiveTo());
        }
    }
}
