package com.closemore.backend.controller;

import com.closemore.backend.auth.AuthService;
import com.closemore.backend.auth.LoginRequest;
import com.closemore.backend.auth.RefreshRequest;
import com.closemore.backend.auth.SessionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three session endpoints. First real HTTP surface in the project.
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
}
