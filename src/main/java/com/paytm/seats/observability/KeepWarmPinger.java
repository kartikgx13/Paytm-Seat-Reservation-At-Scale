package com.paytm.seats.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Free-tier hosts (Render) put a service to sleep after ~15 min without inbound traffic, and the next caller then
 * waits through a JVM cold start. When a public URL is configured, the service requests its own liveness endpoint
 * through the platform's edge on a fixed interval, which counts as inbound traffic and keeps the instance warm.
 * Render injects RENDER_EXTERNAL_URL automatically; locally it is unset and this does nothing.
 */
@Component
public class KeepWarmPinger {

    private static final Logger log = LoggerFactory.getLogger(KeepWarmPinger.class);

    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public KeepWarmPinger(@Value("${app.keep-warm-url:${RENDER_EXTERNAL_URL:}}") String baseUrl) {
        this.url = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.replaceAll("/+$", "") + "/actuator/health/liveness";
        if (url != null) {
            log.info("keep-warm pinger enabled for {}", url);
        }
    }

    @Scheduled(fixedDelayString = "${app.keep-warm-interval-ms:600000}", initialDelayString = "${app.keep-warm-interval-ms:600000}")
    public void ping() {
        if (url == null) {
            return;
        }
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "keep-warm").GET().build(), HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() != 200) {
                log.warn("keep-warm ping returned {}", r.statusCode());
            }
        } catch (Exception e) {
            log.warn("keep-warm ping failed: {}", e.toString());
        }
    }
}
