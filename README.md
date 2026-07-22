# HTTP Servers [![Gradle Check](https://github.com/sava-software/http-servers/actions/workflows/build.yml/badge.svg)](https://github.com/sava-software/http-servers/actions/workflows/build.yml)

A small HTTP server abstraction for Java 25 with pluggable backends. Write handlers once against
`Request` and `HttpResponse`, then choose a server implementation at build or runtime.

## Modules

| Module                    | Description                                                                |
|---------------------------|----------------------------------------------------------------------------|
| `http-servers-core`       | The API: `Request`, `HttpResponse`, handler registration, routing, wiring.  |
| `http-servers-jdk`        | Backend over the JDK's built-in `jdk.httpserver`. No extra dependencies.    |
| `http-servers-jetty`      | Backend over Jetty 12. HTTP/1.1 and H2C, optional gzip, CORS.               |
| `http-servers-fusionauth` | Backend over [java-http](https://github.com/FusionAuth/java-http). CORS.    |
| `http-servers-hello`      | Runnable demo wiring one handler against any of the backends.               |

Depend on `http-servers-core` plus exactly one backend. The backends are interchangeable: the same
handlers, registration calls and routing semantics apply to all of them.

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

### CORS

The Jetty and FusionAuth backends reflect the request `Origin` into `Access-Control-Allow-Origin`
and answer pre-flight `OPTIONS` requests for any method that resolves to a handler. These servers
are expected to sit behind a proxy or gateway that owns origin policy and authentication. The JDK
backend has no CORS handling.

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
