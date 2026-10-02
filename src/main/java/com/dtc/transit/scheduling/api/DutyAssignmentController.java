package com.dtc.transit.scheduling.api;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.common.paging.PageResponse;
import com.dtc.transit.scheduling.crew.AssignmentOverrideService;
import com.dtc.transit.scheduling.crew.AssignmentStatus;
import com.dtc.transit.scheduling.crew.DutyAssignment;

/**
 * Crew assignments, and the manual override.
 *
 * <p>The override requires {@code If-Match}. Without it two schedulers reading the same roster would each apply
 * their decision to a state the other had already changed, and the loser would not know. The header makes the
 * caller state which version they saw, so the second one is told to reload rather than silently overwriting.
 */
@RestController
@RequestMapping("/api/v1/duty-assignments")
public class DutyAssignmentController {

    private final AssignmentOverrideService overrideService;

    public DutyAssignmentController(AssignmentOverrideService overrideService) {
        this.overrideService = overrideService;
    }

    /** One assignment, with its version in an {@code ETag} so a client can send it straight back. */
    @GetMapping("/{id}")
    public ResponseEntity<AssignmentResponse> get(@PathVariable Long id) {
        DutyAssignment assignment = overrideService.require(id);
        return ResponseEntity.ok()
                .eTag("\"" + assignment.getVersion() + "\"")
                .body(AssignmentResponse.from(assignment));
    }

    @GetMapping
    public PageResponse<AssignmentResponse> byCrewMember(
            @org.springframework.web.bind.annotation.RequestParam Long crewMemberId,
            @PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.of(
                overrideService.forCrewMember(crewMemberId, pageable), AssignmentResponse::from);
    }

    /**
     * Replaces the crew member on an assignment.
     *
     * @param ifMatch the version the caller is editing, as returned by the {@code ETag} on a read. Required: an
     *     override without it is a blind write.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<AssignmentResponse> override(
            @PathVariable Long id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody OverrideRequest request) {

        if (ifMatch == null || ifMatch.isBlank()) {
            throw new BusinessRuleException(
                    "IF_MATCH_REQUIRED",
                    "An override must carry an If-Match header with the version it is editing, so a concurrent "
                            + "change cannot be overwritten unnoticed");
        }

        DutyAssignment updated =
                overrideService.override(id, request.crewMemberId(), request.reason(), parseVersion(ifMatch));
        return ResponseEntity.ok()
                .eTag("\"" + updated.getVersion() + "\"")
                .body(AssignmentResponse.from(updated));
    }

    /** Accepts a quoted or bare version, because clients and proxies differ about the quotes. */
    private static long parseVersion(String ifMatch) {
        String trimmed = ifMatch.trim().replace("W/", "").replace("\"", "");
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            throw new BusinessRuleException(
                    "IF_MATCH_INVALID", "If-Match must be the version from the ETag, for example \"3\"");
        }
    }

    public record OverrideRequest(
            @NotNull Long crewMemberId, @NotBlank @Size(max = 1000) String reason) {}

    public record AssignmentResponse(
            Long id,
            Long dutyId,
            String crewRole,
            Long crewMemberId,
            LocalDate serviceDate,
            Instant startsAt,
            Instant endsAt,
            String scheduleStatus,
            AssignmentStatus status,
            String overrideReason,
            long version) {

        static AssignmentResponse from(DutyAssignment assignment) {
            return new AssignmentResponse(
                    assignment.getId(),
                    assignment.getDutyId(),
                    assignment.getCrewRole(),
                    assignment.getCrewMemberId(),
                    assignment.getServiceDate(),
                    assignment.getStartsAt(),
                    assignment.getEndsAt(),
                    assignment.getScheduleStatus(),
                    assignment.getStatus(),
                    assignment.getOverrideReason(),
                    assignment.getVersion());
        }
    }
}
