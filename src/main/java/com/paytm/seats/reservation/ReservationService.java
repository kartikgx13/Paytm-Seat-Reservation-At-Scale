package com.paytm.seats.reservation;

import com.paytm.seats.config.AppProperties;
import com.paytm.seats.observability.ReservationMetrics;
import com.paytm.seats.reservation.ReservationDtos.ReservationView;
import com.paytm.seats.reservation.ReservationDtos.ReserveResult;
import com.paytm.seats.reservation.ReservationRepository.SeatState;
import com.paytm.seats.reservation.ReservationRepository.StoredReservation;
import com.paytm.seats.show.ShowService;
import com.paytm.seats.show.ShowService.ShowRow;
import com.paytm.seats.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class ReservationService {

    public static final int MAX_KEY_LENGTH = 128;
    public static final int MAX_SEATS_PER_REQUEST = 20;

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository repo;
    private final ShowService shows;
    private final TransactionTemplate tx;
    private final AppProperties props;
    private final ReservationMetrics metrics;

    public ReservationService(ReservationRepository repo, ShowService shows, TransactionTemplate tx,
                              AppProperties props, ReservationMetrics metrics) {
        this.repo = repo;
        this.shows = shows;
        this.tx = tx;
        this.props = props;
        this.metrics = metrics;
    }

    /**
     * Reserves all requested seats or none (all-or-nothing). The decision is made inside one transaction:
     * <ol>
     *   <li>claim the idempotency key (unique index on (user_id, idempotency_key)),</li>
     *   <li>conditionally increment the per-user quota (single-statement check-and-set),</li>
     *   <li>row-lock the requested seats in label order and verify every one is available,</li>
     *   <li>flip them to confirmed, then commit.</li>
     * </ol>
     * Any decline throws, which rolls back steps 1-3 so a declined attempt leaves no trace.
     */
    public ReserveResult reserve(UUID showId, String userId, List<String> requestedSeats, String idempotencyKey) {
        long start = System.nanoTime();
        String outcome = "error";
        try {
            ReserveResult r = doReserve(showId, userId, requestedSeats, idempotencyKey);
            if (r.replayed()) {
                outcome = "idempotent_replay";
                metrics.declined(outcome);
            } else {
                outcome = "confirmed";
                metrics.confirmed(r.reservation().seats().size());
            }
            log.info("reserve outcome", kv("event", "reserve"), kv("outcome", outcome), kv("showId", showId),
                    kv("seats", requestedSeats), kv("reservationId", r.reservation().reservationId()),
                    kv("latencyMs", (System.nanoTime() - start) / 1_000_000));
            return r;
        } catch (ApiException e) {
            outcome = e.code();
            metrics.declined(outcome);
            logDecline(outcome, showId, userId, requestedSeats, start);
            throw e;
        } catch (DataAccessException e) {
            outcome = classify(e);
            metrics.declined(outcome);
            logDecline(outcome, showId, userId, requestedSeats, start);
            throw e;
        } finally {
            metrics.latency(outcome, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private void logDecline(String reason, UUID showId, String userId, List<String> seats, long start) {
        log.info("reserve outcome", kv("event", "reserve"), kv("outcome", "declined"), kv("reason", reason),
                kv("showId", showId), kv("seats", seats),
                kv("latencyMs", (System.nanoTime() - start) / 1_000_000));
    }

    private static String classify(DataAccessException e) {
        if (e instanceof CannotGetJdbcConnectionException) {
            return "too_busy";
        }
        return "contention";
    }

    private ReserveResult doReserve(UUID showId, String userId, List<String> requestedSeats, String idempotencyKey) {
        List<String> seats = normalizeSeats(requestedSeats);
        String key = normalizeKey(idempotencyKey);
        ShowRow show = shows.find(showId).orElseThrow(() -> ApiException.notFound("show"));
        String hash = requestHash(showId, seats);

        // Fast path for retries: an already-committed key is answered without touching seat rows.
        var existing = repo.findByKey(userId, key);
        if (existing.isPresent()) {
            return replayOrReject(existing.get(), hash);
        }
        if (seats.size() > show.perUserLimit()) {
            throw perUserLimit(show.perUserLimit());
        }
        // Lock-free early rejection for seats that are visibly taken. This can only decline, never grant,
        // so it cannot cause a double-sell; it just keeps losers of a hot-seat storm off the row locks.
        List<SeatState> peek = repo.peekSeats(showId, seats);
        rejectUnknownSeats(seats, peek);
        rejectUnavailable(peek);

        long amount = Math.multiplyExact(show.pricePaise(), (long) seats.size());
        UUID reservationId = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        return tx.execute(status -> {
            repo.setLocalLockTimeout(props.lockTimeoutMs());

            if (!repo.claimKey(reservationId, showId, userId, key, hash, seats, amount, createdAt)) {
                // A concurrent request with the same key committed first; it is now visible to this statement.
                StoredReservation winner = repo.findByKey(userId, key)
                        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "contention", "please retry"));
                return replayOrReject(winner, hash);
            }
            if (!repo.tryIncrementQuota(showId, userId, seats.size(), show.perUserLimit())) {
                throw perUserLimit(show.perUserLimit());
            }
            List<SeatState> locked = repo.lockSeatsOrdered(showId, seats);
            rejectUnknownSeats(seats, locked);
            rejectUnavailable(locked);

            int updated = repo.confirmSeats(showId, seats, reservationId, userId);
            if (updated != seats.size()) {
                // Unreachable while we hold the row locks; kept so a logic bug fails safe (rollback) not unsafe.
                throw seatTaken(List.of());
            }
            return new ReserveResult(new ReservationView(reservationId, showId, userId, seats, amount,
                    "confirmed", createdAt), false);
        });
    }

    /**
     * Owner-only cancel. Locks in the same global order as reserve (reservation row, quota row, seat rows by
     * label) so cancel and reserve can never deadlock each other. Seats are released only where they still
     * point at this reservation, so a cancel can never free a seat that now belongs to someone else.
     * Cancelling twice is a no-op that returns the cancelled reservation.
     */
    public ReservationView cancel(UUID reservationId, String userId) {
        boolean[] changed = new boolean[1];
        ReservationView v = doCancel(reservationId, userId, changed);
        if (changed[0]) {
            metrics.cancelled(v.seats().size());
        }
        log.info("cancel outcome", kv("event", "cancel"), kv("outcome", changed[0] ? "cancelled" : "already_cancelled"),
                kv("reservationId", reservationId), kv("showId", v.showId()), kv("seats", v.seats()));
        return v;
    }

    private ReservationView doCancel(UUID reservationId, String userId, boolean[] changed) {
        return tx.execute(status -> {
            repo.setLocalLockTimeout(props.lockTimeoutMs());
            StoredReservation r = repo.lockById(reservationId)
                    .filter(s -> s.view().userId().equals(userId))
                    // Same answer for "missing" and "not yours" so ids of other users' reservations don't leak.
                    .orElseThrow(() -> ApiException.notFound("reservation"));
            ReservationView v = r.view();
            if ("cancelled".equals(v.status())) {
                return v;
            }
            repo.decrementQuota(v.showId(), userId, v.seats().size());
            repo.lockSeatsOrdered(v.showId(), v.seats());
            repo.releaseSeats(v.showId(), v.seats(), reservationId);
            repo.markCancelled(reservationId);
            changed[0] = true;
            return new ReservationView(v.reservationId(), v.showId(), v.userId(), v.seats(), v.amountPaise(),
                    "cancelled", v.createdAt());
        });
    }

    public ReservationView get(UUID reservationId, String userId) {
        return repo.findById(reservationId)
                .map(StoredReservation::view)
                .filter(v -> v.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("reservation"));
    }

    public List<ReservationView> listMine(String userId, UUID showId) {
        return repo.findByUserAndShow(userId, showId);
    }

    private ReserveResult replayOrReject(StoredReservation stored, String hash) {
        if (!stored.requestHash().equals(hash)) {
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_reused",
                    "idempotency key was already used with a different request",
                    Map.of("reservation_id", stored.view().reservationId()));
        }
        return new ReserveResult(stored.view(), true);
    }

    private static void rejectUnknownSeats(List<String> requested, List<SeatState> found) {
        if (found.size() == requested.size()) {
            return;
        }
        Set<String> known = found.stream().map(SeatState::label).collect(Collectors.toSet());
        List<String> unknown = requested.stream().filter(s -> !known.contains(s)).toList();
        throw new ApiException(HttpStatus.BAD_REQUEST, "unknown_seat", "seat(s) do not exist in this show",
                Map.of("unknown_seats", unknown));
    }

    private static void rejectUnavailable(List<SeatState> seats) {
        List<String> taken = seats.stream().filter(s -> !"available".equals(s.status())).map(SeatState::label).toList();
        if (!taken.isEmpty()) {
            throw seatTaken(taken);
        }
    }

    static ApiException seatTaken(List<String> taken) {
        return new ApiException(HttpStatus.CONFLICT, "seat_taken", "seat(s) already taken",
                Map.of("unavailable_seats", taken));
    }

    static ApiException perUserLimit(int limit) {
        return new ApiException(HttpStatus.CONFLICT, "per_user_limit",
                "reservation would exceed the per-user limit of " + limit + " seats for this show",
                Map.of("per_user_limit", limit));
    }

    /** Trimmed, de-duplicated check, returned sorted: sorted order is both the lock order and the hash input. */
    static List<String> normalizeSeats(List<String> seats) {
        if (seats == null || seats.isEmpty()) {
            throw ApiException.badRequest("seats must be a non-empty array");
        }
        if (seats.size() > MAX_SEATS_PER_REQUEST) {
            throw ApiException.badRequest("at most " + MAX_SEATS_PER_REQUEST + " seats per request");
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String s : seats) {
            if (s == null || s.isBlank()) {
                throw ApiException.badRequest("seat labels must be non-blank");
            }
            if (!sorted.add(s.trim())) {
                throw ApiException.badRequest("duplicate seat in request: " + s.trim());
            }
        }
        return List.copyOf(sorted);
    }

    static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("idempotency key is required (Idempotency-Key header or idempotency_key)");
        }
        String k = key.trim();
        if (k.length() > MAX_KEY_LENGTH) {
            throw ApiException.badRequest("idempotency key longer than " + MAX_KEY_LENGTH);
        }
        return k;
    }

    static String requestHash(UUID showId, List<String> sortedSeats) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(showId.toString().getBytes(StandardCharsets.UTF_8));
            for (String s : sortedSeats) {
                md.update((byte) 0);
                md.update(s.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
