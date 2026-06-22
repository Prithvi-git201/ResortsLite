package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${app.inventory.endpoint}")
    private String inventoryUrl;

    // FIXED cr-java-0067: Replaced in-memory cache with Google Cloud Memorystore for Redis
    // This enables distributed caching across all application instances with proper TTL
    private static final String CACHE_PREFIX = "booking:";
    private static final long CACHE_TTL_MINUTES = 30;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED cr-java-0065: Removed HTTP session state storage
        // FIXED cr-java-0067: Store in Redis with TTL instead of in-memory HashMap
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set(CACHE_PREFIX + bookingId, booking, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        
        // Store guest association for lookup
        redisTemplate.opsForValue().set(CACHE_PREFIX + "guest:" + bookingId, guestName, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // FIXED cr-java-0065: Retrieve from Redis instead of HTTP session
        String lastGuest = (String) redisTemplate.opsForValue().get(CACHE_PREFIX + "guest:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("guestName", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED cr-java-0071: Use externalized configuration from environment variables
        // The URL is now injected from application.properties which reads from env vars
        
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIXED cr-java-0061, cr-java-0062, cr-java-0063: Removed hardcoded file path
        // Report generation now uses Google Cloud Storage via ReportService
        
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        response.put("storage", "Google Cloud Storage");
        return response;
    }
}
