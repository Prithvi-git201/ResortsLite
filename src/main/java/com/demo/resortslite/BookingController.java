package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

/**
 * BookingController — cloud-ready REST controller.
 *
 * cr-java-0065 FIX: HTTP session state is now stored in Amazon ElastiCache for Redis
 * via Spring Session Data Redis.  The {@link HttpSession} interface is still used in
 * method signatures (Spring MVC injects it automatically), but because
 * {@code spring.session.store-type=redis} is set in application.properties, Spring
 * Session transparently serialises every {@code setAttribute} / {@code getAttribute}
 * call to the shared Redis cluster instead of keeping data in the local JVM heap.
 *
 * This means:
 *  - All EC2 instances share the same session store — no server affinity required.
 *  - Sessions survive instance termination and auto-scaling events.
 *  - The AWS ALB can route any request to any instance without sticky sessions.
 *  - Session TTL is controlled centrally via {@code spring.session.timeout}.
 *
 * cr-java-0067 FIX: The previous unbounded in-memory HashMap cache
 * ({@code private static final Map<String, Object> bookingCache = new HashMap<>()})
 * has been replaced with Amazon ElastiCache for Redis via Spring Data Redis
 * ({@link RedisTemplate}).  Each cache entry is stored with a configurable TTL
 * (default: 60 minutes, controlled by {@code app.cache.booking-ttl-minutes}).
 * This ensures:
 *  - Controlled memory growth — entries expire automatically after TTL elapses.
 *  - Consistent cache state across all EC2 instances (no instance-local data).
 *  - No stale data — expired entries are evicted by Redis automatically.
 *  - Centralized cache management via Amazon ElastiCache for Redis.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 FIX: Spring Data Redis template used to store booking cache entries
     * in Amazon ElastiCache for Redis with a configurable TTL.
     *
     * Replaces the previous unbounded in-memory HashMap:
     *   {@code private static final Map<String, Object> bookingCache = new HashMap<>();}
     *
     * The RedisTemplate is auto-configured by Spring Boot when
     * {@code spring-boot-starter-data-redis} is on the classpath and
     * {@code spring.redis.host} / {@code spring.redis.port} are set.
     * In cloud deployments these point to the Amazon ElastiCache for Redis
     * primary endpoint.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Redis key prefix for booking cache entries.
     * Namespaces booking cache keys to avoid collisions with session keys.
     */
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    /**
     * TTL (in minutes) for each booking cache entry stored in Redis.
     * Sourced from {@code app.cache.booking-ttl-minutes} (default: 60 minutes).
     * Override via environment variable {@code APP_CACHE_BOOKING_TTL_MINUTES}.
     */
    @Value("${app.cache.booking-ttl-minutes:60}")
    private long bookingCacheTtlMinutes;

    /**
     * AWS region used to build the SSM client.
     * Sourced from the {@code CLOUD_AWS_REGION} environment variable (default: us-east-1).
     */
    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    /**
     * SSM Parameter Store key for the inventory service URL.
     * Sourced from the {@code app.ssm.inventory-url-param} property
     * (default: /resortslite/inventory/url).
     */
    @Value("${app.ssm.inventory-url-param:/resortslite/inventory/url}")
    private String inventoryUrlParam;

    /**
     * Creates a new booking and stores the booking state in:
     *  1. The distributed Redis-backed HTTP session (Amazon ElastiCache for Redis
     *     via Spring Session Data Redis) — cr-java-0065 FIX.
     *  2. The Redis booking cache with a TTL-controlled expiry — cr-java-0067 FIX.
     *
     * cr-java-0065 FIX: {@code session.setAttribute} calls are now transparently
     * serialised to ElastiCache for Redis by Spring Session Data Redis, replacing the
     * previous in-process JVM session storage that caused server affinity and data loss
     * during horizontal scaling / failover.
     *
     * cr-java-0067 FIX: Booking data is cached in Redis using
     * {@link RedisTemplate#opsForValue()#set(Object, Object, long, TimeUnit)} with a
     * configurable TTL ({@code app.cache.booking-ttl-minutes}), replacing the previous
     * unbounded in-memory HashMap that grew indefinitely and was invisible to other
     * EC2 instances.
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
        // Redis via Spring Session Data Redis (spring.session.store-type=redis).
        // The HttpSession API is unchanged; Spring Session intercepts these calls and
        // persists the data to the shared Redis cluster, making the instance stateless.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Cache the booking in Amazon ElastiCache for Redis with a
        // TTL-controlled expiry instead of the previous unbounded in-memory HashMap.
        // Key format: "booking:cache:<bookingId>"
        // TTL: configurable via app.cache.booking-ttl-minutes (default: 60 minutes).
        // This ensures:
        //   - Entries expire automatically — no indefinite memory growth.
        //   - Cache is shared across all EC2 instances — no stale data inconsistencies.
        //   - Redis eviction policies (e.g., allkeys-lru) provide additional safety.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the booking status for the given booking ID.
     *
     * cr-java-0065 FIX: {@code session.getAttribute} now reads from the shared
     * ElastiCache for Redis session store (via Spring Session Data Redis), so the
     * guest name is available regardless of which EC2 instance handles the request.
     *
     * cr-java-0067 FIX: Booking details are retrieved from the Redis cache
     * (Amazon ElastiCache for Redis) using the TTL-controlled cache key.
     * If the cache entry has expired or is absent, the request falls through to
     * {@link BookingService#getBookingById(String)} for a fresh database lookup.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Session attribute is retrieved from the distributed
        // ElastiCache for Redis store — consistent across all instances in the cluster.
        String lastGuest = (String) session.getAttribute("guestName");

        // cr-java-0067 FIX: Attempt to retrieve booking details from the Redis cache
        // (Amazon ElastiCache for Redis) before falling back to the database.
        // The cache entry will be absent if the TTL has elapsed, ensuring data freshness.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        if (cachedBooking != null) {
            // Cache hit — return the Redis-cached booking details.
            result.put("details", cachedBooking);
            result.put("cacheSource", "redis");
        } else {
            // Cache miss — fall back to the database via BookingService.
            result.put("details", bookingService.getBookingById(bookingId));
            result.put("cacheSource", "database");
        }
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIX cr-java-0071: Hard-coded environment URL replaced with value retrieved from
        // AWS Systems Manager Parameter Store. The SSM parameter key is configurable via
        // the 'app.ssm.inventory-url-param' property (default: /resortslite/inventory/url).
        String inventoryUrl;
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {
            GetParameterRequest paramRequest = GetParameterRequest.builder()
                    .name(inventoryUrlParam)
                    .withDecryption(false)
                    .build();
            GetParameterResponse paramResponse = ssmClient.getParameter(paramRequest);
            inventoryUrl = paramResponse.parameter().value();
        } catch (Exception e) {
            // Fall back to the environment-variable-backed default if SSM is unavailable
            inventoryUrl = System.getenv().getOrDefault("INVENTORY_SERVICE_URL",
                    "http://inventory-service.internal:8081/rooms/available");
        }

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
        // have their own isolated file systems — /var/legacy/reports/ won't be present.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
