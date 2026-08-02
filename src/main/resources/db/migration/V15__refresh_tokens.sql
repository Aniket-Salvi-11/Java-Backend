-- ============================================================================
-- V15__refresh_tokens.sql   (Phase 2, step 4)
--
-- Server-side session state, so that "log out" and "revoke this user's access"
-- actually do something.
--
-- WHY THIS TABLE HAS TO EXIST
--
--   A signed JWT is verified by checking its signature - the server looks
--   nothing up. That is what makes it fast, and it is also why a JWT cannot be
--   cancelled once issued. With access tokens alone, "log out" can only mean
--   "the client promises to forget the token", and a copy taken beforehand
--   keeps working until it expires.
--
--   Storing the long-lived half of the session here makes revocation real:
--   deleting a refresh token stops any NEW access token being minted, so the
--   user is locked out everywhere within one access-token lifetime (30 minutes
--   by default; see jwt.access-token-ttl).
--
-- WHY THE TOKEN ITSELF IS NOT STORED
--
--   Only a SHA-256 hash of the token goes in Token_Hash. A leak of this table
--   then yields nothing usable - the same reasoning as V12 for passwords.
--
--   Plain SHA-256 is deliberate here, where bcrypt would be wrong. Bcrypt is
--   slow on purpose to frustrate guessing of low-entropy human passwords. A
--   refresh token is 256 bits of CSPRNG output; there is nothing to guess, and
--   a slow hash would only add latency to every refresh.
--
-- WHY RLS RATHER THAN A REVOKE
--
--   This table is touched during login and refresh, when there is no tenant
--   context - so it cannot use the normal tenant policies. But leaving it
--   unprotected would mean any code path with a database connection could read
--   every session in the system.
--
--   The policy below therefore admits ONLY callers that have set
--   app.bypass_rls, which in practice means the SECURITY DEFINER functions at
--   the bottom of this file. A direct SELECT from application code returns
--   nothing. That is also why this is not solved with REVOKE: revoking would
--   have to name the application role, and migrations stay role-agnostic so
--   they run unchanged in QA and production (see db/migration/README.md).
-- ============================================================================

CREATE TABLE IF NOT EXISTS refresh_tokens (
  "Token_ID"   TEXT PRIMARY KEY,
  "User_ID"    TEXT        NOT NULL REFERENCES users("User_ID") ON DELETE CASCADE,
  "Token_Hash" TEXT        NOT NULL UNIQUE,
  "Issued_At"  TIMESTAMPTZ NOT NULL DEFAULT now(),
  "Expires_At" TIMESTAMPTZ NOT NULL,
  "Revoked_At" TIMESTAMPTZ
);

-- Supports "revoke everything for this user" and the expiry sweep.
CREATE INDEX IF NOT EXISTS refresh_tokens_user_idx    ON refresh_tokens ("User_ID");
CREATE INDEX IF NOT EXISTS refresh_tokens_expires_idx ON refresh_tokens ("Expires_At");

ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS refresh_tokens_rls_policy ON refresh_tokens;
CREATE POLICY refresh_tokens_rls_policy ON refresh_tokens
  FOR ALL
  USING (current_setting('app.bypass_rls', true) = 'true')
  WITH CHECK (current_setting('app.bypass_rls', true) = 'true');

COMMENT ON TABLE refresh_tokens IS
  'Server-side sessions. Reachable only through the auth_*_refresh_token functions - direct queries are filtered out by RLS. Stores a SHA-256 hash, never the token.';

-- ---------------------------------------------------------------------------
-- Issue.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION auth_issue_refresh_token(
    p_token_id   TEXT,
    p_user_id    TEXT,
    p_token_hash TEXT,
    p_expires_at TIMESTAMPTZ)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
SET "app.bypass_rls" = 'true'
AS $$
BEGIN
    IF p_expires_at <= now() THEN
        RAISE EXCEPTION 'Refusing to issue an already-expired refresh token for user %', p_user_id;
    END IF;

    INSERT INTO refresh_tokens ("Token_ID", "User_ID", "Token_Hash", "Expires_At")
    VALUES (p_token_id, p_user_id, p_token_hash, p_expires_at);

    RETURN p_token_id;
END
$$;

