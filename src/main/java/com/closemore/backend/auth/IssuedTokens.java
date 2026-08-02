package com.closemore.backend.auth;

import java.time.Instant;

/**
 * What a successful login or refresh hands back.
 *
 * <p>The refresh token is the RAW value - the only moment it exists in plaintext anywhere. Only its
 * SHA-256 hash is stored, so if the client loses this string it cannot be recovered and the user
 * must log in again. That is the intended property, not a limitation.
 */
public record IssuedTokens(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt
) {
}
