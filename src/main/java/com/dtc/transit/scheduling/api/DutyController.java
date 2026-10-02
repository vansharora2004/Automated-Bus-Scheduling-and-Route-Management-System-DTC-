package com.dtc.transit.scheduling.api;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dtc.transit.common.paging.PageResponse;
import com.dtc.transit.common.time.ServiceTime;
import com.dtc.transit.scheduling.duty.Duty;
import com.dtc.transit.scheduling.duty.DutyRepository;
import com.dtc.transit.scheduling.duty.DutyService;
import com.dtc.transit.scheduling.duty.Handover;
import com.dtc.transit.scheduling.duty.PieceOfWork;
import com.dtc.transit.scheduling.engine.model.DutyType;
import com.dtc.transit.scheduling.engine.model.SchedulingMode;

/**
 * A schedule's crew duties and the handovers between them.
 *
 * <p>Nested under the schedule because a duty has no meaning without one: it is one version of one depot-day's
 * work, built against one rule set.
 */
@RestController
@RequestMapping("/api/v1/schedules/{scheduleId}")
public class DutyController {

    private final DutyService dutyService;

    public DutyController(DutyService dutyService) {
        this.dutyService = dutyService;
    }

    @GetMapping("/duties")
    public PageResponse<DutyResponse> duties(
            @PathVariable Long scheduleId,
            @RequestParam(required = false) DutyType dutyType,
            @PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.of(dutyService.ofSchedule(scheduleId, dutyType, pageable), DutyResponse::from);
    }

    /** One duty with the stretches of bus work it covers. */
    @GetMapping("/duties/{dutyId}")
    public DutyDetailResponse duty(@PathVariable Long scheduleId, @PathVariable Long dutyId) {
        var detail = dutyService.detail(scheduleId, dutyId);
        return new DutyDetailResponse(
                DutyResponse.from(detail.duty()),
                detail.pieces().stream().map(PieceResponse::from).toList());
    }

    @GetMapping("/handovers")
    public PageResponse<HandoverResponse> handovers(
            @PathVariable Long scheduleId, @PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.of(dutyService.handoversOf(scheduleId, pageable), HandoverResponse::from);
    }

    /** Duty counts and hours per type, which is the fairness picture a planner wants before publishing. */
    @GetMapping("/duty-summary")
    public List<DutySummaryResponse> summary(@PathVariable Long scheduleId) {
        return dutyService.summary(scheduleId).stream()
                .map(DutySummaryResponse::from)
                .toList();
    }

    /**
     * @param signOn and signOff are clock times, because 90,000 does not read as 01:00 the next morning
     * @param workSec the spread-over less unpaid breaks, which is the figure the daily maximum limits
     */
    public record DutyResponse(
            Long id,
            int dutyNo,
            SchedulingMode mode,
            DutyType dutyType,
            int signOnSec,
            int signOffSec,
            String signOn,
            String signOff,
            int platformSec,
            int paidSec,
            int breakSec,
            int spreadSec,
            int workSec,
            int overtimeSec,
            long version) {

        static DutyResponse from(Duty duty) {
            return new DutyResponse(
                    duty.getId(),
                    duty.getDutyNo(),
                    duty.getMode(),
                    duty.getDutyType(),
                    duty.getSignOnSec(),
                    duty.getSignOffSec(),
                    ServiceTime.format(duty.getSignOnSec()),
                    ServiceTime.format(duty.getSignOffSec()),
                    duty.getPlatformSec(),
                    duty.getPaidSec(),
                    duty.getBreakSec(),
                    duty.getSpreadSec(),
                    duty.getWorkSec(),
                    duty.getOvertimeSec(),
                    duty.getVersion());
        }
    }

    public record DutyDetailResponse(DutyResponse duty, List<PieceResponse> pieces) {}

    /** @param startReliefStopId and endReliefStopId are null when the cut is at the depot */
    public record PieceResponse(
            Long id,
            Long blockId,
            int fromEventSeq,
            int toEventSeq,
            int startSec,
            int endSec,
            String start,
            String end,
            Long startReliefStopId,
            Long endReliefStopId) {

        static PieceResponse from(PieceOfWork piece) {
            return new PieceResponse(
                    piece.getId(),
                    piece.getBlockId(),
                    piece.getFromEventSeq(),
                    piece.getToEventSeq(),
                    piece.getStartSec(),
                    piece.getEndSec(),
                    ServiceTime.format(piece.getStartSec()),
                    ServiceTime.format(piece.getEndSec()),
                    piece.getStartReliefStopId(),
                    piece.getEndReliefStopId());
        }
    }

    /** @param reliefStopId null when the change happens at the depot */
    public record HandoverResponse(
            Long id,
            Long blockId,
            Long reliefStopId,
            int atSec,
            String at,
            Long outgoingDutyId,
            Long incomingDutyId) {

        static HandoverResponse from(Handover handover) {
            return new HandoverResponse(
                    handover.getId(),
                    handover.getBlockId(),
                    handover.getReliefStopId(),
                    handover.getAtSec(),
                    ServiceTime.format(handover.getAtSec()),
                    handover.getOutgoingDutyId(),
                    handover.getIncomingDutyId());
        }
    }

    public record DutySummaryResponse(String dutyType, long dutyCount, long paidSec, long platformSec) {

        static DutySummaryResponse from(DutyRepository.DutyTypeSummary summary) {
            return new DutySummaryResponse(
                    summary.getDutyType(),
                    summary.getDutyCount(),
                    summary.getPaidSec(),
                    summary.getPlatformSec());
        }
    }
}
