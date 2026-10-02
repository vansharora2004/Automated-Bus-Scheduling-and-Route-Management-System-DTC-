package com.dtc.transit.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dtc.transit.support.SecurityTestSupport;
import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.user.Role;

/** Login and user administration leave an audit trail. */
class AuditTrailTest extends SecurityWebTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a successful login is audited")
    void successfulLoginIsAudited() {
        support.createUser("audit-login", null, Role.PLANNER);

        support.login(rest, "audit-login", SecurityTestSupport.PASSWORD);

        assertThat(actions()).contains("LOGIN_SUCCEEDED");
    }

    @Test
    @DisplayName("login rows name the acting username, and no actor that looks like an account")
    void loginActorIsNotMistakenForAnAccount() {
        support.createUser("audit-actor", null, Role.PLANNER);

        support.login(rest, "audit-actor", SecurityTestSupport.PASSWORD);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT actor, entity_id FROM audit_log WHERE action = 'LOGIN_SUCCEEDED'");
        assertThat(rows).hasSize(1);
        // Spring's anonymous principal is named "anonymousUser", which in an audit trail reads like a
        // real account. The username belongs in entity_id, where a reader can act on it.
        assertThat(rows.get(0)).containsEntry("actor", "anonymous");
        assertThat(rows.get(0)).containsEntry("entity_id", "audit-actor");
    }

    @Test
    @DisplayName("a failed login is audited with a reason")
    void failedLoginIsAudited() {
        support.createUser("audit-fail", null, Role.PLANNER);

        support.login(rest, "audit-fail", "wrong-password");

        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT action, entity_id, reason FROM audit_log WHERE action = 'LOGIN_FAILED'");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("entity_id", "audit-fail");
        assertThat(rows.get(0).get("reason")).isEqualTo("bad password");
    }

    @Test
    @DisplayName("creating a user records the new state and the acting administrator")
    void userCreationIsAudited() {
        support.createUser("audit-admin", null, Role.ADMIN);
        String token = support.accessTokenFor(rest, "audit-admin");

        var created = support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/users",
                token,
                """
                {"username":"audited-new-user","password":"correct-horse-battery-staple","roles":["MANAGER"]}""");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT actor, action, entity_type, after, trace_id FROM audit_log WHERE action = 'APP_USER_CREATED'");

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row).containsEntry("entity_type", "APP_USER");
        // The actor is the authenticated principal, which is the user id in the subject claim.
        assertThat(row.get("actor")).isNotNull();
        assertThat(String.valueOf(row.get("after"))).contains("audited-new-user").contains("MANAGER");
        // Correlation id links the audit row back to the request that caused it.
        assertThat(row.get("trace_id")).isNotNull();
    }

    @Test
    @DisplayName("updating a user records both the previous and the new state")
    void userUpdateIsAudited() {
        support.createUser("audit-admin-2", null, Role.ADMIN);
        String token = support.accessTokenFor(rest, "audit-admin-2");
        var target = support.createUser("audit-target", 3L, Role.SCHEDULER);

        var response = support.call(
                rest,
                HttpMethod.PATCH,
                "/api/v1/users/" + target.getId(),
                token,
                """
                {"enabled":false}""");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT before, after FROM audit_log WHERE action = 'APP_USER_UPDATED'");

        assertThat(rows).hasSize(1);
        assertThat(String.valueOf(rows.get(0).get("before"))).contains("\"enabled\": true");
        assertThat(String.valueOf(rows.get(0).get("after"))).contains("\"enabled\": false");
    }

    @Test
    @DisplayName("audit rows never contain password material")
    void auditNeverStoresSecrets() {
        support.createUser("audit-admin-3", null, Role.ADMIN);
        String token = support.accessTokenFor(rest, "audit-admin-3");

        support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/users",
                token,
                """
                {"username":"secret-holder","password":"correct-horse-battery-staple","roles":["PLANNER"]}""");

        List<String> payloads = jdbc.queryForList(
                "SELECT coalesce(before::text, '') || coalesce(after::text, '') FROM audit_log", String.class);

        assertThat(payloads)
                .as("neither the raw password nor its hash may be written to the audit log")
                .noneMatch(p -> p.contains("correct-horse-battery-staple") || p.contains("bcrypt"));
    }

    @Test
    @DisplayName("the audit row lands in the monthly partition for its timestamp")
    void auditRowIsPartitioned() {
        support.createUser("audit-partition", null, Role.PLANNER);
        support.login(rest, "audit-partition", SecurityTestSupport.PASSWORD);

        // tableoid resolves to the concrete partition that physically holds the row.
        List<String> partitions = jdbc.queryForList(
                "SELECT DISTINCT tableoid::regclass::text FROM audit_log", String.class);

        assertThat(partitions).isNotEmpty();
        assertThat(partitions).allSatisfy(name -> assertThat(name).startsWith("audit_log_"));
        assertThat(partitions)
                .as("rows should land in a real monthly partition, not the catch-all default")
                .noneMatch(name -> name.equals("audit_log_default"));
    }

    private List<String> actions() {
        return jdbc.queryForList("SELECT action FROM audit_log", String.class);
    }
}
