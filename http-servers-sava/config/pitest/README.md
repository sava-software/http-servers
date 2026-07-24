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

Both suites were fully triaged 2026-07-22: every remaining key has a reason
below; there is no untriaged debt. The 2026-07-22 passes killed 6 keys (settlement-cache
expiry branch and settler decode path via refactor + tests, the verifier's
overrun-guard removal, the handlers comma-scan arithmetic, and the
authority/source fee-payer fast path — refactored out as fully subsumed by
the per-account scan below it, which returns the same error and payer) — see
`SvmExactVerifierTest`'s corruption tests, `SvmExactSettlerTest`'s malformed
payload tests, and `HandlerUtilTests.parseKeyListIgnoresCommaInEarlierParam`.

Both suites run `STRONGER,EXPERIMENTAL_NAKED_RECEIVER` since the scripted
`pitestMutatorTrial` re-measure 2026-07-24 (+57 mutants on x402, +1 on
handlers — all killed by existing tests; the JSON builders and byte-array
slicing are receiver-returning calls the default set cannot express).

## x402 suite (13 keys / 14 rows: 12 survived, 2 no_coverage)

`verify` 93 holds two sibling `ORDER_IF` mutants — one row per sibling since
the 2026-07-24 multiset upgrade; sibling rows are identical text and
deliberately carry no `# label` (notes key on row text).

Context that drives most of the triage, established empirically 2026-07-22
(see the corruption tests in `SvmExactVerifierTest`): `TransactionSkeleton`
resolution is *asymmetric*. An out-of-range **program** index throws
`ArrayIndexOutOfBoundsException` inside `parseInstructions` (eagerly caught
by `verify`'s catch → `TRANSACTION_COULD_NOT_BE_DECODED`), while an
out-of-range **account** index resolves silently to a `null` account, and a
corrupted data length yields an overrunning slice without throwing. The
committed `crash_*` corpus inputs pin both lazy shapes.

### Error-funnel-redundant guards — removal reaches code whose failure maps to the identical response

- `SvmExactVerifier.verify` 45 (`EQUAL_ELSE`): skipping the
  `payload == null || transaction() == null` guard reaches
  `Base64.decode(null)` → NPE → the same `INVALID_PAYLOAD_TRANSACTION`.
- `verify` 83 (both directions) and 84 (`NullReturnVals`, `NO_COVERAGE`):
  the null-program guard. A null program is **unreachable** — the parse
  throws first (above) and the catch returns the same error the guard
  would. The line-84 return is therefore uncoverable in-harness; what would
  make it live is sava-core making program resolution lazy the way account
  resolution is. Retained as defense in depth for exactly that case.
- `verify` 87 (`EQUAL_IF` direction): removing the `account == null` check
  sends the reachable null account into `account.publicKey()` — an NPE
  *inside* the try → caught → the identical decode error.

### Unreachable sub-states of live guards

- `verify` 87 (`EQUAL_ELSE` direction): a non-null account with a null
  `publicKey()` never occurs — lazy resolution yields null accounts, never
  null-key accounts. The live direction of this guard is pinned by
  `unresolvableAccountIndexRejected`.
- `verify` 93 (`ORDER_IF` ×2, `ConditionalsBoundary`): the skeleton never
  produces a negative `offset` or `len`, and `offset == 0` is structurally
  impossible (signatures precede the message). The *reachable* failure —
  an overrunning slice — has its guard-removal killed by
  `overrunningDataSliceRejected`.
- `verify` 136 (`EQUAL_IF`): `TransferCheckedIxData.read` returns null only
  for a null/empty array; it is always handed the full transaction bytes.
  The discriminator half of the condition is killed by
  `wrongTransferDiscriminator`.
- `SvmExactSettler.settle` 95 (`EQUAL_IF`): `skeleton.feePayer()` cannot be
  null for a transaction that passed verification (verified transactions
  have a non-empty static account table; probed 2026-07-22 — even a zeroed
  signer-count header still resolves a fee payer). The two live mismatch
  directions are killed by the fee-payer mismatch tests.

### Killable only through a brittle coincidence

- `verify` `verifyComputeLimit` 197 (`ORDER_IF`): removing `len() < 1`
  makes `discriminatorByte` read the byte adjacent to an empty data slice
  (the next instruction's header). Distinguishing that requires pinning a
  serialization coincidence — the adjacent byte happening to equal the
  discriminator — which is a flaky harness by construction. The guard is
  real defense for the raw `data()[offset()]` read.

### Equivalent over the reachable domain

- `verify` `verifyOptionalInstructions` 241 (`MathMutator`): in
  `Math.min(i - 3, unknownReasons.length - 1)`, mutating `length - 1` to
  `length + 1` is identity for the whole reachable domain `i ∈ {3, 4, 5}`
  (rule 1 caps instructions at 6). The `i - 3` → `i + 3` variant is killed
  by `unknownOptionalInstruction`.

### Defensive re-parse

- `SvmExactSettler.settle` 89 (`NullReturnVals`, `NO_COVERAGE`): the
  `deserializeSkeleton` catch after a successful verify of the same bytes
  cannot fire today (verify already deserialized them). Uncoverable
  in-harness; would become live if a future verify overload stopped
  parsing. The decode catch *above* verify is the live path, covered by
  `malformedTransactionPayloadDoesNotSubmit` / `nullPayloadDoesNotSubmit`.

## handlers suite (4 keys / 5 rows, all `SURVIVED`, all in `parsePublicKeyParams`)

Line 42 holds two sibling `ConditionalsBoundary` mutants — one row per
sibling since the 2026-07-24 multiset upgrade; the duplicate rows carry no
`# label` (notes key on row text).

- Lines 30, 34 (`EmptyObjectReturnVals`): returning a fresh empty list
  instead of the `NO_PARAMS` constant — equal but not identical, and the
  API does not promise identity.
- Line 38 (`ConditionalsBoundary`): `end < 0` → `<= 0` differs only at
  `end == 0`, unreachable — `from` is past `indexOfParam` plus the
  parameter text, so an `&` at index 0 cannot be found at or after it.
- Line 42 (`ConditionalsBoundary`, 2 mutants, 1 key): `nextComma == 0` is
  unreachable (the comma search starts a full key-length past `from`), and
  `nextComma == end` is unreachable (the character at `end` is `&` or the
  string end, never a comma). The scan's arithmetic itself is pinned by
  `parseKeyListIgnoresCommaInEarlierParam`, which killed the line-41
  `MathMutator` this pass.

Shrinking a baseline is always an improvement; growing one requires a
reason here.
