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

<!-- hardening-template sha256:9ef03098c9cd -->

Full policy: sava-build's `HARDENING.md`. Each `pitest<Suite>` run diffs its unkilled
mutants against the accepted baseline in the module's `config/pitest/<suite>-accepted.csv`
and fails on anything new; triage reasons and the untriaged-debt ledger live in each
module's `config/pitest/README.md`. The parts that bite most often:

- **Scale verification to the change.** Iterate with the module's `test` task; before
  handing off, run only the `pitest<Suite>`(s) whose mutated code the change can reach —
  including a dependent module's suite when it calls the changed API, and the owning suite
  for test-only edits, since a weakened test is exactly what the ratchet catches.
- **The full `hardeningCertify` — every suite freshly observed, serialized,
  provenance-bound, and diffed against `config/pitest/` with strict timeout
  and ownership audits — is the pre-release mutation check, owned by the local
  release checklist.** CI runs `check`; run certification plus an explicit
  local `fuzzAll -PmaxFuzzTime=<seconds>` campaign before release. The
  repo-root `arcmutate-licence.txt` (an OSS certificate for `software.sava.*`,
  not a secret — the subscription download URL behind it is) only accelerates
  ordinary runs through arcmutate incremental analysis, whose machine-local
  history lives in the git-ignored `.pitest-history/`. Certification disables
  that history automatically and re-earns every status from scratch. What the
  licence changes is *reuse*, never the population: `com.arcmutate:base` stays
  on PIT's tool classpath whenever `arcmutate-licence.txt` is present, so
  ordinary runs, `-PnoMutationHistory` runs and certification all mutate the
  same set — jetty `dispatch` reads 72 in every mode (verified 2026-08-03).
  Only the `[history]` marker and the reuse behind it differ. Keep the licence
  committed so local and CI agree; a *count* that moves between runs is drift
  to chase, not a licence artifact. The process itself does not require
  arcmutate — removing the licence changes the population, which is why it is
  committed rather than machine-local like `.pitest-history/`.
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
  Maintain the union with `pitestModeSnapshot -PpitestMode=<label>` / `pitestModeCompare`
  / `-PunionModeFlips`, which writes the flip evidence into the row (per-mode statuses and
  the observed `# line` tag) — verify-side `-PunionMutationBaseline` is the escape hatch
  for a directly witnessed flip and lands bare rows that owe their evidence note by hand.
  "The cause remains" is a claim to re-measure, not a fact to record once.
- Every adapter has a `*PostHandlerTest` (happy paths, 405 + Allow) and a
  `*ConformanceTest` pinning the parts of the `Request`/`HttpResponse` contract every
  backend must agree on — the **raw** query string and path (documented on
  `Request.query()`; `JdkRequest` decoded both until 2026-07-22/24, corrupting boundary
  scans and handing decoded traversals to prefix handlers), routing semantics
  (query-handler paths match exactly plus the trailing-slash alias, path handlers match
  by prefix; the JDK adapter prefix-matched everything through per-path jdk contexts
  until 2026-07-22, when `JdkController` moved to the shared `HandlerMap` lookup from a
  single root context), **canonical routing** (since 2026-07-24 every lookup
  canonicalizes the raw path first — dot segments and benign escapes resolve before
  matching, and ambiguous targets (`%2F`, `%5C`, `%00`, `%25` double-encoding, encoded
  dot segments, empty segments, root-escaping `..`) answer 400 via
  `HandlerLookup.badRequest()`, never route; before this the JDK adapter routed
  `/files%2F..%2Fx` decoded into prefix handlers and FusionAuth prefix-matched raw
  unnormalized paths), a 204/304 answer crossing the wire bodyless, a 512 KiB POST
  round-tripping byte-identical, HEAD answering 405 + Allow (never derived from GET),
  500 on a throwing handler
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
  `config/pitest/README.md` **and a short family label on the row itself** — refreshes
  seed new rows `# untriaged`, and triage means replacing that label, so the baseline
  always says which rows are argued and which are debt. Rows that predate note seeding
  count as `unlabeled` in the verify summary; label them when touched. Never run
  `-PupdateMutationBaseline` just to make the build pass.
