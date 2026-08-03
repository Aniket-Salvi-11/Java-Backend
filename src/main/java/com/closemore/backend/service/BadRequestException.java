package com.closemore.backend.service;

/**
 * The caller sent something this service will not act on - an unknown sort column, a malformed
 * filter. Mapped to 400 by {@code ApiExceptionHandler}.
 *
 * <p>Separate from {@code RbacException} deliberately. That one means "you may not"; this one means
 * "that request does not make sense", and conflating them produces 403s for typos.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
