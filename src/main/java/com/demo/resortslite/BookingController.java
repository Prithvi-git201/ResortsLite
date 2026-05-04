package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIXED: blocker-13 (cz-java-0070) - Replaced local cache with Redis-backed distributed cache
    // Local cache removed - session data now stored in Redis via Spring Session
    // private static final Map<String, Object> bookingCache = new HashMap<>(); // REMOVED

    @Autowired
    private S3StorageService s3StorageService;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED: blocker-5, blocker-7, blocker-8 (cz-java-0063, cz-java-0069) - Session now backed by Redis
        // HttpSession is now managed by Spring Session with Redis backend (configured in RedisConfig)
        // Session data persists across container restarts and scales horizontally
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // FIXED: blocker-13 - Store booking in Redis-backed session instead of local cache
        session.setAttribute("booking_" + booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED: blocker-6 (cz-java-0063) - Session now backed by Redis
        // Reading from HttpSession which is now distributed via Redis
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP call to
        // internal inventory service. AWS ALB, WAF, and Well-Architected security review
        // enforce HTTPS. This call will be blocked or flagged in a cloud-native setup.
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available"; // cr-java-0088

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        // FIXED: blocker-9 (cz-java-0082) - Decoupled service call
        // Service interaction now uses injected BookingService instead of tight coupling
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIXED: blocker-1 (cz-java-0057) - Replaced absolute file path with S3 storage
        // File path now uses S3 object key instead of hardcoded absolute path
        String reportKey = "reports/" + month + "_bookings.pdf";
        String s3Uri = s3StorageService.getS3Uri(reportKey);
        
        // Generate pre-signed URL for secure download (expires in 60 minutes)
        String downloadUrl = s3StorageService.generatePresignedUrl(reportKey, 60);

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", s3Uri);
        response.put("downloadUrl", downloadUrl);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
