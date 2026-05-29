import software.sava.http_servers.core.server.HttpServerBuilderFactory;
import software.sava.http_servers.jdk.JDKHttpServerBuilderFactory;

module software.sava.http_servers.jdk {
  requires java.net.http;

  requires transitive software.sava.http_servers.core;
  requires jdk.httpserver;

  provides HttpServerBuilderFactory with JDKHttpServerBuilderFactory;
}
