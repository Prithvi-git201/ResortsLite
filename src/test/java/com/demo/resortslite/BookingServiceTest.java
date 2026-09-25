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

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidParams_returnsMapWithBookingId() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("bookingId"));
        assertTrue(result.get("bookingId").toString().startsWith("BK-"));
    }

    @Test
    void createBooking_withValidParams_returnsGuestName() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-10", "2024-07-15");

        assertEquals("Bob", result.get("guestName"));
    }

    @Test
    void createBooking_withValidParams_returnsRoomType() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> result = bookingService.createBooking(
                "Carol", "VILLA", "2024-08-01", "2024-08-07");

        assertEquals("VILLA", result.get("roomType"));
    }

    @Test
    void createBooking_withValidParams_returnsCheckInAndCheckOut() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> result = bookingService.createBooking(
                "Dave", "STANDARD", "2024-09-01", "2024-09-03");

        assertEquals("2024-09-01", result.get("checkIn"));
        assertEquals("2024-09-03", result.get("checkOut"));
    }

    @Test
    void createBooking_withValidParams_returnsConfirmationCode() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> result = bookingService.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-04");

        assertNotNull(result.get("confirmationCode"));
        assertFalse(result.get("confirmationCode").toString().isEmpty());
    }

    @Test
    void createBooking_withValidParams_returnsDbHost() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        Map<String, Object> result = bookingService.createBooking(
                "Frank", "DELUXE", "2024-11-01", "2024-11-03");

        assertNotNull(result.get("dbHost"));
    }

    @Test
    void createBooking_invokesJdbcTemplateUpdate() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        bookingService.createBooking("Grace", "STANDARD", "2024-12-01", "2024-12-02");

        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingById
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingById_whenFound_returnsBookingMap() {
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "Henry");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-ABCD1234"))).thenReturn(dbRow);

        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        assertNotNull(result);
        assertEquals("Henry", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-NOTEXIST")))
                .thenThrow(new RuntimeException("No rows"));

        Map<String, Object> result = bookingService.getBookingById("BK-NOTEXIST");

        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("BK-NOTEXIST"));
    }

    @Test
    void getBookingById_whenExceptionThrown_returnsNonNullMap() {
        when(jdbcTemplate.queryForMap(anyString(), any()))
                .thenThrow(new RuntimeException("DB down"));

        Map<String, Object> result = bookingService.getBookingById("BK-ERR");

        assertNotNull(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — room type variations
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_standardRoomNoSeasonNoLoyalty_returnsBasePrice() {
        // 120.0 * 1 night = 120.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomNoSeasonNoLoyalty_returnsBasePrice() {
        // 200.0 * 1 = 200.00
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomNoSeasonNoLoyalty_returnsBasePrice() {
        // 350.0 * 1 = 350.00
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomNoSeasonNoLoyalty_returnsBasePrice() {
        // 600.0 * 1 = 600.00
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_fallsBackToStandardPrice() {
        // default -> 120.0 * 1 = 120.00
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — season multipliers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_peakSeason_appliesMultiplier() {
        // STANDARD 120 * 1.5 = 180.0 * 1 night
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_offSeason_appliesDiscount() {
        // STANDARD 120 * 0.8 = 96.0 * 1 night
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_defaultSeason_noMultiplier() {
        // STANDARD 120 * 1.0 = 120.0 * 1 night
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "REGULAR", "NONE");
        assertEquals("120.00", price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — loyalty discounts
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_goldLoyalty_appliesTenPercentDiscount() {
        // STANDARD 120 * 0.9 = 108.0 * 1 night
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyalty_appliesTwentyPercentDiscount() {
        // STANDARD 120 * 0.8 = 96.0 * 1 night
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_appliesThirtyPercentDiscount() {
        // STANDARD 120 * 0.7 = 84.0 * 1 night
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_noLoyalty_noDiscount() {
        // STANDARD 120 * 1.0 = 120.0 * 1 night
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "BASIC");
        assertEquals("120.00", price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — night-based discounts
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_sevenNights_appliesFivePercentDiscount() {
        // STANDARD 120 * 0.95 * 7 = 798.0
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_appliesTenPercentDiscount() {
        // STANDARD 120 * 0.90 * 14 = 1512.0
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_sixNights_noLongStayDiscount() {
        // STANDARD 120 * 6 = 720.0
        String price = bookingService.calculateRoomPrice("STANDARD", 6, "NORMAL", "NONE");
        assertEquals("720.00", price);
    }

    @Test
    void calculateRoomPrice_twentyNights_appliesTenPercentDiscount() {
        // STANDARD 120 * 0.90 * 20 = 2160.0
        String price = bookingService.calculateRoomPrice("STANDARD", 20, "NORMAL", "NONE");
        assertEquals("2160.00", price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice — combined scenarios
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_peakSeasonGoldLoyaltySevenNights_combinedCalculation() {
        // STANDARD 120 * 1.5 = 180 (PEAK)
        // 180 * 0.9 = 162 (GOLD)
        // 162 * 0.95 = 153.9 (7 nights)
        // 153.9 * 7 = 1077.30
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "PEAK", "GOLD");
        assertEquals("1077.30", price);
    }

    @Test
    void calculateRoomPrice_offSeasonDiamondFourteenNights_combinedCalculation() {
        // DELUXE 200 * 0.8 = 160 (OFF)
        // 160 * 0.7 = 112 (DIAMOND)
        // 112 * 0.90 = 100.8 (14 nights)
        // 100.8 * 14 = 1411.20
        String price = bookingService.calculateRoomPrice("DELUXE", 14, "OFF", "DIAMOND");
        assertEquals("1411.20", price);
    }

    @Test
    void calculateRoomPrice_multipleNights_returnsFormattedString() {
        String price = bookingService.calculateRoomPrice("SUITE", 3, "NORMAL", "NONE");
        assertNotNull(price);
        assertTrue(price.contains("."));
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
        assertFalse(bookingService.isRoomAvailable("standard"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateReport_withMonth_returnsNonNullMessage() {
        String result = bookingService.generateReport("March");
        assertNotNull(result);
    }

    @Test
    void generateReport_withMonth_containsMonthInMessage() {
        String result = bookingService.generateReport("April");
        assertTrue(result.contains("April"));
    }

    @Test
    void generateReport_withMonth_containsTriggeredKeyword() {
        String result = bookingService.generateReport("May");
        assertTrue(result.toLowerCase().contains("report"));
    }

    @Test
    void generateReport_withEmptyMonth_returnsMessageWithEmptyMonth() {
        String result = bookingService.generateReport("");
        assertNotNull(result);
    }

    @Test
    void generateReport_withNumericMonth_returnsMessage() {
        String result = bookingService.generateReport("2024-06");
        assertNotNull(result);
        assertTrue(result.contains("2024-06"));
    }
}
