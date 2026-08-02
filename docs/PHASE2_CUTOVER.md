# Phase 2 cutover — what other teams need to do

The Java backend is being brought up **against the same database the live JS backend uses**. That
constraint drives everything below: any change to shared schema or shared policy affects the live
site immediately, whether or not the Java backend is deployed yet.

This document is the handoff. It is ordered — several steps break production if done out of
sequence.

---

## BLOCKING — do this before deploying any migration

### Does the JS backend's database user bypass RLS?

Run this **as the connection the live JS backend uses**, in QA and production:

```sql
SELECT current_user, rolsuper, rolbypassrls
FROM pg_roles WHERE rolname = current_user;
```

| Result | Meaning |
|---|---|
| `rolsuper` or `rolbypassrls` is `true` | RLS never applied to the JS app. V10/V11 are inert for it. Everything below about login breaking does not apply. |
| both `false` | RLS **is** enforced for the JS app. Step 1 below is mandatory and must ship before V11. |

Nothing else in this document can be safely sequenced until this is answered.

---

## Step 1 — JS backend: change how login reads the user row

**Owner: JS backend team. Required before V11 is applied (if RLS applies — see above).**

`app/api/auth/login/route.ts` runs with `allowUnauthenticated: true`, so no auth context exists and
no RLS session variables are set. Its query only works today because of an escape hatch in the
`users` policy:

```sql
OR current_setting('app.current_user_tenant', true) IS NULL
```

That clause means **any connection with no tenant context can read every user row in every
organisation** — the whole cross-tenant user directory. `V11__users_rls_tighten.sql` removes it.

If V11 is applied while login still relies on that clause, every login query returns zero rows and
the live site reports *"No account found with that email"* for everyone.

**The replacement already exists in the database.** V11 adds a `SECURITY DEFINER` function that
answers exactly one question and cannot enumerate the directory:

```sql
SELECT "User_ID", "Email", "Organization_Name", "Role", "Status"
FROM auth_lookup_user_by_email($1);
```

It is already case-insensitive, so `LOWER("Email") = $1` is no longer needed in the caller.

Note the function returns five columns. The current login query also selects `Password`,
`First_Name`, `Last_Name`, `Avatar_Data_URL`, `Avatar_Updated_At`, `Phone_Number`,
`Residential_Address` and `Office_Address`. Once the user's `User_ID` is known, a second query by
primary key retrieves the rest — that lookup is not blocked by the policy in the same way, because
by then the tenant is known and can be set.

If the function needs to return more columns, say so and it will be widened in a follow-up
migration rather than worked around.

### Grant required

The migration deliberately grants `EXECUTE` to nobody, so it stays portable across environments.
Each environment must grant it explicitly to whatever role its app connects as:

```sql
GRANT EXECUTE ON FUNCTION auth_lookup_user_by_email(TEXT) TO <app_role>;
```

Without this, login fails with *permission denied for function* — in QA and production, while every
CI test stays green.

---

## Step 2 — DBA: check for duplicate emails

**Owner: DBA / whoever runs migrations. Run in QA and production before deploying V13.**

```sql
SELECT lower("Email") AS email,
       count(*)       AS collisions,
       array_agg("User_ID") AS user_ids
FROM users
GROUP BY 1
HAVING count(*) > 1;
```

The `users` table has a case-**sensitive** unique index on `Email`, but every login path in both
backends matches case-**insensitively**. So `Alice@example.com` and `alice@example.com` can both
exist, and the JS query's `LIMIT 1` silently picks whichever row Postgres returns first — which is
not stable across query plans.

**If that query returns rows, real users may already be logging into the wrong account.** Treat it
as a live incident, not a migration prerequisite.

`V13__email_case_uniqueness.sql` refuses to run while duplicates exist and names them:

```
ERROR: Cannot enforce case-insensitive email uniqueness: 2 address(es) collide.
       Colliding: alice@example.com (u1, u2); bob@example.com (u3, u4)
```

It does **not** merge or delete anything automatically. Those rows may own contacts, deals and
audit history; deciding which survives is a human call, and the surviving `User_ID` needs the
other's records re-pointed at it first.

---

## Step 3 — DBA: create a restricted application role per environment

**Owner: DBA. Required before the Java backend connects anywhere.**

The Java backend's entire tenant-isolation guarantee depends on connecting as a role that RLS
actually applies to. Postgres skips RLS for superusers and for roles with `BYPASSRLS`, so
connecting as the master user makes every isolation guarantee silently vacuous.

`src/test/resources/db/init/01-create-app-role.sql` is the authoritative spec, but it is test-only
and cannot run in a managed environment. The equivalent, per environment:

