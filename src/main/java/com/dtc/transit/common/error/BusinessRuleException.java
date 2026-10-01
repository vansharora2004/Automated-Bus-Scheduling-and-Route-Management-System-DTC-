package com.dtc.transit.common.error;

import org.springframework.http.HttpStatus;

/** Raised when input is structurally valid but violates a domain rule. Maps to 422. */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