- **`SURVIVED` and `NO_COVERAGE` are different problems.** The first is a judgment call
  about equivalence; the second is usually an untested line and is mechanical work. Never
  accept a `NO_COVERAGE` mutant as "equivalent" — you have not observed its behaviour.
  One structural exception: a block that always exits by throw reads `NO_COVERAGE`
  forever, executed or not (PIT probes a block at its end), and its return-value mutants
  can never change status. Such a line is owed a test asserting the throw's contract, not
  coverage — and never leave one untested fearing a covered-line `SURVIVED` conversion,
  which would require the block to complete.
- **A suite's percentage is not a target.** An accepted mutant with a written reason is
  finished work, not debt. Before trying to raise a number, check whether the remainder is
  `NO_COVERAGE` (real work) or documented equivalents (already closed).
- Baseline keys are line-less (`class,method,mutator,STATUS`) — editing above a mutated
  method churns nothing, and `# line` tags are metadata, never part of the key. Which
  refresh rewrites a tag is not uniform: **a full update refreshes every line tag; a green
  prune refreshes the tags of the rows it retained and matched, even on a run that drops
  nothing; unions and format-only migration preserve the tags already on the rows.** So a
  stale tag surviving a union or a migration is expected, and is not evidence the row went
  unexamined. The trade is one documented hole: a new mutant replacing a killed one at the
  same key inherits its acceptance silently, so when the line-drift advisory names a key
  whose argument no longer reads against the code, treat it as that swap until shown
  otherwise. Legacy five-field files migrate on any baseline-rewriting refresh, or all at
  once with `migrateMutationBaselines` (no mutation run needed) — but only after every pin
  resolving the plugin is bumped, because pre-line-less plugin versions cannot read a
  migrated file.
- **Iterate with `-PmutateOnly=<class-glob>`** while killing a cluster — seconds instead
  of the full suite — then re-run unscoped before any refresh; the tooling refuses to let
  a scoped report touch the baseline.
- Identical baseline rows are sibling mutants of one compound condition and the comparison
  is a multiset: never hand-dedupe. When one sibling survives, the verify names the killed
  sibling's test — the survivor is the opposite branch direction; triage it as its own
  mutant. A key holding more unkilled mutants than baseline rows reads
  `(shares an accepted key — sibling debt surfaced, or a NEW mutant at that key; check
  the line)`: read the report's line numbers before accepting, because a genuinely new
  mutant at an accepted key is new debt, not surfaced history; refreshes seed such rows
  `# untriaged` like any other newcomer.
- **Randomized tests use fixed seeds, and never sleep**: the ratchet needs deterministic
  kills, and PIT re-runs the suite per mutant, so one real wait costs minutes. Exploration
  belongs to the fuzz targets. Time-dependent code takes a clock seam; give test clocks a
  non-zero origin.
- **Stubs and fixtures return distinguishable, non-default values.** A stub returning
  null/0/""/true/empty makes the matching return-value mutant equivalent by accident of
  the fixture — the clock non-zero-origin rule generalized to every stubbed return.
- **Copy-on-write clusters split by direction.** Assert immutability of returned
  collections (`assertThrows(UnsupportedOperationException, ...)`) at every size: the
  mutable-escape direction is a kill, not an acceptance; only the content-equal siblings
  are family-accepted equivalents.
- **Do not rely on PIT's timeout to detect a mutant.** `TIMED_OUT` counts as detected, is
  never written to a baseline, and is load-dependent — the same mutant can report
  `SURVIVED` alone and `TIMED_OUT` under `qualityGate`. Verify a changed baseline in both
  modes; union only rows observed to flip, never every `TIMED_OUT` row.
- **A new timed-out mutant is a reviewer-stop, not detection noise.** For exactly those
  mutants the ratchet cannot see a weakened covering assertion — the watchdog keeps
  "detecting" whatever the test asserts — so every suite that carries timeouts audits them
  as a *set*, not a count: line-less `class,method,mutator` rows in
  `config/pitest/<suite>-timeouts.csv`, with each member's structural cause (which loop
  exit the mutant removed, which socket wait it made unbounded) written in the module's
  `config/pitest/README.md`. The verify warns on any timed-out mutant outside the set
  (paste the printed row, then write the cause — admit a newcomer to the set only once its
  cause is written), on members matching no mutant in the run (retire them), and on
  members whose method appears nowhere in the README. Audited here:
  core `handlers` and `logging`, jdk `dispatch`, jetty `dispatch`; the remaining suites
  carry no timeouts and so have no file. Because the key is line-less, a *new* timeout in
  an already-audited method+mutator draws no warning — name the line in the README cause
  and re-read it when that code changes.
