package software.sava.http_servers.jdk;

import com.sun.net.httpserver.HttpExchange;
import software.sava.http_servers.core.request.Request;

import java.io.IOException;
import java.io.UncheckedIOException;

final class JdkRequest implements Request {

  private final HttpExchange exchange;

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
    try (final var is = exchange.getRequestBody()) {
      return is.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
