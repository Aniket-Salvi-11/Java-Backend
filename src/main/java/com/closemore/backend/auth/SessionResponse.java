package com.closemore.backend.auth;

import com.closemore.backend.dto.UserResponse;

import java.time.Instant;

/**
 * What login and refresh return.
 *
 * <p>The user object is nested under {@code user} rather than flattened at the top level, which
 * differs from the legacy response where safeUser WAS the whole body. The reason is that the body
 * now carries tokens too, and merging them into the same object makes it ambiguous which fields
 * belong to the session and which to the person. The user object itself is unchanged, so anything
 * consuming it needs only to read one level deeper.
 *
 * <p>Both expiry instants are sent so the client can schedule a refresh before the access token
 * dies, rather than discovering it through a failed request.
 */
public record SessionResponse(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UserResponse user
) {
    /** Public because AuthController lives in another package. */
    public static SessionResponse of(IssuedTokens tokens, UserResponse user) {
        return new SessionResponse(
                tokens.accessToken(),
                tokens.accessTokenExpiresAt(),
                tokens.refreshToken(),
                tokens.refreshTokenExpiresAt(),
                user);
    }
}