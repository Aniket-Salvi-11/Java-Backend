-- ============================================================================
-- V12__password_hash.sql   (Phase 2, step 1 of the auth upgrade)
--
-- Adds a hashed-password column ALONGSIDE the existing plaintext one rather
-- than replacing it. That is deliberate and the whole point of this migration.
--
-- WHY NOT JUST HASH "Password" IN PLACE
--
--   The live JS backend authenticates with a literal string comparison:
--
--       if (user.Password !== password) { ... }        -- app/api/auth/login/route.ts
--
--   Overwriting that column with a bcrypt digest would make every comparison
--   fail, locking real users out of the live site the moment the Java backend
--   hashed their password on login. Both backends run against one database
--   during the cutover, so the column the JS app reads must keep working
--   exactly as it does today.
--
--   This is the standard expand/contract shape: add the new column, let the
--   new code populate it lazily, leave the old column untouched, drop it in a
--   later migration once nothing reads it.
--
-- WHAT THE JAVA BACKEND DOES WITH IT
--
--   On successful login:
--     - Password_Hash present  -> verify against it, done.
--     - Password_Hash null     -> verify against the plaintext "Password"
--                                 (matching current JS behaviour), and on
--                                 success write the bcrypt hash here.
--
--   So users migrate silently as they log in. Nobody is forced to reset.
--
-- WHAT THIS MIGRATION DOES NOT DO
--
--   It does not backfill. A bcrypt hash cannot be computed in SQL without a
--   pgcrypto extension and, more importantly, backfilling would mean reading
--   every plaintext password in a single statement - exactly the exposure this
--   change exists to remove. Lazy migration on login is both safer and simpler.
--
--   It does not drop or alter "Password". That is a later migration, gated on
--   the JS backend being retired. See the checklist in db/migration/README.md.
--
-- ============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS "Password_Hash" TEXT;

COMMENT ON COLUMN users."Password_Hash" IS
  'BCrypt digest. Populated lazily on successful login (see V12). NULL means this user has not logged in through the Java backend yet and "Password" is still authoritative for them.';

COMMENT ON COLUMN users."Password" IS
  'DEPRECATED - plaintext, read by the legacy JS backend only. Superseded by "Password_Hash". Do not read from new code. Drop once the JS backend is retired.';
