# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. Full policy — the three legal
outcomes for a new survivor, determinism requirements, targeting rules —
lives in sava-build's `HARDENING.md`.

Never refresh with `-PupdateMutationBaseline` just to make the build pass:
kill the mutant, refactor it out of existence, or record its equivalence
reason below. Line numbers are part of the baseline key, so edits to a
mutated file shift entries — confirm the verify task's paired stale/"new"
rows are the shifted old ones before refreshing.

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
paste the printed row, then write it here. The key is line-less, so a *new*
timeout in one of these three method+mutator pairs draws no warning — re-read
the line named here whenever that code changes.

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

## server suite — 1 accepted entry

The registration-breadcrumb `VoidMethodCallMutator`s (`addQueryHandler`,
`addPathHandler`) were killed 2026-07-22 by
`everyRegistrationLogsItsPath`, which captures the JUL records — the
"registrations are observable in ops logs" contract is pinned, not
accepted.

- `# provider-path unreachable in-harness` — `HttpServerBuilderFactory.findFirst` 10
  (`NullReturnVals`, `NO_COVERAGE`): the return is reachable only with a service provider on
  the module path. **Unreachable in-harness**: core ships no provider, the
  gradlex whitebox test module cannot add a `provides` clause, and a
  `META-INF/services` registration would resolve under PIT's classpath
  minions but not under the module-path `test` task — a mode-dependent
  harness. What would reach it: a blackbox integration test module with its
  own descriptor. The throwing path is pinned by
  `findFirstThrowsWhenNoBackendIsOnThePath`; the success path is pinned
  end-to-end (2026-07-24) by hello's `findFirstDiscoversABackend`, which
  runs `findFirst` with all three providers on the path — the row stays
  `NO_COVERAGE` only because that test lives outside this suite.

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

Both rows collapse to one audited member —
`BaseJulLogger, formatPlaceholders, IncrementsMutator` at lines 69 and 80 —
because the audit key is line-less; the two lines are the same structural
mistake in the same scan, and a new timeout in that method+mutator will not
warn, so re-read both lines when `formatPlaceholders` changes.

- 69 is the `i++` that skips the `{` of an escaped `\{`; 80 is the `i++` that
  skips the `}` of a `{}` placeholder. Reversed to `i--`, the loop's own
  `i++` returns the cursor to the character it just consumed, so the same
  token is emitted forever and the `StringBuilder` grows until the watchdog
  fires. Detection here is the clock, not `logFormatSubstitutesBeforeEmitting`
  — soften that assertion and these two would still read as detected.
