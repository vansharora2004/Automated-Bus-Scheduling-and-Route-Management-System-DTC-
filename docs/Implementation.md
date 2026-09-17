# Automated Bus Scheduling and Route Management System — Implementation Plan

## Overview

The project is built incrementally.

Each phase ends with a working, tested, demonstrable increment and explicit exit criteria. Nothing moves to the next phase until the current one runs.

Durations are indicative, for one to two developers.

```text
Phase 0  -> Foundations                          1 week
Phase 1  -> Security and RBAC                    1 week
Phase 2  -> Master data, paging and filtering    2 weeks
Phase 3  -> Route management and geospatial      2 weeks
Phase 4  -> Timetables, trips, synthetic data    1 week
Phase 5  -> Vehicle scheduling (blocks)          1 week
Phase 6  -> Constraint engine and linked duties  2 weeks
Phase 7  -> Unlinked duties and handovers        2 weeks
Phase 8  -> Crew assignment, conflicts, publish  2 weeks
Phase 9  -> Reporting, dashboard and audit       1 week
Phase 10 -> Hardening and deployment             1 week

Total                                            ~16 weeks
```

Phase order is not arbitrary. Each phase depends on what came before:

```text
P0 Foundations
      |
      v
P1 Security
      |
      v
P2 Master data
      |
      +----------------+
      |                |
      v                v
P3 Routes & GIS    P4 Timetables
      |                |
      +--------+-------+
               |
               v
          P5 Blocks
               |
               v
      P6 Linked duties
               |
        +------+------+
        |             |
        v             v
P7 Unlinked      P8 Assignment
  duties            & publish
        |             |
        +------+------+
               |
               v
        P9 Reporting
               |
               v
        P10 Hardening
```

## Definition of done

These apply to every phase, not just the ones where they are obviously relevant:

1. Code follows the module and layer boundaries defined in the architecture. ArchUnit tests pass.
2. Unit tests cover domain logic. Integration tests using Testcontainers cover repositories and native SQL.
3. New endpoints appear in OpenAPI with request and response examples and the roles they require.
4. Authorization tests exist for every new endpoint and role combination.
5. A Flyway migration is added, and the schema starts cleanly from an empty database.
6. The relevant edge cases have tests.
7. The phase exit criteria are met and demonstrated.

---

# Phase 0 — Foundations

## Goal

A reproducible project skeleton that builds, runs against a local PostGIS, and has test infrastructure ready before any domain code is written.

## Tasks

1. Generate a Spring Boot 3.x project with Java 21 and Maven, base package `com.dtc.transit`.
2. Add the dependencies listed below.
3. Write `docker-compose.yml` with PostGIS.
4. Set up profiles `local`, `test` and `prod`, with configuration externalised through environment variables.
5. Add Flyway `V1__extensions.sql` creating `postgis`, `btree_gist` and the sequences.
6. Build the `common` module: `ProblemDetail` handler, `Clock` bean, correlation ID filter, `ServiceTime` utilities.
7. Add a Testcontainers base class using `postgis/postgis:16-3.4`.
8. Add an ArchUnit test skeleton enforcing module boundaries.
9. Add springdoc-openapi and Actuator, exposing health, info and prometheus.
10. Add formatting and static analysis: Spotless, plus Error Prone or SpotBugs.

## Project structure

```text
dtc-bus-scheduling/
  pom.xml
  docker-compose.yml
  src/main/java/com/dtc/transit/
    TransitApplication.java
    common/       config, error, paging, filtering, geo, time, audit base
    security/     SecurityConfig, TokenService, AuthController,
                  DepotAccessEvaluator
    user/
    masterdata/   depot/ bus/ crew/ stop/
    route/        route/ pattern/ overlap/ coverage/ proposal/
    timetable/    timetable/ headway/ trip/ deadhead/ calendar/
    scheduling/
      engine/     model/ vehicle/ relief/ duty/ assignment/
                  constraint/ search/     <- no Spring here
      rules/      RuleSet entity and binding
      run/        ScheduleRun, RunWorker, RunReaper, SSE
      schedule/   Schedule, Block, Duty, Assignment, Conflict
      api/
    reporting/
    audit/
  src/main/resources/
    application.yml
    db/migration/
  src/test/java/com/dtc/transit/
    architecture/  ArchUnit rules
    support/       PostgisContainerTest, TestData builders, JwtTestTokens
```

The test tree mirrors the main tree.

## Key dependencies

```xml
<properties>
  <java.version>21</java.version>
</properties>

<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
  </dependency>

  <!-- Geospatial. Version managed by Spring Boot's Hibernate BOM. -->
  <dependency>
    <groupId>org.hibernate.orm</groupId>
    <artifactId>hibernate-spatial</artifactId>
  </dependency>
  <!-- GeoJSON read and write. Align with the jts-core that
       hibernate-spatial pulls in. -->
  <dependency>
    <groupId>org.locationtech.jts.io</groupId>
    <artifactId>jts-io-common</artifactId>
    <version>${jts.version}</version>
  </dependency>

  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
  </dependency>

  <dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
    <version>${mapstruct.version}</version>
  </dependency>
  <dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>${springdoc.version}</version>
  </dependency>
  <dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
  </dependency>
  <dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
  </dependency>

  <!-- Test -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>net.jqwik</groupId>
    <artifactId>jqwik</artifactId>
    <version>${jqwik.version}</version>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <version>${archunit.version}</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

Pin every version property to the latest stable release compatible with the chosen Spring Boot version.

## Local infrastructure

```yaml
services:
  db:
    image: postgis/postgis:16-3.4
    environment:
      POSTGRES_DB: dtc_transit
      POSTGRES_USER: dtc
      POSTGRES_PASSWORD: ${DB_PASSWORD:-local_dev_only}
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U dtc -d dtc_transit"]
      interval: 5s
      retries: 10
