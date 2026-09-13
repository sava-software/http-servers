# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline keys
are line-less — `class,method,mutator,status`, observed lines kept as trailing
`# line` tags refreshes rewrite. Full policy lives in sava-build's
`HARDENING.md`; the installed mechanics are `./gradlew :http-servers-netty:hardeningHelp`.

Never run a baseline-writer task just to make the build pass:
kill the mutant, refactor it out of existence, or record its equivalence
reason below.

## dispatch suite (no accepted rows) — seeded 2026-09-12, emptied 2026-09-12

Registered with the module. It mutates the whole `software.sava.http_servers.netty`
package against `netty.*Test*`: the per-connection pipeline in the order the
initializer builds it — `NettyRequestGate` (one request at a time, the pipelining
queue, the read pause, and the connection's persistence), `NettyRequestAggregator`
(the explicit `413`-and-close policy over Netty's aggregator), `NettyController`
(routing and the 400/404/405 JSON bodies, CORS and pre-flights, the blocking-route
offload, `exceptionCaught` and its client-abort classification) — plus the request
view, response framing, the initializer, the lifecycle handle and the builder wiring.
The covering tests are real socket round trips (`NettyConformanceTest`,
`NettyPostHandlerTest`) and, since the 2026-09-12 gate rework, the same production
pipeline driven in process on an `EmbeddedChannel` (`NettyPipelineTest`, built from
the real `NettyChannelInitializer`), where a write completes synchronously, a blocking
route completes when the test runs it, and the channel's read state is readable.

After the gate rework the population is **132 mutants, 132 killed, 0 `SURVIVED`,
0 `TIMED_OUT`, 0 `NO_COVERAGE`**, observed history-free three times on the final code
(`pitestDispatch -PnoMutationHistory` twice, then `pitestDispatchBaselinePrune`'s own
write-boundary run) under 1-minute load averages of 21–27, `pitestDispatchVerify`
green. Line coverage of the mutated classes is 218/220; the two uncovered lines are
`ResponseUtil`'s private constructor, which nothing calls and which generates no
mutant. There is no `dispatch-accepted.csv`: the prune below removed the last row
and the plugin removes the file with it. Keep it that way. (History: the first seed
was 106/105/1 and the review-application pass 110/109/1.)

### Mutators: `STRONGER,EXPERIMENTAL_NAKED_RECEIVER` (measured 2026-09-12)

Netty's `HttpHeaders.set`, `ChannelPipeline.addLast`, `ChannelConfig.setAutoRead`,
`ChannelPromise.addListener` and the `ServerBootstrap` fluent chain all return their
receiver, so `VoidMethodCallMutator` never fires on a header write, a pipeline
assembly, a listener registration or a bootstrap step; `EXPERIMENTAL_NAKED_RECEIVER`
makes them expressible. On the first history-free run of 2026-09-12 it produced 17
receiver-returning mutants, 16 killed and 1 accepted; after the gate rework every
receiver-returning site is killed — the header writes (`Allow`, `Access-Control-*`,
`Content-Type`, `ResponseUtil.closeAfter`'s `Connection: close`), the pipeline
`addLast`, the bootstrap steps and the bind `sync`, the gate's `promise.addListener`
(drop it and nothing ever completes or closes) and the gate's `setAutoRead` (drop it
and the read state the in-process suite asserts never changes). Three receiver-returning
sites were refactored out rather than accepted (see "Refactored out").

### Fixture bound: 2 s, and why it is not 10 s

The sibling suites bound every client request at 10 s. The first pass of this
suite did the same and read 31 `TIMED_OUT` instances over 24 line-less keys —
every one a dropped or never-completed response write that leaves the client
blocked on a read. PIT's per-mutant watchdog fires at the recorded duration × 1.25 +
4000 ms — about 4 s at these durations — so a 10 s bound can never fire first and
contributes no cause evidence: detection would have been the clock, not this
suite. The 21.5.25 rule says to shorten such a bound and re-observe. At 2 s the
bound beats the watchdog with two seconds to spare, and a hang fails the test as
an `HttpTimeoutException` / `SocketTimeoutException` — an assertion-level kill.
Every raw-socket case additionally reads one Content-Length delimited response
and asserts on it before waiting for the close, so a wrong status or a missing
framing header dies by its assertion rather than by the bound.

One shape the 2 s bound does not cover: `HttpRequest.timeout` bounds the arrival of
the response *head* only, so a response that arrives without `Content-Length` on a
persistent connection leaves `HttpClient` waiting for a body without bound, and the
watchdog is the only exit. That is the finite `KILLED`↔`TIMED_OUT` race first seen
on `dispatch`'s pre-flight `Content-Length: 0` under a load average above 130, read
`TIMED_OUT` again on one of three history-free runs during the gate rework (load
~20), and never admitted: a finite race is `cause:harness` and does not certify.
Framing sites are therefore pinned on the wire in process
(`NettyPipelineTest.corsPreflightIsContentLengthDelimited` for the pre-flight,
`theNextRequestWaitsForTheFinalResponse` for `ResponseUtil`'s `Content-Length`),
which fail by assertion in milliseconds and which PIT's time-ordered prioritiser runs
before any socket case; the raw-socket assertions (`corsPreflightIsContentLengthDelimited`
in the socket suite, every `readResponse` caller) are the second line and the
`HttpClient` cases the last. Three history-free observations since read `KILLED`.

The bound is a fixture safety net, never the claimed oracle for a `SURVIVED`
row; there are no such rows.

### Accepted rows (`dispatch-accepted.csv`)

None. The one row this suite ever carried, `# backpressure`
(`NettyController.channelRead0`, `NakedReceiverMutator` on the `setAutoRead(false)`
read pause), was retired on 2026-09-12 by refactor, not by argument. Its site no
longer exists: flow control moved into `NettyRequestGate.pauseOrResume`, and the
property it stated — while a fully received request awaits its response the
connection is not read — is now observed directly through
`EmbeddedChannel.config().isAutoRead()` (`theNextRequestWaitsForTheFinalResponse`,
`aBodyStillArrivingIsReadOn`), which is exactly the "deterministic seam for
observing reads-paused" the row's own *Invalid if* clause named. The same shape in
the gate is killed. Removed through the Prune protocol: two fresh, full, history-free
previews with the identical single-row candidate multiset, then
`pitestDispatchBaselinePrune`'s own matching run.

### Refactored out (no rows)

- `NettyRequestGate.handlerRemoved` — the first draft released the queue with
  `while ((msg = queued.poll()) != null)`; `RemoveConditional` on that exit turns it
  into an infinite loop over `release(null)`, which read `TIMED_OUT` on the first
  history-free run. The loop is now `queued.forEach(ReferenceCountUtil::release)`,
  which has no exit condition to remove; dropping the call is a leak the in-process
  suite sees as a non-zero `refCnt` on the queued body's buffer
  (`aClosingResponseEndsTheConnectionAndReleasesTheQueue`).
- `NettyRequestGate.completed` — the first draft guarded the drain with
  `ctx.channel().isActive()`. Equivalent before it was ever observed: a response that
  ends the connection never calls `completed`, so its request stays in flight for
  good and `!paused()` already stops every drain at it (`aCloseFromInsideTheDrainStopsTheDrain`).
  Removed rather than measured.
- `NettyController`'s pending queue, `completed`, `setAutoRead` and the guarded
  write-completion listener — moved wholesale into the gate. The property the guard
  pinned (a queued handler's `Error` is answered 500 and closed) still holds through
  the pipeline's own routing of a `channelRead` throw to the throwing handler's
  `exceptionCaught`, and `errorEscapingAQueuedHandlerIsAnsweredAndLogged` still pins it.
- `NettyHttpServer.shutdown` — the first draft waited with
  `terminationFuture().syncUninterruptibly()`, whose `NakedReceiverMutator` (drop
  the wait) survived: `stop()` then returns while the loop threads are still
  exiting, distinguishable only by racing a rebind against them. The wait is now
  `terminationFuture().get()` — the same wait, interruptible, and not a
  receiver-returning shape — so the site no longer exists. The property (a stopped
  server's port is free and its connections cut) stays pinned by
  `aStoppedServerRefusesConnections`.
- `NettyRequest.<init>` — the first draft copied `headers()`; the mutant that
  dropped the copy survived, which is the measurement that the copy was a no-op:
  `HttpHeaders` is a plain map with no tie to the pooled content buffer, and the
  codec never touches a request's headers after handing it on. The reference is
  now kept as-is (the body bytes are still copied — `content()` *is* pooled), and
  the class javadoc says why. The request's version and keep-alive left this class
  with the gate rework: the gate reads them off the decoded head.

### Killed by pinning rather than accepted

- Ordering by construction (RFC 9112 §9.3.2), the 2026-09-12 P1 fix: the gate
  forwards a request head only while nothing is in flight and completes the in-flight
  request on the write of a non-informational response, whoever wrote it. Every
  branch of `channelRead`, `forward`, `paused`, `write` and `completed` is pinned by
  `pipelinedResponsesArriveInRequestOrder`, `pipelinedExpectationFailureKeepsRequestOrder`
  and `pipelinedOversizedRequestKeepsRequestOrder` over sockets, and in process by
  `theNextRequestWaitsForTheFinalResponse` (one release per completion, reads paused
  exactly while a received request waits), `anInterimResponseCompletesNothing` (a
  `100` neither completes nor closes, even on a request that asked to close),
  `aBodyStillArrivingIsReadOn`, `theRequestsCloseIsHonouredOnAnAggregatorAnswer` (the
  417 waits its turn and honours the request's close) and
  `anOversizedRequestIsRefusedInOrderThenClosed`.
- Persistence from both sides (§9.6), the 2026-09-12 P2 fix: `keepAlive =
  request keep-alive && !response Connection: close`, framed by `HttpUtil.setKeepAlive`
  in `NettyRequestGate.write` and closed after the write. The handler's close by
  `handlerConnectionCloseIsHonoured` and `aClosingResponseEndsTheConnectionAndReleasesTheQueue`
  (in any case, and nothing behind it runs); the request's close by
  `connectionCloseIsHonoured` and `theRequestsCloseIsHonouredOnAHandlerAnswer`; the
  HTTP/1.0 default and its explicit `keep-alive` acknowledgement by
  `http10RequestIsAnsweredThenClosed`, `http10KeepAliveIsHonoured` and
  `http10PersistsOnlyOnRequest`. The `future.isSuccess()` guard on completion (a
  response that never reached the client releases nothing) by
  `aFailedResponseWriteReleasesNothing` through a promise-failing outbound stub.
- `NettyRequestAggregator.handleOversizedMessage` — `413`, `Content-Length: 0`,
  `Connection: close`, then the close, independent of the channel's `autoRead` at
  that instant: `pipelinedOversizedRequestKeepsRequestOrder` (framing and EOF on a
  socket), `anOversizedRequestIsRefusedInOrderThenClosed` and
  `oversizedBodiesAreRefusedWith413`.
- `ResponseUtil.closeAfter`'s `Connection: close` (one site shared by the malformed
  400, the `exceptionCaught` 500 and the 413): `malformedRequestsAreRefusedAndClosed`,
  `errorEscapingANonBlockingHandlerIsAnsweredAndLogged` and the 413 cases above all
  read to EOF.
- `NettyController.exceptionCaught`'s client-abort branch (a
  `PrematureChannelClosureException` is the peer leaving mid-request, logged at
  `DEBUG` with nothing written): both directions by
  `aClientLeavingMidRequestIsNotAServerFailure` (no `SEVERE`, one `FINE` carrying the
  throwable, an empty wire) against `errorEscapingANonBlockingHandlerIsAnsweredAndLogged`
  (a real `Error` still answered 500 and logged at `ERROR`).
- `Expect: 100-continue` before the body (RFC 9110 §10.1.1): `expectContinueIsAnsweredBeforeTheBodyIsSent`
  sends nothing past the head until the `100` has arrived.
- The `Access-Control-Allow-Headers` null guard on the pre-flight path — Netty's
  header map refuses the null the other backends treat as a no-op — is pinned by
  `corsPreflightWithoutRequestHeadersAnswers`.
- The `blocking()` booleans of `NettyQueryHandler` and
  `NettyCachedResponseHandler`, both directions, by
  `blockingHandlersRunOnTheProvidedExecutor` through a recording executor.
- The host guard in `NettyServerBuilder.initRestServer`, both directions, by
  `theServerBindsTheRequestedHost` — a synchronous read of the unstarted server's
  address, no socket — and again by `startOnAnOccupiedPortThrows`.
- The dispatch guard's log by the two `throwing*HandlerAnswers500` cases; the
  builder's create-failure log by `invalidPortPropagatesTheFailure`.
- The bodyless-status branch of `ResponseUtil.response` in both directions: the
  codec strips framing from a 204 on its own, but a 304 with `Content-Length: 0`
  would reach the wire, and `bodylessStatusesCarryNoFramingHeaders` reads both raw
  (and pins the surviving `Content-Type`, so the two absence checks mean absence).
- The double-start guard in `NettyHttpServer.start` (`if (bossGroup != null)
  throw`): `RemoveConditional` both directions killed by
  `secondStartThrowsAndTheRunningServerIsUndisturbed`.
- The reply version (`ResponseUtil` frames every response as `HTTP/1.1` whatever
  version the request named): `http10RequestIsAnsweredThenClosed` pins `HTTP/1.1 200`
  for an `HTTP/1.0` request.

### Seed provenance

The baseline was seeded 2026-09-12 by `pitestDispatchBaselineUpdate`, which runs
its own fresh, full, history-free observation: 106 mutants, 105 killed, 1
`SURVIVED`, 0 `TIMED_OUT`, 0 `NO_COVERAGE`, `RUN_ERROR` 0 for every mutator, under a
load average of 134 with two further history-free observations (loads 130 and ~80)
reading the identical population and verdicts. The 2026-09-12 gate rework was
observed history-free five times in all: one run on the draft (133 mutants, the
`handlerRemoved` loop `TIMED_OUT`), one on the refactored gate (132, all killed), one
that read the pre-flight framing race `TIMED_OUT` (132, all detected), and — on the
final code — the two prune previews and the prune's own write-boundary run (132 killed,
nothing timed out), 1-minute loads 10–27. None of these was a solo-quiet machine by
the certify rule (load under ~5); the suite is still owed one such observation
before the first `hardeningCertify`.

### Slow-covering-test advisory

The plugin's coverage-phase advisory names `absentHostBindsAllInterfaces` at
246–251 ms against its 250 ms threshold: the case starts two servers (a `null`
host and a blank one) and two `HttpClient`s. Advisory only, recorded so it is
the first thing to look at if this suite ever shows a load-dependent `TIMED_OUT`.

## Audited timeouts (`dispatch-timeouts.csv`)

**The set is empty and armed.** After the 2 s retiming above, three fresh
history-free observations on 2026-09-12 (the seed run, `BaselineUpdate`'s own run
and `TimeoutAuditInit`'s own run) observed zero `TIMED_OUT` mutants, so no member
could be classified; `pitestDispatchTimeoutAuditInit` refused with "no timed-out
mutants in this run's report — nothing to seed" and prescribed exactly this: a
comments-only `dispatch-timeouts.csv` that arms the audit, so a newly timed-out
mutant lands as a reviewer-stop instead of passing as silent detection.

The gate rework tripped that reviewer-stop twice, and neither instance is recorded,
because neither is liveness: `NettyRequestGate.handlerRemoved`'s
`RemoveConditionalMutator_EQUAL_IF` (a loop whose exit was removed — refactored out,
above) and `NettyController.dispatch`'s `VoidMethodCallMutator` on the pre-flight
`Content-Length: 0` (the finite head-only-response race — repaired in process, above).
Any future `TIMED_OUT` in this suite is, by construction, a covering test that
exceeded its bound *and* PIT's margin, or a head-only response an `HttpClient` case
was left to wait on: a `SURVIVED`↔`TIMED_OUT` flip of a row that should already be
argued here, or a `KILLED`↔`TIMED_OUT` finite race to be repaired with a wire
assertion — never a liveness argument, since every hang this suite can produce is
bounded.

One trap worth naming: a history-assisted run (`pitestDispatch` without
`-PnoMutationHistory`) on a machine whose `.pitest-history/` predates the 2 s
retiming carries the first pass's `TIMED_OUT` verdicts forward — PIT's
incremental analysis assumes a mutant that timed out still does while its class
is unchanged, and never re-executes it — and prints them as "not in the audited
set". Observed 2026-09-12: a `[history]` run finished in 11 s while reporting 29
timed-out instances, which cannot both be true. That preview is check-only by
the plugin's own rule and no record decision may come from it; delete the
machine-local history or run history-free to see the real state.
