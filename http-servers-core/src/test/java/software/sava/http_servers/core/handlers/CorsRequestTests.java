package software.sava.http_servers.core.handlers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the CORS rule the Jetty, Helidon and Netty adapters share: a blank header names
/// nothing, only an `OPTIONS` request that names an origin and a non-blank requested method is
/// a pre-flight, a pre-flight routes to the method it asks for and carries the headers it asks
/// for, anything else routes as itself, and a named origin comes back exactly as named. The
/// adapters' socket suites pin that each of them asks here; this is the rule itself (owner
/// decision, 2026-10-14: an `OPTIONS` without an `Origin` is not a pre-flight and routes as
/// the request it is).
final class CorsRequestTests {

  private static final String ORIGIN = "https://app.example";

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "\t"})
  void anAbsentOrBlankOriginHeaderNamesNoOrigin(final String header) {
    final var cors = CorsRequest.options(header, "GET", "X-Payment");
    assertNull(cors.origin());
    assertFalse(cors.preflight(), "without an origin an OPTIONS naming a method is not a pre-flight");
    assertEquals("OPTIONS", cors.method("OPTIONS"), "it routes as the OPTIONS it is");
    assertNull(cors.requestedHeaders(), "nothing is echoed to a request that is no pre-flight");
  }

  @Test
  void aNamedOriginIsReturnedAsNamed() {
    assertEquals(ORIGIN, CorsRequest.of(ORIGIN).origin());
    assertEquals(ORIGIN, CorsRequest.options(ORIGIN, null, null).origin());
  }

  @Test
  void aPreflightNamesAnOriginAndAMethod() {
    final var cors = CorsRequest.options(ORIGIN, "POST", "X-Payment");
    assertTrue(cors.preflight());
    assertEquals("POST", cors.method("OPTIONS"), "a pre-flight routes to the method it asks for");
    assertEquals(ORIGIN, cors.origin());
    assertEquals("X-Payment", cors.requestedHeaders());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " "})
  void withoutAMethodItIsNotAPreflight(final String requestedMethod) {
    final var cors = CorsRequest.options(ORIGIN, requestedMethod, "X-Payment");
    assertFalse(cors.preflight());
    assertEquals("OPTIONS", cors.method("OPTIONS"));
    assertNull(cors.requestedHeaders());
    assertEquals(ORIGIN, cors.origin(),
        "the origin is still named; whether the answer reflects it is the adapter's business");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "https://app.example"})
  void aRequestThatIsNotOptionsIsNeverAPreflight(final String originHeader) {
    final var cors = CorsRequest.of(originHeader);
    assertFalse(cors.preflight());
    assertEquals("GET", cors.method("GET"), "it routes as itself");
    assertNull(cors.requestedHeaders());
    assertEquals(originHeader == null || originHeader.isBlank() ? null : originHeader, cors.origin(),
        "a named origin is still reflected on a plain request");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " "})
  void aPreflightAskingForNoHeadersHasNoneToEcho(final String requestedHeaders) {
    final var cors = CorsRequest.options(ORIGIN, "GET", requestedHeaders);
    assertTrue(cors.preflight());
    assertNull(cors.requestedHeaders());
  }
}
