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
    final var handler = handlerMap.lookupHandler(path);
    if (handler == null) {
      response.setContentType("application/json");
      ResponseUtil.writeResponse(404, response, """
          {
            "msg": "No handler for path."
          }"""
      );
    } else {
      final var origin = request.getHeader("Origin");
      if (origin != null) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        // if pre-flight check.
        final var requestHeaders = request.getHeaders();
        if (HTTPMethod.OPTIONS.is(request.getMethod()) && requestHeaders.containsKey("Access-Control-Request-Method")) {
          response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"));
          // return handler.handlePreFlight(responseHeaders, callback);
          return;
        }
      }
      handler.handle(request, response);
    }
  }
}