volumes:
  pgdata:
```

## Base configuration

```yaml
spring:
  application:
    name: dtc-transit
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/dtc_transit}
    username: ${DB_USER:dtc}
    password: ${DB_PASSWORD:local_dev_only}
    hikari:
      maximum-pool-size: 20
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc.time_zone: UTC
        jdbc.batch_size: 500
        order_inserts: true
        order_updates: true
  data:
    web:
      pageable:
        default-page-size: 20
        max-page-size: 100
  threads:
    virtual:
      enabled: true
  flyway:
    enabled: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true
```

## Test base

```java
@SpringBootTest
@Testcontainers
public abstract class PostgisContainerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));
}
```

## Expected result

A runnable application, `/actuator/health` returning UP, Swagger UI reachable, an empty migrated schema and a passing test suite.

## Exit criteria

```text
./mvnw verify passes from a clean checkout, including one
Testcontainers test.

The app starts against docker compose up, and Flyway applies V1.

The ArchUnit rule "engine has no Spring or JPA imports" exists
and passes.
```

---

# Phase 1 — Security and RBAC

## Goal

Every endpoint is authenticated. Roles and depot scope are enforced. Tokens can actually be revoked, not just in theory.

Security comes second, before any domain data exists, because retrofitting depot scope onto finished endpoints is far harder than building with it.

## Tasks

1. Migrations for `app_user`, `user_role`, `refresh_token` and `audit_log`, with `audit_log` partitioned.
2. `UserService`: create, update roles and depot, enable and disable. Disabling increments `token_version`.
3. `TokenService`: RS256 encoder and decoder through Nimbus, 15-minute access tokens, 7-day hashed refresh tokens with rotation and reuse detection.
4. `AuthController` with login, refresh and logout.
5. `SecurityConfig`: stateless, deny by default, JWT roles converter, CORS allow-list, security headers.
6. `TokenVersionFilter`, rejecting tokens whose `tv` claim is behind the user's current version, with a 60-second cache.
7. `DepotAccessEvaluator` and a `DepotScope` specification helper.
8. Login rate limiting with Bucket4j, and account lockout.
9. Audit event infrastructure: `AuditEvent` and a listener writing in the same transaction.
10. Seed an admin user from an environment-provided bootstrap password, local profile only.

## Key code

```java
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http,
                            TokenVersionFilter tokenVersionFilter) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())          // stateless bearer tokens
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST,
                        "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/api/v1/users/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST,
                        "/api/v1/schedules/*/publish").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.POST,
                        "/api/v1/routes/*/decision").hasAnyRole("ADMIN", "MANAGER")
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(
                    jwt -> jwt.jwtAuthenticationConverter(rolesConverter())))
            .addFilterAfter(tokenVersionFilter, BearerTokenAuthenticationFilter.class)
            .build();
    }

    private JwtAuthenticationConverter rolesConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
```

```java
@Component("depotAccess")
public class DepotAccessEvaluator {

    /** HQ users have no depot claim and can access every depot.
        Depot-bound users can access only their own. */
    public boolean canAccess(Long depotId) {
        var jwt = (Jwt) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        Long userDepot = jwt.getClaim("depot");
        return userDepot == null || userDepot.equals(depotId);
    }
}
```

Used on an application service:

```java
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','SCHEDULER')"
            + " and @depotAccess.canAccess(#cmd.depotId())")
public ScheduleRunId startRun(StartRunCommand cmd) { ... }
```

## Tests

Login success and failure, lockout after five failures, refresh rotation, and refresh reuse revoking the whole token family.

A disabled user's token is rejected after the cache TTL.

A parameterised test over the permission matrix. This test grows in every later phase, and a new unclassified endpoint makes it fail.

## Exit criteria

```text
Unauthenticated calls to any non-public endpoint       401
Wrong role                                             403
Out-of-depot access                                    404

JWTs are signed with RS256.
alg=none and HS256 tokens are rejected, and this is tested.
Login and user administration are audited.
```

---

# Phase 2 — Master Data, Pagination and Filtering

## Goal

CRUD for depots, buses, crew and stops, built on a reusable pagination, filtering and sorting framework that every later list endpoint uses.

The framework is the real deliverable here. Writing it once means the 22 paginated endpoints behave identically.

## Tasks

1. Migrations for `depot`, `bus`, `bus_unavailability`, `crew_member`, `crew_depot_history`, `crew_leave`, `crew_qualification` and `stop`, including GiST indexes.
2. Entities with `@Version`, sequence ids using `allocationSize = 50`, and JTS `Point` columns.
3. `common.paging`: `PageResponse`, `SortWhitelist`, `includeTotal` returning a `Slice`, and `CursorPage` for keyset pagination.
4. `common.filtering`: filter records bound from query parameters, a specifications helper, and range and enum validation.
5. `common.geo`: `GeoJsonCodec`, plus `bbox` and `near` parameter parsing and validation, including the service-area check and lon/lat order check.
6. Normalisation: registration numbers upper-cased with spaces and dashes removed, employee codes kept as text so leading zeros survive.
7. Depot and stop endpoints, bus and crew endpoints.
8. CSV bulk import for buses and crew, with a per-row validation report, all-or-nothing per file, and `dryRun=true` support.
9. Depot scope applied to every list and detail query.

## Key code

```java
public record PageResponse<T>(List<T> content, int page, int size,
                              Long totalElements, Integer totalPages,
                              boolean hasNext, List<String> sort) {

    public static <E, T> PageResponse<T> of(Page<E> p, Function<E, T> mapper) {
        return new PageResponse<>(p.map(mapper).getContent(),
                p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages(),
                p.hasNext(), sortOf(p.getSort()));
    }

    // includeTotal=false: no count query, so no totals
    public static <E, T> PageResponse<T> of(Slice<E> s, Function<E, T> mapper) {
        return new PageResponse<>(s.map(mapper).getContent(),
                s.getNumber(), s.getSize(),
                null, null, s.hasNext(), sortOf(s.getSort()));
    }

    private static List<String> sortOf(Sort sort) {
        return sort.stream()
                .map(o -> o.getProperty() + "," + o.getDirection().name().toLowerCase())
                .toList();
    }
}
```

```java
@Component
public class SortWhitelist {

