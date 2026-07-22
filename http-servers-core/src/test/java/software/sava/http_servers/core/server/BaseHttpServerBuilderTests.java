package software.sava.http_servers.core.server;

import org.junit.jupiter.api.Test;
import software.sava.http_servers.core.handlers.HandlerMap;
import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.HttpResponse;
import software.sava.http_servers.core.response.QueryHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

final class BaseHttpServerBuilderTests {

  private static final CachedResponse CACHED = () -> new byte[]{1};
  private static final QueryHandler QUERY = request -> HttpResponse.EMPTY;

  /// Handlers are wrapped into tagged strings so tests can assert both the wrapper
  /// chosen and the map slot it landed in.
  private static final class RecordingBuilder extends BaseHttpServerBuilder<String, Object> {

    private Object initializedServer;
    private HandlerMap<String> controller;
    private String initArgs;

    @Override
    protected Object initRestServer(final Executor executor, final String host, final int port) {
      this.initArgs = host + ':' + port;
      this.initializedServer = new Object();
      return initializedServer;
    }

    @Override
    protected HttpServer createServer(final Object server) {
      assertSame(initializedServer, server, "createServer must receive the initialized server");
      return () -> {
      };
    }

    @Override
    protected String cachedResponse(final CachedResponse cachedResponse) {
      return cachedResponse == null ? null : "cached";
    }

    @Override
    protected String nonBlockingGet(final QueryHandler handler) {
      return handler == null ? null : "nonBlockingGet";
    }

    @Override
    protected String blockingGet(final QueryHandler handler) {
      return handler == null ? null : "blockingGet";
    }

    @Override
    protected String nonBlockingPost(final QueryHandler handler) {
      return handler == null ? null : "nonBlockingPost";
    }

    @Override
    protected String blockingPost(final QueryHandler handler) {
      return handler == null ? null : "blockingPost";
    }

    @Override
    protected void setController(final Object server, final HandlerMap<String> handlerMap) {
      assertSame(initializedServer, server, "controller must be set on the initialized server");
      this.controller = handlerMap;
    }
  }

  @Test
  void queryHandlerRegistersTrailingSlashAlias() {
    final var builder = new RecordingBuilder();
    builder.blockingQueryHandler("/status", QUERY);
    assertEquals(Map.of("GET", "blockingGet"), builder.queryHandlers.get("/status"));
    assertEquals(Map.of("GET", "blockingGet"), builder.queryHandlers.get("/status/"));
    assertEquals(2, builder.queryHandlers.size());
  }

  @Test
  void trailingSlashPathRegistersBareAlias() {
    final var builder = new RecordingBuilder();
    builder.blockingQueryHandler("/status/", QUERY);
    assertEquals(Map.of("GET", "blockingGet"), builder.queryHandlers.get("/status/"));
    assertEquals(Map.of("GET", "blockingGet"), builder.queryHandlers.get("/status"));
    assertEquals(2, builder.queryHandlers.size());
  }

  @Test
  void rootPathAliasesToTheEmptyPath() {
    final var builder = new RecordingBuilder();
    builder.blockingQueryHandler("/", QUERY);
    assertEquals(Map.of("GET", "blockingGet"), builder.queryHandlers.get("/"));
    assertEquals(Map.of("GET", "blockingGet"), builder.queryHandlers.get(""));
  }

  @Test
  void eachQueryRegistrationChoosesItsWrapperAndMethod() {
    final var builder = new RecordingBuilder();
    builder.cachedQueryHandler("/a", CACHED);
    builder.nonBlockingQueryHandler("/b", QUERY);
    builder.blockingQueryHandler("/c", QUERY);
    builder.nonBlockingQueryPost("/d", QUERY);
    builder.blockingQueryPost("/e", QUERY);

    assertEquals(Map.of("GET", "cached"), builder.queryHandlers.get("/a"));
    assertEquals(Map.of("GET", "nonBlockingGet"), builder.queryHandlers.get("/b"));
    assertEquals(Map.of("GET", "blockingGet"), builder.queryHandlers.get("/c"));
    assertEquals(Map.of("POST", "nonBlockingPost"), builder.queryHandlers.get("/d"));
    assertEquals(Map.of("POST", "blockingPost"), builder.queryHandlers.get("/e"));
  }

  @Test
  void getAndPostShareAPathSlot() {
    final var builder = new RecordingBuilder();
    builder.blockingQueryHandler("/x", QUERY);
    builder.blockingQueryPost("/x", QUERY);
    assertEquals(Map.of("GET", "blockingGet", "POST", "blockingPost"), builder.queryHandlers.get("/x"));
  }

  @Test
  void nullQueryHandlerRegistersNothing() {
    final var builder = new RecordingBuilder();
    builder.cachedQueryHandler("/a", null);
    builder.blockingQueryHandler("/b", null);
    assertTrue(builder.queryHandlers.isEmpty());
  }

