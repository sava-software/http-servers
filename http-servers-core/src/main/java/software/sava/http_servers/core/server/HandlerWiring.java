package software.sava.http_servers.core.server;

import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.QueryHandler;

import java.util.Set;

public interface HandlerWiring<HG> {

  Set<String> excludeHandlers(final HG handlerGroup);

  boolean includeGroup(final HG handlerGroup);

  boolean includePath(final String path, final Set<String> exclusions);

  default boolean includePath(final HG handlerGroup, final String path) {
    return includePath(path, excludeHandlers(handlerGroup));
  }

  boolean includePath(final String path, final HG handlerGroup);

  void queryCachedResponse(final String path, final CachedResponse cachedResponse);

  void queryNonBlockingGet(final String path,
                           final QueryHandler nonBlockingGetHandler);

  void queryBlockingGet(final String path, final QueryHandler blockingGetHandler);

  void pathCachedResponse(final String path, final CachedResponse cachedResponse);

  void pathNonBlockingGet(final String path,
                          final QueryHandler nonBlockingGetHandler);

  void pathBlockingGet(final String path, final QueryHandler blockingGetHandler);
}
