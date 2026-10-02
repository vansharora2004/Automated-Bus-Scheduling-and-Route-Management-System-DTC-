package com.dtc.transit.security;

import org.springframework.http.HttpStatus;

import com.dtc.transit.common.error.ApiException;

/**
 * Raised when login fails.
 *
 * <p>One exception covers a wrong password, an unknown username and a disabled account, and the
 * message is identical for all three. Distinguishing them would let an attacker enumerate accounts
 * (edge case EC-SEC-05).
 */
public class AuthenticationFailedException extends ApiException {

    public AuthenticationFailedException() {
        super(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "Invalid username or password");
    }
}
