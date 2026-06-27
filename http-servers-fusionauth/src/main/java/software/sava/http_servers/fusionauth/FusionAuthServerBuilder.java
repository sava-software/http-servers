package software.sava.http_servers.fusionauth;

import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServer;
import software.sava.http_servers.core.handlers.HandlerMap;
import software.sava.http_servers.core.response.CachedResponse;
import software.sava.http_servers.core.response.QueryHandler;
import software.sava.http_servers.core.server.BaseHttpServerBuilder;
import software.sava.http_servers.core.server.HttpServer;
import software.sava.http_servers.fusionauth.logging.FusionAuthJulLoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.Executor;

public class FusionAuthServerBuilder extends BaseHttpServerBuilder<HTTPHandler, HTTPServer> {

  @Override
  protected HTTPServer initRestServer(final Executor executor, final String host, final int port) {
    try {
      final HTTPListenerConfiguration listenerConfiguration;
      if (host == null || host.isBlank()) {
        listenerConfiguration = new HTTPListenerConfiguration(port);
      } else {
        listenerConfiguration = new HTTPListenerConfiguration(InetAddress.getByName(host), port);
      }
      final var server = new HTTPServer();
      server.withListener(listenerConfiguration);
      server.configuration().withLoggerFactory(new FusionAuthJulLoggerFactory());
      return server;
    } catch (final UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected HttpServer createServer(final HTTPServer server) {
    return new FusionAuthHttpServer(server);
  }

  @Override
  protected void setController(final HTTPServer server, final HandlerMap<HTTPHandler> handlerMap) {
    final var controller = new FusionAuthController(handlerMap);
    server.withHandler(controller);
  }

  @Override
  protected HTTPHandler cachedResponse(final CachedResponse cachedResponse) {
    return new FusionAuthCachedResponseHandler(cachedResponse);
  }

  @Override
  protected HTTPHandler nonBlockingGet(final QueryHandler nonBlockingGetHandler) {
    return new FusionAuthQueryHandler(nonBlockingGetHandler);
  }

  @Override
  protected HTTPHandler blockingGet(final QueryHandler blockingGetHandler) {
    return new FusionAuthQueryHandler(blockingGetHandler);
  }

  @Override
  protected HTTPHandler nonBlockingPost(final QueryHandler nonBlockingPostHandler) {
    return new FusionAuthQueryHandler(nonBlockingPostHandler);
  }

  @Override
  protected HTTPHandler blockingPost(final QueryHandler blockingPostHandler) {
    return new FusionAuthQueryHandler(blockingPostHandler);
  }
}