    /** Rejects unknown sort fields, and always appends id
        so that ordering is stable across pages. */
    public Pageable check(Pageable pageable, Set<String> allowed) {
        for (Sort.Order o : pageable.getSort()) {
            if (!allowed.contains(o.getProperty())) {
                throw new InvalidSortException(o.getProperty(), allowed);
            }
        }
        Sort stable = pageable.getSort().and(Sort.by("id"));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), stable);
    }
}
```

```java
public record BusFilter(Long depotId, BusStatus status, FuelType fuelType,
                        BusType busType, Boolean ac, String q) {}

final class BusSpecifications {

    static Specification<Bus> of(BusFilter f, DepotScope scope) {
        return Specification.allOf(
            scope.restrict("depot"),
            eq("depot.id", f.depotId()),
            eq("status", f.status()),
            eq("fuelType", f.fuelType()),
            eq("busType", f.busType()),
            eq("ac", f.ac()),
            f.q() == null ? null : (root, query, cb) -> cb.or(
                cb.like(root.get("registrationNo"),
                        "%" + RegistrationNo.normalise(f.q()) + "%"),
                cb.like(cb.upper(root.get("fleetNo")),
                        "%" + f.q().toUpperCase() + "%")));
    }
}
```

```java
@RestController
@RequestMapping("/api/v1/buses")
class BusController {

    private static final Set<String> SORTABLE =
            Set.of("fleetNo", "registrationNo", "status", "depot.code");

    @GetMapping
    PageResponse<BusDto> list(BusFilter filter,
                              @PageableDefault(sort = "fleetNo") Pageable pageable,
                              @RequestParam(defaultValue = "true") boolean includeTotal) {
        return busService.search(filter,
                sortWhitelist.check(pageable, SORTABLE), includeTotal);
    }
}
```

Wildcards in the `q` parameter, meaning `%` and `_`, are escaped before the `LIKE` pattern is built. Filter values are always bound parameters and never concatenated into SQL.

## Tests

Pagination edge cases: a size above 100 is clamped, a negative page gives 400, a page beyond the end gives an empty result, an unknown sort field gives 400, and ordering stays stable when the sort values are duplicated.

A depot-scoped scheduler cannot list or fetch another depot's buses, and gets 404.

CSV import: duplicate registration numbers in different formats, leading-zero employee codes, UTF-8 Hindi names, and an unknown depot code.

## Exit criteria

```text
Depot, bus, crew and stop endpoints implemented, documented
and authorization-tested.

The paging and filtering framework is reused by at least four
list endpoints.

EXPLAIN ANALYZE shows index usage for filtered list queries on
100k synthetic rows.
```

---

# Phase 3 — Route Management and Geospatial

## Goal

Routes stored as validated geometries, fast overlap detection between existing and proposed routes, coverage analysis, and a proposal workflow.

## Tasks

1. Migrations for `route`, `route_pattern` with generated `geom_utm` and `length_m` plus a GiST index, `pattern_stop`, `running_time_band`, `route_overlap`, `coverage_zone`, `grid_cell`, `service_area` and the `cell_coverage` materialized view.
2. `RoutePattern` entity with a JTS `LineString` mapped through Hibernate Spatial.
3. Geometry validation pipeline.
4. Stop-to-line distance check, warning above 50 m, and `dist_from_start_m` computed with `ST_LineLocatePoint`.
5. `OverlapAnalysisService` using the native query, adding shared stops, direction agreement and severity.
6. `CoverageService`: grid-cell coverage view refresh, zone coverage query, and proposal coverage gain.
7. Proposal state machine with guarded transitions. Submitting stores the analysis, and a decision requires MANAGER and a reason.
8. Spatial filters `bbox` and `passesNear` on routes and stops.
9. Recompute overlaps asynchronously when an active pattern changes.

The validation pipeline runs in this order:

```text
parse GeoJSON
     |
     v
check SRID and CRS
     |
     v
check lon/lat order against the service area
     |
     v
at least 2 distinct points
     |
     v
ST_IsValid
     |
     v
optional ST_SimplifyPreserveTopology
     |
     v
vertex cap (e.g. 5,000)
```

## Key code

```java
@Entity
@Table(name = "route_pattern")
public class RoutePattern {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "route_pattern_seq")
    @SequenceGenerator(name = "route_pattern_seq",
                       sequenceName = "route_pattern_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Route route;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    @Column(columnDefinition = "geometry(LineString,4326)", nullable = false)
    private LineString geom;

    @Column(name = "length_m", insertable = false, updatable = false)
    private Double lengthM;                  // generated in the database

    @Version
    private long version;
}
```

```java
public interface OverlapRow {
    Long getPatternId();
    String getRouteNo();
    String getDirection();
    double getOverlapM();
    double getOverlapRatio();
}

