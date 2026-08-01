# closemore-backend

Fresh Java/Spring Boot backend for CloseMore CRM.

**Authoritative plan: Migration Plan v3** — a like-for-like port of 57 endpoints across 12 resource
groups, reusing the existing schema and RLS untouched. The Build Plan PDF (ground-up rebuild across
7 domains) is *not* what this repo implements; it omits Tasks, Dashboard, Auth and Admin, all of
which are in scope here. Recorded so the decision isn't relitigated later.

## Status

| Phase | State |
|---|---|
| **Phase 0 — Foundation** | **Complete.** All four build-sequence items done and verified in CI. |
| **Phase 1 — Entities** | **Complete.** All 16 tables have an entity, repository and DTO. 61 integration tests green. |
| **Phase 2 — Auth** | Not started. Real authentication, password hashing, global exception handling. |
| **Phase 3 — Endpoints** | Not started. The 57-endpoint port. No services or controllers exist yet. |

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
mvn test          # unit tests only - RbacServiceTest. Does NOT run any IT.
mvn verify        # unit + all 61 integration tests (throwaway Postgres via Docker)
mvn spring-boot:run
curl -H "X-User-Id: u-1" -H "X-User-Role: Admin" -H "X-User-Tenant: Acme" \
     http://localhost:8080/internal/tenant-context-echo
```

**`mvn verify`, not `mvn test`, is the gate.** Surefire's default includes (`Test*`, `*Test`,
`*Tests`, `*TestCase`) match none of the IT classes, so `mvn test` silently skips the entire
isolation proof. Failsafe is bound to `verify` for exactly this reason.

If Docker on Windows is broken, don't fight it — push and read the GitHub Actions run. Linux CI is
the authoritative gate and takes about a minute.

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

## The test suite — 61 integration tests

**When these go red, read `RlsWiringPreconditionsIT` first.** It asserts the plumbing rather than
the behaviour, so its failures name the actual cause. "The policy is broken" and "the connection is
wrong" produce identical symptoms everywhere else.

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

The test setup and production differ in exactly two ways, both of which fail silently if missed:

1. **Production needs its own non-superuser app role.** `db/init/01-create-app-role.sql` is the
   spec for its privileges, but it is test-only and cannot run there.
2. **V11's function needs an explicit grant.** The migration deliberately grants `EXECUTE` to
   nobody so it stays portable; tests pick it up via `ALTER DEFAULT PRIVILEGES`. Without this,
   login breaks in production while every test stays green:

   ```sql
   GRANT EXECUTE ON FUNCTION auth_lookup_user_by_email(TEXT) TO <prod_app_role>;
   ```

## Notes for Phase 2

**Authentication must be JWT if mobile is on the roadmap.** Header-trust works when a trusted server
sets the headers; a mobile app runs on a user's device and anyone can send
`X-User-Tenant: SomeOtherCompany`. Tenant and role must come from inside a signed token. The RLS
mechanism itself does not change — only where the tenant value originates.

**Pre-auth lookup is already solved.** `auth_lookup_user_by_email(TEXT)` (V11) is a `SECURITY
DEFINER` function that answers one question and cannot enumerate the directory. Do not reintroduce a
blanket RLS bypass to make a repository finder work — that reopens the hole V11 closed.

## Notes for Phase 3

**RLS denials arrive as `BadSqlGrammarException` with the reason stripped.** SQLSTATE `42501` falls
in Spring's `BAD_SQL_GRAMMAR_CODES`, and that constructor is the one branch in
`SQLStateSQLExceptionTranslator` that drops `ex.getMessage()`. To return 403 rather than 500, match
the SQLSTATE — the type and message will both mislead you:

```java
catch (BadSqlGrammarException e) {
    if ("42501".equals(e.getSQLException().getSQLState())) { /* RLS denied - a boundary, not a bug */ }
}
```

**Add pagination before the pattern spreads.** No repository currently paginates. Retrofitting one
service is cheap; retrofitting twelve is not.

**Version the API.** `/api/v1/...` costs nothing now and is awkward later — mobile clients keep
calling old endpoints for months after a release.

**Consider the avatar columns.** `Avatar_Data_URL` and `Org_Avatar_Data_URL` store images as inline
base64 text. A 50-contact list could be several megabytes of JSON, most of it images — slow and
expensive on mobile. Object storage plus a URL is the usual fix, and it is cheaper to change before
clients depend on the current shape.

## Deferred, deliberately

- Real authentication (header-trust vs JWT — Finding 2, Phase 2)
- Password hashing (Finding 1, Phase 2)
- Global exception handling / `RbacException` -> HTTP mapping (Phase 2)
- Services and controllers for all 12 resource groups (Phase 3)