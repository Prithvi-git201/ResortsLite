package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // BLOCKER-13 FIXED: Replaced local cache with Redis distributed cache
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // BLOCKER-1 FIXED: S3 client for file operations instead of absolute paths
    @Autowired
    private S3Client s3Client;

    @Value("${aws.s3.bucket.reports:resort-reports-bucket}")
    private String reportsBucket;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // BLOCKER-4, BLOCKER-5, BLOCKER-7, BLOCKER-8 FIXED: Using Spring Session with Redis
        // Session data is now stored in Redis and shared across all container instances
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // BLOCKER-13 FIXED: Using Redis for distributed caching with TTL
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("booking:" + bookingId, booking, 1, TimeUnit.HOURS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // BLOCKER-4, BLOCKER-5 FIXED: Session data now persisted in Redis via Spring Session
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // BLOCKER-9 FIXED: Using environment variable for service endpoint
        // This enables service mesh and API Gateway integration
        String inventoryUrl = System.getenv().getOrDefault("INVENTORY_SERVICE_URL", 
            "https://inventory-service.internal:8081/rooms/available");

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // BLOCKER-1 FIXED: Using S3 instead of absolute file paths
        String reportKey = "reports/" + month + "_bookings.pdf";
        
        try {
            // Generate report content (simplified for demonstration)
            String reportContent = bookingService.generateReport(month);
            
            // Upload to S3
            PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(reportsBucket)
                .key(reportKey)
                .build();
            
            s3Client.putObject(putRequest, RequestBody.fromString(reportContent));
            
            Map<String, Object> response = new HashMap<>();
            response.put("reportKey", reportKey);
            response.put("bucket", reportsBucket);
            response.put("message", "Report uploaded to S3");
            return response;
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("error", "Failed to upload report: " + e.getMessage());
            return response;
        }
    }
}
