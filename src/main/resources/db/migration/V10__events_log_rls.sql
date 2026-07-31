-- ============================================================================
-- V10__events_log_rls.sql
--
-- events_log was the one table carrying cross-tenant data with no RLS on it.
-- V5 created it; V3/V4 never covered it, so any role that could reach the
-- table could read every tenant's audit trail -- including "Before_State" and
-- "After_State", which hold serialised record snapshots and are therefore the
-- most sensitive payload in the schema.
--
-- events_log has no "Organization_Name" of its own, so tenancy is derived the
-- same way V4 derives it for contacts and deals: by joining to the acting user
-- and comparing their organisation to app.current_user_tenant.
--
-- Note the WITH CHECK clause is not decorative. Without it the app could write
-- an audit row attributed to a user in another tenant -- i.e. forge an entry in
-- someone else's audit trail, which is worse than merely reading one.
-- ============================================================================

DROP POLICY IF EXISTS events_log_rls_policy ON events_log;

ALTER TABLE events_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE events_log FORCE ROW LEVEL SECURITY;

CREATE POLICY events_log_rls_policy ON events_log
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM users actor
      WHERE actor."User_ID" = events_log."User_ID"
        AND actor."Organization_Name" = current_setting('app.current_user_tenant', true)
    )
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR EXISTS (
      SELECT 1 FROM users actor
      WHERE actor."User_ID" = events_log."User_ID"
        AND actor."Organization_Name" = current_setting('app.current_user_tenant', true)
    )
  );

-- If you later decide audit history should be readable only by Admin/Executive
-- rather than by every user in the tenant, add this to the USING clause (and
-- deliberately NOT to WITH CHECK, since every user must still be able to write
-- their own audit entries):
--
--     AND current_setting('app.current_user_role', true) IN ('Admin', 'Executive')
