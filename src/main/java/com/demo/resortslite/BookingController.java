package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069: Removed javax.servlet.http.HttpSession import — in-memory server-side
// sessions break container restarts and horizontal scaling across AKS pods.
// Spring Session with Azure Cache for Redis (spring-session-data-redis) transparently
// replaces HttpSession with a Redis-backed distributed session; no direct HttpSession
// import is required in the controller.
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0057: Replaced hardcoded absolute file path with environment variable
    // injected via Kubernetes ConfigMap / Azure App Configuration
    @Value("${app.report.base-path:${REPORT_BASE_PATH:/var/reports}}")
    private String reportBasePath;

    // cz-java-0070: Replaced local in-memory HashMap cache (bookingCache) with
    // Azure Cache for Redis via RedisTemplate. The local cache was instance-local
    // and invisible to other AKS pod replicas, breaking horizontal scaling.
    // RedisTemplate connects to Azure Cache for Redis; connection details
    // (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) are injected from Azure Key Vault
    // via the Secrets Store CSI Driver on AKS, ensuring all pods share a single
    // distributed cache that survives container restarts and scale-out events.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cz-java-0069: Replaced HttpSession with HttpServletRequest. Spring Session
            // (spring-session-data-redis) intercepts getSession() on HttpServletRequest and
            // returns a Redis-backed session, externalising state to Azure Cache for Redis
            // via the Secrets Store CSI Driver on AKS. Session data survives pod restarts
            // and is shared across all horizontally-scaled instances.
            HttpServletRequest request) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 (Lines 34-35): Replaced in-memory HttpSession.setAttribute() calls
        // with Spring Session Redis-backed session attributes. request.getSession() returns
        // a Redis-backed HttpSession proxy — data is visible to every pod in the cluster
        // and persists across container restarts. Credentials injected from Azure Key Vault
        // via the Secrets Store CSI Driver (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD env vars).
        request.getSession().setAttribute("lastBooking", booking);
        request.getSession().setAttribute("guestName", guestName);

        // cz-java-0070: Store booking in Azure Cache for Redis instead of the former
        // local HashMap. All AKS pod replicas share this distributed cache entry.
        redisTemplate.opsForValue().set("booking:" + booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cz-java-0069: Replaced HttpSession with HttpServletRequest. Spring Session
            // transparently provides a Redis-backed distributed session so that session
            // attributes are consistent across all AKS pod replicas.
            HttpServletRequest request) {

        // cz-java-0069: Reading session attribute from Redis-backed distributed session
        // via Spring Session — returns correct value regardless of which pod handles
        // the request.
        String lastGuest = (String) request.getSession().getAttribute("guestName");

        // cz-java-0070: Retrieve booking from Azure Cache for Redis (distributed cache)
        // instead of the former local HashMap. Consistent across all AKS pod replicas.
        Object cachedBooking = redisTemplate.opsForValue().get("booking:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("cachedBooking", cachedBooking);
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
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // cz-java-0057: Replaced hardcoded absolute file path /var/legacy/reports/
        // with environment variable injected via Kubernetes ConfigMap (AKS) or
        // Azure App Configuration for cross-environment portability.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
