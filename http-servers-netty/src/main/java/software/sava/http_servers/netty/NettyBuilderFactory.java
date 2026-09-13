package software.sava.http_servers.netty;

import software.sava.http_servers.core.server.HttpServerBuilder;
import software.sava.http_servers.core.server.HttpServerBuilderFactory;

public final class NettyBuilderFactory implements HttpServerBuilderFactory {

  @Override
  public HttpServerBuilder createBuilder() {
    return new NettyServerBuilder();
  }
}
