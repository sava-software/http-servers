# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants against the accepted baseline in `<suite>-accepted.csv`
and **fails on anything new**. Full policy lives in sava-build's `HARDENING.md`.

## hello suite (2 keys, both `SURVIVED`) — seeded 2026-07-22

`HelloServerTests` boots the demo through ServiceLoader against all three
backends — the only end-to-end exercise of the provider wiring (both the
module-path `provides` clauses and the classpath `META-INF/services` entries,
which PIT's classpath minions resolve). `Entrypoint` is excluded from
mutation: a thin main wrapper holding an argument default and an eternal
sleep, unreachable in-harness.

- `HelloServer.start` 31 (`EQUAL_IF` on the `includePath` guard): the demo's
  wiring excludes only `/exclude`, so the guard is always true for
  `/hello` — it exists to demonstrate the API, not to branch.
- `HelloServer.start` 34 (`VoidMethodCall` on the excluded-path
  registration): the call's entire purpose is to be filtered into a no-op by
  the wiring exclusion — removing it produces the same nothing, which is
  exactly what `helloServesAndExcludedPathDoesNot` pins via the 404.
