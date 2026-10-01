package com.paytm.seats.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class IndexController {

    @GetMapping("/")
    public Map<String, Object> index() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", "seat-reservation");
        m.put("endpoints", Map.of(
                "token", "POST /auth/token {\"user_id\":\"u1\"}",
                "create_show", "POST /shows (X-Admin-Key)",
                "show", "GET /shows/{id}",
                "reserve", "POST /shows/{id}/reserve (Bearer, Idempotency-Key)",
                "cancel", "POST /reservations/{id}/cancel (Bearer)",
                "liveness", "GET /actuator/health/liveness",
                "readiness", "GET /actuator/health/readiness",
                "metrics", "GET /actuator/prometheus"));
        return m;
    }
}
