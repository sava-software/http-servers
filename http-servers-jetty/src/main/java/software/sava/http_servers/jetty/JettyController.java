package software.sava.http_servers.jetty;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.sava.http_servers.core.handlers.HandlerMap;

import static java.lang.System.Logger.Level.ERROR;
import static software.sava.http_servers.jetty.BaseJettyHandler.JSON_CONTENT;

final class JettyController extends Handler.Sequence {

  private static final System.Logger logger = System.getLogger(JettyController.class.getName());

  private final HandlerMap<JettyHandler> handlerMap;

  JettyController(final HandlerMap<JettyHandler> handlerMap) {
    this.handlerMap = handlerMap;
  }

  @Override
  public boolean handle(final Request request, final Response response, final Callback callback) {
    final var responseHeaders = response.getHeaders();
    try {
      final var path = request.getHttpURI().getCanonicalPath();
      final var handler = handlerMap.lookupHandler(path);
      if (handler == null) {
        response.setStatus(404);
        responseHeaders.put(JSON_CONTENT);
        Content.Sink.write(response, true, """
            {
              "msg": "No handler for path."
            }""", callback
        );
        return true;
      } else {
        final var requestHeaders = request.getHeaders();
        final var origin = requestHeaders.get(HttpHeader.ORIGIN);
        if (origin != null) {
          responseHeaders.put(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
          // if pre-flight check.
          if (HttpMethod.OPTIONS.is(request.getMethod()) && requestHeaders.contains(HttpHeader.ACCESS_CONTROL_REQUEST_METHOD)) {
            responseHeaders.put(HttpHeader.ACCESS_CONTROL_ALLOW_HEADERS, requestHeaders.get(HttpHeader.ACCESS_CONTROL_REQUEST_HEADERS));
            return handler.handlePreFlight(responseHeaders, callback);
          }
        }
        return handler.handle(request, response, callback);
      }
    } catch (final Throwable throwable) {
      logger.log(ERROR, "Failed to process request.", throwable);
      response.setStatus(500);
      callback.failed(throwable);
      return true;
    }
  }
}
