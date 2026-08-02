package com.closemore.backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Binds the {@code jwt.*} block of application.yml.
 *
 * <p>Durations accept Spring's suffix notation - {@code 30m}, {@code 7d}. The @DurationUnit
 * defaults guard against a bare number being read as milliseconds, which would turn "30" into
 * thirty milliseconds rather than thirty minutes.
 *
 * @param secret         HMAC signing key, at least 32 bytes. Must be overridden per environment.
 * @param issuer         the {@code iss} claim; lets a future service tell our tokens from others'.
 * @param accessTokenTtl how long an access token stays valid. Also the worst-case delay between
 *                       revoking a session and the user actually losing access.
 * @param refreshTokenTtl how long before the user must enter their password again.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        String issuer,
        @DurationUnit(ChronoUnit.MINUTES) Duration accessTokenTtl,
        @DurationUnit(ChronoUnit.DAYS) Duration refreshTokenTtl
) {
}
