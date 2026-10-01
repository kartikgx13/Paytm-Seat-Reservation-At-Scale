package com.paytm.seats.reservation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReservationDtos {

    private ReservationDtos() {
    }

    /** user_id is deliberately absent: identity comes only from the bearer token. */
    public record ReserveRequest(List<String> seats, String idempotencyKey) {
    }

    public record ReservationView(
            UUID reservationId,
            UUID showId,
            String userId,
            List<String> seats,
            long amountPaise,
            String status,
            Instant createdAt) {
    }

    /** replayed == true means an earlier request with the same idempotency key already created this. */
    public record ReserveResult(ReservationView reservation, boolean replayed) {
    }
}
