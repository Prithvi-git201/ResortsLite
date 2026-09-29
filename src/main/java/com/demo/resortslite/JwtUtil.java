package com.demo.resortslite;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.Map;

/**
 * Stateless JWT utility — signing secret is injected from the JWT_SECRET
 * environment variable, which ECS Fargate resolves from AWS Secrets Manager
 * at task startup. No server-side session state is maintained.
 */
@Component
public class JwtUtil {

    private static final long TOKEN_VALIDITY_MS = 3_600_000L; // 1 hour

    /**
     * JWT signing secret injected via environment variable.
     * In ECS Fargate, set JWT_SECRET as a secret reference pointing to
     * AWS Secrets Manager so the value is never stored in the task definition.
     */
    @Value("${JWT_SECRET:default-dev-secret-change-in-production}")
    private String jwtSecret;

    private Key signingKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        // Ensure key is at least 256 bits for HMAC-SHA256
        if (keyBytes.length < 32) {
            keyBytes = java.util.Arrays.copyOf(keyBytes, 32);
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generate a signed JWT token embedding the supplied claims.
     *
     * @param claims arbitrary key/value pairs to embed in the token payload
     * @return compact, URL-safe JWT string
     */
    public String generateToken(Map<String, Object> claims) {
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + TOKEN_VALIDITY_MS))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Parse and validate a JWT token, returning its claims.
     *
     * @param token compact JWT string
     * @return parsed {@link Claims}
     * @throws io.jsonwebtoken.JwtException if the token is invalid or expired
     */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * Extract a single claim value from a raw JWT string.
     *
     * @param token     compact JWT string
     * @param claimName name of the claim to retrieve
     * @return claim value, or {@code null} if absent
     */
    public String extractClaim(String token, String claimName) {
        try {
            Claims claims = parseToken(token);
            Object value = claims.get(claimName);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
