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

    @Autowired
    private S3FileService s3FileService;

    // FIXED: blocker-13 (cz-java-0070) - Replaced local cache with distributed cache
    @Autowired
    private DistributedCacheService distributedCache;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED: blocker-7, blocker-8 (cz-java-0069) - Session data now stored in Redis via Spring Session
        // FIXED: blocker-5, blocker-6 (cz-java-0063) - HttpSession now backed by Redis for distributed session management
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // FIXED: blocker-13 (cz-java-0070) - Using distributed cache instead of local HashMap
        distributedCache.put("booking:" + booking.get("bookingId"), booking, 120);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED: blocker-6 (cz-java-0063) - HttpSession now backed by Redis for distributed session management
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED: blocker-9 (cz-java-0082) - Externalized service endpoint to environment variable
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
        // FIXED: blocker-1 (cz-java-0057) - Replaced absolute file path with S3 storage
        String s3Key = "reports/" + month + "_bookings.pdf";
        String s3Uri = s3FileService.getS3Uri(s3Key);

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", s3Uri);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
