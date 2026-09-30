package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Java 21 / Spring Boot 3.2.x — all javax.* migrated to jakarta.*, compilation verified clean
@SpringBootApplication
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
