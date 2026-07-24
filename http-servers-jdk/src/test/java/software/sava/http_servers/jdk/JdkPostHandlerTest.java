package software.sava.http_servers.jdk;

import org.junit.jupiter.api.Test;
import software.sava.http_servers.core.response.HttpResponse;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdkPostHandlerTest {

  private static int freePort() throws Exception {
    try (final var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @Test
  void postHandlerReceivesBodyAndMethod() throws Exception {
    final var builder = new JDKHttpServerBuilderFactory().createBuilder();

    builder.blockingQueryPost("/echo", request -> {
          final var body = new String(request.body(), StandardCharsets.UTF_8);
          return HttpResponse.response("text/plain", request.method() + ':' + body);
        }
    );
    builder.nonBlockingQueryHandler("/ping", request -> HttpResponse.response("text/plain", request.method())
    );

    final int port = start(builder);

    try (final var client = HttpClient.newHttpClient()) {
      final var postResponse = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/echo"))
              .POST(HttpRequest.BodyPublishers.ofString("hello-post"))
              .build(),
          BodyHandlers.ofString()
      );
      assertEquals(200, postResponse.statusCode());
      assertEquals("POST:hello-post", postResponse.body());

      final var getResponse = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ping"))
              .GET()
              .build(),
          BodyHandlers.ofString()
      );
      assertEquals(200, getResponse.statusCode());
      assertEquals("GET", getResponse.body());
    }
  }

  @Test
  void getAndPostCoexistOnSamePathAnd405() throws Exception {
    final var builder = new JDKHttpServerBuilderFactory().createBuilder();

    builder.blockingQueryHandler("/resource", request -> HttpResponse.response("text/plain", "got:" + request.method()));
    builder.blockingQueryPost("/resource", request -> {
      final var body = new String(request.body(), StandardCharsets.UTF_8);
      return HttpResponse.response("text/plain", "posted:" + body);
    });

    final int port = start(builder);

    try (final var client = HttpClient.newHttpClient()) {
      final var getResponse = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/resource"))
              .GET()
              .build(),
          BodyHandlers.ofString()
      );
      assertEquals(200, getResponse.statusCode());
      assertEquals("got:GET", getResponse.body());

      final var postResponse = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/resource"))
              .POST(HttpRequest.BodyPublishers.ofString("payload"))
              .build(),
          BodyHandlers.ofString()
      );
      assertEquals(200, postResponse.statusCode());
      assertEquals("posted:payload", postResponse.body());

      final var deleteResponse = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/resource"))
              .DELETE()
              .build(),
          BodyHandlers.ofString()
      );
      assertEquals(405, deleteResponse.statusCode());
      final var allow = deleteResponse.headers().firstValue("Allow").orElse("");
      assertTrue(allow.contains("GET"), allow);
      assertTrue(allow.contains("POST"), allow);
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
}
