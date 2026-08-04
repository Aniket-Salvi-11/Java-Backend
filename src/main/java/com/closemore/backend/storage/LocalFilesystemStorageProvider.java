package com.closemore.backend.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores uploads on the local filesystem under a configured root.
 *
 * <p>The default implementation, and the one CI runs. S3 and OCI Object Storage implementations
 * belong with the deployment work: adding either now means a vendor SDK in pom.xml for code nothing
 * can exercise, and a pom change invalidates the Maven cache and provokes the Maven Central 429s
 * that gotcha 11 records.
 *
 * <p><b>The client's filename is never used as a path.</b> A browser will send whatever the operating
 * system gave it, and a hostile client will send whatever it likes - {@code ../../application.yml},
 * an absolute path, a name with a null byte. Every stored file gets a fresh UUID as its actual name;
 * the original is reduced to a short, sanitised suffix so a human browsing the directory can still
 * tell what a file is. The real filename lives in the database column, where it is data rather than
 * an instruction.
 *
 * <p>The resolved path is then checked against the root a second time before any write. That check
 * is redundant given the sanitising above, and it stays: path traversal is the failure that turns a
 * file upload endpoint into arbitrary file write, and one belt plus one pair of braces is cheap.
 */
@Component
@ConditionalOnProperty(name = "closemore.storage.provider",
        havingValue = "local", matchIfMissing = true)
public class LocalFilesystemStorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalFilesystemStorageProvider.class);

    /** Anything outside this set is replaced, so no separator or traversal token can survive. */
    private static final String UNSAFE_CHARACTERS = "[^A-Za-z0-9._-]";

    private static final int MAX_SUFFIX_LENGTH = 60;

    private final Path root;

    public LocalFilesystemStorageProvider(
            @Value("${closemore.storage.local.root:./uploads}") String configuredRoot) {
        this.root = Paths.get(configuredRoot).toAbsolutePath().normalize();
    }

    @Override
    public String store(String keyPrefix, String fileName, byte[] content) {
        Path directory = resolveWithinRoot(sanitise(keyPrefix));
        Path target = directory.resolve(UUID.randomUUID() + "-" + sanitise(fileName));

        // Second check, on the fully resolved target rather than the prefix. See the class comment.
        if (!target.normalize().startsWith(root)) {
            throw new StorageException("Refusing to write outside the storage root");
        }

        try {
            Files.createDirectories(directory);
            Files.write(target, content);
        } catch (IOException ex) {
            throw new StorageException("Could not store file", ex);
        }

        // Relative to the root, so the stored value survives the root moving between environments -
        // which it does, because local, QA and production do not share a directory layout.
        return root.relativize(target).toString().replace('\\', '/');
    }

    @Override
    public byte[] read(String storagePath) {
        Path target = resolveWithinRoot(storagePath);
        try {
            return Files.readAllBytes(target);
        } catch (IOException ex) {
            throw new StorageException("Could not read stored file", ex);
        }
    }

    @Override
    public void delete(String storagePath) {
        try {
            // deleteIfExists, not delete. See the interface comment: an already-missing file is the
            // expected outcome of a retry, and throwing would leave a row that can never be removed.
            Files.deleteIfExists(resolveWithinRoot(storagePath));
        } catch (IOException ex) {
            // Logged, not thrown. The caller is mid-transaction deleting a database row, and an
            // orphaned file on disk is a cleanup problem; an undeletable row is a user-facing one.
            log.warn("Could not delete stored file {} - the database row is being removed anyway",
                    storagePath, ex);
        }
    }

    private Path resolveWithinRoot(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new StorageException("Refusing to access a path outside the storage root");
        }
        return resolved;
    }

    /**
     * Reduces arbitrary client input to something safe to put in a path.
     *
     * <p>Replacing rather than rejecting: an upload should not fail because a filename contains a
     * space or an accented character, and the original name is preserved verbatim in the database
     * either way.
     */
    private String sanitise(String value) {
        if (value == null || value.isBlank()) {
            return "file";
        }
        String cleaned = value.toLowerCase(Locale.ROOT).replaceAll(UNSAFE_CHARACTERS, "_");

        // Leading dots would otherwise survive sanitising and produce hidden files, and "..", having
        // lost its separator, would still be a confusing directory name.
        cleaned = cleaned.replaceAll("^\\.+", "");
        if (cleaned.isBlank()) {
            return "file";
        }
        return cleaned.length() > MAX_SUFFIX_LENGTH
                ? cleaned.substring(cleaned.length() - MAX_SUFFIX_LENGTH)
                : cleaned;
    }
}
