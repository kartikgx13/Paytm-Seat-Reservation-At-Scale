package com.paytm.seats;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationConcurrencyTest extends IntegrationTestBase {

    @Test
    void hotSeatStormHasExactlyOneWinner() throws Exception {
        String show = createShow(seatLabels(50), null);
        int n = 300;
        List<String> tokens = IntStream.range(0, n).mapToObj(i -> token("hot-" + i)).toList();

        List<Resp> results = stampede(n, i -> reserve(show, tokens.get(i), List.of("S7"), "k-" + i));

        long created = results.stream().filter(r -> r.status() == 201).count();
        long seatTaken = results.stream().filter(r -> r.status() == 409 && "seat_taken".equals(r.error())).count();
        assertThat(results).noneMatch(r -> r.status() >= 500);
        assertThat(created).isEqualTo(1);
        assertThat(seatTaken).isEqualTo(n - 1);

        JsonNode c = counts(show);
        assertThat(c.get("confirmed").asInt()).isEqualTo(1);
        assertThat(c.get("available").asInt() + c.get("held").asInt() + c.get("confirmed").asInt())
                .isEqualTo(c.get("total_seats").asInt());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE show_id = ?::uuid AND status = 'confirmed'",
                Integer.class, show)).isEqualTo(1);
    }

    @Test
    void perUserLimitHoldsUnderParallelRequests() throws Exception {
        String show = createShow(seatLabels(20), 4);
        String t = token("greedy");

        List<Resp> results = stampede(10, i -> reserve(show, t, List.of("S" + (i + 1)), "g-" + i));

        assertThat(results).noneMatch(r -> r.status() >= 500);
        assertThat(results.stream().filter(r -> r.status() == 201).count()).isEqualTo(4);
        assertThat(results.stream().filter(r -> "per_user_limit".equals(r.error())).count()).isEqualTo(6);
        assertThat(counts(show).get("confirmed").asInt()).isEqualTo(4);
    }

    @Test
    void sameKeyRetriedInParallelReservesOnce() throws Exception {
        String show = createShow(seatLabels(10), null);
        String t = token("retrier");

        List<Resp> results = stampede(40, i -> reserve(show, t, List.of("S1", "S2"), "same-key"));

        assertThat(results).noneMatch(r -> r.status() >= 500);
        assertThat(results.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.status() == 200).count()).isEqualTo(39);
        Set<String> ids = new HashSet<>();
        results.forEach(r -> ids.add(r.body().get("reservation_id").asText()));
        assertThat(ids).hasSize(1);
        assertThat(counts(show).get("confirmed").asInt()).isEqualTo(2);

        Resp different = reserve(show, t, List.of("S3"), "same-key");
        assertThat(different.status()).isEqualTo(409);
        assertThat(different.error()).isEqualTo("idempotency_key_reused");
        assertThat(counts(show).get("confirmed").asInt()).isEqualTo(2);
    }

    @Test
    void overlappingMultiSeatRequestsNeverDeadlockOrDoubleSell() throws Exception {
        List<String> labels = seatLabels(12);
        String show = createShow(labels, 4);
        int n = 200;
        List<String> tokens = IntStream.range(0, n).mapToObj(i -> token("multi-" + i)).toList();
        Random rnd = new Random(42);
        List<List<String>> wants = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<String> shuffled = new ArrayList<>(labels);
            Collections.shuffle(shuffled, rnd);
            wants.add(shuffled.subList(0, 2 + rnd.nextInt(2)));
        }

        List<Resp> results = stampede(n, i -> reserve(show, tokens.get(i), wants.get(i), "m-" + i));

        assertThat(results).noneMatch(r -> r.status() >= 500);
        Map<String, String> owner = new HashMap<>();
        int seatsWon = 0;
        for (Resp r : results) {
            if (r.status() == 201) {
                for (JsonNode s : r.body().get("seats")) {
                    String prev = owner.put(s.asText(), r.body().get("user_id").asText());
                    assertThat(prev).as("seat %s sold twice", s.asText()).isNull();
                    seatsWon++;
                }
            } else {
                assertThat(r.status()).isEqualTo(409);
            }
        }
        JsonNode c = counts(show);
        assertThat(c.get("confirmed").asInt()).isEqualTo(seatsWon);
        assertThat(c.get("available").asInt() + c.get("confirmed").asInt()).isEqualTo(labels.size());
    }

    @Test
    void cancelIsOwnerOnlyAndNeverResurrectsAResoldSeat() {
        String show = createShow(seatLabels(5), null);
        String alice = token("alice");
        String bob = token("bob");

        Resp a = reserve(show, alice, List.of("S1"), "a1");
        assertThat(a.status()).isEqualTo(201);
        String rid = a.body().get("reservation_id").asText();

        assertThat(post("/reservations/" + rid + "/cancel", null, bearer(bob)).status()).isEqualTo(404);
        assertThat(counts(show).get("confirmed").asInt()).isEqualTo(1);

        Resp cancelled = post("/reservations/" + rid + "/cancel", null, bearer(alice));
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asText()).isEqualTo("cancelled");
        assertThat(counts(show).get("available").asInt()).isEqualTo(5);

        assertThat(reserve(show, bob, List.of("S1"), "b1").status()).isEqualTo(201);
        assertThat(post("/reservations/" + rid + "/cancel", null, bearer(alice)).status()).isEqualTo(200);
        JsonNode c = counts(show);
        assertThat(c.get("confirmed").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_id FROM seats WHERE show_id = ?::uuid AND label = 'S1'",
                String.class, show)).isEqualTo("bob");
    }

    @Test
    void cancelRacingRebookKeepsInvariant() throws Exception {
        String show = createShow(seatLabels(3), null);
        String owner = token("owner");
        Resp a = reserve(show, owner, List.of("S1"), "o1");
        String rid = a.body().get("reservation_id").asText();
        int n = 100;
        List<String> tokens = IntStream.range(0, n).mapToObj(i -> token("racer-" + i)).toList();

        List<Resp> results = stampede(n + 1, i -> i == n
                ? post("/reservations/" + rid + "/cancel", null, bearer(owner))
                : reserve(show, tokens.get(i), List.of("S1"), "r-" + i));

        assertThat(results).noneMatch(r -> r.status() >= 500);
        long winners = results.subList(0, n).stream().filter(r -> r.status() == 201).count();
        assertThat(winners).isLessThanOrEqualTo(1);
        JsonNode c = counts(show);
        assertThat(c.get("available").asInt() + c.get("confirmed").asInt()).isEqualTo(3);
        assertThat(c.get("confirmed").asInt()).isEqualTo((int) winners);
    }

    @Test
    void identityComesFromTokenNotBody() {
        String show = createShow(seatLabels(3), null);
        String mallory = token("mallory");
        Resp r = post("/shows/" + show + "/reserve",
                Map.of("seats", List.of("S1"), "idempotency_key", UUID.randomUUID().toString(), "user_id", "victim"),
                bearer(mallory));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("user_id").asText()).isEqualTo("mallory");

        Resp noToken = post("/shows/" + show + "/reserve", Map.of("seats", List.of("S2"), "idempotency_key", "x"), Map.of());
        assertThat(noToken.status()).isEqualTo(401);
        Resp forged = post("/shows/" + show + "/reserve", Map.of("seats", List.of("S2"), "idempotency_key", "x"),
                bearer(mallory + "tampered"));
        assertThat(forged.status()).isEqualTo(401);
    }

    @Test
    void partialAvailabilityIsAllOrNothing() {
        String show = createShow(seatLabels(5), null);
        assertThat(reserve(show, token("first"), List.of("S2"), "f").status()).isEqualTo(201);

        Resp r = reserve(show, token("second"), List.of("S1", "S2"), "s");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.error()).isEqualTo("seat_taken");
        assertThat(r.body().get("unavailable_seats").get(0).asText()).isEqualTo("S2");
        assertThat(counts(show).get("confirmed").asInt()).isEqualTo(1);
    }
}
