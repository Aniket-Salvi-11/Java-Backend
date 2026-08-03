# Project handoff — CloseMore Java backend

Paste this at the start of a new session, along with the repo link.

**Repo:** https://github.com/Aniket-Salvi-11/Java-Backend
**Branches:** `phase0-and-1` (working), `phase2-auth` (Phase 2 snapshot), `main` (stale, Phase 0 only)
**Backup remote:** `backup` → https://github.com/KevinZane07/closemore-javabackend.git

---

## Working constraints (these shaped every decision so far)

- **Docker on Windows is broken.** The only way to verify anything is GitHub Actions, ~1 minute
  per push. So: complete drop-in files, never snippets, and anticipate failure modes.
- **Every batch must state, per file: NEW or OVERWRITE, plus the full path.** Also give the git
  commands and the expected test count.
- **One new technique per batch.** Small batches have been the difference between first-push-green
  and multi-cycle debugging.
- **Propose before deviating from the plan.** Changes should read as loophole fixes, not redesigns.
- Authoritative plan: **Migration Plan v3** — like-for-like port of 57 endpoints across 12 resource
  groups. The Build Plan PDF is not what this repo implements.

## Stack

Spring Boot 3.3.4, Java 21, Postgres 16, Testcontainers 1.20.4, Flyway, Hibernate with
`ddl-auto: validate`, PascalCase quoted identifiers (`globally_quoted_identifiers: true`).
CI: `.github/workflows/verify.yml`, runs `mvn verify` on Linux.

---

## Status

| Phase | State |
|---|---|
| Phase 0 — Foundation | Complete |
| Phase 1 — Entities | Complete. 16/16 tables have entity + repository + DTO |
| Phase 2 — Auth | Complete. JWT, bcrypt, server-side sessions |
| Phase 3 — Endpoints | **Not started.** The 57-endpoint port |

**102 integration tests green.** Run with `mvn verify` (NOT `mvn test` — Surefire's default
includes match none of the `*IT` classes).

## Test classes

`RlsWiringPreconditionsIT` 6, `TenantIsolationIT` 13, `RepositoryTenantIsolationIT` 3,
`ReferenceDataIT` 5, `DealsIsolationIT` 7, `DealTeamAccessIT` 7, `ActivitiesIsolationIT` 7,
`TasksIsolationIT` 6, `EventLogIT` 5, `GeneratedTimestampsIT` 2, `LoginIT` 10, `JwtAuthIT` 14,
`AuthEndpointIT` 9, `JwtFilterIT` 8.

**When tests go red, read `RlsWiringPreconditionsIT` first** — it asserts the plumbing, so its
failures name the actual cause. "Broken policy" and "wrong connection" look identical elsewhere.

---

## The core design

Security is **Postgres Row-Level Security**, not application code. The app asks "give me all
contacts" and the database returns only permitted rows. There is no `WHERE organization = ...` in
Java to forget.

This only works because the app connects as a **non-superuser role that owns nothing**:

```
Flyway  -> closemore      (superuser)  -- owns tables, needs DDL + FORCE RLS
Hikari  -> closemore_app  (restricted) -- NOSUPERUSER, NOBYPASSRLS, owns nothing
```

`closemore_app` is created by `src/test/resources/db/init/01-create-app-role.sql`, copied into
`/docker-entrypoint-initdb.d`. The Postgres image runs it during cluster init, before TCP is open,
so `container.start()` cannot return until it has succeeded. No race.

Request flow: `JwtAuthenticationFilter` → `RequestUserContextHolder` → `TenantContextAspect`
(`@Around` on `@Transactional`) → `set_config()` session variables → RLS policies.

---

## Hard-won gotchas — do not rediscover these

1. **`spring.flyway.callbacks` is not a Spring Boot property.** It is silently ignored. SQL
   callbacks are found by scanning `spring.flyway.locations`.
2. **Never add `@ServiceConnection`** to the container. It contributes a `JdbcConnectionDetails`
   bean that outranks `spring.datasource.*`, putting the app back on the superuser — every
   isolation test then passes for the wrong reason.
3. **Read RLS policies from `pg_policies`, not from `.sql` files.** Later migrations drop and
   recreate earlier ones. V3 is dead wholesale; V4's `deals`/`line_items`/`deal_contacts` policies
   are superseded by V9; V4's `users` policy by V11. Map is in
   `src/main/resources/db/migration/README.md`.
4. **An `UPDATE` blocked by RLS reports success.** `UPDATE 0`, no error. Any write without tenant
   context must go through a `SECURITY DEFINER` function, or it silently does nothing.
5. **Boolean fields drop the `is` prefix** — `active`, not `isActive`. `isActive` resolves to
   JavaBean property `active`, so `findByIsActiveTrue()` fails at startup.
