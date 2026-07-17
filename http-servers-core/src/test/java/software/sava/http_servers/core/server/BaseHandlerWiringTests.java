package software.sava.http_servers.core.server;

import org.junit.jupiter.api.Test;
import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.HttpResponse;
import software.sava.http_servers.core.response.QueryHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

final class BaseHandlerWiringTests {

  private enum Group {A}

  private static final QueryHandler HANDLER = _ -> HttpResponse.EMPTY;
  private static final CachedResponse CACHED = () -> new byte[0];

  private static HandlerWiring<Group> wiring(final Map<Group, Set<String>> include,
                                             final Map<Group, Set<String>> exclude) {
    return new BaseHandlerWiring<>(new RecordingBuilder(), include, exclude);
  }

  // --- group-level predicates over the full include/exclude truth table ---

  @Test
  void noMapsIncludesEverything() {
    final var w = wiring(Map.of(), Map.of());
    assertTrue(w.includeGroup(Group.A));
    assertFalse(w.excludeGroup(Group.A));
    assertTrue(w.includePath(Group.A, "/anything"));
  }

  @Test
  void emptyExcludeSetBlacklistsWholeGroup() {
    final var w = wiring(Map.of(), Map.of(Group.A, Set.of()));
    assertFalse(w.includeGroup(Group.A));
    assertTrue(w.excludeGroup(Group.A));
    assertFalse(w.includePath(Group.A, "/x"));
  }

  @Test
  void nonEmptyExcludeSetIncludesGroupButNotListedPaths() {
    final var w = wiring(Map.of(), Map.of(Group.A, Set.of("/x")));
    assertTrue(w.includeGroup(Group.A));
    assertFalse(w.excludeGroup(Group.A));
    assertFalse(w.includePath(Group.A, "/x"));
    assertTrue(w.includePath(Group.A, "/y"));
  }

  @Test
  void emptyIncludeSetWhitelistsNothing() {
    final var w = wiring(Map.of(Group.A, Set.of()), Map.of());
    // regression: previously includeGroup returned true here, contradicting excludeGroup
    assertFalse(w.includeGroup(Group.A));
    assertTrue(w.excludeGroup(Group.A));
    assertFalse(w.includePath(Group.A, "/x"));
  }

  @Test
  void nonEmptyIncludeSetWhitelistsOnlyListedPaths() {
    final var w = wiring(Map.of(Group.A, Set.of("/x")), Map.of());
    assertTrue(w.includeGroup(Group.A));
    assertFalse(w.excludeGroup(Group.A));
    assertTrue(w.includePath(Group.A, "/x"));
    assertFalse(w.includePath(Group.A, "/y"));
  }

  @Test
  void includeSetMinusExcludeSet() {
    final var w = wiring(Map.of(Group.A, Set.of("/x", "/y")), Map.of(Group.A, Set.of("/y")));
    assertTrue(w.includeGroup(Group.A));
    assertTrue(w.includePath(Group.A, "/x"));   // whitelisted, not excluded
    assertFalse(w.includePath(Group.A, "/y"));  // whitelisted but excluded
    assertFalse(w.includePath(Group.A, "/z"));  // not whitelisted
  }

  @Test
  void emptyExcludeSetOverridesNonEmptyIncludeSet() {
    final var w = wiring(Map.of(Group.A, Set.of("/x")), Map.of(Group.A, Set.of()));
    assertFalse(w.includeGroup(Group.A));
    assertTrue(w.excludeGroup(Group.A));
    assertFalse(w.includePath(Group.A, "/x"));
  }

  // --- the two negation invariants across every config in the truth table ---

