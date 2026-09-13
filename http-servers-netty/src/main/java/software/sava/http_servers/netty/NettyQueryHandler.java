package software.sava.http_servers.netty;

import software.sava.http_servers.core.response.HttpResponse;
import software.sava.http_servers.core.response.QueryHandler;

final class NettyQueryHandler implements NettyHandler {

  private final boolean blocking;
  private final QueryHandler queryHandler;

  private NettyQueryHandler(final boolean blocking, final QueryHandler queryHandler) {
    this.blocking = blocking;
    this.queryHandler = queryHandler;
  }

  static NettyHandler createBlockingGetHandler(final QueryHandler queryHandler) {
    return new NettyQueryHandler(true, queryHandler);
  }

  static NettyHandler createNonBlockingGetHandler(final QueryHandler queryHandler) {
    return new NettyQueryHandler(false, queryHandler);
  }

  static NettyHandler createBlockingPostHandler(final QueryHandler queryHandler) {
    return new NettyQueryHandler(true, queryHandler);
  }

  static NettyHandler createNonBlockingPostHandler(final QueryHandler queryHandler) {
    return new NettyQueryHandler(false, queryHandler);
  }

  @Override
  public boolean blocking() {
    return blocking;
  }

  @Override
  public HttpResponse handle(final NettyRequest request) {
    return queryHandler.httpResponse(request);
  }
}
