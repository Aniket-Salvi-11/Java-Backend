-- ============================================================================
-- afterMigrate__grant_app_role.sql   (TEST-ONLY -- src/test/resources)
--
-- WHY THE PREVIOUS VERSION OF THIS FILE NEVER RAN
--
--   The tests set `spring.flyway.callbacks=db/callback`. There is no such
--   property. Spring Boot's FlywayProperties (@ConfigurationProperties(prefix =
--   "spring.flyway")) has locations, skipDefaultCallbacks and friends, but no
--   `callbacks` field -- and @ConfigurationProperties ignores unknown keys by
--   default, so the line was silently discarded. Flyway's own `flyway.callbacks`
--   option is a list of fully-qualified Java class names implementing
--   org.flywaydb.core.api.callback.Callback, not a directory.
--
--   SQL callbacks are discovered by scanning `locations`. Because db/callback
--   was never on the locations list, this file was never seen, the role was
--   never created, and Hikari failed with:
--       FATAL: password authentication failed for user "closemore_app"
--
--   AbstractRlsIT now sets:
--       spring.flyway.locations=classpath:db/migration,classpath:db/callback
--   which is what actually makes Flyway pick this file up.
--
-- WHAT THIS FILE IS FOR NOW
--
--   The role itself is created in db/init/01-create-app-role.sql during
--   container startup, and that script's ALTER DEFAULT PRIVILEGES already
--   grants every table the migrations create. So this callback is a safety
--   net, not the mechanism -- it re-asserts the grants over whatever exists
--   after the last migration. Keeping it means the suite still works if
--   someone later points Flyway at a different migration role, or adds a
--   migration that creates objects with an explicit owner.
--
--   Everything here is idempotent (repeated GRANTs are no-ops) and there is
--   deliberately no DROP ROLE: dropping a role that has been granted
--   privileges fails with "role cannot be dropped because some objects depend
--   on it", which is precisely the kind of second-run-only failure that is
--   miserable to debug when CI is the only place you can reproduce it.
-- ============================================================================

GRANT USAGE ON SCHEMA public TO closemore_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES    IN SCHEMA public TO closemore_app;
GRANT USAGE, SELECT                  ON ALL SEQUENCES IN SCHEMA public TO closemore_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO closemore_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO closemore_app;

GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO closemore_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO closemore_app;

-- The blanket GRANT above also hit Flyway's own bookkeeping table. The
-- application has no business reading or writing it, and revoking proves the
-- grant is scoped rather than accidental.
REVOKE ALL ON TABLE public.flyway_schema_history FROM closemore_app;