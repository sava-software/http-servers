package software.sava.http_servers.fusionauth;

import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPResponse;
import software.sava.http_servers.core.response.CachedResponse;

import static software.sava.http_servers.fusionauth.ResponseUtil.writeResponse;

final class FusionAuthCachedResponseHandler implements HTTPHandler {

  private final CachedResponse cachedResponse;

  FusionAuthCachedResponseHandler(final CachedResponse cachedResponse) {
    this.cachedResponse = cachedResponse;
  }

  @Override
  public void handle(final HTTPRequest request, final HTTPResponse response) throws Exception {
    writeResponse(response, cachedResponse.response());
  }
}
