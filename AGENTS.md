# http-servers

Java 25 multi-module library providing a small HTTP server abstraction (`http-servers-core`)
with pluggable backends (`http-servers-jdk`, `http-servers-jetty`, `http-servers-fusionauth`),
a demo module (`http-servers-hello`), and an x402 payment gate for the Solana `exact` scheme
(`http-servers-sava`). Built with the shared `software.sava.build` Gradle plugin (same plugin
family as the `sava` repo); the `hardening` convention plugin provides PIT mutation testing and
Jazzer fuzzing.

## Testing

`./gradlew build` compiles and runs the JUnit 5 suites for every module. The security-relevant
surfaces additionally carry PIT mutation suites and Jazzer fuzz harnesses, configured through
the `hardening {}` block in each module's `build.gradle.kts`.

## Quality gate & mutation ratchet

<!-- hardening-template sha256:96ddf18dcc3a -->

Full policy: sava-build's `HARDENING.md`. Each `pitest<Suite>` run diffs its unkilled
mutants against the accepted baseline in the module's `config/pitest/<suite>-accepted.csv`
and fails on anything new; triage reasons and the untriaged-debt ledger live in each
module's `config/pitest/README.md`. The parts that bite most often:

- **Scale verification to the change.** Iterate with the module's `test` task; before
  handing off, run only the `pitest<Suite>`(s) whose mutated code the change can reach —
  including a dependent module's suite when it calls the changed API, and the owning suite
  for test-only edits, since a weakened test is exactly what the ratchet catches.
- **The full `qualityGate` — every suite, serialized, diffed against `config/pitest/` — is
  the pre-release check, owned by the local release checklist.** CI runs `check` via the
  shared sava-build workflow; run the gate locally before deciding to release (with
  `-PnoMutationHistory` if arcmutate history is ever activated here), alongside long fuzz
  runs — `fuzz<Target> -PmaxFuzzTime=<seconds>` on every registered harness. It is not the
  inner loop.
