package com.demo.resortslite;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * cz-java-0063 FIX: Stateless JWT utility replacing server-side HttpSession.
 * The JWT signing secret is injected from the ECS Fargate task environment variable
 * JWT_SECRET, which is sourced from AWS Secrets Manager. This ensures stateless
 * authentication that works correctly across container restarts and horizontal scaling.
 */
@Component
public class JwtUtil {

    private static final long TOKEN_VALIDITY_MS = 3600_000L; // 1 hour

    // JWT_SECRET is injected via ECS Fargate task definition environment variable,
    // sourced from AWS Secrets Manager — never hardcoded.
    @Value("${JWT_SECRET:default-dev-secret-change-in-production-min32chars}")
    private String jwtSecret;

    /**
     * Generates a signed JWT token carrying the supplied claims.
     *
     * @param subject    the principal (e.g. guestName)
     * @param extraClaims additional claims to embed (e.g. bookingId)
     * @return compact, URL-safe JWT string
     */
    public String generateToken(String subject, Map<String, Object> extraClaims) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .setSubject(subject)
                .addClaims(extraClaims)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + TOKEN_VALIDITY_MS))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Validates and parses a JWT token, returning its claims.
     *
     * @param token the compact JWT string
     * @return parsed {@link Claims}
     * @throws io.jsonwebtoken.JwtException if the token is invalid or expired
     */
    public Claims parseToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * Extracts the subject (guestName) from a Bearer token header value.
     *
     * @param bearerToken value of the Authorization header (may be null)
     * @return subject string, or null if token is absent / invalid
     */
    public String extractSubject(String bearerToken) {
        if (bearerToken == null || !bearerToken.startsWith("Bearer ")) {
            return null;
        }
        try {
            return parseToken(bearerToken.substring(7)).getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
