package com.paytm.seats;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureObservability
class HealthAndMetricsTest extends IntegrationTestBase {

    @Test
    void probesAreUp() {
        assertThat(get("/actuator/health/liveness", Map.of()).status()).isEqualTo(200);
        Resp ready = get("/actuator/health/readiness", Map.of());
        assertThat(ready.status()).isEqualTo(200);
        assertThat(ready.body().at("/components/dbReadiness/status").asText()).isEqualTo("UP");
    }

    @Test
    void prometheusExposesBusinessMetrics() throws Exception {
        String show = createShow(seatLabels(2), null);
        String t = token("metrics-user");
        reserve(show, t, List.of("S1"), "m1");
        reserve(show, t, List.of("S1"), "m1");

        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/actuator/prometheus")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body())
                .contains("reservations_confirmed_total")
                .contains("reservations_declined_total{application=\"seat-reservation\",reason=\"seat_taken\"}")
                .contains("reservations_declined_total{application=\"seat-reservation\",reason=\"idempotent_replay\"}")
                .contains("seats_available");
    }

    @Test
    void floatMoneyIsRejected() {
        Resp r = post("/shows", Map.of("name", "x", "seats", List.of("A1"), "price_paise", 250.5),
                Map.of("X-Admin-Key", "test-admin"));
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    void createShowRequiresAdminKey() {
        Resp r = post("/shows", Map.of("name", "x", "seats", List.of("A1"), "price_paise", 100), Map.of());
        assertThat(r.status()).isEqualTo(401);
    }
}
