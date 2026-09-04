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

<!-- hardening-template block:start -->
- **Scale verification to the change.** Iterate with the module's `test`
  task; before handing off, run only the `pitest<Suite>`(s) whose mutated
  code the change can reach — including suites in dependent modules that
  call a changed API, and the owning suite for test-only edits (a weakened
  test is exactly what the ratchet catches). When the production-class inventory
  changes (add/remove/rename/move), or mutation target/exclusion rules change,
  also run the cheap whole-population
  `mutationOwnershipAudit` before handoff. The full `hardeningCertify` — every
  suite freshly observed, serialized, provenance-bound, diffed against
  `config/pitest/`, with strict timeout and ownership audits — is the pre-release
  check, owned by CI or by the release checklist (this repo records which); it is
  not the inner loop.
- A new unkilled mutant has exactly three legal outcomes: **kill it** with a
  test (prefer asserting the property it breaks over restating the
  implementation), **refactor** it out of existence, or **accept it** with a
  written reason in `config/pitest/README.md` **and a short family label on
  the row itself** — refreshes seed new rows `# untriaged`, and triage means
  replacing that label, so the baseline always says which rows are argued
  and which are debt. For an existing baseline, use `BaselineUnion` after
  reviewing the fresh rows: it appends them without deleting unmatched evidence.
  Reserve `BaselineUpdate` for a first seed or an independently reviewed complete
  rewrite; never run it just to make the build pass. A family label groups
  individually reviewed instances; it never authorizes the next syntactically
  similar mutant.
- **A mutant is a question, not a specification.** Before writing a killing
  test, state the externally intended property and an oracle independent of the
  current implementation: public contract, protocol specification, caller
  invariant, reference implementation, or domain rule. If it contradicts current
  behavior, first demonstrate the bug with a regression test that fails against
  the unmutated code, then fix production; never add a passing assertion that
  merely locks in the bug. At PR or handoff, report each nontrivial behavioral
  cluster — not each mutant — as `Property: ... | Oracle: ... | Outcome: missing
  assertion / production bug / accepted equivalent`. Test names and assertions
  normally carry the durable property; comment only when the oracle or unusual
  setup would otherwise be lost, and never embed PIT coordinates or line numbers.
- Baseline keys are line-less (`class,method,mutator,STATUS`) — editing
  above a mutated method churns nothing, and `# line` tags are review
  metadata. New or edited mutation-evidence prose should use line-less
  class/method/mutator identifiers rather than source line numbers. Existing prose
  is not a plugin-upgrade gate; repair a stale locator when ordinary review encounters
  it. The current PIT report and the row's `# line` tag are the sole transient locators.
  A new mutant replacing a
  killed one at the same key can inherit
  its acceptance, so treat a line-drift advisory whose written argument no
  longer fits the code as that swap until shown otherwise. After review, use
  `BaselineRetag` to refresh only matched line metadata while preserving every
  accepted row; never use an unrelated acceptance or deletion merely to clear
  the advisory. Use the installed plugin's named writer tasks and heed their
  candidate previews. Before `BaselinePrune` can delete, two distinct completed
  fresh full history-free previews must have the exact same candidate multiset;
  its own third fresh write-boundary run must match them too. Candidate drift is a
  reviewer-stop, and matching bytes do not replace review of the relevant
  solo/gate load context or each removal criterion. Every retained row remains
  active acceptance authority regardless of a `# retired`, `# refactor`, or other
  note. When a reviewed refactor removes the mutation site and the gate is already
  clear, finish the normal Prune protocol rather than leaving a purported
  non-authorizing history row; this is tightening the ratchet, not excusing fresh
  debt. Never hand-edit
  record structure or provenance stamps. A PIT, PIT-plugin/tool-artifact,
  ArcMutate-base, or certificate change uses `pitest<Suite>BaselineRebase`: it
  preserves every old row, seeds new rows `# untriaged`, and stamps the reviewed
  toolchain only after a successful fresh observation. That provenance binds the
  current transition and observation; it does not claim that every conservatively
  preserved row was generated by the new toolchain. Perform a schema
  migration/rollback only with a fleet pin plan. A `[history]` report may check
  the ratchet but cannot support adding, removing, or relabelling
  accepted/timeout records; run `pitest<Suite> -PnoMutationHistory` first.
- Consumer hardening notes should focus on local ownership, measurements, acceptance
  reasons, and provenance. Prefer a `hardeningHelp` pointer over a detailed copy of
  installed task behavior, but do not turn a plugin upgrade into a repository-wide
  prose migration. `AGENTS.md` carries this exact generated, digest-pinned template
  with repository-specific facts outside its bounded block. Use `hardeningHelp` and
  project-qualified `hardeningAgentTemplate` as the installed-version authorities,
  and run the matching read-only `hardeningAgentTemplateDiff` against its explicitly
  bounded block on every template-digest move before acknowledging the new marker.
