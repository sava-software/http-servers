package software.sava.http_servers.jetty;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.sava.http_servers.core.response.CachedResponse;

import java.nio.ByteBuffer;

final class JettyCachedJsonResponseHandler extends BaseJettyHandler {

  private final CachedResponse cachedResponse;

  JettyCachedJsonResponseHandler(final CachedResponse cachedResponse) {
    super(InvocationType.NON_BLOCKING, ALLOW_GET);
    this.cachedResponse = cachedResponse;
  }

  @Override
  public boolean handle(final Request request, final Response response, final Callback callback) {
    super.handle(request, response, callback);

    final var responseHeaders = response.getHeaders();
    responseHeaders.put(JSON_CONTENT);

    final byte[] cachedJson = cachedResponse.response();
    response.write(true, ByteBuffer.wrap(cachedJson), callback);

    return true;
  }
}
