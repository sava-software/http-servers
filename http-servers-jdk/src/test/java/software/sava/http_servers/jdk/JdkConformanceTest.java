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
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the parts of the core Request/HttpResponse contract that every backend must agree
/// on: the raw query string, 500 on a throwing handler (never a hang or connection abort),
/// custom status/header propagation (the x402 payment-gate shape), cached JSON responses,
/// and case-insensitive request-header lookup.
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

  @Test
  void throwingNonBlockingHandlerAnswers500() throws Throwable {
    final int port = serve(builder ->
        builder.nonBlockingQueryHandler("/boom", request -> {
          throw new IllegalStateException("handler bug");
        }));

    final var logs = recordLogs(JdkQueryHandler.class.getName(), () -> {
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

  @Test
  void nonBlockingHandlersRunOnTheTaskExecutor() throws Exception {
    final var taskExecutor = new RecordingExecutor();
    final var builder = new JdkServerBuilder(taskExecutor);
    builder.nonBlockingQueryHandler("/nb", request -> HttpResponse.response("text/plain", "nb"));
    builder.blockingQueryHandler("/b", request -> HttpResponse.response("text/plain", "b"));
    final int port = freePort();
    builder.createServer(Executors.newVirtualThreadPerTaskExecutor(), "localhost", port).start();

    try (final var client = HttpClient.newHttpClient()) {
      assertEquals("b", get(client, port, "/b").body());
      assertEquals(0, taskExecutor.dispatches.get(), "blocking handlers must not use the task executor");
      assertEquals("nb", get(client, port, "/nb").body());
      assertEquals(1, taskExecutor.dispatches.get(), "non-blocking handlers must run on the task executor");
    }
  }

  @Test
  void requestsAreDispatchedOnTheServerExecutor() throws Exception {
    final var serverExecutor = new RecordingExecutor();
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

  /// Raw-socket GET so the request target crosses the wire exactly as written — HttpClient
  /// normalizes or refuses the ambiguous targets these cases exist to pin.
  private static String rawGet(final int port, final String requestTarget) throws Exception {
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      final var out = socket.getOutputStream();
      out.write(("GET " + requestTarget + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
    }
  }

  private static int rawStatus(final String response) {
    return Integer.parseInt(response.substring(9, 12));
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
}
