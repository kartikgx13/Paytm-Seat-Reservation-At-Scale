package com.paytm.seats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/** Boots the real app on a random port against a throwaway Postgres and drives it over HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.admin-api-key=test-admin", "spring.datasource.hikari.maximum-pool-size=20"})
@ActiveProfiles("plain")
public abstract class IntegrationTestBase {

    // Started once per JVM and shared by every test class, matching Spring's cached application context.
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "max_connections=200");

    static {
        POSTGRES.start();
    }

    protected static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    protected JdbcTemplate jdbc;

    protected HttpClient http;

    @BeforeEach
    void setUpClient() {
        http = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record Resp(int status, JsonNode body) {
        public String error() {
            return body.path("error").asText(null);
        }
    }

    protected Resp post(String path, Object body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : JSON.writeValueAsString(body)));
            headers.forEach(b::header);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(r.body()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected Resp get(String path, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60)).GET();
            headers.forEach(b::header);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), JSON.readTree(r.body()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected String createShow(List<String> seats, Integer perUserLimit) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", "test", "seats", seats, "price_paise", 25000));
        if (perUserLimit != null) {
            body.put("per_user_limit", perUserLimit);
        }
        Resp r = post("/shows", body, Map.of("X-Admin-Key", "test-admin"));
        if (r.status() != 201) {
            throw new IllegalStateException("create show failed: " + r);
        }
        return r.body().get("id").asText();
    }

    protected static List<String> seatLabels(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            out.add("S" + i);
        }
        return out;
    }

    protected String token(String userId) {
        return post("/auth/token", Map.of("user_id", userId), Map.of()).body().get("token").asText();
    }

    protected Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    protected Resp reserve(String showId, String token, List<String> seats, String key) {
        return post("/shows/" + showId + "/reserve", Map.of("seats", seats, "idempotency_key", key), bearer(token));
    }

    protected JsonNode counts(String showId) {
        return get("/shows/" + showId, Map.of()).body().get("counts");
    }

    /** Runs n tasks that all start at the same instant (latch-gated) and returns their results. */
    protected <T> List<T> stampede(int n, IntFunction<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(ex.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.apply(idx);
                }));
            }
            ready.await();
            go.countDown();
            List<T> out = new ArrayList<>(n);
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }
}
