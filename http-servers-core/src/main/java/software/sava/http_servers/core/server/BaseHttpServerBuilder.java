package software.sava.http_servers.core.server;

import software.sava.http_servers.core.handlers.HandlerMap;
import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.QueryHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static java.lang.System.Logger.Level.INFO;

public abstract class BaseHttpServerBuilder<H, RS> implements HttpServerBuilder {

  protected static final System.Logger logger = System.getLogger(HttpServerBuilder.class.getName());

  protected final Map<String, H> queryHandlers;
  protected final Map<String, H> pathHandlers;

  protected BaseHttpServerBuilder() {
    this.queryHandlers = HashMap.newHashMap(64);
    this.pathHandlers = HashMap.newHashMap(32);
  }

  @Override
  public <HG> HandlerWiring<HG> wireHandlers(final Map<HG, Set<String>> includeHandlersMap,
                                             final Map<HG, Set<String>> excludeHandlersMap) {
    return new BaseHandlerWiring<>(
        this, includeHandlersMap, excludeHandlersMap
    );
  }

  protected abstract RS initRestServer(final Executor executor, final String host, final int port);

  protected abstract HttpServer createServer(final RS server);

  @Override
  public HttpServer createServer(final Executor executor, final String host, final int port) {
    final var restServer = initRestServer(executor, host, port);
    setController(restServer);
    return createServer(restServer);
  }

  protected void addQueryHandler(final String path, final H handler) {
    if (handler != null) {
      queryHandlers.put(path, handler);
      final int to = path.length() - 1;
      if (path.charAt(to) == '/') {
        queryHandlers.put(path.substring(0, to), handler);
      } else {
        queryHandlers.put(path + '/', handler);
      }
    }
    logger.log(INFO, path);
  }

  @Override
  public void cachedQueryHandler(final String path, final CachedResponse handler) {
    addQueryHandler(path, cachedResponse(handler));
  }

  @Override
  public void nonBlockingQueryHandler(final String path, final QueryHandler handler) {
    addQueryHandler(path, nonBlockingGet(handler));
  }

  @Override
  public void blockingQueryHandler(final String path, final QueryHandler handler) {
    addQueryHandler(path, blockingGet(handler));
  }

  protected void addPathHandler(final String path, final H handler) {
    if (handler != null) {
      pathHandlers.put(path, handler);
    }
    logger.log(INFO, path);
  }

  @Override
  public void cachedPathHandler(final String path, final CachedResponse handler) {
    addPathHandler(path, cachedResponse(handler));
  }

  @Override
  public void nonBlockingPathHandler(final String path, final QueryHandler handler) {
    addPathHandler(path, nonBlockingGet(handler));
  }

  @Override
  public void blockingPathHandler(final String path, final QueryHandler handler) {
    addPathHandler(path, blockingGet(handler));
  }

  protected boolean excludeGroup(final Set<String> exclusions) {
    return exclusions != null && exclusions.isEmpty();
  }

  protected abstract H cachedResponse(final CachedResponse cachedResponse);

  protected abstract H nonBlockingGet(final QueryHandler nonBlockingGetHandler);

  protected abstract H blockingGet(final QueryHandler blockingGetHandler);

  protected abstract void setController(final RS server, final HandlerMap<H> handlerMap);

  protected void setController(final RS server) {
    final var handlerMap = HandlerMap.createController(
        Map.copyOf(queryHandlers),
        List.copyOf(pathHandlers.entrySet())
    );
    setController(server, handlerMap);
  }
}
