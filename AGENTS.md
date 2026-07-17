# http-servers

Java 25 multi-module library providing a small HTTP server abstraction (`http-servers-core`)
with pluggable backends (`http-servers-jdk`, `http-servers-jetty`, `http-servers-fusionauth`),
a demo module (`http-servers-hello`), and an x402 payment gate for the Solana `exact` scheme
(`http-servers-sava`). Built with the shared `software.sava.build` Gradle plugin (same plugin
family as the `sava` repo); the `hardening` convention plugin provides PIT mutation testing and
Jazzer fuzzing.

## Testing

`./gradlew build` compiles and runs the JUnit 5 suites for every module. The security-relevant
surfaces additionally carry PIT mutation suites and Jazzer fuzz harnesses, configured through
the `hardening {}` block in each module's `build.gradle.kts`. New survivors must be either
killed with a test or classified equivalent with a reason; new fuzz findings must be fixed and
the crashing input committed to the seed corpus as a regression.

### http-servers-core — request routing (`software.sava.http_servers.core.handlers`)

The first code to touch every untrusted request: query-string parsing (`HandlerUtil`) and
method/path resolution (`HandlerMapImpl`, `HandlerLookup`).

- `./gradlew :http-servers-core:pitestHandlers` — PIT over the three routing classes against
  `handlers.*Test*`. Baseline 2026-07-17: 68 mutations, **99% killed** (1 survivor:
  a `ConditionalsBoundaryMutator` on the `to < 0` sentinel in `parseParam`, equivalent — the
  `substring(from)` and `substring(from, to)` branches coincide when `to == from`). Tests live
  in `HandlerUtilTests` and `HandlerMapTests`.

Note (fixed 2026-07-17): `HandlerUtil` parameter lookup used `query.indexOf(param)`, which
matched a parameter name as a substring of another (`page=` inside `perpage=`) and did not
require the match to sit at a parameter boundary. Replaced with `indexOfParam`, which only
accepts a match at the query start or immediately after `&`. The same substring bug existed in
`http-servers-sava`'s `handlers.HandlerUtil` (public-key params) and was fixed the same way.
No fuzz harness here: a query-string splitter's boundaries all live in tiny inputs the unit
tests reach directly.

### http-servers-sava — x402 payment gate (`software.sava.http_servers.sava.x402`)

The module's threat model is a client-controlled `X-PAYMENT` header (Base64 → JSON → a
partially-signed Solana transaction) that a facilitator would co-sign and submit. A parsing
defect or a verification rule the code fails to enforce is a payment the facilitator wrongly
sponsors, so this is the most heavily tested surface.

- `./gradlew :http-servers-sava:pitestX402` — PIT over the whole `x402` package (models, gate,
  verifier, settler, cache) against `x402.*Test*`. The `RpcTransactionSubmitter` inner class
  (thin adapter over `SolanaRpcClient`, exercised only against a live node) and the `*Fuzz`
  harnesses are excluded. Baseline 2026-07-17: 373 mutations, **95% killed, 96% test strength**.
  The ~14 survivors are the defensive null/bounds guards in `SvmExactVerifier.verify`
  (unreachable now that the fuzzer confirms the total-function contract holds — see below) and
  provably-equivalent redundant null checks whose downstream `try/catch` produces the identical
  error code (e.g. the `payload == null || transaction() == null` guard on line 45, where
  skipping the check reaches `Base64.decode(null)` → NPE → the same `INVALID_PAYLOAD_TRANSACTION`).
- `./gradlew :http-servers-sava:pitestHandlers` — PIT over `handlers.*` (public-key query
  params) against `handlers.*Test*`. Baseline 2026-07-17: 43 mutations, 86% killed; the
  survivors are `ConditionalsBoundaryMutator` off-by-one probes inside the hand-rolled
  multi-key `char[]` scan of `parsePublicKeyParams`, which a base58-length input cannot
  distinguish.
- `./gradlew :http-servers-sava:fuzzSvmVerify -PmaxFuzzTime=<seconds>` — Jazzer over
  `SvmExactVerifyFuzz`, which feeds raw bytes to `SvmExactVerifier.verify(requirements, bytes)`
  under both memo and no-memo requirements. Contract: **any input yields a `VerifyResponse`,
  never a throwable** (the gate calls verify with no try/catch), an accepted input never names
  the fee payer as the paying authority, and the response survives its own JSON round-trip.
  Seeded from valid payment transactions under `src/test/resources/fuzz/svmVerify` (with two
  committed regression inputs, `crash_*`).
- `./gradlew :http-servers-sava:fuzzX402Payload -PmaxFuzzTime=<seconds>` — Jazzer over
  `X402PayloadFuzz`, exercising every model parser and the gate end to end. Contract: the
  parsers tolerate any `RuntimeException`, the direct-JSON and Base64-header paths agree, and
  `X402Gate.httpResponse` answers every request with a 402 or the protected 200, never a
  throwable. Seeded from `src/test/resources/fuzz/x402Payload`.

Fuzzing this surface (2026-07-17) found and fixed one class of defect in `SvmExactVerifier`:
the `TransactionSkeleton` parser walks raw offsets and resolves lazily, so a malformed body
that still forms a valid header can yield instructions with `null` programs or accounts, or
data slices that overrun the transaction bytes. `verify` dereferenced these in the rule checks
(`transferAccounts.get(i).publicKey()`, `instruction.copyData()`) and threw `NullPointerException`
/ `ArrayIndexOutOfBoundsException` past its own `try/catch`. Fixed by validating every parsed
instruction (non-null program, non-null accounts, in-bounds data slice) immediately after
`parseInstructions`, returning `TRANSACTION_COULD_NOT_BE_DECODED` otherwise. Both crashing
inputs are committed to the `svmVerify` corpus and replayed by `VerifyFuzzRegressionTest`.

### Adding a target

- **Mutation suite**: add `mutation.register("<name>") { targetClasses = ...; targetTests = ... }`
  to the module's `hardening {}` block. Exclude test/fuzz helpers that live in the target
  package via `excludedClasses`.
- **Fuzz harness**: write a class with `public static void fuzzerTestOneInput(byte[])` and no
  Jazzer imports (so it compiles with the regular test sources), then
  `fuzz.register("<name>") { targetClass = ...; maxLen = ...; seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/<name>") }`.
  For any structured format a `seedCorpus` of committed inputs is required — a from-scratch
  mutator cannot assemble a valid base64/JSON/transaction. The writable corpus accumulates in
  `build/fuzz/<name>-corpus`. When the fuzzer finds a crash, copy the reported `crash-*`
  artifact into the seed corpus as a named regression input and add a replay assertion.
