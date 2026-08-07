# Per-suite hardening notes

Scope decisions and deliberate exceptions for the mutation suites — this repo's
instance of HARDENING.md's "what the ratchet cannot see" inventory. The process
contract is in `AGENTS.md`; the policy behind it is sava-build's `HARDENING.md`.
Read this when touching a suite's registration, not on every task.

Suites target by package wildcard with explicit exclusions, never an allowlist,
so a new class in a covered package is mutated by default. Per-suite triage
arguments live in each module's `config/pitest/README.md`; this file names the
edges the ratchet cannot see.

## Suite map

| Module | Suites | Notes |
| --- | --- | --- |
| http-servers-core | `pitestHandlers`, `pitestWiring`, `pitestServer`, `pitestResponse`, `pitestLogging` | wiring/server/response run at 100% with empty baselines; `response` also owns `core.request` |
| http-servers-jdk | `pitestDispatch` | 100%, empty baseline |
| http-servers-jetty | `pitestDispatch` | carries the load-flappy handled-flag family |
| http-servers-fusionauth | `pitestDispatch`, `pitestLoggerShim` | see the partition note |
| http-servers-hello | `pitestHello` | demo module, still ratcheted |
| http-servers-sava | `pitestX402`, `pitestHandlers` | the payment-gate threat surface |

## Repo-specific blindness edges

- **Socket kills are load-sensitive.** Every adapter dispatch suite kills
  through real socket round trips, so detection wall clock rides on gate
  parallelism: coordinates flip `SURVIVED <-> TIMED_OUT` under `qualityGate`
  load (jetty's handled-flag family, fusionauth's gate-load-only audited
  timeout). Baselines hold the union, audited timeout sets name the members —
  neither is drift to "fix". Which member of a flappy family flips is itself
  load-dependent; a quiet run on one row is not the family settling.
  Since sava-build 21.5.25 a *finite* `KILLED <-> TIMED_OUT` race is no longer
  admissible as `cause:liveness`: it is `cause:harness`, which does not certify,
  and it is repaired or retimed rather than admitted. Two audited rows sat on
  that line — jetty `initRestServer` 34 and jdk `JdkQueryHandler.process` 79.
  Jetty 34 was re-measured 2026-08-07 under 21.5.25 (`KILLED` solo and
  history-free, identical under `-PisolateMutants`, and `KILLED` for both
  siblings under full certification), and its underlying cause — a leaked
  acceptor the test could not reclaim — was then **fixed in production** rather
  than relabelled; see the API note below. Jdk 79's finite reading is still only
  conjectured and is owed a scoped history-free solo measurement.
- **`HttpServer` gained `stop()` on 2026-08-07, and that was a product fix, not
  a records fix.** The interface had exactly one method, `start()`, and no
  production code anywhere could shut a server down: consumers could not release
  a port, restart on config change, or embed a server in a test without leaking,
  and the demo's `Entrypoint` sleeps forever because nothing else could be done
  with the handle. Every backend already wrapped something stoppable (Jetty
  `Server.stop()`, jdk `stop(int)`, java-http `Closeable`); the abstraction just
  never surfaced it. `HttpServer` now declares `stop()` and extends
  `AutoCloseable` with `close()` delegating to it. Populations moved +1 in core
  `server` (40), jdk `dispatch` (54), jetty `dispatch` (73) and fusionauth
  `dispatch` (56) — every newcomer killed, no accepted row added. The lesson
  worth keeping: an audited-timeout row that keeps resisting classification is
  worth reading as a *missing capability* before it is read as a labelling
  problem.
- **The 21.5.25 bump reset every quiet-run counter.** Certification printed
  `timeout-retirement stash predates fresh-only evidence bound to current
  inputs` for all five suites carrying a timeouts file: quiet-run evidence is
  bound to the input hashes and the plugin SHA is one of them. Every pending
  retirement nomination — jetty 34's included — restarts at one of the required
  three, so no timeout row can be retired on tool evidence until two further
  certifications run on unchanged inputs. Expect the same reset on the next
  plugin bump; it is the rule working, not drift.
- **One proven mixed timeout key.** Core `logging`'s
  `BaseJulLogger, formatPlaceholders, IncrementsMutator` holds two liveness
  members (the loop cursor, lines 69 and 80) and one finite sibling killed
  outright (the argument cursor, line 75). 21.5.25 treats a proven mixed key as
  not representable as an honest certifying row, and refuses the source-line
  qualifier the README used to lean on. The repair is to split the identity into
  distinct method keys; it is owed, and deliberately deferred out of the
  adoption pass for the same confounding reason.
- **The fusionauth logging partition is a handoff, not a hole.** java-http's
  own threads log through `FusionAuthJulLogger`, so mutating the shim under
  socket tests can wedge a run past PIT's timeout (observed 40+ min,
  2026-07-22). `dispatch` excludes `fusionauth.logging.*`; `loggerShim` owns it
  with in-process tests.
- **Declined exclusions.** Both are recorded with `declineExclusionAudit` at
  their registration site, where the measured reason lives: `hello.Entrypoint`
  (thin main — a port-argument default and an eternal sleep; the boot flow it
  wraps is `HelloServer`, mutated and killed through `HelloServerTests`) and
  `x402 SvmExactSettler$RpcTransactionSubmitter` (thin adapter over
  `SolanaRpcClient` behind the `TransactionSubmitter` seam; its correctness
  owner is the settler's error mapping, killed through
  `SvmExactSettlerTest`'s `FakeSubmitter`). The submitter's decline was written
  2026-08-03, when the audit refused the previously bare exclusion.
- **The `request` package rides with `response`.** `core.request` holds only
  the all-abstract `Request` interface, whose one production consumer is
  `core.response`'s `QueryHandler` (`Request -> HttpResponse`) — so
  `pitestResponse` targets both halves of the message contract. Targeted
  rather than declined so a default method added to `Request` later joins the
  mutated population by default; `targetTests` stays narrow, so that first
  default method is owed a test in the response suite's test scope.
- **Two class-path worlds.** PIT minions run on the class path while the test
  tasks run on the module path. Real services are declared in both
  `module-info` and `META-INF/services`; test-only providers are covered via
  probe-and-branch (core's `findFirst`,
  `BaseHttpServerBuilderTests.FixtureFactory`) — assertions may branch on a
  `ServiceLoader` probe, pass/fail may not depend on the world.
- **The Jetty compliance backstop is the one socket-unreachable family.**
  Jetty's `UriCompliance.DEFAULT` answers the ambiguous-path battery before
  `JettyController` runs (measured 2026-07-24 on Jetty 12.1). The named
  missing capability is an in-process controller harness with faked
  `Request`/`Response`/`Callback`; re-measure the claim on every Jetty
  major/minor bump. See the jetty README's per-row reasons.
- **The jdk server layer's verdicts are JDK-build-dependent.** jdk.httpserver
  answers `//echo` 404 on some builds and 400 on others; conformance tests pin
  refused-and-never-routed, never the exact status. CI runs a different JDK
  build than local.
- **Kills come only from `targetTests`.** hello's `findFirstDiscoversABackend`
  exercises core's provider path end-to-end but cannot kill core rows —
  cross-module tests are outside the owning suite's pattern. That is why the
  probe-and-branch fixture lives in core's own test sources.
- **No fuzz workflow, on purpose.** Long fuzz campaigns are a local
  release-checklist item here, recorded with the budget used; a manual GitHub
  campaign may be added for optional exploration, but this repo does not treat
  one as release evidence. (Task semantics: `hardeningHelp`.)
