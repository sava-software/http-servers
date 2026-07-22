package software.sava.http_servers.jetty;

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

/// Pins the parts of the core Request/HttpResponse contract that every backend must agree
/// on: the raw query string, 500 on a throwing handler (never a hang or connection abort),
/// custom status/header propagation (the x402 payment-gate shape), cached JSON responses,
/// and case-insensitive request-header lookup.
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
    final int port = freePort();
    builder.createServer(Executors.newVirtualThreadPerTaskExecutor(), "localhost", port).start();
    return port;
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
              .timeout(Duration.ofSeconds(10))
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
              .timeout(Duration.ofSeconds(10))
              .method("DELETE", HttpRequest.BodyPublishers.noBody())
              .build(),
          BodyHandlers.ofString());
      assertEquals(405, wrongMethod.statusCode());
      assertEquals("application/json", wrongMethod.headers().firstValue("Content-Type").orElse(null));
      org.junit.jupiter.api.Assertions.assertTrue(wrongMethod.body().contains("msg"), wrongMethod.body());
    }
  }
}
