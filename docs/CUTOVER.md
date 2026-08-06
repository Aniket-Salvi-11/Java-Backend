# Cutover — what other teams need to do

Supersedes `docs/PHASE2_CUTOVER.md`, which covered Phase 2 only and was written before any endpoint
existed. Everything from it that is still true is reproduced here; the Phase 2 doc is now a pointer
to this one.

The Java backend is being brought up **against the same database the live JS backend uses**. That
constraint drives everything below: any change to shared schema or shared policy affects the live
site immediately, whether or not the Java backend is deployed yet.

This document is ordered. Several steps break production if done out of sequence.

**Status of the code:** Phases 0–3 are complete. All 58 endpoints in Migration Plan v5's inventory
are implemented, plus three single-record reads, across 61 request mappings. 377 tests green. The
remaining risk is almost entirely in this document rather than in the repository.

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
| both `false` | RLS **is** enforced for the JS app. Step 1 is mandatory and must ship before V11. |

Nothing else in this document can be safely sequenced until this is answered.

### Second blocking question, new in Phase 3

```sql
SELECT polname, pg_get_expr(polwithcheck, polrelid)
FROM pg_policy WHERE polrelid = 'task_notifications'::regclass;
```

If the live JS backend is subject to this policy, **task notifications have been failing silently in
production** — V8's `WITH CHECK` was identical to its `USING` clause, so the only notification
anyone could insert was one addressed to themselves, which is not what a notification is for. If it
is not subject to the policy, that is the same finding as the question above and it matters for the
same reason. Either answer is worth knowing before cutover; neither is a migration prerequisite.

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

Note that V11's version returns five columns and **V14 drops and recreates it with fourteen**. If
the JS backend adopts the five-column version, it must be updated to the wider one in the same
release that applies V14.

### Grants required — now TEN functions, not eight

The auth migrations deliberately grant `EXECUTE` to nobody, so they stay portable across
environments. Each environment must grant them explicitly to whatever role its app connects as:

```sql
-- V14: login and lazy password migration
GRANT EXECUTE ON FUNCTION auth_lookup_user_by_email(TEXT)            TO <app_role>;
GRANT EXECUTE ON FUNCTION auth_store_password_hash(TEXT, TEXT)       TO <app_role>;

-- V15: sessions
GRANT EXECUTE ON FUNCTION auth_lookup_user_by_id(TEXT)                             TO <app_role>;
GRANT EXECUTE ON FUNCTION auth_issue_refresh_token(TEXT, TEXT, TEXT, TIMESTAMPTZ)  TO <app_role>;
GRANT EXECUTE ON FUNCTION auth_consume_refresh_token(TEXT)                         TO <app_role>;
GRANT EXECUTE ON FUNCTION auth_revoke_refresh_token(TEXT)                          TO <app_role>;
GRANT EXECUTE ON FUNCTION auth_revoke_all_refresh_tokens(TEXT)                     TO <app_role>;
GRANT EXECUTE ON FUNCTION auth_purge_expired_refresh_tokens(INTERVAL)              TO <app_role>;

-- V17: self-service registration (NEW in Phase 3)
GRANT EXECUTE ON FUNCTION auth_registration_policy(TEXT)                           TO <app_role>;
GRANT EXECUTE ON FUNCTION auth_signup(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT)    TO <app_role>;
```

Without these, the affected endpoint fails with *permission denied for function* — in QA and
production, while every CI test stays green. The test environment picks them up automatically via
`ALTER DEFAULT PRIVILEGES`, which is exactly why the gap does not show up before deployment.

The two new ones fail differently from the V14/V15 set, and more quietly: login still works, and
only `POST /api/auth/signup` and `GET /api/auth/registration-policy` break. If registration is not
exercised in a QA soak, the first sign will be a real user unable to create an account.

**`JWT_SECRET` must also be set per environment.** The default in `application.yml` is a placeholder
and is not secret; anyone holding it can mint a token for any user in any tenant, which bypasses RLS
entirely. It must be at least 32 bytes — the application refuses to start otherwise, so a missing
value fails loudly rather than silently weakening the signature.

