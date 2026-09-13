package software.sava.http_servers.helidon;

import io.helidon.webserver.WebServer;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the parts of the core Request/HttpResponse contract that every backend must agree
/// on: the raw query string, 500 on a throwing handler (never a hang or connection abort),
/// custom status/header propagation (the x402 payment-gate shape), cached JSON responses,
/// and case-insensitive request-header lookup.
///
/// Mirrors `FusionAuthConformanceTest` test-for-test plus Jetty's error-body, logging and
/// server-header pins, adapted only where Helidon's documented divergences require it (each
/// such case names its oracle): `http10RequestIsAnswered` pins the 505 refusal rather than an
/// answer, and the bodyless and HEAD cases accept Helidon's `Content-Length: 0`. Deliberately
/// absent: Jetty's `blockingHandlersRunOnTheProvidedExecutor` and the jdk executor cases —
/// Helidon runs every request on its own virtual thread, refuses a response completed
/// elsewhere and offers no executor injection, so the adapter ignores the executor it is
/// given (as FusionAuth does); `theSuppliedExecutorReceivesNoWork` pins that negative instead.
final class HelidonConformanceTest {

  private static int freePort() throws Exception {
    try (final var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static int serve(final java.util.function.Consumer<software.sava.http_servers.core.server.HttpServerBuilder> register)
      throws Exception {
    final var builder = new HelidonBuilderFactory().createBuilder();
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

  @Test
  void throwingBlockingHandlerAnswers500() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/boom", request -> {
          throw new IllegalStateException("handler bug");
        }));

