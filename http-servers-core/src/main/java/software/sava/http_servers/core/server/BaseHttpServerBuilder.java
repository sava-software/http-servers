package software.sava.http_servers.core.server;

import software.sava.http_servers.core.handlers.HandlerMap;
import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.QueryHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static java.lang.System.Logger.Level.INFO;

public abstract class BaseHttpServerBuilder<H, RS> implements HttpServerBuilder {

  protected static final System.Logger logger = System.getLogger(HttpServerBuilder.class.getName());

  protected static final String GET = "GET";
  protected static final String POST = "POST";

  protected final Map<String, Map<String, H>> queryHandlers;
  protected final Map<String, Map<String, H>> pathHandlers;

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

  protected void addQueryHandler(final String method, final String path, final H handler) {
    if (handler != null) {
      queryHandlers.computeIfAbsent(path, _ -> new HashMap<>()).put(method, handler);
      final int to = path.length() - 1;
      if (path.charAt(to) == '/') {
        queryHandlers.computeIfAbsent(path.substring(0, to), _ -> new HashMap<>()).put(method, handler);
      } else {
        queryHandlers.computeIfAbsent(path + '/', _ -> new HashMap<>()).put(method, handler);
      }
    }
    logger.log(INFO, path);
  }

  @Override
  public void cachedQueryHandler(final String path, final CachedResponse handler) {
    addQueryHandler(GET, path, cachedResponse(handler));
  }

  @Override
  public void nonBlockingQueryHandler(final String path, final QueryHandler handler) {
    addQueryHandler(GET, path, nonBlockingGet(handler));
  }

  @Override
  public void blockingQueryHandler(final String path, final QueryHandler handler) {
    addQueryHandler(GET, path, blockingGet(handler));
  }

  @Override
  public void nonBlockingQueryPost(final String path, final QueryHandler handler) {
    addQueryHandler(POST, path, nonBlockingPost(handler));
  }

  @Override
  public void blockingQueryPost(final String path, final QueryHandler handler) {
    addQueryHandler(POST, path, blockingPost(handler));
  }

  protected void addPathHandler(final String method, final String path, final H handler) {
    if (handler != null) {
      pathHandlers.computeIfAbsent(path, _ -> new HashMap<>()).put(method, handler);
    }
    logger.log(INFO, path);
  }

  @Override
  public void cachedPathHandler(final String path, final CachedResponse handler) {
    addPathHandler(GET, path, cachedResponse(handler));
  }

  @Override
  public void nonBlockingPathHandler(final String path, final QueryHandler handler) {
    addPathHandler(GET, path, nonBlockingGet(handler));
  }

  @Override
  public void blockingPathHandler(final String path, final QueryHandler handler) {
    addPathHandler(GET, path, blockingGet(handler));
  }

  @Override
  public void nonBlockingPathPost(final String path, final QueryHandler handler) {
    addPathHandler(POST, path, nonBlockingPost(handler));
  }

  @Override
  public void blockingPathPost(final String path, final QueryHandler handler) {
    addPathHandler(POST, path, blockingPost(handler));
  }

  protected boolean excludeGroup(final Set<String> exclusions) {
    return exclusions != null && exclusions.isEmpty();
  }

  protected abstract H cachedResponse(final CachedResponse cachedResponse);

  protected abstract H nonBlockingGet(final QueryHandler nonBlockingGetHandler);

  protected abstract H blockingGet(final QueryHandler blockingGetHandler);

  protected abstract H nonBlockingPost(final QueryHandler nonBlockingPostHandler);

  protected abstract H blockingPost(final QueryHandler blockingPostHandler);

  protected abstract void setController(final RS server, final HandlerMap<H> handlerMap);

  protected void setController(final RS server) {
    final var queryCopy = HashMap.<String, Map<String, H>>newHashMap(queryHandlers.size());
    queryHandlers.forEach((path, methodHandlers) -> queryCopy.put(path, Map.copyOf(methodHandlers)));

    final var pathCopy = new ArrayList<Map.Entry<String, Map<String, H>>>(pathHandlers.size());
    pathHandlers.forEach((path, methodHandlers) -> pathCopy.add(Map.entry(path, Map.copyOf(methodHandlers))));

    final var handlerMap = HandlerMap.createController(Map.copyOf(queryCopy), pathCopy);
    setController(server, handlerMap);
  }
}
