package software.sava.http_servers.helidon;

import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import software.sava.http_servers.core.response.QueryHandler;

import static software.sava.http_servers.helidon.ResponseUtil.writeResponse;

/// Bridges a [QueryHandler] onto a Helidon route. Blocking and non-blocking handlers share
/// this class: Helidon already runs each request on its own virtual thread and refuses a
/// response completed from any other, so there is no executor to hand the non-blocking
/// variant to.
final class HelidonQueryHandler implements Handler {

  private final QueryHandler queryHandler;

  HelidonQueryHandler(final QueryHandler queryHandler) {
    this.queryHandler = queryHandler;
  }

  @Override
  public void handle(final ServerRequest request, final ServerResponse response) {
    final var httpResponse = queryHandler.httpResponse(new HelidonRequest(request));
    writeResponse(response, httpResponse);
  }
}
