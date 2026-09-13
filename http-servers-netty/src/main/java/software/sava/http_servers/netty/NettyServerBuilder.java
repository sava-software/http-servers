package software.sava.http_servers.netty;

import software.sava.http_servers.core.handlers.HandlerMap;
import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.QueryHandler;
import software.sava.http_servers.core.server.BaseHttpServerBuilder;
import software.sava.http_servers.core.server.HttpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;

import static java.lang.System.Logger.Level.ERROR;

/// Builds a server over Netty 4.2 (NIO transport, HTTP/1.x codec). The package is not
/// exported, so consumers reach this builder only through `HttpServerBuilderFactory` and
/// the three tuning knobs below are fixed at their defaults for them — like the JDK and
/// FusionAuth backends, which expose no knobs either; the wider constructor exists for this
/// package's own tests.
final class NettyServerBuilder extends BaseHttpServerBuilder<NettyHandler, NettyHttpServer> {

  /// The largest request body the aggregator assembles before answering 413: 64 MiB —
  /// generous for an API server, and a bound the other backends do not have.
  static final int DEFAULT_MAX_CONTENT_LENGTH = 64 << 20;
  /// Zero leaves the I/O worker count to Netty (twice the available processors).
  static final int DEFAULT_IO_THREADS = 0;

  private final int maxContentLength;
  private final int ioThreads;

  NettyServerBuilder(final int maxContentLength, final int ioThreads) {
    this.maxContentLength = maxContentLength;
    this.ioThreads = ioThreads;
  }

  NettyServerBuilder() {
    this(DEFAULT_MAX_CONTENT_LENGTH, DEFAULT_IO_THREADS);
  }

  @Override
  protected NettyHttpServer initRestServer(final Executor executor, final String host, final int port) {
    try {
      final var address = host == null || host.isBlank()
          ? new InetSocketAddress(port)
          : new InetSocketAddress(host, port);
      return new NettyHttpServer(executor, address, ioThreads);
    } catch (final RuntimeException e) {
      logger.log(ERROR, "Failed to create http server", e);
      throw e;
    }
  }

  @Override
  protected HttpServer createServer(final NettyHttpServer server) {
    return server;
  }

  @Override
  protected void setController(final NettyHttpServer server, final HandlerMap<NettyHandler> handlerMap) {
    server.childHandler(new NettyChannelInitializer(handlerMap, server.executor(), maxContentLength));
  }

  @Override
  protected NettyHandler cachedResponse(final CachedResponse cachedResponse) {
    return new NettyCachedResponseHandler(cachedResponse);
  }

  @Override
  protected NettyHandler nonBlockingGet(final QueryHandler nonBlockingGetHandler) {
    return NettyQueryHandler.createNonBlockingGetHandler(nonBlockingGetHandler);
  }

  @Override
  protected NettyHandler blockingGet(final QueryHandler blockingGetHandler) {
    return NettyQueryHandler.createBlockingGetHandler(blockingGetHandler);
  }

  @Override
  protected NettyHandler nonBlockingPost(final QueryHandler nonBlockingPostHandler) {
    return NettyQueryHandler.createNonBlockingPostHandler(nonBlockingPostHandler);
  }

  @Override
  protected NettyHandler blockingPost(final QueryHandler blockingPostHandler) {
    return NettyQueryHandler.createBlockingPostHandler(blockingPostHandler);
  }
}