-- ---------------------------------------------------------------------------
-- Consume, with rotation.
--
-- A refresh token is single-use: presenting it revokes it and the caller
-- receives a brand new one. That is what makes theft detectable - if a stolen
-- token is used, the legitimate client's next refresh fails, and the user is
-- forced to log in again rather than silently sharing a session with a thief.
--
-- Returns the user id on success, or NULL if the token is unknown, already
-- used, revoked, or expired. Deliberately one indistinguishable NULL for all
-- four: telling a caller WHICH of those applied is free information about
-- other people's sessions.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION auth_consume_refresh_token(p_token_hash TEXT)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
SET "app.bypass_rls" = 'true'
AS $$
DECLARE
    v_user_id TEXT;
BEGIN
    UPDATE refresh_tokens
       SET "Revoked_At" = now()
     WHERE "Token_Hash" = p_token_hash
       AND "Revoked_At" IS NULL
       AND "Expires_At" > now()
    RETURNING "User_ID" INTO v_user_id;

    RETURN v_user_id;
END
$$;

-- ---------------------------------------------------------------------------
-- Revoke one (logout on this device).
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION auth_revoke_refresh_token(p_token_hash TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
SET "app.bypass_rls" = 'true'
AS $$
DECLARE
    affected INTEGER;
BEGIN
    UPDATE refresh_tokens
       SET "Revoked_At" = now()
     WHERE "Token_Hash" = p_token_hash
       AND "Revoked_At" IS NULL;

    GET DIAGNOSTICS affected = ROW_COUNT;
    RETURN affected = 1;
END
$$;

-- ---------------------------------------------------------------------------
-- Revoke all (logout everywhere; also the lever an admin pulls when
-- deactivating an account or when a device is lost).
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION auth_revoke_all_refresh_tokens(p_user_id TEXT)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
SET "app.bypass_rls" = 'true'
AS $$
DECLARE
    affected INTEGER;
BEGIN
    UPDATE refresh_tokens
       SET "Revoked_At" = now()
     WHERE "User_ID" = p_user_id
       AND "Revoked_At" IS NULL;

    GET DIAGNOSTICS affected = ROW_COUNT;
    RETURN affected;
END
$$;

-- ---------------------------------------------------------------------------
-- Housekeeping. Rows are kept briefly after expiry so that a rotation replay
-- can still be observed; call this from a scheduled job, not from a request.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION auth_purge_expired_refresh_tokens(p_older_than INTERVAL DEFAULT '30 days')
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
SET "app.bypass_rls" = 'true'
AS $$
DECLARE
    affected INTEGER;
BEGIN
    DELETE FROM refresh_tokens WHERE "Expires_At" < now() - p_older_than;
    GET DIAGNOSTICS affected = ROW_COUNT;
    RETURN affected;
END
$$;

REVOKE ALL ON FUNCTION auth_issue_refresh_token(TEXT, TEXT, TEXT, TIMESTAMPTZ)  FROM PUBLIC;
REVOKE ALL ON FUNCTION auth_consume_refresh_token(TEXT)                          FROM PUBLIC;
REVOKE ALL ON FUNCTION auth_revoke_refresh_token(TEXT)                           FROM PUBLIC;
REVOKE ALL ON FUNCTION auth_revoke_all_refresh_tokens(TEXT)                      FROM PUBLIC;
REVOKE ALL ON FUNCTION auth_purge_expired_refresh_tokens(INTERVAL)               FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- Look up a user by id, for the refresh path.
--
-- Lives here rather than in V14 because it exists to serve refresh, and V14 had
-- already shipped by the time this was needed.
--
-- Refresh must re-read the user rather than trust what was sealed into the old
-- token, for two reasons:
--
--   - Status. A user deactivated or set back to Pending_Approval since login
--     must stop getting new access tokens. Without this check their session
--     would keep renewing itself for as long as the refresh token lives.
--   - Role and Organization_Name. These become the RLS session variables, so
--     re-reading them is what makes a role change take effect at the next
--     refresh instead of never.
--
-- Same shape as auth_lookup_user_by_email: exactly one row, by exact id, no
-- enumeration. Password material is deliberately NOT returned - refresh has no
-- business touching it.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION auth_lookup_user_by_id(p_user_id TEXT)
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
  "Updated_At"          TIMESTAMPTZ
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
SET "app.bypass_rls" = 'true'
AS $$
  SELECT u."User_ID", u."First_Name", u."Last_Name", u."Email", u."Role", u."Status",
         u."Organization_Name", u."Phone_Number", u."Residential_Address", u."Office_Address",
         u."Created_At", u."Updated_At"
  FROM users u
  WHERE u."User_ID" = p_user_id;
$$;

REVOKE ALL ON FUNCTION auth_lookup_user_by_id(TEXT) FROM PUBLIC;
