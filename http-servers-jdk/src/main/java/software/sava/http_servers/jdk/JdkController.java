package software.sava.http_servers.jdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

final class JdkController implements HttpHandler {

  private final Map<String, HttpHandler> methodHandlers;
  private final String allowedMethods;

  JdkController(final Map<String, HttpHandler> methodHandlers) {
    this.methodHandlers = methodHandlers;
    this.allowedMethods = String.join(", ", methodHandlers.keySet());
  }

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    final var handler = methodHandlers.get(exchange.getRequestMethod());
    if (handler == null) {
      exchange.getResponseHeaders().set("Allow", allowedMethods);
      try (exchange) {
        exchange.sendResponseHeaders(405, -1);
      }
      return;
    }
    handler.handle(exchange);
  }
}
