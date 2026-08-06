# closemore-backend

Fresh Java/Spring Boot backend for CloseMore CRM.

**Authoritative plan: Migration Plan v5** — a like-for-like port of 58 endpoints across 12 resource
groups, reusing the existing schema and RLS untouched. Superseded v3 during Phase 3; the 57-vs-58
discrepancy was a stale header in v5 itself (see `docs/HANDOFF.md`). The Build Plan PDF (ground-up
rebuild across 7 domains) is *not* what this repo implements; it omits Tasks, Dashboard, Auth and
Admin, all of which are in scope here. Recorded so the decision isn't relitigated later.

## Status

| Phase | State |
|---|---|
| **Phase 0 — Foundation** | **Complete.** All four build-sequence items done and verified in CI. |
| **Phase 1 — Entities** | **Complete.** All 16 tables have an entity, repository and DTO. |
| **Phase 2 — Auth** | **Complete.** JWT login/refresh/logout, password hashing, `RbacException` -> HTTP mapping. |
| **Phase 3 — Endpoints** | **Complete.** All 58 endpoints in v5's inventory, plus 3 single-record reads (61 request mappings total). |

**377 tests green** — 357 Failsafe integration tests, 20 Surefire unit tests.

**This repo is code-complete. It is not yet cleared for production.** The remaining work is outside
this codebase: database grants, a DBA-run role setup, a frontend/mobile release, and a handful of
behaviours inferred from the schema that need confirming against the live JS backend. All of it is
tracked in **[`docs/CUTOVER.md`](docs/CUTOVER.md)** — read that before touching QA or production.
**[`docs/HANDOFF.md`](docs/HANDOFF.md)** is the session-to-session working document: current branch
state, every hard-won gotcha from building this, and the open items still needing an answer.

Requires Docker and **JDK 21**. `pom.xml` sets `java.version` to 21; CI uses Temurin 21.

## What is actually proven

The security model is Postgres Row-Level Security, not application code. The application asks a
plain question — *"give me all contacts"* — and the database returns only the rows the caller may
see. There is no `WHERE organization = ...` in the Java to forget.

That only works if the application connects as a role RLS applies to. Postgres skips RLS entirely
for superusers and `BYPASSRLS` roles, so the Testcontainers default user made every isolation test
pass vacuously. The suite therefore runs two roles against one database:

```
Flyway  -> closemore      (container superuser)  -- owns the tables, DDL + FORCE RLS need it
Hikari  -> closemore_app  (restricted role)      -- NOSUPERUSER, NOBYPASSRLS, owns nothing
```

`closemore_app` is created by `src/test/resources/db/init/01-create-app-role.sql`, which the
Postgres image runs from `/docker-entrypoint-initdb.d` during cluster init — before the server
accepts TCP connections, and therefore before Flyway or Hikari can connect. There is no race to
lose. A `@BeforeAll` grant races the Spring context; a Flyway callback cannot create the role
Flyway's own datasource needs. Both were tried and abandoned.

Grants ride on `ALTER DEFAULT PRIVILEGES` set at init time, so every table `V1`–`V11` creates is
granted at `CREATE TABLE` time and no grant has to be sequenced against the migrations.

> **`spring.flyway.callbacks` is not a Spring Boot property.** `FlywayProperties` has no such field
> and `@ConfigurationProperties` ignores unknown keys silently. SQL callbacks are discovered by
> scanning `spring.flyway.locations`. Setting `callbacks` looks like it works and does nothing.

> **Do not add `@ServiceConnection`** to the container. It contributes a `JdbcConnectionDetails`
> bean that outranks `spring.datasource.*`, which would put the application back on the superuser
> and make every isolation assertion pass for the wrong reason.

## Running it

```bash
mvn test          # unit tests only (20 - RbacServiceTest, DealStageRulesTest). Does NOT run any IT.
mvn verify        # unit + all 357 integration tests (throwaway Postgres via Docker)
mvn spring-boot:run
curl -X POST http://localhost:8080/api/auth/login \
     -H "Content-Type: application/json" \
     -d '{"Email":"someone@example.com","Password":"..."}'
```

