package com.paytm.seats.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Business counters. Exposed on /actuator/prometheus as e.g. reservations_confirmed_total and
 * reservations_declined_total{reason="seat_taken"}.
 */
@Component
public class ReservationMetrics {

    private final MeterRegistry registry;
    private final Counter confirmed;
    private final Counter seatsConfirmed;
    private final Counter cancelled;
    private final Counter seatsReleased;
    private final ConcurrentMap<String, Counter> declined = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> latency = new ConcurrentHashMap<>();

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.confirmed = Counter.builder("reservations.confirmed")
                .description("Reservations newly confirmed (one per 201)").register(registry);
        this.seatsConfirmed = Counter.builder("reservations.seats.confirmed")
                .description("Seats moved to confirmed by new reservations").register(registry);
        this.cancelled = Counter.builder("reservations.cancelled")
                .description("Reservations cancelled by their owner").register(registry);
        this.seatsReleased = Counter.builder("reservations.seats.released")
                .description("Seats returned to available by cancellations").register(registry);
        for (String reason : new String[]{"seat_taken", "per_user_limit", "idempotent_replay",
                "idempotency_key_reused", "contention", "too_busy", "unknown_seat", "invalid_request", "not_found"}) {
            declinedCounter(reason);
        }
    }

    public void confirmed(int seats) {
        confirmed.increment();
        seatsConfirmed.increment(seats);
    }

    public void cancelled(int seats) {
        cancelled.increment();
        seatsReleased.increment(seats);
    }

    public void declined(String reason) {
        declinedCounter(reason).increment();
    }

    public void latency(String outcome, Duration d) {
        latency.computeIfAbsent(outcome, o -> Timer.builder("reservations.latency")
                .description("Reserve request latency by outcome")
                .tag("outcome", o)
                .register(registry)).record(d);
    }

    private Counter declinedCounter(String reason) {
        return declined.computeIfAbsent(reason, r -> Counter.builder("reservations.declined")
                .description("Reserve requests that did not create a reservation, by reason")
                .tag("reason", r)
                .register(registry));
    }
}
