# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants against the accepted baseline in `<suite>-accepted.csv`
and **fails on anything new**. Full policy lives in sava-build's `HARDENING.md`.

## dispatch suite — seeded 2026-07-22

The suite has no `NO_COVERAGE` rows. The three it had, with a fourth,
`SURVIVED` row on the same branch, formed the `# compliance backstop` family the
2026-07-24 canonical-routing contract added; the family was retired on
2026-10-11 once `OPTIONS *` was shown to reach its branch over a socket (see
"Killed by pinning rather than accepted").

Covering tests are real socket round trips (`JettyConformanceTest`,
`JettyPostHandlerTest`), every request and read bounded at 2 s (see "Fixture
bound" under the audited timeouts), so a dropped write fails its test instead of
timing out. The handled-flag family below **flaps between `SURVIVED` and
detected across runs** — the baseline holds the union of observed states, so
quiet runs report stale entries rather than failing; that is expected and safe.

Measured 2026-07-24 (`pitestModeSnapshot` solo + gate, `pitestModeCompare`):
two rows flipped across modes, both already insured —
`JettyQueryHandler.handle`'s return (gate=KILLED, solo=SURVIVED) and the
`setSendXPoweredBy(false)` call in `JettyServerBuilder.initRestServer` (same
directions; the "explicit documentation" acceptance is also load-flappy). Zero
uninsured flips anywhere in the repo.

**Cycle 2, 2026-07-24 (pre-release check):** a *third* row flipped — the
routed return in `JettyController.handle` (`BooleanFalseReturnVals`),
gate=KILLED / solo=SURVIVED, already insured by its membership in the union;
`pitestModeCompare` reported 0 uninsured flips, 0 unioned now, 1 already
insured. It now carries the same `(flip insurance: …)` parenthetical as the
other two. Which member of the handled-flag family flips is itself
load-dependent — do not read a quiet run on one row as the family settling.
**The 3-quiet-cycle counter is therefore still at 0**: no cycle has yet
observed the family detected in both modes. The `1 stale entries` notice a
gate run prints is this flip, not rot — the row survives in solo mode, so
pruning it makes the next solo run fail the ratchet with an unexplained
`SURVIVED`.

**Removal criterion for the union rows** (per HARDENING.md, written when the
union is written): drop a union row only when its cause is removed — handlers
no longer returning the handled flag after committing the response, or Jetty's
`writeError` no longer only failing the callback of a committed response — or
after 3 consecutive `pitestModeCompare` cycles observe it detected in both
modes; re-measure with the snapshot/compare pair, never prune from a single
quiet run.

On 2026-07-22 `JettyHandler` was collapsed into Jetty's own `Handler` (it had
become an empty marker) and the population dropped from 71 mutants to 61 when
the `handlePreFlight` handler seam was removed: `JettyController` now writes the
pre-flight response itself, so
`JettyHandler.handlePreFlight`, `BaseJettyHandler.allowMethod` /
`ALLOW_GET` / `ALLOW_POST` / `setResponseHeaders`, and `JettyQueryHandler`'s
duplicate header write are all gone, along with their mutants. Handlers no
longer advertise `Access-Control-Allow-Methods` on ordinary responses, which
the CORS spec only defines for pre-flights — `allowMethodsHeaderIsPreflightOnly`
now pins its absence on this backend, Helidon and Netty. The replacement
controller code is covered: both the `Access-Control-Allow-Methods` write and
the `callback.succeeded()` completing the pre-flight are killed.

`BaseJettyHandler` was then removed as well: once its members above were gone
it added nothing over `Handler.Abstract`, whose own `(InvocationType)`
constructor the two remaining handlers now call directly. Its one surviving
member, the `JSON_CONTENT` header field, moved to `JettyController` — the
class that writes it on the 404 and 405 paths.

