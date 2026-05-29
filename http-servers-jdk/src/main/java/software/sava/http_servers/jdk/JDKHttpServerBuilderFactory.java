package software.sava.http_servers.jdk;

import software.sava.http_servers.core.server.HttpServerBuilder;
import software.sava.http_servers.core.server.HttpServerBuilderFactory;

public final class JDKHttpServerBuilderFactory implements HttpServerBuilderFactory {

  @Override
  public HttpServerBuilder createBuilder() {
    return new JdkServerBuilder();
  }
}
