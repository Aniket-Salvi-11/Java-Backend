package com.closemore.backend.auth;

/**
 * Login body. Field names are capitalised to match the existing frontend, which posts
 * {@code { "Email": ..., "Password": ... }} - see the legacy login route. Renaming them would be a
 * frontend-visible change and Phase 2 is deliberately not making any.
 *
 * <p>No bean-validation annotations on purpose: emptiness is checked in LoginService so that the
 * message stays the established "Email and Password are required" rather than Jakarta Validation's
 * generic wording.
 */
public record LoginRequest(String Email, String Password) {
}
