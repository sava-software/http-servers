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

  @Override
  public void stop() throws Exception {
    // Jetty's LifeCycle tolerates a stop from any state, including the FAILED one a bind
    // conflict leaves behind, so this stays a no-op rather than a second failure.
    server.stop();
  }
}
