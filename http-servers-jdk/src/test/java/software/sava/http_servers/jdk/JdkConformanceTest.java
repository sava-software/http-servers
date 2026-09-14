package software.sava.http_servers.jdk;

import org.junit.jupiter.api.Test;
import software.sava.http_servers.core.response.HttpResponse;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the parts of the core Request/HttpResponse contract that every backend must agree
/// on: the raw query string, 500 on a throwing handler (never a hang or connection abort),
/// custom status/header propagation (the x402 payment-gate shape), cached JSON responses,
/// and case-insensitive request-header lookup — and, at the end, the adapter's connection
/// hygiene: a client that leaves mid-exchange never leaves a connection registered in
/// jdk.httpserver.
final class JdkConformanceTest {

  private static int freePort() throws Exception {
    try (final var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static int serve(final java.util.function.Consumer<software.sava.http_servers.core.server.HttpServerBuilder> register)
      throws Exception {
    final var builder = new JDKHttpServerBuilderFactory().createBuilder();
    register.accept(builder);
    return start(builder);
  }

  private static java.net.http.HttpResponse<String> get(final HttpClient client, final int port, final String pathAndQuery)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery))
            .timeout(Duration.ofSeconds(10))
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

  /// Like [#recordLogs(String, Executable)], with the logger opened to `level` for the
  /// duration and restored afterwards: the adapter's DEBUG records are JUL FINE, below the
  /// default INFO gate.
  private static List<LogRecord> recordLogs(final String loggerName,
                                            final Level level,
                                            final org.junit.jupiter.api.function.Executable body) throws Throwable {
    final var jul = java.util.logging.Logger.getLogger(loggerName);
    final var previous = jul.getLevel();
    jul.setLevel(level);
    try {
      return recordLogs(loggerName, body);
    } finally {
      jul.setLevel(previous);
    }
  }

