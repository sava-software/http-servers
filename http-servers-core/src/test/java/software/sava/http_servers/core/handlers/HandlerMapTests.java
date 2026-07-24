package software.sava.http_servers.core.handlers;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class HandlerMapTests {

  private static final String USERS_GET = "users:GET";
  private static final String USERS_POST = "users:POST";
  private static final String HEALTH_GET = "health:GET";
  private static final String API_PREFIX = "api-prefix";
  private static final String V2_PREFIX = "v2-prefix";

  private static HandlerMap<String> map() {
    final var users = new LinkedHashMap<String, String>();
    users.put("GET", USERS_GET);
    users.put("POST", USERS_POST);
    // keep insertion order so the allowedMethods join is deterministic
    final var queryHandlers = Map.<String, Map<String, String>>of(
        "/users", users,
        "/health", Map.of("GET", HEALTH_GET)
    );
    // longer prefix first: lookup scans in iteration order
    final var pathHandlers = List.of(
        Map.entry("/api/v2/", Map.of("GET", V2_PREFIX)),
        Map.entry("/api/", Map.of("GET", API_PREFIX))
    );
    return HandlerMap.createController(queryHandlers, pathHandlers);
  }

  @Test
  void exactPathAndMethodMatch() {
    final var lookup = map().lookupHandler("GET", "/users");
    assertSame(USERS_GET, lookup.handler());
    assertNull(lookup.allowedMethods());
    assertSame(USERS_POST, map().lookupHandler("POST", "/users").handler());
  }

  @Test
  void methodNotAllowedReportsAllowedMethods() {
    final var lookup = map().lookupHandler("DELETE", "/users");
    assertNull(lookup.handler());
    assertEquals("GET, POST", lookup.allowedMethods());
  }

  @Test
  void ambiguousPathIsBadRequestNotRouted() {
    final var handlerMap = map();
    for (final var raw : new String[]{"/users%2F..%2Fhealth", "/users/%2e%2e/health", "//users", "/users/../../users"}) {
      final var lookup = handlerMap.lookupHandler("GET", raw);
      assertTrue(lookup.badRequest(), raw);
      assertNull(lookup.handler(), raw);
      assertNull(lookup.allowedMethods(), raw);
    }
  }

  @Test
  void routingIsCanonical() {
    final var handlerMap = map();
    assertSame(USERS_GET, handlerMap.lookupHandler("GET", "/api/../users").handler(),
        "dot segments resolve before the exact match");
    assertSame(USERS_GET, handlerMap.lookupHandler("GET", "/%75sers").handler(),
        "benign escapes decode before the exact match");
    assertSame(API_PREFIX, handlerMap.lookupHandler("GET", "/api/x/../y").handler(),
        "dot segments resolve before the prefix match");
    assertFalse(handlerMap.lookupHandler("GET", "/users").badRequest(),
        "a clean lookup never reads as bad request");
  }

  @Test
  void unknownPathIsNotFound() {
    final var lookup = map().lookupHandler("GET", "/missing");
    assertNull(lookup.handler());
    assertNull(lookup.allowedMethods());
    assertSame(HandlerLookup.notFound(), lookup);
  }

  @Test
  void prefixMatchScansInOrder() {
    assertSame(V2_PREFIX, map().lookupHandler("GET", "/api/v2/users").handler());
    assertSame(API_PREFIX, map().lookupHandler("GET", "/api/v1/users").handler());
  }

  @Test
  void prefixMatchWithWrongMethodIsMethodNotAllowed() {
    final var lookup = map().lookupHandler("POST", "/api/v2/users");
    assertNull(lookup.handler());
    assertEquals("GET", lookup.allowedMethods());
  }

  @Test
  void prefixDoesNotMatchShorterPath() {
    assertSame(HandlerLookup.notFound(), map().lookupHandler("GET", "/api"));
  }

  @Test
  void exactMatchTakesPrecedenceOverPrefix() {
    final var queryHandlers = Map.of("/api/v2/users", Map.of("GET", USERS_GET));
    final var pathHandlers = List.of(Map.entry("/api/", Map.of("GET", API_PREFIX)));
    final var map = HandlerMap.createController(queryHandlers, pathHandlers);
    assertSame(USERS_GET, map.lookupHandler("GET", "/api/v2/users").handler());
    assertSame(API_PREFIX, map.lookupHandler("GET", "/api/v2/other").handler());
  }

  @Test
  void lookupFactories() {
    final var matched = HandlerLookup.matched(USERS_GET);
    assertSame(USERS_GET, matched.handler());
    assertNull(matched.allowedMethods());

    final var methodNotAllowed = HandlerLookup.<String>methodNotAllowed("GET");
    assertNull(methodNotAllowed.handler());
    assertEquals("GET", methodNotAllowed.allowedMethods());
  }

  @Test
  void handlerStreams() {
    final var map = map();
    assertEquals(2, map.queryHandlerStream().count());
    assertTrue(map.queryHandlerStream().anyMatch(e -> e.getKey().equals("/users")));
    assertEquals(
        List.of("/api/v2/", "/api/"),
        map.pathHandlerStream().map(Map.Entry::getKey).toList()
    );
  }
}
