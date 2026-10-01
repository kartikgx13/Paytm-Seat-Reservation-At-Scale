package com.paytm.seats.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Demo token issuer so testers can mint identities. In production this is the job of an external IdP;
 * the reservation API itself only ever verifies tokens.
 */
@RestController
public class AuthController {

    public record TokenRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_.:@-]{1,64}$") String userId) {
    }

    public record TokenResponse(String token, String userId, Instant expiresAt) {
    }

    private final JwtService jwt;

    public AuthController(JwtService jwt) {
        this.jwt = jwt;
    }

    @PostMapping("/auth/token")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse token(@Valid @RequestBody TokenRequest req) {
        JwtService.IssuedToken t = jwt.issue(req.userId());
        return new TokenResponse(t.token(), req.userId(), t.expiresAt());
    }
}
