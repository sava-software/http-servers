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
    // getHeader is case-insensitive; the raw header map is keyed lowercase, so a
    // containsKey probe with the canonical name would never match
    final String accessControlRequestMethod;
    final boolean preFlight;
    if (HTTPMethod.OPTIONS.is(request.getMethod())) {
      accessControlRequestMethod = request.getHeader("Access-Control-Request-Method");
      preFlight = accessControlRequestMethod != null && !accessControlRequestMethod.isBlank();
    } else {
      accessControlRequestMethod = null;
      preFlight = false;
    }
    final var method = preFlight
        ? accessControlRequestMethod
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
          // the requested method resolved to a handler, so it is allowed; without this
          // header browsers reject the pre-flight
          response.setHeader("Access-Control-Allow-Methods", method);
          response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"));
          return;
        }
      }
      handler.handle(request, response);
    }
  }
}
