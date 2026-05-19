package com.demo.resortslite;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

/**
 * Security configuration for Azure Active Directory integration.
 * 
 * FIXED: cr-java-0090 - File-based authentication replaced with Azure AD
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            .authorizeRequests()
                .antMatchers("/api/bookings/**").authenticated()
                .antMatchers("/h2-console/**").permitAll()
                .anyRequest().permitAll()
            .and()
            .oauth2Login()
            .and()
            .csrf().disable()
            .headers().frameOptions().disable();
    }
}
