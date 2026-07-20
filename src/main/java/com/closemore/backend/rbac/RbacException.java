package com.closemore.backend.rbac;

/**
 * Java equivalent of `HttpError` as used throughout rbac.ts. Carries an HTTP status the
 * same way the original does (401 / 403), so a Phase 2 @ControllerAdvice can map it to the
 * same response shape the JS backend produces - kept as a plain RuntimeException here since
 * global exception handling is explicitly a Phase 2 item, not Phase 0.
 */
public class RbacException extends RuntimeException {

    private final int status;

    public RbacException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
