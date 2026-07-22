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

## handlers suite — 1 accepted equivalent

`HandlerUtil.parseParam` line 27, `ConditionalsBoundaryMutator` on the
`to < 0` sentinel: the `substring(from)` and `substring(from, to)` branches
coincide when `to == from` (an empty value either way), so `<` → `<=` cannot
change any result. Triaged 2026-07-17.

The suite also carries 2 `TIMED_OUT` mutants (infinite-loop conversions in
the query scan). They count as detected and were observed `TIMED_OUT` in
**both** solo and multi-suite runs — not unioned into the baseline; if one
flips to `SURVIVED`, verify the flip in both modes before adding it.

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

- `HttpServerBuilderFactory.findFirst` 10 (`NullReturnVals`,
  `NO_COVERAGE`): the return is reachable only with a service provider on
  the module path. **Unreachable in-harness**: core ships no provider, the
  gradlex whitebox test module cannot add a `provides` clause, and a
  `META-INF/services` registration would resolve under PIT's classpath
  minions but not under the module-path `test` task — a mode-dependent
  harness. What would reach it: a blackbox integration test module with its
  own descriptor. The throwing path is pinned by
  `findFirstThrowsWhenNoBackendIsOnThePath`; the provider path is exercised
  end-to-end by the adapter modules' round-trip tests.

## logging suite — 5 accepted entries

The formatting core (`formatPlaceholders`, `stringify`) is package-private
and tested directly; a redundant escape-look-ahead condition
(`i + 2 <= len` subsumed by `i + 1 < len`) was refactored out during
seeding rather than accepted. The remainder:

- `formatPlaceholders` 61 (`MathMutator`): `len << 2` StringBuilder
  capacity — allocation-size only, never what is computed.
- `log` 26 (`EQUAL_ELSE`): forcing the null-throwable case through the
  throwable `logp` overload passes `(Throwable) null`, which produces a
  `LogRecord` identical to the message-only overload's.
- `logFormat` 38 (`EQUAL_ELSE`): routing an empty values array into
  `formatPlaceholders` leaves every placeholder intact — byte-identical
  output to the raw-emit fast path it bypassed.
- `resolveCaller` 51 (`EQUAL_ELSE`): the null-frame fallback fires only if
  every remaining stack frame belongs to the logger class, which no test
  call chain can arrange — there is always a caller frame beneath the
  wrappers. Defensive fallback, retained.
- `stringify` 93 (`EQUAL_ELSE`): forcing non-arrays into the array switch
  lands in its `default -> v.toString()` arm — the same result the
  fast path returns.

The suite also carries 2 `TIMED_OUT` mutants (loop mutations in
`formatPlaceholders`), observed detected in both solo and gate runs — not
unioned into the baseline.