The `X-User-Id`/`X-User-Role`/`X-User-Tenant` header-trust shown in earlier versions of this file
was the Phase 0 shortcut and no longer works outside a non-`prod` profile — see "Phase 0 safety
guards" below. Every endpoint now authenticates via the bearer token returned from `/api/auth/login`.

**`mvn verify`, not `mvn test`, is the gate.** Surefire's default includes (`Test*`, `*Test`,
`*Tests`, `*TestCase`) match none of the IT classes, so `mvn test` silently skips the entire
isolation proof. Failsafe is bound to `verify` for exactly this reason.

If Docker on Windows is broken, don't fight it — push and read the GitHub Actions run. Linux CI is
the authoritative gate and takes about a minute.

## Endpoint coverage — 61 request mappings

| Group | Mappings |
|---|---:|
| Deals | 13 |
| Tasks | 7 |
| Users | 6 |
| Auth | 5 |
| Contacts | 5 |
| Products | 5 |
| Pipelines | 5 |
| Dashboard | 5 |
| Activities | 4 |
| Attachments | 4 |
| Admin | 1 |
| Health | 1 |

Counted from the controllers, not tallied from the plan — a running total drifted during Phase 3 and
the fix was to make this reproducible:

```bash
grep -h "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping\|@PatchMapping" \
  src/main/java/com/closemore/backend/controller/*.java | wc -l
```

61 is v5's 58 plus 3 single-record `GET /{id}` routes (Contacts, Products, Pipelines) added by a
project decision, not present in v5's inventory.

## Entity coverage — 16/16

| Group | Tables |
|---|---|
| Core | `users`, `contacts` |
| Reference (no RLS) | `products`, `pipelines` |
| Deals | `deals`, `line_items`, `deal_contacts`, `deal_team_members` |
| Activities | `activities`, `activity_attachments` |
| Tasks | `tasks`, `task_attachments`, `task_comments`, `comment_reactions`, `task_notifications` |
| Admin | `events_log` |

16 entities (plus 2 `@EmbeddedId` classes), 16 repositories, 16 DTOs, 16 mapper methods. Every
`@Column(name=...)` was diffed against `information_schema` in both directions — no unmapped
columns, no phantom mappings.

The schema has **16** tables, not the 15 both planning documents state. Worth correcting there.

### Mapping decisions worth knowing

- **FKs are scalars, not `@ManyToOne`.** A lazy association invites Hibernate to load across the
  RLS boundary outside the transaction that set the session variables — and that fails as empty
  results, not an error. Associations can come later, per read path, with a test alongside.
- **`pipelines.Stages_JSON`** is `jsonb` and needs `@JdbcTypeCode(SqlTypes.JSON)` on a `String`
  field. A plain `String` fails `ddl-auto: validate` at startup.
- **Boolean fields drop the `is` prefix** — `active`, not `isActive`. A field named `isActive`
  resolves to JavaBean property `active` anyway, so `findByIsActiveTrue()` fails at startup with
  "No property 'isActive' found". Applies to `products.Is_Active`, `deal_contacts.Is_Primary`,
  `tasks.Is_Read`, `task_notifications.Is_Read`.
- **`activity_attachments.Uploaded_At` is TEXT; `task_attachments.Uploaded_At` is TIMESTAMPTZ.**
  Same name, same concept, different types. Mapped `String` and `OffsetDateTime` respectively.
- **`events_log.Log_Entry_ID` is the only generated key** — a SERIAL, mapped
  `@GeneratedValue(IDENTITY)`. That disables JDBC insert batching, so bulk audit writes belong in
  `JdbcTemplate`, not this entity.
- **DB-managed timestamps use `@Generated`, not `insertable = false`.** Both stop Hibernate writing
  the column, but only `@Generated` makes it SELECT the value back — otherwise an entity returned
  from `save()` carries a null timestamp while the row on disk has a real one. `Updated_At` on
  `users`/`contacts`/`deals`/`activities` additionally needs `{INSERT, UPDATE}` because V5's
  `update_modified_column` trigger rewrites it on every update.

## Policy findings

The live policies do not match what the migration files suggest, because later migrations drop and
recreate earlier ones. **Read policies from `pg_policies` in a running database, not from the SQL
files.**

