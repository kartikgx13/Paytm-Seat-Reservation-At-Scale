package com.paytm.seats.auth;

import com.paytm.seats.config.AppProperties;
import com.paytm.seats.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Establishes the caller's identity from the Authorization header only. Nothing in the request body can
 * influence who the caller is; controllers read the user id exclusively via {@link CurrentUser}.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    static final String USER_ATTR = "auth.userId";

    private final JwtService jwt;
    private final byte[] adminKey;

    public AuthInterceptor(JwtService jwt, AppProperties props) {
        this.jwt = jwt;
        this.adminKey = props.adminApiKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if ("OPTIONS".equals(req.getMethod())) {
            return true;
        }
        String path = req.getRequestURI();
        if ("POST".equals(req.getMethod()) && "/shows".equals(path)) {
            requireAdmin(req);
            return true;
        }
        String header = req.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "missing bearer token");
        }
        String userId = jwt.verify(header.substring(7).trim())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "invalid or expired token"));
        req.setAttribute(USER_ATTR, userId);
        MDC.put("userId", userId);
        return true;
    }

    private void requireAdmin(HttpServletRequest req) {
        String provided = req.getHeader("X-Admin-Key");
        if (provided == null || !MessageDigest.isEqual(adminKey, provided.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "admin key required");
        }
    }
}
