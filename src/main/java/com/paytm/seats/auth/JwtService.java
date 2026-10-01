package com.paytm.seats.auth;

import com.paytm.seats.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {

    private static final String ISSUER = "seat-reservation";

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(AppProperties props) {
        // Hash the configured secret so any length yields a valid 256-bit HMAC key.
        this.key = Keys.hmacShaKeyFor(sha256(props.jwtSecret()));
        this.ttl = Duration.ofHours(props.jwtTtlHours());
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public IssuedToken issue(String userId) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, exp);
    }

    /** Returns the user id (subject) if the token is validly signed, unexpired and ours. */
    public Optional<String> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String sub = claims.getSubject();
            return sub == null || sub.isBlank() ? Optional.empty() : Optional.of(sub);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
