# Migration notes

## Read this before trusting a policy you found in a .sql file

Several migrations **drop and recreate** policies defined earlier. Those earlier definitions are
still sitting in their files, unmarked, and look authoritative. They are not.

Flyway checksums applied migrations, so a superseded file cannot be edited — not even to add a
comment — without breaking validation against any database that already ran it. Hence this map
rather than notes in the files themselves.

**When in doubt, ask the database, not the repository:**

```sql
SELECT tablename, policyname, qual FROM pg_policies WHERE schemaname = 'public';
```

## Supersession map

| Policy | Defined in | Superseded by | Live version |
|---|---|---|---|
| `contacts_rls_policy` | V3, V4 | — | **V4** |
| `users_rls_policy` | V4 | V11 | **V11** |
| `deals_rls_policy` | V3, V4 | V9 | **V9** |
| `line_items_rls_policy` | V3, V4 | V9 | **V9** |
| `deal_contacts_rls_policy` | V3, V4 | V9 | **V9** |
| `activities_rls_policy` | V3, V4 | — | **V4** |
| `activity_attachments_rls_policy` | V3, V4 | — | **V4** |
| `deal_team_members_rls_policy` | V9 | — | **V9** |
| `tasks_rls_policy` and the rest of the Tasks group | V8 | — | **V8** |
| `events_log_rls_policy` | V10 | — | **V10** |

V3 is superseded wholesale by V4 — every policy it creates is recreated there. Treat V3 as history.

## What the supersessions actually changed

**V9 added team membership as a route to read access.** Reading V4's `deals_rls_policy` suggests
only the owner plus Admin/Executive can see a deal. The live policy has a third branch: anyone
listed in `deal_team_members` for that deal. The same clause was added to `line_items` and
`deal_contacts`.

Note V9 did **not** touch `activities`, so a team member can open a deal and still see none of its
activity history. That asymmetry is pinned by
`ActivitiesIsolationIT.aDealTeamMemberCanOpenTheDealButSeesNoneOfItsActivities` — it looks like an
oversight in the original rather than a decision, but changing it is a product call, not a port
call.

**V11 closed a hole in `users`.** V4's policy ended with:

```sql
OR current_setting('app.current_user_tenant', true) IS NULL
OR current_setting('app.current_user_tenant', true) = ''
```

which let any connection without a tenant context read the entire cross-tenant user directory. It
was scaffolding for login, which by definition runs before a tenant context exists. V11 removes the
clause and replaces it with `auth_lookup_user_by_email(TEXT)`, a `SECURITY DEFINER` function that
answers one question and cannot enumerate.

**V10 gave `events_log` a policy at all.** It had none — the table was reachable by any role that
could connect, and it holds `Before_State`/`After_State` record snapshots.

## Conventions for new migrations

- Every tenant-scoped table needs `ENABLE` **and** `FORCE ROW LEVEL SECURITY`. `FORCE` matters
  because the table owner otherwise bypasses its own policy.
- Include a `WITH CHECK` clause, not just `USING`. Without it a tenant can write rows it cannot
  read — forging an audit entry or a team membership in someone else's organisation.
- Do not reference `closemore_app` by name. That role exists only in the test environment; grants
  ride on `ALTER DEFAULT PRIVILEGES` set during container init. Migrations that name it will not
  run in production.
- Add the table to `RlsWiringPreconditionsIT.TENANT_TABLES` so a missing policy fails a test rather
  than leaking quietly.
- If you supersede an existing policy, add a row to the map above.
