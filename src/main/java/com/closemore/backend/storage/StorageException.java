package com.closemore.backend.storage;

/**
 * A storage operation failed for a reason the caller cannot do anything about - the disk, the
 * network, a missing object.
 *
 * <p>Unchecked deliberately. Every call site sits inside a transactional service method that has no
 * meaningful recovery available: it cannot conjure the bytes back, and the only sensible response is
 * to let the transaction roll back and report a 500. Forcing each of them to catch and rethrow would
 * add noise and invite an empty catch block.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
