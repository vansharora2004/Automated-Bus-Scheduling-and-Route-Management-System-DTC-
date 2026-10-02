package com.dtc.transit.common.paging;

import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.dtc.transit.common.error.InvalidSortException;

/**
 * Validates a requested sort against what a resource actually allows, and makes the order stable.
 *
 * <p>Two separate reasons for the whitelist. A caller must not be able to sort by
 * {@code passwordHash} and read the ordering as an oracle on values they cannot see; and sorting by an
 * unindexed column invites a full scan with a disk sort on a large table. Rejecting is better than
 * silently ignoring, which would return data in an order the caller did not ask for.
 *
 * <p>The {@code id} tiebreaker is appended to every sort. Without it, rows sharing a sort value may
 * come back in a different order on each query, so paging through them can show a row twice or skip it
 * entirely (edge case EC-API-05).
 */
@Component
public class SortWhitelist {

    /** Hard ceiling on page size, matching the documented API contract. */
    public static final int MAX_PAGE_SIZE = 100;

    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * Validates the sort and appends the tiebreaker.
     *
     * <p>A size above the maximum is clamped rather than rejected, so a caller asking for too much gets
     * the first 100 rows and can see the effective size in the response.
     *
     * @throws InvalidSortException if any requested property is not allowed
     */
    public Pageable apply(Pageable pageable, Set<String> allowed) {
        List<String> rejected = pageable.getSort().stream()
                .map(Sort.Order::getProperty)
                .filter(property -> !allowed.contains(property))
                .toList();
        if (!rejected.isEmpty()) {
            throw new InvalidSortException(rejected, allowed);
        }

        Sort stable = pageable.getSort().isSorted()
                ? pageable.getSort().and(Sort.by("id"))
                : Sort.by("id");

        int size = Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE);
        return PageRequest.of(pageable.getPageNumber(), size, stable);
    }
}