public interface RoutePatternRepository extends JpaRepository<RoutePattern, Long> {

    @Query(nativeQuery = true, value = """
        WITH proposed AS (
          SELECT ST_Transform(
                   ST_SetSRID(ST_GeomFromGeoJSON(:geojson), 4326), 32643) AS g
        ),
        candidates AS (
          SELECT rp.id, rp.route_id, rp.direction, rp.geom_utm
          FROM route_pattern rp, proposed p
          WHERE ST_DWithin(rp.geom_utm, p.g, :bufferM)
            AND rp.id <> COALESCE(:excludeId, -1)
        ),
        segments AS (
          SELECT c.id AS pattern_id, c.route_id, c.direction, d.geom AS seg
          FROM candidates c, proposed p,
               LATERAL ST_Dump(ST_Intersection(p.g,
                   ST_Buffer(c.geom_utm, :bufferM,
                             'endcap=flat join=round'))) d
        )
        SELECT s.pattern_id AS patternId,
               r.route_no   AS routeNo,
               s.direction  AS direction,
               SUM(ST_Length(s.seg)) AS overlapM,
               SUM(ST_Length(s.seg))
                 / (SELECT ST_Length(g) FROM proposed) AS overlapRatio
        FROM segments s
        JOIN route r ON r.id = s.route_id AND r.status = 'ACTIVE'
        WHERE ST_Length(s.seg) >= :minSegmentM
        GROUP BY s.pattern_id, r.route_no, s.direction
        ORDER BY overlapM DESC
        """)
    List<OverlapRow> findOverlaps(@Param("geojson") String geojson,
                                  @Param("bufferM") double bufferM,
                                  @Param("minSegmentM") double minSegmentM,
                                  @Param("excludeId") Long excludeId);
}
```

A simple spatial filter goes through Hibernate Spatial rather than native SQL:

```java
@Query("select s from Stop s where s.active = true and st_within(s.location, :bbox) = true")
Page<Stop> findInBbox(@Param("bbox") Polygon bbox, Pageable pageable);
```

## Tests

These run against Testcontainers with hand-built geometries, so the expected numbers are known exactly:

```text
Identical routes                        ratio ~ 1.00
Parallel road 40 m away, 25 m buffer    ratio 0.00
Crossing routes                         ratio 0.00 (segment < 200 m)
3 km shared on a 10 km route            ratio 0.30 +/- 0.01
```

Opposite direction on the same corridor is detected and labelled `same_direction = false`.

A loop route where start equals end, and an out-and-back route, do not have their lengths double counted.

Swapped lon/lat input is rejected with a helpful message, and an invalid geometry is rejected.

Coverage: a zone entirely within 500 m of a stop gives 1.0, and a zone with no stops gives 0.

Performance: an overlap query against 2,000 patterns completes under 500 ms at p95 with the GiST index, and the same query is run without the index for comparison.

## Exit criteria

```text
Overlap analysis reproduces the expected values on the labelled
fixture set.

The proposal workflow is enforced, audited and
authorization-tested.

Route, stop and coverage endpoints are documented with GeoJSON
examples.
```

---

# Phase 4 — Timetables, Trips and Synthetic Data

## Goal

Generate trips from headways, maintain deadhead times, and produce reproducible datasets for scheduling development and evaluation.

Without datasets, nothing after this phase can be measured.

## Tasks

1. Migrations for `timetable`, `headway_band`, `trip`, `deadhead` and `calendar_exception`.
2. `TripGenerator`: per direction, walk the headway bands from service start to end, looking up running time by departure band. A trip crossing a band boundary uses the band at departure.
3. Validation: bands must not overlap, end must be after start, average speed must be plausible at roughly 8 to 45 km/h in urban operation, and duplicate trips are rejected.
4. `DeadheadMatrix` holding measured values, or an estimate of straight-line distance times a detour factor of 1.3 divided by band speed, flagged as estimated.
5. Day-type resolution with calendar exceptions for holidays and special events.
6. A timetable change marks dependent drafts and published schedules as needing revalidation.
7. A synthetic data generator running under a `seed` profile as a CLI runner, with a fixed random seed.
8. An optional GTFS static importer for stops, routes, shapes and trips, subject to the data source's licence terms.
9. Timetable and trip endpoints, with the trips list using keyset pagination.

The generator produces three datasets:

```text
S   1 depot,   20 routes,  ~120 buses,  ~1,200 trips,   ~300 crew
M   10 depots,           ~1,200 buses, ~12,000 trips, ~3,000 crew
L   45 depots,          5,000+ buses,  ~50,000 trips, ~12,000 crew
```

All three carry realistic structure: morning and evening peaks, radial and ring routes, terminals as relief points, an electric-bus share, a leave rate and a licence-expiry distribution.

## Key code

```java
public List<TripDraft> generate(Timetable tt, RoutePattern pattern, Direction dir) {
    var trips = new ArrayList<TripDraft>();
    for (HeadwayBand band : tt.bands(dir)) {              // sorted, non-overlapping
        for (int dep = band.fromSec(); dep < band.toSec(); dep += band.headwaySec()) {
            int run = pattern.runningTimeAt(tt.dayType(), dep);   // band lookup by departure
            trips.add(new TripDraft(pattern.id(), dep, dep + run, pattern.lengthM()));
        }
    }
    return trips;
}
```

## Exit criteria

```text
Generated trip counts match the analytical count, the sum over
bands of ceil(band length / headway).

