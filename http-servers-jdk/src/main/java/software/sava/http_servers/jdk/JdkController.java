package software.sava.http_servers.jdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import software.sava.http_servers.core.handlers.HandlerMap;

import java.io.IOException;

import static java.lang.System.Logger.Level.ERROR;

/// Dispatches every request through the shared [HandlerMap] from a single root context, so
/// JDK routing agrees with the Jetty and FusionAuth adapters: query-handler paths match
/// exactly (plus the builder's trailing-slash alias) and only path handlers match by
/// prefix. Registering one jdk-httpserver context per path would instead prefix-match
/// everything — `/echo` would serve `/echo/anything`.
final class JdkController implements HttpHandler {

  private static final System.Logger logger = System.getLogger(JdkController.class.getName());

  private final HandlerMap<HttpHandler> handlerMap;

  JdkController(final HandlerMap<HttpHandler> handlerMap) {
    this.handlerMap = handlerMap;
  }

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    // getPath() percent-decodes, which would let an encoded traversal reach a prefix
    // handler decoded; routing canonicalization is owned by the shared HandlerMap
    final var lookup = handlerMap.lookupHandler(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath());
    final var handler = lookup.handler();
    if (handler == null) {
      final var allowedMethods = lookup.allowedMethods();
      try (exchange) {
        if (lookup.badRequest()) {
          exchange.sendResponseHeaders(400, -1);
        } else if (allowedMethods == null) {
          exchange.sendResponseHeaders(404, -1);
        } else {
          exchange.getResponseHeaders().set("Allow", allowedMethods);
          exchange.sendResponseHeaders(405, -1);
        }
      }
      return;
    }
    try {
      handler.handle(exchange);
    } catch (final RuntimeException e) {
      // without this the jdk server aborts the connection, and the client sees EOF, not a status
      logger.log(ERROR, "Failed to process request.", e);
      serverError(exchange);
    }
  }

  static void serverError(final HttpExchange exchange) {
    try (exchange) {
      exchange.sendResponseHeaders(500, -1);
    } catch (final IOException | RuntimeException ignored) {
      // response headers were already sent; closing the exchange is all that remains
    }
  }
}
