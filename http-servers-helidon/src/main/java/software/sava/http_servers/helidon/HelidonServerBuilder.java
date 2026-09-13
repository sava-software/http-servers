package software.sava.http_servers.helidon;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.http.Handler;
import software.sava.http_servers.core.handlers.HandlerMap;
import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.QueryHandler;
import software.sava.http_servers.core.server.BaseHttpServerBuilder;
import software.sava.http_servers.core.server.HttpServer;

import java.time.Duration;
import java.util.concurrent.Executor;

/// Builds a server over Helidon WebServer 4 (Níma). Divergences from the other backends:
///
/// - The `Executor` handed to `createServer` is accepted and ignored. Helidon runs every
///   request on its own virtual thread and refuses a response completed from any other
///   thread, and exposes no executor injection — so blocking and non-blocking handlers alike
///   run inline on the request thread (the FusionAuth backend ignores the executor too).
/// - `initRestServer` returns an unstarted `WebServerConfig.Builder`, not a server: a Helidon
///   `WebServer` cannot be restarted once stopped and its `start()` logs a bind failure instead
///   of throwing, so [HelidonHttpServer] builds the `WebServer` inside its own `start()`.
final class HelidonServerBuilder extends BaseHttpServerBuilder<Handler, WebServerConfig.Builder> {

  @Override
  protected WebServerConfig.Builder initRestServer(final Executor executor, final String host, final int port) {
    final var config = WebServer.builder()
        .port(port)
        // stop() is immediate: the HttpServer contract promises no grace period
        .shutdownGracePeriod(Duration.ZERO)
        // the adapter owns the lifecycle; no backend registers a JVM shutdown hook
        .shutdownHook(false);
    if (host != null && !host.isBlank()) {
      config.host(host);
    }
    return config;
  }

  @Override
  protected HttpServer createServer(final WebServerConfig.Builder config) {
    return new HelidonHttpServer(config);
  }

  @Override
  protected void setController(final WebServerConfig.Builder config, final HandlerMap<Handler> handlerMap) {
    final var controller = new HelidonController(handlerMap);
    // one catch-all route: every method (OPTIONS, HEAD, unknown) and every raw path reaches
    // the controller, which owns routing through the shared HandlerMap
    config.routing(routing -> routing.any(controller));
  }

  @Override
  protected Handler cachedResponse(final CachedResponse cachedResponse) {
    return new HelidonCachedResponseHandler(cachedResponse);
  }

  @Override
  protected Handler nonBlockingGet(final QueryHandler nonBlockingGetHandler) {
    return new HelidonQueryHandler(nonBlockingGetHandler);
  }

  @Override
  protected Handler blockingGet(final QueryHandler blockingGetHandler) {
    return new HelidonQueryHandler(blockingGetHandler);
  }

  @Override
  protected Handler nonBlockingPost(final QueryHandler nonBlockingPostHandler) {
    return new HelidonQueryHandler(nonBlockingPostHandler);
  }

  @Override
  protected Handler blockingPost(final QueryHandler blockingPostHandler) {
    return new HelidonQueryHandler(blockingPostHandler);
  }
}
