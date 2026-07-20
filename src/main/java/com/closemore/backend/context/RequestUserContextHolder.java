package com.closemore.backend.context;

import org.springframework.stereotype.Component;

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
