package com.dtc.transit.security;

import org.springframework.http.HttpStatus;

import com.dtc.transit.common.error.ApiException;

/** Raised while a lockout is in force after repeated failed logins. */
public class AccountLockedException extends ApiException {

    public AccountLockedException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED", "Too many failed attempts. Try again later.");
    }
}
