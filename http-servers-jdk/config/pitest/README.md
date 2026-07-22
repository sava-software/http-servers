# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. Full policy lives in sava-build's
`HARDENING.md`.

Never refresh with `-PupdateMutationBaseline` just to make the build pass:
kill the mutant, refactor it out of existence, or record its equivalence
reason below.

## dispatch suite (2 keys, both `SURVIVED`) — seeded 2026-07-22

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

- `JdkServerBuilder.initRestServer` 34 (`EQUAL_IF`/`EQUAL_ELSE`, one
  direction each): forcing the host-absent branch binds the wildcard
  address instead of the requested host. The wildcard serves a superset
  that includes loopback, so every in-process client still connects;
  distinguishing it requires probing a second network interface, which is
  environment-dependent. The other two directions — forcing the host
  branch on a null/blank host — throw and are killed by
  `absentHostBindsAllInterfaces`.
