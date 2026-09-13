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
has none, header lookup is case-insensitive, and `body()` is never `null`.

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
honoured on the blocking path by the JDK, Jetty and Netty backends, which dispatch blocking
request handling onto it. Netty runs non-blocking routes and cached responses inline on its event
loop instead. FusionAuth and Helidon own their worker threads and ignore the executor, so code
that needs to observe or bound handler dispatch has to pick the JDK, Jetty or Netty backend.

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
connection that closes while a request body is still arriving — a client abandoning an upload —
is not a server failure on Netty: it is logged at `DEBUG`, nothing is answered.

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
