package com.dtc.transit.common.error;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.http.HttpStatus;

/**
 * Raised when a caller sorts by a property a resource does not expose.
 *
 * <p>The message lists the allowed properties, because a 400 that only says "bad sort" leaves the
 * caller guessing at a closed set they cannot discover.
 */
public class InvalidSortException extends ApiException {

    private final Set<String> allowed;

    public InvalidSortException(Collection<String> rejected, Collection<String> allowed) {
        super(
                HttpStatus.BAD_REQUEST,
                "INVALID_SORT",
                "Cannot sort by " + new TreeSet<>(rejected) + ". Allowed: " + new TreeSet<>(allowed));
        this.allowed = Set.copyOf(allowed);
    }

    public Set<String> allowed() {
        return allowed;
    }
}
