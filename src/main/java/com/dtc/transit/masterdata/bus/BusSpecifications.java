package com.dtc.transit.masterdata.bus;

import org.springframework.data.jpa.domain.Specification;

import com.dtc.transit.common.filtering.Filters;

/** Turns a {@link BusFilter} into predicates. */
public final class BusSpecifications {

    private BusSpecifications() {}

    public static Specification<Bus> of(BusFilter filter) {
        return Specification.allOf(
                Filters.eq("depot.id", filter.depotId()),
                Filters.eq("status", filter.status()),
                Filters.eq("busType", filter.busType()),
                Filters.eq("fuelType", filter.fuelType()),
                Filters.eq("airConditioned", filter.ac()),
                freeText(filter.q()));
    }

    /**
     * Matches registration or fleet number.
     *
     * <p>The term is normalised the same way the stored registration is, so searching for
     * "DL 1PC" finds a bus stored as "DL1PC1234" (edge case EC-DATA-01).
     */
    private static Specification<Bus> freeText(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String normalised = RegistrationNo.normaliseForSearch(term);
        return (root, query, builder) -> builder.or(
                Filters.<Bus>contains("registrationNo", normalised).toPredicate(root, query, builder),
                Filters.<Bus>contains("fleetNo", term).toPredicate(root, query, builder));
    }
}
