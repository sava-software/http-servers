package software.sava.http_servers.jdk;

import software.sava.http_servers.core.server.HttpServer;

final class JdkHttpServer implements HttpServer {

  private final com.sun.net.httpserver.HttpServer server;

  JdkHttpServer(final com.sun.net.httpserver.HttpServer server) {
    this.server = server;
  }

  @Override
  public void start() {
    server.start();
  }

  @Override
  public void stop() {
    // jdk.httpserver's only shutdown takes a grace period in seconds; zero closes the
    // listener and cuts in-flight exchanges immediately, which is the contract HttpServer
    // documents. Safe on a server that never started.
    server.stop(0);
  }
}
