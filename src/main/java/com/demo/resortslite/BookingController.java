package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cr-java-0065 FIX: javax.servlet.http.HttpSession is retained as the API surface, but
// the backing store has been migrated to Amazon ElastiCache for Redis via Spring Session
// (see RedisSessionConfig). Spring Session transparently intercepts every
// session.setAttribute / session.getAttribute call and serialises the data to Redis,
// so all application instances in the Auto Scaling Group share the same session state.
// Server affinity (sticky sessions) is no longer required on the AWS ALB.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0071: SSM Parameter Store service injected to resolve environment-specific
     * URLs at runtime instead of hard-coding them in source.
     */
    @Autowired
    private SsmParameterStoreService ssmParameterStoreService;

    // cr-java-0067 FIX: The former unbounded static in-memory HashMap (bookingCache) has
    // been replaced with Amazon ElastiCache for Redis via Spring's RedisTemplate.
    // Benefits over the old HashMap approach:
    //   - TTL-based expiration (configurable via cache.booking.ttl-seconds) prevents
    //     indefinite memory growth and stale data accumulation.
    //   - Cache is shared across all application instances in the Auto Scaling Group —
    //     any instance can serve a cache hit regardless of which instance populated it.
    //   - Eviction, persistence, and replication are managed by ElastiCache, not the JVM.
    //   - No out-of-memory risk from unbounded cache growth on a single EC2 instance.
    @Autowired
    private RedisTemplate<String, Object> bookingRedisTemplate;

    /**
     * TTL for booking cache entries in seconds.
     * Defaults to 3600 s (1 hour); override with CACHE_BOOKING_TTL_SECONDS env var
     * or the {@code cache.booking.ttl-seconds} application property.
     */
    @Value("${cache.booking.ttl-seconds:${CACHE_BOOKING_TTL_SECONDS:3600}}")
    private long bookingCacheTtlSeconds;

    /** Redis key prefix for booking cache entries — avoids key collisions with other data. */
    private static final String BOOKING_CACHE_PREFIX = "booking:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now stored in Amazon ElastiCache for
        // Redis via Spring Session (RedisSessionConfig / @EnableRedisHttpSession).
        // The HttpSession API is unchanged — Spring Session intercepts these calls and
        // persists the data to the shared Redis cluster, making it visible to every
        // application instance behind the AWS ALB. Auto-scaling and failover are safe.
        session.setAttribute("lastBooking", booking); // backed by ElastiCache Redis
        session.setAttribute("guestName", guestName); // backed by ElastiCache Redis

        // cr-java-0067 FIX: Cache the booking in Amazon ElastiCache for Redis with a TTL.
        // The key is prefixed with "booking:" to avoid collisions with other Redis data.
        // The TTL (bookingCacheTtlSeconds, default 3600 s) ensures entries expire
        // automatically, preventing unbounded memory growth and stale data.
        // All instances in the Auto Scaling Group share this cache — no instance-local state.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        bookingRedisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Session attribute is now read from Amazon ElastiCache for
        // Redis via Spring Session. Any instance in the cluster can serve this request
        // and will retrieve the correct guestName regardless of which instance handled
        // the original /create request.
        String lastGuest = (String) session.getAttribute("guestName"); // backed by ElastiCache Redis

        // cr-java-0067 FIX: Booking details are retrieved from the shared ElastiCache
        // Redis cache (with TTL) rather than from the former instance-local HashMap.
        // If the cache entry has expired or is absent, fall back to the database via
        // bookingService.getBookingById() to ensure correctness.
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedBooking = (Map<String, Object>) bookingRedisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: The former hard-coded URL
        // "http://inventory-service.internal:8081/rooms/available" has been replaced
        // with a value retrieved at runtime from AWS Systems Manager Parameter Store
        // (SSM path: /resortslite/inventory-service-url, configurable via
        // ssm.parameter.inventory-url application property).
        // This makes the endpoint environment-agnostic — dev, staging, and production
        // each supply their own URL through SSM without any code change.
        String inventoryUrl = ssmParameterStoreService.getInventoryServiceUrl(); // cr-java-0071

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path. This path does not exist inside a container image. Container images
        // have their own isolated file systems — /var/legacy/reports won't be present.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
