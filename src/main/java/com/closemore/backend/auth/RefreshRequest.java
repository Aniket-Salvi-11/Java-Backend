package com.closemore.backend.auth;

/** Body for refresh and logout. The raw refresh token the client was given at login. */
public record RefreshRequest(String refreshToken) {
}
