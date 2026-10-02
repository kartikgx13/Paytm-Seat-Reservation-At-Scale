package com.paytm.seats.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String jwtSecret,
        int jwtTtlHours,
        String adminApiKey,
        int defaultPerUserLimit,
        int lockTimeoutMs,
        long gaugeRefreshMs,
        int admissionMaxConcurrent,
        long admissionMaxWaitMs) {
}
