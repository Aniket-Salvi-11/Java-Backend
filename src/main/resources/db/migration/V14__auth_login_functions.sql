-- ============================================================================
-- V14__auth_login_functions.sql   (Phase 2, step 3)
--
-- Completes V11's pre-auth work. V11 added auth_lookup_user_by_email before
-- the login flow was written, and two gaps only became visible once it was:
--
--   1. The function returns User_ID, Email, Organization_Name, Role and Status.
--      Login also needs the password material to verify against, and the rest
--      of the safeUser fields for the response. Without them the caller has to
--      make a second query - which the users policy blocks, because at login
--      time there is still no tenant context.
--
--   2. Writing the migrated bcrypt hash back is blocked for the same reason,
--      and blocked SILENTLY: an UPDATE filtered by RLS reports "UPDATE 0"
--      rather than raising. A lazy password migration built on a plain UPDATE
--      would appear to work and quietly never migrate anybody.
--
-- Both are solved the same way V11 solved the original problem: narrow
-- SECURITY DEFINER functions that do exactly one thing each, rather than
-- reopening a hole in the policy.
--
-- WHY WIDENING IS SAFE
--
--   The concern with a SECURITY DEFINER function over users is enumeration -
--   that is what V4's null-tenant clause allowed, and why V11 removed it. This
--   function is not enumerable: it takes one exact email address and returns at
--   most one row. You cannot walk the directory with it, and V13's unique index
--   on lower("Email") now guarantees the "at most one" part.
--
--   Returning password material is unavoidable for a login path and is not a
--   new exposure: the same bytes are already reachable by any caller who has a
--   tenant context, and the function is granted to nobody by default.
--
-- TIMING
--
--   A return type cannot be changed with CREATE OR REPLACE, so this drops and
--   recreates. Doing it now is deliberate: docs/PHASE2_CUTOVER.md asks the JS
--   team to adopt this function, and no caller exists yet. After adoption the
--   same change would be a breaking one.
-- ============================================================================

DROP FUNCTION IF EXISTS auth_lookup_user_by_email(TEXT);

CREATE FUNCTION auth_lookup_user_by_email(p_email TEXT)
RETURNS TABLE (
  "User_ID"             TEXT,
  "First_Name"          TEXT,
  "Last_Name"           TEXT,
  "Email"               TEXT,
  "Role"                TEXT,
  "Status"              TEXT,
  "Organization_Name"   TEXT,
  "Phone_Number"        TEXT,
  "Residential_Address" TEXT,
  "Office_Address"      TEXT,
  "Created_At"          TIMESTAMPTZ,
  "Updated_At"          TIMESTAMPTZ,
  "Password"            TEXT,
  "Password_Hash"       TEXT
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
SET "app.bypass_rls" = 'true'
AS $$
  SELECT u."User_ID", u."First_Name", u."Last_Name", u."Email", u."Role", u."Status",
         u."Organization_Name", u."Phone_Number", u."Residential_Address", u."Office_Address",
         u."Created_At", u."Updated_At", u."Password", u."Password_Hash"
  FROM users u
  WHERE lower(u."Email") = lower(p_email);
$$;

REVOKE ALL ON FUNCTION auth_lookup_user_by_email(TEXT) FROM PUBLIC;

COMMENT ON FUNCTION auth_lookup_user_by_email(TEXT) IS
  'Pre-auth lookup by exact email. Returns at most one row and cannot enumerate the directory. Grant EXECUTE per environment; see docs/PHASE2_CUTOVER.md.';

-- ---------------------------------------------------------------------------
-- The lazy password migration write.
--
-- Deliberately narrow: it can only ever set "Password_Hash", only for a user
-- whose hash is still null, and it takes no other column. It cannot be
-- repurposed to change a role, an organisation, or anybody's status.
--
-- The "IS NULL" guard makes it single-shot per user. A second call for the
-- same user changes nothing and returns false, so a concurrent double login
-- cannot have one request overwrite the other's hash.
--
-- Returns whether a row was actually written, so the caller can tell "migrated
-- this user" from "already migrated" - which the plain UPDATE it replaces
-- could not do, since a policy-filtered UPDATE and a no-op UPDATE both report
-- zero rows.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION auth_store_password_hash(p_user_id TEXT, p_hash TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
SET "app.bypass_rls" = 'true'
AS $$
DECLARE
    updated INTEGER;
BEGIN
    IF p_hash IS NULL OR length(p_hash) < 20 THEN
        RAISE EXCEPTION 'Refusing to store an implausible password hash for user %', p_user_id;
    END IF;

    UPDATE users
       SET "Password_Hash" = p_hash
     WHERE "User_ID" = p_user_id
       AND "Password_Hash" IS NULL;

    GET DIAGNOSTICS updated = ROW_COUNT;
    RETURN updated = 1;
END
$$;

REVOKE ALL ON FUNCTION auth_store_password_hash(TEXT, TEXT) FROM PUBLIC;

COMMENT ON FUNCTION auth_store_password_hash(TEXT, TEXT) IS
  'One-shot lazy migration of a plaintext password to a bcrypt hash. Only writes when Password_Hash IS NULL. Grant EXECUTE per environment.';
