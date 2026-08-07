# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline keys
are line-less — `class,method,mutator,status`, with each row's observed line
kept as a trailing `# line` tag that refreshes rewrite (migrated 2026-08-02).
Full policy — the three legal outcomes for a new survivor, determinism
requirements, targeting rules — lives in sava-build's `HARDENING.md`.

Never run a baseline-writer task just to make the build pass:
kill the mutant, refactor it out of existence, or record its equivalence
reason below. Because the key carries no line, edits around a mutated method
churn nothing; when the line-drift advisory names a key here, re-read that
key's argument below against the current code before trusting it — a new
mutant landing at an accepted key inherits the acceptance silently (the
documented same-key swap hole).

Baselines seeded 2026-07-21 (`handlers`, `wiring`) and 2026-07-22 (`server`,
`response`, `logging`, alongside their first unit tests); every entry below
was verified stable solo and multi-suite on its seeding day. All entries are
triaged — there is no untriaged debt.

## Mutator overrides

`handlers`, `logging` and `server` run `STRONGER,EXPERIMENTAL_NAKED_RECEIVER`
(trialed 2026-07-22: +5 and +7 mutants on handlers/logging; re-measured with
`pitestMutatorTrial` 2026-07-24: +1 on server — all killed by existing tests.
Dropped `String` slicing, list building and `StringBuilder.append` chains are
receiver-returning calls the default set cannot express). `wiring` and
`response` fired nothing in either measurement — nothing to enable.

## handlers suite — 1 accepted equivalent (`# empty-value coincidence`)

`HandlerUtil.parseRawParam` line 37, `ConditionalsBoundaryMutator` on the
`to < 0` sentinel: the `substring(from)` and `substring(from, to)` branches
coincide when `to == from` (an empty value either way), so `<` → `<=` cannot
change any result. Triaged 2026-07-17 (in `parseParam` before value decoding
was added 2026-07-22 and structure extraction moved to `parseRawParam`).

The 2026-07-24 canonical-routing contract grew the suite to 160 mutants
(`PathCanonicalizer`, the `HandlerLookup.badRequest()` state and the
canonicalize-first `HandlerMapImpl` lookup, killed by
`PathCanonicalizerTests`, the `HandlerMapTests` canonical/ambiguous cases
and the generated `PathCanonicalizerFuzzSeedReplayTest`); a redundant
empty-segments early return was refactored out rather than accepted during
that pass. The suite carries 3 `TIMED_OUT` mutants (infinite-loop
conversions in the query and path scans). They count as detected and were
observed `TIMED_OUT` across runs — not unioned into the baseline; if one
flips to `SURVIVED`, verify the flip in both modes before adding it.

### Audited timeouts (`handlers-timeouts.csv`)

A timeout detects slowness, not wrongness, so the ratchet cannot see a
weakened covering assertion behind one — the three members are audited by
`class,method,mutator` and each carries its structural cause below. A
timed-out mutant outside the set is a reviewer-stop: identify the cause,
paste the printed row, classify it, then write it here. All three are
`cause:liveness` — each is a non-advancing scan with no path-owned finite
completion, i.e. a real loop rather than the straight-line path the
21.5.25 doctrine refuses as liveness evidence. These are in-process scans, so
no fixture bound is in play at all: nothing here waits on a socket, and the
suite runs no HTTP client. Membership and cause are key-level, so the
`cause:liveness` token claims every sibling under each key; no key here is a
mixed key — that needs two siblings timing out for *different* structural
reasons, not merely a finite sibling sharing the key.
The `# line` values are diagnostic pointers only; source movement never warns,
fails, or needs re-anchoring.

- `PathCanonicalizer.canonicalize` 64 (`IncrementsMutator`) — `i += 2`, the
  skip past the two hex digits of a `%XX` escape, becomes `i -= 2`: the scan
  walks back onto the same `%`, re-decodes it and never reaches the end of
  the path. Killed by wall clock, not by an assertion.
- `HandlerUtil.indexOfParam` 24 (`MathMutator`) — the loop's
  `query.indexOf(param, index + 1)` becomes `index - 1`, so the search
  restarts *before* the match it just rejected and returns that same index
  forever.
- `HandlerUtil.parseIntParams` 106 (`IncrementsMutator`) — `++to`, the step
  past the comma before the next `indexOf(',', from)`, becomes `--to`:
  `from` lands before the comma, the next scan finds the same comma, and the
  value list grows without the cursor advancing.

## wiring suite — no accepted mutants

`wiring-accepted.csv` is empty and the suite runs at 100% (78 mutants).
Keep it that way: any new survivor here is a real gap, not debt.

## response suite — no accepted mutants

`response-accepted.csv` is empty and the suite runs at 100% (9 mutants).
Keep it that way.

The suite also targets `core.request.*` (2026-08-03). That package holds only
the all-abstract `Request` interface, so it adds no mutants and does not move
the count above — but the ownership audit counts it as production code, and
this package's `QueryHandler` (`Request -> HttpResponse`) is its only
production consumer. It is targeted rather than declined so a default method
added to `Request` later is mutated by default; `targetTests` deliberately
stays `core.response.*Test*`, so that first default method is owed a test in
this suite's test scope rather than a silent widening.

## server suite — no accepted mutants

