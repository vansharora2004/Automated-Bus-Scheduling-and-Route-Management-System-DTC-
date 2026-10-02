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

    /** Removes every account, token and audit row so each test starts from a known state. */
    public void reset() {
        // Every test logs in from 127.0.0.1, so without this the per-address budget is shared across
        // unrelated cases and later tests fail with 429 for reasons that have nothing to do with them.
        rateLimiter.reset();
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM audit_log");
    }

    public AppUser createUser(String username, Long depotId, Role... roles) {
        var user = new AppUser(username, passwordEncoder.encode(PASSWORD), Set.of(roles), depotId);
        return users.save(user);
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