- **Iterate with `-PmutateOnly=<class-glob>`** while killing a cluster —
  seconds instead of the full suite — then re-run unscoped with
  `-PnoMutationHistory` before any record decision; the tooling refuses to let
  a scoped report touch the baseline.
- Identical baseline rows are sibling mutants of one compound condition and
  the comparison is a multiset: never hand-dedupe. When one sibling
  survives, the verify names the killed sibling's test — the survivor is
  the opposite branch direction; triage it as its own mutant.
- **A survivor contradicted by an existing oracle may be contaminated evidence.**
  Open PIT's HTML **Covering tests** list, then compare the same scoped,
  history-free population with and without isolation:
  `-PmutateOnly=<class> -PnoMutationHistory`, then
  `-PmutateOnly=<class> -PisolateMutants`. An isolation-only kill points
  to state leaked between mutants — commonly a thread, executor, handler, or
  static fixture whose cleanup an earlier assertion failure skipped. Put
  teardown in `finally`/`try`-with-resources and rerun normally, history-free;
  isolated execution is diagnostic evidence, never a baseline decision.
- **Stubs and fixtures return distinguishable, non-default values.** A stub
  returning null/0/""/true/empty makes the matching return-value mutant
  equivalent by accident of the fixture — the clock non-zero-origin rule
  generalized to every stubbed return.
- **Copy-on-write clusters split by direction.** Assert immutability of
  returned collections (`assertThrows(UnsupportedOperationException, ...)`)
  at every size: the mutable-escape direction is a kill, not an acceptance;
  only the content-equal siblings are family-accepted equivalents.
- **Randomized tests use fixed seeds, and never sleep**: the ratchet needs
  deterministic kills, and PIT re-runs the suite per mutant, so one real wait
  costs minutes. Exploration belongs to the fuzz targets.
- **Do not rely on PIT's timeout to detect a mutant.** `TIMED_OUT` counts as
  detected and is not written to the baseline, but it proves only watchdog
  detection. Load can change the observed status and line-less keys can conflate
  siblings. Verify a baseline in both modes; for measured load-flip insurance,
  union only rows observed to flip, never every `TIMED_OUT` row. This does not
  restrict additive `BaselineUnion` acceptance of separately reviewed fresh debt.
- **A new timed-out mutant is a reviewer-stop, not detection noise.** A timeout
  can mask a weakened assertion; audit a set, not a count. **Record.**
  `config/pitest/<suite>-timeouts.csv` holds line-less
  `class,method,mutator` keys and a cause; `# line` is diagnostic, while
  `config/pitest/README.md` records the full cause. Verification warns on outside
  timeouts and stale members. `pitest<Suite>Debt` previews the pre-PIT
  file check. `TimeoutAuditInit` seeds an uncertifiable file: classify every row.
  **Classify.** Only `cause:liveness` certifies: after deterministic seams and
  budgets, the mutated path has no path-owned finite completion. A fixture's
  emergency exit does not demote that loss; record its bound. A bound claimed
  as the deterministic oracle must beat PIT's
  `duration × timeoutFactor + timeoutConst`; otherwise shorten it and re-observe
  history-free — it contributes no cause evidence. A later emergency
  ceiling cannot prove liveness.
  A straight-line path without a loop, retry, lock, wait, blocking call, or external
  completion dependency is not credible liveness evidence. Prove the mutated path
  receives the test clock/budget and check for a synchronous state reader; a
  collaborator's `TestClock` cannot observe a system clock.
  Missing/unknown causes, `cause:untriaged`, finite `cause:resource`, and
  `cause:harness` are reviewer-stops; harness records a finite covering-path/watchdog
  race without authorizing it. Resource behavior needs its promised contract test/fix
  or a stable `SURVIVED` equivalence argument. Liveness authorizes `TIMED_OUT`, never
  `MEMORY_ERROR`: for a non-advancing loop racing the heap, make every covering path
  fail deterministically without relying on PIT test order, or refactor out the
  mutation site.
  **Disambiguate.** A cause covers every `TIMED_OUT` sibling under its key. A finite
  sibling observed `KILLED` or another valid non-timeout does not itself create
  mixed timeout causes, but a key
  cannot certify when trustworthy fresh evidence shows distinct same-key siblings
  timing out under different cause categories. One later `KILLED` does not erase that
  conflict; `KILLED`↔`TIMED_OUT` movement alone does not prove it. Repair the finite
  path and establish repeated fresh history-free non-timeout observations under
  solo/gate load, or split/refactor/eliminate the site. Multiplicity drift prints
  all current line-full candidates, but lines cannot define identity: moving imports,
  adding a method, or reflowing code never warns, fails, or requires re-anchoring.
  **Retire.** Remove an admissible liveness member only after the tool reports 3+
  distinct fresh full-run quiet observations over identical execution inputs,
  confirmed under solo/gate load. When retirement semantics are unchanged, a plugin
  fingerprint change alone does not reset this advisory; captured PIT-input changes
  do, and unmodeled semantic changes require a timeout-quiet format bump. A
  finite `KILLED`↔`TIMED_OUT` race never certifies: repair it instead of waiting on
  liveness retirement. The quiet stash is a machine-local nomination; never copy or
  merge it, and retain the row without same-input gate confirmation. Assisted
  reports are previews and advance neither timeout status nor quiet-run evidence.
