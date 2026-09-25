package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ResortsLiteApplicationTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Spring context load test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void contextLoads() {
        // Verifies that the Spring application context starts without errors
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ResortsLiteApplication class tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void applicationClass_canBeInstantiated() {
        ResortsLiteApplication app = new ResortsLiteApplication();
        assertNotNull(app);
    }

    @Test
    void applicationClass_isAnnotatedWithSpringBootApplication() {
        assertTrue(ResortsLiteApplication.class
                .isAnnotationPresent(org.springframework.boot.autoconfigure.SpringBootApplication.class));
    }
}
