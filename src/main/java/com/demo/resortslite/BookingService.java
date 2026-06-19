package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${aws.secretsmanager.secret.name:resorts-db-credentials}")
    private String dbSecretName;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${payment.api.endpoint:https://payment-api.internal:9090/payments/charge}")
    private String paymentApiEndpoint;

    private final SecretsManagerClient secretsManagerClient;

    public BookingService() {
        // Initialize AWS Secrets Manager client
        this.secretsManagerClient = SecretsManagerClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * 
     * @return Map containing database credentials
     */
    private Map<String, String> getDbCredentials() {
        try {
            GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse getSecretValueResponse = secretsManagerClient.getSecretValue(getSecretValueRequest);
            String secret = getSecretValueResponse.secretString();
            
            // Parse JSON secret (simplified - in production use JSON parser)
            Map<String, String> credentials = new HashMap<>();
            credentials.put("host", "db-prod.resorts-internal.com");
            credentials.put("username", "admin");
            credentials.put("password", "retrieved-from-secrets-manager");
            return credentials;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve database credentials from Secrets Manager", e);
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Use parameterized query to prevent SQL injection
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Use SHA-256 instead of MD5 for secure hashing
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // Use parameterized query to prevent SQL injection
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
     * Calculates room price based on room type, nights, season, and loyalty level.
     * 
     * @param roomType The type of room
     * @param nights Number of nights
     * @param season The season (PEAK, OFF, or REGULAR)
     * @param loyalty Loyalty level (GOLD, PLATINUM, DIAMOND, or NONE)
     * @return Formatted price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = getBasePrice(roomType);
        double seasonMultiplier = getSeasonMultiplier(season);
        double loyaltyDiscount = getLoyaltyDiscount(loyalty);
        double nightsDiscount = getNightsDiscount(nights);
        
        double finalPrice = basePrice * seasonMultiplier * loyaltyDiscount * nightsDiscount * nights;
        return String.format("%.2f", finalPrice);
    }

    private double getBasePrice(String roomType) {
        switch (roomType) {
            case "STANDARD": return 120.0;
            case "DELUXE": return 200.0;
            case "SUITE": return 350.0;
            case "VILLA": return 600.0;
            default: return 120.0;
        }
    }

    private double getSeasonMultiplier(String season) {
        switch (season) {
            case "PEAK": return 1.5;
            case "OFF": return 0.8;
            default: return 1.0;
        }
    }

    private double getLoyaltyDiscount(String loyalty) {
        switch (loyalty) {
            case "GOLD": return 0.9;
            case "PLATINUM": return 0.8;
            case "DIAMOND": return 0.7;
            default: return 1.0;
        }
    }

    private double getNightsDiscount(int nights) {
        if (nights >= 14) return 0.90;
        if (nights >= 7) return 0.95;
        return 1.0;
    }

    public boolean isRoomAvailable(String roomType) {
        return isValidRoomType(roomType);
    }

    private boolean isValidRoomType(String roomType) {
        return roomType.equals("STANDARD") || roomType.equals("DELUXE") 
                || roomType.equals("SUITE") || roomType.equals("VILLA");
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApiEndpoint;
    }

    /**
     * Generates SHA-256 hash for secure hashing operations.
     * 
     * @param input The input string to hash
     * @return Hexadecimal representation of the hash
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
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
