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
 * Maps the `tasks` table from V8__tasks.sql.
 *
 * <p><b>Its RLS policy is materially weaker than every other tenant table, and that is worth
 * knowing before building on it.</b> The whole predicate is: does the ASSIGNEE belong to the
 * current tenant? There is no owner clause and no role clause. So any Sales_Rep sees every task in
 * their organisation, including tasks assigned to and by other people. Contrast contacts and deals,
 * where a Sales_Rep sees only their own.
 *
 * <p>That is faithful to the JS original, so it is ported as-is and pinned by
 * TasksIsolationIT.everyColleagueInTheOrganisationSeesEveryTask rather than quietly tightened. If
 * the product wants task-level privacy, that is a deliberate policy change with a failing test to
 * prove it landed.
 *
 * <p>Note the whole group derives tenancy from tasks.Assigned_To - attachments, comments and
 * reactions all join back to it. Assigned_By plays no part in visibility.
 *
 * <p>Description is the only nullable column. Is_Read defaults to false in both the DDL and Java,
 * and Status defaults to 'Open' in the database only - set it explicitly when inserting, since
 * Java's default is null and the column is NOT NULL.
 */
@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
public class TaskEntity {

    @Id
    @Column(name = "Task_ID", nullable = false, updatable = false)
    private String taskId;

    @Column(name = "Task_Title", nullable = false)
    private String taskTitle;

    /** The only nullable column on this table. */
    @Column(name = "Description")
    private String description;

    /** FK -> users(User_ID). The column every policy in this group joins on to derive tenancy. */
    @Column(name = "Assigned_To", nullable = false)
    private String assignedTo;

    /** FK -> users(User_ID). Recorded for audit; plays no part in RLS visibility. */
    @Column(name = "Assigned_By", nullable = false)
    private String assignedBy;

    /** TEXT column (application-supplied), not a timestamp. */
    @Column(name = "Due_Date", nullable = false)
    private String dueDate;

    /** DB default 'Open'; Java default is null and the column is NOT NULL, so set it on insert. */
    @Column(name = "Status", nullable = false)
    private String status;

    /** Field is `read`, not `isRead` - see ProductEntity.active for why the name matters. */
    @Column(name = "Is_Read", nullable = false)
    private boolean read;

    @Column(name = "Created_At", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
