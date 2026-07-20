package com.closemore.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Placeholder only. Real authentication/authorization (Finding 2 decision: header-trust vs.
 * JWT) is explicitly Phase 2, item 10. Without this bean, spring-boot-starter-security's
 * autoconfiguration puts a generated-password basic-auth wall in front of every endpoint,
 * which would block {@link com.closemore.backend.filter.UserContextFilter} and every
 * integration test in Phase 0/1. This permits all requests through Spring Security so
 * UserContextFilter/RbacService are the only gate for now - tighten this the moment Phase 2
 * lands.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
