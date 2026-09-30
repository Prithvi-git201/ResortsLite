package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BookingController}.
 * All BookingService calls are mocked so no database is required.
 */
@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
        // Inject @Value fields that Spring would normally populate
        ReflectionTestUtils.setField(bookingController, "inventoryEndpoint",
                "https://inventory-svc.internal:8081/rooms");
        ReflectionTestUtils.setField(bookingController, "reportBasePath",
                "/tmp/reports/");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_validInput_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Alice");
        mockBooking.put("roomType", "SUITE");
        mockBooking.put("checkIn", "2024-06-01");
        mockBooking.put("checkOut", "2024-06-05");
        when(bookingService.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05"))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
        assertNotNull(response.get("booking"));
    }

    @Test
    void createBooking_storesBookingInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-XYZ99999");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-03", session);

        // Assert — session should hold the guest name (cr-java-0065 behaviour under test)
        assertEquals("Bob", session.getAttribute("guestName"));
        assertNotNull(session.getAttribute("lastBooking"));
    }

    @Test
    void createBooking_returnsBookingObjectInResponse() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-TEST0001");
        mockBooking.put("roomType", "STANDARD");
        when(bookingService.createBooking("Carol", "STANDARD", "2024-08-10", "2024-08-12"))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Carol", "STANDARD", "2024-08-10", "2024-08-12", session);

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> booking = (Map<String, Object>) response.get("booking");
        assertEquals("BK-TEST0001", booking.get("bookingId"));
    }

    @Test
    void createBooking_callsBookingServiceOnce() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ONCE0001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Dave", "VILLA", "2024-09-01", "2024-09-10", session);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Dave", "VILLA", "2024-09-01", "2024-09-10");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingStatus
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingStatus_existingBooking_returnsDetails() {
        // Arrange
        Map<String, Object> details = new HashMap<>();
        details.put("id", "BK-STATUS01");
        details.put("guest", "Eve");
        when(bookingService.getBookingById("BK-STATUS01")).thenReturn(details);
        session.setAttribute("guestName", "Eve");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS01", session);

        // Assert
        assertNotNull(result);
        assertEquals("BK-STATUS01", result.get("bookingId"));
        assertEquals("Eve", result.get("sessionGuest"));
        assertNotNull(result.get("details"));
    }

    @Test
    void getBookingStatus_noSessionGuest_returnsNullSessionGuest() {
        // Arrange
        when(bookingService.getBookingById("BK-NOSESS")).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-NOSESS", session);

        // Assert
        assertNull(result.get("sessionGuest"));
        assertEquals("BK-NOSESS", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_callsServiceWithCorrectId() {
        // Arrange
        when(bookingService.getBookingById("BK-VERIFY")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-VERIFY", session);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-VERIFY");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // checkAvailability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void checkAvailability_availableRoom_returnsTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(result);
        assertEquals("SUITE", result.get("roomType"));
        assertEquals(true, result.get("available"));
    }

    @Test
    void checkAvailability_unavailableRoom_returnsFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("UNKNOWN")).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("UNKNOWN");

        // Assert
        assertEquals(false, result.get("available"));
    }

    @Test
    void checkAvailability_includesInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

        // Assert
        assertNotNull(result.get("inventoryEndpoint"));
        assertTrue(result.get("inventoryEndpoint").toString().startsWith("https://"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // downloadReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void downloadReport_validMonth_returnsReportPath() {
        // Arrange
        when(bookingService.generateReport("2024-03")).thenReturn("Report generated");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-03");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("reportPath"));
        assertTrue(result.get("reportPath").toString().contains("2024-03"));
    }

    @Test
    void downloadReport_pathUsesConfiguredBasePath() {
        // Arrange
        when(bookingService.generateReport("2024-06")).thenReturn("OK");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-06");

        // Assert
        String path = (String) result.get("reportPath");
        assertTrue(path.startsWith("/tmp/reports/"));
        assertTrue(path.endsWith("_bookings.pdf"));
    }

    @Test
    void downloadReport_includesMessageFromService() {
        // Arrange
        when(bookingService.generateReport("2024-12")).thenReturn("Report for December");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-12");

        // Assert
        assertEquals("Report for December", result.get("message"));
    }

    @Test
    void downloadReport_callsGenerateReportOnce() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("done");

        // Act
        bookingController.downloadReport("2024-01");

        // Assert
        verify(bookingService, times(1)).generateReport("2024-01");
    }
}
