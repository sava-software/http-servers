package software.sava.http_servers.jetty;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.sava.http_servers.core.response.QueryHandler;

import java.nio.ByteBuffer;

final class JettyQueryHandler extends BaseJettyHandler {

  private final QueryHandler queryHandler;

  JettyQueryHandler(final InvocationType invocationType, final HttpField allowMethod, final QueryHandler queryHandler) {
    super(invocationType, allowMethod);
    this.queryHandler = queryHandler;
  }

  static JettyHandler createGetHandler(final InvocationType invocationType, final QueryHandler queryHandler) {
    return new JettyQueryHandler(invocationType, ALLOW_GET, queryHandler);
  }

  static JettyHandler createPostHandler(final InvocationType invocationType, final QueryHandler queryHandler) {
    return new JettyQueryHandler(invocationType, ALLOW_POST, queryHandler);
  }

  static JettyHandler createBlockingGetHandler(final QueryHandler queryHandler) {
    return createGetHandler(InvocationType.BLOCKING, queryHandler);
  }

  static JettyHandler createNonBlockingGetHandler(final QueryHandler queryHandler) {
    return createGetHandler(InvocationType.NON_BLOCKING, queryHandler);
  }

  static JettyHandler createBlockingPostHandler(final QueryHandler queryHandler) {
    return createPostHandler(InvocationType.BLOCKING, queryHandler);
  }

  static JettyHandler createNonBlockingPostHandler(final QueryHandler queryHandler) {
    return createPostHandler(InvocationType.NON_BLOCKING, queryHandler);
  }

  @Override
  public boolean handle(final Request request, final Response response, final Callback callback) {
    super.handle(request, response, callback);

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

  @Override
  public boolean handlePreFlight(final HttpFields.Mutable responseHeaders, final Callback callback) {
    responseHeaders.put(HttpHeader.ACCESS_CONTROL_ALLOW_METHODS, allowMethod.getValue());
    return super.handlePreFlight(responseHeaders, callback);
  }
}
