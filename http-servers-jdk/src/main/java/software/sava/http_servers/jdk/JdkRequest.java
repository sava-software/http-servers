package software.sava.http_servers.jdk;

import com.sun.net.httpserver.HttpExchange;
import software.sava.http_servers.core.request.Request;

import java.io.IOException;
import java.io.UncheckedIOException;

/// The request body is read from the socket once, on the first [#body()] call, and the same
/// bytes are handed back to every later call — as the Netty and FusionAuth backends do, which
/// hold the body they already received. jdk.httpserver's request stream is single-shot (a read
/// after `close()` fails with "Stream is closed"), so without the copy a handler's second read
/// would look exactly like a client that left mid-upload.
final class JdkRequest implements Request {

  /// The request body could not be read to its declared end — by [#body()], or by the drain
  /// [JdkController#answerWithoutBody] runs before a bodyless head: the client reset or closed
  /// the connection mid-upload, or the socket failed. [JdkController] tells it apart from an
  /// `UncheckedIOException` a handler raises for its own reasons — that one is a handler
  /// failure and is answered `500`; this one is logged at `DEBUG`, never answered, and its
  /// cause escapes `handle()` so jdk.httpserver closes and unregisters the connection.
  static final class BodyReadException extends UncheckedIOException {

    BodyReadException(final IOException cause) {
      super(cause);
    }
  }

  private final HttpExchange exchange;
  private byte[] body;

  JdkRequest(final HttpExchange exchange) {
    this.exchange = exchange;
  }

  @Override
  public String method() {
    return exchange.getRequestMethod();
  }

  @Override
  public String path() {
    // getPath() percent-decodes; the Request contract is the raw path, matching query()
    return exchange.getRequestURI().getRawPath();
  }

  @Override
  public String query() {
    // getQuery() percent-decodes, which would corrupt boundary scans downstream; the
    // Request contract is the raw query string.
    return exchange.getRequestURI().getRawQuery();
  }

  @Override
  public String header(final String name) {
    return exchange.getRequestHeaders().getFirst(name);
  }

  @Override
  public byte[] body() {
    if (body == null) {
      try (final var is = exchange.getRequestBody()) {
        body = is.readAllBytes();
      } catch (final IOException e) {
        throw new BodyReadException(e);
      }
    }
    return body;
  }
}
