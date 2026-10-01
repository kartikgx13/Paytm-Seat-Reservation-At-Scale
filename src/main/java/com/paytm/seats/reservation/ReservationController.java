package com.paytm.seats.reservation;

import com.paytm.seats.auth.CurrentUser;
import com.paytm.seats.reservation.ReservationDtos.ReservationView;
import com.paytm.seats.reservation.ReservationDtos.ReserveRequest;
import com.paytm.seats.reservation.ReservationDtos.ReserveResult;
import com.paytm.seats.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ReservationController {

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    /**
     * 201 for a newly created reservation; 200 (same body, header Idempotent-Replayed: true) when the
     * idempotency key was already used for this exact request.
     */
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationView> reserve(@PathVariable UUID showId,
                                                   @RequestBody ReserveRequest body,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
                                                   HttpServletRequest http) {
        String userId = CurrentUser.id(http);
        String key = resolveKey(headerKey, body.idempotencyKey());
        ReserveResult r = reservations.reserve(showId, userId, body.seats(), key);
        if (r.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(r.reservation());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(r.reservation());
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationView cancel(@PathVariable UUID id, HttpServletRequest http) {
        return reservations.cancel(id, CurrentUser.id(http));
    }

    @GetMapping("/reservations/{id}")
    public ReservationView get(@PathVariable UUID id, HttpServletRequest http) {
        return reservations.get(id, CurrentUser.id(http));
    }

    @GetMapping("/me/reservations")
    public List<ReservationView> mine(@RequestParam("show_id") UUID showId, HttpServletRequest http) {
        return reservations.listMine(CurrentUser.id(http), showId);
    }

    private static String resolveKey(String header, String body) {
        boolean hasHeader = header != null && !header.isBlank();
        boolean hasBody = body != null && !body.isBlank();
        if (hasHeader && hasBody && !header.trim().equals(body.trim())) {
            throw ApiException.badRequest("Idempotency-Key header and idempotency_key body field disagree");
        }
        return hasHeader ? header : body;
    }
}
