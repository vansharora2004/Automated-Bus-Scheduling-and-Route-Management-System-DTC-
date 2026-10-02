# Runbook

Operational procedures for the DTC scheduling service. Written for whoever is on call, which may not be whoever
built it.

Every procedure below assumes shell access to the application host and a `psql` session as the `migrator` role.
The application connects as `app_rw`, which cannot run the DDL or the audit-log statements some of these need.

---

## Service down

Alert: `ServiceDown`. Prometheus has not scraped the instance for two minutes.

1. Check the container is running.

   ```sh
   docker compose -f docker-compose.prod.yml ps
   docker compose -f docker-compose.prod.yml logs --tail=200 app
   ```

2. Check readiness directly. Liveness answering while readiness fails means the application is up but the
   database is not reachable.

   ```sh
   curl -s localhost:8080/actuator/health/liveness
   curl -s localhost:8080/actuator/health/readiness
   ```

3. If the JVM exited with an out-of-memory error, the container is configured to exit rather than limp. Check
   the memory limit against actual use before raising it; a run over the L dataset holds a depot-day in memory,
   not the whole network.

4. If Flyway failed at startup, the log says which migration. **Do not** edit a migration that has already run
   anywhere. Add a corrective one.

---

## Stuck or failing run

Alerts: `StuckSchedulingRun`, `SchedulingRunFailures`, `RunQueueBacklog`.

A run should take seconds per depot-day. Anything still `RUNNING` after fifteen minutes is stuck or its worker
is gone.

1. Look at the queue.

   ```sql
   SELECT id, depot_id, service_date, status, progress, claimed_by,
          now() - heartbeat_at AS since_heartbeat, error
   FROM schedule_run
   WHERE status IN ('QUEUED', 'RUNNING')
   ORDER BY created_at;
   ```

2. **Heartbeat older than two minutes and still `RUNNING`** means the reaper is not running. The reaper fails
   such runs automatically every thirty seconds; if it has not, check `app.scheduling.worker.enabled` is not
   `false` in this environment, then restart the instance.

3. **To release a depot-day by hand** — only when the reaper is confirmed dead and a scheduler is blocked:

   ```sql
   UPDATE schedule_run
   SET status = 'FAILED', finished_at = now(), error = 'MANUALLY_RELEASED'
   WHERE id = '<run-id>' AND status = 'RUNNING';
   ```

   There is nothing else to clean up. A run writes its schedule in one transaction, so a dead worker committed
   nothing and left no partial rows.

4. **Repeated failures** — the `reason` tag on `scheduling_run_failures_total` narrows it:

   | Reason | Meaning | Action |
   |---|---|---|
   | `no_fleet` | The depot has no usable buses on that date | Check `bus.status` and `bus_unavailability` |
   | `rule_set` | No rule set is effective on that date | Create one; see below |
   | `worker_lost` | Workers are being killed mid-run | Check memory limits and pod evictions |
   | `other` | Read `schedule_run.error` | — |

5. **Backlog with nothing running** means no worker is polling. One instance must have
   `app.scheduling.worker.enabled=true`.

---

## Blocked publish

A scheduler reports that publishing returns 422 or 409.

1. **422 `SCHEDULE_HAS_BLOCKING_CONFLICTS`** — unresolved hard conflicts. List them:

   ```sql
   SELECT type, count(*), min(message) AS example
   FROM conflict
   WHERE schedule_id = <id> AND severity = 'HARD' AND NOT resolved
   GROUP BY type ORDER BY count(*) DESC;
   ```

   `UNASSIGNED_DUTY` carries a reason histogram in `details`. That histogram is the fix: nine drivers short of
   rest is a different problem from nine on leave.

   A conflict can be accepted deliberately through
   `POST /api/v1/schedules/{id}/conflicts/{conflictId}/resolve` with a reason. It is audited against whoever
   gave it. **Never** resolve one with an UPDATE: the reason and the actor are the point.

2. **422 `SCHEDULE_NOT_VALIDATED`** — call `POST /api/v1/schedules/{id}/validate` first.

3. **409 `PUBLISH_WOULD_DOUBLE_BOOK`** — a database exclusion constraint refused the publish. Something in this
   version overlaps a *different* published schedule, almost always a block or duty running past midnight into
   the next service date. Find it:

   ```sql
   SELECT a.crew_member_id, a.starts_at, a.ends_at, d.schedule_id
   FROM duty_assignment a
   JOIN duty d ON d.id = a.duty_id
   WHERE a.schedule_status = 'PUBLISHED' AND a.status <> 'CANCELLED'
     AND a.crew_member_id IN (
       SELECT crew_member_id FROM duty_assignment x
       JOIN duty xd ON xd.id = x.duty_id
       WHERE xd.schedule_id = <id>)
   ORDER BY a.crew_member_id, a.starts_at;
   ```

   This is the constraint doing its job. Fix the roster, do not disable it.

