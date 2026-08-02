package com.closemore.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Keeps Spring Security's autoconfiguration out of the way so that
 * {@link com.closemore.backend.filter.JwtAuthenticationFilter} is the single place request identity
 * is decided.
 *
 * <p><b>Why permitAll here is not the same as "no authentication".</b> Without this bean,
 * spring-boot-starter-security installs a generated-password basic-auth wall in front of every
 * endpoint - a second, unrelated authentication scheme that would have to be satisfied before our
 * token was ever looked at. This says "Spring Security does not gate requests"; the JWT filter
 * does, and it rejects anything without a valid token before a controller is reached.
 *
 * <p><b>The @Profile("!prod") guard has been removed.</b> It existed because the Phase 0 setup
 * trusted client headers, and shipping that would have been a cross-tenant breach - so the bean
 * simply did not exist under the prod profile, and Boot's basic-auth wall would have caused a loud
 * outage instead. Phase 2 removed the header trust, so there is nothing left to guard against, and
 * leaving the guard in would mean production ran with a configuration nothing had been tested
 * against.
 *
 * <p>Two settings worth calling out:
 * <ul>
 *   <li>CSRF is disabled because there is no session cookie to forge against. Identity travels in
 *       an Authorization header, which a browser will not attach cross-origin on its own - the
 *       condition CSRF protection exists to address does not arise.</li>
 *   <li>Session creation is STATELESS, so no HttpSession is ever created. Sessions live in the
 *       refresh_tokens table instead, which is what makes server-side logout work; a servlet
 *       session alongside it would be a second, invisible source of truth.</li>
 * </ul>
 *
 * <p>@ConditionalOnWebApplication: this only means anything in a servlet context. Most of the
 * integration tests run with WebEnvironment.NONE, where no HttpSecurity bean exists, and without
 * this guard they fail to load the context.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}