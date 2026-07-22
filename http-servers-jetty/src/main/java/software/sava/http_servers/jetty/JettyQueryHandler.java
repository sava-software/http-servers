package software.sava.http_servers.jetty;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.sava.http_servers.core.response.QueryHandler;

import java.nio.ByteBuffer;

final class JettyQueryHandler extends BaseJettyHandler {

  private final QueryHandler queryHandler;

  JettyQueryHandler(final InvocationType invocationType, final QueryHandler queryHandler) {
    super(invocationType);
    this.queryHandler = queryHandler;
  }

  static JettyHandler createBlockingHandler(final QueryHandler queryHandler) {
    return new JettyQueryHandler(InvocationType.BLOCKING, queryHandler);
  }

  static JettyHandler createNonBlockingHandler(final QueryHandler queryHandler) {
    return new JettyQueryHandler(InvocationType.NON_BLOCKING, queryHandler);
  }

  @Override
  public boolean handle(final Request request, final Response response, final Callback callback) {
    final var httpResponse = queryHandler.httpResponse(new JettyRequest(request));

    final var responseHeaders = response.getHeaders();

    responseHeaders.put(new HttpField(HttpHeader.CONTENT_TYPE, httpResponse.contentType()));
    for (final var header : httpResponse.headers().entrySet()) {
      responseHeaders.put(header.getKey(), header.getValue());
    }

    response.setStatus(httpResponse.statusCode());
    response.write(true, ByteBuffer.wrap(httpResponse.body()), callback);
    return true;
  }
}
