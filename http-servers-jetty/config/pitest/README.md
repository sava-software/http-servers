# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants against the accepted baseline in `<suite>-accepted.csv`
and **fails on anything new**. Full policy lives in sava-build's `HARDENING.md`.

## dispatch suite (14 keys, all `SURVIVED`) — seeded 2026-07-22

Covering tests are real socket round trips (`JettyConformanceTest`,
`JettyPostHandlerTest`). The suite carries 6 `TIMED_OUT` mutants
(socket-wait conversions), and the handled-flag family below **flaps between
`SURVIVED` and detected across runs** — the baseline holds the union of
observed states, so quiet runs report stale entries rather than failing;
that is expected and safe.

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
(`throwingHandlerFailureIsLogged`), and `setSendServerVersion`
(`identifyingServerHeadersAreSuppressed`).

- **Handled-flag family** (`JettyController` 67/79/82/88,
  `JettyQueryHandler.handle` 43, `JettyCachedJsonResponseHandler.handle` 28):
  mutants on the boolean a `Handler.handle` returns. Every return sits after
  the response is committed (`Content.Sink.write` / `response.write` /
  `callback.succeeded()`), and Jetty ignores the handled flag once the
  response is committed — the wire response is identical either way. These are
  the rows observed to flip under load.
- **Wildcard-bind family** (`JettyServerBuilder.initRestServer` 34 both
  skip-directions, 35 `setHost` removal): not setting the connector host
  binds the wildcard address, a superset that still serves loopback clients;
  distinguishable only from a second network interface.
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
- `JettyServerBuilder` 24 (`setVirtualThreadsExecutor` removal): thread
  placement only — wire-invisible, and asserting it would couple the test
  to Jetty thread-pool internals.
- `JettyServerBuilder` 29 (`setSendXPoweredBy(false)` removal): the flag's
  default is already false; the call is explicit documentation. (Its
  `setSendServerVersion` sibling defaults *on* and is killed.)

Note that Jetty's `HttpFields.Mutable.put` returns `Mutable` rather than
`void`, so `VoidMethodCallMutator` never fires on a header write here. Header
writes are therefore invisible to this suite's mutator set — the duplicate
pre-flight write removed above was found by reading, not by a survivor.
