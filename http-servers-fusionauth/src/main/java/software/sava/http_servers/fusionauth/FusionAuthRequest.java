package software.sava.http_servers.fusionauth;

import io.fusionauth.http.server.HTTPRequest;
import software.sava.http_servers.core.request.Request;

final class FusionAuthRequest implements Request {

  private final HTTPRequest request;

  FusionAuthRequest(final HTTPRequest request) {
    this.request = request;
  }

  @Override
  public String path() {
    return request.getPath();
  }

  @Override
  public String query() {
    return request.getQueryString();
  }

  @Override
  public String header(final String name) {
    return request.getHeader(name);
  }

  @Override
  public byte[] body() {
    final var body = request.getBodyBytes();
    return body == null ? new byte[0] : body;
  }
}
