# Write-up: Seat Reservation at Scale

## 1. The atomic decision

Every reserve runs in a single Postgres transaction (READ COMMITTED) in
[`ReservationService.doReserve`](src/main/java/com/paytm/seats/reservation/ReservationService.java), with the SQL in
[`ReservationRepository`](src/main/java/com/paytm/seats/reservation/ReservationRepository.java):

```sql
SET LOCAL lock_timeout = '5000ms';

-- 1. claim the idempotency key (unique index on (user_id, idempotency_key))
INSERT INTO reservations (...) VALUES (...) ON CONFLICT ON CONSTRAINT reservations_idem_uq DO NOTHING;

-- 2. per-user limit: check-and-increment in ONE statement
INSERT INTO user_show_quota AS q (show_id, user_id, seat_count) VALUES (:show, :user, :n)
ON CONFLICT (show_id, user_id) DO UPDATE SET seat_count = q.seat_count + EXCLUDED.seat_count
WHERE q.seat_count + EXCLUDED.seat_count <= :limit
RETURNING seat_count;                                  -- no row back means over the limit

-- 3. lock the seats, in a single global order
SELECT label, status FROM seats WHERE show_id = :show AND label = ANY(:sorted) ORDER BY label FOR UPDATE;
-- (application checks all are 'available'; otherwise throw, which rolls back steps 1 and 2)

-- 4. flip them, still guarded on state
UPDATE seats SET status='confirmed', reservation_id=:rid, user_id=:user
WHERE show_id=:show AND label = ANY(:sorted) AND status='available';
COMMIT;
```

**Why it can't double-sell.** A seat is exactly one row, `(show_id, label)` is its primary key, so there is never a
second copy. `SELECT ... FOR UPDATE` takes that row's exclusive lock. Of 500 transactions racing for A12, one gets the lock;
the other 499 queue behind it. When the winner commits, each waiter re-reads the *latest committed* row version (Postgres
re-evaluates locked rows after the wait), sees `confirmed`, and declines with `409 seat_taken`. The status check and the
write happen under the same lock in the same transaction, so there is no read-then-write window. The final `UPDATE ... AND
status='available'` (checked against the expected row count) and the table `CHECK` constraints
(`available` iff `reservation_id IS NULL`) are a second line of defence: a logic bug fails as a rollback, not a double-sell.

**Why the limit can't be exceeded.** The quota check and the increment are one `INSERT ... ON CONFLICT DO UPDATE ... WHERE`
statement. Ten parallel requests from one user serialize on that user's quota row; each sees the previous one's committed
count. If the reservation later fails (seat taken), the increment rolls back with it.

**Multi-seat and deadlock.** Seat labels are de-duplicated and sorted before anything else, and every transaction acquires
locks in the same global order: reservation row (new), then the user's quota row, then seat rows in ascending label order. Cancel
uses the same order (reservation row, quota row, seats sorted). Since every transaction climbs the same ladder, no two can
each hold a lock the other wants, so there's no cycle and no deadlock. The integration test fires 200 random overlapping 2-3
seat requests in shuffled order and asserts zero 5xx (a deadlock would surface as SQLState 40P01).

**Partial requests: all-or-nothing.** If any requested seat isn't available, the whole transaction rolls back and the 409
names the unavailable seats. Best-effort would hand people seat combinations they didn't ask for (e.g. split couples), and
all-or-nothing falls straight out of the transactional design.

**Keeping losers off the locks.** Before opening the transaction, a lock-free `SELECT` rejects requests whose seats are
*visibly* taken. It can only decline, never grant, so it cannot cause a double-sell; it means that once a hot seat is sold, the
remaining stampede is rejected without opening a transaction or waiting on row locks.

**No 5xx under contention.** A lock wait beyond `lock_timeout` (55P03), deadlock (40P01) or serialization failure maps to
`409 contention` (nothing was written; safe to retry). Hikari pool exhaustion maps to `429 too_busy` + `Retry-After`. Requests
otherwise *queue* for a DB connection (30 s connection timeout, virtual threads so waiting is cheap) rather than fail.

## 2. Idempotency

- **Where:** the `reservations` table itself. `UNIQUE (user_id, idempotency_key)` plus `request_hash = sha256(show_id, sorted seats)`.
  There's no separate cache, so the key and the reservation are written in the same transaction and can't disagree.
- **Exactly-once:** the key claim is the first write in the transaction. If two requests with the same key race, the second
  `INSERT ... ON CONFLICT DO NOTHING` *blocks on the unique index* until the first transaction ends. If the first commits, the
  second gets no row and returns the committed reservation as a replay. If the first rolled back (e.g. seat taken), the second
  proceeds as a fresh attempt. A declined attempt leaves no key behind, so a retry after a decline is re-evaluated, not stuck.
- **Replay response:** `200` with the original body and `Idempotent-Replayed: true`. `201` means "created by this call", so
  in a storm the number of 201s equals the number of reservations.
- **Same key, different body:** hash mismatch gives `409 idempotency_key_reused` (with the original reservation id).
- **Scope:** keys are per user (a key identifies one operation by that user). Keys of different users never collide, and
  user B can never replay user A's key to read A's reservation.
