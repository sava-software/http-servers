package software.sava.http_servers.jetty;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.sava.http_servers.core.handlers.HandlerMap;

import static java.lang.System.Logger.Level.ERROR;

final class JettyController extends Handler.Sequence {

  static final HttpField JSON_CONTENT = new HttpField(HttpHeader.CONTENT_TYPE, "application/json");
  private static final System.Logger logger = System.getLogger(JettyController.class.getName());

  private final HandlerMap<Handler> handlerMap;

  JettyController(final HandlerMap<Handler> handlerMap) {
    this.handlerMap = handlerMap;
  }

  @Override
  public boolean handle(final Request request, final Response response, final Callback callback) {
    final var responseHeaders = response.getHeaders();
    try {
      final var path = request.getHttpURI().getCanonicalPath();
      final var requestHeaders = request.getHeaders();
      final String accessControlRequestMethod;
      final boolean preFlight;
      if (HttpMethod.OPTIONS.is(request.getMethod())) {
        accessControlRequestMethod = requestHeaders.get(HttpHeader.ACCESS_CONTROL_REQUEST_METHOD);
        preFlight = accessControlRequestMethod != null && !accessControlRequestMethod.isBlank();
      } else {
        accessControlRequestMethod = null;
        preFlight = false;
      }
      final var method = preFlight
          ? accessControlRequestMethod
          : request.getMethod();

      final var lookup = handlerMap.lookupHandler(method, path);
      final var handler = lookup.handler();
      if (handler == null) {
        final var allowedMethods = lookup.allowedMethods();
        if (allowedMethods == null) {
          response.setStatus(404);
          responseHeaders.put(JSON_CONTENT);
          Content.Sink.write(response, true, """
              {
                "msg": "No handler for path."
              }""", callback
          );
        } else {
          response.setStatus(405);
          responseHeaders.put(HttpHeader.ALLOW, allowedMethods);
          responseHeaders.put(JSON_CONTENT);
          Content.Sink.write(response, true, """
              {
                "msg": "Method not allowed."
              }""", callback
          );
        }
        return true;
      } else {
        final var origin = requestHeaders.get(HttpHeader.ORIGIN);
        if (origin != null) {
          responseHeaders.put(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
          // if pre-flight check.
          if (preFlight) {
            // the requested method resolved to a handler, so it is allowed; browsers
            // reject a pre-flight that does not name the method
            responseHeaders.put(HttpHeader.ACCESS_CONTROL_ALLOW_METHODS, method);
            responseHeaders.put(HttpHeader.ACCESS_CONTROL_ALLOW_HEADERS, requestHeaders.get(HttpHeader.ACCESS_CONTROL_REQUEST_HEADERS));
            callback.succeeded();
            return true;
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
