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

## Explicitly deferred to later phases (Not build these yet)

- Real authentication (header-trust vs. JWT - Finding 2, Phase 2 item 10)
- Password hashing (Finding 1, Phase 2 item 11)
- Global exception handling / `RbacException` -> HTTP response mapping (Phase 2 item 12)
- JPA entities, DTOs, and the real 15-table Flyway migrations (Phase 1)
