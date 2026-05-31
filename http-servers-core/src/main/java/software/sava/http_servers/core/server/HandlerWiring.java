package software.sava.http_servers.core.server;

import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.QueryHandler;

public interface HandlerWiring<HG> {

  HttpServerBuilder serverBuilder();

  boolean includeGroup(final HG handlerGroup);

  boolean includePath(final HG handlerGroup, final String path);

  boolean excludeGroup(final HG handlerGroup);

  boolean excludePath(final HG handlerGroup, final String path);

  void queryCachedResponse(final HG handlerGroup, final String path, final CachedResponse cachedResponse);

  void queryCachedResponse(final String path, final CachedResponse cachedResponse);

  void queryNonBlockingGet(final HG handlerGroup, final String path, final QueryHandler nonBlockingGetHandler);

  void queryNonBlockingGet(final String path, final QueryHandler nonBlockingGetHandler);

  void queryBlockingGet(final HG handlerGroup, final String path, final QueryHandler blockingGetHandler);

  void queryBlockingGet(final String path, final QueryHandler blockingGetHandler);

  void pathCachedResponse(final HG handlerGroup, final String path, final CachedResponse cachedResponse);

  void pathCachedResponse(final String path, final CachedResponse cachedResponse);

  void pathNonBlockingGet(final HG handlerGroup, final String path, final QueryHandler nonBlockingGetHandler);

  void pathNonBlockingGet(final String path, final QueryHandler nonBlockingGetHandler);

  void pathBlockingGet(final HG handlerGroup, final String path, final QueryHandler blockingGetHandler);

  void pathBlockingGet(final String path, final QueryHandler blockingGetHandler);
}