**Schedule `auth_purge_expired_refresh_tokens()`.** Nothing calls it automatically. Without a
periodic job `refresh_tokens` grows without bound; rows are retained past expiry on purpose so a
rotation replay can still be observed, so a daily or weekly call is enough.

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

`V13__email_case_uniqueness.sql` refuses to run while duplicates exist and names them. It does
**not** merge or delete anything automatically. Those rows may own contacts, deals and audit
history; deciding which survives is a human call, and the surviving `User_ID` needs the other's
records re-pointed at it first.

---

## Step 3 — DBA: create a restricted application role per environment

**Owner: DBA. Required before the Java backend connects anywhere.**

The Java backend's entire tenant-isolation guarantee depends on connecting as a role that RLS
actually applies to. Postgres skips RLS for superusers and for roles with `BYPASSRLS`, so connecting
as the master user makes every isolation guarantee silently vacuous.

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
after creating it — the master role you run this as may itself carry `BYPASSRLS`, and migrations run
as that role, not as the app role:

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

Set these **in the environment**, not in `application.yml` — baselining is a one-time property of a
pre-existing database, and committing it makes every future empty database silently skip `V1`–`V9`.

Verify before the first real run:

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
```

Expected: a single `9` baseline row, then `V10` through `V17` applied.

**Do this in QA first and confirm the history table looks right before touching production.**

---

## Step 5 — Migrations to apply, in order

| Migration | Effect on the live JS site | Prerequisite |
|---|---|---|
| `V10__events_log_rls.sql` | None for reads. Adds `WITH CHECK` on `events_log`, so a write attributing an audit row to a user in another organisation now fails. The JS `logEvent` writes rows for the acting user, so this should be a no-op — worth a QA soak. | — |
| `V11__users_rls_tighten.sql` | **Breaks login** unless Step 1 has shipped. | Step 1 |
| `V12__password_hash.sql` | None. Adds a nullable column the JS app never reads. | — |
| `V13__email_case_uniqueness.sql` | None once it succeeds. Refuses to run while duplicates exist. | Step 2 |
| `V14__auth_login_functions.sql` | None. Widens the V11 lookup function and adds the lazy password-migration write. **DROPs and recreates `auth_lookup_user_by_email` with a 14-column return type** — if the JS backend has adopted the 5-column version, it must be updated in the same release. | — |
| `V15__refresh_tokens.sql` | None. New table plus session functions; nothing existing reads them. | — |
| `V16__task_notifications_write_check.sql` | Widens the `task_notifications` `WITH CHECK` from "addressed to me" to "addressed to anyone in my organisation". Reads are unchanged and stay strictly per-user. If the JS backend is subject to this policy, this **fixes** notification writes rather than breaking them — see the second blocking question. | — |
| `V17__registration_functions.sql` | None. Two new `SECURITY DEFINER` functions; nothing existing calls them. Needs the two new grants above. | — |

`V12` and `V13` are safe to deploy ahead of the Java backend and are independent of Steps 1 and 4.

### What V16 changes, stated plainly

Any user may now write a notification addressed to any colleague in their own organisation, with
arbitrary text. That is exactly what the application does on their behalf and it cannot cross a
tenant boundary. It does mean a notification is not proof of who triggered it — `events_log` is, and
its own `WITH CHECK` ties each row to the acting user.

---

## Step 6 — Frontend and mobile: the changes Phase 3 actually requires

Phase 2 required no frontend change. **Phase 3 does**, and one item is large.

### 6a. Authentication moves from `X-User-ID` to a bearer token

The current authentication is an `X-User-ID` header, read in `lib/auth.ts` and trusted as-is. There
is nothing verifying it, so **any client can send another user's `User_ID` and become them**. That is
the reason Phase 2 exists.

The Java backend issues a signed JWT at login. Send it as `Authorization: Bearer <token>` instead of
`X-User-ID`. Error bodies keep the existing `{"error": "..."}` envelope, and the `Pending_Approval` /
`Inactive` 403 messages are preserved verbatim, so existing error handling works unchanged.

```
POST /api/auth/login     {"Email": "...", "Password": "..."}
POST /api/auth/refresh   {"refreshToken": "..."}
POST /api/auth/logout    {"refreshToken": "..."}   -> 204
```

Login and refresh both return:

```json
{
  "accessToken": "...",
  "accessTokenExpiresAt": "2026-08-02T18:30:00Z",
  "refreshToken": "...",
  "refreshTokenExpiresAt": "2026-08-09T18:00:00Z",
  "user": { "...the existing safeUser object, unchanged..." }
}
```

Three things to handle:

1. **The user object is nested under `user`.** Previously the response body WAS safeUser. The object
   itself is unchanged — it is one level deeper.
2. **Refresh tokens rotate.** Every call to `/refresh` returns a NEW refresh token and invalidates
   the one presented. Replace the stored copy each time; reusing the old value fails by design,
   because that is what makes a stolen token detectable.
3. **Refresh proactively.** The access token lives 30 minutes. Use `accessTokenExpiresAt` to renew
   shortly before it lapses rather than waiting for a 401.

### 6b. DECISION NEEDED — every resource path gains a `/v1` segment

**This is the largest client-facing item in the migration and it is not settled.**

Migration Plan v5's inventory lists every route unversioned: `/api/contacts`, `/api/deals`,
`/api/tasks`, `/api/users`. The Java backend serves them at `/api/v1/contacts`, `/api/v1/deals`, and
so on. Auth and health are the exceptions and stay unversioned — `/api/auth/*` because those paths
already shipped, `/api/health` because a probe URL is infrastructure configured once.

That means **53 of the 58 routes move**, and every client call to an old path returns 404 at cutover.

The versioning decision was taken inside Phase 3 and is recorded in `docs/HANDOFF.md`. Its stated
reason for leaving auth alone was to avoid a coordinated frontend release — which the other 53
routes then require anyway. Three options:

1. **Keep `/api/v1`.** Frontend and mobile change every request path in the same release that
   switches to bearer tokens. Since 6a already requires touching request logic, the marginal cost is
   small — but it must be the *same* release.
2. **Drop the prefix**, matching v5 exactly. Five `@RequestMapping` lines and their tests; roughly a
   one-tranche change in this repo, zero client change.
3. **Serve both temporarily.** Avoids a hard cutover, doubles the surface to secure, and temporary
   compatibility routes do not get removed.

Someone outside this repo has to choose. Option 1 is cheapest **only** if the client release is
already happening for 6a.

### 6c. Registration is new

```
GET  /api/auth/registration-policy?organizationName=Acme
POST /api/auth/signup
```

Both are unauthenticated. `registration-policy` returns `{bootstrap, approvalPending,
requiresApprover}` so the signup form can adapt — whether this account will own a new organisation,
and whether privileged roles should be offered at all.

**Signup returns the created account and never a session.** An account may land in
`Pending_Approval`, and issuing tokens for an account that cannot sign in would contradict the
Status check login already enforces. If the JS signup screen expects to be logged in on success, it
needs one extra call to `/api/auth/login`. **Confirm this is acceptable.**

**Known exposure:** `registration-policy` tells an anonymous caller whether an organisation name
already exists. That is unavoidable if the form adapts itself, it returns two booleans and no user
data, and it reveals nothing that attempting a signup would not. **Needs QA sign-off.**

---

## Step 7 — Behaviours derived rather than specified. Confirm against the JS code.

v5's inventory describes these endpoints in one line each and does not state the rules. The rules
below were derived from the schema and its comments, and each is pinned by a test — so a wrong guess
surfaces as a named failing test, not as a silent behaviour change. **The JS implementation is the
authority. Please check each one.**

| Area | What was derived | Where it came from |
|---|---|---|
| **Signup role/status** | Privileged roles (`Admin`, `Executive`) land in `Pending_Approval`; `Sales_Rep` is `Active` immediately. | `V6__user_approval.sql`'s comment on `users."Status"` |
| **Signup bootstrap** | The first account in an empty organisation becomes its `Admin`, `Active`, whatever role it asked for. Scoped **per organisation**, not globally to the first user in the system. | Inferred — without it a new tenant can never onboard, since an Admin needs an existing Admin to approve them |
| **Pipeline stage rename** | Detected by **position**, and only when the stage list length is unchanged. Stage JSON carries no stable id, so reordering is indistinguishable from renaming. | Inferred |
| **Pipeline stage removal** | Deals on a removed stage move to the **first** stage — the only destination guaranteed to exist. | Inferred |
| **Closed deals in a cascade** | A deal on `Closed Won`/`Closed Lost` is **never moved**, even when its stage is removed, leaving a dangling stage reference. Sweeping it would reopen closed business and change its Status. | Judgement call |
| **Leaderboard timeframe** | `month`/`quarter` filter on `Updated_At`, because there is no `Closed_Date` column. A deal won in March and edited in May counts as May. | Least-wrong column available |
| **Forecast total** | Returns **both** a raw and a probability-weighted total, because v5 says "forecast revenue total" without saying which and the two differ substantially. Say which the existing dashboard renders and the other can be dropped. | Ambiguous in v5 |
| **Admin-created users have no password** | `POST /api/v1/users` sets no credential; v5 inventories no route that sets another user's password. If the JS backend has an invite or reset flow, it is missing from the inventory and needs adding as scope. | Absent from v5 |

---

## Step 8 — Security decisions this repo deliberately did not take

Two places where matching the JS backend's behaviour means crossing a boundary the RLS policies
exist to enforce. Both currently do the conservative thing and are flagged rather than fixed.

**1. `GET /api/v1/dashboard/leaderboard` refuses Sales_Reps (403).**
v5 calls it a ranking of sales reps, implying reps see it. `V4__tenant_rls.sql` scopes a Sales_Rep to
`Owner_ID = current_user_id`, so an organisation-wide ranking is unreachable for them — the query
would return one row, their own, indistinguishable from genuinely leading. Refusing is the loud
failure. Matching JS needs a `SECURITY DEFINER` aggregate reading across owners, which publishes
every rep's won revenue to every other rep. **Product and security call.**

**2. The pipeline stage cascade stops at the caller's tenant.**
`pipelines` has no RLS; `deals` does. So an Admin editing a stage list changes it for every
organisation, while the deal reassignment only touches their own organisation's deals. Other tenants
keep a stage name that no longer exists in the pipeline. The JS backend, having no RLS, updates
everyone's. Fixing it needs a `SECURITY DEFINER` writer that modifies other tenants' rows.
**Security call.**

**Related, and worth knowing even though no decision is needed:** `products` and `pipelines` have no
RLS policy at all. They are global reference data, and for those two groups the application's Admin
check is the *only* thing guarding them — not defence in depth behind a policy. An Admin in one
tenant edits a catalogue and a stage configuration every tenant sees.

---

## Summary of what breaks if this is done out of order

| Mistake | Symptom |
|---|---|
| V11 before Step 1 | Every login on the live site fails: *"No account found with that email"* |
| Missing V14/V15 `EXECUTE` grant | Login fails with *permission denied for function*, in that environment only |
| Missing V17 `EXECUTE` grant | Login works; **signup and registration-policy alone fail**. Easy to miss in a soak that does not create an account. |
| App role has `BYPASSRLS` | Tenant isolation silently does nothing. No error, no failing test. |
| App role owns the tables | Same, unless every table keeps `FORCE ROW LEVEL SECURITY` |
| Flyway without a baseline | `V2`–`V9` re-execute against live data |
| Flyway baseline committed to `application.yml` | Every future empty database skips `V1`–`V9` |
| Bearer-token release without the `/v1` path change | 404 on every resource route — the two are one release, not two |
| V14 applied while JS uses the 5-column lookup | Login fails on a column count mismatch |
