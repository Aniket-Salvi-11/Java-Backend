package com.closemore.backend.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password hashing. Wraps Spring Security's BCryptPasswordEncoder, which is already on the
 * classpath via spring-boot-starter-security - no new dependency.
 *
 * <p>BCrypt rather than a plain SHA digest because it is deliberately slow and salts every hash
 * individually. Two users with the same password get different digests, so a leaked table cannot
 * be attacked by looking up precomputed hashes.
 *
 * <p>Strength 10 is the Spring default: roughly 100ms per verification on modern hardware. High
 * enough to make brute force impractical, low enough not to be a denial-of-service vector on the
 * login endpoint itself. Raising it later is safe - the cost factor is embedded in each stored
 * hash, so old hashes keep verifying at their original strength and get upgraded naturally as
 * users log in.
 *
 * <p>Note BCrypt silently truncates input beyond 72 bytes. Not a practical concern here, but worth
 * knowing before adding a "maximum password length" validation that contradicts it.
 */
@Service
public class PasswordService {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * Constant-time comparison of a candidate password against a stored bcrypt digest. Returns
     * false rather than throwing when the stored value is null or malformed, so a corrupt row
     * fails login rather than failing the request with a 500.
     */
    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            return encoder.matches(rawPassword, storedHash);
        } catch (IllegalArgumentException ex) {
            // BCryptPasswordEncoder throws this for a stored value that is not a bcrypt hash at
            // all - e.g. a plaintext password that reached this column by mistake.
            return false;
        }
    }
}
