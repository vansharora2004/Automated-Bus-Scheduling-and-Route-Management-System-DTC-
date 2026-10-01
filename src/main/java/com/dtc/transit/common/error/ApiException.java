package com.dtc.transit.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base class for errors that map to a deliberate HTTP status.
 *
 * <p>Each subclass fixes one status and one machine-readable code, so the handler never has to guess
 * from the exception type.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
