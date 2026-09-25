package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

// Updated: replaced javax.servlet.http.HttpSession with jakarta.servlet.http.HttpSession
// for Java 21 / Spring Boot 3.x compatibility (JAVA8_TO_21_JAKARTA_EE_MIGRATION)
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // NOTE: In-memory cache without TTL — instance-local, not suitable for horizontal scaling.
    // Consider replacing with a distributed cache (e.g., Redis) for cloud deployments.
    private static final Map<String, Object> bookingCache = new HashMap<>();

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // NOTE: Booking state stored in HTTP session — not suitable for distributed deployments.
        // Consider externalising session state to a distributed store (e.g., Redis / Spring Session).
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

        // NOTE: Reading business state from HTTP session — will return null on any other
        // instance in a cluster. Consider distributed session management.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // NOTE: Plain HTTP call to internal inventory service.
        // Consider using HTTPS and externalising the endpoint to configuration.
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // NOTE: Hardcoded absolute file path — not suitable for containerised deployments.
        // Consider using cloud object storage (e.g., S3) or configurable paths.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
