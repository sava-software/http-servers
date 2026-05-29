module software.sava.http_servers.hello {
  requires java.net.http;

  requires transitive software.sava.http_servers.core;
//  requires software.sava.http_servers.fusionauth;
//  requires software.sava.http_servers.jdk;
//  requires software.sava.http_servers.jetty;

  uses software.sava.http_servers.core.server.HttpServerBuilderFactory;
}
