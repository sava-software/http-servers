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
}