6. **DB-managed timestamps need `@Generated`, not `insertable = false`.** The latter leaves the
   returned entity's field null while the row has a value. `Updated_At` on
   users/contacts/deals/activities needs `{INSERT, UPDATE}` — V5 has a trigger.
7. **`activity_attachments.Uploaded_At` is TEXT; `task_attachments.Uploaded_At` is TIMESTAMPTZ.**
   Same name, different types.
8. **RLS denials arrive as `BadSqlGrammarException` with the message stripped.** Match on SQLSTATE
   `42501`. Handled in `ApiExceptionHandler`.
9. **`Jwt.getIssuer()` throws** for a non-URL issuer — use `getClaimAsString("iss")`.
10. **Never test JWT tampering by flipping the last base64 char.** It carries only 4 significant
    bits, so `{A,B,C,D}` decode identically — the test is flaky, not wrong. Tamper the payload.
11. **Maven Central 429s on GitHub Actions** when `pom.xml` changes (cache key is its hash). The
    workflow retries dependency resolution 4× with backoff.
12. **Cross-package visibility.** Package-private members in `auth` called from `controller` have
    broken the build twice. Check before shipping.

---

## Phase 2 — what exists

**Migrations V10–V15:**
- V10 — RLS on `events_log` (it had none; audit history was cross-tenant readable)
- V11 — closed the `users` null-tenant hole; added `auth_lookup_user_by_email`
- V12 — `Password_Hash` column **alongside** plaintext `Password`
- V13 — unique index on `lower("Email")`; aborts loudly if duplicates exist
- V14 — widened the lookup to 14 columns; added `auth_store_password_hash`
- V15 — `refresh_tokens` table + 6 session functions + `auth_lookup_user_by_id`

**Java:** `com.closemore.backend.auth` (`LoginService`, `PasswordService`, `JwtService`,
`RefreshTokenService`, `AuthService`), `controller` (`AuthController`, `ApiExceptionHandler`),
`filter/JwtAuthenticationFilter`.

**Endpoints:** `POST /api/auth/login`, `/refresh`, `/logout`.
30-min access token, 7-day refresh, rotation on every refresh, server-side revocation.

**Deliberately not done in Phase 2** (frontend-visible, deferred by choice): merging the
"No account found" / "Incorrect password" messages; dropping `Avatar_Data_URL` from the login
response.

---

## Before production — these fail silently if missed

`docs/PHASE2_CUTOVER.md` is the full handoff for the DBA/frontend/JS teams. Key items:

1. **Production and QA each need their own non-superuser app role.** The init script is the spec
   but is test-only.
2. **8 `GRANT EXECUTE` statements** for the V14/V15 functions. Tests get them via
   `ALTER DEFAULT PRIVILEGES`; real environments do not.
3. **`JWT_SECRET`** must be overridden, ≥32 bytes. The default is a placeholder.
4. **Flyway baseline.** QA/prod have the schema but no `flyway_schema_history`. Set
   `baseline-on-migrate: true` and `baseline-version: 9` **there**, not in `application.yml`.
5. **Schedule `auth_purge_expired_refresh_tokens()`.** Nothing calls it.
6. **Check for duplicate emails before V13:**
   `SELECT lower("Email"), count(*) FROM users GROUP BY 1 HAVING count(*) > 1;`
7. **V14 drops and recreates `auth_lookup_user_by_email`** with 14 columns instead of 5. If the JS
   backend adopted the 5-column version, it must update in the same release.

---

## Phase 3 — suggested starting point

Port the 57 endpoints across 12 resource groups. No services or controllers exist yet except auth.

Decisions to make early, because retrofitting is expensive:

- **Pagination.** Nothing paginates. Add `Pageable` to the first service, before the pattern is
  copied 11 times.
- **API versioning.** `/api/v1/...` costs nothing now and is awkward later — mobile clients keep
  calling old endpoints for months.
- **Avatar columns.** `Avatar_Data_URL` stores inline base64 images. A 50-contact list is several
  MB of JSON, most of it images. Object storage + URL is the usual fix.

Suggested order: Contacts (simplest policy, one `EXISTS` on `Owner_ID`), then Deals (deepest
joins), then Activities, Tasks, Admin.

**Defence in depth:** RLS enforces tenancy, but keep `RbacService` checks in the service layer too —
mirroring the JS original. If a policy is ever dropped by a bad migration, the application check
still holds, and vice versa.

---

## Known open items

- `main` is stale (Phase 0 only) and was force-pushed once. Consider branch protection.
- `deal_team_members`' policy lets anyone in the org see who is on a deal they cannot open.
  Matches the JS original; flagged, not changed.
- Activities ignore deal team membership — a team member can open a deal but see none of its
  activity history. Looks like an oversight in the original; pinned by a test.
- Tasks are tenant-only (no owner clause) — any Sales_Rep sees every task in the org.
- Plaintext `Password` column still exists for the legacy JS backend. Drop it once that is retired.