S, M and L datasets generate deterministically from the same
seed, with a stable checksum.

A holiday override changes the timetable picked for that date,
and this is tested.
```

---

# Phase 5 — Vehicle Scheduling and Run Infrastructure

## Goal

Turn a depot-day's trips into blocks, assign buses, and run the whole thing through the asynchronous job pipeline.

## Tasks

1. Engine model as pure Java records: `TripView`, `DepotContext`, `VehicleClass`, `Block`, `BlockEvent`, `VehicleSchedule`.
2. `GreedyBestFitBlockBuilder`, handling minimum layover, deadhead, vehicle class, EV range with reserve, mid-day depot returns and charging events.
3. `MinFleetMatchingBlockBuilder` using Hopcroft-Karp, available as an alternative builder and as the PVR lower bound.
4. `BusAssigner`, mapping blocks to physical buses of the right class, honouring unavailability and preferring an even distribution of kilometres.
5. Migrations for `rule_set` in basic form, `schedule_run`, `schedule`, `vehicle_block`, `block_event`, `bus_assignment` with its exclusion constraint, `conflict`, and the partial unique indexes.
6. `ScheduleSnapshotLoader`, read-only at `REPEATABLE READ`, and `SchedulePersister` using JDBC batch writes.
7. The run queue: `ScheduleRunService.create` with an idempotency key, `RunWorker` claiming with `SKIP LOCKED`, a heartbeat, and `RunReaper`.
8. Run and schedule endpoints.

## Key code

```java
public final class GreedyBestFitBlockBuilder implements BlockBuilder {

    @Override
    public VehicleSchedule build(List<TripView> trips, DepotContext ctx, RuleSet rules) {
        List<TripView> ordered = trips.stream()
                .sorted(Comparator.comparingInt(TripView::startSec)
                                  .thenComparingLong(TripView::id))
                .toList();

        List<BlockDraft> blocks = new ArrayList<>();
        List<Uncovered> uncovered = new ArrayList<>();

        for (TripView trip : ordered) {
            BlockDraft best = null;
            int bestSlack = Integer.MAX_VALUE;

            for (BlockDraft b : blocks) {
                if (!b.vehicleClass().satisfies(trip.requiredClass())) continue;
                int readyAt = b.endSec()
                        + rules.minLayoverSec(b.lastTrip())
                        + ctx.deadheadSec(b.endStopId(), trip.startStopId(), b.endSec());
                int slack = trip.startSec() - readyAt;
                // canAppend checks EV range and maximum block length
                if (slack >= 0 && slack < bestSlack && b.canAppend(trip, ctx, rules)) {
                    best = b;
                    bestSlack = slack;
                }
            }

            if (best != null) {
                // inserts DEPOT_PARK and CHARGING events when the slack is large
                best.append(trip, ctx, rules);
            } else if (ctx.fleet().hasCapacity(trip.requiredClass(), blocks)) {
                blocks.add(BlockDraft.pullOutFor(trip, ctx, rules));
            } else {
                uncovered.add(new Uncovered(trip, ConflictType.UNCOVERED_TRIP,
                        "No available vehicle of class " + trip.requiredClass()));
            }
        }
        return VehicleSchedule.of(
                blocks.stream().map(b -> b.close(ctx, rules)).toList(), uncovered);
    }
}
```

Claiming a queued run from any instance without double processing:

```java
@Query(nativeQuery = true, value = """
    UPDATE schedule_run
    SET status = 'RUNNING', claimed_by = :worker,
        heartbeat_at = now(), started_at = now()
    WHERE id = (
        SELECT id FROM schedule_run
        WHERE status = 'QUEUED'
        ORDER BY created_at
        FOR UPDATE SKIP LOCKED
        LIMIT 1)
    RETURNING id
    """)
Optional<UUID> claimNext(@Param("worker") String workerId);
```

## Tests

Property-based tests assert that for any generated trip set, every trip appears in exactly one block or in the uncovered list, that consecutive trips within a block satisfy layover plus deadhead, and that EV blocks never exceed usable range.

The greedy PVR is compared with the matching lower bound on the S dataset and the gap is recorded.

A second run for the same depot and date, while one is queued or running, returns 409.

Killing a worker mid-run leads the reaper to mark the run failed, with no partial schedule rows left behind.

## Exit criteria

```text
The S dataset produces blocks with 100% trip coverage, or
explained uncovered trips, in under 5 s.

The asynchronous run lifecycle works end to end through the API.
```

---

# Phase 6 — Constraint Engine and Linked Duties

## Goal

A configurable constraint engine, and a linked duty builder that produces legal duties where the crew stays with one bus.

## Tasks

1. A typed `RuleSet` record with Bean Validation, stored as JSONB and resolved by effective date and depot, plus its endpoints.
2. The `Constraint<T>` interface and its catalogue: `MaxWorkPerDuty`, `MaxContinuousWork`, `MinBreak`, `MaxSpreadOver`, `MinPaidDuty` as soft, and the rest.
3. An incremental evaluation API, `DutyAccumulator`, for fast feasibility checks during construction.
4. `ReliefOpportunityFinder`.
5. `LinkedDutyBuilder`, including split linked duties around mid-day depot parking.
6. Duty metrics: sign-on and sign-off, platform, paid, breaks, spread-over and overtime, plus duty type classification.
7. Handover records at cut points.
8. Migrations for `piece_of_work`, `duty`, `duty_piece` and `handover`.
9. Duty, handover and validation endpoints.

## Key code

```java
public interface Constraint<T> {
    String code();
    Severity severity();
    List<Violation> check(T subject, ValidationContext ctx);
}

