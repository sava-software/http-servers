package software.sava.http_servers.core.server;

import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.QueryHandler;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class BaseHandlerWiring<HG, H> implements HandlerWiring<HG> {

  private final HttpServerBuilder serverBuilder;
  protected final Map<HG, Set<String>> includeHandlersMap;
  protected final Map<HG, Set<String>> excludeHandlersMap;

  protected BaseHandlerWiring(final HttpServerBuilder serverBuilder,
                              final Map<HG, Set<String>> includeHandlersMap,
                              final Map<HG, Set<String>> excludeHandlersMap) {
    this.serverBuilder = serverBuilder;
    this.includeHandlersMap = Objects.requireNonNullElse(includeHandlersMap, Map.of());
    this.excludeHandlersMap = Objects.requireNonNullElse(excludeHandlersMap, Map.of());
  }

  @Override
  public HttpServerBuilder serverBuilder() {
    return serverBuilder;
  }

  @Override
  public boolean includeGroup(final HG handlerGroup) {
    final var inclusions = includeHandlersMap.get(handlerGroup);
    if (inclusions != null && !inclusions.isEmpty()) {
      return true;
    }
    final var exclusions = excludeHandlersMap.get(handlerGroup);
    return exclusions == null || !exclusions.isEmpty();
  }

  @Override
  public boolean includePath(final HG handlerGroup, final String path) {
    if (includeGroup(handlerGroup)) {
      final var exclusions = excludeHandlersMap.get(handlerGroup);
      if (exclusions != null && exclusions.contains(path)) {
        return false;
      }
      final var inclusions = includeHandlersMap.get(handlerGroup);
      return inclusions == null || inclusions.contains(path);
    } else {
      return false;
    }
  }

  @Override
  public boolean excludeGroup(final HG handlerGroup) {
    final var inclusions = includeHandlersMap.get(handlerGroup);
    if (inclusions != null && inclusions.isEmpty()) {
      return true;
    }
    final var exclusions = excludeHandlersMap.get(handlerGroup);
    return exclusions != null && exclusions.isEmpty();
  }

  @Override
  public boolean excludePath(final HG handlerGroup, final String path) {
    if (includeGroup(handlerGroup)) {
      final var exclusions = excludeHandlersMap.get(handlerGroup);
      if (exclusions != null && exclusions.contains(path)) {
        return true;
      }
      final var inclusions = includeHandlersMap.get(handlerGroup);
      return inclusions == null || !inclusions.contains(path);
    } else {
      return true;
    }
  }

  @Override
  public void queryCachedResponse(final HG handlerGroup, final String path, final CachedResponse cachedResponse) {
    if (includePath(handlerGroup, path)) {
      serverBuilder.cachedQueryHandler(path, cachedResponse);
    }
  }

  @Override
  public void queryCachedResponse(final String path, final CachedResponse cachedResponse) {
    serverBuilder.cachedQueryHandler(path, cachedResponse);
  }

  @Override
  public void queryNonBlockingGet(final HG handlerGroup, final String path, final QueryHandler nonBlockingGetHandler) {
    if (includePath(handlerGroup, path)) {
      serverBuilder.nonBlockingQueryHandler(path, nonBlockingGetHandler);
    }
  }

  @Override
  public void queryNonBlockingGet(final String path, final QueryHandler nonBlockingGetHandler) {
    serverBuilder.nonBlockingQueryHandler(path, nonBlockingGetHandler);
  }

  @Override
  public void queryBlockingGet(final HG handlerGroup, final String path, final QueryHandler blockingGetHandler) {
    if (includePath(handlerGroup, path)) {
      serverBuilder.blockingQueryHandler(path, blockingGetHandler);
    }
  }

  @Override
  public void queryBlockingGet(final String path, final QueryHandler blockingGetHandler) {
    serverBuilder.blockingQueryHandler(path, blockingGetHandler);
  }

  @Override
  public void queryNonBlockingPost(final HG handlerGroup, final String path, final QueryHandler nonBlockingPostHandler) {
    if (includePath(handlerGroup, path)) {
      serverBuilder.nonBlockingQueryPost(path, nonBlockingPostHandler);
    }
  }

  @Override
  public void queryNonBlockingPost(final String path, final QueryHandler nonBlockingPostHandler) {
    serverBuilder.nonBlockingQueryPost(path, nonBlockingPostHandler);
  }

  @Override
  public void queryBlockingPost(final HG handlerGroup, final String path, final QueryHandler blockingPostHandler) {
    if (includePath(handlerGroup, path)) {
      serverBuilder.blockingQueryPost(path, blockingPostHandler);
    }
  }

  @Override
  public void queryBlockingPost(final String path, final QueryHandler blockingPostHandler) {
    serverBuilder.blockingQueryPost(path, blockingPostHandler);
  }

  @Override
  public void pathCachedResponse(final HG handlerGroup, final String path, final CachedResponse cachedResponse) {
    if (includePath(handlerGroup, path)) {
      serverBuilder.cachedPathHandler(path, cachedResponse);
    }
  }

  @Override
  public void pathCachedResponse(final String path, final CachedResponse cachedResponse) {
    serverBuilder.cachedPathHandler(path, cachedResponse);
  }

  @Override
  public void pathNonBlockingGet(final HG handlerGroup, final String path, final QueryHandler nonBlockingGetHandler) {
    if (includePath(handlerGroup, path)) {
      serverBuilder.nonBlockingPathHandler(path, nonBlockingGetHandler);
    }
  }

  @Override
  public void pathNonBlockingGet(final String path, final QueryHandler nonBlockingGetHandler) {
    serverBuilder.nonBlockingPathHandler(path, nonBlockingGetHandler);
  }

  @Override
  public void pathBlockingGet(final HG handlerGroup, final String path, final QueryHandler blockingGetHandler) {
    if (includePath(handlerGroup, path)) {
      serverBuilder.blockingPathHandler(path, blockingGetHandler);
    }
  }

  @Override
  public void pathBlockingGet(final String path, final QueryHandler blockingGetHandler) {
    serverBuilder.blockingPathHandler(path, blockingGetHandler);
  }

  @Override
  public void pathNonBlockingPost(final HG handlerGroup, final String path, final QueryHandler nonBlockingPostHandler) {
    if (includePath(handlerGroup, path)) {
      serverBuilder.nonBlockingPathPost(path, nonBlockingPostHandler);
    }
  }

  @Override
  public void pathNonBlockingPost(final String path, final QueryHandler nonBlockingPostHandler) {
    serverBuilder.nonBlockingPathPost(path, nonBlockingPostHandler);
  }

  @Override
  public void pathBlockingPost(final HG handlerGroup, final String path, final QueryHandler blockingPostHandler) {
    if (includePath(handlerGroup, path)) {
      serverBuilder.blockingPathPost(path, blockingPostHandler);
    }
  }

  @Override
  public void pathBlockingPost(final String path, final QueryHandler blockingPostHandler) {
    serverBuilder.blockingPathPost(path, blockingPostHandler);
  }
}
