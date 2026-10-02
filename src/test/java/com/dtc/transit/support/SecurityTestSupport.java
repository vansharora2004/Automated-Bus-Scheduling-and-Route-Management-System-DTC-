package com.dtc.transit.support;

import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.dtc.transit.security.LoginRateLimiter;
import com.dtc.transit.user.AppUser;
import com.dtc.transit.user.AppUserRepository;
import com.dtc.transit.user.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Creates accounts and obtains real tokens for security tests.
 *
 * <p>Tokens are obtained through the actual login endpoint rather than minted directly, so the tests
 * exercise the same path a client uses, including the filter chain and the claims it depends on.
 *
 * <p>Every response is read as text and parsed here rather than bound to a response type. A failed
 * login answers with {@code application/problem+json}, and binding that to a token record throws
 * before the test can even see the status code, which turns every expected rejection into an
 * unreadable error.
 */
@Component
public class SecurityTestSupport {

    /** Long enough to satisfy the minimum length the create-user endpoint enforces. */
    public static final String PASSWORD = "correct-horse-battery-staple";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoginRateLimiter rateLimiter;

    /**
     * Removes every account, token, audit row and master-data row so each test starts from a known
     * state.
     *
     * <p>Deletion order follows the foreign keys inwards. {@code app_user} references {@code depot} from
     * Phase 3 onward, so accounts go before depots.
     */
    public void reset() {
        // Every test logs in from 127.0.0.1, so without this the per-address budget is shared across
        // unrelated cases and later tests fail with 429 for reasons that have nothing to do with them.
        rateLimiter.reset();
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM audit_log");
        // Schedules before timetables: block events reference trips and stops, bus assignments reference
        // buses, and every one of them hangs off a schedule that hangs off a run.
        jdbc.update("DELETE FROM duty_assignment");
        jdbc.update("DELETE FROM handover");
        jdbc.update("DELETE FROM duty_piece");
        jdbc.update("DELETE FROM duty");
        jdbc.update("DELETE FROM piece_of_work");
        jdbc.update("DELETE FROM conflict");
        jdbc.update("DELETE FROM bus_assignment");
        jdbc.update("DELETE FROM block_event");
        jdbc.update("DELETE FROM vehicle_block");
        jdbc.update("UPDATE schedule_run SET schedule_id = NULL");
        jdbc.update("DELETE FROM schedule");
        jdbc.update("DELETE FROM schedule_run");
        // Only depot-scoped rule sets. The global one is seeded by migration and resolution depends on it, so
        // deleting it would make every run fail with NO_RULE_SET.
        jdbc.update("DELETE FROM rule_set WHERE depot_id IS NOT NULL");
        // Timetables before routes: trips reference patterns and stops, timetables reference routes.
        jdbc.update("DELETE FROM trip");
        jdbc.update("DELETE FROM headway_band");
        jdbc.update("DELETE FROM timetable");
        jdbc.update("DELETE FROM deadhead");
        jdbc.update("DELETE FROM calendar_exception");
        // Routes first: they reference depot and stop, so they must go before either.
        // service_area is deliberately left alone - it is seeded by migration and geometry validation
        // depends on it.
        jdbc.update("DELETE FROM route_overlap");
        jdbc.update("DELETE FROM pattern_stop");
        jdbc.update("DELETE FROM running_time_band");
        jdbc.update("DELETE FROM route_pattern");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM coverage_zone");
        jdbc.update("DELETE FROM grid_cell");
        jdbc.update("DELETE FROM crew_qualification");
        jdbc.update("DELETE FROM crew_leave");
        jdbc.update("DELETE FROM crew_depot_history");
        jdbc.update("DELETE FROM crew_member");
        jdbc.update("DELETE FROM bus_unavailability");
        jdbc.update("DELETE FROM bus");
        jdbc.update("DELETE FROM stop");
        jdbc.update("DELETE FROM depot");
    }

