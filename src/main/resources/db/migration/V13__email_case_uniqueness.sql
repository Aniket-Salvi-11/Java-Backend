-- ============================================================================
-- V13__email_case_uniqueness.sql   (Phase 2, step 2)
--
-- THE BUG
--
--   users has UNIQUE ("Email") - a case-SENSITIVE index. So these two rows can
--   both exist today:
--
--       Alice@example.com
--       alice@example.com
--
--   Every login path, in both backends, matches case-INSENSITIVELY:
--
--       JS:   WHERE LOWER("Email") = $1 LIMIT 1
--       Java: WHERE lower(u."Email") = lower(p_email)     -- V11's function
--
--   The JS query hides the collision behind LIMIT 1 and logs the user into
--   whichever row Postgres happens to return first - which is not guaranteed
--   stable across plans or vacuums. The Java function has no LIMIT and would
--   return both rows.
--
--   Verified reproducible: inserting both spellings succeeds, and a
--   lower()-based lookup then matches 2 rows.
--
-- THE FIX, AND WHY IT MAY FAIL ON PURPOSE
--
--   A unique index on lower("Email") makes the constraint agree with the
--   lookups. On a live database that index CANNOT be created if duplicates
--   already exist - and this migration deliberately does not resolve them
--   automatically. Merging or deleting user accounts is not a decision a
--   migration script should make silently: those rows may own contacts, deals
--   and audit history.
--
--   Instead the DO block below fails with an explicit list of the offending
--   addresses, so the deploy stops with an actionable message rather than an
--   opaque "could not create unique index" error.
--
--   RUN THIS BEFORE DEPLOYING, against QA and production:
--
--       SELECT lower("Email") AS email, count(*), array_agg("User_ID")
--       FROM users GROUP BY 1 HAVING count(*) > 1;
--
--   If it returns rows, decide per address which User_ID survives and
--   re-point its contacts/deals/activities before deploying this.
--
-- WHY NOT ALSO DROP THE OLD INDEX
--
--   users_Email_key stays. It is strictly weaker than the new index but
--   harmless, and dropping a constraint on a live table during a cutover buys
--   nothing. Retire it later with the rest of the cleanup.
-- ============================================================================

DO $$
DECLARE
    duplicates TEXT;
BEGIN
    SELECT string_agg(email || ' (' || ids || ')', '; ')
      INTO duplicates
      FROM (
        SELECT lower("Email") AS email,
               string_agg("User_ID", ', ' ORDER BY "User_ID") AS ids
        FROM users
        GROUP BY lower("Email")
        HAVING count(*) > 1
      ) d;

    IF duplicates IS NOT NULL THEN
        RAISE EXCEPTION
          'Cannot enforce case-insensitive email uniqueness: % address(es) collide. Resolve these manually before deploying V13 - see the header of this migration. Colliding: %',
          (SELECT count(*) FROM (SELECT 1 FROM users GROUP BY lower("Email") HAVING count(*) > 1) x),
          duplicates;
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower_key ON users (lower("Email"));

COMMENT ON INDEX users_email_lower_key IS
  'Makes uniqueness agree with how every login path actually looks users up (case-insensitively). See V13.';
