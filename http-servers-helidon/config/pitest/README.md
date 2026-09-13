# Mutation hardening evidence

This file contains repository-specific evidence and decisions only. Run
`./gradlew :http-servers-helidon:hardeningHelp` for the exact mechanics installed in this checkout,
and `./gradlew :http-servers-helidon:hardeningAgentTemplate` for the version-matched agent contract.
The portable decision policy lives in sava-build's `HARDENING.md`.
Keep all prose and inline, fenced, or tabular coordinate rosters source-line-free;
retain line-less class/method/mutator evidence and meaningful multiplicity as `xN`
(typographic `×N` is equivalent).

Never run a baseline-writer task just to make the build pass: kill the mutant,
refactor it out of existence, or record its equivalence reason below.

## dispatch suite — no accepted mutants (seeded 2026-09-12)

Registered with the module: `HelidonController` routing (400/404/405, CORS
pre-flight and origin reflection, the 500 funnel), `HelidonRequest` /
`ResponseUtil` bridging, `HelidonHttpServer`'s build-in-`start()` lifecycle and
`HelidonServerBuilder` wiring, mutated as the package wildcard and killed
through real socket round trips (`HelidonConformanceTest`,
`HelidonPostHandlerTest`). 8 production classes, all owned by this suite
(`mutationOwnershipAudit`: 8 owned, 0 declined).

First seed: **73 mutants, 73 killed (100%)**, 0 `SURVIVED`, 0 `NO_COVERAGE`,
0 `TIMED_OUT`, 0 `RUN_ERROR`, observed identically in three fresh history-free
full runs on 2026-09-12 (`pitestDispatch -PnoMutationHistory`, the
`BaselineUpdate` seed and the `TimeoutAuditInit` attempt) under solo load
averages of 22–88 (unrelated processes; nothing this module ran). PIT
spends ~10 s in mutation analysis. `pitestDispatchBaselineUpdate` therefore
wrote no accepted baseline ("nothing unkilled — no baseline to write"); keep it
that way.

Review-application pass (2026-09-12): the fixes and added tests below grew the
population to **77 mutants, 77 killed (100%)**, still 0 `SURVIVED` / `NO_COVERAGE`
/ `TIMED_OUT` / `RUN_ERROR` (line coverage 128/131 — the three uncovered lines
are `ResponseUtil`'s and `HelidonBuilderFactory`'s private/trivial members that
generate no mutant), observed history-free (`pitestDispatch -PnoMutationHistory`)
under load ~16. `pitestDispatchVerify` passes against the still-empty baseline. Provenance: `pitestDispatchBaselineRebase` (its own fresh
history-free 73/73 observation, retained all 0 rows) stamped
`dispatch-pitest-version` and `dispatch-pitest-toolchain.tsv` on 2026-09-12 —
PIT 1.30.0, ArcMutate Base 1.7.2, byte-identical to the jdk suite's stamp — so
the empty record is bound to the toolchain that observed it rather than
reading as legacy-unbound.

The suite runs `STRONGER,EXPERIMENTAL_NAKED_RECEIVER`. Helidon's
`ServerResponse.status`/`header` and `WebServerConfig.Builder` are fluent, so
`VoidMethodCallMutator` never fires on a header write or a listener setting;
`pitestMutatorTrial -PtrialMutators=EXPERIMENTAL_NAKED_RECEIVER` on 2026-09-12
measured **13 generated, 13 killed by existing tests, 0 unkilled**
(`NakedReceiverMutator` x13) — the dropped `Allow`, `Content-Type`, CORS and
custom-header writes, the `status(...)` call, and the `shutdownGracePeriod` /
`shutdownHook` listener settings are all expressible and all pinned.

### Behavioral clusters closed while seeding

- `ResponseUtil.writeResponse`, the `204 || 304` compound condition
  (`RemoveConditionalMutator_EQUAL_ELSE`, one sibling per status). The
  first history-assisted run left the second sibling `SURVIVED`: with the
  empty body the conformance case uses, `send(new byte[0])` and `send()` are
  wire-identical on Helidon (both answer with `Content-Length: 0` and no
  content), so the branch could not be told apart. **Property:** the adapter,
  not the handler, owns the bodyless-status contract — a 204 or 304 crosses
  the wire without content even when the handler attached some. **Oracle:**
  RFC 9110 sections 15.3.5 and 15.4.5; on Helidon the alternative is a refused
  entity (500) or content that desyncs the next keep-alive response.
  **Outcome:** missing assertion — `bodylessStatusesDropAnAttachedBody` reads
  the raw socket with `Connection: close` and pins an empty byte tail and a
  `Content-Length` that is absent or `0`; both siblings now read `KILLED`.
