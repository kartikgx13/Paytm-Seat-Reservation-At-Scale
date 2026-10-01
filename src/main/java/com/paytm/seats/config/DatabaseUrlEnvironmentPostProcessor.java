package com.paytm.seats.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates a platform-style DATABASE_URL (postgres://user:pass@host:port/db) into Spring datasource
 * properties. Render, Railway and Heroku all inject this form; JDBC needs a different one.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String raw = env.getProperty("DATABASE_URL");
        if (raw == null || raw.isBlank() || raw.startsWith("jdbc:")) {
            return;
        }
        URI uri = URI.create(raw);
        Map<String, Object> props = new HashMap<>();
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
        props.put("spring.datasource.url", "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + query);
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            String[] parts = userInfo.split(":", 2);
            props.put("spring.datasource.username", URLDecoder.decode(parts[0], StandardCharsets.UTF_8));
            if (parts.length > 1) {
                props.put("spring.datasource.password", URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        env.getPropertySources().addFirst(new MapPropertySource("databaseUrl", props));
    }
}
