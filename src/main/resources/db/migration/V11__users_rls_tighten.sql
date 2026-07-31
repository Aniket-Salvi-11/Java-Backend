-- ============================================================================
-- V11__users_rls_tighten.sql
--
-- THE HOLE
--
--   V4's users_rls_policy ended with:
--
--       OR current_setting('app.current_user_tenant', true) IS NULL
--       OR current_setting('app.current_user_tenant', true) = ''
--
--   Read that as: "any connection with no tenant context may read the entire
--   user directory, across every tenant." Names, emails, roles, org names.
--   The isolation tests did not catch it because contacts_rls_policy
--   independently requires a matching organisation, so contacts still returned
--   zero rows -- the users table was the only thing exposed, and nothing
--   asserted on it.
--
-- WHY IT WAS THERE
--
--   UserRepository's own comment explains it: "login runs before a tenant
--   context exists, so it will need the bypass path or a dedicated pre-auth
--   mechanism - flagged for Phase 2." The escape hatch was scaffolding for
--   authentication. It works, but it pays for one narrow lookup by opening the
--   whole table to every context-less query in the application.
--
-- THE REPLACEMENT
--
--   Close the policy, and give authentication the dedicated door the comment
--   asked for: a SECURITY DEFINER function that answers exactly one question
--   ("which user has this email?") and nothing else. One row, five columns, no
--   ability to enumerate. Everything else about users is now tenant-scoped
--   with no exceptions.
--
--   The function is deliberately role-agnostic -- it grants EXECUTE to nobody,
--   only revoking the implicit PUBLIC grant. Test environments pick it up via
--   ALTER DEFAULT PRIVILEGES in db/init/01-create-app-role.sql. In production,
--   grant it explicitly to whatever role your app connects as:
--
--       GRANT EXECUTE ON FUNCTION auth_lookup_user_by_email(TEXT) TO <app_role>;
-- ============================================================================

DROP POLICY IF EXISTS users_rls_policy ON users;

CREATE POLICY users_rls_policy ON users
  FOR ALL
  USING (
    current_setting('app.bypass_rls', true) = 'true'
    OR "Organization_Name" = current_setting('app.current_user_tenant', true)
  )
  WITH CHECK (
    current_setting('app.bypass_rls', true) = 'true'
    OR "Organization_Name" = current_setting('app.current_user_tenant', true)
  );

-- ---------------------------------------------------------------------------
-- The pre-auth lookup.
--
-- SECURITY DEFINER alone is not enough: the users table is FORCE ROW LEVEL
-- SECURITY, so even the table owner is subject to the policy above and the
-- function would return nothing. The `SET "app.bypass_rls" = 'true'` clause is
-- what makes it work -- and because it is a function-local SET, Postgres
-- restores the previous value when the function returns. Verified: the GUC
-- reads back empty afterwards and users is still invisible to the caller.
--
-- `SET search_path = public, pg_temp` is mandatory hygiene for SECURITY
-- DEFINER. Without it a caller can prepend a schema to search_path and
-- substitute their own `users` table, and the function will happily read it
-- with the owner's privileges.
--
-- lower(...) on both sides mirrors the original login query's
-- LOWER("Email") = $1. If you add an index for it, it must be the matching
-- expression index: CREATE INDEX ... ON users (lower("Email")).
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION auth_lookup_user_by_email(p_email TEXT)
RETURNS TABLE (
  "User_ID"           TEXT,
  "Email"             TEXT,
  "Organization_Name" TEXT,
  "Role"              TEXT,
  "Status"            TEXT
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
SET "app.bypass_rls" = 'true'
AS $$
  SELECT u."User_ID", u."Email", u."Organization_Name", u."Role", u."Status"
  FROM users u
  WHERE lower(u."Email") = lower(p_email);
$$;

-- Postgres grants EXECUTE on new functions to PUBLIC by default. For a
-- SECURITY DEFINER function that reads across tenants, that default is wrong.
REVOKE ALL ON FUNCTION auth_lookup_user_by_email(TEXT) FROM PUBLIC;
