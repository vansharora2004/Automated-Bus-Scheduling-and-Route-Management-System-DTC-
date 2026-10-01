package com.dtc.transit.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.dtc.transit.support.PostgisContainerTest;

/**
 * Phase 1 verification: the application starts over HTTP and reports itself healthy.
 *
 * <p>The health check includes the datasource, so an UP response also proves the application reached
 * the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorHealthTest extends PostgisContainerTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("/actuator/health returns UP")
    void healthIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("every response carries a correlation id header")
    void correlationIdIsPresent() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }
}
