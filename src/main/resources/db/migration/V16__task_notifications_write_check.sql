-- ============================================================================
-- V16__task_notifications_write_check.sql
--
-- V8 gave task_notifications a policy whose WITH CHECK clause was identical to
-- its USING clause:
--
--     "User_ID" = current_setting('app.current_user_id', true)
--
-- That is correct for reads and impossible for writes. A notification exists to
-- tell SOMEBODY ELSE something -- the assignee that work has been given to them,
-- the assigner that it is done. Under the V8 clause the only notification a user
-- could insert was one addressed to themselves, so the entire feature refused at
-- the database with "new row violates row-level security policy".
--
-- This surfaced when the Phase 3 tasks endpoints were built: five integration
-- tests, all of them the ones where one user notifies another. It is very
-- unlikely the clause was written deliberately -- the far more probable story is
-- that it was copied from the USING clause when the policy was drafted, and that
-- the legacy backend never hit it because it connects as a role that is not
-- subject to the policy. Worth confirming against the QA database before cutover,
-- because if the legacy backend IS subject to it, notifications have been
-- silently failing in production and nobody noticed.
--
-- USING IS DELIBERATELY UNCHANGED. Reads stay strictly per-user, which is the
-- valuable half: task_notifications remains the one table in the schema where a
-- colleague, and an Admin, still cannot see your rows. Only the write side moves.
--
-- The trade being made: any user may now write a notification addressed to any
-- colleague in their own organisation, with arbitrary message text. That is
-- exactly what the application does on their behalf, and it cannot cross a tenant
-- boundary. It does mean a notification is not proof of who triggered it -- the
-- audit trail in events_log is, and that has its own WITH CHECK tying each row to
-- the acting user.
--
-- Derived the same way V10 derives tenancy for events_log: by joining to the
-- recipient and comparing their organisation to app.current_user_tenant. Note the
-- users policy applies inside this subquery too, so a recipient outside the
-- caller's organisation is invisible here and the EXISTS fails -- the check holds
-- even if this clause is read in isolation.
-- ============================================================================

DROP POLICY IF EXISTS task_notifications_rls_policy ON task_notifications;

CREATE POLICY task_notifications_rls_policy ON task_notifications
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR "User_ID" = current_setting('app.current_user_id', true)
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM users recipient
      WHERE recipient."User_ID" = task_notifications."User_ID"
        AND recipient."Organization_Name" = current_setting('app.current_user_tenant', true)
    )
  );
