package com.dtc.transit.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.user.Role;

/**
 * Every endpoint checked against every role, in each access context.
 *
 * <p>This exists from Phase 2, with only the handful of endpoints that exist so far, and grows in every
 * later phase. Built at the end instead, it would have to be reconstructed from finished code, which is
 * exactly when a wrong assumption becomes permanent.
 *
 * <p>{@link #everyEndpointIsClassified()} is the part that keeps it honest: it fails when an endpoint
 * is added without a row here, so coverage cannot quietly rot.
 *
 * <p>{@code @AutoConfigureObservability} is required because Spring Boot switches metrics exporters off
 * inside {@code @SpringBootTest}, which would leave {@code /actuator/prometheus} unmapped and make its
 * authorization rule look verified when nothing had been checked.
 */
@AutoConfigureObservability
class PermissionMatrixTest extends SecurityWebTest {

    // Qualified explicitly: the actuator contributes a second RequestMappingHandlerMapping bean, and
    // only the MVC one carries the application's own endpoints.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /**
     * One expected outcome per endpoint and caller.
     *
     * @param expected the status a correctly configured system returns
     */
    record Case(HttpMethod method, String path, Caller caller, HttpStatus expected, String body) {
        @Override
        public String toString() {
            return method + " " + path + " as " + caller + " -> " + expected.value();
        }
    }

    enum Caller {
        ANONYMOUS,
        ADMIN_HQ,
        MANAGER_DEPOT_1,
        PLANNER_HQ,
        SCHEDULER_DEPOT_1
    }

    static Stream<Case> cases() {
        String newUser =
                """
                {"username":"created-by-matrix","password":"correct-horse-battery-staple","roles":["SCHEDULER"]}""";
        return Stream.of(
                // Logout requires authentication but no particular role.
                new Case(HttpMethod.POST, "/api/v1/auth/logout", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.POST, "/api/v1/auth/logout", Caller.SCHEDULER_DEPOT_1, HttpStatus.NO_CONTENT, null),
                new Case(HttpMethod.POST, "/api/v1/auth/logout", Caller.ADMIN_HQ, HttpStatus.NO_CONTENT, null),

                // User administration is ADMIN only.
                new Case(HttpMethod.GET, "/api/v1/users/999999", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/api/v1/users/999999", Caller.MANAGER_DEPOT_1, HttpStatus.FORBIDDEN, null),
                new Case(HttpMethod.GET, "/api/v1/users/999999", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, null),
                new Case(HttpMethod.GET, "/api/v1/users/999999", Caller.SCHEDULER_DEPOT_1, HttpStatus.FORBIDDEN, null),
                // A deliberately out-of-range id, not a low one: the id sequence keeps climbing across
                // tests, so "1" could legitimately belong to a user created moments earlier. ADMIN
                // therefore gets 404 rather than 403 - the request was allowed, the row is simply absent.
                new Case(HttpMethod.GET, "/api/v1/users/999999", Caller.ADMIN_HQ, HttpStatus.NOT_FOUND, null),

                new Case(HttpMethod.POST, "/api/v1/users", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, newUser),
                new Case(HttpMethod.POST, "/api/v1/users", Caller.MANAGER_DEPOT_1, HttpStatus.FORBIDDEN, newUser),
                new Case(HttpMethod.POST, "/api/v1/users", Caller.PLANNER_HQ, HttpStatus.FORBIDDEN, newUser),
                new Case(HttpMethod.POST, "/api/v1/users", Caller.SCHEDULER_DEPOT_1, HttpStatus.FORBIDDEN, newUser),
                new Case(HttpMethod.POST, "/api/v1/users", Caller.ADMIN_HQ, HttpStatus.CREATED, newUser),

                new Case(HttpMethod.PATCH, "/api/v1/users/999999", Caller.MANAGER_DEPOT_1, HttpStatus.FORBIDDEN, "{}"),
                new Case(HttpMethod.PATCH, "/api/v1/users/999999", Caller.SCHEDULER_DEPOT_1, HttpStatus.FORBIDDEN, "{}"),
                new Case(HttpMethod.PATCH, "/api/v1/users/999999", Caller.ADMIN_HQ, HttpStatus.NOT_FOUND, "{}"),

                // Metrics and API docs are not public.
                new Case(HttpMethod.GET, "/actuator/prometheus", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/actuator/prometheus", Caller.SCHEDULER_DEPOT_1, HttpStatus.FORBIDDEN, null),
                new Case(HttpMethod.GET, "/actuator/prometheus", Caller.ADMIN_HQ, HttpStatus.OK, null),
                new Case(HttpMethod.GET, "/v3/api-docs", Caller.ANONYMOUS, HttpStatus.UNAUTHORIZED, null),
                new Case(HttpMethod.GET, "/v3/api-docs", Caller.ADMIN_HQ, HttpStatus.OK, null),

                // Health must answer before any token exists, for liveness probes.
                new Case(HttpMethod.GET, "/actuator/health", Caller.ANONYMOUS, HttpStatus.OK, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matrix(Case testCase) {
        String token = tokenFor(testCase.caller());

        ResponseEntity<String> response =
                support.call(rest, testCase.method(), testCase.path(), token, testCase.body());

        assertThat(response.getStatusCode())
                .as("%s %s as %s", testCase.method(), testCase.path(), testCase.caller())
                .isEqualTo(testCase.expected());
    }

    @Test
    @DisplayName("every mapped API endpoint has at least one matrix row")
    void everyEndpointIsClassified() {
        Set<String> classified = cases()
                .map(c -> c.method() + " " + normalise(c.path()))
                .collect(java.util.stream.Collectors.toSet());

        List<String> unclassified = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> {
                    var patterns = info.getPathPatternsCondition() == null
                            ? Set.<String>of()
                            : info.getPathPatternsCondition().getPatternValues();
                    var methods = info.getMethodsCondition().getMethods();
                    return patterns.stream()
                            .filter(p -> p.startsWith("/api/"))
                            .flatMap(p -> methods.isEmpty()
                                    ? Stream.of("GET " + normalise(p))
                                    : methods.stream().map(m -> m.name() + " " + normalise(p)));
                })
                .distinct()
                .filter(key -> !classified.contains(key))
                // Login and refresh are exercised in depth by the authentication tests.
                .filter(key -> !key.endsWith("/api/v1/auth/login") && !key.endsWith("/api/v1/auth/refresh"))
                .sorted()
                .toList();

        assertThat(unclassified)
                .as("endpoints with no permission-matrix row; add a case for each")
                .isEmpty();
    }

    /** Collapses path variables so a concrete test path matches its mapping pattern. */
    private static String normalise(String path) {
        return path.replaceAll("\\{[^}]+}", "{id}").replaceAll("/\\d+", "/{id}");
    }

    private String tokenFor(Caller caller) {
        return switch (caller) {
            case ANONYMOUS -> null;
            case ADMIN_HQ -> {
                support.createUser("matrix-admin", null, Role.ADMIN);
                yield support.accessTokenFor(rest, "matrix-admin");
            }
            case MANAGER_DEPOT_1 -> {
                support.createUser("matrix-manager", 1L, Role.MANAGER);
                yield support.accessTokenFor(rest, "matrix-manager");
            }
            case PLANNER_HQ -> {
                support.createUser("matrix-planner", null, Role.PLANNER);
                yield support.accessTokenFor(rest, "matrix-planner");
            }
            case SCHEDULER_DEPOT_1 -> {
                support.createUser("matrix-scheduler", 1L, Role.SCHEDULER);
                yield support.accessTokenFor(rest, "matrix-scheduler");
            }
        };
    }
}
