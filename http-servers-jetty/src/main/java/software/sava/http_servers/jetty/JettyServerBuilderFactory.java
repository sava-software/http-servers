package software.sava.http_servers.jetty;

import software.sava.http_servers.core.server.HttpServerBuilder;
import software.sava.http_servers.core.server.HttpServerBuilderFactory;

public final class JettyServerBuilderFactory implements HttpServerBuilderFactory {

  @Override
  public HttpServerBuilder createBuilder() {
    return new JettyServerBuilder();
  }
}
