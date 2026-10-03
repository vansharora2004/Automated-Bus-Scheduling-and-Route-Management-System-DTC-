package com.dtc.transit.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.user.Role;

/**
 * The generated OpenAPI document actually describes the API.
 *
 * <p>Springdoc can silently produce a document missing whole controllers — a CGLIB proxy on a controller broke it
 * outright in Phase 3, and nothing failed until somebody opened the Swagger UI. This asserts that every API group
 * appears and that the bearer scheme is declared, so a regression in the documentation is a build failure rather
 * than a discovery.
 */
class OpenApiDocumentTest extends SecurityWebTest {

    private String admin;

    @BeforeEach
    void authenticate() {
        support.createUser("openapi-admin", null, Role.ADMIN);
        admin = support.accessTokenFor(rest, "openapi-admin");
    }

    @Test
    @DisplayName("the document is generated and covers every API group")
    void documentCoversEveryApiGroup() {
        ResponseEntity<String> response = support.call(rest, HttpMethod.GET, "/v3/api-docs", admin, null);

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        var paths = support.json(response).get("paths");
        assertThat(paths).as("the document should list paths").isNotNull();

        // One representative path per group. A missing group means a controller was dropped from the document,
        // which is exactly what a broken proxy does.
        List<String> expected = List.of(
                "/api/v1/auth/login",
                "/api/v1/users",
                "/api/v1/depots",
                "/api/v1/buses",
                "/api/v1/crew",
                "/api/v1/stops",
                "/api/v1/routes",
                "/api/v1/coverage/zones",
                "/api/v1/timetables",
                "/api/v1/trips",
                "/api/v1/deadheads",
                "/api/v1/calendar/exceptions",
                "/api/v1/rule-sets",
                "/api/v1/schedule-runs",
                "/api/v1/schedules",
                "/api/v1/duty-assignments",
                "/api/v1/reports/fleet-utilization",
                "/api/v1/dashboard/today",
                "/api/v1/audit-logs");

        for (String path : expected) {
            assertThat(paths.has(path)).as("the document should describe %s", path).isTrue();
        }
    }

    @Test
    @DisplayName("the bearer scheme is declared, so the Swagger UI can authenticate")
    void bearerSchemeIsDeclared() {
        ResponseEntity<String> response = support.call(rest, HttpMethod.GET, "/v3/api-docs", admin, null);
        var document = support.json(response);

        // Without this the Authorize button does not exist, every try-it-out request goes out unauthenticated,
        // and the documentation looks broken when it is the description of the authentication that is missing.
        var scheme = document.get("components").get("securitySchemes").get("bearerAuth");
        assertThat(scheme).isNotNull();
        assertThat(scheme.get("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.get("bearerFormat").asText()).isEqualTo("JWT");
        assertThat(document.get("info").get("title").asText()).contains("DTC");
    }

    @Test
    @DisplayName("duty and schedule sub-resources are documented, not just their parents")
    void nestedResourcesAreDocumented() {
        ResponseEntity<String> response = support.call(rest, HttpMethod.GET, "/v3/api-docs", admin, null);
        var paths = support.json(response).get("paths");

        // These carry path variables, which is where a template mismatch would quietly drop a path.
        assertThat(paths.has("/api/v1/schedules/{scheduleId}/duties")).isTrue();
        assertThat(paths.has("/api/v1/schedules/{scheduleId}/handovers")).isTrue();
        assertThat(paths.has("/api/v1/schedules/{id}/publish")).isTrue();
        assertThat(paths.has("/api/v1/schedule-runs/{id}/events")).isTrue();
    }
}
