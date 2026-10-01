package com.paytm.seats.auth;

import com.paytm.seats.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static String id(HttpServletRequest req) {
        Object id = req.getAttribute(AuthInterceptor.USER_ATTR);
        if (id == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "authentication required");
        }
        return (String) id;
    }
}
