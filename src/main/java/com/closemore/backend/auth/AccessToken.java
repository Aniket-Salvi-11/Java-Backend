package com.closemore.backend.auth;

import java.time.Instant;

/** A signed access token and the moment it stops being accepted. */
public record AccessToken(String value, Instant expiresAt) {
}