```sql
CREATE ROLE <app_role> LOGIN PASSWORD '<from your secret store>'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION;

GRANT CONNECT ON DATABASE <db> TO <app_role>;
GRANT USAGE ON SCHEMA public TO <app_role>;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES    IN SCHEMA public TO <app_role>;
GRANT USAGE, SELECT                  ON ALL SEQUENCES IN SCHEMA public TO <app_role>;
GRANT EXECUTE                        ON ALL FUNCTIONS IN SCHEMA public TO <app_role>;

-- so future migrations grant automatically; run as the role that owns the tables
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO <app_role>;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO <app_role>;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO <app_role>;
```

**The role must not own any table.** An owner bypasses its own RLS policy unless the table is
declared `FORCE ROW LEVEL SECURITY`. Ours are, but not owning the tables means isolation does not
depend on that detail staying correct.

**Managed-Postgres caveat.** Neither AWS RDS nor OCI gives a true superuser, so verify the role
after creating it — the master role you run this as may itself carry `BYPASSRLS`, and migrations
run as that role, not as the app role:

```sql
SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = '<app_role>';
-- expect: false, false
```

---

## Step 4 — DBA: baseline Flyway against the existing schema

**Owner: DBA + Java backend. Required before the Java backend runs migrations anywhere real.**

QA and production already have the full schema, created by the JS project's own `001_init.sql` …
`008_deal_teams.sql`. They have **no `flyway_schema_history` table**.

If the Java backend runs Flyway against them unprepared, Flyway treats the database as empty and
attempts `V1` onward. `V1__init.sql` uses `CREATE TABLE IF NOT EXISTS` throughout, so it will not
error loudly — it will appear to succeed while later migrations re-execute against live data.

The Java migrations `V1`–`V9` are byte-equivalent to the JS project's `001`–`008`, so the existing
schema is already at `V9`. Baseline it there and let Flyway apply only `V10` onward:

```properties
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=9
```

Verify before the first real run:

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
```

Expected: a single `9` baseline row, then `V10`, `V11`, `V12`, `V13` applied.

**Do this in QA first and confirm the history table looks right before touching production.**

---

## Step 5 — Migrations to apply, in order

| Migration | Effect on the live JS site | Prerequisite |
|---|---|---|
| `V10__events_log_rls.sql` | None for reads. Adds `WITH CHECK` on `events_log`, so a write attributing an audit row to a user in another organisation now fails. The JS `logEvent` writes rows for the acting user, so this should be a no-op — worth a QA soak. | — |
| `V11__users_rls_tighten.sql` | **Breaks login** unless Step 1 has shipped. | Step 1 |
| `V12__password_hash.sql` | None. Adds a nullable column the JS app never reads. | — |
| `V13__email_case_uniqueness.sql` | None once it succeeds. Refuses to run while duplicates exist. | Step 2 |

`V12` and `V13` are safe to deploy ahead of the Java backend and are independent of Steps 1 and 4.

---

## Step 6 — Frontend: nothing yet, but be aware of what is coming

**No frontend change is required for the migrations above.** Flagged now so it is not a surprise:

The current authentication is an `X-User-ID` header, read in `lib/auth.ts` and trusted as-is. There
is nothing verifying it, so **any client can send another user's `User_ID` and become them**. That
is the reason Phase 2 exists.

The Java backend will issue a signed JWT at login, and the frontend will need to store it and send
it as `Authorization: Bearer <token>` instead of `X-User-ID`. The response body from
`POST /auth/login` will keep the existing `safeUser` shape, so nothing that consumes the user object
has to change — only the transport of the credential.

The exact contract will be circulated before that work lands. Two behaviour changes are proposed and
are open for objection:

1. **One error message for unknown email and wrong password.** Today the API distinguishes *"No
   account found with that email"* from *"Incorrect password"*, which lets anyone test whether an
   address is registered. If the UI depends on distinguishing these, say so now.
2. **`Avatar_Data_URL` dropped from the login response.** It is an inline base64 image sent on every
   login. If the UI needs it immediately after login rather than fetching it separately, say so.

The `Pending_Approval` and `Inactive` status responses — HTTP 403 with *"Your account is pending
System Administrator approval."* and *"Account is inactive"* — are being preserved verbatim.

---

## Summary of what breaks if this is done out of order

| Mistake | Symptom |
|---|---|
| V11 before Step 1 | Every login on the live site fails: *"No account found with that email"* |
| No `EXECUTE` grant | Login fails with *permission denied for function*, in that environment only |
| App role has `BYPASSRLS` | Tenant isolation silently does nothing. No error, no failing test. |
| App role owns the tables | Same, unless every table keeps `FORCE ROW LEVEL SECURITY` |
| Flyway without a baseline | `V2`–`V9` re-execute against live data |
| V13 before Step 2 | Deploy aborts with a named list of colliding addresses (safe, but stops the release) |
