package com.closemore.backend.context;

public record RequestUserContext(
        String userId,
        String role,
        String organizationName
) {
    public String organizationNameOrEmpty() {
        return organizationName == null ? "" : organizationName;
    }
}
