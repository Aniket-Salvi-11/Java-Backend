package com.closemore.backend.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Registration policy and self-service signup.
 *
 * <p><b>Why JdbcTemplate and not a repository</b> - the same reason LoginService gives. Both
 * endpoints run before any tenant context exists, so the users policy would refuse every query a
 * JPA repository could make. V17's SECURITY DEFINER functions are the only way in, and they are
 * called directly rather than wrapped in an entity mapping that would imply a persistence context
 * this code does not have.
 *
 * <p>The role and status rules are NOT here. They live in auth_signup, because deciding in Java
 * would mean SELECT-then-INSERT and two concurrent first signups would both become Admin. See the
 * header of V17 for the full account.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final String POLICY_SQL = """
            SELECT "Bootstrap", "Has_Active_Admin"
            FROM auth_registration_policy(?)
            """;

    private static final String SIGNUP_SQL = """
            SELECT "User_ID", "First_Name", "Last_Name", "Email", "Role", "Status",
                   "Organization_Name"
            FROM auth_signup(?, ?, ?, ?, ?, ?, ?)
            """;

    private static final RowMapper<SignupResponse> SIGNUP_ROW_MAPPER = (rs, rowNum) ->
            new SignupResponse(
                    rs.getString("User_ID"),
                    rs.getString("First_Name"),
                    rs.getString("Last_Name"),
                    rs.getString("Email"),
                    rs.getString("Role"),
                    rs.getString("Status"),
                    rs.getString("Organization_Name"));

    private final JdbcTemplate jdbcTemplate;
    private final PasswordService passwordService;

    /** What kind of signup this would be, for one organisation. */
    public RegistrationPolicyResponse policyFor(String organizationName) {
        String organisation = required(organizationName, "Organization_Name is required");

        List<RegistrationPolicyResponse> rows = jdbcTemplate.query(
                POLICY_SQL,
                (rs, rowNum) -> RegistrationPolicyResponse.of(
                        rs.getBoolean("Bootstrap"), rs.getBoolean("Has_Active_Admin")),
                organisation);

        // The function always returns exactly one row, but an empty result would otherwise
        // surface as an IndexOutOfBounds rather than something a caller can read.
        if (rows.isEmpty()) {
            throw new AuthenticationException(500, "Registration policy unavailable");
        }
        return rows.get(0);
    }

    /**
     * Creates an account.
     *
     * <p>Hashes with BCrypt rather than storing the plaintext the JS backend keeps - Finding 1 in
     * the migration plan, resolved in favour of fixing. New accounts therefore never have a
     * Password column value at all, only Password_Hash; LoginService already handles both, since
     * it has to for the users being lazily migrated.
     */
    public SignupResponse signup(SignupRequest request) {
        String email = required(request.Email(), "Email is required")
                .toLowerCase(Locale.ROOT);
        String organisation = required(request.Organization_Name(), "Organization_Name is required");
        String firstName = required(request.First_Name(), "First_Name is required");
        String lastName = required(request.Last_Name(), "Last_Name is required");
        String password = required(request.Password(), "Password is required");

        // Null means "no preference", which is the common case: the signup form only offers a role
        // when registration-policy says privileged roles are available.
        String requestedRole = request.Role() == null || request.Role().isBlank()
                ? "Sales_Rep"
                : request.Role();
        if (!List.of("Sales_Rep", "Executive", "Admin").contains(requestedRole)) {
            throw new AuthenticationException(400, "Unknown role " + requestedRole);
        }

        try {
            List<SignupResponse> rows = jdbcTemplate.query(
                    SIGNUP_SQL,
                    SIGNUP_ROW_MAPPER,
                    UUID.randomUUID().toString(),
                    firstName,
                    lastName,
                    email,
                    passwordService.hash(password),
                    organisation,
                    requestedRole);

            if (rows.isEmpty()) {
                throw new AuthenticationException(500, "Registration failed");
            }
            return rows.get(0);
        } catch (DuplicateKeyException alreadyRegistered) {
            // V13's unique index on lower("Email") is what catches this. 409 rather than 400:
            // the request was well-formed, the state was not.
            throw new AuthenticationException(409, "An account with that email already exists");
        }
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new AuthenticationException(400, message);
        }
        return value.trim();
    }
}
