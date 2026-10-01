package com.paytm.seats.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Assigns every request a correlation id (honouring a sane inbound X-Request-Id), puts it in the MDC so
 * every log line for the request carries it, echoes it back in the response, and writes one access log line.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Logger access = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String inbound = req.getHeader(HEADER);
        String requestId = inbound != null && SAFE_ID.matcher(inbound).matches() ? inbound : UUID.randomUUID().toString();
        long start = System.nanoTime();
        MDC.put("requestId", requestId);
        res.setHeader(HEADER, requestId);
        try {
            chain.doFilter(req, res);
        } finally {
            String path = req.getRequestURI();
            if (!path.startsWith("/actuator")) {
                access.info("{} {} {}", req.getMethod(), path, res.getStatus(),
                        kv("method", req.getMethod()), kv("path", path), kv("status", res.getStatus()),
                        kv("durationMs", (System.nanoTime() - start) / 1_000_000));
            }
            MDC.clear();
        }
    }
}
