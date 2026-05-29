import software.sava.http_servers.core.server.HttpServerBuilderFactory;
import software.sava.http_servers.jetty.JettyServerBuilderFactory;

module software.sava.http_servers.jetty {
  requires java.net.http;

  requires transitive software.sava.http_servers.core;

  requires org.eclipse.jetty.compression.server;
  requires static org.eclipse.jetty.compression.gzip;
//  requires static org.eclipse.jetty.compression.brotli;
//  requires static org.eclipse.jetty.compression.zstandard;
  requires transitive org.eclipse.jetty.http;
  requires org.eclipse.jetty.http2.server;
  requires org.eclipse.jetty.io;
  requires transitive org.eclipse.jetty.server;
  requires transitive org.eclipse.jetty.util;

  provides HttpServerBuilderFactory with JettyServerBuilderFactory;
}
