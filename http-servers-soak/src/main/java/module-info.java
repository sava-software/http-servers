module software.sava.http_servers.soak {
  requires java.logging;
  requires java.management;
  requires java.net.http;
  requires jdk.jfr;

  requires transitive software.sava.http_servers.core;

  uses software.sava.http_servers.core.server.HttpServerBuilderFactory;
}
