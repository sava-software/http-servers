package software.sava.http_servers.fusionauth;

import io.fusionauth.http.server.HTTPServer;
import software.sava.http_servers.core.server.HttpServer;

final class FusionAuthHttpServer implements HttpServer {

  private final HTTPServer server;

  FusionAuthHttpServer(final HTTPServer server) {
    this.server = server;
  }

  @Override
  public void start() {
    server.start();
  }
}
