# HTTP Servers [![Gradle Check](https://github.com/sava-software/http-servers/actions/workflows/build.yml/badge.svg)](https://github.com/sava-software/http-servers/actions/workflows/build.yml)

A small HTTP server abstraction for Java 25 with pluggable backends. Write handlers once against
`Request` and `HttpResponse`, then choose a server implementation at build or runtime.

## Modules

| Module                    | Description                                                                      |
|---------------------------|----------------------------------------------------------------------------------|
| `http-servers-core`       | The API: `Request`, `HttpResponse`, handler registration, routing, wiring.       |
| `http-servers-jdk`        | Backend over the JDK's built-in `jdk.httpserver`. No extra dependencies.         |
| `http-servers-jetty`      | Backend over Jetty 12. HTTP/1.1 and H2C, optional gzip, CORS.                    |
| `http-servers-fusionauth` | Backend over [java-http](https://github.com/FusionAuth/java-http). CORS.         |
| `http-servers-helidon`    | Backend over Helidon WebServer 4. HTTP/1.1 on virtual threads, H2C opt-in, CORS. |
| `http-servers-netty`      | Backend over Netty 4.2. HTTP/1.1 on the Netty codec, CORS.                       |
| `http-servers-hello`      | Runnable demo wiring one handler against any of the backends.                    |
| `http-servers-soak`       | Unpublished soak harness: a long-running target server plus a load generator.    |

Depend on `http-servers-core` plus exactly one backend. The backends are interchangeable: the same
handlers, registration calls and routing semantics apply to all of them.

Wherever a backend's own wire behaviour differs from the rest, the difference is written down
under [Backend divergences](#backend-divergences).

## Dependency configuration

Artifacts are published to GitHub Packages. See
[sava.software/quickstart](https://sava.software/quickstart) for repository configuration.

```kotlin
dependencies {
  implementation(platform("software.sava:solana-version-catalog:VERSION"))
  implementation("software.sava:http-servers-core")
  implementation("software.sava:http-servers-jetty")

  // Jetty only, for compressed responses.
  runtimeOnly("org.eclipse.jetty.compression:jetty-compression-gzip")
}
```

`solana-version-catalog` 25.30.21 and later carry the Helidon and Netty modules the two newest
backends require, so depending on `http-servers-helidon` or `http-servers-netty` is enough to
resolve its server; consumers add no extra lines for it.

## Usage

```java
import software.sava.http_servers.core.response.HttpResponse;
import software.sava.http_servers.core.server.HttpServerBuilderFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

// Discovers the backend on the module path or class path.
var builder = HttpServerBuilderFactory.findFirst();

builder.nonBlockingQueryHandler("/health", request -> HttpResponse.json("{\"status\":\"UP\"}"));

builder.blockingQueryPost("/echo", request -> HttpResponse.response(
    "text/plain", new String(request.body(), StandardCharsets.UTF_8)
));

var server = builder.createServer(Executors.newVirtualThreadPerTaskExecutor(), "localhost", 8080);
server.start();
```

Module path consumers declare the service in `module-info.java`:

```java
uses software.sava.http_servers.core.server.HttpServerBuilderFactory;
```

To pin a backend instead of discovering one, construct its factory directly — for example
`new JettyServerBuilderFactory().createBuilder()`.

### Handlers

Every registration method names three things: how the path matches, which HTTP method it answers,
and whether the handler may block.

| Method                                             | Matches        | Answers |
|----------------------------------------------------|----------------|---------|
| `nonBlockingQueryHandler` / `blockingQueryHandler`  | exact path     | `GET`   |
| `nonBlockingQueryPost` / `blockingQueryPost`        | exact path     | `POST`  |
| `nonBlockingPathHandler` / `blockingPathHandler`    | path prefix    | `GET`   |
| `nonBlockingPathPost` / `blockingPathPost`          | path prefix    | `POST`  |
| `cachedQueryHandler` / `cachedPathHandler`          | exact / prefix | `GET`   |

Use the `blocking` variants for handlers that perform blocking I/O. The `cached` variants take a
`CachedResponse` supplying pre-encoded JSON bytes.

`GET` and `POST` may share a path. A request whose path matches but whose method does not is
answered with `405` and an `Allow` header; an unmatched path is answered with `404`.

Query-handler paths match exactly, with a trailing-slash alias registered automatically: `/status`
also serves `/status/`, but not `/status/anything`. Path handlers are prefix routes, so `/files/`
serves `/files/a/b`.

### Request and response

`Request` exposes `method()`, `path()`, `query()`, `header(name)` and `body()`. The query string is
**raw**: separators arrive as literal `&`, while a `&` or `=` that belongs to a value stays
percent-encoded, so it can never be mistaken for a separator. `query()` is `null` when the request
has none, header lookup is case-insensitive, and `body()` is never `null`. On the JDK, Netty and
FusionAuth backends `body()` hands back the same bytes however many times it is read — the JDK
adapter reads the socket once and keeps them, Netty holds the body it decoded, java-http caches
its own first read; Jetty and Helidon are not pinned either way.

`HandlerUtil` in `software.sava.http_servers.core.handlers` parses that raw string. It matches a
parameter only at a boundary — the start of the query or just after a `&` — so `page=` never
matches inside `perpage=`, splits structure before decoding so an encoded delimiter can never act
as a separator, and percent-decodes each returned value (`%XX` escapes plus `+` as space). A
malformed escape throws `IllegalArgumentException`. Anything reading `query()` directly gets the
raw string and decodes for itself.

`HttpResponse` carries a status code, content type, headers and a body, built through
`HttpResponse.response(..)` and `HttpResponse.json(..)`. `withHeader(name, value)` returns a copy
with one header added, leaving the original untouched:

```java
HttpResponse.json(402, "{\"error\":\"payment required\"}")
    .withHeader("X-Payment-Response", settlement);
```

A handler that throws is answered with `500`, and the failure is logged.

#### Backend divergences

Routing, status codes, header propagation and the raw path and query contract are identical on
every backend. The framing a server puts around a response is its own, so the contract below is
what a caller may rely on rather than the bytes any one backend happens to emit.

**Bodyless statuses.** A `204` or a `304` is sent without a body everywhere, and no backend
chunks either of them. `Content-Length: 0` is the backend's own choice: the JDK and Netty
backends send neither `Content-Length` nor `Transfer-Encoding` on either status, Jetty omits
`Content-Length` on `204` and sends `Content-Length: 0` on `304`, and FusionAuth and Helidon send
`Content-Length: 0` on both. Assert that `Content-Length` is absent or exactly `0`, never that it
is present. Helidon treats `205` as bodyless too, because that is its own no-entity set, so
content a handler attaches to a `205` is dropped there and sent by every other backend.

**Executors.** Handlers run on virtual threads. The `Executor` handed to `createServer` is
honoured by the JDK backend for every exchange — `jdk.httpserver` dispatches each one onto it,
and blocking and non-blocking handlers alike run there, on the dispatching thread — and on the
blocking path by the Jetty and Netty backends, which dispatch blocking request handling onto it.
Netty runs non-blocking routes and cached responses inline on its event loop instead. FusionAuth
and Helidon own their worker threads and ignore the executor, so code that needs to observe or
bound handler dispatch has to pick the JDK, Jetty or Netty backend. The JDK backend used to hand
non-blocking handlers to a second executor of its own; it no longer does, because `jdk.httpserver`
can only clean up an exchange that fails on the thread it dispatched it to (see below).

**A client that leaves.** On the JDK and Netty backends a client that abandons an upload, whether
it closes the connection or resets it, is not a server failure: the departure is logged at
`DEBUG`, nothing more is answered, and the connection is closed. The JDK backend treats a client
that resets while its response is being written the same way; the Netty backend's tests cover
abandoned uploads only, so nothing is claimed for it there. On the JDK backend that close is also
what unregisters the connection from `jdk.httpserver`, which otherwise keeps a connection whose
failure it swallowed for the life of the server; so the adapter never swallows an I/O failure, and
a bodyless answer (`400`, `404`, `405`, `500`, `204`, `304`) reads and discards the unread request
body before it writes its head. That order is what keeps a truncated upload to such a path from
being answered a head and then leaking its connection, and it has a cost on every such answer, not
only when a client stalls: the head waits until the declared body has arrived, for as long as the
client takes to send it — a slow or merely large upload to an unrouted path delays its `404` by
its own transfer time — up to `jdk.httpserver`'s drain amount (`sun.net.httpserver.drainAmount`,
64 KiB by default), past which the head is written and the connection closed after it, as before.
A handler that throws is still answered `500` and logged at `ERROR`.

**HTTP/1.0.** The JDK, Jetty, FusionAuth and Netty backends answer an `HTTP/1.0` request, subject
to the `Host` rule below, and close the connection afterwards unless it asks for
`Connection: keep-alive`. Helidon refuses it with `505`. Every backend replies in `HTTP/1.1`
whatever version the request names.

**The `Host` header.** RFC 9112 makes `Host` mandatory from `HTTP/1.1` onwards, and Jetty enforces
exactly that: `400` for an `HTTP/1.1` request without one or with a blank one, and a normal answer
to the `HTTP/1.0` form. FusionAuth requires `Host` on every request, `HTTP/1.0` included, and
answers `400` without it. Helidon answers `400` for an `HTTP/1.1` request with a missing or blank
`Host`, and refuses the `HTTP/1.0` form before that rule applies. The JDK and Netty backends
require it on neither version.

**An empty query.** A request target ending in a bare `?` reaches the handler as an empty `query()`
on the JDK, Jetty and Netty backends and as `null` on FusionAuth and Helidon. Treat the empty
string and `null` alike; `HandlerUtil` already does.

**H2C.** Jetty serves cleartext HTTP/2 out of the box. Helidon serves it once
`io.helidon.webserver:helidon-webserver-http2` is added as a `runtimeOnly` dependency, which also
replaces its clean `HTTP/1.0` `505` with no answer at all: the connection is closed with zero
bytes when a `Host` header is present and held open until the idle timeout when it is not. Do not
enable it in front of `HTTP/1.0` clients. The JDK, FusionAuth and Netty backends speak HTTP/1.1
only.

**Connection persistence.** A handler that sets `Connection: close` on its response ends the
connection after that response on every backend: the header reaches the wire and the server
closes. Persistence is decided from both sides — the request's keep-alive and the response's
close directive — so a keep-alive request answered with `Connection: close` does not stay open.

**Pipelining on Netty.** Netty serves one request per connection at a time and answers pipelined
requests strictly in request order (RFC 9112 §9.3.2), whoever produces the answer: a handler, the
controller's own `400`/`404`/`405`/`500`, the codec's `100 Continue` and `413`, or the `417` and
`413` with which an expectation is refused. A refusal waits its turn behind the request in
flight exactly as a routed answer does, so a client never pairs it with the wrong request — but
it is *decided* the moment the request head is decoded, because refusing an expectation tells the
codec that the refused body is not coming and what follows the head is the next request line,
and that is only true of the request the codec is standing on. While a fully received request
awaits its response the connection is not read, which stalls a client that pipelines without
reading answers instead of buffering it without bound. A request's own `Connection: close` is
honoured whether a handler answered it or it was refused, and nothing pipelined behind a closing
response is processed (§9.6).

**Request-body size on Netty.** Netty aggregates a request body up to 64 MiB, a bound the other
backends do not impose. A body declared or received past that limit is answered `413` in request
order with `Connection: close` and `Content-Length: 0`, and the connection is then closed: the body
is never read, so a client still sending it may see the connection reset before it has read the
`413`. That includes a request announcing the oversized body with `Expect: 100-continue`, which
is refused the same way before any of the body is read — a 100-continue client may send it
without waiting, and it is never read either way. An `Expect` the server does not support is
answered `417` in request order, with `Content-Length: 0`, and the connection stays open; a
client that sends the body of a refused request anyway has those bytes read as its next request
line, which is Netty's own outcome: an unroutable line is answered as such, an unparsable one
with `400`, `Connection: close` and the close itself. A malformed request head is `400` whatever
`Expect` it carried (or the `413` and close its declared length would earn it without one), and an
`Expect` on an `HTTP/1.0` request is ignored (RFC 9110 §10.1.1). Put the Netty backend behind a
proxy if you need a different limit; the other backends stream the body with no aggregation cap.

**Stopping on Netty.** `stop()` is immediate for the socket: the listener and every open
connection are cut at once, in-flight blocking handlers included. It then waits for the event-loop
threads to exit, so a non-blocking handler that blocks the loop against its contract delays the
return by its own duration. The other backends do not wait on handler code in `stop()`. A
connection that ends while a request body is still arriving — a client abandoning an upload,
whether it closes the connection or resets it — is not a server failure on Netty: it is logged
at `DEBUG`, nothing is answered and the connection is closed.

**Idle connections on Netty.** Netty closes an idle connection after 30 s without writing
anything, where idle means neither carrying a fully received request that awaits its response nor
making progress: a silent connection between requests is closed 30 s after its last response (or
its accept), and a request whose head has arrived but whose body has stopped is closed 30 s after
the last byte decoded — so a client that sends a head and then nothing cannot hold a connection
open for free, while an upload that is slow but still progressing is never cut. A fully received
request, however long its handler takes, keeps its connection, and a client gets the full 30 s of
grace after every response. The JDK and Jetty backends both close a silent connection between
requests after the same 30 s; they part on a request in progress. The JDK never times one out —
its request timeout defaults to unlimited, so a stalled body holds a connection for good. Jetty
delivers its idle timeout to whatever the request is waiting on: a handler waiting for a body that
has stopped gets a `500` and the connection is closed, while a handler that is busy rather than
waiting is left to finish and its response is still delivered. Netty's rule is Jetty's on both
counts and diverges from the JDK only in closing the stalled body.

**Absolute-form targets.** A request line in absolute form, `GET http://host/p HTTP/1.1`, is
refused with `400` by Netty and FusionAuth, which route on the target as received. The JDK and
Jetty servers reduce it to its path first and serve it, and Jetty additionally answers `400` when
the `Host` header names a different authority than the target does.

### CORS

The Jetty, FusionAuth, Helidon and Netty backends reflect the request `Origin` into
`Access-Control-Allow-Origin` and answer pre-flight `OPTIONS` requests for any method that
resolves to a handler. These servers are expected to sit behind a proxy or gateway that owns
origin policy and authentication. The JDK backend has no CORS handling, so a pre-flight `OPTIONS`
is simply an unrouted method there and is answered with `405` and an `Allow` header.

### Conditional registration

`HandlerWiring` filters registrations by handler group and path, so a deployment can enable a subset
of handlers without branching at each call site:

```java
enum Api {PUBLIC, ADMIN}

var wiring = builder.wireNonExcludedHandlers(Map.of(Api.ADMIN, Set.of()));

wiring.queryNonBlockingGet(Api.PUBLIC, "/health", healthHandler);  // registered
wiring.queryNonBlockingGet(Api.ADMIN, "/shutdown", adminHandler);  // filtered out
```

An empty set excludes the whole group. `wireIncludedHandlers` inverts this into an allowlist, and
`includePath` / `excludePath` answer the same question directly.

## Build

[Generate a classic token](https://github.com/settings/tokens) with the `read:packages` scope needed
to access dependencies hosted on GitHub Package Repository.

#### ~/.gradle/gradle.properties

```properties
savaGithubPackagesUsername=GITHUB_USERNAME
savaGithubPackagesPassword=GITHUB_TOKEN
```

```shell
./gradlew check
```

Mutation and fuzz testing conventions for this repository are documented in [AGENTS.md](AGENTS.md).

## Soak testing

`http-servers-soak` is an unpublished operational harness for the long-run behaviour the unit
suites cannot see — leaks, thread and descriptor growth, response ordering and connection handling
under sustained mixed traffic. One line runs it against any backend:

```shell
./http-servers-soak/soak.sh NettyBuilderFactory 3600 32
```

The arguments are the factory simple name, the duration in seconds, the connection count (default
32, split across keep-alive `HttpClient` workers, raw-socket pipeliners, raw-socket abusers, one
idle prober and a connection churner) and the server heap in MiB (default 256, the `-Xmx`). The
server runs under a continuous flight recording and native-memory tracking (below). Results land
in `http-servers-soak/build/soak/runs/<backend>-<timestamp>/`:

| file | contents |
|---|---|
| `server.csv` | one JVM sample every 30 s: heap, GC count, live and peak threads, open descriptors, Netty `LEAK:` reports and `SEVERE` records, and the `jdk.VirtualThreadSubmitFailed` and `jdk.VirtualThreadPinned` counts over the whole run, from the server's own event stream |
| `server.log` | the server's stderr: JUL console output, `LEAK:` reports, stack traces |
| `rss.csv` | the server's RSS every 30 s and once more at the end of the load |
| `nmt.log`, `nmt.csv` | `jcmd VM.native_memory`: a baseline taken at start, then a `summary.diff` at the end of the warm-up, every 30 min and once when the load has finished — the log verbatim under timestamps, the CSV with one row per diff: the totals, the committed size and diff of `Java Heap`, `Class`, `Thread`, `Code`, `GC`, `Metaspace`, `Internal`, `Other` (direct buffers), `Arena Chunk` and `Tracing`, and last the gated total: committed less `Arena Chunk` and `Tracing` |
| `jfr-repo/` | the recording's disk repository — the ring's chunks — while the server runs; the JVM removes it on a clean exit |
| `soak-final.jfr` | the recording dumped with `path-to-gc-roots=true` before the server is stopped |
| `soak-exit.jfr` | the same recording as the JVM dumps it on exit (`dumponexit`): the fallback when the dump before the stop was abandoned, without the root paths |
| `jfr-summary.txt`, `jfr-report.md`, `jfr-metrics.properties` | `jfr summary` of the recording the report was built from; `JfrReport`'s sections, also appended to `summary.md`; the same pass as `key=value` pairs, which the verdict reads |
| `load.log` | the load generator's periodic summaries and final report |
| `summary.md` | the verdict and the numbers behind it |

`server.log` grows by roughly 15-20 KiB/s at the default mix, because every `GET /fail` the load
sends is a logged handler failure with its stack trace; everything lives under `build/`, the
recording's repository included, so `clean` reclaims it — also after a server the harness had
to kill, which leaves that repository behind.

**The recording.** The server starts with `-XX:NativeMemoryTracking=summary`,
`-XX:FlightRecorderOptions:repository=<run directory>/jfr-repo` and
`-XX:StartFlightRecording:name=soak,settings=http-servers-soak/soak.jfc,disk=true,maxsize=512m,dumponexit=true`.
`soak.jfc` is the JDK's `default.jfc` — the "Continuous" profile, under 1 % overhead — with the
deltas its header lists: retained-object samples (`jdk.OldObjectSample`, cutoff 0) keep their
allocation stack, the native-memory events sample every 30 s, the socket read and write threshold
is 20 ms, and `jdk.JavaExceptionThrow` is off because the load's `GET /fail` throws on every request
(`jdk.JavaErrorThrow` stays on, and JFR records an exception event beside every error event
regardless, so a handful still appear). Execution samples every 20 ms, pinned virtual threads over
20 ms, `jdk.VirtualThreadSubmitFailed`, platform thread starts and ends, GC pauses and monitor
contention over 20 ms are `default.jfc`'s own settings. The recording is a disk ring of at most
512 MiB, which at the default mix is written at roughly 25-35 KiB/s, so a 24 h run keeps its last
four to six hours rather than the whole run — `nmt.csv`, `rss.csv` and `server.csv` cover the whole
run regardless, and the last carries the `jdk.VirtualThreadSubmitFailed` and
`jdk.VirtualThreadPinned` counts from a `RecordingStream` in the server (`JfrCounters`), a second
recording whose chunks are released as soon as they are consumed, so it neither pins the ring nor
changes what it records. NMT's own `Tracing` category is the recorder's buffers. Before
the server is stopped the ring is dumped with `jcmd JFR.dump name=soak path-to-gc-roots=true`,
which walks the heap from the sampled objects and is bounded at 10 min; SIGTERM then writes
`soak-exit.jfr`.

Open either file with the `jfr` tool of a JDK at least as new as the recording's —
`jfr summary soak-final.jfr` for the event counts,
`jfr print --events jdk.OldObjectSample --stack-depth 8 soak-final.jfr` for the retained objects
with their allocation stacks and root paths, `jfr view hot-methods`, `gc-pauses`,
`pinned-threads`, `allocation-by-site` or `native-memory-committed` for the built-in views — or in
JDK Mission Control (File → Open File; its Live Objects page shows the old-object samples with
their reference chains, Method Profiling the hot stacks).

**The JFR section of `summary.md`** is `jfr-report.md`, produced by `JfrReport` in one pass over
the file through `jdk.jfr.consumer.RecordingFile`: (a) the event counts per type; (b) the top ten
retained allocation sites from `jdk.OldObjectSample` — object type, the top eight allocation
frames, and the GC root and referrer path when the dump recorded one, the sites with a path
listed first because they are the objects the dump proved reachable — with a sample re-emitted at
every chunk rotation counted once; (c) the top ten hottest stacks from `jdk.ExecutionSample`
aggregated by top frame, the share of samples per thread, and a note when one thread holds half
of them, the wedged-thread signature; (d) the `jdk.VirtualThreadPinned` count with its top five
stacks and reasons, and the `jdk.VirtualThreadSubmitFailed` count; (e) GC: the collections, the
max and p99 sum-of-pauses per collection, and the phase pauses; (f) platform thread starts and
ends and who started them; (g) native memory: `jdk.NativeMemoryUsageTotal` first and last
committed, the per-type first and last from `jdk.NativeMemoryUsage`, and `nmt.csv` as a table.
The two native-memory series in (g) are not the same number: the events are the whole NMT total,
`Tracing` and `Arena Chunk` included, sampled by JFR's own periodic task a few seconds away from
each `jcmd` attach and covering only the ring; `nmt.csv` covers the whole run and its gated total
leaves those two categories out, and that is the series the verdict fits.

The verdict in `summary.md` is `PASS` only when the load generator exited 0 — every response
verified against the per-backend expectation table in `Backend.java`, no unexpected exception (one
that escapes a worker is recorded and fails the run), every profile read at least one response,
every abuse case ran or was skipped for a stated reason, and at least one request per second per
connection was made over the run — no `LEAK:` report was counted or logged, no `OutOfMemoryError`
or heap dump appeared, no `SEVERE` record was logged beyond the ones the load provokes (one per
`GET /fail`, and up to one per upload abandoned mid-body, by reset or half-close, which a backend
may or may not log — the JDK backend used to log both as handler failures and now, like Netty, logs
neither above `DEBUG`), live threads and open descriptors ended within a
small margin of their post-warm-up baseline (16 threads, 64 descriptors), the least-squares RSS
slope over the post-warm-up samples is at most 2 MiB per hour — or 8 MiB over the whole fitted
window when that is larger, because a short run cannot resolve a slope that small — the
least-squares slope of the gated native-memory total (committed less `Arena Chunk` and `Tracing`)
over every `summary.diff` from the end of the warm-up to the end of the load is within those same
two numbers over its own window, the server counted no `jdk.VirtualThreadSubmitFailed` event over
the whole run (and the recording holds no more than the server counted), and a recording was
written and `JfrReport` processed it. The gated total leaves out the two categories that move by
megabytes between samples with nothing leaked — `Arena Chunk`, the JVM's transient compilation
and class-loading arenas, and `Tracing`, the recorder's own buffers — both of which stay in
`nmt.csv` and `nmt.log` per category; the ungated total is printed beside it. The pinned count,
the old-object top types and a dominating thread are reported under the verdict but not gated.
Warm-up is a span of time, not a share of the samples: everything within
`max(60 s, 10 % of the duration)` of the first sample is dropped, so the heap-commit ramp and the
pre-load thread count never enter the fit or the baseline. Those checks need at least two JVM
samples, three RSS samples and two native-memory diffs (the end of the warm-up and the end of the
load; a run over 30 min adds a diff every 30 min to the fit) after the warm-up — about two
minutes at the 30 s sampling interval; a shorter run reports them as not measured under its
verdict instead of failing on them. On a loaded workstation the two-minute RSS slope is noise in
both directions (the heap's first-touch ramp one way, the OS compressor the other); the gated
native-memory slope is the stable short-run signal, and the RSS slope earns its keep on the long
runs. A server that exits mid-run stops the load at once and fails the run with the time it
died, and one that never prints its port fails before the load starts; both leave a `summary.md`.
Abuse cases a backend does not document under [Backend divergences](#backend-divergences)
are skipped there rather than guessed, and the number of skipped cases is part of the verdict.

The thresholds can be loosened from the environment for a noisy machine:
`SOAK_RSS_SLOPE_KIB_PER_HOUR` (2048), `SOAK_RSS_NOISE_FLOOR_KIB` (8192) — both also bound the
gated native-memory slope — `SOAK_THREAD_MARGIN` (16), `SOAK_FD_MARGIN` (64), `SOAK_SEVERE_MARGIN` (0)
and `SOAK_WARMUP_SECONDS` (the `max(60, duration / 10)` above). The recording has its own knobs:
`SOAK_JFR_MAXSIZE` (512m, the ring both dumps are bounded by), `SOAK_JFR_DUMP_TIMEOUT_SECONDS`
(600, after which the root-path dump is abandoned and the exit dump used) and
`SOAK_NMT_INTERVAL_SECONDS` (1800, the `summary.diff` cadence).
