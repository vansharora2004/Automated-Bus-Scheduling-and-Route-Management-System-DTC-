package com.dtc.transit.common.paging;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.dtc.transit.common.error.InvalidPagingException;

/**
 * Rejects malformed paging parameters before Spring quietly repairs them.
 *
 * <p>This has to run on the raw query string. Spring's {@code Pageable} resolver turns
 * {@code page=-1} into page 0 and a non-numeric {@code size} into the default, so by the time a
 * controller sees a {@code Pageable} the mistake is invisible and the caller gets a successful response
 * to a request that was wrong. A silent repair is worse than an error: the caller believes they read
 * the page they asked for.
 *
 * <p>Size above the maximum is deliberately not rejected here. The documented contract clamps it, and
 * the response reports the effective size (edge case EC-API-01).
 */
@Component
public class PagingParameterInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        requireNonNegativeInt(request, "page");
        requirePositiveInt(request, "size");
        return true;
    }

    private static void requireNonNegativeInt(HttpServletRequest request, String name) {
        String raw = request.getParameter(name);
        if (raw == null) {
            return;
        }
        int value = parse(name, raw);
        if (value < 0) {
            throw new InvalidPagingException(name, raw, "must not be negative");
        }
    }

    private static void requirePositiveInt(HttpServletRequest request, String name) {
        String raw = request.getParameter(name);
        if (raw == null) {
            return;
        }
        int value = parse(name, raw);
        if (value < 1) {
            throw new InvalidPagingException(name, raw, "must be at least 1");
        }
    }

    private static int parse(String name, String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidPagingException(name, raw, "must be an integer");
        }
    }
}
