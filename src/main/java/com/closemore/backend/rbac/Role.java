package com.closemore.backend.rbac;

public enum Role {
    ADMIN("Admin"),
    EXECUTIVE("Executive"),
    SALES_REP("Sales_Rep");

    private final String dbValue;

    Role(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static Role fromDbValue(String value) {
        for (Role role : values()) {
            if (role.dbValue.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown Role value: " + value);
    }
}