- Suites: http-servers-core has `pitestHandlers`, `pitestWiring`, `pitestServer`,
  `pitestResponse` and `pitestLogging`; http-servers-sava has `pitestX402` and
  `pitestHandlers`; each adapter has a `pitestDispatch` (routing/error dispatch, killed
  through socket round trips), and fusionauth additionally `pitestLoggerShim` — split out
  because the framework's own threads log through the shim, so mutating it under socket
  tests can wedge the server past PIT's timeout. The hello demo has `pitestHello`
  (`HelloServerTests` boots the demo through ServiceLoader against all three backends);
  every module is ratcheted. The adapters declare their `HttpServerBuilderFactory` both in
  `module-info` and in `META-INF/services`, so discovery works on the classpath (including
  PIT's minions) as well as the module path.
  The jetty socket suite's handled-flag family flaps between detected and `SURVIVED`
  under load; its baseline holds the union, so stale-entry warnings there are expected.
- Every adapter has a `*PostHandlerTest` (happy paths, 405 + Allow) and a
  `*ConformanceTest` pinning the parts of the `Request`/`HttpResponse` contract every
  backend must agree on — the **raw** query string (documented on `Request.query()`;
  `JdkRequest` decoded it until 2026-07-22, corrupting boundary scans on percent-encoded
  delimiters), routing semantics (query-handler paths match exactly plus the
  trailing-slash alias, path handlers match by prefix; the JDK adapter prefix-matched
  everything through per-path jdk contexts until 2026-07-22, when `JdkController` moved to
  the shared `HandlerMap` lookup from a single root context), 500 on a throwing handler
  (the JDK adapter used to abort the connection from blocking handlers and hang the client
  from non-blocking ones), custom status/header propagation (the x402 402-plus-header
  shape), cached JSON responses, case-insensitive header lookup, the body-never-null
  contract, and CORS pre-flight semantics (including that a blank
  `Access-Control-Request-Method` is not a pre-flight) — registering the fusionauth suite found that
  its pre-flight detection probed a lowercase-keyed header map with the canonical name
  (pre-flights always 405'd) and omitted `Access-Control-Allow-Methods`; both fixed
  2026-07-22.
- A new unkilled mutant has exactly three legal outcomes: **kill it** with a test (prefer
  asserting the property it breaks over restating the implementation), **refactor** it out
  of existence, or **accept it** with a written reason in the module's
  `config/pitest/README.md`. Never run `-PupdateMutationBaseline` just to make the build
  pass.
- **`SURVIVED` and `NO_COVERAGE` are different problems.** The first is a judgment call
  about equivalence; the second is an untested line and is mechanical work. Never accept a
  `NO_COVERAGE` mutant as "equivalent" — you have not observed its behaviour.
- **A suite's percentage is not a target.** An accepted mutant with a written reason is
  finished work, not debt. Before trying to raise a number, check whether the remainder is
  `NO_COVERAGE` (real work) or documented equivalents (already closed).
- Line-number churn from editing a mutated file shows up as paired stale + "new" baseline
  entries; confirm they're the shifted old ones before refreshing.
- **Randomized tests use fixed seeds, and never sleep**: the ratchet needs deterministic
  kills, and PIT re-runs the suite per mutant, so one real wait costs minutes. Exploration
  belongs to the fuzz targets. Time-dependent code takes a clock seam; give test clocks a
  non-zero origin.
- **Do not rely on PIT's timeout to detect a mutant.** `TIMED_OUT` counts as detected and
  is load-dependent — the same mutant can report `SURVIVED` alone and `TIMED_OUT` under
  `qualityGate`. Verify a changed baseline in both modes; union only rows observed to flip,
  never every `TIMED_OUT` row. The core `handlers` suite carries 2 timed-out mutants
  observed stable in both modes (see its `config/pitest/README.md`).
- **A flaky harness is worse than recorded debt.** If an interleaving or a boundary cannot
  be made deterministic, accept the mutant with a written reason rather than chasing it
  with sleeps or spin-waits. Allocation and timing harnesses are a last resort, reserved
  for stated design goals — they re-run once per mutant and need a `volatile` sink.
- **A wandering unkilled count is a defect, not noise** — chase it before refreshing any
  baseline. Known causes: real waits, `TIMED_OUT` load flips, `@Execution`/`@TestInstance`
  not reaching concrete classes from an abstract base (version-dependent — check the
  resolved JUnit jar), and coverage attributed to field initializers — exercise factories
  from inside a `@Test`.
- **Kill rates are bounded by the mutator set.** `BigInteger`/`BigDecimal` arithmetic is
  method calls, invisible to the default arithmetic mutators — if fee math on Big types is
  ever introduced, trial `EXPERIMENTAL_BIG_INTEGER` per suite, enable only what fires, and
  record the numbers in `config/pitest/README.md`.
- Exclusions must cover the **test source set**, not a naming convention: shared fakes are
  named `RecordingFoo`/`StubFoo` and match no `*Test*` pattern. After registering or
  widening a suite, check the verify task's warning and confirm no mutated class lives
  under `src/test`.
- **Verify by the absence of failures, not the presence of passes.** Counting `PASSED`
  lines hides a failure next to them, and a green `clean build` can mean the build cache
  short-circuited. A *failed* PIT run leaves the previous report in place — trust the exit
  code, and delete report directories when comparing runs. A suite that got faster without
  getting narrower is a bug report (exception: a summary carrying the `[history]` marker
  is arcmutate incremental reuse).
- **Transient infra failures are not results.** PIT `MINION_DIED` fails before writing a
  report — re-run the suite; a Gradle-worker `EOFException` death is the same shape, and a
  per-mutant `RUN_ERROR` under load is the same shape smaller (not counted as detected).
  The daemon log (`~/.gradle/daemon/<version>/daemon-<pid>.out.log`) keeps a failed
  build's full output even when the shell discarded it.
- Fuzz findings become a committed seed input **and** a named regression test, never just
  a fix — and every committed corpus is replayed by a unit test inside `check`
  (`VerifyFuzzRegressionTest`, `PayloadFuzzRegressionTest`,
  `HandlerUtilFuzzRegressionTests`), so a new seed replays automatically and the corpus
  cannot rot between fuzz runs.
- **When one thing has two representations, fuzz the differential.** The existing
  harnesses assert agreement (direct-JSON vs Base64-header parse; the gate's total 402/200
  contract), not just absence of crashes — keep new harnesses to that bar.

The `hardening-template` marker above is checked by `agentsTemplateInSync` (wired into
`check`): when sava-build's agent-instructions template changes, the build fails until this
block is re-diffed against it and the marker updated to the digest the failure prints. Sync
or **act on** each changed bullet before updating the marker — a new requirement may mean
new code, not just new prose.

### http-servers-core — request routing (`software.sava.http_servers.core.handlers`)

The first code to touch every untrusted request: query-string parsing (`HandlerUtil`) and
method/path resolution (`HandlerMapImpl`, `HandlerLookup`).

- `./gradlew :http-servers-core:pitestHandlers` — PIT over the `handlers` package (wildcard)
  against `handlers.*Test*`. 69 mutants; 1 accepted equivalent (triaged in
  `config/pitest/README.md`) and 2 timed-out (stable in both run modes). Tests live in
  `HandlerUtilTests` and `HandlerMapTests`.
- `./gradlew :http-servers-core:pitestWiring` — PIT over `BaseHandlerWiring` (the handler-group
  include/exclude filter that decides which handlers get registered) against
  `BaseHandlerWiringTests`. 78 mutants, **100% killed**, empty baseline — keep it that way.
- `./gradlew :http-servers-core:pitestServer` — PIT over the `server` package except
  `BaseHandlerWiring` (owned by `pitestWiring`), against `server.*Test*`. Covers the
  builder's trailing-slash aliasing, method routing, controller snapshotting and the
  factory service lookup and registration logging (`BaseHttpServerBuilderTests`).
  38 mutants; 1 accepted entry.
- `./gradlew :http-servers-core:pitestResponse` — PIT over the `response` package
  (`HttpResponse` factories and `withHeader` copy semantics, `HttpResponseTests`).
  9 mutants, **100% killed**, empty baseline — keep it that way.
- `./gradlew :http-servers-core:pitestLogging` — PIT over `BaseJulLogger` against
  `logging.*Test*`. The placeholder formatter and `stringify` are package-private and
  asserted directly; emission and caller resolution are asserted through a capturing JUL
  handler (`BaseJulLoggerTests`). 55 mutants; 5 accepted equivalents, 2 stable timed-out.

`BaseHandlerWiring`'s include/exclude predicates must stay strict negations
(`includeGroup == !excludeGroup`, `includePath == !excludePath`) across the full truth table;
`BaseHandlerWiringTests` enforces this.

Query param lookup must match only at a parameter boundary (query start or after `&`), never
as a substring (`page=` must not match inside `perpage=`) — use `indexOfParam`, not
`query.indexOf`. Applies to both this module's `HandlerUtil` and `http-servers-sava`'s
`handlers.HandlerUtil`. `./gradlew :http-servers-core:fuzzHandlerUtil` runs a differential
harness (`HandlerUtilFuzz`): the hand-rolled boundary scanner against a naive split-based
reference, required to agree on every input — value, absence, integers, or exception class —
because since value decoding landed the parser is no longer just a splitter. Seeds live under
`src/test/resources/fuzz/handlerUtil` and are replayed by `HandlerUtilFuzzRegressionTests`.

### http-servers-sava — x402 payment gate (`software.sava.http_servers.sava.x402`)

The module's threat model is a client-controlled `X-PAYMENT` header (Base64 → JSON → a
partially-signed Solana transaction) that a facilitator would co-sign and submit. A parsing
defect or a verification rule the code fails to enforce is a payment the facilitator wrongly
sponsors, so this is the most heavily tested surface.

- `./gradlew :http-servers-sava:pitestX402` — PIT over the whole `x402` package (models, gate,
  verifier, settler, cache) against `x402.*Test*`. The `RpcTransactionSubmitter` inner class
  (thin adapter over `SolanaRpcClient`, exercised only against a live node) and the `*Fuzz`
  harnesses are excluded. 367 mutants, 96% detected; the 13 baseline keys are all triaged
  equivalents with per-key reasons in `config/pitest/README.md` — chiefly guards whose
  removal funnels to the identical error response, and sub-states `TransactionSkeleton`'s
  asymmetric lazy resolution cannot produce (out-of-range program indices throw eagerly;
  account indices resolve to null; data lengths overrun silently — pinned by the
  corruption tests in `SvmExactVerifierTest`).
- `./gradlew :http-servers-sava:pitestHandlers` — PIT over `handlers.*` (public-key query
  params) against `handlers.*Test*`. 43 mutants, 88% detected; the 4 baseline keys are
  triaged equivalents (empty-list identity and unreachable scan boundaries — see
  `config/pitest/README.md`).
- `./gradlew :http-servers-sava:fuzzSvmVerify -PmaxFuzzTime=<seconds>` — Jazzer over
  `SvmExactVerifyFuzz`, which feeds raw bytes to `SvmExactVerifier.verify(requirements, bytes)`
  under both memo and no-memo requirements. Contract: **any input yields a `VerifyResponse`,
  never a throwable** (the gate calls verify with no try/catch), an accepted input never names
  the fee payer as the paying authority, and the response survives its own JSON round-trip.
  Seeded from valid payment transactions under `src/test/resources/fuzz/svmVerify` (with two
  committed regression inputs, `crash_*`).
- `./gradlew :http-servers-sava:fuzzX402Payload -PmaxFuzzTime=<seconds>` — Jazzer over
  `X402PayloadFuzz`, exercising every model parser and the gate end to end. Contract: the
  parsers tolerate any `RuntimeException`, the direct-JSON and Base64-header paths agree, and
  `X402Gate.httpResponse` answers every request with a 402 or the protected 200, never a
  throwable. Seeded from `src/test/resources/fuzz/x402Payload`.

The up-front instruction validation in `SvmExactVerifier.verify` (non-null program, non-null
accounts, in-bounds data slice, returning `TRANSACTION_COULD_NOT_BE_DECODED` otherwise) is
load-bearing, not redundant — but asymmetrically so. `TransactionSkeleton` resolution was
probed 2026-07-22: an out-of-range *program* index throws eagerly inside `parseInstructions`
(caught by `verify`'s own `try/catch`), while an out-of-range *account* index resolves
silently to a `null` account and a corrupted data length yields a slice overrunning the
transaction bytes — states the rule checks would otherwise dereference and throw past
`verify`. The `crash_*` inputs in the `svmVerify` corpus (replayed by
`VerifyFuzzRegressionTest`) and the corruption tests in `SvmExactVerifierTest`
(`unresolvableProgramIndexRejected`, `unresolvableAccountIndexRejected`,
`overrunningDataSliceRejected`) guard this.

### Adding a target

- **Mutation suite**: add `mutation.register("<name>") { targetClasses = ...; targetTests = ... }`
  to the module's `hardening {}` block. Exclude test/fuzz helpers that live in the target
  package via `excludedClasses`.
- **Fuzz harness**: write a class with `public static void fuzzerTestOneInput(byte[])` and no
  Jazzer imports (so it compiles with the regular test sources), then
  `fuzz.register("<name>") { targetClass = ...; maxLen = ...; seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/<name>") }`.
  For any structured format a `seedCorpus` of committed inputs is required — a from-scratch
  mutator cannot assemble a valid base64/JSON/transaction. The writable corpus accumulates in
  `build/fuzz/<name>-corpus`. When the fuzzer finds a crash, copy the reported `crash-*`
  artifact into the seed corpus as a named regression input and add a replay assertion.
