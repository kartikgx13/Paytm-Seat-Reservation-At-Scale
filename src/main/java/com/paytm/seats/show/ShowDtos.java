package com.paytm.seats.show;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ShowDtos {

    private ShowDtos() {
    }

    public static final int MAX_SEATS_PER_SHOW = 50_000;

    public record CreateShowRequest(
            @NotBlank @Size(max = 200) String name,
            @NotEmpty @Size(max = MAX_SEATS_PER_SHOW) List<@NotBlank @Size(max = 32) String> seats,
            @NotNull @Min(0) Long pricePaise,
            @Min(1) Integer perUserLimit) {
    }

    public record SeatView(String label, String status) {
    }

    public record Counts(int totalSeats, int available, int held, int confirmed) {
        public boolean reconciles() {
            return available + held + confirmed == totalSeats;
        }
    }

    public record ShowView(
            UUID id,
            String name,
            long pricePaise,
            int perUserLimit,
            int totalSeats,
            Instant createdAt,
            Counts counts,
            List<SeatView> seats) {
    }
}
