package software.sava.http_servers.jdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import software.sava.http_servers.core.response.QueryHandler;

import java.io.IOException;

/// One registered query or path handler. Blocking and non-blocking registrations are the same
/// thing here: jdk.httpserver already runs every exchange on the `Executor` given to
/// `HttpServer.setExecutor` (`ServerImpl.Dispatcher.handle`: `executor.execute (t)`,
/// ServerImpl.java:609), which [JdkServerBuilder] sets to the one handed to `createServer`,
/// so this method is entered on that executor's thread. Hopping to a second executor and
/// returning from [#handle] at once was the leak described on [JdkController]: a response
/// write that fails after `handle` has returned can reach neither `ServerImpl.closeConnection`
/// nor, unless its stream's flush succeeds, the `WriteFinished` event — the connection stays
/// registered with its channel open.
final class JdkQueryHandler implements HttpHandler {

  private final QueryHandler queryHandler;

  JdkQueryHandler(final QueryHandler queryHandler) {
    this.queryHandler = queryHandler;
  }

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    // a RuntimeException propagates to JdkController's guard, which answers 500; a failed body
    // read propagates as JdkRequest.BodyReadException, for which it closes the connection
    final var httpResponse = queryHandler.httpResponse(new JdkRequest(exchange));

    final var headers = exchange.getResponseHeaders();
    headers.set("Content-Type", httpResponse.contentType());
    for (final var header : httpResponse.headers().entrySet()) {
      headers.set(header.getKey(), header.getValue());
    }

    final var body = httpResponse.body();
    final int statusCode = httpResponse.statusCode();
    if (statusCode == 204 || statusCode == 304) {
      // bodyless statuses take contentLen -1; any other value makes the jdk server force
      // -1 itself and log a warning about the correction
      try (exchange) {
        JdkController.answerWithoutBody(exchange, statusCode);
      }
    } else {
      exchange.sendResponseHeaders(statusCode, body.length);
      try (exchange; final var os = exchange.getResponseBody()) {
        os.write(body);
      }
    }
  }
}
