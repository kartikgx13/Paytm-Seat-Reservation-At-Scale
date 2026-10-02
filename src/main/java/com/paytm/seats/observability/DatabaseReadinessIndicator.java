package com.paytm.seats.observability;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Readiness check that round-trips to Postgres and fails closed within a bounded time.
 * <p>
 * It uses its own single-connection pool: during an on-sale burst every connection in the main pool is busy and
 * hundreds of requests are queued for one, so a probe sharing that pool would time out and report DOWN while the
 * database is perfectly healthy, and the platform would then restart a working instance mid-burst.
 */
@Component("dbReadiness")
public class DatabaseReadinessIndicator implements HealthIndicator {

    private static final long TIMEOUT_MS = 2000;

    private final HikariDataSource probePool;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DatabaseReadinessIndicator(DataSourceProperties props) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(props.determineUrl());
        cfg.setUsername(props.determineUsername());
        cfg.setPassword(props.determinePassword());
        cfg.setPoolName("health-probe");
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(TIMEOUT_MS);
        cfg.setValidationTimeout(1000);
        cfg.setInitializationFailTimeout(-1);
        this.probePool = new HikariDataSource(cfg);
    }

    @Override
    public Health health() {
        long start = System.nanoTime();
        try {
            boolean ok = CompletableFuture.supplyAsync(this::selectOne, executor).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long ms = (System.nanoTime() - start) / 1_000_000;
            return ok ? Health.up().withDetail("latencyMs", ms).build()
                    : Health.down().withDetail("reason", "unexpected result").build();
        } catch (TimeoutException e) {
            return Health.down().withDetail("reason", "timed out after " + TIMEOUT_MS + "ms").build();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Health.down().withDetail("reason", cause.getClass().getSimpleName()).build();
        }
    }

    private boolean selectOne() {
        try (Connection c = probePool.getConnection(); Statement st = c.createStatement()) {
            st.setQueryTimeout(2);
            try (ResultSet rs = st.executeQuery("SELECT 1")) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @PreDestroy
    void close() {
        probePool.close();
        executor.shutdownNow();
    }
}
