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
| **Phase 1 — Entities** | **De-risked, 2 of 16 tables built.** The hard question (does RLS survive Hibernate?) is answered and guarded by `RepositoryTenantIsolationIT`. The remaining 14 entities are volume, not risk. |

### Phase 0 scope (build sequence, steps 1–4)

| # | Item | Status |
|---|---|---|
| 1 | Finalize the RLS/session-variable replication strategy | **Done** — Spring AOP `@Around` advice on `@Transactional` methods, see `aop/TenantContextAspect.java`. Proven against a non-superuser role in CI; see "How isolation is actually proven" below. |
| 2 | Set up the full 16-table schema locally via Testcontainers | **Done** — the original `001_init.sql`…`008_deal_teams.sql` copied verbatim into `db/migration/` as `V1__init.sql`…`V9__deal_teams.sql`, plus `V10`/`V11` closing two RLS gaps found during Phase 1. |
| 3 | Extract the RBAC rules verbatim | **Done** — `rbac/RbacService.java`, unit tested in `RbacServiceTest.java`. |
| 4 | Scaffold the Spring Boot project (Web, Security, Data JPA, Validation, PostgreSQL Driver, Lombok) | **Done** — see `pom.xml`. AOP and Flyway starters added on top, since the RLS mechanism and schema setup both need them. |

Note: the schema has **16** tables, not the 15 both planning documents state — `users, products,
pipelines, contacts, deals, line_items, deal_contacts, activities, activity_attachments,
events_log, tasks, task_attachments, task_comments, comment_reactions, task_notifications,
deal_team_members`. Worth correcting in the docs so Phase 1 isn't sized off a wrong number.

### Phase 1 entity coverage

Built: `UserEntity`, `ContactEntity`. Outstanding: `deals`, `pipelines`, `products`, `line_items`,
`deal_contacts`, `activities`, `activity_attachments`, `tasks`, `task_attachments`, `task_comments`,
`comment_reactions`, `task_notifications`, `deal_team_members`, `events_log`.

Suggested order — Deals group first (`deals`, `pipelines`, `products`, `line_items`,
`deal_contacts`), because those policies have the deepest joins and so surface a schema mismatch
soonest. `ddl-auto: validate` plus `globally_quoted_identifiers` makes each entity self-checking: a
wrong column name fails at context startup, not at runtime.

**Add a repository-level isolation test for these four**, whose policy shape differs materially from
`contacts` (a single `EXISTS` on a direct `Owner_ID`):

| Table | Policy shape |
|---|---|
| `line_items`, `deal_contacts` | `EXISTS (deals JOIN users)` — two hops |
| `activities` | logger + polymorphic parent, branches on `Parent_Object_Type` |
| `activity_attachments` | `activities JOIN users`, nested three levels |

These traverse relationships Hibernate may lazy-load *outside* the transaction that set the session
variables — a different failure mode from anything currently tested, and one that shows up as empty
results rather than an error.

## How isolation is actually proven

**The application connects as a non-superuser.** This is the part that makes the tests mean
anything. Postgres skips RLS entirely for superusers and for `BYPASSRLS` roles, so querying as the
Testcontainers default user makes every tenant see every row — `ENABLE`/`FORCE ROW LEVEL SECURITY`
is applied, it just doesn't apply to that connection.

So the test suite runs two roles against one database:

```
Flyway  -> closemore      (container superuser)  -- owns the tables, DDL + FORCE RLS need it
Hikari  -> closemore_app  (restricted role)      -- NOSUPERUSER, NOBYPASSRLS, owns nothing
```

`closemore_app` is created by `src/test/resources/db/init/01-create-app-role.sql`, which the
Postgres image runs from `/docker-entrypoint-initdb.d` during cluster init — before the server
accepts TCP connections, and therefore before Flyway or Hikari can possibly connect. There is no
race to lose. A `@BeforeAll` grant races the Spring context; a Flyway callback can't create the role
Flyway's own datasource needs. Both were tried and abandoned.

Grants ride on `ALTER DEFAULT PRIVILEGES`, set at init time, so every table `V1`–`V11` create is
granted at `CREATE TABLE` time and no grant has to be sequenced against the migrations.

> `spring.flyway.callbacks` is **not a Spring Boot property** — `FlywayProperties` has no such
> field, and `@ConfigurationProperties` ignores unknown keys silently. SQL callbacks are discovered
> by scanning `spring.flyway.locations`. Setting `callbacks` looks like it works and does nothing.

