package software.sava.http_servers.jdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import software.sava.http_servers.core.response.QueryHandler;

import java.io.IOException;
import java.util.concurrent.Executor;

final class JdkQueryHandler implements HttpHandler {

  private final QueryHandler queryHandler;
  private final Executor executor; // null for blocking

  private JdkQueryHandler(final Executor executor, final QueryHandler queryHandler) {
    this.executor = executor;
    this.queryHandler = queryHandler;
  }

  static HttpHandler createBlockingGetHandler(final QueryHandler queryHandler) {
    return new JdkQueryHandler(null, queryHandler);
  }

  static HttpHandler createNonBlockingGetHandler(final Executor executor, final QueryHandler queryHandler) {
    return new JdkQueryHandler(executor, queryHandler);
  }

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    if (executor == null) {
      process(exchange);
    } else {
      executor.execute(() -> {
        try {
          process(exchange);
        } catch (final IOException e) {
          try {
            exchange.sendResponseHeaders(500, -1);
          } catch (final IOException ignored) {
          }
        }
      });
    }
  }

  private void process(final HttpExchange exchange) throws IOException {
    final var requestURI = exchange.getRequestURI();
    final var httpResponse = queryHandler.httpResponse(requestURI.getPath(), requestURI.getQuery());

    final var headers = exchange.getResponseHeaders();
    headers.set("Content-Type", httpResponse.contentType());

    final var body = httpResponse.body();
    exchange.sendResponseHeaders(httpResponse.statusCode(), body.length);
    try (exchange; final var os = exchange.getResponseBody()) {
      os.write(body);
    }
  }
}
