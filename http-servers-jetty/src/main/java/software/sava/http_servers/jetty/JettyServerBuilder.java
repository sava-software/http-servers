package software.sava.http_servers.jetty;

import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import software.sava.http_servers.core.handlers.HandlerMap;
import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.QueryHandler;
import software.sava.http_servers.core.server.BaseHttpServerBuilder;
import software.sava.http_servers.core.server.HttpServer;

import java.util.concurrent.Executor;

public class JettyServerBuilder extends BaseHttpServerBuilder<JettyHandler, Server> {

  @Override
  protected Server initRestServer(final Executor executor, final String host, final int port) {
    final var threadPool = new QueuedThreadPool(Runtime.getRuntime().availableProcessors());
    threadPool.setVirtualThreadsExecutor(executor);
    final var server = new Server(threadPool);

    final var httpConfiguration = new HttpConfiguration();
    httpConfiguration.setSendServerVersion(false);
    httpConfiguration.setSendXPoweredBy(false);

    final var h11 = new HttpConnectionFactory(httpConfiguration);
    final var h2 = new HTTP2CServerConnectionFactory(httpConfiguration);
    final var serverConnector = new ServerConnector(server, h11, h2);
    if (host != null && !host.isBlank()) {
      serverConnector.setHost(host);
    }
    serverConnector.setPort(port);
    server.addConnector(serverConnector);

    return server;
  }

  @Override
  protected HttpServer createServer(final Server server) {
    return new JettyHttpSever(server);
  }

  @Override
  protected JettyHandler cachedResponse(final CachedResponse cachedResponse) {
    return new JettyCachedJsonResponseHandler(cachedResponse);
  }

  @Override
  protected JettyHandler nonBlockingGet(final QueryHandler nonBlockingGetHandler) {
    return JettyQueryHandler.createNonBlockingGetHandler(nonBlockingGetHandler);
  }

  @Override
  protected JettyHandler blockingGet(final QueryHandler blockingGetHandler) {
    return JettyQueryHandler.createBlockingGetHandler(blockingGetHandler);
  }

  @Override
  protected void setController(final Server server, final HandlerMap<JettyHandler> handlerMap) {
    final var controller = new JettyController(handlerMap);
    final var compressionHandler = new CompressionHandler(controller);
    server.setHandler(compressionHandler);
  }
}
