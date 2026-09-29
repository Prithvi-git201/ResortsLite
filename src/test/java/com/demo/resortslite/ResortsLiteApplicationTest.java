package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration / smoke tests for {@link ResortsLiteApplication}.
 * Verifies that the Spring application context loads successfully.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResortsLiteApplicationTest {

    /**
     * Verifies that the Spring application context loads without errors.
     * This is the standard Spring Boot smoke test.
     */
    @Test
    void contextLoads() {
        // If the context fails to load, this test will fail automatically.
        // No explicit assertions needed — the @SpringBootTest annotation
        // handles context loading verification.
        assertTrue(true, "Application context should load successfully");
    }

    /**
     * Verifies that the main method can be invoked without throwing exceptions.
     * Uses an empty args array to simulate a no-argument startup.
     */
    @Test
    void main_doesNotThrowException() {
        // Arrange / Act / Assert
        assertDoesNotThrow(() -> ResortsLiteApplication.main(new String[]{}),
                "main() should not throw any exception");
    }
}
