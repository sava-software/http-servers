package software.sava.http_servers.jetty;

import org.eclipse.jetty.server.Server;
import software.sava.http_servers.core.server.HttpServer;

final class JettyHttpSever implements HttpServer {

  private final Server server;

  JettyHttpSever(final Server server) {
    this.server = server;
  }

  @Override
  public void start() throws Exception {
    server.start();
  }
}
