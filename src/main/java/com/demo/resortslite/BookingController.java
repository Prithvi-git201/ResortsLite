package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cr-java-0065 and cr-java-0067 FIX applied.
 *
 * cr-java-0065: All HTTP session state (lastBooking, guestName) has been migrated from
 * javax.servlet.http.HttpSession to Amazon ElastiCache for Redis via
 * Spring Session / Spring Data Redis.
 *
 * cr-java-0067: The unbounded static in-memory bookingCache (HashMap without TTL) has been
 * replaced with Amazon ElastiCache for Redis using RedisTemplate with a configurable TTL.
 * Cache entries are stored under the key prefix "cache:booking:<bookingId>" and expire
 * automatically after the configured TTL, preventing indefinite memory growth, stale data,
 * and cross-instance cache inconsistency in AWS Auto Scaling Groups.
 *
 * Session data and cache data are now stored in a centralised Redis cluster so that every
 * application instance in the AWS Auto Scaling Group reads and writes the same store.
 * This removes server affinity, enables horizontal scaling, and prevents data loss on
 * instance termination or ALB re-routing.
 *
 * The RedisTemplate<String, Object> bean is provided by the RedisSessionConfig configuration
 * class and uses the connection details supplied via the REDIS_HOST / REDIS_PORT environment
 * variables (or the spring.redis.host / spring.redis.port application properties).
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * RedisTemplate used to store and retrieve both session-scoped data and booking cache
     * entries in Amazon ElastiCache for Redis.
     *
     * cr-java-0065 FIX: Replaces all javax.servlet.http.HttpSession usage.
     * cr-java-0067 FIX: Replaces the unbounded static in-memory bookingCache HashMap.
     *
     * All cache entries are stored with an explicit TTL (see cacheTtlMinutes) so that
     * memory growth is bounded, stale data is automatically evicted, and every instance
     * in the cluster shares the same consistent view of the cache.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * TTL (in minutes) for booking cache entries stored in Redis.
     * Defaults to 60 minutes; override via CACHE_TTL_MINUTES env var or the
     * application property cache.ttl.minutes.
     *
     * cr-java-0067 FIX: Explicit TTL ensures cache entries expire automatically,
     * preventing indefinite memory growth and stale data across instances.
     */
    @Value("${cache.ttl.minutes:60}")
    private long cacheTtlMinutes;

    /**
     * TTL (in minutes) for session keys stored in Redis.
     * Defaults to 30 minutes; override via SESSION_TTL_MINUTES env var or
     * the application property session.ttl.minutes.
     */
    @Value("${session.ttl.minutes:30}")
    private long sessionTtlMinutes;

    /**
     * AWS SSM Parameter Store parameter name for the inventory service URL.
     * Defaults to "/resortslite/inventory/service-url" but can be overridden via
     * the environment variable INVENTORY_SERVICE_URL_PARAM or the application property
     * inventory.service.url.param.
     */
    @Value("${inventory.service.url.param:${INVENTORY_SERVICE_URL_PARAM:/resortslite/inventory/service-url}}")
    private String inventoryServiceUrlParam;

    /**
     * AWS region used when building the SSM client.
     * Resolved from the application property aws.region or the AWS_REGION environment variable.
     */
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Redis key prefix for booking cache entries (cr-java-0067 FIX).
     * Separates cache entries from session entries in the same Redis namespace.
     */
    private static final String CACHE_KEY_PREFIX = "cache:booking:";

    /**
     * Redis key prefix for session entries (cr-java-0065 FIX).
     */
    private static final String SESSION_KEY_PREFIX = "session:booking:";

    /**
     * cr-java-0065 FIX: Session state (lastBooking, guestName) is now written to
     * Amazon ElastiCache for Redis via RedisTemplate instead of HttpSession.
     *
     * cr-java-0067 FIX: Booking data is now cached in Amazon ElastiCache for Redis
     * with an explicit TTL (cacheTtlMinutes) instead of the unbounded static HashMap.
     * The cache key "cache:booking:<bookingId>" expires automatically, preventing
     * indefinite memory growth and ensuring consistent data across all instances.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String bookingId = (String) booking.get("bookingId");

        // cr-java-0065 FIX: Store session-scoped booking state in Redis (ElastiCache)
        // instead of HttpSession.  Keys are namespaced under "session:booking:<bookingId>"
        // and expire after the configured TTL so stale data is automatically evicted.
        String sessionKey = SESSION_KEY_PREFIX + bookingId;
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
        redisTemplate.expire(sessionKey, sessionTtlMinutes, TimeUnit.MINUTES);

        // cr-java-0067 FIX: Cache booking data in Amazon ElastiCache for Redis with an
        // explicit TTL instead of the unbounded static in-memory HashMap.
        // - Key: "cache:booking:<bookingId>" — namespaced to avoid collisions with session keys.
        // - TTL: cacheTtlMinutes (default 60 min, configurable via CACHE_TTL_MINUTES env var).
        // - Effect: cache entries expire automatically, memory growth is bounded, and all
        //   application instances behind the AWS ALB share the same consistent cache.
        String cacheKey = CACHE_KEY_PREFIX + bookingId;
        redisTemplate.opsForHash().putAll(cacheKey, booking);
        redisTemplate.expire(cacheKey, cacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * cr-java-0065 FIX: Guest name is now retrieved from Amazon ElastiCache for Redis
     * via RedisTemplate instead of HttpSession, ensuring consistent reads across all
     * application instances behind the AWS ALB.
     *
     * cr-java-0067 FIX: Booking details are now read from the Redis cache (ElastiCache)
     * instead of the static in-memory HashMap, providing a consistent, TTL-bounded view
     * of cached booking data across all instances.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // cr-java-0065 FIX: Read session-scoped guest name from Redis (ElastiCache)
        // instead of HttpSession — visible to every instance in the cluster.
        String sessionKey = SESSION_KEY_PREFIX + bookingId;
        String lastGuest = (String) redisTemplate.opsForHash().get(sessionKey, "guestName");

        // cr-java-0067 FIX: Read cached booking data from Redis (ElastiCache) instead of
        // the static in-memory HashMap.  Returns null (cache miss) if the TTL has expired,
        // in which case the caller falls back to bookingService.getBookingById().
        String cacheKey = CACHE_KEY_PREFIX + bookingId;
        Map<Object, Object> cachedBooking = redisTemplate.opsForHash().entries(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("cachedBooking", cachedBooking.isEmpty() ? null : cachedBooking);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Hard-coded environment URL replaced with a value retrieved
        // from AWS Systems Manager Parameter Store at runtime. The parameter name is
        // injected via the application property inventory.service.url.param (or the
        // INVENTORY_SERVICE_URL_PARAM environment variable), enabling environment-agnostic
        // deployments without any code changes between dev / staging / production.
        String inventoryUrl = getParameterFromSsm(inventoryServiceUrlParam);

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

    /**
     * Retrieves a plaintext parameter value from AWS Systems Manager Parameter Store.
     * Credentials are resolved via the AWS Default Credential Provider Chain
     * (IAM role, environment variables, ~/.aws/credentials, etc.).
     *
     * @param parameterName the SSM parameter name (e.g. "/resortslite/inventory/service-url")
     * @return the parameter value stored in SSM Parameter Store
     */
    private String getParameterFromSsm(String parameterName) {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(false)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        }
    }
}
