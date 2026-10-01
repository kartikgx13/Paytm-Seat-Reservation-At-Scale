package com.paytm.seats.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Seat-state gauges read straight from the database (the system of record), so they reconcile with
 * GET /shows/{id} by construction rather than drifting like in-memory counters would. Refreshed on a
 * fixed delay; scoped to recently created shows to bound cardinality.
 */
@Component
public class SeatGauges {

    private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);
    private static final int MAX_SHOWS = 25;

    private final JdbcTemplate jdbc;
    private final MultiGauge available;
    private final MultiGauge held;
    private final MultiGauge confirmed;
    private final MultiGauge total;
    private final MultiGauge drift;
    private final AtomicLong availableAll = new AtomicLong();
    private final AtomicLong driftAll = new AtomicLong();

    public SeatGauges(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.available = MultiGauge.builder("seats.available").description("Seats available, per show").register(registry);
        this.held = MultiGauge.builder("seats.held").description("Seats held, per show").register(registry);
        this.confirmed = MultiGauge.builder("seats.confirmed").description("Seats confirmed, per show").register(registry);
        this.total = MultiGauge.builder("seats.total").description("Total seats, per show").register(registry);
        this.drift = MultiGauge.builder("seats.reconciliation.drift")
                .description("total - (available + held + confirmed); must always be 0").register(registry);
        registry.gauge("seats.available.all_recent_shows", availableAll);
        registry.gauge("seats.reconciliation.drift.all_recent_shows", driftAll);
    }

    record Row(String showId, String name, int total, long available, long held, long confirmed) {
    }

    @Scheduled(fixedDelayString = "${app.gauge-refresh-ms:2000}", initialDelay = 1000)
    public void refresh() {
        try {
            List<Row> rows = jdbc.query("""
                    SELECT s.id::text AS show_id, s.name, s.total_seats,
                           count(*) FILTER (WHERE st.status = 'available') AS available,
                           count(*) FILTER (WHERE st.status = 'held')      AS held,
                           count(*) FILTER (WHERE st.status = 'confirmed') AS confirmed
                    FROM (SELECT * FROM shows ORDER BY created_at DESC LIMIT ?) s
                    JOIN seats st ON st.show_id = s.id
                    GROUP BY s.id, s.name, s.total_seats
                    """,
                    (rs, i) -> new Row(rs.getString("show_id"), rs.getString("name"), rs.getInt("total_seats"),
                            rs.getLong("available"), rs.getLong("held"), rs.getLong("confirmed")),
                    MAX_SHOWS);
            List<MultiGauge.Row<?>> a = new ArrayList<>();
            List<MultiGauge.Row<?>> h = new ArrayList<>();
            List<MultiGauge.Row<?>> c = new ArrayList<>();
            List<MultiGauge.Row<?>> t = new ArrayList<>();
            List<MultiGauge.Row<?>> d = new ArrayList<>();
            long availSum = 0;
            long driftSum = 0;
            for (Row r : rows) {
                Tags tags = Tags.of("show_id", r.showId(), "show_name", r.name());
                long dr = r.total() - (r.available() + r.held() + r.confirmed());
                a.add(MultiGauge.Row.of(tags, r.available()));
                h.add(MultiGauge.Row.of(tags, r.held()));
                c.add(MultiGauge.Row.of(tags, r.confirmed()));
                t.add(MultiGauge.Row.of(tags, r.total()));
                d.add(MultiGauge.Row.of(tags, dr));
                availSum += r.available();
                driftSum += Math.abs(dr);
            }
            available.register(a, true);
            held.register(h, true);
            confirmed.register(c, true);
            total.register(t, true);
            drift.register(d, true);
            availableAll.set(availSum);
            driftAll.set(driftSum);
        } catch (RuntimeException e) {
            // Gauges keep their last value; readiness reports DB health separately.
            log.warn("seat gauge refresh failed: {}", e.getMessage());
        }
    }
}