| Finding | Detail |
|---|---|
| **V9 supersedes V4** | `V9__deal_teams.sql` recreates the `deals`, `line_items` and `deal_contacts` policies, adding a team-membership route. V4's versions are dead code. Reading V4 alone suggests only the owner and Admin/Executive can see a deal. |
| **`events_log` had no RLS** | Any role reaching the table could read every organisation's audit history, including `Before_State`/`After_State` snapshots. Closed by `V10__events_log_rls.sql`, with `WITH CHECK` so entries can't be forged across tenants. |
| **`users` had a null-tenant hole** | V4's policy ended with `OR current_setting('app.current_user_tenant', true) IS NULL`, so any context-less query could read the entire cross-tenant user directory. No test caught it because `contacts` independently returned nothing. Closed by `V11__users_rls_tighten.sql`. |
| **Activities ignore team membership** | V9 gave team members access to deals but never touched `activities`. A team member can open a deal and see none of its activity history. Looks like an oversight in the original; pinned by a test rather than changed. |
| **Tasks are tenant-only** | `tasks_rls_policy` has no owner or role clause — any Sales_Rep sees every task in the organisation, plus its attachments, comments and reactions. Much weaker than contacts/deals. Faithful to the JS original. |
| **Notifications are per-user** | The only table whose policy matches on `app.current_user_id` with no organisation join. An Admin cannot see a colleague's notifications — right for an inbox, but it breaks the pattern everywhere else. |
| **Team membership rows are broadly visible** | `deal_team_members`' own policy checks only that the member shares the caller's organisation, so someone who cannot open a deal can still see who is on it. Matches the JS original. |

`products` and `pipelines` deliberately have no RLS — global reference data. If either gains a
tenant-owned column it needs a policy, and `RlsWiringPreconditionsIT.TENANT_TABLES` is where to add
it.

## The test suite — 357 integration tests, 20 unit tests

**When these go red, read `RlsWiringPreconditionsIT` first.** It asserts the plumbing rather than
the behaviour, so its failures name the actual cause. "The policy is broken" and "the connection is
wrong" produce identical symptoms everywhere else.

The Phase 1 isolation classes below are the foundation the rest is built on and are still the right
place to start when RLS itself is suspect. Full per-class counts for every Phase 2/3 endpoint group
are in `docs/HANDOFF.md`'s "Test classes" table — reproduced here would drift the same way the
57-vs-58 endpoint count did, so that table is the single source now.

| Class | Tests | Covers |
|---|---:|---|
| `RlsWiringPreconditionsIT` | 6 | The app connects as a non-superuser that owns nothing and can't bypass RLS; every tenant table has RLS enabled *and* forced; grants exist; the Flyway callback ran. |
| `TenantIsolationIT` | 13 | Concurrent tenants on a shared pool; PK-level isolation; class-level `@Transactional`; no-context fails closed; session variables don't leak across pooled connections; the aspect refuses to run outside a transaction; the V10/V11 regression guards. |
| `RepositoryTenantIsolationIT` | 3 | RLS survives Hibernate-generated SQL and the first-level cache, not just JdbcTemplate. |
| `ReferenceDataIT` | 5 | The jsonb mapping round-trips; the derived boolean query resolves; reference data is readable with no tenant context (by design). |
| `DealsIsolationIT` | 7 | The two-hop `line_items -> deals -> users` policy, including the case only a two-hop policy can get wrong: right organisation, wrong owner. |
| `DealTeamAccessIT` | 7 | Composite `@EmbeddedId` keys; the team-membership access route V9 added. |
| `ActivitiesIsolationIT` | 7 | The polymorphic `Parent_Object_Type` branch and all four routes into an activity; the team-member gap. |
| `TasksIsolationIT` | 6 | The three-hop reaction chain; tenant-only task visibility; per-user notifications. |
| `EventLogIT` | 5 | IDENTITY key generation; writing through Hibernate; `WITH CHECK` surfacing correctly on flush. |
| `GeneratedTimestampsIT` | 2 | `@Generated` reads DB defaults and trigger-written values back onto the returned entity. |
| *(Phase 2/3 endpoint tests)* | 296 | Auth, Contacts, Deals, Activities, Attachments, Tasks, Signup, Users, Products, Pipelines, Dashboard, Admin/Health. One class per resource group — see `docs/HANDOFF.md`. |

