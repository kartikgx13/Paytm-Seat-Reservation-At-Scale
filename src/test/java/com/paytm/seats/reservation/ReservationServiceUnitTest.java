package com.paytm.seats.reservation;

import com.paytm.seats.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationServiceUnitTest {

    @Test
    void seatsAreTrimmedAndSorted() {
        assertThat(ReservationService.normalizeSeats(List.of(" B2", "A10", "A2 "))).containsExactly("A10", "A2", "B2");
    }

    @Test
    void duplicateSeatsRejected() {
        assertThatThrownBy(() -> ReservationService.normalizeSeats(List.of("A1", "A1 ")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void hashIsOrderIndependentButShowAndSeatSensitive() {
        UUID show = UUID.randomUUID();
        String h1 = ReservationService.requestHash(show, ReservationService.normalizeSeats(List.of("A1", "A2")));
        String h2 = ReservationService.requestHash(show, ReservationService.normalizeSeats(List.of("A2", "A1")));
        String h3 = ReservationService.requestHash(show, ReservationService.normalizeSeats(List.of("A1", "A3")));
        String h4 = ReservationService.requestHash(UUID.randomUUID(), ReservationService.normalizeSeats(List.of("A1", "A2")));
        assertThat(h1).isEqualTo(h2).isNotEqualTo(h3).isNotEqualTo(h4);
    }

    @Test
    void keyRequired() {
        assertThatThrownBy(() -> ReservationService.normalizeKey("  ")).isInstanceOf(ApiException.class);
    }
}
