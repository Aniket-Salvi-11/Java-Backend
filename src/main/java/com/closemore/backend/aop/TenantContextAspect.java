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
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Java port of the set_config block that appears twice in db.ts (query() and withTransaction()) -
 * reference doc, Section 5. This is Phase 0's highest-risk piece; do not modify the ordering or
 * pointcut without re-running TenantIsolationIT (src/test/.../tenant/TenantIsolationIT.java) via
 * `mvn verify`.
 *
 * WHAT THIS DOES AND WHY IT'S SAFE (mirrors the three bullet points in the reference doc):
 *
 *   1. It only ever runs for @Transactional methods, and fires the three set_config(..., true)
 *      calls (the `true` = "local to this transaction only", same as the original) as the FIRST
 *      statements after BEGIN and before any entity query executes.
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
 * If that ordering were ever reversed, set_config would either run before BEGIN (on a connection
 * not yet bound to the transaction) or not run at all. The assertion in
 * requireActiveTransaction() below turns that from a silent misconfiguration into a startup-time
 * / first-request-time failure.
 */
@Aspect
@Component
@Order(1)
@RequiredArgsConstructor
public class TenantContextAspect {

    private final RequestUserContextHolder contextHolder;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Both forms of the annotation are matched deliberately.
     *
     * `@annotation(...)` alone matches METHOD-level @Transactional only. A service written as
     * `@Service @Transactional public class DealService { ... }` - which is completely idiomatic
     * and will absolutely be written during Phase 3 - would then run every one of its methods with
     * no session variables set. Under RLS that fails safe (empty result sets) rather than
     * dangerously, but it is a multi-hour debugging session waiting to happen. `@within(...)`
     * covers the class-level case.
     */
    @Around("@annotation(org.springframework.transaction.annotation.Transactional)"
            + " || @within(org.springframework.transaction.annotation.Transactional)")
    public Object setTenantSessionVariables(ProceedingJoinPoint joinPoint) throws Throwable {
        RequestUserContext ctx = contextHolder.get();

        // No authenticated user (e.g. an unauthenticated health check, or a system/batch job
        // that should use app.bypass_rls instead) - matches db.ts's `if (user)` guard exactly.
        // Nothing is set; RLS then denies all rows by default, which is the SAFE failure mode.
        if (ctx != null) {
            requireActiveTransaction(joinPoint);

            // queryForObject, NOT update(). JdbcTemplate.update() calls executeUpdate(), and
            // pgjdbc throws "A result was returned when none was expected" for a statement that
            // returns rows - which SELECT set_config(...) does. set_config returns the value it
            // just set, so we read and discard it.
            setLocal("app.current_user_id", ctx.userId());
            setLocal("app.current_user_role", ctx.role());
            setLocal("app.current_user_tenant", ctx.organizationNameOrEmpty());
        }

        return joinPoint.proceed();
    }

    private void setLocal(String key, String value) {
        jdbcTemplate.queryForObject("SELECT set_config(?, ?, true)", String.class, key, value);
    }

    /**
     * Fails loudly if this advice ever runs outside a real transaction while a user context is
     * present. Without this the failure is silent: set_config(..., true) applied outside a
     * transaction is scoped to a statement Spring immediately returns to the pool, so the
     * following entity query runs with no tenant context and RLS returns zero rows - which looks
     * like "the data is missing" rather than "the isolation mechanism is broken".
     *
     * This fires if @EnableTransactionManagement(order = 0) is removed, if @Order(1) here is
     * changed, or if a method is annotated with a propagation level that doesn't open a
     * transaction (NOT_SUPPORTED / NEVER / SUPPORTS with no caller transaction).
     */
    private void requireActiveTransaction(ProceedingJoinPoint joinPoint) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "TenantContextAspect ran with a user context but no active transaction at "
                            + joinPoint.getSignature().toShortString()
                            + ". The RLS session variables would be set on a connection that is not "
                            + "the one running the query. Check @EnableTransactionManagement(order = 0), "
                            + "@Order(1) on this aspect, and the propagation level of that method. "
                            + "See reference doc Section 5.");
        }
    }
}