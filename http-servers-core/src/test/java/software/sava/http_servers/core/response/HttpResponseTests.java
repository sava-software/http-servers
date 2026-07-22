package software.sava.http_servers.core.response;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class HttpResponseTests {

  private static final byte[] BODY = {1, 2, 3};

  @Test
  void fullFactoryCarriesEveryField() {
    final var headers = Map.of("X-A", "1");
    final var resp = HttpResponse.response(418, "text/plain", headers, BODY);
    assertEquals(418, resp.statusCode());
    assertEquals("text/plain", resp.contentType());
    assertEquals(headers, resp.headers());
    assertSame(BODY, resp.body());
  }

  @Test
  void statusFactoryHasNoExtraHeaders() {
    final var resp = HttpResponse.response(204, "text/plain", BODY);
    assertEquals(204, resp.statusCode());
    assertEquals("text/plain", resp.contentType());
    assertTrue(resp.headers().isEmpty());
    assertSame(BODY, resp.body());
  }

  @Test
  void contentTypeFactoryDefaultsTo200() {
    final var resp = HttpResponse.response("text/html", BODY);
    assertEquals(200, resp.statusCode());
    assertEquals("text/html", resp.contentType());
    assertSame(BODY, resp.body());
  }

  @Test
  void stringBodyEncodesUtf8() {
    final var resp = HttpResponse.response("text/plain", "héllo");
    assertEquals(200, resp.statusCode());
    assertArrayEquals("héllo".getBytes(StandardCharsets.UTF_8), resp.body());
  }

  @Test
  void jsonFactoriesSetContentTypeAndStatus() {
    assertEquals("application/json", HttpResponse.json(BODY).contentType());
    assertEquals(200, HttpResponse.json(BODY).statusCode());
    assertSame(BODY, HttpResponse.json(500, BODY).body());
    assertEquals(500, HttpResponse.json(500, BODY).statusCode());

    final var stringJson = HttpResponse.json(201, "{\"a\":1}");
    assertEquals(201, stringJson.statusCode());
    assertEquals("application/json", stringJson.contentType());
    assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), stringJson.body());

    final var defaultStatus = HttpResponse.json("{}");
    assertEquals(200, defaultStatus.statusCode());
    assertEquals("application/json", defaultStatus.contentType());
  }

  @Test
  void emptyConstantIsAnEmptyJsonObject() {
    assertEquals(200, HttpResponse.EMPTY.statusCode());
    assertEquals("application/json", HttpResponse.EMPTY.contentType());
    assertArrayEquals("{}".getBytes(StandardCharsets.US_ASCII), HttpResponse.EMPTY.body());
    assertTrue(HttpResponse.EMPTY.headers().isEmpty());
  }

  @Test
  void withHeaderAddsWithoutMutatingTheOriginal() {
    final var original = HttpResponse.response(202, "text/plain", Map.of("X-A", "1"), BODY);
    final var updated = original.withHeader("X-B", "2");

    assertEquals(Map.of("X-A", "1"), original.headers(), "original must be unchanged");
    assertEquals(Map.of("X-A", "1", "X-B", "2"), updated.headers());
    // everything else carries over
    assertEquals(202, updated.statusCode());
    assertEquals("text/plain", updated.contentType());
    assertSame(BODY, updated.body());
  }

  @Test
  void withHeaderOverridesAnExistingName() {
    final var resp = HttpResponse.response(200, "text/plain", Map.of("X-A", "1"), BODY)
        .withHeader("X-A", "2");
    assertEquals(Map.of("X-A", "2"), resp.headers());
  }

  @Test
  void withHeaderPreservesInsertionOrderOfNewNames() {
    final var resp = HttpResponse.response("text/plain", BODY)
        .withHeader("X-A", "1")
        .withHeader("X-B", "2")
        .withHeader("X-C", "3");
    assertEquals(List.of("X-A", "X-B", "X-C"), List.copyOf(resp.headers().keySet()));
  }
}
