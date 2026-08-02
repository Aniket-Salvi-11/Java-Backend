package com.closemore.backend.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Signs and verifies access tokens.
 *
 * <p><b>What goes in the token, and why it matters.</b> The claims carry the three values
 * TenantContextAspect writes into the RLS session variables: user id, role and tenant. Today those
 * arrive as {@code X-User-Role} and {@code X-User-Tenant} headers that any client can set at will -
 * so a client can currently pick its own tenant, which defeats the entire isolation design. Once
 * they come from a signed token, changing them requires the signing key.
 *
 * <p>Note the values are read from the database row at login and then sealed into the token. They
 * are not re-read on every request. The consequence is that a role or organisation change does not
 * take effect until the user's next refresh - at most one access-token lifetime, 30 minutes by
 * default. Revoking their refresh token (see RefreshTokenService) forces it sooner.
 *
 * <p><b>HS256 with a shared secret</b> rather than a public/private key pair. A single service both
 * issues and verifies these tokens, so there is nothing to gain from asymmetric keys and one fewer
 * thing to distribute. If a second service ever needs to verify tokens without being able to mint
 * them, that is the moment to move to RS256 - the claim set does not change.
 */
@Service
public class JwtService {

    /** Claim names. Short and stable: these end up in every request the frontend sends. */
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_TENANT = "tenant";

    private static final MacAlgorithm ALGORITHM = MacAlgorithm.HS256;

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;

        byte[] keyBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // Nimbus would reject this later with a less obvious message. Failing at startup is
            // better than failing on the first login attempt in a new environment.
            throw new IllegalStateException(
                    "jwt.secret must be at least 32 bytes for HS256; got " + keyBytes.length
                            + ". Set the JWT_SECRET environment variable.");
        }
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");

        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(ALGORITHM).build();
    }

    /**
     * Mints an access token for an authenticated user.
     *
     * <p>The tenant claim may legitimately be null - Organization_Name is nullable in the schema.
     * A null is written as an absent claim rather than the string "null", so
     * {@code tenantOf()} returns null and the RLS session variable ends up unset, which fails
     * closed: every tenant policy then matches nothing.
     */
    public AccessToken issue(AuthenticatedUserRow user) {
        Instant now = Instant.now();
        Duration ttl = properties.accessTokenTtl();
        Instant expiresAt = now.plus(ttl);

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.userId())
                .claim(CLAIM_ROLE, user.role());

        if (user.organizationName() != null) {
            claims.claim(CLAIM_TENANT, user.organizationName());
        }

        String value = encoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(ALGORITHM).build(), claims.build()))
                .getTokenValue();

        return new AccessToken(value, expiresAt);
    }

    /**
     * Verifies signature, issuer and expiry, and returns the decoded token.
     *
     * <p>Throws for anything wrong - tampered payload, wrong key, expired, or an {@code alg} the
     * decoder was not configured for. That last case is the one worth knowing about: a decoder
     * pinned to HS256 will not accept a token claiming {@code "alg":"none"}, which is the classic
     * JWT forgery. {@code macAlgorithm(HS256)} in the constructor is what pins it.
     *
     * @throws AuthenticationException 401, with no detail about which check failed
     */
    public Jwt verify(String tokenValue) {
        try {
            Jwt jwt = decoder.decode(tokenValue);

            if (!properties.issuer().equals(jwt.getIssuer() == null ? null : jwt.getIssuer().toString())) {
                throw new AuthenticationException(401, "Invalid token");
            }
            return jwt;
        } catch (JwtException ex) {
            // Deliberately opaque. "Expired" versus "bad signature" is useful to an attacker and
            // useless to a legitimate client, which should just refresh either way.
            throw new AuthenticationException(401, "Invalid token");
        }
    }

    public String userIdOf(Jwt jwt) {
        return jwt.getSubject();
    }

    public String roleOf(Jwt jwt) {
        return jwt.getClaimAsString(CLAIM_ROLE);
    }

    public String tenantOf(Jwt jwt) {
        return jwt.getClaimAsString(CLAIM_TENANT);
    }
}
