package software.sava.http_servers.netty;

import software.sava.http_servers.core.handlers.HandlerMap;
import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.QueryHandler;
import software.sava.http_servers.core.server.BaseHttpServerBuilder;
import software.sava.http_servers.core.server.HttpServer;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executor;

import static java.lang.System.Logger.Level.ERROR;
import static java.util.Objects.requireNonNull;

/// Builds a server over Netty 4.2 (NIO transport, HTTP/1.x codec). The package is not
/// exported, so consumers reach this builder through `HttpServerBuilderFactory`. Factory
/// builders capture the gzip compression property at construction; wider constructors
/// keep this package's tests independent of process-wide configuration.
final class NettyServerBuilder extends BaseHttpServerBuilder<NettyHandler, NettyHttpServer> {

  /// The largest request body the aggregator assembles before answering 413: 64 MiB —
  /// generous for an API server, and a bound the other backends do not have.
  static final int DEFAULT_MAX_CONTENT_LENGTH = 64 << 20;
  /// Zero leaves the I/O worker count to Netty (twice the available processors).
  static final int DEFAULT_IO_THREADS = 0;
  /// A connection that is neither carrying a fully received request awaiting its response nor
  /// making progress for this long — measured from the later of its last decoded read and its
  /// last completed response, so a stalled request body is idle and a request awaiting its
  /// handler never is — is closed: 30 s, the JDK backend's idle interval and Jetty's connector
  /// default. No mutant reaches a static initializer, so the value is pinned by
  /// `NettyConformanceTest.theDefaultIdleTimeoutMatchesTheOtherBackends`.
  static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);

  private final int maxContentLength;
  private final int ioThreads;
  private final long idleTimeoutNanos;
  private final boolean compression;
  static final String COMPRESSION_PROPERTY = "software.sava.http_servers.netty.compression";

  /// `idleTimeout` must be positive and expressible in nanoseconds (about 292 years): there is
  /// no "disabled" value, a zero or negative timeout would close every connection the instant
  /// it was accepted, and an absent or out-of-range one would otherwise fail only inside
  /// `createServer` — all are refused here, at construction.
  NettyServerBuilder(final int maxContentLength, final int ioThreads, final Duration idleTimeout) {
    this(maxContentLength, ioThreads, idleTimeout, true);
  }

  NettyServerBuilder(final int maxContentLength, final int ioThreads, final Duration idleTimeout,
                     final boolean compression) {
    this.compression = compression;
    if (!requireNonNull(idleTimeout, "idleTimeout").isPositive()) {
      throw new IllegalArgumentException("idleTimeout must be positive: " + idleTimeout);
    }
    this.maxContentLength = maxContentLength;
    this.ioThreads = ioThreads;
    this.idleTimeoutNanos = idleTimeout.toNanos();
  }

  NettyServerBuilder() {
    this(DEFAULT_MAX_CONTENT_LENGTH, DEFAULT_IO_THREADS, DEFAULT_IDLE_TIMEOUT,
        configuredCompression());
  }

  private static boolean configuredCompression() {
    final var value = System.getProperty(COMPRESSION_PROPERTY);
    if (value == null) {
      return true;
    }
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value)) {
      return false;
    }
    throw new IllegalArgumentException(COMPRESSION_PROPERTY + " must be true or false: " + value);
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
    server.childHandler(new NettyChannelInitializer(handlerMap, server.executor(), maxContentLength, idleTimeoutNanos,
        System::nanoTime, compression));
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
