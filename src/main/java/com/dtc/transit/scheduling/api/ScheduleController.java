package com.dtc.transit.scheduling.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dtc.transit.common.paging.PageResponse;
import com.dtc.transit.common.time.ServiceTime;
import com.dtc.transit.scheduling.engine.model.Severity;
import com.dtc.transit.scheduling.schedule.BusAssignment;
import com.dtc.transit.scheduling.schedule.Conflict;
import com.dtc.transit.scheduling.schedule.Schedule;
import com.dtc.transit.scheduling.schedule.ScheduleService;
import com.dtc.transit.scheduling.schedule.ScheduleStatus;
import com.dtc.transit.scheduling.schedule.VehicleBlock;
import com.fasterxml.jackson.databind.JsonNode;

/** Schedules: their blocks, assignments, conflicts, and the path to publication. */
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping
    public PageResponse<ScheduleResponse> list(
            @RequestParam Long depotId, @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(scheduleService.listForDepot(depotId, pageable), ScheduleResponse::from);
    }

    @GetMapping("/{id}")
    public ScheduleResponse get(@PathVariable Long id) {
        return ScheduleResponse.from(scheduleService.require(id));
    }

    @GetMapping("/{id}/blocks")
    public PageResponse<BlockResponse> blocks(
            @PathVariable Long id, @PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.of(scheduleService.blocksOf(id, pageable), BlockResponse::from);
    }

    @GetMapping("/{id}/bus-assignments")
    public List<AssignmentResponse> busAssignments(@PathVariable Long id) {
        return scheduleService.assignmentsOf(id).stream()
                .map(AssignmentResponse::from)
                .toList();
    }

    @GetMapping("/{id}/conflicts")
    public PageResponse<ConflictResponse> conflicts(
            @PathVariable Long id,
            @RequestParam(required = false) Severity severity,
            @PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.of(scheduleService.conflictsOf(id, severity, pageable), ConflictResponse::from);
    }

    /**
     * Checks whether anything blocks publication, and promotes the schedule when nothing does.
     *
     * <p>Returns 200 either way. "Still has three blocking conflicts" is a successful answer to the question "is
     * this ready", and a 4xx would make a client treat its own correct request as a mistake.
     */
    @PostMapping("/{id}/validate")
    public ValidationResponse validate(@PathVariable Long id) {
        var result = scheduleService.validate(id);
        return new ValidationResponse(
                ScheduleResponse.from(result.schedule()), result.valid(), result.blockingConflicts());
    }

    @PostMapping("/{id}/publish")
    public ScheduleResponse publish(@PathVariable Long id) {
        return ScheduleResponse.from(scheduleService.publish(id));
    }

    @PostMapping("/{id}/discard")
    public ScheduleResponse discard(@PathVariable Long id) {
        return ScheduleResponse.from(scheduleService.discard(id));
    }

    /** Records that someone with authority accepted a conflict. The reason is required and audited. */
    @PostMapping("/{id}/conflicts/{conflictId}/resolve")
    public ConflictResponse resolveConflict(
            @PathVariable Long id,
            @PathVariable Long conflictId,
            @Valid @RequestBody ResolveConflictRequest request) {
        return ConflictResponse.from(scheduleService.resolveConflict(id, conflictId, request.reason()));
    }

    public record ResolveConflictRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record ScheduleResponse(
            Long id,
            UUID runId,
            Long depotId,
            LocalDate serviceDate,
            int versionNo,
            ScheduleStatus status,
            boolean needsRevalidation,
            Instant publishedAt,
            String publishedBy,
            long version) {

        static ScheduleResponse from(Schedule schedule) {
            return new ScheduleResponse(
                    schedule.getId(),
                    schedule.getRunId(),
                    schedule.getDepotId(),
                    schedule.getServiceDate(),
                    schedule.getVersionNo(),
                    schedule.getStatus(),
                    schedule.isNeedsRevalidation(),
                    schedule.getPublishedAt(),
                    schedule.getPublishedBy(),
                    schedule.getVersion());
        }
    }

    /** @param pullOut and pullIn are clock times, because 97200 does not read as 03:00 the next morning */
    public record BlockResponse(
            Long id,
            int blockNo,
            String vehicleClass,
            int pullOutSec,
            int pullInSec,
            String pullOut,
            String pullIn,
            double serviceKm,
            double deadKm,
            double totalKm) {

        static BlockResponse from(VehicleBlock block) {
            return new BlockResponse(
                    block.getId(),
                    block.getBlockNo(),
                    block.getVehicleClass(),
                    block.getPullOutSec(),
                    block.getPullInSec(),
                    ServiceTime.format(block.getPullOutSec()),
                    ServiceTime.format(block.getPullInSec()),
                    block.getServiceKm(),
                    block.getDeadKm(),
                    block.getTotalKm());
        }
    }

    public record AssignmentResponse(
            Long id, Long blockId, Long busId, Instant startsAt, Instant endsAt, String scheduleStatus) {

        static AssignmentResponse from(BusAssignment assignment) {
            return new AssignmentResponse(
                    assignment.getId(),
                    assignment.getBlockId(),
                    assignment.getBusId(),
                    assignment.getStartsAt(),
                    assignment.getEndsAt(),
                    assignment.getScheduleStatus());
        }
    }

    public record ConflictResponse(
            Long id,
            String type,
            Severity severity,
            String message,
            JsonNode entityRefs,
            boolean resolved,
            String resolvedBy) {

        static ConflictResponse from(Conflict conflict) {
            return new ConflictResponse(
                    conflict.getId(),
                    conflict.getType(),
                    conflict.getSeverity(),
                    conflict.getMessage(),
                    RawJson.parse(conflict.getEntityRefs()),
                    conflict.isResolved(),
                    conflict.getResolvedBy());
        }
    }

    /** @param blockingConflicts how many unresolved hard conflicts remain; zero means publishable */
    public record ValidationResponse(ScheduleResponse schedule, boolean valid, long blockingConflicts) {}
}
