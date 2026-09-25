package com.demo.resortslite;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.AddrUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * BookingController — stateless JWT-based session management.
 *
 * cz-java-0063 fix: Replaced HttpSession (server-side session storage) with
 * stateless JWT tokens.  The signing secret is injected from the
 * JWT_SECRET environment variable, which is populated by AWS Secrets Manager
 * into the ECS Fargate task definition at runtime.  No session state is held
 * in container memory, so horizontal scaling and container restarts are safe.
 *
 * cz-java-0069 fix (Lines 34–35 in original source): In-memory session storage
 * (session.setAttribute calls) removed.  As a low-effort transitional measure,
 * ALB target group stickiness (duration-based sticky sessions) is enabled on the
 * ECS Fargate service so that a given client is consistently routed to the same
 * task while the full stateless migration is completed.
 *
 * ECS / ALB Stickiness configuration (apply via IaC / AWS Console):
 *   - Target Group → Attributes → Stickiness: Enabled
 *   - Stickiness type: Load balancer generated cookie
 *   - Stickiness duration: 86400 seconds (1 day) — tune to match session TTL
 *   - ECS Service: set minimumHealthyPercent=100 during deployments to avoid
 *     draining sticky sessions prematurely.
 * Spring-side: server.servlet.session.cookie.same-site=Lax ensures the ALB
 * stickiness cookie (AWSALB) is forwarded correctly from modern browsers.
 *
 * cz-java-0070 fix (Line 19 in original source): Replaced local in-memory
 * HashMap cache (instance-local, invisible to other ECS Fargate tasks) with
 * Amazon ElastiCache for Memcached.  The Memcached endpoint is injected via
 * the MEMCACHED_ENDPOINT environment variable, which is populated from AWS SSM
 * Parameter Store into the ECS Fargate task definition at runtime.  This
 * ensures all horizontally-scaled container instances share the same distributed
 * cache, eliminating cache inconsistency across replicas.
 *
 * AWS SSM Parameter Store key: /resortsLite/cache/memcachedEndpoint
 * ECS Fargate task definition environment variable: MEMCACHED_ENDPOINT
 * Example value: resortsLite-cache.abc123.cfg.use1.cache.amazonaws.com:11211
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // EFS-backed mount path resolved via environment variable for ECS Fargate portability (cz-java-0057 fix)
    @Value("${REPORT_BASE_PATH:/mnt/efs/reports}")
    private String reportBasePath;

    /**
     * cz-java-0063: JWT signing secret injected from the JWT_SECRET environment
     * variable (populated by AWS Secrets Manager via ECS Fargate task definition).
     * Falls back to a 256-bit placeholder for local development only.
     */
    @Value("${JWT_SECRET:changeme-local-dev-secret-256bit!!}")
    private String jwtSecret;

    /**
     * cz-java-0070: Memcached endpoint injected from the MEMCACHED_ENDPOINT
     * environment variable.  In ECS Fargate this variable is sourced from AWS SSM
     * Parameter Store (/resortsLite/cache/memcachedEndpoint) via the task
     * definition's "secrets" or "environment" block.
     * Format: <host>:<port>  e.g. resortsLite-cache.abc123.cfg.use1.cache.amazonaws.com:11211
     * Falls back to localhost:11211 for local development only.
     */
    @Value("${MEMCACHED_ENDPOINT:localhost:11211}")
    private String memcachedEndpoint;

    /** Token validity: 1 hour (3 600 000 ms). */
    private static final long JWT_EXPIRY_MS = 3_600_000L;

    /** Cache entry TTL: 1 hour (3 600 seconds). */
    private static final int CACHE_TTL_SECONDS = 3_600;

    /**
     * cz-java-0070 fix: Distributed Memcached client backed by Amazon ElastiCache.
     * Replaces the former instance-local HashMap bookingCache.  All ECS Fargate
     * tasks share the same ElastiCache cluster, so cached entries are visible
     * across every horizontally-scaled replica.
     */
    private MemcachedClient memcachedClient;

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    /**
     * cz-java-0070: Initialise the Memcached client after Spring injects the
     * MEMCACHED_ENDPOINT value.  Connection failures are logged but do not
     * prevent the application from starting — cache misses fall through to the
     * backing service.
     */
    @PostConstruct
    public void initMemcachedClient() {
        try {
            memcachedClient = new MemcachedClient(AddrUtil.getAddresses(memcachedEndpoint));
        } catch (IOException e) {
            // Log and continue — cache is best-effort; the application remains functional
            System.err.println("[cz-java-0070] WARNING: Could not connect to Memcached at "
                    + memcachedEndpoint + ": " + e.getMessage());
            memcachedClient = null;
        }
    }

    /**
     * cz-java-0070: Gracefully shut down the Memcached connection when the
     * container stops, ensuring clean resource release during ECS task draining.
     */
    @PreDestroy
    public void shutdownMemcachedClient() {
        if (memcachedClient != null) {
            memcachedClient.shutdown();
        }
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /** Build a HMAC-SHA256 signing key from the injected secret. */
    private Key signingKey() {
        byte[] keyBytes = jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // Pad / truncate to exactly 32 bytes (256 bits) required by HS256
        byte[] key256 = new byte[32];
        System.arraycopy(keyBytes, 0, key256, 0, Math.min(keyBytes.length, 32));
        return Keys.hmacShaKeyFor(key256);
    }

    /**
     * cz-java-0070: Store a booking entry in the distributed Memcached cache.
     * No-ops gracefully if the Memcached client is unavailable.
     */
    private void cacheBooking(String bookingId, Object booking) {
        if (memcachedClient != null) {
            memcachedClient.set("booking:" + bookingId, CACHE_TTL_SECONDS, booking);
        }
    }

    /**
     * cz-java-0070: Retrieve a booking entry from the distributed Memcached cache.
     * Returns null on cache miss or if the client is unavailable.
     */
    private Object getCachedBooking(String bookingId) {
        if (memcachedClient != null) {
            return memcachedClient.get("booking:" + bookingId);
        }
        return null;
    }

    /**
     * cz-java-0063: Issue a stateless JWT carrying the guest context that was
     * previously stored in HttpSession.  The token is returned to the caller
     * and must be presented on subsequent requests — no server-side state is
     * retained between requests.
     */
    private String issueBookingToken(String guestName, String bookingId) {
        return Jwts.builder()
                .setSubject(guestName)
                .claim("bookingId", bookingId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + JWT_EXPIRY_MS))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * cz-java-0063: Parse and validate a JWT, returning its claims.
     * Throws a JwtException if the token is invalid or expired.
     */
    private Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    // ---------------------------------------------------------------------------
    // Endpoints
    // ---------------------------------------------------------------------------

    /**
     * cz-java-0063 fix (Lines 27–27): Removed HttpSession parameter.
     * Guest context is now encoded in a signed JWT returned in the response
     * instead of being stored in server-side session memory.
     *
     * cz-java-0070 fix: Booking result is stored in distributed ElastiCache
     * Memcached (via cacheBooking helper) instead of the former instance-local
     * HashMap, ensuring cache consistency across all ECS Fargate replicas.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0063: Guest context is embedded in a stateless JWT instead of
        // being stored in HttpSession.  The token travels with the client and is
        // verified on each request — no container-local state is created.
        String bookingToken = issueBookingToken(guestName, (String) booking.get("bookingId"));

        // cz-java-0070: Store booking in distributed ElastiCache Memcached so all
        // horizontally-scaled ECS Fargate tasks can access the cached entry.
        cacheBooking((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        // Return the JWT so the client can present it on subsequent calls
        response.put("bookingToken", bookingToken);
        return response;
    }

    /**
     * cz-java-0063 fix (Lines 48–48): Removed HttpSession parameter.
     * Guest identity is now resolved from the Bearer JWT supplied in the
     * Authorization header instead of being read from server-side session memory.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // cz-java-0063: Resolve guest name from the stateless JWT presented by
        // the client.  If no token is provided the field is left null, which is
        // the same observable behaviour as a missing/expired session attribute.
        String lastGuest = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                Claims claims = parseToken(authHeader.substring(7));
                lastGuest = claims.getSubject();
            } catch (Exception e) {
                // Invalid / expired token — treat as unauthenticated (no session state)
                lastGuest = null;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP call to
        // internal inventory service. AWS ALB, WAF, and Well-Architected security review
        // enforce HTTPS. This call will be blocked or flagged in a cloud-native setup.
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available"; // cr-java-0088

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // EFS-backed path resolved from REPORT_BASE_PATH environment variable (cz-java-0057 fix)
        // Replaces hardcoded "/var/legacy/reports/" with ECS Fargate EFS volume mount path
        String reportPath = Paths.get(reportBasePath, month + "_bookings.pdf").toString();

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
