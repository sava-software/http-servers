package software.sava.http_servers.jetty;

import org.eclipse.jetty.io.Content;
import software.sava.http_servers.core.request.Request;

import java.io.IOException;
import java.io.UncheckedIOException;

final class JettyRequest implements Request {

  private final org.eclipse.jetty.server.Request request;

  JettyRequest(final org.eclipse.jetty.server.Request request) {
    this.request = request;
  }

  @Override
  public String method() {
    return request.getMethod();
  }

  @Override
  public String path() {
    return request.getHttpURI().getPath();
  }

  @Override
  public String query() {
    return request.getHttpURI().getQuery();
  }

  @Override
  public String header(final String name) {
    return request.getHeaders().get(name);
  }

  @Override
  public byte[] body() {
    try {
      final var buffer = Content.Source.asByteBuffer(request);
      final var bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      return bytes;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
