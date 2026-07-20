package com.closemore.backend.aop;

import com.closemore.backend.context.RequestUserContext;
import com.closemore.backend.context.RequestUserContextHolder;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Java port of the set_config block that appears twice in db.ts (query() and withTransaction()) -
 * reference doc, Section 5. This is Phase 0's highest-risk piece; do not modify the ordering or
 * pointcut without re-running TenantIsolationIT (src/test/.../tenant/TenantIsolationIT.java).
 *
 * WHAT THIS DOES AND WHY IT'S SAFE (mirrors the three bullet points in the reference doc):
 *
 *   1. It only ever runs for methods annotated @Transactional, and only fires the three
 *      set_config(..., true) calls (the `true` = "local to this transaction only", same as
 *      the original) as the FIRST statements after BEGIN and before any entity query executes.
 *
 *   2. It issues those set_config calls through the injected JdbcTemplate, which shares the
 *      SAME DataSource as the JPA EntityManager. Because this advice runs *inside* an
 *      already-open Spring-managed transaction (see the @Order note below), Spring's
 *      DataSourceUtils binds JdbcTemplate to the exact same physical Connection that Hibernate
 *      will use for the rest of the method - this is what "same client/connection... inside the
 *      same BEGIN...COMMIT block" means in Java terms.
 *
 *   3. Nothing is ever set at the DataSource/connection-pool level - only inside a live
 *      transaction, and set_config's third argument (true) makes Postgres clear it automatically
 *      on COMMIT/ROLLBACK. Combined with HikariCP only releasing the connection back to the pool
 *      after the transaction ends, a pooled connection is always "clean" before reuse - exactly
 *      the invariant the reference doc calls out.
 *
 * ORDERING IS THE WHOLE GAME: @Order(1) here, combined with
 * @EnableTransactionManagement(order = 0) on the application class, guarantees Spring's
 * transaction advisor wraps OUTSIDE this aspect. That means the call sequence for any
 * @Transactional service method is:
 *   proxy -> [transaction advisor: BEGIN] -> [this aspect: 3x set_config] -> method body -> COMMIT
 * If that ordering were ever reversed, set_config would either run before BEGIN (on a
 * connection not yet bound to the transaction - a no-op) or not run at all for methods that
 * don't call through this proxy chain correctly. Get this wrong and you get the "dangerous
 * failure mode" from the reference doc: a leftover session variable from a previous request
 * leaking into the next one on a reused pooled connection.
 */
@Aspect
@Component
@Order(1)
@RequiredArgsConstructor
public class TenantContextAspect {

    private final RequestUserContextHolder contextHolder;
    private final JdbcTemplate jdbcTemplate;

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object setTenantSessionVariables(ProceedingJoinPoint joinPoint) throws Throwable {
        RequestUserContext ctx = contextHolder.get();

        // No authenticated user (e.g. an unauthenticated health check, or a system/batch job
        // that should use app.bypass_rls instead) - matches db.ts's `if (user)` guard exactly.
        // Nothing is set; RLS then denies all rows by default, which is the SAFE failure mode.
        if (ctx != null) {
            jdbcTemplate.update("SELECT set_config(?, ?, true)", "app.current_user_id", ctx.userId());
            jdbcTemplate.update("SELECT set_config(?, ?, true)", "app.current_user_role", ctx.role());
            jdbcTemplate.update("SELECT set_config(?, ?, true)", "app.current_user_tenant", ctx.organizationNameOrEmpty());
        }

        return joinPoint.proceed();
    }
}
