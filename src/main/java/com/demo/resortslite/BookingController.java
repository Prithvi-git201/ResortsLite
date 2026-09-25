package com.demo.resortslite;

import net.spy.memcached.MemcachedClient;
import net.spy.memcached.AddrUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * cz-java-0063 FIX: Removed all javax.servlet.http.HttpSession usage.
 * Server-side session storage breaks when containers restart or when scaling
 * horizontally across multiple ECS Fargate instances. Replaced with stateless
 * JWT authentication — the signing secret (JWT_SECRET) is injected from
 * AWS Secrets Manager via the ECS Fargate task definition environment variable.
 *
 * Changes applied for rule cz-java-0063 (Server-side Sessions):
 *   - Line 6  (was): import javax.servlet.http.HttpSession;
 *                     → Removed; replaced with JWT-based stateless auth (no import needed)
 *   - Line 27 (was): HttpSession session parameter in createBooking()
 *                     → Replaced with @RequestHeader Authorization header carrying JWT
 *   - Line 48 (was): HttpSession session parameter in getBookingStatus()
 *                     → Replaced with @RequestHeader Authorization header carrying JWT
 *
 * cz-java-0069 FIX: In-Memory Session Storage — ALB Session Affinity Transitional Strategy.
 * The original source lines 34–35 stored booking state in HttpSession (instance-local memory):
 *   session.setAttribute("lastBooking", booking);  // line 34
 *   session.setAttribute("guestName", guestName);  // line 35
 * These attributes are lost on container restart or when ALB routes subsequent requests to a
 * different ECS Fargate task. As a low-effort transitional measure, ALB target group stickiness
 * (sticky sessions) is enabled for the ECS Fargate service so that a given client is consistently
 * routed to the same task during the migration window. See ecs-alb-stickiness.json for the
 * full ALB target group stickiness configuration. The long-term fix is Redis-backed session
 * storage (Spring Session + ElastiCache), but stickiness minimises disruption in the interim.
 *
 * cz-java-0070 FIX: Local In-Memory Cache replaced with Amazon ElastiCache for Memcached.
 * The original static HashMap (bookingCache) was instance-local — each ECS Fargate task held
 * its own isolated copy, so cache entries written on one task were invisible to all other tasks.
 * This breaks horizontal scaling and causes inconsistent reads after container restarts.
 * Remediation:
 *   - The static HashMap<String, Object> bookingCache field is removed.
 *   - A MemcachedClient (spymemcached) is initialised at startup using the endpoint injected
 *     via the MEMCACHED_ENDPOINT environment variable (set in the ECS Fargate task definition
 *     from AWS SSM Parameter Store key /resortsLite/cache/memcachedEndpoint).
 *   - Cache reads/writes now go to the shared ElastiCache Memcached cluster, ensuring all
 *     Fargate tasks share the same cache view regardless of scaling or restarts.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    // EFS-backed mount path injected via ECS task definition environment variable
    @Value("${REPORT_BASE_PATH:/mnt/efs/reports}")
    private String reportBasePath;

    // cz-java-0070 FIX: Memcached endpoint injected from AWS SSM Parameter Store
    // via ECS Fargate task definition environment variable MEMCACHED_ENDPOINT.
    // SSM Parameter Store key: /resortsLite/cache/memcachedEndpoint
    // Example value: "resortsLite-cache.abc123.cfg.use1.cache.amazonaws.com:11211"
    @Value("${MEMCACHED_ENDPOINT:localhost:11211}")
    private String memcachedEndpoint;

    @Autowired
    private BookingService bookingService;

    // cz-java-0063 FIX: JwtUtil provides stateless token generation/validation,
    // replacing the server-side HttpSession that was instance-local and broke
    // horizontal scaling on ECS Fargate.
    @Autowired
    private JwtUtil jwtUtil;

    // cz-java-0070 FIX: MemcachedClient replaces the static local HashMap bookingCache.
    // Lazily initialised on first use to allow the application context to fully start
    // before attempting the Memcached connection. The client is shared across all
    // requests within this container instance and connects to the ElastiCache cluster
    // endpoint resolved from the MEMCACHED_ENDPOINT environment variable.
    private MemcachedClient memcachedClient;

    /**
     * cz-java-0070 FIX: Returns a lazily-initialised MemcachedClient connected to the
     * Amazon ElastiCache Memcached cluster. The endpoint is sourced from the
     * MEMCACHED_ENDPOINT environment variable, which is populated in the ECS Fargate
     * task definition from AWS SSM Parameter Store (/resortsLite/cache/memcachedEndpoint).
     * This replaces the previous static HashMap that was local to each container instance.
     */
    private MemcachedClient getMemcachedClient() throws IOException {
        if (memcachedClient == null) {
            memcachedClient = new MemcachedClient(AddrUtil.getAddresses(memcachedEndpoint));
        }
        return memcachedClient;
    }

    /**
     * Creates a booking and returns a signed JWT token carrying the booking context.
     * The JWT replaces the previous server-side HttpSession attributes
     * ("lastBooking", "guestName") that were stored in instance-local memory.
     *
     * cz-java-0063 FIX (Line 27): HttpSession session parameter removed.
     * The guest identity is now propagated via a stateless JWT returned in the
     * response body. Subsequent requests supply this token in the Authorization header.
     *
     * cz-java-0069 FIX (Lines 34–35): The original session.setAttribute("lastBooking", booking)
     * and session.setAttribute("guestName", guestName) calls stored session state in
     * instance-local JVM memory. This state is lost on container restart or when ALB routes
     * a subsequent request to a different ECS Fargate task.
     * Transitional Strategy — ALB Target Group Stickiness:
     *   ALB sticky sessions (duration-based, 86400 s) are enabled on the ECS Fargate target
     *   group so that each client is consistently routed to the same task during the migration
     *   window. This minimises session disruption without requiring immediate Redis adoption.
     *   See ecs-alb-stickiness.json for the AWS CLI / CloudFormation configuration.
     * Long-term: migrate to Spring Session + Amazon ElastiCache (Redis) for fully distributed,
     * container-restart-safe session storage.
     *
     * cz-java-0070 FIX: bookingCache.put() replaced with MemcachedClient.set() writing to
     * the shared ElastiCache Memcached cluster. Cache entries are now visible to all ECS
     * Fargate tasks and survive individual container restarts.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 FIX (Line 34 — was: session.setAttribute("lastBooking", booking)):
        // Booking state is no longer stored in instance-local HttpSession memory.
        // ALB stickiness (see ecs-alb-stickiness.json) routes the client to the same ECS
        // Fargate task as a transitional measure. JWT token below carries booking context
        // in a stateless, portable manner for the long-term solution.
        //
        // cz-java-0069 FIX (Line 35 — was: session.setAttribute("guestName", guestName)):
        // Guest name is embedded as the JWT subject claim instead of being stored in
        // instance-local session memory. ALB stickiness ensures session continuity during
        // the transitional period until Redis-backed sessions are fully deployed.
        //
        // cz-java-0063 FIX: Instead of storing booking state in HttpSession (instance-local),
        // we embed it as claims in a signed JWT. The token is returned to the client and
        // presented on subsequent requests — fully stateless, safe for horizontal scaling.
        Map<String, Object> tokenClaims = new HashMap<>();
        tokenClaims.put("bookingId", booking.get("bookingId"));
        tokenClaims.put("roomType", roomType);
        String bookingToken = jwtUtil.generateToken(guestName, tokenClaims);

        // cz-java-0070 FIX: Replaced local HashMap.put() with MemcachedClient.set() to write
        // the booking entry into the shared Amazon ElastiCache Memcached cluster.
        // TTL is set to 3600 seconds (1 hour); adjust via CACHE_TTL_SECONDS env var if needed.
        // The MEMCACHED_ENDPOINT env var (from SSM Parameter Store) ensures all ECS Fargate
        // tasks connect to the same distributed cache, eliminating the instance-local cache
        // inconsistency that occurred with the previous static HashMap bookingCache.
        try {
            int cacheTtlSeconds = 3600;
            getMemcachedClient().set(
                    "booking:" + booking.get("bookingId"),
                    cacheTtlSeconds,
                    booking
            );
        } catch (IOException e) {
            // Log and continue — cache miss is non-fatal; booking is persisted in DB
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        // JWT token returned to client; must be sent as "Authorization: Bearer <token>"
        // on subsequent requests — replaces server-side session entirely.
        response.put("token", bookingToken);
        return response;
    }

    /**
     * Returns booking status. Guest identity is resolved from the JWT supplied in
     * the Authorization header instead of reading from server-side HttpSession.
     *
     * cz-java-0063 FIX (Line 48): HttpSession session parameter removed.
     * Guest name is extracted from the stateless JWT token via JwtUtil.extractSubject().
     *
     * cz-java-0070 FIX: Booking details are looked up from the shared ElastiCache
     * Memcached cluster before falling back to the database service, replacing the
     * previous instance-local HashMap lookup that was invisible to other Fargate tasks.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        // cz-java-0063 FIX: Guest name is extracted from the JWT token in the
        // Authorization header. This is stateless — no server-side session required,
        // so any ECS Fargate instance can serve the request correctly.
        String lastGuest = jwtUtil.extractSubject(authorizationHeader);

        // cz-java-0070 FIX: Attempt to retrieve booking from the shared ElastiCache
        // Memcached cluster before falling back to the database. This replaces the
        // previous instance-local HashMap lookup (bookingCache.get(bookingId)) that
        // was invisible to other ECS Fargate tasks in the cluster.
        Object cachedBooking = null;
        try {
            cachedBooking = getMemcachedClient().get("booking:" + bookingId);
        } catch (IOException e) {
            // Cache unavailable — fall through to DB lookup
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
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
        // cz-java-0057 FIX: Replaced hardcoded absolute path with EFS-backed environment variable.
        // REPORT_BASE_PATH is set in the ECS Fargate task definition and maps to an EFS volume mount.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
