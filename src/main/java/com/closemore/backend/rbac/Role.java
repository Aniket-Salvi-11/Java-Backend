package com.closemore.backend.rbac;

/**
 * The Role column is a plain TEXT value in Postgres ('Admin' / 'Executive' / 'Sales_Rep'),
 * not a native enum - keep the exact string values here so @Column mappings and the RLS
 * policies (which compare current_setting('app.current_user_role', true) against these same
 * literal strings) stay in sync.
 */
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
