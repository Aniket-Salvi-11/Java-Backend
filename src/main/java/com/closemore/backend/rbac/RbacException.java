package com.closemore.backend.rbac;

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
