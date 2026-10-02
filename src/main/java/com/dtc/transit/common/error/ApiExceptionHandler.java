package com.dtc.transit.common.error;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.dtc.transit.common.web.CorrelationIdFilter;

/**
 * Translates exceptions into RFC 7807 problem details.
 *
 * <p>The status mapping is fixed by the architecture: validation failure 400, not found or
 * out-of-scope 404, optimistic lock 409, business rule 422. Every response carries the correlation id
 * so a client report can be matched to a log line.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://docs.dtc.example/problems/";

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException e, WebRequest request) {
        log.debug("API exception {}: {}", e.code(), e.getMessage());
        return problem(e.status(), e.code(), e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e, WebRequest request) {
        ProblemDetail problem =
                problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request);
        List<String> errors = new ArrayList<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.add(fieldError.getField() + ": " + fieldError.getDefaultMessage());
        }
        errors.sort(Comparator.naturalOrder());
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleHandlerValidation(HandlerMethodValidationException e, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException e, WebRequest request) {
        // Unknown JSON properties land here, which is how mass-assignment attempts are rejected
        // rather than silently ignored (edge case EC-API-16).
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body could not be read", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", e.getMessage(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException e, WebRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                "STALE_VERSION",
                "The resource was modified by someone else. Reload and retry.",
                request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException e, WebRequest request) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied", request);
    }

    /**
     * An unmapped path.
     *
     * <p>Handled explicitly because the catch-all below would otherwise report a request for a URL that
     * does not exist as an internal server error, which misdirects anyone reading the logs.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ProblemDetail handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "No handler for this path", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, WebRequest request) {
        // Log the cause here, but never leak internals to the caller (edge case EC-SEC-12).
        log.error("Unhandled exception", e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request);
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_BASE + code.toLowerCase()));
        problem.setTitle(code);
        problem.setProperty("code", code);
        String traceId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE, WebRequest.SCOPE_REQUEST);
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }
}
