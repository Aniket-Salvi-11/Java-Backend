# closemore-backend

Fresh Java/Spring Boot backend for CloseMore CRM . This repo
starts at **Phase 0 - Foundation**.

## Phase 0 scope (build sequence, steps 1-4)

| # | Item | Status in this commit |
|---|---|---|
| 1 | Finalize the RLS/session-variable replication strategy | **Done** - Spring AOP `@Around` advice on `@Transactional` service methods. See `aop/TenantContextAspect.java`. |
| 2 | Set up the full 15-table schema locally via Testcontainers | **Done** - `001_init.sql`...`008_deal_teams.sql` copied verbatim into `db/migration/` as `V1__init.sql`...`V9__deal_teams.sql`. `TenantIsolationIT` now seeds two real tenants (bypassing RLS the same way `seed.mjs` does) and asserts isolation on the real `contacts` table. |
| 3 | Extract the RBAC rules verbatim | **Done** - `rbac/RbacService.java`, unit tested in `RbacServiceTest.java`. |
| 4 | Scaffold the Spring Boot project (Web, Security, Data JPA, Validation, PostgreSQL Driver, Lombok) | **Done** - see `pom.xml`. AOP and Flyway starters added on top since the RLS mechanism and schema setup both need them. |

## Ordering caveat (read before touching `@EnableTransactionManagement` or `@Order`)

`TenantContextAspect` must run *inside* the transaction boundary, not outside it - see the
long comment at the top of that class and in `CloseMoreBackendApplication`. Verified by
`TenantIsolationIT`.

<<<<<<< ours
## Explicitly deferred to later phases (Not build these yet)
=======
## Running Phase 0 locally

Requires Docker (for Testcontainers) and a JDK 17+.

```bash
mvn test          # unit tests only - RbacServiceTest. Does NOT run TenantIsolationIT.
mvn verify        # unit tests + TenantIsolationIT (spins up a throwaway Postgres via Docker)
mvn spring-boot:run
curl -H "X-User-Id: u-1" -H "X-User-Role: Admin" -H "X-User-Tenant: Acme" \
     http://localhost:8080/internal/tenant-context-echo
```

The manual curl above is the fastest way to eyeball that the AOP wiring actually works before
trusting the automated test - see `TenantContextEchoController`.

**`mvn verify`, not `mvn test`, is the gate.** Surefire's default includes (`Test*`, `*Test`,
`*Tests`, `*TestCase`) do not match `TenantIsolationIT`, so `mvn test` skips it silently. Failsafe
is bound to the `verify` phase in `pom.xml` for exactly this reason. CI must run `mvn verify`.

## What `TenantIsolationIT` actually asserts

Five things, each guarding a specific way the RLS mechanism can break:

| Test | Guards against |
|---|---|
| `concurrentTenantsOnASharedPoolNeverSeeEachOthersRows` | Cross-tenant bleed via a reused pooled connection. Pool is pinned to `maximum-pool-size=1` so the two tenants are *guaranteed* to share one physical connection - with the default pool of 10 they would get separate connections and the test would pass without ever exercising reuse. |
| `aTenantCannotReachAnotherTenantsRowEvenByPrimaryKey` | A count-based assertion passing for the wrong reason (right number of rows, wrong rows). |
| `classLevelTransactionalServicesAlsoGetTheSessionVariables` | The pointcut being narrowed back to `@annotation` only, which would miss `@Service @Transactional public class DealService` - the idiomatic form Phase 3 services will use. |
| `aRequestWithNoUserContextSeesNothing` | An accidentally permissive RLS policy. Documents the safe failure mode as an assertion, not a comment. |
| `sessionVariablesDoNotSurviveOntoTheNextRequestOnTheSameConnection` | `set_config`'s transaction-local flag being dropped, or the connection being released before COMMIT. |
| `aspectRefusesToRunOutsideARealTransaction` | The advice ordering contract (`@EnableTransactionManagement(order = 0)` + `@Order(1)`) inverting. |

## Phase 0 safety guards (read before removing anything)

Two beans exist only to make the Phase 0 shortcut impossible to ship:

- `UserContextFilter` reads `X-User-Role` and `X-User-Tenant` straight off the client. A
  client-supplied tenant defeats the entire RLS design, so the bean is annotated
  `@Profile("!prod")` - it cannot be deployed under the prod profile by accident. Remove the guard
  in Phase 2, when role and tenant come from the `users` row instead of the request.
- `SecurityConfig` permits all requests. Also `@Profile("!prod")`. If it ever reaches prod, Boot's
  default security autoconfiguration takes over and locks everything behind basic auth - a loud
  outage rather than a silently open API.

## Open questions for the team (raised by Phase 0, answered by someone else)

- **`events_log` has no RLS policy.** Every table except `products`, `pipelines` and `events_log`
  has `ENABLE`/`FORCE ROW LEVEL SECURITY` plus a policy. `events_log` has neither, so
  `GET /api/admin/events-log` (most recent 500, Admin-only) is protected by the application check
  alone - an Admin in one organisation would see other organisations' audit entries unless
  something outside the schema prevents it. Needs a decision from the DB owner: intentional, or a
  gap to close before Phase 3 builds the Admin API?
- **The schema has 16 tables, not 15.** Both planning documents say 15; `V1`-`V9` create
  `users, products, pipelines, contacts, deals, line_items, deal_contacts, activities,
  activity_attachments, events_log, tasks, task_attachments, task_comments, comment_reactions,
  task_notifications, deal_team_members`. Worth correcting in the docs so the Phase 1 entity count
  isn't planned off a wrong number.
- **The two planning documents describe different projects.** The Build Plan PDF is a ground-up
  rebuild across 7 domains judged on business behaviour; Migration Plan v3 is a like-for-like port
  of 57 endpoints across 12 resource groups with the schema and RLS reused untouched. This repo has
  committed to the second. Phase 3 scope and timeline differ substantially depending on which one
  is authoritative - the Build Plan omits Tasks, Dashboard, Auth and Admin entirely.

## Explicitly deferred to later phases (do not build these yet)
>>>>>>> theirs

- Real authentication (header-trust vs. JWT - Finding 2, Phase 2)
- Password hashing (Finding 1, Phase 2)
- Global exception handling / `RbacException` -> HTTP response mapping (Phase 2)
- JPA entities, DTOs, and repositories for all 16 tables (Phase 1)
