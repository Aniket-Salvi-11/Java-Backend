package com.closemore.backend.controller;

import com.closemore.backend.auth.AuthService;
import com.closemore.backend.auth.LoginRequest;
import com.closemore.backend.auth.RefreshRequest;
import com.closemore.backend.auth.RegistrationPolicyResponse;
import com.closemore.backend.auth.RegistrationService;
import com.closemore.backend.auth.SessionResponse;
import com.closemore.backend.auth.SignupRequest;
import com.closemore.backend.auth.SignupResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The session endpoints, plus the two registration endpoints tranche 5a added.
 *
 * <p>Deliberately thin - every decision lives in {@link AuthService}. Note also that none of these
 * are {@code @Transactional} and none touch a repository: they run before a tenant context exists,
 * which is precisely why the underlying services go through the SECURITY DEFINER functions rather
 * than JPA.
 *
 * <p>These paths must stay reachable without a token, for the obvious reason. When the JWT filter
 * lands it needs an allowlist covering exactly this controller.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RegistrationService registrationService;

    /**
     * Verifies credentials and opens a session.
     *
     * <p>Errors come back through ApiExceptionHandler: 400 for missing fields, 401 for bad
     * credentials, 403 for an account that exists but may not sign in.
     */
    @PostMapping("/login")
    public SessionResponse login(@RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.Email(), request.Password());
        return SessionResponse.of(result.tokens(), result.user());
    }

    /**
     * Exchanges a refresh token for a new pair. The presented token is consumed in the process, so
     * a client must replace its stored copy with the one returned here - reusing the old value
     * fails, by design.
     */
    @PostMapping("/refresh")
    public SessionResponse refresh(@RequestBody RefreshRequest request) {
        AuthService.LoginResult result = authService.refresh(request.refreshToken());
        return SessionResponse.of(result.tokens(), result.user());
    }

    /**
     * Ends this session. Always 204, whether or not the token was live - reporting the difference
     * would let a caller probe for other people's valid tokens.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * Reports what kind of signup this organisation would get, so the form can adapt.
     *
     * <p>Unauthenticated, like the rest of this controller - the caller has no account yet, which
     * is the whole point. It does mean an anonymous caller can learn whether an organisation name
     * is taken; that exposure is stated in the header of V17 and is flagged for QA sign-off.
     */
    @GetMapping("/registration-policy")
    public RegistrationPolicyResponse registrationPolicy(
            @RequestParam("organizationName") String organizationName) {
        return registrationService.policyFor(organizationName);
    }

    /**
     * Self-service registration.
     *
     * <p>201 with the created account, and never a session - see SignupResponse for why. The role
     * in the request is a preference; auth_signup decides what is actually stored.
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@RequestBody SignupRequest request) {
        return registrationService.signup(request);
    }
}