public final class MaxContinuousWork implements Constraint<DutyView> {

    @Override public String code()       { return "CONTINUOUS_WORK_EXCEEDED"; }
    @Override public Severity severity() { return Severity.HARD; }

    @Override
    public List<Violation> check(DutyView duty, ValidationContext ctx) {
        int limit = ctx.rules().maxContinuousWorkMin() * 60;
        int minBreak = ctx.rules().minBreakMin() * 60;
        int continuous = 0;

        for (DutySegment seg : duty.segments()) {    // WORK and GAP, in time order
            if (seg.isWork()) {
                continuous += seg.durationSec();
                if (continuous > limit) {
                    return List.of(Violation.hard(code(), duty.ref(),
                        "Continuous work %d min exceeds %d min without a %d-min break at %s"
                            .formatted(continuous / 60, limit / 60,
                                       minBreak / 60, seg.startLabel())));
                }
            } else if (seg.durationSec() >= minBreak) {
                continuous = 0;       // only a qualifying break resets the counter
            }
        }
        return List.of();
    }
}
```

```java
public final class LinkedDutyBuilder implements DutyBuilder {

    @Override
    public CrewSchedule build(VehicleSchedule vs, DepotContext ctx, RuleSet rules) {
        var duties = new ArrayList<DutyDraft>();
        var violations = new ArrayList<Violation>();

        for (Block block : vs.blocks()) {
            List<ReliefOpportunity> reliefs = reliefFinder.find(block, ctx, rules);
            int cursor = 0;

            while (cursor < reliefs.size() - 1) {
                OptionalInt cut = bestCut(block, reliefs, cursor, rules);

                if (cut.isEmpty()) {
                    int next = cursor + 1;
                    DutyDraft infeasible = DutyDraft.linked(
                            block, reliefs.get(cursor), reliefs.get(next), rules);
                    duties.add(infeasible);
                    violations.add(Violation.hard("NO_FEASIBLE_RELIEF", infeasible.ref(),
                            "No legal relief between " + reliefs.get(cursor).label()
                                    + " and " + reliefs.get(next).label()));
                    cursor = next;
                } else {
                    duties.add(DutyDraft.linked(block, reliefs.get(cursor),
                                                reliefs.get(cut.getAsInt()), rules));
                    cursor = cut.getAsInt();
                }
            }
        }
        return CrewSchedule.of(duties, handoversFrom(duties), violations);
    }

    /** Checks every later relief, because the constraints are not monotone,
        and picks the cut closest to target work, penalising a remainder
        shorter than the minimum paid duty. */
    private OptionalInt bestCut(Block block, List<ReliefOpportunity> reliefs,
                                int from, RuleSet rules) { ... }
}
```

## Tests

Unit tests per constraint, including boundaries. Exactly at the limit passes, and one second over fails.

A 17-hour block produces two or three duties, each legal, with handovers at relief points.

A block containing a 6-hour stretch with no relief point produces a `NO_FEASIBLE_RELIEF` conflict and is not silently accepted.

Changing `maxContinuousWorkMin` in the rule set changes the output with no code change. This is the test that proves rules really are data.

## Exit criteria

```text
The S dataset in linked mode produces duties with zero hard
violations, apart from explained NO_FEASIBLE_RELIEF conflicts.

Duty metrics match hand-computed fixtures.
```

---

# Phase 7 — Unlinked Duties and Handovers

## Goal

Combine pieces of work across buses into efficient legal duties with feasible handovers, then improve them with local search.

## Tasks

1. `PieceCutter`, using dynamic programming per block over relief opportunities, with piece length constrained between the minimum piece and the maximum continuous work.
2. Relief-point transfer times, whether walking or staff shuttle, plus depot sign-on and sign-off travel.
3. `UnlinkedDutyBuilder` greedy construction.
4. The handover feasibility constraint, plus maximum pieces and maximum bus changeovers as soft constraints.
5. `LocalSearchImprover` with its five moves, a seeded RNG, a time budget, and a cost function whose weights come from the rule set.
6. Determinism: stable ordering, the seed persisted on the run, and the output hash included in run metrics.
7. Run progress events carrying phase, iteration and best cost, streamed over SSE.

## Key code

```java
public final class LocalSearchImprover {

    public CrewSchedule improve(CrewSchedule start, SearchContext ctx) {
        var rng = new SplittableRandom(ctx.seed());
        var current = start.mutableCopy();
        double currentCost = ctx.cost().of(current);
        int sinceImprovement = 0;
        long deadline = ctx.clock().millis() + ctx.timeBudgetMs();

        while (ctx.clock().millis() < deadline
                && sinceImprovement < ctx.maxNonImproving()) {

            Move move = ctx.moves().pick(rng).propose(current, rng);
            if (move == null || !move.isHardFeasible(current, ctx.constraints())) {
                sinceImprovement++;
                continue;
            }

            // incremental: touches only the affected duties
            double delta = move.costDelta(current, ctx.cost());
            if (delta < 0) {
                move.apply(current);
                currentCost += delta;
                sinceImprovement = 0;
                ctx.progress().report(currentCost);
            } else {
                sinceImprovement++;
            }
        }
        return current.freeze();
    }
}
```

## Tests

Property-based tests assert that every piece belongs to exactly one duty, that every duty passes all hard constraints, that each handover gap is at least transfer plus buffer, and that the union of pieces equals the union of block work, so nothing is lost or duplicated.

Determinism is checked by running the same input and seed ten times and comparing output hashes.

Comparison: on the S and M datasets, unlinked mode needs fewer duties and less paid idle time than linked mode, and the numbers go into the evaluation report.

Local search never increases cost and never introduces a hard violation.

## Exit criteria

```text
The M dataset in unlinked mode completes per depot in under 60 s,
including a 30 s search budget.

