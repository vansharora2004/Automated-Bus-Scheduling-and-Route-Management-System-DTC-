package com.dtc.transit.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need a real PostGIS database.
 *
 * <p>An in-memory database would not do. The schema depends on PostGIS types, GiST indexes,
 * {@code tstzrange} exclusion constraints and partial unique indexes, none of which H2 reproduces
 * faithfully. Testing against anything else would verify the wrong database.
 *
 * <p>The container is static, so one instance is reused across every subclass in the same JVM rather
 * than started per test class.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
public abstract class PostgisContainerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));
}
