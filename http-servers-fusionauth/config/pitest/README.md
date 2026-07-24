# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants against the accepted baseline in `<suite>-accepted.csv`
and **fails on anything new**. Full policy lives in sava-build's `HARDENING.md`.

## Why two suites

The java-http framework's own threads log through the
`FusionAuthJulLogger` shim, so mutating the shim while socket tests run can
wedge the server itself — past PIT's per-test timeout (observed 2026-07-22 as
a run hung for 40+ minutes). The `loggerShim` suite therefore owns
`fusionauth.logging.*` with the in-process `FusionAuthJulLoggerTests` as its
only covering tests, and `dispatch` excludes the package.

The dispatch suite runs `STRONGER,EXPERIMENTAL_NAKED_RECEIVER` since the
scripted `pitestMutatorTrial` re-measure 2026-07-24 (+2 mutants: one killed
by existing tests, and the `withLoggerFactory` shim installation — invisible
to `VoidMethodCall` because java-http's config API is fluent — killed by
`frameworkLoggingFlowsThroughTheJulShim`, which pins that java-http's own
logging surfaces through JUL).

## dispatch suite (5 keys, all `SURVIVED`) — seeded 2026-07-22

Registering this suite (with `FusionAuthConformanceTest`) found and fixed two
real pre-flight defects: detection used `containsKey` with the canonical
header name against java-http's lowercase-keyed map — so **CORS pre-flights
had never worked** (any browser pre-flight got a 405) — and the pre-flight
response never set `Access-Control-Allow-Methods`, which browsers require.
Also killed by pinning: `ResponseUtil.setContentLength` (explicit
`Content-Length` asserted on cached responses); two dead `writeResponse`
overloads were deleted outright.

- `FusionAuthController` 26 (`EQUAL_IF` on the blank-ACRM check): treating a
  blank `Access-Control-Request-Method` as a pre-flight looks up method `" "`,
  which no handler map contains — the same 405 + Allow the non-pre-flight
  path returns. The non-blank contract itself is pinned by
  `blankRequestMethodHeaderIsNotAPreflight`.
- `FusionAuthController` 55 (`EQUAL_IF` on `origin != null`): forcing the
  branch with a null origin calls `setHeader(ACAO, null)`, a no-op; the
  no-Origin pre-flight sub-case has no well-defined semantics to pin
  (mirrors the Jetty controller's equivalent row).
- `FusionAuthServerBuilder.initRestServer` 23 (both surviving directions):
  `InetAddress.getByName(null)` resolves to loopback, which serves the same
  local clients the wildcard bind does; the wildcard direction is the usual
  superset. The throwing direction (blank host forced into `getByName`) is
  killed by `absentHostBindsAllInterfaces`.
- `FusionAuthRequest.body` 37 (`EQUAL_ELSE`): the `null -> empty array`
  guard's null side is unreachable — java-http hands back an empty body for
  body-less requests (the guarded contract itself is pinned by
  `bodyOnAGetRequestIsEmptyNotNull`). Defensive, retained.

## loggerShim suite — no accepted mutants

`loggerShim-accepted.csv` is empty and the suite runs at 100% (13 mutants)
against `FusionAuthJulLoggerTests` (level mapping both directions, every
emit method, threshold gating). Keep it that way.