- **A flaky harness is worse than recorded debt.** If an interleaving or a boundary cannot
  be made deterministic, accept the mutant with a written reason rather than chasing it
  with sleeps or spin-waits. Allocation and timing harnesses are a last resort, reserved
  for stated design goals — they re-run once per mutant, need a `volatile` sink so escape
  analysis cannot delete the very thing they measure, and flap when the margin is thin.
- When a test you believe in will not go green, **suspect the code before you soften the
  assertion** — that is where this process finds its real bugs, and every dated fix in the
  conformance list above started as an assertion someone could have relaxed instead.
- **A wandering unkilled count is a defect, not noise** — chase it before refreshing any
  baseline. Known causes: real waits, `TIMED_OUT` load flips, `@Execution`/`@TestInstance`
  not reaching concrete classes from an abstract base (version-dependent — JUnit 6 marks
  both `@Inherited`, so check the resolved JUnit jar rather than assuming), and coverage
  attributed to field initializers — exercise factories from inside a `@Test`.
- **Build the subject under test inside the test body, not in a field.** Under `PER_CLASS`
  lifecycle a field-initialized client's construction coverage attaches to whichever test
  runs first, so wiring mutants can never pair with the test that drives what they wire —
  they survive even under a harness that asserts every request. One test that constructs
  the client in the test method and drives each configured URL restores the pairing.
