package com.closemore.backend.auth;

import lombok.Getter;

/**
 * A login attempt that did not succeed, carrying the HTTP status the API should return.
 *
 * <p>Separate from RbacException because these are authentication failures (who are you?) rather
 * than authorisation failures (may you do this?), and Phase 2's global exception handler will want
 * to treat them differently - notably by rate-limiting and by never leaking the reason into logs
 * alongside the attempted password.
 */
@Getter
public class AuthenticationException extends RuntimeException {

    private final int status;

    public AuthenticationException(int status, String message) {
        super(message);
        this.status = status;
    }
}