- **A flaky harness is worse than recorded debt.** If an interleaving or a
  boundary cannot be made deterministic, accept the mutant with a written
  reason rather than chasing it with sleeps or spin-waits.
- **A suite's percentage is not a target.** An accepted mutant with a written
  reason is finished work, not debt. Before trying to raise a number, check
  whether the remainder is `NO_COVERAGE` (real work) or documented
  equivalents (already closed).
- **Allocation and timing harnesses are a last resort for thin constant-factor
  differences**, reserved for properties that are a stated design goal. A
  removed growth/capacity/amortisation guard that changes complexity class is
  not “allocation-size only”: use a small input with an orders-of-magnitude
  margin and the correct path through the mutated code. Harnesses re-run once
  per mutant, need a `volatile` sink so escape analysis cannot delete what they
  measure, and flap when the margin is thin.
- When a test you believe in will not go green, **suspect the code before you
  soften the assertion** — that is where this process finds real bugs.
- **A wandering unkilled count is a defect, not noise** — chase it before
  changing any baseline. Reproduce it under the relevant solo/gate loads,
  inspect per-mutant coordinates, remove real waits, and move construction
  coverage into the test body before deciding whether it is a product defect,
  a load-dependent timeout, or a harness defect.
- **Build the subject under test inside the test body, not in a field.**
  Under `PER_CLASS` lifecycle a field-initialized client's construction
  coverage attaches to whichever test runs first, so wiring mutants can
  never pair with the test that drives what they wire — they survive even
  under a harness that asserts every request. One test that constructs the
  client in the test method and drives each configured URL restores the
  pairing.
- **Kill rates are bounded by the mutator set.** `BigInteger`/`BigDecimal`
  arithmetic and receiver-returning fluent calls can be invisible to the
  enabled defaults. Follow the plugin's trial advice per suite, enable only
  mutators proved to fire, and record the measured numbers and declines.
- Module-path and mutation-test service discovery can differ. Declare real
  services in every runtime representation the project supports, probe the
  active environment in test-only scaffolding, and never commit a harness
  whose pass/fail result depends on which task launched it.
- `SURVIVED` and `NO_COVERAGE` are different problems: the first is a
  judgment call about equivalence, the second is usually an untested line
  and is mechanical. Never accept a `NO_COVERAGE` mutant as "equivalent" —
  you have not observed its behaviour. One structural exception: a block
  that always exits by throw reads `NO_COVERAGE` forever, executed or not
  (PIT probes a block at its end), and its return-value mutants can never
  change status. Such a line is owed a test asserting the throw's contract,
  not coverage — and never leave one untested fearing a covered-line
  `SURVIVED` conversion, which would require the block to complete.
- Exclusions must cover the **test source set**, not a naming convention:
  shared fakes are named `RecordingFoo` / `StubFoo` and match no `*Test*`
  pattern. After registering or widening a suite, list the mutated classes and
  confirm none live under `src/test`.
- **Verify by the absence of failures, not the presence of passes.** Counting
  `PASSED` lines hides a failure sitting next to them, and a green
  `clean build` can mean the build cache short-circuited rather than that
  tests ran. Check the failure count and confirm the task actually executed.
  A mutation run has a second version of this: PIT writes reports incrementally,
  so a failed run can otherwise look complete. The plugin clears known
  decision-grade leaves before each attempt, writes `.running` until clean
  completion, and retains unfiltered `pitest.stdout.log` / `pitest.stderr.log`
  beside the selected report. Trust the exit code and sentinel, not a summary
  from a failed attempt. Use `pitest<Suite>Diagnostic` for isolated
  `VERBOSE_NO_SPINNER`, history-free investigation; its report and raw logs are
  machine-local diagnostic output, may contain sensitive test/process details,
  and can never support a record or certification decision.
