package com.closemore.backend.auth;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The long half of a session: issue, rotate, revoke.
 *
 * <p>This is what makes logout real. An access token cannot be cancelled once signed - verification
 * is a signature check with no database lookup, which is exactly why it is fast. Revocation
 * therefore works one level up: delete the refresh token, and no new access token can be minted.
 * The user loses access within one access-token lifetime (30 minutes by default).
 *
 * <p><b>Only a hash is stored.</b> The raw token exists in exactly two places - the response to the
 * client, and the client's own storage. A leak of the refresh_tokens table yields nothing usable,
 * the same reasoning as V12 for passwords. SHA-256 rather than bcrypt is correct here: the token is
 * 256 bits of {@link SecureRandom} output, so there is nothing to guess and a deliberately slow
 * hash would only add latency to every refresh.
 *
 * <p><b>Rotation.</b> Presenting a refresh token consumes it and returns a new one. Beyond limiting
 * the window if one leaks, it makes theft visible: if a thief uses a stolen token, the legitimate
 * client's next refresh fails and the user is bounced to the login screen, rather than the two of
 * them quietly sharing a session forever.
 *
 * <p>All access goes through the SECURITY DEFINER functions from V15. The table's RLS policy admits
 * only callers that have set {@code app.bypass_rls}, so a direct query from application code
 * returns nothing - see the header of that migration for why it is done that way rather than with
 * a REVOKE.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** 256 bits. Long enough that guessing is not a threat model worth considering. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final JwtProperties properties;

    /**
     * Issues a fresh refresh token for a user and returns the RAW value. This is the only point at
     * which the plaintext token exists on the server; it is hashed before storage and never
     * recoverable afterwards.
     */
    public IssuedRefreshToken issue(String userId) {
        String raw = randomToken();
        Instant expiresAt = Instant.now().plus(properties.refreshTokenTtl());

        jdbcTemplate.queryForObject(
                "SELECT auth_issue_refresh_token(?, ?, ?, ?)",
                String.class,
                UUID.randomUUID().toString(),
                userId,
                hash(raw),
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));

        return new IssuedRefreshToken(raw, expiresAt);
    }

    /**
     * Consumes a refresh token and returns the user it belonged to, or null if it was unknown,
     * already used, revoked or expired.
     *
     * <p>One indistinguishable null for all four cases on purpose. Telling the caller which applied
     * would leak information about sessions they do not own, and a legitimate client's response is
     * the same regardless: send the user back to the login screen.
     */
    public String consume(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        return jdbcTemplate.queryForObject(
                "SELECT auth_consume_refresh_token(?)", String.class, hash(rawToken));
    }

    /** Logout on this device. Returns whether an active session was actually ended. */
    public boolean revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT auth_revoke_refresh_token(?)", Boolean.class, hash(rawToken)));
    }

    /**
     * Logout everywhere. Also the lever to pull when deactivating an account or when a device is
     * lost - the user keeps working for at most one access-token lifetime, then stops.
     *
     * @return how many active sessions were ended
     */
    public int revokeAllFor(String userId) {
        Integer revoked = jdbcTemplate.queryForObject(
                "SELECT auth_revoke_all_refresh_tokens(?)", Integer.class, userId);
        int count = revoked == null ? 0 : revoked;
        if (count > 0) {
            log.info("Revoked {} refresh token(s) for user {}", count, userId);
        }
        return count;
    }

    /**
     * Deletes long-expired rows. Intended for a scheduled job, not a request path - rows are kept
     * for a while after expiry so a rotation replay can still be observed.
     */
    public int purgeExpired() {
        Integer purged = jdbcTemplate.queryForObject(
                "SELECT auth_purge_expired_refresh_tokens('30 days'::interval)", Integer.class);
        return purged == null ? 0 : purged;
    }

    private static String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        // URL-safe and unpadded so the value survives headers, JSON and query strings untouched.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, hex encoded. Deterministic and unsalted on purpose - the lookup is by hash, so it
     * has to produce the same output every time for the same token.
     */
    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is required of every JVM, so this cannot happen.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** The raw token handed to the client, plus when it stops working. */
    public record IssuedRefreshToken(String value, Instant expiresAt) {
    }
}
