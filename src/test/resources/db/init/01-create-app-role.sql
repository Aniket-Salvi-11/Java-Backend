-- ============================================================================
-- 01-create-app-role.sql
--
-- Copied into /docker-entrypoint-initdb.d/ inside the Postgres container by
-- RlsPostgres. The official postgres image runs every *.sql file in that
-- directory with `psql -v ON_ERROR_STOP=1` as POSTGRES_USER, against
-- POSTGRES_DB, on a temporary server that is NOT yet accepting TCP
-- connections. Only after every init script succeeds does the entrypoint
-- restart Postgres for real and log "database system is ready to accept
-- connections" for the second time -- which is exactly the event
-- PostgreSQLContainer's default wait strategy blocks on.
--
-- That is the whole point of putting the role creation here instead of in
-- @BeforeAll or a Flyway callback: by the time container.start() returns,
-- this file has already run to completion. There is no window in which
-- Flyway, Hikari, or a test can observe a database without closemore_app in
-- it, so there is no race to lose and no chicken-and-egg (Flyway cannot be
-- responsible for creating the role that Flyway's own datasource needs).
--
-- If this script fails, the entrypoint aborts and the container dies during
-- start(), so the failure surfaces as a container startup error with the psql
-- error in the logs -- not as a mysterious auth failure 30 seconds later.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- The restricted role the APPLICATION connects as.
--
-- NOSUPERUSER and NOBYPASSRLS are the two attributes that make the isolation
-- tests meaningful. Postgres skips row-level security entirely for superusers
-- and for roles with BYPASSRLS, which is why the Testcontainers default user
-- sees every tenant's rows: ENABLE/FORCE ROW LEVEL SECURITY is applied, it
-- just does not apply to that connection.
--
-- This role also owns nothing. Table ownership matters too -- an owner
-- bypasses RLS unless the table is declared FORCE ROW LEVEL SECURITY. The
-- migrations do set FORCE, but not owning the tables means the tests do not
-- depend on that detail being right.
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'closemore_app') THEN
        CREATE ROLE closemore_app
            LOGIN
            PASSWORD 'closemore_app_pw'
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOBYPASSRLS
            NOREPLICATION;
    ELSE
        -- Only reachable if Testcontainers container reuse is switched on and
        -- the volume survives. Keep the attributes authoritative either way.
        ALTER ROLE closemore_app
            LOGIN
            PASSWORD 'closemore_app_pw'
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOBYPASSRLS
            NOREPLICATION;
    END IF;
END
$$;

-- CONNECT is granted to PUBLIC by default, so this is belt-and-braces for the
-- case where someone later revokes it. current_database() keeps the script
-- independent of withDatabaseName(...).
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO closemore_app', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO closemore_app;

-- ---------------------------------------------------------------------------
-- The part that removes the need for any grant to be correctly sequenced
-- against the migrations.
--
-- ALTER DEFAULT PRIVILEGES is forward-looking: it says "whenever the current
-- role creates a table in public from now on, grant these privileges to
-- closemore_app automatically". Flyway migrates as this same role, so every
-- table V1..V9 create -- and every table a future V10 creates -- is granted at
-- CREATE TABLE time, with no afterMigrate step required.
--
-- Note the deliberate omission of TRUNCATE and REFERENCES, and of any grant on
-- the schema's CREATE privilege: the app role can read and write rows and
-- nothing else.
-- ---------------------------------------------------------------------------
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO closemore_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO closemore_app;

-- Covers V11's auth_lookup_user_by_email. That migration deliberately grants
-- EXECUTE to nobody (it only revokes the implicit PUBLIC grant) so it stays
-- portable to production, where the app role has a different name. Granting it
-- here keeps the role name confined to test resources. A default privilege and
-- a PUBLIC grant are independent grants, so V11's REVOKE ... FROM PUBLIC does
-- not disturb this one -- verified.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO closemore_app;