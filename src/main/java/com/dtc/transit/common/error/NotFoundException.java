package com.dtc.transit.common.error;

import org.springframework.http.HttpStatus;

/**
 * Raised when a resource does not exist, or exists outside the caller's depot scope.
 *
 * <p>Out-of-scope access deliberately produces 404 rather than 403. A 403 would confirm that the id
 * exists, which lets a caller enumerate ids belonging to other depots (edge case EC-SEC-01).
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static NotFoundException of(String entityType, Object id) {
        return new NotFoundException(entityType + " " + id + " was not found");
    }
}
