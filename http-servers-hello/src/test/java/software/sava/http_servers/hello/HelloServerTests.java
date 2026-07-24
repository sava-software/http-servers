package software.sava.http_servers.hello;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/// Boots the demo against every backend discovered over ServiceLoader — the only place the
/// provider wiring (`module-info` `provides`/`uses`) is exercised end to end.
final class HelloServerTests {

  private static int freePort() throws Exception {
    try (final var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static java.net.http.HttpResponse<String> get(final HttpClient client, final int port, final String path)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build(),
        BodyHandlers.ofString()
    );
  }

  @ParameterizedTest
  @ValueSource(strings = {"JDKHttpServerBuilderFactory", "JettyServerBuilderFactory", "FusionAuthBuilderFactory"})
  void helloServesAndExcludedPathDoesNot(final String factoryName) throws Exception {
    final int port = freePort();
    assertNotNull(HelloServer.start(factoryName, "localhost", port));

    try (final var client = HttpClient.newHttpClient()) {
      final var hello = get(client, port, "/hello");
      assertEquals(200, hello.statusCode(), factoryName);
      assertEquals("application/json", hello.headers().firstValue("Content-Type").orElse(null), factoryName);
      assertTrue(hello.body().contains("\"message\": \"Hello\""), factoryName + " body: " + hello.body());

      final var excluded = get(client, port, "/exclude");
      assertEquals(404, excluded.statusCode(),
          factoryName + ": the wiring exclusion must keep /exclude unregistered");
    }
  }

  /// The only end-to-end exercise of `HttpServerBuilderFactory.findFirst`'s success path:
  /// this module has all three backends on the path, so discovery must yield a builder in
  /// both worlds the tools run in (module path here, class path under PIT minions). The
  /// path stays an accepted `NO_COVERAGE` in core's own suite — core ships no provider —
  /// but it is no longer unobserved.
  @Test
  void findFirstDiscoversABackend() {
    assertNotNull(software.sava.http_servers.core.server.HttpServerBuilderFactory.findFirst(),
        "three backends provide HttpServerBuilderFactory; findFirst must yield a builder");
  }

  @Test
  void unknownFactoryNameThrows() {
    final var thrown = assertThrows(IllegalStateException.class,
        () -> HelloServer.start("NoSuchFactory", "localhost", 0));
    assertTrue(thrown.getMessage().contains("NoSuchFactory"));
  }

  @Test
  void helloHandlerBuildsTheConstantResponse() {
    final var response = new HelloHandler().httpResponse(null);
    assertEquals(200, response.statusCode());
    assertEquals("application/json", response.contentType());
    assertTrue(new String(response.body()).contains("\"message\": \"Hello\""));
  }
}
