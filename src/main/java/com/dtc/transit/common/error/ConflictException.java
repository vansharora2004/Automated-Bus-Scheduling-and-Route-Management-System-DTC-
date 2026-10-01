package com.dtc.transit.common.error;

import org.springframework.http.HttpStatus;

/** Raised when a request collides with existing state, such as a duplicate or a stale version. */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
