package com.dtc.transit.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need a real PostGIS database.
 *
 * <p>An in-memory database would not do. The schema depends on PostGIS types, GiST indexes,
 * {@code tstzrange} exclusion constraints, partial unique indexes and declarative partitioning, none
 * of which H2 reproduces faithfully. Testing against anything else would verify the wrong database.
 *
 * <p>The container is a JVM-wide singleton, started once in a static initialiser and never stopped.
 * This deliberately avoids {@code @Testcontainers} with {@code @Container}: that pair ties the
 * container's lifecycle to the <em>test class</em>, so with a shared base class the first class to
 * finish shuts the container down and every later class fails with "Failed to obtain JDBC Connection".
 * Ryuk removes the container when the JVM exits, so nothing leaks.
 */
@Tag("integration")
@SpringBootTest
public abstract class PostgisContainerTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGIS.start();
    }
}
