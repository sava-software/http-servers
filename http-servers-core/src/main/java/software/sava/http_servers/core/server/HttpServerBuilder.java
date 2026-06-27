package software.sava.http_servers.core.server;

import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.QueryHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

public interface HttpServerBuilder {

  <HG> HandlerWiring<HG> wireHandlers(final Map<HG, Set<String>> includeHandlersMap,
                                      final Map<HG, Set<String>> excludeHandlersMap);

  default <HG> HandlerWiring<HG> wireIncludedHandlers(final Map<HG, Set<String>> includeHandlersMap) {
    return wireHandlers(includeHandlersMap, null);
  }

  default <HG> HandlerWiring<HG> wireNonExcludedHandlers(final Map<HG, Set<String>> excludeHandlersMap) {
    return wireHandlers(null, excludeHandlersMap);
  }

  default <HG> HandlerWiring<HG> wireHandlers() {
    return wireHandlers(null, null);
  }

  HttpServer createServer(final Executor executor, final String host, final int port);

  void cachedQueryHandler(final String path, final CachedResponse handler);

  void nonBlockingQueryHandler(final String path, final QueryHandler handler);

  void blockingQueryHandler(final String path, final QueryHandler handler);

  void nonBlockingQueryPost(final String path, final QueryHandler handler);

  void blockingQueryPost(final String path, final QueryHandler handler);

  void cachedPathHandler(final String path, final CachedResponse handler);

  void nonBlockingPathHandler(final String path, final QueryHandler handler);

  void blockingPathHandler(final String path, final QueryHandler handler);

  void nonBlockingPathPost(final String path, final QueryHandler handler);

  void blockingPathPost(final String path, final QueryHandler handler);
}
