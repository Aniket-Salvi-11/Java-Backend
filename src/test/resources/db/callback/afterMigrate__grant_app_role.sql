-- TEST-ONLY Flyway callback. Lives in src/test/resources/db/callback/ so it runs ONLY in tests,
-- never in production migrations. Flyway runs every "afterMigrate" callback after all versioned
-- migrations complete, as the SUPERUSER (Flyway's connection) - so the tables definitely exist
-- and we definitely have rights to grant.
--
-- This creates the restricted, non-superuser application role and grants it exactly the
-- privileges the app needs. Because it runs as part of the Flyway lifecycle (not in a racy
-- @BeforeAll that can fire before the schema is built), the grants are guaranteed to land after
-- every table exists. This is what fixes "permission denied for table contacts".
--
-- The role is NOSUPERUSER + NOBYPASSRLS and owns nothing, so RLS (ENABLE + FORCE) genuinely
-- applies to it - which is the whole point of the isolation tests.

DROP ROLE IF EXISTS closemore_app;
CREATE ROLE closemore_app LOGIN PASSWORD 'closemore_app_pw' NOSUPERUSER NOBYPASSRLS;

GRANT USAGE ON SCHEMA public TO closemore_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO closemore_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO closemore_app;

-- Cover any tables/sequences created by later callbacks or future migrations too.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO closemore_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO closemore_app;
