package com.paytm.seats.reservation;

import com.paytm.seats.reservation.ReservationDtos.ReservationView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All SQL for the reservation path. Every method here runs inside the caller's transaction. */
@Repository
public class ReservationRepository {

    public record StoredReservation(ReservationView view, String requestHash) {
    }

    public record SeatState(String label, String status) {
    }

    private static final String RESERVATION_COLUMNS =
            "id, show_id, user_id, seats, amount_paise, status, created_at, request_hash";

    private static final RowMapper<StoredReservation> MAPPER = (rs, i) -> new StoredReservation(
            new ReservationView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("show_id", UUID.class),
                    rs.getString("user_id"),
                    Arrays.asList((String[]) rs.getArray("seats").getArray()),
                    rs.getLong("amount_paise"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant()),
            rs.getString("request_hash"));

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Bounds how long any statement in the current transaction may wait for a row lock. */
    public void setLocalLockTimeout(int millis) {
        jdbc.execute("SET LOCAL lock_timeout = '" + Math.max(1, millis) + "ms'");
    }

    public Optional<StoredReservation> findByKey(String userId, String idempotencyKey) {
        return jdbc.query("SELECT " + RESERVATION_COLUMNS + " FROM reservations WHERE user_id = ? AND idempotency_key = ?",
                MAPPER, userId, idempotencyKey).stream().findFirst();
    }

    public Optional<StoredReservation> findById(UUID id) {
        return jdbc.query("SELECT " + RESERVATION_COLUMNS + " FROM reservations WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<StoredReservation> lockById(UUID id) {
        return jdbc.query("SELECT " + RESERVATION_COLUMNS + " FROM reservations WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public List<ReservationView> findByUserAndShow(String userId, UUID showId) {
        return jdbc.query("SELECT " + RESERVATION_COLUMNS + " FROM reservations WHERE user_id = ? AND show_id = ? "
                + "ORDER BY created_at", MAPPER, userId, showId).stream().map(StoredReservation::view).toList();
    }

    /**
     * Claims the idempotency key. If another in-flight transaction holds the same (user, key), Postgres makes
     * this statement wait on the unique index until that transaction ends: on commit we get no row back
     * (the key is taken, caller replays); on rollback our insert proceeds. Exactly one writer can ever win.
     */
    public boolean claimKey(UUID id, UUID showId, String userId, String key, String hash, List<String> seats,
                            long amountPaise, Instant createdAt) {
        Integer inserted = jdbc.execute((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO reservations "
                            + "(id, show_id, user_id, idempotency_key, request_hash, seats, amount_paise, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'confirmed', ?) "
                            + "ON CONFLICT ON CONSTRAINT reservations_idem_uq DO NOTHING")) {
                ps.setObject(1, id);
                ps.setObject(2, showId);
                ps.setString(3, userId);
                ps.setString(4, key);
                ps.setString(5, hash);
                ps.setArray(6, c.createArrayOf("text", seats.toArray()));
                ps.setLong(7, amountPaise);
                ps.setTimestamp(8, Timestamp.from(createdAt));
                return ps.executeUpdate();
            }
        });
        return inserted != null && inserted == 1;
    }

    /**
     * Atomically checks and increments the user's seat count for the show. The WHERE on DO UPDATE makes the
     * limit check and the increment one statement under the quota row's lock; no row back means over limit.
     */
    public boolean tryIncrementQuota(UUID showId, String userId, int n, int limit) {
        List<Integer> r = jdbc.queryForList(
                "INSERT INTO user_show_quota AS q (show_id, user_id, seat_count) VALUES (?, ?, ?) "
                        + "ON CONFLICT (show_id, user_id) DO UPDATE SET seat_count = q.seat_count + EXCLUDED.seat_count "
                        + "WHERE q.seat_count + EXCLUDED.seat_count <= ? "
                        + "RETURNING seat_count",
                Integer.class, showId, userId, n, limit);
        return !r.isEmpty();
    }

    public void decrementQuota(UUID showId, String userId, int n) {
        jdbc.update("UPDATE user_show_quota SET seat_count = seat_count - ? WHERE show_id = ? AND user_id = ?",
                n, showId, userId);
    }

    /**
     * Row-locks the requested seats in label order. A single global order means two multi-seat requests can
     * never each hold a seat the other is waiting for, so there is no lock cycle and no deadlock.
     */
    public List<SeatState> lockSeatsOrdered(UUID showId, List<String> sortedLabels) {
        return jdbc.execute((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT label, status FROM seats WHERE show_id = ? AND label = ANY(?) ORDER BY label FOR UPDATE")) {
                ps.setObject(1, showId);
                ps.setArray(2, c.createArrayOf("text", sortedLabels.toArray()));
                List<SeatState> out = new ArrayList<>(sortedLabels.size());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new SeatState(rs.getString(1), rs.getString(2)));
                    }
                }
                return out;
            }
        });
    }

    /** Lock-free read used only to reject hopeless requests early; never used to decide a win. */
    public List<SeatState> peekSeats(UUID showId, List<String> labels) {
        return jdbc.execute((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT label, status FROM seats WHERE show_id = ? AND label = ANY(?)")) {
                ps.setObject(1, showId);
                ps.setArray(2, c.createArrayOf("text", labels.toArray()));
                List<SeatState> out = new ArrayList<>(labels.size());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new SeatState(rs.getString(1), rs.getString(2)));
                    }
                }
                return out;
            }
        });
    }

    /** Guarded on status = 'available' as a second line of defence; caller already holds the row locks. */
    public int confirmSeats(UUID showId, List<String> labels, UUID reservationId, String userId) {
        return jdbc.execute((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE seats SET status = 'confirmed', reservation_id = ?, user_id = ?, updated_at = now() "
                            + "WHERE show_id = ? AND label = ANY(?) AND status = 'available'")) {
                ps.setObject(1, reservationId);
                ps.setString(2, userId);
                ps.setObject(3, showId);
                ps.setArray(4, c.createArrayOf("text", labels.toArray()));
                return ps.executeUpdate();
            }
        });
    }

    /** Releases only seats still owned by this reservation, so a release can never free someone else's seat. */
    public int releaseSeats(UUID showId, List<String> sortedLabels, UUID reservationId) {
        return jdbc.execute((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE seats SET status = 'available', reservation_id = NULL, user_id = NULL, updated_at = now() "
                            + "WHERE show_id = ? AND label = ANY(?) AND reservation_id = ?")) {
                ps.setObject(1, showId);
                ps.setArray(2, c.createArrayOf("text", sortedLabels.toArray()));
                ps.setObject(3, reservationId);
                return ps.executeUpdate();
            }
        });
    }

    public void markCancelled(UUID reservationId) {
        jdbc.update("UPDATE reservations SET status = 'cancelled', cancelled_at = now() WHERE id = ?", reservationId);
    }
}
