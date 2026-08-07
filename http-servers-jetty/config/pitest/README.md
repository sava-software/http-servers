# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants against the accepted baseline in `<suite>-accepted.csv`
and **fails on anything new**. Full policy lives in sava-build's `HARDENING.md`.

## dispatch suite (15 keys / 15 rows: 12 survived, 3 no_coverage) — seeded 2026-07-22

The 2026-07-24 canonical-routing contract added the compliance-backstop
family below (the only `NO_COVERAGE` rows in the suite).

Covering tests are real socket round trips (`JettyConformanceTest`,
`JettyPostHandlerTest`). The suite carries 6 `TIMED_OUT` mutants
(socket-wait conversions, audited in `dispatch-timeouts.csv` — causes at the
end of this file), and the handled-flag family below **flaps between
`SURVIVED` and detected across runs** — the baseline holds the union of
observed states, so quiet runs report stale entries rather than failing;
that is expected and safe.

Measured 2026-07-24 (`pitestModeSnapshot` solo + gate, `pitestModeCompare`):
two rows flipped across modes, both already insured — `JettyQueryHandler` 43
(gate=KILLED, solo=SURVIVED) and `JettyServerBuilder` 29 (same directions;
the "explicit documentation" acceptance is also load-flappy). Zero uninsured
flips anywhere in the repo.

**Cycle 2, 2026-07-24 (pre-release check):** a *third* row flipped —
`JettyController` 96 (`BooleanFalseReturnVals`), gate=KILLED / solo=SURVIVED,
already insured by its membership in the union; `pitestModeCompare` reported
0 uninsured flips, 0 unioned now, 1 already insured. It now carries the same
`(flip insurance: …)` parenthetical as rows 43 and 29. Which member of the
handled-flag family flips is itself load-dependent — do not read a quiet run
on one row as the family settling. **The 3-quiet-cycle counter is therefore
still at 0**: no cycle has yet observed the family detected in both modes.
The `1 stale entries` notice a gate run prints is this flip, not rot — the
row survives in solo mode, so pruning it makes the next solo run fail the
ratchet with an unexplained `SURVIVED`.

**Removal criterion for the union rows** (per
HARDENING.md, written when the union is written): drop a union row only when
its cause is removed — handlers no longer returning the handled flag after
committing the response, or Jetty ceasing to ignore it — or after 3
consecutive `pitestModeCompare` cycles observe it detected in both modes;
re-measure with the snapshot/compare pair, never prune from a single quiet
run.

On 2026-07-22 `JettyHandler` was collapsed into Jetty's own `Handler` (it had
become an empty marker) and the population dropped from 71 mutants to 61 when
the `handlePreFlight` handler seam was removed: `JettyController` now writes the
pre-flight response itself (mirroring `FusionAuthController`), so
`JettyHandler.handlePreFlight`, `BaseJettyHandler.allowMethod` /
`ALLOW_GET` / `ALLOW_POST` / `setResponseHeaders`, and `JettyQueryHandler`'s
duplicate header write are all gone, along with their mutants. Handlers no
longer advertise `Access-Control-Allow-Methods` on ordinary responses, which
the CORS spec only defines for pre-flights — `allowMethodsHeaderIsPreflightOnly`
now pins its absence on both this backend and FusionAuth. The replacement
controller code is covered: both the `Access-Control-Allow-Methods` write and
the `callback.succeeded()` completing the pre-flight are killed.

`BaseJettyHandler` was then removed as well: once its members above were gone
it added nothing over `Handler.Abstract`, whose own `(InvocationType)`
constructor the two remaining handlers now call directly. Its one surviving
member, the `JSON_CONTENT` header field, moved to `JettyController` — the
class that writes it on the 404 and 405 paths. This shifted
`JettyCachedJsonResponseHandler.handle`'s accepted row from line 27 to 28; the
mutant, its reason, and the 14-key population are otherwise unchanged.

