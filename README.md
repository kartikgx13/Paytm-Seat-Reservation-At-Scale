# Seat Reservation at Scale

A JSON HTTP service that sells assigned seats for a show and stays correct under an on-sale stampede:
no seat is ever sold twice, no user exceeds their per-show limit, and a retried request never reserves twice.
Java 21 + Spring Boot 3.5 + PostgreSQL 16; every correctness decision is made atomically inside Postgres.

| | |
|---|---|
| **Repository** | https://github.com/kartikgx13/Paytm-Seat-Reservation-At-Scale |
| **Live URL** | https://seat-reservation-n32o.onrender.com |
| Liveness | `GET /actuator/health/liveness` |
| Readiness (checks DB, fails closed) | `GET /actuator/health/readiness` |
| Prometheus metrics | `GET /actuator/prometheus` |
| Design write-up | [WRITEUP.md](WRITEUP.md) |

> Hosted on Render's **free tier (0.1 CPU, 512 MB)**, which sleeps a service after 15 min without inbound traffic.
> To keep it warm, the app pings its own public URL every 10 min while running (`KeepWarmPinger`, using Render's
> `RENDER_EXTERNAL_URL`), and a [GitHub Actions heartbeat](.github/workflows/keep-warm.yml) hits readiness every 5 min
> (it also wakes the instance if it ever sleeps, and a failed run flags an outage). If you ever do catch a cold start
> (~1-2 min), `./burst.sh` waits for readiness before firing. On 0.1 CPU a 20k burst is *correct* but *slow* (p50 ~8 s, see below). That's CPU time, not
> correctness: the same burst at 0.5 CPU finishes in ~25 s with p99 ~2 s.

---

## Run the burst (one command)

```bash
ADMIN_API_KEY=<admin key> ./burst.sh https://seat-reservation-n32o.onrender.com
```

Needs Node 18+ (no `npm install`), or falls back to Docker. Against the local stack the admin key defaults to `dev-admin-key`:

```bash
./burst.sh http://localhost:8080
```

What it does, against a freshly created show:

1. **Hot-seat storm**: 500 distinct users x 5 hot seats, all fired together. Expects exactly one `201` per seat, everyone else `409 seat_taken`.
2. **Stampede**: 5,000 reservations (1-2 seats, 80% aimed at the front rows), ~10% re-sent concurrently with the same idempotency key.
3. **Idempotency**: one key x20 in parallel gives one `201` plus nineteen `200` replays of the same reservation; the same key with different seats gives `409 idempotency_key_reused`.
4. **Per-user limit**: one user fires 10 parallel requests on a limit-4 show. Expects at most 4 seats.
5. **Identity**: a spoofed `user_id` in the body is ignored, cancelling someone else's reservation returns 404, and a tampered token returns 401.
6. **Reconciliation**: `available + held + confirmed == total` (sampled mid-burst and at the end), the seats granted to the client equal the seats the server says are confirmed, and Prometheus counters and gauges match the API.

It prints the outcome distribution (confirmed / declined-by-reason / 5xx / network errors, latency percentiles) and exits non-zero on any violation.
Tunables: `--users 2000 --seats 1000 --hot-seats 5 --hot-users 500 --stampede 5000 --concurrency 800`.

**Live run** against https://seat-reservation-n32o.onrender.com (free tier) with ~20k requests
(`--users 4000 --seats 2000 --hot-seats 10 --hot-users 1000 --stampede 9000 --concurrency 500`):

```
[1] Hot-seat storm: 1000 users x 10 seats (A1 ... A10) = 10000 requests
  PASS  seat A1: 1 x 201, 999 x 409, 0 x 5xx, 0 network errors
  ... (same for A2 - A10)
[6] Reconciliation
  PASS  final: available 591 + held 0 + confirmed 1409 = 2000 (total 2000)
  PASS  mid-burst: invariant held in 44/44 samples
  PASS  seats granted to us (1409) == seats confirmed on server (1409)
  PASS  metrics: reservations_confirmed_total delta 1092 == 201s observed 1092
  PASS  metrics: seats_available=591, seats_confirmed=1409 match GET /shows (591, 1409)
=== Outcome distribution ===
  requests                          19892
  confirmed (201)                   1092
  idempotent replay (200)           135
  declined: seat_taken              18653
  declined: per_user_limit          9
  5xx                               0
  latency ms                        p50=7733 p95=10601 p99=14821
RESULT: PASS
```

