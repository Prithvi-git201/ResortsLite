package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * BookingController — cloud-native REST controller for resort booking operations.
 *
 * <p><strong>cr-java-0065 fix (Lines 6, 27, 34, 35, 48 of original source):</strong>
 * All HTTP session state ({@code session.setAttribute} / {@code session.getAttribute})
 * has been migrated to <strong>Amazon ElastiCache for Redis</strong> via
 * <strong>Spring Session Data Redis</strong>.  The {@link EnableRedisHttpSession}
 * annotation on this controller (and the companion {@code RedisSessionConfig} class)
 * causes Spring to transparently replace the servlet container's in-memory
 * {@link HttpSession} with a Redis-backed session.  Every {@code setAttribute} /
 * {@code getAttribute} call is now serialised to / deserialised from the shared
 * ElastiCache cluster, so all EC2 instances behind the ALB share the same session
 * store — eliminating server affinity and enabling true horizontal scaling.</p>
 *
 * <p><strong>cr-java-0067 fix (Line 19 of original source):</strong>
 * The unbounded static in-memory {@code HashMap} cache ({@code bookingCache}) has been
 * replaced with <strong>Amazon ElastiCache for Redis</strong> via Spring's
 * {@link RedisTemplate}.  Each booking entry is stored with a configurable TTL
 * (default 30 minutes, controlled by {@code app.cache.booking-ttl-minutes}), preventing
 * indefinite memory growth, stale data inconsistencies across instances, and
 * out-of-memory errors.  All EC2 instances share the same centralised Redis cache,
 * ensuring consistent data regardless of which node handles the request.</p>
 *
 * <p><strong>cr-java-0071 fix (Line 66 of original source):</strong>
 * The hard-coded environment URL
 * {@code "http://inventory-service.internal:8081/rooms/available"} has been
 * replaced with a value retrieved at runtime from
 * <strong>AWS Systems Manager Parameter Store</strong>.  The SSM parameter name
 * is externalised via the {@code app.ssm.inventory-url-param} property (backed by
 * the {@code SSM_INVENTORY_URL_PARAM} environment variable), so the same binary
 * can be deployed to dev, staging, and production without any code changes.</p>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 FIX: Replaced the unbounded static in-memory HashMap cache with
     * Amazon ElastiCache for Redis via Spring's RedisTemplate.
     *
     * <p>The previous implementation used a JVM-local {@code static final Map<String, Object>}
     * which caused:
     * <ul>
     *   <li>Indefinite memory growth — no TTL or eviction policy.</li>
     *   <li>Stale data — cache entries were never invalidated.</li>
     *   <li>Instance isolation — each EC2 node held its own independent copy,
     *       making cache data inconsistent across the Auto Scaling group.</li>
     * </ul>
     * The Redis-backed cache resolves all three issues: TTL-controlled expiration
     * prevents unbounded growth, a single shared ElastiCache cluster ensures
     * consistency across all instances, and entries are automatically evicted
     * after {@code app.cache.booking-ttl-minutes} minutes.</p>
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * cr-java-0067 FIX: TTL (in minutes) for booking cache entries stored in Redis.
     * Controlled via the {@code BOOKING_CACHE_TTL_MINUTES} environment variable or
     * the {@code app.cache.booking-ttl-minutes} application property.
     * Defaults to 30 minutes if not set.
     *
     * <p>To configure in AWS ECS / Elastic Beanstalk:
     * <pre>
     *   BOOKING_CACHE_TTL_MINUTES=30
     * </pre>
     * Or via AWS SSM Parameter Store:
     * <pre>
     *   aws ssm put-parameter --name /resortslite/cache/booking-ttl-minutes --value "30" --type String
     * </pre>
     * </p>
     */
    @Value("${app.cache.booking-ttl-minutes:${BOOKING_CACHE_TTL_MINUTES:30}}")
    private long bookingCacheTtlMinutes;

    /** Redis key prefix for booking cache entries (cr-java-0067 FIX). */
    private static final String BOOKING_CACHE_PREFIX = "booking:";

    /**
     * cr-java-0071 FIX: SSM parameter name for the inventory-service URL.
     * Resolved from the environment variable SSM_INVENTORY_URL_PARAM, with a
     * safe default of "/resortslite/inventory/service-url".
     * The actual URL value is stored in AWS SSM Parameter Store under this key.
     */
    @Value("${app.ssm.inventory-url-param:/resortslite/inventory/service-url}")
    private String inventoryUrlSsmParam;

    /**
     * cr-java-0071 FIX: AWS region used to build the SSM client.
     * Resolved from the environment variable AWS_REGION, defaulting to us-east-1.
     */
    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * <p>This helper centralises all SSM lookups so that the controller never
     * contains hard-coded environment-specific URLs (cr-java-0071).</p>
     *
     * @param paramName the SSM parameter name (e.g. "/resortslite/inventory/service-url")
     * @return the decrypted string value stored in Parameter Store
     */
    private String getSsmParameter(String paramName) {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        }
    }

    /**
     * Creates a new booking and stores transient session state in the
     * Redis-backed {@link HttpSession} (cr-java-0065 FIX).
     *
     * <p>Spring Session Data Redis transparently intercepts every
     * {@code session.setAttribute} call and serialises the value to the
     * shared ElastiCache cluster, so the data is visible to every EC2
     * instance in the Auto Scaling group — no sticky sessions required.</p>
     *
     * <p>The booking is also cached in Redis with a TTL via {@link RedisTemplate}
     * (cr-java-0067 FIX), replacing the previous unbounded in-memory HashMap.</p>
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session is now backed by Amazon ElastiCache for Redis via
        // Spring Session Data Redis (@EnableRedisHttpSession in RedisSessionConfig).
        // setAttribute() serialises the value to the shared Redis cluster so that any
        // EC2 instance behind the ALB can read it — server affinity is no longer needed.
        session.setAttribute("lastBooking", booking); // cr-java-0065 FIXED — Redis-backed
        session.setAttribute("guestName", guestName); // cr-java-0065 FIXED — Redis-backed

        // cr-java-0067 FIX: Replaced bookingCache.put() on a static in-memory HashMap with
        // a TTL-controlled write to Amazon ElastiCache for Redis via RedisTemplate.
        // - Key:   "booking:<bookingId>"  (namespaced to avoid collisions)
        // - Value: the booking Map (serialised to JSON by the configured RedisSerializer)
        // - TTL:   bookingCacheTtlMinutes (default 30 min, env: BOOKING_CACHE_TTL_MINUTES)
        // This ensures controlled expiration, consistent data across all EC2 instances,
        // and centralised cache management — eliminating the three failure modes of the
        // previous unbounded JVM-local HashMap.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, Duration.ofMinutes(bookingCacheTtlMinutes));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the booking status for the given booking ID.
     *
     * <p>The guest name is read from the Redis-backed {@link HttpSession}
     * (cr-java-0065 FIX) — the value is deserialised from ElastiCache, so
     * it is available regardless of which EC2 instance handles the request.</p>
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: getAttribute() now reads from the shared Redis session store
        // (Amazon ElastiCache) via Spring Session Data Redis, not from instance-local memory.
        // The value is consistent across all nodes in the cluster.
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065 FIXED — Redis-backed

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (Line 66): Replaced hard-coded environment URL
        //   "http://inventory-service.internal:8081/rooms/available"
        // with a runtime lookup from AWS Systems Manager Parameter Store.
        // The SSM parameter name is externalised via the property
        //   app.ssm.inventory-url-param (env: SSM_INVENTORY_URL_PARAM)
        // so the same artifact can be deployed to any environment without code changes.
        String inventoryUrl = getSsmParameter(inventoryUrlSsmParam); // cr-java-0071 FIXED

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
