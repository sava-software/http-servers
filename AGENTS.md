# http-servers

Java 25 multi-module library providing a small HTTP server abstraction (`http-servers-core`)
with pluggable backends (`http-servers-jdk`, `http-servers-jetty`, `http-servers-helidon`,
`http-servers-netty`),
a demo module (`http-servers-hello`), an x402 payment gate for the Solana `exact` scheme
(`http-servers-sava`), and an unpublished soak harness (`http-servers-soak`). Built with the
shared `software.sava.build` Gradle plugin (same plugin family as the `sava` repo); the
`hardening` convention plugin provides PIT mutation testing and Jazzer fuzzing.

## Testing

`./gradlew build` compiles and runs the JUnit 5 suites for every module. The security-relevant
surfaces additionally carry PIT mutation suites and Jazzer fuzz harnesses, configured through
the `hardening {}` block in each module's `build.gradle.kts`.

## Quality gate & mutation ratchet

<!-- hardening-template block:start -->
- Work with the module's `test` task. The mutation suites are a final gate, run once
  per unpushed range when the work is complete and reviewed, before the push: each
  `pitest<Suite>` whose mutated code the range can reach, including suites in
  dependent modules, plus `mutationOwnershipAudit` when production classes or
  target/exclusion rules changed. Never per commit, amend or review round; a change
  the gate forces goes back through review as a delta. `hardeningCertify` (or
  `:hardeningCertifyAll`) and `fuzzAll` are the pre-release checks this repo's notes
  assign an owner to.
- Test-only edits owe the suites whose mutated code those tests exercise; when scope
  is unclear, run `./gradlew qualityGate` from the Gradle root. Doc and comment
  edits owe no suite; a build-script edit only when it changes what
  PIT is given. A change the gate forced owes it again by the same reachability rule
  once reviewed. `pitest<Suite>Verify` answers one way: it keeps its report while only
  recompiled Java sources changed and every recompiled class is byte-identical, which
  proves that suite is owed nothing; a refusal (a moved line, a resource, a build
  script, `src/main/java` under ArcMutate) names its cause and proves nothing by itself.
- When the gate reports unkilled mutants, iterate on one cluster with
  `-PmutateOnly=<class-glob>`. Before any record decision, re-run unscoped with
  `-PnoMutationHistory`: a `[history]` report cannot support adding, removing, or
  relabelling records.
- An unkilled mutant has three outcomes: kill it with a test that asserts the
  property it breaks, refactor it out of existence, or accept it with a written
  reason in `config/pitest/README.md` and a family label on the row. Refreshes seed
  rows `# untriaged`; triage replaces that label. Never accept a `NO_COVERAGE`
  mutant as equivalent; it is an untested line.
- A mutant is a question, not a specification. State the intended property and an
  oracle independent of the implementation before writing the killing test. If they
  contradict current behaviour, prove the bug with a failing regression test first,
  then fix production; never lock a bug in with a passing assertion.
- Write records only through the installed writer tasks: `BaselineUnion` adds
  reviewed rows, `BaselineRetag` refreshes `# line` metadata, `BaselinePrune` deletes
  only after two matching fresh history-free previews, `BaselineUpdate` is for a
  first seed or a reviewed complete rewrite, and `pitest<Suite>BaselineRebase`
  follows a PIT, PIT-plugin/tool-artifact, ArcMutate-base, or certificate change.
  Never hand-edit baseline
  rows or provenance stamps.
- Baseline keys are line-less (`class,method,mutator,STATUS`); `# line` tags are
  review metadata that belong to their row: `BaselineRetag` refreshes them, a hand
  edit is a hand-edited row. Identical rows are sibling mutants and the comparison
  is a multiset: never hand-dedupe.
- `config/pitest/README.md` holds the arguments in force, each updated in place and
  never appended to as a pass report: a family's members, reason, oracle and the
  condition that invalidates it; an audited timeout's cause. The totals the build prints
  are not restated there; the measurements it cannot reconstruct are kept.
- A new `TIMED_OUT` mutant is a reviewer stop, never detection. Record it in
  `config/pitest/<suite>-timeouts.csv` with a cause and argue it in the README; only
  `cause:liveness` certifies. A member whose coordinate has left the population is
  removed by hand after one fresh history-free run with valid committed provenance
  omits it; while provenance is invalid, repair or rebase it first.