    /**
     * Creates an account, first making sure the depot it is bound to exists.
     *
     * <p>Phase 3 added the foreign key from {@code app_user.depot_id}, so a test that wants a
     * depot-bound user needs a real depot row behind it. Seeding one here keeps the Phase 2 tests
     * readable: they care that the user belongs to "some depot", not which.
     */
    public AppUser createUser(String username, Long depotId, Role... roles) {
        if (depotId != null) {
            ensureDepot(depotId);
        }
        var user = new AppUser(username, passwordEncoder.encode(PASSWORD), Set.of(roles), depotId);
        return users.save(user);
    }

    /** Inserts a depot with an explicit id, keeping the sequence clear of it. */
    public void ensureDepot(Long id) {
        Integer existing =
                jdbc.queryForObject("SELECT count(*) FROM depot WHERE id = ?", Integer.class, id);
        if (existing != null && existing > 0) {
            return;
        }
        jdbc.update(
                """
                INSERT INTO depot (id, code, name, location, parking_capacity, charging_bays)
                VALUES (?, ?, ?, ST_SetSRID(ST_MakePoint(77.2, 28.6), 4326), 100, 4)
                """,
                id,
                "FIXTURE-" + id,
                "Fixture depot " + id);
        // Keep generated ids clear of the explicit one, so a later insert cannot collide.
        jdbc.queryForObject("SELECT setval('depot_seq', GREATEST(?, (SELECT last_value FROM depot_seq)))",
                Long.class, id + 1000);
    }

    public ResponseEntity<String> login(TestRestTemplate rest, String username, String password) {
        String body = """
                {"username":"%s","password":"%s"}""".formatted(username, password);
        return post(rest, "/api/v1/auth/login", body);
    }

    public ResponseEntity<String> refresh(TestRestTemplate rest, String refreshToken) {
        String body = """
                {"refreshToken":"%s"}""".formatted(refreshToken);
        return post(rest, "/api/v1/auth/refresh", body);
    }

    /** Logs in and returns the access token, failing loudly if login did not succeed. */
    public String accessTokenFor(TestRestTemplate rest, String username) {
        ResponseEntity<String> response = login(rest, username, PASSWORD);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(
                    "login failed for " + username + ": " + response.getStatusCode() + " " + response.getBody());
        }
        return field(response, "accessToken");
    }

    /** Logs in and returns both tokens. */
    public Tokens tokensFor(TestRestTemplate rest, String username) {
        ResponseEntity<String> response = login(rest, username, PASSWORD);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(
                    "login failed for " + username + ": " + response.getStatusCode() + " " + response.getBody());
        }
        return new Tokens(field(response, "accessToken"), field(response, "refreshToken"));
    }

    /** The response body as a JSON tree, for assertions that need to navigate structure. */
    public JsonNode json(ResponseEntity<String> response) {
        try {
            return JSON.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("response was not JSON: " + response.getBody(), e);
        }
    }

    /** Reads one string field from a JSON response body. */
    public String field(ResponseEntity<String> response, String name) {
        try {
            JsonNode node = JSON.readTree(response.getBody());
            JsonNode value = node.get(name);
            return value == null || value.isNull() ? null : value.asText();
        } catch (Exception e) {
            throw new IllegalStateException("response was not JSON: " + response.getBody(), e);
        }
    }

    public long longField(ResponseEntity<String> response, String name) {
        try {
            return JSON.readTree(response.getBody()).get(name).asLong();
        } catch (Exception e) {
            throw new IllegalStateException("response was not JSON: " + response.getBody(), e);
        }
    }

    /** Issues a request carrying a bearer token, or no token when {@code token} is null. */
    public ResponseEntity<String> call(
            TestRestTemplate rest, HttpMethod method, String path, String token, String body) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> post(TestRestTemplate rest, String path, String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    public record Tokens(String accessToken, String refreshToken) {}
}
