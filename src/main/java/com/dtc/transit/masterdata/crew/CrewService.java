package com.dtc.transit.masterdata.crew;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.common.config.AppTimeProperties;
import com.dtc.transit.common.error.ConflictException;
import com.dtc.transit.common.error.NotFoundException;
import com.dtc.transit.common.paging.SortWhitelist;
import com.dtc.transit.masterdata.depot.Depot;
import com.dtc.transit.masterdata.depot.DepotRepository;
import com.dtc.transit.security.DepotAccessEvaluator;
import com.dtc.transit.security.DepotScope;

/** Crew administration: records, leave, qualifications and transfers. */
@Service
public class CrewService {

    public static final Set<String> SORTABLE =
            Set.of("employeeCode", "name", "crewRole", "status", "licenceExpiry", "depot.code");

    private final CrewMemberRepository crew;
    private final CrewLeaveRepository leaves;
    private final CrewQualificationRepository qualifications;
    private final CrewDepotHistoryRepository depotHistory;
    private final DepotRepository depots;
    private final SortWhitelist sortWhitelist;
    private final DepotScope depotScope;
    private final DepotAccessEvaluator depotAccess;
    private final AppTimeProperties timeProperties;
    private final ApplicationEventPublisher events;

    public CrewService(
            CrewMemberRepository crew,
            CrewLeaveRepository leaves,
            CrewQualificationRepository qualifications,
            CrewDepotHistoryRepository depotHistory,
            DepotRepository depots,
            SortWhitelist sortWhitelist,
            DepotScope depotScope,
            DepotAccessEvaluator depotAccess,
            AppTimeProperties timeProperties,
            ApplicationEventPublisher events) {
        this.crew = crew;
        this.leaves = leaves;
        this.qualifications = qualifications;
        this.depotHistory = depotHistory;
        this.depots = depots;
        this.sortWhitelist = sortWhitelist;
        this.depotScope = depotScope;
        this.depotAccess = depotAccess;
        this.timeProperties = timeProperties;
        this.events = events;
    }