**Do not add `@ServiceConnection`** to the container. It contributes a `JdbcConnectionDetails` bean
that outranks `spring.datasource.*`, which would put the app back on the superuser and make every
isolation assertion pass for the wrong reason.

## Running the tests

Requires Docker and **JDK 21** (`pom.xml` sets `java.version` to 21; CI uses Temurin 21).

```bash
mvn test          # unit tests only - RbacServiceTest. Does NOT run any IT.
mvn verify        # unit + all 22 integration tests (throwaway Postgres via Docker)
mvn spring-boot:run
curl -H "X-User-Id: u-1" -H "X-User-Role: Admin" -H "X-User-Tenant: Acme" \
     http://localhost:8080/internal/tenant-context-echo
```

**`mvn verify`, not `mvn test`, is the gate.** Surefire's default includes (`Test*`, `*Test`,
`*Tests`, `*TestCase`) match none of the IT classes, so `mvn test` silently skips the entire
isolation proof. Failsafe is bound to `verify` in `pom.xml` for exactly this reason. CI must run
`mvn verify`.

If Docker on Windows is broken, don't fight it — push and read the GitHub Actions run. Linux CI is
the authoritative gate and takes about a minute.

## What the integration tests assert (22 total)

**When these go red, read `RlsWiringPreconditionsIT` first.** It asserts the plumbing rather than
the behaviour, so its failures name the actual cause. "The policy is broken" and "the connection is
wrong" produce identical symptoms in the other two classes.

### `RlsWiringPreconditionsIT` (6)

| Test | Guards against |
|---|---|
| `applicationConnectsAsTheRestrictedRoleNotTheContainerSuperuser` | The whole proof silently becoming vacuous. |
| `theApplicationRoleCannotBypassRowLevelSecurity` | `SUPERUSER`/`BYPASSRLS` creeping onto the app role. |
| `theApplicationRoleDoesNotOwnTheTablesItQueries` | Owner-bypass, so isolation doesn't hinge on `FORCE` being set correctly. |
| `everyTenantScopedTableHasRlsEnabledAndForced` | A new tenant table shipping with no policy behind it. |
| `theApplicationRoleCanReadTheTablesItNeeds` | A missing `GRANT` ("permission denied") masquerading as RLS filtering. |
| `theApplicationRoleCannotTouchFlywaysBookkeeping` | The `afterMigrate` callback not running at all. |

### `TenantIsolationIT` (13)

| Test | Guards against |
|---|---|
| `concurrentTenantsOnASharedPoolNeverSeeEachOthersRows` | Cross-tenant bleed via a reused pooled connection. Pool is pinned to `maximum-pool-size=2` so two tenant threads contend for the same connections; at the default of 10 they'd get separate ones and the test would pass without exercising reuse. |
| `aTenantCannotReachAnotherTenantsRowEvenByPrimaryKey` | A count assertion passing for the wrong reason (right number of rows, wrong rows). |
| `classLevelTransactionalServicesAlsoGetTheSessionVariables` | The pointcut narrowing back to `@annotation` only, missing `@Service @Transactional public class DealService`. |
| `aRequestWithNoUserContextSeesNothing` | An accidentally permissive policy. Documents the safe failure mode as an assertion, not a comment. |
| `sessionVariablesDoNotSurviveOntoTheNextRequestOnTheSameConnection` | `set_config`'s transaction-local flag being dropped, or the connection released before COMMIT. |
| `aspectRefusesToRunOutsideARealTransaction` | The advice ordering contract inverting. |
| `theUserDirectoryIsTenantScoped` | Regression on V11. |
| `aRequestWithNoUserContextCannotEnumerateTheUserDirectory` | Reopening V4's null-tenant hole. |
| `theAuditTrailIsTenantScoped` | Regression on V10. Asserts the visible *set of actors*, not a row count, because all ITs share one database and one test writes an audit row. |
| `aRequestWithNoUserContextSeesNoAuditTrail` | Context-less reads of the audit trail. |
| `aTenantCannotForgeAnAuditEntryAgainstAnotherTenantsUser` | `WITH CHECK` being dropped from the events_log policy — writing into someone else's audit trail is worse than reading it. |
| `aTenantCanStillWriteItsOwnAuditEntries` | Over-tightening the above into something that blocks legitimate writes. |
| `theLoginDoorResolvesAUserWithoutOpeningTheDirectory` | The pre-auth function breaking, or its bypass leaking past the call. |

### `RepositoryTenantIsolationIT` (3)

