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
import java.util.concurrent.Executors;

import static java.lang.System.Logger.Level.ERROR;

public class JdkServerBuilder extends BaseHttpServerBuilder<HttpHandler, HttpServer> {

  private final Executor taskExecutor;

  protected JdkServerBuilder(final Executor taskExecutor) {
    this.taskExecutor = taskExecutor;
  }


  protected JdkServerBuilder() {
    this(Executors.newVirtualThreadPerTaskExecutor());
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
    return JdkQueryHandler.createNonBlockingGetHandler(taskExecutor, nonBlockingGetHandler);
  }

  @Override
  protected HttpHandler blockingGet(final QueryHandler blockingGetHandler) {
    return JdkQueryHandler.createBlockingGetHandler(blockingGetHandler);
  }

  @Override
  protected void setController(final HttpServer server, final HandlerMap<HttpHandler> handlerMap) {
    handlerMap.queryHandlerStream().forEach(entry -> server.createContext(entry.getKey(), entry.getValue()));
    handlerMap.pathHandlerStream().forEach(entry -> server.createContext(entry.getKey(), entry.getValue()));
  }
}