Local Docker stack, same shape (`--concurrency 1500`):

```
[1] Hot-seat storm: 1000 users x 10 seats (A1 ... A10) = 10000 requests
  PASS  seat A1: 1 x 201, 999 x 409, 0 x 5xx, 0 network errors
  ... (same for A2 - A10)
[6] Reconciliation
  PASS  final: available 612 + held 0 + confirmed 1388 = 2000 (total 2000)
  PASS  seats granted to us (1388) == seats confirmed on server (1388)
  PASS  metrics: reservations_confirmed_total delta 1086 == 201s observed 1086
  PASS  metrics: seats_available=612, seats_confirmed=1388 match GET /shows (612, 1388)
=== Outcome distribution ===
  requests                          19929
  confirmed (201)                   1086
  idempotent replay (200)           163
  declined: seat_taken              18668
  declined: per_user_limit          9
  5xx                               0
RESULT: PASS
```

---

## Run locally

```bash
docker compose up --build        # app :8080, Postgres :5432, Prometheus :9090, Grafana :3000
./burst.sh http://localhost:8080
```

Grafana (http://localhost:3000, no login) has a provisioned **Seat Reservation - On-sale** dashboard: outcomes/s by reason,
HTTP status mix, seats per show, reconciliation drift, 5xx, p50/p99 latency, DB pool. Prometheus loads the alert rules
in [`observability/alerts.yml`](observability/alerts.yml).

Without Docker for the app: start Postgres, then `./mvnw spring-boot:run` (JDK 21). Use `SPRING_PROFILES_ACTIVE=plain` for human-readable logs.

### Tests

```bash
./mvnw test     # needs a Docker daemon (Testcontainers starts Postgres)
```

With Colima: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

The integration tests drive the real HTTP stack with latch-gated parallel requests: a 300-user hot-seat storm, 10 parallel requests
against a limit of 4, 40 parallel same-key retries, 200 overlapping multi-seat requests in random order (deadlock and double-sell check),
cancel racing 100 re-bookers, owner-only cancel / no resurrection, token-only identity, all-or-nothing partials.

---

## API

All bodies are JSON with snake_case fields. Money is integer paise. Errors look like `{"error": "<code>", "message": "...", ...details}`.

### Get a token (demo issuer)

```bash
curl -X POST $BASE/auth/token -H 'content-type: application/json' -d '{"user_id":"alice"}'
# {"token":"eyJ...","user_id":"alice","expires_at":"..."}
```

The reservation API only trusts the token's `sub` claim (HS256 JWT). This endpoint stands in for an external identity provider
so testers can mint users.

### Create a show (admin)

```bash
curl -X POST $BASE/shows -H "X-Admin-Key: $ADMIN_API_KEY" -H 'content-type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000,"per_user_limit":4}'
```

`201` with the show, `counts`, and every seat `available`. `per_user_limit` is optional (default 4). Fractional `price_paise` is rejected (400).

### Reserve

```bash
curl -X POST $BASE/shows/$SHOW/reserve -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: 7f1c...' -H 'content-type: application/json' -d '{"seats":["A12"]}'
```

The key may be sent as the `Idempotency-Key` header or as the `idempotency_key` body field (if both are sent they must match).

| Outcome | Status | `error` |
|---|---|---|
| Reserved | **201** | |
| Same key, same request (retry) | **200** + `Idempotent-Replayed: true`, original reservation | |
| Any requested seat already taken | **409** | `seat_taken` (+ `unavailable_seats`) |
| Would exceed per-user limit | **409** | `per_user_limit` |
| Same key, different seats/show | **409** | `idempotency_key_reused` |
| Lock wait exceeded under extreme contention (retryable, nothing written) | **409** | `contention` |
| Overloaded: no execution slot within 30 s, or DB pool exhausted (retryable) | **429** + `Retry-After` | `too_busy` |
| Seat label not in show / bad input | **400** | `unknown_seat` / `invalid_request` |
| Missing or invalid token | **401** | `unauthorized` |

**Multi-seat requests are all-or-nothing.** If any requested seat is unavailable nothing is reserved and the 409 lists the unavailable seats.
Reservations are **confirmed immediately** (there is no payment step in scope); seats are returned via cancel.

### Cancel (owner only)

```bash
curl -X POST $BASE/reservations/$RID/cancel -H "Authorization: Bearer $TOKEN"
```

`200` with `status: "cancelled"`. Cancelling again is a no-op `200`. Another user's reservation gives `404` (indistinguishable from
a missing one). Only seats still owned by that reservation are released.

