package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — REST controller for resort booking operations.
 *
 * <p><strong>cr-java-0067 FIX — In-Memory Caching Without TTL:</strong><br>
 * The unbounded static {@code HashMap} used as an in-memory booking cache has been
 * replaced with <em>Amazon ElastiCache for Redis</em> via Spring Boot's
 * {@link RedisTemplate}.  Every cache entry is written with an explicit TTL
 * (default: 30 minutes, configurable via {@code BOOKING_CACHE_TTL_SECONDS}) so
 * that:
 * <ul>
 *   <li>Memory growth is bounded — entries expire automatically.</li>
 *   <li>Cache data is consistent across all EC2 / ECS instances in the cluster.</li>
 *   <li>Stale data is eliminated — TTL-based expiry ensures freshness.</li>
 *   <li>The cache survives instance restarts and auto-scaling events.</li>
 * </ul>
 *
 * <p><strong>cr-java-0065 FIX — HTTP Session State Storage:</strong><br>
 * All HTTP session state is now backed by <em>Amazon ElastiCache for Redis</em> via
 * Spring Session Data Redis ({@code spring-session-data-redis}).  The
 * {@link HttpSession} API is preserved unchanged; Spring Session transparently
 * serialises every {@code setAttribute}/{@code getAttribute} call to the
 * centralised Redis cluster instead of keeping data in JVM heap memory.
 *
 * <p>This eliminates the server-affinity problem that previously prevented
 * horizontal scaling on AWS: any EC2 / ECS instance can now serve any request
 * because session data is stored externally in ElastiCache, not on the instance
 * that originally created the session.
 *
 * <p>Required infrastructure:
 * <ul>
 *   <li>Amazon ElastiCache for Redis cluster (endpoint supplied via
 *       {@code REDIS_HOST} / {@code REDIS_PORT} environment variables or
 *       AWS Systems Manager Parameter Store)</li>
 *   <li>{@code spring-session-data-redis} and
 *       {@code spring-boot-starter-data-redis} on the classpath (added to
 *       {@code pom.xml})</li>
 *   <li>{@code spring.session.store-type=redis} in
 *       {@code application.properties}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 FIX: RedisTemplate replaces the unbounded static HashMap.
     *
     * <p>Previously:
     * <pre>
     *   private static final Map&lt;String, Object&gt; bookingCache = new HashMap&lt;&gt;();
     * </pre>
     * This caused indefinite memory growth, instance-local cache state (invisible to
     * other EC2 instances), and stale data inconsistencies in horizontally-scaled
     * cloud deployments.
     *
     * <p>Now: all cache reads/writes go through {@link RedisTemplate} backed by
     * Amazon ElastiCache for Redis.  Each entry is stored with an explicit TTL
     * (controlled by {@code BOOKING_CACHE_TTL_SECONDS}, default 1800 s = 30 min),
     * ensuring bounded memory usage, automatic expiry of stale entries, and a
     * single shared cache visible to every instance in the cluster.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * TTL for booking cache entries in seconds.
     * Sourced from the {@code BOOKING_CACHE_TTL_SECONDS} environment variable or
     * AWS Systems Manager Parameter Store ({@code /resortslite/cache/booking-ttl}).
     * Defaults to 1800 seconds (30 minutes).
     */
    @Value("${app.booking.cache.ttl-seconds:${BOOKING_CACHE_TTL_SECONDS:1800}}")
    private long bookingCacheTtlSeconds;

    /** Redis key prefix for booking cache entries. */
    private static final String BOOKING_CACHE_PREFIX = "booking:cache:";

    /**
     * cr-java-0071 FIX: Hard-coded inventory service URL replaced with a value sourced
     * from AWS Systems Manager Parameter Store via the {@code app.inventory.url} Spring
     * property. The property is resolved at runtime from the SSM parameter
     * {@code /resortslite/inventory/url}, enabling environment-agnostic deployments
     * without code changes between dev, staging, and production.
     *
     * <p>Previously: {@code String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";}
     * <p>Now: injected from SSM Parameter Store via {@code @Value("${app.inventory.url}")}
     */
    @Value("${app.inventory.url:https://inventory-service.internal:8081/rooms/available}")
    private String inventoryUrl;

    /**
     * Creates a new booking and caches it in Amazon ElastiCache for Redis with a TTL.
     *
     * <p><strong>cr-java-0067 FIX (line 19 in original):</strong> The booking is now
     * stored in the Redis-backed cache via {@link RedisTemplate#opsForValue()#set} with
     * an explicit TTL ({@code bookingCacheTtlSeconds}), replacing the unbounded
     * {@code bookingCache.put()} call on the static HashMap.  This ensures:
     * <ul>
     *   <li>Entries expire automatically after the configured TTL.</li>
     *   <li>All cluster instances share the same cache state.</li>
     *   <li>Memory usage is bounded and predictable.</li>
     * </ul>
     *
     * <p><strong>cr-java-0065 FIX (lines 34–35):</strong> {@code session.setAttribute}
     * calls are retained but now transparently serialised to Amazon ElastiCache for
     * Redis by Spring Session, making the state visible to every instance in the
     * cluster and safe across auto-scaling events and instance terminations.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now stored in Amazon ElastiCache for
        // Redis via Spring Session Data Redis. The HttpSession API is unchanged; Spring
        // Session intercepts these calls and serialises the data to the centralised Redis
        // cluster, eliminating server affinity and enabling stateless horizontal scaling.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Replaced unbounded static HashMap cache entry with a
        // Redis-backed cache entry that carries an explicit TTL.
        // Previously: bookingCache.put((String) booking.get("bookingId"), booking);
        // Now: stored in Amazon ElastiCache for Redis with TTL-based expiry, ensuring
        // bounded memory growth, automatic stale-data removal, and cross-instance
        // cache consistency in horizontally-scaled AWS deployments.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking, reading from the Redis cache when available.
     *
     * <p><strong>cr-java-0067 FIX:</strong> Booking lookup now queries Amazon
     * ElastiCache for Redis via {@link RedisTemplate} instead of the instance-local
     * HashMap, ensuring cache hits are consistent across all cluster instances.
     *
     * <p><strong>cr-java-0065 FIX (line 48):</strong> {@code session.getAttribute}
     * now reads from Amazon ElastiCache for Redis, so the value is consistent
     * regardless of which cluster instance handles the request.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Session attribute is now retrieved from Amazon ElastiCache
        // for Redis via Spring Session Data Redis. Any instance in the cluster can serve
        // this request and will receive the same session data stored by the originating
        // instance, eliminating the null-on-other-instance problem.
        String lastGuest = (String) session.getAttribute("guestName");

        // cr-java-0067 FIX: Cache lookup now goes to Amazon ElastiCache for Redis
        // instead of the instance-local HashMap, providing consistent results across
        // all EC2 / ECS instances in the cluster.
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        // Use cached booking details if available; fall back to service lookup
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (source line 66): The hard-coded URL
        //   "http://inventory-service.internal:8081/rooms/available"
        // has been removed. The endpoint is now injected via the @Value field
        // `inventoryUrl`, which is resolved from the Spring property `app.inventory.url`.
        // In AWS deployments this property is supplied by AWS Systems Manager Parameter
        // Store (parameter: /resortslite/inventory/url), allowing the URL to differ
        // across dev, staging, and production without any code change or redeployment.

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
