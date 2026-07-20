# closemore-backend

Fresh Java/Spring Boot backend for CloseMore CRM (not a JS-to-Java conversion). This repo
starts at **Phase 0 - Foundation**, per the migration plan.

## Phase 0 scope (build sequence, steps 1-4)

| # | Item | Status in this commit |
|---|---|---|
| 1 | Finalize the RLS/session-variable replication strategy | **Done** - Spring AOP `@Around` advice on `@Transactional` service methods. See `aop/TenantContextAspect.java`. |
| 2 | Set up the full 15-table schema locally via Testcontainers | **Done** - `001_init.sql`...`008_deal_teams.sql` copied verbatim into `db/migration/` as `V1__init.sql`...`V9__deal_teams.sql`. `TenantIsolationIT` now seeds two real tenants (bypassing RLS the same way `seed.mjs` does) and asserts isolation on the real `contacts` table. |
| 3 | Extract the RBAC rules verbatim | **Done** - `rbac/RbacService.java`, unit tested in `RbacServiceTest.java`. |
| 4 | Scaffold the Spring Boot project (Web, Security, Data JPA, Validation, PostgreSQL Driver, Lombok) | **Done** - see `pom.xml`. AOP and Flyway starters added on top since the RLS mechanism and schema setup both need them. |

## Why AOP `@Around` specifically

The reference doc lists three options for where to fire the `set_config` calls: a Hibernate
`Interceptor`, an `@Around` Spring AOP advice on the transactional service method, or manual
calls in a repository base class. This project uses the AOP approach - idiomatic Spring, and
the pointcut (`@annotation(...Transactional)`) makes it apply uniformly to every service
method without each one having to remember to call anything. The tradeoff called out
explicitly: it's only as safe as the discipline of never running a raw query outside an
advised (`@Transactional`) method - a query fired from a non-transactional context bypasses
the aspect entirely and runs with no tenant context, which (per the reference doc) fails
*safely* under RLS (empty results) rather than dangerously, but it's still a footgun worth
remembering as more services get added in Phase 3.

The other two options are worth knowing as fallbacks if that discipline turns out to be hard
to maintain in practice:
- **Hibernate `Interceptor`** - hooks lower, at the `Connection`/`Session` level, so it can't
  be accidentally skipped by an un-annotated method the way the AOP advice can - at the cost
  of being less idiomatic Spring and slightly more awkward to unit test in isolation.
- **Manual calls in a repository base class** - most explicit and easiest to reason about
  line-by-line, but pushes the responsibility back onto whoever writes the next repository to
  remember to extend the base class, which has the same discipline problem as the AOP option
  without the AOP option's automatic uniform coverage.

## Migration filename mapping

The original repo has two files both prefixed `005_` (`005_events_and_timestamps.sql` and
`005_user_approval.sql`). `migrate.mjs` applies files in `fs.readdir(...).sort()` order, i.e.
plain alphabetical - and `events_and_timestamps` sorts before `user_approval` ('e' < 'u').
Flyway needs distinct version numbers, so that pair became `V5` / `V6` respectively, preserving
the exact order `migrate.mjs` would have applied them in. Everything after that shifts up by one:

| Original | Flyway |
|---|---|
| `001_init.sql` | `V1__init.sql` |
| `002_columns.sql` | `V2__columns.sql` |
| `003_rls.sql` | `V3__rls.sql` |
| `004_tenant_rls.sql` | `V4__tenant_rls.sql` |
| `005_events_and_timestamps.sql` | `V5__events_and_timestamps.sql` |
| `005_user_approval.sql` | `V6__user_approval.sql` |
| `006_deal_financial_fields.sql` | `V7__deal_financial_fields.sql` |
| `007_tasks.sql` | `V8__tasks.sql` |
| `008_deal_teams.sql` | `V9__deal_teams.sql` |

Flyway's own `flyway_schema_history` table replaces the hand-rolled `schema_migrations` table
`migrate.mjs` created - same guarantee (apply each file once, in order), no code needed.

## Ordering caveat (read before touching `@EnableTransactionManagement` or `@Order`)

`TenantContextAspect` must run *inside* the transaction boundary, not outside it - see the
long comment at the top of that class and in `CloseMoreBackendApplication`. Verified by
`TenantIsolationIT`.

## Running Phase 0 locally

Requires Docker (for Testcontainers) and a JDK 17+.

```bash
mvn test          # runs RbacServiceTest + TenantIsolationIT (spins up a throwaway Postgres)
mvn spring-boot:run
curl -H "X-User-Id: u-1" -H "X-User-Role: Admin" -H "X-User-Tenant: Acme" \
     http://localhost:8080/internal/tenant-context-echo
```

The manual curl above is the fastest way to eyeball that the AOP wiring actually works before
trusting the automated test - see `TenantContextEchoController`.

## Explicitly deferred to later phases (do not build these yet)

- Real authentication (header-trust vs. JWT - Finding 2, Phase 2 item 10)
- Password hashing (Finding 1, Phase 2 item 11)
- Global exception handling / `RbacException` -> HTTP response mapping (Phase 2 item 12)
- JPA entities, DTOs, and the real 15-table Flyway migrations (Phase 1)
