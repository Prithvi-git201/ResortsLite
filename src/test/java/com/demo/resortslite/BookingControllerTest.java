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

/**
 * Comprehensive unit tests for {@link BookingController}.
 * Covers createBooking, getBookingStatus, checkAvailability, and downloadReport.
 */
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

    // -----------------------------------------------------------------------
    // createBooking
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidInputs_returnsConfirmedStatus() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_responseContainsBookingObject() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        // Assert
        assertTrue(response.containsKey("booking"));
        assertNotNull(response.get("booking"));
    }

    @Test
    void createBooking_bookingObjectMatchesServiceResult() {
        // Arrange
        when(bookingService.createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-03"))
                .thenReturn(sampleBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03", session);

        // Assert
        assertEquals(sampleBooking, response.get("booking"));
    }

    @Test
    void createBooking_setsSessionAttributeLastBooking() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        bookingController.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        // Assert
        verify(session, times(1)).setAttribute(eq("lastBooking"), eq(sampleBooking));
    }

    @Test
    void createBooking_setsSessionAttributeGuestName() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        bookingController.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        // Assert
        verify(session, times(1)).setAttribute(eq("guestName"), eq("Alice"));
    }

    @Test
    void createBooking_callsBookingServiceCreateBooking() {
        // Arrange
        when(bookingService.createBooking("Charlie", "VILLA", "2024-08-01", "2024-08-10"))
                .thenReturn(sampleBooking);

        // Act
        bookingController.createBooking("Charlie", "VILLA", "2024-08-01", "2024-08-10", session);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Charlie", "VILLA", "2024-08-01", "2024-08-10");
    }

    @Test
    void createBooking_responseHasTwoKeys() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Diana", "STANDARD", "2024-09-01", "2024-09-03", session);

        // Assert — response should have "status" and "booking"
        assertEquals(2, response.size());
    }

    // -----------------------------------------------------------------------
    // getBookingStatus
    // -----------------------------------------------------------------------

    @Test
    void getBookingStatus_returnsMapWithBookingId() {
        // Arrange
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(sampleBooking);
        when(session.getAttribute("guestName")).thenReturn("Alice");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        // Assert
        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_returnsSessionGuestName() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(sampleBooking);
        when(session.getAttribute("guestName")).thenReturn("Alice");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        // Assert
        assertEquals("Alice", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_returnsNullSessionGuestWhenNotSet() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(sampleBooking);
        when(session.getAttribute("guestName")).thenReturn(null);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        // Assert
        assertNull(result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_returnsDetailsFromService() {
        // Arrange
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(sampleBooking);
        when(session.getAttribute("guestName")).thenReturn("Alice");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        // Assert
        assertEquals(sampleBooking, result.get("details"));
    }

    @Test
    void getBookingStatus_callsGetBookingByIdOnService() {
        // Arrange
        when(bookingService.getBookingById("BK-XYZ9999")).thenReturn(new HashMap<>());
        when(session.getAttribute("guestName")).thenReturn(null);

        // Act
        bookingController.getBookingStatus("BK-XYZ9999", session);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-XYZ9999");
    }

    @Test
    void getBookingStatus_responseContainsThreeKeys() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(sampleBooking);
        when(session.getAttribute("guestName")).thenReturn("Bob");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        // Assert — bookingId, sessionGuest, details
        assertEquals(3, result.size());
    }

    // -----------------------------------------------------------------------
    // checkAvailability
    // -----------------------------------------------------------------------

    @Test
    void checkAvailability_returnsMapWithRoomType() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(result);
        assertEquals("SUITE", result.get("roomType"));
    }

    @Test
    void checkAvailability_availableRoomReturnsTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals(true, result.get("available"));
    }

    @Test
    void checkAvailability_unavailableRoomReturnsFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, result.get("available"));
    }

    @Test
    void checkAvailability_responseContainsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        // Assert
        assertTrue(result.containsKey("inventoryEndpoint"));
        assertNotNull(result.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_inventoryEndpointIsHttps() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("VILLA");

        // Assert
        String endpoint = (String) result.get("inventoryEndpoint");
        assertTrue(endpoint.startsWith("https://"),
                "Inventory endpoint should use HTTPS for security compliance");
    }

    @Test
    void checkAvailability_callsIsRoomAvailableOnService() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        bookingController.checkAvailability("SUITE");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("SUITE");
    }

    // -----------------------------------------------------------------------
    // downloadReport
    // -----------------------------------------------------------------------

    @Test
    void downloadReport_returnsMapWithReportPath() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        // Act
        Map<String, Object> result = bookingController.downloadReport("March");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("reportPath"));
    }

    @Test
    void downloadReport_reportPathContainsMonth() {
        // Arrange
        when(bookingService.generateReport("April")).thenReturn("Report generated for April");

        // Act
        Map<String, Object> result = bookingController.downloadReport("April");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.contains("April"), "Report path should contain the month");
    }

    @Test
    void downloadReport_reportPathEndsWithPdf() {
        // Arrange
        when(bookingService.generateReport("May")).thenReturn("Report generated for May");

        // Act
        Map<String, Object> result = bookingController.downloadReport("May");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.endsWith("_bookings.pdf"), "Report path should end with _bookings.pdf");
    }

    @Test
    void downloadReport_responseContainsMessage() {
        // Arrange
        String expectedMessage = "Report generation triggered for: June";
        when(bookingService.generateReport("June")).thenReturn(expectedMessage);

        // Act
        Map<String, Object> result = bookingController.downloadReport("June");

        // Assert
        assertEquals(expectedMessage, result.get("message"));
    }

    @Test
    void downloadReport_callsGenerateReportOnService() {
        // Arrange
        when(bookingService.generateReport("July")).thenReturn("Report for July");

        // Act
        bookingController.downloadReport("July");

        // Assert
        verify(bookingService, times(1)).generateReport("July");
    }

    @Test
    void downloadReport_responseHasTwoKeys() {
        // Arrange
        when(bookingService.generateReport("August")).thenReturn("Report for August");

        // Act
        Map<String, Object> result = bookingController.downloadReport("August");

        // Assert — reportPath and message
        assertEquals(2, result.size());
    }
}
