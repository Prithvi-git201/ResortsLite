package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for Azure Active Directory authentication.
 * FIXED cr-java-0090: Replaces file-based authentication with Azure AD.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeRequests(authorize -> authorize
                .antMatchers("/api/bookings/**").authenticated()
                .antMatchers("/h2-console/**").permitAll()
                .anyRequest().permitAll()
            )
            .oauth2Login()
            .and()
            .oauth2ResourceServer()
            .jwt();

        // Disable CSRF for H2 console (development only)
        http.csrf().ignoringAntMatchers("/h2-console/**");
        http.headers().frameOptions().sameOrigin();

        return http.build();
    }
}
