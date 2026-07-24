# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. Full policy lives in sava-build's
`HARDENING.md`.

Never refresh with `-PupdateMutationBaseline` just to make the build pass:
kill the mutant, refactor it out of existence, or record its equivalence
reason below.

## dispatch suite — no accepted mutants (since 2026-07-24)

Registered when `JdkController` gained real routing logic (the shared
`HandlerMap` dispatch that fixed jdk-context prefix matching). The covering
tests are real socket round trips (`JdkConformanceTest`,
`JdkPostHandlerTest`), so the suite runs slower per mutant than an
in-process one and carries 7 `TIMED_OUT` mutants (socket-wait conversions),
observed detected in both solo and `qualityGate` runs — not unioned into
the baseline.

The error-log `VoidMethodCallMutator`s (`JdkController.handle`,
`JdkQueryHandler`'s executor task, `initRestServer`'s create-failure log)
were killed 2026-07-22: the failure-path tests capture the JUL records and
assert the thrown exception is logged — "failures are never silent" is
pinned, not accepted.

The wildcard-bind family (`initRestServer` 34, both skip-directions) was
accepted 2026-07-22 as "distinguishable only from a second network
interface" — falsified 2026-07-24: `startOnAnOccupiedPortThrows` occupies
the requested `localhost` address, so binding the wildcard instead dodges
the conflict and the expected bind failure never happens. The occupied
port is the second observer the acceptance said did not exist; the
baseline is now empty — keep it that way.
