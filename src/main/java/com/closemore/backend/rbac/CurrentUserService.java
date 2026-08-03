package com.closemore.backend.rbac;

import com.closemore.backend.context.RequestUserContext;
import com.closemore.backend.context.RequestUserContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Turns the per-request context the JWT filter established into the {@link AuthenticatedUser} that
 * {@link RbacService} takes.
 *
 * <p>Two representations exist because they answer different questions. {@link RequestUserContext}
 * carries role and tenant as raw strings, because that is what {@code set_config} takes.
 * {@link AuthenticatedUser} carries a typed {@link Role}, because the service layer branches on it.
 * Converting in one place means the two failure paths below are handled once rather than in each of
 * the twelve resource groups.
 *
 * <p><b>Both failure paths matter.</b> A null context means a request reached a service without
 * passing the JWT filter. The filter rejects tokenless requests, so this should be unreachable - but
 * "should be unreachable" is how a shortcut survives into production. An unparseable role means the
 * token carries a role string no longer in the enum, which is what a botched role rename looks like:
 * {@code Role.fromDbValue} throws {@link IllegalArgumentException}, and left alone that surfaces as
 * a 500. Neither is a server fault, so neither is a 500.
 *
 * <p>Public class, public method, on purpose - called from {@code service}. Package-private members
 * called across packages have broken this build twice.
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final RequestUserContextHolder contextHolder;

    public AuthenticatedUser require() {
        RequestUserContext context = contextHolder.get();
        if (context == null) {
            throw new RbacException(401, "Not authenticated");
        }
        try {
            return new AuthenticatedUser(context.userId(), Role.fromDbValue(context.role()));
        } catch (IllegalArgumentException ex) {
            throw new RbacException(403, "Unrecognised role");
        }
    }
}
