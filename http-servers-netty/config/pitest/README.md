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
queue, the read pause, the connection's persistence and, since 2026-09-13, its idle
timeout on an injected clock), `NettyRequestAggregator`
(the explicit `413`-and-close policy over Netty's aggregator), `NettyController`
(routing and the 400/404/405 JSON bodies, CORS and pre-flights, the blocking-route
offload, `exceptionCaught` and its client-abort classification) — plus the request
view, response framing, the initializer, the lifecycle handle and the builder wiring.
The covering tests are real socket round trips (`NettyConformanceTest`,
`NettyPostHandlerTest`) and, since the 2026-09-12 gate rework, the same production
pipeline driven in process on an `EmbeddedChannel` (`NettyPipelineTest`, built from
the real `NettyChannelInitializer`), where a write completes synchronously, a blocking
route completes when the test runs it, the channel's read state is readable and —
since the idle timeout — time is a clock the test advances (`FakeClock`: the embedded
loop's `Ticker` at origin 10^12 ns and, read through `gateNanoTime()` at a *negative*
origin of −2·10^12 ns — what `System.nanoTime` may deliver in production, unlike the
normalised ticker — the gate's clock, so the two agree in differences and disagree in
absolute readings, which is what makes a deadline computed from an absolute reading, or
an accept time mutated to zero, observable).

After the expectation-refusal fix (2026-09-13, after the idle-timeout review: the
delayed-reset defect under "Killed by pinning", its reproductions and the tests that pin
the refusal contract) and the review of that fix the same day (the framing of a head the
codec could not parse, below, and the tests that pin the malformed-head and
unparsable-line shapes and the refusal over sockets) the population is **180 mutants, 180
killed, 0 `SURVIVED`, 0 `TIMED_OUT`, 0 `NO_COVERAGE`, 0 `RUN_ERROR`**, observed
history-free on the final code (`pitestDispatch -PnoMutationHistory`, PIT's mutation phase
8 s of a 9 s run, 906 test executions, 5.03 per mutant) under a 1-minute load average of
23.2 at launch and 31.1 when it finished, `pitestDispatchVerify` 180/180,
`mutationOwnershipAudit` 13 classes owned — the gate's `Refusal` record is the thirteenth.
Line coverage of the mutated classes is 283/285, the two uncovered lines still
`ResponseUtil`'s private constructor. Over the 144-mutant idle-timeout population that is
+36: from the refusal fix, the gate's `admit` (7), `refusal` (11) and `answer` (5),
`channelRead`'s head dispatch (2) and its discard guard (2), `forward`'s `Refusal` branch
(3) and its extracted `begin` call (1), and the decoder-failure guard of
`NettyRequestAggregator.newContinueResponse` (3) — 34 — and from its review the
decoder-failure conditional in `begin` (2) that frames an unparsable head as HTTP/1.1.
`ResponseUtil.emptyResponse`'s two mutants add nothing to the total: they replaced the two
inline `setContentLength` sites (the aggregator's `413` and the pre-flight `200`) it
absorbed. Every one is killed by a named test below. The fix's own observation, before the
review, read 178/178 (mutation phase 8 s of 10 s, 868 executions, 4.88 per mutant) under
load averages of 35.7 at launch and 34.2 a minute after. There is still no
`dispatch-accepted.csv`. Keep it that way.

After the idle-timeout review (2026-09-13: the stalled-body fix, the knob validation and
the tests below) the population was **144 mutants, 144 killed, 0 `SURVIVED`, 0
`TIMED_OUT`, 0 `NO_COVERAGE`, 0 `RUN_ERROR`**, observed history-free on that code
(`pitestDispatch -PnoMutationHistory`, PIT's mutation phase 10 s, 648 test executions,
4.5 per mutant) under a 1-minute load average of 29 at the start and 39 at the end,
`pitestDispatchVerify` 144/144, `mutationOwnershipAudit` 12 classes owned. The two
mutants added were `NettyServerBuilder.<init>`'s `RemoveConditional` pair on the
idle-timeout validation, both killed (below). Line coverage of the mutated classes was
242/244. (The idle timeout's first observation, the same day and before the review, read
142/142 under a load average of 5.75, `pitestDispatchVerify` green.) The attempt before that, on
the same code under a load average of 8.92, produced the identical 142-mutant
population with 141 killed and one `RUN_ERROR` — a minion death at
`NettyRequestAggregator.handleOversizedMessage` / `VoidMethodCallMutator`, PIT's only
diagnosis the generic "did not start or died during analysis", no resource failure
named — which the verifier refused as invalid evidence; nothing was tuned, and the
clean run is its closure, not its diagnosis. (History: the first
seed was 106/105/1, the review-application pass 110/109/1, and the 2026-09-12 gate
rework 132/132/0, observed history-free three times — `pitestDispatch
-PnoMutationHistory` twice, then `pitestDispatchBaselinePrune`'s own write-boundary
run — under 1-minute load averages of 21–27; line coverage of the mutated classes was
then 218/220, the two uncovered lines being `ResponseUtil`'s private constructor,
which nothing calls and which generates no mutant. The prune removed the last
accepted row and the plugin removed the file with it.) The review run's input
identity differs from the 142-mutant run's, so the plugin reset the timeout-quiet
counter and the prune-preview matches with it — state-reset notices, not findings;
there is no timeout member to retire and no accepted row to prune.

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

One case waits on real time, and it is the only one:
`anIdleConnectionIsClosedAfterTheIdleTimeout` builds a server through the builder's
`idleTimeout` knob at 200 ms, opens a socket, sends nothing and reads EOF. It waits
for an event (the server's close), never sleeps, and its 2 s socket bound is the
emergency exit: ten times the timeout, and inside PIT's margin — the recorded
duration (206 ms in the clean run's coverage phase) × 1.25 + 4000 ms ≈ 4.3 s — so a mutant that
never closes fails as `SocketTimeoutException` at 2 s, an assertion-level kill, not a
`TIMED_OUT`. It also asserts the EOF did not come *early*: the server's clock and the
test's are one `System.nanoTime`, and the deadline is armed only once the connection
exists, so `elapsed >= 200 ms` is a causal lower bound, not a timing guess. Every
other idle-timeout property is pinned on the advanced clock in `NettyPipelineTest`,
which is where PIT's time-ordered prioritiser runs first.

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

- `NettyRequestGate.refusal` — Netty's `HttpUtil.isUnsupportedExpectation` is
  package-private, so the gate restates the aggregator's rule itself: one compound guard
  (`no Expect || decoder failure || version below HTTP/1.1` → not refused), then
  "not `100-continue`" → 417, then `Content-Length > maxContentLength` → 413. Each guard
  and each comparison has its own killer rather than an equivalence argument: the missing
  `Expect` by every routed request (a null `contentEqualsIgnoreCase` reads as a refusal), the
  decoder failure by `aMalformedHeadIsRefusedWith400WhateverItExpects`, the version by
  `anExpectationOnAnHttp10RequestIsIgnored` (both the `<` boundary and the always-refuse
  direction), the length by `aContinueExpectationAtTheLimitIsInvited` (the `>` boundary: a
  body at the limit is invited) against the 413 cases.
- `NettyRequestAggregator.newContinueResponse` — no "defensive" throw for a head the gate
  should have refused: the gate refuses first, so such a block could only ever read
  `NO_COVERAGE`, which the doctrine forbids accepting. The override's one branch is the
  decoder-failure guard, both directions killed (never-null by
  `aMalformedHeadIsRefusedWith400WhateverItExpects` — a `100` ahead of the `400`;
  always-null by `anInterimResponseCompletesNothing` and `aContinueExpectationAtTheLimitIsInvited`);
  that the gate refuses first is what the class comment records, and the reproduction
  tests would fail if it stopped (the aggregator's own 417 would fire the reset late again).
- `ResponseUtil.emptyResponse` — the framed bodiless response (`Content-Length: 0`, RFC
  9112 §6.3) was built inline by the aggregator's `413` and the controller's pre-flight
  `200`, and would have been a third time by the gate's refusals; it is one site now, whose
  `VoidMethodCallMutator` on `setContentLength` is killed by
  `corsPreflightIsContentLengthDelimited`, `anOversizedRequestIsRefusedInOrderThenClosed`
  and the refusal cases' `content-length: 0` assertions alike.
- `NettyRequestGate.checkIdle` / `scheduleIdleCheck` — the idle check re-arms itself
  for the time still to run (`remaining = timeout - (now - lastActivity)`), closing
  when `remaining <= 0`. The `ConditionalsBoundaryMutator` on that comparison (`< 0`)
  re-arms for a delay of zero at the exact deadline, and on the embedded loop a task
  scheduled for the current instant is polled again in the same `runScheduledTasks`
  drain — an infinite loop inside Netty that no fixture bound can reach, i.e. a
  `TIMED_OUT` under a frozen clock, not a kill. The re-arm is therefore floored at one
  nanosecond in `scheduleIdleCheck` (`Math.max(delayNanos, 1L)`, a static call the
  enabled mutators leave alone), which turns that mutant into an observable "still open
  at the deadline" that `anIdleConnectionIsClosedOnceTheTimeoutElapses` fails by
  assertion. The other mutant this set produces on the comparison is
  `RemoveConditionalMutator_ORDER_IF` (always close), killed by
  `aRequestWhoseBodyStopsArrivingIsIdle`; the always-re-arm direction
  (`RemoveConditionalMutator_ORDER_ELSE`) is not generated under this mutator set —
  the fresh report carries none anywhere in the population — and is pinned by test
  regardless: hand-forcing the branch to `false` fails
  `anIdleConnectionIsClosedOnceTheTimeoutElapses` and the socket case, and would also be
  a zero re-arm the floor converts. The floor is unreachable in unmutated code (the
  else-branch's `remaining` is positive) and harmless on a real loop.
- `NettyRequestGate.checkIdle`'s exemption is `paused()` alone (`inFlight &&
  bodyComplete`: a fully received request awaiting its response), not
  `paused() || !queued.isEmpty()`: nothing can be queued while nothing is in flight
  (the drain in `completed` stops only at a request in flight or an empty queue),
  so a queue term would be a compound condition whose sibling mutants are
  equivalent by that invariant. The property it would have stated — a queued
  pipelined request keeps the connection alive — is pinned by
  `aQueuedRequestKeepsTheConnectionAlive`. Before the 2026-09-13 review the test was
  `inFlight` alone, which exempted a request whose head had arrived and whose body had
  stopped — a client sending a head and nothing more held its connection for ever,
  writing nothing (the slowloris shape; found by review with a socket probe at a
  300 ms knob: a head-only `POST`, an unanswered `100 Continue` and an unterminated
  chunked body all still open after 3 s while a silent connection closed at 328 ms).
  The regression tests below failed against that code before the one-token fix.
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

- The expectation refusal (2026-09-13, the P2 review finding). Oracle: RFC 9110 §10.1.1
  and Netty's own `HttpObjectAggregator` contract — an unsupported `Expect` is 417, an
  `Expect: 100-continue` announcing a body over `maxContentLength` is 413, an `Expect` on
  HTTP/1.0 is ignored — plus RFC 9112 §9.3.2 for the ordering. The defect: refusing fires
  `HttpExpectationFailedEvent`, on which `HttpObjectDecoder.userEventTriggered` resets a
  decoder that is mid-body (`READ_FIXED_LENGTH_CONTENT`, `READ_VARIABLE_LENGTH_CONTENT`,
  `READ_CHUNK_SIZE`); behind the gate the aggregator's refusal reached the codec at the
  refused request's *turn*, when the codec could be mid-body of a later request, whose
  tail was then parsed as a request line and never answered (the review's `EmbeddedChannel`
  differential: three pipelined requests, the second refused, the third's body split across
  reads; the same input without `Expect` served all three). The gate now decides the
  refusal in `channelRead`, where the codec still stands on the head, fires the event
  there and answers in turn. Pinned in process by
  `aRefusedExpectationLeavesTheNextRequestDecodable` (the reproduction — 200, 417, 200
  with the whole body — which failed against the old code with an empty wire after the
  tail; it also kills the `NakedReceiverMutator` on `admit`'s `fireUserEventTriggered`,
  since without the reset the codec reads the next request's first bytes as the refused
  body), `aBodySentAfterAnUnsupportedExpectationIsReadAsTheNextRequest` (the review's
  exact shape, body on the wire: Netty's own outcome — those bytes read as a request line
  and answered 405, no handler run, the connection in step for the next request),
  `anOversizedContinueExpectationIsRefusedInOrderThenClosed` (413 in turn with
  `Connection: close` and `Content-Length: 0`, no `100`, then closed, the queue released,
  nothing logged), `aRefusedRequestFirstOnAConnectionIsAnsweredAtOnce` (417 with nothing
  in flight, reads on, no `Connection` header, and the refusal is idle activity: open one
  tick short of a timeout after it, closed at it — with the 413 cases this kills both
  directions of `answer`'s close conditional and the `NakedReceiver` on its `closeAfter`),
  `anOversizedContinueExpectationFirstOnAConnectionIsRefusedAndClosedAtOnce`,
  `aBodilessRefusedRequestIsAnsweredInOrderAndTheConnectionContinues` (the codec's
  `EMPTY_LAST_CONTENT` dropped; 200, 417, 200),
  `theBodyPartsOfARefusedRequestAreReleased` (a `DefaultLastHttpContent` handed over at
  the codec's seam while the refusal is still queued reads `refCnt` 0 at once: the
  never-discard direction of `channelRead`'s guard is a defensive ownership invariant, not
  a wire property — in production the only thing the codec can emit for a refused head is
  its `EMPTY_LAST_CONTENT` singleton, whose release is a no-op and which the aggregator
  would drop unseen with nothing in aggregation, so the synthetic part at the codec's
  context is the deliberate seam, and a Netty upgrade should re-check that reachability
  claim), `theRequestsCloseIsHonouredOnARefusal` (the request's close framed on the 417,
  nothing logged), `anExpectationOnAnHttp10RequestIsIgnored`,
  `aContinueExpectationAtTheLimitIsInvited`, `aMalformedHeadIsRefusedWith400WhateverItExpects`
  (the gate's decoder-failure guard, a 417 in place of the 400; the aggregator's, a 100
  ahead of it; and — no mutant, but the guard's *place* in the rule — a `Content-Length`
  the codec refused to normalise, `abc` and a value past `long`, beside
  `Expect: 100-continue`: still the 400 and nothing logged, where a gate that parsed that
  length before the guard would throw `NumberFormatException` out of `channelRead`, the
  controller's 500 and an `ERROR` record) and
  `aMalformedHeadAnnouncingAnOversizedBodyIsRefusedWith413WhateverItExpects` (the same
  malformed head over the limit is the aggregator's 413 and close with no `Expect`, `foo`
  and `100-continue` alike — what the contract promises is independence from the `Expect`,
  not the 400 in every case); and over sockets by `pipelinedExpectationFailureKeepsRequestOrder`,
  `pipelinedOversizedRequestKeepsRequestOrder`, `expectContinueIsAnsweredBeforeTheBodyIsSent`
  and, since the review of the fix, `aRefusedExpectationLeavesThePipelinedRequestBehindItDecodable`
  (the reproduction shape end to end: a refusal with no close behind a blocking route and a
  third request whose body is written in two halves — 200, 417 with `Content-Length: 0`
  and no `Connection` header, 200 with the whole body — the shape the in-process
  reproduction was observed to hang on, which over a socket would fail on the 2 s read
  bound; not itself observed against the old design) and `anOversizedContinueExpectationIsRefusedAndClosed`
  (the deliberate divergence from Netty's continue-path 413, which carries no `Connection`
  header and keeps the connection: 413, `Content-Length: 0`, `Connection: close`, EOF).
  `forward`'s `Refusal` branch removed sends the marker down the pipeline, where nothing
  accepts it and the request is never answered — every 417 case; `answer`'s `write` and
  `flush` dropped leave the wire empty in process and the socket case on its 2 s bound.
- The framing of a head the codec could not parse (2026-09-13, the review of the refusal
  fix). Oracle: RFC 9112 §9.6 — a server about to close sends `Connection: close` on its
  final response. The defect predates the refusal fix but that fix made it a routine
  outcome of a documented contract (a refused body sent anyway is read as a request line,
  and an unparsable one is the 400 and a close): for a request line that never parsed the
  codec's stand-in is an invented `HTTP/1.0` head (`HttpRequestDecoder.createInvalidMessage`),
  `begin` took that as the request's version, and `HttpUtil.setKeepAlive` framed for
  HTTP/1.0 *removes* the `Connection: close` the controller's 400 carries — a
  keep-alive-shaped 400 and then a bare FIN, every request pipelined behind the garbage
  discarded by the codec unsignalled. A head whose failure is in the header block keeps its
  real HTTP/1.1 and was never affected, which is why `aMalformedHeadIsRefusedWith400WhateverItExpects`
  and the socket suite's `malformedRequestsAreRefusedAndClosed` (both `NoColon`) were green
  over it. `begin` now frames a decoder-failed head as HTTP/1.1 — what every response here
  is anyway — while a genuinely parsed `HTTP/1.0` request keeps its implicit close. Both
  directions of that conditional (`RemoveConditional` on `begin`): the real-version
  direction by `anUnparsableRequestLineIsRefusedWith400AndAnExplicitClose` (`nonsense`,
  then a request behind it: one 400 with `connection: close`, closed, nothing logged —
  which failed against the fix before the review, the header absent) and
  `anUnparsableLineMadeOfARefusedBodyIsAnsweredAndClosed` (the same shape reached through
  the refusal contract: 200, 417 without a close, 400 with it, the request behind the
  garbage never answered); the always-HTTP/1.1 direction by `http10PersistsOnlyOnRequest`
  (an HTTP/1.0 close stays implicit, an HTTP/1.0 keep-alive stays acknowledged).
- The idle timeout (2026-09-13). The oracle is split, because the two reference
  backends do not agree past the first case. *A silent connection between requests is
  closed after 30 s*: both — the JDK backend's `idleInterval`
  (`ServerConfig.DEFAULT_IDLE_INTERVAL_IN_SECS = 30`) and Jetty's connector idle timeout
  (`AbstractConnector._idleTimeout = 30000`). *A request whose body has stopped is
  closed*: Jetty alone — `HttpConnection.onIdleExpired` hands the timeout to
  `HttpChannelState.onIdleTimeout`, which fails a pending read (a handler waiting for
  the body gets a `500` and the connection closes: standalone 12.1.12 probe at a 300 ms
  timeout, head-only `POST` closed at 313 ms); the JDK holds it for good
  (`DEFAULT_MAX_REQ_TIME = -1`; this repo's JDK backend probed at
  `-Dsun.net.httpserver.idleInterval=1`: a silent connection closed at 1004 ms, a
  head-only `POST` still open at 8 s). *A fully received request awaiting its handler
  is never timed out*: both — the JDK by the same `-1`, and Jetty because
  `onIdleExpired` returns `!handlingRequest` and `onIdleTimeout` only fails a pending
  read or write (standalone probe: a handler blocking 1500 ms under the 300 ms timeout
  was still answered `200`). The gate follows the majority on each: idle is
  `!paused()` and no progress. Every branch of `NettyRequestGate.checkIdle` —
  `paused()` both ways, `remaining <= 0` at the boundary and as `ORDER_IF`, both `-`
  operators of the remaining-time arithmetic — every mutant of `paused()`, and the
  scheduled lambda's `checkIdle` call are pinned in process on the advanced clock:
  `anIdleConnectionIsClosedOnceTheTimeoutElapses` (closed at the deadline, open one
  nanosecond before, nothing written), `aReceivedRequestAwaitingItsResponseIsNeverIdle`
  (open across five timeouts with a blocking route pending, then closed exactly one
  full timeout after the response and not at the next check),
  `aRequestWhoseBodyStopsArrivingIsIdle` (a head and two of four body bytes, then
  silence: open one nanosecond short of a timeout after the last byte, closed at it,
  nothing written, no handler run, nothing logged above `DEBUG`),
  `aContinueNeverFollowedByABodyIsIdle` (the aggregator's `100` is not the client's
  activity), `aProgressingUploadIsNeverIdle` (a chunk one nanosecond before each of
  three deadlines keeps the connection, the completed request then waits for its
  handler across three more timeouts, and the grace after the answer is a full timeout),
  `activityResetsTheIdleDeadline` (a request answered one nanosecond before the
  deadline defers the close by a full timeout — the check that fires with time still to
  run must re-arm), `aQueuedRequestKeepsTheConnectionAlive`,
  `aPeerCloseReleasesTheQueueAndTheIdleCheck` (a close through the pipeline — the
  transport's path, unlike `EmbeddedChannel.close()`, which cancels every scheduled task
  itself — releases the queued body and leaves `runScheduledPendingTasks()` at −1) and
  `noTaskOutlivesAnIdleClosedConnection`. The builder's knob and `System::nanoTime`
  plumbing are pinned end to end by the one real-time case above; the knob's
  validation (`NettyServerBuilder.<init>`, `RemoveConditional` both ways: never refuse
  is killed by `anUnusableIdleTimeoutIsRefusedAtConstruction`, always refuse by every
  test that builds a server) refuses zero, negative, null and nanosecond-overflowing
  timeouts at construction rather than inside `createServer`.
- Three idle-timeout statements generate no mutant and are pinned by test anyway,
  each verified by deleting the statement in place and re-running `NettyPipelineTest`
  (the file restored byte-for-byte afterwards): `NettyServerBuilder.DEFAULT_IDLE_TIMEOUT`
  is a static initializer (PIT filters `<clinit>` code and `INLINE_CONSTS` is not in
  this set), so `NettyConformanceTest.theDefaultIdleTimeoutMatchesTheOtherBackends`
  asserts the 30 s the README promises and `NettyPipelineTest` measures every idle
  property against `DEFAULT_IDLE_TIMEOUT.toNanos()` rather than a private copy; the
  accept-time `lastActivity` in `handlerAdded` (a field store from a non-void call) is
  visible only because the gate's clock has a negative origin — deleted, the first check
  finds an enormous `remaining` and never closes, failing
  `anIdleConnectionIsClosedOnceTheTimeoutElapses` and
  `noTaskOutlivesAnIdleClosedConnection` (under the earlier single-origin clock the same
  deletion was green: at the first deadline `remaining` is 0 for the right value and
  negative for 0, both a close); and the read refresh in `channelRead`, deleted, fails
  `aProgressingUploadIsNeverIdle`, `aRequestWhoseBodyStopsArrivingIsIdle` and
  `aContinueNeverFollowedByABodyIsIdle` (it was unobservable before the review, when a
  read could only ever precede an exempt in-flight state).
- Ordering by construction (RFC 9112 §9.3.2), the 2026-09-12 P1 fix: the gate
  forwards a request head only while nothing is in flight and completes the in-flight
  request on the write of a non-informational response, whoever wrote it. Every
  branch of `channelRead`, `forward`, `paused`, `write` and `completed` is pinned by
  `pipelinedResponsesArriveInRequestOrder`, `pipelinedExpectationFailureKeepsRequestOrder`
  and `pipelinedOversizedRequestKeepsRequestOrder` over sockets, and in process by
  `theNextRequestWaitsForTheFinalResponse` (one release per completion, reads paused
  exactly while a received request waits), `anInterimResponseCompletesNothing` (a
  `100` neither completes nor closes, even on a request that asked to close),
  `aBodyStillArrivingIsReadOn`, `theRequestsCloseIsHonouredOnARefusal` (the 417 waits
  its turn and honours the request's close) and
  `anOversizedRequestIsRefusedInOrderThenClosed`.
- Persistence from both sides (§9.6), the 2026-09-12 P2 fix: `keepAlive =
  request keep-alive && !response Connection: close`, framed by `HttpUtil.setKeepAlive`
  in `NettyRequestGate.write` (for the request's version, or HTTP/1.1 for a head the codec
  could not parse — above) and closed after the write. The handler's close by
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
  `oversizedBodiesAreRefusedWith413`. The `Expect: 100-continue` refusal is no longer
  its sibling here: the gate's `413` (above) carries the same headers and close.
- `ResponseUtil.closeAfter`'s `Connection: close` (one site shared by the malformed
  400, the `exceptionCaught` 500 and both 413s): `malformedRequestsAreRefusedAndClosed`,
  `errorEscapingANonBlockingHandlerIsAnsweredAndLogged` and the 413 cases above all
  read to EOF.
- `NettyController.exceptionCaught`'s client-abort branch (a
  `PrematureChannelClosureException` is the peer leaving mid-request, logged at
  `DEBUG` with nothing written): both directions by
  `aClientLeavingMidRequestIsNotAServerFailure` (no `SEVERE`, one `FINE` carrying the
  throwable, an empty wire) against `errorEscapingANonBlockingHandlerIsAnsweredAndLogged`
  (a real `Error` still answered 500 and logged at `ERROR`).
- `Expect: 100-continue` before the body (RFC 9110 §10.1.1): `expectContinueIsAnsweredBeforeTheBodyIsSent`
  sends nothing past the head until the `100` has arrived; in process,
  `anInterimResponseCompletesNothing` and `aContinueExpectationAtTheLimitIsInvited`.
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
  for an `HTTP/1.0` request, and `anUnparsableRequestLineIsRefusedWith400AndAnExplicitClose`
  that the codec's invented `HTTP/1.0` for an unparsable line decides neither the reply
  version nor, since the review above, its `Connection` framing.

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

Since the idle timeout the suite's slowest covering test is
`anIdleConnectionIsClosedAfterTheIdleTimeout` — 206 ms in all three 2026-09-13 history-free
runs, against the plugin's 250 ms threshold, and real wall-clock time by construction (a
200 ms knob waited out for an EOF), not work. No coverage-phase advisory fired in
either run, but that case is the first thing to look at if this suite ever shows a
load-dependent `TIMED_OUT`. History: before it, the advisory named
`absentHostBindsAllInterfaces` at 246–251 ms (two servers — a `null` host and a blank
one — and two `HttpClient`s), which remains the second candidate.

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
The idle timeout (2026-09-13) added a third shape and refactored it out before it
was observed: a re-arm for zero delay under the frozen test clock is an infinite loop
inside `EmbeddedEventLoop.runScheduledTasks`, which no fixture bound reaches; the
one-nanosecond floor in `NettyRequestGate.scheduleIdleCheck` ("Refactored out", above)
makes the boundary mutant fail by assertion instead. Both history-free runs on the
idle-timeout code (142 mutants before the review, 144 after it), the
expectation-refusal run (178) and its review-application run (180) read zero `TIMED_OUT`. Any future `TIMED_OUT` in this suite is, by
construction, a covering test that exceeded its bound *and* PIT's margin, a head-only
response an `HttpClient` case was left to wait on, or a scheduled task re-armed for
the instant it runs in: a `SURVIVED`↔`TIMED_OUT` flip of a row that should already be
argued here, or a `KILLED`↔`TIMED_OUT` finite race to be repaired with a wire or
clock assertion — never a liveness argument, since every hang this suite can produce
is bounded.

One trap worth naming: a history-assisted run (`pitestDispatch` without
`-PnoMutationHistory`) on a machine whose `.pitest-history/` predates the 2 s
retiming carries the first pass's `TIMED_OUT` verdicts forward — PIT's
incremental analysis assumes a mutant that timed out still does while its class
is unchanged, and never re-executes it — and prints them as "not in the audited
set". Observed 2026-09-12: a `[history]` run finished in 11 s while reporting 29
timed-out instances, which cannot both be true. That preview is check-only by
the plugin's own rule and no record decision may come from it; delete the
machine-local history or run history-free to see the real state.
