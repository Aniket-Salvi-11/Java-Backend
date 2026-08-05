package com.closemore.backend.filter;

import com.closemore.backend.auth.AuthenticationException;
import com.closemore.backend.auth.JwtService;
import com.closemore.backend.context.RequestUserContext;
import com.closemore.backend.context.RequestUserContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Establishes request identity from a signed token. Replaces {@code UserContextFilter}, which read
 * X-User-Id, X-User-Role and X-User-Tenant straight off the wire and believed them.
 *
 * <p><b>What changes, concretely.</b> The old filter let a client choose its own tenant, which made
 * every RLS policy in the schema decorative - anyone able to set a header could read any
 * organisation's data. Here the same three values come from claims inside a token this service
 * signed, so altering them requires the signing key. The rest of the chain is untouched: the values
 * still land in {@link RequestUserContextHolder}, TenantContextAspect still copies them into the
 * Postgres session variables, and the policies still do the enforcing.
 *
 * <p><b>The @Profile("!prod") guard is gone</b>, from this filter and from SecurityConfig. It
 * existed to make the Phase 0 shortcut impossible to deploy; with the shortcut removed there is
 * nothing left to guard against, and keeping it would mean the real authentication filter did not
 * exist in production.
 *
 * <p><b>Fails closed twice over.</b> A request without a usable token is rejected here with 401.
 * But even if that were bypassed, no context would be set, the session variables would be unset,
 * and every tenant policy would match zero rows - the request would see nothing rather than
 * everything. The 401 exists to make that a clear error rather than a puzzling empty list.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Paths reachable without a token. Necessarily just the session endpoints - you cannot present
     * a token to obtain your first token.
     *
     * <p>Kept as an explicit prefix list rather than a permissive pattern: every future addition
     * should be a deliberate decision, since anything on this list is public internet surface.
     */
    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout",
            // Tranche 5a. Both are pre-account by definition: registration-policy is what the
            // signup form calls to decide what to render, and signup is how an account comes to
            // exist. Requiring a token on either would make them unreachable.
            "/api/auth/registration-policy",
            "/api/auth/signup",
            // Tranche 7. A health probe cannot present a token, and one that demanded a token
            // would report every instance unhealthy - pulling the whole service out of rotation.
            "/api/health");

    private final JwtService jwtService;
    private final RequestUserContextHolder contextHolder;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = bearerTokenOf(request);
        if (token == null) {
            reject(response, "Missing or malformed Authorization header");
            return;
        }

        RequestUserContext context;
        try {
            Jwt jwt = jwtService.verify(token);
            context = new RequestUserContext(
                    jwtService.userIdOf(jwt),
                    jwtService.roleOf(jwt),
                    jwtService.tenantOf(jwt));
        } catch (AuthenticationException ex) {
            // Expired, tampered, wrong key, wrong issuer - all one response. Which of them applied
            // is useful to an attacker and irrelevant to a legitimate client, whose next move is to
            // refresh either way.
            reject(response, ex.getMessage());
            return;
        }

        try {
            contextHolder.set(context);
            filterChain.doFilter(request, response);
        } finally {
            // Tomcat reuses this thread for the next request that lands on it, so leaving the
            // context set would leak one user's tenant into another user's request. The same
            // reasoning as the original filter's finally block, and it is load-bearing:
            // TenantIsolationIT asserts it.
            contextHolder.clear();
        }
    }

    private String bearerTokenOf(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Writes the same {@code {"error": "..."}} envelope ApiExceptionHandler uses. Written directly
     * rather than by throwing, because a filter rejection happens before Spring MVC is involved and
     * @RestControllerAdvice would never see it.
     */
    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
