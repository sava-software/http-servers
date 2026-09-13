package software.sava.http_servers.helidon;

import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import software.sava.http_servers.core.response.CachedResponse;

import static software.sava.http_servers.helidon.ResponseUtil.writeJson;

final class HelidonCachedResponseHandler implements Handler {

  private final CachedResponse cachedResponse;

  HelidonCachedResponseHandler(final CachedResponse cachedResponse) {
    this.cachedResponse = cachedResponse;
  }

  @Override
  public void handle(final ServerRequest request, final ServerResponse response) {
    writeJson(response, cachedResponse.response());
  }
}
