# Seed corpora

Each directory here is a fuzz target's committed seed corpus (`seedCorpus` in
`http-servers-sava/build.gradle.kts`), replayed on every `check` by a
plugin-generated `<Harness>SeedReplayTest` in the harness's package — so the
corpus cannot rot between fuzz runs, and under PIT the replay participates as
a killer. The invariants each replay asserts live in the harness's own
javadoc; a fuzz finding is only closed by a committed seed here **plus** a
named regression test.

This file lives next to the corpus directories, never inside one: every file
inside a corpus directory is fed to the harness as a seed.

## `svmVerify` — [SvmExactVerifyFuzz](../../java/software/sava/http_servers/sava/x402/SvmExactVerifyFuzz.java)

The facilitator `/verify` total-function contract: any byte sequence produces
a `VerifyResponse`, never a throwable. `valid_transfer` and
`valid_transfer_memo` are valid payment transactions built by the same
helpers as `SvmExactVerifierTest` (header/offset/length agreement is
unreachable from a from-scratch mutator); `crash_null_account_meta` and
`crash_regression_2` are minimized inputs that previously crashed the
verifier.

## `x402Payload` — [X402PayloadFuzz](../../java/software/sava/http_servers/sava/x402/X402PayloadFuzz.java)

The payment-payload parsing harness: parsers tolerate malformed input with a
RuntimeException, the direct-JSON and Base64-header paths agree, and the gate
answers 402 or the protected 200, never a throwable. `spec_payload` is the
payload shape from the x402 spec; `valid_payment` is a payload that verifies
end-to-end.
