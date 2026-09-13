package software.sava.http_servers.netty;

import software.sava.http_servers.core.response.HttpResponse;

/// The backend's handler type: a route bridged from the core API. Unlike the other backends
/// there is no framework response object to write into — the controller owns the channel,
/// so a handler produces the [HttpResponse] and the controller puts it on the wire.
interface NettyHandler {

  /// `true` when the route may block and must leave the event loop for the executor handed
  /// to `createServer`; `false` runs it inline on the event loop.
  boolean blocking();

  HttpResponse handle(final NettyRequest request);
}