    /**
     * Lists crew, narrowed to the caller's depot where they have one.
     *
     * <p>Leave and qualifications live in their own tables, so those two filters are resolved to id sets
     * first and applied as predicates. Doing it this way keeps one page query with one count, which a
     * join to a multi-row child table would break by duplicating rows.
     */
    // Planners design routes and timetables and have no business in personnel records, so crew are
    // not readable by them at all.
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public Page<CrewMember> search(CrewFilter filter, Pageable pageable) {
        List<Long> onLeave = filter.availableOn() == null
                ? List.of()
                : leaves.findCrewMemberIdsOnLeaveBetween(
                        startOfServiceDay(filter.availableOn()), startOfServiceDay(filter.availableOn().plusDays(1)));

        List<Long> qualified = filter.qualification() == null || filter.qualification().isBlank()
                ? List.of()
                : qualifications.findCrewMemberIdsHolding(filter.qualification());

        Specification<CrewMember> spec = Specification.allOf(
                CrewSpecifications.of(filter, onLeave, qualified), depotScope.restrict("depot.id"));
        return crew.findAll(spec, sortWhitelist.apply(pageable, SORTABLE));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional(readOnly = true)
    public CrewMember get(Long id) {
        CrewMember member = crew.findById(id).orElseThrow(() -> NotFoundException.of("Crew member", id));
        depotAccess.requireAccess(member.getDepot().getId(), "Crew member", id);
        return member;
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional
    public CrewMember create(NewCrewMember command) {
        Depot depot = depots.findById(command.depotId())
                .orElseThrow(() -> NotFoundException.of("Depot", command.depotId()));
        depotAccess.requireAccess(depot.getId(), "Depot", command.depotId());

        if (crew.existsByEmployeeCodeIgnoreCase(command.employeeCode())) {
            throw new ConflictException(
                    "EMPLOYEE_CODE_TAKEN", "Employee code '" + command.employeeCode() + "' already exists");
        }

        var member = crew.save(new CrewMember(
                command.employeeCode(),
                command.name(),
                command.crewRole(),
                depot,
                command.licenceNo(),
                command.licenceClass(),
                command.licenceExpiry(),
                command.weeklyOffDayOfWeek()));

        // The current posting opens on the day they join, so a later transfer has something to close.
        depotHistory.save(new CrewDepotHistory(
                member.getId(), depot.getId(), command.joinedOn() == null ? LocalDate.now() : command.joinedOn()));

        events.publishEvent(AuditEvent.created("CREW_MEMBER", member.getId(), describe(member)));
        return member;
    }

    /**
     * Records leave.
     *
     * <p>A scheduler may do this for their own depot, because absences are reported to the depot and
     * have to be reflected before the next run.
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')")
    @Transactional
    public CrewLeave recordLeave(Long crewMemberId, Instant from, Instant to, LeaveType type, String note) {
        CrewMember member = get(crewMemberId);
        var leave = leaves.save(new CrewLeave(member.getId(), from, to, type, note));
        events.publishEvent(new AuditEvent(
                "CREW_LEAVE_RECORDED",
                "CREW_MEMBER",
                String.valueOf(crewMemberId),
                null,
                """
                {"from":"%s","to":"%s","type":"%s"}""".formatted(from, to, type),
                note));
        return leave;
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional
    public CrewQualification addQualification(Long crewMemberId, String code, LocalDate validUntil) {
        CrewMember member = get(crewMemberId);
        var qualification = qualifications.save(new CrewQualification(member.getId(), code, validUntil));
        events.publishEvent(new AuditEvent(
                "CREW_QUALIFICATION_ADDED",
                "CREW_MEMBER",
                String.valueOf(crewMemberId),
                null,
                """
                {"code":"%s","validUntil":%s}"""
                        .formatted(code.toUpperCase(), validUntil == null ? "null" : "\"" + validUntil + "\""),
                null));
        return qualification;
    }

    /**
     * Transfers a crew member, closing the previous posting.
     *
     * <p>History is appended rather than rewritten, so a past service date still resolves to the depot
     * the person actually belonged to then (edge case EC-CA-05).
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional
    public CrewMember transfer(Long crewMemberId, Long newDepotId, LocalDate effectiveFrom) {
        CrewMember member = crew.findById(crewMemberId)
                .orElseThrow(() -> NotFoundException.of("Crew member", crewMemberId));
        Depot target = depots.findById(newDepotId).orElseThrow(() -> NotFoundException.of("Depot", newDepotId));

        String before = describe(member);
        depotHistory.findOpenPosting(crewMemberId).ifPresent(open -> open.closeOn(effectiveFrom.minusDays(1)));
        depotHistory.save(new CrewDepotHistory(crewMemberId, newDepotId, effectiveFrom));

        member.transferTo(target);
        crew.save(member);

        events.publishEvent(AuditEvent.updated("CREW_MEMBER", crewMemberId, before, describe(member)));
        return member;
    }

    @Transactional(readOnly = true)
    public List<CrewLeave> leaveFor(Long crewMemberId) {
        get(crewMemberId);
        return leaves.findByCrewMemberIdOrderByStartsAtAsc(crewMemberId);
    }

    @Transactional(readOnly = true)
    public List<CrewQualification> qualificationsFor(Long crewMemberId) {
        get(crewMemberId);
        return qualifications.findByKeyCrewMemberId(crewMemberId);
    }

    @Transactional(readOnly = true)
    public List<CrewDepotHistory> depotHistoryFor(Long crewMemberId) {
        get(crewMemberId);
        return depotHistory.findByCrewMemberIdOrderByEffectiveFromDesc(crewMemberId);
    }

    /**
     * Start of a service day as an absolute instant.
     *
     * <p>A service day is not a calendar day: it begins at the configured hour in the presentation zone,
     * so availability for a date covers the late duties that run past midnight.
     */
    private Instant startOfServiceDay(LocalDate serviceDate) {
        ZoneId zone = timeProperties.zone();
        LocalTime start = timeProperties.serviceDayStart();
        return serviceDate.atTime(start).atZone(zone).toInstant();
    }

    private static String describe(CrewMember member) {
        return """
                {"employeeCode":"%s","name":"%s","crewRole":"%s","depotId":%d,"status":"%s"}"""
                .formatted(
                        member.getEmployeeCode(),
                        member.getName(),
                        member.getCrewRole(),
                        member.getDepot().getId(),
                        member.getStatus());
    }

    /** Creation command, kept separate from the HTTP request body. */
    public record NewCrewMember(
            String employeeCode,
            String name,
            CrewRole crewRole,
            Long depotId,
            String licenceNo,
            LicenceClass licenceClass,
            LocalDate licenceExpiry,
            Integer weeklyOffDayOfWeek,
            LocalDate joinedOn) {}
}
