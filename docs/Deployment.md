# Deployment, backup and restore

How the service is packaged, deployed, backed up and restored. Companion to [Runbook.md](Runbook.md), which
covers what to do when something is wrong.

---

## 1. The image

A multi-stage build. The build stage carries the JDK, Maven and the dependency cache — roughly 700 MB of things
with no business on a production host. The runtime stage carries a JRE and the application, and nothing that
could compile or download code.

```sh
docker build -t dtc-scheduling:0.1.0 .
```

Three properties worth knowing:

- **Non-root.** The container runs as uid 10001. A container escape starting as root is a host compromise, and
  nothing this application does needs privilege.
- **Layered jar.** Spring Boot orders layers by how often they change, so a code-only redeploy ships a few
  hundred kilobytes rather than the whole fat jar.
- **Container-aware heap.** `MaxRAMPercentage=75` rather than a fixed `-Xmx`, which either wastes the memory
  limit or exceeds it and gets the container killed.

Tests do not run in the image build. They need a Docker daemon for Testcontainers, and starting containers
inside an image build is a problem nobody should have to debug. CI runs `./mvnw clean verify` separately.

---

## 2. Configuration

Everything secret comes from the environment. `docker-compose.prod.yml` declares the required ones with
`${VAR:?message}`, so a missing secret fails at startup rather than silently running with a development value.

| Variable | Required | Notes |
|---|---|---|
| `DB_USER`, `DB_PASSWORD` | yes | The database superuser, for container initialisation only |
| `APP_DB_USER`, `APP_DB_PASSWORD` | yes | The `app_rw` role: DML only |
| `MIGRATOR_DB_USER`, `MIGRATOR_DB_PASSWORD` | yes | The `migrator` role: DDL, used by Flyway |
| `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY` | yes | PEM. Unset means an ephemeral pair per restart |
| `CORS_ALLOWED_ORIGINS` | yes | No wildcard default; the API is credentialed |
| `BOOTSTRAP_PASSWORD` | first deploy | Creates the initial admin. Remove afterwards |
| `GRAFANA_PASSWORD` | yes | |

```sh
docker compose -f docker-compose.prod.yml up -d
```

The application waits for the database health check, runs eleven Flyway migrations, then turns readiness green.
`start_period` is ninety seconds for that reason.

---

## 3. First deployment

1. Start the database alone and create the roles with login rights. `V11` creates them `NOLOGIN` with no
   password, because a password in a repository is not a password.

   ```sh
   docker compose -f docker-compose.prod.yml up -d db
   psql -h localhost -U "$DB_USER" -d dtc_transit -c \
     "ALTER ROLE app_rw LOGIN PASSWORD '$APP_DB_PASSWORD';"
   psql -h localhost -U "$DB_USER" -d dtc_transit -c \
     "ALTER ROLE migrator LOGIN PASSWORD '$MIGRATOR_DB_PASSWORD';"
   ```

   On a completely fresh database the roles do not exist until migrations have run, so start the application
   once with `SPRING_DATASOURCE_USERNAME` set to the superuser, then switch to `app_rw` and restart.

2. Start the application with `BOOTSTRAP_PASSWORD` set. It creates the initial admin and logs that it did.

3. Log in, create real accounts, then remove `BOOTSTRAP_PASSWORD` and restart.

4. Verify:

   ```sh
   curl -s localhost:8080/actuator/health/readiness
   curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/actuator/prometheus | head
   ```

---

## 4. Backup

Base backup plus WAL archiving. A base backup alone restores only to the moment it was taken; WAL is what makes
point-in-time recovery possible, which is what you want when the problem is a bad bulk update at 14:05 rather
than a dead disk.

`docker-compose.prod.yml` enables `archive_mode=on` with the archive on its own volume.

**Nightly base backup:**

```sh
docker compose -f docker-compose.prod.yml exec -T db \
  pg_basebackup -U "$DB_USER" -D - -Ft -z -Xfetch \
  > "backups/base-$(date +%F).tar.gz"
```

**Retention.** Keep base backups for 30 days and WAL covering the same window. WAL older than the oldest base
backup is useless; WAL newer than it is what recovery replays.

Back up the JWT key pair too, in the secret store rather than with the database. Restoring a database without
the signing key leaves every refresh token unusable.

---

## 5. Restore

Rehearse this before needing it. An untested restore procedure is a hope.

1. Stop the application. A restore with writers attached produces an inconsistent result.

   ```sh
   docker compose -f docker-compose.prod.yml stop app
   ```

2. Restore the base backup into an empty data directory.

   ```sh
   docker compose -f docker-compose.prod.yml down db
   docker volume rm dtc_db-data
   docker compose -f docker-compose.prod.yml up -d db
   docker compose -f docker-compose.prod.yml exec -T db \
     tar -xzf - -C /var/lib/postgresql/data < backups/base-2026-10-01.tar.gz
   ```

