# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. Full policy lives in sava-build's
`HARDENING.md`.

Never refresh with `-PupdateMutationBaseline` just to make the build pass:
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

Every member is the same structural cause: a removed call that leaves the
`HttpExchange` unanswered and unclosed, so the test client blocks on a
response that will never arrive and PIT's watchdog — not an assertion — ends
the run. That is exactly the blind spot the audited set exists for: weaken
any of these tests to uselessness and the timeouts keep reading as
"detected". The seven rows collapse to four line-less members; re-read the
lines named here whenever the dispatch path changes.

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