Handovers are listed through the handovers endpoint with relief
point and times.
```

---

# Phase 8 — Crew Assignment, Conflicts, Overrides and Publish

## Goal

Assign duties to named crew legally and fairly, explain what cannot be staffed, allow safe manual overrides, and publish atomically.

## Tasks

1. Migration for `duty_assignment` with the exclusion constraint on published rows.
2. `CrewHistoryLoader`, loading rolling 7-day actual and planned assignments, plus 28 days for fairness.
3. `EligibilityFilter` with reason codes, `FairnessScorer`, `MrvCrewAssigner` and the standby pool.
4. Conflict persistence with aggregated rejection reasons.
5. `ValidationService`, performing a full re-check of the whole schedule and producing `VALIDATED` only when there are zero hard conflicts.
6. Manual override through `PATCH /duty-assignments/{id}` with `If-Match`: re-validate the affected crew's window, reject hard violations outright, allow a soft override only for MANAGER or ADMIN with a reason, and audit it.
7. `PublishService`: idempotent, in one transaction, superseding the old version, publishing the new one and flipping assignment status, relying on the database constraints as the final guard.
8. `RevalidationJob`, re-checking future published schedules when a bus goes to maintenance or breakdown, leave is approved or a licence expires.
9. Crew duty history, override, conflict and publish endpoints.

## Key code

```java
public final class MrvCrewAssigner implements CrewAssigner {

    @Override
    public Roster assign(CrewSchedule cs, CrewPool pool,
                         CrewHistory history, RuleSet rules) {
        var ctx = new AssignmentContext(pool, history, rules);
        var slots = new ArrayList<>(cs.duties().stream()
                .flatMap(d -> d.requiredRoles().stream().map(role -> new Slot(d, role)))
                .toList());

        int booked = 0;
        while (!slots.isEmpty()) {
            if (booked % 50 == 0) {           // refresh the MRV ordering periodically
                slots.sort(Comparator.comparingInt(ctx::eligibleCount)
                        .thenComparingInt(s -> s.duty().signOnSec())
                        .thenComparing(s -> s.duty().ref()));
            }
            Slot slot = slots.removeFirst();

            // candidates plus a rejection reason histogram
            Eligibility e = ctx.evaluate(slot);
            e.candidates().stream()
                .min(Comparator.comparingDouble(
                            (CrewMember c) -> fairness.score(c, slot, ctx))
                        .thenComparing(CrewMember::employeeCode))
                .ifPresentOrElse(
                        crew -> ctx.book(crew, slot),
                        () -> ctx.unassigned(slot, e.rejectionHistogram()));
            booked++;
        }
        return ctx.toRoster();
    }
}
```

```java
@Transactional
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public PublishResult publish(long scheduleId, String idempotencyKey) {
    Schedule s = schedules.lockById(scheduleId);              // SELECT ... FOR UPDATE

    if (s.isPublished()) return PublishResult.alreadyPublished(s);   // idempotent
    if (!depotAccess.canAccess(s.depotId())) throw new NotFoundException();
    if (conflicts.countOpenHard(scheduleId) > 0)
        throw new PublishBlockedException(scheduleId);

    schedules.findPublished(s.depotId(), s.serviceDate()).ifPresent(old -> {
        assignments.setScheduleStatus(old.id(), "SUPERSEDED");
        old.supersede();
    });

    // the exclusion constraints fire here
    assignments.setScheduleStatus(s.id(), "PUBLISHED");
    s.publish(currentUser(), clock.instant());
    events.publish(new SchedulePublished(s.id()));            // audit + dashboard refresh
    return PublishResult.published(s);
}
```

An exclusion violation raised by PostgreSQL, SQLSTATE `23P01`, is translated into 409 Conflict naming the conflicting crew member or bus and the overlapping time range.

## Tests

An eligibility table test for every reason code.

Rest across midnight: a duty ending at 01:30 followed by one starting at 09:00 the same calendar day violates a 10-hour minimum rest.

Weekly limits using history from the previous week.

Two schedulers overriding the same assignment concurrently: one succeeds and the other gets 412 or 409.

Publishing with open hard conflicts returns 422. Publishing twice with the same key returns the same result.

Forcing an overlapping published assignment through raw SQL is rejected by the exclusion constraint, which proves the guard actually exists rather than being assumed.

Revalidation: marking a bus as broken down creates a `BUS_UNAVAILABLE` conflict on the published schedule.

## Exit criteria

```text
The L dataset, 5,000+ buses, is scheduled across all depots with
published schedules containing zero hard violations.

Every unassigned duty carries a reason histogram.

