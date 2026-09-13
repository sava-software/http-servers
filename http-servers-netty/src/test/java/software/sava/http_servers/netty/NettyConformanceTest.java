package software.sava.http_servers.netty;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.sava.http_servers.core.response.HttpResponse;
import software.sava.http_servers.core.server.HttpServer;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the parts of the core Request/HttpResponse contract that every backend must agree
/// on: the raw query string, 500 on a throwing handler (never a hang or connection abort),
/// custom status/header propagation (the x402 payment-gate shape), cached JSON responses,
/// and case-insensitive request-header lookup.
///
/// Mirrors `FusionAuthConformanceTest` case for case, with two documented exceptions:
/// `frameworkLoggingFlowsThroughTheJulShim` (java-http needs a logger shim to reach JUL,
/// while Netty's `InternalLoggerFactory` selects JUL on its own whenever no SLF4J/Log4j is
/// present, so there is no adapter-owned shim installation to pin here), and the sibling's
/// `http10RequestIsAnswered`, which appears here split into `http10RequestIsAnsweredThenClosed`
/// and `http10KeepAliveIsHonoured` because Netty answers the 1.0 form on both sides of the
/// keep-alive rule. The cases after the mirrored block pin what this backend adds —
/// per-connection response ordering under pipelining, HTTP/1.0, the codec's 400/413 answers,
/// executor placement and lifecycle.
///
/// Every server started through `serve`/`start` (or `absentHostBindsAllInterfaces`'s direct
/// starts) is registered and stopped in an `@AfterEach`, so no connection or event-loop
/// thread leaks between tests — the isolation AGENTS.md asks for so state cannot leak between
/// PIT mutants. Tests that own a server's lifecycle explicitly (`startOwned`,
/// `startOnAnOccupiedPortThrows`) close it themselves.
///
/// Every client request and raw socket carries a 2 s bound (the sibling suites use 10 s).
/// A dropped response write leaves the client blocked on a read, and PIT's per-mutant
/// watchdog fires at the recorded duration × 1.25 + 4000 ms — so a 10 s bound never fires
/// first and such mutants read as `TIMED_OUT`, detected by the clock rather than by this
/// suite. At 2 s the bound beats the watchdog with room to spare, and a hang fails the
/// test as a timeout exception: an assertion-level kill, deterministic under load. Raw
/// tests additionally assert on a Content-Length delimited response before they wait for
/// the close, so a wrong status is caught by its assertion rather than by the bound.
/// `anIdleConnectionIsClosedAfterTheIdleTimeout` is the one case that waits on real time,
/// for an event and never a sleep; the idle timeout's arithmetic, and which connections it
/// applies to, are pinned on an advanced clock in `NettyPipelineTest`.
final class NettyConformanceTest {

  /// Every server a test starts without owning its lifecycle, drained here so no bound
  /// listener or event-loop thread survives the test.
  private final java.util.List<HttpServer> started = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

  @AfterEach
  void stopStartedServers() throws Exception {
    Exception first = null;
    for (final var server : started) {
      try {
        server.stop();
      } catch (final Exception e) {
        if (first == null) {
          first = e;
        }
      }
    }
    started.clear();
    if (first != null) {
      throw first;
    }
  }

  private static int freePort() throws Exception {
    try (final var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private int serve(final java.util.function.Consumer<software.sava.http_servers.core.server.HttpServerBuilder> register)
      throws Exception {
    final var builder = new NettyBuilderFactory().createBuilder();
    register.accept(builder);
    return start(builder);
  }

  private static java.net.http.HttpResponse<String> get(final HttpClient client, final int port, final String pathAndQuery)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build(),
        BodyHandlers.ofString()
    );
  }

