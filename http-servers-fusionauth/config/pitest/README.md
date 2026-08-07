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

## dispatch suite (3 keys, all `SURVIVED`) — seeded 2026-07-22

Registering this suite (with `FusionAuthConformanceTest`) found and fixed two
real pre-flight defects: detection used `containsKey` with the canonical
header name against java-http's lowercase-keyed map — so **CORS pre-flights
had never worked** (any browser pre-flight got a 405) — and the pre-flight
response never set `Access-Control-Allow-Methods`, which browsers require.
Also killed by pinning: `ResponseUtil.setContentLength` (explicit
`Content-Length` asserted on cached responses); two dead `writeResponse`
overloads were deleted outright.

- `# blank-ACRM funnel` — `FusionAuthController` 26 (`EQUAL_IF`): treating a
  blank `Access-Control-Request-Method` as a pre-flight looks up method `" "`,
  which no handler map contains — the same 405 + Allow the non-pre-flight
  path returns. The non-blank contract itself is pinned by
  `blankRequestMethodHeaderIsNotAPreflight`.
- `# null-origin no-op` — `FusionAuthController` 61 (`EQUAL_IF` on `origin != null`): forcing the
  branch with a null origin calls `setHeader(ACAO, null)`, a no-op; the
  no-Origin pre-flight sub-case has no well-defined semantics to pin
  (mirrors the Jetty controller's equivalent row).
- The former wildcard-bind rows (`FusionAuthServerBuilder.initRestServer`
  23, both directions) were killed 2026-07-24 by `startOnAnOccupiedPortThrows`
  (the occupied `localhost` address distinguishes which address the listener
  binds), which also pins the never-silent start contract: java-http's
  `start()` logs bind failures instead of throwing, so
  `FusionAuthHttpServer.start` captures the shim's SEVERE record
  (thread-filtered) and rethrows — a port probe cannot attribute a listening
  socket to this server.
- `# defensive fallback` — `FusionAuthRequest.body` 37 (`EQUAL_ELSE`): the `null -> empty array`
  guard's null side is unreachable — java-http hands back an empty body for
  body-less requests (the guarded contract itself is pinned by
  `bodyOnAGetRequestIsEmptyNotNull`). Defensive, retained.

### Audited timeouts (`dispatch-timeouts.csv`)

**The set is empty and armed — this suite has no audited timeout member.**
`dispatch-timeouts.csv` is kept as comments only so a newly timed-out mutant
still lands as a reviewer-stop instead of passing as silent detection.

Its one member, `FusionAuthController.handle`
(`RemoveConditionalMutator_EQUAL_IF`, line 61 at the time), was retired
2026-08-05. It entered the set on 2026-08-02 because its covering tests are
full socket round trips whose wall clock exceeded PIT's per-mutant margin
(recorded × 1.25 + 4000 ms) under `qualityGate` parallelism. That was never a
liveness argument: forcing the branch makes `setHeader(ACAO, null)` — a no-op
producing a byte-identical response — so the mutated path terminates. Under
the cause classification the audit is a `class,method,mutator` judgment, and
the honest category here was `cause:resource`, which "terminates and needs a
deterministic contract-first disposition, not watchdog detection".

That disposition already existed and **remains**: the accepted
`# null-origin no-op` `SURVIVED` row above, whose equivalence argument is
unchanged by how slowly the tests run.

Retirement evidence — three agreeing history-free observations, each read from
the full report rather than an aggregate count, with the coordinate reading
`SURVIVED` and the audit key carrying zero `TIMED_OUT` copies in every one:

- a solo history-free preview;
- a fresh solo `pitestDispatch -PnoMutationHistory`;
- a fresh `-PnoMutationHistory` run under representative gate load, with six
  mutation suites executing in parallel.

The plugin independently reported the member "quiet for 3 runs" and eligible
for retirement. No baseline writer ran: `BaselinePrune` edits accepted
`SURVIVED`/`NO_COVERAGE` debt, not timeout membership, and both
`FusionAuthController.handle` accepted rows are preserved.

**Re-checked against sava-build 21.5.25 (2026-08-07): the retirement still
stands, and the stricter rules reinforce it.** The new doctrine requires, for
an *otherwise admissible liveness* member, three or more distinct fresh
full-run quiet notices over identical evidence inputs plus confirmation under
the relevant solo and gate loads — this retirement carried all of that. It also
never needed that rule: the member was not a liveness member. Forcing the
branch produces a byte-identical response, so the covering path is finite, and
21.5.25 states that a finite race "is benign only to baseline arithmetic, never
certifying evidence" and is repaired or retimed rather than admitted. Two
further new rules were checked and are satisfied: the plugin's quiet stash is a
machine-local nomination that was read, never copied or merged into any
committed record; and this suite's conformance requests do carry a fixture
bound — `HttpRequest.timeout(Duration.ofSeconds(10))` — which is recorded here
for completeness but was the claimed oracle for nothing, and cannot fire before
PIT's recorded-duration × 1.25 + 4000 ms margin.

## loggerShim suite — no accepted mutants

`loggerShim-accepted.csv` is empty and the suite runs at 100% (13 mutants)
against `FusionAuthJulLoggerTests` (level mapping both directions, every
emit method, threshold gating). Keep it that way.
