# Seed corpora

Each directory here is a fuzz target's committed seed corpus (`seedCorpus` in
`http-servers-core/build.gradle.kts`), replayed on every `check` by a
plugin-generated `<Harness>SeedReplayTest` in the harness's package — so the
corpus cannot rot between fuzz runs, and under PIT the replay participates as
a killer. The invariants each replay asserts live in the harness's own
javadoc; a fuzz finding is only closed by a committed seed here **plus** a
named regression test.

This file lives next to the corpus directories, never inside one: every file
inside a corpus directory is fed to the harness as a seed.

## `formatPlaceholders` — [FormatPlaceholdersFuzz](../../java/software/sava/http_servers/core/logging/FormatPlaceholdersFuzz.java)

The two-mode placeholder-formatter harness: `raw_*` seeds exercise the
arbitrary-message mode (`raw_plain`, `raw_utf8`, `raw_hostile` — never throw,
brace-free messages pass through unchanged), and `gen_*` seeds exercise the
generated-token mode whose expected output is ground truth by construction
(`gen_all_tokens`, `gen_escapes`, `gen_exhausted` — every token class, the
escape forms, and value-pool exhaustion).

## `pathCanonicalizer` — [PathCanonicalizerFuzz](../../java/software/sava/http_servers/core/handlers/PathCanonicalizerFuzz.java)

The routing path-canonicalizer harness (added 2026-07-24 with the canonical-routing
contract): `gen_*` seeds exercise the generative-token mode whose expected canonical form
is ground truth by construction (`gen_all_tokens` — every token class, `gen_reject` — a
poisoned path, `gen_root_escape` — `..` past the root), and `raw_*` seeds exercise the
arbitrary-path property mode (`raw_traversal`, `raw_encoded_traversal`, `raw_root`,
`raw_hostile` — never throw; accepted results are rooted and hold no dot, empty,
backslash or NUL segment).

## `handlerUtil` — [HandlerUtilFuzz](../../java/software/sava/http_servers/core/handlers/HandlerUtilFuzz.java)

The differential query-scanner harness: the hand-rolled scanner and the naive
reference must agree on every input. Seeds pin the disagreement-prone shapes —
`malformed_escape`, `encoded_delims`, `plus_and_utf8`, `multi_params`,
`boundary_probe`, `int_list`.