- **Kill rates are bounded by the mutator set.** `BigInteger`/`BigDecimal` arithmetic is
  method calls, invisible to the default arithmetic mutators — if fee math on Big types is
  ever introduced, trial `EXPERIMENTAL_BIG_INTEGER` per suite, enable only what fires, and
  record the numbers in `config/pitest/README.md`. Fluent calls returning their receiver
  are likewise invisible to `VoidMethodCallMutator`; `EXPERIMENTAL_NAKED_RECEIVER` is
  enabled (trial numbers recorded in each module's `config/pitest/README.md`) on every
  suite where it fires — as of the 2026-07-24 `pitestMutatorTrial` re-measure that is all
  of them except core `wiring`/`response`, jdk `dispatch` and fusionauth `loggerShim`,
  whose code has no receiver-returning calls. Re-measure with
  `pitestMutatorTrial -PtrialMutators=<CANDIDATE>` when code evolves — the 2026-07-24
  pass found firing sites on four suites whose recorded claim was "nothing fires".
- **PIT minions run on the class path**, even though this repo's tasks run on the module
  path: `module-info` services are invisible to them, and a test-resources
  `META-INF/services` is invisible to the module-path `test` task. The adapters therefore
  declare `HttpServerBuilderFactory` in both places; never commit a harness whose
  *pass/fail* depends on which task ran it — but assertions may branch on a
  `ServiceLoader` probe (the probe-and-branch pattern), which is how test-only providers
  get covered under PIT. Core's `findFirst` success path is killed exactly this way:
  `BaseHttpServerBuilderTests.FixtureFactory` is registered in test-resources
  `META-INF/services` (class-path worlds resolve it, the module-path task asserts the
  no-provider throw), which retired the suite's last accepted `NO_COVERAGE` row
  2026-08-02. Nest fixture providers inside the test class — a top-level fixture matches
  no `*Test*` exclusion and silently joins the mutated population.
- Exclusions must cover the **test source set**, not a naming convention: shared fakes are
  named `RecordingFoo`/`StubFoo` and match no `*Test*` pattern. After registering or
  widening a suite, check the verify task's warning and confirm no mutated class lives
  under `src/test`.
- **Verify by the absence of failures, not the presence of passes.** Counting `PASSED`
  lines hides a failure next to them, and a green `clean build` can mean the build cache
  short-circuited. A *failed* PIT run leaves the previous report in place — trust the exit
  code, and delete report directories when comparing runs. A suite that got faster without
  getting narrower is a bug report — real speedups come from fewer mutants or faster
  covering tests (exception: a summary carrying the `[history]` marker is arcmutate
  incremental reuse, where fast is expected; `hardeningCertify` never carries it).
- **Transient infra failures are not results.** PIT `MINION_DIED` fails before writing a
  report, so it cannot corrupt one — re-run the suite; a Gradle-worker `EOFException`
  death is the same shape, and a per-mutant `RUN_ERROR` under load is the same shape
  smaller: the hardening parser refuses the report outright rather than certifying PIT's
  detected score around the hole. The refusal and `pitest<Suite>Debt` name every offending
  row — **retain every `RUN_ERROR` coordinate before a quiet re-run overwrites the
  report**, because the same coordinate surfacing twice is a defect in that mutant's
  covering test, not load. The daemon log
  (`~/.gradle/daemon/<version>/daemon-<pid>.out.log`) keeps a failed build's full output
  even when the shell discarded it — read it before calling a failure unexplained.
- Fuzz findings become a committed seed input **and** a named regression test, never just
  a fix — and every committed corpus is replayed inside `check` by a plugin-generated
  `<Harness>SeedReplayTest` (from `generateFuzzReplayTests`; fails on a missing or empty
  corpus), so a new seed replays automatically and the corpus cannot rot between fuzz
  runs. Seed provenance lives in the `src/test/resources/fuzz/README.md` next to each
  module's corpus directories.
- **Run fuzz campaigns explicitly and locally.** `fuzzAll` derives its task graph from
  every registered target, so it cannot drift from a hand-written workflow task list the
  way a `fuzz.yml` matrix can. It is a local release-checklist responsibility here: run
  `fuzzAll -PmaxFuzzTime=<seconds>` and record the budget you used before releasing. This
  repo deliberately has no scheduled GitHub fuzz workflow — such a workflow is optional
  exploration, never release evidence (see `HARDENING_NOTES.md`).
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
  against `handlers.*Test*`. 160 mutants (the canonical-routing contract of 2026-07-24
  added `PathCanonicalizer` and the `HandlerLookup.badRequest()` state); 1 accepted
  equivalent (triaged in `config/pitest/README.md`) and 3 timed-out (load-dependent loop
  conversions). Tests live in `HandlerUtilTests`, `HandlerMapTests` and
  `PathCanonicalizerTests`.
- `./gradlew :http-servers-core:pitestWiring` — PIT over `BaseHandlerWiring` (the handler-group
  include/exclude filter that decides which handlers get registered) against
  `BaseHandlerWiringTests`. 78 mutants, **100% killed**, empty baseline — keep it that way.
- `./gradlew :http-servers-core:pitestServer` — PIT over the `server` package except
  `BaseHandlerWiring` (owned by `pitestWiring`), against `server.*Test*`. Covers the
  builder's trailing-slash aliasing, method routing, controller snapshotting, the
  factory service lookup (probe-and-branch, see above) and registration logging
  (`BaseHttpServerBuilderTests`). 39 mutants, **100% killed**, empty baseline — keep it
  that way.
- `./gradlew :http-servers-core:pitestResponse` — PIT over the `response` package
  (`HttpResponse` factories and `withHeader` copy semantics, `HttpResponseTests`) and,
  since 2026-08-03, the `request` package alongside it: `Request` is all-abstract and
  contributes no mutants, but the ownership audit counts it as production code and this
  package's `QueryHandler` is its one production consumer. 9 mutants, **100% killed**,
  empty baseline — keep it that way.
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
`handlers.HandlerUtil`.

Routing canonicalizes before it matches: `HandlerMapImpl.lookupHandler` reduces the raw
request path through `PathCanonicalizer` (per-segment percent-decode, dot-segment
resolution, trailing slash preserved) and refuses ambiguous targets — malformed escapes,
escapes or literals introducing `/` `\` NUL, `%25` double-encoding, encoded dot segments,
empty segments, root-escaping `..` — as `HandlerLookup.badRequest()`, which every
controller answers with 400. The canonical form decides routing only; `Request.path()`
stays raw. `./gradlew :http-servers-core:fuzzPathCanonicalizer` runs a generative-oracle
harness (`PathCanonicalizerFuzz`): token streams whose expected canonical form is built
alongside, plus an arbitrary-bytes mode asserting never-throws and that accepted results
are rooted with no dot/empty/backslash/NUL/`%` segment. Seeds live under
`src/test/resources/fuzz/pathCanonicalizer` and are replayed by the generated
`PathCanonicalizerFuzzSeedReplayTest`.

`./gradlew :http-servers-core:fuzzHandlerUtil` runs a differential
harness (`HandlerUtilFuzz`): the hand-rolled boundary scanner against a naive split-based
reference, required to agree on every input — value, absence, integers, or exception class —
because since value decoding landed the parser is no longer just a splitter. Seeds live under
`src/test/resources/fuzz/handlerUtil` and are replayed by the generated
`HandlerUtilFuzzSeedReplayTest`.

### http-servers-sava — x402 payment gate (`software.sava.http_servers.sava.x402`)

The module's threat model is a client-controlled `X-PAYMENT` header (Base64 → JSON → a
partially-signed Solana transaction) that a facilitator would co-sign and submit. A parsing
defect or a verification rule the code fails to enforce is a payment the facilitator wrongly
sponsors, so this is the most heavily tested surface.

- `./gradlew :http-servers-sava:pitestX402` — PIT over the whole `x402` package (models, gate,
  verifier, settler, cache) against `x402.*Test*`. The `RpcTransactionSubmitter` inner class
  (thin adapter over `SolanaRpcClient`, exercised only against a live node) is excluded by
  hand; the `*Fuzz` harnesses need no glob — registered fuzz targets are auto-excluded. 424 mutants, 96% detected; the 13 baseline keys (14 rows) are all triaged
  equivalents with per-key reasons in `config/pitest/README.md` — chiefly guards whose
  removal funnels to the identical error response, and sub-states `TransactionSkeleton`'s
  asymmetric lazy resolution cannot produce (out-of-range program indices throw eagerly;
  account indices resolve to null; data lengths overrun silently — pinned by the
  corruption tests in `SvmExactVerifierTest`).
- `./gradlew :http-servers-sava:pitestHandlers` — PIT over `handlers.*` (public-key query
  params) against `handlers.*Test*`. 44 mutants, 88% detected; the 4 baseline keys (5 rows) are
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
`verify`. The `crash_*` inputs in the `svmVerify` corpus (replayed by the generated
`SvmExactVerifyFuzzSeedReplayTest`) and the corruption tests in `SvmExactVerifierTest`
(`unresolvableProgramIndexRejected`, `unresolvableAccountIndexRejected`,
`overrunningDataSliceRejected`) guard this.

### Adding a target

- **Mutation suite**: add `mutation.register("<name>") { targetClasses = ...; targetTests = ... }`
  to the module's `hardening {}` block. Exclude test helpers that live in the target
  package via `excludedClasses`; registered fuzz targets are auto-excluded from every
  suite (no hand-written `*Fuzz*` globs). The exclusion audit warns when a glob swallows
  a production class no sibling suite owns — either narrow the glob or record the
  decision with `declineExclusionAudit("<glob>", "<measured reason>")`.
- **Shared test scaffolding**: the plugin can generate six support classes
  (`hardening.generateTestSupport = true`; see sava-build's `HARDENING.md`) — Ports,
  RecordingExecutor, JulRecorder, LoopbackHttpServer, ManualScheduledExecutor,
  ConcurrencyHarness. Deliberately NOT adopted (evaluated 2026-07-24): the existing inline
  helpers are tiny, PIT-pinned, and in places intentionally different (the jdk
  `RecordingExecutor` bundles its own virtual-thread delegate; the inline JUL captures
  don't force levels or detach parent handlers). Flip it on the first time a test needs a
  raw-socket HTTP server (the escape hatch for "unreachable in-harness" transport
  acceptances), a deterministic scheduler, or a new recorder — instead of hand-rolling
  another copy — and migrate the inline helpers opportunistically, re-running the owning
  suites.
- **Fuzz harness**: write a class with `public static void fuzzerTestOneInput(byte[])` and no
  Jazzer imports (so it compiles with the regular test sources), then
  `fuzz.register("<name>") { targetClass = ...; maxLen = ...; seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/<name>") }`.
  For any structured format a `seedCorpus` of committed inputs is required — a from-scratch
  mutator cannot assemble a valid base64/JSON/transaction. The writable corpus accumulates in
  `build/fuzz/<name>-corpus`. When the fuzzer finds a crash, copy the reported `crash-*`
  artifact into the seed corpus as a named regression input and add a replay assertion.
