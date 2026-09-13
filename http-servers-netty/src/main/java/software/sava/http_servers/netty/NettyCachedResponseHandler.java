package software.sava.http_servers.netty;

import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.HttpResponse;

final class NettyCachedResponseHandler implements NettyHandler {

  private final CachedResponse cachedResponse;

  NettyCachedResponseHandler(final CachedResponse cachedResponse) {
    this.cachedResponse = cachedResponse;
  }

  @Override
  public boolean blocking() {
    // pre-encoded bytes never block; the response is written from the event loop
    return false;
  }

  @Override
  public HttpResponse handle(final NettyRequest request) {
    return HttpResponse.json(cachedResponse.response());
  }
}
