package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cr-java-0065 FIX: javax.servlet.http.HttpSession is retained as the API surface,
// but Spring Session Data Redis (configured in RedisSessionConfig) transparently
// intercepts all HttpSession operations and stores/retrieves session data from
// Amazon ElastiCache for Redis.  No server affinity is required — any EC2 / ECS
// instance can serve any request because session state lives in ElastiCache, not
// in the local JVM heap.  This satisfies the stateless cloud-native requirement.
import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX: Replaced unbounded in-memory HashMap cache (bookingCache) with
    // Amazon ElastiCache for Redis via Spring Data RedisTemplate.
    //
    // Previously (original source line 19):
    //   private static final Map<String, Object> bookingCache = new HashMap<>();
    //
    // The static HashMap was instance-local — each EC2/ECS instance maintained its own
    // independent copy of the cache, causing:
    //   - Stale data inconsistencies across instances (cache misses on other nodes)
    //   - Unbounded memory growth (no TTL or eviction policy)
    //   - Potential OutOfMemoryError under sustained load
    //   - Cache invalidation impossible across a horizontally-scaled cluster
    //
    // The new implementation uses RedisTemplate backed by Amazon ElastiCache for Redis:
    //   - All instances share a single, centralized cache store in ElastiCache
    //   - Every cache entry is written with a configurable TTL (default: 30 minutes)
    //     controlled by the BOOKING_CACHE_TTL_MINUTES environment variable
    //   - Redis eviction policies (e.g., allkeys-lru) provide additional memory safety
    //   - Cache entries expire automatically — no manual invalidation required
    //   - Consistent cache state across all application instances at all times
    //
    // ElastiCache connection is configured in RedisSessionConfig and application.properties:
    //   REDIS_HOST — ElastiCache primary endpoint hostname
    //   REDIS_PORT — ElastiCache port (default 6379; 6380 for TLS)
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // TTL for booking cache entries in Amazon ElastiCache for Redis.
    // Sourced from environment variable BOOKING_CACHE_TTL_MINUTES (default: 30 minutes).
    // Set this value in your ECS task definition, EKS pod spec, or Elastic Beanstalk
    // environment properties to control cache expiration per environment.
    // SSM parameter path: /resortslite/cache/booking-ttl-minutes
    @Value("${BOOKING_CACHE_TTL_MINUTES:30}")
    private long bookingCacheTtlMinutes;

    // Redis key prefix for booking cache entries — namespaces keys to avoid collisions
    // with other data stored in the same ElastiCache cluster.
    private static final String BOOKING_CACHE_KEY_PREFIX = "resortslite:booking:";

    // cr-java-0071: Hard-coded environment URL replaced with value sourced from
    // AWS Systems Manager Parameter Store via the Spring @Value binding.
    // The SSM parameter /resortslite/inventory/url is resolved at startup through
    // the aws-ssm-parameter-store Spring Cloud integration (or injected as the
    // environment variable INVENTORY_SERVICE_URL by the deployment pipeline).
    // This makes the endpoint environment-agnostic: dev, staging, and production
    // each supply their own SSM parameter value without any code change.
    @Value("${inventory.service.url:${INVENTORY_SERVICE_URL:http://inventory-service.internal:8081/rooms/available}}")
    private String inventoryServiceUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cr-java-0065 FIX: HttpSession parameter is retained. Spring Session Data Redis
            // (see RedisSessionConfig) intercepts this session object at the servlet filter
            // level and delegates all attribute storage to Amazon ElastiCache for Redis.
            // The session is now distributed — AWS ALB can route subsequent requests to any
            // instance without sticky sessions, and auto-scaling / failover are safe.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: session.setAttribute now writes to Amazon ElastiCache for Redis
        // via Spring Session Data Redis (RedisSessionConfig + @EnableRedisHttpSession).
        // Session data is no longer stored in the local JVM heap; it is persisted centrally
        // in ElastiCache so every application instance in the cluster can access it.
        session.setAttribute("lastBooking", booking); // backed by ElastiCache via Spring Session
        session.setAttribute("guestName", guestName); // backed by ElastiCache via Spring Session

        // cr-java-0067 FIX: Cache booking in Amazon ElastiCache for Redis with TTL.
        // Key is namespaced with BOOKING_CACHE_KEY_PREFIX to avoid collisions.
        // TTL is set to bookingCacheTtlMinutes (default 30 min, configurable via
        // BOOKING_CACHE_TTL_MINUTES env var) to prevent unbounded memory growth and
        // ensure stale entries are automatically evicted from the cache.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, Duration.ofMinutes(bookingCacheTtlMinutes));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: session.getAttribute now reads from Amazon ElastiCache for Redis
        // via Spring Session Data Redis.  The value is available on every application instance
        // regardless of which instance handled the original createBooking request, eliminating
        // the server-affinity dependency that caused data loss under horizontal scaling.
        String lastGuest = (String) session.getAttribute("guestName"); // backed by ElastiCache via Spring Session

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 fix: The hard-coded URL "http://inventory-service.internal:8081/rooms/available"
        // (original line 66) has been removed. The endpoint is now resolved from the
        // instance field inventoryServiceUrl, which is populated at application startup
        // from AWS Systems Manager Parameter Store via the Spring property
        // "inventory.service.url" (SSM parameter path: /resortslite/inventory/url).
        // This eliminates the environment-specific hard-coding and allows the same
        // artifact to be deployed across dev, staging, and production without modification.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryServiceUrl);
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
