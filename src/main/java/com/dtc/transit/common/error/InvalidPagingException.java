package com.dtc.transit.common.error;

import org.springframework.http.HttpStatus;

/** Raised when a paging parameter is not a usable number. */
public class InvalidPagingException extends ApiException {

    public InvalidPagingException(String parameter, String value, String requirement) {
        super(
                HttpStatus.BAD_REQUEST,
                "INVALID_PAGING",
                "Parameter '" + parameter + "' with value '" + value + "' is invalid: " + requirement);
    }
}