3. For point-in-time recovery, add a `recovery.signal` file and set the target before starting:

   ```conf
   restore_command = 'cp /wal/%f %p'
   recovery_target_time = '2026-10-01 14:00:00+05:30'
   ```

4. Start the database and wait for recovery to finish.

5. **Verify before letting the application in.** A restore that silently lost the constraints is worse than no
   restore, because the application will cheerfully write into it.

   ```sql
   -- The guards that make the data trustworthy must exist.
   SELECT conname FROM pg_constraint
   WHERE conname IN ('no_crew_double_booking', 'no_bus_double_booking',
                     'timetable_no_overlapping_validity', 'headway_band_no_overlap');
   -- Expect four rows.

   -- No crew member double-booked on published work.
   SELECT count(*) FROM duty_assignment a
   JOIN duty_assignment b ON b.crew_member_id = a.crew_member_id AND b.id <> a.id
        AND b.work_period && a.work_period
   WHERE a.schedule_status = 'PUBLISHED' AND b.schedule_status = 'PUBLISHED';
   -- Expect 0.

   -- One published schedule per depot-day.
   SELECT depot_id, service_date, count(*) FROM schedule
   WHERE status = 'PUBLISHED' GROUP BY 1, 2 HAVING count(*) > 1;
   -- Expect no rows.

   -- Flyway agrees about what has been applied.
   SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
   ```

6. Start the application and check readiness.

---

## 6. Monitoring

Prometheus scrapes `/actuator/prometheus`, which is ADMIN-only, so the scrape carries a bearer token from
`PROMETHEUS_TOKEN_FILE`. In a cluster the management port would be bound to an internal interface instead and
the token would be unnecessary — that is the one Phase 11 item left to the deployment environment.

Application-specific metrics:

```text
scheduling.run.duration{mode}   how long a depot-day takes
scheduling.run.failures{reason} failures, with a bounded reason tag
scheduling.conflicts{type}      the most useful operational signal
scheduling.runs.queued          backlog
scheduling.runs.running         in flight
route.overlap.duration          the most expensive spatial query
api.page.size                   what clients actually ask for
```

Tag cardinality is kept low deliberately. A tag per depot would be 45 series per metric and a tag per run id
would be unbounded — which is how a monitoring system is brought down by the thing it monitors.

Alerts are in [`ops/monitoring/alerts.yml`](../ops/monitoring/alerts.yml): service down, API p95, run failures,
stuck runs, queue backlog, pool saturation and revalidation storms. Each one links to a runbook section. There
are deliberately few: an alert that fires without requiring action trains people to ignore the ones that do.

---

## 7. Security verification

| Check | How | Status |
|---|---|---|
| Dependency vulnerabilities | `./mvnw -Psecurity-scan verify` | Profile added; fails on CVSS ≥ 7. Needs an `NVD_API_KEY` and network access |
| Security headers | `AuthenticationFlowTest`, `SecurityConfig` | HSTS, frame options and content-type options asserted |
| CORS | `SecurityConfig` | No wildcard; origins required by configuration |
| Actuator exposure | `PermissionMatrixTest` | `/actuator/**` is ADMIN-only except the health probes |
| Authorization matrix | `PermissionMatrixTest` | 161 cases, and every mapped endpoint must carry a row |
| Token forgery | `TokenForgeryTest` | `alg=none` and HS256 confusion both refused |
| Secrets in the tree | Manual review | No credentials committed; every secret is an environment variable |
| API scan (ZAP) | Not run | Needs a deployed instance and an authenticated scan profile. See below |

**ZAP is not run here.** It needs a running instance and an authenticated scan session, neither of which exists
in this environment. The OpenAPI document at `/v3/api-docs` is what drives it when there is somewhere to point
it:

```sh
docker run --rm -t ghcr.io/zaproxy/zaproxy:stable zap-api-scan.py \
  -t http://host.docker.internal:8080/v3/api-docs -f openapi \
  -z "-config replacer.full_list(0).replacement=Bearer $TOKEN"
```

---

## 8. Load testing

[`ops/load/k6-request-mix.js`](../ops/load/k6-request-mix.js) implements the documented request mix, with the
performance targets as k6 thresholds rather than numbers somebody reads off a graph.

```sh
k6 run -e BASE_URL=http://localhost:8080 -e USERNAME=admin -e PASSWORD=... \
  ops/load/k6-request-mix.js
```

Not in CI: it needs a populated database and a deployed instance, and a load test that runs on every commit is a
load test somebody disables.

Measured in this environment, from the scheduling benchmark rather than from k6:

```text
S dataset, one depot-day   1,200 trips   88 blocks   249 ms   (target 5 s)
M dataset, one depot-day   1,200 trips   88 blocks   146 ms
Dead running                             19.3%
```

The full-fleet L benchmark with parallel depot runs was **not** executed. See the deviations in the batch report.
