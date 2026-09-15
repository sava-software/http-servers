package software.sava.http_servers.jetty;

import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ReservedThreadExecutor;
import software.sava.http_servers.core.handlers.HandlerMap;
import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.QueryHandler;
import software.sava.http_servers.core.server.BaseHttpServerBuilder;
import software.sava.http_servers.core.server.HttpServer;

import java.util.concurrent.Executor;

public class JettyServerBuilder extends BaseHttpServerBuilder<Handler, Server> {

  /// Jetty refuses to start a connector ("Insufficient configured threads") unless the pool's
  /// maximum exceeds the threads its components lease, so a pool of one platform thread per
  /// processor is raised to one thread beyond those leases when it would not clear them —
  /// which happens on one to three processors, never on a larger host.
  static int maxThreads(final int availableProcessors, final int leasedThreads) {
    return Math.max(availableProcessors, leasedThreads + 1);
  }

  /// The threads Jetty's `ThreadPoolBudget` leases from `threadPool` when the server starts:
  /// the reserved-thread executor (none once a virtual-thread executor is set), the
  /// connector's acceptors and its selectors, each read from Jetty's own heuristics before
  /// start. The reserved count also depends on the pool's maximum, but only from 16 threads
  /// up, and [#maxThreads] raises a pool only while these leases (at most 3 below 16 threads)
  /// meet the processor count, so raising it never changes this prediction.
  static int leasedThreads(final QueuedThreadPool threadPool, final ServerConnector connector) {
    return ReservedThreadExecutor.reservedThreads(threadPool, threadPool.getReservedThreads())
        + connector.getAcceptors()
        + connector.getSelectorManager().getSelectorCount();
  }

  @Override
  protected Server initRestServer(final Executor executor, final String host, final int port) {
    // The count Jetty's lease heuristics read: the JVM's, unless JETTY_AVAILABLE_PROCESSORS overrides it.
    final int availableProcessors = ProcessorUtils.availableProcessors();
    final var threadPool = new QueuedThreadPool(availableProcessors);
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
    threadPool.setMaxThreads(maxThreads(availableProcessors, leasedThreads(threadPool, serverConnector)));

    return server;
  }

  @Override
  protected HttpServer createServer(final Server server) {
    return new JettyHttpServer(server);
  }

  @Override
  protected Handler cachedResponse(final CachedResponse cachedResponse) {
    return new JettyCachedJsonResponseHandler(cachedResponse);
  }

  @Override
  protected Handler nonBlockingGet(final QueryHandler nonBlockingGetHandler) {
    return JettyQueryHandler.createNonBlockingHandler(nonBlockingGetHandler);
  }

  @Override
  protected Handler blockingGet(final QueryHandler blockingGetHandler) {
    return JettyQueryHandler.createBlockingHandler(blockingGetHandler);
  }

  @Override
  protected Handler nonBlockingPost(final QueryHandler nonBlockingPostHandler) {
    return JettyQueryHandler.createNonBlockingHandler(nonBlockingPostHandler);
  }

  @Override
  protected Handler blockingPost(final QueryHandler blockingPostHandler) {
    return JettyQueryHandler.createBlockingHandler(blockingPostHandler);
  }

  @Override
  protected void setController(final Server server, final HandlerMap<Handler> handlerMap) {
    final var controller = new JettyController(handlerMap);
    final var compressionHandler = new CompressionHandler(controller);
    server.setHandler(compressionHandler);
  }
}
