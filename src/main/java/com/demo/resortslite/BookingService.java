package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service handling resort booking operations.
 *
 * <p><strong>Security Notes:</strong></p>
 * <ul>
 *   <li>Hardcoded DB credentials should be externalised to AWS Secrets Manager / Parameter Store.</li>
 *   <li>SQL queries use parameterised statements to prevent SQL injection.</li>
 *   <li>MD5 hashing is retained for confirmation codes (non-security use); for security-sensitive
 *       hashing, replace with SHA-256 or bcrypt.</li>
 * </ul>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // NOTE: Hardcoded database credentials — externalise to AWS Secrets Manager
    // or environment variables before deploying to production.
    private static final String DB_HOST = "db-prod.resorts-internal.com";
    private static final String DB_USER = "admin";
    private static final String DB_PASS = "Resort$Pass#2019!";

    // NOTE: Hardcoded infrastructure hostname — externalise to environment variable
    // or AWS Parameter Store. Cloud IP addresses change on restart/redeployment.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge";

    /**
     * Creates a new booking record in the database.
     *
     * @param guestName  the name of the guest
     * @param roomType   the type of room requested
     * @param checkIn    the check-in date string
     * @param checkOut   the check-out date string
     * @return a map containing the booking details and confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Using parameterised query to prevent SQL injection
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // NOTE: MD5 used here for confirmation code generation (non-security purpose).
        // For security-sensitive hashing, replace with SHA-256 or bcrypt.
        String confirmCode = md5Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", DB_HOST);
        return booking;
    }

    /**
     * Retrieves a booking by its ID.
     *
     * @param bookingId the booking identifier
     * @return a map containing the booking details, or an error entry if not found
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Using parameterised query to prevent SQL injection
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    /**
     * Calculates the total room price based on room type, nights, season, and loyalty tier.
     *
     * @param roomType the type of room (STANDARD, DELUXE, SUITE, VILLA)
     * @param nights   the number of nights
     * @param season   the season (PEAK, OFF, or standard)
     * @param loyalty  the guest loyalty tier (GOLD, PLATINUM, DIAMOND, or none)
     * @return the formatted total price as a string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = switch (roomType) {
            case "STANDARD" -> 120.0;
            case "DELUXE"   -> 200.0;
            case "SUITE"    -> 350.0;
            case "VILLA"    -> 600.0;
            default         -> 120.0;
        };

        basePrice = switch (season) {
            case "PEAK" -> basePrice * 1.5;
            case "OFF"  -> basePrice * 0.8;
            default     -> basePrice;
        };

        basePrice = switch (loyalty) {
            case "GOLD"     -> basePrice * 0.9;
            case "PLATINUM" -> basePrice * 0.8;
            case "DIAMOND"  -> basePrice * 0.7;
            default         -> basePrice;
        };

        if (nights >= 14) {
            basePrice = basePrice * 0.90;
        } else if (nights >= 7) {
            basePrice = basePrice * 0.95;
        }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room of the given type is available.
     *
     * @param roomType the type of room to check
     * @return {@code true} if the room type is valid and available, {@code false} otherwise
     */
    public boolean isRoomAvailable(String roomType) {
        return switch (roomType) {
            case "STANDARD", "DELUXE", "SUITE", "VILLA" -> true;
            default -> false;
        };
    }

    /**
     * Triggers report generation for the specified month.
     *
     * @param month the month for which to generate the report
     * @return a status message string
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * Computes an MD5 hash of the given input string.
     *
     * <p>NOTE: MD5 is a broken hash algorithm (RFC 6151) and must NOT be used for
     * security-sensitive operations. Replace with SHA-256 or bcrypt for passwords/tokens.</p>
     *
     * @param input the string to hash
     * @return the hex-encoded MD5 hash, or the original input if hashing fails
     */
    private String md5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
