package software.sava.http_servers.fusionauth;

import software.sava.http_servers.core.server.HttpServerBuilder;
import software.sava.http_servers.core.server.HttpServerBuilderFactory;

public final class FusionAuthBuilderFactory implements HttpServerBuilderFactory {

  @Override
  public HttpServerBuilder createBuilder() {
    return new FusionAuthServerBuilder();
  }
}