`server-accepted.csv` was retired 2026-08-02 and the suite runs at 100%
(40 mutants; 39 until the `HttpServer.close()` default landed 2026-08-07, killed by
`closeStopsTheServerAndStartsNothing`). Keep it that way.

The registration-breadcrumb `VoidMethodCallMutator`s (`addQueryHandler`,
`addPathHandler`) were killed 2026-07-22 by
`everyRegistrationLogsItsPath`, which captures the JUL records — the
"registrations are observable in ops logs" contract is pinned, not
accepted.

The suite's last accepted row — `HttpServerBuilderFactory.findFirst`
(`NullReturnVals`, `NO_COVERAGE` `# provider-path unreachable in-harness`) —
was killed 2026-08-02 by the probe-and-branch pattern:
`BaseHttpServerBuilderTests.FixtureFactory` is registered in test-resources
`META-INF/services`, so PIT's class-path minions resolve it and
`findFirstResolvesAProviderWhereOneIsVisible` asserts the built builder's
identity, while the module-path `test` task (where that registration is
invisible) asserts the no-provider throw. The old acceptance read
"never commit a mode-dependent harness" as forbidding any world-sensitive
test; the rule forbids mode-dependent *pass/fail*, not assertions that
branch on a `ServiceLoader` probe — both worlds pass deterministically.

## logging suite — 5 accepted entries

The formatting core (`formatPlaceholders`, `stringify`) is package-private
and tested directly; a redundant escape-look-ahead condition
(`i + 2 <= len` subsumed by `i + 1 < len`) was refactored out during
seeding rather than accepted. The remainder:

- `# allocation-size only` — `formatPlaceholders` 61 (`MathMutator`): `len << 2`
  StringBuilder capacity — sizes the allocation, never what is computed.
- `# identical-output` family — `log` 26, `logFormat` 38 and `stringify` 93 below all
  produce byte-identical output through the mutated route.
- `log` 26 (`EQUAL_ELSE`): forcing the null-throwable case through the
  throwable `logp` overload passes `(Throwable) null`, which produces a
  `LogRecord` identical to the message-only overload's.
- `logFormat` 38 (`EQUAL_ELSE`): routing an empty values array into
  `formatPlaceholders` leaves every placeholder intact — byte-identical
  output to the raw-emit fast path it bypassed.
- `# defensive fallback` — `resolveCaller` 51 (`EQUAL_ELSE`): the null-frame fallback fires only if
  every remaining stack frame belongs to the logger class, which no test
  call chain can arrange — there is always a caller frame beneath the
  wrappers. Defensive fallback, retained.
- `stringify` 93 (`EQUAL_ELSE`): forcing non-arrays into the array switch
  lands in its `default -> v.toString()` arm — the same result the
  fast path returns.

The suite also carries 2 `TIMED_OUT` mutants (loop mutations in
`formatPlaceholders`), observed detected in both solo and gate runs — not
unioned into the baseline.

### Audited timeouts (`logging-timeouts.csv`)

Both timed-out rows collapse to one audited member —
`BaseJulLogger, formatPlaceholders, IncrementsMutator` (`cause:liveness`) at
lines 69 and 80 — the two lines are the same structural mistake in the same
scan. The line values are diagnostic only and never need re-anchoring when
`formatPlaceholders` moves.

- 69 is the `i++` that skips the `{` of an escaped `\{`; 80 is the `i++` that
  skips the `}` of a `{}` placeholder. Reversed to `i--`, the loop's own
  `i++` returns the cursor to the character it just consumed, so the same
  token is emitted forever and the `StringBuilder` grows until the watchdog
  fires. Detection here is the clock, not `logFormatSubstitutesBeforeEmitting`
  — soften that assertion and these two would still read as detected.
  Which of the two reads `TIMED_OUT` on a given run is load-dependent (one
  detected outright, 2026-08-03); 2 timed out is the steady state.
- **A third mutant shares this key and must not inherit its argument.** Line
  75 (`sb.append(stringify(values[next++]))`) is the same mutator in the same
  method, but `next` is the *argument* cursor, not the loop cursor: reversing
  it re-reads the previous value instead of spinning, so it is killed
  outright by `logFormatSubstitutesBeforeEmitting` asserting the substituted
  text. It is named here only because the line-less key would admit it
  silently — a `TIMED_OUT` at 75 is **not** covered by the argument above and
  is a reviewer-stop, since nothing about that statement can hang.

  This key is **not** a mixed key under 21.5.25, and an earlier revision of
  this file wrongly said it was (corrected 2026-08-07 by sava-build). A
  `cause:` explains a `TIMED_OUT`; a key becomes unrepresentable only when two
  or more siblings *actually time out* for different structural reasons. Line
  75 never times out, so it asserts no cause and conflicts with nothing — 69
  and 80 time out for one and the same reason, which `cause:liveness`
  describes honestly. What remains is the ordinary key-level blind spot, which
  is exactly why the paragraph above names line 75: the audit is key-level and
  cannot flag it, so a human reading this file has to.
- This key is also the repo's `MEMORY_ERROR` risk: the mutated scan grows the
  `StringBuilder` while the cursor stands still, so it races the heap against
  the watchdog. Liveness authorizes a valid `TIMED_OUT` only — if a
  `MEMORY_ERROR` ever appears here, the fix is to make **every** covering path
  fail deterministically without relying on PIT's test order, or to refactor
  the progress-mutation site out, not to widen the audit.