Killed by pinning rather than accepted: the `OPTIONS` test that picks
`CorsRequest.options` over `CorsRequest.of`
(`preflightHeadersOnNonOptionsRequestsAreIgnored`,
`optionsWithoutRequestMethodIsMethodNotAllowed`; the rule itself is core's
`CorsRequest`, mutated under `pitestHandlers`, and
`preflightWithoutOriginIsNotAPreflight`, `blankOriginIsNotAPreflight`,
`blankOriginIsNotReflected`, `blankRequestMethodHeaderIsNotAPreflight` and
`preflightWithoutRequestHeadersOmitsAllowHeaders` pin this controller's wiring
to it and its two null-put echoes), the error log
(`throwingHandlerFailureIsLogged`), `setSendServerVersion`
(`identifyingServerHeadersAreSuppressed`), and `setVirtualThreadsExecutor`
(`blockingHandlersRunOnTheProvidedExecutor` — jetty 12 dispatches blocking
work on the provided executor deterministically; killed via a recording
executor 2026-07-22). Since 2026-10-11 also the ambiguous-path 400 branch
(`asteriskFormIsAnsweredWithTheAmbiguousPath400`: `OPTIONS *` passes Jetty's
`UriCompliance` and the shared routing refuses it — the socket route the
retired `# compliance backstop` acceptance said did not exist) and the routed
return's `true` direction (`aRoutedHandlerThatDeclinesIsAnsweredWithJettys404`).

- **`# handled-flag family`**: mutants on the boolean a `Handler.handle`
  returns. The members are these `JettyController.handle` returns: the `return
  true` after its own 400/404/405, after the pre-flight and after the catch's
  500, and the `false` direction of the routed `return handler.handle(…)`. They
  also include `JettyQueryHandler.handle` and
  `JettyCachedJsonResponseHandler.handle`, each after its write. Jetty answers a
  `false` with `Response.writeError(…, 404)` (`HttpChannelState`'s handler
  invoker), which on a committed response only fails the callback: a no-op once
  the write has completed it (`ChannelCallback` ignores a second completion),
  and an aborted response while the write is still in flight. The pre-flight's
  `false` is always the no-op, because its `callback.succeeded()` completes the
  exchange before the return, so that member is equivalent rather than racing.
  For every other member the machine's load decides the race, so these are the
  rows observed to flip under load. The routed return's `false` races only
  because the adapter's handlers write before they return. Its `true` direction
  is no member: a routed raw `Handler` that declines, which
  `JettyServerBuilder`'s protected `addQueryHandler` allows, must get Jetty's
  404, and `aRoutedHandlerThatDeclinesIsAnsweredWithJettys404` kills it. The
  catch's `return true` was already a member while the catch failed the
  callback. Since the catch writes the JSON 500 itself (2026-10-10), it races
  like the rest. It was killed in that day's solo history-free run and survived
  under certification the same evening.
- The former wildcard-bind family (the host guard in `initRestServer` skipped in
  both directions, and the `setHost` removal) was killed 2026-07-24 by
  `startOnAnOccupiedPortThrows`: the occupied `localhost` address makes a
  wildcard bind dodge the conflict and skip the expected throw — the second
  observer the acceptance said required another network interface. PIT now
  credits the guard's kill to `theConnectorBindsTheRequestedHost` (added
  2026-08-07), which reads the requested host off the unstarted connector.
- `# null-origin no-op` — was `JettyController.handle`'s `origin != null`
  guard (`EQUAL_IF`): forcing the branch with a null origin made
  `put(ACCESS_CONTROL_ALLOW_ORIGIN, null)` — a header *remove*, i.e. a no-op.
  The guard was refactored out on 2026-10-14: the controller now relies on
  that remove for both of its echoes, with `blankOriginIsNotReflected` and
  `preflightWithoutRequestHeadersOmitsAllowHeaders` pinning it, and a
  pre-flight cannot be read without an origin (`CorsRequest.options`). The
  coordinate has left the population; the row leaves through the Prune
  protocol once two fresh history-free previews agree.
- `# explicit-default doc` — the `setSendXPoweredBy(false)` call in
  `JettyServerBuilder.initRestServer` (its removal): the flag's default is
  already false; the call is explicit documentation. (Its
  `setSendServerVersion` sibling defaults *on* and is killed.)

