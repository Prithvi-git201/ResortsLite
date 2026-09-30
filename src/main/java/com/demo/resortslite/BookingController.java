package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// Updated from javax.servlet to jakarta.servlet for Jakarta EE / Spring Boot 3.x compatibility
// (JAVA8_TO_21_JAKARTA_EE_MIGRATION)
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // VIOLATION cr-java-0067 [Cloud Compatibility / Mandatory]: In-memory cache without TTL
    // breaks horizontal scaling — cache is instance-local, invisible to other EC2 instances
    private static final Map<String, Object> bookingCache = new HashMap<>(); // cr-java-0067

    // FIX cr-java-0088 / cr-java-0021: Inventory endpoint externalised to application property.
    // Uses HTTPS to comply with cloud security standards (AWS ALB / WAF enforce HTTPS).
    @Value("${app.inventory.endpoint:https://inventory-svc.internal:8081/rooms}")
    private String inventoryEndpoint;

    // FIX czr-java-001: Report base path externalised to application property.
    // No hardcoded absolute paths — supports container and cloud deployments.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // VIOLATION cr-java-0065 [Cloud Compatibility / Mandatory]: Booking state stored in
        // HTTP session memory. AWS ALB distributes requests across EC2 instances — session
        // data on instance A is invisible to instance B. Auto-scaling and failover breaks.
        session.setAttribute("lastBooking", booking); // cr-java-0065
        session.setAttribute("guestName", guestName); // cr-java-0065

        bookingCache.put((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // VIOLATION cr-java-0065 [Cloud Compatibility / Mandatory]: Reading business state
        // from HTTP session — will return null on any other instance in the cluster.
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIX cr-java-0088 / cr-java-0021: Replaced hardcoded plain HTTP internal URL with
        // an externalised HTTPS endpoint injected via @Value. Cloud security standards
        // (AWS ALB / WAF) enforce HTTPS; plain HTTP calls are blocked or flagged.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIX czr-java-001 [Software Portability / Mandatory]: Replaced hardcoded absolute
        // path /var/legacy/reports with an externalised property. The path is now configurable
        // via environment variable / application property and defaults to /tmp/reports/,
        // which is available in all Linux container images.
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
