package com.finpay.gateway.auth;

import com.finpay.gateway.common.error.GlobalExceptionHandler.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.identity.ApiKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Resolves the caller from either:
 *   Authorization: Bearer &lt;jwt&gt;         -> dashboard user
 *   Authorization: ApiKey &lt;fp_live_...&gt; -> merchant
 *   X-API-Key: &lt;fp_live_...&gt;             -> merchant (convenience)
 * and installs a {@link FinPayPrincipal} + ROLE_ authority into the SecurityContext.
 */
@Component
public class FinPayAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;
    private final JwtService jwtService;
    private final ObjectMapper mapper;

    public FinPayAuthFilter(ApiKeyService apiKeyService, JwtService jwtService, ObjectMapper mapper) {
        this.apiKeyService = apiKeyService;
        this.jwtService = jwtService;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        try {
            resolvePrincipal(request);
            chain.doFilter(request, response);
        } catch (ApiException e) {
            SecurityContextHolder.clearContext();
            response.setStatus(e.getStatus().value());
            response.setContentType("application/json");
            mapper.writeValue(response.getWriter(), new ApiErrorResponse(
                    e.getCode(), e.getMessage(), null, request.getRequestURI(), Instant.now()));
        }
    }

    private void resolvePrincipal(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String xApiKey = request.getHeader("X-API-Key");

        FinPayPrincipal principal = null;
        if (header != null && header.startsWith("Bearer ")) {
            principal = jwtService.parse(header.substring(7).trim());
        } else if (header != null && header.startsWith("ApiKey ")) {
            principal = fromApiKey(header.substring(7).trim());
        } else if (xApiKey != null && !xApiKey.isBlank()) {
            principal = fromApiKey(xApiKey.trim());
        }

        if (principal != null) {
            var auth = new UsernamePasswordAuthenticationToken(principal, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
    }

    private FinPayPrincipal fromApiKey(String raw) {
        ApiKey key = apiKeyService.authenticate(raw);
        return new FinPayPrincipal.MerchantCaller(key.getMerchantId(), key.getId(), key.getRole());
    }
}