| Test | Guards against |
|---|---|
| `hibernateFindAllReturnsOnlyTheCurrentTenantsRows` | RLS holding for JdbcTemplate but not for Hibernate-generated SQL. |
| `hibernateFindByIdCannotLoadAnotherTenantsRow` | The first-level cache or a PK lookup path sidestepping the policy. |
| `entitiesMatchTheRealSchema_validateWouldHaveFailedOtherwise` | Entity/schema drift under `ddl-auto: validate`. |

## Ordering caveat (read before touching `@EnableTransactionManagement` or `@Order`)

`TenantContextAspect` must run *inside* the transaction boundary, not outside it — see the long
comment at the top of that class and in `CloseMoreBackendApplication`. Verified by
`aspectRefusesToRunOutsideARealTransaction`.

## Phase 0 safety guards (read before removing anything)

Two beans exist only to make the Phase 0 shortcut impossible to ship:

- `UserContextFilter` reads `X-User-Role` and `X-User-Tenant` straight off the client. A
  client-supplied tenant defeats the entire RLS design, so the bean is `@Profile("!prod")` — it
  cannot deploy under the prod profile by accident. Remove the guard in Phase 2, when role and
  tenant come from the `users` row instead of the request.
- `SecurityConfig` permits all requests. Also `@Profile("!prod")`. If it ever reaches prod, Boot's
  default security autoconfiguration takes over and locks everything behind basic auth — a loud
  outage rather than a silently open API.

## Deploying to production (not yet done — read before you do)

The test setup and production differ in exactly two ways, both of which fail silently if missed:

1. **Production needs its own non-superuser app role.** `db/init/01-create-app-role.sql` is the
   spec for its privileges, but it is test-only and cannot run there.
2. **V11's function needs an explicit grant.** The migration deliberately grants `EXECUTE` to
   nobody so it stays portable; tests pick it up via `ALTER DEFAULT PRIVILEGES`. Production has no
   equivalent, so login breaks in prod while every test stays green:

   ```sql
   GRANT EXECUTE ON FUNCTION auth_lookup_user_by_email(TEXT) TO <prod_app_role>;
   ```

## Error handling note for Phase 3

An RLS denial is SQLSTATE `42501`. Spring's `SQLStateSQLExceptionTranslator` reads the class code
`42`, finds it in `BAD_SQL_GRAMMAR_CODES`, and returns `BadSqlGrammarException` — whose constructor
is the one branch in that translator that drops `ex.getMessage()`. So a denial arrives as:

- **Type:** `BadSqlGrammarException`, not `PermissionDeniedDataAccessException`
- **Message:** `...; bad SQL grammar [INSERT INTO ...]` — the reason is gone
- **Cause:** the `PSQLException` carrying SQLSTATE `42501` and the real explanation

To return 403 rather than 500, match the SQLSTATE; the type and message will both mislead you:

```java
catch (BadSqlGrammarException e) {
    if ("42501".equals(e.getSQLException().getSQLState())) { /* RLS denied - a boundary, not a bug */ }
}
```

## Resolved (previously open questions)

- ~~`events_log` has no RLS policy~~ — **closed by `V10__events_log_rls.sql`.** It now has
  `ENABLE`/`FORCE` plus a tenant policy derived from the acting user's organisation, with
  `WITH CHECK` so audit entries can't be forged across tenants. `products` and `pipelines` remain
  without RLS, deliberately: they read as global reference data. If either gains a tenant-owned
  column it needs a policy, and `RlsWiringPreconditionsIT.TENANT_TABLES` is where to add it.
- ~~Which planning document is authoritative~~ — **Migration Plan v3**, see top of this file.
- ~~The schema has 16 tables, not 15~~ — confirmed against `pg_class`; docs still need correcting.

**Found and closed during Phase 1, not previously tracked:** V4's `users_rls_policy` ended with
`OR current_setting('app.current_user_tenant', true) IS NULL OR ... = ''`, meaning any context-less
query could read the entire cross-tenant user directory. `contacts` still returned nothing (its
policy independently requires a matching organisation), which is why no test caught it. Closed by
`V11__users_rls_tighten.sql`.

## Explicitly deferred to later phases (do not build these yet)

- Real authentication (header-trust vs. JWT — Finding 2, Phase 2)
- Password hashing (Finding 1, Phase 2)
- Global exception handling / `RbacException` → HTTP response mapping (Phase 2)
- JPA entities, DTOs, and repositories for the remaining 14 tables (Phase 1)