package software.sava.http_servers.jdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import software.sava.http_servers.core.response.CachedResponse;

import java.io.IOException;

final class JdkCachedJsonResponseHandler implements HttpHandler {

  private final CachedResponse cachedResponse;

  JdkCachedJsonResponseHandler(final CachedResponse cachedResponse) {
    this.cachedResponse = cachedResponse;
  }

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    final var headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "application/json");
    final var bytes = cachedResponse.response();
    exchange.sendResponseHeaders(200, bytes.length);
    try (exchange; final var os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }
}
