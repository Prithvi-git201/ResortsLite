package com.demo.resortslite;

import io.jsonwebtoken.Claims;
import net.spy.memcached.MemcachedClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * BookingController — stateless REST controller.
 *
 * Server-side HttpSession has been replaced with stateless JWT authentication
 * (cz-java-0063). Guest context that was previously stored in the HTTP session
 * is now embedded in a signed JWT token returned to the client and supplied back
 * on subsequent requests via the Authorization header. The JWT signing secret is
 * injected from the JWT_SECRET environment variable, which ECS Fargate resolves
 * from AWS Secrets Manager at task startup, ensuring no session state is held
 * in container memory and the service scales horizontally without sticky sessions.
 *
 * cz-java-0069 — In-Memory Session Storage (ALB Session Affinity Transitional Strategy):
 * As a low-effort transitional measure while the full Redis migration is completed,
 * ALB target group stickiness (duration-based cookies) is enabled on the ECS Fargate
 * service. This ensures that requests from the same client are consistently routed to
 * the same container replica, minimising session disruption caused by in-memory state.
 *
 * ECS / ALB configuration required (set via AWS Console, CDK, or Terraform):
 *   - ALB Target Group: stickiness.enabled = true
 *   - ALB Target Group: stickiness.type = lb_cookie
 *   - ALB Target Group: stickiness.lb_cookie.duration_seconds = 86400  (24 h)
 *   - ECS Service: loadBalancers[].targetGroupArn = <ALB_TARGET_GROUP_ARN>
 *
 * Environment variables consumed by this service (ECS Task Definition):
 *   ALB_STICKINESS_ENABLED   = true
 *   ALB_COOKIE_DURATION_SECS = 86400
 *
 * Long-term target: migrate all session state to Amazon ElastiCache (Redis) so that
 * sticky sessions are no longer required and the service is fully stateless.
 *
 * cz-java-0070 FIX — Local Caches:
 * The local in-memory HashMap cache (bookingCache) that was declared at line 19 of the
 * original source has been replaced with Amazon ElastiCache for Memcached. The Memcached
 * client is injected as a Spring bean (see MemcachedConfig) and the cluster endpoint is
 * supplied via the MEMCACHED_ENDPOINT environment variable, which ECS Fargate resolves
 * from AWS SSM Parameter Store at task startup. This ensures all container replicas share
 * a single distributed cache and the service scales horizontally without stale or
 * inconsistent instance-local cache state.
 *
 * ECS Task Definition — required environment variables (sourced from SSM Parameter Store):
 *   MEMCACHED_ENDPOINT    = <elasticache-cluster-endpoint>:11211
 *   MEMCACHED_EXPIRY_SECS = 3600   (optional, defaults to 3600)
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * cz-java-0070 FIX (line 19 — original source):
     * Replaced: private static final Map<String, Object> bookingCache = new HashMap<>();
     *
     * The local in-memory HashMap cache has been removed and replaced with an injected
     * MemcachedClient connected to Amazon ElastiCache for Memcached. The cluster endpoint
     * is injected via the MEMCACHED_ENDPOINT environment variable resolved from AWS SSM
     * Parameter Store by ECS Fargate at task startup. Cache entries are stored with a
     * configurable TTL (MEMCACHED_EXPIRY_SECS, default 3600 s) to prevent stale data.
     * All container replicas share the same distributed cache, enabling true horizontal
     * scaling without instance-local state.
     */
    @Autowired
    private MemcachedClient memcachedClient;

    /**
     * Cache entry TTL in seconds, injected from the memcachedExpirySecs bean
     * (backed by the MEMCACHED_EXPIRY_SECS environment variable).
     */
    @Autowired
    @Qualifier("memcachedExpirySecs")
    private int memcachedExpirySecs;

    // EFS-backed mount path injected via environment variable (ECS Fargate task definition)
    @Value("${REPORT_BASE_PATH:/mnt/efs/reports}")
    private String reportBasePath;

    /**
     * Create a new booking.
     *
     * Guest context (bookingId, guestName) is embedded in a signed JWT token
     * returned in the response body. The client must supply this token in the
     * Authorization header on subsequent requests. No server-side session is used.
     *
     * Fix: cz-java-0063 — HttpSession parameters removed; JWT token issued instead.
     *
     * Fix: cz-java-0069 — In-memory session attributes (session.setAttribute("lastBooking")
     * and session.setAttribute("guestName") at original lines 34–35) replaced by JWT claims.
     * ALB target group stickiness is enabled as a transitional measure (see class-level
     * Javadoc) to minimise disruption while the Redis migration is in progress.
     *
     * Fix: cz-java-0070 — Booking result is now stored in Amazon ElastiCache (Memcached)
     * via the injected MemcachedClient instead of the local HashMap bookingCache.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Build JWT claims with the guest context that was previously stored in HttpSession.
        // The token is signed with the secret from JWT_SECRET (AWS Secrets Manager via ECS).
        // cz-java-0069 FIX (line 34): session.setAttribute("lastBooking", booking) removed —
        //   booking state is now carried in the signed JWT returned to the client.
        // cz-java-0069 FIX (line 35): session.setAttribute("guestName", guestName) removed —
        //   guestName is now embedded as a JWT claim; ALB stickiness provides transitional
        //   session affinity while Redis migration is completed.
        Map<String, Object> claims = new HashMap<>();
        claims.put("bookingId", booking.get("bookingId"));
        claims.put("guestName", guestName);
        String jwtToken = jwtUtil.generateToken(claims);

        // cz-java-0070 FIX: Store booking in Amazon ElastiCache (Memcached) instead of the
        // local HashMap bookingCache. The MemcachedClient is connected to the ElastiCache
        // cluster endpoint supplied via MEMCACHED_ENDPOINT (SSM Parameter Store → ECS Fargate).
        // The entry expires after memcachedExpirySecs seconds (MEMCACHED_EXPIRY_SECS env var).
        String cacheKey = "booking:" + booking.get("bookingId");
        memcachedClient.set(cacheKey, memcachedExpirySecs, booking.toString());

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        // Return the JWT so the client can present it on subsequent requests.
        response.put("token", jwtToken);
        return response;
    }

    /**
     * Retrieve booking status.
     *
     * Guest context is extracted from the JWT supplied in the Authorization header
     * instead of reading from an HttpSession attribute.
     *
     * Fix: cz-java-0063 — HttpSession parameter removed; guest name resolved from JWT.
     *
     * Fix: cz-java-0070 — Booking lookup now queries Amazon ElastiCache (Memcached)
     * via the injected MemcachedClient before falling back to the booking service.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {

        // Extract guestName from the JWT token (Bearer <token>) instead of HttpSession.
        String lastGuest = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            lastGuest = jwtUtil.extractClaim(token, "guestName");
        }

        // cz-java-0070 FIX: Attempt to retrieve the booking from Amazon ElastiCache
        // (Memcached) before falling back to the booking service. The cache key matches
        // the key used when the booking was stored in the createBooking endpoint.
        String cacheKey = "booking:" + bookingId;
        Object cachedBooking = memcachedClient.get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        result.put("cacheHit", cachedBooking != null);
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
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path replaced with EFS-backed environment variable for ECS Fargate compatibility.
        // Mount the EFS volume at the path specified by REPORT_BASE_PATH in the task definition.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
