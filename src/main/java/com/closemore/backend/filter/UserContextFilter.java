package com.closemore.backend.filter;

import com.closemore.backend.context.RequestUserContext;
import com.closemore.backend.context.RequestUserContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Java port of the request-entry piece of src/lib/auth.ts (getAuthContext), scoped to what
 * Phase 0 needs: proving out the RLS session-variable mechanism (Section 5 of the reference
 * doc) end-to-end before real auth exists.
 *
 * TEMPORARY, PHASE 0 ONLY: the original getAuthContext() only trusts X-User-ID and then loads
 * Role/Organization_Name from the users table. That table lookup needs the User JPA repository,
 * which doesn't exist until Phase 1 (step 8). So for Phase 0 this filter also reads
 * X-User-Role / X-User-Tenant directly off the request, purely so the tenant-isolation
 * integration test can drive real requests through the full filter -> AOP -> set_config path
 * without a User entity yet.
 *
 * TODO (Phase 2, item 10 "Authentication"): once Finding 2 is decided, replace the body of this
 * filter with either (a) the header-trust lookup against UserRepository, or (b) JWT validation.
 * Either way, X-User-Role / X-User-Tenant read from the client MUST be deleted at that point -
 * trusting a client-supplied role/tenant is only acceptable as a Phase 0 scaffolding shortcut.
 */
@Component
@RequiredArgsConstructor
public class UserContextFilter extends OncePerRequestFilter {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";
    private static final String USER_TENANT_HEADER = "X-User-Tenant";

    private final RequestUserContextHolder contextHolder;

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
