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

        if (ctx != null) {
            jdbcTemplate.update("SELECT set_config(?, ?, true)", "app.current_user_id", ctx.userId());
            jdbcTemplate.update("SELECT set_config(?, ?, true)", "app.current_user_role", ctx.role());
            jdbcTemplate.update("SELECT set_config(?, ?, true)", "app.current_user_tenant", ctx.organizationNameOrEmpty());
        }

        return joinPoint.proceed();
    }
}
