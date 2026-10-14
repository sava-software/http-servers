package software.sava.http_servers.jetty;

import org.eclipse.jetty.server.ServerConnector;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the parts of the core Request/HttpResponse contract that every backend must agree
/// on: the raw query string, 500 on a throwing handler or a non-final status (never a hang or
/// connection abort), custom status/header propagation (the x402 payment-gate shape), cached
/// JSON responses, and case-insensitive request-header lookup.
///
/// Every client request and raw socket read carries a 2 s bound, as in the Netty suite (the
/// JDK and Helidon suites use 10 s). A removed write leaves Jetty's `Callback` uncompleted and
/// the client blocked on a read, and PIT's per-mutant watchdog fires at the recorded duration
/// × 1.25 + 4000 ms, so a 10 s bound never fired first: those mutants read `TIMED_OUT`,
/// detected by the clock rather than by this suite. At 2 s the bound beats the watchdog, and a
/// hang fails its test as a timeout exception, an assertion-level kill.
final class JettyConformanceTest {

  private static final software.sava.http_servers.core.server.HttpServerBuilderFactory FACTORY =
      new JettyServerBuilderFactory();

  private static int freePort() throws Exception {
    try (final var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static int serve(final java.util.function.Consumer<software.sava.http_servers.core.server.HttpServerBuilder> register)
      throws Exception {
    final var builder = new JettyServerBuilderFactory().createBuilder();
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

  /// A throwing handler's 500 is the controller's own answer: the JSON error shape the Helidon
  /// and Netty controllers send, with nothing of the throwable in it. Jetty's default error page
  /// would print the throwable's class and message to the caller. The CORS echo set before the
  /// handler ran is dropped, as Jetty's own error response drops it.
  @Test
  void handlerFailureAnswersJsonWithoutTheThrowable() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/boom", request -> {
          throw new IllegalStateException("secret-detail");
        }));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/boom"))
              .timeout(Duration.ofSeconds(2))
              .header("Origin", "https://example.test")
              .GET()
              .build(),
          BodyHandlers.ofString()
      );
      assertEquals(500, response.statusCode());
      assertEquals("""
          {
            "msg": "Failed to process request."
          }""", response.body());
      assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null));
      org.junit.jupiter.api.Assertions.assertTrue(
          response.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
          "the 500 must not carry the CORS echo set before the handler threw");
    }
  }

  /// A handler that returns an interim status has failed like one that throws, and gets the same
  /// JSON 500. Written as given, a `103` goes out as an interim head without its body, and the
  /// final response the client then waits for is never written.
  @Test
  void interimStatusFromAHandlerAnswers500() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/early", request ->
            HttpResponse.response(103, "text/plain", "early".getBytes(StandardCharsets.UTF_8))));

    final var response = rawGet(port, "/early");
    assertEquals(500, rawStatus(response), response);
    assertEquals("application/json", rawHeader(response, "Content-Type"), response);
    assertEquals("""
        {
          "msg": "Failed to process request."
        }""", rawBody(response), response);
  }

  /// The page Jetty writes for an error it answers itself names the status reason, not the
  /// failure. A response declaring a shorter `Content-Length` than its body is refused by
  /// Jetty's write before the head is committed, and the default page would print the
  /// refusal's `IOException` — class and message — to the caller.
  @Test
  void jettyErrorPageCarriesNoFailureDetail() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/short", request ->
            HttpResponse.json("{}").withHeader("Content-Length", "1")));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = get(client, port, "/short");
      assertEquals(500, response.statusCode());
      final var body = response.body();
      org.junit.jupiter.api.Assertions.assertFalse(body.contains("IOException") || body.contains("content-length"), body);
      org.junit.jupiter.api.Assertions.assertTrue(body.contains("Server Error"), body);
    }
  }

  /// A 500 answered while the request body is still arriving says `Connection: close`, as
  /// Jetty's own error response did: consuming what has arrived before the head is written
  /// marks a connection with body still to come as not persistent. Written first, the head goes
  /// out without it and Jetty closes the connection unannounced, so a keep-alive client reuses a
  /// dead connection.
  @Test
  void handlerFailureMidUploadAnnouncesTheClose() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryPost("/boom", request -> {
          throw new IllegalStateException("handler bug");
        }));

    final var exchange = rawExchange(port, "POST /boom HTTP/1.1\r\nHost: 127.0.0.1\r\n"
        + "Content-Type: application/octet-stream\r\nContent-Length: 2000000\r\n\r\n"
        + "x".repeat(1000));
    assertEquals(500, rawStatus(exchange.response()), exchange.response());
    assertEquals("close", rawHeader(exchange.response(), "Connection"), exchange.response());
  }

  /// The controller's 404, answered while an upload to an unrouted path is still arriving, says
  /// `Connection: close` as the 500 above does: Jetty keeps no connection whose body it could not
  /// consume, so the head has to announce the close that follows it.
  @Test
  void notFoundMidUploadAnnouncesTheClose() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryPost("/upload", request -> HttpResponse.response("text/plain", "ok")));

    final var exchange = rawExchange(port, "POST /nowhere HTTP/1.1\r\nHost: 127.0.0.1\r\n"
        + "Content-Type: application/octet-stream\r\nContent-Length: 2000000\r\n\r\n"
        + "x".repeat(1000));
    assertEquals(404, rawStatus(exchange.response()), exchange.response());
    assertEquals("close", rawHeader(exchange.response(), "Connection"), exchange.response());
  }

  /// The same for the controller's 405: an upload still arriving at a GET-only path.
  @Test
  void methodNotAllowedMidUploadAnnouncesTheClose() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/get-only", request -> HttpResponse.response("text/plain", "got")));

    final var exchange = rawExchange(port, "POST /get-only HTTP/1.1\r\nHost: 127.0.0.1\r\n"
        + "Content-Type: application/octet-stream\r\nContent-Length: 2000000\r\n\r\n"
        + "x".repeat(1000));
    assertEquals(405, rawStatus(exchange.response()), exchange.response());
    assertEquals("close", rawHeader(exchange.response(), "Connection"), exchange.response());
  }

  /// The same for a handler's own answer when the handler returns without reading the body.
  @Test
  void unreadBodyMidUploadAnnouncesTheClose() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryPost("/ignore", request -> HttpResponse.response("text/plain", "ignored")));

    final var exchange = rawExchange(port, "POST /ignore HTTP/1.1\r\nHost: 127.0.0.1\r\n"
        + "Content-Type: application/octet-stream\r\nContent-Length: 2000000\r\n\r\n"
        + "x".repeat(1000));
    assertEquals(200, rawStatus(exchange.response()), exchange.response());
    assertEquals("close", rawHeader(exchange.response(), "Connection"), exchange.response());
  }

  /// And for a cached response, which never reads a body: a request to a cached route that
  /// declares one and is still sending it is answered with `Connection: close` as well.
  @Test
  void cachedResponseMidUploadAnnouncesTheClose() throws Exception {
    final int port = serve(builder ->
        builder.cachedQueryHandler("/cached", () -> "{\"a\":1}".getBytes(StandardCharsets.UTF_8)));

    final var exchange = rawExchange(port, "GET /cached HTTP/1.1\r\nHost: 127.0.0.1\r\n"
        + "Content-Type: application/octet-stream\r\nContent-Length: 2000000\r\n\r\n"
        + "x".repeat(1000));
    assertEquals(200, rawStatus(exchange.response()), exchange.response());
    assertEquals("close", rawHeader(exchange.response(), "Connection"), exchange.response());
  }

  /// The other side of the cases above: a small POST whose body has fully arrived, and was read,
  /// keeps its connection, so a request pipelined behind it on the same socket is answered.
  /// Consuming before the head is written closes only a connection with body still to come.
  @Test
  void fullyReceivedPostKeepsTheConnection() throws Exception {
    final int port = serve(builder -> {
      builder.blockingQueryPost("/echo", request ->
          HttpResponse.response("text/plain", new String(request.body(), StandardCharsets.UTF_8)));
      builder.blockingQueryHandler("/ping", request -> HttpResponse.response("text/plain", "pong"));
    });

    final var exchange = rawExchange(port, "POST /echo HTTP/1.1\r\nHost: 127.0.0.1\r\n"
        + "Content-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello"
        + "GET /ping HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
    final var response = exchange.response();
    final int second = response.indexOf("HTTP/1.1 ", 1);
    org.junit.jupiter.api.Assertions.assertTrue(second > 0,
        "the request behind the POST must be answered on the same socket: " + response);
    final var first = response.substring(0, second);
    assertEquals(200, rawStatus(first), response);
    org.junit.jupiter.api.Assertions.assertNull(rawHeader(first, "Connection"), response);
    assertEquals("hello", rawBody(first), response);
    final var next = response.substring(second);
    assertEquals(200, rawStatus(next), response);
    assertEquals("pong", rawBody(next), response);
    org.junit.jupiter.api.Assertions.assertTrue(exchange.closed(),
        "the server must close after Connection: close: " + response);
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
  void preflightWithoutRequestHeadersOmitsAllowHeaders() throws Exception {
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
      assertEquals(200, preflight.statusCode(), "a pre-flight naming no request headers is still a pre-flight");
      assertEquals("https://app.example",
          preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
      assertEquals("POST",
          preflight.headers().firstValue("Access-Control-Allow-Methods").orElse(null));
      assertTrue(preflight.headers().firstValue("Access-Control-Allow-Headers").isEmpty(),
          "nothing was requested, so nothing is allowed back: the null put is a remove");
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
      final var builder = FACTORY.createBuilder();
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
  void preflightWithoutOriginIsNotAPreflight() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/simple", request -> HttpResponse.response("text/plain", "body")));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/simple"))
              .timeout(Duration.ofSeconds(2))
              .header("Access-Control-Request-Method", "GET")
              .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
              .build(),
          BodyHandlers.ofString());
      assertEquals(405, response.statusCode(),
          "an OPTIONS naming a method but carrying no Origin is not a cross-origin pre-flight: it "
              + "routes as the OPTIONS it is, which no handler answers, instead of reaching the GET handler");
      assertEquals("GET", response.headers().firstValue("Allow").orElse(null),
          "the ordinary 405 names the methods the path does serve");
      assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
          "nothing to reflect without an Origin");
      assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").isEmpty(),
          "Allow-Methods is a pre-flight answer, and this was none");
    }
  }

  @Test
  void blankOriginIsNotAPreflight() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/simple", request -> HttpResponse.response("text/plain", "body")));

    // raw, so the blank header is known to be on the wire: a client library may drop it
    final var response = rawExchange(port, "OPTIONS /simple HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: \r\n"
        + "Access-Control-Request-Method: GET\r\nConnection: close\r\n\r\n").response();
    assertEquals(405, rawStatus(response),
        "a blank Origin names no origin, so the request is no more a pre-flight than one without the header: " + response);
    assertEquals("GET", rawHeader(response, "Allow"), response);
    assertNull(rawHeader(response, "Access-Control-Allow-Origin"), response);
    assertNull(rawHeader(response, "Access-Control-Allow-Methods"), response);
  }

  @Test
  void blankOriginIsNotReflected() throws Exception {
    // the handler echoes the raw header, so the pin sees the blank arrive rather than vanish
    final int port = serve(builder ->
        builder.blockingQueryHandler("/simple", request ->
            HttpResponse.response("text/plain", String.valueOf(request.header("Origin")))));

    final var response = rawExchange(port,
        "GET /simple HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: \r\nConnection: close\r\n\r\n").response();
    assertEquals(200, rawStatus(response), response);
    assertEquals("", rawBody(response).strip(),
        "the blank header reached the handler as a blank value, not as an absent one: " + response);
    assertNull(rawHeader(response, "Access-Control-Allow-Origin"),
        "a blank Origin names no origin, so there is nothing to reflect: the same reading as the pre-flight rule: " + response);
  }

  @Test
  void blankRequestMethodHeaderIsNotAPreflight() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/simple", request -> HttpResponse.response("text/plain", "body")));

    // raw, so the blank header is known to be on the wire: a client library may drop it
    final var response = rawExchange(port, "OPTIONS /simple HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: https://app.example\r\n"
        + "Access-Control-Request-Method: \r\nConnection: close\r\n\r\n").response();
    assertEquals(405, rawStatus(response),
        "a blank Access-Control-Request-Method names no method, so the request is not a pre-flight: " + response);
    assertEquals("GET", rawHeader(response, "Allow"), response);
    assertNull(rawHeader(response, "Access-Control-Allow-Methods"), response);
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

    final var logs = recordLogs(JettyController.class.getName(), () -> {
      try (final var client = HttpClient.newHttpClient()) {
        assertEquals(500, get(client, port, "/boom-logged").statusCode());
      }
    });
    org.junit.jupiter.api.Assertions.assertTrue(
        logs.stream().anyMatch(r -> r.getThrown() instanceof IllegalStateException),
        "the handler failure must be logged, not swallowed");
  }

  @Test
  void identifyingServerHeadersAreSuppressed() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/plain", request -> HttpResponse.response("text/plain", "ok")));

    try (final var client = HttpClient.newHttpClient()) {
      final var response = get(client, port, "/plain");
      assertEquals(200, response.statusCode());
      org.junit.jupiter.api.Assertions.assertTrue(response.headers().firstValue("Server").isEmpty(),
          "the Server version header is configured off");
      org.junit.jupiter.api.Assertions.assertTrue(response.headers().firstValue("X-Powered-By").isEmpty(),
          "the X-Powered-By header is configured off");
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
      org.junit.jupiter.api.Assertions.assertTrue(
          get(client, port, "/g").headers().firstValue("Access-Control-Allow-Methods").isEmpty(),
          "a simple GET must not advertise allowed methods");

      final var post = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/p"))
              .timeout(Duration.ofSeconds(2))
              .header("Origin", "https://app.example")
              .POST(HttpRequest.BodyPublishers.ofString("x"))
              .build(),
          BodyHandlers.ofString());
      org.junit.jupiter.api.Assertions.assertTrue(
          post.headers().firstValue("Access-Control-Allow-Methods").isEmpty(),
          "a simple POST must not advertise allowed methods");
      assertEquals("https://app.example",
          post.headers().firstValue("Access-Control-Allow-Origin").orElse(null),
          "the origin is still reflected on simple requests");
    }
  }

  @Test
  void blockingHandlersRunOnTheProvidedExecutor() throws Exception {
    final var dispatches = new java.util.concurrent.atomic.AtomicInteger();
    final var delegate = Executors.newVirtualThreadPerTaskExecutor();
    final java.util.concurrent.Executor recording = command -> {
      dispatches.incrementAndGet();
      delegate.execute(command);
    };

    final var builder = new JettyServerBuilderFactory().createBuilder();
    builder.blockingQueryHandler("/b", request -> HttpResponse.response("text/plain", "b"));
    final int port = freePort();
    builder.createServer(recording, "localhost", port).start();

    try (final var client = HttpClient.newHttpClient()) {
      assertEquals("b", get(client, port, "/b").body());
    }
    org.junit.jupiter.api.Assertions.assertTrue(dispatches.get() > 0,
        "the executor handed to createServer must receive work");
  }

  @Test
  void errorResponsesAreJson() throws Exception {
    final int port = serve(builder ->
        builder.blockingQueryHandler("/simple", request -> HttpResponse.response("text/plain", "body")));

    try (final var client = HttpClient.newHttpClient()) {
      final var notFound = get(client, port, "/nowhere");
      assertEquals(404, notFound.statusCode());
      assertEquals("application/json", notFound.headers().firstValue("Content-Type").orElse(null));
      org.junit.jupiter.api.Assertions.assertTrue(notFound.body().contains("msg"), notFound.body());

      final var wrongMethod = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/simple"))
              .timeout(Duration.ofSeconds(2))
              .method("DELETE", HttpRequest.BodyPublishers.noBody())
              .build(),
          BodyHandlers.ofString());
      assertEquals(405, wrongMethod.statusCode());
      assertEquals("application/json", wrongMethod.headers().firstValue("Content-Type").orElse(null));
      org.junit.jupiter.api.Assertions.assertTrue(wrongMethod.body().contains("msg"), wrongMethod.body());
    }
  }

  /// One raw-socket exchange, written onto the wire exactly as given and read until the
  /// server closes the connection. HttpClient normalizes or refuses the ambiguous targets
  /// these cases exist to pin, always speaks HTTP/1.1, and always sends a Host header, so
  /// none of those shapes can be expressed through it. `closed` is false when the 2 s
  /// SO_TIMEOUT expired with the connection still open; for an HTTP/1.0 request that is the
  /// property under test, not a harness failure.
  private record RawExchange(String response, boolean closed) {
  }

  private static RawExchange rawExchange(final int port, final String request) throws Exception {
    try (final var socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
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
    org.junit.jupiter.api.Assertions.assertTrue(exchange.closed(),
        "the server must close after Connection: close: " + exchange.response());
    return exchange.response();
  }

  private static int rawStatus(final String response) {
    return Integer.parseInt(response.substring(9, 12));
  }

  /// First value of `name` in the raw response head, or null when the header is absent.
  /// Matching is case-insensitive because header case is each backend's own: the jdk server
  /// writes `Content-length` and jetty `Content-Length`.
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
        "/files%2F..%2Fecho", "/files/%2e%2e/echo", "/files/a\\b", "/a%2541", "/echo/../../echo", "//echo"}) {
      final var response = rawGet(port, target);
      assertEquals(400, rawStatus(response), target + " -> " + response);
    }
  }

  /// The asterisk form, `OPTIONS *` (RFC 9112 §3.2.4), is a target Jetty's `UriCompliance`
  /// passes and the shared routing refuses: Jetty exempts `OPTIONS` (and `PRI` and `CONNECT`)
  /// from its rooted-path check, `*` is not a rooted path, so `PathCanonicalizer` rejects it and
  /// the controller answers the ambiguous-path 400 with its JSON body, as the Helidon and Netty
  /// controllers do. On a default builder such targets are the way into that branch.
  @Test
  void asteriskFormIsAnsweredWithTheAmbiguousPath400() throws Exception {
    final int port = serve(builder ->
        builder.nonBlockingQueryHandler("/x", request -> HttpResponse.response("text/plain", "x")));
    final var exchange = rawExchange(port, "OPTIONS * HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
    final var response = exchange.response();
    assertEquals(400, rawStatus(response), response);
    assertEquals("application/json", rawHeader(response, "Content-Type"), response);
    org.junit.jupiter.api.Assertions.assertTrue(rawBody(response).contains("\"msg\": \"Ambiguous request path.\""), response);
  }

  /// The routed `return handler.handle(…)` passes on the routed handler's own answer to
  /// whether it took the request. The adapter's handlers always do, but `JettyServerBuilder`
  /// routes any Jetty `Handler` registered through its protected `addQueryHandler`, and one that
  /// declines must get Jetty's 404, not a request left waiting on a callback no one completes.
  @Test
  void aRoutedHandlerThatDeclinesIsAnsweredWithJettys404() throws Exception {
    final var builder = new JettyServerBuilder() {
      {
        addQueryHandler("GET", "/declines", new org.eclipse.jetty.server.Handler.Abstract.NonBlocking() {
          @Override
          public boolean handle(final org.eclipse.jetty.server.Request request,
                                final org.eclipse.jetty.server.Response response,
                                final org.eclipse.jetty.util.Callback callback) {
            return false;
          }
        });
      }
    };
    final var response = rawGet(start(builder), "/declines");
    assertEquals(404, rawStatus(response), response);
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
    org.junit.jupiter.api.Assertions.assertTrue(exchange.closed(),
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
    try (final var client = HttpClient.newHttpClient()) {
      final var noContent = get(client, port, "/gone");
      assertEquals(204, noContent.statusCode());
      assertEquals("", noContent.body());

      final var notModified = get(client, port, "/same");
      assertEquals(304, notModified.statusCode());
      assertEquals("", notModified.body());
    }

    // and on the raw wire: jetty omits Content-Length on 204 and sends an explicit
    // "Content-Length: 0" on 304, and chunks neither
    assertBodylessFraming(rawGet(port, "/gone"), null);
    assertBodylessFraming(rawGet(port, "/same"), "0");
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
  @Test
  void startOnAnOccupiedPortThrows() throws Exception {
    try (final var occupant = new ServerSocket(0, 50, java.net.InetAddress.getByName("localhost"));
         final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var builder = new JettyServerBuilderFactory().createBuilder();
      builder.blockingQueryHandler("/x", request -> HttpResponse.response("text/plain", "x"));
      final var server = builder.createServer(executor, "localhost", occupant.getLocalPort());
      try {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
            server::start,
            "a server that cannot bind must throw, never report success silently");
      } finally {
        // A mutant that drops the host guard binds the wildcard, dodges the conflict and
        // leaves a live acceptor this test never expected to exist. Before HttpServer had
        // stop() there was no way to reclaim it, and the leak raced PIT's watchdog.
        server.stop();
      }
    }
  }

  record OwnedServer(software.sava.http_servers.core.server.HttpServer server, int port) {
  }

  /// Like [#start], but hands the server back so the caller owns its lifecycle.
  static OwnedServer startOwned(final software.sava.http_servers.core.server.HttpServerBuilder builder,
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
      final var builder = new JettyServerBuilderFactory().createBuilder();
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

  /// The connector must bind the host it was given, never the wildcard — read synchronously
  /// off the unstarted connector, with no socket, no clock and nothing to clean up.
  /// `startOnAnOccupiedPortThrows` above reaches the same property through a bind conflict,
  /// and stops whatever server a mutated host guard lets it start; PIT credits the guard's
  /// kill to this case, whose oracle cannot time out.
  @Test
  void theConnectorBindsTheRequestedHost() {
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var server = new JettyServerBuilder().initRestServer(executor, "localhost", 0);
      final var connector = (ServerConnector) server.getConnectors()[0];
      assertEquals("localhost", connector.getHost(),
          "initRestServer must apply the requested host to the connector");
    }
  }
}
