package software.sava.http_servers.core.server;

import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.QueryHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

public interface HttpServerBuilder {

  <HG> HandlerWiring<HG> wireHandlers(final Map<HG, Set<String>> includeHandlersMap,
                                      final Map<HG, Set<String>> excludeHandlersMap);

  HttpServer createServer(final Executor executor, final String host, final int port);

  void queryCachedHandler(final String path, final CachedResponse handler);

  void queryNonBlockingHandler(final String path, final QueryHandler handler);

  void queryBlockingHandler(final String path, final QueryHandler handler);

  void pathCachedHandler(final String path, final CachedResponse handler);

  void pathNonBlockingHandler(final String path, final QueryHandler handler);

  void pathBlockingHandler(final String path, final QueryHandler handler);
}
