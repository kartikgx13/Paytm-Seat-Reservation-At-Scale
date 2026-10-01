package com.paytm.seats.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Readiness check that actually round-trips to Postgres and fails closed within a bounded time. The stock
 * indicator would block for the full pool connection-timeout when the DB is unreachable, which makes probes
 * hang instead of reporting DOWN.
 */
@Component("dbReadiness")
public class DatabaseReadinessIndicator implements HealthIndicator {

    private static final long TIMEOUT_MS = 2000;

    private final JdbcTemplate jdbc;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DatabaseReadinessIndicator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        long start = System.nanoTime();
        try {
            Integer one = CompletableFuture.supplyAsync(() -> jdbc.queryForObject("SELECT 1", Integer.class), executor)
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long ms = (System.nanoTime() - start) / 1_000_000;
            return one != null && one == 1
                    ? Health.up().withDetail("latencyMs", ms).build()
                    : Health.down().withDetail("reason", "unexpected result").build();
        } catch (TimeoutException e) {
            return Health.down().withDetail("reason", "timed out after " + TIMEOUT_MS + "ms").build();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Health.down().withDetail("reason", cause.getClass().getSimpleName()).build();
        }
    }
}
