package com.dtc.transit.common.error;

import org.springframework.http.HttpStatus;

/**
 * Raised when a range filter's bounds are the wrong way round.
 *
 * <p>Silently swapping them would be worse: the caller would get results for a range they did not ask
 * for and never learn their query was wrong (edge case EC-API-07).
 */
public class InvalidRangeException extends ApiException {

    public InvalidRangeException(String parameter, Object from, Object to) {
        super(
                HttpStatus.BAD_REQUEST,
                "INVALID_RANGE",
                "Range '" + parameter + "' is inverted: from=" + from + " is after to=" + to);
    }
}
