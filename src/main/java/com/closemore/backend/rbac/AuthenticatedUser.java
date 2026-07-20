package com.closemore.backend.rbac;

public record AuthenticatedUser(String userId, Role role) {
}
