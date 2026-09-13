package software.sava.http_servers.helidon;

import software.sava.http_servers.core.server.HttpServerBuilder;
import software.sava.http_servers.core.server.HttpServerBuilderFactory;

public final class HelidonBuilderFactory implements HttpServerBuilderFactory {

  @Override
  public HttpServerBuilder createBuilder() {
    return new HelidonServerBuilder();
  }
}
