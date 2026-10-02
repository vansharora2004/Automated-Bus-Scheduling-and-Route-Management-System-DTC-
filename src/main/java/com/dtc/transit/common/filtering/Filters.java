package com.dtc.transit.common.filtering;

import java.util.Comparator;

import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

import org.springframework.data.jpa.domain.Specification;

import com.dtc.transit.common.error.InvalidRangeException;

/**
 * Building blocks for turning a filter record into JPA predicates.
 *
 * <p>Every helper returns null for an absent value, because Spring Data treats a null specification as
 * "no condition". That is what lets a filter with eight optional fields compose without a cascade of if
 * statements, and keeps the generated SQL free of always-true clauses.
 */
public final class Filters {

    private Filters() {}

    /** Equality on a possibly nested attribute path, or no condition when the value is null. */
    public static <T> Specification<T> eq(String attributePath, Object value) {
        if (value == null) {
            return null;
        }
        return (root, query, builder) -> builder.equal(resolve(root, attributePath), value);
    }

    /** Case-insensitive contains, or no condition when the term is null or blank. */
    public static <T> Specification<T> contains(String attributePath, String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String pattern = "%" + escapeLike(term.trim().toUpperCase()) + "%";
        return (root, query, builder) ->
                builder.like(builder.upper(resolve(root, attributePath).as(String.class)), pattern, '\\');
    }

    /** Inclusive lower bound. */
    public static <T, V extends Comparable<? super V>> Specification<T> atLeast(String attributePath, V from) {
        if (from == null) {
            return null;
        }
        return (root, query, builder) -> builder.greaterThanOrEqualTo(resolve(root, attributePath).as(cast(from)), from);
    }

    /** Inclusive upper bound. */
    public static <T, V extends Comparable<? super V>> Specification<T> atMost(String attributePath, V to) {
        if (to == null) {
            return null;
        }
        return (root, query, builder) -> builder.lessThanOrEqualTo(resolve(root, attributePath).as(cast(to)), to);
    }

    /**
     * Validates that a range is the right way round.
     *
     * @throws InvalidRangeException when from is strictly after to
     */
    public static <V extends Comparable<? super V>> void requireOrderedRange(String parameter, V from, V to) {
        if (from != null && to != null && Comparator.<V>naturalOrder().compare(from, to) > 0) {
            throw new InvalidRangeException(parameter, from, to);
        }
    }

    /**
     * Escapes the wildcards a caller may type into a free-text term.
     *
     * <p>Without this, a search for {@code %} matches every row, and {@code _} silently becomes a
     * single-character wildcard (edge case EC-API-09). The value is still bound as a parameter, so this
     * is about correct matching, not injection.
     */
    static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @SuppressWarnings("unchecked")
    private static <V> Class<V> cast(V value) {
        return (Class<V>) value.getClass();
    }

    private static <T> Path<Object> resolve(Root<T> root, String attributePath) {
        Path<Object> path = null;
        for (String segment : attributePath.split("\\.")) {
            path = path == null ? root.get(segment) : path.get(segment);
        }
        return path;
    }
}
