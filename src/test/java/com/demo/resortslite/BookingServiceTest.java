package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link BookingService}.
 * Covers createBooking, getBookingById, calculateRoomPrice,
 * isRoomAvailable, isValidRoomType, and generateReport.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // -----------------------------------------------------------------------
    // createBooking
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidInputs_returnsBookingMapWithExpectedKeys() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("bookingId"));
        assertTrue(result.containsKey("guestName"));
        assertTrue(result.containsKey("roomType"));
        assertTrue(result.containsKey("checkIn"));
        assertTrue(result.containsKey("checkOut"));
        assertTrue(result.containsKey("confirmationCode"));
    }

    @Test
    void createBooking_bookingIdStartsWithBK() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertNotNull(bookingId);
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    void createBooking_guestNamePreservedInResult() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Charlie", "STANDARD", "2024-08-10", "2024-08-12");

        // Assert
        assertEquals("Charlie", result.get("guestName"));
    }

    @Test
    void createBooking_roomTypePreservedInResult() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Diana", "VILLA", "2024-09-01", "2024-09-10");

        // Assert
        assertEquals("VILLA", result.get("roomType"));
    }

    @Test
    void createBooking_checkInAndCheckOutPreservedInResult() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Eve", "DELUXE", "2024-10-01", "2024-10-07");

        // Assert
        assertEquals("2024-10-01", result.get("checkIn"));
        assertEquals("2024-10-07", result.get("checkOut"));
    }

    @Test
    void createBooking_confirmationCodeIsNotNull() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Frank", "SUITE", "2024-11-01", "2024-11-05");

        // Assert
        assertNotNull(result.get("confirmationCode"));
        assertFalse(((String) result.get("confirmationCode")).isEmpty());
    }

    @Test
    void createBooking_confirmationCodeIsSHA256HexString() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Grace", "STANDARD", "2024-12-01", "2024-12-03");

        // Assert — SHA-256 hex is always 64 characters
        String code = (String) result.get("confirmationCode");
        assertEquals(64, code.length(), "SHA-256 hex digest should be 64 characters");
        assertTrue(code.matches("[0-9a-f]+"), "SHA-256 hex should contain only hex characters");
    }

    @Test
    void createBooking_jdbcTemplateUpdateCalledOnce() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Henry", "DELUXE", "2024-01-01", "2024-01-05");

        // Assert
        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(), any(), any());
    }

    // -----------------------------------------------------------------------
    // getBookingById
    // -----------------------------------------------------------------------

    @Test
    void getBookingById_withExistingId_returnsBookingMap() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "Ivy");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-ABCD1234"))).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        // Assert
        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("id"));
        assertEquals("Ivy", result.get("guest"));
    }

    @Test
    void getBookingById_withNonExistingId_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-NOTFOUND")))
                .thenThrow(new RuntimeException("No results"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTFOUND");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(((String) result.get("error")).contains("BK-NOTFOUND"));
    }

    @Test
    void getBookingById_errorMessageContainsBookingId() {
        // Arrange
        String missingId = "BK-MISSING99";
        when(jdbcTemplate.queryForMap(anyString(), eq(missingId)))
                .thenThrow(new RuntimeException("EmptyResultDataAccessException"));

        // Act
        Map<String, Object> result = bookingService.getBookingById(missingId);

        // Assert
        String errorMsg = (String) result.get("error");
        assertNotNull(errorMsg);
        assertTrue(errorMsg.contains(missingId));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — room type variations
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_noLoyalty_1Night() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");

        // Assert — 120.0 * 1 = 120.00
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoom_normalSeason_noLoyalty_1Night() {
        // 200.0 * 1 = 200.00
        assertEquals("200.00", bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_suiteRoom_normalSeason_noLoyalty_1Night() {
        // 350.0 * 1 = 350.00
        assertEquals("350.00", bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_villaRoom_normalSeason_noLoyalty_1Night() {
        // 600.0 * 1 = 600.00
        assertEquals("600.00", bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_unknownRoomType_defaultsToStandardPrice() {
        // default -> 120.0
        assertEquals("120.00", bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE"));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — season variations
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoom_peakSeason_noLoyalty_1Night() {
        // 120.0 * 1.5 = 180.00
        assertEquals("180.00", bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE"));
    }

    @Test
    void calculateRoomPrice_standardRoom_offSeason_noLoyalty_1Night() {
        // 120.0 * 0.8 = 96.00
        assertEquals("96.00", bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE"));
    }

    @Test
    void calculateRoomPrice_standardRoom_unknownSeason_noLoyalty_1Night() {
        // default season -> no multiplier
        assertEquals("120.00", bookingService.calculateRoomPrice("STANDARD", 1, "SUMMER", "NONE"));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — loyalty variations
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_goldLoyalty_1Night() {
        // 120.0 * 0.9 = 108.00
        assertEquals("108.00", bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD"));
    }

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_platinumLoyalty_1Night() {
        // 120.0 * 0.8 = 96.00
        assertEquals("96.00", bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM"));
    }

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_diamondLoyalty_1Night() {
        // 120.0 * 0.7 = 84.00
        assertEquals("84.00", bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND"));
    }

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_unknownLoyalty_1Night() {
        // default loyalty -> no discount
        assertEquals("120.00", bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "SILVER"));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — night-based discounts
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_noLoyalty_7Nights() {
        // 120.0 * 0.95 * 7 = 798.00
        assertEquals("798.00", bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_noLoyalty_14Nights() {
        // 120.0 * 0.90 * 14 = 1512.00
        assertEquals("1512.00", bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_noLoyalty_6Nights() {
        // 120.0 * 6 = 720.00 (no night discount)
        assertEquals("720.00", bookingService.calculateRoomPrice("STANDARD", 6, "NORMAL", "NONE"));
    }

    @Test
    void calculateRoomPrice_villaRoom_peakSeason_diamondLoyalty_14Nights() {
        // 600.0 * 1.5 * 0.7 * 0.90 * 14 = 7938.00
        double base = 600.0 * 1.5 * 0.7 * 0.90 * 14;
        String expected = String.format("%.2f", base);
        assertEquals(expected, bookingService.calculateRoomPrice("VILLA", 14, "PEAK", "DIAMOND"));
    }

    @Test
    void calculateRoomPrice_suiteRoom_offSeason_platinumLoyalty_7Nights() {
        // 350.0 * 0.8 * 0.8 * 0.95 * 7 = 1489.60
        double base = 350.0 * 0.8 * 0.8 * 0.95 * 7;
        String expected = String.format("%.2f", base);
        assertEquals(expected, bookingService.calculateRoomPrice("SUITE", 7, "OFF", "PLATINUM"));
    }

    // -----------------------------------------------------------------------
    // isRoomAvailable
    // -----------------------------------------------------------------------

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
    void isRoomAvailable_nullRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(null));
    }

    // -----------------------------------------------------------------------
    // isValidRoomType
    // -----------------------------------------------------------------------

    @Test
    void isValidRoomType_standard_returnsTrue() {
        assertTrue(bookingService.isValidRoomType("STANDARD"));
    }

    @Test
    void isValidRoomType_deluxe_returnsTrue() {
        assertTrue(bookingService.isValidRoomType("DELUXE"));
    }

    @Test
    void isValidRoomType_suite_returnsTrue() {
        assertTrue(bookingService.isValidRoomType("SUITE"));
    }

    @Test
    void isValidRoomType_villa_returnsTrue() {
        assertTrue(bookingService.isValidRoomType("VILLA"));
    }

    @Test
    void isValidRoomType_lowercase_returnsFalse() {
        assertFalse(bookingService.isValidRoomType("standard"));
    }

    @Test
    void isValidRoomType_emptyString_returnsFalse() {
        assertFalse(bookingService.isValidRoomType(""));
    }

    @Test
    void isValidRoomType_null_returnsFalse() {
        assertFalse(bookingService.isValidRoomType(null));
    }

    @Test
    void isValidRoomType_randomString_returnsFalse() {
        assertFalse(bookingService.isValidRoomType("CABIN"));
    }

    // -----------------------------------------------------------------------
    // generateReport
    // -----------------------------------------------------------------------

    @Test
    void generateReport_returnsStringContainingMonth() {
        // Act
        String result = bookingService.generateReport("March");

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("March"));
    }

    @Test
    void generateReport_returnsNonEmptyString() {
        String result = bookingService.generateReport("January");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void generateReport_differentMonths_returnsDifferentMessages() {
        String march = bookingService.generateReport("March");
        String april = bookingService.generateReport("April");
        assertNotEquals(march, april);
    }
}
