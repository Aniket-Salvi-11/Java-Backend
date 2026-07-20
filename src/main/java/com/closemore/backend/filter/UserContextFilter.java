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
