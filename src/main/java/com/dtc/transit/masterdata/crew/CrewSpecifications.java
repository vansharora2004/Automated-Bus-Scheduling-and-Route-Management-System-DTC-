package com.dtc.transit.masterdata.crew;

import java.time.LocalDate;
import java.util.Collection;

import org.springframework.data.jpa.domain.Specification;

import com.dtc.transit.common.filtering.Filters;

/** Turns a {@link CrewFilter} into predicates. */
public final class CrewSpecifications {

    private CrewSpecifications() {}

    /**
     * @param onLeaveIds crew on leave over the {@code availableOn} date, resolved by the service
     *     because the leave table is a separate aggregate
     * @param qualifiedIds crew holding the requested qualification, resolved the same way
     */
    public static Specification<CrewMember> of(
            CrewFilter filter, Collection<Long> onLeaveIds, Collection<Long> qualifiedIds) {
        return Specification.allOf(
                Filters.eq("depot.id", filter.depotId()),
                Filters.eq("crewRole", filter.crewRole()),
                Filters.eq("status", filter.status()),
                licenceExpiringBefore(filter.licenceExpiringBefore()),
                availableOn(filter.availableOn(), onLeaveIds),
                holdingQualification(filter.qualification(), qualifiedIds),
                freeText(filter.q()));
    }

    /**
     * Crew whose licence lapses before a date.
     *
     * <p>Rows with no expiry are excluded rather than treated as expiring, because a conductor has no
     * licence to lapse and would otherwise flood a renewal report.
     */
    private static Specification<CrewMember> licenceExpiringBefore(LocalDate date) {
        if (date == null) {
            return null;
        }
        return (root, query, builder) -> builder.and(
                builder.isNotNull(root.get("licenceExpiry")),
                builder.lessThan(root.get("licenceExpiry"), date));
    }

    /**
     * Crew who can actually work on a date.
     *
     * <p>Three conditions, not one: active status, a licence still valid on that date, and no
     * overlapping leave. Asking for availability and getting back someone on sick leave with an expired
     * licence would make the filter worse than useless.
     */
    private static Specification<CrewMember> availableOn(LocalDate date, Collection<Long> onLeaveIds) {
        if (date == null) {
            return null;
        }
        return (root, query, builder) -> {
            var active = builder.equal(root.get("status"), CrewStatus.ACTIVE);
            var licenceOk = builder.or(
                    builder.notEqual(root.get("crewRole"), CrewRole.DRIVER),
                    builder.greaterThanOrEqualTo(root.get("licenceExpiry"), date));
            if (onLeaveIds.isEmpty()) {
                return builder.and(active, licenceOk);
            }
            return builder.and(active, licenceOk, builder.not(root.get("id").in(onLeaveIds)));
        };
    }

    private static Specification<CrewMember> holdingQualification(String code, Collection<Long> qualifiedIds) {
        if (code == null || code.isBlank()) {
            return null;
        }
        if (qualifiedIds.isEmpty()) {
            // Nobody holds it. Returning no predicate would wrongly return everyone.
            return (root, query, builder) -> builder.disjunction();
        }
        return (root, query, builder) -> root.get("id").in(qualifiedIds);
    }

    private static Specification<CrewMember> freeText(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        return (root, query, builder) -> builder.or(
                Filters.<CrewMember>contains("employeeCode", term).toPredicate(root, query, builder),
                Filters.<CrewMember>contains("name", term).toPredicate(root, query, builder));
    }
}