The override and publish flows are fully audited.
```

---

# Phase 9 — Reporting, Dashboard and Audit

## Goal

Managers and schedulers get timely KPIs and an operations snapshot. Auditors get a queryable trail.

## Tasks

1. Materialized views `mv_fleet_utilization_daily`, `mv_crew_hours_weekly` and `mv_schedule_kpis`, refreshed on publish with a debounce, and nightly.
2. Report endpoints with filters, pagination and streamed CSV export through `Accept: text/csv`.
3. The today dashboard: buses out now, unassigned blocks, unassigned duties, open conflicts by type, and unavailable buses. It reads live tables with a 30-second cache.
4. SSE for run progress, and optionally a dashboard push on publish or conflict events.
5. The audit log endpoint with keyset pagination and filters on actor, entity, action and time range.
6. PII masking by role in crew responses, partially masking phone, address and licence number for non-managers.

## Key SQL

```sql
CREATE MATERIALIZED VIEW mv_fleet_utilization_daily AS
SELECT s.depot_id,
       s.service_date,
       COUNT(DISTINCT vb.id)                                        AS blocks,
       SUM(vb.service_km)                                           AS service_km,
       SUM(vb.dead_km)                                              AS dead_km,
       SUM(vb.dead_km) / NULLIF(SUM(vb.service_km + vb.dead_km), 0) AS dead_km_ratio,
       SUM(e.trip_sec)::numeric
         / NULLIF(SUM(vb.pull_in_sec - vb.pull_out_sec), 0)         AS in_service_ratio
FROM schedule s
JOIN vehicle_block vb ON vb.schedule_id = s.id
JOIN LATERAL (
     SELECT COALESCE(SUM(be.end_sec - be.start_sec), 0) AS trip_sec
     FROM block_event be
     WHERE be.block_id = vb.id AND be.type = 'TRIP') e ON true
WHERE s.status = 'PUBLISHED'
GROUP BY s.depot_id, s.service_date;

-- required for REFRESH MATERIALIZED VIEW CONCURRENTLY
CREATE UNIQUE INDEX ON mv_fleet_utilization_daily (depot_id, service_date);
```

Peak vehicle requirement, meaning peak concurrent blocks, is computed in Java from block intervals with a sweep line, or in SQL using `generate_series` over 5-minute buckets.

## Exit criteria

```text
Report figures match an independent computation from the raw
tables on the S dataset, and this is tested.

Reports include published schedules only, with superseded
versions excluded.

The audit completeness test passes: every write endpoint
produces exactly one audit record.
```

---

# Phase 10 — Hardening and Deployment

## Goal

Production-ready performance, security, observability and packaging.

## Performance tasks

1. Run k6 or Gatling load tests using the scenario mix defined in the evaluation document.
2. Review `EXPLAIN (ANALYZE, BUFFERS)` output for the top 20 queries, add or adjust indexes, and check for N+1 problems using Hibernate statistics.
3. Size the HikariCP pool and the bounded engine executor.
4. Run the full-fleet scheduling benchmark on the L dataset with parallel depot runs.

## Security tasks

1. Add OWASP Dependency-Check or equivalent to the build, and run an OWASP ZAP API scan against the OpenAPI spec.
2. Verify security headers, CORS configuration and actuator exposure. Scan the tree for secrets.
3. Set up least-privilege database roles.

```text
app_rw     DML only
migrator   DDL, used by Flyway
           UPDATE and DELETE revoked on audit_log
```

## Observability tasks

1. Add custom metrics.
2. Build a Grafana dashboard with alerts for run failures, p95 latency, database pool saturation and stuck runs.
3. Emit JSON logs carrying `traceId`, `userId` and `depotId`.

```text
scheduling.run.duration{mode}
scheduling.run.failures
scheduling.conflicts{type}
route.overlap.duration
api.page.size
```

## Packaging and operations tasks

1. Write a multi-stage Dockerfile using a layered jar, a non-root user and JRE 21.
2. Write production compose files or Kubernetes manifests with liveness and readiness probes and resource limits.
3. Document the backup and restore procedure, base backup plus WAL, and run a restore rehearsal.
4. Write the runbook covering a stuck run, a blocked publish, a revalidation storm, key rotation and restore.

## Dockerfile

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./mvnw -q -DskipTests package \
 && java -Djarmode=layertools -jar target/*.jar extract

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/application/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", \
            "org.springframework.boot.loader.launch.JarLauncher"]
```

Newer Spring Boot versions replace `layertools` with `-Djarmode=tools extract --layers`. Use whichever form matches the pinned Boot version.

## Exit criteria

```text
All performance targets are met, or deviations are documented
with a root cause.

No high or critical findings from the dependency and ZAP scans.

A clean deployment from the image to a fresh environment, with a
restored database backup, succeeds.
```

---

# Risk Register

## Labour-rule values differ from assumptions

Impact is high: schedules could be illegal, or needlessly over-conservative. Likelihood is high, because the values in the rule set are assumptions until someone confirms them.

Mitigation: rules are data, not code. Rule sets are reviewed with DTC HR and legal before go-live.

## Real data quality is poor

Geometry and legacy spreadsheets may both be messy, which produces wrong overlaps and failed imports. Likelihood is high.

Mitigation: the validation pipeline, dry-run imports, a data-quality report, and explicit estimated-deadhead flags so nobody mistakes an estimate for a measurement.

## Heuristic quality is insufficient

The system might need more duties or buses than necessary. Likelihood is medium.

Mitigation: lower bounds to measure the gap honestly, local search, and a pluggable solver interface so a better algorithm can be dropped in later.

## Full-fleet run time is too long

This would cause missed planning windows. Likelihood is medium.

Mitigation: depot partitioning, parallel workers and a time-bounded search.

## Scope creep into real-time operations

AVL and live tracking would delay everything. Likelihood is medium.

Mitigation: explicitly out of scope for the first version, and stated as such in the project statement.

## Spatial query performance at scale

Analysis could become slow. Likelihood is low to medium.

Mitigation: generated projected columns, GiST indexes, grid-based coverage and materialized views.

## Concurrency bugs in publish or override

These would cause double booking. Likelihood is low.

Mitigation: database exclusion constraints, optimistic locking and dedicated concurrency tests.
