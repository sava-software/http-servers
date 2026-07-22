package software.sava.http_servers.jetty;

import org.eclipse.jetty.server.Server;
import software.sava.http_servers.core.server.HttpServer;

final class JettyHttpServer implements HttpServer {

  private final Server server;

  JettyHttpServer(final Server server) {
    this.server = server;
  }

  @Override
  public void start() throws Exception {
    server.start();
  }
}