## Ordering caveat

`TenantContextAspect` must run *inside* the transaction boundary, not outside it — see the comments
in that class and in `CloseMoreBackendApplication`. Verified by
`aspectRefusesToRunOutsideARealTransaction`.

## Phase 0 safety guards (read before removing)

Two beans exist only to make the Phase 0 shortcut impossible to ship:

- `UserContextFilter` reads `X-User-Role` and `X-User-Tenant` straight off the client. A
  client-supplied tenant defeats the entire RLS design, so the bean is `@Profile("!prod")`.
- `SecurityConfig` permits all requests. Also `@Profile("!prod")`. If it ever reaches prod, Boot's
  default security autoconfiguration takes over and locks everything behind basic auth — a loud
  outage rather than a silently open API.

## Before deploying to production

**This section is superseded by [`docs/CUTOVER.md`](docs/CUTOVER.md).** What was two items when
Phase 1 ended (a prod app role, one `GRANT EXECUTE`) is now an ordered, multi-team checklist:
ten `GRANT EXECUTE` statements across three migrations, a duplicate-email check, a Flyway baseline,
an open `/api/v1` versioning decision that moves 53 client-facing routes, eight behaviours inferred
from the schema that need confirming against the live JS backend, and two security decisions this
repo deliberately left for a human to make. Read that document before touching QA or production —
it is kept current; this README is not the place for that checklist anymore.

## How Phase 2 and Phase 3 answered the open questions from Phase 1

Kept as a short record of what was decided, since the reasoning is easy to lose once the code is
just... how it works now.

**Authentication is JWT, not header-trust.** Login issues a signed access token (30 min) and a
rotating refresh token; every subsequent request carries `Authorization: Bearer <token>`. Tenant and
role come from inside the signed token, never from a client-supplied header — the
`X-User-Id`/`X-User-Role`/`X-User-Tenant` shortcut from Phase 0 is `@Profile("!prod")`-gated and
cannot reach production. `auth_lookup_user_by_email` (V11) remains the pre-auth lookup; V14/V15
widened it and added the session-token functions rather than replacing the mechanism.

**RLS denials are translated at the boundary.** `BadSqlGrammarException` strips the driver message
for SQLSTATE `42501`, so the global exception handler matches on the SQLSTATE itself rather than the
message, and maps it to 403 — a boundary, not a 500.

**The API is versioned — `/api/v1/...` — except where it deliberately is not.** Auth stays at
`/api/auth/*` and health at `/api/health`, because both predate the versioning decision or are
infrastructure rather than a resource group. This is now the single largest open item before
cutover: v5's inventory is entirely unversioned, so 53 routes differ from the plan document. See
`docs/CUTOVER.md` step 6b.

**Pagination exists, opt-in.** Every list endpoint returns a bare array by default and switches to
the `PageResponse` envelope (`items`, `totalElements`, `hasNext`, ...) when `?page=` or `?size=` is
present, rather than requiring pagination everywhere.

**The avatar columns were not changed.** `Avatar_Data_URL` and `Org_Avatar_Data_URL` still round-trip
as inline base64 through the standard DTOs — flagged as a future migration in `docs/HANDOFF.md`
rather than addressed in Phase 3, since resizing the field is a frontend-visible change and no
resource group's endpoints depended on fixing it to ship.

## What's next

Phases 0–3 are code-complete. What remains is entirely outside this repository:

- **`docs/CUTOVER.md`** — the DBA/frontend/JS-backend checklist. Start here.
- **`docs/HANDOFF.md`** — the living working document: current state, every gotcha discovered while
  building this, and the open items still needing an answer from someone else.

No further phase is currently planned in this repo. If one starts, the pattern established here
(a `HANDOFF.md` kept current every session, a `CUTOVER.md` kept current at the end of each phase,
this README updated when status actually changes rather than left as a snapshot) is worth keeping —
this file drifted for two full phases before this rewrite, which is the failure mode to avoid.