### Read

- `GET /shows/{id}`: per-seat status and `counts {total_seats, available, held, confirmed}` (public).
- `GET /reservations/{id}`: owner only.
- `GET /me/reservations?show_id=...`: the caller's reservations for a show.

---

## Observability

**Metrics** (`/actuator/prometheus`):

| Metric | Type | Meaning |
|---|---|---|
| `reservations_confirmed_total` | counter | one per `201` |
| `reservations_seats_confirmed_total` | counter | seats moved to confirmed |
| `reservations_declined_total{reason}` | counter | `seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_key_reused`, `contention`, `too_busy`, `unknown_seat`, ... |
| `reservations_cancelled_total`, `reservations_seats_released_total` | counter | cancellations |
| `seats_available/held/confirmed/total{show_id,show_name}` | gauge | read from the DB every 2 s (25 most recent shows) |
| `seats_reconciliation_drift{show_id}` | gauge | `total - (available+held+confirmed)`, must be 0 |
| `reservations_latency_seconds{outcome}` | histogram | reserve latency by outcome |
| `http_server_requests_seconds{uri,status}` | histogram | standard HTTP metrics (5xx visible here) |
| `hikaricp_connections_*` | gauge | DB pool active / pending |

**Logs**: one JSON object per line on stdout. Every line for a request carries `requestId` (taken from an inbound `X-Request-Id`
or generated, and echoed in the response header) and `userId`. Each reserve writes an outcome line
(`event=reserve outcome=confirmed|declined reason=... showId seats latencyMs`) plus an access line (`method path status durationMs`).
On Render: service, then **Logs** (live tail, searchable).

---

## Deploy (Render)

1. Push this repo to GitHub.
2. Render dashboard: **New**, then **Blueprint**, pick the repo. [`render.yaml`](render.yaml) creates a free Postgres and a Docker web service
   with `healthCheckPath: /actuator/health/liveness` (Render restarts on this check, so it must not depend on the
   DB; readiness still checks the DB and fails closed).
3. Set `ADMIN_API_KEY` when prompted (`JWT_SECRET` is generated, `DATABASE_URL` is wired from the database).
4. Wait for the deploy to report healthy, then `ADMIN_API_KEY=... ./burst.sh https://<service>.onrender.com`.

Configuration (env vars): `DATABASE_URL` (or `JDBC_DATABASE_URL`/`DB_USER`/`DB_PASSWORD`), `JWT_SECRET`, `ADMIN_API_KEY`,
`DB_POOL_SIZE` (20), `LOCK_TIMEOUT_MS` (5000), `DEFAULT_PER_USER_LIMIT` (4), `ADMISSION_MAX_CONCURRENT` (64; 24 on Render),
`ADMISSION_MAX_WAIT_MS` (30000), `JAVA_OPTS`, `PORT` (8080).

## Layout

```
src/main/java/com/paytm/seats/
  reservation/   ReservationService (the transaction), ReservationRepository (all locking SQL), controller
  show/          create / read shows, snapshot-consistent counts
  auth/          JWT verify/issue, AuthInterceptor (token-only identity, admin key)
  observability/ metrics, DB-sourced seat gauges, request-id filter, fail-closed readiness
src/main/resources/db/migration/V1__init.sql   schema + constraints
burst/burst.mjs, burst.sh                       load / correctness burst
observability/                                  Prometheus config, alert rules, Grafana dashboard
```
