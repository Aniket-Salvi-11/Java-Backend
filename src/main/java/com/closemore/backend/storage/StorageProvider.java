package com.closemore.backend.storage;

/**
 * Where uploaded files live. No vendor type in any signature.
 *
 * <p>Section 4 of the migration plan records that storage is dual-mode in the Next.js backend -
 * local filesystem or S3, switched by an environment variable - and v4 added a third case: OCI
 * Object Storage, for the client whose deployment runs on Oracle Cloud. Three implementations, one
 * interface, selected by {@code closemore.storage.provider}.
 *
 * <p><b>The returned path is opaque to callers and stored verbatim.</b> Nothing outside an
 * implementation may parse, join to, or construct one - it is a filesystem path under local storage
 * and an object key under S3 or OCI, and code that assumes either shape breaks silently on the
 * other. The database column {@code Storage_Path} holds whatever {@link #store} returned and hands
 * it straight back to {@link #read} or {@link #delete}.
 *
 * <p>OCI Object Storage exposes an S3-compatible API, so the OCI implementation may turn out to be
 * the S3 one with a different endpoint. Worth confirming before writing a third class.
 */
public interface StorageProvider {

    /**
     * Stores content and returns the opaque path to retrieve it by.
     *
     * @param keyPrefix   groups related files - callers pass the owning activity's id
     * @param fileName    the client's filename, used only to derive a readable suffix; implementations
     *                    must NOT trust it as a path component
     * @param content     the bytes
     */
    String store(String keyPrefix, String fileName, byte[] content);

    /** Reads content back. Throws {@link StorageException} if the path no longer resolves. */
    byte[] read(String storagePath);

    /**
     * Removes content. Must not throw when the path is already gone.
     *
     * <p>Delete is called while removing a database row, and the two cannot be made atomic across a
     * filesystem or an object store. An already-missing file is the expected outcome of a retry, and
     * failing on it would leave a row that can never be deleted.
     */
    void delete(String storagePath);
}
