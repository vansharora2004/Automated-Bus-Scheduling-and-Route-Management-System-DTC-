package com.dtc.transit.security;

import java.util.Optional;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Query-level depot restriction, applied to every list query from Phase 3 onward.
 *
 * <p>{@link DepotAccessEvaluator} guards access to one known entity. This guards queries that return
 * many, by narrowing the query itself rather than filtering results afterwards. Filtering after the
 * fact would break pagination: a page of 20 could come back with 3 rows, and the total count would
 * describe rows the caller cannot see.
 *
 * <p>For an HQ user {@link #restrict} returns null, which Spring Data treats as no predicate at all.
 */
@Component
public class DepotScope {

    private final DepotAccessEvaluator depotAccess;

    public DepotScope(DepotAccessEvaluator depotAccess) {
        this.depotAccess = depotAccess;
    }

    /**
     * Restricts a query to the caller's depot.
     *
     * @param attributePath path to the depot id on the queried entity, for example {@code depot.id} or
     *     {@code depotId}
     * @return a predicate for a depot-bound caller, or null for an HQ user
     */
    public <T> Specification<T> restrict(String attributePath) {
        Optional<Long> depotId = depotAccess.currentDepotId();
        if (depotId.isEmpty()) {
            return null;
        }
        Long required = depotId.get();
        return (root, query, builder) -> builder.equal(resolve(root, attributePath), required);
    }

    /** The depot a new record must belong to, or empty when the caller may choose freely. */
    public Optional<Long> requiredDepotId() {
        return depotAccess.currentDepotId();
    }

    private static <T> jakarta.persistence.criteria.Path<Object> resolve(
            jakarta.persistence.criteria.Root<T> root, String attributePath) {
        jakarta.persistence.criteria.Path<Object> path = null;
        for (String segment : attributePath.split("\\.")) {
            path = path == null ? root.get(segment) : path.get(segment);
        }
        return path;
    }
}
