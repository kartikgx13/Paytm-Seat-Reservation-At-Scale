package com.paytm.seats.config;

import com.paytm.seats.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor auth;

    public WebConfig(AuthInterceptor auth) {
        this.auth = auth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // GET /shows/{id} is public; creating shows is admin-only; everything that acts as a user needs a token.
        registry.addInterceptor(auth)
                .addPathPatterns("/shows", "/shows/*/reserve", "/reservations/**", "/me/**");
    }
}
