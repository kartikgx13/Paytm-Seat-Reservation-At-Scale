package com.paytm.seats.observability;

import com.paytm.seats.config.AppProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps how many API requests execute at once. Requests over the cap park (cheaply, on virtual threads) in FIFO
 * order instead of all competing for CPU, which keeps per-request work and memory bounded during a stampede and,
 * crucially, keeps health probes (which bypass this filter) answering promptly on small instances. A request
 * that cannot be admitted within the wait budget is shed with a retryable 429.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AdmissionControlFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdmissionControlFilter.class);

    private final Semaphore permits;
    private final long maxWaitMs;
    private final AtomicInteger waiting = new AtomicInteger();
    private final ReservationMetrics metrics;

    public AdmissionControlFilter(AppProperties props, MeterRegistry registry, ReservationMetrics metrics) {
        this.permits = new Semaphore(props.admissionMaxConcurrent(), true);
        this.maxWaitMs = props.admissionMaxWaitMs();
        this.metrics = metrics;
        Gauge.builder("admission.waiting", waiting, AtomicInteger::get)
                .description("Requests parked waiting for an execution slot").register(registry);
        Gauge.builder("admission.in_flight", permits, p -> props.admissionMaxConcurrent() - p.availablePermits())
                .description("Requests currently executing").register(registry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return req.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        boolean acquired;
        waiting.incrementAndGet();
        try {
            acquired = permits.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        } finally {
            waiting.decrementAndGet();
        }
        if (!acquired) {
            log.warn("admission wait exceeded, shedding {} {}", req.getMethod(), req.getRequestURI());
            if (req.getRequestURI().endsWith("/reserve")) {
                metrics.declined("too_busy");
            }
            res.setStatus(429);
            res.setHeader("Retry-After", "1");
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"too_busy\",\"message\":\"server busy, retry shortly\"}");
            return;
        }
        try {
            chain.doFilter(req, res);
        } finally {
            permits.release();
        }
    }
}
