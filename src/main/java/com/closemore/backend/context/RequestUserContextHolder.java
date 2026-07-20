package com.closemore.backend.context;

import org.springframework.stereotype.Component;

/**
 * Java equivalent of `export const authContextStorage = new AsyncLocalStorage()` in db.ts.
 *
 * Backed by a ThreadLocal rather than Node's AsyncLocalStorage because Spring MVC's default
 * (non-reactive, non-virtual-thread-async) request handling keeps one request on one thread
 * for its whole lifecycle - the same assumption db.ts relies on. If the service later adopts
 * virtual threads with structured concurrency that hops threads mid-request, this holder needs
 * to move to something thread-hop-safe (e.g. Micrometer's context propagation); flag that as a
 * follow-up rather than a Phase 0 concern.
 *
 * {@link com.closemore.backend.filter.UserContextFilter} is the only writer. It MUST clear the
 * ThreadLocal in a finally block - Tomcat reuses worker threads across requests, so a value left
 * behind here would leak one request's identity into the next request handled by that thread.
 */
@Component
public class RequestUserContextHolder {

    private static final ThreadLocal<RequestUserContext> CURRENT = new ThreadLocal<>();

    public void set(RequestUserContext context) {
        CURRENT.set(context);
    }

    /** Returns null when no authenticated user is attached to this request (mirrors getStore() returning undefined). */
    public RequestUserContext get() {
        return CURRENT.get();
    }

    public void clear() {
        CURRENT.remove();
    }
}
