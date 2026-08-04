-- ============================================================================
-- V17__registration_functions.sql   (Phase 3, tranche 5a)
--
-- GET /api/auth/registration-policy and POST /api/auth/signup are unauthenticated
-- by definition: the caller has no account yet, so there is no tenant context and
-- no session variables. Every ordinary route through JPA is therefore closed to
-- them, exactly as login was in Phase 2. This file solves it the same way V11 and
-- V14 did - narrow SECURITY DEFINER functions that each do one thing - rather
-- than reopening the users policy.
--
-- Do NOT be tempted to widen users_rls_policy for signup instead. The whole
-- reason V11 tightened it was that a null-tenant clause made the user directory
-- enumerable by anyone who could reach the API.
--
-- WHERE THE RULES COME FROM
--
--   Migration Plan v5 names three signup outcomes - "bootstrap-admin,
--   pending-approval, or active Sales_Rep" - but not the conditions that select
--   between them. V6__user_approval.sql does: "privileged roles (Admin,
--   Executive/Manager) may be stored as Status = 'Pending_Approval' until an
--   active Admin approves." So the branch is on the REQUESTED ROLE, not on a
--   configuration flag - which is consistent with the schema, since none of the
--   sixteen tables holds any per-organisation settings.
--
--     - No users in the organisation yet  -> Admin, Active. The bootstrap case.
--       Without it a brand new organisation could never be created: an Admin
--       needs an existing active Admin to approve them, and there is none.
--     - Privileged role requested, active Admin exists -> Pending_Approval.
--     - Sales_Rep requested -> Active immediately.
--
--   CONFIRM WITH THE TEAM before cutover: the JS implementation is the authority
--   on whether bootstrap is scoped per organisation, as it is here, or globally
--   to the very first user in the system. Per-organisation is the only reading
--   that lets a second tenant onboard without a DBA, so it is what this does.
--
-- WHY THE BRANCH LIVES IN SQL AND NOT IN JAVA
--
--   Deciding in Java means SELECT-then-INSERT, and two concurrent first signups
--   for one organisation would both observe "no users" and both become Admin.
--   Doing it in one function closes the window, and the advisory lock below
--   closes it completely: concurrent signups for the same organisation serialise,
--   signups for different organisations do not block each other. The lock is
--   transaction-scoped, so it releases on commit or rollback without cleanup.
--
-- ENUMERATION - the exposure this file does add, stated plainly
--
--   auth_registration_policy() tells an unauthenticated caller whether a given
--   organisation name already exists. That is a real, small leak and it is
--   unavoidable if the signup form is to adapt itself, which is what v5 asks for.
--   It returns two booleans and no user data, it answers about one exact name
--   rather than a pattern, and it exposes nothing that attempting a signup would
--   not also reveal. Flagged in docs/HANDOFF.md for QA sign-off rather than
--   decided here.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Registration policy for one organisation.
-- ---------------------------------------------------------------------------
CREATE FUNCTION auth_registration_policy(p_organization_name TEXT)
RETURNS TABLE (
  "Bootstrap"        BOOLEAN,
  "Has_Active_Admin" BOOLEAN
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
SET "app.bypass_rls" = 'true'
AS $$
BEGIN
    IF p_organization_name IS NULL OR btrim(p_organization_name) = '' THEN
        RAISE EXCEPTION 'Organization name is required';
    END IF;

    RETURN QUERY
    SELECT
      NOT EXISTS (
        SELECT 1 FROM users u
         WHERE u."Organization_Name" = p_organization_name
      ),
      EXISTS (
        SELECT 1 FROM users u
         WHERE u."Organization_Name" = p_organization_name
           AND u."Role" = 'Admin'
           AND u."Status" = 'Active'
      );
END
$$;

REVOKE ALL ON FUNCTION auth_registration_policy(TEXT) FROM PUBLIC;

COMMENT ON FUNCTION auth_registration_policy(TEXT) IS
  'Reports whether an organisation is empty (bootstrap) and whether it has an active Admin who could approve a privileged registration. Booleans only, no user data. Grant EXECUTE per environment.';

-- ---------------------------------------------------------------------------
-- 2. Signup.
--
-- Returns the created row so the caller never has to read back through a policy
-- that would refuse it - the account may be Pending_Approval, and a pending user
-- has no session with which to read anything.
--
-- Deliberately does NOT accept a Status: the caller proposes a role, this
-- function decides the status. Letting an unauthenticated request name its own
-- status would be a self-approval hole.
-- ---------------------------------------------------------------------------
CREATE FUNCTION auth_signup(
  p_user_id           TEXT,
  p_first_name        TEXT,
  p_last_name         TEXT,
  p_email             TEXT,
  p_password_hash     TEXT,
  p_organization_name TEXT,
  p_requested_role    TEXT
)
RETURNS TABLE (
  "User_ID"           TEXT,
  "First_Name"        TEXT,
  "Last_Name"         TEXT,
  "Email"             TEXT,
  "Role"              TEXT,
  "Status"            TEXT,
  "Organization_Name" TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
SET "app.bypass_rls" = 'true'
AS $$
DECLARE
    v_role      TEXT;
    v_status    TEXT;
    v_bootstrap BOOLEAN;
BEGIN
    IF p_organization_name IS NULL OR btrim(p_organization_name) = '' THEN
        RAISE EXCEPTION 'Organization name is required';
    END IF;

    -- Refuse an implausible hash for the same reason auth_store_password_hash
    -- does: a bug that passed a plaintext password through would otherwise be
    -- invisible until someone tried to log in.
    IF p_password_hash IS NULL OR length(p_password_hash) < 20 THEN
        RAISE EXCEPTION 'Refusing to store an implausible password hash for %', p_email;
    END IF;

    IF p_requested_role NOT IN ('Sales_Rep', 'Executive', 'Admin') THEN
        RAISE EXCEPTION 'Unknown role %', p_requested_role;
    END IF;

    -- Serialise concurrent signups for THIS organisation only.
    PERFORM pg_advisory_xact_lock(hashtext(p_organization_name));

    SELECT NOT EXISTS (
        SELECT 1 FROM users u WHERE u."Organization_Name" = p_organization_name
    ) INTO v_bootstrap;

    IF v_bootstrap THEN
        -- First account in a new organisation. Becomes its Admin regardless of
        -- what was asked for - there is nobody to approve anything otherwise.
        v_role   := 'Admin';
        v_status := 'Active';
    ELSIF p_requested_role IN ('Admin', 'Executive') THEN
        v_role   := p_requested_role;
        v_status := 'Pending_Approval';
    ELSE
        v_role   := 'Sales_Rep';
        v_status := 'Active';
    END IF;

    RETURN QUERY
    INSERT INTO users ("User_ID","First_Name","Last_Name","Email","Role","Status",
                       "Organization_Name","Password_Hash")
    VALUES (p_user_id, p_first_name, p_last_name, p_email, v_role, v_status,
            p_organization_name, p_password_hash)
    RETURNING users."User_ID", users."First_Name", users."Last_Name", users."Email",
              users."Role", users."Status", users."Organization_Name";
END
$$;

REVOKE ALL ON FUNCTION auth_signup(TEXT,TEXT,TEXT,TEXT,TEXT,TEXT,TEXT) FROM PUBLIC;

COMMENT ON FUNCTION auth_signup(TEXT,TEXT,TEXT,TEXT,TEXT,TEXT,TEXT) IS
  'Self-service registration. Decides role and status itself - bootstrap Admin, Pending_Approval for privileged roles, Active Sales_Rep otherwise. Never accepts a caller-supplied Status. Grant EXECUTE per environment.';
