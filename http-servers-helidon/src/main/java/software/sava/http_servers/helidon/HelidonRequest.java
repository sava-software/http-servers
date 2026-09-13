package software.sava.http_servers.helidon;

import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.ServerRequest;
import software.sava.http_servers.core.request.Request;

import java.io.IOException;
import java.io.UncheckedIOException;

final class HelidonRequest implements Request {

  private final ServerRequest request;

  HelidonRequest(final ServerRequest request) {
    this.request = request;
  }

  @Override
  public String method() {
    return request.prologue().method().text();
  }

  @Override
  public String path() {
    // path() percent-decodes; the Request contract is the raw path, matching query()
    return request.path().rawPath();
  }

  @Override
  public String query() {
    // Helidon's UriQuery is never null and a request without a query (or with a bare '?')
    // carries UriQuery.empty(); the Request contract is null for none. The JDK backend hands
    // a bare '?' on as "" — the one shape the two disagree on.
    final var query = request.query().rawValue();
    return query.isEmpty() ? null : query;
  }

  @Override
  public String header(final String name) {
    // header names are case-insensitive by construction: HeaderNames.create lowercases
    return request.headers().first(HeaderNames.create(name)).orElse(null);
  }

  @Override
  public byte[] body() {
    // a request without an entity hands back an empty stream (as(byte[].class) would throw
    // "No entity" instead), so the never-null contract needs no guard here
    try (final var is = request.content().inputStream()) {
      return is.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
