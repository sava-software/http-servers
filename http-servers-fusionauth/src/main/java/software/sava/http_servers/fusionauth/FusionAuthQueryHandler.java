package software.sava.http_servers.fusionauth;

import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPResponse;
import software.sava.http_servers.core.response.QueryHandler;

import static software.sava.http_servers.fusionauth.ResponseUtil.writeResponse;

final class FusionAuthQueryHandler implements HTTPHandler {

  private final QueryHandler queryHandler;

  FusionAuthQueryHandler(final QueryHandler queryHandler) {
    this.queryHandler = queryHandler;
  }

  @Override
  public void handle(final HTTPRequest request, final HTTPResponse response) throws Exception {
    final var httpResponse = queryHandler.httpResponse(request.getPath(), request.getQueryString());
    writeResponse(response, httpResponse);
  }
}
