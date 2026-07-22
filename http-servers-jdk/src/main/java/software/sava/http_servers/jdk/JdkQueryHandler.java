package software.sava.http_servers.jdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import software.sava.http_servers.core.response.QueryHandler;

import java.io.IOException;
import java.util.concurrent.Executor;

import static java.lang.System.Logger.Level.ERROR;

final class JdkQueryHandler implements HttpHandler {

  private static final System.Logger logger = System.getLogger(JdkQueryHandler.class.getName());

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

  static HttpHandler createBlockingPostHandler(final QueryHandler queryHandler) {
    return new JdkQueryHandler(null, queryHandler);
  }

  static HttpHandler createNonBlockingPostHandler(final Executor executor, final QueryHandler queryHandler) {
    return new JdkQueryHandler(executor, queryHandler);
  }

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    if (executor == null) {
      // blocking: a RuntimeException propagates to JdkController's guard, which answers 500
      process(exchange);
    } else {
      executor.execute(() -> {
        try {
          process(exchange);
        } catch (final IOException | RuntimeException e) {
          // the controller's frame is gone by now; an unanswered exchange would hang the client
          logger.log(ERROR, "Failed to process request.", e);
          JdkController.serverError(exchange);
        }
      });
    }
  }

  private void process(final HttpExchange exchange) throws IOException {
    final var request = new JdkRequest(exchange);
    final var httpResponse = queryHandler.httpResponse(request);

    final var headers = exchange.getResponseHeaders();
    headers.set("Content-Type", httpResponse.contentType());
    for (final var header : httpResponse.headers().entrySet()) {
      headers.set(header.getKey(), header.getValue());
    }

    final var body = httpResponse.body();
    exchange.sendResponseHeaders(httpResponse.statusCode(), body.length);
    try (exchange; final var os = exchange.getResponseBody()) {
      os.write(body);
    }
  }
}
