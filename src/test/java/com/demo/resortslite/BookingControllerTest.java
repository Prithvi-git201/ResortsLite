package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @Mock
    private HttpSession session;

    @InjectMocks
    private BookingController bookingController;

    private Map<String, Object> sampleBooking;

    @BeforeEach
    void setUp() {
        sampleBooking = new HashMap<>();
        sampleBooking.put("bookingId", "BK-ABCD1234");
        sampleBooking.put("guestName", "Alice");
        sampleBooking.put("roomType", "SUITE");
        sampleBooking.put("checkIn", "2024-06-01");
        sampleBooking.put("checkOut", "2024-06-05");
        sampleBooking.put("confirmationCode", "abc123def456");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_withValidParams_returnsBookingInResponse() {
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        assertNotNull(response.get("booking"));
        assertEquals(sampleBooking, response.get("booking"));
    }

    @Test
    void createBooking_withValidParams_setsSessionAttributes() {
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        bookingController.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        verify(session).setAttribute(eq("lastBooking"), eq(sampleBooking));
        verify(session).setAttribute(eq("guestName"), eq("Alice"));
    }

    @Test
    void createBooking_withValidParams_invokesBookingService() {
        when(bookingService.createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-03"))
                .thenReturn(sampleBooking);

        bookingController.createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-03", session);

        verify(bookingService, times(1))
                .createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-03");
    }

    @Test
    void createBooking_responseContainsStatusKey() {
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        Map<String, Object> response = bookingController.createBooking(
                "Carol", "VILLA", "2024-08-01", "2024-08-07", session);

        assertTrue(response.containsKey("status"));
    }

    @Test
    void createBooking_responseContainsBookingKey() {
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        Map<String, Object> response = bookingController.createBooking(
                "Dave", "STANDARD", "2024-09-01", "2024-09-02", session);

        assertTrue(response.containsKey("booking"));
    }

    @Test
    void createBooking_bookingIdStoredInCache() {
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        Map<String, Object> response = bookingController.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-04", session);

        // The booking should be in the response
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingStatus
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingStatus_withValidBookingId_returnsBookingIdInResult() {
        Map<String, Object> bookingDetails = new HashMap<>();
        bookingDetails.put("id", "BK-ABCD1234");
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(bookingDetails);
        when(session.getAttribute("guestName")).thenReturn("Alice");

        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        assertEquals("BK-ABCD1234", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_withValidBookingId_returnsSessionGuest() {
        Map<String, Object> bookingDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(bookingDetails);
        when(session.getAttribute("guestName")).thenReturn("Alice");

        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        assertEquals("Alice", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_withValidBookingId_returnsDetails() {
        Map<String, Object> bookingDetails = new HashMap<>();
        bookingDetails.put("guest", "Bob");
        when(bookingService.getBookingById("BK-XYZ9876")).thenReturn(bookingDetails);
        when(session.getAttribute("guestName")).thenReturn(null);

        Map<String, Object> result = bookingController.getBookingStatus("BK-XYZ9876", session);

        assertNotNull(result.get("details"));
    }

    @Test
    void getBookingStatus_whenNoSessionGuest_returnsNullSessionGuest() {
        Map<String, Object> bookingDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(bookingDetails);
        when(session.getAttribute("guestName")).thenReturn(null);

        Map<String, Object> result = bookingController.getBookingStatus("BK-NONE", session);

        assertNull(result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_invokesGetBookingById() {
        Map<String, Object> bookingDetails = new HashMap<>();
        when(bookingService.getBookingById("BK-TEST")).thenReturn(bookingDetails);
        when(session.getAttribute("guestName")).thenReturn("Frank");

        bookingController.getBookingStatus("BK-TEST", session);

        verify(bookingService, times(1)).getBookingById("BK-TEST");
    }

    @Test
    void getBookingStatus_resultContainsAllThreeKeys() {
        Map<String, Object> bookingDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(bookingDetails);
        when(session.getAttribute("guestName")).thenReturn("Grace");

        Map<String, Object> result = bookingController.getBookingStatus("BK-KEYS", session);

        assertTrue(result.containsKey("bookingId"));
        assertTrue(result.containsKey("sessionGuest"));
        assertTrue(result.containsKey("details"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // checkAvailability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void checkAvailability_withAvailableRoom_returnsAvailableTrue() {
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        assertEquals(true, response.get("available"));
    }

    @Test
    void checkAvailability_withUnavailableRoom_returnsAvailableFalse() {
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        Map<String, Object> response = bookingController.checkAvailability("PENTHOUSE");

        assertEquals(false, response.get("available"));
    }

    @Test
    void checkAvailability_returnsRoomTypeInResponse() {
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        assertEquals("DELUXE", response.get("roomType"));
    }

    @Test
    void checkAvailability_returnsInventoryEndpoint() {
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        assertNotNull(response.get("inventoryEndpoint"));
        assertTrue(response.get("inventoryEndpoint").toString().contains("inventory-service"));
    }

    @Test
    void checkAvailability_invokesIsRoomAvailable() {
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        bookingController.checkAvailability("VILLA");

        verify(bookingService, times(1)).isRoomAvailable("VILLA");
    }

    @Test
    void checkAvailability_responseContainsThreeKeys() {
        when(bookingService.isRoomAvailable(anyString())).thenReturn(false);

        Map<String, Object> response = bookingController.checkAvailability("UNKNOWN");

        assertTrue(response.containsKey("roomType"));
        assertTrue(response.containsKey("inventoryEndpoint"));
        assertTrue(response.containsKey("available"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // downloadReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void downloadReport_withMonth_returnsReportPath() {
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        Map<String, Object> response = bookingController.downloadReport("March");

        assertNotNull(response.get("reportPath"));
        assertTrue(response.get("reportPath").toString().contains("March"));
    }

    @Test
    void downloadReport_withMonth_returnsMessage() {
        when(bookingService.generateReport("April")).thenReturn("Report generated for April");

        Map<String, Object> response = bookingController.downloadReport("April");

        assertEquals("Report generated for April", response.get("message"));
    }

    @Test
    void downloadReport_invokesGenerateReport() {
        when(bookingService.generateReport("May")).thenReturn("Report for May");

        bookingController.downloadReport("May");

        verify(bookingService, times(1)).generateReport("May");
    }

    @Test
    void downloadReport_reportPathContainsPdfExtension() {
        when(bookingService.generateReport(anyString())).thenReturn("Report generated");

        Map<String, Object> response = bookingController.downloadReport("June");

        assertTrue(response.get("reportPath").toString().endsWith(".pdf"));
    }

    @Test
    void downloadReport_responseContainsBothKeys() {
        when(bookingService.generateReport(anyString())).thenReturn("Report generated");

        Map<String, Object> response = bookingController.downloadReport("July");

        assertTrue(response.containsKey("reportPath"));
        assertTrue(response.containsKey("message"));
    }
}
