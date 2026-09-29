package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

// Updated: javax.servlet migrated to jakarta.servlet (Spring Boot 3.x / Jakarta EE 10)
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // NOTE cr-java-0067: In-memory cache without TTL breaks horizontal scaling.
    // For cloud-native deployments, replace with a distributed cache (e.g. Redis / ElastiCache).
    private static final Map<String, Object> bookingCache = new HashMap<>();

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // NOTE cr-java-0065: Session-based state does not survive across multiple instances.
        // For cloud-native / auto-scaling deployments, externalise session state to
        // Spring Session + Redis (AWS ElastiCache) or a similar distributed store.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

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

        // NOTE cr-java-0065: Session attribute will be null on any other cluster instance.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Updated cr-java-0088: inventory service endpoint externalised to environment variable;
        // plain HTTP replaced with HTTPS for cloud security compliance.
        String inventoryUrl = System.getenv().getOrDefault(
                "INVENTORY_SERVICE_URL", "https://inventory-service.internal:8081/rooms/available");

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Updated czr-java-001: absolute file path replaced with a configurable,
        // container-friendly path sourced from an environment variable.
        String reportBasePath = System.getenv().getOrDefault(
                "REPORT_BASE_PATH",
                System.getProperty("java.io.tmpdir") + java.io.File.separator + "reports" + java.io.File.separator);
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