- Tests are deterministic: fixed seeds, no sleeps, a clock with a non-zero origin,
  stubs that return distinguishable non-default values, and the subject built inside
  the test body. Exclusions must cover the test source set, not a naming convention.
- Verify by the absence of failures: trust the exit code and the `.running`
  sentinel, not a summary. `MINION_DIED` and `RUN_ERROR` are not results; re-run once
  on a quiet machine. A suite that got faster without getting narrower is a bug report.
- Fuzz findings become a committed seed input and a named regression test. Run
  `fuzzAll` locally with an explicit `-PmaxFuzzTime` and `-PmaxParallelFuzzTargets`
  before a release. Where one thing has two representations, fuzz the differential.
- `./gradlew :module:hardeningHelp` lists the installed tasks and options;
  sava-build's HARDENING.md holds the argument behind every rule above.
<!-- hardening-template block:end -->

CI owns `check`; the local release checklist owns `hardeningCertifyAll` and
the explicit `fuzzAll` campaign. Complete certification means seven project
receipts covering twelve suites with no `.running` sentinel. The fuzz campaign
must record both its time budget and parallel-target limit and exercise all five
registered targets: `formatPlaceholders`, `pathCanonicalizer`, `handlerUtil`,
`svmVerify`, and `x402Payload`.