  @Test
  void includeAndExcludeAreStrictNegations() {
    final List<Map<Group, Set<String>>> includeConfigs = List.of(
        Map.of(), Map.of(Group.A, Set.of()), Map.of(Group.A, Set.of("/x")));
    final List<Map<Group, Set<String>>> excludeConfigs = List.of(
        Map.of(), Map.of(Group.A, Set.of()), Map.of(Group.A, Set.of("/x")));
    final var paths = List.of("/x", "/y");

    for (final var include : includeConfigs) {
      for (final var exclude : excludeConfigs) {
        final var w = wiring(include, exclude);
        assertEquals(w.includeGroup(Group.A), !w.excludeGroup(Group.A),
            () -> "includeGroup != !excludeGroup for include=" + include + " exclude=" + exclude);
        for (final var path : paths) {
          assertEquals(w.includePath(Group.A, path), !w.excludePath(Group.A, path),
              () -> "includePath != !excludePath for include=" + include + " exclude=" + exclude + " path=" + path);
        }
      }
    }
  }

  // --- the wiring delegation actually gates registration on includePath ---

  @Test
  void groupGatedWiringSkipsExcludedPaths() {
    final var builder = new RecordingBuilder();
    final var w = new BaseHandlerWiring<>(builder, Map.of(), Map.of(Group.A, Set.of("/blocked")));

    w.queryBlockingGet(Group.A, "/allowed", HANDLER);
    w.queryBlockingGet(Group.A, "/blocked", HANDLER);
    w.queryBlockingPost(Group.A, "/blocked", HANDLER);
    w.pathBlockingGet(Group.A, "/allowed-prefix", HANDLER);
    w.pathBlockingGet(Group.A, "/blocked", HANDLER);

    assertEquals(List.of("queryBlockingGet:/allowed"), builder.queryGets);
    assertTrue(builder.queryPosts.isEmpty());
    assertEquals(List.of("pathBlockingGet:/allowed-prefix"), builder.pathGets);
  }

  @Test
  void everyGroupedWiringMethodGatesOnIncludePath() {
    final var builder = new RecordingBuilder();
    final var w = new BaseHandlerWiring<>(builder, Map.of(), Map.of(Group.A, Set.of("/blocked")));

    w.queryCachedResponse(Group.A, "/ok", CACHED);
    w.queryCachedResponse(Group.A, "/blocked", CACHED);
    w.queryNonBlockingGet(Group.A, "/ok", HANDLER);
    w.queryNonBlockingGet(Group.A, "/blocked", HANDLER);
    w.queryBlockingGet(Group.A, "/ok", HANDLER);
    w.queryBlockingGet(Group.A, "/blocked", HANDLER);
    w.queryNonBlockingPost(Group.A, "/ok", HANDLER);
    w.queryNonBlockingPost(Group.A, "/blocked", HANDLER);
    w.queryBlockingPost(Group.A, "/ok", HANDLER);
    w.queryBlockingPost(Group.A, "/blocked", HANDLER);

    w.pathCachedResponse(Group.A, "/ok", CACHED);
    w.pathCachedResponse(Group.A, "/blocked", CACHED);
    w.pathNonBlockingGet(Group.A, "/ok", HANDLER);
    w.pathNonBlockingGet(Group.A, "/blocked", HANDLER);
    w.pathBlockingGet(Group.A, "/ok", HANDLER);
    w.pathBlockingGet(Group.A, "/blocked", HANDLER);
    w.pathNonBlockingPost(Group.A, "/ok", HANDLER);
    w.pathNonBlockingPost(Group.A, "/blocked", HANDLER);
    w.pathBlockingPost(Group.A, "/ok", HANDLER);
    w.pathBlockingPost(Group.A, "/blocked", HANDLER);

    // every method registered exactly the /ok path and skipped /blocked
    assertEquals(List.of(
        "cachedQueryHandler:/ok", "nonBlockingQueryHandler:/ok", "queryBlockingGet:/ok"), builder.queryGets);
    assertEquals(List.of(
        "nonBlockingQueryPost:/ok", "queryBlockingPost:/ok"), builder.queryPosts);
    assertEquals(List.of(
        "cachedPathHandler:/ok", "nonBlockingPathHandler:/ok", "pathBlockingGet:/ok"), builder.pathGets);
    assertEquals(List.of(
        "nonBlockingPathPost:/ok", "pathBlockingPost:/ok"), builder.pathPosts);
  }

