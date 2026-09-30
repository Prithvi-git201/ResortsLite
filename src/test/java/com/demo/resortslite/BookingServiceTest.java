package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BookingService}.
 * JdbcTemplate is mocked so no real database is required.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bookingService, "paymentApi",
                "https://payment-svc.internal:9090/charge");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_validInput_returnsMapWithBookingId() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("bookingId"));
        assertTrue(result.get("bookingId").toString().startsWith("BK-"));
    }

    @Test
    void createBooking_returnsGuestName() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        assertEquals("Bob", result.get("guestName"));
    }

    @Test
    void createBooking_returnsRoomType() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "STANDARD", "2024-08-01", "2024-08-03");

        // Assert
        assertEquals("STANDARD", result.get("roomType"));
    }

    @Test
    void createBooking_returnsCheckInAndCheckOut() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Dave", "VILLA", "2024-09-10", "2024-09-20");

        // Assert
        assertEquals("2024-09-10", result.get("checkIn"));
        assertEquals("2024-09-20", result.get("checkOut"));
    }

    @Test
    void createBooking_returnsConfirmationCode() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-05");

        // Assert
        assertNotNull(result.get("confirmationCode"));
        // SHA-256 hex string is 64 characters
        assertEquals(64, result.get("confirmationCode").toString().length());
    }

    @Test
    void createBooking_usesParameterisedSql() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        bookingService.createBooking("Frank", "DELUXE", "2024-11-01", "2024-11-04");

        // Assert — verify parameterised update was called (not string-concatenated SQL)
        verify(jdbcTemplate, times(1))
                .update(contains("?"), any(), any(), any(), any(), any());
    }

    @Test
    void createBooking_differentGuests_produceDifferentBookingIds() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> r1 = bookingService.createBooking(
                "Guest1", "STANDARD", "2024-01-01", "2024-01-02");
        Map<String, Object> r2 = bookingService.createBooking(
                "Guest2", "STANDARD", "2024-01-01", "2024-01-02");

        // Assert
        assertNotEquals(r1.get("bookingId"), r2.get("bookingId"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingById
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingById_existingId_returnsBookingMap() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-FOUND001");
        dbRow.put("guest", "Grace");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-FOUND001")))
                .thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-FOUND001");

        // Assert
        assertNotNull(result);
        assertEquals("BK-FOUND001", result.get("id"));
        assertEquals("Grace", result.get("guest"));
    }

    @Test
    void getBookingById_notFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-MISSING")))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-MISSING");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("error"));
        assertTrue(result.get("error").toString().contains("BK-MISSING"));
    }

    @Test
    void getBookingById_usesParameterisedQuery() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-PARAM01")))
                .thenReturn(new HashMap<>());

        // Act
        bookingService.getBookingById("BK-PARAM01");

        // Assert
        verify(jdbcTemplate, times(1)).queryForMap(contains("?"), eq("BK-PARAM01"));
    }

    @Test
    void getBookingById_genericException_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-ERR")))
                .thenThrow(new RuntimeException("DB connection failed"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ERR");

        // Assert
        assertNotNull(result.get("error"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_noLoyalty() {
        // Arrange: STANDARD=120, NORMAL season (no multiplier), no loyalty, 1 night
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoom_normalSeason_noLoyalty() {
        // Arrange: DELUXE=200, 1 night
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoom_normalSeason_noLoyalty() {
        // Arrange: SUITE=350, 1 night
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoom_normalSeason_noLoyalty() {
        // Arrange: VILLA=600, 1 night
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_defaultsToStandard() {
        // Arrange: unknown type defaults to 120
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeason_appliesMultiplier() {
        // Arrange: STANDARD=120 * 1.5 (PEAK) * 1 night = 180
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_offSeason_appliesDiscount() {
        // Arrange: STANDARD=120 * 0.8 (OFF) * 1 night = 96
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_goldLoyalty_appliesDiscount() {
        // Arrange: STANDARD=120 * 0.9 (GOLD) * 1 night = 108
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyalty_appliesDiscount() {
        // Arrange: STANDARD=120 * 0.8 (PLATINUM) * 1 night = 96
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_appliesDiscount() {
        // Arrange: STANDARD=120 * 0.7 (DIAMOND) * 1 night = 84
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNights_appliesWeeklyDiscount() {
        // Arrange: STANDARD=120 * 0.95 (7+ nights) * 7 = 798
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_appliesBiweeklyDiscount() {
        // Arrange: STANDARD=120 * 0.90 (14+ nights) * 14 = 1512
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonGoldLoyalty_combinedMultipliers() {
        // Arrange: SUITE=350 * 1.5 (PEAK) * 0.9 (GOLD) * 1 night = 472.50
        String price = bookingService.calculateRoomPrice("SUITE", 1, "PEAK", "GOLD");
        assertEquals("472.50", price);
    }

    @Test
    void calculateRoomPrice_multipleNights_multipliesCorrectly() {
        // Arrange: DELUXE=200 * 3 nights = 600
        String price = bookingService.calculateRoomPrice("DELUXE", 3, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isRoomAvailable
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void isRoomAvailable_standardRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
    }

    @Test
    void isRoomAvailable_deluxeRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
    }

    @Test
    void isRoomAvailable_suiteRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("SUITE"));
    }

    @Test
    void isRoomAvailable_villaRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_unknownRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    void isRoomAvailable_emptyString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_lowercaseRoomType_returnsFalse() {
        // Room type matching is case-sensitive
        assertFalse(bookingService.isRoomAvailable("standard"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateReport_returnsNonNullString() {
        // Act
        String result = bookingService.generateReport("2024-03");

        // Assert
        assertNotNull(result);
    }

    @Test
    void generateReport_containsMonth() {
        // Act
        String result = bookingService.generateReport("2024-05");

        // Assert
        assertTrue(result.contains("2024-05"));
    }

    @Test
    void generateReport_containsPaymentApiEndpoint() {
        // Act
        String result = bookingService.generateReport("2024-07");

        // Assert
        assertTrue(result.contains("https://payment-svc.internal:9090/charge"),
                "Report message should reference the payment API endpoint");
    }

    @Test
    void generateReport_differentMonths_produceDifferentMessages() {
        // Act
        String r1 = bookingService.generateReport("2024-01");
        String r2 = bookingService.generateReport("2024-02");

        // Assert
        assertNotEquals(r1, r2);
    }
}
