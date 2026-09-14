package software.sava.http_servers.jdk;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import software.sava.http_servers.core.handlers.HandlerMap;
import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.QueryHandler;
import software.sava.http_servers.core.server.BaseHttpServerBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;

import static java.lang.System.Logger.Level.ERROR;

public class JdkServerBuilder extends BaseHttpServerBuilder<HttpHandler, HttpServer> {

  protected JdkServerBuilder() {
  }

  /// @deprecated the task executor is not used any more and the argument is ignored:
  /// non-blocking handlers run on the `Executor` handed to [#createServer], like blocking
  /// ones, because jdk.httpserver can only clean up a failed exchange on the thread it
  /// dispatched it to (see [JdkController]). Kept for one release so a class-path subclass
  /// calling it still compiles — the only kind there can be: this module exports no package,
  /// so on the module path the builder is reachable only through
  /// [JDKHttpServerBuilderFactory], which never called it.
  @Deprecated(forRemoval = true)
  protected JdkServerBuilder(final Executor taskExecutor) {
    this();
  }

  @Override
  protected HttpServer initRestServer(final Executor executor, final String host, final int port) {
    try {
      final var address = host == null || host.isBlank()
          ? new InetSocketAddress(port)
          : new InetSocketAddress(host, port);
      final var httpServer = HttpServer.create(address, 0);
      httpServer.setExecutor(executor);
      return httpServer;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    } catch (final RuntimeException e) {
      logger.log(ERROR, "Failed to create http server", e);
      throw e;
    }
  }

  @Override
  protected software.sava.http_servers.core.server.HttpServer createServer(final HttpServer server) {
    return new JdkHttpServer(server);
  }

  @Override
  protected HttpHandler cachedResponse(final CachedResponse cachedResponse) {
    return new JdkCachedJsonResponseHandler(cachedResponse);
  }

  @Override
  protected HttpHandler nonBlockingGet(final QueryHandler nonBlockingGetHandler) {
    return new JdkQueryHandler(nonBlockingGetHandler);
  }

  @Override
  protected HttpHandler blockingGet(final QueryHandler blockingGetHandler) {
    return new JdkQueryHandler(blockingGetHandler);
  }

  @Override
  protected HttpHandler nonBlockingPost(final QueryHandler nonBlockingPostHandler) {
    return new JdkQueryHandler(nonBlockingPostHandler);
  }

  @Override
  protected HttpHandler blockingPost(final QueryHandler blockingPostHandler) {
    return new JdkQueryHandler(blockingPostHandler);
  }

  @Override
  protected void setController(final HttpServer server, final HandlerMap<HttpHandler> handlerMap) {
    server.createContext("/", new JdkController(handlerMap));
  }
}
