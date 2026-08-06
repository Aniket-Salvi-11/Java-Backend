# Phase 2 cutover — superseded

This document covered Phase 2 only and was written before any endpoint existed. It has been
replaced by **[`docs/CUTOVER.md`](CUTOVER.md)**, which covers Phases 2 and 3 in one ordered
document.

Everything still true has been carried across. What changed:

- The `EXECUTE` grant list is now **ten** functions, not eight — V17 added
  `auth_registration_policy` and `auth_signup`.
- The Flyway baseline verification now expects `V10` through `V17`.
- `V16` and `V17` were added to the migration table.
- Frontend/mobile now have real work: bearer tokens, the `/api/v1` path change across 53 routes,
  and the new registration endpoints.
- Two new sections: behaviours derived rather than specified (confirm against the JS code), and
  security decisions this repo deliberately did not take.

The original text is in git history if a Phase 2-only view is ever needed:

```bash
git log --follow -p -- docs/PHASE2_CUTOVER.md
```
