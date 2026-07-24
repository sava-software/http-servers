# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants against the accepted baseline in `<suite>-accepted.csv`
and **fails on anything new**. Full policy lives in sava-build's `HARDENING.md`.

## dispatch suite (15 keys / 15 rows: 12 survived, 3 no_coverage) — seeded 2026-07-22

The 2026-07-24 canonical-routing contract added the compliance-backstop
family below (the only `NO_COVERAGE` rows in the suite).

Covering tests are real socket round trips (`JettyConformanceTest`,
`JettyPostHandlerTest`). The suite carries 6 `TIMED_OUT` mutants
(socket-wait conversions), and the handled-flag family below **flaps between
`SURVIVED` and detected across runs** — the baseline holds the union of
observed states, so quiet runs report stale entries rather than failing;
that is expected and safe.

Measured 2026-07-24 (`pitestModeSnapshot` solo + gate, `pitestModeCompare`):
two rows flipped across modes, both already insured — `JettyQueryHandler` 43
(gate=KILLED, solo=SURVIVED) and `JettyServerBuilder` 29 (same directions;
the "explicit documentation" acceptance is also load-flappy). Zero uninsured
flips anywhere in the repo. **Removal criterion for the union rows** (per
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

- **Handled-flag family** (`JettyController` 67/79/82/88,
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
- `JettyController` 36 (`EQUAL_IF` on the blank-ACRM check): treating a blank
  `Access-Control-Request-Method` as a pre-flight looks up method `" "`, which
  no handler map contains — the same 405 + Allow the non-pre-flight path
  returns. The non-blank contract itself is pinned by
  `blankRequestMethodHeaderIsNotAPreflight`.
- `JettyController` 70 (`EQUAL_IF` on `origin != null`): forcing the branch
  with a null origin makes `put(ACCESS_CONTROL_ALLOW_ORIGIN, null)` — a
  header *remove*, i.e. a no-op. The divergent sub-case (a pre-flight
  without an Origin header) has no well-defined semantics to pin.
- `JettyController` 86 (`setStatus(500)` removal in the catch):
  `callback.failed(throwable)` on the next line produces the same 500.
- **Compliance-backstop family** (`JettyController` 52 `EQUAL_ELSE` on the
  `badRequest()` check, 56–58 `NO_COVERAGE` in the 400 branch): the shared
  `HandlerMap` refuses ambiguous paths (encoded separators, encoded dot
  segments, double-encoding, empty segments — see core's `PathCanonicalizer`),
  but Jetty's own `UriCompliance.DEFAULT` rejects every such target before
  this handler runs — measured 2026-07-24: the whole ambiguous conformance
  battery, literal backslash included, answers 400 from Jetty's layer. The
  branch is defense in depth for a future Jetty default change; unreachable
  through any socket harness by construction. What would reach it: an
  in-process controller harness with a faked `Request`/`Response` pair (the
  named escape for all four rows). The jdk and fusionauth twins of this
  branch are live and killed by `ambiguousPathsAreRefused`.
- `JettyServerBuilder` 29 (`setSendXPoweredBy(false)` removal): the flag's
  default is already false; the call is explicit documentation. (Its
  `setSendServerVersion` sibling defaults *on* and is killed.)

Jetty's `HttpFields.Mutable.put` returns `Mutable` rather than `void`, so
`VoidMethodCallMutator` never fires on a header write. The suite therefore
runs `STRONGER,EXPERIMENTAL_NAKED_RECEIVER` (trialed 2026-07-22: +10 mutants,
8 killed immediately, 2 exposed the untested `Content-Type` on 404/405 error
bodies — killed by `errorResponsesAreJson`). Header writes are now
expressible; the duplicate pre-flight write removed earlier had to be found
by reading precisely because they were not.