  @Test
  void rawQueryStringReachesTheHandler() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/q", request ->
            HttpResponse.response("text/plain", String.valueOf(request.query()))));

    try (final var client = HttpClient.newHttpClient()) {
      final var encoded = get(client, port, "/q?keys=a%26b&x=1");
      assertEquals(200, encoded.statusCode());
      assertEquals("keys=a%26b&x=1", encoded.body(),
          "percent-encoded delimiters must reach the handler undecoded");

      final var absent = get(client, port, "/q");
      assertEquals("null", absent.body(), "a request without a query must yield null");
    }
  }

  /// Captures JUL records published under {@code loggerName} while {@code body} runs.
  private static java.util.List<java.util.logging.LogRecord> recordLogs(
      final String loggerName, final org.junit.jupiter.api.function.Executable body) throws Throwable {
    final var records = java.util.Collections.synchronizedList(new java.util.ArrayList<java.util.logging.LogRecord>());
    final var jul = java.util.logging.Logger.getLogger(loggerName);
    final var handler = new java.util.logging.Handler() {
      @Override
      public void publish(final java.util.logging.LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    jul.addHandler(handler);
    try {
      body.execute();
    } finally {
      jul.removeHandler(handler);
    }
    return records;
  }

  @Test
  void throwingBlockingHandlerAnswers500() throws Throwable {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/boom", request -> {
          throw new IllegalStateException("handler bug");
        }));

    final var logs = recordLogs(NettyController.class.getName(), () -> {
      try (final var client = HttpClient.newHttpClient()) {
        assertEquals(500, get(client, port, "/boom").statusCode());
      }
    });
    assertTrue(logs.stream().anyMatch(r -> r.getThrown() instanceof IllegalStateException),
        "the handler failure must be logged, not swallowed");
  }

  @Test
  void throwingNonBlockingHandlerAnswers500() throws Throwable {
    final int port = serve(builder ->
        builder.nonBlockingQueryHandler("/boom", request -> {
          throw new IllegalStateException("handler bug");
        }));

    final var logs = recordLogs(NettyController.class.getName(), () -> {
      try (final var client = HttpClient.newHttpClient()) {
        assertEquals(500, get(client, port, "/boom").statusCode());
      }
    });
    assertTrue(logs.stream().anyMatch(r -> r.getThrown() instanceof IllegalStateException),
        "the handler failure must be logged, not swallowed");
  }

  @Test
  void statusAndCustomHeadersCrossTheWire() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/pay", request ->
            HttpResponse.json(402, "{\"error\":\"payment required\"}")
                .withHeader("X-Payment-Response", "settlement-abc")));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = get(client, port, "/pay");
      assertEquals(402, response.statusCode());
      assertEquals("settlement-abc", response.headers().firstValue("X-Payment-Response").orElse(null));
      assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null));
      assertEquals("{\"error\":\"payment required\"}", response.body());
    }
  }

  @Test
  void cachedHandlerServesJsonBytes() throws Exception {
    final byte[] cached = "{\"cached\":true}".getBytes(StandardCharsets.UTF_8);
    final int port = serve(builder -> builder.cachedQueryHandler("/cached", () -> cached));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/cached"))
              .timeout(Duration.ofSeconds(2))
              .GET()
              .build(),
          BodyHandlers.ofByteArray()
      );
      assertEquals(200, response.statusCode());
      assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null));
      assertEquals(String.valueOf(cached.length),
          response.headers().firstValue("Content-Length").orElse(null),
          "responses must carry an explicit Content-Length");
      assertArrayEquals(cached, response.body());
    }
  }

  @Test
  void headerLookupIsCaseInsensitive() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/h", request ->
            HttpResponse.response("text/plain", String.valueOf(request.header("X-Payment")))));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/h"))
              .timeout(Duration.ofSeconds(2))
              .header("x-payment", "header-value")
              .GET()
              .build(),
          BodyHandlers.ofString()
      );
      assertEquals("header-value", response.body());
    }
  }

  @Test
  void queryHandlerPathsMatchExactly() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/echo", request ->
            HttpResponse.response("text/plain", "echo")));

    try (final var client = HttpClient.newHttpClient()) {
      assertEquals(200, get(client, port, "/echo").statusCode());
      assertEquals(200, get(client, port, "/echo/").statusCode(),
          "the builder registers the trailing-slash alias");
      assertEquals(404, get(client, port, "/echo/sub").statusCode(),
          "query-handler paths must not prefix-match");
      assertEquals(404, get(client, port, "/echoes").statusCode());
      assertEquals(404, get(client, port, "/nowhere").statusCode());
    }
  }

  @Test
  void pathHandlersMatchByPrefix() throws Exception {
    final int port = serve(builder ->
        builder.blockingPathHandler("/files/", request ->
            HttpResponse.response("text/plain", request.path())));

    try (final var client = HttpClient.newHttpClient()) {
      final var nested = get(client, port, "/files/a/b");
      assertEquals(200, nested.statusCode(), "path handlers are prefix routes");
      assertEquals("/files/a/b", nested.body());
      assertEquals(404, get(client, port, "/other").statusCode());
    }
  }

  @Test
  void corsPreflightAnswersForTheTargetMethod() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryPost("/pay", request -> HttpResponse.response("text/plain", "paid")));

    try (final var client = HttpClient.newHttpClient()) {
      final var preflight = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/pay"))
              .timeout(Duration.ofSeconds(2))
              .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
              .header("Origin", "https://app.example")
              .header("Access-Control-Request-Method", "POST")
              .header("Access-Control-Request-Headers", "X-Payment")
              .build(),
          BodyHandlers.ofString());
      assertEquals(200, preflight.statusCode());
      assertEquals("https://app.example",
          preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null),
          "the pre-flight must reflect the origin");
      assertEquals("POST",
          preflight.headers().firstValue("Access-Control-Allow-Methods").orElse(null),
          "browsers reject a pre-flight without Allow-Methods");
      assertEquals("X-Payment",
          preflight.headers().firstValue("Access-Control-Allow-Headers").orElse(null));
    }
  }

  @Test
  void originIsReflectedOnSimpleRequests() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/simple", request -> HttpResponse.response("text/plain", "ok")));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/simple"))
              .timeout(Duration.ofSeconds(2))
              .header("Origin", "https://app.example")
              .GET()
              .build(),
          BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("ok", response.body());
      assertEquals("https://app.example",
          response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    }
  }

  @Test
  void nonBlockingPostRoundTrip() throws Exception {
    final int port = serve(builder ->
        builder.nonBlockingQueryPost("/np", request ->
            HttpResponse.response("text/plain", new String(request.body(), StandardCharsets.UTF_8))));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/np"))
              .timeout(Duration.ofSeconds(2))
              .POST(HttpRequest.BodyPublishers.ofString("posted"))
              .build(),
          BodyHandlers.ofString()
      );
      assertEquals(200, response.statusCode());
      assertEquals("posted", response.body());
    }
  }

  @Test
  void absentHostBindsAllInterfaces() throws Exception {
    for (final String host : new String[]{null, "  "}) {
      final var builder = new NettyBuilderFactory().createBuilder();
      builder.blockingQueryHandler("/w", request -> HttpResponse.response("text/plain", "w"));
      final int port = freePort();
      final var server = builder.createServer(Executors.newVirtualThreadPerTaskExecutor(), host, port);
      server.start();
      started.add(server);
      try (final var client = HttpClient.newHttpClient()) {
        assertEquals("w", get(client, port, "/w").body(), "host=" + host);
      }
    }
  }

  @Test
  void preflightHeadersOnNonOptionsRequestsAreIgnored() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/simple", request -> HttpResponse.response("text/plain", "body")));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/simple"))
              .timeout(Duration.ofSeconds(2))
              .header("Origin", "https://app.example")
              .header("Access-Control-Request-Method", "GET")
              .GET()
              .build(),
          BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("body", response.body(), "a GET with pre-flight headers is a normal request");
    }
  }

  @Test
  void optionsWithoutRequestMethodIsMethodNotAllowed() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/simple", request -> HttpResponse.response("text/plain", "body")));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/simple"))
              .timeout(Duration.ofSeconds(2))
              .header("Origin", "https://app.example")
              .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
              .build(),
          BodyHandlers.ofString());
      assertEquals(405, response.statusCode(),
          "OPTIONS without Access-Control-Request-Method is not a pre-flight");
    }
  }

  @Test
  void bodyOnAGetRequestIsEmptyNotNull() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/len", request ->
            HttpResponse.response("text/plain", String.valueOf(request.body().length))));

    try (final var client = HttpClient.newHttpClient()) {
      assertEquals("0", get(client, port, "/len").body());
    }
  }

  @Test
  void blankRequestMethodHeaderIsNotAPreflight() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/simple", request -> HttpResponse.response("text/plain", "body")));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/simple"))
              .timeout(Duration.ofSeconds(2))
              .header("Origin", "https://app.example")
              .header("Access-Control-Request-Method", " ")
              .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
              .build(),
          BodyHandlers.ofString());
      assertEquals(405, response.statusCode(),
          "a blank Access-Control-Request-Method is not a pre-flight");
    }
  }

  @Test
  void allowMethodsHeaderIsPreflightOnly() throws Exception {
    final int port = serve(builder -> {
      builder.blockingQueryHandler("/g", request -> HttpResponse.response("text/plain", "g"));
      builder.blockingQueryPost("/p", request -> HttpResponse.response("text/plain", "p"));
    });

    try (final var client = HttpClient.newHttpClient()) {
      // Access-Control-Allow-Methods is only meaningful on a pre-flight response
      assertTrue(get(client, port, "/g").headers().firstValue("Access-Control-Allow-Methods").isEmpty(),
          "a simple GET must not advertise allowed methods");

      final var post = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/p"))
              .timeout(Duration.ofSeconds(2))
              .header("Origin", "https://app.example")
              .POST(HttpRequest.BodyPublishers.ofString("x"))
              .build(),
          BodyHandlers.ofString());
      assertTrue(post.headers().firstValue("Access-Control-Allow-Methods").isEmpty(),
          "a simple POST must not advertise allowed methods");
      assertEquals("https://app.example",
          post.headers().firstValue("Access-Control-Allow-Origin").orElse(null),
          "the origin is still reflected on simple requests");
    }
  }

  /// Raw-socket exchange so the request crosses the wire exactly as written — HttpClient
  /// normalizes or refuses the ambiguous targets these cases exist to pin, and cannot speak
  /// HTTP/1.0 or pipeline. The server must close the connection for the read to complete.
  private static String raw(final int port, final String request) throws Exception {
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      final var out = socket.getOutputStream();
      out.write(request.getBytes(StandardCharsets.US_ASCII));
      out.flush();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
    }
  }

  private static String rawGet(final int port, final String requestTarget) throws Exception {
    return raw(port, "GET " + requestTarget + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
  }

  private static int rawStatus(final String response) {
    return Integer.parseInt(response.substring(9, 12));
  }

  /// The first value of {@code name} among a raw response's header lines, or null. Guards a
  /// truncated response (no blank-line terminator) so a mutant that drops the head fails by
  /// its own assertion message, not an opaque index exception.
  private static String rawHeader(final String response, final String name) {
    final int endOfHead = response.indexOf("\r\n\r\n");
    final var head = endOfHead < 0 ? response : response.substring(0, endOfHead);
    for (final var line : head.split("\r\n")) {
      final int colon = line.indexOf(':');
      if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
        return line.substring(colon + 1).strip();
      }
    }
    return null;
  }

  private static String rawBody(final String response) {
    final int endOfHead = response.indexOf("\r\n\r\n");
    return endOfHead < 0 ? "" : response.substring(endOfHead + 4);
  }

  /// Reads exactly one Content-Length delimited response off {@code in}: the head up to and
  /// including the blank line, then the announced body — so a test can assert on the
  /// response before deciding whether the connection should now be closed or reused.
  private static String readResponse(final java.io.InputStream in) throws java.io.IOException {
    final var head = new java.io.ByteArrayOutputStream();
    while (!endsWithBlankLine(head.toByteArray())) {
      final int b = in.read();
      if (b < 0) {
        throw new java.io.EOFException("closed before the response head completed: " + head.toString(StandardCharsets.ISO_8859_1));
      }
      head.write(b);
    }
    final var headText = head.toString(StandardCharsets.ISO_8859_1);
    final var contentLength = rawHeader(headText, "Content-Length");
    final int length = contentLength == null ? 0 : Integer.parseInt(contentLength);
    final var body = in.readNBytes(length);
    if (body.length != length) {
      throw new java.io.EOFException("closed after " + body.length + " of " + length + " body bytes: " + headText);
    }
    return headText + new String(body, StandardCharsets.ISO_8859_1);
  }

  private static boolean endsWithBlankLine(final byte[] bytes) {
    final int n = bytes.length;
    return n >= 4 && bytes[n - 4] == '\r' && bytes[n - 3] == '\n' && bytes[n - 2] == '\r' && bytes[n - 1] == '\n';
  }

  private static void assertClosed(final java.io.InputStream in) throws java.io.IOException {
    assertEquals(-1, in.read(), "the server must close the connection");
  }

  @Test
  void ambiguousPathsAreRefused() throws Exception {
    final int port = serve(builder -> {
      builder.blockingQueryHandler("/echo", request -> HttpResponse.response("text/plain", "QH"));
      builder.blockingPathHandler("/files/", request -> HttpResponse.response("text/plain", "PH:" + request.path()));
    });
    for (final var target : new String[]{
        "/files%2F..%2Fecho", "/files/%2e%2e/echo", "/files/a\\b", "/a%2541", "/echo/../../echo", "//echo",
        // absolute-form: NettyRequest does not reduce it to its path, so the shared routing
        // refuses it (as java-http does; the JDK and Jetty servers reduce it first)
        "http://elsewhere.example/echo"}) {
      final var response = rawGet(port, target);
      assertEquals(400, rawStatus(response), target + " -> " + response);
    }
  }

  @Test
  void dotSegmentsAndBenignEscapesRouteCanonically() throws Exception {
    final int port = serve(builder -> {
      builder.blockingQueryHandler("/echo", request -> HttpResponse.response("text/plain", "QH"));
      builder.blockingPathHandler("/files/", request -> HttpResponse.response("text/plain", "PH:" + request.path()));
    });
    final var resolved = rawGet(port, "/files/../echo");
    assertEquals(200, rawStatus(resolved), resolved);
    assertTrue(resolved.contains("QH"),
        "dot segments must resolve to the canonical target before routing: " + resolved);

    final var decoded = rawGet(port, "/%65cho");
    assertEquals(200, rawStatus(decoded), decoded);
    assertTrue(decoded.contains("QH"),
        "benign escapes must decode before routing: " + decoded);
  }

  @Test
  void handlerSeesTheRawPath() throws Exception {
    final int port = serve(builder ->
        builder.blockingPathHandler("/files/", request ->
            HttpResponse.response("text/plain", "PH:" + request.path())));
    final var response = rawGet(port, "/files/%61bc");
    assertEquals(200, rawStatus(response), response);
    assertTrue(response.contains("PH:/files/%61bc"),
        "canonicalization decides routing only; the handler-visible path stays raw: " + response);
  }

  @Test
  void noContentAndNotModifiedCrossTheWireWithoutABody() throws Exception {
    final int port = serve(builder -> {
      builder.blockingQueryHandler("/gone", request ->
          HttpResponse.response(204, "text/plain", new byte[0]));
      builder.blockingQueryHandler("/same", request ->
          HttpResponse.response(304, "text/plain", new byte[0]));
    });
    try (final var client = HttpClient.newHttpClient()) {
      final var noContent = get(client, port, "/gone");
      assertEquals(204, noContent.statusCode());
      assertEquals("", noContent.body());

      final var notModified = get(client, port, "/same");
      assertEquals(304, notModified.statusCode());
      assertEquals("", notModified.body());
    }
  }

  @Test
  void largeBodyRoundTrips() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryPost("/big", request ->
            HttpResponse.response("application/octet-stream", request.body())));
    final byte[] payload = new byte[512 * 1024];
    for (int i = 0; i < payload.length; ++i) {
      payload[i] = (byte) (i * 31);
    }
    try (final var client = HttpClient.newHttpClient()) {
      final var response = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/big"))
              .timeout(Duration.ofSeconds(2))
              .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
              .build(),
          BodyHandlers.ofByteArray());
      assertEquals(200, response.statusCode());
      assertArrayEquals(payload, response.body(), "the body must round-trip byte-identical");
    }
  }

  @Test
  void headIsMethodNotAllowedWithAllow() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/echo", request -> HttpResponse.response("text/plain", "QH")));
    try (final var client = HttpClient.newHttpClient()) {
      final var response = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/echo"))
              .timeout(Duration.ofSeconds(2))
              .method("HEAD", HttpRequest.BodyPublishers.noBody())
              .build(),
          BodyHandlers.ofString());
      assertEquals(405, response.statusCode(), "HEAD is not derived from GET; routing is explicit");
      assertEquals("GET", response.headers().firstValue("Allow").orElse(null));
    }
  }

  /// `freePort`'s probe-close-rebind window can race a parallel test to the port; retry
  /// with a fresh port when the loser's bind fails. Anything that is not a lost port race
  /// propagates untouched.
  private int start(final software.sava.http_servers.core.server.HttpServerBuilder builder) throws Exception {
    return start(builder, Executors.newVirtualThreadPerTaskExecutor());
  }

  private int start(final software.sava.http_servers.core.server.HttpServerBuilder builder,
                    final java.util.concurrent.Executor executor) throws Exception {
    for (int attempt = 0; ; ++attempt) {
      final int port = freePort();
      final var server = builder.createServer(executor, "localhost", port);
      try {
        server.start();
        started.add(server);
        return port;
      } catch (final Exception e) {
        server.stop();
        if (attempt == 2 || !lostThePortRace(e)) {
          throw e;
        }
      }
    }
  }

  private static boolean lostThePortRace(final Throwable thrown) {
    for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.net.BindException || cause instanceof java.net.ConnectException) {
        return true;
      }
    }
    return false;
  }

  /// A server that cannot bind must throw — never report success and hold a dead server —
  /// and the throw must carry `java.net.BindException` in its cause chain, which is what
  /// [#start]'s port-race retry keys on. Like Jetty's, a failed start leaves the event loops
  /// allocated until `stop()`, so the server is stopped in `finally`.
  @Test
  void startOnAnOccupiedPortThrows() throws Exception {
    try (final var occupant = new ServerSocket(0, 50, java.net.InetAddress.getByName("localhost"))) {
      final var builder = new NettyBuilderFactory().createBuilder();
      builder.blockingQueryHandler("/x", request -> HttpResponse.response("text/plain", "x"));
      final var server = builder.createServer(Executors.newVirtualThreadPerTaskExecutor(), "localhost", occupant.getLocalPort());
      try {
        final var thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, server::start,
            "a server that cannot bind must throw, never report success silently");
        assertTrue(lostThePortRace(thrown), "the bind failure must be discoverable in the cause chain: " + thrown);
      } finally {
        server.stop();
      }
    }
  }

  private record OwnedServer(software.sava.http_servers.core.server.HttpServer server, int port) {
  }

  /// Like [#start], but hands the server back so the caller owns its lifecycle.
  private static OwnedServer startOwned(final software.sava.http_servers.core.server.HttpServerBuilder builder,
                                        final java.util.concurrent.Executor executor) throws Exception {
    for (int attempt = 0; ; ++attempt) {
      final int port = freePort();
      final var server = builder.createServer(executor, "localhost", port);
      try {
        server.start();
        return new OwnedServer(server, port);
      } catch (final Exception e) {
        server.stop();
        if (attempt == 2 || !lostThePortRace(e)) {
          throw e;
        }
      }
    }
  }

  /// A stopped server releases its port — the other half of the lifecycle, and the reason
  /// `HttpServer` is `AutoCloseable`.
  @Test
  void aStoppedServerRefusesConnections() throws Exception {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
         final var client = HttpClient.newHttpClient()) {
      final var builder = new NettyBuilderFactory().createBuilder();
      builder.blockingQueryHandler("/x", request -> HttpResponse.response("text/plain", "x"));
      final var owned = startOwned(builder, executor);
      final var server = owned.server();
      try (server) {
        assertEquals(200, get(client, owned.port(), "/x").statusCode(),
            "the server must answer while running");
      }
      org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
          () -> get(client, owned.port(), "/x"),
          "a stopped server must refuse connections, not keep answering");
      // idempotence (NettyHttpServer's javadoc): a second stop on an already-stopped server
      // takes the null path and does nothing, rather than shutting down a null group set
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(server::stop,
          "stop() on an already-stopped server must be a no-op");
    }
  }

  /// A second `start()` on a running server must be refused, not allocate a second group set
  /// and bind again — a second bind that failed would orphan the first listener, which a later
  /// `stop()` (keyed on the current group set) could not release. The refusal is an
  /// `IllegalStateException` (the shape jdk.httpserver uses) and the running server is
  /// undisturbed.
  @Test
  void secondStartThrowsAndTheRunningServerIsUndisturbed() throws Exception {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
         final var client = HttpClient.newHttpClient()) {
      final var builder = new NettyBuilderFactory().createBuilder();
      builder.blockingQueryHandler("/x", request -> HttpResponse.response("text/plain", "x"));
      final var owned = startOwned(builder, executor);
      try (final var server = owned.server()) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, server::start,
            "a second start must be refused, not bind a second listener");
        assertEquals("x", get(client, owned.port(), "/x").body(),
            "the refused second start must leave the running server alone");
      }
    }
  }

  // ---- what this backend adds beyond the shared contract ----

  @Test
  void errorResponsesAreJson() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/simple", request -> HttpResponse.response("text/plain", "body")));

    try (final var client = HttpClient.newHttpClient()) {
      final var notFound = get(client, port, "/nowhere");
      assertEquals(404, notFound.statusCode());
      assertEquals("application/json", notFound.headers().firstValue("Content-Type").orElse(null));
      assertTrue(notFound.body().contains("msg"), notFound.body());

      final var wrongMethod = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/simple"))
              .timeout(Duration.ofSeconds(2))
              .method("DELETE", HttpRequest.BodyPublishers.noBody())
              .build(),
          BodyHandlers.ofString());
      assertEquals(405, wrongMethod.statusCode());
      assertEquals("application/json", wrongMethod.headers().firstValue("Content-Type").orElse(null));
      assertTrue(wrongMethod.body().contains("msg"), wrongMethod.body());
    }
  }

  /// Netty sends no identifying headers of its own, the cross-backend property Jetty's
  /// `identifyingServerHeadersAreSuppressed` pins. The Content-Type assertion anchors the two
  /// absences: a headerless response would satisfy them otherwise.
  @Test
  void identifyingServerHeadersAreSuppressed() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/plain", request -> HttpResponse.response("text/plain", "ok")));
    final var response = rawGet(port, "/plain");
    assertEquals(200, rawStatus(response), response);
    assertEquals("text/plain", rawHeader(response, "Content-Type"), response);
    assertNull(rawHeader(response, "Server"), "Netty sends no Server header: " + response);
    assertNull(rawHeader(response, "X-Powered-By"), "Netty sends no X-Powered-By header: " + response);
  }

  /// An HTTP/1.1 request with no Host header is answered 200, where RFC 9112 §3.2 requires
  /// 400 — parity with the JDK backend (probed the same way: also 200), a divergence from
  /// Jetty, FusionAuth and Helidon, which refuse it. Pinned so the choice is a recorded one
  /// rather than an accident; NettyController's "Known divergences" names it. Oracle: the JDK
  /// backend as the parity reference, java-http as the backend that refuses.
  @Test
  void hostlessHttp11IsAnswered() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/p", request -> HttpResponse.response("text/plain", "p")));
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      socket.getOutputStream().write("GET /p HTTP/1.1\r\nConnection: close\r\n\r\n"
          .getBytes(StandardCharsets.US_ASCII));
      final var response = readResponse(socket.getInputStream());
      assertEquals(200, rawStatus(response), response);
      assertEquals("p", rawBody(response), response);
    }
  }

  /// Counts dispatches while delegating to a real executor.
  private static final class RecordingExecutor implements java.util.concurrent.Executor {
    private final java.util.concurrent.Executor delegate = Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.concurrent.atomic.AtomicInteger dispatches = new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public void execute(final Runnable command) {
      dispatches.incrementAndGet();
      delegate.execute(command);
    }
  }

  /// Blocking routes must leave the event loop for the executor handed to `createServer`;
  /// non-blocking and cached routes must not be offloaded to it.
  @Test
  void blockingHandlersRunOnTheProvidedExecutor() throws Exception {
    final var serverExecutor = new RecordingExecutor();
    final var builder = new NettyBuilderFactory().createBuilder();
    builder.blockingQueryHandler("/b", request -> HttpResponse.response("text/plain", "b"));
    builder.nonBlockingQueryHandler("/nb", request -> HttpResponse.response("text/plain", "nb"));
    builder.cachedQueryHandler("/c", () -> "{}".getBytes(StandardCharsets.UTF_8));
    final int port = start(builder, serverExecutor);

    try (final var client = HttpClient.newHttpClient()) {
      assertEquals("nb", get(client, port, "/nb").body());
      assertEquals(0, serverExecutor.dispatches.get(), "non-blocking handlers run on the event loop");
      assertEquals("{}", get(client, port, "/c").body());
      assertEquals(0, serverExecutor.dispatches.get(), "cached responses are written from the event loop");
      assertEquals("b", get(client, port, "/b").body());
      assertEquals(1, serverExecutor.dispatches.get(), "blocking handlers must run on the provided executor");
    }
  }

  @Test
  void invalidPortPropagatesTheFailure() throws Throwable {
    final var builder = new NettyBuilderFactory().createBuilder();
    final var logs = recordLogs("software.sava.http_servers.core.server.HttpServerBuilder", () ->
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
            builder.createServer(Executors.newVirtualThreadPerTaskExecutor(), "localhost", -1)));
    assertTrue(logs.stream().anyMatch(r -> r.getThrown() instanceof IllegalArgumentException),
        "the create failure must be logged before the rethrow");
  }

  /// The listener must bind the host it was given and only fall back to the wildcard for an
  /// absent one — read synchronously off the unstarted server, with no socket, no clock and
  /// nothing to clean up (the oracle that cannot time out; `startOnAnOccupiedPortThrows`
  /// reaches the same property through a bind conflict).
  @Test
  void theServerBindsTheRequestedHost() {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var named = new NettyServerBuilder().initRestServer(executor, "localhost", 0).address();
      assertEquals("localhost", named.getHostString(),
          "initRestServer must apply the requested host");
      for (final String host : new String[]{null, "  "}) {
        final var wildcard = new NettyServerBuilder().initRestServer(executor, host, 0).address();
        assertTrue(wildcard.getAddress().isAnyLocalAddress(), "host=" + host + " must bind the wildcard");
      }
    }
  }

  /// The shipped idle timeout is the number the README and the other backends' defaults name:
  /// 30 s, the JDK backend's `idleInterval` and Jetty's connector default. Every in-process
  /// idle property is measured against this constant, so the constant itself is pinned here —
  /// PIT generates no mutant for a static initializer, and a test is the only pin there is.
  @Test
  void theDefaultIdleTimeoutMatchesTheOtherBackends() {
    assertEquals(Duration.ofSeconds(30), NettyServerBuilder.DEFAULT_IDLE_TIMEOUT);
  }

  /// The idle timeout knob is checked where it is set. A zero or negative timeout would close
  /// every connection the instant it was accepted, and an absent timeout or one too large for
  /// nanoseconds would otherwise fail only inside `createServer`; all of them are refused by
  /// the constructor, and a positive one is accepted.
  @Test
  void anUnusableIdleTimeoutIsRefusedAtConstruction() {
    for (final var timeout : new Duration[]{Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(-30)}) {
      assertThrows(IllegalArgumentException.class,
          () -> new NettyServerBuilder(NettyServerBuilder.DEFAULT_MAX_CONTENT_LENGTH, NettyServerBuilder.DEFAULT_IO_THREADS, timeout),
          "a non-positive idle timeout must be refused: " + timeout);
    }
    assertThrows(NullPointerException.class,
        () -> new NettyServerBuilder(NettyServerBuilder.DEFAULT_MAX_CONTENT_LENGTH, NettyServerBuilder.DEFAULT_IO_THREADS, null));
    assertThrows(ArithmeticException.class,
        () -> new NettyServerBuilder(NettyServerBuilder.DEFAULT_MAX_CONTENT_LENGTH, NettyServerBuilder.DEFAULT_IO_THREADS, Duration.ofDays(400_000)),
        "a timeout past the nanosecond range must fail at construction, not at createServer");
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> new NettyServerBuilder(NettyServerBuilder.DEFAULT_MAX_CONTENT_LENGTH, NettyServerBuilder.DEFAULT_IO_THREADS, Duration.ofNanos(1)));
  }

  /// `HttpServer` documents `stop()` as a no-op on a server that never started; here that
  /// means no event loops exist to shut down, and a second stop finds none either.
  @Test
  void stopOnANeverStartedServerIsANoOp() throws Exception {
    final var builder = new NettyBuilderFactory().createBuilder();
    builder.blockingQueryHandler("/x", request -> HttpResponse.response("text/plain", "x"));
    final var server = builder.createServer(Executors.newVirtualThreadPerTaskExecutor(), "localhost", 0);
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(server::stop);
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(server::close);
  }

  private static void await(final java.util.concurrent.CountDownLatch latch) {
    try {
      latch.await();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /// Two pipelined GETs on one connection whose handlers would finish in reverse order — the
  /// first blocks on a latch the test releases only after the second is on the wire and a
  /// round trip on a second connection has completed — must still be answered in request
  /// order (RFC 9112 §9.3.2). The builder is given one I/O thread so both connections share
  /// an event loop: that loop cannot serve the probe before it has processed everything
  /// already readable on the first connection, so an implementation that dispatched the
  /// second (inline) request instead of queueing it would have written its response before
  /// the probe returned, i.e. before the first response — the ordering violation this pins.
  @Test
  void pipelinedResponsesArriveInRequestOrder() throws Exception {
    final var release = new java.util.concurrent.CountDownLatch(1);
    final var builder = new NettyServerBuilder(NettyServerBuilder.DEFAULT_MAX_CONTENT_LENGTH, 1, NettyServerBuilder.DEFAULT_IDLE_TIMEOUT);
    builder.blockingQueryHandler("/first", request -> {
      await(release);
      return HttpResponse.response("text/plain", "first");
    });
    builder.nonBlockingQueryHandler("/second", request -> HttpResponse.response("text/plain", "second"));
    builder.nonBlockingQueryHandler("/probe", request -> HttpResponse.response("text/plain", "probe"));
    final int port = start(builder);

    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      final var out = socket.getOutputStream();
      out.write(("GET /first HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
          + "GET /second HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      out.flush();

      final var probe = rawGet(port, "/probe");
      assertEquals(200, rawStatus(probe), probe);
      release.countDown();

      final var in = socket.getInputStream();
      final var first = readResponse(in);
      assertEquals(200, rawStatus(first), first);
      assertEquals("first", rawBody(first), "the first request's response must come first: " + first);
      final var second = readResponse(in);
      assertEquals(200, rawStatus(second), second);
      assertEquals("second", rawBody(second), second);
      assertClosed(in);
    } finally {
      // a failure before the release would otherwise leave the handler parked forever
      release.countDown();
    }
  }

  /// An HTTP/1.0 client — no Host header, no keep-alive — gets a complete, Content-Length
  /// delimited response, after which the server closes (RFC 1945 §1.3: the server closes the
  /// connection after sending the response). The reply is `HTTP/1.1` whatever version the
  /// request named — the cross-backend property the README states and the other three
  /// backends hold (RFC 9112 §2.3: a server answers in its own highest conformant version);
  /// only the keep-alive default is keyed on the request's `HTTP/1.0`.
  @Test
  void http10RequestIsAnsweredThenClosed() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/p", request -> HttpResponse.response("text/plain", "p")));
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      socket.getOutputStream().write("GET /p HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      final var in = socket.getInputStream();
      final var response = readResponse(in);
      assertTrue(response.startsWith("HTTP/1.1 200 "), "the reply version is HTTP/1.1, as on every backend: " + response);
      assertEquals("1", rawHeader(response, "Content-Length"), response);
      assertEquals("p", rawBody(response));
      assertFalse("keep-alive".equalsIgnoreCase(rawHeader(response, "Connection")),
          "a connection about to close must not claim keep-alive: " + response);
      assertClosed(in);
    }
  }

  /// An HTTP/1.0 client that asked for keep-alive must be told the connection stays open —
  /// `Connection: keep-alive` on the response (RFC 9112 §9.3.1), or the client reads to an
  /// EOF that never comes — and the connection must then really serve the next request.
  @Test
  void http10KeepAliveIsHonoured() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/p", request -> HttpResponse.response("text/plain", "p")));
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      final var out = socket.getOutputStream();
      final var in = socket.getInputStream();
      out.write("GET /p HTTP/1.0\r\nConnection: keep-alive\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      final var kept = readResponse(in);
      assertEquals(200, rawStatus(kept), kept);
      assertTrue("keep-alive".equalsIgnoreCase(rawHeader(kept, "Connection")),
          "an HTTP/1.0 keep-alive must be acknowledged explicitly: " + kept);
      assertEquals("p", rawBody(kept));

      out.write("GET /p HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      final var last = readResponse(in);
      assertEquals(200, rawStatus(last), "the kept connection must serve the next request: " + last);
      assertClosed(in);
    }
  }

  /// `Connection: close` on an HTTP/1.1 request is answered with `Connection: close` and the
  /// connection is closed once the response is out (RFC 9112 §9.6).
  @Test
  void connectionCloseIsHonoured() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/p", request -> HttpResponse.response("text/plain", "p")));
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      socket.getOutputStream().write("GET /p HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
          .getBytes(StandardCharsets.US_ASCII));
      final var in = socket.getInputStream();
      final var response = readResponse(in);
      assertEquals(200, rawStatus(response), response);
      assertTrue("close".equalsIgnoreCase(rawHeader(response, "Connection")), response);
      assertClosed(in);
    }
  }

  /// A request the codec cannot parse (a header line without a colon) is refused with 400
  /// and the connection closed — the decoder has discarded the rest of the stream, so
  /// nothing after it can be answered.
  @Test
  void malformedRequestsAreRefusedAndClosed() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/p", request -> HttpResponse.response("text/plain", "p")));
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      socket.getOutputStream().write("GET /p HTTP/1.1\r\nHost: 127.0.0.1\r\nNoColon\r\n\r\n"
          .getBytes(StandardCharsets.US_ASCII));
      final var in = socket.getInputStream();
      final var response = readResponse(in);
      assertEquals(400, rawStatus(response), response);
      assertEquals("application/json", rawHeader(response, "Content-Type"), response);
      assertTrue(rawBody(response).contains("msg"), response);
      assertClosed(in);
    }
  }

  /// Bodies past the builder's aggregation limit are refused with 413 by the aggregator —
  /// a bound the other backends do not have, documented on `NettyController`.
  @Test
  void oversizedBodiesAreRefusedWith413() throws Exception {
    final var builder = new NettyServerBuilder(256, NettyServerBuilder.DEFAULT_IO_THREADS, NettyServerBuilder.DEFAULT_IDLE_TIMEOUT);
    builder.blockingQueryPost("/big", request ->
        HttpResponse.response("application/octet-stream", request.body()));
    final int port = start(builder);

    try (final var client = HttpClient.newHttpClient()) {
      final var response = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/big"))
              .timeout(Duration.ofSeconds(2))
              .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[512]))
              .build(),
          BodyHandlers.ofString());
      assertEquals(413, response.statusCode());
    }
  }

  /// A bare `?` is an empty query, not an absent one (RFC 3986 §3.4: the query component
  /// begins at the `?`), matching the JDK backend's `getRawQuery`.
  @Test
  void bareQuestionMarkYieldsAnEmptyQuery() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/q", request ->
            HttpResponse.response("text/plain", String.valueOf(request.query()))));
    final var response = rawGet(port, "/q?");
    assertEquals(200, rawStatus(response), response);
    assertEquals("0", rawHeader(response, "Content-Length"), response);
    assertEquals("", rawBody(response), "a bare '?' must yield the empty query, not null");
  }

  /// 204 and 304 carry neither Content-Length nor Transfer-Encoding: RFC 9110 §8.6 forbids
  /// Content-Length on 204, and allows it on 304 only when it equals the payload of the 200
  /// it stands for — which a handler answering 304 here cannot know — so the adapter sends
  /// no framing at all, as the JDK adapter does with contentLen -1.
  ///
  /// The Content-Type assertion is what keeps the two absences honest (the reason
  /// FusionAuth's `assertBodylessFraming` carries it): every other check here expects a
  /// header to be missing, so a `rawHeader` that always answered null would satisfy them all
  /// while proving nothing — and dropping the body must not drop the declared type either.
  @Test
  void bodylessStatusesCarryNoFramingHeaders() throws Exception {
    final int port = serve(builder -> {
      builder.blockingQueryHandler("/gone", request ->
          HttpResponse.response(204, "text/plain", new byte[0]));
      builder.blockingQueryHandler("/same", request ->
          HttpResponse.response(304, "text/plain", new byte[0]));
    });
    for (final var target : new String[]{"/gone", "/same"}) {
      final var response = rawGet(port, target);
      assertNull(rawHeader(response, "Content-Length"), target + " -> " + response);
      assertNull(rawHeader(response, "Transfer-Encoding"), target + " -> " + response);
      assertEquals("text/plain", rawHeader(response, "Content-Type"), target + " -> " + response);
      assertEquals("", rawBody(response), target + " -> " + response);
    }
  }

  /// An `Error` thrown by a non-blocking handler passes the dispatch guard (which answers
  /// `RuntimeException` only, like the JDK adapter) and reaches the pipeline, where Netty's
  /// default is to log it and leave the connection open — the client would hang. The
  /// controller's `exceptionCaught` must answer 500, log it, and close. Read raw so the
  /// close is asserted (the JSON body and `close` are half `exceptionCaught`'s contract and
  /// no mutator pins the `false` literal on the write) rather than left to the client bound.
  @Test
  void errorEscapingANonBlockingHandlerIsAnsweredAndLogged() throws Throwable {
    final int port = serve(builder ->
        builder.nonBlockingQueryHandler("/boom-error", request -> {
          throw new AssertionError("handler bug");
        }));

    final var logs = recordLogs(NettyController.class.getName(), () -> {
      try (final var socket = new java.net.Socket("127.0.0.1", port)) {
        socket.setSoTimeout(2_000);
        socket.getOutputStream().write("GET /boom-error HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
            .getBytes(StandardCharsets.US_ASCII));
        final var in = socket.getInputStream();
        final var response = readResponse(in);
        assertEquals(500, rawStatus(response), response);
        assertEquals("application/json", rawHeader(response, "Content-Type"), response);
        assertTrue(rawBody(response).contains("msg"), response);
        assertClosed(in);
      }
    });
    assertTrue(logs.stream().anyMatch(r -> r.getThrown() instanceof AssertionError),
        "the escaped failure must be logged, not swallowed");
  }

  /// The queued sibling of the case above: an `Error` from a handler dispatched off the
  /// *write-completion listener* (the second request of a pipelined pair, drained from the
  /// queue when the first completes) does not reach `exceptionCaught` on its own — Netty's
  /// promise notifier logs and swallows a listener throw (`DefaultPromise.notifyListener0`),
  /// which would leave the client unanswered and the connection stuck in flight. The
  /// controller guards the listener body and routes the throw to `exceptionCaught`, so the
  /// second request is answered 500 and the connection closed. Modelled on
  /// `pipelinedResponsesArriveInRequestOrder`: one I/O thread and a probe on a second
  /// connection ensure the loop has queued the second request before the first is released.
  @Test
  void errorEscapingAQueuedHandlerIsAnsweredAndLogged() throws Throwable {
    final var release = new java.util.concurrent.CountDownLatch(1);
    final var builder = new NettyServerBuilder(NettyServerBuilder.DEFAULT_MAX_CONTENT_LENGTH, 1, NettyServerBuilder.DEFAULT_IDLE_TIMEOUT);
    builder.blockingQueryHandler("/first", request -> {
      await(release);
      return HttpResponse.response("text/plain", "first");
    });
    builder.nonBlockingQueryHandler("/boom-error", request -> {
      throw new AssertionError("handler bug");
    });
    builder.nonBlockingQueryHandler("/probe", request -> HttpResponse.response("text/plain", "probe"));
    final int port = start(builder);

    final var logs = recordLogs(NettyController.class.getName(), () -> {
      try (final var socket = new java.net.Socket("127.0.0.1", port)) {
        socket.setSoTimeout(2_000);
        final var out = socket.getOutputStream();
        out.write(("GET /first HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
            + "GET /boom-error HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        out.flush();

        final var probe = rawGet(port, "/probe");
        assertEquals(200, rawStatus(probe), probe);
        release.countDown();

        final var in = socket.getInputStream();
        final var first = readResponse(in);
        assertEquals(200, rawStatus(first), first);
        assertEquals("first", rawBody(first), "the first request's response must come first: " + first);
        final var second = readResponse(in);
        assertEquals(500, rawStatus(second), "the queued handler's Error must be answered 500, not swallowed: " + second);
        assertEquals("application/json", rawHeader(second, "Content-Type"), second);
        assertTrue(rawBody(second).contains("msg"), second);
        assertClosed(in);
      } finally {
        // a failure before the release would otherwise leave the handler parked forever
        release.countDown();
      }
    });
    assertTrue(logs.stream().anyMatch(r -> r.getThrown() instanceof AssertionError),
        "the escaped failure must be logged, not swallowed");
  }

  /// The pre-flight answer has no body, and on a persistent HTTP/1.1 connection a response
  /// with neither Content-Length nor Transfer-Encoding is delimited only by the close that
  /// never comes (RFC 9112 §6.3) — so it must announce `Content-Length: 0`. Read raw, so the
  /// framing is asserted rather than waited out by a client bound.
  @Test
  void corsPreflightIsContentLengthDelimited() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryPost("/pay", request -> HttpResponse.response("text/plain", "paid")));
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      socket.getOutputStream().write(("OPTIONS /pay HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: https://app.example\r\n"
          + "Access-Control-Request-Method: POST\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      final var in = socket.getInputStream();
      final var response = readResponse(in);
      assertEquals(200, rawStatus(response), response);
      assertEquals("0", rawHeader(response, "Content-Length"), response);
      assertEquals("POST", rawHeader(response, "Access-Control-Allow-Methods"), response);
      assertClosed(in);
    }
  }

  /// A pre-flight without `Access-Control-Request-Headers` is still a pre-flight (the Fetch
  /// standard sends that header only when the actual request carries non-safelisted
  /// headers); it is answered with the origin and method and no Allow-Headers, where Netty's
  /// header map would otherwise refuse the null value the other backends treat as a no-op.
  @Test
  void corsPreflightWithoutRequestHeadersAnswers() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryPost("/pay", request -> HttpResponse.response("text/plain", "paid")));

    try (final var client = HttpClient.newHttpClient()) {
      final var preflight = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/pay"))
              .timeout(Duration.ofSeconds(2))
              .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
              .header("Origin", "https://app.example")
              .header("Access-Control-Request-Method", "POST")
              .build(),
          BodyHandlers.ofString());
      assertEquals(200, preflight.statusCode());
      assertEquals("https://app.example",
          preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
      assertEquals("POST", preflight.headers().firstValue("Access-Control-Allow-Methods").orElse(null));
      assertTrue(preflight.headers().firstValue("Access-Control-Allow-Headers").isEmpty(),
          "no requested headers, nothing to allow");
      assertEquals("", preflight.body());
    }
  }

  // ---- ordering and persistence are decided per connection, whoever writes the response ----

  /// A pipelined request the aggregator answers on its own — here an unsupported `Expect`,
  /// answered 417 before any handler runs — must still wait its turn behind the request in
  /// flight: HTTP/1.1 pipelining requires responses in request order whoever writes them
  /// (RFC 9112 §9.3.2), or the client pairs the 417 with the GET. Same shape as
  /// `pipelinedResponsesArriveInRequestOrder`: one I/O thread and a probe on a second
  /// connection guarantee the loop has read both requests before the first handler is
  /// released. The POST asks to close, so the request's own `Connection: close` must be
  /// honoured even though the controller never saw it (§9.6).
  @Test
  void pipelinedExpectationFailureKeepsRequestOrder() throws Exception {
    final var release = new java.util.concurrent.CountDownLatch(1);
    final var builder = new NettyServerBuilder(NettyServerBuilder.DEFAULT_MAX_CONTENT_LENGTH, 1, NettyServerBuilder.DEFAULT_IDLE_TIMEOUT);
    builder.blockingQueryHandler("/slow", request -> {
      await(release);
      return HttpResponse.response("text/plain", "slow");
    });
    builder.nonBlockingQueryPost("/p", request -> HttpResponse.response("text/plain", "posted"));
    builder.nonBlockingQueryHandler("/probe", request -> HttpResponse.response("text/plain", "probe"));
    final int port = start(builder);

    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      final var out = socket.getOutputStream();
      out.write(("GET /slow HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
          + "POST /p HTTP/1.1\r\nHost: 127.0.0.1\r\nExpect: foo\r\nContent-Length: 2\r\nConnection: close\r\n\r\nhi")
          .getBytes(StandardCharsets.US_ASCII));
      out.flush();

      final var probe = rawGet(port, "/probe");
      assertEquals(200, rawStatus(probe), probe);
      release.countDown();

      final var in = socket.getInputStream();
      final var first = readResponse(in);
      assertEquals(200, rawStatus(first), "the in-flight request's response must come first: " + first);
      assertEquals("slow", rawBody(first), first);
      final var second = readResponse(in);
      assertEquals(417, rawStatus(second), "the unsupported expectation is answered after it: " + second);
      assertClosed(in);
    } finally {
      // a failure before the release would otherwise leave the handler parked forever
      release.countDown();
    }
  }

  /// The oversized sibling: a pipelined request whose `Content-Length` exceeds the aggregation
  /// limit is answered 413 *after* the in-flight response, and the connection is then closed
  /// — never the other way round, which loses the earlier response entirely (its write lands
  /// on a closed channel). Read to EOF: the close is part of the contract.
  @Test
  void pipelinedOversizedRequestKeepsRequestOrder() throws Exception {
    final var release = new java.util.concurrent.CountDownLatch(1);
    final var builder = new NettyServerBuilder(256, 1, NettyServerBuilder.DEFAULT_IDLE_TIMEOUT);
    builder.blockingQueryHandler("/slow", request -> {
      await(release);
      return HttpResponse.response("text/plain", "slow");
    });
    builder.nonBlockingQueryPost("/big", request ->
        HttpResponse.response("application/octet-stream", request.body()));
    builder.nonBlockingQueryHandler("/probe", request -> HttpResponse.response("text/plain", "probe"));
    final int port = start(builder);

    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      final var out = socket.getOutputStream();
      final byte[] body = new byte[512];
      final var head = ("GET /slow HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
          + "POST /big HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: " + body.length + "\r\n\r\n")
          .getBytes(StandardCharsets.US_ASCII);
      final var wire = new byte[head.length + body.length];
      System.arraycopy(head, 0, wire, 0, head.length);
      System.arraycopy(body, 0, wire, head.length, body.length);
      out.write(wire);
      out.flush();

      final var probe = rawGet(port, "/probe");
      assertEquals(200, rawStatus(probe), probe);
      release.countDown();

      final var in = socket.getInputStream();
      final var first = readResponse(in);
      assertEquals(200, rawStatus(first), "the in-flight request's response must come first: " + first);
      assertEquals("slow", rawBody(first), first);
      final var second = readResponse(in);
      assertEquals(413, rawStatus(second), "the oversized request is refused after it: " + second);
      assertEquals("0", rawHeader(second, "Content-Length"),
          "the 413 is framed so it can be delimited before the close (RFC 9112 §6.3): " + second);
      assertClosed(in);
    } finally {
      release.countDown();
    }
  }

  /// A handler that answers with `Connection: close` on a plain HTTP/1.1 keep-alive request is
  /// asking the server to end the connection after this response (RFC 9112 §9.6): the header
  /// must reach the wire and the connection must close. Persistence is decided from both
  /// sides — the request's keep-alive and the response's close directive — not from the
  /// request alone.
  @Test
  void handlerConnectionCloseIsHonoured() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/bye", request ->
            HttpResponse.response("text/plain", "bye").withHeader("Connection", "close")));
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      socket.getOutputStream().write("GET /bye HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
          .getBytes(StandardCharsets.US_ASCII));
      final var in = socket.getInputStream();
      final var response = readResponse(in);
      assertEquals(200, rawStatus(response), response);
      assertEquals("bye", rawBody(response), response);
      assertTrue("close".equalsIgnoreCase(rawHeader(response, "Connection")),
          "the handler's Connection: close must reach the wire: " + response);
      assertClosed(in);
    }
  }

  /// `Expect: 100-continue` is answered with `100 Continue` before the client sends the body
  /// (RFC 9110 §10.1.1), and the request then completes with that body. The client here
  /// sends nothing past the head until the interim response has arrived, so the `100`
  /// provably precedes the body rather than racing it.
  @Test
  void expectContinueIsAnsweredBeforeTheBodyIsSent() throws Exception {
    final int port = serve(builder ->
        builder.nonBlockingQueryPost("/p", request ->
            HttpResponse.response("text/plain", new String(request.body(), StandardCharsets.UTF_8))));
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      final var out = socket.getOutputStream();
      final var in = socket.getInputStream();
      out.write(("POST /p HTTP/1.1\r\nHost: 127.0.0.1\r\nExpect: 100-continue\r\nContent-Length: 2\r\nConnection: close\r\n\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      final var interim = readResponse(in);
      assertEquals(100, rawStatus(interim), "the interim response must come before any body is sent: " + interim);

      out.write("hi".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      final var response = readResponse(in);
      assertEquals(200, rawStatus(response), response);
      assertEquals("hi", rawBody(response), response);
      assertClosed(in);
    }
  }

  /// A connection that sends nothing is closed by the server once the idle timeout has
  /// elapsed — parity with the JDK backend's idle interval and Jetty's connector idle timeout
  /// (both 30 s by default, as is this backend's, pinned by
  /// `theDefaultIdleTimeoutMatchesTheOtherBackends`), observed here through the builder's
  /// knob at 200 ms as the client's EOF. This is the suite's one case that waits on real time, and it waits for an event:
  /// the 2 s socket bound is the fixture's emergency exit, ten times the timeout and inside
  /// PIT's margin (recorded duration × 1.25 + 4 s), so a server that never closes fails by
  /// `SocketTimeoutException`, not by the watchdog. The EOF must not come early either: the
  /// server measures on the same `System.nanoTime` as this test and arms its deadline only
  /// once the connection exists, so the elapsed time is bounded below by the timeout itself.
  @Test
  void anIdleConnectionIsClosedAfterTheIdleTimeout() throws Exception {
    final var idleTimeout = Duration.ofMillis(200);
    final var builder = new NettyServerBuilder(NettyServerBuilder.DEFAULT_MAX_CONTENT_LENGTH, NettyServerBuilder.DEFAULT_IO_THREADS, idleTimeout);
    builder.blockingQueryHandler("/p", request -> HttpResponse.response("text/plain", "p"));
    final int port = start(builder);
    final long connecting = System.nanoTime();
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      assertEquals(-1, socket.getInputStream().read(), "the server must close an idle connection");
      final long elapsed = System.nanoTime() - connecting;
      assertTrue(elapsed >= idleTimeout.toNanos(),
          "closed " + elapsed + " ns after connecting, inside the " + idleTimeout.toNanos() + " ns idle timeout");
    }
  }
}
