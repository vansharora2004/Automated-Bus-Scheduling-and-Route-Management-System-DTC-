package com.dtc.transit.security;

import org.springframework.http.HttpStatus;

import com.dtc.transit.common.error.ApiException;

/**
 * Raised when a presented refresh token cannot be exchanged.
 *
 * <p>Maps to 401 so a client knows to log in again. The message deliberately stays coarse; telling a
 * caller whether a token is unknown, expired or replayed would help someone probing for valid values.
 */
public class InvalidRefreshTokenException extends ApiException {

    public InvalidRefreshTokenException(String detail) {
        super(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", detail);
    }
}