- The 500 funnel's framing (`HelidonController.handle` catch): a handler whose
  `HttpResponse` carries a value Helidon refuses to write (a CR/LF in a header)
  throws inside `send()` after Helidon has stamped the failed body's
  `Content-Length` on the response; Helidon only fills in a *missing* length, so
  the 500 would otherwise declare that stale length over its own 41-byte JSON
  body and desync a keep-alive connection. **Property:** a 500 declares the
  length of the body it actually sends. **Oracle:** RFC 9112 §6.3. **Outcome:**
  production fix (`response.headers().clear()` before re-framing) plus a
  regression that fails against the unfixed adapter
  (`handlerResponseThatCannotBeWrittenStillFramesTheAnswer`, read over a
  persistent socket so the desync is observable and the next request proves the
  connection stayed in sync). The `NakedReceiver`/`VoidMethodCall` on the new
  `headers().clear()` is killed by that test (drop it and the stale length
  returns). An `isSent()` guard was considered and rejected: its true branch is
  reachable only on a mid-send socket failure, which no deterministic test can
  produce, so it read as an equivalent survivor — refactored out rather than
  accepted, keeping the empty baseline.
- The bodyless set widened to 205 (`ResponseUtil.writeResponse`, the
  `204 || 205 || 304` compound): Helidon's no-entity set is {204, 205, 304}, so a
  205 with a body reaches `send()` and Helidon answers 500 where the other
  backends send the body. **Property:** the adapter, not the handler, owns the
  bodyless contract for every status Helidon treats as no-entity. **Oracle:**
  RFC 9110 §15.3.6. **Outcome:** production fix (add 205 to the guard) plus 205
  added to `noContentAndNotModifiedCrossTheWireWithoutABody` and
  `bodylessStatusesDropAnAttachedBody`; the three `RemoveConditional` siblings on
  the widened condition are all killed.
- Everything else was killed by the mirrored conformance suite, including the
  HTTP/1.0 505 refusal (`http10RequestIsAnswered`, raw socket — also the guard on
  the H2C dependency decision), the executor-ignored divergence
  (`theSuppliedExecutorReceivesNoWork`, a recording executor asserted at zero
  dispatches with the handler observed on a Helidon-owned virtual thread), the
  bare-`?`-to-null mapping (`bareQuestionMarkYieldsANullQuery`) and the
  keep-alive-not-desynced property that makes the HEAD and bodyless divergences
  safe (`keepAliveConnectionIsNotDesynced`), and the two lifecycle rows the other
  backends do not have:
  `HelidonHttpServer.start`'s double-start guard
  (`secondStartThrowsAndTheRunningServerIsUndisturbed`: an `IllegalStateException`,
  not a second bind attempt) and its `isRunning()` check
  (`startOnAnOccupiedPortThrows`: Helidon logs a bind failure and returns
  not-running, so dropping the check reports success on a dead server).

### Audited timeouts (`dispatch-timeouts.csv`)

**The set is empty and armed — this suite has never timed out.**
`pitestDispatchTimeoutAuditInit` refused to seed a member ("no timed-out mutants in
this run's report — nothing to seed") and prescribed the comment-only file, so a
first timeout lands as the reviewer-stop it is rather than as silent detection.

Why this backend lacks the socket-wait liveness family the jdk and jetty suites
carry: when a mutant drops a `send()` (or the `handler.handle` dispatch) the
controller returns with the response unsent, and Helidon's `HttpRoutingImpl`
answers 500 itself ("A route MUST call either send, reroute, or next on
ServerResponse"). Every such mutant is a status mismatch the covering test sees
at once, never a client blocked on a socket read — `VoidMethodCallMutator` fired
14 times and all 14 read `KILLED`. The one path that does hang a client on the
other backends, a dropped `WebServer.start()`, is caught here by the adapter's
own `isRunning()` check throwing before any client connects.

**Fixture bound (recorded per the 21.5.25 rule).** Every `HttpClient` request in
`HelidonConformanceTest` carries `HttpRequest.timeout(Duration.ofSeconds(10))`
and the raw-socket helper sets a 10 s `SO_TIMEOUT`. Neither is the claimed
oracle for anything — there is no timeout member — and neither can fire before
PIT's recorded-duration × 1.25 + 4000 ms margin at these durations. Recorded so
the next reviewer does not have to rediscover it.

### Duration context

Helidon's first `WebServer` in a JVM pays a cold bootstrap (feature discovery,
media/encoding context, the INFO banner) of a few hundred milliseconds; warm
start/stop cycles are single-digit milliseconds. Measured 2026-09-12 in the
module's own test report: the first server-starting test takes ~570 ms, every
later one 30–50 ms. Each PIT minion pays the cold cost once, which is why the
plugin's coverage-phase advisory names `throwingHandlerFailureIsLogged`
(382–791 ms across the 2026-09-12 runs, threshold 250 ms) as the slowest
covering test — it is simply the test the minion's JUnit ordering starts the
first server in. It is an advisory, not a finding; retiming it is the first
thing to try if a load-dependent `TIMED_OUT` ever needs repair rather than
audit.

### Deliberately absent tests

Jetty's `blockingHandlersRunOnTheProvidedExecutor` and the jdk executor cases
have no counterpart: Helidon runs each request on its own virtual thread,
refuses a response completed from another thread and exposes no executor
injection, so `HelidonServerBuilder` accepts and ignores the executor (as the
FusionAuth adapter does). There is no wiring for such a test to pin.
