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
verified stable solo and multi-suite the same day. Rows not listed under a
triage heading below are **untriaged debt made explicit, not acceptance** —
the kill/triage pass is the next planned work.

## x402 suite (18 keys: 14 survived, 4 no_coverage)

### Triaged equivalent (carried from the 2026-07-17 pass)

**Error-funnel-redundant guards** — removing the guard reaches code that
throws, and the surrounding `try/catch` maps the throw to the *same* error
code the guard returns, so no caller can tell the difference:

- `SvmExactVerifier.verify` 45 (`RemoveConditionalMutator_EQUAL_ELSE`): the
  `payload == null || payload.transaction() == null` guard; skipping it
  reaches `Base64.decode(null)` → NPE → the identical
  `INVALID_PAYLOAD_TRANSACTION` response.

### Untriaged debt — `SURVIVED` (judgment calls pending)

The up-front instruction-validation guards in `SvmExactVerifier.verify`
(lines 83/87/93 both mutation directions, 136, 151), the settler's fee-payer
guard (`SvmExactSettler.settle` 91), the settlement-cache expiry comparison
(`SettlementCache.claim` 48), `verifyComputeLimit` 200 and the
`verifyOptionalInstructions` reason-index arithmetic (244). The validation
itself is load-bearing (see `AGENTS.md` — `TransactionSkeleton` resolves
lazily, and the `crash_*` corpus inputs reach these guards), but the fuzz
replay only asserts no-throw, which cannot distinguish these mutants; each
needs either a unit test constructing a distinguishing transaction or a
written equivalence reason here.

### Untriaged debt — `NO_COVERAGE` (mechanical test work, never "equivalent")

- `SvmExactSettler.settle` 78 and 85: the `catch` fallbacks for
  `transactionBytes()` / `deserializeSkeleton` failures — no test feeds the
  settler a malformed payload.
- `SettlementCache.claim` 51: the expired-claim replacement path — no test
  crosses the retention boundary.
- `SvmExactVerifier.verify` 84: the null-`programId.publicKey()` return — no
  covering input.

## handlers suite (5 keys, all `SURVIVED`)

All inside the hand-rolled multi-key `char[]` scan of
`HandlerUtil.parsePublicKeyParams` (lines 30–42): `ConditionalsBoundaryMutator`
off-by-one probes plus the `EmptyObjectReturnValsMutator`/`MathMutator`
variants on the same scan. Documented 2026-07-17 as indistinguishable by a
base58-length input; that claim predates the ratchet and has not been
re-verified per key — treat as untriaged until each row gets a distinguishing
test or a per-key reason here.

Shrinking a baseline is always an improvement; growing one requires a
reason here.
