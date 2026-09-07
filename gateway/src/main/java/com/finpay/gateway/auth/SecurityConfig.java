package com.finpay.gateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.common.error.GlobalExceptionHandler.ApiErrorResponse;
import com.finpay.gateway.common.ratelimit.RateLimitFilter;
import com.finpay.gateway.common.ratelimit.RateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Instant;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, FinPayAuthFilter authFilter,
                                    RateLimiter rateLimiter, ObjectMapper mapper) throws Exception {
        RateLimitFilter rateLimitFilter = new RateLimitFilter(rateLimiter, mapper);
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus", "/actuator/metrics/**").permitAll()
                        .requestMatchers("/v1/auth/login").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/v1/webhooks/receiver/**").permitAll() // demo webhook sink
                        .requestMatchers("/v1/checkout/**").permitAll() // customer-facing payment link, no login
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setStatus(401);
                            res.setContentType("application/json");
                            mapper.writeValue(res.getWriter(), new ApiErrorResponse(
                                    "UNAUTHORIZED", "Authentication required", null, req.getRequestURI(), Instant.now()));
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            res.setStatus(403);
                            res.setContentType("application/json");
                            mapper.writeValue(res.getWriter(), new ApiErrorResponse(
                                    "FORBIDDEN", "Insufficient role", null, req.getRequestURI(), Instant.now()));
                        }))
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, FinPayAuthFilter.class);
        return http.build();
    }
}
