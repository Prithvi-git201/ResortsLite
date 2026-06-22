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

    // FIXED cr-java-0067: Replaced in-memory cache with Azure Cache for Redis
    // Distributed cache with TTL enables horizontal scaling and cache consistency
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // FIXED cr-java-0071: Externalized URL to Azure App Configuration
    @Value("${app.inventory.endpoint:https://inventory-service.internal:8443/rooms/available}")
    private String inventoryUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED cr-java-0065: Removed HTTP session storage
        // Session state now managed by Spring Session with Azure Cache for Redis
        // This enables stateless architecture and horizontal scaling
        
        // FIXED cr-java-0067: Using Redis with TTL for distributed caching
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("booking:" + bookingId, booking, 24, TimeUnit.HOURS);
        
        // Store guest context in Redis instead of HTTP session
        redisTemplate.opsForValue().set("lastBooking:" + guestName, booking, 1, TimeUnit.HOURS);
        redisTemplate.opsForValue().set("guestName:" + bookingId, guestName, 24, TimeUnit.HOURS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // FIXED cr-java-0065: Retrieve from Redis instead of HTTP session
        String lastGuest = (String) redisTemplate.opsForValue().get("guestName:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED cr-java-0071: Using externalized configuration from Azure App Configuration
        // URL now loaded from environment-specific configuration

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIXED cr-java-0061, cr-java-0062, cr-java-0063: File operations now handled by Azure Blob Storage
        // ReportService now uses cloud storage instead of local file system

        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
