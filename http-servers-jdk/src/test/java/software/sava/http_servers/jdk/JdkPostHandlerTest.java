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

    final int port = freePort();
    final var executor = Executors.newVirtualThreadPerTaskExecutor();
    final var server = builder.createServer(executor, "localhost", port);
    server.start();

    try (final var client = HttpClient.newHttpClient()) {
      final var postResponse = client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/echo"))
              .POST(HttpRequest.BodyPublishers.ofString("hello-post"))
              .build(),
          BodyHandlers.ofString()
      );
      assertEquals(200, postResponse.statusCode());
      assertEquals("POST:hello-post", postResponse.body());

      final var getResponse = client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ping"))
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

    final int port = freePort();
    final var executor = Executors.newVirtualThreadPerTaskExecutor();
    final var server = builder.createServer(executor, "localhost", port);
    server.start();

    try (final var client = HttpClient.newHttpClient()) {
      final var getResponse = client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/resource"))
              .GET()
              .build(),
          BodyHandlers.ofString()
      );
      assertEquals(200, getResponse.statusCode());
      assertEquals("got:GET", getResponse.body());

      final var postResponse = client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/resource"))
              .POST(HttpRequest.BodyPublishers.ofString("payload"))
              .build(),
          BodyHandlers.ofString()
      );
      assertEquals(200, postResponse.statusCode());
      assertEquals("posted:payload", postResponse.body());

      final var deleteResponse = client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/resource"))
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
}