- **A suite that got faster without getting narrower is a bug report.** Real
  speedups come from fewer mutants or faster covering tests; an unexplained
  one usually means the run did less than you think. Read the task's evidence
  markers and scope; only a fresh full certification may support a release.
  The process itself needs no ArcMutate licence and applies to any Java package.
- **Invalid execution outcomes are not results.** PIT `MINION_DIED` fails
  before writing a report, so it cannot corrupt one — re-run the suite; a
  Gradle-worker `EOFException` death is the same shape, and a per-mutant
  `RUN_ERROR` often first observed in a multi-suite run is the same
  shape smaller (load average itself proves nothing; the hardening parser refuses
  the report rather than certifying PIT's detected score). The refusal and
  `pitest<Suite>Debt` name every offending row; retain the coordinate before a
  quiet re-run replaces the report. `RUN_ERROR` alone diagnoses neither load nor
  memory and never justifies changing threads or heap; record load/RSS as context,
  retry once quietly, and tune only when PIT explicitly diagnoses a process-resource
  failure. Recurrence localizes a repeatable observation, not its cause: stable
  mutation-unit partition can report an aggregate-contention minion death at the same
  coordinate repeatedly. Compare fresh history-free full attempts with
  `-PmutateOnly=<class> -PnoMutationHistory`; a reliable scoped kill points away from
  the mutant alone without proving load, while a scoped batched/`-PisolateMutants`
  difference says the mutation-unit boundary matters — inspect leaked state first,
  then packing/process overhead. Run `pitest<Suite>Diagnostic` full and scoped when
  per-process progress is missing; its separate raw streams establish no total order,
  and the last announced mutation is context, not cause. Only a clean fresh full
  unscoped run can support records or certification. Such a later clean run (or a
  successful `hardeningCertify`) is sufficient closure for a non-recurring invalid
  outcome: it does not diagnose that failure, and the invalid attempt creates no
  mutation-record debt. If certification was interrupted, retry the affected
  project's whole `hardeningCertify`; its receipt deliberately re-executes every
  suite in that project in one invocation rather than stitching attempts, while
  other project receipts remain independent.
  The daemon log
  (`~/.gradle/daemon/<version>/daemon-<pid>.out.log`) keeps a failed build's
  full output even when the shell discarded it — read it before calling a
  failure unexplained.
- Fuzz findings become a committed seed input **and** a named regression
  test, never just a fix — and the committed corpus is replayed by a unit
  test inside `check`, so it cannot rot between fuzz runs.
- **Run fuzz campaigns explicitly and locally.** `fuzzAll` is derived from every
  registered target, so it cannot drift from a hand-written workflow task list;
  set and record `-PmaxFuzzTime=<seconds>` and
  `-PmaxParallelFuzzTargets=<count>` before release. Scheduled GitHub fuzz
  workflows are optional and are not release evidence.
- **When one thing has two representations, fuzz the differential.** Two
  parsers for one config, an encode/decode round trip, a fast path beside a
  reference path: assert the two *agree* rather than that neither crashes.
  Crash-only fuzzing cannot see a wrong answer.
- **Time-dependent code takes a clock**, so tests advance time instead of
  waiting. Give test clocks a non-zero origin — a clock starting at 0 makes
  every "start timestamp mutated to 0" mutant equivalent by accident.
<!-- hardening-template block:end -->
<!-- hardening-template sha256:714041431f01 -->

CI owns `check`; the local release checklist owns `hardeningCertifyAll` and
the explicit `fuzzAll` campaign. Complete certification means six project
receipts covering twelve suites with no `.running` sentinel. The fuzz campaign
must record both its time budget and parallel-target limit and exercise all five
registered targets: `formatPlaceholders`, `pathCanonicalizer`, `handlerUtil`,
`svmVerify`, and `x402Payload`.

The `hardening-template` marker above is checked by `agentsTemplateInSync` (wired into
`check`): when sava-build's agent-instructions template changes, the build fails until this
block is re-diffed against it and the marker updated to the digest the failure prints. Sync
or **act on** each changed bullet before updating the marker — a new requirement may mean
new code, not just new prose.

### http-servers-core — request routing (`software.sava.http_servers.core.handlers`)

The first code to touch every untrusted request: query-string parsing (`HandlerUtil`) and
method/path resolution (`HandlerMapImpl`, `HandlerLookup`).

- `./gradlew :http-servers-core:pitestHandlers` — PIT over the `handlers` package (wildcard)
  against `handlers.*Test*`. 143 mutants (the canonical-routing contract of 2026-07-24
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
  (`BaseHttpServerBuilderTests`). 40 mutants, **100% killed**, empty baseline — keep it
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
  params) against `handlers.*Test*`. 37 mutants, 86% detected; the 4 baseline keys (5 rows) are
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