  @Test
  void pathHandlersRegisterExactlyWithoutAliases() {
    final var builder = new RecordingBuilder();
    builder.cachedPathHandler("/files/", CACHED);
    builder.nonBlockingPathHandler("/p1", QUERY);
    builder.blockingPathHandler("/p2", QUERY);
    builder.nonBlockingPathPost("/p3", QUERY);
    builder.blockingPathPost("/p4", QUERY);

    assertEquals(Map.of("GET", "cached"), builder.pathHandlers.get("/files/"));
    assertNull(builder.pathHandlers.get("/files"), "path handlers must not be slash-aliased");
    assertEquals(Map.of("GET", "nonBlockingGet"), builder.pathHandlers.get("/p1"));
    assertEquals(Map.of("GET", "blockingGet"), builder.pathHandlers.get("/p2"));
    assertEquals(Map.of("POST", "nonBlockingPost"), builder.pathHandlers.get("/p3"));
    assertEquals(Map.of("POST", "blockingPost"), builder.pathHandlers.get("/p4"));
    assertTrue(builder.queryHandlers.isEmpty());
  }

  @Test
  void nullPathHandlerRegistersNothing() {
    final var builder = new RecordingBuilder();
    builder.cachedPathHandler("/a", null);
    assertTrue(builder.pathHandlers.isEmpty());
  }

  @Test
  void createServerInitializesThenWiresTheController() {
    final var builder = new RecordingBuilder();
    builder.blockingQueryHandler("/status", QUERY);
    builder.blockingPathPost("/upload", QUERY);

    final var server = builder.createServer(Runnable::run, "localhost", 8080);
    assertNotNull(server);
    assertEquals("localhost:8080", builder.initArgs);
    assertNotNull(builder.controller, "setController must run before createServer returns");

    assertEquals("blockingGet", builder.controller.lookupHandler("GET", "/status").handler());
    assertEquals("blockingGet", builder.controller.lookupHandler("GET", "/status/").handler());
    assertEquals("blockingPost", builder.controller.lookupHandler("POST", "/upload").handler());
  }

  @Test
  void controllerSnapshotIgnoresLaterRegistrations() {
    final var builder = new RecordingBuilder();
    builder.blockingQueryHandler("/early", QUERY);
    builder.createServer(Runnable::run, "localhost", 1);
    final var snapshot = builder.controller;

    builder.blockingQueryHandler("/late", QUERY);
    assertNull(snapshot.lookupHandler("GET", "/late").handler(), "the wired controller must be a snapshot");
    assertNotNull(snapshot.lookupHandler("GET", "/early").handler());
  }

  @Test
  void everyRegistrationLogsItsPath() {
    final var records = java.util.Collections.synchronizedList(new java.util.ArrayList<java.util.logging.LogRecord>());
    final var jul = java.util.logging.Logger.getLogger(HttpServerBuilder.class.getName());
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
      final var builder = new RecordingBuilder();
      builder.blockingQueryHandler("/logged-query", QUERY);
      builder.blockingPathHandler("/logged-path", QUERY);
      final var messages = records.stream().map(java.util.logging.LogRecord::getMessage).toList();
      assertTrue(messages.contains("/logged-query"), "query registration must log its path: " + messages);
      assertTrue(messages.contains("/logged-path"), "path registration must log its path: " + messages);
    } finally {
      jul.removeHandler(handler);
    }
  }

  @Test
  void excludeGroupRequiresAnEmptySet() {
    final var builder = new RecordingBuilder();
    assertFalse(builder.excludeGroup(null));
    assertTrue(builder.excludeGroup(Set.of()));
    assertFalse(builder.excludeGroup(Set.of("/path")));
  }

  @Test
  void findFirstThrowsWhenNoBackendIsOnThePath() {
    // core itself ships no HttpServerBuilderFactory provider; the adapter modules do
    assertThrows(java.util.NoSuchElementException.class, HttpServerBuilderFactory::findFirst);
  }

  @Test
  void wireHandlersBindsTheWiringToThisBuilder() {
    final var builder = new RecordingBuilder();
    final var wiring = builder.wireHandlers(Map.of(), Map.of());
    assertSame(builder, wiring.serverBuilder());
  }

  @Test
  void defaultWireOverloadsDelegateWithOpenFilters() {
    final var builder = new RecordingBuilder();
    final var group = "group";

    final var open = builder.wireHandlers();
    assertSame(builder, open.serverBuilder());
    assertTrue(open.includeGroup(group));
    assertFalse(open.excludeGroup(group));

    final var included = builder.wireIncludedHandlers(Map.of(group, Set.of("/a")));
    assertTrue(included.includeGroup(group));
    assertTrue(included.includePath(group, "/a"));
    assertFalse(included.includePath(group, "/b"), "an include-set is a per-group whitelist");
    assertTrue(included.includePath("other", "/b"), "unnamed groups are included by default");

    final var excluded = builder.wireNonExcludedHandlers(Map.of(group, Set.of()));
    assertTrue(excluded.excludeGroup(group), "an empty exclude-set blacklists the whole group");
    assertFalse(excluded.excludeGroup("other"));
    assertFalse(excluded.includeGroup(group));
  }
}
