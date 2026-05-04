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

    // FIXED cr-java-0067: Replaced in-memory HashMap with Amazon ElastiCache for Redis
    // This enables distributed caching across multiple instances with proper TTL management
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // FIXED cr-java-0071: Externalized environment URL to AWS Systems Manager Parameter Store
    // Retrieved via environment variable for environment-agnostic deployments
    @Value("${app.inventory.endpoint:https://inventory-service.internal:8081/rooms/available}")
    private String inventoryUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED cr-java-0065: Removed HTTP session storage
        // Session data is now stored in Amazon ElastiCache for Redis via Spring Session
        // This enables stateless application instances with centralized session management
        
        // Store booking in Redis cache with 1-hour TTL
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("booking:" + bookingId, booking, 1, TimeUnit.HOURS);
        
        // Store guest context in Redis with 1-hour TTL
        redisTemplate.opsForValue().set("guest:" + bookingId, guestName, 1, TimeUnit.HOURS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // FIXED cr-java-0065: Retrieve guest information from Redis instead of HTTP session
        // This works across all instances in the cluster
        String lastGuest = (String) redisTemplate.opsForValue().get("guest:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED cr-java-0071: Using externalized inventory URL from Parameter Store
        // URL is now retrieved from environment variable, supporting HTTPS and dynamic endpoints

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // File path handling moved to ReportService with S3 integration
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
