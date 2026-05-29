import software.sava.http_servers.core.server.HttpServerBuilderFactory;
import software.sava.http_servers.fusionauth.FusionAuthBuilderFactory;

module software.sava.http_servers.fusionauth {
  requires java.logging;
  requires java.net.http;

  requires transitive software.sava.http_servers.core;

  requires transitive io.fusionauth.http;

  provides HttpServerBuilderFactory with FusionAuthBuilderFactory;
}
