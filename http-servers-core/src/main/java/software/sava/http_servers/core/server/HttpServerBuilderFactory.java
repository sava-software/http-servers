package software.sava.http_servers.core.server;

import java.util.ServiceLoader;

public interface HttpServerBuilderFactory {

  HttpServerBuilder createBuilder();

  static HttpServerBuilder findFirst() {
    return ServiceLoader.load(HttpServerBuilderFactory.class)
        .stream()
        .findFirst()
        .orElseThrow()
        .get()
        .createBuilder();
  }
}
