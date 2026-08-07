# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline keys
are line-less — `class,method,mutator,status`, observed lines kept as trailing
`# line` tags refreshes rewrite. Full policy lives in sava-build's
`HARDENING.md`.

Never run a baseline-writer task just to make the build pass:
kill the mutant, refactor it out of existence, or record its equivalence
reason below.

## dispatch suite — no accepted mutants (since 2026-07-24)

Registered when `JdkController` gained real routing logic (the shared
`HandlerMap` dispatch that fixed jdk-context prefix matching). The covering
tests are real socket round trips (`JdkConformanceTest`,
`JdkPostHandlerTest`), so the suite runs slower per mutant than an
in-process one and carries 7 `TIMED_OUT` mutants (socket-wait conversions),
observed detected in both solo and `qualityGate` runs — not unioned into
the baseline.

### Audited timeouts (`dispatch-timeouts.csv`)

Four of the five members share one structural cause: a removed call that
leaves the `HttpExchange` unanswered and unclosed, so the test client blocks
on a response that will never arrive and PIT's watchdog — not an assertion —
ends the run. The fifth (`process` 79) blocks the client a different way and
is argued separately below. That is exactly the blind spot the audited set
exists for: weaken any of these tests to uselessness and the timeouts keep
reading as "detected". The eight rows collapse to five line-less members, all
`cause:liveness`. Every one of them is an external completion dependency — the
test client blocks on a socket read that the mutated path never satisfies — so
none is the straight-line path the 21.5.25 doctrine refuses as liveness
evidence. Membership and cause are key-level, so the `cause:liveness` token
claims every sibling under each of these keys; none is a proven mixed key
today, and if one is ever shown to mix liveness with a finite cause the repair
is to split it into distinct method keys, not to annotate a line. The line
values named here are diagnostic pointers only — moving the dispatch path never
warns, fails, or requires re-anchoring.

**Fixture bound (recorded per the 21.5.25 rule).** `JdkConformanceTest`'s
requests carry `HttpRequest.timeout(Duration.ofSeconds(10))`. It is *not* the
claimed oracle for any row above — the argument is PIT's watchdog, not the
client — and it cannot fire first: PIT's per-mutant margin here is the
recorded duration × 1.25 + 4000 ms, a few seconds at these durations. A bound
that cannot fail first contributes no cause evidence in either direction, so
it neither supports nor weakens the five members; it is recorded so the next
reviewer does not have to rediscover it.

- `JdkQueryHandler.handle` 44, 46 (`VoidMethodCallMutator`) — 44 drops
  `process(exchange)` on the blocking branch, 46 drops the
  `executor.execute(...)` that carries the non-blocking one. Either way no
  handler ever runs and nothing writes response headers.
- `JdkQueryHandler.lambda$handle$0` 48, 52 (`VoidMethodCallMutator`) — the
  same pair one frame deeper inside the executor task: 48 drops
  `process(exchange)`, 52 drops the `JdkController.serverError(exchange)`
  that is the only remaining answer once the controller's frame is gone.
- `JdkController.handle` 47, 51 (`VoidMethodCallMutator`) — 47 drops the
  `handler.handle(exchange)` dispatch itself, 51 the `serverError(exchange)`
  fallback in its catch; the second is why a throwing handler hangs the
  client instead of aborting it.
- `JdkHttpServer.start` 15 (`VoidMethodCallMutator`) — `server.start()`
  removed. `HttpServer.create` has already bound the port, so connections sit
  in the accept backlog: the client connects and then waits forever, which is
  why this reads as a timeout rather than a connection refusal.
- `JdkQueryHandler.process` 79 (`VoidMethodCallMutator`) — `os.write(body)`
  removed. **Not the unanswered-exchange cause above**: line 77 has already
  run `sendResponseHeaders(statusCode, body.length)`, so the exchange is
  answered and the try-with-resources still closes it — the response simply
  under-delivers against the Content-Length it just promised. The client is
  told to expect `body.length` bytes, receives none, and blocks reading the
  promised body. Admitted 2026-08-04, first observed timing out under
  certification load having previously read as detected; load-dependent like
  the rest of this module's dispatch family, so a solo run may show it killed
  by the body assertion instead. **Open under 21.5.25** (2026-08-07): "may show
  it killed" is a conjecture, never a measurement. If a scoped history-free
  solo run demonstrates a `KILLED` reading, this stops being liveness and
  becomes a finite `KILLED`↔`TIMED_OUT` race — `cause:harness`, non-certifying,
  repaired by retiming or fixing the covering path rather than admitted. Unlike
  jetty's `initRestServer` 34 the finite reading here has *not* been
  demonstrated, so the row stays `cause:liveness`; measure it (solo and gate,
  `-PnoMutationHistory`) before the next certification that is not confounded
  by an unrelated toolchain change.

The error-log `VoidMethodCallMutator`s (`JdkController.handle`,
`JdkQueryHandler`'s executor task, `initRestServer`'s create-failure log)
were killed 2026-07-22: the failure-path tests capture the JUL records and
assert the thrown exception is logged — "failures are never silent" is
pinned, not accepted.

The wildcard-bind family (`initRestServer` 34, both skip-directions) was
accepted 2026-07-22 as "distinguishable only from a second network
interface" — falsified 2026-07-24: `startOnAnOccupiedPortThrows` occupies
the requested `localhost` address, so binding the wildcard instead dodges
the conflict and the expected bind failure never happens. The occupied
port is the second observer the acceptance said did not exist; the
baseline is now empty — keep it that way.
