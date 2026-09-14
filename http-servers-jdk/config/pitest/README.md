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
in-process one. 48 mutants since the connection-hygiene rework of
2026-09-14 and the review that followed it the same day (the two added are
the request-body cache's `body == null` siblings, both killed), **100%
detected**, 2 `TIMED_OUT` — down from 7: the rework turned five
unanswered-exchange hangs into closed connections the client sees at once
(see the audited timeouts below) — observed identically in two full
history-free runs of each state; not unioned into the baseline, which stays
empty. Keep it that way.

### Connection hygiene (2026-09-14)

The rework answers a leak bisected in the soak harness: jdk.httpserver never
unregistered a connection whose exchange failed inside `HttpExchange.close()`,
or after `handle()` had returned to it (`JdkController`'s javadoc carries the
JDK-source argument, `JdkQueryHandler`'s the reason the executor hop had to
go). The adapter now runs every handler inline on the server executor, lets
every `IOException` escape `handle()`, and closes an unread request body
before it writes a bodyless head. The behavioral clusters the ratchet cannot
infer are pinned by name in `JdkConformanceTest`:

- Property: a client that resets or half-closes mid-upload leaves no
  connection registered, is answered nothing, and is logged at `DEBUG` only |
  Oracle: `ServerImpl.allConnections` read by reflection — no public
  observation exists (the exchange count `stop(delay)` waits on is never
  decremented on the repaired path either, the idle timer scans only the idle
  sets, and the `maxConnections` cap is a JVM-wide static read once) — plus the
  raw wire and a JUL capture | Outcome: production bug
  (`anUploadAbandonedByResetIsUnregistered`,
  `anUploadAbandonedByHalfCloseIsNeitherAnsweredNorLeaked`).
- Property: a reset while an 8 MiB response is being written on the
  non-blocking route leaves no connection registered | Oracle: the same set,
  once the head has been read | Outcome: production bug
  (`aClientResettingMidResponseOnTheNonBlockingRouteIsUnregistered`).
- Property: an unread request body is drained before a bodyless head is
  written, so a truncated upload to an unrouted path is answered nothing and
  unregistered | Oracle: the wire (no `404` head arrives) and the set |
  Outcome: production bug
  (`anUnroutedUploadHalfClosedMidBodyIsNeitherAnsweredNorLeaked`,
  `anUnroutedUploadResetDuringThe404IsUnregistered`). The half-close sibling
  is what kills the request-body `close()` removal in
  `JdkController.answerWithoutBody` deterministically: with the drain moved
  behind the head, the head arrives.
- Property: blocking and non-blocking handlers both run on the server
  executor, and the deprecated task executor receives nothing | Oracle: the
  handler's thread name, set by the executor | Outcome: contract change,
  pinned (`nonBlockingAndBlockingHandlersRunOnTheServerExecutor`,
  `theDeprecatedTaskExecutorReceivesNothing`).
- Property: `Request.body()` hands back the same bytes however many times it
  is read, and a second read is neither a departure nor a failure | Oracle:
  the core contract names no single-read restriction, `NettyRequest` returns
  the body it holds and java-http's `HTTPRequest.getBodyBytes` caches its
  first read; the wire and a JUL capture | Outcome: production
  bug, found on review — jdk.httpserver's request stream is single-shot, so
  with `BodyReadException` covering every `IOException` out of `body()` a
  handler's second read was answered nothing and logged at `DEBUG` as a client
  leaving; the adapter now keeps the bytes from the first read
  (`theRequestBodyCanBeReadMoreThanOnce`).

The leak oracle needs `--add-opens jdk.httpserver/sun.net.httpserver`: on the
test task for the module-path run, and as the suite's evidence-bound
`minionJvmArgs` for the class-path minions (`build.gradle.kts`).

### Audited timeouts (`dispatch-timeouts.csv`)

Three members, all `cause:liveness`, all one structural cause: a removed
call that leaves the `HttpExchange` unanswered and unclosed, so the test
client blocks on a response that will never arrive and PIT's watchdog — not
an assertion — ends the run. That is exactly the blind spot the audited set
exists for: weaken any of these tests to uselessness and the timeouts keep
reading as "detected". Every member is an external completion dependency —
the test client blocks on a socket read that the mutated path never
satisfies — so none is the straight-line path the 21.5.25 doctrine refuses as
liveness evidence. Membership and cause are key-level, so the
`cause:liveness` token claims every sibling under each of these keys; none is
a mixed key, which would need two siblings *timing out* for different
structural reasons rather than merely a killed sibling sharing a key. If one
is ever shown to mix causes the repair is to split it into distinct method
keys, not to annotate a line. The line values named here are diagnostic
pointers only — moving the dispatch path never warns, fails, or requires
re-anchoring.

**Fixture bounds (recorded per the 21.5.25 rule).** `JdkConformanceTest`'s
requests carry `HttpRequest.timeout(Duration.ofSeconds(10))` and its raw
sockets a 10 s `SO_TIMEOUT`. Neither is the claimed oracle for any row above —
the argument is PIT's watchdog, not the client — and neither can fire first:
PIT's per-mutant margin here is the recorded duration × 1.25 + 4000 ms, a few
seconds at these durations. A bound that cannot fail first contributes no
cause evidence in either direction, so it neither supports nor weakens the
members; it is recorded so the next reviewer does not have to rediscover it.
The connection-hygiene tests carry two shorter bounds, 2 s each and under the
margin: the poll on the connection set, which all five carry, and a latch the
client waits on before it aborts, which three carry — the handler-entered
latch in `anUploadAbandonedByResetIsUnregistered` and
`anUploadAbandonedByHalfCloseIsNeitherAnsweredNorLeaked`, and the latch on
jdk.httpserver's own `Exchange request line` record in
`anUnroutedUploadResetDuringThe404IsUnregistered`. Neither bound is the
oracle for a timeout member, and each of those three tests sends one routed
probe request before its aborts precisely so that a mutant under which the
latch can never open — no handler runs, or the server never starts — fails
them the way it fails every other covering test, a blocked read past the
margin, instead of by the 2 s bound in some test orders only. The other two
(`aClientResettingMidResponseOnTheNonBlockingRouteIsUnregistered`,
`anUnroutedUploadHalfClosedMidBodyIsNeitherAnsweredNorLeaked`) wait on no
latch and need no probe: under such a mutant they block on their 10 s socket
reads, and every other verdict they reach is a wire assertion. The race the
probe prevents was measured before it existed: `JdkController.handle`'s
`handler.handle` removal read `KILLED` in one scoped history-free run and
`TIMED_OUT` in the next, the order-dependent `KILLED`↔`TIMED_OUT` race the
doctrine forbids; with the probe it read `TIMED_OUT` in two scoped and two
full runs. The 404-reset test's probe was added on review the same day, by
the same reasoning, for the `JdkHttpServer.start` member: `HttpServer.create`
has already bound the port, so a never-started server still accepts that
test's upload into its backlog and its latch — not a blocked read — would
have been the first bound to fire whenever it ran ahead of a blocking test.

- `JdkController.handle` (`VoidMethodCallMutator`; line 79 on 2026-09-14) —
  drops `handler.handle(exchange)`: no handler runs and nothing writes response
  headers. The `serverError` fallback that used to share this key is now
  `answerWithoutBody(exchange, 500)` inside `try (exchange)`, and its removal
  reads `KILLED`, as do the removals of the 400/404/405 answers: closing an
  exchange with nothing sent makes `ExchangeImpl.close` close the connection,
  which the client sees as EOF at once. The `try (exchange)` costs nothing in
  production — `sendResponseHeaders(status, -1)` closes the exchange on success
  and `ServerImpl.closeConnection` closes the connection on failure.
- `JdkHttpServer.start` (line 15) — `server.start()` removed. `HttpServer.create`
  has already bound the port, so connections sit in the accept backlog: the
  client connects and then waits forever, which is why this reads as a timeout
  rather than a connection refusal.
- `JdkQueryHandler.handle` — retained, quiet since 2026-09-14. Its timing-out
  siblings were the blocking-branch `process(exchange)` call and the
  `executor.execute(...)` hop, both gone with the hop. The siblings that
  remain — the `answerWithoutBody` call for 204/304 inside `try (exchange)`,
  `sendResponseHeaders(statusCode, body.length)` and `os.write(body)` — read
  `KILLED` in every 2026-09-14 observation because each leaves the exchange in
  a state the adapter now closes: a dropped bodyless answer closes the
  connection through `try (exchange)`; dropped headers make the placeholder
  stream's write throw; and a dropped body write makes
  `FixedLengthOutputStream.close` throw "insufficient bytes written", an
  `IOException` that escapes `handle()` to `closeConnection`, ending the
  client's read at EOF. That last one closes the question left open under
  21.5.25 for the former `process` member: the finite reading is now
  demonstrated, and the 2026-08-04 `TIMED_OUT` was the executor hop's doing —
  on the non-blocking route the same exception was caught in the hopped task
  and swallowed by `serverError`, so the covering tests that went through that
  route blocked while the blocking ones killed, and the verdict followed the
  test order. With every covering path finite the member is admissible for
  retirement once the tool reports 3+ fresh full-run quiet observations under
  solo/gate load; until then it stays.

**Retired members (2026-09-14).** `JdkQueryHandler.lambda$handle$0` (the
executor task no longer exists) and `JdkQueryHandler.process` (folded into
`handle`; its `os.write` sibling is finite now, above). Both matched no mutant
in the fresh history-free full report and were removed by hand, which is the
verify's stated remedy for a stale row — the plugin's only timeout-set writer,
`TimeoutAuditInit`, seeds a new file and refuses an existing one.

The error-log `VoidMethodCallMutator`s (`JdkController.handle`'s `ERROR` for
a throwing handler, `initRestServer`'s create-failure log) were killed
2026-07-22: the failure-path tests capture the JUL records and assert the
thrown exception is logged — "failures are never silent" is pinned, not
accepted. Since 2026-09-14 the same holds for the two `DEBUG` records a
departing client leaves: the connection-hygiene tests open the logger to
`FINE`, count exactly one record per departure with its `IOException`
attached and the message naming the side of the exchange the client left
during — a failed body read or drain is "before its request body was read",
a failed response write "before its response was written" — and assert
nothing above `FINE` was logged.

The wildcard-bind family (`initRestServer` 34, both skip-directions) was
accepted 2026-07-22 as "distinguishable only from a second network
interface" — falsified 2026-07-24: `startOnAnOccupiedPortThrows` occupies
the requested `localhost` address, so binding the wildcard instead dodges
the conflict and the expected bind failure never happens. The occupied
port is the second observer the acceptance said did not exist; the
baseline is now empty — keep it that way.