Killed by pinning rather than accepted: the pre-flight detection conditions
(`preflightHeadersOnNonOptionsRequestsAreIgnored`,
`optionsWithoutRequestMethodIsMethodNotAllowed`), the error log
(`throwingHandlerFailureIsLogged`), `setSendServerVersion`
(`identifyingServerHeadersAreSuppressed`), and `setVirtualThreadsExecutor`
(`blockingHandlersRunOnTheProvidedExecutor` — jetty 12 dispatches blocking
work on the provided executor deterministically; killed via a recording
executor 2026-07-22).

- **`# handled-flag family`** (`JettyController` 67/79/82/88,
  `JettyQueryHandler.handle` 43, `JettyCachedJsonResponseHandler.handle` 28):
  mutants on the boolean a `Handler.handle` returns. Every return sits after
  the response is committed (`Content.Sink.write` / `response.write` /
  `callback.succeeded()`), and Jetty ignores the handled flag once the
  response is committed — the wire response is identical either way. These are
  the rows observed to flip under load.
- The former wildcard-bind family (`initRestServer` 34 both skip-directions,
  35 `setHost` removal) was killed 2026-07-24 by `startOnAnOccupiedPortThrows`:
  the occupied `localhost` address makes a wildcard bind dodge the conflict
  and skip the expected throw — the second observer the acceptance said
  required another network interface.
- `# blank-ACRM funnel` — `JettyController` 36 (`EQUAL_IF`): treating a blank
  `Access-Control-Request-Method` as a pre-flight looks up method `" "`, which
  no handler map contains — the same 405 + Allow the non-pre-flight path
  returns. The non-blank contract itself is pinned by
  `blankRequestMethodHeaderIsNotAPreflight`.
- `# null-origin no-op` — `JettyController` 70 (`EQUAL_IF` on `origin != null`): forcing the branch
  with a null origin makes `put(ACCESS_CONTROL_ALLOW_ORIGIN, null)` — a
  header *remove*, i.e. a no-op. The divergent sub-case (a pre-flight
  without an Origin header) has no well-defined semantics to pin.
- `# error funnel` — `JettyController` 86 (`setStatus(500)` removal in the catch):
  `callback.failed(throwable)` on the next line produces the same 500.
