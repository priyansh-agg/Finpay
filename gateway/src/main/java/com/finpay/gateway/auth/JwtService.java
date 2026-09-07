package com.finpay.gateway.auth;

import com.finpay.gateway.common.error.ApiException;
import com.finpay.gateway.identity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMinutes;
    private final String issuer;

    public JwtService(@Value("${finpay.jwt.secret}") String secret,
                      @Value("${finpay.jwt.ttl-minutes}") long ttlMinutes,
                      @Value("${finpay.jwt.issuer}") String issuer) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("finpay.jwt.secret must be >= 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlMinutes = ttlMinutes;
        this.issuer = issuer;
    }

    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(user.getId().toString())
                .claim("role", user.getRole())
                .claim("merchantId", user.getMerchantId() == null ? null : user.getMerchantId().toString())
                .claim("email", user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public FinPayPrincipal.UserCaller parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).requireIssuer(issuer).build()
                    .parseSignedClaims(token).getPayload();
            String merchantId = c.get("merchantId", String.class);
            return new FinPayPrincipal.UserCaller(
                    UUID.fromString(c.getSubject()),
                    merchantId == null ? null : UUID.fromString(merchantId),
                    c.get("email", String.class),
                    c.get("role", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("Invalid or expired token");
        }
    }
}