  @Test
  void ungroupedWiringAlwaysRegisters() {
    final var builder = new RecordingBuilder();
    final var w = new BaseHandlerWiring<>(builder, Map.of(Group.A, Set.of()), Map.of());

    // the no-group overloads bypass the include/exclude filter entirely, even though this
    // group's empty include-set would exclude everything
    w.queryCachedResponse("/always", CACHED);
    w.queryNonBlockingGet("/always", HANDLER);
    w.queryBlockingGet("/always", HANDLER);
    w.queryNonBlockingPost("/always", HANDLER);
    w.queryBlockingPost("/always", HANDLER);
    w.pathCachedResponse("/always", CACHED);
    w.pathNonBlockingGet("/always", HANDLER);
    w.pathBlockingGet("/always", HANDLER);
    w.pathNonBlockingPost("/always", HANDLER);
    w.pathBlockingPost("/always", HANDLER);

    assertEquals(List.of(
        "cachedQueryHandler:/always", "nonBlockingQueryHandler:/always", "queryBlockingGet:/always"),
        builder.queryGets);
    assertEquals(List.of(
        "nonBlockingQueryPost:/always", "queryBlockingPost:/always"), builder.queryPosts);
    assertEquals(List.of(
        "cachedPathHandler:/always", "nonBlockingPathHandler:/always", "pathBlockingGet:/always"),
        builder.pathGets);
    assertEquals(List.of(
        "nonBlockingPathPost:/always", "pathBlockingPost:/always"), builder.pathPosts);
  }

  @Test
  void serverBuilderIsExposed() {
    final var builder = new RecordingBuilder();
    assertSame(builder, new BaseHandlerWiring<>(builder, Map.of(), Map.of()).serverBuilder());
  }

  /// Records which paths get wired so the include/exclude gate can be observed end to end.
  private static final class RecordingBuilder implements HttpServerBuilder {

    private final List<String> queryGets = new ArrayList<>();
    private final List<String> queryPosts = new ArrayList<>();
    private final List<String> pathGets = new ArrayList<>();
    private final List<String> pathPosts = new ArrayList<>();

    @Override
    public <HG> HandlerWiring<HG> wireHandlers(final Map<HG, Set<String>> includeHandlersMap,
                                               final Map<HG, Set<String>> excludeHandlersMap) {
      return new BaseHandlerWiring<>(this, includeHandlersMap, excludeHandlersMap);
    }

    @Override
    public HttpServer createServer(final Executor executor, final String host, final int port) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void cachedQueryHandler(final String path, final CachedResponse handler) {
      queryGets.add("cachedQueryHandler:" + path);
    }

    @Override
    public void nonBlockingQueryHandler(final String path, final QueryHandler handler) {
      queryGets.add("nonBlockingQueryHandler:" + path);
    }

    @Override
    public void blockingQueryHandler(final String path, final QueryHandler handler) {
      queryGets.add("queryBlockingGet:" + path);
    }

    @Override
    public void nonBlockingQueryPost(final String path, final QueryHandler handler) {
      queryPosts.add("nonBlockingQueryPost:" + path);
    }

    @Override
    public void blockingQueryPost(final String path, final QueryHandler handler) {
      queryPosts.add("queryBlockingPost:" + path);
    }

    @Override
    public void cachedPathHandler(final String path, final CachedResponse handler) {
      pathGets.add("cachedPathHandler:" + path);
    }

    @Override
    public void nonBlockingPathHandler(final String path, final QueryHandler handler) {
      pathGets.add("nonBlockingPathHandler:" + path);
    }

    @Override
    public void blockingPathHandler(final String path, final QueryHandler handler) {
      pathGets.add("pathBlockingGet:" + path);
    }

    @Override
    public void nonBlockingPathPost(final String path, final QueryHandler handler) {
      pathPosts.add("nonBlockingPathPost:" + path);
    }

    @Override
    public void blockingPathPost(final String path, final QueryHandler handler) {
      pathPosts.add("pathBlockingPost:" + path);
    }
  }
}
