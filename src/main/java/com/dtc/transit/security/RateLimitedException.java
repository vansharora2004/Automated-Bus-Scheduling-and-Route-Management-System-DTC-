package com.dtc.transit.security;

import org.springframework.http.HttpStatus;

import com.dtc.transit.common.error.ApiException;

/** Raised when login attempts exceed the configured rate, per username or per client address. */
public class RateLimitedException extends ApiException {

    public RateLimitedException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Slow down.");
    }
}