---

## Revalidation storm

Alert: `RevalidationStorm`. Many published schedules flagged at once.

This almost always means one bulk master-data change rather than many real problems — a fleet import that set
statuses, or a licence batch that expired.

1. See the scale and the cause.

   ```sql
   SELECT s.service_date, s.depot_id, s.id
   FROM schedule s WHERE s.status = 'PUBLISHED' AND s.needs_revalidation
   ORDER BY s.service_date;
   ```

2. If the master-data change was a mistake, revert it and let the next revalidation pass clear the flags — the
   job only sets them, so reverting the cause removes the finding on the next run. The existing
   `NEEDS_REVALIDATION` conflicts stay until resolved; resolve them with a reason once the cause is gone.

3. If the change was real, each affected depot-day needs a new schedule version. Published schedules are
   immutable by design: crews were told where to be, and rewriting that silently is worse than republishing.

4. To stop the noise while investigating, set `app.scheduling.revalidation.enabled=false` and restart. Remember
   to turn it back on.

---

## Pool saturation

Alert: `ConnectionPoolSaturated`. Threads are waiting for a database connection.

1. Find the long-running transaction first. One forgotten transaction saturates a pool faster than load does.

   ```sql
   SELECT pid, now() - xact_start AS duration, state, left(query, 120) AS query
   FROM pg_stat_activity
   WHERE datname = current_database() AND xact_start IS NOT NULL
   ORDER BY xact_start;
   ```

2. A refresh of a reporting view can hold a lock if it fell back to the non-concurrent form. That fallback only
   happens when the view has never been populated, so it should be a one-off on a fresh database.

3. Only then consider pool size. It must stay below the database's `max_connections` divided by the number of
   instances, with headroom for `migrator` and for a human with `psql`.

---

## Key rotation

The RS256 key pair signs every access token. Rotating it invalidates every token in circulation.

1. Generate a new pair.

   ```sh
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
   openssl rsa -pubout -in private.pem -out public.pem
   ```

2. Set `JWT_PRIVATE_KEY` and `JWT_PUBLIC_KEY` and restart. Access tokens live fifteen minutes, so clients
   recover within that window using their refresh tokens.

3. To force every session out instead — a compromised key, or a departing administrator — bump the affected
   accounts' `token_version`. That is what the field is for, and it works without a key change or a blacklist.

   ```sql
   UPDATE app_user SET token_version = token_version + 1 WHERE username = '<username>';
   ```

   Revocation takes effect within `app.security.jwt.token-version-cache-ttl`, which is sixty seconds.

4. **Never** leave the key variables unset in production. The application then generates an ephemeral pair at
   startup, which invalidates every token on each restart and breaks a multi-instance deployment outright.

---

## Creating a rule set

A run fails with `NO_RULE_SET` when no rule set is effective on the service date. The migration seeds a global
one effective from 2000-01-01, so this only happens if that row was deleted.

Use the API rather than SQL, so the values are validated and the change is audited:

```
POST /api/v1/rule-sets          # ADMIN only
POST /api/v1/rule-sets/validate # checks consistency without storing
GET  /api/v1/rule-sets/effective?depotId=1&serviceDate=2026-06-01
```

A rule set is never edited. `PUT /api/v1/rule-sets/{id}` creates a new version with a later effective date, so
a schedule built under the old values stays explicable.

---

## Database roles

| Role | Rights | Used by |
|---|---|---|
| `app_rw` | `SELECT`, `INSERT`, `UPDATE`, `DELETE`. No DDL. No `UPDATE`/`DELETE` on `audit_log`. | The application |
| `migrator` | DDL | Flyway, and these procedures |

Created by `V11__least_privilege_roles.sql` with `NOLOGIN` and no password, because a password in a repository is
not a password. Grant login as a deployment step:

```sql
ALTER ROLE app_rw    LOGIN PASSWORD '<from the secret store>';
ALTER ROLE migrator  LOGIN PASSWORD '<from the secret store>';
```

The audit log is append-only: `UPDATE` and `DELETE` are revoked from `app_rw` and blocked by a trigger on every
partition. Retention is a partition drop as `migrator`, never a row delete.

```sql
DROP TABLE audit_log_2024_01;   -- after archiving
```
