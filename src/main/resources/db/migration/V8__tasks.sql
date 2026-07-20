-- Create Tasks Table
CREATE TABLE IF NOT EXISTS tasks (
  "Task_ID" TEXT PRIMARY KEY,
  "Task_Title" TEXT NOT NULL,
  "Description" TEXT,
  "Assigned_To" TEXT NOT NULL REFERENCES users("User_ID") ON DELETE CASCADE,
  "Assigned_By" TEXT NOT NULL REFERENCES users("User_ID") ON DELETE CASCADE,
  "Due_Date" TEXT NOT NULL,
  "Status" TEXT NOT NULL DEFAULT 'Open', -- 'Open' | 'In Progress' | 'Done'
  "Is_Read" BOOLEAN NOT NULL DEFAULT FALSE,
  "Created_At" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create Task Attachments Table
CREATE TABLE IF NOT EXISTS task_attachments (
  "Attachment_ID" TEXT PRIMARY KEY,
  "Task_ID" TEXT NOT NULL REFERENCES tasks("Task_ID") ON DELETE CASCADE,
  "File_Name" TEXT NOT NULL,
  "Mime_Type" TEXT NOT NULL,
  "File_Size" INTEGER NOT NULL,
  "Storage_Path" TEXT NOT NULL,
  "Uploaded_By" TEXT NOT NULL REFERENCES users("User_ID") ON DELETE CASCADE,
  "Uploaded_At" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create Task Comments Table
CREATE TABLE IF NOT EXISTS task_comments (
  "Comment_ID" TEXT PRIMARY KEY,
  "Task_ID" TEXT NOT NULL REFERENCES tasks("Task_ID") ON DELETE CASCADE,
  "User_ID" TEXT NOT NULL REFERENCES users("User_ID") ON DELETE CASCADE,
  "User_Name" TEXT NOT NULL,
  "Content" TEXT NOT NULL,
  "Attachment_URL" TEXT,
  "Created_At" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create Comment Reactions Table
CREATE TABLE IF NOT EXISTS comment_reactions (
  "Reaction_ID" TEXT PRIMARY KEY,
  "Comment_ID" TEXT NOT NULL REFERENCES task_comments("Comment_ID") ON DELETE CASCADE,
  "User_ID" TEXT NOT NULL REFERENCES users("User_ID") ON DELETE CASCADE,
  "Emoji" TEXT NOT NULL,
  UNIQUE("Comment_ID", "User_ID", "Emoji")
);

-- Create Task Notifications Table
CREATE TABLE IF NOT EXISTS task_notifications (
  "Notification_ID" TEXT PRIMARY KEY,
  "User_ID" TEXT NOT NULL REFERENCES users("User_ID") ON DELETE CASCADE,
  "Task_ID" TEXT NOT NULL REFERENCES tasks("Task_ID") ON DELETE CASCADE,
  "Message" TEXT NOT NULL,
  "Is_Read" BOOLEAN NOT NULL DEFAULT FALSE,
  "Created_At" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Enable RLS and Force RLS for tenant company isolation
ALTER TABLE tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks FORCE ROW LEVEL SECURITY;
ALTER TABLE task_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_attachments FORCE ROW LEVEL SECURITY;
ALTER TABLE task_comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_comments FORCE ROW LEVEL SECURITY;
ALTER TABLE comment_reactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE comment_reactions FORCE ROW LEVEL SECURITY;
ALTER TABLE task_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_notifications FORCE ROW LEVEL SECURITY;

-- Create Tenant isolation RLS Policies
CREATE POLICY tasks_rls_policy ON tasks
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM users u
      WHERE u."User_ID" = tasks."Assigned_To"
        AND u."Organization_Name" = current_setting('app.current_user_tenant', true)
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM users u
      WHERE u."User_ID" = tasks."Assigned_To"
        AND u."Organization_Name" = current_setting('app.current_user_tenant', true)
    )
  );

CREATE POLICY task_attachments_rls_policy ON task_attachments
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM tasks t
      JOIN users u ON u."User_ID" = t."Assigned_To"
      WHERE t."Task_ID" = task_attachments."Task_ID"
        AND u."Organization_Name" = current_setting('app.current_user_tenant', true)
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM tasks t
      JOIN users u ON u."User_ID" = t."Assigned_To"
      WHERE t."Task_ID" = task_attachments."Task_ID"
        AND u."Organization_Name" = current_setting('app.current_user_tenant', true)
    )
  );

CREATE POLICY task_comments_rls_policy ON task_comments
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM tasks t
      JOIN users u ON u."User_ID" = t."Assigned_To"
      WHERE t."Task_ID" = task_comments."Task_ID"
        AND u."Organization_Name" = current_setting('app.current_user_tenant', true)
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM tasks t
      JOIN users u ON u."User_ID" = t."Assigned_To"
      WHERE t."Task_ID" = task_comments."Task_ID"
        AND u."Organization_Name" = current_setting('app.current_user_tenant', true)
    )
  );

CREATE POLICY comment_reactions_rls_policy ON comment_reactions
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM task_comments tc
      JOIN tasks t ON t."Task_ID" = tc."Task_ID"
      JOIN users u ON u."User_ID" = t."Assigned_To"
      WHERE tc."Comment_ID" = comment_reactions."Comment_ID"
        AND u."Organization_Name" = current_setting('app.current_user_tenant', true)
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM task_comments tc
      JOIN tasks t ON t."Task_ID" = tc."Task_ID"
      JOIN users u ON u."User_ID" = t."Assigned_To"
      WHERE tc."Comment_ID" = comment_reactions."Comment_ID"
        AND u."Organization_Name" = current_setting('app.current_user_tenant', true)
    )
  );

CREATE POLICY task_notifications_rls_policy ON task_notifications
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR "User_ID" = current_setting('app.current_user_id', true)
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR "User_ID" = current_setting('app.current_user_id', true)
  );
