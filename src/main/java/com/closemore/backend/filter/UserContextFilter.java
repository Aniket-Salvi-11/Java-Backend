package com.closemore.backend.filter;

import com.closemore.backend.context.RequestUserContext;
import com.closemore.backend.context.RequestUserContextHolder;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Java port of the request-entry piece of src/lib/auth.ts (getAuthContext), scoped to what
 * Phase 0 needs: proving out the RLS session-variable mechanism (Section 5 of the reference
 * doc) end-to-end before real auth exists.
 *
 * TEMPORARY, PHASE 0 ONLY. The original getAuthContext() only trusts X-User-ID and then loads
 * Role/Organization_Name from the users table. That table lookup needs the User JPA repository,
 * which doesn't exist until Phase 1. So for Phase 0 this filter also reads X-User-Role /
 * X-User-Tenant directly off the request, purely so the tenant-isolation integration test can
 * drive real requests through the full filter -> AOP -> set_config path without a User entity yet.
 *
 * WHY @Profile("!prod"): a client-supplied tenant header defeats the entire RLS design - anyone
 * who can set a header can read any organisation's data. Documenting that in a comment is not
 * enough, because the failure mode of forgetting is a cross-tenant breach. This bean simply does
 * not exist under the `prod` profile, so the shortcut cannot be deployed by accident. With it
 * absent, no tenant context is ever set, RLS denies every row, and the failure is loud and safe.
 *
 * TODO (Phase 2, "Authentication"): once Finding 2 is decided, replace this with either (a) the
 * header-trust lookup against UserRepository - X-User-ID only, Role and Organization_Name read
 * from the users row, never from the client - or (b) JWT validation. Delete the @Profile guard at
 * that point, not before.
 */
@Component
@Profile("!prod")
@Slf4j
@RequiredArgsConstructor
public class UserContextFilter extends OncePerRequestFilter {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";
    private static final String USER_TENANT_HEADER = "X-User-Tenant";

    private final RequestUserContextHolder contextHolder;

    @PostConstruct
    void warnThisIsNotRealAuthentication() {
        log.warn("UserContextFilter is active: user id, ROLE and TENANT are read from client "
                + "request headers with no verification. Phase 0 scaffolding only - this bean is "
                + "excluded from the 'prod' profile and must be replaced in Phase 2.");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String userId = request.getHeader(USER_ID_HEADER);
        try {
            if (userId != null && !userId.isBlank()) {
                contextHolder.set(new RequestUserContext(
                        userId,
                        request.getHeader(USER_ROLE_HEADER),
                        request.getHeader(USER_TENANT_HEADER)
                ));
            }
            filterChain.doFilter(request, response);
        } finally {
            // Mirrors AsyncLocalStorage's automatic scoping: nothing from this request's
            // context should be visible once the request is done, especially since Tomcat
            // reuses this thread for the next request that lands on it.
            contextHolder.clear();
        }
    }
}