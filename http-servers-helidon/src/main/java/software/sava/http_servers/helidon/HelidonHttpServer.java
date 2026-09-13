package software.sava.http_servers.helidon;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;
import software.sava.http_servers.core.server.HttpServer;

import java.io.IOException;

/// Owns the Helidon `WebServer` lifecycle. Divergences from the other backends:
///
/// - The `WebServer` is built inside [#start()], not by the builder: Helidon cannot restart a
///   stopped `WebServer`, so the configuration is kept and the server instance is created on
///   demand. A second `start()` throws `IllegalStateException` rather than silently doing
///   nothing (Helidon's own second `start()` on a running server is a no-op).
/// - `WebServer.start()` does not throw on a bind failure: it logs SEVERE and returns with
///   `isRunning() == false`. No cause is available, so [#start()] converts that into an
///   `IOException` naming the address, which is what the `HttpServer` contract expects.
/// - [#stop()] is immediate (the listener is configured with a zero grace period) and cuts
///   in-flight requests; on a server that never started, or whose start failed, it is a no-op.
final class HelidonHttpServer implements HttpServer {

  private final WebServerConfig.Builder config;
  private WebServer server;

  HelidonHttpServer(final WebServerConfig.Builder config) {
    this.config = config;
  }

  private String address() {
    return config.host() + ':' + config.port();
  }

  @Override
  public synchronized void start() throws IOException {
    if (server != null) {
      throw new IllegalStateException("Helidon server already started on " + address());
    }
    final var webServer = config.build();
    webServer.start();
    if (!webServer.isRunning()) {
      throw new IOException("Helidon listener failed to start on " + address()
          + "; WebServer.start() logs the failure (typically a bind conflict) instead of throwing");
    }
    server = webServer;
  }

  @Override
  public synchronized void stop() {
    final var webServer = server;
    if (webServer != null) {
      webServer.stop();
    }
  }
}