- **A race the tests caught:** a retry could arrive after its twin committed but before its own key lookup saw that commit.
  It then hit the lock-free pre-check, saw its *own* seats as taken, and returned `seat_taken` instead of a replay. The fix:
  before declining in the pre-check, look the key up again. The parallel same-key test (40 concurrent) reproduced it
  deterministically: 35 replays + 4 wrong declines before the fix, 39 replays after.

## 3. Holds and expiry

Reservations are **confirmed immediately** (there is no payment step in scope), and seats come back via an explicit
**owner-only cancel**:

```sql
SELECT ... FROM reservations WHERE id = :rid FOR UPDATE;          -- then check owner == token user, else 404
UPDATE user_show_quota SET seat_count = seat_count - :n ...;
SELECT ... FROM seats WHERE ... ORDER BY label FOR UPDATE;
UPDATE seats SET status='available', reservation_id=NULL, user_id=NULL
 WHERE show_id=:show AND label = ANY(:seats) AND reservation_id = :rid;   -- only seats still ours
UPDATE reservations SET status='cancelled' ...;
```

The release is keyed on `reservation_id`, not on the seat label, so it can never free a seat that has since been sold to
someone else. Cancelling twice is a no-op (the reservation row lock makes concurrent double-cancels serialize; the second sees
`cancelled`). A non-owner gets 404, the same as a missing id, so reservation ids don't leak.

The schema already has a `held` state. A time-boxed hold, if I extended this, would be: reserve → `held` with
`held_until`; a separate confirm/pay endpoint → `confirmed`; expiry handled lazily (the availability predicate becomes
`status='available' OR (status='held' AND held_until < now())`, evaluated under the row lock) plus a sweeper
(`UPDATE ... WHERE status='held' AND held_until < now() ... FOR UPDATE SKIP LOCKED`) to keep counts and gauges honest. Lazy
evaluation means correctness never depends on the sweeper running on time.

## 4. Consistency vs availability under a partition

This system is **CP**. Postgres is the single source of truth and every grant is a committed transaction there. If the app
can't reach the database, it can't decide who owns a seat, so it refuses:

