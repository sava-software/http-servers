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
  public Set<String> excludeHandlers(final HG handlerGroup) {
    return excludeHandlersMap.get(handlerGroup);
  }

  @Override
  public boolean includeGroup(final HG handlerGroup) {
    final var exclusions = excludeHandlersMap.get(handlerGroup);
    return exclusions == null || !exclusions.isEmpty();
  }

  @Override
  public boolean includePath(final String path, final Set<String> exclusions) {
    return exclusions == null || !exclusions.contains(path);
  }

  @Override
  public boolean includePath(final String path, final HG handlerGroup) {
    return includePath(path, excludeHandlersMap.get(handlerGroup));
  }

  @Override
  public void queryCachedResponse(final String path, final CachedResponse cachedResponse) {
    serverBuilder.queryCachedHandler(path, cachedResponse);
  }

  @Override
  public void queryNonBlockingGet(final String path, final QueryHandler nonBlockingGetHandler) {
    serverBuilder.queryNonBlockingHandler(path, nonBlockingGetHandler);
  }

  @Override
  public void queryBlockingGet(final String path, final QueryHandler blockingGetHandler) {
    serverBuilder.queryBlockingHandler(path, blockingGetHandler);
  }

  @Override
  public void pathCachedResponse(final String path, final CachedResponse cachedResponse) {
    serverBuilder.pathCachedHandler(path, cachedResponse);
  }

  @Override
  public void pathNonBlockingGet(final String path, final QueryHandler nonBlockingGetHandler) {
    serverBuilder.pathNonBlockingHandler(path, nonBlockingGetHandler);
  }

  @Override
  public void pathBlockingGet(final String path, final QueryHandler blockingGetHandler) {
    serverBuilder.pathBlockingHandler(path, blockingGetHandler);
  }
}
