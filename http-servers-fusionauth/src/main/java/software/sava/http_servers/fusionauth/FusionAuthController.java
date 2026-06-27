package software.sava.http_servers.fusionauth;

import io.fusionauth.http.HTTPMethod;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPResponse;
import software.sava.http_servers.core.handlers.HandlerMap;

final class FusionAuthController implements HTTPHandler {

  private final HandlerMap<HTTPHandler> handlerMap;

  FusionAuthController(final HandlerMap<HTTPHandler> handlerMap) {
    this.handlerMap = handlerMap;
  }

  @Override
  public void handle(final HTTPRequest request, final HTTPResponse response) throws Exception {
    final var path = request.getPath();
    final var requestHeaders = request.getHeaders();
    final var preFlight = HTTPMethod.OPTIONS.is(request.getMethod())
        && requestHeaders.containsKey("Access-Control-Request-Method");
    final var method = preFlight
        ? request.getHeader("Access-Control-Request-Method")
        : String.valueOf(request.getMethod());

    final var lookup = handlerMap.lookupHandler(method, path);
    final var handler = lookup.handler();
    if (handler == null) {
      final var allowedMethods = lookup.allowedMethods();
      if (allowedMethods == null) {
        ResponseUtil.writeResponse(404, response, """
            {
              "msg": "No handler for path."
            }"""
        );
      } else {
        response.setHeader("Allow", allowedMethods);
        ResponseUtil.writeResponse(405, response, """
            {
              "msg": "Method not allowed."
            }"""
        );
      }
    } else {
      final var origin = request.getHeader("Origin");
      if (origin != null) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        // if pre-flight check.
        if (preFlight) {
          response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"));
          // return handler.handlePreFlight(responseHeaders, callback);
          return;
        }
      }
      handler.handle(request, response);
    }
  }
}
