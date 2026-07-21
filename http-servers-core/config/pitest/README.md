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

Baselines seeded 2026-07-21 from the pre-existing survivor population;
verified stable solo and multi-suite the same day.

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
