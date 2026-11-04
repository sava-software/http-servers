package software.sava.http_servers.netty;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import software.sava.http_servers.core.response.HttpResponse;
import software.sava.http_servers.core.server.HttpServerBuilder;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Factory compression configuration is observed on the wire and captured when the
/// builder is created. No test reads the builder's private configuration fields.
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NettyConfigurationTest {

  private static final String BODY = "factory-configured response ".repeat(64);
  private String previousCompression;

  @BeforeEach
  void isolateProperty() {
    previousCompression = System.getProperty(NettyServerBuilder.COMPRESSION_PROPERTY);
    System.clearProperty(NettyServerBuilder.COMPRESSION_PROPERTY);
  }

  @AfterEach
  void restoreProperty() {
    if (previousCompression == null) {
      System.clearProperty(NettyServerBuilder.COMPRESSION_PROPERTY);
    } else {
      System.setProperty(NettyServerBuilder.COMPRESSION_PROPERTY, previousCompression);
    }
  }

  private static HttpServerBuilder builder() {
    final var builder = new NettyBuilderFactory().createBuilder();
    builder.nonBlockingQueryHandler("/configuration", request -> HttpResponse.response("text/plain", BODY));
    return builder;
  }

  private static void assertHttp11Compression(final HttpServerBuilder builder, final boolean compressed)
      throws Exception {
    final int port;
    try (final var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
         final var server = builder.createServer(executor, "127.0.0.1", port);
         final var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      server.start();
      final var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/configuration"))
          .timeout(Duration.ofSeconds(2)).header("Accept-Encoding", "gzip").GET().build(),
          java.net.http.HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, response.statusCode());
      assertEquals(HttpClient.Version.HTTP_1_1, response.version());
      assertEquals(compressed ? "gzip" : null, response.headers().firstValue("Content-Encoding").orElse(null));
      final byte[] actual;
      if (compressed) {
        try (final var gzip = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
          actual = gzip.readAllBytes();
        }
      } else {
        actual = response.body();
      }
      assertArrayEquals(BODY.getBytes(StandardCharsets.UTF_8), actual);
    }
  }

  @Test
  void absentCompressionPropertyEnablesGzip() throws Exception {
    final var builder = builder();
    System.setProperty(NettyServerBuilder.COMPRESSION_PROPERTY, "false");
    assertHttp11Compression(builder, true);
  }

  @Test
  void explicitCompressionValuesAreCapturedAtBuilderConstruction() throws Exception {
    for (final String value : List.of("TrUe", "FaLsE")) {
      System.setProperty(NettyServerBuilder.COMPRESSION_PROPERTY, value);
      final var builder = builder();
      System.setProperty(NettyServerBuilder.COMPRESSION_PROPERTY, "invalid-after-construction");
      assertHttp11Compression(builder, value.equalsIgnoreCase("true"));
    }
  }

  @Test
  void invalidCompressionValuesFailAtFactoryConstruction() {
    for (final String value : List.of("", "1", "yes", " true ", " false ")) {
      System.setProperty(NettyServerBuilder.COMPRESSION_PROPERTY, value);
      final var failure = assertThrows(IllegalArgumentException.class, () -> new NettyBuilderFactory().createBuilder());
      assertTrue(failure.getMessage().contains(NettyServerBuilder.COMPRESSION_PROPERTY), failure.getMessage());
    }
  }
}