- **`# compliance backstop`** family (4 rows): the shared `HandlerMap`
  refuses ambiguous paths (encoded separators, encoded dot segments,
  double-encoding, empty segments — see core's `PathCanonicalizer`), but
  Jetty's own `UriCompliance.DEFAULT` rejects every such target before this
  handler runs — measured 2026-07-24 on Jetty 12.1: the whole ambiguous
  conformance battery, literal backslash included, answers 400 from Jetty's
  layer. Per row: `JettyController` 52 (`EQUAL_ELSE`, `SURVIVED`) skips the
  `badRequest()` check — covered, but no socket request can arrive with an
  ambiguous canonical form, so both branch directions answer identically;
  56–58 (`NO_COVERAGE`) are the 400-writing statements of the branch no
  request enters. The branch is defense in depth for a future Jetty default
  change. **Missing capability** (the named escape for all four rows, and an
  acceptance with an expiry date): constructing a Jetty core `Request` whose
  `HttpURI` carries an ambiguous target without a socket — an in-process
  controller harness with faked `Request`/`Response`/`Callback` (the
  `generateTestSupport` adoption trigger in AGENTS.md). Re-measure the
  UriCompliance claim on every Jetty major/minor bump — the 400-from-Jetty
  measurement is what keeps these four equivalent. The jdk and fusionauth
  twins of this branch are live and killed by `ambiguousPathsAreRefused`.
- `# explicit-default doc` — `JettyServerBuilder` 29 (`setSendXPoweredBy(false)`
  removal): the flag's
  default is already false; the call is explicit documentation. (Its
  `setSendServerVersion` sibling defaults *on* and is killed.)

## Audited timeouts (`dispatch-timeouts.csv`)

Three of the four members are one structural cause: a removed call that leaves
the Jetty `Callback` never completed, so the response is never flushed and the
socket client waits until PIT's watchdog ends the run. The fourth
(`initRestServer` 34) is a bind-path cause argued separately below. Detection
here is the clock, not an assertion — weaken these tests and the timeouts
still read as "detected", which is why membership is audited rather than
counted. The seven rows collapse to four line-less members, all recorded
`cause:liveness` — but see the standing 21.5.25 exception on `initRestServer`
34 below. Membership and cause are key-level, so the `cause:liveness` token
claims every sibling under each key; none of the four is a proven mixed key
today, and a proven mixture would be repaired by splitting the identity into
distinct method keys, never by annotating a line. The line values below are
diagnostic pointers only — moving the write paths never warns, fails, or
requires re-anchoring.

**Fixture bound (recorded per the 21.5.25 rule).** `JettyConformanceTest`'s
requests carry `HttpRequest.timeout(Duration.ofSeconds(10))`. It is not the
claimed oracle for any row here — the argument is PIT's watchdog — and it
cannot fire first, since PIT's per-mutant margin is the recorded duration ×
1.25 + 4000 ms. A bound that cannot fail first contributes no cause evidence
either way; it is recorded so it need not be rediscovered. Note that
`startOnAnOccupiedPortThrows`, the covering test for `initRestServer` 34, uses
no HTTP client at all and therefore has no fixture bound whatsoever.

- `JettyController.handle` 66, 75, 92, 101 (`VoidMethodCallMutator`) — 66 and
  75 drop the `Content.Sink.write` that emits the 404 and 405 JSON bodies
  (the write completes the callback; without it the status is set and nothing
  is ever sent), 92 drops the `callback.succeeded()` that terminates a
  pre-flight answer, and 101 the `callback.failed(throwable)` that terminates
  the 500 path. The sibling write at 58 (the ambiguous-path 400) is *not*
  here: it is `NO_COVERAGE`, socket-unreachable behind Jetty's
  `UriCompliance` — see the `# compliance backstop` family above.
- `JettyQueryHandler.handle` 42 (`VoidMethodCallMutator`) — `response.write`
  removed: status and headers are set, the body never leaves, the callback
  never completes.
- `JettyServerBuilder.initRestServer` 34 (`RemoveConditionalMutator_EQUAL_ELSE`)
  — **not the un-completed-callback cause above.** The guard is
  `if (host != null && !host.isBlank())`; skipping it drops
  `serverConnector.setHost(host)`, so the connector binds the wildcard instead
  of the requested `localhost`. `startOnAnOccupiedPortThrows` occupies
  `localhost:P` and asserts the bind fails, but a wildcard bind dodges that
  conflict, so `start()` succeeds where the test expects a throw — and the
  test holds no reference to the server it never expected to exist, so that
  live acceptor is leaked into the mutation run. Solo the assertion still
  reports first and the mutant reads `KILLED` (verified scoped 2026-08-04,
  16/16); under certification parallelism the leaked acceptor outlives the
  watchdog and it reads `TIMED_OUT`. Admitted 2026-08-04. Killing it in both
  modes means stopping any unexpectedly-started server in that test — the
  named missing capability here, and strictly better than this acceptance.

  **Standing exception under sava-build 21.5.25 (recorded 2026-08-07): this
  row's `cause:liveness` no longer holds.** The new doctrine falsifies the
  admission on two independent grounds. First, the covering path is
  *demonstrated* finite — the 16/16 solo `KILLED` reading above is exactly the
  "demonstrated finite covering-path/watchdog race" the new `cause:harness`
  category names, and such a race "is benign only to baseline arithmetic, never
  certifying evidence"; the prescribed remedy is to repair or retime the
  covering path, not to admit it and not to wait on the liveness-retirement
  rule. Second, liveness may not be admitted while a synchronous state reader
  can expose the defect without waiting, and one plainly can: the mutant's
  whole effect is that the connector binds the wildcard instead of the
  requested host, which the started server's own connector reports
  synchronously — no clock, no socket, no watchdog. The leak itself is the
  contaminated-evidence shape the new isolation bullet describes: a thread
  whose cleanup an assertion failure skipped, diagnosable with
  `-PmutateOnly=…:JettyServerBuilder -PnoMutationHistory` against
  `-PmutateOnly=…:JettyServerBuilder -PisolateMutants`.

  **Fresh 2026-08-07 measurement under 21.5.25, solo and history-free**
  (`-PmutateOnly=…JettyServerBuilder -PnoMutationHistory`): 16 mutants, 15
  killed, **`initRestServer` 34 reads `KILLED`**, and the scope carries zero
  `TIMED_OUT`. The only survivor is line 29, the accepted `# explicit-default
  doc` row. Repeating the same scope with `-PisolateMutants` gives an identical
  result — 15/16, same survivor, zero `TIMED_OUT` — so there is **no
  isolation-only kill and no contaminated evidence** in this scope today: the
  leaked acceptor is real but is not currently changing any verdict. That
  retires the stale 2026-08-04 16/16 as the standing solo evidence and replaces
  it with a measurement taken on the current toolchain.

  **The gate half agrees.** The full twelve-suite `hardeningCertify` of
  2026-08-07 (fresh, history-free, `gitTree d87c754a`) read **both line-34
  siblings `KILLED`**. The suite's six `TIMED_OUT` mutants that run are all in
  the other three audited keys — `JettyController.handle` 66/75/92/101,
  `JettyQueryHandler.handle` 42, `JettyCachedJsonResponseHandler.handle` 26.
  So across solo, isolated and gate load on the current toolchain the finite
  race does not reproduce, and this member is quiet.

  **It is not retired yet, and the reason is mechanical rather than a judgment
  call.** The same certification printed `timeout-retirement stash predates
  fresh-only evidence bound to current inputs — the quiet-run counter resets
  this run` for every suite carrying a timeouts file: 21.5.25 binds quiet-run
  evidence to the input hashes, the plugin SHA is one of them, and the
  21.5.24 → 21.5.25 bump therefore reset every counter to zero. The retirement
  bar is three or more quiet notices over *identical* inputs, so this member
  stands at one. Two further clean certifications on unchanged inputs retire it.

  `theConnectorBindsTheRequestedHost` was added 2026-08-07 as the synchronous
  state reader the doctrine asks you to look for: it reads the requested host
  straight off the unstarted connector, with no socket, no watchdog and nothing
  leaked. Measured honestly, **it does not change the kill attribution** — PIT
  stops at the first killing test and still credits
  `startOnAnOccupiedPortThrows` with both line-34 siblings. Its value is that
  the property now has an oracle that cannot time out.

  **The leak itself is still owed.** Removing it needs a way to stop a started
  server, and `HttpServer` exposes only `start()` — which is why this was a
  *missing capability* and not a quick fix. The candidates are widening
  `HttpServer` with `stop()`/`close()` (a production API addition, weighed
  against it being the first production change since 25.2.0) or restructuring
  `startOnAnOccupiedPortThrows` onto the protected `initRestServer` /
  `setController` / `createServer(RS)` triple so the test owns the Jetty
  `Server` and can stop it, at the cost of no longer driving the public
  `createServer(Executor, String, int)` from this case.

- `JettyCachedJsonResponseHandler.handle` 26 (`VoidMethodCallMutator`) — the
  same shape on the cached-JSON path.

## Slow-covering-test advisory (new in sava-build 21.5.25)

Every scoped run of this suite on 2026-08-07 printed the plugin's new
coverage-phase advisory: `JettyConformanceTest.throwingHandlerFailureIsLogged`
took 354–373 ms against a 250 ms threshold. It is an advisory, not a failure —
the plugin is explicit that the measurement "does not prove the test covers a
target mutant or prescribe a remedy". Recorded here because it is the cheapest
standing pointer at *why* this suite races the watchdog at all: it is the
slowest covering test in the repo's slowest suite, and PIT repays that
wall-clock cost once per mutant it covers. Retiming it is the first thing to
try if a load-dependent `TIMED_OUT` in this suite ever needs repair rather than
audit.

Jetty's `HttpFields.Mutable.put` returns `Mutable` rather than `void`, so
`VoidMethodCallMutator` never fires on a header write. The suite therefore
runs `STRONGER,EXPERIMENTAL_NAKED_RECEIVER` (trialed 2026-07-22: +10 mutants,
8 killed immediately, 2 exposed the untested `Content-Type` on 404/405 error
bodies — killed by `errorResponsesAreJson`). Header writes are now
expressible; the duplicate pre-flight write removed earlier had to be found
by reading precisely because they were not.
