package software.sava.http_servers.jetty;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.sava.http_servers.core.handlers.CorsRequest;
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
      // the raw as-received path: getCanonicalPath() has already percent-decoded, and the
      // shared HandlerMap decodes during canonicalization — routing on the canonical form
      // would decode twice ("/a%2541" would route as "/aA" here and "/a%41" elsewhere)
      final var path = request.getHttpURI().getPath();
      final var requestHeaders = request.getHeaders();
      // the shared CORS rule (CorsRequest): a pre-flight names an origin and the method it asks
      // for, which is then the method to route; anything else, an OPTIONS missing either
      // header included, routes as itself, which nothing registered through the builder
      // answers, so it gets 405 with Allow as on the JDK backend
      final var cors = HttpMethod.OPTIONS.is(request.getMethod())
          ? CorsRequest.options(
              requestHeaders.get(HttpHeader.ORIGIN),
              requestHeaders.get(HttpHeader.ACCESS_CONTROL_REQUEST_METHOD),
              requestHeaders.get(HttpHeader.ACCESS_CONTROL_REQUEST_HEADERS))
          : CorsRequest.of(requestHeaders.get(HttpHeader.ORIGIN));
      final var method = cors.method(request.getMethod());

      final var lookup = handlerMap.lookupHandler(method, path);
      final var handler = lookup.handler();
      if (handler == null) {
        // as in the catch below: body still to come makes the head say Connection: close
        request.consumeAvailable();
        final var allowedMethods = lookup.allowedMethods();
        if (lookup.badRequest()) {
          // Jetty's UriCompliance rejects ambiguous targets before this handler runs; what
          // reaches this branch is a target it passes and the shared routing refuses, such as
          // the asterisk form (OPTIONS *), and any target a consumer's relaxed compliance allows
          response.setStatus(400);
          responseHeaders.put(JSON_CONTENT);
          Content.Sink.write(response, true, """
              {
                "msg": "Ambiguous request path."
              }""", callback
          );
        } else if (allowedMethods == null) {
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
        // a null value is a remove in HttpFields.Mutable, relied on for both echoes: a request
        // naming no origin (an absent or blank Origin) gets no Access-Control-Allow-Origin and
        // a pre-flight naming no request headers gets no Access-Control-Allow-Headers, pinned
        // by blankOriginIsNotReflected and preflightWithoutRequestHeadersOmitsAllowHeaders
        responseHeaders.put(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN, cors.origin());
        // the pre-flight branch: never entered without an origin (CorsRequest)
        if (cors.preflight()) {
          // the requested method resolved to a handler, so it is allowed; browsers
          // reject a pre-flight that does not name the method
          responseHeaders.put(HttpHeader.ACCESS_CONTROL_ALLOW_METHODS, method);
          responseHeaders.put(HttpHeader.ACCESS_CONTROL_ALLOW_HEADERS, cors.requestedHeaders());
          callback.succeeded();
          return true;
        }
        return handler.handle(request, response, callback);
      }
    } catch (final Throwable throwable) {
      logger.log(ERROR, "Failed to process request.", throwable);
      // answered here with the JSON the Helidon and Netty controllers send, not by failing the
      // callback into Jetty's error page. Consuming what has arrived first marks a connection
      // with body still to come as not persistent, so the head says Connection: close, as Jetty's
      // own error response did. reset() drops whatever was set before the throw, the CORS echo
      // included; on a committed response it throws, and Jetty aborts the exchange
      request.consumeAvailable();
      response.reset();
      response.setStatus(500);
      responseHeaders.put(JSON_CONTENT);
      Content.Sink.write(response, true, """
          {
            "msg": "Failed to process request."
          }""", callback
      );
      return true;
    }
  }
}
