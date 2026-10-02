package com.dtc.transit.common.error;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

    /**
     * A required query parameter that was not sent.
     *
     * <p>Handled explicitly, because the catch-all would report it as an internal server error. That is
     * actively misleading: the request was malformed, nothing failed inside the system, and telling a client
     * to retry a call that can never succeed wastes everyone's time.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(
            MissingServletRequestParameterException e, WebRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "MISSING_PARAMETER",
                "Required parameter '" + e.getParameterName() + "' is missing",
                request);
    }

    /**
     * A parameter that was sent but could not be converted, such as a malformed date or an unknown enum.
     *
     * <p>Named in the message. "Conversion failed" without saying which parameter leaves a caller guessing,
     * and the value itself is not echoed back, since it came from the request and may be hostile.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e, WebRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_PARAMETER",
                "Parameter '" + e.getName() + "' has the wrong type or format",
                request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException e, WebRequest request) {
        // Unknown JSON properties land here, which is how mass-assignment attempts are rejected
        // rather than silently ignored (edge case EC-API-16).
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body could not be read", request);
    }

    /**
     * A request that should have been multipart but was not.
     *
     * <p>Handled explicitly because the catch-all would report a client mistake as an internal error.
     */
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ProblemDetail handleMultipart(
            org.springframework.web.multipart.MultipartException e, WebRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "MULTIPART_EXPECTED",
                "This endpoint expects a multipart upload with a 'file' part",
                request);
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

    /**
     * A constraint the database refused.
     *
     * <p>Mapped to 409 rather than falling through to the catch-all. These are the exclusion constraints and
     * partial unique indexes the schema relies on - overlapping timetable validity, overlapping headway
     * bands, a duplicate code - and every one of them is the caller's conflict to resolve, not an internal
     * failure. Reporting them as 500 would also tell a caller to retry, which can never succeed.
     *
     * <p>The constraint name is logged but not returned. It names tables and columns, which is more of the
     * schema than an API caller needs (edge case EC-SEC-12).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException e, WebRequest request) {
        log.warn("Database rejected a write: {}", e.getMostSpecificCause().getMessage());
        return problem(
                HttpStatus.CONFLICT,
                "CONSTRAINT_VIOLATION",
                "The change conflicts with existing data or violates a database constraint.",
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
