package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController - Cloud-ready REST controller with Redis-backed session and cache.
 * 
 * FIXED VIOLATIONS:
 * - cr-java-0065: Externalized session state to Azure Cache for Redis
 * - cr-java-0067: Replaced in-memory cache with Azure Cache for Redis with TTL
 * - cr-java-0071: Externalized URLs to Azure App Configuration
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${app.inventory.endpoint}")
    private String inventoryUrl;

    // Cache TTL in seconds (10 minutes)
    private static final long CACHE_TTL_SECONDS = 600;

    /**
     * Creates a new booking and stores session data in Redis.
     * 
     * FIXED: cr-java-0065 - Session state now stored in Redis instead of HTTP session
     * FIXED: cr-java-0067 - Cache now uses Redis with TTL instead of in-memory HashMap
     * 
     * @param guestName Guest name
     * @param roomType Room type
     * @param checkIn Check-in date
     * @param checkOut Check-out date
     * @param sessionId Session identifier (from request header or cookie)
     * @return Map containing booking confirmation
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "default-session") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Store session data in Redis with TTL (replaces HTTP session storage)
        String sessionKey = "session:" + sessionId;
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
        redisTemplate.expire(sessionKey, 30, TimeUnit.MINUTES);

        // Store booking in distributed cache with TTL (replaces in-memory HashMap)
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, CACHE_TTL_SECONDS, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        response.put("cacheType", "Azure Cache for Redis");
        return response;
    }

    /**
     * Retrieves booking status with session data from Redis.
     * 
     * FIXED: cr-java-0065 - Session data retrieved from Redis instead of HTTP session
     * 
     * @param bookingId Booking ID
     * @param sessionId Session identifier
     * @return Map containing booking status
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "default-session") String sessionId) {

        // Retrieve session data from Redis (replaces HTTP session)
        String sessionKey = "session:" + sessionId;
        String lastGuest = (String) redisTemplate.opsForHash().get(sessionKey, "guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        result.put("sessionStore", "Azure Cache for Redis");
        return result;
    }

    /**
     * Checks room availability using externalized inventory service URL.
     * 
     * FIXED: cr-java-0071 - URL externalized to Azure App Configuration
     * 
     * @param roomType Room type to check
     * @return Map containing availability information
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Use externalized inventory URL from Azure App Configuration
        String fullInventoryUrl = inventoryUrl + "/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", fullInventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        response.put("configSource", "Azure App Configuration");
        return response;
    }

    /**
     * Downloads report using Azure Blob Storage.
     * 
     * @param month Month for the report
     * @return Map containing report download information
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now handled by Azure Blob Storage in ReportService
        Map<String, Object> response = new HashMap<>();
        response.put("month", month);
        response.put("message", bookingService.generateReport(month));
        response.put("storageType", "Azure Blob Storage");
        return response;
    }
}
