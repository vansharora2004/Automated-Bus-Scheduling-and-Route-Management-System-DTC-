package com.dtc.transit.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dtc.transit.support.PostgisContainerTest;

/**
 * Phase 1 verification: Flyway runs against a real PostGIS instance and leaves the expected
 * foundation in place.
 */
class DatabaseFoundationTest extends PostgisContainerTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Flyway applies V1 and records it in the history table")
    void flywayAppliedFirstMigration() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    @DisplayName("postgis and btree_gist extensions are installed")
    void extensionsInstalled() {
        List<String> extensions =
                jdbc.queryForList("SELECT extname FROM pg_extension ORDER BY extname", String.class);

        assertThat(extensions).contains("postgis", "btree_gist");
    }

    @Test
    @DisplayName("PostGIS answers a spatial query in the metric CRS used for Delhi")
    void postgisIsUsable() {
        // A one-degree line near Delhi, measured in EPSG:32643 (UTM 43N). The assertion is loose on
        // purpose: the point is that ST_Transform and ST_Length work, not the exact distance.
        Double lengthMetres = jdbc.queryForObject(
                """
                SELECT ST_Length(
                         ST_Transform(
                           ST_SetSRID(ST_MakeLine(ST_MakePoint(77.0, 28.6), ST_MakePoint(77.0, 28.61)), 4326),
                           32643))
                """,
                Double.class);

        assertThat(lengthMetres).isBetween(1_000.0, 1_200.0);
    }

    @Test
    @DisplayName("id sequences exist and increment by the Hibernate allocation size")
    void sequencesUsePooledAllocation() {
        List<String> sequences = jdbc.queryForList(
                "SELECT sequencename FROM pg_sequences WHERE schemaname = 'public' ORDER BY sequencename",
                String.class);

        assertThat(sequences).contains("app_user_seq", "refresh_token_seq", "audit_log_seq");

        // INCREMENT BY must match allocationSize on the entity, or the two allocators collide.
        Long increment = jdbc.queryForObject(
                "SELECT increment_by FROM pg_sequences WHERE sequencename = 'app_user_seq'", Long.class);
        assertThat(increment).isEqualTo(50L);
    }
}
