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
- Authoritative plan: **Migration Plan v5** — like-for-like port of 58 endpoints across 12 resource
  groups. The Build Plan PDF is not what this repo implements. v4 added cloud portability for the
  OCI deployment and database access rules for the Sprint 4 AI orchestrator; v5 corrected the API
  inventory. Read v5, not v3.

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
| Phase 3 — Endpoints | **In progress.** 17 of 58 done — Contacts (5), Deals (13, minus 1 overlap) |

**189 tests green** — 176 integration, 13 unit. Run with `mvn verify` (NOT `mvn test` — Surefire's
default includes match none of the `*IT` classes; `mvn test` silently skips the entire isolation
proof and reports success).

Branch: `phase3-endpoints`, off `phase2-auth`.

## Test classes

`RlsWiringPreconditionsIT` 6, `TenantIsolationIT` 13, `RepositoryTenantIsolationIT` 3,
`ReferenceDataIT` 5, `DealsIsolationIT` 7, `DealTeamAccessIT` 7, `ActivitiesIsolationIT` 7,
`TasksIsolationIT` 6, `EventLogIT` 5, `GeneratedTimestampsIT` 2, `LoginIT` 10, `JwtAuthIT` 14,
`AuthEndpointIT` 9, `JwtFilterIT` 8, `ContactApiIT` 30, `DealApiIT` 37.

Unit: `RbacServiceTest` 13, `DealStageRulesTest` 7. Note `DealStageRulesTest` lives in
`src/test/java/com/closemore/backend/service/` — a third test package alongside `rbac` and
`tenant` — and runs under Surefire, not Failsafe, because its name ends in `Test`.

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

13. **`Pageable.unpaged(sort)` is not a sorted query.** `SimpleJpaRepository.findAll(Pageable)`
    short-circuits an unpaged `Pageable` to `new PageImpl<>(findAll())` and discards the `Sort`.
    Every row still returns, the count is right, the response shape is right, nothing throws —
    only the `ORDER BY` vanishes and Postgres returns rows in whatever order it likes. Cost one red
    CI run. Use the `findAll(Sort)` / `findBy...(x, Sort)` overloads for unpaginated lists. The
    `findAll(Specification, Sort)` overload used by `DealService` does NOT have this short-circuit.

14. **The `events_log` RLS error in CI logs is expected.** `EventLogIT
    .aTenantCannotWriteAnAuditEntryAgainstAnotherTenantsUser` deliberately triggers
    `new row violates row-level security policy for table "events_log"` to prove V10's `WITH CHECK`
    works, and `RlsPostgres` forwards Postgres stderr into the report on purpose. Do not chase it.

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

## Phase 3 — decisions taken, and where it stands

58 endpoints across 12 resource groups (57 ported like-for-like plus `POST /api/auth/refresh`,
which has no JS equivalent and exists because Finding 2 was resolved with tokens). 17 done.

### Decisions already made — do not relitigate these without saying so

- **List response shape: bare array by default, envelope on opt-in.** `GET /api/v1/contacts`
  returns a plain JSON array exactly as the Next.js backend does. Adding `?page=` or `?size=`
  switches to `PageResponse`. This was a project decision, not a convenience: a client receiving an
  object where it expected an array gets an empty screen rather than an error, and a bad mobile
  release is gated by store review. Clients adopt pagination per screen on their own schedule.
- **API versioning: `/api/v1/...` for every resource group.** Auth stays on `/api/auth/*`
  unversioned — those paths are already in `JwtAuthenticationFilter.PUBLIC_PATH_PREFIXES` and
  already shipped in the JS frontend, so moving them is a coordinated release.
- **A sortable-column allowlist per service.** Two jobs: an unknown property makes Spring Data throw
  during query derivation (a 500 for a caller-side typo), and the avatar columns are deliberately
  excluded so nobody can make Postgres collate megabytes of base64 across a table.
- **`spring.data.web.pageable.max-page-size: 100`** caps the opt-in paginated path only. The
  unpaginated path is uncapped, matching the JS backend.
- **Single-record `GET /{id}` added** to Contacts, and to be added to Products and Pipelines. Not in
  the JS inventory, added deliberately: once a list arrives 25 rows at a time, a deep link or push
  notification pointing at one record would otherwise have to walk pages to find it.
- **Task attachments get no endpoints**, task notifications ride inside `GET /api/tasks`, tasks get
  no DELETE, and `state.ts` `loadState`/`replaceState` is dropped in favour of Flyway seeding.

### The patterns every remaining group copies

Read `ContactService` and `DealService` before writing the next one. Between them they establish:

- Class-level `@Transactional` on the service — `TenantContextAspect` only sets the session
  variables for transactional methods, and a non-transactional one returns an empty list rather than
  erroring.
- `CurrentUserService.require()` at the top of every method, converting the request context into an
  `AuthenticatedUser`.
- `saveAndFlush`, not `save`, on writes — the statement must reach the database inside the method so
  an RLS refusal surfaces there rather than at commit, where it escapes the handling.
- The audit snapshot is taken BEFORE the setters run. The entity is managed, so capturing after
  means both states record the new values and the diff is lost permanently.
- 404, never 403, for a row RLS filtered out. Distinguishing them turns the id space into an
  enumeration oracle.
- `AuditService` has no `@Transactional` of its own, deliberately — it joins the caller's
  transaction so the row and its audit entry commit together or not at all.

### Visibility is not the same shape for every table

Contacts are one hop: you own the row or you do not, so `requireOwnerOrAdmin` and a
`findByOwnerId` filter match the policy. Deals are three routes — owner, team member, or
Admin/Executive — because V9 extended the policy. Using the contacts pattern on deals would be
STRICTER than the database, and team deals would silently vanish from the list. Check the live
policy in `pg_policies` before assuming which shape applies.

### Remaining tranches

3. Activities + Attachments (8) — includes the `IngestionEventPublisher` interface Migration Plan
   v4 calls for: two implementations, AWS SQS and OCI Queue, selected by configuration.
4. Tasks (7) — comments, reactions, notifications embedded in the task response.
5. Users + Auth (7) — `registration-policy` and `signup` are still owed; login, logout and refresh
   already exist from Phase 2, which makes that group look finished when it is not.
6. Products, Pipelines (8) — plus the two single-record routes.
7. Dashboard, Admin, Health (7).

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

### Open items raised by Phase 3, needing answers from outside the Java repo

- **Five questions for the JS codebase.** Whether `task_attachments` was ever exposed by a route;
  whether notifications have a read endpoint; whether tasks were meant to be deletable; whether
  Contacts/Products/Pipelines have single-record routes the inventory missed; and whether
  `loadState`/`replaceState` is called from a route or only a seed script.
- **Deal financials are not recalculated.** Only `Deal_Value` is, from the sum of line items. The
  formulas for ARR, TCV, TLV and the three commission fields are not documented anywhere this port
  can see, and guessing would produce wrong revenue figures that look plausible. They stay
  caller-supplied via `PUT /api/v1/deals/{id}` until someone confirms the rules.
- **`GET /deals/{id}/story` cannot include tasks.** The plan describes it as "deal + its notes +
  tasks", but `tasks` carries no reference to a deal anywhere in the schema — no column, no join
  table. Implemented as audit trail + activities; the gap is documented in `DealStoryResponse`.
- **Migration Plan is at Revision 5.** It now covers cloud portability for the OCI deployment,
  database access for the Sprint 4 AI orchestrator, and the API inventory corrections above.