- readiness (`/actuator/health/readiness`) runs `SELECT 1` on its own dedicated connection with a 2 s bound and goes
  DOWN (503) for anything that routes on it; liveness stays UP so the platform doesn't restart-loop a healthy process
  (Render's single health check, which also drives restarts, is therefore pointed at liveness; see section 6),
- writes fail (`503 db_unavailable` if the DB is unreachable, not a guessed answer).

Selling seats from a cache or a second region during a partition would mean two partitions can each sell A12. For an
inventory of unique items with money attached, a few seconds of "try again" is far cheaper than double-selling and
refunding. Reads (`GET /shows/{id}`) could be served stale from a replica if needed, as long as they're labelled as such and
never used to decide a grant.

## 5. Observability: what pages me at 2am

Rules are in [`observability/alerts.yml`](observability/alerts.yml), with a Grafana dashboard in `observability/grafana`.

| Page | Signal | Why |
|---|---|---|
| **Reconciliation drift** | `max(abs(seats_reconciliation_drift)) > 0` | `available+held+confirmed != total`. This is data corruption; nothing else matters until it's explained. |
| **Any sustained 5xx** | `rate(http_server_requests_seconds_count{status=~"5..",uri!~"/actuator.*"}[1m]) > 0` for 1m | Declines are designed to be 4xx; a 5xx is a bug or an outage. |
| **Not ready / down** | readiness failing / scrape `up == 0` | DB unreachable, so we are refusing sales. |
| Ticket: pool saturation | `hikaricp_connections_pending` high | Users are queueing; scale DB connections/instances before latency becomes timeouts. |
| Ticket: contention/too_busy declines rising | `reservations_declined_total{reason=~"contention\|too_busy"}` | Users are being turned away for *capacity*, not because seats are gone. |
| Ticket: p99 reserve latency > 2 s | histogram | Early warning of the above. |

Not paged: a spike of `seat_taken`. That's the system working during an on-sale.

Logs are JSON with a `requestId` (accepted from `X-Request-Id` or generated, echoed in the response) and `userId` on every
line, plus one outcome line per reserve (`outcome`, `reason`, `seats`, `latencyMs`), so a single user's complaint
("I got 409 at 20:00:01") can be traced to the exact decision. Render's logs sit behind a dashboard login, so there is a
[screen recording of the live logs during a full burst](https://drive.google.com/file/d/1NR4wOJWhcqgYBFQx2uvgnCfNiwVKaGe6/view?usp=sharing).

The seat gauges are read **from the database** every 2 s rather than maintained as in-memory counters, so they reconcile with
`GET /shows/{id}` by construction and survive restarts. Business counters are per-process (reset on restart, as Prometheus
counters do), and the burst script checks counter deltas against the 201s it observed.

## 6. What broke on the real deploy (Render free tier: 0.1 CPU, 512 MB)

Local bursts were clean from the start; the first live burst was not: thousands of `502`s. Correctness still held
(every hot seat had exactly one 201), but the instance was being restarted mid-burst, and Render's edge returned
`502` with `x-render-routing: no-deploy` while no instance was up. Fixes, each verified by reproducing Render's limits
locally (`docker run --memory 512m --cpus 0.1`) before redeploying:

1. **JVM sized for the container.** Prometheus showed ~365 MB max heap plus uncapped metaspace and a 240 MB code cache
   inside 512 MB. Now heap is 45% with metaspace, code cache, direct memory and thread stacks capped explicitly; the JIT is
   C1-only, which also cut cold start on 0.1 CPU from ~210 s to ~85 s. Peak RSS under a 0.1-CPU storm: ~390 MB.
2. **Readiness had been sharing the request pool.** Mid-burst all 20 connections are busy and hundreds of requests queue
   for one, so the probe timed out and reported DOWN on a healthy DB. It now uses its own one-connection pool.
3. **Admission control.** A fair semaphore caps executing requests (24 on Render). Waiters park cheaply on virtual threads
   in FIFO order, so a stampede doesn't turn into hundreds of runnable threads fighting for 0.1 CPU. Anything not admitted
   within 30 s gets a retryable `429`, never a 5xx.
4. **Keep-alive.** One lone edge `502` remained, the classic upstream-closes-a-reused-connection race (Tomcat closes after
   100 requests per connection by default). The app now never closes keep-alive connections first.
5. **Health check target.** Even with the above, a strict 0.1-CPU quota freezes the container for ~90 ms of every 100 ms,
   so probes occasionally took 5-10 s and Render restarted a working instance. Render uses one check for both routing and
   restarts, and restarts must key off *liveness*, so the health check now points at liveness. Readiness still checks the DB.

Result: the live service passes the full ~20k-request burst (10 hot seats x 1,000 users, 9k stampede with retries) with
zero 5xx and no restart. Latency on 0.1 CPU is high (p50 ~8 s). The same burst at 0.5 CPU locally completes in ~25 s with p99
~2 s and health probes under 0.5 s, so the remaining cost is CPU, not design.

A later run still saw a single `502` (`x-render-routing: no-deploy`) in ~8k requests. The instance did not restart and the
app's own 5xx counter stayed at zero, so the request never reached the app. The burst script now does what a real client
should: it retries a 5xx that lacks the app's JSON error body, up to 3 times with backoff. This is safe because every
reserve carries an idempotency key. It reports how many retries it made, and any 5xx returned by the app still fails the run.

## 7. AI usage

<!-- Edit this section so it reflects exactly what you did. It is graded on honesty and you'll be asked about it. -->

I used an AI coding agent (Cursor) heavily, and I'm describing it accurately here.

**What I directed/decided:**
- Stack: Java 21 + Spring Boot + PostgreSQL (what I'm strongest in and what I'd extend live), and Render for hosting.
- Reviewed and approved the implementation plan before any code was written: Postgres as the single arbiter, row locks in a
  fixed order, conditional upsert for quota, unique index for idempotency, confirm-immediately + cancel.

**What the AI produced:** the plan draft, most of the code, the SQL, the tests, the burst script, Docker/Render config and the
first draft of these docs. It also set up the local toolchain (JDK, Maven, Colima).

**Where judgement mattered / what I verified:**
- The concurrency tests found a real bug (the same-key twin race in section 2). The fix is a re-check of the key before a
  pre-check decline, not removal of the pre-check, because the pre-check is what keeps a hot-seat storm off the row locks.
- Choices I want to be explicit about owning: `200` (not `201`) for idempotent replays; idempotency keys scoped per user;
  all-or-nothing partials; `404` for cancelling someone else's reservation; `409 contention` / `429 too_busy` instead of 5xx.
- I ran the burst locally at ~20k requests (zero 5xx, every hot seat exactly one 201, metrics reconciled) and against the
  live URL; the live failures and their fixes are in section 6.
- I chose to stay on Render's free tier and document the CPU limit rather than pay for a bigger instance.

## 8. What I'd do next

1. **Holds + payment**: `held` with TTL, confirm endpoint, lazy expiry + `SKIP LOCKED` sweeper (section 3).
2. **Admission control for on-sale**: a per-show virtual queue / token bucket in front of reserve so the DB sees a bounded
   arrival rate; today we rely on pool queueing plus the lock-free pre-check.
3. **Horizontal scale**: the app is stateless, so add instances behind the LB; size `instances x pool` against Postgres
   `max_connections` (or put PgBouncer in transaction mode in front). Partition hot data by `show_id` if one DB isn't enough;
   every transaction touches exactly one show, so sharding by show keeps them single-shard.
4. **Durable metrics**: derive confirmed/cancelled counts from the DB (or an outbox) in addition to process counters, and ship
   logs to a hosted backend with saved queries per `requestId`.
5. **Proper auth**: verify tokens from a real IdP (JWKS, RS256), drop the demo issuer, rate-limit per user.
6. **Read path**: cache `GET /shows/{id}` for a second or serve it from a replica; at 50k seats it's the heaviest query.