**Thread-pool budget (2026-09-15).** `initRestServer` sized its pool to one platform
thread per processor, and Jetty's `ThreadPoolBudget` refuses to start a connector unless
the pool's maximum exceeds what its components lease (reserved threads, acceptors,
selectors): no server started on one or two processors, nor on three without an executor.
The builder now raises the pool to one thread beyond the leases it predicts from Jetty's
own heuristics (`leasedThreads`, `maxThreads`). The population moves 73 → 79, every
newcomer killed, no row added: the `leasedThreads` sums and return by
`theLeasePredictionMatchesJettysBudget*` (the oracle is Jetty's lease count after start),
`maxThreads` by its table test, and the `setMaxThreads` removal — equivalent on any host
with four or more processors — by `aServerStartsAndAnswersWhenJettyCountsOneToThreeProcessors`,
which lowers Jetty's `ProcessorUtils` count in-process. The builder sizes from that same
count, so this is the only route by which PIT can see a small host. The real small-host JVMs
(`smallHostTest1..3`, `-XX:ActiveProcessorCount=1..3`) run under `check`, outside PIT's
reach: a forked JVM never loads a mutant.

## Audited timeouts (`dispatch-timeouts.csv`)

The set is empty and armed, so a `TIMED_OUT` mutant in this suite is a
reviewer-stop. Its five members were removed by hand on 2026-10-11, after the
plugin's quiet notice had named all five quiet for three fresh history-free
runs on identical inputs, one under gate load and two solo. Four were one
structural cause, a removed call that leaves Jetty's `Callback` uncompleted,
so the response is never flushed and the client waits on a read:
`JettyController.handle`, `JettyErrorHandler.generateResponse`,
`JettyQueryHandler.handle` and `JettyCachedJsonResponseHandler.handle`. The
fifth was the `initRestServer` host guard, quiet since 2026-08-07 and killed
by `theConnectorBindsTheRequestedHost`, which reads the requested host
straight off the connector.

**Fixture bound: 2 s, and why it is not 10 s.** Every `HttpClient` request and
raw socket read in `JettyConformanceTest`, `JettyPostHandlerTest` and
`JettyThreadBudgetTest` is bounded at 2 s. PIT's per-mutant watchdog fires at the
recorded duration × 1.25 + 4000 ms, so the 10 s bound the suite carried until
2026-10-11 never fired first. A removed write therefore read `TIMED_OUT`: seven
mutants over the four callback keys in the history-free run of 2026-10-10,
detected by the clock rather than by an assertion. Their same-key timeout budget
also kept the stale `# error funnel` row, which no writer task could remove. At
2 s each of those mutants reads `KILLED`. Do not restore the 10 s bound without
re-auditing this file.

## Slow-covering-test advisory (new in sava-build 21.5.25)

Every scoped run of this suite on 2026-08-07 printed the plugin's new
coverage-phase advisory: `JettyConformanceTest.throwingHandlerFailureIsLogged`
took 354–373 ms against a 250 ms threshold. It is an advisory, not a failure —
the plugin is explicit that the measurement "does not prove the test covers a
target mutant or prescribe a remedy". Recorded here because it is the cheapest
standing pointer at *why* this suite raced the watchdog before its 2 s bound:
it is the slowest covering test in the repo's slowest suite, and PIT repays
that wall-clock cost once per mutant it covers. Retiming it is the first thing
to try if a load-dependent `TIMED_OUT` in this suite ever needs repair rather
than audit.

Jetty's `HttpFields.Mutable.put` returns `Mutable` rather than `void`, so
`VoidMethodCallMutator` never fires on a header write. The suite therefore
runs `STRONGER,EXPERIMENTAL_NAKED_RECEIVER` (trialed 2026-07-22: +10 mutants,
8 killed immediately, 2 exposed the untested `Content-Type` on 404/405 error
bodies — killed by `errorResponsesAreJson`). Header writes are now
expressible; the duplicate pre-flight write removed earlier had to be found
by reading precisely because they were not.
