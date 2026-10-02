package com.dtc.transit.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.user.Role;

/**
 * The paging contract, exercised over HTTP against every list endpoint.
 *
 * <p>Covers edge cases EC-API-01 to EC-API-05. These behaviours are the reason the framework exists, so
 * they are verified on the real endpoints rather than only on the helper in isolation.
 */
class PagingContractTest extends SecurityWebTest {

    private String adminToken;

    /** Every paginated list endpoint delivered so far. */
    private static final String[] LIST_ENDPOINTS = {
        "/api/v1/users", "/api/v1/depots", "/api/v1/stops", "/api/v1/buses", "/api/v1/crew"
    };

    @BeforeEach
    void createAdmin() {
        support.createUser("paging-admin", null, Role.ADMIN);
        adminToken = support.accessTokenFor(rest, "paging-admin");
    }

    @Test
    @DisplayName("the framework is reused by at least four list endpoints")
    void frameworkIsSharedAcrossEndpoints() {
        assertThat(LIST_ENDPOINTS).hasSizeGreaterThanOrEqualTo(4);

        for (String endpoint : LIST_ENDPOINTS) {
            ResponseEntity<String> response = get(endpoint + "?page=0&size=5");

            assertThat(response.getStatusCode()).as("%s", endpoint).isEqualTo(HttpStatus.OK);
            // One envelope for all of them, which is the point of writing it once.
            assertThat(response.getBody())
                    .as("%s envelope", endpoint)
                    .contains("\"content\"")
                    .contains("\"page\"")
                    .contains("\"size\"")
                    .contains("\"hasNext\"")
                    .contains("\"sort\"");
        }
    }

    @Test
    @DisplayName("EC-API-01: a size above the maximum is clamped and the response says so")
    void oversizedPageIsClamped() {
        for (String endpoint : LIST_ENDPOINTS) {
            ResponseEntity<String> response = get(endpoint + "?size=100000");

            assertThat(response.getStatusCode()).as("%s", endpoint).isEqualTo(HttpStatus.OK);
            assertThat(support.longField(response, "size"))
                    .as("%s effective size", endpoint)
                    .isEqualTo(100);
        }
    }

    @Test
    @DisplayName("EC-API-02: a negative page is rejected rather than silently repaired")
    void negativePageRejected() {
        for (String endpoint : LIST_ENDPOINTS) {
            ResponseEntity<String> response = get(endpoint + "?page=-1");

            // Spring's resolver would turn this into page 0 and answer 200, so the caller would believe
            // they had read the page they asked for.
            assertThat(response.getStatusCode()).as("%s", endpoint).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(support.field(response, "code")).isEqualTo("INVALID_PAGING");
        }
    }

    @Test
    @DisplayName("EC-API-02: a zero or negative size is rejected")
    void nonPositiveSizeRejected() {
        assertThat(get("/api/v1/depots?size=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/depots?size=-5").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a non-numeric paging parameter is rejected")
    void nonNumericPagingRejected() {
        ResponseEntity<String> response = get("/api/v1/depots?page=first");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(support.field(response, "detail")).contains("must be an integer");
    }

    @Test
    @DisplayName("EC-API-04: an unknown sort field is rejected and the error lists what is allowed")
    void unknownSortRejected() {
        ResponseEntity<String> response = get("/api/v1/users?sort=passwordHash,asc");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(support.field(response, "code")).isEqualTo("INVALID_SORT");
        // A bare "bad sort" leaves the caller guessing at a set they cannot discover.
        assertThat(support.field(response, "detail")).contains("Allowed:").contains("username");
    }

    @Test
    @DisplayName("EC-API-04: sorting is refused on every list endpoint for an unknown field")
    void unknownSortRejectedEverywhere() {
        for (String endpoint : LIST_ENDPOINTS) {
            assertThat(get(endpoint + "?sort=definitelyNotAField,asc").getStatusCode())
                    .as("%s", endpoint)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("EC-API-03: a page beyond the end is empty, not an error")
    void pageBeyondEndIsEmpty() {
        ResponseEntity<String> response = get("/api/v1/users?page=500&size=20");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"content\":[]");
        // The total still describes the collection, so a client can tell it overshot.
        assertThat(support.longField(response, "totalElements")).isEqualTo(1);
    }

    @Test
    @DisplayName("the applied sort is reported, including the id tiebreaker")
    void appliedSortIsReported() {
        ResponseEntity<String> response = get("/api/v1/users?sort=username,desc");

        assertThat(response.getBody()).contains("username,desc").contains("id,asc");
    }

    private ResponseEntity<String> get(String path) {
        return support.call(rest, HttpMethod.GET, path, adminToken, null);
    }
}
