package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Tests for ResortsLiteApplication — verifies Spring context loads successfully.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
class ResortsLiteApplicationTest {

    /**
     * Verifies that the Spring application context loads without errors.
     * This implicitly tests that all beans are wired correctly.
     */
    @Test
    void contextLoads() {
        // If the context fails to load, this test will fail automatically
    }

    /**
     * Verifies that the main() entry point can be invoked without throwing exceptions.
     * Uses H2 in-memory datasource properties to avoid requiring a real PostgreSQL instance.
     */
    @Test
    void main_doesNotThrow() {
        assertDoesNotThrow(() ->
                ResortsLiteApplication.main(new String[]{
                        "--spring.main.web-application-type=none",
                        "--spring.datasource.url=jdbc:h2:mem:testdb2;DB_CLOSE_DELAY=-1",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.sql.init.mode=never"
                })
        );
    }
}