  @Test
  void throwingBlockingHandlerAnswers500() throws Throwable {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/boom", request -> {
          throw new IllegalStateException("handler bug");
        }));

    final var logs = recordLogs(JdkController.class.getName(), () -> {
      try (final var client = HttpClient.newHttpClient()) {
        assertEquals(500, get(client, port, "/boom").statusCode());
      }
    });
    org.junit.jupiter.api.Assertions.assertTrue(
        logs.stream().anyMatch(r -> r.getThrown() instanceof IllegalStateException),
        "the handler failure must be logged, not swallowed");
  }

  /// Non-blocking handlers run inline on the server executor (see JdkQueryHandler), so the
  /// controller's guard is what answers the 500 and logs the failure — the same guard, and
  /// the same logger, as for a blocking handler.
  @Test
  void throwingNonBlockingHandlerAnswers500() throws Throwable {
    final int port = serve(builder ->
        builder.nonBlockingQueryHandler("/boom", request -> {
          throw new IllegalStateException("handler bug");
        }));

    final var logs = recordLogs(JdkController.class.getName(), () -> {
      try (final var client = HttpClient.newHttpClient()) {
        assertEquals(500, get(client, port, "/boom").statusCode());
      }
    });
    org.junit.jupiter.api.Assertions.assertTrue(
        logs.stream().anyMatch(r -> r.getThrown() instanceof IllegalStateException),
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
              .timeout(Duration.ofSeconds(10))
              .GET()
              .build(),
          BodyHandlers.ofByteArray()
      );
      assertEquals(200, response.statusCode());
      assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null));
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
              .timeout(Duration.ofSeconds(10))
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
  void nonBlockingPostRoundTrip() throws Exception {
    final int port = serve(builder ->
        builder.nonBlockingQueryPost("/np", request ->
            HttpResponse.response("text/plain", new String(request.body(), StandardCharsets.UTF_8))));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/np"))
              .timeout(Duration.ofSeconds(10))
              .POST(HttpRequest.BodyPublishers.ofString("posted"))
              .build(),
          BodyHandlers.ofString()
      );
      assertEquals(200, response.statusCode());
      assertEquals("posted", response.body());
    }
  }

  /// `Request.body()` may be read more than once and hands back the same bytes every time —
  /// the Netty and FusionAuth backends return the body they hold, and the core contract names
  /// no single-read restriction. jdk.httpserver's request stream is single-shot, so the adapter
  /// keeps the bytes; without that a handler's second read failed like a client leaving
  /// mid-upload — nothing answered, the connection closed, a `DEBUG` record — and a real handler
  /// defect was invisible.
  @Test
  void theRequestBodyCanBeReadMoreThanOnce() throws Throwable {
    final int port = serve(builder ->
        builder.blockingQueryPost("/twice", request -> {
          final var first = new String(request.body(), StandardCharsets.UTF_8);
          final var second = new String(request.body(), StandardCharsets.UTF_8);
          return HttpResponse.response("text/plain", first + "|" + second);
        }));

    final var logs = recordLogs(JdkController.class.getName(), Level.ALL, () -> {
      try (final var client = HttpClient.newHttpClient()) {
        final var response = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/twice"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString("posted"))
                .build(),
            BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode(), "a second body read is not a failure of any kind");
        assertEquals("posted|posted", response.body(), "every read hands back the request's bytes");
      }
    });
    assertTrue(logs.isEmpty(), "a second body read is neither a client departure nor a handler failure: " + describe(logs));
  }

  /// Counts dispatches while delegating to a real virtual-thread executor whose threads carry
  /// `namePrefix`, so a handler can report which executor it ran on.
  private static final class RecordingExecutor implements java.util.concurrent.Executor {
    private final java.util.concurrent.Executor delegate;
    private final AtomicInteger dispatches = new AtomicInteger();

    RecordingExecutor(final String namePrefix) {
      this.delegate = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(namePrefix, 0).factory());
    }

    @Override
    public void execute(final Runnable command) {
      dispatches.incrementAndGet();
      delegate.execute(command);
    }
  }

  private static HttpResponse currentThreadName(final software.sava.http_servers.core.request.Request request) {
    return HttpResponse.response("text/plain", Thread.currentThread().getName());
  }

  /// Both registration kinds run on the server executor: jdk.httpserver dispatches every
  /// exchange onto the `Executor` given to `createServer`, and the adapter no longer hops
  /// non-blocking handlers onto a second one — that hop was the leak described on
  /// JdkController. The handler's thread name is the oracle: the executor names its threads.
  @Test
  void nonBlockingAndBlockingHandlersRunOnTheServerExecutor() throws Exception {
    final var serverExecutor = new RecordingExecutor("server-executor-");
    final var builder = new JDKHttpServerBuilderFactory().createBuilder();
    builder.nonBlockingQueryHandler("/nb", JdkConformanceTest::currentThreadName);
    builder.blockingQueryHandler("/b", JdkConformanceTest::currentThreadName);
    final int port = freePort();
    builder.createServer(serverExecutor, "localhost", port).start();

    try (final var client = HttpClient.newHttpClient()) {
      final var nonBlocking = get(client, port, "/nb").body();
      assertTrue(nonBlocking.startsWith("server-executor-"), "non-blocking handlers run on the server executor: " + nonBlocking);
      final var blocking = get(client, port, "/b").body();
      assertTrue(blocking.startsWith("server-executor-"), "blocking handlers run on the server executor: " + blocking);
    }
    assertTrue(serverExecutor.dispatches.get() >= 2, "the server executor receives every exchange");
  }

  /// The deprecated task-executor constructor is honoured only as far as compiling: the
  /// executor it takes receives nothing, and its non-blocking handlers run on the server
  /// executor like everything else.
  @Test
  void theDeprecatedTaskExecutorReceivesNothing() throws Exception {
    final var taskExecutor = new RecordingExecutor("task-executor-");
    @SuppressWarnings("removal")
    final var builder = new JdkServerBuilder(taskExecutor);
    builder.nonBlockingQueryHandler("/nb", JdkConformanceTest::currentThreadName);
    final int port = freePort();
    builder.createServer(new RecordingExecutor("server-executor-"), "localhost", port).start();

    try (final var client = HttpClient.newHttpClient()) {
      final var nonBlocking = get(client, port, "/nb").body();
      assertTrue(nonBlocking.startsWith("server-executor-"), nonBlocking);
    }
    assertEquals(0, taskExecutor.dispatches.get(), "the deprecated task executor is ignored");
  }

  @Test
  void requestsAreDispatchedOnTheServerExecutor() throws Exception {
    final var serverExecutor = new RecordingExecutor("server-executor-");
    final var builder = new JDKHttpServerBuilderFactory().createBuilder();
    builder.blockingQueryHandler("/e", request -> HttpResponse.response("text/plain", "e"));
    final int port = freePort();
    builder.createServer(serverExecutor, "localhost", port).start();

    try (final var client = HttpClient.newHttpClient()) {
      assertEquals("e", get(client, port, "/e").body());
    }
    // the server may dispatch several events per exchange; zero means setExecutor was dropped
    org.junit.jupiter.api.Assertions.assertTrue(serverExecutor.dispatches.get() > 0,
        "the configured executor must dispatch exchanges");
  }

  @Test
  void absentHostBindsAllInterfaces() throws Exception {
    for (final String host : new String[]{null, "  "}) {
      final var builder = new JDKHttpServerBuilderFactory().createBuilder();
      builder.blockingQueryHandler("/w", request -> HttpResponse.response("text/plain", "w"));
      final int port = freePort();
      builder.createServer(Executors.newVirtualThreadPerTaskExecutor(), host, port).start();
      try (final var client = HttpClient.newHttpClient()) {
        assertEquals("w", client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/w"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            BodyHandlers.ofString()
        ).body(), "host=" + host);
      }
    }
  }

  @Test
  void invalidPortPropagatesTheFailure() throws Throwable {
    final var builder = new JDKHttpServerBuilderFactory().createBuilder();
    final var logs = recordLogs("software.sava.http_servers.core.server.HttpServerBuilder", () ->
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
            builder.createServer(Executors.newVirtualThreadPerTaskExecutor(), "localhost", -1)));
    org.junit.jupiter.api.Assertions.assertTrue(
        logs.stream().anyMatch(r -> r.getThrown() instanceof IllegalArgumentException),
        "the create failure must be logged before the rethrow");
  }

  @Test
  void optionsAnswers405WithoutCorsSupport() throws Exception {
    // deliberate divergence: the jdk adapter has no CORS handling, so a pre-flight is an
    // unknown method on the path and gets a 405 with the Allow header
    final int port = serve(builder ->
        builder.blockingQueryPost("/pay", request -> HttpResponse.response("text/plain", "paid")));

    try (final var client = HttpClient.newHttpClient()) {
      final var preflight = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/pay"))
              .timeout(Duration.ofSeconds(10))
              .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
              .header("Origin", "https://app.example")
              .header("Access-Control-Request-Method", "POST")
              .build(),
          BodyHandlers.ofString());
      assertEquals(405, preflight.statusCode());
      assertEquals("POST", preflight.headers().firstValue("Allow").orElse(null));
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

  /// One raw-socket exchange, written onto the wire exactly as given and read until the
  /// server closes the connection. HttpClient normalizes or refuses the ambiguous targets
  /// these cases exist to pin, always speaks HTTP/1.1, and always sends a Host header, so
  /// none of those shapes can be expressed through it. `closed` is false when the 10 s
  /// SO_TIMEOUT expired with the connection still open; for an HTTP/1.0 request that is the
  /// property under test, not a harness failure.
  private record RawExchange(String response, boolean closed) {
  }

  private static RawExchange rawExchange(final int port, final String request) throws Exception {
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      final var out = socket.getOutputStream();
      out.write(request.getBytes(StandardCharsets.US_ASCII));
      out.flush();
      final var in = socket.getInputStream();
      final var received = new java.io.ByteArrayOutputStream();
      final byte[] chunk = new byte[4096];
      boolean closed = true;
      try {
        for (int read; (read = in.read(chunk)) >= 0; ) {
          received.write(chunk, 0, read);
        }
      } catch (final java.net.SocketTimeoutException e) {
        closed = false;
      }
      return new RawExchange(received.toString(StandardCharsets.ISO_8859_1), closed);
    }
  }

  /// Raw-socket GET so the request target crosses the wire exactly as written.
  /// A `Connection: close` request is answered and then closed, so a backend that leaves the
  /// socket open cannot pass by waiting out the read timeout.
  private static String rawGet(final int port, final String requestTarget) throws Exception {
    final var exchange = rawExchange(port,
        "GET " + requestTarget + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
    assertTrue(exchange.closed(),
        "the server must close after Connection: close: " + exchange.response());
    return exchange.response();
  }

  private static int rawStatus(final String response) {
    return Integer.parseInt(response.substring(9, 12));
  }

  /// First value of `name` in the raw response head, or null when the header is absent.
  /// Matching is case-insensitive because header case is each backend's own: the jdk server
  /// writes `Content-length`, jetty `Content-Length`, and java-http lower-cases everything.
  private static String rawHeader(final String response, final String name) {
    final int endOfHead = response.indexOf("\r\n\r\n");
    final var head = endOfHead < 0 ? response : response.substring(0, endOfHead);
    final var lines = head.split("\r\n");
    for (int i = 1; i < lines.length; ++i) {
      final int colon = lines[i].indexOf(':');
      if (colon > 0 && lines[i].substring(0, colon).trim().equalsIgnoreCase(name)) {
        return lines[i].substring(colon + 1).trim();
      }
    }
    return null;
  }

  private static String rawBody(final String response) {
    final int endOfHead = response.indexOf("\r\n\r\n");
    return endOfHead < 0 ? "" : response.substring(endOfHead + 4);
  }

  /// Bodyless-status framing on the wire: never chunked, never a body, and a Content-Length
  /// that is either absent or exactly `0`. `expectedContentLength` is this backend's choice
  /// between those two, asserted precisely so a change of framing fails here instead of
  /// quietly widening the contract.
  ///
  /// The Content-Type assertion is what keeps the rest honest. Every other check here expects
  /// a header to be missing, so a rawHeader that always answered null would satisfy them all
  /// while proving nothing; pinning a header that is present makes the absences mean absence.
  /// It is also a property in its own right: dropping the body does not drop the declared type.
  private static void assertBodylessFraming(final String response, final String expectedContentLength) {
    assertEquals(expectedContentLength, rawHeader(response, "Content-Length"), response);
    org.junit.jupiter.api.Assertions.assertNull(rawHeader(response, "Transfer-Encoding"),
        "a bodyless status must never be chunked: " + response);
    assertEquals("text/plain", rawHeader(response, "Content-Type"),
        "a bodyless status keeps its declared content type: " + response);
    assertEquals("", rawBody(response), response);
  }

  @Test
  void ambiguousPathsAreRefused() throws Exception {
    final int port = serve(builder -> {
      builder.blockingQueryHandler("/echo", request -> HttpResponse.response("text/plain", "QH"));
      builder.blockingPathHandler("/files/", request -> HttpResponse.response("text/plain", "PH:" + request.path()));
    });
    for (final var target : new String[]{
        "/files%2F..%2Fecho", "/files/%2e%2e/echo", "/files/a\\b", "/a%2541", "/echo/../../echo"}) {
      final var response = rawGet(port, target);
      assertEquals(400, rawStatus(response), target + " -> " + response);
    }
    // an empty segment never reaches the controller, but the jdk server's own verdict is
    // JDK-build-dependent ("//echo" parses as an authority-form target): 25.0.2 finds no
    // context and answers 404, 25.0.4 rejects the request URI outright with 400. Pin the
    // library-level invariant — refused before routing — not the JDK's choice of status.
    final var emptySegment = rawGet(port, "//echo");
    final int status = rawStatus(emptySegment);
    assertTrue(status == 400 || status == 404, emptySegment);
    assertFalse(emptySegment.contains("QH"), "an empty-segment target must never route: " + emptySegment);
  }

  @Test
  void dotSegmentsAndBenignEscapesRouteCanonically() throws Exception {
    final int port = serve(builder -> {
      builder.blockingQueryHandler("/echo", request -> HttpResponse.response("text/plain", "QH"));
      builder.blockingPathHandler("/files/", request -> HttpResponse.response("text/plain", "PH:" + request.path()));
    });
    final var resolved = rawGet(port, "/files/../echo");
    assertEquals(200, rawStatus(resolved), resolved);
    org.junit.jupiter.api.Assertions.assertTrue(resolved.contains("QH"),
        "dot segments must resolve to the canonical target before routing: " + resolved);

    final var decoded = rawGet(port, "/%65cho");
    assertEquals(200, rawStatus(decoded), decoded);
    org.junit.jupiter.api.Assertions.assertTrue(decoded.contains("QH"),
        "benign escapes must decode before routing: " + decoded);
  }

  @Test
  void handlerSeesTheRawPath() throws Exception {
    final int port = serve(builder ->
        builder.blockingPathHandler("/files/", request ->
            HttpResponse.response("text/plain", "PH:" + request.path())));
    final var response = rawGet(port, "/files/%61bc");
    assertEquals(200, rawStatus(response), response);
    org.junit.jupiter.api.Assertions.assertTrue(response.contains("PH:/files/%61bc"),
        "canonicalization decides routing only; the handler-visible path stays raw: " + response);
  }

  /// HTTP/1.0 with no Host header, written straight onto the socket: HttpClient always
  /// speaks HTTP/1.1 and always sends Host, so this shape exists nowhere else in the suite.
  /// RFC 9112 s3.2 makes Host mandatory only from HTTP/1.1 onwards, so the 1.0 form is a
  /// well-formed request and has to be answered rather than refused.
  @Test
  void http10RequestIsAnswered() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/p", request -> HttpResponse.response("text/plain", "hello")));
    final var exchange = rawExchange(port, "GET /p HTTP/1.0\r\n\r\n");
    assertEquals(200, rawStatus(exchange.response()), exchange.response());
    assertEquals("hello", rawBody(exchange.response()), exchange.response());
    assertTrue(exchange.closed(),
        "HTTP/1.0 defaults to close, so the server ends the response by closing: " + exchange.response());
  }

  @Test
  void noContentAndNotModifiedCrossTheWireWithoutABody() throws Exception {
    final int port = serve(builder -> {
      builder.blockingQueryHandler("/gone", request ->
          HttpResponse.response(204, "text/plain", new byte[0]));
      builder.blockingQueryHandler("/same", request ->
          HttpResponse.response(304, "text/plain", new byte[0]));
    });
    // the adapter must speak the bodyless-status contract itself (contentLen -1), not be
    // corrected by the jdk server — the correction logs this warning
    final var corrections = new java.util.concurrent.CopyOnWriteArrayList<java.util.logging.LogRecord>();
    final var serverLogger = java.util.logging.Logger.getLogger("com.sun.net.httpserver");
    final var capture = new java.util.logging.Handler() {
      @Override
      public void publish(final java.util.logging.LogRecord record) {
        if (String.valueOf(record.getMessage()).contains("forcing contentLen")) {
          corrections.add(record);
        }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    serverLogger.addHandler(capture);
    try (final var client = HttpClient.newHttpClient()) {
      final var noContent = get(client, port, "/gone");
      assertEquals(204, noContent.statusCode());
      assertEquals("", noContent.body());

      final var notModified = get(client, port, "/same");
      assertEquals(304, notModified.statusCode());
      assertEquals("", notModified.body());

      // and on the raw wire: the jdk adapter sends contentLen -1, so neither bodyless status
      // carries a Content-Length at all, and neither is chunked
      assertBodylessFraming(rawGet(port, "/gone"), null);
      assertBodylessFraming(rawGet(port, "/same"), null);
      assertTrue(corrections.isEmpty(),
          "the jdk server had to force contentLen for a bodyless status: " + corrections.size());
    } finally {
      serverLogger.removeHandler(capture);
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
              .timeout(Duration.ofSeconds(10))
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
              .timeout(Duration.ofSeconds(10))
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
  private static int start(final software.sava.http_servers.core.server.HttpServerBuilder builder) throws Exception {
    final var executor = Executors.newVirtualThreadPerTaskExecutor();
    for (int attempt = 0; ; ++attempt) {
      final int port = freePort();
      try {
        builder.createServer(executor, "localhost", port).start();
        return port;
      } catch (final Exception e) {
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

  /// A server that cannot bind must throw — never report success and hold a dead server.
  /// (java-http itself logs-and-returns on a bind failure; the adapter's listener probe
  /// converts that into the throw this case pins.)
  @Test
  void startOnAnOccupiedPortThrows() throws Exception {
    try (final var occupant = new ServerSocket(0, 50, java.net.InetAddress.getByName("localhost"))) {
      final var builder = new JDKHttpServerBuilderFactory().createBuilder();
      builder.blockingQueryHandler("/x", request -> HttpResponse.response("text/plain", "x"));
      org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
          () -> builder.createServer(Executors.newVirtualThreadPerTaskExecutor(), "localhost", occupant.getLocalPort()).start(),
          "a server that cannot bind must throw, never report success silently");
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
      final var builder = new JDKHttpServerBuilderFactory().createBuilder();
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
    }
  }

  // ---- connection hygiene -----------------------------------------------------------------
  //
  // jdk.httpserver keeps every accepted connection in ServerImpl.allConnections until the
  // dispatcher finishes its response or ServerImpl.closeConnection runs (JdkController's javadoc
  // carries the line references). Each case below is a client that leaves at a point where the
  // adapter used to let jdk.httpserver swallow the failure — a closed channel left in that set
  // for the life of the server — and asserts that the set is back to its size from before the
  // abort, that the departure was logged at DEBUG and never as a server failure, and what, if
  // anything, crossed the wire.

  /// Builds through the concrete builder so the test can hold the jdk.httpserver server the
  /// adapter wraps: `initRestServer` is the protected seam that creates it.
  private static final class CapturingBuilder extends JdkServerBuilder {
    com.sun.net.httpserver.HttpServer jdkServer;

    @Override
    protected com.sun.net.httpserver.HttpServer initRestServer(final java.util.concurrent.Executor executor,
                                                               final String host,
                                                               final int port) {
      jdkServer = super.initRestServer(executor, host, port);
      return jdkServer;
    }
  }

  /// The leak oracle: the size of `ServerImpl.allConnections`, read by reflection through
  /// `HttpServerImpl.server`. Nothing public observes it — no API, log record, timer or
  /// `stop()` grace period distinguishes a registered dead connection from none: the exchange
  /// count `stop(delay)` waits on is not decremented on the repaired path either, the idle timer
  /// scans only the idle sets, and the `maxConnections` cap that would refuse one connection too
  /// many is a JVM-wide static read once at class load. The package is opened by the
  /// `--add-opens` in build.gradle.kts (the test task and the suite's minionJvmArgs).
  private static int registeredConnections(final com.sun.net.httpserver.HttpServer server) {
    try {
      return ((java.util.Set<?>) field(field(server, "server"), "allConnections")).size();
    } catch (final ReflectiveOperationException | RuntimeException e) {
      throw new AssertionError("the leak oracle needs --add-opens jdk.httpserver/sun.net.httpserver, see build.gradle.kts", e);
    }
  }

  private static Object field(final Object target, final String name) throws ReflectiveOperationException {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        final var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
      } catch (final NoSuchFieldException declaredFurtherUp) {
        // keep climbing
      }
    }
    throw new NoSuchFieldException(name + " on " + target.getClass());
  }

  /// Polls the oracle until it reads `expected`: the unregistration happens on the server's
  /// thread after the client is gone, so there is nothing else to wait on. The 2 s bound is
  /// under PIT's per-mutant margin, so a mutant that never unregisters fails here by assertion
  /// rather than by watchdog, and a passing run returns as soon as the set is right.
  private static void assertRegisteredConnections(final com.sun.net.httpserver.HttpServer server,
                                                  final int expected,
                                                  final String why) {
    final long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    int observed = registeredConnections(server);
    while (observed != expected && System.nanoTime() < deadline) {
      java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
      observed = registeredConnections(server);
    }
    assertEquals(expected, observed, why);
  }

  private static final String LEFT_BEFORE_REQUEST_READ = "Client left before its request body was read";
  private static final String LEFT_BEFORE_RESPONSE_WRITTEN = "Client left before its response was written";

  /// Each departure is logged exactly once, at DEBUG, with the I/O failure attached and the
  /// message naming which side of the exchange the client left during — a failed body read or
  /// drain is `LEFT_BEFORE_REQUEST_READ`, a failed response write `LEFT_BEFORE_RESPONSE_WRITTEN` —
  /// and nothing about it is logged as a server failure.
  private static void assertClientLeftLoggedAtDebugOnly(final List<LogRecord> logs,
                                                        final int departures,
                                                        final String message) {
    assertEquals(departures,
        logs.stream().filter(r -> r.getLevel() == Level.FINE && r.getThrown() instanceof java.io.IOException).count(),
        "each departure is logged once at DEBUG with its I/O failure: " + describe(logs));
    assertEquals(departures,
        logs.stream().filter(r -> String.valueOf(r.getMessage()).startsWith(message)).count(),
        "each departure names the side of the exchange the client left during: " + describe(logs));
    assertTrue(logs.stream().noneMatch(r -> r.getLevel().intValue() > Level.FINE.intValue()),
        "a client leaving is not a server failure: " + describe(logs));
  }

  private static String describe(final List<LogRecord> logs) {
    return logs.stream()
        .map(r -> r.getLevel() + ": " + r.getMessage() + (r.getThrown() == null ? "" : " <" + r.getThrown() + ">"))
        .toList()
        .toString();
  }

  private static final String UPLOAD_HEAD =
      " HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/octet-stream\r\nContent-Length: 65536\r\n\r\n";

  /// Sends a POST head declaring 64 KiB and 4 KiB of the body, waits for `serverBusy` — the
  /// point at which the server is provably past the head, so the abandonment lands on the
  /// body — then leaves: with a reset (`SO_LINGER 0`; nothing can be read back), or with a
  /// half-close, after which whatever the server writes is read until it closes and returned.
  /// The wait for `serverBusy` is bounded like the oracle poll; it is not the oracle for any
  /// mutant — a server that never reaches a routed request is caught by the routed probe every
  /// test that waits on a latch sends first — so the bound only keeps a broken fixture from
  /// waiting long.
  private static String abandonUpload(final int port,
                                      final String path,
                                      final CountDownLatch serverBusy,
                                      final boolean reset) throws Exception {
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setTcpNoDelay(true);
      socket.setSoTimeout(10_000);
      final var out = socket.getOutputStream();
      out.write(("POST " + path + UPLOAD_HEAD).getBytes(StandardCharsets.US_ASCII));
      out.write(new byte[4096]);
      out.flush();
      assertTrue(serverBusy.await(2, TimeUnit.SECONDS), "the server never reached the request");
      if (reset) {
        socket.setSoLinger(true, 0);
        return null;
      }
      socket.shutdownOutput();
      final var received = new java.io.ByteArrayOutputStream();
      final byte[] chunk = new byte[4096];
      try {
        for (int read; (read = socket.getInputStream().read(chunk)) >= 0; ) {
          received.write(chunk, 0, read);
        }
      } catch (final java.net.SocketException closedUnderneath) {
        // the server closed with the half-close still pending: as final as EOF
      }
      return received.toString(StandardCharsets.ISO_8859_1);
    }
  }

  /// One routed round trip on its own connection before the aborts, sent by every test whose
  /// abort waits on a latch bounded under PIT's margin. A server that does not start, does not
  /// route, or does not run its handlers fails here the way it fails every other covering
  /// test — a wrong status at once, or a blocked read past PIT's margin — rather than at a
  /// latch bound that only these tests carry, which would make the verdict on such a mutant
  /// depend on which covering test PIT runs first.
  private static void assertRouted(final int port) throws Exception {
    try (final var client = HttpClient.newHttpClient()) {
      assertEquals("probe", get(client, port, "/probe").body(), "the routed probe must be answered before the aborts");
    }
  }

  /// Reads a response head — through its blank line — and no further.
  private static String readHead(final java.io.InputStream in) throws java.io.IOException {
    final var head = new java.io.ByteArrayOutputStream(256);
    int last4 = 0;
    while (last4 != 0x0D0A0D0A) {
      final int b = in.read();
      if (b < 0) {
        throw new java.io.EOFException("closed before the response head completed: " + head.toString(StandardCharsets.ISO_8859_1));
      }
      head.write(b);
      last4 = (last4 << 8) | b;
    }
    return head.toString(StandardCharsets.ISO_8859_1);
  }

  /// The bisected shape: an upload abandoned with a reset — 4 KiB of a declared 64 KiB body,
  /// then `SO_LINGER 0` — leaked one registered connection per abort. The body read failed,
  /// the 500 the controller then wrote failed too, and jdk.httpserver swallowed that second
  /// failure inside `HttpExchange.close()`. Now the body-read failure escapes `handle()`, where
  /// `ServerImpl.closeConnection` unregisters the connection: nothing is answered, the
  /// departure is logged at DEBUG, and the set is back to its pre-abort size after every abort.
  /// The client resets only once the handler has been entered, so the reset lands on the body
  /// read rather than on the head.
  @Test
  void anUploadAbandonedByResetIsUnregistered() throws Throwable {
    final var builder = new CapturingBuilder();
    final var entered = new AtomicReference<>(new CountDownLatch(0));
    builder.blockingQueryPost("/up", request -> {
      entered.get().countDown();
      return HttpResponse.response("text/plain", String.valueOf(request.body().length));
    });
    builder.blockingQueryHandler("/probe", request -> HttpResponse.response("text/plain", "probe"));
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var owned = startOwned(builder, executor);
      final var server = owned.server();
      try (server) {
        final var jdk = builder.jdkServer;
        assertRouted(owned.port());
        assertRegisteredConnections(jdk, 0, "the probe's connection is unregistered once the client closed it: the oracle's baseline");
        final var logs = recordLogs(JdkController.class.getName(), Level.ALL, () -> {
          for (int abort = 1; abort <= 3; ++abort) {
            final var handlerEntered = new CountDownLatch(1);
            entered.set(handlerEntered);
            abandonUpload(owned.port(), "/up", handlerEntered, true);
            assertRegisteredConnections(jdk, 0, "abort " + abort + ": the reset upload's connection must be unregistered");
          }
        });
        assertClientLeftLoggedAtDebugOnly(logs, 3, LEFT_BEFORE_REQUEST_READ);
      }
    }
  }

  /// The half-close half of the same shape: 4 KiB of the body, then `shutdownOutput`. The body
  /// read fails the same way (the JDK's "connection closed before all data received"), and the
  /// outcome is the same — nothing is answered, where this backend used to answer 500 and the
  /// Netty backend never did, and the connection is unregistered.
  @Test
  void anUploadAbandonedByHalfCloseIsNeitherAnsweredNorLeaked() throws Throwable {
    final var builder = new CapturingBuilder();
    final var entered = new AtomicReference<>(new CountDownLatch(0));
    builder.blockingQueryPost("/up", request -> {
      entered.get().countDown();
      return HttpResponse.response("text/plain", String.valueOf(request.body().length));
    });
    builder.blockingQueryHandler("/probe", request -> HttpResponse.response("text/plain", "probe"));
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var owned = startOwned(builder, executor);
      final var server = owned.server();
      try (server) {
        final var jdk = builder.jdkServer;
        assertRouted(owned.port());
        assertRegisteredConnections(jdk, 0, "the probe's connection is unregistered once the client closed it: the oracle's baseline");
        final var logs = recordLogs(JdkController.class.getName(), Level.ALL, () -> {
          for (int abort = 1; abort <= 3; ++abort) {
            final var handlerEntered = new CountDownLatch(1);
            entered.set(handlerEntered);
            final var answered = abandonUpload(owned.port(), "/up", handlerEntered, false);
            assertEquals("", answered, "abort " + abort + ": a truncated upload is answered nothing, not 500");
            assertRegisteredConnections(jdk, 0, "abort " + abort + ": the truncated upload's connection must be unregistered");
          }
        });
        assertClientLeftLoggedAtDebugOnly(logs, 3, LEFT_BEFORE_REQUEST_READ);
      }
    }
  }

  /// The reviewer's shape on the (formerly executor-hopped) non-blocking route: the client
  /// reads the head of a response too large for the loopback buffers — 8 MiB, past the 4 MiB
  /// macOS autotunes each side up to, with the client's receive buffer capped so the server is
  /// still writing — and resets. The write fails on the server's thread and escapes; before,
  /// the catch on the hopped task tried to answer 500 on an exchange whose headers were sent,
  /// swallowed that, and left the connection registered with its channel open.
  @Test
  void aClientResettingMidResponseOnTheNonBlockingRouteIsUnregistered() throws Throwable {
    final var builder = new CapturingBuilder();
    final byte[] big = new byte[8 << 20];
    builder.nonBlockingQueryHandler("/big", request -> HttpResponse.response("application/octet-stream", big));
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var owned = startOwned(builder, executor);
      final var server = owned.server();
      try (server) {
        final var jdk = builder.jdkServer;
        final var logs = recordLogs(JdkController.class.getName(), Level.ALL, () -> {
          for (int abort = 1; abort <= 3; ++abort) {
            try (final var socket = new java.net.Socket()) {
              socket.setReceiveBufferSize(16 << 10);
              socket.connect(new java.net.InetSocketAddress("127.0.0.1", owned.port()), 10_000);
              socket.setTcpNoDelay(true);
              socket.setSoTimeout(10_000);
              socket.getOutputStream().write("GET /big HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
              socket.getOutputStream().flush();
              final var head = readHead(socket.getInputStream());
              assertEquals(200, rawStatus(head), head);
              assertEquals(String.valueOf(big.length), rawHeader(head, "Content-Length"), head);
              socket.setSoLinger(true, 0);
            }
            assertRegisteredConnections(jdk, 0, "abort " + abort + ": the connection reset mid-response must be unregistered");
          }
        });
        assertClientLeftLoggedAtDebugOnly(logs, 3, LEFT_BEFORE_RESPONSE_WRITTEN);
      }
    }
  }

  /// An upload to an unrouted path, reset while the server answers 404 — the reviewer's third
  /// shape. The 404 carries no body, and for that framing jdk.httpserver drains the unread
  /// request body inside `HttpExchange.close()`, where a failure is swallowed; the adapter now
  /// closes the request body on its own frame first, so the reset fails there and escapes. The
  /// client resets once the server has logged the request line: jdk.httpserver's own DEBUG
  /// record `Exchange request line: ...` is the one signal, short of a routed handler, that the
  /// server is inside the exchange, and a 404 has no handler. That record only ever fails to
  /// arrive from a server that was never started, which the routed probe sent first turns
  /// into a blocked read past PIT's margin instead of this test's own 2 s latch bound.
  @Test
  void anUnroutedUploadResetDuringThe404IsUnregistered() throws Throwable {
    final var builder = new CapturingBuilder();
    builder.blockingQueryHandler("/elsewhere", request -> HttpResponse.response("text/plain", "elsewhere"));
    builder.blockingQueryHandler("/probe", request -> HttpResponse.response("text/plain", "probe"));
    final var requestLineSeen = new AtomicReference<>(new CountDownLatch(0));
    final var jdkLogger = java.util.logging.Logger.getLogger("com.sun.net.httpserver");
    final var previous = jdkLogger.getLevel();
    final var onRequestLine = new java.util.logging.Handler() {
      @Override
      public void publish(final LogRecord record) {
        if (String.valueOf(record.getMessage()).contains("Exchange request line")) {
          requestLineSeen.get().countDown();
        }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    jdkLogger.setLevel(Level.ALL);
    jdkLogger.addHandler(onRequestLine);
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var owned = startOwned(builder, executor);
      final var server = owned.server();
      try (server) {
        final var jdk = builder.jdkServer;
        assertRouted(owned.port());
        assertRegisteredConnections(jdk, 0, "the probe's connection is unregistered once the client closed it: the oracle's baseline");
        final var logs = recordLogs(JdkController.class.getName(), Level.ALL, () -> {
          for (int abort = 1; abort <= 3; ++abort) {
            final var seen = new CountDownLatch(1);
            requestLineSeen.set(seen);
            abandonUpload(owned.port(), "/nowhere", seen, true);
            assertRegisteredConnections(jdk, 0, "abort " + abort + ": the connection reset during the 404 must be unregistered");
          }
        });
        assertClientLeftLoggedAtDebugOnly(logs, 3, LEFT_BEFORE_REQUEST_READ);
      }
    } finally {
      jdkLogger.removeHandler(onRequestLine);
      jdkLogger.setLevel(previous);
    }
  }

  /// The half-close variant, which also pins the order of the two steps: the request body is
  /// drained before the head is written, so a truncated upload to an unrouted path is answered
  /// nothing — the drain fails, the failure escapes, the connection is closed and unregistered.
  /// Draining after the head would put a 404 on the wire and then lose the drain's failure
  /// inside `HttpExchange.close()`, with the connection left registered.
  @Test
  void anUnroutedUploadHalfClosedMidBodyIsNeitherAnsweredNorLeaked() throws Throwable {
    final var builder = new CapturingBuilder();
    builder.blockingQueryHandler("/elsewhere", request -> HttpResponse.response("text/plain", "elsewhere"));
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var owned = startOwned(builder, executor);
      final var server = owned.server();
      try (server) {
        final var jdk = builder.jdkServer;
        final var logs = recordLogs(JdkController.class.getName(), Level.ALL, () -> {
          for (int abort = 1; abort <= 3; ++abort) {
            final var answered = abandonUpload(owned.port(), "/nowhere", new CountDownLatch(0), false);
            assertEquals("", answered, "abort " + abort + ": the body is drained before the 404 head, so a truncated upload gets no head");
            assertRegisteredConnections(jdk, 0, "abort " + abort + ": the truncated upload's connection must be unregistered");
          }
        });
        assertClientLeftLoggedAtDebugOnly(logs, 3, LEFT_BEFORE_REQUEST_READ);
      }
    }
  }
}