    try (final var client = HttpClient.newHttpClient()) {
      assertEquals(500, get(client, port, "/boom").statusCode());
    }
  }

  @Test
  void throwingNonBlockingHandlerAnswers500() throws Exception {
    final int port = serve(builder ->
        builder.nonBlockingQueryHandler("/boom", request -> {
          throw new IllegalStateException("handler bug");
        }));

    try (final var client = HttpClient.newHttpClient()) {
      assertEquals(500, get(client, port, "/boom").statusCode());
    }
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
  void corsPreflightAnswersForTheTargetMethod() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryPost("/pay", request -> HttpResponse.response("text/plain", "paid")));

    try (final var client = HttpClient.newHttpClient()) {
      final var preflight = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/pay"))
              .timeout(Duration.ofSeconds(10))
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

  /// Helidon's `header(name, value)` refuses a null value, where java-http and Jetty treat it
  /// as "unset"; the adapter therefore guards the echo. Oracle: the Fetch standard only
  /// requires `Access-Control-Allow-Headers` when the pre-flight listed request headers.
  @Test
  void preflightWithoutRequestHeadersOmitsAllowHeaders() throws Exception {
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
      assertEquals(200, preflight.statusCode(), "a pre-flight naming no request headers is still a pre-flight");
      assertEquals("https://app.example",
          preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
      assertEquals("POST",
          preflight.headers().firstValue("Access-Control-Allow-Methods").orElse(null));
      assertTrue(preflight.headers().firstValue("Access-Control-Allow-Headers").isEmpty(),
          "nothing was requested, so nothing is allowed back");
    }
  }

  @Test
  void originIsReflectedOnSimpleRequests() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/simple", request -> HttpResponse.response("text/plain", "ok")));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/simple"))
              .timeout(Duration.ofSeconds(10))
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
              .timeout(Duration.ofSeconds(10))
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
      final var builder = new HelidonBuilderFactory().createBuilder();
      builder.blockingQueryHandler("/w", request -> HttpResponse.response("text/plain", "w"));
      final int port = freePort();
      builder.createServer(Executors.newVirtualThreadPerTaskExecutor(), host, port).start();
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
              .timeout(Duration.ofSeconds(10))
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
              .timeout(Duration.ofSeconds(10))
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
              .timeout(Duration.ofSeconds(10))
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
      assertTrue(
          get(client, port, "/g").headers().firstValue("Access-Control-Allow-Methods").isEmpty(),
          "a simple GET must not advertise allowed methods");

      final var post = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/p"))
              .timeout(Duration.ofSeconds(10))
              .header("Origin", "https://app.example")
              .POST(HttpRequest.BodyPublishers.ofString("x"))
              .build(),
          BodyHandlers.ofString());
      assertTrue(
          post.headers().firstValue("Access-Control-Allow-Methods").isEmpty(),
          "a simple POST must not advertise allowed methods");
      assertEquals("https://app.example",
          post.headers().firstValue("Access-Control-Allow-Origin").orElse(null),
          "the origin is still reflected on simple requests");
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
  void throwingHandlerFailureIsLogged() throws Throwable {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/boom-logged", request -> {
          throw new IllegalStateException("handler bug");
        }));

    final var logs = recordLogs(HelidonController.class.getName(), () -> {
      try (final var client = HttpClient.newHttpClient()) {
        assertEquals(500, get(client, port, "/boom-logged").statusCode());
      }
    });
    assertTrue(
        logs.stream().anyMatch(r -> r.getThrown() instanceof IllegalStateException),
        "the handler failure must be logged, not swallowed");
  }

  /// Helidon emits neither a `Server` nor an `X-Powered-By` header (measured on 4.5.4:
  /// `Http1ServerResponse` writes `Date`, `Connection` and the length headers only), so
  /// nothing is configured off here — unlike Jetty, whose builder switches both off. Pinned
  /// so a later Helidon feature or version that starts identifying itself is noticed. The
  /// `Date` assertion anchors the two absences: a response with no headers at all would
  /// satisfy them, and Helidon does emit `Date` on every answer.
  @Test
  void identifyingServerHeadersAreSuppressed() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/plain", request -> HttpResponse.response("text/plain", "ok")));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = get(client, port, "/plain");
      assertEquals(200, response.statusCode());
      assertTrue(response.headers().firstValue("Date").isPresent(),
          "Helidon emits Date on every response, so the absences below mean absence");
      assertTrue(response.headers().firstValue("Server").isEmpty(),
          "no Server header is emitted");
      assertTrue(response.headers().firstValue("X-Powered-By").isEmpty(),
          "no X-Powered-By header is emitted");
    }
  }

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
              .timeout(Duration.ofSeconds(10))
              .method("DELETE", HttpRequest.BodyPublishers.noBody())
              .build(),
          BodyHandlers.ofString());
      assertEquals(405, wrongMethod.statusCode());
      assertEquals("application/json", wrongMethod.headers().firstValue("Content-Type").orElse(null));
      assertTrue(wrongMethod.body().contains("msg"), wrongMethod.body());

      final var failed = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/simple"))
              .timeout(Duration.ofSeconds(10))
              .header("Origin", "https://app.example")
              .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
              .build(),
          BodyHandlers.ofString());
      assertEquals(405, failed.statusCode());
    }
    // Helidon does not canonicalize before routing, so unlike Jetty the ambiguous-path 400
    // is this controller's own answer and carries the same JSON shape
    final var ambiguous = rawGet(port, "/a%2541");
    assertEquals(400, rawStatus(ambiguous), ambiguous);
    assertEquals("application/json", rawHeader(ambiguous, "Content-Type"), ambiguous);
    assertTrue(rawBody(ambiguous).contains("msg"), ambiguous);

    // an extension method Helidon has no constant for reaches the controller through the
    // catch-all route (not a method whitelist), so it is a 405 with Allow, never a 501
    final var unknownMethod = rawExchange(port,
        "PROPFIND /simple HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").response();
    assertEquals(405, rawStatus(unknownMethod), unknownMethod);
    assertEquals("GET", rawHeader(unknownMethod, "Allow"), unknownMethod);
    assertEquals("application/json", rawHeader(unknownMethod, "Content-Type"), unknownMethod);
  }

  /// A throwing handler's 500 is the controller's own answer, so it carries the JSON error
  /// shape the other error statuses use rather than Helidon's zero-byte default.
  @Test
  void handlerFailureAnswersJson() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/boom", request -> {
          throw new IllegalStateException("handler bug");
        }));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = get(client, port, "/boom");
      assertEquals(500, response.statusCode());
      assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null));
      assertTrue(response.body().contains("msg"), response.body());
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
  private static String rawGet(final int port, final String requestTarget) throws Exception {
    return raw(port, "GET", requestTarget);
  }

  private static String raw(final int port, final String method, final String requestTarget) throws Exception {
    return rawExchange(port,
        method + ' ' + requestTarget + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").response();
  }

  private static int rawStatus(final String response) {
    return Integer.parseInt(response.substring(9, 12));
  }

  /// First value of `name` in the raw response head, or null when the header is absent.
  /// Matching is case-insensitive because header case is each backend's own.
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
  /// quietly widening the contract — Helidon's is `0`, added to every no-entity status by
  /// upstream design (`Http1ServerResponse`, PR 9408) with no way to drop it.
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

  /// Reads exactly one Content-Length delimited response off {@code in}: the head up to and
  /// including the blank line, then the announced body — so a test can assert on one
  /// response of a persistent connection before reading the next.
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

  @Test
  void ambiguousPathsAreRefused() throws Exception {
    final int port = serve(builder -> {
      builder.blockingQueryHandler("/echo", request -> HttpResponse.response("text/plain", "QH"));
      builder.blockingPathHandler("/files/", request -> HttpResponse.response("text/plain", "PH:" + request.path()));
    });
    // Only the status is pinned per target, because two members are Helidon's own zero-byte
    // 400 (its request-target validation runs before the controller): "/files/a\\b", an
    // illegal character in the target, and "/pct%zz", a malformed percent escape — the
    // divergence HelidonController documents. The rest reach the controller and carry the
    // JSON body, which errorResponsesAreJson pins against "/a%2541". Core's PathCanonicalizer
    // refuses every one of these on every backend; a malformed escape must never route.
    for (final var target : new String[]{
        "/files%2F..%2Fecho", "/files/%2e%2e/echo", "/files/a\\b", "/a%2541", "/echo/../../echo", "//echo",
        "/pct%zz"}) {
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

  /// 205 rides along with the cross-backend 204/304 pair: it is in Helidon's no-entity set
  /// (`Http1ServerResponse`, {204, 205, 304}), where the other backends send whatever the
  /// handler attached, so the adapter has to treat it as bodyless here (RFC 9110 s15.3.6 — a
  /// 205 must not generate content) rather than let Helidon answer 500.
  @Test
  void noContentAndNotModifiedCrossTheWireWithoutABody() throws Exception {
    final int port = serve(builder -> {
      builder.blockingQueryHandler("/gone", request ->
          HttpResponse.response(204, "text/plain", new byte[0]));
      builder.blockingQueryHandler("/reset", request ->
          HttpResponse.response(205, "text/plain", new byte[0]));
      builder.blockingQueryHandler("/same", request ->
          HttpResponse.response(304, "text/plain", new byte[0]));
    });
    try (final var client = HttpClient.newHttpClient()) {
      final var noContent = get(client, port, "/gone");
      assertEquals(204, noContent.statusCode());
      assertEquals("", noContent.body());

      final var resetContent = get(client, port, "/reset");
      assertEquals(205, resetContent.statusCode());
      assertEquals("", resetContent.body());

      final var notModified = get(client, port, "/same");
      assertEquals(304, notModified.statusCode());
      assertEquals("", notModified.body());
    }

    // and on the raw wire: Helidon sends "Content-Length: 0" on every bodyless status by
    // upstream design (PR 9408), where jdk sends none, and chunks none of them
    assertBodylessFraming(rawGet(port, "/gone"), "0");
    assertBodylessFraming(rawGet(port, "/reset"), "0");
    assertBodylessFraming(rawGet(port, "/same"), "0");
  }

  /// The adapter, not the handler, owns the bodyless-status contract: a handler that attaches
  /// content to a 204, 205 or 304 must still produce a content-free answer. Oracle: RFC 9110
  /// sections 15.3.5, 15.3.6 and 15.4.5 — none of these may carry content; Helidon would
  /// otherwise refuse the entity (500) or write it and desync the next keep-alive response.
  /// Read raw with `Connection: close` so the bytes after the header block are observed, not
  /// interpreted away by a client that already knows these statuses are bodyless.
  @Test
  void bodylessStatusesDropAnAttachedBody() throws Exception {
    final int port = serve(builder -> {
      builder.blockingQueryHandler("/gone", request ->
          HttpResponse.response(204, "text/plain", "ignored".getBytes(StandardCharsets.UTF_8)));
      builder.blockingQueryHandler("/reset", request ->
          HttpResponse.response(205, "text/plain", "ignored".getBytes(StandardCharsets.UTF_8)));
      builder.blockingQueryHandler("/same", request ->
          HttpResponse.response(304, "text/plain", "ignored".getBytes(StandardCharsets.UTF_8)));
    });
    final var statuses = java.util.Map.of("/gone", 204, "/reset", 205, "/same", 304);
    for (final var entry : statuses.entrySet()) {
      final var raw = rawGet(port, entry.getKey());
      assertEquals(entry.getValue(), rawStatus(raw), raw);
      assertBodylessFraming(raw, "0");
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

  /// Helidon writes the entity on HEAD instead of stripping it, so the adapter answers HEAD
  /// with status and headers only (the other backends send the JSON 405 body, which their
  /// servers drop). Oracle: RFC 9110 section 9.3.2 — a HEAD response must not carry content,
  /// and one that did would desync the next response on a keep-alive connection. The raw
  /// socket read pins that nothing follows the header block.
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
    final var raw = raw(port, "HEAD", "/echo");
    assertEquals(405, rawStatus(raw), raw);
    assertEquals("GET", rawHeader(raw, "Allow"), raw);
    assertEquals("application/json", rawHeader(raw, "Content-Type"),
        "the bodyless answer keeps the type the GET form declares: " + raw);
    // the recorded choice HelidonController documents: an empty send is framed as 0, not as
    // the 34 bytes the matching GET would declare
    assertEquals("0", rawHeader(raw, "Content-Length"), raw);
    assertEquals("", rawBody(raw), "a HEAD answer must carry no content: " + raw);
  }

  /// HTTP/1.0 written straight onto the socket: HttpClient always speaks HTTP/1.1 and always
  /// sends Host, so this shape exists nowhere else in the suite.
  ///
  /// Documented divergence, deliberately pinned as the *refusal*: Helidon 4 serves HTTP/1.1
  /// only and answers every HTTP/1.0 request with 505 (RFC 9110 s15.6.6; oracle: Helidon
  /// 4.5.4's `Http1Prologue`, "be friendly rejecting 1.0", probed on the wire 2026-09-12 with
  /// and without Host), where the other backends serve it — see README "Backend divergences".
  /// It is also the guard on build.gradle.kts's H2C decision: with `helidon-webserver-http2`
  /// on the module path the refusal becomes a zero-byte close (Host supplied) or a held
  /// connection (Host absent), so this case fails the moment that jar arrives uninvited. The
  /// HTTP/1.1 Host rule is pinned alongside: RFC 9112 s3.2 makes Host mandatory there, and
  /// Helidon answers its own zero-byte 400 for a missing or blank one before the controller.
  @Test
  void http10RequestIsAnswered() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/p", request -> HttpResponse.response("text/plain", "hello")));

    for (final var request : new String[]{
        "GET /p HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n",
        "GET /p HTTP/1.0\r\n\r\n",
        "GET /p HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n"}) {
      final var exchange = rawExchange(port, request);
      assertEquals(505, rawStatus(exchange.response()), request + " -> " + exchange.response());
      assertEquals("0", rawHeader(exchange.response(), "Content-Length"), exchange.response());
      assertTrue(exchange.closed(), "the refusal ends by closing: " + exchange.response());
    }

    for (final var request : new String[]{
        "GET /p HTTP/1.1\r\nConnection: close\r\n\r\n",
        "GET /p HTTP/1.1\r\nHost: \r\nConnection: close\r\n\r\n"}) {
      final var exchange = rawExchange(port, request);
      assertEquals(400, rawStatus(exchange.response()), request + " -> " + exchange.response());
      assertEquals("", rawBody(exchange.response()), "Helidon's own answer carries no body: " + exchange.response());
    }
  }

  /// A handler that sets `Connection: close` ends the connection here as on every other
  /// backend: the header reaches the wire and the server closes after the response, so a
  /// second request on the socket is never served (RFC 9112 s9.6). The response is read by
  /// its Content-Length rather than to EOF so that a dropped header fails the assertion at
  /// once instead of waiting out the socket timeout, which PIT would only see as a watchdog
  /// detection.
  @Test
  void handlerConnectionCloseIsHonoured() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/bye", request ->
            HttpResponse.response("text/plain", "bye").withHeader("Connection", "close")));
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      final var out = socket.getOutputStream();
      out.write("GET /bye HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      final var in = socket.getInputStream();
      final var response = readDelimitedResponse(in);
      assertEquals(200, rawStatus(response), response);
      assertEquals("bye", rawBody(response), response);
      assertTrue("close".equalsIgnoreCase(rawHeader(response, "Connection")),
          "the handler's Connection: close must reach the wire: " + response);
      assertEquals(-1, in.read(), "the server must close after the handler's Connection: close");
    }
  }

  /// Reads one response whose body is Content-Length delimited: the head up to its blank
  /// line, then exactly Content-Length bytes, returning without waiting for the connection
  /// to end.
  private static String readDelimitedResponse(final java.io.InputStream in) throws java.io.IOException {
    final var received = new java.io.ByteArrayOutputStream();
    for (int b; (b = in.read()) >= 0; ) {
      received.write(b);
      if (received.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
        break;
      }
    }
    final var length = rawHeader(received.toString(StandardCharsets.ISO_8859_1), "Content-Length");
    received.write(in.readNBytes(length == null ? 0 : Integer.parseInt(length)));
    return received.toString(StandardCharsets.ISO_8859_1);
  }

  /// A bare `?` reaches the handler as `null` here, where the JDK and Netty backends hand on
  /// the empty string (RFC 3986 s3.4 says the query component begins at the `?`, so both
  /// readings are defensible): Helidon's `UriQuery` is never null and a bare `?` carries
  /// `UriQuery.empty()`, indistinguishable from no query at all, which `HelidonRequest.query()`
  /// maps to the contract's `null` — the FusionAuth side of the README's "empty query" row.
  /// Raw because HttpClient normalizes a bare `?` away.
  @Test
  void bareQuestionMarkYieldsANullQuery() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/q", request ->
            HttpResponse.response("text/plain", String.valueOf(request.query()))));
    final var response = rawGet(port, "/q?");
    assertEquals(200, rawStatus(response), response);
    assertEquals("null", rawBody(response), "a bare '?' must yield null on this backend: " + response);

    final var present = rawGet(port, "/q?x=");
    assertEquals("x=", rawBody(present), "only the bare '?' collapses to null: " + present);
  }

  /// Regression for a framing defect: a handler whose response Helidon refuses to write (a
  /// header value with a CR LF, say — anything derived from request or upstream input can
  /// produce one) fails inside `send()` *after* Helidon has stamped the failed body's
  /// `Content-Length` on the response, and Helidon only fills in a missing length. The 500
  /// then declared the length of the body that never went out — 5, 4096, whatever the handler
  /// chose — over its own 41-byte JSON body, and on a keep-alive connection the client read
  /// the tail of the error as the head of the next response. Oracle: RFC 9112 s6.3 — a
  /// Content-Length that does not match the content sent is a framing error. Read over a
  /// persistent socket so the desync would be observed, then prove the connection is intact
  /// by getting a correct answer to the next request on it. Reproduced against the unfixed
  /// adapter on the wire 2026-09-12 (`Content-Length: 5` over the 41-byte body, next
  /// response glued to it).
  @Test
  void handlerResponseThatCannotBeWrittenStillFramesTheAnswer() throws Exception {
    final int port = serve(builder -> {
      builder.blockingQueryHandler("/bad-type", request ->
          HttpResponse.response("text/plain\r\nX-Evil: 1", "SHORT"));
      builder.blockingQueryHandler("/bad-header", request ->
          HttpResponse.response("text/plain", "x").withHeader("X-Bad", "a\r\nX-Injected: 1"));
      builder.blockingQueryHandler("/ok", request -> HttpResponse.response("text/plain", "fine"));
    });
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      final var out = socket.getOutputStream();
      final var in = socket.getInputStream();
      for (final var target : new String[]{"/bad-type", "/bad-header"}) {
        out.write(("GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        final var failed = readResponse(in);
        assertEquals(500, rawStatus(failed), target + " -> " + failed);
        assertEquals("application/json", rawHeader(failed, "Content-Type"),
            "the 500 is the controller's JSON answer, not Helidon's zero-byte one: " + failed);
        final var body = rawBody(failed);
        assertTrue(body.contains("msg"), failed);
        assertEquals(String.valueOf(body.getBytes(StandardCharsets.ISO_8859_1).length),
            rawHeader(failed, "Content-Length"),
            "the 500 must declare the length of the body it sends, not of the one that failed: " + failed);
        org.junit.jupiter.api.Assertions.assertNull(rawHeader(failed, "X-Bad"),
            "the refused header must not leak into the error answer: " + failed);

        out.write("GET /ok HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        final var next = readResponse(in);
        assertEquals(200, rawStatus(next), "the connection must still be in sync after the 500: " + next);
        assertEquals("fine", rawBody(next), next);
      }
    }
  }

  /// The property that makes the HEAD and bodyless divergences safe rather than merely
  /// different: every single-shot case in this suite sends `Connection: close`, so only this
  /// one observes that the *next* response on a persistent connection is intact. Oracle: RFC
  /// 9110 s9.3.2 and RFC 9112 s6.3 — a response whose content does not match its framing
  /// makes every later response on the connection unreadable. Sleep-free: each read completes
  /// when the server writes.
  @Test
  void keepAliveConnectionIsNotDesynced() throws Exception {
    final int port = serve(builder -> {
      builder.blockingQueryHandler("/echo", request -> HttpResponse.response("text/plain", "QH"));
      builder.blockingQueryHandler("/gone", request ->
          HttpResponse.response(204, "text/plain", "ignored".getBytes(StandardCharsets.UTF_8)));
      builder.blockingQueryPost("/pay", request -> HttpResponse.response("text/plain", "paid"));
    });
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      final var out = socket.getOutputStream();
      final var in = socket.getInputStream();

      out.write("HEAD /echo HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      final var head = readResponse(in);
      assertEquals(405, rawStatus(head), head);
      assertEquals("GET", rawHeader(head, "Allow"), head);
      assertEquals("", rawBody(head), head);

      out.write("GET /echo HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      final var afterHead = readResponse(in);
      assertEquals(200, rawStatus(afterHead), "the response after a bodyless HEAD answer must parse cleanly: " + afterHead);
      assertEquals("QH", rawBody(afterHead), afterHead);

      out.write("GET /gone HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      final var noContent = readResponse(in);
      assertEquals(204, rawStatus(noContent), noContent);
      assertEquals("", rawBody(noContent), noContent);

      out.write(("OPTIONS /pay HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: https://app.example\r\n"
          + "Access-Control-Request-Method: POST\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      out.flush();
      final var preflight = readResponse(in);
      assertEquals(200, rawStatus(preflight), preflight);
      assertEquals("POST", rawHeader(preflight, "Access-Control-Allow-Methods"), preflight);
      assertEquals("", rawBody(preflight), preflight);

      out.write("GET /echo HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      final var last = readResponse(in);
      assertEquals(200, rawStatus(last), "the response after a dropped 204 body and a pre-flight must parse cleanly: " + last);
      assertEquals("QH", rawBody(last), last);
      assertEquals(-1, in.read(), "Connection: close must end the connection");
    }
  }

  /// The mirror image of Jetty's `blockingHandlersRunOnTheProvidedExecutor`, and the guard on
  /// the README's backend-selection advice: the executor handed to `createServer` is accepted
  /// and never given work, because Helidon's `HttpRoutingImpl` refuses a response completed
  /// off the request thread ("A route MUST call either send, reroute, or next on
  /// ServerResponse on the request thread") and offers no executor injection. Both halves are
  /// pinned — zero dispatches, and the handler observed on a Helidon-owned virtual thread —
  /// for blocking and non-blocking routes alike.
  @Test
  void theSuppliedExecutorReceivesNoWork() throws Exception {
    final var dispatches = new java.util.concurrent.atomic.AtomicInteger();
    try (final var delegate = Executors.newVirtualThreadPerTaskExecutor()) {
      final java.util.concurrent.Executor recording = command -> {
        dispatches.incrementAndGet();
        delegate.execute(command);
      };
      final var builder = new HelidonBuilderFactory().createBuilder();
      builder.blockingQueryHandler("/b", request ->
          HttpResponse.response("text/plain", Thread.currentThread().getName() + "|" + Thread.currentThread().isVirtual()));
      builder.nonBlockingQueryHandler("/nb", request ->
          HttpResponse.response("text/plain", Thread.currentThread().getName() + "|" + Thread.currentThread().isVirtual()));
      final var owned = startOwned(builder, recording);
      try (final var server = owned.server();
           final var client = HttpClient.newHttpClient()) {
        for (final var target : new String[]{"/b", "/nb"}) {
          final var body = get(client, owned.port(), target).body();
          assertTrue(body.endsWith("|true"), target + " must run on a virtual thread: " + body);
          assertTrue(body.contains("WebServer"), target + " must run on a Helidon-owned thread: " + body);
        }
        assertEquals(0, dispatches.get(), "Helidon ignores the executor it is given");
      }
    }
  }

  /// Helidon logs through `System.Logger`, whose default finder is JUL, so the framework's
  /// own start-up logging reaches JUL with no shim installed — the property FusionAuth's
  /// `frameworkLoggingFlowsThroughTheJulShim` pins through its adapter-installed logger
  /// factory. Nothing in this module installs anything; the pin is that nothing needs to.
  @Test
  void frameworkLoggingFlowsThroughJul() throws Throwable {
    final var records = recordLogs("io.helidon", () ->
        serve(builder ->
            builder.blockingQueryHandler("/ping", request -> HttpResponse.response("text/plain", "pong"))));
    assertTrue(
        records.stream().anyMatch(record -> String.valueOf(record.getLoggerName()).startsWith("io.helidon")),
        "Helidon's start-up logging must surface through JUL; captured records: " + records.size());
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

  /// Helidon reports a bind conflict only through its own SEVERE log and `isRunning()`, so
  /// the adapter's `IOException` carries no `BindException` cause; the lost-race check
  /// reads the adapter's message instead.
  private static boolean lostThePortRace(final Throwable thrown) {
    for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.net.BindException || cause instanceof java.net.ConnectException) {
        return true;
      }
      if (cause instanceof java.io.IOException && String.valueOf(cause.getMessage()).contains("failed to start")) {
        return true;
      }
    }
    return false;
  }

  /// A server that cannot bind must throw — never report success and hold a dead server.
  /// Helidon's `WebServer.start()` logs a bind failure and returns not-running; the adapter
  /// reads `isRunning()` and converts that into the `IOException` this case pins, naming the
  /// address since no cause is available.
  @Test
  void startOnAnOccupiedPortThrows() throws Exception {
    try (final var occupant = new ServerSocket(0, 50, java.net.InetAddress.getByName("localhost"));
         final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var builder = new HelidonBuilderFactory().createBuilder();
      builder.blockingQueryHandler("/x", request -> HttpResponse.response("text/plain", "x"));
      final var server = builder.createServer(executor, "localhost", occupant.getLocalPort());
      try {
        final var thrown = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
            server::start,
            "a server that cannot bind must throw, never report success silently");
        assertTrue(thrown.getMessage().contains("localhost:" + occupant.getLocalPort()),
            "no cause is available, so the message must name the address: " + thrown.getMessage());
      } finally {
        // A mutant that drops the host guard binds the wildcard, dodges the conflict and
        // leaves a live listener this test never expected to exist; reclaim it.
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
      final var builder = new HelidonBuilderFactory().createBuilder();
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

  /// `HttpServer` promises that `stop()` on a server that never started is a no-op; the
  /// adapter builds its `WebServer` lazily, so there is nothing to stop yet.
  @Test
  void stopOnANeverStartedServerIsANoOp() throws Exception {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var builder = new HelidonBuilderFactory().createBuilder();
      builder.blockingQueryHandler("/x", request -> HttpResponse.response("text/plain", "x"));
      final var server = builder.createServer(executor, "localhost", freePort());
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(server::stop);
    }
  }

  /// Helidon's own second `start()` on a running server is a silent no-op and a stopped
  /// `WebServer` cannot be restarted; the adapter refuses a second start loudly instead, and
  /// the first server keeps answering.
  @Test
  void secondStartThrowsAndTheRunningServerIsUndisturbed() throws Exception {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
         final var client = HttpClient.newHttpClient()) {
      final var builder = new HelidonBuilderFactory().createBuilder();
      builder.blockingQueryHandler("/x", request -> HttpResponse.response("text/plain", "x"));
      final var owned = startOwned(builder, executor);
      try (final var server = owned.server()) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, server::start,
            "a second start must be refused, not silently ignored");
        assertEquals("x", get(client, owned.port(), "/x").body(),
            "the refused second start must leave the running server alone");
      }
    }
  }

  /// The listener must bind the host it was given, never the wildcard — read synchronously
  /// off the unstarted configuration, with no socket, no clock and nothing to clean up.
  /// `startOnAnOccupiedPortThrows` reaches the same property through a bind conflict; this
  /// one pins it against an oracle that cannot time out.
  @Test
  void theListenerBindsTheRequestedHost() {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var config = new HelidonServerBuilder().initRestServer(executor, "localhost", 0);
      assertEquals("localhost", config.host(),
          "initRestServer must apply the requested host to the listener");
      final var blank = new HelidonServerBuilder().initRestServer(executor, "  ", 0);
      assertEquals(WebServer.builder().host(), blank.host(),
          "a blank host must leave Helidon's bind-all default in place");
    }
  }

  /// `HttpServer` documents `stop()` as immediate, not graceful: the listener must carry a
  /// zero grace period, or a stop with a request in flight would wait for Helidon's default.
  @Test
  void theListenerStopsWithoutAGracePeriod() {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var config = new HelidonServerBuilder().initRestServer(executor, "localhost", 0);
      assertEquals(Duration.ZERO, config.shutdownGracePeriod());
    }
  }

  /// The adapter owns the lifecycle through `start()`/`stop()`; no other backend registers a
  /// JVM shutdown hook, and Helidon's default would add one per server built.
  @Test
  void theListenerRegistersNoShutdownHook() {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var config = new HelidonServerBuilder().initRestServer(executor, "localhost", 0);
      org.junit.jupiter.api.Assertions.assertFalse(config.shutdownHook());
    }
  }
}