`http-servers-soak` sits outside these gates. It is an operational tool — a long-running target
server and a load generator, run through `./http-servers-soak/soak.sh` (see the README's "Soak
testing") — with no unit oracle to mutate against, for the same reason a load generator is never
a PIT target. It applies no `hardening` plugin, registers no suite, is not published, and is not
one of the seven receipts above. A run's `summary.md` is an operational record, not a
certification input; a defect it exposes is fixed and pinned in the owning backend's dispatch
suite, never in the harness.

Nothing checks the block between the `hardening-template` markers above against the
installed plugin. On every sava-build upgrade, re-take it whole from
`./gradlew --no-daemon -q :http-servers-core:hardeningAgentTemplate` when the printed block
changed, and **act on** each changed bullet before pasting — a new requirement may mean new
code, not just new prose.

### http-servers-core — request routing (`software.sava.http_servers.core.handlers`)

The first code to touch every untrusted request: query-string parsing (`HandlerUtil`) and
method/path resolution (`HandlerMapImpl`, `HandlerLookup`).

- `./gradlew :http-servers-core:pitestHandlers` — PIT over the `handlers` package (wildcard)
  against `handlers.*Test*`. 143 mutants (the canonical-routing contract of 2026-07-24
  added `PathCanonicalizer` and the `HandlerLookup.badRequest()` state); 1 accepted
  equivalent (triaged in `config/pitest/README.md`) and 3 timed-out (load-dependent loop
  conversions). Tests live in `HandlerUtilTests`, `HandlerMapTests`,
  `PathCanonicalizerTests` and, since 2026-10-14, `CorsRequestTests`:
  `CorsRequest` holds the CORS rule the Jetty, Helidon and Netty adapters share
  (a blank `Origin` names no origin; a pre-flight is an `OPTIONS` naming an origin
  and a non-blank requested method, and routes to that method), so its mutants
  are killed here and the adapters' socket suites pin only that they ask it.
  Recount the population at the next gate.
- `./gradlew :http-servers-core:pitestWiring` — PIT over `BaseHandlerWiring` (the handler-group
  include/exclude filter that decides which handlers get registered) against
  `BaseHandlerWiringTests`. 70 mutants, **100% killed**, empty baseline — keep it that way.
- `./gradlew :http-servers-core:pitestServer` — PIT over the `server` package except
  `BaseHandlerWiring` (owned by `pitestWiring`), against `server.*Test*`. Covers the
  builder's trailing-slash aliasing, method routing, controller snapshotting, the
  factory service lookup (probe-and-branch; see "Two class-path worlds" in
  `HARDENING_NOTES.md`) and registration logging (`BaseHttpServerBuilderTests`). 40
  mutants, **100% killed**, empty baseline — keep it that way.
- `./gradlew :http-servers-core:pitestResponse` — PIT over the `response` package
  (`HttpResponse` factories and `withHeader` copy semantics, `HttpResponseTests`) and,
  since 2026-08-03, the `request` package alongside it: `Request` is all-abstract and
  contributes no mutants, but the ownership audit counts it as production code and this
  package's `QueryHandler` is its one production consumer. 14 mutants, **100% killed**,
  empty baseline — keep it that way.
- `./gradlew :http-servers-core:pitestLogging` — PIT over `BaseJulLogger` against
  `logging.*Test*`. The placeholder formatter and `stringify` are package-private and
  asserted directly; emission and caller resolution are asserted through a capturing JUL
  handler (`BaseJulLoggerTests`). 55 mutants; 5 accepted equivalents, 1 timed-out.

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

### http-servers-helidon — Helidon backend (`software.sava.http_servers.helidon`)

Helidon WebServer 4.5.5 behind the shared `HandlerMap`, reached from one catch-all route so
Helidon's own routing never decides a match. Versions come from `solana-version-catalog`
(25.30.21 and later).

- `./gradlew :http-servers-helidon:pitestDispatch` — PIT over the whole `helidon` package
  (wildcard) against `helidon.*Test*`, with `EXPERIMENTAL_NAKED_RECEIVER` added to `STRONGER`
  because `ServerResponse.status`/`header` and `WebServerConfig.Builder` are fluent, so a dropped
  header or listener setting is invisible to `VoidMethodCallMutator` (trial 2026-09-12: 13
  generated, 13 killed). 77 mutants before the pre-flight conditionals moved to core's
  `CorsRequest` on 2026-10-14 (recount at the next gate), **100% killed**, no accepted baseline file and an empty,
  armed `dispatch-timeouts.csv` — keep both that way. The covering tests are real socket round
  trips in `HelidonConformanceTest` and `HelidonPostHandlerTest`.

The suite carries no socket-wait timeout family, unlike the jdk dispatch suite: a
controller or handler that returns without completing the response is answered 500 by Helidon's
own routing, so a dropped `send()` is a status mismatch the covering test sees at once rather
than a client blocked on a read.

Three adapter contracts the ratchet cannot infer are pinned by name: `initRestServer` returns an
unstarted `WebServerConfig.Builder` because a Helidon `WebServer` cannot be restarted once
stopped, so `HelidonHttpServer` builds it inside `start()` and refuses a second start
(`secondStartThrowsAndTheRunningServerIsUndisturbed`); `WebServer.start()` logs a bind failure
instead of throwing, so the adapter converts a not-running server into an `IOException` naming
the address (`startOnAnOccupiedPortThrows`); and `ResponseUtil` owns the bodyless set {204, 205,
304}, which is Helidon's no-entity set rather than the 204/304 the other backends use
(`bodylessStatusesDropAnAttachedBody`). Helidon's HTTP/1.0 `505` is pinned as a refusal by
`http10RequestIsAnswered`, which is also the guard on the H2C decision recorded in
`build.gradle.kts`: with `helidon-webserver-http2` on the path that clean 505 becomes no answer
at all.

### http-servers-netty — Netty backend (`software.sava.http_servers.netty`)

Netty 4.2.19.Final behind the shared `HandlerMap`. Each connection's pipeline is `HttpServerCodec` →
`NettyRequestGate` → `NettyRequestAggregator` → `NettyController`: the gate owns response
ordering, connection persistence and the idle timeout (one request in flight per connection,
reads paused while a complete request awaits its answer, `Connection: close` decided from both
the request and the response — framed for the request's version, or as HTTP/1.1 for a head the
codec could not parse, so the close its `400` carries is signalled whatever version the codec
invented for an unparsable line — and a connection that is neither carrying a fully received request
awaiting its response nor making progress for 30 s — measured on an injected clock from the later
of its last decoded read and its last completed response, so a stalled request body is idle and a
request awaiting its handler never is — closed by the gate's own scheduled check), and refuses
expectations itself — an unsupported `Expect` with `417`, an `Expect: 100-continue` announcing a
body over the limit with `413` and a close — the moment the codec hands over the head, answering
in request order: a refusal resets the codec (Netty's `HttpExpectationFailedEvent`), which is
right only while the codec still stands on that head, so the decision cannot wait for the
request's turn even though its answer does. The aggregator keeps the `100 Continue` and the
oversized-body `413`, both ordered behind the gate by construction, and no `IdleStateHandler` or
`netty-handler` dependency is needed. Versions come from `solana-version-catalog` (25.30.21 and
later).

- `./gradlew :http-servers-netty:pitestDispatch` — PIT over the whole `netty` package (wildcard)
  against `netty.*Test*`, with `EXPERIMENTAL_NAKED_RECEIVER` added to `STRONGER` because
  `HttpHeaders.set`, `ChannelPipeline.addLast`, `ChannelConfig.setAutoRead` and the
  `ServerBootstrap` chain all return their receiver. 192 mutants before the pre-flight conditionals
  moved to core's `CorsRequest` on 2026-10-14 (recount at the next gate), **100% killed**, no accepted
  baseline file and an empty, armed `dispatch-timeouts.csv` — keep it that way. The covering
  tests are real socket round trips in `NettyConformanceTest` and `NettyPostHandlerTest`, plus
  `NettyPipelineTest`, which drives the real channel initializer on an `EmbeddedChannel` so
  ordering, flow control, reference counts, framing, the expectation refusals and the idle
  timeout are asserted in process — the last on a fake clock that is the embedded loop's ticker (origin 10^12 ns) and,
  offset to a negative origin as `System.nanoTime` may be, the gate's clock, advanced by the
  test rather than waited on; the shipped 30 s default is what those cases measure against,
  and is pinned by name in the socket suite.

The former `# backpressure` accepted row (`NettyController.channelRead0`'s read pause) was
retired through the Prune protocol when ordering moved into `NettyRequestGate`, where the same
shape is killed by the in-process `isAutoRead()` assertions. The reasoning, and the sites
refactored out rather than accepted, are in `config/pitest/README.md`.

This suite's fixtures bound every `HttpClient` request and raw socket at 2 s, as Jetty's have since
2026-10-11, where the JDK and Helidon suites use 10 s, and that bound is load-bearing for the
records: the first pass at 10 s read 31 `TIMED_OUT` instances over 24 keys, every one a dropped
response write, and a 10 s bound can never fire inside PIT's own margin, so detection was the
watchdog rather than an assertion. At 2 s each of those reads `KILLED`. Do not restore the 10 s
convention without re-auditing the timeouts file. A history-assisted run on a machine whose
`.pitest-history/` predates the retiming replays the old `TIMED_OUT` verdicts; run
`-PnoMutationHistory` before any record decision. The suite's one real-time case is
`anIdleConnectionIsClosedAfterTheIdleTimeout` (a 200 ms idle timeout through the builder knob, EOF
read under the 2 s bound, and a causal lower bound on the elapsed time); `config/pitest/README.md`
records its bound against PIT's margin, and it is now the suite's slowest covering test. The idle
rule's oracle is split and recorded there: the JDK and Jetty both close a silent connection between
requests; Jetty alone fails a stalled request body (the JDK holds it for good); neither interrupts a
busy handler.

### http-servers-sava — x402 payment gate (`software.sava.http_servers.sava.x402`)

The module's threat model is a client-controlled `X-PAYMENT` header (Base64 → JSON → a
partially-signed Solana transaction) that a facilitator would co-sign and submit. A parsing
defect or a verification rule the code fails to enforce is a payment the facilitator wrongly
sponsors, so this is the most heavily tested surface.

- `./gradlew :http-servers-sava:pitestX402` — PIT over the whole `x402` package (models, gate,
  verifier, settler, cache) against `x402.*Test*`. The `RpcTransactionSubmitter` inner class
  (thin adapter over `SolanaRpcClient`, exercised only against a live node) is excluded by
  hand; the `*Fuzz` harnesses need no glob — registered fuzz targets are auto-excluded.
  387 mutants and 97% detected under PIT 1.30.0; the 21.5.30 provenance rebase and
  guarded retirement leave 10 active baseline rows with per-family reasons in
  `config/pitest/README.md`.
  The live remainder is chiefly guards whose removal funnels to the identical error
  response and sub-states `TransactionSkeleton`'s asymmetric lazy resolution cannot
  produce (out-of-range program indices throw eagerly; account indices resolve to null;
  data lengths overrun silently — pinned by the corruption tests in
  `SvmExactVerifierTest`).
- `./gradlew :http-servers-sava:pitestHandlers` — PIT over `handlers.*` (public-key query
  params) against `handlers.*Test*`. 37 mutants, 86% detected; the 5 baseline rows (2 line-less keys) are
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
