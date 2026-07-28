package com.closemore.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Maps the `users` table exactly as built by V1__init.sql + V2__columns.sql + V5 (timestamps).
 *
 * MAPPING RULES THAT MUST HOLD (reference doc Section 9, Migration Plan Section 4):
 *  - Every column is PascalCase and quoted in the DDL. Hibernate's implicit naming strategy would
 *    lower-case and snake-case these, silently failing to match. Every @Column(name=...) below is
 *    therefore explicit and case-exact. `ddl-auto: validate` is the guard: if any mapping drifts
 *    from the real column, the context fails to start rather than misbehaving at runtime.
 *  - Primary key is an application-generated TEXT value (no @GeneratedValue). Only events_log uses
 *    a database serial - do not copy a generation strategy onto this entity.
 *  - Password is deliberately mapped (it exists in the schema and login reads it) but is never
 *    exposed via a DTO. See UserResponse. Finding 1 (plaintext) is a Phase 2 decision; the entity
 *    just reflects the column as-is.
 *  - Created_At / Updated_At are managed by a Postgres trigger (update_modified_column in V5), NOT
 *    by Hibernate. They are mapped insertable=false, updatable=false so JPA reads them but never
 *    tries to write them - otherwise Hibernate and the trigger would fight over Updated_At.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class UserEntity {

    @Id
    @Column(name = "User_ID", nullable = false, updatable = false)
    private String userId;

    @Column(name = "First_Name", nullable = false)
    private String firstName;

    @Column(name = "Last_Name", nullable = false)
    private String lastName;

    @Column(name = "Email", nullable = false, unique = true)
    private String email;

    @Column(name = "Role", nullable = false)
    private String role;

    @Column(name = "Status", nullable = false)
    private String status;

    /** Nullable in the schema. Never surfaced in any DTO. */
    @Column(name = "Password")
    private String password;

    @Column(name = "Avatar_Data_URL")
    private String avatarDataUrl;

    @Column(name = "Avatar_Updated_At")
    private String avatarUpdatedAt;

    @Column(name = "Phone_Number")
    private String phoneNumber;

    /** The tenancy key for the entire system. Nullable in the raw schema (added by V2 ALTER). */
    @Column(name = "Organization_Name")
    private String organizationName;

    @Column(name = "Residential_Address")
    private String residentialAddress;

    @Column(name = "Office_Address")
    private String officeAddress;

    @Column(name = "Created_At", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "Updated_At", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
