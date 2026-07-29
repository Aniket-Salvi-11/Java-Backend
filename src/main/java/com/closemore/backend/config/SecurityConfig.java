package com.closemore.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

/**
 * Placeholder only. Real authentication/authorization (Finding 2 decision: header-trust vs. JWT)
 * is explicitly a Phase 2 item. Without this bean, spring-boot-starter-security's
 * autoconfiguration puts a generated-password basic-auth wall in front of every endpoint, which
 * would block {@link com.closemore.backend.filter.UserContextFilter} and every integration test in
 * Phase 0/1. This permits all requests through Spring Security so UserContextFilter/RbacService
 * are the only gate for now.
 *
 * Excluded from the `prod` profile on purpose, and paired with the same guard on
 * UserContextFilter. If this ever reaches a prod deployment, Boot's default security
 * autoconfiguration takes over and locks everything behind basic auth - a loud, obvious,
 * fail-closed outage rather than a silently wide-open API.
* @ConditionalOnWebApplication: this filter chain only has meaning in a servlet web context.
 * The Phase 1 isolation tests run with WebEnvironment.NONE (no HttpSecurity bean exists), so
 * without this guard they fail to load the context. It activates normally when the app runs. 
*/
@Configuration
@Profile("!prod")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}