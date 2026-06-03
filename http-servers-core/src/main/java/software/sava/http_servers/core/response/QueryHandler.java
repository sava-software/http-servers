package software.sava.http_servers.core.response;

import software.sava.http_servers.core.request.Request;

public interface QueryHandler {

  @Deprecated
  HttpResponse httpResponse(final String path, final String query);

  default HttpResponse httpResponse(final Request request) {
    return httpResponse(request.path(), request.query());
  }
}
